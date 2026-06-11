(ns ai.obney.orc.ontology.consolidation-trigger-test
  "Tests for C-2a-3a: threshold-tracking processor + the
   :ontology/consolidation-requested event + on-demand command.

   This sub-slice ships ONLY the trigger plumbing — no LLM call, no
   :*-description-updated emission. The LLM reflection step lands in
   C-2a-3b; the anti-recency safeguards in C-2a-3c."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [dscloj.core :as dscloj]
            [malli.core :as m]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            ;; Required so the consolidator processor is registered when
            ;; this NS's tests start their ctx. Without this, threshold-
            ;; triggered consolidation-requested events get dispatched but
            ;; the consolidator handler may not be present on the registry,
            ;; producing different behavior depending on load order.
            [ai.obney.orc.ontology.core.consolidator]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

;; QP-2 / Gap-test-isolation-bug: this NS's tests dispatch many events
;; that fire the threshold processor → autonomous :ontology/consolidation-
;; requested events → the consolidator handler launches an async workflow
;; that calls dscloj/predict. Without a stub here, predict hits real
;; OpenRouter (or fails fast), and the consolidator's retry budget keeps
;; the async-thread alive for up to ~5s after the test's Thread/sleep
;; returns. When the next deftest's with-redefs on dscloj/predict
;; activates GLOBALLY (with-redefs modifies the var root, not thread-
;; local), the leftover async-threads HIT the new redef and append to the
;; new test's captured atom — corrupting LATER tests' assertions.
;;
;; Fix: stub dscloj/predict for the lifetime of EVERY deftest in this NS
;; so autonomous consolidations complete fast (no retry-loop bleed).
(defn- stub-predict-fixture [f]
  (with-redefs [dscloj/predict
                (fn [_provider _module _inputs _options]
                  {:outputs {:capabilities ["x"]
                             :strengths [{:trait "x" :good-when "x"
                                          :recommended-pattern "x"
                                          :confidence 1.0
                                          :evidence-count 1
                                          :first-observed-at "2026-06-08T00:00:00Z"
                                          :last-reinforced-at "2026-06-08T00:00:00Z"}]
                             :weaknesses []
                             :representative-uses ["x"]
                             :avoid-when []
                             :summary "trigger-test stub"}
                   :usage {:total-tokens 1}
                   :model "stub"})]
    (f)))

(use-fixtures :each stub-predict-fixture)

;; =============================================================================
;; Test context helpers
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/c2a3a-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :event-pubsub ps
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  ::cache-dir cache-dir}
        processors (reduce-kv
                     (fn [acc proc-name {:keys [handler-fn topics]}]
                       (assoc acc proc-name
                              (tp/start {:event-pubsub ps :topics topics
                                         :handler-fn handler-fn :context base-ctx})))
                     {} @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defn- stop-context [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [es (:event-store ctx)] (es/stop es))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)]
     (try ~@body (finally (stop-context ~sym)))))

;; =============================================================================
;; RED #1 — :ontology/consolidation-requested event schema validates
;; =============================================================================

(deftest consolidation-requested-event-schema-validates
  (testing "A well-formed :ontology/consolidation-requested event passes its schema"
    (let [event {:target-type :node-type
                 :target-id :llm
                 :on-demand? false
                 :requested-at "2026-05-26T00:00:00Z"}
          schema (get @schema-util/registry* :ontology/consolidation-requested)]
      (is (some? schema)
          ":ontology/consolidation-requested schema should be registered")
      (is (m/validate schema event)
          (str "Event should validate. Explanation: "
               (when schema (pr-str (m/explain schema event))))))))

;; =============================================================================
;; RED #2 — :ontology/request-consolidation command emits a matching event
;; =============================================================================

(deftest request-consolidation-command-emits-event
  (testing "Dispatching :ontology/request-consolidation emits :ontology/consolidation-requested with on-demand? true"
    (with-test-ctx [ctx]
      (let [cmd {:command/name :ontology/request-consolidation
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :target-type :node-type
                 :target-id :llm
                 :on-demand? true}
            result (cp/process-command (assoc ctx :command cmd))
            event (first (:command-result/events result))]
        (is (= :ontology/consolidation-requested (:event/type event))
            "Event type is :ontology/consolidation-requested")
        (is (= :node-type (:target-type event))
            "Event carries target-type")
        (is (= :llm (:target-id event))
            "Event carries target-id")
        (is (true? (:on-demand? event))
            "Event carries on-demand? flag")
        (is (string? (:requested-at event))
            "Event stamps :requested-at")))))

