(ns ai.obney.orc.orc-service.emitted-setup-failure-test
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]))

;; MODEL-CONTRACT-19 / GeneratedChildSetupMustSucceedBeforeExecution.
;; The only fake is the external provider; public authoring, compilation,
;; commands, SQLite events, execution and projections are real.
(def proof (atom nil))
(defn emitted [schema]
  (str "(emit-tree! [:sequence [:llm {:instruction \"Copy seed into generated-answer.value.\" "
       ":reads [:seed] :writes [:generated-answer] :output-schemas {:generated-answer " schema "}}] "
       "[:final {:keys [:generated-answer]}]])"))

(deftest rejected-generated-schema-stops-child-and-next-native-iteration-can-recover
  (let [dir (str (System/getProperty "java.io.tmpdir") "/orc-emitted-setup-" (random-uuid) "/")
        requests (atom []) commands (atom []) primes (atom 0)
        programs [(emitted "[:map [:items [:vector :map]]]")
                  (emitted "[:map [:value :int]]")
                  "(final! {:result (get-var :generated-answer)})"]
        server (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1" 0) 0)
        pool (java.util.concurrent.Executors/newFixedThreadPool 2)]
    (.mkdirs (io/file dir))
    (.setExecutor server pool)
    (.createContext server "/chat/completions"
      (reify com.sun.net.httpserver.HttpHandler
        (handle [_ exchange]
          (try
            (let [wire (json/parse-string (slurp (.getRequestBody exchange)) true)
                  properties (get-in wire [:response_format :json_schema :schema :properties])
                  prime? (contains? properties :code)
                  n (when prime? (swap! primes inc))
                  content (if prime?
                            {:reasoning "Controlled setup recovery." :code (nth programs (dec n) "(final! {:result {:value -1}})")}
                            (cond (contains? properties :generated-answer) {:generated-answer {:value 7}}
                                  (contains? properties :value) {:value 7}
                                  :else {}))
                  response (json/generate-string {:model (:model wire) :choices [{:finish_reason "stop" :message {:role "assistant" :content (json/generate-string content)}}] :usage {:prompt_tokens 1 :completion_tokens 1}})
                  bytes (.getBytes response "UTF-8")]
              (swap! requests conj {:prime? prime? :prime-index n :wire wire :response response})
              (.sendResponseHeaders exchange 200 (alength bytes))
              (with-open [stream (.getResponseBody exchange)] (.write stream bytes)))
            (finally (.close exchange))))))
    (.start server)
    (try
      (llm/register-provider! :setup-probe {:provider :openrouter :model "fixture/setup-probe"
        :config {:api-base (str "http://127.0.0.1:" (.getPort (.getAddress server))) :api-key "LOCAL-FIXTURE" :timeout 5000}})
      (let [command cp/process-command]
        (with-redefs [cp/process-command (fn [context]
                                          (let [result (command context)]
                                            (swap! commands conj {:command (:command context) :result result}) result))]
          (h/with-async-test-context [ctx {:context {:tenant-id (random-uuid) :llm-provider :setup-probe}
            :event-store-conn {:type :sqlite :database-file (str dir "store.sqlite") :maximum-pool-size 4}}]
            (let [sheet (orc/build-workflow! ctx
                          (orc/workflow "setup-failure-recovery"
                            (orc/blackboard {:seed :int :result [:map [:value :int]]})
                            (orc/repl-researcher "research" :model "fixture/setup-probe"
                              :instruction "Use emitted specialist, inspect any setup rejection, correct it and return the result."
                              :reads [:seed] :writes [:result] :max-iterations 4
                              :options {:output-mode :json-schema :max-retries 0 :max-tokens 1024}
                              :rlm {:recursive? true :checkpointed? true :sub-model "fixture/setup-probe"
                                    :sub-options {:output-mode :json-schema :max-retries 0 :max-tokens 1024}})))
                  result (orc/execute ctx sheet {:seed 7} :timeout-ms 45000 :max-ticks 1 :llm-call-budget 6)
                  events (h/read-all-events ctx)
                  family (orc/get-execution-family ctx (:trace-id result))
                  rejection (first (filter #(and (= :sheet/declare-key (get-in % [:command :command/name]))
                                                (= :generated-answer (get-in % [:command :key]))
                                                (= :cognitect.anomalies/incorrect (get-in % [:result :cognitect.anomalies/category]))) @commands))
                  rejected-sheet (get-in rejection [:command :sheet-id])
                  children (filterv #(not (:prime? %)) @requests)
                  second-prime (first (filter #(= 2 (:prime-index %)) @requests))
                  third-prime (first (filter #(= 3 (:prime-index %)) @requests))
                  valid-leaf (first (filter #(= {:generated-answer {:value 7}} (:outputs %)) family))
                  valid-node (when valid-leaf (orc/get-node ctx (:sheet-id valid-leaf) (:node-id valid-leaf)))
                  captured {:result result :events events :family family :commands @commands :requests @requests :valid-node valid-node :dir dir}]
              (reset! proof captured)
              (spit (str dir "proof.edn") (pr-str captured))
              (println "EMITTED-SETUP-PROOF" dir)
              (is (some? rejection) "Specific invalid output schema is rejected through public declaration")
              (is (str/includes? (get-in rejection [:result :cognitect.anomalies/message] "") "[:generated-answer :items :items]"))
              (is (empty? (filter #(and (= rejected-sheet (:sheet-id %)) (= :sheet/tree-tick-started (:event/type %))) events))
                  "Rejected generated sheet must never begin a child tick")
              (is (= 1 (count children)) "Only the corrected child may call the provider")
              (is (str/includes? (pr-str (:wire second-prime)) "[:generated-answer :items :items]")
                  "Next native researcher iteration receives offending schema path")
              (is (not (str/includes? (pr-str (:wire third-prime)) "Latest child setup outcome:"))
                  "A successful child clears the current setup feedback; old errors do not direct later work")
              (is (= :success (:status result)))
              (is (= {:value 7} (get-in result [:outputs :result])))
              (is (= [:seed] (:reads valid-node)))
              (is (= [:generated-answer] (:writes valid-node)))
              (is (= {:seed 7} (:inputs valid-leaf)))
              (is (some #(and (= :sheet/node-io-set (:event/type %)) (= (:node-id valid-leaf) (:node-id %))
                              (= [:seed] (:reads %)) (= [:generated-answer] (:writes %))) events)
                  "Corrected child I/O is genuinely persisted")
              (is (every? #(seq (get-in % [:wire :response_format :json_schema :schema :properties])) children)
                  "A rejected setup never degrades to an empty provider contract")))))
      (finally (.stop server 0) (.shutdownNow pool)))))
