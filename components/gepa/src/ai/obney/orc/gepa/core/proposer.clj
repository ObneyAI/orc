(ns ai.obney.orc.gepa.core.proposer
  "Instruction Proposer for GEPA - EXACT 1:1 Python GEPA Parity.

   Uses the EXACT same prompt template as Python GEPA's
   InstructionProposalSignature from instruction_proposal.py.

   Key differences from previous implementation:
   - Single LLM call (not 3-step workflow)
   - EXACT prompt template from Python
   - Output extraction matching Python's output_extractor"
  (:require [ai.obney.orc.orc-service.interface :as sheet]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as u]))

;; =============================================================================
;; EXACT Python GEPA Prompt Template
;; =============================================================================
;; From: src/gepa/strategies/instruction_proposal.py
;; Class: InstructionProposalSignature
;; This is the default_prompt_template - copied EXACTLY

(def python-gepa-prompt-template
  "EXACT copy of Python GEPA's InstructionProposalSignature.default_prompt_template.

   Placeholders:
   - <curr_param> → current instruction
   - <side_info> → formatted reflective examples"
  "I provided an assistant with the following instructions to perform a task for me:
```
<curr_param>
```

The following are examples of different task inputs provided to the assistant along with the assistant's response for each of them, and some feedback on how the assistant's response could be better:
```
<side_info>
```

Your task is to write a new instruction for the assistant.

Read the inputs carefully and identify the input format and infer detailed task description about the task I wish to solve with the assistant.

Read all the assistant responses and the corresponding feedback. Identify all niche and domain specific factual information about the task and include it in the instruction, as a lot of it may not be available to the assistant in the future. The assistant may have utilized a generalizable strategy to solve the task, if so, include that in the instruction as well.

Provide the new instructions within ``` blocks.")

(def general-rules-prompt-template
  "Optional reflection prompt for callers to pass as `:reflection-prompt-template`.

   GEPA's default template with three sentences inserted before its last line.
   GEPA's default asks the rewrite to include the niche and domain facts the
   examples show; this template asks for general rules instead of copied
   example texts, and leaves out values that each input already carries.
   Nothing selects it unless a caller passes it."
  (str/replace
    python-gepa-prompt-template
    "\n\nProvide the new instructions within ``` blocks."
    (str "\n\nThe new instruction will be used on many inputs you have not seen. "
         "Turn what the feedback shows into general rules: describe the kind of input or "
         "mistake, and do not copy these examples' exact texts, names or answers into the "
         "instruction. Include domain knowledge the inputs do not provide; leave out "
         "values that each input already provides, since they can differ from one input "
         "to the next."
         "\n\nProvide the new instructions within ``` blocks.")))

;; =============================================================================
;; Output Extraction - Matches Python's output_extractor
;; =============================================================================
;; From: src/gepa/strategies/instruction_proposal.py
;; Method: InstructionProposalSignature.output_extractor

