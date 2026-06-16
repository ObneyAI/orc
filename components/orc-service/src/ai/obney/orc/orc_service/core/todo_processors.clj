(ns ai.obney.orc.orc-service.core.todo-processors
  "Behavior Tree Sheet todo processors (policies).

   These are event-driven side effects that respond to domain events:
   - Execute leaf nodes when tree tick starts
   - Handle sequence/fallback/parallel composite node logic
   - Handle map-each iteration
   - Update blackboard with node outputs"
  (:require [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.core.rlm-tree-executor :as tree-executor]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.streaming :as streaming]
            [ai.obney.grain.event-store-v3.interface :as es :refer [->event]]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.todo-processor-v2.interface :refer [defprocessor]]
            [ai.obney.grain.time.interface :as time]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as u]))

;; =============================================================================
;; Output Key Normalization
;; =============================================================================
;;
;; Code executors may return string keys ({"output" "value"}) while the
;; :sheet/complete-node-execution command schema expects keyword keys.
;; This helper ensures consistent keyword keys.

(defn- normalize-output-keys
  "Convert output map keys to keywords for command schema compatibility.
   Handles both string and keyword keys, preserving values."
  [outputs]
  (when outputs
    (reduce-kv (fn [acc k v]
                 (assoc acc (if (keyword? k) k (keyword k)) v))
               {}
               outputs)))

;; =============================================================================
;; LLM Call Budget Tracking (Opt-in Only)
;; =============================================================================
;;
;; Budget is opt-in only - if :llm-call-budget is not specified, NO limit is
;; enforced. This prevents unexpected workflow cancellations.

;; Tracks LLM call counts per tick-id for budget enforcement.
(defonce ^:private tick-llm-counts (atom {}))

(defn- increment-llm-count!
  "Increment LLM call count for a tick."
  [tick-id]
  (swap! tick-llm-counts update tick-id (fnil inc 0)))

(defn- get-llm-count
  "Get current LLM call count for a tick."
  [tick-id]
  (get @tick-llm-counts tick-id 0))

(defn clear-llm-count!
  "Clear LLM count for a tick (called on tick completion)."
  [tick-id]
  (swap! tick-llm-counts dissoc tick-id))

;; =============================================================================
;; Tick-Scoped Usage Tracking
;; =============================================================================
;; Tracks token usage per tick-id for aggregation across sub-LLM calls.

(defonce ^:private tick-usage (atom {}))

(defn- add-usage!
  "Add usage from a single LLM call to the tick's total."
  [tick-id usage]
  (when (and tick-id usage)
    (swap! tick-usage update tick-id
           (fn [u]
             (let [current (or u {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0})]
               {:prompt-tokens (+ (:prompt-tokens current 0)
                                  (or (:prompt-tokens usage) (:prompt_tokens usage) 0))
                :completion-tokens (+ (:completion-tokens current 0)
                                      (or (:completion-tokens usage) (:completion_tokens usage) 0))
                :total-tokens (+ (:total-tokens current 0)
                                 (or (:total-tokens usage) (:total_tokens usage) 0))})))))

(defn get-tick-usage
  "Get aggregated usage for a tick."
  [tick-id]
  (get @tick-usage tick-id {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0}))

(defn clear-tick-usage!
  "Clear usage for a tick (called on tick completion)."
  [tick-id]
  (swap! tick-usage dissoc tick-id))

(defn- check-llm-budget
  "Check if LLM budget exceeded. Returns nil if no budget set (unlimited).
   Only checks if :llm-call-budget was explicitly set in tick options."
  [context tick-id]
  (let [tick-ctx (rm/get-tick-execution-context context tick-id)
        budget (get-in tick-ctx [:options :llm-call-budget])]
    ;; CRITICAL: Only check if budget was explicitly set (not nil)
    ;; nil means unlimited - no cap enforced
    (when budget
      (let [current (get-llm-count tick-id)]
        (when (>= current budget)
          {:exceeded true :current current :budget budget})))))

;; =============================================================================
;; Tick-Scoped Resolution Helpers
;; =============================================================================
;;
(defn- resolve-nodes-by-id
  "Get nodes-by-id for a tick from tick-scoped execution context."
  [ctx _sheet-id tick-id]
  (rm/get-tick-nodes-by-id ctx tick-id))

(defn- resolve-blackboard
  "Get blackboard for a tick from tick-scoped execution context."
  [ctx _sheet-id tick-id]
  (rm/get-tick-blackboard ctx tick-id))

(defn- resolve-instruction-overrides
  "Get GEPA instruction overrides for a tick, if any."
  [ctx tick-id]
  (:instruction-overrides (rm/get-tick-execution-context ctx tick-id)))

(defn- apply-instruction-override
  "If instruction overrides exist for this node's name, apply them."
  [node overrides]
  (if-let [override (and overrides (:name node) (get overrides (:name node)))]
    (assoc node :instruction override)
    node))

