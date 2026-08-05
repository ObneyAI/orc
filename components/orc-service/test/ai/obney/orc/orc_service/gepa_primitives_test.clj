(ns ai.obney.orc.orc-service.gepa-primitives-test
  "Tests for GEPA (Goal-directed, Evaluative, Planning Agent) foundational primitives.

   Tests cover:
   - Execution trace storage with path tracking
   - Trace querying (get-trace, get-traces)
   - Version-targeted execution (execute-version)
   - Batch execution (batch-execute)
   - Structural diff (diff-versions)
   - Node statistics aggregation (node-stats)"
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]))

;; =============================================================================
;; Helper: Create a simple test workflow
;; =============================================================================

(defn- create-simple-workflow!
  "Create a simple workflow with sequence -> condition -> fallback structure.
   Returns {:sheet-id ... :node-ids {:root ... :cond1 ... :fallback ... :cond2 ...}}"
  [ctx]
  (let [;; Create sheet
        sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "GEPA Test"))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)

        ;; Create root sequence
        seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
        seq-id (-> seq-result :command-result/events first :node-id)
        _ (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id seq-id "Root"))

        ;; Declare blackboard keys
        _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :test-key :string))
        _ (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :other-key :string))

        ;; Create first condition (child of sequence)
        cond1-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :condition :parent-id seq-id))
        cond1-id (-> cond1-result :command-result/events first :node-id)
        _ (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id cond1-id "check-exists"))
        _ (h/run-and-apply! ctx (h/make-set-node-check-command sheet-id cond1-id {:key :test-key :op :exists}))

        ;; Create fallback (child of sequence)
        fb-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :fallback :parent-id seq-id))
        fb-id (-> fb-result :command-result/events first :node-id)
        _ (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id fb-id "TryOptions"))

        ;; Create second condition (child of fallback - will fail)
        cond2-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :condition :parent-id fb-id))
        cond2-id (-> cond2-result :command-result/events first :node-id)
        _ (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id cond2-id "check-nonexistent"))
        _ (h/run-and-apply! ctx (h/make-set-node-check-command sheet-id cond2-id {:key :nonexistent :op :exists}))

        ;; Create third condition (child of fallback - will succeed)
        cond3-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :condition :parent-id fb-id))
        cond3-id (-> cond3-result :command-result/events first :node-id)
        _ (h/run-and-apply! ctx (h/make-set-node-name-command sheet-id cond3-id "check-other"))
        _ (h/run-and-apply! ctx (h/make-set-node-check-command sheet-id cond3-id {:key :other-key :op :exists}))]

    {:sheet-id sheet-id
     :node-ids {:root seq-id
                :cond1 cond1-id
                :fallback fb-id
                :cond2 cond2-id
                :cond3 cond3-id}}))

;; =============================================================================
;; Phase 0: Node ID Preservation in Snapshots
;; =============================================================================

