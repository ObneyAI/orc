(ns ai.obney.orc.ontology.core.consolidator
  "C-2a-3b — Living Description consolidator.

   Subscribes to :ontology/consolidation-requested events. For each
   request, gathers the target's current description + recent events +
   accumulated metrics + structural context, runs a single
   structured-output LLM reflection call, and emits the matching
   :*-description-updated event.

   Anti-recency-bias safeguards (confidence-decay/grow math + anomaly
   handling + budget cap + 3-run regression scenario) are SCOPED OUT —
   they land in C-2a-3c. For this slice, the LLM's :version and
   :consolidated-from-event-count fields are overwritten by the
   consolidator's computed values before emission."
  (:require [malli.core :as m]
            [malli.error :as me]
            [cheshire.core :as json]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas :as ontology-schemas]
            [ai.obney.grain.command-processor-v2.interface :as command-processor]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.todo-processor-v2.interface :refer [defprocessor]]
            [ai.obney.grain.time.interface :as time]
            [com.brunobonacci.mulog :as u]))

;; =============================================================================
;; Recent-window size (configurable in C-2a-3c via event-sourced config)
;; =============================================================================

(def ^:private recent-window-size
  "Default window size for the consolidator's recent-events input.
   Decoupled from the threshold (trigger fires after N=10 events; the
   reflection sees up to W=500 historical events for richer context)."
  500)

;; =============================================================================
;; Reflection prompt + structured-output workflow
;; =============================================================================

(def ^:private reflection-instruction
  "Instruction text for the reflection LLM. The model is asked to
   synthesize a principle-shaped description body from the inputs."
  (str "You are consolidating an evolving description of a behavior-tree component "
       "from observed execution evidence. Your job is to produce a principle-shaped "
       "description body that captures the component's strengths, weaknesses, and "
       "actionable usage guidance.\n\n"
       "ANTI-RECENCY DISCIPLINE — read this carefully:\n"
       "  :aggregate-metrics is the STABLE BASELINE accumulated across the target's lifetime.\n"
       "  :recent-events is a LEADING INDICATOR slice (most recent events only).\n"
       "  :recent-vs-historical-delta is the computed gap between recent and historical.\n"
       "  Update strength/weakness confidences GRADUALLY — large recent swings against a strong\n"
       "  historical baseline should produce SMALL confidence changes, not big ones. Erasing a\n"
       "  prior strong principle because of one bad burst is the failure mode to avoid.\n"
       "  A consistent + substantial recent shift (delta of >0.20 sustained, OR aggregate\n"
       "  evidence-count high but recent counter-evidence consistent) warrants larger changes.\n\n"
       "INPUTS PROVIDED:\n"
       "- :target-type — the granularity: :node-type, :node-instance, :tree-fingerprint, or :tree-class\n"
       "  (:tree-class is the substrate the model's prompt-injection reads from —\n"
       "  per-class descriptions evolve across runs of tasks assigned to the same class)\n"
       "- :target-id — the identity within that granularity (keyword, [sheet node] tuple, or string/UUID)\n"
       "- :current-description — the latest existing description body, or nil if this is the first consolidation\n"
       "- :recent-events — the last W events involving this target (status, duration, etc.).\n"
       "  Each observation MAY also carry :judge-scores, a vector of per-event evaluator outputs from "
       "judges attached to the host node — each entry has :judge-name, :score (0.0-1.0), and :feedback.\n"
       "- :aggregate-metrics — accumulated rolling metrics across this target's lifetime.\n"
       "  May include :judge-averages — a map of {judge-name -> mean-score} computed across all "
       "observations of this target's lifetime. This is the STABLE BASELINE for judge signal.\n"
       "- :recent-vs-historical-delta — {:recent-success-rate :historical-success-rate :delta}, or nil on first consolidation\n"
       "  JUDGE WEIGHTING: when a recent observation's :judge-scores diverge from "
       ":judge-averages, treat that the same way you treat success-rate deltas — a single bad "
       "judge score against a long-stable :judge-averages baseline is NOT grounds to invert a "
       "strength; consistent + substantial divergence across multiple recent observations IS.\n"
       "- :structural-context — for trees: the canonical tree-raw S-expression; "
       "for instances: the node config; for node-types: the keyword alone\n\n"
       "OUTPUT — :description-body is a single map with ALL of these top-level keys:\n"
       "  :capabilities         — vector of short strings, what the component CAN do\n"
       "  :strengths            — vector of principle-entry maps (see below)\n"
       "  :weaknesses           — vector of principle-entry maps (see below)\n"
       "  :representative-uses  — vector of short strings, concrete situations the component is good for\n"
       "  :avoid-when           — vector of short strings, contexts to AVOID using the component\n"
       "  :summary              — one-paragraph free-text synthesis (self-contained — NO file paths, NO internal slice names)\n"
       "  :version              — int (any value; consolidator overwrites with computed)\n"
       "  :consolidated-from-event-count — int (any value; consolidator overwrites with computed)\n\n"
       "EVERY one of those 8 keys MUST be present in your output. Empty vectors are acceptable for\n"
       ":capabilities, :strengths, :weaknesses, :representative-uses, :avoid-when when truly nothing applies,\n"
       "but you must include the key with an empty vector ([]) — DO NOT OMIT THE KEY.\n\n"
       "PRINCIPLE-ENTRY shape — every map in :strengths or :weaknesses must contain:\n"
       "  :trait                   — a concrete observable pattern (NOT a status like 'investigate')\n"
       "  :good-when               — context guard (strengths only)\n"
       "  :avoid-when              — context guard (weaknesses only)\n"
       "  :recommended-pattern     — actionable advice (strengths only)\n"
       "  :recommended-alternative — actionable advice (weaknesses only)\n"
       "  :confidence              — 0.0–1.0 weight signal\n"
       "  :evidence-count          — integer count of supporting observations\n"
       "  :first-observed-at       — ISO timestamp string\n"
       "  :last-reinforced-at      — ISO timestamp string\n\n"
       "NEVER produce status-shaped entries (avoid words like 'investigate', 'observed', "
       "'unclear'). Every entry, even at low confidence, must carry concrete actionable guidance.\n\n"
       "EXAMPLE OUTPUT (illustrative shape — your content will differ):\n"
       "  {:capabilities [\"runs a sub-LLM call\" \"produces structured output\"]\n"
       "   :strengths [{:trait \"...\" :good-when \"...\" :recommended-pattern \"...\"\n"
       "                :confidence 1.0 :evidence-count 8\n"
       "                :first-observed-at \"2026-05-20T00:00:00Z\"\n"
       "                :last-reinforced-at \"2026-05-26T00:00:00Z\"}]\n"
       "   :weaknesses [{:trait \"...\" :avoid-when \"...\" :recommended-alternative \"...\"\n"
       "                 :confidence 1.0 :evidence-count 2\n"
       "                 :first-observed-at \"2026-05-20T00:00:00Z\"\n"
       "                 :last-reinforced-at \"2026-05-26T00:00:00Z\"}]\n"
       "   :representative-uses [\"per-chunk extraction inside :map-each\"]\n"
       "   :avoid-when [\"deterministic work — use :code instead\"]\n"
       "   :summary \"One-paragraph synthesis.\"\n"
       "   :version 1\n"
       "   :consolidated-from-event-count 10}"))

