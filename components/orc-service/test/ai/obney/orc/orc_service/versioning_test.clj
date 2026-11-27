(ns ai.obney.orc.orc-service.versioning-test
  "Tests for behavior tree versioning functionality."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]))

;; =============================================================================
;; Publish Version Tests
;; =============================================================================

(deftest publish-version-test
  (testing "publishes a version with snapshot"
    (h/with-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Version Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
              seq-id (-> seq-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id seq-id "Root Sequence"))

          (let [pub-result (h/run-and-apply! ctx (h/make-publish-version-command sheet-id :description "Initial version"))]
            (is (not (h/is-anomaly? pub-result)))
            (is (= :sheet/version-published (h/get-event-type pub-result))))

          (let [sheet (sheet/get-sheet ctx sheet-id)]
            (is (= 1 (:published-version sheet)))
            (is (false? (:draft-dirty? sheet)))))))))

(deftest publish-version-increments-test
  (testing "version numbers increment correctly"
    (h/with-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Increment Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))

        (h/run-and-apply! ctx (h/make-publish-version-command sheet-id :description "v1"))
        (h/run-and-apply! ctx (h/make-publish-version-command sheet-id :description "v2"))
        (h/run-and-apply! ctx (h/make-publish-version-command sheet-id :description "v3"))

        (let [history-result (h/run-query ctx (h/make-version-history-query sheet-id))
              versions (get-in history-result [:query/result :versions])]
          (is (= 3 (count versions)))
          (is (= [1 2 3] (map :version-number versions))))))))

(deftest publish-version-without-root-fails-test
  (testing "cannot publish sheet without root node"
    (h/with-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "No Root Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)
            pub-result (h/run-command ctx (h/make-publish-version-command sheet-id))]
        (is (h/is-anomaly? pub-result))
        (is (= :cognitect.anomalies/incorrect (h/anomaly-category pub-result)))))))

;; =============================================================================
;; Version History Query Tests
;; =============================================================================

(deftest version-history-query-test
  (testing "returns correct version history"
    (h/with-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "History Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
        (h/run-and-apply! ctx (h/make-publish-version-command sheet-id :description "First"))
        (h/run-and-apply! ctx (h/make-publish-version-command sheet-id :description "Second"))

        (let [result (h/run-query ctx (h/make-version-history-query sheet-id))
              data (:query/result result)]
          (is (= 2 (count (:versions data))))
          (is (= 2 (:current-published-version data)))
          (is (false? (:draft-dirty? data))))))))

(deftest get-version-query-test
  (testing "returns specific version snapshot"
    (h/with-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Get Version Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
              seq-id (-> seq-result :command-result/events first :node-id)]
          (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id seq-id "Version 1 Root"))
          (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))

          (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id seq-id "Version 2 Root"))
          (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))

          (let [v1-result (h/run-query ctx (h/make-get-version-query sheet-id 1))
                v1-snapshot (get-in v1-result [:query/result :version :snapshot])]
            (is (= "Version 1 Root" (get-in v1-snapshot [:nodes :name]))))

          (let [v2-result (h/run-query ctx (h/make-get-version-query sheet-id 2))
                v2-snapshot (get-in v2-result [:query/result :version :snapshot])]
            (is (= "Version 2 Root" (get-in v2-snapshot [:nodes :name])))))))))

;; =============================================================================
;; Draft Dirty Tracking Tests
;; =============================================================================

(deftest draft-dirty-tracking-test
  (testing "tracks when draft differs from published version"
    (h/with-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Dirty Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
              seq-id (-> seq-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))

          (let [sheet (sheet/get-sheet ctx sheet-id)]
            (is (false? (:draft-dirty? sheet))))

          (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id seq-id "Modified"))

          (let [sheet (sheet/get-sheet ctx sheet-id)]
            (is (true? (:draft-dirty? sheet))))

          (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))

          (let [sheet (sheet/get-sheet ctx sheet-id)]
            (is (false? (:draft-dirty? sheet)))))))))

;; =============================================================================
;; Execution Mode Tests
;; =============================================================================

(deftest set-execution-mode-test
  (testing "can toggle execution mode"
    (h/with-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Mode Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
        (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))

        (h/run-and-apply! ctx (h/make-set-execution-mode-command sheet-id :published))
        (let [sheet (sheet/get-sheet ctx sheet-id)]
          (is (= :published (:execution-mode sheet))))

        (h/run-and-apply! ctx (h/make-set-execution-mode-command sheet-id :draft))
        (let [sheet (sheet/get-sheet ctx sheet-id)]
          (is (= :draft (:execution-mode sheet))))))))

(deftest set-execution-mode-requires-published-version-test
  (testing "cannot set published mode without a published version"
    (h/with-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Mode Fail Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)
            mode-result (h/run-command ctx (h/make-set-execution-mode-command sheet-id :published))]
        (is (h/is-anomaly? mode-result))
        (is (= :cognitect.anomalies/incorrect (h/anomaly-category mode-result)))))))

;; =============================================================================
;; Revert Tests
;; =============================================================================

(deftest revert-to-version-stashes-draft-test
  (testing "reverting stashes dirty draft before restoring"
    (h/with-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Revert Stash Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
              seq-id (-> seq-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id seq-id "Original"))
          (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))

          (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id seq-id "Modified"))

          (let [sheet (sheet/get-sheet ctx sheet-id)]
            (is (true? (:draft-dirty? sheet))))

          (let [revert-result (h/run-and-apply! ctx (h/make-revert-to-version-command sheet-id 1))
                events (:command-result/events revert-result)]
            (is (= 2 (count events)))
            (is (= :sheet/draft-stashed (-> events first :event/type)))
            (is (= :sheet/draft-reverted (-> events second :event/type))))

          (let [sheet (sheet/get-sheet ctx sheet-id)]
            (is (true? (:has-stash? sheet)))))))))

