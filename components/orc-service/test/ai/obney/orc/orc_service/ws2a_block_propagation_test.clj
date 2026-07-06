(ns ai.obney.orc.orc-service.ws2a-block-propagation-test
  "WS-2a — engine typed block propagation.

   Root cause (WS-1 diagnosis, prototype-confirmed): a Phase-2 :code leaf's
   gated tool call throws an AssertionError (an Error, NOT an Exception). Both
   execute-code (catch Exception) and execute-leaf-node (catch Exception) MISS
   it -> it propagates past both, the leaf's :sheet/complete-node-execution is
   never dispatched, the tick's completion promise is never delivered, and the
   parent (deref p 900000) hangs the full 15-min Phase-2 budget. So the wrong
   :failure status AND the ~17-min timeout are ONE bug.

   The fix (Option A, grilled): an orc-level BLOCK SIGNAL primitive
   (block!/blocking-condition?/block-payload) carrying an OPAQUE payload orc
   never interprets. The engine recognizes it at the leaf, completes the node
   :blocked (so the tick completes and the deref returns immediately), and
   propagates :blocked + the payload up through the composite chain / tree-tick
   to the caller's result. A stub (block! payload) from a :code leaf is the
   test instrument; the propagation is the system under test.

   Harness: real Grain (in-memory event store, schema-validated commands ->
   events -> projections), the real execute-leaf / execute-tree / RLM path,
   the real runtime completion registry (register-completion! + deref p) — the
   SAME plumbing WS-1's hang lives in. Assertions read the completion event /
   tick result / execute result BACK, never a bare return value alone."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.core.block :as block]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Test instruments — stub leaf fns (the block is the instrument; propagation
;; is the SUT). Referenced by fully-qualified symbol string so execute-code's
;; resolve-fn path exercises the REAL leaf execution.
;; =============================================================================

(def ^:private stub-payload
  "Opaque payload — orc must round-trip it verbatim and never interpret it."
  {:orc-block/kind :permission-required
   :tool "shell/exec"
   :request-id "req-ws2a-stub-0001"
   :nested {:args ["ls" "-la"]}})

(defn blocking-leaf-fn
  "A :code leaf that raises the orc block signal (stands in for a gated tool
   call that needs permission)."
  [_ctx]
  (block/block! stub-payload))

(defn plain-exception-leaf-fn
  "A :code leaf that throws an ordinary Exception (backward-compat: -> :failure)."
  [_ctx]
  (throw (ex-info "ordinary leaf failure" {:some :data})))

(defn non-blocking-error-leaf-fn
  "A :code leaf that throws a non-blocking Error (an AssertionError that is NOT
   the block signal). Must NOT be silently treated as :blocked."
  [_ctx]
  (throw (AssertionError. "a plain assert, not the block signal")))

;; =============================================================================
;; Sheet builders
;; =============================================================================

(defn- setup-code-leaf-sheet!
  "Build (sequence (code <fn-sym>)). Returns {:sheet-id :seq-id :leaf-id}."
  [ctx fn-sym]
  (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "WS-2a Block Sheet"))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)]
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :x :string))
    (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
          seq-id (-> seq-result :command-result/events first :node-id)
          leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
          leaf-id (-> leaf-result :command-result/events first :node-id)]
      (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code :fn fn-sym))
      (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [] [:x]))
      {:sheet-id sheet-id :seq-id seq-id :leaf-id leaf-id})))

(defn- dispatch-tick!
  "Dispatch a :sheet/tick-tree command and return {:tick-id :promise}. Mirrors
   the RLM tree executor's register-completion! + deref plumbing so the timing
   proof exercises the real completion promise."
  [ctx sheet-id inputs timeout-ms]
  (let [tick-id (random-uuid)
        p (runtime/register-completion! tick-id)]
    (cp/process-command
      (assoc ctx :command
             {:command/id (random-uuid)
              :command/timestamp (time/now)
              :command/name :sheet/tick-tree
              :sheet-id sheet-id
              :tick-id tick-id
              :inputs inputs
              :options {:timeout-ms timeout-ms}}))
    {:tick-id tick-id :promise p}))

