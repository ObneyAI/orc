(ns d008-live-verify
  "Live verification for D-008 partial-results with a REAL LLM.

   Builds a small sheet:
     [:sequence
      [:map-each :chunks → :results
        [:sequence
          [:leaf check-sentinel (:code, throws if item is sentinel)]
          [:leaf summarize (:ai, real LLM call)]]]
      [:leaf count-results (:code, writes :count)]]

   3 chunks are seeded; one is the sentinel. The check-sentinel leaf throws
   on the sentinel item before the LLM is ever called, so map-each sees a
   :failure for that iteration. The other two iterations make real LLM
   calls. Expected outcome:
     - Tree result :status :partial (sticky from sequence)
     - Map-each's node-execution-completed event :status :partial with
       :partial-summary {:total 3 :succeeded 2 :failed 1
                         :failure-indices [<sentinel-idx>] ...}
     - :results blackboard has 2 items (successes only)
     - count-results runs against :results and writes :count 2"
  (:require [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [clojure.pprint :as pp]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

;; =============================================================================
;; Test functions used by :code leaves in the sheet
;; =============================================================================

(defn check-sentinel
  "Throws when :current-item :sentinel? is true. Used to force one map-each
   iteration to fail BEFORE the real LLM call. The thrown ex-info is converted
   to {:status :failure :error ...} by execute-code."
  [{:keys [inputs]}]
  (let [item (get inputs :current-item)]
    (when (:sentinel? item)
      (throw (ex-info "Sentinel failure for D-008 verification"
                      {:item item})))
    {:current-item item}))

(defn count-results
  "Reads :results and writes :count (the size of the vector)."
  [{:keys [inputs]}]
  {:count (count (get inputs :results))})

;; =============================================================================
;; Context bringup (mirrors rlm-gen-bench)
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/d008-live-verify-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "live-verify"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  :dscloj-provider :openrouter
                  :event-pubsub ps
                  ::cache-dir cache-dir}
        processors (reduce-kv
                    (fn [acc proc-name {:keys [handler-fn topics]}]
                      (assoc acc proc-name
                             (tp/start {:event-pubsub ps
                                        :topics topics
                                        :handler-fn handler-fn
                                        :context base-ctx})))
                    {}
                    @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defn- stop-context [ctx]
  (doseq [[_ processor] (:processors ctx)] (tp/stop processor))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [cache (:cache ctx)] (kv/stop cache))
  (when-let [event-store (:event-store ctx)] (es/stop event-store))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [child (.listFiles f)] (.delete child))
        (.delete f)))))

;; =============================================================================
;; Sheet construction
;; =============================================================================

(defn- build-sheet! [ctx]
  (executor/setup-providers!)
  (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "D-008 Live Verify"))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)]
    ;; Declare blackboard keys
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :chunks [:vector :map]))
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :current-item :map))
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :results [:vector :map]))
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :summary :string))
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :count :int))

    (let [;; ROOT: sequence
          seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
          seq-id (-> seq-result :command-result/events first :node-id)

          ;; Sequence child 0: map-each over :chunks → :results
          map-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :map-each :parent-id seq-id))
          map-id (-> map-result :command-result/events first :node-id)
          _ (h/run-and-apply! ctx (h/make-set-map-each-config-command sheet-id map-id
                                    :chunks :current-item :results))

          ;; Map-each child: SEQUENCE [check-sentinel, ai-summarize]
          inner-seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence :parent-id map-id))
          inner-seq-id (-> inner-seq-result :command-result/events first :node-id)

          ;; check-sentinel leaf (:code, throws on sentinel item)
          check-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id inner-seq-id :index 0))
          check-id (-> check-result :command-result/events first :node-id)
          _ (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id check-id :code
                                    :fn "d008-live-verify/check-sentinel"))
          _ (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id check-id [:current-item] [:current-item]))

          ;; ai-summarize leaf (real LLM via :openrouter provider)
          ai-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id inner-seq-id :index 1))
          ai-id (-> ai-result :command-result/events first :node-id)
          _ (h/run-and-apply! ctx (h/make-set-node-instruction-command sheet-id ai-id
                                    "Summarize :current-item in one short sentence. Return only the summary."))
          _ (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id ai-id :ai
                                    :model "google/gemini-2.5-flash"))
          _ (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id ai-id [:current-item] [:current-item]))

          ;; Sequence child 1: count-results (:code, reads :results, writes :count)
          count-leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id :index 1))
          count-leaf-id (-> count-leaf-result :command-result/events first :node-id)
          _ (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id count-leaf-id :code
                                    :fn "d008-live-verify/count-results"))
          _ (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id count-leaf-id [:results] [:count]))]
      {:sheet-id sheet-id
       :seq-id seq-id
       :map-id map-id
       :inner-seq-id inner-seq-id
       :check-id check-id
       :ai-id ai-id
       :count-leaf-id count-leaf-id})))

