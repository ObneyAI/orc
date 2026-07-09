(ns ai.obney.orc.colbert.commands-test
  "Integration tests for ColBERT command handlers.

   Verifies that commands emit the correct events via the command processor,
   and that failure paths convert to anomalies. Encoder-backed happy paths
   (create-index/search/rerank against the real JVM encoder) are covered by
   index_search_test and rerank tests; these tests focus on validation and
   anomaly paths."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.obney.orc.grain-test-utils.interface :as tu]
            [ai.obney.orc.colbert.interface.schemas]
            [ai.obney.orc.colbert.core.commands]
            [ai.obney.orc.colbert.core.operations :as operations]
            [ai.obney.orc.colbert.core.read-models :as rm]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface :refer [->event]]
            [cognitect.anomalies :as anom]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def ^:dynamic *ctx* nil)

(defn with-ctx [f]
  (let [ctx (tu/create-test-context "colbert")]
    (try
      (binding [*ctx* ctx]
        (f))
      (finally
        (tu/stop-context ctx)))))

(use-fixtures :each with-ctx)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn seed-index!
  "Seed an index-created event into the event store so read models see it."
  [ctx & {:keys [index-id index-name]
          :or {index-id (random-uuid)
               index-name "test-index"}}]
  (let [result (es/append (:event-store ctx)
                 {:tenant-id (:tenant-id ctx)
                  :events [(->event {:type :colbert/index-created
                                      :tags #{[:index index-id]}
                                      :body {:index-id index-id
                                             :index-name index-name
                                             :index-path "/tmp/test-index"
                                             :documents ["Doc 1" "Doc 2"]
                                             :document-ids ["id1" "id2"]
                                             :document-count 2
                                             :passage-count 2
                                             :model-name "answerdotai/answerai-colbert-small-v1"
                                             :config {:split-documents? true
                                                      :max-document-length 256
                                                      :use-faiss? false}
                                             :created-at "2024-01-01T00:00:00Z"
                                             :duration-ms 100}})]})]
    (when (::anom/category result)
      (throw (ex-info "seed-index! failed" result)))
    index-id))

;; =============================================================================
;; Delete Index Command Tests
;; =============================================================================

(deftest delete-index-command-emits-event-test
  (testing "delete-index command emits :colbert/index-deleted event"
    (let [index-id (seed-index! *ctx*)
          result (tu/process-command! *ctx*
                   {:command/name :colbert/delete-index
                    :index-id index-id})]
      (is (not (::anom/category result))
          "Should not be an anomaly")
      (is (tu/event-of-type? result :colbert/index-deleted)
          "Should emit index-deleted event")
      (let [event (tu/find-event result :colbert/index-deleted)]
        (is (= index-id (:index-id event))
            "Event should contain the correct index-id")
        (is (some? (:deleted-at event))
            "Event should contain deleted-at timestamp")))))

(deftest delete-index-missing-returns-not-found-test
  (testing "delete-index returns not-found for non-existent index"
    (let [result (tu/process-command! *ctx*
                   {:command/name :colbert/delete-index
                    :index-id (random-uuid)})]
      (is (= ::anom/not-found (::anom/category result))
          "Should return not-found anomaly"))))

(deftest delete-index-already-deleted-returns-conflict-test
  (testing "delete-index returns conflict when index already deleted"
    (let [index-id (seed-index! *ctx*)
          ;; Delete it once
          first-result (tu/process-command! *ctx*
                         {:command/name :colbert/delete-index
                          :index-id index-id})]
      ;; Apply the delete event so read model sees it
      (tu/apply-events! *ctx* first-result)
      ;; Try to delete again
      (let [second-result (tu/process-command! *ctx*
                            {:command/name :colbert/delete-index
                             :index-id index-id})]
        (is (= ::anom/conflict (::anom/category second-result))
            "Should return conflict anomaly for already-deleted index")))))

;; =============================================================================
;; Search Command Tests (anomaly paths)
;; =============================================================================

(deftest search-missing-index-returns-not-found-test
  (testing "search returns not-found for non-existent index"
    (let [result (tu/process-command! *ctx*
                   {:command/name :colbert/search
                    :index-id (random-uuid)
                    :query "test query"
                    :k 5})]
      (is (= ::anom/not-found (::anom/category result))
          "Should return not-found anomaly"))))

(deftest search-deleted-index-returns-not-found-test
  (testing "search returns not-found for deleted index"
    (let [index-id (seed-index! *ctx*)
          delete-result (tu/process-command! *ctx*
                          {:command/name :colbert/delete-index
                           :index-id index-id})]
      (tu/apply-events! *ctx* delete-result)
      (let [result (tu/process-command! *ctx*
                     {:command/name :colbert/search
                      :index-id index-id
                      :query "test"
                      :k 5})]
        (is (= ::anom/not-found (::anom/category result))
            "Should return not-found for deleted index")))))

;; =============================================================================
;; Rerank Command Tests (anomaly paths)
;; =============================================================================

(deftest rerank-operation-failure-returns-fault-test
  (testing "rerank returns a fault anomaly when the underlying operation throws"
    ;; Force the failure deterministically: simulate the encoder-backed
    ;; operation throwing. The command handler must convert the exception
    ;; into an ::anom/fault map regardless of host state.
    (with-redefs [operations/rerank (fn [& _]
                                      (throw (ex-info "ColBERT encoder unavailable (simulated)" {})))]
      (let [result (tu/process-command! *ctx*
                     {:command/name :colbert/rerank
                      :query "test query"
                      :documents ["doc 1" "doc 2"]
                      :k 2})]
        (is (= ::anom/fault (::anom/category result))
            "Should return fault anomaly when the operation throws")))))
