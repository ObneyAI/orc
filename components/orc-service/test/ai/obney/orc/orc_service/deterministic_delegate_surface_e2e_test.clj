(ns ai.obney.orc.orc-service.deterministic-delegate-surface-e2e-test
  "Deterministic public-boundary coverage for the complete :delegate surface.

   Checklist: docs/DETERMINISTIC-E2E-TEST-CHECKLIST.md, DET-E2E-170 onward."
  (:require [clojure.test :refer [deftest is testing]]
            [cognitect.anomalies :as anom]
            [malli.core :as m]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [ai.obney.orc.orc-service.core.block :as block]
            [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]
            [ai.obney.orc.orc-service.core.value-log :as value-log]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def captured-child-inputs (atom nil))
(def empty-release (atom nil))
(def contract-child-calls (atom 0))
(def contract-downstream-calls (atom 0))
(def fallback-recovery-calls (atom 0))
(def shared-target-calls (atom 0))
(def parallel-active (atom 0))
(def parallel-peak (atom 0))
(def parallel-both-started (atom nil))
(def parallel-release (atom nil))
(def parallel-observed (atom {}))
(def map-delegate-active (atom 0))
(def map-delegate-peak (atom 0))
(def map-delegate-two-started (atom nil))
(def map-delegate-release (atom nil))
(def map-delegate-inputs (atom []))
(def duplicate-dispatch-calls (atom 0))
(def recovery-effect-calls (atom 0))

(defn capture-isolated-child [{:keys [inputs]}]
  (reset! captured-child-inputs inputs)
  {:mapped-output (str (:mapped inputs) "-child")
   :child-only "must-not-cross"})

(defn empty-success [_] {:hidden "child-private"})

(defn empty-failure [_]
  (throw (ex-info "empty delegate failed" {:test :det-e2e-179})))

(defn empty-wait [_]
  (deref @empty-release 5000 nil)
  {:hidden "too-late"})

(defn version-a [_] {:result "A"})
(defn version-b [_] {:result "B"})
(defn provenance-parent [{:keys [inputs]}] {:handoff (str (:seed inputs) "-parent")})
(defn provenance-child [{:keys [inputs]}] {:result (str (:handoff inputs) "-child")})
(defn provenance-downstream [{:keys [inputs]}] {:final (str (:result inputs) "-done")})
(defn contract-child [{:keys [inputs]}]
  (swap! contract-child-calls inc)
  {:output (:input inputs)})
(defn omit-required-output [_] {})
(defn nil-required-output [_] {:output nil})
(defn invalid-required-output [_] {:output 42})
(defn contract-downstream [_]
  (swap! contract-downstream-calls inc)
  {:after "ran"})
(defn partial-item [{:keys [inputs]}]
  (let [item (:item inputs)]
    (if (= 2 (:id item))
      (throw (ex-info "item two failed" {:id 2}))
      {:item (assoc item :processed true)})))
(def blocked-payload {:kind :approval-required :request-id "delegate-block-1"})
(defn block-child [_] (block/block! blocked-payload))
(defn fallback-recovery [_]
  (swap! fallback-recovery-calls inc)
  {:recovered "yes"})
(defn shared-target [{:keys [inputs]}]
  (swap! shared-target-calls inc)
  {:result (:input inputs)})
(defn- parallel-child [label output-key inputs]
  (swap! parallel-observed assoc label inputs)
  (let [active (swap! parallel-active inc)]
    (swap! parallel-peak max active)
    (when (= 2 active) (deliver @parallel-both-started true))
    (deref @parallel-release 3000 nil)
    (swap! parallel-active dec)
    {output-key (name label)}))
(defn parallel-a [{:keys [inputs]}] (parallel-child :a :a inputs))
(defn parallel-b [{:keys [inputs]}] (parallel-child :b :b inputs))
(defn lineage-grandchild [{:keys [inputs]}]
  {:value (str (:value inputs) "-grandchild")})
(defn map-delegate-child [{:keys [inputs]}]
  (let [item (:item inputs)
        active (swap! map-delegate-active inc)]
    (swap! map-delegate-inputs conj item)
    (swap! map-delegate-peak max active)
    (when (= 2 active) (deliver @map-delegate-two-started true))
    (deref @map-delegate-release 3000 nil)
    (swap! map-delegate-active dec)
    {:item (assoc item :processed true)}))
(defn duplicate-dispatch-child [_]
  (swap! duplicate-dispatch-calls inc)
  (deref @empty-release)
  {:result "once"})
(defn recovery-effect-child [_]
  (swap! recovery-effect-calls inc)
  {:result "recovered-once"})

(defn- fq [function-name]
  (str "ai.obney.orc.orc-service.deterministic-delegate-surface-e2e-test/"
       function-name))

(defn- anomaly? [result]
  (and (map? result) (contains? result ::anom/category)))

(defn- delegate-config-events [ctx]
  (filter #(= :sheet/delegate-config-set (:event/type %))
          (h/read-all-events ctx)))

(defn- build-config-fixture! [ctx suffix]
  (let [target-id (dsl/build-workflow!
                   ctx
                   (dsl/workflow (str "delegate-config-target-" suffix)
                     (dsl/blackboard {:input :string :output :string})
                     (dsl/code "copy" :fn "consumer.delegate/copy"
                       :reads [:input] :writes [:output])))
        parent-id (dsl/build-workflow!
                   ctx
                   (dsl/workflow (str "delegate-config-parent-" suffix)
                     (dsl/blackboard {:input :string :output :string :other :string})
                     (dsl/delegate "child" :target-sheet-id target-id
                       :reads [:input] :writes [:output])))
        leaf-id (dsl/build-workflow!
                 ctx
                 (dsl/workflow (str "delegate-config-leaf-" suffix)
                   (dsl/blackboard {:input :string})
                   (dsl/code "leaf" :fn "consumer.delegate/noop" :reads [:input])))]
    {:target-id target-id
     :parent-id parent-id
     :delegate-id (get-in (dsl/export-sheet ctx parent-id) [:nodes :id])
     :leaf-id (get-in (dsl/export-sheet ctx leaf-id) [:nodes :id])
     :leaf-sheet-id leaf-id}))

(deftest det-e2e-172-delegate-target-validation-matrix
  (testing "missing, malformed, unknown, and cross-tenant targets append no configuration"
    (h/with-test-context [ctx]
      (let [{:keys [parent-id delegate-id]} (build-config-fixture! ctx "target-validation")
            before (count (delegate-config-events ctx))
            base (h/make-set-delegate-config-command
                  parent-id delegate-id (random-uuid)
                  :reads [:input] :writes [:output])
            other-tenant-ctx (assoc ctx :tenant-id (random-uuid))
            cases [(dissoc base :target-sheet-id)
                   (assoc base :command/id (random-uuid) :target-sheet-id "not-a-uuid")
                   (assoc base :command/id (random-uuid) :target-sheet-id (random-uuid))
                   (assoc base :command/id (random-uuid) :target-sheet-id
                          (:target-id (build-config-fixture!
                                       other-tenant-ctx "other-tenant-target")))]
            results (mapv #(h/run-command ctx %) cases)]
        (is (every? anomaly? results))
        (is (every? #(re-find #"(?i)(target|uuid|invalid command)"
                              (or (::anom/message %) ""))
                    results))
        (is (= before (count (delegate-config-events ctx)))
            "invalid targets cannot append partial delegate configuration")))))

(deftest det-e2e-173-delegate-node-and-mapping-validation-matrix
  (testing "wrong node kind, unknown mappings, and invalid limits are rejected atomically"
    (h/with-test-context [ctx]
      (let [{:keys [target-id parent-id delegate-id leaf-id leaf-sheet-id]}
            (build-config-fixture! ctx "mapping-validation")
            before (count (delegate-config-events ctx))
            command #(h/make-set-delegate-config-command
                      parent-id delegate-id target-id
                      :reads [:input] :writes [:output])
            cases [(h/make-set-delegate-config-command
                    leaf-sheet-id leaf-id target-id :reads [:input])
                   (assoc (command) :command/id (random-uuid) :reads [:missing])
                   (assoc (command) :command/id (random-uuid) :writes [:missing])
                   (assoc (command) :command/id (random-uuid) :max-ticks 0)
                   (assoc (command) :command/id (random-uuid) :max-ticks -1)
                   (assoc (command) :command/id (random-uuid) :max-ticks 1.5)
                   (assoc (command) :command/id (random-uuid) :timeout-ms 0)
                   (assoc (command) :command/id (random-uuid) :timeout-ms -1)]
            results (mapv #(h/run-command ctx %) cases)]
        (is (every? anomaly? results))
        (is (= before (count (delegate-config-events ctx)))
            "rejected configuration cannot append any delegate event"))))
  (testing "positive boundary values persist exactly"
    (h/with-test-context [ctx]
      (let [{:keys [target-id parent-id delegate-id]}
            (build-config-fixture! ctx "mapping-boundaries")
            result (h/run-command
                    ctx
                    (h/make-set-delegate-config-command
                     parent-id delegate-id target-id
                     :reads [:input :other]
                     :writes [:output]
                     :timeout-ms 1
                     :max-ticks 1
                     :inherit-ontology? false))
            node (:nodes (dsl/export-sheet ctx parent-id))]
        (is (not (anomaly? result)))
        (is (= {:target-sheet-id target-id
                :reads [:input :other]
                :writes [:output]
                :timeout-ms 1
                :max-ticks 1
                :inherit-ontology? false}
               (select-keys node
                            [:target-sheet-id :reads :writes :timeout-ms
                             :max-ticks :inherit-ontology?])))))))

(defn- execute-isolation-fixture [ctx suffix]
  (reset! captured-child-inputs nil)
  (let [child-id (sheet/build-workflow!
                  ctx
                  (sheet/workflow (str "delegate-isolation-child-" suffix)
                    (sheet/blackboard {:mapped :string
                                       :parent-only [:maybe :string]
                                       :mapped-output :string
                                       :child-only :string})
                    (sheet/code "inspect" :fn (fq "capture-isolated-child")
                      :reads [:mapped :parent-only]
                      :writes [:mapped-output :child-only])))
        parent-id (sheet/build-workflow!
                   ctx
                   (sheet/workflow (str "delegate-isolation-parent-" suffix)
                     (sheet/blackboard {:mapped :string
                                        :parent-only :string
                                        :mapped-output :string
                                        :child-only :string})
                     (sheet/delegate "child" :target-sheet-id child-id
                       :reads [:mapped]
                       :writes [:mapped-output])))
        result (sheet/execute ctx parent-id
                              {:mapped "work"
                               :parent-only "private"
                               :child-only "parent-original"})]
    {:result result
     :family (do
               (is (h/settle-until!
                    #(= 2 (count (get-in
                                  (h/run-query ctx
                                    {:query/name :sheet/get-trace-family
                                     :trace-id (:trace-id result)})
                                  [:query/result :traces])))))
               (get-in (h/run-query ctx
                         {:query/name :sheet/get-trace-family
                          :trace-id (:trace-id result)})
                       [:query/result]))}))

