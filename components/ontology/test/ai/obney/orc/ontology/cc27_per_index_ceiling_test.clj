(ns ai.obney.orc.ontology.cc27-per-index-ceiling-test
  "CC-27 — the domain-penalty :linear/:sigmoid path must normalize each score
   by the ceiling of the ENCODING THAT PRODUCED IT, not the process default.

   CC-25 built the per-index ceiling seam on the colbert side
   (`results-maxsim-ceiling` reads `maximum_query_tokens` off the truncation
   report the encode call stamped on the result collection) — but the ontology
   consumer never threaded it: `default-penalty-config`'s
   `:colbert-norm {:max-score nil}` flowed a literal nil into
   `normalize-colbert-score`, which resolves the PROCESS default
   (`maxsim-ceiling`'s no-arg form). Above that default every score clamps to
   1.0 and relative order is destroyed (`invariant.BoundedNormalization`);
   below it the scale silently compresses. Safe today ONLY because one index
   runs at the default.

   TWO encodings whose `maximum_query_tokens` differ (one above the process
   default, one below — the process default can match at most one of them),
   scored through the PRODUCTION wiring: the resolver-seam 2-arity constructors
   with the REAL colbert normalize + ceiling fns; only the rerank round-trip is
   stubbed, and the stub stamps the exact truncation metadata the real
   `rerank` stamps (proven real by CC-25's
   rerank-normalization-follows-its-own-encoding-ceiling).

   NB: the shipped default `:method :batch-relative` divides by the call's max
   and never touches the ceiling — this defect bites the explicit
   :linear/:sigmoid configs only, so those are what these tests pin."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.core.domain-penalty :as dp]
            [ai.obney.orc.colbert.interface :as colbert]))

;; The process default, derived from the very function under scrutiny: with no
;; :max-score, normalize is score/PROCESS-ceiling (linear, un-clamped for a
;; score of 1.0), so the ceiling is its reciprocal. No encoder/model load —
;; the limit resolves from configuration (see colbert normalization_test).
(defn- process-default ^double []
  (/ 1.0 (double (colbert/normalize-colbert-score 1.0))))

(def ^:private task "refactor: extract a pure helper from the request handler")

(def ^:private candidate
  {:document-id "A"
   :fitness-score 0.95
   :avoid-when ["AVOID-GUARD"]
   :content "CONTENT"
   :strengths [{:good-when "GOOD-GUARD"}]})

(defn- stub-rerank
  "A rerank stub shaped like the real interface fn (ctx, opts): scores each
   document from `score-by-content` and stamps the SAME truncation-report
   metadata `attach-truncation` stamps on every real search/rerank collection —
   the limit the encoding really ran under."
  [limit score-by-content]
  (fn [_ctx {:keys [documents]}]
    (with-meta
      (mapv (fn [c] {:content c :score (double (score-by-content c))}) documents)
      {:query-truncation {:maximum-query-tokens limit
                          :query-token-count 8
                          :query-truncated? false
                          :discarded-token-count 0}})))

(defn- linear-cfg []
  (assoc dp/default-penalty-config :colbert-norm {:max-score nil :method :linear}))

