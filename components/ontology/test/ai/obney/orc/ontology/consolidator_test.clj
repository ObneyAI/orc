(ns ai.obney.orc.ontology.consolidator-test
  "Tests for C-2a-3b: consolidator processor + LLM reflection.

   The consolidator subscribes to :ontology/consolidation-requested
   events. For each, it pulls the target's current description, recent
   events, accumulated metrics, and structural context, runs a single
   structured-output LLM reflection call, and emits the matching
   :*-description-updated event with the validated body.

   Tests fake the LLM call via `with-redefs` on dscloj/predict so no
   real network calls happen during dev iteration. The HITL live-verify
   uses a real LLM."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [dscloj.core :as dscloj]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.ontology.core.consolidator]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.core.commands]
            [ai.obney.orc.orc-service.core.read-models]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Test context helpers
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/c2a3b-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :event-pubsub ps
                  :dscloj-provider :openrouter
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
;; Fake LLM response — well-formed description-body
;; =============================================================================

(def fake-description-body
  "A minimal but well-formed description-body that the fake LLM returns.
   Used by tests that don't care about content, only orchestration."
  {:capabilities ["x"]
   :strengths [{:trait "stable structured output"
                :good-when "structured output desired"
                :recommended-pattern "[:llm {:output-schemas {...}}]"
                :confidence 1.0
                :evidence-count 1
                :first-observed-at "2026-05-26T00:00:00Z"
                :last-reinforced-at "2026-05-26T00:00:00Z"}]
   :weaknesses [{:trait "rate-limit risk under unbounded concurrency"
                 :avoid-when "high event rates AND no :max-concurrency cap"
                 :recommended-alternative "bound :max-concurrency to 3 on :map-each"
                 :confidence 1.0
                 :evidence-count 1
                 :first-observed-at "2026-05-26T00:00:00Z"
                 :last-reinforced-at "2026-05-26T00:00:00Z"}]
   :representative-uses ["per-chunk extraction"]
   :avoid-when ["work is deterministic — use :code"]
   :summary "Sample LLM-authored description body."
   :version 99           ;; LLM-authored; consolidator overwrites
   :consolidated-from-event-count 99 ;; same
   })

(defn- with-faked-llm
  "Run body with dscloj/predict stubbed to return a synthetic well-formed
   description body. The fake returns one :outputs entry PER U11 :writes
   key — matching the consolidator's six-key structured-output contract
   (capabilities / strengths / weaknesses / representative-uses /
   avoid-when / summary). The :version + :consolidated-from-event-count
   values in `response` are ignored — the consolidator computes them."
  [response f]
  (with-redefs [dscloj/predict (fn [_provider _module _inputs _options]
                                  {:outputs (select-keys response
                                                          [:capabilities
                                                           :strengths
                                                           :weaknesses
                                                           :representative-uses
                                                           :avoid-when
                                                           :summary])
                                   :usage {:total-tokens 100}
                                   :model "fake-model"})]
    (f)))

;; =============================================================================
;; RED #1 — consolidator processor receives :ontology/consolidation-requested
;; =============================================================================
;;
;; Tracer-bullet: after the on-demand command emits consolidation-requested,
;; the consolidator handler runs end-to-end. The handler effect we observe:
;; a matching :*-description-updated event lands in the store.

(deftest consolidator-receives-consolidation-requested-event
  (testing "After an on-demand consolidation-requested, the consolidator emits a matching :*-description-updated event"
    (with-test-ctx [ctx]
      (with-faked-llm fake-description-body
        (fn []
          (cp/process-command
            (assoc ctx :command
                   {:command/name :ontology/request-consolidation
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :target-type :node-type
                    :target-id :llm
                    :on-demand? true}))
          (Thread/sleep 500)
          (let [body (ontology/get-description ctx :node-type :llm)]
            (is (some? body)
                "Consolidator should have emitted a :ontology/node-type-description-updated event")
            (is (= "Sample LLM-authored description body." (:summary body))
                "Description body should contain the LLM's summary")))))))

;; =============================================================================
;; RED #3 — Version increments correctly (consolidator overwrites LLM value)
;; =============================================================================
;;
;; The LLM may return any :version (the fake returns 99). The consolidator
;; MUST overwrite with the computed value: 1 if no prior description,
;; (inc prior-version) otherwise. This isolates the consolidator from any
;; version-numbering drift in the LLM's output.

(deftest first-consolidation-emits-version-1
  (testing "With no prior description in the store, the consolidator emits :version 1"
    (with-test-ctx [ctx]
      (with-faked-llm fake-description-body
        (fn []
          (cp/process-command
            (assoc ctx :command
                   {:command/name :ontology/request-consolidation
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :target-type :node-type
                    :target-id :code
                    :on-demand? true}))
          (Thread/sleep 500)
          (let [body (ontology/get-description ctx :node-type :code)]
            (is (= 1 (:version body))
                "First consolidation overrides LLM's :version 99 to computed :version 1")))))))

(deftest subsequent-consolidation-increments-version
  (testing "With a prior description at version 3, the consolidator emits :version 4"
    (with-test-ctx [ctx]
      ;; Seed a prior description at version 3
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/record-node-type-description
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :target-id :map-each
                :body (assoc fake-description-body
                              :version 3
                              :summary "Prior body at v3")}))
      (Thread/sleep 200)
      (is (= 3 (:version (ontology/get-description ctx :node-type :map-each)))
          "Sanity: prior body is at v3")
      ;; Now consolidate
      (with-faked-llm fake-description-body
        (fn []
          (cp/process-command
            (assoc ctx :command
                   {:command/name :ontology/request-consolidation
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :target-type :node-type
                    :target-id :map-each
                    :on-demand? true}))
          (Thread/sleep 500)
          (let [body (ontology/get-description ctx :node-type :map-each)]
            (is (= 4 (:version body))
                "Consolidator overrides LLM's :version 99 to computed :version 4 (prior 3 + 1)")
            (is (= "Sample LLM-authored description body." (:summary body))
                "Body otherwise reflects the LLM's output")))))))

;; =============================================================================
;; RED #4 — :consolidated-from-event-count matches the recent-window size
;; =============================================================================
;;
;; The LLM returns :consolidated-from-event-count 99 (fake). The consolidator
;; MUST overwrite with the actual count of events it pulled into the
;; reflection prompt. Drives implementation of gather-recent-events —
;; for this slice, it pulls all :sheet/node-execution-completed events
;; for the target's node-type from the event store.

(defn- complete-node-event! [ctx node-type]
  (cp/process-command
    (assoc ctx :command
           {:command/name :sheet/complete-node-execution
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :sheet-id (random-uuid)
            :tick-id (random-uuid)
            :node-id (random-uuid)
            :node-type node-type
            :status :success
            :writes {}
            :duration-ms 100})))

(deftest consolidated-from-event-count-matches-recent-events
  (testing "After 3 node-execution-completed events for the target, the consolidator's emitted body has :consolidated-from-event-count 3"
    (with-test-ctx [ctx]
      (dotimes [_ 3] (complete-node-event! ctx :sequence))
      (Thread/sleep 200)
      (with-faked-llm fake-description-body
        (fn []
          (cp/process-command
            (assoc ctx :command
                   {:command/name :ontology/request-consolidation
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :target-type :node-type
                    :target-id :sequence
                    :on-demand? true}))
          (Thread/sleep 2000)
          (let [body (ontology/get-description ctx :node-type :sequence)]
            (is (= 3 (:consolidated-from-event-count body))
                "Consolidator computes count from gathered events, overriding LLM's 99")))))))

;; =============================================================================
;; RED #5 — Malli-validation failure aborts cleanly (no event emitted)
;; =============================================================================
;;
;; When the LLM returns a malformed body (e.g., missing :strengths), U11
;; output-schemas Malli validation rejects it inside the workflow execution.
;; The consolidator must abort cleanly: NO :*-description-updated event
;; emitted, error logged. (Retry-once-then-abort lands in C-2a-3c.)

(def malformed-description-body
  "LLM output that's missing required keys (:strengths). Used to verify
   that the U11 :output-schemas Malli validation catches it and the
   consolidator aborts cleanly."
  {:capabilities ["x"]
   ;; Intentionally missing :strengths, :weaknesses, etc.
   :summary "Malformed body."})

