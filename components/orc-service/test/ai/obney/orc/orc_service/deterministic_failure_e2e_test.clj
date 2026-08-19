(ns ai.obney.orc.orc-service.deterministic-failure-e2e-test
  "Deterministic end-to-end coverage for failure and partial-result semantics."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.grain.event-store-v3.interface :as es]))

(defn fail-selected [{:keys [inputs]}]
  (let [item (:item inputs)]
    (if (:fail? item)
      (throw (ex-info "selected item failure" {:id (:id item)}))
      {:item (assoc item :processed? true)})))

(defn fail-every-item [{:keys [inputs]}]
  (throw (ex-info "all items fail" {:item (:item inputs)})))

(defn count-successes [{:keys [inputs]}]
  {:success-count (count (:results inputs))})

(def retry-attempts (atom 0))

(defn succeeds-on-third-attempt [_]
  (let [attempt (swap! retry-attempts inc)]
    (if (< attempt 3)
      (throw (ex-info "transient failure" {:attempt attempt}))
      {:retry-result attempt})))

(defn always-fails-retry [_]
  (let [attempt (swap! retry-attempts inc)]
    (throw (ex-info "persistent failure" {:attempt attempt}))))

(defn slow-result [_]
  (Thread/sleep 300)
  {:slow-result "late"})

(def cancellation-fence-state
  (atom {:started (promise) :release (promise)}))

(defn release-after-cancellation [_]
  (deliver (:started @cancellation-fence-state) true)
  @(:release @cancellation-fence-state)
  {:late-result "must-not-land"})

(def cancellation-state (atom {:started [] :completed []}))

(defn cancellable-item [{:keys [inputs]}]
  (let [item (:item inputs)]
    (swap! cancellation-state update :started conj item)
    (Thread/sleep 400)
    (swap! cancellation-state update :completed conj item)
    {:item item}))

(def downstream-runs (atom 0))

(defn nil-output [_]
  {:required nil})

(defn partial-nil-output [_]
  {:present "value" :missing nil})

(defn downstream-must-not-run [_]
  (swap! downstream-runs inc)
  {:downstream "ran"})

(def duplicate-side-effects (atom 0))

(defn counted-success [_]
  (swap! duplicate-side-effects inc)
  {:duplicate-result "once"})

(defn unserializable-exception [_]
  (throw (ex-info "stable public failure"
                  {:safe :context
                   :unsafe (Thread/currentThread)})))

(defn- fq [function-name]
  (str "ai.obney.orc.orc-service.deterministic-failure-e2e-test/"
       function-name))

(deftest det-e2e-110-terminal-cancellation-fence
  (testing "an in-flight leaf cannot publish after its tick is cancelled"
    (h/with-async-test-context [ctx]
      (reset! cancellation-fence-state
              {:started (promise) :release (promise)})
      (let [workflow (sheet/workflow "det-e2e-110-cancellation-fence"
                       (sheet/blackboard {:late-result :string})
                       (sheet/code "late" :fn (fq "release-after-cancellation")
                         :reads [] :writes [:late-result]))
            sheet-id (sheet/build-workflow! ctx workflow)
            {:keys [tick-id result]} (sheet/execute-stream
                                      ctx sheet-id {} :timeout-ms 5000)]
        (is (true? (deref (:started @cancellation-fence-state) 2000 false)))
        (is (= [tick-id] (:cancelled (sheet/cancel! ctx tick-id))))
        (deliver (:release @cancellation-fence-state) true)
        (is (not= ::timeout (deref result 2000 ::timeout)))
        (is (h/settle-until!
             #(some (fn [event]
                      (= :sheet/tick-cancelled (:event/type event)))
                    (h/read-tick-events ctx tick-id))
             :timeout-ms 2000))
        (Thread/sleep 100)
        (let [events (h/read-tick-events ctx tick-id)
              cancellation-index (first
                                  (keep-indexed
                                   (fn [idx event]
                                     (when (= :sheet/tick-cancelled (:event/type event))
                                       idx))
                                   events))
              after-cancellation (drop (inc cancellation-index) events)]
          (is (not-any? #(contains? #{:sheet/execution-value-written
                                      :sheet/node-execution-completed
                                      :sheet/tree-tick-completed}
                                    (:event/type %))
                        after-cancellation)))))))

