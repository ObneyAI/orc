(ns ai.obney.orc.orc-service.deterministic-lifecycle-e2e-test
  "Deterministic end-to-end coverage for workflow lifecycle and versioning."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.grain.event-store-v3.interface :as es]))

(defn lifecycle-v1 [{:keys [inputs]}]
  {:output (str (:input inputs) "-v1")})

(defn lifecycle-v2 [{:keys [inputs]}]
  {:output (str (:input inputs) "-v2")})

(defn lifecycle-copy [{:keys [inputs]}]
  {:output (:input inputs)})

(defn lifecycle-maybe-fail [{:keys [inputs]}]
  (if (= "fail" (:input inputs))
    (throw (ex-info "deliberate batch item failure" {:input (:input inputs)}))
    {:output (str (:input inputs) "-ok")}))

(defn- fq [function-name]
  (str "ai.obney.orc.orc-service.deterministic-lifecycle-e2e-test/"
       function-name))

(defn- workflow [name function-name & {:keys [extra-key?]}]
  (sheet/workflow name
    (sheet/blackboard (cond-> {:input :string :output :string}
                        extra-key? (assoc :extra :string)))
    (sheet/sequence "main"
      (sheet/code "transform" :fn (fq function-name)
        :reads [:input] :writes [:output]))))

(defn- all-events [ctx]
  (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)})))

(defn- trace-for [ctx result]
  (let [trace-id (:trace-id result)]
    (is (uuid? trace-id) "execution must expose its durable trace identity")
    (is (h/settle-until!
         #(some? (get-in (h/run-query ctx (h/make-get-trace-query trace-id))
                         [:query/result :trace])))
        "execution trace did not settle")
    (get-in (h/run-query ctx (h/make-get-trace-query trace-id))
            [:query/result :trace])))

