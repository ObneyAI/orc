(ns ai.obney.orc.evaluation.core.judge-runtime
  "Per-event evaluator runtime: judges as durable assessments (ADR 0008).

   Two processors:

   1. `on-node-execution-completed` subscribes to :sheet/node-execution-completed.
      For each judge effective on the completed node it REQUESTS an assessment
      (`:evaluation/assessment-requested`, identified by the completion, the judge
      and the judge's revision) on the pure path with a CAS, so replaying a
      completion requests nothing new. It judges nothing.
   2. `on-assessment-requested` subscribes to those requests. For a request with
      no outcome yet it starts one background future that runs the judge and
      records the outcome (scored, failed or ungradable) through
      `:evaluation/record-assessment-outcome`. A scored outcome of a LEARNING
      judge that carries feedback also becomes the legacy
      `:judge/score-emitted` record the learning loops read.

   Attaching a judge is what enables it: an explicitly attached judge assesses
   with or without the Living Description flag. The flag (set with
   `:ontology/set-living-description-enabled`) gates only the five DEFAULT
   judges, which apply to a :repl-researcher with no explicit attachment.

   `:rlm/tree-generated` (Gap-7b) still grades the campaign's last tree through
   the earlier direct path (`run-judges-and-dispatch!`)."
  (:require [ai.obney.grain.todo-processor-v2.interface :refer [defprocessor]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.evaluation.core.assessments :as assessments]
            [ai.obney.orc.evaluation.core.judge-run :as judge-run]
            [ai.obney.orc.evaluation.core.node-version :as node-version]
            [ai.obney.orc.evaluation.core.heuristic-structural :as heuristic-structural]
            [com.brunobonacci.mulog :as u]))

;; =============================================================================
;; Living Description opt-in gate (lazily resolved)
;; =============================================================================

(defn- living-description-enabled?
  "Return the system-level Living Description opt-in flag.

   The flag lives in the ontology component. We resolve it lazily via
   `requiring-resolve` so the evaluation component does NOT hard-depend on
   ontology: judges run as a standalone capability (Layer 1) with no DJL,
   no ColBERT, no Python on the classpath. When ontology is not present,
   the flag is simply false: an EXPLICITLY attached judge still assesses (the
   flag does not gate attachments), while the opt-in default judges do not
   apply and the self-improving write-side (which needs ontology anyway)
   stays dormant. When ontology IS present this is identical to calling
   `ontology/get-living-description-enabled?`."
  [ctx]
  (boolean
   (when-let [f (try (requiring-resolve
                      'ai.obney.orc.ontology.interface/get-living-description-enabled?)
                     (catch Throwable _ nil))]
     (f ctx))))

;; =============================================================================
;; Trace-data construction
;; =============================================================================

(defn- find-started-inputs
  "Gap-7 fix#1: when the completion event has no :inputs, reach back to
   the matching :sheet/node-execution-started event and return its
   :inputs. Without this, LLM judges on terminal repl-researcher
   completions get empty inputs context, the rubric prompt renders
   `{inputs}` as `{}`, OpenRouter responses lack a valid :score, and
   judges silently nil. Returns nil if no matching started event is
   found.

   RR-31: the query is scoped to this tick (`:tags #{[:tick tick-id]}`)
   rather than scanning every :sheet/node-execution-started event the
   tenant has ever emitted — the same O(store)-per-judged-completion shape
   as the survey-hang root cause. The in-memory filter below already
   narrowed to this tick-id; this just stops fetching every other tick's
   events to do it."
  [ctx sheet-id tick-id node-id]
  (when (and (:event-store ctx) sheet-id tick-id node-id)
    (let [started-events (into [] (es/read (:event-store ctx)
                                            {:types #{:sheet/node-execution-started}
                                             :tenant-id (:tenant-id ctx)
                                             :tags #{[:tick tick-id]}}))
          matching (first (filter #(and (= sheet-id (:sheet-id %))
                                         (= tick-id (:tick-id %))
                                         (= node-id (:node-id %)))
                                  started-events))]
      (:inputs matching))))

(defn- find-tick-repl-researcher-source
  "Gap-7b: given an :rlm/tree-generated event's tick-id, locate the
   matching :sheet/node-execution-started event for the host
   repl-researcher node. Returns nil when no matching started event
   exists or when none of the candidates is a repl-researcher.

   The bench's recursive RLM emits multiple node-execution-started
   events per tick (root + Phase 2 children + the repl-researcher).
   Plain first-match returns the wrong node — we filter to the one
   whose read-model entry is :repl-researcher.

   Modern :rlm/tree-generated events carry :sheet-id + :node-id in
   the body directly (the producer knows them); this helper is the
   fallback path for legacy events that omit those fields."
  [ctx tick-id]
  (when (and (:event-store ctx) tick-id)
    (let [started-events (into [] (es/read (:event-store ctx)
                                            {:types #{:sheet/node-execution-started}
                                             :tenant-id (:tenant-id ctx)}))
          candidates (filter #(= tick-id (:tick-id %)) started-events)
          matching (first (filter (fn [evt]
                                    (= :repl-researcher
                                       (:type (orc/get-node ctx (:sheet-id evt) (:node-id evt)))))
                                  candidates))]
      (when matching
        {:sheet-id (:sheet-id matching)
         :node-id (:node-id matching)
         :inputs (or (:inputs matching) {})}))))

(defn- strip-engine-keys
  "Drop the engine's own bookkeeping keys (map-each markers, tick iteration,
   durable order) from a map of values: they are not data any node declared,
   and a strict judge schema rejects them."
  [m]
  (into {} (remove (comp orc/value-log-engine-key? key)) m))

(defn- resolved-reads-inputs
  "RR-31: the node's :inputs, resolved from its recorded reads via the value
   log. `event` is the `:sheet/node-execution-completed` body — it carries
   :read-keys (what the node declared it reads) and :read-sources (where
   each key's value came from); `orc/value-log-resolve-reads` walks those
   pointers back to the actual values the node read, the same way
   build-trace-data's :outputs already resolves the node's writes.

   Any non-empty direct :inputs on the event (execution context / map-each
   item overrides — the only thing `:inputs` carries since the value-log
   merge, per todo_processors.clj's root-start emit) is layered OVER the
   resolved reads: those are overrides the caller has already computed, not
   a substitute for the reads. When there is no direct :inputs, the resolved
   reads stand alone."
  [ctx tick-id event]
  (let [resolved (or (orc/value-log-resolve-reads (:event-store ctx) (:tenant-id ctx)
                                                  tick-id event)
                     {})
        direct-inputs (not-empty (:inputs event))]
    (merge resolved direct-inputs)))

(defn- descendant-ids
  "The ids of every node beneath `node-id` in `nodes-by-id`."
  [nodes-by-id node-id]
  (loop [acc #{} frontier [node-id]]
    (if (empty? frontier)
      acc
      (let [kids (into [] (mapcat #(:children-ids (get nodes-by-id %))) frontier)]
        (recur (into acc kids) kids)))))

(defn- composite-reads
  "What a composite (a node with children and no read declaration of its own)
   read from OUTSIDE itself: the values its descendants in this tick read that
   no earlier descendant had written, resolved from the value log. Values a
   composite's children pass to each other are internal to it, not its reads."
  [ctx tick-id event node]
  (let [nodes-by-id (orc/get-nodes-by-id ctx (:sheet-id event))
        beneath (descendant-ids nodes-by-id (:id node))
        tick-events (orc/value-log-read-tick-events (:event-store ctx) (:tenant-id ctx) tick-id)
        completions (->> tick-events
                         (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                       (contains? beneath (:node-id %))))
                         (sort-by #(str (:event/id %))))
        external (loop [cs completions, written #{}, found {}]
                   (if-let [c (first cs)]
                     (let [fresh (into [] (remove #(or (contains? written %) (contains? found %)))
                                       (:read-keys c))]
                       (recur (rest cs)
                              (into written (:write-keys c))
                              (into found (map (fn [k] [k c])) fresh)))
                     found))]
    (into {}
          (mapcat (fn [[completion ks]]
                    (select-keys (orc/value-log-resolve-reads (:event-store ctx) (:tenant-id ctx)
                                                              tick-id completion)
                                 (map first ks))))
          (group-by val external))))

(defn- root-tick-id
  "The root of the run `tick-id` belongs to: follow :parent-tick-id up the
   durable tick-started events (delegates and generated trees start child
   ticks)."
  [ctx tick-id]
  (loop [id tick-id, depth 0]
    (let [parent (:parent-tick-id
                  (orc/value-log-tick-started-event ctx (:tenant-id ctx) id))]
      (if (and parent (< depth 64)) (recur parent (inc depth)) id))))

(defn- original-task
  "The original task of the run: the root run's inputs, resolved values, kept
   separate from any node's own instruction."
  [ctx tick-id]
  (strip-engine-keys
   (or (orc/value-log-tick-seeds ctx (:tenant-id ctx) (root-tick-id ctx tick-id)) {})))

(defn- run-node
  "The node a completion is for, as it ran: its entry in the run's durable tick
   execution context (the definition the run actually executed - for a published
   run, a node of the run's own that the live sheet does not have). Falls back
   to the sheet's live node only for a run with no snapshot."
  [ctx {:keys [sheet-id tick-id node-id]}]
  (or (some-> (orc/get-tick-execution-context ctx tick-id)
              (get-in [:nodes-by-id node-id]))
      (when (and sheet-id node-id) (orc/get-node ctx sheet-id node-id))))

(defn- build-trace-data
  "Build the `trace-data` map the evaluation judges expect:
   `{:inputs <host-input-values> :outputs <host-output-values>
     :instruction <host-instruction> :original-task <root-run-inputs>
     :researcher-iterations <ordered-durable-records, when applicable>}`.

   `event` is the `:sheet/node-execution-completed` event body.

   Evidence by scope: a leaf's :inputs are its own resolved reads; a composite
   (sequence, fallback, parallel, map-each) records no reads of its own, so its
   :inputs are what its descendants read from outside it. :outputs are the
   node's own writes resolved from the value log, INCLUDING writes forwarded
   from a delegate's child run (`:write-sources`). A composite has no
   instruction: :instruction stays nil rather than being invented. The run's
   original task is a separate item, never folded into the node's instruction.
   The engine's own bookkeeping keys are never part of the evidence.

   RR-31: when the completion records :read-keys, :inputs is resolved from
   the value log (`resolved-reads-inputs`) — the node's ACTUAL recorded
   reads, not the execution-context leftovers the event itself carries.
   Completions that record no :read-keys (direct-tick / researcher-terminal
   completions, which never went through the read-key bookkeeping) keep the
   pre-RR-31 behavior: direct :inputs on the event, else a reach-back to the
   matching :sheet/node-execution-started event. Researcher iterations are
   read from their durable projection rather than racing asynchronous
   execution-trace publication."
  [ctx event]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        node (run-node ctx event)
        read-keys (:read-keys event)
        direct-inputs (:inputs event)
        composite? (and (seq (:children-ids node)) (empty? read-keys))
        inputs (strip-engine-keys
                (cond
                  (seq read-keys) (resolved-reads-inputs ctx tick-id event)
                  composite? (composite-reads ctx tick-id event node)
                  :else (or (not-empty direct-inputs)
                            (find-started-inputs ctx sheet-id tick-id node-id)
                            {})))
        ;; The completion event carries only :write-keys and :write-sources —
        ;; values live in the tick's :sheet/execution-value-written events (or,
        ;; for a delegate or composite, in the child run the sources point
        ;; into). Resolve them by (node-id, exec-context) so judges score
        ;; against what THIS node execution actually produced. An empty map
        ;; here would silently degrade every grounding score rather than fail
        ;; loudly.
        outputs (orc/value-log-resolve-writes (:event-store ctx) (:tenant-id ctx) tick-id event)
        ;; RR-33: the node's declared writes, for judges that need to name
        ;; the task when the node has no instruction (e.g. a `code` node,
        ;; whose DSL takes no instruction). Prefer the completion event's
        ;; own :write-keys (the shape it recorded at completion time); fall
        ;; back to the resolved outputs' keys so this is never empty when
        ;; the node in fact wrote something.
        write-keys (or (not-empty (:write-keys event))
                       (vec (keys outputs)))]
    (cond->
     {:node-id node-id
      :tick-id tick-id
      :exec-context (second (orc/value-log-execution-key event))
      :inputs inputs
      :outputs outputs
      :write-keys write-keys
      :original-task (original-task ctx tick-id)
      ;; RR-33: pass the node's instruction through as nil (not "") when
      ;; absent — an empty string is truthy under `or`, so the pre-RR-33
      ;; `(or (:instruction node) "")` here silently defeated every
      ;; downstream "No instruction provided" fallback. nil lets
      ;; compose-task (judges.clj) tell "no instruction" from "instruction
      ;; is the empty string" and fall through to :criteria / declared
      ;; write keys.
      :instruction (:instruction node)}
      (= :repl-researcher (:type node))
      (assoc :researcher-iterations
             (orc/get-researcher-iteration-records
              ctx sheet-id tick-id node-id)))))

;; =============================================================================
;; Judge dispatch
;; =============================================================================

(defn- project-dimensions
  "RR-30: a default LLM judge's own evidence lists projected into its one named
   DimensionScore. The projection lives with the judge run path
   (`judge-run/project-dimensions`); kept here under its original name."
  [judge-type inner score]
  (judge-run/project-dimensions judge-type inner score))

(defn- outcome->legacy
  "Map a judge OUTCOME (`judge-run/run-judge`) to the canonical
   `{:score :feedback :dimensions :model-provenance}` shape the
   `:evaluation/record-judge-score` command records, or nil.

   Only a :scored outcome carrying feedback is recorded: the legacy score event
   requires feedback, and a score-only outcome (rubric :feedback :none) has
   none and must not be given invented text - it is logged and skipped until
   assessments are recorded durably. A :failed or :ungradable outcome never
   becomes a score; it is logged with its reason."
  [judge-type outcome]
  (case (:status outcome)
    :scored
    (if (contains? outcome :feedback)
      {:score (:score outcome)
       :feedback (:feedback outcome)
       :dimensions (:dimensions outcome)
       ;; The legacy event carries one provenance map: the judge's model call.
       :model-provenance (some-> (first (:model-provenance outcome))
                                 (select-keys [:provider :model :usage]))}
      (do (u/log ::score-only-result-not-recorded
                 :judge-type judge-type
                 :score (:score outcome)
                 :band (:band outcome))
          nil))

    (do (u/log ::judge-not-scored
               :judge-type judge-type
               :status (:status outcome)
               :reason (:reason outcome)
               :message (:message outcome))
        nil)))

(def ^:private llm-judge-types
  "The built-in judge types: each runs as a shipped behaviour (judge-behaviours)."
  #{:grounding :reasoning :completeness :instruction-following})

(defn- invoke-heuristic-structural
  "Gap-2: heuristic structural evaluator. Scores the shape of the tree
   the host node emitted (via Phase 1 emit-tree! → :generated-tree-raw
   in :writes). Returns nil when the node didn't emit a tree, signaling
   the judge to gracefully no-op rather than score nothing."
  [trace-data]
  (when-let [tree (get-in trace-data [:outputs :generated-tree-raw])]
    (heuristic-structural/evaluate-tree-structure tree)))

(defn- invoke-custom-judge
  "Gap-4: run a consumer-defined judge workflow against the host node's
   trace-data and harvest a score. A custom judge is run exactly like every
   other judge (`judge-run/run-judge`): its workflow reads `:host-inputs`,
   `:host-outputs`, `:host-instruction`, `:host-trace` (and `:rubric` when the
   judge declares one) and writes either a numeric `:score` + `:feedback`
   (+ optional `:dimensions`) or, with a rubric, a `:band`.

   Returns the canonical {:score :feedback :dimensions} shape, or nil when the
   judge produced no valid result; every failure is logged loudly with its
   reason - silent skipping is the failure mode the unification arc fought.

   Confinement is durable, not a depth counter: the judge's run is marked as
   assessment work (see `judge-run/run-judge`), so nothing inside it is ever
   auto-assessed."
  [ctx judge-config trace-data]
  (outcome->legacy :custom (judge-run/run-judge ctx judge-config trace-data)))

(defn- invoke-judge
  "Invoke a single attached judge against the host node's trace-data.
   Returns the {:score :feedback :dimensions} canonical shape, or nil
   when the judge produced no valid result. Every LLM or custom judge is an
   ORC workflow run through `judge-run/run-judge`; heuristic-structural stays
   a deterministic code path (it is pure tree-shape arithmetic with no model
   call, so there is nothing for a workflow to budget, retry or account)."
  [ctx judge-config trace-data]
  (let [judge-type (:type judge-config)]
    (cond
      (contains? llm-judge-types judge-type)
      (outcome->legacy judge-type (judge-run/run-judge ctx judge-config trace-data))

      (= :heuristic-structural judge-type)
      (invoke-heuristic-structural trace-data)

      (= :custom judge-type)
      (invoke-custom-judge ctx judge-config trace-data)

      :else nil)))

;; =============================================================================
;; Event construction
;; =============================================================================

(defn- ->record-judge-score-command
  "Pure builder: the body for the `:evaluation/record-judge-score`
   command. The command handler is the sole writer of
   `:judge/score-emitted`; this builds the body it validates + emits.
   `source` is any map carrying :sheet-id / :node-id / :tick-id."
  [source judge-name judge-config result]
  {:command/id (random-uuid)
   :command/timestamp (time/now)
   :command/name :evaluation/record-judge-score
   :sheet-id (:sheet-id source)
   :tick-id (:tick-id source)
   :node-id (:node-id source)
   :judge-name judge-name
   :judge-config judge-config
   :score (:score result)
   :feedback (:feedback result)
   :dimensions (:dimensions result)
   :model-provenance (:model-provenance result)
   :emitted-at (str (java.time.Instant/now))})

;; =============================================================================
;; Gap-8 — multi-judge weight aggregation
;; =============================================================================
;;
;; When multiple judges fire on the same (sheet, node, tick) tuple,
;; produce a single weighted composite score in [0.0, 1.0]. Default
;; policy (per spec): even-weight when no weights are specified;
;; explicit weights normalized to sum to 1.0. A single judge always
;; composites to its own score (weight = 1.0 after normalization),
;; matching consumer intuition for the common case.

(defn- normalize-judge-weights
  "Pure: given a seq of `{:judge-name :score :weight}` entries (where
   :score is numeric and :weight is optional), return a vector with
   :weight set to the EFFECTIVE normalized weight each entry contributes
   to the composite. Result weights always sum to ≤ 1.0 (= 1.0 except
   when all entries have :weight 0).

   Policy (Gap-8 RED#4 — share remaining mass):
   - **No :weight on any entry** → each entry gets 1/N (even distribution).
   - **All entries have :weight** → weights are re-normalized to sum to
     1.0 (relative weighting; consumer's input is a ratio, not absolute).
   - **Mixed (some :weight, some not)**:
     - When the explicit weights sum to < 1.0: explicit values are kept
       verbatim; un-weighted entries share the remaining mass evenly.
     - When the explicit weights sum to ≥ 1.0: un-weighted entries get
       0.0 (the explicit budget is already exhausted); explicit weights
       are re-normalized to sum to 1.0.

   Rationale: adding `:weight` to one judge must NOT silently zero the
   others. Consumers say 'weight THIS judge specifically; defaults for
   the rest'. The share-remaining policy preserves that intuition.

   Returns nil if no entries are scorable or total mass is zero."
  [entries]
  (let [scorable (filter #(number? (:score %)) entries)
        ;; Round computed weights to 4 decimals to keep event bodies
        ;; clean of float-subtraction noise (e.g. 1.0 - 0.7 yields
        ;; 0.30000000000000004 in IEEE 754); consistent with the
        ;; per-decimal rounding in compute-composite-score.
        round4 (fn [w] (-> w (* 10000) Math/round (/ 10000.0)))]
    (when (seq scorable)
      (let [weighted   (filter #(number? (:weight %)) scorable)
            unweighted (remove #(number? (:weight %)) scorable)
            explicit-sum (reduce + 0.0 (map :weight weighted))]
        (cond
          ;; No explicit weights anywhere → even 1/N distribution.
          (empty? weighted)
          (let [w (round4 (/ 1.0 (count scorable)))]
            (mapv #(assoc % :weight w) scorable))

          ;; All entries explicit → re-normalize to sum to 1.0
          ;; (preserves the all-weighted-with-arbitrary-sum behavior).
          (empty? unweighted)
          (when (pos? explicit-sum)
            (mapv #(assoc % :weight (round4 (/ (:weight %) explicit-sum))) weighted))

          ;; Mixed + explicit-sum ≥ 1.0 → un-weighted get 0.0;
          ;; explicit re-normalized to sum to 1.0.
          (>= explicit-sum 1.0)
          (when (pos? explicit-sum)
            (into []
                  (concat
                    (mapv #(assoc % :weight (round4 (/ (:weight %) explicit-sum))) weighted)
                    (mapv #(assoc % :weight 0.0) unweighted))))

          ;; Mixed + explicit-sum < 1.0 → un-weighted share remaining
          ;; mass (1.0 - explicit-sum) evenly.
          :else
          (let [remaining (- 1.0 explicit-sum)
                share (round4 (/ remaining (count unweighted)))]
            (into []
                  (concat
                    weighted
                    (mapv #(assoc % :weight share) unweighted)))))))))

(defn- compute-composite-score
  "Pure: takes a seq of `{:judge-name :score :weight}` entries and
   returns the weighted composite score, or nil if the entries are
   empty. See `normalize-judge-weights` for the weight policy.

   Returns a double rounded to 4 decimal places to keep schema-level
   equality predictable (avoids floating-point noise in test diffs).

   Entries with non-numeric :score are skipped; if NO entries have a
   numeric score, returns nil."
  [entries]
  (when-let [normalized (normalize-judge-weights entries)]
    (let [composite (reduce + 0.0 (map (fn [{:keys [score weight]}]
                                          (* score weight))
                                        normalized))]
      (-> composite
          (* 10000)
          Math/round
          (/ 10000.0)))))

(defn- ->record-composite-score-command
  "Gap-8: build the body for the `:evaluation/record-composite-score`
   command from the judge results collected during a single processor
   cycle. Returns nil when fewer than 2 judges have valid scores —
   single-judge ticks don't need a composite distinct from the score
   itself, and zero-judge ticks have nothing to composite.

   `judge-results` is a seq of `{:judge-name :judge-config :result}`
   where :result is the canonical `{:score :feedback :dimensions}`
   shape (or nil for failed judges).

   The `:contributing-judges` field shows the EFFECTIVE normalized
   weight each judge contributed (e.g., `1/N` when consumers didn't set
   explicit weights), not the raw :judge-config value. This is what
   consumers want for debugging the composite: 'how did this number get
   computed?'

   The command handler is the sole writer of
   `:judge/composite-score-computed`; this builds the body it validates
   + emits. `source` carries :sheet-id / :node-id / :tick-id."
  ([source judge-results]
   ;; The direct (tree-shape) path: every judge that returned nothing counts as
   ;; one that did not score.
   (->record-composite-score-command
    source judge-results
    (let [scored (count (filter #(number? (:score (:result %))) judge-results))]
      ;; The direct path forms a composite only from two or more scores.
      {:expected (if (>= scored 2) (count judge-results) 0) :scored scored
       :failed (- (count judge-results) scored) :ungradable 0})))
  ([source judge-results coverage]
   (let [raw-entries (keep (fn [{:keys [judge-name judge-config result]}]
                             (when (and result (number? (:score result)))
                               {:judge-name judge-name
                                :score (:score result)
                                :weight (:weight judge-config)}))
                           judge-results)
         normalized (when (seq raw-entries) (normalize-judge-weights raw-entries))
         composite (when (seq raw-entries) (compute-composite-score raw-entries))]
     ;; A composite needs at least two judges expected. With one or more scored
     ;; it carries the mean over the scored; with none scored it carries
     ;; coverage only and NO score - a score is never invented.
     (when (>= (:expected coverage) 2)
       (cond-> {:command/id (random-uuid)
                :command/timestamp (time/now)
                :command/name :evaluation/record-composite-score
                :sheet-id (:sheet-id source)
                :tick-id (:tick-id source)
                :node-id (:node-id source)
                :contributing-judges (mapv (fn [{:keys [judge-name score weight]}]
                                             {:judge-name judge-name
                                              :score (double score)
                                              :weight (double weight)})
                                           normalized)
                :coverage coverage
                :partial (< (:scored coverage) (:expected coverage))
                :purpose :learning
                :emitted-at (str (java.time.Instant/now))}
         composite (assoc :composite-score composite)
         (:subject-completion-id source) (assoc :subject-completion-id
                                                (:subject-completion-id source)))))))

;; =============================================================================
;; Gap-5 — Default judge attachment for repl-researcher nodes
;; =============================================================================
;;
;; When the Living Description opt-in flag is on AND a repl-researcher
;; node has NO explicit :judges attached, the resolver applies a default
;; set. Consumer's explicit :sheet/set-node-judges (even with an empty
;; vec) always wins.

(def ^:private default-judges
  "Default judge entries auto-attached to repl-researcher nodes when
   the opt-in flag is on. Each entry is {:judge-name :judge-config}.
   Judge names are bare (e.g., \"grounding\") so downstream consumers
   querying judge-scores see them under the canonical name.

   ---
   IMPORTANT HISTORICAL CONTEXT — read before adding
   :applies-to-completion-kinds back to these defaults
   ---

   Gap-7 (2026-06-04) attempted to route these 5 judges by
   completion kind: heuristic-structural to intermediate ticks (which
   were assumed to carry :generated-tree-raw), the 4 LLM judges to
   terminal ticks (which were assumed to carry final task outputs).

   The LIVE verify of Gap-7 on legal-issue-detection revealed that
   premise was WRONG for the recursive RLM bench path:

     1. Recursive RLM only dispatches ONE :sheet/node-execution-completed
        per run — at terminal time when (final!) fires. There are no
        intermediate :sheet/node-execution-completed events; the
        Phase 1 ↔ Phase 2 loop is internal to the executor.

     2. The single terminal event carries EVERYTHING the executor
        accumulated: :issues + :ambiguities + ... (the final
        synthesized outputs) AND :generated-tree-raw + :iterations
        (the Phase 1 work products). Both heuristic-structural AND
        the LLM judges have valid inputs to grade.

   Net effect of applying :applies-to-completion-kinds #{:tree-iteration}
   to heuristic-structural: it never fires because no event of that
   kind ever arrives. The bench LOST a judge that previously fired
   usefully. That's a regression.

   Resolution chosen (2026-06-05): leave the defaults WITHOUT
   :applies-to-completion-kinds so all 5 judges attempt every
   repl-researcher terminal completion. The 4-arg resolver arity,
   the :completion-kind event field, and the command-handler
   derivation logic ALL REMAIN as infrastructure. A user can still
   attach a CUSTOM judge with :applies-to-completion-kinds on their
   own node — the filter only fires when the field is present.

   The tree-event grading use case (where we'd want
   heuristic-structural to fire on the campaign's :rlm/tree-generated
   event rather than only on the terminal sum-up) is filed as
   Gap-7b — `docs/issues/c2d-followups/Gap-7b-heuristic-structural-
   subscribes-to-rlm-tree-generated.md`. Gap-7b adds a SEPARATE
   processor subscribed to :rlm/tree-generated; it doesn't change
   this defaults list.

   What you SHOULD NOT do without Gap-7b shipping first:
     - Set :applies-to-completion-kinds #{:tree-iteration} on
       heuristic-structural here. There are no :tree-iteration
       events to fire on; this would silently regress.
     - Set :applies-to-completion-kinds #{:terminal} on the 4 LLM
       judges. That LOOKS correct, but it would only help once
       Gap-7b is shipped AND the executor stops carrying
       :generated-tree-raw on terminal events (otherwise nothing
       changes — both kinds of evidence are on terminal anyway).

   What you CAN do safely on top of this list:
     - Add new judge types via consumer code with their own
       :applies-to-completion-kinds.
     - Add a new judge type to this defaults list WITHOUT a
       kind filter (so it follows the same all-completions
       attach behavior as the existing 5).

   See also `Gap-7-judge-routing-by-completion-kind.md` for the
   full retrospective of what we tried and why we reverted."
  [{:judge-name "heuristic-structural"
    :judge-config {:type :heuristic-structural}}
   {:judge-name "grounding"
    :judge-config {:type :grounding}}
   {:judge-name "reasoning"
    :judge-config {:type :reasoning}}
   {:judge-name "completeness"
    :judge-config {:type :completeness}}
   {:judge-name "instruction-following"
    :judge-config {:type :instruction-following}}])

(defn- applies-to?
  "Predicate: does this judge config apply to the given completion-kind?

   A judge with NO :applies-to-completion-kinds set applies to EVERY
   completion (backwards-compat — the default 5 judges are unfiltered and
   must keep firing on all completions, including nil-kind events).

   A judge that DOES specify :applies-to-completion-kinds is opting into
   kind-scoping: it applies ONLY when the event carries a matching kind. A
   nil-completion-kind event does NOT match — the judge asked for a
   specific kind and the event doesn't declare one.

   CJ-5b (live root cause): the coding-outcome judge is declared
   :applies-to-completion-kinds #{:terminal} so it fires EXACTLY ONCE, on
   the repl-researcher's terminal completion. A :blocked completion (a
   tool-permission pause, BEFORE any edit) carries :completion-kind nil —
   orc derives :terminal only for #{:success :failure :timeout}, not
   :blocked. The previous `(nil? completion-kind) true` branch let the
   kind-filtered judge fire on that premature :blocked completion and score
   a false band-1 'no file changes'. Checking the judge's filter FIRST
   fixes this without changing the unfiltered-judge default."
  [judge-config completion-kind]
  (let [applies (:applies-to-completion-kinds judge-config)]
    (cond
      (nil? applies) true
      (nil? completion-kind) false
      :else (contains? applies completion-kind))))

(defn get-effective-judges-for-node
  "Return the effective judge list for a node — `[{:judge-name :judge-config}...]`.

   Resolution order:
   1. Node has explicit :judges field present (consumer called
      :sheet/set-node-judges, even with []): look up each judge name's
      config from the sheet's :judges read-model. Returns the union.
   2. Else if node type is :repl-researcher AND the Living Description opt-in
      flag is on (the flag gates ONLY these defaults, never an attachment):
      return the default-judges set.
   3. Else: empty vector.

   The 4-arg arity filters by Gap-7's :applies-to-completion-kinds. A
   judge config carrying e.g. `:applies-to-completion-kinds #{:terminal}`
   is excluded from the effective list when the resolver is called
   with completion-kind `:tree-iteration` — or, per CJ-5b, with a nil
   completion-kind (a :blocked completion carries no kind). Backwards-compat:
   the 3-arg arity is UNFILTERED (returns every effective judge regardless
   of kind — the 'what judges are attached' query), and judges WITHOUT
   :applies-to-completion-kinds always apply.

   CJ-5b: the 3-arg 'unfiltered' intent and a real event's nil kind used to
   collapse to the same `nil` argument, so making applies-to? exclude nil
   would have broken the 3-arg query. We disambiguate with an explicit
   ::all-kinds sentinel: the 3-arg arity passes it (skip filtering), while
   the 4-arg arity from `on-node-execution-completed` passes the event's
   real (possibly nil) kind (filter — nil excludes kind-scoped judges).

   Public — callers can query 'what judges WILL run for this node'."
  ([ctx sheet-id node-id]
   (get-effective-judges-for-node ctx sheet-id node-id ::all-kinds))
  ([ctx sheet-id node-id completion-kind]
   (let [node (when (and sheet-id node-id) (orc/get-node ctx sheet-id node-id))
         all-effective
         (cond
           (nil? node)
           []

           (contains? node :judges)
           (let [sheet-judges (orc/get-judges ctx sheet-id)]
             (vec
               (keep (fn [judge-name]
                       (when-let [jc (get sheet-judges judge-name)]
                         {:judge-name judge-name
                          :judge-config jc}))
                     (:judges node))))

           (and (= :repl-researcher (:type node))
                (living-description-enabled? ctx))
           default-judges

           :else [])]
     (if (= ::all-kinds completion-kind)
       all-effective
       (filterv #(applies-to? (:judge-config %) completion-kind)
                all-effective)))))

;; =============================================================================
;; Processor — async judge execution + command-only emission
;; =============================================================================
;;
;; ORC async pattern (mirrors orc-service's `execute-leaf-node`): the
;; processor handler NEVER blocks on slow LLM-judge futures. It returns a
;; `:result/effect` that spawns ONE background future and returns
;; immediately, so LLM latency is never jammed into the pubsub/poller
;; thread. Inside that future we resolve + run the judges in PARALLEL
;; (per-judge future + per-judge timeout, exactly as before), then emit
;; each result via `cp/process-command` — the commands
;; (:evaluation/record-judge-score, :evaluation/record-composite-score)
;; are the ONLY writers of the score events, and they are idempotent so
;; an at-least-once effect-path replay can't double-emit.
;;
;; This replaces the previous design where the handler deref'd the judge
;; futures inline and returned `:result/events`. That blocked the handler
;; for the full max(judge time), and a throw in the coalesced poller
;; batch path lost the whole batch and re-looped forever (the verified
;; stall). The effect path routes through `process-event`'s per-event
;; try/catch + skip-checkpoint, which is safe under burst.

(def ^:private per-judge-timeout-ms
  "How long a single judge invocation may run before we give up on it.
   A slow/stuck judge can't block the others (each judge owns its own
   future) and now can't block the pubsub thread either (the whole
   resolve+run loop lives in the effect's background future)."
  60000)

(defn- run-judges-and-dispatch!
  "Run the resolved `effective` judges against `trace-data` in PARALLEL
   (one future per judge + per-judge timeout), then dispatch one
   `:evaluation/record-judge-score` command per judge that produced a
   result and, when ≥2 judges scored, one
   `:evaluation/record-composite-score` command.

   `source` carries :sheet-id / :node-id / :tick-id used to tag the
   emitted events. Runs entirely on the caller's thread — callers invoke
   it from inside the effect's background future so the pubsub/poller
   thread is never blocked.

   Preserves all prior judge behavior: tier-1 + :custom dispatch via
   invoke-judge, per-judge failure isolation
   (::judge-invocation-failed), per-judge timeout
   (::judge-invocation-timeout), and the ::all-judges-returned-nil
   audible-silence safeguard."
  [context source effective trace-data]
  (let [futures (mapv
                  (fn [{:keys [judge-name judge-config]}]
                    [judge-name judge-config
                     (future
                       (try (invoke-judge context judge-config trace-data)
                            (catch Throwable t
                              (u/log ::judge-invocation-failed
                                     :judge-name judge-name
                                     :error (.getMessage t)
                                     :exception-class (.getName (class t)))
                              nil)))])
                  effective)
        ;; Collect the raw judge results so the composite can be computed
        ;; from them without re-deriving.
        judge-results (mapv
                        (fn [[judge-name judge-config fut]]
                          (let [result (try (deref fut per-judge-timeout-ms ::timeout)
                                            (catch Throwable t
                                              (u/log ::judge-deref-failed
                                                     :judge-name judge-name
                                                     :error (.getMessage t))
                                              nil))]
                            {:judge-name judge-name
                             :judge-config judge-config
                             :result (cond
                                       (= ::timeout result)
                                       (do (u/log ::judge-invocation-timeout
                                                  :judge-name judge-name
                                                  :timeout-ms per-judge-timeout-ms)
                                           nil)
                                       :else result)}))
                        futures)
        scored (filter :result judge-results)]
    (if (seq scored)
      (do
        ;; One record-judge-score command per judge with a result. The
        ;; command emits :judge/score-emitted (idempotent on
        ;; [sheet node tick judge-name]).
        (doseq [{:keys [judge-name judge-config result]} scored]
          (cp/process-command
            (assoc context :command
                   (->record-judge-score-command source judge-name
                                                 judge-config result))))
        ;; After all per-judge scores: one record-composite-score command
        ;; (idempotent on [sheet node tick]) when ≥2 judges scored.
        (when-let [composite-cmd (->record-composite-score-command source judge-results)]
          (cp/process-command
            (assoc context :command composite-cmd))))
      ;; All effective judges returned nil. Surface this loudly — the
      ;; silent-skip path was masking a real wiring gap on recursive-RLM
      ;; terminal completions (terminal :writes lack :generated-tree-raw →
      ;; heuristic-structural nils; LLM judges also nil for reasons not
      ;; yet diagnosed). This log makes the silence audible.
      (u/log ::all-judges-returned-nil
             :sheet-id (:sheet-id source)
             :node-id (:node-id source)
             :tick-id (:tick-id source)
             :effective-judge-names (mapv :judge-name effective)
             :has-generated-tree-raw? (some? (get-in trace-data
                                                     [:outputs :generated-tree-raw]))
             :writes-keys (vec (keys (or (:outputs trace-data) {})))))))

(defn- distinct-by-first
  "Transducer: drop entries whose first element was already seen."
  []
  (fn [rf]
    (let [seen (volatile! #{})]
      (fn
        ([] (rf))
        ([acc] (rf acc))
        ([acc [k :as entry]]
         (if (contains? @seen k)
           acc
           (do (vswap! seen conj k) (rf acc entry))))))))

(defn- judge-revision-number
  "The revision of the definition in force: the judges read model's, else 1 (the
   built-in defaults are never declared, so they have a single definition)."
  [judge-config]
  (or (:revision-number judge-config) 1))

(defn- judge-purposes [judge-config]
  (if-let [purposes (not-empty (set (:purposes judge-config)))]
    purposes
    assessments/default-purposes))

(defn- judged-node-id
  "The node a completion is judged and measured as. A run of a published version
   has nodes of its own, each recording the draft node it was published from
   (read from the run's durable tick execution context): that draft node is the
   one whose judges monitor it. Any other completion is the node it names."
  [ctx {:keys [tick-id node-id]}]
  (or (some-> (orc/get-tick-execution-context ctx tick-id)
              (get-in [:nodes-by-id node-id :source-node-id]))
      node-id))

(defn- ->assessment-requested-event
  "Pure builder of the `:evaluation/assessment-requested` event for `judge` (an
   effective-judges entry) assessing the completion `completion`. `node-id` is
   the node judged (the source node of a published run's node);
   `version-number` the published sheet version the run executed, nil for a
   draft. `depends-on` (the assessment ids the judging must wait for) is
   recorded on the request and indexed by a [:depends-on id] tag, so an outcome
   can find the requests waiting on it."
  [completion assessment-id {:keys [judge-name judge-config]} node-version node-id version-number depends-on]
  (let [subject (:event/id completion)
        sheet-id (:sheet-id completion)
        run-node-id (:node-id completion)
        tick-id (:tick-id completion)
        ;; Which execution of the node this is (a map-each iteration): the
        ;; completion carries it as :exec-context, or in its :inputs.
        exec-context (second (orc/value-log-execution-key completion))]
    (es/->event
     {:type :evaluation/assessment-requested
      :tags (into #{[:sheet sheet-id] [:node node-id] [:tick tick-id]
                    [:assessment assessment-id] [:subject subject]}
                  (map (fn [dependency] [:depends-on dependency]))
                  depends-on)
      :body (cond-> {:assessment-id assessment-id
                     :sheet-id sheet-id
                     :node-id node-id
                     :tick-id tick-id
                     :subject-completion-id subject
                     :judge-name judge-name
                     :judge-revision-number (judge-revision-number judge-config)
                     :judge-type (:type judge-config)
                     :purposes (judge-purposes judge-config)
                     :requested-at (str (time/now))}
              (seq exec-context) (assoc :exec-context exec-context)
              (not= node-id run-node-id) (assoc :run-node-id run-node-id)
              node-version (assoc :node-version node-version)
              version-number (assoc :version-number version-number)
              (seq depends-on) (assoc :depends-on (vec depends-on)))})))

(defn- requested-assessment-ids
  "The ids of the assessments already requested for the completion `subject`."
  [{:keys [event-store tenant-id]} subject]
  (into #{}
        (map :assessment-id)
        (es/read event-store {:types #{assessments/request-event-type}
                              :tags #{[:subject subject]}
                              :tenant-id tenant-id})))

(defn- family-dependency-ids
  "The ids of the assessments a judge on `completion` must wait for: one for
   every judge effective on every execution inside the completion's family, as
   the evaluation runtime will request them. Assessment ids are deterministic
   (subject completion, judge, revision), so the dependencies can be named at
   request time even for a child whose completion has not yet been processed by
   the evaluation processor: children complete before their parent, but their
   requests are made asynchronously and may land after it."
  [context completion]
  (let [family (judge-run/execution-family
                context {:tick-id (:tick-id completion)
                         :node-id (:node-id completion)
                         :exec-context (second (orc/value-log-execution-key completion))})
        effective (memoize (fn [sheet-id node-id kind]
                             (get-effective-judges-for-node context sheet-id node-id kind)))]
    (into []
          (comp (mapcat (fn [entry]
                          (map (fn [judge]
                                 (assessments/assessment-id
                                  (:event-id entry) (:judge-name judge)
                                  (judge-revision-number (:judge-config judge))))
                               ;; judged as its source node when it is a run of a
                               ;; published version, like the completion handler
                               (effective (:sheet-id entry)
                                          (judged-node-id context entry)
                                          (:completion-kind entry)))))
                (distinct))
          family)))

(defn on-node-execution-completed
  "Handler for :sheet/node-execution-completed: REQUEST the assessments, judge
   nothing.

   Resolves the node's effective judges (`get-effective-judges-for-node`: an
   explicit attachment always assesses; the five defaults only for a
   :repl-researcher with the Living Description flag on) and returns one
   `:evaluation/assessment-requested` event per judge as `:result/events`,
   fenced by a `:result/cas` that rejects the append when any of those
   assessments has been requested already - so delivering the same completion
   again requests nothing new.

   The request is recorded on the PURE path (events + handler CAS): no effect and
   no checkpoint watermark can skip it. Judging itself is the job of
   `on-assessment-requested`, which only ever starts from a durable request.

   Grain appends a handler-CAS outcome without a checkpoint, so the same
   completion is delivered again; its CAS is only a no-op if every delivery
   computes the same requests. Judges can be revised or attached between
   deliveries, so the request set is decided ONCE: a completion that already
   has any request is not requested again, and the CAS admits the append only
   while the completion has none. Returns nil when there is nothing to request."
  [{:keys [event] :as context}]
  (when-let [subject (:event/id event)]
    (when-not (orc/assessment-origin context (:tick-id event))
    (when (empty? (requested-assessment-ids context subject))
    (let [node-id (judged-node-id context event)
          effective (get-effective-judges-for-node context (:sheet-id event) node-id
                                                   (:completion-kind event))
          candidates (into []
                           (comp (map (fn [judge]
                                        [(assessments/assessment-id
                                          subject (:judge-name judge)
                                          (judge-revision-number (:judge-config judge)))
                                         judge]))
                                 (distinct-by-first))
                           effective)
          fresh candidates
          ;; Only a judge that declares :child-assessments waits on the family
          ;; (PayForWhatYouUse): the family is read for no other judge.
          dependencies (delay (family-dependency-ids context event))]
      (when (seq fresh)
        {:result/events (let [version (node-version/node-version context event)
                              version-number (:version-number
                                              (orc/get-tick-execution-context context (:tick-id event)))]
                          (mapv (fn [[id judge]]
                                  (->assessment-requested-event
                                   event id judge version node-id version-number
                                   (when (judge-run/declares-key? context (:judge-config judge)
                                                                  :child-assessments)
                                     @dependencies)))
                                fresh))
         :result/cas {:types #{assessments/request-event-type}
                      :tags #{[:subject subject]}
                      :predicate-fn (fn [existing] (empty? (into [] existing)))}}))))))

;; =============================================================================
;; Judging a requested assessment
;; =============================================================================

(defn- assessment-events
  "Every lifecycle event (requested, terminal) matching `tags` for the tenant."
  [{:keys [event-store tenant-id]} tags]
  (into [] (es/read event-store {:types assessments/lifecycle-event-types
                                 :tags tags
                                 :tenant-id tenant-id})))

(defn- terminal-event? [event]
  (contains? assessments/terminal-event-types (:event/type event)))

(defn- assessment-settled?
  "True when the assessment already has its terminal outcome."
  [context assessment-id]
  (boolean (some terminal-event? (assessment-events context #{[:assessment assessment-id]}))))

(defn- subject-completion
  "The durable `:sheet/node-execution-completed` event a request assesses."
  [{:keys [event-store tenant-id]} {:keys [tick-id subject-completion-id]}]
  (first (filter #(= subject-completion-id (:event/id %))
                 (into [] (es/read event-store {:types #{:sheet/node-execution-completed}
                                                :tags #{[:tick tick-id]}
                                                :tenant-id tenant-id})))))

(defn- resolve-judge-config
  "The definition in force for the revision a request names: the judges read
   model's current entry when it is that revision, else the entry rebuilt from
   that revision's recorded config. A built-in default (never declared) has its
   one definition. nil when the judge or revision is unknown."
  [context {:keys [sheet-id judge-name judge-revision-number purposes]}]
  (if-let [declared (get (orc/get-judges context sheet-id) judge-name)]
    (if (= judge-revision-number (:revision-number declared))
      declared
      (when-let [revision (some #(when (= judge-revision-number (:revision-number %)) %)
                                (:revisions declared))]
        (let [config (:judge-config revision)]
          (merge (cond-> config
                   (:sheet-id config) (assoc :eval-sheet-id (:sheet-id config)))
                 {:sheet-id sheet-id
                  :judge-name judge-name
                  :revision-number judge-revision-number
                  :purposes purposes}))))
    (some #(when (= judge-name (:judge-name %)) (:judge-config %)) default-judges)))

(defn- failed-outcome [reason message]
  {:status :failed :reason reason :message message})

(defn- heuristic-outcome
  "The deterministic structural judge as an outcome: it grades the tree the node
   emitted, and a node that emitted none is ungradable - never scored."
  [trace-data]
  (if-let [result (invoke-heuristic-structural trace-data)]
    (merge {:status :scored} (select-keys result [:score :feedback :dimensions]))
    {:status :ungradable
     :reason :no-tree-emitted
     :message (str "The assessed node wrote no :generated-tree-raw, so there is no "
                   "tree structure to grade.")}))

(defn- child-assessment-evidence
  "What a parent's judge is shown of the assessments within its family: for each
   assessment it waited on, its judge, the node it assessed and its outcome -
   status, band, score and feedback when scored, reason and message when failed
   or ungradable. A field the outcome does not have is absent, never invented. A
   dependency that never ended (cannot happen once judging starts) shows as
   :pending."
  [context dependency-ids]
  (into []
        (keep (fn [id]
                (let [events (assessment-events context #{[:assessment id]})
                      requested (some #(when (= assessments/request-event-type (:event/type %)) %) events)
                      terminal (some #(when (terminal-event? %) %) events)]
                  (when requested
                    (merge (select-keys requested [:assessment-id :judge-name :node-id
                                                   :subject-completion-id])
                           {:node-name (:name (orc/get-node context (:sheet-id requested)
                                                            (:node-id requested)))
                            :status (get assessments/terminal-status (:event/type terminal) :pending)}
                           (select-keys terminal [:band :score :feedback :dimensions
                                                  :reason :message]))))))
        dependency-ids))

(defn- assess
  "Run the judge of `request` against its subject completion and return
   `{:outcome ... :judge-config ...}`. Every way this can fail is an outcome with
   its reason; nothing here throws."
  [context request]
  (let [judge-config (resolve-judge-config context request)
        completion (subject-completion context request)]
    (cond
      (nil? completion)
      {:outcome (failed-outcome :subject-completion-missing
                                (str "The completion " (:subject-completion-id request)
                                     " this assessment judges is not in the event store."))}

      (nil? judge-config)
      {:outcome (failed-outcome :judge-definition-missing
                                (str "Judge " (pr-str (:judge-name request)) " revision "
                                     (:judge-revision-number request)
                                     " is not declared on the sheet."))}

      :else
      {:judge-config judge-config
       :outcome
       (try
         (let [trace-data (build-trace-data context completion)]
           (if (= :heuristic-structural (:type judge-config))
             (heuristic-outcome trace-data)
             ;; the assessment id rides in the evidence: the judge's run is
             ;; durably marked with it (judge-run/run-judge)
             (judge-run/run-judge
              context judge-config
              (cond-> (assoc trace-data :assessment-id (:assessment-id request))
                (judge-run/declares-key? context judge-config :child-assessments)
                (assoc :child-assessments
                       (child-assessment-evidence context (:depends-on request)))))))
         (catch Throwable t
           (u/log ::assessment-judging-threw
                  :assessment-id (:assessment-id request)
                  :error (ex-message t)
                  :exception-class (.getName (class t)))
           (failed-outcome :judge-execution-failed
                           (or (ex-message t) (.getName (class t))))))})))

(defn- ->record-assessment-outcome-command
  [request {:keys [outcome judge-config]}]
  (merge {:command/id (random-uuid)
          :command/timestamp (time/now)
          :command/name :evaluation/record-assessment-outcome
          :assessment-id (:assessment-id request)}
         (into {} (remove (comp nil? val))
               (assoc (select-keys outcome [:status :band :score :feedback :dimensions
                                            :band-distribution :reason :message
                                            :model-provenance :judge-tick-id])
                      :judge-config judge-config))))

(defn- record-composite-when-settled!
  "Gap-8 HonestComposite, computed from ASSESSMENTS: once every assessment
   requested for the completion has its terminal outcome, ONE composite is
   recorded for its (sheet, node, tick) (idempotent, like before).

   The composite is of the completion's LEARNING judges only - the scalar the
   learning loops optimise - so a monitoring-only judge's score is never mixed
   into it. It is the mean over the judges that scored (two or more), and it is
   never presented without its coverage: how many learning judges were expected,
   how many scored, failed and were ungradable, and `:partial` when any did not
   score."
  [context request]
  (let [events (assessment-events context #{[:subject (:subject-completion-id request)]})
        requests (filterv #(= assessments/request-event-type (:event/type %)) events)
        terminal-by-id (into {} (comp (filter terminal-event?) (map (juxt :assessment-id identity))) events)]
    (when (every? (comp terminal-by-id :assessment-id) requests)
      (let [learning (filterv #(contains? (set (:purposes %)) :learning) requests)
            outcome-of (fn [r] (get assessments/terminal-status
                                    (:event/type (terminal-by-id (:assessment-id r)))))
            by-status (frequencies (map outcome-of learning))
            coverage {:expected (count learning)
                      :scored (get by-status :scored 0)
                      :failed (get by-status :failed 0)
                      :ungradable (get by-status :ungradable 0)}
            results (into []
                          (keep (fn [r]
                                  (when (= :scored (outcome-of r))
                                    {:judge-name (:judge-name r)
                                     :judge-config (resolve-judge-config context r)
                                     :result {:score (:score (terminal-by-id (:assessment-id r)))}})))
                          learning)]
        (when-let [command (->record-composite-score-command
                            {:sheet-id (:sheet-id request)
                             :node-id (:node-id request)
                             :tick-id (:tick-id request)
                             :subject-completion-id (:subject-completion-id request)}
                            results coverage)]
          (cp/process-command (assoc context :command command)))))))

(defn- judge-assessment!
  "Judge one requested assessment and record its outcome. Runs on the effect's
   background future, so no handler thread ever waits on a judge."
  [context request]
  (let [result (assess context request)
        recorded (cp/process-command
                  (assoc context :command (->record-assessment-outcome-command request result)))]
    (when (:cognitect.anomalies/category recorded)
      (u/log ::assessment-outcome-not-recorded
             :assessment-id (:assessment-id request)
             :anomaly recorded))
    (record-composite-when-settled! context request)))

(def ^:private judging-in-flight
  "The assessments this process is judging right now. Judging starts from two
   places - a request, and the outcome of the last assessment a request waited
   on - and an assessment is judged once however the two interleave."
  (atom #{}))

(defn- claim-judging!
  "Atomically claim `assessment-id` for judging: true for exactly one caller."
  [assessment-id]
  (let [[before _] (swap-vals! judging-in-flight conj assessment-id)]
    (not (contains? before assessment-id))))

(defn- start-judging!
  "Start one background future that judges `request` and records its outcome,
   unless it is settled or already being judged. Never blocks."
  [context request]
  (let [id (:assessment-id request)]
    (when (claim-judging! id)
      (future
        (try
          (when-not (assessment-settled? context id)
            (judge-assessment! context request))
          (catch Throwable t
            (u/log ::assessment-judging-future-failed
                   :assessment-id id
                   :error (ex-message t)
                   :exception-class (.getName (class t))))
          (finally
            (swap! judging-in-flight disj id)))))))

(defn- dependencies-settled?
  "True when every assessment `request` waits on has its terminal outcome. A
   failed or ungradable one is as settled as a scored one; a request that waits
   on nothing is settled."
  [context request]
  (every? #(assessment-settled? context %) (:depends-on request)))

(defn on-assessment-requested
  "Handler for :evaluation/assessment-requested: judge it - once the assessments
   it waits on have all ended.

   A request that already has its terminal outcome (a replay) does nothing. A
   request that waits on assessments (a parent's judge that declares
   :child-assessments) that have not all ended does nothing yet: it stays
   durably PENDING, and `on-assessment-settled` starts it when the last one ends.
   Otherwise the handler returns a non-blocking `:result/effect` that starts one
   background future for the judgment; the future runs the judge and records the
   outcome through `:evaluation/record-assessment-outcome`. The request is
   durable before this ever runs, so an effect the processor skips leaves the
   assessment visibly PENDING - never silently gone."
  [{:keys [event] :as context}]
  (when (and (not (assessment-settled? context (:assessment-id event)))
             (dependencies-settled? context event))
    {:result/checkpoint :after
     :result/effect (fn [] (start-judging! context event))}))

(defn on-assessment-settled
  "Handler for an assessment's terminal outcome: start every request that waits
   on it and whose other dependencies have all ended too. Driven by the outcome
   event itself, so nothing polls. The requests are found by their
   [:depends-on id] tag."
  [{:keys [event event-store tenant-id] :as context}]
  (let [waiting (into []
                      (filter (fn [request]
                                (and (not (assessment-settled? context (:assessment-id request)))
                                     (dependencies-settled? context request))))
                      (es/read event-store {:types #{assessments/request-event-type}
                                            :tags #{[:depends-on (:assessment-id event)]}
                                            :tenant-id tenant-id}))]
    (when (seq waiting)
      {:result/checkpoint :after
       :result/effect (fn [] (doseq [request waiting] (start-judging! context request)))})))

;; =============================================================================
;; Gap-7b — tree-shape grading on the campaign's :rlm/tree-generated event
;; =============================================================================
;;
;; :rlm/tree-generated fires ONCE per campaign, at the campaign's terminal
;; boundary, carrying the LAST tree the model emitted — it is not a
;; per-emit or per-iteration event. Every intermediate tree's shape is
;; durable on its own :rlm/researcher-iteration-recorded record (the
;; emitted-tree fingerprint), which is where per-iteration structure lives.
;;
;; This processor subscribes to :rlm/tree-generated to grade that last
;; tree's shape once per campaign. It uses the SAME resolver +
;; judge dispatch as the terminal processor, but filters to judges
;; that grade tree SHAPE (not output content) via tree-shape-judge-
;; types. LLM output judges (grounding/reasoning/etc.) don't run here
;; because the intermediate event carries no synthesized output —
;; only the tree DSL.
;;
;; Per Gap-7 retrospective: we deliberately do NOT use the
;; :applies-to-completion-kinds infrastructure to gate which judges
;; run here. That field was reverted to defaults-have-no-filter post-
;; Gap-7. Instead, this processor's responsibility is "run the tree-
;; shape judges on tree-generation events" — that's a processor-owned
;; concern, not a judge-config concern.

(def ^:private tree-shape-judge-types
  "Set of judge types that grade tree SHAPE from a raw-dsl. The
   :rlm/tree-generated processor only runs judges whose :type belongs
   to this set. Other judges (grounding, reasoning, completeness,
   instruction-following) grade OUTPUT content and run on terminal
   completions where the synthesized output is available."
  #{:heuristic-structural})

(defn- on-rlm-tree-generated
  "Handler for :rlm/tree-generated. Gated on the Living Description
   opt-in flag.

   The event body carries :execution-id (= tick-id) + :raw-dsl. As of
   Gap-7b's producer-side change, it also carries :sheet-id + :node-id
   directly — preferred. Legacy events (or non-bench producers) without
   those fields fall back to looking up the host repl-researcher node
   via the matching :sheet/node-execution-started event for the tick.

   Filters the resolver's effective judge list to tree-shape graders
   (currently heuristic-structural) and returns a non-blocking
   `:result/effect` that runs them in parallel (per-judge timeout) and
   dispatches the score-recording command in a background future — same
   async discipline as on-node-execution-completed. NO deref in the
   handler, NO :result/events."
  [{:keys [event] :as context}]
  ;; AssessmentWorkIsMarked: a tree generated inside an assessment's run is
  ;; never auto-assessed, like any other completion there.
  (when (and (living-description-enabled? context)
             (not (orc/assessment-origin context (:execution-id event))))
    (let [tick-id (:execution-id event)
          raw-dsl (:raw-dsl event)
          direct-sheet-id (:sheet-id event)
          direct-node-id (:node-id event)
          source (cond
                   ;; Preferred: producer included sheet/node in body.
                   (and direct-sheet-id direct-node-id)
                   {:sheet-id direct-sheet-id
                    :node-id direct-node-id
                    :inputs (or (find-started-inputs context direct-sheet-id
                                                     tick-id direct-node-id) {})}
                   ;; Fallback: scan started events for the host
                   ;; repl-researcher.
                   :else (find-tick-repl-researcher-source context tick-id))]
      (when (and tick-id raw-dsl source)
        (let [{:keys [sheet-id node-id inputs]} source
              node (orc/get-node context sheet-id node-id)
              effective (get-effective-judges-for-node context sheet-id node-id)
              tree-shape-effective (filterv #(contains? tree-shape-judge-types
                                                       (:type (:judge-config %)))
                                            effective)]
          (when (seq tree-shape-effective)
            ;; Snapshot trace-data on the handler thread; run the judges +
            ;; command dispatch in the effect's background future so the
            ;; pubsub thread returns immediately. The score events are
            ;; tagged with the discovered (sheet, node, tick) so the
            ;; consolidator's join keys match.
            (let [trace-data {:inputs inputs
                              :outputs {:generated-tree-raw raw-dsl}
                              :instruction (or (:instruction node) "")}
                  cmd-source {:sheet-id sheet-id
                              :node-id node-id
                              :tick-id tick-id}]
              {:result/checkpoint :after
               :result/effect
               (fn []
                 (future
                   (try
                     (run-judges-and-dispatch! context cmd-source
                                               tree-shape-effective trace-data)
                     (catch Throwable t
                       (u/log ::judge-runtime-future-failed
                              :sheet-id sheet-id
                              :node-id node-id
                              :tick-id tick-id
                              :error (.getMessage t)
                              :exception-class (.getName (class t)))))))})))))))

;; =============================================================================
;; Read-model — judge scores history per (sheet, tick, node)
;; =============================================================================
;;
;; Accumulates :judge/score-emitted events (the learning loops' record: a
;; scored assessment of a LEARNING judge that carries feedback) into a map keyed
;; by [sheet-id tick-id node-id]; value is the vector of entries in emission
;; order. One tick can hold several entries per judge: distinct executions of a
;; node (map-each iterations) are distinct assessments. Consolidator (under
;; Gap-3) will read these by sheet+tick to enrich its LLM reflection input.
;; For every assessment, scored or not, see `:evaluation/assessments`.

(defmulti judge-scores*
  (fn [_state event] (:event/type event)))

(defmethod judge-scores* :default [state _] state)

(defmethod judge-scores* :judge/score-emitted
  [state event]
  (let [key [(:sheet-id event) (:tick-id event) (:node-id event)]
        entry (select-keys event [:sheet-id :tick-id :node-id
                                  :judge-name :judge-config
                                  :score :feedback :dimensions :emitted-at
                                  ;; J08: the judge's model call, which the
                                  ;; record carries and the history dropped.
                                  :model-provenance])]
    (update state key (fnil conj []) entry)))

(defn judge-scores
  "Build the judge-scores state from a seq of events."
  [initial-state events]
  (reduce judge-scores* initial-state events))

(defreadmodel :evaluation judge-scores
  {:events #{:judge/score-emitted} :version 1}
  [state event] (judge-scores* state event))

(defn get-judge-scores
  "Return the vector of judge score entries (the learning loops' record) for the
   given (sheet-id, node-id, tick-id) tuple, in emission order. Empty vector if
   no learning judge scored for that tick. Entries carry the score, feedback,
   dimensions and :model-provenance (absent for a judge that makes no model
   call). The band, revision and assessment of a score are on its
   `:judge/score-emitted` event and in `:evaluation/assessments`."
  [ctx sheet-id node-id tick-id]
  (or (get (rmp/project ctx :evaluation/judge-scores)
           [sheet-id tick-id node-id])
      []))

;; =============================================================================
;; Processor registration
;; =============================================================================

(defprocessor :evaluation on-node-execution-completed
  {:topics #{:sheet/node-execution-completed}}
  "Per-event evaluator runtime. Resolves the judges effective on the completing
   node (an explicit attachment always; the defaults only with the Living
   Description flag on) and REQUESTS one assessment per judge. Judging is the
   job of `on-assessment-requested`."
  [context]
  (on-node-execution-completed context))

(defprocessor :evaluation on-rlm-tree-generated
  {:topics #{:rlm/tree-generated}}
  "Gap-7b: tree-shape grader on :rlm/tree-generated. The event fires
   once per campaign, at the terminal boundary, carrying the last tree
   the model emitted; this processor grades that tree's shape once, so
   the consolidator receives one structural signal per campaign
   alongside the terminal sum-up. Intermediate trees' shapes are
   durable on their iteration records, not on this event. Only runs
   tree-shape judges (currently heuristic-structural) — LLM output
   judges run on the terminal :sheet/node-execution-completed event
   where final outputs are available."
  [context]
  (on-rlm-tree-generated context))

(defprocessor :evaluation on-assessment-settled
  {:topics #{:evaluation/assessment-scored
             :evaluation/assessment-failed
             :evaluation/assessment-ungradable}}
  "S9b: an assessment ended - start the parents' assessments that were waiting on
   it, once all of their dependencies have ended."
  [context]
  (on-assessment-settled context))

(defprocessor :evaluation on-assessment-requested
  {:topics #{:evaluation/assessment-requested}}
  "S7: judge a requested assessment and record its outcome (scored, failed or
   ungradable). Starts from the durable request, never from a completion."
  [context]
  (on-assessment-requested context))
