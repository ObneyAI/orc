(ns ai.obney.orc.orc-service.model-decision-bands-test
  "A banded decision (`sheet/llm-decision ... :bands-from`): the model selects
   ONE ordered band of a rubric read from the blackboard at run time, and the
   integer band is written. Specified by ModelDecision (orc-service.allium) and
   DecisionProtocolPrediction (llm.allium): ordered bands are asked as a score,
   the selected band is the uniquely most probable level, an exact tie selects
   no band, and the reported expected position is kept as reported.

   Public DSL -> build -> execute -> events. Only the provider seam is injected:
   `llm/predict` for a conversational model, a `:decision-transport` for a
   native decision model."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def ^:private rubric
  {:criterion "How well are the claims supported?"
   :stance "Be strict."
   :bands {1 "Unsupported facts"
           2 "Partly supported facts"
           3 "All facts supported"}})

(def ^:private rubric-schema
  [:map {:description "A grading rubric: ordered, described bands"}
   [:bands [:map-of :int [:maybe :string]]]
   [:criterion {:optional true} :string]
   [:stance {:optional true} :string]])

(defn- banded-workflow [workflow-name & {:as decision-options}]
  (sheet/workflow workflow-name
    (sheet/blackboard {:evidence :string :rubric rubric-schema :band :int})
    (apply sheet/llm-decision "grade"
           (mapcat identity (merge {:instruction "Grade the evidence against the rubric."
                                    :reads [:evidence :rubric]
                                    :bands-from :rubric
                                    :writes [:band]}
                                   decision-options)))))

(defn- build-outcome [ctx workflow]
  (try (if (uuid? (sheet/build-workflow! ctx workflow)) ::built ::rejected)
       (catch Exception e {:rejected (ex-message e)})))

;; ---------------------------------------------------------------------------
;; Build-time validation
;; ---------------------------------------------------------------------------

(deftest banded-decision-build-time-validation
  (h/with-async-test-context [ctx]
    (testing "a well-formed banded decision builds"
      (is (= ::built (build-outcome ctx (banded-workflow "bands-ok")))))
    (testing ":bands-from must also be declared in :reads"
      (let [r (build-outcome ctx (banded-workflow "bands-not-read" :reads [:evidence]))]
        (is (map? r))
        (is (str/includes? (:rejected r) ":reads"))))
    (testing "the answer key must be an integer schema"
      (let [r (build-outcome ctx (sheet/workflow "bands-string-answer"
                                   (sheet/blackboard {:evidence :string :rubric rubric-schema :band :string})
                                   (sheet/llm-decision "grade"
                                     :instruction "Grade." :reads [:evidence :rubric]
                                     :bands-from :rubric :writes [:band])))]
        (is (map? r))
        (is (str/includes? (:rejected r) "integer"))))
    (testing ":abstain and :min-confidence are rejected on a banded decision"
      (is (map? (build-outcome ctx (banded-workflow "bands-abstain" :abstain 1))))
      (is (map? (build-outcome ctx (banded-workflow "bands-floor" :min-confidence 0.5 :abstain 1))))
      (is (map? (build-outcome ctx (banded-workflow "bands-floor-only" :min-confidence 0.5)))))
    (testing ":bands-from and :options-from are mutually exclusive"
      (is (map? (build-outcome
                 ctx (sheet/workflow "bands-and-options"
                       (sheet/blackboard {:evidence :string :rubric rubric-schema :band :int})
                       (sheet/llm-decision "grade"
                         :instruction "Grade." :reads [:evidence :rubric]
                         :bands-from :rubric :options-from :rubric :writes [:band]))))))))

;; ---------------------------------------------------------------------------
;; A conversational model: the model returns just the band
;; ---------------------------------------------------------------------------

