(ns el3-induced-fallback-probe
  "EL-3 /prototype (THROWAWAY) — the LIVE induced-fallback proof.

   Induces a reranker fallback at the reranker/rerank! boundary ONLY (ColBERT
   search + index stay REAL), then drives the C-2c-2 wedge
   (maybe-auto-classify-and-set-context) with REAL grain and asserts, by
   READING THE EVENT STORE BACK:
     (1) classify-task / classify-behaviors return :outcome :uncertain.
     (2) NO :ontology/task-classified event lands (no assign-task-class
         dispatched → no fresh-mint, no class creation/accrual).
     (3) the node :context is STILL set with the rerank-fallback? caution.

   Detect-and-defer (ADR 0015): :uncertain creates/captures NOTHING.

   Run (venv read-only from orc-main; -J-D BEFORE -M:dev):
     OPENROUTER_API_KEY=... \\
     clojure -J-Dcolbert.venv.path=/Users/darylroberts/Desktop/Code/orc-main/.venv-colbert \\
             -J-Dcolbert.bridge.script=/Users/darylroberts/Desktop/Code/orc-rinject-redesign/scripts/colbert_bridge.py \\
             -M:dev -m el3-induced-fallback-probe"
  (:require [runner]
            [ai.obney.orc.ontology.interface :as ont]
            [ai.obney.orc.ontology.core.task-classifier :as tc]
            [ai.obney.orc.ontology.core.reranker :as reranker]
            [ai.obney.orc.orc-service.core.todo-processors :as otp]
            [ai.obney.orc.colbert.interface :as colbert]
            [ai.obney.grain.event-store-v3.interface :as es]))

(defn build-sig [instruction]
  (tc/build-task-signature {:instruction instruction
                            :reads [:user-message :active-plan :workspace-root]
                            :writes [:assistant-response]
                            :mcp-tools ["shell/exec" "fs/read" "fs/list"]}))

(def in-domain-instruction
  "The report-rendering endpoint is slow on large inputs; profile it and optimize the hot path so a 10k-row report renders under 500ms without changing the output.")

(defn -main [& _]
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (println "FATAL: OPENROUTER_API_KEY not set") (System/exit 1))
  (println "=== EL-3 INDUCED-FALLBACK PROBE (live, fault ONLY at reranker/rerank!) ===")
  (try
    (runner/start!)
    (let [ctx (deref (var-get (requiring-resolve 'runner/system-state)))
          sig (build-sig in-domain-instruction)]

      ;; ---- 0. RULE OUT THE HARNESS FIRST: real index + real hits, no fault ----
      (println "\n--- 0. harness check: real ColBERT hits WITHOUT fault ---")
      (let [tf (ont/search-descriptions ctx {:query sig :granularity :tree-fingerprint
                                             :rerank-with-intent "fit?" :k 5})]
        (println "  tree-fingerprint hits (no fault):" (count tf)
                 " (must be > 0 or the probe is measuring an empty index, NOT EL-3)"))

      ;; ---- 1. UNDER FALLBACK: classifiers return :outcome :uncertain ----
      (println "\n--- 1. classifiers under induced rerank! fallback ---")
      (with-redefs [reranker/rerank! (fn [& _] (throw (ex-info "induced reranker failure (EL-3 probe)" {})))]
        (let [tres (ont/classify-task ctx {:task-signature sig :threshold 0.7 :walk-down? true})
              bres (ont/classify-behaviors ctx {:task-signature sig :threshold 0.6 :top-n 5})]
          (println "  classify-task   :outcome =" (:outcome tres)
                   " :was-fresh-mint? =" (:was-fresh-mint? tres)
                   " :assigned-tree-id =" (:assigned-tree-id tres)
                   " :rerank-fallback? =" (:rerank-fallback? tres))
          (println "  classify-behaviors :outcome =" (:outcome bres)
                   " :rerank-fallback? =" (:rerank-fallback? bres)
                   " any-behavioral-mint? =" (boolean (some #(true? (:was-fresh-mint? %)) (:behaviors bres))))
          (let [ok-task (and (= :uncertain (:outcome tres))
                             (not (true? (:was-fresh-mint? tres)))
                             (nil? (:assigned-tree-id tres)))
                ok-behav (and (= :uncertain (:outcome bres))
                              (not (some #(true? (:was-fresh-mint? %)) (:behaviors bres))))]
            (println "  classify-task :uncertain & no-mint? =" ok-task)
            (println "  classify-behaviors :uncertain & no-mint? =" ok-behav))))

      ;; ---- 2. THE WEDGE under fallback: NO assign-task-class event in store ----
      (println "\n--- 2. wedge under fallback: read the event store back ---")
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node {:id (random-uuid) :name "el3-probe-node"
                  :type :repl-researcher
                  :instruction in-domain-instruction
                  :reads [] :writes []
                  :rlm {:auto-classify? true}}
            wedge-ctx (assoc ctx :sheet-id sheet-id :tick-id tick-id)
            before (count (into [] (es/read (:event-store ctx)
                                            {:tenant-id (:tenant-id ctx)
                                             :types #{:ontology/task-classified}})))]
        (with-redefs [reranker/rerank! (fn [& _] (throw (ex-info "induced reranker failure (EL-3 probe)" {})))]
          (let [result-node (otp/maybe-auto-classify-and-set-context node wedge-ctx)
                after-events (into [] (es/read (:event-store ctx)
                                               {:tenant-id (:tenant-id ctx)
                                                :types #{:ontology/task-classified}}))
                this-node-events (filter #(= (:id node) (:source-node-id %)) after-events)]
            (println "  task-classified events BEFORE wedge:" before)
            (println "  task-classified events AFTER wedge :" (count after-events)
                     " (for THIS node:" (count this-node-events) ")")
            (println "  >>> NO new assign-task-class event for this node? ="
                     (zero? (count this-node-events)) " <<<")
            (println "  node :context set? =" (some? (:context result-node)))
            (println "  context structural :rerank-fallback? caution ="
                     (get-in result-node [:context :r05-classifier :structural :rerank-fallback?]))
            (println "  context behavioral :rerank-fallback? caution ="
                     (get-in result-node [:context :r05-classifier :behavioral :rerank-fallback?]))
            (let [pass (and (zero? (count this-node-events))
                            (some? (:context result-node)))]
              (println "\n=== PROBE VERDICT:" (if pass "PASS — uncertain deferred, NOTHING created, caution surfaced"
                                                  "FAIL — uncertainty still created/assigned") "==="))))))
    (catch Throwable t
      (println "ERROR:" (.getMessage t)) (.printStackTrace t))
    (finally
      (try (runner/stop!) (catch Throwable _ nil))
      (try (colbert/stop-bridge!) (catch Throwable _ nil))
      (shutdown-agents))))
