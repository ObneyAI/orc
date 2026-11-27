(ns ai.obney.orc.colbert.bounded-normalization-test
  "CC-25 — `invariant.BoundedNormalization` (specs/colbert.allium):

     Normalized scores lie from zero through one while preserving relative
     order among retained results.

   CC-17 made the query limit per-index CONFIGURATION on the ENCODE side
   (`IndexConfiguration.maximum_query_tokens`, threaded into
   `logic/encode-query` by queries/search and queries/search-batch)
   but left the NORMALIZE side process-global: `maxsim-ceiling`'s no-arg form
   resolves the PROCESS default. Two sources of truth for one number.

   The MaxSim ceiling IS `maximum_query_tokens` (that many unit-normed query
   rows, each contributing at most 1.0), so an index configured ABOVE the
   process default produces raw scores ABOVE the process ceiling — and
   `(min 1.0 ...)` flattens every one of them to 1.0, destroying relative
   order. An index configured BELOW the default is silently compressed into a
   sliver of the [0,1] scale.

   Real encoder, real index artifact, real search — the ceilings are measured
   against scores the encoder actually produced, never simulated."
  (:require [ai.obney.orc.colbert.core.logic :as logic]
            [ai.obney.orc.colbert.core.queries :as queries]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.obney.orc.colbert.colbert-test-support :as support]
            [ai.obney.orc.colbert.core.read-models :as read-models]
            [ai.obney.orc.colbert.interface :as colbert]))

(use-fixtures :once support/with-model-path)

(defn- test-encoder [] (logic/get-encoder (logic/resolve-model-dir)))

(def documents
  ["Any dispute arising under this agreement shall be settled by binding arbitration."
   "Either party may terminate this agreement with thirty days written notice."
   "The midfielder scored twice in the final minutes of the championship match."])

(def query "binding arbitration of contract disputes")

(defn- temp-index-root ^String []
  (str (.toFile (java.nio.file.Files/createTempDirectory
                 "colbert-cc25-" (make-array java.nio.file.attribute.FileAttribute 0)))))

