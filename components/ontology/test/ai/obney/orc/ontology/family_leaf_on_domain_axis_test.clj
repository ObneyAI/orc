(ns ai.obney.orc.ontology.family-leaf-on-domain-axis-test
  "CV-E (`DomainFamilyIsALeafOnTheDomainAxis`, revised C5') — a domain family
   is never a shape-ranking candidate.

   Companion to reranker_test.clj's generic `:exclude?` mechanism tests: this
   file proves classify-task's OWN wiring of that mechanism end to end —
   through the REAL `search-descriptions` (colbert/search + reranker/rerank!
   stubbed, everything in between real) — so a family present in the
   underlying ColBERT index never becomes top-1 or a ranked candidate, and
   the over-fetch classify-task relies on actually gets an in-domain seed
   past a crowd of families to the reranker (the RS-7 post-fix arm's pass 1
   failure this bundle fixes: 5 ranked candidates were all other groups'
   families, the seed never shown to the reranker)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.reranker :as reranker]
            [ai.obney.orc.ontology.core.task-classifier :as tc]
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
;; Test context helpers (mirror reranker_test.clj)
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/family-leaf-test-" (random-uuid))
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

(defn- inject-index-created!
  [ctx]
  (es/append (:event-store ctx)
    {:tenant-id (:tenant-id ctx)
     :events [(es/->event
                {:type :colbert/index-created
                 :tags #{}
                 :body {:index-id (random-uuid)
                        :index-name "ontology-descriptions"
                        :index-path "/tmp/family-leaf-test-index"
                        :documents ["doc-1"]
                        :document-ids ["id-1"]
                        :document-count 1
                        :passage-count 1
                        :model-name "colbert-ir/colbertv2.0"
                        :config {:split-documents? true
                                 :max-document-length 256
                                 :use-faiss? false}
                        :created-at "2026-05-27T00:00:00Z"}})]}))

(defn- tree-class-row [id score]
  {:content (str "tree-class " id) :score score :rank 1
   :document-id (str id)
   :document_metadata {:granularity "tree-class" :target-id (str id)
                       :confidence 0.5 :last-update "2026"}})

(defn- accepting-rerank!
  "Reranker stub: every candidate passes at fitness 0.8, in the order it was
   handed (the identity-shaped re-rank most of these tests need)."
  [_ctx opts]
  (mapv (fn [c] {:document-id (:document-id c) :reasoning "ok" :fitness-score 0.8})
        (:candidates opts)))

;; =============================================================================
;; classify-task: a family that ColBERT ranks first never becomes top-1 or a
;; ranked candidate.
;; =============================================================================

