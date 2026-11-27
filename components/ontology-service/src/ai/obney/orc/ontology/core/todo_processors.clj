(ns ai.obney.orc.ontology.core.todo-processors
  "Reactive ontology learning and projection processors."
  (:require [malli.error :as me]
            [malli.core :as m]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.ontology.interface.schemas :as schemas]
            [ai.obney.orc.ontology.core.read-models :as read-models]
            [ai.obney.orc.ontology.core.commands :as commands]
            [ai.obney.orc.integrations.interface :as integrations]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.orc.ontology.core.logic :as logic]
            [ai.obney.orc.ontology.core.queries :as queries]
            [com.brunobonacci.mulog :as u]))

;; --- Todo processors ---

(defn completion-classification
  [{:keys [event-store tenant-id]} {:keys [sheet-id tick-id node-id]}]
  (->> (es/read event-store
                         {:tenant-id tenant-id
                          :types #{:ontology/task-classified}
                          :tags #{[:tick tick-id]}})
       (reduce (fn [_ event]
                 (when (and (= sheet-id (:source-sheet-id event))
                            (= tick-id (:source-tick-id event))
                            (= node-id (:source-node-id event)))
                   (reduced event)))
               nil)))

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
    (let [result (commands/run-command! context
                   {:command/id (random-uuid)
                    :command/timestamp (time/now)
                    :command/name :ontology/request-consolidation
                    :target-type target-type
                    :target-id target-id
                    :on-demand? false})]
      (when (and (map? result)
                 (= :cognitect.anomalies/conflict
                    (:cognitect.anomalies/category result)))
        (u/log :ai.obney.orc.ontology.core.todo-processors/cas-conflict-benign
               :target-type target-type
               :target-id target-id
               :note "Another handler won the race; this is expected"))
      result)
    (catch Exception e
      (u/log :ai.obney.orc.ontology.core.todo-processors/request-consolidation-error
             :error (.getMessage e)
             :target-type target-type
             :target-id target-id))))

(defn- maybe-fire-consolidation!
  "If the current delta-counter for the target meets or exceeds its
   configured threshold, emit :ontology/request-consolidation."
  [context target-type target-id]
  (let [delta (read-models/get-consolidation-delta context target-type target-id)
        threshold (read-models/get-consolidation-threshold context target-type)]
    (when (>= delta threshold)
      (u/log :ai.obney.orc.ontology.core.todo-processors/threshold-crossed
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
    (let [claims (read-models/get-claims context :tree-class class-id)
          version (read-models/get-claim-set-version context :tree-class class-id)
          deltas (build-deltas claims)]
      (when (seq deltas)
        (let [result (commands/run-command! context
                       {:command/name :ontology/record-claim-deltas
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :granularity :tree-class
                        :target-identifier class-id
                        :deltas (vec deltas)
                        :evidence-event-count 0
                        :claim-set-version version})]
          (cond
            (and (logic/claim-delta-write-lost-the-race? result)
                 (< attempt logic/claim-delta-retry-attempts))
            (recur (inc attempt))

            (logic/claim-delta-write-lost-the-race? result)
            (do (u/log :ai.obney.orc.ontology.core.todo-processors/claim-delta-retry-exhausted
                       :class-id class-id
                       :attempts (inc attempt)
                       :delta-count (count deltas)
                       :result result
                       :note "gave up after the retry budget; this write was LOST — a
                              concurrent writer kept winning the claim-set-version CAS
                              on every attempt")
                result)

            :else result))))))

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
        source-text (logic/emitted-tree-source-text event)]
    (when (and (some? generated-tree) (some? source-sheet-id) (some? status)
               (some? tree-fingerprint) (some? source-text))
      (when-let [class-id (read-models/get-tree-class-for-occurrence
                            context source-sheet-id source-tick-id)]
        (let [claims (read-models/get-claims context :tree-class class-id)
              legacy-only? (and (some? (read-models/get-description context :tree-class class-id))
                                (empty? claims))]
          (if legacy-only?
            (u/log :ai.obney.orc.ontology.core.todo-processors/enrichment-skipped-legacy-body
                   :class-id class-id
                   :note "target still holds a pre-claim body; CC-5/CC-12 convert it")
            (let [kind (if (= :success status) :strength :weakness)
                  trait (if (= :success status)
                          (logic/worked-pattern-trait tree-fingerprint)
                          (logic/failed-shape-trait tree-fingerprint))]
              (u/log :ai.obney.orc.ontology.core.todo-processors/enriching-tree-class-with-emitted-dsl
                     :class-id class-id :source-sheet-id source-sheet-id
                     :status status :tree-fingerprint tree-fingerprint)
              (record-claim-deltas-with-retry!
               context class-id
               (fn [fresh-claims]
                 [(logic/shape-delta (logic/shape-claim fresh-claims kind trait)
                               kind trait source-text
                               :emitted-artifact-outcome)])))))))))

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
  (->> (into [] (es/read event-store
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
          (u/log :ai.obney.orc.ontology.core.todo-processors/corroborating-worked-patterns-from-occurrence
                 :class-id assigned-tree-id :fingerprint-count (count fingerprints))
          (record-claim-deltas-with-retry!
           context assigned-tree-id
           (fn [fresh-claims]
             (keep (fn [fingerprint]
                     (let [trait (logic/worked-pattern-trait fingerprint)]
                       (when-let [existing (logic/shape-claim fresh-claims :strength trait)]
                         (logic/shape-delta existing :strength trait
                                      (:recommendation existing)
                                      :campaign-verdict))))
                   fingerprints))))))))

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

(defn- dispatch-create-index!
  "Build the document collection and dispatch the :colbert/create-index
   defcommand. Extracted so both the gated path (maybe-rebuild!) and the
   forced path (force-rebuild!) share the same dispatch — keeps the
   :split-documents? / :max-document-length / :model-name knobs in one
   place."
  [context descriptions reason-metadata]
  (if-not (integrations/capability :colbert :create-index!)
    (u/log :ai.obney.orc.ontology.core.todo-processors/reindex-skipped-no-colbert
           :reason "colbert component not on classpath")
    (let [{:keys [collection document-ids document-metadatas]}
          (logic/build-document-collection descriptions)]
    (u/log :ai.obney.orc.ontology.core.todo-processors/reindex-triggered
           :document-count (count collection)
           :reason-metadata reason-metadata)
    (try
      (cp/process-command
        (assoc context :command
               {:command/name :colbert/create-index
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :collection collection
                :index-name logic/ontology-descriptions-index-name
                :document-ids document-ids
                :document-metadatas document-metadatas
                :model-name "colbert-ir/colbertv2.0"
                :split-documents? true
                :max-document-length 256}))
      (catch Exception e
        (u/log :ai.obney.orc.ontology.core.todo-processors/reindex-failed
               :error (.getMessage e)))))))

