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
;; Knobs (ADR 0016) — all tunable, started CONSERVATIVE.
;;
;; The knobs are scorer-relative: cos_avoid / cos_good are always in [0,1]
;; (cosine for :embedding; colbert/normalize-colbert-score for :colbert), so
;; one (margin, scale, cap) set is scale-stable across backends.
;;
;; The DEFAULT set is RECALIBRATED for the NORMALIZED ColBERT scale against the
;; REAL enriched candidate signals (NOT the hand-written separability probe
;; strings). Calibration measured by development/bench/el5_domain_penalty_proto
;; on the live refactor case (real grain + real ColBERT MaxSim, /40 linear norm):
;;
;;   child/rename-move-symbol (force-fit) cos_avoid 0.518 - cos_good 0.451 = +0.068  <- MUST fire
;;   Validation                            0.345 - 0.382 = -0.038                     <- clean
;;   Research                              0.335 - 0.380 = -0.045                     <- clean
;;   Critique                              0.322 - 0.356 = -0.034                     <- clean
;;   Code-building (correct parent)        0.269 - 0.357 = -0.088                     <- clean
;;
;; So the live separability band is (-0.034, +0.068): every clean case has a
;; NEGATIVE contrast and only the rename force-fit is positive. (The contrast is
;; tighter than the probe's +0.137 because the candidate's POSITIVE strings
;; include the verbose :summary, which itself carries 'refactor'/'extract'
;; tokens that lift cos_good — exactly the content-quality dependency ADR 0016
;; flags; C-3 sharpening the guard widens this over runs.)
;;
;;   :margin 0.03  — sits inside the band: ABOVE every clean case (all <= -0.034,
;;                   so case (3) web-search-on-own-domain + case (2) deepening
;;                   stay penalty 0 — the zero-FP guard) and BELOW the rename
;;                   force-fit +0.068 (so case (1) fires). Conservative: a clean
;;                   case would have to flip from -0.034 to >+0.03 (a 0.064 swing)
;;                   before a false positive — far beyond ColBERT noise here.
;;   :penalty-scale 10.0 — turns rename's net contrast (0.068 - 0.03 = 0.038)
;;                   into a ~0.38 penalty: enough to flip the LLM's shape-favored
;;                   fitness below the correct candidate AND, at threshold 0.6,
;;                   push a borderline force-fit under the gate. Clean cases stay
;;                   at penalty 0 regardless of scale (their contrast is < margin).
;;   :penalty-cap 0.6 — graded, never a hard zero (demoted, not annihilated).
;;
;; JVM-ColBERT Slice 3 amendment: the calibration above was measured on the
;; colbertv2 bridge with /40 linear normalization. The pure-JVM answerai
;; checkpoint's MASK-expansion floor compresses fixed-ceiling cosines
;; (~0.70-0.73, margins ~0.01-0.03), so the DEFAULT :colbert-norm is now
;; {:max-score 32.0 :method :batch-relative} (see default-penalty-config and
;; batch-relative-scores). :batch-relative widens every witnessed margin
;; beyond both /40 and /32 linear and preserves the clean/force-fit
;; separability ORDER (clean cases <= +0.0025, force-fit +0.0160 on the probe
;; sets), but the ABSOLUTE margins sit below the 0.03 :margin knob calibrated
;; for colbertv2 — the knobs themselves are deliberately NOT retuned here;
;; the el5 separability re-run (Slice 4) judges end behavior and owns any
;; recalibration.
;; =============================================================================