(defn- fake-predict [answers calls]
  (let [remaining (atom answers)]
    (fn [_provider module inputs options]
      (swap! calls conj {:module module :inputs inputs :options options})
      (let [answer (first @remaining)
            _ (swap! remaining #(if (next %) (next %) %))
            output-name (-> module :outputs first :name)
            outputs {output-name answer}]
        (if (:with-metadata? options)
          {:outputs outputs
           :usage {:prompt_tokens 5 :completion_tokens 1 :total_tokens 6}
           :model "resolved/chat-model"
           :raw-response (pr-str outputs)}
          outputs)))))

(defn- run-chat [ctx workflow inputs answers]
  (let [calls (atom [])]
    (with-redefs [llm/predict (fake-predict answers calls)]
      (let [sheet-id (sheet/build-workflow! ctx workflow)]
        {:result (sheet/execute (assoc ctx :llm-provider :deterministic-provider)
                                sheet-id inputs :timeout-ms 20000)
         :calls calls}))))

(def ^:private evidence {:evidence "Revenue was 4.1M per the board report."})

(defn- positions [text strings]
  (mapv #(str/index-of text %) strings))

(deftest chat-model-is-offered-every-band-in-order-and-writes-the-integer
  (h/with-async-test-context [ctx]
    (let [{:keys [result calls]} (run-chat ctx (banded-workflow "bands-chat")
                                           (assoc evidence :rubric rubric) ["2"])
          instructions (:instructions (:module (first @calls)))
          output (-> (first @calls) :module :outputs first)
          offered (pr-str output)]
      (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
      (is (= 2 (get-in result [:outputs :band])) "the integer band is written, not the string")
      (let [at (positions instructions ["1: Unsupported facts" "2: Partly supported facts"
                                        "3: All facts supported"])]
        (is (every? some? at) instructions)
        (is (= at (sort at)) "bands are rendered in order"))
      (is (str/includes? instructions "How well are the claims supported?"))
      (is (str/includes? instructions "Be strict."))
      (is (str/includes? offered "ordered-bands"))
      (is (not (contains? (:inputs (first @calls)) :rubric))
          "the rubric is the offer, not also evidence"))))

(deftest chat-model-band-outside-the-rubric-writes-nothing
  (h/with-async-test-context [ctx]
    (let [{:keys [result]} (run-chat ctx (banded-workflow "bands-chat-unoffered")
                                     (assoc evidence :rubric rubric) ["7"])]
      (is (= :failure (:status result)))
      (is (not (contains? (:outputs result) :band))))))

(deftest invalid-run-time-rubric-fails-with-a-clear-error-and-writes-nothing
  (h/with-async-test-context [ctx]
    (doseq [[label bad] {"blank band" {:bands {1 "Unsupported" 2 "  " 3 "All"}}
                         "single band" {:bands {1 "Only one"}}
                         "non-contiguous" {:bands {1 "a" 3 "c"}}
                         "nil band" {:bands {1 "a" 2 nil}}}]
      (let [{:keys [result calls]} (run-chat ctx (banded-workflow (str "bands-bad-" label))
                                             (assoc evidence :rubric bad) ["1"])]
        (is (= :failure (:status result)) label)
        (is (not (contains? (:outputs result) :band)) label)
        (is (empty? @calls) (str label ": the model is never asked about an invalid rubric"))
        (is (str/includes? (str (:error result)) "rubric") (pr-str (:error result)))))))

(deftest changing-only-the-rubric-changes-the-offered-bands
  (h/with-async-test-context [ctx]
    (let [calls (atom [])]
      (with-redefs [llm/predict (fake-predict ["1"] calls)]
        (let [sheet-id (sheet/build-workflow! ctx (banded-workflow "bands-rubric-swap"))
              run! (fn [rb] (sheet/execute (assoc ctx :llm-provider :deterministic-provider)
                                           sheet-id (assoc evidence :rubric rb) :timeout-ms 20000))
              a (run! {:bands {1 "Cold" 2 "Warm"}})
              b (run! {:bands {1 "Slow" 2 "Medium" 3 "Fast" 4 "Instant"}})
              [call-a call-b] @calls]
          (is (= :success (:status a)))
          (is (= :success (:status b)))
          (is (= 2 (count (-> call-a :module :outputs first :spec (->> (drop 2))))))
          (is (= 4 (count (-> call-b :module :outputs first :spec (->> (drop 2))))))
          (is (str/includes? (:instructions (:module call-a)) "2: Warm"))
          (is (not (str/includes? (:instructions (:module call-a)) "Fast")))
          (is (str/includes? (:instructions (:module call-b)) "4: Instant")))))))

;; ---------------------------------------------------------------------------
;; A native decision model: Score
;; ---------------------------------------------------------------------------

(defn- score-response [score probabilities & {:keys [legend confidence]
                                              :or {legend {"0" "Unsupported facts"
                                                           "1" "Partly supported facts"
                                                           "2" "All facts supported"}
                                                   confidence 0}}]
  {:id "dec-1" :model "typesafe/jev-1.13-20260917" :provider "TypeSafe"
   :answers {"band" (cond-> {"type" "score" "score" score "probabilities" probabilities
                             "confidence" confidence}
                      legend (assoc "legend" legend))}
   :usage {"input_tokens" 300 "output_tokens" 17 "cost" 0.00002}})

(defn- register-jev! [reply requests]
  (llm/register-provider!
   :wf-jev
   {:provider :openrouter :protocol :decision :model "typesafe/jev-1.13"
    :config {:api-key "dummy-not-a-key"
             :decision-transport (fn [request] (swap! requests conj request) reply)}}))

(defn- failure-kind [ctx result]
  (:failure-kind (first (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                      (= :failure (:status %)))
                                (h/read-tick-events ctx (:trace-id result))))))

(defn- run-jev [ctx reply workflow-name & {:as decision-options}]
  (let [requests (atom [])]
    (register-jev! reply requests)
    (let [sheet-id (sheet/build-workflow! ctx (apply banded-workflow workflow-name
                                                     (mapcat identity decision-options)))
          result (sheet/execute ctx sheet-id (assoc evidence :rubric rubric) :timeout-ms 20000)]
      {:result result :requests requests})))

(defn- decision-completion [ctx result]
  (first (filter #(and (= :sheet/node-execution-completed (:event/type %))
                       (some? (:decision %)))
                 (h/read-tick-events ctx (:trace-id result)))))

(deftest decision-model-is-asked-a-score-over-ordered-levels
  (h/with-async-test-context [ctx {:context {:llm-provider :wf-jev}}]
    (let [{:keys [result requests]}
          (run-jev ctx (score-response 1.18 {"0" 0.41 "1" 0.01 "2" 0.58}) "bands-jev")
          question (get-in (first @requests) [:questions "band"])
          record (:decision (decision-completion ctx result))]
      (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
      (is (= "score" (:type question)))
      (is (= ["Unsupported facts" "Partly supported facts" "All facts supported"]
             (:criteria question))
          "levels are an ordered array of the band descriptions")
      (is (= 3 (get-in result [:outputs :band]))
          "the unique argmax index 2 is band 3, NOT the rounded expected position 1")
      (is (= {:probabilities {"0" 0.41 "1" 0.01 "2" 0.58}
              :expected-position 1.18
              :confidence 0}
             (:band-distribution record))
          "the reported distribution, expected position and confidence are kept exactly")
      (is (= [1 2 3] (:offered record)))
      (is (= 3 (:answer record))))))

(deftest exact-tie-for-most-probable-band-selects-no-band
  (h/with-async-test-context [ctx {:context {:llm-provider :wf-jev}}]
    (let [{:keys [result]}
          (run-jev ctx (score-response 1.0 {"0" 0.45 "1" 0.1 "2" 0.45}) "bands-jev-tie")
          completion (decision-completion ctx result)]
      (is (= :failure (:status result)))
      (is (not (contains? (:outputs result) :band)))
      (is (= :undecided (:failure-kind completion)))
      (is (= {:probabilities {"0" 0.45 "1" 0.1 "2" 0.45} :expected-position 1.0 :confidence 0}
             (get-in completion [:decision :band-distribution]))
          "the distribution survives in the decision record"))))

(deftest score-answers-that-contradict-the-question-are-invalid
  (h/with-async-test-context [ctx {:context {:llm-provider :wf-jev}}]
    (testing "a legend that does not match the submitted descriptions"
      (let [{:keys [result]}
            (run-jev ctx (score-response 1.18 {"0" 0.41 "1" 0.01 "2" 0.58}
                                         :legend {"0" "Unsupported facts" "1" "Something else"
                                                  "2" "All facts supported"})
                     "bands-jev-legend")]
        (is (= :failure (:status result)))
        (is (= :schema-validation-failed (failure-kind ctx result)))
        (is (not (contains? (:outputs result) :band)))))
    (testing "a distribution missing a level"
      (let [{:keys [result]}
            (run-jev ctx (score-response 1.0 {"0" 0.4 "2" 0.6}) "bands-jev-missing")]
        (is (= :failure (:status result)))
        (is (= :schema-validation-failed (failure-kind ctx result)))))
    (testing "answered as the wrong primitive"
      (let [{:keys [result]}
            (run-jev ctx {:model "typesafe/jev-1.13-20260917"
                          :answers {"band" {"type" "choice" "choice" "2"
                                            "probabilities" {"1" 0.2 "2" 0.5 "3" 0.3}}}}
                     "bands-jev-wrong-primitive")]
        (is (= :failure (:status result)))))))

(deftest quantized-mass-of-ninety-nine-hundredths-is-accepted
  (h/with-async-test-context [ctx {:context {:llm-provider :wf-jev}}]
    (let [{:keys [result]}
          (run-jev ctx (score-response 1.6 {"0" 0.0 "1" 0.41 "2" 0.58}) "bands-jev-quantized")]
      (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
      (is (= 3 (get-in result [:outputs :band]))))))

(deftest undecided-is-not-retried-but-transport-and-invalid-answers-are
  (h/with-async-test-context [ctx {:context {:llm-provider :wf-jev}}]
    (testing "an exact tie is the provider's answer: one invocation, no retry"
      (let [{:keys [result requests]}
            (run-jev ctx (score-response 1.0 {"0" 0.45 "1" 0.1 "2" 0.45}) "bands-retry-tie")]
        (is (= 1 (count @requests)))
        (is (= :undecided (failure-kind ctx result)))
        (is (= {:probabilities {"0" 0.45 "1" 0.1 "2" 0.45} :expected-position 1.0 :confidence 0}
               (get-in (decision-completion ctx result) [:decision :band-distribution])))))
    (testing "a schema-invalid answer still retries"
      (let [{:keys [result requests]}
            (run-jev ctx (score-response 1.0 {"0" 0.4 "2" 0.6}) "bands-retry-invalid")]
        (is (= 2 (count @requests)))
        (is (= :schema-validation-failed (failure-kind ctx result)))))
    (testing "a transport failure still retries"
      (let [requests (atom [])]
        (register-jev! nil requests)
        (llm/register-provider!
         :wf-jev
         {:provider :openrouter :protocol :decision :model "typesafe/jev-1.13"
          :config {:api-key "dummy-not-a-key"
                   :decision-transport (fn [r] (swap! requests conj r)
                                         (throw (java.io.IOException. "boom")))}})
        (let [sheet-id (sheet/build-workflow! ctx (banded-workflow "bands-retry-transport"))
              result (sheet/execute ctx sheet-id (assoc evidence :rubric rubric) :timeout-ms 20000)]
          (is (= 2 (count @requests)))
          (is (= :transport-failure (failure-kind ctx result))))))))