(deftest det-e2e-177-exact-delegate-input-allowlist-and-child-isolation
  (h/with-async-test-context [ctx]
    (let [{:keys [result family]} (execute-isolation-fixture ctx "reads")]
      (is (= :success (:status result)))
      (is (= {:mapped "work" :parent-only nil} @captured-child-inputs)
          "the child executor sees only the delegate's declared read allowlist")
      (is (= "private" (get-in result [:outputs :parent-only])))
      (is (= 2 (count (:traces family))))
      (is (= (:trace-id result)
             (:parent-trace-id (first (filter :parent-trace-id (:traces family)))))))))

(deftest det-e2e-178-exact-delegate-output-allowlist
  (h/with-async-test-context [ctx]
    (let [{:keys [result family]} (execute-isolation-fixture ctx "writes")
          child-id (:trace-id (first (filter :parent-trace-id (:traces family))))
          _ (is (h/settle-until!
                 #(some? (get-in (h/run-query ctx {:query/name :sheet/get-trace
                                                    :trace-id child-id})
                                  [:query/result :trace]))))
          child-trace (get-in (h/run-query ctx {:query/name :sheet/get-trace
                                                 :trace-id child-id})
                              [:query/result :trace])]
      (is (= :success (:status result)))
      (is (= "work-child" (get-in result [:outputs :mapped-output])))
      (is (= "parent-original" (get-in result [:outputs :child-only]))
          "an undeclared child output cannot overwrite an existing parent value")
      (is (= "private" (get-in result [:outputs :parent-only])))
      (is (some #(and (= "inspect" (:node-name %)) (= :success (:status %)))
                (:node-traces child-trace))
          "the child-only output was produced by a successful child execution"))))

(defn- empty-contract-workflow! [ctx suffix function-name & {:keys [timeout-ms]}]
  (let [child-id (sheet/build-workflow!
                  ctx
                  (sheet/workflow (str "empty-contract-child-" suffix)
                    (sheet/blackboard {:hidden :string})
                    (sheet/code "child-work" :fn (fq function-name) :writes [:hidden])))
        parent (sheet/workflow (str "empty-contract-parent-" suffix)
                 (sheet/blackboard {:parent-value :string})
                 (cond-> (sheet/delegate "child" :target-sheet-id child-id)
                   timeout-ms (assoc :timeout-ms timeout-ms)))]
    (sheet/build-workflow! ctx parent)))

(deftest det-e2e-179-empty-delegate-contract
  (testing "success executes the child without exposing its private value"
    (h/with-async-test-context [ctx]
      (let [parent-id (empty-contract-workflow! ctx "success" "empty-success")
            result (sheet/execute ctx parent-id {:parent-value "kept"})]
        (is (= :success (:status result)))
        (is (= {:parent-value "kept"} (:outputs result))))))
  (testing "failure crosses as status/error but invents no parent write"
    (h/with-async-test-context [ctx]
      (let [parent-id (empty-contract-workflow! ctx "failure" "empty-failure")
            result (sheet/execute ctx parent-id {:parent-value "kept"})]
        (is (= :failure (:status result)))
        (is (re-find #"empty delegate failed" (:error result)))
        (is (= {:parent-value "kept"} (:outputs result))))))
  (testing "timeout terminates without publishing the child's late private value"
    (h/with-async-test-context [ctx]
      (reset! empty-release (promise))
      (try
        (let [parent-id (empty-contract-workflow! ctx "timeout" "empty-wait"
                                                   :timeout-ms 25)
              result (sheet/execute ctx parent-id {:parent-value "kept"}
                                    :timeout-ms 2000)]
          (is (= :timeout (:status result)))
          (is (nil? (get-in result [:outputs :hidden]))))
        (finally
          (deliver @empty-release true)))))
  (testing "cancellation terminates the root and child without parent writes"
    (h/with-async-test-context [ctx]
      (reset! empty-release (promise))
      (try
        (let [parent-id (empty-contract-workflow! ctx "cancel" "empty-wait"
                                                   :timeout-ms 4000)
              tick-id (random-uuid)
              pending (future (sheet/execute ctx parent-id {:parent-value "kept"}
                                             :tick-id tick-id :timeout-ms 5000))]
          (is (h/settle-until!
               #(some (fn [event]
                        (and (= :sheet/tree-tick-started (:event/type event))
                             (= tick-id (:parent-tick-id event))))
                      (h/read-all-events ctx))))
          (let [cancelled (:cancelled (sheet/cancel! ctx tick-id))
                result (deref pending 3000 ::timeout)]
            (is (= 2 (count cancelled)))
            (is (not= ::timeout result))
            (is (true? (:cancelled? result)))
            (is (nil? (get-in result [:outputs :hidden])))))
        (finally
          (deliver @empty-release true))))))

(defn- constant-child! [ctx suffix function-name]
  (sheet/build-workflow!
   ctx
   (sheet/workflow (str "delegate-version-child-" suffix)
     (sheet/blackboard {:result :string})
     (sheet/code "constant" :fn (fq function-name) :writes [:result]))))

(defn- publish! [ctx sheet-id]
  (h/run-command ctx (h/make-publish-version-command sheet-id)))

(defn- execution-mode! [ctx sheet-id mode]
  (h/run-command ctx (h/make-set-execution-mode-command sheet-id mode)))

(defn- settled-family
  ([ctx root-trace-id] (settled-family ctx root-trace-id 2))
  ([ctx root-trace-id expected-count]
   (is (h/settle-until!
        #(= expected-count
            (count (get-in (h/run-query ctx
                             {:query/name :sheet/get-trace-family
                              :trace-id root-trace-id})
                           [:query/result :traces])))))
   (get-in (h/run-query ctx {:query/name :sheet/get-trace-family
                             :trace-id root-trace-id})
           [:query/result])))

