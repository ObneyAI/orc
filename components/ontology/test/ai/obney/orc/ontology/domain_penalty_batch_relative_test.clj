(ns ai.obney.orc.ontology.domain-penalty-batch-relative-test
  "Slice 3 (JVM-ColBERT), cycles 3-4: the :batch-relative normalization method
   for the :colbert domain-penalty backend, and the re-derived 32.0 ceiling
   default.

   WHY (witnessed, P-0 + Slice 1): the answerai checkpoint's MASK query
   expansion gives even unrelated guard/task pairs a ~30/32 raw-score floor,
   so a FIXED-CEILING linear normalization compresses every cosine into
   ~0.75-0.98 and the contrastive domain-penalty margin collapses (0.011
   observed vs colbertv2's ~0.18). :batch-relative instead normalizes each
   guard by the MAX raw score within the candidate's own rerank call (the
   normalize-result-scores idiom, scoped per candidate), so cos-avoid/cos-good
   express RELATIVE guard affinity — the strongest guard is 1.0 and the
   contrast survives the floor.

   Deterministic — stubbed rerank-fn via the injected 4-arity seams, no DJL,
   no bridge. The REAL-model evidence table lives in the colbert component's
   batch_relative_evidence_test (needs the local encoder fixture)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.core.domain-penalty :as dp]))

(defn- approx? [expected actual] (< (Math/abs (- (double expected) (double actual))) 1e-9))

;; =============================================================================
;; Cycle 3: the new defaults + batch-relative-scores bounds
;; =============================================================================

(deftest default-colbert-norm-is-batch-relative-with-a-derived-ceiling
  ;; CC-17: the fixed ceiling IS colbert's maximum_query_tokens, which became
  ;; configuration. Pinning 32.0 here would go stale the moment an operator
  ;; retunes the limit (and is flatly wrong against the shipped 464 — every
  ;; score would clamp to 1.0). :max-score nil means "the colbert backend's own
  ;; derived ceiling"; the DEFAULT method is :batch-relative, which is
  ;; dimensionless and ignores it entirely.
  (testing "the shipped default: derived ceiling + :batch-relative method"
    (is (= {:max-score nil :method :batch-relative}
           (:colbert-norm dp/default-penalty-config)))
    (is (nil? (:max-score (:colbert-norm dp/default-penalty-config)))
        "no frozen MaxSim ceiling may be pinned in the ontology component")))

(deftest batch-relative-scores-bounds
  (testing "empty scores => empty map (no fabricated scores)"
    (is (= {} (dp/batch-relative-scores {}))))
  (testing "all-equal positive scores => every score 1.0"
    (is (= {"a" 1.0 "b" 1.0 "c" 1.0}
           (dp/batch-relative-scores {"a" 5.0 "b" 5.0 "c" 5.0}))))
  (testing "zero max => normalizer 1.0 (raw pass-through, never divide-by-zero)"
    (is (= {"a" 0.0 "b" 0.0}
           (dp/batch-relative-scores {"a" 0.0 "b" 0.0}))))
  (testing "negative max => normalizer 1.0 (an all-negative call can't flip signs)"
    (is (= {"a" -2.0 "b" -3.0}
           (dp/batch-relative-scores {"a" -2.0 "b" -3.0}))))
  (testing "the general case: max maps to 1.0, others proportional"
    (let [rel (dp/batch-relative-scores {"top" 30.0 "mid" 15.0 "low" 3.0})]
      (is (approx? 1.0 (get rel "top")))
      (is (approx? 0.5 (get rel "mid")))
      (is (approx? 0.1 (get rel "low"))))))

;; =============================================================================
;; Cycle 4: the :colbert scorer under the :batch-relative default —
;; synthetic scores through the injected rerank-fn/norm-fn arities
;; =============================================================================

(def ^:private avoid-guard "the task is an extract/refactor, not a pure rename")
(def ^:private good-summary "behavior-preserving pure rename of a symbol")
(def ^:private good-when "a pure rename across files")

(def ^:private force-fit-candidate
  {:document-id "rename"
   :avoid-when [avoid-guard]
   :content good-summary
   :strengths [{:good-when good-when}]})