(def ^:private reflection-workflow
  "Single-:llm-node ORC workflow for the consolidator's reflection call.

   The blackboard schemas for each :writes key drive dscloj's structured-
   output spec — that's how the LLM is told the per-field types. Each of
   the six top-level description-body fields is its own :writes slot with
   a precise schema; in particular :strengths/:weaknesses use the rich
   principle-entry schema so the LLM is told to produce maps with
   :trait/:good-when/:recommended-pattern/:confidence/:evidence-count/...
   fields, not just `:map`."
  (orc/workflow "ontology-consolidator-reflection"
    (orc/blackboard
      {:target-type [:enum :node-type :node-instance :tree-fingerprint :tree-class]
       :target-id :any
       :current-description [:maybe :map]
       :recent-events [:vector :map]
       :aggregate-metrics [:maybe :map]
       :recent-vs-historical-delta [:maybe :map]
       :structural-context :any
       :capabilities [:vector :string]
       :strengths [:vector ontology-schemas/principle-entry]
       :weaknesses [:vector ontology-schemas/principle-entry]
       :representative-uses [:vector :string]
       :avoid-when [:vector :string]
       :summary :string})

    (orc/llm "reflect"
      :instruction reflection-instruction
      :reads [:target-type :target-id :current-description
              :recent-events :aggregate-metrics
              :recent-vs-historical-delta :structural-context]
      :writes [:capabilities :strengths :weaknesses
               :representative-uses :avoid-when :summary]
      ;; Use the existing ORC/dscloj retry primitive — the executor's
      ;; :llm handler already retries on transient errors AND nil
      ;; outputs (executor.clj `outputs-have-nil?`). Lifting the budget
      ;; from the default 1 retry to 3 covers the LLM-flakiness we see
      ;; on first-consolidation runs without reinventing retry.
      :options {:max-retries 3
                :retry-delay-ms [500 1500 3000]})))

;; =============================================================================
;; Input gathering
;; =============================================================================

(defn- source-event-type-for-target
  "Which source event-type carries observations for a given target-type?"
  [target-type]
  (case target-type
    :node-type        :sheet/node-execution-completed
    :node-instance    :sheet/node-execution-completed
    :tree-fingerprint :sheet/rlm-tree-execution-completed
    :tree-class       :ontology/task-classified))

(defn- event-matches-target?
  "Filter predicate for an event against a (target-type, target-id) target."
  [target-type target-id event]
  (case target-type
    :node-type        (= target-id (:node-type event))
    :node-instance    (and (= (first target-id) (:sheet-id event))
                           (= (second target-id) (:node-id event)))
    :tree-fingerprint (= target-id (:tree-fingerprint event))
    :tree-class       (= target-id (:assigned-tree-id event))))

