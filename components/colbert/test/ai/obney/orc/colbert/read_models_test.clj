(ns ai.obney.orc.colbert.read-models-test
  "Unit tests for ColBERT read model event projections.

   These tests verify that events are correctly applied to build
   the indexes read model."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.colbert.core.read-models :as rm]))

;; =============================================================================
;; Test Data Fixtures
;; =============================================================================

(def test-index-id (random-uuid))
(def test-index-id-2 (random-uuid))

(defn make-index-created-event
  "Create a test :colbert/index-created event."
  [& {:keys [index-id index-name documents document-ids]
      :or {index-id test-index-id
           index-name "test-index"
           documents ["Doc 1" "Doc 2" "Doc 3"]
           document-ids ["id1" "id2" "id3"]}}]
  {:event/type :colbert/index-created
   :index-id index-id
   :index-name index-name
   :index-path "/tmp/test-index"
   :documents documents
   :document-ids document-ids
   :document-metadatas nil
   :document-count (count documents)
   :passage-count (count documents)
   :model-name "answerdotai/answerai-colbert-small-v1"
   :config {:split-documents? true
            :max-document-length 256
            :use-faiss? false}
   :created-at "2024-01-01T00:00:00Z"})

(defn make-index-deleted-event
  "Create a test :colbert/index-deleted event."
  [& {:keys [index-id] :or {index-id test-index-id}}]
  {:event/type :colbert/index-deleted
   :index-id index-id
   :deleted-at "2024-01-03T00:00:00Z"})

;; =============================================================================
;; Index Read Model Tests
;; =============================================================================

(deftest apply-index-created-event-test
  (testing "Index created event creates index entry"
    (let [event (make-index-created-event)
          state (rm/apply-index-event {} event)
          index (get-in state [:indexes test-index-id])]
      (is (some? index))
      (is (= test-index-id (:index-id index)))
      (is (= "test-index" (:index-name index)))
      (is (= ["Doc 1" "Doc 2" "Doc 3"] (:documents index)))
      (is (= ["id1" "id2" "id3"] (:document-ids index)))
      (is (= 3 (:document-count index)))
      (is (= :active (:status index))))))

(deftest apply-index-deleted-event-test
  (testing "Index deleted event marks index as deleted"
    (let [created (make-index-created-event)
          deleted (make-index-deleted-event)
          state (-> {}
                    (rm/apply-index-event created)
                    (rm/apply-index-event deleted))
          index (get-in state [:indexes test-index-id])]
      (is (= :deleted (:status index)))
      (is (some? (:deleted-at index))))))

(deftest apply-index-events-pipeline-test
  (testing "Full event pipeline produces correct state"
    (let [events [(make-index-created-event)
                  (make-index-created-event :index-id test-index-id-2
                                            :index-name "second-index"
                                            :documents ["A" "B"]
                                            :document-ids ["a" "b"])]
          state (rm/apply-index-events events)]
      (is (= 2 (count (:indexes state))))
      (is (= 3 (count (get-in state [:indexes test-index-id :documents]))))
      (is (= 2 (count (get-in state [:indexes test-index-id-2 :documents])))))))

(deftest apply-index-event-unknown-event-test
  (testing "Unknown event types are ignored"
    (let [state {:indexes {}}
          result (rm/apply-index-event state {:event/type :unknown/event})]
      (is (= state result)))))

;; =============================================================================
;; Event Type Constants Tests
;; =============================================================================

(deftest event-types-test
  (testing "Event type sets are defined correctly"
    (is (contains? rm/colbert-event-types :colbert/index-created))
    (is (contains? rm/colbert-event-types :colbert/index-deleted))
    (is (contains? rm/colbert-event-types :colbert/search-performed))
    (is (contains? rm/colbert-event-types :colbert/rerank-performed))
    (is (contains? rm/index-event-types :colbert/index-created))))
