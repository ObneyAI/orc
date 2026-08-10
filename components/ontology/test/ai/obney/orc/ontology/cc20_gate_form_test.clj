(ns ai.obney.orc.ontology.cc20-gate-form-test
  "CC-20 (ADR 0027, grill GR-4 Q5 / GR-5 Q2): the EL-5 gate moves off absolute
   similarity-score thresholds onto a POPULATION-RELATIVE form — a z-score
   within the pass's own contrast distribution, with a theory-anchored
   positivity precondition (avoid must beat good AT ALL before outlierness can
   fire anything).

   WHY z AND NOT THE ISSUE'S LEADING CANDIDATE (TMM), decided on the BANKED
   evidence (doc/build-timeline/evidence/cc16/cc16-cells-{shipped,32}.edn —
   559 real cells x 2 positive-signal variants x 2 query-token limits, real
   JVM ColBERT, real production bodies; loaded verbatim below, never
   transcribed):

   - TMM == the shipped :batch-relative normalization (score/call-max is
     exactly (s - theoretical-min 0)/(empirical-max - 0)), so 'TMM form' means
     an absolute margin re-derived on that scale. MEASURED KILL: every margin
     in the demotion-feasible window — canary probe floor c=+0.001652 < m,
     force-fit demotion ceiling m < c=+0.003554 - 0.0145/scale — fires 27-30
     of the consolidator task's 43 cells (the LONGEST real query; its whole
     contrast distribution sits high on the batch-relative scale), fires
     52-135/559 overall, and at every m < +0.002915 fires the rename-symbol x
     Rename-move-symbol OWN-DOMAIN cell. The old margin's own 6/559 firings
     were ALL on that one long-query task — the exact per-query scale
     non-comparability Rossi et al. (CIKM'24) predict — and a re-derived TMM
     margin reproduces that failure at a new value. Cross-limit, the same
     contrast moves 7x (force-fit +0.0241 @32 -> +0.0036 @464), so no absolute
     value on this scale survives the ceiling knob either (Lee 2210.13678;
     Hawking & Robertson 2003).
   - RANK (top-k by contrast) fires a FIXED budget every pass — it cannot
     abstain when nothing is wrong (ADR 0027: a gate must be able to do
     nothing), needs k>=3 to reach the force-fit (rank #3 at the shipped
     limit), and carries no graded magnitude for the demotion contract.
   - Z within the pass: scale-free (survives both the ceiling move and corpus
     growth in the sd), fires the force-fit at BOTH limits (z +1.73 @464,
     +3.01 @32), abstains on every ground-truth own-domain cell at the shipped
     limit (max own-domain z +1.24), and abstains on 3 of the old gate's 6
     firings (z +1.11..+1.19 — scale, not signal).

   THE DERIVED VALUES (from the banked cells, derived once, form+value
   together per GR-5 Q2):
     z-threshold 1.5  — inside the shipped-limit separability band: above
                        every ground-truth own-domain cell (max +1.236) and
                        below the force-fit (+1.729); also the exact bound at
                        which populations of n<=4 PROVABLY cannot fire
                        (sample-z max = (n-1)/sqrt(n) = 1.5 at n=4).
     z-scale     0.1  — the demotion contract: the witnessed force-fit excess
                        (1.729-1.5=0.229) must clear P-B's measured demotion
                        bar 0.0145; 0.1 gives p=0.0229 (1.58x the bar) and
                        keeps the most extreme witnessed cell (z +4.24) at
                        p=0.27, well inside the 0.6 cap — graded, never
                        annihilating.
     min-population 5 — z needs a population; below 5 the sd is not an
                        estimate and (n-1)/sqrt(n) <= 1.5 makes firing
                        impossible anyway. A single-candidate pass (the banked
                        web-search canary probe) abstains STRUCTURALLY.

   Fixtures: cc20_cells_shipped.edn / cc20_cells_32.edn are byte-identical
   copies of the banked cc16 evidence files (see their :config). The tests
   drive the REAL production pass (penalize-candidates, injected-scorer arity)
   over the banked cosines — they do not reimplement the gate arithmetic."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.obney.orc.ontology.core.domain-penalty :as dp]
            [ai.obney.orc.ontology.core.task-classifier :as tc]))

(defn- approx? [a b] (< (Math/abs (- (double a) (double b))) 1e-9))

;; =============================================================================
;; 1. The pure population primitives — edges first.
;; =============================================================================

