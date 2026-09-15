(ns ai.obney.orc.ontology.core.todo-processors
  "Todo processors for ontology self-learning.

   These event handlers close the loop between execution, judging and the
   ontology's living descriptions:

   1. Campaign terminal handling for researcher nodes.
   2. C-2a-3a — threshold-tracking trigger: when a target's delta-counter
      crosses its configured threshold, emit :ontology/request-consolidation
      so the consolidator processor (C-2a-3b) can run reflection over the
      occurrence's judge scores and durable evidence.
   3. Tree-class enrichment, worked-pattern corroboration, description
      re-embedding and concept projection.

   Judge feedback reaches the ontology through the consolidator's living
   descriptions. The former automatic tree-profile feeder (a processor on an
   evaluation event no producer ever emitted) was retired under grill
   decision D6 / RR-32; tree profiles are fed only by the consumer-facing
   record-tree-strength / record-tree-weakness commands."
  (:require [ai.obney.orc.ontology.core.classifier :as classifier]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.grain.command-processor-v2.interface :as command-processor]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.todo-processor-v2.interface :refer [defprocessor]]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]
            [com.brunobonacci.mulog :as u]))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn run-command!
  "Execute a command through the command processor."
  [context command]
  (command-processor/process-command
    (assoc context :command command)))

(def ^:private recurrence-verdicts #{:success :failure :timeout})

(defn- completion-classification
  [{:keys [event-store tenant-id]} {:keys [sheet-id tick-id node-id]}]
  (->> (event-store/read event-store
                         {:tenant-id tenant-id
                          :types #{:ontology/task-classified}
                          :tags #{[:tick tick-id]}})
       (reduce (fn [_ event]
                 (when (and (= sheet-id (:source-sheet-id event))
                            (= tick-id (:source-tick-id event))
                            (= node-id (:source-node-id event)))
                   (reduced event)))
               nil)))

(defprocessor :ontology on-researcher-campaign-terminal
  {:topics #{:sheet/node-execution-completed}}
  "RR-19: translate a classified researcher campaign's behavior verdict into
   its explicit durable recurrence fact. Blocked completions and parent-level
   cancellation/abandonment are not behavior verdicts and never enter here."
  [{:keys [event] :as context}]
  (when (and (= :repl-researcher (:node-type event))
             (contains? recurrence-verdicts (:status event)))
    (when-let [classification (completion-classification context event)]
      (run-command!
       context
       {:command/name :ontology/record-tree-class-occurrence
        :command/id (random-uuid)
        :command/timestamp (time/now)
        :source-sheet-id (:source-sheet-id classification)
        :source-tick-id (:source-tick-id classification)
        :source-node-id (:source-node-id classification)
        :source-completion-event-id (:event/id event)
        :assigned-tree-id (:assigned-tree-id classification)
        :verdict (:status event)}))))

;; =============================================================================
;; C-2a-3a — Threshold-tracking trigger
;; =============================================================================
;;
;; Listens to the execution-completion events that feed the delta-counter
;; read-model. For each event, derives the affected (target-type, target-id)
;; tuples, reads the current counter, compares to the configured threshold,
;; and emits :ontology/request-consolidation when the counter has crossed.
;;
;; Counter reset semantics are handled by the read-model's reduction on
;; :ontology/consolidation-requested — so re-firing requires the counter
;; to climb back to threshold from 0.

(defn- request-consolidation!
  "Issue a non-on-demand :ontology/request-consolidation command.
   The command uses CAS on the crossing-uuid tag to enforce exactly-once
   semantics across concurrent processor handlers. A
   :cognitect.anomalies/conflict result is EXPECTED and benign — it
   means another handler in the same threshold window won the race and
   already emitted the consolidation-requested event. We treat it as
   silent success, not an error."
  [context target-type target-id]
  (try
    (let [result (run-command! context
                   {:command/id (random-uuid)
                    :command/timestamp (time/now)
                    :command/name :ontology/request-consolidation
                    :target-type target-type
                    :target-id target-id
                    :on-demand? false})]
      (when (and (map? result)
                 (= :cognitect.anomalies/conflict
                    (:cognitect.anomalies/category result)))
        (u/log ::cas-conflict-benign
               :target-type target-type
               :target-id target-id
               :note "Another handler won the race; this is expected"))
      result)
    (catch Exception e
      (u/log ::request-consolidation-error
             :error (.getMessage e)
             :target-type target-type
             :target-id target-id))))

(defn- maybe-fire-consolidation!
  "If the current delta-counter for the target meets or exceeds its
   configured threshold, emit :ontology/request-consolidation."
  [context target-type target-id]
  (let [delta (rm/get-consolidation-delta context target-type target-id)
        threshold (rm/get-consolidation-threshold context target-type)]
    (when (>= delta threshold)
      (u/log ::threshold-crossed
             :target-type target-type
             :target-id target-id
             :delta delta
             :threshold threshold)
      (request-consolidation! context target-type target-id))))

(defn on-node-execution-completed
  "Threshold-trigger logic for :sheet/node-execution-completed.
   Ticks the per-node-type AND per-node-instance counters via the
   read-model projection, then checks both for threshold crossing."
  [{:keys [event] :as context}]
  (let [{:keys [node-type sheet-id node-id]} event]
    (when (some? node-type)
      (maybe-fire-consolidation! context :node-type node-type))
    (when (and (some? sheet-id) (some? node-id))
      (maybe-fire-consolidation! context :node-instance [sheet-id node-id]))))

(defn on-rlm-tree-execution-completed
  "Threshold-trigger logic for :sheet/rlm-tree-execution-completed."
  [{:keys [event] :as context}]
  (when-let [fp (:tree-fingerprint event)]
    (maybe-fire-consolidation! context :tree-fingerprint fp)))

(defn on-tree-class-occurrence-recorded
  "RR-19 threshold-trigger logic for a verdict-qualified tree-class occurrence.
   One completed campaign verdict ticks
   the counter under [:tree-class assigned-tree-id]. When the per-
   tree-class threshold crosses, the consolidator updates the
   description body the classifier reads from."
  [{:keys [event] :as context}]
  (when-let [tree-class-id (:assigned-tree-id event)]
    (maybe-fire-consolidation! context :tree-class tree-class-id)))

(defprocessor :ontology on-execution-completed-check-threshold
  {:topics #{:sheet/node-execution-completed
             :sheet/rlm-tree-execution-completed
             :ontology/tree-class-occurrence-recorded}}
  "C-2a-3a + RR-19: after each execution-completion or verdict occurrence
   event, check whether the affected target's delta-counter has crossed
   its configured threshold; emit :ontology/request-consolidation if so."
  [{:keys [event] :as context}]
  (case (:event/type event)
    :sheet/node-execution-completed     (on-node-execution-completed context)
    :sheet/rlm-tree-execution-completed (on-rlm-tree-execution-completed context)
    :ontology/tree-class-occurrence-recorded
    (on-tree-class-occurrence-recorded context)
    nil))

;; =============================================================================
;; CV-2 (ADR 0017 decision 3) — post-emit emitted-tree worked-DSL enrichment
;; =============================================================================
;;
;; ADR 0015's literal "capture the emitted TREE": once the RLM emits its tree
;; (the Phase-2 :sheet/rlm-tree-execution-completed bookend now carries the
;; emitted worked-DSL + the SOURCE sheet-id), record that DSL against the
;; assigned :tree-class so it surfaces as a :strengths[].:recommended-pattern —
;; the content EL-4 harvest reads, so a harvested specialist ships the REAL
;; proven pattern rather than just the CV-1 signature.
;;
;; ADDITIVE over CV-1's floor: the floor already made the class retrievable at
;; classify time (robust to turn timeouts). This enrichment lands ONLY when an
;; emit completes — a turn that times out before emit carries no :generated-tree
;; → no enrichment → the class is still retrievable via CV-1's floor (never a
;; regression). Re-orchestration, NOT rewrite: reuses the sheet->class join and
;; the claim-delta command — NO second synthesis LLM.
;;
;; CC-6 (ADR 0021): it writes ONE CLAIM OPERATION, never a body. Re-recording a
;; whole body made this the second writer of a slot the claim path owns, and the
;; two disagreed by construction — CC-3 re-derives `:current` from the claim set
;; on every claim event, so a consolidation would erase this enrichment and this
;; enrichment would then overwrite the consolidation's assembled body with a
;; stale snapshot. The projection's assembly is now the only writer of a
;; tree-class body.
;;
;; RR-20 (WorkedPatternsAreProvenNotMerelyRecent) — keyed on OUTCOME and SHAPE:
;; CV-2 as landed wrote ONE claim per class regardless of whether the emitted
;; tree succeeded or failed, and identified it by a fixed literal trait rather
;; than by the tree it named — so a repair round's FAILED tree could
;; crystallize as the class's proven pattern, and a class that genuinely
;; succeeded with two shapes could only ever remember the most recent one.
;; RR-20 changes both:
;;   1. IDENTITY IS PER SHAPE. The claim's `:content` names the emitted tree's
;;      `:tree-fingerprint`, so a class keeps one claim PER DISTINCT SHAPE
;;      rather than one claim total. A shape that recurs reinforces its own
;;      claim; it can never reinforce or overwrite a sibling shape's.
;;   2. THE OUTCOME DECIDES THE SECTION. A `:success` bookend's shape is a
;;      `:strength` (a worked pattern harvest may offer); a
;;      `:failure`/`:timeout`/`:partial` bookend's shape is a `:weakness` (a
;;      failed shape, recorded with its exact source as evidence, never
;;      offered as a pattern). `:edit` therefore no longer swaps one shape's
;;      source for a DIFFERENT shape's — a delta only ever `:edit`s the SAME
;;      fingerprint's claim (the rare case where two emits share a shape but
;;      differ in normalized-away content, e.g. a reworded :instruction).
;;      A bookend with no `:status` (pre-C-2a-2 replay) records nothing new.
;;   3. RESOLUTION IS BY OCCURRENCE. `get-tree-class-for-occurrence` (SJ-1's
;;      `:occurrence->class`, keyed on `[source-sheet-id source-tick-id]`)
;;      replaces `get-tree-class-for-sheet` (keyed on the bare, possibly
;;      shared, sheet-id) so a bookend can never be attributed to a sibling
;;      turn's classification on the same static host sheet.
;;   4. `:evidence-basis :emitted-artifact-outcome` (not `:emitted-artifact`)
;;      declares the stronger, still-mechanical fact this now rests on: not
;;      merely that the tree was emitted, but the engine's own deterministic
;;      execution-outcome recorded with it. See the schema docstring.
;;   5. THE SELECTOR'S PREFERENCE. `corroborate-worked-patterns-from-occurrence!`
;;      (below) reacts to RR-19's durable verdict and REINFORCES (never
;;      creates) the matching shape claim(s) when the campaign's terminal
;;      verdict is `:success` — a shape corroborated by both its own bookend
;;      AND the campaign's verdict accumulates more earned support than a
;;      bookend-only shape, so `harvest/best-recommended-pattern`'s existing
;;      confidence-ranked sort already prefers it. This keeps `harvest-body`'s
;;      signature exactly as the propagated RR-20 contract fixes it (`desc`
;;      + `occurrences`, no ctx/class-id) — no new field on the assembled
;;      strength entry was needed to make the preference decidable.