(defn extract-instruction-from-response
  "Extract instruction from ``` blocks, matching Python GEPA's logic.

   Python implementation:
   1. Find first and last ``` positions
   2. If they're the same or overlap, handle incomplete blocks
   3. Skip optional language specifier after opening ```
   4. Return stripped content between blocks"
  [lm-out]
  (let [start (str/index-of lm-out "```")
        end (str/last-index-of lm-out "```")]
    (cond
      ;; No backticks found
      (nil? start)
      (str/trim lm-out)

      ;; First and last backticks are same or overlap (incomplete block)
      (or (nil? end) (>= (+ start 3) end))
      (let [stripped (str/trim lm-out)]
        (cond
          ;; Starts with ``` - remove opening and optional language specifier
          (str/starts-with? stripped "```")
          (let [after-opening (subs stripped 3)]
            (if-let [newline-idx (str/index-of after-opening "\n")]
              (str/trim (subs after-opening (inc newline-idx)))
              (str/trim after-opening)))

          ;; Ends with ``` - remove closing
          (str/ends-with? stripped "```")
          (str/trim (subs stripped 0 (- (count stripped) 3)))

          ;; Just return as-is
          :else
          stripped))

      ;; Normal case: content between first ``` and last ```
      :else
      (let [content (subs lm-out (+ start 3) end)]
        ;; Skip optional language specifier (e.g., ```markdown)
        (if-let [newline-idx (str/index-of content "\n")]
          (let [first-line (subs content 0 newline-idx)]
            ;; If first line looks like a language specifier (no spaces), skip it
            (if (and (seq first-line)
                     (not (str/includes? first-line " ")))
              (str/trim (subs content (inc newline-idx)))
              (str/trim content)))
          (str/trim content))))))

;; =============================================================================
;; Prompt Rendering - Matches Python's prompt_renderer
;; =============================================================================

(defn validate-prompt-template
  "Validate that prompt template contains required placeholders.
   Matches Python's InstructionProposalSignature.validate_prompt_template"
  [template]
  (when template
    (let [required-placeholders ["<curr_param>" "<side_info>"]
          missing (remove #(str/includes? template %) required-placeholders)]
      (when (seq missing)
        (throw (ex-info (str "Missing placeholder(s) in prompt template: "
                             (str/join ", " missing))
                        {:missing missing}))))))

(defn render-proposal-prompt
  "Render the proposal prompt with placeholders filled in.

   Arguments:
   - current-instruction: The current instruction text
   - formatted-examples: Pre-formatted reflective examples string
   - prompt-template: Optional custom template (uses default if nil)"
  ([current-instruction formatted-examples]
   (render-proposal-prompt current-instruction formatted-examples nil))
  ([current-instruction formatted-examples prompt-template]
   (let [template (or prompt-template python-gepa-prompt-template)]
     (validate-prompt-template template)
     (-> template
         (str/replace "<curr_param>" current-instruction)
         (str/replace "<side_info>" formatted-examples)))))

;; =============================================================================
;; ORC Workflow Definition - Single LLM Node
;; =============================================================================

(def instruction-proposer-workflow
  "ORC workflow for GEPA instruction proposal.

   This is a single-LLM-node workflow that matches Python GEPA exactly.
   The prompt template is injected at execution time via the instruction.

   Inputs:
   - current-instruction: The instruction being optimized
   - reflective-examples: Markdown-formatted examples with feedback

   Outputs:
   - proposed-instruction: New instruction within ``` blocks"
  (sheet/workflow "gepa-instruction-proposer"
    (sheet/blackboard
      {:current-instruction [:string {:description "Current instruction to improve"}]
       :reflective-examples [:string {:description "Markdown-formatted examples with Inputs, Generated Outputs, and Feedback"}]
       :proposed-instruction [:string {:description "New instruction within ``` blocks"}]})

    ;; Single LLM node - prompt is built at execution time
    ;; We use a placeholder instruction here; the real prompt is built
    ;; by render-proposal-prompt and passed as the instruction
    (sheet/llm "propose"
      :model "anthropic/claude-sonnet-4"
      :instruction python-gepa-prompt-template
      :reads [:current-instruction :reflective-examples]
      :writes [:proposed-instruction])))

;; =============================================================================
;; Workflow Builder
;; =============================================================================

(defn build-proposer-workflow!
  "Build the instruction proposer workflow.

   Returns the sheet-id of the built workflow."
  [context]
  (u/log ::building-proposer-workflow)
  (sheet/build-workflow! context instruction-proposer-workflow))

;; =============================================================================
;; Direct Proposal Function (bypasses ORC for simpler integration)
;; =============================================================================

(defn propose-new-instruction
  "Propose a new instruction using the EXACT Python GEPA method.

   This function can be called directly without needing the ORC workflow,
   which is useful for integration into the todo processors.

   Arguments:
   - llm-fn: Function that takes a prompt and returns LLM response
   - current-instruction: The instruction to improve
   - formatted-examples: Pre-formatted reflective examples string
   - prompt-template: Optional custom template for this component

   Returns:
   {:proposed-instruction \"extracted instruction\"
    :raw-response \"full LLM response\"}"
  [llm-fn current-instruction formatted-examples & {:keys [prompt-template]}]
  (u/log ::proposing-new-instruction
         :instruction-length (count current-instruction)
         :examples-length (count formatted-examples))

  (let [prompt (render-proposal-prompt current-instruction
                                       formatted-examples
                                       prompt-template)
        response (llm-fn prompt)
        extracted (extract-instruction-from-response response)]

    (u/log ::instruction-proposed
           :extracted-length (count extracted))

    {:proposed-instruction extracted
     :raw-response response}))

;; =============================================================================
;; Batch Proposal for Multiple Components
;; =============================================================================

(defn propose-for-components
  "Propose new instructions for multiple components.

   This matches Python GEPA's behavior where each component (predictor)
   can have its own custom prompt template.

   Arguments:
   - llm-fn: Function that takes a prompt and returns LLM response
   - candidate: Map of component-name → current-instruction
   - reflective-dataset: Map of component-name → formatted-examples
   - components-to-update: Vector of component names to mutate
   - component-templates: Optional map of component-name → prompt-template

   Returns:
   Map of component-name → proposed-instruction"
  [llm-fn candidate reflective-dataset components-to-update
   & {:keys [component-templates]}]
  (reduce
    (fn [new-texts component-name]
      (let [current-instruction (get candidate component-name)
            formatted-examples (get reflective-dataset component-name)]
        (if (or (nil? formatted-examples)
                (str/blank? formatted-examples))
          (do
            (u/log ::skipping-component
                   :component component-name
                   :reason "no-reflective-data")
            new-texts)
          (let [template (get component-templates component-name)
                result (propose-new-instruction llm-fn
                                                current-instruction
                                                formatted-examples
                                                :prompt-template template)]
            (assoc new-texts component-name (:proposed-instruction result))))))
    {}
    components-to-update))

;; =============================================================================
;; Workflow Executor (for ORC-based execution)
;; =============================================================================

(defn propose-mutation
  "Execute the instruction proposer via ORC workflow.

   Arguments:
   - context: Grain context with event-store, etc.
   - sheet-id: The proposer workflow sheet ID
   - current-instruction: The instruction to improve
   - formatted-examples: Pre-formatted reflective examples

   Returns:
   {:status :success/:failure
    :proposed-instruction \"extracted instruction\"
    :raw-response \"full response\"}"
  [context sheet-id current-instruction formatted-examples]
  (u/log ::proposing-mutation-via-orc
         :sheet-id sheet-id)

  (let [result (sheet/execute context sheet-id
                 {:current-instruction current-instruction
                  :reflective-examples formatted-examples})]

    (if (= :success (:status result))
      (let [raw-response (get-in result [:outputs :proposed-instruction])
            extracted (extract-instruction-from-response raw-response)]
        {:status :success
         :proposed-instruction extracted
         :raw-response raw-response})

      {:status :failure
       :error (:error result)})))