(defn- stub-rerank
  "A rerank-fn serving fixed raw scores by content (per-doc independent, like
   MaxSim). Optionally counts calls."
  ([scores] (stub-rerank scores (atom 0)))
  ([scores calls]
   (fn [{:keys [documents]}]
     (swap! calls inc)
     (mapv (fn [c] {:content c :score (get scores c 0.0)}) documents))))

(defn- linear-norm [score & {:keys [max-score] :or {max-score 32.0}}]
  (min 1.0 (max 0.0 (/ (double score) max-score))))

(deftest batch-relative-default-restores-relative-separation
  (testing "compressed raw scores (the witnessed answerai MASK floor shape):
            the default :batch-relative expresses the strongest guard as 1.0,
            widening the contrast beyond ANY fixed-ceiling linear variant"
    ;; Raw scores shaped like the live evidence run: everything ~28-29.2 of 32.
    ;; CC-20 — CHANGED APPLIED VARIANT, SAME CONTRACT. The default
    ;; :positive-signal is now :good-when (ADR 0026 Stage 2, flipped by CC-20
    ;; with the watch conditions met on the banked cells), so the APPLIED
    ;; cos-good under the default config is MAX over the :good-when strings
    ;; alone (27.9), normalized by the candidate's own guard max — no longer
    ;; MAX(:content, :good-when) (28.7). The property under test — batch-
    ;; relative widens the contrast beyond any fixed-ceiling linear — is
    ;; unchanged and the with-content reading stays available as shadow.
    (let [scores {avoid-guard 29.2 good-summary 28.7 good-when 27.9}
          scorer (dp/colbert-scorer nil dp/default-penalty-config
                                    (stub-rerank scores) linear-norm)
          {:keys [cos-avoid cos-good] :as res} (scorer force-fit-candidate "refactor extract a helper")
          margin (- cos-avoid cos-good)
          linear-32-margin (- (/ 29.2 32.0) (/ 28.7 32.0))
          linear-40-margin (- (/ 29.2 40.0) (/ 28.7 40.0))]
      (is (approx? 1.0 cos-avoid) "the candidate's strongest guard is 1.0")
      (is (approx? (/ 27.9 29.2) cos-good)
          "APPLIED cos-good (:good-when alone) relative to the candidate's own max")
      (is (approx? (/ 28.7 29.2) (:cos-good-with-content res))
          "the with-content reading is still computed, as shadow")
      (is (pos? margin) "the force-fit shape: avoid beats good")
      (is (> margin linear-32-margin) "wider than /32 linear")
      (is (> margin linear-40-margin) "wider than /40 linear (the old default)")))
  (testing "genuinely-separated raw scores: relative contrast is large"
    (let [scores {avoid-guard 30.0 good-summary 15.0 good-when 12.0}
          scorer (dp/colbert-scorer nil dp/default-penalty-config
                                    (stub-rerank scores) linear-norm)
          {:keys [cos-avoid cos-good]} (scorer force-fit-candidate "task")]
      (is (approx? 1.0 cos-avoid))
      ;; CC-20: applied :good-when => 12/30 (the with-content 15/30 is shadow).
      (is (approx? 0.4 cos-good))))
  (testing "no guards => {0,0} and NO rerank call"
    (let [calls (atom 0)
          scorer (dp/colbert-scorer nil dp/default-penalty-config
                                    (stub-rerank {} calls) linear-norm)]
      (is (= {:cos-avoid 0.0 :cos-good 0.0} (scorer {} "task")))
      (is (zero? @calls) "empty doc set => no round-trip"))))