(deftest revert-without-dirty-draft-no-stash-test
  (testing "reverting clean draft doesn't create stash"
    (h/with-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Revert No Stash Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
        (h/run-and-apply! ctx (h/make-publish-version-command sheet-id :description "v1"))
        (h/run-and-apply! ctx (h/make-publish-version-command sheet-id :description "v2"))

        (let [revert-result (h/run-and-apply! ctx (h/make-revert-to-version-command sheet-id 1))
              events (:command-result/events revert-result)]
          (is (= 1 (count events)))
          (is (= :sheet/draft-reverted (-> events first :event/type))))

        (let [sheet (sheet/get-sheet ctx sheet-id)]
          (is (not (:has-stash? sheet))))))))

(deftest revert-to-nonexistent-version-fails-test
  (testing "reverting to nonexistent version fails"
    (h/with-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Revert Fail Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
        (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))

        (let [revert-result (h/run-command ctx (h/make-revert-to-version-command sheet-id 99))]
          (is (h/is-anomaly? revert-result))
          (is (= :cognitect.anomalies/not-found (h/anomaly-category revert-result))))))))

;; =============================================================================
;; Stash Tests
;; =============================================================================

(deftest restore-stash-test
  (testing "can restore from stash"
    (h/with-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Restore Stash Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
              seq-id (-> seq-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id seq-id "Original"))
          (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))

          (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id seq-id "Modified"))
          (h/run-and-apply! ctx (h/make-revert-to-version-command sheet-id 1))

          (let [stash-result (h/run-query ctx (h/make-get-stash-query sheet-id))
                stash (get-in stash-result [:query/result :stash])]
            (is (some? stash))
            (is (= "Modified" (get-in stash [:snapshot :nodes :name]))))

          (let [restore-result (h/run-and-apply! ctx (h/make-restore-stash-command sheet-id))]
            (is (not (h/is-anomaly? restore-result)))
            (is (= :sheet/stash-restored (h/get-event-type restore-result))))

          (let [sheet (sheet/get-sheet ctx sheet-id)]
            (is (not (:has-stash? sheet)))))))))

(deftest restore-stash-without-stash-fails-test
  (testing "cannot restore when no stash exists"
    (h/with-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "No Stash Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)
            restore-result (h/run-command ctx (h/make-restore-stash-command sheet-id))]
        (is (h/is-anomaly? restore-result))
        (is (= :cognitect.anomalies/not-found (h/anomaly-category restore-result)))))))

;; =============================================================================
;; Sheet View Screen Version Info Tests
;; =============================================================================

(deftest sheet-view-screen-includes-version-info-test
  (testing "sheet-view-screen query includes version info"
    (h/with-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "View Screen Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
        (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))
        (h/run-and-apply! ctx (h/make-set-execution-mode-command sheet-id :published))

        (let [view-result (h/run-query ctx {:query/name :sheet/sheet-view-screen
                                            :sheet-id sheet-id})
              version-info (get-in view-result [:query/result :version-info])]
          (is (some? version-info))
          (is (= 1 (:published-version version-info)))
          (is (false? (:draft-dirty? version-info)))
          (is (= :published (:execution-mode version-info)))
          (is (false? (:has-stash? version-info))))))))

;; =============================================================================
;; Snapshot Content Tests
;; =============================================================================

(deftest snapshot-captures-tree-structure-test
  (testing "snapshot captures complete tree structure"
    (h/with-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Snapshot Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
              seq-id (-> seq-result :command-result/events first :node-id)

              leaf1-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
              leaf1-id (-> leaf1-result :command-result/events first :node-id)

              fb-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :fallback :parent-id seq-id))
              fb-id (-> fb-result :command-result/events first :node-id)

              leaf2-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id fb-id))
              leaf2-id (-> leaf2-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id seq-id "Root"))
          (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id leaf1-id "Leaf 1"))
          (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id fb-id "Fallback"))
          (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id leaf2-id "Leaf 2"))
          (h/run-and-apply! ctx (h/make-set-node-instruction-command sheet-id leaf1-id "Do something"))

          (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))

          (let [version-result (h/run-query ctx (h/make-get-version-query sheet-id 1))
                snapshot (get-in version-result [:query/result :version :snapshot])
                root-node (:nodes snapshot)
                children (:children root-node)
                child-names (set (map :name children))]
            (is (= :sequence (:type root-node)))
            (is (= "Root" (:name root-node)))
            (is (= 2 (count children)))
            (is (contains? child-names "Leaf 1"))
            (is (contains? child-names "Fallback"))
            (let [leaf1 (first (filter #(= "Leaf 1" (:name %)) children))]
              (is (= "Do something" (:instruction leaf1))))
            (let [fb (first (filter #(= "Fallback" (:name %)) children))]
              (is (= 1 (count (:children fb)))))))))))

(deftest snapshot-captures-blackboard-schema-test
  (testing "snapshot captures blackboard schema without values"
    (h/with-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "BB Schema Test"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :input :string))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :count :int))
        (h/run-and-apply! ctx (h/make-set-key-value-command sheet-id :input "test value"))

        (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))

        (let [version-result (h/run-query ctx (h/make-get-version-query sheet-id 1))
              bb-schema (get-in version-result [:query/result :version :snapshot :blackboard-schema])]
          (is (= :string (:input bb-schema)))
          (is (= :int (:count bb-schema))))))))
