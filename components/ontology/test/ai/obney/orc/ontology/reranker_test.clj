(ns ai.obney.orc.ontology.reranker-test
  "Tests for C-2b-2: LLM reranker over ColBERT recall.

   The reranker is a single-:llm-node ORC workflow that takes
   (query, intent, candidates) and returns reordered top-N with
   per-candidate :reasoning + :fitness-score. Activated by
   `:rerank-with-intent` on `ontology/search-descriptions`."
  (:require [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas :as ontology-schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.ontology.core.reranker :as reranker]
            [dscloj.core]
            [ai.obney.orc.colbert.interface :as colbert]
            [ai.obney.orc.colbert.interface.schemas]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.schemas]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]))

;; =============================================================================
;; Test context helpers
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/c2b2-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :event-pubsub ps
                  :dscloj-provider :openrouter
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

;; =============================================================================
;; RED #1 — reranked-result schema validates well-formed entries
;; =============================================================================

(deftest reranked-result-schema-validates
  (testing "A well-formed {:document-id :reasoning :fitness-score} entry passes the schema"
    (let [entry {:document-id "node-type::llm"
                 :reasoning "Matches the caller's intent: needs structured-output LLM calls with retries inside map-each."
                 :fitness-score 0.92}]
      (is (m/validate ontology-schemas/reranked-result entry)
          (str "Entry should validate. Explanation: "
               (pr-str (m/explain ontology-schemas/reranked-result entry))))))

  (testing "A vector of well-formed entries passes the reranked-results schema"
    (let [v [{:document-id "a" :reasoning "primary fit"  :fitness-score 0.9}
             {:document-id "b" :reasoning "secondary"    :fitness-score 0.5}
             {:document-id "c" :reasoning "weak match"   :fitness-score 0.1}]]
      (is (m/validate ontology-schemas/reranked-results v))))

  (testing ":fitness-score outside [0.0, 1.0] is rejected"
    (let [bad-low  {:document-id "a" :reasoning "x" :fitness-score -0.1}
          bad-high {:document-id "a" :reasoning "x" :fitness-score 1.5}]
      (is (not (m/validate ontology-schemas/reranked-result bad-low))
          "Below 0.0 must be rejected")
      (is (not (m/validate ontology-schemas/reranked-result bad-high))
          "Above 1.0 must be rejected")))

  (testing "Missing :reasoning is rejected (downstream consumers depend on it)"
    (let [bad {:document-id "a" :fitness-score 0.5}]
      (is (not (m/validate ontology-schemas/reranked-result bad))))))

;; =============================================================================
;; RED #2 — rerank! with fake LLM returns reordered candidates
;; =============================================================================

(defn- with-faked-rerank-llm
  "Run body with dscloj/predict stubbed to return a deterministic
   reranked-results vector. The workflow's :writes is :reranked-json
   (a JSON string) — the fake produces that shape; the production
   rerank! fn parses + canonicalizes it back to Clojure."
  [reranked-results f]
  (let [json-keyed (mapv (fn [r]
                           {:document_id (:document-id r)
                            :reasoning (:reasoning r)
                            :fitness_score (:fitness-score r)})
                         reranked-results)
        payload (clojure.data.json/write-str json-keyed)]
    (with-redefs [dscloj.core/predict
                  (fn [_provider _module _inputs _options]
                    {:outputs {:reranked-json payload}
                     :usage {:total-tokens 100}
                     :model "fake-model"})]
      (f))))

(deftest rerank-returns-reordered-candidates-from-llm
  (testing "Given 3 candidates in input order, the reranker passes through the LLM's preferred ordering"
    (with-test-ctx [ctx]
      (let [candidates [{:content "Document A about chunked extraction"
                         :score 0.8
                         :document-id "a"
                         :document-metadata {:granularity :tree-fingerprint :target-id "a"}}
                        {:content "Document B about parallel processing"
                         :score 0.7
                         :document-id "b"
                         :document-metadata {:granularity :tree-fingerprint :target-id "b"}}
                        {:content "Document C about sequence pipelines"
                         :score 0.6
                         :document-id "c"
                         :document-metadata {:granularity :tree-fingerprint :target-id "c"}}]
            ;; The fake LLM returns the candidates in REVERSE order
            fake-reranked [{:document-id "c" :reasoning "best fit for sequential" :fitness-score 0.95}
                           {:document-id "b" :reasoning "secondary fit"           :fitness-score 0.6}
                           {:document-id "a" :reasoning "weakest fit"             :fitness-score 0.3}]]
        (with-faked-rerank-llm fake-reranked
          (fn []
            (let [result (reranker/rerank! ctx
                           {:query "find me a sequential pipeline pattern"
                            :intent "I want to chain LLM calls where each consumes the previous output"
                            :candidates candidates})]
              (is (vector? result))
              (is (= ["c" "b" "a"]
                     (mapv :document-id result))
                  "Result is ordered per the fake LLM's response (reverse of input)"))))))))

