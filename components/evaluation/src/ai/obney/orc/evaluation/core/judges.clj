(ns ai.obney.orc.evaluation.core.judges
  "Judge implementations for evaluating LLM node executions.

   Judges are functions that take trace data and return evaluation results.
   They can be used directly or wrapped in ORC sheets for full observability.

   Each judge function follows the code executor pattern:
   (fn [{:keys [inputs]}] outputs-map)

   CONFIGURATION:
   Set *use-mock-llm* to true for testing without LLM calls.
   Set *judge-provider* to configure which LLM provider to use.
   Set *judge-model* to configure which model to use."
  (:require [ai.obney.orc.evaluation.core.feedback :as feedback]
            [ai.obney.orc.evaluation.core.rubrics :as rubrics]
            [cheshire.core :as json]
            [clojure.string :as str]
            [dscloj.core :as dscloj]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:dynamic *use-mock-llm*
  "Set to true to use mock LLM responses for testing.
   Default: false (use real LLM calls)."
  false)

(def ^:dynamic *judge-provider*
  "Provider keyword for LLM calls (e.g., :openrouter, :anthropic).
   Default: :openrouter"
  :openrouter)

(def ^:dynamic *judge-model*
  "Model to use for judge LLM calls.
   Default: google/gemini-2.5-flash (fast and cost-effective for evaluation)"
  "google/gemini-2.5-flash")

;; =============================================================================
;; Judge Result Schemas (for LLM output)
;; =============================================================================

(def GroundingResultSchema
  "Malli schema for grounding judge output"
  [:map
   [:score :double]
   [:grounded-claims [:vector :string]]
   [:ungrounded-claims [:vector :string]]
   [:feedback :string]])

(def InstructionFollowingResultSchema
  "Malli schema for instruction following judge output"
  [:map
   [:score :double]
   [:requirements-met [:vector :string]]
   [:requirements-missed [:vector :string]]
   [:feedback :string]])

(def ReasoningResultSchema
  "Malli schema for reasoning quality judge output"
  [:map
   [:score :double]
   [:reasoning-strengths [:vector :string]]
   [:reasoning-weaknesses [:vector :string]]
   [:feedback :string]])

(def CompletenessResultSchema
  "Malli schema for completeness judge output"
  [:map
   [:score :double]
   [:aspects-covered [:vector :string]]
   [:aspects-missing [:vector :string]]
   [:feedback :string]])

;; =============================================================================
;; Prompt Rendering
;; =============================================================================

(defn render-rubric-prompt
  "Render a rubric prompt with trace data.

   Args:
     rubric: A rubric map with :prompt template
     trace-data: Map with :inputs, :outputs (or :response), :instruction

   Returns:
     Rendered prompt string"
  [rubric trace-data]
  (let [inputs-json (json/generate-string (or (:inputs trace-data) {}) {:pretty true})
        response (cond
                   (:response trace-data) (:response trace-data)
                   (string? (:outputs trace-data)) (:outputs trace-data)
                   (:outputs trace-data) (json/generate-string (:outputs trace-data) {:pretty true})
                   :else "{}")
        instruction (or (:instruction trace-data) "No instruction provided")]
    (-> (:prompt rubric)
        (str/replace "{inputs}" inputs-json)
        (str/replace "{response}" (str response))
        (str/replace "{instruction}" instruction))))

;; =============================================================================
;; LLM Calling Infrastructure
;; =============================================================================

(defn- build-judge-module
  "Build a DSCloj module for a judge evaluation.

   Args:
     prompt: The rendered rubric prompt
     output-fields: Vector of output field definitions

   Returns:
     DSCloj module map"
  [prompt output-fields]
  {:inputs [{:name :evaluation_request
             :spec :string
             :description "The evaluation request with context and scoring criteria"}]
   :outputs output-fields
   :instructions prompt})

(defn- grounding-output-fields []
  [{:name :score
    :spec :double
    :description "Score from 0.0 to 1.0"}
   {:name :grounded-claims
    :spec [:vector :string]
    :description "List of claims that ARE supported by inputs"}
   {:name :ungrounded-claims
    :spec [:vector :string]
    :description "List of claims that are NOT supported by inputs"}
   {:name :feedback
    :spec :string
    :description "Specific actionable feedback explaining the score"}])

(defn- instruction-following-output-fields []
  [{:name :score
    :spec :double
    :description "Score from 0.0 to 1.0"}
   {:name :requirements-met
    :spec [:vector :string]
    :description "List of instruction requirements that were satisfied"}
   {:name :requirements-missed
    :spec [:vector :string]
    :description "List of instruction requirements that were NOT satisfied"}
   {:name :feedback
    :spec :string
    :description "Specific actionable feedback explaining the score"}])

(defn- reasoning-output-fields []
  [{:name :score
    :spec :double
    :description "Score from 0.0 to 1.0"}
   {:name :reasoning-strengths
    :spec [:vector :string]
    :description "Aspects of reasoning that were good"}
   {:name :reasoning-weaknesses
    :spec [:vector :string]
    :description "Logical gaps or unclear elements"}
   {:name :feedback
    :spec :string
    :description "Specific actionable feedback for improving reasoning"}])

(defn- completeness-output-fields []
  [{:name :score
    :spec :double
    :description "Score from 0.0 to 1.0"}
   {:name :aspects-covered
    :spec [:vector :string]
    :description "Aspects of the task that were addressed"}
   {:name :aspects-missing
    :spec [:vector :string]
    :description "Aspects that should have been included but weren't"}
   {:name :feedback
    :spec :string
    :description "Specific actionable feedback for improving completeness"}])

(defn call-llm-judge
  "Call an LLM to evaluate using the given prompt and parse the response.

   Args:
     prompt: The rendered rubric prompt
     output-fields: Vector of DSCloj output field definitions
     options: Optional map with :provider, :model

   Returns:
     Map with parsed output fields (e.g., {:score 0.8 :feedback \"...\" ...})"
  [prompt output-fields & {:keys [provider model] :or {provider *judge-provider*
                                                        model *judge-model*}}]
  (let [module (build-judge-module prompt output-fields)
        ;; DSCloj needs inputs as a keyword map
        inputs {:evaluation_request "Please evaluate according to the rubric above."}
        ;; Make the LLM call — :model rides through dscloj into the
        ;; litellm router as a per-request override, so the judge model
        ;; actually applies instead of the provider registration's default.
        result (dscloj/predict provider module inputs
                               {:with-metadata? false
                                :validate? false
                                :model model})]
    ;; Result is already a map of keyword -> value
    (or (:outputs result) result)))

;; =============================================================================
;; Mock LLM Responses (for testing)
;; =============================================================================

(defn- mock-grounding-result [prompt]
  {:score 0.8
   :grounded-claims ["Mock: Response uses information from provided context"]
   :ungrounded-claims []
   :feedback (str "Mock evaluation - prompt length: " (count prompt) " chars. "
                  "In production, this would analyze actual grounding.")})

(defn- mock-instruction-following-result [prompt]
  {:score 0.85
   :requirements-met ["Mock: Main task addressed"]
   :requirements-missed []
   :feedback (str "Mock evaluation - prompt length: " (count prompt) " chars. "
                  "In production, this would analyze instruction compliance.")})

(defn- mock-reasoning-result [prompt]
  {:score 0.75
   :reasoning-strengths ["Mock: Clear logical structure"]
   :reasoning-weaknesses []
   :feedback (str "Mock evaluation - prompt length: " (count prompt) " chars. "
                  "In production, this would analyze reasoning quality.")})

(defn- mock-completeness-result [prompt]
  {:score 0.9
   :aspects-covered ["Mock: Main aspects addressed"]
   :aspects-missing []
   :feedback (str "Mock evaluation - prompt length: " (count prompt) " chars. "
                  "In production, this would analyze completeness.")})

;; =============================================================================
;; Judge Functions (Code Executor Pattern)
;; =============================================================================

(defn grounding-judge
  "Evaluate if response is grounded in provided context.

   This is a code executor function for use in sheet/code nodes.

   Input keys:
     trace-data: Map with :inputs, :outputs/:response, :instruction

   Output keys:
     grounding-result: Map with :score, :grounded-claims, :ungrounded-claims, :feedback"
  [{:keys [inputs] :as _executor-context}]
  (let [trace-data (:trace-data inputs)
        rubric (rubrics/get-rubric :grounding)
        prompt (render-rubric-prompt rubric trace-data)
        result (if *use-mock-llm*
                 (mock-grounding-result prompt)
                 (call-llm-judge prompt (grounding-output-fields)))]
    {:grounding-result result}))

(defn instruction-following-judge
  "Evaluate if the LLM followed its instruction.

   Input keys:
     trace-data: Map with :inputs, :outputs/:response, :instruction

   Output keys:
     instruction-result: Map with :score, :requirements-met, :requirements-missed, :feedback"
  [{:keys [inputs] :as _executor-context}]
  (let [trace-data (:trace-data inputs)
        rubric (rubrics/get-rubric :instruction-following)
        prompt (render-rubric-prompt rubric trace-data)
        result (if *use-mock-llm*
                 (mock-instruction-following-result prompt)
                 (call-llm-judge prompt (instruction-following-output-fields)))]
    {:instruction-result result}))

(defn reasoning-judge
  "Evaluate the quality of reasoning in the response.

   Input keys:
     trace-data: Map with :inputs, :outputs/:response, :instruction

   Output keys:
     reasoning-result: Map with :score, :reasoning-strengths, :reasoning-weaknesses, :feedback"
  [{:keys [inputs] :as _executor-context}]
  (let [trace-data (:trace-data inputs)
        rubric (rubrics/get-rubric :reasoning)
        prompt (render-rubric-prompt rubric trace-data)
        result (if *use-mock-llm*
                 (mock-reasoning-result prompt)
                 (call-llm-judge prompt (reasoning-output-fields)))]
    {:reasoning-result result}))

(defn completeness-judge
  "Evaluate the completeness of the response.

   Input keys:
     trace-data: Map with :inputs, :outputs/:response, :instruction

   Output keys:
     completeness-result: Map with :score, :aspects-covered, :aspects-missing, :feedback"
  [{:keys [inputs] :as _executor-context}]
  (let [trace-data (:trace-data inputs)
        rubric (rubrics/get-rubric :completeness)
        prompt (render-rubric-prompt rubric trace-data)
        result (if *use-mock-llm*
                 (mock-completeness-result prompt)
                 (call-llm-judge prompt (completeness-output-fields)))]
    {:completeness-result result}))

;; =============================================================================
;; Aggregation Executor
;; =============================================================================

(defn aggregate-dimensions
  "Aggregate multiple dimension results into a single score.

   Input keys:
     grounding-result: Result from grounding judge
     instruction-result: Result from instruction following judge
     reasoning-result: Result from reasoning judge
     completeness-result: Result from completeness judge

   Output keys:
     aggregate-score: Float between 0.0 and 1.0
     feedback-summary: Combined feedback string
     dimensions: Vector of dimension details"
  [{:keys [inputs] :as _executor-context}]
  (let [grounding (:grounding-result inputs)
        instruction (:instruction-result inputs)
        reasoning (:reasoning-result inputs)
        completeness (:completeness-result inputs)

        dimensions [(feedback/->metric-dimension
                     "Source Grounding" 0.35
                     (or (:score grounding) 0.5)
                     (or (:feedback grounding) "No feedback"))
                    (feedback/->metric-dimension
                     "Instruction Following" 0.25
                     (or (:score instruction) 0.5)
                     (or (:feedback instruction) "No feedback"))
                    (feedback/->metric-dimension
                     "Reasoning Quality" 0.20
                     (or (:score reasoning) 0.5)
                     (or (:feedback reasoning) "No feedback"))
                    (feedback/->metric-dimension
                     "Completeness" 0.20
                     (or (:score completeness) 0.5)
                     (or (:feedback completeness) "No feedback"))]

        result (feedback/combine-dimension-scores dimensions)]

    {:aggregate-score (:score result)
     :feedback-summary (feedback/aggregate-feedback-summary result)
     :dimensions (mapv #(select-keys % [:name :weight :score :feedback])
                       dimensions)}))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn evaluate-single
  "Evaluate a trace with a single judge.

   Convenience wrapper that formats inputs correctly.

   Args:
     judge-key: One of :grounding, :instruction-following, :reasoning, :completeness
     trace-data: Map with :inputs, :outputs/:response, :instruction

   Returns:
     The judge result map"
  [judge-key trace-data]
  (let [judge-fn (case judge-key
                   :grounding grounding-judge
                   :instruction-following instruction-following-judge
                   :reasoning reasoning-judge
                   :completeness completeness-judge)]
    (judge-fn {:inputs {:trace-data trace-data}})))

(defn evaluate-all
  "Evaluate a trace with all judges and aggregate.

   Args:
     trace-data: Map with :inputs, :outputs/:response, :instruction

   Returns:
     Map with:
       :aggregate-score - Float 0.0-1.0
       :feedback-summary - Combined feedback
       :dimensions - Per-dimension results
       :raw-results - Individual judge results"
  [trace-data]
  (let [grounding-res (grounding-judge {:inputs {:trace-data trace-data}})
        instruction-res (instruction-following-judge {:inputs {:trace-data trace-data}})
        reasoning-res (reasoning-judge {:inputs {:trace-data trace-data}})
        completeness-res (completeness-judge {:inputs {:trace-data trace-data}})

        ;; Combine all results for aggregation
        combined-inputs (merge grounding-res instruction-res reasoning-res completeness-res)

        agg-result (aggregate-dimensions {:inputs combined-inputs})]

    {:aggregate-score (:aggregate-score agg-result)
     :feedback-summary (:feedback-summary agg-result)
     :dimensions (:dimensions agg-result)
     :raw-results {:grounding (:grounding-result grounding-res)
                   :instruction-following (:instruction-result instruction-res)
                   :reasoning (:reasoning-result reasoning-res)
                   :completeness (:completeness-result completeness-res)}}))

;; =============================================================================
;; Judge Registry
;; =============================================================================

(def judge-registry
  "Registry of available judge executors."
  {:grounding #'grounding-judge
   :instruction-following #'instruction-following-judge
   :reasoning #'reasoning-judge
   :completeness #'completeness-judge
   :aggregate #'aggregate-dimensions})

(defn get-judge
  "Get a judge executor by key."
  [key]
  (get judge-registry key))

;; =============================================================================
;; Configuration Helpers
;; =============================================================================

(defmacro with-mock-llm
  "Execute body with mock LLM responses (for testing)."
  [& body]
  `(binding [*use-mock-llm* true]
     ~@body))

(defmacro with-judge-config
  "Execute body with custom judge configuration.

   Example:
     (with-judge-config {:provider :anthropic :model \"claude-3-haiku-20240307\"}
       (evaluate-all trace-data))"
  [{:keys [provider model mock?]} & body]
  `(binding [*judge-provider* (or ~provider *judge-provider*)
             *judge-model* (or ~model *judge-model*)
             *use-mock-llm* (or ~mock? *use-mock-llm*)]
     ~@body))
