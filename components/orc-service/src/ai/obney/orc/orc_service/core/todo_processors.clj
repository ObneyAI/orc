(ns ai.obney.orc.orc-service.core.todo-processors
  "Behavior Tree Sheet todo processors (policies).

   These are event-driven side effects that respond to domain events:
   - Execute leaf nodes when tree tick starts
   - Handle sequence/fallback/parallel composite node logic
   - Handle map-each iteration
   - Update blackboard with node outputs"
  (:require [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.core.block :as block]
            [ai.obney.orc.orc-service.core.rlm-tree-executor :as tree-executor]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.streaming :as streaming]
            [ai.obney.orc.orc-service.core.profile :as profile]
            [ai.obney.orc.orc-service.core.value-log :as value-log]
            [ai.obney.orc.orc-service.core.value-storage :as value-storage]
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

;; A budget can be exceeded concurrently by sibling leaves. Reserve each
;; cancellation dispatch once so those leaves cannot append duplicate terminal
;; events before the first cancellation has reached the ticks projection.
(defonce ^:private budget-cancellation-claims (atom #{}))

(defn- claim-budget-cancellation!
  [tick-id]
  (let [claimed? (atom false)]
    (swap! budget-cancellation-claims
           (fn [claims]
             (if (contains? claims tick-id)
               claims
               (do (reset! claimed? true)
                   (conj claims tick-id)))))
    @claimed?))

(defn clear-llm-count!
  "Clear LLM count for a tick (called on tick completion)."
  [tick-id]
  (swap! tick-llm-counts dissoc tick-id)
  (swap! budget-cancellation-claims disj tick-id))

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

(defn- reserve-llm-call!
  "Atomically reserve one call against the root tick's shared LLM budget.
   Returns nil when reserved, or an exceeded descriptor when no capacity
   remains. With no explicit budget, records the call without imposing a cap.

   Takes the ALREADY-PROJECTED tick execution context rather than projecting
   its own. Every projection of :sheet/tick-execution-contexts decodes the
   tick's cached blob, and callers here already hold it."
  [tick-ctx tick-id]
  (let [budget (get-in tick-ctx [:options :llm-call-budget])
        budget-tick-id (or (get-in tick-ctx [:options :llm-budget-root-tick-id])
                           tick-id)
        budget-sheet-id (get-in tick-ctx [:options :llm-budget-root-sheet-id])
        outcome (atom nil)]
    (swap! tick-llm-counts
           (fn [counts]
             (let [current (get counts budget-tick-id 0)]
               (if (and budget (>= current budget))
                 (do (reset! outcome {:exceeded true
                                      :current current
                                      :budget budget
                                      :root-tick-id budget-tick-id
                                      :root-sheet-id budget-sheet-id})
                     counts)
                 (assoc counts budget-tick-id (inc current))))))
    @outcome))

(defn- reserve-llm-call-or-cancel!
  "Reserve one call and durably cancel both the active and root ticks when the
   shared root budget is exhausted. Returns the exceeded descriptor, or nil."
  [context tick-ctx sheet-id tick-id]
  (when-let [exceeded (reserve-llm-call! tick-ctx tick-id)]
    (let [reason (str "LLM call budget exceeded: "
                      (:current exceeded) "/" (:budget exceeded))]
      ;; Every started tick owns its terminal event. Cancel the active child
      ;; first, then the shared-budget root when they differ. Root-node
      ;; completions racing either cancellation are fenced by
      ;; complete-tree-tick and cannot append a second terminal event.
      (when (claim-budget-cancellation! tick-id)
        (cp/process-command
         (assoc context :command
                {:command/id (random-uuid)
                 :command/timestamp (time/now)
                 :command/name :sheet/cancel-tick
                 :sheet-id sheet-id
                 :tick-id tick-id
                 :reason reason})))
      (when (and (:root-sheet-id exceeded)
                 (:root-tick-id exceeded)
                 (not= tick-id (:root-tick-id exceeded))
                 (claim-budget-cancellation! (:root-tick-id exceeded)))
        (cp/process-command
         (assoc context :command
                {:command/id (random-uuid)
                 :command/timestamp (time/now)
                 :command/name :sheet/cancel-tick
                 :sheet-id (:root-sheet-id exceeded)
                 :tick-id (:root-tick-id exceeded)
                 :reason reason})))
      exceeded)))

;; =============================================================================
;; Tick-Scoped Resolution Helpers
;; =============================================================================
;;
(defn- resolve-nodes-by-id
  "Get nodes-by-id for a tick from tick-scoped execution context."
  [ctx _sheet-id tick-id]
  (rm/get-tick-nodes-by-id ctx tick-id))

(defn- resolve-blackboard
  "Get blackboard METADATA for a tick from tick-scoped execution context.
   Entries carry key, schema, version, size profile and (for written keys) a
   :source — but NO :value. Use hydrate-values when values are
   needed."
  [ctx _sheet-id tick-id]
  (rm/get-tick-blackboard ctx tick-id))

(defn- hydrate-values
  "Fill in :value on a metadata blackboard for `ks` (nil = every key),
   resolving each from the canonical write log.

   Ask for the keys you actually need. A node's declared :reads is almost
   always the right set — the exceptions are repl-researcher (whose sandbox
   can read any key by name at runtime) and result delivery (which owes the
   caller the whole blackboard).

   nil ks means EVERY key, so narrowing call sites must pass (or (:reads node)
   []) rather than (:reads node) — a node that declares no reads would
   otherwise silently hydrate the whole blackboard."
  [{:keys [tenant-id] :as context} tick-id metadata-bb ks]
  (value-log/hydrate-blackboard context tenant-id tick-id metadata-bb ks))

(defn- resolve-blackboard-values
  "Project a tick's blackboard metadata and hydrate `ks` in one step, for
   handlers that do not already hold the projected context."
  [ctx sheet-id tick-id ks]
  (hydrate-values ctx tick-id (resolve-blackboard ctx sheet-id tick-id) ks))

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
            behaviors (:behaviors behavioral-result)
            ;; EL-3 (ADR 0015): detect-and-defer. When the structural OR
            ;; behavioral classification :outcome is :uncertain (a reranker
            ;; fallback — we do NOT know the class), SKIP the
            ;; :ontology/assign-task-class dispatch entirely: no fresh-mint
            ;; event, no class creation/accrual. We still set the node
            ;; :context below so the R-Inject reranker-fallback caution
            ;; surfaces to the model. :matched / :novel dispatch as today.
            ;; The defeat condition (ADR 0015) is an :uncertain outcome that
            ;; still mints/assigns — so the dispatch is gated here, not via a
            ;; schema throw on a nil :assigned-tree-id.
            uncertain? (or (= :uncertain (:outcome result))
                           (= :uncertain (:outcome behavioral-result)))]
        (if uncertain?
          (println (format "[DEBUG RLM] node '%s' auto-classify DEFERRED (outcome :uncertain — struct=%s behav=%s) — NO assign-task-class dispatched"
                           (or (:name node) (str (:id node)))
                           (:outcome result)
                           (:outcome behavioral-result)))
          (do
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
            ;; CV-1 (ADR 0017) Part 1 — CONVERGENCE CAPTURE. On a fresh-mint
            ;; (and only then — we are already past the :uncertain guard), ALSO
            ;; record a provisional :tree-class description whose SEARCHABLE
            ;; content is the task `signature` (the same instruction-aware text
            ;; classify-task searched by). This puts the just-minted class into
            ;; the corpus immediately, so the NEXT identical/similar signature
            ;; retrieves + matches it (accrues) instead of scattering a new
            ;; random-uuid — the capture EL-1b specified but never built.
            ;; ONLY on fresh-mint: a MATCH or BUNDLE (:was-fresh-mint? false)
            ;; records NOTHING, so a recurrence does not re-emit a description
            ;; and thrash the ColBERT reindex (see orc-sheet-content-hash-thrash).
            ;; Reuses record-tree-class-description (keys on the fresh-mint root
            ;; UUID the classifier just assigned). Robust to turn timeouts — it
            ;; does not depend on the RLM completing.
            (when (:was-fresh-mint? result)
              (cp/process-command
                (assoc context :command
                       {:command/name :ontology/record-tree-class-description
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :target-id (:assigned-tree-id result)
                        :body {:summary signature
                               :capabilities []
                               :strengths []
                               :weaknesses []
                               :representative-uses []
                               :avoid-when []
                               :version 1
                               :consolidated-from-event-count 0}}))
              (println (format "[DEBUG RLM] node '%s' CONVERGENCE-CAPTURE recorded provisional :tree-class description for %s"
                               (or (:name node) (str (:id node)))
                               (:assigned-tree-id result))))))
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

(defn- fetch-behavioral-body
  "Pull a behavioral-subtree description body via ontology/get-description.

   E3 Part-1 (scope fix): behavioral seed bodies — and minted children —
   land in the Living-Description read-model under the :tree-fingerprint
   granularity, NOT :tree-class. Both emission paths stamp
   :target-type :tree-fingerprint on the description-updated event:
     - seed-baseline-corpus! emits behavioral seeds via
       :ontology/record-tree-description (commands.clj record-tree-
       description → :target-type :tree-fingerprint)
     - :ontology/mint-behavioral-subtree emits the same event with
       :target-type :tree-fingerprint, keyed by the derived stable id.
   (Structural tree-class seeds are DUAL-emitted under :tree-class for the
   structural read path — behavioral ones are not, so a behavioral id read
   against :tree-class misses → rich body nil → no strengths rendered.)

   This is the behavioral counterpart of fetch-tree-body: same UUID
   coercion + best-effort nil-on-failure semantics, but reads the
   :tree-fingerprint scope where behavioral bodies actually live. The
   structural fetch-tree-body path is deliberately left reading
   :tree-class (C-Loop-1) and is NOT changed."
  [ctx target-id]
  (when-let [uuid-target (->uuid target-id)]
    (let [get-description (requiring-resolve
                            'ai.obney.orc.ontology.interface/get-description)]
      (try
        (when get-description
          (get-description ctx :tree-fingerprint uuid-target))
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

(defn- rerank-source-caution
  "Per-candidate annotation naming WHY the reranker's score is missing.

   RR-1 (ADR 0020): the reranker's fallback is now stamped with WHICH
   failure it was — :colbert-fallback (it could not rank) vs
   :timeout-fallback (it ran out of budget, twice). Both mean the same
   thing for the reader — the score below is raw ColBERT similarity, not a
   grounded fitness — so both are cautioned; naming the reason keeps the
   distinction the signal now carries visible to the model and to anyone
   auditing the rendered prompt. Returns nil for a successful rerank."
  [rerank-source]
  (case rerank-source
    :colbert-fallback " [reranker fell back to ColBERT — treat with caution]"
    :timeout-fallback " [reranker timed out (retry included) and fell back to ColBERT — treat with caution]"
    nil))

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
         (rerank-source-caution rerank-source)
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
           "   SPECIALIZE vs ROOT-MINT: `:parent nil` mints a NEW root behavior. If, instead, one "
           "of the nearest references listed above is a broad-but-related parent (it shares the "
           "task's shape even though it fell below threshold), prefer specializing a CHILD of it — "
           "pass that reference's behavior-id as `:parent <id>` so the new behavior accrues evidence "
           "under the proven parent rather than scattering as an unrelated root.\n\n"
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
      (let [body (fetch-behavioral-body ctx behavior-id)
            summary (:summary body)
            rich (format-seed-body body traits-per-seed-cap)
            seed-name (derive-seed-name summary)
            header-label (if seed-name
                           (str (inc idx) ". Behavioral: " seed-name)
                           (str (inc idx) ". Behavioral suggestion"))]
        (str header-label " (confidence: "
             (format "%.2f" (double (or confidence 0.0))) ")"
             (rerank-source-caution rerank-source)
             "\n"
             "   Why this fits: " (or reasoning "(no reasoning recorded)") "\n\n"
             "   Guidance (seed `:summary`):\n"
             "   " (or summary reasoning "(no guidance recorded)") "\n\n"
             (or rich "")
             ;; E2 (ADR 0014, RG-3): references INFORM, they do not GATE. A
             ;; match clearing threshold is NOT a reason to suppress the
             ;; specialize/mint invitation — that gate is exactly why the
             ;; corpus stays shape-broad (mint fired 1/21: a "good enough"
             ;; parent is almost always found, so the not-found mint branch
             ;; never fired and no coding CHILD was ever born). The strengths/
             ;; weaknesses above are the EVIDENCE that informs the four-way
             ;; choice; :avoid-when in particular lets the model self-detect a
             ;; shape-over-match (a broad parent matching on shape, not domain).
             "\n   Adopt / adapt / SPECIALIZE / mint-adjacent — use the evidence above, not the score alone:\n"
             "   - ADOPT this pattern as-is if it is an EXACT fit for your task.\n"
             "   - ADAPT it (keep the shape, override the specifics) if it mostly fits.\n"
             "   - SPECIALIZE — if this is a BROAD fit rather than an exact one (its `:summary`/strengths"
             " describe a more general shape than your task, or its `:avoid-when` flags your context),"
             " mint a domain-specialized CHILD of THIS behavior. The child keeps the parent's proven shape"
             " but pins your domain, gets a STABLE derived id, and ACCRUES evidence under the parent"
             " (it is retrievable on subsequent classify-behaviors calls):\n\n"
             "       (mint-behavior! \"<short-kebab-name>\"\n"
             "                       {:capabilities [\"<concrete action verb + object>\"]\n"
             "                        :strengths [{:trait \"<what works AND why>\"\n"
             "                                     :good-when \"<context guard>\"\n"
             "                                     :recommended-pattern \"<concrete DSL snippet>\"\n"
             "                                     :confidence 0.7\n"
             "                                     :evidence-count 1}]\n"
             "                        :weaknesses [{:trait \"<what fails AND why>\"\n"
             "                                      :avoid-when \"<context guard>\"\n"
             "                                      :recommended-alternative \"<concrete fix>\"\n"
             "                                      :confidence 0.7\n"
             "                                      :evidence-count 1}]\n"
             "                        :representative-uses [\"<concrete task this child shipped on>\"]\n"
             "                        :avoid-when [\"<concrete anti-context>\"]\n"
             "                        :summary \"<2-3 sentences naming the domain + load-bearing trait + when to prefer this over the parent>\"\n"
             "                        :version 1\n"
             "                        :consolidated-from-event-count 0}\n"
             "                       :parent " behavior-id ")\n\n"
             "   - MINT-ADJACENT — if your task is related-but-DISTINCT (not a child of this behavior),"
             " mint an adjacent behavior the same way with `:parent nil` (or under whichever listed"
             " reference is the true nearest parent).\n"
             "   :strengths/:weaknesses are VECTORS OF MAPS (principle-shaped: :trait + context-guard +"
             " concrete recommended action + :confidence + :evidence-count) — a vector of bare strings"
             " fails schema validation and the mint is dropped silently.\n")))))

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

(defn- current-node-type-guidance
  "Read the current Living Description for this node type when the ontology
   component is present. R-Inject already marks the execution as opted into
   adaptive guidance; this adds the complementary node-type learning to the
   retrieved tree/behavior guidance without introducing a production
   dependency from orc-service to ontology."
  [ctx node]
  (when-let [node-type (:type node)]
    (try
      (when-let [get-description
                 (requiring-resolve
                  'ai.obney.orc.ontology.interface/get-description)]
        (when-let [body (get-description ctx :node-type node-type)]
          {:target-id node-type
           :version (:version body)
           :body body}))
      (catch Throwable _ nil))))

(defn- format-node-type-guidance [guidance]
  (when guidance
    (str "### Current learned guidance for node type `"
         (:target-id guidance) "` (version " (:version guidance) ")\n"
         "This is the current persisted Living Description for the executor "
         "you are using. Apply it alongside the retrieved corpus examples.\n"
         (pr-str (:body guidance)) "\n")))

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
            node-type-guidance (current-node-type-guidance ctx node)
            block (str "## Suggested patterns from corpus\n\n"
                       "These are concrete EXAMPLES retrieved from the seed corpus based on "
                       "classification of your task. Each example includes:\n"
                       "  - WHY the candidate fits (reranker reasoning)\n"
                       "  - The pattern's prose summary (seed `:summary`)\n"
                       "  - Capabilities it provides\n"
                       "  - Proven STRENGTHS — traits observed to work, each with a worked-example DSL snippet you can adapt\n"
                       "  - Observed WEAKNESSES — failure modes others hit, with the recommended fix\n"
                       "  - Representative uses where this pattern has shipped\n\n"
                       ;; E2 (ADR 0014, RG-3): the four moves are ALWAYS available —
                       ;; finding a match never removes specialize/mint. References
                       ;; inform the choice with evidence; they do not gate it.
                       "You always have FOUR moves, and the references below are EVIDENCE for choosing — not a mandate:\n"
                       "  - ADOPT — use a reference as-is when it is an EXACT fit.\n"
                       "  - ADAPT — keep a reference's pattern, override the specifics, when it mostly fits.\n"
                       "  - SPECIALIZE — mint a CHILD of the nearest reference (`mint-behavior!` with `:parent <that behavior-id>`) when the top hit is a BROAD shape rather than an exact fit; the child keeps the proven shape, pins your domain, and accrues evidence under the parent. This is the RECOMMENDED move when a match cleared threshold only on shape.\n"
                       "  - MINT — mint a fresh behavior (`:parent nil`) when the task is genuinely novel and no reference is a true parent.\n"
                       "A match clearing threshold does NOT mean adopt/adapt is your only option — weigh each reference's strengths AND its `:avoid-when` against THIS task, then pick the right move. Your job is the RIGHT tree for THIS task; the corpus is evidence, not gospel.\n\n"
                       (format-structural-section ctx structural)
                       "\n"
                       (format-behavioral-section ctx behavioral)
                       "\n"
                       (format-node-type-guidance node-type-guidance)
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
                           :classifier-payload
                           (cond-> payload
                             node-type-guidance
                             (assoc :node-type-guidance node-type-guidance))}))
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
  "Create a tick-scoped blackboard write event (isolated per execution).

   `attribution` is an optional {:node-id _ :exec-context _} naming the node
   EXECUTION this write belongs to. It matters most for map-each item writes:
   the parent emits them before starting each child, and all iterations share
   one blackboard key, so without attribution a consumer cannot tell which
   iteration a given item write was for — and the blackboard slot itself only
   ever holds the most recent one. With it, each item write is addressable by
   the same [node-id exec-context] identity every other write uses."
  ([context sheet-id tick-id key value]
   (make-bb-write-event context sheet-id tick-id key value nil))
  ([context sheet-id tick-id key value attribution]
   (->event
    {:type :sheet/execution-value-written
     :tags #{[:sheet sheet-id]
             [:tick tick-id]}
     :body (value-storage/prepare-write
            context
            (cond-> {:tick-id tick-id
                     :sheet-id sheet-id
                     :key key
                     :value value}
              (:node-id attribution) (assoc :node-id (:node-id attribution))
              (seq (:exec-context attribution))
              (assoc :exec-context (:exec-context attribution))
              ;; Direction matters. A map-each child often reads AND writes the
              ;; same item key, so "attributed to execution E" is ambiguous
              ;; without it: is this the value E was given, or the value E
              ;; produced? :input-seed? marks the former.
              (:input-seed? attribution) (assoc :input-seed? true)))})))

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
      ;; :inputs carries only what cannot be resolved from the tick
      ;; blackboard — execution context and map-each item overrides. The
      ;; root's declared reads are already in the blackboard and
      ;; execute-leaf-node resolves them there.
      {:result/events
       [(->event
         {:type :sheet/node-execution-started
          :tags #{[:sheet sheet-id]
                  [:node root-id]
                  [:tick tick-id]}
          :body {:sheet-id sheet-id
                 :tick-id tick-id
                 :node-id root-id
                 ;; A re-tick reuses the durable tick identity, so iteration
                 ;; must participate in node-execution identity. Without it,
                 ;; duplicate-completion defense mistakes iteration N's
                 ;; parent completion for iteration N+1 and suppresses the
                 ;; newly started tree.
                 :inputs (cond-> {}
                           (> (or (:iteration event) 1) 1)
                           (assoc ::tick-iteration (:iteration event)))}})]})))

