(ns ai.obney.orc.mcp-sheet-builder.core.patterns
  "Pattern matching for tool combinations.

   Maps tool relationships to Google's 8 Agent Patterns:
   1. Sequential Pipeline - Linear data flow
   2. Coordinator/Dispatcher - Route to specialists
   3. Parallel Fan-Out/Gather - Independent work, then synthesis
   4. Hierarchical Decomposition - Complex task breakdown
   5. Generator/Critic - Quality assurance loop
   6. Iterative Refinement - Progressive improvement
   7. Research Compilation - Multi-source gathering
   8. Adversarial - Balanced analysis with opposing views"
  (:require [ai.obney.orc.llm.interface :as llm]
            [clojure.string :as str]))

;; ============================================================================
;; Pattern Definitions
;; ============================================================================

(def pattern-definitions
  "Definitions of agent patterns with detection criteria."
  {:sequential-pipeline
   {:name "Sequential Pipeline"
    :description "Linear data flow through tool chain"
    :orc-nodes [:sequence]
    :min-tools 2
    :requires #{:sequential}}

   :coordinator-dispatcher
   {:name "Coordinator/Dispatcher"
    :description "Route requests to specialist tools"
    :orc-nodes [:fallback :llm-condition]
    :min-tools 3
    :requires #{:alternative}}

   :parallel-fan-out
   {:name "Parallel Fan-Out/Gather"
    :description "Independent parallel work, then synthesis"
    :orc-nodes [:parallel :sequence]
    :min-tools 2
    :requires #{:parallel}}

   :hierarchical-decomposition
   {:name "Hierarchical Decomposition"
    :description "Break complex task into subtasks"
    :orc-nodes [:sequence :parallel]
    :min-tools 3
    :requires #{:sequential :parallel}}

   :generator-critic
   {:name "Generator/Critic"
    :description "Generate then validate/critique"
    :orc-nodes [:sequence :fallback]
    :min-tools 2
    :requires #{:refinement}}

   :iterative-refinement
   {:name "Iterative Refinement"
    :description "Progressive improvement through iteration"
    :orc-nodes [:fallback :sequence]
    :min-tools 2
    :requires #{:refinement :sequential}}

   :research-compilation
   {:name "Research Compilation"
    :description "Gather from multiple sources, synthesize"
    :orc-nodes [:parallel :map-each :sequence]
    :min-tools 2
    :requires #{:retrieval :search}}

   :adversarial
   {:name "Adversarial Analysis"
    :description "Opposing views with judge synthesis"
    :orc-nodes [:parallel :sequence]
    :min-tools 3
    :requires #{:generate}}})

;; ============================================================================
;; Pattern Detection
;; ============================================================================

(defn- has-relationship-type?
  "Check if relationships include a specific type."
  [relationships rel-type]
  (some #(= (:type %) rel-type) relationships))

(defn- has-capability?
  "Check if any tool has a specific capability."
  [tools capability]
  (some #(contains? (:capabilities %) capability) tools))

(defn- check-pattern-requirements
  "Check if tools and relationships satisfy pattern requirements."
  [tools relationships pattern-key]
  (let [pattern (get pattern-definitions pattern-key)
        requirements (:requires pattern)]
    (every?
     (fn [req]
       (case req
         :sequential (has-relationship-type? relationships :sequential)
         :parallel (has-relationship-type? relationships :parallel)
         :alternative (has-relationship-type? relationships :alternative)
         :complementary (has-relationship-type? relationships :complementary)
         :refinement (has-relationship-type? relationships :refinement)
         :retrieval (has-capability? tools :retrieval)
         :search (has-capability? tools :search)
         :generate (has-capability? tools :generate)
         true))
     requirements)))

(defn- calculate-pattern-confidence
  "Calculate confidence score for a pattern match."
  [tools relationships pattern-key]
  (let [pattern (get pattern-definitions pattern-key)
        tool-count (count tools)
        min-tools (:min-tools pattern)
        requirements (:requires pattern)
        ;; Base confidence from meeting minimum tools
        base-conf (if (>= tool-count min-tools) 0.5 0.3)
        ;; Bonus for each satisfied requirement
        req-bonus (* 0.1 (count requirements))
        ;; Bonus for relationship density
        rel-density (/ (count relationships) (max 1 (* tool-count (dec tool-count) 0.5)))
        density-bonus (* 0.2 (min 1.0 rel-density))]
    (min 1.0 (+ base-conf req-bonus density-bonus))))

(defn- detect-pattern
  "Detect if a specific pattern applies to the tools."
  [tools relationships pattern-key]
  (let [pattern (get pattern-definitions pattern-key)]
    (when (and (>= (count tools) (:min-tools pattern))
               (check-pattern-requirements tools relationships pattern-key))
      {:pattern pattern-key
       :confidence (calculate-pattern-confidence tools relationships pattern-key)
       :description (:description pattern)
       :orc-nodes (:orc-nodes pattern)
       :tools (mapv :name tools)})))

;; ============================================================================
;; Pattern Selection
;; ============================================================================

(defn select-patterns
  "Select applicable patterns for the analyzed tools.

   Returns a vector of pattern matches sorted by confidence."
  ([tools relationships]
   (select-patterns tools relationships {}))
  ([tools relationships opts]
   (let [all-patterns (keys pattern-definitions)
         matches (->> all-patterns
                      (map #(detect-pattern tools relationships %))
                      (filter some?)
                      (sort-by :confidence >)
                      vec)]
     ;; Apply exploration budget if specified
     (if-let [budget (:exploration-budget opts)]
       (take (:patterns budget) matches)
       matches))))

(defn best-pattern
  "Get the best matching pattern."
  [tools relationships]
  (first (select-patterns tools relationships)))

(defn pattern-for-problem
  "Select pattern based on problem description (for LLM-assisted selection).

   DEPRECATED: Use select-pattern-for-intent for semantic pattern selection.
   This is kept for backwards compatibility."
  [_tools relationships problem-description]
  (cond
    (re-find #"(?i)research|gather|search" (or problem-description ""))
    (first (filter #(= (:pattern %) :research-compilation)
                   (select-patterns _tools relationships)))

    (re-find #"(?i)generate|create|produce" (or problem-description ""))
    (first (filter #(= (:pattern %) :generator-critic)
                   (select-patterns _tools relationships)))

    (re-find #"(?i)analyze|compare|evaluate" (or problem-description ""))
    (first (filter #(= (:pattern %) :adversarial)
                   (select-patterns _tools relationships)))

    :else
    (best-pattern _tools relationships)))

;; ============================================================================
;; LLM-Based Pattern Selection (Phase 3)
;; ============================================================================

(def pattern-selection-module
  "ORC LLM module for semantic pattern selection.
   Uses LLM to choose the best agent pattern based on user intent,
   tool capabilities, and detected relationships."
  {:inputs [{:name :user-intent
             :spec :string
             :description "What the user wants to accomplish"}
            {:name :tool-capabilities
             :spec :string
             :description "List of tools with their capabilities"}
            {:name :relationship-summary
             :spec :string
             :description "Summary of detected relationships between tools"}
            {:name :available-patterns
             :spec :string
             :description "List of available agent patterns to choose from"}]
   :outputs [{:name :selected-pattern
              :spec [:enum "sequential-pipeline" "coordinator-dispatcher"
                     "parallel-fan-out" "hierarchical-decomposition"
                     "generator-critic" "iterative-refinement"
                     "research-compilation" "adversarial"]
              :description "The best pattern for this use case"}
             {:name :reasoning
              :spec :string
              :description "Why this pattern is the best fit for the user's intent"}
             {:name :confidence
              :spec [:enum "high" "medium" "low"]
              :description "Confidence level in the pattern selection"}
             {:name :alternative-pattern
              :spec [:enum "sequential-pipeline" "coordinator-dispatcher"
                     "parallel-fan-out" "hierarchical-decomposition"
                     "generator-critic" "iterative-refinement"
                     "research-compilation" "adversarial" "none"]
              :description "Second best pattern if the primary doesn't work"}]
   :instructions "You are an expert at selecting agent orchestration patterns.

Given the user's intent, available tools, and their relationships, select the best agent pattern:

1. SEQUENTIAL-PIPELINE: Use when tools have clear data flow dependencies (A → B → C)
2. COORDINATOR-DISPATCHER: Use when routing to specialists based on input type
3. PARALLEL-FAN-OUT: Use when multiple independent operations can run together
4. HIERARCHICAL-DECOMPOSITION: Use when task can be broken into subtasks
5. GENERATOR-CRITIC: Use when one tool creates and another validates
6. ITERATIVE-REFINEMENT: Use for progressive improvement loops
7. RESEARCH-COMPILATION: Use when gathering from multiple sources then synthesizing
8. ADVERSARIAL: Use when comparing alternatives or needing balanced analysis

Choose based on:
- What the user wants to accomplish (primary factor)
- How the tools relate to each other (sequential, parallel, refinement)
- Tool capabilities (search, generate, validate, etc.)

Be specific in your reasoning about WHY this pattern fits the user's goal."})

(defn- format-tools-for-selection
  "Format tools into a string describing their capabilities."
  [tools]
  (->> tools
       (map (fn [{:keys [name description capabilities]}]
              (str "- " name
                   (when description (str ": " description))
                   (when (seq capabilities)
                     (str " [capabilities: " (str/join ", " (map clojure.core/name capabilities)) "]")))))
       (str/join "\n")))

(defn- format-relationships-for-selection
  "Summarize relationships for LLM consumption."
  [relationships]
  (if (empty? relationships)
    "No relationships detected between tools."
    (let [grouped (group-by :type relationships)]
      (->> grouped
           (map (fn [[rel-type rels]]
                  (str "- " (clojure.core/name rel-type) " relationships: "
                       (str/join ", "
                                 (map (fn [{:keys [from to confidence]}]
                                        (str from " → " to " (conf: " (format "%.2f" (or confidence 0.5)) ")"))
                                      rels)))))
           (str/join "\n")))))

(defn- format-patterns-for-selection
  "Format available patterns for LLM prompt."
  []
  (->> pattern-definitions
       (map (fn [[k v]]
              (str "- " (name k) ": " (:description v))))
       (str/join "\n")))

(defn select-pattern-for-intent
  "Use LLM to select the best pattern based on user intent and context.

   Args:
     intent - Intent map from analyze-intent (or just a string for simple use)
     tools - Vector of analyzed MCP tools
     relationships - Vector of detected relationships
     opts - Options map:
       :provider - ORC LLM provider (default :openrouter)
       :model - Model to use (default \"google/gemini-2.5-flash\")

   Returns:
     {:pattern :keyword  ;; The selected pattern keyword
      :reasoning string  ;; Why this pattern was chosen
      :confidence :high/:medium/:low
      :alternative :keyword  ;; Second choice pattern
      :raw-result ...}  ;; Full ORC LLM result"
  ([intent tools relationships]
   (select-pattern-for-intent intent tools relationships {}))
  ([intent tools relationships {:keys [provider model]
                                :or {provider :openrouter
                                     model "google/gemini-2.5-flash"}}]
   (let [;; Extract user intent string
         intent-str (if (map? intent)
                      (or (:primary-goal intent)
                          (str intent))
                      (str intent))

         ;; Format inputs
         tools-str (format-tools-for-selection tools)
         rels-str (format-relationships-for-selection relationships)
         patterns-str (format-patterns-for-selection)

         ;; Prepare ORC LLM inputs
         inputs {:user-intent intent-str
                 :tool-capabilities tools-str
                 :relationship-summary rels-str
                 :available-patterns patterns-str}

         ;; Options for ORC LLM
         options {:with-metadata? true :validate? false :model model}

         ;; Call ORC LLM
         result (llm/predict provider pattern-selection-module inputs options)
         outputs (:outputs result)

         ;; Parse pattern keyword
         selected-str (:selected-pattern outputs)
         pattern-keyword (when selected-str
                           (keyword selected-str))

         ;; Parse alternative
         alt-str (:alternative-pattern outputs)
         alt-keyword (when (and alt-str (not= alt-str "none"))
                       (keyword alt-str))

         ;; Parse confidence
         conf-str (:confidence outputs)
         confidence-keyword (when conf-str
                              (keyword conf-str))]

     {:pattern (or pattern-keyword :sequential-pipeline)
      :reasoning (or (:reasoning outputs) "No reasoning provided")
      :confidence (or confidence-keyword :medium)
      :alternative alt-keyword
      :raw-result result})))

(defn select-pattern-quick
  "Quick pattern selection without LLM - uses heuristics based on intent keywords.
   Useful for fast/cheap pattern selection when LLM isn't needed."
  [intent tools relationships]
  (let [intent-str (str/lower-case
                    (if (map? intent)
                      (or (:primary-goal intent) "")
                      (str intent)))
        ;; Get applicable patterns first
        applicable (select-patterns tools relationships)]
    (cond
      ;; Research/search intents
      (or (str/includes? intent-str "research")
          (str/includes? intent-str "search")
          (str/includes? intent-str "find")
          (str/includes? intent-str "gather"))
      (or (first (filter #(= (:pattern %) :research-compilation) applicable))
          (first applicable))

      ;; Generation/creation intents
      (or (str/includes? intent-str "generate")
          (str/includes? intent-str "create")
          (str/includes? intent-str "produce")
          (str/includes? intent-str "write"))
      (or (first (filter #(= (:pattern %) :generator-critic) applicable))
          (first applicable))

      ;; Comparison/analysis intents
      (or (str/includes? intent-str "compare")
          (str/includes? intent-str "analyze")
          (str/includes? intent-str "evaluate")
          (str/includes? intent-str "pros and cons"))
      (or (first (filter #(= (:pattern %) :adversarial) applicable))
          (first applicable))

      ;; Multi-step process intents
      (or (str/includes? intent-str "pipeline")
          (str/includes? intent-str "transform")
          (str/includes? intent-str "process"))
      (or (first (filter #(= (:pattern %) :sequential-pipeline) applicable))
          (first applicable))

      ;; Multiple independent tasks
      (or (str/includes? intent-str "parallel")
          (str/includes? intent-str "simultaneously")
          (str/includes? intent-str "at the same time"))
      (or (first (filter #(= (:pattern %) :parallel-fan-out) applicable))
          (first applicable))

      ;; Validate/check intents
      (or (str/includes? intent-str "validate")
          (str/includes? intent-str "check")
          (str/includes? intent-str "verify"))
      (or (first (filter #(= (:pattern %) :generator-critic) applicable))
          (first applicable))

      ;; Default: use best pattern from relationship detection
      :else
      (first applicable))))

;; ============================================================================
;; Pattern-specific Tool Grouping
;; ============================================================================

(defn group-tools-for-pattern
  "Group tools according to a pattern's structure."
  [tools relationships pattern-key]
  (case pattern-key
    :sequential-pipeline
    ;; Order tools by sequential relationships
    (let [seq-rels (filter #(= (:type %) :sequential) relationships)
          graph (reduce (fn [g {:keys [from to]}]
                          (assoc g from to))
                        {} seq-rels)]
      ;; Simple topological sort
      {:pipeline (mapv :name tools)})

    :parallel-fan-out
    ;; Group tools that can run in parallel
    (let [parallel-rels (filter #(= (:type %) :parallel) relationships)
          parallel-tools (distinct (concat (map :from parallel-rels)
                                           (map :to parallel-rels)))]
      {:parallel-group parallel-tools
       :synthesizer (first (filter #(contains? (:capabilities %) :aggregate) tools))})

    :research-compilation
    ;; Searchers + synthesizer
    (let [searchers (filter #(some (:capabilities %) #{:search :retrieval}) tools)
          synthesizers (filter #(contains? (:capabilities %) :generate) tools)]
      {:searchers (mapv :name searchers)
       :synthesizer (first (map :name synthesizers))})

    ;; Default: no specific grouping
    {:tools (mapv :name tools)}))

(comment
  ;; Example usage
  (def tools
    [{:name "search" :capabilities #{:search} :idempotent? true}
     {:name "getPage" :capabilities #{:retrieval} :idempotent? true}
     {:name "summarize" :capabilities #{:generate :transform}}])

  (def rels
    [{:type :sequential :from "search" :to "getPage" :confidence 0.8}
     {:type :sequential :from "getPage" :to "summarize" :confidence 0.7}])

  (select-patterns tools rels))