;; =============================================================================
;; Main entry
;; =============================================================================

(defn- save-edn! [filename data]
  (let [dir "development/bench/generalization-results"]
    (.mkdirs (io/file dir))
    (let [path (str dir "/" filename)]
      (spit path (with-out-str (pp/pprint data)))
      path)))

(defn run!
  "Run the live verify. Args (optional keys):
     :happy-path? — when true, all 3 chunks are non-sentinel (no-regression check
                    that the happy path still works after D-008 changes)."
  ([] (run! {}))
  ([{:keys [happy-path?]}]
   (let [ctx (create-context)]
    (try
      (let [{:keys [sheet-id map-id]} (build-sheet! ctx)
            chunks (if happy-path?
                     [{:id 1 :text "Cats are small carnivorous mammals. They are popular pets worldwide."}
                      {:id 2 :text "Birds are warm-blooded vertebrates with feathers and beaks."}
                      {:id 3 :text "Dogs descended from wolves. They are commonly kept as companion animals."}]
                     [{:id 1 :text "Cats are small carnivorous mammals. They are popular pets worldwide."}
                      {:id 2 :sentinel? true :text "<SENTINEL — should fail>"}
                      {:id 3 :text "Dogs descended from wolves. They are commonly kept as companion animals."}])
            _ (println "\n=== D-008 LIVE VERIFY ===")
            _ (println "Sheet ID:" sheet-id)
            _ (println "Map-each ID:" map-id)
            _ (println "Chunks (id=2 is sentinel):")
            _ (doseq [c chunks] (println " " c))
            _ (println "\nExecuting with real OpenRouter LLM...")

            t0 (System/currentTimeMillis)
            result (sheet/execute ctx sheet-id {:chunks chunks} :timeout-ms 60000)
            elapsed (- (System/currentTimeMillis) t0)
            _ (println "\nElapsed:" elapsed "ms")
            _ (println "Result status:" (:status result))
            _ (println "Result :count output:" (get-in result [:outputs :count]))
            _ (println "Result :results count:" (count (get-in result [:outputs :results])))

            tick-id (:trace-id result)
            events (when tick-id
                     (into [] (es/read (:event-store ctx)
                                {:tags #{[:tick tick-id]}
                                 :tenant-id (:tenant-id ctx)})))
            map-completion (->> events
                                (filter #(= :sheet/node-execution-completed (:event/type %)))
                                (filter #(= map-id (:node-id %)))
                                first)
            _ (println "\n--- Map-each completion event ---")
            _ (println "Status:" (:status map-completion))
            _ (println "Partial summary:")
            _ (pp/pprint (:partial-summary map-completion))

            ;; Save full EDN for inspection
            saved-path (save-edn!
                         (str "d008-live-verify_" (System/currentTimeMillis) ".edn")
                         {:result result
                          :map-completion (select-keys map-completion
                                                       [:event/type :node-id :status :partial-summary
                                                        :duration-ms])
                          :event-types (frequencies (map :event/type events))
                          :event-count (count events)})
            _ (println "\nFull EDN saved to:" saved-path)]
        result)
      (finally
        (stop-context ctx))))))

(comment
  ;; To run the partial path (sentinel injection):
  (run!)
  ;; To run the happy-path no-regression check (all 3 chunks succeed):
  (run! {:happy-path? true})
  )