(def ^:private item-schema
  [:map
   [:id :int]
   [:fail? {:optional true} :boolean]
   [:processed? {:optional true} :boolean]])

(defn- partial-workflow [name executor & {:keys [with-downstream? parallel]
                                          :or {parallel 3}}]
  (sheet/workflow name
    (sheet/blackboard {:items [:vector item-schema]
                       :item item-schema
                       :results [:vector item-schema]
                       :success-count :int})
    (apply sheet/sequence "main"
           (cond->
            [(sheet/map-each "process"
               :from :items :as :item :into :results :parallel parallel
               (sheet/code "process-one" :fn (fq executor)
                 :reads [:item] :writes [:item]))]
             with-downstream?
             (conj (sheet/code "count-successes" :fn (fq "count-successes")
                     :reads [:results] :writes [:success-count]))))))

(defn- trace-for [ctx result]
  (is (h/settle-until!
       #(some? (get-in (h/run-query ctx {:query/name :sheet/get-trace
                                          :trace-id (:trace-id result)})
                        [:query/result :trace]))))
  (get-in (h/run-query ctx {:query/name :sheet/get-trace
                             :trace-id (:trace-id result)})
          [:query/result :trace]))

(defn- map-completion [ctx result]
  (->> (h/read-tick-events ctx (:trace-id result))
       (filter #(and (= :sheet/node-execution-completed (:event/type %))
                     (= :partial (:status %))))
       first))

(def items
  [{:id 1} {:id 2 :fail? true} {:id 3} {:id 4 :fail? true} {:id 5}])