(deftest malformed-llm-output-aborts-without-emitting
  (testing "When the LLM returns a malformed body, no :*-description-updated event is emitted"
    (with-test-ctx [ctx]
      (with-faked-llm malformed-description-body
        (fn []
          (cp/process-command
            (assoc ctx :command
                   {:command/name :ontology/request-consolidation
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :target-type :node-type
                    :target-id :parallel
                    :on-demand? true}))
          (Thread/sleep 1500)
          (is (nil? (ontology/get-description ctx :node-type :parallel))
              "Malformed LLM output should produce NO description event"))))))

;; =============================================================================
;; RED #6 — Fan-out to all three target types
;; =============================================================================
;;
;; The consolidator must produce the matching :*-description-updated event
;; for each of the three target types. Same LLM body used for all three;
;; the consolidator routes via record-description-command's case
;; dispatching.

(deftest fan-out-to-all-three-target-types
  (testing "Consolidator emits the matching :*-description-updated event for each target type"
    (with-test-ctx [ctx]
      (let [node-instance-target [(random-uuid) (random-uuid)]
            tree-fp "fp-consolidator-fan-out"]
        (with-faked-llm fake-description-body
          (fn []
            ;; node-type
            (cp/process-command
              (assoc ctx :command
                     {:command/name :ontology/request-consolidation
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :target-type :node-type
                      :target-id :final
                      :on-demand? true}))
            ;; node-instance
            (cp/process-command
              (assoc ctx :command
                     {:command/name :ontology/request-consolidation
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :target-type :node-instance
                      :target-id node-instance-target
                      :on-demand? true}))
            ;; tree-fingerprint
            (cp/process-command
              (assoc ctx :command
                     {:command/name :ontology/request-consolidation
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :target-type :tree-fingerprint
                      :target-id tree-fp
                      :on-demand? true}))
            (Thread/sleep 2000)
            (is (some? (ontology/get-description ctx :node-type :final))
                "node-type target produces a node-type description event")
            (is (some? (ontology/get-description ctx :node-instance node-instance-target))
                "node-instance target produces a node-instance description event")
            (is (some? (ontology/get-description ctx :tree-fingerprint tree-fp))
                "tree-fingerprint target produces a tree description event")))))))

;; =============================================================================
;; C-2a-3c — Anti-recency safeguards (Layer 2: aggregate + delta in prompt)
;; =============================================================================
;;
;; To verify the consolidator passes real aggregate metrics from C-2a-2's
;; rolling-metrics aggregator into the LLM input, we install a fake LLM
;; that captures its inputs and assert :aggregate-metrics is non-nil after
;; some source events have been emitted.

(defn- complete-node-event-with-status! [ctx node-type status]
  (cp/process-command
    (assoc ctx :command
           {:command/name :sheet/complete-node-execution
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :sheet-id (random-uuid)
            :tick-id (random-uuid)
            :node-id (random-uuid)
            :node-type node-type
            :status status
            :writes {}
            :duration-ms 100})))

(defn- with-input-capturing-llm
  "Like with-faked-llm but ALSO captures the inputs passed to dscloj/predict
   into the supplied atom. The fake returns the well-formed description-body
   regardless."
  [captured-inputs response f]
  (with-redefs [dscloj/predict
                (fn [_provider _module inputs _options]
                  (swap! captured-inputs conj inputs)
                  {:outputs (select-keys response
                                          [:capabilities :strengths :weaknesses
                                           :representative-uses :avoid-when :summary])
                   :usage {:total-tokens 100}
                   :model "fake-model"})]
    (f)))

;; =============================================================================
;; C-2a-3c — Layer 2: recent-vs-historical delta computation
;; =============================================================================
;;
;; The consolidator computes recent success-rate from recent-events and
;; historical success-rate from aggregate-metrics; passes the delta into
;; the LLM as a separate input so the model can see "recent vs aggregate"
;; without conflating them.

(deftest delta-is-computed-from-recent-vs-historical
  (testing "compute-delta returns recent-success-rate, historical-success-rate, and the delta when aggregate-metrics is non-nil"
    (let [aggregate {:success-count 80 :failure-count 20 :total-duration 0 :executions []}
          recent-events [{:status :success} {:status :success} {:status :failure}
                         {:status :failure} {:status :failure}]
          delta (#'ai.obney.orc.ontology.core.consolidator/compute-delta
                  aggregate recent-events)]
      (is (some? delta) "compute-delta returns a map")
      (is (= 0.4 (:recent-success-rate delta))
          "recent-success-rate = 2 successes / 5 events")
      (is (= 0.8 (:historical-success-rate delta))
          "historical-success-rate = 80 / (80 + 20)")
      (is (< (Math/abs (- -0.4 (:delta delta))) 1e-9)
          "delta = recent - historical = 0.4 - 0.8 = -0.4 (recent is worse)"))))

(deftest delta-nil-when-no-aggregate
  (testing "compute-delta returns nil when aggregate-metrics is nil (first consolidation has no historical to compare against)"
    (is (nil? (#'ai.obney.orc.ontology.core.consolidator/compute-delta
                nil
                [{:status :success}])))))

(deftest delta-passed-to-llm-when-aggregate-exists
  (testing "After source events, the consolidator passes recent-vs-historical-delta to the LLM"
    (with-test-ctx [ctx]
      (dotimes [_ 7] (complete-node-event-with-status! ctx :llm :success))
      (dotimes [_ 3] (complete-node-event-with-status! ctx :llm :failure))
      (Thread/sleep 300)
      (let [captured (atom [])]
        (with-input-capturing-llm captured fake-description-body
          (fn []
            (cp/process-command
              (assoc ctx :command
                     {:command/name :ontology/request-consolidation
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :target-type :node-type
                      :target-id :llm
                      :on-demand? true}))
            (Thread/sleep 1500)
            (let [inputs (first @captured)
                  delta (get inputs :recent-vs-historical-delta)
                  delta-text (if (string? delta) delta (pr-str delta))]
              (is (some? delta) ":recent-vs-historical-delta should be present in the LLM input")
              ;; recent-success-rate = 7/10 = 0.7, historical-success-rate = 7/10
              ;; (events ARE the aggregate, so delta is 0.0).
              (is (or (str/includes? delta-text "delta")
                      (str/includes? delta-text "rate"))
                  (str "delta payload mentions delta/rate fields: "
                       (subs delta-text 0 (min 200 (count delta-text))))))))))))

;; =============================================================================
;; C-2a-3c — Layer 4: status-shape forbidden in the prompt (no runtime check)
;; =============================================================================
;;
;; Decision (2026-05-26): the production gate is the PROMPT, not a post-hoc
;; runtime check. Hardcoded phrase matching against status-words is brittle
;; (misses synonyms, false-positives on legitimate uses), so the prompt does
;; the work and the test only verifies the prompt's content.
;;
;; If the live verify shows status-shaped leakage, the fix is prompt tuning
;; (or adding an LLM-as-judge classifier in a future slice), NOT a hardcoded
;; backstop.

(deftest reflection-prompt-forbids-status-shaped-entries
  (testing "Reflection prompt explicitly forbids status-shaped output with concrete examples"
    (let [instr @#'ai.obney.orc.ontology.core.consolidator/reflection-instruction
          lower (str/lower-case instr)]
      (is (str/includes? lower "investigate")
          "Prompt mentions 'investigate' as a forbidden status-word example")
      (is (str/includes? lower "observed")
          "Prompt mentions 'observed' as a forbidden status-word example")
      (is (or (str/includes? lower "never produce")
              (str/includes? lower "must not")
              (str/includes? lower "do not"))
          "Prompt uses imperative language forbidding status-shapes")
      (is (or (str/includes? lower "actionable")
              (str/includes? lower "principle-shape"))
          "Prompt explains that every entry must carry actionable principle-shaped content"))))

;; =============================================================================
;; C-2a-3c — Budget cap
;; =============================================================================
;;
;; Hourly rolling budget per target-type. Default 100/hour. When exceeded,
;; subsequent consolidation-requested events no-op (LLM call skipped, no
;; new description emitted). Verified by setting the budget low and
;; firing N+1 consolidation-requested events.