(deftest det-e2e-175-parent-version-freezes-delegate-configuration
  (h/with-async-test-context [ctx]
    (let [target-a (constant-child! ctx "parent-published-a" "version-a")
          target-b (constant-child! ctx "parent-draft-b" "version-b")
          parent-id (sheet/build-workflow!
                     ctx
                     (sheet/workflow "delegate-parent-version-freeze"
                       (sheet/blackboard {:result :string})
                       (sheet/delegate "child" :target-sheet-id target-a
                         :writes [:result])))
          delegate-id (get-in (dsl/export-sheet ctx parent-id) [:nodes :id])]
      (is (not (anomaly? (publish! ctx parent-id))))
      (is (not (anomaly? (execution-mode! ctx parent-id :published))))
      (is (not (anomaly?
                (h/run-command
                 ctx
                 (h/make-set-delegate-config-command
                  parent-id delegate-id target-b :writes [:result])))))
      (let [published (sheet/execute ctx parent-id {} :timeout-ms 2000)
            draft (sheet/execute ctx parent-id {} :force-draft true :timeout-ms 2000)]
        (is (= :success (:status published)))
        (is (= "A" (get-in published [:outputs :result])))
        (is (= 1 (:executed-version published)))
        (is (= :success (:status draft)))
        (is (= "B" (get-in draft [:outputs :result])))
        (is (nil? (:executed-version draft)))
        (is (= 1 (get-in (h/run-query ctx {:query/name :sheet/get-trace
                                            :trace-id (:trace-id published)})
                         [:query/result :trace :version-number])))
        (is (nil? (get-in (h/run-query ctx {:query/name :sheet/get-trace
                                             :trace-id (:trace-id draft)})
                          [:query/result :trace :version-number])))))))

(deftest det-e2e-176-delegated-target-version-selection
  (h/with-async-test-context [ctx]
    (let [target-id (constant-child! ctx "target-selection" "version-a")
          _ (is (not (anomaly? (publish! ctx target-id))))
          parent-id (sheet/build-workflow!
                     ctx
                     (sheet/workflow "delegate-target-version-selection"
                       (sheet/blackboard {:result :string})
                       (sheet/delegate "child" :target-sheet-id target-id
                         :writes [:result])))]
      ;; Change the target draft after publishing v1.
      (is (= target-id
             (sheet/build-workflow!
              ctx
              (sheet/workflow "delegate-version-child-target-selection"
                (sheet/blackboard {:result :string})
                (sheet/code "constant" :fn (fq "version-b") :writes [:result])))))
      (is (not (anomaly? (execution-mode! ctx target-id :published))))
      (let [published-child (sheet/execute ctx parent-id {} :timeout-ms 2000)
            published-family (settled-family ctx (:trace-id published-child))
            published-summary (first (filter :parent-trace-id
                                             (:traces published-family)))
            _ (is (h/settle-until! #(h/trace-stored? ctx (:trace-id published-summary))))
            published-trace (get-in (h/run-query ctx {:query/name :sheet/get-trace
                                                       :trace-id (:trace-id published-summary)})
                                    [:query/result :trace])]
        (is (= "A" (get-in published-child [:outputs :result])))
        (is (= target-id (:sheet-id published-trace)))
        (is (= 1 (:version-number published-trace))))
      (is (not (anomaly? (execution-mode! ctx target-id :draft))))
      (let [draft-child (sheet/execute ctx parent-id {} :timeout-ms 2000)
            draft-family (settled-family ctx (:trace-id draft-child))
            draft-summary (first (filter :parent-trace-id (:traces draft-family)))
            _ (is (h/settle-until! #(h/trace-stored? ctx (:trace-id draft-summary))))
            draft-trace (get-in (h/run-query ctx {:query/name :sheet/get-trace
                                                   :trace-id (:trace-id draft-summary)})
                                [:query/result :trace])]
        (is (= "B" (get-in draft-child [:outputs :result])))
        (is (= target-id (:sheet-id draft-trace)))
        (is (nil? (:version-number draft-trace)))))))

(deftest det-e2e-181-delegate-read-write-provenance
  (h/with-async-test-context [ctx]
    (let [child-id (sheet/build-workflow!
                    ctx
                    (sheet/workflow "delegate-provenance-child"
                      (sheet/blackboard {:handoff :string :result :string})
                      (sheet/code "child-copy" :fn (fq "provenance-child")
                        :reads [:handoff] :writes [:result])))
          parent-id (sheet/build-workflow!
                     ctx
                     (sheet/workflow "delegate-provenance-parent"
                       (sheet/blackboard {:seed :string :handoff :string
                                          :result :string :final :string})
                       (sheet/sequence "main"
                         (sheet/code "produce" :fn (fq "provenance-parent")
                           :reads [:seed] :writes [:handoff])
                         (sheet/delegate "delegate" :target-sheet-id child-id
                           :reads [:handoff] :writes [:result])
                         (sheet/code "consume" :fn (fq "provenance-downstream")
                           :reads [:result] :writes [:final]))))
          result (sheet/execute ctx parent-id {:seed "origin"})
          family (settled-family ctx (:trace-id result))
          child-tick-id (:trace-id (first (filter :parent-trace-id (:traces family))))
          root-events (h/read-tick-events ctx (:trace-id result))
          child-events (h/read-tick-events ctx child-tick-id)
          root-completions (filter #(= :sheet/node-execution-completed (:event/type %))
                                   root-events)
          producer (first (filter #(contains? (set (:write-keys %)) :handoff)
                                  root-completions))
          delegate (first (filter :completion-id root-completions))
          consumer (first (filter #(contains? (set (:write-keys %)) :final)
                                  root-completions))
          child-leaf (first (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                          (contains? (set (:write-keys %)) :result))
                                    child-events))
          parent-handoff-event (first (filter #(and (= :sheet/execution-value-written
                                                        (:event/type %))
                                                    (= :handoff (:key %)))
                                              root-events))
          child-result-event (first (filter #(and (= :sheet/execution-value-written
                                                      (:event/type %))
                                                  (= :result (:key %)))
                                            child-events))
          parent-handoff-source {:tick-id (:trace-id result)
                                 :event-id (:event/id parent-handoff-event)}
          child-result-source {:tick-id child-tick-id
                               :event-id (:event/id child-result-event)}]
      (is (= :success (:status result)))
      (is (= "origin-parent-child-done" (get-in result [:outputs :final])))
      (is (= parent-handoff-source (get-in child-leaf [:read-sources :handoff]))
          (pr-str child-leaf))
      (is (= child-result-source (get-in delegate [:write-sources :result])))
      (is (= child-result-source (get-in consumer [:read-sources :result]))
          (pr-str consumer))
      (is (= "origin-parent"
             (value-log/resolve-source ctx (:tenant-id ctx) parent-handoff-source)))
      (is (= "origin-parent-child"
             (value-log/resolve-source ctx (:tenant-id ctx) child-result-source))))))

(defn- input-contract-workflow! [ctx suffix parent-schema child-schema]
  (let [child-id (sheet/build-workflow!
                  ctx
                  (sheet/workflow (str "delegate-input-contract-child-" suffix)
                    (sheet/blackboard {:input child-schema :output child-schema})
                    (sheet/code "must-not-run" :fn (fq "contract-child")
                      :reads [:input] :writes [:output])))]
    (sheet/build-workflow!
     ctx
     (sheet/workflow (str "delegate-input-contract-parent-" suffix)
       (sheet/blackboard {:input parent-schema :output child-schema})
       (sheet/delegate "child" :target-sheet-id child-id
         :reads [:input] :writes [:output])))))

(deftest det-e2e-182-delegate-input-contract-rejection
  (h/with-async-test-context [ctx]
    (reset! contract-child-calls 0)
    (let [nested-schema [:map [:items [:vector [:map [:id :int]]]]]
          cases [{:suffix "missing" :parent [:maybe :string] :child :string :inputs {}}
                 {:suffix "type" :parent [:or :string :int] :child :string
                  :inputs {:input 42}}
                 {:suffix "nested"
                  :parent [:map [:items [:vector [:map [:id :string]]]]]
                  :child nested-schema
                  :inputs {:input {:items [{:id "wrong"}]}}}]
          results (mapv (fn [{:keys [suffix parent child inputs]}]
                          (sheet/execute ctx
                                         (input-contract-workflow!
                                          ctx suffix parent child)
                                         inputs :timeout-ms 2000))
                        cases)]
      (is (every? #(= :failure (:status %)) results))
      (is (every? #(re-find #"(?i)(schema|input|required|invalid)"
                            (or (:error %) ""))
                  results))
      (is (zero? @contract-child-calls))
      (doseq [result results]
        (let [trace (get-in (h/run-query ctx {:query/name :sheet/get-trace
                                               :trace-id (:trace-id result)})
                            [:query/result :trace])]
          (is (= :failure (:status trace)))
          (is (some #(and (= :delegate (:node-type %))
                          (= :failure (:status %)))
                    (:node-traces trace)))
          (is (nil? (get-in result [:outputs :output]))))))))

