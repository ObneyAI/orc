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
;;   3. WELL-SCORED     — EVERY judge dimension's mean over its most recent
;;                        :dimension-window scored occurrences clears
;;                        :dimension-floor (CC-26; CC-24b/ADR 0029 replaced
;;                        the LIFETIME mean here, which measured 0 firings in
;;                        105 real positions at 0.80 AND at 0.75 — a mean can
;;                        never forget, so evidence produced by since-fixed
;;                        engine defects vetoed the class forever)
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
  "Conservative defaults. Only a class with real recurring volume (>= 10),
   EVERY judge dimension clearing :dimension-floor over its most recent
   :dimension-window scored occurrences, EVERY one of its last
   :consistency-window scored occurrences clearing :consistency-floor, and
   shape-convergence (<= half as many distinct shapes as occurrences) is
   harvested.

   Mirrors specs/ontology.allium's config block:
     promotion_occurrence_threshold = 10   maximum_shape_ratio = 0.5
     consistency_window = 5                consistency_floor  = 0.75
     dimension_floor = 0.75                dimension_window   = 10

   CC-24b (ADR 0029, MEASURED) moved both floors 0.8 -> 0.75 and made them
   mean something chosen. `evaluation/core/scale.clj` maps a discrete 1-5
   judge scale by (n-1)/(max-min), so the achievable per-occurrence set at one
   judge is exactly {0, 0.25, 0.5, 0.75, 1.0}. 0.8 sat in the GAP between band
   4 (described to the judge as good work) and band 5, so it silently read
   'PERFECT ONLY': every floor in {0.60, 0.70, 0.75} produced identical gate
   behaviour on the real corpus, as did every floor in {0.80, 0.90, 1.00}.
   0.75 is the lattice point that says what we mean — band 4 or better
   qualifies — and 'good work counts' is now an explicit policy rather than an
   accident of where a number fell between bands. If band 4 later proves too
   permissive the lever is one ratified value ON the lattice (0.75 -> 1.00),
   not a redesign. Anything reading these as '80%' is wrong; they are lattice
   points, and the spec says so.

   :dimension-window is new: the dimension axis reads a TRAILING window, never
   a raw lifetime mean (see every-dimension-qualified?)."
  {:min-occurrences    10
   :dimension-floor    0.75
   :dimension-window   10
   :consistency-window 5
   :consistency-floor  0.75
   :max-shapes-ratio   0.5})