(def default-penalty-config
  "CONSERVATIVE defaults (ADR 0016 — recalibrated for the NORMALIZED ColBERT
   scale, the default backend; see the four-case calibration in the proto):

     :scorer          — :colbert (DEFAULT) | :embedding. Selects the backend.
     :embedding-model — embedding model id when :scorer is :embedding (the model
                        is swappable; nil => the embedding component default,
                        all-MiniLM-L6-v2 today).
     :penalty-scale   — multiplier on the (avoid - good - margin) contrast.
     :margin          — embedding-noise floor: avoid must beat good by MORE than
                        this before any penalty fires (the zero-false-positive
                        guard for case (3) web-search-on-own-domain + case (2)
                        deepening-on-own-task).
     :penalty-cap     — caps the penalty so it stays GRADED, never a hard zero
                        (the candidate is demoted, not annihilated — reversible).
     :colbert-norm    — normalization opts ({:max-score :method}) for the
                        :colbert scorer, applied to both avoid + good so
                        margin/cap are scale-stable. DEFAULT (JVM-ColBERT
                        Slice 3, amended by CC-17): {:max-score nil :method
                        :batch-relative}. :max-score nil means 'the colbert
                        backend's own derived ceiling' — the MaxSim bound IS
                        maximum_query_tokens, which CC-17 turned into
                        configuration, so a literal 32.0 pinned here would go
                        stale the moment an operator retunes the limit (and
                        does go stale against the shipped 464: every real score
                        would clamp to 1.0). :batch-relative normalizes each
                        guard by
                        the MAX raw score within the candidate's own rerank
                        call (batch-relative-scores below) instead of the
                        fixed ceiling, because the checkpoint's ~30/32
                        MASK-expansion floor compresses fixed-ceiling cosines
                        into ~0.75-0.98 and collapses the contrastive margin
                        (0.011 witnessed vs colbertv2's ~0.18). Explicit
                        :linear / :sigmoid configs keep their exact old
                        behavior (norm-fn per score against :max-score)."
  {:scorer :colbert
   :embedding-model nil
   :penalty-scale 10.0
   ;; 0.010 — gate-evidenced retune for the answerai checkpoint's batch-relative
   ;; scale (Slice 4b, user-approved at the Slice-4 gate). The colbertv2-era
   ;; 0.03 (derivation in the band commentary above) was INERT here: every
   ;; witnessed must-fire margin (+0.016 probe force-fits, +0.0026
   ;; live-enriched) sat below it. 0.010 fires the probe force-fits with
   ;; ~1.6x headroom and spares every witnessed clean case (max +0.0025).
   ;; Known limit (Slice-4 gate report §3): live-enriched force-fits
   ;; (+0.0026) are inseparable from clean by ANY margin value — restoring
   ;; live-corpus bite is guard-sharpening work (EL-5/C-3), not knob tuning.
   ;; CC-17 re-derivation of :margin against the RELOCATED MaxSim ceiling
   ;; (32 -> 464 query rows). The default :batch-relative normalization divides
   ;; by the call's OWN max raw score, so it is DIMENSIONLESS: moving the
   ;; ceiling cannot rescale the margin, and 0.010 is therefore not
   ;; arithmetically invalidated. MEASURED on 20 real living-description
   ;; candidates against a real consolidator signature
   ;; (doc/build-timeline/evidence/cc17):
   ;;     limit  32: contrast p50 -0.0082, p95 +0.00277, max +0.00392 -> fires 0/20
   ;;     limit 464: contrast p50 -0.0130, p95 -0.00794, max -0.00686 -> fires 0/20
   ;; So the knob's BEHAVIOUR is unchanged (inert on live-enriched candidates
   ;; at both limits) — but the contrast distribution shifts ~0.005 MORE
   ;; NEGATIVE, deepening the inertness the Slice-4 gate report §3 already
   ;; recorded. Retuning it here would be fitting a knob to data that says the
   ;; guards are not separable; the fix stays guard-sharpening (EL-5/C-3).
   ;;
   ;; THE OTHER HALF, stated plainly because it IS a regression: on the four
   ;; SHORT synthetic probe sets (colbert batch_relative_evidence_test) the same
   ;; move compresses the batch-relative contrast ~75x —
   ;;     force-fit +0.0160 @32  ->  +0.000211 @464
   ;;     cleanest   +0.0025 @32  ->  -0.001544 @464
   ;; so on THAT family the force-fit falls from 1.6x ABOVE this margin to ~47x
   ;; BELOW it and the penalty stops firing. The separability ORDER survives
   ;; (the force-fit is still the only positive margin; a test now pins that).
   ;; NOT retuned here because :margin is a gate-approved calibration (ADR 0016
   ;; / Slice-4 gate) and re-fitting it to four short synthetic probes would be
   ;; fitting to a regime the measured production corpus does not contain — its
   ;; SHORTEST real query is 150 word-piece tokens. Retuning :margin AND
   ;; :penalty-scale for the new scale is a gate decision, not a subagent's.
   :margin 0.010
   :penalty-cap 0.6
   ;; CC-16 / ADR 0026 — which reading of the POSITIVE signal is APPLIED to the
   ;; fitness. Both are always computed and stamped (ADR 0027: a gate must be
   ;; able to report whether it is doing anything), so this knob is the STAGE-2
   ;; flip and nothing else:
   ;;   :content+good-when — pre-ADR-0026: the whole indexed description PLUS
   ;;                        every :good-when. Measured INERT on production
   ;;                        content (cos-good 1.000000 in 153/154 cells,
   ;;                        0 firings) because the ~800-char summary restates
   ;;                        the behavior's own avoid-conditions and wins the
   ;;                        call's own normalizer.
   ;;   :good-when         — ADR 0026: the use-case description alone, which is
   ;;                        what ADR 0016's contrast actually names.
   ;; STAGE 1 ships :content+good-when — the shipped behavior, byte for byte —
   ;; so the shadow numbers can be read against the 0/154 baseline BEFORE the
   ;; mechanism is woken. Flipping this default is Stage 2 and is gated on the
   ;; three watch conditions in ADR 0026 (firing rate seen; the web-search
   ;; zero-false-positive case still exactly 0; the refactor force-fit actually
   ;; DEMOTING, i.e. penalty > 0.0145 — firing is not demoting).
   :positive-signal :content+good-when
   :colbert-norm {:max-score nil :method :batch-relative}})

