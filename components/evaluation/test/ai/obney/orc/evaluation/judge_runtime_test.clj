(ns ai.obney.orc.evaluation.judge-runtime-test
  "Gap-1 — per-event evaluator runtime. Tests progress in RED→GREEN cycles
   per `docs/issues/c2d-followups/Gap-1-per-event-evaluator-runtime.md`."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [malli.core :as m]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.judge-runtime]
            ;; Load the score-recording command handlers so the judge
            ;; runtime's background future can dispatch them (they are the
            ;; sole writers of :judge/score-emitted /
            ;; :judge/composite-score-computed).
            [ai.obney.orc.evaluation.core.commands]
            [ai.obney.orc.evaluation.core.judges :as judges]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.core.commands]
            [ai.obney.orc.orc-service.core.read-models]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]
            [litellm.router :as litellm-router]))

;; Gap-4: register a placeholder litellm config so orc/execute on a
;; :code-only eval sheet doesn't fail with "Configuration not found"
;; during the runtime's provider validation. Real consumers register
;; the actual config at app boot; for unit tests that only exercise
;; :code-node eval sheets this is a no-op stub.
(litellm-router/register! :openrouter
                          {:provider :openrouter
                           :model "test-noop"
                           :config {:api-base "http://localhost:0"
                                    :api-key "test"}})

;; =============================================================================
;; Gap-4 test fixtures — synthetic custom-judge eval functions
;; =============================================================================
;;
;; A custom judge is a consumer-built ORC workflow. The runtime sub-
;; executes that workflow with the host node's inputs/outputs/instruction
;; as inputs. The sheet's outputs MUST yield :score (double 0-1) and
;; :feedback (string). These test fns implement the consumer side.

(defn custom-judge-by-output-length
  "Test eval fn: score = length of (str host-outputs) / 200, clamped to 1.0."
  [{:keys [inputs] :as _executor-ctx}]
  (let [host-outputs (:host-outputs inputs)
        s (str host-outputs)
        raw (/ (count s) 200.0)
        score (min 1.0 raw)]
    {:score score
     :feedback (str "Output is " (count s) " chars (raw " raw " clamped " score ")")}))

(defn custom-judge-schema-compliance
  "Test eval fn: score = 1.0 if host-outputs has :answer key, 0.0 otherwise."
  [{:keys [inputs] :as _executor-ctx}]
  (let [host-outputs (:host-outputs inputs)
        has-answer? (contains? host-outputs :answer)]
    {:score (if has-answer? 1.0 0.0)
     :feedback (if has-answer?
                 "host outputs contains :answer field"
                 "host outputs missing :answer field")}))

(defn custom-judge-broken-no-score
  "Test eval fn: returns outputs WITHOUT :score — exercises the
   missing-score graceful path."
  [_ctx]
  {:feedback "broken-judge missing-score-on-purpose"})

(defn custom-judge-throws
  "Test eval fn: deliberately throws — exercises error graceful path."
  [_ctx]
  (throw (ex-info "intentional-test-error" {:test :gap-4})))

;; =============================================================================
;; Test context helpers (mirror the pattern from consolidation_trigger_test)
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/judge-runtime-test-" (random-uuid))
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
                              ;; Only the evaluation judge processors take a
                              ;; :processor-name. They use the :result/effect
                              ;; path, which checkpoints per-event — a distinct
                              ;; name keeps each processor's replay-guard
                              ;; watermark separate (mirrors production, where
                              ;; the control plane assigns names) so a slow
                              ;; effect isn't skipped. The other (pure-path)
                              ;; processors stay name-less exactly as before:
                              ;; naming them would switch their pure
                              ;; :result/events path into the checkpointed
                              ;; branch, whose monotonic replay-guard
                              ;; FALSE-POSITIVES under the concurrent sub-ticks
                              ;; that custom-judge orc/execute fans out (e.g.
                              ;; complete-tree-tick getting :already-processed
                              ;; and never delivering the completion promise).
                              (tp/start (cond-> {:event-pubsub ps :topics topics
                                                 :handler-fn handler-fn :context base-ctx}
                                          (= "evaluation" (namespace proc-name))
                                          (assoc :processor-name proc-name)))))
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

(defn- count-score-emitted-events [ctx]
  (count (into [] (es/read (:event-store ctx)
                           {:types #{:judge/score-emitted}
                            :tenant-id (:tenant-id ctx)}))))

;; Post-async refactor (2026-06-17): the judge runtime no longer emits
;; score events synchronously inside the processor handler. It returns a
;; non-blocking :result/effect that spawns a background future which runs
;; the judges and dispatches the record-judge-score / record-composite-
;; score commands. Emission is therefore EVENTUAL — observers must POLL
;; rather than sleep-once. (The old fixed-sleep-then-read pattern was
;; calibrated to the old blocking handler and is now racy under the
;; extra async hop + load from the other registered processors.) These
;; helpers poll for the expected event count with a generous bound, the
;; same discipline the Gap-4/Gap-8 tests already used.

(defn- wait-for-score-events
  "Poll until at least `n` :judge/score-emitted events exist for the
   tenant (optionally filtered by `tick-id`), or the timeout elapses.
   Returns the matching events vector."
  ([ctx n] (wait-for-score-events ctx n nil))
  ([ctx n tick-id]
   (let [deadline (+ (System/currentTimeMillis) 15000)
         match (fn []
                 (cond->> (into [] (es/read (:event-store ctx)
                                            {:types #{:judge/score-emitted}
                                             :tenant-id (:tenant-id ctx)}))
                   tick-id (filterv #(= tick-id (:tick-id %)))))]
     (loop []
       (let [evts (match)]
         (if (or (>= (count evts) n)
                 (>= (System/currentTimeMillis) deadline))
           evts
           (do (Thread/sleep 50) (recur))))))))

(defn- wait-for-composite-events
  "Poll until at least `n` :judge/composite-score-computed events exist
   for the given tick, or the timeout elapses. Returns the matching
   events vector."
  [ctx n tick-id]
  (let [deadline (+ (System/currentTimeMillis) 15000)
        match (fn []
                (filterv #(= tick-id (:tick-id %))
                         (into [] (es/read (:event-store ctx)
                                           {:types #{:judge/composite-score-computed}
                                            :tenant-id (:tenant-id ctx)}))))]
    (loop []
      (let [evts (match)]
        (if (or (>= (count evts) n)
                (>= (System/currentTimeMillis) deadline))
          evts
          (do (Thread/sleep 50) (recur)))))))

(defn- set-living-description-enabled! [ctx enabled?]
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/set-living-description-enabled
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :enabled? enabled?}))
  (Thread/sleep 100))

;; =============================================================================
;; RED #2 — :judge/score-emitted schema registration + validation
;; =============================================================================
;;
;; The unified evaluator protocol's per-event evaluator emission. Every
;; auto-running judge under Gap-1 emits this event shape. Consolidator
;; will consume them under Gap-3.

(def well-formed-body
  "A minimal but fully-conforming :judge/score-emitted body."
  {:sheet-id (random-uuid)
   :tick-id (random-uuid)
   :node-id (random-uuid)
   :judge-name "grounding"
   :judge-config {:type :grounding}
   :score 0.85
   :feedback "Output is well-grounded in the source document."
   :dimensions [{:name "Source Grounding" :weight 0.5 :score 0.9 :feedback "All claims trace to source"}
                {:name "Citation Quality" :weight 0.5 :score 0.8 :feedback "Most citations are direct quotes"}]
   :emitted-at "2026-06-03T12:00:00Z"})

(deftest judge-score-emitted-schema-validates-well-formed-body
  (testing "A well-formed :judge/score-emitted body passes schema validation"
    (let [schema (get @schema-util/registry* :judge/score-emitted)]
      (is (some? schema)
          ":judge/score-emitted schema should be registered in the global registry")
      (is (m/validate schema well-formed-body)
          (str "Well-formed body should validate. Explanation: "
               (when schema (pr-str (m/explain schema well-formed-body))))))))

(deftest judge-score-emitted-schema-rejects-missing-score
  (testing "A body missing the required :score field fails validation"
    (let [schema (get @schema-util/registry* :judge/score-emitted)
          malformed (dissoc well-formed-body :score)]
      (is (some? schema))
      (is (not (m/validate schema malformed))
          "Missing :score must fail validation"))))

(deftest judge-score-emitted-schema-rejects-missing-judge-name
  (testing "A body missing :judge-name fails validation"
    (let [schema (get @schema-util/registry* :judge/score-emitted)
          malformed (dissoc well-formed-body :judge-name)]
      (is (not (m/validate schema malformed))
          "Missing :judge-name must fail validation"))))

(deftest judge-score-emitted-schema-rejects-out-of-range-score
  (testing "A score outside [0.0, 1.0] fails validation"
    (let [schema (get @schema-util/registry* :judge/score-emitted)
          malformed (assoc well-formed-body :score 1.5)]
      (is (not (m/validate schema malformed))
          "Score > 1.0 must fail validation"))))

(deftest judge-score-emitted-schema-accepts-empty-dimensions
  (testing "An empty :dimensions vector is acceptable (some judges produce no dimensions)"
    (let [schema (get @schema-util/registry* :judge/score-emitted)
          minimal (assoc well-formed-body :dimensions [])]
      (is (m/validate schema minimal)
          "Empty :dimensions should validate — judges may produce no breakdown"))))

;; =============================================================================
;; RED #3 — Processor gated by opt-in flag (tracer bullet)
;; =============================================================================
;;
;; New defprocessor in evaluation/core/judge-runtime subscribed to
;; :sheet/node-execution-completed. With the opt-in flag OFF (default),
;; the processor must NOT emit any :judge/score-emitted events, even if
;; the host node has judges attached.
;;
;; This is the tracer-bullet test — proves the processor is registered,
;; subscribed to the right event, and respects the flag gate. Doesn't
;; yet test the dispatch logic (that's RED#5+).

(defn- emit-node-execution-completed!
  "Helper: emit a synthetic :sheet/node-execution-completed event so
   the new processor reacts to it. We dispatch a real command rather
   than poking the event store directly, to exercise the full path."
  [ctx sheet-id tick-id node-id]
  (cp/process-command
    (assoc ctx :command
           {:command/name :sheet/complete-node-execution
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :sheet-id sheet-id
            :tick-id tick-id
            :node-id node-id
            :node-type :llm
            :status :success
            :writes {}
            :duration-ms 100})))

(defn- emit-node-execution-with-tree-output!
  "Emit a node-execution-completed event whose :writes carries the
   model's :generated-tree-raw — what a real repl-researcher Phase 1
   emit-tree! would write into the blackboard."
  [ctx sheet-id tick-id node-id tree-dsl]
  (cp/process-command
    (assoc ctx :command
           {:command/name :sheet/complete-node-execution
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :sheet-id sheet-id
            :tick-id tick-id
            :node-id node-id
            :node-type :llm
            :status :success
            :writes {:generated-tree-raw tree-dsl}
            :duration-ms 100})))

(deftest processor-no-events-when-opt-in-off
  (testing "Tracer bullet: with opt-in flag OFF, no :judge/score-emitted events fire even after node-execution-completed"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)]
        ;; Flag is default OFF — no explicit set call
        (is (false? (ontology/get-living-description-enabled? ctx))
            "Pre-condition: opt-in flag is off")
        (emit-node-execution-completed! ctx sheet-id tick-id node-id)
        (Thread/sleep 400)
        (is (= 0 (count-score-emitted-events ctx))
            "No judge events should fire when opt-in is off")))))

;; =============================================================================
;; RED #4 — Early-return when no judges attached to the completing node
;; =============================================================================
;;
;; Opt-in flag ON, but the node has no attached judges. Processor must
;; not emit any :judge/score-emitted events. This isolates the no-judges
;; short-circuit from the judge-dispatch logic (which lands in RED#5+).

(deftest processor-no-events-when-no-judges-attached
  (testing "With opt-in flag ON but the node has no :judges attached, processor emits zero events"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (is (true? (ontology/get-living-description-enabled? ctx))
          "Pre-condition: opt-in flag is on")
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)]
        ;; Node has no attached judges (it doesn't even exist in any sheet,
        ;; so get-node returns nil and the read-model's :judges field is nil)
        (emit-node-execution-completed! ctx sheet-id tick-id node-id)
        (Thread/sleep 400)
        (is (= 0 (count-score-emitted-events ctx))
            "No judge events when no judges attached")))))

