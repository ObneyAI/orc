(ns ai.obney.orc.orc-service.output-group-meaning-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [hato.client :as http]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as bt]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def left-meaning "Complete left aggregate, including explicit empty and null observations.")
(def right-meaning "Independent right aggregate; zero is a measured result.")
(def left-schema
  [:map {:closed true :description left-meaning}
   [:shared {:description "Left field entry meaning."}
    [:string {:description "Left field schema meaning (literal text)."}]]
   [:nested [:map {:closed true :description "Nested left record meaning."}
             [:enabled [:boolean {:description "Explicit enabled state, including false."}]]
             [:note [:maybe {:description "Explicit null observation is not absence."} :string]]]]])
(def right-schema
  [:map {:closed true :description right-meaning}
   [:shared {:description "Right field entry meaning."}
    [:int {:description "Right field schema meaning."}]]])
(def scalar-schema [:boolean {:description "Independent scalar signal; not either aggregate."}])
(def board {:packet [:map [:left/result left-schema] [:right/result right-schema] [:signal scalar-schema]]
            :left/result left-schema :right/result right-schema :signal scalar-schema
            :received [:map [:left/result left-schema] [:right/result right-schema] [:signal scalar-schema]]})
(def value {:left/result {:shared "" :nested {:enabled false :note nil}}
            :right/result {:shared 0} :signal false})
(def wire-value {:__orc_field_0 "" :nested {:enabled false :note nil}
                 :__orc_field_1 0 :signal false})
(defn receive [{:keys [inputs]}] {:received inputs})
(defn emitted [_]
  [:sequence [:llm {:instruction "Preserve the complete packet into the three declared outputs."
                    :reads [:packet] :writes [:left/result :right/result :signal]}]
   [:final {:keys [:left/result :right/result :signal]}]])

