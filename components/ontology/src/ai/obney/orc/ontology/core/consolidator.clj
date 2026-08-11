(ns ai.obney.orc.ontology.core.consolidator
  "Living Description consolidator.

   Subscribes to :ontology/consolidation-requested events. For each request,
   gathers the target's recent events + accumulated metrics + structural
   context and runs a single structured-output LLM reflection call.

   TWO WRITE PATHS, AND THEY NEVER SHARE A TARGET (CC-5, ADR 0021).

   1. THE CLAIM PATH — `:tree-class` targets. The reflection call is handed the
      target's NUMBERED CLAIM SET plus the guarded evidence window, and it
      returns OPERATIONS over that set (`:add` / `:support` / `:contradict` /
      `:edit`), never a replacement body. The operations are validated,
      collapsed per target claim, given their episodes IN CODE, and dispatched
      as `:ontology/record-claim-deltas`. The body is then ASSEMBLED from the
      claims by CC-3's fold. No whole-body description event is emitted for a
      claim-path target, so the assembled body has exactly ONE writer.

   2. THE LEGACY BODY PATH — `:node-type`, `:node-instance` and
      `:tree-fingerprint`. These granularities cannot join the claim model yet
      (see `claim-path-target-type?` for the two concrete blockers), so they
      still emit `:*-description-updated`. They have no claim path at all, so
      their `:current` slot also has exactly one writer.

   THE ANTI-RECENCY RUNTIME VALIDATOR IS DELETED (ADR 0021). It rejected a
   consolidation whenever a high-confidence prior entry was missing from the
   new body, matched by `:trait` STRING — so any rephrasing of a protected
   insight read as an erasure. On the real corpus it rejected 145 of 145
   consolidations and roughly nine in ten of those rejections were wrong: the
   insight was present, reworded. Protection-induced starvation is what the
   delta contract makes structurally impossible, which is why the valve is
   removed rather than tuned. The prompt-level anti-recency FRAMING stays on
   the legacy body path, where whole-body rewriting is still how that path
   works and gradual movement is still the right instruction."
  (:require [malli.core :as m]
            [malli.error :as me]
            [cheshire.core :as json]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.ontology.core.evidence-projection :as evidence-projection]
            [ai.obney.orc.ontology.core.read-models :as read-models]
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

(def ^:private current-description-schema
  [:map
   [:summary {:optional true} :string]
   [:capabilities {:optional true} [:vector :string]]
   [:version {:optional true} :int]])

(def ^:private recent-event-schema
  [:map
   [:event/type {:optional true} :keyword]
   [:status {:optional true} :keyword]
   [:confidence {:optional true} :double]])

(def ^:private aggregate-metrics-schema
  [:map
   [:success-count {:optional true} :int]
   [:failure-count {:optional true} :int]
   [:total-count {:optional true} :int]
   [:success-rate {:optional true} :double]])

(def ^:private history-delta-schema
  [:map
   [:recent-success-rate :double]
   [:historical-success-rate :double]
   [:delta :double]])

(def ^:private target-identity-schema
  [:or :keyword [:tuple :uuid :uuid] :uuid :string])

(def ^:private reflection-max-retries
  "The executor-level retry budget BOTH reflection workflows configure
   (see the :options on their :llm nodes). Named because CC-28's failure
   accounting derives the terminal attempt count from it: the executor's
   exception path only escapes after exhausting exactly this budget, so
   a terminal exception-class failure consumed (inc reflection-max-retries)
   provider attempts."
  3)

(defn classify-reflection-failure
  "CC-28 (FailureIsVisible) — map a terminal non-:success exec-result from
   a reflection workflow to {:reason <closed-class> :attempts <count>}.

   PURE, and public for test access. The reason classes are the CLOSED set
   `ontology-schemas/consolidation-failure-reason`; the matching here is a
   heuristic over the executor's terminal shapes, but what it PRODUCES is
   always one of the four contract classes:

     :timeout           — the exec-result's own :status. The executor does
       not surface which attempt the deadline died on, so :attempts is the
       certain floor of 1.
     :unparseable       — the executor's nil-extraction terminal, matched
       on its verbatim error prefix (\"LLM output unparseable for keys\",
       executor.clj). The provider ANSWERED — one terminal attempt's output
       could not be extracted — so :attempts is the floor of 1.
     :provider-rejected — the provider refused the call outright: token/
       context-length caps, invalid-request shapes. The executor retries
       these like any exception (it cannot tell permanent from transient),
       so the full budget was consumed: (inc reflection-max-retries).
     :retries-exhausted — every other exception-terminal failure: the
       provider kept erroring until the retry budget ran out. Also the
       fallback for any unrecognized error string — 'died for a reason we
       could not classify' is still 'died', and the closed set means an
       unknown shape maps to the honest superclass rather than minting a
       new one. Attempts: (inc reflection-max-retries), exact — the
       exception path only escapes after exhausting the budget."
  [{:keys [status error]}]
  (let [error-str (some-> error str)]
    (cond
      (= :timeout status)
      {:reason :timeout :attempts 1}

      (and error-str (re-find #"LLM output unparseable" error-str))
      {:reason :unparseable :attempts 1}

      (and error-str
           (re-find #"(?i)too long|too large|context.{0,8}length|maximum context|token limit|exceeds?.{0,24}(maximum|limit)|invalid_request"
                    error-str))
      {:reason :provider-rejected :attempts (inc reflection-max-retries)}

      :else
      {:reason :retries-exhausted :attempts (inc reflection-max-retries)})))

(defn- record-consolidation-failure!
  "Emit the durable death certificate for a terminally failed reflection —
   ONE :ontology/description-consolidation-failed event for the whole
   attempt-set, dispatched through the command processor exactly as the
   success paths dispatch their writes. Also keeps the pre-CC-28 log line
   so operators' existing ::consolidate-execution-failed searches keep
   working."
  [context target-type target-id exec-result]
  (let [{:keys [reason attempts]} (classify-reflection-failure exec-result)]
    (u/log ::consolidate-execution-failed
           :target-type target-type :target-id target-id
           :status (:status exec-result) :error (:error exec-result)
           :reason reason :attempts attempts)
    (command-processor/process-command
      (assoc context :command
             (cond-> {:command/name :ontology/record-consolidation-failure
                      :command/id (random-uuid)
                      :command/timestamp (time/now)
                      :granularity target-type
                      :target-identifier target-id
                      :reason reason
                      :attempts attempts}
               (some? (:error exec-result))
               (assoc :error (str (:error exec-result))))))))

(defn- execute-reflection
  "Run the reflection workflow, converting a THROW from the execute call
   itself into the same terminal shape the executor returns for its own
   failures — so both reflection paths have exactly ONE failure seam and
   a thrown reflection cannot slip past FailureIsVisible into the
   processor's catch-all log line."
  [context sheet-id inputs]
  (try
    (orc/execute context sheet-id inputs)
    (catch Exception e
      {:status :failure :error (.getMessage e)})))

(defn- reflection-workflow
  "Single-:llm-node ORC workflow for the consolidator's reflection call.

   The blackboard schemas for each :writes key drive llm's structured-
   output spec — that's how the LLM is told the per-field types. Each of
   the six top-level description-body fields is its own :writes slot with
   a precise schema; in particular :strengths/:weaknesses use the rich
   principle-entry schema so the LLM is told to produce maps with
   :trait/:good-when/:recommended-pattern/:confidence/:evidence-count/...
   fields, not just `:map`."
  [model]
  (orc/workflow "ontology-consolidator-reflection"
    (orc/blackboard
      {:target-type [:enum :node-type :node-instance :tree-fingerprint :tree-class]
       ;; Target identity is intentionally polymorphic by granularity:
       ;; node types are keywords, node instances are [sheet-id node-id]
       ;; tuples, and tree targets are UUIDs or strings.
       :target-id target-identity-schema
       :current-description [:maybe current-description-schema]
       :recent-events [:vector recent-event-schema]
       :aggregate-metrics [:maybe aggregate-metrics-schema]
       :recent-vs-historical-delta [:maybe history-delta-schema]
       :structural-context [:maybe target-identity-schema]
       :capabilities [:vector :string]
       :strengths [:vector ontology-schemas/principle-entry]
       :weaknesses [:vector ontology-schemas/principle-entry]
       :representative-uses [:vector :string]
       :avoid-when [:vector :string]
       :summary :string})

    (cond->
      (orc/llm "reflect"
        :instruction reflection-instruction
        :reads [:target-type :target-id :current-description
                :recent-events :aggregate-metrics
                :recent-vs-historical-delta :structural-context]
        :writes [:capabilities :strengths :weaknesses
                 :representative-uses :avoid-when :summary]
        ;; Use the existing ORC/llm retry primitive — the executor's
        ;; :llm handler already retries on transient errors AND nil
        ;; outputs (executor.clj `outputs-have-nil?`). Lifting the budget
        ;; from the default 1 retry to 3 covers the LLM-flakiness we see
        ;; on first-consolidation runs without reinventing retry.
        :options {:max-retries reflection-max-retries
                  :retry-delay-ms [500 1500 3000]})
      model (assoc :model model))))

;; =============================================================================
;; CC-5 (ADR 0021) — the CLAIM reflection: numbered claim set in, operations out
;; =============================================================================
;;
;; The prompt below is not a rewrite of the body prompt. Its four load-bearing
;; elements were each MEASURED by the CC-5 prototype (P-A) against the real
;; judges'-slot model at temperature 0 over 20 real consolidations reconstructed
;; from the production rejection corpus:
;;
;;   1. A TWO-STEP PROCEDURE WITH STEP 1 MANDATORY. Walking the numbered claim
;;      set in order and asking, per claim, whether the evidence bears on it —
;;      and saying out loud that reinforcing a supported claim is not optional —
;;      moved the share of pool claims that received any operation from 12.7% to
;;      31.4%. The improvement was de-confounded against completion budget: the
;;      old prompt re-run at the higher budget produced 16 operations where it
;;      had produced 15, while this procedure produced 37 on the same rows.
;;   2. ONE LINE PER CLAIM, QUOTED, IN THE DELTA'S OWN VOCABULARY. Three of the
;;      four observed failure shapes were rendering artifacts, not reasoning
;;      failures: indented `guard:` / `recommendation:` continuation lines bled
;;      into `:content` (7 occurrences) and bracket kind labels bled in (3).
;;      Flattening the pool to one quoted line per claim eliminated all of them.
;;   3. THE PERMITTED KEY SET NAMED EXPLICITLY, and `:content` stated to be one
;;      line of plain prose.
;;   4. THE MODEL IS NEVER ASKED FOR `:episodes` OR `:from-legacy-corpus`. Those
;;      are filled in code from the evidence window. Across 178 operations the
;;      model copied claim ids out of pools of up to 25 with ZERO fabrications;
;;      asking it to also invent occurrence uuid pairs is how that number stops
;;      being zero.
;;
;; THE WHOLE CLAIM SET IS SHOWN. No pre-filter. The only pre-computable
;; narrowing — claim/evidence lexical containment — destroyed 30 of the 35
;; claims the model actually used and emptied the pool on up to 13 of 20 real
;; consolidations, because claim language is abstract ("modifies source code
;; files") while evidence is concrete event records. That is the ColBERT
;; refutation one layer up. It also buys nothing: the pool listing is 24-43% of
;; the prompt (7-8% on the large rows) and the evidence window dominates.
;; Revisit above ~40 claims, and then narrow by recency or support, never by
;; overlap.

(def ^:private claim-operation-proposal
  "What ONE reflection operation may look like ON THE WIRE.

   Deliberately a SUPERSET-tolerant shape rather than `claim-delta` itself:
   `:kind` is optional here because a `:support` / `:contradict` / `:edit`
   operation names a claim that already HAS a kind, and demanding the model
   restate it is one more field to get wrong. `prepare-operations` fills it from
   the named claim before anything is dispatched.

   `:episodes` and `:from-legacy-corpus` are absent BY DESIGN — see element 4
   above."
  [:map
   [:operation      [:enum :add :support :contradict :edit]]
   [:target-claim   {:optional true} [:maybe :string]]
   [:kind           {:optional true} [:maybe [:enum :capability :strength :weakness
                                              :guard :representative-use]]]
   ;; OPTIONAL, and that is a LIVE-QA finding rather than a design preference.
   ;; Asked to `:support` claim #1, the real model answers
   ;; `{:operation "support" :target-claim "…#0"}` and nothing else — which is
   ;; correct behaviour: a support restates nothing. Requiring `:content` here
   ;; rejected every reinforcement the model produced, and the stubbed contract
   ;; tests could not see it because their stubs always supply one.
   ;; `prepare-operations` fills it from the named claim; `:add` and `:edit`
   ;; still have to carry their own.
   [:content        {:optional true} [:maybe :string]]
   [:context-guard  {:optional true} [:maybe :string]]
   [:recommendation {:optional true} [:maybe :string]]])

(def ^:private claim-reflection-instruction
  (str
    "You are updating an accumulated set of CLAIMS about a behavior-tree "
    "component from newly observed execution evidence.\n\n"

    "You are NOT writing a description. You do not rewrite, summarise or "
    "replace anything. You emit a list of OPERATIONS over the numbered claim "
    "set you are given, and deterministic code applies them. A claim you say "
    "nothing about survives unchanged.\n\n"

    "INPUTS:\n"
    "- :claim-set — the target's CURRENT claims, numbered, one per line. Each "
    "line gives the claim's id, its kind, the support it has accumulated, and "
    "its content in quotes. The id is the string you must copy verbatim into "
    ":target-claim.\n"
    "- :recent-events — the observation window this consolidation reasons over. "
    "Each observation may carry :judge-scores (:judge-name, :score 0.0-1.0, "
    ":feedback) from judges attached to the host node.\n"
    "- :aggregate-metrics — accumulated metrics across this target's lifetime, "
    "the STABLE BASELINE. May include :judge-averages, the mean score per judge "
    "across the whole lifetime.\n"
    "- :recent-vs-historical-delta — the computed gap between the recent window "
    "and the historical baseline, or nil on a first consolidation.\n"
    "- :target-type / :target-id / :structural-context — what the target IS.\n\n"

    "FOLLOW THIS PROCEDURE IN ORDER.\n\n"

    "STEP 1 — MANDATORY, AND YOU MUST DO IT BEFORE STEP 2. Walk the claim set "
    "from claim 1 to the last claim, IN ORDER, and for EACH one ask: does this "
    "evidence window bear on this claim?\n"
    "  - The evidence CORROBORATES it -> emit :support naming its id.\n"
    "  - The evidence corroborates it AND you can state it more precisely, or "
    "it belongs to a different kind now -> emit :edit naming its id, with the "
    "improved :content (and the new :kind if it moved).\n"
    "  - The evidence CONTRADICTS it -> emit :contradict naming its id.\n"
    "  - The evidence says nothing about it -> emit nothing for it.\n"
    "REINFORCING A CLAIM THE EVIDENCE SUPPORTS IS NOT OPTIONAL. Support is how "
    "a claim earns the right to act; a claim that keeps being true and keeps "
    "being passed over in silence decays relative to the ones that are named. "
    "Do not skip a claim because it is 'already obvious' or 'already known' — "
    "if this window shows it again, say so.\n\n"

    "STEP 2 — only now, look for insights in the evidence that NO existing "
    "claim covers. Before proposing one, check it against EVERY claim in the "
    "set, including claims of a different kind: an insight is frequently the "
    "same knowledge as an existing claim wearing different words, and it is far "
    "better to :edit an existing claim than to :add a near-duplicate. Only when "
    "an insight matches nothing in the set, emit :add.\n\n"

    "OPERATIONS — the permitted keys, and NOTHING ELSE:\n"
    "  :operation      — one of :add, :support, :contradict, :edit\n"
    "  :target-claim   — the claim id copied VERBATIM from the claim set. "
    "Required for :support, :contradict and :edit. Omit it for :add.\n"
    "  :kind           — one of :capability, :strength, :weakness, :guard, "
    ":representative-use. Required for :add. Supply it on :edit only when the "
    "claim's kind is changing.\n"
    "  :content        — ONE LINE of plain prose stating the claim itself. Not "
    "a label, not a heading, not a bracketed tag, not a multi-line block, and "
    "never a continuation of another field. Required for :add and :edit.\n"
    "  :context-guard  — optional, one line: when this claim does NOT apply.\n"
    "  :recommendation — optional, one line: what to do about it.\n"
    "Any other key is REJECTED and its operation is discarded.\n\n"

    "Do NOT invent occurrence ids, episode references, event ids, timestamps, "
    "confidences or evidence counts. Those are supplied by the system from the "
    "evidence window. You have no way to know them and a guess is worse than "
    "an omission.\n\n"

    ":content must be a concrete observable pattern with actionable meaning. "
    "Never produce status-shaped content — words like 'investigate', "
    "'observed' or 'unclear' describe your process, not the component.\n\n"

    "Emit an empty operation list only when the evidence genuinely bears on "
    "nothing in the set and contains no new insight."))

(defn- claim-reflection-workflow
  "Single-:llm-node ORC workflow for the CLAIM reflection call.

   `:operations` is typed as a vector of `claim-operation-proposal` so the llm
   structured-output spec tells the model the per-field types. The schema is an
   OPEN malli map — which is exactly why `prepare-operations` has to reject
   unknown keys itself: a drifted key VALIDATES here and would vanish silently.

   The input schemas mirror `reflection-workflow`'s: both workflows are fed by
   the same gatherers (`gather-recent-events`, `gather-aggregate-metrics`,
   `compute-delta`, `gather-structural-context`), and the DSL layer rejects
   unconstrained schemas (`:any` / bare `:map`) outright.

   CC-31: parameterized by `model` exactly as `reflection-workflow` is — the
   node's `:model` is what the engine stamps onto the completion event, and
   that stamp is what makes the recorded deltas' provenance extractable. A
   nil model leaves the node unpinned (ambient provider default), which is
   the pre-CC-31 shape unchanged."
  [model]
  (orc/workflow "ontology-consolidator-claim-reflection"
    (orc/blackboard
      {:target-type [:enum :node-type :node-instance :tree-fingerprint :tree-class]
       :target-id target-identity-schema
       :claim-set :string
       :recent-events [:vector recent-event-schema]
       :aggregate-metrics [:maybe aggregate-metrics-schema]
       :recent-vs-historical-delta [:maybe history-delta-schema]
       :structural-context [:maybe target-identity-schema]
       :operations [:vector claim-operation-proposal]})

    (cond->
      (orc/llm "propose-claim-operations"
        :instruction claim-reflection-instruction
        :reads [:target-type :target-id :claim-set
                :recent-events :aggregate-metrics
                :recent-vs-historical-delta :structural-context]
        :writes [:operations]
        :options {:max-retries reflection-max-retries
                  :retry-delay-ms [500 1500 3000]})
      model (assoc :model model))))

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
    (assoc :timestamp (str (:event/timestamp event)))
    ;; CC-21b — then reduce the value payloads to shape. See evidence-projection.
    true evidence-projection/project-observation))

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
        ;; HP-2: key executions by the bookend's [:source-sheet-id
        ;; :source-tick-id] occurrence pair — the bookend's own :sheet-id is
        ;; the EPHEMERAL Phase-2 sheet, a domain disjoint from the classified
        ;; HOST :source-sheet-id, so the previous bare-:sheet-id keying made
        ;; the exec lookup nil for EVERY observation (the reflection LLM never
        ;; saw execution evidence for a tree-class). Bookends predating
        ;; :source-tick-id don't participate.
        executions-by-occurrence (into {}
                                       (keep (fn [e]
                                               (when (and (:source-sheet-id e)
                                                          (:source-tick-id e))
                                                 [[(:source-sheet-id e) (:source-tick-id e)] e])))
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
                             exec (get executions-by-occurrence [sheet-id tick-id])
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
      (let [source-events (->> (es/read (:event-store ctx)
                                        {:types #{event-type}
                                         :tenant-id (:tenant-id ctx)})
                               (into [])
                               (filter #(event-matches-target? target-type target-id %)))
            judge-scores (->> (es/read (:event-store ctx)
                                       {:types #{:judge/score-emitted}
                                        :tenant-id (:tenant-id ctx)})
                              (into [])
                              (group-by (juxt :sheet-id :tick-id)))]
        (->> source-events
             (map (fn [event]
                    (let [scores (get judge-scores
                                      [(:sheet-id event) (:tick-id event)])]
                      (cond-> (clean-event-for-llm event)
                        (seq scores)
                        (assoc :judge-scores
                               (mapv #(select-keys % [:judge-name :judge-config
                                                     :score :feedback :dimensions
                                                     :emitted-at])
                                     scores))))))
             (take-last recent-window-size)
             vec)))))

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
        ;; HP-2: the class's occurrence identity is the [source-sheet-id
        ;; source-tick-id] PAIR — the bare source-sheet-id is the STATIC
        ;; workflow-definition sheet shared by every turn of a task-shape.
        ;; The previous sheet-only set (a) matched ZERO bookends (their
        ;; :sheet-id is the disjoint EPHEMERAL Phase-2 sheet — so
        ;; success/failure/shapes were always 0) and (b) over-matched judge
        ;; scores across every class sharing the host sheet (the pre-SJ-1
        ;; misattribution, surviving here after SJ-1 fixed the read-model —
        ;; which also silently broke read-model<->aggregate parity until now).
        occurrence-pairs (into #{}
                               (map (juxt :source-sheet-id :source-tick-id))
                               task-classifieds)
        all-tree-executions (->> (es/read (:event-store ctx)
                                          {:types #{:sheet/rlm-tree-execution-completed}
                                           :tenant-id (:tenant-id ctx)})
                                 (into []))
        relevant-execs (filter #(contains? occurrence-pairs
                                           [(:source-sheet-id %) (:source-tick-id %)])
                               all-tree-executions)
        success-count (count (filter #(= :success (:status %)) relevant-execs))
        failure-count (count (filter #(= :failure (:status %)) relevant-execs))
        distinct-shapes (->> relevant-execs
                             (keep :tree-fingerprint)
                             distinct
                             count)
        ;; Gap-3: per-judge averages across observations for this
        ;; tree-class. Judge events carry the HOST sheet-id + the TURN's
        ;; tick-id, so the same occurrence-pair scoping applies (matches the
        ;; SJ-1-fixed tree-class-judge-averages read-model — parity restored).
        relevant-judge-scores (->> (es/read (:event-store ctx)
                                            {:types #{:judge/score-emitted}
                                             :tenant-id (:tenant-id ctx)})
                                   (into [])
                                   (filter #(contains? occurrence-pairs
                                                       [(:sheet-id %) (:tick-id %)])))
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
  "llm returns simple-typed vector fields as native Clojure data, but
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

(defn- record-description-command [target-type target-id body model-provenance]
  (let [cmd-name (case target-type
                   :node-type        :ontology/record-node-type-description
                   :node-instance    :ontology/record-node-instance-description
                   :tree-fingerprint :ontology/record-tree-description
                   :tree-class       :ontology/record-tree-class-description)]
    {:command/name cmd-name
     :command/id (random-uuid)
     :command/timestamp (time/now)
     :target-id target-id
     :body body
     :model-provenance model-provenance}))

(defn- next-version [current-description]
  (if current-description
    (inc (or (:version current-description) 0))
    1))

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
;; CC-22b — contract ParentInferenceQuery (bounded, de-duplicated rendering
;; at the SIGNATURE seam; the fold — assemble-body/descriptions* — is
;; untouched and the stored body stays full).
;;
;; Motivating measurement (CC-22a, inspect-accepted): 43/86 real targets'
;; fully-ASSEMBLED renders exceed the 461-token query budget TODAY (mean
;; 1.64x inflation vs legacy prose) because `assemble-summary` already joins
;; every capability + representative use into `:summary` and the builder
;; above then appends the same two lists AGAIN; positional encoder truncation
;; then discards the LAST-rendered section first — which is the guards.
;; =============================================================================

(def ^:private by-earned-support
  "read-models' claim ranking (support desc, tie-break :claim-id) — reached
   through its own var so this seam and the fold's rendering order cannot
   drift apart. The order is the spec's `global support-rank` and the
   tie-break keeps a rebuild from the log byte-identical."
  @#'read-models/by-earned-support)

(defn- claim-query-render
  "One-pass render of a claim seq for the parent-inference query: the
   assemble-summary section order (Capabilities, Representative uses,
   Strengths, Known weaknesses, Avoid when), each claim's content exactly
   ONCE (invariant.NoDuplicateClaimContent — the measured 1.64x inflation
   was summary+sections double-rendering, not knowledge growth). Bounding
   happens by claim RANK upstream, never by token position, so the guards
   cannot be pushed last-and-truncated by the encoder."
  [claims]
  (let [contents (fn [kind]
                   (into [] (comp (filter #(= kind (:kind %)))
                                  (map :content)
                                  (distinct))
                         claims))
        section (fn [label items]
                  (when (seq items)
                    (str label ": " (str/join "; " items) ".")))
        parts (remove nil?
                      [(section "Capabilities" (contents :capability))
                       (section "Representative uses" (contents :representative-use))
                       (section "Strengths" (contents :strength))
                       (section "Known weaknesses" (contents :weakness))
                       (section "Avoid when" (contents :guard))])]
    (str "TREE SUMMARY:\n" (str/join " " parts))))

(defn- query-fits-fn
  "A predicate `(fits? query-string)` — true when the string fits the
   CONFIGURED encoder query budget (`maximum_query_tokens` minus the query
   special tokens), measured by the REAL tokenizer via the colbert
   interface's `query-truncation` (tokenizer-only, the same resolution order
   production searches use — never a hardcoded budget, never chars/4).

   Resolved lazily: ontology ships without a ColBERT dependency. nil when
   colbert is not on the classpath — there is then no ColBERT query budget
   to enforce (classification runs on graph + DJL-embedding signals) and the
   caller LOUDLY reports the unbounded render."
  []
  (when-let [qt (try (requiring-resolve 'ai.obney.orc.colbert.interface/query-truncation)
                     (catch Throwable _ nil))]
    (fn [q] (not (:query-truncated? (qt {} {:query q}))))))

(defn bounded-inference-query
  "CC-22b (contract ParentInferenceQuery): render a target's parent-inference
   query at the signature seam.

   LEGACY (empty claim set — every production target today): delegates to
   `build-parent-inference-signature`, BYTE-IDENTICALLY. Behavior-neutral
   until migration day.

   CLAIM-BACKED: renders the largest global support-rank PREFIX of the claim
   set (tie-break :claim-id; first-overflow stop) whose one-pass render fits
   the configured budget. Exclusion is OBSERVABLE — the unrendered claim ids
   are returned AND logged (`::inference-query-claims-excluded`), never
   silently capped; every excluded claim stays durable in the claim set.

   Returns {:query :rendered-claim-ids :excluded-claim-ids :claim-backed?}.

   Public: the production callers are the consolidator's two hydration
   inference calls; tests exercise this seam with the banked real fixtures."
  ([body claims] (bounded-inference-query body claims nil))
  ([body claims {:keys [target-id]}]
   (if (empty? claims)
     {:query (build-parent-inference-signature body)
      :rendered-claim-ids []
      :excluded-claim-ids []
      :claim-backed? false}
     (let [ranked (vec (by-earned-support claims))
           fits? (query-fits-fn)
           kept-n (if fits?
                    (try
                      (loop [n 1]
                        (cond
                          (> n (count ranked)) (count ranked)
                          (fits? (claim-query-render (subvec ranked 0 n))) (recur (inc n))
                          :else (dec n)))
                      (catch Exception e
                        (u/log ::inference-query-budget-unavailable
                               :target-id target-id
                               :reason :tokenizer-error
                               :error (.getMessage e))
                        (count ranked)))
                    (do (u/log ::inference-query-budget-unavailable
                               :target-id target-id
                               :reason :colbert-not-on-classpath)
                        (count ranked)))
           kept (subvec ranked 0 kept-n)
           excluded (subvec ranked kept-n)]
       (when (seq excluded)
         (u/log ::inference-query-claims-excluded
                :target-id target-id
                :rendered-claim-count (count kept)
                :excluded-claim-count (count excluded)
                :excluded-claim-ids (mapv :claim-id excluded)))
       {:query (claim-query-render kept)
        :rendered-claim-ids (mapv :claim-id kept)
        :excluded-claim-ids (mapv :claim-id excluded)
        :claim-backed? true}))))

(defn- claims-for-inference
  "The target's current claim set for query rendering, or [] with a LOUD log
   when the descriptions read model is unreachable in this context (CC-27
   posture: an unavoidable fallback must announce itself, never default
   silently). Production contexts always carry the read-model cache, so the
   fallback arm exists for degraded contexts only: the QUERY then degrades to
   the legacy render instead of the whole hydration failing."
  [context target-type target-id]
  (try (ontology/get-claims context target-type target-id)
       (catch Exception e
         (u/log ::inference-claims-read-unavailable
                :target-id target-id
                :target-type target-type
                :error (.getMessage e))
         [])))

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
          ;; CC-22b: claim-backed targets render the bounded de-duplicated
          ;; query; targets with no claims (all of production today) render
          ;; byte-identically to the legacy builder.
          signature (:query (bounded-inference-query
                              body
                              (claims-for-inference context target-type target-id)
                              {:target-id target-id}))
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
    (let [behavioral-ontology-id (java.util.UUID/nameUUIDFromBytes
                                  (.getBytes "behavioral-subtree-ontology" "UTF-8"))
          tree-class-ontology-id (java.util.UUID/nameUUIDFromBytes
                                  (.getBytes "tree-class-ontology" "UTF-8"))
          shell-uri (str "tree-class:" shell-id)
          concepts (rmp/project context :ontology/concepts)]
      (when-not (ontology/ontology-exists? context behavioral-ontology-id)
        (ontology/create-ontology! context
                                   {:command/id behavioral-ontology-id
                                    :name "Behavioral subtree ontology"
                                    :scope :behavioral-subtree
                                    :base-uri "behavioral-subtree:"}))
      (when-not (ontology/ontology-exists? context tree-class-ontology-id)
        (ontology/create-ontology! context
                                   {:command/id tree-class-ontology-id
                                    :name "Tree class ontology"
                                    :scope :tree-class
                                    :base-uri "tree-class:"}))
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
                      :source-ontology-id behavioral-ontology-id
                      :target-ontology-id tree-class-ontology-id
                      :source-uri behavior-uri
                      :target-uri shell-uri
                      :predicate "behavior:composes-into"}))))))))

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
          ;; CC-22b: claim-backed targets render the bounded de-duplicated
          ;; query; targets with no claims (all of production today) render
          ;; byte-identically to the legacy builder.
          signature (:query (bounded-inference-query
                              body
                              (claims-for-inference context target-type target-id)
                              {:target-id target-id}))
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

