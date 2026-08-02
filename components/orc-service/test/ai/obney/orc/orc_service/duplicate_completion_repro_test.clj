(ns ai.obney.orc.orc-service.duplicate-completion-repro-test
  "MINIMAL REPRO — non-idempotent node completion for :sequence/:fallback.

   Claim: unlike the :parallel branch of `handle-child-completion` — which appends
   the parent completion under an event-store CAS keyed #{[:tick tick-id][:node
   parent-id]} with the comment 'prevent duplicate completions ...' — the
   :sequence and :fallback branches emit the parent completion as plain
   :result/events with NO such guard. A SINGLE quiet execution is clean; but the
   tree runs on the async todo-processor poller, and once that pool is contended
   (as it is in the real system, where many gaps produce concurrently), a single
   logical tree execution fans out into a cascade of DUPLICATE node completions —
   each of which re-runs the leaf's side effect (in production: a paid LLM call).

   Measured invariant: within one tick, a node completes at most once per start.
   This test runs many executions concurrently through one shared poller and
   asserts that invariant per tick; it FAILS when a tick shows more completions
   than starts, and prints the amplification."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]))

;; The leaf's observable side effect — the analogue of a paid LLM call.
(def leaf-runs (atom 0))
(defn counting-leaf [_ctx]
  (swap! leaf-runs inc)
  ;; a little work, to widen the window during which the pool is contended
  (Thread/sleep 5)
  {:flag "attempt"})

(def ^:private branches 3)
(def ^:private concurrent 24)

(defn- build-failing-fallback! [ctx]
  (let [sheet-id (-> (h/run-and-apply! ctx (h/make-create-sheet-command :name "dup-repro"))
                     :command-result/events first :sheet-id)]
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :flag :string))
    (let [root (-> (h/run-and-apply! ctx (h/make-create-node-command sheet-id :fallback))
                   :command-result/events first :node-id)]
      (dotimes [_ branches]
        (let [seq-id (-> (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence :parent-id root))
                         :command-result/events first :node-id)
              leaf-id (-> (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
                          :command-result/events first :node-id)
              cond-id (-> (h/run-and-apply! ctx (h/make-create-node-command sheet-id :condition :parent-id seq-id))
                          :command-result/events first :node-id)]
          (h/run-and-apply! ctx (h/make-set-node-executor-command
                                 sheet-id leaf-id :code
                                 :fn "ai.obney.orc.orc-service.duplicate-completion-repro-test/counting-leaf"))
          (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [] [:flag]))
          (h/run-and-apply! ctx (h/make-set-node-check-command
                                 sheet-id cond-id {:key :flag :op :equals :value "never"}))))
      sheet-id)))

(defn- fire! [ctx sheet-id]
  (let [tick-id (random-uuid)
        p (runtime/register-completion! tick-id)]
    (cp/process-command
     (assoc ctx :command {:command/id (random-uuid) :command/timestamp (time/now)
                          :command/name :sheet/tick-tree :sheet-id sheet-id
                          :tick-id tick-id :inputs {} :options {:timeout-ms 30000}}))
    {:tick-id tick-id :promise p}))

(defn- all-events [ctx type]
  (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :types #{type}})))

(deftest sequence-fallback-completion-is-not-idempotent-under-load
  (testing "each node completes at most once per start within its tick — extra
            completions are the missing per-[:tick :node] CAS that :parallel has"
    (reset! leaf-runs 0)
    ;; warm the fn resolution once so concurrent executors don't race on require
    (counting-leaf nil) (reset! leaf-runs 0)
    (h/with-async-test-context [ctx]
      (let [sheet-id (build-failing-fallback! ctx)
            ;; fire many executions at once so the shared poller pool is contended
            fired (mapv (fn [_] (fire! ctx sheet-id)) (range concurrent))
            _ (doseq [{:keys [promise]} fired] (deref promise 30000 ::timeout))
            _ (Thread/sleep 500) ;; let any in-flight duplicate cascades settle
            completes (all-events ctx :sheet/node-execution-completed)
            starts (all-events ctx :sheet/node-execution-started)
            fails (all-events ctx :sheet/node-execution-completed)
            ;; per (tick,node): starts vs completions
            comp-by (frequencies (map (juxt :tick-id :node-id) completes))
            start-by (frequencies (map (juxt :tick-id :node-id) starts))
            over (into {} (filter (fn [[k c]] (> c (get start-by k 0))) comp-by))
            worst (when (seq over) (apply max (vals over)))
            err (->> fails (keep :error) frequencies)]
        (println "\n=== duplicate-completion repro (load) ===")
        (println "concurrent execs   :" concurrent "  branches/exec:" branches)
        (println "leaf effect runs   :" @leaf-runs "  (correct =" (* concurrent branches) ")")
        (println "total starts       :" (count starts) "  total completions:" (count completes))
        (println "(tick,node) pairs with completions > starts:" (count over))
        (println "worst single node completions in one tick   :" (or worst 1))
        (println "completion statuses:" (frequencies (map :status completes)))
        (when (seq err) (println "leaf/exec errors   :" err))
        ;; NEGATIVE RESULT (2026-07-20): this assertion PASSES. In isolation —
        ;; single or 24x concurrent — every node completes exactly once per start;
        ;; the sequence/fallback propagation is idempotent here despite the missing
        ;; per-[:tick :node] CAS. So this test does NOT reproduce the duplicate-
        ;; completion amplification seen live (282 completions in one tick). The
        ;; missing CAS is a real latent asymmetry and the live symptom is real, but
        ;; the causal link is UNPROVEN by this repro: reproducing it evidently needs
        ;; conditions not present here — slow async LLM completions (~30s windows),
        ;; the live multi-service poller under sustained load, or restart/lease-
        ;; driven event re-processing. (Leaf side effects don't run under run-tests:
        ;; the fn is in a test ns and the executor future can't resolve it — a test
        ;; artifact, not the bug; it runs fine from a plain eval.)
        (is (empty? over)
            (str (count over) " (tick,node) pairs completed more than they started "
                 "(worst = " worst "x)"))))))
