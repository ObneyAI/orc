(ns mt7c-acceptance
  "MT-7c — acceptance for the vocabulary-binding line (ADR-0001): two clean solo
   comprehensive O*NET builds through the FULL composed pipeline, gated on
   within-run integrity + the semantic CQ-gate; cross-run structural sameness is
   a NON-gating diagnostic (two runs may discover different valid vocabularies).

   `mt7c-acceptance-verdict` is the PURE, testable verdict (the /tdd deliverable);
   the two live builds + the capability A2-vs-B table are the /inspect-orc.

   USAGE:
     verdict tests: clj -M:dev:test -e \"(require '[mt7c-acceptance]) (clojure.test/run-tests 'mt7c-acceptance)\"
     live:          clj -J-Xmx6g -M:dev:test -m mt7c-acceptance"
  (:require [eb12-graph-b-central-evolver :as h]
            [mt5-acceptance :as mt5]
            [ai.obney.orc.ontology.core.vocabulary-binding :as vb]
            [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

;; ===========================================================================
;; The PURE verdict (the /tdd deliverable)
;; ===========================================================================

(defn scheme-of [uri] (let [u (str uri) i (str/index-of u "/")] (if i (subs u 0 i) u)))

(defn variant-scheme-splits
  "The 7a-fixed fragmentation signature: pairs of DISTINCT scheme spellings that
   normalize (vb/normalize-name) to ONE string (e.g. `Skill` vs `skill`,
   `Job Element` vs `job-element`). Returns [{:normalized … :schemes [<spelling> …]} …]."
  [schemes]
  (->> (keys schemes)
       (group-by vb/normalize-name)
       (filter #(> (count (val %)) 1))
       (mapv (fn [[n ss]] {:normalized n :schemes (vec ss)}))))

(defn mt7c-acceptance-verdict
  "PURE pass/fail over TWO measured runs. Each run:
     {:status <kw> :schemes {<scheme-string> <count>} :cq-verdict [{:verdict <kw>} …]
      :containers-processed n :containers-failed n}
   Gating (per run): honest-terminal; no variant-scheme splits; CQ-gate answered
   (≥1 :pass — the GC-11c/ADR-0001 criterion); not a hollow pass (failed containers
   < half of processed). NON-gating diagnostics: cross-run structural sameness
   (vocabularies may differ), scheme counts."
  [{:keys [runs]}]
  (let [per-run
        (map-indexed
         (fn [i {:keys [status schemes cq-verdict containers-processed containers-failed]}]
           (let [splits (variant-scheme-splits (or schemes {}))
                 passes (count (filter #(= :pass (:verdict %)) cq-verdict))
                 failed (or containers-failed 0)
                 processed (or containers-processed 0)]
             [{:criterion (keyword (str "run" (inc i) "-honest-terminal"))
               :pass? (contains? #{:complete :failed-cq} status)
               :detail (str "status=" status)}
              {:criterion (keyword (str "run" (inc i) "-no-variant-scheme-splits"))
               :pass? (empty? splits)
               :detail (if (empty? splits) (str (count (or schemes {})) " schemes, no splits")
                           (str "SPLITS: " (pr-str splits)))}
              {:criterion (keyword (str "run" (inc i) "-cq-gate-answered"))
               :pass? (pos? passes)
               :detail (str passes "/" (count cq-verdict) " CQ :pass verdicts")}
              {:criterion (keyword (str "run" (inc i) "-not-hollow"))
               :pass? (or (zero? processed) (< failed (max 1 (quot processed 2))))
               :detail (str failed "/" processed " containers failed (a mass-failure "
                            "'unfragmented' run is hollow)")}]))
         runs)
        sameness {:criterion :cross-run-structural-sameness
                  :gating? false
                  :pass? (= 1 (count (distinct (map (comp set keys :schemes) runs))))
                  :detail (str "run schemes: "
                               (pr-str (mapv (comp vec sort keys :schemes) runs))
                               " [NON-GATING — vocabularies may legitimately differ (ADR-0001)]")}
        criteria (vec (concat (apply concat per-run) [sameness]))]
    {:pass? (every? :pass? (remove #(false? (:gating? %)) criteria))
     :reasons criteria}))

;; ===========================================================================
;; TDD — the verdict on fixtures
;; ===========================================================================

(def ^:private good-run
  {:status :failed-cq
   :schemes {"occupation" 1016 "skill" 120 "skillobservation" 900}
   :cq-verdict [{:verdict :pass} {:verdict :fail}]
   :containers-processed 12 :containers-failed 1})

(deftest verdict-passes-two-clean-runs-even-with-different-vocabularies
  (testing "both runs internally clean + CQ-answered → PASS, even though the two
            runs' vocabularies differ (structural sameness is NON-gating)"
    (let [run2 (assoc good-run :schemes {"occupation" 1016 "workplaceelement" 400})
          v (mt7c-acceptance-verdict {:runs [good-run run2]})]
      (is (:pass? v))
      (is (false? (:pass? (first (filter #(= :cross-run-structural-sameness (:criterion %))
                                         (:reasons v)))))
          "the sameness diagnostic reports the difference — without gating"))))

(deftest verdict-fails-on-a-variant-scheme-split
  (testing "a case/variant split (Skill vs skill) — the 7a fragmentation signature —
            fails that run"
    (let [bad (update good-run :schemes assoc "Skill" 83)
          v (mt7c-acceptance-verdict {:runs [bad good-run]})]
      (is (not (:pass? v)))
      (is (not (:pass? (first (filter #(= :run1-no-variant-scheme-splits (:criterion %))
                                      (:reasons v)))))))))

(deftest verdict-fails-when-the-cq-gate-never-answered
  (testing "zero :pass CQ verdicts → fail (the semantic gate is THE acceptance)"
    (let [bad (assoc good-run :cq-verdict [{:verdict :fail} {:verdict :unknown}])
          v (mt7c-acceptance-verdict {:runs [good-run bad]})]
      (is (not (:pass? v))))))

(deftest verdict-fails-a-hollow-mass-failure-run
  (testing "an 'unfragmented' run achieved by most containers FAILING is hollow"
    (let [bad (assoc good-run :containers-failed 7 :containers-processed 12)
          v (mt7c-acceptance-verdict {:runs [bad good-run]})]
      (is (not (:pass? v))))))

(deftest verdict-fails-a-crashed-run
  (testing "a crash/timeout status is never an acceptable terminal"
    (let [bad (assoc good-run :status :error)
          v (mt7c-acceptance-verdict {:runs [bad good-run]})]
      (is (not (:pass? v))))))

;; ===========================================================================
;; The live driver (the /inspect-orc)
;; ===========================================================================

(defn- measure-run [r]
  (let [concepts (get r (keyword "eb12-graph-b-central-evolver" "concepts"))]
    {:status (:status r)
     :schemes (frequencies (map (comp scheme-of :uri) concepts))
     :cq-verdict (vec (:cq-verdict r))
     :containers-processed (get-in r [:build-result :containers-processed])
     :containers-failed (get-in r [:build-result :containers-failed])
     :concept-count (count concepts)
     :concepts concepts}))

(defn -main [& _]
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY required (env only)" {})))
  (h/register-openrouter! h/default-model)
  (println "=== MT-7c ACCEPTANCE — comprehensive O*NET x2 (full composed pipeline) ===")
  (let [a2 (mt5/a2-baseline)
        do-run (fn [n]
                 (println (str "\n--- RUN " n " (solo, bounded) ---"))
                 (let [r (h/run! {:only [:onet] :max-containers 12 :store :sqlite
                                  :budget {:max-iterations 8 :total-budget-ms 900000 :max-retries 3}
                                  :evolver-config {:max-iterations 1}})
                       m (measure-run r)]
                   (println "  status:" (:status m) " concepts:" (:concept-count m))
                   (println "  schemes:" (into (sorted-map) (:schemes m)))
                   (println "  cq: " (mapv :verdict (:cq-verdict m)))
                   m))
        run1 (do-run 1)
        run2 (do-run 2)
        v (mt7c-acceptance-verdict {:runs [(dissoc run1 :concepts) (dissoc run2 :concepts)]})]
    (println "\n=== VERDICT ===  pass?" (:pass? v))
    (doseq [r (:reasons v)]
      (println (format "  %-36s %-6s %s" (name (:criterion r)) (str (:pass? r)) (:detail r))))
    ;; capability table vs A2 (report, not gate)
    (println "\n=== CAPABILITY vs A2 (report — structural difference acknowledged) ===")
    (println "  A2 baseline:" (:facet-coverage a2) "over" (:occupation-count a2) "occupations")
    (doseq [[n m] [[1 run1] [2 run2]]]
      (let [b (mt5/measure-b (:concepts m))]
        (println (str "  B run " n ": occ-count=" (:occupation-count b)
                      " junction=" (:junction-node-count b)
                      " facets=" (:facet-coverage b)))))
    (shutdown-agents)
    (System/exit (if (:pass? v) 0 1))))