;; =============================================================================
;; RED #5 — One LLM judge dispatches and emits :judge/score-emitted
;; =============================================================================
;;
;; First real dispatch slice. Test setup builds a real sheet with a leaf
;; node that has a `:grounding` judge attached. After node-execution-
;; completed fires, the processor must look up the judge, invoke
;; evaluation/grounding-judge (mock mode bound so no real LLM call), and
;; emit exactly one :judge/score-emitted event with judge-name "grounding"
;; and a structured score/feedback.
;;
;; Uses evaluation/judges/*use-mock-llm* dynamic var to skip real LLM —
;; the judge function returns the mock score map.

(defn- setup-sheet-with-judges!
  "Helper: create a sheet, declare a judge, create a leaf node, attach
   the judge. Returns {:sheet-id :node-id}."
  [ctx judge-name judge-config]
  (let [sheet-result (cp/process-command
                       (assoc ctx :command
                              {:command/name :sheet/create-sheet
                               :command/id (random-uuid)
                               :command/timestamp (time/now)
                               :name (str "Test sheet — " (random-uuid))}))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)
        _ (cp/process-command
            (assoc ctx :command
                   {:command/name :sheet/declare-judge
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :sheet-id sheet-id
                    :judge-name judge-name
                    :judge-config judge-config}))
        node-result (cp/process-command
                      (assoc ctx :command
                             {:command/name :sheet/create-node
                              :command/id (random-uuid)
                              :command/timestamp (time/now)
                              :sheet-id sheet-id
                              :type :leaf}))
        node-id (-> node-result :command-result/events first :node-id)
        _ (cp/process-command
            (assoc ctx :command
                   {:command/name :sheet/set-node-judges
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :sheet-id sheet-id
                    :node-id node-id
                    :judges [judge-name]}))]
    (Thread/sleep 100)
    {:sheet-id sheet-id :node-id node-id}))

(defn- setup-sheet-with-multiple-judges!
  "Helper: create a sheet + leaf node + attach a list of [name config] judges."
  [ctx judge-pairs]
  (let [sheet-result (cp/process-command
                       (assoc ctx :command
                              {:command/name :sheet/create-sheet
                               :command/id (random-uuid)
                               :command/timestamp (time/now)
                               :name (str "Test sheet — " (random-uuid))}))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)
        _ (doseq [[judge-name judge-config] judge-pairs]
            (cp/process-command
              (assoc ctx :command
                     {:command/name :sheet/declare-judge
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :sheet-id sheet-id
                      :judge-name judge-name
                      :judge-config judge-config})))
        node-result (cp/process-command
                      (assoc ctx :command
                             {:command/name :sheet/create-node
                              :command/id (random-uuid)
                              :command/timestamp (time/now)
                              :sheet-id sheet-id
                              :type :leaf}))
        node-id (-> node-result :command-result/events first :node-id)
        _ (cp/process-command
            (assoc ctx :command
                   {:command/name :sheet/set-node-judges
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :sheet-id sheet-id
                    :node-id node-id
                    :judges (mapv first judge-pairs)}))]
    (Thread/sleep 150)
    {:sheet-id sheet-id :node-id node-id}))

(deftest one-grounding-judge-dispatches-and-emits-event
  (testing "Opt-in ON + node with [:grounding] attached + mock LLM mode → exactly one :judge/score-emitted event lands"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      ;; *use-mock-llm* is a dynamic var; `binding` doesn't propagate across
      ;; the processor's async/thread boundary. with-redefs mutates the var's
      ;; root value globally for the body's lifetime, which DOES propagate.
      (with-redefs [judges/*use-mock-llm* true]
        (let [{:keys [sheet-id node-id]} (setup-sheet-with-judges!
                                            ctx "my-grounding"
                                            {:type :grounding})
              tick-id (random-uuid)]
          (emit-node-execution-completed! ctx sheet-id tick-id node-id)
          (let [events (wait-for-score-events ctx 1 tick-id)]
            (is (= 1 (count events))
                "Exactly one :judge/score-emitted event should land")
            (when (seq events)
              (let [e (first events)]
                (is (= "my-grounding" (:judge-name e))
                    "Event carries the configured judge-name")
                (is (number? (:score e))
                    "Event has a numeric score")
                (is (string? (:feedback e))
                    "Event has feedback text")
                (is (= node-id (:node-id e))
                    "Event references the host node")))))))))

;; =============================================================================
;; RED #6 — All 4 LLM judges dispatch in one node-execution
;; =============================================================================
;;
;; Attaching all four prebuilt LLM judges to a single leaf node should
;; produce one :judge/score-emitted event per judge. Verifies the
;; dispatch table covers grounding / reasoning / completeness /
;; instruction-following uniformly and that one node-execution-completed
;; event drives multiple judge runs.

(deftest four-llm-judges-dispatch-in-parallel
  (testing "All 4 LLM judge types attached → 4 :judge/score-emitted events with correct judge-names"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (with-redefs [judges/*use-mock-llm* true]
        (let [{:keys [sheet-id node-id]}
              (setup-sheet-with-multiple-judges!
                ctx
                [["g" {:type :grounding}]
                 ["r" {:type :reasoning}]
                 ["c" {:type :completeness}]
                 ["i" {:type :instruction-following}]])
              tick-id (random-uuid)]
          (emit-node-execution-completed! ctx sheet-id tick-id node-id)
          (let [events (wait-for-score-events ctx 4 tick-id)]
            (is (= 4 (count events))
                "All four judges should emit exactly one event each")
            (is (= #{"g" "r" "c" "i"}
                   (set (map :judge-name events)))
                "Events should cover all four configured judge-names")
            (is (every? #(and (number? (:score %))
                              (string? (:feedback %)))
                        events)
                "Every event has a numeric score and string feedback")))))))

;; =============================================================================
;; RED #7 — Per-judge failure isolation
;; =============================================================================
;;
;; A single judge that throws inside its invocation must NOT block other
;; attached judges. Try/catch per judge + mulog log. Only the surviving
;; judges emit events.

(deftest one-judge-throws-others-still-fire
  (testing "Two judges attached, one throws → exactly one event lands (the surviving judge)"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (with-redefs [judges/*use-mock-llm* true
                    ;; Force grounding-judge to throw; reasoning-judge passes through
                    judges/grounding-judge (fn [_ctx]
                                             (throw (ex-info "synthetic grounding failure" {})))]
        (let [{:keys [sheet-id node-id]}
              (setup-sheet-with-multiple-judges!
                ctx
                [["g" {:type :grounding}]
                 ["r" {:type :reasoning}]])
              tick-id (random-uuid)]
          (emit-node-execution-completed! ctx sheet-id tick-id node-id)
          (let [events (wait-for-score-events ctx 1 tick-id)]
            (is (= 1 (count events))
                "Only the surviving (reasoning) judge should have emitted")
            (when (seq events)
              (is (= "r" (:judge-name (first events)))
                  "The surviving event is the reasoning judge"))))))))

;; =============================================================================
;; RED #8 — :heuristic-structural judge stub emits sentinel value
;; =============================================================================
;;
;; Gap-2 replaces this with the real heuristic structural evaluator
;; (re-extracted from the retiring RLM Rolling Judge). For Gap-1, the
;; type is recognized and emits a sentinel score so downstream
;; consumers can be written against the unified event shape now.

(deftest heuristic-structural-judge-recognized-and-emits
  (testing ":heuristic-structural judge type is recognized and emits when a tree is present in :writes"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [{:keys [sheet-id node-id]}
            (setup-sheet-with-judges! ctx "structure" {:type :heuristic-structural})
            tick-id (random-uuid)
            tree-dsl [:sequence [:llm {}] [:final {}]]]
        (emit-node-execution-with-tree-output! ctx sheet-id tick-id node-id tree-dsl)
        (let [events (wait-for-score-events ctx 1 tick-id)]
          (is (= 1 (count events))
              "Heuristic-structural judge emits when tree is present in :writes")
          (when (seq events)
            (let [e (first events)]
              (is (= "structure" (:judge-name e)))
              (is (number? (:score e))
                  "Score is numeric (Gap-2 real evaluator output)"))))))))

;; =============================================================================
;; RED #9 — :custom judge type gracefully no-ops (Gap-4 implements)
;; =============================================================================
;;
;; The :custom judge type accepts a :sheet-id reference to a consumer-
;; defined evaluation sheet. Gap-4 ships the sub-execution mechanics.
;; For Gap-1, the type must be RECOGNIZED but invoke-judge returns nil
;; (graceful no-op). No event emitted, no crash, no other judges
;; affected.

(deftest custom-judge-type-gracefully-no-ops
  (testing ":custom judge type: no event emitted, no crash, sibling judges still fire"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (with-redefs [judges/*use-mock-llm* true]
        ;; Attach a :custom judge alongside an LLM judge. The custom one
        ;; should silently no-op; the LLM one should emit normally.
        (let [{:keys [sheet-id node-id]}
              (setup-sheet-with-multiple-judges!
                ctx
                [["g" {:type :grounding}]
                 ["mycustom" {:type :custom :sheet-id (random-uuid)}]])
              tick-id (random-uuid)]
          (emit-node-execution-completed! ctx sheet-id tick-id node-id)
          (let [events (wait-for-score-events ctx 1 tick-id)]
            (is (= 1 (count events))
                "Exactly one event — the LLM judge emits; the :custom judge no-ops in Gap-1")
            (when (seq events)
              (is (= "g" (:judge-name (first events)))
                  "The surviving event is the LLM judge, not the custom"))))))))

;; =============================================================================
;; RED #10 — get-judge-scores read-model and public query
;; =============================================================================
;;
;; After N :judge/score-emitted events fire for a (sheet-id, tick-id,
;; node-id) tuple, the public query (evaluation/get-judge-scores ctx
;; sheet-id node-id tick-id) returns the vector of judge result bodies
;; in emission order.

(deftest get-judge-scores-returns-emitted-events
  (testing "After 2 judges fire on a node, get-judge-scores returns both"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (with-redefs [judges/*use-mock-llm* true]
        (let [{:keys [sheet-id node-id]}
              (setup-sheet-with-multiple-judges!
                ctx
                [["g" {:type :grounding}]
                 ["r" {:type :reasoning}]])
              tick-id (random-uuid)]
          (emit-node-execution-completed! ctx sheet-id tick-id node-id)
          (wait-for-score-events ctx 2 tick-id)
          (let [scores ((requiring-resolve
                          'ai.obney.orc.evaluation.interface/get-judge-scores)
                        ctx sheet-id node-id tick-id)]
            (is (= 2 (count scores))
                "Read-model returns both emitted scores")
            (is (= #{"g" "r"} (set (map :judge-name scores)))
                "Both judge-names present in the result vector")
            (is (every? #(number? (:score %)) scores)
                "Every score entry has a numeric :score field")))))))

;; =============================================================================
;; Gap-5 — Default judge attachment for repl-researcher nodes
;; =============================================================================
;;
;; When the Living Description opt-in flag is on AND a repl-researcher
;; node has no explicit :judges attached, the resolver applies a default
;; set: heuristic-structural + grounding + reasoning + completeness +
;; instruction-following. Consumer's explicit set-node-judges (even with
;; an empty list) ALWAYS wins over defaults. Defaults DO NOT apply to
;; other node types — only :repl-researcher.

(defn- create-repl-researcher-node!
  "Create a repl-researcher node in the given sheet. Returns node-id."
  [ctx sheet-id]
  (let [result (cp/process-command
                 (assoc ctx :command
                        {:command/name :sheet/create-node
                         :command/id (random-uuid)
                         :command/timestamp (time/now)
                         :sheet-id sheet-id
                         :type :repl-researcher}))]
    (Thread/sleep 100)
    (-> result :command-result/events first :node-id)))

(defn- create-bare-sheet!
  "Create a fresh sheet, return sheet-id."
  [ctx]
  (let [result (cp/process-command
                 (assoc ctx :command
                        {:command/name :sheet/create-sheet
                         :command/id (random-uuid)
                         :command/timestamp (time/now)
                         :name (str "gap5 — " (random-uuid))}))]
    (-> result :command-result/events first :sheet-id)))

(deftest get-effective-judges-defaults-on-repl-researcher
  (testing "When opt-in is ON and a repl-researcher node has no explicit :judges, the resolver returns the 5 default judges"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [sheet-id (create-bare-sheet! ctx)
            node-id (create-repl-researcher-node! ctx sheet-id)
            get-eff (requiring-resolve
                      'ai.obney.orc.evaluation.interface/get-effective-judges-for-node)
            effective (get-eff ctx sheet-id node-id)]
        (is (= 5 (count effective))
            "Resolver returns 5 default judges for repl-researcher with no explicit set-node-judges")
        (is (= #{:heuristic-structural :grounding :reasoning :completeness :instruction-following}
               (set (map #(get-in % [:judge-config :type]) effective)))
            "Defaults include all four LLM judges plus heuristic-structural")))))

(deftest get-effective-judges-non-repl-researcher-no-defaults
  (testing ":leaf node (not :repl-researcher) gets no default judges"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [sheet-id (create-bare-sheet! ctx)
            node-result (cp/process-command
                          (assoc ctx :command
                                 {:command/name :sheet/create-node
                                  :command/id (random-uuid)
                                  :command/timestamp (time/now)
                                  :sheet-id sheet-id
                                  :type :leaf}))
            node-id (-> node-result :command-result/events first :node-id)
            _ (Thread/sleep 100)
            get-eff (requiring-resolve
                      'ai.obney.orc.evaluation.interface/get-effective-judges-for-node)]
        (is (= [] (get-eff ctx sheet-id node-id))
            ":leaf node gets no defaults — only :repl-researcher does")))))

(deftest get-effective-judges-opt-in-off-no-defaults
  (testing "Opt-in flag OFF → no defaults applied even for repl-researcher"
    (with-test-ctx [ctx]
      ;; Flag stays default OFF
      (is (false? (ontology/get-living-description-enabled? ctx)))
      (let [sheet-id (create-bare-sheet! ctx)
            node-id (create-repl-researcher-node! ctx sheet-id)
            get-eff (requiring-resolve
                      'ai.obney.orc.evaluation.interface/get-effective-judges-for-node)]
        (is (= [] (get-eff ctx sheet-id node-id))
            "Opt-in flag still gates defaults")))))

(deftest get-effective-judges-explicit-empty-disables-defaults
  (testing "Consumer set-node-judges with [] explicitly → zero judges (consumer override wins over defaults)"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [sheet-id (create-bare-sheet! ctx)
            node-id (create-repl-researcher-node! ctx sheet-id)
            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :sheet/set-node-judges
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :sheet-id sheet-id
                        :node-id node-id
                        :judges []}))
            _ (Thread/sleep 100)
            get-eff (requiring-resolve
                      'ai.obney.orc.evaluation.interface/get-effective-judges-for-node)]
        (is (= [] (get-eff ctx sheet-id node-id))
            "Explicit empty consumer override returns []")))))

;; =============================================================================
;; Gap-5 RED#3-#6 — Integration: defaults dispatch through the runtime
;; =============================================================================

(deftest gap5-defaults-fire-for-repl-researcher
  (testing "repl-researcher node + opt-in ON + no explicit judges → all 5 defaults fire"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (with-redefs [judges/*use-mock-llm* true]
        (let [sheet-id (create-bare-sheet! ctx)
              node-id (create-repl-researcher-node! ctx sheet-id)
              tick-id (random-uuid)
              ;; Use a tree-bearing event so heuristic-structural can score
              tree-dsl [:sequence [:llm {}] [:final {}]]]
          (emit-node-execution-with-tree-output! ctx sheet-id tick-id node-id tree-dsl)
          (let [events (wait-for-score-events ctx 5 tick-id)]
            (is (= 5 (count events))
                "All 5 default judges (heuristic-structural + 4 LLM) fired")
            (is (= #{"heuristic-structural" "grounding" "reasoning"
                     "completeness" "instruction-following"}
                   (set (map :judge-name events)))
                "Default judge names match")))))))

(deftest gap5-consumer-empty-override-fires-nothing
  (testing "repl-researcher + opt-in ON + consumer set-node-judges [] → zero events"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [sheet-id (create-bare-sheet! ctx)
            node-id (create-repl-researcher-node! ctx sheet-id)
            tick-id (random-uuid)
            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :sheet/set-node-judges
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :sheet-id sheet-id
                        :node-id node-id
                        :judges []}))
            _ (Thread/sleep 100)
            tree-dsl [:sequence [:llm {}] [:final {}]]]
        (emit-node-execution-with-tree-output! ctx sheet-id tick-id node-id tree-dsl)
        (Thread/sleep 400)
        (let [events (into [] (es/read (:event-store ctx)
                                       {:types #{:judge/score-emitted}
                                        :tenant-id (:tenant-id ctx)}))]
          (is (= 0 (count events))
              "Explicit empty override silences defaults"))))))

(deftest gap5-non-repl-researcher-leaf-no-defaults
  (testing ":leaf node (not :repl-researcher) + opt-in ON + no explicit judges → zero events"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (with-redefs [judges/*use-mock-llm* true]
        (let [sheet-id (create-bare-sheet! ctx)
              node-result (cp/process-command
                            (assoc ctx :command
                                   {:command/name :sheet/create-node
                                    :command/id (random-uuid)
                                    :command/timestamp (time/now)
                                    :sheet-id sheet-id
                                    :type :leaf}))
              node-id (-> node-result :command-result/events first :node-id)
              _ (Thread/sleep 100)
              tick-id (random-uuid)
              tree-dsl [:sequence [:llm {}] [:final {}]]]
          (emit-node-execution-with-tree-output! ctx sheet-id tick-id node-id tree-dsl)
          (Thread/sleep 400)
          (let [events (into [] (es/read (:event-store ctx)
                                         {:types #{:judge/score-emitted}
                                          :tenant-id (:tenant-id ctx)}))]
            (is (= 0 (count events))
                "Defaults only apply to :repl-researcher; :leaf gets nothing")))))))

(deftest gap5-defaults-do-not-fire-when-opt-in-off
  (testing "repl-researcher node + opt-in OFF + no explicit judges → zero events"
    (with-test-ctx [ctx]
      ;; Flag default OFF — no explicit set call
      (with-redefs [judges/*use-mock-llm* true]
        (let [sheet-id (create-bare-sheet! ctx)
              node-id (create-repl-researcher-node! ctx sheet-id)
              tick-id (random-uuid)
              tree-dsl [:sequence [:llm {}] [:final {}]]]
          (emit-node-execution-with-tree-output! ctx sheet-id tick-id node-id tree-dsl)
          (Thread/sleep 400)
          (let [events (into [] (es/read (:event-store ctx)
                                         {:types #{:judge/score-emitted}
                                          :tenant-id (:tenant-id ctx)}))]
            (is (= 0 (count events))
                "Opt-in flag still gates default attachment")))))))

;; =============================================================================
;; Gap-2 RED #4 — standalone Rolling Judge processor retired
;; =============================================================================
;;
;; The :rlm evaluate-rlm-tree processor at orc-service todo_processors.clj
;; subscribed to :rlm/tree-generated and emitted :rlm/tree-evaluated.
;; Under the unified protocol it's superseded by the :heuristic-structural
;; judge attached via the per-event evaluator runtime. After retirement:
;;   - The processor key is no longer registered in tp/processor-registry*
;;   - :rlm/tree-evaluated event TYPE schema stays (backward compat for any
;;     external consumer) but nothing in our codebase emits it.

(deftest standalone-rlm-rolling-judge-processor-not-registered
  (testing "Gap-2: :rlm evaluate-rlm-tree processor is no longer in the global registry"
    (is (not (contains? @tp/processor-registry* :rlm/evaluate-rlm-tree))
        "After Gap-2 retirement, the standalone RLM Rolling Judge processor must be gone — its functionality moved under the unified evaluator protocol via the :heuristic-structural judge type.")))

;; =============================================================================
;; Gap-2 RED #2 — heuristic-structural judge uses real evaluator
;; =============================================================================
;;
;; Replaces Gap-1's hardcoded sentinel (score 0.5, "stub" feedback)
;; with the real structural heuristic extracted in Gap-2 RED#1. When
;; the node-execution-completed event carries a :generated-tree-raw in
;; its :writes, the heuristic-structural judge scores the actual tree
;; shape — exposing whether the model used :sequence / :map-each /
;; :final patterns.

(deftest heuristic-structural-uses-real-evaluator-not-stub
  (testing "When the host node has :generated-tree-raw in outputs, the heuristic-structural judge scores the actual tree shape (not the Gap-1 sentinel)"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [{:keys [sheet-id node-id]}
            (setup-sheet-with-judges! ctx "structure" {:type :heuristic-structural})
            tick-id (random-uuid)
            ;; An EXCELLENT tree shape per the heuristic: :sequence + :map-each + :final
            excellent-tree [:sequence
                            [:chunk-document {:from :doc :into :chunks}]
                            [:map-each {:from :chunks :as :chunk :into :results}
                             [:llm {:reads [:chunk] :writes [:result]}]]
                            [:aggregate {:from :results :writes [:out]}]
                            [:final {:keys [:out]}]]]
        (emit-node-execution-with-tree-output! ctx sheet-id tick-id node-id excellent-tree)
        (let [events (wait-for-score-events ctx 1 tick-id)]
          (is (= 1 (count events))
              "Heuristic judge emitted once")
          (when (seq events)
            (let [e (first events)]
              (is (>= (:score e) 0.5)
                  (str "Excellent tree shape (seq + map-each + final) should score >= 0.5 — "
                       "Gap-1 sentinel returned 0.5 hardcoded; if we still see 0.5 the "
                       "real evaluator may not be wired. Got: " (:score e)))
              (is (not (.contains (str (:feedback e)) "stub"))
                  "Feedback should NOT contain the Gap-1 'stub' text — that means the sentinel is still in place")
              ;; Substantive check: real evaluator produces 2 dimensions
              (is (= 2 (count (:dimensions e)))
                  "Real evaluator emits 2 dimensions (Structure + Decomposition); Gap-1 sentinel emitted []"))))))))

;; =============================================================================
;; Gap-2 RED #3 — graceful default when host node didn't emit a tree
;; =============================================================================

(deftest heuristic-structural-no-tree-no-event
  (testing "When the node-execution-completed event has no :generated-tree-raw in :writes, the heuristic judge gracefully no-ops"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [{:keys [sheet-id node-id]}
            (setup-sheet-with-judges! ctx "structure" {:type :heuristic-structural})
            tick-id (random-uuid)]
        ;; emit a node-completed event with EMPTY writes (no tree output)
        (emit-node-execution-completed! ctx sheet-id tick-id node-id)
        (Thread/sleep 400)
        (let [events (into [] (es/read (:event-store ctx)
                                       {:types #{:judge/score-emitted}
                                        :tenant-id (:tenant-id ctx)}))]
          (is (= 0 (count events))
              "No tree output → heuristic judge doesn't emit (avoids scoring nothing)"))))))

;; =============================================================================
;; RED #11 — Integration tracer: mixed judges, end-to-end queryable
;; =============================================================================
;;
;; Final synthetic check before LIVE verify. Combines LLM + heuristic
;; judges on one node, exercises the full chain: opt-in flag → judges
;; attached → node-execution-completed → processor → multiple judges
;; invoked → events emitted → queryable via public API. Mirrors what
;; the bench's repl-researcher path will do, just at this slice's
;; synthetic level.

(deftest integration-tracer-mixed-judges-end-to-end
  (testing "Mixed judges (2 LLM + heuristic-structural) attached → all emit + queryable"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (with-redefs [judges/*use-mock-llm* true]
        (let [{:keys [sheet-id node-id]}
              (setup-sheet-with-multiple-judges!
                ctx
                [["g" {:type :grounding}]
                 ["c" {:type :completeness}]
                 ["structure" {:type :heuristic-structural}]])
              tick-id (random-uuid)
              tree-dsl [:sequence [:llm {}] [:final {}]]
              get-scores (requiring-resolve
                           'ai.obney.orc.evaluation.interface/get-judge-scores)]
          ;; Pre-condition: no scores yet
          (is (= [] (get-scores ctx sheet-id node-id tick-id))
              "Before execution, no scores exist for this tick/node")
          (emit-node-execution-with-tree-output! ctx sheet-id tick-id node-id tree-dsl)
          (wait-for-score-events ctx 3 tick-id)
          ;; Post-condition: 3 scores, all queryable
          (let [scores (get-scores ctx sheet-id node-id tick-id)]
            (is (= 3 (count scores))
                "All three judges emitted")
            (is (= #{"g" "c" "structure"} (set (map :judge-name scores)))
                "All three judge-names present")
            (is (every? #(and (number? (:score %))
                              (string? (:feedback %))
                              (= sheet-id (:sheet-id %))
                              (= node-id (:node-id %))
                              (= tick-id (:tick-id %)))
                        scores)
                "All entries have canonical shape + correct identifiers"))
          ;; Independent isolation: a different tick on the same node sees 0 scores
          (is (= [] (get-scores ctx sheet-id node-id (random-uuid)))
              "Different tick-id returns empty (scope isolation)"))))))

;; =============================================================================
;; Observability — all-judges-returned-nil silent-skip
;; =============================================================================
;;
;; Diagnostic surfaced via gap3_anomaly_diagnose (2026-06-03): a real
;; bench run's terminal repl-researcher completion lacks
;; `:generated-tree-raw` in `:writes`, so heuristic-structural returns
;; nil. In that diagnostic, the 4 LLM judges ALSO returned nil for the
;; terminal completion (root cause deferred to follow-up issue
;; `Gap-7-judge-routing-by-completion-kind.md`). The combined effect
;; was a silent skip: the processor returned nil with no events, no
;; logs, no signal.
;;
;; This test locks the post-fix contract: when effective-judges is
;; non-empty but every judge returns nil, the processor still returns
;; nil (no events emitted, no crash) — the observability work happens
;; via mulog in the production path and is not asserted here. The
;; behavior guarantee is: no spurious judge events, no exception.

(deftest processor-no-events-when-effective-judges-all-return-nil
  (testing "When effective judges are non-empty but every judge returns nil (e.g., heuristic-structural with no tree in :writes), the processor returns nil — no judge events, no crash"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [;; Attach ONLY heuristic-structural so the only path is the
            ;; one that nils when :generated-tree-raw is absent.
            {:keys [sheet-id node-id]}
            (setup-sheet-with-judges! ctx "structure" {:type :heuristic-structural})
            tick-id (random-uuid)]
        ;; Emit a completion with empty :writes — no :generated-tree-raw.
        ;; This mirrors the recursive-RLM terminal-(final!)-completion
        ;; shape that the anomaly diagnostic surfaced.
        (emit-node-execution-completed! ctx sheet-id tick-id node-id)
        (Thread/sleep 400)
        (is (= 0 (count-score-emitted-events ctx))
            "No :judge/score-emitted events when all effective judges return nil — processor must not fabricate events")))))

;; =============================================================================
;; Gap-7 RED#1 — resolver filters by completion-kind
;; =============================================================================
;;
;; Recursive RLM fires :sheet/node-execution-completed multiple times
;; per "run" with different :writes payloads. Intermediate ticks have
;; :generated-tree-raw; terminal (final!) ticks have final outputs.
;; Different judges grade different completion kinds. The resolver
;; filters effective judges by checking each judge's
;; :applies-to-completion-kinds set against the event's completion-
;; kind. Judges whose set doesn't include the event's kind are
;; filtered out.

(deftest gap7-resolver-filters-by-applies-to-completion-kinds
  (testing "Gap-7 RED#1: a judge whose :applies-to-completion-kinds excludes the event's :completion-kind is filtered out of the effective list when the resolver is called with the kind"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [{:keys [sheet-id node-id]}
            (setup-sheet-with-judges! ctx "structure"
                                       {:type :heuristic-structural
                                        :applies-to-completion-kinds #{:tree-iteration}})
            resolver @#'ai.obney.orc.evaluation.core.judge-runtime/get-effective-judges-for-node]
        ;; Sanity: the 3-arg arity still returns the judge (no filter)
        (let [unfiltered (resolver ctx sheet-id node-id)]
          (is (= 1 (count unfiltered))
              "3-arg arity returns the explicitly-attached judge")
          (is (= "structure" (-> unfiltered first :judge-name))
              "3-arg arity returns the judge by name"))
        ;; The new arity with completion-kind :terminal — judge excluded
        (let [filtered-terminal (resolver ctx sheet-id node-id :terminal)]
          (is (= 0 (count filtered-terminal))
              (str "4-arg arity with :terminal filters out a judge configured "
                   "for :tree-iteration. Got: " (pr-str filtered-terminal))))
        ;; The new arity with completion-kind :tree-iteration — judge included
        (let [filtered-iter (resolver ctx sheet-id node-id :tree-iteration)]
          (is (= 1 (count filtered-iter))
              "4-arg arity with :tree-iteration retains the matching judge")
          (is (= "structure" (-> filtered-iter first :judge-name))
              "Retained judge's name preserved"))))))

;; =============================================================================
;; Gap-7 RED#3 — :sheet/node-execution-completed event schema accepts :completion-kind
;; =============================================================================

(deftest gap7-node-execution-completed-schema-accepts-completion-kind
  (testing "Gap-7 RED#3: the :sheet/complete-node-execution command + :sheet/node-execution-completed event accept an optional :completion-kind field of value :tree-iteration or :terminal"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [{:keys [sheet-id node-id]}
            (setup-sheet-with-judges! ctx "structure" {:type :heuristic-structural})
            tick-id (random-uuid)
            ;; Dispatch a complete-node-execution command WITH :completion-kind :terminal.
            ;; If the schema doesn't accept the field, this dispatch returns an
            ;; anomaly and no event lands.
            result (cp/process-command
                     (assoc ctx :command
                            {:command/name :sheet/complete-node-execution
                             :command/id (random-uuid)
                             :command/timestamp (time/now)
                             :sheet-id sheet-id
                             :tick-id tick-id
                             :node-id node-id
                             :node-type :leaf
                             :status :success
                             :writes {}
                             :completion-kind :terminal
                             :duration-ms 100}))]
        (Thread/sleep 200)
        (is (not (:cognitect.anomalies/category result))
            (str "Command must accept :completion-kind without anomaly. Got: " (pr-str result)))
        (let [events (into [] (es/read (:event-store ctx)
                                       {:types #{:sheet/node-execution-completed}
                                        :tenant-id (:tenant-id ctx)}))
              matching (filter #(and (= sheet-id (:sheet-id %))
                                      (= tick-id (:tick-id %))
                                      (= node-id (:node-id %)))
                               events)]
          (is (= 1 (count matching))
              "The completion event landed")
          (is (= :terminal (:completion-kind (first matching)))
              (str "Event must carry :completion-kind :terminal verbatim. Got: "
                   (pr-str (first matching)))))))))

;; =============================================================================
;; Gap-7 RED#4 — processor honors event :completion-kind during resolution
;; =============================================================================

(deftest gap7-processor-routes-judge-by-event-completion-kind
  (testing "Gap-7 RED#4: when a node's attached judge declares :applies-to-completion-kinds #{:tree-iteration} and the event arrives with :completion-kind :terminal, the processor does NOT fire the judge. Inverse: when the event's :completion-kind is :tree-iteration, the judge fires."
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [{:keys [sheet-id node-id]}
            (setup-sheet-with-judges! ctx "structure"
                                       {:type :heuristic-structural
                                        :applies-to-completion-kinds #{:tree-iteration}})
            tree-dsl [:sequence [:llm {}] [:final {}]]
            tick-terminal (random-uuid)
            tick-iter (random-uuid)]
        ;; Dispatch a TERMINAL completion — judge should NOT fire.
        (cp/process-command
          (assoc ctx :command
                 {:command/name :sheet/complete-node-execution
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :sheet-id sheet-id
                  :tick-id tick-terminal
                  :node-id node-id
                  :node-type :leaf
                  :status :success
                  :writes {:generated-tree-raw tree-dsl}  ;; tree present, BUT...
                  :completion-kind :terminal              ;; ...event marked terminal
                  :duration-ms 100}))
        (Thread/sleep 400)
        (is (= 0 (count-score-emitted-events ctx))
            "No judge events should fire when event's :completion-kind doesn't match judge's :applies-to-completion-kinds")
        ;; Dispatch a TREE-ITERATION completion — judge SHOULD fire.
        (cp/process-command
          (assoc ctx :command
                 {:command/name :sheet/complete-node-execution
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :sheet-id sheet-id
                  :tick-id tick-iter
                  :node-id node-id
                  :node-type :leaf
                  :status :success
                  :writes {:generated-tree-raw tree-dsl}
                  :completion-kind :tree-iteration
                  :duration-ms 100}))
        (let [events (wait-for-score-events ctx 1 tick-iter)]
          (is (= 1 (count events))
              "Judge fires for matching :completion-kind"))))))

;; =============================================================================
;; Gap-7 RED#5 — default judges routed by completion kind
;; =============================================================================
;;
;; Per the Gap-7 design: heuristic-structural grades the intermediate
;; tree shape; the 4 LLM judges grade the terminal synthesized output.
;; The default-judges set carries :applies-to-completion-kinds so the
;; resolver routes them correctly when opt-in is on.

(deftest gap7-default-judges-have-no-completion-kind-filter
  (testing "Gap-7 (post-revert 2026-06-05): default-judges have NO :applies-to-completion-kinds, so all 5 judges resolve regardless of the event's :completion-kind. The infrastructure (4-arg resolver arity, schema field, command-handler derivation) stays in place for Gap-7b. See default-judges docstring for the full retrospective. If a future change adds :applies-to-completion-kinds back to these defaults without Gap-7b shipping first, that's a silent regression."
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [sheet-id (create-bare-sheet! ctx)
            node-id (create-repl-researcher-node! ctx sheet-id)
            resolver @#'ai.obney.orc.evaluation.core.judge-runtime/get-effective-judges-for-node
            ;; All five expected default judge names.
            all-defaults #{"heuristic-structural" "grounding" "reasoning"
                           "completeness" "instruction-following"}]
        (let [no-kind (resolver ctx sheet-id node-id)
              iter-kind (resolver ctx sheet-id node-id :tree-iteration)
              terminal-kind (resolver ctx sheet-id node-id :terminal)]
          (is (= all-defaults (set (map :judge-name no-kind)))
              "3-arg arity (no completion-kind) returns all 5 default judges")
          (is (= all-defaults (set (map :judge-name iter-kind)))
              (str "With :tree-iteration: all 5 still return because defaults have "
                   "no :applies-to-completion-kinds field. "
                   "Got: " (pr-str (mapv :judge-name iter-kind))))
          (is (= all-defaults (set (map :judge-name terminal-kind)))
              (str "With :terminal: same — all 5 return. "
                   "Got: " (pr-str (mapv :judge-name terminal-kind))))
          ;; Lock in the infrastructure: a user attaching a CUSTOM judge
          ;; with :applies-to-completion-kinds STILL gets filtered (this
          ;; is what gap7-resolver-filters-by-applies-to-completion-kinds
          ;; tests). The defaults just don't USE the filter today.
          (doseq [j no-kind]
            (is (nil? (:applies-to-completion-kinds (:judge-config j)))
                (str "Default judge " (:judge-name j)
                     " must NOT carry :applies-to-completion-kinds — "
                     "see default-judges docstring for why."))))))))

;; =============================================================================
;; Gap-7 RED#6 — RLM repl-researcher dispatch populates :completion-kind by status
;; =============================================================================
;;
;; The orc-service todo processor that executes a repl-researcher
;; dispatches :sheet/complete-node-execution at the end of each Phase 1
;; iteration. In recursive RLM mode, status is :tree-generated for
;; intermediate iterations (where the model emit-tree!'d) and :success
;; for the terminal (final!) call. The dispatch must tag those
;; completion events with the appropriate :completion-kind so the judge
;; runtime routes correctly.
;;
;; This test isn't a full RLM execution (that needs the real executor +
;; an LLM). Instead it dispatches the command directly with both
;; status flavors and asserts the events carry the right
;; :completion-kind. The dispatch-site change (which the GREEN
;; implementation makes) is to populate the field at every callsite
;; based on the status.
;;
;; Note: this test ONLY locks the wiring between status and
;; completion-kind at the orc-service command level. The LIVE verify
;; exercises the full recursive-RLM path.

(deftest gap7-repl-researcher-completion-kind-derived-from-status
  (testing "Gap-7 RED#6: a completion command dispatched WITHOUT explicit :completion-kind but with status :tree-generated lands as :completion-kind :tree-iteration; status :success lands as :terminal. (Either the caller supplies kind or the command handler derives it from status — both interfaces are acceptable; this test verifies the END STATE on the event.)"
    (with-test-ctx [ctx]
      (let [sheet-id (create-bare-sheet! ctx)
            node-id (create-repl-researcher-node! ctx sheet-id)
            tick-iter (random-uuid)
            tick-term (random-uuid)]
        ;; Intermediate dispatch — status :tree-generated, NO explicit completion-kind.
        ;; Handler must derive :tree-iteration.
        (cp/process-command
          (assoc ctx :command
                 {:command/name :sheet/complete-node-execution
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :sheet-id sheet-id
                  :tick-id tick-iter
                  :node-id node-id
                  :node-type :repl-researcher
                  :status :tree-generated
                  :writes {:generated-tree-raw [:sequence [:llm {}]]}
                  :duration-ms 100}))
        ;; Terminal dispatch — status :success, NO explicit completion-kind.
        ;; Handler must derive :terminal.
        (cp/process-command
          (assoc ctx :command
                 {:command/name :sheet/complete-node-execution
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :sheet-id sheet-id
                  :tick-id tick-term
                  :node-id node-id
                  :node-type :repl-researcher
                  :status :success
                  :writes {:issues "final synthesis output"}
                  :duration-ms 100}))
        (Thread/sleep 300)
        (let [events (into [] (es/read (:event-store ctx)
                                       {:types #{:sheet/node-execution-completed}
                                        :tenant-id (:tenant-id ctx)}))
              by-tick (into {} (map (fn [e] [(:tick-id e) e])) events)
              iter-e (get by-tick tick-iter)
              term-e (get by-tick tick-term)]
          (is (= :tree-iteration (:completion-kind iter-e))
              (str "Intermediate completion (status :tree-generated) lands as :tree-iteration. Got: "
                   (pr-str iter-e)))
          (is (= :terminal (:completion-kind term-e))
              (str "Terminal completion (status :success) lands as :terminal. Got: "
                   (pr-str term-e))))))))

;; =============================================================================
;; Gap-7 fix#1 — build-trace-data reaches back for :inputs on terminal events
;; =============================================================================
;;
;; LIVE verify of Gap-7 routing surfaced that terminal repl-researcher
;; completions don't carry :inputs (the original task inputs flow
;; through :sheet/node-execution-started separately). The LLM judges'
;; build-trace-data then sees empty inputs → renders an empty
;; {inputs} JSON in the rubric prompt → OpenRouter responds without a
;; valid :score → invoke-llm-judge silently returns nil. Fix: when the
;; completion event lacks :inputs, build-trace-data queries the event
;; store for the matching (sheet, tick, node) :sheet/node-execution-
;; started event and uses its :inputs instead.

(deftest gap7-build-trace-data-reaches-back-for-inputs
  (testing "Gap-7 fix#1: build-trace-data, given a completion event with NO :inputs but a matching :sheet/node-execution-started event in the store with :inputs, returns trace-data whose :inputs is the started event's"
    (with-test-ctx [ctx]
      (let [build-trace-data @#'ai.obney.orc.evaluation.core.judge-runtime/build-trace-data
            sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            started-inputs {:document "The original task input text"
                            :max-length 1000}
            ;; Append a synthetic started event with the inputs we want
            ;; build-trace-data to find.
            _ (es/append (:event-store ctx)
                         {:tenant-id (:tenant-id ctx)
                          :events [(es/->event
                                     {:type :sheet/node-execution-started
                                      :tags #{[:sheet sheet-id]
                                              [:node node-id]
                                              [:tick tick-id]}
                                      :body {:sheet-id sheet-id
                                             :tick-id tick-id
                                             :node-id node-id
                                             :inputs started-inputs}})]})
            ;; The completion event has rich :writes but NO :inputs —
            ;; the shape we observed on real bench terminal completions.
            completion-event {:sheet-id sheet-id
                              :tick-id tick-id
                              :node-id node-id
                              :status :success
                              :writes {:issues "Found some legal issues"
                                       :ambiguities "Found some ambiguities"}}
            trace-data (build-trace-data ctx completion-event)]
        (is (= started-inputs (:inputs trace-data))
            (str "When the completion event lacks :inputs, build-trace-data must reach back "
                 "to the matching started event. Got: " (pr-str (:inputs trace-data))))
        (is (= {:issues "Found some legal issues"
                :ambiguities "Found some ambiguities"}
               (:outputs trace-data))
            ":outputs still comes from the completion event's :writes")))))

(deftest gap7-build-trace-data-prefers-direct-inputs-when-present
  (testing "Gap-7 fix#1: when the completion event ALREADY has :inputs, build-trace-data uses them directly and doesn't reach back. Avoids unnecessary event-store queries on the happy path."
    (with-test-ctx [ctx]
      (let [build-trace-data @#'ai.obney.orc.evaluation.core.judge-runtime/build-trace-data
            sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            ;; Started event with DIFFERENT inputs — should be ignored.
            _ (es/append (:event-store ctx)
                         {:tenant-id (:tenant-id ctx)
                          :events [(es/->event
                                     {:type :sheet/node-execution-started
                                      :tags #{[:sheet sheet-id]
                                              [:node node-id]
                                              [:tick tick-id]}
                                      :body {:sheet-id sheet-id
                                             :tick-id tick-id
                                             :node-id node-id
                                             :inputs {:stale "started inputs"}}})]})
            ;; Completion carries its own inputs — the started lookup should NOT clobber them.
            completion-event {:sheet-id sheet-id
                              :tick-id tick-id
                              :node-id node-id
                              :status :success
                              :inputs {:fresh "completion inputs"}
                              :writes {:final "output"}}
            trace-data (build-trace-data ctx completion-event)]
        (is (= {:fresh "completion inputs"} (:inputs trace-data))
            (str ":inputs must be the completion event's direct value, NOT the started event's. "
                 "Got: " (pr-str (:inputs trace-data))))))))

;; =============================================================================
;; Gap-7b RED#1 — :rlm/tree-generated triggers heuristic-structural
;; =============================================================================
;;
;; Per Gap-7's LIVE finding: recursive RLM only fires ONE terminal
;; :sheet/node-execution-completed per run. Intermediate Phase 1
;; emit-tree! iterations surface on :rlm/tree-generated events instead.
;; Gap-7b adds a SECOND processor in judge_runtime subscribed to that
;; event type so heuristic-structural can grade each intermediate tree
;; as it's emitted.
;;
;; The :rlm/tree-generated event body carries :execution-id (= tick-id)
;; and :raw-dsl, but NOT sheet-id/node-id (those live in event tags).
;; The processor must look them up from the matching
;; :sheet/node-execution-started event for the same tick.

(defn- emit-rlm-tree-generated!
  "Helper: append a synthetic :rlm/tree-generated event with the given
   raw-dsl tied to the (sheet, tick) tuple. The event itself doesn't
   carry sheet-id/node-id in its body — the processor must lookup."
  [ctx sheet-id tick-id raw-dsl]
  (es/append (:event-store ctx)
             {:tenant-id (:tenant-id ctx)
              :events [(es/->event
                         {:type :rlm/tree-generated
                          :tags #{[:sheet sheet-id] [:tick tick-id]}
                          :body {:tree-id (random-uuid)
                                 :execution-id tick-id
                                 :raw-dsl raw-dsl
                                 :generated-at (str (java.time.Instant/now))}})]}))

(defn- emit-node-execution-started!
  "Helper: append a synthetic :sheet/node-execution-started event so
   the Gap-7b processor can look up sheet-id/node-id from the tick-id."
  [ctx sheet-id tick-id node-id inputs]
  (es/append (:event-store ctx)
             {:tenant-id (:tenant-id ctx)
              :events [(es/->event
                         {:type :sheet/node-execution-started
                          :tags #{[:sheet sheet-id] [:node node-id] [:tick tick-id]}
                          :body {:sheet-id sheet-id
                                 :tick-id tick-id
                                 :node-id node-id
                                 :inputs (or inputs {})}})]}))

(deftest gap7b-rlm-tree-generated-triggers-heuristic-structural
  (testing "Gap-7b RED#1: a :rlm/tree-generated event with valid :raw-dsl triggers the new processor; heuristic-structural fires; exactly 1 :judge/score-emitted event lands tagged with the correct tick-id + node-id (looked up from the started event)"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      ;; Create a repl-researcher node (Gap-5 default attachment applies).
      (let [sheet-id (create-bare-sheet! ctx)
            node-id (create-repl-researcher-node! ctx sheet-id)
            tick-id (random-uuid)
            tree-dsl [:sequence
                      [:chunk-document {:from :doc :into :chunks}]
                      [:map-each {:from :chunks :as :chunk :into :results
                                  :max-concurrency 3}
                       [:llm {:reads [:chunk] :writes [:result]}]]
                      [:aggregate {:from :results :writes [:items]}]
                      [:final {:keys [:items]}]]]
        ;; Started event so the processor can lookup sheet/node from tick.
        (emit-node-execution-started! ctx sheet-id tick-id node-id {:doc "x"})
        (Thread/sleep 100)
        (let [before (count (into [] (es/read (:event-store ctx)
                                               {:types #{:judge/score-emitted}
                                                :tenant-id (:tenant-id ctx)})))]
          (emit-rlm-tree-generated! ctx sheet-id tick-id tree-dsl)
          (Thread/sleep 800) ;; allow async processor + judge to settle
          (let [after-events (into [] (es/read (:event-store ctx)
                                                {:types #{:judge/score-emitted}
                                                 :tenant-id (:tenant-id ctx)}))
                new-events (filter #(= tick-id (:tick-id %)) after-events)]
            (is (= 1 (count new-events))
                (str "Exactly 1 :judge/score-emitted event must land for this tick. "
                     "Total before: " before
                     ", after: " (count after-events)
                     ", matching tick: " (count new-events)))
            (when (seq new-events)
              (let [event (first new-events)]
                (is (= "heuristic-structural" (:judge-name event))
                    "The judge that fires is heuristic-structural")
                (is (= sheet-id (:sheet-id event))
                    "Score event carries the looked-up sheet-id")
                (is (= node-id (:node-id event))
                    "Score event carries the looked-up node-id")
                (is (number? (:score event))
                    "Score is numeric")))))))))

;; =============================================================================
;; Gap-7b RED#2 — defensive: :rlm/tree-generated with no :raw-dsl no-ops
;; =============================================================================
;;
;; The schema marks :raw-dsl as :any (it can technically be nil), and
;; even legitimately-emitted events might carry an empty tree under
;; degenerate Phase 1 conditions. The processor must not crash and
;; must not emit a score event when there's nothing to grade.

(deftest gap7b-rlm-tree-generated-with-no-tree-no-ops
  (testing "Gap-7b RED#2: when :rlm/tree-generated arrives with :raw-dsl nil, processor returns no events and doesn't crash"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [sheet-id (create-bare-sheet! ctx)
            node-id (create-repl-researcher-node! ctx sheet-id)
            tick-id (random-uuid)]
        (emit-node-execution-started! ctx sheet-id tick-id node-id {:doc "x"})
        (Thread/sleep 100)
        (let [before (count (into [] (es/read (:event-store ctx)
                                               {:types #{:judge/score-emitted}
                                                :tenant-id (:tenant-id ctx)})))]
          ;; Emit a :rlm/tree-generated event with :raw-dsl nil.
          (emit-rlm-tree-generated! ctx sheet-id tick-id nil)
          (Thread/sleep 600)
          (let [after (count (into [] (es/read (:event-store ctx)
                                                {:types #{:judge/score-emitted}
                                                 :tenant-id (:tenant-id ctx)})))]
            (is (= before after)
                (str "No new :judge/score-emitted events when :raw-dsl is nil. "
                     "Before: " before ", After: " after))))))))

;; =============================================================================
;; Gap-7b RED#3 — opt-in flag gating (mirrors existing processor)
;; =============================================================================
;;
;; The on-rlm-tree-generated processor must respect the same Living
;; Description opt-in gate as on-node-execution-completed. With the
;; flag OFF, valid :rlm/tree-generated events with valid trees and
;; valid host nodes must NOT produce judge events.

(deftest gap7b-rlm-tree-generated-gated-on-opt-in
  (testing "Gap-7b RED#3: when Living Description opt-in is OFF, the rlm/tree-generated processor produces no judge events even for valid events"
    (with-test-ctx [ctx]
      ;; Opt-in EXPLICITLY OFF
      (set-living-description-enabled! ctx false)
      (let [sheet-id (create-bare-sheet! ctx)
            node-id (create-repl-researcher-node! ctx sheet-id)
            tick-id (random-uuid)
            tree-dsl [:sequence [:llm {}] [:final {}]]]
        (emit-node-execution-started! ctx sheet-id tick-id node-id {:doc "x"})
        (Thread/sleep 100)
        (let [before (count (into [] (es/read (:event-store ctx)
                                               {:types #{:judge/score-emitted}
                                                :tenant-id (:tenant-id ctx)})))]
          (emit-rlm-tree-generated! ctx sheet-id tick-id tree-dsl)
          (Thread/sleep 600)
          (let [after (count (into [] (es/read (:event-store ctx)
                                                {:types #{:judge/score-emitted}
                                                 :tenant-id (:tenant-id ctx)})))]
            (is (= before after)
                (str "Opt-in OFF must produce ZERO new judge events. "
                     "Before: " before ", After: " after))))))))

;; =============================================================================
;; Gap-4 RED#1 (tracer) — :custom judge sub-executes a consumer eval sheet
;; =============================================================================
;;
;; This is the "consumer brings their own judge" affordance. The
;; consumer builds an ORC workflow (any code/llm/composite structure
;; that ultimately writes :score + :feedback to its blackboard).
;; They `declare-judge` with :type :custom + :sheet-id, then attach
;; the judge to host nodes. When the host node's completion event
;; arrives, the runtime sub-executes the eval sheet with :host-inputs/
;; :host-outputs/:host-instruction as inputs.

(defn- build-eval-workflow!
  "Helper: build a tiny eval workflow that runs a single :code node
   reading :host-outputs (the host node's writes) and writing :score
   + :feedback. Returns the materialized sheet-id."
  [ctx workflow-name fn-symbol]
  (let [workflow (orc/workflow workflow-name
                   (orc/blackboard
                     {:host-inputs :any
                      :host-outputs :any
                      :host-instruction :any
                      :host-trace :any
                      :score :double
                      :feedback :string})
                   (orc/code "eval"
                     :fn fn-symbol
                     :reads [:host-outputs]
                     :writes [:score :feedback]))]
    (orc/build-workflow! ctx workflow)))

(deftest gap4-custom-judge-sub-executes-eval-sheet
  (testing "Gap-4 RED#1: a :custom judge with :sheet-id pointing at a consumer-built eval workflow sub-executes that workflow when the host node completes; :judge/score-emitted lands with the score the eval sheet produced"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [eval-sheet-id (build-eval-workflow!
                            ctx
                            (str "gap4-length-judge-" (random-uuid))
                            "ai.obney.orc.evaluation.judge-runtime-test/custom-judge-by-output-length")
            ;; Declare the custom judge on a host sheet with the eval-sheet-id.
            host-sheet-result (cp/process-command
                                (assoc ctx :command
                                       {:command/name :sheet/create-sheet
                                        :command/id (random-uuid)
                                        :command/timestamp (time/now)
                                        :name (str "host-sheet-" (random-uuid))}))
            host-sheet-id (-> host-sheet-result :command-result/events first :sheet-id)
            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :sheet/declare-judge
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :sheet-id host-sheet-id
                        :judge-name "output-length"
                        :judge-config {:type :custom :sheet-id eval-sheet-id}}))
            host-node-result (cp/process-command
                               (assoc ctx :command
                                      {:command/name :sheet/create-node
                                       :command/id (random-uuid)
                                       :command/timestamp (time/now)
                                       :sheet-id host-sheet-id
                                       :type :leaf}))
            host-node-id (-> host-node-result :command-result/events first :node-id)
            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :sheet/set-node-judges
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :sheet-id host-sheet-id
                        :node-id host-node-id
                        :judges ["output-length"]}))
            tick-id (random-uuid)
            host-output-payload {:answer "A response from the model with some characters"}]
        (Thread/sleep 200)
        ;; Trigger the host node completion event — the runtime should
        ;; sub-execute the eval sheet and emit a score event.
        (cp/process-command
          (assoc ctx :command
                 {:command/name :sheet/complete-node-execution
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :sheet-id host-sheet-id
                  :tick-id tick-id
                  :node-id host-node-id
                  :node-type :leaf
                  :status :success
                  :writes host-output-payload}))
        ;; Sub-execution is synchronous via orc/execute but the
        ;; processor itself is async; poll for the score event.
        (loop [waited 0]
          (let [n (count (into [] (es/read (:event-store ctx)
                                            {:types #{:judge/score-emitted}
                                             :tenant-id (:tenant-id ctx)})))]
            (cond
              (>= n 1) :done
              (>= waited 15000) :timeout
              :else (do (Thread/sleep 500) (recur (+ waited 500))))))
        (let [events (into [] (es/read (:event-store ctx)
                                        {:types #{:judge/score-emitted}
                                         :tenant-id (:tenant-id ctx)}))
              matching (filter #(= tick-id (:tick-id %)) events)]
          (is (= 1 (count matching))
              (str "Exactly 1 :judge/score-emitted event must land for this tick. "
                   "Got: " (count matching) " matching of " (count events) " total."))
          (when (seq matching)
            (let [event (first matching)
                  expected-len (count (str host-output-payload))
                  expected-score (min 1.0 (/ expected-len 200.0))]
              (is (= "output-length" (:judge-name event))
                  "Score event names the custom judge")
              (is (= host-sheet-id (:sheet-id event)))
              (is (= host-node-id (:node-id event)))
              (is (= expected-score (:score event))
                  (str "Score matches the eval fn's computation. Expected "
                       expected-score " from " expected-len " chars; got "
                       (:score event)))
              (is (str/includes? (:feedback event) (str expected-len))
                  (str ":feedback flows through from eval sheet output. Got: "
                       (pr-str (:feedback event)))))))))))

;; =============================================================================
;; Gap-4 RED#1.5 — custom LLM judge with structured outputs
;; =============================================================================
;;
;; Custom judges aren't limited to deterministic :code workflows. The
;; consumer's eval sheet can include `:llm` nodes — the same way the
;; default grounding/reasoning/completeness/instruction-following judges
;; do. This test confirms generalization: a custom judge defined as an
;; LLM-graded workflow harvests structured outputs (:score, :feedback,
;; :dimensions) from the LLM's response and emits a `:judge/score-
;; emitted` event of the same shape as deterministic judges.
;;
;; Unit test uses with-redefs on dscloj/predict to return synthetic
;; structured outputs. LIVE verify (RED-LIVE) hits real OpenRouter.

(require '[dscloj.core :as dscloj])

(defn- with-faked-llm
  "Run body with dscloj/predict stubbed to return synthetic structured
   outputs. Mirrors the consolidator-test helper of the same name."
  [outputs f]
  (with-redefs [dscloj/predict (fn [_provider _module _inputs _options]
                                  {:outputs outputs
                                   :usage {:total-tokens 100}
                                   :model "fake-llm-judge"})]
    (f)))

(defn- build-llm-eval-workflow!
  "Helper: build an eval workflow that uses an :llm node to grade the
   host's output. The LLM reads :host-outputs + :host-instruction and
   writes :score + :feedback + :dimensions. Returns the materialized
   sheet-id."
  [ctx workflow-name]
  (let [workflow (orc/workflow workflow-name
                   (orc/blackboard
                     {:host-inputs :any
                      :host-outputs :any
                      :host-instruction :any
                      :host-trace :any
                      :score :double
                      :feedback :string
                      :dimensions [:vector :any]})
                   (orc/llm "grade"
                     :model "google/gemini-3-flash-preview"
                     :instruction (str "You are an evaluation judge. Given the host node's "
                                       ":host-outputs and the original :host-instruction, "
                                       "score how well the output addresses the instruction. "
                                       "Return :score (0.0-1.0), :feedback (string explaining "
                                       "the score), and :dimensions (vector of "
                                       "{:name :score :feedback} maps for sub-criteria).")
                     :reads [:host-outputs :host-instruction]
                     :writes [:score :feedback :dimensions]))]
    (orc/build-workflow! ctx workflow)))

(deftest gap4-custom-llm-judge-emits-structured-score
  (testing "Gap-4 RED#1.5: a custom judge with an :llm node grades the host's output, harvests :score + :feedback + :dimensions from the LLM's structured response, and emits a properly-shaped :judge/score-emitted event"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [eval-sheet-id (build-llm-eval-workflow!
                            ctx
                            (str "gap4-llm-judge-" (random-uuid)))
            host-sheet-id (let [r (cp/process-command
                                    (assoc ctx :command
                                           {:command/name :sheet/create-sheet
                                            :command/id (random-uuid)
                                            :command/timestamp (time/now)
                                            :name (str "host-llm-" (random-uuid))}))]
                            (-> r :command-result/events first :sheet-id))
            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :sheet/declare-judge
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :sheet-id host-sheet-id
                        :judge-name "task-completion"
                        :judge-config {:type :custom :sheet-id eval-sheet-id}}))
            host-node-id (let [r (cp/process-command
                                   (assoc ctx :command
                                          {:command/name :sheet/create-node
                                           :command/id (random-uuid)
                                           :command/timestamp (time/now)
                                           :sheet-id host-sheet-id
                                           :type :leaf}))]
                           (-> r :command-result/events first :node-id))
            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :sheet/set-node-judges
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :sheet-id host-sheet-id
                        :node-id host-node-id
                        :judges ["task-completion"]}))
            tick-id (random-uuid)
            ;; Synthetic LLM response — what a grading LLM would
            ;; produce when reading the host's output through the
            ;; rubric instruction.
            fake-llm-outputs {:score 0.85
                              :feedback (str "The host's output is well-formed and addresses "
                                             "the instruction. Minor gap: did not include a "
                                             "rationale for the chosen approach.")
                              :dimensions [{:name "completeness"
                                            :score 0.9
                                            :feedback "Covered the main requirements"}
                                           {:name "clarity"
                                            :score 0.8
                                            :feedback "Mostly clear; some technical jargon"}]}
            host-writes {:answer "A response that addresses the task."}]
        (Thread/sleep 200)
        ;; Fake the LLM call inside the eval sheet's :llm node.
        (with-faked-llm fake-llm-outputs
          (fn []
            (cp/process-command
              (assoc ctx :command
                     {:command/name :sheet/complete-node-execution
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :sheet-id host-sheet-id
                      :tick-id tick-id
                      :node-id host-node-id
                      :node-type :leaf
                      :status :success
                      :writes host-writes}))
            ;; Poll for the score event — orc/execute is sync but the
            ;; processor is async via futures, so we poll briefly.
            (loop [waited 0]
              (let [n (count (filter #(= tick-id (:tick-id %))
                                     (into [] (es/read (:event-store ctx)
                                                        {:types #{:judge/score-emitted}
                                                         :tenant-id (:tenant-id ctx)}))))]
                (cond
                  (>= n 1) :done
                  (>= waited 15000) :timeout
                  :else (do (Thread/sleep 500) (recur (+ waited 500))))))))
        (let [events (into [] (es/read (:event-store ctx)
                                        {:types #{:judge/score-emitted}
                                         :tenant-id (:tenant-id ctx)}))
              matching (filter #(= tick-id (:tick-id %)) events)]
          (is (= 1 (count matching))
              (str "1 :judge/score-emitted event must land for the LLM custom judge. "
                   "Got: " (count matching)))
          (when (seq matching)
            (let [event (first matching)]
              (is (= "task-completion" (:judge-name event)))
              (is (= 0.85 (:score event))
                  "Score harvested verbatim from the LLM's structured output")
              (is (str/includes? (:feedback event) "well-formed")
                  ":feedback string flows through unchanged from LLM response")
              (is (= 2 (count (:dimensions event)))
                  ":dimensions vector flows through")
              (is (= #{"completeness" "clarity"}
                     (set (map :name (:dimensions event))))
                  "Each dimension carries the LLM's sub-criterion name"))))))))

;; =============================================================================
;; Gap-4 RED#2 — eval sheet outputs lack :score → graceful no-op
;; =============================================================================
;;
;; If a consumer's eval sheet produces outputs WITHOUT a numeric
;; :score (broken contract), the runtime must not emit a malformed
;; :judge/score-emitted event. It logs the issue (via mulog
;; ::custom-judge-output-missing-score) and returns nil. Sibling
;; judges + other ticks continue unaffected.

(defn- build-broken-eval-workflow!
  "Helper: build an eval workflow that does NOT write :score (broken
   contract). The :code node writes only :feedback."
  [ctx workflow-name]
  (let [workflow (orc/workflow workflow-name
                   (orc/blackboard
                     {:host-inputs :any
                      :host-outputs :any
                      :host-instruction :any
                      :host-trace :any
                      :feedback :string})
                   (orc/code "eval"
                     :fn "ai.obney.orc.evaluation.judge-runtime-test/custom-judge-broken-no-score"
                     :reads [:host-outputs]
                     :writes [:feedback]))]
    (orc/build-workflow! ctx workflow)))

(deftest gap4-custom-judge-missing-score-no-ops
  (testing "Gap-4 RED#2: eval sheet that doesn't write :score produces no :judge/score-emitted event (graceful contract violation)"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [eval-sheet-id (build-broken-eval-workflow!
                            ctx (str "gap4-broken-" (random-uuid)))
            host-sheet-id (let [r (cp/process-command
                                    (assoc ctx :command
                                           {:command/name :sheet/create-sheet
                                            :command/id (random-uuid)
                                            :command/timestamp (time/now)
                                            :name (str "host-broken-" (random-uuid))}))]
                            (-> r :command-result/events first :sheet-id))
            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :sheet/declare-judge
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :sheet-id host-sheet-id
                        :judge-name "broken-judge"
                        :judge-config {:type :custom :sheet-id eval-sheet-id}}))
            host-node-id (let [r (cp/process-command
                                   (assoc ctx :command
                                          {:command/name :sheet/create-node
                                           :command/id (random-uuid)
                                           :command/timestamp (time/now)
                                           :sheet-id host-sheet-id
                                           :type :leaf}))]
                           (-> r :command-result/events first :node-id))
            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :sheet/set-node-judges
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :sheet-id host-sheet-id
                        :node-id host-node-id
                        :judges ["broken-judge"]}))
            tick-id (random-uuid)]
        (Thread/sleep 200)
        (let [before (count (into [] (es/read (:event-store ctx)
                                               {:types #{:judge/score-emitted}
                                                :tenant-id (:tenant-id ctx)})))]
          (cp/process-command
            (assoc ctx :command
                   {:command/name :sheet/complete-node-execution
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :sheet-id host-sheet-id
                    :tick-id tick-id
                    :node-id host-node-id
                    :node-type :leaf
                    :status :success
                    :writes {:answer "anything"}}))
          (Thread/sleep 1500)
          (let [after (count (into [] (es/read (:event-store ctx)
                                                {:types #{:judge/score-emitted}
                                                 :tenant-id (:tenant-id ctx)})))]
            (is (= before after)
                (str "No score event when eval sheet outputs lack :score. "
                     "Before: " before ", After: " after))))))))

;; =============================================================================
;; Gap-4 RED#3 — multiple :custom judges on the same node fire independently
;; =============================================================================
;;
;; The parallel-judges discipline (Gap-7 fix) handles this for default
;; judges. Verify it generalizes to custom judges: two distinct eval
;; sheets attached as judges to the same node each emit their own
;; :judge/score-emitted event with their respective judge-name.

(defn- build-schema-eval-workflow!
  "Helper: eval workflow scoring schema compliance via custom-judge-schema-compliance."
  [ctx workflow-name]
  (orc/build-workflow! ctx
                       (orc/workflow workflow-name
                         (orc/blackboard
                           {:host-inputs :any
                            :host-outputs :any
                            :host-instruction :any
                            :host-trace :any
                            :score :double
                            :feedback :string})
                         (orc/code "eval"
                           :fn "ai.obney.orc.evaluation.judge-runtime-test/custom-judge-schema-compliance"
                           :reads [:host-outputs]
                           :writes [:score :feedback]))))

(deftest gap4-multiple-custom-judges-fire-independently
  (testing "Gap-4 RED#3: two :custom judges with distinct eval sheets attached to the same node both fire and emit their own :judge/score-emitted events tagged with their respective judge-name"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [length-sheet-id (build-eval-workflow!
                              ctx
                              (str "gap4-length-" (random-uuid))
                              "ai.obney.orc.evaluation.judge-runtime-test/custom-judge-by-output-length")
            schema-sheet-id (build-schema-eval-workflow!
                              ctx
                              (str "gap4-schema-" (random-uuid)))
            host-sheet-id (let [r (cp/process-command
                                    (assoc ctx :command
                                           {:command/name :sheet/create-sheet
                                            :command/id (random-uuid)
                                            :command/timestamp (time/now)
                                            :name (str "host-multi-" (random-uuid))}))]
                            (-> r :command-result/events first :sheet-id))
            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :sheet/declare-judge
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :sheet-id host-sheet-id
                        :judge-name "length-judge"
                        :judge-config {:type :custom :sheet-id length-sheet-id}}))
            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :sheet/declare-judge
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :sheet-id host-sheet-id
                        :judge-name "schema-judge"
                        :judge-config {:type :custom :sheet-id schema-sheet-id}}))
            host-node-id (let [r (cp/process-command
                                   (assoc ctx :command
                                          {:command/name :sheet/create-node
                                           :command/id (random-uuid)
                                           :command/timestamp (time/now)
                                           :sheet-id host-sheet-id
                                           :type :leaf}))]
                           (-> r :command-result/events first :node-id))
            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :sheet/set-node-judges
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :sheet-id host-sheet-id
                        :node-id host-node-id
                        :judges ["length-judge" "schema-judge"]}))
            tick-id (random-uuid)
            host-writes {:answer "Final response from host"}]
        (Thread/sleep 200)
        (cp/process-command
          (assoc ctx :command
                 {:command/name :sheet/complete-node-execution
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :sheet-id host-sheet-id
                  :tick-id tick-id
                  :node-id host-node-id
                  :node-type :leaf
                  :status :success
                  :writes host-writes}))
        (loop [waited 0]
          (let [matching (filter #(= tick-id (:tick-id %))
                                 (into [] (es/read (:event-store ctx)
                                                    {:types #{:judge/score-emitted}
                                                     :tenant-id (:tenant-id ctx)})))]
            (cond
              (>= (count matching) 2) :done
              (>= waited 15000) :timeout
              :else (do (Thread/sleep 500) (recur (+ waited 500))))))
        (let [matching (filter #(= tick-id (:tick-id %))
                               (into [] (es/read (:event-store ctx)
                                                  {:types #{:judge/score-emitted}
                                                   :tenant-id (:tenant-id ctx)})))]
          (is (= 2 (count matching))
              (str "Both custom judges must fire. Got: " (count matching)))
          (is (= #{"length-judge" "schema-judge"}
                 (set (map :judge-name matching)))
              "Each event names a distinct judge")
          ;; Schema-judge scores 1.0 because :answer is present;
          ;; length-judge scores (24 / 200) = 0.12.
          (let [by-name (into {} (map (fn [e] [(:judge-name e) (:score e)])) matching)]
            (is (= 1.0 (get by-name "schema-judge"))
                "schema-judge scores 1.0 because :answer field is present")
            (is (number? (get by-name "length-judge"))
                "length-judge produces a numeric score independently")))))))

;; =============================================================================
;; Gap-4 RED#4 — invalid eval-sheet-id → graceful error, siblings still fire
;; =============================================================================
;;
;; If a custom judge points at a non-existent sheet-id (consumer
;; misconfigured, sheet deleted, etc), the runtime must NOT crash. It
;; should log via mulog and skip — sibling judges (default or other
;; custom) on the same node continue to fire normally.

(deftest gap4-invalid-sheet-id-graceful-with-siblings
  (testing "Gap-4 RED#4: a custom judge with :sheet-id pointing at a nonexistent sheet skips gracefully; another custom judge with a valid sheet-id on the same node still fires"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [valid-sheet-id (build-eval-workflow!
                             ctx
                             (str "gap4-valid-" (random-uuid))
                             "ai.obney.orc.evaluation.judge-runtime-test/custom-judge-by-output-length")
            bogus-sheet-id (random-uuid)  ;; never built — doesn't exist
            host-sheet-id (let [r (cp/process-command
                                    (assoc ctx :command
                                           {:command/name :sheet/create-sheet
                                            :command/id (random-uuid)
                                            :command/timestamp (time/now)
                                            :name (str "host-mixed-" (random-uuid))}))]
                            (-> r :command-result/events first :sheet-id))
            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :sheet/declare-judge
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :sheet-id host-sheet-id
                        :judge-name "valid-judge"
                        :judge-config {:type :custom :sheet-id valid-sheet-id}}))
            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :sheet/declare-judge
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :sheet-id host-sheet-id
                        :judge-name "bogus-judge"
                        :judge-config {:type :custom :sheet-id bogus-sheet-id}}))
            host-node-id (let [r (cp/process-command
                                   (assoc ctx :command
                                          {:command/name :sheet/create-node
                                           :command/id (random-uuid)
                                           :command/timestamp (time/now)
                                           :sheet-id host-sheet-id
                                           :type :leaf}))]
                           (-> r :command-result/events first :node-id))
            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :sheet/set-node-judges
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :sheet-id host-sheet-id
                        :node-id host-node-id
                        :judges ["valid-judge" "bogus-judge"]}))
            tick-id (random-uuid)]
        (Thread/sleep 200)
        (cp/process-command
          (assoc ctx :command
                 {:command/name :sheet/complete-node-execution
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :sheet-id host-sheet-id
                  :tick-id tick-id
                  :node-id host-node-id
                  :node-type :leaf
                  :status :success
                  :writes {:answer "x"}}))
        ;; Wait for the valid judge to settle (5 sec budget).
        (loop [waited 0]
          (let [matching (filter #(= tick-id (:tick-id %))
                                 (into [] (es/read (:event-store ctx)
                                                    {:types #{:judge/score-emitted}
                                                     :tenant-id (:tenant-id ctx)})))]
            (cond
              (>= (count matching) 1) :done
              (>= waited 15000) :timeout
              :else (do (Thread/sleep 500) (recur (+ waited 500))))))
        (Thread/sleep 1500) ;; ensure bogus-judge has had its chance to fail/skip
        (let [matching (filter #(= tick-id (:tick-id %))
                               (into [] (es/read (:event-store ctx)
                                                  {:types #{:judge/score-emitted}
                                                   :tenant-id (:tenant-id ctx)})))]
          (is (= 1 (count matching))
              (str "Exactly 1 score event — only valid-judge produced it. Got: "
                   (count matching)))
          (when (seq matching)
            (is (= "valid-judge" (:judge-name (first matching)))
                "The score event is from the valid judge, not the bogus one")))))))

;; =============================================================================
;; Gap-4 RED#5 — recursion safeguard (depth-1 max)
;; =============================================================================
;;
;; A maliciously-configured custom judge sheet could attach its OWN
;; :custom judges to its internal nodes, potentially spawning a
;; runaway chain of sub-ticks. The runtime tracks :judge-depth in
;; context; default max-depth = 1 (a judge sheet's internal nodes'
;; judges are SKIPPED).
;;
;; This test exercises invoke-custom-judge directly to verify the
;; depth-1 guard fires before sub-executing. Verifies the safeguard's
;; CONTRACT: at depth = max, invoke-custom-judge returns nil without
;; calling orc/execute.

(deftest gap4-recursion-safeguard-skips-at-max-depth
  (testing "Gap-4 RED#5: when ctx ::judge-depth is already at max-depth, invoke-custom-judge returns nil WITHOUT calling orc/execute (preventing infinite recursion)"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [invoke-custom-judge @#'ai.obney.orc.evaluation.core.judge-runtime/invoke-custom-judge
            ;; Build any eval sheet — recursion guard fires BEFORE
            ;; orc/execute would be called.
            eval-sheet-id (build-eval-workflow!
                            ctx
                            (str "gap4-recursion-" (random-uuid))
                            "ai.obney.orc.evaluation.judge-runtime-test/custom-judge-by-output-length")
            judge-config {:type :custom :eval-sheet-id eval-sheet-id}
            trace-data {:inputs {} :outputs {:answer "x"} :instruction ""}
            ;; Track whether orc/execute would have been called by
            ;; replacing the var temporarily.
            execute-called? (atom false)]
        (with-redefs [orc/execute (fn [& _]
                                     (reset! execute-called? true)
                                     {:status :success
                                      :outputs {:score 1.0 :feedback ""}})]
          ;; depth = max-depth (1 by default) → recursion guard fires
          (let [result (invoke-custom-judge
                         (assoc ctx
                                :ai.obney.orc.evaluation.core.judge-runtime/judge-depth 1)
                         judge-config trace-data)]
            (is (nil? result)
                "Recursion guard returns nil at max-depth")
            (is (false? @execute-called?)
                "orc/execute is NOT called when depth >= max-depth"))
          ;; depth < max-depth → normal execution
          (reset! execute-called? false)
          (let [result (invoke-custom-judge
                         (assoc ctx
                                :ai.obney.orc.evaluation.core.judge-runtime/judge-depth 0)
                         judge-config trace-data)]
            (is (some? result)
                "At depth < max-depth, judge runs normally")
            (is (true? @execute-called?)
                "orc/execute IS called when depth < max-depth")))))))

;; =============================================================================
;; Gap-4 RED#6 — custom-judge scores flow into get-judge-scores
;; =============================================================================
;;
;; Custom judge scores must be queryable via the same get-judge-scores
;; read-model the default judges use. Gap-3's consolidator integration
;; then picks them up via :judge-averages in :aggregate-metrics. This
;; test verifies the read-model integration; the consolidator
;; aggregation is covered downstream by the existing consolidator
;; tests once custom scores are in the store.

(deftest gap4-custom-judge-scores-flow-into-read-model
  (testing "Gap-4 RED#6: after a custom judge fires, its score appears in get-judge-scores for the (sheet, node, tick) tuple alongside any other judges"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [length-sheet-id (build-eval-workflow!
                              ctx
                              (str "gap4-flow-length-" (random-uuid))
                              "ai.obney.orc.evaluation.judge-runtime-test/custom-judge-by-output-length")
            schema-sheet-id (build-schema-eval-workflow!
                              ctx
                              (str "gap4-flow-schema-" (random-uuid)))
            host-sheet-id (let [r (cp/process-command
                                    (assoc ctx :command
                                           {:command/name :sheet/create-sheet
                                            :command/id (random-uuid)
                                            :command/timestamp (time/now)
                                            :name (str "host-flow-" (random-uuid))}))]
                            (-> r :command-result/events first :sheet-id))
            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :sheet/declare-judge
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :sheet-id host-sheet-id
                        :judge-name "len"
                        :judge-config {:type :custom :sheet-id length-sheet-id}}))
            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :sheet/declare-judge
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :sheet-id host-sheet-id
                        :judge-name "sch"
                        :judge-config {:type :custom :sheet-id schema-sheet-id}}))
            host-node-id (let [r (cp/process-command
                                   (assoc ctx :command
                                          {:command/name :sheet/create-node
                                           :command/id (random-uuid)
                                           :command/timestamp (time/now)
                                           :sheet-id host-sheet-id
                                           :type :leaf}))]
                           (-> r :command-result/events first :node-id))
            _ (cp/process-command
                (assoc ctx :command
                       {:command/name :sheet/set-node-judges
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :sheet-id host-sheet-id
                        :node-id host-node-id
                        :judges ["len" "sch"]}))
            tick-id (random-uuid)
            get-judge-scores (requiring-resolve
                               'ai.obney.orc.evaluation.interface/get-judge-scores)]
        (Thread/sleep 200)
        (cp/process-command
          (assoc ctx :command
                 {:command/name :sheet/complete-node-execution
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :sheet-id host-sheet-id
                  :tick-id tick-id
                  :node-id host-node-id
                  :node-type :leaf
                  :status :success
                  :writes {:answer "Hello world"}}))
        ;; Wait for both judges to settle AND for the eventually-consistent
        ;; :evaluation/judge-scores read-model to reflect both. Poll the
        ;; read-model directly (it lags the raw events under load — the
        ;; async command path makes the two custom-judge emissions land at
        ;; slightly different times).
        (loop [waited 0]
          (let [scores (get-judge-scores ctx host-sheet-id host-node-id tick-id)]
            (cond
              (>= (count scores) 2) :done
              (>= waited 20000) :timeout
              :else (do (Thread/sleep 250) (recur (+ waited 250))))))
        (let [scores (get-judge-scores ctx host-sheet-id host-node-id tick-id)]
          (is (= 2 (count scores))
              (str "get-judge-scores returns both custom judge entries. Got: " (count scores)))
          (is (= #{"len" "sch"} (set (map :judge-name scores)))
              "Both judge names present in the read-model")
          (is (every? #(number? (:score %)) scores)
              "Every entry has a numeric :score field"))))))

;; =============================================================================
;; Gap-8 RED#1 (tracer) — compute-composite-score weighted math
;; =============================================================================
;;
;; Pure function: takes a sequence of {:judge-name :score :weight} entries
;; and returns a weighted composite score in [0.0, 1.0]. Locks the
;; arithmetic before any processor wiring touches it.
;;
;; Default policy (per spec Decision 1 — Option A): when no weights are
;; set, each judge gets 1/N. When explicit weights exist, they're
;; normalized to sum to 1.0 across the set.

(deftest gap8-composite-score-three-weighted-judges
  (testing "Gap-8 RED#1: 3 judges with explicit weights [0.5 0.3 0.2] + scores [0.9 0.6 0.4] → composite = 0.5*0.9 + 0.3*0.6 + 0.2*0.4 = 0.71"
    (let [compute @#'ai.obney.orc.evaluation.core.judge-runtime/compute-composite-score
          entries [{:judge-name "a" :score 0.9 :weight 0.5}
                   {:judge-name "b" :score 0.6 :weight 0.3}
                   {:judge-name "c" :score 0.4 :weight 0.2}]
          composite (compute entries)]
      (is (some? composite)
          "compute-composite-score returns a non-nil result for valid input")
      ;; Expected: 0.5*0.9 + 0.3*0.6 + 0.2*0.4 = 0.45 + 0.18 + 0.08 = 0.71
      (is (= 0.71 composite)
          (str "Composite must be the weighted sum exactly (no floating-point fuzz "
               "expected at this precision). Got: " composite)))))

(deftest gap8-composite-score-even-weight-default
  (testing "Gap-8 RED#2: when no judges have :weight set, each gets 1/N and composite = mean of scores"
    (let [compute @#'ai.obney.orc.evaluation.core.judge-runtime/compute-composite-score]
      ;; 2 judges, no weights → mean
      (is (= 0.7 (compute [{:judge-name "a" :score 0.8}
                            {:judge-name "b" :score 0.6}]))
          "2 judges no weights → (0.8 + 0.6) / 2 = 0.7")
      ;; 4 judges, no weights → mean
      (is (= 0.5 (compute [{:judge-name "a" :score 0.8}
                            {:judge-name "b" :score 0.6}
                            {:judge-name "c" :score 0.4}
                            {:judge-name "d" :score 0.2}]))
          "4 judges no weights → mean = 0.5"))))

(deftest gap8-composite-score-single-judge
  (testing "Gap-8 RED#3: single judge no surprises — composite equals the judge's score regardless of whether weight is set"
    (let [compute @#'ai.obney.orc.evaluation.core.judge-runtime/compute-composite-score]
      (is (= 0.85 (compute [{:judge-name "only" :score 0.85}]))
          "Single judge no weight → composite = its score")
      (is (= 0.85 (compute [{:judge-name "only" :score 0.85 :weight 0.42}]))
          "Single judge with weight (any positive) → composite = its score (weight normalizes to 1.0)")
      (is (= 1.0 (compute [{:judge-name "only" :score 1.0 :weight 100}]))
          "Single judge with absurd weight → still its score after normalization"))))

;; =============================================================================
;; Gap-8 RED#4 — mixed weights: un-weighted judges share remaining mass evenly
;; =============================================================================
;;
;; Policy (per user decision): when SOME judges have :weight set and others
;; don't, the explicit weights are honored verbatim and the un-weighted
;; judges share the REMAINING probability mass evenly. This matches the
;; consumer mental model: "I weighted one judge specifically; the rest
;; default normally." Adding :weight to one judge MUST NOT silently zero
;; the others — that would be a footgun.
;;
;; Edge case: when explicit weights already sum to ≥ 1.0, the un-weighted
;; judges get weight 0.0 (the consumer's explicit weights already used up
;; the budget).

(deftest gap8-mixed-weights-unweighted-share-remaining-mass
  (testing "Gap-8 RED#4: explicit weights honored; un-weighted judges share what's left over evenly"
    (let [normalize @#'ai.obney.orc.evaluation.core.judge-runtime/normalize-judge-weights
          compute @#'ai.obney.orc.evaluation.core.judge-runtime/compute-composite-score]
      ;; 3 judges, ONE weighted at 0.5; remaining 0.5 splits evenly between the other 2 → 0.25 each.
      (let [entries [{:judge-name "explicit" :score 0.9 :weight 0.5}
                     {:judge-name "default-a" :score 0.6}
                     {:judge-name "default-b" :score 0.4}]
            normalized (normalize entries)
            weights (into {} (map (juxt :judge-name :weight) normalized))]
        (is (= 0.5 (get weights "explicit"))
            "Explicit-weight judge keeps its declared weight verbatim (already normalized in a 3-judge mixed scenario where remaining mass = 0.5)")
        (is (= 0.25 (get weights "default-a"))
            "Un-weighted judges share the REMAINING mass (1.0 - 0.5 = 0.5) evenly between the 2 un-weighted → 0.25 each")
        (is (= 0.25 (get weights "default-b"))
            "Symmetric — both un-weighted judges get the same share")
        ;; Composite = 0.5*0.9 + 0.25*0.6 + 0.25*0.4 = 0.45 + 0.15 + 0.10 = 0.70
        (is (= 0.7 (compute entries))
            "Composite reflects the share-remaining policy"))))

  (testing "Gap-8 RED#4: explicit weights summing to ≥ 1.0 → un-weighted judges get 0.0"
    (let [normalize @#'ai.obney.orc.evaluation.core.judge-runtime/normalize-judge-weights
          ;; 3 judges; two explicit summing to 1.0 already; the un-weighted has nothing left.
          entries [{:judge-name "explicit-a" :score 0.9 :weight 0.6}
                   {:judge-name "explicit-b" :score 0.5 :weight 0.4}
                   {:judge-name "default" :score 0.3}]
          normalized (normalize entries)
          weights (into {} (map (juxt :judge-name :weight) normalized))]
      (is (= 0.6 (get weights "explicit-a")) "Explicit weights preserved")
      (is (= 0.4 (get weights "explicit-b")) "Explicit weights preserved")
      (is (= 0.0 (get weights "default"))
          "When explicit weights already sum to ≥ 1.0, the un-weighted judge gets 0.0 — consumer's explicit choice already exhausted the mass")))

  (testing "Gap-8 RED#4: one explicit-weight judge + one un-weighted → un-weighted gets exactly the remaining mass"
    (let [normalize @#'ai.obney.orc.evaluation.core.judge-runtime/normalize-judge-weights
          entries [{:judge-name "explicit" :score 0.8 :weight 0.7}
                   {:judge-name "default" :score 0.4}]
          normalized (normalize entries)
          weights (into {} (map (juxt :judge-name :weight) normalized))]
      (is (= 0.7 (get weights "explicit")) "Explicit 0.7 preserved")
      (is (= 0.3 (get weights "default"))
          "Single un-weighted judge gets 1.0 - 0.7 = 0.3, no division required"))))

;; =============================================================================
;; Gap-8 RED#5 — processor emits :judge/composite-score-computed alongside scores
;; =============================================================================
;;
;; When multiple judges fire on a (sheet, node, tick), the processor
;; that already waits for all parallel futures to complete (Gap-7
;; parallel-judges fix) ALSO computes the weighted composite from the
;; collected results and appends a single
;; :judge/composite-score-computed event to its :result/events vector.
;; Atomic emission per tick, no separate processor.

(deftest gap8-processor-emits-composite-event
  (testing "Gap-8 RED#5: when 2 judges fire on a tick, the processor emits exactly 1 :judge/composite-score-computed event tagged with the same (sheet, node, tick) and a composite score derived from the judges' scores"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (with-redefs [judges/*use-mock-llm* true]
        (let [{:keys [sheet-id node-id]}
              (setup-sheet-with-multiple-judges!
                ctx
                [["g" {:type :grounding}]
                 ["r" {:type :reasoning}]])
              tick-id (random-uuid)]
          (emit-node-execution-completed! ctx sheet-id tick-id node-id)
          ;; Poll for both score events AND the composite event
          (loop [waited 0]
            (let [scores (filter #(= tick-id (:tick-id %))
                                 (into [] (es/read (:event-store ctx)
                                                    {:types #{:judge/score-emitted}
                                                     :tenant-id (:tenant-id ctx)})))
                  composites (filter #(= tick-id (:tick-id %))
                                     (into [] (es/read (:event-store ctx)
                                                        {:types #{:judge/composite-score-computed}
                                                         :tenant-id (:tenant-id ctx)})))]
              (cond
                (and (= 2 (count scores)) (= 1 (count composites))) :done
                (>= waited 10000) :timeout
                :else (do (Thread/sleep 300) (recur (+ waited 300))))))
          (let [composites (filter #(= tick-id (:tick-id %))
                                   (into [] (es/read (:event-store ctx)
                                                      {:types #{:judge/composite-score-computed}
                                                       :tenant-id (:tenant-id ctx)})))]
            (is (= 1 (count composites))
                (str "Exactly 1 composite event must land for this tick. Got: " (count composites)))
            (when (seq composites)
              (let [evt (first composites)]
                (is (= sheet-id (:sheet-id evt))
                    "Composite event carries the host sheet-id")
                (is (= node-id (:node-id evt))
                    "Composite event carries the host node-id")
                (is (number? (:composite-score evt))
                    ":composite-score is numeric")
                (is (and (>= (:composite-score evt) 0.0)
                         (<= (:composite-score evt) 1.0))
                    ":composite-score is in [0.0, 1.0]")
                (is (vector? (:contributing-judges evt))
                    ":contributing-judges lists which judges fed the composite")
                (is (= #{"g" "r"} (set (map :judge-name (:contributing-judges evt))))
                    "Both fired judges are listed as contributors")))))))))
