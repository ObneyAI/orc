(ns ai.obney.orc.gepa.rotation-prompt-budget-test
  "Deterministic integration tests (no model, no network) for three general
   reflection behaviours in specs/gepa.allium:

   - RoundRobinComponents: each candidate rotates through its components, a
     round skipped for a perfect parent does not advance it, and a child
     continues from the furthest rotation among its parents;
   - PersistedReflectionPromptTemplate: one template for every component, or
     one per component, validated at start and used by every proposal;
   - ReflectionBudgetCheckedBeforeEachProposal: a token budget measured from the
     durable proposer-call evidence, plus the provider-reported cost.

   Scenarios seed an optimization through the public commands, drive the real
   handlers, and stub only the model call (`llm/predict`) or the proposer."
  (:require [ai.obney.orc.gepa.core.logic :as logic]
            [clojure.string :as string]
            [clojure.test :refer [deftest testing is]]
            [ai.obney.grain.control-plane.interface :as control-plane]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.grain-test-utils.interface :as tu]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.gepa.interface :as gepa]
            [ai.obney.orc.gepa.core.commands]
            [ai.obney.orc.gepa.core.read-models :as rm]
            [ai.obney.orc.gepa.core.todo-processors :as tp]
            [ai.obney.orc.gepa.interface.schemas]))

;; -----------------------------------------------------------------------------
;; Fixture workflow and metric
;; -----------------------------------------------------------------------------

(defn echo-fn [{:keys [inputs]}]
  {:output (str "OUT:" (get inputs :input))})

(defn- echo-workflow []
  (sheet/workflow "gepa-rotation-budget-test"
    (sheet/blackboard {:input :string :output :string})
    (sheet/code "echo"
      :fn "ai.obney.orc.gepa.rotation-prompt-budget-test/echo-fn"
      :reads [:input]
      :writes [:output])))

(defn- examples [prefix n]
  (mapv (fn [i] {"input" (str prefix "-" i)}) (range n)))

(defn- example-id [input] (get input "input"))

(defn- counting-metric
  "A metric whose feedback names the example it graded. `score-of` maps an
   input id to a score. Every invocation is counted by input id."
  [counts score-of]
  (fn [input _output]
    (swap! counts update (example-id input) (fnil inc 0))
    {:score (score-of (example-id input))
     :feedback (str "FB<" (example-id input) ">")}))

(def ^:private real-on-candidate-evaluated tp/on-candidate-evaluated)
(def ^:private real-on-subsample-evaluation-started tp/on-subsample-evaluation-started)
(def ^:private real-generate tp/generate-mutated-instruction)

