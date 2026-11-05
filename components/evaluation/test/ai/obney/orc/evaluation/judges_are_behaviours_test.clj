(ns ai.obney.orc.evaluation.judges-are-behaviours-test
  "S6b — every judge is a behaviour tree on one run path (JudgesAreBehaviours,
   JudgeModelResolvesLikeAnyNode, NothingInvented, FeedbackIsOptional,
   ExactTieIsUngradable).

   Driven through the real processor path: a host workflow with a judge
   attached executes, the judge runtime grades its completion, and the result
   is read back from events. Only the provider seam is injected (`llm/predict`
   for a conversational model, a `:decision-transport` for a native decision
   model)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.evaluation.interface]
            [ai.obney.orc.evaluation.core.judge-run :as judge-run]
            [ai.obney.orc.evaluation.core.judge-runtime]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.ontology.interface]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [litellm.router :as router]))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- enable-living-description! [ctx]
  (h/run-and-apply! ctx {:command/name :ontology/set-living-description-enabled
                         :command/id (random-uuid)
                         :command/timestamp (time/now)
                         :enabled? true})
  (Thread/sleep 100))

(defn- host-workflow
  "One llm leaf `classify` with `judges` (name -> config) attached as
   \"the-judge\"."
  [workflow-name judge-config]
  (sheet/workflow workflow-name
    (sheet/blackboard {:ticket-message :string :category :string})
    (sheet/judges {:the-judge judge-config})
    (sheet/llm "classify"
      :instruction "Classify the ticket into one category."
      :reads [:ticket-message]
      :writes [:category]
      :judges ["the-judge"])))

