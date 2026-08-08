(ns ai.obney.orc.ontology.core.harvest
  "EL-4 (ADR 0015): HARVEST — the emergence loop's terminus. Crystallizes a
   recurring + well-scored + coherent :tree-class into a named durable
   behavioral-subtree via the existing mint-behavioral-subtree command.

   Re-orchestration, not reinvention: reuses the standing judge-averages
   read-model (Slice 1) + get-consolidation-total + the ALREADY-consolidated
   tree-class description (the consolidator's synthesis — NO second LLM path)
   + the mint-behavioral-subtree command (stable derived id). The conservative
   gate (Slice 2) is the safety."
  (:require [clojure.string :as str]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.grain.command-processor-v2.interface :as command-processor]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.todo-processor-v2.interface :refer [defprocessor]]
            [ai.obney.grain.time.interface :as time]
            [com.brunobonacci.mulog :as u]))

;; =============================================================================
;; Slice 2 — the conservative harvest GATE (pure)
;; =============================================================================
;;
;; The gate IS the safety: harvest is the ONLY automatic path that CREATES a
;; durable behavior, so the bar is deliberately HIGH and conservative. A
;; class must clear ALL FOUR conditions:
;;   1. RECURRING       — occurrences >= :min-occurrences
;;   2. CONSISTENT      — each of the last :consistency-window scored
;;                        occurrences clears :consistency-floor (CC-26)
;;   3. WELL-SCORED     — EVERY judge dimension's lifetime mean clears
;;                        :dimension-floor (CC-26)
;;   4. COHERENT        — a tight cluster, not a grab-bag: the count of
;;                        distinct tree-shapes seen for the class is small
;;                        relative to occurrences
;;                        (distinct-tree-shapes / occurrences <= :max-shapes-ratio).
;;                        A recurring pattern converges on a few shapes; a
;;                        grab-bag scatters across many.
;;
;; Knobs are tunable and started HIGH (measurement-first: raise the bar,
;; lower it only with data).
;;
;; CC-26 — WELL-SCORED was ONE SCALAR, and that scalar was a MEAN OF MEANS.
;; It collapsed two axes the spec (rule PromoteWellScoredClass) keeps separate:
;;
;;   requires: quality.all(dimension => dimension.score >= dimension_floor(...))
;;       -> across JUDGES at one moment. One catastrophic dimension vetoes
;;          promotion and cannot be compensated for by strength elsewhere.
;;   requires: consistently_qualified(tree_class, consistency_window)
;;       -> across OCCURRENCES over time. Durable creation requires the class
;;          to have qualified REPEATEDLY, so a single disastrous occurrence is
;;          not averaged away.
;;
;; MEASURED consequence of the collapse (grill GR-5, reproduced as a test):
;; a class with 20 occurrences and FOUR disastrous (0.0) ones scored exactly
;; 0.800 and PROMOTED. No value of the threshold could have fixed that — a
;; lifetime mean destroys "has qualified repeatedly" UPSTREAM of the gate.
;;
;; The two axes are the two marginals of the (judge x occurrence) score matrix.
;; Neither is a substitute for the other and neither may be re-collapsed into
;; a single scalar.

(def default-harvest-config
  "Conservative defaults — started HIGH. Only a class with real recurring
   volume (>= 10), EVERY judge dimension clearing :dimension-floor, EVERY one
   of its last :consistency-window scored occurrences clearing
   :consistency-floor, and shape-convergence (<= half as many distinct shapes
   as occurrences) is harvested.

   Mirrors specs/ontology.allium's config block:
     promotion_occurrence_threshold = 10   maximum_shape_ratio = 0.5
     consistency_window = 5                consistency_floor  = 0.8
     dimension_floor = 0.8"
  {:min-occurrences    10
   :dimension-floor    0.8
   :consistency-window 5
   :consistency-floor  0.8
   :max-shapes-ratio   0.5})