(def ^:private requested-var
  #'tp/on-parent-minibatch-evaluation-requested)
(def ^:private real-on-requested (some-> requested-var deref))
(def ^:private open-gate? (atom false))

(defn call-with-gated-requested-handler [test-thread f]
  (reset! open-gate? false)
  (if requested-var
    (with-redefs-fn {requested-var
                     (fn [c] (when (or (identical? test-thread (Thread/currentThread)) @open-gate?)
                               (real-on-requested c)))}
      f)
    (f)))

(defmacro with-loop-context [[ctx] & body]
  `(tu/with-test-context [~ctx]
     (with-redefs [tp/on-optimization-started (constantly nil)
                   tp/on-candidate-created (constantly nil)
                   tp/on-candidate-evaluation-started (constantly nil)
                   tp/on-candidate-evaluated (constantly nil)
                   tp/on-subsample-evaluation-started (constantly nil)
                   tp/on-proposal-ready (constantly nil)
                   tp/on-subsample-evaluated (constantly nil)
                   logic/on-mutation-proposed (constantly nil)
                   logic/on-frontier-updated (constantly nil)
                   tp/on-optimization-completed (constantly nil)
                   logic/on-optimization-failed (constantly nil)
                   tp/generate-mutated-instruction
                   (fn [& _#] (throw (ex-info "the proposer must be stubbed by the scenario" {})))]
       (let [cp# (control-plane/start {:event-store (:event-store ~ctx) :cache (:cache ~ctx)
                                       :context ~ctx})]
         (try (call-with-gated-requested-handler (Thread/currentThread) (fn [] ~@body))
              (finally (control-plane/stop cp#)))))))

;; -----------------------------------------------------------------------------
;; Seeding and observation
;; -----------------------------------------------------------------------------

(def ^:private three-components
  {"alpha" "ALPHA-INSTRUCTION" "beta" "BETA-INSTRUCTION" "gamma" "GAMMA-INSTRUCTION"})

(defn- seed!
  "Start an optimization and create two evaluated candidates: `P`, the frontier
   parent, and `C`, the candidate whose evaluation was just recorded. Both carry
   the same named instruction components."
  [ctx {:keys [trainset valset config instructions evaluate?]
        :or {instructions {"default" "PARENT-INSTRUCTION"} evaluate? true}}]
  (let [sheet-id (sheet/build-workflow! ctx (echo-workflow))
        metric-ref (atom (fn [_ _] 0.0))
        opt-id (random-uuid)
        descriptor (logic/prepare! ctx opt-id {:metric-fn (fn [i o] (@metric-ref i o))})
        opt (get-in (tu/process-command! ctx
                      {:command/name :gepa/start-optimization
                       :optimization-id opt-id :metric-descriptor descriptor
                       :sheet-id sheet-id :trainset trainset :valset valset
                       :inherit-from-previous false
                       :config (merge {:max-metric-calls 10000
                                       :reflection-minibatch-size 3}
                                      config)})
                    [:command/result :optimization-id])
        candidate! (fn [parents]
                     (get-in (tu/process-command! ctx
                               {:command/name :gepa/create-candidate
                                :optimization-id opt :instructions instructions
                                :parent-ids parents :mutation-reason "fixture"})
                             [:command/result :candidate-id]))
        evaluate! (fn [candidate-id]
                    (tu/process-command! ctx
                      {:command/name :gepa/record-evaluation-result
                       :optimization-id opt :candidate-id candidate-id
                       :scores (mapv (constantly 0.0) valset) :trace-ids []
                       :metric-calls 0}))
        parent (candidate! [nil])
        cand (candidate! [nil])]
    (when evaluate?
      (evaluate! parent)
      (evaluate! cand))
    {:optimization-id opt :sheet-id sheet-id :metric-ref metric-ref :parent-id parent
     :candidate-id cand :valset valset :trainset trainset
     :child! (fn [parents] (let [c (candidate! parents)] (evaluate! c) c))}))

(defn- events-of [ctx opt types]
  (into [] (es/read (:event-store ctx)
             {:tenant-id (:tenant-id ctx) :types types
              :tags #{[:optimization opt]}})))

(defn- requested-rounds [ctx opt]
  (events-of ctx opt #{:gepa/parent-minibatch-evaluation-requested}))

(defn- components-requested [ctx opt]
  (mapv :component (requested-rounds ctx opt)))

(defn- recording-proposer [calls]
  (fn [_context _parent-instructions component examples _model
       & [_optimization-id parent-id iteration]]
    (swap! calls conj {:component component :examples examples
                       :parent-id parent-id :iteration iteration})
    "FIXED-PROPOSED-INSTRUCTION"))

(defn- request-round!
  "Deliver the candidate-evaluated event to the real processor, with the
   frontier sampler fixed to the parent. Only the round request is recorded."
  [ctx {:keys [optimization-id parent-id candidate-id valset metric-ref]} metric proposer]
  (reset! metric-ref metric)
  (with-redefs [rm/sample-pareto-frontier (constantly parent-id)
                tp/generate-mutated-instruction proposer
                tp/execute-workflow-with-instructions tp/execute-workflow-with-instructions]
    (real-on-candidate-evaluated
      (assoc ctx :gepa/metric-fn metric
             :event {:optimization-id optimization-id :candidate-id candidate-id
                     :scores (mapv (constantly 0.0) valset) :aggregate-score 0.0}))))

(defn- drive-requested! [ctx opt metric proposer]
  (when real-on-requested
    (with-redefs [tp/generate-mutated-instruction proposer]
      (doseq [e (requested-rounds ctx opt)]
        (real-on-requested (assoc ctx :gepa/metric-fn metric :event e))))))

(defn- run-round!
  ([ctx seeded metric calls] (run-round! ctx seeded metric (recording-proposer calls) nil))
  ([ctx seeded metric proposer _]
   (request-round! ctx seeded metric proposer)
   (drive-requested! ctx (:optimization-id seeded) metric proposer)))

(defn- gate-pending!
  "Run the acceptance gate for every proposal that has no decision yet."
  [ctx {:keys [optimization-id]} metric]
  (let [decided (set (map (juxt :parent-id :iteration)
                          (events-of ctx optimization-id #{:gepa/subsample-evaluated})))]
    (doseq [ready (events-of ctx optimization-id #{:gepa/proposal-ready})
            :when (not (contains? decided [(:parent-id ready) (:iteration ready)]))]
      (real-on-subsample-evaluation-started (assoc ctx :gepa/metric-fn metric :event ready)))))

(defn- full-round!
  "One whole mutation round: request, parent evaluation, proposal, gate. The
   gate's decision advances the iteration so the next round is a new one."
  ([ctx seeded metric calls] (full-round! ctx seeded metric calls {}))
  ([ctx seeded metric calls {:keys [parent proposer]}]
   (let [seeded (cond-> seeded parent (assoc :parent-id parent))]
     (run-round! ctx seeded metric (or proposer (recording-proposer calls)) nil)
     (gate-pending! ctx seeded metric))))

(defn- stub-predict
  "Stand-in for the reflection model call. Records each prompt it receives."
  [prompts usage]
  (fn [_provider _module inputs _options]
    (swap! prompts conj (:prompt inputs))
    {:outputs {:response "```\nNEW INSTRUCTION\n```"}
     :model "stub/model"
     :usage usage}))

(def ^:private hundred-tokens {:prompt_tokens 60 :completion_tokens 40 :total_tokens 100})

(defn- proposer-evidence [ctx opt]
  (events-of ctx opt #{:gepa/proposer-call-completed}))

(defn- tokens-recorded [ctx opt]
  (reduce + (map #(get-in % [:usage :total-tokens] 0) (proposer-evidence ctx opt))))

(defn- record-proposer-tokens!
  "Durable proposer-call evidence for `tokens` tokens, as a completed call."
  [ctx {:keys [optimization-id parent-id]} tokens]
  (tu/process-command! ctx
    {:command/name :gepa/record-proposer-call
     :optimization-id optimization-id :parent-candidate-id parent-id
     :component "alpha" :call-id (random-uuid) :provider :openrouter :model "stub/model"
     :usage {:prompt-tokens 0 :completion-tokens tokens :total-tokens tokens}
     :prompt "p" :response "r" :prompt-sha256 "a" :response-sha256 "b"}))

;; -----------------------------------------------------------------------------
;; Round-robin component rotation
;; -----------------------------------------------------------------------------

(deftest rotation-visits-each-component-in-turn
  (testing "successive rounds of one parent rotate through its components in a stable order"
    (with-loop-context [ctx]
      (let [counts (atom {})
            calls (atom [])
            metric (counting-metric counts (constantly 0.0))
            seeded (seed! ctx {:trainset (examples "TRAIN" 5) :valset (examples "VAL" 4)
                               :instructions three-components})]
        (dotimes [_ 4] (full-round! ctx seeded metric calls))
        (is (= ["alpha" "beta" "gamma" "alpha"] (components-requested ctx (:optimization-id seeded))))
        (is (= ["alpha" "beta" "gamma" "alpha"] (mapv :component @calls))
            "the proposer is asked to rewrite the recorded component")))))

(deftest a-skipped-round-does-not-advance-the-rotation
  (testing "a parent perfect on its minibatch leaves its pointer where it was"
    (with-loop-context [ctx]
      (let [counts (atom {})
            calls (atom [])
            seeded (seed! ctx {:trainset (examples "TRAIN" 5) :valset (examples "VAL" 4)
                               :instructions three-components})]
        (full-round! ctx seeded (counting-metric counts (constantly 1.0)) calls)
        (full-round! ctx seeded (counting-metric counts (constantly 0.0)) calls)
        (full-round! ctx seeded (counting-metric counts (constantly 0.0)) calls)
        (is (= 2 (count @calls)) "the first round was skipped")
        (is (= ["alpha" "alpha" "beta"] (components-requested ctx (:optimization-id seeded))))))))

(deftest a-redelivered-round-keeps-its-component
  (testing "the component belongs to the recorded round, whatever happens on redelivery"
    (with-loop-context [ctx]
      (let [counts (atom {})
            calls (atom [])
            metric (counting-metric counts (constantly 0.0))
            seeded (seed! ctx {:trainset (examples "TRAIN" 5) :valset (examples "VAL" 4)
                               :instructions three-components})]
        (run-round! ctx seeded metric (recording-proposer calls) nil)
        (run-round! ctx seeded metric (recording-proposer calls) nil)
        (is (= ["alpha"] (components-requested ctx (:optimization-id seeded))))
        (is (= ["alpha"] (mapv :component-updated
                               (events-of ctx (:optimization-id seeded) #{:gepa/proposal-ready}))))))))

(deftest a-child-continues-from-its-parents-rotation
  (testing "a new candidate starts at the furthest rotation among its parents"
    (with-loop-context [ctx]
      (let [counts (atom {})
            calls (atom [])
            metric (counting-metric counts (constantly 0.0))
            seeded (seed! ctx {:trainset (examples "TRAIN" 5) :valset (examples "VAL" 4)
                               :instructions three-components})
            {:keys [optimization-id parent-id candidate-id]} seeded]
        (dotimes [_ 2] (full-round! ctx seeded metric calls))
        (let [child ((:child! seeded) [parent-id])]
          (full-round! ctx (assoc seeded :candidate-id child) metric calls {:parent child})
          (full-round! ctx seeded metric calls {:parent candidate-id}))
        (is (= ["alpha" "beta" "gamma" "alpha"] (components-requested ctx optimization-id))
            "the child continues at the parent's pointer; an unrelated candidate starts at the first component")))))

;; -----------------------------------------------------------------------------
;; The reflection prompt template
;; -----------------------------------------------------------------------------

(defn- two-rounds-with-model! [ctx seeded metric prompts usage]
  (binding [logic/*predict-reflection* (stub-predict prompts usage)]
    (dotimes [_ 2] (full-round! ctx seeded metric (atom []) {:proposer real-generate}))))

(deftest a-template-string-reaches-every-components-prompt
  (testing "one template serves every component"
    (with-loop-context [ctx]
      (let [counts (atom {})
            prompts (atom [])
            seeded (seed! ctx {:trainset (examples "TRAIN" 5) :valset (examples "VAL" 4)
                               :instructions three-components
                               :config {:reflection-prompt-template
                                        "STRING-TEMPLATE <curr_param> || <side_info>"}})]
        (two-rounds-with-model! ctx seeded (counting-metric counts (constantly 0.0))
                                prompts hundred-tokens)
        (is (= 2 (count @prompts)))
        (is (every? #(string/starts-with? % "STRING-TEMPLATE") @prompts))
        (is (string/includes? (first @prompts) "ALPHA-INSTRUCTION"))
        (is (string/includes? (second @prompts) "BETA-INSTRUCTION"))))))

(deftest a-template-map-reaches-its-component-only
  (testing "a component without a template uses the default"
    (with-loop-context [ctx]
      (let [counts (atom {})
            prompts (atom [])
            seeded (seed! ctx {:trainset (examples "TRAIN" 5) :valset (examples "VAL" 4)
                               :instructions three-components
                               :config {:reflection-prompt-template
                                        {"beta" "BETA-TEMPLATE <curr_param> || <side_info>"}}})]
        (two-rounds-with-model! ctx seeded (counting-metric counts (constantly 0.0))
                                prompts hundred-tokens)
        (is (string/includes? (first @prompts) "I provided an assistant")
            "alpha has no template and gets the default")
        (is (string/starts-with? (second @prompts) "BETA-TEMPLATE"))))))

(deftest a-template-without-its-placeholders-is-refused-at-start
  (testing "validation happens before any evaluation"
    (with-loop-context [ctx]
      (let [sheet-id (sheet/build-workflow! ctx (echo-workflow))
            start! (fn [template]
                     (tu/process-command! ctx
                       {:command/name :gepa/start-optimization
                        :sheet-id sheet-id :trainset (examples "TRAIN" 3) :valset (examples "VAL" 3)
                        :inherit-from-previous false
                        :config {:reflection-prompt-template template}}))
            started (fn [] (into [] (es/read (:event-store ctx)
                                      {:tenant-id (:tenant-id ctx)
                                       :types #{:gepa/optimization-started}})))]
        (is (= :cognitect.anomalies/incorrect
               (:cognitect.anomalies/category (start! "no placeholders here"))))
        (is (= :cognitect.anomalies/incorrect
               (:cognitect.anomalies/category
                 (start! {"alpha" "ok <curr_param> <side_info>" "beta" "missing <curr_param>"}))))
        (is (empty? (started)) "nothing was recorded for a refused template")
        (is (nil? (:cognitect.anomalies/category (start! "fine <curr_param> <side_info>"))))
        (is (= 1 (count (started))))
        (is (= "fine <curr_param> <side_info>"
               (get-in (first (started)) [:config :reflection-prompt-template]))
            "the template is persisted with the configuration")))))

(deftest the-template-is-used-after-resume
  (testing "a resumed round proposes with the persisted template"
    (with-loop-context [ctx]
      (let [counts (atom {})
            prompts (atom [])
            metric (counting-metric counts (constantly 0.0))
            seeded (seed! ctx {:trainset (examples "TRAIN" 5) :valset (examples "VAL" 4)
                               :instructions three-components
                               :config {:reflection-prompt-template
                                        "RESUMED-TEMPLATE <curr_param> || <side_info>"}})
            real-execute tp/execute-workflow-with-instructions
            done (atom 0)]
        (with-redefs [tp/execute-workflow-with-instructions
                      (fn [& [_ _ _ _ _ call-context :as args]]
                        (if (and (= :subsample-parent (:role call-context)) (>= @done 1))
                          (throw (ex-info "worker died" {:gepa/evaluation-status :recovery-required}))
                          (do (swap! done inc) (apply real-execute args))))]
          (binding [logic/*predict-reflection* (stub-predict prompts hundred-tokens)]
            (run-round! ctx seeded metric (fn [& _] (throw (ex-info "not reached" {}))) nil)))
        (is (empty? @prompts))
        (with-redefs [tp/generate-mutated-instruction real-generate]
          (binding [logic/*predict-reflection* (stub-predict prompts hundred-tokens)]
            (gepa/resume! (assoc ctx :gepa/metric-fn metric) (:optimization-id seeded))))
        (is (= 1 (count @prompts)))
        (is (string/starts-with? (first @prompts) "RESUMED-TEMPLATE"))))))

(deftest the-general-rules-template-is-an-optional-addition
  (testing "it is GEPA's template plus three sentences, and nothing selects it by default"
    (let [tpl logic/general-rules-prompt-template
          exported ai.obney.orc.gepa.interface/general-rules-prompt-template
          sentences (str "The new instruction will be used on many inputs you have not seen. "
                         "Turn what the feedback shows into general rules: describe the kind of input or "
                         "mistake, and do not copy these examples' exact texts, names or answers into the "
                         "instruction. Include domain knowledge the inputs do not provide; leave out "
                         "values that each input already provides, since they can differ from one input "
                         "to the next.")
          base logic/python-gepa-prompt-template
          last-line "Provide the new instructions within ``` blocks."
          expected (string/replace base (str "\n\n" last-line)
                                   (str "\n\n" sentences "\n\n" last-line))]
      (is (some? tpl) "the template is defined in the proposer namespace")
      (is (identical? tpl exported) "and re-exported from the public interface")
      (is (= expected tpl) "GEPA's text unchanged apart from the three sentences before its last line")
      (is (not= base tpl))
      (is (nil? (logic/validate-prompt-template tpl)) "it carries both placeholders")
      (is (= base (string/replace tpl (str sentences "\n\n") ""))))))

