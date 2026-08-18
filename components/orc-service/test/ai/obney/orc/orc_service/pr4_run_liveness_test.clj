(ns ai.obney.orc.orc-service.pr4-run-liveness-test
  "PR-4 per-run liveness: a run whose engine goes SILENT past its budget
   yields a bounded, DISTINCT, attributable, non-retryable liveness failure
   at every promise-deref-on-a-run seam — and the process is not starved by
   the wedge (a second run still executes normally).

   Forensic grounding (evidence/pr3/PR3-FORENSIC.md):
   - Shape B (named, W2R-4): a conversation turn wedged inside a NESTED
     runtime/execute launched by the reranker (todo_processors.clj:326 ->
     apply-rerank -> rerank! reranker.clj:392 -> runtime.clj:478). The bound
     must cover nested engine runs.
   - Shape A (thread-invisible, W2R-2/W2R-3): no thread executes the wedged
     run at all. The generic per-run bound is the containment; the
     first-cause hunt is a registered follow-up, not this slice.
   - Shape C (pool exhaustion) is ruled out with data.

   The distinctness contract: a wedge is NEVER a `:timeout` status (the
   reranker retries `:timeout` — RR-1) and its error string must not
   contain the retryable \"timed out\" fragment. A wedge is never retried."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.core.rlm-dsl :as rlm-dsl]
            [ai.obney.orc.orc-service.core.rlm-tree-executor :as tree-executor]))

;; =============================================================================
;; Wedge fixture: work that never finishes and survives interrupts
;; =============================================================================

