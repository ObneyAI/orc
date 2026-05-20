(ns d003-live-verify
  "Live verification for D-003 Phase 2 budget-aware timeout with cancellation.

   STRATEGY:
   Instead of relying on the LLM to reliably call emit-tree! during Phase 1
   (which depends on prompt details and model behavior), this script mocks
   the very first dscloj/predict call to return a deterministic emit-tree!
   tree containing a :map-each over chunks with REAL :ai LLM leaves.
   All subsequent predict calls (Phase 2 sub-LLM calls inside the tree)
   delegate to the actual provider, so cancellation is proven against real
   LLM wall-time.

   Two scenarios:

   1. TIGHT BUDGET (:tight-budget? true)
      - :timeout-ms = 8000ms
      - Phase 1 is mocked-instant; Phase 2 has 6 chunks × real LLM call.
      - Each real LLM call takes ~1s, so 6 chunks take ~6s sequentially,
        but the budget gives Phase 2 ~7.9s remaining — and with a few of
        them running serially we should cancel partway through.
      - Expected: :status :timeout, :sheet/tick-cancelled in store with
        the child Phase 2 tick-id.

   2. GENEROUS BUDGET (:tight-budget? false)
      - :timeout-ms = 60000ms
      - Same tree, plenty of headroom — Phase 2 completes normally.
      - Expected: :status :success, :phase1-elapsed-ms and :phase2-elapsed-ms
        populated, no regression vs pre-D-003 behavior."
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
            [dscloj.core :as dscloj]
            [clojure.pprint :as pp]
            [clojure.java.io :as io]))

;; =============================================================================
;; Context bringup
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/d003-live-verify-" (random-uuid))
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
;; The deterministic Phase 1 tree (returned by mocked predict on first call)
;;
;; A :map-each over :chunks → :results, child is an :llm node that summarizes
;; each chunk. Final :llm consolidates into :summary. With 6 chunks @ ~1s each
;; (serial), Phase 2 takes ~6-8 seconds — long enough for an 8s total budget
;; (with Phase 1 mocked-instant) to cancel partway through.
;; =============================================================================

(def ^:private phase1-emit-tree-code
  "(emit-tree!
     [:sequence
       [:map-each {:from :chunks :as :chunk :into :chunk-summaries}
         [:llm {:instruction \"Summarize this chunk in one sentence.\"
                :reads [:chunk]
                :writes [:chunk-summary]}]]
       [:aggregate {:from :chunk-summaries :writes [:combined]}]
       [:llm {:instruction \"Synthesize a one-paragraph :summary from :combined.\"
              :reads [:combined]
              :writes [:summary]}]
       [:final {:keys [:summary]}]])")

;; =============================================================================
;; Sheet build
;; =============================================================================

(defn- build-sheet! [ctx timeout-ms]
  (executor/setup-providers!)
  (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "D-003 Live Verify"))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)]
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :chunks [:vector :string]))
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :summary :string))
    (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
          seq-id (-> seq-result :command-result/events first :node-id)
          node-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :repl-researcher :parent-id seq-id))
          node-id (-> node-result :command-result/events first :node-id)]
      (h/run-and-apply! ctx (h/make-set-repl-researcher-config-command
                              sheet-id node-id
                              "Summarize the chunks."
                              [:chunks]
                              [:summary]
                              []
                              :model "google/gemini-2.5-flash"
                              :max-iterations 5
                              :rlm true
                              :timeout-ms timeout-ms))
      {:sheet-id sheet-id :node-id node-id})))

;; =============================================================================
;; Main entry
;; =============================================================================

(defn- save-edn! [filename data]
  (let [dir "development/bench/generalization-results"]
    (.mkdirs (io/file dir))
    (let [path (str dir "/" filename)]
      (spit path (with-out-str (pp/pprint data)))
      path)))

(defn- chunks-data []
  ;; 6 small chunks — Phase 2 will run 6 real LLM calls
  ["Cats are carnivorous mammals popular as pets."
   "Dogs descended from wolves and are loyal companions."
   "Birds have feathers and most can fly."
   "Fish breathe through gills and live in water."
   "Reptiles are cold-blooded vertebrates with scales."
   "Mammals nurse their young with milk."])