(deftest budget-cap-blocks-consolidation-after-limit
  (testing "After setting node-type budget to 2, three sequential consolidations produce only 2 description-updated events; the 3rd is blocked"
    (with-test-ctx [ctx]
      ;; Set budget = 2 / hour for :node-type
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/set-consolidation-budget
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :target-type :node-type
                :budget 2}))
      (Thread/sleep 200)
      (with-faked-llm fake-description-body
        (fn []
          ;; Fire consolidations SEQUENTIALLY with waits between — mirrors
          ;; real-world cadence (threshold-driven, spread over time). The
          ;; test would burst-race the read-model if fired back-to-back;
          ;; in production, threshold-tracking serializes these naturally.
          (dotimes [i 3]
            (cp/process-command
              (assoc ctx :command
                     {:command/name :ontology/request-consolidation
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :target-type :node-type
                      :target-id (keyword (str "budget-test-" i))
                      :on-demand? true}))
            ;; Wait for the consolidator handler to complete + the
            ;; description-updated event to project through the
            ;; recent-consolidations read-model before the next budget
            ;; check runs.
            (Thread/sleep 1500))
          (let [t0 (ontology/get-description ctx :node-type :budget-test-0)
                t1 (ontology/get-description ctx :node-type :budget-test-1)
                t2 (ontology/get-description ctx :node-type :budget-test-2)
                emitted-count (count (filter some? [t0 t1 t2]))]
            (is (= 2 emitted-count)
                (str "Only 2 description-updated events should be emitted "
                     "(targets " (if t0 "0✓" "0✗") " " (if t1 "1✓" "1✗") " " (if t2 "2✓" "2✗") ")."
                     " The 3rd is blocked by the budget cap."))))))))

(deftest reflection-prompt-includes-anti-recency-framing
  (testing "The reflection-instruction explicitly frames aggregate as stable baseline and recent as leading indicator"
    (let [instr (str/lower-case @#'ai.obney.orc.ontology.core.consolidator/reflection-instruction)]
      (is (or (str/includes? instr "stable baseline")
              (str/includes? instr "leading indicator"))
          "Prompt must include explicit anti-recency framing — phrase 'stable baseline' or 'leading indicator'")
      (is (str/includes? instr "aggregate")
          "Prompt mentions :aggregate-metrics input")
      (is (or (str/includes? instr "recent-vs-historical")
              (str/includes? instr "delta"))
          "Prompt mentions the :recent-vs-historical-delta input"))))

(deftest aggregate-metrics-passed-to-llm-when-source-events-exist
  (testing "After source events for the target, the consolidator passes non-nil :aggregate-metrics to the LLM. The workflow's serialize-for-llm step JSON-encodes the map before it reaches dscloj — we verify the encoded payload contains the expected success/failure counts."
    (with-test-ctx [ctx]
      ;; Emit 6 source events (4 success + 2 failure) for :llm
      (dotimes [_ 4] (complete-node-event-with-status! ctx :llm :success))
      (dotimes [_ 2] (complete-node-event-with-status! ctx :llm :failure))
      (Thread/sleep 300)
      (let [captured (atom [])]
        (with-input-capturing-llm captured fake-description-body
          (fn []
            (cp/process-command
              (assoc ctx :command
                     {:command/name :ontology/request-consolidation
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :target-type :node-type
                      :target-id :llm
                      :on-demand? true}))
            (Thread/sleep 1500)
            (is (= 1 (count @captured))
                "Exactly one LLM call expected (on-demand consolidation)")
            (let [inputs (first @captured)
                  agg (get inputs :aggregate-metrics)
                  agg-text (if (string? agg) agg (pr-str agg))]
              (is (string? agg-text)
                  ":aggregate-metrics should be present in the LLM input (string after serialize-for-llm)")
              (is (or (str/includes? agg-text "\"success-count\":4")
                      (str/includes? agg-text ":success-count 4"))
                  (str ":aggregate-metrics payload should contain success-count 4 — got: "
                       (subs agg-text 0 (min 200 (count agg-text)))))
              (is (or (str/includes? agg-text "\"failure-count\":2")
                      (str/includes? agg-text ":failure-count 2"))
                  (str ":aggregate-metrics payload should contain failure-count 2 — got: "
                       (subs agg-text 0 (min 200 (count agg-text))))))))))))

;; =============================================================================
;; C-Loop-1 — consolidator handles :tree-class consolidation-requested
;; =============================================================================
;;
;; The Living Description loop's substrate: when a tree-class accumulates
;; threshold task-classified events (or an on-demand request fires), the
;; consolidator gathers the task-classified evidence and emits an
;; :ontology/tree-description-updated event under :target-type :tree-class.
;; The body the classifier reads via get-description :tree-class reflects
;; the LLM's reflection over the observed evidence.

(defn- assign-task-class-evidence! [ctx assigned-tree-id]
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

(deftest consolidator-handles-tree-class-consolidation-request
  (testing "After on-demand :tree-class consolidation-requested, an :ontology/tree-description-updated event with :target-type :tree-class lands and get-description :tree-class returns the LLM body"
    (with-test-ctx [ctx]
      (with-faked-llm fake-description-body
        (fn []
          (let [tree-class-id (random-uuid)]
            (assign-task-class-evidence! ctx tree-class-id)
            (Thread/sleep 100)
            (cp/process-command
              (assoc ctx :command
                     {:command/name :ontology/request-consolidation
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :target-type :tree-class
                      :target-id tree-class-id
                      :on-demand? true}))
            (Thread/sleep 600)
            (let [body (ontology/get-description ctx :tree-class tree-class-id)]
              (is (some? body)
                  "Consolidator should have emitted :ontology/tree-description-updated for :tree-class")
              (is (= "Sample LLM-authored description body." (:summary body))
                  "Body's :summary should carry the LLM's content")
              (is (pos? (:consolidated-from-event-count body))
                  ":consolidated-from-event-count should reflect the recent-events window size, > 0"))))))))

;; =============================================================================
;; RED #7 — Living Description loop ceiling check (two-run verify, mocked LLM)
;; =============================================================================
;;
;; End-to-end flow C-Loop-1 enables:
;;   1. Bootstrap: a seed body exists under :tree-class scope
;;   2. R-Inject "run 1": apply-r05-classifier-context reads :tree-class
;;      body (the seed bootstrap)
;;   3. Threshold N task-classified events accumulate; threshold processor
;;      emits :ontology/consolidation-requested for :tree-class
;;   4. Consolidator runs LLM reflection, emits :tree-description-updated
;;      with :target-type :tree-class — body is the LLM's refined output
;;   5. R-Inject "run 2": apply-r05-classifier-context reads :tree-class
;;      body — NOW returns the consolidator-written body, NOT the seed
;;
;; This synthetic version emits the events directly (no live R-Inject) and
;; stubs the LLM so the test is deterministic + fast. A live two-run
;; verify (running through the bench) is a separate HITL step.

(def updated-fake-body
  "A second fake body the LLM returns on the consolidator's second cycle —
   distinct content from fake-description-body so we can assert the
   prepend body actually changed."
  {:capabilities ["post-consolidation capability"]
   :strengths [{:trait "post-consolidation strength"
                :good-when "after observed evidence has been processed"
                :recommended-pattern "[:llm {:output-schemas {...}}]"
                :confidence 1.0
                :evidence-count 5
                :first-observed-at "2026-06-02T00:00:00Z"
                :last-reinforced-at "2026-06-02T00:00:00Z"}]
   :weaknesses [{:trait "post-consolidation weakness — observed under load"
                 :avoid-when "high concurrency with no rate-limit handling"
                 :recommended-alternative "bound :max-concurrency to 3"
                 :confidence 1.0
                 :evidence-count 5
                 :first-observed-at "2026-06-02T00:00:00Z"
                 :last-reinforced-at "2026-06-02T00:00:00Z"}]
   :representative-uses ["the task this consolidation observed"]
   :avoid-when ["work is deterministic"]
   :summary "Post-consolidation summary — refined from observation."
   :version 99
   :consolidated-from-event-count 99})

(deftest two-run-living-description-loop-updates-tree-class-body
  (testing "C-Loop-1 ceiling check: after the consolidator processes N task-classified events for a tree-class, get-description :tree-class returns the consolidator-written body, distinct from the original seed body"
    (with-test-ctx [ctx]
      (let [tree-class-id (random-uuid)
            seed-body {:capabilities ["seed-bootstrap capability"]
                       :strengths [{:trait "seed-bootstrap strength"
                                    :good-when "bootstrap context"
                                    :recommended-pattern "[:llm {:output-schemas {...}}]"
                                    :confidence 1.0
                                    :evidence-count 1
                                    :first-observed-at "2026-05-26T00:00:00Z"
                                    :last-reinforced-at "2026-05-26T00:00:00Z"}]
                       :weaknesses [{:trait "seed-bootstrap weakness"
                                     :avoid-when "bootstrap edge"
                                     :recommended-alternative "bootstrap alternative"
                                     :confidence 1.0
                                     :evidence-count 1
                                     :first-observed-at "2026-05-26T00:00:00Z"
                                     :last-reinforced-at "2026-05-26T00:00:00Z"}]
                       :representative-uses ["bootstrap representative use"]
                       :avoid-when ["bootstrap avoid"]
                       :summary "Seed bootstrap summary — present before any R-Inject run."
                       :version 1
                       :consolidated-from-event-count 0}]
        (cp/process-command
          (assoc ctx :command
                 {:command/name :ontology/record-tree-class-description
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :target-id tree-class-id
                  :body seed-body}))
        (Thread/sleep 100)
        (let [run-1-body (ontology/get-description ctx :tree-class tree-class-id)]
          (is (= "Seed bootstrap summary — present before any R-Inject run."
                 (:summary run-1-body))
              "Run-1 prepend body is the bootstrap seed")
          (is (= 0 (:consolidated-from-event-count run-1-body))
              "Run-1 bootstrap body has no consolidator-observed evidence yet")
          (cp/process-command
            (assoc ctx :command
                   {:command/name :ontology/set-consolidation-threshold
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :target-type :tree-class
                    :threshold 3}))
          (Thread/sleep 100)
          (with-faked-llm updated-fake-body
            (fn []
              (dotimes [_ 3]
                (assign-task-class-evidence! ctx tree-class-id))
              (Thread/sleep 1500)))
          (let [run-2-body (ontology/get-description ctx :tree-class tree-class-id)]
            (is (not= run-1-body run-2-body)
                "Run-2 body differs from Run-1 body — the Living Description loop produced an update")
            (is (= "Post-consolidation summary — refined from observation."
                   (:summary run-2-body))
                "Run-2 prepend body's :summary is the LLM-refined content")
            (is (pos? (:consolidated-from-event-count run-2-body))
                "Run-2 body's :consolidated-from-event-count reflects observed events (> 0)")
            (is (= 2 (:version run-2-body))
                "Consolidator increments version from 1 (seed) to 2 (first consolidation)")))))))

;; =============================================================================
;; RED #8 — gather-recent-events joins task-classified + execution events
;; =============================================================================
;;
;; For :tree-class targets, the consolidator's LLM input must carry both
;; the classification metadata AND the execution outcome per observation.
;; Without execution context, the LLM only sees "this task was classified
;; here at confidence X" — it can't reason about which tree-shapes worked
;; under load, what the model designed, how the run completed.

(defn- assign-task-class-to-sheet! [ctx sheet-id tree-class-id]
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/assign-task-class
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :source-sheet-id sheet-id
            :source-tick-id (random-uuid)
            :source-node-id (random-uuid)
            :assigned-tree-id tree-class-id
            :confidence 0.95
            :top-candidates []
            :reasoning "test"
            :was-fresh-mint? false})))