(defn- events-of [ctx event-type]
  (->> (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :types #{event-type}})
       (into [])))

;; =============================================================================
;; Cycle 1 — END-TO-END: a real emitted tree whose :code leaf calls
;; (block! stub-payload) ⇒ the tick COMPLETES with :status :blocked + the
;; opaque payload, the parent (deref p ...) RETURNS PROMPTLY (well under the
;; budget — the WS-1 timing fix), and :blocked + payload reach the caller's
;; result. Read the completion event / tick-tick-completed / delivered result
;; BACK from grain.
;;
;; RED on 1a8cfca1: block! throws an AssertionError; neither execute-code nor
;; execute-leaf-node catches it (both catch Exception only); the future dies,
;; the leaf completion is never dispatched, the tick promise is never
;; delivered, and (deref p ...) hangs until the budget. Here the deref falls
;; back to ::timeout after `budget-ms`, so RED manifests as a NON-:blocked
;; result that took ~budget-ms (with the default 900_000 budget it would hang
;; the full 15 minutes — the ~17-min turn).
;; =============================================================================

(deftest cycle1-leaf-block-completes-tick-blocked-and-deref-returns-fast
  (testing "a :code leaf that raises the block signal ⇒ tick completes :blocked + opaque payload, deref returns promptly, payload reaches the result"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id leaf-id]} (setup-code-leaf-sheet!
                                         ctx "ai.obney.orc.orc-service.ws2a-block-propagation-test/blocking-leaf-fn")
            ;; Bounded budget so the RED witness (a hang to the budget) is
            ;; observable in CI rather than the full 900_000 ms default. The
            ;; GREEN timing proof below is absolute (<< budget), so it holds
            ;; regardless of this bound.
            budget-ms 12000
            {:keys [promise]} (dispatch-tick! ctx sheet-id {} budget-ms)
            t0 (System/currentTimeMillis)
            result (deref promise (+ budget-ms 3000) ::timeout)
            elapsed (- (System/currentTimeMillis) t0)]
        ;; (a) The deref RETURNED (not our fallback) — the tick's completion
        ;; promise was actually delivered.
        (is (not= ::timeout result)
            "the completion promise was delivered — the tick completed instead of hanging")
        ;; (b) It returned PROMPTLY — the WS-1 hang fix. Absolute wall-time
        ;; well under the budget (and astronomically under the 900_000 default).
        (is (< elapsed 5000)
            (str "deref returned promptly (WS-1 timing fix); elapsed=" elapsed
                 "ms vs budget " budget-ms "ms"))
        ;; (c) The delivered result carries :status :blocked + the OPAQUE payload.
        (is (= :blocked (:status result))
            (str "the tick result is :blocked; got " (:status result)))
        (is (= stub-payload (:block-payload result))
            "the opaque payload round-trips to the tick result verbatim")
        ;; (d) Read BACK from grain: the LEAF completed :blocked with the payload.
        (let [leaf-completions (->> (events-of ctx :sheet/node-execution-completed)
                                    (filter #(= leaf-id (:node-id %))))]
          (is (= 1 (count leaf-completions))
              "exactly one leaf completion event was emitted (the future did NOT die)")
          (is (= :blocked (:status (first leaf-completions)))
              "the leaf node-execution-completed event carries :status :blocked")
          (is (= stub-payload (:block-payload (first leaf-completions)))
              "the leaf completion event carries the opaque payload"))
        ;; (e) Read BACK: the tree tick completed with root-status :blocked.
        (let [tick-completions (events-of ctx :sheet/tree-tick-completed)]
          (is (some #(= :blocked (:root-status %)) tick-completions)
              "a tree-tick-completed event carries :root-status :blocked"))))))

