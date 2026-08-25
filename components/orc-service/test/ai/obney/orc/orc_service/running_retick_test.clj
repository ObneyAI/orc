(ns ai.obney.orc.orc-service.running-retick-test
  "Tests for :running status re-tick behavior.
   Verifies that conditions with :on-fail :running cause the tree to
   re-tick from root, enabling agent loops in behavior trees."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Test executor: increments a counter on each tick
;; =============================================================================

(def tick-counter (atom 0))
(def downstream-counter (atom 0))
(def delegated-inputs (atom []))

(defn increment-counter
  "Code executor that increments a counter each time it's called."
  [{:keys [_inputs]}]
  (let [n (swap! tick-counter inc)]
    {:counter (str n)}))

(defn prepare-delegated-work [_]
  (let [n (swap! tick-counter inc)]
    {:counter (str n)
     :delegate-input ({1 "A" 2 "B" 3 "C"} n)}))

(defn complete-delegated-work [{:keys [inputs]}]
  (let [delegate-input (:delegate-input inputs)]
    (swap! delegated-inputs conj delegate-input)
    {:delegated-result delegate-input}))

(defn complete-downstream-work [_]
  (swap! downstream-counter inc)
  {:final-result "advanced"})

(defn- node-detail [ctx trace-id trace-instance-id]
  (:query/result
   (h/run-query ctx {:query/name :sheet/node-trace-detail
                     :trace-id trace-id
                     :trace-instance-id trace-instance-id})))

;; =============================================================================
;; Tests
;; =============================================================================

(deftest later-parent-iterations-start-distinct-durable-delegate-invocations
  (testing "three visits deliver current inputs and resume only their matching parent invocation"
    (h/with-async-test-context [ctx]
      (reset! tick-counter 0)
      (reset! downstream-counter 0)
      (reset! delegated-inputs [])
      (let [child (sheet/workflow "retick-after-delegate-child"
                    (sheet/blackboard {:delegate-input :string
                                       :delegated-result :string})
                    (sheet/code "delegated-work"
                      :fn "ai.obney.orc.orc-service.running-retick-test/complete-delegated-work"
                      :reads [:delegate-input]
                      :writes [:delegated-result]))
            child-id (sheet/build-workflow! ctx child)
            parent (sheet/workflow "retick-after-delegate-parent"
                     (sheet/blackboard {:counter :string
                                        :delegate-input :string
                                        :delegated-result :string
                                        :final-result :string})
                     (sheet/sequence "root"
                       (sheet/code "prepare-delegate-input"
                         :fn "ai.obney.orc.orc-service.running-retick-test/prepare-delegated-work"
                         :writes [:counter :delegate-input])
                       (sheet/delegate "successful-delegate"
                         :target-sheet-id child-id
                         :reads [:delegate-input]
                         :writes [:delegated-result])
                       (sheet/condition "continue-after-delegate"
                         :check {:key :counter :op :equals :value "3"
                                 :on-fail :running})
                       (sheet/code "next-tick-work"
                         :fn "ai.obney.orc.orc-service.running-retick-test/complete-downstream-work"
                         :writes [:final-result])))
            parent-id (sheet/build-workflow! ctx parent)
            result (sheet/execute ctx parent-id {} :timeout-ms 5000 :max-ticks 4)
            trace (loop [attempt 0]
                    (or (rm/get-trace ctx (:trace-id result))
                        (when (< attempt 200)
                          (Thread/sleep 10)
                          (recur (inc attempt)))))
            events (h/read-tick-events ctx (:trace-id result))
            child-starts (filter #(and (= :sheet/tree-tick-started (:event/type %))
                                       (= (:trace-id result) (:parent-tick-id %)))
                                 (h/read-all-events ctx))
            delegate-traces (filter #(= "successful-delegate" (:node-name %))
                                    (:node-traces trace))
            condition-traces (filter #(= "continue-after-delegate" (:node-name %))
                                     (:node-traces trace))
            child-ids (mapv :tick-id child-starts)]
        (is (= :success (:status result)))
        (is (= 3 @tick-counter))
        (is (= ["A" "B" "C"] @delegated-inputs))
        (is (= 1 @downstream-counter))
        (is (= "C" (get-in result [:outputs :delegated-result])))
        (is (= "advanced" (get-in result [:outputs :final-result])))
        (is (= 3 (count (filter #(= :sheet/tree-tick-started (:event/type %)) events))))
        (is (= 3 (count child-starts)))
        (is (= 3 (count (set child-ids))))
        (is (= :success (:status trace)))
        (is (= 3 (count delegate-traces)))
        (is (= #{1 2 3}
               (set (map #(or (get (:exec-context %) ::tp/tick-iteration)
                              1)
                         delegate-traces))))
        (is (= #{{:delegate-input "A"}
                 {:delegate-input "B"}
                 {:delegate-input "C"}}
               (set (map #(-> (node-detail ctx (:trace-id result)
                                           (:trace-instance-id %))
                              :inputs)
                         delegate-traces))))
        (is (= #{{:delegated-result "A"}
                 {:delegated-result "B"}
                 {:delegated-result "C"}}
               (set (map #(-> (node-detail ctx (:trace-id result)
                                           (:trace-instance-id %))
                              :outputs)
                         delegate-traces))))
        (doseq [child-id child-ids]
          (let [child-result (runtime/durable-terminal-result ctx child-id)]
            (tp/complete-delegate-parent! ctx child-id child-result)
            (tp/complete-delegate-parent! ctx child-id child-result)))
        (let [deliveries (filter #(and (= :sheet/node-execution-completed
                                          (:event/type %))
                                       (contains? (set child-ids) (:completion-id %)))
                                 (h/read-all-events ctx))]
          (is (= 3 (count deliveries))
              "duplicate delivery retains exactly one parent completion per invocation"))
        (is (= 3 (count condition-traces))
            "the sibling after the delegate executes once per invocation")
        (is (= #{:running :success} (set (map :status condition-traces))))
        (is (some #(and (= "next-tick-work" (:node-name %))
                        (= :success (:status %)))
                  (:node-traces trace)))))))