(defn- complete-tree-for-sheet! [ctx sheet-id fp status]
  (cp/process-command
    (assoc ctx :command
           {:command/name :sheet/record-rlm-tree-execution-completion
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :sheet-id sheet-id
            :tick-id (random-uuid)
            :trajectory []
            :total-usage {:total-tokens 1000}
            :tree-fingerprint fp
            :status status
            :duration-ms 5000})))

(deftest consolidator-input-joins-task-classified-with-execution-events
  (testing "C-Loop-1 RED#8: for :tree-class targets, the LLM input's recent-events carry both classification + execution-outcome per observation"
    (with-test-ctx [ctx]
      (let [tree-class-id (random-uuid)
            sheet-1 (random-uuid)
            sheet-2 (random-uuid)
            sheet-3 (random-uuid)
            captured (atom [])]
        (assign-task-class-to-sheet! ctx sheet-1 tree-class-id)
        (complete-tree-for-sheet! ctx sheet-1 "fp-A" :success)
        (assign-task-class-to-sheet! ctx sheet-2 tree-class-id)
        (complete-tree-for-sheet! ctx sheet-2 "fp-A" :success)
        (assign-task-class-to-sheet! ctx sheet-3 tree-class-id)
        (complete-tree-for-sheet! ctx sheet-3 "fp-B" :failure)
        (Thread/sleep 200)
        (with-input-capturing-llm captured fake-description-body
          (fn []
            (cp/process-command
              (assoc ctx :command
                     {:command/name :ontology/request-consolidation
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :target-type :tree-class
                      :target-id tree-class-id
                      :on-demand? true}))
            (Thread/sleep 1500)))
        (let [inputs (first @captured)
              recent (get inputs :recent-events)
              recent-text (if (string? recent) recent (pr-str recent))]
          (is (some? recent)
              "LLM input carries :recent-events for the consolidation")
          (is (str/includes? recent-text "fp-A")
              ":recent-events should carry execution data (tree-fingerprint fp-A) joined from rlm-tree-execution-completed")
          (is (str/includes? recent-text "fp-B")
              ":recent-events should carry the second tree-fingerprint fp-B")
          (is (or (str/includes? recent-text ":success")
                  (str/includes? recent-text "\"success\""))
              ":recent-events should carry execution :status :success")
          (is (or (str/includes? recent-text ":failure")
                  (str/includes? recent-text "\"failure\""))
              ":recent-events should carry execution :status :failure"))))))

;; =============================================================================
;; RED #9 — gather-aggregate-metrics returns cross-observation baseline for :tree-class
;; =============================================================================
;;
;; Per LIVING-DESCRIPTIONS.md's "aggregate + delta in the prompt" safeguard:
;; the consolidator gives the LLM the all-time historical baseline so it
;; can compare recent-window trends against it. For :tree-class, this
;; baseline aggregates across all task-classified events (and their joined
;; executions) for the class:
;;   :total-assignments    — count of task-classified events
;;   :success-count        — executions completed with :success status
;;   :failure-count        — executions completed with :failure status
;;   :distinct-tree-shapes — count of distinct tree-fingerprints observed

(deftest consolidator-aggregate-metrics-carry-cross-observation-baseline-for-tree-class
  (testing "C-Loop-1 RED#9: aggregate-metrics for :tree-class summarizes total assignments, success/failure across observations, and distinct tree-shapes"
    (with-test-ctx [ctx]
      (let [tree-class-id (random-uuid)
            sheet-1 (random-uuid)
            sheet-2 (random-uuid)
            sheet-3 (random-uuid)
            sheet-4 (random-uuid)
            captured (atom [])]
        (assign-task-class-to-sheet! ctx sheet-1 tree-class-id)
        (complete-tree-for-sheet! ctx sheet-1 "fp-bounded-concurrency" :success)
        (assign-task-class-to-sheet! ctx sheet-2 tree-class-id)
        (complete-tree-for-sheet! ctx sheet-2 "fp-bounded-concurrency" :success)
        (assign-task-class-to-sheet! ctx sheet-3 tree-class-id)
        (complete-tree-for-sheet! ctx sheet-3 "fp-unbounded-concurrency" :failure)
        (assign-task-class-to-sheet! ctx sheet-4 tree-class-id)
        (complete-tree-for-sheet! ctx sheet-4 "fp-unbounded-concurrency" :failure)
        (Thread/sleep 200)
        (with-input-capturing-llm captured fake-description-body
          (fn []
            (cp/process-command
              (assoc ctx :command
                     {:command/name :ontology/request-consolidation
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :target-type :tree-class
                      :target-id tree-class-id
                      :on-demand? true}))
            (Thread/sleep 1500)))
        (let [inputs (first @captured)
              agg (get inputs :aggregate-metrics)
              agg-text (if (string? agg) agg (pr-str agg))]
          (is (some? agg)
              ":aggregate-metrics should be present in the LLM input for :tree-class consolidation")
          (is (or (str/includes? agg-text "\"total-assignments\":4")
                  (str/includes? agg-text ":total-assignments 4"))
              (str ":total-assignments should equal the count of task-classified events for this tree-class (4). Got: "
                   (subs agg-text 0 (min 400 (count agg-text)))))
          (is (or (str/includes? agg-text "\"success-count\":2")
                  (str/includes? agg-text ":success-count 2"))
              (str ":success-count should equal the count of :success executions (2). Got: "
                   (subs agg-text 0 (min 400 (count agg-text)))))
          (is (or (str/includes? agg-text "\"failure-count\":2")
                  (str/includes? agg-text ":failure-count 2"))
              (str ":failure-count should equal the count of :failure executions (2). Got: "
                   (subs agg-text 0 (min 400 (count agg-text)))))
          (is (or (str/includes? agg-text "\"distinct-tree-shapes\":2")
                  (str/includes? agg-text ":distinct-tree-shapes 2"))
              (str ":distinct-tree-shapes should equal the count of unique fingerprints (2). Got: "
                   (subs agg-text 0 (min 400 (count agg-text))))))))))