(defn- output-contract-workflow! [ctx suffix function-name]
  (let [child-id (sheet/build-workflow!
                  ctx
                  (sheet/workflow (str "delegate-output-contract-child-" suffix)
                    (sheet/blackboard {:output :string})
                    (sheet/code "invalid-producer" :fn (fq function-name)
                      :writes [:output])))]
    (sheet/build-workflow!
     ctx
     (sheet/workflow (str "delegate-output-contract-parent-" suffix)
       (sheet/blackboard {:output :string :after :string})
       (sheet/sequence "main"
         (sheet/delegate "child" :target-sheet-id child-id :writes [:output])
         (sheet/code "downstream" :fn (fq "contract-downstream")
           :writes [:after]))))))

(deftest det-e2e-183-delegate-output-contract-rejection
  (h/with-async-test-context [ctx]
    (reset! contract-downstream-calls 0)
    (let [results (mapv (fn [[suffix function-name]]
                          (sheet/execute
                           ctx (output-contract-workflow! ctx suffix function-name)
                           {} :timeout-ms 2000))
                        [["omitted" "omit-required-output"]
                         ["nil" "nil-required-output"]
                         ["invalid" "invalid-required-output"]])]
      (is (every? #(= :failure (:status %)) results))
      (is (every? #(re-find #"(?i)(write|output|schema|nil|omitted)"
                            (or (:error %) ""))
                  results))
      (is (zero? @contract-downstream-calls))
      (is (every? #(nil? (get-in % [:outputs :output])) results))
      (doseq [result results]
        (let [family (settled-family ctx (:trace-id result))]
          (is (= 2 (count (:traces family))))
          (is (every? #(= :failure (:status %)) (:traces family))))))))

(deftest det-e2e-186-delegate-partial-child-semantics
  (h/with-async-test-context [ctx]
    (let [item-schema [:map [:id :int] [:processed {:optional true} :boolean]]
          child-id (sheet/build-workflow!
                    ctx
                    (sheet/workflow "delegate-partial-child"
                      (sheet/blackboard {:items [:vector item-schema]
                                         :item item-schema
                                         :results [:vector item-schema]})
                      (sheet/map-each "partial-map" :from :items :as :item
                        :into :results :parallel 2
                        (sheet/code "maybe" :fn (fq "partial-item")
                          :reads [:item] :writes [:item]))))
          parent-id (sheet/build-workflow!
                     ctx
                     (sheet/workflow "delegate-partial-parent"
                       (sheet/blackboard {:items [:vector item-schema]
                                          :results [:vector item-schema]})
                       (sheet/delegate "child" :target-sheet-id child-id
                         :reads [:items] :writes [:results])))
          result (sheet/execute ctx parent-id
                                {:items [{:id 1} {:id 2} {:id 3}]}
                                :timeout-ms 3000)
          family (settled-family ctx (:trace-id result))
          child (first (filter :parent-trace-id (:traces family)))
          root-events (h/read-tick-events ctx (:trace-id result))
          delivery (first (filter #(and (= :sheet/node-execution-completed
                                           (:event/type %))
                                        (:completion-id %))
                                  root-events))]
      (is (= :partial (:status result)))
      (is (= :partial (:status child)))
      (is (= :partial (:status delivery)))
      (is (= [{:id 1 :processed true} {:id 3 :processed true}]
             (get-in result [:outputs :results])))
      (is (= [1] (get-in (first (filter :partial-summary
                                        (h/read-tick-events ctx (:trace-id child))))
                          [:partial-summary :failure-indices])))
      (is (not-any? #(= :delegate-invocation/partial (:event/type %)) root-events)))))

(deftest det-e2e-187-delegate-blocked-child-semantics
  (h/with-async-test-context [ctx]
    (let [child-id (sheet/build-workflow!
                    ctx
                    (sheet/workflow "delegate-blocked-child"
                      (sheet/blackboard {})
                      (sheet/code "block" :fn (fq "block-child"))))
          parent-id (sheet/build-workflow!
                     ctx
                     (sheet/workflow "delegate-blocked-parent"
                       (sheet/blackboard {})
                       (sheet/delegate "child" :target-sheet-id child-id)))
          result (sheet/execute ctx parent-id {} :timeout-ms 3000)
          family (settled-family ctx (:trace-id result))
          child (first (filter :parent-trace-id (:traces family)))
          child-completion (first (filter #(= :sheet/tree-tick-completed (:event/type %))
                                          (h/read-tick-events ctx (:trace-id child))))]
      (is (= :blocked (:status result)))
      (is (= :blocked (:status child)))
      (is (= :blocked (:root-status child-completion)))
      (is (= blocked-payload (:block-payload child-completion)))
      (is (empty? (:outputs result)))
      (is (= (:trace-id result) (:parent-trace-id child))))))

(deftest det-e2e-184-delegate-success-transition
  (h/with-async-test-context [ctx]
    (let [{:keys [result family]} (execute-isolation-fixture ctx "success-transition")
          child-id (:trace-id (first (filter :parent-trace-id (:traces family))))
          events (h/read-all-events ctx)
          delivery (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                 (= child-id (:completion-id %)))
                           events)
          parent-terminal (filter #(and (= :sheet/tree-tick-completed (:event/type %))
                                        (= (:trace-id result) (:tick-id %)))
                                  events)
          child-terminal (filter #(and (= :sheet/tree-tick-completed (:event/type %))
                                       (= child-id (:tick-id %))
                                       (not= :running (:root-status %)))
                                 events)
          root-trace (get-in (h/run-query ctx {:query/name :sheet/get-trace
                                                :trace-id (:trace-id result)})
                             [:query/result :trace])]
      (is (= :success (:status result)))
      (is (= "work-child" (get-in result [:outputs :mapped-output])))
      (is (= 1 (count delivery)))
      (is (= :success (:status (first delivery))))
      (is (= 1 (count parent-terminal)))
      (is (= 1 (count child-terminal)))
      (is (= :success (:root-status (first parent-terminal))))
      (is (= :success (:root-status (first child-terminal))))
      (is (some #(and (= :delegate (:node-type %))
                      (= :success (:status %))
                      (= child-id (:delegate-child-tick-id %)))
                (:node-traces root-trace))))))

(deftest det-e2e-185-delegate-failure-transition-with-fallback
  (h/with-async-test-context [ctx]
    (reset! fallback-recovery-calls 0)
    (let [child-id (sheet/build-workflow!
                    ctx
                    (sheet/workflow "delegate-failure-child"
                      (sheet/blackboard {})
                      (sheet/code "fail" :fn (fq "empty-failure"))))
          parent-id (sheet/build-workflow!
                     ctx
                     (sheet/workflow "delegate-failure-parent"
                       (sheet/blackboard {:preserved :string :recovered :string})
                       (sheet/fallback "recover"
                         (sheet/delegate "child" :target-sheet-id child-id)
                         (sheet/code "fallback" :fn (fq "fallback-recovery")
                           :writes [:recovered]))))
          result (sheet/execute ctx parent-id {:preserved "safe"} :timeout-ms 3000)
          family (settled-family ctx (:trace-id result))
          child (first (filter :parent-trace-id (:traces family)))
          _ (is (h/settle-until! #(h/trace-stored? ctx (:trace-id child))))
          child-trace (get-in (h/run-query ctx {:query/name :sheet/get-trace
                                                 :trace-id (:trace-id child)})
                              [:query/result :trace])
          root-trace (get-in (h/run-query ctx {:query/name :sheet/get-trace
                                                :trace-id (:trace-id result)})
                             [:query/result :trace])]
      (is (= :success (:status result)))
      (is (= "safe" (get-in result [:outputs :preserved])))
      (is (= "yes" (get-in result [:outputs :recovered])))
      (is (= 1 @fallback-recovery-calls))
      (is (= :failure (:status child)))
      (is (re-find #"empty delegate failed" (or (:error child-trace) "")))
      (is (some #(and (= :delegate (:node-type %)) (= :failure (:status %)))
                (:node-traces root-trace)))
      (is (= 1 (count (filter #(and (= :sheet/node-execution-completed
                                      (:event/type %))
                                   (= (:trace-id child) (:completion-id %)))
                             (h/read-all-events ctx))))))))

(deftest det-e2e-188-delegate-timeout-transition-and-child-trace
  (h/with-async-test-context [ctx]
    (reset! empty-release (promise))
    (try
      (let [parent-id (empty-contract-workflow! ctx "terminal-timeout" "empty-wait"
                                                 :timeout-ms 40)
            result (sheet/execute ctx parent-id {:parent-value "kept"}
                                  :timeout-ms 2000)
            family (settled-family ctx (:trace-id result))
            child (first (filter :parent-trace-id (:traces family)))
            root-terminal (filter #(and (= :sheet/tree-tick-completed (:event/type %))
                                        (= (:trace-id result) (:tick-id %)))
                                  (h/read-all-events ctx))
            child-terminal (filter #(and (= :sheet/tree-tick-completed (:event/type %))
                                         (= (:trace-id child) (:tick-id %)))
                                   (h/read-all-events ctx))]
        (is (= :timeout (:status result)))
        (is (= :timeout (:status child)))
        (is (= 1 (count root-terminal)))
        (is (= 1 (count child-terminal)))
        (is (= :timeout (:root-status (first root-terminal))))
        (is (= :timeout (:root-status (first child-terminal))))
        (is (and (number? (:duration-ms child))
                 (< (:duration-ms child) 2000)))
        (is (nil? (get-in result [:outputs :hidden]))))
      (finally
        (deliver @empty-release true)))))

(deftest det-e2e-198-distinct-delegates-targeting-one-workflow
  (h/with-async-test-context [ctx]
    (reset! shared-target-calls 0)
    (let [child-id (sheet/build-workflow!
                    ctx
                    (sheet/workflow "shared-delegate-target"
                      (sheet/blackboard {:input :string :result :string})
                      (sheet/code "echo" :fn (fq "shared-target")
                        :reads [:input] :writes [:result])))
          parent-id (sheet/build-workflow!
                     ctx
                     (sheet/workflow "two-distinct-delegates"
                       (sheet/blackboard {:input :string :result :string})
                       (sheet/sequence "both"
                         (sheet/delegate "first" :target-sheet-id child-id
                           :reads [:input] :writes [:result])
                         (sheet/delegate "second" :target-sheet-id child-id
                           :reads [:input] :writes [:result]))))
          result (sheet/execute ctx parent-id {:input "same"} :timeout-ms 3000)
          family (get-in (h/run-query ctx {:query/name :sheet/get-trace-family
                                            :trace-id (:trace-id result)})
                         [:query/result])
          children (filter :parent-trace-id (:traces family))
          deliveries (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                   (:completion-id %))
                             (h/read-tick-events ctx (:trace-id result)))]
      (is (= :success (:status result)))
      (is (= "same" (get-in result [:outputs :result])))
      (is (= 2 @shared-target-calls))
      (is (= 2 (count children)))
      (is (= 2 (count (set (map :trace-id children)))))
      (is (= 2 (count deliveries)))
      (is (= 2 (count (set (map :completion-id deliveries))))))))

(deftest det-e2e-201-truly-parallel-delegates
  (h/with-async-test-context [ctx]
    (reset! parallel-active 0)
    (reset! parallel-peak 0)
    (reset! parallel-observed {})
    (reset! parallel-both-started (promise))
    (reset! parallel-release (promise))
    (let [child-a (sheet/build-workflow!
                   ctx
                   (sheet/workflow "parallel-delegate-child-a"
                     (sheet/blackboard {:shared :string :a :string :b [:maybe :string]})
                     (sheet/code "a" :fn (fq "parallel-a")
                       :reads [:shared :b] :writes [:a])))
          child-b (sheet/build-workflow!
                   ctx
                   (sheet/workflow "parallel-delegate-child-b"
                     (sheet/blackboard {:shared :string :a [:maybe :string] :b :string})
                     (sheet/code "b" :fn (fq "parallel-b")
                       :reads [:shared :a] :writes [:b])))
          parent-id (sheet/build-workflow!
                     ctx
                     (sheet/workflow "parallel-delegate-parent"
                       (sheet/blackboard {:shared :string :a :string :b :string})
                       (sheet/parallel "parallel"
                         {:success-policy :all :failure-policy :any}
                         (sheet/delegate "a" :target-sheet-id child-a
                           :reads [:shared] :writes [:a])
                         (sheet/delegate "b" :target-sheet-id child-b
                           :reads [:shared] :writes [:b]))))
          correlation-id (random-uuid)
          pending (future (sheet/execute ctx parent-id {:shared "only"}
                                         :timeout-ms 4000
                                         :correlation-id correlation-id))]
      (is (= true (deref @parallel-both-started 2000 ::timeout)))
      (deliver @parallel-release true)
      (let [result (deref pending 3000 ::timeout)
            family (settled-family ctx (:trace-id result) 3)
            children (filter :parent-trace-id (:traces family))]
        (is (not= ::timeout result))
        (is (= :success (:status result)))
        (is (= 2 @parallel-peak))
        (is (= {:a {:shared "only" :b nil}
                :b {:shared "only" :a nil}}
               @parallel-observed))
        (is (= "a" (get-in result [:outputs :a])))
        (is (= "b" (get-in result [:outputs :b])))
        (is (= 2 (count children)))
        (is (= #{correlation-id} (set (map :correlation-id children))))))))

(deftest det-e2e-202-nested-delegate-lineage-chain
  (h/with-async-test-context [ctx]
    (let [grandchild-id (sheet/build-workflow!
                         ctx
                         (sheet/workflow "lineage-grandchild"
                           (sheet/blackboard {:value :string})
                           (sheet/code "work" :fn (fq "lineage-grandchild")
                             :reads [:value] :writes [:value])))
          child-id (sheet/build-workflow!
                    ctx
                    (sheet/workflow "lineage-child"
                      (sheet/blackboard {:value :string})
                      (sheet/delegate "grandchild" :target-sheet-id grandchild-id
                        :reads [:value] :writes [:value])))
          parent-id (sheet/build-workflow!
                     ctx
                     (sheet/workflow "lineage-parent"
                       (sheet/blackboard {:value :string})
                       (sheet/delegate "child" :target-sheet-id child-id
                         :reads [:value] :writes [:value])))
          correlation-id (random-uuid)
          result (sheet/execute ctx parent-id {:value "root"}
                                :correlation-id correlation-id :timeout-ms 3000)
          family (settled-family ctx (:trace-id result) 3)
          by-sheet (into {} (map (juxt :sheet-id identity) (:traces family)))
          root (get by-sheet parent-id)
          child (get by-sheet child-id)
          grandchild (get by-sheet grandchild-id)
          ids (mapv :trace-id [root child grandchild])
          completions (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                    (:completion-id %))
                              (h/read-all-events ctx))]
      (is (= :success (:status result)))
      (is (= "root-grandchild" (get-in result [:outputs :value])))
      (is (= 3 (count (set ids))))
      (is (= (:trace-id root) (:parent-trace-id child)))
      (is (= (:trace-id child) (:parent-trace-id grandchild)))
      (is (every? #(= (:trace-id root) (:root-trace-id %))
                  [root child grandchild]))
      (is (= #{correlation-id} (set (map :correlation-id [root child grandchild]))))
      (is (every? nil? (map :version-number [root child grandchild])))
      (is (= #{(:trace-id child) (:trace-id grandchild)}
             (set (map :completion-id completions)))))))

(deftest det-e2e-200-delegate-inside-map-each
  (h/with-async-test-context [ctx]
    (reset! map-delegate-active 0)
    (reset! map-delegate-peak 0)
    (reset! map-delegate-inputs [])
    (reset! map-delegate-two-started (promise))
    (reset! map-delegate-release (promise))
    (let [item-schema [:map [:id :int] [:processed {:optional true} :boolean]]
          child-id (sheet/build-workflow!
                    ctx
                    (sheet/workflow "map-delegate-child"
                      (sheet/blackboard {:item item-schema})
                      (sheet/code "work" :fn (fq "map-delegate-child")
                        :reads [:item] :writes [:item])))
          parent-id (sheet/build-workflow!
                     ctx
                     (sheet/workflow "map-delegate-parent"
                       (sheet/blackboard {:items [:vector item-schema]
                                          :item item-schema
                                          :results [:vector item-schema]})
                       (sheet/map-each "map" :from :items :as :item
                         :into :results :parallel 2
                         (sheet/delegate "child" :target-sheet-id child-id
                           :reads [:item] :writes [:item]))))
          input [{:id 1} {:id 2} {:id 3}]
          pending (future (sheet/execute ctx parent-id {:items input}
                                         :timeout-ms 4000))]
      (is (= true (deref @map-delegate-two-started 2000 ::timeout)))
      (deliver @map-delegate-release true)
      (let [result (deref pending 3000 ::timeout)
            family (settled-family ctx (:trace-id result) 4)
            children (filter :parent-trace-id (:traces family))
            child-starts (filter #(and (= :sheet/tree-tick-started (:event/type %))
                                       (= (:trace-id result) (:parent-tick-id %)))
                                 (h/read-all-events ctx))]
        (is (not= ::timeout result))
        (is (= :success (:status result)))
        (is (= (mapv #(assoc % :processed true) input)
               (get-in result [:outputs :results])))
        (is (= 2 @map-delegate-peak))
        (is (= (set input) (set @map-delegate-inputs)))
        (is (= 3 (count children)))
        (is (= 3 (count child-starts)))
        (is (= 3 (count (set (map :tick-id child-starts)))))
        (is (= #{0 1 2}
               (set (keep #(get-in % [:options :delegate-parent-exec-context
                                       :ai.obney.orc.orc-service.core.todo-processors/map-each-index])
                          child-starts))))))))

(deftest det-e2e-211-delegate-family-and-correlation-queries
  (h/with-async-test-context [ctx]
    (let [grandchild-id (sheet/build-workflow!
                         ctx
                         (sheet/workflow "family-grandchild"
                           (sheet/blackboard {:value :string})
                           (sheet/code "work" :fn (fq "lineage-grandchild")
                             :reads [:value] :writes [:value])))
          child-id (sheet/build-workflow!
                    ctx
                    (sheet/workflow "family-child"
                      (sheet/blackboard {:value :string})
                      (sheet/delegate "grandchild" :target-sheet-id grandchild-id
                        :reads [:value] :writes [:value])))
          parent-id (sheet/build-workflow!
                     ctx
                     (sheet/workflow "family-parent"
                       (sheet/blackboard {:value :string})
                       (sheet/delegate "child" :target-sheet-id child-id
                         :reads [:value] :writes [:value])))
          correlation-id (random-uuid)
          first-result (sheet/execute ctx parent-id {:value "one"}
                                      :correlation-id correlation-id)
          second-result (sheet/execute ctx parent-id {:value "two"}
                                       :correlation-id correlation-id)
          unrelated (sheet/execute ctx parent-id {:value "other"})
          first-family (settled-family ctx (:trace-id first-result) 3)
          second-family (settled-family ctx (:trace-id second-result) 3)
          first-ids (mapv :trace-id (:traces first-family))
          from-each (mapv #(get-in (h/run-query ctx {:query/name :sheet/get-trace-family
                                                      :trace-id %})
                                   [:query/result])
                          first-ids)
          correlated (get-in (h/run-query ctx {:query/name :sheet/get-correlated-traces
                                                :correlation-id correlation-id})
                             [:query/result])
          other-tenant (assoc ctx :tenant-id (random-uuid))]
      (is (every? #(= first-family %) from-each))
      (is (= 6 (count (:traces correlated))))
      (is (= 2 (count (:families correlated))))
      (is (= #{(:trace-id first-result) (:trace-id second-result)}
             (set (map :root-trace-id (:families correlated)))))
      (is (not (contains? (set (map :trace-id (:traces correlated)))
                          (:trace-id unrelated))))
      (is (= [(:trace-id first-result) (:trace-id second-result)]
             (mapv :root-trace-id (:families correlated))))
      (is (anomaly? (h/run-query other-tenant {:query/name :sheet/get-trace-family
                                                :trace-id (:trace-id first-result)})))
      (is (empty? (get-in (h/run-query other-tenant
                            {:query/name :sheet/get-correlated-traces
                             :correlation-id correlation-id})
                          [:query/result :traces]))))))

(deftest det-e2e-215-delegate-event-schema-and-causation-integrity
  (h/with-async-test-context [ctx]
    (let [child-id (constant-child! ctx "event-integrity" "version-a")
          parent-id (sheet/build-workflow!
                     ctx
                     (sheet/workflow "delegate-event-integrity-parent"
                       (sheet/blackboard {:result :string})
                       (sheet/delegate "child" :target-sheet-id child-id
                         :writes [:result])))
          correlation-id (random-uuid)
          result (sheet/execute ctx parent-id {} :correlation-id correlation-id)
          family (settled-family ctx (:trace-id result))
          ticks (set (map :trace-id (:traces family)))
          events (filter #(ticks (:tick-id %)) (h/read-all-events ctx))
          invalid (filter (fn [event]
                            (let [schema (get @schema-util/registry* (:event/type event))]
                              (or (nil? schema) (not (m/validate schema event)))))
                          events)
          child-start (first (filter #(and (= :sheet/tree-tick-started (:event/type %))
                                           (:parent-tick-id %))
                                     events))
          delivery (first (filter #(and (= :sheet/node-execution-completed
                                           (:event/type %))
                                        (:completion-id %))
                                  events))]
      (is (= :success (:status result)))
      (is (seq events))
      (is (empty? invalid) (str "invalid delegate events: " (mapv :event/type invalid)))
      (is (every? uuid? (map :event/id events)))
      (is (every? some? (map :event/timestamp events)))
      (is (= (:trace-id result) (:parent-tick-id child-start)))
      (is (= correlation-id (:correlation-id child-start)))
      (is (= (:tick-id child-start) (:completion-id delivery)))
      (is (= parent-id (:sheet-id delivery)))
      (is (= (:trace-id result) (:tick-id delivery))))))

(defn- delegate-deliveries [ctx parent-tick-id child-tick-id]
  (filter #(and (= :sheet/node-execution-completed (:event/type %))
                (= parent-tick-id (:tick-id %))
                (= child-tick-id (:completion-id %)))
          (h/read-all-events ctx)))

(deftest det-e2e-189-delegate-terminal-state-immutability
  (testing "success is not overwritten by conflicting failure delivery"
    (h/with-async-test-context [ctx]
      (let [{:keys [result family]} (execute-isolation-fixture ctx "immutable-success")
            child-id (:trace-id (first (filter :parent-trace-id (:traces family))))]
        (run! deref (repeatedly 50 #(future (tp/complete-delegate-parent!
                                             ctx child-id
                                             {:status :failure
                                              :error "late conflict"
                                              :outputs {:mapped-output "evil"}}))))
        (is (= 1 (count (delegate-deliveries ctx (:trace-id result) child-id))))
        (is (= :success (:status (first (delegate-deliveries
                                         ctx (:trace-id result) child-id)))))
        (is (= "work-child" (get-in result [:outputs :mapped-output]))))))
  (testing "failure is not overwritten by conflicting success delivery"
    (h/with-async-test-context [ctx]
      (let [child-id (sheet/build-workflow!
                      ctx (sheet/workflow "immutable-failure-child"
                            (sheet/blackboard {:result :string})
                            (sheet/code "fail" :fn (fq "empty-failure"))))
            parent-id (sheet/build-workflow!
                       ctx (sheet/workflow "immutable-failure-parent"
                             (sheet/blackboard {:result :string})
                             (sheet/delegate "child" :target-sheet-id child-id
                               :writes [:result])))
            result (sheet/execute ctx parent-id {})
            family (settled-family ctx (:trace-id result))
            child-tick-id (:trace-id (first (filter :parent-trace-id (:traces family))))]
        (run! deref (repeatedly 50 #(future (tp/complete-delegate-parent!
                                             ctx child-tick-id
                                             {:status :success
                                              :outputs {:result "evil"}}))))
        (is (= 1 (count (delegate-deliveries ctx (:trace-id result) child-tick-id))))
        (is (= :failure (:status (first (delegate-deliveries
                                         ctx (:trace-id result) child-tick-id)))))
        (is (nil? (get-in result [:outputs :result]))))))
  (testing "timeout is not overwritten by a late success"
    (h/with-async-test-context [ctx]
      (reset! empty-release (promise))
      (try
        (let [parent-id (empty-contract-workflow! ctx "immutable-timeout" "empty-wait"
                                                   :timeout-ms 30)
              result (sheet/execute ctx parent-id {} :timeout-ms 2000)
              family (settled-family ctx (:trace-id result))
              child-id (:trace-id (first (filter :parent-trace-id (:traces family))))]
          (tp/complete-delegate-parent! ctx child-id
                                        {:status :success :outputs {:hidden "evil"}})
          (is (= 1 (count (delegate-deliveries ctx (:trace-id result) child-id))))
          (is (= :timeout (:status (first (delegate-deliveries
                                           ctx (:trace-id result) child-id)))))
          (is (nil? (get-in result [:outputs :hidden]))))
        (finally (deliver @empty-release true)))))
  (testing "cancellation fences a late child completion"
    (h/with-async-test-context [ctx]
      (reset! empty-release (promise))
      (try
        (let [parent-id (empty-contract-workflow! ctx "immutable-cancel" "empty-wait"
                                                   :timeout-ms 4000)
              tick-id (random-uuid)
              pending (future (sheet/execute ctx parent-id {} :tick-id tick-id
                                             :timeout-ms 5000))
              _ (is (h/settle-until!
                     #(some (fn [event]
                              (and (= :sheet/tree-tick-started (:event/type event))
                                   (= tick-id (:parent-tick-id event))))
                            (h/read-all-events ctx))))
              child-id (:tick-id (first (filter #(and (= :sheet/tree-tick-started
                                                          (:event/type %))
                                                       (= tick-id (:parent-tick-id %)))
                                                 (h/read-all-events ctx))))]
          (sheet/cancel! ctx tick-id)
          (let [result (deref pending 2000 ::timeout)]
            (tp/complete-delegate-parent! ctx child-id
                                          {:status :success :outputs {:hidden "evil"}})
            (is (not= ::timeout result))
            (is (:cancelled? result))
            (is (empty? (delegate-deliveries ctx tick-id child-id)))
            (is (nil? (get-in result [:outputs :hidden])))))
        (finally (deliver @empty-release true))))))

(deftest det-e2e-197-duplicate-dispatch-preserves-one-child-identity
  (h/with-async-test-context [ctx]
    (reset! duplicate-dispatch-calls 0)
    (reset! empty-release (promise))
    (try
      (let [child-id (sheet/build-workflow!
                      ctx (sheet/workflow "duplicate-dispatch-child"
                            (sheet/blackboard {:result :string})
                            (sheet/code "work" :fn (fq "duplicate-dispatch-child")
                              :writes [:result])))
            parent-id (sheet/build-workflow!
                       ctx (sheet/workflow "duplicate-dispatch-parent"
                             (sheet/blackboard {:result :string})
                             (sheet/delegate "child" :target-sheet-id child-id
                               :writes [:result])))
            tick-id (random-uuid)
            pending (future (sheet/execute ctx parent-id {} :tick-id tick-id
                                           :timeout-ms 4000))
            started-event (loop []
                            (or (first (filter #(and (= :sheet/node-execution-started
                                                        (:event/type %))
                                                     (= tick-id (:tick-id %)))
                                               (h/read-all-events ctx)))
                                (do (Thread/yield) (recur))))]
        (run! deref (repeatedly 100 #(future (tp/execute-delegate-node
                                              (assoc ctx :event started-event)))))
        (is (h/settle-until! #(= 1 @duplicate-dispatch-calls)))
        (let [child-starts (filter #(and (= :sheet/tree-tick-started (:event/type %))
                                         (= tick-id (:parent-tick-id %)))
                                   (h/read-all-events ctx))]
          (is (= 1 (count child-starts))
              (str "one logical delegate child: "
                   (pr-str (mapv #(select-keys % [:event/id :tick-id :parent-tick-id])
                                 child-starts))))
          (run! deref (repeatedly 100 #(future (tp/execute-delegate-node
                                                (assoc ctx :event started-event)))))
          (deliver @empty-release true)
          (let [result (deref pending 3000 ::timeout)
                child-tick-id (:tick-id (first child-starts))]
            (is (not= ::timeout result))
            (is (= :success (:status result)))
            (is (= "once" (get-in result [:outputs :result])))
            (is (= 1 @duplicate-dispatch-calls))
            (is (= 1 (count (delegate-deliveries ctx tick-id child-tick-id)))))))
      (finally (deliver @empty-release true)))))