(def known-judge-dimension-count
  "LOAD-BEARING ASSUMPTION, recorded so the next change is a DECISION and not
   an accident.

   The corpus this gate was calibrated against has EXACTLY ONE judge
   dimension (implementation-turn/coding-outcome, 120/120 scores). Two things
   depend on that number:

   1. `:dimension-floor` is UNIFORM. The spec's `dimension_floor(dimension)`
      is per-dimension; with one dimension there is nothing to differentiate,
      so a single value is faithful. A SECOND judge makes per-dimension floors
      a real question — a strict judge and a lenient judge cannot share a
      floor without one of them being wrong.
   2. The per-dimension floor is a PROVABLE NO-OP at this count: with one
      value, `every? >= floor` and `mean >= floor` are the same predicate
      (pinned by cc26-dimension-floor-is-a-no-op-at-one-judge). That equality
      is what made the dimensional half of CC-26 safe to ship without
      re-baselining the live corpus. It stops holding at TWO judges, and at
      FIVE judges the collapsed mean promotes a class with a dimension
      scoring ZERO (measured: 3 judges -> 0.667 fails, 4 -> 0.750 fails,
      5 -> exactly 0.800, promotes).

   `maybe-harvest!` logs ::judge-dimension-count-changed whenever the observed
   count differs from this, so the assumption announces its own expiry."
  1)

