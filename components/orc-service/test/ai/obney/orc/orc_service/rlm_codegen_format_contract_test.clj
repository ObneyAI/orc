(ns ai.obney.orc.orc-service.rlm-codegen-format-contract-test
  "Researcher code guidance describes the value; the selected SIO protocol owns
   its envelope. Verify actual requests and typed results through public APIs."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def proof (atom []))

(defn- fixture! []
  (let [requests (atom []) counts (atom {})
        pool (java.util.concurrent.Executors/newFixedThreadPool 2)
        server (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server pool)
    (.createContext server "/chat/completions"
      (reify com.sun.net.httpserver.HttpHandler
        (handle [_ exchange]
          (try
            (let [raw (slurp (.getRequestBody exchange))
                  body (json/parse-string raw true)
                  prime? (= "fixture/prime" (:model body))
                  n (get (swap! counts update (:model body) (fnil inc 0)) (:model body))
                  outputs (if prime?
                            {:reasoning "Execute the controlled task."
                             :code (if (= n 1)
                                     "(do (llm \"inline\" :instruction \"Return inline value\" :writes [:inline-value]) (emit-tree! [:sequence [:llm {:instruction \"Return emitted value\" :reads [] :writes [:emitted-value] :output-schemas {:emitted-value :string}}] [:final {:keys [:emitted-value]}]]))"
                                     "(final! {:summary (str (get-var :inline-value) \"/\" (get-var :emitted-value))})")}
                            (if (= n 1) {:inline-value "inline"} {:emitted-value "emitted"}))
                  function? (contains? body :tools)
                  content (if (:response_format body)
                            (json/generate-string outputs)
                            (str (str/join "\n" (map (fn [[k v]] (str "[[ ## " (name k) " ## ]]\n" v)) outputs))
                                 "\n[[ ## completed ## ]]"))
                  message (if function?
                            {:role "assistant" :content nil
                             :tool_calls [{:id "controlled" :type "function"
                                           :function {:name "submit_response" :arguments (json/generate-string outputs)}}]}
                            {:role "assistant" :content content})
                  response (json/generate-string {:model (:model body)
                              :choices [{:index 0 :finish_reason (if function? "tool_calls" "stop") :message message}]
                              :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})
                  bytes (.getBytes response "UTF-8")]
              (swap! requests conj {:body body :raw-body raw :response-body response})
              (.add (.getResponseHeaders exchange) "Content-Type" "application/json")
              (.sendResponseHeaders exchange 200 (alength bytes))
              (with-open [out (.getResponseBody exchange)] (.write out bytes)))
            (finally (.close exchange))))))
    (.start server)
    {:server server :pool pool :requests requests :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))}))

(deftest researcher-and-inherited-specialists-use-one-output-envelope
  (reset! proof [])
  (doseq [mode [:marker :function :node-strict :provider-strict]]
    (testing (name mode)
      (let [{:keys [server pool requests url]} (fixture!)
            selected (case mode :marker {:use-function-calling? false}
                                :function {:use-function-calling? true}
                                :node-strict {:output-mode :json-schema}
                                :provider-strict {})]
        (try
          (llm/register-provider! :researcher-envelope
            (cond-> {:provider :openrouter :model "fixture/prime"
                     :config {:api-base url :api-key "CONTROLLED-NOT-A-REAL-KEY" :timeout 10000}}
              (= mode :provider-strict) (assoc :output-mode :json-schema)))
          (h/with-async-test-context [ctx {:context {:tenant-id (random-uuid) :llm-provider :researcher-envelope}}]
            (let [sheet (orc/build-workflow! ctx
                          (orc/workflow (str "researcher-envelope-" (name mode))
                            (orc/blackboard {:summary :string :inline-value :string})
                            (orc/repl-researcher "research" :model "fixture/prime"
                              :instruction "Complete the controlled specialist task." :writes [:summary]
                              :max-iterations 2 :options (merge {:max-retries 0 :max-tokens 32768} selected)
                              :rlm {:recursive? true :checkpointed? true
                                    :sub-model "fixture/specialist" :sub-options selected})))
                  result (orc/execute ctx sheet {} :timeout-ms 30000 :max-ticks 1 :llm-call-budget 4)
                  events (orc/value-log-read-tick-events ctx (:tenant-id ctx) (:trace-id result))
                  bodies (mapv :body @requests)
                  prime-bodies (filterv #(= "fixture/prime" (:model %)) bodies)
                  strict? (contains? #{:node-strict :provider-strict} mode)]
              (swap! proof conj {:mode mode :result result :events events :requests @requests})
              (is (= :success (:status result)) (pr-str result))
              (is (= "inline/emitted" (get-in result [:outputs :summary])))
              (is (= :success (:root-status (orc/get-tick ctx (:trace-id result)))))
              (is (= ["fixture/prime" "fixture/specialist" "fixture/specialist" "fixture/prime"]
                     (mapv :model bodies)))
              (doseq [body bodies]
                (is (= strict? (= "json_schema" (get-in body [:response_format :type]))))
                (is (= (= mode :function) (contains? body :tools)))
                (when strict?
                  (is (= true (get-in body [:response_format :json_schema :strict])))
                  (is (= true (get-in body [:provider :require_parameters])))))
              (doseq [body prime-bodies
                      :let [prompt (get-in body [:messages 0 :content])]]
                (is (not (str/includes? prompt "Your response MUST start with"))
                    "The researcher must not impose a competing output envelope")
                (is (not (str/includes? prompt "Call the provided function with your answer."))
                    "Only the selected SIO function protocol requests the response tool")
                (is (str/includes? prompt "RAW Clojure code") "Code value guidance survives in every mode")
                (is (re-find #"(?i)no markdown code fences|no ```" prompt))
                (if (= mode :marker)
                  (is (str/includes? prompt "[[ ## code ## ]]"))
                  (is (not (str/includes? prompt "[[ ## code ## ]]")))))))
          (finally (.stop server 0) (.shutdownNow pool)))))))
