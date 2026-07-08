(ns ai.obney.orc.ontology.core.todo-processors
  "Todo processors for ontology self-learning.

   These event handlers implement the automatic feedback loop between
   evaluation and ontology:

   1. On evaluation/trace-evaluated: Classify failures and auto-record to tree profile
   2. On tree-profile-updated (future): Trigger re-embedding for hybrid search
   3. C-2a-3a — threshold-tracking trigger: when a target's delta-counter
      crosses its configured threshold, emit :ontology/request-consolidation
      so the consolidator processor (C-2a-3b) can run reflection.

   This closes the integration gap where judges evaluate outputs but
   results don't flow to the ontology for learning."
  (:require [ai.obney.orc.ontology.core.classifier :as classifier]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.grain.command-processor-v2.interface :as command-processor]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.todo-processor-v2.interface :refer [defprocessor]]
            [ai.obney.grain.time.interface :as time]
            [com.brunobonacci.mulog :as u]))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn run-command!
  "Execute a command through the command processor."
  [context command]
  (command-processor/process-command
    (assoc context :command command)))

(defn- transform-evaluation-result
  "Transform evaluation event format to classify-evaluation command format.

   Evaluation event dimensions: [{:name :weight :score :feedback}]
   Command expects: {:score :dimensions [{:name :score :feedback}]}"
  [{:keys [dimensions aggregate-score]}]
  {:score aggregate-score
   :dimensions (mapv (fn [{:keys [name score feedback]}]
                       {:name name
                        :score score
                        :feedback (or feedback "")})
                     dimensions)})

;; =============================================================================
;; On Trace Evaluated
;; =============================================================================

(defn on-trace-evaluated
  "Handle evaluation/trace-evaluated event.

   When a trace is evaluated by the judges:
   1. Transform the evaluation result to ontology format
   2. Call :ontology/classify-evaluation with auto-record? true
   3. This automatically records weaknesses to the tree profile

   This closes the feedback loop: evaluate → classify → learn."
  [{:keys [event] :as context}]
  (let [{:keys [trace-id sheet-id node-id dimensions aggregate-score]} event]

    (u/log ::on-trace-evaluated
           :trace-id trace-id
           :sheet-id sheet-id
           :aggregate-score aggregate-score)

    ;; Only classify if we have dimensions and a low score (potential failure)
    ;; Skip classification for high-scoring traces to reduce noise
    (when (and (seq dimensions)
               (< aggregate-score 0.8))  ;; Threshold for "potential failure"

      (let [evaluation-result (transform-evaluation-result event)]
        (u/log ::classifying-evaluation
               :trace-id trace-id
               :num-dimensions (count dimensions)
               :aggregate-score aggregate-score)

        (try
          (run-command! context
            {:command/id (random-uuid)
             :command/timestamp (time/now)
             :command/name :ontology/classify-evaluation
             :trace-id trace-id
             :sheet-id sheet-id
             :node-id node-id
             :evaluation-result evaluation-result
             :auto-record? true})  ;; Key: automatically record weaknesses

          (catch Exception e
            (u/log ::classify-evaluation-error
                   :error (.getMessage e)
                   :trace-id trace-id)))))))

;; =============================================================================
;; On High-Scoring Trace (Record Strengths)
;; =============================================================================

(defn on-high-scoring-trace
  "Handle evaluation/trace-evaluated event for successful traces.

   When a trace scores high (>= 0.85), record as a strength:
   1. Identify which success patterns might apply
   2. Record the strength to the tree profile

   This complements on-trace-evaluated which handles failures."
  [{:keys [event] :as context}]
  (let [{:keys [trace-id sheet-id aggregate-score]} event]

    ;; Only record strengths for high-scoring traces
    (when (>= aggregate-score 0.85)
      (u/log ::recording-high-score-strength
             :trace-id trace-id
             :sheet-id sheet-id
             :aggregate-score aggregate-score)

      ;; For now, record as a generic success pattern
      ;; In the future, use pattern detection to identify specific patterns
      (try
        (run-command! context
          {:command/id (random-uuid)
           :command/timestamp (time/now)
           :command/name :ontology/record-tree-strength
           :tree-id sheet-id
           :pattern-uri "success:HighQualityOutput"
           :confidence aggregate-score
           :evidence-trace-ids [trace-id]
           :avg-score aggregate-score})

        (catch Exception e
          (u/log ::record-strength-error
                 :error (.getMessage e)
                 :trace-id trace-id))))))