;; =============================================================================
;; RED #3 — set-consolidation-threshold command + event-sourced config read-model
;; =============================================================================
;;
;; Event-sourced threshold config: a set-threshold command emits a
;; threshold-set event; the config read-model projects it; lookup returns
;; the set value, or the default 10 when unset.

(deftest threshold-config-defaults-to-10-when-unset
  (testing "get-consolidation-threshold returns the default 10 when no threshold has been set"
    (with-test-ctx [ctx]
      (is (= 10 (ontology/get-consolidation-threshold ctx :node-type))
          "Default threshold is 10 for unset target-types"))))

(deftest set-consolidation-threshold-command-updates-config
  (testing "After :ontology/set-consolidation-threshold sets node-type → 5, get-consolidation-threshold returns 5"
    (with-test-ctx [ctx]
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/set-consolidation-threshold
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :target-type :node-type
                :threshold 5}))
      (Thread/sleep 100)
      (is (= 5 (ontology/get-consolidation-threshold ctx :node-type))
          "Threshold lookup returns the set value")
      (is (= 10 (ontology/get-consolidation-threshold ctx :tree-fingerprint))
          "Other target-types remain at default 10"))))

;; =============================================================================
;; RED #4 — delta-counter read-model
;; =============================================================================
;;
;; The counter ticks for each :sheet/node-execution-completed (per node-type
;; AND per node-instance) and each :sheet/rlm-tree-execution-completed (per
;; tree-fingerprint). It resets to 0 when :ontology/consolidation-requested
;; lands. The read-model itself is pure — independent of the threshold-
;; tracking processor that will consume it.

(defn- complete-node! [ctx sheet-id tick-id node-id node-type status]
  (cp/process-command
    (assoc ctx :command
           {:command/name :sheet/complete-node-execution
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :sheet-id sheet-id
            :tick-id tick-id
            :node-id node-id
            :node-type node-type
            :status status
            :writes {}
            :duration-ms 100})))

(defn- complete-tree! [ctx sheet-id tick-id tree-fingerprint status]
  (cp/process-command
    (assoc ctx :command
           {:command/name :sheet/record-rlm-tree-execution-completion
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :sheet-id sheet-id
            :tick-id tick-id
            :trajectory []
            :total-usage {:total-tokens 0}
            :tree-fingerprint tree-fingerprint
            :status status
            :duration-ms 100})))

(deftest delta-counter-ticks-on-node-execution-completed
  (testing "After 3 node-execution-completed events for :llm, the counter for [:node-type :llm] is 3"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid)
            tick-id  (random-uuid)]
        (complete-node! ctx sheet-id tick-id (random-uuid) :llm :success)
        (complete-node! ctx sheet-id tick-id (random-uuid) :llm :success)
        (complete-node! ctx sheet-id tick-id (random-uuid) :llm :failure)
        (Thread/sleep 100)
        (is (= 3 (ontology/get-consolidation-delta ctx :node-type :llm))
            "Counter ticks once per node-execution-completed with matching node-type")))))

(deftest delta-counter-ticks-on-rlm-tree-execution-completed
  (testing "After 2 rlm-tree-execution-completed events for fp 'abc', the counter for [:tree-fingerprint 'abc'] is 2"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid)
            fp "fp-abc-shape"]
        (complete-tree! ctx sheet-id (random-uuid) fp :success)
        (complete-tree! ctx sheet-id (random-uuid) fp :success)
        (Thread/sleep 100)
        (is (= 2 (ontology/get-consolidation-delta ctx :tree-fingerprint fp))
            "Counter ticks once per rlm-tree-execution-completed with matching fingerprint")))))

(deftest delta-counter-resets-on-consolidation-requested
  (testing "After a :ontology/consolidation-requested lands, the target's counter is reset to 0"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid)
            tick-id  (random-uuid)]
        ;; Accumulate 3 ticks for :code
        (complete-node! ctx sheet-id tick-id (random-uuid) :code :success)
        (complete-node! ctx sheet-id tick-id (random-uuid) :code :success)
        (complete-node! ctx sheet-id tick-id (random-uuid) :code :success)
        (Thread/sleep 100)
        (is (= 3 (ontology/get-consolidation-delta ctx :node-type :code))
            "Pre-reset counter is 3")
        ;; Fire an on-demand consolidation request
        (cp/process-command
          (assoc ctx :command
                 {:command/name :ontology/request-consolidation
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :target-type :node-type
                  :target-id :code
                  :on-demand? true}))
        (Thread/sleep 100)
        (is (= 0 (ontology/get-consolidation-delta ctx :node-type :code))
            "Counter resets to 0 after consolidation-requested for that target")))))

