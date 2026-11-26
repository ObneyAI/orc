(ns ai.obney.orc.ontology.domain-penalty-colbert-seam-test
  "Slice 0 (JVM-ColBERT, poly boundary fix): the colbert RESOLVER SEAM in
   domain-penalty.

   domain-penalty used to STATICALLY require ai.obney.orc.colbert.interface —
   the ONE production namespace that did — which broke the shipped orc-ontology
   project's 'ontology works fully without colbert' contract (poly Error 107).
   The real :colbert backend now resolves rerank / normalize-colbert-score
   dynamically at call time through an INJECTABLE resolver seam
   (dp/*colbert-resolver*), so these tests fake 'colbert absent' WITHOUT
   classpath surgery. Three semantics under test:

     1. ABSENT + caller EXPLICITLY configured :scorer :colbert  => precise
        ex-info (naming the missing component + the :scorer :embedding
        alternative) — loud at the source.
     2. ABSENT + DEFAULT config (no explicit scorer preference)  => the
        existing :embedding backend is selected with a mulog warning naming
        the substitution (the documented graceful-degradation contract).
     3. PRESENT (resolver returns fns)                            => identical
        scoring path as the injected-fn arity (parity), i.e. today's behavior.

   NOTE: this namespace deliberately does NOT require the colbert interface —
   absence is simulated via the seam, presence via a fake resolver."
  (:require [ai.obney.orc.ontology.core.logic :as logic]
            [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [com.brunobonacci.mulog.core :as mulog-core]))

(def ^:private absent
  "A resolver that simulates 'colbert not on the classpath'."
  (constantly nil))


(defn- ex-info-from
  "Run thunk; return the ExceptionInfo it throws, or nil if it returned."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e e)))

(defn- assert-colbert-unavailable-ex [ex via]
  (is (some? ex) (str via " throws ex-info when colbert is explicitly requested but absent"))
  (when ex
    (let [data (ex-data ex)]
      (is (= :colbert-unavailable (:error data)) (str via ": :error names the condition"))
      (is (= 'ai.obney.orc.colbert.interface (:missing-component data))
          (str via ": the missing component is named in ex-data"))
      (is (= {:scorer :embedding} (:alternative data))
          (str via ": the :scorer :embedding alternative is named in ex-data"))
      (is (re-find #"(?i)colbert" (ex-message ex)) (str via ": message names colbert"))
      (is (re-find #"(?i):scorer :embedding" (ex-message ex))
          (str via ": message names the :embedding alternative")))))

(deftest absent-plus-explicit-colbert-throws-precise-ex-info
  (testing "production path shape: operator EXPLICITLY set :scorer :colbert in ctx
            :domain-penalty-config (the pre-merge map), colbert unresolvable
            => make-batch-scorer / make-scorer throw the precise ex-info"
    (binding [logic/*colbert-resolver* absent]
      (let [ctx {:domain-penalty-config {:scorer :colbert}}
            merged (merge logic/default-penalty-config {:scorer :colbert})]
        (assert-colbert-unavailable-ex
         (ex-info-from #(logic/make-batch-scorer ctx merged)) "make-batch-scorer")
        (assert-colbert-unavailable-ex
         (ex-info-from #(logic/make-scorer ctx merged)) "make-scorer"))))

  (testing "direct caller shape: nil ctx, config explicitly names :scorer :colbert
            (not the shipped default map) => throws"
    (binding [logic/*colbert-resolver* absent]
      (assert-colbert-unavailable-ex
       (ex-info-from #(logic/make-batch-scorer nil {:scorer :colbert}))
       "make-batch-scorer (direct config)")))

  (testing "direct constructor: colbert-scorer / batch-colbert-scorer real-backend
            arity IS an explicit colbert request => throws when absent"
    (binding [logic/*colbert-resolver* absent]
      (assert-colbert-unavailable-ex
       (ex-info-from #(logic/colbert-scorer nil logic/default-penalty-config))
       "colbert-scorer 2-arity")
      (assert-colbert-unavailable-ex
       (ex-info-from #(logic/batch-colbert-scorer nil logic/default-penalty-config))
       "batch-colbert-scorer 2-arity"))))


(def ^:private vocab
  ["extract" "refactor" "rename" "pure" "helper" "handler" "build" "config"])

(defn- bow [s]
  (let [low (str/lower-case (or s ""))]
    (mapv (fn [w] (if (str/includes? low w) 1.0 0.0)) vocab)))

(def ^:private fallback-candidates
  [{:document-id "A" :fitness-score 0.95
    :avoid-when ["extract or refactor, not a pure rename"]
    :content "behavior-preserving pure rename of a symbol"
    :strengths [{:good-when "a pure rename across files"}]}
   {:document-id "B" :fitness-score 0.80
    :avoid-when ["a one-line config tweak"]
    :content "build/extract/refactor code structure into a helper"
    :strengths [{:good-when "extract a pure helper from a handler"}]}])

(def ^:private fallback-task
  "refactor: extract a pure helper from the request handler")

(defn- capture-mulog
  "Intercept the fn mu/log expands to (com.brunobonacci.mulog.core/log*) and
   collect {:event <kw> :pairs <map>} entries while running thunk. Returns
   [events result]."
  [thunk]
  (let [logs (atom [])]
    (with-redefs [mulog-core/log* (fn [_logger event-name pairs]
                                    (swap! logs conj {:event event-name
                                                      :pairs (apply hash-map pairs)}))]
      (let [result (thunk)]
        [@logs result]))))

(deftest absent-plus-default-config-selects-embedding-backend-with-warning
  (testing "colbert unresolvable + DEFAULT config (caller expressed no scorer
            preference) => penalize-candidates runs on the existing :embedding
            backend (scores are REAL, not fail-open zeros) and a mulog warning
            names the substitution"
    (let [embed-calls (atom [])
          fake-embed (fn ([s] (swap! embed-calls conj s) (bow s))
                       ([s _] (swap! embed-calls conj s) (bow s)))]
      (binding [logic/*colbert-resolver* absent]
        (with-redefs [logic/embedding-embed-text fake-embed]
          (let [[logs out] (capture-mulog
                            #(logic/penalize-candidates nil fallback-candidates fallback-task))
                warnings (filter #(= :ai.obney.orc.ontology.core.domain-penalty/colbert-unavailable-fallback (:event %)) logs)
                by-id (into {} (map (juxt :document-id identity)) out)]
            (is (some #{fallback-task} @embed-calls)
                "the task was embedded — the :embedding backend was selected and used")
            (is (= 2 (count out)) "all candidates survive")
            (is (every? #(and (contains? % :cos-avoid) (contains? % :cos-good)
                              (contains? % :domain-penalty))
                        out)
                "every candidate is stamped with the scorer-shaped {:cos-avoid :cos-good} + penalty")
            (is (pos? (:cos-avoid (get by-id "A")))
                "cosines are REAL embedding scores, not fail-open 0.0")
            (is (= 1 (count warnings))
                "exactly one mulog warning names the colbert->embedding substitution")
            (when-let [{:keys [pairs]} (first warnings)]
              (is (= :colbert (:requested-scorer pairs)) "warning names what was requested")
              (is (= :embedding (:selected-scorer pairs)) "warning names what was substituted"))))))))

(deftest absent-plus-nonscorer-override-still-falls-back
  (testing "production ctx shape: operator override exists but names NO :scorer
            (e.g. only :penalty-scale) => still the DEFAULT-scorer case =>
            embedding fallback, no throw"
    (binding [logic/*colbert-resolver* absent]
      (with-redefs [logic/embedding-embed-text (fn ([s] (bow s)) ([s _] (bow s)))]
        (let [ctx {:domain-penalty-config {:penalty-scale 2.0}}
              merged (merge logic/default-penalty-config {:penalty-scale 2.0})
              [logs out] (capture-mulog
                          #(logic/penalize-candidates ctx fallback-candidates fallback-task merged))]
          (is (= 2 (count out)) "no throw: the pass completed")
          (is (some #(= :ai.obney.orc.ontology.core.domain-penalty/colbert-unavailable-fallback (:event %)) logs)
              "the substitution warning fired"))))))


(defn- fake-colbert-score
  "Deterministic per-doc score (token overlap with the task * 5.0) — per-doc
   independent of the other docs, mirroring MaxSim's set-independence."
  [content]
  (let [t-tokens (set (str/split (str/lower-case fallback-task) #"\W+"))
        c-tokens (str/split (str/lower-case content) #"\W+")]
    (* 5.0 (count (filter t-tokens c-tokens)))))

(deftest present-resolver-scores-identically-to-injected-arity
  (testing "resolver PRESENT (fake {:rerank :normalize}) => the real-backend
            2-arity produces IDENTICAL {:cos-avoid :cos-good} to the injected
            rerank-fn/norm-fn 4-arity on the same inputs (per-candidate + batch),
            and the default config path uses colbert with NO substitution warning"
    (let [rerank-calls (atom 0)
          fake-rerank (fn [_ctx {:keys [documents]}]
                        (swap! rerank-calls inc)
                        (mapv (fn [c] {:content c :score (fake-colbert-score c)})
                              documents))
          fake-norm (fn [score & {:keys [max-score]}]
                      (min 1.0 (max 0.0 (/ (double score)
                                           (double (or max-score 40.0))))))
          resolver (constantly {:rerank fake-rerank :normalize fake-norm})
          cfg logic/default-penalty-config
          cand (first fallback-candidates)]
      (binding [logic/*colbert-resolver* resolver]
        (let [via-resolver ((logic/colbert-scorer nil cfg) cand fallback-task)
              via-injected ((logic/colbert-scorer nil cfg
                                               (fn [opts] (fake-rerank nil opts))
                                               fake-norm)
                            cand fallback-task)]
          (is (= via-injected via-resolver)
              "colbert-scorer: resolver path == injected-fn path on the same inputs")
          (is (pos? (:cos-avoid via-resolver))
              "the parity is on NONTRIVIAL scores (avoid guard overlaps the task)"))
        (let [lookup-resolver ((logic/batch-colbert-scorer nil cfg)
                               fallback-candidates fallback-task)
              lookup-injected ((logic/batch-colbert-scorer nil cfg
                                                        (fn [opts] (fake-rerank nil opts))
                                                        fake-norm)
                               fallback-candidates fallback-task)]
          (doseq [c fallback-candidates]
            (is (= (lookup-injected c) (lookup-resolver c))
                (str (:document-id c) ": batch resolver path == injected path"))))
        (let [calls-before @rerank-calls
              [logs out] (capture-mulog
                          #(logic/penalize-candidates nil fallback-candidates fallback-task))]
          (is (= (inc calls-before) @rerank-calls)
              "penalize-candidates used the colbert backend (exactly one batched call)")
          (is (= 2 (count out)))
          (is (not-any? #(= :ai.obney.orc.ontology.core.domain-penalty/colbert-unavailable-fallback (:event %)) logs)
              "no substitution warning when colbert is present"))))))
