(ns ai.obney.orc.workflow-driver.loop-test
  "Test the multi-turn driver loop logic by mocking the LLM-emit step
   with deterministic emissions. Verifies: convergence-on-success,
   surrender-on-max-turns, prior-attempts feedback accumulation."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.workflow-driver.core.emit-agent :as emit-agent]
            [ai.obney.orc.workflow-driver.interface :as driver]))

(defn echo-fn [{:keys [inputs]}]
  {:analysis {:status :ok :echo (:doc inputs)}})

(defn boom-fn [{:keys [inputs]}]
  (throw (ex-info "boom" {:inputs inputs})))

(defn- bootstrap-with! [ctx fn-fqn]
  (orc/build-workflow! ctx
    (orc/workflow "loop-test-sheet"
      (orc/blackboard {:doc :string :analysis :map})
      (orc/sequence "main"
        (orc/code "process"
          :fn fn-fqn
          :reads [:doc]
          :writes [:analysis])))))

(def ^:private smoke-set
  [{:name "a" :inputs {:doc "alpha"}}
   {:name "b" :inputs {:doc "beta"}}])

(defn- form-with-fn
  "DSL source string referencing the given fn FQN."
  [fn-fqn]
  (str "(workflow \"loop-test-sheet\"
          (blackboard {:doc :string :analysis :map})
          (sequence \"main\"
            (code \"process\"
              :fn \"" fn-fqn "\"
              :reads [:doc]
              :writes [:analysis])))"))

(defn- canned-propose
  "Build a stand-in for propose-tree-via-llm! that returns a fixed
   sequence of workflow forms (one per turn). Each call advances by
   one. Used by `with-redefs` in the tests below."
  [forms]
  (let [counter (atom -1)]
    (fn [_ctx _sheet-id _objective & [_]]
      (let [i (swap! counter inc)
            form (nth forms (min i (dec (count forms))))]
        {:reasoning (str "canned turn " (inc i))
         :workflow-form form
         :usage {:prompt-tokens 10 :completion-tokens 5 :total-tokens 15}
         :model "mock"}))))

(deftest publishes-when-first-turn-passes-eval
  (testing "loop publishes immediately when the proposal makes eval pass"
    (h/with-async-test-context [ctx]
      (let [sheet-id (bootstrap-with! ctx
                       "ai.obney.orc.workflow-driver.loop-test/echo-fn")
            ;; Emit the same correct form back — submit will be idempotent
            propose (canned-propose
                      [(form-with-fn
                         "ai.obney.orc.workflow-driver.loop-test/echo-fn")])]
        (with-redefs [emit-agent/propose-tree-via-llm! propose]
          (let [result (driver/run-driver-loop! ctx
                         {:sheet-id sheet-id
                          :objective "Make sure smoke-set passes."
                          :eval-set smoke-set
                          :max-turns 3
                          :tick-timeout-ms 10000})]
            (is (= :published (:status result)))
            (is (pos-int? (:version-number result)))
            (is (= 1 (count (:turns result))))
            (is (= :publish (-> result :turns first :decision)))
            (is (= 2 (get-in result [:final-eval :pass-count])))))))))

(deftest surrenders-when-eval-keeps-failing
  (testing "loop surrenders after max-turns when eval never passes"
    (h/with-async-test-context [ctx]
      (let [sheet-id (bootstrap-with! ctx
                       "ai.obney.orc.workflow-driver.loop-test/boom-fn")
            ;; Every turn proposes the same broken form
            propose (canned-propose
                      [(form-with-fn
                         "ai.obney.orc.workflow-driver.loop-test/boom-fn")
                       (form-with-fn
                         "ai.obney.orc.workflow-driver.loop-test/boom-fn")])]
        (with-redefs [emit-agent/propose-tree-via-llm! propose]
          (let [result (driver/run-driver-loop! ctx
                         {:sheet-id sheet-id
                          :objective "Will never pass with boom-fn."
                          :eval-set smoke-set
                          :max-turns 2
                          :tick-timeout-ms 10000})]
            (is (= :surrendered (:status result)))
            (is (= :max-turns-without-publishable-draft (:reason result)))
            (is (= 2 (count (:turns result))))
            (is (= 0 (get-in result [:final-eval :pass-count])))))))))

(deftest recovers-after-rejected-turn
  (testing "loop continues after a parse-error turn and publishes when next turn is valid"
    (h/with-async-test-context [ctx]
      (let [sheet-id (bootstrap-with! ctx
                       "ai.obney.orc.workflow-driver.loop-test/echo-fn")
            propose (canned-propose
                      ;; Turn 1: a malformed emission → parse-error
                      ["(workflow \"loop-test-sheet\""
                       ;; Turn 2: valid form
                       (form-with-fn
                         "ai.obney.orc.workflow-driver.loop-test/echo-fn")])]
        (with-redefs [emit-agent/propose-tree-via-llm! propose]
          (let [result (driver/run-driver-loop! ctx
                         {:sheet-id sheet-id
                          :objective "Recover from parse error."
                          :eval-set smoke-set
                          :max-turns 3
                          :tick-timeout-ms 10000})]
            (is (= :published (:status result)))
            (is (= 2 (count (:turns result))))
            ;; Turn 1 should have decision :continue with submit :parse-error
            (is (= :continue (-> result :turns first :decision)))
            (is (= :parse-error (-> result :turns first :submit :status)))
            ;; Turn 2 should have decision :publish
            (is (= :publish (-> result :turns second :decision)))))))))