(defn- maybe-lookup-parent-summary
  "Best-effort lookup of the parent sheet's consolidated :summary from
   the ontology corpus. Returns the summary string when available, nil
   otherwise. Graceful when the ontology component isn't loaded.

   The parent sheet's description lives in the :tree-fingerprint
   granularity, keyed by the sheet's canonical-tree-raw hash. C-2c-2's
   first cut uses sheet-id as a proxy — the corpus may have the
   description tagged under either form. We try sheet-id first; later
   slices can add fingerprint translation."
  [context sheet-id]
  (try
    (let [get-description (requiring-resolve
                            'ai.obney.orc.ontology.interface/get-description)]
      (when get-description
        (let [body (get-description context :tree-fingerprint sheet-id)]
          (:summary body))))
    (catch Exception _ nil)))

(defn maybe-auto-classify-and-set-context
  "C-2c-2: when the node is an :rlm repl-researcher with
   :auto-classify? true and no :context already set, run the classifier
   and inject the resulting :tree-id into the node's :context.

   Behavior:
     - :context already set on node      → no-op, return node
     - :auto-classify? false or absent   → no-op, return node
     - else                              → call classify-task, dispatch
                                            :ontology/assign-task-class
                                            command, set :context

   Context map must carry :sheet-id and :tick-id so the emitted event
   carries the provenance triple needed for downstream auditing.

   Returns the (possibly :context-modified) node map. Side effect: when
   the classifier runs, an :ontology/task-classified event lands in the
   store via the dispatched command."
  [node context]
  (let [rlm-config (:rlm node)
        rlm-map? (map? rlm-config)
        auto? (and rlm-map? (true? (:auto-classify? rlm-config)))
        already-set? (some? (:context node))]
    (if (or already-set? (not auto?))
      node
      (let [threshold (or (:auto-classify-threshold rlm-config) 0.7)
            ;; Behavioral side surfaces few-shot examples to the model.
            ;; Set lower than the structural match threshold (0.7) so we
            ;; can show UP TO top-N behaviors when there are adjacent
            ;; matches, but high enough that low-signal candidates
            ;; (random pattern matches at <0.6) don't take up prompt
            ;; tokens. Matches the min-display-confidence floor used
            ;; for the structural side downstream. Override per-task
            ;; via :rlm {:auto-classify-behavioral-threshold X}.
            behavioral-threshold (or (:auto-classify-behavioral-threshold rlm-config)
                                     0.6)
            classify-task (requiring-resolve
                            'ai.obney.orc.ontology.interface/classify-task)
            classify-behaviors (requiring-resolve
                                 'ai.obney.orc.ontology.interface/classify-behaviors)
            build-sig (requiring-resolve
                        'ai.obney.orc.ontology.core.task-classifier/build-task-signature)
            signature (build-sig node)
            parent-summary (maybe-lookup-parent-summary context (:sheet-id context))
            result (classify-task context
                     (cond-> {:task-signature signature
                              :threshold threshold}
                       parent-summary (assoc :parent-summary parent-summary)))
            ;; R05b: after the structural classification, query the
            ;; behavioral corpus with the structural :assigned-tree-id
            ;; as :structural-context so behaviors that compose into
            ;; this shell are surfaced. composes-into edges are
            ;; retrieval hints — empty filtered set falls back to the
            ;; unfiltered candidate set in classify-behaviors itself.
            behavioral-result (classify-behaviors context
                                {:task-signature signature
                                 :threshold behavioral-threshold
                                 :structural-context (:assigned-tree-id result)
                                 ;; Bump top-n past classify-behaviors's
                                 ;; default of 3 so the prepend's behavioral
                                 ;; section can surface up to 5 candidates
                                 ;; (matches behavioral-cap downstream).
                                 :top-n 5})
            behaviors (:behaviors behavioral-result)]
        (cp/process-command
          (assoc context :command
                 (cond-> {:command/name :ontology/assign-task-class
                          :command/id (random-uuid)
                          :command/timestamp (time/now)
                          :source-sheet-id (:sheet-id context)
                          :source-tick-id (:tick-id context)
                          :source-node-id (:id node)
                          :assigned-tree-id (:assigned-tree-id result)
                          :confidence (:confidence result)
                          :top-candidates (:top-candidates result)
                          :reasoning (or (:reasoning result) "")
                          :was-fresh-mint? (:was-fresh-mint? result)}
                   ;; C-2d-2: forward :parent-tree-id when walk-down returned
                   ;; one. Nil means top-level match or walk-down disabled.
                   (some? (:parent-tree-id result))
                   (assoc :parent-tree-id (:parent-tree-id result))
                   ;; R01: forward :rerank-fallback? as :rerank-failed?
                   ;; when classify-task observed a reranker fallback.
                   ;; Omit when false (legacy event shape preserved).
                   (true? (:rerank-fallback? result))
                   (assoc :rerank-failed? true)
                   ;; R05b: forward behavioral classification when present.
                   ;; Omit when nil/empty so the legacy event shape is
                   ;; preserved on opt-out / failure paths.
                   (seq behaviors)
                   (assoc :behavioral-subtrees behaviors))))
        (println (format "[DEBUG RLM] node '%s' auto-classified → %s (confidence %.2f, was-fresh-mint? %s, behavioral-count %d)"
                         (or (:name node) (str (:id node)))
                         (:assigned-tree-id result)
                         (double (or (:confidence result) 0.0))
                         (:was-fresh-mint? result)
                         (count (:behaviors behavioral-result))))
        ;; R-Inject: stash the full classifier payload on :context so
        ;; apply-r05-classifier-context can prepend it to the model's
        ;; Phase 1 prompt. :tree-id stays at the top level for downstream
        ;; consumers (event tagging, telemetry); :self-learning? is
        ;; dropped (it only existed for the legacy apply-ontology-context
        ;; path which R-Inject replaces in the pipeline).
        (assoc node :context
               {:tree-id (:assigned-tree-id result)
                :r05-classifier
                {:structural {:assigned-tree-id (:assigned-tree-id result)
                              :confidence (:confidence result)
                              :was-fresh-mint? (:was-fresh-mint? result)
                              :reasoning (:reasoning result)
                              :top-candidates (vec (:top-candidates result))
                              :rerank-fallback? (boolean (:rerank-fallback? result))}
                 :behavioral {:behaviors (vec (:behaviors behavioral-result))
                              :rerank-fallback? (boolean
                                                  (:rerank-fallback? behavioral-result))}}})))))

;; =============================================================================
;; R-Inject — R05 classifier output reaches the model's Phase 1 prompt
;; =============================================================================
;;
;; The wedge classifies the task (structural + behavioral) and stashes the
;; full envelope on the node's :context.:r05-classifier payload. This
;; helper reads the payload and prepends a principle-shaped block to
;; :instruction so the model sees the corpus's actual examples (with
;; reranker reasoning + seed :summary guidance) when designing its tree.
;;
;; Edge cases:
;;   - structural :was-fresh-mint? true   → "no high-confidence" branch
;;   - top-1 behavioral fresh-mint marker → "consider minting" branch
;;   - :rerank-fallback? on either axis   → caution annotation
;;
;; Each branch is principle-shaped — no hardcoded phrase matches against
;; instruction text.
;; =============================================================================

(def ^:private behavioral-cap
  "Maximum behavioral suggestions surfaced to the model. We aim for up
   to 5 strong matches; weaker ones below min-display-confidence are
   filtered out before this cap takes effect. The wedge calls
   classify-behaviors with `:top-n 5` so the candidate set is sized
   to feed this cap. Adjust both in tandem if changing."
  5)

(def ^:private structural-cap
  "Maximum structural candidates surfaced to the model. classify-task
   retrieves :k 5 from the corpus; the reranker scores all of them.
   We aim for up to 5 strong matches; the min-display-confidence floor
   below prunes weak ones. So you see breadth WHEN the corpus has
   strong adjacent shapes, and a tight focused set when it doesn't."
  5)

(def ^:private min-display-confidence
  "Floor for surfacing a candidate in the prepend. Candidates that
   score below this on the reranker are dropped before the cap is
   applied — keeps weak/noisy matches out of the model's prompt
   regardless of how many slots we'd otherwise have. Tuned to the
   reranker's typical 'cliff' between strong matches (>=0.85) and
   noise (<=0.4): 0.6 catches the middle ground where a candidate
   is plausibly related but not a clear fit."
  0.6)

(def ^:private traits-per-seed-cap
  "Per matched seed, the max number of strengths AND max number of
   weaknesses included in the prepend. Strengths/weaknesses are sorted
   by :confidence descending; the top-N from each list are surfaced.
   Caps prompt bloat — production seeds may have 3-5 strengths each."
  2)

(defn- truncate
  "Cap a string at n chars for prepend safety (e.g. unusually long
   :recommended-pattern snippets). Returns nil for nil input."
  [s n]
  (cond
    (nil? s) nil
    (<= (count s) n) s
    :else (str (subs s 0 n) "…[truncated]")))

(defn- format-principle-entry
  "Render one entry from :strengths or :weaknesses as a prose block the
   model can read. `kind` is :strength or :weakness; selects which
   fields to include and how to label them."
  [kind entry]
  (let [{:keys [trait good-when avoid-when recommended-pattern
                recommended-alternative confidence evidence-count]} entry
        ev-suffix (cond
                    (and confidence evidence-count)
                    (format " (confidence %.2f, evidence-count %d)"
                            (double confidence) (int evidence-count))
                    confidence
                    (format " (confidence %.2f)" (double confidence))
                    :else "")]
    (case kind
      :strength
      (str "  - **Trait:** " (or trait "(no trait recorded)") ev-suffix "\n"
           (when good-when
             (str "    - Good when: " good-when "\n"))
           (when recommended-pattern
             (str "    - Worked example DSL (corpus reference — adapt to your task):\n"
                  "      ```clojure\n"
                  "      " (truncate recommended-pattern 1200) "\n"
                  "      ```\n")))
      :weakness
      (str "  - **Failure mode:** " (or trait "(no trait recorded)") ev-suffix "\n"
           (when avoid-when
             (str "    - Avoid when: " avoid-when "\n"))
           (when recommended-alternative
             (str "    - Recommended fix: " recommended-alternative "\n"))))))

(defn- format-seed-body
  "Render the rich seed body (capabilities + strengths + weaknesses +
   representative-uses) as prose the model can read. Each section is
   optional — missing fields are skipped silently. Returns nil if the
   body is nil (caller-side gate).

   `traits-cap` is the per-list maximum count for strengths and
   weaknesses (sorted by :confidence desc before truncation)."
  [body traits-cap]
  (when body
    (let [{:keys [capabilities strengths weaknesses representative-uses]} body
          rank-by-conf (fn [coll] (sort-by (fn [e] (- (double (or (:confidence e) 0.0))))
                                           (or coll [])))
          top-strengths (take traits-cap (rank-by-conf strengths))
          top-weaknesses (take traits-cap (rank-by-conf weaknesses))]
      (str
        (when (seq capabilities)
          (str "Capabilities:\n"
               (->> capabilities (map (fn [c] (str "  - " c))) (str/join "\n"))
               "\n\n"))
        (when (seq top-strengths)
          (str "Strengths (proven traits — these patterns have been observed to work; mimic where they fit, adapt as needed):\n"
               (->> top-strengths
                    (map (partial format-principle-entry :strength))
                    (str/join "\n"))
               "\n"))
        (when (seq top-weaknesses)
          (str "Weaknesses (observed failure modes — avoid these patterns, apply the recommended fix where applicable):\n"
               (->> top-weaknesses
                    (map (partial format-principle-entry :weakness))
                    (str/join "\n"))
               "\n"))
        (when (seq representative-uses)
          (str "Representative uses (concrete tasks this pattern has shipped on):\n"
               (->> representative-uses (map (fn [u] (str "  - " u))) (str/join "\n"))
               "\n"))))))

(defn- ->uuid
  "Normalize a target-id to a java.util.UUID before the read-model lookup.

   The classifier's :top-candidates carry :document-metadata.:target-id as
   a STRING (ColBERT bridges metadata through JSON, so a UUID seed comes
   back as \"5a08300e-10e3-305a-80c1-17eafea15ff7\"). The descriptions
   read-model keys bodies under the literal java.util.UUID. Without this
   coercion the lookup misses silently and the prepend degrades to
   summary-only.

   Pass-through for already-UUID input keeps the function idempotent.
   String input that does not parse as a UUID returns nil — that's a
   genuine miss and the prepend renders without the rich body (rather
   than crashing the request)."
  [v]
  (cond
    (uuid? v) v
    (string? v) (try (java.util.UUID/fromString v)
                     (catch IllegalArgumentException _ nil))
    :else nil))

(defn- fetch-tree-body
  "Pull a tree-class description body via ontology/get-description.

   C-Loop-1: reads from :tree-class scope so the Living Description
   loop's consolidator-updated bodies surface in the next R-Inject
   run's prepend. Seeds are also recorded under :tree-class (via
   seed-all!), so first-time runs see seed content via the same read
   path. The classifier search index continues to partition by
   :tree-fingerprint — that's the index lookup, not the body read.

   T2-Hardening-B: coerce target-id to UUID before the lookup. The
   classifier's candidate metadata round-trips target-id through JSON
   so it arrives as a stringified UUID; the read-model keys by UUID.

   Returns nil on any failure (helper is best-effort — the prepend
   degrades to summary-only when the full body is unavailable)."
  [ctx target-id]
  (when-let [uuid-target (->uuid target-id)]
    (let [get-description (requiring-resolve
                            'ai.obney.orc.ontology.interface/get-description)]
      (try
        (when get-description
          (get-description ctx :tree-class uuid-target))
        (catch Exception _ nil)))))

(defn- derive-seed-name
  "Extract a human-readable seed name from the start of a `:summary`
   string. Most seeds begin with their own name followed by a stative
   verb or 'trees' / 'is' / 'evaluates' / etc.:
     'Legal-issue-detection trees flag...' → 'Legal-issue-detection'
     'Risk-analysis trees identify...'     → 'Risk-analysis'
     'Critique evaluates...'                → 'Critique'
     'Comparative summary is the...'        → 'Comparative summary'
     'ResearchThenSynthesize is a...'       → 'ResearchThenSynthesize'

   Falls back to nil when no clear name can be extracted (caller can
   then use a generic header). Best-effort — corpus authors should keep
   summaries written with the name first to make this work."
  [summary]
  (when (and (string? summary) (not (str/blank? summary)))
    (let [name-tail-re #"(?i)^(.+?)\s+(trees?|is|are|evaluates?|reasons?|integrates?|turns?|checks?|flag|identif(?:y|ies)|compare|extract|generates?|produces?|synthes(?:ize|izes)|investigates?|handles?|composes?|consists?|builds?|classifies?|validates?|gather|gathers|analyzes?)\b"
          m (re-find name-tail-re summary)]
      (when m
        (let [candidate (str/trim (nth m 1))]
          ;; Sanity-bound: anything longer than ~50 chars is probably
          ;; the whole first sentence, not a name. Return nil so the
          ;; caller falls back to the generic header.
          (when (<= (count candidate) 50)
            candidate))))))

(defn- format-structural-candidate
  "Render one structural candidate with its full seed body.
   `idx` is the 1-based position in the top-N list (used to label
   primary vs alternative)."
  [ctx idx candidate]
  (let [{:keys [content fitness-score reasoning rerank-source
                document-metadata]} candidate
        target-id (:target-id document-metadata)
        body (fetch-tree-body ctx target-id)
        rich (format-seed-body body traits-per-seed-cap)
        seed-name (derive-seed-name content)
        rank-label (cond
                     (= 1 idx) "Top match"
                     :else (str "Alternative #" (dec idx)))
        ;; If the seed name was extractable, the header reads
        ;;   #### Top match — Legal-issue-detection (confidence: 1.00)
        ;; Falling back to just the rank label when extraction fails.
        header-label (if seed-name
                       (str rank-label " — " seed-name)
                       rank-label)]
    (str "#### " header-label " (confidence: "
         (format "%.2f" (double (or fitness-score 0.0))) ")"
         (when (= :colbert-fallback rerank-source)
           " [reranker fell back to ColBERT — treat with caution]")
         "\n"
         "Why this fits: " (or reasoning "(no reasoning recorded)") "\n\n"
         "Pattern guidance (seed `:summary`):\n"
         (or content "(no guidance content recorded)") "\n\n"
         (or rich ""))))

(defn- format-structural-section [ctx structural]
  (let [{:keys [was-fresh-mint? top-candidates rerank-fallback?]} structural
        ;; Apply the display-confidence floor BEFORE the cap so a weak
        ;; candidate doesn't push out the slot for a stronger one. In
        ;; practice classify-task returns 5; the reranker scores spread
        ;; from 1.00 down to 0.0 — only the >= floor entries reach the
        ;; model.
        strong-candidates (filter
                            (fn [c] (>= (double (or (:fitness-score c) 0.0))
                                        min-display-confidence))
                            (or top-candidates []))
        candidates (take structural-cap strong-candidates)]
    (cond
      ;; No high-confidence match — caller fresh-minted at root.
      was-fresh-mint?
      (str "### Structural patterns\n"
           "No high-confidence structural match was returned by the classifier. "
           "Design at the abstract pattern level — the structural shape is the "
           "primary signal you have. The behavioral suggestions below reflect "
           "what the task ACCOMPLISHES.\n")

      ;; Real match with content.
      (seq candidates)
      (str "### Structural patterns (top "
           (count candidates)
           " from corpus retrieval)\n"
           (when rerank-fallback?
             "Classifier reranker fell back to similarity scoring; treat suggestions with caution and prioritize your own reading of the task.\n\n")
           (->> candidates
                (map-indexed (fn [i c] (format-structural-candidate ctx (inc i) c)))
                (str/join "\n"))))))

(defn- format-behavioral-entry [ctx idx behavior]
  (let [{:keys [behavior-id confidence was-fresh-mint? reasoning
                rerank-source]} behavior]
    (if was-fresh-mint?
      ;; C-Loop-3: strengthened from C-Loop-2 P2 after observing the
      ;; agent ignore the affordance on a behaviorally-OOD task. The
      ;; original phrasing ("Consider whether...") was too soft —
      ;; competed with the model's strong "design the simplest tree
      ;; that works" prior and lost. The new phrasing:
      ;;   1. Frames the fresh-mint marker as a CONCRETE SIGNAL the
      ;;      task may be novel — not just a possibility.
      ;;   2. Names the 12 existing behavioral categories so the model
      ;;      can explicitly check the task against them.
      ;;   3. Demands explicit :reasoning text stating WHICH category
      ;;      fits OR WHY the task is novel — forces the consideration
      ;;      rather than letting it get pattern-matched away.
      ;;   4. Makes the cost of NOT minting concrete: novel patterns
      ;;      are LOST without contribution.
      ;;   5. Acknowledges that mint is not mandatory — preserves the
      ;;      model's judgement when an existing category genuinely
      ;;      fits.
      (str (inc idx) ". Behavioral suggestion (no candidate above threshold)\n"
           "   BEHAVIORALLY-NOVEL TASK SIGNAL: classify-behaviors returned no candidate "
           "above threshold for this task — none of the existing corpus behaviors "
           "matched the task semantics by retrieval+rerank score. This is a SIGNAL the "
           "task may exercise a behavioral pattern the corpus does not yet capture.\n\n"
           "   The existing behavioral categories in the corpus are: research, "
           "extraction, analysis, synthesis, ideation, design, critique, validation, "
           "code-building, transformation, classification, investigation.\n\n"
           "   Before designing your tree, in your :reasoning output, state EITHER:\n"
           "     (a) which existing category fits this task (e.g., \"this is a "
           "design task — produces a single committed plan from constraints\"), OR\n"
           "     (b) why the task is genuinely novel relative to all 12 (name the "
           "behavioral pattern your tree will exercise that isn't captured above).\n\n"
           "   If (b) — the task IS behaviorally novel AND your tree exercises a "
           "generalizable pattern future tasks could reuse — contribute it via:\n\n"
           "       (mint-behavior! \"<short-kebab-name>\"\n"
           "                       {:capabilities [\"<concrete action verb + object — 1 per capability>\"\n"
           "                                       \"<another concrete capability>\"]\n"
           "                        :strengths [{:trait \"<what works AND why\"\n"
           "                                     :good-when \"<context guard — when this trait fires>\"\n"
           "                                     :recommended-pattern \"<concrete DSL snippet showing the shape>\"\n"
           "                                     :confidence 0.7\n"
           "                                     :evidence-count 1}]\n"
           "                        :weaknesses [{:trait \"<what fails AND why>\"\n"
           "                                      :avoid-when \"<context guard — when this trait fires>\"\n"
           "                                      :recommended-alternative \"<concrete fix or alternative\"\n"
           "                                      :confidence 0.7\n"
           "                                      :evidence-count 1}]\n"
           "                        :representative-uses [\"<concrete task this pattern shipped on>\"\n"
           "                                              \"<another concrete task>\"]\n"
           "                        :avoid-when [\"<concrete anti-context>\"]\n"
           "                        :summary \"<2-3 sentences naming the domain + the load-bearing trait + when to prefer this over existing categories>\"\n"
           "                        :version 1\n"
           "                        :consolidated-from-event-count 0}\n"
           "                       :parent nil)\n\n"
           "   CRITICAL: :strengths and :weaknesses are VECTORS OF MAPS, not vectors of "
           "strings. Each entry is principle-shaped — :trait + context-guard + concrete "
           "recommended action + confidence + evidence-count. A vector of bare strings "
           "fails schema validation and the mint is dropped silently. Look at any "
           "existing seed body in the corpus retrieval above to see the expected shape.\n\n"
           "   The minted behavior will be retrievable on subsequent classify-behaviors "
           "calls — your contribution persists in the corpus for future tasks. Without "
           "minting, a genuinely novel pattern is LOST when this task completes.\n\n"
           "   If (a) — the task fits an existing category by your judgement — no mint "
           "is needed; designing the tree using known patterns is the right call.\n")
      (let [body (fetch-tree-body ctx behavior-id)
            summary (:summary body)
            rich (format-seed-body body traits-per-seed-cap)
            seed-name (derive-seed-name summary)
            header-label (if seed-name
                           (str (inc idx) ". Behavioral: " seed-name)
                           (str (inc idx) ". Behavioral suggestion"))]
        (str header-label " (confidence: "
             (format "%.2f" (double (or confidence 0.0))) ")"
             (when (= :colbert-fallback rerank-source)
               " [reranker fell back to ColBERT — treat with caution]")
             "\n"
             "   Why this fits: " (or reasoning "(no reasoning recorded)") "\n\n"
             "   Guidance (seed `:summary`):\n"
             "   " (or summary reasoning "(no guidance recorded)") "\n\n"
             (or rich ""))))))

(defn- format-behavioral-section [ctx behavioral]
  (let [{:keys [behaviors rerank-fallback?]} behavioral
        entries (take behavioral-cap behaviors)]
    (when (seq entries)
      (str "### Behavioral competencies (top " (count entries)
           " from corpus retrieval)\n"
           (when rerank-fallback?
             "Classifier reranker fell back on the behavioral axis; treat the suggestions below with caution.\n\n")
           (->> entries
                (map-indexed (partial format-behavioral-entry ctx))
                (str/join "\n"))))))

(defn apply-r05-classifier-context
  "R-Inject: prepend R05's classifier output to the node's :instruction
   when the wedge has stashed a :r05-classifier payload on :context.

   When :r05-classifier is absent (node not auto-classified or :context
   set manually), returns the node unchanged. Otherwise prepends a
   principle-shaped block — structural pattern + behavioral top-3 with
   reasoning + seed :summary guidance — so the model designing the tree
   in Phase 1 sees the corpus's actual examples.

   Pure transformation; the only effect is reading description bodies
   for behavioral seeds via ontology/get-description (3 reads max)."
  [node ctx]
  (let [payload (get-in node [:context :r05-classifier])]
    (if-not payload
      node
      (let [structural (:structural payload)
            behavioral (:behavioral payload)
            block (str "## Suggested patterns from corpus\n\n"
                       "These are concrete EXAMPLES retrieved from the seed corpus based on "
                       "classification of your task. Each example includes:\n"
                       "  - WHY the candidate fits (reranker reasoning)\n"
                       "  - The pattern's prose summary (seed `:summary`)\n"
                       "  - Capabilities it provides\n"
                       "  - Proven STRENGTHS — traits observed to work, each with a worked-example DSL snippet you can adapt\n"
                       "  - Observed WEAKNESSES — failure modes others hit, with the recommended fix\n"
                       "  - Representative uses where this pattern has shipped\n\n"
                       "Mimic what works, modify what's risky for your task, OR design from scratch. They are not mandates — your job is to design the RIGHT tree for THIS task, using the corpus as evidence not gospel.\n\n"
                       (format-structural-section ctx structural)
                       "\n"
                       (format-behavioral-section ctx behavioral)
                       "\n---\n")
            result (update node :instruction (fn [inst] (str block inst)))
            ;; Trace capture: write the rendered prepend + full classifier
            ;; payload to a sidecar file keyed by sheet-id so the bench
            ;; runner can pair it with the saved EDN. Best-effort; failures
            ;; are silent (we don't want trace IO to break the request).
            sheet-id (:sheet-id ctx)]
        (when sheet-id
          (try
            (spit (str "/tmp/r-inject-trace-" sheet-id ".edn")
                  (pr-str {:rendered-at (str (java.time.Instant/now))
                           :prepend block
                           :prepend-chars (count block)
                           :original-instruction-chars (- (count (:instruction result))
                                                          (count block))
                           :classifier-payload payload}))
            (catch Exception _ nil)))
        (println (format "[DEBUG R-Inject] prepended %d chars (instruction now %d chars)"
                         (count block)
                         (count (:instruction result))))
        result))))

(defn- apply-ontology-context
  "If node has :context parameter, inject ontology context into instruction.

   The :context parameter can include:
   - :problem-type - Problem URI for context lookup
   - :include-patterns - Include success patterns
   - :include-failures - Include failure patterns
   - :tree-id - Enable self-learning mode
   - :self-learning? - Enable self-learning context

   This requires the ontology component to be available in the context."
  [node context]
  (let [ctx-config (:context node)]
    (if (and ctx-config (:instruction node))
      ;; Try to build ontology context - gracefully handle missing ontology component
      (try
        (let [ontology-ns (requiring-resolve 'ai.obney.orc.ontology.interface/build-ontology-context)
              format-fn (requiring-resolve 'ai.obney.orc.ontology.interface/format-context-for-llm)]
          (if (and ontology-ns format-fn)
            (let [;; Resolve tree-id: explicit > sheet-id (auto for self-learning)
                  tree-id (or (:tree-id ctx-config)
                              (when (:self-learning? ctx-config) (:sheet-id context)))
                  ;; Build ontology context - pass full context (needs :event-store and :cache)
                  ontology-ctx (ontology-ns context
                                            (cond-> {:problem-type (:problem-type ctx-config)}
                                              tree-id (assoc :tree-id tree-id)
                                              (:self-learning? ctx-config) (assoc :self-learning? true)))
                  ;; Format for LLM injection
                  formatted (format-fn ontology-ctx
                                       {:include (cond-> #{}
                                                   (:include-patterns ctx-config) (conj :patterns)
                                                   (:include-failures ctx-config) (conj :failures))
                                        :max-items 5})]
              (if (and formatted (not (str/blank? formatted)))
                ;; Prepend ontology context to instruction
                (update node :instruction
                        (fn [inst]
                          (str "## Ontology Context\n\n"
                               formatted
                               "\n\n---\n\n"
                               inst)))
                node))
            node))
        (catch Exception _e
          ;; Ontology component not available or error - proceed without context
          node))
      node)))

(defn- make-bb-write-event
  "Create a tick-scoped blackboard write event (isolated per execution)."
  [_event-store sheet-id tick-id key value _blackboard]
  (->event
   {:type :sheet/execution-value-written
    :tags #{[:sheet sheet-id]
            [:tick tick-id]}
    :body {:tick-id tick-id
           :sheet-id sheet-id
           :key key
           :value value}}))

;; =============================================================================
;; Tick Execution Processor
;; =============================================================================

(defn execute-tree-tick
  "When a tree tick starts, begin executing from the root node.
   Reads from tick-scoped execution context (snapshot-based execution)."
  [{:keys [event event-store] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        tick-ctx (rm/get-tick-execution-context context tick-id)
        root-id (:root-node-id tick-ctx)
        nodes-by-id (:nodes-by-id tick-ctx)
        root-node (when root-id (get nodes-by-id root-id))]
    (when root-node
      (let [blackboard (:blackboard tick-ctx)
            inputs (if (= :leaf (:type root-node))
                     (reduce (fn [acc k]
                               (if-let [entry (get blackboard k)]
                                 (assoc acc k (:value entry))
                                 acc))
                             {}
                             (:reads root-node))
                     {})]
        {:result/events
         [(->event
           {:type :sheet/node-execution-started
            :tags #{[:sheet sheet-id]
                    [:node root-id]
                    [:tick tick-id]}
            :body {:sheet-id sheet-id
                   :tick-id tick-id
                   :node-id root-id
                   :inputs inputs}})]}))))

;; =============================================================================
;; Node Execution Processor
;; =============================================================================

;; Default provider - set to nil to use mock, or :openrouter etc for real execution
(def ^:dynamic *default-dscloj-provider* :openrouter)

(defn- extract-execution-context
  "Extract map-each execution context from inputs.
   Returns a map with only the context keys needed for correlation."
  [inputs]
  (select-keys inputs [::map-each-index ::map-each-parent]))

;; =============================================================================
;; Input Profile Computation (deep module — pure function, testable in isolation)
;; =============================================================================

(defn- profile-value
  "Profile a single value's shape. Returns a map of size/density indicators.
   Pure function — no side effects, deterministic."
  [v]
  (cond
    (string? v)
    {:type :string
     :length (count v)
     :word-count (count (clojure.string/split v #"\s+"))
     :line-count (count (clojure.string/split-lines v))}

    (sequential? v)
    {:type :vector
     :length (count v)}

    (map? v)
    {:type :map
     :length (count v)}

    :else
    {:type :other
     :length (count (str v))}))

(defn compute-input-profile
  "Given a node's :reads list and a blackboard, build an input-profile map
   keyed by read key. Each value is a map of size/density indicators
   (see profile-value).

   Pure function — testable in isolation."
  [reads blackboard]
  (reduce
    (fn [acc k]
      (let [v (get-in blackboard [k :value])]
        (if (nil? v)
          acc
          (assoc acc k (profile-value v)))))
    {}
    (or reads [])))

(defn execute-leaf-node
  "Execute a leaf node when node-execution-started is emitted.
   Supports multiple executor types:
   - :ai - DSCloj AI execution (default)
   - :code - Clojure function execution
   - :tool - Direct tool invocation

   Runs execution in a future to avoid blocking the pubsub thread.
   Uses cp/process-command to emit completion events."
  [{:keys [event event-store dscloj-provider] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        event-inputs (:inputs event)
        nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)
        overrides (resolve-instruction-overrides context tick-id)
        node (-> (get nodes-by-id node-id)
                 (apply-instruction-override overrides)
                 ;; R-Inject: leaves don't carry :r05-classifier (only the
                 ;; repl-researcher path runs the wedge), so this is a
                 ;; no-op for leaves. Calling the same helper everywhere
                 ;; gives the pipeline a single context-prepend contract.
                 (apply-r05-classifier-context context))]
    (when (= :leaf (:type node))
      (let [raw-blackboard (resolve-blackboard context sheet-id tick-id)
            ;; Merge event inputs into blackboard (e.g., map-each item values)
            blackboard (reduce (fn [bb [k v]]
                                 (if (and (keyword? k) (not (= (namespace k) (namespace ::_))))
                                   (assoc-in bb [k :value] v)
                                   bb))
                               raw-blackboard
                               event-inputs)
            ;; Use provider from context, fall back to default, or use mock if nil
            provider (or dscloj-provider *default-dscloj-provider*)
            executor-type (or (:executor node) :ai)
            ;; Extract execution context for correlation
            exec-context (extract-execution-context event-inputs)
            ;; Check LLM budget ONLY for AI executor types (not code)
            is-llm-call? (and (= :ai executor-type) provider)
            ;; Stage 2 token streaming: only built when a live subscriber
            ;; opted into deltas for this tick. execute-ai falls back to
            ;; blocking predict when nil (or when DSCloj lacks
            ;; predict-stream-v2).
            stream-cfg (when is-llm-call?
                         (when-let [cfg (streaming/delta-config tick-id)]
                           (cond-> (assoc cfg
                                          :tick-id tick-id
                                          :sheet-id sheet-id
                                          :node-id node-id)
                             (and (::map-each-index exec-context)
                                  (::map-each-parent exec-context))
                             (assoc :map-each {:parent (::map-each-parent exec-context)
                                               :index (::map-each-index exec-context)}))))]
        ;; Check budget before execution (only if budget set and this is an LLM call)
        (if-let [exceeded (and is-llm-call? (check-llm-budget context tick-id))]
          ;; Budget exceeded - fail immediately
          (cp/process-command
            (assoc context :command
                   {:command/id (random-uuid)
                    :command/timestamp (time/now)
                    :command/name :sheet/fail-node-execution
                    :sheet-id sheet-id
                    :tick-id tick-id
                    :node-id node-id
                    :error (str "LLM call budget exceeded: "
                               (:current exceeded) "/" (:budget exceeded))}))
          ;; Run execution in a future to avoid blocking
          (do
            (when is-llm-call?
              (let [map-idx (::map-each-index exec-context)]
                (u/log ::leaf-llm-event-received
                       :map-idx map-idx
                       :thread (.getName (Thread/currentThread)))))
            (future
            (try
              (if (rm/is-tick-or-ancestor-cancelled? context tick-id)
                ;; Cancellation guard: the tick was cancelled between event
                ;; emission and this future starting (or while queued). Fail
                ;; fast instead of spending an LLM call on a dead tick.
                (cp/process-command
                  (assoc context :command
                         {:command/id (random-uuid)
                          :command/timestamp (time/now)
                          :command/name :sheet/fail-node-execution
                          :sheet-id sheet-id
                          :tick-id tick-id
                          :node-id node-id
                          :error "tick cancelled"}))
                (do
              ;; Increment LLM count before making the call (if applicable)
              (when is-llm-call? (increment-llm-count! tick-id))
              (let [start-ms (System/currentTimeMillis)
                    _ (when is-llm-call?
                        (let [instr (or (:instruction node) "")
                              instr-preview (if (> (count instr) 80) (str (subs instr 0 80) "...") instr)
                              map-idx (::map-each-index exec-context)]
                          (u/log ::leaf-llm-subcall-started
                                 :node-id node-id
                                 :map-idx map-idx
                                 :thread (.getName (Thread/currentThread))
                                 :instruction-preview instr-preview)))
                    result (cond
                             ;; Code executor doesn't need provider
                             (= :code executor-type)
                             (executor/execute-leaf node blackboard nil
                                                    :context context)
                             ;; AI executor with provider
                             provider
                             (executor/execute-leaf node blackboard provider
                                                    :context context
                                                    :stream stream-cfg)
                             ;; No provider - use mock
                             :else
                             (executor/execute-leaf-mock node blackboard))
                  {:keys [status outputs error duration-ms usage raw-response]} result
                  _ (when is-llm-call?
                      (u/log ::leaf-llm-subcall-completed
                             :node-id node-id
                             :status status
                             :duration-ms (or duration-ms (- (System/currentTimeMillis) start-ms))
                             :total-tokens (:total-tokens usage)))]
              ;; Track usage for this tick (aggregates across all LLM calls)
              (when usage (add-usage! tick-id usage))
              ;; Use process-command to emit completion event
              (cp/process-command
                (assoc context :command
                       (cond-> {:command/id (random-uuid)
                                :command/timestamp (time/now)
                                :command/name :sheet/complete-node-execution
                                :sheet-id sheet-id
                                :tick-id tick-id
                                :node-id node-id
                                :status status
                                :writes (normalize-output-keys (or outputs {}))}
                         duration-ms (assoc :duration-ms duration-ms)
                         error (assoc :error error)
                         ;; Verbatim raw LLM response on parse failures —
                         ;; persisted on the completion event so (node-output
                         ;; <node-id>) can retrieve the full text for diagnosis.
                         (and (= :failure status) raw-response)
                         (assoc :raw-response raw-response)
                         (seq exec-context) (assoc :inputs exec-context)
                         (seq usage) (assoc :usage usage))))
              ;; ALSO emit the RLM-specific learning-signal event when an LLM
              ;; call has usage. Carries a precomputed structured node-path
              ;; and an :input-profile derived from the node's :reads so
              ;; downstream judges/aggregators don't need to recompute.
              (when (and is-llm-call? (seq usage))
                (let [node-path (cond-> [{:type :leaf :node-id node-id}]
                                  (::map-each-parent exec-context)
                                  (#(into [{:type :map-each
                                            :parent (::map-each-parent exec-context)
                                            :index (::map-each-index exec-context)}]
                                          %)))
                      input-profile (compute-input-profile (:reads node) blackboard)]
                  (cp/process-command
                    (assoc context :command
                           (cond-> {:command/id (random-uuid)
                                    :command/timestamp (time/now)
                                    :command/name :sheet/record-rlm-tree-node-completion
                                    :sheet-id sheet-id
                                    :tick-id tick-id
                                    :node-id node-id
                                    :node-path node-path
                                    :usage usage}
                             (seq input-profile)
                             (assoc :input-profile input-profile)))))))))
            (catch Exception e
              ;; Use process-command to emit failure event
              (cp/process-command
                (assoc context :command
                       {:command/id (random-uuid)
                        :command/timestamp (time/now)
                        :command/name :sheet/fail-node-execution
                        :sheet-id sheet-id
                        :tick-id tick-id
                        :node-id node-id
                        :error (.getMessage e)})))))))
        ;; Return nil - completion will be handled by the future via process-command
        nil))))

;; =============================================================================
;; REPL Researcher Node Execution Processor
;; =============================================================================

(defn execute-repl-researcher-node
  "Execute a repl-researcher node when node-execution-started is emitted.
   Runs in a future like leaf/llm-condition nodes to avoid blocking pubsub."
  [{:keys [event event-store dscloj-provider] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        event-inputs (:inputs event)
        nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)
        overrides (resolve-instruction-overrides context tick-id)
        node (-> (get nodes-by-id node-id)
                 (apply-instruction-override overrides)
                 (maybe-auto-classify-and-set-context
                   (assoc context :sheet-id sheet-id :tick-id tick-id))
                 ;; R-Inject: replaces the legacy apply-ontology-context
                 ;; call. The wedge stashes R05's full classifier payload
                 ;; on :context; this helper prepends a principle-shaped
                 ;; "Suggested patterns from corpus" block to :instruction
                 ;; so the model designs trees informed by real corpus
                 ;; examples (with reasoning + seed :summary guidance).
                 ;; Pass sheet-id explicitly so the helper can write a
                 ;; sidecar trace file the bench runner picks up.
                 (apply-r05-classifier-context
                   (assoc context :sheet-id sheet-id :tick-id tick-id)))]
    (when (= :repl-researcher (:type node))
      (let [raw-blackboard (resolve-blackboard context sheet-id tick-id)
            blackboard (reduce (fn [bb [k v]]
                                 (if (and (keyword? k) (not (= (namespace k) (namespace ::_))))
                                   (assoc-in bb [k :value] v)
                                   bb))
                               raw-blackboard
                               event-inputs)
            provider (or dscloj-provider *default-dscloj-provider*)
            exec-context (extract-execution-context event-inputs)]
        (future
          (try
            ;; C-Loop-3: thread sheet-id / tick-id / cache through to
            ;; execute-repl-researcher so the recursive RLM sandbox's
            ;; mint-behavior! + get-description SCI bindings have the
            ;; command context they need to dispatch + read the
            ;; descriptions read-model. Without these, mint-behavior!
            ;; throws "requires a command context" and the agent's
            ;; mint call is lost.
            (let [enriched-context (assoc context
                                          :sheet-id sheet-id
                                          :tick-id tick-id
                                          ;; node-id rides along so RLM stream
                                          ;; events (iteration/phase2) carry the
                                          ;; hosting repl-researcher node.
                                          :node-id node-id)
                  result (if provider
                           (executor/execute-repl-researcher node blackboard provider enriched-context)
                           {:status :failure :error "No DSCloj provider configured"})
                  {:keys [status outputs error duration-ms generated-tree-raw iteration-reasonings usage iterations]} result
                  ;; Track usage for this tick (RLM mode aggregates all LLM calls)
                  _ (when usage (add-usage! tick-id usage))
                  ;; Handle :tree-generated status - only propagate raw tree (canonical contains fns)
                  ;; The raw S-expr DSL is pure data and can be serialized to event store
                  effective-status (if (= :tree-generated status) :tree-generated status)
                  ;; U8: Sanitize the raw tree before putting it on the blackboard.
                  ;; Inline (fn ...) values on :code nodes are SCI fn objects that
                  ;; Fressian cannot serialize. Without sanitization, the read-model
                  ;; can't project the resulting events and the tick stays pending
                  ;; forever. The actual function continues to live in the
                  ;; ephemeral-fn-registry for Phase-2 execution; only the event
                  ;; representation needs sanitization.
                  sanitized-tree-raw (when generated-tree-raw
                                       (tree-executor/sanitize-tree-for-events generated-tree-raw))
                  ;; Include sanitized generated-tree-raw in outputs when present
                  ;; (for Phase 2 auto-execution observability)
                  effective-outputs (cond-> (or outputs {})
                                      sanitized-tree-raw (assoc :generated-tree-raw sanitized-tree-raw)
                                      (seq iteration-reasonings) (assoc :iteration-reasonings (vec iteration-reasonings))
                                      ;; Iteration history — code + result + stdout + error + vars-created
                                      ;; per iteration. Surfaced so bench reports can show the model's
                                      ;; full Phase 1 work (including syntax-error retries) alongside
                                      ;; its reasoning + the final tree.
                                      (seq iterations) (assoc :iterations (vec iterations)))]
              ;; Emit :rlm/tree-generated event when tree is generated
              ;; Check for generated-tree-raw presence (Phase 2 auto-execution returns :success with this field)
              (when (some? generated-tree-raw)
                (es/append event-store
                           {:tenant-id (:tenant-id context)
                            :events [(->event
                                      {:type :rlm/tree-generated
                                       :tags #{[:sheet sheet-id]
                                               [:tick tick-id]
                                               [:node node-id]}
                                       :body {:tree-id (random-uuid)
                                              :execution-id tick-id
                                              :raw-dsl sanitized-tree-raw
                                              :generated-at (str (java.time.Instant/now))
                                              ;; Gap-7b: identify the host
                                              ;; repl-researcher explicitly so
                                              ;; downstream judges don't have to
                                              ;; scan started events.
                                              :sheet-id sheet-id
                                              :node-id node-id}})]}))
              ;; U10: Emit :rlm/researcher-iterations event whenever the
              ;; researcher ran at least one Phase 1 iteration — even when
              ;; no tree was ultimately emitted (e.g. small-input direct
              ;; execution). This gives downstream observers a uniform
              ;; capture surface for iteration history regardless of
              ;; execution mode.
              (when (seq iterations)
                (es/append event-store
                           {:tenant-id (:tenant-id context)
                            :events [(->event
                                      {:type :rlm/researcher-iterations
                                       :tags #{[:sheet sheet-id]
                                               [:tick tick-id]}
                                       :body {:execution-id tick-id
                                              :iterations (vec iterations)
                                              :iteration-count (count iterations)
                                              :emitted-at (str (java.time.Instant/now))}})]}))
              (cp/process-command
                (assoc context :command
                       (cond-> {:command/id (random-uuid)
                                :command/timestamp (time/now)
                                :command/name :sheet/complete-node-execution
                                :sheet-id sheet-id
                                :tick-id tick-id
                                :node-id node-id
                                :node-type :repl-researcher
                                :status effective-status
                                :writes (normalize-output-keys (or effective-outputs {}))}
                         duration-ms (assoc :duration-ms duration-ms)
                         error (assoc :error error)
                         (seq exec-context) (assoc :inputs exec-context)
                         ;; Propagate :usage (including :by-node from Phase 2)
                         ;; so per-node detail bubbles up to the parent tick.
                         (seq usage) (assoc :usage usage)))))
            (catch Exception e
              (cp/process-command
                (assoc context :command
                       {:command/id (random-uuid)
                        :command/timestamp (time/now)
                        :command/name :sheet/fail-node-execution
                        :sheet-id sheet-id
                        :tick-id tick-id
                        :node-id node-id
                        :error (.getMessage e)})))))
        nil))))