(defn- run-rebuild-pass!
  "One read-decide-dispatch pass. Returns nil. Reads the authoritative
   reindex-state (the read-model revalidates with l1-ttl 0) so each pass
   sees every event appended so far, including a prior pass's
   :colbert/index-created reset."
  [context]
  (let [reindex-state (read-models/get-reindex-state context)
        reindex-config (read-models/get-reindex-config context)
        descriptions (collect-current-descriptions context)]
    (when (logic/should-rebuild? reindex-state reindex-config (count descriptions))
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
  (let [latch (logic/reindex-latch [(:tenant-id context) logic/ontology-descriptions-index-name])
        [old _] (swap-vals! latch (fn [{:keys [running?] :as s}]
                                    (if running?
                                      (assoc s :dirty? true)
                                      {:running? true :dirty? false})))]
    (when-not (:running? old)
      (try
        (loop []
          (swap! latch assoc :dirty? false)
          (run-rebuild-pass! context)
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

(defn- ensure-system-ontology!
  [context ontology-id name scope base-uri]
  (when-not (read-models/ontology-exists? context ontology-id)
    (cp/process-command
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
  (let [uri (logic/todo-processors-tree-class-uri target-id)
        concepts (rmp/project context :ontology/concepts)]
    (when-not (contains? concepts uri)
      (ensure-system-ontology! context logic/todo-processors-tree-class-ontology-id
                               "Tree class ontology" :tree-class "tree-class:")
      (cp/process-command
        (assoc context :command
               {:command/name :ontology/create-concept
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :ontology-id logic/todo-processors-tree-class-ontology-id
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
            concepts (rmp/project context :ontology/concepts)
            already-linked? (contains? (get-in concepts [target-uri :broader])
                                       parent-uri)]
        (when-not already-linked?
          (cp/process-command
            (assoc context :command
                   {:command/name :ontology/create-relationship
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :source-ontology-id logic/todo-processors-tree-class-ontology-id
                    :target-ontology-id logic/todo-processors-tree-class-ontology-id
                    :source-uri target-uri
                    :target-uri parent-uri
                    :predicate "skos:broader"})))))))

(defn- ensure-behavioral-subtree-concept!
  "If a behavioral-subtree concept with the given target-id's URI
   doesn't exist in the concepts read-model, dispatch
   :ontology/create-concept to bring it into existence. Returns the URI."
  [context target-id]
  (let [uri (logic/todo-processors-behavioral-subtree-uri target-id)
        concepts (rmp/project context :ontology/concepts)]
    (when-not (contains? concepts uri)
      (ensure-system-ontology! context logic/behavioral-subtree-ontology-id
                               "Behavioral subtree ontology" :behavioral-subtree
                               "behavioral-subtree:")
      (cp/process-command
        (assoc context :command
               {:command/name :ontology/create-concept
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :ontology-id logic/behavioral-subtree-ontology-id
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
              (cp/process-command
                (assoc context :command
                       {:command/name :ontology/create-relationship
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :source-ontology-id logic/behavioral-subtree-ontology-id
                        :target-ontology-id logic/behavioral-subtree-ontology-id
                        :source-uri target-uri
                        :target-uri parent-uri
                        :predicate "skos:broader"})))))

        (doseq [shell-id composes-into]
          (let [shell-uri (logic/todo-processors-tree-class-uri shell-id)
                _ (ensure-tree-class-concept! context shell-id)
                concepts (rmp/project context :ontology/concepts)
                already-linked? (contains?
                                  (get-in concepts [target-uri :composes-into])
                                  shell-uri)]
            (when-not already-linked?
              (cp/process-command
                (assoc context :command
                       {:command/name :ontology/create-relationship
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :source-ontology-id logic/behavioral-subtree-ontology-id
                        :target-ontology-id logic/todo-processors-tree-class-ontology-id
                        :source-uri target-uri
                        :target-uri shell-uri
                        :predicate "behavior:composes-into"})))))))))

;; --- Harvest ---

(defn- mint-harvested! [ctx class-id body parent-behavior]
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/mint-behavioral-subtree
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :name (logic/harvest-name class-id)
            :body body
            :parent-behavior parent-behavior
            :provenance :harvested
            :harvested-from-tree-class class-id})))

(defn maybe-harvest!
  "The harvest decision for one :tree-class. Cheap pre-gate first (recurring
   volume + not-already-harvested), then the full conservative gate + mint.
   Idempotent: fires ONCE per class (the class-id guard). No-op below the
   gate — the conservative bar IS the safety."
  ([ctx class-id] (maybe-harvest! ctx class-id logic/default-harvest-config))
  ([ctx class-id config]
   (let [occurrences (read-models/get-consolidation-total ctx :tree-class class-id)]
     (when (and (>= occurrences (:min-occurrences config))
                (not (queries/already-harvested? ctx class-id)))
       (let [judge-avgs (read-models/get-tree-class-judge-averages ctx class-id)
             trailing-avgs (read-models/get-tree-class-judge-recent-averages
                             ctx class-id (:dimension-window config))
             scores (queries/occurrence-scores ctx class-id)
             coherence (queries/winning-shape-coherence ctx class-id config)
             metrics {:occurrences occurrences
                      :judge-trailing-averages trailing-avgs
                      :occurrence-scores scores
                      :winning-shape-coherence coherence}
             report (logic/harvest-gate-report metrics config)]
         (when (and (seq judge-avgs)
                    (not= (count judge-avgs) logic/known-judge-dimension-count))
           (u/log :ai.obney.orc.ontology.core.harvest/judge-dimension-count-changed
                  :class-id class-id
                  :observed (count judge-avgs)
                  :assumed logic/known-judge-dimension-count
                  :judges (vec (sort (keys judge-avgs)))
                  :note (str "the uniform :dimension-floor was calibrated for "
                             logic/known-judge-dimension-count
                             " judge dimension(s); per-dimension floors are now a "
                             "decision to make, not an accident to absorb")))
         (u/log :ai.obney.orc.ontology.core.harvest/harvest-gate-report
                :class-id class-id
                :recurring (:recurring report)
                :dimension (:dimension report)
                :consistency (:consistency report)
                :coherence (:coherence report)
                :lifetime-judge-averages judge-avgs
                :candidate? (:candidate? report))
         (when (:candidate? report)
           (let [desc (read-models/get-description ctx :tree-class class-id)
                 parent (queries/nearest-abstract-behavior ctx class-id)
                 body (queries/harvest-body ctx desc occurrences class-id)]
             (cond
               (nil? body)
               (u/log :ai.obney.orc.ontology.core.harvest/harvest-skipped-no-description :class-id class-id)

               (nil? parent)
               (u/log :ai.obney.orc.ontology.core.harvest/harvest-skipped-no-parent
                      :class-id class-id
                      :note "no behavioral anchor via skos:broader — not creating an orphan")

               :else
               (do (u/log :ai.obney.orc.ontology.core.harvest/harvest-minting
                          :class-id class-id
                          :parent-behavior parent
                          :occurrences occurrences
                          :judge-averages judge-avgs
                          :judge-trailing-averages trailing-avgs
                          :recent-occurrence-scores
                          (take-last (:consistency-window config) scores)
                          :winning-shape-coherence coherence)
                   (mint-harvested! ctx class-id body parent))))))))))

(defn report-shape-coherence!
  "RR-21: dispatch the durable :ontology/report-shape-coherence command for
   ONE verdict occurrence. `source-occurrence-event-id` is that occurrence
   event's own `:event/id` (UUIDv7, durably time-ordered) — the identity the
   command bounds its measure to (see `harvest/class-occurrences-through`),
   so the report reflects THIS occurrence's durable position regardless of
   what has landed in the store by the time it is actually processed
   (finding, inspection: computing from the store's current state at
   PROCESSING time is a scheduling-dependent race, not a measure of the
   named occurrence). The command's own CAS makes this idempotent per
   [tree-class source-sheet-id source-tick-id] (a no-op on replay, RR-19's
   occurrence-command idiom) — this fn itself has no process-local state and
   performs no bare production event append."
  [ctx class-id source-sheet-id source-tick-id source-occurrence-event-id]
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/report-shape-coherence
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :tree-class class-id
            :source-sheet-id source-sheet-id
            :source-tick-id source-tick-id
            :source-occurrence-event-id source-occurrence-event-id})))

;; --- Consolidator ---

(defn- budgeted-window
  "Apply the BoundedReflectionEvidence selection for one gathered window and
   return its `:selected` half.

   PR-2: a non-empty `:excluded` half lands as ONE durable compact
   :ontology/reflection-evidence-excluded event (counts + the excluded
   range's bounds — the excluded set is the contiguous oldest prefix, so the
   bounds identify every member without restating 267-event windows), plus
   the pre-PR-2 log line. Zero exclusions dispatch NOTHING. The dispatch is
   guarded: an exclusion record that cannot be written must not take the
   consolidation down with it — the selection result still stands, and the
   failure is LOUD."
  [ctx target-type target-id events]
  (let [budget (try (read-models/get-evidence-token-budget ctx)
                    (catch Exception e
                      (u/log :ai.obney.orc.ontology.core.consolidator/evidence-budget-config-read-unavailable
                             :target-type target-type
                             :target-id target-id
                             :error (.getMessage e))
                      logic/default-evidence-token-budget))
        {:keys [selected excluded predicted-prompt-tokens]}
        (logic/select-evidence-window events
                                (logic/evidence-density-constants target-type)
                                budget)]
    (when (seq excluded)
      (u/log :ai.obney.orc.ontology.core.consolidator/evidence-window-events-excluded
             :target-type target-type
             :target-id target-id
             :budget-tokens budget
             :selected-count (count selected)
             :excluded-count (count excluded)
             :predicted-prompt-tokens predicted-prompt-tokens)
      (try
        (let [result (cp/process-command
                       (assoc ctx :command
                              (cond-> {:command/name :ontology/record-reflection-evidence-exclusion
                                       :command/id (random-uuid)
                                       :command/timestamp (time/now)
                                       :target-type target-type
                                       :target-id target-id
                                       :budget-tokens budget
                                       :selected-count (count selected)
                                       :excluded-count (count excluded)
                                       :predicted-prompt-tokens predicted-prompt-tokens
                                       :oldest-excluded-at (:timestamp (first excluded))
                                       :newest-excluded-at (:timestamp (peek excluded))}
                                (some? (:consolidation-request-id ctx))
                                (assoc :request-id (:consolidation-request-id ctx)))))]
          (when (:cognitect.anomalies/category result)
            (u/log :ai.obney.orc.ontology.core.consolidator/evidence-exclusion-record-failed
                   :target-type target-type :target-id target-id
                   :anomaly result)))
        (catch Exception e
          (u/log :ai.obney.orc.ontology.core.consolidator/evidence-exclusion-record-failed
                 :target-type target-type :target-id target-id
                 :error (.getMessage e)))))
    selected))

(defn- record-consolidation-failure-fact!
  "Dispatch ONE durable :ontology/description-consolidation-failed record
   with an already-classified {:reason :attempts (:error) (:request-id)}.
   The seam under `record-consolidation-failure!` (which classifies an
   exec-result first), used directly by PR-2's callers whose failure is not
   an executor terminal: the post-answer holes (an answer whose value could
   not be used — :unparseable) and the orphan sweep (:caller-interrupted)."
  [context target-type target-id {:keys [reason attempts error request-id]}]
  (cp/process-command
    (assoc context :command
           (cond-> {:command/name :ontology/record-consolidation-failure
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :granularity target-type
                    :target-identifier target-id
                    :reason reason
                    :attempts attempts}
             (some? error) (assoc :error (str error))
             (some? request-id) (assoc :request-id request-id)))))

(defn- record-consolidation-failure!
  "Emit the durable death certificate for a terminally failed reflection —
   ONE :ontology/description-consolidation-failed event for the whole
   attempt-set, dispatched through the command processor exactly as the
   success paths dispatch their writes. Also keeps the pre-CC-28 log line
   so operators' existing ::consolidate-execution-failed searches keep
   working. PR-2: carries the consolidation request id from the context so
   the failure ANSWERS the request in the outcome ledger."
  [context target-type target-id exec-result]
  (let [{:keys [reason attempts]} (logic/classify-reflection-failure exec-result)]
    (u/log :ai.obney.orc.ontology.core.consolidator/consolidate-execution-failed
           :target-type target-type :target-id target-id
           :status (:status exec-result) :error (:error exec-result)
           :reason reason :attempts attempts)
    (record-consolidation-failure-fact!
      context target-type target-id
      {:reason reason
       :attempts attempts
       :error (:error exec-result)
       :request-id (:consolidation-request-id context)})))

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

   Per LIVING-DESCRIPTIONS.md's decoupled-threshold-and-window safeguard the
   window is BOUNDED — since PR-1 by the token budget (newest-first, ADR
   0030) rather than an event count; aggregate metrics give the LLM the
   historical baseline to compare against.

   RR-23: `task-classifieds` is scoped by the `[:description-target
   target-id]` tag it already carries. Executions, judge scores and
   iteration records are then read ONCE PER OCCURRENCE (this class's
   distinct `[source-sheet-id source-tick-id source-node-id]` triples),
   scoped by `[:source-tick tick-id]` (bookends, per RR-23's new emit-site
   tag), `[:tick tick-id]` (judge scores), and `[:tick tick-id] [:node
   node-id]` (iteration records) — replacing four type-wide tenant scans.
   Cost is now proportional to this class's occurrence count, not the
   store's total event count. `:source-sheet-id`/`:sheet-id` stay
   defensive filters (tag narrows, filter decides); legacy bookends
   written before this slice (no `:source-tick` tag) are invisible to the
   scoped read, same as the prior `and (:source-sheet-id e) (:source-tick-
   id e)` guard already excluded bookends missing either field — no
   caller here needs legacy replay (a live reflection over the current
   store), so no opt-in fallback is wired."
  [ctx target-id]
  (let [task-classifieds (->> (es/read (:event-store ctx)
                                       {:types #{:ontology/task-classified}
                                        :tenant-id (:tenant-id ctx)
                                        :tags #{[:description-target target-id]}})
                              (into [])
                              (filter #(= target-id (:assigned-tree-id %))))
        occurrence-keys (->> task-classifieds
                             (map (juxt :source-sheet-id :source-tick-id :source-node-id))
                             distinct)
        executions-by-occurrence
        (into {}
              (keep (fn [[sheet tick _node]]
                      (when (and sheet tick)
                        (when-let [exec (->> (es/read (:event-store ctx)
                                                      {:types #{:sheet/rlm-tree-execution-completed}
                                                       :tenant-id (:tenant-id ctx)
                                                       :tags #{[:source-tick tick]}})
                                             (into [])
                                             (filter #(and (= sheet (:source-sheet-id %))
                                                           (= tick (:source-tick-id %))))
                                             last)]
                          [[sheet tick] exec]))))
              occurrence-keys)
        judge-scores-by-sheet-tick
        (into {}
              (keep (fn [[sheet tick _node]]
                      (when (and sheet tick)
                        (when-let [scores (seq (->> (es/read (:event-store ctx)
                                                             {:types #{:judge/score-emitted}
                                                              :tenant-id (:tenant-id ctx)
                                                              :tags #{[:tick tick]}})
                                                    (into [])
                                                    (filter #(= sheet (:sheet-id %)))))]
                          [[sheet tick] scores]))))
              occurrence-keys)
        iteration-records-by-campaign
        (into {}
              (keep (fn [[sheet tick node :as campaign-key]]
                      (when (and sheet tick node)
                        (when-let [records (seq (->> (es/read (:event-store ctx)
                                                              {:types #{:rlm/researcher-iteration-recorded}
                                                               :tenant-id (:tenant-id ctx)
                                                               :tags #{[:tick tick] [:node node]}})
                                                     (into [])
                                                     (filter #(= sheet (:sheet-id %)))
                                                     (sort-by (juxt :iteration-index :attempt-ordinal))
                                                     (mapv :iteration-record)))]
                          [campaign-key records]))))
              occurrence-keys)
        joined (mapv (fn [tc]
                       (let [sheet-id (:source-sheet-id tc)
                             tick-id (:source-tick-id tc)
                             node-id (:source-node-id tc)
                             exec (get executions-by-occurrence [sheet-id tick-id])
                             judge-events (get judge-scores-by-sheet-tick [sheet-id tick-id])
                             iteration-records
                             (get iteration-records-by-campaign
                                  [sheet-id tick-id node-id])
                             cleaned-tc (logic/clean-event-for-llm tc)]
                         (cond-> cleaned-tc
                           (seq iteration-records)
                           (assoc :researcher-iterations iteration-records)
                           exec (assoc :execution
                                       (-> exec
                                           logic/clean-event-for-llm
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
    (budgeted-window ctx :tree-class target-id joined)))

(defn- gather-recent-events
  "Pull this target's observation window from the event store. Returns a
   vector of cleaned event maps (in event-store order, most recent last),
   selected newest-first within the evidence token budget (PR-1, ADR 0030
   — see `select-evidence-window`).

   C-Loop-1: :tree-class targets use a join path that pairs
   task-classified observations with their execution outcomes — see
   gather-recent-tree-class-events."
  [ctx target-type target-id]
  (if (= :tree-class target-type)
    (gather-recent-tree-class-events ctx target-id)
    (let [event-type (logic/source-event-type-for-target target-type)]
      (let [source-events (->> (es/read (:event-store ctx)
                                        {:types #{event-type}
                                         :tenant-id (:tenant-id ctx)})
                               (into [])
                               (filter #(logic/event-matches-target? target-type target-id %)))
            judge-scores (->> (es/read (:event-store ctx)
                                       {:types #{:judge/score-emitted}
                                        :tenant-id (:tenant-id ctx)})
                              (into [])
                              (group-by (juxt :sheet-id :tick-id)))]
        (->> source-events
             (map (fn [event]
                    (let [scores (get judge-scores
                                      [(:sheet-id event) (:tick-id event)])]
                      (cond-> (logic/clean-event-for-llm event)
                        (seq scores)
                        (assoc :judge-scores
                               (mapv #(select-keys % [:judge-name :judge-config
                                                     :score :feedback :dimensions
                                                     :emitted-at])
                                     scores))))))
             vec
             (budgeted-window ctx target-type target-id))))))

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
   plus-delta safeguard.

   RR-23: `task-classifieds` is scoped by the `[:description-target
   target-id]` tag it already carries. Executions and judge scores are
   then read ONCE PER OCCURRENCE PAIR, scoped by `[:source-tick tick]`
   (bookends) and `[:tick tick]` (judge scores), instead of two type-wide
   tenant scans filtered in memory — cost is now proportional to this
   class's occurrence count. `:source-sheet-id`/`:sheet-id` stay
   defensive filters (tag narrows, filter decides); a bookend predating
   RR-23's `:source-tick` tag is invisible to the scoped read, same as it
   was already excluded by the pre-existing `occurrence-pairs` filter
   whenever it lacked `:source-sheet-id`/`:source-tick-id` entirely — no
   caller here needs legacy replay, so no opt-in fallback is wired."
  [ctx target-id]
  (let [task-classifieds (->> (es/read (:event-store ctx)
                                       {:types #{:ontology/task-classified}
                                        :tenant-id (:tenant-id ctx)
                                        :tags #{[:description-target target-id]}})
                              (into [])
                              (filter #(= target-id (:assigned-tree-id %))))
        occurrence-pairs (into #{}
                               (map (juxt :source-sheet-id :source-tick-id))
                               task-classifieds)
        relevant-execs (into []
                             (mapcat (fn [[sheet tick]]
                                       (when (and sheet tick)
                                         (->> (es/read (:event-store ctx)
                                                       {:types #{:sheet/rlm-tree-execution-completed}
                                                        :tenant-id (:tenant-id ctx)
                                                        :tags #{[:source-tick tick]}})
                                              (into [])
                                              (filter #(= sheet (:source-sheet-id %)))))))
                             occurrence-pairs)
        success-count (count (filter #(= :success (:status %)) relevant-execs))
        failure-count (count (filter #(= :failure (:status %)) relevant-execs))
        distinct-shapes (->> relevant-execs
                             (keep :tree-fingerprint)
                             distinct
                             count)
        relevant-judge-scores (into []
                                    (mapcat (fn [[sheet tick]]
                                              (when (and sheet tick)
                                                (->> (es/read (:event-store ctx)
                                                              {:types #{:judge/score-emitted}
                                                               :tenant-id (:tenant-id ctx)
                                                               :tags #{[:tick tick]}})
                                                     (into [])
                                                     (filter #(= sheet (:sheet-id %)))))))
                                    occurrence-pairs)
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
    :node-instance    nil
    :tree-class       (tree-class-aggregate-metrics ctx target-id)
    nil))

(defn- claims-for-inference
  "The target's current claim set for query rendering, or [] with a LOUD log
   when the descriptions read model is unreachable in this context (CC-27
   posture: an unavoidable fallback must announce itself, never default
   silently). Production contexts always carry the read-model cache, so the
   fallback arm exists for degraded contexts only: the QUERY then degrades to
   the legacy render instead of the whole hydration failing."
  [context target-type target-id]
  (try (read-models/get-claims context target-type target-id)
       (catch Exception e
         (u/log :ai.obney.orc.ontology.core.consolidator/inference-claims-read-unavailable
                :target-id target-id
                :target-type target-type
                :error (.getMessage e))
         [])))

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
    (let [classify-behaviors (integrations/capability :ontology :classify-behaviors)
          signature (:query (logic/bounded-inference-query
                              body
                              (claims-for-inference context target-type target-id)
                              {:target-id target-id}))
          result (try
                   (classify-behaviors context
                                       {:task-signature signature
                                        :threshold logic/behavioral-inference-threshold
                                        :structural-context target-id
                                        :top-n logic/behavioral-inference-top-n})
                   (catch Exception e
                     (u/log :ai.obney.orc.ontology.core.consolidator/behavioral-inference-error
                            :target-id target-id
                            :error (.getMessage e))
                     nil))
          behaviors (when result (:behaviors result))
          top-1 (first behaviors)
          fresh-mint? (or (nil? top-1) (:was-fresh-mint? top-1))]
      (if (or (nil? result) fresh-mint?)
        (do (u/log :ai.obney.orc.ontology.core.consolidator/orphan-behavioral-subtree-inferred
                   :target-id target-id
                   :reason (cond
                             (nil? result) :inference-error
                             fresh-mint?   :fresh-mint
                             :else         :below-threshold))
            body)
        (do (u/log :ai.obney.orc.ontology.core.consolidator/behavioral-subtree-ids-inferred
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
      (when-not (read-models/ontology-exists? context behavioral-ontology-id)
        (commands/create-ontology! context
                                   {:command/id behavioral-ontology-id
                                    :name "Behavioral subtree ontology"
                                    :scope :behavioral-subtree
                                    :base-uri "behavioral-subtree:"}))
      (when-not (read-models/ontology-exists? context tree-class-ontology-id)
        (commands/create-ontology! context
                                   {:command/id tree-class-ontology-id
                                    :name "Tree class ontology"
                                    :scope :tree-class
                                    :base-uri "tree-class:"}))
      (doseq [behavior-id behavior-ids]
        (let [behavior-uri (str "behavioral-subtree:" behavior-id)
              already-linked? (contains? (get-in concepts [behavior-uri :composes-into])
                                          shell-uri)]
          (when-not already-linked?
            (cp/process-command
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
    (let [classify-task (integrations/capability :ontology :classify-task)
          signature (:query (logic/bounded-inference-query
                              body
                              (claims-for-inference context target-type target-id)
                              {:target-id target-id}))
          result (try
                   (classify-task context
                                  {:task-signature signature
                                   :threshold logic/parent-inference-threshold
                                   :walk-down? false})
                   (catch Exception e
                     (u/log :ai.obney.orc.ontology.core.consolidator/parent-inference-error
                            :target-id target-id
                            :error (.getMessage e))
                     nil))]
      (if (and result (not (:was-fresh-mint? result)))
        (do (u/log :ai.obney.orc.ontology.core.consolidator/parent-tree-class-inferred
                   :target-id target-id
                   :parent-tree-id (:assigned-tree-id result)
                   :confidence (:confidence result))
            (assoc body :parent-tree-id (:assigned-tree-id result)))
        (do (u/log :ai.obney.orc.ontology.core.consolidator/orphan-tree-class-created
                   :target-id target-id
                   :confidence (when result (:confidence result))
                   :reason (cond
                             (nil? result)               :inference-error
                             (:was-fresh-mint? result)   :fresh-mint
                             :else                       :below-threshold))
            body)))

    :else body))

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
  (cp/process-command
    (assoc context :command
           (cond-> {:command/name :ontology/record-claim-deltas
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :granularity target-type
                    :target-identifier target-id
                    :deltas deltas
                    :evidence-event-count evidence-event-count
                    :claim-set-version (read-models/get-claim-set-version
                                         context target-type target-id)}
             (some? model-provenance)
             (assoc :model-provenance model-provenance)
             (some? (:consolidation-request-id context))
             (assoc :request-id (:consolidation-request-id context))))))

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
  (let [body (read-models/get-description context target-type target-id)
        claims (read-models/get-claims context target-type target-id)]
    (when (and body (empty? claims))
      (let [deltas (logic/legacy-body->claim-deltas body)]
        (when (seq deltas)
          (u/log :ai.obney.orc.ontology.core.consolidator/legacy-body-converted-to-claims
                 :target-type target-type
                 :target-id target-id
                 :claim-count (count deltas))
          (record-claim-deltas! (dissoc context :consolidation-request-id)
                                target-type target-id deltas 0 nil))))))

(defn- consolidate-claims!
  "CC-5: consolidate a target by proposing OPERATIONS over its claim set.

   No whole-body description event is emitted anywhere on this path. The body
   is assembled from the claims by CC-3's fold, which makes the assembled body
   single-writer and the interleaving defect unrepresentable rather than
   detected."
  [context target-type target-id]
  (maybe-backfill-legacy-body! context target-type target-id)
  (let [claims (read-models/get-claims context target-type target-id)
        recent-events (gather-recent-events context target-type target-id)
        aggregate-metrics (gather-aggregate-metrics context target-type target-id)
        recent-vs-historical-delta (logic/compute-delta aggregate-metrics recent-events)
        structural-context (logic/gather-structural-context context target-type target-id)
        episodes (logic/evidence-window-episodes recent-events)
        model (or (:ontology-consolidator-model context)
                  (:model context))
        sheet-id (orc/build-workflow! context (logic/claim-reflection-workflow model))
        exec-result (logic/execute-reflection context sheet-id
                                        {:target-type target-type
                                         :target-id target-id
                                         :claim-set (logic/render-claim-set claims)
                                         :recent-events recent-events
                                         :aggregate-metrics aggregate-metrics
                                         :recent-vs-historical-delta recent-vs-historical-delta
                                         :structural-context structural-context})
        model-completion (some #(when (and (= (:trace-id exec-result) (:tick-id %))
                                           (:model %))
                                  %)
                               (into [] (es/read (:event-store context)
                                                {:tenant-id (:tenant-id context)
                                                 :types #{:sheet/node-execution-completed}
                                                 :tags #{[:tick (:trace-id exec-result)]}})))
        model-provenance (when model-completion
                           {:trace-id (:trace-id exec-result)
                            :model (or (:requested-model model-completion)
                                       (:model model-completion))
                            :usage (:usage model-completion)})
        operations (get-in exec-result [:outputs :operations])]
    (cond
      (not= :success (:status exec-result))
      (record-consolidation-failure! context target-type target-id exec-result)

      (not (sequential? operations))
      (do (u/log :ai.obney.orc.ontology.core.consolidator/claim-reflection-produced-no-operation-list
                 :target-type target-type :target-id target-id
                 :outputs-keys (vec (keys (:outputs exec-result))))
          (record-consolidation-failure-fact!
            context target-type target-id
            {:reason :unparseable
             :attempts 1
             :error "claim reflection produced no operation list"
             :request-id (:consolidation-request-id context)}))

      :else
      (let [{:keys [deltas rejected]} (logic/prepare-operations operations claims episodes)
            touched (into #{} (keep :target-claim) deltas)]
        (when (seq rejected)
          (u/log :ai.obney.orc.ontology.core.consolidator/claim-operations-rejected
                 :target-type target-type :target-id target-id
                 :rejections (mapv :reason rejected)))
        (u/log :ai.obney.orc.ontology.core.consolidator/claim-consolidation-completed
               :target-type target-type :target-id target-id
               :pool-size (count claims)
               :pool-claims-touched (count touched)
               :pool-claims-silent (- (count claims) (count touched))
               :operations-proposed (count operations)
               :operations-rejected (count rejected)
               :deltas-dispatched (count deltas)
               :evidence-event-count (count recent-events)
               :evidence-episode-count (count episodes))
        (cond
          (seq deltas)
          (record-claim-deltas! context target-type target-id
                                deltas (count recent-events)
                                model-provenance)

          (seq rejected)
          (record-consolidation-failure-fact!
            context target-type target-id
            {:reason :unparseable
             :attempts 1
             :error (str "every proposed claim operation was rejected: "
                         (pr-str (mapv :reason rejected)))
             :request-id (:consolidation-request-id context)})

          :else
          (do (u/log :ai.obney.orc.ontology.core.consolidator/claim-reflection-proposed-nothing
                     :target-type target-type :target-id target-id
                     :request-id (:consolidation-request-id context))
              (cp/process-command
                (assoc context :command
                       (cond-> {:command/name :ontology/record-consolidation-skip
                                :command/id (random-uuid)
                                :command/timestamp (time/now)
                                :target-type target-type
                                :target-id target-id
                                :reason :no-operations-proposed}
                         (some? (:consolidation-request-id context))
                         (assoc :request-id (:consolidation-request-id context)))))))))))

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
  (let [current-description (read-models/get-description context target-type target-id)
        recent-events (gather-recent-events context target-type target-id)
        aggregate-metrics (gather-aggregate-metrics context target-type target-id)
        recent-vs-historical-delta (logic/compute-delta aggregate-metrics recent-events)
        structural-context (logic/gather-structural-context context target-type target-id)
        model (or (:ontology-consolidator-model context)
                  (:model context))
        sheet-id (orc/build-workflow! context (logic/reflection-workflow model))
        exec-result (logic/execute-reflection context sheet-id
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
                                                 :types #{:sheet/node-execution-completed}
                                                 :tags #{[:tick (:trace-id exec-result)]}})))
        model-provenance (when model-completion
                           {:trace-id (:trace-id exec-result)
                            :model (or (:requested-model model-completion)
                                       (:model model-completion))
                            :usage (:usage model-completion)})
        outputs (:outputs exec-result)
        raw-body (when (and outputs
                            (every? #(contains? outputs %)
                                    [:capabilities :strengths :weaknesses
                                     :representative-uses :avoid-when :summary]))
                   {:capabilities                  (:capabilities outputs)
                    :strengths                     (logic/parse-vector-of-maps (:strengths outputs))
                    :weaknesses                    (logic/parse-vector-of-maps (:weaknesses outputs))
                    :representative-uses           (:representative-uses outputs)
                    :avoid-when                    (:avoid-when outputs)
                    :summary                       (:summary outputs)
                    :version                       (logic/next-version current-description)
                    :consolidated-from-event-count (count recent-events)})
        body (when raw-body
               (maybe-hydrate-parent-tree-id context target-type target-id
                                              current-description raw-body))
        body (when body
               (maybe-hydrate-behavioral-subtree-ids context target-type target-id
                                                      current-description body))]
    (cond
      (not= :success (:status exec-result))
      (record-consolidation-failure! context target-type target-id exec-result)

      (not (m/validate schemas/description-body body))
      (let [explain (me/humanize (m/explain schemas/description-body body))]
        (u/log :ai.obney.orc.ontology.core.consolidator/consolidate-validation-failed
               :target-type target-type
               :target-id target-id
               :explain explain)
        (record-consolidation-failure-fact!
          context target-type target-id
          {:reason :unparseable
           :attempts 1
           :error (str "reflection body failed description-body validation: "
                       (pr-str explain))
           :request-id (:consolidation-request-id context)}))

      :else
      (do
        (cp/process-command
          (assoc context :command (logic/record-description-command target-type target-id body
                                                              model-provenance
                                                              (:consolidation-request-id context))))
        (when (= :tree-fingerprint target-type)
          (dispatch-observed-composes-into-edges!
            context target-id (:behavioral-subtree-ids body)))))))

(defn consolidate!
  "Run the consolidation for a single (target-type, target-id) target.

   Budget gate: if the configured hourly consolidation budget for this
   target-type has been exhausted (per the rolling-hour count of
   consolidation attempts — successes AND, per CC-28's
   FailuresConsumeBudget, terminal reflection failures), the consolidation
   is skipped: no LLM call, and (PR-2) a durable
   :ontology/consolidation-skipped ANSWER — a skip is still not a failure,
   but a skip with no event was byte-indistinguishable from the 08-18
   mid-flight death, and the orphan sweep would have converted every
   routine budget skip into a FALSE :caller-interrupted record. The skip
   event is deliberately NOT folded into recent-consolidations (it consumed
   no LLM attempt and must not extend the exhaustion that caused it). The
   hour window rolls naturally; subsequent requests succeed once older
   entries fall out of the window.

   Otherwise the target takes ONE of the two write paths — claim deltas or a
   whole body, never both, see `claim-path-target-type?` — and neither path
   rejects a consolidation for its wording: the anti-recency validator is
   deleted."
  [context target-type target-id]
  (u/log :ai.obney.orc.ontology.core.consolidator/consolidate-start :target-type target-type :target-id target-id)
  (let [budget (read-models/get-consolidation-budget context target-type)
        recent-count (read-models/get-recent-consolidation-count context target-type)]
    (if (>= recent-count budget)
      (do
        (u/log :ai.obney.orc.ontology.core.consolidator/consolidate-budget-exceeded
               :target-type target-type
               :target-id target-id
               :budget budget
               :recent-count recent-count)
        (cp/process-command
          (assoc context :command
                 (cond-> {:command/name :ontology/record-consolidation-skip
                          :command/id (random-uuid)
                          :command/timestamp (time/now)
                          :target-type target-type
                          :target-id target-id
                          :reason :budget-exhausted
                          :budget budget
                          :recent-count recent-count}
                   (some? (:consolidation-request-id context))
                   (assoc :request-id (:consolidation-request-id context))))))
      (if (logic/claim-path-target-type? target-type)
        (consolidate-claims! context target-type target-id)
        (consolidate-body! context target-type target-id)))))

(defn sweep-orphaned-consolidation-requests!
  "PR-2, the CLOSED half of EveryConsolidationRequestYieldsAnOutcome: find
   every consolidation request older than the grace period with NO durable
   outcome (the 2026-08-18 shape — mid-flight JVM death between the
   reflection and the outcome append) and convert each into a durable
   :ontology/description-consolidation-failed record.

   Reason class: :caller-interrupted — the SIO-4b class, set UNCHANGED at
   five. The SIO-4b forensic identified this exact producer (JVM stopped
   mid-consolidation); a death so hard that not even the failure record
   landed is the same fact one notch harder, not a new fact. Attempts: 0 —
   a vanished request evidences NO consumed provider attempt at all
   (AttemptCountIsEvidenced at its true floor; even 1 would be a count on
   no evidence). The orphaned request's id + requested-at ride on :error,
   and :request-id makes the swept record ANSWER the orphan in the ledger,
   so a swept orphan is never swept twice.

   Callers: the consolidate-on-request processor (scoped to the target it
   is about to consolidate, excluding the in-flight request), and startup /
   operator sweeps (unscoped). Returns the swept orphan entries for
   logging; assertions belong on the projections, not this return value.

   opts:
     :older-than-ms      — grace override; default (:orphan-grace-ms ctx)
                           then `orphan-request-grace-ms`
     :target-type / :target-id — optional scope
     :exclude-request-id — never sweep this request (the one currently
                           being answered)"
  [context {:keys [older-than-ms target-type target-id exclude-request-id]}]
  (let [grace (or older-than-ms
                  (:orphan-grace-ms context)
                  logic/orphan-request-grace-ms)
        orphans (->> (read-models/get-unanswered-consolidation-requests
                       context {:older-than-ms grace
                                :target-type target-type
                                :target-id target-id})
                     (remove #(= exclude-request-id (:request-id %)))
                     vec)]
    (doseq [{:keys [request-id target-type target-id requested-at]} orphans]
      (u/log :ai.obney.orc.ontology.core.consolidator/orphaned-consolidation-request-swept
             :request-id request-id
             :target-type target-type
             :target-id target-id
             :requested-at requested-at)
      (record-consolidation-failure-fact!
        context target-type target-id
        {:reason :caller-interrupted
         :attempts 0
         :error (str "swept orphaned consolidation request " request-id
                     " requested-at " requested-at
                     ": no outcome event was ever recorded"
                     " (EveryConsolidationRequestYieldsAnOutcome)")
         :request-id request-id}))
    orphans))

;; --- Todo processors ---

(tp/defprocessor :ontology on-researcher-campaign-terminal
  {:topics #{:sheet/node-execution-completed}}
  "RR-19: translate a classified researcher campaign's behavior verdict into
   its explicit durable recurrence fact. Blocked completions and parent-level
   cancellation/abandonment are not behavior verdicts and never enter here."
  [{:keys [event] :as context}]
  (when (and (= :repl-researcher (:node-type event))
             (contains? logic/recurrence-verdicts (:status event)))
    (when-let [classification (completion-classification context event)]
      (commands/run-command!
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

(tp/defprocessor :ontology on-execution-completed-check-threshold
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

(tp/defprocessor :ontology on-emit-enrich-tree-class
  {:topics #{:sheet/rlm-tree-execution-completed}}
  "CV-2/RR-20: after an RLM emits its tree, record the emitted worked-DSL as
   a shape claim in the section its outcome earns (additive over CV-1's
   floor). Separate processor from the threshold-check above so each concern
   stays independent; both subscribe to the same bookend topic."
  [context]
  (enrich-tree-class-with-emitted-dsl! context))

(tp/defprocessor :ontology on-campaign-success-corroborate-worked-pattern
  {:topics #{:ontology/tree-class-occurrence-recorded}}
  "RR-20: reinforce a shape's worked-pattern claim when the campaign's
   durable (RR-19) verdict corroborates it — see
   `corroborate-worked-patterns-from-occurrence!`. Separate processor,
   separate topic (the campaign's terminal verdict, not the Phase-2 bookend)
   from `on-emit-enrich-tree-class` above."
  [context]
  (corroborate-worked-patterns-from-occurrence! context))

(tp/defprocessor :ontology on-description-updated-maybe-reindex
  {:topics #{:ontology/node-type-description-updated
             :ontology/node-instance-description-updated
             :ontology/tree-description-updated
             :ontology/claim-deltas-recorded}}
  "C-2b-1: after each description-updated or claim-deltas-recorded event,
   check whether the reindex-state has crossed the threshold OR timer
   trigger; if so, rebuild the ColBERT ontology-descriptions index via
   create-index!."
  [context]
  (maybe-rebuild! context))

(tp/defprocessor :ontology on-behavioral-subtree-minted-force-rebuild
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
        mark-result (commands/run-command! context
                      {:command/id (random-uuid)
                       :command/timestamp (time/now)
                       :command/name :ontology/mark-mint-reindex-forced
                       :minted-event-id minted-event-id})]
    (if (= :cognitect.anomalies/conflict (:cognitect.anomalies/category mark-result))
      (u/log :ai.obney.orc.ontology.core.todo-processors/mint-reindex-already-forced :minted-event-id minted-event-id)
      (force-rebuild! context))))

(tp/defprocessor :ontology on-tree-description-updated-project-concept
  {:topics #{:ontology/tree-description-updated}}
  "C-2d-1: project :parent-tree-id from tree-description-updated events
   into the :ontology/concepts graph as a skos:broader relationship,
   creating concepts on-demand for both endpoints."
  [context]
  (on-tree-description-updated-project-concept context))

(tp/defprocessor :ontology on-behavioral-subtree-description-updated-project-concept
  {:topics #{:ontology/tree-description-updated}}
  "R05a: project :scope :behavioral-subtree tree-description-updated
   events into the :ontology/concepts graph as behavioral-subtree
   concepts + skos:broader (parent-behavior) + behavior:composes-into
   (each entry in :composes-into) edges."
  [context]
  (on-behavioral-subtree-description-updated-project-concept context))

;; --- Harvest ---

(tp/defprocessor :ontology on-tree-class-check-harvest
  {:topics #{:sheet/node-execution-completed
             :sheet/rlm-tree-execution-completed
             :ontology/tree-class-occurrence-recorded}}
  "EL-4 + RR-19 + RR-21: after a verdict occurrence, (1) report the
   winning-shape coherence measure durably — UNCONDITIONALLY, every
   occurrence, no threshold (the spec's rule ReportSuccessfulShapeCoherence
   has none) — then (2) check whether the class has crossed the conservative
   harvest gate; if so (and not already harvested), crystallize it into a
   durable behavioral-subtree. Only :ontology/tree-class-occurrence-recorded
   identifies a :tree-class target; the other topics are subscribed for
   symmetry with the threshold processor but are no-ops here."
  [{:keys [event] :as context}]
  (when (= :ontology/tree-class-occurrence-recorded (:event/type event))
    (when-let [class-id (:assigned-tree-id event)]
      (try
        (report-shape-coherence! context class-id
                                  (:source-sheet-id event) (:source-tick-id event)
                                  (:event/id event))
        (catch Exception e
          (u/log :ai.obney.orc.ontology.core.harvest/shape-coherence-report-error :class-id class-id :error (.getMessage e))))
      (try
        (maybe-harvest! context class-id)
        (catch Exception e
          (u/log :ai.obney.orc.ontology.core.harvest/harvest-error :class-id class-id :error (.getMessage e)))))))

;; --- Consolidator ---

(tp/defprocessor :ontology consolidate-on-request
  {:topics #{:ontology/consolidation-requested}}
  "C-2a-3b: handle :ontology/consolidation-requested by running the
   reflection LLM call and emitting the matching :*-description-updated.

   PR-2 (EveryConsolidationRequestYieldsAnOutcome): the REQUEST EVENT's id
   rides the context as :consolidation-request-id, and every durable
   outcome the consolidation produces carries it — that is the
   request/outcome seam the ledger matches on. Before consolidating, the
   NEXT-PASS sweep converts this target's over-grace orphans (excluding the
   request being answered right now) into durable failure records; the
   sweep is guarded so a sweep failure can never cost the live
   consolidation."
  [{:keys [event] :as context}]
  (let [{:keys [target-type target-id]} event
        request-id (:event/id event)
        context (assoc context :consolidation-request-id request-id)]
    (try
      (sweep-orphaned-consolidation-requests!
        context {:target-type target-type
                 :target-id target-id
                 :exclude-request-id request-id})
      (catch Exception e
        (u/log :ai.obney.orc.ontology.core.consolidator/orphan-sweep-error
               :error (.getMessage e)
               :target-type target-type
               :target-id target-id)))
    (try
      (consolidate! context target-type target-id)
      (catch Exception e
        (u/log :ai.obney.orc.ontology.core.consolidator/consolidate-error
               :error (.getMessage e)
               :target-type target-type
               :target-id target-id)))))