;; =============================================================================
;; RED #5 — threshold processor fires :consolidation-requested at default 10
;; =============================================================================
;;
;; The threshold-tracking processor subscribes to node-execution-completed
;; and rlm-tree-execution-completed events. When a target's delta crosses
;; the configured threshold (default 10), it emits :ontology/request-
;; consolidation. After firing, the consolidation-requested event resets
;; the counter, so we don't double-fire.

(defn- count-consolidation-requested-events [ctx target-type target-id]
  (->> (into [] (es/read (:event-store ctx)
                          {:types #{:ontology/consolidation-requested}
                           :tenant-id (:tenant-id ctx)}))
       (filter #(and (= target-type (:target-type %))
                     (= target-id   (:target-id %))))
       count))

(deftest threshold-processor-fires-at-default-10
  (testing "After 10 node-execution-completed events for :llm, one :ontology/consolidation-requested fires"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid)
            tick-id  (random-uuid)]
        (dotimes [_ 10]
          (complete-node! ctx sheet-id tick-id (random-uuid) :llm :success))
        (Thread/sleep 300)
        (is (= 1 (count-consolidation-requested-events ctx :node-type :llm))
            "Exactly one consolidation-requested fires after the 10th event")
        (is (= 0 (ontology/get-consolidation-delta ctx :node-type :llm))
            "After the trigger fires, the counter is reset to 0")))))

;; =============================================================================
;; RED #6 — per-target-type threshold override
;; =============================================================================
;;
;; The threshold processor reads its threshold from the event-sourced
;; config (via get-consolidation-threshold). Setting the tree-fingerprint
;; threshold to 5 should make tree completions fire at 5, while node-type
;; completions still fire at the default 10.

(deftest per-target-type-threshold-override
  (testing "After setting :tree-fingerprint threshold to 5, tree completions fire at 5 while node-type still fires at 10"
    (with-test-ctx [ctx]
      ;; Configure tree-fingerprint threshold = 5
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/set-consolidation-threshold
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :target-type :tree-fingerprint
                :threshold 5}))
      (Thread/sleep 100)

      ;; Emit 5 tree completions for the same fingerprint
      (let [fp "fp-test-override"
            sheet-id (random-uuid)]
        (dotimes [_ 5]
          (complete-tree! ctx sheet-id (random-uuid) fp :success))
        (Thread/sleep 300)
        (is (= 1 (count-consolidation-requested-events ctx :tree-fingerprint fp))
            "Tree consolidation-requested fires after the 5th tree completion (override active)"))

      ;; Emit 9 node-type completions for :code (should NOT fire — default is 10)
      (let [sheet-id (random-uuid)
            tick-id  (random-uuid)]
        (dotimes [_ 9]
          (complete-node! ctx sheet-id tick-id (random-uuid) :code :success))
        (Thread/sleep 300)
        (is (= 0 (count-consolidation-requested-events ctx :node-type :code))
            ":code node-type still uses default threshold 10 — no fire yet at 9")))))

;; =============================================================================
;; RED #7 — on-demand command bypasses threshold
;; =============================================================================
;;
;; When :ontology/request-consolidation is called with :on-demand? true,
;; the command bypasses the threshold check and always emits the event.

(deftest on-demand-command-bypasses-threshold
  (testing "Request-consolidation with on-demand? true fires regardless of counter state"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid)
            tick-id  (random-uuid)]
        ;; Only 3 events — well below default threshold 10
        (complete-node! ctx sheet-id tick-id (random-uuid) :map-each :success)
        (complete-node! ctx sheet-id tick-id (random-uuid) :map-each :success)
        (complete-node! ctx sheet-id tick-id (random-uuid) :map-each :success)
        (Thread/sleep 200)
        (is (= 3 (ontology/get-consolidation-delta ctx :node-type :map-each))
            "Pre-request delta is 3, well below default 10")
        (is (= 0 (count-consolidation-requested-events ctx :node-type :map-each))
            "No threshold-driven fire yet at delta 3")

        ;; On-demand request bypasses threshold
        (cp/process-command
          (assoc ctx :command
                 {:command/name :ontology/request-consolidation
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :target-type :node-type
                  :target-id :map-each
                  :on-demand? true}))
        (Thread/sleep 200)
        (is (= 1 (count-consolidation-requested-events ctx :node-type :map-each))
            "On-demand request emits consolidation-requested regardless of delta")
        (is (= 0 (ontology/get-consolidation-delta ctx :node-type :map-each))
            "After the on-demand fire, the counter is reset to 0")))))