(def floor-comparison-tolerance
  "The spec's `config floor_comparison_tolerance` (CC-29): floor verdicts are
   exact on the DISCRETE judge scale. Judge scores are band values on a 1-5
   scale mapped (n-1)/4 — the achievable set is {0, 0.25, 0.5, 0.75, 1.0},
   quantum 0.25 at one judge (0.25/J when J judges are averaged). CORRECTED
   2026-08-12: this said 0.05, which was never true of the shipped scale; the
   tolerance argument only gets SAFER with the real, larger quantum. That the
   FLOORS sit between bands is CC-24's crux, not this tolerance's concern. Any
   legitimate below-floor mean differs from the floor by at
   least quantum/occurrence-count — orders of magnitude above binary-
   representation error, which is what actually produced a rejection: a class
   whose every score was exactly 0.8 projected its lifetime mean as
   0.7999999999999999 (double accumulation) and was rejected by the >= 0.8
   floor (measured, CC-26 real-stream check). A floor comparison therefore
   tolerates representation error strictly smaller than the scale's quantum;
   the tolerance can never change a verdict between two values the scale can
   actually distinguish.

   SAFE WINDOW: legitimate distinctions are >= quantum/count (>= 0.25/40 =
   6.25e-3 at forty occurrences, and larger below that; ~1e-5 even at absurd
   counts —
   realistic counts), representation error is ~1e-13, so 1e-9 keeps at least
   three orders of magnitude of margin on EACH side. Applied at the floor-
   COMPARISON seam only — `x >= floor` becomes `x >= (- floor tolerance)` in
   the two floor predicates below — never exact/rational arithmetic in the
   folds, which would change read-model state shapes (version bumps, cache
   serialization) for a problem the comparison seam fixes completely. The
   gate's integer thresholds (:min-occurrences) and the exactly-computed
   shapes ratio need no tolerance: no comparable artifact is possible there."
  1.0E-9)

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
   spec's ExcludeAbstainedEvaluations).

   CC-29: the floor comparison tolerates representation error — see
   `floor-comparison-tolerance`. A window score is a COMPUTED per-occurrence
   aggregate (mean over that occurrence's judge scores), so a set of band
   values whose true mean sits exactly ON the floor can accumulate to a double
   strictly below the floor literal (e.g. bands 0.6/0.9/0.9 -> 0.7999999999999999)
   and must not be rejected for it. Genuine deficits are >= quantum/judge-count,
   far outside the tolerance."
  [occurrence-scores consistency-window consistency-floor]
  (boolean
    (and (number? consistency-window)
         (pos? consistency-window)
         (number? consistency-floor)
         (sequential? occurrence-scores)
         (let [window (take-last consistency-window occurrence-scores)
               effective-floor (- consistency-floor floor-comparison-tolerance)]
           (and (= (count window) consistency-window)
                (every? #(and (number? %) (>= % effective-floor)) window))))))

(defn every-dimension-qualified?
  "The spec's `quality.all(dimension => dimension.score >= dimension_floor)`:
   EVERY judge dimension clears `dimension-floor`.

   CC-24b (ADR 0029): `judge-averages` is each judge's mean over its most
   recent `dimension_window` SCORED occurrences — a TRAILING WINDOW, never a
   raw lifetime mean. Measured: the lifetime mean against the floor fired 0
   times in 105 real positions at BOTH 0.80 and 0.75 (the dominant class would
   have needed ~97 consecutive perfect occurrences, and its zero-blocks came
   from ENGINE defects since fixed); trailing-10 fires 38/105 and abstains
   67/105 — it can do both on real data, which is ADR 0027's bar. Eligibility
   can therefore be LOST as well as gained; that is intended (enforcement is
   continuously earned).

   One catastrophic dimension vetoes promotion and cannot be compensated for
   by strength elsewhere — which a MEAN over the dimensions does allow
   (measured: at five judges, one dimension scoring ZERO still averages to
   exactly 0.800 and promoted).

   No judge signal at all (nil / empty) never qualifies: a class that was
   never judged has not been judged well.

   Note this is the OTHER axis from consistently-qualified? — per JUDGE over
   the class's recent scored occurrences, versus per OCCURRENCE over the
   consistency window. They are the two marginals of the (judge x occurrence)
   matrix, are not interchangeable, and must not be collapsed back into one
   scalar.

   CC-29: the floor comparison tolerates representation error — see
   `floor-comparison-tolerance`. This is the MEASURED artifact site: a class
   whose every score was exactly 0.8 projected its mean as
   0.7999999999999999 (double accumulation in the standing read-model) and was
   rejected by the raw >= 0.8 floor. An at-floor mean qualifies; a genuinely
   below-floor mean (>= quantum/window short) is still rejected."
  [judge-averages dimension-floor]
  (boolean
    (and (number? dimension-floor)
         (map? judge-averages)
         (seq judge-averages)
         (let [effective-floor (- dimension-floor floor-comparison-tolerance)]
           (every? #(and (number? %) (>= % effective-floor))
                   (vals judge-averages))))))

(defn harvest-candidate?
  "Pure conservative gate. Returns true iff the class is RECURRING and
   well-scored on BOTH axes (every DIMENSION over its recent scored
   occurrences, every recent OCCURRENCE over the consistency window), per
   config.

   Metrics:
     :occurrences               lifetime count of verdict-qualified campaigns
     :judge-trailing-averages   {judge-name -> mean over that judge's most
                                recent :dimension-window scored occurrences} —
                                the DIMENSION axis. CC-24b (ADR 0029) renamed
                                this from :judge-averages, deliberately: the
                                LIFETIME mean is a different projection, is
                                still live for logging/parity, and firing the
                                gate on it measured 0/105 real positions. A
                                name that no longer says which projection it
                                is would let that regression back in silently.
     :occurrence-scores         per-occurrence aggregate judge score, in
                                temporal order (most recent LAST) — the
                                CONSISTENCY axis

   RR-21: the COHERENCE clause (winning-shape-coherence, the ratio of
   distinct successful terminal shapes to successful campaigns) is computed
   and REPORTED (see `winning-shape-coherence` and the durable
   :ontology/shape-coherence-reported event) but never gates here — the
   spec's `rule PromoteWellScoredClass` carries no coherence `requires`, and
   ADR 0029's dossier note is explicit: a threshold that has never fired
   cannot be distinguished from one that never will, so it is measured
   before it is trusted to block. Threshold calibration is a later,
   data-driven slice.

   A non-positive occurrence count never passes (avoids divide-by-zero +
   seeds/total=0 slipping through)."
  [{:keys [occurrences judge-trailing-averages occurrence-scores]}
   {:keys [min-occurrences dimension-floor
           consistency-window consistency-floor]}]
  (boolean
    (and (number? occurrences)
         (pos? occurrences)
         (>= occurrences min-occurrences)
         (every-dimension-qualified? judge-trailing-averages dimension-floor)
         (consistently-qualified? occurrence-scores consistency-window consistency-floor))))

(defn harvest-gate-report
  "The gate's verdict, CLAUSE BY CLAUSE, so a decision nobody watched can be
   read back afterwards. `:candidate?` is `harvest-candidate?` itself — this
   is a lens on the gate, never a second implementation of it.

   RR-21: the `:coherence` clause reports `winning-shape-coherence`'s
   `:status` (`:qualified` | `:rejected` | `:not-measurable` — see that fn)
   verbatim as `:verdict`, alongside the counts and ratio it observed. Unlike
   every other clause here, `:coherence`'s verdict is NEVER folded into
   `:candidate?` — see `harvest-candidate?`. `:not-measurable` replaces the
   pre-RR-21 `:abstained` (the all-trees `distinct-tree-shapes` measure,
   which passed vacuously on 100% of occurrences in both real stores and is
   now retired from harvest — the consolidator keeps its own independent
   descriptive aggregate for reflection)."
  [{:keys [occurrences judge-trailing-averages occurrence-scores winning-shape-coherence]
    :as metrics}
   {:keys [min-occurrences dimension-floor dimension-window
           consistency-window consistency-floor max-shapes-ratio] :as config}]
  {:recurring   {:verdict (if (and (number? occurrences)
                                   (pos? occurrences)
                                   (>= occurrences min-occurrences))
                            :qualified :rejected)
                 :occurrences occurrences
                 :threshold min-occurrences}
   :dimension   {:verdict (cond
                            (not (seq judge-trailing-averages)) :abstained
                            (every-dimension-qualified? judge-trailing-averages
                                                        dimension-floor) :qualified
                            :else :rejected)
                 :trailing-averages judge-trailing-averages
                 :window dimension-window
                 :floor dimension-floor}
   :consistency {:verdict (if (consistently-qualified? occurrence-scores
                                                       consistency-window
                                                       consistency-floor)
                            :qualified :rejected)
                 :window (vec (take-last (or consistency-window 0) occurrence-scores))
                 :floor consistency-floor}
   :coherence   {:verdict (:status winning-shape-coherence)
                 :ratio (:ratio winning-shape-coherence)
                 :successful-campaigns (:successful-campaigns winning-shape-coherence)
                 :successful-shape-observations (:successful-shape-observations
                                                  winning-shape-coherence)
                 :distinct-successful-shapes (:distinct-successful-shapes
                                              winning-shape-coherence)
                 :max-shapes-ratio max-shapes-ratio}
   :candidate?  (harvest-candidate? metrics config)})

;; =============================================================================
;; Slice 3 — harvest orchestration + processor
;; =============================================================================

(defn- behavioral-subtree-uri [id] (str "behavioral-subtree:" id))

(defn- durable-through
  "`events` (already in durable event-store order) truncated to include
   everything up to and INCLUDING the event whose `:event/id` is
   `upper-bound-event-id`. nil bound means unbounded — 'everything so far'.
   Falls back to unbounded if the named id is not found (defensive only; the
   report command always names an event it just read from the same store)."
  [events upper-bound-event-id]
  (if (nil? upper-bound-event-id)
    (vec events)
    (let [truncated
          (persistent!
           (reduce (fn [acc event]
                     (let [acc (conj! acc event)]
                       (if (= upper-bound-event-id (:event/id event))
                         (reduced acc)
                         acc)))
                   (transient [])
                   events))]
      (if (and (seq truncated) (= upper-bound-event-id (:event/id (peek truncated))))
        truncated
        (vec events)))))

(defn class-occurrences-through
  "RR-21 (finding, inspection); RR-23 (tag-scoped): this class's
   `:ontology/tree-class-occurrence-recorded` events, in durable order,
   bounded to (and including) the one whose `:event/id` is
   `upper-bound-event-id` — nil bound means unbounded (every occurrence
   recorded so far). PUBLIC: the durable-order 'as of THIS occurrence'
   snapshot both `:verdict-occurrences` and `winning-shape-coherence` are
   computed over, so the report command derives both numbers from the
   identical bounded view — never the store's current state at PROCESSING
   time, which is a race a backlogged/reordered delivery can and does
   expose (reproduced: three verdicts landed before the handler ran on any
   of them; every report read back the FINAL count instead of its own
   occurrence's position).

   RR-23: scoped by the `[:description-target class-id]` tag every
   `:ontology/tree-class-occurrence-recorded` event already carries
   (commands.clj) instead of a type-wide tenant scan — cost no longer
   grows with occurrences belonging to other classes. The `:assigned-
   tree-id` filter stays as a defensive check (tag narrows, filter
   decides); no legacy concern, this tag predates RR-23."
  [ctx class-id upper-bound-event-id]
  (let [this-classes-occurrences
        (->> (es/read (:event-store ctx)
                      {:types #{:ontology/tree-class-occurrence-recorded}
                       :tenant-id (:tenant-id ctx)
                       :tags #{[:description-target class-id]}})
             (into [])
             (filter #(= class-id (:assigned-tree-id %))))]
    (durable-through this-classes-occurrences upper-bound-event-id)))

(defn- successful-verdict-pairs
  "RR-21: the [source-sheet-id source-tick-id] pairs of this class's
   `:success`-verdict tree-class occurrences ONLY, bounded to
   `upper-bound-event-id` (see `class-occurrences-through`) — the population
   the winning-shape ratio is computed over. Failed, timed-out, cancelled and
   abandoned campaigns never enter this set (cancelled/abandoned campaigns
   have no verdict occurrence at all; failed/timed-out ones are excluded by
   the `:verdict` filter), so they cannot make a failure-heavy class look
   more coherent. HP-2: the bare source-sheet-id is the STATIC
   workflow-definition sheet shared by every turn of a task-shape, so a
   sheet-only set either matches nothing (a bookend's :sheet-id is the
   EPHEMERAL Phase-2 sheet, a disjoint domain) or over-matches across classes
   sharing the host. The pair is what uniquely names one occurrence across
   its classification attribution, explicit verdict fact, and bookend
   (:source-sheet-id/:source-tick-id)."
  [ctx class-id upper-bound-event-id]
  (->> (class-occurrences-through ctx class-id upper-bound-event-id)
       (filter #(= :success (:verdict %)))
       (map (juxt :source-sheet-id :source-tick-id))
       (into #{})))

(defn- winning-shape-bookends
  "RR-23: this `pair`'s (`[source-sheet-id source-tick-id]`) Phase-2 bookends,
   scoped by the `[:source-tick source-tick-id]` tag `record-rlm-tree-
   execution-completion` now writes at its emit site — a targeted read
   instead of a type-wide tenant scan. `:source-sheet-id` stays a defensive
   filter (tag narrows, filter decides).

   Legacy replay: a bookend written BEFORE this slice landed carries no
   `:source-tick` tag and is invisible to the scoped read above. Correctness
   for such pre-existing stores is opt-in only (`legacy-replay?`), never the
   default — falling back to a full type scan on every call would restore
   exactly the O(store) cost this slice removes. When opted in and the
   scoped read finds nothing, fall back once to the old unscoped scan
   filtered by `pair`."
  [ctx pair legacy-replay?]
  (let [[sheet tick] pair
        scoped (->> (es/read (:event-store ctx)
                             {:types #{:sheet/rlm-tree-execution-completed}
                              :tenant-id (:tenant-id ctx)
                              :tags #{[:source-tick tick]}})
                    (into [])
                    (filter #(= sheet (:source-sheet-id %))))]
    (if (and legacy-replay? (empty? scoped))
      (->> (es/read (:event-store ctx)
                    {:types #{:sheet/rlm-tree-execution-completed}
                     :tenant-id (:tenant-id ctx)})
           (into [])
           (filter #(and (= sheet (:source-sheet-id %)) (= tick (:source-tick-id %)))))
      scoped)))

(defn- winning-shape
  "RR-21 + RR-23: the fingerprint of the LAST `:status :success` Phase-2
   bookend, in durable event order, among `pair`'s bookends (see
   `winning-shape-bookends`) — the winning shape that carried that
   successful campaign to success. Shapes the campaign abandoned earlier
   (failed bookends, or earlier success bookends superseded by a later one)
   are process evidence, never winning shapes. nil when the campaign has no
   success bookend at all — it solved by direct tool call, or the (non-nil)
   fingerprint field is simply absent — which is exactly the 'successful
   campaign with no recorded shape' case: it contributes to
   `:successful-campaigns` but not to `:successful-shape-observations`."
  [ctx pair legacy-replay?]
  (->> (winning-shape-bookends ctx pair legacy-replay?)
       (filter #(and (= pair [(:source-sheet-id %) (:source-tick-id %)])
                     (= :success (:status %))))
       last
       :tree-fingerprint))

(defn winning-shape-coherence
  "RR-21 (rule ReportSuccessfulShapeCoherence, dossier G11): the ratified
   coherence measure — distinct successful terminal shapes divided by
   successful campaigns, ONE winning shape per successful campaign (the
   terminal `:success` bookend in durable order; see `winning-shape`).
   Replaces the never-witnessed all-trees `distinct-tree-shapes` measure
   (retired from harvest; the consolidator keeps its own independent
   descriptive aggregate for reflection).

   Measurable iff `successful-campaigns > 0` AND
   `successful-shape-observations > 0` — a class with successful campaigns
   but no recorded shape (every campaign solved by direct tool call) is
   `:not-measurable`, distinct from a real `:qualified`/`:rejected` verdict:
   'harvesting nothing looks identical to nothing qualifying' (ADR 0029),
   so the absence of evidence is never reported as if it were evidence.
   Computed by a targeted scan, like `occurrence-scores`.

   4-arity `upper-bound-event-id` (see `class-occurrences-through`) computes
   the measure strictly AS OF that occurrence's durable position — the fix
   for the scheduling-dependent race a backlogged/reordered delivery
   exposed. The 2- and 3-arities stay unbounded ('everything so far'), used
   by `maybe-harvest!`'s live (non-durable) gate check, which has no single
   occurrence identity to be 'as of'.

   RR-23: bookends are read PER PAIR, scoped by `[:source-tick source-tick-
   id]` (see `winning-shape-bookends`), instead of one type-wide tenant
   scan — cost no longer grows with other classes' bookends. `config`'s
   optional `:legacy-replay?` (default false/absent — never on by default)
   opts every pair's read into a one-time unscoped fallback when its
   scoped read is empty, for stores holding bookends written before this
   slice; `default-harvest-config` does not set it, so the live gate stays
   tag-scoped-only unless a caller explicitly asks for legacy replay."
  ([ctx class-id] (winning-shape-coherence ctx class-id default-harvest-config nil))
  ([ctx class-id config] (winning-shape-coherence ctx class-id config nil))
  ([ctx class-id {:keys [max-shapes-ratio legacy-replay?]} upper-bound-event-id]
   (let [pairs (successful-verdict-pairs ctx class-id upper-bound-event-id)
         successful-campaigns (count pairs)
         winning-shapes (into [] (keep #(winning-shape ctx % legacy-replay?)) pairs)
         successful-shape-observations (count winning-shapes)
         distinct-successful-shapes (count (distinct winning-shapes))
         measurable? (and (pos? successful-campaigns) (pos? successful-shape-observations))
         ratio (when measurable? (/ distinct-successful-shapes successful-campaigns))]
     {:successful-campaigns successful-campaigns
      :successful-shape-observations successful-shape-observations
      :distinct-successful-shapes distinct-successful-shapes
      :ratio ratio
      :status (cond
                (not measurable?) :not-measurable
                (<= ratio max-shapes-ratio) :qualified
                :else :rejected)})))

(defn occurrence-scores
  "CC-26 — the CONSISTENCY axis: this class's per-OCCURRENCE aggregate judge
   score, in temporal order (most recent LAST). Occurrences with no judge
   score are omitted (no signal is not a zero).

   Why this is an event scan and not the standing judge-averages read-model:
   that read-model is an ORDER-FREE aggregate BY CONSTRUCTION (its whole
   design note is that a score seen before or after its classification both
   land correctly), so it cannot answer 'the last N'. Recency has to come from
   event-store order — the same idiom the consolidator's
   gather-recent-tree-class-events uses (a newest-last es/read; since PR-1
   bounded there by the evidence token budget rather than an event count).
   Like distinct-tree-shapes this is reached only past the cheap
   occurrence pre-gate, so it runs rarely.

   The per-occurrence aggregate is the mean of that occurrence's judge scores
   — the marginal along the DIMENSION axis. That is not a re-collapse of the
   two floors: the dimension floor is the OTHER marginal (per judge, over the
   class's lifetime), and the two together constrain both axes of the
   (judge x occurrence) matrix that the old single scalar flattened.

   RR-23: the occurrence scan is scoped by `[:description-target class-id]`
   (the tag `:ontology/tree-class-occurrence-recorded` already carries).
   Judge scores are then read ONCE PER OCCURRENCE, scoped by
   `[:tick tick-id]` (the tag `:judge/score-emitted` already carries), in
   place of one type-wide scan over the tenant's fastest-growing event type
   grouped in memory — cost is now proportional to this class's occurrence
   count, not the store's total score count. `:sheet-id` stays a defensive
   filter (tick alone already names one occurrence; tag narrows, filter
   decides)."
  [ctx class-id]
  (let [ordered-occurrences (->> (es/read (:event-store ctx)
                                          {:types #{:ontology/tree-class-occurrence-recorded}
                                           :tenant-id (:tenant-id ctx)
                                           :tags #{[:description-target class-id]}})
                                 (into [])
                                 (filter #(= class-id (:assigned-tree-id %)))
                                 (map (juxt :source-sheet-id :source-tick-id))
                                 (distinct))]
    (into []
          (keep (fn [[sheet tick]]
                  (let [scores (->> (es/read (:event-store ctx)
                                             {:types #{:judge/score-emitted}
                                              :tenant-id (:tenant-id ctx)
                                              :tags #{[:tick tick]}})
                                    (into [])
                                    (filter #(and (number? (:score %)) (= sheet (:sheet-id %)))))]
                    (when (seq scores)
                      (/ (reduce + 0.0 (map :score scores))
                         (double (count scores)))))))
          ordered-occurrences)))

(defn- latest-classified-behavior-id
  "The top behavior-id from the most-recent task-classified event for this
   class that carries a non-empty :behavioral-subtrees — the live signal of
   which behavior this class composes into. nil when the class has never been
   behaviorally classified.

   RR-23: scoped by the `[:description-target class-id]` tag
   `:ontology/task-classified` already carries."
  [ctx class-id]
  (->> (es/read (:event-store ctx)
                {:types #{:ontology/task-classified} :tenant-id (:tenant-id ctx)
                 :tags #{[:description-target class-id]}})
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
   this class as its source.

   RR-23: scoped by the `[:harvested-tree-class ...]` tag the mint command
   already writes on this path (commands.clj's `mint-behavioral-subtree`,
   via the shared `harvested-tree-class-tag` derivation) instead of a
   type-wide scan of every minted behavioral-subtree for the tenant — a
   non-harvested mint carries no such tag at all, so the scoped read never
   sees it. `:provenance`/`:harvested-from-tree-class` stay a defensive
   filter (tag narrows, filter decides). No legacy concern: this tag
   predates RR-23."
  [ctx class-id]
  (boolean
    (some #(and (= :harvested (:provenance %))
                (= class-id (:harvested-from-tree-class %)))
          (into [] (es/read (:event-store ctx)
                            {:types #{:ontology/behavioral-subtree-minted}
                             :tenant-id (:tenant-id ctx)
                             :tags #{(rm/harvested-tree-class-tag class-id)}})))))

(defn- best-recommended-pattern
  "RR-20 (exact behavioral change 4): the worked DSL for the class.

   Only `:strengths` entries ever carry `:recommended-pattern` — a failed
   shape is recorded as a `:weakness` (RR-20's writer, `todo-processors/
   enrich-tree-class-with-emitted-dsl!`), so it surfaces under
   `:recommended-alternative` in `:weaknesses`, never here. A failed-only
   class therefore returns nil by construction: never a failed shape offered
   as the worked pattern.

   Ranked by `:verdict-corroborations` descending FIRST, then `:confidence`
   (support) descending, then `:last-reinforced-at` descending (most
   recently reinforced first). This IS 'prefer success-backed shapes
   corroborated by verdict occurrences over bare emitted artefacts', made
   an EXPLICIT primary sort key rather than left to support alone: a bare
   artefact re-emitted repeatedly earns support too (CV-2's ordinary
   `:support` reinforcement), so ranking on raw `:confidence` alone could
   let enough bare repetition outrank a shape corroborated by only ONE real
   campaign verdict — a class-of-evidence conflation the acceptance
   criterion 'prefers... over' does not tolerate. `:verdict-corroborations`
   (absent/nil treated as 0) is durable ONLY on the claims RR-19's
   `todo-processors/corroborate-worked-patterns-from-occurrence!` reinforced
   with `:evidence-basis :campaign-verdict` (`read-models/reinforce-claim`),
   so ANY corroborated shape (>= 1) outranks EVERY merely-repeated one (= 0)
   before support is ever compared. No cross-event query belongs here:
   `harvest-body`'s signature (`desc` + `occurrences`) is the propagated
   RR-20 contract and stays exactly that — the field is read straight off
   the already-assembled `:strengths` entries."
  [desc]
  (->> (:strengths desc)
       (filter :recommended-pattern)
       (sort (fn [a b]
               (let [by-corroboration (compare (or (:verdict-corroborations b) 0)
                                               (or (:verdict-corroborations a) 0))]
                 (if-not (zero? by-corroboration)
                   by-corroboration
                   (let [by-confidence (compare (or (:confidence b) 0.0)
                                                (or (:confidence a) 0.0))]
                     (if (zero? by-confidence)
                       (compare (or (:last-reinforced-at b) "")
                                (or (:last-reinforced-at a) ""))
                       by-confidence))))))
       first
       :recommended-pattern))

(defn- domain-label-for
  "RS-5 (gap B): the domain label lives on the class's tree-class CONCEPT —
   RS-3's `mint-domain-child` stamps the judged domain label directly onto
   the child's concept at birth (RS-P2: the command path, never a
   description body write — CC-6) — never in the assembled description
   `harvest-body` transplants. Returns nil (no ctx/class-id, no concept, a
   blank label, or the generic placeholder `ensure-tree-class-concept!`
   lazy-creates for an ordinary non-domain tree-class, whose :label equals
   its own id) so an ordinary tree-class's harvest stays byte-shaped."
  [ctx class-id]
  (when (and ctx class-id)
    (let [concept (rm/get-concept-by-uri ctx (str "tree-class:" class-id))
          label (:label concept)]
      (when (and (string? label)
                 (not (str/blank? label))
                 (not= label (str class-id)))
        label))))

(defn harvest-body
  "Assemble the harvested behavior's body by REUSING the consolidator's
   already-synthesized tree-class description (no second synthesis LLM):
   transplant :capabilities/:strengths/:weaknesses/:representative-uses/
   :avoid-when/:summary, add the worked DSL as :recommended-pattern, and
   stamp :version + :consolidated-from-event-count so anti-recency engages.
   Returns nil when the class has no consolidated description yet.

   RS-5 (gap B): the 4-arg arity additionally stamps :domain-label when the
   class is a domain child (`domain-label-for`) — the label lives on the
   CONCEPT, so the description alone can't carry it forward. The 2-arg
   arity (desc + occurrences, the propagated RR-20 contract) is UNCHANGED —
   every existing caller's body stays byte-identical."
  ([desc occurrences] (harvest-body nil desc occurrences nil))
  ([ctx desc occurrences class-id]
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
       (assoc :recommended-pattern (best-recommended-pattern desc))
       (domain-label-for ctx class-id)
       (assoc :domain-label (domain-label-for ctx class-id))))))

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
             ;; CC-24b (ADR 0029): the GATE reads the trailing window; the
             ;; lifetime mean is still projected, but only to be recorded.
             trailing-avgs (rm/get-tree-class-judge-recent-averages
                             ctx class-id (:dimension-window config))
             scores (occurrence-scores ctx class-id)
             coherence (winning-shape-coherence ctx class-id config)
             metrics {:occurrences occurrences
                      :judge-trailing-averages trailing-avgs
                      :occurrence-scores scores
                      :winning-shape-coherence coherence}
             report (harvest-gate-report metrics config)]
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
         ;; CC-24b (ADR 0029 decision 5): every full-gate evaluation records
         ;; its clause-by-clause verdict. Reached only past the cheap
         ;; occurrence pre-gate, so it is rare — and it is the only place the
         ;; coherence ABSTENTION (a clause passing on absent evidence) is
         ;; visible to an operator.
         (u/log ::harvest-gate-report
                :class-id class-id
                :recurring (:recurring report)
                :dimension (:dimension report)
                :consistency (:consistency report)
                :coherence (:coherence report)
                :lifetime-judge-averages judge-avgs
                :candidate? (:candidate? report))
         (when (:candidate? report)
           (let [desc (rm/get-description ctx :tree-class class-id)
                 parent (nearest-abstract-behavior ctx class-id)
                 body (harvest-body ctx desc occurrences class-id)]
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
                          :judge-trailing-averages trailing-avgs
                          :recent-occurrence-scores
                          (take-last (:consistency-window config) scores)
                          :winning-shape-coherence coherence)
                   (mint-harvested! ctx class-id body parent))))))))))

(defn- report-shape-coherence!
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
  (command-processor/process-command
    (assoc ctx :command
           {:command/name :ontology/report-shape-coherence
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :tree-class class-id
            :source-sheet-id source-sheet-id
            :source-tick-id source-tick-id
            :source-occurrence-event-id source-occurrence-event-id})))

(defprocessor :ontology on-tree-class-check-harvest
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
          (u/log ::shape-coherence-report-error :class-id class-id :error (.getMessage e))))
      (try
        (maybe-harvest! context class-id)
        (catch Exception e
          (u/log ::harvest-error :class-id class-id :error (.getMessage e)))))))
