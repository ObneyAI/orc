(ns ai.obney.orc.mcp-sheet-builder.workflows.builder
  "The meta-sheet workflow - a sheet that builds sheets.

   This ORC workflow performs the complete MCP analysis pipeline:
   1. Discover - Fetch tools from MCP server
   2. Analyze - Extract capabilities and relationships
   3. Match - Find applicable patterns
   4. Select - Choose best pattern (optionally LLM-assisted)
   5. Generate - Create ORC DSL code
   6. Validate - Check generated sheet"
  (:require [ai.obney.orc.mcp-sheet-builder.core.mcp-client :as mcp-client]
            [ai.obney.orc.mcp-sheet-builder.core.analyzer :as analyzer]
            [ai.obney.orc.mcp-sheet-builder.core.relationships :as relationships]
            [ai.obney.orc.mcp-sheet-builder.core.patterns :as patterns]
            [ai.obney.orc.mcp-sheet-builder.core.generator :as generator]
            [ai.obney.orc.mcp-sheet-builder.core.validator :as validator]))

;; ============================================================================
;; Workflow Executors (for ORC code nodes)
;; ============================================================================

(defn fetch-tools-executor
  "Executor that fetches tools from an MCP connection."
  [{:keys [inputs context]}]
  (let [mcp-opts (get inputs :mcp-opts)
        mcp-conn (or (:mcp-session context)
                     (mcp-client/connect mcp-opts))
        tools (mcp-client/list-tools mcp-conn)]
    {:raw-tools tools
     :mcp-session mcp-conn}))

(defn analyze-capabilities-executor
  "Executor that analyzes tool capabilities."
  [{:keys [inputs]}]
  (let [raw-tools (get inputs :raw-tools)
        analyzed (analyzer/analyze-tools raw-tools)]
    {:analyzed-tools analyzed}))

(defn detect-relationships-executor
  "Executor that detects tool relationships."
  [{:keys [inputs]}]
  (let [analyzed-tools (get inputs :analyzed-tools)
        relationships (relationships/detect-relationships analyzed-tools)]
    {:tool-relationships relationships}))

(defn match-patterns-executor
  "Executor that matches applicable patterns."
  [{:keys [inputs]}]
  (let [analyzed-tools (get inputs :analyzed-tools)
        tool-relationships (get inputs :tool-relationships)
        matched-patterns (patterns/select-patterns analyzed-tools tool-relationships)]
    {:applicable-patterns matched-patterns}))

(defn generate-sheet-executor
  "Executor that generates the ORC sheet."
  [{:keys [inputs]}]
  (let [analyzed-tools (get inputs :analyzed-tools)
        tool-relationships (get inputs :tool-relationships)
        selected-pattern (get inputs :selected-pattern)
        analysis {:tools analyzed-tools
                  :relationships tool-relationships
                  :patterns [selected-pattern]}
        sheet (generator/generate-sheet analysis {:pattern (:pattern selected-pattern)})]
    {:generated-dsl-code (:code sheet)
     :generated-workflow (:workflow sheet)
     :generated-blackboard (:blackboard sheet)}))

(defn validate-sheet-executor
  "Executor that validates the generated sheet."
  [{:keys [inputs]}]
  (let [workflow (get inputs :generated-workflow)
        blackboard (get inputs :generated-blackboard)
        validation (validator/validate-sheet {:workflow workflow
                                              :blackboard blackboard})]
    {:validation-result validation}))

;; ============================================================================
;; ORC Workflow Definition
;; ============================================================================

