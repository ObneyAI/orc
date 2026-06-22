(ns ai.obney.orc.ontology.r03-ood-stress-test
  "Unit tests for the R03 OOD stress-test orchestrator's pure helpers.

   The orchestrator lives in the brick test-support namespace
   ai.obney.orc.ontology.test-support.c2d-ood-stress-test.
   These tests cover the unit-testable helpers — corpus loader, metric
   computation, per-instruction outcome classifier, markdown generator,
   EDN persistence. The actual live stress run is the HITL audit step;
   it's not unit-testable (requires real LLM + ColBERT)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.ontology.test-support.c2d-ood-stress-test :as ood]))

;; =============================================================================
;; RED #1 — corpus loader reads *.txt files and strips leading `;` comments
;; =============================================================================

(deftest corpus-loader-reads-files-and-strips-comment-headers
  (testing "load-corpus reads every *.txt in the given dir"
    (let [tmpdir (str "/tmp/r03-corpus-" (random-uuid))]
      (.mkdirs (java.io.File. tmpdir))
      (try
        (spit (str tmpdir "/alpha.txt")
              ";; Provenance: synthetic\n;; Family: code-related\nFirst instruction body.")
        (spit (str tmpdir "/beta.txt")
              ";; Provenance: synthetic\nSecond instruction body without a family line.")
        (spit (str tmpdir "/gamma.txt")
              "No leading comment at all — entire file is the instruction.")
        (let [corpus (ood/load-corpus tmpdir)]
          (is (= 3 (count corpus))
              "Three .txt files were loaded")
          (let [by-slug (into {} (map (juxt :slug identity)) corpus)]
            (is (= "First instruction body." (-> by-slug (get "alpha") :instruction))
                "Leading `;;`-prefixed lines stripped from alpha")
            (is (= "Second instruction body without a family line."
                   (-> by-slug (get "beta") :instruction))
                "Leading `;;` lines stripped from beta")
            (is (= "No leading comment at all — entire file is the instruction."
                   (-> by-slug (get "gamma") :instruction))
                "File without leading comments returns the full body verbatim")
            (is (every? #(re-find #"alpha|beta|gamma" (:source-path %)) corpus)
                "Each entry carries the source path")))
        (finally
          (doseq [f (.listFiles (java.io.File. tmpdir))] (.delete f))
          (.delete (java.io.File. tmpdir))))))

  (testing "load-corpus returns [] when dir is empty"
    (let [tmpdir (str "/tmp/r03-corpus-empty-" (random-uuid))]
      (.mkdirs (java.io.File. tmpdir))
      (try
        (is (= [] (ood/load-corpus tmpdir))
            "Empty dir → empty corpus, no exception")
        (finally
          (.delete (java.io.File. tmpdir))))))

  (testing "load-corpus skips non-.txt files"
    (let [tmpdir (str "/tmp/r03-corpus-mixed-" (random-uuid))]
      (.mkdirs (java.io.File. tmpdir))
      (try
        (spit (str tmpdir "/real.txt") "instruction")
        (spit (str tmpdir "/notes.md") "not a corpus file")
        (spit (str tmpdir "/.hidden") "should be skipped")
        (let [corpus (ood/load-corpus tmpdir)]
          (is (= 1 (count corpus))
              "Only the .txt file counts"))
        (finally
          (doseq [f (.listFiles (java.io.File. tmpdir))] (.delete f))
          (.delete (java.io.File. tmpdir)))))))

;; =============================================================================
;; RED #2 — per-instruction outcome classifier
;; =============================================================================
;;
;; Given a classify-task envelope (the shape produced by the wedge in
;; production), `classify-outcome` returns derived structural flags so
;; aggregate metric counting becomes (filter :direct-matched? results)
;; etc. Pure function over the result map; no live LLM.
;; =============================================================================

(deftest classify-outcome-derives-structural-flags
  (testing "Direct match (top-1 confidence >= 0.9, no walk-down, no fresh-mint)"
    (let [matched-id (random-uuid)
          envelope {:assigned-tree-id matched-id
                    :confidence 1.0
                    :was-fresh-mint? false
                    :parent-tree-id nil
                    :rerank-fallback? false
                    :top-candidates [{:document-metadata {:target-id matched-id}
                                      :fitness-score 1.0
                                      :rerank-source :reranker}]}
          outcome (ood/classify-outcome envelope)]
      (is (true? (:direct-matched? outcome)) ":direct-matched? true when top-1 fitness >= 0.9")
      (is (false? (:walk-down-fired? outcome)) "walk-down didn't fire")
      (is (false? (:was-fresh-mint? outcome)))
      (is (false? (:rerank-fallback? outcome)))))

  (testing "Walk-down fired (top-1 != assigned-tree-id)"
    (let [parent-id (random-uuid)
          leaf-id (random-uuid)
          envelope {:assigned-tree-id leaf-id
                    :confidence 0.92
                    :was-fresh-mint? false
                    :parent-tree-id parent-id
                    :rerank-fallback? false
                    :top-candidates [{:document-metadata {:target-id parent-id}
                                      :fitness-score 0.85
                                      :rerank-source :reranker}]}
          outcome (ood/classify-outcome envelope)]
      (is (true? (:walk-down-fired? outcome)) "top-1 != assigned-tree-id → walk fired")
      (is (false? (:direct-matched? outcome)) "top-1 fitness 0.85 < 0.9 → not a direct match")
      (is (false? (:was-fresh-mint? outcome)))))

  (testing "Fresh-mint (not-matched path)"
    (let [envelope {:assigned-tree-id (random-uuid)
                    :confidence 0.0
                    :was-fresh-mint? true
                    :parent-tree-id nil
                    :rerank-fallback? false
                    :top-candidates [{:document-metadata {:target-id (random-uuid)}
                                      :fitness-score 0.3
                                      :rerank-source :reranker}]}
          outcome (ood/classify-outcome envelope)]
      (is (true? (:was-fresh-mint? outcome)))
      (is (false? (:direct-matched? outcome)))
      (is (false? (:rerank-fallback? outcome)))))

  (testing "Rerank fallback (R01 surfaced silent reranker failure)"
    (let [envelope {:assigned-tree-id (random-uuid)
                    :confidence 0.0
                    :was-fresh-mint? true
                    :parent-tree-id nil
                    :rerank-fallback? true
                    :top-candidates [{:document-metadata {:target-id (random-uuid)}
                                      :fitness-score nil
                                      :rerank-source :colbert-fallback}]}
          outcome (ood/classify-outcome envelope)]
      (is (true? (:rerank-fallback? outcome))
          ":rerank-fallback? propagates from envelope")
      (is (true? (:was-fresh-mint? outcome))
          "Fresh-mint still fires (the classifier's not-matched path)")
      (is (false? (:direct-matched? outcome))))))

;; =============================================================================
;; RED #3 — aggregate-metrics over a vector of result maps
;; =============================================================================
;;
;; Each per-instruction result is {:envelope ... :outcome ... :elapsed-ms ...
;; :parent-chain [...]}. aggregate-metrics returns structural counts +
;; rates + a latency summary + a parent-chain-depth distribution. Pure
;; over its input — no live LLM, no Side effects.
;; =============================================================================

(defn- mk-result
  "Helper to construct a synthetic per-instruction result for testing."
  [{:keys [direct? walk? fresh? fallback? elapsed-ms chain-depth]}]
  {:envelope {} ; opaque; tests only read :outcome
   :outcome {:direct-matched? (boolean direct?)
             :walk-down-fired? (boolean walk?)
             :was-fresh-mint? (boolean fresh?)
             :rerank-fallback? (boolean fallback?)}
   :elapsed-ms elapsed-ms
   :parent-chain (vec (repeat chain-depth {:uri "x" :label "x"}))})

(deftest aggregate-metrics-counts-structural-outcomes
  (testing "aggregate-metrics over a synthetic 5-result fixture"
    (let [results [(mk-result {:direct? true  :elapsed-ms 100 :chain-depth 1})
                   (mk-result {:direct? true  :elapsed-ms 200 :chain-depth 1})
                   (mk-result {:walk? true    :elapsed-ms 300 :chain-depth 3})
                   (mk-result {:fresh? true   :elapsed-ms 400 :chain-depth 1})
                   (mk-result {:fallback? true :fresh? true :elapsed-ms 500 :chain-depth 0})]
          metrics (ood/aggregate-metrics results)]

      (testing "rates over the 5 results"
        (is (= 2 (:direct-match-count metrics))   "2 direct matches")
        (is (= 1 (:walk-down-fired-count metrics)) "1 walk-down fired")
        (is (= 2 (:fresh-mint-count metrics))     "2 fresh-mints (one with fallback)")
        (is (= 1 (:rerank-failure-count metrics)) "1 rerank failure")
        (is (= 5 (:total-count metrics))          "5 total"))

      (testing "rates expressed as ratios in [0.0, 1.0]"
        (is (= 0.4 (:direct-match-rate metrics))   "2/5 direct match")
        (is (= 0.2 (:walk-down-fired-rate metrics)) "1/5 walk fired")
        (is (= 0.4 (:fresh-mint-rate metrics))     "2/5 fresh-mint")
        (is (= 0.2 (:rerank-failure-rate metrics)) "1/5 rerank fail"))

      (testing "latency summary"
        (is (= 300.0 (:latency-mean-ms metrics))   "mean of 100/200/300/400/500 = 300")
        (is (= 300 (:latency-median-ms metrics)) "median = 300")
        (is (= 500 (:latency-p95-ms metrics))    "p95 of 5 = 500"))

      (testing "parent-chain-depth distribution"
        (is (= {0 1 1 3 3 1} (:parent-chain-depth-distribution metrics))
            "0:1, 1:3, 3:1"))))

  (testing "aggregate-metrics over empty input"
    (let [metrics (ood/aggregate-metrics [])]
      (is (= 0 (:total-count metrics)))
      (is (= 0.0 (:direct-match-rate metrics))
          "Empty input → 0.0 rate (no division by zero)")
      (is (= 0.0 (:rerank-failure-rate metrics)))
      (is (zero? (:latency-mean-ms metrics))))))

;; =============================================================================
;; RED #4 — markdown summary generator
;; =============================================================================
;;
;; Generates the human-readable OOD-RESULTS.md. The output has:
;;   - a header section with aggregate rates
;;   - a per-instruction table with audit columns marked [?]
;;   - explicit honest-curation-disclosure block
;; =============================================================================

(deftest markdown-summary-includes-rates-and-audit-columns
  (testing "markdown-summary generates a complete report"
    (let [corpus [{:slug "alpha"
                   :instruction "Refactor this auth code to use middleware."
                   :source-path "/tmp/alpha.txt"}
                  {:slug "beta"
                   :instruction "Recommend a sprint plan for our migration."
                   :source-path "/tmp/beta.txt"}]
          results [{:slug "alpha"
                    :outcome {:direct-matched? true :walk-down-fired? false
                              :was-fresh-mint? false :rerank-fallback? false}
                    :elapsed-ms 1100
                    :envelope {:assigned-tree-id "00000000-0000-0000-0000-aaaaaaaaaaaa"
                               :confidence 0.95
                               :parent-tree-id nil}
                    :parent-chain [{:uri "tree-class:x" :label "X"}]}
                   {:slug "beta"
                    :outcome {:direct-matched? false :walk-down-fired? true
                              :was-fresh-mint? false :rerank-fallback? false}
                    :elapsed-ms 1500
                    :envelope {:assigned-tree-id "00000000-0000-0000-0000-bbbbbbbbbbbb"
                               :confidence 0.85
                               :parent-tree-id "00000000-0000-0000-0000-cccccccccccc"}
                    :parent-chain [{:uri "tree-class:y" :label "Y"}
                                   {:uri "tree-class:z" :label "Z"}]}]
          metrics (ood/aggregate-metrics results)
          md (ood/markdown-summary corpus results metrics)]

      (testing "header content"
        (is (re-find #"(?i)ood.*stress" md)
            "Title mentions OOD stress")
        (is (re-find #"(?i)bias|honest|curation" md)
            "Includes honest-curation-disclosure block per the orchestrator docstring"))

      (testing "aggregate rates surface in human-readable form"
        (is (re-find #"direct.match" md))
        (is (re-find #"walk.down" md))
        (is (re-find #"fresh.mint" md))
        (is (re-find #"rerank.fail" md)))

      (testing "per-instruction table"
        (is (re-find #"alpha" md) "Instruction slug 'alpha' present")
        (is (re-find #"beta" md)  "Instruction slug 'beta' present")
        (is (re-find #"\|" md)    "Has at least one markdown table pipe")
        (is (re-find #"---" md)   "Has table header separator"))

      (testing "audit columns are marked [?] for HITL fill-in"
        (is (re-find #"\[\?\]" md)
            "[?] markers present so HITL reviewer can grep for unaudited rows")))))

;; =============================================================================
;; RED #5 — EDN persistence to a directory
;; =============================================================================
;;
;; persist-run! writes:
;;   - one ood-result-<slug>_<ts>.edn per instruction (verbatim envelope)
;;   - one ood-combined_<ts>.edn (corpus + results + metrics)
;;   - OOD-RESULTS.md (overwritten each run)
;; Returns a map of paths written. Pure aside from the file writes.
;; =============================================================================

(deftest persist-run-writes-per-instruction-combined-and-markdown
  (testing "persist-run! writes per-instruction EDNs + combined EDN + markdown summary"
    (let [tmpdir (str "/tmp/r03-results-" (random-uuid))
          _ (.mkdirs (java.io.File. tmpdir))
          corpus [{:slug "one" :instruction "x" :source-path "/tmp/one.txt"}
                  {:slug "two" :instruction "y" :source-path "/tmp/two.txt"}]
          results [{:slug "one"
                    :outcome {:direct-matched? true :walk-down-fired? false
                              :was-fresh-mint? false :rerank-fallback? false}
                    :elapsed-ms 100
                    :envelope {:assigned-tree-id (random-uuid)
                               :confidence 0.95 :parent-tree-id nil}
                    :parent-chain [{:uri "tree-class:x" :label "X"}]}
                   {:slug "two"
                    :outcome {:direct-matched? false :walk-down-fired? false
                              :was-fresh-mint? true :rerank-fallback? false}
                    :elapsed-ms 200
                    :envelope {:assigned-tree-id (random-uuid)
                               :confidence 0.0 :parent-tree-id nil}
                    :parent-chain []}]
          metrics (ood/aggregate-metrics results)]
      (try
        (let [paths (ood/persist-run! {:dir tmpdir
                                        :corpus corpus
                                        :results results
                                        :metrics metrics})]
          (is (map? paths) "Returns a path-map for HITL inspection")
          (is (vector? (:per-instruction paths))
              ":per-instruction is a vector of saved paths")
          (is (= 2 (count (:per-instruction paths)))
              "Two per-instruction files written")
          (is (some? (:combined paths))
              ":combined path returned")
          (is (some? (:markdown paths))
              ":markdown path returned")

          (testing "each saved file exists + is readable"
            (doseq [p (:per-instruction paths)]
              (is (.exists (java.io.File. ^String p))
                  (str p " was written")))
            (is (.exists (java.io.File. ^String (:combined paths))))
            (is (.exists (java.io.File. ^String (:markdown paths)))))

          (testing "per-instruction EDN round-trips through read-string"
            (let [data (read-string (slurp (first (:per-instruction paths))))]
              (is (map? data))
              (is (contains? data :envelope))
              (is (contains? data :outcome))
              (is (contains? data :parent-chain))))

          (testing "combined EDN carries all 3 sections"
            (let [combined (read-string (slurp (:combined paths)))]
              (is (= 2 (count (:corpus combined))))
              (is (= 2 (count (:results combined))))
              (is (some? (:metrics combined)))))

          (testing "markdown file is the markdown-summary output"
            (let [md (slurp (:markdown paths))]
              (is (re-find #"(?i)ood.*stress" md))
              (is (re-find #"\[\?\]" md)))))
        (finally
          (doseq [f (.listFiles (java.io.File. tmpdir))] (.delete f))
          (.delete (java.io.File. tmpdir)))))))