;; =============================================================================
;; Processor Registration
;; =============================================================================

(defprocessor :ontology on-trace-evaluated
  {:topics #{:evaluation/trace-evaluated}}
  "Handle evaluation/trace-evaluated: classify failures and auto-record to ontology."
  [context]
  (on-trace-evaluated context)
  ;; Also check for high-scoring traces to record strengths
  (on-high-scoring-trace context))

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

(defn on-task-classified
  "C-Loop-1 threshold-trigger logic for :ontology/task-classified.
   The classifier's substrate — one assignment per R-Inject run ticks
   the counter under [:tree-class assigned-tree-id]. When the per-
   tree-class threshold crosses, the consolidator updates the
   description body the classifier reads from."
  [{:keys [event] :as context}]
  (when-let [tree-class-id (:assigned-tree-id event)]
    (maybe-fire-consolidation! context :tree-class tree-class-id)))

(defprocessor :ontology on-execution-completed-check-threshold
  {:topics #{:sheet/node-execution-completed
             :sheet/rlm-tree-execution-completed
             :ontology/task-classified}}
  "C-2a-3a + C-Loop-1: after each execution-completion or task-classified
   event, check whether the affected target's delta-counter has crossed
   its configured threshold; emit :ontology/request-consolidation if so."
  [{:keys [event] :as context}]
  (case (:event/type event)
    :sheet/node-execution-completed     (on-node-execution-completed context)
    :sheet/rlm-tree-execution-completed (on-rlm-tree-execution-completed context)
    :ontology/task-classified           (on-task-classified context)
    nil))

;; =============================================================================
;; CV-2 (ADR 0017 decision 3) — post-emit emitted-tree worked-DSL enrichment
;; =============================================================================
;;
;; ADR 0015's literal "capture the emitted TREE": once the RLM emits its tree
;; (the Phase-2 :sheet/rlm-tree-execution-completed bookend now carries the
;; emitted worked-DSL + the SOURCE sheet-id), enrich the assigned :tree-class
;; description with that DSL as a :strengths[].:recommended-pattern — the
;; content EL-4 harvest reads so a harvested specialist ships the REAL proven
;; pattern, not just the CV-1 signature summary.
;;
;; ADDITIVE over CV-1's floor: the floor already made the class retrievable at
;; classify time (robust to turn timeouts). This enrichment lands ONLY when an
;; emit completes — a turn that times out before emit carries no :generated-tree
;; → no enrichment → the class is still retrievable via the CV-1 summary (never
;; a regression). Re-orchestration, NOT rewrite: reuses the sheet->class join,
;; get-description, and record-tree-class-description — NO second synthesis LLM.

(def ^:private emitted-pattern-trait
  "Stable :trait marker for the single :strengths entry that carries the
   post-emit worked-DSL. Stable so a re-emit UPDATES that one entry rather
   than appending a new strength on every turn (thrash-safe)."
  "emitted worked-DSL (post-emit capture)")