(deftest batch-relative-batch-scorer-is-results-neutral-vs-per-candidate
  (testing "the batched hot path (ONE physical call over ALL candidates' guards)
            yields IDENTICAL cosines to the per-candidate scorer — each candidate
            is normalized by ITS OWN guard max, not the global call max"
    ;; Candidate B's guards all score BELOW A's — the load-bearing case: if the
    ;; batch normalized by the GLOBAL max (A's 29.2), B's cosines would shrink.
    (let [b-avoid "a one-line config tweak"
          b-summary "build/extract/refactor code structure into a helper"
          b-good "extract a pure helper from a handler"
          cand-b {:document-id "B" :avoid-when [b-avoid] :content b-summary
                  :strengths [{:good-when b-good}]}
          scores {avoid-guard 29.2 good-summary 28.7 good-when 27.9
                  b-avoid 20.0 b-summary 25.0 b-good 24.0}
          candidates [force-fit-candidate cand-b]
          calls (atom 0)
          rerank (stub-rerank scores calls)
          batch-lookup ((dp/batch-colbert-scorer nil dp/default-penalty-config
                                                 rerank linear-norm)
                        candidates "task")
          per-scorer (dp/colbert-scorer nil dp/default-penalty-config
                                        rerank linear-norm)]
      (is (= 1 @calls) "the batch factory made exactly ONE call")
      (doseq [c candidates]
        (is (= (per-scorer c "task") (batch-lookup c))
            (str (:document-id c) ": batch cosines == per-candidate cosines")))
      (testing "B is normalized by B's OWN guard max, not A's 29.2
                (CC-20: the applied variant is :good-when, so B's own max is
                its good-when score 24.0 — the with-content shadow still
                normalizes by 25.0)"
        (let [{:keys [cos-avoid cos-good] :as res} (batch-lookup cand-b)]
          (is (approx? (/ 20.0 24.0) cos-avoid))
          (is (approx? 1.0 cos-good))
          (is (approx? (/ 20.0 25.0) (:cos-avoid-with-content res))
              "the with-content shadow keeps the 25.0 normalizer"))))))

(deftest explicit-linear-and-sigmoid-configs-keep-their-exact-old-behavior
  (testing "an EXPLICIT {:method :linear :max-score 40.0} still normalizes each
            group max by the fixed ceiling through norm-fn (pre-Slice-3 behavior)"
    ;; CC-20: the pre-flip positive signal is stated EXPLICITLY — this test
    ;; pins the exact pre-Slice-3 behavior, and that behavior applied the
    ;; :content+good-when reading (inheriting the flipped default would
    ;; silently change which cosines these historical values describe).
    (let [scores {avoid-guard 20.0 good-summary 12.0 good-when 10.0}
          cfg {:scorer :colbert :colbert-norm {:max-score 40.0 :method :linear}
               :positive-signal :content+good-when}
          scorer (dp/colbert-scorer nil cfg (stub-rerank scores)
                                    (fn [score & {:keys [max-score method]}]
                                      (is (= 40.0 max-score) "norm-fn receives the explicit ceiling")
                                      (is (= :linear method) "norm-fn receives the explicit method")
                                      (/ (double score) max-score)))
          {:keys [cos-avoid cos-good]} (scorer force-fit-candidate "task")]
      (is (approx? 0.5 cos-avoid) "avoid 20/40")
      (is (approx? 0.3 cos-good) "good MAX(12,10)/40"))
    (testing "and the batch scorer honors the same explicit config"
      (let [scores {avoid-guard 20.0 good-summary 12.0 good-when 10.0}
            cfg {:scorer :colbert :colbert-norm {:max-score 40.0 :method :linear}
                 :positive-signal :content+good-when}
            lookup ((dp/batch-colbert-scorer nil cfg (stub-rerank scores)
                                             (fn [score & {:keys [max-score]}]
                                               (/ (double score) max-score)))
                    [force-fit-candidate] "task")]
        ;; CC-16 (ADR 0026 + 0027) — WIDENED FROM EXACT-MAP TO SUBSET, SAME
        ;; CONTRACT. The applied cosines are unchanged (0.5 / 0.3); what changed
        ;; is that every scorer now ALSO reports both positive-signal variants,
        ;; because ADR 0027 requires the gate to be able to say what it is doing.
        ;; `=` on the whole map asserted "the scorer reports nothing else", which
        ;; is not the contract under test here — the contract is "an explicit
        ;; :linear config still normalizes the group max by the fixed ceiling".
        ;; The shadow keys are pinned separately below so widening loses nothing.
        (is (= {:cos-avoid 0.5 :cos-good 0.3}
               (select-keys (lookup force-fit-candidate) [:cos-avoid :cos-good]))
            "the APPLIED cosines are the exact pre-Slice-3 fixed-ceiling values")
        (is (= {:cos-avoid-with-content 0.5 :cos-good-with-content 0.3
                :cos-avoid-sans-content 0.5 :cos-good-sans-content 0.25
                :positive-signal :content+good-when}
               (select-keys (lookup force-fit-candidate)
                            [:cos-avoid-with-content :cos-good-with-content
                             :cos-avoid-sans-content :cos-good-sans-content
                             :positive-signal]))
            "and BOTH positive-signal variants are reported: dropping :content
             lowers cos-good from MAX(12,10)/40 to 10/40, the direction ADR 0026
             predicts, while the applied variant is untouched")))))

(deftest penalize-candidates-default-path-fires-on-relative-force-fit
  (testing "end-to-end through the production hot path (4-arity, default config,
            injected via with-redefs on the resolver seam): a candidate whose
            avoid guard RELATIVELY dominates its good signals gets penalized and
            re-sorted below the clean candidates.

            CC-20 — SAME CONTRACT, POPULATION-SIZED FIXTURE. The default gate
            is now :z-score, which judges each candidate against the pass's
            own contrast population (min-population 5), so the fixture carries
            seven clean candidates + the force-fit instead of one of each. The
            clean candidates share one guard set (allowed — the batch call
            dedups strings, the per-candidate lookup does not care), giving a
            tight negative-contrast population the force-fit is a genuine
            outlier of."
    (let [clean-avoid "a one-line config tweak"
          clean-good "extract a pure helper from a handler"
          clean-summary "extract a pure helper from a handler, refactor structure"
          cleans (mapv (fn [i]
                         {:document-id (str "clean" i) :fitness-score 0.90
                          :avoid-when [clean-avoid] :content clean-summary
                          :strengths [{:good-when clean-good}]})
                       (range 7))
          force-fit (assoc force-fit-candidate :fitness-score 0.95)
          ;; force-fit (applied :good-when): avoid 30 vs good-when 22
          ;;   -> cos-avoid 1.0, cos-good 22/30 -> contrast +0.267 (the outlier).
          ;; cleans: good 29 vs avoid 20 -> cos-good 1.0 -> contrast 20/29-1 = -0.31.
          scores {avoid-guard 30.0 good-summary 24.0 good-when 22.0
                  clean-avoid 20.0 clean-summary 29.0 clean-good 29.0}
          rerank (stub-rerank scores)
          resolver (constantly {:rerank (fn [_ctx opts] (rerank opts))
                                :normalize linear-norm})]
      (binding [dp/*colbert-resolver* resolver]
        (let [out (dp/penalize-candidates nil (conj cleans force-fit) "task")
              by-id (into {} (map (juxt :document-id identity)) out)]
          (is (pos? (:domain-penalty (get by-id "rename")))
              "the relative force-fit contrast fires the penalty")
          (doseq [i (range 7)]
            (is (= 0.0 (:domain-penalty (get by-id (str "clean" i))))
                "every clean candidate is untouched (cos-good tops its call)"))
          (is (= "rename" (:document-id (last out)))
              "the penalized shape-winner drops below the clean candidates"))))))

;; =============================================================================
;; Slice 4b -> CC-20: the gate-evidenced :margin retune is FROZEN, not shipped.
;; =============================================================================

(deftest margin-retune-is-frozen-in-the-legacy-absolute-defaults
  ;; CC-20 (ADR 0027) — SUPERSEDES the Slice-4b default pin this deftest used
  ;; to be. The 0.010 margin was measured inert on the shipped signal (0/559
  ;; at the shipped limit) and firing on per-query SCALE where it did fire
  ;; (6/559, all on the one longest-query task), so the shipped default gate
  ;; is now :z-score (see cc20-gate-form-test for the banked-cells contract).
  ;; The Slice-4b calibration itself is preserved verbatim as the frozen
  ;; :absolute-form fallback — explicit-:margin configs keep their meaning.
  (testing "the shipped default carries NO absolute margin"
    (is (not (contains? dp/default-penalty-config :margin)))
    (is (= :z-score (get-in dp/default-penalty-config [:gate :form]))))
  (testing "the Slice-4b values are frozen, byte for byte, in the legacy
            absolute defaults"
    (is (= 0.010 (:margin dp/legacy-absolute-defaults)))
    (is (= 10.0 (:penalty-scale dp/legacy-absolute-defaults))))
  (testing "a probe-shaped force-fit (the witnessed +0.016 margin) FIRES under
            the frozen absolute arithmetic (domain-penalty's bare arity)"
    (is (pos? (dp/domain-penalty 0.816 0.800))))
  (testing "the max witnessed clean case (+0.0025) does NOT fire under it"
    (is (zero? (dp/domain-penalty 0.8025 0.800)))))