;; =============================================================================
;; Node Execution Processor
;; =============================================================================

;; Default provider - set to nil to use mock, or :openrouter etc for real execution
(def ^:dynamic *default-llm-provider* :openrouter)

(defn- extract-execution-context
  "Extract map-each execution context from inputs.
   Returns a map with only the context keys needed for correlation."
  [inputs]
  (select-keys inputs [::map-each-index ::map-each-parent ::tick-iteration]))

;; =============================================================================
;; Input Profile Computation (deep module — pure function, testable in isolation)
;; =============================================================================

(def profile-value
  "See core.profile — extracted so the command side can profile too."
  profile/profile-value)

(def profile-values profile/profile-values)

(def compute-input-profile profile/compute-input-profile)

;; Per-read-value cap for trace :inputs capture. A grounding judge needs enough
;; of each read value to verify the answer was built from it — not megabytes.
;; Large string reads (e.g. full-file contents) are truncated to this many chars.
(def ^:private max-trace-input-chars 20000)

(defn- truncate-trace-value
  "Truncate a single read value for inclusion in a trace's :inputs.
   Strings longer than max-trace-input-chars are cut with a marker; other
   values pass through unchanged (vectors/maps are bounded structurally by
   the reads themselves and stay small in practice)."
  [v]
  (if (and (string? v) (> (count v) max-trace-input-chars))
    (str (subs v 0 max-trace-input-chars)
         "\n…[truncated " (- (count v) max-trace-input-chars) " chars]")
    v))