;; =============================================================================
;; Delegate Node Execution Processor
;; =============================================================================

(defn execute-delegate-node
  "Execute delegate node by dispatching to target sheet.
   Maps parent blackboard inputs to target, executes, maps outputs back.

   Delegate nodes execute another sheet (workflow) with isolated blackboard:
   - :reads keys are passed from parent blackboard to target inputs
   - :writes keys are received from target outputs back to parent blackboard
   - Execution is async with timeout enforcement

   Follows ORC async pattern: future + cp/process-command for completion."
  [{:keys [event event-store] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        event-inputs (:inputs event)
        nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)
        node (get nodes-by-id node-id)]
    (when (= :delegate (:type node))
      (let [raw-blackboard (resolve-blackboard context sheet-id tick-id)
            ;; Merge event inputs into blackboard (ORC pattern)
            blackboard (reduce (fn [bb [k v]]
                                 (if (and (keyword? k)
                                          (not (= (namespace k) (namespace ::_))))
                                   (assoc-in bb [k :value] v)
                                   bb))
                               raw-blackboard
                               event-inputs)
            exec-context (extract-execution-context event-inputs)

            target-sheet-id (:target-sheet-id node)
            read-keys (:reads node)
            write-keys (:writes node)
            timeout-ms (or (:delegate-timeout-ms node) 300000)

            ;; Map parent blackboard to target inputs (string keys for execute)
            target-inputs (reduce
                           (fn [acc k]
                             (if-let [entry (get blackboard k)]
                               (assoc acc (name k) (:value entry))
                               acc))
                           {}
                           read-keys)]

        ;; Async execution pattern (ORC standard)
        (future
          (try
            (let [start-time (System/currentTimeMillis)
                  ;; Lineage: delegate sub-workflows run as child ticks. Link
                  ;; BEFORE dispatch so stream subscribers on the parent tick
                  ;; see the whole cascade.
                  child-tick-id (random-uuid)
                  _ (streaming/link-child! tick-id child-tick-id)
                  result (runtime/execute context target-sheet-id
                                          target-inputs
                                          :timeout-ms timeout-ms
                                          :tick-id child-tick-id
                                          :parent-tick-id tick-id)
                  duration-ms (- (System/currentTimeMillis) start-time)
                  status (:status result)

                  ;; Map target outputs to parent blackboard
                  ;; Note: target outputs may have keyword or string keys depending on execution path
                  outputs (reduce
                           (fn [acc k]
                             (let [kw-key (if (keyword? k) k (keyword k))
                                   str-key (name k)
                                   ;; Try keyword key first, then string key
                                   v (or (get (:outputs result) kw-key)
                                         (get (:outputs result) str-key))]
                               (if v
                                 (assoc acc k v)
                                 acc)))
                           {}
                           write-keys)]

              ;; Complete node execution (ORC pattern)
              (cp/process-command
                (assoc context :command
                       (cond-> {:command/id (random-uuid)
                                :command/timestamp (time/now)
                                :command/name :sheet/complete-node-execution
                                :sheet-id sheet-id
                                :tick-id tick-id
                                :node-id node-id
                                :status status
                                :writes (normalize-output-keys outputs)
                                :duration-ms duration-ms}
                         (seq exec-context) (assoc :inputs exec-context)
                         (:error result) (assoc :error (:error result))))))

            (catch Exception e
              ;; Fail node execution (ORC pattern)
              (cp/process-command
                (assoc context :command
                       {:command/id (random-uuid)
                        :command/timestamp (time/now)
                        :command/name :sheet/fail-node-execution
                        :sheet-id sheet-id
                        :tick-id tick-id
                        :node-id node-id
                        :error (.getMessage e)})))))
        ;; Return nil - completion handled async via process-command
        nil))))

