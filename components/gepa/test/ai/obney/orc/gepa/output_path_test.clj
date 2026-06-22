(ns ai.obney.orc.gepa.output-path-test
  "Event-sourced integration test (NO LLM): prove that the per-instance
   generated OUTPUT threads all the way through the wired GEPA loop —
   candidate-evaluated carries per-instance :outputs, the population read-model
   surfaces them via get-candidate-outputs, and build-reflective-dataset puts the
   REAL output (not {}) into the 'Generated Outputs' slot of each reflective
   example. Mirrors feedback_path_test. Uses a trivial code-only sheet so the
   loop runs instantly."
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
  {:output (str "ECHO:" (get inputs :input "default"))})

(defn make-echo-workflow []
  (sheet/workflow "gepa-output-path-test"
    (sheet/blackboard {:input :string :output :string})
    (sheet/code "echo"
      :fn "ai.obney.orc.gepa.output-path-test/echo-fn"
      :reads [:input]
      :writes [:output])))

;; A fake "judge-style" metric: low score + RICH feedback, like make-judge-metric.
(defn fake-judge-metric [_input _output]
  {:score 0.0
   :feedback "completeness (0.00): the response is the literal word and answers nothing"})

(deftest generated-output-threads-through-wired-loop
  (testing "candidate-evaluated carries :outputs and reflective dataset uses them"
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

        (testing "the seed candidate-evaluated event carries per-instance outputs"
          (is (seq evaluated) "at least one candidate was evaluated")
          (is (map? (:outputs seed)))
          (is (= 2 (count (:outputs seed))) "one output per valset instance")
          ;; The echo workflow writes :output "ECHO:<input>" onto the blackboard.
          (let [out0 (get (:outputs seed) 0)]
            (is (map? out0) "each per-instance output is the output blackboard map")
            (is (some #(and (string? %) (string/includes? % "ECHO:"))
                      (vals out0))
                "the real generated output text is present")))

        (testing "read-model surfaces the candidate outputs"
          (is (= (:outputs seed)
                 (rm/get-candidate-outputs ctx opt-id seed-id))))

        (testing "build-reflective-dataset puts the REAL output (not {}) into Generated Outputs"
          (let [refl (tp/build-reflective-dataset ctx opt-id seed-id
                                                  {:reflection-minibatch-size 3})
                gen-outputs (map #(get % "Generated Outputs") refl)]
            (is (seq refl) "produced reflective examples from the failing instances")
            (is (every? map? gen-outputs)
                "every reflective example's Generated Outputs is the real output map")
            (is (not-any? empty? gen-outputs)
                "NONE fall back to the empty {} output placeholder")
            (is (some #(string/includes? % "ECHO:")
                      (mapcat (comp (partial map str) vals) gen-outputs))
                "the reflective examples carry the real generated output text")))

        (control-plane/stop cp)))))