(deftest det-e2e-206-recovery-after-child-terminal-before-delivery
  (doseq [[suffix function-name expected-status expected-output]
          [["success" "version-a" :success "A"]
           ["failure" "empty-failure" :failure nil]]]
    (testing suffix
      (h/with-async-test-context [ctx]
        (let [child-id (sheet/build-workflow!
                        ctx (sheet/workflow (str "recovery-terminal-child-" suffix)
                              (sheet/blackboard {:result :string})
                              (sheet/code "work" :fn (fq function-name)
                                :writes (if expected-output [:result] []))))
              parent-id (sheet/build-workflow!
                         ctx (sheet/workflow (str "recovery-terminal-parent-" suffix)
                               (sheet/blackboard {:result :string})
                               (sheet/delegate "child" :target-sheet-id child-id
                                 :writes [:result])))
              deliver! tp/complete-delegate-parent!
              gap (with-redefs [tp/complete-delegate-parent! (fn [& _] nil)]
                    (let [pending (future (sheet/execute ctx parent-id {}
                                                         :timeout-ms 4000))
                          child-tick-id (loop []
                                          (or (some #(when (and (= :sheet/tree-tick-started
                                                                    (:event/type %))
                                                               (:parent-tick-id %))
                                                      (:tick-id %))
                                                    (h/read-all-events ctx))
                                              (do (Thread/yield) (recur))))
                          terminal (loop []
                                     (or (runtime/durable-terminal-result ctx child-tick-id)
                                         (do (Thread/yield) (recur))))]
                      (is (= expected-status (:status terminal)))
                      (is (= ::waiting (deref pending 50 ::waiting)))
                      {:pending pending :child-id child-tick-id :terminal terminal}))]
          (run! deref (repeatedly 50 #(future (deliver! ctx (:child-id gap)
                                                        (:terminal gap)))))
          (let [result (deref (:pending gap) 2000 ::timeout)]
            (is (not= ::timeout result))
            (is (= expected-status (:status result)))
            (is (= expected-output (get-in result [:outputs :result])))
            (is (= 1 (count (delegate-deliveries
                             ctx (:trace-id result) (:child-id gap)))))))))))