(deftest running-condition-causes-retick
  (testing "a condition with :on-fail :running causes the tree to re-tick multiple times"
    (h/with-async-test-context [ctx]
      (reset! tick-counter 0)
      (let [wf (sheet/workflow "retick-test"
                 (sheet/blackboard
                   {:counter [:string {:description "tick count"}]})
                 (sheet/sequence "main"
                   (sheet/code "increment"
                     :fn "ai.obney.orc.orc-service.running-retick-test/increment-counter"
                     :reads []
                     :writes [:counter])
                   (sheet/condition "keep-going"
                     :check {:key :counter :op :equals :value "__never__" :on-fail :running})))
            sheet-id (sheet/build-workflow! ctx wf)
            ;; Execute with a short timeout — the tree will re-tick until max iterations
            _ (alter-var-root #'tp/*max-tick-iterations* (constantly 5))
            result (sheet/execute ctx sheet-id {} :timeout-ms 10000)]
        ;; The tree should have ticked 5 times (max iterations)
        ;; and the final status should be :failure (max iterations exhausted)
        (is (= 5 @tick-counter)
            "Tree should re-tick exactly max-tick-iterations times")
        (is (= :failure (:status result))
            "Should fail after exhausting max iterations")))))

(deftest running-condition-terminates-on-success
  (testing "tree re-ticks via :running until a condition passes, then succeeds"
    (h/with-async-test-context [ctx]
      (reset! tick-counter 0)
      (let [wf (sheet/workflow "retick-terminate-test"
                 (sheet/blackboard
                   {:counter [:string {:description "tick count"}]})
                 ;; Increment, then check: if counter != "3" → :running (re-tick).
                 ;; When counter = "3" → condition fails normally → but we need success...
                 ;; Use a fallback: keep-going (not at 3 yet) vs done (at 3)
                 (sheet/fallback "agent"
                   (sheet/sequence "keep-going"
                     (sheet/condition "not-at-3"
                       :check {:key :counter :op :not-equals :value "3"})
                     (sheet/code "increment"
                       :fn "ai.obney.orc.orc-service.running-retick-test/increment-counter"
                       :reads []
                       :writes [:counter])
                     (sheet/condition "re-tick"
                       :check {:key :counter :op :equals :value "__never__" :on-fail :running}))
                   (sheet/sequence "done"
                     (sheet/condition "at-3"
                       :check {:key :counter :op :equals :value "3"}))))
            sheet-id (sheet/build-workflow! ctx wf)
            _ (alter-var-root #'tp/*max-tick-iterations* (constantly 10))
            result (sheet/execute ctx sheet-id {:counter "0"} :timeout-ms 10000)]
        ;; Tick 1: not-at-3 passes ("0"!="3"), increment → "1", re-tick → :running
        ;; Tick 2: not-at-3 passes ("1"!="3"), increment → "2", re-tick → :running
        ;; Tick 3: not-at-3 passes ("2"!="3"), increment → "3", re-tick → :running
        ;; Tick 4: not-at-3 fails ("3"=="3") → fallback tries done → at-3 passes → :success
        (is (= 3 @tick-counter)
            "Increment should run 3 times")
        (is (= :success (:status result))
            "Should succeed when done skill matches")
        (is (= "3" (get-in result [:outputs :counter]))
            "Output should reflect final counter value")))))

(deftest running-in-fallback-reticks
  (testing "a :running condition inside a fallback child re-ticks properly"
    (h/with-async-test-context [ctx]
      (reset! tick-counter 0)
      (let [wf (sheet/workflow "fallback-retick-test"
                 (sheet/blackboard
                   {:counter [:string {:description "tick count"}]
                    :result [:string {:description "output"}]})
                 (sheet/fallback "agent"
                   ;; Skill 1: runs for first 2 ticks (counter < 3)
                   (sheet/sequence "keep-ticking"
                     (sheet/condition "not-done?"
                       :check {:key :counter :op :not-equals :value "3"})
                     (sheet/code "increment"
                       :fn "ai.obney.orc.orc-service.running-retick-test/increment-counter"
                       :reads []
                       :writes [:counter])
                     ;; Always return :running to re-tick
                     (sheet/condition "re-tick"
                       :check {:key :counter :op :equals :value "__never__" :on-fail :running}))
                   ;; Skill 2: runs when counter = "3" (not-done? fails, fallback tries this)
                   (sheet/sequence "done"
                     (sheet/condition "is-done?"
                       :check {:key :counter :op :equals :value "3"})
                     (sheet/code "finish"
                       :fn "ai.obney.orc.orc-service.running-retick-test/increment-counter"
                       :reads []
                       :writes [:counter]))))
            sheet-id (sheet/build-workflow! ctx wf)
            _ (alter-var-root #'tp/*max-tick-iterations* (constantly 10))
            result (sheet/execute ctx sheet-id {:counter "0"} :timeout-ms 10000)]
        ;; Ticks 1-2: keep-ticking increments counter (1, 2), re-tick fires
        ;; Tick 3: not-done? fails (counter="3"?... wait, counter is "2" after 2 increments,
        ;;         then on tick 3 not-done? checks counter != "3" → "2" != "3" → true,
        ;;         so keep-ticking runs again, counter becomes "3", re-tick fires
        ;; Tick 4: not-done? checks "3" != "3" → false → sequence fails → fallback tries done
        ;;         is-done? checks "3" = "3" → true → finish runs → success
        (is (= :success (:status result))
            "Fallback should eventually succeed via the done skill")
        (is (>= @tick-counter 4)
            "Should take at least 4 ticks")))))

(deftest per-execute-max-ticks-option
  (testing ":max-ticks option on execute overrides the global dynamic var"
    (h/with-async-test-context [ctx]
      (reset! tick-counter 0)
      ;; Set a high global value to prove the per-execute option wins
      (alter-var-root #'tp/*max-tick-iterations* (constantly 50))
      (let [wf (sheet/workflow "per-execute-cap-test"
                 (sheet/blackboard
                   {:counter [:string {:description "tick count"}]})
                 (sheet/sequence "main"
                   (sheet/code "increment"
                     :fn "ai.obney.orc.orc-service.running-retick-test/increment-counter"
                     :reads []
                     :writes [:counter])
                   (sheet/condition "keep-going"
                     :check {:key :counter :op :equals :value "__never__" :on-fail :running})))
            sheet-id (sheet/build-workflow! ctx wf)
            result (sheet/execute ctx sheet-id {} :timeout-ms 10000 :max-ticks 3)]
        (is (= 3 @tick-counter)
            "Tree should re-tick exactly :max-ticks times, not the global 50")
        (is (= :failure (:status result))
            "Should fail after exhausting :max-ticks")))))