;; =============================================================================
;; C-Loop-1 — Living Description loop: :tree-class as a target-type
;; =============================================================================
;;
;; Option C: promote :tree-class to a first-class target-type so the
;; consolidator updates per-tree-class bodies — the substrate that
;; R-Inject's classifier reads via get-description. The threshold-driver
;; source for :tree-class is :ontology/task-classified (one event per
;; R-Inject run assigned to that class), distinct from the per-fingerprint
;; layer which counts :sheet/rlm-tree-execution-completed.

(defn- assign-task-class! [ctx assigned-tree-id]
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/assign-task-class
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :source-sheet-id (random-uuid)
            :source-tick-id (random-uuid)
            :source-node-id (random-uuid)
            :assigned-tree-id assigned-tree-id
            :confidence 0.95
            :top-candidates []
            :reasoning "test"
            :was-fresh-mint? false})))

(deftest delta-counter-ticks-on-task-classified-for-tree-class
  (testing "After 3 :ontology/task-classified events for the same :assigned-tree-id, the counter for [:tree-class id] is 3"
    (with-test-ctx [ctx]
      (let [tree-class-id (random-uuid)]
        (assign-task-class! ctx tree-class-id)
        (assign-task-class! ctx tree-class-id)
        (assign-task-class! ctx tree-class-id)
        (Thread/sleep 100)
        (is (= 3 (ontology/get-consolidation-delta ctx :tree-class tree-class-id))
            "Counter ticks once per :ontology/task-classified with matching assigned-tree-id")))))

;; =============================================================================
;; Gap-1 RED#1 — Living Description opt-in flag round-trips
;; =============================================================================
;;
;; New system-level flag gating the WRITING side of the Living Description
;; loop (consolidator + threshold tracking + future per-event evaluator
;; runtime). Mirrors the existing set-consolidation-threshold pattern:
;; event-sourced config, read-model projects state, public query reads it.
;; Default OFF — consumers must explicitly opt in.

(deftest living-description-enabled-defaults-false-when-unset
  (testing "get-living-description-enabled? returns false when no opt-in event has been emitted"
    (with-test-ctx [ctx]
      (is (false? (ontology/get-living-description-enabled? ctx))
          "Default is false (opt-in only)"))))

(deftest set-living-description-enabled-true-then-query-returns-true
  (testing "After :ontology/set-living-description-enabled true, the query returns true"
    (with-test-ctx [ctx]
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/set-living-description-enabled
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :enabled? true}))
      (Thread/sleep 100)
      (is (true? (ontology/get-living-description-enabled? ctx))
          "Query reflects the set value"))))

(deftest set-living-description-enabled-false-after-true-flips-it-off
  (testing "Setting false after true flips the flag back off"
    (with-test-ctx [ctx]
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/set-living-description-enabled
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :enabled? true}))
      (Thread/sleep 100)
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/set-living-description-enabled
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :enabled? false}))
      (Thread/sleep 100)
      (is (false? (ontology/get-living-description-enabled? ctx))
          "After flip-back, query returns false"))))

(deftest threshold-processor-fires-on-task-classified-for-tree-class
  (testing "After threshold-N task-classified events for the same tree-class-id, one :ontology/consolidation-requested fires"
    (with-test-ctx [ctx]
      ;; Lower threshold to 3 for a fast deterministic test
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/set-consolidation-threshold
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :target-type :tree-class
                :threshold 3}))
      (Thread/sleep 100)
      (let [tree-class-id (random-uuid)]
        (assign-task-class! ctx tree-class-id)
        (assign-task-class! ctx tree-class-id)
        (assign-task-class! ctx tree-class-id)
        (Thread/sleep 400)
        (is (= 1 (count-consolidation-requested-events ctx :tree-class tree-class-id))
            "Exactly one consolidation-requested fires after threshold is crossed")
        (is (= 0 (ontology/get-consolidation-delta ctx :tree-class tree-class-id))
            "Counter resets to 0 after the trigger fires")))))
