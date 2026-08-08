(ns ai.obney.orc.ontology.rr1-reranker-timeout-resilience-test
  "RR-1 (ADR 0020): the classify->rerank call gets an explicit, sized
   execution budget + one retry, and a TIMEOUT is distinguishable from a
   genuine rerank failure in the signal.

   Fault-injection boundary: `orc/execute` — the exact seam where the
   engine's generic 300000ms default used to be inherited. Everything below
   it (the reranker workflow build, the JSON parse/validate, apply-rerank's
   JOIN + stamping, classify-task/classify-behaviors' outcome derivation)
   is the REAL code path over a REAL grain event store (commands ->
   schema-validated events -> projections).

   Assertions read the classifier/search read-back, never a bare return
   value of the thing under test where a read-back exists."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.ontology.core.reranker :as reranker]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.colbert.interface :as colbert]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.schemas]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]))

;; =============================================================================
;; Real-grain test context (commands -> events -> projections; no bare appends)
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/rr1-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :event-pubsub ps
                  :llm-provider :openrouter
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  ::cache-dir cache-dir}
        processors (reduce-kv
                     (fn [acc proc-name {:keys [handler-fn topics]}]
                       (assoc acc proc-name
                              (tp/start {:event-pubsub ps :topics topics
                                         :handler-fn handler-fn :context base-ctx})))
                     {} @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defn- stop-context [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [es (:event-store ctx)] (es/stop es))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)]
     (try ~@body (finally (stop-context ~sym)))))

(def ^:private sample-candidates
  [{:content "chunked extraction over long documents"
    :score 0.9 :rank 1 :document-id "a"
    :document-metadata {:granularity :tree-fingerprint :target-id "a"
                        :confidence 0.8 :last-update "2026"}}
   {:content "risk analysis over contracts"
    :score 0.7 :rank 2 :document-id "b"
    :document-metadata {:granularity :tree-fingerprint :target-id "b"
                        :confidence 0.6 :last-update "2026"}}])

;; -----------------------------------------------------------------------------
;; search-descriptions index-discovery + ColBERT recall stubs.
;;
;; Same pattern (and same rationale) as the R01 rerank_failure_surfacing_test:
;; search-descriptions resolves its index through `colbert/list-indexes`, so
;; without the stub the lookup returns nil, search short-circuits to the
;; cold-no-index [] branch, and apply-rerank is never reached — the stamping
;; assertions would pass VACUOUSLY. ColBERT recall itself is not the subject
;; here; the fault boundary under test is `orc/execute`.
;; -----------------------------------------------------------------------------

(defn- fake-list-indexes [_ctx & _opts]
  [{:index-name "ontology-descriptions"
    :index-id   (random-uuid)
    :created-at "2026-05-28T00:00:00Z"}])

