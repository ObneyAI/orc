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
   penalize pass. The avoid/good SOURCE strings come from EL-2's enrichment."
  (:require [clojure.string :as str]
            [ai.obney.orc.ontology.core.embedding :as embedding]
            [ai.obney.orc.colbert.interface :as colbert]
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
     :colbert-norm    — colbert/normalize-colbert-score opts ({:max-score
                        :method}); applied to both avoid + good so margin/cap are
                        scale-stable. Only used by the :colbert scorer."
  {:scorer :colbert
   :embedding-model nil
   :penalty-scale 10.0
   :margin 0.03
   :penalty-cap 0.6
   :colbert-norm {:max-score 40.0 :method :linear}})

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
  "The candidate's NEGATIVE signals: its enriched top-level :avoid-when vector
   plus any per-weakness :avoid-when guards. EL-2's enrich-candidate-evidence
   already folds the per-weakness guards into the top-level vector, but we union
   defensively so the penalty reads every guard the body carries."
  [candidate]
  (vec (distinct (concat (:avoid-when candidate)
                         (keep :avoid-when (:weaknesses candidate))))))

(defn positive-strings
  "The candidate's POSITIVE signals: the indexed summary (:content) AND every
   :good-when from the enriched strengths. MAX over these is cos-good — the
   use-case description the avoid-condition must beat for the penalty to fire."
  [candidate]
  (vec (distinct (concat (when (:content candidate) [(:content candidate)])
                         (keep :good-when (:strengths candidate))))))

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
  ([{:keys [embedding-model]} embed-fn]
   (let [embed (if embedding-model
                 (fn [s] (embed-fn s {:model-id embedding-model}))
                 embed-fn)
         ;; Memoize the task embedding so a batch of candidates embeds the task
         ;; once; guard strings vary per candidate so they aren't memoized.
         task->emb (memoize embed)]
     (fn [candidate task]
       (let [task-emb (task->emb task)]
         (if (nil? task-emb)
           ;; Can't embed the task — no penalty source (fail open).
           {:cos-avoid 0.0 :cos-good 0.0}
           {:cos-avoid (max-cos embed task-emb (avoid-strings candidate))
            :cos-good  (max-cos embed task-emb (positive-strings candidate))}))))))

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

(defn colbert-scorer
  "The :colbert backend (DEFAULT). In-memory MaxSim via colbert/rerank — NO
   index. Returns a scorer fn (candidate task). Closes over ctx + the
   normalization opts. rerank-fn / norm-fn default to the real colbert interface;
   tests inject stubs.

   NB: one rerank call PER CANDIDATE (its own guard set), each scoring all of
   that candidate's guards against the task in a single bridge round-trip."
  ([ctx config] (colbert-scorer ctx config
                                (fn [opts] (colbert/rerank ctx opts))
                                colbert/normalize-colbert-score))
  ([_ctx {:keys [colbert-norm]} rerank-fn norm-fn]
   (let [{:keys [max-score method]
          :or {max-score (:max-score (:colbert-norm default-penalty-config))
               method (:method (:colbert-norm default-penalty-config))}} colbert-norm
         norm (fn [score] (norm-fn score :max-score max-score :method method))]
     (fn [candidate task]
       (colbert-rerank-scores rerank-fn norm
                              (avoid-strings candidate)
                              (positive-strings candidate)
                              task)))))

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
    :colbert   (colbert-scorer ctx config)
    ;; Unknown scorer keyword — fall back to the default backend, but log it so a
    ;; typo'd operator config surfaces (never silently mis-score).
    (do (mu/log ::unknown-scorer :scorer scorer :falling-back-to :colbert)
        (colbert-scorer ctx config))))

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
   document set for ONE rerank/embed call. Reuses avoid-strings/positive-strings."
  [candidates]
  (let [non-blank (fn [ss] (remove (fn [s] (or (nil? s) (str/blank? s))) ss))
        avoid (vec (distinct (non-blank (mapcat avoid-strings candidates))))
        good  (vec (distinct (non-blank (mapcat positive-strings candidates))))
        docs  (vec (distinct (concat avoid good)))]
    {:avoid avoid :good good :docs docs}))