(defonce ^{:doc "Release valve for forever-blocking-fn. Each test resets it
  to a fresh promise and delivers it in `finally` so the leaked leaf thread
  exits after the assertion instead of living for the JVM's lifetime."}
  release
  (atom nil))

(defn forever-blocking-fn
  "The wedge shape: a leaf effect that never completes and swallows the
   interrupt that `cancel-active-work!` sends. Mirrors the observed wedges,
   where the run went silent below the sheet timeout with no terminal."
  [_]
  (loop []
    (let [p @release]
      (if (and p (realized? p))
        {:output "released"}
        (do (try
              (if p (deref p 50 nil) (Thread/sleep 50))
              (catch InterruptedException _))
            (recur))))))

(defn ok-fn [_] {:output "ok"})

(defn slow-fn
  "Live-but-over-budget shape: blocks well past the run budget but the run
   showed recent engine activity when the budget expired."
  [_]
  (Thread/sleep 2000)
  {:output "late"})

(def wedge-wf
  (sheet/workflow "PR4 wedge"
    (sheet/blackboard {:output :string})
    (sheet/sequence "root"
      (sheet/code "wedge-leaf"
        :fn "ai.obney.orc.orc-service.pr4-run-liveness-test/forever-blocking-fn"
        :reads [] :writes [:output]))))

(def ok-wf
  (sheet/workflow "PR4 ok"
    (sheet/blackboard {:output :string})
    (sheet/sequence "root"
      (sheet/code "ok-leaf"
        :fn "ai.obney.orc.orc-service.pr4-run-liveness-test/ok-fn"
        :reads [] :writes [:output]))))

(def slow-wf
  (sheet/workflow "PR4 slow"
    (sheet/blackboard {:output :string})
    (sheet/sequence "root"
      (sheet/code "slow-leaf"
        :fn "ai.obney.orc.orc-service.pr4-run-liveness-test/slow-fn"
        :reads [] :writes [:output]))))

(defn- assert-wedged-shape
  "The distinctness contract shared by every seam."
  [result seam]
  (is (= :failure (:status result))
      "a wedge is a :failure, never the retryable :timeout status")
  (is (not= :timeout (:status result)))
  (is (true? (:wedged? result)) "the distinct :wedged? discriminator is set")
  (let [liveness (:liveness result)]
    (is (map? liveness) "attribution map present")
    (is (= seam (:seam liveness)) "the seam that fired is named")
    (is (uuid? (:tick-id liveness)) "the wedged run is named by tick-id")
    (is (number? (:waited-ms liveness)) "how long the caller waited")
    (is (number? (:last-activity-age-ms liveness))
        "how long the engine had been silent")
    (is (number? (:grace-ms liveness)) "the grace the engine was given"))
  (is (string? (:error result)))
  (is (str/includes? (:error result) "wedged"))
  (is (not (str/includes? (str/lower-case (:error result)) "timed out"))
      "the wedge error must not carry the retryable timeout fragment"))

;; =============================================================================
;; Seam 1: runtime/execute (runtime.clj promise deref)
;; =============================================================================

(deftest wedged-run-fails-distinctly-within-bound
  (testing "a forever-blocking leaf yields a bounded, distinct, attributable liveness failure"
    (reset! release (promise))
    (try
      (h/with-async-test-context [ctx]
        (let [sheet-id (sheet/build-workflow! ctx wedge-wf)
              t0 (System/currentTimeMillis)
              result (sheet/execute ctx sheet-id {}
                                    :timeout-ms 1500
                                    :result-grace-ms 300)
              elapsed (- (System/currentTimeMillis) t0)]
          (is (< elapsed 15000) "the wait is bounded")
          (assert-wedged-shape result :orc.runtime/execute)
          (is (= (:trace-id result) (get-in result [:liveness :tick-id]))
              "attribution matches the run's trace id")))
      (finally (some-> @release (deliver :done))))))

(deftest live-over-budget-run-keeps-timeout-status
  (testing "a run with recent engine activity at budget expiry is an over-budget :timeout, not a wedge"
    (h/with-async-test-context [ctx]
      (let [sheet-id (sheet/build-workflow! ctx slow-wf)
            ;; the leaf STARTED (a durable node-execution-started event) just
            ;; before expiry, so the engine's silence is younger than grace
            result (sheet/execute ctx sheet-id {}
                                  :timeout-ms 300
                                  :result-grace-ms 5000)]
        (is (= :timeout (:status result))
            "existing over-budget semantics are preserved")
        (is (not (:wedged? result))
            "a live run is never classified as wedged")))))

;; =============================================================================
;; The not-starved property, engine level (the W35 pattern)
;; =============================================================================

(deftest engine-not-starved-after-wedge
  (testing "after a run wedges and its bound fires, a second run in the same process executes normally"
    (reset! release (promise))
    (try
      (h/with-async-test-context [ctx]
        (let [wedge-sheet (sheet/build-workflow! ctx wedge-wf)
              ok-sheet (sheet/build-workflow! ctx ok-wf)
              r1 (sheet/execute ctx wedge-sheet {}
                                :timeout-ms 1500
                                :result-grace-ms 300)
              r2 (sheet/execute ctx ok-sheet {} :timeout-ms 10000)]
          (is (true? (:wedged? r1)) "precondition: the first run wedged")
          (is (= :success (:status r2)) "the second run is not starved")
          (is (= "ok" (get-in r2 [:outputs :output])))))
      (finally (some-> @release (deliver :done))))))

;; =============================================================================
;; Nested-run coverage (Shape B): a nested execute wedges; the OUTER turn
;; receives the distinct failure attributably instead of hanging
;; =============================================================================

(defonce ^{:doc "Full test context handed to the nested-execute leaf, the way
  the reranker's rerank! reaches runtime/execute from inside a running turn."}
  nested-call
  (atom nil))

(defn nested-execute-fn
  "Equivalent of the reranker's nested runtime/execute (reranker.clj:392 ->
   runtime.clj deref): launched from inside an outer run, derefs the nested
   run's promise."
  [_]
  (let [{:keys [ctx sheet-id]} @nested-call
        r (sheet/execute ctx sheet-id {}
                         :timeout-ms 1200
                         :result-grace-ms 300)]
    {:inner-status (name (:status r))
     :inner-wedged (str (boolean (:wedged? r)))
     :inner-tick-id (str (get-in r [:liveness :tick-id] "missing"))}))

(def nested-outer-wf
  (sheet/workflow "PR4 nested outer"
    (sheet/blackboard {:inner-status :string
                       :inner-wedged :string
                       :inner-tick-id :string})
    (sheet/sequence "root"
      (sheet/code "launch-nested"
        :fn "ai.obney.orc.orc-service.pr4-run-liveness-test/nested-execute-fn"
        :reads []
        :writes [:inner-status :inner-wedged :inner-tick-id]))))

(deftest nested-run-wedge-surfaces-attributably-to-outer
  (testing "a nested execute whose promise never delivers fails distinctly at the NESTED seam and the outer run receives it"
    (reset! release (promise))
    (try
      (h/with-async-test-context [ctx]
        (let [wedge-sheet (sheet/build-workflow! ctx wedge-wf)
              outer-sheet (sheet/build-workflow! ctx nested-outer-wf)
              _ (reset! nested-call {:ctx ctx :sheet-id wedge-sheet})
              t0 (System/currentTimeMillis)
              outer (sheet/execute ctx outer-sheet {} :timeout-ms 30000)
              elapsed (- (System/currentTimeMillis) t0)]
          (is (< elapsed 25000) "the outer turn does not hang")
          (is (= :success (:status outer))
              "the outer run completes, carrying the nested verdict")
          (is (= "failure" (get-in outer [:outputs :inner-status]))
              "the nested wedge is NOT the retryable :timeout status")
          (is (= "true" (get-in outer [:outputs :inner-wedged]))
              "the nested seam classified the wedge distinctly")
          (let [inner-tick (get-in outer [:outputs :inner-tick-id])]
            (is (uuid? (parse-uuid inner-tick))
                "the nested run is attributable by tick-id")
            (is (not= (str (:trace-id outer)) inner-tick)
                "attribution names the NESTED run, not the outer one"))))
      (finally (some-> @release (deliver :done))))))

;; =============================================================================
;; Seam 2: execute-stream (streaming.clj promise deref)
;; =============================================================================

(deftest streaming-run-wedge-fails-distinctly
  (testing "the execute-stream result promise resolves to the distinct liveness failure"
    (reset! release (promise))
    (try
      (h/with-async-test-context [ctx]
        (let [sheet-id (sheet/build-workflow! ctx wedge-wf)
              stream (sheet/execute-stream ctx sheet-id {}
                                           :timeout-ms 1500
                                           :result-grace-ms 300)
              result (deref (:result stream) 15000 ::never-delivered)]
          (is (not= ::never-delivered result) "the stream result is bounded")
          (assert-wedged-shape result :orc.streaming/execute-stream)))
      (finally (some-> @release (deliver :done))))))

;; =============================================================================
;; Seam 3: rlm-tree-executor execute-tree (Phase 2 child-tick deref)
;; =============================================================================

(deftest rlm-child-tick-wedge-fails-distinctly
  (testing "a Phase-2 child tick that wedges yields the distinct liveness failure at the tree-executor seam"
    (reset! release (promise))
    (try
      (h/with-async-test-context [ctx]
        (let [tree (rlm-dsl/rlm-dsl->orc-dsl
                    [:sequence
                     [:code {:fn "ai.obney.orc.orc-service.pr4-run-liveness-test/forever-blocking-fn"
                             :reads []
                             :writes [:output]
                             :output-schemas {:output :string}}]
                     [:final {:keys [:output]}]])
              result (tree-executor/execute-tree tree ctx
                                                 {:timeout-ms 1500
                                                  :result-grace-ms 300})]
          (assert-wedged-shape result :orc.rlm-tree-executor/execute-tree)
          (is (uuid? (:trace-id result))
              "the child tick id is surfaced for caller-side cancellation")))
      (finally (some-> @release (deliver :done))))))
