(ns ai.obney.orc.orc-service.inline-input-contract-test
  "MODEL-CONTRACT-7 / DeclaredMeaningReachesTheModel. Real public runtime,
   SQLite, SIO and HTTP adapter; only the remote provider is a local fixture."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def proof (atom []))

(def payload-schema
  [:map {:description "Supplied record collection."}
   [:records [:vector {:description "Records in source order."}
              [:map
               [:status [:enum {:description "Lifecycle status supplied by the source."} "active" "retired"]]
               [:amount [:int {:description "Exact unit count; zero is meaningful."}]]
               [:enabled [:boolean {:description "Whether this record is enabled."}]]
               [:note [:maybe {:description "Source annotation; null means no annotation."} :string]]
               [:hidden {:optional true} [:string {:description "UNSHOWN-NESTED-MEANING"}]]]]]])
(def payload {:records [{:status "active" :amount 0 :enabled false :note nil}]})
(def image-url "https://fixture.invalid/synthetic.png")
(def read-keys [:payload :nullable :blank :override :absent :image])
(def board
  {:payload payload-schema
   :nullable [:maybe {:description "A supplied nullable input."} :string]
   :blank [:string {:description "A supplied empty string."}]
   :override [:maybe {:description "The current override, including explicit null."} :string]
   :absent [:maybe {:description "UNSHOWN-TOP-LEVEL-MEANING"} :string]
   :image [:vector {:field-type :image} :string]
   :computed [:map {:description "A computed record with an authored contract."}
              [:count [:int {:description "Computed count in units."}]]]
   :ordinary-result :string :inline-result :string :research-result :string})

(defn- fixture! [code]
  (let [requests (atom []) errors (atom [])
        pool (java.util.concurrent.Executors/newFixedThreadPool 2)
        server (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server pool)
    (.createContext server "/chat/completions"
      (reify com.sun.net.httpserver.HttpHandler
        (handle [_ exchange]
          (try
            (let [raw (slurp (.getRequestBody exchange)) body (json/parse-string raw true)
                  outputs (case (:model body)
                            "fixture/prime" {:reasoning "Execute the controlled public boundary." :code code}
                            "fixture/ordinary" {:ordinary-result "ordinary-ok"}
                            "fixture/inline" {:inline-result "inline-ok"})
                  function? (contains? body :tools)
                  content (if (:response_format body) (json/generate-string outputs)
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
            (catch Throwable e (swap! errors conj {:class (.getName (class e))}))
            (finally (.close exchange))))))
    (.start server)
    {:server server :pool pool :requests requests :errors errors
     :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))}))

(defn- text-content [body]
  (let [content (get-in body [:messages 0 :content])]
    (if (string? content) content
      (str/join "\n" (keep #(when (= "text" (:type %)) (:text %)) content)))))

(defn- value-text [prompt mode k]
  (let [label (if (= mode :marker) (str "[[ ## " (name k) " ## ]]\n") (str (name k) ": "))
        offset (.lastIndexOf ^String prompt ^String label)]
    (when (<= 0 offset)
      (let [start (+ offset (count label)) end (.indexOf ^String prompt "\n\n" start)]
        (subs prompt start (if (neg? end) (count prompt) end))))))

(defn- json-value [s]
  (try (when s (json/parse-string s true))
       (catch Exception _ {:invalid-json s})))

(deftest inline-and-ordinary-input-contracts-reach-the-wire
  (reset! proof [])
  (doseq [mode [:marker :function :strict]]
    (testing (name mode)
      (let [code "(do (store! :override nil) (store! :computed {:count 0}) (llm \"inspect\" :reads [:payload :nullable :blank :override :absent :image :computed] :writes [:inline-result] :instruction \"Return the controlled inline result.\") (final! {:research-result (get-var :inline-result)}))"
            {:keys [server pool requests errors url]} (fixture! code)
            selected (case mode :marker {:use-function-calling? false}
                                :function {:use-function-calling? true}
                                :strict {:output-mode :json-schema})
            options (merge {:max-retries 0 :max-tokens 2222 :temperature 0.25} selected)
            db (str "/tmp/inline-input-contract-" (random-uuid) ".sqlite")]
        (try
          (llm/register-provider! :inline-input-contract
            {:provider :openrouter :model "fixture/prime"
             :config {:api-base url :api-key "CONTROLLED-NOT-A-REAL-KEY" :timeout 10000}})
          (h/with-async-test-context [ctx {:context {:tenant-id (random-uuid) :llm-provider :inline-input-contract}
                                          :event-store-conn {:type :sqlite :database-file db :maximum-pool-size 4}}]
            (let [sheet (orc/build-workflow! ctx
                          (orc/workflow (str "inline-input-contract-" (name mode))
                            (orc/blackboard board)
                            (orc/sequence "work"
                              (orc/llm "ordinary" :model "fixture/ordinary" :reads read-keys
                                :writes [:ordinary-result] :instruction "Return the controlled ordinary result." :options options)
                              (orc/repl-researcher "research" :model "fixture/prime"
                                :reads read-keys :writes [:research-result] :max-iterations 1
                                :instruction "Execute the controlled inline specialist." :options options
                                :rlm {:recursive? true :checkpointed? true :sub-model "fixture/inline" :sub-options options}))))
                  result (orc/execute ctx sheet {:payload payload :nullable nil :blank "" :override "old"
                                                :image [image-url]} :timeout-ms 30000 :max-ticks 1 :llm-call-budget 3)
                  events (orc/value-log-read-tick-events ctx (:tenant-id ctx) (:trace-id result))
                  bodies (mapv :body @requests)
                  calls (filterv #(contains? #{"fixture/ordinary" "fixture/inline"} (:model %)) bodies)]
              (swap! proof conj {:mode mode :result result :events events :requests @requests :fixture-errors @errors})
              (is (empty? @errors))
              (is (= :success (:status result)) (pr-str result))
              (is (= "inline-ok" (get-in result [:outputs :research-result])))
              (is (= :success (:root-status (orc/get-tick ctx (:trace-id result)))))
              (is (= ["fixture/ordinary" "fixture/prime" "fixture/inline"] (mapv :model bodies)))
              (doseq [body calls :let [prompt (text-content body) inline? (= "fixture/inline" (:model body))]]
                (is (= payload (json-value (value-text prompt mode :payload))))
                (doseq [meaning ["Supplied record collection." "Records in source order."
                                 "Lifecycle status supplied by the source." "Exact unit count; zero is meaningful."
                                 "Whether this record is enabled." "Source annotation; null means no annotation."]]
                  (is (str/includes? prompt meaning) (str (:model body) " missing " meaning)))
                (is (str/includes? prompt "active"))
                (is (str/includes? prompt "retired"))
                (is (= "null" (value-text prompt mode :nullable)))
                (is (= "" (value-text prompt mode :blank)))
                (is (= (if inline? "null" "old") (value-text prompt mode :override)))
                (is (nil? (value-text prompt mode :absent)))
                (is (not (str/includes? prompt "UNSHOWN-")))
                (is (not (str/includes? prompt "Input: ")))
                (is (= [image-url] (mapv #(get-in % [:image_url :url])
                                        (filter #(= "image_url" (:type %)) (get-in body [:messages 0 :content])))))
                (is (= 2222 (:max_tokens body)))
                (is (= 0.25 (:temperature body)))
                (when inline?
                  (is (= {:count 0} (json-value (value-text prompt mode :computed))))
                  (is (str/includes? prompt "A computed record with an authored contract."))
                  (is (str/includes? prompt "Computed count in units."))))))
          (finally
            (.stop server 0) (.shutdownNow pool)
            (doseq [suffix ["" "-shm" "-wal"]] (.delete (java.io.File. (str db suffix))))))))))
