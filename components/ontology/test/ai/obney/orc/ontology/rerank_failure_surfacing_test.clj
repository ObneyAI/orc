(ns ai.obney.orc.ontology.rerank-failure-surfacing-test
  "Tests for R01: reranker failure surfacing.

   Covers:
   - :rerank-failed? optional on :ontology/task-classified event +
     :ontology/assign-task-class command schemas
   - apply-rerank stamps :rerank-source :reranker on every result when
     the reranker workflow succeeds
   - apply-rerank stamps :rerank-source :colbert-fallback + :fitness-score
     nil + :reasoning nil when the workflow returns nil OR throws OR
     produces empty results
   - classify-task computes :rerank-fallback? from top-1's :rerank-source
   - defcommand forwards :rerank-failed? from command to event body
   - C-2c-2 wedge forwards :rerank-fallback? from classify-task result
     to the dispatched command's :rerank-failed?

   The per-result tag + classify-task flag + event flag are all
   additive — legacy schemas/callers see no shape change."
  (:require [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.reranker :as reranker]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.colbert.interface :as colbert]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.schemas]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; RED #1 — :rerank-failed? optional on task-classified event + command
;; =============================================================================

(deftest task-classified-event-accepts-optional-rerank-failed
  (testing "Legacy event without :rerank-failed? still validates"
    (let [event {:source-sheet-id   (random-uuid)
                 :source-tick-id    (random-uuid)
                 :source-node-id    (random-uuid)
                 :assigned-tree-id  (random-uuid)
                 :confidence        0.85
                 :top-candidates    []
                 :reasoning         "x"
                 :classified-at     "2026-05-28T00:00:00Z"
                 :was-fresh-mint?   false}
          schema (get @schema-util/registry* :ontology/task-classified)]
      (is (m/validate schema event)
          "Legacy event without :rerank-failed? remains valid")))

  (testing "Event WITH :rerank-failed? true validates"
    (let [event {:source-sheet-id   (random-uuid)
                 :source-tick-id    (random-uuid)
                 :source-node-id    (random-uuid)
                 :assigned-tree-id  (random-uuid)
                 :confidence        0.0
                 :top-candidates    []
                 :reasoning         "x"
                 :classified-at     "2026-05-28T00:00:00Z"
                 :was-fresh-mint?   true
                 :rerank-failed?    true}
          schema (get @schema-util/registry* :ontology/task-classified)]
      (is (m/validate schema event)
          (str "Event with :rerank-failed? should validate. Explain: "
               (pr-str (m/explain schema event))))))

  (testing ":rerank-failed? is declared in the schema (not just open-map extra)"
    (let [schema (get @schema-util/registry* :ontology/task-classified)
          schema-form (m/form schema)]
      (is (some #{:rerank-failed?} (flatten (vec schema-form)))
          (str ":rerank-failed? should appear in the schema form. Got: "
               (pr-str schema-form))))))

(deftest assign-task-class-command-accepts-optional-rerank-failed
  (testing "Legacy command without :rerank-failed? still validates"
    (let [cmd {:source-sheet-id   (random-uuid)
               :source-tick-id    (random-uuid)
               :source-node-id    (random-uuid)
               :assigned-tree-id  (random-uuid)
               :confidence        0.85
               :top-candidates    []
               :reasoning         "x"
               :was-fresh-mint?   false}
          schema (get @schema-util/registry* :ontology/assign-task-class)]
      (is (m/validate schema cmd)
          "Legacy command without :rerank-failed? remains valid")))

  (testing "Command WITH :rerank-failed? true validates"
    (let [cmd {:source-sheet-id   (random-uuid)
               :source-tick-id    (random-uuid)
               :source-node-id    (random-uuid)
               :assigned-tree-id  (random-uuid)
               :confidence        0.0
               :top-candidates    []
               :reasoning         "x"
               :was-fresh-mint?   true
               :rerank-failed?    true}
          schema (get @schema-util/registry* :ontology/assign-task-class)]
      (is (m/validate schema cmd)
          (str "Command with :rerank-failed? should validate. Explain: "
               (pr-str (m/explain schema cmd))))))

  (testing ":rerank-failed? is declared in the assign-task-class schema"
    (let [schema (get @schema-util/registry* :ontology/assign-task-class)
          schema-form (m/form schema)]
      (is (some #{:rerank-failed?} (flatten (vec schema-form)))
          (str ":rerank-failed? should appear in the schema form. Got: "
               (pr-str schema-form))))))

;; =============================================================================
;; Test scaffolding for apply-rerank cycles (#2/#3/#4)
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/r01-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)]
    {:event-store event-store
     :cache cache
     :tenant-id tenant-id
     :event-pubsub ps
     :dscloj-provider :openrouter
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     ::cache-dir cache-dir}))