(defn- upsert-emitted-pattern-strength
  "Return `body` with its emitted-pattern strength added/updated to carry
   `dsl-str` as :recommended-pattern. Preserves every other key (incl.
   CV-1's :summary) and every OTHER strength. Idempotent by trait: at most
   ONE emitted-pattern strength exists; a re-emit bumps :evidence-count +
   :last-reinforced-at and keeps the original :first-observed-at."
  [body dsl-str now]
  (let [strengths (vec (:strengths body))
        idx (first (keep-indexed
                     (fn [i s] (when (= emitted-pattern-trait (:trait s)) i))
                     strengths))
        existing (when idx (nth strengths idx))
        entry {:trait emitted-pattern-trait
               :good-when (or (:summary body) "")
               :recommended-pattern dsl-str
               :confidence 1.0
               :evidence-count (inc (or (:evidence-count existing) 0))
               :first-observed-at (or (:first-observed-at existing) now)
               :last-reinforced-at now}]
    (assoc body :strengths
           (if idx (assoc strengths idx entry) (conj strengths entry)))))

(defn enrich-tree-class-with-emitted-dsl!
  "CV-2: on a completion event carrying :generated-tree + :source-sheet-id,
   resolve the tree-class for the source sheet, read its CURRENT description,
   and re-record it with the emitted DSL as a :recommended-pattern strength.

   No-ops (never crashes) when:
     - the event carries no emitted tree / source sheet (timeout / legacy),
     - the source sheet was never classified (no class to enrich),
     - the class has no description yet (nothing to enrich additively),
     - the emitted DSL is ALREADY the recorded pattern (thrash-safe: skip the
       re-record so no redundant tree-description-updated / reindex fires)."
  [{:keys [event] :as context}]
  (let [{:keys [generated-tree source-sheet-id]} event]
    (when (and (some? generated-tree) (some? source-sheet-id))
      (when-let [class-id (rm/get-tree-class-for-sheet context source-sheet-id)]
        (when-let [current (rm/get-description context :tree-class class-id)]
          (let [dsl-str (pr-str generated-tree)
                already? (some #(and (= emitted-pattern-trait (:trait %))
                                     (= dsl-str (:recommended-pattern %)))
                               (:strengths current))]
            (when-not already?
              (let [now (time/now)
                    merged (upsert-emitted-pattern-strength current dsl-str (str now))]
                (u/log ::enriching-tree-class-with-emitted-dsl
                       :class-id class-id :source-sheet-id source-sheet-id)
                (run-command! context
                  {:command/name :ontology/record-tree-class-description
                   :command/id (random-uuid)
                   :command/timestamp now
                   :target-id class-id
                   :body merged})))))))))

(defprocessor :ontology on-emit-enrich-tree-class
  {:topics #{:sheet/rlm-tree-execution-completed}}
  "CV-2: after an RLM emits its tree, record the emitted worked-DSL as the
   assigned tree-class's :recommended-pattern (additive over CV-1's floor).
   Separate processor from the threshold-check above so each concern stays
   independent; both subscribe to the same bookend topic."
  [context]
  (enrich-tree-class-with-emitted-dsl! context))

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
             :ontology/tree-description-updated}}
  "C-2b-1: after each description-updated event, check whether the
   reindex-state has crossed the threshold OR timer trigger; if so,
   rebuild the ColBERT ontology-descriptions index via create-index!."
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
   newly-rebuilt corpus already includes the mint)."
  [context]
  (force-rebuild! context))

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

(defn- ensure-tree-class-concept!
  "If a concept with the given target-id's URI doesn't exist in the
   concepts read-model, dispatch :ontology/create-concept to bring it
   into existence. Returns the URI."
  [context target-id]
  (let [uri (tree-class-uri target-id)
        concepts (rmp/project context :ontology/concepts)]
    (when-not (contains? concepts uri)
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
                :indicators []})))
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
                    :source-uri target-uri
                    :target-uri parent-uri
                    :predicate "skos:broader"
                    :properties {}})))))))

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
                :indicators []})))
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
                        :source-uri target-uri
                        :target-uri parent-uri
                        :predicate "skos:broader"
                        :properties {}})))))

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
                        :source-uri target-uri
                        :target-uri shell-uri
                        :predicate "behavior:composes-into"
                        :properties {}})))))))))

(defprocessor :ontology on-behavioral-subtree-description-updated-project-concept
  {:topics #{:ontology/tree-description-updated}}
  "R05a: project :scope :behavioral-subtree tree-description-updated
   events into the :ontology/concepts graph as behavioral-subtree
   concepts + skos:broader (parent-behavior) + behavior:composes-into
   (each entry in :composes-into) edges."
  [context]
  (on-behavioral-subtree-description-updated-project-concept context))