(defn- grading-call? [module]
  (boolean (some #(= :band (:name %)) (:outputs module))))

(def ^:private grounding-outputs
  {:reasoning "The claims trace to the ticket."
   :grounded-claims ["billing error"]
   :ungrounded-claims []
   :band 4
   :feedback "Well grounded in the ticket."})

(defn- fake-llm
  "A conversational provider. The grading call is recognised by the `band`
   field it must write; everything else is the host node's own call."
  [calls {:keys [judge-outputs judge-model] :or {judge-outputs grounding-outputs
                                                 judge-model "fake/judge-model"}}]
  (fn [_provider module inputs options]
    (swap! calls conj {:module module :inputs inputs :options options})
    (if (grading-call? module)
      {:outputs (if (fn? judge-outputs) (judge-outputs) judge-outputs)
       :usage {:prompt_tokens 11 :completion_tokens 7 :total_tokens 18}
       :model judge-model}
      {:outputs {:category "billing"}
       :usage {:prompt_tokens 3 :completion_tokens 1 :total_tokens 4}
       :model "fake/host-model"})))

(defn- score-events [ctx]
  (into [] (es/read (:event-store ctx) {:types #{:judge/score-emitted}
                                        :tenant-id (:tenant-id ctx)})))

(defn- all-events [ctx event-type]
  (into [] (es/read (:event-store ctx) {:types #{event-type}
                                        :tenant-id (:tenant-id ctx)})))

(defn- run-host!
  "Execute the host workflow with `stub` as the provider and wait for the
   judge's score event. Returns the host result, the score event (or nil) and
   the captured provider calls."
  [ctx workflow stub-opts & {:keys [await-score?] :or {await-score? true}}]
  (enable-living-description! ctx)
  (let [calls (atom [])]
    (with-redefs [llm/predict (fake-llm calls stub-opts)]
      (let [sheet-id (sheet/build-workflow! ctx workflow)
            result (sheet/execute ctx sheet-id {:ticket-message "URGENT: billing error on my account."}
                                  :timeout-ms 60000)]
        (when await-score?
          (h/settle-until! #(seq (score-events ctx)) :timeout-ms 20000))
        {:result result
         :score (first (score-events ctx))
         :calls calls}))))

(defn- judge-completions
  "The model-backed node completions that are NOT the host's own tick."
  [ctx host-tick-id]
  (->> (all-events ctx :sheet/node-execution-completed)
       (filter #(and (not= host-tick-id (:tick-id %)) (:model %)))))

;; ---------------------------------------------------------------------------
;; Cycle 2 - a built-in judge runs as a WORKFLOW
;; ---------------------------------------------------------------------------

(deftest built-in-grounding-judge-runs-as-a-workflow
  (h/with-async-test-context [ctx]
    (let [{:keys [result score calls]}
          (run-host! ctx (host-workflow "s6b-cycle2" {:type :grounding}) {})
          grading (first (filter #(grading-call? (:module %)) @calls))
          completions (judge-completions ctx (:trace-id result))]
      (is (= :success (:status result)))
      (is (some? score) "a legacy score event lands for the scored judge")
      (is (= 0.75 (:score score)) "band 4 of 5 maps to (4-1)/(5-1)")
      (is (= "Well grounded in the ticket." (:feedback score)))
      (is (some? grading) "the grading call goes through the node's provider call")
      (testing "the grading request carries the rubric bands and the host evidence"
        (let [rubric (str (get-in grading [:inputs :rubric]))
              outputs (str (get-in grading [:inputs :host-outputs]))
              inputs (str (get-in grading [:inputs :host-inputs]))]
          (is (str/includes? rubric "Fully grounded") rubric)
          (is (str/includes? rubric "Ungrounded / fabricated") rubric)
          (is (str/includes? outputs "billing") outputs)
          (is (str/includes? inputs "URGENT: billing error") inputs)))
      (testing "the judge is a workflow: a judge tick exists with usage and resolved model on its completion"
        (is (= 1 (count completions)) (pr-str completions))
        (is (= "fake/judge-model" (:resolved-model (first completions))))
        (is (= 18 (get-in (first completions) [:usage :total-tokens])))))))

;; ---------------------------------------------------------------------------
;; Cycle 2b - every blackboard key carries a model-facing description
;; ---------------------------------------------------------------------------

(deftest every-judge-key-carries-a-model-facing-description
  (h/with-async-test-context [ctx]
    (let [{:keys [calls]} (run-host! ctx (host-workflow "s6b-cycle2b" {:type :grounding}) {})
          module (:module (first (filter #(grading-call? (:module %)) @calls)))
          described (fn [fields n] (:description (first (filter #(= n (:name %)) fields))))]
      (is (str/includes? (described (:inputs module) :host-instruction) "the assessed node was given"))
      (is (str/includes? (described (:inputs module) :host-inputs) "values the assessed node read")
          "a recursive value schema must not lose its description")
      (is (str/includes? (described (:inputs module) :host-outputs) "values the assessed node wrote"))
      (is (str/includes? (described (:inputs module) :rubric) "grading contract"))
      (is (str/includes? (described (:outputs module) :band) "band number you chose"))
      (is (str/includes? (described (:outputs module) :reasoning) "BEFORE choosing a band")))))

;; ---------------------------------------------------------------------------
;; Cycle 3 - the rubric is data: revising it changes the next grading request
;; ---------------------------------------------------------------------------

(def ^:private strict-bands
  {1 "Wrong: contradicts the ticket"
   2 "Weak: mostly unsupported"
   3 "Fine: supported by the ticket"})

(defn- rubric [feedback]
  {:criterion "Is the category supported by the ticket text?"
   :stance "Be exacting."
   :bands strict-bands
   :feedback feedback})

(deftest revising-the-rubric-changes-the-next-grading-request
  (h/with-async-test-context [ctx]
    (enable-living-description! ctx)
    (let [calls (atom [])
          stub (fake-llm calls {:judge-outputs {:reasoning "r" :grounded-claims [] :ungrounded-claims []
                                                :band 3 :feedback "Supported."}})
          grading (fn [] (filter #(grading-call? (:module %)) @calls))]
      (with-redefs [llm/predict stub]
        (let [sheet-id (sheet/build-workflow!
                        ctx (host-workflow "s6b-cycle3" {:type :grounding :rubric (rubric :required)}))
              run! (fn [] (sheet/execute ctx sheet-id {:ticket-message "billing error"} :timeout-ms 60000))
              score-count #(count (score-events ctx))]
          (is (= :success (:status (run!))))
          (is (h/settle-until! #(= 1 (score-count)) :timeout-ms 20000))
          (let [first-rubric (str (get-in (first (grading)) [:inputs :rubric]))]
            (is (str/includes? first-rubric "Fine: supported by the ticket") first-rubric)
            (is (str/includes? first-rubric "Be exacting.") first-rubric)
            (is (not (str/includes? first-rubric "Fully grounded"))
                "a declared rubric replaces the default bands"))
          (testing "revise the judge (not the host) and run again"
            (let [revised (assoc (rubric :required)
                                 :bands {1 "Nope" 2 "Revised: partly supported" 3 "Revised: fully supported"}
                                 :criterion "Revised criterion text")
                  r (h/run-and-apply! ctx (h/make-revise-judge-command
                                           sheet-id "the-judge" {:type :grounding :rubric revised}))]
              (is (nil? (:cognitect.anomalies/category r)) (pr-str r))
              (is (= :success (:status (run!))))
              (is (h/settle-until! #(= 2 (score-count)) :timeout-ms 20000))
              (let [second-rubric (str (get-in (second (grading)) [:inputs :rubric]))]
                (is (str/includes? second-rubric "Revised: fully supported") second-rubric)
                (is (str/includes? second-rubric "Revised criterion text") second-rubric)
                (is (not (str/includes? second-rubric "Fine: supported by the ticket"))
                    "the next grading request carries only the revised bands")))))))))

;; ---------------------------------------------------------------------------
;; Outcome-level harness: run-judge is the one run path, so the outcome shapes
;; (scored / failed / ungradable) are asserted on it directly.
;; ---------------------------------------------------------------------------

(def ^:private evidence
  {:inputs {:ticket-message "URGENT: billing error on my account."}
   :outputs {:category "billing"}
   :instruction "Classify the ticket into one category."})

(defn- judge! [ctx judge-config & {:keys [stub]}]
  (let [calls (atom [])]
    (with-redefs [llm/predict (or stub (fake-llm calls {}))]
      {:outcome (judge-run/run-judge ctx judge-config evidence)
       :calls calls})))

(defn- feedback-judge! [ctx outputs & {:as judge-config}]
  (let [calls (atom [])]
    (with-redefs [llm/predict (fake-llm calls {:judge-outputs outputs})]
      {:outcome (judge-run/run-judge ctx (merge {:type :grounding} judge-config) evidence)
       :calls calls})))

;; ---------------------------------------------------------------------------
;; Cycle 4 - score-only judge on a native decision model; an exact tie is
;; ungradable
;; ---------------------------------------------------------------------------

(defn- jev-reply [score probabilities]
  {:id "dec-1" :model "typesafe/jev-1.13-20260917" :provider "TypeSafe"
   :answers {"band" {"type" "score" "score" score "probabilities" probabilities
                     "confidence" 0
                     "legend" (zipmap (map str (range)) (map strict-bands (sort (keys strict-bands))))}}
   :usage {"input_tokens" 300 "output_tokens" 17 "cost" 0.00002}})

(defn- register-jev! [reply requests]
  (llm/register-provider!
   :s6b-jev
   {:provider :openrouter :protocol :decision :model "typesafe/jev-1.13"
    :config {:api-key "dummy-not-a-key"
             :decision-transport (fn [request] (swap! requests conj request) reply)}}))

(def ^:private score-only-judge {:type :grounding :model "s6b-jev" :rubric (rubric :none)})

(deftest score-only-judge-scores-by-band-and-carries-no-feedback
  (h/with-async-test-context [ctx]
    (let [requests (atom [])
          _ (register-jev! (jev-reply 1.18 {"0" 0.41 "1" 0.01 "2" 0.58}) requests)
          outcome (judge-run/run-judge ctx score-only-judge evidence)]
      (is (= :scored (:status outcome)) (pr-str outcome))
      (is (= 3 (:band outcome)) "the unique argmax is level 3, not the rounded expected position")
      (is (= 1.0 (:score outcome)) "(3-1)/(3-1)")
      (is (not (contains? outcome :feedback)) "a score-only assessment carries no feedback key")
      (is (= ["typesafe/jev-1.13-20260917"] (mapv :resolved-model (:model-provenance outcome)))
          (pr-str (:model-provenance outcome)))
      (is (= 1 (count @requests)))
      (is (= (mapv strict-bands [1 2 3]) (get-in (first @requests) [:questions "band" :criteria]))
          "the model is asked to score the rubric's described bands in order"))))

(deftest an-exact-tie-is-ungradable-and-never-a-score
  (h/with-async-test-context [ctx]
    (let [requests (atom [])
          _ (register-jev! (jev-reply 1.0 {"0" 0.45 "1" 0.1 "2" 0.45}) requests)
          outcome (judge-run/run-judge ctx score-only-judge evidence)]
      (is (= :ungradable (:status outcome)) (pr-str outcome))
      (is (= :tied-bands (:reason outcome)))
      (is (not-any? #(contains? outcome %) [:score :band :feedback]))
      (is (= 1 (count @requests)) "a tie is the provider's answer: it is not retried"))))

(deftest score-only-and-tied-judges-record-no-legacy-score-event
  (h/with-async-test-context [ctx]
    (enable-living-description! ctx)
    (let [requests (atom [])]
      (register-jev! (jev-reply 1.18 {"0" 0.41 "1" 0.01 "2" 0.58}) requests)
      (let [sheet-id (sheet/build-workflow!
                      ctx (sheet/workflow "s6b-score-only-host"
                            (sheet/blackboard {:ticket-message :string :category :string})
                            (sheet/judges {:the-judge score-only-judge})
                            (sheet/code "classify" :fn "ai.obney.orc.evaluation.judges-are-behaviours-test/classify"
                              :reads [:ticket-message] :writes [:category] :judges ["the-judge"])))
            result (sheet/execute ctx sheet-id {:ticket-message "billing error"} :timeout-ms 60000)]
        (is (= :success (:status result)))
        (is (h/settle-until! #(seq @requests) :timeout-ms 20000) "the judge ran its model call")
        (Thread/sleep 500)
        (is (empty? (score-events ctx))
            "the legacy score event requires feedback, which a score-only judge never invents")))))

(defn classify [_] {:category "billing"})

;; ---------------------------------------------------------------------------
;; Cycle 5 - nothing is invented: out-of-rubric band, blank required feedback,
;; incomplete dimension
;; ---------------------------------------------------------------------------

(deftest a-band-outside-the-rubric-is-an-invalid-result
  (h/with-async-test-context [ctx]
    (let [{:keys [outcome]} (feedback-judge! ctx (assoc grounding-outputs :band 9))]
      (is (= :failed (:status outcome)) (pr-str outcome))
      (is (= :invalid-result (:reason outcome)))
      (is (not-any? #(contains? outcome %) [:score :band])))))

(defn- counting-judge
  "Run a grounding judge whose model answers with `feedbacks` in turn (the last
   repeats); `::absent` omits the field. Returns the outcome and the number of
   grading calls."
  [ctx feedbacks]
  (let [calls (atom 0)
        remaining (atom feedbacks)
        stub (fn [_provider module _inputs _options]
               (swap! calls inc)
               (let [fb (first @remaining)
                     _ (swap! remaining #(if (next %) (next %) %))]
                 {:outputs (if (= ::absent fb)
                             (dissoc grounding-outputs :feedback)
                             (assoc grounding-outputs :feedback fb))
                  :usage {:total-tokens 1} :model "fake/judge-model"}))]
    (with-redefs [llm/predict stub]
      {:outcome (judge-run/run-judge ctx {:type :grounding} evidence)
       :calls calls})))

(deftest blank-feedback-is-a-malformed-answer-that-is-retried
  (h/with-async-test-context [ctx]
    (testing "blank then good: one retry, scored with the good feedback"
      (let [{:keys [outcome calls]} (counting-judge ctx ["" "Cite the ticket."])]
        (is (= 2 @calls))
        (is (= :scored (:status outcome)) (pr-str outcome))
        (is (= "Cite the ticket." (:feedback outcome)))))
    (doseq [[label bad] [["blank" ""] ["whitespace-only" "  \n\t "] ["absent" ::absent]]]
      (testing (str label " every time: 1 + default retries calls, then failed :missing-feedback")
        (let [{:keys [outcome calls]} (counting-judge ctx [bad])]
          (is (= 2 @calls) label)
          (is (= :failed (:status outcome)) (pr-str outcome))
          (is (= :missing-feedback (:reason outcome)) (pr-str outcome))
          (is (not-any? #(contains? outcome %) [:score :band :feedback])))))
    (testing "whitespace then good, absent then good also retry"
      (doseq [bad ["  " ::absent]]
        (let [{:keys [outcome calls]} (counting-judge ctx [bad "Fixed."])]
          (is (= 2 @calls))
          (is (= "Fixed." (:feedback outcome))))))))

(deftest a-judge-whose-feedback-stays-blank-records-no-score-event
  (h/with-async-test-context [ctx]
    (enable-living-description! ctx)
    (let [calls (atom [])]
      ;; the stub must outlive the host's execution: the judge runs after it
      (with-redefs [llm/predict (fake-llm calls {:judge-outputs (assoc grounding-outputs :feedback "   ")})]
        (let [sheet-id (sheet/build-workflow!
                        ctx (host-workflow "s6b-blank-feedback" {:type :grounding}))]
          (sheet/execute ctx sheet-id {:ticket-message "billing error"} :timeout-ms 60000)
          (is (h/settle-until! #(>= (count (filter (fn [c] (grading-call? (:module c))) @calls)) 2)
                               :timeout-ms 20000)
              "the blank answer was retried")
          (Thread/sleep 1500)
          (is (= 2 (count (filter (fn [c] (grading-call? (:module c))) @calls))))
          (is (empty? (score-events ctx))))))))

(def ^:private host-io [:map-of :keyword :string])

(def ^:private dimension-schema
  [:vector [:map
            [:name {:optional true} :string]
            [:score {:optional true} :double]
            [:weight {:optional true} :double]
            [:feedback {:optional true} :string]]])

(defn- custom-judge-id!
  "Build a one-node custom judge workflow whose code node is `fn-name` and which
   writes `writes`. `extra` adds blackboard keys."
  [ctx fn-name writes extra]
  (sheet/build-workflow!
   ctx (sheet/workflow (str "s6b-custom-" fn-name)
         (sheet/blackboard (merge {:host-inputs host-io :host-outputs host-io
                                   :host-instruction :string
                                   :host-trace [:vector [:map [:node-id {:optional true} :uuid]]]
                                   :feedback :string :dimensions dimension-schema}
                                  extra))
         (sheet/code "judge" :fn (str "ai.obney.orc.evaluation.judges-are-behaviours-test/" fn-name)
           :reads [:host-outputs] :writes writes))))

(defn incomplete-dimension-judge [_]
  {:score 0.8 :feedback "fine" :dimensions [{}]})

(deftest an-incomplete-dimension-fails-instead-of-defaulting-to-zero
  (h/with-async-test-context [ctx]
    (let [id (custom-judge-id! ctx "incomplete-dimension-judge" [:score :feedback :dimensions] {:score :double})
          outcome (judge-run/run-judge ctx {:type :custom :eval-sheet-id id} evidence)]
      (is (= :failed (:status outcome)) (pr-str outcome))
      (is (= :invalid-dimensions (:reason outcome)))
      (is (not (contains? outcome :score))))))

(defn missing-score-dimension-judge [_]
  {:score 0.8 :feedback "fine" :dimensions [{:name "tone" :feedback "x"}]})

(defn weightless-dimension-judge [_]
  {:score 0.8 :feedback "fine" :dimensions [{:name "tone" :score 0.6 :feedback "ok"}]})

(deftest a-dimension-missing-its-score-fails-but-a-missing-weight-defaults-to-equal
  (h/with-async-test-context [ctx]
    (let [no-score (custom-judge-id! ctx "missing-score-dimension-judge"
                                     [:score :feedback :dimensions] {:score :double})
          no-weight (custom-judge-id! ctx "weightless-dimension-judge"
                                      [:score :feedback :dimensions] {:score :double})
          bad (judge-run/run-judge ctx {:type :custom :eval-sheet-id no-score} evidence)
          ok (judge-run/run-judge ctx {:type :custom :eval-sheet-id no-weight} evidence)]
      (is (= :invalid-dimensions (:reason bad)) (pr-str bad))
      (is (= :scored (:status ok)) (pr-str ok))
      (is (= [{:name "tone" :weight 1.0 :score 0.6 :feedback "ok"}] (:dimensions ok))))))

;; ---------------------------------------------------------------------------
;; Cycle 6 - a provider finish error inside the judge fails the judge
;; ---------------------------------------------------------------------------

(deftest a-provider-finish-error-fails-the-judge-and-yields-no-score
  (h/with-async-test-context [ctx]
    (with-redefs [router/completion
                  (fn [& _]
                    {:id "gen-finish-error"
                     :model "fake/judge-model"
                     :usage {:prompt_tokens 7 :completion_tokens 0 :total_tokens 7}
                     :choices [{:finish-reason :error
                                :native-finish-reason "MALFORMED_FUNCTION_CALL"
                                :message {:content nil}}]})]
      (let [outcome (judge-run/run-judge ctx {:type :grounding} evidence)]
        (is (= :failed (:status outcome)) (pr-str outcome))
        (is (= :provider-finish-error (:reason outcome)))
        (is (not-any? #(contains? outcome %) [:score :band :feedback]))
        (is (some? (:judge-tick-id outcome)))
        (is (pos? (count (:model-provenance outcome)))
            "the spent call is still accounted for: provenance and usage come from the judge tick")))))

;; ---------------------------------------------------------------------------
;; Cycle 7 - no resolvable provider/model: a failed assessment that says how
;; to configure one
;; ---------------------------------------------------------------------------

(deftest an-unresolvable-model-fails-with-how-to-configure-one
  (h/with-async-test-context [ctx {:context {:llm-provider :s6b-never-registered}}]
    (let [outcome (judge-run/run-judge ctx {:type :grounding} evidence)]
      (is (= :failed (:status outcome)) (pr-str outcome))
      (is (= :judge-model-unresolved (:reason outcome)))
      (is (str/includes? (:message outcome) "Declare a :model on the judge"))
      (is (str/includes? (:message outcome) "configure the runtime"))
      (is (not-any? #(contains? outcome %) [:score :band])))))

(deftest an-unconfigured-provider-is-not-retried
  (h/with-async-test-context [ctx]
    (let [calls (atom 0)]
      (with-redefs [router/completion
                    (fn [& _]
                      (swap! calls inc)
                      (throw (ex-info "Configuration not found"
                                      {:config-name :s6b-nowhere :available []})))]
        (let [outcome (judge-run/run-judge ctx {:type :grounding} evidence)]
          (is (= :judge-model-unresolved (:reason outcome)) (pr-str outcome))
          (is (= 1 @calls) "a deterministic misconfiguration is invoked once, never retried"))))))

;; ---------------------------------------------------------------------------
;; Cycle 8 - the judge's model: declared, else the runtime provider's
;; ---------------------------------------------------------------------------

(deftest a-declared-judge-model-is-the-grading-nodes-model
  (h/with-async-test-context [ctx]
    (let [{:keys [calls outcome]} (feedback-judge! ctx grounding-outputs :model "x/judge-model")]
      (is (= :scored (:status outcome)) (pr-str outcome))
      (is (= "x/judge-model" (get-in (first @calls) [:options :model])))
      (is (= "x/judge-model" (:requested-model (first (:model-provenance outcome))))
          "provenance records the model the node was configured with"))))

(deftest a-judge-with-no-declared-model-carries-none-and-runs-on-the-runtime-provider
  (h/with-async-test-context [ctx]
    (let [{:keys [calls outcome]} (feedback-judge! ctx grounding-outputs)]
      (is (= :scored (:status outcome)) (pr-str outcome))
      (is (not (contains? (:options (first @calls)) :model))
          "the engine carries no built-in judge model")
      (is (= :openrouter (first (map :provider (:model-provenance outcome))))
          "the request went to the runtime's configured provider"))))

;; ---------------------------------------------------------------------------
;; Cycle 9 - custom judges: a legacy numeric score still scores; with a rubric
;; the judge writes a band
;; ---------------------------------------------------------------------------

(defn numeric-score-judge [_]
  {:score 0.6 :feedback "Legacy verdict." :dimensions []})

(defn out-of-range-score-judge [_]
  {:score 1.5 :feedback "Too generous." :dimensions []})

(defn rubric-band-judge [_] {:band 2 :feedback "Weak but present."})

(defn score-only-with-rubric-judge [_] {:score 0.9 :feedback "No band."})

(def ^:private rubric-key-schema
  [:map [:bands [:map-of :int :string]]
   [:criterion {:optional true} :string]
   [:stance {:optional true} :string]])

(deftest a-legacy-custom-judge-writing-a-numeric-score-still-scores
  (h/with-async-test-context [ctx]
    (let [id (custom-judge-id! ctx "numeric-score-judge" [:score :feedback :dimensions] {:score :double})
          outcome (judge-run/run-judge ctx {:type :custom :eval-sheet-id id} evidence)]
      (is (= :scored (:status outcome)) (pr-str outcome))
      (is (= 0.6 (:score outcome)))
      (is (= "Legacy verdict." (:feedback outcome)))
      (is (not (contains? outcome :band)) "no rubric: no band"))
    (let [id (custom-judge-id! ctx "out-of-range-score-judge" [:score :feedback :dimensions] {:score :double})
          outcome (judge-run/run-judge ctx {:type :custom :eval-sheet-id id} evidence)]
      (is (= :failed (:status outcome)) (pr-str outcome))
      (is (= :invalid-result (:reason outcome)) "a score outside [0,1] is never clamped"))))

(deftest a-custom-judge-with-a-rubric-scores-by-the-band-it-writes
  (h/with-async-test-context [ctx]
    (let [id (custom-judge-id! ctx "rubric-band-judge" [:band :feedback]
                               {:band :int :rubric rubric-key-schema})
          outcome (judge-run/run-judge ctx {:type :custom :eval-sheet-id id :rubric (rubric :required)}
                                       evidence)]
      (is (= :scored (:status outcome)) (pr-str outcome))
      (is (= 2 (:band outcome)))
      (is (= 0.5 (:score outcome)) "(2-1)/(3-1)")
      (is (= "Weak but present." (:feedback outcome))))
    (testing "a rubric'd custom judge that writes only a numeric score has nothing to score"
      (let [id (custom-judge-id! ctx "score-only-with-rubric-judge" [:score :feedback]
                                 {:score :double :rubric rubric-key-schema})
            outcome (judge-run/run-judge ctx {:type :custom :eval-sheet-id id :rubric (rubric :required)}
                                         evidence)]
        (is (= :failed (:status outcome)) (pr-str outcome))
        (is (= :invalid-result (:reason outcome)))))))

;; ---------------------------------------------------------------------------
;; Cycle 10 (live finding) - a model that answers in the marker format writes
;; the band as bare text; it must still be read as the integer band
;; ---------------------------------------------------------------------------

(deftest a-band-answered-as-marker-text-is-read-as-the-integer-band
  (h/with-async-test-context [ctx]
    (let [answer (str "[[ ## reasoning ## ]]\nThe claim traces to the ticket.\n"
                      "[[ ## grounded-claims ## ]]\n[\"billing\"]\n"
                      "[[ ## ungrounded-claims ## ]]\n[]\n"
                      "[[ ## band ## ]]\n4\n"
                      "[[ ## feedback ## ]]\nWell grounded in the ticket.\n")]
      (with-redefs [router/completion
                    (fn [& _]
                      {:id "gen-1" :model "fake/marker-model"
                       :usage {:prompt_tokens 5 :completion_tokens 5 :total_tokens 10}
                       :choices [{:finish-reason :stop :message {:content answer}}]})]
        (let [outcome (judge-run/run-judge ctx {:type :grounding} evidence)]
          (is (= :scored (:status outcome)) (pr-str outcome))
          (is (= 4 (:band outcome)) "the band is the integer 4, not the text \"4\"")
          (is (= 0.75 (:score outcome))))))))
