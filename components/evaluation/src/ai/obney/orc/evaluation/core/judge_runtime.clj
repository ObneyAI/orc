(ns ai.obney.orc.evaluation.core.judge-runtime
  "Gap-1 — per-event evaluator runtime.

   Subscribes to :sheet/node-execution-completed events. For each event,
   looks up the host node's attached :judges and executes them, emitting
   one :judge/score-emitted event per successful judge invocation.

   Gated by the system-level Living Description opt-in flag
   (`:ontology/set-living-description-enabled`). When the flag is off
   (default), the processor returns immediately without any work — no
   reads, no dispatches, no events. This preserves the zero-cost
   guarantee for consumers who haven't opted in.

   Per the unification PRD, every per-event evaluator (LLM-judges,
   heuristic-structural, future custom judges) emits the same
   `:judge/score-emitted` shape. The consolidator (under Gap-3) will
   consume these events alongside raw execution evidence to update
   Living Description bodies."
  (:require [ai.obney.grain.todo-processor-v2.interface :refer [defprocessor]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.evaluation.core.judges :as judges]
            [ai.obney.orc.evaluation.core.heuristic-structural :as heuristic-structural]
            [com.brunobonacci.mulog :as u]))

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
   found."
  [ctx sheet-id tick-id node-id]
  (when (and (:event-store ctx) sheet-id tick-id node-id)
    (let [started-events (into [] (es/read (:event-store ctx)
                                            {:types #{:sheet/node-execution-started}
                                             :tenant-id (:tenant-id ctx)}))
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

(defn- build-trace-data
  "Build the `trace-data` map the evaluation judges expect:
   `{:inputs <host-input-values> :outputs <host-output-values>
     :instruction <host-instruction>}`.

   `event` is the `:sheet/node-execution-completed` event body. When
   the event lacks :inputs (the recursive RLM terminal-completion
   case), reach back to the matching :sheet/node-execution-started
   event so the LLM judges' rubric prompts render with the original
   task inputs."
  [ctx event]
  (let [sheet-id (:sheet-id event)
        tick-id (:tick-id event)
        node-id (:node-id event)
        node (when (and sheet-id node-id) (orc/get-node ctx sheet-id node-id))
        direct-inputs (:inputs event)
        reached-inputs (when (empty? direct-inputs)
                         (find-started-inputs ctx sheet-id tick-id node-id))]
    {:inputs (or (not-empty direct-inputs) reached-inputs {})
     :outputs (or (:writes event) {})
     :instruction (or (:instruction node) "")}))

;; =============================================================================
;; Judge dispatch
;; =============================================================================

(defn- invoke-llm-judge
  "Dispatch on judge-type to the matching public judge function. Returns
   the canonical {:score :feedback :dimensions} shape if the judge
   produced a score, otherwise nil. Each judge function resolves its
   var on every call so with-redefs / mock bindings take effect."
  [judge-type trace-data]
  (let [executor-ctx {:inputs {:trace-data trace-data}}
        [judge-output result-key]
        (case judge-type
          :grounding             [(judges/grounding-judge executor-ctx)             :grounding-result]
          :reasoning             [(judges/reasoning-judge executor-ctx)             :reasoning-result]
          :completeness          [(judges/completeness-judge executor-ctx)          :completeness-result]
          :instruction-following [(judges/instruction-following-judge executor-ctx) :instruction-result]
          [nil nil])
        inner (when (and judge-output result-key)
                (get judge-output result-key))]
    (when (and inner (:score inner))
      {:score (double (:score inner))
       :feedback (or (:feedback inner) "")
       :dimensions []})))

(def ^:private llm-judge-types
  "Set of judge types that route to evaluation/core/judges functions."
  #{:grounding :reasoning :completeness :instruction-following})

(defn- invoke-heuristic-structural
  "Gap-2: heuristic structural evaluator. Scores the shape of the tree
   the host node emitted (via Phase 1 emit-tree! → :generated-tree-raw
   in :writes). Returns nil when the node didn't emit a tree, signaling
   the judge to gracefully no-op rather than score nothing."
  [trace-data]
  (when-let [tree (get-in trace-data [:outputs :generated-tree-raw])]
    (heuristic-structural/evaluate-tree-structure tree)))

(def ^:private default-custom-judge-timeout-ms
  "How long a custom judge's sub-execution is allowed to run. Spec
   default is 60s; consumers may override per-judge via
   :judge-config.:timeout-ms."
  60000)

(defn- invoke-custom-judge
  "Gap-4: sub-execute a consumer-defined eval sheet against the host
   node's trace-data and harvest a score.

   The consumer's judge sheet must be a previously-built ORC workflow
   whose blackboard reads `:host-inputs`, `:host-outputs`,
   `:host-instruction`, `:host-trace` and writes `:score` (numeric 0-1)
   + `:feedback` (string) + optional `:dimensions` (vector).

   Returns the canonical {:score :feedback :dimensions} shape, or nil
   on any failure: missing sheet-id, sheet not found, sheet execution
   failure, missing :score in outputs. All failures are mulog'd loudly
   — silent skipping is the failure mode the unification arc fought
   the whole way through.

   Recursion safeguard (Gap-4 RED#5): when the host event is itself
   produced by a custom-judge sub-execution, the runtime increments
   :judge-depth in context. If depth exceeds max-depth (default 1),
   this fn returns nil before sub-executing. Without this, a
   maliciously-configured custom judge can spawn unbounded sub-ticks."
  [ctx judge-config trace-data]
  (let [;; Gap-4: read :eval-sheet-id (the consumer's eval workflow).
        ;; The judges read-model lifts judge-config's :sheet-id to
        ;; :eval-sheet-id so it isn't overwritten by the host sheet's
        ;; id during merge. Fall back to :sheet-id for legacy callers
        ;; that bypass the read-model and pass the raw judge-config
        ;; with :sheet-id directly.
        eval-sheet-id (or (:eval-sheet-id judge-config)
                          (:sheet-id judge-config))
        timeout-ms (or (:timeout-ms judge-config) default-custom-judge-timeout-ms)
        depth (or (::judge-depth ctx) 0)
        max-depth (or (::max-judge-depth ctx) 1)]
    (cond
      (nil? eval-sheet-id)
      (do (u/log ::custom-judge-missing-sheet-id
                 :judge-config (select-keys judge-config [:type :timeout-ms]))
          nil)

      (>= depth max-depth)
      (do (u/log ::custom-judge-recursion-skipped
                 :depth depth
                 :max-depth max-depth
                 :sheet-id eval-sheet-id)
          nil)

      :else
      (let [sub-ctx (assoc ctx ::judge-depth (inc depth))
            result (try
                     (orc/execute sub-ctx eval-sheet-id
                                  {:host-inputs (or (:inputs trace-data) {})
                                   :host-outputs (or (:outputs trace-data) {})
                                   :host-instruction (or (:instruction trace-data) "")
                                   :host-trace []}
                                  :timeout-ms timeout-ms)
                     (catch Throwable t
                       (u/log ::custom-judge-execution-threw
                              :sheet-id eval-sheet-id
                              :error (.getMessage t)
                              :exception-class (.getName (class t)))
                       nil))
            outputs (:outputs result)
            score (:score outputs)]
        (cond
          (not= :success (:status result))
          (do (u/log ::custom-judge-execution-not-success
                     :sheet-id eval-sheet-id
                     :status (:status result)
                     :error (:error result))
              nil)

          (not (number? score))
          (do (u/log ::custom-judge-output-missing-score
                     :sheet-id eval-sheet-id
                     :outputs-keys (vec (keys (or outputs {}))))
              nil)

          :else
          (let [;; Normalize dimensions to the DimensionScore schema:
                ;; consumers may produce {:name :score :feedback}
                ;; without :weight (the LLM grading rubric won't always
                ;; emit weights). Default :weight to 1.0 so the event
                ;; passes Malli validation.
                raw-dims (or (:dimensions outputs) [])
                norm-dims (mapv (fn [d]
                                  (cond-> (or d {})
                                    (nil? (:weight d)) (assoc :weight 1.0)
                                    (nil? (:score d)) (assoc :score 0.0)
                                    (nil? (:name d)) (assoc :name "")
                                    (nil? (:feedback d)) (assoc :feedback "")
                                    true (update :weight double)
                                    true (update :score double)))
                                raw-dims)]
            {:score (double score)
             :feedback (or (:feedback outputs) "")
             :dimensions norm-dims}))))))

(defn- invoke-judge
  "Invoke a single attached judge against the host node's trace-data.
   Returns the {:score :feedback :dimensions} canonical shape, or nil
   when the judge produced no valid result. The ctx arg is required
   for `:custom` judges, which sub-execute via `orc/execute`."
  [ctx judge-config trace-data]
  (let [judge-type (:type judge-config)]
    (cond
      (contains? llm-judge-types judge-type)
      (invoke-llm-judge judge-type trace-data)

      (= :heuristic-structural judge-type)
      (invoke-heuristic-structural trace-data)

      (= :custom judge-type)
      (invoke-custom-judge ctx judge-config trace-data)

      :else nil)))

;; =============================================================================
;; Event construction
;; =============================================================================

(defn- ->score-emitted-event
  [event judge-name judge-config result]
  (es/->event
    {:type :judge/score-emitted
     :tags #{[:sheet (:sheet-id event)]
             [:node (:node-id event)]
             [:tick (:tick-id event)]}
     :body {:sheet-id (:sheet-id event)
            :tick-id (:tick-id event)
            :node-id (:node-id event)
            :judge-name judge-name
            :judge-config judge-config
            :score (:score result)
            :feedback (:feedback result)
            :dimensions (:dimensions result)
            :emitted-at (str (java.time.Instant/now))}}))

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

(defn- ->composite-score-event
  "Gap-8: build a `:judge/composite-score-computed` event from the
   judge results collected during a single processor cycle. Returns
   nil when fewer than 2 judges have valid scores — single-judge ticks
   don't need a composite distinct from the score itself, and zero-
   judge ticks have nothing to composite.

   `judge-results` is a seq of `{:judge-name :judge-config :result}`
   where :result is the canonical `{:score :feedback :dimensions}`
   shape (or nil for failed judges).

   The `:contributing-judges` field on the emitted event shows the
   EFFECTIVE normalized weight each judge contributed (e.g., `1/N`
   when consumers didn't set explicit weights), not the raw
   :judge-config value. This is what consumers want for debugging
   the composite: 'how did this number get computed?'"
  [event judge-results]
  (let [raw-entries (keep (fn [{:keys [judge-name judge-config result]}]
                            (when (and result (number? (:score result)))
                              {:judge-name judge-name
                               :score (:score result)
                               :weight (:weight judge-config)}))
                          judge-results)]
    (when (>= (count raw-entries) 2)
      (when-let [normalized (normalize-judge-weights raw-entries)]
        (when-let [composite (compute-composite-score raw-entries)]
          (es/->event
            {:type :judge/composite-score-computed
             :tags #{[:sheet (:sheet-id event)]
                     [:node (:node-id event)]
                     [:tick (:tick-id event)]}
             :body {:sheet-id (:sheet-id event)
                    :tick-id (:tick-id event)
                    :node-id (:node-id event)
                    :composite-score composite
                    :contributing-judges (mapv (fn [{:keys [judge-name score weight]}]
                                                  {:judge-name judge-name
                                                   :score (double score)
                                                   :weight (double weight)})
                                                normalized)
                    :emitted-at (str (java.time.Instant/now))}}))))))

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

   The intermediate-tick grading use case (where we'd want
   heuristic-structural to fire on each :rlm/tree-generated emission
   rather than waiting for the terminal sum-up) is filed as
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
   When the judge has no :applies-to-completion-kinds set OR when the
   caller didn't supply a kind, the judge applies (backwards-compat
   default). Otherwise the kind must be present in the set."
  [judge-config completion-kind]
  (let [applies (:applies-to-completion-kinds judge-config)]
    (cond
      (nil? completion-kind) true
      (nil? applies) true
      :else (contains? applies completion-kind))))

(defn get-effective-judges-for-node
  "Return the effective judge list for a node — `[{:judge-name :judge-config}...]`.

   Resolution order:
   1. Node has explicit :judges field present (consumer called
      :sheet/set-node-judges, even with []): look up each judge name's
      config from the sheet's :judges read-model. Returns the union.
   2. Else if node type is :repl-researcher AND opt-in flag is on:
      return the default-judges set.
   3. Else: empty vector.

   The 4-arg arity filters by Gap-7's :applies-to-completion-kinds. A
   judge config carrying e.g. `:applies-to-completion-kinds #{:terminal}`
   is excluded from the effective list when the resolver is called
   with completion-kind `:tree-iteration`. Backwards-compat: 3-arg arity
   and judges without :applies-to-completion-kinds always apply.

   Public — callers can query 'what judges WILL run for this node'."
  ([ctx sheet-id node-id]
   (get-effective-judges-for-node ctx sheet-id node-id nil))
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
                (ontology/get-living-description-enabled? ctx))
           default-judges

           :else [])]
     (filterv #(applies-to? (:judge-config %) completion-kind)
              all-effective))))