(deftest contrast-population-states-n-mean-sd
  (testing "population stats over a contrast seq"
    (let [p (dp/contrast-population [1.0 2.0 3.0 4.0 5.0])]
      (is (= 5 (:n p)))
      (is (approx? 3.0 (:mean p)))
      (is (approx? (Math/sqrt 2.5) (:sd p)) "sample sd (n-1)")))
  (testing "degenerate populations never fabricate spread"
    (is (= 0 (:n (dp/contrast-population []))))
    (is (= 0.0 (:sd (dp/contrast-population [0.7]))) "n=1 has no spread")
    (is (= 0.0 (:sd (dp/contrast-population [0.2 0.2 0.2 0.2 0.2])))
        "all-equal has zero spread")))

(def ^:private gate
  "The CC-20 derived gate values (see ns docstring for the derivation)."
  {:form :z-score :z-threshold 1.5 :z-scale 0.1 :min-population 5})

(deftest z-gate-penalty-fires-graded-above-threshold
  ;; A tight population with one genuine outlier. NB sample z is bounded by
  ;; (n-1)/sqrt(n), so the outlier's z is real, not assumed.
  (let [pop (dp/contrast-population [-0.1 0.1 -0.1 0.1 -0.1 0.1 0.0 0.9])
        z (/ (- 0.9 (:mean pop)) (:sd pop))]
    (testing "the outlier's z genuinely clears the threshold (fixture sanity)"
      (is (> z 1.5)))
    (testing "above threshold => graded penalty, scale * (z - threshold)"
      (is (approx? (* 0.1 (- z 1.5))
                   (dp/z-gate-penalty 0.9 pop gate 0.6)))
      (is (pos? (dp/z-gate-penalty 0.9 pop gate 0.6))))
    (testing "at/below threshold => exactly 0"
      (is (= 0.0 (dp/z-gate-penalty 0.1 pop gate 0.6)))
      (is (= 0.0 (dp/z-gate-penalty 0.0 pop gate 0.6))))))

(deftest z-gate-penalty-positivity-precondition
  (testing "a NEGATIVE contrast never fires, however extreme its z — the
            theory anchor: the penalty means 'avoid beats good', and a
            negative contrast means it does not (ADR 0016), so outlierness
            alone must not penalize"
    (let [pop (dp/contrast-population [-0.9 -0.91 -0.92 -0.93 -0.1])]
      ;; -0.1 is a huge positive-side outlier of this population, but c <= 0.
      (is (= 0.0 (dp/z-gate-penalty -0.1 pop gate 0.6))))))

(deftest z-gate-penalty-abstains-without-a-population
  (testing "below min-population => 0 (a z against no population is not a z);
            the banked single-candidate canary probe abstains STRUCTURALLY"
    (let [pop (dp/contrast-population [0.9])]
      (is (= 0.0 (dp/z-gate-penalty 0.9 pop gate 0.6))))
    (let [pop (dp/contrast-population [0.1 0.2 0.9 0.15])]
      (is (= 0.0 (dp/z-gate-penalty 0.9 pop gate 0.6)) "n=4 < 5 abstains")))
  (testing "zero spread => 0 (no outlier relative to an all-equal population)"
    (let [pop (dp/contrast-population [0.3 0.3 0.3 0.3 0.3 0.3])]
      (is (= 0.0 (dp/z-gate-penalty 0.3 pop gate 0.6))))))

;; =============================================================================
;; 2. Form selection + the population-aware pass (through the REAL
;;    penalize-candidates, injected-scorer arity — no ColBERT, no LLM).
;; =============================================================================

(deftest the-default-config-selects-the-z-form-and-carries-no-absolute-values
  (testing "CC-20: the shipped default gate is :z-score; the old absolute
            values do NOT survive as dead config"
    (is (= :z-score (:form (dp/gate-config dp/default-penalty-config))))
    (is (not (contains? dp/default-penalty-config :margin))
        "no :margin in the shipped defaults")
    (is (not (contains? dp/default-penalty-config :penalty-scale))
        "no :penalty-scale in the shipped defaults"))
  (testing "ADR 0026 Stage 2 (watch conditions met on the banked cells — see
            the fixture tests below): the APPLIED positive signal is :good-when"
    (is (= :good-when (:positive-signal dp/default-penalty-config))))
  (testing "an explicit :margin selects the frozen absolute form — an operator's
            calibrated override keeps its exact historical meaning"
    (let [g (dp/gate-config {:margin 0.03})]
      (is (= :absolute (:form g)))
      (is (= 0.03 (:margin g)))
      (is (= 10.0 (:penalty-scale g)) "absent scale falls back to the frozen legacy value"))
    (is (= :absolute (:form (dp/gate-config (assoc dp/default-penalty-config :margin 0.02))))
        "explicit :margin wins even when the default :gate map is also present")))