(defn run-case! [role inherited?]
  (let [dir (str (java.nio.file.Files/createTempDirectory "generated-contract-" (make-array java.nio.file.attribute.FileAttribute 0)) "/")
        requests (atom []) errors (atom []) primes (atom 0)
        server (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/chat/completions"
      (reify com.sun.net.httpserver.HttpHandler
        (handle [_ exchange]
          (try
            (let [request (json/parse-string (slurp (.getRequestBody exchange)) true)
                  _ (swap! requests conj request)
                  prime? (contains? (get-in request [:response_format :json_schema :schema :properties]) :code)
                  response (if prime?
                             {:reasoning "Execute the requested typed child."
                              :code (if (= 1 (swap! primes inc))
                                      (str "(emit-tree! '" (pr-str (emitted {})) ")")
                                      "(final! {:left/result (get-var :left/result) :right/result (get-var :right/result) :signal (get-var :signal)})")}
                             wire-value)
                  bytes (.getBytes (json/generate-string
                                    {:id (str (random-uuid)) :model "fixture/inherited-contract"
                                     :choices [{:finish_reason "stop" :message {:role "assistant" :content (json/generate-string response)}}]
                                     :usage {:prompt_tokens 1 :completion_tokens 1}}) "UTF-8")]
              (.sendResponseHeaders exchange 200 (alength bytes))
              (with-open [body (.getResponseBody exchange)] (.write body bytes)))
            (catch Throwable e
              (swap! errors conj (.getMessage e))
              (try (.sendResponseHeaders exchange 500 -1) (catch Throwable _)))
            (finally (.close exchange))))))
    (.start server)
    (try
      (llm/register-provider! :generated-contract
        {:provider :openrouter :model "fixture/inherited-contract" :output-mode (when inherited? :json-schema)
         :config {:api-key "fixture" :api-base (str "http://127.0.0.1:" (.getPort (.getAddress server)))}})
      (h/with-async-test-context [ctx {:context {:llm-provider :generated-contract}
                                     :event-store-conn {:type :sqlite :database-file (str dir "store.sqlite")}}]
        (let [sheet (bt/build-workflow! ctx
                      (bt/workflow "inherited-output-contract"
                        (bt/blackboard board)
                        (bt/sequence "compose"
                          (if (= role :emitted)
                          (bt/repl-researcher "research" :model "fixture/inherited-contract"
                            :instruction "Emit the child, inspect its result, then return its typed summary."
                            :reads [:packet] :writes [:left/result :right/result :signal] :max-iterations 2
                            :options (cond-> {:max-retries 0 :timeout-ms 10000} (not inherited?) (assoc :output-mode :json-schema))
                            :rlm {:recursive? true :checkpointed? true :sub-model "fixture/inherited-contract"
                                  :sub-options (cond-> {:max-retries 0 :timeout-ms 10000} (not inherited?) (assoc :output-mode :json-schema))})
                            (bt/llm "ordinary" :instruction "Preserve complete packet as summary."
                              :reads [:packet] :writes [:left/result :right/result :signal]
                              :options (cond-> {:max-retries 0} (not inherited?) (assoc :output-mode :json-schema))
                              :retry {:max-attempts 1 :backoff-ms []}))
                          (bt/code "consume" :fn "ai.obney.orc.orc-service.output-group-meaning-test/receive"
                            :reads [:left/result :right/result :signal] :writes [:received]))))
              result (bt/execute ctx sheet {:packet value} :timeout-ms 30000 :max-ticks 1 :llm-call-budget 4)
              events (h/read-all-events ctx)
              family (bt/get-execution-family ctx (:trace-id result))
              writes (mapv #(bt/value-log-resolve-writes ctx (:tenant-id ctx) (:tick-id %) %)
                           (filter #(= :sheet/node-execution-completed (:event/type %)) events))
              proof {:path dir :sheet sheet :result result :events events :family family :writes writes
                     :requests @requests :errors @errors}]
          (spit (str dir "proof.edn") (pr-str proof))
          (println "GENERATED-CONTRACT-PROOF" dir)
          proof))
      (finally (.stop server 0)))))



(defn authored-meanings [schemas]
  ;; Inventory starts from original declared schemas, including containing maps.
  (set (mapcat (fn [node]
                 (when (map? node)
                   (concat (when-let [d (:description node)] [d])
                           (vals (:descriptions node)))))
               (tree-seq coll? seq schemas))))

(deftest original-output-group-meanings-survive-flattening
  (doseq [[role inherited?] [[:ordinary false] [:ordinary true] [:emitted false] [:emitted true]]]
    (let [{:keys [result writes requests errors]} (run-case! role inherited?)
          child (first (remove #(contains? (get-in % [:response_format :json_schema :schema :properties]) :code) requests))
          schema (get-in child [:response_format :json_schema :schema])
          output-text (last (str/split (str/join "\n" (map :content (:messages child))) #"Output contract \(JSON Schema\):"))
          meanings (authored-meanings [left-schema right-schema scalar-schema])]
      (is (empty? errors))
      (is (= :success (:status result)))
      (is (= value (get-in result [:outputs :received])))
      (is (some #(= value (:received %)) writes))
      (is (= [:__orc_field_0 :nested :__orc_field_1 :signal] (vec (keys (:properties schema)))))
      (is (= "Independent scalar signal; not either aggregate." (get-in schema [:properties :signal :description])))
      (doseq [meaning meanings]
        (is (str/includes? output-text meaning) (str "Original authored meaning missing: " meaning)))
      (is (= (str "Original output group [:left/result] (wire fields [\"__orc_field_0\" \"nested\"]):\n" left-meaning
                  "\nOriginal output group [:right/result] (wire fields [\"__orc_field_1\"]):\n" right-meaning)
             (:description schema)))
      (is (= schema (json/parse-string (second (str/split-lines output-text)) true))
          "The message guide uses the identical compiled schema including group meanings"))))

(def control-proof (atom {}))
(deftest group-provenance-does-not-change-legacy-wire-or-validation
  (let [module (dissoc (executor/build-module {:name "group-control" :reads [] :writes [:result]}
                        {:result {:schema [:map {:closed true :description "Authored aggregate meaning."}
                                           [:answer [:string {:description "Authored answer meaning."}]]]}})
                      :output-mapping)
        without-groups (dissoc module :orc/output-groups)]
    (is (= [{:path [:result] :fields ["answer"] :description "Authored aggregate meaning."}]
           (:orc/output-groups module)))
    (doseq [mode [:marker :function :strict]]
      (let [captures (atom [])]
        (llm/register-provider! :group-control {:provider :openrouter :model "fixture/group" :config {:api-key "fixture"}})
        (with-redefs [http/post
                      (fn [_ opts]
                        (swap! captures conj (:body opts))
                        (future {:status 200 :body
                                 {:choices [{:finish_reason "stop" :message
                                             (if (= mode :function)
                                               {:role "assistant" :tool_calls [{:id "call" :type "function"
                                                 :function {:name "submit_response" :arguments "{\"answer\":\"ok\"}"}}]}
                                               {:role "assistant" :content (if (= mode :strict)
                                                  "{\"answer\":\"ok\"}" "[[ ## answer ## ]]\nok")})}]}}))]
          (doseq [spec [module without-groups]]
            (is (= {:answer "ok"}
                   (llm/predict :group-control spec {}
                                (cond-> {:use-function-calling? (= mode :function)}
                                  (= mode :strict) (assoc :output-mode :json-schema)))))))
        (swap! control-proof assoc mode @captures)
        (is (= 2 (count @captures)))
        (if (= mode :strict)
          (let [[with-group without-group] (map #(get-in (json/parse-string % true)
                                                        [:response_format :json_schema :schema]) @captures)]
            (is (some? (:description with-group)))
            (is (nil? (:description without-group)))
            (is (= (dissoc with-group :description) without-group)
                "All properties, aliases, validation constraints and order remain unchanged"))
          (is (= (first @captures) (second @captures))
              "Complete legacy adapter body is byte-identical with/without group provenance"))))
    (with-redefs [http/post (fn [_ _] (future {:status 200 :body
                      {:choices [{:finish_reason "stop" :message {:role "assistant" :content "{}"}}]}}))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (llm/predict :group-control module {} {:output-mode :json-schema}))))))