(deftest det-e2e-204-recovery-before-durable-child-start
  (h/with-async-test-context [ctx]
    (reset! duplicate-dispatch-calls 0)
    (reset! empty-release (promise))
    (try
      (let [child-id (sheet/build-workflow!
                      ctx (sheet/workflow "recovery-before-start-child"
                            (sheet/blackboard {:result :string})
                            (sheet/code "work" :fn (fq "duplicate-dispatch-child")
                              :writes [:result])))
            parent-id (sheet/build-workflow!
                       ctx (sheet/workflow "recovery-before-start-parent"
                             (sheet/blackboard {:result :string})
                             (sheet/delegate "child" :target-sheet-id child-id
                               :writes [:result])))
            dispatch! tp/execute-delegate-node
            suppressed (promise)
            gap (with-redefs [tp/execute-delegate-node
                              (fn [& _] (deliver suppressed true) nil)]
                  (let [pending (future (sheet/execute ctx parent-id {}
                                                       :timeout-ms 4000))
                        started (loop []
                                  (or (first (filter #(= :sheet/node-execution-started
                                                          (:event/type %))
                                                     (h/read-all-events ctx)))
                                      (do (Thread/yield) (recur))))]
                    (is (= true (deref suppressed 2000 ::not-intercepted))
                        "the original processor delivery was intercepted")
                    (is (empty? (filter #(and (= :sheet/tree-tick-started
                                                 (:event/type %))
                                              (:parent-tick-id %))
                                        (h/read-all-events ctx))))
                    {:pending pending :started started}))]
        (run! deref (repeatedly 50 #(future (dispatch! (assoc ctx :event (:started gap))))))
        (is (h/settle-until! #(= 1 @duplicate-dispatch-calls)))
        (deliver @empty-release true)
        (let [result (deref (:pending gap) 2000 ::timeout)
              starts (filter #(and (= :sheet/tree-tick-started (:event/type %))
                                   (= (:trace-id result) (:parent-tick-id %)))
                             (h/read-all-events ctx))]
          (is (not= ::timeout result))
          (is (= :success (:status result)))
          (is (= "once" (get-in result [:outputs :result])))
          (is (= 1 (count starts)))
          (is (= 1 @duplicate-dispatch-calls))
          (is (= 1 (count (delegate-deliveries ctx (:trace-id result)
                                                (:tick-id (first starts))))))))
      (finally (deliver @empty-release true)))))