;; =============================================================================
;; The pure penalty arithmetic (DETERMINISTIC — unit-tested hard). UNCHANGED.
;; =============================================================================

(defn clamp
  "Clamp x to [lo, hi]."
  [x lo hi]
  (-> x (max lo) (min hi)))

(defn domain-penalty
  "The CONTRASTIVE penalty for one candidate, given its two scores.

     cos-avoid — score(:avoid-when, task)  (MAX over the candidate's avoid guards)
     cos-good  — score(:good-when/:summary, task) (MAX over the positive signals)

   Returns a penalty in [0, penalty-cap]. Fires (> 0) ONLY when
   cos-avoid - cos-good > margin: the task is more the avoid-condition than the
   use-case, beyond scorer noise. good >= avoid (or within margin) => 0."
  ([cos-avoid cos-good]
   (domain-penalty cos-avoid cos-good default-penalty-config))
  ([cos-avoid cos-good {:keys [penalty-scale margin penalty-cap]
                        :or {penalty-scale (:penalty-scale default-penalty-config)
                             margin (:margin default-penalty-config)
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
   try/catch fallback, not crash it."
  [candidate task scorer config]
  (contrast-record (scorer-cosines candidate task scorer) config))

(defn- assoc-penalty
  "Stamp one candidate with {:cos-avoid :cos-good :domain-penalty} — plus both
   CC-16 positive-signal variants when the scorer reported them — and its
   penalized :fitness-score. The fitness is penalized by the APPLIED variant
   ONLY; the shadow is observability, never behaviour."
  [candidate cosines config]
  (let [record  (contrast-record cosines config)
        penalty (:penalty record)]
    (-> candidate
        (merge (dissoc record :penalty))
        (assoc :domain-penalty penalty)
        (assoc :fitness-score (apply-penalty (:fitness-score candidate) penalty)))))

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
  {:candidate-count (count stamped)
   :applied-positive-signal (applied-positive-signal config)
   :margin (double (get config :margin (:margin default-penalty-config)))
   :penalty-scale (double (get config :penalty-scale (:penalty-scale default-penalty-config)))
   :penalty-cap (double (get config :penalty-cap (:penalty-cap default-penalty-config)))
   :variants (into {}
                   (remove (comp nil? val))
                   {:content+good-when
                    (variant-summary stamped :cos-avoid-with-content
                                     :cos-good-with-content :domain-penalty-with-content)
                    :good-when
                    (variant-summary stamped :cos-avoid-sans-content
                                     :cos-good-sans-content :domain-penalty-sans-content)})})

(defn- log-penalty-pass!
  "Emit the ADR-0027 pass report. The headline firing counts are FLAT so they
   are greppable in a log line without parsing the nested distributions."
  [stamped config]
  (let [report (penalty-pass-report stamped config)]
    (mu/log ::domain-penalty-pass
            :candidate-count (:candidate-count report)
            :applied-positive-signal (:applied-positive-signal report)
            :margin (:margin report)
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
         stamped (mapv (fn [c cosines] (assoc-penalty c cosines config))
                       candidates per-candidate-cosines)]
     (log-penalty-pass! stamped config)
     (vec (sort-by (fn [c] (or (:fitness-score c) -1.0)) > stamped))))
  ([_ctx candidates task config scorer]
   ;; PER-CANDIDATE injected-scorer seam (backward-compatible determinism path):
   ;; scorer-cosines already fails open per-candidate around the scorer call.
   (let [stamped (mapv (fn [c] (assoc-penalty c (scorer-cosines c task scorer) config))
                       candidates)]
     (log-penalty-pass! stamped config)
     (vec (sort-by (fn [c] (or (:fitness-score c) -1.0)) > stamped)))))