(defn- stamped-scorer
  "Injected per-candidate scorer reading pre-stamped ::cos-avoid/::cos-good."
  [c _t]
  {:cos-avoid (double (or (::cos-avoid c) 0.0))
   :cos-good  (double (or (::cos-good c) 0.0))})

(defn- cands-with-contrasts
  "n candidates whose (cos-avoid - cos-good) equal the given contrasts."
  [contrasts]
  (vec (map-indexed (fn [i c]
                      {:document-id (str "c" i) :fitness-score 0.9
                       ::cos-avoid (max c 0.0) ::cos-good (max (- c) 0.0)})
                    contrasts)))

(deftest default-pass-fires-the-outlier-and-spares-the-population
  (let [contrasts [0.001 -0.001 0.002 -0.002 0.0015 -0.0015 0.001 0.02]
        out (dp/penalize-candidates nil (cands-with-contrasts contrasts)
                                    "task" dp/default-penalty-config stamped-scorer)
        by-id (into {} (map (juxt :document-id identity)) out)]
    (testing "the genuine outlier (0.02 against a +-0.002 population) fires"
      (is (pos? (:domain-penalty (get by-id "c7"))))
      (is (< (:fitness-score (get by-id "c7")) 0.9) "and is demoted"))
    (testing "every in-population candidate abstains"
      (doseq [i (range 7)]
        (is (= 0.0 (:domain-penalty (get by-id (str "c" i))))
            (str "c" i " abstains"))))
    (testing "re-sort: the fired outlier drops below the unfired candidates"
      (is (= "c7" (:document-id (last out)))))))