(deftest det-e2e-205-recovery-while-child-running
  (h/with-async-test-context [ctx]
    (reset! recovery-effect-calls 0)
    (let [child-id (sheet/build-workflow!
                    ctx (sheet/workflow "recovery-running-child"
                          (sheet/blackboard {:result :string})
                          (sheet/code "effect" :fn (fq "recovery-effect-child")
                            :writes [:result])))
          parent-id (sheet/build-workflow!
                     ctx (sheet/workflow "recovery-running-parent"
                           (sheet/blackboard {:result :string})
                           (sheet/delegate "child" :target-sheet-id child-id
                             :writes [:result] :timeout-ms 120000)))
          intercepted (promise)
          retire-release (promise)
          execute-leaf! tp/execute-leaf-node
          gap (with-redefs [tp/execute-leaf-node
                            (fn [{:keys [event] :as context}]
                              (if (= child-id (:sheet-id event))
                                (do (deliver intercepted event)
                                    @retire-release
                                    nil)
                                (execute-leaf! context)))]
                (let [pending (future (sheet/execute ctx parent-id {}
                                                     :timeout-ms 120000))]
                  {:pending pending
                   :abandoned-start (deref intercepted 5000 ::not-intercepted)}))
          pending (:pending gap)
          abandoned-start (:abandoned-start gap)]
      (is (not= ::not-intercepted abandoned-start))
      (is (zero? @recovery-effect-calls))
      (let [stop-result (future (h/stop-test-processors! ctx))]
        (deliver retire-release true)
        (is (not= ::stop-timeout (deref stop-result 5000 ::stop-timeout))))
      (runtime/deregister-completion! (:tick-id abandoned-start))
      (let [recovered-ctx (assoc ctx :processors (h/start-test-processors ctx))]
        (try
          (is (h/settle-until!
               #(some :resumed? (runtime/resume-in-progress! recovered-ctx))
               :timeout-ms 30000)
              "recovery waits until the abandoned frontier is projected")
          (let [result (deref pending 30000 ::timeout)
                child-starts (filter #(and (= :sheet/tree-tick-started (:event/type %))
                                           (= (:trace-id result) (:parent-tick-id %)))
                                     (h/read-all-events ctx))]
            (is (not= ::timeout result))
            (is (= :success (:status result)))
            (is (= "recovered-once" (get-in result [:outputs :result])))
            (is (= 1 @recovery-effect-calls))
            (is (= 1 (count child-starts)))
            (is (= 1 (count (delegate-deliveries
                             ctx (:trace-id result) (:tick-id (first child-starts)))))))
          (finally
            (h/stop-test-processors! recovered-ctx)))))))