;; =============================================================================
;; RED #3 — every reranked entry carries :reasoning + :fitness-score
;; =============================================================================

(deftest rerank-result-carries-reasoning-and-fitness-score
  (testing "Every entry in the reranked result has a non-empty :reasoning and a numeric :fitness-score in [0.0, 1.0]"
    (with-test-ctx [ctx]
      (let [candidates [{:content "A" :score 0.8 :document-id "a"
                         :document-metadata {:granularity :node-type :target-id "a"}}
                        {:content "B" :score 0.7 :document-id "b"
                         :document-metadata {:granularity :node-type :target-id "b"}}]
            fake [{:document-id "a"
                   :reasoning "The :llm node fits the caller's intent because it supports per-chunk structured output with :output-schemas."
                   :fitness-score 0.88}
                  {:document-id "b"
                   :reasoning "The :code node is a weaker fit — the intent requires LLM judgment, not a deterministic transform."
                   :fitness-score 0.25}]]
        (with-faked-rerank-llm fake
          (fn []
            (let [result (reranker/rerank! ctx
                           {:query "best node for per-chunk extraction"
                            :intent "I want per-chunk LLM extraction with bounded concurrency"
                            :candidates candidates})]
              (is (every? (fn [r]
                            (and (string? (:reasoning r))
                                 (pos? (count (:reasoning r)))))
                          result)
                  "Every entry has a non-empty :reasoning string")
              (is (every? (fn [r]
                            (let [s (:fitness-score r)]
                              (and (number? s) (<= 0.0 s 1.0))))
                          result)
                  "Every entry has a numeric :fitness-score in [0.0, 1.0]")
              (is (m/validate ontology-schemas/reranked-results result)
                  "Full result vector validates against the schema"))))))))

;; =============================================================================
;; RED #4 — search-descriptions without :rerank-with-intent skips the reranker
;; =============================================================================

(defn- inject-index-created!
  "Append a :colbert/index-created event so latest-ontology-descriptions-index
   has something to find. Used to put search-descriptions into its non-cold
   path without spinning up real ColBERT."
  [ctx]
  (es/append (:event-store ctx)
    {:tenant-id (:tenant-id ctx)
     :events [(es/->event
                {:type :colbert/index-created
                 :tags #{}
                 :body {:index-id (random-uuid)
                        :index-name "ontology-descriptions"
                        :index-path "/tmp/c2b2-test-index"
                        :documents ["doc-1"]
                        :document-ids ["id-1"]
                        :document-count 1
                        :passage-count 1
                        :model-name "colbert-ir/colbertv2.0"
                        :config {:split-documents? true
                                 :max-document-length 256
                                 :use-faiss? false}
                        :created-at "2026-05-27T00:00:00Z"}})]}))

(deftest search-without-rerank-intent-skips-reranker
  (testing "When :rerank-with-intent is NOT provided, search-descriptions returns pure ColBERT and never invokes the reranker workflow"
    (with-test-ctx [ctx]
      (inject-index-created! ctx)
      (Thread/sleep 100)
      (let [rerank-called? (atom false)
            colbert-results [{:content "doc A" :score 0.9 :rank 1
                              :document-id "a"
                              :document_metadata {:granularity "node-type"
                                                  :target-id "validate"
                                                  :confidence 0.8
                                                  :last-update "2026-05-27"}}]]
        (with-redefs [colbert/search (fn [_ctx _opts] colbert-results)
                      reranker/rerank! (fn [_ctx _opts]
                                          (reset! rerank-called? true)
                                          [])]
          (let [results (ontology/search-descriptions ctx
                          {:query "validation loop"
                           :k 5})]
            (is (false? @rerank-called?)
                "reranker/rerank! must NOT be invoked when :rerank-with-intent is absent")
            (is (= 1 (count results))
                "Pure ColBERT results returned through")
            (is (= "a" (-> results first :document-id))
                "Document id passes through")))))))

;; =============================================================================
;; RED #5 — search-descriptions with :rerank-with-intent invokes reranker
;; =============================================================================