(defn read-sources
  "For each of a node's declared :reads, the id of the write event that
   produced the value the node is about to see: {read-key event-id}.

   Returns {} when `exec-context` is non-empty — i.e. inside a map-each
   iteration. The blackboard holds ONE :source per key, and
   concurrent iterations sharing the item key clobber it, so any id captured
   here would name an arbitrary iteration's write. The iteration's own
   exec-context is the reliable identity and travels on both the completion
   and the item write; recording a plausible-but-wrong id alongside it is
   worse than recording nothing, because a consumer would trust it.

   Recorded on the completion event so a consumer can rehydrate the node's
   inputs EXACTLY. Resolving a read by key name alone yields the last write
   to that key in the tick, which — when a key is written more than once —
   can be a value produced after this node had already finished.

   Keys with no entry are ones seeded into the tick rather than written
   during it (they have no write event); consumers fall back to the tick's
   seeded inputs for those. Pure function — testable in isolation."
  ([reads blackboard] (read-sources reads blackboard nil))
  ([reads blackboard exec-context]
   (if (seq exec-context)
     {}
     (reduce (fn [acc k]
               (if-let [src (get-in blackboard [k :source])]
                 (assoc acc k src)
                 acc))
             {}
             (or reads [])))))

(defn extract-read-inputs
  "Build the {read-key -> value} map a node actually read from the blackboard,
   restricted to the node's declared :reads. Large string values are truncated
   (see max-trace-input-chars) so the eval trace carries real grounding context
   without bloating events. Returns {} when reads is empty or no values present.

   This is the honest-grounding capture: control nodes (sequence/fallback)
   emit a non-leaf child's node-execution-started with only the map-each
   context, so the synthesis/answer node's real inputs were dropped from the
   trace. Capturing them at execution time — when the blackboard is fully
   resolved (prior siblings' writes already merged) — gives judges the
   evidence the answer was grounded on. Pure function — testable in isolation."
  [reads blackboard]
  (reduce
    (fn [acc k]
      (let [v (get-in blackboard [k :value])]
        (if (nil? v)
          acc
          (assoc acc k (truncate-trace-value v)))))
    {}
    (or reads [])))

(defn execute-leaf-node
  "Execute a leaf node when node-execution-started is emitted.
   Supports multiple executor types:
   - :ai - ORC LLM AI execution (default)
   - :code - Clojure function execution
   - :tool - Direct tool invocation

   Runs execution in a future to avoid blocking the pubsub thread.
   Uses cp/process-command to emit completion events."
  [{:keys [event event-store llm-provider] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        event-inputs (:inputs event)
        ;; ONE projection for the whole handler. Every
        ;; :sheet/tick-execution-contexts projection decodes the tick's cached
        ;; blob, and this fn used to take five of them (nodes-by-id, overrides,
        ;; blackboard, tool-context, budget) for a single leaf.
        tick-ctx (rm/get-tick-execution-context context tick-id)
        nodes-by-id (:nodes-by-id tick-ctx)
        overrides (:instruction-overrides tick-ctx)
        node (-> (get nodes-by-id node-id)
                 (apply-instruction-override overrides)
                 ;; R-Inject: leaves don't carry :r05-classifier (only the
                 ;; repl-researcher path runs the wedge), so this is a
                 ;; no-op for leaves. Calling the same helper everywhere
                 ;; gives the pipeline a single context-prepend contract.
                 (apply-r05-classifier-context context))]
    (when (= :leaf (:type node))
      ;; Hydrate only what this leaf declared it reads. gather-inputs,
      ;; extract-read-inputs and compute-input-profile all restrict themselves
      ;; to (:reads node); read-sources needs only :source, which is
      ;; metadata. Order matters: hydrate FIRST, then overlay event-inputs —
      ;; a map-each item value arrives on the started event and must win over
      ;; whatever the shared item key resolves to.
      (let [raw-blackboard (hydrate-values context tick-id (:blackboard tick-ctx)
                                           (or (:reads node) []))
            ;; Merge event inputs into blackboard (e.g., map-each item values)
            blackboard (reduce (fn [bb [k v]]
                                 (if (and (keyword? k) (not (= (namespace k) (namespace ::_))))
                                   (assoc-in bb [k :value] v)
                                   bb))
                               raw-blackboard
                               event-inputs)
            ;; G1 (ADR 0018): read the opaque :tool-context that rode the
            ;; tick-execution-context across the async boundary and assoc it
            ;; onto the context handed to execute-leaf -> execute-code, so the
            ;; leaf fn sees (:tool-context ctx). Absent -> leaf-context is the
            ;; unchanged context (no behavior change for non-coding leaves).
            ;; Works at any depth: composites resolve leaves through the same
            ;; per-tick context.
            tool-context (:tool-context tick-ctx)
            leaf-context (cond-> (assoc (merge context
                                                (runtime/ephemeral-context-for tick-id))
                                        :tick-options (:options tick-ctx))
                           tool-context (assoc :tool-context tool-context))
            ;; Use provider from context, fall back to default, or use mock if nil
            provider (or llm-provider *default-llm-provider*)
            executor-type (or (:executor node) :ai)
            ;; Extract execution context for correlation
            exec-context (extract-execution-context event-inputs)
            ;; Check LLM budget ONLY for AI executor types (not code)
            is-llm-call? (and (#{:ai :repl-researcher} executor-type) provider)
            ;; Stage 2 token streaming: only built when a live subscriber
            ;; opted into deltas for this tick. execute-ai falls back to
            ;; blocking predict when nil (or when ORC LLM lacks
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
        (if-let [_exceeded (and is-llm-call?
                                (reserve-llm-call-or-cancel!
                                 context tick-ctx sheet-id tick-id))]
          ;; The shared helper has already durably cancelled the active tick
          ;; and, when different, its shared-budget root.
          nil
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
                    raw-result (cond
                             ;; Code executor doesn't need provider
                             (= :code executor-type)
                             (executor/execute-leaf node blackboard nil
                                                    :context leaf-context)
                             ;; AI executor with provider
                             provider
                             (executor/execute-leaf node blackboard provider
                                                    :context leaf-context
                                                    :stream stream-cfg)
                             ;; No provider - use mock
                             :else
                             (executor/execute-leaf-mock node blackboard))
                    result (executor/validate-leaf-outputs blackboard raw-result is-llm-call?)
                  {:keys [status outputs rejected-writes error duration-ms usage raw-response block-payload]} result
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
                                ;; C-2a-2: the per-node-type rolling-metrics
                                ;; aggregator is (if-let [nt (:node-type event)] ...),
                                ;; so omitting this silently drops the execution
                                ;; from the metric rather than failing.
                                :node-type (:type node)
                                :status status
                                :writes (normalize-output-keys (or outputs {}))}
                         (seq rejected-writes)
                         (assoc :rejected-writes (normalize-output-keys rejected-writes))
                         duration-ms (assoc :duration-ms duration-ms)
                         error (assoc :error error)
                         ;; WS-2a: carry the OPAQUE block payload through on a
                         ;; :blocked completion so it propagates up the tree to
                         ;; the caller's result. orc never interprets it.
                         (= :blocked status) (assoc :block-payload block-payload)
                         ;; Verbatim raw LLM response on parse failures —
                         ;; persisted on the completion event so (node-output
                         ;; <node-id>) can retrieve the full text for diagnosis.
                         (and (= :failure status) raw-response)
                         (assoc :raw-response raw-response)
                         ;; The only place a leaf's reads are observed —
                         ;; node-execution-started does not inline them. Omitting
                         ;; this leaves the trace with no :read-keys and grounding
                         ;; judges scoring against {}. complete-node-execution
                         ;; reduces these to :read-keys + :input-profile before
                         ;; they reach the event, so it costs no storage.
                         :always
                         (as-> cmd
                               (let [reads (extract-read-inputs (:reads node) blackboard)]
                                 (cond-> cmd
                                   (or (seq exec-context) (seq reads))
                                   (assoc :inputs (merge exec-context reads))
                                   ;; Which write each read resolved to, so
                                   ;; rehydration is an exact lookup rather than
                                   ;; a last-write-wins guess on the key name.
                                   (seq reads)
                                   (assoc :read-sources (read-sources (:reads node) blackboard exec-context)))))
                         (seq usage) (assoc :usage usage)
                         (and is-llm-call? (:model node))
                         (assoc :model (:model node)))))
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
            ;; WS-2a: catch Throwable (not just Exception) so a block signal
            ;; that escapes execute-code's own catch (e.g. thrown from an :ai
            ;; leaf or fn resolution) still COMPLETES the node :blocked rather
            ;; than dying in the future and hanging the tick. Preserves today's
            ;; behavior for everything else: an ordinary Exception -> :failure;
            ;; a non-blocking Error/Throwable -> re-thrown exactly as before
            ;; (it escaped the future previously too — no new swallowing).
            (catch Throwable t
              (cond
                (block/blocking-condition? t)
                (cp/process-command
                  (assoc context :command
                         {:command/id (random-uuid)
                          :command/timestamp (time/now)
                          :command/name :sheet/complete-node-execution
                          :sheet-id sheet-id
                          :tick-id tick-id
                          :node-id node-id
                          :node-type (:type node)
                          :status :blocked
                          :writes {}
                          :block-payload (block/block-payload t)}))

                (instance? Exception t)
                (cp/process-command
                  (assoc context :command
                         {:command/id (random-uuid)
                          :command/timestamp (time/now)
                          :command/name :sheet/fail-node-execution
                          :sheet-id sheet-id
                          :tick-id tick-id
                          :node-id node-id
                          :error (.getMessage t)}))

                :else (throw t)))))))
        ;; Return nil - completion will be handled by the future via process-command
        nil))))

