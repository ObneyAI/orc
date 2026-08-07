(ns ai.obney.orc.colbert.bounded-normalization-test
  "CC-25 — `invariant.BoundedNormalization` (specs/colbert.allium):

     Normalized scores lie from zero through one while preserving relative
     order among retained results.

   CC-17 made the query limit per-index CONFIGURATION on the ENCODE side
   (`IndexConfiguration.maximum_query_tokens`, threaded into
   `encoder/encode-query` by operations/search and operations/search-batch)
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
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.obney.orc.colbert.colbert-test-support :as support]
            [ai.obney.orc.colbert.core.encoder :as encoder]
            [ai.obney.orc.colbert.core.model-store :as model-store]
            [ai.obney.orc.colbert.core.operations :as operations]
            [ai.obney.orc.colbert.core.read-models :as read-models]
            [ai.obney.orc.colbert.interface :as colbert]))

(use-fixtures :once support/with-model-path)

(defn- test-encoder [] (encoder/get-encoder (model-store/resolve-model-dir)))

(def documents
  ["Any dispute arising under this agreement shall be settled by binding arbitration."
   "Either party may terminate this agreement with thirty days written notice."
   "The midfielder scored twice in the final minutes of the championship match."])

(def query "binding arbitration of contract disputes")

(defn- temp-index-root ^String []
  (str (.toFile (java.nio.file.Files/createTempDirectory
                 "colbert-cc25-" (make-array java.nio.file.attribute.FileAttribute 0)))))