;; =============================================================================
;; Cycle 2 — Backward-compat (non-vacuous: the typed-vs-untyped split is the
;; point). Through the public execute-leaf interface, the :code leaf path is a
;; THREE-way split:
;;   - the block signal    -> {:status :blocked} + opaque payload
;;   - an ordinary Exception -> {:status :failure} (today's behavior, unchanged)
;;   - a non-blocking Error  -> RE-THROWN (NOT silently blocked, NOT :failure) —
;;                              exactly as before the change (no new swallowing).
;; Plus an end-to-end tick proving an Exception leaf still completes :failure.
;; =============================================================================

(def ^:private base "ai.obney.orc.orc-service.ws2a-block-propagation-test/")

(defn- code-node [fn-name]
  {:type :leaf :executor :code :fn (str base fn-name) :reads [] :writes [:x]})

(deftest cycle2-execute-leaf-three-way-split
  (testing "the block signal is caught and typed :blocked with the opaque payload"
    (let [result (executor/execute-leaf (code-node "blocking-leaf-fn") {} nil)]
      (is (= :blocked (:status result)) "blocking condition -> :blocked (not :failure)")
      (is (= stub-payload (:block-payload result)) "the opaque payload is captured on the result")))

  (testing "an ordinary Exception is UNCHANGED -> :status :failure, never :blocked"
    (let [result (executor/execute-leaf (code-node "plain-exception-leaf-fn") {} nil)]
      (is (= :failure (:status result)) "ordinary Exception -> :failure (backward-compat)")
      (is (not= :blocked (:status result)) "an ordinary Exception is NOT treated as a block")
      (is (nil? (:block-payload result)) "no block payload on an ordinary failure")
      (is (= "ordinary leaf failure" (:error result)) "the failure message is preserved")))

  (testing "a NON-blocking Error is re-thrown (no new swallowing) — NOT turned into :blocked or :failure"
    ;; Proves the blocking check is SPECIFIC: a generic AssertionError (an Error,
    ;; not the block signal) is not over-caught. It escapes execute-code exactly
    ;; as it did before WS-2a.
    (is (thrown? AssertionError
                 (executor/execute-leaf (code-node "non-blocking-error-leaf-fn") {} nil))
        "a non-blocking Error propagates out of execute-leaf, unchanged")))

(deftest cycle2-end-to-end-ordinary-exception-completes-failure-not-blocked
  (testing "through the real tick: an ordinary Exception leaf completes :failure (not :blocked), with no block payload"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id leaf-id]} (setup-code-leaf-sheet!
                                         ctx (str base "plain-exception-leaf-fn"))
            {:keys [promise]} (dispatch-tick! ctx sheet-id {} 12000)
            result (deref promise 15000 ::timeout)]
        (is (not= ::timeout result) "the tick completed (Exception path completes the node)")
        (is (= :failure (:status result)) (str "ordinary Exception -> :failure; got " (:status result)))
        (is (not= :blocked (:status result)) "an ordinary failure is NOT reported as :blocked")
        (is (nil? (:block-payload result)) "no block payload on an ordinary failure result")
        (let [leaf-completions (->> (events-of ctx :sheet/node-execution-completed)
                                    (filter #(= leaf-id (:node-id %))))]
          (is (= 1 (count leaf-completions)) "the leaf emitted exactly one completion")
          (is (= :failure (:status (first leaf-completions))) "leaf completion is :failure")
          (is (nil? (:block-payload (first leaf-completions))) "no block payload on the leaf failure event"))))))

;; =============================================================================
;; Cycle 3 — RLM / Phase-2 END-TO-END (the headline scenario): a repl-researcher
;; emits a tree whose FIRST :code leaf raises the block signal. The block must
;; propagate through the child Phase-2 tick -> execute-tree's result -> the RLM
;; loop -> the repl-researcher node completion -> the ROOT tick -> the outer
;; execute result, carrying the OPAQUE payload. And no SUBSEQUENT tree leaf runs
;; (budget not wasted). Recursive mode (the default) — a block must SHORT-CIRCUIT
;; the loop, not summarize-and-recur.
;;
;; RED before the RLM short-circuit + node payload propagation: recursive mode
;; summarizes the :blocked tree result and recurs to max-iterations -> the outer
;; status is :failure (not :blocked) and the payload is lost.
;; =============================================================================

(defn after-block-leaf-fn
  "A SECOND :code leaf placed AFTER the blocking leaf. Must NEVER run — a block
   short-circuits the tree."
  [_ctx]
  {:after-block "SHOULD-NOT-RUN"})

(def ^:private blocking-emit-tree-code
  (str "(emit-tree! [:sequence "
       "[:code {:fn \"" base "blocking-leaf-fn\" :reads [] :writes [:seen-marker]}] "
       "[:code {:fn \"" base "after-block-leaf-fn\" :reads [] :writes [:after-block]}] "
       "[:final {:keys [:seen-marker]}]])"))

(defn- setup-blocking-repl-researcher-sheet! [ctx]
  (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "WS-2a RLM Block Sheet"))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)]
    (doseq [k [:question :seen-marker :after-block]]
      (h/run-and-apply! ctx (h/make-declare-key-command sheet-id k :string)))
    (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
          seq-id (-> seq-result :command-result/events first :node-id)
          node-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :repl-researcher :parent-id seq-id))
          node-id (-> node-result :command-result/events first :node-id)]
      (h/run-and-apply! ctx (h/make-set-repl-researcher-config-command
                              sheet-id node-id
                              "Do the task." [:question] [:seen-marker] []
                              :max-iterations 3
                              :rlm {:recursive? true}
                              ;; Bound the Phase-2 child budget so any hang-path
                              ;; regression surfaces bounded (GREEN returns fast).
                              :timeout-ms 12000))
      {:sheet-id sheet-id :node-id node-id})))

