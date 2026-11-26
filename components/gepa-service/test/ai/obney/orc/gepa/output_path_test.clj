(ns ai.obney.orc.gepa.output-path-test
  "Event-sourced integration test (NO LLM): prove that the per-instance
   generated OUTPUT threads all the way through the wired GEPA loop —
   candidate-evaluated carries per-instance :outputs, the population read-model
   surfaces them via get-candidate-outputs, and the reflective examples handed to
   the proposer put the REAL output of the parent's TRAINING example (not {}, not
   a validation output) into the 'Generated Outputs' slot. Training and
   validation sets are distinct. Mirrors feedback_path_test. Uses a trivial code-only sheet so the
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

(defn fake-judge-metric [_input _output]
  {:score 0.0
   :feedback "completeness (0.00): the response is the literal word and answers nothing"})

(deftest generated-output-threads-through-wired-loop
  (testing "candidate-evaluated carries :outputs and reflection shows the training example's output"
    (tu/with-test-context [ctx]
      (let [cp (control-plane/start {:event-store (:event-store ctx)
                                     :cache (:cache ctx)
                                     :context ctx
                                     :heartbeat-interval-ms 100
                                     :staleness-threshold-ms 1000})
            sheet-id (sheet/build-workflow! ctx (make-echo-workflow))
            trainset [{"input" "TRAIN-banana"} {"input" "TRAIN-mango"} {"input" "TRAIN-plum"}]
            valset [{"input" "VAL-kiwi"} {"input" "VAL-fig"}]
            proposed (atom [])
            result (with-redefs [tp/generate-mutated-instruction
                                 (fn [_ _ _ examples & _]
                                   (swap! proposed conj examples)
                                   "FIXED-INSTRUCTION")]
                     (gepa/optimize! ctx
                       {:sheet-id sheet-id
                        :trainset trainset
                        :valset valset
                        :metric-fn fake-judge-metric
                        :config {:max-metric-calls 10
                                 :reflection-lm "us.anthropic.claude-sonnet-4-20250514-v1:0"}
                        :inherit-from-previous false
                        :block? true
                        :timeout-ms 60000}))
            opt-id (:optimization-id result)
            all (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)}))
            evaluated (filter #(and (= :gepa/candidate-evaluated (:event/type %))
                                    (= opt-id (:optimization-id %))) all)
            seed (first (sort-by (comp str :evaluated-at) evaluated))
            seed-id (:candidate-id seed)
            examples (mapcat identity @proposed)]

        (is (= :completed (:status result))
            (str "code-only GEPA timed out; durable event inventory: "
                 (pr-str (frequencies (map :event/type all)))))

        (testing "the seed candidate-evaluated event carries per-instance outputs"
          (is (seq evaluated) "at least one candidate was evaluated")
          (is (map? (:outputs seed)))
          (is (= 2 (count (:outputs seed))) "one output per valset instance")
          (let [out0 (get (:outputs seed) 0)]
            (is (map? out0) "each per-instance output is the output blackboard map")
            (is (some #(and (string? %) (string/includes? % "ECHO:VAL-"))
                      (vals out0))
                "the real generated output text is present")))

        (testing "read-model surfaces the candidate outputs"
          (is (= (:outputs seed)
                 (rm/get-candidate-outputs ctx opt-id seed-id))))

        (testing "reflective examples carry the REAL output of their own training example"
          (let [gen-outputs (map #(get % "Generated Outputs") examples)]
            (is (seq examples) "the proposer was given reflective examples")
            (is (every? map? gen-outputs))
            (is (not-any? empty? gen-outputs) "NONE fall back to the empty {} placeholder")
            (is (every? #(= (str "ECHO:" (get-in % ["Inputs" "input"]))
                            (:output (get % "Generated Outputs")))
                        examples)
                "each output is the echo of its own training input")
            (is (not-any? #(string/includes? (pr-str %) "VAL-") examples)
                "no validation value reaches reflection")))

        (control-plane/stop cp)))))