(deftest the-general-rules-template-reaches-the-proposer-prompt
  (testing "passed as :reflection-prompt-template, its sentences are in the prompt"
    (with-loop-context [ctx]
      (let [tpl logic/general-rules-prompt-template
            counts (atom {})
            prompts (atom [])
            seeded (seed! ctx {:trainset (examples "TRAIN" 5) :valset (examples "VAL" 4)
                               :instructions three-components
                               :config (cond-> {} tpl (assoc :reflection-prompt-template tpl))})]
        (binding [logic/*predict-reflection* (stub-predict prompts hundred-tokens)]
          (full-round! ctx seeded (counting-metric counts (constantly 0.0)) (atom [])
                       {:proposer real-generate}))
        (is (= 1 (count @prompts)))
        (is (string/includes? (first @prompts)
                              "The new instruction will be used on many inputs you have not seen."))
        (is (string/includes? (first @prompts)
                              "leave out values that each input already provides"))))))

;; -----------------------------------------------------------------------------
;; The reflection token budget and provider cost
;; -----------------------------------------------------------------------------

(deftest no-proposer-call-starts-once-the-budget-is-reached
  (testing "the run completes with the best evaluated candidate"
    (with-loop-context [ctx]
      (let [counts (atom {})
            prompts (atom [])
            metric (counting-metric counts (constantly 0.0))
            seeded (seed! ctx {:trainset (examples "TRAIN" 5) :valset (examples "VAL" 4)
                               :instructions three-components
                               :config {:max-reflection-tokens 150}})
            {:keys [optimization-id]} seeded]
        (binding [logic/*predict-reflection* (stub-predict prompts hundred-tokens)]
          (dotimes [_ 3] (full-round! ctx seeded metric (atom []) {:proposer real-generate})))
        (is (= 2 (count @prompts)) "the third proposer call never started")
        (is (= 200 (tokens-recorded ctx optimization-id)))
        (is (= 2 (count (requested-rounds ctx optimization-id)))
            "no round was opened once the budget was reached")
        (is (= 1 (count (events-of ctx optimization-id #{:gepa/optimization-completed})))
            "the run finished with the best fully evaluated candidate")
        (is (empty? (events-of ctx optimization-id #{:gepa/optimization-failed})))))))

(deftest the-budget-is-checked-again-before-the-proposer-call
  (testing "a round opened under budget does not call the proposer once the budget is spent"
    (with-loop-context [ctx]
      (let [counts (atom {})
            prompts (atom [])
            metric (counting-metric counts (constantly 0.0))
            seeded (seed! ctx {:trainset (examples "TRAIN" 5) :valset (examples "VAL" 4)
                               :instructions three-components
                               :config {:max-reflection-tokens 100}})
            {:keys [optimization-id]} seeded]
        (request-round! ctx seeded metric real-generate)
        (is (= 1 (count (requested-rounds ctx optimization-id))) "opened under budget")
        (record-proposer-tokens! ctx seeded 100)
        (binding [logic/*predict-reflection* (stub-predict prompts hundred-tokens)]
          (drive-requested! ctx optimization-id metric real-generate))
        (is (empty? @prompts) "no model call")
        (is (empty? (events-of ctx optimization-id #{:gepa/proposal-ready})))
        (is (= 1 (count (events-of ctx optimization-id #{:gepa/optimization-completed}))))))))

(deftest the-budget-with-no-evaluated-candidate-fails-and-names-itself
  (testing "failure names the reflection token budget"
    (with-loop-context [ctx]
      (let [counts (atom {})
            metric (counting-metric counts (constantly 0.0))
            seeded (seed! ctx {:trainset (examples "TRAIN" 5) :valset (examples "VAL" 4)
                               :instructions three-components :evaluate? false
                               :config {:max-reflection-tokens 50}})
            {:keys [optimization-id]} seeded]
        (record-proposer-tokens! ctx seeded 100)
        (request-round! ctx seeded metric real-generate)
        (is (empty? (requested-rounds ctx optimization-id)))
        (let [failed (first (events-of ctx optimization-id #{:gepa/optimization-failed}))]
          (is (some? failed))
          (is (string/includes? (str (:error-message failed)) "Reflection token budget")))))))

(deftest a-reused-proposer-call-is-not-counted-again
  (testing "redelivery and resume reuse the recorded call without spending or finishing"
    (with-loop-context [ctx]
      (let [counts (atom {})
            prompts (atom [])
            metric (counting-metric counts (constantly 0.0))
            seeded (seed! ctx {:trainset (examples "TRAIN" 5) :valset (examples "VAL" 4)
                               :instructions three-components
                               :config {:max-reflection-tokens 100}})
            {:keys [optimization-id]} seeded]
        (binding [logic/*predict-reflection* (stub-predict prompts hundred-tokens)]
          (run-round! ctx seeded metric real-generate nil)
          (is (= 1 (count @prompts)))
          (is (= 100 (tokens-recorded ctx optimization-id)))
          (drive-requested! ctx optimization-id metric real-generate))
        (is (= 1 (count @prompts)) "no second model call")
        (is (= 100 (tokens-recorded ctx optimization-id)) "the call is counted once")
        (is (= 1 (count (events-of ctx optimization-id #{:gepa/proposal-ready}))))
        (is (empty? (events-of ctx optimization-id #{:gepa/optimization-completed :gepa/optimization-failed}))
            "reuse does not finish the run")
        (let [resumed (gepa/resume! (assoc ctx :gepa/metric-fn metric) optimization-id)]
          (is (= 100 (:reflection-tokens-used resumed)) "resume reports the tokens used"))))))

(deftest progress-reports-the-reflection-tokens
  (testing "used and budget"
    (with-loop-context [ctx]
      (let [counts (atom {})
            prompts (atom [])
            metric (counting-metric counts (constantly 0.0))
            seeded (seed! ctx {:trainset (examples "TRAIN" 5) :valset (examples "VAL" 4)
                               :instructions three-components
                               :config {:max-reflection-tokens 150}})
            {:keys [optimization-id]} seeded]
        (binding [logic/*predict-reflection* (stub-predict prompts hundred-tokens)]
          (run-round! ctx seeded metric real-generate nil))
        (let [progress (gepa/get-progress ctx optimization-id)
              result (or (:query/result progress) progress)]
          (is (= {:used 100 :budget 150} (:reflection-tokens result))))))))

(deftest a-provider-reported-cost-is-kept-in-the-evidence
  (testing "cost survives into the proposer-call event"
    (with-loop-context [ctx]
      (let [counts (atom {})
            prompts (atom [])
            seeded (seed! ctx {:trainset (examples "TRAIN" 5) :valset (examples "VAL" 4)
                               :instructions three-components})
            {:keys [optimization-id]} seeded]
        (binding [logic/*predict-reflection* (stub-predict prompts (assoc hundred-tokens :cost 0.0123))]
          (run-round! ctx seeded (counting-metric counts (constantly 0.0)) real-generate nil))
        (let [evidence (first (proposer-evidence ctx optimization-id))]
          (is (= 0.0123 (get-in evidence [:usage :cost])))
          (is (= 100 (get-in evidence [:usage :total-tokens]))))))))
