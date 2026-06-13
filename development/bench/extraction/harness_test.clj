(ns extraction.harness-test
  "S16 — Extraction bench harness tests.

   Discipline:
   - All assertions go through the harness's PUBLIC interface
     (`list-fixtures`, `load-fixture`, `run-fixture!`, `run-all!`,
     `passes-G2?`). No internal helper is asserted against.
   - Real Grain in-memory event store is created INSIDE the harness
     per `run-fixture!`. No mocked store. The judge is the harness's
     controlled `always-pass-judge` — production live-verify wires a
     real LLM judge.
   - Adversarial tests assert the failure modes that the gate's
     QUALITY rests on: diff direction, HITL marker semantics, G2
     half-and-half failures.

   Invoke from a Clojure REPL:
     (require '[harness-test :as h-t] '[clojure.test :refer [run-tests]])
     (run-tests 'harness-test)"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [extraction.harness :as h]))

;; =============================================================================
;; T1 — list-fixtures discovers the three derived fixtures
;; =============================================================================

(deftest list-fixtures-finds-three-derived
  (testing "list-fixtures scans the fixtures/ directory and returns
            sorted vector of fixture names. Three AFK-derived fixtures
            land at S16 commit time."
    (let [names (h/list-fixtures)]
      (is (vector? names))
      (is (>= (count names) 3)
          (str "Expected at least 3 fixtures, got " (count names)))
      (is (contains? (set names) "document-analysis"))
      (is (contains? (set names) "risk-analysis"))
      (is (contains? (set names) "legal-issue-detection"))
      (is (= (sort names) names)
          "fixtures are returned in sorted order so the report is stable"))))

;; =============================================================================
;; T2 — load-fixture returns the 4-file fixture map
;; =============================================================================

(deftest load-fixture-returns-four-files
  (testing "load-fixture returns :source / :spec / :expected-ttl /
            :notes for an existing fixture, and nil for an unknown one."
    (let [f (h/load-fixture "document-analysis")]
      (is (map? f))
      (is (= "document-analysis" (:fixture-name f)))
      (is (= :ttl (get-in f [:source :type])))
      (is (string? (get-in f [:source :content])))
      (is (pos? (count (get-in f [:source :content]))))
      (is (map? (:spec f)))
      (is (vector? (:competency-questions (:spec f))))
      (is (>= (count (:competency-questions (:spec f))) 3))
      (is (string? (:expected-ttl f)))
      (is (string? (:notes f))))
    (is (nil? (h/load-fixture "this-fixture-does-not-exist")))))

;; =============================================================================
;; T3 — run-fixture! happy path (document-analysis)
;; =============================================================================

(deftest run-fixture-document-analysis-passes-G2
  (testing "run-fixture! on document-analysis: the skeleton's
            full-export produces every expected triple (empty :missing)
            and the CQ pass-rate >= 0.8 — the G2 gate passes.

            Adversarial: assert :status = :pass AND passes-G2? = true.
            One half of the gate failing means the other half can't
            silently cover."
    (let [r (h/run-fixture! "document-analysis")]
      (is (= :pass (:status r))
          (str "Expected :pass, got " (:status r)
               " missing=" (count (get-in r [:triple-diff :missing]))
               " cq-pass-rate=" (:cq-pass-rate r)
               " missing-sample="
               (pr-str (take 3 (get-in r [:triple-diff :missing])))))
      (is (h/passes-G2? r) "passes-G2? must agree with :pass status")
      (is (= #{} (get-in r [:triple-diff :missing]))
          "No triple in expected.ttl may be missing from actual export")
      (is (>= (:cq-pass-rate r) 0.8)
          "CQ pass-rate must meet the gate")
      (is (map? (:evidence-score-distribution r))
          "Evidence distribution is recorded per-fixture")
      (is (#{:auto-derived :hitl-reviewed} (:expected-graph-status r))
          ":expected-graph-status is one of the two honest values"))))

;; =============================================================================
;; T4 — Triple-diff direction (recall gap goes to :missing)
;; =============================================================================
;;
;; Adversarial: if expected.ttl has a triple the skeleton DOESN'T
;; produce, that triple lands in :missing (not :extra). This is the
;; load-bearing direction — getting it wrong silently inverts the
;; gate. We make a TEMPORARY fixture under fixtures/_synthetic-recall-
;; gap/ for this test only, with an expected.ttl that has one
;; phantom triple the source TTL doesn't produce. Tear it down after.

(def ^:private synthetic-fixture-name "_synthetic-recall-gap")

(defn- write-synthetic-fixture! [name expected-extra-triple]
  (let [dir (io/file "development/bench/extraction/fixtures" name)]
    (.mkdirs dir)
    (spit (io/file dir "source.ttl")
          (str "@prefix skos: <http://www.w3.org/2004/02/skos/core#> .\n"
               "@prefix ex: <http://example.org/" name "#> .\n\n"
               "ex:Apple a skos:Concept ; skos:prefLabel \"Apple\"@en .\n"))
    (spit (io/file dir "spec.edn")
          (pr-str {:purpose "synthetic recall-gap test fixture"
                   :competency-questions ["Is there an Apple concept?"]}))
    (spit (io/file dir "expected.ttl")
          (str "@prefix skos: <http://www.w3.org/2004/02/skos/core#> .\n"
               "@prefix ex: <http://example.org/" name "#> .\n\n"
               "ex:Apple a skos:Concept ; skos:prefLabel \"Apple\" .\n"
               expected-extra-triple))
    (spit (io/file dir "notes.md")
          "synthetic fixture for harness test — created and torn down per run")
    dir))

(defn- delete-synthetic-fixture! [name]
  (let [dir (io/file "development/bench/extraction/fixtures" name)]
    (when (.exists dir)
      (doseq [f (.listFiles dir)] (.delete f))
      (.delete dir))))

(deftest triple-diff-direction-puts-recall-gap-in-missing
  (testing "If expected.ttl carries a triple the skeleton CANNOT produce,
            that triple appears in :triple-diff :missing. Diff direction
            is EXPECTED minus ACTUAL → :missing (recall gap)."
    (try
      (write-synthetic-fixture! synthetic-fixture-name
                                "ex:Apple skos:prefLabel \"Orange\" .\n")
      (let [r (h/run-fixture! synthetic-fixture-name)
            missing (get-in r [:triple-diff :missing])]
        ;; The phantom triple "Apple prefLabel Orange" is in expected
        ;; but the skeleton only produces "Apple prefLabel Apple" — so
        ;; the phantom is in :missing AND "Apple prefLabel Apple" is in :extra.
        (is (pos? (count missing))
            "Recall gap MUST surface in :missing (not :extra)")
        (is (some (fn [t] (str/includes? t "Orange")) missing)
            "The phantom 'Orange' triple is the missing one")
        ;; Adversarial: the gate FAILS. Half-and-half failures must
        ;; not silently pass.
        (is (false? (h/passes-G2? r))
            "Recall gap fails G2 even if CQ pass-rate is OK"))
      (finally
        (delete-synthetic-fixture! synthetic-fixture-name)))))

;; =============================================================================
;; T5 — URDNA2015 byte-equivalent expected.ttl produces empty :missing
;; =============================================================================
;;
;; Adversarial: an expected.ttl with DIFFERENT byte form (e.g.,
;; different prefix names, different whitespace, reordered triples)
;; but the SAME triple-set as actual must produce :missing #{}. The
;; harness reuses S09's canonicalizer — this asserts the reuse
;; actually flows through.

(def ^:private byte-different-fixture-name "_synthetic-urdna-equiv")

(defn- write-byte-different-fixture! []
  (let [dir (io/file "development/bench/extraction/fixtures" byte-different-fixture-name)]
    (.mkdirs dir)
    (spit (io/file dir "source.ttl")
          (str "@prefix skos: <http://www.w3.org/2004/02/skos/core#> .\n"
               "@prefix ex: <http://example.org/" byte-different-fixture-name "#> .\n\n"
               "ex:Banana a skos:Concept ; skos:prefLabel \"Banana\"@en .\n"))
    (spit (io/file dir "spec.edn")
          (pr-str {:purpose "URDNA equivalence test fixture"
                   :competency-questions ["Is there a Banana concept?"]}))
    (spit (io/file dir "expected.ttl")
          (str
           "# Re-ordered, prefix-renamed, but URDNA-equivalent to the actual export.\n"
           "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
           "@prefix s: <http://www.w3.org/2004/02/skos/core#> .\n"
           "@prefix concept: <http://example.org/" byte-different-fixture-name "#> .\n\n"
           "concept:Banana s:scopeNote \"custom\" .\n"
           "concept:Banana a s:Concept .\n"
           "concept:Banana s:prefLabel \"Banana\" .\n"))
    (spit (io/file dir "notes.md")
          "synthetic URDNA-equivalence fixture — verifies S09 canonicalizer reuse")
    dir))

(deftest urdna2015-byte-different-expected-still-empty-missing
  (testing "An expected.ttl with different byte form but the SAME triple
            set as actual yields :missing = #{}. Proves S09 URDNA2015
            canonicalization is the comparison basis (not lexical match)."
    (try
      (write-byte-different-fixture!)
      (let [r (h/run-fixture! byte-different-fixture-name)
            missing (get-in r [:triple-diff :missing])]
        (is (empty? missing)
            (str "Byte-different but URDNA-equivalent expected.ttl must "
                 "produce empty :missing. Got: "
                 (pr-str (take 5 missing)))))
      (finally
        (delete-synthetic-fixture! byte-different-fixture-name)))))

;; =============================================================================
;; T6 — G2 gate adversarial: both halves required
;; =============================================================================

(deftest passes-G2-requires-both-halves
  (testing "passes-G2? returns true ONLY when :missing #{} AND
            :cq-pass-rate >= 0.8. Each half failing alone fails the
            gate — there is no silent cover."
    ;; Half A failure: missing has content, pass-rate OK.
    (is (false? (h/passes-G2? {:triple-diff {:missing #{"x"} :extra #{}}
                               :cq-pass-rate 0.95}))
        "Non-empty :missing fails G2 regardless of pass-rate")
    ;; Half B failure: missing empty, pass-rate below.
    (is (false? (h/passes-G2? {:triple-diff {:missing #{} :extra #{}}
                               :cq-pass-rate 0.75}))
        "pass-rate < 0.8 fails G2 even with empty :missing")
    ;; Half B failure: pass-rate nil (no spec / no CQs).
    (is (false? (h/passes-G2? {:triple-diff {:missing #{} :extra #{}}
                               :cq-pass-rate nil}))
        "nil pass-rate fails G2 — fixtures without testable CQs can't pass")
    ;; Both halves pass.
    (is (true? (h/passes-G2? {:triple-diff {:missing #{} :extra #{"x"}}
                              :cq-pass-rate 0.8}))
        "Empty :missing + pass-rate >= 0.8 passes G2 (extras tolerated)")))

;; =============================================================================
;; T7 — HITL marker reflects review state honestly
;; =============================================================================

(def ^:private hitl-toggle-fixture-name "_synthetic-hitl-toggle")

(defn- write-hitl-toggle-fixture! [notes-content]
  (let [dir (io/file "development/bench/extraction/fixtures" hitl-toggle-fixture-name)]
    (.mkdirs dir)
    (spit (io/file dir "source.ttl")
          (str "@prefix skos: <http://www.w3.org/2004/02/skos/core#> .\n"
               "@prefix ex: <http://example.org/" hitl-toggle-fixture-name "#> .\n\n"
               "ex:Cherry a skos:Concept ; skos:prefLabel \"Cherry\"@en .\n"))
    (spit (io/file dir "spec.edn")
          (pr-str {:purpose "HITL toggle test"
                   :competency-questions ["Is there a Cherry concept?"]}))
    (spit (io/file dir "expected.ttl") "")
    (spit (io/file dir "notes.md") notes-content)))

(deftest hitl-marker-reflects-review-state
  (testing "When notes.md contains HITL-REVIEW-REQUIRED →
            :expected-graph-status :auto-derived. When the marker is
            absent → :hitl-reviewed. Adversarial: the harness STILL
            RUNS in both states; the report is honest about which is
            which (no silent defaulting to :hitl-reviewed)."
    (try
      (write-hitl-toggle-fixture! "## Notes\n\nHITL-REVIEW-REQUIRED — derived from X")
      (let [r (h/run-fixture! hitl-toggle-fixture-name)]
        (is (= :auto-derived (:expected-graph-status r))
            "HITL-REVIEW-REQUIRED marker → :auto-derived"))
      (write-hitl-toggle-fixture! "## Notes\n\nThis fixture has been HITL-reviewed by user.")
      (let [r (h/run-fixture! hitl-toggle-fixture-name)]
        (is (= :hitl-reviewed (:expected-graph-status r))
            "No marker → :hitl-reviewed"))
      (finally
        (delete-synthetic-fixture! hitl-toggle-fixture-name)))))

;; =============================================================================
;; T8 — run-all! produces a report at the expected path
;; =============================================================================

(deftest run-all-produces-extraction-results-md
  (testing "run-all! discovers every fixture, runs each, writes
            extraction-RESULTS.md at the documented path. Adversarial:
            the report MUST include the :expected-graph-status column
            for every fixture (so the report is HONEST about HITL state)."
    (let [results (h/run-all!)
          report-path "development/bench/extraction/extraction-RESULTS.md"
          report (slurp report-path)]
      (is (vector? results))
      (is (>= (count results) 3))
      ;; Headline summary line — the report MUST surface BOTH
      ;; pass-count and HITL-reviewed-count.
      (is (str/includes? report "G2 status:")
          "Report headline carries G2 status")
      (is (str/includes? report "HITL-reviewed expected graphs")
          "Report headline carries HITL-review state count")
      ;; Per-fixture sections name each fixture.
      (is (str/includes? report "document-analysis"))
      (is (str/includes? report "risk-analysis"))
      (is (str/includes? report "legal-issue-detection"))
      ;; Per-fixture sections carry the expected-graph-status.
      (is (str/includes? report "Expected-graph status:")
          "Every fixture section names its HITL state")
      ;; Per-fixture timing column.
      (is (str/includes? report "Timing:")))))