(defmacro with-discoverable-index [& body]
  `(with-redefs [colbert/list-indexes fake-list-indexes]
     ~@body))

(def ^:private colbert-candidates
  [{:content "chunked extraction over long documents"
    :score 0.9 :rank 1 :document-id "a"
    :document_metadata {:granularity "tree-fingerprint" :target-id "a"
                        :confidence 0.8 :last-update "2026"}}
   {:content "risk analysis over contracts"
    :score 0.7 :rank 2 :document-id "b"
    :document_metadata {:granularity "tree-fingerprint" :target-id "b"
                        :confidence 0.6 :last-update "2026"}}
   {:content "comparison of two documents"
    :score 0.5 :rank 3 :document-id "c"
    :document_metadata {:granularity "tree-fingerprint" :target-id "c"
                        :confidence 0.4 :last-update "2026"}}])

(def ^:private reranked-json
  "A well-formed reranker payload echoing the three candidate document-ids."
  (str "[{\"document_id\":\"a\",\"reasoning\":\"chunking fits the long-document read\",\"fitness_score\":0.91},"
       "{\"document_id\":\"b\",\"reasoning\":\"risk framing is adjacent but not the task\",\"fitness_score\":0.44},"
       "{\"document_id\":\"c\",\"reasoning\":\"comparison needs two inputs; task has one\",\"fitness_score\":0.20}]"))

(defn- timeout-result [] {:status :timeout :error "Execution timed out" :duration-ms 900001})
(defn- success-result [] {:status :success :outputs {:reranked-json reranked-json}})

;; The measured worst case the budget must clear (ADR 0020 / grill-input §2):
;; worst controlled-repro completion 10525 tokens at the worst LIVE observed
;; degraded throughput of ~25 tok/s = ~421s.
(def ^:private worst-observed-ms 421000)

;; =============================================================================
;; RED #1 — the classify->rerank call carries an explicit, SIZED :timeout-ms
;; =============================================================================

(deftest rerank-passes-explicit-sized-timeout-to-orc-execute
  (testing "rerank! passes an explicit :timeout-ms to orc/execute — not the inherited generic 300000ms default"
    (with-test-ctx [ctx]
      (let [seen (atom [])]
        (with-redefs [orc/execute (fn [_ctx _sheet-id _inputs & opts]
                                    (swap! seen conj (apply hash-map opts))
                                    {:status :success
                                     :outputs {:reranked-json "[]"}})]
          (reranker/rerank! ctx {:query "q" :intent "i" :candidates sample-candidates}))
        (is (= 1 (count @seen))
            "Sanity: the reranker workflow was executed exactly once on the success path")
        (let [t (:timeout-ms (first @seen))]
          (is (some? t)
              "an explicit :timeout-ms is passed (absent => orc/execute's generic 300000ms default is inherited)")
          (is (not= 300000 t)
              "the budget is NOT the inherited generic 300000ms workflow default (the cliff the live run tripped)")
          (is (>= t worst-observed-ms)
              (str "the budget clears the measured worst case (10525 completion tokens / 25 tok/s ~= "
                   worst-observed-ms "ms). Got: " t))
          (is (> t (* 1.5 worst-observed-ms))
              (str "the budget clears the worst case with REAL margin, not a bare match. Got: " t)))))))

;; =============================================================================
;; RED #2 — an induced timeout retries EXACTLY once, same model / same call
;; =============================================================================

(deftest rerank-retries-exactly-once-on-timeout
  (testing "when the rerank workflow times out, rerank! retries the SAME call once — and only once"
    (with-test-ctx [ctx]
      (let [calls (atom [])]
        (with-redefs [orc/execute (fn [_ctx sheet-id inputs & opts]
                                    (swap! calls conj {:sheet-id sheet-id
                                                       :inputs inputs
                                                       :opts (apply hash-map opts)})
                                    {:status :timeout :error "Execution timed out"})]
          (reranker/rerank! ctx {:query "q" :intent "i" :candidates sample-candidates}))
        (is (= 2 (count @calls))
            "exactly TWO executions: the original + ONE retry, then stop (no retry storm)")
        (is (apply = (map :sheet-id @calls))
            "the retry runs the SAME reranker workflow (same sheet-id) — not a rebuilt/degraded variant")
        (is (apply = (map :inputs @calls))
            "the retry sends the SAME inputs (same query/intent/candidates) — same model, same call")
        (is (apply = (map :opts @calls))
            "the retry carries the SAME budget as the first attempt")))))

(deftest rerank-does-not-retry-a-genuine-failure
  (testing "a non-timeout workflow failure is NOT retried — that is the reranker failing to rank, and apply-rerank's ColBERT fallback owns it (ADR 0015 behavior unchanged)"
    (with-test-ctx [ctx]
      (let [calls (atom 0)]
        (with-redefs [orc/execute (fn [_ctx _sheet-id _inputs & _opts]
                                    (swap! calls inc)
                                    {:status :failure :error "genuine rerank failure"})]
          (reranker/rerank! ctx {:query "q" :intent "i" :candidates sample-candidates}))
        (is (= 1 @calls)
            "a genuine :failure executes exactly once — the retry is timeout-specific")))))

;; =============================================================================
;; RED #3 — a retry that SUCCEEDS is invisible: identical to today's success
;; =============================================================================

(deftest retry-that-succeeds-looks-identical-to-today
  (testing "first attempt times out, retry succeeds → results are stamped :reranker with real fitness/reasoning; NO timeout marker leaks"
    (with-test-ctx [ctx]
      (let [attempts (atom 0)]
        (with-discoverable-index
          (with-redefs [colbert/search (fn [_ctx _opts] colbert-candidates)
                        orc/execute (fn [_ctx _sheet-id _inputs & _opts]
                                      (if (= 1 (swap! attempts inc))
                                        (timeout-result)
                                        (success-result)))]
            (let [results (ontology/search-descriptions ctx
                            {:query "x" :rerank-with-intent "y" :k 3})]
              (is (= 2 @attempts)
                  "Sanity: the first attempt timed out and exactly one retry ran")
              (is (= 3 (count results))
                  "Sanity: the reranked set came back")
              (is (every? #(= :reranker (:rerank-source %)) results)
                  "a recovered timeout is INVISIBLE — every result is stamped :reranker, exactly as today")
              (is (not-any? #(= :timeout-fallback (:rerank-source %)) results)
                  "no :timeout-fallback stamp on a retry that succeeded")
              (is (every? #(number? (:fitness-score %)) results)
                  "real fitness scores (the reranker's output, not the nil-stamped fallback)")
              (is (every? #(string? (:reasoning %)) results)
                  "real reasoning survives the retry"))))))))

;; =============================================================================
;; RED #4 — the two failure modes are DISTINGUISHABLE in the signal:
;;   timeout-after-retry  -> :rerank-source :timeout-fallback
;;   genuine throw/nil    -> :rerank-source :colbert-fallback  (unchanged)
;; =============================================================================

(deftest timeout-after-retry-stamps-timeout-fallback
  (testing "when the retry ALSO times out, every fallback result is stamped :rerank-source :timeout-fallback"
    (with-test-ctx [ctx]
      (let [attempts (atom 0)]
        (with-discoverable-index
          (with-redefs [colbert/search (fn [_ctx _opts] colbert-candidates)
                        orc/execute (fn [_ctx _sheet-id _inputs & _opts]
                                      (swap! attempts inc)
                                      (timeout-result))]
            (let [results (ontology/search-descriptions ctx
                            {:query "x" :rerank-with-intent "y" :k 3})]
              (is (= 2 @attempts)
                  "Sanity: original + one retry, both timed out")
              (is (= 3 (count results))
                  "the caller still gets the ColBERT-ordered fallback set — fail-soft is unchanged")
              (is (every? #(= :timeout-fallback (:rerank-source %)) results)
                  "the fallback reached via TIMEOUT is stamped :timeout-fallback")
              (is (every? #(nil? (:fitness-score %)) results)
                  ":fitness-score is explicitly nil, same as any other fallback")
              (is (every? #(nil? (:reasoning %)) results)
                  ":reasoning is explicitly nil, same as any other fallback"))))))))

(deftest genuine-failure-still-stamps-colbert-fallback
  (testing "a genuine rerank failure (throw / nil / empty) keeps stamping :colbert-fallback — distinguishable from the timeout case in this same suite"
    (with-test-ctx [ctx]
      (with-discoverable-index
        (with-redefs [colbert/search (fn [_ctx _opts] colbert-candidates)]
          (testing "reranker THROWS"
            (with-redefs [reranker/rerank! (fn [& _] (throw (ex-info "synthetic rerank failure" {})))]
              (let [results (ontology/search-descriptions ctx
                              {:query "x" :rerank-with-intent "y" :k 3})]
                (is (every? #(= :colbert-fallback (:rerank-source %)) results)
                    "a throw is a GENUINE rerank failure — :colbert-fallback, not :timeout-fallback")
                (is (not-any? #(= :timeout-fallback (:rerank-source %)) results)))))
          (testing "reranker returns nil"
            (with-redefs [reranker/rerank! (fn [& _] nil)]
              (let [results (ontology/search-descriptions ctx
                              {:query "x" :rerank-with-intent "y" :k 3})]
                (is (every? #(= :colbert-fallback (:rerank-source %)) results)
                    "a nil return is a GENUINE rerank failure — :colbert-fallback"))))
          (testing "reranker returns empty"
            (with-redefs [reranker/rerank! (fn [& _] [])]
              (let [results (ontology/search-descriptions ctx
                              {:query "x" :rerank-with-intent "y" :k 3})]
                (is (every? #(= :colbert-fallback (:rerank-source %)) results)
                    "an empty result is a GENUINE rerank failure — :colbert-fallback"))))
          (testing "workflow FAILS (non-timeout status) through the real rerank! path"
            (with-redefs [orc/execute (fn [_ctx _sheet-id _inputs & _opts]
                                        {:status :failure :error "genuine rerank failure"})]
              (let [results (ontology/search-descriptions ctx
                              {:query "x" :rerank-with-intent "y" :k 3})]
                (is (every? #(= :colbert-fallback (:rerank-source %)) results)
                    "a non-timeout workflow failure is GENUINE — :colbert-fallback")))))))))

;; =============================================================================
;; RED #5 — a :timeout-fallback DEFERS exactly like a :colbert-fallback
;; (ADR 0015 detect-and-defer is unchanged: same :outcome :uncertain, no mint)
;; =============================================================================

(def ^:private behavioral-colbert-candidates
  (mapv #(assoc-in % [:document_metadata :granularity] "behavioral-subtree")
        colbert-candidates))

(deftest classify-defers-on-timeout-fallback-just-like-colbert-fallback
  (testing "structural: a rerank timeout (after retry) derives :outcome :uncertain, assigns NOTHING, and the timeout reason survives onto the surfaced candidates"
    (with-test-ctx [ctx]
      (with-discoverable-index
        (with-redefs [colbert/search (fn [_ctx _opts] colbert-candidates)
                      orc/execute (fn [_ctx _sheet-id _inputs & _opts] (timeout-result))]
          (let [result (ontology/classify-task ctx
                         {:task-signature "read a long contract and pull the obligations"
                          :threshold 0.7
                          :walk-down? false})]
            (is (= :uncertain (:outcome result))
                "EL-3 / ADR 0015: a timeout is uncertainty, NOT novelty — defer")
            (is (true? (:rerank-fallback? result))
                ":rerank-fallback? is true, so the R-Inject caution still surfaces")
            (is (nil? (:assigned-tree-id result))
                "no class assigned — nothing accrues from a deferred turn")
            (is (not (true? (:was-fresh-mint? result)))
                "no fallback-mint (the 8/8 conflation stays fixed)")
            (is (= :timeout-fallback (-> result :top-candidates first :rerank-source))
                "the DISTINGUISHABLE reason rides through to the surfaced candidates"))))))

  (testing "behavioral: same defer, same distinguishable reason"
    (with-test-ctx [ctx]
      (with-discoverable-index
        (with-redefs [colbert/search (fn [_ctx _opts] behavioral-colbert-candidates)
                      orc/execute (fn [_ctx _sheet-id _inputs & _opts] (timeout-result))]
          (let [result (ontology/classify-behaviors ctx
                         {:task-signature "read a long contract and pull the obligations"
                          :threshold 0.6
                          :top-n 5})]
            (is (= :uncertain (:outcome result))
                "EL-3 / ADR 0015: behavioral axis defers on a timeout too")
            (is (true? (:rerank-fallback? result))
                ":rerank-fallback? true on the behavioral axis")
            (is (not-any? :was-fresh-mint? (:behaviors result))
                "no behavioral fresh-mint marker under a timeout")
            (is (= :timeout-fallback (-> result :behaviors first :rerank-source))
                "the behavioral entries carry the distinguishable :timeout-fallback source")))))))