(defn run!
  "Run the D-003 live verify.
     :tight-budget? true  → 8s budget, Phase 2 should cancel
     :tight-budget? false → 60s budget, happy path no-regression"
  ([] (run! {:tight-budget? true}))
  ([{:keys [tight-budget?]}]
   (let [ctx (create-context)
         timeout-ms (if tight-budget? 4000 60000)
         real-predict dscloj/predict
         predict-call-count (atom 0)]
     (try
       (let [{:keys [sheet-id node-id]} (build-sheet! ctx timeout-ms)
             _ (println "\n=== D-003 LIVE VERIFY ===")
             _ (println "Mode:" (if tight-budget? "TIGHT BUDGET (8s, expect timeout+cancel)"
                                    "GENEROUS BUDGET (60s, expect success, no-regression)"))
             _ (println "Sheet ID:" sheet-id "Node ID:" node-id)
             _ (println "Configured :timeout-ms:" timeout-ms)
             _ (println "Chunks:" (count (chunks-data)))

             ;; Mock predict: 1st call (Phase 1) returns emit-tree! code instantly,
             ;; all subsequent calls (Phase 2 sub-LLMs) delegate to the real provider.
             t0 (System/currentTimeMillis)
             result (with-redefs [dscloj/predict
                                  (fn [provider module inputs opts]
                                    (let [n (swap! predict-call-count inc)]
                                      (if (= 1 n)
                                        (do (println "[predict mock] Phase 1 — returning emit-tree! code")
                                            {:outputs {:code phase1-emit-tree-code}
                                             :usage {:prompt_tokens 100 :completion_tokens 80 :total_tokens 180}})
                                        ;; Phase 2 sub-LLM — REAL provider
                                        (real-predict provider module inputs opts))))]
                      (sheet/execute ctx sheet-id {:chunks (chunks-data)}
                        ;; Outer wall is GENEROUS — let D-003's internal budget
                        ;; cancellation be the actual gate. We must avoid the
                        ;; outer sheet/execute deref timing out before D-003
                        ;; finishes its cancel + drain.
                        :timeout-ms 60000))
             elapsed (- (System/currentTimeMillis) t0)

             _ (println "\nElapsed wall-time:" elapsed "ms")
             _ (println "Predict calls total:" @predict-call-count
                        "(1 Phase 1 mock +" (max 0 (dec @predict-call-count)) "real Phase 2)")
             _ (println "Result status:" (:status result))
             _ (println "Result :error:" (:error result))
             _ (println "Result outputs keys:" (keys (:outputs result)))

             tick-id (:trace-id result)
             events (when tick-id
                      (into [] (es/read (:event-store ctx)
                                 {:tenant-id (:tenant-id ctx)})))
             cancelled-events (filter #(= :sheet/tick-cancelled (:event/type %)) events)
             _ (println "\n--- Event store evidence ---")
             _ (println "Total events in store:" (count events))
             _ (println ":sheet/tick-cancelled events:" (count cancelled-events))
             _ (doseq [e cancelled-events]
                 (println "  cancelled tick-id:" (:tick-id e)))

             saved-path (save-edn!
                          (str "d003-live-verify_"
                               (if tight-budget? "tight_" "generous_")
                               (System/currentTimeMillis) ".edn")
                          {:tight-budget? tight-budget?
                           :timeout-ms timeout-ms
                           :result-status (:status result)
                           :result-error (:error result)
                           :elapsed-wall-ms elapsed
                           :predict-call-count @predict-call-count
                           :event-types (frequencies (map :event/type events))
                           :cancelled-tick-ids (mapv :tick-id cancelled-events)})
             _ (println "\nFull EDN saved to:" saved-path)]
         result)
       (finally
         (stop-context ctx))))))

(comment
  ;; Tight budget — should TIMEOUT + CANCEL:
  (run! {:tight-budget? true})
  ;; Generous budget — should SUCCEED:
  (run! {:tight-budget? false})
  )