(defmacro with-index-root [& body]
  `(let [prev# (System/getProperty logic/index-root-property)]
     (System/setProperty logic/index-root-property (temp-index-root))
     (try ~@body
          (finally (if prev#
                     (System/setProperty logic/index-root-property prev#)
                     (System/clearProperty logic/index-root-property))))))

(defn- search-index-at
  "Build a REAL index whose IndexConfiguration.maximum_query_tokens is
   `limit`, then really search it. Returns the result collection, truncation
   metadata and all."
  [limit label]
  (let [created (logic/create-index! {}
                  {:collection documents
                   :document-ids ["d1" "d2" "d3"]
                   :index-name (str "cc25-" label "-" limit)
                   :split-documents? false
                   :maximum-query-tokens limit})]
    (with-redefs [read-models/get-index
                  (fn [_ id] (when (= id (:index-id created))
                               {:index-id id :index-path (:index-path created)
                                :config (:config created) :status :active}))]
      (queries/search {} {:query query :index-id (:index-id created) :k 3}))))

(defn- strictly-descending?
  "The invariant's second clause, stated as a property over the retained
   results: every adjacent pair keeps its strict ordering. `(apply > coll)` on
   a clamped-flat vector is FALSE, which is exactly the signal wanted."
  [scores]
  (apply > scores))


(deftest above-default-index-normalization-is-bounded-and-order-preserving
  (with-index-root
    (let [enc (test-encoder)
          positions (long (get-in enc [:consts :max-position-embeddings]))
          process-default (long (logic/configured-maximum-query-tokens))
          limit (- positions 12)
          results (search-index-at limit "above")
          raw (mapv :score results)]
      (testing "the probe is non-vacuous by construction"
        (println "  [N] process default =" process-default
                 "| index limit =" limit
                 "| max_position_embeddings =" positions)
        (println "  [N] raw scores (n=" (count raw) ") =" (pr-str raw))
        (is (= 3 (count results)))
        (is (<= limit positions) "the probe limit must be a LEGAL configuration")
        (is (> limit process-default)
            "the probe index must sit ABOVE the process default or it proves nothing")
        (is (= limit (:maximum-query-tokens (:query-truncation (meta results))))
            "the encoding that ran used the INDEX's limit")
        (is (strictly-descending? raw)
            "the raw scores must already be distinct and ordered")
        (is (> (apply min raw) (double process-default))
            "every raw score must exceed the PROCESS ceiling — that is the clamp"))
      (testing "the process-global ceiling is the WRONG ceiling for these scores"
        (is (not= (logic/maxsim-ceiling) (logic/results-maxsim-ceiling results))
            "the two sources of truth really do diverge here")
        (is (= (double limit) (logic/results-maxsim-ceiling results))
            "the results' own ceiling is the limit the encoding ran under"))
      (testing "BoundedNormalization: bounded AND order-preserving"
        (let [normalized (logic/normalize-results-to-ceiling results)
              norm (mapv :score normalized)]
          (println "  [N] normalized =" (pr-str norm))
          (is (= 3 (count normalized)) "every retained result survives")
          (is (every? #(<= 0.0 % 1.0) norm)
              "normalized scores lie from zero through one")
          (is (strictly-descending? norm)
              "relative order among retained results is preserved")
          (is (= (mapv :document_id results) (mapv :document_id normalized))
              "the ranking itself is untouched")
          (is (= raw (mapv :raw-score normalized))
              "the raw MaxSim value stays available"))))))


(deftest below-default-index-normalization-does-not-compress-the-scale
  (with-index-root
    (let [process-default (long (logic/configured-maximum-query-tokens))
          limit 96
          results (search-index-at limit "below")
          raw (mapv :score results)
          process-ceiling (logic/maxsim-ceiling)]
      (testing "the probe is non-vacuous by construction"
        (println "  [N] process default =" process-default "| index limit =" limit)
        (println "  [N] raw scores (n=" (count raw) ") =" (pr-str raw))
        (is (= 3 (count results)))
        (is (< limit process-default)
            "the probe index must sit BELOW the process default or it proves nothing")
        (is (= limit (:maximum-query-tokens (:query-truncation (meta results)))))
        (is (strictly-descending? raw))
        (is (< (/ (apply max raw) process-ceiling) 0.25)
            (str "against the PROCESS ceiling " process-ceiling " these scores would sit "
                 "in the bottom quarter of the scale — that is the compression")))
      (testing "normalizing against the encoding's OWN ceiling uses the whole scale"
        (let [norm (mapv :score (logic/normalize-results-to-ceiling results))]
          (println "  [N] normalized =" (pr-str norm)
                   "| against the process ceiling =" (pr-str (mapv #(/ % process-ceiling) raw)))
          (is (every? #(<= 0.0 % 1.0) norm))
          (is (strictly-descending? norm))
          (is (> (apply max norm) 0.9)
              "the top score sits near the ceiling it was normalized against, not at 0.2")))
      (testing "the normalized scale is INVARIANT to the index's configured limit"
        (let [at-limit (mapv :score (logic/normalize-results-to-ceiling results))
              at-default (mapv :score (logic/normalize-results-to-ceiling
                                       (search-index-at process-default "below-ref")))
              deltas (mapv (fn [a b] (Math/abs (- (double a) (double b))))
                           at-limit at-default)]
          (println "  [N] scale deltas vs the default-configured index =" (pr-str deltas))
          (is (every? #(< % 0.02) deltas)
              "configuration must move the ceiling, not the meaning of the number"))))))


(deftest default-configured-index-normalization-is-unchanged
  (with-index-root
    (let [process-default (long (logic/configured-maximum-query-tokens))
          results (search-index-at process-default "default")
          raw (mapv :score results)
          before (mapv #(logic/normalize-colbert-score (:score %)) results)
          after (mapv :score (logic/normalize-results-to-ceiling results))]
      (testing "the probe is non-vacuous — it really is at the process default"
        (println "  [N] process default =" process-default
                 "| raw scores (n=" (count raw) ") =" (pr-str raw))
        (is (= 3 (count results)))
        (is (= process-default
               (:maximum-query-tokens (:query-truncation (meta results)))))
        (is (strictly-descending? raw)))
      (testing "the two ceilings AGREE here — the reason the defect stayed latent"
        (is (= (logic/maxsim-ceiling) (logic/results-maxsim-ceiling results))))
      (testing "and the normalized scores are bit-for-bit what they always were"
        (println "  [N] before =" (pr-str before) "| after =" (pr-str after))
        (is (= before after) "an index at the process default must be untouched")
        (is (every? #(<= 0.0 % 1.0) after))
        (is (strictly-descending? after))))))


(deftest normalization-options-fields-and-value-equality
  (with-index-root
    (let [limit 96
          results (search-index-at limit "options")
          raw (mapv :score results)]
      (testing "entity-fields.NormalizationOptions: :method selects the curve"
        (let [linear (mapv :score (logic/normalize-results-to-ceiling
                                   results :method :linear))
              sigmoid (mapv :score (logic/normalize-results-to-ceiling
                                    results :method :sigmoid))]
          (println "  [N] linear =" (pr-str linear) "| sigmoid =" (pr-str sigmoid))
          (is (not= linear sigmoid) ":method must actually change the output")
          (is (= linear (mapv #(/ (double %) (double limit)) raw))
              ":linear is score/ceiling against the ENCODING's ceiling")
          (is (every? #(<= 0.0 % 1.0) sigmoid))
          (is (strictly-descending? sigmoid)
              "BoundedNormalization holds on the sigmoid branch too")))
      (testing "entity-fields.NormalizationOptions: :max-score overrides the ceiling"
        (let [override (mapv :score (logic/normalize-results-to-ceiling
                                     results :max-score 200.0))]
          (is (= override (mapv #(/ (double %) 200.0) raw))
              "an explicit maximum_score wins over the derived ceiling")))
      (testing "value-equality.NormalizationOptions: equal options, equal results"
        (is (= (logic/normalize-results-to-ceiling results :method :linear)
               (logic/normalize-results-to-ceiling results :method :linear))
            "the options are a VALUE — the same options normalize the same way")
        (is (not= (logic/normalize-results-to-ceiling results :method :linear)
                  (logic/normalize-results-to-ceiling results :method :sigmoid))
            "and different options are a different value"))
      (testing "the truncation report survives normalization"
        (is (= (:query-truncation (meta results))
               (:query-truncation (meta (logic/normalize-results-to-ceiling results))))
            "a normalized collection still answers a query that may have been cut"))
      (testing "an empty collection normalizes to an empty collection"
        (is (= [] (logic/normalize-results-to-ceiling [])))))))


(deftest the-public-interface-exposes-the-threaded-ceiling
  (with-index-root
    (let [limit 96
          results (search-index-at limit "interface")]
      (testing "results-maxsim-ceiling passes through"
        (is (= (double limit) (colbert/results-maxsim-ceiling results))))
      (testing "normalize-results-to-ceiling passes through, options and all"
        (is (= (logic/normalize-results-to-ceiling results)
               (colbert/normalize-results-to-ceiling results)))
        (is (= (logic/normalize-results-to-ceiling results :method :sigmoid)
               (colbert/normalize-results-to-ceiling results :method :sigmoid))))
      (testing "and normalize-colbert-score threads it via :max-score"
        (let [ceiling (colbert/results-maxsim-ceiling results)
              norm (mapv #(colbert/normalize-colbert-score (:score %) :max-score ceiling)
                         results)]
          (println "  [N] via the public interface =" (pr-str norm))
          (is (every? #(<= 0.0 % 1.0) norm))
          (is (strictly-descending? norm))
          (is (= norm (mapv :score (colbert/normalize-results-to-ceiling results)))))))))


(deftest rerank-normalization-follows-its-own-encoding-ceiling
  (let [process-default (long (logic/configured-maximum-query-tokens))
        limit 96
        results (logic/rerank {} {:query query
                                       :documents documents
                                       :maximum-query-tokens limit})
        raw (mapv :score results)]
    (testing "the probe is non-vacuous"
      (println "  [N] rerank at" limit "raw scores (n=" (count raw) ") =" (pr-str raw))
      (is (= 3 (count results)))
      (is (not= limit process-default))
      (is (= limit (:maximum-query-tokens (:query-truncation (meta results)))))
      (is (strictly-descending? raw)))
    (testing "BoundedNormalization holds on the index-free path too"
      (let [norm (mapv :score (logic/normalize-results-to-ceiling results))]
        (println "  [N] rerank normalized =" (pr-str norm))
        (is (= (double limit) (logic/results-maxsim-ceiling results)))
        (is (every? #(<= 0.0 % 1.0) norm))
        (is (strictly-descending? norm))
        (is (> (apply max norm) 0.9) "not compressed against the process default")))))