(deftest snapshot-preserves-node-ids-test
  (testing "published version snapshots preserve original node IDs"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id node-ids]} (create-simple-workflow! ctx)

            ;; Publish version
            _ (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))

            ;; Get the version
            version-result (h/run-query ctx (h/make-get-version-query sheet-id 1))
            snapshot (get-in version-result [:query/result :version :snapshot])
            snapshot-root (:nodes snapshot)]

        ;; Verify root ID is preserved
        (is (uuid? (:id snapshot-root)))
        (is (= (:root node-ids) (:id snapshot-root)))

        ;; Verify children IDs are preserved
        (let [children (:children snapshot-root)]
          (is (= 2 (count children)))
          (is (some #(= (:cond1 node-ids) (:id %)) children))
          (is (some #(= (:fallback node-ids) (:id %)) children)))))))

;; =============================================================================
;; Helper: Wait for trace to appear (trace assembly runs concurrently)
;; =============================================================================

(defn- wait-for-trace
  "Poll for a trace to appear in the event store. Returns the trace or nil."
  [ctx trace-id & {:keys [max-attempts delay-ms] :or {max-attempts 20 delay-ms 50}}]
  (loop [attempt 0]
    (let [result (h/run-query ctx (h/make-get-trace-query trace-id))]
      (cond
        (and (not (h/is-anomaly? result)) (:query/result result))
        (get-in result [:query/result :trace])

        (< attempt max-attempts)
        (do (Thread/sleep delay-ms)
            (recur (inc attempt)))

        :else nil))))

;; =============================================================================
;; Phase 1: Trace Storage
;; =============================================================================

(deftest execution-stores-trace-test
  (testing "execution stores an execution trace via async assembly"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (create-simple-workflow! ctx)
            result (sheet/execute ctx sheet-id {:test-key "hello" :other-key "world"})
            trace-id (:trace-id result)]
        (is (= :success (:status result)))
        (is (uuid? trace-id))
        ;; Wait for trace assembly (runs concurrently after execution completes)
        (let [trace (wait-for-trace ctx trace-id)]
          (is (some? trace) "Trace should be stored after execution")
          (when trace
            (is (= sheet-id (:sheet-id trace)))
            (is (= :success (:status trace)))
            (is (map? (:input-snapshot trace)))
            (is (map? (:output-snapshot trace)))
            (is (vector? (:node-traces trace)))
            (is (pos? (count (:node-traces trace))))))))))

(deftest trace-includes-node-details-test
  (testing "trace node entries include name, type, and status"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (create-simple-workflow! ctx)
            result (sheet/execute ctx sheet-id {:test-key "hello" :other-key "world"})
            trace (wait-for-trace ctx (:trace-id result))]
        (is (some? trace))
        (when trace
          (let [node-traces (:node-traces trace)
                by-name (into {} (map (juxt :node-name identity)) node-traces)]
            ;; Root sequence should be present
            (is (contains? by-name "Root"))
            (is (= :sequence (:node-type (get by-name "Root"))))
            ;; Condition nodes should be present
            (is (contains? by-name "check-exists"))
            (is (= :condition (:node-type (get by-name "check-exists"))))
            ;; All nodes should have a status
            (is (every? #(contains? % :status) node-traces))))))))

(deftest trace-captures-inputs-test
  (testing "trace input snapshot covers the execution inputs"
    ;; The snapshot records key -> size profile, not key -> value: the values
    ;; are durable in the tick's :sheet/execution-value-written events, and
    ;; storing them in the trace as well made :sheet/execution-traced the
    ;; largest event type in the log (docs/EVENT-STORE-PATTERNS.md).
    ;; Coverage — which keys the tick was given — is the durable fact here.
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (create-simple-workflow! ctx)
            result (sheet/execute ctx sheet-id {:test-key "input-val" :other-key "other-val"})
            trace (wait-for-trace ctx (:trace-id result))]
        (is (some? trace))
        (when trace
          (let [snap (:input-snapshot trace)]
            (is (contains? snap :test-key))
            (is (contains? snap :other-key))
            ;; The profile describes the value it stands in for.
            (is (= {:type :string :length 9 :word-count 1 :line-count 1}
                   (get snap :test-key))
                (str "profile should describe \"input-val\"; got " (get snap :test-key)))
            ;; The values themselves remain reachable through the caller's result.
            (is (= "input-val" (get-in result [:outputs :test-key])))
            (is (= "other-val" (get-in result [:outputs :other-key])))))))))

;; =============================================================================
;; Phase 2: Trace Querying
;; =============================================================================

(deftest get-trace-not-found-test
  (testing "get-trace returns not-found for invalid ID"
    (h/with-async-test-context [ctx]
      (let [result (h/run-query ctx (h/make-get-trace-query (random-uuid)))]
        (is (h/is-anomaly? result))
        (is (= :cognitect.anomalies/not-found (h/anomaly-category result)))))))

(deftest get-traces-query-test
  (testing "get-traces returns traces for a sheet"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (create-simple-workflow! ctx)
            result (sheet/execute ctx sheet-id {:test-key "hello" :other-key "world"})
            _ (wait-for-trace ctx (:trace-id result))
            traces-result (h/run-query ctx (h/make-get-traces-query sheet-id))]
        (is (not (h/is-anomaly? traces-result)))
        (let [{:keys [traces total]} (:query/result traces-result)]
          (is (= 1 total))
          (is (= 1 (count traces)))
          (is (= :success (:status (first traces)))))))))

;; =============================================================================
;; Phase 3: Version-Targeted Execution
;; =============================================================================

(deftest execute-version-test
  (testing "execute-version runs specific published version"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (create-simple-workflow! ctx)

            ;; Publish version 1
            _ (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))

            ;; Execute version 1
            result (h/run-command ctx (h/make-execute-version-command sheet-id 1
                                        :inputs {:test-key "v1" :other-key "v1"}))
            data (:command-result/data result)]

        (is (= :success (:status data)))

        ;; Verify event emitted
        (let [events (:command-result/events result)]
          (is (= 1 (count events)))
          (is (= :sheet/version-executed (:event/type (first events))))
          (is (= sheet-id (:sheet-id (first events))))
          (is (= 1 (:version-number (first events)))))))))

