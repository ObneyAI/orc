(ns ai.obney.orc.workflow-driver.bt-node-test
  "Verify the driver-node composes inside a parent workflow: when the
   parent ticks, the embedded driver-node invokes the loop against a
   target sheet and writes the loop's result to the parent's
   blackboard."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.workflow-driver.core.emit-agent :as emit-agent]
            [ai.obney.orc.workflow-driver.interface :as driver]))

(defn pass-fn [{:keys [inputs]}]
  {:analysis {:status :ok :doc (:doc inputs)}})

(defn- target-form
  "Same DSL the canned propose returns — keeps the target structurally
   stable so build-workflow! is a no-op when the loop submits."
  []
  "(workflow \"bt-node-target\"
     (blackboard {:doc :string :analysis :map})
     (sequence \"main\"
       (code \"process\"
         :fn \"ai.obney.orc.workflow-driver.bt-node-test/pass-fn\"
         :reads [:doc]
         :writes [:analysis])))")

(defn- canned-propose
  [forms]
  (let [counter (atom -1)]
    (fn [_ctx _sheet-id _objective & [_]]
      (let [i (swap! counter inc)
            form (nth forms (min i (dec (count forms))))]
        {:reasoning (str "canned turn " (inc i))
         :workflow-form form
         :usage {:prompt-tokens 10 :completion-tokens 5 :total-tokens 15}
         :model "mock"}))))

(deftest driver-node-runs-loop-during-parent-tick
  (testing "embedding driver-node inside a parent workflow drives the target sheet"
    (h/with-async-test-context [ctx]
      ;; 1. Build the TARGET sheet — what the driver will improve
      (let [target-sheet-id
            (orc/build-workflow! ctx
              (orc/workflow "bt-node-target"
                (orc/blackboard {:doc :string :analysis :map})
                (orc/sequence "main"
                  (orc/code "process"
                    :fn "ai.obney.orc.workflow-driver.bt-node-test/pass-fn"
                    :reads [:doc]
                    :writes [:analysis]))))

            ;; 2. Build the PARENT sheet that embeds a driver-node
            parent-sheet-id
            (orc/build-workflow! ctx
              (orc/workflow "bt-node-parent"
                (orc/blackboard {:driver-config :map :driver-result :map})
                (orc/sequence "main"
                  (driver/driver-node "improve-target"))))

            driver-config
            {:sheet-id target-sheet-id
             :objective "Re-emit the target unchanged."
             :eval-set [{:name "a" :inputs {:doc "alpha"}}
                        {:name "b" :inputs {:doc "beta"}}]
             :max-turns 1
             :min-pass-rate 1.0
             :tick-timeout-ms 10000}]

        ;; 3. Tick the PARENT — driver-node fires, runs the loop on TARGET
        (with-redefs [emit-agent/propose-tree-via-llm!
                      (canned-propose [(target-form)])]
          (let [parent-result (orc/execute ctx parent-sheet-id
                                {:driver-config driver-config}
                                :timeout-ms 30000)]
            (testing "parent execution succeeded"
              (is (= :success (:status parent-result))))

            (testing "the embedded driver loop reached :published"
              (let [driver-result (get-in parent-result [:outputs :driver-result])]
                (is (some? driver-result))
                (is (= :published (:status driver-result)))
                (is (pos-int? (:version-number driver-result)))))

            (testing "the target sheet was actually published"
              (is (some? (orc/get-latest-version ctx target-sheet-id))))))))))
