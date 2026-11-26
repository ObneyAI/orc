(ns ai.obney.orc.ontology.cc16-positive-signal-test
  "CC-16 (ADR 0026 + ADR 0027): the domain penalty's POSITIVE signal narrows to
   `:good-when`, shipped in two stages with the other variant kept in shadow.

   WHY THIS NAMESPACE EXISTS AT ALL — the standing EL-5 regression contract was
   exercised against a 176-char stub summary. P-B proved that a PRODUCTION
   summary is a ~800-char consolidated paragraph that RESTATES the behavior's
   own avoid-conditions in prose, so under MaxSim the positive signal matches
   the same query tokens the guard does and cancels it. A contract built on the
   stub therefore certifies this change WITHOUT EVER EXERCISING IT. Everything
   below runs against `cc16_production_shaped_contrast.edn`: the real
   `child/rename-move-symbol` body from the 2,713-event production dump, joined
   to the real JVM-native ColBERT MaxSim raw scores P-B measured on it (variant
   :A0 = today's shipped summary-as-key). See that file's :provenance.

   Determinism: the raw scores are a GOLDEN FIXTURE served through the injected
   rerank seam, so no encoder, no bridge, no DJL — but the numbers are real
   measurements, not invented ones. The reproduce-the-shipped-value assertion
   is the harness control: if the stub or the arithmetic drifted, it fails
   before any conclusion is drawn from the shadow numbers.

   The demotion threshold 0.0145 is P-B's measurement on the real 17-candidate
   corpus: the refactor force-fit needs penalty > 0.0145 to fall below the
   runner-up. 'The penalty fires' is NOT success — firing != demoting."
  (:require [ai.obney.orc.ontology.core.logic :as logic]
            [clojure.test :refer [deftest testing is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private fixture
  (-> "cc16_production_shaped_contrast.edn" io/resource slurp edn/read-string))

(def ^:private demotion-threshold
  "P-B, real corpus, real ColBERT: the refactor force-fit needs a penalty
   ABOVE this to fall below the runner-up. Below it the penalty fires and the
   force-fit stays rank #1 — firing without demoting."
  0.0145)

(def ^:private pre-cc20-config
  "CC-20: the EXACT configuration this fixture's measurements were taken
   under — the pre-CC-20 shipped absolute gate (Slice-4b margin retune) with
   the Stage-1 positive signal. The measurement-reproduction and
   absolute-arithmetic assertions below MUST replay under the arithmetic that
   produced the banked numbers; the shipped default is now the CC-20 :z-score
   gate with :positive-signal :good-when (see cc20-gate-form-test for its
   banked-cells contract), under which a single-candidate pass abstains
   structurally — so scoring these one-candidate fixture cells under the NEW
   default would assert nothing about the measurements."
  {:scorer :colbert
   :penalty-scale 10.0
   :margin 0.010
   :penalty-cap 0.6
   :positive-signal :content+good-when
   :colbert-norm {:max-score nil :method :batch-relative}})

(defn- production-candidate
  "The enriched-candidate shape the penalty reads, built from the REAL body."
  []
  (let [b (:body fixture)]
    {:document-id "child/rename-move-symbol"
     :fitness-score 0.95
     :content (:summary b)
     :avoid-when (vec (:avoid-when b))
     :strengths (vec (:strengths b))
     :weaknesses (vec (:weaknesses b))}))

(defn- golden-rerank
  "A rerank-fn serving the REAL measured MaxSim raw scores for one cell.
   Throws on a document the fixture has no measurement for, so the test can
   never silently score an unmeasured string as 0.0."
  [cell seen]
  (fn [{:keys [documents]}]
    (swap! seen into documents)
    (mapv (fn [d]
            (if-let [s (get (:raw-scores cell) d)]
              {:content d :score s}
              (throw (ex-info "golden fixture has no measured score for this document"
                              {:document d}))))
          documents)))

(defn- score-cell
  "Run the PRODUCTION hot path (penalize-candidates, 4-arity) for one fixture
   cell under `config`, returning the stamped candidate + the documents the
   single rerank call actually received."
  ([cell] (score-cell cell pre-cc20-config))
  ([cell config]
   (let [seen (atom #{})
         rerank (golden-rerank cell seen)
         resolver (constantly {:rerank (fn [_ctx opts] (rerank opts))
                               :normalize (fn [score & _] score)})]
     (binding [logic/*colbert-resolver* resolver]
       {:stamped (first (logic/penalize-candidates nil [(production-candidate)]
                                                (:qid cell) config))
        :documents @seen}))))

(defn- approx? [expected actual eps]
  (and (some? actual) (< (Math/abs (- (double expected) (double actual))) eps)))


(deftest golden-fixture-reproduces-the-shipped-measured-penalty
  (testing "the fixture + the shipped arithmetic reproduce P-B's REAL measured
            cos-avoid / cos-good / penalty for every cell (harness control:
            if this fails, every shadow number below is meaningless)"
    (println "\n=== CC-16 golden fixture: N =" (count (:cells fixture))
             "cells, production body summary chars ="
             (count (:summary (:body fixture))) "===")
    (is (= 3 (count (:cells fixture))) "N is stated, not assumed")
    (is (= 796 (count (:summary (:body fixture))))
        "the fixture body carries the PRODUCTION 796-char summary, not the 176-char stub")
    (doseq [cell (:cells fixture)]
      (let [{:keys [stamped documents]} (score-cell cell)
            m (:measured-with-content cell)]
        (is (= (set (keys (:raw-scores cell))) documents)
            (str (:qid cell) ": the single rerank call saw EXACTLY the measured guard set"))
        (is (approx? (:cos-avoid m) (:cos-avoid-with-content stamped) 1e-4)
            (str (:qid cell) ": cos-avoid reproduces the measurement"))
        (is (approx? (:cos-good m) (:cos-good-with-content stamped) 1e-4)
            (str (:qid cell) ": cos-good reproduces the measurement"))
        (is (approx? (:penalty m) (:domain-penalty-with-content stamped) 1e-3)
            (str (:qid cell) ": penalty reproduces the measurement"))))))


(deftest production-shaped-content-cancels-the-guard-and-good-when-restores-it
  (testing "on the REAL production body, the shipped positive signal (:content
            + :good-when) leaves the refactor force-fit UNDEMOTED, while the
            ADR-0026 signal (:good-when alone) clears P-B's demotion threshold.
            This is the contract the 176-char stub could not express."
    (let [refactor-cells (filter #(str/starts-with? (:qid %) "refactor/")
                                 (:cells fixture))]
      (is (= 2 (count refactor-cells)) "N refactor force-fit cells")
      (println (format "%-22s %12s %12s | %12s %12s"
                       "qid" "p(with)" "demotes?" "p(sans)" "demotes?"))
      (doseq [cell refactor-cells]
        (let [{:keys [stamped]} (score-cell cell)
              pw (:domain-penalty-with-content stamped)
              ps (:domain-penalty-sans-content stamped)]
          (println (format "%-22s %12.6f %12s | %12.6f %12s"
                           (:qid cell) (double (or pw -1.0))
                           (str (and pw (> pw demotion-threshold)))
                           (double (or ps -1.0))
                           (str (and ps (> ps demotion-threshold)))))
          (is (some? pw) (str (:qid cell) ": the with-content variant is computed and stamped"))
          (is (some? ps) (str (:qid cell) ": the sans-content variant is computed and stamped"))
          (is (not (> (double pw) demotion-threshold))
              (str (:qid cell) ": PINS THE DEFECT — the shipped positive signal cannot demote "
                   "the force-fit on production-shaped content (p=" pw " <= " demotion-threshold ")"))
          (is (> (double ps) demotion-threshold)
              (str (:qid cell) ": the ADR-0026 signal DEMOTES the force-fit "
                   "(p=" ps " > " demotion-threshold ")")))))))

(deftest production-shaped-content-pins-cos-good-at-one-and-good-when-frees-it
  (testing "the measured mechanism: with :content in the positive signal the
            document wins its own call's normalizer on the non-force-fit query,
            pinning cos-good at exactly 1.0; dropping it frees the contrast"
    (let [cell (first (filter #(= "add-function/oov" (:qid %)) (:cells fixture)))
          {:keys [stamped]} (score-cell cell)]
      (is (= 1.0 (:cos-good-with-content stamped))
          "cos-good is pinned at EXACTLY 1.0 — the key beat its own guard for the normalizer")
      (is (< (:cos-good-sans-content stamped) 1.0)
          "with :good-when alone the positive signal no longer wins the normalizer")
      (is (> (- (:cos-avoid-sans-content stamped) (:cos-good-sans-content stamped))
             (- (:cos-avoid-with-content stamped) (:cos-good-with-content stamped)))
          "the contrast moves in the direction ADR 0026 predicts (avoid gains on good)"))))


(deftest positive-strings-is-the-good-when-signal
  (let [c (production-candidate)]
    (testing "ADR 0026: positive-strings is the :good-when strings ALONE —
              :good-when IS 'the use-case description' ADR 0016's contrast
              names; :content is the whole document"
      (is (= (vec (keep :good-when (:strengths (:body fixture))))
             (logic/positive-strings c)))
      (is (not (some #{(:content c)} (logic/positive-strings c)))
          "the indexed description is NOT a positive signal"))
    (testing "legacy-positive-strings keeps the pre-ADR-0026 reading so the
              penalty can report BOTH variants (ADR 0027 shadow mode)"
      (is (= (:content c) (first (logic/legacy-positive-strings c))))
      (is (every? (set (logic/legacy-positive-strings c)) (logic/positive-strings c))
          "the ADR-0026 signal is a SUBSET of the legacy one — so one rerank
           call over the legacy set scores both variants"))
    (testing "scored-strings is what one rerank call must cover: avoid ++ the
              union of both positive readings"
      (is (= (set (concat (logic/avoid-strings c) (logic/legacy-positive-strings c)))
             (set (logic/scored-strings c))))
      (is (= (count (logic/scored-strings c)) (count (distinct (logic/scored-strings c))))
          "deduped"))))


(deftest the-shipped-default-applies-good-when-and-shadows-with-content
  (doseq [cell (:cells fixture)]
    (let [{:keys [stamped]} (score-cell cell logic/default-penalty-config)]
      (testing (str (:qid cell) " — the APPLIED keys equal the sans-content variant")
        (is (= :good-when (:positive-signal stamped))
            "ADR 0026 Stage 2, flipped by CC-20")
        (is (= (:cos-avoid-sans-content stamped) (:cos-avoid stamped)))
        (is (= (:cos-good-sans-content stamped) (:cos-good stamped)))
        (is (= (:domain-penalty-sans-content stamped) (:domain-penalty stamped)))
        (is (= (logic/apply-penalty 0.95 (:domain-penalty-sans-content stamped))
               (:fitness-score stamped))
            "fitness is penalized by the APPLIED variant only")))))

(deftest the-pre-cc20-config-still-applies-the-stage-1-signal
  (doseq [cell (:cells fixture)]
    (let [{:keys [stamped]} (score-cell cell)]
      (testing (str (:qid cell) " — under the explicit pre-CC-20 config the "
                    "APPLIED keys equal the with-content variant")
        (is (= :content+good-when (:positive-signal stamped)))
        (is (= (:cos-avoid-with-content stamped) (:cos-avoid stamped)))
        (is (= (:cos-good-with-content stamped) (:cos-good stamped)))
        (is (= (:domain-penalty-with-content stamped) (:domain-penalty stamped)))
        (is (= (logic/apply-penalty 0.95 (:domain-penalty-with-content stamped))
               (:fitness-score stamped))
            "fitness is penalized by the APPLIED variant only")))))

(deftest the-positive-signal-knob-selects-which-variant-is-applied
  (let [cell (first (filter #(= "refactor/oov" (:qid %)) (:cells fixture)))
        stage-1 (:stamped (score-cell cell pre-cc20-config))
        stage-2 (:stamped (score-cell cell (assoc pre-cc20-config
                                                  :positive-signal :good-when)))]
    (testing "flipping :positive-signal to :good-when applies the sans-content
              variant — and BOTH variants stay stamped either way (ADR 0027:
              silence must stay distinguishable from absence)"
      (is (= :good-when (:positive-signal stage-2)))
      (is (= (:domain-penalty-sans-content stage-2) (:domain-penalty stage-2)))
      (is (= (:domain-penalty-with-content stage-1) (:domain-penalty-with-content stage-2))
          "the shadow variant is unaffected by which one is applied")
      (is (= (:domain-penalty-sans-content stage-1) (:domain-penalty-sans-content stage-2))
          "and so is the other one")
      (is (> (:domain-penalty stage-2) (:domain-penalty stage-1))
          "the flip is what wakes the penalty on production-shaped content"))))


(deftest narrowing-the-positive-signal-does-not-widen-what-enforces
  (testing "a CLAIM-BACKED candidate whose enforcing set is PRESENT-AND-EMPTY
            cannot be penalized under EITHER positive-signal variant, however
            low the positive signal goes"
    (let [cell (first (:cells fixture))
          cand (assoc (production-candidate)
                      :ai.obney.orc.ontology.core.domain-penalty/enforcing-avoid-when [])
          seen (atom #{})
          rerank (golden-rerank cell seen)
          resolver (constantly {:rerank (fn [_ctx opts] (rerank opts))
                                :normalize (fn [score & _] score)})
          stamped (binding [logic/*colbert-resolver* resolver]
                    (first (logic/penalize-candidates nil [cand] (:qid cell)
                                                   pre-cc20-config)))]
      (is (= [] (logic/avoid-strings cand))
          "present-and-empty means 'has claims, none has earned enforcement yet'")
      (is (= 0.0 (:cos-avoid-with-content stamped)))
      (is (= 0.0 (:cos-avoid-sans-content stamped)))
      (is (= 0.0 (:domain-penalty-with-content stamped)))
      (is (= 0.0 (:domain-penalty-sans-content stamped))
          "an unvalidated claim still cannot suppress retrieval — the ADR-0026
           narrowing does not reach the negative signal")
      (is (= 0.95 (:fitness-score stamped)) "and the fitness is untouched"))))

(deftest the-rerank-contract-is-one-call-with-delta-only-results
  (let [cell (first (:cells fixture))
        cand (assoc (production-candidate) :reasoning "shape" :document_metadata {:granularity "x"})
        seen (atom #{})
        rerank (golden-rerank cell seen)
        calls (atom 0)
        resolver (constantly {:rerank (fn [_ctx opts] (swap! calls inc) (rerank opts))
                              :normalize (fn [score & _] score)})
        stamped (binding [logic/*colbert-resolver* resolver]
                  (first (logic/penalize-candidates nil [cand] (:qid cell)
                                                 logic/default-penalty-config)))]
    (is (= 1 @calls) "EXACTLY one rerank call for the pass — the shadow adds none")
    (is (= (set (keys (:raw-scores cell))) @seen)
        "and exactly the pre-CC-16 document set — the shadow adds no documents either")
    (doseq [k [:document-id :reasoning :document_metadata :content :avoid-when :strengths]]
      (is (= (get cand k) (get stamped k)) (str k " survives the pass unchanged")))
    (is (<= 0.0 (:domain-penalty stamped) (:penalty-cap logic/default-penalty-config))
        "the fitness delta stays bounded by the cap")))


(deftest penalty-pass-report-states-the-firing-rate-and-contrast-distribution
  (let [cells (:cells fixture)
        stamped (mapv #(:stamped (score-cell %)) cells)
        report (logic/penalty-pass-report stamped pre-cc20-config)]
    (testing "the report states its N, its gate form, and the knobs it judged against"
      (is (= (count cells) (:candidate-count report)))
      (is (= :content+good-when (:applied-positive-signal report)))
      (is (= :absolute (:gate-form report)))
      (is (= (:margin pre-cc20-config) (:margin report)))
      (is (= (:penalty-scale pre-cc20-config) (:penalty-scale report)))
      (is (= (:penalty-cap pre-cc20-config) (:penalty-cap report))))
    (testing "BOTH variants report a firing count, a firing rate, and the
              contrast + penalty distributions CC-20 will derive the penalty's
              form and value from"
      (doseq [variant [:content+good-when :good-when]]
        (let [v (get-in report [:variants variant])]
          (is (some? v) (str variant " is reported"))
          (is (integer? (:fired v)) (str variant ": a firing COUNT, not a boolean"))
          (is (= (/ (double (:fired v)) (count cells)) (:fired-rate v))
              (str variant ": the rate is the count over N"))
          (doseq [dist [:contrast :penalty]
                  k [:min :p50 :p95 :max :mean]]
            (is (number? (get-in v [dist k]))
                (str variant " " dist " " k " is reported"))))))
    (testing "and the report DISCRIMINATES: on production-shaped content the
              shipped signal is inert where the ADR-0026 signal is not"
      (is (< (get-in report [:variants :content+good-when :fired])
             (get-in report [:variants :good-when :fired]))
          "a gate that never fires and a gate that is not there must not look identical"))))