;; =============================================================================
;; Processor — handler
;; =============================================================================

(defn on-node-execution-completed
  "Handler for :sheet/node-execution-completed. Gated on the Living
   Description opt-in flag. When on, resolves the effective judge list
   for the node (explicit attachment OR Gap-5 defaults for
   :repl-researcher), dispatches each, and returns `:result/events`
   containing one :judge/score-emitted per successful invocation.

   Resolution lives in `get-effective-judges-for-node` — same flag
   gate applies inside the resolver, so the outer check here is
   redundant but kept for clarity / short-circuit before any node
   lookup."
  [{:keys [event] :as context}]
  (when (ontology/get-living-description-enabled? context)
    (let [sheet-id (:sheet-id event)
          node-id (:node-id event)
          ;; Gap-7: pass the event's :completion-kind into the resolver
          ;; so it can filter judges whose :applies-to-completion-kinds
          ;; doesn't match. Backwards-compat: events without the field
          ;; pass nil → resolver returns all candidates.
          completion-kind (:completion-kind event)
          effective (get-effective-judges-for-node context sheet-id node-id completion-kind)]
      (when (seq effective)
        (let [trace-data (build-trace-data context event)
              ;; 2026-06-05 LLM-judge-nilling root-cause finding:
              ;; The original investigation thought judges were silently
              ;; returning nil on terminal events. Heavy instrumentation
              ;; revealed the real issue: judges DO produce results, but
              ;; the processor invoked them SEQUENTIALLY — each LLM
              ;; judge takes 2-3s against OpenRouter, so 4 LLM judges in
              ;; sequence = 8-12s per event. Verify scripts that polled
              ;; the event store with a short Thread/sleep would see 0
              ;; events because the processor was still mid-flight.
              ;;
              ;; Fix: run independent judges in parallel via `future`.
              ;; Each judge owns its own thread; total wall time =
              ;; max(judge times) instead of sum. We deref with a
              ;; per-judge timeout so a single slow/stuck judge can't
              ;; block the others.
              ;;
              ;; The :all-judges-returned-nil log below remains as an
              ;; audible-silence safeguard for the case where every
              ;; judge legitimately returns nil (e.g., no inputs/no tree
              ;; and they can't grade anything).
              per-judge-timeout-ms 60000
              futures (mapv
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
              ;; Collect both score-events AND the raw judge results so
              ;; Gap-8 can compute a weighted composite from the results
              ;; without re-deriving them.
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
              score-events (vec
                             (keep (fn [{:keys [judge-name judge-config result]}]
                                     (when result
                                       (->score-emitted-event event judge-name
                                                              judge-config result)))
                                   judge-results))
              composite-event (->composite-score-event event judge-results)
              events (cond-> score-events
                       composite-event (conj composite-event))]
          (if (seq events)
            {:result/events events}
            ;; All effective judges returned nil. Surface this loudly —
            ;; the silent-skip path was masking a real wiring gap on
            ;; recursive-RLM terminal completions (terminal :writes lack
            ;; :generated-tree-raw → heuristic-structural nils; LLM
            ;; judges also nil for reasons not yet diagnosed). Tracked
            ;; for design follow-up; until that lands, this log makes
            ;; the silence audible.
            (do (u/log ::all-judges-returned-nil
                       :sheet-id sheet-id
                       :node-id node-id
                       :tick-id (:tick-id event)
                       :status (:status event)
                       :effective-judge-names (mapv :judge-name effective)
                       :has-generated-tree-raw? (some? (get-in trace-data
                                                              [:outputs :generated-tree-raw]))
                       :writes-keys (vec (keys (or (:outputs trace-data) {}))))
                nil)))))))

;; =============================================================================
;; Gap-7b — per-Phase-1-iteration tree-shape grading
;; =============================================================================
;;
;; Recursive RLM emits :rlm/tree-generated per Phase 1 emit-tree! call.
;; The terminal :sheet/node-execution-completed only fires ONCE per run
;; (when the executor's loop terminates via (final!)), so subscribing
;; tree-shape judges only to that event means we grade just the last
;; tree the model produced — losing N-1 intermediate tree designs.
;;
;; This processor subscribes to :rlm/tree-generated to grade each
;; intermediate tree as it's emitted. It uses the SAME resolver +
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
   (currently heuristic-structural) and invokes them in parallel with
   per-judge timeout — same discipline as on-node-execution-completed."
  [{:keys [event] :as context}]
  (when (ontology/get-living-description-enabled? context)
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
            (let [trace-data {:inputs inputs
                              :outputs {:generated-tree-raw raw-dsl}
                              :instruction (or (:instruction node) "")}
                  per-judge-timeout-ms 60000
                  futures (mapv
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
                            tree-shape-effective)
                  ;; Score events are tagged with the discovered (sheet,
                  ;; node, tick) so consolidator's join keys match. The
                  ;; ->score-emitted-event helper reads sheet-id/node-id/
                  ;; tick-id from the source event; we synthesize the
                  ;; required shape for it.
                  synthetic-source {:sheet-id sheet-id
                                    :node-id node-id
                                    :tick-id tick-id}
                  events (vec
                           (keep
                             (fn [[judge-name judge-config fut]]
                               (let [result (try (deref fut per-judge-timeout-ms ::timeout)
                                                 (catch Throwable t
                                                   (u/log ::judge-deref-failed
                                                          :judge-name judge-name
                                                          :error (.getMessage t))
                                                   nil))]
                                 (cond
                                   (= ::timeout result)
                                   (do (u/log ::judge-invocation-timeout
                                              :judge-name judge-name
                                              :timeout-ms per-judge-timeout-ms)
                                       nil)

                                   result
                                   (->score-emitted-event synthetic-source
                                                          judge-name judge-config result)

                                   :else nil)))
                             futures))]
              (when (seq events)
                {:result/events events}))))))))