(deftest default-pass-abstains-below-min-population
  (testing "n=2: an absolute gate at ANY margin would fire on a +0.5 contrast;
            the z gate abstains — there is no population to be an outlier OF"
    (let [out (dp/penalize-candidates nil (cands-with-contrasts [0.5 -0.1])
                                      "task" dp/default-penalty-config stamped-scorer)]
      (is (every? #(= 0.0 (:domain-penalty %)) out)))))

(deftest explicit-margin-config-keeps-the-absolute-arithmetic
  (testing "a config naming :margin behaves byte-identically to the pre-CC-20
            gate — population size is irrelevant to it"
    (let [cfg {:penalty-scale 2.0 :margin 0.05 :penalty-cap 0.6}
          out (dp/penalize-candidates nil (cands-with-contrasts [0.5 -0.1])
                                      "task" cfg stamped-scorer)
          fired (first (filter #(= "c0" (:document-id %)) out))]
      ;; contrast 0.5 - margin 0.05 = 0.45 ; * 2.0 = 0.9 -> capped 0.6
      (is (= 0.6 (:domain-penalty fired)))
      (is (= 0.0 (:domain-penalty (first (filter #(= "c1" (:document-id %)) out))))))))

(deftest pass-report-states-the-gate-form-and-its-knobs
  (testing ":z-score report carries the derived knobs + the population it judged"
    (let [out (dp/penalize-candidates nil (cands-with-contrasts [0.001 -0.001 0.002 -0.002 0.02])
                                      "task" dp/default-penalty-config stamped-scorer)
          report (dp/penalty-pass-report out dp/default-penalty-config)]
      (is (= :z-score (:gate-form report)))
      (is (= 1.5 (:z-threshold report)))
      (is (= 0.1 (:z-scale report)))
      (is (= 5 (:min-population report)))
      (is (= 5 (get-in report [:population :n])) "the applied population's n")
      (is (number? (get-in report [:population :sd])))
      (is (not (contains? report :margin)) "no absolute knob reported for the z form")))
  (testing ":absolute report keeps the margin/scale it judged against"
    (let [cfg {:penalty-scale 2.0 :margin 0.05 :penalty-cap 0.6}
          out (dp/penalize-candidates nil (cands-with-contrasts [0.5 -0.1]) "task" cfg stamped-scorer)
          report (dp/penalty-pass-report out cfg)]
      (is (= :absolute (:gate-form report)))
      (is (= 0.05 (:margin report)))
      (is (= 2.0 (:penalty-scale report))))))

(deftest z-gate-penalty-honours-the-cap
  (testing "the penalty stays GRADED and capped — demoted, never annihilated.
            NB sample z is bounded by (n-1)/sqrt(n), so reaching the cap at
            scale 0.1 needs z >= 7.5 => n >= 58; 59 zeros + one outlier gives
            z = (59/60)*sqrt(60) ~ 7.62."
    (let [pop (dp/contrast-population (conj (vec (repeat 59 0.0)) 5.0))]
      (is (= 0.6 (dp/z-gate-penalty 5.0 pop gate 0.6))))))

;; =============================================================================
;; 3. THE BANKED-CELLS CONTRACT — the derivation, replayed through the REAL
;;    production pass over the REAL banked cells. Every number below is loaded
;;    from the fixture EDN (byte-identical copies of the CC-16 evidence bank),
;;    never transcribed. The ONE exception, disclosed: the web-search canary
;;    PROBE is not in the cells EDN (the harness scored it in a separate
;;    single-candidate pass); its banked contrast pair (cos-avoid 1.0 vs
;;    cos-good sans-content 0.998348 => contrast +0.001652,
;;    cc16-shadow-shipped2.log 'WATCH CONDITION 2(a)') is carried here from
;;    the banked LOG — and the property asserted (a single-candidate pass
;;    abstains) is structural, not value-dependent.
;; =============================================================================

(def ^:private cells-shipped
  (-> "cc20_cells_shipped.edn" io/resource slurp edn/read-string))
(def ^:private cells-32
  (-> "cc20_cells_32.edn" io/resource slurp edn/read-string))

(def ^:private force-fit-task "refactor-extract-helper (the FORCE-FIT case)")
(def ^:private canary-task "web-search-own-domain (the ZERO-FP canary)")

(def ^:private demotion-bar
  "P-B, real corpus, real ColBERT: the refactor force-fit needs a penalty
   ABOVE this to fall below the runner-up. Firing is not demoting."
  0.0145)

(def ^:private pre-cc20-absolute-config
  "The exact shipped gate this migration replaces (Slice-4b margin retune) —
   used ONLY to demonstrate old-vs-new on the same banked cells."
  {:penalty-scale 10.0 :margin 0.010 :penalty-cap 0.6})

(def ^:private own-domain-cells
  "Ground-truth (task, its OWN behavior candidate) pairs inside the banked
   matrix — cells the gate must NEVER fire on. The rename-symbol pair is the
   issue-documented own-domain false positive of the truncated-query regime;
   the rest follow from the harness task texts vs the candidate summaries.
   validate-artifact's own candidate is Validation ('checks an artifact
   against formal rules') — NOT Critique, whose own first avoid-when guard
   ('the task is checking against formal rules (pass/fail) — that's
   validation, not critique') names this exact task as its avoid-domain."
  {"rename-symbol"      ["Rename-move-symbol"]
   "investigate"        ["Investigation" "Bug-diagnosis"]
   "debug-failing-test" ["Bug-diagnosis"]
   "write-doc"          ["Documentation-writing"]
   "wire-dependency"    ["Code-edit dependency-wiring"]
   "extract-entities"   ["Extraction" "Chunked-extraction" "ChunkedExtraction"]
   "classify-items"     ["Classification"]
   "validate-artifact"  ["Validation checks"]
   "web-search-own-domain (the ZERO-FP canary)" ["Research gathers"]})

(defn- task-cells [data variant task]
  (->> (get-in data [:cells variant])
       (filter #(= task (:label %)))
       vec))

(defn- run-banked-pass
  "Drive the REAL penalize-candidates (injected-scorer arity) over one banked
   task's cells: each candidate carries that cell's banked cosines."
  ([cells] (run-banked-pass cells dp/default-penalty-config))
  ([cells config]
   (let [cands (mapv (fn [cell]
                       {:document-id (:document-id cell)
                        :fitness-score 0.9
                        ::cos-avoid (:cos-avoid cell)
                        ::cos-good (:cos-good cell)})
                     cells)
         out (dp/penalize-candidates nil cands "banked task" config stamped-scorer)]
     (into {} (map (juxt :document-id identity)) out))))

(defn- cell-of [by-id prefix]
  (let [hits (filter #(str/starts-with? % prefix) (keys by-id))]
    (is (= 1 (count hits)) (str "exactly one candidate matches prefix " prefix))
    (get by-id (first hits))))

(deftest banked-force-fit-fires-and-demotes-where-the-old-gate-could-not
  (let [cells (task-cells cells-shipped :good-when force-fit-task)
        _ (is (= 43 (count cells)) "N stated, not assumed")
        new-pass (run-banked-pass cells)
        old-pass (run-banked-pass cells pre-cc20-absolute-config)
        ff-new (cell-of new-pass "Rename-move-symbol")
        ff-old (cell-of old-pass "Rename-move-symbol")]
    (testing "OLD gate (margin 0.010): the force-fit cannot even fire —
              its banked contrast (+0.003554) sits 2.8x below the margin"
      (is (= 0.0 (:domain-penalty ff-old))))
    (testing "NEW gate: fires AND clears P-B's demotion bar (p > 0.0145)"
      (is (pos? (:domain-penalty ff-new)))
      (is (> (:domain-penalty ff-new) demotion-bar)
          (str "demotes, not merely fires: p=" (:domain-penalty ff-new))))))

(deftest banked-canary-probe-abstains-structurally
  (testing "EL-5 case (3), the zero-FP canary PROBE: a single-candidate pass
            has no population, so the z gate abstains by construction — the
            banked probe contrast (+0.001652, sans-content) yields EXACTLY 0"
    (let [by-id (run-banked-pass
                 [{:label canary-task
                   :document-id "websearch(probe)"
                   :cos-avoid 1.0 :cos-good 0.998348}])]
      (is (= 0.0 (:domain-penalty (get by-id "websearch(probe)")))))))

(deftest banked-canary-task-spares-the-web-search-behavior
  (let [cells (task-cells cells-shipped :good-when canary-task)
        by-id (run-banked-pass cells)
        fired (->> (vals by-id) (filter #(pos? (:domain-penalty %)))
                   (map :document-id) set)]
    (testing "the web-search behavior itself (Research) is never penalized on
              its own task"
      (is (= 0.0 (:domain-penalty (cell-of by-id "Research gathers")))))
    (testing "what DOES fire on this task is pinned by identity — candidates
              whose avoid-domains cover a non-their-domain task, none of them
              the web-search behavior (regression surface, deterministic
              fixture)"
      (is (= #{"Contract-comparison trees compare two cont"
               "Workspace multi-file edit is the EFFECTOR "
               "Model cascade is fallback-recovery where b"
               "Investigation explains WHY a system is mis"}
             fired)))))

(deftest banked-own-domain-cells-all-abstain
  (testing "every ground-truth (task x its-own-behavior) cell in the banked
            matrix abstains under the new gate at the shipped limit"
    (doseq [[task prefixes] own-domain-cells
            :let [by-id (run-banked-pass (task-cells cells-shipped :good-when task))]
            prefix prefixes]
      (is (= 0.0 (:domain-penalty (cell-of by-id prefix)))
          (str task " x " prefix " must not fire")))))

(deftest banked-new-gate-abstains-where-the-old-gate-fired-on-scale
  (let [task "consolidator-signature (LONGEST real query)"
        cells (task-cells cells-shipped :good-when task)
        new-pass (run-banked-pass cells)
        old-pass (run-banked-pass cells pre-cc20-absolute-config)
        scale-artifacts ["Risk-analysis trees identify and categoriz"
                         "Documentation-writing produces human-facin"
                         "Rename-move-symbol is a behavior-preservin"]]
    (testing "the old margin fired on the longest real query's SCALE: these
              three cells cleared 0.010 only because the whole task's contrast
              distribution sits high (Rossi CIKM'24: scores are not comparable
              across queries) — the new gate reads them as in-population and
              abstains"
      (doseq [doc scale-artifacts]
        (is (pos? (:domain-penalty (cell-of old-pass doc)))
            (str doc ": the old gate fired"))
        (is (= 0.0 (:domain-penalty (cell-of new-pass doc)))
            (str doc ": the new gate abstains"))))
    (testing "while the task's true outliers still fire under both"
      (doseq [doc ["Model cascade is fallback-recovery where b"
                   "Chunked-extraction is THE structural patte"]]
        (is (pos? (:domain-penalty (cell-of new-pass doc))))))))

(deftest banked-critique-guard-is-a-true-positive-the-old-gate-missed
  (testing "validate-artifact x Critique: the banked Critique body's FIRST
            avoid-when guard is 'the task is checking against formal rules
            (pass/fail) — that's validation, not critique', and the banked
            task IS formal pass/fail rule-checking. The new gate fires it
            (z +1.75); the old margin could not (contrast +0.00286 << 0.010).
            The task's OWN behavior (Validation) abstains under both."
    (let [cells (task-cells cells-shipped :good-when "validate-artifact")
          new-pass (run-banked-pass cells)
          old-pass (run-banked-pass cells pre-cc20-absolute-config)]
      (is (pos? (:domain-penalty (cell-of new-pass "Critique evaluates"))))
      (is (= 0.0 (:domain-penalty (cell-of old-pass "Critique evaluates"))))
      (is (= 0.0 (:domain-penalty (cell-of new-pass "Validation checks")))))))

(deftest banked-form-transfers-across-the-query-token-ceiling
  (testing "the property that motivated CC-20: the SAME gate values fire the
            force-fit at BOTH maximum_query_tokens regimes, although the raw
            contrast moves ~7x (+0.0241 @32 -> +0.0036 @464) — the move that
            made every absolute value meaningless"
    (let [ff-464 (cell-of (run-banked-pass (task-cells cells-shipped :good-when force-fit-task))
                          "Rename-move-symbol")
          ff-32 (cell-of (run-banked-pass (task-cells cells-32 :good-when force-fit-task))
                         "Rename-move-symbol")]
      (is (> (:domain-penalty ff-464) demotion-bar))
      (is (> (:domain-penalty ff-32) demotion-bar)))))

;; =============================================================================
;; 4. THE CLASSIFY CONFIDENCE GATE — no form migration; reporting added.
;;
;; The Part-2 derivation for this gate is an IMPOSSIBILITY result, not a value:
;; the gate's property is 'is the BEST match good enough to assign', judged on
;; the top-1, and every candidate-set-relative form is degenerate there —
;; rank(top-1) == 1 identically, min-max/TMM(top-1) == 1.0 identically (the
;; issue's own documented caution: after candidate-set normalisation the top
;; candidate is 1.0 whether or not anything is relevant), and z(top-1) measures
;; separation-from-the-pack, a DIFFERENT property (uniformly-good and
;; uniformly-bad candidate sets are indistinguishable). The thresholded
;; quantity is also NOT a corpus-scale similarity score — it is the reranker's
;; rubric-anchored fitness (CC-19, N=156 real: values {1.0 x62, 0.98 x21,
;; 0.95 x58}, near-inert in [0.4, 0.9], with the REAL signal in the low tail:
;; the two fresh-mints sat at 0.35 and 0.0). So the absolute form stays, the
;; low tail keeps its bite, and per ADR 0027 decision 2 the gate now REPORTS
;; itself — a near-inert gate must be able to say so.
;; =============================================================================

(deftest classify-confidence-gate-reports-itself
  (testing "the report states outcome, N, threshold and the top-score's
            distance to it"
    (let [r (tc/confidence-gate-report
             {:outcome :matched :confidence 0.95
              :top-candidates [{} {} {}]
              :was-fresh-mint? false :rerank-fallback? false}
             0.7)]
      (is (= :matched (:outcome r)))
      (is (= 0.7 (:threshold r)))
      (is (= 0.95 (:top-score r)))
      (is (approx? 0.25 (:margin-to-threshold r)))
      (is (= 3 (:candidate-count r)))
      (is (false? (:rerank-fallback? r)))))
  (testing "a fresh mint reports the LOW-TAIL signal CC-19 says is the gate's
            real discriminating power"
    (let [r (tc/confidence-gate-report
             {:outcome :novel :confidence 0.35 :top-candidates []
              :was-fresh-mint? true :rerank-fallback? false}
             0.7)]
      (is (= :novel (:outcome r)))
      (is (true? (:was-fresh-mint? r)))
      (is (approx? -0.35 (:margin-to-threshold r)))
      (is (zero? (:candidate-count r)))))
  (testing "an uncertain (reranker-fallback) outcome is reported as such —
            uncertainty must stay distinguishable from novelty"
    (let [r (tc/confidence-gate-report
             {:outcome :uncertain :confidence 0.0 :top-candidates []
              :rerank-fallback? true}
             0.7)]
      (is (= :uncertain (:outcome r)))
      (is (true? (:rerank-fallback? r))))))

(deftest banked-firing-rate-is-pinned
  (testing "ADR 0027: the gate can say what it is doing — 42 of 559 cells fire
            at the shipped limit under the ADR-0026 signal (deterministic
            fixture; a drift here means the gate or the fixture changed)"
    (let [tasks (distinct (map :label (get-in cells-shipped [:cells :good-when])))
          fired (reduce + (for [t tasks]
                            (->> (run-banked-pass (task-cells cells-shipped :good-when t))
                                 vals
                                 (filter #(pos? (:domain-penalty %)))
                                 count)))]
      (is (= 13 (count tasks)))
      (is (= 42 fired)))))