(defn consistently-qualified?
  "The spec's `consistently_qualified(tree_class, consistency_window)`: the
   LAST `consistency-window` scored occurrences must EACH clear
   `consistency-floor`.

   DELIBERATELY NOT a mean over the window. A mean over the window would
   reintroduce the identical defect one level up — 4 zeros among 20 average to
   0.800 exactly the way they did before, just over a shorter span. 'Has
   qualified repeatedly' is a property of every element of the window, not of
   its average.

   A class with FEWER than `consistency-window` scored occurrences has not yet
   demonstrated repetition and does not qualify — the conservative bar IS the
   safety. Unscored occurrences carry no judge signal and simply do not
   participate in the window (an unscored occurrence is not a zero; cf. the
   spec's ExcludeAbstainedEvaluations)."
  [occurrence-scores consistency-window consistency-floor]
  (boolean
    (and (number? consistency-window)
         (pos? consistency-window)
         (number? consistency-floor)
         (sequential? occurrence-scores)
         (let [window (take-last consistency-window occurrence-scores)]
           (and (= (count window) consistency-window)
                (every? #(and (number? %) (>= % consistency-floor)) window))))))

(defn every-dimension-qualified?
  "The spec's `quality.all(dimension => dimension.score >= dimension_floor)`:
   EVERY judge dimension's lifetime mean clears `dimension-floor`. One
   catastrophic dimension vetoes promotion and cannot be compensated for by
   strength elsewhere — which a MEAN over the dimensions does allow (measured:
   at five judges, one dimension scoring ZERO still averages to exactly 0.800
   and promoted).

   No judge signal at all (nil / empty) never qualifies: a class that was
   never judged has not been judged well.

   Note this is the OTHER axis from consistently-qualified? — per judge over
   the class's LIFETIME, versus per occurrence over the RECENT window. They
   are not interchangeable and must not be collapsed back into one scalar."
  [judge-averages dimension-floor]
  (boolean
    (and (number? dimension-floor)
         (map? judge-averages)
         (seq judge-averages)
         (every? #(and (number? %) (>= % dimension-floor))
                 (vals judge-averages)))))

(defn harvest-candidate?
  "Pure conservative gate. Returns true iff the class is RECURRING and
   well-scored on BOTH axes (every DIMENSION over its lifetime, every recent
   OCCURRENCE over the window) and COHERENT, per config.

   Metrics:
     :occurrences               lifetime count of classifications
     :judge-averages            {judge-name -> lifetime mean} — the
                                DIMENSION axis
     :occurrence-scores         per-occurrence aggregate judge score, in
                                temporal order (most recent LAST) — the
                                CONSISTENCY axis
     :distinct-tree-shapes      coherence signal

   A non-positive occurrence count never passes (avoids divide-by-zero +
   seeds/total=0 slipping through)."
  [{:keys [occurrences judge-averages occurrence-scores distinct-tree-shapes]}
   {:keys [min-occurrences dimension-floor
           consistency-window consistency-floor max-shapes-ratio]}]
  (boolean
    (and (number? occurrences)
         (pos? occurrences)
         (>= occurrences min-occurrences)
         (every-dimension-qualified? judge-averages dimension-floor)
         (consistently-qualified? occurrence-scores consistency-window consistency-floor)
         (number? distinct-tree-shapes)
         (<= (/ (double distinct-tree-shapes) (double occurrences))
             max-shapes-ratio))))

;; =============================================================================
;; Slice 3 — harvest orchestration + processor
;; =============================================================================

(defn- behavioral-subtree-uri [id] (str "behavioral-subtree:" id))

(defn- class-occurrence-pairs
  "The [source-sheet-id source-tick-id] pairs of every task-classified event
   assigned to this class — the per-OCCURRENCE identity. HP-2: the bare
   source-sheet-id is the STATIC workflow-definition sheet shared by every
   turn of a task-shape, so a sheet-only set either matches nothing (a
   bookend's :sheet-id is the EPHEMERAL Phase-2 sheet, a disjoint domain) or
   over-matches across classes sharing the host. The pair is what uniquely
   names one occurrence, on both the classification and the bookend
   (:source-sheet-id/:source-tick-id)."
  [ctx class-id]
  (->> (es/read (:event-store ctx)
                {:types #{:ontology/task-classified} :tenant-id (:tenant-id ctx)})
       (into [])
       (filter #(= class-id (:assigned-tree-id %)))
       (map (juxt :source-sheet-id :source-tick-id))
       (into #{})))

(defn distinct-tree-shapes
  "EL-4: count of distinct tree-fingerprints across executions attributed to
   this class. Mirrors the consolidator's aggregate :distinct-tree-shapes —
   the coherence signal the gate reads. HP-2: joined by the bookend's
   [:source-sheet-id :source-tick-id] occurrence pair (pre-HP-2 this filtered
   the class's HOST sheet-ids against the bookend's EPHEMERAL :sheet-id —
   disjoint domains, 0 rows always, so the coherence gate passed vacuously).
   Bookends predating the :source-tick-id field don't participate. Computed
   by a targeted scan; reached only past the cheap occurrence pre-gate, so
   it runs rarely."
  [ctx class-id]
  (let [pairs (class-occurrence-pairs ctx class-id)]
    (->> (es/read (:event-store ctx)
                  {:types #{:sheet/rlm-tree-execution-completed}
                   :tenant-id (:tenant-id ctx)})
         (into [])
         (filter #(contains? pairs [(:source-sheet-id %) (:source-tick-id %)]))
         (keep :tree-fingerprint)
         distinct
         count)))

(defn occurrence-scores
  "CC-26 — the CONSISTENCY axis: this class's per-OCCURRENCE aggregate judge
   score, in temporal order (most recent LAST). Occurrences with no judge
   score are omitted (no signal is not a zero).

   Why this is an event scan and not the standing judge-averages read-model:
   that read-model is an ORDER-FREE aggregate BY CONSTRUCTION (its whole
   design note is that a score seen before or after its classification both
   land correctly), so it cannot answer 'the last N'. Recency has to come from
   event-store order — the same idiom the consolidator's
   gather-recent-tree-class-events uses (`take-last recent-window-size` over
   an es/read). Like distinct-tree-shapes this is reached only past the cheap
   occurrence pre-gate, so it runs rarely.

   The per-occurrence aggregate is the mean of that occurrence's judge scores
   — the marginal along the DIMENSION axis. That is not a re-collapse of the
   two floors: the dimension floor is the OTHER marginal (per judge, over the
   class's lifetime), and the two together constrain both axes of the
   (judge x occurrence) matrix that the old single scalar flattened."
  [ctx class-id]
  (let [ordered-occurrences (->> (es/read (:event-store ctx)
                                          {:types #{:ontology/task-classified}
                                           :tenant-id (:tenant-id ctx)})
                                 (into [])
                                 (filter #(= class-id (:assigned-tree-id %)))
                                 (map (juxt :source-sheet-id :source-tick-id))
                                 (distinct))
        scores-by-occurrence (->> (es/read (:event-store ctx)
                                           {:types #{:judge/score-emitted}
                                            :tenant-id (:tenant-id ctx)})
                                  (into [])
                                  (filter #(number? (:score %)))
                                  (group-by (juxt :sheet-id :tick-id)))]
    (into []
          (keep (fn [occurrence-key]
                  (when-let [scores (seq (get scores-by-occurrence occurrence-key))]
                    (/ (reduce + 0.0 (map :score scores))
                       (double (count scores))))))
          ordered-occurrences)))

(defn- latest-classified-behavior-id
  "The top behavior-id from the most-recent task-classified event for this
   class that carries a non-empty :behavioral-subtrees — the live signal of
   which behavior this class composes into. nil when the class has never been
   behaviorally classified."
  [ctx class-id]
  (->> (es/read (:event-store ctx)
                {:types #{:ontology/task-classified} :tenant-id (:tenant-id ctx)})
       (into [])
       (filter #(and (= class-id (:assigned-tree-id %))
                     (seq (:behavioral-subtrees %))))
       last
       :behavioral-subtrees
       first
       :behavior-id))

(defn nearest-abstract-behavior
  "Walk skos:broader UP from the class's classified behavior to the top
   abstract behavior (auto-waterfall). Returns the abstract behavior's id
   (the parent-behavior for the mint), or nil when the class has no
   behavioral signal to anchor under — in which case harvest is skipped
   rather than creating an orphan."
  [ctx class-id]
  (when-let [behavior-id (latest-classified-behavior-id ctx class-id)]
    (let [concepts (rmp/project ctx :ontology/concepts)]
      (loop [uri (behavioral-subtree-uri behavior-id)
             id  behavior-id
             seen #{}]
        (let [parent-uri (->> (get-in concepts [uri :broader])
                              (filter #(and (string? %)
                                            (str/starts-with? % "behavioral-subtree:")))
                              first)]
          (if (and parent-uri (not (contains? seen parent-uri)))
            (recur parent-uri
                   (subs parent-uri (count "behavioral-subtree:"))
                   (conj seen uri))
            id))))))

(defn already-harvested?
  "Fire-once guard, keyed on the STABLE class-id (independent of any name or
   parent drift): true when a :harvested behavioral-subtree already records
   this class as its source."
  [ctx class-id]
  (boolean
    (some #(and (= :harvested (:provenance %))
                (= class-id (:harvested-from-tree-class %)))
          (into [] (es/read (:event-store ctx)
                            {:types #{:ontology/behavioral-subtree-minted}
                             :tenant-id (:tenant-id ctx)})))))

(defn- best-recommended-pattern
  "The worked DSL for the class = the highest-confidence strength's
   :recommended-pattern from the consolidated description."
  [desc]
  (->> (:strengths desc)
       (filter :recommended-pattern)
       (sort-by :confidence >)
       first
       :recommended-pattern))

(defn harvest-body
  "Assemble the harvested behavior's body by REUSING the consolidator's
   already-synthesized tree-class description (no second synthesis LLM):
   transplant :capabilities/:strengths/:weaknesses/:representative-uses/
   :avoid-when/:summary, add the worked DSL as :recommended-pattern, and
   stamp :version + :consolidated-from-event-count so anti-recency engages.
   Returns nil when the class has no consolidated description yet."
  [desc occurrences]
  (when desc
    (cond-> {:capabilities         (vec (:capabilities desc))
             :strengths            (vec (:strengths desc))
             :weaknesses           (vec (:weaknesses desc))
             :representative-uses  (vec (:representative-uses desc))
             :avoid-when           (vec (:avoid-when desc))
             :summary              (or (:summary desc) "")
             :version              1
             :consolidated-from-event-count occurrences}
      (best-recommended-pattern desc)
      (assoc :recommended-pattern (best-recommended-pattern desc)))))

(defn- harvest-name [class-id] (str "harvested-tree-class-" class-id))

(defn- mint-harvested! [ctx class-id body parent-behavior]
  (command-processor/process-command
    (assoc ctx :command
           {:command/name :ontology/mint-behavioral-subtree
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :name (harvest-name class-id)
            :body body
            :parent-behavior parent-behavior
            :provenance :harvested
            :harvested-from-tree-class class-id})))

(defn maybe-harvest!
  "The harvest decision for one :tree-class. Cheap pre-gate first (recurring
   volume + not-already-harvested), then the full conservative gate + mint.
   Idempotent: fires ONCE per class (the class-id guard). No-op below the
   gate — the conservative bar IS the safety."
  ([ctx class-id] (maybe-harvest! ctx class-id default-harvest-config))
  ([ctx class-id config]
   (let [occurrences (rm/get-consolidation-total ctx :tree-class class-id)]
     (when (and (>= occurrences (:min-occurrences config))
                (not (already-harvested? ctx class-id)))
       (let [judge-avgs (rm/get-tree-class-judge-averages ctx class-id)
             scores (occurrence-scores ctx class-id)
             shapes (distinct-tree-shapes ctx class-id)
             metrics {:occurrences occurrences
                      :judge-averages judge-avgs
                      :occurrence-scores scores
                      :distinct-tree-shapes shapes}]
         ;; The judge count is a LOAD-BEARING ASSUMPTION (see
         ;; known-judge-dimension-count). Announce its expiry rather than
         ;; letting a new judge silently change what the uniform
         ;; :dimension-floor means.
         (when (and (seq judge-avgs)
                    (not= (count judge-avgs) known-judge-dimension-count))
           (u/log ::judge-dimension-count-changed
                  :class-id class-id
                  :observed (count judge-avgs)
                  :assumed known-judge-dimension-count
                  :judges (vec (sort (keys judge-avgs)))
                  :note (str "the uniform :dimension-floor was calibrated for "
                             known-judge-dimension-count
                             " judge dimension(s); per-dimension floors are now a "
                             "decision to make, not an accident to absorb")))
         (when (harvest-candidate? metrics config)
           (let [desc (rm/get-description ctx :tree-class class-id)
                 parent (nearest-abstract-behavior ctx class-id)
                 body (harvest-body desc occurrences)]
             (cond
               (nil? body)
               (u/log ::harvest-skipped-no-description :class-id class-id)

               (nil? parent)
               (u/log ::harvest-skipped-no-parent
                      :class-id class-id
                      :note "no behavioral anchor via skos:broader — not creating an orphan")

               :else
               (do (u/log ::harvest-minting
                          :class-id class-id
                          :parent-behavior parent
                          :occurrences occurrences
                          :judge-averages judge-avgs
                          :recent-occurrence-scores
                          (take-last (:consistency-window config) scores)
                          :distinct-tree-shapes shapes)
                   (mint-harvested! ctx class-id body parent))))))))))

(defprocessor :ontology on-tree-class-check-harvest
  {:topics #{:sheet/node-execution-completed
             :sheet/rlm-tree-execution-completed
             :ontology/task-classified}}
  "EL-4 (ADR 0015): after a task-classified event, check whether the class
   has crossed the conservative harvest gate; if so (and not already
   harvested), crystallize it into a durable behavioral-subtree. Only
   :ontology/task-classified identifies a :tree-class target; the other
   topics are subscribed for symmetry with the threshold processor but are
   no-ops here."
  [{:keys [event] :as context}]
  (when (= :ontology/task-classified (:event/type event))
    (when-let [class-id (:assigned-tree-id event)]
      (try
        (maybe-harvest! context class-id)
        (catch Exception e
          (u/log ::harvest-error :class-id class-id :error (.getMessage e)))))))