;; =============================================================================
;; CC-5 — the claim path
;; =============================================================================

(defn- claim-path-target-type?
  "Which granularities consolidate as CLAIM DELTAS rather than as a whole body.

   Today: `:tree-class` only, and the boundary is a consequence of two concrete
   blockers rather than a preference.

   `:node-type` and `:node-instance` CANNOT join the claim model at all. CC-1's
   `:ontology/record-claim-deltas` types `:target-identifier` as
   `[:or :string :uuid]` (so does the recorded event, and so does the spec's
   ClaimDeltasRecorded). A node-type target is a KEYWORD (`:llm`) and a
   node-instance target is a `[sheet-id node-id]` TUPLE. Neither is
   representable, and stringifying them would key the claim set at a DIFFERENT
   read-model node from the description they belong to — `[:node-type \":llm\"]`
   is not `[:node-type :llm]` — which would fork one target's Living
   Description into two. That is a schema change (CC-1's contract and the
   spec's), not something this slice may make silently.

   `:tree-fingerprint` is blocked by a different thing: its body is the ONLY
   carrier for the C-2d-2 `:parent-tree-id` and R05d `:behavioral-subtree-ids`
   graph metadata, which the reactive projector and the behavior:composes-into
   edge growth both read off the emitted description event. CC-3's
   `assemble-body` can only CARRY those keys FORWARD from a previous body, so a
   brand-new tree-fingerprint consolidating through the claim path would never
   be parented and would never grow its composes-into edges. A ClaimDelta has
   no field for graph metadata, so giving it one is a spec change too.

   Both are reported as findings rather than worked around. What matters for
   the two-writers-on-one-slot defect is that the split is TOTAL: a target that
   has a claim path never takes the body path, and a target that takes the body
   path can never have claims. No `:current` slot has two writers."
  [target-type]
  (= :tree-class target-type))

(def ^:private permitted-delta-keys
  "The key set an operation may carry, DERIVED from `claim-delta` rather than
   restated, so the two cannot drift apart.

   This exists because the delta schema is an OPEN malli map. A drifted key —
   P-A observed a `:guard` where `:context-guard` belonged, once in 178
   operations — VALIDATES, rides through the command, lands on the event, and
   its data is then never read by anything. Silent. Rejecting the operation
   here is the only place that failure is visible at all."
  (into #{} (map first) (m/children (m/schema ontology-schemas/claim-delta))))

(def ^:private operation-precedence
  "Which operation wins when a consolidation names the same claim twice.

   `:edit` outranks everything because CC-2 makes an edit a SUPERSET of a
   support — it reinforces AND rewords — so collapsing the `{:support, :edit}`
   pair P-A observed in 4 of 20 rows loses nothing at all. `:contradict`
   outranks a bare `:support` because withheld disagreement is the signal this
   corpus is thinnest on, and with the seed at 2 a single contradiction can no
   longer erase anything."
  {:edit 3 :contradict 2 :support 1})

(defn- collapse-operations
  "ONE CONSOLIDATION IS ONE OCCURRENCE.

   CC-2 applies deltas sequentially, so two operations naming the same claim
   increment its support twice from a single evidence window. That inflation
   feeds straight into CC-7's validation threshold and CC-9's enforcement
   weighting — a claim would earn the right to suppress behaviours by being
   mentioned twice in one paragraph. P-A measured this in 4 of 20 real rows.

   Keeps at most one operation per `:target-claim` (highest precedence, ties
   broken by the model's own last mention) and at most one `:add` per
   (kind, content) pair, and preserves the model's ordering otherwise."
  [ops]
  (let [indexed (vec (map-indexed vector ops))
        add-keep (:keep (reduce (fn [{:keys [seen keep]} [i op]]
                                  (let [k [(:kind op) (:content op)]]
                                    (if (contains? seen k)
                                      {:seen seen :keep keep}
                                      {:seen (conj seen k) :keep (conj keep i)})))
                                {:seen #{} :keep #{}}
                                (filter (fn [[_ op]] (= :add (:operation op))) indexed)))
        other-keep (->> indexed
                        (filter (fn [[_ op]] (not= :add (:operation op))))
                        (group-by (fn [[_ op]] (:target-claim op)))
                        (map (fn [[_ group]]
                               (first (last (sort-by (fn [[i op]]
                                                       [(get operation-precedence (:operation op) 0) i])
                                                     group)))))
                        (into #{}))
        keep (into add-keep other-keep)]
    (mapv second (filter (fn [[i _]] (contains? keep i)) indexed))))

(defn- ->enum-keyword
  "Coerce ONE enum-valued field from the wire.

   The reflection arrives as structured JSON, so its enums come back as STRINGS
   — the live model answers `{:operation \"support\"}` where the schema declares
   `:support`. This is the wire boundary and it belongs here rather than in the
   schema, because the schema is also what documents the value the rest of the
   system passes around.

   A LIVE-QA FINDING, and the reason a stub is never sufficient: every stubbed
   test in this repo hands the consolidator keywords, so the whole contract
   suite was green while the real model's every operation was being rejected as
   malformed. Tolerates `\"support\"`, `\":support\"` and `:support` alike;
   anything else is left untouched so the schema rejects it."
  [v]
  (cond
    (keyword? v) v
    (string? v)  (let [s (str/trim v)]
                   (if (str/blank? s) nil (keyword (str/replace s #"^:" ""))))
    :else v))

(defn- coerce-proposal
  "Normalise one proposed operation off the wire before it is validated."
  [op]
  (cond-> op
    (contains? op :operation) (update :operation ->enum-keyword)
    (contains? op :kind)      (update :kind ->enum-keyword)))

(defn- prepare-operations
  "Turn the reflection's proposed operations into dispatchable claim deltas.

   Five things happen here, and none of them can be done by a prompt:

   0. WIRE ENUMS ARE COERCED to keywords (see `->enum-keyword`).
   1. UNKNOWN KEYS ARE REJECTED (see `permitted-delta-keys`).
   2. Malformed operations are rejected against `claim-operation-proposal`
      rather than reaching a command that would take them.
   3. `:kind` AND `:content` are FILLED from the named claim for non-`:add`
      operations. The model is not asked to restate the kind or the wording of a
      claim it is only reinforcing — and observably does not — while
      `claim-delta` requires both.
   4. Operations are COLLAPSED per target claim (see `collapse-operations`).

   Finally the episodes are attached IN CODE from the evidence window — the
   model is never asked for occurrence ids, which is what keeps id fabrication
   at zero. An operation that arrived carrying its own episodes (a mechanical
   writer rather than the reflection LLM) keeps them.

   Returns `{:deltas [...] :rejected [{:operation .. :reason ..} ...]}`."
  [operations claims episodes]
  (let [by-id (into {} (map (juxt :claim-id identity)) claims)
        rejected (volatile! [])
        reject! (fn [op reason] (vswap! rejected conj {:operation op :reason reason}) nil)
        normalized
        (into []
              (keep (fn [raw]
                      (let [op (when (map? raw) (coerce-proposal raw))]
                        (cond
                          (not (map? raw))
                          (reject! raw :not-a-map)

                          (seq (remove permitted-delta-keys (keys op)))
                          (reject! op :unknown-keys)

                          (not (m/validate claim-operation-proposal op))
                          (reject! op :malformed-operation)

                          :else
                          (let [add? (= :add (:operation op))
                                target (:target-claim op)
                                claim (get by-id target)
                                kind (or (:kind op) (:kind claim))
                                content (or (not-empty (str/trim (str (:content op))))
                                            (:content claim))]
                            (cond
                              (and add? (nil? (:kind op)))
                              (reject! op :add-without-kind)

                              (and add? (nil? content))
                              (reject! op :add-without-content)

                              (and (not add?) (str/blank? (str target)))
                              (reject! op :missing-target-claim)

                              ;; A non-add naming a claim that is not in the set
                              ;; AND supplying no kind (or no wording) cannot be
                              ;; expressed as a delta at all. (A non-add that
                              ;; DOES carry both is dispatched: CC-2's fold
                              ;; deliberately tolerates an unresolvable target as
                              ;; a no-op, because a claim retiring under a
                              ;; concurrent consolidation is normal learning, not
                              ;; a malformed command.)
                              (or (nil? kind) (nil? content))
                              (reject! op :unresolvable-target-claim)

                              :else
                              (cond-> (assoc op :kind kind :content content)
                                add? (dissoc :target-claim))))))))
              operations)]
    {:deltas (mapv (fn [op]
                     (assoc op
                            :episodes (vec (or (seq (:episodes op)) episodes))
                            :from-legacy-corpus (boolean (:from-legacy-corpus op))
                            ;; CC-6, and it is a GUARD not a default. A
                            ;; reflection operation always rests on the turns
                            ;; this consolidation reasoned over, so its basis is
                            ;; fixed IN CODE and stamped over whatever the model
                            ;; said — the same discipline element 4 above already
                            ;; applies to `:episodes`. `permitted-delta-keys` is
                            ;; derived from `claim-delta`, so declaring
                            ;; `:evidence-basis` there made it a key the model is
                            ;; now ALLOWED to emit; without this line a model that
                            ;; emitted `:evidence-basis :legacy-corpus` on a
                            ;; consolidation with an empty evidence window would
                            ;; talk its way straight past CC-4's guard.
                            :evidence-basis :judged-occurrences))
                   (collapse-operations normalized))
     :rejected @rejected}))

(def ^:private max-episodes-per-delta
  "How many of the evidence window's occurrences ride on one delta.

   The window itself is capped at `recent-window-size` (500). Filing all 500 on
   every claim on every consolidation would grow `:supporting-episodes`
   without bound — CC-2's fold appends without de-duplicating, and it is not
   this slice's to change — which is the same unbounded-growth defect CC-13
   found in the injection sidecar, relocated into the claim set. The most
   RECENT occurrences are kept: they are the ones a reader would re-examine and
   the ones most likely to still resolve against judge evidence."
  50)

(defn- occurrence-pair
  "The HP-2 `[sheet-id tick-id]` occurrence pair for one observation.

   Prefers `:source-*`: a bookend's own `:sheet-id` is the EPHEMERAL Phase-2
   sheet, a domain disjoint from the classified HOST sheet, and a static
   task-shape's sheet-id is shared across every turn — the tick is what makes
   the reference resolvable. That is the SJ-1 join lesson, and getting it wrong
   here would hand CC-4's guard occurrences it can never ground."
  [ev]
  (let [s (or (:source-sheet-id ev) (:sheet-id ev))
        t (or (:source-tick-id ev) (:tick-id ev))]
    (when (and (uuid? s) (uuid? t)) [s t])))

(defn- evidence-window-episodes
  "The occurrences this consolidation reasoned over, oldest first, deduped and
   capped. This is what CC-4's guard resolves against judge evidence."
  [recent-events]
  (->> recent-events
       (keep occurrence-pair)
       distinct
       vec
       (take-last max-episodes-per-delta)
       vec))

(defn- render-claim-set
  "The numbered pool, ONE LINE PER CLAIM, quoted, in the delta's own
   vocabulary. See the four load-bearing prompt elements above: this rendering
   alone eliminated three of the four failure shapes P-A observed."
  [claims]
  (if (empty? claims)
    "(this target has no claims yet — the set is empty)"
    (->> claims
         (map-indexed
           (fn [i c]
             (str (inc i) ". "
                  "claim-id=" (pr-str (:claim-id c)) " "
                  "kind=" (pr-str (:kind c)) " "
                  "support=" (:support c) " "
                  "content=" (pr-str (:content c)))))
         (str/join "\n"))))

(defn- record-claim-deltas!
  "Dispatch one batch of deltas against the target's CURRENT claim-set version.

   The version is re-read immediately before the dispatch because a backfill
   may have just advanced it, and because `record-claim-deltas` refuses a stale
   batch rather than racing it.

   CC-31: `model-provenance` is the completion that PROPOSED the deltas
   (trace-id / model / usage), threaded exactly as the retained description
   path threads it. Nil for writers with no model — the legacy-body backfill
   converts authored prose, no LLM proposed it — and the command omits the
   field rather than sending nil."
  [context target-type target-id deltas evidence-event-count model-provenance]
  (command-processor/process-command
    (assoc context :command
           (cond-> {:command/name :ontology/record-claim-deltas
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :granularity target-type
                    :target-identifier target-id
                    :deltas deltas
                    :evidence-event-count evidence-event-count
                    :claim-set-version (ontology/get-claim-set-version
                                         context target-type target-id)}
             (some? model-provenance)
             (assoc :model-provenance model-provenance)))))

(defn- legacy-body->claim-deltas
  "Every insight a legacy whole-body description holds, expressed as `:add`
   deltas — the body's sections mapped onto the claim kinds CC-3 assembles them
   back out of."
  [body]
  (let [add (fn [kind content guard recommendation]
              (when-not (str/blank? (str content))
                {:operation :add
                 :kind kind
                 :content (str content)
                 :context-guard guard
                 :recommendation recommendation
                 :episodes []
                 :from-legacy-corpus true}))]
    (vec (concat
           (keep #(add :capability % nil nil) (:capabilities body))
           (keep #(add :representative-use % nil nil) (:representative-uses body))
           (keep #(add :guard % nil nil) (:avoid-when body))
           (keep #(add :strength (:trait %) (:good-when %) (:recommended-pattern %))
                 (:strengths body))
           (keep #(add :weakness (:trait %) (:avoid-when %) (:recommended-alternative %))
                 (:weaknesses body))))))

(defn- maybe-backfill-legacy-body!
  "THE TWO-WRITERS-ON-ONE-SLOT DECISION, and it is the one this slice had to
   make: what happens to a target that has only a LEGACY body when the claim
   path takes over.

   Doing nothing was not an option. CC-3 recomputes `:current` from the claim
   set on every claim event, so the first delta to land on a legacy-bodied
   target would REPLACE a body assembled from a lifetime of consolidations with
   one assembled from that consolidation's two or three claims. That is exactly
   the context collapse ADR 0021 exists to make unrepresentable, arriving
   through the migration rather than through the model.

   Refusing to consolidate such targets until CC-12's migration lands was the
   alternative, and it is worse: every live target has a legacy body, so the
   loop this slice exists to close would be dead on arrival.

   So the body is CONVERTED before the reflection runs. Each of its insights
   becomes an `:add` carrying `:from-legacy-corpus true` and NO episodes — the
   declared-provenance arm CC-4 built for precisely this: knowledge that
   asserts a prior corpus rather than a judge event, so there is no judge
   evidence to resolve and no starved score to catch. CC-7 then keeps these
   claims honest: with no post-guard episodes they can be visible and
   well-supported and still cannot validate, so a backfilled claim cannot
   suppress a behaviour on the strength of evidence nobody can re-examine. It
   can earn that right later, by being reinforced from real episodes.

   Runs once per target by construction: after it, the target has claims.

   CC-12 owns the BULK migration of the historical corpus. This is the
   just-in-time case at the consolidation boundary, and CC-12 should either
   reuse `legacy-body->claim-deltas` or supersede this."
  [context target-type target-id]
  (let [body (ontology/get-description context target-type target-id)
        claims (ontology/get-claims context target-type target-id)]
    (when (and body (empty? claims))
      (let [deltas (legacy-body->claim-deltas body)]
        (when (seq deltas)
          (u/log ::legacy-body-converted-to-claims
                 :target-type target-type
                 :target-id target-id
                 :claim-count (count deltas))
          (record-claim-deltas! context target-type target-id deltas 0 nil))))))

(defn- consolidate-claims!
  "CC-5: consolidate a target by proposing OPERATIONS over its claim set.

   No whole-body description event is emitted anywhere on this path. The body
   is assembled from the claims by CC-3's fold, which makes the assembled body
   single-writer and the interleaving defect unrepresentable rather than
   detected."
  [context target-type target-id]
  (maybe-backfill-legacy-body! context target-type target-id)
  (let [claims (ontology/get-claims context target-type target-id)
        recent-events (gather-recent-events context target-type target-id)
        aggregate-metrics (gather-aggregate-metrics context target-type target-id)
        recent-vs-historical-delta (compute-delta aggregate-metrics recent-events)
        structural-context (gather-structural-context context target-type target-id)
        episodes (evidence-window-episodes recent-events)
        ;; CC-31: same explicit pin the body path honors — a caller running a
        ;; provenance-sensitive workflow may pin the consolidator's model
        ;; independently of the ambient provider default, and the node's
        ;; :model is what the completion event (and therefore the recorded
        ;; deltas' provenance) carries.
        model (or (:ontology-consolidator-model context)
                  (:model context))
        sheet-id (orc/build-workflow! context (claim-reflection-workflow model))
        exec-result (execute-reflection context sheet-id
                                        {:target-type target-type
                                         :target-id target-id
                                         :claim-set (render-claim-set claims)
                                         :recent-events recent-events
                                         :aggregate-metrics aggregate-metrics
                                         :recent-vs-historical-delta recent-vs-historical-delta
                                         :structural-context structural-context})
        ;; CC-31: which completion proposed these operations — matched from
        ;; the store exactly as the retained description path matches it
        ;; (the completion event whose tick-id is this execution's trace-id
        ;; and which carries a :model).
        model-completion (some #(when (and (= (:trace-id exec-result) (:tick-id %))
                                           (:model %))
                                  %)
                               (into [] (es/read (:event-store context)
                                                {:tenant-id (:tenant-id context)
                                                 :types #{:sheet/node-execution-completed}})))
        model-provenance (when model-completion
                           {:trace-id (:trace-id exec-result)
                            :model (:model model-completion)
                            :usage (:usage model-completion)})
        operations (get-in exec-result [:outputs :operations])]
    (cond
      ;; CC-28 (FailureIsVisible): a reflection that never answered leaves
      ;; a durable failure record — ONE per attempt-set — instead of only
      ;; a log line indistinguishable from health.
      (not= :success (:status exec-result))
      (record-consolidation-failure! context target-type target-id exec-result)

      (not (sequential? operations))
      (u/log ::claim-reflection-produced-no-operation-list
             :target-type target-type :target-id target-id
             :outputs-keys (vec (keys (:outputs exec-result))))

      :else
      (let [{:keys [deltas rejected]} (prepare-operations operations claims episodes)
            touched (into #{} (keep :target-claim) deltas)]
        (when (seq rejected)
          (u/log ::claim-operations-rejected
                 :target-type target-type :target-id target-id
                 :rejections (mapv :reason rejected)))
        ;; The measurement P-B and CC-11 calibrate against. Silence is SAFE in a
        ;; delta world (the claim persists; under whole-body rewrite silence
        ;; meant deletion) but it is not neutral: if only a fraction of the
        ;; claims the evidence supports are reinforced, every support count —
        ;; and therefore every threshold and weighting curve calibrated against
        ;; that distribution — is calibrated against a distorted one. Logged
        ;; per consolidation so the rate is measurable in production instead of
        ;; being inferred.
        (u/log ::claim-consolidation-completed
               :target-type target-type :target-id target-id
               :pool-size (count claims)
               :pool-claims-touched (count touched)
               :pool-claims-silent (- (count claims) (count touched))
               :operations-proposed (count operations)
               :operations-rejected (count rejected)
               :deltas-dispatched (count deltas)
               :evidence-event-count (count recent-events)
               :evidence-episode-count (count episodes))
        (when (seq deltas)
          (record-claim-deltas! context target-type target-id
                                deltas (count recent-events)
                                model-provenance))))))

(defn- consolidate-body!
  "The LEGACY whole-body path — see `claim-path-target-type?` for exactly which
   granularities still take it and why.

   The anti-recency runtime validator that used to sit between the reflection
   and the emission is GONE. Nothing here rejects or clamps a consolidation.
   The prompt still asks for gradual movement against the stable baseline,
   because on this path a consolidation really does rewrite the whole body and
   that instruction is still the right one — but the ask is no longer backed by
   a string-matching valve that read every rephrasing as an erasure."
  [context target-type target-id]
  (let [current-description (ontology/get-description context target-type target-id)
        recent-events (gather-recent-events context target-type target-id)
        aggregate-metrics (gather-aggregate-metrics context target-type target-id)
        recent-vs-historical-delta (compute-delta aggregate-metrics recent-events)
        structural-context (gather-structural-context context target-type target-id)
        ;; A caller running a provenance-sensitive workflow may pin the
        ;; consolidator independently of the ambient provider default.  This
        ;; is deliberately explicit: silently falling back would make a
        ;; description impossible to attribute after the fact.
        model (or (:ontology-consolidator-model context)
                  (:model context))
        sheet-id (orc/build-workflow! context (reflection-workflow model))
        exec-result (execute-reflection context sheet-id
                                        {:target-type target-type
                                         :target-id target-id
                                         :current-description current-description
                                         :recent-events recent-events
                                         :aggregate-metrics aggregate-metrics
                                         :recent-vs-historical-delta recent-vs-historical-delta
                                         :structural-context structural-context})
        model-completion (some #(when (and (= (:trace-id exec-result) (:tick-id %))
                                           (:model %))
                                  %)
                               (into [] (es/read (:event-store context)
                                                {:tenant-id (:tenant-id context)
                                                 :types #{:sheet/node-execution-completed}})))
        model-provenance (when model-completion
                           {:trace-id (:trace-id exec-result)
                            :model (:model model-completion)
                            :usage (:usage model-completion)})
        outputs (:outputs exec-result)
        ;; Assemble the description-body from the six separate :writes
        ;; produced by the LLM. llm returns simple-vector fields
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
      ;; CC-28 (FailureIsVisible): same durable death certificate the claim
      ;; path records — the LEGACY body path's targets die just as silently
      ;; without it.
      (not= :success (:status exec-result))
      (record-consolidation-failure! context target-type target-id exec-result)

      (not (m/validate ontology-schemas/description-body body))
      (u/log ::consolidate-validation-failed
             :target-type target-type
             :target-id target-id
             :explain (me/humanize (m/explain ontology-schemas/description-body body)))

      :else
      (do
        (command-processor/process-command
          (assoc context :command (record-description-command target-type target-id body
                                                              model-provenance)))
        ;; R05d: after the description-updated event lands, grow the
        ;; behavior:composes-into graph for any newly-observed (behavior
        ;; → shell) pairs. Sticky / idempotent — re-running on the same
        ;; pair is a no-op.
        (when (= :tree-fingerprint target-type)
          (dispatch-observed-composes-into-edges!
            context target-id (:behavioral-subtree-ids body)))))))

(defn consolidate!
  "Run the consolidation for a single (target-type, target-id) target.

   Budget gate: if the configured hourly consolidation budget for this
   target-type has been exhausted (per the rolling-hour count of
   consolidation attempts — successes AND, per CC-28's
   FailuresConsumeBudget, terminal reflection failures), the consolidation
   is skipped (no LLM call, no event emitted — a skip is not a failure).
   The hour window rolls naturally; subsequent requests succeed once older
   entries fall out of the window.

   Otherwise the target takes ONE of the two write paths — claim deltas or a
   whole body, never both, see `claim-path-target-type?` — and neither path
   rejects a consolidation for its wording: the anti-recency validator is
   deleted."
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
      (if (claim-path-target-type? target-type)
        (consolidate-claims! context target-type target-id)
        (consolidate-body! context target-type target-id)))))

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