(def mcp-sheet-builder-workflow
  "The meta-sheet workflow definition.

   This can be built and executed using the orc-service."
  '(sheet/workflow "mcp-sheet-builder"
     (sheet/blackboard
       {:mcp-opts [:map
                   [:type [:enum :http :static :claude-mcp]]
                   [:preset {:optional true} :keyword]
                   [:url {:optional true} :string]]
        :raw-tools [:vector [:map [:name :string]]]
        :analyzed-tools [:vector [:map
                                  [:name :string]
                                  [:category :keyword]
                                  [:capabilities [:set :keyword]]
                                  [:idempotent? :boolean]]]
        :tool-relationships [:vector [:map
                                      [:type :keyword]
                                      [:from :string]
                                      [:to :string]
                                      [:confidence :double]
                                      [:reason :string]]]
        :applicable-patterns [:vector [:map
                                       [:pattern :keyword]
                                       [:confidence :double]]]
        :selected-pattern [:map
                           [:pattern :keyword]
                           [:confidence :double]]
        :generated-dsl-code :string
        :generated-workflow
        [:schema
         {:registry
          {:mcp/workflow-form
           [:or :symbol :string :keyword :int :double :boolean
            [:map-of :keyword [:ref :mcp/workflow-form]]
            [:sequential [:ref :mcp/workflow-form]]]}}
         [:ref :mcp/workflow-form]]
        :generated-blackboard
        [:map-of :keyword
         [:schema
          {:registry
           {:mcp/schema-form
            [:or :keyword :string :int :double :boolean
             [:map-of :keyword [:ref :mcp/schema-form]]
             [:sequential [:ref :mcp/schema-form]]]}}
          [:ref :mcp/schema-form]]]
        :validation-result [:map
                            [:valid? :boolean]
                            [:errors [:vector :string]]
                            [:warnings [:vector :string]]]})

     (sheet/sequence "build-sheet"
       ;; Phase 1: Discover
       (sheet/code "fetch-tools"
         :fn "ai.obney.orc.mcp-sheet-builder.workflows.builder/fetch-tools-executor"
         :reads [:mcp-opts]
         :writes [:raw-tools])

       ;; Phase 2: Analyze (parallel)
       (sheet/sequence "analyze"
         (sheet/code "analyze-capabilities"
           :fn "ai.obney.orc.mcp-sheet-builder.workflows.builder/analyze-capabilities-executor"
           :reads [:raw-tools]
           :writes [:analyzed-tools])

         (sheet/code "detect-relationships"
           :fn "ai.obney.orc.mcp-sheet-builder.workflows.builder/detect-relationships-executor"
           :reads [:analyzed-tools]
           :writes [:tool-relationships]))

       ;; Phase 3: Match patterns
       (sheet/code "match-patterns"
         :fn "ai.obney.orc.mcp-sheet-builder.workflows.builder/match-patterns-executor"
         :reads [:analyzed-tools :tool-relationships]
         :writes [:applicable-patterns])

       ;; Phase 4: Select best pattern (LLM-assisted)
       (sheet/llm "select-pattern"
         :model "google/gemini-2.5-flash"
         :instruction "Given these analyzed MCP tools and applicable patterns,
                       select the best pattern for creating a useful workflow.

                       Consider:
                       - The types of tools available (search, retrieval, generation)
                       - The relationships between tools
                       - Which pattern would create the most useful workflow

                       Return the selected pattern with reasoning."
         :reads [:analyzed-tools :applicable-patterns]
         :writes [:selected-pattern])

       ;; Phase 5: Generate
       (sheet/code "generate-sheet"
         :fn "ai.obney.orc.mcp-sheet-builder.workflows.builder/generate-sheet-executor"
         :reads [:analyzed-tools :tool-relationships :selected-pattern]
         :writes [:generated-dsl-code :generated-workflow :generated-blackboard])

       ;; Phase 6: Validate
       (sheet/code "validate-sheet"
         :fn "ai.obney.orc.mcp-sheet-builder.workflows.builder/validate-sheet-executor"
         :reads [:generated-workflow :generated-blackboard]
         :writes [:validation-result]))))

;; ============================================================================
;; Direct Execution (without ORC)
;; ============================================================================

(defn build-sheet-directly
  "Execute the sheet building pipeline directly (without ORC runtime).

   Useful for testing and exploration."
  [{:keys [mcp-opts problem]}]
  (let [;; Phase 1: Discover
        mcp-conn (mcp-client/connect mcp-opts)
        raw-tools (mcp-client/list-tools mcp-conn)

        ;; Phase 2: Analyze
        analyzed-tools (analyzer/analyze-tools raw-tools)
        tool-relationships (relationships/detect-relationships analyzed-tools)

        ;; Phase 3: Match patterns
        applicable-patterns (patterns/select-patterns analyzed-tools tool-relationships)

        ;; Phase 4: Select (simple heuristic if no LLM)
        selected-pattern (if problem
                           (patterns/pattern-for-problem analyzed-tools tool-relationships problem)
                           (first applicable-patterns))

        ;; Phase 5: Generate
        analysis {:tools analyzed-tools
                  :relationships tool-relationships
                  :patterns applicable-patterns}
        sheet (generator/generate-sheet analysis {:pattern (:pattern selected-pattern)})

        ;; Phase 6: Validate
        validation (validator/validate-sheet sheet)]

    {:mcp-connection mcp-conn
     :raw-tools raw-tools
     :analyzed-tools analyzed-tools
     :relationships tool-relationships
     :patterns applicable-patterns
     :selected-pattern selected-pattern
     :sheet sheet
     :validation validation}))

(comment
  ;; Example: Build a sheet from Langfuse tools
  (def result
    (build-sheet-directly
     {:mcp-opts {:preset :langfuse}
      :problem "Research Langfuse documentation"}))

  (:sheet result)
  (:validation result))
