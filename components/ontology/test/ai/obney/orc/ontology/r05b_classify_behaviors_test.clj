(ns ai.obney.orc.ontology.r05b-classify-behaviors-test
  "R05b — classify-behaviors fn + C-2c-2 wedge integration.

   Covers:
   - Indexer granularity derives from body :scope when :behavioral-subtree
     is set (so search-descriptions :granularity :behavioral-subtree
     returns only the 11 R05a behavioral seeds, not all tree-fingerprint
     descriptions)
   - classify-behaviors fresh-mint marker when no candidate returned
   - classify-behaviors top-N happy path with :rerank-source :reranker
   - R01: classify-behaviors propagates :rerank-fallback? from top-1
   - classify-behaviors :structural-context filter via :composed-by
     reverse edge in the concept graph
   - classify-behaviors :structural-context with empty filtered set falls
     back to unfiltered candidates (retrieval hints, not gates)
   - :ontology/assign-task-class defcommand forwards :behavioral-subtrees
     onto the emitted task-classified event body
   - C-2c-2 wedge calls classify-behaviors after classify-task and
     forwards :behavioral-subtrees through the command path"
  (:require [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas :as ontology-schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors :as ont-tp]
            [ai.obney.orc.ontology.core.task-classifier :as task-classifier]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.schemas]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Test context helpers
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/r05b-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)]
    {:event-store event-store
     :cache cache
     :tenant-id tenant-id
     :event-pubsub ps
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

(defn- base-body []
  {:capabilities ["x"]
   :strengths []
   :weaknesses []
   :representative-uses ["x"]
   :avoid-when ["x"]
   :summary "Behavioral subtree under test."
   :version 1
   :consolidated-from-event-count 1})

;; =============================================================================
;; RED #1 — build-document-collection derives :granularity from body :scope
;; =============================================================================
;;
;; R05a's behavioral seeds emit via :ontology/record-tree-description, so
;; they land in the descriptions read-model under granularity
;; :tree-fingerprint. But their body carries :scope :behavioral-subtree.
;; The indexer's per-document metadata must reflect the body's scope —
;; otherwise search-descriptions :granularity :behavioral-subtree returns
;; nothing (filter compares metadata's :granularity to the query value).

(deftest indexer-derives-granularity-from-body-scope
  (testing "tree-fingerprint body with :scope :behavioral-subtree → metadata :granularity :behavioral-subtree"
    (let [build-doc-collection (var-get
                                 (requiring-resolve
                                   'ai.obney.orc.ontology.core.todo-processors/build-document-collection))
          descriptions [{:granularity :tree-fingerprint
                         :target-id (random-uuid)
                         :body (assoc (base-body) :scope :behavioral-subtree)
                         :recorded-at "2026-05-28T00:00:00Z"}]
          {:keys [document-metadatas]} (build-doc-collection descriptions)
          meta (first document-metadatas)]
      (is (= :behavioral-subtree (:granularity meta))
          "Indexer must derive :granularity :behavioral-subtree from body :scope so search-descriptions filter works")))

  (testing "tree-fingerprint body WITHOUT :scope → metadata :granularity :tree-fingerprint (legacy)"
    (let [build-doc-collection (var-get
                                 (requiring-resolve
                                   'ai.obney.orc.ontology.core.todo-processors/build-document-collection))
          descriptions [{:granularity :tree-fingerprint
                         :target-id (random-uuid)
                         :body (base-body)
                         :recorded-at "2026-05-28T00:00:00Z"}]
          {:keys [document-metadatas]} (build-doc-collection descriptions)
          meta (first document-metadatas)]
      (is (= :tree-fingerprint (:granularity meta))
          "Legacy descriptions (no :scope) keep :tree-fingerprint granularity")))

  (testing "tree-fingerprint body with :scope :tree-class → metadata :granularity :tree-fingerprint"
    (let [build-doc-collection (var-get
                                 (requiring-resolve
                                   'ai.obney.orc.ontology.core.todo-processors/build-document-collection))
          descriptions [{:granularity :tree-fingerprint
                         :target-id (random-uuid)
                         :body (assoc (base-body) :scope :tree-class)
                         :recorded-at "2026-05-28T00:00:00Z"}]
          {:keys [document-metadatas]} (build-doc-collection descriptions)
          meta (first document-metadatas)]
      (is (= :tree-fingerprint (:granularity meta))
          "Explicit :scope :tree-class keeps :tree-fingerprint granularity (additive routing only applies to :behavioral-subtree)"))))

;; =============================================================================
;; classify-behaviors stub helpers
;; =============================================================================
;;
;; classify-behaviors calls search-descriptions (which fans out to ColBERT
;; + reranker). For unit tests we with-redef search-descriptions to a stub
;; returning the candidate vector we want to exercise. Real retrieval is
;; exercised by R05e's live-verify orchestrator.

(defn- stub-search!
  "Return a fn shaped like ontology/search-descriptions that records its
   args in `calls-atom` and returns `candidates`. Use via with-redefs on
   ontology/search-descriptions in classify-behaviors tests."
  [calls-atom candidates]
  (fn [_ctx opts]
    (swap! calls-atom conj opts)
    candidates))

;; =============================================================================
;; RED #2 — classify-behaviors with no candidates → single fresh-mint marker
;; =============================================================================

(deftest classify-behaviors-empty-returns-fresh-mint-marker
  (let [calls (atom [])]
    (with-redefs [ontology/search-descriptions (stub-search! calls [])]
      (let [result (task-classifier/classify-behaviors
                     {}
                     {:task-signature "x"
                      :threshold 0.7})]
        (testing "returns :behaviors with exactly one fresh-mint marker"
          (is (= 1 (count (:behaviors result)))
              "Single fresh-mint marker when no candidates"))
        (testing "fresh-mint marker carries :was-fresh-mint? true + UUID + reasoning"
          (let [m (first (:behaviors result))]
            (is (true? (:was-fresh-mint? m)) "Fresh-mint marker")
            (is (uuid? (:behavior-id m)) "Behavior-id is a fresh UUID")
            (is (string? (:reasoning m)) "Reasoning is non-nil string")
            (is (pos? (count (:reasoning m))) "Reasoning is non-empty")))
        (testing ":rerank-fallback? false on empty (nothing to fall back)"
          (is (false? (:rerank-fallback? result))))))))

;; =============================================================================
;; RED #3 — classify-behaviors passes the shared intent string to search
;; =============================================================================

(deftest classify-behaviors-uses-shared-intent-constant
  (let [calls (atom [])]
    (with-redefs [ontology/search-descriptions (stub-search! calls [])]
      (task-classifier/classify-behaviors
        {}
        {:task-signature "x" :threshold 0.7}))
    (testing "search-descriptions called with :granularity :behavioral-subtree"
      (is (= :behavioral-subtree (-> @calls first :granularity))
          "classify-behaviors targets the behavioral-subtree granularity"))
    (testing "search-descriptions called with the shared behavioral-classifier-intent string verbatim"
      (let [actual (-> @calls first :rerank-with-intent)
            expected (var-get
                       (requiring-resolve
                         'ai.obney.orc.ontology.core.task-classifier/behavioral-classifier-intent))]
        (is (= expected actual)
            "Shared constant — defined once in the classifier ns and referenced from this call site")))))

;; =============================================================================
;; Candidate helper (shape produced by search-descriptions + reranker)
;; =============================================================================

(defn- mk-candidate
  "Construct a reranked candidate as search-descriptions would return it
   after apply-rerank. Use :source :reranker for the success path and
   :source :colbert-fallback for the R01 fallback path."
  ([target-id fitness reasoning]
   (mk-candidate target-id fitness reasoning :reranker))
  ([target-id fitness reasoning source]
   {:document-id (str "behavioral-subtree:" target-id)
    :document-metadata {:granularity :behavioral-subtree
                        :target-id target-id}
    :fitness-score fitness
    :reasoning reasoning
    :rerank-source source}))

;; =============================================================================
;; RED #4 — top-N happy path: reranker success above threshold
;; =============================================================================

(deftest classify-behaviors-top-n-happy-path
  (let [b1 (random-uuid)
        b2 (random-uuid)
        b3 (random-uuid)
        b4 (random-uuid)
        candidates [(mk-candidate b1 0.92 "Best fit — analysis dominates")
                    (mk-candidate b2 0.85 "Second fit — synthesis composes")
                    (mk-candidate b3 0.78 "Third fit — research adjacent")
                    (mk-candidate b4 0.40 "Below threshold — drop")]]
    (with-redefs [ontology/search-descriptions
                  (stub-search! (atom []) candidates)]
      (let [result (task-classifier/classify-behaviors
                     {}
                     {:task-signature "x"
                      :threshold 0.7})]
        (testing "returns 3 behaviors above threshold (default top-n=3)"
          (is (= 3 (count (:behaviors result)))
              "Default top-n=3; only 3 of 4 candidates above threshold 0.7"))
        (testing "every returned behavior carries reranker reasoning + :rerank-source :reranker"
          (doseq [b (:behaviors result)]
            (is (string? (:reasoning b)) "Reasoning preserved from candidate")
            (is (pos? (count (:reasoning b))) "Reasoning non-empty")
            (is (= :reranker (:rerank-source b))
                "Top-N entries carry :rerank-source :reranker on success path")))
        (testing "behaviors are :was-fresh-mint? false (real corpus matches)"
          (doseq [b (:behaviors result)]
            (is (false? (:was-fresh-mint? b))
                "Top-N entries are not fresh-mint markers")))
        (testing "behavior-ids match the candidate target-ids in order"
          (is (= [b1 b2 b3] (mapv :behavior-id (:behaviors result)))
              "Behaviors preserve reranker order; below-threshold candidate dropped"))
        (testing ":rerank-fallback? false on success path"
          (is (false? (:rerank-fallback? result)))))))

  (testing "custom :top-n caps results"
    (let [candidates [(mk-candidate (random-uuid) 0.92 "a")
                      (mk-candidate (random-uuid) 0.85 "b")
                      (mk-candidate (random-uuid) 0.78 "c")]]
      (with-redefs [ontology/search-descriptions
                    (stub-search! (atom []) candidates)]
        (let [result (task-classifier/classify-behaviors
                       {}
                       {:task-signature "x"
                        :threshold 0.7
                        :top-n 2})]
          (is (= 2 (count (:behaviors result)))
              "Custom :top-n 2 caps at 2"))))))

;; =============================================================================
;; RED #5 — R01: classify-behaviors propagates :rerank-fallback? from top-1
;; =============================================================================

(deftest classify-behaviors-r01-rerank-fallback
  (testing "top-1 :rerank-source :colbert-fallback → :rerank-fallback? true"
    (let [b1 (random-uuid)
          candidates [(mk-candidate b1 nil nil :colbert-fallback)
                      (mk-candidate (random-uuid) nil nil :colbert-fallback)]]
      (with-redefs [ontology/search-descriptions
                    (stub-search! (atom []) candidates)]
        (let [result (task-classifier/classify-behaviors
                       {}
                       {:task-signature "x"
                        :threshold 0.7})]
          (is (true? (:rerank-fallback? result))
              "When top-1 :rerank-source is :colbert-fallback, the result propagates :rerank-fallback? true (R01)")))))

  (testing "top-1 :rerank-source :reranker → :rerank-fallback? false"
    (let [candidates [(mk-candidate (random-uuid) 0.9 "ok" :reranker)]]
      (with-redefs [ontology/search-descriptions
                    (stub-search! (atom []) candidates)]
        (let [result (task-classifier/classify-behaviors
                       {}
                       {:task-signature "x"
                        :threshold 0.7})]
          (is (false? (:rerank-fallback? result))
              "Reranker success path → :rerank-fallback? false"))))))

;; =============================================================================
;; concept-graph helpers for structural-context filter tests
;; =============================================================================

(defn- emit-behavioral-seed!
  "Drive the R05a seed pipeline to land a behavioral-subtree concept
   into the concept graph with the requested composes-into edges to
   shells (tree-class UUIDs). Returns the behavioral-subtree URI."
  [ctx behavior-id shell-ids]
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/record-tree-description
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :target-id behavior-id
            :body {:capabilities ["x"]
                   :strengths []
                   :weaknesses []
                   :representative-uses ["x"]
                   :avoid-when ["x"]
                   :summary "x"
                   :version 1
                   :consolidated-from-event-count 1
                   :scope :behavioral-subtree
                   :composes-into shell-ids}}))
  ;; Drive the R05a processor synchronously over the event so the
  ;; concept-graph projection is observable to the test.
  (let [handler (requiring-resolve
                  'ai.obney.orc.ontology.core.todo-processors/on-behavioral-subtree-description-updated-project-concept)]
    (doseq [e (into [] (es/read (:event-store ctx)
                                {:tenant-id (:tenant-id ctx)
                                 :types #{:ontology/tree-description-updated}}))]
      (handler (assoc ctx :event e))))
  (str "behavioral-subtree:" behavior-id))

;; =============================================================================
;; RED #6 — :structural-context surfaces composers ALONGSIDE other top
;;            candidates (HINT, not GATE) — R07 fix
;;
;; R05b initial behavior dropped non-composers whenever any composer
;; appeared in the candidate set. R07 live verify on code-004 (debug
;; memory leak) showed that hid behaviors that were the obvious
;; semantic fit when they didn't compose into the specific structural
;; shell. The new contract: composes-into is a RETRIEVAL HINT —
;; composers are surfaced at the FRONT (boosted) but non-composers
;; remain in the candidate set. The reranker decides the final order.
;; =============================================================================

(deftest classify-behaviors-structural-context-boosts-composers-without-gating
  (with-test-ctx [ctx]
    (let [shell-id (random-uuid)
          composer-id (random-uuid)
          non-composer-id (random-uuid)]
      ;; Seed composer with composes-into → shell; non-composer with no edge.
      (emit-behavioral-seed! ctx composer-id [shell-id])
      (emit-behavioral-seed! ctx non-composer-id [])

      (let [candidates [(mk-candidate composer-id 0.9 "Composes into the shell")
                        (mk-candidate non-composer-id 0.85 "Does NOT compose into the shell")]
            calls (atom [])]
        (with-redefs [ontology/search-descriptions
                      (stub-search! calls candidates)]
          (let [result (task-classifier/classify-behaviors
                         ctx
                         {:task-signature "x"
                          :threshold 0.7
                          :structural-context shell-id})]
            (testing "composer is returned"
              (is (some #(= composer-id (:behavior-id %)) (:behaviors result))
                  "composer (URI in shell's :composed-by reverse set) appears in result"))
            (testing "non-composer is ALSO returned (HINT not GATE)"
              (is (some #(= non-composer-id (:behavior-id %)) (:behaviors result))
                  "non-composer is NOT dropped — the reranker decides; composes-into is a retrieval hint"))
            (testing "composer surfaces FIRST in the result (boost preserved)"
              (is (= composer-id (-> result :behaviors first :behavior-id))
                  "composer appears at the front so the reranker reads it preferentially"))))))))

;; =============================================================================
;; RED #7 — :structural-context with empty filtered set falls back to unfiltered
;;            (retrieval hints, not gates)
;; =============================================================================

(deftest classify-behaviors-empty-filter-falls-back-to-unfiltered
  (with-test-ctx [ctx]
    (let [;; Shell has NO behaviors composing into it yet.
          shell-id (random-uuid)
          candidate-id-1 (random-uuid)
          candidate-id-2 (random-uuid)
          candidates [(mk-candidate candidate-id-1 0.9 "Strong match")
                      (mk-candidate candidate-id-2 0.85 "Decent match")]
          calls (atom [])]
      (with-redefs [ontology/search-descriptions
                    (stub-search! calls candidates)]
        (let [result (task-classifier/classify-behaviors
                       ctx
                       {:task-signature "x"
                        :threshold 0.7
                        :structural-context shell-id})]
          (testing "filter would empty the candidate set → fall back to unfiltered"
            (is (= 2 (count (:behaviors result)))
                "Both candidates surfaced because the filtered set was empty (retrieval hints, not gates)")
            (is (= #{candidate-id-1 candidate-id-2}
                   (set (map :behavior-id (:behaviors result))))
                "Identity preserved across the fallback path")))))))

;; =============================================================================
;; RED #8 — :ontology/assign-task-class forwards :behavioral-subtrees to event
;; =============================================================================

(defn- read-task-classified-by-tick [ctx tick-id]
  (->> (into [] (es/read (:event-store ctx)
                         {:tenant-id (:tenant-id ctx)
                          :types #{:ontology/task-classified}}))
       (filter #(= tick-id (:source-tick-id %)))
       first))

(deftest assign-task-class-forwards-behavioral-subtrees
  (with-test-ctx [ctx]
    (let [sheet-id (random-uuid)
          legacy-tick (random-uuid)
          behavioral-tick (random-uuid)
          node-id (random-uuid)
          tree-id (random-uuid)
          behaviors [{:behavior-id (random-uuid) :confidence 0.9
                      :was-fresh-mint? false :reasoning "principled match"
                      :rerank-source :reranker}]]

      (testing "command WITHOUT :behavioral-subtrees → event WITHOUT :behavioral-subtrees (legacy preserved)"
        (cp/process-command
          (assoc ctx :command
                 {:command/name :ontology/assign-task-class
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :source-sheet-id sheet-id
                  :source-tick-id legacy-tick
                  :source-node-id node-id
                  :assigned-tree-id tree-id
                  :confidence 0.8
                  :top-candidates []
                  :reasoning "ok"
                  :was-fresh-mint? false}))
        (let [event (read-task-classified-by-tick ctx legacy-tick)]
          (is (some? event)
              "Legacy event landed")
          (is (not (contains? event :behavioral-subtrees))
              "Legacy event body has no :behavioral-subtrees field")))

      (testing "command WITH :behavioral-subtrees → event carries the same vector"
        (cp/process-command
          (assoc ctx :command
                 {:command/name :ontology/assign-task-class
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :source-sheet-id sheet-id
                  :source-tick-id behavioral-tick
                  :source-node-id node-id
                  :assigned-tree-id tree-id
                  :confidence 0.8
                  :top-candidates []
                  :reasoning "ok"
                  :was-fresh-mint? false
                  :behavioral-subtrees behaviors}))
        (let [event (read-task-classified-by-tick ctx behavioral-tick)]
          (is (some? event)
              "Behavioral event landed")
          (is (= behaviors (:behavioral-subtrees event))
              "Event body forwards :behavioral-subtrees verbatim from the command"))))))
