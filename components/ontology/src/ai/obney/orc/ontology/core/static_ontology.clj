(ns ai.obney.orc.ontology.core.static-ontology
  "Static core ontology definitions.

   Three-layer ontology system inspired by Anterior's 'Adaptive Domain Intelligence Engine':
   1. FAILURE ONTOLOGY - Why things go wrong (maps to 4 evaluation judges)
   2. SUCCESS ONTOLOGY - What makes things work (structural, instruction, data flow patterns)
   3. PROBLEM ONTOLOGY - What types of problems exist (classification, retrieval, generation)

   These are initialized once and extended over time via LLM discovery.")

;; =============================================================================
;; Dimension -> Failure URI Dictionary
;; =============================================================================
;; The one dictionary mapping evaluation dimension names (the four rubric
;; names plus their short aliases) to root failure concept URIs. The
;; classifier and get-failure-concept-for-dimension below both read this
;; single map so they cannot drift apart (RR-32).

(def dimension->failure-uri
  "Maps evaluation dimension names to root failure concept URIs."
  {"Grounding" "failure:Grounding"
   "Source Grounding" "failure:Grounding"
   "Instruction Following" "failure:InstructionFollowing"
   "Reasoning" "failure:Reasoning"
   "Reasoning Quality" "failure:Reasoning"
   "Completeness" "failure:Completeness"})

;; =============================================================================
;; Layer 1: Failure Ontology
;; =============================================================================
;; Maps to the 4 evaluation judges: Grounding, Instruction Following, Reasoning, Completeness

(def FAILURE_ONTOLOGY_CORE
  "Static core failure types - maps to 4 evaluation judges.
   Level 1 categories come directly from judges, Level 2 are known subtypes."
  {:concepts
   [;; Root
    {:uri "failure:Root"
     :label "Failure"
     :description "Root concept for all failure types"
     :scope :failure}

    ;; -------------------------------------------------------------------------
    ;; Level 1: Main categories (from 4 evaluation judges)
    ;; -------------------------------------------------------------------------

    {:uri "failure:Grounding"
     :label "Grounding Failure"
     :description "Output not supported by input context - claims without evidence"
     :scope :failure
     :broader ["failure:Root"]}

    {:uri "failure:InstructionFollowing"
     :label "Instruction Following Failure"
     :description "Did not follow given instruction - format, constraints, or requirements violated"
     :scope :failure
     :broader ["failure:Root"]}

    {:uri "failure:Reasoning"
     :label "Reasoning Failure"
     :description "Logical or reasoning defects - gaps, leaps, or circular reasoning"
     :scope :failure
     :broader ["failure:Root"]}

    {:uri "failure:Completeness"
     :label "Completeness Failure"
     :description "Missing required content or aspects - entities, details, or coverage"
     :scope :failure
     :broader ["failure:Root"]}

    ;; -------------------------------------------------------------------------
    ;; Level 2: Known subtypes under Grounding
    ;; -------------------------------------------------------------------------

    {:uri "failure:Hallucination"
     :label "Hallucination"
     :description "Generated claims not present in sources - invented facts or relationships"
     :scope :failure
     :broader ["failure:Grounding"]
     :indicators ["claim not found" "made up" "invented" "no evidence" "fabricated"]}

    {:uri "failure:FactHallucination"
     :label "Fact Hallucination"
     :description "Specific factual claims that are invented"
     :scope :failure
     :broader ["failure:Hallucination"]
     :indicators ["wrong number" "incorrect date" "false claim"]}

    {:uri "failure:RelationshipHallucination"
     :label "Relationship Hallucination"
     :description "Invented relationships between entities"
     :scope :failure
     :broader ["failure:Hallucination"]
     :indicators ["no relationship" "invented connection" "false link"]}

    {:uri "failure:Contradiction"
     :label "Contradiction"
     :description "Output contradicts information in the input"
     :scope :failure
     :broader ["failure:Grounding"]
     :indicators ["contradicts" "opposite" "inconsistent with input"]}

    {:uri "failure:Misattribution"
     :label "Misattribution"
     :description "Correctly stated facts attributed to wrong sources"
     :scope :failure
     :broader ["failure:Grounding"]
     :indicators ["wrong source" "misattributed" "credited to wrong"]}

    ;; -------------------------------------------------------------------------
    ;; Level 2: Known subtypes under InstructionFollowing
    ;; -------------------------------------------------------------------------

    {:uri "failure:FormatViolation"
     :label "Format Violation"
     :description "Output in wrong format - JSON instead of markdown, etc."
     :scope :failure
     :broader ["failure:InstructionFollowing"]
     :indicators ["wrong format" "invalid JSON" "not markdown" "malformed"]}

    {:uri "failure:ConstraintViolation"
     :label "Constraint Violation"
     :description "Violated explicit constraints - length, count, or range limits"
     :scope :failure
     :broader ["failure:InstructionFollowing"]
     :indicators ["too long" "too short" "exceeded limit" "out of range"]}

    {:uri "failure:RequirementMissed"
     :label "Requirement Missed"
     :description "Did not include explicitly required elements"
     :scope :failure
     :broader ["failure:InstructionFollowing"]
     :indicators ["missing required" "did not include" "omitted required"]}

    {:uri "failure:ScopeViolation"
     :label "Scope Violation"
     :description "Went outside the specified scope of the task"
     :scope :failure
     :broader ["failure:InstructionFollowing"]
     :indicators ["out of scope" "beyond request" "unasked for"]}

    ;; -------------------------------------------------------------------------
    ;; Level 2: Known subtypes under Reasoning
    ;; -------------------------------------------------------------------------

    {:uri "failure:LogicalGap"
     :label "Logical Gap"
     :description "Missing reasoning step - conclusion doesn't follow from premises"
     :scope :failure
     :broader ["failure:Reasoning"]
     :indicators ["missing step" "doesn't follow" "unexplained leap"]}

    {:uri "failure:UnjustifiedLeap"
     :label "Unjustified Leap"
     :description "Conclusion reached without sufficient supporting reasoning"
     :scope :failure
     :broader ["failure:Reasoning"]
     :indicators ["unjustified" "no support" "leap in logic"]}

    {:uri "failure:CircularReasoning"
     :label "Circular Reasoning"
     :description "Conclusion used as premise - tautological reasoning"
     :scope :failure
     :broader ["failure:Reasoning"]
     :indicators ["circular" "tautology" "assumes conclusion"]}

    {:uri "failure:FalseEquivalence"
     :label "False Equivalence"
     :description "Treating unlike things as equivalent"
     :scope :failure
     :broader ["failure:Reasoning"]
     :indicators ["false equivalence" "not comparable" "unlike comparison"]}

    ;; -------------------------------------------------------------------------
    ;; Level 2: Known subtypes under Completeness
    ;; -------------------------------------------------------------------------

    {:uri "failure:MissingEntity"
     :label "Missing Entity"
     :description "Required entity or item not included in output"
     :scope :failure
     :broader ["failure:Completeness"]
     :indicators ["missing" "omitted" "not included" "left out"]}

    {:uri "failure:InsufficientDetail"
     :label "Insufficient Detail"
     :description "Covered topics but with inadequate depth"
     :scope :failure
     :broader ["failure:Completeness"]
     :indicators ["too brief" "lacks detail" "superficial" "shallow"]}

    {:uri "failure:TruncatedOutput"
     :label "Truncated Output"
     :description "Output was cut off before completion"
     :scope :failure
     :broader ["failure:Completeness"]
     :indicators ["truncated" "cut off" "incomplete" "ended abruptly"]}

    {:uri "failure:PartialCoverage"
     :label "Partial Coverage"
     :description "Only some aspects of the request were addressed"
     :scope :failure
     :broader ["failure:Completeness"]
     :indicators ["partial" "only covered" "missed aspects"]}]

   :relationships
   [{:source "failure:Hallucination" :target "failure:Contradiction" :predicate "skos:related"}
    {:source "failure:LogicalGap" :target "failure:UnjustifiedLeap" :predicate "skos:related"}]})

;; =============================================================================
;; Layer 2: Success Ontology
;; =============================================================================
;; Patterns that lead to successful tree executions

(def SUCCESS_ONTOLOGY_CORE
  "Static core success patterns - what makes trees work well."
  {:concepts
   [;; Root
    {:uri "success:Root"
     :label "Success Pattern"
     :description "Root concept for success patterns"
     :scope :success}

    ;; -------------------------------------------------------------------------
    ;; Pattern Categories
    ;; -------------------------------------------------------------------------

    {:uri "success:StructuralPattern"
     :label "Structural Pattern"
     :description "Effective tree structure patterns - how nodes are organized"
     :scope :success
     :broader ["success:Root"]}

    {:uri "success:InstructionPattern"
     :label "Instruction Pattern"
     :description "Effective instruction patterns - how prompts are written"
     :scope :success
     :broader ["success:Root"]}

    {:uri "success:DataFlowPattern"
     :label "Data Flow Pattern"
     :description "Effective data flow patterns - how information moves through the tree"
     :scope :success
     :broader ["success:Root"]}

    ;; -------------------------------------------------------------------------
    ;; Structural Patterns
    ;; -------------------------------------------------------------------------

    {:uri "success:ValidationLoop"
     :label "Validation Loop"
     :description "Including validation step after generation to catch errors"
     :scope :success
     :broader ["success:StructuralPattern"]}

    {:uri "success:FallbackRecovery"
     :label "Fallback Recovery"
     :description "Using fallback nodes to handle failures gracefully"
     :scope :success
     :broader ["success:StructuralPattern"]}

    {:uri "success:ParallelIndependent"
     :label "Parallel Independent"
     :description "Running independent tasks in parallel for efficiency"
     :scope :success
     :broader ["success:StructuralPattern"]}

    {:uri "success:SequentialPipeline"
     :label "Sequential Pipeline"
     :description "Clear sequential steps where each builds on the previous"
     :scope :success
     :broader ["success:StructuralPattern"]}

    {:uri "success:IterativeRefinement"
     :label "Iterative Refinement"
     :description "Multiple passes to improve output quality"
     :scope :success
     :broader ["success:StructuralPattern"]}

    ;; -------------------------------------------------------------------------
    ;; Instruction Patterns
    ;; -------------------------------------------------------------------------

    {:uri "success:ExplicitSchema"
     :label "Explicit Schema Definition"
     :description "Using explicit output schemas with field descriptions"
     :scope :success
     :broader ["success:InstructionPattern"]}

    {:uri "success:FewShotExamples"
     :label "Few-Shot Examples"
     :description "Including examples in the instruction to guide output"
     :scope :success
     :broader ["success:InstructionPattern"]}

    {:uri "success:ChainOfThought"
     :label "Chain of Thought"
     :description "Instructing step-by-step reasoning before conclusions"
     :scope :success
     :broader ["success:InstructionPattern"]}

    {:uri "success:ExplicitConstraints"
     :label "Explicit Constraints"
     :description "Clearly stating constraints and boundaries"
     :scope :success
     :broader ["success:InstructionPattern"]}

    {:uri "success:ContextGrounding"
     :label "Context Grounding"
     :description "Explicitly referencing input data in instructions"
     :scope :success
     :broader ["success:InstructionPattern"]}

    ;; -------------------------------------------------------------------------
    ;; Data Flow Patterns
    ;; -------------------------------------------------------------------------

    {:uri "success:MultiSourceGathering"
     :label "Multi-Source Gathering"
     :description "Gathering information from multiple sources in parallel"
     :scope :success
     :broader ["success:DataFlowPattern"]}

    {:uri "success:ProgressiveEnrichment"
     :label "Progressive Enrichment"
     :description "Building up data through successive enrichment steps"
     :scope :success
     :broader ["success:DataFlowPattern"]}

    {:uri "success:ExplicitBlackboardKeys"
     :label "Explicit Blackboard Keys"
     :description "Clear, typed blackboard keys with descriptions"
     :scope :success
     :broader ["success:DataFlowPattern"]}

    {:uri "success:ScopedContext"
     :label "Scoped Context"
     :description "Passing only relevant context to each node"
     :scope :success
     :broader ["success:DataFlowPattern"]}]

   :relationships
   [{:source "success:ValidationLoop" :target "success:IterativeRefinement" :predicate "skos:related"}
    {:source "success:ExplicitSchema" :target "success:ExplicitBlackboardKeys" :predicate "skos:related"}]})

;; =============================================================================
;; Layer 3: Problem Domain Ontology
;; =============================================================================
;; Types of problems that trees can solve

(def PROBLEM_ONTOLOGY_CORE
  "Static core problem domains - what types of problems trees solve."
  {:concepts
   [;; Root
    {:uri "problem:Root"
     :label "Problem Category"
     :description "Root concept for problem types"
     :scope :problem}

    ;; -------------------------------------------------------------------------
    ;; Main Categories
    ;; -------------------------------------------------------------------------

    {:uri "problem:InformationRetrieval"
     :label "Information Retrieval"
     :description "Finding and extracting information from sources"
     :scope :problem
     :broader ["problem:Root"]}

    {:uri "problem:ContentGeneration"
     :label "Content Generation"
     :description "Creating new content based on inputs"
     :scope :problem
     :broader ["problem:Root"]}

    {:uri "problem:Analysis"
     :label "Analysis"
     :description "Analyzing and evaluating data or content"
     :scope :problem
     :broader ["problem:Root"]}

    {:uri "problem:Workflow"
     :label "Workflow"
     :description "Multi-step processes with conditional logic"
     :scope :problem
     :broader ["problem:Root"]}

    ;; -------------------------------------------------------------------------
    ;; Information Retrieval subtypes
    ;; -------------------------------------------------------------------------

    {:uri "problem:DocumentSearch"
     :label "Document Search"
     :description "Finding relevant documents or sections"
     :scope :problem
     :broader ["problem:InformationRetrieval"]}

    {:uri "problem:DataExtraction"
     :label "Data Extraction"
     :description "Extracting structured data from unstructured sources"
     :scope :problem
     :broader ["problem:InformationRetrieval"]}

    {:uri "problem:KnowledgeQuery"
     :label "Knowledge Query"
     :description "Answering questions from a knowledge base"
     :scope :problem
     :broader ["problem:InformationRetrieval"]}

    {:uri "problem:ResearchGathering"
     :label "Research Gathering"
     :description "Collecting information from multiple sources"
     :scope :problem
     :broader ["problem:InformationRetrieval"]}

    ;; -------------------------------------------------------------------------
    ;; Content Generation subtypes
    ;; -------------------------------------------------------------------------

    {:uri "problem:Summarization"
     :label "Summarization"
     :description "Condensing content while preserving key information"
     :scope :problem
     :broader ["problem:ContentGeneration"]}

    {:uri "problem:Translation"
     :label "Translation"
     :description "Converting content between languages or formats"
     :scope :problem
     :broader ["problem:ContentGeneration"]}

    {:uri "problem:CreativeWriting"
     :label "Creative Writing"
     :description "Generating creative or original content"
     :scope :problem
     :broader ["problem:ContentGeneration"]}

    {:uri "problem:StructuredOutput"
     :label "Structured Output"
     :description "Generating structured data (JSON, tables)"
     :scope :problem
     :broader ["problem:ContentGeneration"]}

    ;; -------------------------------------------------------------------------
    ;; Analysis subtypes
    ;; -------------------------------------------------------------------------

    {:uri "problem:Classification"
     :label "Classification"
     :description "Categorizing items into predefined classes"
     :scope :problem
     :broader ["problem:Analysis"]}

    {:uri "problem:Scoring"
     :label "Scoring"
     :description "Assigning numerical scores or ratings"
     :scope :problem
     :broader ["problem:Analysis"]}

    {:uri "problem:Comparison"
     :label "Comparison"
     :description "Comparing multiple items or options"
     :scope :problem
     :broader ["problem:Analysis"]}

    {:uri "problem:QualityAssessment"
     :label "Quality Assessment"
     :description "Evaluating quality or validity"
     :scope :problem
     :broader ["problem:Analysis"]}

    {:uri "problem:SentimentAnalysis"
     :label "Sentiment Analysis"
     :description "Determining sentiment or emotional tone"
     :scope :problem
     :broader ["problem:Analysis"]}

    ;; -------------------------------------------------------------------------
    ;; Workflow subtypes
    ;; -------------------------------------------------------------------------

    {:uri "problem:MultiStepProcess"
     :label "Multi-Step Process"
     :description "Sequential steps with dependencies"
     :scope :problem
     :broader ["problem:Workflow"]}

    {:uri "problem:ConditionalBranching"
     :label "Conditional Branching"
     :description "Different paths based on conditions"
     :scope :problem
     :broader ["problem:Workflow"]}

    {:uri "problem:DataPipeline"
     :label "Data Pipeline"
     :description "Processing data through transformation stages"
     :scope :problem
     :broader ["problem:Workflow"]}]

   :relationships
   [{:source "problem:Classification" :target "problem:Scoring" :predicate "skos:related"}
    {:source "problem:DataExtraction" :target "problem:StructuredOutput" :predicate "skos:related"}]})

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn get-all-static-concepts
  "Get all concepts from all static ontologies."
  []
  (concat (:concepts FAILURE_ONTOLOGY_CORE)
          (:concepts SUCCESS_ONTOLOGY_CORE)
          (:concepts PROBLEM_ONTOLOGY_CORE)))

(defn get-all-static-relationships
  "Get all relationships from all static ontologies."
  []
  (concat (:relationships FAILURE_ONTOLOGY_CORE)
          (:relationships SUCCESS_ONTOLOGY_CORE)
          (:relationships PROBLEM_ONTOLOGY_CORE)))

(defn get-concepts-by-scope
  "Get concepts filtered by scope."
  [scope]
  (case scope
    :failure (:concepts FAILURE_ONTOLOGY_CORE)
    :success (:concepts SUCCESS_ONTOLOGY_CORE)
    :problem (:concepts PROBLEM_ONTOLOGY_CORE)
    (get-all-static-concepts)))

(defn get-concept-by-uri
  "Find a concept by its URI across all static ontologies."
  [uri]
  (first (filter #(= uri (:uri %)) (get-all-static-concepts))))

(defn get-narrower-concepts
  "Get all concepts that have the given URI as a broader concept."
  [broader-uri]
  (filter #(some #{broader-uri} (:broader %)) (get-all-static-concepts)))

(defn get-failure-concept-for-dimension
  "Map evaluation dimension name to root failure concept URI."
  [dimension-name]
  (get dimension->failure-uri dimension-name))
