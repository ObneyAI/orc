(ns ai.obney.orc.ontology.core.domain-penalty
  "EL-5 (ADR 0016, emergence loop): the deterministic CONTRASTIVE domain penalty.

   EL-2 made the LLM reranker READ each candidate's judge-grounded :avoid-when,
   but an orchestrator rate probe measured the refactor->rename-move-symbol
   force-fit persists 9/10 — the LLM ignores the domain veto even when shown it
   (single-scalar shape-override, NOT a recall gap). EL-5 makes the SAME
   :avoid-when evidence BITE DETERMINISTICALLY: after the LLM rerank, apply a
   graded penalty multiplier to each candidate's fitness.

   THE EQUATION (implement exactly):

     domain_penalty(candidate, task) =
       clamp( penalty_scale * max(0, cos_avoid - cos_good - margin),
              0, penalty_cap)
     final_fitness = llm_fitness * (1 - domain_penalty)

   CONTRASTIVE is the load-bearing idea: the penalty fires ONLY when the task is
   MORE like the candidate's :avoid-when than its positive use-case description.
   A naive cos(:avoid-when, task) is REJECTED — it would penalize a web-search
   behavior FOR a web-search task (topic overlap on 'web search', not the
   avoid-condition). The contrast asks 'is this task more the AVOID-condition
   than the USE-case?', not 'does the guard mention a topic in the task?'.

   ----------------------------------------------------------------------------
   AMENDMENT (ADR 0016, post-prototype): the SCORER is a PLUGGABLE injected
   capability; ColBERT is the default.
   ----------------------------------------------------------------------------
   The contrastive equation is unchanged, but the BACKEND that produces
   cos_avoid / cos_good is now config-selected:

     :colbert  (DEFAULT) — colbert/rerank in-memory MaxSim (late-interaction,
                NO index). One rerank call scores ALL guard strings against the
                ONE task query, so avoid + good are on the SAME scale: pass
                (concat avoid-strings positive-strings) once, split the results
                back BY CONTENT, take MAX over each group, NORMALIZE each via
                colbert/normalize-colbert-score -> [0,1]. ColBERT separated the
                refactor force-fit (avoid 20.7 - good 15.3 = +5.46) from the
                web-search zero-FP (7.1 - 10.4 = -3.29); all-MiniLM single-vector
                cosine did NOT (refactor contrast -0.112). So ColBERT is default.

     :embedding — embed + cosine (all-MiniLM today; the MODEL is a config value
                so a stronger embedder can be dropped in). Retained as the
                cheaper/offline + upgrade path (it's the original (b) code).

   The penalty arithmetic (domain-penalty, apply-penalty) and the
   penalize-candidates PASS (score -> multiply fitness -> re-sort) are unchanged
   and already pure-given-a-scorer: this is a STRATEGY SEAM, not a rewrite. The
   scorer is INJECTED — default to the real backend, fake it in tests.

   This namespace holds the PURE arithmetic, the two scorer adapters, and the
   penalize pass. The avoid/good SOURCE strings come from EL-2's enrichment.

   ----------------------------------------------------------------------------
   AMENDMENT (CC-16, ADR 0026 + ADR 0027): the POSITIVE signal narrows to
   `:good-when`, and BOTH readings are computed and reported on every pass.
   ----------------------------------------------------------------------------
   `positive-strings` used to be `(:content candidate)` — the whole indexed
   description — plus every `:good-when`. Measured on the real corpus with the
   real JVM ColBERT: both sides go into ONE rerank call and `batch-relative`
   divides by THAT CALL'S max, so a ~800-char consolidated summary that restates
   the behavior's own avoid-conditions won its own normalizer and cancelled its
   own guard. `cos-good` was 1.000000 in 153 of 154 cells; the penalty fired 0
   times. Narrowing to `:good-when` is RESTORING ADR 0016's intent — that ADR
   already says the avoid-condition must beat 'the use-case description'.

   TWO-STAGE SHIP. The narrowing lowers cos-good across the board, so the
   penalty CAN fire more often and may surface false positives an inert penalty
   was hiding. Shadow mode is free (cos-good is a MAX over already-scored
   strings), so every pass computes BOTH readings, stamps both, and applies the
   one named by `:positive-signal`. STAGE 1 (shipped) applies the pre-ADR-0026
   reading: zero behaviour change. STAGE 2 flips the default, gated on three
   watch conditions — see `default-penalty-config`.

   ADR 0027: `penalty-pass-report` / `::domain-penalty-pass` make the gate able
   to say whether it is doing anything — its firing rate and the contrast
   distribution it saw, per variant. Silence must be distinguishable from
   absence."
  (:require [clojure.string :as str]
            [ai.obney.orc.ontology.core.embedding :as embedding]
            [com.brunobonacci.mulog :as mu]))

;; =============================================================================
;; Knobs — CC-20 (ADR 0027): the gate FORM is derived before any value.
;;
;; The full history of the absolute calibration (ADR 0016's colbertv2 band
;; derivation, the JVM-ColBERT Slice-3 batch-relative amendment, the Slice-4b
;; 0.010 margin retune, and CC-17's before/after measurements) lives with
;; `legacy-absolute-defaults` below and in doc/adr/0027 + the CC-20 issue.
;; The short version, because it is the reason this section looks the way it
;; does: every absolute value fitted to this scale was measured either INERT
;; (margin 0.010: 0/154, then 0/559 firings on the shipped signal) or firing
;; on per-query SCALE rather than signal (6/559, all six on the one
;; longest-query task), and the same contrast moves 7x when the
;; maximum_query_tokens ceiling moves (CC-17). The shipped gate is therefore
;; population-relative (:z-score, see z-gate-penalty); the absolute arithmetic
;; survives ONLY behind an explicit :margin in the config (gate-config).
;; =============================================================================

(def default-penalty-config
  "The shipped penalty configuration (CC-20, ADR 0027 — form derived before
   value, both derived ONCE from the banked CC-16 cells:
   doc/build-timeline/evidence/cc16/cc16-cells-{shipped,32}.edn — 559 real
   cells x 2 positive-signal variants x 2 query-token limits, real JVM
   ColBERT, real production bodies).

     :scorer          — :colbert (DEFAULT) | :embedding. Selects the backend.
     :embedding-model — embedding model id when :scorer is :embedding (nil =>
                        the embedding component default).
     :penalty-cap     — caps the penalty so it stays GRADED, never a hard zero
                        (demoted, not annihilated — reversible). Shared by
                        both gate forms.
     :gate            — the CC-20 gate: {:form :z-score ...} (see
                        z-gate-penalty + gate-config). Derived values:
                          :z-threshold 1.5 — inside the measured separability
                            band at the shipped query limit: ABOVE every
                            ground-truth own-domain cell (max witnessed z
                            +1.236, rename-symbol x Rename-move-symbol) and
                            BELOW the refactor force-fit (+1.729 at limit 464,
                            +3.011 at limit 32 — the form survives the ceiling
                            knob that moved the raw contrast 7x). Also the
                            exact bound below which a population of n<=4
                            PROVABLY cannot fire (sample z <= (n-1)/sqrt(n)).
                          :z-scale 0.1 — the demotion contract: the witnessed
                            force-fit excess (1.729-1.5) becomes p=0.0229,
                            1.58x P-B's measured demotion bar (0.0145); the
                            most extreme witnessed cell (z +4.24) stays at
                            p=0.27, well under the cap.
                          :min-population 5 — no population, no z: a
                            single-candidate pass (the banked web-search
                            canary probe) abstains STRUCTURALLY.
                        HOW THE GATE BEHAVES AS N GROWS (the property that
                        motivated CC-20): the population is the pass's own
                        candidates, so corpus growth refines the mean/sd
                        estimate instead of walking a fixed cutoff off the
                        distribution (Lee 2210.13678 §5.1; Hawking & Robertson
                        2003 §5.4), and per-query scale differences divide out
                        (Rossi CIKM'24 — the old margin's 6/559 firings were
                        all on the one longest-query task's scale).
     :positive-signal — CC-16 / ADR 0026: which reading of the positive signal
                        is APPLIED to the fitness (both are ALWAYS computed
                        and stamped; the other is shadow). :good-when is the
                        ADR-0026 reading — the use-case description ADR 0016's
                        contrast actually names. STAGE 2 FLIPPED BY CC-20: the
                        three ADR-0026 watch conditions are met on the banked
                        cells under the z gate — firing rate SEEN (42/559 at
                        the shipped limit); the web-search zero-FP case
                        exactly 0 (the probe pass abstains structurally, the
                        real Research cell sits at z +0.76); the force-fit
                        DEMOTES (p=0.0229 > 0.0145). Under :content+good-when
                        the force-fit contrast is NEGATIVE (-0.0018, rank 8/43
                        — the summary cancels its own guard), so NO gate of
                        any form can demote it: the flip is a precondition of
                        the EL-5 acceptance contract, not a preference.
     :colbert-norm    — unchanged (JVM-ColBERT Slice 3, amended by CC-17):
                        {:max-score nil :method :batch-relative}; nil means
                        'the colbert backend's own derived ceiling'. Explicit
                        :linear / :sigmoid configs keep their exact old
                        behavior.

   The pre-CC-20 absolute knobs (:margin 0.010, :penalty-scale 10.0) are
   DELIBERATELY ABSENT — not dead config. See legacy-absolute-defaults +
   gate-config: a config that names a :margin explicitly selects the frozen
   absolute form with its historical semantics."
  {:scorer :colbert
   :embedding-model nil
   :penalty-cap 0.6
   :gate {:form :z-score
          :z-threshold 1.5
          :z-scale 0.1
          :min-population 5}
   :positive-signal :good-when
   :colbert-norm {:max-score nil :method :batch-relative}})

;; =============================================================================
;; The pure penalty arithmetic (DETERMINISTIC — unit-tested hard).
;;
;; CC-20 (ADR 0027): TWO gate forms now exist.
;;   :z-score  (DEFAULT) — population-relative: a candidate fires when its
;;             contrast is BOTH positive (avoid beats good at all — ADR 0016's
;;             theory anchor) AND an outlier of the pass's own contrast
;;             distribution (z above :z-threshold). Scale-free, so it survives
;;             the two drifts that made every absolute value here go inert or
;;             wrong: corpus growth (Lee 2210.13678 §5.1; Hawking & Robertson
;;             2003 §5.4) and the maximum_query_tokens ceiling move (CC-17
;;             measured the same contrast shifting 7x between limits 32/464).
;;             Rossi et al. (CIKM'24): similarity scores are not comparable
;;             across queries — and the old margin's 6/559 banked firings were
;;             indeed ALL on one long-query task's scale, not its signal.
;;   :absolute (EXPLICIT-CONFIG ONLY) — the pre-CC-20 margin arithmetic,
;;             selected by any config that names a :margin. Retained because
;;             it is a real contract (operator overrides; the calibrated-knob
;;             unit tests; the banked-measurement reproductions, which must be
;;             replayed under the arithmetic that produced them). It is NOT
;;             reachable from the shipped default config.
;; =============================================================================

(defn clamp
  "Clamp x to [lo, hi]."
  [x lo hi]
  (-> x (max lo) (min hi)))

(def legacy-absolute-defaults
  "The FROZEN pre-CC-20 absolute-gate calibration (Slice-4b margin retune +
   ADR 0016 scale). These are NOT the shipped defaults any more — CC-20
   (ADR 0027 decision 3) removed the absolute form from the default config
   because every value fitted to this scale was measured either inert (0/154,
   then 0/559 at margin 0.010 on the with-content signal) or firing on
   per-query scale rather than signal (6/559, all on the one longest-query
   task). They remain ONLY as the :or fallbacks of the explicit :absolute
   form, so an operator config or test that names a :margin without restating
   every knob keeps its exact historical meaning."
  {:penalty-scale 10.0
   :margin 0.010})

(defn contrast-population
  "CC-20: the population a z-gated pass judges against — {:n :mean :sd} over
   the pass's contrasts. Sample sd (n-1); degenerate populations (n<=1,
   all-equal) report :sd 0.0 rather than fabricating spread."
  [contrasts]
  (let [xs (mapv double contrasts)
        n (count xs)]
    (if (zero? n)
      {:n 0 :mean 0.0 :sd 0.0}
      (let [mean (/ (reduce + xs) (double n))
            sd (if (< n 2)
                 0.0
                 (Math/sqrt (/ (reduce + (map (fn [x] (let [d (- x mean)] (* d d))) xs))
                               (double (dec n)))))]
        {:n n :mean mean :sd sd}))))

(defn z-gate-penalty
  "CC-20: the :z-score gate for ONE candidate's contrast against the pass's
   population. Fires iff ALL of:
     - the population is estimable: n >= :min-population AND sd > 0
       (a single-candidate pass — e.g. the banked web-search canary probe —
       abstains STRUCTURALLY: a z against no population is not a z);
     - contrast > 0 — the ADR 0016 theory anchor: the penalty asserts 'the
       task is more the avoid-condition than the use-case', and a non-positive
       contrast means it is not, however extreme its z within the pass;
     - z = (contrast - mean) / sd  >  :z-threshold.
   Then penalty = clamp(z-scale * (z - z-threshold), 0, penalty-cap) —
   graded by HOW FAR outside the pass's own distribution the cell sits,
   capped so demotion never annihilates."
  [contrast {:keys [n mean sd] :as _population}
   {:keys [z-threshold z-scale min-population]
    :or {z-threshold 1.5 z-scale 0.1 min-population 5}}
   penalty-cap]
  (let [c (double contrast)]
    (if (or (< n (long min-population))
            (not (pos? (double sd)))
            (<= c 0.0))
      0.0
      (let [z (/ (- c (double mean)) (double sd))]
        (clamp (* (double z-scale) (max 0.0 (- z (double z-threshold))))
               0.0
               (double penalty-cap))))))

(defn gate-config
  "CC-20: resolve which gate FORM a config selects, plus that form's knobs.

   An explicit :margin ANYWHERE in the config selects the frozen :absolute
   form — an operator's calibrated pre-CC-20 override (or a test pinning the
   absolute arithmetic) keeps its exact historical meaning; missing absolute
   knobs fall back to legacy-absolute-defaults. Otherwise the config's :gate
   map, defaulted key-by-key from the shipped :z-score gate. The precedence
   matters for the production merge site (interface apply-rerank merges
   default-penalty-config UNDER the operator's :domain-penalty-config): the
   merged map always carries the default :gate, so :margin-present is the only
   honest signal that the operator asked for the absolute form."
  [config]
  (if (contains? config :margin)
    {:form :absolute
     :margin (double (:margin config))
     :penalty-scale (double (get config :penalty-scale
                                 (:penalty-scale legacy-absolute-defaults)))}
    (merge (:gate default-penalty-config) (:gate config))))

(defn domain-penalty
  "The CONTRASTIVE penalty for one candidate, given its two scores.

     cos-avoid — score(:avoid-when, task)  (MAX over the candidate's avoid guards)
     cos-good  — score(:good-when/:summary, task) (MAX over the positive signals)

   Returns a penalty in [0, penalty-cap]. Fires (> 0) ONLY when
   cos-avoid - cos-good > margin: the task is more the avoid-condition than the
   use-case, beyond scorer noise. good >= avoid (or within margin) => 0.

   CC-20: this is the frozen :absolute-form PRIMITIVE — a pure per-cell fn of
   two cosines, reachable only from an explicit-:margin config (gate-config).
   The shipped default gate is population-relative (z-gate-penalty) and does
   not route through here; missing absolute knobs fall back to the frozen
   legacy-absolute-defaults, never to the shipped config (which no longer
   carries them)."
  ([cos-avoid cos-good]
   (domain-penalty cos-avoid cos-good legacy-absolute-defaults))
  ([cos-avoid cos-good {:keys [penalty-scale margin penalty-cap]
                        :or {penalty-scale (:penalty-scale legacy-absolute-defaults)
                             margin (:margin legacy-absolute-defaults)
                             penalty-cap (:penalty-cap default-penalty-config)}}]
   (let [contrast (- (double cos-avoid) (double cos-good) (double margin))]
     (clamp (* (double penalty-scale) (max 0.0 contrast))
            0.0
            (double penalty-cap)))))

(defn apply-penalty
  "final_fitness = llm_fitness * (1 - penalty). nil fitness passes through nil
   (the :colbert-fallback path leaves fitness nil on purpose)."
  [fitness penalty]
  (when (some? fitness)
    (* (double fitness) (- 1.0 (double penalty)))))

;; =============================================================================
;; The candidate's signal SOURCE strings (reuses EL-2's enrichment). UNCHANGED.
;; =============================================================================

(defn avoid-strings
  "The candidate's NEGATIVE signals — the strings allowed to SUPPRESS it.

   CC-9a (ADR 0022, `invariant.OnlyValidatedClaimsEnforce`): when EL-2's
   enrichment stamped `::enforcing-avoid-when`, that vector IS the answer.
   The candidate is CLAIM-BACKED, and the stamp is the subset of its guards
   that reached `:validated` — an unproven claim stays in `:avoid-when` (the
   reranker still reads it) but is not here, so it cannot move the ranking.
   PRESENT-AND-EMPTY is meaningful: 'this target has claims and none of them
   has earned enforcement yet'.

   ABSENT means there is no claim set to gate on — a legacy wholesale-recorded
   body, or a candidate map built by hand — and the reading is unchanged from
   before CC-9a: the top-level :avoid-when vector unioned with any per-weakness
   :avoid-when guards. EL-2's enrichment already folds the per-weakness guards
   into the top-level vector, but we union defensively so the penalty reads
   every guard the body carries."
  [candidate]
  (if (contains? candidate ::enforcing-avoid-when)
    (vec (distinct (::enforcing-avoid-when candidate)))
    (vec (distinct (concat (:avoid-when candidate)
                           (keep :avoid-when (:weaknesses candidate)))))))

(defn positive-strings
  "The candidate's POSITIVE signal (ADR 0026): every `:good-when` from the
   enriched strengths, and NOTHING else. MAX over these is cos-good — the
   use-case description the avoid-condition must beat for the penalty to fire.

   `:content` — the whole indexed description — used to be in here, and removing
   it is framed as RESTORING ADR 0016's intent rather than changing it: that ADR
   says the avoid-condition must beat 'the use-case description', and
   `:good-when` IS the use-case description; `:content` is the whole document.

   MEASURED CAUSE (P-B / P-C: real 17-behavior corpus, real JVM-native ColBERT).
   The avoid guards and the positive signal go into ONE rerank call, and
   `batch-relative-scores` divides every score in that call by THAT CALL'S max —
   so whichever side scores highest is pinned at exactly 1.0. A ~800-char
   consolidated summary that restates the behavior's own avoid-conditions in
   prose therefore CANCELLED ITS OWN GUARD: `cos-good` was `1.000000` in 153 of
   154 cells and the penalty fired 0 times. A ~1,700-char document was competing
   against ~99-char guards (n=44, mean 99.2) for the same normalizer.

   Dropping `:content` does three things at once: the key stops competing with
   its own guard; the two sides become length-matched (`:good-when` ~=
   `:avoid-when` ~= 99 chars); and any future retrieval-key work is decoupled
   from EL-5 entirely."
  [candidate]
  (vec (distinct (keep :good-when (:strengths candidate)))))

(defn legacy-positive-strings
  "The PRE-ADR-0026 positive signal: the indexed description (`:content`) PLUS
   every `:good-when`. Retained ONLY so the penalty can compute and report BOTH
   readings on every pass — ADR 0027 requires a gate to be able to report
   whether it is doing anything, and silence must stay distinguishable from
   absence.

   This is a SUPERSET of `positive-strings`, which is why the shadow is FREE:
   cos-good is a MAX over already-scored strings, so one rerank call over this
   set yields both variants with zero additional round-trips."
  [candidate]
  (vec (distinct (concat (when (:content candidate) [(:content candidate)])
                         (keep :good-when (:strengths candidate))))))

(def positive-signal-variants
  "The two readings of the positive signal, by name:

     :good-when          — ADR 0026 (`positive-strings`)
     :content+good-when  — pre-ADR-0026 (`legacy-positive-strings`)

   BOTH are computed on every pass; `:positive-signal` in the config selects
   which one is APPLIED to the fitness. The other is stamped as shadow."
  #{:good-when :content+good-when})

(defn positive-strings-for
  "The positive signal under one named variant."
  [variant candidate]
  (case variant
    :good-when (positive-strings candidate)
    (legacy-positive-strings candidate)))

(defn scored-strings
  "Every string ONE rerank/embed call must cover for a candidate: its avoid
   guards plus the UNION of both positive-signal readings. Because the legacy
   reading is a superset of the ADR-0026 one, this is EXACTLY the document set
   the pre-CC-16 code already sent — computing the shadow costs zero extra calls
   AND zero extra documents."
  [candidate]
  (vec (distinct (concat (avoid-strings candidate)
                         (legacy-positive-strings candidate)))))

(defn applied-positive-signal
  "Which positive-signal variant this config APPLIES to the fitness. An
   unrecognised value falls back to the shipped default and is LOGGED — a
   typo'd operator config must never silently change which signal bites."
  [config]
  (let [v (get config :positive-signal (:positive-signal default-penalty-config))]
    (if (positive-signal-variants v)
      v
      (do (mu/log ::unknown-positive-signal
                  :positive-signal v
                  :falling-back-to (:positive-signal default-penalty-config))
          (:positive-signal default-penalty-config)))))

(defn- dual-cosines
  "Compute {:cos-avoid :cos-good} under BOTH positive-signal readings from the
   SAME already-scored strings, and alias the APPLIED reading onto the canonical
   :cos-avoid / :cos-good keys so the scorer contract is unchanged for callers
   that only read those two.

   `variant->cosines` is (fn [variant] -> {:cos-avoid :cos-good})."
  [variant->cosines applied]
  (let [with-content (variant->cosines :content+good-when)
        sans-content (variant->cosines :good-when)
        app (if (= :good-when applied) sans-content with-content)]
    {:cos-avoid (:cos-avoid app)
     :cos-good  (:cos-good app)
     :cos-avoid-with-content (:cos-avoid with-content)
     :cos-good-with-content  (:cos-good with-content)
     :cos-avoid-sans-content (:cos-avoid sans-content)
     :cos-good-sans-content  (:cos-good sans-content)
     :positive-signal applied}))

;; =============================================================================
;; ColBERT RESOLVER SEAM (JVM-ColBERT Slice 0 — the poly boundary fix).
;;
;; colbert is an OPTIONAL component: the shipped orc-ontology project
;; deliberately excludes it ('ontology works fully without colbert'). This
;; namespace therefore must NOT statically require the colbert interface — the
;; real :colbert backend resolves its two fns dynamically at call time (the
;; same requiring-resolve idiom as interface/colbert-fn), through an
;; INJECTABLE seam so tests can simulate absence without classpath surgery.
;; =============================================================================

(defn- resolve-colbert-fns
  "Lazily resolve the colbert interface fns the :colbert backend needs.
   Returns {:rerank <var> :normalize <var>} when the colbert component is on
   the classpath, nil otherwise. Resolving VARS (not fn values) preserves
   with-redefs / re-def semantics — a var call derefs at invocation time,
   exactly like the previous static `colbert/rerank` call."
  []
  (let [rerank    (try (requiring-resolve 'ai.obney.orc.colbert.interface/rerank)
                       (catch Throwable _ nil))
        normalize (try (requiring-resolve 'ai.obney.orc.colbert.interface/normalize-colbert-score)
                       (catch Throwable _ nil))]
    (when (and rerank normalize)
      {:rerank rerank :normalize normalize})))

(def ^:dynamic *colbert-resolver*
  "The INJECTABLE resolver seam (defaults to the real requiring-resolve impl).
   A fn of no args returning {:rerank <ifn> :normalize <ifn>} or nil when the
   colbert component is absent. Tests bind this to (constantly nil) to
   simulate 'colbert not on the classpath', or to a fake-returning resolver
   to drive the present path deterministically."
  resolve-colbert-fns)

(defn- colbert-unavailable-ex
  "The precise, loud-at-the-source error for 'colbert explicitly requested but
   not on the classpath'."
  []
  (ex-info
   (str "Domain-penalty scorer :colbert was requested, but the colbert component "
        "(ai.obney.orc.colbert.interface) is not on the classpath. The shipped "
        "orc-ontology project deliberately excludes colbert (and its Python-venv "
        "bridge) so ontology runs venv-free. Either add the orc-colbert package "
        "to your classpath, or configure {:scorer :embedding} (the embed+cosine "
        "backend) in :domain-penalty-config.")
   {:error :colbert-unavailable
    :missing-component 'ai.obney.orc.colbert.interface
    :requested-scorer :colbert
    :alternative {:scorer :embedding}}))

(defn- scorer-explicit?
  "Did the CALLER explicitly choose the scorer, vs inheriting the default?
   Drives the colbert-absent semantics: explicit :colbert => loud ex-info;
   default => graceful :embedding degradation with a warning.

   The public search path (ontology interface apply-rerank) MERGES
   default-penalty-config with the operator's ctx :domain-penalty-config
   BEFORE calling in, so the merged config ALWAYS carries :scorer — there the
   operator's PRE-merge map (still on ctx) is the honest explicitness signal.
   Direct callers may state it outright via a :scorer-explicit? entry in
   config, or implicitly by passing a config that names a :scorer and is not
   just the shipped default map (penalize-candidates' default 3-arity passes
   default-penalty-config itself, which stays the DEFAULT case)."
  [ctx config]
  (cond
    (contains? config :scorer-explicit?)
    (boolean (:scorer-explicit? config))

    (map? (:domain-penalty-config ctx))
    (contains? (:domain-penalty-config ctx) :scorer)

    :else
    (and (contains? config :scorer)
         (not= config default-penalty-config))))

(defn- colbert-backend-or-degrade
  "Availability gate for a :colbert backend selection. Semantics:
     resolvable                => (colbert-ctor) — EXACTLY today's behavior.
     absent + EXPLICIT config  => throw the precise colbert-unavailable ex-info.
     absent + DEFAULT config   => (embedding-ctor) — the existing :embedding
                                  backend — with a mulog warning naming the
                                  substitution (graceful degradation, matching
                                  every other ontology->colbert touchpoint)."
  [ctx config colbert-ctor embedding-ctor]
  (if (*colbert-resolver*)
    (colbert-ctor)
    (if (scorer-explicit? ctx config)
      (throw (colbert-unavailable-ex))
      (do (mu/log ::colbert-unavailable-fallback
                  :requested-scorer :colbert
                  :selected-scorer :embedding
                  :reason "colbert component not on the classpath; default config degrades to the :embedding backend")
          (embedding-ctor)))))

;; =============================================================================
;; SCORERS — the injected capability (ADR 0016 amendment + EL-5.1 batching).
;;
;; A PER-CANDIDATE scorer is a fn
;;   (scorer candidate task) -> {:cos-avoid <[0,1]> :cos-good <[0,1]>}
;; computing the MAX-over-guards contrast cosines for ONE candidate. Retained for
;; score-candidate's pure-given-a-scorer contract + the unit/proto seams.
;;
;; EL-5.1 (one bridge call per rerank, NOT N — the obney-ops-workshop discipline):
;; the HOT PATH (penalize-candidates) now uses a BATCH scorer factory
;;   (batch-scorer candidates task) -> (fn [candidate] {:cos-avoid :cos-good})
;; which gathers the DISTINCT set of all guard strings across ALL candidates,
;; makes ONE rerank/embed call, builds a single content->normalized-score map,
;; and serves every candidate's {:cos-avoid :cos-good} from that SHARED map. This
;; is RESULTS-NEUTRAL: a guard's MaxSim/cosine vs the (single shared) task query
;; is candidate-independent, so one map yields the IDENTICAL per-guard scores as
;; N separate calls — only the bridge-call count drops (M -> 1). Default to the
;; real backend; inject a fake in tests. The adapters:
;; =============================================================================

(defn- max-cos
  "MAX cosine of the task embedding against a collection of strings. Embeds each
   non-blank string via the embedding interface; returns 0.0 when there is
   nothing to compare (so a missing signal never fabricates a penalty — a
   candidate with no :avoid-when can't be penalized; a candidate with no
   positive signal has cos-good 0.0, the conservative side)."
  [embed-fn task-embedding strings]
  (let [vals (->> strings
                  (remove (fn [s] (or (nil? s) (str/blank? s))))
                  (keep (fn [s]
                          (when-let [e (embed-fn s)]
                            (embedding/cosine-similarity task-embedding e)))))]
    (if (seq vals) (apply max vals) 0.0)))

(defn embedding-scorer
  "The :embedding backend (original (b) path). embed+cosine, MAX over guards.
   The embedding MODEL is config-swappable via :embedding-model (nil => the
   embedding component default). Pure given embed-fn; embeds the task ONCE per
   candidate-batch by closing over a memo. Returns a scorer fn (candidate task).

   In production, embed-fn defaults to the real embedding interface; tests pass a
   deterministic fake embed-fn so no DJL model loads."
  ([config] (embedding-scorer config embedding/embed-text))
  ([{:keys [embedding-model] :as config} embed-fn]
   (let [embed (if embedding-model
                 (fn [s] (embed-fn s {:model-id embedding-model}))
                 embed-fn)
         ;; Memoize the task embedding so a batch of candidates embeds the task
         ;; once; guard strings vary per candidate so they aren't memoized.
         task->emb (memoize embed)
         applied (applied-positive-signal config)]
     (fn [candidate task]
       (let [task-emb (task->emb task)]
         (if (nil? task-emb)
           ;; Can't embed the task — no penalty source (fail open).
           {:cos-avoid 0.0 :cos-good 0.0}
           ;; CC-16: cos-avoid is variant-independent here (cosine is per-string,
           ;; not call-relative), but it is carried per variant anyway so the
           ;; stamped record has the same shape on both backends.
           (let [cos-avoid (max-cos embed task-emb (avoid-strings candidate))]
             (dual-cosines
              (fn [variant]
                {:cos-avoid cos-avoid
                 :cos-good (max-cos embed task-emb (positive-strings-for variant candidate))})
              applied))))))))

(defn colbert-rerank-scores
  "Adapter helper (PURE given rerank-fn): given a rerank-fn that maps
   {:query :documents} -> [{:content :score}], the candidate's avoid + positive
   strings, and the task, run ONE rerank call over (concat avoid good) so every
   guard is scored against the SAME query on the SAME scale, split the results
   back BY CONTENT, take MAX over each group, and NORMALIZE each via norm-fn ->
   [0,1]. Returns {:cos-avoid :cos-good}.

   No guards on a side => that side's score is 0.0 (the conservative side: a
   candidate with no :avoid-when can't be penalized). Deterministic given
   rerank-fn, so tests stub rerank-fn and assert split+max+normalize without
   touching the bridge."
  [rerank-fn norm-fn avoid good task]
  (let [avoid (vec (remove (fn [s] (or (nil? s) (str/blank? s))) avoid))
        good  (vec (remove (fn [s] (or (nil? s) (str/blank? s))) good))
        docs  (vec (distinct (concat avoid good)))]
    (if (empty? docs)
      {:cos-avoid 0.0 :cos-good 0.0}
      (let [res (rerank-fn {:query task :documents docs})
            ;; Split back by content; the same string can appear in both groups
            ;; only if the body lists it both ways — distinct above dedupes the
            ;; rerank docs, but we score each group against its OWN membership.
            by-content (into {} (map (juxt :content :score)) res)
            max-norm (fn [strings]
                       (let [scores (keep by-content strings)]
                         (if (seq scores)
                           (norm-fn (apply max scores))
                           0.0)))]
        {:cos-avoid (max-norm avoid)
         :cos-good  (max-norm good)}))))

(defn batch-relative-scores
  "The :batch-relative normalization (JVM-ColBERT Slice 3): given one rerank
   call's content->RAW-score map, divide every score by the call's MAX raw
   score (the normalize-result-scores idiom) so each guard expresses affinity
   RELATIVE to the strongest guard in the call. This is what restores the
   contrastive margin on the answerai checkpoint, whose MASK-expansion floor
   (~30/32) makes fixed-ceiling normalization collapse all cosines together.

   Bounds: empty map => {} (nothing fabricated); non-positive max =>
   normalizer 1.0 (raw pass-through — an all-zero call can never fabricate a
   contrast, and division by zero is impossible); all-equal positive scores =>
   every score 1.0 (contrast 0)."
  [content->raw]
  (if (empty? content->raw)
    {}
    (let [mx (apply max (vals content->raw))
          normalizer (if (pos? mx) (double mx) 1.0)]
      (into {} (map (fn [[content score]]
                      [content (/ (double score) normalizer)]))
            content->raw))))

(defn- relative-cosines
  "ONE variant's :batch-relative cosines: normalize the candidate's own
   (avoid ++ good) sub-map by ITS max raw score, then MAX per side.

   CC-16: the variant's own guard set is what defines the normalizer, so
   dropping `:content` from the positive side ALSO drops it from the divisor —
   which is the whole point. When `:content` was the call's max (P-C:
   `:winner-of-normalizer :POSITIVE`), removing it moves cos-avoid to 1.0 and
   un-pins cos-good from 1.0. Both variants are still served from the SAME raw
   scores, because MaxSim is per-document independent: this is arithmetic on
   already-scored strings, not a second rerank call."
  [raw-score-map avoid good]
  (let [sub (into {}
                  (keep (fn [s] (when-some [v (raw-score-map s)] [s v])))
                  (distinct (concat avoid good)))
        rel (batch-relative-scores sub)
        max-over (fn [strings]
                   (let [vs (keep rel strings)]
                     (if (seq vs) (apply max vs) 0.0)))]
    {:cos-avoid (max-over avoid)
     :cos-good  (max-over good)}))

(defn- candidate-relative-cosines-fn
  "Per-candidate :batch-relative lookup: given a SHARED content->RAW-score map
   (from one physical rerank call — per-candidate or batched across candidates;
   raw MaxSim is per-doc independent, so sharing is results-neutral), return
     (fn [candidate] -> {:cos-avoid :cos-good <+ both variants>})
   where each candidate's guards are normalized by the MAX raw score among
   THAT CANDIDATE'S OWN guards (batch-relative-scores over its sub-map), then
   maxed per side. Scoping the normalizer to the candidate keeps the batched
   hot path IDENTICAL to the per-candidate scorer (no cross-candidate
   contamination of the contrast) and matches 'normalize by the call's max' —
   the call being the candidate's own avoid+good rerank. A side with no scored
   guards is 0.0 (the conservative side, never fabricated)."
  [raw-score-map config]
  (let [applied (applied-positive-signal config)]
    (fn [candidate]
      (let [avoid (avoid-strings candidate)]
        (dual-cosines
         (fn [variant] (relative-cosines raw-score-map avoid
                                         (positive-strings-for variant candidate)))
         applied)))))

(defn colbert-scorer
  "The :colbert backend (DEFAULT). In-memory MaxSim via colbert/rerank — NO
   index. Returns a scorer fn (candidate task). Closes over ctx + the
   normalization opts. rerank-fn / norm-fn default to the real colbert interface;
   tests inject stubs.

   NB: one rerank call PER CANDIDATE (its own guard set), each scoring all of
   that candidate's guards against the task in a single bridge round-trip.

   The 2-arity resolves the colbert fns via *colbert-resolver* at construction
   time; it IS an explicit colbert request, so it throws the precise
   colbert-unavailable ex-info when the component is absent (make-scorer /
   make-batch-scorer own the default-config graceful degradation)."
  ([ctx config]
   (let [{:keys [rerank normalize]} (or (*colbert-resolver*)
                                        (throw (colbert-unavailable-ex)))]
     (colbert-scorer ctx config
                     (fn [opts] (rerank ctx opts))
                     normalize)))
  ([_ctx {:keys [colbert-norm] :as config} rerank-fn norm-fn]
   (let [{:keys [max-score method]
          :or {max-score (:max-score (:colbert-norm default-penalty-config))
               method (:method (:colbert-norm default-penalty-config))}} colbert-norm
         applied (applied-positive-signal config)]
     (if (= :batch-relative method)
       ;; :batch-relative (the DEFAULT): ONE rerank over the candidate's own
       ;; avoid+good guards, normalized by that call's max raw score. norm-fn
       ;; (the fixed-ceiling normalizer) is deliberately unused here.
       ;;
       ;; CC-16: the document set is `scored-strings` — the union of both
       ;; positive-signal readings — which is EXACTLY the set this call already
       ;; sent, so the shadow adds neither a round-trip nor a document.
       (fn [candidate task]
         (let [docs (vec (remove (fn [s] (or (nil? s) (str/blank? s)))
                                 (scored-strings candidate)))]
           (if (empty? docs)
             {:cos-avoid 0.0 :cos-good 0.0}
             (let [res (rerank-fn {:query task :documents docs})
                   raw (into {} (map (juxt :content :score)) res)]
               ((candidate-relative-cosines-fn raw config) candidate)))))
       ;; Explicit :linear / :sigmoid — the exact pre-Slice-3 behavior, with the
       ;; same shadow bolted on: fixed-ceiling normalization is per-score, so the
       ;; two variants differ only in which strings the good-side MAX ranges over.
       (let [norm (fn [score] (norm-fn score :max-score max-score :method method))]
         (fn [candidate task]
           (let [avoid (vec (remove (fn [s] (or (nil? s) (str/blank? s)))
                                    (avoid-strings candidate)))
                 docs  (vec (remove (fn [s] (or (nil? s) (str/blank? s)))
                                    (scored-strings candidate)))]
             (if (empty? docs)
               {:cos-avoid 0.0 :cos-good 0.0}
               (let [res (rerank-fn {:query task :documents docs})
                     by-content (into {} (map (juxt :content :score)) res)
                     max-norm (fn [strings]
                                (let [scores (keep by-content strings)]
                                  (if (seq scores) (norm (apply max scores)) 0.0)))
                     cos-avoid (max-norm avoid)]
                 (dual-cosines
                  (fn [variant]
                    {:cos-avoid cos-avoid
                     :cos-good (max-norm (remove (fn [s] (or (nil? s) (str/blank? s)))
                                                 (positive-strings-for variant candidate)))})
                  applied))))))))))

(defn make-scorer
  "Select + construct the PER-CANDIDATE scorer from config (ADR 0016 amendment):
   :colbert (DEFAULT) or :embedding. Default real backend; tests bypass this and
   inject a fake scorer directly into score-candidate / penalize-candidates.

   NB: this is the N-call (one bridge round-trip per candidate) seam, retained for
   score-candidate's pure contract + the proto. The HOT PATH uses make-batch-scorer
   (EL-5.1 — one call for the whole batch)."
  [ctx {:keys [scorer] :or {scorer (:scorer default-penalty-config)} :as config}]
  (case scorer
    :embedding (embedding-scorer config)
    :colbert   (colbert-backend-or-degrade ctx config
                                           #(colbert-scorer ctx config)
                                           #(embedding-scorer config))
    ;; Unknown scorer keyword — fall back to the default backend, but log it so a
    ;; typo'd operator config surfaces (never silently mis-score).
    (do (mu/log ::unknown-scorer :scorer scorer :falling-back-to :colbert)
        (colbert-backend-or-degrade ctx config
                                    #(colbert-scorer ctx config)
                                    #(embedding-scorer config)))))

;; =============================================================================
;; BATCH SCORERS (EL-5.1) — one bridge/embed call for the WHOLE candidate set.
;;
;; A batch scorer is a FACTORY:
;;   (batch-scorer candidates task) -> (fn [candidate] {:cos-avoid :cos-good})
;; It eagerly gathers the DISTINCT guard strings across ALL candidates, makes the
;; SINGLE backend call up front, builds a content->normalized-score map, and
;; returns a PURE per-candidate lookup that maxes over each candidate's own guards
;; from that shared map. The returned lookup never touches the bridge.
;; =============================================================================

(defn- distinct-guards
  "All DISTINCT non-blank guard strings across the candidate set, split into the
   :avoid and :good universes (deduped within each), and the combined distinct
   document set for ONE rerank/embed call.

   CC-16: the :good universe is the LEGACY (superset) reading, so ONE call still
   scores both positive-signal variants — the document set is byte-identical to
   the pre-CC-16 one and the shadow costs nothing."
  [candidates]
  (let [non-blank (fn [ss] (remove (fn [s] (or (nil? s) (str/blank? s))) ss))
        avoid (vec (distinct (non-blank (mapcat avoid-strings candidates))))
        good  (vec (distinct (non-blank (mapcat legacy-positive-strings candidates))))
        docs  (vec (distinct (concat avoid good)))]
    {:avoid avoid :good good :docs docs}))

(defn- candidate-cosines-fn
  "Given a content->normalized-score map (the SHARED scores from the single
   batched call), return a pure per-candidate lookup
     (fn [candidate] -> {:cos-avoid :cos-good <+ both variants>})
   that maxes each candidate's avoid-strings / positive strings over the map.
   A guard absent from the map (e.g. blank, never scored) contributes nothing; a
   side with no scored guards is 0.0 (never fabricated — the conservative side).

   Fixed-ceiling normalization is per-score, so the two positive-signal variants
   differ only in which strings the good-side MAX ranges over."
  [score-map config]
  (let [applied (applied-positive-signal config)
        max-over (fn [strings]
                   (let [vs (keep score-map strings)]
                     (if (seq vs) (apply max vs) 0.0)))]
    (fn [candidate]
      (let [cos-avoid (max-over (avoid-strings candidate))]
        (dual-cosines
         (fn [variant] {:cos-avoid cos-avoid
                        :cos-good (max-over (positive-strings-for variant candidate))})
         applied)))))

(defn batch-colbert-scorer
  "The :colbert BATCH backend (DEFAULT, EL-5.1). Returns a factory
     (fn [candidates task] -> (fn [candidate] {:cos-avoid :cos-good}))
   that makes EXACTLY ONE colbert/rerank call over the DISTINCT guard set across
   ALL candidates (one shared task query), normalizes each score, and serves every
   candidate from the shared map. rerank-fn / norm-fn default to the real colbert
   interface; tests inject a stubbed rerank-fn (with a call counter — the headline
   guardrail).

   RESULTS-NEUTRAL: MaxSim(query, doc) is per-doc independent of the other docs in
   the call, and all candidates share the same task query, so the shared map gives
   the IDENTICAL per-guard scores as the N-call colbert-scorer.

   The 2-arity resolves the colbert fns via *colbert-resolver* at construction
   time; it IS an explicit colbert request, so it throws the precise
   colbert-unavailable ex-info when the component is absent (make-scorer /
   make-batch-scorer own the default-config graceful degradation)."
  ([ctx config]
   (let [{:keys [rerank normalize]} (or (*colbert-resolver*)
                                        (throw (colbert-unavailable-ex)))]
     (batch-colbert-scorer ctx config
                           (fn [opts] (rerank ctx opts))
                           normalize)))
  ([_ctx {:keys [colbert-norm] :as config} rerank-fn norm-fn]
   (let [{:keys [max-score method]
          :or {max-score (:max-score (:colbert-norm default-penalty-config))
               method (:method (:colbert-norm default-penalty-config))}} colbert-norm]
     (fn [candidates task]
       (let [{:keys [docs]} (distinct-guards candidates)]
         (if (empty? docs)
           ;; No guards anywhere => no round-trip; every candidate {0,0}.
           (fn [_candidate] {:cos-avoid 0.0 :cos-good 0.0})
           (let [res (rerank-fn {:query task :documents docs})]
             (if (= :batch-relative method)
               ;; :batch-relative (the DEFAULT): share the RAW per-guard scores
               ;; from the single call (raw MaxSim is per-doc independent), but
               ;; normalize each candidate by ITS OWN guard max — identical
               ;; cosines to the per-candidate colbert-scorer (results-neutral
               ;; preserved), no cross-candidate contamination.
               (candidate-relative-cosines-fn
                (into {} (map (juxt :content :score)) res)
                config)
               ;; Explicit :linear / :sigmoid — the exact pre-Slice-3 behavior.
               (let [norm (fn [score] (norm-fn score :max-score max-score :method method))
                     score-map (into {} (map (juxt :content (comp norm :score))) res)]
                 (candidate-cosines-fn score-map config))))))))))

(defn batch-embedding-scorer
  "The :embedding BATCH backend (EL-5.1). Returns a factory
     (fn [candidates task] -> (fn [candidate] {:cos-avoid :cos-good}))
   that embeds the task ONCE and the DISTINCT guard set ONCE (across ALL
   candidates), builds a content->cosine map, and serves every candidate from it.
   The embedding MODEL is config-swappable via :embedding-model. Pure given
   embed-fn; tests pass a deterministic fake embed-fn so no DJL model loads.

   Same RESULTS-NEUTRAL property as the N-call embedding-scorer: cosine(task,
   guard) is independent of the other guards, so the shared map matches."
  ([config] (batch-embedding-scorer config embedding/embed-text))
  ([{:keys [embedding-model] :as config} embed-fn]
   (let [embed (if embedding-model
                 (fn [s] (embed-fn s {:model-id embedding-model}))
                 embed-fn)]
     (fn [candidates task]
       (let [task-emb (embed task)]
         (if (nil? task-emb)
           ;; Can't embed the task — no penalty source (fail open) for everyone.
           (fn [_candidate] {:cos-avoid 0.0 :cos-good 0.0})
           (let [{:keys [docs]} (distinct-guards candidates)
                 score-map (into {}
                                 (keep (fn [s]
                                         (when-let [e (embed s)]
                                           [s (embedding/cosine-similarity task-emb e)])))
                                 docs)]
             (candidate-cosines-fn score-map config))))))))

(defn make-batch-scorer
  "Select + construct the BATCH scorer factory from config (EL-5.1): :colbert
   (DEFAULT) or :embedding. Returns (fn [candidates task] -> per-candidate-fn).
   Default real backend; tests bypass this and inject a fake batch factory (or use
   the deterministic stub rerank-fn / embed-fn). Mirrors make-scorer's selection."
  [ctx {:keys [scorer] :or {scorer (:scorer default-penalty-config)} :as config}]
  (case scorer
    :embedding (batch-embedding-scorer config)
    :colbert   (colbert-backend-or-degrade ctx config
                                           #(batch-colbert-scorer ctx config)
                                           #(batch-embedding-scorer config))
    (do (mu/log ::unknown-scorer :scorer scorer :falling-back-to :colbert)
        (colbert-backend-or-degrade ctx config
                                    #(batch-colbert-scorer ctx config)
                                    #(batch-embedding-scorer config)))))

;; =============================================================================
;; score-candidate / penalize-candidates — the PASS. Now scorer-driven.
;; =============================================================================

(defn- variant-record
  "The {cos-avoid, cos-good, penalty} record for ONE shadowed positive-signal
   variant, or nil when the scorer did not report that variant. An INJECTED FAKE
   scorer returning only {:cos-avoid :cos-good} therefore stays valid — the
   shadow keys are simply absent, never fabricated."
  [cosines a-key g-key p-key config]
  (when (and (some? (get cosines a-key)) (some? (get cosines g-key)))
    (let [a (double (get cosines a-key))
          g (double (get cosines g-key))]
      {a-key a g-key g p-key (domain-penalty a g config)})))

(defn- contrast-record
  "Normalize a scorer's return into the full contrast+penalty record: the
   APPLIED :cos-avoid / :cos-good / :penalty, plus whichever positive-signal
   variants the scorer reported (CC-16 shadow mode, ADR 0027)."
  [cosines config]
  (let [cos-avoid (double (or (:cos-avoid cosines) 0.0))
        cos-good  (double (or (:cos-good cosines) 0.0))]
    (merge {:cos-avoid cos-avoid
            :cos-good cos-good
            :penalty (domain-penalty cos-avoid cos-good config)}
           (when-let [ps (:positive-signal cosines)] {:positive-signal ps})
           (variant-record cosines :cos-avoid-with-content :cos-good-with-content
                           :domain-penalty-with-content config)
           (variant-record cosines :cos-avoid-sans-content :cos-good-sans-content
                           :domain-penalty-sans-content config))))

(defn- z-contrast-records
  "CC-20 :z-score stamping for a WHOLE PASS: every key-pair's penalties are
   judged against that pair's contrast population across the pass. The applied
   pair's population is all candidates (missing cosines read 0.0, exactly as
   contrast-record always did); a shadow variant's population is the
   candidates that reported that variant (absent stays absent — never
   fabricated). When every candidate reports both variants — every production
   backend does — the applied record's penalty is identical to its aliased
   variant's, because they are the same contrasts against the same population."
  [cosines-seq gate cap]
  (let [applied-contrast (fn [cos] (- (double (or (:cos-avoid cos) 0.0))
                                      (double (or (:cos-good cos) 0.0))))
        applied-pop (contrast-population (map applied-contrast cosines-seq))
        pair-pop (fn [a-key g-key]
                   (contrast-population
                    (keep (fn [cos]
                            (when (and (some? (get cos a-key)) (some? (get cos g-key)))
                              (- (double (get cos a-key)) (double (get cos g-key)))))
                          cosines-seq)))
        with-pop (pair-pop :cos-avoid-with-content :cos-good-with-content)
        sans-pop (pair-pop :cos-avoid-sans-content :cos-good-sans-content)
        vrec (fn [cos a-key g-key p-key pop]
               (when (and (some? (get cos a-key)) (some? (get cos g-key)))
                 (let [a (double (get cos a-key))
                       g (double (get cos g-key))]
                   {a-key a
                    g-key g
                    p-key (z-gate-penalty (- a g) pop gate cap)})))]
    (mapv (fn [cos]
            (merge {:cos-avoid (double (or (:cos-avoid cos) 0.0))
                    :cos-good  (double (or (:cos-good cos) 0.0))
                    :penalty (z-gate-penalty (applied-contrast cos) applied-pop gate cap)}
                   (when-let [ps (:positive-signal cos)] {:positive-signal ps})
                   (vrec cos :cos-avoid-with-content :cos-good-with-content
                         :domain-penalty-with-content with-pop)
                   (vrec cos :cos-avoid-sans-content :cos-good-sans-content
                         :domain-penalty-sans-content sans-pop)))
          cosines-seq)))

(defn- contrast-records
  "The contrast+penalty records for EVERY cosines map in one pass, gate-form
   aware (CC-20): :absolute => the pre-CC-20 per-cell arithmetic, byte for
   byte; :z-score (the default) => population-relative via z-contrast-records."
  [cosines-seq config]
  (let [g (gate-config config)]
    (if (= :absolute (:form g))
      (mapv #(contrast-record % config) cosines-seq)
      (z-contrast-records cosines-seq g
                          (double (get config :penalty-cap
                                       (:penalty-cap default-penalty-config)))))))

(defn- stamp-all
  "Stamp every candidate with its contrast record + :domain-penalty and its
   penalized :fitness-score (the fitness is penalized by the APPLIED variant
   ONLY; the shadow is observability, never behaviour)."
  [candidates cosines-seq config]
  (mapv (fn [c record]
          (let [penalty (:penalty record)]
            (-> c
                (merge (dissoc record :penalty))
                (assoc :domain-penalty penalty)
                (assoc :fitness-score (apply-penalty (:fitness-score c) penalty)))))
        candidates
        (contrast-records cosines-seq config)))

(defn- scorer-cosines
  "Call the injected per-candidate scorer, FAILING OPEN to {0,0} on any throw."
  [candidate task scorer]
  (try
    (scorer candidate task)
    (catch Throwable t
      (mu/log ::scorer-failed
              :document-id (:document-id candidate)
              :error (.getMessage t))
      {:cos-avoid 0.0 :cos-good 0.0})))

(defn score-candidate
  "Compute {:cos-avoid :cos-good :penalty} for one enriched candidate against the
   task, using the injected SCORER. The scorer ((scorer candidate task) ->
   {:cos-avoid :cos-good}) is the seam: :colbert / :embedding in production, a
   deterministic fake in tests. Pure given the scorer + config.

   CC-16: when the scorer also reports the two positive-signal variants (every
   production backend does), the record additionally carries
   :cos-avoid/:cos-good/:domain-penalty -with-content and -sans-content. Those
   keys are ADDITIVE — a fake scorer that returns only the two canonical keys is
   still a valid scorer and simply produces no shadow.

   FAIL OPEN: if the scorer throws (e.g. the ColBERT bridge is unavailable), the
   candidate scores {0,0} -> penalty 0 (the LLM ordering is left untouched, never
   a FABRICATED penalty). The penalty layer is BEST-EFFORT/additive — a scoring
   outage must degrade retrieval gracefully, exactly like the reranker's own
   try/catch fallback, not crash it.

   CC-20: under the default :z-score gate a SINGLE candidate is a population
   of one, so this fn's penalties are 0 by construction (no population, no z)
   — the pass-level penalize-candidates is where the shipped gate bites. An
   explicit-:margin config keeps the pre-CC-20 per-cell behavior exactly."
  [candidate task scorer config]
  (first (contrast-records [(scorer-cosines candidate task scorer)] config)))

;; =============================================================================
;; ADR 0027 — the gate must be able to REPORT whether it is doing anything.
;;
;; "Silence must be distinguishable from absence." Every gate in the
;; promotion-and-ranking path that was found broken or inert survived multiple
;; reviews precisely because nothing could report the difference between a gate
;; that never fires and a gate that is not there. This penalty already computes
;; both positive-signal variants for free, so the firing rate and the contrast
;; distribution it saw are recorded rather than discarded.
;;
;; CC-20 derives the penalty's FORM and VALUE from this distribution.
;; =============================================================================

(defn- percentile
  "Nearest-rank percentile over an already-sorted vector."
  [sorted p]
  (let [n (count sorted)]
    (nth sorted (min (dec n) (max 0 (int (Math/ceil (- (* (/ p 100.0) n) 1))))))))

(defn- distribution
  "min / p25 / p50 / p75 / p95 / max / mean over a collection of numbers, or nil
   when there is nothing to describe (never a fabricated zero)."
  [xs]
  (when (seq xs)
    (let [v (vec (sort (map double xs)))]
      {:n (count v)
       :min (first v)
       :p25 (percentile v 25)
       :p50 (percentile v 50)
       :p75 (percentile v 75)
       :p95 (percentile v 95)
       :max (peek v)
       :mean (/ (reduce + v) (double (count v)))})))

(defn- variant-summary
  "One positive-signal variant's report over a stamped candidate set."
  [stamped a-key g-key p-key]
  (let [rows (keep (fn [c]
                     (when (and (some? (get c a-key)) (some? (get c g-key)))
                       {:contrast (- (double (get c a-key)) (double (get c g-key)))
                        :penalty (double (or (get c p-key) 0.0))}))
                   stamped)]
    (when (seq rows)
      (let [fired (count (filter #(pos? (:penalty %)) rows))]
        {:n (count rows)
         :fired fired
         :fired-rate (/ (double fired) (count rows))
         :contrast (distribution (map :contrast rows))
         :penalty (distribution (map :penalty rows))}))))

(defn penalty-pass-report
  "ADR 0027: what the domain penalty DID on this pass — its firing rate and the
   contrast distribution it saw, per positive-signal variant, alongside the knobs
   it judged against. PURE: takes the stamped candidates, returns the report.

   `:variants` is keyed by `positive-signal-variants`; a variant the scorer did
   not report is ABSENT rather than zero, so 'the shadow was not computed' can
   never be read as 'the shadow never fired'."
  [stamped config]
  (let [g (gate-config config)
        base {:candidate-count (count stamped)
              :applied-positive-signal (applied-positive-signal config)
              :gate-form (:form g)
              :penalty-cap (double (get config :penalty-cap (:penalty-cap default-penalty-config)))
              :variants (into {}
                              (remove (comp nil? val))
                              {:content+good-when
                               (variant-summary stamped :cos-avoid-with-content
                                                :cos-good-with-content :domain-penalty-with-content)
                               :good-when
                               (variant-summary stamped :cos-avoid-sans-content
                                                :cos-good-sans-content :domain-penalty-sans-content)})}]
    (if (= :absolute (:form g))
      (assoc base
             :margin (:margin g)
             :penalty-scale (:penalty-scale g))
      (assoc base
             :z-threshold (double (:z-threshold g))
             :z-scale (double (:z-scale g))
             :min-population (long (:min-population g))
             ;; The population the APPLIED gate judged against (CC-20: the
             ;; pass must be able to report not just its firing rate but the
             ;; distribution its z was computed on).
             :population (contrast-population
                          (map (fn [c] (- (double (or (:cos-avoid c) 0.0))
                                          (double (or (:cos-good c) 0.0))))
                               stamped))))))

(defn- log-penalty-pass!
  "Emit the ADR-0027 pass report. The headline firing counts are FLAT so they
   are greppable in a log line without parsing the nested distributions."
  [stamped config]
  (let [report (penalty-pass-report stamped config)]
    (mu/log ::domain-penalty-pass
            :candidate-count (:candidate-count report)
            :applied-positive-signal (:applied-positive-signal report)
            :gate-form (:gate-form report)
            :fired-with-content (get-in report [:variants :content+good-when :fired])
            :fired-sans-content (get-in report [:variants :good-when :fired])
            :report report)))

(defn penalize-candidates
  "EL-5 penalty PASS (EL-5.1: ONE bridge call for the whole batch, not N): given
   enriched candidates (each carrying :fitness-score + :avoid-when/:content/
   :strengths from EL-2) and the task string, compute the contrastive domain
   penalty per candidate, multiply it into :fitness-score, stamp :domain-penalty +
   :cos-avoid + :cos-good for observability, and RE-SORT by the new fitness
   (descending). Candidates without a usable fitness (:colbert-fallback, nil) keep
   nil fitness and sort last. Output map shape is otherwise unchanged (the contract
   {:document-id :reasoning :fitness-score} is preserved; the extra keys are
   additive observability).

   EL-5.1 SCORING: the SELECTED backend's BATCH scorer factory (make-batch-scorer)
   is invoked ONCE per pass — it makes EXACTLY ONE colbert/rerank (or one
   embed-task + one embed-distinct-guards) call over the DISTINCT guard set across
   ALL candidates and returns a pure per-candidate lookup. This is RESULTS-NEUTRAL
   vs the old per-candidate (N-call) path (per-doc MaxSim/cosine is set-independent
   under the shared task query) and collapses M bridge round-trips into 1.

   FAIL OPEN: if the single batched call throws (e.g. the ColBERT bridge is
   unavailable), EVERY candidate scores {0,0} -> penalty 0, the LLM ordering is
   left UNTOUCHED (never a fabricated penalty), matching the reranker's own
   try/catch fallback.

   Pure given the backend — the 5-arity injects a PER-CANDIDATE scorer
   ((scorer candidate task) -> {:cos-avoid :cos-good}) for full determinism (no
   ColBERT/LLM/DJL); used by the unit re-sort/fail-open tests + the proto. The
   4-arity is the production hot path (batch)."
  ([ctx candidates task]
   (penalize-candidates ctx candidates task default-penalty-config))
  ([ctx candidates task config]
   ;; PRODUCTION HOT PATH (EL-5.1): one batched backend call for all candidates.
   ;; FAIL OPEN around the SINGLE call: a backend outage => every candidate {0,0}.
   (let [per-candidate-cosines
         (try
           (let [batch-factory (make-batch-scorer ctx config)
                 lookup (batch-factory candidates task)]
             ;; Force the lookup per candidate now (still inside the try) so a
             ;; lazily-deferred backend error is caught here, not downstream.
             (mapv (fn [c] (or (lookup c) {:cos-avoid 0.0 :cos-good 0.0})) candidates))
           (catch Throwable t
             (mu/log ::batch-scorer-failed :error (.getMessage t)
                     :candidate-count (count candidates))
             (mapv (constantly {:cos-avoid 0.0 :cos-good 0.0}) candidates)))
         stamped (stamp-all candidates per-candidate-cosines config)]
     (log-penalty-pass! stamped config)
     (vec (sort-by (fn [c] (or (:fitness-score c) -1.0)) > stamped))))
  ([_ctx candidates task config scorer]
   ;; PER-CANDIDATE injected-scorer seam (backward-compatible determinism path):
   ;; scorer-cosines already fails open per-candidate around the scorer call.
   ;; CC-20: cosines are gathered FIRST so the :z-score gate judges each
   ;; candidate against the whole pass's contrast population.
   (let [cosines (mapv (fn [c] (scorer-cosines c task scorer)) candidates)
         stamped (stamp-all candidates cosines config)]
     (log-penalty-pass! stamped config)
     (vec (sort-by (fn [c] (or (:fitness-score c) -1.0)) > stamped)))))