;; =============================================================================
;; Condition Node Execution Processor
;; =============================================================================

(defn- normalize-for-comparison
  "Normalize values for comparison, handling yesno/boolean cases.
   Converts 'yes'/'true'/true to true, 'no'/'false'/false to false."
  [v]
  (cond
    (boolean? v) v
    (string? v) (let [lower (clojure.string/lower-case v)]
                  (cond
                    (#{"yes" "true" "1"} lower) true
                    (#{"no" "false" "0"} lower) false
                    ;; Try parsing as number
                    :else (try (Double/parseDouble v) (catch Exception _ v))))
    :else v))

(defn evaluate-condition-check
  "Evaluate a condition check against a blackboard value.
   Returns true if check passes, false otherwise.
   Handles yesno fields by normalizing 'yes'/'no' strings to booleans."
  [check blackboard]
  (let [{:keys [key op value]} check
        entry (get blackboard key)
        bb-value (:value entry)
        ;; Normalize both values for comparison
        norm-bb (normalize-for-comparison bb-value)
        norm-val (normalize-for-comparison value)]
    (case op
      :equals (= norm-bb norm-val)
      :not-equals (not= norm-bb norm-val)
      :gt (and (number? norm-bb) (number? norm-val) (> norm-bb norm-val))
      :lt (and (number? norm-bb) (number? norm-val) (< norm-bb norm-val))
      :gte (and (number? norm-bb) (number? norm-val) (>= norm-bb norm-val))
      :lte (and (number? norm-bb) (number? norm-val) (<= norm-bb norm-val))
      :contains (and (string? bb-value) (string? value) (.contains bb-value value))
      :exists (some? bb-value)
      :truthy (boolean norm-bb)  ;; Use normalized value so "no"/"false" → false
      ;; Default to false for unknown ops
      false)))

(defn execute-condition-node
  "Execute a condition node when node-execution-started is emitted.
   Evaluates the check against blackboard and returns success/failure/running.
   Also handles llm-condition nodes via async LLM execution."
  [{:keys [event event-store] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        event-inputs (:inputs event)
        exec-context (extract-execution-context event-inputs)
        nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)
        node (get nodes-by-id node-id)
        node-type (:type node)]
    (cond
      ;; Static condition - immediate evaluation
      (= :condition node-type)
      (let [blackboard (resolve-blackboard context sheet-id tick-id)
            check (:check node)
            on-fail (get check :on-fail :failure)
            passed? (if check
                      (evaluate-condition-check check blackboard)
                      false)
            status (if passed?
                     :success
                     on-fail)]
        {:result/events
         [(->event
           {:type :sheet/node-execution-completed
            :tags #{[:sheet sheet-id]
                    [:node node-id]
                    [:tick tick-id]}
            :body (cond-> {:sheet-id sheet-id
                           :tick-id tick-id
                           :node-id node-id
                           :status status}
                    (seq exec-context) (assoc :inputs exec-context))})]})

      ;; LLM condition - async execution via future
      (= :llm-condition node-type)
      (let [blackboard (resolve-blackboard context sheet-id tick-id)
            provider (:dscloj-provider context)]
        (future
          (try
            (let [result (if provider
                           (executor/execute-llm-condition node blackboard provider
                                                          :context {:event-store event-store})
                           ;; No provider - fail with error
                           {:status :failure
                            :error "No dscloj-provider configured for LLM condition"})
                  {:keys [status result error duration-ms]} result
                  ;; LLM condition: true = success, false = failure
                  final-status (if (= :success status)
                                 (if result :success :failure)
                                 :failure)]
              (cp/process-command
               (assoc context :command
                      (cond-> {:command/id (random-uuid)
                               :command/timestamp (time/now)
                               :command/name :sheet/complete-node-execution
                               :sheet-id sheet-id
                               :tick-id tick-id
                               :node-id node-id
                               :status final-status
                               :writes {}}
                        duration-ms (assoc :duration-ms duration-ms)
                        error (assoc :error error)
                        (seq exec-context) (assoc :inputs exec-context)))))
            (catch Exception e
              (cp/process-command
               (assoc context :command
                      {:command/id (random-uuid)
                       :command/timestamp (time/now)
                       :command/name :sheet/fail-node-execution
                       :sheet-id sheet-id
                       :tick-id tick-id
                       :node-id node-id
                       :error (.getMessage e)})))))
        ;; Return nil - completion handled by future
        nil)

      ;; Not a condition node
      :else nil)))

;; =============================================================================
;; Composite Node Execution Processor
;; =============================================================================