(defn- stop-context [ctx]
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

(defn- inject-index-created!
  "Append a :colbert/index-created event so search-descriptions has an
   index to find. Mirrors the pattern from reranker_test.clj."
  [ctx]
  (es/append (:event-store ctx)
    {:tenant-id (:tenant-id ctx)
     :events [(es/->event
                {:type :colbert/index-created
                 :tags #{}
                 :body {:index-id (random-uuid)
                        :index-name "ontology-descriptions"
                        :index-path "/tmp/r01-test-index"
                        :documents ["doc-1"]
                        :document-ids ["id-1"]
                        :document-count 1
                        :passage-count 1
                        :model-name "colbert-ir/colbertv2.0"
                        :config {:split-documents? true
                                 :max-document-length 256
                                 :use-faiss? false}
                        :created-at "2026-05-28T00:00:00Z"}})]}))

(def ^:private colbert-candidates
  "Three synthetic ColBERT candidates used across cycles 2/3/4."
  [{:content "node-type llm content"
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
                        :confidence 0.4 :last-update "2026"}}])

;; =============================================================================
;; RED #2 — apply-rerank success path stamps :rerank-source :reranker
;; =============================================================================

(deftest apply-rerank-success-stamps-rerank-source-reranker
  (testing "When the reranker returns valid results, every search-descriptions result has :rerank-source :reranker"
    (with-test-ctx [ctx]
      (inject-index-created! ctx)
      (Thread/sleep 100)
      (let [fake-rerank [{:document-id "a" :reasoning "primary" :fitness-score 0.9}
                         {:document-id "b" :reasoning "secondary" :fitness-score 0.7}
                         {:document-id "c" :reasoning "tertiary" :fitness-score 0.5}]]
        (with-redefs [colbert/search (fn [_ctx _opts] colbert-candidates)
                      reranker/rerank! (fn [_ctx _opts] fake-rerank)]
          (let [results (ontology/search-descriptions ctx
                          {:query "x"
                           :rerank-with-intent "y"
                           :k 3})]
            (is (= 3 (count results))
                "Sanity: 3 results came back")
            (is (every? #(= :reranker (:rerank-source %)) results)
                ":rerank-source is :reranker on every result")
            (is (every? #(number? (:fitness-score %)) results)
                ":fitness-score is real (non-nil) on every result")
            (is (every? #(string? (:reasoning %)) results)
                ":reasoning is real (non-nil) on every result")))))))

;; =============================================================================
;; RED #3 — apply-rerank fallback (reranker returned nil) stamps
;; :rerank-source :colbert-fallback AND :fitness-score nil AND :reasoning nil
;; =============================================================================

(deftest apply-rerank-fallback-nil-stamps-colbert-fallback
  (testing "When the reranker returns nil, every result has :rerank-source :colbert-fallback + :fitness-score nil + :reasoning nil"
    (with-test-ctx [ctx]
      (inject-index-created! ctx)
      (Thread/sleep 100)
      (with-redefs [colbert/search (fn [_ctx _opts] colbert-candidates)
                    reranker/rerank! (fn [_ctx _opts] nil)]
        (let [results (ontology/search-descriptions ctx
                        {:query "x"
                         :rerank-with-intent "y"
                         :k 3})]
          (is (= 3 (count results))
              "Sanity: results fall back to ColBERT order with same K")
          (is (every? #(= :colbert-fallback (:rerank-source %)) results)
              ":rerank-source is :colbert-fallback on every result")
          (is (every? #(nil? (:fitness-score %)) results)
              ":fitness-score is explicitly nil on every fallback result")
          (is (every? #(nil? (:reasoning %)) results)
              ":reasoning is explicitly nil on every fallback result")
          (is (every? #(contains? % :fitness-score) results)
              ":fitness-score key is PRESENT (with nil value), not absent — this is the load-bearing signal callers can detect")
          (is (every? #(contains? % :reasoning) results)
              ":reasoning key is PRESENT (with nil value), not absent"))))))

;; =============================================================================
;; RED #4 — apply-rerank fallback (reranker THREW) stamps colbert-fallback
;; =============================================================================

(deftest apply-rerank-fallback-throw-stamps-colbert-fallback
  (testing "When the reranker throws, fallback path produces colbert-fallback-stamped results just like the nil-return case"
    (with-test-ctx [ctx]
      (inject-index-created! ctx)
      (Thread/sleep 100)
      (with-redefs [colbert/search (fn [_ctx _opts] colbert-candidates)
                    reranker/rerank! (fn [_ctx _opts]
                                        (throw (ex-info "synthetic rerank failure" {})))]
        (let [results (ontology/search-descriptions ctx
                        {:query "x"
                         :rerank-with-intent "y"
                         :k 3})]
          (is (= 3 (count results))
              "Caller never sees the exception — fallback still returns K results")
          (is (every? #(= :colbert-fallback (:rerank-source %)) results)
              "Throw is treated identically to nil-return — fallback path")
          (is (every? #(nil? (:fitness-score %)) results))
          (is (every? #(nil? (:reasoning %)) results)))))))

;; =============================================================================
;; RED #5 — classify-task computes :rerank-fallback? true from top-1's
;; :rerank-source when the candidates came from the fallback path
;; =============================================================================

(deftest classify-task-flags-rerank-fallback-true-when-top-1-is-colbert-fallback
  (testing "When the top-1 candidate has :rerank-source :colbert-fallback, classify-task result carries :rerank-fallback? true"
    (let [fallback-candidates [{:content "x" :score 0.3 :rank 1
                                :document-id "tf::a"
                                :document-metadata {:granularity :tree-fingerprint
                                                    :target-id (random-uuid)
                                                    :confidence 0.5}
                                :reasoning nil
                                :fitness-score nil
                                :rerank-source :colbert-fallback}]]
      (with-redefs [ontology/search-descriptions (fn [_ctx _opts] fallback-candidates)]
        (let [result (ontology/classify-task {}
                       {:task-signature "x"
                        :threshold 0.7
                        :walk-down? false})]
          (is (true? (:rerank-fallback? result))
              ":rerank-fallback? is true on the classify-task result")
          ;; Under fallback the top-1 :fitness-score is nil → top-score = 0.0
          ;; → not matched → fresh-mint at root via the legacy path. The
          ;; flag distinguishes this from a real low-confidence match.
          (is (true? (:was-fresh-mint? result))
              "Fresh-mint still happens (nil fitness → 0.0 → below threshold)")
          (is (nil? (:parent-tree-id result))
              ":parent-tree-id stays nil (legacy not-matched path)"))))))

;; =============================================================================
;; RED #6 — classify-task reports :rerank-fallback? false under success
;; =============================================================================

(deftest classify-task-flags-rerank-fallback-false-when-reranker-succeeded
  (testing "When the top-1 candidate has :rerank-source :reranker, classify-task result carries :rerank-fallback? false"
    (let [matched-uuid (random-uuid)
          success-candidates [{:content "x" :score 0.9 :rank 1
                               :document-id "tf::a"
                               :document-metadata {:granularity :tree-fingerprint
                                                   :target-id matched-uuid
                                                   :confidence 1.0}
                               :reasoning "principle-shaped fit"
                               :fitness-score 0.95
                               :rerank-source :reranker}]]
      (with-redefs [ontology/search-descriptions (fn [_ctx _opts] success-candidates)]
        (let [result (ontology/classify-task {}
                       {:task-signature "x"
                        :threshold 0.7
                        :walk-down? false})]
          (is (false? (:rerank-fallback? result))
              ":rerank-fallback? is FALSE on the success path")
          (is (= matched-uuid (:assigned-tree-id result))
              "Sanity: legitimate match still classified correctly"))))))

;; =============================================================================
;; RED #7 — defcommand :ontology/assign-task-class forwards :rerank-failed?
;; from command body to the emitted :ontology/task-classified event body
;; =============================================================================

(deftest assign-task-class-defcommand-forwards-rerank-failed
  (testing "Command with :rerank-failed? true → event body carries :rerank-failed? true"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid)
            tick-id  (random-uuid)
            node-id  (random-uuid)
            assigned (random-uuid)
            cmd {:command/name :ontology/assign-task-class
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :source-sheet-id sheet-id
                 :source-tick-id tick-id
                 :source-node-id node-id
                 :assigned-tree-id assigned
                 :confidence 0.0
                 :top-candidates []
                 :reasoning "x"
                 :was-fresh-mint? true
                 :rerank-failed? true}]
        (cp/process-command (assoc ctx :command cmd))
        (let [events (into [] (es/read (:event-store ctx)
                                       {:tenant-id (:tenant-id ctx)
                                        :types #{:ontology/task-classified}}))
              event (first events)]
          (is (= 1 (count events)))
          (is (true? (:rerank-failed? event))
              "Event body carries :rerank-failed? true from the command")
          (let [schema (get @schema-util/registry* :ontology/task-classified)]
            (is (m/validate schema event)
                "Event with :rerank-failed? still validates"))))))

  (testing "Command WITHOUT :rerank-failed? → event omits the field"
    (with-test-ctx [ctx]
      (let [cmd {:command/name :ontology/assign-task-class
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :source-sheet-id (random-uuid)
                 :source-tick-id (random-uuid)
                 :source-node-id (random-uuid)
                 :assigned-tree-id (random-uuid)
                 :confidence 0.9
                 :top-candidates []
                 :reasoning "x"
                 :was-fresh-mint? false}]
        (cp/process-command (assoc ctx :command cmd))
        (let [events (into [] (es/read (:event-store ctx)
                                       {:tenant-id (:tenant-id ctx)
                                        :types #{:ontology/task-classified}}))
              event (first events)]
          (is (= 1 (count events)))
          (is (not (contains? event :rerank-failed?))
              "Legacy command → event omits :rerank-failed? entirely"))))))

;; =============================================================================
;; RED #8 — C-2c-2 wedge forwards :rerank-fallback? from classify-task to
;; the dispatched :ontology/assign-task-class command's :rerank-failed?
;; =============================================================================

(defn- run-wedge!
  "Invoke the C-2c-2 wedge with the given node + context."
  [node context]
  (let [wedge (requiring-resolve
                'ai.obney.orc.orc-service.core.todo-processors/maybe-auto-classify-and-set-context)]
    (wedge node context)))

(deftest wedge-forwards-rerank-fallback-true-to-event
  (testing "When classify-task returns :rerank-fallback? true, the dispatched command + emitted event carry :rerank-failed? true"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            classify-result {:assigned-tree-id (random-uuid)
                             :confidence 0.0
                             :top-candidates []
                             :reasoning "fallback"
                             :was-fresh-mint? true
                             :parent-tree-id nil
                             :rerank-fallback? true}]
        (with-redefs [ontology/classify-task (fn [_ _] classify-result)]
          (let [node {:id node-id
                      :name "test-node"
                      :instruction "anything"
                      :reads [] :writes []
                      :rlm {:auto-classify? true}}
                wedge-ctx (assoc ctx :sheet-id sheet-id :tick-id tick-id)
                _ (run-wedge! node wedge-ctx)
                events (into [] (es/read (:event-store ctx)
                                         {:tenant-id (:tenant-id ctx)
                                          :types #{:ontology/task-classified}}))
                event (first events)]
            (is (= 1 (count events)))
            (is (true? (:rerank-failed? event))
                "Event body carries :rerank-failed? true from wedge")))))))

(deftest wedge-omits-rerank-failed-when-classify-task-says-false
  (testing "When classify-task returns :rerank-fallback? false, the wedge omits the field from the command"
    (with-test-ctx [ctx]
      (let [classify-result {:assigned-tree-id (random-uuid)
                             :confidence 0.9
                             :top-candidates []
                             :reasoning "match"
                             :was-fresh-mint? false
                             :parent-tree-id nil
                             :rerank-fallback? false}]
        (with-redefs [ontology/classify-task (fn [_ _] classify-result)]
          (let [node {:id (random-uuid) :name "node"
                      :instruction "x" :reads [] :writes []
                      :rlm {:auto-classify? true}}
                wedge-ctx (assoc ctx :sheet-id (random-uuid) :tick-id (random-uuid))
                _ (run-wedge! node wedge-ctx)
                events (into [] (es/read (:event-store ctx)
                                         {:tenant-id (:tenant-id ctx)
                                          :types #{:ontology/task-classified}}))
                event (first events)]
            (is (= 1 (count events)))
            (is (not (contains? event :rerank-failed?))
                "rerank-failed? false → field omitted from event (legacy event shape)")))))))