;; =============================================================================
;; Read-model — judge scores history per (sheet, tick, node)
;; =============================================================================
;;
;; Accumulates :judge/score-emitted events into a map keyed by
;; [sheet-id tick-id node-id]; value is the vector of event bodies in
;; emission order. Consolidator (under Gap-3) will read these by
;; sheet+tick to enrich its LLM reflection input.

(defmulti judge-scores*
  (fn [_state event] (:event/type event)))

(defmethod judge-scores* :default [state _] state)

(defmethod judge-scores* :judge/score-emitted
  [state event]
  (let [key [(:sheet-id event) (:tick-id event) (:node-id event)]
        entry (select-keys event [:sheet-id :tick-id :node-id
                                  :judge-name :judge-config
                                  :score :feedback :dimensions :emitted-at])]
    (update state key (fnil conj []) entry)))

(defn judge-scores
  "Build the judge-scores state from a seq of events."
  [initial-state events]
  (reduce judge-scores* initial-state events))

(defreadmodel :evaluation judge-scores
  {:events #{:judge/score-emitted} :version 1}
  [state event] (judge-scores* state event))

(defn get-judge-scores
  "Return the vector of judge result entries for the given
   (sheet-id, node-id, tick-id) tuple, in emission order. Empty vector
   if no judges fired for that tick."
  [ctx sheet-id node-id tick-id]
  (or (get (rmp/project ctx :evaluation/judge-scores)
           [sheet-id tick-id node-id])
      []))

;; =============================================================================
;; Processor registration
;; =============================================================================

(defprocessor :evaluation on-node-execution-completed
  {:topics #{:sheet/node-execution-completed}}
  "Gap-1: per-event evaluator runtime. Looks up attached judges on the
   completing node and executes them. Gated on the Living Description
   opt-in flag — no work happens unless a consumer has explicitly
   enabled the loop."
  [context]
  (on-node-execution-completed context))

(defprocessor :evaluation on-rlm-tree-generated
  {:topics #{:rlm/tree-generated}}
  "Gap-7b: per-Phase-1-iteration tree-shape grader. Recursive RLM
   emits :rlm/tree-generated for each intermediate emit-tree! during
   Phase 1; this processor grades the tree shape for each so the
   consolidator sees multiple structural signals per run instead of
   just the terminal sum-up. Only runs tree-shape judges (currently
   heuristic-structural) — LLM output judges run on the terminal
   :sheet/node-execution-completed event where final outputs are
   available."
  [context]
  (on-rlm-tree-generated context))