(defn- clean-event-for-llm
  "Strip non-JSON-serializable fields (event-store metadata, timestamps,
   tag sets) from a source event before it's passed to the LLM. Keeps
   only the semantic content the LLM needs to reason about — status,
   duration, target identity, usage. Timestamps are stringified."
  [event]
  (cond-> (-> event
              (dissoc :event/id :event/tags :event/timestamp))
    (some? (:event/timestamp event))
    (assoc :timestamp (str (:event/timestamp event)))))

(defn- gather-recent-tree-class-events
  "C-Loop-1 + Gap-3: for :tree-class targets, gather task-classified
   observations JOINED to their matching
   :sheet/rlm-tree-execution-completed events (by sheet-id) AND any
   :judge/score-emitted events for the same sheet+tick (Gap-3). Each
   output observation carries:
     - classification metadata at top-level (assigned-tree-id, confidence, ...)
     - :execution — submap with tree fingerprint, status, duration, usage,
       compact trajectory summary
     - :judge-scores — vector of per-judge {:judge-name :score :feedback ...}
       entries, joined by (sheet-id, tick-id) from :judge/score-emitted events.

   The judge-scores join lets the LLM weight judge-grounded signal
   alongside raw execution evidence (Gap-3 C-3 wiring).

   Per LIVING-DESCRIPTIONS.md's decoupled-threshold-and-window safeguard:
   capped at recent-window-size so a single bad burst doesn't reshape
   the description; aggregate metrics give the LLM the historical
   baseline to compare against."
  [ctx target-id]
  (let [task-classifieds (->> (es/read (:event-store ctx)
                                       {:types #{:ontology/task-classified}
                                        :tenant-id (:tenant-id ctx)})
                              (into [])
                              (filter #(= target-id (:assigned-tree-id %))))
        all-tree-executions (->> (es/read (:event-store ctx)
                                          {:types #{:sheet/rlm-tree-execution-completed}
                                           :tenant-id (:tenant-id ctx)})
                                 (into []))
        executions-by-sheet (into {}
                                  (map (fn [e] [(:sheet-id e) e]))
                                  all-tree-executions)
        ;; Gap-3: pull all :judge/score-emitted events, group by
        ;; [sheet-id tick-id] so we can attach per-observation.
        all-judge-scores (->> (es/read (:event-store ctx)
                                       {:types #{:judge/score-emitted}
                                        :tenant-id (:tenant-id ctx)})
                              (into []))
        judge-scores-by-sheet-tick (group-by (juxt :sheet-id :tick-id) all-judge-scores)
        joined (mapv (fn [tc]
                       (let [sheet-id (:source-sheet-id tc)
                             tick-id (:source-tick-id tc)
                             exec (get executions-by-sheet sheet-id)
                             judge-events (get judge-scores-by-sheet-tick [sheet-id tick-id])
                             cleaned-tc (clean-event-for-llm tc)]
                         (cond-> cleaned-tc
                           exec (assoc :execution
                                       (-> exec
                                           clean-event-for-llm
                                           (update :trajectory
                                                   (fn [traj]
                                                     (when (seq traj)
                                                       (mapv (fn [t]
                                                               (cond-> {:event-type (:event-type t)}
                                                                 (:status t) (assoc :status (:status t))))
                                                             traj))))))
                           (seq judge-events)
                           (assoc :judge-scores
                                  (mapv (fn [j]
                                          (select-keys j [:judge-name :judge-config
                                                          :score :feedback :dimensions
                                                          :emitted-at]))
                                        judge-events)))))
                     task-classifieds)]
    (vec (take-last recent-window-size joined))))

(defn- gather-recent-events
  "Pull the last W events for this target from the event store.
   Returns a vector of cleaned event maps (in event-store order, most
   recent last). Capped at recent-window-size.

   C-Loop-1: :tree-class targets use a join path that pairs
   task-classified observations with their execution outcomes — see
   gather-recent-tree-class-events."
  [ctx target-type target-id]
  (if (= :tree-class target-type)
    (gather-recent-tree-class-events ctx target-id)
    (let [event-type (source-event-type-for-target target-type)]
      (->> (es/read (:event-store ctx)
                    {:types #{event-type}
                     :tenant-id (:tenant-id ctx)})
           (into [])
           (filter #(event-matches-target? target-type target-id %))
           (map clean-event-for-llm)
           (take-last recent-window-size)
           vec))))

(defn- success-rate
  "Fraction of events with :status :success. nil for empty input."
  [events]
  (when (seq events)
    (/ (count (filter #(= :success (:status %)) events))
       (double (count events)))))

(defn- compute-delta
  "Compute recent-vs-historical comparison for the LLM. Returns a small
   map with :recent-success-rate, :historical-success-rate, and the
   :delta (recent minus historical). Returns nil when aggregate-metrics
   is unavailable (first consolidation) — the prompt then omits the
   delta section."
  [aggregate-metrics recent-events]
  (when (and aggregate-metrics (seq recent-events))
    (let [succ (:success-count aggregate-metrics)
          fail (:failure-count aggregate-metrics)
          total (+ (or succ 0) (or fail 0))]
      (when (pos? total)
        (let [historical (double (/ succ total))
              recent (success-rate recent-events)]
          {:recent-success-rate recent
           :historical-success-rate historical
           :delta (- recent historical)})))))

(defn- tree-class-aggregate-metrics
  "C-Loop-1: build the cross-observation baseline for a :tree-class
   target. Scans the event store for all task-classified events
   assigned to this class plus their joined execution outcomes.

   Returns:
     :total-assignments    — count of task-classified events
     :success-count        — executions completed with :success status
     :failure-count        — executions completed with :failure status
     :distinct-tree-shapes — count of distinct tree-fingerprints across
                              executions assigned to this class

   The LLM compares the recent-window's success rate against this
   baseline to grade whether a trend is consistent + substantial enough
   to update the description, per LIVING-DESCRIPTIONS.md's aggregate-
   plus-delta safeguard."
  [ctx target-id]
  (let [task-classifieds (->> (es/read (:event-store ctx)
                                       {:types #{:ontology/task-classified}
                                        :tenant-id (:tenant-id ctx)})
                              (into [])
                              (filter #(= target-id (:assigned-tree-id %))))
        sheet-ids (into #{} (map :source-sheet-id) task-classifieds)
        all-tree-executions (->> (es/read (:event-store ctx)
                                          {:types #{:sheet/rlm-tree-execution-completed}
                                           :tenant-id (:tenant-id ctx)})
                                 (into []))
        relevant-execs (filter #(contains? sheet-ids (:sheet-id %))
                               all-tree-executions)
        success-count (count (filter #(= :success (:status %)) relevant-execs))
        failure-count (count (filter #(= :failure (:status %)) relevant-execs))
        distinct-shapes (->> relevant-execs
                             (keep :tree-fingerprint)
                             distinct
                             count)
        ;; Gap-3: per-judge averages across observations for this
        ;; tree-class. Pulls all :judge/score-emitted events tagged with
        ;; sheets belonging to this class, groups by :judge-name, and
        ;; reports the mean score so the LLM can compare a recent window
        ;; against the stable baseline.
        relevant-judge-scores (->> (es/read (:event-store ctx)
                                            {:types #{:judge/score-emitted}
                                             :tenant-id (:tenant-id ctx)})
                                   (into [])
                                   (filter #(contains? sheet-ids (:sheet-id %))))
        judge-averages (when (seq relevant-judge-scores)
                         (into {}
                               (map (fn [[judge-name entries]]
                                      [judge-name
                                       (/ (reduce + 0.0 (map :score entries))
                                          (double (count entries)))]))
                               (group-by :judge-name relevant-judge-scores)))]
    (cond-> {:total-assignments (count task-classifieds)
             :success-count success-count
             :failure-count failure-count
             :distinct-tree-shapes distinct-shapes}
      judge-averages (assoc :judge-averages judge-averages))))

(defn- gather-aggregate-metrics
  "Pull accumulated metrics for this target from C-2a-2's cross-sheet
   rolling-metrics aggregators. Returns nil for granularities without
   a cross-sheet aggregator (currently :node-instance) — the LLM still
   gets the recent-events slice, just no aggregate baseline."
  [ctx target-type target-id]
  (case target-type
    :node-type        (orc/get-node-type-metrics ctx target-id)
    :tree-fingerprint (orc/get-tree-fingerprint-metrics ctx target-id)
    ;; :node-instance has no cross-sheet aggregator today (the legacy
    ;; per-(sheet, node-id) rolling-metrics is sheet-scoped). Could be
    ;; added in a follow-up — for now we pass nil and the LLM works
    ;; from recent-events + structural-context.
    :node-instance    nil
    ;; C-Loop-1: :tree-class aggregator scans the event store for the
    ;; class's task-classified events + their joined executions. See
    ;; tree-class-aggregate-metrics.
    :tree-class       (tree-class-aggregate-metrics ctx target-id)
    nil))

(defn- gather-structural-context
  "Pull structural context for this target (tree-raw / node config / kw)."
  [_ctx _target-type target-id]
  ;; Minimum impl for RED #1 — return target-id itself for node-types
  ;; (the keyword IS the structural context).
  target-id)

;; =============================================================================
;; Consolidation effect
;; =============================================================================

(defn- parse-vector-of-maps
  "dscloj returns simple-typed vector fields as native Clojure data, but
   vector-of-map fields (like :strengths / :weaknesses with their rich
   per-entry schema) sometimes arrive as EDN-or-JSON-encoded strings.
   Parse those back into native data. Pass already-parsed vectors through."
  [v]
  (cond
    (vector? v) v
    (sequential? v) (vec v)
    (not (string? v)) v
    (str/blank? v) []
    :else
    (try
      ;; Try EDN first — the LLM more often produces Clojure-syntax output
      ;; under our prompt; JSON is the fallback.
      (let [parsed (read-string v)]
        (if (sequential? parsed) (vec parsed) v))
      (catch Exception _
        (try
          (json/parse-string v true)
          (catch Exception _ v))))))

(defn- record-description-command [target-type target-id body]
  (let [cmd-name (case target-type
                   :node-type        :ontology/record-node-type-description
                   :node-instance    :ontology/record-node-instance-description
                   :tree-fingerprint :ontology/record-tree-description
                   :tree-class       :ontology/record-tree-class-description)]
    {:command/name cmd-name
     :command/id (random-uuid)
     :command/timestamp (time/now)
     :target-id target-id
     :body body}))

(defn- next-version [current-description]
  (if current-description
    (inc (or (:version current-description) 0))
    1))

;; =============================================================================
;; Gap-6 — anti-recency runtime validator
;; =============================================================================
;;
;; The reflection-instruction prompt asks the LLM to update confidences
;; gradually + never erase strong principles on one bad burst. Gap-6
;; backs that ask with runtime code. The validator runs post-LLM,
;; pre-emission. For each strength/weakness entry in the PRIOR body
;; that is "protected" (high confidence + high evidence-count), the
;; validator:
;;   - REJECTS the new body if the entry is missing entirely
;;   - CLAMPS the new entry's confidence if it dropped > max-decrease
;;
;; All comparisons match by :trait field (the actionable handle).
;; First consolidation (prior body nil) passes through untouched —
;; nothing to protect yet.

(def ^:private anti-recency-defaults
  "Tunable thresholds with spec defaults. A future event-sourced config
   slice will let operators override these via :ontology/set-anti-
   recency-config; for Gap-6's tracer-bullet slices the defaults apply."
  {:protected-confidence-threshold 0.7
   :protected-evidence-count-threshold 5
   :max-confidence-decrease-per-cycle 0.2})

(defn- protected-entry?
  "An entry from the prior body is PROTECTED when its confidence + evidence
   passed the configured floors. Only protected entries trigger validator
   reject/clamp behavior — low-evidence speculative entries are free to
   churn."
  [entry {:keys [protected-confidence-threshold
                 protected-evidence-count-threshold]}]
  (and (number? (:confidence entry))
       (number? (:evidence-count entry))
       (>= (:confidence entry) protected-confidence-threshold)
       (>= (:evidence-count entry) protected-evidence-count-threshold)))

(defn- find-by-trait
  "Locate an entry in a vector of {:trait ...} maps. The :trait field is
   the actionable handle the consolidator/LLM author against — we match
   structurally on its string value, not on any positional/identity
   relation."
  [entries trait]
  (first (filter #(= trait (:trait %)) entries)))

(defn- clamp-entry-in-bucket
  "Return the body with the entry whose :trait matches `trait` (in
   bucket :strengths or :weaknesses) updated to carry `clamped-confidence`
   instead of its current :confidence. Other fields untouched."
  [body bucket trait clamped-confidence]
  (update body bucket
          (fn [entries]
            (mapv (fn [e]
                    (if (= trait (:trait e))
                      (assoc e :confidence clamped-confidence)
                      e))
                  entries))))

(defn- anti-recency-validate
  "Validate that the new body doesn't regress protected entries from
   the prior body. Returns {:decision <:ok | :reject | :clamp>
   :body <body> :audit [<entry>...]}.

   For every protected entry (high :confidence + high :evidence-count)
   in the prior body:
     - MISSING from new body → :reject the new body; do not emit.
     - confidence decreased by more than max-confidence-decrease-per-cycle
       → :clamp the new entry's confidence to prior - max-decrease, and
         continue.
   Otherwise pass through unchanged."
  [prior-body new-body config]
  (let [cfg (merge anti-recency-defaults config)
        max-decrease (:max-confidence-decrease-per-cycle cfg)]
    (if (nil? prior-body)
      {:decision :ok :body new-body :audit []}
      (let [audit (atom [])
            body-atom (atom new-body)]
        (doseq [bucket [:strengths :weaknesses]]
          (doseq [prior-entry (get prior-body bucket)]
            (when (protected-entry? prior-entry cfg)
              (let [trait (:trait prior-entry)
                    new-entry (find-by-trait (get @body-atom bucket) trait)]
                (cond
                  (nil? new-entry)
                  (swap! audit conj
                         {:event-kind :rejection
                          :bucket bucket
                          :entry-trait trait
                          :prior-confidence (:confidence prior-entry)
                          :prior-evidence-count (:evidence-count prior-entry)
                          :reason :missing-protected-entry})

                  (and (number? (:confidence new-entry))
                       (> (- (:confidence prior-entry) (:confidence new-entry))
                          max-decrease))
                  (let [prior-conf (:confidence prior-entry)
                        llm-conf (:confidence new-entry)
                        clamped (- prior-conf max-decrease)]
                    (swap! body-atom clamp-entry-in-bucket bucket trait clamped)
                    (swap! audit conj
                           {:event-kind :clamp
                            :bucket bucket
                            :entry-trait trait
                            :prior-confidence prior-conf
                            :llm-confidence llm-conf
                            :clamped-confidence clamped
                            :reason :confidence-decrease-exceeded-max})))))))
        (let [audit-vec @audit
              final-body @body-atom]
          (cond
            (some #(= :rejection (:event-kind %)) audit-vec)
            {:decision :reject :body final-body :audit audit-vec}

            (some #(= :clamp (:event-kind %)) audit-vec)
            {:decision :clamp :body final-body :audit audit-vec}

            :else
            {:decision :ok :body final-body :audit audit-vec}))))))

;; =============================================================================
;; C-2d-2 — parent-tree-id hydration
;;
;; When the consolidator emits a new tree-fingerprint description, the
;; body should carry :parent-tree-id so the C-2d-1 reactive projector can
;; wire the new tree-class as a child of an abstract parent in the
;; concept graph.
;;
;; Three cases (per C-2d Decision 5):
;;   - non-tree-fingerprint target → body unchanged
;;   - sticky (subsequent consolidation): preserve existing :parent-tree-id
;;     from the prior description; classify-task NOT called
;;   - first-time (no prior description): call classify-task with
;;     :walk-down? false to infer the top-1 abstract parent. If match,
;;     assoc :parent-tree-id. If fresh-mint, omit :parent-tree-id and log
;;     ::orphan-tree-class-created for HITL surfacing.
;; =============================================================================

(def ^:private parent-inference-threshold
  "Confidence threshold used when asking classify-task to identify the
   abstract parent of a brand-new tree-fingerprint. Mirrors the
   :auto-classify-threshold default from the C-2c-2 wedge — one knob
   to tune for both call sites."
  0.7)

(defn- build-parent-inference-signature
  "Build a task-signature for the consolidator's parent-inference call
   from the just-consolidated description body. Uses :summary,
   :capabilities, and :representative-uses so the classifier sees what
   the tree IS rather than what produced it."
  [body]
  (str "TREE SUMMARY:\n"
       (or (:summary body) "(none)")
       "\n\nCAPABILITIES:\n"
       (str/join "\n" (map #(str "- " %) (or (:capabilities body) [])))
       "\n\nREPRESENTATIVE USES:\n"
       (str/join "\n" (map #(str "- " %) (or (:representative-uses body) [])))))

;; =============================================================================
;; R05d — behavioral-subtree-ids hydration
;;
;; Parallel to maybe-hydrate-parent-tree-id but on the BEHAVIORAL axis.
;; Same three cases:
;;   - non-tree-fingerprint target → body unchanged
;;   - sticky (prior :behavioral-subtree-ids on current description) →
;;     preserve them; classify-behaviors NOT called
;;   - first-time → classify-behaviors with :structural-context = the
;;     tree-class id. Top-N above-threshold IDs assoc'd as
;;     :behavioral-subtree-ids. Fresh-mint marker → omit + log
;;     ::orphan-behavioral-subtree-inferred
;;
;; The composes-into edge growth (dispatch-observed-composes-into-edges!)
;; is a separate post-emit step.
;; =============================================================================

(def ^:private behavioral-inference-threshold
  "Confidence threshold for the consolidator's behavioral inference call.
   Mirrors the classify-behaviors default. Distinct from
   parent-inference-threshold so the two retrieval surfaces can be tuned
   independently as the corpus matures."
  0.7)

(def ^:private behavioral-inference-top-n
  "How many top behaviors to stamp on the body. Three keeps the
   downstream display compact while giving the RLM researcher enough
   examples to triangulate."
  3)

(defn maybe-hydrate-behavioral-subtree-ids
  "Return `body` possibly with :behavioral-subtree-ids assoc'ed per the
   R05d rules. Pure-effects-aside: when first-time inference runs, it
   calls the classify-behaviors var (which talks to the corpus +
   reranker). When classify-behaviors's top-1 is the fresh-mint marker,
   an orphan log is emitted.

   Public for test access; callers within the consolidator's body
   assembly are the only production users."
  [context target-type target-id current-description body]
  (cond
    (not= target-type :tree-fingerprint)
    body

    (seq (:behavioral-subtree-ids current-description))
    (assoc body :behavioral-subtree-ids (:behavioral-subtree-ids current-description))

    (nil? current-description)
    (let [classify-behaviors (requiring-resolve
                               'ai.obney.orc.ontology.interface/classify-behaviors)
          signature (build-parent-inference-signature body)
          result (try
                   (classify-behaviors context
                                       {:task-signature signature
                                        :threshold behavioral-inference-threshold
                                        :structural-context target-id
                                        :top-n behavioral-inference-top-n})
                   (catch Exception e
                     (u/log ::behavioral-inference-error
                            :target-id target-id
                            :error (.getMessage e))
                     nil))
          behaviors (when result (:behaviors result))
          top-1 (first behaviors)
          fresh-mint? (or (nil? top-1) (:was-fresh-mint? top-1))]
      (if (or (nil? result) fresh-mint?)
        (do (u/log ::orphan-behavioral-subtree-inferred
                   :target-id target-id
                   :reason (cond
                             (nil? result) :inference-error
                             fresh-mint?   :fresh-mint
                             :else         :below-threshold))
            body)
        (do (u/log ::behavioral-subtree-ids-inferred
                   :target-id target-id
                   :count (count behaviors))
            (assoc body
                   :behavioral-subtree-ids
                   (mapv :behavior-id behaviors)))))

    :else body))

(defn dispatch-observed-composes-into-edges!
  "R05d: for each behavioral-subtree id the consolidator inferred for
   this tree-fingerprint, dispatch :ontology/create-relationship with
   predicate \"behavior:composes-into\" when the edge is not already
   present in the concept graph. Sticky once added (the read-model's
   :composes-into set is conj-only); the no-op skip makes repeat calls
   safe.

   No-op when `behavior-ids` is nil or empty.

   Public for test access; the consolidator calls this after the
   description-updated event lands."
  [context shell-id behavior-ids]
  (when (seq behavior-ids)
    (let [shell-uri (str "tree-class:" shell-id)
          concepts (rmp/project context :ontology/concepts)]
      (doseq [behavior-id behavior-ids]
        (let [behavior-uri (str "behavioral-subtree:" behavior-id)
              already-linked? (contains? (get-in concepts [behavior-uri :composes-into])
                                          shell-uri)]
          (when-not already-linked?
            (command-processor/process-command
              (assoc context :command
                     {:command/name :ontology/create-relationship
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :source-uri behavior-uri
                      :target-uri shell-uri
                      :predicate "behavior:composes-into"
                      :properties {}}))))))))

(defn maybe-hydrate-parent-tree-id
  "Return `body` possibly with :parent-tree-id assoc'ed per the C-2d-2
   rules. Pure-effects-aside: when first-time inference runs, it calls
   the classify-task var (which talks to the corpus + reranker). When
   classify-task returns fresh-mint, an orphan log is emitted.

   Public for test access; callers within the consolidator's body
   assembly are the only production users."
  [context target-type target-id current-description body]
  (cond
    (not= target-type :tree-fingerprint)
    body

    (some? (:parent-tree-id current-description))
    (assoc body :parent-tree-id (:parent-tree-id current-description))

    (nil? current-description)
    (let [classify-task (requiring-resolve
                          'ai.obney.orc.ontology.interface/classify-task)
          signature (build-parent-inference-signature body)
          result (try
                   (classify-task context
                                  {:task-signature signature
                                   :threshold parent-inference-threshold
                                   :walk-down? false})
                   (catch Exception e
                     (u/log ::parent-inference-error
                            :target-id target-id
                            :error (.getMessage e))
                     nil))]
      (if (and result (not (:was-fresh-mint? result)))
        (do (u/log ::parent-tree-class-inferred
                   :target-id target-id
                   :parent-tree-id (:assigned-tree-id result)
                   :confidence (:confidence result))
            (assoc body :parent-tree-id (:assigned-tree-id result)))
        (do (u/log ::orphan-tree-class-created
                   :target-id target-id
                   :confidence (when result (:confidence result))
                   :reason (cond
                             (nil? result)               :inference-error
                             (:was-fresh-mint? result)   :fresh-mint
                             :else                       :below-threshold))
            body)))

    :else body))

(defn- consolidate!-inner
  "The actual consolidation body — split out so the public consolidate!
   can do a clean budget-gate check before kicking off the LLM workflow."
  [context target-type target-id]
  (let [current-description (ontology/get-description context target-type target-id)
        recent-events (gather-recent-events context target-type target-id)
        aggregate-metrics (gather-aggregate-metrics context target-type target-id)
        recent-vs-historical-delta (compute-delta aggregate-metrics recent-events)
        structural-context (gather-structural-context context target-type target-id)
        sheet-id (orc/build-workflow! context reflection-workflow)
        exec-result (orc/execute context sheet-id
                                  {:target-type target-type
                                   :target-id target-id
                                   :current-description current-description
                                   :recent-events recent-events
                                   :aggregate-metrics aggregate-metrics
                                   :recent-vs-historical-delta recent-vs-historical-delta
                                   :structural-context structural-context})
        outputs (:outputs exec-result)
        ;; Assemble the description-body from the six separate :writes
        ;; produced by the LLM. dscloj returns simple-vector fields
        ;; (:capabilities, :representative-uses, :avoid-when) as native
        ;; Clojure data, but complex vector-of-map fields (:strengths,
        ;; :weaknesses) sometimes arrive as EDN/JSON-encoded strings —
        ;; we parse those before assembling.
        raw-body (when (and outputs
                            (every? #(contains? outputs %)
                                    [:capabilities :strengths :weaknesses
                                     :representative-uses :avoid-when :summary]))
                   {:capabilities                  (:capabilities outputs)
                    :strengths                     (parse-vector-of-maps (:strengths outputs))
                    :weaknesses                    (parse-vector-of-maps (:weaknesses outputs))
                    :representative-uses           (:representative-uses outputs)
                    :avoid-when                    (:avoid-when outputs)
                    :summary                       (:summary outputs)
                    :version                       (next-version current-description)
                    :consolidated-from-event-count (count recent-events)})
        ;; C-2d-2: hydrate :parent-tree-id when applicable. Sticky for
        ;; subsequent consolidations; classify-task on first-time;
        ;; passthrough for non-tree-fingerprint targets.
        body (when raw-body
               (maybe-hydrate-parent-tree-id context target-type target-id
                                              current-description raw-body))
        ;; R05d: hydrate :behavioral-subtree-ids on the same body. Same
        ;; sticky/first-time/orphan/passthrough rules; runs after
        ;; :parent-tree-id so both retrieval axes appear on the emitted
        ;; description.
        body (when body
               (maybe-hydrate-behavioral-subtree-ids context target-type target-id
                                                      current-description body))]
    (cond
      (not= :success (:status exec-result))
      (u/log ::consolidate-execution-failed
             :target-type target-type
             :target-id target-id
             :status (:status exec-result)
             :error (:error exec-result))

      (not (m/validate ontology-schemas/description-body body))
      (u/log ::consolidate-validation-failed
             :target-type target-type
             :target-id target-id
             :explain (me/humanize (m/explain ontology-schemas/description-body body)))

      :else
      (let [;; Gap-6: post-LLM, pre-emission anti-recency validator.
            ;; Compares this body against the prior body and rejects
            ;; (or clamps) regressions of high-confidence + high-
            ;; evidence-count entries. See `anti-recency-validate`.
            validation (anti-recency-validate current-description body {})
            decision (:decision validation)
            final-body (:body validation)]
        (cond
          (= :reject decision)
          (do
            (doseq [audit-entry (:audit validation)]
              (when (= :rejection (:event-kind audit-entry))
                (command-processor/process-command
                  (assoc context :command
                         {:command/name :ontology/record-anti-recency-rejection
                          :command/id (random-uuid)
                          :command/timestamp (time/now)
                          :target-type target-type
                          :target-id target-id
                          :bucket (:bucket audit-entry)
                          :entry-trait (:entry-trait audit-entry)
                          :prior-confidence (:prior-confidence audit-entry)
                          :prior-evidence-count (:prior-evidence-count audit-entry)
                          :reason (:reason audit-entry)
                          :rejected-body body}))))
            (u/log ::anti-recency-rejection
                   :target-type target-type
                   :target-id target-id
                   :audit-entry-count (count (:audit validation))))

          :else
          (do
            (when (= :clamp decision)
              (doseq [audit-entry (:audit validation)]
                (when (= :clamp (:event-kind audit-entry))
                  (command-processor/process-command
                    (assoc context :command
                           {:command/name :ontology/record-anti-recency-clamp
                            :command/id (random-uuid)
                            :command/timestamp (time/now)
                            :target-type target-type
                            :target-id target-id
                            :bucket (:bucket audit-entry)
                            :entry-trait (:entry-trait audit-entry)
                            :prior-confidence (:prior-confidence audit-entry)
                            :llm-confidence (:llm-confidence audit-entry)
                            :clamped-confidence (:clamped-confidence audit-entry)
                            :reason (:reason audit-entry)}))))
              (u/log ::anti-recency-clamp
                     :target-type target-type
                     :target-id target-id
                     :clamp-entry-count (count (:audit validation))))
            (command-processor/process-command
              (assoc context :command (record-description-command target-type target-id final-body)))
            ;; R05d: after the description-updated event lands, grow the
            ;; behavior:composes-into graph for any newly-observed (behavior
            ;; → shell) pairs. Sticky / idempotent — re-running on the same
            ;; pair is a no-op.
            (when (= :tree-fingerprint target-type)
              (dispatch-observed-composes-into-edges!
                context target-id (:behavioral-subtree-ids final-body)))))))))

(defn consolidate!
  "Run the consolidation for a single (target-type, target-id) target.

   Budget gate: if the configured hourly consolidation budget for this
   target-type has been exhausted (per the rolling-hour count of
   :*-description-updated events), the consolidation is skipped (no LLM
   call, no event emitted). The hour window rolls naturally; subsequent
   requests succeed once older entries fall out of the window.

   Otherwise: reads inputs, executes the reflection workflow via ORC,
   validates the structured output against the description-body schema,
   overwrites :version + :consolidated-from-event-count with computed
   values, and emits the matching :*-description-updated event via the
   existing C-2a-1 record-*-description command.

   Validation failure: logs and aborts cleanly (no event emitted)."
  [context target-type target-id]
  (u/log ::consolidate-start :target-type target-type :target-id target-id)
  (let [budget (ontology/get-consolidation-budget context target-type)
        recent-count (ontology/get-recent-consolidation-count context target-type)]
    (if (>= recent-count budget)
      (u/log ::consolidate-budget-exceeded
             :target-type target-type
             :target-id target-id
             :budget budget
             :recent-count recent-count)
      (consolidate!-inner context target-type target-id))))

;; =============================================================================
;; Processor registration
;; =============================================================================

(defprocessor :ontology consolidate-on-request
  {:topics #{:ontology/consolidation-requested}}
  "C-2a-3b: handle :ontology/consolidation-requested by running the
   reflection LLM call and emitting the matching :*-description-updated."
  [{:keys [event] :as context}]
  (let [{:keys [target-type target-id]} event]
    (try
      (consolidate! context target-type target-id)
      (catch Exception e
        (u/log ::consolidate-error
               :error (.getMessage e)
               :target-type target-type
               :target-id target-id)))))
