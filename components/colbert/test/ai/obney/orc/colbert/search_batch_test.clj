(ns ai.obney.orc.colbert.search-batch-test
  "Slice 3, cycles 1-2: JVM-backed operations/search-batch + the frozen
   search-for-rrf(-batch) seam contracts.

   Cycle 1 — search-batch: stubbed read-model (the index_search_test idiom),
   golden index over the 6 fixture docs, the 3 golden queries. The contract:
   a vector of result-lists ALIGNED to :queries, each inner list EXACTLY the
   corresponding single `search` output (same snake_case keys), the not-found/
   deleted ex-infos preserved verbatim, and the artifact read from disk
   exactly ONCE across the whole batch (the index-store cache PIN — a counting
   redef on index-store/read-index, nothing built here).

   Cycle 2 — search-for-rrf / search-for-rrf-batch seam pins: [{:uri :score}]
   shapes (vector-of-vectors aligned for batch), :weight multiplies,
   :normalize? true = max-in-batch (top score = weight), :normalize? false =
   raw MaxSim scores.

   Uses the REAL local model via -Dcolbert.model.path; the index root is a
   per-test temp dir via -Dcolbert.index.root. No Python, no network."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.obney.orc.colbert.colbert-test-support :as support]
            [ai.obney.orc.colbert.core.index-store :as index-store]
            [ai.obney.orc.colbert.core.operations :as operations]
            [ai.obney.orc.colbert.core.read-models :as read-models]))

(use-fixtures :once support/with-model-path)

(def index-root-property "colbert.index.root")