(defn execute-composite-node
  "Handle execution of sequence/fallback nodes.
   When started, begin executing the first child.
   When a child completes, decide whether to continue or finish."
  [{:keys [event event-store] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        event-inputs (:inputs event)
        exec-context (extract-execution-context event-inputs)
        nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)
        node (get nodes-by-id node-id)]
    (when (#{:sequence :fallback} (:type node))
      (let [children-ids (:children-ids node)]
        (if (empty? children-ids)
          ;; No children - sequence succeeds, fallback fails
          {:result/events
           [(->event
             {:type :sheet/node-execution-completed
              :tags #{[:sheet sheet-id]
                      [:node node-id]
                      [:tick tick-id]}
              :body (cond-> {:sheet-id sheet-id
                             :tick-id tick-id
                             :node-id node-id
                             :status (if (= :sequence (:type node)) :success :failure)}
                      (seq exec-context) (assoc :inputs exec-context))})]}
          ;; Start first child
          (let [first-child-id (first children-ids)
                first-child (get nodes-by-id first-child-id)
                raw-blackboard (resolve-blackboard context sheet-id tick-id)
                ;; Merge event inputs into blackboard (e.g., map-each item values)
                ;; This ensures items passed from map-each are available to children
                blackboard (reduce (fn [bb [k v]]
                                     (if (and (keyword? k)
                                              (not (= (namespace k) (namespace ::_))))
                                       (assoc-in bb [k :value] v)
                                       bb))
                                   raw-blackboard
                                   event-inputs)
                bb-inputs (if (= :leaf (:type first-child))
                            (reduce (fn [acc k]
                                      (if-let [entry (get blackboard k)]
                                        (assoc acc k (:value entry))
                                        acc))
                                    {}
                                    (:reads first-child))
                            {})
                ;; Merge execution context with blackboard inputs AND event-inputs
                ;; Pass event-inputs to children so they can also access map-each items
                inputs (merge exec-context event-inputs bb-inputs)]
            {:result/events
             [(->event
               {:type :sheet/node-execution-started
                :tags #{[:sheet sheet-id]
                        [:node first-child-id]
                        [:tick tick-id]}
                :body {:sheet-id sheet-id
                       :tick-id tick-id
                       :node-id first-child-id
                       :inputs inputs}})]}))))))

;; =============================================================================
;; Parallel Node Execution Processor
;; =============================================================================

(defn execute-parallel-node
  "Handle execution of parallel nodes.
   When started, execute ALL children concurrently.
   Completion is handled by handle-parallel-child-completion."
  [{:keys [event event-store] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        event-inputs (:inputs event)
        exec-context (extract-execution-context event-inputs)
        nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)
        node (get nodes-by-id node-id)]
    (when (= :parallel (:type node))
      (let [children-ids (:children-ids node)]
        (if (empty? children-ids)
          ;; No children - parallel succeeds
          {:result/events
           [(->event
             {:type :sheet/node-execution-completed
              :tags #{[:sheet sheet-id]
                      [:node node-id]
                      [:tick tick-id]}
              :body (cond-> {:sheet-id sheet-id
                             :tick-id tick-id
                             :node-id node-id
                             :status :success}
                      (seq exec-context) (assoc :inputs exec-context))})]}
          ;; Start ALL children concurrently
          (let [blackboard (resolve-blackboard context sheet-id tick-id)]
            {:result/events
             (vec
              (for [child-id children-ids]
                (let [child (get nodes-by-id child-id)
                      bb-inputs (if (= :leaf (:type child))
                                  (reduce (fn [acc k]
                                            (if-let [entry (get blackboard k)]
                                              (assoc acc k (:value entry))
                                              acc))
                                          {}
                                          (:reads child))
                                  {})
                      ;; Merge execution context with blackboard inputs
                      inputs (merge exec-context bb-inputs)]
                  (->event
                   {:type :sheet/node-execution-started
                    :tags #{[:sheet sheet-id]
                            [:node child-id]
                            [:tick tick-id]}
                    :body {:sheet-id sheet-id
                           :tick-id tick-id
                           :node-id child-id
                           :inputs inputs}}))))}))))))

;; =============================================================================
;; Child Completion Handler
;; =============================================================================

(defn- matches-execution-context?
  "Check if an event's inputs match the given execution context.
   Empty context matches everything (for non-map-each executions)."
  [event exec-context]
  (if (empty? exec-context)
    true  ;; No context = match all (normal execution, not inside map-each)
    (let [event-inputs (or (:inputs event) {})]
      (every? (fn [[k v]]
                (= (get event-inputs k) v))
              exec-context))))

(defn- count-child-statuses
  "Count how many children of a node have completed with each status.
   Filters by execution context to distinguish between map-each iterations.
   Returns {:success n :failure n :total-children n :completed n}"
  [{:keys [event-store tenant-id] :as ctx} sheet-id tick-id parent-node exec-context]
  (let [children-ids (:children-ids parent-node)
        total (count children-ids)
        ;; Read all execution completed events for this tick and these children
        events (vec (es/read event-store
                                      {:types #{:sheet/node-execution-completed}
                                       :tags #{[:tick tick-id]}
                                       :tenant-id tenant-id}))
        ;; Filter to only children of this parent AND matching execution context
        child-set (set children-ids)
        child-completions (filter (fn [e]
                                    (and (child-set (:node-id e))
                                         (matches-execution-context? e exec-context)))
                                  events)
        ;; Count by status
        success-count (count (filter #(= :success (:status %)) child-completions))
        failure-count (count (filter #(= :failure (:status %)) child-completions))]
    {:success success-count
     :failure failure-count
     :total-children total
     :completed (count child-completions)}))

(defn- evaluate-parallel-completion
  "Evaluate if a parallel node should complete based on its policies.
   Returns nil if not ready, or {:status :success/:failure} if ready."
  [child-counts success-policy failure-policy]
  (let [{:keys [success failure total-children completed]} child-counts
        ;; Default policies
        success-policy (or success-policy :all)
        failure-policy (or failure-policy :any)]
    (cond
      ;; Check failure policy first
      (and (= failure-policy :any) (> failure 0))
      {:status :failure}

      (and (= failure-policy :all) (= failure total-children))
      {:status :failure}

      ;; Check success policy
      (and (= success-policy :all) (= success total-children))
      {:status :success}

      (and (= success-policy :any) (> success 0))
      {:status :success}

      (and (= success-policy :majority) (> success (/ total-children 2)))
      {:status :success}

      ;; All children completed but didn't meet success criteria
      (= completed total-children)
      {:status :failure}

      ;; Not all children completed yet
      :else nil)))

(defn handle-child-completion
  "When a child node completes, handle the parent's logic.
   For sequences: continue on success, fail on failure.
   For fallbacks: succeed on success, continue on failure.
   For parallel: check policies to determine completion.
   Propagates execution context from child to parent/siblings."
  [{:keys [event event-store tenant-id] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        child-id (:node-id event)
        child-status (:status event)
        child-writes (:writes event)  ;; Capture writes from completed child
        event-inputs (:inputs event)
        exec-context (extract-execution-context event-inputs)
        nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)
        child (get nodes-by-id child-id)
        parent-id (:parent-id child)]
    (when parent-id
      (let [parent (get nodes-by-id parent-id)
            siblings (:children-ids parent)
            child-index (.indexOf (vec siblings) child-id)
            next-child-id (get (vec siblings) (inc child-index))
            blackboard (resolve-blackboard context sheet-id tick-id)
            ;; Merge child's writes into blackboard - handles race condition where
            ;; read model hasn't yet processed the execution-value-written events
            blackboard-with-writes (reduce (fn [bb [k v]]
                                             (assoc-in bb [k :value] v))
                                           blackboard
                                           child-writes)]
        (case (:type parent)
          :sequence
          (cond
            ;; :success and :tree-generated both mean the child completed successfully.
            ;; :tree-generated is from RLM two-phase execution (tree was generated).
            ;; D-008: :partial from map-each is treated as continuation — downstream
            ;; synthesis nodes still run against the successes-only output.
            (#{:success :tree-generated :partial} child-status)
            (if next-child-id
              ;; Continue to next child
              (let [next-child (get nodes-by-id next-child-id)
                    next-index (inc child-index)
                    total-children (count siblings)
                    bb-inputs (if (= :leaf (:type next-child))
                                (reduce (fn [acc k]
                                          (if-let [entry (get blackboard-with-writes k)]
                                            (assoc acc k (:value entry))
                                            acc))
                                        {}
                                        (:reads next-child))
                                {})
                    ;; Merge execution context with blackboard inputs AND child writes
                    ;; Child writes are needed for non-leaf nodes (map-each, etc.) that
                    ;; read from the blackboard - ensures they see the previous child's outputs
                    inputs (merge exec-context bb-inputs child-writes)]
                {:result/events
                 [;; Emit sequence progress event
                  (->event
                   {:type :sheet/sequence-progress-updated
                    :tags #{[:sheet sheet-id]
                            [:node parent-id]
                            [:tick tick-id]}
                    :body {:sheet-id sheet-id
                           :tick-id tick-id
                           :node-id parent-id
                           :child-index next-index
                           :total-children total-children}})
                  (->event
                   {:type :sheet/node-execution-started
                    :tags #{[:sheet sheet-id]
                            [:node next-child-id]
                            [:tick tick-id]}
                    :body {:sheet-id sheet-id
                           :tick-id tick-id
                           :node-id next-child-id
                           :inputs inputs}})]})
              ;; All children succeeded — sequence completes.
              ;; D-008 sticky :partial: if ANY prior sibling completed with :partial,
              ;; propagate :partial up so the at-a-glance status truthfully reflects
              ;; that partial behavior occurred somewhere in the tree. Otherwise use
              ;; the last child's status (preserves :tree-generated for RLM Phase 1).
              (let [sibling-set (set siblings)
                    child-completions (->> (es/read event-store
                                             {:types #{:sheet/node-execution-completed}
                                              :tags #{[:tick tick-id]}
                                              :tenant-id tenant-id})
                                           (into [])
                                           (filter (fn [e]
                                                     (and (sibling-set (:node-id e))
                                                          (matches-execution-context? e exec-context)))))
                    any-partial? (some #(= :partial (:status %)) child-completions)
                    final-status (if any-partial? :partial child-status)]
                (cp/process-command
                  (assoc context :command
                         (cond-> {:command/id (random-uuid)
                                  :command/timestamp (time/now)
                                  :command/name :sheet/complete-node-execution
                                  :sheet-id sheet-id
                                  :tick-id tick-id
                                  :node-id parent-id
                                  :status final-status
                                  :writes (or (:writes event) {})}  ;; Propagate writes, default to empty map
                           (seq exec-context) (assoc :inputs exec-context))))
                nil))

            (= child-status :failure)
            ;; Child failed - sequence fails, propagate error
            {:result/events
             [(->event
               {:type :sheet/node-execution-completed
                :tags #{[:sheet sheet-id]
                        [:node parent-id]
                        [:tick tick-id]}
                :body (cond-> {:sheet-id sheet-id
                               :tick-id tick-id
                               :node-id parent-id
                               :status :failure}
                        (:error event) (assoc :error (:error event))
                        (seq exec-context) (assoc :inputs exec-context))})]}

            (= child-status :timeout)
            ;; D-003: Child timed out (e.g. RLM Phase 2 budget exceeded) -
            ;; propagate :timeout up so callers can distinguish budget exhaustion
            ;; from a generic failure. Like :failure, sequence does not continue.
            {:result/events
             [(->event
               {:type :sheet/node-execution-completed
                :tags #{[:sheet sheet-id]
                        [:node parent-id]
                        [:tick tick-id]}
                :body (cond-> {:sheet-id sheet-id
                               :tick-id tick-id
                               :node-id parent-id
                               :status :timeout}
                        (:error event) (assoc :error (:error event))
                        (seq exec-context) (assoc :inputs exec-context))})]}

            (= child-status :running)
            ;; Child returned running - propagate up
            {:result/events
             [(->event
               {:type :sheet/node-execution-completed
                :tags #{[:sheet sheet-id]
                        [:node parent-id]
                        [:tick tick-id]}
                :body (cond-> {:sheet-id sheet-id
                               :tick-id tick-id
                               :node-id parent-id
                               :status :running}
                        (seq exec-context) (assoc :inputs exec-context))})]}

            ;; Unknown status - do nothing
            :else nil)

          :fallback
          (case child-status
            (:success :partial)
            ;; Child succeeded (or partially succeeded — D-008) - fallback succeeds.
            ;; :partial means "we got something usable" so fallback stops here.
            ;; The :status surfaced matches the child's so downstream sees the truth.
            {:result/events
             [(->event
               {:type :sheet/node-execution-completed
                :tags #{[:sheet sheet-id]
                        [:node parent-id]
                        [:tick tick-id]}
                :body (cond-> {:sheet-id sheet-id
                               :tick-id tick-id
                               :node-id parent-id
                               :status child-status}
                        (seq exec-context) (assoc :inputs exec-context))})]}
            ;; D-003: :timeout from a child is like :failure for fallback purposes —
            ;; we didn't get useful output, try the next sibling. If no next sibling,
            ;; fallback completes with :timeout (truthful propagation).
            (:failure :timeout)
            (if next-child-id
              ;; Continue to next child
              (let [next-child (get nodes-by-id next-child-id)
                    bb-inputs (if (= :leaf (:type next-child))
                                (reduce (fn [acc k]
                                          (if-let [entry (get blackboard k)]
                                            (assoc acc k (:value entry))
                                            acc))
                                        {}
                                        (:reads next-child))
                                {})
                    ;; Merge execution context with blackboard inputs
                    inputs (merge exec-context bb-inputs)]
                {:result/events
                 [(->event
                   {:type :sheet/node-execution-started
                    :tags #{[:sheet sheet-id]
                            [:node next-child-id]
                            [:tick tick-id]}
                    :body {:sheet-id sheet-id
                           :tick-id tick-id
                           :node-id next-child-id
                           :inputs inputs}})]})
              ;; All children failed - fallback fails
              {:result/events
               [(->event
                 {:type :sheet/node-execution-completed
                  :tags #{[:sheet sheet-id]
                          [:node parent-id]
                          [:tick tick-id]}
                  :body (cond-> {:sheet-id sheet-id
                                 :tick-id tick-id
                                 :node-id parent-id
                                 :status :failure}
                          (seq exec-context) (assoc :inputs exec-context))})]})
            :running
            ;; Child returned running - propagate up
            {:result/events
             [(->event
               {:type :sheet/node-execution-completed
                :tags #{[:sheet sheet-id]
                        [:node parent-id]
                        [:tick tick-id]}
                :body (cond-> {:sheet-id sheet-id
                               :tick-id tick-id
                               :node-id parent-id
                               :status :running}
                        (seq exec-context) (assoc :inputs exec-context))})]}
            ;; Unknown status - do nothing
            nil)

          :parallel
          ;; For parallel nodes, check if all children completed based on policies.
          ;; Uses event-store CAS to prevent duplicate completions when
          ;; multiple children complete rapidly and all see the same state.
          (let [child-counts (count-child-statuses context sheet-id tick-id parent exec-context)
                completion (evaluate-parallel-completion
                            child-counts
                            (:success-policy parent)
                            (:failure-policy parent))]
            (when completion
              (let [event (->event
                            {:type :sheet/node-execution-completed
                             :tags #{[:sheet sheet-id]
                                     [:node parent-id]
                                     [:tick tick-id]}
                             :body (cond-> {:sheet-id sheet-id
                                            :tick-id tick-id
                                            :node-id parent-id
                                            :status (:status completion)}
                                     (seq exec-context) (assoc :inputs exec-context))})]
                ;; CAS: only append if no completion for this node+tick exists yet.
                ;; Event-store handles publishing, so no need to return events.
                (es/append event-store
                  {:events [event]
                   :tenant-id tenant-id
                   :cas {:types #{:sheet/node-execution-completed}
                         :tags #{[:tick tick-id] [:node parent-id]}
                         :predicate-fn (fn [existing]
                                         (empty? (into [] existing)))}})
                {})))

          ;; Map-each has special handling via execute-map-each-node
          :map-each
          nil

          ;; Unknown parent type
          nil)))))