(deftest search-with-rerank-intent-invokes-reranker
  (testing "When :rerank-with-intent is provided, search-descriptions calls reranker/rerank! with (query, intent, candidates) and the reranker's ordering is reflected in the final result"
    (with-test-ctx [ctx]
      (inject-index-created! ctx)
      (Thread/sleep 100)
      (let [captured-args (atom nil)
            ;; Pure ColBERT returns 3 candidates in score order a > b > c
            colbert-results [{:content "node-type llm content"
                              :score 0.9 :rank 1
                              :document-id "a"
                              :document_metadata {:granularity "node-type" :target-id "llm"
                                                  :confidence 0.8 :last-update "2026"}}
                             {:content "node-type code content"
                              :score 0.7 :rank 2
                              :document-id "b"
                              :document_metadata {:granularity "node-type" :target-id "code"
                                                  :confidence 0.6 :last-update "2026"}}
                             {:content "node-type map-each content"
                              :score 0.5 :rank 3
                              :document-id "c"
                              :document_metadata {:granularity "node-type" :target-id "map-each"
                                                  :confidence 0.4 :last-update "2026"}}]
            ;; Reranker prefers c > a > b for the given intent
            fake-rerank [{:document-id "c" :reasoning "best fit"   :fitness-score 0.95}
                         {:document-id "a" :reasoning "good fit"   :fitness-score 0.6}
                         {:document-id "b" :reasoning "weak fit"   :fitness-score 0.3}]]
        (with-redefs [colbert/search (fn [_ctx _opts] colbert-results)
                      reranker/rerank! (fn [_ctx opts]
                                          (reset! captured-args opts)
                                          fake-rerank)]
          (let [results (ontology/search-descriptions ctx
                          {:query "best node for iteration"
                           :rerank-with-intent "I want bounded-concurrency per-chunk processing"
                           :k 3})]
            (is (some? @captured-args)
                "reranker/rerank! was invoked")
            (is (= "best node for iteration" (:query @captured-args))
                ":query forwarded")
            (is (= "I want bounded-concurrency per-chunk processing"
                   (:intent @captured-args))
                ":intent forwarded")
            (is (sequential? (:candidates @captured-args))
                ":candidates forwarded as a sequential collection")
            (is (= ["c" "a" "b"] (mapv :document-id results))
                "Final result is in the reranker's order, not the ColBERT order")
            (is (every? #(string? (:reasoning %)) results)
                "Final results carry :reasoning from the reranker")
            (is (every? #(number? (:fitness-score %)) results)
                "Final results carry :fitness-score from the reranker")))))))

;; =============================================================================
;; RED #6 — K bounding: 2x over-fetch when reranking
;; =============================================================================

(deftest search-with-rerank-overfetches-2x
  (testing "When the caller asks for :k 10 with :rerank-with-intent, search-descriptions asks ColBERT for 20 candidates (2x over-fetch) so the reranker has signal"
    (with-test-ctx [ctx]
      (inject-index-created! ctx)
      (Thread/sleep 100)
      (let [captured-colbert-k (atom nil)]
        (with-redefs [colbert/search (fn [_ctx opts]
                                        (reset! captured-colbert-k (:k opts))
                                        ;; Return some matching docs so the
                                        ;; happy path completes
                                        (mapv (fn [i]
                                                {:content (str "doc " i)
                                                 :score (- 1.0 (* 0.01 i))
                                                 :rank (inc i)
                                                 :document-id (str "d" i)
                                                 :document_metadata {:granularity "node-type"
                                                                     :target-id (str "t" i)
                                                                     :confidence 0.5
                                                                     :last-update "2026"}})
                                              (range 20)))
                      reranker/rerank! (fn [_ctx opts]
                                          (mapv (fn [c]
                                                  {:document-id (:document-id c)
                                                   :reasoning "ok"
                                                   :fitness-score 0.5})
                                                (:candidates opts)))]
          (ontology/search-descriptions ctx
            {:query "anything"
             :rerank-with-intent "any intent"
             :k 10})
          (is (= 20 @captured-colbert-k)
              "ColBERT was asked for 20 (2x over-fetch) when :k 10 with rerank"))))))

;; =============================================================================
;; RED #7 — hard-cap at 50 candidates regardless of caller's :k
;; =============================================================================

(deftest search-with-rerank-hard-caps-at-50
  (testing "When the caller asks for :k 100 with rerank, ColBERT is asked for at most 50 (the hard cap), not 200"
    (with-test-ctx [ctx]
      (inject-index-created! ctx)
      (Thread/sleep 100)
      (let [captured-colbert-k (atom nil)]
        (with-redefs [colbert/search (fn [_ctx opts]
                                        (reset! captured-colbert-k (:k opts))
                                        (mapv (fn [i]
                                                {:content (str "doc " i)
                                                 :score (- 1.0 (* 0.01 i))
                                                 :rank (inc i)
                                                 :document-id (str "d" i)
                                                 :document_metadata {:granularity "node-type"
                                                                     :target-id (str "t" i)
                                                                     :confidence 0.5
                                                                     :last-update "2026"}})
                                              (range 50)))
                      reranker/rerank! (fn [_ctx opts]
                                          (mapv (fn [c]
                                                  {:document-id (:document-id c)
                                                   :reasoning "ok"
                                                   :fitness-score 0.5})
                                                (:candidates opts)))]
          (ontology/search-descriptions ctx
            {:query "anything"
             :rerank-with-intent "any intent"
             :k 100})
          (is (= 50 @captured-colbert-k)
              "ColBERT was asked for at most 50 even though caller asked for :k 100"))))))

;; =============================================================================
;; RED #8 — reranker instruction explicitly forbids status-shaped reasoning
;;
;; This is a FRAME check on the prompt constant, not a runtime check on LLM
;; output. We are verifying that the prompt the model sees contains the
;; anti-recency-style discipline so model outputs are principle-shaped by
;; construction. Same shape as C-2a-3c's prompt-frame assertions.
;; =============================================================================

(deftest reranker-instruction-forbids-status-shaped-reasoning
  (testing "The reranker instruction contains explicit principle-shape discipline so the LLM is told to produce concrete, actionable reasoning"
    (let [instr (str @#'reranker/reranker-instruction)]
      (is (re-find #"(?i)principle-shaped|concrete|actionable" instr)
          "Instruction explicitly tells the model to be principle-shaped / concrete / actionable")
      (is (re-find #"(?i)status-shaped|forbidden|do not produce" instr)
          "Instruction explicitly forbids status-shaped reasoning")
      ;; Examples of the FORBIDDEN status-shapes should be enumerated in
      ;; the prompt so the model knows what to avoid.
      (is (re-find #"(?i)looks ok|seems fine|unclear|further evaluation" instr)
          "Instruction enumerates forbidden status-shape examples"))))

;; =============================================================================
;; RED #9 — fail-soft to pure ColBERT when the reranker throws
;; =============================================================================

(deftest search-fail-soft-on-rerank-error
  (testing "If the reranker workflow throws, search-descriptions returns the pure-ColBERT top-K stamped as fallback (post-R01 contract)"
    (with-test-ctx [ctx]
      (inject-index-created! ctx)
      (Thread/sleep 100)
      (let [colbert-results [{:content "A" :score 0.9 :rank 1 :document-id "a"
                              :document_metadata {:granularity "node-type" :target-id "a"
                                                  :confidence 0.8 :last-update "2026"}}
                             {:content "B" :score 0.7 :rank 2 :document-id "b"
                              :document_metadata {:granularity "node-type" :target-id "b"
                                                  :confidence 0.5 :last-update "2026"}}]]
        (with-redefs [colbert/search (fn [_ctx _opts] colbert-results)
                      reranker/rerank! (fn [_ctx _opts]
                                          (throw (ex-info "Simulated LLM failure" {})))]
          (let [results (ontology/search-descriptions ctx
                          {:query "anything"
                           :rerank-with-intent "any intent"
                           :k 2})]
            (is (= 2 (count results))
                "Pure-ColBERT top-K is returned despite rerank failure")
            (is (= ["a" "b"] (mapv :document-id results))
                "Results are in pure-ColBERT order, not reranked")
            ;; Post-R01: fields are PRESENT with nil value so callers can
            ;; detect the absence. :rerank-source is :colbert-fallback.
            (is (every? #(nil? (:reasoning %)) results)
                ":reasoning is explicitly nil on fallback (R01)")
            (is (every? #(nil? (:fitness-score %)) results)
                ":fitness-score is explicitly nil on fallback (R01)")
            (is (every? #(= :colbert-fallback (:rerank-source %)) results)
                ":rerank-source is :colbert-fallback (R01)")))))))

(deftest search-fail-soft-on-rerank-empty-result
  (testing "If the reranker returns nil/empty, search-descriptions still returns the pure-ColBERT top-K (graceful degradation)"
    (with-test-ctx [ctx]
      (inject-index-created! ctx)
      (Thread/sleep 100)
      (let [colbert-results [{:content "A" :score 0.9 :rank 1 :document-id "a"
                              :document_metadata {:granularity "node-type" :target-id "a"
                                                  :confidence 0.8 :last-update "2026"}}]]
        (with-redefs [colbert/search (fn [_ctx _opts] colbert-results)
                      reranker/rerank! (fn [_ctx _opts] nil)]
          (let [results (ontology/search-descriptions ctx
                          {:query "x"
                           :rerank-with-intent "x"
                           :k 1})]
            (is (= 1 (count results)) "ColBERT top-K returned")
            (is (= "a" (-> results first :document-id)))))))))