(deftest det-e2e-207-recovery-after-delivery-before-parent-continuation
  (h/with-async-test-context [ctx]
    (reset! contract-child-calls 0)
    (reset! contract-downstream-calls 0)
    (let [child-id (sheet/build-workflow!
                    ctx (sheet/workflow "recovery-after-delivery-child"
                          (sheet/blackboard {:input :string :output :string})
                          (sheet/code "copy" :fn (fq "contract-child")
                            :reads [:input] :writes [:output])))
          parent-id (sheet/build-workflow!
                     ctx (sheet/workflow "recovery-after-delivery-parent"
                           (sheet/blackboard {:input :string :output :string
                                              :after :string})
                           (sheet/sequence "main"
                             (sheet/delegate "child" :target-sheet-id child-id
                               :reads [:input] :writes [:output])
                             (sheet/code "after" :fn (fq "contract-downstream")
                               :writes [:after]))))
          continue! tp/handle-child-completion
          gap (with-redefs [tp/handle-child-completion
                            (fn [{:keys [event] :as context}]
                              (when-not (and (= parent-id (:sheet-id event))
                                             (:completion-id event))
                                (continue! context)))]
                (let [pending (future (sheet/execute ctx parent-id {:input "v"}
                                                     :timeout-ms 4000))
                      delivery (loop [deadline (+ (System/currentTimeMillis) 3000)]
                                 (or (first (filter #(and (= :sheet/node-execution-completed
                                                            (:event/type %))
                                                         (= parent-id (:sheet-id %))
                                                         (:completion-id %))
                                                   (h/read-all-events ctx)))
                                     (when (< (System/currentTimeMillis) deadline)
                                       (Thread/yield)
                                       (recur deadline))))]
                  (is (some? delivery) "child delivery became durable")
                  (is (= ::waiting (deref pending 50 ::waiting)))
                  {:pending pending :delivery delivery}))
          append-continuation!
          #(when-let [events (seq (:result/events
                                   (continue! (assoc ctx :event (:delivery gap)))))]
             (es/append (:event-store ctx)
                        {:tenant-id (:tenant-id ctx) :events (vec events)})
             (vec events))]
      ;; The first recovery appends the continuation. Grain's strong
      ;; read-your-own-write contract makes that fact immediately visible to
      ;; every later recovery, including concurrent duplicate deliveries.
      (is (some? (append-continuation!)) "recovery appended parent continuation")
      (is (h/settle-until!
           #(some (fn [event]
                    (and (= :sheet/node-execution-started (:event/type event))
                         (= parent-id (:sheet-id event))
                         (not= (:node-id (:delivery gap)) (:node-id event))))
                  (h/read-all-events ctx))))
      (run! deref (repeatedly 49 #(future (append-continuation!))))
      (let [result (deref (:pending gap) 2500 ::timeout)
            parent-events (h/read-tick-events ctx (:trace-id result))]
        (is (not= ::timeout result))
        (is (= :success (:status result)))
        (is (= "v" (get-in result [:outputs :output])))
        (is (= "ran" (get-in result [:outputs :after])))
        (is (= 1 @contract-child-calls))
        (is (= 1 @contract-downstream-calls))
        (is (= 1 (count (filter #(and (= :sheet/node-execution-completed
                                        (:event/type %))
                                     (:completion-id %))
                               parent-events))))
        (is (= 1 (count (filter #(and (= :sheet/tree-tick-completed
                                        (:event/type %))
                                     (not= :running (:root-status %)))
                               parent-events))))))))

(deftest det-e2e-208-duplicate-and-reordered-terminal-delivery
  (h/with-async-test-context [ctx]
    (reset! contract-child-calls 0)
    (let [child-id (sheet/build-workflow!
                    ctx (sheet/workflow "reordered-terminal-child"
                          (sheet/blackboard {:input :string :output :string})
                          (sheet/code "copy" :fn (fq "contract-child")
                            :reads [:input] :writes [:output])))
          parent-id (sheet/build-workflow!
                     ctx (sheet/workflow "reordered-terminal-parent"
                           (sheet/blackboard {:input :string :output :string})
                           (sheet/delegate "child" :target-sheet-id child-id
                             :reads [:input] :writes [:output])))
          gap (with-redefs [tp/complete-delegate-parent! (fn [& _] nil)]
                (let [pending (future (sheet/execute ctx parent-id {:input "first"}
                                                     :timeout-ms 4000))
                      child-start
                      (loop [deadline (+ (System/currentTimeMillis) 3000)]
                        (or (first (filter #(and (= :sheet/tree-tick-started
                                                   (:event/type %))
                                                (= child-id (:sheet-id %))
                                                (:parent-tick-id %))
                                          (h/read-all-events ctx)))
                            (when (< (System/currentTimeMillis) deadline)
                              (Thread/yield)
                              (recur deadline))))
                      child-terminal
                      (loop [deadline (+ (System/currentTimeMillis) 3000)]
                        (or (first (filter #(and (= :sheet/tree-tick-completed
                                                   (:event/type %))
                                                (= (:tick-id child-start)
                                                   (:tick-id %))
                                                (not= :running (:root-status %)))
                                          (h/read-all-events ctx)))
                            (when (< (System/currentTimeMillis) deadline)
                              (Thread/yield)
                              (recur deadline))))
                      parent-start
                      (first (filter #(and (= :sheet/node-execution-started
                                                (:event/type %))
                                             (= parent-id (:sheet-id %)))
                                     (h/read-all-events ctx)))]
                  (is (some? child-terminal))
                  (is (some? parent-start))
                  (is (= ::waiting (deref pending 50 ::waiting)))
                  {:pending pending
                   :terminal child-terminal
                   :parent-start parent-start}))
          deliver! #(tp/deliver-delegate-child-completion
                     (assoc ctx :event (:terminal gap)))
          observe! #(tp/execute-delegate-node
                     (assoc ctx :event (:parent-start gap)))
          recover! #(runtime/resume-in-progress! ctx)]
      ;; Exercise several orderings concurrently. All three paths converge on
      ;; the child's durable tick identity and the parent's completion CAS.
      (doseq [ordering [[deliver! observe! recover!]
                        [recover! deliver! observe!]
                        [observe! recover! deliver!]]]
        (run! deref (mapv #(future (%)) (apply concat (repeat 20 ordering)))))
      (let [first-result (deref (:pending gap) 2500 ::timeout)
            first-child-id (:tick-id (:terminal gap))
            first-deliveries (delegate-deliveries ctx (:trace-id first-result)
                                                   first-child-id)]
        (is (not= ::timeout first-result))
        (is (= :success (:status first-result)))
        (is (= "first" (get-in first-result [:outputs :output])))
        (is (= 1 (count first-deliveries)))
        (let [second-result (sheet/execute ctx parent-id {:input "second"}
                                           :timeout-ms 3000)
              child-starts (filter #(and (= :sheet/tree-tick-started
                                             (:event/type %))
                                          (= child-id (:sheet-id %))
                                          (:parent-tick-id %))
                                    (h/read-all-events ctx))]
          (is (= :success (:status second-result)))
          (is (= "second" (get-in second-result [:outputs :output])))
          (is (= 2 @contract-child-calls))
          (is (= 2 (count child-starts)))
          (is (= 2 (count (set (map :tick-id child-starts))))))))))
