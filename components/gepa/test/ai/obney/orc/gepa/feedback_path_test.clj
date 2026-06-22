(ns ai.obney.orc.gepa.feedback-path-test
  "Event-sourced integration test (NO LLM): prove that a metric returning
   {:score :feedback} threads its RICH feedback all the way through the
   wired GEPA loop — candidate-evaluated carries per-instance :feedbacks, and
   build-reflective-dataset uses that rich feedback (not the legacy 'scored
   0.XX' string). Uses a trivial code-only sheet so the loop runs instantly."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as string]
            [ai.obney.orc.grain-test-utils.interface :as tu]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.gepa.interface :as gepa]
            [ai.obney.orc.gepa.interface.schemas]
            [ai.obney.orc.gepa.core.read-models :as rm]
            [ai.obney.orc.gepa.core.todo-processors :as tp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.control-plane.interface :as control-plane]))

(defn echo-fn [{:keys [inputs]}]
  {:output (str (get inputs :input "default"))})

(defn make-echo-workflow []
  (sheet/workflow "gepa-feedback-path-test"
    (sheet/blackboard {:input :string :output :string})
    (sheet/code "echo"
      :fn "ai.obney.orc.gepa.feedback-path-test/echo-fn"
      :reads [:input]
      :writes [:output])))

;; A fake "judge-style" metric: low score + RICH feedback, like make-judge-metric.
(defn fake-judge-metric [_input _output]
  {:score 0.0
   :feedback "completeness (0.00): the response is the literal word and answers nothing"})

(deftest rich-feedback-threads-through-wired-loop
  (testing "candidate-evaluated carries :feedbacks and reflective dataset uses them"
    (tu/with-test-context [ctx]
      (let [cp (control-plane/start {:event-store (:event-store ctx)
                                     :cache (:cache ctx)
                                     :context ctx})
            sheet-id (sheet/build-workflow! ctx (make-echo-workflow))
            data [{"input" "banana"} {"input" "kiwi"}]
            result (gepa/optimize! ctx
                     {:sheet-id sheet-id
                      :trainset data
                      :valset data
                      :metric-fn fake-judge-metric
                      :config {:max-metric-calls 4
                               :reflection-lm "us.anthropic.claude-sonnet-4-20250514-v1:0"}
                      :inherit-from-previous false
                      :block? true
                      :timeout-ms 60000})
            opt-id (:optimization-id result)
            all (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)}))
            evaluated (filter #(and (= :gepa/candidate-evaluated (:event/type %))
                                    (= opt-id (:optimization-id %))) all)
            seed (first (sort-by (comp str :evaluated-at) evaluated))
            seed-id (:candidate-id seed)]

        (testing "the seed candidate-evaluated event carries per-instance rich feedback"
          (is (seq evaluated) "at least one candidate was evaluated")
          (is (map? (:feedbacks seed)))
          (is (= 2 (count (:feedbacks seed))) "one feedback per valset instance")
          (is (string/includes? (get (:feedbacks seed) 0) "completeness")))

        (testing "read-model surfaces the candidate feedbacks"
          (is (= (:feedbacks seed)
                 (rm/get-candidate-feedbacks ctx opt-id seed-id))))

        (testing "build-reflective-dataset uses the RICH feedback, not 'scored 0.XX'"
          (let [refl (tp/build-reflective-dataset ctx opt-id seed-id
                                                  {:reflection-minibatch-size 3})
                feedbacks (map #(get % "Feedback") refl)]
            (is (seq refl) "produced reflective examples from the failing instances")
            (is (every? #(string/includes? % "completeness") feedbacks)
                "every reflective example carries the judges' rich feedback")
            (is (not-any? #(string/includes? % "scored") feedbacks)
                "NONE fall back to the legacy score-only string")))

        (control-plane/stop cp)))))