;; =============================================================================
;; Map-Each Node Execution Processor
;; =============================================================================

;; Process Manager State for Map-Each Iterations
;;
;; This atom acts as a process manager (saga coordinator) for map-each node
;; execution. It tracks in-flight iteration state across multiple async event
;; cycles. This is intentionally NOT event-sourced because:
;; - State is transient (only exists during active map-each execution)
;; - Concurrent child completions require atomic updates (race condition risk with event sourcing)
;; - Loss on restart is acceptable (the parent tick will timeout and can be restarted)
(defonce ^:private map-each-state (atom {}))

(defn- map-each-key [tick-id node-id]
  (str tick-id "-" node-id))

;; =============================================================================
;; D-008: classify-map-each-outcome — pure deep module
;; =============================================================================

(defn classify-map-each-outcome
  "Classify the outcome of a map-each based on its results vector.

   Input:
     {:results    [...]   ;; raw value for success, {:__status :failure ...} for failure
      :item-count N}      ;; total items started

   Returns a 3-tuple: [status partial-summary-or-nil output-vector]
     status  = :success | :partial | :failure
     summary = nil OR {:total :succeeded :failed :failure-indices :failure-reasons}
     output  = successes-only vector

   Rule table (Q6 from D-008 PRD):
     - Empty input          -> [:success nil []]
     - All items succeeded  -> [:success nil <full vector>]
     - 0 < failed < N       -> [:partial <summary> <successes-only>]
     - All items failed     -> [:failure <summary> []]"
  [{:keys [results item-count]}]
  (if (zero? item-count)
    [:success nil []]
    (let [failure? (fn [r] (and (map? r) (= :failure (:__status r))))
          indexed (map-indexed vector results)
          failed-pairs (filter (fn [[_ r]] (failure? r)) indexed)
          successful-pairs (remove (fn [[_ r]] (failure? r)) indexed)
          failed-count (count failed-pairs)]
      (cond
        (zero? failed-count)
        [:success nil (vec results)]

        :else
        (let [summary {:total item-count
                       :succeeded (- item-count failed-count)
                       :failed failed-count
                       :failure-indices (mapv first failed-pairs)
                       :failure-reasons (into {} (map (fn [[i r]] [i (:__error r)]) failed-pairs))}
              status (if (= failed-count item-count) :failure :partial)
              output (mapv second successful-pairs)]
          [status summary output])))))

(defn execute-map-each-node
  "Handle execution of map-each nodes.
   Iterates over a list in the blackboard, executing the child subtree for each item.
   Supports optional concurrency via :max-concurrency."
  [{:keys [event event-store] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        event-inputs (:inputs event)  ;; May contain writes from previous sequence child
        nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)
        node (get nodes-by-id node-id)]
    (when (= :map-each (:type node))
      (let [source-key (:source-key node)
            item-key (:item-key node)
            output-key (:output-key node)
            max-concurrency (or (:max-concurrency node) 1)
            children-ids (:children-ids node)
            child-id (first children-ids) ;; map-each has exactly one child subtree
            blackboard (resolve-blackboard context sheet-id tick-id)
            ;; Check event inputs first (may contain writes from previous sequence child),
            ;; then fall back to blackboard. This handles race condition where read model
            ;; hasn't yet processed the execution-value-written events.
            source-list (or (get event-inputs source-key)
                            (get-in blackboard [source-key :value]))]
        (cond
          (not (sequential? source-list))
          ;; Source is not a list - fail
          {:result/events
           [(->event
             {:type :sheet/node-execution-completed
              :tags #{[:sheet sheet-id]
                      [:node node-id]
                      [:tick tick-id]}
              :body {:sheet-id sheet-id
                     :tick-id tick-id
                     :node-id node-id
                     :status :failure
                     :error (str "Source key '" source-key "' is not a list")}})]}

          (empty? source-list)
          ;; Empty list - succeed with empty results
          {:result/events
           [(make-bb-write-event event-store sheet-id tick-id output-key [] blackboard)
            (->event
             {:type :sheet/node-execution-completed
              :tags #{[:sheet sheet-id]
                      [:node node-id]
                      [:tick tick-id]}
              :body {:sheet-id sheet-id
                     :tick-id tick-id
                     :node-id node-id
                     :status :success}})]}

          (not child-id)
          ;; No child subtree - succeed with original list
          {:result/events
           [(make-bb-write-event event-store sheet-id tick-id output-key (vec source-list) blackboard)
            (->event
             {:type :sheet/node-execution-completed
              :tags #{[:sheet sheet-id]
                      [:node node-id]
                      [:tick tick-id]}
              :body {:sheet-id sheet-id
                     :tick-id tick-id
                     :node-id node-id
                     :status :success}})]}

          :else
          ;; Initialize iteration state and start first batch
          (let [state-key (map-each-key tick-id node-id)
                items (vec source-list)
                total-items (count items)
                ;; Pre-allocate results with nils to handle out-of-order completions
                ;; Determine how many to start
                batch-size (min max-concurrency total-items)
                batch-indices (vec (range batch-size))
                initial-state {:items items
                               :current-index 0
                               :results (vec (repeat total-items nil))
                               ;; Track items that have been started but not yet completed
                               :in-flight (set batch-indices)
                               :max-concurrency max-concurrency
                               :child-id child-id
                               :item-key item-key
                               :output-key output-key
                               :source-key source-key}
                ]
            ;; Store state
            (swap! map-each-state assoc state-key initial-state)
            ;; Start first batch - set item value and start child for each
            ;; We emit blackboard writes + start events so children can read item from blackboard
            {:result/events
             (into
              ;; Emit initial progress event
              [(->event
                {:type :sheet/map-each-progress-updated
                 :tags #{[:sheet sheet-id]
                         [:node node-id]
                         [:tick tick-id]}
                 :body {:sheet-id sheet-id
                        :tick-id tick-id
                        :node-id node-id
                        :item-index 0
                        :total-items total-items}})]
              (mapcat
               (fn [idx]
                 (let [item (nth items idx)
                       child (get nodes-by-id child-id)]
                   ;; Emit blackboard write for item BEFORE starting child
                   ;; This ensures all children in a sequence can read the item
                   [(make-bb-write-event event-store sheet-id tick-id item-key item blackboard)
                    (->event
                     {:type :sheet/node-execution-started
                      :tags #{[:sheet sheet-id]
                              [:node child-id]
                              [:tick tick-id]}
                      :body {:sheet-id sheet-id
                             :tick-id tick-id
                             :node-id child-id
                             ;; Pass item as an input override
                             :inputs {item-key item
                                      ::map-each-index idx
                                      ::map-each-parent node-id}}})]))
               batch-indices))}))))))

(defn handle-map-each-child-completion
  "Handle completion of a map-each child iteration.
   Collects results and starts next items or completes the map-each node.
   Only processes completions from the DIRECT child of the map-each node."
  [{:keys [event event-store] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        completing-node-id (:node-id event)
        child-status (:status event)
        inputs (:inputs event)
        writes (:writes event)
        ;; Check if this is a map-each child
        map-each-parent-id (get inputs ::map-each-parent)
        item-index (get inputs ::map-each-index)]
    (when (and map-each-parent-id item-index)
      (let [state-key (map-each-key tick-id map-each-parent-id)
            state (get @map-each-state state-key)]
        ;; Only process if:
        ;; 1. State exists for this map-each
        ;; 2. The completing node is the DIRECT child of the map-each (not a descendant)
        (when (and state (= completing-node-id (:child-id state)))
          (let [{:keys [items child-id item-key output-key]} state
                ;; Get the original item
                item (nth items item-index)
                ;; When child is a composite (sequence/fallback), writes may be empty.
                ;; In that case, read all non-special keys from the blackboard.
                effective-writes (if (seq writes)
                                   writes
                                   ;; Composite child - read from blackboard
                                   (let [blackboard (resolve-blackboard context sheet-id tick-id)
                                         source-key (:source-key state)]
                                     (reduce-kv
                                      (fn [acc k v]
                                        (if (and (keyword? k)
                                                 (not (#{item-key source-key output-key} k))
                                                 (not (= (namespace k) (namespace ::_))))
                                          (assoc acc k (:value v))
                                          acc))
                                      {}
                                      blackboard)))
                ;; Create result from writes
                computed-result (if (= :success child-status)
                                  (let [updated-item (get effective-writes item-key item)
                                        source-key (:source-key state)
                                        other-writes (reduce-kv
                                                       (fn [acc k v]
                                                         (if (and (not= k item-key)
                                                                  (not= k source-key)
                                                                  (not= k output-key))
                                                           (assoc acc (keyword k) v)
                                                           acc))
                                                       {}
                                                       effective-writes)]
                                    (if (map? updated-item)
                                      (merge updated-item other-writes)
                                      (if (seq other-writes)
                                        other-writes
                                        updated-item)))
                                  (assoc (if (map? item) item {:__original item})
                                         :__status :failure
                                         :__error (:error event)))
                ;; Atomically update state and determine action.
                ;; This prevents race conditions when multiple children complete concurrently.
                ;; Track :in-flight so concurrent completions don't all pick the same next-to-start.
                action (atom nil)
                _ (swap! map-each-state
                         (fn [all-state]
                           (let [s (get all-state state-key)]
                             (if-not s
                               ;; State already cleaned up (shouldn't happen)
                               (do (reset! action :noop) all-state)
                               (let [new-results (assoc (:results s) item-index computed-result)
                                     completed-count (count (filter some? new-results))
                                     total (count (:items s))
                                     ;; Remove the just-completed index from in-flight
                                     in-flight-after-removal (disj (:in-flight s) item-index)]
                                 (if (= completed-count total)
                                   ;; All done — remove state, action = :complete
                                   (do (reset! action {:type :complete
                                                       :results new-results
                                                       :completed-count completed-count})
                                       (dissoc all-state state-key))
                                   ;; Find next item to start: must be nil in results AND not in flight
                                   (let [next-to-start (first
                                                        (filter #(and (nil? (get new-results %))
                                                                      (not (contains? in-flight-after-removal %)))
                                                                (range total)))
                                         in-flight-after-start (if next-to-start
                                                                 (conj in-flight-after-removal next-to-start)
                                                                 in-flight-after-removal)]
                                     (reset! action (if (and next-to-start (< next-to-start total))
                                                      {:type :start-next
                                                       :next-index next-to-start
                                                       :completed-count completed-count}
                                                      {:type :wait
                                                       :completed-count completed-count}))
                                     (assoc all-state state-key
                                            (assoc s
                                                   :results new-results
                                                   :in-flight in-flight-after-start)))))))))
                act @action
                total-items (count items)
                nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)
                blackboard (resolve-blackboard context sheet-id tick-id)]
            (case (:type act)
              :complete
              ;; D-008: classify the map-each outcome using the pure deep module.
              ;; Returns [status partial-summary-or-nil output-vector] where output
              ;; is the successes-only vector (failure markers stripped).
              (let [[status summary output]
                    (classify-map-each-outcome
                      {:results (:results act) :item-count total-items})
                    completion-body (cond-> {:sheet-id sheet-id
                                             :tick-id tick-id
                                             :node-id map-each-parent-id
                                             :status status}
                                      summary (assoc :partial-summary summary))]
                {:result/events
                 [(->event
                   {:type :sheet/map-each-progress-updated
                    :tags #{[:sheet sheet-id] [:node map-each-parent-id] [:tick tick-id]}
                    :body {:sheet-id sheet-id :tick-id tick-id :node-id map-each-parent-id
                           :item-index (:completed-count act) :total-items total-items}})
                  (make-bb-write-event event-store sheet-id tick-id output-key output blackboard)
                  (->event
                   {:type :sheet/node-execution-completed
                    :tags #{[:sheet sheet-id] [:node map-each-parent-id] [:tick tick-id]}
                    :body completion-body})]})

              :start-next
              (let [next-item (nth items (:next-index act))
                    child (get nodes-by-id child-id)]
                {:result/events
                 [(->event
                   {:type :sheet/map-each-progress-updated
                    :tags #{[:sheet sheet-id] [:node map-each-parent-id] [:tick tick-id]}
                    :body {:sheet-id sheet-id :tick-id tick-id :node-id map-each-parent-id
                           :item-index (:completed-count act) :total-items total-items}})
                  ;; Write item to blackboard so children in sequence can read it
                  (make-bb-write-event event-store sheet-id tick-id item-key next-item blackboard)
                  (->event
                   {:type :sheet/node-execution-started
                    :tags #{[:sheet sheet-id] [:node child-id] [:tick tick-id]}
                    :body {:sheet-id sheet-id :tick-id tick-id :node-id child-id
                           :inputs {item-key next-item
                                    ::map-each-index (:next-index act)
                                    ::map-each-parent map-each-parent-id}}})]})

              ;; :wait or :noop — just emit progress
              {:result/events
               [(->event
                 {:type :sheet/map-each-progress-updated
                  :tags #{[:sheet sheet-id] [:node map-each-parent-id] [:tick tick-id]}
                  :body {:sheet-id sheet-id :tick-id tick-id :node-id map-each-parent-id
                         :item-index (or (:completed-count act) 0) :total-items total-items}})]})))))))

;; =============================================================================
;; Blackboard Update Processor
;; =============================================================================

(defn update-blackboard-on-completion
  "No-op — blackboard writes are handled atomically by complete-node-execution.
   Kept as a registered processor for event flow compatibility."
  [_context]
  nil)


;; =============================================================================
;; Tree Tick Completion
;; =============================================================================

;; Maximum iterations to prevent infinite loops
(def ^:dynamic *max-tick-iterations* 10)

(defn complete-tree-tick
  "When the root node completes, complete the tree tick.
   If status is :running, automatically re-tick (up to max iterations).
   If tick has been cancelled, stop immediately.
   For tick-scoped executions, includes outputs in the completion event."
  [{:keys [event event-store] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        status (:status event)
        error (:error event)  ;; Extract error from node completion
        tick-ctx (rm/get-tick-execution-context context tick-id)
        root-node-id (:root-node-id tick-ctx)
        ;; Per-execution override from options, else global dynamic default
        max-ticks (or (get-in tick-ctx [:options :max-ticks]) *max-tick-iterations*)
        ;; Get current tick to check iteration count and cancellation.
        ;; Ancestor-aware: a child tick (RLM Phase 2 / delegate) spawned in
        ;; the window where its parent was being cancelled self-terminates
        ;; here instead of running to its own timeout.
        tick (rm/get-tick context tick-id)
        current-iteration (or (:iteration tick) 1)
        cancelled? (or (= :cancelled (:status tick))
                       (and (:parent-tick-id tick)
                            (rm/is-tick-or-ancestor-cancelled? context (:parent-tick-id tick))))]
    (when (= node-id root-node-id)
      (cond
        ;; Tick was cancelled - don't re-tick
        cancelled?
        {:result/events
         [(->event
           {:type :sheet/tree-tick-completed
            :tags #{[:sheet sheet-id]
                    [:tick tick-id]}
            :body {:sheet-id sheet-id
                   :tick-id tick-id
                   :iteration current-iteration
                   :root-status :failure}})]}

        ;; Status is running and we haven't hit max iterations - re-tick
        (and (= status :running)
             (< current-iteration max-ticks))
        {:result/events
         [(->event
           {:type :sheet/tree-tick-completed
            :tags #{[:sheet sheet-id]
                    [:tick tick-id]}
            :body {:sheet-id sheet-id
                   :tick-id tick-id
                   :iteration current-iteration
                   :root-status :running}})
          (->event
           {:type :sheet/tree-tick-started
            :tags #{[:sheet sheet-id]
                    [:tick tick-id]}
            :body {:sheet-id sheet-id
                   :tick-id tick-id
                   :iteration (inc current-iteration)}})]}

        ;; Either success/failure, or hit max iterations
        :else
        (let [final-status (if (and (= status :running)
                                     (>= current-iteration max-ticks))
                             :failure
                             status)
              ;; For tick-scoped executions, gather outputs from isolated blackboard
              outputs (when tick-ctx
                        (let [bb (:blackboard (rm/get-tick-execution-context context tick-id))]
                          (reduce-kv (fn [acc k entry]
                                       (assoc acc k (:value entry)))
                                     {}
                                     bb)))]
          {:result/events
           [(->event
             {:type :sheet/tree-tick-completed
              :tags #{[:sheet sheet-id]
                      [:tick tick-id]}
              :body (cond-> {:sheet-id sheet-id
                             :tick-id tick-id
                             :iteration current-iteration
                             :root-status final-status}
                      outputs (assoc :outputs outputs)
                      error (assoc :error error))})]})))))


