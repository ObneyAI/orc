(ns ai.obney.orc.gepa.metric-blackboard-keys-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.orc.gepa.interface :as gepa]
            [ai.obney.orc.gepa.core.metric-runtime :as metric]
            [ai.obney.orc.gepa.core.todo-processors :as todo]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.grain-test-utils.interface :as tu]))

(deftest default-metric-grades-the-answer-not-an-echoed-input
  (let [ctx {:tenant-id (random-uuid)}
        descriptor (metric/prepare! ctx (random-uuid) {})]
    (is (= 1.0 (get-in (metric/invoke-once! ctx descriptor
                         {"expected" "fixed"}
                         (array-map :question "unrelated" :answer "fixed")) [:result :score])))
    (is (= 0.0 (get-in (metric/invoke-once! ctx descriptor
                         {"expected" "fixed"}
                         (array-map :question "fixed" :answer "wrong")) [:result :score])))))

(deftest built-in-factories-accept-canonical-blackboard-keys
  (is (= 1.0 ((gepa/make-exact-match-metric "answer")
              {"answer" "fixed"} {:question "unrelated" :answer "fixed"})))
  (is (= 1.0 ((gepa/make-contains-metric "answer")
              {"answer" "FIXED"} {:question "unrelated" :answer "prefix fixed suffix"}))))

(deftest reflection-model-request-honors-explicit-generation-options
  (let [captured (atom nil)]
    (with-redefs [llm/predict (fn [_ _ _ options]
                              (reset! captured options)
                              {:outputs {:response "A complete instruction"}})]
      (is (= "A complete instruction"
             ((todo/make-llm-fn {:llm-provider :openrouter
                                :gepa/reflection-options {:max-tokens 65536}}
                               "test/model") "Improve the instruction")))
      (is (= 65536 (:max-tokens @captured)))
      (is (true? (:use-function-calling? @captured)))
      (is (= "test/model" (:model @captured))))))

(deftest named-output-presence-prevents-echoed-input-grading
  (is (= 0.0 (todo/default-metric-fn {"expected" "fixed"}
                                    (array-map :question "fixed" :answer nil))))
  (is (= 0.0 (todo/default-metric-fn {"expected" "fixed"}
                                    {:question "fixed" :context "other"})))
  (is (= 0.0 ((gepa/make-exact-match-metric "domain/answer")
              {"domain/answer" "fixed"}
              {"domain/answer" nil :domain/answer "fixed"})))
  (is (= 1.0 ((gepa/make-exact-match-metric "domain/answer")
              {"domain/answer" "fixed"} {:domain/answer "fixed"}))))

(deftest reflection-options-survive-command-events-and-projection
  (tu/with-test-context [ctx]
    (let [options {:max-tokens 65536 :temperature 0.4}
          started (gepa/optimize! ctx {:sheet-id (random-uuid)
                                      :trainset [{"question" "q"}]
                                      :valset [{"question" "q"}]
                                      :config {:reflection-options options}})]
      (is (uuid? (:optimization-id started)))
      (is (= options (get-in (gepa/get-optimization-summary ctx (:optimization-id started))
                             [:config :reflection-options]))))))