;; =============================================================================
;; RED #10 — multi-cycle living-description evolution
;; =============================================================================
;;
;; The whole point of the loop: each consolidation BUILDS on the prior
;; consolidated body, not just on the current window. Per LIVING-
;; DESCRIPTIONS.md Part 1: "successes climb in confidence; failures get
;; principle-shaped lessons attached" over many cycles. This requires
;; cycle N to receive cycle N-1's body as :current-description in the
;; LLM input.

(defn- cycle-body
  "Build a fake LLM body keyed by cycle number so we can verify which
   cycle produced which output."
  [n]
  {:capabilities [(str "cycle-" n "-capability")]
   :strengths [{:trait (str "cycle-" n "-strength")
                :good-when "after observing evidence"
                :recommended-pattern "[:llm {:output-schemas {...}}]"
                :confidence 1.0
                :evidence-count n
                :first-observed-at "2026-06-02T00:00:00Z"
                :last-reinforced-at "2026-06-02T00:00:00Z"}]
   :weaknesses []
   :representative-uses [(str "cycle-" n "-use")]
   :avoid-when []
   :summary (str "Cycle-" n " consolidator-authored summary")
   :version 999      ;; consolidator overwrites
   :consolidated-from-event-count 999})

(deftest multi-cycle-consolidation-builds-on-prior-body
  (testing "C-Loop-1 RED#10: consecutive consolidations evolve the body (version 1 → 2 → 3) and each cycle's LLM input carries the prior consolidated body as :current-description"
    (with-test-ctx [ctx]
      (let [tree-class-id (random-uuid)
            captured (atom [])]
        ;; --- Cycle 1: 2 observations → consolidate → returns cycle-1-body ---
        (let [s1 (random-uuid) s2 (random-uuid)]
          (assign-task-class-to-sheet! ctx s1 tree-class-id)
          (complete-tree-for-sheet! ctx s1 "fp-A" :success)
          (assign-task-class-to-sheet! ctx s2 tree-class-id)
          (complete-tree-for-sheet! ctx s2 "fp-A" :success)
          (Thread/sleep 100)
          (with-input-capturing-llm captured (cycle-body 1)
            (fn []
              (cp/process-command
                (assoc ctx :command
                       {:command/name :ontology/request-consolidation
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :target-type :tree-class
                        :target-id tree-class-id
                        :on-demand? true}))
              (Thread/sleep 1500))))
        (let [body-after-1 (ontology/get-description ctx :tree-class tree-class-id)]
          (is (= 1 (:version body-after-1))
              "Cycle 1 sets version to 1 (no prior consolidation)")
          (is (= "Cycle-1 consolidator-authored summary" (:summary body-after-1))
              "Body-after-1 carries cycle-1's :summary"))

        ;; --- Cycle 2: 2 MORE observations → consolidate → returns cycle-2-body ---
        (let [s3 (random-uuid) s4 (random-uuid)]
          (assign-task-class-to-sheet! ctx s3 tree-class-id)
          (complete-tree-for-sheet! ctx s3 "fp-B" :failure)
          (assign-task-class-to-sheet! ctx s4 tree-class-id)
          (complete-tree-for-sheet! ctx s4 "fp-B" :failure)
          (Thread/sleep 100)
          (with-input-capturing-llm captured (cycle-body 2)
            (fn []
              (cp/process-command
                (assoc ctx :command
                       {:command/name :ontology/request-consolidation
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :target-type :tree-class
                        :target-id tree-class-id
                        :on-demand? true}))
              (Thread/sleep 1500))))
        (let [body-after-2 (ontology/get-description ctx :tree-class tree-class-id)]
          (is (= 2 (:version body-after-2))
              "Cycle 2 increments version to 2 — built on cycle-1's body")
          (is (= "Cycle-2 consolidator-authored summary" (:summary body-after-2))
              "Body-after-2 carries cycle-2's :summary"))

        ;; --- Assert cycle 2's LLM saw cycle 1's body as :current-description ---
        (is (= 2 (count @captured))
            "Two LLM calls happened (one per consolidation cycle)")
        (let [cycle-2-inputs (second @captured)
              current-desc (get cycle-2-inputs :current-description)
              current-desc-text (if (string? current-desc) current-desc (pr-str current-desc))]
          (is (some? current-desc)
              "Cycle 2's LLM input has :current-description (the loop is closed)")
          (is (str/includes? current-desc-text "Cycle-1 consolidator-authored summary")
              "Cycle 2's :current-description IS the body produced by cycle 1 — successive consolidations build on prior outputs"))))))

;; =============================================================================
;; Gap-3 RED#1 — consolidator joins :judge/score-emitted events into input
;; =============================================================================
;;
;; The consolidator's LLM input map should carry :judge-scores per
;; observation. Lets the LLM weight judge-grounded signal alongside raw
;; execution evidence.

(defn- emit-judge-score!
  "Synthetic helper: directly append a :judge/score-emitted event for the
   given sheet/tick. We bypass the per-event evaluator runtime here —
   the consolidator just needs the events present in the store."
  [ctx sheet-id tick-id node-id judge-name score feedback]
  (es/append (:event-store ctx)
             {:tenant-id (:tenant-id ctx)
              :events [(es/->event
                         {:type :judge/score-emitted
                          :tags #{[:sheet sheet-id] [:tick tick-id] [:node node-id]}
                          :body {:sheet-id sheet-id
                                 :tick-id tick-id
                                 :node-id node-id
                                 :judge-name judge-name
                                 :judge-config {:type :grounding}
                                 :score score
                                 :feedback feedback
                                 :dimensions []
                                 :emitted-at (str (java.time.Instant/now))}})]}))

(deftest consolidator-input-includes-joined-judge-scores
  (testing "Gap-3: consolidator's LLM input :recent-events carries judge-scores joined by sheet/tick"
    (with-test-ctx [ctx]
      (let [tree-class-id (random-uuid)
            sheet-1 (random-uuid)
            node-1 (random-uuid)
            captured (atom [])]
        (assign-task-class-to-sheet! ctx sheet-1 tree-class-id)
        (complete-tree-for-sheet! ctx sheet-1 "fp-A" :success)
        ;; Find the source-tick-id from the assign-task-class event so we
        ;; can join judges to the same tick.
        (Thread/sleep 100)
        (let [task-classifieds (into [] (es/read (:event-store ctx)
                                                  {:types #{:ontology/task-classified}
                                                   :tenant-id (:tenant-id ctx)}))
              tick-1 (-> task-classifieds first :source-tick-id)]
          (emit-judge-score! ctx sheet-1 tick-1 node-1 "grounding" 0.85 "Well-grounded")
          (emit-judge-score! ctx sheet-1 tick-1 node-1 "reasoning" 0.70 "Clear reasoning")
          (Thread/sleep 200)
          (with-input-capturing-llm captured fake-description-body
            (fn []
              (cp/process-command
                (assoc ctx :command
                       {:command/name :ontology/request-consolidation
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :target-type :tree-class
                        :target-id tree-class-id
                        :on-demand? true}))
              (Thread/sleep 1500)))
          (let [inputs (first @captured)
                recent (get inputs :recent-events)
                recent-text (if (string? recent) recent (pr-str recent))]
            (is (some? recent)
                "LLM input carries :recent-events")
            (is (or (str/includes? recent-text "grounding")
                    (str/includes? recent-text "\"grounding\""))
                (str "Recent events should include the grounding judge's score. Got: "
                     (subs recent-text 0 (min 400 (count recent-text)))))
            (is (or (str/includes? recent-text "Well-grounded")
                    (str/includes? recent-text "\"Well-grounded\""))
                "Judge feedback strings flow through verbatim")))))))

;; =============================================================================
;; Gap-3 RED#2 — aggregate-metrics carries :judge-averages
;; =============================================================================
;;
;; Per the unification PRD: aggregate-metrics is the STABLE BASELINE the
;; LLM compares the recent window against. When judges have fired across
;; multiple ticks for this tree-class, the aggregate should carry
;; per-judge averages so the LLM can reason "this tree-class's grounding
;; trends at 0.78 historically; this window saw 0.42 — a real
;; regression" rather than scoring the window in isolation.