(defn- worked-pattern-trait
  "RR-20: the `:content` of the SHAPE'S `:strength` claim — a class's worked
   pattern for the tree identified by `fingerprint`. Identity is now PER
   SHAPE (`[:strength (worked-pattern-trait fp)]`), not one fixed literal per
   class: a class that succeeds with two shapes keeps two distinct claims,
   and a re-success of one can only ever reinforce its own. Phrased as a
   readable trait (CC-3's `assemble-summary` renders claim content into
   `:summary`, which ColBERT indexes) rather than as a bare sentinel, while
   still embedding the fingerprint so the identity is genuinely per-shape."
  [fingerprint]
  (str "emits the " fingerprint " worked tree for tasks of this class"))

(defn- failed-shape-trait
  "RR-20: the `:content` of the SHAPE'S `:weakness` claim — a class's failed
   shape for the tree identified by `fingerprint`. Distinct kind AND distinct
   content from `worked-pattern-trait` at the same fingerprint, so a shape
   that fails once and later succeeds (a repair) records TWO claims that
   never touch each other, per `WorkedPatternsAreProvenNotMerelyRecent`."
  [fingerprint]
  (str "emitted the " fingerprint " tree for tasks of this class and it failed"))

(defn- shape-claim
  "The claim this writer owns for `kind`+`trait` (one shape, one section), or
   nil. Identity is `[kind trait]` — the same key every time this exact shape
   recurs in this exact section, which is what makes reinforcement (rather
   than a rival entry) possible."
  [claims kind trait]
  (first (filter #(and (= kind (:kind %)) (= trait (:content %))) claims)))

(defn- shape-delta
  "The ONE claim operation one shape's bookend expresses, scoped to a single
   `[kind trait]` identity (one fingerprint, one section — :strength or
   :weakness). RR-20 narrows what the ADR 0021 ratchet-removal note below
   still explains: THIS `:edit` can only ever reword the SAME shape's claim,
   never swap a different shape's source in over it — the class-level 'one
   slot, last write wins' defect CV-2 originally had is gone because the slot
   is now per-shape.

     no claim for THIS shape yet    -> :add
     same source as recorded        -> :support   (a repeat emit is
                                                    CORROBORATION that this
                                                    is the class's worked/
                                                    failed shape)
     different source, SAME shape   -> :edit       (rare: the fingerprint
                                                    normalizes away :fn/
                                                    :instruction content, so
                                                    two emits can share a
                                                    shape while differing in
                                                    exact source text —
                                                    reinforces AND rewords
                                                    in place)

   `evidence-basis` (RR-20) declares what this rests on and DIFFERS by
   caller: the bookend writer (`enrich-tree-class-with-emitted-dsl!`) always
   passes `:emitted-artifact-outcome` — the engine's own deterministic
   execution outcome (the bookend's `:status`) recorded together with the
   artifact it produced. The occurrence-corroboration writer
   (`corroborate-worked-patterns-from-occurrence!`) always passes
   `:campaign-verdict` — a SEPARATE, LATER mechanical fact (the whole
   campaign, not just this Phase-2 execution, reached :success) — and ONLY
   ever reaches the `:support` branch (it reinforces an existing claim, it
   never creates or rewords one). Neither basis names an occurrence, so
   CC-7 can never validate the claim and CC-9's gate can never let it
   enforce from either alone; `:campaign-verdict` support is durable via
   `read-models/reinforce-claim`'s `:verdict-corroborations` counter, which
   `harvest/best-recommended-pattern` ranks on ahead of raw support."
  [existing kind trait source-text evidence-basis]
  (let [base {:kind kind
              :content trait
              :context-guard nil
              :recommendation source-text
              :episodes []
              :from-legacy-corpus false
              :evidence-basis evidence-basis}]
    (cond
      (nil? existing)
      (assoc base :operation :add)

      (= source-text (:recommendation existing))
      (assoc base :operation :support :target-claim (:claim-id existing))

      :else
      (assoc base :operation :edit :target-claim (:claim-id existing)))))

(def ^:private claim-delta-retry-attempts
  "RR-20: how many times `record-claim-deltas-with-retry!` re-reads and
   retries after a `:stale-claim-set` refusal before giving up (each retry
   is a fresh read, so this bounds worst-case work, not correctness — the
   loop always converges once writers stop racing)."
  5)

(defn- claim-delta-write-lost-the-race?
  "RR-20: true when `record-claim-deltas` needs to be retried against a
   fresh read, on EITHER of the two distinct ways a concurrent writer can
   beat this one to `class-id`'s claim set:

     1. `:ontology/refused` — the HANDLER's own pre-check
        (`recorded-claim-delta-count` vs our `:claim-set-version`) found the
        version already stale before it even tried to append.
     2. `::anom/conflict` — the version was current when the handler
        checked, but a DIFFERENT concurrent append committed between that
        check and this one's own append; the event-store's CAS predicate
        (compared at the `dosync` boundary, not the handler's read) is what
        catches THIS race, and it returns the anomaly directly rather than
        the handler's `:ontology/refused` map — the handler never runs its
        own refusal branch for this case, because ITS pre-check passed.
   Missing either arm silently drops a concurrent writer's delta: this is
   what RR-20's fail-then-repair scenario (two claim-affecting bookends with
   no synchronizing sleep) exercises for real, and is the root cause the
   debug session for this slice traced before landing this function."
  [result]
  (or (boolean (:ontology/refused result))
      (= ::anom/conflict (::anom/category result))))

(defn- record-claim-deltas-with-retry!
  "RR-20: dispatch `(build-deltas fresh-claims)` against `class-id`'s CURRENT
   claim set, retrying on either race `claim-delta-write-lost-the-race?`
   names by re-reading fresh claims + version and rebuilding the deltas from
   scratch.

   ROOT CAUSE this fixes: `record-claim-deltas` refuses/loses rather than
   silently races a stale version (CC-4's `RefuseStaleClaimDeltas`), and
   neither this writer nor its one prior precedent (the consolidator's
   `record-claim-deltas!`) previously retried on either failure mode — every
   earlier CV-2/CC-6 test avoided the race only by sleeping between
   successive emits. RR-20's own propagated test does not: a fail-then-
   repair within one turn fires two claim-affecting bookends back-to-back
   with no synchronizing sleep, which is a real shape (a researcher can
   emit a failing tree and its repair in the same turn), so
   `WorkedPatternsAreProvenNotMerelyRecent` must hold under that
   concurrency, not merely when a test fixture happens to serialize it.

   `build-deltas` returns a vector (possibly empty, in which case this is a
   true no-op — no command dispatched) so both this writer's single shape
   delta and the occurrence-corroboration writer's batch of reinforcement
   deltas share one retry path."
  [context class-id build-deltas]
  (loop [attempt 0]
    (let [claims (rm/get-claims context :tree-class class-id)
          version (rm/get-claim-set-version context :tree-class class-id)
          deltas (build-deltas claims)]
      (when (seq deltas)
        (let [result (run-command! context
                       {:command/name :ontology/record-claim-deltas
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :granularity :tree-class
                        :target-identifier class-id
                        :deltas (vec deltas)
                        :evidence-event-count 0
                        :claim-set-version version})]
          (cond
            (and (claim-delta-write-lost-the-race? result)
                 (< attempt claim-delta-retry-attempts))
            (recur (inc attempt))

            (claim-delta-write-lost-the-race? result)
            (do (u/log ::claim-delta-retry-exhausted
                       :class-id class-id
                       :attempts (inc attempt)
                       :delta-count (count deltas)
                       :result result
                       :note "gave up after the retry budget; this write was LOST — a
                              concurrent writer kept winning the claim-set-version CAS
                              on every attempt")
                result)

            :else result))))))

(defn- emitted-tree-source-text
  "RR-20 (OfferedPatternsAreUsable): the EXACT text to offer, preferring
   RR-6's `:generated-tree-source` (the re-emission authority — real
   production bookends always carry it alongside `:generated-tree`, per
   `rlm-tree-executor`'s `execute-tree`) and falling back to `(pr-str
   generated-tree)` only for a bookend that predates that field entirely
   (replay of pre-RR-6 events). Never prefers `pr-str` when the exact source
   is available — that was the defect this slice's `a-pattern-is-offered-
   as-its-exact-recorded-source` test names."
  [{:keys [generated-tree generated-tree-source]}]
  (or generated-tree-source (some-> generated-tree pr-str)))

(defn enrich-tree-class-with-emitted-dsl!
  "CV-2/RR-20: on a completion event carrying :generated-tree +
   :source-sheet-id + :source-tick-id + :status + :tree-fingerprint, resolve
   the tree-class for the OCCURRENCE (not the bare sheet — see
   `get-tree-class-for-occurrence`) and record ONE claim operation for the
   emitted tree's SHAPE, into the section its outcome earns: `:status
   :success` -> `:strength` (a worked pattern harvest may offer);
   `:failure`/`:timeout`/`:partial` -> `:weakness` (a failed shape, recorded
   with its exact source as evidence, never offered as a pattern). CC-3's
   assembly surfaces a `:strength` shape as `:strengths[].:recommended-pattern`
   — the content EL-4 harvest reads. CC-6: no whole-body write; the
   projection's assembly is the only writer of a tree-class body.

   No-ops (never crashes) when:
     - the event carries no emitted tree / source sheet (timeout / legacy),
     - the event carries no :status (pre-C-2a-2 replay) — RR-20: recording
       a shape's outcome-section requires knowing the outcome,
     - the event carries no :tree-fingerprint — a shape claim's identity IS
       the fingerprint; without one there is nothing to key it on,
     - the occurrence [source-sheet-id source-tick-id] was never classified
       (no class to enrich),
     - THE TARGET STILL HOLDS ONLY A LEGACY BODY. That last one is not
       defensive tidying. CC-3 re-derives `:current` from the claim set on
       every claim event, so landing one mechanical claim on a class whose
       knowledge is still a pre-claim body would replace a lifetime of
       consolidations with a single sentence — the context collapse ADR 0021
       exists to make unrepresentable, arriving through a side door rather
       than through the model. Converting such a target is CC-5's backfill (at
       the consolidation boundary) and CC-12's migration (in bulk); it is not
       this writer's to do, and doing it here would put a third writer on the
       slot the whole slice exists to reduce to one."
  [{:keys [event] :as context}]
  (let [{:keys [generated-tree source-sheet-id source-tick-id
                status tree-fingerprint]} event
        source-text (emitted-tree-source-text event)]
    (when (and (some? generated-tree) (some? source-sheet-id) (some? status)
               (some? tree-fingerprint) (some? source-text))
      (when-let [class-id (rm/get-tree-class-for-occurrence
                            context source-sheet-id source-tick-id)]
        (let [claims (rm/get-claims context :tree-class class-id)
              legacy-only? (and (some? (rm/get-description context :tree-class class-id))
                                (empty? claims))]
          (if legacy-only?
            (u/log ::enrichment-skipped-legacy-body
                   :class-id class-id
                   :note "target still holds a pre-claim body; CC-5/CC-12 convert it")
            (let [kind (if (= :success status) :strength :weakness)
                  trait (if (= :success status)
                          (worked-pattern-trait tree-fingerprint)
                          (failed-shape-trait tree-fingerprint))]
              (u/log ::enriching-tree-class-with-emitted-dsl
                     :class-id class-id :source-sheet-id source-sheet-id
                     :status status :tree-fingerprint tree-fingerprint)
              (record-claim-deltas-with-retry!
               context class-id
               (fn [fresh-claims]
                 [(shape-delta (shape-claim fresh-claims kind trait)
                               kind trait source-text
                               :emitted-artifact-outcome)])))))))))

(defprocessor :ontology on-emit-enrich-tree-class
  {:topics #{:sheet/rlm-tree-execution-completed}}
  "CV-2/RR-20: after an RLM emits its tree, record the emitted worked-DSL as
   a shape claim in the section its outcome earns (additive over CV-1's
   floor). Separate processor from the threshold-check above so each concern
   stays independent; both subscribe to the same bookend topic."
  [context]
  (enrich-tree-class-with-emitted-dsl! context))

(defn- success-bookend-fingerprints
  "RR-20 + RR-23: distinct :tree-fingerprint values among :success Phase-2
   bookends whose [:source-sheet-id :source-tick-id] matches this
   occurrence.

   RR-23: the bookend is now ALSO tagged `[:source-tick source-tick-id]` at
   its emit site (`orc-service/core/commands.clj`'s
   `record-rlm-tree-execution-completion`) — this occurrence's tick IS a
   tag to join on, so the read is scoped by it instead of a type-wide scan
   of every bookend for the tenant. `:source-sheet-id` stays a defensive
   filter (tag narrows, filter decides). Legacy replay: a bookend written
   before this slice carries no `:source-tick` tag and is invisible to this
   scoped read; the sole caller (`corroborate-worked-patterns-from-
   occurrence!`, fired live off a just-recorded occurrence) never needs a
   pre-slice bookend, so no opt-in fallback is wired here — a genuine
   legacy-replay need would be a new, explicit caller decision.

   `(into [] ...)` BEFORE `filter`/`map`: `event-store/read` returns a
   REDUCIBLE (Grain v3's in-memory store), not a seq — `filter`/`map` calling
   `seq` on it directly throws `IllegalArgumentException: Don't know how to
   create ISeq from: ...in_memory$read_single$reify...`. Every other reader
   in this codebase (harvest.clj's `class-occurrence-pairs`,
   `distinct-tree-shapes`, `occurrence-scores`; this file's
   `completion-classification`) already does this; this function's omission
   was the reproduced FINDING 1 defect — the exception is thrown on the
   todo-processor's own thread, which swallows it silently, so
   `corroborate-worked-patterns-from-occurrence!` never reinforced anything
   in a real processor-full context despite every unit-level check on its
   pure helpers passing."
  [{:keys [event-store tenant-id]} source-sheet-id source-tick-id]
  (->> (into [] (event-store/read event-store
                                  {:tenant-id tenant-id
                                   :types #{:sheet/rlm-tree-execution-completed}
                                   :tags #{[:source-tick source-tick-id]}}))
       (filter #(and (= source-sheet-id (:source-sheet-id %))
                     (= source-tick-id (:source-tick-id %))
                     (= :success (:status %))
                     (some? (:tree-fingerprint %))))
       (map :tree-fingerprint)
       distinct))

(defn corroborate-worked-patterns-from-occurrence!
  "RR-20 (exact behavioral change 4 — the selector's preference): when a
   campaign's DURABLE verdict (RR-19's :ontology/tree-class-occurrence-recorded)
   is :success, REINFORCE (never create) the matching shape claim(s) — one
   :support delta per distinct :success-bookend fingerprint attributed to this
   occurrence.

   This is what makes 'prefer success-backed shapes corroborated by verdict
   occurrences over bare emitted artefacts' concrete WITHOUT touching
   `harvest/best-recommended-pattern`'s signature or adding a field to the
   assembled strength entry: a shape corroborated by BOTH its own bookend AND
   the campaign's terminal verdict accumulates strictly more earned support
   (hence higher `:confidence`) than a bookend-only shape, so the selector's
   existing confidence-ranked sort already prefers it. `harvest-body` stays
   exactly `[desc occurrences]`, as the propagated RR-20 contract fixes it.

   Never creates a claim — `on-emit-enrich-tree-class` (the bookend processor)
   is the sole creator of a shape claim. If this fires before that write has
   landed (a genuine race: the campaign's terminal completion could in
   principle be observed before its own Phase-2 bookend's claim-delta command
   completes), `shape-claim` finds nothing and this no-ops rather than racing
   a duplicate `:add`. `:failure`/`:timeout` verdicts reinforce nothing here —
   the failed-shape claim already carries the evidence the bookend that
   created it declared; RR-20's contract asks only that success be
   preferred, not that failure be doubly penalized."
  [{:keys [event] :as context}]
  (let [{:keys [verdict assigned-tree-id source-sheet-id source-tick-id]} event]
    (when (= :success verdict)
      (let [fingerprints (success-bookend-fingerprints
                          context source-sheet-id source-tick-id)]
        (when (seq fingerprints)
          (u/log ::corroborating-worked-patterns-from-occurrence
                 :class-id assigned-tree-id :fingerprint-count (count fingerprints))
          (record-claim-deltas-with-retry!
           context assigned-tree-id
           (fn [fresh-claims]
             (keep (fn [fingerprint]
                     (let [trait (worked-pattern-trait fingerprint)]
                       (when-let [existing (shape-claim fresh-claims :strength trait)]
                         (shape-delta existing :strength trait
                                      (:recommendation existing)
                                      :campaign-verdict))))
                   fingerprints))))))))

(defprocessor :ontology on-campaign-success-corroborate-worked-pattern
  {:topics #{:ontology/tree-class-occurrence-recorded}}
  "RR-20: reinforce a shape's worked-pattern claim when the campaign's
   durable (RR-19) verdict corroborates it — see
   `corroborate-worked-patterns-from-occurrence!`. Separate processor,
   separate topic (the campaign's terminal verdict, not the Phase-2 bookend)
   from `on-emit-enrich-tree-class` above."
  [context]
  (corroborate-worked-patterns-from-occurrence! context))

;; =============================================================================
;; C-2b-1 — Re-index processor
;;
;; Subscribes to ALL THREE :ontology/*-description-updated events. After each
;; event, checks the reindex-state read-model against the reindex-config:
;;   - if events-since-last-rebuild >= configured threshold, OR
;;   - if (now - last-rebuild-timestamp) >= configured timer-minutes, OR
;;   - if no index has been built yet (cold-start),
;; → call colbert/create-index! with the current ontology-descriptions corpus.
;;
;; The :colbert/index-created event emitted by create-index! is what resets
;; reindex-state — handled by the read-model projection in read_models.clj.
;; =============================================================================

(def ^:private ontology-descriptions-index-name
  "Stable :index-name used for every rebuild of the ontology descriptions
   corpus. Per the C-2b sub-grill (Decision 1): one giant index across all
   3 granularities, distinguished only by metadata."
  "ontology-descriptions")

(defn- average-confidence
  "Average :confidence across a description body's :strengths vector.
   Returns 0.0 for empty/missing strengths."
  [body]
  (let [strengths (:strengths body)
        confidences (keep :confidence strengths)]
    (if (seq confidences)
      (double (/ (reduce + confidences) (count confidences)))
      0.0)))

(defn- collect-current-descriptions
  "Project the descriptions read-model and flatten into a vector of
   document records. Each record: {:granularity :target-id :body
   :recorded-at}. Used by the rebuild path to construct the ColBERT
   document collection + per-document metadata."
  [ctx]
  (let [state (rmp/project ctx :ontology/descriptions)]
    (vec
      (for [[granularity by-target] state
            [target-id {:keys [current history]}] by-target
            :when current]
        {:granularity granularity
         :target-id target-id
         :body current
         :recorded-at (some-> history last :recorded-at)}))))

(defn- effective-granularity
  "R05b: route per-document granularity by body :scope when set. R05a's
   behavioral seeds emit via :ontology/record-tree-description (so their
   record-model granularity is :tree-fingerprint) but their body carries
   :scope :behavioral-subtree. The indexer surfaces this as a distinct
   metadata granularity so search-descriptions :granularity
   :behavioral-subtree filters cleanly. Legacy descriptions without
   :scope (and explicit :scope :tree-class) keep their original
   granularity — additive routing only kicks in for :behavioral-subtree."
  [d]
  (let [scope (-> d :body :scope)]
    (if (= scope :behavioral-subtree)
      :behavioral-subtree
      (:granularity d))))

(defn- build-document-collection
  "Convert the flattened description records into the
   (:collection, :document-ids, :document-metadatas) tuple required by
   colbert/create-index!.

   Document content = the description's :summary (the field designed for
   retrieval embedding). Metadata carries granularity + target-id +
   average confidence + last-update so post-retrieval filtering and the
   downstream LLM reranker (C-2b-2) can reason about each match."
  [descriptions]
  (reduce
    (fn [{:keys [collection document-ids document-metadatas] :as acc} d]
      (let [content (or (-> d :body :summary) "")
            g (effective-granularity d)
            doc-id (str g ":" (pr-str (:target-id d)))
            metadata {:granularity g
                      :target-id (:target-id d)
                      :confidence (average-confidence (:body d))
                      :last-update (str (:recorded-at d))}]
        (-> acc
            (assoc :collection (conj collection content))
            (assoc :document-ids (conj document-ids doc-id))
            (assoc :document-metadatas (conj document-metadatas metadata)))))
    {:collection [] :document-ids [] :document-metadatas []}
    descriptions))

(defn minutes-since
  "Whole minutes elapsed between an ISO-string timestamp and now. Returns
   Long/MAX_VALUE if ts is nil (so the timer-condition always fires when
   no rebuild has happened).

   Non-private so tests can with-redefs it to simulate elapsed time
   without sleeping."
  [iso-str]
  (if (nil? iso-str)
    Long/MAX_VALUE
    (try
      (let [past (java.time.OffsetDateTime/parse iso-str)
            now (java.time.OffsetDateTime/now)]
        (.toMinutes (java.time.Duration/between past now)))
      (catch Exception _ Long/MAX_VALUE))))

(defn- should-rebuild?
  "Decide whether the current reindex-state crosses either the threshold
   or timer trigger from the reindex-config. Cold-start (no index built
   yet) always rebuilds when there are any descriptions to index."
  [reindex-state reindex-config descriptions-count]
  (let [{:keys [events-since-last-rebuild last-rebuild-timestamp index-built?]} reindex-state
        {:keys [reindex-threshold-events reindex-timer-minutes]} reindex-config]
    (cond
      ;; Cold-start: any descriptions exist but no index built yet
      (and (pos? descriptions-count) (not index-built?))
      true

      ;; Event-count threshold crossed
      (>= events-since-last-rebuild reindex-threshold-events)
      true

      ;; Timer threshold crossed AND at least one event since last rebuild
      (and (pos? events-since-last-rebuild)
           (>= (minutes-since last-rebuild-timestamp) reindex-timer-minutes))
      true

      :else false)))

(defn- dispatch-create-index!
  "Build the document collection and dispatch the :colbert/create-index
   defcommand. Extracted so both the gated path (maybe-rebuild!) and the
   forced path (force-rebuild!) share the same dispatch — keeps the
   :split-documents? / :max-document-length / :model-name knobs in one
   place."
  [context descriptions reason-metadata]
  (if-not (find-ns 'ai.obney.orc.colbert.interface)
    ;; ColBERT is an optional Layer-5 upgrade. When its component is not on the
    ;; classpath, the ColBERT-backed Living-Description index simply isn't built
    ;; — graph + embedding retrieval is unaffected. Clean no-op, not a failure.
    (u/log ::reindex-skipped-no-colbert
           :reason "colbert component not on classpath")
    (let [{:keys [collection document-ids document-metadatas]}
          (build-document-collection descriptions)]
    (u/log ::reindex-triggered
           :document-count (count collection)
           :reason-metadata reason-metadata)
    (try
      ;; NOTE: pass :split-documents? + :max-document-length explicitly.
      ;; The :colbert/create-index defcommand forwards these to
      ;; operations/create-index! unconditionally; operations' :or
      ;; defaults only apply when the key is absent — passing nil
      ;; overrides the default and produces a malformed
      ;; :colbert/index-created event (config keys would be nil).
      (command-processor/process-command
        (assoc context :command
               {:command/name :colbert/create-index
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :collection collection
                :index-name ontology-descriptions-index-name
                :document-ids document-ids
                :document-metadatas document-metadatas
                :model-name "colbert-ir/colbertv2.0"
                :split-documents? true
                :max-document-length 256}))
      (catch Exception e
        (u/log ::reindex-failed
               :error (.getMessage e)))))))

(defonce
  ^{:private true
    :doc "In-process coalescing latches for the threshold/timer reindex
   trigger, keyed by [tenant-id index-name], each holding
   {:running? bool :dirty? bool}.

   WHY: grain's todo-processor processes events CONCURRENTLY — the
   execution-fn spawns one thread per event. A burst of N
   *-description-updated events therefore lands N handler threads in
   maybe-rebuild! at once. Each independently reads
   events-since-last-rebuild via the read-model, all observe it >=
   threshold (the :colbert/index-created reset from any one rebuild isn't
   appended/visible until after the others have already read), and each
   dispatches a redundant — and expensive — ColBERT rebuild. Observed: a
   10-event burst produced 2..10 rebuilds.

   FIX: a per-(tenant,index) coalescing latch with re-check. One thread
   becomes the runner; concurrent threads mark the latch :dirty? instead
   of dispatching. The runner re-reads-and-redecides until no work is
   pending, so a burst collapses into a single rebuild WITHOUT a
   lost-wakeup (the event that crosses the threshold always forces at
   least one more pass that observes it). In-memory per JVM (grain
   reindex processors are singleton per tenant). The mint force-rebuild!
   path is intentionally NOT latched — it must always rebuild so a
   freshly-minted behavior is indexed immediately."}
  reindex-latches*
  (atom {}))

(defn- reindex-latch
  "Return the coalescing latch (an atom holding {:running? :dirty?}) for
   `k`, creating it on first use. Idempotent under contention."
  [k]
  (or (get @reindex-latches* k)
      (get (swap! reindex-latches* update k
                  (fn [existing] (or existing (atom {:running? false :dirty? false}))))
           k)))

(defn- run-rebuild-pass!
  "One read-decide-dispatch pass. Returns nil. Reads the authoritative
   reindex-state (the read-model revalidates with l1-ttl 0) so each pass
   sees every event appended so far, including a prior pass's
   :colbert/index-created reset."
  [context]
  (let [reindex-state (rm/get-reindex-state context)
        reindex-config (rm/get-reindex-config context)
        descriptions (collect-current-descriptions context)]
    (when (should-rebuild? reindex-state reindex-config (count descriptions))
      (dispatch-create-index! context descriptions
        {:event-count (:events-since-last-rebuild reindex-state)
         :threshold (:reindex-threshold-events reindex-config)
         :cold-start? (not (:index-built? reindex-state))}))))

(defn maybe-rebuild!
  "Read reindex-state + config; if the trigger conditions are met, build
   the document collection from the current descriptions read-model and
   dispatch the :colbert/create-index command. The
   :colbert/index-created event emitted by the command handler is what
   resets the counter via the reindex-state projection.

   We dispatch via the command processor (NOT colbert/create-index!
   directly) because the interface fn bypasses event emission — it
   returns the bridge result but never emits :colbert/index-created.
   The defcommand is the only path that emits the event.

   Concurrency: grain runs one handler thread per event, so this is
   wrapped in a per-(tenant,index) coalescing latch with re-check (see
   `reindex-latches*`). A concurrent burst of description-updated events
   collapses into a single rebuild — the winning thread re-reads and
   re-decides until no peer has marked work pending, which both coalesces
   redundant rebuilds and guarantees the threshold-crossing event is
   never lost."
  [context]
  (let [latch (reindex-latch [(:tenant-id context) ontology-descriptions-index-name])
        ;; Become the runner, or mark work pending for the active runner.
        [old _] (swap-vals! latch (fn [{:keys [running?] :as s}]
                                    (if running?
                                      (assoc s :dirty? true)
                                      {:running? true :dirty? false})))]
    (when-not (:running? old)
      (try
        (loop []
          ;; Clear dirty before the pass; peers that arrive during the pass
          ;; will re-set it and force another pass.
          (swap! latch assoc :dirty? false)
          (run-rebuild-pass! context)
          ;; Atomically: if work was marked pending during the pass, keep
          ;; running for another pass; otherwise stop. Closing this in one
          ;; CAS prevents a lost wakeup between the check and the stop.
          (let [[before _] (swap-vals! latch
                                       (fn [s] (if (:dirty? s)
                                                 (assoc s :dirty? false)
                                                 {:running? false :dirty? false})))]
            (when (:dirty? before) (recur))))
        (catch Throwable t
          (reset! latch {:running? false :dirty? false})
          (throw t))))))

(defn force-rebuild!
  "QP-3: dispatch :colbert/create-index unconditionally, bypassing the
   should-rebuild? gate. Used by the mint-triggered re-index path so a
   single behavioral mint surfaces on the next classify-behaviors call
   instead of waiting for the threshold (default 10 events).

   Safe to call even when no descriptions exist — the dispatch becomes
   a no-op rebuild of an empty corpus (the defcommand handles it).
   Mints are low-frequency, so per-mint rebuild cost is bounded."
  [context]
  (let [descriptions (collect-current-descriptions context)]
    (dispatch-create-index! context descriptions
      {:forced? true
       :document-count (count descriptions)})))

(defprocessor :ontology on-description-updated-maybe-reindex
  {:topics #{:ontology/node-type-description-updated
             :ontology/node-instance-description-updated
             :ontology/tree-description-updated
             ;; CC-6: a claim-delta event re-derives `:current` (CC-3), so it
             ;; changes what the index would contain just as a recorded body
             ;; does. Without this topic the whole claim path — CV-1's
             ;; classify-time capture, CV-2's worked pattern, and every
             ;; consolidation since CC-5 — updates the corpus and never asks
             ;; for it to be re-indexed. The coalescing latch below already
             ;; makes the extra subscription cheap under a burst.
             :ontology/claim-deltas-recorded}}
  "C-2b-1: after each description-updated or claim-deltas-recorded event,
   check whether the reindex-state has crossed the threshold OR timer
   trigger; if so, rebuild the ColBERT ontology-descriptions index via
   create-index!."
  [context]
  (maybe-rebuild! context))

(defprocessor :ontology on-behavioral-subtree-minted-force-rebuild
  {:topics #{:ontology/behavioral-subtree-minted}}
  "QP-3: agent (or hand-authored) mints are low-frequency, authoritative
   contributions — they must surface on the very next classify-behaviors
   call, not after 9 more unrelated description events accumulate to
   cross the threshold-10 gate. This processor subscribes ONLY to the
   audit-trail :ontology/behavioral-subtree-minted event and forces a
   ColBERT rebuild unconditionally.

   The companion :ontology/tree-description-updated event the mint
   defcommand also emits fires the threshold-gated processor above;
   that's fine — the index rebuild this processor dispatches sets
   :index-built? true and resets events-since-last-rebuild to 0, so the
   threshold-gated processor's call on the same event is a no-op (the
   newly-rebuilt corpus already includes the mint).

   RR-24: grain's todo-processor-v2 checkpoints a pure-result handler
   (like this one) AFTER its body runs — the already-checkpointed?
   replay guard only exists on the :result/effect + :result/checkpoint
   :after path, which this handler doesn't use. Without a guard of our
   own, an at-least-once redelivery of the SAME triggering event (a
   pubsub/catch-up race, or a crash after force-rebuild! ran but before
   the processor's checkpoint landed) would re-pay the full ColBERT
   rebuild. Dispatch a durable, event-store-backed CAS marker keyed on
   the triggering event's id FIRST; only call force-rebuild! when that
   dispatch is NOT a conflict. A process-local atom would not survive a
   crash/restart or be shared across nodes, so the marker is a real
   durable event, not in-memory state."
  [{:keys [event] :as context}]
  (let [minted-event-id (:event/id event)
        mark-result (run-command! context
                      {:command/id (random-uuid)
                       :command/timestamp (time/now)
                       :command/name :ontology/mark-mint-reindex-forced
                       :minted-event-id minted-event-id})]
    (if (= :cognitect.anomalies/conflict (:cognitect.anomalies/category mark-result))
      (u/log ::mint-reindex-already-forced :minted-event-id minted-event-id)
      (force-rebuild! context))))

;; =============================================================================
;; C-2d-1 — tree-class hierarchy projection processor
;;
;; When a :ontology/tree-description-updated event lands with a
;; :parent-tree-id in its body, project the parent/child relationship
;; into the :ontology/concepts graph as SKOS broader/narrower. Both
;; concepts are created lazily if not already present. Idempotent.
;;
;; URI scheme: tree-class:<target-id> where target-id is either a UUID
;; (task-class) or a string fingerprint (e.g. "seed:tree:ChunkedExtraction").
;; ;; The raw form is preserved in event bodies; only the URI translation
;; happens here.
;; =============================================================================

(def ^:private tree-class-ontology-id
  "Stable UUID for the dedicated tree-class ontology. Concepts created
   for tree-class hierarchy live under this ontology-id; keeps them
   isolated from the failure/success/problem ontologies."
  (java.util.UUID/nameUUIDFromBytes (.getBytes "tree-class-ontology" "UTF-8")))

(defn- tree-class-uri
  "Render a target-id (UUID or string fingerprint) as a tree-class URI."
  [target-id]
  (str "tree-class:" target-id))

(defn- ensure-system-ontology!
  [context ontology-id name scope base-uri]
  (when-not (rm/ontology-exists? context ontology-id)
    (command-processor/process-command
     (assoc context :command
            {:command/name :ontology/create-ontology
             :command/id ontology-id
             :command/timestamp (time/now)
             :name name
             :scope scope
             :description (str "ORC managed " name)
             :base-uri base-uri}))))

(defn- ensure-tree-class-concept!
  "If a concept with the given target-id's URI doesn't exist in the
   concepts read-model, dispatch :ontology/create-concept to bring it
   into existence. Returns the URI."
  [context target-id]
  (let [uri (tree-class-uri target-id)
        concepts (rmp/project context :ontology/concepts)]
    (when-not (contains? concepts uri)
      (ensure-system-ontology! context tree-class-ontology-id
                               "Tree class ontology" :tree-class "tree-class:")
      (command-processor/process-command
        (assoc context :command
               {:command/name :ontology/create-concept
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :ontology-id tree-class-ontology-id
                :uri uri
                :label (str target-id)
                :description (str "Tree-class concept for " target-id)
                :scope :tree-class
                :broader []
                :indicators []
                :provenance {:kind :system-static}})))
    uri))

(defn on-tree-description-updated-project-concept
  "C-2d-1: when a :ontology/tree-description-updated event with a
   :parent-tree-id lands, project the parent/child relationship into
   the :ontology/concepts graph as a skos:broader link. Lazy-init both
   concepts. Idempotent — re-running on the same event sees the
   existing :broader link and skips the redundant emit.

   Note: :parent-tree-id lives in the event's :body (alongside
   :capabilities/:strengths/:summary/etc.), not at the top level —
   matches the description-body shape stored in :ontology/descriptions."
  [{:keys [event] :as context}]
  (let [body (:body event)
        parent-tree-id (:parent-tree-id body)]
    (when (and parent-tree-id
               (= :tree-fingerprint (:target-type event)))
      (let [target-uri (ensure-tree-class-concept! context (:target-id event))
            parent-uri (ensure-tree-class-concept! context parent-tree-id)
            ;; Read AFTER lazy-create so the broader-already-set? check
            ;; sees the just-created concepts. Re-running on the same
            ;; event observes the parent already in :broader and skips
            ;; the redundant create-relationship emit.
            concepts (rmp/project context :ontology/concepts)
            already-linked? (contains? (get-in concepts [target-uri :broader])
                                       parent-uri)]
        (when-not already-linked?
          (command-processor/process-command
            (assoc context :command
                   {:command/name :ontology/create-relationship
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :source-ontology-id tree-class-ontology-id
                    :target-ontology-id tree-class-ontology-id
                    :source-uri target-uri
                    :target-uri parent-uri
                    :predicate "skos:broader"})))))))

(defprocessor :ontology on-tree-description-updated-project-concept
  {:topics #{:ontology/tree-description-updated}}
  "C-2d-1: project :parent-tree-id from tree-description-updated events
   into the :ontology/concepts graph as a skos:broader relationship,
   creating concepts on-demand for both endpoints."
  [context]
  (on-tree-description-updated-project-concept context))

;; =============================================================================
;; R05a — Behavioral subtree concept projector
;; =============================================================================
;; Parallel to C-2d-1's on-tree-description-updated-project-concept, but
;; filters on (:scope body) = :behavioral-subtree and projects under the
;; behavioral-subtree:<id> URI namespace. Distinct namespace prevents
;; cross-talk with C-2d-1's tree-class:<id> projections; both processors
;; subscribe to the same event stream and each one's filter ensures it
;; only fires for its own scope.
;;
;; The behavioral concept's body carries:
;;   :parent-behavior  - optional UUID or string; SKOS broader WITHIN
;;                       Layer 2 (nil for top-level behaviors)
;;   :composes-into    - optional vector of UUIDs/strings naming the
;;                       structural tree-class shells this behavior
;;                       commonly composes into. Emitted as
;;                       "behavior:composes-into" relationships from the
;;                       behavior to each shell.
;; =============================================================================

(def ^:private behavioral-subtree-ontology-id
  "Stable UUID for the dedicated behavioral-subtree ontology. Concepts
   created for Layer 2 retrieval live under this ontology-id; keeps
   them isolated from the tree-class / failure / success / problem
   ontologies."
  (java.util.UUID/nameUUIDFromBytes
    (.getBytes "behavioral-subtree-ontology" "UTF-8")))

(defn- behavioral-subtree-uri
  "Render a target-id (UUID or string fingerprint) as a behavioral-subtree URI."
  [target-id]
  (str "behavioral-subtree:" target-id))

(defn- ensure-behavioral-subtree-concept!
  "If a behavioral-subtree concept with the given target-id's URI
   doesn't exist in the concepts read-model, dispatch
   :ontology/create-concept to bring it into existence. Returns the URI."
  [context target-id]
  (let [uri (behavioral-subtree-uri target-id)
        concepts (rmp/project context :ontology/concepts)]
    (when-not (contains? concepts uri)
      (ensure-system-ontology! context behavioral-subtree-ontology-id
                               "Behavioral subtree ontology" :behavioral-subtree
                               "behavioral-subtree:")
      (command-processor/process-command
        (assoc context :command
               {:command/name :ontology/create-concept
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :ontology-id behavioral-subtree-ontology-id
                :uri uri
                :label (str target-id)
                :description (str "Behavioral subtree concept for " target-id)
                :scope :behavioral-subtree
                :broader []
                :indicators []
                :provenance {:kind :system-static}})))
    uri))

(defn on-behavioral-subtree-description-updated-project-concept
  "R05a: when a :ontology/tree-description-updated event with
   :scope :behavioral-subtree lands, project:
   1. The behavioral-subtree concept itself (lazy-create at the
      behavioral-subtree:<target-id> URI).
   2. SKOS broader link to :parent-behavior when present.
   3. behavior:composes-into edges to each entry in :composes-into.

   Idempotent — re-running on the same event observes the existing
   concept/edges and skips redundant emits. Doesn't fire on
   :scope :tree-class events (those belong to C-2d-1's processor)."
  [{:keys [event] :as context}]
  (let [body (:body event)]
    (when (and (= :tree-fingerprint (:target-type event))
               (= :behavioral-subtree (:scope body)))
      (let [target-uri (ensure-behavioral-subtree-concept! context (:target-id event))
            parent-behavior (:parent-behavior body)
            composes-into (:composes-into body)]

        (when parent-behavior
          (let [parent-uri (ensure-behavioral-subtree-concept! context parent-behavior)
                concepts (rmp/project context :ontology/concepts)
                already-linked? (contains? (get-in concepts [target-uri :broader])
                                           parent-uri)]
            (when-not already-linked?
              (command-processor/process-command
                (assoc context :command
                       {:command/name :ontology/create-relationship
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :source-ontology-id behavioral-subtree-ontology-id
                        :target-ontology-id behavioral-subtree-ontology-id
                        :source-uri target-uri
                        :target-uri parent-uri
                        :predicate "skos:broader"})))))

        (doseq [shell-id composes-into]
          (let [shell-uri (tree-class-uri shell-id)
                _ (ensure-tree-class-concept! context shell-id)
                concepts (rmp/project context :ontology/concepts)
                already-linked? (contains?
                                  (get-in concepts [target-uri :composes-into])
                                  shell-uri)]
            (when-not already-linked?
              (command-processor/process-command
                (assoc context :command
                       {:command/name :ontology/create-relationship
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :source-ontology-id behavioral-subtree-ontology-id
                        :target-ontology-id tree-class-ontology-id
                        :source-uri target-uri
                        :target-uri shell-uri
                        :predicate "behavior:composes-into"})))))))))

(defprocessor :ontology on-behavioral-subtree-description-updated-project-concept
  {:topics #{:ontology/tree-description-updated}}
  "R05a: project :scope :behavioral-subtree tree-description-updated
   events into the :ontology/concepts graph as behavioral-subtree
   concepts + skos:broader (parent-behavior) + behavior:composes-into
   (each entry in :composes-into) edges."
  [context]
  (on-behavioral-subtree-description-updated-project-concept context))