(defmacro with-index-root [& body]
  `(let [prev# (System/getProperty operations/index-root-property)]
     (System/setProperty operations/index-root-property (temp-index-root))
     (try ~@body
          (finally (if prev#
                     (System/setProperty operations/index-root-property prev#)
                     (System/clearProperty operations/index-root-property))))))

(defn- search-index-at
  "Build a REAL index whose IndexConfiguration.maximum_query_tokens is
   `limit`, then really search it. Returns the result collection, truncation
   metadata and all."
  [limit label]
  (let [created (operations/create-index! {}
                  {:collection documents
                   :document-ids ["d1" "d2" "d3"]
                   :index-name (str "cc25-" label "-" limit)
                   :split-documents? false
                   :maximum-query-tokens limit})]
    (with-redefs [read-models/get-index
                  (fn [_ id] (when (= id (:index-id created))
                               {:index-id id :index-path (:index-path created)
                                :config (:config created) :status :active}))]
      (operations/search {} {:query query :index-id (:index-id created) :k 3}))))

(defn- strictly-descending?
  "The invariant's second clause, stated as a property over the retained
   results: every adjacent pair keeps its strict ordering. `(apply > coll)` on
   a clamped-flat vector is FALSE, which is exactly the signal wanted."
  [scores]
  (apply > scores))

;; =============================================================================
;; Cycle 1 — an index ABOVE the process default. Every raw score exceeds the
;; process ceiling, so a process-global normalization clamps them all to 1.0.
;; =============================================================================

(deftest above-default-index-normalization-is-bounded-and-order-preserving
  (with-index-root
    (let [enc (test-encoder)
          positions (long (get-in enc [:consts :max-position-embeddings]))
          process-default (long (encoder/configured-maximum-query-tokens))
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
        (is (not= (operations/maxsim-ceiling) (operations/results-maxsim-ceiling results))
            "the two sources of truth really do diverge here")
        (is (= (double limit) (operations/results-maxsim-ceiling results))
            "the results' own ceiling is the limit the encoding ran under"))
      (testing "BoundedNormalization: bounded AND order-preserving"
        (let [normalized (operations/normalize-results-to-ceiling results)
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

;; =============================================================================
;; Cycle 3 — an index BELOW the process default. Nothing clamps here, so the
;; damage is quieter: the whole result set is squeezed into a sliver at the
;; bottom of the [0,1] scale, and every downstream threshold fitted to the
;; scale silently means something else.
;; =============================================================================

(deftest below-default-index-normalization-does-not-compress-the-scale
  (with-index-root
    (let [process-default (long (encoder/configured-maximum-query-tokens))
          limit 96
          results (search-index-at limit "below")
          raw (mapv :score results)
          process-ceiling (operations/maxsim-ceiling)]
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
        (let [norm (mapv :score (operations/normalize-results-to-ceiling results))]
          (println "  [N] normalized =" (pr-str norm)
                   "| against the process ceiling =" (pr-str (mapv #(/ % process-ceiling) raw)))
          (is (every? #(<= 0.0 % 1.0) norm))
          (is (strictly-descending? norm))
          (is (> (apply max norm) 0.9)
              "the top score sits near the ceiling it was normalized against, not at 0.2")))
      (testing "the normalized scale is INVARIANT to the index's configured limit"
        ;; The scale-free statement of the same property, with no magic number:
        ;; the same query over the same documents must land in the same place on
        ;; [0,1] whether the index was built at 96 rows or at the default. (Not
        ;; bit-identical: the [MASK] pedestal's share of the ceiling drifts
        ;; slightly with the row count — the CC-17 headroom measurement.)
        (let [at-limit (mapv :score (operations/normalize-results-to-ceiling results))
              at-default (mapv :score (operations/normalize-results-to-ceiling
                                       (search-index-at process-default "below-ref")))
              deltas (mapv (fn [a b] (Math/abs (- (double a) (double b))))
                           at-limit at-default)]
          (println "  [N] scale deltas vs the default-configured index =" (pr-str deltas))
          (is (every? #(< % 0.02) deltas)
              "configuration must move the ceiling, not the meaning of the number"))))))

;; =============================================================================
;; Cycle 4 — the regression guard. At the process default the two sources of
;; truth AGREE, which is precisely why the divergence stayed invisible; the fix
;; must be a bit-for-bit no-op there.
;; =============================================================================

(deftest default-configured-index-normalization-is-unchanged
  (with-index-root
    (let [process-default (long (encoder/configured-maximum-query-tokens))
          results (search-index-at process-default "default")
          raw (mapv :score results)
          ;; The PRE-CC-25 computation, verbatim: normalize_score with no
          ;; :max-score, i.e. the process-default ceiling.
          before (mapv #(operations/normalize-colbert-score (:score %)) results)
          after (mapv :score (operations/normalize-results-to-ceiling results))]
      (testing "the probe is non-vacuous — it really is at the process default"
        (println "  [N] process default =" process-default
                 "| raw scores (n=" (count raw) ") =" (pr-str raw))
        (is (= 3 (count results)))
        (is (= process-default
               (:maximum-query-tokens (:query-truncation (meta results)))))
        (is (strictly-descending? raw)))
      (testing "the two ceilings AGREE here — the reason the defect stayed latent"
        (is (= (operations/maxsim-ceiling) (operations/results-maxsim-ceiling results))))
      (testing "and the normalized scores are bit-for-bit what they always were"
        (println "  [N] before =" (pr-str before) "| after =" (pr-str after))
        (is (= before after) "an index at the process default must be untouched")
        (is (every? #(<= 0.0 % 1.0) after))
        (is (strictly-descending? after))))))

;; =============================================================================
;; NormalizationOptions — entity-fields + value-equality, over the collection
;; form the ontology actually reaches for.
;; =============================================================================

(deftest normalization-options-fields-and-value-equality
  (with-index-root
    (let [limit 96
          results (search-index-at limit "options")
          raw (mapv :score results)]
      (testing "entity-fields.NormalizationOptions: :method selects the curve"
        (let [linear (mapv :score (operations/normalize-results-to-ceiling
                                   results :method :linear))
              sigmoid (mapv :score (operations/normalize-results-to-ceiling
                                    results :method :sigmoid))]
          (println "  [N] linear =" (pr-str linear) "| sigmoid =" (pr-str sigmoid))
          (is (not= linear sigmoid) ":method must actually change the output")
          (is (= linear (mapv #(/ (double %) (double limit)) raw))
              ":linear is score/ceiling against the ENCODING's ceiling")
          (is (every? #(<= 0.0 % 1.0) sigmoid))
          (is (strictly-descending? sigmoid)
              "BoundedNormalization holds on the sigmoid branch too")))
      (testing "entity-fields.NormalizationOptions: :max-score overrides the ceiling"
        (let [override (mapv :score (operations/normalize-results-to-ceiling
                                     results :max-score 200.0))]
          (is (= override (mapv #(/ (double %) 200.0) raw))
              "an explicit maximum_score wins over the derived ceiling")))
      (testing "value-equality.NormalizationOptions: equal options, equal results"
        (is (= (operations/normalize-results-to-ceiling results :method :linear)
               (operations/normalize-results-to-ceiling results :method :linear))
            "the options are a VALUE — the same options normalize the same way")
        (is (not= (operations/normalize-results-to-ceiling results :method :linear)
                  (operations/normalize-results-to-ceiling results :method :sigmoid))
            "and different options are a different value"))
      (testing "the truncation report survives normalization"
        (is (= (:query-truncation (meta results))
               (:query-truncation (meta (operations/normalize-results-to-ceiling results))))
            "a normalized collection still answers a query that may have been cut"))
      (testing "an empty collection normalizes to an empty collection"
        (is (= [] (operations/normalize-results-to-ceiling [])))))))

;; =============================================================================
;; The PUBLIC seam — `ai.obney.orc.colbert.interface` is what the ontology
;; consumes (domain-penalty resolves normalize-colbert-score through it), so
;; the threaded ceiling has to be reachable from there, not just from core.
;; =============================================================================

(deftest the-public-interface-exposes-the-threaded-ceiling
  (with-index-root
    (let [limit 96
          results (search-index-at limit "interface")]
      (testing "results-maxsim-ceiling passes through"
        (is (= (double limit) (colbert/results-maxsim-ceiling results))))
      (testing "normalize-results-to-ceiling passes through, options and all"
        (is (= (operations/normalize-results-to-ceiling results)
               (colbert/normalize-results-to-ceiling results)))
        (is (= (operations/normalize-results-to-ceiling results :method :sigmoid)
               (colbert/normalize-results-to-ceiling results :method :sigmoid))))
      (testing "and normalize-colbert-score threads it via :max-score"
        (let [ceiling (colbert/results-maxsim-ceiling results)
              norm (mapv #(colbert/normalize-colbert-score (:score %) :max-score ceiling)
                         results)]
          (println "  [N] via the public interface =" (pr-str norm))
          (is (every? #(<= 0.0 % 1.0) norm))
          (is (strictly-descending? norm))
          (is (= norm (mapv :score (colbert/normalize-results-to-ceiling results)))))))))

;; =============================================================================
;; `rerank` has no index, but it DOES take an explicit :maximum-query-tokens —
;; so the same divergence is reachable without an index artifact at all.
;; =============================================================================

(deftest rerank-normalization-follows-its-own-encoding-ceiling
  (let [process-default (long (encoder/configured-maximum-query-tokens))
        limit 96
        results (operations/rerank {} {:query query
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
      (let [norm (mapv :score (operations/normalize-results-to-ceiling results))]
        (println "  [N] rerank normalized =" (pr-str norm))
        (is (= (double limit) (operations/results-maxsim-ceiling results)))
        (is (every? #(<= 0.0 % 1.0) norm))
        (is (strictly-descending? norm))
        (is (> (apply max norm) 0.9) "not compressed against the process default")))))
