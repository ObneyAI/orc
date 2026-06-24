(ns gc3-measurement-test
  "GC-3 — measurement hardening + graph-health fragmentation check.

   Dev-harness test ns (development/src is on the :dev path, NOT a polylith
   brick — there is no brick gate for it). Run with:

     clj -M:dev:test -e \"(require 'gc3-measurement-test 'clojure.test) \\
       (let [r (clojure.test/run-tests 'gc3-measurement-test)] \\
         (println :SUMMARY r) (System/exit (+ (:fail r) (:error r))))\"

   Cycle 1: uri-kind is convention-agnostic (scheme = before first : OR /).
   Cycle 2: graph-health flags same-identity-under-≥2-schemes fragmentation.
   Cycle 3 (DECISIVE): re-analyze the REAL MC-7 artifact — hardened graph-stats
            reports the real slash-scheme kinds with sane counts AND
            :graph-health/:fragmented? is true."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [eb12-graph-b-central-evolver :as b]))

;; ---------------------------------------------------------------------------
;; Cycle 1 — classifier is convention-agnostic
;; ---------------------------------------------------------------------------

(deftest uri-kind-is-convention-agnostic
  (testing "slash-scheme canonical URIs classify by their scheme (the bug)"
    (is (= :programofstudy (b/uri-kind "programofstudy/01.0901")))
    (is (= :occupation (b/uri-kind "occupation/25-1041")))
    (is (= :institution (b/uri-kind "institution/236753"))))
  (testing "colon-scheme legacy/degraded URIs still classify by their scheme"
    (is (= :degree_program (b/uri-kind "degree_program:22:52")))
    (is (= :field_of_study (b/uri-kind "field_of_study:01.0901"))))
  (testing "scheme is lower-cased"
    (is (= :occupation (b/uri-kind "Occupation/25-1041"))))
  (testing "a URI with NEITHER separator → :other"
    (is (= :other (b/uri-kind "bareword")))
    (is (= :other (b/uri-kind ""))))
  (testing "the FIRST separator wins for a mixed a:b/c (colon before slash)"
    (is (= :a (b/uri-kind "a:b/c"))))
  (testing "the FIRST separator wins for a mixed a/b:c (slash before colon)"
    (is (= :a (b/uri-kind "a/b:c")))))

;; ---------------------------------------------------------------------------
;; Cycle 2 — graph-health flags same-identity-under-≥2-schemes fragmentation
;; (synthetic, NO domain field names — Discipline 12)
;; ---------------------------------------------------------------------------

(deftest graph-health-flags-fragmentation
  (testing "same identity-tail under TWO distinct schemes → :fragmented? true"
    (let [concepts [{:uri "thing/42"} {:uri "thing-alt:42"} {:uri "other/99"}]
          health (b/graph-health concepts)]
      (is (true? (:fragmented? health)))
      (is (seq (:fragmented-identities health)))
      (testing "the offending identity (42) is surfaced with both schemes"
        (let [hit (first (filter #(= "42" (:identity %))
                                 (:fragmented-identities health)))]
          (is (some? hit) "the shared identity-tail 42 is reported")
          (is (= #{:thing :thing-alt} (set (:schemes hit)))
              "both distinct schemes are surfaced")))))
  (testing "a clean single-scheme set flags NOTHING"
    (let [concepts [{:uri "thing/1"} {:uri "thing/2"} {:uri "thing/3"}]
          health (b/graph-health concepts)]
      (is (false? (:fragmented? health)))
      (is (empty? (:fragmented-identities health)))))
  (testing "distinct identities under distinct schemes is NOT fragmentation"
    ;; thing/1 and other:2 share no identity-tail → clean
    (let [concepts [{:uri "thing/1"} {:uri "other:2"}]
          health (b/graph-health concepts)]
      (is (false? (:fragmented? health))))))

;; ---------------------------------------------------------------------------
;; Cycle 3 — DECISIVE real-artifact re-analysis (Discipline 4)
;; The real MC-7 fragmented graph (1.6M EDN). With the :-only classifier every
;; slash-scheme URI bucketed :other and the analyst read "≈1 institution / no
;; occupations". The hardened classifier must report the REAL slash-scheme kinds
;; with sane LOOSE lower-bounds, and graph-health must FLAG the artifact as
;; fragmented (it genuinely is — two subgraphs minted under split conventions).
;; ---------------------------------------------------------------------------

(def artifact-path
  "docs/build-timeline/live-verify/EB12-graph-b-central-evolver-artifact.edn")

(deftest real-artifact-reanalysis-is-decisive
  (let [f (io/file artifact-path)]
    (is (.exists f) (str "real MC-7 artifact present at " artifact-path))
    (when (.exists f)
      (let [artifact (edn/read-string (slurp f))
            concepts (:concepts artifact)
            by-kind (frequencies (map #(b/uri-kind (:uri %)) concepts))
            health (b/graph-health concepts)]
        (testing "the artifact actually has concepts to analyze"
          (is (> (count concepts) 1000)
              (str "real artifact concept count: " (count concepts))))
        (testing "hardened classifier reports the REAL slash-scheme kinds (not :other)"
          ;; the real kinds are the artifact's OWN data — institution/, occupation/.
          (is (> (get by-kind :institution 0) 1000)
              (str "institutions (known ≈1456): " (get by-kind :institution 0)
                   " — full by-kind: " (pr-str by-kind)))
          (is (> (get by-kind :occupation 0) 100)
              (str "occupations (known ≈581): " (get by-kind :occupation 0)
                   " — full by-kind: " (pr-str by-kind))))
        (testing "graph-health FLAGS the real fragmented artifact"
          (is (true? (:fragmented? health))
              (str "fragmented-identity-count: " (:fragmented-identity-count health)
                   " sample: " (pr-str (take 3 (:fragmented-identities health))))))
        ;; surface the contrast in the run log (not an assertion)
        (println "\n[GC-3 real-artifact] hardened :concepts-by-kind =>")
        (println (pr-str (into (sorted-map-by (fn [a c] (compare [(by-kind c) c] [(by-kind a) a]))) by-kind)))
        (println "[GC-3 real-artifact] :graph-health :fragmented? =>" (:fragmented? health)
                 " fragmented-identity-count:" (:fragmented-identity-count health))
        (println "[GC-3 real-artifact] sample fragmented identities =>")
        (println (pr-str (take 5 (:fragmented-identities health))))))))