(defn- scorer-at
  "The PRODUCTION wiring: colbert-scorer's resolver 2-arity, real normalize +
   real per-encoding ceiling fn, rerank stubbed at `limit`."
  [limit score-by-content]
  (binding [dp/*colbert-resolver*
            (constantly {:rerank (stub-rerank limit score-by-content)
                         :normalize colbert/normalize-colbert-score
                         :ceiling colbert/results-maxsim-ceiling})]
    (dp/colbert-scorer nil (linear-cfg))))

(deftest linear-normalization-uses-the-ceiling-of-the-encoding-that-produced-the-score
  (let [p (process-default)]
    (testing "an encoding ABOVE the process default: raw scores exceed the
              process ceiling, so the process-global nil-fallback clamps BOTH
              guards to the precise wrong value 1.0 and the contrast is
              destroyed; each must instead be normalized by its OWN ceiling"
      (let [limit (+ p 16.0)
            avoid-raw (+ p 6.0)
            good-raw (+ p 4.0)
            scores {"AVOID-GUARD" avoid-raw
                    "GOOD-GUARD" good-raw
                    "CONTENT" 1.0}
            ;; Non-vacuity: the stub's metadata is the exact shape the CC-25
            ;; seam reads, and both raw scores really exceed the process
            ;; ceiling (the clamp form of the defect).
            probe ((stub-rerank limit scores) nil {:documents ["AVOID-GUARD"]})
            _ (is (= (double limit) (colbert/results-maxsim-ceiling probe))
                  "the stubbed truncation report is readable by the real seam")
            _ (is (> good-raw p) "both probe scores sit above the process ceiling")
            {:keys [cos-avoid cos-good]} ((scorer-at limit scores) candidate task)]
        (is (= (/ avoid-raw limit) cos-avoid)
            "cos-avoid is normalized by the encoding's OWN ceiling")
        (is (= (/ good-raw limit) cos-good)
            "cos-good is normalized by the encoding's OWN ceiling")
        (is (not= 1.0 cos-avoid)
            "the process-default fallback's precise wrong value here is a 1.0 clamp")
        (is (> cos-avoid cos-good)
            "the avoid/good contrast survives normalization (BoundedNormalization's
             order clause — the clamp collapses it to 0.0)")))
    (testing "an encoding BELOW the process default: nothing clamps, the scale
              silently compresses instead — the precise wrong values are
              raw/process-default"
      (let [limit 96.0
            avoid-raw 90.0
            good-raw 60.0
            scores {"AVOID-GUARD" avoid-raw
                    "GOOD-GUARD" good-raw
                    "CONTENT" 1.0}
            _ (is (< limit p) "the probe encoding must sit BELOW the process default")
            _ (is (< (/ avoid-raw p) 0.25)
                  "against the process ceiling these scores sit in the bottom
                   quarter of the scale — that is the compression")
            {:keys [cos-avoid cos-good]} ((scorer-at limit scores) candidate task)]
        (is (= (/ avoid-raw limit) cos-avoid)
            "cos-avoid uses the whole scale of ITS OWN encoding (0.9375, not ~0.19)")
        (is (= (/ good-raw limit) cos-good)
            "cos-good likewise (0.625, not ~0.13)")
        (is (not= (/ avoid-raw p) cos-avoid)
            "the process-default fallback's precise wrong value here is raw/process-default")))))

(deftest batch-linear-normalization-uses-the-ceiling-of-the-encoding-that-produced-the-scores
  (testing "the EL-5.1 HOT PATH (batch-colbert-scorer, ONE rerank for all
            candidates) has the identical obligation: a nil :max-score means
            the batch call's OWN encoding ceiling, not the process default"
    (let [p (process-default)
          limit (+ p 16.0)
          avoid-raw (+ p 6.0)
          good-raw (+ p 4.0)
          scores {"AVOID-GUARD" avoid-raw
                  "GOOD-GUARD" good-raw
                  "CONTENT" 1.0}
          lookup (binding [dp/*colbert-resolver*
                           (constantly {:rerank (stub-rerank limit scores)
                                        :normalize colbert/normalize-colbert-score
                                        :ceiling colbert/results-maxsim-ceiling})]
                   ((dp/batch-colbert-scorer nil (linear-cfg)) [candidate] task))
          {:keys [cos-avoid cos-good]} (lookup candidate)]
      (is (= (/ avoid-raw limit) cos-avoid)
          "cos-avoid is normalized by the batch encoding's OWN ceiling")
      (is (= (/ good-raw limit) cos-good)
          "cos-good is normalized by the batch encoding's OWN ceiling")
      (is (not= 1.0 cos-avoid)
          "the process-default fallback's precise wrong value here is a 1.0 clamp")
      (is (> cos-avoid cos-good)
          "the avoid/good contrast survives (the clamp collapses it to 0.0)"))))

(deftest the-real-resolver-carries-the-per-encoding-ceiling-fn
  (testing "the default *colbert-resolver* (the real requiring-resolve impl)
            resolves CC-25's results-maxsim-ceiling alongside rerank/normalize,
            so the production 2-arity constructors get the per-encoding ceiling
            without any caller wiring it"
    (let [{:keys [rerank normalize ceiling] :as fns} (dp/*colbert-resolver*)]
      (is (some? fns) "colbert is on this classpath — the resolver must resolve")
      (is (= (resolve 'ai.obney.orc.colbert.interface/rerank) rerank)
          ":rerank is the exact interface var")
      (is (= (resolve 'ai.obney.orc.colbert.interface/normalize-colbert-score) normalize)
          ":normalize is the exact interface var")
      (is (= (resolve 'ai.obney.orc.colbert.interface/results-maxsim-ceiling) ceiling)
          ":ceiling is the exact CC-25 seam var — consumed, not re-invented"))))