(deftest consolidator-aggregate-metrics-carry-judge-averages-for-tree-class
  (testing "Gap-3 RED#2: aggregate-metrics for :tree-class includes :judge-averages keyed by judge-name with the cross-observation mean score"
    (with-test-ctx [ctx]
      (let [tree-class-id (random-uuid)
            sheet-1 (random-uuid)
            sheet-2 (random-uuid)
            sheet-3 (random-uuid)
            node-1 (random-uuid)
            node-2 (random-uuid)
            node-3 (random-uuid)
            captured (atom [])]
        (assign-task-class-to-sheet! ctx sheet-1 tree-class-id)
        (complete-tree-for-sheet! ctx sheet-1 "fp-A" :success)
        (assign-task-class-to-sheet! ctx sheet-2 tree-class-id)
        (complete-tree-for-sheet! ctx sheet-2 "fp-A" :success)
        (assign-task-class-to-sheet! ctx sheet-3 tree-class-id)
        (complete-tree-for-sheet! ctx sheet-3 "fp-B" :failure)
        (Thread/sleep 100)
        (let [task-classifieds (into [] (es/read (:event-store ctx)
                                                  {:types #{:ontology/task-classified}
                                                   :tenant-id (:tenant-id ctx)}))
              tick-for-sheet (into {}
                                   (map (fn [tc]
                                          [(:source-sheet-id tc) (:source-tick-id tc)])
                                        task-classifieds))]
          ;; Sheet 1: grounding 0.9, reasoning 0.8
          (emit-judge-score! ctx sheet-1 (tick-for-sheet sheet-1) node-1 "grounding" 0.9 "ok")
          (emit-judge-score! ctx sheet-1 (tick-for-sheet sheet-1) node-1 "reasoning" 0.8 "ok")
          ;; Sheet 2: grounding 0.7, reasoning 0.6
          (emit-judge-score! ctx sheet-2 (tick-for-sheet sheet-2) node-2 "grounding" 0.7 "ok")
          (emit-judge-score! ctx sheet-2 (tick-for-sheet sheet-2) node-2 "reasoning" 0.6 "ok")
          ;; Sheet 3: grounding 0.5 (only grounding — reasoning didn't fire here)
          (emit-judge-score! ctx sheet-3 (tick-for-sheet sheet-3) node-3 "grounding" 0.5 "ok")
          (Thread/sleep 200)
          (with-input-capturing-llm captured fake-description-body
            (fn []
              (cp/process-command
                (assoc ctx :command
                       {:command/name :ontology/request-consolidation
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :target-type :tree-class
                        :target-id tree-class-id
                        :on-demand? true}))
              (Thread/sleep 1500)))
          (let [inputs (first @captured)
                agg (get inputs :aggregate-metrics)
                agg-text (if (string? agg) agg (pr-str agg))]
            (is (some? agg)
                ":aggregate-metrics should be present in the LLM input")
            (is (or (str/includes? agg-text "judge-averages")
                    (str/includes? agg-text "\"judge-averages\""))
                (str ":aggregate-metrics should include :judge-averages. Got: "
                     (subs agg-text 0 (min 400 (count agg-text)))))
            ;; grounding avg: (0.9 + 0.7 + 0.5) / 3 = 0.7
            (is (or (str/includes? agg-text "0.7")
                    (str/includes? agg-text "\"grounding\":0.7"))
                (str ":judge-averages should report grounding's mean ≈ 0.7. Got: "
                     (subs agg-text 0 (min 400 (count agg-text)))))
            ;; reasoning avg: (0.8 + 0.6) / 2 = 0.7 — also 0.7, but verify both judges named
            (is (or (str/includes? agg-text "reasoning")
                    (str/includes? agg-text "\"reasoning\""))
                ":judge-averages should also report a per-judge entry for 'reasoning'")))))))

;; =============================================================================
;; Gap-3 RED#3 — reflection-instruction explains judge-scores to the LLM
;; =============================================================================
;;
;; Per the unification PRD: the consolidator can only reason about
;; judge signal if the LLM is told that judge-scores are input + how
;; to weight them. Without prompt guidance, the LLM may ignore the
;; :judge-scores entries entirely.

(deftest consolidator-reflection-instruction-explains-judge-scores
  (testing "Gap-3 RED#3: reflection-instruction text describes :judge-scores per-event and :judge-averages in aggregate, with explicit guidance to weight judge feedback alongside execution evidence"
    (let [instruction @#'ai.obney.orc.ontology.core.consolidator/reflection-instruction]
      (is (string? instruction)
          "reflection-instruction is the private string driving the LLM")
      (is (str/includes? instruction "judge-scores")
          "Instruction must mention :judge-scores so the LLM treats per-event judge entries as input")
      (is (str/includes? instruction "judge-averages")
          "Instruction must mention :judge-averages so the LLM compares per-window judge signal to its historical baseline"))))

;; =============================================================================
;; Gap-3 RED#4 — graceful degradation when no judges fired
;; =============================================================================
;;
;; The full judge loop is opt-in (Gap-1 is gated on the Living
;; Description flag). Consumers running with the flag off, or in
;; environments where no judges happen to fire for a given tree-class,
;; must still see the consolidator produce a valid description body.
;;
;; This test verifies:
;;   1. The LLM input's :recent-events carries no :judge-scores key when
;;      no judge events exist (vs. an empty vector, which would mislead
;;      the LLM into reading "all judges scored 0").
;;   2. :aggregate-metrics does NOT include :judge-averages when no
;;      judges fired (same anti-misleading reasoning).
;;   3. The consolidator still emits a :tree-description-updated event
;;      — the absence of judge signal does not break the loop.

(deftest consolidator-degrades-gracefully-when-no-judges-fired
  (testing "Gap-3 RED#4: when no :judge/score-emitted events exist for the tree-class, the LLM input omits judge fields rather than fabricating empty ones, and the consolidator still emits a description update"
    (with-test-ctx [ctx]
      (let [tree-class-id (random-uuid)
            sheet-1 (random-uuid)
            sheet-2 (random-uuid)
            captured (atom [])
            updates-before (count (into [] (es/read (:event-store ctx)
                                                     {:types #{:ontology/tree-description-updated}
                                                      :tenant-id (:tenant-id ctx)})))]
        ;; Seed evidence WITHOUT any judge events. This mirrors the
        ;; "Gap-1 opt-in off" case.
        (assign-task-class-to-sheet! ctx sheet-1 tree-class-id)
        (complete-tree-for-sheet! ctx sheet-1 "fp-A" :success)
        (assign-task-class-to-sheet! ctx sheet-2 tree-class-id)
        (complete-tree-for-sheet! ctx sheet-2 "fp-A" :success)
        (Thread/sleep 200)
        (with-input-capturing-llm captured fake-description-body
          (fn []
            (cp/process-command
              (assoc ctx :command
                     {:command/name :ontology/request-consolidation
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :target-type :tree-class
                      :target-id tree-class-id
                      :on-demand? true}))
            (Thread/sleep 1500)))
        (let [inputs (first @captured)
              recent (get inputs :recent-events)
              agg (get inputs :aggregate-metrics)
              recent-text (if (string? recent) recent (pr-str recent))
              agg-text (if (string? agg) agg (pr-str agg))]
          (is (some? recent)
              "LLM input still carries :recent-events with the classification observations")
          (is (not (str/includes? recent-text "judge-scores"))
              (str ":recent-events must NOT contain a :judge-scores key when no judges fired. "
                   "Otherwise the LLM might infer 'judges ran but produced nothing'. Got: "
                   (subs recent-text 0 (min 400 (count recent-text)))))
          (is (some? agg)
              ":aggregate-metrics is still computed")
          (is (not (str/includes? agg-text "judge-averages"))
              (str ":aggregate-metrics must NOT contain :judge-averages when no judges fired. Got: "
                   (subs agg-text 0 (min 400 (count agg-text))))))
        ;; The consolidator's emit-description-update path still runs.
        (Thread/sleep 500)
        (let [updates-after (count (into [] (es/read (:event-store ctx)
                                                      {:types #{:ontology/tree-description-updated}
                                                       :tenant-id (:tenant-id ctx)})))]
          (is (> updates-after updates-before)
              "Consolidator still emits :ontology/tree-description-updated even with zero judge signal"))))))

;; =============================================================================
;; Gap-6 RED#1 — anti-recency validator returns :ok when prior body is nil
;; =============================================================================
;;
;; The validator is the runtime backstop for the doc-only anti-recency
;; safeguards. First consolidation has no prior to protect — validator
;; must pass through unchanged.

(deftest gap6-validator-passes-through-when-prior-body-is-nil
  (testing "Gap-6 RED#1: anti-recency-validate returns :ok with the new body unchanged when prior body is nil (first consolidation has no baseline to protect)"
    (let [validate @#'ai.obney.orc.ontology.core.consolidator/anti-recency-validate
          new-body {:capabilities ["x"]
                    :strengths [{:trait "fresh"
                                 :good-when "always"
                                 :recommended-pattern "[:llm {}]"
                                 :confidence 0.9
                                 :evidence-count 3
                                 :first-observed-at "2026-06-03T00:00:00Z"
                                 :last-reinforced-at "2026-06-03T00:00:00Z"}]
                    :weaknesses []
                    :representative-uses ["x"]
                    :avoid-when []
                    :summary "first consolidation"
                    :version 1
                    :consolidated-from-event-count 1}
          result (validate nil new-body {})]
      (is (some? result)
          "validator returns a non-nil result map for legitimate inputs")
      (is (= :ok (:decision result))
          (str ":decision should be :ok when prior body is nil. Got: " (pr-str result)))
      (is (= new-body (:body result))
          ":body should be the new body unchanged"))))

;; =============================================================================
;; Gap-6 RED#2 — validator REJECTS when a protected entry is missing
;; =============================================================================

(deftest gap6-validator-rejects-when-protected-strength-is-missing
  (testing "Gap-6 RED#2: when prior body has a strength entry with confidence >= 0.7 AND evidence-count >= 5, and the new body OMITS that entry (matched by :trait), validator returns :reject and includes the missing-entry detail in :audit"
    (let [validate @#'ai.obney.orc.ontology.core.consolidator/anti-recency-validate
          prior {:capabilities ["x"]
                 :strengths [{:trait "per-section :map-each surfaces issues consistently"
                              :good-when "sections are independent"
                              :recommended-pattern "[:map-each {:max-concurrency 3} ...]"
                              :confidence 0.95
                              :evidence-count 7
                              :first-observed-at "2026-05-26T00:00:00Z"
                              :last-reinforced-at "2026-06-02T00:00:00Z"}]
                 :weaknesses []
                 :representative-uses ["x"]
                 :avoid-when []
                 :summary "prior"
                 :version 3
                 :consolidated-from-event-count 4}
          new-body {:capabilities ["x"]
                    :strengths []  ;; protected trait erased
                    :weaknesses []
                    :representative-uses ["x"]
                    :avoid-when []
                    :summary "new"
                    :version 4
                    :consolidated-from-event-count 5}
          result (validate prior new-body {})]
      (is (= :reject (:decision result))
          (str ":decision should be :reject when a protected entry is missing. Got: " (pr-str result)))
      (is (vector? (:audit result))
          ":audit is a vector for downstream event emission")
      (is (seq (:audit result))
          ":audit should carry at least one entry describing the missing protected trait")
      (is (some #(and (= :rejection (:event-kind %))
                      (str/includes? (str (:entry-trait %)) "per-section :map-each"))
                (:audit result))
          (str ":audit must name the missing trait. Got: "
               (pr-str (:audit result)))))))

;; =============================================================================
;; Gap-6 RED#3 — validator CLAMPS on excessive confidence decrease
;; =============================================================================
;;
;; If the LLM dropped a protected entry's confidence by more than the
;; configured maximum (default 0.2), the validator clamps the value to
;; (prior-confidence - max-decrease) instead of accepting the LLM's
;; number. The audit trail records the original LLM value alongside
;; the clamped value.

(deftest gap6-validator-clamps-excessive-confidence-decrease
  (testing "Gap-6 RED#3: when a protected entry's confidence drops by more than max-decrease, validator clamps the new entry to (prior - max-decrease) and records both values in :audit"
    (let [validate @#'ai.obney.orc.ontology.core.consolidator/anti-recency-validate
          trait "per-section :map-each surfaces issues consistently"
          prior {:capabilities ["x"]
                 :strengths [{:trait trait
                              :good-when "sections are independent"
                              :recommended-pattern "[:map-each ...]"
                              :confidence 0.95
                              :evidence-count 7
                              :first-observed-at "2026-05-26T00:00:00Z"
                              :last-reinforced-at "2026-06-02T00:00:00Z"}]
                 :weaknesses []
                 :representative-uses ["x"]
                 :avoid-when []
                 :summary "prior"
                 :version 3
                 :consolidated-from-event-count 4}
          ;; LLM dropped confidence from 0.95 to 0.3 — a swing of 0.65
          ;; that exceeds the default 0.2 ceiling.
          new-body {:capabilities ["x"]
                    :strengths [{:trait trait
                                 :good-when "sections are independent"
                                 :recommended-pattern "[:map-each ...]"
                                 :confidence 0.3
                                 :evidence-count 8
                                 :first-observed-at "2026-05-26T00:00:00Z"
                                 :last-reinforced-at "2026-06-03T00:00:00Z"}]
                    :weaknesses []
                    :representative-uses ["x"]
                    :avoid-when []
                    :summary "new"
                    :version 4
                    :consolidated-from-event-count 5}
          result (validate prior new-body {})]
      (is (= :clamp (:decision result))
          (str ":decision should be :clamp when confidence drop exceeds max. Got: " (pr-str result)))
      (let [clamped-entry (first (:strengths (:body result)))]
        (is (= trait (:trait clamped-entry))
            "Clamped entry preserves its :trait")
        (is (= 0.75 (:confidence clamped-entry))
            (str "Clamped confidence = prior 0.95 - max-decrease 0.2 = 0.75. Got: "
                 (:confidence clamped-entry))))
      (is (some #(and (= :clamp (:event-kind %))
                      (= trait (:entry-trait %))
                      (= 0.95 (:prior-confidence %))
                      (= 0.3 (:llm-confidence %))
                      (= 0.75 (:clamped-confidence %)))
                (:audit result))
          (str ":audit must record prior + llm + clamped values for the entry. Got: "
               (pr-str (:audit result)))))))

