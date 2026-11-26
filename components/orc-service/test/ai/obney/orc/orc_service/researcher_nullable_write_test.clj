(ns ai.obney.orc.orc-service.researcher-nullable-write-test
 (:require [clojure.edn :as edn] [clojure.test :refer [deftest is testing]] [cheshire.core :as json] [clojure.java.io :as io]
 [ai.obney.grain.event-store-sqlite-v3.interface]
 [ai.obney.orc.llm.interface :as llm]
 [ai.obney.orc.orc-service.interface :as orc]
 [ai.obney.orc.orc-service.test-helpers :as h]
 [ai.obney.orc.orc-service.core.value-log :as value-log]))
(def proof (atom []))
(def home (str (System/getProperty "java.io.tmpdir") "/orc-nullable-write-" (random-uuid) "/"))
(defn emit-program [value]
 ;; Reuse the authored answer contract. Redeclaring every answer as nullable
 ;; would test a contract conflict rather than a nonnullable returned nil.
 (str "(emit-tree! [:sequence [:code {:fn '(fn [_] " value ") :reads [:seed] :writes [:answer]}] [:final {:keys [:answer]}]])"))
(def cases [[:explicit-null "{:answer nil}" [:maybe :string]] [:missing-write "{}" [:maybe :string]] [:nonnullable-null "{:answer nil}" :string] [:whole-nil "nil" [:maybe :string]] [:false-seed "{:answer nil}" [:maybe :string] false] [:zero-seed "{:answer nil}" [:maybe :string] 0]])
(defn run-case! [[kind second-value answer-schema seed-value]]
 (let [requests (atom []) call (atom 0)
       programs [(emit-program "{:answer \"old\"}") (emit-program second-value)
                 "(final! {:result {:present (contains? (list-vars) :answer) :value (get-var :answer)}})"]
       server (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1" 0) 0)
       pool (java.util.concurrent.Executors/newFixedThreadPool 2)
       dir (str home (name kind) "-" (random-uuid) "/")]
  (.mkdirs (io/file dir)) (.setExecutor server pool)
  (.createContext server "/chat/completions"
   (reify com.sun.net.httpserver.HttpHandler
    (handle [_ ex]
     (try
      (let [wire (json/parse-string (slurp (.getRequestBody ex)) true)
            n (swap! call inc)
            code (nth programs (dec n) "(final! {:result {:present false :value \"unexpected extra call\"}})")
            response (json/generate-string {:model (:model wire) :choices [{:finish_reason "stop" :message {:role "assistant" :content (json/generate-string {:reasoning "Controlled emitted-write probe." :code code})}}] :usage {:prompt_tokens 1 :completion_tokens 1}})
            bytes (.getBytes response "UTF-8")]
       (swap! requests conj {:request wire :code code :response response})
       (.sendResponseHeaders ex 200 (alength bytes)) (with-open [o (.getResponseBody ex)] (.write o bytes)))
      (finally (.close ex))))))
  (.start server)
  (try
   (llm/register-provider! :nil-probe {:provider :openrouter :model "fixture/nil-probe"
      :config {:api-base (str "http://127.0.0.1:" (.getPort (.getAddress server))) :api-key "LOCAL-NO-KEY" :timeout 5000}})
   (h/with-async-test-context [ctx {:context {:tenant-id (random-uuid) :llm-provider :nil-probe}
      :event-store-conn {:type :sqlite :database-file (str dir "store.sqlite") :maximum-pool-size 4}}]
    (let [sheet (orc/build-workflow! ctx (orc/workflow (str "nil-probe-" kind)
                  (orc/blackboard {:seed [:maybe [:or :string :boolean :int]] :answer answer-schema
                                   :result [:map [:present :boolean] [:value [:maybe :string]]]})
                  (orc/repl-researcher "probe" :model "fixture/nil-probe" :reads [:seed] :writes [:result]
                   :instruction "Execute controlled programs and report stored value." :max-iterations 4
                   :options {:output-mode :json-schema :max-retries 0 :max-tokens 1024}
                   :rlm {:recursive? true :checkpointed? true})))
          result (orc/execute ctx sheet {:seed seed-value} :timeout-ms 45000 :max-ticks 1 :llm-call-budget 4)
          family (orc/get-execution-family ctx (:trace-id result))
          events (h/read-all-events ctx)
          mirror (mapv (fn [entry]
                        (let [completion (first (filter #(= (:event-id entry) (:event/id %)) events))]
                          {:node-id (:node-id entry) :tick-id (:tick-id entry)
                           :inputs (orc/value-log-resolve-reads ctx (:tenant-id ctx) (:tick-id entry) completion)
                           :outputs (orc/value-log-resolve-writes ctx (:tenant-id ctx) (:tick-id entry) completion)})) family)
          original-read value-log/read-tick-events
          unresolved-family (with-redefs [value-log/read-tick-events
                             (fn [& args] (mapv (fn [event]
                              (if (and (= :sheet/execution-value-written (:event/type event)) (:node-id event)
                                       (= :answer (:key event)) (contains? event :value) (nil? (:value event)))
                                (-> event (dissoc :value) (assoc :event/type :sheet/execution-value-referenced
                                  :source {:tick-id (:tick-id event) :event-id (random-uuid)})) event))
                                (apply original-read args)))]
                            (orc/get-execution-family ctx (:trace-id result)))
          proof {:mirror mirror :unresolved-family unresolved-family :kind kind :seed-value seed-value :result result :family family :events events :requests @requests :programs programs :paid-calls 0 :dir dir}]
     (swap! ai.obney.orc.orc-service.researcher-nullable-write-test/proof conj proof)
     (println "NIL-PROBE" kind (select-keys result [:status :error]) "calls" @call)
     {:kind kind :dir dir :status (:status result) :outputs (:outputs result) :calls @call}))
   (finally (.stop server 0) (.shutdownNow pool)))))
(deftest emitted-code-preserves-explicit-null-only-under-nullable-contract
  (reset! proof [])
  (doseq [case cases] (run-case! case))
  (doseq [{:keys [kind family events requests result mirror unresolved-family seed-value]} @proof]
    (testing (name kind)
      (let [leaves (filter #(= :leaf (:node-type %)) family)
            old-leaf (first (filter #(= {:answer "old"} (:outputs %)) leaves))
            null-leaf (first (filter #(and (= :success (:status %)) (contains? (:outputs %) :answer) (nil? (get-in % [:outputs :answer]))) leaves))
            actual-null-writes (filter #(and (= :sheet/execution-value-written (:event/type %))
                                             (= :answer (:key %)) (contains? % :value) (nil? (:value %))
                                             (:node-id %) (not (:tick-seed? %))) events)]
        (is (= 3 (count requests)))
        (doseq [leaf leaves]
          (is (contains? (:inputs leaf) :seed) "Nullable input seed must remain visible")
          (is (= seed-value (get-in leaf [:inputs :seed]))))
        (is (= (mapv #(select-keys % [:node-id :tick-id :inputs :outputs]) family) mirror)
            "Public family readback must match public value-log resolution")
        (is (= :success (:status old-leaf)))
        (let [initial-child (first (filter #(and (= :sheet/tree-tick-started (:event/type %))
                                                 (= (:tick-id old-leaf) (:tick-id %))) events))]
          (is (some? initial-child))
          (is (not (contains? (:seed-sources initial-child) :answer))
              "An unwritten declared output must not become an explicit null seed"))
        (if (contains? #{:explicit-null :false-seed :zero-seed} kind)
          (do (is (= {:present true :value nil} (get-in result [:outputs :result]))
                  "Successful null replaces retained prior value in actual researcher")
              (is (some (fn [write] (some #(and (= :sheet/node-execution-completed (:event/type %))
                       (= :success (:status %)) (= (:node-id write) (:node-id %)) (= (:tick-id write) (:tick-id %))) events)) actual-null-writes)
                  "Canonical null write has a successful completed producer")
              (is (every? #(not (and (contains? (:outputs %) :answer) (nil? (get-in % [:outputs :answer])))) unresolved-family)
                  "Unresolved reference control must not fabricate a null value")
              (is (some? null-leaf) "Nullable code result must complete successfully")
              (is (some #(and (= (:node-id null-leaf) (:node-id %)) (= (:tick-id null-leaf) (:tick-id %))) actual-null-writes)
                  "Null must have actual completed producer ownership, not an initialized slot"))
          (do (is (= {:present true :value "old"} (get-in result [:outputs :result]))
                  "A failed or absent write preserves the prior completed value")
              (is (some #(= :failure (:status %)) leaves))
              (is (empty? actual-null-writes) "Missing, invalid or ambiguous return must not publish null")))))))