(defn- temp-dir ^java.io.File [label]
  (.toFile (java.nio.file.Files/createTempDirectory
            (str "colbert-search-batch-test-" label)
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn with-temp-index-root
  "Point -Dcolbert.index.root at a fresh temp dir for each test, restoring
   the previous value afterwards (never write indexes into the repo cwd)."
  [f]
  (let [previous (System/getProperty index-root-property)]
    (System/setProperty index-root-property (str (temp-dir "root")))
    (try
      (f)
      (finally
        (if previous
          (System/setProperty index-root-property previous)
          (System/clearProperty index-root-property))))))

(use-fixtures :each with-temp-index-root)

(defn- stub-index
  [{:keys [index-id index-path]}]
  {:index-id index-id
   :index-path index-path
   :status :active})

(defmacro with-stubbed-read-model
  [result & body]
  `(let [result# ~result]
     (with-redefs [read-models/get-index
                   (fn [~'_ index-id#]
                     (when (= index-id# (:index-id result#))
                       (stub-index result#)))]
       ~@body)))

(defn- golden-index!
  "Build the 6-doc golden index; returns the create-index! result map."
  [index-name]
  (let [golden (support/read-golden "python_scores.json")
        doc-ids (vec (sort (keys (get golden "documents"))))
        docs (mapv #(get-in golden ["documents" %]) doc-ids)]
    (operations/create-index! {}
      {:collection docs
       :document-ids doc-ids
       :index-name index-name
       :split-documents? false})))

(defn- golden-queries []
  (let [golden (support/read-golden "python_scores.json")]
    (mapv #(get-in golden ["queries" %]) (sort (keys (get golden "queries"))))))

;; =============================================================================
;; Cycle 1: search-batch — alignment, per-query parity with `search`,
;; one artifact read for the whole batch, frozen ex-infos
;; =============================================================================

(deftest search-batch-is-aligned-and-per-query-identical-to-search
  (let [result (golden-index! "batch-golden")
        queries (golden-queries)]
    (with-stubbed-read-model result
      (let [batch (operations/search-batch {} {:queries queries
                                               :index-id (:index-id result)
                                               :k 6})]
        (testing "a vector of result-lists aligned to :queries"
          (is (vector? batch))
          (is (= (count queries) (count batch)))
          (is (every? vector? batch)))
        (testing "each inner list equals the corresponding single `search` output
                  (same snake_case keys, same scores, same order)"
          (doseq [[q results] (map vector queries batch)]
            (is (= (operations/search {} {:query q
                                          :index-id (:index-id result)
                                          :k 6})
                   results)
                (str "batch results for " (pr-str q)
                     " must equal the single-query search output"))))
        (testing "snake_case result shape (the frozen bridge shape)"
          (doseq [r (apply concat batch)]
            (is (= #{:content :score :rank :document_id :document_metadata}
                   (set (keys r))))))
        (testing "distinct queries produce distinct rankings (no cross-wiring)"
          (is (not= (mapv :document_id (nth batch 0))
                    (mapv :document_id (nth batch 1)))))))))

(deftest search-batch-respects-k-and-nil-k-default
  (let [result (golden-index! "batch-k")
        queries (golden-queries)]
    (with-stubbed-read-model result
      (testing "k limits each inner result list"
        (let [batch (operations/search-batch {} {:queries queries
                                                 :index-id (:index-id result)
                                                 :k 2})]
          (is (every? #(= 2 (count %)) batch))
          (is (every? #(= [1 2] (mapv :rank %)) batch))))
      (testing "explicit nil k coalesces to the default 10 (6 docs -> all 6)"
        (let [batch (operations/search-batch {} {:queries [(first queries)]
                                                 :index-id (:index-id result)
                                                 :k nil})]
          (is (= 6 (count (first batch)))))))))

(deftest search-batch-reads-the-artifact-exactly-once
  ;; The cache PIN: index-store/load-index memoizes per canonical path, so N
  ;; queries cost ONE disk read. Nothing is built here — this test pins the
  ;; property Slice 3 depends on.
  (let [result (golden-index! "batch-cache")
        queries (golden-queries)
        reads (atom 0)
        real-read index-store/read-index]
    ;; cold start: nothing from THIS path may be cached
    (index-store/evict-index! (:index-path result))
    (with-stubbed-read-model result
      (with-redefs [index-store/read-index (fn [dir]
                                             (swap! reads inc)
                                             (real-read dir))]
        (let [batch (operations/search-batch {} {:queries queries
                                                 :index-id (:index-id result)
                                                 :k 3})]
          (is (= (count queries) (count batch)) "the batch ran")
          (is (= 1 @reads)
              "the artifact is read from disk exactly ONCE for the whole batch"))))))

;; =============================================================================
;; Cycle 2: search-for-rrf / search-for-rrf-batch seam pins ({:uri :score},
;; alignment, weight multiplies, :normalize? true = max-in-batch)
;; =============================================================================

(deftest search-for-rrf-shape-normalization-and-weight
  (let [result (golden-index! "rrf-single")
        [q1 & _] (golden-queries)]
    (with-stubbed-read-model result
      (let [rrf (operations/search-for-rrf {} {:query q1
                                               :index-id (:index-id result)
                                               :k 6})
            raw (operations/search {} {:query q1
                                       :index-id (:index-id result)
                                       :k 6})]
        (testing "vector of {:uri :score} — exactly those keys"
          (is (vector? rrf))
          (is (= 6 (count rrf)))
          (doseq [r rrf]
            (is (= #{:uri :score} (set (keys r))))
            (is (string? (:uri r)))
            (is (double? (:score r)))))
        (testing ":uri is the indexed document-id, in search's ranking order"
          (is (= (mapv :document_id raw) (mapv :uri rrf))))
        (testing ":normalize? true (the default) = max-in-batch: top score is 1.0,
                  relative order preserved"
          (is (= 1.0 (double (:score (first rrf)))))
          (is (apply >= (map :score rrf))))
        (testing ":weight multiplies the normalized scores"
          (let [weighted (operations/search-for-rrf {} {:query q1
                                                        :index-id (:index-id result)
                                                        :k 6
                                                        :weight 2.0})]
            (doseq [[r w] (map vector rrf weighted)]
              (is (= (:uri r) (:uri w)))
              (is (< (Math/abs (- (* 2.0 (:score r)) (:score w))) 1e-9)))))
        (testing ":normalize? false returns raw MaxSim scores (times weight 1.0)"
          (let [unnorm (operations/search-for-rrf {} {:query q1
                                                      :index-id (:index-id result)
                                                      :k 6
                                                      :normalize? false})]
            (is (= (mapv :score raw) (mapv :score unnorm)))))))))

(deftest search-for-rrf-batch-aligned-and-per-query-identical-to-single
  (let [result (golden-index! "rrf-batch")
        queries (golden-queries)]
    (with-stubbed-read-model result
      (let [batch (operations/search-for-rrf-batch {} {:queries queries
                                                       :index-id (:index-id result)
                                                       :k 6})]
        (testing "vector-of-vectors aligned to :queries"
          (is (vector? batch))
          (is (= (count queries) (count batch)))
          (doseq [inner batch
                  r inner]
            (is (= #{:uri :score} (set (keys r))))))
        (testing "each element equals the single search-for-rrf for that query
                  (same normalization, same weight semantics)"
          (doseq [[q inner] (map vector queries batch)]
            (is (= (operations/search-for-rrf {} {:query q
                                                  :index-id (:index-id result)
                                                  :k 6})
                   inner)
                (str "rrf batch element for " (pr-str q)
                     " must equal the single search-for-rrf output"))))
        (testing "max-in-batch normalization is PER QUERY (each list tops at 1.0)"
          (doseq [inner batch]
            (is (= 1.0 (double (:score (first inner)))))))
        (testing ":weight multiplies per element"
          (let [weighted (operations/search-for-rrf-batch {} {:queries queries
                                                              :index-id (:index-id result)
                                                              :k 6
                                                              :weight 0.5})]
            (doseq [[inner winner] (map vector batch weighted)
                    [r w] (map vector inner winner)]
              (is (= (:uri r) (:uri w)))
              (is (< (Math/abs (- (* 0.5 (:score r)) (:score w))) 1e-9)))))))))

(deftest search-batch-not-found-and-deleted-ex-infos-preserved
  (testing "unknown index throws the EXACT pre-existing ex-info"
    (with-redefs [read-models/get-index (fn [_ _] nil)]
      (let [index-id (random-uuid)
            ex (try (operations/search-batch {} {:queries ["q"] :index-id index-id :k 3})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= "Index not found" (ex-message ex)))
        (is (= {:index-id index-id} (ex-data ex))))))
  (testing "deleted index throws the EXACT pre-existing ex-info"
    (with-redefs [read-models/get-index (fn [_ _] {:status :deleted})]
      (let [index-id (random-uuid)
            ex (try (operations/search-batch {} {:queries ["q"] :index-id index-id :k 3})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= "Index has been deleted" (ex-message ex)))
        (is (= {:index-id index-id} (ex-data ex)))))))