(deftest det-e2e-021-map-each-partial-result
  (testing "partial map-each status, compact output, and durable failure indices agree"
    (h/with-async-test-context [ctx]
      (let [workflow (partial-workflow "det-e2e-021-map-partial" "fail-selected")
            result (sheet/execute ctx (sheet/build-workflow! ctx workflow) {:items items})
            trace (trace-for ctx result)
            completion (map-completion ctx result)
            summary (:partial-summary completion)]
        (is (= :partial (:status result)))
        (is (= [1 3 5] (mapv :id (get-in result [:outputs :results]))))
        (is (every? :processed? (get-in result [:outputs :results])))
        (is (= :partial (:status trace)))
        (is (some? completion))
        (is (= {:total 5 :succeeded 3 :failed 2 :failure-indices [1 3]}
               (select-keys summary [:total :succeeded :failed :failure-indices])))
        (is (= #{1 3} (set (keys (:failure-reasons summary)))))))))

(deftest det-e2e-022-map-each-all-items-fail
  (testing "all failed iterations produce no false success and preserve every failure"
    (h/with-async-test-context [ctx]
      (let [workflow (partial-workflow "det-e2e-022-map-all-fail" "fail-every-item")
            input [{:id 1} {:id 2} {:id 3}]
            result (sheet/execute ctx (sheet/build-workflow! ctx workflow) {:items input})
            trace (trace-for ctx result)
            events (h/read-tick-events ctx (:trace-id result))
            leaf-failures (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                        (= :failure (:status %))) events)]
        (is (= :failure (:status result)))
        (is (empty? (get-in result [:outputs :results])))
        (is (= :failure (:status trace)))
        (is (>= (count leaf-failures) 3))
        (is (= 3 (count (filter #(= :leaf (:node-type %)) (:node-traces trace)))))
        (is (every? #{:failure}
                    (map :status (filter #(= :leaf (:node-type %))
                                         (:node-traces trace)))))))))

(deftest det-e2e-023-partial-result-feeds-downstream-explicitly
  (testing "a sequence continues after partial map-each while retaining partial status"
    (h/with-async-test-context [ctx]
      (let [workflow (partial-workflow "det-e2e-023-partial-downstream" "fail-selected"
                                       :with-downstream? true)
            result (sheet/execute ctx (sheet/build-workflow! ctx workflow) {:items items})
            trace (trace-for ctx result)
            completion (map-completion ctx result)]
        (is (= :partial (:status result)) "partial cannot masquerade as success")
        (is (= 3 (get-in result [:outputs :success-count]))
            "downstream receives compact successes")
        (is (= [1 3 5] (mapv :id (get-in result [:outputs :results]))))
        (is (= [1 3] (get-in completion [:partial-summary :failure-indices])))
        (is (= :partial (:status trace)))
        (is (some #(and (= :leaf (:node-type %)) (= :success (:status %)))
                  (:node-traces trace)))))))

(deftest det-e2e-024-map-each-iteration-isolation
  (testing "each repeated leaf trace rehydrates only its own input and output"
    (h/with-async-test-context [ctx]
      (let [workflow (partial-workflow "det-e2e-024-map-isolation" "fail-selected"
                                       :parallel 3)
            input [{:id 10} {:id 20} {:id 30}]
            result (sheet/execute ctx (sheet/build-workflow! ctx workflow) {:items input})
            trace (trace-for ctx result)
            leaves (filter #(= :leaf (:node-type %)) (:node-traces trace))
            details (mapv #(get-in
                            (h/run-query ctx {:query/name :sheet/node-trace-detail
                                              :trace-id (:trace-id result)
                                              :trace-instance-id (:trace-instance-id %)})
                            [:query/result]) leaves)]
        (is (= :success (:status result)))
        (is (= 3 (count leaves)))
        (is (= 3 (count (set (map :trace-instance-id leaves)))))
        (is (= #{{:item {:id 10}} {:item {:id 20}} {:item {:id 30}}}
               (set (map :inputs details))))
        (is (= #{{:item {:id 10 :processed? true}}
                 {:item {:id 20 :processed? true}}
                 {:item {:id 30 :processed? true}}}
               (set (map :outputs details))))
        (is (every? #(= 1 (count (:inputs %))) details))
        (is (every? #(= 1 (count (:outputs %))) details))))))

(deftest det-e2e-025-composite-map-each-leaf-failure-safety
  (testing "parallel map-each with a composite leaf must not misattribute successful items"
    (h/with-async-test-context [ctx]
      (let [workflow (sheet/workflow "det-e2e-025-composite-map-leaf"
                       (sheet/blackboard {:items [:vector item-schema]
                                          :item item-schema
                                          :results [:vector item-schema]})
                       (sheet/map-each "process"
                         :from :items :as :item :into :results :parallel 3
                         (sheet/sequence "composite-leaf"
                           (sheet/code "process-one" :fn (fq "fail-selected")
                             :reads [:item] :writes [:item]))))
            result (sheet/execute ctx (sheet/build-workflow! ctx workflow) {:items items})
            output (get-in result [:outputs :results])
            trace (trace-for ctx result)]
        (is (= :partial (:status result)))
        (is (= [1 3 5] (mapv :id output))
            "composite collection must retain the correct successful item identities")
        (is (every? :processed? output)
            "no stale pre-execution item may bleed into the collection")
        (is (= :partial (:status trace)))
        (is (= 5 (count (filter #(= :leaf (:node-type %))
                                (:node-traces trace)))))))))

(deftest det-e2e-026-code-retry-eventually-succeeds
  (testing "a code leaf retries to success and emits one canonical completion"
    (h/with-async-test-context [ctx]
      (reset! retry-attempts 0)
      (let [workflow (sheet/workflow "det-e2e-026-retry-success"
                       (sheet/blackboard {:retry-result :int})
                       (sheet/code "flaky" :fn (fq "succeeds-on-third-attempt")
                         :reads [] :writes [:retry-result]
                         :retry {:max-attempts 5 :backoff-ms [5 10]}))
            result (sheet/execute ctx (sheet/build-workflow! ctx workflow) {})
            trace (trace-for ctx result)
            completions (filter #(= :sheet/node-execution-completed (:event/type %))
                                (h/read-tick-events ctx (:trace-id result)))]
        (is (= :success (:status result)))
        (is (= 3 @retry-attempts))
        (is (= 3 (get-in result [:outputs :retry-result])))
        (is (= :success (:status trace)))
        (is (= 1 (count completions)) "retries do not duplicate node completion")))))

(deftest det-e2e-027-code-retry-exhaustion
  (testing "a persistently failing leaf stops exactly at max-attempts"
    (h/with-async-test-context [ctx]
      (reset! retry-attempts 0)
      (let [workflow (sheet/workflow "det-e2e-027-retry-exhaustion"
                       (sheet/blackboard {:retry-result :int})
                       (sheet/code "always-fails" :fn (fq "always-fails-retry")
                         :reads [] :writes [:retry-result]
                         :retry {:max-attempts 3 :backoff-ms [5 10]}))
            result (sheet/execute ctx (sheet/build-workflow! ctx workflow) {})
            trace (trace-for ctx result)
            completions (filter #(= :sheet/node-execution-completed (:event/type %))
                                (h/read-tick-events ctx (:trace-id result)))]
        (is (= :failure (:status result)))
        (is (= 3 @retry-attempts))
        (is (nil? (get-in result [:outputs :retry-result])))
        (is (= :failure (:status trace)))
        (is (= 1 (count completions)) "exhaustion emits one terminal completion")))))

(defn- build-slow-child! [ctx name]
  (sheet/build-workflow!
   ctx
   (sheet/workflow name
     (sheet/blackboard {:slow-result :string})
     (sheet/code "slow" :fn (fq "slow-result")
       :reads [] :writes [:slow-result]))))

(deftest det-e2e-028-per-node-timeout
  (testing "a delegate node's own timeout bounds its child independently of the tick"
    (h/with-async-test-context [ctx]
      (let [child-id (build-slow-child! ctx "det-e2e-028-slow-child")
            parent (sheet/workflow "det-e2e-028-node-timeout"
                     (sheet/blackboard {:slow-result :string})
                     (sheet/delegate "bounded-child"
                       :target-sheet-id child-id :reads [] :writes [:slow-result]
                       :timeout-ms 35))
            started (System/nanoTime)
            result (sheet/execute ctx (sheet/build-workflow! ctx parent) {}
                                  :timeout-ms 1500)
            elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)
            trace (trace-for ctx result)]
        (is (= :timeout (:status result)))
        (is (= :timeout (:status trace)))
        (is (< elapsed-ms 1000.0))
        (is (nil? (get-in result [:outputs :slow-result])))
        (is (= 1 (count (:child-trace-ids trace))))))))

(deftest det-e2e-029-tick-timeout
  (testing "the whole-tick timeout returns no outputs and leaves durable timeout evidence"
    (h/with-async-test-context [ctx]
      (let [workflow (sheet/workflow "det-e2e-029-tick-timeout"
                       (sheet/blackboard {:slow-result :string})
                       (sheet/code "slow" :fn (fq "slow-result")
                         :reads [] :writes [:slow-result]))
            tick-id (random-uuid)
            result (sheet/execute ctx (sheet/build-workflow! ctx workflow) {}
                                  :tick-id tick-id :timeout-ms 35)
            events (h/read-tick-events ctx tick-id)]
        (is (= :timeout (:status result)))
        (is (not (contains? result :outputs)))
        (is (number? (:duration-ms result)))
        (is (= tick-id (:trace-id result))
            "timeout response must retain its caller-visible trace identity")
        (is (some #(= :sheet/tick-cancelled (:event/type %)) events)
            "exhausting the tick budget must cancel the still-running tick")
        (is (some #(and (= :sheet/execution-traced (:event/type %))
                        (= :timeout (:status %))) events)
            "timeout must be durably queryable")))))

(deftest det-e2e-030-node-versus-tick-timeout-precedence
  (testing "the smaller applicable node/tick budget controls observed termination"
    (h/with-async-test-context [ctx]
      (let [child-id (build-slow-child! ctx "det-e2e-030-slow-child")
            node-first (sheet/workflow "det-e2e-030-node-first"
                         (sheet/blackboard {:slow-result :string})
                         (sheet/delegate "child" :target-sheet-id child-id
                           :reads [] :writes [:slow-result] :timeout-ms 35))
            tick-first (sheet/workflow "det-e2e-030-tick-first"
                         (sheet/blackboard {:slow-result :string})
                         (sheet/delegate "child" :target-sheet-id child-id
                           :reads [] :writes [:slow-result] :timeout-ms 500))
            node-start (System/nanoTime)
            node-result (sheet/execute ctx (sheet/build-workflow! ctx node-first) {}
                                       :timeout-ms 500)
            node-ms (/ (- (System/nanoTime) node-start) 1000000.0)
            tick-start (System/nanoTime)
            tick-result (sheet/execute ctx (sheet/build-workflow! ctx tick-first) {}
                                       :timeout-ms 35)
            tick-ms (/ (- (System/nanoTime) tick-start) 1000000.0)]
        (is (= :timeout (:status node-result)))
        (is (= :timeout (:status tick-result)))
        (is (< node-ms 250.0) "node timeout wins over the longer tick budget")
        (is (< tick-ms 250.0) "tick timeout wins over the longer node budget")
        (is (not (contains? tick-result :outputs)))
        (is (nil? (get-in node-result [:outputs :slow-result])))))))

(deftest det-e2e-031-cancellation-during-map-each
  (testing "cancellation stops scheduling new map iterations and unblocks the caller"
    (h/with-async-test-context [ctx]
      (reset! cancellation-state {:started [] :completed []})
      (let [workflow (sheet/workflow "det-e2e-031-cancel-map"
                       (sheet/blackboard {:items [:vector :int]
                                          :item :int
                                          :results [:vector :int]})
                       (sheet/map-each "work"
                         :from :items :as :item :into :results :parallel 2
                         (sheet/code "slow-item" :fn (fq "cancellable-item")
                           :reads [:item] :writes [:item])))
            sheet-id (sheet/build-workflow! ctx workflow)
            {:keys [tick-id result]} (sheet/execute-stream
                                      ctx sheet-id {:items (vec (range 10))}
                                      :timeout-ms 5000)]
        (is (h/settle-until! #(>= (count (:started @cancellation-state)) 2)
                             :timeout-ms 2000))
        (is (= [tick-id] (:cancelled (sheet/cancel! ctx tick-id))))
        (let [execution-result (deref result 2000 ::timeout)]
          (is (not= ::timeout execution-result))
          (is (= :failure (:status execution-result)))
          (is (true? (:cancelled? execution-result))))
        (Thread/sleep 550)
        (is (<= (count (:started @cancellation-state)) 2)
            "no queued iteration starts after cancellation")
        (is (some #(= :sheet/tick-cancelled (:event/type %))
                  (h/read-tick-events ctx tick-id)))))))

(deftest det-e2e-032-duplicate-completion-defense
  (testing "duplicate child completion delivery cannot complete its parent twice"
    (h/with-async-test-context [ctx]
      (reset! duplicate-side-effects 0)
      (let [workflow (sheet/workflow "det-e2e-032-duplicate-completion"
                       (sheet/blackboard {:duplicate-result :string})
                       (sheet/sequence "main"
                         (sheet/code "counted" :fn (fq "counted-success")
                           :reads [] :writes [:duplicate-result])))
            result (sheet/execute ctx (sheet/build-workflow! ctx workflow) {})
            tick-id (:trace-id result)
            trace (trace-for ctx result)
            child-id (:node-id (first (filter #(= :leaf (:node-type %))
                                              (:node-traces trace))))
            parent-id (:node-id (first (filter #(= :sequence (:node-type %))
                                               (:node-traces trace))))
            initial-events (h/read-tick-events ctx tick-id)
            completions (filter #(= :sheet/node-execution-completed (:event/type %))
                                initial-events)
            child-completion (first (filter #(= child-id (:node-id %)) completions))
            body (dissoc child-completion
                         :event/id :event/type :event/tags :event/timestamp)
            duplicate (es/->event {:type :sheet/node-execution-completed
                                   :tags (:event/tags child-completion)
                                   :body body})]
        (is (= :success (:status result)))
        (is (= 1 @duplicate-side-effects))
        (is (some? child-completion))
        (is (uuid? parent-id))
        (es/append (:event-store ctx)
                   {:tenant-id (:tenant-id ctx) :events [duplicate]})
        (Thread/sleep 300)
        (let [after (h/read-tick-events ctx tick-id)
              parent-summaries (->> after
                                    (filter #(= :sheet/ephemeral-evaluations-recorded
                                                (:event/type %)))
                                    (mapcat :steps)
                                    (filter #(= parent-id (:node-id %))))]
          (is (= 1 (count parent-summaries))
              "ephemeral parent outcome remains canonical under duplicate delivery")
          (is (= 1 @duplicate-side-effects)
              "duplicate completion never re-runs the leaf side effect")
          (is (= tick-id (:trace-id
                          (get-in (h/run-query ctx {:query/name :sheet/get-trace
                                                    :trace-id tick-id})
                                  [:query/result :trace])))
              "one canonical trace remains queryable"))))))

(deftest det-e2e-033-nil-output-fails-without-downstream-execution
  (testing "a declared nil code output fails at the leaf boundary"
    (h/with-async-test-context [ctx]
      (reset! downstream-runs 0)
      (let [workflow (sheet/workflow "det-e2e-033-nil-output"
                       (sheet/blackboard {:required :string :downstream :string})
                       (sheet/sequence "main"
                         (sheet/code "nil" :fn (fq "nil-output")
                           :reads [] :writes [:required])
                         (sheet/code "must-not-run" :fn (fq "downstream-must-not-run")
                           :reads [:required] :writes [:downstream])))
            result (sheet/execute ctx (sheet/build-workflow! ctx workflow) {})
            trace (trace-for ctx result)]
        (is (= :failure (:status result)))
        (is (= 0 @downstream-runs))
        (is (nil? (get-in result [:outputs :required])))
        (is (= :failure (:status trace)))
        (is (= 2 (count (:node-traces trace))) "sequence plus failing leaf")))))

(deftest det-e2e-034-partial-nil-output-names-only-missing-field
  (testing "partial nil output rejects the whole write and identifies only the nil key"
    (h/with-async-test-context [ctx]
      (let [workflow (sheet/workflow "det-e2e-034-partial-nil"
                       (sheet/blackboard {:present :string :missing :string})
                       (sheet/code "partial" :fn (fq "partial-nil-output")
                         :reads [] :writes [:present :missing]))
            result (sheet/execute ctx (sheet/build-workflow! ctx workflow) {})
            events (h/read-tick-events ctx (:trace-id result))
            failed (first (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                        (= :failure (:status %))) events))]
        (is (= :failure (:status result)))
        (is (re-find #":missing" (or (:error failed) "")))
        (is (not (re-find #":present(?:\s|,|\])" (or (:error failed) ""))))
        (is (nil? (get-in result [:outputs :missing])))
        (is (nil? (get-in result [:outputs :present])))))))

(deftest det-e2e-035-exception-sanitization
  (testing "unserializable exception data cannot poison durable failure handling"
    (h/with-async-test-context [ctx]
      (let [workflow (sheet/workflow "det-e2e-035-exception-sanitization"
                       (sheet/blackboard {:result :string})
                       (sheet/code "throws" :fn (fq "unserializable-exception")
                         :reads [] :writes [:result]))
            result (sheet/execute ctx (sheet/build-workflow! ctx workflow) {})
            trace (trace-for ctx result)
            events (h/read-tick-events ctx (:trace-id result))]
        (is (= :failure (:status result)))
        (is (= :failure (:status trace)))
        (is (re-find #"stable public failure" (or (:error result) "")))
        (is (seq events) "failure remains durably queryable")
        (is (every? #(not (instance? Thread %)) (tree-seq coll? seq events)))
        (is (every? #(try (pr-str %) true (catch Throwable _ false)) events))))))