;; =============================================================================
;; Execution Completion Delivery
;; =============================================================================

(defn- build-node-trace
  "Build a per-leaf-node execution trace for a completed tick. Reads all
   `:sheet/node-execution-completed` events with timestamp >= the tick's
   start time, capturing the parent tick's nodes AND any Phase 2 child
   sheet/tick events (which have different tick-ids but are spawned from
   within this tick's lifecycle).

   Returns a vector of selected per-event fields, sorted by timestamp.
   The shape mirrors what bench harnesses (predict_rlm_comparison, R-Inject)
   used to query themselves — it's now a first-class part of the tick
   completion result so consumers don't have to compose it.

   Each entry:
     {:node-id <uuid> :sheet-id <uuid> :tick-id <uuid> :status <kw>
      :timestamp <OffsetDateTime>
      :inputs {…optional…} :writes {…optional…}
      :usage {…optional…} :duration-ms <ms optional>}

   When events of that type haven't been emitted (or event-store is nil),
   returns an empty vector. Never throws."
  [event-store tenant-id tick-id]
  (try
    (when event-store
      (let [;; Find this tick's :sheet/tree-tick-started timestamp so we
            ;; filter out completions from prior runs.
            tick-events (into [] (es/read event-store
                                  (cond-> {:tags #{[:tick tick-id]}}
                                    tenant-id (assoc :tenant-id tenant-id))))
            started-event (first (filter #(= :sheet/tree-tick-started (:event/type %))
                                         tick-events))
            start-ts (some-> started-event :event/timestamp)
            ;; Query ALL completions across the event store (parent +
            ;; any child sheets spawned during Phase 2). The timestamp
            ;; filter scopes us to events emitted on or after this
            ;; tick's start.
            all-completions (into [] (es/read event-store
                                      (cond-> {:types #{:sheet/node-execution-completed}}
                                        tenant-id (assoc :tenant-id tenant-id))))]
        (->> all-completions
             (filter (fn [ev]
                       (let [ts (:event/timestamp ev)]
                         (or (nil? start-ts)
                             (.isAfter ^java.time.OffsetDateTime ts start-ts)
                             (.isEqual ^java.time.OffsetDateTime ts start-ts)))))
             (sort-by :event/timestamp)
             (mapv (fn [ev]
                     (cond-> {:node-id (:node-id ev)
                              :sheet-id (:sheet-id ev)
                              :tick-id (:tick-id ev)
                              :status (:status ev)
                              :timestamp (:event/timestamp ev)}
                       (:inputs ev) (assoc :inputs (:inputs ev))
                       (:writes ev) (assoc :writes (:writes ev))
                       (:usage ev) (assoc :usage (:usage ev))
                       (:duration-ms ev) (assoc :duration-ms (:duration-ms ev))))))))
    (catch Exception _ [])))

(defn- aggregate-tick-by-node
  "Read all :sheet/node-execution-completed events for the given tick-id
   from the event store, and aggregate per-node usage with a structured
   node-path key.

   When a node's :usage already contains a :by-node breakdown (i.e. it
   was a parent node like repl-researcher that aggregated child-tick
   per-node usage), we PASS THROUGH those structured entries instead of
   reducing to a single entry — this preserves the full path detail from
   Phase 2 child ticks back into the parent tick's saved result."
  [event-store tenant-id tick-id]
  (when event-store
    (let [tick-events (into [] (es/read event-store
                                (cond-> {:tags #{[:tick tick-id]}}
                                  tenant-id (assoc :tenant-id tenant-id))))
          completions (filter #(= :sheet/node-execution-completed (:event/type %))
                              tick-events)]
      (reduce
        (fn [acc event]
          (let [node-id (:node-id event)
                inputs (:inputs event)
                usage (:usage event)
                child-by-node (:by-node usage)]
            (cond
              ;; Pass-through: this node already has a per-child breakdown
              ;; (it was a parent that aggregated Phase 2 sub-LLM calls).
              ;; Inherit those entries directly so the structured paths
              ;; are preserved.
              (and node-id (seq child-by-node))
              (merge-with (fn [a b]
                            (merge-with + a b))
                          acc child-by-node)

              ;; Simple case: this node had its own usage but no sub-breakdown.
              ;; Build a path from map-each context (if present) and add as
              ;; a single entry.
              (and node-id usage)
              (let [map-each-parent (some (fn [k]
                                            (when (and (keyword? k)
                                                       (= (name k) "map-each-parent"))
                                              (get inputs k)))
                                          (keys (or inputs {})))
                    map-each-index (some (fn [k]
                                           (when (and (keyword? k)
                                                      (= (name k) "map-each-index"))
                                             (get inputs k)))
                                         (keys (or inputs {})))
                    path (if (and (some? map-each-parent) (some? map-each-index))
                           [{:type :map-each :parent map-each-parent :index map-each-index}
                            {:type :leaf :node-id node-id}]
                           [{:type :leaf :node-id node-id}])]
                (update acc path
                        (fn [existing]
                          (merge-with +
                                      (or existing {:prompt-tokens 0
                                                    :completion-tokens 0
                                                    :total-tokens 0})
                                      (select-keys usage
                                                   [:prompt-tokens
                                                    :completion-tokens
                                                    :total-tokens])))))

              :else acc)))
        {}
        completions))))

(defn deliver-execution-result
  "When a tick completes, deliver the result to any waiting promise.
   This bridges the async todo processor pipeline back to sync callers
   who are blocking on runtime/execute.

   Skips delivery for intermediate :running completions — those are
   re-tick signals, not final results. The final delivery happens when
   the tree completes with :success/:failure, or when max iterations
   is hit (complete-tree-tick converts :running to :failure)."
  [{:keys [event event-store tenant-id]}]
  (let [tick-id (:tick-id event)
        root-status (:root-status event)
        outputs (:outputs event)
        error (:error event)]
    ;; Skip intermediate :running completions — those are re-tick signals,
    ;; not final results. complete-tree-tick converts :running to :failure
    ;; when max iterations are exhausted.
    (when (not= root-status :running)
      ;; Get aggregated usage before clearing
      (let [usage (get-tick-usage tick-id)
            by-node (aggregate-tick-by-node event-store tenant-id tick-id)
            usage-with-breakdown (cond-> usage
                                   (seq by-node) (assoc :by-node by-node))
            ;; Build per-leaf-node execution trace from the event store.
            ;; First-class field — consumers (bench harnesses, eval frameworks,
            ;; ontology consolidators) read `:node-trace` from the result
            ;; instead of querying the event store themselves.
            node-trace (build-node-trace event-store tenant-id tick-id)]
        ;; Clean up budget and usage tracking for this tick
        (clear-llm-count! tick-id)
        (clear-tick-usage! tick-id)
        (runtime/deliver-completion! tick-id
          (cond-> {:status (case root-status
                             :success :success
                             :failure :failure
                             :tree-generated :tree-generated
                             ;; D-008: surface :partial to callers so they
                             ;; can distinguish "we got some output" from
                             ;; "we got nothing".
                             :partial :partial
                             ;; D-003: surface :timeout truthfully so callers
                             ;; can distinguish "budget exceeded mid-execution"
                             ;; from "we failed for some other reason".
                             :timeout :timeout
                             :failure)
                   :outputs (or outputs {})
                   ;; Include raw tree for :tree-generated status (canonical form generated at execution time)
                   :generated-tree-raw (get outputs :generated-tree-raw)
                   :trace-id tick-id
                   :error error}
            ;; Include usage if any LLM calls were made
            (pos? (:total-tokens usage 0)) (assoc :usage usage-with-breakdown)
            ;; Always include :node-trace when we have any leaf events.
            (seq node-trace) (assoc :node-trace node-trace)))))
    ;; No events to emit
    nil))

(defn deliver-cancellation
  "When a tick is cancelled, unblock any caller waiting on runtime/execute
   immediately instead of letting it hang until its timeout. The status
   stays :failure (existing callers switch on :status); :cancelled? true is
   the additive discriminator. Idempotent with the eventual terminal
   delivery: whichever fires first wins the promise, the other no-ops."
  [{:keys [event]}]
  (let [tick-id (:tick-id event)]
    (runtime/deliver-completion! tick-id
      {:status :failure
       :error "tick cancelled"
       :cancelled? true
       :outputs {}
       :trace-id tick-id}))
  nil)

;; =============================================================================
;; Trace Assembly (Post-hoc from events)
;; =============================================================================

(defn trace-execution-context
  "Return the execution-disambiguating context for trace correlation.
   This distinguishes repeated executions of the same node under map-each."
  [inputs]
  (select-keys (or inputs {}) [::map-each-index ::map-each-parent]))

(defn trace-execution-key
  "Correlation key for matching started/completed events in trace assembly."
  [event]
  [(:node-id event) (trace-execution-context (:inputs event))])

(defn assemble-execution-trace
  "After a tick completes, assemble an execution trace from events and store it.
   Only runs for tick-scoped executions (snapshot-based).
   Reads node-execution-started/completed events, correlates them with node
   metadata from the execution snapshot, and stores via store-execution-trace."
  [{:keys [event event-store] :as context}]
  (let [tick-id (:tick-id event)
        sheet-id (:sheet-id event)
        root-status (:root-status event)
        outputs (:outputs event)
        tick-ctx (rm/get-tick-execution-context context tick-id)]
    ;; Only assemble traces for tick-scoped executions
    (when tick-ctx
      (let [nodes-by-id (:nodes-by-id tick-ctx)
            version-number (:version-number tick-ctx)
            ;; Read all events for this tick (into [] to realize reducible)
            tick-events (into [] (es/read event-store {:tags #{[:tick tick-id]} :tenant-id (:tenant-id context)}))
            ;; Find tick-started event for timing
            started-event (first (filter #(= :sheet/tree-tick-started (:event/type %)) tick-events))
            started-at (when started-event (:event/timestamp started-event))
            completed-at (:event/timestamp event)
            ;; Build input snapshot from tick blackboard seed
            input-snapshot (let [bb (:blackboard tick-ctx)]
                             (reduce-kv (fn [acc k entry]
                                          (if (some? (:value entry))
                                            (assoc acc k (:value entry))
                                            acc))
                                        {} bb))
            ;; Build output snapshot
            output-snapshot (or outputs {})
            ;; Correlate node-execution-started and completed events
            started-events (filter #(= :sheet/node-execution-started (:event/type %)) tick-events)
            completed-events (filter #(= :sheet/node-execution-completed (:event/type %)) tick-events)
            ;; Build completed map by node plus execution context. Map-each runs
            ;; the same child node-id once per item, so keying only by node-id
            ;; collapses all child completions into whichever event was seen last.
            completed-by-execution (reduce (fn [acc e]
                                             (assoc acc (trace-execution-key e) e))
                                           {}
                                           completed-events)
            ;; Build node traces
            node-traces (vec
                          (for [started started-events
                                :let [node-id (:node-id started)
                                      node (get nodes-by-id node-id)
                                      completed (get completed-by-execution
                                                     (trace-execution-key started))]
                                :when node]
                            (cond-> {:node-id node-id
                                     :node-name (:name node)
                                     :node-type (:type node)
                                     :parent-id (:parent-id node)
                                     :status (or (:status completed) :unknown)
                                     :started-at (str (:event/timestamp started))
                                     :completed-at (when completed (str (:event/timestamp completed)))}
                              (:duration-ms completed) (assoc :duration-ms (:duration-ms completed))
                              (:writes completed) (assoc :outputs (:writes completed))
                              (:error completed) (assoc :error (:error completed))
                              ;; Inputs from event (non-context keys)
                              (:inputs started) (assoc :inputs
                                                       (into {} (filter (fn [[k _]] (and (keyword? k) (not (= (namespace k) (namespace ::_)))))
                                                                        (:inputs started)))))))
            ;; Calculate duration
            duration-ms (if (and started-at completed-at)
                          (- (.toEpochMilli (java.time.Instant/parse (str completed-at)))
                             (.toEpochMilli (java.time.Instant/parse (str started-at))))
                          0)
            trace-id tick-id
            final-status (case root-status
                           :success :success
                           :failure :failure
                           :partial :partial
                           :timeout :timeout
                           :running :running
                           :tree-generated :tree-generated
                           :failure)]
        ;; Store trace via command in a future (best-effort, non-blocking).
        ;; Must be async because cp/process-command -> es/append -> pubsub/pub
        ;; can deadlock if called synchronously within a todo processor thread.
        (future
          (try
            (cp/process-command
              (assoc context :command
                     (cond-> {:command/id (random-uuid)
                              :command/timestamp (time/now)
                              :command/name :sheet/store-execution-trace
                              :trace-id trace-id
                              :sheet-id sheet-id
                              :started-at (str (or started-at (time/now)))
                              :completed-at (str (or completed-at (time/now)))
                              :duration-ms duration-ms
                              :status final-status
                              :input-snapshot input-snapshot
                              :output-snapshot output-snapshot
                              :node-traces node-traces}
                       version-number (assoc :version-number version-number)
                       (:error event) (assoc :error (:error event)))))
            (catch Exception _e
              ;; Log but don't fail — trace storage is best-effort
              nil)))
        ;; No events to emit directly
        nil))))

;; =============================================================================
;; Todo Processor Registry
;; =============================================================================

;; =============================================================================
;; Snapshot Restore Processor
;; =============================================================================

(defn- flatten-snapshot-nodes
  "Flatten a nested snapshot tree into a list of [node parent-id] pairs,
   in creation order (parent before children)."
  [snapshot-node parent-id]
  (when snapshot-node
    (let [node-id (random-uuid)
          node-record [node-id parent-id snapshot-node]
          children (or (:children snapshot-node) [])]
      (cons node-record
            (mapcat #(flatten-snapshot-nodes % node-id) children)))))

(defn- collect-deletion-order
  "Collect nodes in deletion order (children before parents, leaf-first).
   Returns vector of node-ids to delete."
  [nodes-by-id root-id]
  (letfn [(collect [node-id]
            (let [node (get nodes-by-id node-id)
                  children-ids (:children-ids node)]
              (concat (mapcat collect children-ids)
                      [node-id])))]
    (when root-id
      (vec (collect root-id)))))

(defn restore-from-snapshot
  "Restore sheet state from a version or stash snapshot.
   Handles both :sheet/draft-reverted and :sheet/stash-restored events.

   This generates all events needed to:
   1. Delete all existing nodes (leaf-first)
   2. Delete all existing blackboard keys
   3. Recreate nodes from snapshot
   4. Recreate blackboard schema from snapshot"
  [{:keys [event event-store] :as context}]
  (let [event-type (:event/type event)
        sheet-id (:sheet-id event)
        snapshot (:snapshot event)]
    (when (and (#{:sheet/draft-reverted :sheet/stash-restored} event-type)
               snapshot)
      (let [;; Get current state to delete
            current-nodes-by-id (rm/get-nodes-by-id context sheet-id)
            current-sheet (rm/get-sheet context sheet-id)
            current-root-id (:root-node-id current-sheet)
            current-blackboard (rm/get-blackboard-by-key context sheet-id)

            ;; Generate deletion events for nodes (leaf-first order)
            node-deletion-ids (collect-deletion-order current-nodes-by-id current-root-id)
            node-deletion-events (mapv (fn [node-id]
                                         (->event
                                          {:type :sheet/node-deleted
                                           :tags #{[:sheet sheet-id]
                                                   [:node node-id]}
                                           :body {:sheet-id sheet-id
                                                  :node-id node-id}}))
                                       node-deletion-ids)

            ;; Generate deletion events for blackboard keys
            key-deletion-events (mapv (fn [[k _]]
                                        (->event
                                         {:type :sheet/key-deleted
                                          :tags #{[:sheet sheet-id]}
                                          :body {:sheet-id sheet-id
                                                 :key k}}))
                                      current-blackboard)

            ;; Extract snapshot data
            snapshot-nodes (:nodes snapshot)
            blackboard-schema (:blackboard-schema snapshot)

            ;; Generate key declaration events
            key-declaration-events (mapv (fn [[k schema]]
                                           (->event
                                            {:type :sheet/key-declared
                                             :tags #{[:sheet sheet-id]}
                                             :body {:sheet-id sheet-id
                                                    :key k
                                                    :schema schema}}))
                                         blackboard-schema)

            ;; Flatten snapshot tree to get node creation order
            node-records (flatten-snapshot-nodes snapshot-nodes nil)

            ;; Generate node creation and configuration events
            node-events (mapcat
                         (fn [[node-id parent-id snapshot-node]]
                           (let [node-type (:type snapshot-node)
                                 create-event (->event
                                               {:type :sheet/node-created
                                                :tags #{[:sheet sheet-id]
                                                        [:node node-id]}
                                                :body (cond-> {:sheet-id sheet-id
                                                               :node-id node-id
                                                               :type node-type}
                                                        parent-id (assoc :parent-id parent-id))})
                                 ;; Name event
                                 name-event (when (:name snapshot-node)
                                              (->event
                                               {:type :sheet/node-name-set
                                                :tags #{[:sheet sheet-id]
                                                        [:node node-id]}
                                                :body {:sheet-id sheet-id
                                                       :node-id node-id
                                                       :name (:name snapshot-node)}}))
                                 ;; Configuration events based on node type
                                 config-events
                                 (case node-type
                                   :leaf
                                   (filterv some?
                                            [(when (:instruction snapshot-node)
                                               (->event
                                                {:type :sheet/node-instruction-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body {:sheet-id sheet-id
                                                        :node-id node-id
                                                        :instruction (:instruction snapshot-node)}}))
                                             (when (or (seq (:reads snapshot-node))
                                                       (seq (:writes snapshot-node)))
                                               (->event
                                                {:type :sheet/node-io-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body {:sheet-id sheet-id
                                                        :node-id node-id
                                                        :reads (or (:reads snapshot-node) [])
                                                        :writes (or (:writes snapshot-node) [])}}))
                                             (when (:executor snapshot-node)
                                               (->event
                                                {:type :sheet/node-executor-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body (cond-> {:sheet-id sheet-id
                                                                :node-id node-id
                                                                :executor (:executor snapshot-node)}
                                                         (:model snapshot-node) (assoc :model (:model snapshot-node))
                                                         (:fn snapshot-node) (assoc :fn (:fn snapshot-node))
                                                         (:tools snapshot-node) (assoc :tools (:tools snapshot-node)))}))
                                             (when (:retry snapshot-node)
                                               (->event
                                                {:type :sheet/node-retry-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body {:sheet-id sheet-id
                                                        :node-id node-id
                                                        :retry (:retry snapshot-node)}}))])

                                   :condition
                                   (filterv some?
                                            [(when (:check snapshot-node)
                                               (->event
                                                {:type :sheet/node-check-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body {:sheet-id sheet-id
                                                        :node-id node-id
                                                        :check (:check snapshot-node)}}))])

                                   :llm-condition
                                   (filterv some?
                                            [(when (or (:instruction snapshot-node)
                                                       (seq (:reads snapshot-node)))
                                               (->event
                                                {:type :sheet/llm-condition-config-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body (cond-> {:sheet-id sheet-id
                                                                :node-id node-id
                                                                :instruction (or (:instruction snapshot-node) "")
                                                                :reads (or (:reads snapshot-node) [])}
                                                         (:model snapshot-node) (assoc :model (:model snapshot-node)))}))])

                                   :parallel
                                   [(->event
                                     {:type :sheet/parallel-config-set
                                      :tags #{[:sheet sheet-id] [:node node-id]}
                                      :body {:sheet-id sheet-id
                                             :node-id node-id
                                             :success-policy (or (:success-policy snapshot-node) :all)
                                             :failure-policy (or (:failure-policy snapshot-node) :any)}})]

                                   :map-each
                                   (filterv some?
                                            [(when (and (:source-key snapshot-node)
                                                        (:item-key snapshot-node)
                                                        (:output-key snapshot-node))
                                               (->event
                                                {:type :sheet/map-each-config-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body (cond-> {:sheet-id sheet-id
                                                                :node-id node-id
                                                                :source-key (:source-key snapshot-node)
                                                                :item-key (:item-key snapshot-node)
                                                                :output-key (:output-key snapshot-node)}
                                                         (:max-concurrency snapshot-node)
                                                         (assoc :max-concurrency (:max-concurrency snapshot-node)))}))])

                                   :delegate
                                   (filterv some?
                                            [(when (:target-sheet-id snapshot-node)
                                               (->event
                                                {:type :sheet/delegate-config-set
                                                 :tags #{[:sheet sheet-id] [:node node-id]}
                                                 :body (cond-> {:sheet-id sheet-id
                                                                :node-id node-id
                                                                :target-sheet-id (:target-sheet-id snapshot-node)
                                                                :reads (or (:reads snapshot-node) [])
                                                                :writes (or (:writes snapshot-node) [])}
                                                         (:delegate-timeout-ms snapshot-node)
                                                         (assoc :timeout-ms (:delegate-timeout-ms snapshot-node))
                                                         (some? (:inherit-ontology? snapshot-node))
                                                         (assoc :inherit-ontology? (:inherit-ontology? snapshot-node)))}))])

                                   ;; sequence, fallback, repl-researcher - no extra config needed
                                   [])]
                             (filterv some? (into [create-event name-event] config-events))))
                         node-records)]

        {:result/events
         (vec (concat node-deletion-events
                      key-deletion-events
                      key-declaration-events
                      node-events))}))))

;; =============================================================================
;; Processor Registration (defprocessor delegates to existing handler fns)
;; =============================================================================

(defprocessor :sheet start-tree-tick
  {:topics #{:sheet/tree-tick-started}}
  "Start tick execution when tree tick begins."
  [context]
  (execute-tree-tick context))

(defprocessor :sheet execute-leaf-node
  {:topics #{:sheet/node-execution-started}}
  "Execute leaf nodes when node execution starts."
  [context]
  (execute-leaf-node context))

(defprocessor :sheet execute-condition-node
  {:topics #{:sheet/node-execution-started}}
  "Execute condition nodes when node execution starts."
  [context]
  (execute-condition-node context))

(defprocessor :sheet execute-composite-node
  {:topics #{:sheet/node-execution-started}}
  "Execute composite nodes (sequence/fallback) when node execution starts."
  [context]
  (execute-composite-node context))

(defprocessor :sheet execute-parallel-node
  {:topics #{:sheet/node-execution-started}}
  "Execute parallel nodes when node execution starts."
  [context]
  (execute-parallel-node context))

(defprocessor :sheet execute-map-each-node
  {:topics #{:sheet/node-execution-started}}
  "Execute map-each nodes when node execution starts."
  [context]
  (execute-map-each-node context))

(defprocessor :sheet execute-repl-researcher-node
  {:topics #{:sheet/node-execution-started}}
  "Execute repl-researcher nodes when node execution starts."
  [context]
  (execute-repl-researcher-node context))

(defprocessor :sheet execute-delegate-node
  {:topics #{:sheet/node-execution-started}}
  "Execute delegate nodes when node execution starts."
  [context]
  (execute-delegate-node context))

(defprocessor :sheet handle-child-completion
  {:topics #{:sheet/node-execution-completed}}
  "Handle child completion for composite nodes."
  [context]
  (handle-child-completion context))

(defprocessor :sheet handle-map-each-child-completion
  {:topics #{:sheet/node-execution-completed}}
  "Handle map-each child iteration completion."
  [context]
  (handle-map-each-child-completion context))

(defprocessor :sheet update-blackboard
  {:topics #{:sheet/node-execution-completed}}
  "Update blackboard on node completion."
  [context]
  (update-blackboard-on-completion context))

(defprocessor :sheet complete-tree-tick
  {:topics #{:sheet/node-execution-completed}}
  "Complete tree tick when root node completes."
  [context]
  (complete-tree-tick context))

(defprocessor :sheet restore-from-snapshot
  {:topics #{:sheet/draft-reverted :sheet/stash-restored}}
  "Restore sheet state from a version or stash snapshot."
  [context]
  (restore-from-snapshot context))

(defprocessor :sheet deliver-execution-result
  {:topics #{:sheet/tree-tick-completed}}
  "Deliver execution result to waiting promises (bridges async to sync)."
  [context]
  (deliver-execution-result context))

(defprocessor :sheet deliver-cancellation
  {:topics #{:sheet/tick-cancelled}}
  "Unblock callers waiting on a cancelled tick."
  [context]
  (deliver-cancellation context))

(defprocessor :sheet assemble-execution-trace
  {:topics #{:sheet/tree-tick-completed}}
  "Assemble and store execution trace from events."
  [context]
  (assemble-execution-trace context))

;; =============================================================================
;; RLM Rolling Judge — RETIRED in Gap-2
;; =============================================================================
;;
;; The standalone `:rlm evaluate-rlm-tree` processor (heuristic-only
;; structural eval of model-generated trees) is no longer here. Its
;; functionality moved to the unified evaluator protocol:
;;
;;   - Heuristic re-extracted to:
;;     `components/evaluation/src/.../core/heuristic_structural.clj`
;;   - Wired as the `:heuristic-structural` judge type in:
;;     `components/evaluation/src/.../core/judge_runtime.clj`
;;   - Consumers attach it via `:sheet/set-node-judges` to specific
;;     leaf nodes. The judge fires when the host node's :writes carry
;;     a `:generated-tree-raw` entry — what a repl-researcher's Phase 1
;;     emit-tree! produces.
;;
;; The `:rlm/tree-evaluated` event TYPE remains in the schema registry
;; for backward compatibility with any external consumer, but no code
;; in this repo emits it. New evaluations land as `:judge/score-emitted`
;; events with `:judge-name "<consumer-chosen-name>"` and
;; `:judge-config {:type :heuristic-structural}`.