(deftest family-ranked-first-by-colbert-never-becomes-top-1-or-a-candidate
  (testing "five families outrank the seed on raw ColBERT score; classify-task's
            own :exclude? wiring (family-candidate?) drops every family before
            the reranker ever sees them, so the seed alone is ranked and wins"
    (with-test-ctx [ctx]
      (inject-index-created! ctx)
      (Thread/sleep 100)
      (let [seed-id (random-uuid)
            family-ids (vec (repeatedly 5 random-uuid))
            family-set (set family-ids)
            docs (conj (mapv #(tree-class-row % 0.95) family-ids)
                       (tree-class-row seed-id 0.5))]
        (with-redefs [colbert/search (fn [_ctx opts] (vec (take (:k opts) docs)))
                      reranker/rerank! accepting-rerank!
                      tc/get-consolidation-total* (fn [_ _ _] 0)]
          (let [ctx (assoc ctx :domain-family-parent-fn
                           (fn [_ id] (when (contains? family-set id)
                                       {:parent-id (random-uuid) :domain-label "some-family"})))
                r (ontology/classify-task ctx {:task-signature "x" :threshold 0.7 :walk-down? false})]
            (is (not (contains? family-set (:assigned-tree-id r)))
                "top-1 is never a family")
            (is (empty? (filter #(contains? (into #{} (map str) family-ids) (:target-id %))
                                (:ranked-candidates r)))
                "no family appears anywhere in the ranked candidates")
            (is (= seed-id (:assigned-tree-id r))
                "the seed reaches the reranker and wins, despite five families outranking it on raw score")))))))

;; =============================================================================
;; classify-task: the in-domain seed that sat behind five families reaches the
;; reranker (the RS-7 crowding failure, now fixed by the :exclude? over-fetch).
;; =============================================================================

(deftest in-domain-seed-behind-a-crowd-of-families-reaches-the-reranker
  (testing "40 families outrank the seed on raw ColBERT score, pushing it to
            position 41 — beyond the pre-existing 3x granularity over-fetch
            (30) but within the CV-E :exclude? EXTRA 2x over-fetch (60) — so
            the seed still reaches the reranker and is assigned"
    (with-test-ctx [ctx]
      (inject-index-created! ctx)
      (Thread/sleep 100)
      (let [seed-id (random-uuid)
            family-ids (vec (repeatedly 40 random-uuid))
            family-set (set family-ids)
            docs (conj (mapv #(tree-class-row % 0.95) family-ids)
                       (tree-class-row seed-id 0.5))]
        (with-redefs [colbert/search (fn [_ctx opts] (vec (take (:k opts) docs)))
                      reranker/rerank! accepting-rerank!
                      tc/get-consolidation-total* (fn [_ _ _] 0)]
          (let [ctx (assoc ctx :domain-family-parent-fn
                           (fn [_ id] (when (contains? family-set id)
                                       {:parent-id (random-uuid) :domain-label "some-family"})))
                r (ontology/classify-task ctx {:task-signature "x" :threshold 0.7 :walk-down? false})]
            (is (= seed-id (:assigned-tree-id r))
                "the seed is assigned — it was not lost behind the crowd of families")))))))

;; =============================================================================
;; classify-task: a failed family lookup excludes that candidate (fail closed).
;; =============================================================================

(deftest classify-task-failed-family-lookup-excludes-the-candidate
  (testing "the :domain-family-parent-fn seam throws for one candidate ->
            family-candidate? treats it as excluded, never lets an unverified
            class compete for the reranker slot"
    (with-test-ctx [ctx]
      (inject-index-created! ctx)
      (Thread/sleep 100)
      (let [ok-id (random-uuid)
            unverifiable-id (random-uuid)
            docs [(tree-class-row unverifiable-id 0.95) (tree-class-row ok-id 0.5)]]
        (with-redefs [colbert/search (fn [_ctx opts] (vec (take (:k opts) docs)))
                      reranker/rerank! accepting-rerank!
                      tc/get-consolidation-total* (fn [_ _ _] 0)]
          (let [ctx (assoc ctx :domain-family-parent-fn
                           (fn [_ id]
                             (if (= id unverifiable-id)
                               (throw (ex-info "store unavailable" {}))
                               nil)))
                r (ontology/classify-task ctx {:task-signature "x" :threshold 0.7 :walk-down? false})]
            (is (= ok-id (:assigned-tree-id r))
                "the unverifiable candidate never competes; the verified one wins")
            (is (not (some #(= (str unverifiable-id) (:target-id %)) (:ranked-candidates r)))
                "the unverifiable candidate is not among the ranked candidates at all")))))))

;; =============================================================================
;; classify-task: with no families in the index at all, the no-predicate path
;; is unaffected — a sanity guard alongside reranker_test's generic version.
;; =============================================================================

(deftest classify-task-with-no-families-present-is-unaffected
  (testing "when :domain-family-parent-fn never finds a family, classify-task's
            :exclude? wiring never excludes anything — the ordinary top-1
            match is unaffected"
    (with-test-ctx [ctx]
      (inject-index-created! ctx)
      (Thread/sleep 100)
      (let [shape-id (random-uuid)
            docs [(tree-class-row shape-id 0.95)]]
        (with-redefs [colbert/search (fn [_ctx opts] (vec (take (:k opts) docs)))
                      reranker/rerank! accepting-rerank!
                      tc/get-consolidation-total* (fn [_ _ _] 0)]
          (let [ctx (assoc ctx :domain-family-parent-fn (fn [_ _] nil))
                r (ontology/classify-task ctx {:task-signature "x" :threshold 0.7 :walk-down? false})]
            (is (= shape-id (:assigned-tree-id r)))
            (is (false? (:was-fresh-mint? r)))))))))