(deftest execute-version-not-found-test
  (testing "execute-version returns not-found for invalid version"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (create-simple-workflow! ctx)
            result (h/run-command ctx (h/make-execute-version-command sheet-id 99))]

        (is (h/is-anomaly? result))
        (is (= :cognitect.anomalies/not-found (h/anomaly-category result)))))))

;; =============================================================================
;; Phase 4: Batch Execution
;; =============================================================================

(deftest batch-execute-test
  (testing "batch-execute runs multiple inputs in parallel"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (create-simple-workflow! ctx)

            ;; Publish version
            _ (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))

            ;; Batch execute
            result (h/run-command ctx (h/make-batch-execute-command sheet-id
                                        [{:test-key "a" :other-key "a"}
                                         {:test-key "b" :other-key "b"}
                                         {:test-key "c" :other-key "c"}]
                                        :version-number 1))
            data (:command-result/data result)]

        (is (= 3 (:total-executions data)))
        (is (= 3 (:successful-count data)))
        (is (= 0 (:failed-count data)))
        (is (>= (:duration-ms data) 0))

        ;; Verify event emitted
        (let [events (:command-result/events result)]
          (is (= 1 (count events)))
          (is (= :sheet/batch-executed (:event/type (first events))))
          (is (= sheet-id (:sheet-id (first events))))
          (is (= 3 (:total-executions (first events)))))))))

(deftest batch-execute-empty-inputs-fails-test
  (testing "batch-execute fails with empty inputs list"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (create-simple-workflow! ctx)
            result (h/run-command ctx (h/make-batch-execute-command sheet-id []))]

        (is (h/is-anomaly? result))
        (is (= :cognitect.anomalies/incorrect (h/anomaly-category result)))))))

;; =============================================================================
;; Phase 5: Structural Diff
;; =============================================================================

(deftest diff-versions-no-changes-test
  (testing "diff-versions shows no changes for identical versions"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (create-simple-workflow! ctx)

            ;; Publish v1 and v2 without changes
            _ (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))
            _ (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))

            result (h/run-query ctx (h/make-diff-versions-query sheet-id 1 2))
            data (:query/result result)
            node-diff (:node-diff data)]

        (is (= 1 (:from-version data)))
        (is (= 2 (:to-version data)))
        (is (empty? (:added-nodes node-diff)))
        (is (empty? (:removed-nodes node-diff)))
        (is (empty? (:modified-nodes node-diff)))))))

(deftest diff-versions-detects-modifications-test
  (testing "diff-versions detects node modifications"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id node-ids]} (create-simple-workflow! ctx)

            ;; Publish v1
            _ (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))

            ;; Modify a node
            _ (h/run-and-apply! ctx (h/make-set-node-check-command sheet-id (:cond1 node-ids)
                                      {:key :test-key :op :truthy}))

            ;; Publish v2
            _ (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))

            result (h/run-query ctx (h/make-diff-versions-query sheet-id 1 2))
            node-diff (get-in result [:query/result :node-diff])]

        (is (empty? (:added-nodes node-diff)))
        (is (empty? (:removed-nodes node-diff)))
        (is (= 1 (count (:modified-nodes node-diff))))

        (let [modified (first (:modified-nodes node-diff))]
          (is (= (:cond1 node-ids) (:id modified)))
          (is (= "check-exists" (:name modified)))
          (is (some #(= :check (:field %)) (:changes modified))))))))

(deftest diff-versions-not-found-test
  (testing "diff-versions returns not-found for invalid version"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (create-simple-workflow! ctx)
            _ (h/run-and-apply! ctx (h/make-publish-version-command sheet-id))

            result (h/run-query ctx (h/make-diff-versions-query sheet-id 1 99))]

        (is (h/is-anomaly? result))
        (is (= :cognitect.anomalies/not-found (h/anomaly-category result)))))))

;; =============================================================================
;; Phase 6: Node Statistics
;; NOTE: Node stats tests are pending Phase 3 of async migration (trace assembly).
;; =============================================================================