;; =============================================================================
;; Gap-6 RED#6 — validator does NOT false-positive on legitimate evolution
;; =============================================================================
;;
;; The failure mode to fear is the validator rejecting GOOD
;; consolidations. The legitimate case: a protected entry whose
;; confidence dropped by within the configured max-decrease, OR a
;; non-protected entry that churned freely. Both must pass through
;; with :decision :ok.

(deftest gap6-validator-allows-legitimate-evolution
  (testing "Gap-6 RED#6: validator passes through unchanged (decision :ok, empty audit) when (a) a protected entry exists in new body with confidence drop within max-decrease AND (b) a non-protected (low evidence-count) entry is missing from new body"
    (let [validate @#'ai.obney.orc.ontology.core.consolidator/anti-recency-validate
          protected-trait "per-section :map-each surfaces issues consistently"
          speculative-trait "preliminary observation that may not hold up"
          prior {:capabilities ["x"]
                 :strengths [{:trait protected-trait
                              :good-when "sections are independent"
                              :recommended-pattern "[:map-each ...]"
                              :confidence 0.95
                              :evidence-count 7
                              :first-observed-at "2026-05-26T00:00:00Z"
                              :last-reinforced-at "2026-06-02T00:00:00Z"}
                             {:trait speculative-trait
                              :good-when "unclear"
                              :recommended-pattern "[:llm {}]"
                              :confidence 0.4    ;; under protection threshold
                              :evidence-count 1  ;; under protection threshold
                              :first-observed-at "2026-06-02T00:00:00Z"
                              :last-reinforced-at "2026-06-02T00:00:00Z"}]
                 :weaknesses []
                 :representative-uses ["x"]
                 :avoid-when []
                 :summary "prior"
                 :version 3
                 :consolidated-from-event-count 4}
          ;; Healthy evolution: protected entry confidence drops 0.95 → 0.80
          ;; (within the 0.2 max), speculative entry pruned entirely.
          new-body {:capabilities ["x" "y"]
                    :strengths [{:trait protected-trait
                                 :good-when "sections are independent"
                                 :recommended-pattern "[:map-each ...]"
                                 :confidence 0.80
                                 :evidence-count 8
                                 :first-observed-at "2026-05-26T00:00:00Z"
                                 :last-reinforced-at "2026-06-03T00:00:00Z"}]
                    :weaknesses []
                    :representative-uses ["x" "y"]
                    :avoid-when []
                    :summary "new"
                    :version 4
                    :consolidated-from-event-count 5}
          result (validate prior new-body {})]
      (is (= :ok (:decision result))
          (str ":decision should be :ok for healthy evolution. Got: " (pr-str result)))
      (is (empty? (:audit result))
          (str ":audit should be empty when no rejection/clamp happened. Got: "
               (pr-str (:audit result))))
      (is (= new-body (:body result))
          ":body should be passed through unchanged when no clamp/rejection happened")
      (let [protected-entry (first (:strengths (:body result)))]
        (is (= 0.80 (:confidence protected-entry))
            "Protected entry's confidence is the LLM value (no clamp), not the prior")))))

