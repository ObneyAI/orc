(ns ai.obney.orc.gepa.feedback-path-test
  "Event-sourced integration test (NO LLM): prove that a metric returning
   {:score :feedback} threads its RICH feedback all the way through the
   wired GEPA loop — candidate-evaluated carries per-instance :feedbacks, and
   the reflective examples handed to the proposer (built from the parent's
   TRAINING minibatch, with training and validation sets distinct) use that
   rich feedback (not the legacy 'scored 0.XX' string). Uses a trivial
   code-only sheet so the loop runs instantly."
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

(defn fake-judge-metric [input _output]
  {:score 0.0
   :feedback (str "completeness (0.00): the response is the literal word and answers nothing <"
                  (get input "input") ">")})

(deftest real-subsample-result-preserves-iteration-correlation
  (testing "the real evaluation path cannot lose the proposal iteration"
    (let [optimization-id (random-uuid)
          parent-id (random-uuid)
          recorded (atom nil)
          event {:optimization-id optimization-id
                 :parent-id parent-id
                 :proposed-instructions {"responder" "improved"}
                 :component-updated "responder"
                 :subsample-indices [0]
                 :iteration 7}]
      (with-redefs [rm/get-optimization-summary
                    (constantly {:sheet-id (random-uuid)
                                 :config {:skip-perfect-score false}})
                    rm/get-trainset (constantly [{"input" "ALPHA"}])
                    rm/get-candidate
                    (constantly {:instructions {"responder" "original"}})
                    tp/evaluate-candidate-on-subsample
                    (fn [_ _ instructions _ _ _ _]
                      {:scores [(if (= "improved" (get instructions "responder"))
                                  1.0 0.0)]
                       :metric-calls 1})
                    tp/run-command! (fn [_ command] (reset! recorded command))]
        (tp/on-subsample-evaluation-started
          {:event event :gepa/metric-fn (constantly 0.0)})
        (is (= :gepa/record-subsample-result (:command/name @recorded)))
        (is (= 7 (:iteration @recorded))
            "result correlation must survive the real evaluator branch")))))

(deftest rich-feedback-threads-through-wired-loop
  (testing "candidate-evaluated carries :feedbacks and reflection uses the parent's minibatch feedback"
    (tu/with-test-context [ctx]
      (let [cp (control-plane/start {:event-store (:event-store ctx)
                                     :cache (:cache ctx)
                                     :context ctx})
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

        (is (= :completed (:status result)))

        (testing "the seed candidate-evaluated event carries per-instance rich feedback"
          (is (seq evaluated) "at least one candidate was evaluated")
          (is (map? (:feedbacks seed)))
          (is (= 2 (count (:feedbacks seed))) "one feedback per valset instance")
          (is (string/includes? (get (:feedbacks seed) 0) "completeness")))

        (testing "read-model surfaces the candidate feedbacks"
          (is (= (:feedbacks seed)
                 (rm/get-candidate-feedbacks ctx opt-id seed-id))))

        (testing "the reflective examples use the RICH feedback of one TRAINING example, not 'scored 0.XX'"
          (let [feedbacks (map #(get % "Feedback") examples)]
            (is (seq examples) "the proposer was given reflective examples")
            (is (every? #(some #{(get % "Inputs")} trainset) examples)
                "every input is a training example")
            (is (every? #(string/includes? (get % "Feedback")
                                           (str "<" (get-in % ["Inputs" "input"]) ">"))
                        examples)
                "each feedback belongs to its own example")
            (is (every? #(string/includes? % "completeness") feedbacks))
            (is (not-any? #(string/includes? % "scored") feedbacks)
                "NONE fall back to the legacy score-only string")
            (is (not-any? #(string/includes? % "VAL-") feedbacks)
                "no validation example feeds reflection")))

        (control-plane/stop cp)))))
