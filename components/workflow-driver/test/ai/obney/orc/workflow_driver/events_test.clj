(ns ai.obney.orc.workflow-driver.events-test
  "Verify driver-level events are emitted at every turn point and
   that `replay-session` reconstructs the full optimization arc.
   Plan verification item 5."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.workflow-driver.core.emit-agent :as emit-agent]
            [ai.obney.orc.workflow-driver.interface :as driver]))

(defn pass-fn [{:keys [inputs]}]
  {:analysis {:doc (:doc inputs)}})

(def ^:private fixed-form
  "(workflow \"events-test-sheet\"
     (blackboard {:doc :string :analysis :map})
     (sequence \"main\"
       (code \"p\" :fn \"ai.obney.orc.workflow-driver.events-test/pass-fn\"
         :reads [:doc] :writes [:analysis])))")

(defn- canned-propose [forms]
  (let [counter (atom -1)]
    (fn [_ctx _sheet-id _objective & [_]]
      (let [i (swap! counter inc)]
        {:reasoning (str "canned " (inc i))
         :workflow-form (nth forms (min i (dec (count forms))))
         :usage {:total-tokens 100 :prompt-tokens 70 :completion-tokens 30}
         :model "mock"}))))

(defn- bootstrap! [ctx]
  (orc/build-workflow! ctx
    (orc/workflow "events-test-sheet"
      (orc/blackboard {:doc :string :analysis :map})
      (orc/sequence "main"
        (orc/code "p" :fn "ai.obney.orc.workflow-driver.events-test/pass-fn"
          :reads [:doc] :writes [:analysis])))))

(deftest replay-publish-session-reconstructs-arc
  (testing "successful publish session emits session-started → turn events → session-ended"
    (h/with-async-test-context [ctx]
      (let [sheet-id (bootstrap! ctx)]
        (with-redefs [emit-agent/propose-tree-via-llm! (canned-propose [fixed-form])]
          (let [result (driver/run-driver-loop! ctx
                         {:sheet-id sheet-id
                          :objective "smoke"
                          :eval-set [{:name "a" :inputs {:doc "alpha"}}]
                          :max-turns 2
                          :tick-timeout-ms 10000
                          :description "events smoke"})
                events (driver/replay-session ctx (:session-id result))
                types (mapv :event/type events)]
            (testing "session reaches :published"
              (is (= :published (:status result))))
            (testing "session-id is on the result and on every event"
              (is (uuid? (:session-id result)))
              (is (every? #(= (:session-id result) (:session-id %)) events)))
            (testing "every expected event type appears, in order"
              (is (= [:driver/session-started
                      :driver/turn-began
                      :driver/proposal-emitted
                      :driver/submit-result
                      :driver/eval-completed
                      :driver/turn-decided
                      :driver/session-ended]
                     types)))
            (testing "session-ended carries :published + version-number"
              (let [ended (last events)]
                (is (= :driver/session-ended (:event/type ended)))
                (is (= :published (:status ended)))
                (is (pos-int? (:version-number ended)))
                (is (= 1 (:turns-count ended)))))
            (testing "turn-decided records the publish decision"
              (let [decided (->> events
                                 (filter #(= :driver/turn-decided (:event/type %)))
                                 first)]
                (is (= :publish (:decision decided)))))))))))

(deftest replay-surrender-session-reconstructs-arc
  (testing "surrender session emits per-turn events for every turn + session-ended"
    (h/with-async-test-context [ctx]
      (let [sheet-id (bootstrap! ctx)]
        (with-redefs [emit-agent/propose-tree-via-llm!
                      (canned-propose [fixed-form fixed-form])]
          (let [result (driver/run-driver-loop! ctx
                         {:sheet-id sheet-id
                          :objective "min judge unreachable"
                          :eval-set [{:name "a" :inputs {:doc "alpha"}}]
                          :max-turns 2
                          ;; impossible threshold — no judges → score nil → fails
                          :min-judge-score 0.99
                          :judges [:grounding]
                          :tick-timeout-ms 10000
                          :description "surrender path"})
                events (driver/replay-session ctx (:session-id result))
                turn-decided (filter #(= :driver/turn-decided (:event/type %)) events)]
            (testing "session reaches :surrendered"
              (is (= :surrendered (:status result))))
            (testing "session-ended carries :surrendered"
              (is (= :surrendered (-> events last :status))))
            (testing "turn-decided fires for each turn"
              (is (= 2 (count turn-decided))))))))))