(deftest cycle3-rlm-phase2-block-propagates-to-execute-result-no-further-leaves
  (testing "a Phase-2 :code leaf block ⇒ outer execute result is :blocked + opaque payload; the child tick blocked; no subsequent tree leaf ran"
    (h/with-async-test-context [ctx]
      (with-redefs [dscloj.core/predict
                    (fn [_provider _module _inputs _opts]
                      {:outputs {:code blocking-emit-tree-code}
                       :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})]
        (let [{:keys [sheet-id]} (setup-blocking-repl-researcher-sheet! ctx)
              root-tick-id (random-uuid)
              t0 (System/currentTimeMillis)
              result (sheet/execute (assoc ctx :dscloj-provider :openrouter)
                                    sheet-id {:question "go"}
                                    :timeout-ms 30000
                                    :tick-id root-tick-id)
              elapsed (- (System/currentTimeMillis) t0)]
          ;; (a) The outer turn result is :blocked + the OPAQUE payload — the
          ;; block reached execute's result through the whole RLM stack.
          (is (= :blocked (:status result))
              (str "outer execute result is :blocked; got " (:status result)
                   " error " (:error result)))
          (is (= stub-payload (:block-payload result))
              "the opaque payload round-tripped to the outer execute result")
          ;; (b) No hang through the RLM path (recursive loop short-circuited).
          (is (< elapsed 10000) (str "returned promptly through the RLM stack; elapsed=" elapsed "ms"))
          ;; (c) Read BACK: the CHILD Phase-2 tick completed :blocked (a
          ;; tree-tick-completed with a tick-id OTHER than the root tick).
          (let [child-blocked (->> (events-of ctx :sheet/tree-tick-completed)
                                   (filter #(and (not= root-tick-id (:tick-id %))
                                                 (= :blocked (:root-status %)))))]
            (is (seq child-blocked) "the Phase-2 child tick completed with :root-status :blocked"))
          ;; (d) No SUBSEQUENT tree leaf ran: the after-block leaf never wrote
          ;; its sentinel (budget not wasted).
          (let [after-writes (->> (events-of ctx :sheet/execution-value-written)
                                  (filter #(= :after-block (:key %)))
                                  (map :value))]
            (is (empty? after-writes)
                (str "no tree leaf ran after the block; unexpected :after-block writes: "
                     (pr-str after-writes)))))))))

;; =============================================================================
;; Cycle 4 — Phase-1 carrier UNCHANGED. The block signal is an AssertionError
;; SUBCLASS on purpose: orc-sessions' EXISTING Phase-1 turn-level
;; `catch AssertionError` (workflow.clj:1899) must keep catching it after WS-2c
;; switches its permission-pending to (block! ...). This pins the engine-side
;; contract that adopting the signal does NOT regress the Phase-1 pending path.
;; (orc-sessions adoption itself is WS-2c — out of scope here; this asserts the
;; carrier property the engine guarantees.)
;; =============================================================================

(deftest cycle4-block-carrier-is-assertionerror-subclass-phase1-catch-preserved
  (testing "a Phase-1-style `catch AssertionError` still catches the block signal"
    (let [caught (try (block/block! stub-payload)
                      (catch AssertionError e e))]
      (is (instance? AssertionError caught)
          "the carrier IS an AssertionError — a plain `catch AssertionError` catches it")
      ;; It does double duty: still recognizable as the block signal, payload intact.
      (is (block/blocking-condition? caught) "the caught throwable is still the block signal")
      (is (= stub-payload (block/block-payload caught)) "the opaque payload is still accessible")))

  (testing "the carrier is NOT an Exception — this is WHY the leaf path must catch Throwable, and why Phase-1 uses catch AssertionError"
    (let [caught (try (block/block! stub-payload) (catch Throwable t t))]
      (is (not (instance? Exception caught))
          "an ordinary `catch Exception` would MISS it (the WS-1 root cause)")
      (is (instance? Error caught) "it is an Error (AssertionError extends Error)")))

  (testing "specificity: an ordinary AssertionError is NOT the block signal"
    (is (not (block/blocking-condition? (AssertionError. "plain")))
        "a generic AssertionError is not mistaken for the block signal")
    (is (nil? (block/block-payload (AssertionError. "plain")))
        "no payload on a non-signal AssertionError"))

  (testing "the interface re-export exposes the same primitive (WS-2c's surface)"
    (let [caught (try (sheet/block! stub-payload) (catch AssertionError e e))]
      (is (sheet/blocking-condition? caught) "sheet/blocking-condition? recognizes the signal")
      (is (= stub-payload (sheet/block-payload caught)) "sheet/block-payload returns the opaque payload"))))