;; =============================================================================
;; Gap-6 RED#4 — consolidator BLOCKS emission on validator rejection
;; =============================================================================
;;
;; Integration test: prior body has a protected entry; faked LLM
;; returns a body with that entry erased. The consolidator must
;; (a) NOT dispatch the record-description command (no new
;; :ontology/node-type-description-updated event), and (b) emit
;; an :ontology/anti-recency-rejection audit event.

(def fake-body-with-protected-entry
  "Seed body shape with one protected strength (confidence 0.95,
   evidence-count 7) — the validator must defend this entry."
  {:capabilities ["x"]
   :strengths [{:trait "stable structured output via :output-schemas"
                :good-when "structured output desired"
                :recommended-pattern "[:llm {:output-schemas {...}}]"
                :confidence 0.95
                :evidence-count 7
                :first-observed-at "2026-05-26T00:00:00Z"
                :last-reinforced-at "2026-06-02T00:00:00Z"}]
   :weaknesses []
   :representative-uses ["per-chunk extraction"]
   :avoid-when ["deterministic work"]
   :summary "Prior body with protected entry"
   :version 3
   :consolidated-from-event-count 99})

(def fake-body-with-protected-entry-erased
  "LLM-returned body that DROPS the protected entry entirely — the
   validator should reject this."
  {:capabilities ["x"]
   :strengths []  ;; protected trait erased
   :weaknesses []
   :representative-uses ["per-chunk extraction"]
   :avoid-when ["deterministic work"]
   :summary "Body with strength erased — the catastrophic-regression case"
   :version 99
   :consolidated-from-event-count 99})

(deftest gap6-consolidator-rejects-emission-when-validator-rejects
  (testing "Gap-6 RED#4: when validator detects a missing protected entry, consolidator does NOT emit a new :*-description-updated event AND emits :ontology/anti-recency-rejection audit event"
    (with-test-ctx [ctx]
      ;; Seed prior body
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/record-node-type-description
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :target-id :map-each
                :body fake-body-with-protected-entry}))
      (Thread/sleep 200)
      (let [updates-before (count (into [] (es/read (:event-store ctx)
                                                     {:types #{:ontology/node-type-description-updated}
                                                      :tenant-id (:tenant-id ctx)})))
            rejections-before (count (into [] (es/read (:event-store ctx)
                                                        {:types #{:ontology/anti-recency-rejection}
                                                         :tenant-id (:tenant-id ctx)})))]
        ;; Faked LLM returns the catastrophic regression — protected entry erased
        (with-faked-llm fake-body-with-protected-entry-erased
          (fn []
            (cp/process-command
              (assoc ctx :command
                     {:command/name :ontology/request-consolidation
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :target-type :node-type
                      :target-id :map-each
                      :on-demand? true}))
            (Thread/sleep 800)))
        (let [updates-after (count (into [] (es/read (:event-store ctx)
                                                      {:types #{:ontology/node-type-description-updated}
                                                       :tenant-id (:tenant-id ctx)})))
              rejections-after (count (into [] (es/read (:event-store ctx)
                                                         {:types #{:ontology/anti-recency-rejection}
                                                          :tenant-id (:tenant-id ctx)})))]
          (is (= updates-before updates-after)
              (str "Consolidator MUST NOT emit a new description-updated event when validator rejects. "
                   "Before: " updates-before ", After: " updates-after))
          (is (= (inc rejections-before) rejections-after)
              (str "Consolidator MUST emit exactly one :ontology/anti-recency-rejection event. "
                   "Before: " rejections-before ", After: " rejections-after))
          (let [rejection (last (into [] (es/read (:event-store ctx)
                                                   {:types #{:ontology/anti-recency-rejection}
                                                    :tenant-id (:tenant-id ctx)})))]
            (is (= :node-type (:target-type rejection))
                "Rejection event names the target-type")
            (is (= :map-each (:target-id rejection))
                "Rejection event names the target-id")
            (is (str/includes? (str (:entry-trait rejection))
                                "stable structured output")
                (str "Rejection event names the missing trait. Got: "
                     (pr-str (:entry-trait rejection))))
            (is (= 0.95 (:prior-confidence rejection))
                "Rejection event records the prior confidence")))
        ;; Body in the read-model is still the prior — not corrupted
        (let [body (ontology/get-description ctx :node-type :map-each)]
          (is (= 3 (:version body))
              "After rejection, the read-model still holds the prior body (version unchanged)")
          (is (= "Prior body with protected entry" (:summary body))
              "Prior body's summary is preserved verbatim"))))))

;; =============================================================================
;; Gap-6 RED#5 — consolidator EMITS with clamped body when validator clamps
;; =============================================================================
;;
;; The LLM dropped a protected entry's confidence excessively but the
;; entry is still PRESENT. The consolidator must (a) emit the
;; description-updated event with the CLAMPED confidence (not the
;; LLM's value), AND (b) emit a :ontology/anti-recency-clamp-applied
;; audit event recording the prior/llm/clamped values.

(def fake-body-with-protected-entry-confidence-tanked
  "LLM-returned body that PRESERVES the protected trait but slashes
   its confidence from 0.95 → 0.2 — far outside the 0.2 max-decrease
   ceiling. The validator must clamp the new value to 0.75."
  {:capabilities ["x"]
   :strengths [{:trait "stable structured output via :output-schemas"
                :good-when "structured output desired"
                :recommended-pattern "[:llm {:output-schemas {...}}]"
                :confidence 0.2  ;; catastrophic drop
                :evidence-count 8
                :first-observed-at "2026-05-26T00:00:00Z"
                :last-reinforced-at "2026-06-03T00:00:00Z"}]
   :weaknesses []
   :representative-uses ["per-chunk extraction"]
   :avoid-when ["deterministic work"]
   :summary "Body with confidence catastrophically dropped"
   :version 99
   :consolidated-from-event-count 99})

(deftest gap6-consolidator-emits-clamped-body-on-validator-clamp
  (testing "Gap-6 RED#5: when validator clamps a protected entry's confidence, consolidator emits the description-updated event with the CLAMPED value AND emits :ontology/anti-recency-clamp-applied audit"
    (with-test-ctx [ctx]
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/record-node-type-description
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :target-id :map-each
                :body fake-body-with-protected-entry}))
      (Thread/sleep 200)
      (let [clamps-before (count (into [] (es/read (:event-store ctx)
                                                    {:types #{:ontology/anti-recency-clamp-applied}
                                                     :tenant-id (:tenant-id ctx)})))]
        (with-faked-llm fake-body-with-protected-entry-confidence-tanked
          (fn []
            (cp/process-command
              (assoc ctx :command
                     {:command/name :ontology/request-consolidation
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :target-type :node-type
                      :target-id :map-each
                      :on-demand? true}))
            (Thread/sleep 800)))
        (let [clamps-after (count (into [] (es/read (:event-store ctx)
                                                     {:types #{:ontology/anti-recency-clamp-applied}
                                                      :tenant-id (:tenant-id ctx)})))]
          (is (= (inc clamps-before) clamps-after)
              ":ontology/anti-recency-clamp-applied event must land for the clamped entry"))
        (let [body (ontology/get-description ctx :node-type :map-each)
              protected-entry (first (:strengths body))]
          (is (= 4 (:version body))
              "After clamp, the body IS emitted (version bumped) — not rejected")
          (is (= "stable structured output via :output-schemas" (:trait protected-entry))
              "Protected trait preserved in emitted body")
          (is (= 0.75 (:confidence protected-entry))
              (str "Emitted confidence is the CLAMPED value (prior 0.95 - max-decrease 0.2 = 0.75), "
                   "NOT the LLM's 0.2. Got: " (:confidence protected-entry))))
        (let [clamp-evt (last (into [] (es/read (:event-store ctx)
                                                 {:types #{:ontology/anti-recency-clamp-applied}
                                                  :tenant-id (:tenant-id ctx)})))]
          (is (= :node-type (:target-type clamp-evt)))
          (is (= :map-each (:target-id clamp-evt)))
          (is (= 0.95 (:prior-confidence clamp-evt)))
          (is (= 0.2 (:llm-confidence clamp-evt)))
          (is (= 0.75 (:clamped-confidence clamp-evt))))))))