;; =============================================================================
;; REPL Researcher Node Execution Processor
;; =============================================================================

(defn execute-repl-researcher-node
  "Execute a repl-researcher node when node-execution-started is emitted.
   Runs in a future like leaf/llm-condition nodes to avoid blocking pubsub."
  [{:keys [event event-store llm-provider] :as context}]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        event-inputs (:inputs event)
        ;; ONE projection for the whole handler — see execute-leaf-node.
        tick-ctx (rm/get-tick-execution-context context tick-id)
        nodes-by-id (:nodes-by-id tick-ctx)
        overrides (:instruction-overrides tick-ctx)
        ;; RR-1 (ADR 0020): the node as it comes off the tick snapshot. The
        ;; auto-classify wedge + R-Inject used to run HERE, synchronously, on
        ;; the dispatch thread — contradicting this function's own docstring
        ;; and letting one slow classify->rerank call (measured live at 302s)
        ;; hold the thread the todo-processor delivers other node executions
        ;; on. They now run INSIDE the future below, still ahead of Phase-1
        ;; prompt assembly. Verified safe: nothing between here and the future
        ;; reads the wedge's output — the two consumers of `base-node` out
        ;; here are (:type …) and (:reads …), neither of which the wedge or
        ;; R-Inject touch (they set :context and prepend to :instruction),
        ;; and the only other reader of a node's :context,
        ;; apply-ontology-context, is the legacy path R-Inject replaced (it
        ;; has no call sites left anywhere in the workspace).
        base-node (-> (get nodes-by-id node-id)
                      (apply-instruction-override overrides))]
    (when (= :repl-researcher (:type base-node))
      ;; The ONLY site that hydrates everything, and it has to: the RLM
      ;; sandbox's get-input takes an arbitrary key chosen at runtime by
      ;; model-authored code, and build-available-variables-section previews
      ;; every [k v] in the blackboard. There is no declared read set to
      ;; narrow to. One bulk resolve, on the heaviest node type in the system.
      (let [raw-blackboard (hydrate-values context tick-id (:blackboard tick-ctx) nil)
            blackboard (reduce (fn [bb [k v]]
                                 (if (and (keyword? k) (not (= (namespace k) (namespace ::_))))
                                   (assoc-in bb [k :value] v)
                                   bb))
                               raw-blackboard
                               event-inputs)
            provider (or llm-provider *default-llm-provider*)
            exec-context (extract-execution-context event-inputs)
            ;; Honest-grounding capture: the values this node actually read
            ;; from the (fully-resolved) blackboard, restricted to its declared
            ;; :reads and truncated for size. Control-node started events drop a
            ;; non-leaf child's reads, so trace assembly would otherwise default
            ;; the synthesis node's :inputs to {} and judges score grounded
            ;; answers as hallucinations. We carry these into the completion
            ;; event; assemble-execution-trace prefers them over the (empty)
            ;; started inputs.
            read-inputs (extract-read-inputs (:reads base-node) blackboard)]
        (future
          (try
            ;; C-Loop-3: thread sheet-id / tick-id / cache through to
            ;; execute-repl-researcher so the recursive RLM sandbox's
            ;; mint-behavior! + get-description SCI bindings have the
            ;; command context they need to dispatch + read the
            ;; descriptions read-model. Without these, mint-behavior!
            ;; throws "requires a command context" and the agent's
            ;; mint call is lost.
            (let [;; RR-1 (ADR 0020): auto-classify + R-Inject, moved OFF the
                  ;; dispatch thread into this future — still ahead of Phase-1
                  ;; prompt assembly, which is the only thing that consumes
                  ;; them. A slow classify->rerank now costs THIS turn's
                  ;; latency and nothing else.
                  wedge-ctx (assoc context :sheet-id sheet-id :tick-id tick-id)
                  node (-> base-node
                           (maybe-auto-classify-and-set-context wedge-ctx)
                           ;; R-Inject: replaces the legacy
                           ;; apply-ontology-context call. The wedge stashes
                           ;; R05's full classifier payload on :context; this
                           ;; helper prepends a principle-shaped "Suggested
                           ;; patterns from corpus" block to :instruction so
                           ;; the model designs trees informed by real corpus
                           ;; examples (with reasoning + seed :summary
                           ;; guidance). sheet-id rides on wedge-ctx so the
                           ;; helper can write the sidecar trace file the
                           ;; bench runner picks up.
                           (apply-r05-classifier-context wedge-ctx))
                  ;; CE-5b FIX B (ADR 0018): read the OPAQUE :tool-context that
                  ;; FIX A stored on THIS tick's execution-context read model
                  ;; (the same tick this repl-researcher node runs in) and
                  ;; thread it into the context handed to
                  ;; execute-repl-researcher -> execute-tree, whose Phase-2
                  ;; child tick re-threads it to the emitted leaf. Mirrors
                  ;; execute-leaf-node's read (rm/get-tick-execution-context).
                  ;; Absent -> enriched-context unchanged (backward-compatible).
                  ;; Taken from the handler's single projection: :tool-context
                  ;; is written once, on tree-tick-started, so it cannot change
                  ;; between dispatch and this future running.
                  tool-context (:tool-context tick-ctx)
                  correlation-id (:correlation-id tick-ctx)
                  enriched-context (cond-> (assoc context
                                                  :sheet-id sheet-id
                                                  :tick-id tick-id
                                                  :tick-options (:options tick-ctx)
                                                  ;; Recursive researchers make several
                                                  ;; provider calls inside one durable
                                                  ;; node execution. Charge each at its
                                                  ;; actual call boundary against the
                                                  ;; same root-tick budget.
                                                  :reserve-llm-call!
                                                  #(reserve-llm-call-or-cancel!
                                                    context tick-ctx sheet-id tick-id)
                                                  ;; node-id rides along so RLM stream
                                                  ;; events (iteration/phase2) carry the
                                                  ;; hosting repl-researcher node.
                                                  :node-id node-id)
                                     tool-context (assoc :tool-context tool-context)
                                     correlation-id (assoc :orc/correlation-id correlation-id))
                  result (if provider
                           (executor/execute-repl-researcher node blackboard provider enriched-context)
                           {:status :failure :error "No ORC LLM provider configured"})
                  {:keys [status outputs error duration-ms generated-tree-raw iteration-reasonings usage iterations block-payload]} result
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
                                      (seq iterations) (assoc :iterations (vec iterations))
                                      ;; CJ-5b: carry the OPAQUE :tool-context onto the terminal
                                      ;; completion's :writes so a downstream per-event judge (e.g.
                                      ;; the coding-outcome judge) recovers the turn-id via
                                      ;; build-trace-data and grounds on the turn's captured effect.
                                      ;; On a :success terminal the completion otherwise carried only
                                      ;; the declared writes (+ RLM internals) — never the reads — so
                                      ;; the turn-id was unreachable and the judge grounded empty. The
                                      ;; :tool-context is the SAME one FIX B (above) reads from this
                                      ;; tick's execution-context read model; orc does not interpret
                                      ;; it. Absent -> not carried (backward-compatible).
                                      tool-context (assoc :tool-context tool-context))]
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
                         ;; Carry the node's real read inputs (plus any map-each
                         ;; context) so the eval trace has honest grounding
                         ;; context. exec-context keys win on conflict to keep
                         ;; map-each correlation intact.
                         (or (seq read-inputs) (seq exec-context))
                         (assoc :inputs (merge read-inputs exec-context))
                         ;; Which write each read resolved to, so a consumer
                         ;; rehydrates what this node saw rather than the last
                         ;; write to that key.
                         (seq read-inputs)
                         (assoc :read-sources (read-sources (:reads base-node) blackboard exec-context))
                         ;; WS-2a: a Phase-2 leaf blocked -> the RLM loop
                         ;; short-circuited with :status :blocked + the opaque
                         ;; payload. Carry the payload onto the repl-researcher
                         ;; node completion so it reaches the parent tick result.
                         (= :blocked effective-status) (assoc :block-payload block-payload)
                         ;; Propagate :usage (including :by-node from Phase 2)
                         ;; so per-node detail bubbles up to the parent tick.
                         (seq usage) (assoc :usage usage)
                         (:model node) (assoc :model (:model node))))))
            (catch Exception e
              (cp/process-command
                (assoc context :command
                       {:command/id (random-uuid)
                        :command/timestamp (time/now)
                        :command/name :sheet/fail-node-execution
                        :sheet-id sheet-id
                        :tick-id tick-id
                        :node-id node-id
                        :error (.getMessage e)}))))))
        nil)))

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
      ;; A delegate forwards exactly its declared :reads to the target sheet,
      ;; and extract-read-inputs captures the same set for the trace.
      (let [raw-blackboard (resolve-blackboard-values context sheet-id tick-id
                                                      (or (:reads node) []))
            ;; Merge event inputs into blackboard (ORC pattern)
            blackboard (reduce (fn [bb [k v]]
                                 (if (and (keyword? k)
                                          (not (= (namespace k) (namespace ::_))))
                                   (assoc-in bb [k :value] v)
                                   bb))
                               raw-blackboard
                               event-inputs)
            exec-context (extract-execution-context event-inputs)
            correlation-id (:correlation-id
                            (rm/get-tick-execution-context context tick-id))

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
                           read-keys)
            target-input-sources (read-sources read-keys blackboard exec-context)
            ;; Honest-grounding capture (same rationale as repl-researcher):
            ;; the control node that started this delegate dropped its reads
            ;; (delegate is non-leaf), so the eval trace would default the
            ;; synthesis node's :inputs to {}. Capture the real keyword-keyed
            ;; read values (truncated) for the completion event.
            read-inputs (extract-read-inputs read-keys blackboard)]

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
                                          :parent-tick-id tick-id
                                          :correlation-id correlation-id
                                          :input-sources target-input-sources
                                          :return-references? true)
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
                           write-keys)
                  output-sources (reduce
                                  (fn [acc k]
                                    (let [kw-key (if (keyword? k) k (keyword k))
                                          source (or (get (:output-sources result) kw-key)
                                                     (get (:output-sources result) (name k)))]
                                      (if source (assoc acc k source) acc)))
                                  {} write-keys)]

              ;; Complete node execution (ORC pattern)
              (cp/process-command
                (assoc context :command
                       (cond-> {:command/id (random-uuid)
                                :command/timestamp (time/now)
                                :command/name :sheet/complete-node-execution
                                :sheet-id sheet-id
                                :tick-id tick-id
                                :node-id node-id
                                :node-type (:type node)
                                :status status
                                :write-sources (normalize-output-keys output-sources)
                                :write-references? true
                                :duration-ms duration-ms}
                         ;; Carry the node's real read inputs (plus any map-each
                         ;; context) so the eval trace has honest grounding
                         ;; context; exec-context keys win to keep correlation.
                         (or (seq read-inputs) (seq exec-context))
                         (assoc :inputs (merge read-inputs exec-context))
                         ;; Exact read provenance — see the leaf path.
                         (seq read-inputs)
                         (assoc :read-sources (read-sources read-keys blackboard exec-context))
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
      ;; A static check reads exactly one key — see evaluate-condition-check.
      (let [check (:check node)
            blackboard (resolve-blackboard-values context sheet-id tick-id
                                                  (if-let [k (:key check)] [k] []))
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
      ;; execute-llm-condition assembles its prompt from (:reads node) only.
      (let [blackboard (resolve-blackboard-values context sheet-id tick-id
                                                  (or (:reads node) []))
            provider (:llm-provider context)]
        (future
          (try
            (let [result (if provider
                           (executor/execute-llm-condition node blackboard provider
                                                          :context {:event-store event-store})
                           ;; No provider - fail with error
                           {:status :failure
                            :error "No llm-provider configured for LLM condition"})
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
                               :node-type (:type node)
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
                ;; Declared reads are not inlined; the child resolves them
                ;; from the tick blackboard. event-inputs IS forwarded — it
                ;; carries the map-each item override, which lives on a shared
                ;; blackboard key and cannot be safely re-resolved under
                ;; concurrency.
                inputs (merge exec-context event-inputs)]
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
          ;; Start ALL children concurrently. Declared reads are not inlined
          ;; (see execute-tree-tick); with N children that would store N copies
          ;; of every value they share.
          {:result/events
           (vec
            (for [child-id children-ids]
              (->event
               {:type :sheet/node-execution-started
                :tags #{[:sheet sheet-id]
                        [:node child-id]
                        [:tick tick-id]}
                :body {:sheet-id sheet-id
                       :tick-id tick-id
                       :node-id child-id
                       :inputs exec-context}})))})))))

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

(defmulti next-child-strategy
  "Select the next sibling for an ordered control node.

   Custom strategies dispatch on :strategy-id. Missing and unknown ids retain
   the engine's linear behavior."
  (fn [{:keys [strategy-id]}] (or strategy-id :linear)))

(defmethod next-child-strategy :linear
  [{:keys [siblings child-index]}]
  (get (vec siblings) (inc child-index)))

(defmethod next-child-strategy :default
  [opts]
  (next-child-strategy (assoc opts :strategy-id :linear)))

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

(defmulti parallel-completion-strategy
  "Evaluate parallel completion through an extensible strategy id."
  (fn [{:keys [strategy-id]}] (or strategy-id :boolean)))

(defmethod parallel-completion-strategy :boolean
  [{:keys [child-counts success-policy failure-policy]}]
  (evaluate-parallel-completion child-counts success-policy failure-policy))

(defmethod parallel-completion-strategy :default
  [opts]
  (parallel-completion-strategy (assoc opts :strategy-id :boolean)))

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
        ;; Resolved from the write log: the completion event carries only
        ;; :write-keys. Needed to keep a map-each iteration's sequence chained
        ;; to its own values — see the :inputs note in the :sequence branch.
        child-writes (value-log/resolve-writes context (:tenant-id context) tick-id event)
        event-inputs (:inputs event)
        exec-context (extract-execution-context event-inputs)
        nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)
        child (get nodes-by-id child-id)
        parent-id (:parent-id child)]
    (when (and parent-id
               ;; Parent completion is a canonical fact for one execution
               ;; context. At-least-once delivery of a child completion must
               ;; not propagate another terminal parent completion.
               (not (some #(and (= parent-id (:node-id %))
                                (matches-execution-context? % exec-context))
                          (into [] (es/read event-store
                                           {:types #{:sheet/node-execution-completed}
                                            :tags #{[:tick tick-id]}
                                            :tenant-id tenant-id})))))
      (let [parent (get nodes-by-id parent-id)
            siblings (:children-ids parent)
            child-index (.indexOf (vec siblings) child-id)
            next-child-id (next-child-strategy
                           {:strategy-id (:strategy-id parent)
                            :siblings siblings
                            :child-index child-index
                            :event event
                            :parent parent})]
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
              (let [next-index (inc child-index)
                    total-children (count siblings)
                    ;; Declared reads are not inlined — the next child resolves
                    ;; them from the tick blackboard, which is
                    ;; read-your-own-write.
                    ;;
                    ;; The completing child's writes ARE inlined inside a
                    ;; map-each iteration. Concurrent iterations share one
                    ;; blackboard slot per key, so by the time the next step in
                    ;; this iteration runs another iteration may have
                    ;; overwritten it; read-your-own-write does not help when
                    ;; the value belongs to a different iteration. Bounded to
                    ;; one node's outputs, and only inside map-each.
                    inputs (if (seq exec-context)
                             (merge exec-context child-writes)
                             exec-context)]
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
                                  :node-type (:type parent)
                                  :status final-status
                                  ;; Propagate the child's writes so the parent's
                                  ;; completion reports them. Resolved from the
                                  ;; write log; the event carries only keys now.
                                  :write-sources (value-log/resolve-write-sources
                                                  event-store (:tenant-id context)
                                                  tick-id event)}
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

            (= child-status :blocked)
            ;; WS-2a: a child raised the orc block signal (a gated tool call
            ;; needs permission). Like :failure/:timeout, the sequence does not
            ;; continue — but unlike :timeout it short-circuits regardless of
            ;; composite kind: the whole turn must pause for permission, not
            ;; try another sibling. Propagate :blocked + the OPAQUE payload up.
            {:result/events
             [(->event
               {:type :sheet/node-execution-completed
                :tags #{[:sheet sheet-id]
                        [:node parent-id]
                        [:tick tick-id]}
                :body (cond-> {:sheet-id sheet-id
                               :tick-id tick-id
                               :node-id parent-id
                               :status :blocked}
                        (contains? event :block-payload)
                        (assoc :block-payload (:block-payload event))
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
              ;; Declared reads not inlined (see the :sequence branch). A
              ;; fallback retries siblings against the same inputs, so inlining
              ;; would store one copy per attempt.
              (let [inputs exec-context]
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
            ;; WS-2a: a child raised the orc block signal. A block is NOT a
            ;; "this approach failed, try the next sibling" — the turn must
            ;; pause for permission, so the fallback short-circuits and
            ;; propagates :blocked + the OPAQUE payload up (does NOT try the
            ;; next child).
            :blocked
            {:result/events
             [(->event
               {:type :sheet/node-execution-completed
                :tags #{[:sheet sheet-id]
                        [:node parent-id]
                        [:tick tick-id]}
                :body (cond-> {:sheet-id sheet-id
                               :tick-id tick-id
                               :node-id parent-id
                               :status :blocked}
                        (contains? event :block-payload)
                        (assoc :block-payload (:block-payload event))
                        (seq exec-context) (assoc :inputs exec-context))})]}
            ;; Unknown status - do nothing
            nil)

          :parallel
          ;; For parallel nodes, check if all children completed based on policies.
          ;; Uses event-store CAS to prevent duplicate completions when
          ;; multiple children complete rapidly and all see the same state.
          (let [child-counts (count-child-statuses context sheet-id tick-id parent exec-context)
                completion (parallel-completion-strategy
                            {:strategy-id (:strategy-id parent)
                             :child-counts child-counts
                             :success-policy (:success-policy parent)
                             :failure-policy (:failure-policy parent)
                             :event event
                             :parent parent})]
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
                       :failure-reasons (into {} (map (fn [[i r]] [i (or (:__error r) "(no error reason — node timed out or returned nil)")]) failed-pairs))}
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
            ;; Only the source key needs a value here — it IS the iteration
            ;; list. Everything else this handler uses is node config.
            blackboard (resolve-blackboard-values context sheet-id tick-id
                                                  [source-key])
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
                     :node-type :map-each
                     :status :failure
                     :error (str "Source key '" source-key "' is not a list")}})]}

          (empty? source-list)
          ;; Empty list - succeed with empty results
          {:result/events
           [(make-bb-write-event context sheet-id tick-id output-key [])
            (->event
             {:type :sheet/node-execution-completed
              :tags #{[:sheet sheet-id]
                      [:node node-id]
                      [:tick tick-id]}
              :body {:sheet-id sheet-id
                     :tick-id tick-id
                     :node-id node-id
                     :node-type :map-each
                     :status :success}})]}

          (not child-id)
          ;; No child subtree - succeed with original list
          {:result/events
           [(make-bb-write-event context sheet-id tick-id output-key (vec source-list))
            (->event
             {:type :sheet/node-execution-completed
              :tags #{[:sheet sheet-id]
                      [:node node-id]
                      [:tick tick-id]}
              :body {:sheet-id sheet-id
                     :tick-id tick-id
                     :node-id node-id
                     :node-type :map-each
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
                   ;; Attributed to the ITERATION that will read it, so a
                   ;; consumer can tell iteration i's item from iteration j's.
                   ;; The shared blackboard slot cannot — it holds one value.
                   [(make-bb-write-event context sheet-id tick-id item-key item
                                         {:node-id child-id
                                          :input-seed? true
                                          :exec-context {::map-each-index idx
                                                         ::map-each-parent node-id}})
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
        ;; One read of the tick's events, reused for every resolution below.
        ;; This used to be a resolve-writes call that read the tick and threw
        ;; the events away, so holding them costs nothing extra.
        tick-events (value-log/read-tick-events context (:tenant-id context) tick-id)
        ;; This iteration's writes, resolved from the canonical write log by
        ;; (node-id, exec-context). Attribution matters here more than
        ;; anywhere else: with max-concurrency > 1 several iterations of the
        ;; same child node-id race on the SAME item key, so resolving by key
        ;; alone would give every iteration whichever value landed last.
        writes (value-log/writes-for tick-events event)
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
                effective-writes
                (let [source-key (:source-key state)
                      ;; The iteration item key is a legitimate output: a leaf
                      ;; commonly transforms and rewrites `:as`. Excluding it
                      ;; here made composite children fall back to the stale
                      ;; pre-execution seed. Only collection plumbing keys are
                      ;; special.
                      special? #{source-key output-key}]
                  (or
                   (not-empty writes)
                   ;; Composite child: the direct child produced no attributed
                   ;; writes because the real writers are its DESCENDANTS.
                   ;; Every node inside one iteration shares that iteration's
                   ;; exec-context — execute-composite-node forwards :inputs to
                   ;; its children and complete-node-execution stamps the
                   ;; namespaced subset onto each write — so keying on the
                   ;; iteration finds them at any depth.
                   ;;
                   ;; This also FIXES a concurrency bug: the previous fallback
                   ;; swept the shared blackboard, so with max-concurrency > 1
                   ;; every iteration saw whatever the others had just written.
                   (not-empty
                    (reduce-kv (fn [acc k v] (if (special? k) acc (assoc acc k v)))
                               {}
                               (get (value-log/writes-by-iteration tick-events)
                                    {::map-each-index item-index
                                     ::map-each-parent map-each-parent-id}
                                    {})))
                   ;; Conservative fallback, for an iteration that genuinely
                   ;; wrote nothing anywhere: reproduce the old whole-blackboard
                   ;; sweep, built from the events already in hand rather than
                   ;; from the read model.
                   (let [latest (merge (value-log/tick-seeds
                                        context (:tenant-id context) tick-id)
                                       (value-log/latest-values tick-events))]
                     (reduce-kv
                      (fn [acc k v]
                        (if (and (keyword? k)
                                 (not (special? k))
                                 (not (= (namespace k) (namespace ::_))))
                          (assoc acc k v)
                          acc))
                      {}
                      latest))
                   {}))
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
                nodes-by-id (resolve-nodes-by-id context sheet-id tick-id)]
            (case (:type act)
              :complete
              ;; D-008: classify the map-each outcome using the pure deep module.
              ;; Returns [status partial-summary-or-nil output-vector] where output
              ;; is the successes-only vector (failure markers stripped).
              (let [[status summary output]
                    (classify-map-each-outcome
                      {:results (:results act) :item-count total-items})
                    ;; Slice O opt-in: when the map-each parent node has
                    ;; :preserve-failures? truthy, write the ALIGNED full-length
                    ;; results vector (with {:__status :failure …} markers at
                    ;; failed slots) instead of the successes-only `output`.
                    ;; Flag absent/false → write `output` exactly as before.
                    preserve-failures? (boolean
                                         (:preserve-failures?
                                          (get nodes-by-id map-each-parent-id)))
                    into-value (if preserve-failures? (:results act) output)
                    completion-body (cond-> {:sheet-id sheet-id
                                             :tick-id tick-id
                                             :node-id map-each-parent-id
                                             :node-type :map-each
                                             :status status}
                                      summary (assoc :partial-summary summary))]
                {:result/events
                 [(->event
                   {:type :sheet/map-each-progress-updated
                    :tags #{[:sheet sheet-id] [:node map-each-parent-id] [:tick tick-id]}
                    :body {:sheet-id sheet-id :tick-id tick-id :node-id map-each-parent-id
                           :item-index (:completed-count act) :total-items total-items}})
                  (make-bb-write-event context sheet-id tick-id output-key into-value)
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
                  ;; Attributed to the iteration that will read it — see the
                  ;; batch-dispatch site above.
                  (make-bb-write-event context sheet-id tick-id item-key next-item
                                       {:node-id child-id
                                        :input-seed? true
                                        :exec-context {::map-each-index (:next-index act)
                                                       ::map-each-parent map-each-parent-id}})
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

(defn- tick-written-keys
  "The set of blackboard keys this tick actually wrote, read from its
   canonical value and reference events.

   Returns nil when the set cannot be determined (no event store, or the
   read threw). Callers MUST treat nil as \"include every key\" so that a
   read failure degrades to the previous whole-blackboard behavior rather
   than silently dropping a caller's outputs."
  [event-store tenant-id tick-id]
  (try
    (when event-store
      (into #{}
            (map :key)
            (es/read event-store
                     (cond-> {:types #{:sheet/execution-value-written
                                       :sheet/execution-value-referenced}
                              :tags #{[:tick tick-id]}}
                       tenant-id (assoc :tenant-id tenant-id)))))
    (catch Exception _ nil)))

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
        ;; WS-2a: the OPAQUE block payload rides the root node's :blocked
        ;; completion up onto the tree-tick-completed event so the delivered
        ;; result carries it. orc never interprets it.
        block-payload (:block-payload event)
        tick-ctx (rm/get-tick-execution-context context tick-id)
        correlation-id (:correlation-id tick-ctx)
        root-node-id (:root-node-id tick-ctx)
        ;; Per-execution override from options, else global dynamic default
        max-ticks (or (get-in tick-ctx [:options :max-ticks]) *max-tick-iterations*)
        ;; Get current tick to check iteration count and cancellation.
        ;; Ancestor-aware: a child tick (RLM Phase 2 / delegate) spawned in
        ;; the window where its parent was being cancelled self-terminates
        ;; here instead of running to its own timeout.
        tick (rm/get-tick context tick-id)
        current-iteration (or (:iteration tick) 1)
        cancelled? (or (contains? @budget-cancellation-claims tick-id)
                       (= :cancelled (:status tick))
                       (and (:parent-tick-id tick)
                            (rm/is-tick-or-ancestor-cancelled? context (:parent-tick-id tick))))]
    (when (= node-id root-node-id)
      (cond
        ;; Cancellation is already the tick's terminal decision. A root-node
        ;; completion racing in afterwards must not append a second terminal
        ;; tree-tick event.
        cancelled?
        {:result/events []}

        ;; Status is running and we haven't hit max iterations - re-tick
        (and (= status :running)
             (< current-iteration max-ticks))
        {:result/events
         [(->event
           {:type :sheet/tree-tick-completed
            :tags (cond-> #{[:sheet sheet-id] [:tick tick-id]}
                    correlation-id (conj [:correlation correlation-id]))
            :body (cond-> {:sheet-id sheet-id
                           :tick-id tick-id
                           :iteration current-iteration
                           :root-status :running}
                    correlation-id (assoc :correlation-id correlation-id))})
          (->event
           {:type :sheet/tree-tick-started
            :tags (cond-> #{[:sheet sheet-id] [:tick tick-id]}
                    correlation-id (conj [:correlation correlation-id]))
            :body (cond-> {:sheet-id sheet-id
                           :tick-id tick-id
                           :iteration (inc current-iteration)}
                    correlation-id (assoc :correlation-id correlation-id))})]}

        ;; Either success/failure, or hit max iterations
        :else
        (let [final-status (if (and (= status :running)
                                     (>= current-iteration max-ticks))
                             :failure
                             status)
              ;; Only the keys this tick WROTE, not the whole blackboard. A
              ;; tick is seeded with its caller's working set, so echoing every
              ;; seeded key back out re-stores values the tick never touched.
              ;; Callers still see the full blackboard —
              ;; deliver-execution-result rehydrates it from the
              ;; tick-execution-context read model.
              output-keys (when tick-ctx
                            (let [bb (:blackboard tick-ctx)
                                  written (tick-written-keys event-store
                                                             (:tenant-id context)
                                                             tick-id)]
                              (vec (for [[k _] bb
                                         ;; nil written-set => could not be
                                         ;; determined => name every key.
                                         :when (or (nil? written) (contains? written k))]
                                     k))))]
          {:result/events
           [(->event
             {:type :sheet/tree-tick-completed
              :tags (cond-> #{[:sheet sheet-id] [:tick tick-id]}
                      correlation-id (conj [:correlation correlation-id]))
              :body (cond-> {:sheet-id sheet-id
                             :tick-id tick-id
                             :iteration current-iteration
                             :root-status final-status}
                      output-keys (assoc :output-keys output-keys)
                      correlation-id (assoc :correlation-id correlation-id)
                      ;; WS-2a: carry the opaque payload when the tree blocked.
                      (= :blocked final-status) (assoc :block-payload block-payload)
                      error (assoc :error error))})]})))))


;; =============================================================================
;; Execution Completion Delivery
;; =============================================================================

(defn- tick-family-ids
  "Return `tick-id` and every transitively nested child tick.

   Child starts carry a [:parent-tick parent-id] tag, so this walk is bounded
   by the execution tree. It never scans unrelated tenant history."
  [event-store tenant-id tick-id]
  (loop [seen #{tick-id}
         frontier [tick-id]]
    (if (empty? frontier)
      seen
      (let [children
            (into #{}
                  (mapcat (fn [parent-id]
                            (into []
                                  (comp (map :tick-id)
                                        (remove seen))
                                  (es/read event-store
                                           (cond-> {:types #{:sheet/tree-tick-started}
                                                    :tags #{[:parent-tick parent-id]}}
                                             tenant-id (assoc :tenant-id tenant-id))))))
                  frontier)]
        (recur (into seen children) (vec children))))))

(defn- build-node-trace
  "Build a per-node execution trace for a completed tick and its descendants.

   The trace is lineage-scoped through :parent-tick tags. Completion and write
   events are read only for that tick family, and writes are indexed once for
   constant-time lookup per completion. Runtime is therefore linear in this
   execution's event count, independent of accumulated tenant history.

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
  [{:keys [event-store] :as context} tenant-id tick-id]
  (try
    (if-not event-store
      []
      (let [family-ids (tick-family-ids event-store tenant-id tick-id)
            family-events
            (value-storage/hydrate-events
             context
             (into []
                   (mapcat (fn [family-tick-id]
                             (into []
                                   (es/read event-store
                                            (cond-> {:types #{:sheet/node-execution-completed
                                                             :sheet/execution-value-written}
                                                     :tags #{[:tick family-tick-id]}}
                                              tenant-id (assoc :tenant-id tenant-id))))))
                   family-ids))
            writes-by-execution (value-log/writes-by-execution family-events)]
        (->> family-events
             (filter #(= :sheet/node-execution-completed (:event/type %)))
             (sort-by :event/timestamp)
             (mapv (fn [ev]
                     (let [writes (or (not-empty
                                       (get writes-by-execution
                                            (value-log/execution-key ev)))
                                      (:writes ev)
                                      {})]
                       (cond-> {:node-id (:node-id ev)
                                :sheet-id (:sheet-id ev)
                                :tick-id (:tick-id ev)
                                :status (:status ev)
                                :timestamp (:event/timestamp ev)}
                         (:inputs ev) (assoc :inputs (:inputs ev))
                         ;; Keyed by (node-id, exec-context) so map-each
                         ;; iterations stay distinct.
                         (seq writes) (assoc :writes writes)
                         (:usage ev) (assoc :usage (:usage ev))
                         (:duration-ms ev) (assoc :duration-ms (:duration-ms ev)))))))))
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
  [{:keys [event event-store tenant-id] :as context}]
  (let [tick-id (:tick-id event)
        root-status (:root-status event)
        executed-version (:version-number (rm/get-tick-execution-context context tick-id))
        ;; complete-tree-tick stores only the keys this tick wrote, so the
        ;; full blackboard is rehydrated here from the tick-execution-context
        ;; read model. Falls back to the event's own :outputs for legacy ticks
        ;; dispatched without an execution snapshot.
        ;; The caller is owed the tick's END STATE, so this resolves straight
        ;; from the log rather than through the read model. Delivery is the one
        ;; place that must not depend on how far the projection has caught up:
        ;; a node's writes are appended atomically with its completion, so the
        ;; log is complete by the time we see tree-tick-completed, while the
        ;; cache can still be catching up under concurrent projections — which
        ;; would silently drop a key from the caller's :outputs.
        ;;
        ;; The cached key set is still unioned in, so a declared-but-unwritten
        ;; key is reported as nil rather than going missing, as before.
        ;;
        ;; MUST run before forget-tick! below, which drops the memoized seeds.
        outputs (or (when-let [bb (rm/get-tick-blackboard context tick-id)]
                      (merge (zipmap (keys bb) (repeat nil))
                             (value-log/final-values context tenant-id tick-id)))
                    (:outputs event))
        error (:error event)
        ;; WS-2a: the opaque block payload, present on a :blocked tree tick.
        block-payload (:block-payload event)]
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
            trace-start-ms (System/currentTimeMillis)
            node-trace (build-node-trace context tenant-id tick-id)
            trace-duration-ms (- (System/currentTimeMillis) trace-start-ms)
            _ (u/log ::execution-result-trace-built
                     :tick-id tick-id
                     :node-count (count node-trace)
                     :duration-ms trace-duration-ms)]
        ;; Clean up budget and usage tracking for this tick
        (clear-llm-count! tick-id)
        (clear-tick-usage! tick-id)
        ;; Safe here and not before: `outputs` above was already resolved.
        (value-log/forget-tick! tick-id)
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
                             ;; WS-2a: surface :blocked truthfully so the caller
                             ;; (RLM loop / turn executor) can route it into the
                             ;; blocked-turn machinery instead of a false failure.
                             :blocked :blocked
                             :failure)
                   :outputs (or outputs {})
                   :output-sources (value-log/final-sources context tenant-id tick-id)
                   ;; Include raw tree for :tree-generated status (canonical form generated at execution time)
                   :generated-tree-raw (get outputs :generated-tree-raw)
                   :trace-id tick-id
                   :error error}
            executed-version (assoc :executed-version executed-version)
            ;; Include usage if any LLM calls were made
            (pos? (:total-tokens usage 0)) (assoc :usage usage-with-breakdown)
            ;; WS-2a: carry the opaque payload out to the caller's result.
            (= :blocked root-status) (assoc :block-payload block-payload)
            ;; Always include :node-trace when we have any leaf events.
            (seq node-trace) (assoc :node-trace node-trace)))
        (runtime/forget-ephemeral-context! tick-id)))
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
       :error (or (:reason event) "tick cancelled")
       :cancelled? true
       :outputs {}
       :trace-id tick-id})
    ;; A cancelled tick never reaches deliver-execution-result, so its seed
    ;; memo would otherwise be retained for the life of the process.
    (clear-llm-count! tick-id)
    (value-log/forget-tick! tick-id)
    (runtime/forget-ephemeral-context! tick-id))
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

(defn- originating-tick-started
  [event-store tenant-id tick-id]
  (some #(when (:execution-snapshot %) %)
        (into [] (es/read event-store
                          {:types #{:sheet/tree-tick-started}
                           :tags #{[:tick tick-id]}
                           :tenant-id tenant-id}))))

(defn- trace-lineage
  "Return durable parent/root/direct-child tick IDs for a trace."
  [event-store tenant-id tick-id]
  (let [started (originating-tick-started event-store tenant-id tick-id)
        parent-id (:parent-tick-id started)
        root-id (loop [current tick-id, seen #{}, depth 0]
                  (if (or (contains? seen current) (>= depth 100))
                    tick-id
                    (if-let [parent (:parent-tick-id
                                     (originating-tick-started event-store tenant-id current))]
                      (recur parent (conj seen current) (inc depth))
                      current)))
        children (->> (es/read event-store
                               {:types #{:sheet/tree-tick-started}
                                :tags #{[:parent-tick tick-id]}
                                :tenant-id tenant-id})
                      (into [])
                      (map :tick-id)
                      distinct
                      (sort-by str)
                      vec)]
    {:parent-trace-id parent-id
     :root-trace-id root-id
     :child-trace-ids children}))

(defn- timestamp-ms [timestamp]
  (cond
    (instance? java.time.Instant timestamp) (.toEpochMilli ^java.time.Instant timestamp)
    (instance? java.time.OffsetDateTime timestamp) (-> ^java.time.OffsetDateTime timestamp .toInstant .toEpochMilli)
    :else (-> (java.time.OffsetDateTime/parse (str timestamp)) .toInstant .toEpochMilli)))

(defn- elapsed-ms [started completed]
  (when (and started completed)
    (max 0 (- (timestamp-ms (:event/timestamp completed))
              (timestamp-ms (:event/timestamp started))))))

(defn- trace-node-path [nodes-by-id node-id]
  (loop [current node-id, path (), seen #{}, depth 0]
    (if (or (nil? current) (contains? seen current) (>= depth 100))
      (vec path)
      (let [node (get nodes-by-id current)]
        (recur (:parent-id node)
               (conj path (:name node))
               (conj seen current)
               (inc depth))))))

(defn assemble-execution-trace
  "After a tick completes, assemble an execution trace from events and store it.
   Only runs for tick-scoped executions (snapshot-based).
   Reads node-execution-started/completed events, correlates them with node
   metadata from the execution snapshot, and stores via store-execution-trace."
  [{:keys [event event-store] :as context}]
  (let [tick-id (:tick-id event)
        sheet-id (:sheet-id event)
        root-status (:root-status event)
        tick-ctx (rm/get-tick-execution-context context tick-id)]
    ;; Only assemble traces for tick-scoped executions
    (when tick-ctx
      (let [nodes-by-id (:nodes-by-id tick-ctx)
            version-number (:version-number tick-ctx)
            correlation-id (:correlation-id tick-ctx)
            ;; Read all events for this tick (into [] to realize reducible)
            tick-events (into [] (es/read event-store {:tags #{[:tick tick-id]} :tenant-id (:tenant-id context)}))
            ;; Find tick-started event for timing
            started-event (first (filter #(= :sheet/tree-tick-started (:event/type %)) tick-events))
            started-at (when started-event (:event/timestamp started-event))
            completed-at (:event/timestamp event)
            ;; Which keys the tick touched and how big each value was — not
            ;; the values. :output-snapshot profiles the keys this tick wrote;
            ;; :input-snapshot profiles the keys it was given and did not
            ;; write. Values rehydrate via the node-trace-detail query.
            written-keys (apply disj
                                (or (tick-written-keys event-store (:tenant-id context) tick-id)
                                    #{})
                                (keys (:seed-sources started-event)))
            ;; From the projection already bound above. This handler runs on
            ;; tree-tick-completed, and complete-node-execution appends a
            ;; node's writes and its completion event in ONE command result,
            ;; so every write in the tick is visible before we get here.
            final-bb (or (:blackboard tick-ctx) {})
            ;; The cached blackboard already carries a :profile per key — it is
            ;; recorded exactly when there was a value to describe. So trace
            ;; assembly needs no values at all: it reads the shapes straight
            ;; off the metadata instead of resolving values only to measure
            ;; and discard them.
            snapshot-profile (fn [key-pred]
                               (reduce-kv (fn [acc k entry]
                                            (if (and (key-pred k) (some? (:profile entry)))
                                              (assoc acc k (:profile entry))
                                              acc))
                                          {} final-bb))
            input-snapshot (snapshot-profile #(not (contains? written-keys %)))
            output-snapshot (snapshot-profile #(contains? written-keys %))
            ;; Correlate node-execution-started and completed events
            started-events (filter #(= :sheet/node-execution-started (:event/type %)) tick-events)
            completed-events (filter #(= :sheet/node-execution-completed (:event/type %)) tick-events)
            started-by-execution (into {} (map (juxt trace-execution-key identity) started-events))
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
                                                     (trace-execution-key started))
                                      exec-context (trace-execution-context (:inputs started))
                                      parent-started (when-let [parent-id (:parent-id node)]
                                                       (or (get started-by-execution [parent-id exec-context])
                                                           (get started-by-execution [parent-id {}])))
                                      node-duration (or (:duration-ms completed)
                                                        (elapsed-ms started completed))]
                                :when node]
                            (cond-> {:node-id node-id
                                     :trace-instance-id (:event/id started)
                                     :node-name (:name node)
                                     :node-type (:type node)
                                     :path (trace-node-path nodes-by-id node-id)
                                     ;; :node-type and :executor are separate
                                     ;; axes and consumers need both — a leaf is
                                     ;; :leaf whether it calls an LLM or runs
                                     ;; Clojure, and only :executor says which.
                                     ;; A node-id is not unique within a tick —
                                     ;; map-each runs the same child once per
                                     ;; item — so this is what makes each entry
                                     ;; addressable. Same shape
                                     ;; value-log/execution-key reads off
                                     ;; lifecycle events.
                                     :exec-context exec-context
                                     :status (or (:status completed) :unknown)
                                     :started-at (str (:event/timestamp started))
                                     :completed-at (when completed (str (:event/timestamp completed)))}
                              (:executor node) (assoc :executor (:executor node))
                              (:parent-id node) (assoc :parent-id (:parent-id node))
                              parent-started (assoc :parent-trace-instance-id (:event/id parent-started))
                              (some? node-duration) (assoc :duration-ms node-duration)
                              (:error completed) (assoc :error (:error completed))
                              ;; Shape, not values — every value here is
                              ;; already durable in
                              ;; :sheet/execution-value-written. The
                              ;; :sheet/node-trace-detail query rehydrates them
                              ;; on demand; the profiles keep the trace useful
                              ;; on its own for dashboards and for deciding
                              ;; what to drill into.
                              (seq (:write-keys completed))
                              (assoc :write-keys (:write-keys completed)
                                     :output-profile (:write-profile completed))

                              (seq (:rejected-write-keys completed))
                              (assoc :rejected-write-keys (:rejected-write-keys completed)
                                     :rejected-output-profile
                                     (:rejected-write-profile completed))

                              ;; Read shape comes straight off the completion
                              ;; event, which captures reads resolved at
                              ;; execution time. Control nodes start a non-leaf
                              ;; child with only the map-each context, so the
                              ;; started event has no reads to fall back to.
                              (seq (:read-keys completed))
                              (assoc :read-keys (:read-keys completed)
                                     :input-profile (:input-profile completed)))))
            ;; Calculate duration
            duration-ms (if (and started-at completed-at)
                          (max 0 (- (timestamp-ms completed-at)
                                    (timestamp-ms started-at)))
                          0)
            trace-id tick-id
            {:keys [parent-trace-id root-trace-id child-trace-ids]}
            (trace-lineage event-store (:tenant-id context) tick-id)
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
                              :root-trace-id root-trace-id
                              :child-trace-ids child-trace-ids
                              :started-at (str (or started-at (time/now)))
                              :completed-at (str (or completed-at (time/now)))
                              :duration-ms duration-ms
                              :status final-status
                              :input-snapshot input-snapshot
                              :output-snapshot output-snapshot
                              :node-traces node-traces}
                       parent-trace-id (assoc :parent-trace-id parent-trace-id)
                       correlation-id (assoc :correlation-id correlation-id)
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
                                                         (assoc :max-concurrency (:max-concurrency snapshot-node))
                                                         (some? (:preserve-failures? snapshot-node))
                                                         (assoc :preserve-failures? (:preserve-failures? snapshot-node)))}))])

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