(defn- normalize-export [exported]
  (letfn [(normalize-node [node]
            (when node
              (-> node
                  (dissoc :id)
                  (update :children #(when % (mapv normalize-node %))))))]
    (-> exported
        (dissoc :exported-at :version)
        (update :sheet dissoc :id)
        (update :nodes normalize-node))))

(defn- diff-workflow-v1 [name]
  (sheet/workflow name
    (sheet/blackboard {:input :string
                       :output :string
                       :obsolete :string
                       :mutable :string})
    (sheet/sequence "main"
      (sheet/code "transform" :fn (fq "lifecycle-v1")
        :reads [:input] :writes [:output])
      (sheet/code "remove-me" :fn (fq "lifecycle-copy")
        :reads [:input] :writes [:obsolete]))))

(defn- diff-workflow-v2 [name]
  (sheet/workflow name
    (sheet/blackboard {:input :string
                       :output :string
                       :added :string
                       :mutable :any})
    (sheet/sequence "main"
      (sheet/code "transform" :fn (fq "lifecycle-v2")
        :reads [:input] :writes [:output]
        :retry {:max-attempts 2 :backoff-ms [1]})
      (sheet/code "add-me" :fn (fq "lifecycle-copy")
        :reads [:input] :writes [:added]))))

(deftest det-e2e-036-deterministic-workflow-identity
  (testing "an unchanged rebuild preserves identity, graph, and event count"
    (h/with-async-test-context [ctx]
      (let [definition (workflow "det-e2e-036-build-idempotency" "lifecycle-v1")
            first-id (sheet/build-workflow! ctx definition)
            first-events (count (all-events ctx))
            first-nodes (sheet/get-nodes-for-sheet ctx first-id)
            second-id (sheet/build-workflow! ctx definition)
            second-events (count (all-events ctx))
            second-nodes (sheet/get-nodes-for-sheet ctx second-id)
            result (sheet/execute ctx second-id {:input "x"})]
        (is (= first-id second-id))
        (is (uuid? first-id))
        (is (= first-events second-events) "unchanged rebuild emits no events")
        (is (= first-nodes second-nodes))
        (is (= 2 (count second-nodes)) "one sequence and one code leaf")
        (is (= "x-v1" (get-in result [:outputs :output])))))))

(deftest det-e2e-037-changed-same-name-definition
  (testing "a changed same-name definition preserves sheet identity but replaces behavior coherently"
    (h/with-async-test-context [ctx]
      (let [v1 (workflow "det-e2e-037-changed-definition" "lifecycle-v1")
            v2 (workflow "det-e2e-037-changed-definition" "lifecycle-v2" :extra-key? true)
            sheet-id (sheet/build-workflow! ctx v1)
            before (sheet/execute ctx sheet-id {:input "x"})
            event-count-before (count (all-events ctx))
            rebuilt-id (sheet/build-workflow! ctx v2)
            after (sheet/execute ctx rebuilt-id {:input "x"})
            bb (sheet/get-blackboard-by-key ctx rebuilt-id)]
        (is (= sheet-id rebuilt-id))
        (is (= "x-v1" (get-in before [:outputs :output])))
        (is (= "x-v2" (get-in after [:outputs :output])))
        (is (> (count (all-events ctx)) event-count-before))
        (is (= #{:input :output :extra} (set (keys bb))))
        (is (= 2 (count (sheet/get-nodes-for-sheet ctx rebuilt-id))))))))

(deftest det-e2e-038-atomic-build-failure
  (testing "a failed initial build must not leave a partially usable workflow"
    (h/with-async-test-context [ctx]
      (let [name "det-e2e-038-atomic-build-failure"
            invalid (sheet/workflow name
                      (sheet/blackboard {:input :string})
                      (sheet/sequence "main"
                        (sheet/code "invalid-write" :fn (fq "lifecycle-v1")
                          :reads [:input] :writes [:undeclared])))
            thrown (try
                     (sheet/build-workflow! ctx invalid)
                     nil
                     (catch Throwable t t))
            deterministic-id (dsl/sheet-id-for-name name)
            stored-sheet (sheet/get-sheet ctx deterministic-id)
            nodes (sheet/get-nodes-for-sheet ctx deterministic-id)
            bb (sheet/get-blackboard-by-key ctx deterministic-id)
            execution (sheet/execute ctx deterministic-id {:input "x"})]
        (is (some? thrown) "invalid IO must fail loudly")
        (is (nil? stored-sheet) "failed build leaves no visible sheet")
        (is (empty? nodes) "failed build leaves no nodes")
        (is (empty? bb) "failed build leaves no blackboard projection")
        (is (= :failure (:status execution))
            "a partial build must not be executable as a successful workflow")))))

(deftest det-e2e-039-concurrent-identical-builds
  (testing "concurrent identical registrations converge on one coherent graph"
    (h/with-async-test-context [ctx]
      (let [definition (workflow "det-e2e-039-concurrent-build" "lifecycle-v1")
            gate (promise)
            builds (mapv (fn [_]
                           (future @gate (sheet/build-workflow! ctx definition)))
                         (range 8))]
        (deliver gate true)
        (let [ids (mapv #(deref % 10000 ::timeout) builds)
              sheet-id (first ids)
              nodes (sheet/get-nodes-for-sheet ctx sheet-id)
              bb (sheet/get-blackboard-by-key ctx sheet-id)
              result (sheet/execute ctx sheet-id {:input "x"})]
          (is (not-any? #{::timeout} ids))
          (is (= 1 (count (set ids))))
          (is (= 2 (count nodes)))
          (is (= 2 (count (set (map :id nodes)))))
          (is (= #{:input :output} (set (keys bb))))
          (is (= :success (:status result)))
          (is (= "x-v1" (get-in result [:outputs :output]))))))))

(deftest det-e2e-040-publish-and-execute-version-one
  (testing "a pinned published execution identifies version one in its result and durable trace"
    (h/with-async-test-context [ctx]
      (let [sheet-id (sheet/build-workflow!
                      ctx (workflow "det-e2e-040-publish-v1" "lifecycle-v1"))
            publish (h/run-and-apply!
                     ctx (h/make-publish-version-command sheet-id :description "v1"))
            result (sheet/execute ctx sheet-id {:input "x"} :use-version 1)
            trace (trace-for ctx result)]
        (is (not (h/is-anomaly? publish)))
        (is (= :sheet/version-published (h/get-event-type publish)))
        (is (= :success (:status result)))
        (is (= "x-v1" (get-in result [:outputs :output])))
        (is (= 1 (:executed-version result)))
        (is (= 1 (:version-number trace)))))))

(deftest det-e2e-041-published-version-isolated-from-draft
  (testing "changing the draft does not mutate the executable v1 snapshot"
    (h/with-async-test-context [ctx]
      (let [name "det-e2e-041-version-isolation"
            sheet-id (sheet/build-workflow! ctx (workflow name "lifecycle-v1"))
            _ (h/run-and-apply! ctx (h/make-publish-version-command sheet-id :description "v1"))
            _ (sheet/build-workflow! ctx (workflow name "lifecycle-v2" :extra-key? true))
            draft (sheet/execute ctx sheet-id {:input "x"} :force-draft true)
            pinned (sheet/execute ctx sheet-id {:input "x"} :use-version 1)
            pinned-trace (trace-for ctx pinned)]
        (is (= "x-v2" (get-in draft [:outputs :output])))
        (is (nil? (:executed-version draft)))
        (is (= "x-v1" (get-in pinned [:outputs :output])))
        (is (= 1 (:executed-version pinned)))
        (is (= 1 (:version-number pinned-trace)))
        (is (true? (:draft-dirty? (sheet/get-sheet ctx sheet-id))))))))

(deftest det-e2e-042-publish-version-two
  (testing "published snapshots are monotonic and remain independently executable"
    (h/with-async-test-context [ctx]
      (let [name "det-e2e-042-publish-v2"
            sheet-id (sheet/build-workflow! ctx (workflow name "lifecycle-v1"))
            _ (h/run-and-apply! ctx (h/make-publish-version-command sheet-id :description "v1"))
            _ (sheet/build-workflow! ctx (workflow name "lifecycle-v2" :extra-key? true))
            _ (h/run-and-apply! ctx (h/make-publish-version-command sheet-id :description "v2"))
            versions (sheet/get-versions-for-sheet ctx sheet-id)
            v1 (sheet/execute ctx sheet-id {:input "x"} :use-version 1)
            v2 (sheet/execute ctx sheet-id {:input "x"} :use-version 2)]
        (is (= [1 2] (mapv :version-number versions)))
        (is (= 2 (:published-version (sheet/get-sheet ctx sheet-id))))
        (is (= [1 2] [(:executed-version v1) (:executed-version v2)]))
        (is (= ["x-v1" "x-v2"]
               [(get-in v1 [:outputs :output]) (get-in v2 [:outputs :output])]))
        (is (= [1 2] (mapv :version-number [(trace-for ctx v1) (trace-for ctx v2)])))))))

(deftest det-e2e-043-version-diff
  (testing "version diff reports node and blackboard additions, removals, and modifications"
    (h/with-async-test-context [ctx]
      (let [name "det-e2e-043-version-diff"
            sheet-id (sheet/build-workflow! ctx (diff-workflow-v1 name))
            _ (h/run-and-apply! ctx (h/make-publish-version-command sheet-id :description "v1"))
            _ (sheet/build-workflow! ctx (diff-workflow-v2 name))
            _ (h/run-and-apply! ctx (h/make-publish-version-command sheet-id :description "v2"))
            diff (:query/result
                  (h/run-query ctx (h/make-diff-versions-query sheet-id 1 2)))
            node-diff (:node-diff diff)
            bb-diff (:blackboard-diff diff)]
        (is (= [1 2] [(:from-version diff) (:to-version diff)]))
        (is (some #(= "add-me" (:name %)) (:added-nodes node-diff)))
        (is (some #(= "remove-me" (:name %)) (:removed-nodes node-diff)))
        (is (some #(and (= "transform" (:name %))
                        (some (fn [change]
                                (contains? #{:fn :retry} (:field change)))
                              (:changes %)))
                  (:modified-nodes node-diff)))
        (is (= #{:added} (set (:added bb-diff))))
        (is (= #{:obsolete} (set (:removed bb-diff))))
        (is (= [{:key :mutable :old-schema :string :new-schema :any}]
               (:modified bb-diff)))))))

(deftest det-e2e-044-export-import-round-trip
  (testing "an exported workflow imports into clean infrastructure without semantic loss"
    (h/with-async-test-context [source-ctx]
      (let [source-id (sheet/build-workflow!
                       source-ctx (workflow "det-e2e-044-export-import" "lifecycle-v1"
                                            :extra-key? true))
            source-export (sheet/export-sheet source-ctx source-id)
            source-result (sheet/execute source-ctx source-id {:input "x"})]
        (h/with-async-test-context [target-ctx]
          (let [target-id (sheet/import-sheet target-ctx source-export)
                target-export (sheet/export-sheet target-ctx target-id)
                target-result (sheet/execute target-ctx target-id {:input "x"})]
            (is (uuid? target-id))
            (is (= (normalize-export source-export)
                   (normalize-export target-export)))
            (is (= (:outputs source-result) (:outputs target-result)))
            (is (= :success (:status target-result)))
            (is (= 2 (count (sheet/get-nodes-for-sheet target-ctx target-id))))))))))

(deftest det-e2e-045-dsl-round-trip
  (testing "DSL export regenerates the same semantics and deterministic placement"
    (h/with-async-test-context [ctx]
      (let [definition (workflow "det-e2e-045-dsl-roundtrip" "lifecycle-v1"
                                 :extra-key? true)
            first-id (sheet/build-workflow! ctx definition)
            first-export (sheet/export-sheet ctx first-id)
            dsl-code (sheet/export-to-dsl first-export)
            regenerated (binding [*ns* (find-ns 'ai.obney.orc.orc-service.core.dsl)]
                          (eval (read-string dsl-code)))
            second-id (sheet/build-workflow! ctx regenerated)
            second-export (sheet/export-sheet ctx second-id)
            result (sheet/execute ctx second-id {:input "x"})]
        (is (= first-id second-id))
        (is (= (normalize-export first-export) (normalize-export second-export)))
        (is (= dsl-code (sheet/export-to-dsl second-export))
            "canonical DSL and node placement are deterministic")
        (is (= :success (:status result)))
        (is (= "x-v1" (get-in result [:outputs :output])))))))

(deftest det-e2e-046-batch-execution
  (testing "batch results remain input-aligned and one failed item cannot poison siblings"
    (h/with-async-test-context [ctx]
      (let [sheet-id (sheet/build-workflow!
                      ctx (workflow "det-e2e-046-batch" "lifecycle-maybe-fail"))
            command-result (h/run-command
                            ctx (h/make-batch-execute-command
                                 sheet-id [{:input "a"} {:input "fail"} {:input "c"}]))
            data (:command-result/data command-result)
            results (:results data)
            traces (mapv #(trace-for ctx %) results)]
        (is (= 3 (:total-executions data)))
        (is (= 2 (:successful-count data)))
        (is (= 1 (:failed-count data)))
        (is (= [:success :failure :success] (mapv :status results)))
        (is (= ["a-ok" nil "c-ok"] (mapv #(get-in % [:outputs :output]) results)))
        (is (= 3 (count (set (map :trace-id results)))))
        (is (= (mapv :trace-id results) (mapv :trace-id traces)))
        (is (= :sheet/batch-executed (h/get-event-type command-result)))))))

(deftest det-e2e-047-batch-pinned-version
  (testing "every item in a v1 batch remains pinned after v2 is published"
    (h/with-async-test-context [ctx]
      (let [name "det-e2e-047-batch-pinned"
            sheet-id (sheet/build-workflow! ctx (workflow name "lifecycle-v1"))
            _ (h/run-and-apply! ctx (h/make-publish-version-command sheet-id :description "v1"))
            _ (sheet/build-workflow! ctx (workflow name "lifecycle-v2"))
            _ (h/run-and-apply! ctx (h/make-publish-version-command sheet-id :description "v2"))
            command-result (h/run-command
                            ctx (h/make-batch-execute-command
                                 sheet-id [{:input "a"} {:input "b"} {:input "c"}]
                                 :version-number 1))
            results (get-in command-result [:command-result/data :results])
            traces (mapv #(trace-for ctx %) results)
            event (first (:command-result/events command-result))]
        (is (= ["a-v1" "b-v1" "c-v1"]
               (mapv #(get-in % [:outputs :output]) results)))
        (is (= [1 1 1] (mapv :executed-version results)))
        (is (= [1 1 1] (mapv :version-number traces)))
        (is (= 3 (count (set (map :trace-id results)))))
        (is (= 1 (:version-number event)))
        (is (= 3 (:successful-count event)))
        (is (= 0 (:failed-count event)))))))
