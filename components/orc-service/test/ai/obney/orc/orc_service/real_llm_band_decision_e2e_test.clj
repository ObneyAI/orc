(ns ai.obney.orc.orc-service.real-llm-band-decision-e2e-test
  "REAL-model proof of a banded `sheet/llm-decision` (:bands-from): the same
   rubric on the blackboard is graded by a native decision model (Jev, asked a
   Score over the ordered levels) and by a conversational model. Opt-in through
   ORC_OPENROUTER_E2E_TESTS. Clear-cut evidence only; no accuracy claim."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.complex-e2e-support :as live]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def ^:private chat-model "z-ai/glm-5.3-flash")

(def ^:private rubric
  {:criterion "How well does the evidence support the claim?"
   :bands {1 "The evidence contradicts the claim or says nothing about it"
           2 "The evidence supports part of the claim"
           3 "The evidence fully supports the claim"}})

(defn- workflow [workflow-name & {:as opts}]
  (sheet/workflow workflow-name
    (sheet/blackboard {:claim :string :evidence :string
                       :rubric [:map [:bands [:map-of :int :string]] [:criterion :string]]
                       :band :int})
    (apply sheet/llm-decision "grade"
           (mapcat identity (merge {:instruction "Grade the claim against the evidence using the rubric."
                                    :reads [:claim :evidence :rubric]
                                    :bands-from :rubric :writes [:band]}
                                   opts)))))

(defn- run! [ctx wf inputs]
  (sheet/execute ctx (sheet/build-workflow! ctx wf) inputs :timeout-ms 120000))

(defn- record [ctx result]
  (first (filter #(and (= :sheet/node-execution-completed (:event/type %)) (some? (:decision %)))
                 (h/read-tick-events ctx (:trace-id result)))))

(def ^:private supported
  {:claim "Q3 2025 revenue was 4.1 million dollars."
   :evidence "The filed board report states: Q3 2025 revenue was 4.1 million dollars."
   :rubric rubric})

(def ^:private contradicted
  {:claim "Q3 2025 revenue was 9 million dollars."
   :evidence "The filed board report states: Q3 2025 revenue was 4.1 million dollars."
   :rubric rubric})

(deftest real-jev-band-decision
  (live/with-real-openrouter
    (llm/register-provider! :real-jev-bands
                            {:provider :openrouter :protocol :decision :model "typesafe/jev-1.13"
                             :config {:api-key (System/getenv "OPENROUTER_API_KEY")}})
    (h/with-async-test-context [ctx {:context {:llm-provider :real-jev-bands}}]
      (let [top (run! ctx (workflow "real-jev-band-top") supported)
            bottom (run! ctx (workflow "real-jev-band-bottom") contradicted)
            c (record ctx top)]
        (println :JEV-BAND (pr-str {:top (:outputs top) :bottom (:outputs bottom) :decision (:decision c)}))
        (is (= :success (:status top)) (pr-str (select-keys top [:status :error])))
        (is (= 3 (get-in top [:outputs :band])))
        (is (= 1 (get-in bottom [:outputs :band])))
        (is (map? (get-in c [:decision :band-distribution :probabilities])))
        (is (number? (get-in c [:decision :band-distribution :expected-position])))))))

(deftest real-chat-band-decision
  (live/with-real-openrouter
    (live/register-openrouter-model! chat-model)
    (h/with-async-test-context [ctx {:context {:llm-provider :openrouter}}]
      (testing "a conversational model selects the band; the integer is written"
        (let [top (run! ctx (workflow "real-chat-band-top" :model chat-model) supported)
              bottom (run! ctx (workflow "real-chat-band-bottom" :model chat-model) contradicted)]
          (println :CHAT-BAND (pr-str {:top (:outputs top) :bottom (:outputs bottom)
                                       :decision (:decision (record ctx top))}))
          (is (= :success (:status top)) (pr-str (select-keys top [:status :error])))
          (is (= 3 (get-in top [:outputs :band])))
          (is (= 1 (get-in bottom [:outputs :band]))))))))