(defn- candidate-cosines-fn
  "Given a content->normalized-score map (the SHARED scores from the single
   batched call), return a pure per-candidate lookup
     (fn [candidate] -> {:cos-avoid :cos-good})
   that maxes each candidate's avoid-strings / positive-strings over the map.
   A guard absent from the map (e.g. blank, never scored) contributes nothing; a
   side with no scored guards is 0.0 (never fabricated — the conservative side)."
  [score-map]
  (let [max-over (fn [strings]
                   (let [vs (keep score-map strings)]
                     (if (seq vs) (apply max vs) 0.0)))]
    (fn [candidate]
      {:cos-avoid (max-over (avoid-strings candidate))
       :cos-good  (max-over (positive-strings candidate))})))

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
   the IDENTICAL per-guard scores as the N-call colbert-scorer."
  ([ctx config] (batch-colbert-scorer ctx config
                                      (fn [opts] (colbert/rerank ctx opts))
                                      colbert/normalize-colbert-score))
  ([_ctx {:keys [colbert-norm]} rerank-fn norm-fn]
   (let [{:keys [max-score method]
          :or {max-score (:max-score (:colbert-norm default-penalty-config))
               method (:method (:colbert-norm default-penalty-config))}} colbert-norm
         norm (fn [score] (norm-fn score :max-score max-score :method method))]
     (fn [candidates task]
       (let [{:keys [docs]} (distinct-guards candidates)]
         (if (empty? docs)
           ;; No guards anywhere => no bridge round-trip; every candidate {0,0}.
           (fn [_candidate] {:cos-avoid 0.0 :cos-good 0.0})
           (let [res (rerank-fn {:query task :documents docs})
                 score-map (into {} (map (juxt :content (comp norm :score))) res)]
             (candidate-cosines-fn score-map))))))))

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
  ([{:keys [embedding-model]} embed-fn]
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
             (candidate-cosines-fn score-map))))))))

(defn make-batch-scorer
  "Select + construct the BATCH scorer factory from config (EL-5.1): :colbert
   (DEFAULT) or :embedding. Returns (fn [candidates task] -> per-candidate-fn).
   Default real backend; tests bypass this and inject a fake batch factory (or use
   the deterministic stub rerank-fn / embed-fn). Mirrors make-scorer's selection."
  [ctx {:keys [scorer] :or {scorer (:scorer default-penalty-config)} :as config}]
  (case scorer
    :embedding (batch-embedding-scorer config)
    :colbert   (batch-colbert-scorer ctx config)
    (do (mu/log ::unknown-scorer :scorer scorer :falling-back-to :colbert)
        (batch-colbert-scorer ctx config))))

;; =============================================================================
;; score-candidate / penalize-candidates — the PASS. Now scorer-driven.
;; =============================================================================

(defn score-candidate
  "Compute {:cos-avoid :cos-good :penalty} for one enriched candidate against the
   task, using the injected SCORER. The scorer ((scorer candidate task) ->
   {:cos-avoid :cos-good}) is the seam: :colbert / :embedding in production, a
   deterministic fake in tests. Pure given the scorer + config.

   FAIL OPEN: if the scorer throws (e.g. the ColBERT bridge is unavailable), the
   candidate scores {0,0} -> penalty 0 (the LLM ordering is left untouched, never
   a FABRICATED penalty). The penalty layer is BEST-EFFORT/additive — a scoring
   outage must degrade retrieval gracefully, exactly like the reranker's own
   try/catch fallback, not crash it."
  [candidate task scorer config]
  (let [{:keys [cos-avoid cos-good]}
        (try
          (scorer candidate task)
          (catch Throwable t
            (mu/log ::scorer-failed
                    :document-id (:document-id candidate)
                    :error (.getMessage t))
            {:cos-avoid 0.0 :cos-good 0.0}))
        cos-avoid (double (or cos-avoid 0.0))
        cos-good  (double (or cos-good 0.0))]
    {:cos-avoid cos-avoid
     :cos-good cos-good
     :penalty (domain-penalty cos-avoid cos-good config)}))

(defn- assoc-penalty
  "Stamp one candidate with {:cos-avoid :cos-good :domain-penalty} and its
   penalized :fitness-score, given its already-computed contrast cosines."
  [candidate cos-avoid cos-good config]
  (let [cos-avoid (double (or cos-avoid 0.0))
        cos-good  (double (or cos-good 0.0))
        penalty   (domain-penalty cos-avoid cos-good config)]
    (-> candidate
        (assoc :cos-avoid cos-avoid :cos-good cos-good :domain-penalty penalty)
        (assoc :fitness-score (apply-penalty (:fitness-score candidate) penalty)))))

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
             (mapv (constantly {:cos-avoid 0.0 :cos-good 0.0}) candidates)))]
     (->> (map (fn [c {:keys [cos-avoid cos-good]}]
                 (assoc-penalty c cos-avoid cos-good config))
               candidates per-candidate-cosines)
          (sort-by (fn [c] (or (:fitness-score c) -1.0)) >)
          vec)))
  ([_ctx candidates task config scorer]
   ;; PER-CANDIDATE injected-scorer seam (backward-compatible determinism path):
   ;; score-candidate already fails open per-candidate around the scorer call.
   (->> candidates
        (mapv (fn [c]
                (let [{:keys [cos-avoid cos-good]}
                      (score-candidate c task scorer config)]
                  (assoc-penalty c cos-avoid cos-good config))))
        (sort-by (fn [c] (or (:fitness-score c) -1.0)) >)
        vec)))
