(ns ai.obney.orc.ontology.core.logic
  "Ontology data, retrieval, classification and source transformations."
  (:require [ai.obney.ontology.interface :as ontology-lib]
            [ai.obney.ontology.core.source-registry :as sources]
            [ai.obney.ontology.core.rdf :as rdf]
            [ai.obney.ontology.core.graph :as graph]
            [ai.obney.ontology.core.entity-resolver :as entity-resolver]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.integrations.interface :as integrations]
            [ai.obney.orc.ontology.interface.schemas :as schemas]
            [ai.obney.orc.orc-service.interface :as orc]
            [cheshire.core :as cheshire]
            [clojure.data.csv :as csv]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [com.brunobonacci.mulog :as u]
            [litellm.router :as litellm-router]
            [malli.core :as m])
  (:import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory
           ai.djl.repository.zoo.Criteria
           java.io.File
           java.security.MessageDigest))

;; --- Interface ---

(defn colbert-fn
  "Lazily resolve a fn from the colbert component. Returns nil when colbert
   is not on the classpath, so the ontology builds and runs without it — the
   ColBERT-backed Living-Description search simply returns no results, while
   graph + embedding retrieval is unaffected. (ColBERT is an optional Layer-5
   upgrade; see COMPONENT-MAP.md.)"
  [fn-name]
  (integrations/capability :colbert (keyword fn-name)))

;; --- Static ontology ---

(def FAILURE_ONTOLOGY_CORE
  "Static core failure types - maps to 4 evaluation judges.
   Level 1 categories come directly from judges, Level 2 are known subtypes."
  {:concepts
   [
    {:uri "failure:Root"
     :label "Failure"
     :description "Root concept for all failure types"
     :scope :failure}


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

(def SUCCESS_ONTOLOGY_CORE
  "Static core success patterns - what makes trees work well."
  {:concepts
   [
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

(def PROBLEM_ONTOLOGY_CORE
  "Static core problem domains - what types of problems trees solve."
  {:concepts
   [
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

(defn get-all-static-concepts
  "Get all concepts from all static ontologies."
  []
  (concat (:concepts FAILURE_ONTOLOGY_CORE)
          (:concepts SUCCESS_ONTOLOGY_CORE)
          (:concepts PROBLEM_ONTOLOGY_CORE)))

(defn get-concepts-by-scope
  "Get concepts filtered by scope."
  [scope]
  (case scope
    :failure (:concepts FAILURE_ONTOLOGY_CORE)
    :success (:concepts SUCCESS_ONTOLOGY_CORE)
    :problem (:concepts PROBLEM_ONTOLOGY_CORE)
    (get-all-static-concepts)))

;; --- Interface ---

(defn get-static-concepts
  "Get concepts from static ontology definitions.
   Options:
   - :scope - Filter by :failure, :success, or :problem"
  ([]
   (get-all-static-concepts))
  ([{:keys [scope]}]
   (if scope
     (get-concepts-by-scope scope)
     (get-all-static-concepts))))

(defn latest-ontology-descriptions-index
  "Return the most-recently-created active ColBERT index with name
   'ontology-descriptions', or nil if none exists."
  [ctx]
  (let [candidates (filter #(= "ontology-descriptions" (:index-name %))
                           (if-let [list-indexes (colbert-fn 'list-indexes)]
                             (list-indexes ctx)
                             []))]
    (when (seq candidates)
      (last (sort-by :created-at candidates)))))

(defn granularity-name
  "Granularity field round-trips through ColBERT's Python bridge as a
   JSON string, so a stored value of `:node-type` comes back as
   \"node-type\". Compare by name to bridge that gap."
  [v]
  (cond
    (keyword? v) (name v)
    (string? v)  v
    :else        (str v)))

(defn normalize-search-result
  "`colbert/search` returns snake_case keys (`:document_id`,
   `:document_metadata`) — the result shape frozen when the signal moved
   to the pure JVM. Normalize to kebab-case here, and re-keywordize known
   metadata fields so downstream consumers don't have to deal with
   stringified keywords from the artifact's JSON roundtrip."
  [r]
  (let [meta (or (:document-metadata r) (:document_metadata r))
        norm-meta (when meta
                    (cond-> meta
                      (:granularity meta) (update :granularity
                                                   #(if (keyword? %) % (keyword %)))))]
    (-> r
        (assoc :document-id (or (:document-id r) (:document_id r)))
        (assoc :document-metadata norm-meta)
        (dissoc :document_id :document_metadata))))

(def ^:private rerank-over-fetch-multiplier
  "Default over-fetch when reranking: pull 2x the caller's :k from
   ColBERT so the reranker has signal to actually re-order."
  2)

(def ^:private rerank-hard-cap
  "Hard cap on the candidate count fed to the reranker. Bounds the
   structured-output prompt size regardless of caller :k."
  50)

(defn rerank-fetch-k
  "How many candidates to pull from ColBERT when reranking. Caller asks
   for top-:k; we pull 2x that for the reranker to choose from, hard-
   capped at rerank-hard-cap."
  [k]
  (min rerank-hard-cap (max k (* rerank-over-fetch-multiplier k))))

(def ^:private evidence-max-entries
  "Keep the enrichment compact: cap how many strengths/weaknesses ride along
   so the structured-output prompt stays small."
  3)

(defn coerce-evidence-target-id
  "A candidate's :document-metadata :target-id round-trips through ColBERT's
   JSON bridge as a string; coerce to UUID when it parses as one (minted
   bodies key the descriptions read-model by UUID). Non-UUID strings (e.g.
   seed fingerprints like \"seed:tree:ChunkedExtraction\") pass through."
  [tid]
  (cond
    (uuid? tid) tid
    (string? tid) (try (java.util.UUID/fromString tid) (catch Exception _ tid))
    :else tid))

(defn compact-strengths [strengths]
  (->> strengths
       (take evidence-max-entries)
       (mapv (fn [e] (cond-> {:trait (:trait e)}
                       (:good-when e) (assoc :good-when (:good-when e))
                       (:recommended-pattern e) (assoc :recommended-pattern (:recommended-pattern e)))))))

(defn compact-weaknesses [weaknesses]
  (->> weaknesses
       (take evidence-max-entries)
       (mapv (fn [e] (cond-> {:trait (:trait e)}
                       (:avoid-when e) (assoc :avoid-when (:avoid-when e))
                       (:recommended-alternative e) (assoc :recommended-alternative (:recommended-alternative e)))))))

(def family-label-list-bound
  "CV-A (domain-child convergence, item 1 + item 6): the bound on the
   tenant-wide family-label list — one number shared by the classifier's
   tenant-wide family landing (`existing-domain-families`) and the
   reranker's bounded `:existing-domain-labels` blackboard slot, so the two
   never drift apart."
  50)

(defn tree-class-uri->target-id
  "Strip the 'tree-class:' prefix from a concept URI and parse as UUID when
   possible. Mirrors task-classifier's private `uri->target-id` — duplicated
   here rather than shared, matching this codebase's existing precedent for
   this exact derivation (commands.clj's `tree-class-uri` helper)."
  [uri]
  (let [bare (if (and (string? uri) (str/starts-with? uri "tree-class:"))
               (subs uri (count "tree-class:"))
               uri)]
    (try (java.util.UUID/fromString bare)
         (catch Exception _ bare))))

;; --- Task classifier ---

(def default-classify-behaviors-top-n
  "The established top-N surfacing convention for behavioral classification.

   NOT private: RR-3 (ADR 0020 #4) reuses this exact number as K for the
   reranker's structural candidate-richness cap (how many CHILDREN keep full
   richness in the prompt) rather than inventing a second magic number that
   could drift away from this one."
  3)

;; --- Interface ---

(def ^:private child-full-richness-top-n
  "K — how many CHILDREN keep full richness in the reranker prompt. Reuses
   `classify-behaviors`' established `:top-n` surfacing convention rather than
   introducing a second number that could drift from it."
  default-classify-behaviors-top-n)

(def ^:private terse-candidate-keys
  "What a CAPPED candidate carries into the reranker prompt: the content
   SUMMARY (already a one-line summary — it is the description's `:summary`
   field, which is what ColBERT indexes), the raw ColBERT score, and the
   document-id it must echo back. Everything else — :avoid-when, :strengths,
   :weaknesses, :document-metadata, :rank — is dropped. That drop IS the
   shrinkage."
  [:document-id :content :score])

(defn- child-candidate?
  "True when the candidate's Living Description body declares a
   `:parent-behavior` — i.e. it is one of the harvest-grown CHILDREN. The ~12
   curated abstract parents have none, and neither does any non-behavioral
   candidate (tree-class / tree-fingerprint / node-type), so both keep full
   richness. Conservative by construction: only the unbounded, growing side of
   the corpus is ever capped."
  [c]
  (some? (:ai.obney.orc.ontology.interface/parent-behavior c)))

(defn shape-candidate-richness
  "Decide STRUCTURALLY who carries full richness into the reranker prompt.

   Parents (and every non-behavioral candidate) keep everything. Children are
   ordered by their pre-rerank ColBERT score; the top-`child-full-richness-top-n`
   keep everything, the rest are cut to `terse-candidate-keys`.

   Candidate ORDER and COUNT are unchanged — this only shrinks per-candidate
   detail, never the ranking task.

   CC-9a: `::domain-penalty/enforcing-avoid-when` is stripped here alongside
   `::parent-behavior`. It is an internal signal for the deterministic penalty,
   and its strings are already in the candidate's `:avoid-when` — putting it in
   the prompt would duplicate every guard the model reads."
  [annotated]
  (let [keep-ids (into #{}
                       (map :document-id)
                       (->> annotated
                            (filter child-candidate?)
                            (sort-by #(or (:score %) 0.0) >)
                            (take child-full-richness-top-n)))]
    (mapv (fn [c]
            (let [c' (dissoc c :ai.obney.orc.ontology.interface/parent-behavior :ai.obney.orc.ontology.core.domain-penalty/enforcing-avoid-when)]
              (if (and (child-candidate? c)
                       (not (contains? keep-ids (:document-id c))))
                (select-keys c' terse-candidate-keys)
                c')))
          annotated)))

(def ^:private tree-axis-names #{"tree-class" "tree-fingerprint"})

(defn- tree-axis-target [c]
  (let [g (granularity-name (-> c :document-metadata :granularity))]
    (when (contains? tree-axis-names g)
      (str (-> c :document-metadata :target-id)))))

(defn tree-class-representatives
  "Every tree class is indexed on BOTH tree axes with the same description, so
   a reranker fed raw rows spends two of its slots on each class and sees half
   as many classes as its budget allows (a live in-domain task lost its seed
   this way, ColBERT's near-flat scores deciding which five classes made it).
   Returns `{:representatives :siblings}`: one row per tree class, in ColBERT
   order, preferring the instruction-aware :tree-class row; every other row
   passes through unchanged. `:siblings` maps a class's target id to its other
   axis rows, for `restore-axis-siblings`."
  [rows]
  (let [by-target (group-by tree-axis-target (filter tree-axis-target rows))
        pick (fn [t] (let [rs (get by-target t)]
                       (or (some #(when (= "tree-class" (granularity-name (-> % :document-metadata :granularity))) %) rs)
                           (first rs))))
        reps (loop [[r & more] rows seen #{} out []]
               (if-not r
                 out
                 (if-let [t (tree-axis-target r)]
                   (if (seen t) (recur more seen out) (recur more (conj seen t) (conj out (pick t))))
                   (recur more seen (conj out r)))))
        rep-ids (set (map :document-id reps))]
    {:representatives reps
     :siblings (into {} (map (fn [[t rs]] [t (vec (remove #(rep-ids (:document-id %)) rs))])) by-target)}))

(defn restore-axis-siblings
  "After the rerank, give each tree class's other axis row the SAME judgement
   as its representative (fitness, reasoning, domain verdict, enrichment) with
   its own identity fields, placed right after it. Downstream sees both axis
   rows exactly as before, now judged once."
  [reranked siblings]
  (vec (mapcat (fn [r]
                 (cons r (for [sib (get siblings (tree-axis-target r))]
                           (merge r (select-keys sib [:document-id :document-metadata :score :rank :content])))))
               reranked)))

;; --- Serialization ---

(defn- format-prefixes
  "Format prefix declarations for Turtle output."
  [prefixes]
  (->> prefixes
       (map (fn [[prefix uri]]
              (str "@prefix " prefix ": <" uri "> .")))
       (str/join "\n")))

(def standard-prefixes
  "Standard prefixes for ontology export."
  {"rdf" "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
   "rdfs" "http://www.w3.org/2000/01/rdf-schema#"
   "owl" "http://www.w3.org/2002/07/owl#"
   "xsd" "http://www.w3.org/2001/XMLSchema#"
   "skos" "http://www.w3.org/2004/02/skos/core#"
   "dcterms" "http://purl.org/dc/terms/"})

(def domain-prefixes
  "Prefixes for ontology domain namespaces."
  {"failure" "http://obney.ai/workshop/ontology/failure#"
   "success" "http://obney.ai/workshop/ontology/success#"
   "problem" "http://obney.ai/workshop/ontology/problem#"
   "tree" "http://obney.ai/workshop/ontology/tree#"
   "node" "http://obney.ai/workshop/ontology/node#"
   "pattern" "http://obney.ai/workshop/ontology/pattern#"
   "ex" "http://example.org/taxonomy#"})

(defn- all-prefixes []
  (merge standard-prefixes domain-prefixes))

(defn- uri->local-name
  "Extract local name from a URI like 'failure:Hallucination' -> 'Hallucination'"
  [uri]
  (when uri
    (if (str/includes? uri ":")
      (second (str/split uri #":" 2))
      uri)))

(defn- uri->prefix
  "Extract prefix from a URI like 'failure:Hallucination' -> 'failure'"
  [uri]
  (when uri
    (if (str/includes? uri ":")
      (first (str/split uri #":" 2))
      nil)))

(defn- escape-turtle-string
  "Escape special characters in Turtle string literals."
  [s]
  (when s
    (-> s
        (str/replace "\\" "\\\\")
        (str/replace "\"" "\\\"")
        (str/replace "\n" "\\n")
        (str/replace "\r" "\\r")
        (str/replace "\t" "\\t"))))

(defn- concept->turtle
  "Serialize a single concept to Turtle triples.
   Returns a string of Turtle statements."
  [{:keys [uri label description scope broader narrower related indicators]}]
  (let [prefix (or (uri->prefix uri) "ex")
        local-name (uri->local-name uri)]
    (str
     prefix ":" local-name " a skos:Concept ;\n"
     "  skos:prefLabel \"" (escape-turtle-string label) "\"@en ;\n"
     (when (seq description)
       (str "  skos:definition \"" (escape-turtle-string description) "\"@en ;\n"))
     (when scope
       (str "  skos:scopeNote \"" (name scope) "\"@en ;\n"))
     (when (seq broader)
       (str "  skos:broader "
            (str/join " , " (map #(let [p (or (uri->prefix %) "ex")
                                        n (uri->local-name %)]
                                   (str p ":" n))
                                 broader))
            " ;\n"))
     (when (seq narrower)
       (str "  skos:narrower "
            (str/join " , " (map #(let [p (or (uri->prefix %) "ex")
                                        n (uri->local-name %)]
                                   (str p ":" n))
                                 narrower))
            " ;\n"))
     (when (seq related)
       (str "  skos:related "
            (str/join " , " (map #(let [p (or (uri->prefix %) "ex")
                                        n (uri->local-name %)]
                                   (str p ":" n))
                                 related))
            " ;\n"))
     (when (seq indicators)
       (str/join ""
                 (map #(str "  skos:hiddenLabel \"" (escape-turtle-string %) "\"@en ;\n")
                      indicators)))
     "  .\n")))

(defn serialization-concepts->turtle
  "Serialize a collection of concepts to SKOS Turtle format.

   Arguments:
   - concepts: Map of URI -> concept-map (from concepts read model)
   - opts: Options map with:
     - :base-uri - Base URI for the ontology
     - :include-scheme? - Whether to include ConceptScheme (default true)"
  [concepts & [{:keys [base-uri include-scheme?]
                :or {base-uri "http://obney.ai/workshop/ontology/"
                     include-scheme? true}}]]
  (let [concept-list (if (map? concepts) (vals concepts) concepts)
        prefixes-str (format-prefixes (all-prefixes))
        scheme-str (when include-scheme?
                     (str "\n# Concept Scheme\n"
                          "<" base-uri "scheme> a skos:ConceptScheme ;\n"
                          "  skos:prefLabel \"ObneyAI Workshop Ontology\"@en ;\n"
                          "  dcterms:description \"Three-layer ontology: Failure, Success, Problem Domain\"@en ;\n"
                          "  .\n"))
        concepts-str (str "\n# Concepts\n"
                          (str/join "\n" (map concept->turtle concept-list)))]
    (str prefixes-str "\n" (or scheme-str "") concepts-str)))

;; --- Interface ---

(defn interface-concepts->turtle
  "Serialize concepts to SKOS Turtle format.
   Arguments:
   - concepts: Map of URI -> concept-map
   - opts: {:base-uri \"...\" :include-scheme? true/false}"
  [concepts & [opts]]
  (serialization-concepts->turtle concepts opts))

;; --- Serialization ---

(defn- sanitize-local-name
  "Convert a label or URI fragment to a valid local name.
   Removes non-alphanumeric characters and ensures valid identifier."
  [s]
  (when s
    (-> s
        (str/replace #"[^a-zA-Z0-9_-]" "")
        (str/replace #"^(\d)" "_$1"))))

(defn- strength->turtle
  "Serialize a tree strength as OWL individual."
  [tree-uri {:keys [pattern confidence evidence-count avg-score]}]
  (let [pattern-local (sanitize-local-name (uri->local-name pattern))]
    (str "tree:" tree-uri "_strength_" pattern-local " a tree:Strength ;\n"
         "  tree:forTree tree:" tree-uri " ;\n"
         "  tree:pattern " (or (uri->prefix pattern) "pattern") ":" (uri->local-name pattern) " ;\n"
         "  tree:confidence \"" confidence "\"^^xsd:double ;\n"
         (when evidence-count
           (str "  tree:evidenceCount \"" evidence-count "\"^^xsd:integer ;\n"))
         (when avg-score
           (str "  tree:avgScore \"" avg-score "\"^^xsd:double ;\n"))
         "  .\n")))

(defn- weakness->turtle
  "Serialize a tree weakness as OWL individual."
  [tree-uri {:keys [failure subtype frequency severity triggers]} idx]
  (let [failure-local (sanitize-local-name (uri->local-name failure))]
    (str "tree:" tree-uri "_weakness_" failure-local "_" idx " a tree:Weakness ;\n"
         "  tree:forTree tree:" tree-uri " ;\n"
         "  tree:failureType failure:" (uri->local-name failure) " ;\n"
         (when subtype
           (str "  tree:failureSubtype failure:" (uri->local-name subtype) " ;\n"))
         "  tree:frequency \"" frequency "\"^^xsd:double ;\n"
         "  tree:severity \"" (name severity) "\" ;\n"
         (when (seq triggers)
           (str "  tree:triggers \""
                (escape-turtle-string (str/join ", " triggers))
                "\" ;\n"))
         "  .\n")))

(defn- problem-mapping->turtle
  "Serialize a problem mapping as OWL individual."
  [tree-uri {:keys [problem-uri success-rate execution-count]}]
  (let [problem-local (sanitize-local-name (uri->local-name problem-uri))]
    (str "tree:" tree-uri "_solves_" problem-local " a tree:ProblemMapping ;\n"
         "  tree:forTree tree:" tree-uri " ;\n"
         "  tree:problemType problem:" (uri->local-name problem-uri) " ;\n"
         "  tree:successRate \"" success-rate "\"^^xsd:double ;\n"
         "  tree:executionCount \"" execution-count "\"^^xsd:integer ;\n"
         "  .\n")))

(defn serialization-tree-profile->turtle
  "Serialize a tree profile to OWL Turtle format.

   Arguments:
   - profile: Tree profile map from tree-profiles read model
   - opts: Options map (reserved for future use)"
  [{:keys [tree-id strengths weaknesses solves domain-knowledge]} & [_opts]]
  (let [tree-uri (str tree-id)
        tree-str (str "# Tree Profile: " tree-id "\n"
                      "tree:" tree-uri " a tree:TreeProfile ;\n"
                      "  rdfs:label \"Tree " tree-uri "\" ;\n"
                      "  .\n\n")
        strengths-str (when (seq strengths)
                        (str "# Strengths\n"
                             (str/join "\n"
                                       (map #(strength->turtle tree-uri %) strengths))
                             "\n"))
        weaknesses-str (when (seq weaknesses)
                         (str "# Weaknesses\n"
                              (str/join "\n"
                                        (map-indexed (fn [idx w]
                                                       (weakness->turtle tree-uri w idx))
                                                     weaknesses))
                              "\n"))
        mappings-str (when (seq solves)
                       (str "# Problem Mappings\n"
                            (str/join "\n"
                                      (map #(problem-mapping->turtle tree-uri %) solves))
                            "\n"))]
    (str tree-str
         (or strengths-str "")
         (or weaknesses-str "")
         (or mappings-str ""))))

;; --- Interface ---

(defn interface-tree-profile->turtle
  "Serialize a single tree profile to OWL Turtle format."
  [profile & [opts]]
  (serialization-tree-profile->turtle profile opts))

;; --- Serialization ---

(defn serialization-tree-profiles->turtle
  "Serialize multiple tree profiles to OWL Turtle format."
  [profiles & [opts]]
  (let [prefixes-str (format-prefixes (all-prefixes))
        ontology-decl (str "\n# Tree Profile Ontology\n"
                           "tree:TreeProfile a owl:Class ;\n"
                           "  rdfs:label \"Tree Profile\" ;\n"
                           "  rdfs:comment \"Profile capturing a tree's strengths, weaknesses, and capabilities\" .\n\n"
                           "tree:Strength a owl:Class ;\n"
                           "  rdfs:label \"Strength\" .\n\n"
                           "tree:Weakness a owl:Class ;\n"
                           "  rdfs:label \"Weakness\" .\n\n"
                           "tree:ProblemMapping a owl:Class ;\n"
                           "  rdfs:label \"Problem Mapping\" .\n\n")
        profiles-str (str/join "\n"
                               (map #(serialization-tree-profile->turtle %) (vals profiles)))]
    (str prefixes-str "\n" ontology-decl profiles-str)))

;; --- Interface ---

(defn interface-tree-profiles->turtle
  "Serialize multiple tree profiles to OWL Turtle format."
  [profiles & [opts]]
  (serialization-tree-profiles->turtle profiles opts))

;; --- Serialization ---

(defn- node-pattern->turtle
  "Serialize a learned pattern as OWL individual."
  [node-type pattern-type effective? {:keys [pattern metrics evidence-count]} idx]
  (let [id (str (name node-type) "_" (name pattern-type) "_"
                (if effective? "eff" "ineff") "_" idx)]
    (str "pattern:" id " a pattern:LearnedPattern ;\n"
         "  pattern:nodeType \"" (name node-type) "\" ;\n"
         "  pattern:patternType \"" (name pattern-type) "\" ;\n"
         "  pattern:effective " (if effective? "true" "false") " ;\n"
         "  pattern:description \"" (escape-turtle-string pattern) "\" ;\n"
         (when evidence-count
           (str "  pattern:evidenceCount \"" evidence-count "\"^^xsd:integer ;\n"))
         (when-let [sr (:success-rate metrics)]
           (str "  pattern:successRate \"" sr "\"^^xsd:double ;\n"))
         (when-let [avg (:avg-score metrics)]
           (str "  pattern:avgScore \"" avg "\"^^xsd:double ;\n"))
         "  .\n")))

(defn serialization-node-experiences->turtle
  "Serialize node learning experiences to OWL Turtle format.

   Arguments:
   - experiences: Map from node-experiences read model"
  [experiences & [_opts]]
  (let [prefixes-str (format-prefixes (all-prefixes))
        ontology-decl (str "\n# Pattern Learning Ontology\n"
                           "pattern:LearnedPattern a owl:Class ;\n"
                           "  rdfs:label \"Learned Pattern\" ;\n"
                           "  rdfs:comment \"A pattern learned from node execution traces\" .\n\n")
        patterns-str
        (str/join "\n"
                  (for [[node-type pattern-types] experiences
                        [pattern-type {:keys [effective ineffective]}] pattern-types
                        :let [eff-patterns (map-indexed
                                            (fn [idx p]
                                              (node-pattern->turtle node-type pattern-type true p idx))
                                            (or effective []))
                              ineff-patterns (map-indexed
                                              (fn [idx p]
                                                (node-pattern->turtle node-type pattern-type false p idx))
                                              (or ineffective []))]]
                    (str/join "\n" (concat eff-patterns ineff-patterns))))]
    (str prefixes-str "\n" ontology-decl patterns-str)))

;; --- Interface ---

(defn interface-node-experiences->turtle
  "Serialize node learning experiences to OWL Turtle format."
  [experiences & [opts]]
  (serialization-node-experiences->turtle experiences opts))

;; --- Static ontology ---

(def static-ontology-dimension->failure-uri
  "Maps evaluation dimension names to root failure concept URIs."
  {"Grounding" "failure:Grounding"
   "Source Grounding" "failure:Grounding"
   "Instruction Following" "failure:InstructionFollowing"
   "Reasoning" "failure:Reasoning"
   "Reasoning Quality" "failure:Reasoning"
   "Completeness" "failure:Completeness"})

;; --- Classifier ---

(def classifier-dimension->failure-uri
  "Maps evaluation dimension names to root failure concept URIs. Delegates to
   the static ontology's dictionary so this classifier and
   static/get-failure-concept-for-dimension cannot drift apart (RR-32)."
  static-ontology-dimension->failure-uri)

;; --- Static ontology ---

(defn static-ontology-get-narrower-concepts
  "Get all concepts that have the given URI as a broader concept."
  [broader-uri]
  (filter #(some #{broader-uri} (:broader %)) (get-all-static-concepts)))

;; --- Classifier ---

(def subtype-indicators
  "Maps failure subtypes to their indicator patterns.
   Indicators are phrases that suggest this specific failure type."
  {
   "failure:Hallucination" ["hallucinated" "made up" "invented" "fabricated" "not in source"
                            "no evidence" "unsupported claim" "imagined"]
   "failure:FactHallucination" ["wrong number" "incorrect date" "false claim" "wrong fact"
                                "incorrect value" "made up statistic"]
   "failure:RelationshipHallucination" ["no relationship" "invented connection" "false link"
                                        "wrong relationship" "doesn't connect"]
   "failure:Contradiction" ["contradicts" "opposite" "inconsistent" "conflicts with"
                            "disagrees with" "contrary to"]
   "failure:Misattribution" ["wrong source" "misattributed" "credited to wrong"
                             "incorrect attribution" "wrong author"]

   "failure:FormatViolation" ["wrong format" "invalid JSON" "not markdown" "malformed"
                              "invalid syntax" "format error" "parse error"]
   "failure:ConstraintViolation" ["too long" "too short" "exceeded limit" "out of range"
                                  "over limit" "under limit" "word count"]
   "failure:RequirementMissed" ["missing required" "did not include" "omitted required"
                                "forgot to include" "missing mandatory"]
   "failure:ScopeViolation" ["out of scope" "beyond request" "unasked for" "off topic"
                             "irrelevant" "not requested"]

   "failure:LogicalGap" ["missing step" "doesn't follow" "unexplained leap" "gap in logic"
                         "skipped reasoning" "unclear connection"]
   "failure:UnjustifiedLeap" ["unjustified" "no support" "leap in logic" "unsupported conclusion"
                              "baseless claim" "unsubstantiated"]
   "failure:CircularReasoning" ["circular" "tautology" "assumes conclusion" "begging the question"
                                "self-referential" "proves itself"]
   "failure:FalseEquivalence" ["false equivalence" "not comparable" "unlike comparison"
                               "apples and oranges" "incomparable"]

   "failure:MissingEntity" ["missing" "omitted" "not included" "left out" "forgot"
                            "skipped" "absent"]
   "failure:InsufficientDetail" ["too brief" "lacks detail" "superficial" "shallow"
                                 "not enough detail" "needs more"]
   "failure:TruncatedOutput" ["truncated" "cut off" "incomplete" "ended abruptly"
                              "stopped early" "unfinished"]
   "failure:PartialCoverage" ["partial" "only covered" "missed aspects" "incomplete coverage"
                              "didn't address"]})

(defn- score-indicator-match
  "Score how well feedback text matches a failure subtype's indicators.
   Returns a score between 0 and 1."
  [feedback indicators]
  (when (and (seq feedback) (seq indicators))
    (let [feedback-lower (str/lower-case feedback)
          matches (filter #(str/includes? feedback-lower (str/lower-case %)) indicators)
          match-count (count matches)]
      (if (pos? match-count)
        (min 1.0 (* match-count 0.3))
        0.0))))

(defn find-specific-subtype
  "Find the most specific failure subtype based on feedback text.
   Returns the subtype URI or nil if no specific subtype matches."
  [base-failure-uri feedback]
  (when (and base-failure-uri (seq feedback))
    (let [narrower-uris (map :uri (static-ontology-get-narrower-concepts base-failure-uri))
          all-subtypes (concat narrower-uris
                               (mapcat #(map :uri (static-ontology-get-narrower-concepts %)) narrower-uris))
          scored (->> all-subtypes
                      (map (fn [uri]
                             (when-let [indicators (get subtype-indicators uri)]
                               {:uri uri
                                :score (score-indicator-match feedback indicators)})))
                      (filter some?)
                      (filter #(pos? (:score %)))
                      (sort-by :score >))]
      (when-let [best (first scored)]
        (when (>= (:score best) 0.3)
          (:uri best))))))

(defn- dimension->confidence
  "Convert dimension score to failure confidence.
   Lower scores = higher confidence in the failure."
  [score threshold]
  (if (< score threshold)
    (- 1.0 (/ score threshold))
    0.0))

(defn classifier-classify-evaluation
  "Classify evaluation results into failure ontology URIs.

   Arguments:
   - evaluation-result: Map with :score and :dimensions
     - :score - Overall score (0.0-1.0)
     - :dimensions - Vector of {:name :score :feedback}

   Options:
   - :threshold - Score below which to flag as failure (default 0.7)
   - :min-confidence - Minimum confidence to include (default 0.1)

   Returns:
   {:failures [{:uri \"failure:Hallucination\"
                :confidence 0.8
                :evidence \"feedback text\"
                :dimension \"Grounding\"}]
    :primary-failure-uri \"failure:Hallucination\"
    :overall-score 0.5}"
  [evaluation-result & [{:keys [threshold min-confidence]
                         :or {threshold 0.7
                              min-confidence 0.1}}]]
  (let [{:keys [score dimensions]} evaluation-result
        failures (->> dimensions
                      (filter #(< (:score %) threshold))
                      (filter #(contains? classifier-dimension->failure-uri (:name %)))
                      (map (fn [{:keys [name score feedback]}]
                             (let [base-uri (get classifier-dimension->failure-uri name)
                                   confidence (dimension->confidence score threshold)
                                   subtype (find-specific-subtype base-uri feedback)]
                               {:uri (or subtype base-uri)
                                :base-uri base-uri
                                :subtype-uri subtype
                                :confidence confidence
                                :evidence feedback
                                :dimension name
                                :dimension-score score})))
                      (filter #(>= (:confidence %) min-confidence))
                      (sort-by :confidence >)
                      vec)]
    {:failures failures
     :primary-failure-uri (some-> failures first :uri)
     :overall-score score
     :threshold threshold}))

;; --- Interface ---

(defn interface-classify-evaluation
  "Classify evaluation results into failure ontology URIs.

   Arguments:
   - evaluation-result: Map with :score and :dimensions
     - :score - Overall score (0.0-1.0)
     - :dimensions - Vector of {:name :score :feedback}

   Options:
   - :threshold - Score below which to flag as failure (default 0.7)
   - :min-confidence - Minimum confidence to include (default 0.1)

   Returns:
   {:failures [{:uri :confidence :evidence :dimension}]
    :primary-failure-uri \"failure:Hallucination\"
    :overall-score 0.5}"
  [evaluation-result & [opts]]
  (classifier-classify-evaluation evaluation-result opts))

;; --- Classifier ---

(defn classifier-classify-trace-evaluations
  "Classify multiple trace evaluations, grouping failures by type.
   Useful for aggregating patterns across many executions.

   Arguments:
   - evaluations: Seq of {:trace-id :evaluation-result}

   Returns:
   {:by-failure {\"failure:Hallucination\" [{:trace-id ... :confidence ...}]}
    :summary {:total-traces N :failed-traces N :by-dimension {...}}}"
  [evaluations & [opts]]
  (let [classified (map (fn [{:keys [trace-id evaluation-result]}]
                          {:trace-id trace-id
                           :classification (classifier-classify-evaluation evaluation-result opts)})
                        evaluations)
        by-failure (->> classified
                        (mapcat (fn [{:keys [trace-id classification]}]
                                  (map (fn [f]
                                         (assoc f :trace-id trace-id))
                                       (:failures classification))))
                        (group-by :uri))
        total (count classified)
        failed (count (filter #(seq (-> % :classification :failures)) classified))
        by-dimension (->> classified
                          (mapcat #(-> % :classification :failures))
                          (group-by :dimension)
                          (map (fn [[dim fs]]
                                 [dim {:count (count fs)
                                       :avg-confidence (/ (reduce + (map :confidence fs)) (count fs))}]))
                          (into {}))]
    {:by-failure by-failure
     :summary {:total-traces total
               :failed-traces failed
               :failure-rate (when (pos? total) (double (/ failed total)))
               :by-dimension by-dimension}}))

;; --- Interface ---

(defn interface-classify-trace-evaluations
  "Classify multiple trace evaluations, grouping failures by type."
  [evaluations & [opts]]
  (classifier-classify-trace-evaluations evaluations opts))

;; --- Interface ---

(defn interface-bfs-spreading-activation
  "BFS traversal with exponential decay activation scores.

   Algorithm:
   1. Initialize seeds with activation 1.0
   2. Process nodes level by level (BFS)
   3. For each node, propagate to neighbors:
      neighbor_activation = current_activation × edge_weight × decay^depth
   4. Stop when activation < min_activation or depth > max_depth
   5. Return all visited nodes sorted by activation score

   Args:
     graph: Graph map with :nodes and :edges (or concept map to convert)
     seed-uris: Collection of starting URIs
     config: {:max-depth N :decay N :min-activation N :max-results N}

   Returns:
     Vector of {:uri :score :path :depth :source-seed :edge-type}"
  [graph-or-concepts seed-uris & [config]]
  (let [g (if (contains? graph-or-concepts :edges)
            graph-or-concepts
            (graph/concepts->graph graph-or-concepts))]
    (graph/bfs-spreading-activation g seed-uris config)))

;; --- Interface ---

(defn interface-link-expansion
  "Fast 2-3 hop expansion for real-time suggestions.

   Use for: autocomplete, quick suggestions, low-latency scenarios

   Args:
     graph: Graph map with :nodes and :edges (or concept map)
     seed-uris: Collection of starting URIs
     opts: {:hops N} - Number of expansion hops (1-3)

   Returns:
     Vector of {:uri :score :path :depth}"
  [graph-or-concepts seed-uris & {:keys [hops] :or {hops 2}}]
  (let [g (if (contains? graph-or-concepts :edges)
            graph-or-concepts
            (graph/concepts->graph graph-or-concepts))]
    (graph/link-expansion g seed-uris :hops hops)))

;; --- Interface ---

(defn interface-compute-rrf-scores
  "Reciprocal Rank Fusion for merging multiple ranked result lists.

   Formula: score(d) = sum(1 / (k + rank_i))
   where k = 60 (per original RRF paper)

   Args:
     batches: Vector of result batches, each batch is vector of {:uri :score}
     opts: {:k N} - RRF constant (default 60)

   Returns:
     Vector of [uri fused-score] sorted by fused score descending"
  [batches & {:keys [k] :or {k 60}}]
  (graph/compute-rrf-scores batches :k k))

;; --- Interface ---

(defn interface-merge-batches
  "Merge multiple search batches using RRF or weighted-sum fusion.

   Args:
     batches: Vector of result batches
     opts: {:method \"rrf\"/\"weighted-sum\" :weights [...]}

   Returns:
     Vector of {:uri :score :sources}"
  [batches & {:keys [method weights] :or {method "rrf"}}]
  (graph/merge-batches batches :method method :weights weights))

;; --- Interface ---

(defn interface-quick-search
  "Quick graph search without full ORC workflow.

   Args:
     graph: Graph map or concept map
     seed-uris: Vector of seed URIs
     opts: {:strategy \"bfs\"/\"link_expansion\" :max-depth N :decay N :hops N}

   Returns:
     Vector of {:uri :score :path :depth}"
  [graph seed-uris & {:keys [strategy max-depth decay hops]
                      :or {strategy "bfs" max-depth 3 decay 0.5 hops 2}}]
  (graph/quick-search graph seed-uris
                      :strategy strategy :max-depth max-depth :decay decay :hops hops))

;; --- Static ontology ---

(defn get-all-static-relationships
  "Get all relationships from all static ontologies."
  []
  (concat (:relationships FAILURE_ONTOLOGY_CORE)
          (:relationships SUCCESS_ONTOLOGY_CORE)
          (:relationships PROBLEM_ONTOLOGY_CORE)))

;; --- Retrieval ---

(defn build-static-concept-graph
  "Build concept graph from static ontology only (no event store required)."
  []
  (let [all-concepts (get-all-static-concepts)
        all-relationships (get-all-static-relationships)

        concept-map (reduce (fn [acc c]
                              (assoc acc (:uri c)
                                     {:uri (:uri c)
                                      :label (:label c)
                                      :scope (:scope c)
                                      :broader (set (or (:broader c) []))
                                      :narrower #{}
                                      :related #{}}))
                            {}
                            all-concepts)

        concept-map (reduce (fn [acc c]
                              (reduce (fn [a broader-uri]
                                        (update-in a [broader-uri :narrower]
                                                   (fnil conj #{}) (:uri c)))
                                      acc
                                      (or (:broader c) [])))
                            concept-map
                            all-concepts)

        concept-map (reduce (fn [acc {:keys [source target predicate]}]
                              (case predicate
                                "skos:related"
                                (-> acc
                                    (update-in [source :related] (fnil conj #{}) target)
                                    (update-in [target :related] (fnil conj #{}) source))
                                acc))
                            concept-map
                            all-relationships)]

    (graph/concepts->graph concept-map)))

(defn retrieval-expand-concept-neighborhood
  "Expand from seed concept URIs using BFS spreading activation.

   Args:
     seed-uris: Collection of starting concept URIs
     opts: {:max-depth N :decay N :graph graph} - optional graph, otherwise uses static

   Returns:
     Vector of {:uri :score :path :depth} sorted by score"
  [seed-uris & {:keys [max-depth decay graph]
                :or {max-depth 2 decay 0.6}}]
  (let [g (or graph (build-static-concept-graph))]
    (graph/bfs-spreading-activation g seed-uris
                                    {:max-depth max-depth
                                     :decay decay
                                     :min-activation 0.01})))

;; --- Interface ---

(defn interface-expand-concept-neighborhood
  "Expand from seed concept URIs using BFS spreading activation.

   Args:
     seed-uris: Collection of starting concept URIs
     opts: {:max-depth N :decay N :graph graph}

   Returns:
     Vector of {:uri :score :path :depth} sorted by score"
  [seed-uris & {:keys [max-depth decay graph]
                :or {max-depth 2 decay 0.6}}]
  (retrieval-expand-concept-neighborhood seed-uris
                                         :max-depth max-depth
                                         :decay decay
                                         :graph graph))

;; --- Interface ---

(defn interface-compute-temporal-relevance
  "Compute temporal relevance score.

   Returns:
   - 1.0 for current year
   - 1.1 for future years (slight boost)
   - Decaying score for past years (min 0.1)
   - 0.5 for entities without temporal info"
  [entity-year reference-year & {:keys [decay-per-year] :or {decay-per-year 0.15}}]
  (graph/compute-temporal-relevance entity-year reference-year
                                    :decay-per-year decay-per-year))

;; --- Interface ---

(def interface-concept-events
  "Event types that affect the concept graph."
  ontology-lib/graph-event-types)

;; --- Read models ---

(def read-models-tree-profile-events
  "Events that affect tree profile read model"
  #{:ontology/tree-strength-recorded
    :ontology/tree-weakness-recorded
    :ontology/tree-problem-mapping-created
    :ontology/tree-problem-mapping-updated
    :ontology/domain-knowledge-added})

;; --- Interface ---

(def interface-tree-profile-events
  "Event types that affect tree profiles."
  read-models-tree-profile-events)

;; --- Read models ---

(def read-models-node-learning-events
  "Events that affect node-level learning read model"
  #{:ontology/node-pattern-learned})

;; --- Interface ---

(def interface-node-learning-events
  "Event types that affect node learning."
  read-models-node-learning-events)

;; --- Read models ---

(def ontology-events
  "Events that affect the ontology lifecycle"
  #{:ontology/ontology-created})

(def discovery-events
  "Events for ontology discovery/extension"
  #{:ontology/failure-subtype-discovered})

(def read-models-all-ontology-events
  "All events for full ontology reconstruction"
  (set/union ontology-events
             ontology-lib/graph-event-types
             read-models-tree-profile-events
             read-models-node-learning-events
             discovery-events))

;; --- Interface ---

(def interface-all-ontology-events
  "All ontology-related event types."
  read-models-all-ontology-events)

;; --- Read models ---

(def read-models-embedding-events
  "Events that affect embedding read models"
  #{:ontology/tree-profile-embedded
    :ontology/evaluation-embedded
    :ontology/embedding-model-configured})

;; --- Interface ---

(def interface-embedding-events
  "Event types that affect embedding read models."
  read-models-embedding-events)

;; --- Embedding ---

(def default-model-id
  "Default embedding model - all-MiniLM-L6-v2 produces 384-dim embeddings."
  "sentence-transformers/all-MiniLM-L6-v2")

(defonce ^{:private true :doc "Map of model-id -> loaded DJL model. Thread-safe atom."}
  embedding-models
  (atom {}))

(defn- load-embedding-model-internal
  "Load a DJL embedding model by model-id.

   Args:
     model-id: HuggingFace model identifier (e.g., 'sentence-transformers/all-MiniLM-L6-v2')

   Returns:
     Loaded DJL ZooModel"
  [model-id]
  (println "[DJL] Loading embedding model:" model-id)
  (let [model-url (str "djl://ai.djl.huggingface.pytorch/" model-id)
        criteria (-> (Criteria/builder)
                     (.setTypes String (Class/forName "[F"))
                     (.optModelUrls model-url)
                     (.optEngine "PyTorch")
                     (.optTranslatorFactory (TextEmbeddingTranslatorFactory.))
                     (.build))
        model (.loadModel criteria)]
    (println "[DJL] Model loaded successfully:" model-id)
    model))

(defn embedding-get-embedding-model
  "Get an embedding model by ID, loading it if necessary.

   Thread-safe with double-checked locking pattern.

   Args:
     model-id: Optional model identifier (default: all-MiniLM-L6-v2)

   Returns:
     Loaded DJL ZooModel"
  ([]
   (embedding-get-embedding-model default-model-id))
  ([model-id]
   (if-let [model (get @embedding-models model-id)]
     model
     (locking embedding-models
       (if-let [model (get @embedding-models model-id)]
         model
         (let [model (load-embedding-model-internal model-id)]
           (swap! embedding-models assoc model-id model)
           model))))))

(defn embedding-embed-text
  "Generate an embedding vector for text.

   Args:
     text: String to embed
     opts: Optional map with :model-id (default: all-MiniLM-L6-v2)

   Returns:
     Vector of doubles (384 dimensions for default model), or nil on error."
  ([text]
   (embedding-embed-text text {}))
  ([text {:keys [model-id] :or {model-id default-model-id}}]
   (when (and text (not (str/blank? text)))
     (try
       (let [model (embedding-get-embedding-model model-id)]
         (with-open [predictor (.newPredictor model)]
           (mapv double (.predict predictor text))))
       (catch Exception e
         (println "[DJL] Error generating embedding:" (.getMessage e))
         nil)))))

;; --- Interface ---

(defn interface-embed-text
  "Generate an embedding vector for text.

   Args:
     text: String to embed
     opts: {:model-id \"sentence-transformers/all-MiniLM-L6-v2\"}

   Returns:
     Vector of doubles (384 dimensions for default model), or nil on error."
  [text & [opts]]
  (embedding-embed-text text (or opts {})))

;; --- Embedding ---

(defn embedding-embed-texts-batch
  "Generate embeddings for multiple texts.

   Args:
     texts: Collection of strings to embed
     opts: Optional map with :model-id

   Returns:
     Vector of embedding vectors (same order as input)"
  ([texts]
   (embedding-embed-texts-batch texts {}))
  ([texts opts]
   (when (seq texts)
     (println "[DJL] Generating embeddings for" (count texts) "texts")
     (mapv #(embedding-embed-text % opts) texts))))

;; --- Interface ---

(defn interface-embed-texts-batch
  "Generate embeddings for multiple texts.

   Args:
     texts: Collection of strings to embed
     opts: {:model-id \"...\"}

   Returns:
     Vector of embedding vectors"
  [texts & [opts]]
  (embedding-embed-texts-batch texts (or opts {})))

;; --- Embedding ---

(defn embedding-concept->embedding-text
  "Convert a concept to text suitable for embedding.

   Combines specified fields into a single text string.

   Args:
     concept: Concept map with :label, :description, :indicators, etc.
     fields: Set of field keywords to include (default: #{:label :description})

   Returns:
     Combined text string for embedding"
  ([concept]
   (embedding-concept->embedding-text concept #{:label :description}))
  ([concept fields]
   (let [parts (remove str/blank?
                       [(when (contains? fields :label)
                          (:label concept))
                        (when (contains? fields :description)
                          (:description concept))
                        (when (and (contains? fields :indicators)
                                   (seq (:indicators concept)))
                          (str "Indicators: " (str/join ", " (:indicators concept))))
                        (when (and (contains? fields :triggers)
                                   (seq (:triggers concept)))
                          (str "Triggers: " (str/join ", " (:triggers concept))))])]
     (str/join ". " parts))))

;; --- Interface ---

(defn interface-concept->embedding-text
  "Convert a concept to text suitable for embedding.

   Combines specified fields into a single text string.

   Args:
     concept: Concept map with :label, :description, :indicators, etc.
     fields: Set of field keywords to include (default: #{:label :description})

   Returns:
     Combined text string for embedding"
  [concept & [fields]]
  (embedding-concept->embedding-text concept (or fields #{:label :description})))

(defn interface-get-embedding-model
  "Get an embedding model by ID, loading it if necessary.

   Thread-safe with double-checked locking pattern.

   Args:
     model-id: Optional model identifier (default: all-MiniLM-L6-v2)

   Returns:
     Loaded DJL ZooModel"
  [& [model-id]]
  (if model-id
    (embedding-get-embedding-model model-id)
    (embedding-get-embedding-model)))

;; --- Field analyzer workflow ---

(def ^:private sampled-field-value-schema
  [:or :string :int :double :boolean :keyword :uuid :inst
   [:vector [:or :string :int :double :boolean :keyword :uuid]]])

(def ^:private sample-record-schema
  [:map-of :keyword sampled-field-value-schema])

(def ^:private schema-form-schema
  [:or :keyword
   [:vector [:or :keyword :string
             [:map-of :keyword [:or :string :boolean]]
             [:vector :keyword]]]])

(def field-analyzer-workflow
  "ORC workflow for LLM-driven field embedding analysis.

   This workflow takes a Malli schema and optional sample data,
   then uses an LLM to determine which fields contain semantic content
   worth embedding for similarity search.

   Input keys:
   - schema: Malli [:map ...] schema to analyze
   - sample-data: Vector of sample records matching the schema (recommended: 3-5)
   - confidence-threshold: Minimum confidence to include (default: 0.7)

   Output keys:
   - embeddable-fields: Vector of field names recommended for embedding
   - confidence-scores: Map of field-name -> confidence score (0.0-1.0)
   - reasoning: Map of field-name -> explanation string"

  (orc/workflow "field-analyzer"

    ;; =========================================================================
    ;; BLACKBOARD SCHEMA
    ;; =========================================================================
    (orc/blackboard
      {
       ;; --- Inputs ---
       :schema schema-form-schema
       :sample-data [:vector sample-record-schema]
       :confidence-threshold [:double {:description "Minimum confidence to include a field (0.0-1.0)"}]

       ;; === Intermediate State ===
       :field-candidates [:vector [:map
                                   [:field-name :keyword]
                                   [:field-type :string]
                                   [:nullable :boolean]
                                   [:description {:optional true} :string]
                                   [:sample-values {:optional true}
                                    [:vector sampled-field-value-schema]]
                                   [:value-stats {:optional true} [:map
                                                                   [:sample-count {:optional true} :int]
                                                                   [:unique-count {:optional true} :int]
                                                                   [:avg-length {:optional true} :double]]]]]

       :llm-assessment [:map
                        [:fields [:vector [:map
                                           [:fieldName :string]
                                           [:shouldEmbed :boolean]
                                           [:confidence :double]
                                           [:reasoning :string]]]]]

       ;; === Outputs ===
       :embeddable-fields [:vector :keyword]
       :confidence-scores [:map-of :keyword :double]
       :reasoning [:map-of :keyword :string]})

    ;; =========================================================================
    ;; MAIN SEQUENCE
    ;; =========================================================================
    (orc/sequence "analyze"

      (orc/code "extract-metadata"
        :fn "ai.obney.orc.ontology.core.field-analyzer/extract-field-metadata"
        :reads [:schema :sample-data]
        :writes [:field-candidates])

      (orc/llm "assess-fields"
        :model "google/gemini-2.5-flash"
        :instruction "You are analyzing database fields to determine which ones contain semantic content worth embedding for similarity search.

For each field in the provided candidates, assess:

1. **Semantic Content**: Does the field contain natural language, meaningful text, or concepts that would benefit from semantic search? Consider the sample values.

2. **Embedding Value**: Would embedding this field help users find similar records? Text descriptions, narratives, and categorizations are good candidates. IDs, timestamps, and pure numbers are not.

3. **Field Type**: String fields and string vectors are candidates. UUIDs, dates, numbers, and enums typically are not worth embedding.

For EACH field, provide:
- fieldName: The field name as a string
- shouldEmbed: true if this field should be embedded, false otherwise
- confidence: Your confidence score from 0.0 to 1.0
- reasoning: Brief explanation of your decision

Examples of good embedding candidates:
- Description fields with narrative text
- Notes or comments with free-form content
- Summary or analysis fields
- Keyword or tag vectors

Examples of poor embedding candidates:
- ID fields (uuid, database IDs)
- Timestamps (created-at, updated-at)
- Status enums (active, pending, closed)
- Numeric metrics without context
- Email addresses, phone numbers, URLs

Return your assessment as a JSON object with a 'fields' array containing one assessment object per field."
        :reads [:field-candidates]
        :writes [:llm-assessment]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      (orc/code "filter-results"
        :fn "ai.obney.orc.ontology.core.field-analyzer/filter-embeddable-fields"
        :reads [:llm-assessment :confidence-threshold]
        :writes [:embeddable-fields :confidence-scores :reasoning]))))

(defn build-field-analyzer!
  "Build the field analyzer workflow in the given context.

   Returns the sheet-id for subsequent executions."
  [ctx]
  (orc/build-workflow! ctx field-analyzer-workflow))

(defn analyze-schema
  "Execute field analysis on a schema.

   Args:
     ctx: Application context with sheet service
     sheet-id: Pre-built sheet ID (or nil to build new)
     schema: Malli schema to analyze
     sample-data: Vector of sample records
     opts: {:confidence-threshold 0.7}

   Returns:
     {:embeddable-fields [:field1 :field2]
      :confidence-scores {...}
      :reasoning {...}
      :trace-id ...}"
  [ctx sheet-id schema sample-data & {:keys [confidence-threshold]
                                       :or {confidence-threshold 0.7}}]
  (let [sid (or sheet-id (build-field-analyzer! ctx))
        result (orc/execute ctx sid
                              {:schema schema
                               :sample-data sample-data
                               :confidence-threshold confidence-threshold})]
    (if (:success? result)
      {:embeddable-fields (get-in result [:outputs :embeddable-fields])
       :confidence-scores (get-in result [:outputs :confidence-scores])
       :reasoning (get-in result [:outputs :reasoning])
       :trace-id (:trace-id result)
       :method :llm}
      {:error (:error result)
       :trace-id (:trace-id result)})))

;; --- Interface ---

(defn analyze-fields-for-embedding
  "Explicitly analyze schema to determine embeddable fields using LLM.

   Unlike detect-embeddable-fields (heuristic-based), this uses an LLM
   to examine actual data samples and determine which fields contain
   semantic content worth embedding for similarity search.

   This is an EXPLICIT step - call it BEFORE embedding to get recommendations.
   Results can be reviewed, cached, or modified before use.

   Workflow:
   1. Call this function with schema + sample data
   2. Review the recommended fields and reasoning
   3. Optionally modify the field list
   4. Use embed-with-fields to embed data

   Args:
     ctx: Application context with sheet service
     schema: Malli [:map ...] schema to analyze
     opts:
       :sample-data - Sample records matching schema (recommended: 3-5)
       :confidence-threshold - Min confidence to include (default: 0.7)
       :sheet-id - Pre-built workflow sheet ID (optional, for reuse)

   Returns:
     {:embeddable-fields [:field1 :field2 ...]
      :confidence-scores {:field1 0.92 :field2 0.85}
      :reasoning {:field1 \"Contains natural language...\" ...}
      :method :llm
      :trace-id #uuid \"...\"}"
  [ctx schema & {:keys [sample-data confidence-threshold sheet-id]
                 :or {confidence-threshold 0.7}}]
  (analyze-schema ctx sheet-id schema (or sample-data [])
                               :confidence-threshold confidence-threshold))

;; --- Field analyzer ---

(defn- get-type-name
  "Get human-readable type name from Malli schema."
  [schema]
  (when schema
    (let [t (m/type schema)]
      (cond
        (= t :string) "string"
        (= t :int) "integer"
        (= t :double) "number"
        (= t :boolean) "boolean"
        (= t :keyword) "keyword"
        (= t :uuid) "uuid"
        (= t :any) "any"
        (= t :map) "map"
        (= t :vector) (str "vector<" (get-type-name (first (m/children schema))) ">")
        (= t :set) (str "set<" (get-type-name (first (m/children schema))) ">")
        (= t :enum) (str "enum" (vec (m/children schema)))
        (= t :maybe) (str "maybe<" (get-type-name (first (m/children schema))) ">")
        :else (str t)))))

(defn- get-field-description
  "Extract description from Malli schema properties."
  [schema]
  (when schema
    (let [props (m/properties schema)]
      (or (:description props) nil))))

(defn- extract-entry-info
  "Extract metadata from a single Malli schema entry."
  [[field-name props field-schema]]
  (let [actual-schema (if (map? props) field-schema props)
        is-optional (if (map? props) (:optional props) false)]
    {:field-name field-name
     :field-type (get-type-name actual-schema)
     :nullable is-optional
     :description (get-field-description actual-schema)}))

(defn- sample-field-values
  "Extract sample values for a field from data records.
   Returns up to max-samples unique values."
  [field-name records max-samples]
  (when (seq records)
    (->> records
         (map #(get % field-name))
         (remove nil?)
         (distinct)
         (take max-samples)
         vec)))

(defn- compute-value-stats
  "Compute statistics about sampled values."
  [values]
  (when (seq values)
    (let [string-values (filter string? values)]
      {:sample-count (count values)
       :unique-count (count (distinct values))
       :value-lengths (when (seq string-values)
                        (mapv count string-values))
       :avg-length (when (seq string-values)
                     (double (/ (reduce + (map count string-values))
                                (count string-values))))
       :all-strings? (= (count values) (count string-values))})))

(defn analyze-schema-fields
  "Analyze a Malli schema and extract field metadata with sample values.

   Args:
     schema: Malli [:map ...] schema
     sample-data: Collection of sample records (can be empty)
     opts: {:max-samples 5}

   Returns:
     Vector of field info maps, each containing:
     {:field-name :label
      :field-type \"string\"
      :nullable false
      :description \"User-provided label\"
      :sample-values [\"Acme Corp\" \"Beta Inc\"]
      :value-stats {:avg-length 8.5 :unique-count 2}}"
  [schema sample-data & {:keys [max-samples] :or {max-samples 5}}]
  (when (and schema (= :map (m/type schema)))
    (let [entries (m/entries schema)]
      (mapv (fn [entry]
              (let [base-info (extract-entry-info entry)
                    field-name (:field-name base-info)
                    samples (sample-field-values field-name sample-data max-samples)
                    stats (compute-value-stats samples)]
                (merge base-info
                       {:sample-values samples
                        :value-stats stats})))
            entries))))

(def ^:private likely-semantic-patterns
  "Regex patterns that suggest semantic content in field names."
  [#"(?i).*description.*"
   #"(?i).*summary.*"
   #"(?i).*content.*"
   #"(?i).*text.*"
   #"(?i).*message.*"
   #"(?i).*note.*"
   #"(?i).*comment.*"
   #"(?i).*feedback.*"
   #"(?i).*explanation.*"
   #"(?i).*reasoning.*"
   #"(?i).*narrative.*"
   #"(?i).*instruction.*"])

(def ^:private likely-non-semantic-patterns
  "Regex patterns that suggest non-semantic content."
  [#"(?i).*[-_]?id$"
   #"(?i).*[-_]?uuid$"
   #"(?i).*[-_]?at$"
   #"(?i).*[-_]?date$"
   #"(?i).*[-_]?time$"
   #"(?i).*[-_]?url$"
   #"(?i).*[-_]?uri$"
   #"(?i).*[-_]?email$"
   #"(?i).*[-_]?phone$"
   #"(?i)^status$"
   #"(?i)^type$"
   #"(?i)^version$"])

(defn heuristic-embeddable?
  "Apply heuristics to determine if a field is likely embeddable.
   Used as fallback when LLM unavailable."
  [{:keys [field-name field-type sample-values value-stats]}]
  (let [name-str (name field-name)]
    (cond
      (some #(re-matches % name-str) likely-non-semantic-patterns)
      {:should-embed false
       :confidence 0.8
       :reasoning "Field name matches non-semantic pattern"}

      (some #(re-matches % name-str) likely-semantic-patterns)
      {:should-embed true
       :confidence 0.8
       :reasoning "Field name matches semantic pattern"}

      (and (= field-type "string")
           (:avg-length value-stats)
           (> (:avg-length value-stats) 50))
      {:should-embed true
       :confidence 0.7
       :reasoning "String field with substantial average length"}

      (str/starts-with? (or field-type "") "vector<string>")
      {:should-embed true
       :confidence 0.6
       :reasoning "Vector of strings likely contains semantic content"}

      (not (or (= field-type "string")
               (str/starts-with? (or field-type "") "vector<string>")))
      {:should-embed false
       :confidence 0.9
       :reasoning "Non-string type not suitable for embedding"}

      :else
      {:should-embed false
       :confidence 0.3
       :reasoning "Unable to determine from heuristics alone"})))

(defn field-analyzer-detect-embeddable-fields-heuristic
  "Schema + data heuristic detection (no LLM).
   Fallback when LLM analysis is unavailable."
  [schema sample-data & {:keys [threshold] :or {threshold 0.6}}]
  (let [field-candidates (analyze-schema-fields schema sample-data)
        assessments (mapv (fn [fc]
                            (merge fc (heuristic-embeddable? fc)))
                          field-candidates)
        embeddable (->> assessments
                        (filter :should-embed)
                        (filter #(>= (:confidence %) threshold))
                        (sort-by :confidence >))]
    {:embeddable-fields (mapv :field-name embeddable)
     :confidence-scores (into {} (map (juxt :field-name :confidence) assessments))
     :reasoning (into {} (map (juxt :field-name :reasoning) assessments))
     :method :heuristic}))

;; --- Interface ---

(defn interface-detect-embeddable-fields-heuristic
  "Schema + data heuristic detection (no LLM).

   Use this when:
   - LLM analysis is not needed (simple/known schemas)
   - LLM is unavailable or too slow
   - Testing or development

   Args:
     schema: Malli schema to analyze
     sample-data: Optional sample records
     opts:
       :threshold - Min confidence (default: 0.6)

   Returns:
     {:embeddable-fields [...]
      :confidence-scores {...}
      :reasoning {...}
      :method :heuristic}"
  [schema & [sample-data opts]]
  (field-analyzer-detect-embeddable-fields-heuristic
   schema (or sample-data [])
   :threshold (or (:threshold opts) 0.6)))

;; --- Field analyzer ---

(defn combine-fields-for-embedding
  "Combine specified fields from a record into text for embedding.

   Args:
     record: Data record
     fields: Collection of field names (keywords) to include

   Returns:
     Combined text string suitable for embedding"
  [record fields]
  (->> fields
       (map (fn [field]
              (let [v (get record field)]
                (cond
                  (nil? v) nil
                  (string? v) v
                  (sequential? v) (str/join ", " v)
                  :else (pr-str v)))))
       (remove str/blank?)
       (str/join ". ")))

;; --- Interface ---

(defn embed-with-fields
  "Embed data using specified fields.

   Combines the values of specified fields into text and generates an embedding.

   Args:
     data: Record to embed
     fields: Collection of field names to include
     opts: {:model-id \"...\"}

   Returns:
     Embedding vector (384 dimensions for default model)"
  [data fields & {:keys [model-id] :as opts}]
  (let [text (combine-fields-for-embedding data fields)]
    (embedding-embed-text text (or opts {}))))

(defn analyze-and-embed
  "Convenience function: analyze schema and embed data in one call.

   For simple cases where you don't need to review/modify the field selection.
   Uses the hybrid approach: tries LLM analysis, falls back to heuristics.

   Args:
     ctx: Application context (or nil for heuristic-only)
     schema: Malli schema
     data: Record to embed
     opts:
       :sample-data - Sample records for analysis
       :confidence-threshold - Min confidence (default: 0.7)
       :fallback-to-heuristic - If true, use heuristics when LLM fails (default: true)

   Returns:
     {:embedding [...] (384-dim vector)
      :fields-used [...]
      :method :llm or :heuristic}"
  [ctx schema data & {:keys [sample-data confidence-threshold fallback-to-heuristic]
                      :or {confidence-threshold 0.7 fallback-to-heuristic true}}]
  (let [analysis (if ctx
                   (try
                     (analyze-fields-for-embedding ctx schema
                                                    :sample-data (or sample-data [data])
                                                    :confidence-threshold confidence-threshold)
                     (catch Exception e
                       (when fallback-to-heuristic
                         (interface-detect-embeddable-fields-heuristic schema [data]))))
                   (interface-detect-embeddable-fields-heuristic schema [data]))
        fields (:embeddable-fields analysis)
        emb (when (seq fields)
              (embed-with-fields data fields))]
    {:embedding emb
     :fields-used fields
     :method (:method analysis)}))

;; --- Read models ---

(def read-models-learned-rule-events
  "Events that affect the learned-rules read model"
  #{:ontology/learned-rule-extracted})

;; --- Interface ---

(def interface-learned-rule-events
  "Event types that affect the learned-rules read model"
  read-models-learned-rule-events)

;; --- Read models ---

(def read-models-site-registry-events
  "Events that affect site registry read model"
  #{:site/registered
    :site/trust-updated
    :site/pattern-learned})

;; --- Interface ---

(def interface-site-registry-events
  "Event types that affect site registry read model."
  read-models-site-registry-events)

;; --- Model pinning ---

(defn pin-model
  "Replace every explicitly model-backed node's model. With nil, preserve the
  workflow's production default. This operates on the DSL definition before
  it is built, so the pinned value becomes part of durable node metadata."
  [workflow model]
  (if-not model
    workflow
    (walk/postwalk
     (fn [form]
       (if (and (map? form) (contains? form :model))
         (assoc form :model model)
         form))
     workflow)))

;; --- Csv ontology ---

(def ^:private csv-cell-schema
  [:or :string :int :double :boolean])

(def ^:private csv-row-schema
  [:map-of :keyword csv-cell-schema])

(def ^:private definition-info-schema
  [:map
   [:definition :string]
   [:scope-note :string]
   [:external-alignments [:vector :string]]])

(def ^:private definition-result-schema
  [:map
   [:name :string]
   [:source_columns [:vector :string]]
   [:description :string]
   [:definition :string]
   [:scope-note :string]
   [:external-alignments [:vector :string]]])

(def ^:private hierarchy-result-schema
  [:map
   [:has-hierarchy :boolean]
   [:hierarchy-type [:enum "ordering" "containment" "specialization" "none"]]
   [:hierarchy-relationships [:vector [:map [:broader :string] [:narrower :string]]]]
   [:top-level [:vector :string]]
   [:reasoning :string]])

(def ^:private property-mapping-schema
  [:map
   [:column :string]
   [:suggested_name :string]
   [:property_type :string]
   [:description :string]])

(def csv-to-ontology-pipeline
  "Complete CSV-to-ontology pipeline with 6-phase enrichment.

   Maps 1:1 to Python CSVOntologyBuilder + CSVEnrichmentPipeline phases:
   1. Structure Analysis (code - rule-based)
   2. Schema Analysis (LLM: AnalyzeCSVSchema)
   3. Definition Generation (LLM: EnrichEntityDefinition per class)
   4. Hierarchy Enrichment (LLM: SuggestHierarchy per categorical column)
   5. Relationship Discovery (LLM: DiscoverImplicitRelationships)
   6. Quality Validation (LLM: DetectAmbiguity + ValidateColumnMapping)
   7-9. TBox/ABox construction and serialization (code)"

  (orc/workflow "csv-to-ontology"

    (orc/blackboard
      ;; === Inputs ===
      {:csv-path [:string {:description "Path to the CSV file to process"}]
       :csv-data [:vector {:description "Parsed CSV data as vector of maps"} csv-row-schema]
       :entity-column [:string {:description "Column to use as entity label/identifier"}]
       :entity-type [:string {:description "Class name for instances (e.g., 'Program', 'Student')"}]
       :base-uri [:string {:description "Ontology namespace URI (e.g., 'http://example.org/ontology#')"}]

       ;; === ChainOfThought Reasoning Fields ===
       :schema-reasoning [:string {:description "Step-by-step reasoning for analyzing CSV schema and identifying domain patterns"}]
       :definition-reasoning [:string {:description "Step-by-step reasoning for generating entity definitions"}]
       :relationship-reasoning [:string {:description "Step-by-step reasoning for discovering implicit relationships"}]
       :ambiguity-reasoning [:string {:description "Step-by-step reasoning for detecting ambiguous terms"}]

       :column-analysis [:vector {:description "Analysis of each CSV column with type detection and statistics"}
                         [:map
                          [:name :string]
                          [:column-type :string]
                          [:unique-count :int]
                          [:null-count :int]
                          [:sample-values [:vector :string]]
                          [:suggested-property :string]
                          [:is-fk-candidate :boolean]]]
       :detected-classes [:vector {:description "Class names detected from CSV structure"} :string]
       :detected-properties [:vector {:description "Properties detected from columns"}
                             [:map [:name :string] [:type :string] [:range :string]]]
       :detected-hierarchies [:vector {:description "Hierarchies detected from column relationships"}
                              [:map [:column :string] [:parent :string] [:child :string]]]
       :detected-foreign-keys [:vector {:description "Foreign key candidates detected"}
                               [:map [:from-column :string] [:to-table :string]]]

       :date-columns [:vector {:description "Columns containing date/temporal data"} :string]
       :has-temporal-data :boolean
       :temporal-entities [:vector {:description "Entities with extracted temporal metadata"}
                           [:map
                            [:entity-label :string]
                            [:effective-year [:maybe :int]]
                            [:start-date [:maybe :string]]
                            [:end-date [:maybe :string]]
                            [:temporal-type :string]]]

       :column-summary :string
       :sample-rows :string
       :detected-patterns :string
       :domain [:string {:description "The semantic domain this CSV represents (e.g., 'Higher Education Programs')"}]
       :domain-description [:string {:description "1-2 sentence description of the domain"}]
       :entities [:vector {:description "1-5 main entity types identified in the CSV data"}
                  [:map
                   [:name :string]
                   [:source_columns [:vector :string]]
                   [:description :string]]]
       :relationships [:vector {:description "Relationships between entity types"}
                       [:map
                        [:subject :string]
                        [:predicate :string]
                        [:object :string]
                        [:source_columns [:vector :string]]]]
       :property-mappings [:vector {:description "Mappings from CSV columns to ontology properties"}
                           [:map
                            [:column :string]
                            [:suggested_name :string]
                            [:property_type :string]
                            [:description :string]]]

       :current-entity [:map {:description "Entity currently being processed"}
                        [:name :string] [:source_columns [:vector :string]] [:description :string]]
       :entity-name :string
       :source-columns [:vector {:description "Source CSV columns for current entity"} :string]
       :sample-instances [:vector {:description "Sample values from entity column"} :string]
       :related-entities [:vector {:description "Other entity types related to current entity"} :string]
       :definition [:string {:description "Formal 2-3 sentence definition for the entity class"}]
       :scope-note [:string {:description "Usage guidance and boundaries for this concept"}]
       :external-alignments [:vector {:description "Alignments to standard ontologies (e.g., 'schema:Course')"} :string]
       :definition-results [:vector definition-result-schema]
       :entity-definitions [:map-of :string definition-info-schema]

       :current-column :string
       :categorical-columns [:vector {:description "Columns with categorical values"} :string]
       :column-name :string
       :unique-values [:vector {:description "Unique values in current categorical column"} :string]
       :value-counts :string
       :has-hierarchy [:boolean {:description "Whether these values have a natural hierarchy"}]
       :hierarchy-type [:enum {:description "Type of hierarchy: ordering, containment, specialization, or none"} "ordering" "containment" "specialization" "none"]
       :hierarchy-relationships [:vector {:description "Broader/narrower relationships between categorical values"}
                                 [:map
                                  [:broader :string]
                                  [:narrower :string]]]
       :top-level [:vector {:description "Root values with no broader concept"} :string]
       :reasoning [:string {:description "Explanation of the hierarchy structure"}]
       :hierarchy-results [:vector hierarchy-result-schema]
       :hierarchies [:map-of :string hierarchy-result-schema]

       :entities-info :string
       :sample-data :string
       :existing-relationships [:vector {:description "Already identified relationships"} :string]
       :discovered-relationships [:vector {:description "Implicit relationships discovered between entity types"}
                                  [:map
                                   [:subject :string]
                                   [:predicate :string]
                                   [:object :string]
                                   [:confidence :double]
                                   [:evidence :string]]]
       :inverse-relationships [:vector {:description "Suggested inverse property names"} :string]

       :terms-to-validate [:vector {:description "Terms to check for ambiguity"} :string]
       :existing-definitions [:map-of :string definition-info-schema]
       :mappings-to-validate [:vector property-mapping-schema]
       :ambiguous-terms [:vector {:description "Terms with multiple meanings or unclear scope"}
                         [:map
                          [:term :string]
                          [:issue :string]
                          [:suggested_clarification :string]]]
       :suggested-property :string
       :property-type :string
       :sample-values [:vector {:description "Sample values for validation"} :string]
       :is-valid :boolean
       :improved-name :string
       :corrected-type :string
       :issues [:vector {:description "Validation issues found"} :string]
       :mapping-issues [:vector [:map [:issue :string]]]

       :tbox [:map {:description "Ontology schema (classes and properties)"}
              [:classes [:vector [:map [:uri :string] [:label :string] [:comment :string]]]]
              [:object-properties [:vector [:map [:uri :string] [:domain :string] [:range :string]]]]
              [:datatype-properties [:vector [:map [:uri :string] [:domain :string] [:range :string]]]]]
       :abox [:vector {:description "Ontology instances"}
              [:map
               [:uri :string]
               [:type :string]
               [:label :string]
               [:properties [:map-of :string csv-cell-schema]]]]

       ;; === Output ===
       :owl-output :string
       :statistics [:map {:description "Conversion statistics"}
                    [:row-count :int]
                    [:column-count :int]
                    [:class-count :int]
                    [:property-count :int]
                    [:individual-count :int]
                    [:triple-count :int]]})

    ;; =========================================================================
    ;; MAIN PIPELINE SEQUENCE
    ;; =========================================================================
    (orc/sequence "csv-main-pipeline"

      (orc/code "load-csv"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/load-csv-fn"
        :reads [:csv-path :csv-data]
        :writes [:csv-data])

      (orc/code "analyze-structure"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/analyze-csv-structure-fn"
        :reads [:csv-data :entity-column]
        :writes [:column-analysis :detected-classes :detected-properties
                 :detected-hierarchies :detected-foreign-keys])

      (orc/code "detect-temporal-columns"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/detect-temporal-columns-fn"
        :reads [:column-analysis]
        :writes [:date-columns :has-temporal-data])

      (orc/code "extract-temporal-metadata"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/extract-temporal-metadata-fn"
        :reads [:csv-data :date-columns :entity-column]
        :writes [:temporal-entities])

      (orc/code "build-context-strings"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/build-csv-context-fn"
        :reads [:column-analysis :csv-data :detected-classes
                :detected-hierarchies :detected-foreign-keys]
        :writes [:column-summary :sample-rows :detected-patterns])

      (orc/llm "analyze-csv-schema"
        :model "google/gemini-2.5-flash"
        :instruction "Analyze CSV structure to understand domain and suggest ontology design. Identify 1-5 main entity types, suggest meaningful relationship names, map columns to property names (camelCase), and distinguish data properties (literals) from object properties (references)."
        :reads [:column-summary :sample-rows :detected-patterns]
        :writes [:schema-reasoning :domain :domain-description :entities :relationships :property-mappings]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      (orc/map-each "generate-definitions" :from :entities :as :current-entity :into :definition-results
        (orc/sequence "enrich-current-entity"
          (orc/code "prepare-entity-context"
            :fn "ai.obney.orc.ontology.sheets.csv-ontology/prepare-entity-context-fn"
            :reads [:current-entity :csv-data :column-analysis :entities]
            :writes [:entity-name :source-columns :sample-instances :related-entities])

          (orc/llm "enrich-entity-definition"
            :model "google/gemini-2.5-flash"
            :instruction "Generate a formal 2-3 sentence definition for this entity class. Include usage boundaries in the scope note. Suggest alignments to standard ontologies (schema.org, SKOS, Dublin Core)."
            :reads [:domain :current-entity :entity-name :source-columns :sample-instances :related-entities]
            :writes [:definition-reasoning :definition :scope-note :external-alignments]
            :retry {:max-attempts 2 :backoff-ms [200 1000]})))

      (orc/code "collect-definitions"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/collect-entity-definitions-fn"
        :reads [:entities :definition-results]
        :writes [:entity-definitions])

      (orc/code "identify-categorical-columns"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/identify-categorical-columns-fn"
        :reads [:column-analysis]
        :writes [:categorical-columns])

      (orc/map-each "build-hierarchies" :from :categorical-columns :as :current-column :into :hierarchy-results
        (orc/code "prepare-hierarchy-context"
          :fn "ai.obney.orc.ontology.sheets.csv-ontology/prepare-hierarchy-context-fn"
          :reads [:csv-data]
          :writes [:column-name :unique-values :value-counts])

        (orc/llm "suggest-hierarchy"
          :model "google/gemini-2.5-flash"
          :instruction "Determine if these categorical values have a natural hierarchy (ordering, containment, or specialization). Not all columns have hierarchies - return has_hierarchy=false if none exists."
          :reads [:value-counts]
          :writes [:has-hierarchy :hierarchy-type :hierarchy-relationships :top-level :reasoning]
          :retry {:max-attempts 2 :backoff-ms [200 1000]}))

      (orc/code "collect-hierarchies"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/collect-hierarchies-fn"
        :reads [:categorical-columns :hierarchy-results]
        :writes [:hierarchies])

      (orc/code "prepare-relationship-context"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/prepare-relationship-context-fn"
        :reads [:relationships]
        :writes [:entities-info :sample-data :existing-relationships])

      (orc/llm "discover-implicit-relationships"
        :model "google/gemini-2.5-flash"
        :instruction "Discover implicit relationships between entity types not captured by explicit foreign keys. Look for co-occurrence patterns, domain-specific relationships, and inverse relationships. Don't duplicate existing relationships."
        :reads [:existing-relationships]
        :writes [:relationship-reasoning :discovered-relationships :inverse-relationships]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      (orc/code "prepare-validation-context"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/prepare-validation-context-fn"
        :reads [:property-mappings]
        :writes [:terms-to-validate :existing-definitions :mappings-to-validate])

      (orc/llm "detect-ambiguity"
        :model "google/gemini-2.5-flash"
        :instruction "Detect ambiguous terms: those with multiple meanings, overlapping concepts that should be merged or distinguished, and terms with unclear scope."
        :reads [:existing-definitions]
        :writes [:ambiguity-reasoning :ambiguous-terms]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})


      (orc/code "collect-mapping-issues"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/collect-mapping-issues-fn"
        :reads [:mappings-to-validate]
        :writes [:mapping-issues])

      (orc/code "build-tbox"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/build-tbox-fn"
        :reads [:entities :entity-definitions :relationships :discovered-relationships
                :property-mappings :mapping-issues :hierarchies :base-uri]
        :writes [:tbox])

      (orc/code "build-abox"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/build-abox-fn"
        :reads [:tbox :csv-data :column-analysis :entity-column :entity-type
                :hierarchies :base-uri]
        :writes [:abox])

      (orc/code "serialize-to-owl"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/serialize-to-owl-fn"
        :reads [:tbox :abox :temporal-entities :base-uri]
        :writes [:owl-output])

      (orc/code "compute-statistics"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/compute-csv-statistics-fn"
        :reads [:csv-data :tbox :abox :temporal-entities :date-columns]
        :writes [:statistics]))))

(defn build-csv-ontology-pipeline!
  "Build the CSV-to-ontology pipeline workflow. Returns sheet-id."
  ([context] (build-csv-ontology-pipeline! context nil))
  ([context model]
   (orc/build-workflow! context
                          (pin-model csv-to-ontology-pipeline model))))

(defn run-csv-to-ontology
  "Run the CSV-to-ontology pipeline with given inputs.

   Args:
     context: The ORC context
     sheet-id: The built sheet ID
     opts: Map with keys:
       :csv-path - Path to CSV file (or provide :csv-data directly)
       :csv-data - Parsed CSV data as vector of maps
       :entity-column - Column to use as entity label
       :entity-type - Class name for instances
       :base-uri - Ontology namespace URI

   Returns:
     {:status :success/:failed
      :domain - Detected domain
      :entities - Identified entity types
      :tbox - Ontology schema (classes, properties)
      :abox - Instances
      :owl-output - OWL Turtle serialization
      :statistics - Conversion statistics}"
  [context sheet-id {:keys [csv-path csv-data entity-column entity-type base-uri]
                     :or {base-uri "http://example.org/ontology#"
                          entity-type "Entity"}}]
  (let [inputs (cond-> {:csv-data csv-data
                        :entity-column entity-column
                        :entity-type entity-type
                        :base-uri base-uri}
                 csv-path (assoc :csv-path csv-path))
        result (orc/execute context sheet-id inputs)]
    (if (= :success (:status result))
      {:status :success
       :trace-id (:trace-id result)
       :domain (get-in result [:outputs :domain])
       :domain-description (get-in result [:outputs :domain-description])
       :entities (get-in result [:outputs :entities])
       :relationships (get-in result [:outputs :relationships])
       :discovered-relationships (get-in result [:outputs :discovered-relationships])
       :tbox (get-in result [:outputs :tbox])
       :abox (get-in result [:outputs :abox])
       :owl-output (get-in result [:outputs :owl-output])
       :statistics (get-in result [:outputs :statistics])}
      {:status :failed
       :trace-id (:trace-id result)
       :error (:error result)})))

;; --- Interface ---

(defn extract-from-csv
  "Extract ontology from CSV data using the CSV ORC sheet.

   Args:
     ctx: ORC context
     opts:
       :csv-data - Parsed CSV as vector of maps, or CSV string
       :entity-column - Column to use as entity label
       :entity-type - OWL class name
       :base-uri - Ontology namespace

   Returns:
     {:status :success/:failed
      :entities [...] :relationships [...] :tbox {...}
      :owl-output string}"
  [ctx opts]

  (let [build! #'build-csv-ontology-pipeline!
        run! #'run-csv-to-ontology
        sheet-id (build! ctx)]
    (run! ctx sheet-id opts)))

;; --- Ontology exploration ---

(def taxonomy-pipeline
  "Complete taxonomy generation pipeline.

   Maps 1:1 to Python TaxonomyPipeline phases:
   1. Ingestion (code)
   2. Extraction (LLM: ExtractConcepts)
   3. Deduplication (code)
   4. Hierarchy Building (LLM: DiscoverHierarchy)
   5. Relationship Discovery (LLM: FindRelatedConcepts)
   6. Validation (LLM: ValidateTaxonomy)
   7. Refinement (code, conditional)
   8. Build Taxonomy (code)
   9. Serialization (code)"

  (orc/workflow "taxonomy-pipeline"

    (orc/blackboard
      ;; === Inputs ===
      {:source-text [:string {:description "Text to extract concepts from for taxonomy building"}]
       :domain [:string {:description "Domain context (e.g., 'artificial intelligence', 'higher education')"}]
       :existing-concepts [:vector {:description "Already known concepts to avoid extracting again"} :string]
       :source-paths [:vector {:description "Paths to source files to load"} :string]
       :output-format :string

       ;; === ChainOfThought Reasoning Fields ===
       :extraction-reasoning [:string {:description "Step-by-step reasoning for identifying and extracting concepts from the text"}]
       :hierarchy-reasoning [:string {:description "Step-by-step reasoning for discovering broader/narrower relationships between concepts"}]
       :related-reasoning [:string {:description "Step-by-step reasoning for finding non-hierarchical relationships"}]
       :causal-reasoning [:string {:description "Step-by-step reasoning for identifying causal relationships in the text"}]
       :validation-reasoning [:string {:description "Step-by-step reasoning for validating taxonomy structure and quality"}]

       :raw-concepts [:vector {:description "Only explicitly named domain concepts extracted from text with labels, definitions, and entity types"}
                      [:map
                       [:label :string]
                       [:alt_labels :string]
                       [:definition :string]
                       [:entity_type :string]]]

       :concepts [:vector {:description "Deduplicated concepts after type-based blocking"}
                  [:map
                   [:label :string]
                   [:alt_labels :string]
                   [:definition :string]
                   [:entity_type :string]
                   [:confidence :double]]]
       :concept-labels [:vector {:description "Just the labels from concepts for hierarchy building"} :string]
       :blocking-stats [:map {:description "Statistics from type-based blocking deduplication"}
                        [:type-count :int]
                        [:types [:vector :string]]
                        [:concepts-per-type [:map-of :string :int]]]

       :relationships [:vector {:description "Broader/narrower (parent-child) relationships between concepts"}
                       [:map
                        [:broader :string]
                        [:narrower :string]]]
       :top-concepts [:vector {:description "Root concepts with no broader concept"} :string]

       :related-pairs [:vector {:description "Non-hierarchical relationships between concepts"}
                       [:map
                        [:concept1 :string]
                        [:concept2 :string]
                        [:reason :string]]]

       :causal-relations [:vector {:description "Causal, enabling, and preventative relationships from text"}
                          [:map
                           [:cause :string]
                           [:effect :string]
                           [:relation_type :string]
                           [:confidence :double]
                           [:evidence :string]]]
       :validated-causal-relations [:vector {:description "Validated causal relations with normalized types"}
                                    [:map
                                     [:cause :string]
                                     [:effect :string]
                                     [:relation_type :string]
                                     [:confidence :double]
                                     [:evidence :string]]]
       :causal-stats [:map {:description "Statistics from causal relation extraction"}
                      [:total-extracted :int]
                      [:total-validated :int]
                      [:by-type [:map-of :string :int]]]

       :issues [:vector {:description "Validation issues found in taxonomy structure"}
                [:map
                 [:type :string]
                 [:description :string]
                 [:affected_concepts :string]
                 [:suggestion :string]]]
       :quality [:enum {:description "Overall quality assessment"} "good" "fair" "poor"]
       :suggestions [:vector {:description "Suggestions for improving taxonomy quality"} :string]

       :taxonomy [:map
                  [:concepts [:vector [:map
                                       [:label :string]
                                       [:alt_labels :string]
                                       [:definition :string]
                                       [:entity_type :string]
                                       [:confidence :double]]]]
                  [:relationships [:vector [:map [:broader :string] [:narrower :string]]]]
                  [:top-concepts [:vector :string]]
                  [:related [:vector [:map
                                      [:concept1 :string]
                                      [:concept2 :string]
                                      [:reason :string]]]]
                  [:causal-relations [:vector [:map
                                               [:cause :string]
                                               [:effect :string]
                                               [:relation_type :string]
                                               [:confidence :double]
                                               [:evidence :string]]]]
                  [:domain :string]
                  [:base-uri :string]]
       :skos-output :string})

    ;; =========================================================================
    ;; MAIN PIPELINE SEQUENCE
    ;; =========================================================================
    (orc/sequence "main-pipeline"

      (orc/code "ingest-sources"
        :fn "ai.obney.orc.ontology.sheets.ontology-exploration/ingest-sources-fn"
        :reads [:source-paths :source-text]
        :writes [:source-text])

      (orc/llm "extract-concepts"
        :model "google/gemini-2.5-flash"
        :instruction "Extract only explicitly named domain entities and concepts from the text. Do not invent supporting concepts merely because they appear in a definition; short inputs may yield only 1-4 concepts. Preserve stated canonical names and alternative names exactly. Include alternative labels (synonyms, abbreviations) when explicitly stated. Avoid extracting concepts already in existing_concepts. Use domain-appropriate entity types."
        :reads [:source-text :domain :existing-concepts]
        :writes [:extraction-reasoning :raw-concepts]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      (orc/code "deduplicate-concepts"
        :fn "ai.obney.orc.ontology.sheets.ontology-exploration/deduplicate-concepts-fn"
        :reads [:raw-concepts]
        :writes [:concepts :blocking-stats])

      (orc/code "extract-labels"
        :fn "ai.obney.orc.ontology.sheets.ontology-exploration/extract-labels-fn"
        :reads [:concepts]
        :writes [:concept-labels])

      (orc/llm "discover-hierarchy"
        :model "google/gemini-2.5-flash"
        :instruction "Identify broader/narrower (parent-child) relationships between concepts. A concept is narrower if it is a type of or subset of another. Polyhierarchy is allowed. Not every concept needs relationships."
        :reads [:concept-labels :domain]
        :writes [:hierarchy-reasoning :relationships :top-concepts]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      (orc/llm "find-related-concepts"
        :model "google/gemini-2.5-flash"
        :instruction "Find non-hierarchical relationships between concepts. Identify concepts that are associated, similar, or frequently used together but NOT in a parent-child relationship. Focus on meaningful associations."
        :reads [:concept-labels :domain]
        :writes [:related-reasoning :related-pairs]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      (orc/llm "extract-causal-relations"
        :model "google/gemini-2.5-flash"
        :instruction "Extract causal, enabling, and preventative relationships from text. Look for: causes (X leads to Y), enables (X allows Y), prevents (X blocks Y), preparesFor (X is prerequisite for Y). Focus on explicit causal statements, not implied correlations."
        :reads [:source-text :concept-labels :domain]
        :writes [:causal-reasoning :causal-relations]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      (orc/code "validate-causal-relations"
        :fn "ai.obney.orc.ontology.sheets.ontology-exploration/validate-causal-relations-fn"
        :reads [:causal-relations :concept-labels]
        :writes [:validated-causal-relations :causal-stats])

      (orc/llm "validate-taxonomy"
        :model "google/gemini-2.5-flash"
        :instruction "Validate taxonomy structure. Check for: orphans (concepts with no parent that aren't top concepts), cycles (circular references), missing definitions, inconsistent granularity among siblings, and near-duplicate concepts."
        :reads [:concept-labels :relationships :domain]
        :writes [:validation-reasoning :issues :quality :suggestions]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      (orc/fallback "refine-if-needed"
        (orc/sequence "skip-refinement"
          (orc/condition "quality-ok"
            :check {:key :quality :op :equals :value "good"}))

        (orc/code "apply-auto-fixes"
          :fn "ai.obney.orc.ontology.sheets.ontology-exploration/apply-auto-fixes-fn"
          :reads [:issues :concepts :relationships :top-concepts]
          :writes [:top-concepts]))

      (orc/code "build-taxonomy"
        :fn "ai.obney.orc.ontology.sheets.ontology-exploration/build-taxonomy-fn"
        :reads [:concepts :relationships :top-concepts :related-pairs
                :validated-causal-relations :domain]
        :writes [:taxonomy])

      (orc/code "serialize-to-skos"
        :fn "ai.obney.orc.ontology.sheets.ontology-exploration/serialize-to-skos-fn"
        :reads [:taxonomy :output-format]
        :writes [:skos-output]))))

(defn build-taxonomy-pipeline!
  "Build the taxonomy pipeline workflow. Returns sheet-id."
  ([context] (build-taxonomy-pipeline! context nil))
  ([context model]
   (orc/build-workflow! context
                          (pin-model taxonomy-pipeline model))))

(defn run-taxonomy-pipeline
  "Run the taxonomy pipeline with given inputs.

   Args:
     context: The ORC context
     sheet-id: The built sheet ID
     opts: Map with keys:
       :source-text - Text to extract concepts from
       :domain - Domain context (e.g., 'artificial intelligence')
       :existing-concepts - Already known concepts to avoid (optional)

   Returns:
     {:status :success/:failed
      :concepts - Extracted and deduplicated concepts
      :relationships - Broader/narrower relationships
      :top-concepts - Root concepts
      :related-pairs - Non-hierarchical relationships
      :validation - Quality assessment and issues
      :skos-output - SKOS Turtle serialization}"
  [context sheet-id {:keys [source-text domain existing-concepts]
                     :or {existing-concepts []}}]
  (let [result (orc/execute context sheet-id
                 {:source-text source-text
                  :domain domain
                  :existing-concepts existing-concepts
                  :output-format "turtle"})]
    (if (= :success (:status result))
      {:status :success
       :trace-id (:trace-id result)
       :concepts (get-in result [:outputs :concepts])
       :relationships (get-in result [:outputs :relationships])
       :top-concepts (get-in result [:outputs :top-concepts])
       :related-pairs (get-in result [:outputs :related-pairs])
       :validation {:quality (get-in result [:outputs :quality])
                    :issues (get-in result [:outputs :issues])
                    :suggestions (get-in result [:outputs :suggestions])}
       :skos-output (get-in result [:outputs :skos-output])}
      {:status :failed
       :trace-id (:trace-id result)
       :error (:error result)})))

;; --- Interface ---

(defn extract-from-text
  "Extract ontology from text using the taxonomy ORC sheet.

   Args:
     ctx: ORC context
     opts:
       :source-text - Text to extract concepts from
       :domain - Domain context (e.g., \"artificial intelligence\")
       :existing-concepts - Concepts to avoid re-extracting

   Returns:
     {:status :success/:failed
      :concepts [...] :relationships [...] :top-concepts [...]
      :skos-output string}"
  [ctx opts]

  (let [build! #'build-taxonomy-pipeline!
        run! #'run-taxonomy-pipeline
        sheet-id (build! ctx)]
    (run! ctx sheet-id opts)))

;; --- Sql ontology ---

(def sql-to-ontology-pipeline
  "Complete SQL database to ontology pipeline.

   Phases:
   1. Connect to Database (code)
   2. Analyze Schema (code - tables, columns, FKs)
   3. Build Context (code)
   4. Semantic Analysis (LLM - identify domain, entity types)
   5. Per-Table Entity Extraction (LLM - definitions, key properties)
   6. Relationship Discovery (LLM - implicit relationships)
   7. TBox Construction (code)
   8. ABox Extraction (code - sample instances)
   9. Serialization (code)"

  (orc/workflow "sql-to-ontology"

    ;; =========================================================================
    ;; BLACKBOARD SCHEMA
    ;; =========================================================================
    (orc/blackboard
      ;; === Inputs ===
      {:db-path [:string {:description "Path to SQLite database file"}]
       :base-uri [:string {:description "Ontology namespace URI"}]
       :max-tables [:int {:description "Maximum tables to process"}]
       :max-instances [:int {:description "Maximum instances per table"}]

       ;; === Connection ===
       :db-connected :boolean
       :table-names [:vector {:description "List of table names"} :string]
       :table-count :int

       ;; === Schema Analysis ===
       :table-analysis [:vector {:description "Analysis of each table"}
                        [:map
                         [:table-name :string]
                         [:row-count :int]
                         [:columns [:vector [:map
                                             [:name :string]
                                             [:type :string]
                                             [:nullable :boolean]
                                             [:primary-key :boolean]
                                             [:semantic-type :keyword]
                                             [:xsd-type :string]]]]
                         [:primary-keys [:vector :string]]
                         [:foreign-keys [:vector [:map
                                                  [:from-column :string]
                                                  [:to-table :string]
                                                  [:to-column :string]]]]]]
       :schema-summary [:map {:description "Summary statistics"}
                        [:table-count :int]
                        [:total-columns :int]
                        [:total-rows :int]
                        [:total-foreign-keys :int]]

       ;; === Context Strings ===
       :table-summary :string
       :fk-summary :string
       :detected-patterns :string

       ;; === LLM Outputs ===
       :schema-reasoning [:string {:description "Reasoning about database domain"}]
       :domain [:string {:description "Identified domain"}]
       :domain-description [:string {:description "Domain description"}]
       :database-purpose [:string {:description "Purpose of this database"}]

       ;; === Per-Table Processing ===
       :current-table [:map {:description "Table being processed"}
                       [:table-name :string]
                       [:row-count :int]
                       [:columns [:vector [:map
                                           [:name :string]
                                           [:type :string]
                                           [:nullable :boolean]
                                           [:primary-key :boolean]
                                           [:semantic-type :keyword]
                                           [:xsd-type :string]]]]
                       [:primary-keys [:vector :string]]
                       [:foreign-keys [:vector [:map
                                                [:from-column :string]
                                                [:to-table :string]
                                                [:to-column :string]]]]]
       :table-name :string
       :row-count :int
       :column-names [:vector {:description "Column names"} :string]
       :label-columns [:vector {:description "Label columns"} :string]
       :id-columns [:vector {:description "ID columns"} :string]
       :description-columns [:vector {:description "Description columns"} :string]
       :table-reasoning [:string {:description "Reasoning about table entity"}]
       :class-name [:string {:description "OWL class name for table"}]
       :definition [:string {:description "Entity definition"}]
       :entity-type [:string {:description "Entity type category"}]
       :key-properties [:vector {:description "Key semantic properties"} :string]
       :entity-results [:vector [:map
                                  [:class-name :string]
                                  [:definition :string]
                                  [:entity-type :string]
                                  [:key-properties [:vector :string]]]]
       :table-entities-json [:string {:description "JSON output from batch table entity extraction"}]
       :table-entities [:map-of :string [:map
                                          [:class-name :string]
                                          [:definition :string]
                                          [:entity-type :string]
                                          [:key-properties [:vector :string]]]]

       ;; === Relationship Discovery ===
       :relationship-reasoning [:string {:description "Reasoning about relationships"}]
       :discovered-relationships [:vector {:description "Discovered relationships"}
                                  [:map
                                   [:subject :string]
                                   [:predicate :string]
                                   [:object :string]
                                   [:confidence :double]
                                   [:evidence :string]]]

       ;; === TBox/ABox ===
       :tbox [:map {:description "Ontology schema"}
              [:classes [:vector [:map [:uri :string] [:label :string] [:comment :string]]]]
              [:object-properties [:vector [:map [:uri :string] [:label :string] [:domain :string] [:range :string]]]]
              [:datatype-properties [:vector [:map [:uri :string] [:label :string] [:domain :string] [:range :string]]]]]
       :abox [:vector {:description "Ontology instances"}
              [:map
               [:uri :string]
               [:type :string]
               [:label :string]
               [:properties [:map-of :string
                             [:or :nil :string :int :double :boolean :keyword :uuid :inst]]]]]

       ;; === Output ===
       :owl-output :string
       :statistics [:map {:description "Conversion statistics"}
                    [:table-count :int]
                    [:column-count :int]
                    [:class-count :int]
                    [:individual-count :int]]})

    ;; =========================================================================
    ;; MAIN PIPELINE SEQUENCE
    ;; =========================================================================
    (orc/sequence "sql-main-pipeline"

      (orc/code "connect-to-database"
        :fn "ai.obney.orc.ontology.sheets.sql-ontology/connect-to-database-fn"
        :reads [:db-path]
        :writes [:db-connected :table-names :table-count])

      (orc/code "analyze-schema"
        :fn "ai.obney.orc.ontology.sheets.sql-ontology/analyze-schema-fn"
        :reads [:db-path :table-names :max-tables]
        :writes [:table-analysis :schema-summary])

      (orc/code "build-schema-context"
        :fn "ai.obney.orc.ontology.sheets.sql-ontology/build-schema-context-fn"
        :reads [:table-analysis :schema-summary]
        :writes [:table-summary :fk-summary :detected-patterns])

      (orc/llm "analyze-database-domain"
        :model "google/gemini-2.5-flash"
        :instruction "Analyze this database schema to understand its domain and purpose. Identify the main domain (e.g., 'Higher Education', 'Healthcare', 'Finance'), describe what this database is used for, and note any domain-specific patterns or conventions."
        :reads [:table-summary :fk-summary]
        :writes [:schema-reasoning :domain :domain-description :database-purpose]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      (orc/llm "generate-table-entities"
        :model "google/gemini-2.5-flash"
        :instruction "For each table in the schema, generate an OWL class definition. For each table provide: a meaningful class name (PascalCase), a 1-2 sentence definition, and an entity type (Person, Organization, Event, Artifact, Concept, Location, etc.). Return as a JSON object with table names as keys."
        :reads [:table-summary :domain :domain-description]
        :writes [:table-reasoning :table-entities-json]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      (orc/code "parse-table-entities"
        :fn "ai.obney.orc.ontology.sheets.sql-ontology/parse-table-entities-fn"
        :reads [:table-analysis :table-entities-json]
        :writes [:table-entities])

      (orc/llm "discover-relationships"
        :model "google/gemini-2.5-flash"
        :instruction "Discover implicit relationships between tables beyond explicit foreign keys. Look for: naming conventions suggesting relationships, semantic associations between entities, common patterns in the domain. Don't duplicate relationships already captured by foreign keys."
        :reads [:table-summary :fk-summary :domain :domain-description]
        :writes [:relationship-reasoning :discovered-relationships]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      (orc/code "build-tbox"
        :fn "ai.obney.orc.ontology.sheets.sql-ontology/build-sql-tbox-fn"
        :reads [:table-analysis :table-entities :discovered-relationships :base-uri]
        :writes [:tbox])

      (orc/code "extract-abox"
        :fn "ai.obney.orc.ontology.sheets.sql-ontology/extract-sample-instances-fn"
        :reads [:db-path :table-analysis :table-entities :base-uri :max-instances]
        :writes [:abox])

      (orc/code "serialize-to-owl"
        :fn "ai.obney.orc.ontology.sheets.sql-ontology/serialize-sql-to-owl-fn"
        :reads [:tbox :abox :base-uri :db-path]
        :writes [:owl-output])

      (orc/code "compute-statistics"
        :fn "ai.obney.orc.ontology.sheets.sql-ontology/compute-sql-statistics-fn"
        :reads [:table-analysis :tbox :abox :schema-summary]
        :writes [:statistics]))))

(defn build-sql-ontology-pipeline!
  "Build the SQL-to-ontology pipeline workflow. Returns sheet-id."
  ([context] (build-sql-ontology-pipeline! context nil))
  ([context model]
   (orc/build-workflow! context
                          (pin-model sql-to-ontology-pipeline model))))

(defn run-sql-to-ontology
  "Run the SQL-to-ontology pipeline with given inputs.

   Args:
     context: The ORC context
     sheet-id: The built sheet ID
     opts: Map with keys:
       :db-path - Path to SQLite database file
       :base-uri - Ontology namespace URI (default: http://example.org/db#)
       :max-tables - Max tables to process (default: 50)
       :max-instances - Max instances per table (default: 10)
       :timeout-ms - Execution timeout in ms (default: 600000 = 10 minutes)

   Returns:
     {:status :success/:failed
      :domain - Detected domain
      :domain-description - Domain description
      :tbox - Ontology schema (classes, properties)
      :abox - Sample instances
      :owl-output - OWL Turtle serialization
      :statistics - Conversion statistics}"
  [context sheet-id {:keys [db-path base-uri max-tables max-instances timeout-ms]
                     :or {base-uri "http://example.org/db#"
                          max-tables 50
                          max-instances 10
                          timeout-ms 600000}}]
  (let [base-config (litellm-router/get-config :openrouter)
        model-name "google/gemini-2.5-flash"
        model-provider-key (keyword "openrouter" model-name)]
    (when (and base-config (not (litellm-router/get-config model-provider-key)))
      (litellm-router/register! model-provider-key
        {:provider :openrouter
         :model model-name
         :config (if (:config base-config)
                   (:config base-config)
                   (dissoc base-config :provider :model))})))
  (let [result (orc/execute context sheet-id
                 {:db-path db-path
                  :base-uri base-uri
                  :max-tables max-tables
                  :max-instances max-instances}
                 {:timeout-ms timeout-ms})]
    (if (= :success (:status result))
      {:status :success
       :trace-id (:trace-id result)
       :domain (get-in result [:outputs :domain])
       :domain-description (get-in result [:outputs :domain-description])
       :database-purpose (get-in result [:outputs :database-purpose])
       :table-entities (get-in result [:outputs :table-entities])
       :tbox (get-in result [:outputs :tbox])
       :abox (get-in result [:outputs :abox])
       :owl-output (get-in result [:outputs :owl-output])
       :statistics (get-in result [:outputs :statistics])}
      {:status :failed
       :trace-id (:trace-id result)
       :error (:error result)})))

;; --- Interface ---

(defn extract-from-sql
  "Extract ontology from SQLite database using the SQL ORC sheet.

   Args:
     ctx: ORC context
     opts:
       :db-path - Path to SQLite database file
       :base-uri - Ontology namespace (default: http://example.org/db#)
       :max-tables - Max tables to process (default: 50)
       :max-instances - Max instances per table for A-box (default: 10)

   Returns:
     {:status :success/:failed
      :domain - Detected domain
      :domain-description - Domain description
      :tbox {:classes [...] :object-properties [...] :datatype-properties [...]}
      :abox [...]
      :owl-output string
      :statistics {...}}"
  [ctx opts]

  (let [build! #'build-sql-ontology-pipeline!
        run! #'run-sql-to-ontology
        sheet-id (build! ctx)]
    (run! ctx sheet-id opts)))

;; --- Unified ontology ---

(defn unified-ontology-detect-source-type
  "Auto-detect source type from path or content.

   Returns one of: :csv, :text, :sql, :json, :rdf, :unknown

   Detection priority:
   1. Explicit :type in source spec
   2. File extension (.csv, .db, .sqlite, .json, .ttl, .rdf, .txt)
   3. Content inspection (commas + newlines → CSV, starts with { → JSON, etc.)"
  [{:keys [path content type]}]
  (cond
    type
    (keyword type)

    (and path (string? path))
    (let [lower-path (str/lower-case path)]
      (cond
        (str/ends-with? lower-path ".csv") :csv
        (str/ends-with? lower-path ".db") :sql
        (str/ends-with? lower-path ".sqlite") :sql
        (str/ends-with? lower-path ".sqlite3") :sql
        (str/ends-with? lower-path ".json") :json
        (str/ends-with? lower-path ".jsonl") :json
        (str/ends-with? lower-path ".ttl") :rdf
        (str/ends-with? lower-path ".rdf") :rdf
        (str/ends-with? lower-path ".owl") :rdf
        (str/ends-with? lower-path ".nt") :rdf
        (str/ends-with? lower-path ".txt") :text
        (str/ends-with? lower-path ".md") :text
        :else :unknown))

    (and content (string? content))
    (let [trimmed (str/trim content)]
      (cond
        (or (str/starts-with? trimmed "{")
            (str/starts-with? trimmed "["))
        :json

        (or (str/starts-with? trimmed "@prefix")
            (str/starts-with? trimmed "PREFIX")
            (and (str/starts-with? trimmed "<")
                 (str/includes? trimmed ">")))
        :rdf

        (and (str/includes? content ",")
             (str/includes? content "\n")
             (let [lines (str/split-lines content)]
               (and (> (count lines) 1)
                    (let [comma-counts (map #(count (re-seq #"," %)) (take 5 lines))]
                      (apply = comma-counts)))))
        :csv

        :else :text))

    :else :unknown))

(defn file-exists?
  "Check if a file exists at the given path."
  [path]
  (and path (.exists (io/file path))))

(def ^:private sheet-ids
  "Cache for built sheet IDs."
  (atom {}))

(defn- unified-ontology-ensure-csv-sheet! [ctx]
  (or (get @sheet-ids :csv)
      (let [id (build-csv-ontology-pipeline! ctx)]
        (swap! sheet-ids assoc :csv id)
        id)))

(defn extract-csv
  "Extract ontology from CSV source."
  [ctx {:keys [path content entity-column entity-type base-uri]}]
  (let [sheet-id (unified-ontology-ensure-csv-sheet! ctx)
        csv-data (cond
                   content content
                   (and path (file-exists? path)) (slurp path)
                   :else (throw (ex-info "CSV source not found" {:path path})))]
    (run-csv-to-ontology ctx sheet-id
      {:csv-data csv-data
       :entity-column (or entity-column "name")
       :entity-type (or entity-type "Entity")
       :base-uri (or base-uri "http://unified.ai/")})))

(defn- unified-ontology-ensure-text-sheet! [ctx]
  (or (get @sheet-ids :text)
      (let [id (build-taxonomy-pipeline! ctx)]
        (swap! sheet-ids assoc :text id)
        id)))

(defn extract-text
  "Extract ontology from text source."
  [ctx {:keys [path content domain existing-concepts]}]
  (let [sheet-id (unified-ontology-ensure-text-sheet! ctx)
        text-content (cond
                       content content
                       (and path (file-exists? path)) (slurp path)
                       :else (throw (ex-info "Text source not found" {:path path})))]
    (run-taxonomy-pipeline ctx sheet-id
      {:source-text text-content
       :domain (or domain "general")
       :existing-concepts (or existing-concepts [])})))

(defn- unified-ontology-ensure-sql-sheet! [ctx]
  (or (get @sheet-ids :sql)
      (let [id (build-sql-ontology-pipeline! ctx)]
        (swap! sheet-ids assoc :sql id)
        id)))

(defn extract-sql
  "Extract ontology from SQL/SQLite database."
  [ctx {:keys [path base-uri max-tables max-instances]}]
  (let [sheet-id (unified-ontology-ensure-sql-sheet! ctx)]
    (if (and path (file-exists? path))
      (run-sql-to-ontology ctx sheet-id
        {:db-path path
         :base-uri (or base-uri "http://db.ai/")
         :max-tables (or max-tables 50)
         :max-instances (or max-instances 10)})
      (throw (ex-info "SQL database not found" {:path path})))))

;; --- Json ontology ---

(defn run-json-to-ontology
  "Run the JSON-to-ontology extraction pipeline.

   Args:
     ctx - ORC context with :event-store
     sheet-id - UUID of the built pipeline
     opts:
       :json-data - Pre-parsed JSON data
       :json-path - Path to JSON file
       :content - JSON string
       :base-uri - Ontology namespace URI (required)
       :domain - Optional domain hint

   Returns:
     {:status :success/:failure
      :concepts [...]
      :relationships [...]
      :tbox {...}
      :abox [...]
      :owl-output \"...\"}"
  [ctx sheet-id {:keys [json-data json-path content base-uri domain]}]
  (let [inputs (cond-> {:base-uri base-uri}
                 json-data (assoc :json-data json-data)
                 json-path (assoc :json-path json-path)
                 content (assoc :content content)
                 domain (assoc :domain domain))
        result (orc/execute ctx sheet-id inputs)]
    (if (= :success (:status result))
      (merge {:status :success :trace-id (:trace-id result)}
             (select-keys (:outputs result)
                          [:domain :concepts :relationships :tbox :abox :owl-output]))
      {:status :failure
       :trace-id (:trace-id result)
       :error (:error result)})))

(def ^:private json-value-schema
  [:schema
   {:registry
    {:ai.obney.orc.ontology.sheets.json-ontology/json-value
     [:or :nil :string :int :double :boolean
      [:vector [:ref :ai.obney.orc.ontology.sheets.json-ontology/json-value]]
      [:map-of [:or :keyword :string] [:ref :ai.obney.orc.ontology.sheets.json-ontology/json-value]]]}}
   [:ref :ai.obney.orc.ontology.sheets.json-ontology/json-value]])

(def ^:private concept-schema
  [:map
   [:uri :string]
   [:label :string]
   [:definition :string]
   [:entity-type :string]
   [:confidence :double]
   [:label-field {:optional true} :string]
   [:source-fields {:optional true} [:vector :string]]])

(def ^:private structure-field-schema
  [:map
   [:name :string]
   [:type :keyword]
   [:nullable? {:optional true} :boolean]
   [:sample-values [:vector json-value-schema]]
   [:distinct-count {:optional true} :int]])

(def ^:private tbox-class-schema
  [:map [:uri :string] [:label :string] [:definition :string] [:type :string]])

(def ^:private tbox-property-schema
  [:map [:uri :string] [:label :string] [:domain :string] [:range :string] [:type :string]])

(def json-to-ontology-pipeline
  "Behavior tree pipeline for JSON-to-Ontology extraction."
  (orc/workflow "json-to-ontology"
    (orc/blackboard
      {
       ;; --- Inputs ---
       :json-data json-value-schema
       :json-path [:string {:optional true}]
       :content [:string {:optional true}]
       :base-uri [:string {:description "Ontology namespace URI"}]
       :domain [:string {:optional true :description "Optional domain hint"}]
       :existing-concepts [:vector {:optional true} concept-schema]

       :structure [:map {:description "JSON structure analysis"}
                   [:root-type :keyword]
                   [:element-type {:optional true} [:maybe :keyword]]
                   [:total-count :int]
                   [:fields [:vector structure-field-schema]]
                   [:has-nested-objects? {:optional true} :boolean]
                   [:has-arrays? {:optional true} :boolean]
                   [:sample-values {:optional true} [:vector json-value-schema]]]
       :structure-summary [:string {:description "Human-readable structure summary for LLM"}]

       :domain-reasoning [:string {:description "Step-by-step reasoning for domain analysis"}]
       :domain-description [:string {:description "Description of the domain"}]
       :naming-patterns [:string {:description "Detected naming conventions"}]

       :entity-reasoning [:string {:description "Step-by-step reasoning for entity discovery"}]
       :entity-types [:vector {:description "Discovered entity types"}
                      [:map
                       [:name :string]
                       [:type :string]
                       [:source-fields [:vector :string]]
                       [:label-field {:optional true} :string]
                       [:definition :string]
                       [:confidence :double]]]

       :concepts [:vector {:description "Extracted concepts"}
                  [:map
                   [:uri :string]
                   [:label :string]
                   [:definition :string]
                   [:entity-type :string]
                   [:confidence :double]]]
       :dedup-stats [:map [:before :int] [:after :int] [:removed :int]]

       :definition-reasoning [:string {:description "Reasoning for definitions"}]
       :enriched-definitions [:vector {:description "Formal definitions for discovered entity types"}
                              [:map
                               [:entity-type :string]
                               [:definition :string]
                               [:scope-note :string]]]

       :relationship-reasoning [:string {:description "Reasoning for relationships"}]
       :relationships [:vector {:description "Discovered relationships"}
                       [:map
                        [:subject :string]
                        [:predicate :string]
                        [:object :string]
                        [:evidence :string]
                        [:confidence :double]]]

       :validation-reasoning [:string {:description "Reasoning for validation"}]
       :quality [:string {:description "Quality assessment: good/needs-work"}]
       :issues [:vector :string]
       :suggestions [:vector :string]

       :tbox [:map {:description "Ontology schema"}
              [:classes [:vector tbox-class-schema]]
              [:object-properties [:vector tbox-property-schema]]
              [:datatype-properties [:vector tbox-property-schema]]]
       :abox [:vector {:description "Instances"}
              [:map
               [:uri :string]
               [:type :string]
               [:label :string]
               [:properties [:map-of :string json-value-schema]]]]
       :owl-output [:string {:description "OWL Turtle serialization"}]})

    (orc/sequence "json-main-pipeline"
      (orc/code "parse-json"
        :fn "ai.obney.orc.ontology.sheets.json-ontology/parse-json-fn"
        :reads [:json-data :json-path :content]
        :writes [:json-data :structure :structure-summary])

      (orc/llm "analyze-json-domain"
        :model "google/gemini-3-flash-preview"
        :instruction "Analyze this JSON structure to understand its domain and purpose.

Based on the field names, values, and structure:
1. Identify the main domain (e.g., 'Human Resources', 'E-commerce', 'Healthcare')
2. Describe what this JSON data represents
3. Note any naming conventions or patterns (including cryptic/abbreviated field names)

Even if field names are cryptic (like 'n' for name, 'a' for age), infer their meaning from sample values."
        :reads [:structure-summary]
        :writes [:domain-reasoning :domain :domain-description :naming-patterns]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      (orc/llm "discover-entity-types"
        :model "google/gemini-3-flash-preview"
        :instruction "Identify entity types represented in this JSON.

For each entity type, provide:
1. A meaningful name (PascalCase) - even if the JSON fields are cryptic
2. The type category (Person, Organization, Event, Product, Location, Concept, etc.)
3. Which JSON fields belong to this entity
4. Which field should be used as the human-readable label (label-field)
5. A brief definition based on the data

Return as JSON array:
[{\"name\": \"Person\", \"type\": \"Person\", \"source-fields\": [\"n\", \"a\"], \"label-field\": \"n\", \"definition\": \"An individual with name and age\", \"confidence\": 0.9}]

The label-field should be the field that best identifies each instance (usually name, title, or id).
For cryptic fields, explain your reasoning (e.g., 'n' appears to be name based on string values like 'John Smith')."
        :reads [:structure-summary :domain :domain-description :naming-patterns]
        :writes [:entity-reasoning :entity-types]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      (orc/code "extract-concepts"
        :fn "ai.obney.orc.ontology.sheets.json-ontology/extract-concepts-from-analysis-fn"
        :reads [:entity-types :base-uri :json-data]
        :writes [:concepts])

      (orc/code "deduplicate-concepts"
        :fn "ai.obney.orc.ontology.sheets.json-ontology/deduplicate-concepts-fn"
        :reads [:concepts]
        :writes [:concepts :dedup-stats])

      (orc/llm "enrich-definitions"
        :model "google/gemini-3-flash-preview"
        :instruction "For each entity type, generate a formal 2-3 sentence definition.

Include:
1. What the entity represents
2. Key properties/characteristics
3. How it relates to other entities in the domain

Return as a JSON array with this exact shape:
[{\"entity-type\": \"Person\", \"definition\": \"...\", \"scope-note\": \"...\"}]"
        :reads [:concepts :domain :domain-description]
        :writes [:definition-reasoning :enriched-definitions]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      (orc/llm "discover-relationships"
        :model "google/gemini-3-flash-preview"
        :instruction "Discover relationships between entities in this JSON.

Look for:
1. Nesting patterns (parent contains child)
2. ID references (field_id pointing to another entity)
3. Semantic relationships based on domain knowledge
4. Implicit relationships from field names

Return as JSON array:
[{\"subject\": \"Employee\", \"predicate\": \"worksFor\", \"object\": \"Company\", \"evidence\": \"...\", \"confidence\": 0.8}]"
        :reads [:structure-summary :concepts :domain]
        :writes [:relationship-reasoning :relationships]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      (orc/llm "validate-quality"
        :model "google/gemini-3-flash-preview"
        :instruction "Validate the extracted ontology for quality issues.

Check for:
1. Ambiguous entity names
2. Overlapping concepts that should be merged
3. Missing definitions
4. Inconsistent naming conventions

Return as JSON:
{\"quality\": \"good\"/\"needs-work\", \"issues\": [...], \"suggestions\": [...]}"
        :reads [:concepts :relationships :domain]
        :writes [:validation-reasoning :quality :issues :suggestions]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      (orc/code "build-tbox"
        :fn "ai.obney.orc.ontology.sheets.json-ontology/build-tbox-fn"
        :reads [:concepts :relationships :base-uri]
        :writes [:tbox])

      (orc/code "build-abox"
        :fn "ai.obney.orc.ontology.sheets.json-ontology/build-abox-fn"
        :reads [:json-data :concepts :base-uri]
        :writes [:abox])

      (orc/code "serialize-to-owl"
        :fn "ai.obney.orc.ontology.sheets.json-ontology/serialize-to-owl-fn"
        :reads [:tbox :abox :base-uri]
        :writes [:owl-output]))))

(defn build-json-ontology-pipeline!
  "Build the JSON ontology extraction pipeline in the given context.
   Returns the sheet-id."
  ([ctx] (build-json-ontology-pipeline! ctx nil))
  ([ctx model]
   (orc/build-workflow! ctx
                          (pin-model json-to-ontology-pipeline model))))

;; --- Unified ontology ---

(defn- unified-ontology-ensure-json-sheet! [ctx]
  (or (get @sheet-ids :json)
      (let [id (build-json-ontology-pipeline! ctx)]
        (swap! sheet-ids assoc :json id)
        id)))

(defn extract-json
  "Extract ontology from JSON source.

   Args:
     ctx - ORC context
     opts:
       :path     - Path to JSON file
       :content  - Inline JSON string
       :base-uri - Ontology namespace URI (default: http://json.ai/)
       :domain   - Optional domain hint for LLM

   Returns extraction result with :concepts, :relationships, :tbox, :abox, etc."
  [ctx {:keys [path content base-uri domain]}]
  (let [sheet-id (unified-ontology-ensure-json-sheet! ctx)
        json-data (cond
                    content
                    (json/read-str content :key-fn keyword)

                    (and path (file-exists? path))
                    (json/read-str (slurp path) :key-fn keyword)

                    :else
                    (throw (ex-info "JSON source not found" {:path path})))]
    (run-json-to-ontology ctx sheet-id
      {:json-data json-data
       :base-uri (or base-uri "http://json.ai/")
       :domain domain})))

(defn extract-rdf
  "Import existing RDF/OWL ontology."
  [_ctx {:keys [path content]}]
  {:status :not-implemented
   :message "RDF import not yet implemented"
   :path path
   :content-provided? (boolean content)})

(defn extract
  "Unified ontology extraction - auto-detects source type and routes appropriately.

   Args:
     ctx - ORC context with :event-store
     source - Map with:
       :path    - Path to source file (optional if :content provided)
       :content - Inline content (optional if :path provided)
       :type    - Source type override (optional, will auto-detect)

       Type-specific options:
       CSV:
         :entity-column - Column for entity labels
         :entity-type   - OWL class name
         :base-uri      - Ontology namespace

       Text:
         :domain           - Domain context
         :existing-concepts - Concepts to avoid re-extracting

       SQL:
         :max-tables    - Max tables to process
         :max-instances - Max instances per table

   Returns:
     {:status :success/:failed/:not-implemented
      :source-type :csv/:text/:sql/:json/:rdf
      :concepts [...]
      :relationships [...]
      ...type-specific outputs...}"
  [ctx source]
  (let [source-type (unified-ontology-detect-source-type source)]
    (try
      (let [result (case source-type
                     :csv (extract-csv ctx source)
                     :text (extract-text ctx source)
                     :sql (extract-sql ctx source)
                     :json (extract-json ctx source)
                     :rdf (extract-rdf ctx source)
                     :unknown {:status :failed
                               :error "Could not detect source type"
                               :source source})]
        (assoc result :source-type source-type))
      (catch Exception e
        {:status :failed
         :source-type source-type
         :error (.getMessage e)
         :exception-type (type e)}))))

;; --- Interface ---

(defn extract-unified
  "Unified ontology extraction - auto-detects source type.

   This is the simplest API - provide a source and let the system
   determine the appropriate extraction method.

   Args:
     ctx: ORC context
     source: Map with:
       :path - File path (optional if :content provided)
       :content - Inline content (optional if :path provided)
       :type - Source type override (optional, will auto-detect)
       Plus type-specific options

   Auto-detection:
     - .csv → CSV extraction
     - .db/.sqlite → SQL extraction
     - .txt/.md → Text extraction
     - .json → JSON extraction (planned)
     - RDF content with :type \"rdf\" → deterministic N-Triples import

   Returns:
     {:status :success/:failed/:not-implemented
      :source-type :csv/:text/:sql/:json/:rdf
      ...type-specific outputs...}"
  [ctx source]

  (#'extract ctx source))

;; --- Unified ontology ---

(defn extract-multiple
  "Extract ontologies from multiple sources.
   Returns a map of source paths/indexes to extraction results."
  [ctx sources]
  (into {}
        (map-indexed
         (fn [idx source]
           [(or (:path source) (str "source-" idx))
            (extract ctx source)])
         sources)))

;; --- Interface ---

(defn extract-unified-multiple
  "Extract ontologies from multiple sources with unified API.

   Returns map of source identifiers to extraction results."
  [ctx sources]

  (#'extract-multiple ctx sources))

;; --- Field analyzer ---

(defn- is-optional-field?
  "Check if a schema entry is optional."
  [[_field-name props _schema]]
  (and (map? props) (:optional props)))

(defn extract-field-metadata
  "Code node executor: Extract schema structure and sample values.

   Reads:
     - schema: Malli schema (as data)
     - sample-data: Vector of sample records

   Writes:
     - field-candidates: Vector of field metadata maps

   Each field candidate includes:
     {:field-name :label
      :field-type \"string\"
      :nullable false
      :description \"...\" ;; from Malli metadata
      :sample-values [\"Acme Corp\" \"Beta Inc\" \"Gamma LLC\"]
      :value-stats {:avg-length 9.3 :unique-count 3}}"
  [{:keys [inputs]}]
  (let [schema (get inputs :schema)
        sample-data (get inputs :sample-data)
        normalized-data (when (seq sample-data)
                          (mapv (fn [record]
                                  (if (and (map? record)
                                           (some string? (keys record)))
                                    (into {} (map (fn [[k v]] [(keyword k) v]) record))
                                    record))
                                sample-data))
        field-candidates (analyze-schema-fields schema normalized-data)]
    {:field-candidates (or field-candidates [])}))

(defn filter-embeddable-fields
  "Code node executor: Post-process LLM assessment.

   Applies confidence threshold, sorts by value, returns final list.

   Reads:
     - llm-assessment: Map with :fields vector from LLM
     - confidence-threshold: Double (default 0.7)

   Writes:
     - embeddable-fields: Vector of field names (keywords)
     - confidence-scores: Map of field-name -> confidence
     - reasoning: Map of field-name -> reasoning string"
  [{:keys [inputs]}]
  (let [assessment (get inputs :llm-assessment)
        threshold (or (get inputs :confidence-threshold) 0.7)
        fields (or (:fields assessment)
                   (get assessment "fields")
                   [])
        normalized-fields (mapv (fn [f]
                                  {:field-name (or (:field-name f)
                                                   (keyword (get f "field-name"))
                                                   (keyword (get f "fieldName")))
                                   :confidence (or (:confidence f)
                                                   (get f "confidence")
                                                   0.0)
                                   :should-embed (or (:should-embed f)
                                                     (get f "should-embed")
                                                     (get f "shouldEmbed")
                                                     false)
                                   :reasoning (or (:reasoning f)
                                                  (get f "reasoning")
                                                  "")})
                                fields)
        embeddable (->> normalized-fields
                        (filter #(:should-embed %))
                        (filter #(>= (:confidence %) threshold))
                        (sort-by :confidence >))]
    {:embeddable-fields (mapv :field-name embeddable)
     :confidence-scores (into {}
                              (map (juxt :field-name :confidence)
                                   normalized-fields))
     :reasoning (into {}
                      (map (juxt :field-name :reasoning)
                           normalized-fields))}))

(defn format-field-candidates-for-llm
  "Format field candidates as a readable string for LLM prompts."
  [field-candidates]
  (str/join "\n\n"
            (map (fn [{:keys [field-name field-type description sample-values value-stats]}]
                   (str "Field: " (name field-name) "\n"
                        "Type: " field-type "\n"
                        (when description
                          (str "Description: " description "\n"))
                        (when (seq sample-values)
                          (str "Sample values:\n"
                               (str/join "\n"
                                         (map-indexed
                                          (fn [i v]
                                            (str "  " (inc i) ". "
                                                 (if (string? v)
                                                   (str "\"" (subs v 0 (min 100 (count v)))
                                                        (when (> (count v) 100) "...") "\"")
                                                   (pr-str v))))
                                          sample-values))
                               "\n"))
                        (when (:avg-length value-stats)
                          (str "Avg length: " (format "%.1f" (:avg-length value-stats)) " chars\n"))
                        (when (:unique-count value-stats)
                          (str "Unique values in sample: " (:unique-count value-stats)))))
                 field-candidates)))

(defn schema-hash
  "Generate a hash for caching schema analysis results."
  [schema]
  (str (hash (pr-str schema))))

(def ^:private colbert-semantic-fields
  "Fields that are commonly semantic and good for ColBERT indexing."
  #{:label :name :title :description :definition :summary
    :content :text :explanation :reasoning :notes :comment})

(def ^:private colbert-non-semantic-fields
  "Fields that are NOT good for ColBERT indexing."
  #{:id :uri :uuid :code :created-at :updated-at :timestamp
    :source-id :entity-type :confidence :score})

(defn detect-colbert-fields
  "Detect which fields from concepts should be indexed in ColBERT.

   Analyzes the structure of concept maps to find text-rich semantic fields
   suitable for late-interaction neural retrieval.

   Args:
     concepts - Vector of concept maps

   Returns:
     {:colbert-fields [:label :description ...]
      :reasoning {:field-name \"reason\" ...}}"
  [concepts]
  (if (empty? concepts)
    {:colbert-fields [:label :description]
     :reasoning {:default "No concepts provided, using default fields"}}
    (let [
          sample (take 10 concepts)
          all-keys (into #{} (mapcat keys sample))

          semantic-found (filterv #(contains? all-keys %) colbert-semantic-fields)

          other-keys (set/difference all-keys
                                     colbert-semantic-fields
                                     colbert-non-semantic-fields)

          additional-semantic-strings
          (->> other-keys
               (filter (fn [k]
                         (let [values (keep k sample)
                               string-vals (filter string? values)]
                           (and (seq string-vals)
                                (> (/ (count string-vals) (max 1 (count values))) 0.5)
                                (> (/ (reduce + (map count string-vals))
                                      (max 1 (count string-vals)))
                                   20)))))
               vec)

          additional-semantic-vectors
          (->> other-keys
               (filter (fn [k]
                         (let [values (keep k sample)
                               vector-vals (filter sequential? values)
                               non-empty-vectors (filter seq vector-vals)]
                           (and (seq vector-vals)
                                (> (/ (count vector-vals) (max 1 (count values))) 0.5)
                                (seq non-empty-vectors)
                                (every? (fn [v] (every? string? v))
                                        non-empty-vectors)))))
               vec)

          colbert-fields (vec (concat semantic-found
                                      additional-semantic-strings
                                      additional-semantic-vectors))

          reasoning (into {}
                          (concat
                           (for [f semantic-found]
                             [f "Known semantic field type"])
                           (for [f additional-semantic-strings]
                             [f "String field with substantial average length"])
                           (for [f additional-semantic-vectors]
                             [f "Vector of strings field"])))]

      {:colbert-fields (if (seq colbert-fields)
                         colbert-fields
                         [:label :description])
       :reasoning (if (seq reasoning)
                    reasoning
                    {:default "Using default fields"})})))

(defn detect-embedding-fields-heuristic
  "Detect embedding fields using heuristics (no LLM).

   Uses detect-colbert-fields logic - suitable when LLM is unavailable
   or for performance-critical paths.

   Args:
     concepts - Vector of concept maps

   Returns:
     {:embedding-fields [:label :description ...]
      :reasoning {:field-name \"reason\" ...}
      :method :heuristic}"
  [concepts]
  (let [{:keys [colbert-fields reasoning]} (detect-colbert-fields concepts)]
    {:embedding-fields colbert-fields
     :reasoning reasoning
     :method :heuristic}))

(defn detect-embedding-fields
  "Detect which fields from concepts should be used for embedding.

   When called with ctx (2-arity), uses LLM-driven ORC workflow to analyze
   field samples and determine which are semantically meaningful. Falls back
   to heuristics if workflow execution fails or ctx is not provided.

   The LLM approach:
   - Analyzes actual sample values, not just field names
   - Works with any datasource without hardcoded field lists
   - Provides reasoning for each decision

   Args:
     concepts - Vector of concept maps (1-arity, uses heuristics)
     ctx - Context with :event-store for sheet execution (2-arity, uses LLM)
     concepts - Vector of concept maps

   Returns:
     {:embedding-fields [:label :description ...]
      :reasoning {:field-name \"reason\" ...}
      :confidence-scores {:field-name 0.95 ...}  ;; Only with LLM
      :method :llm|:heuristic}"
  ([concepts]
   (detect-embedding-fields-heuristic concepts))

  ([ctx concepts]
   (if (nil? ctx)
     (detect-embedding-fields-heuristic concepts)
     (try

       (let [analyze-schema #'analyze-schema
             sample-data (vec (take 10 concepts))
             result (analyze-schema ctx nil nil sample-data :confidence-threshold 0.6)]
         (if (:error result)
           (do
             (u/log :ai.obney.orc.ontology.core.field-analyzer/llm-workflow-failed :error (:error result) :trace-id (:trace-id result))
             (detect-embedding-fields-heuristic concepts))
           {:embedding-fields (:embeddable-fields result)
            :reasoning (:reasoning result)
            :confidence-scores (:confidence-scores result)
            :trace-id (:trace-id result)
            :method :llm}))
       (catch Exception e
         (u/log :ai.obney.orc.ontology.core.field-analyzer/llm-workflow-exception :error (.getMessage e))
         (detect-embedding-fields-heuristic concepts))))))

;; --- Evidence guard ---

(def judge-score-event-type
  "The engine's judge-evidence event. The guard reads it by TYPE and TAG
   only — no code dependency on components/evaluation-service, which is deliberate:
   the ontology-side guard ships regardless of whether the engine-side
   `:no-evidence` outcome (ADR 0023, proposed to the maintainer as CC-14)
   ever lands."
  :judge/score-emitted)

(def ^:private starvation-patterns
  "Feedback shapes in which a judge SAYS its input was absent.

   This is not sentiment analysis and not a quality heuristic — every
   pattern here matches a judge REPORTING the absence of the material it
   was asked to judge, which is a fact about the evaluation harness rather
   than about the response. The last pattern is the 1.0-scoring judge from
   the incident: reasoning from an assumed document is the same starvation
   as scoring against an empty one, and it is the arm a score-based filter
   would have missed entirely."
  [#"(?i)\binput\s+context\b[^.]{0,60}?\b(?:was|is)\s+empty\b"
   #"(?i)\bempty\s+input\s+context\b"
   #"(?i)\b(?:no|missing|absent)\s+input\s+context\b"
   #"(?i)\bcontext\b[^.]{0,60}?\b(?:was|is)\s+empty\b"
   #"(?i)\bempty\s*\(\s*json\s*\{\s*\}\s*\)"
   #"(?i)\bcontext\b[^.]{0,40}?\bwas\s+not\s+provided\b"
   #"(?i)\bno\s+(?:source\s+)?(?:document|documents|evidence|context|input|excerpt|excerpts|material)\s+(?:was|were)?\s*(?:provided|supplied|available|given|present)\b"
   #"(?i)\b(?:document|source|context|input)\s+(?:was|is)\s+(?:not\s+provided|unavailable|missing)\b"
   #"(?i)\bnothing\s+(?:was\s+)?(?:provided|supplied)\s+to\s+(?:judge|evaluate|assess|score)\b"
   #"(?i)\bassum(?:e|ed|ing)\s+(?:that\s+)?the\s+(?:document|source|context|input)\s+(?:exists|existed|is\s+correct)\b"])

(def min-substantive-feedback-chars
  "Below this, a judge's feedback is too thin for the cheap check to read
   either way, so the occurrence goes to the verifier instead of being
   guessed at in either direction. Deliberately low: the deterministic
   layer must own the COMMON case, and real judge feedback in this system
   is a paragraph, not a phrase."
  40)

(defn starved-feedback?
  "True when a judge's own feedback reports that it had no input to judge."
  [feedback]
  (boolean (and (string? feedback)
                (some #(re-find % feedback) starvation-patterns))))

(def verifier-model
  "A DIFFERENT model family from the grounding judges under inspection —
   asking a judge from the same family whether a sibling's explanation is
   grounded reproduces the sibling's blind spot. Pinned as a default the
   caller can override, mirroring RR-2's config-slot pattern."
  "qwen/qwen3.5-flash-02-23")

(def ^:private verifier-instruction
  "You are checking whether an evaluation's written explanation REFERENCES REAL
EVIDENCE from the material it was given, or whether it was produced without
anything to look at.

You are NOT judging whether the evaluation's verdict was correct, kind, or
well-calibrated. Exactly one question: does this explanation cite something
concrete that could only have come from real input — a quoted phrase, an
identifier, a number, a named section — or is it generic, hypothetical, or
explicitly reasoning about material it says it did not receive?

Answer with `true` when the explanation is anchored in real material, and
`false` when it is not. When you cannot tell, answer `false` — an evaluation
whose grounding cannot be established must not become a durable claim.")

(defn- verifier-workflow
  [model]
  (let [orc #'orc/workflow
        blackboard #'orc/blackboard
        llm #'orc/llm]
    (orc "ontology-evidence-explanation-verifier"
         (blackboard {:explanation :string
                      :grounded-verdict :string})
         (llm "verify-explanation"
              :model model
              :instruction verifier-instruction
              :reads [:explanation]
              :writes [:grounded-verdict]
              :options {:max-retries 3
                        :retry-delay-ms [500 1500 3000]
                        :use-function-calling? true}))))

(def ^:private surrounding-quoting
  "Whitespace and the quoting a model wraps a one-word answer in. Deliberately
   TINY: every character added here is a new way for prose to be mistaken for a
   verdict. Markdown emphasis and trailing punctuation are NOT in it — a field
   that needs them stripped is no longer a one-word answer."
  #"(?:^[\s\"'`]+)|(?:[\s\"'`]+$)")

(defn grounding-established?
  "Did the verifier's `:grounded-verdict` field ESTABLISH grounding?

   True only when the field, after trimming whitespace and any surrounding
   quoting, is exactly the word `true` (any case). Prose, a template echo, a
   multi-word answer, an empty field, `nil` — all of it means grounding was NOT
   established, which is the same answer as `false`.

   WHY SO STRICT (CC-4b measured this; evidence in orc-sessions
   `doc/build-timeline/evidence/cc4b/`). The predecessor asked only whether the
   field contained the token `true` anywhere. On 8 of 30 real verifier calls the
   forced tool call was dropped upstream and dscloj silently reissued in marker
   mode, so the field came back carrying leaked `</think>` reasoning and/or an
   echoed `{grounded-verdict}` template. In three of those the model had stated
   `false` — once six times over — and an incidental `true`, quoted from the
   instruction or from the very explanation under inspection, was enough to
   return GROUNDED. A verdict is a one-word answer; anything longer is the
   model thinking out loud, and thinking out loud is not a verdict.

   This is the contract the guard's fail-CLOSED promise rests on, so it is
   asymmetric ON PURPOSE: a field that fails to parse costs a deferred claim,
   which is re-derivable; a field misread as `true` costs a fabricated one,
   which is not.

   ONE OF THE EIGHT IS NOT OURS, recorded here so it is not re-diagnosed. In
   arm-2 call 04 the field was the literal `{grounded-verdict}` placeholder
   while the model's final block was a correctly formed
   `[[ ## grounded-verdict ## ]] true`. That verdict was lost UPSTREAM:
   `dscloj.core/parse-output`'s `extract-field` (fork 41ef3e20,
   `src/dscloj/core.clj`) selects with `re-find`, which returns the FIRST
   marker occurrence. Replaying that captured 9,643-char response through the
   same regex yields, in order, `{grounded-verdict}`, `{true/false}`, `true`,
   `true` — the two real answers are the last two, and dscloj returns the
   first. Our side never sees the raw text; it reads an already-parsed field,
   so there is no occurrence for us to choose. Strict extraction cannot
   recover a verdict dscloj discarded; it only guarantees the loss lands on
   the safe side. Reported as an upstream finding alongside ADR 0025's
   `tool_choice` key fix, which removes the marker fallback that produces
   these fields in the first place."
  [raw-verdict]
  (= "true" (-> raw-verdict
                str
                (str/replace surrounding-quoting "")
                (str/lower-case))))

(defn default-explanation-verifier
  "The REAL layer-2 verifier: a one-node LLM workflow asking whether an
   explanation references real evidence from the input.

   orc-service is resolved lazily (the ontology's established pattern for
   optional heavy paths) so requiring this namespace never drags an LLM
   stack onto the load path, and so the deterministic layer is provably
   reachable without one.

   Fails CLOSED, and `grounding-established?` is what makes that true rather
   than aspirational: an unreachable verifier, a failed workflow AND a verdict
   field that is anything other than a one-word `true` all return false. An
   evidence-starved claim that slips through is the failure this slice exists
   to prevent; a claim deferred because grounding could not be established is
   recorded as an exclusion and can be re-derived."
  [ctx {:keys [explanation model]}]
  (try
    (let [build! #'orc/build-workflow!
          execute #'orc/execute
          sheet-id (build! ctx (verifier-workflow (or model verifier-model)))
          result (execute ctx sheet-id {:explanation (str explanation)})]
      (if (= :success (:status result))
        (grounding-established? (get-in result [:outputs :grounded-verdict]))
        (do (u/log :ai.obney.orc.ontology.core.evidence-guard/verifier-workflow-failed :status (:status result))
            false)))
    (catch Exception e
      (u/log :ai.obney.orc.ontology.core.evidence-guard/verifier-unavailable :error (.getMessage e))
      false)))

(defn verifier-fn
  "The injected-capability seam: the context may carry
   `:ontology/evidence-verifier`; otherwise the real implementation is
   used."
  [ctx]
  (or (:ontology/evidence-verifier ctx) default-explanation-verifier))

(defn declared-basis
  "What a delta says its content rests on, normalised.

   Absent means DERIVED, never `nil`: a pre-CC-6 delta carrying only
   `:from-legacy-corpus` keeps exactly the meaning it had, and everything else
   defaults to `:judged-occurrences` — the strict arm. A new field cannot
   loosen an old delta."
  [{:keys [evidence-basis from-legacy-corpus]}]
  (or evidence-basis
      (if from-legacy-corpus :legacy-corpus :judged-occurrences)))

(def declared-bases-admitted
  "The bases that stand WITHOUT resolvable judge evidence, because they assert
   something other than a judgement:

     :legacy-corpus             a prior corpus, whose judgements predate the
                                guard and cannot be re-resolved.
     :classification-signature  the deterministic input the classifier keyed
                                on — a fact about what was asked, recorded
                                before anything could have judged the answer.
     :emitted-artifact          a verbatim artifact the engine itself produced,
                                recorded from the completion event that
                                produced it.
     :emitted-artifact-outcome  RR-20: a verbatim artifact PLUS the engine's
                                own deterministic execution-outcome (bookend
                                `:status`) recorded with it — still a fact
                                about what happened, never a judge's verdict.
     :campaign-verdict          RR-20: RR-19's durable campaign verdict
                                (`:ontology/tree-class-occurrence-recorded`
                                `:success`) reinforcing a shape claim a
                                bookend already created — a distinct,
                                LATER mechanical fact from
                                `:emitted-artifact-outcome`, still no
                                occurrence named, still never a judge's
                                verdict.
     :authored                  designer-written corpus knowledge (CC-9d). A
                                curated guard has no occurrences by
                                construction: nothing judged it, because
                                nothing could have. Authorship is a true,
                                auditable statement about where the content
                                came from, which is precisely the category
                                this set exists for.

   `:judged-occurrences` is deliberately ABSENT: it is the arm that means
   'resolve my episodes', and a delta declaring it while naming none is exactly
   the ungrounded assertion this guard exists to refuse.

   CC-9d ADDED `:authored` HERE RATHER THAN BUILDING A SECOND EXEMPTION ROUTE,
   and that is the whole point: the seeding path needs exactly what this set
   already grants — admission for a delta that names no occurrence — so a
   parallel bypass would be a second way past the guard, which is how a guard
   stops meaning anything. What authorship additionally buys (enforcement) is
   granted downstream in the claim fold, where the spec puts it, NOT here. The
   ONE rule below is unchanged: a delta that DOES name occurrences is resolved
   normally whatever it declares."
  #{:legacy-corpus :classification-signature :emitted-artifact
    :emitted-artifact-outcome :campaign-verdict :authored})

(defn occurrence-explanations
  [occurrence-verdicts]
  (->> occurrence-verdicts
       (mapcat (comp #(map :feedback %) :evidence))
       (remove str/blank?)
       vec))

(def guard-events
  #{:ontology/promotion-evidence-excluded
    :ontology/claim-deltas-refused})

(defmulti claim-guard*
  "Apply one guard event to the claim-guard read-model state.
   State: {granularity {target-id {:excluded-evidence [...] :refusals [...]}}}"
  (fn [_state event] (:event/type event)))

(defmethod claim-guard* :default [state _event] state)

(defmethod claim-guard* :ontology/promotion-evidence-excluded
  [state event]
  (update-in state [(:granularity event) (:target-identifier event) :excluded-evidence]
             (fnil conj [])
             {:reason (:reason event)
              :episodes (:episodes event)
              :operation (:operation event)
              :kind (:kind event)
              :content (:content event)
              :settled-by (:settled-by event)
              :excluded-at (:excluded-at event)}))

(defmethod claim-guard* :ontology/claim-deltas-refused
  [state event]
  (update-in state [(:granularity event) (:target-identifier event) :refusals]
             (fnil conj [])
             {:reason (:reason event)
              :attempted-version (:attempted-version event)
              :current-version (:current-version event)
              :delta-count (:delta-count event)
              :refused-at (:refused-at event)}))

;; --- Static ontology ---

(defn static-ontology-get-concept-by-uri
  "Find a concept by its URI across all static ontologies."
  [uri]
  (first (filter #(= uri (:uri %)) (get-all-static-concepts))))

(defn get-failure-concept-for-dimension
  "Map evaluation dimension name to root failure concept URI."
  [dimension-name]
  (get static-ontology-dimension->failure-uri dimension-name))

;; --- Queries ---

(defn queries-tenant-scoped? [ctx]
  (some? (:tenant-id ctx)))

;; --- Retrieval ---

(def default-retrieval-config
  "Default configuration for retrieval operations."
  {:min-success-rate 0.7
   :min-confidence   0.5
   :min-frequency    0.2
   :max-results      10
   :bfs-max-depth    2
   :bfs-decay        0.6
   :rrf-k            60})

(defn expand-from-problem-type
  "Expand concept neighborhood starting from a problem type URI.

   Returns related problems, success patterns, and failure types."
  [problem-uri & {:keys [max-depth] :or {max-depth 2}}]
  (retrieval-expand-concept-neighborhood [problem-uri] :max-depth max-depth))

(defn format-rich-pattern
  "Format a pattern with its conditions and actions for LLM injection.

   Produces multi-line entries when the pattern carries rich tree-design
   context (a tree-DSL snippet in :action-taken.target plus a :reason
   and an :expected-outcome at the top level). Lower-fidelity patterns
   fall back to the single-line label format.

   For RLM tree-design principles, the multi-line form surfaces:
     - the named pattern (Action),
     - the WHY behind the rule (Why, from :action-taken.reason),
     - what success looks like (Expected outcome, from :expected-outcome),
     - the tree-DSL snippet rendered as a Clojure code block.

   Works for any domain (drone, legal, sales, construction, RLM tree-design)."
  [pattern]
  (let [{:keys [label context-conditions action-taken confidence avg-score
                count evidence-episodes expected-outcome]} pattern

        conditions-str (when (and context-conditions (seq context-conditions))
                         (->> context-conditions
                              (map (fn [[k v]]
                                     (let [field-name (name k)
                                           value-str (cond
                                                       (number? v) (if (float? v)
                                                                     (format "%.1f" (double v))
                                                                     (str v))
                                                       (coll? v) (str "[" (str/join " " (map #(if (float? %) (format "%.1f" %) %) v)) "]")
                                                       :else (str v))]
                                       (str field-name "=" value-str))))
                              (str/join ", ")))

        {action-type :type
         action-target :target
         action-reason :reason} action-taken

        target-str (when action-target
                     (if (coll? action-target)
                       (str "[" (str/join " " (map #(if (float? %) (format "%.1f" %) %) action-target)) "]")
                       (str action-target)))

        action-summary (when action-type
                         (str action-type (when target-str " (see code block below)")))

        score (or avg-score confidence 0.8)

        episode-count (or evidence-episodes count)]

    (cond
      (and conditions-str
           action-type
           (or action-reason expected-outcome target-str))
      (let [header (str "- **" (or action-type label) "** — when " conditions-str
                        (format " (%.0f%% confidence" (* 100 score))
                        (when (and episode-count (pos? episode-count))
                          (str ", " episode-count " episode" (when (> episode-count 1) "s")))
                        ")")
            target-block (when target-str
                           (str "\n  - Action:\n    ```clojure\n    " target-str "\n    ```"))
            reason-line (when action-reason
                          (str "\n  - Why: " action-reason))
            outcome-line (when expected-outcome
                           (str "\n  - Expected outcome: " expected-outcome))]
        (str header target-block reason-line outcome-line))

      (and conditions-str action-summary)
      (str "- When " conditions-str ": " action-type
           (when target-str (str " to " target-str))
           (format " (%.0f%% success" (* 100 score))
           (when (and episode-count (pos? episode-count))
             (str ", " episode-count " episodes"))
           ")")

      :else
      (str "- **" label "**"
           (format " (%.0f%% confidence" (* 100 (or confidence 0.8)))
           (when (and episode-count (pos? episode-count))
             (str ", " episode-count " episodes"))
           ")"))))

(defn retrieve-with-rrf
  "Retrieve and merge results from multiple sources using RRF.

   Useful when combining results from:
   - Graph-based expansion
   - Pattern matching
   - Problem type matching

   Args:
     batches: Vector of result batches, each [{:uri :score} ...]
     opts: {:k N} - RRF constant (default 60)

   Returns:
     Merged and re-ranked results"
  [batches & {:keys [k] :or {k 60}}]
  (graph/merge-batches batches :method "rrf"))

(defn resolve-colbert-search-fn
  "Dynamically resolve the ColBERT search-for-rrf function if available.

   Returns the function or nil if ColBERT component is not loaded."
  []
  (integrations/capability :colbert :search-for-rrf))

(defn colbert-search-concepts
  "Search concepts using ColBERT late-interaction retrieval.

   This function provides ColBERT search results formatted for RRF fusion
   with graph and embedding signals. Requires the ColBERT component to be
   available and an index to be created.

   Args:
     ctx - Context map containing :event-store
     query-text - Natural language search query
     opts:
       :colbert-index-id - ColBERT index UUID (required)
       :limit - Maximum results (default 20)
       :weight - Score weight for RRF (default 1.0)

   Returns:
     Vector of {:uri :score} or nil if ColBERT not available"
  [ctx query-text & {:keys [colbert-index-id limit weight]
                      :or {limit 20 weight 1.0}}]
  (when-let [search-fn (resolve-colbert-search-fn)]
    (when colbert-index-id
      (try
        (search-fn ctx {:query query-text
                        :index-id colbert-index-id
                        :k limit
                        :normalize? true
                        :weight weight})
        (catch Exception e
          (println "ColBERT search failed:" (.getMessage e))
          nil)))))

(defn- resolve-colbert-search-batch-fn
  "Dynamically resolve the ColBERT search-for-rrf-batch function if available."
  []
  (integrations/capability :colbert :search-for-rrf-batch))

(defn colbert-search-concepts-batch
  "Batched `colbert-search-concepts`: ONE index load + ONE bridge round-trip for ALL
   queries. Returns a vector aligned to `query-texts`, each a vector of {:uri :score}
   for RRF fusion — or nil when ColBERT is unavailable.

   Args:
     ctx - Context map containing :event-store
     query-texts - Vector of natural-language search queries
     opts:
       :colbert-index-id - ColBERT index UUID (required)
       :limit - Maximum results per query (default 20)
       :weight - Score weight for RRF (default 1.0)"
  [ctx query-texts & {:keys [colbert-index-id limit weight]
                      :or {limit 20 weight 1.0}}]
  (when-let [search-fn (resolve-colbert-search-batch-fn)]
    (when (and colbert-index-id (seq query-texts))
      (try
        (search-fn ctx {:queries (vec query-texts)
                        :index-id colbert-index-id
                        :k limit
                        :normalize? true
                        :weight weight})
        (catch Exception e
          (println "ColBERT batch search failed:" (.getMessage e))
          nil)))))

(defn- hit-key [{:keys [ontology-id uri]}]
  (if ontology-id [ontology-id uri] uri))

(defn- fuse-and-enrich
  "Fuse graph/embedding/colbert result-lists via RRF and enrich the top `limit` with
   concept metadata + per-signal ranks. The shared fusion core of hybrid-search (one
   query) and hybrid-search-batch (per query). Input result maps carry their native
   score keys: graph/colbert use :score, embedding uses :similarity."
  [{:keys [graph-results embedding-results colbert-results weights limit signals]
    :or {weights {:graph 1.0 :embedding 1.0 :colbert 1.0} limit 10}}]
  (let [
        records (into {} (map (juxt hit-key identity)) (concat colbert-results embedding-results graph-results))
        graph-batch (when (seq graph-results)
                      (mapv (fn [{:keys [score] :as hit}]
                              {:uri (hit-key hit) :score (* (:graph weights 1.0) score)})
                            graph-results))
        embedding-batch (when (seq embedding-results)
                          (mapv (fn [{:keys [similarity] :as hit}]
                                  {:uri (hit-key hit) :score (* (:embedding weights 1.0) similarity)})
                                embedding-results))
        colbert-batch (mapv #(assoc % :uri (hit-key %)) colbert-results)
        batches (filterv seq [graph-batch embedding-batch colbert-batch])
        merged (when (seq batches)
                 (graph/merge-batches batches :method "rrf"))
        graph-ranks (when graph-batch
                      (into {} (map-indexed (fn [i {:keys [uri]}] [uri (inc i)]) graph-batch)))
        embedding-ranks (when embedding-batch
                          (into {} (map-indexed (fn [i {:keys [uri]}] [uri (inc i)]) embedding-batch)))
        colbert-ranks (when colbert-batch
                        (into {} (map-indexed (fn [i {:keys [uri]}] [uri (inc i)]) colbert-batch)))
        enriched-results (->> merged
                              (take limit)
                              (mapv (fn [{:keys [uri score]}]
                                      (let [hit (get records uri)
                                            concept (if (:ontology-id hit) hit (or (static-ontology-get-concept-by-uri (:uri hit)) hit))]
                                        (merge {:uri (:uri hit) :score score
                                                :graph-rank (get graph-ranks uri)
                                                :embedding-rank (get embedding-ranks uri)
                                                :colbert-rank (get colbert-ranks uri)
                                                :label (:label concept) :description (:description concept)
                                                :scope (:scope concept)}
                                               (select-keys hit [:ontology-id :concept-id]))))))]
    {:results enriched-results
     :graph-results (vec graph-results)
     :embedding-results (vec embedding-results)
     :colbert-results (vec colbert-results)
     :method "rrf"
     :signals-enabled signals
     :batches-used (cond-> []
                     (seq graph-batch) (conj :graph)
                     (seq embedding-batch) (conj :embedding)
                     (seq colbert-batch) (conj :colbert))}))

(defn format-context-for-llm
  "Format ontology context for injection into LLM prompts.

   Converts the rich ontology context into a markdown-formatted string
   suitable for inclusion in an LLM system prompt.

   Args:
     context: Map from build-ontology-context containing:
       - :recommended-patterns - Success patterns to use
       - :patterns-to-avoid - Failure patterns to avoid
       - :related-concepts - Neighborhood expansion results
       - :problem-hierarchy - Problem type with parent/child
       - :few-shot-trees - Similar successful trees
     opts: Configuration map
       :include #{:patterns :failures :related :hierarchy :examples}
       :max-items - Max items per section (default 5)

   Returns:
     Formatted markdown string for prompt injection, or nil if empty"
  [context {:keys [include max-items]
            :or {include #{:patterns :related}
                 max-items 5}}]
  (let [sections (atom [])

        _ (when (and (contains? include :hierarchy)
                     (:problem-hierarchy context))
            (let [h (:problem-hierarchy context)]
              (swap! sections conj
                     (str "### Problem Context\n"
                          "**Type:** " (:label h) "\n"
                          (when (:description h)
                            (str "**Description:** " (:description h) "\n"))
                          (when (seq (:broader h))
                            (str "**Category:** " (str/join ", " (map :label (:broader h)))))))))

        _ (when (and (contains? include :patterns)
                     (seq (:recommended-patterns context)))
            (let [patterns (:recommended-patterns context)
                  has-rich-context? (some #(and (:context-conditions %) (:action-taken %)) patterns)
                  dedupe-key (fn [p]
                               (let [conds (:context-conditions p)
                                     action (:action-taken p)]
                                 (str (pr-str (sort-by first conds))
                                      "|" (:type action) "|" (pr-str (:target action)))))
                  deduped-patterns (->> patterns
                                        (filter #(and (:context-conditions %) (:action-taken %)))
                                        (group-by dedupe-key)
                                        (map (fn [[_ group]]
                                               (let [best (apply max-key :confidence group)]
                                                 (assoc best
                                                        :count (reduce + 0 (keep :count group))
                                                        :evidence-episodes (count group)))))
                                        (sort-by :confidence >)
                                        vec)
                  final-patterns (if (seq deduped-patterns) deduped-patterns patterns)]
              (swap! sections conj
                     (if has-rich-context?
                       (str "### Learned Rules from Success Patterns\n"
                            (->> final-patterns
                                 (take max-items)
                                 (map format-rich-pattern)
                                 (str/join "\n")))
                       (str "### Recommended Patterns\n"
                            (->> final-patterns
                                 (take max-items)
                                 (map #(str "- **" (:label %) "**: " (:description %)))
                                 (str/join "\n")))))))

        _ (when (and (contains? include :failures)
                     (seq (:patterns-to-avoid context)))
            (swap! sections conj
                   (str "### Patterns to Avoid\n"
                        (->> (:patterns-to-avoid context)
                             (take max-items)
                             (map #(str "- **" (or (:description %) (:failure-uri %)) "**"
                                        (when (:frequency %)
                                          (str " (frequency: " (format "%.0f%%" (* 100 (:frequency %))) ")"))))
                             (str/join "\n")))))

        _ (when (and (contains? include :related)
                     (seq (:related-concepts context)))
            (swap! sections conj
                   (str "### Related Concepts\n"
                        (->> (:related-concepts context)
                             (take max-items)
                             (map #(let [concept (static-ontology-get-concept-by-uri (:uri %))]
                                     (str "- **" (or (:label concept) (:uri %)) "**"
                                          (when (:description concept)
                                            (str ": " (:description concept))))))
                             (str/join "\n")))))

        _ (when (and (contains? include :examples)
                     (seq (:few-shot-trees context)))
            (swap! sections conj
                   (str "### Similar Successful Trees\n"
                        (->> (:few-shot-trees context)
                             (take max-items)
                             (map #(str "- Tree `" (:tree-id %) "`"
                                        (when-let [pm (:problem-match %)]
                                          (str " (success rate: " (format "%.0f%%" (* 100 (:success-rate pm))) ")"))
                                        (when (seq (:matching-patterns %))
                                          (str "\n  Patterns: " (str/join ", " (:matching-patterns %))))))
                             (str/join "\n")))))]

    (when (seq @sections)
      (str "## Relevant Knowledge\n\n" (str/join "\n\n" @sections)))))

;; --- Todo processors ---

(def recurrence-verdicts #{:success :failure :timeout})

(defn worked-pattern-trait
  "RR-20: the `:content` of the SHAPE'S `:strength` claim — a class's worked
   pattern for the tree identified by `fingerprint`. Identity is now PER
   SHAPE (`[:strength (worked-pattern-trait fp)]`), not one fixed literal per
   class: a class that succeeds with two shapes keeps two distinct claims,
   and a re-success of one can only ever reinforce its own. Phrased as a
   readable trait (CC-3's `assemble-summary` renders claim content into
   `:summary`, which ColBERT indexes) rather than as a bare sentinel, while
   still embedding the fingerprint so the identity is genuinely per-shape."
  [fingerprint]
  (str "emits the " fingerprint " worked tree for tasks of this class"))

(defn failed-shape-trait
  "RR-20: the `:content` of the SHAPE'S `:weakness` claim — a class's failed
   shape for the tree identified by `fingerprint`. Distinct kind AND distinct
   content from `worked-pattern-trait` at the same fingerprint, so a shape
   that fails once and later succeeds (a repair) records TWO claims that
   never touch each other, per `WorkedPatternsAreProvenNotMerelyRecent`."
  [fingerprint]
  (str "emitted the " fingerprint " tree for tasks of this class and it failed"))

(defn shape-claim
  "The claim this writer owns for `kind`+`trait` (one shape, one section), or
   nil. Identity is `[kind trait]` — the same key every time this exact shape
   recurs in this exact section, which is what makes reinforcement (rather
   than a rival entry) possible."
  [claims kind trait]
  (first (filter #(and (= kind (:kind %)) (= trait (:content %))) claims)))

(defn shape-delta
  "The ONE claim operation one shape's bookend expresses, scoped to a single
   `[kind trait]` identity (one fingerprint, one section — :strength or
   :weakness). RR-20 narrows what the ADR 0021 ratchet-removal note below
   still explains: THIS `:edit` can only ever reword the SAME shape's claim,
   never swap a different shape's source in over it — the class-level 'one
   slot, last write wins' defect CV-2 originally had is gone because the slot
   is now per-shape.

     no claim for THIS shape yet    -> :add
     same source as recorded        -> :support   (a repeat emit is
                                                    CORROBORATION that this
                                                    is the class's worked/
                                                    failed shape)
     different source, SAME shape   -> :edit       (rare: the fingerprint
                                                    normalizes away :fn/
                                                    :instruction content, so
                                                    two emits can share a
                                                    shape while differing in
                                                    exact source text —
                                                    reinforces AND rewords
                                                    in place)

   `evidence-basis` (RR-20) declares what this rests on and DIFFERS by
   caller: the bookend writer (`enrich-tree-class-with-emitted-dsl!`) always
   passes `:emitted-artifact-outcome` — the engine's own deterministic
   execution outcome (the bookend's `:status`) recorded together with the
   artifact it produced. The occurrence-corroboration writer
   (`corroborate-worked-patterns-from-occurrence!`) always passes
   `:campaign-verdict` — a SEPARATE, LATER mechanical fact (the whole
   campaign, not just this Phase-2 execution, reached :success) — and ONLY
   ever reaches the `:support` branch (it reinforces an existing claim, it
   never creates or rewords one). Neither basis names an occurrence, so
   CC-7 can never validate the claim and CC-9's gate can never let it
   enforce from either alone; `:campaign-verdict` support is durable via
   `read-models/reinforce-claim`'s `:verdict-corroborations` counter, which
   `harvest/best-recommended-pattern` ranks on ahead of raw support."
  [existing kind trait source-text evidence-basis]
  (let [base {:kind kind
              :content trait
              :context-guard nil
              :recommendation source-text
              :episodes []
              :from-legacy-corpus false
              :evidence-basis evidence-basis}]
    (cond
      (nil? existing)
      (assoc base :operation :add)

      (= source-text (:recommendation existing))
      (assoc base :operation :support :target-claim (:claim-id existing))

      :else
      (assoc base :operation :edit :target-claim (:claim-id existing)))))

(def claim-delta-retry-attempts
  "RR-20: how many times `record-claim-deltas-with-retry!` re-reads and
   retries after a `:stale-claim-set` refusal before giving up (each retry
   is a fresh read, so this bounds worst-case work, not correctness — the
   loop always converges once writers stop racing)."
  5)

(defn claim-delta-write-lost-the-race?
  "RR-20: true when `record-claim-deltas` needs to be retried against a
   fresh read, on EITHER of the two distinct ways a concurrent writer can
   beat this one to `class-id`'s claim set:

     1. `:ontology/refused` — the HANDLER's own pre-check
        (`recorded-claim-delta-count` vs our `:claim-set-version`) found the
        version already stale before it even tried to append.
     2. `::anom/conflict` — the version was current when the handler
        checked, but a DIFFERENT concurrent append committed between that
        check and this one's own append; the event-store's CAS predicate
        (compared at the `dosync` boundary, not the handler's read) is what
        catches THIS race, and it returns the anomaly directly rather than
        the handler's `:ontology/refused` map — the handler never runs its
        own refusal branch for this case, because ITS pre-check passed.
   Missing either arm silently drops a concurrent writer's delta: this is
   what RR-20's fail-then-repair scenario (two claim-affecting bookends with
   no synchronizing sleep) exercises for real, and is the root cause the
   debug session for this slice traced before landing this function."
  [result]
  (or (boolean (:ontology/refused result))
      (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result))))

(defn emitted-tree-source-text
  "RR-20 (OfferedPatternsAreUsable): the EXACT text to offer, preferring
   RR-6's `:generated-tree-source` (the re-emission authority — real
   production bookends always carry it alongside `:generated-tree`, per
   `rlm-tree-executor`'s `execute-tree`) and falling back to `(pr-str
   generated-tree)` only for a bookend that predates that field entirely
   (replay of pre-RR-6 events). Never prefers `pr-str` when the exact source
   is available — that was the defect this slice's `a-pattern-is-offered-
   as-its-exact-recorded-source` test names."
  [{:keys [generated-tree generated-tree-source]}]
  (or generated-tree-source (some-> generated-tree pr-str)))

(def ontology-descriptions-index-name
  "Stable :index-name used for every rebuild of the ontology descriptions
   corpus. Per the C-2b sub-grill (Decision 1): one giant index across all
   3 granularities, distinguished only by metadata."
  "ontology-descriptions")

(defn- average-confidence
  "Average :confidence across a description body's :strengths vector.
   Returns 0.0 for empty/missing strengths."
  [body]
  (let [strengths (:strengths body)
        confidences (keep :confidence strengths)]
    (if (seq confidences)
      (double (/ (reduce + confidences) (count confidences)))
      0.0)))

(defn- effective-granularity
  "R05b: route per-document granularity by body :scope when set. R05a's
   behavioral seeds emit via :ontology/record-tree-description (so their
   record-model granularity is :tree-fingerprint) but their body carries
   :scope :behavioral-subtree. The indexer surfaces this as a distinct
   metadata granularity so search-descriptions :granularity
   :behavioral-subtree filters cleanly. Legacy descriptions without
   :scope (and explicit :scope :tree-class) keep their original
   granularity — additive routing only kicks in for :behavioral-subtree."
  [d]
  (let [scope (-> d :body :scope)]
    (if (= scope :behavioral-subtree)
      :behavioral-subtree
      (:granularity d))))

(defn build-document-collection
  "Convert the flattened description records into the
   (:collection, :document-ids, :document-metadatas) tuple required by
   colbert/create-index!.

   Document content = the description's :summary (the field designed for
   retrieval embedding). Metadata carries granularity + target-id +
   average confidence + last-update so post-retrieval filtering and the
   downstream LLM reranker (C-2b-2) can reason about each match."
  [descriptions]
  (reduce
    (fn [{:keys [collection document-ids document-metadatas] :as acc} d]
      (let [content (or (-> d :body :summary) "")
            g (effective-granularity d)
            doc-id (str g ":" (pr-str (:target-id d)))
            metadata {:granularity g
                      :target-id (:target-id d)
                      :confidence (average-confidence (:body d))
                      :last-update (str (:recorded-at d))}]
        (-> acc
            (assoc :collection (conj collection content))
            (assoc :document-ids (conj document-ids doc-id))
            (assoc :document-metadatas (conj document-metadatas metadata)))))
    {:collection [] :document-ids [] :document-metadatas []}
    descriptions))

(defn minutes-since
  "Whole minutes elapsed between an ISO-string timestamp and now. Returns
   Long/MAX_VALUE if ts is nil (so the timer-condition always fires when
   no rebuild has happened).

   Non-private so tests can with-redefs it to simulate elapsed time
   without sleeping."
  [iso-str]
  (if (nil? iso-str)
    Long/MAX_VALUE
    (try
      (let [past (java.time.OffsetDateTime/parse iso-str)
            now (java.time.OffsetDateTime/now)]
        (.toMinutes (java.time.Duration/between past now)))
      (catch Exception _ Long/MAX_VALUE))))

(defn should-rebuild?
  "Decide whether the current reindex-state crosses either the threshold
   or timer trigger from the reindex-config. Cold-start (no index built
   yet) always rebuilds when there are any descriptions to index."
  [reindex-state reindex-config descriptions-count]
  (let [{:keys [events-since-last-rebuild last-rebuild-timestamp index-built?]} reindex-state
        {:keys [reindex-threshold-events reindex-timer-minutes]} reindex-config]
    (cond
      (and (pos? descriptions-count) (not index-built?))
      true

      (>= events-since-last-rebuild reindex-threshold-events)
      true

      (and (pos? events-since-last-rebuild)
           (>= (minutes-since last-rebuild-timestamp) reindex-timer-minutes))
      true

      :else false)))

(defonce
  ^{:private true
    :doc "In-process coalescing latches for the threshold/timer reindex
   trigger, keyed by [tenant-id index-name], each holding
   {:running? bool :dirty? bool}.

   WHY: grain's todo-processor processes events CONCURRENTLY — the
   execution-fn spawns one thread per event. A burst of N
   *-description-updated events therefore lands N handler threads in
   maybe-rebuild! at once. Each independently reads
   events-since-last-rebuild via the read-model, all observe it >=
   threshold (the :colbert/index-created reset from any one rebuild isn't
   appended/visible until after the others have already read), and each
   dispatches a redundant — and expensive — ColBERT rebuild. Observed: a
   10-event burst produced 2..10 rebuilds.

   FIX: a per-(tenant,index) coalescing latch with re-check. One thread
   becomes the runner; concurrent threads mark the latch :dirty? instead
   of dispatching. The runner re-reads-and-redecides until no work is
   pending, so a burst collapses into a single rebuild WITHOUT a
   lost-wakeup (the event that crosses the threshold always forces at
   least one more pass that observes it). In-memory per JVM (grain
   reindex processors are singleton per tenant). The mint force-rebuild!
   path is intentionally NOT latched — it must always rebuild so a
   freshly-minted behavior is indexed immediately."}
  reindex-latches*
  (atom {}))

(defn reindex-latch
  "Return the coalescing latch (an atom holding {:running? :dirty?}) for
   `k`, creating it on first use. Idempotent under contention."
  [k]
  (or (get @reindex-latches* k)
      (get (swap! reindex-latches* update k
                  (fn [existing] (or existing (atom {:running? false :dirty? false}))))
           k)))

;; --- Serialization ---

(defn validate-turtle
  "Basic validation of generated Turtle syntax.
   Returns {:valid? true/false :errors [...]}."
  [turtle-str]
  (let [errors (atom [])]
    (let [quote-count (count (filter #(= \" %) turtle-str))]
      (when (odd? quote-count)
        (swap! errors conj "Unbalanced quotes")))
    (let [without-uris (-> turtle-str
                           (str/replace #"<[^>]+>" "")
                           (str/replace #"\"(?:\\\\.|[^\"\\\\])*\"" ""))
          used-prefixes (->> (re-seq #"(\w+):" without-uris)
                             (map second)
                             (remove #{"http" "https" "urn" "mailto" "file"})
                             set)
          declared-prefixes (->> (re-seq #"@prefix\s+(\w+):" turtle-str)
                                 (map second)
                                 set)]
      (when-not (every? declared-prefixes used-prefixes)
        (swap! errors conj "Potentially undeclared prefix")))
    {:valid? (empty? @errors)
     :errors @errors}))

;; --- Harvest ---

(def default-harvest-config
  "Conservative defaults. Only a class with real recurring volume (>= 10),
   EVERY judge dimension clearing :dimension-floor over its most recent
   :dimension-window scored occurrences, EVERY one of its last
   :consistency-window scored occurrences clearing :consistency-floor, and
   shape-convergence (<= half as many distinct shapes as occurrences) is
   harvested.

   Mirrors specs/ontology.allium's config block:
     promotion_occurrence_threshold = 10   maximum_shape_ratio = 0.5
     consistency_window = 5                consistency_floor  = 0.75
     dimension_floor = 0.75                dimension_window   = 10

   CC-24b (ADR 0029, MEASURED) moved both floors 0.8 -> 0.75 and made them
   mean something chosen. `evaluation/core/scale.clj` maps a discrete 1-5
   judge scale by (n-1)/(max-min), so the achievable per-occurrence set at one
   judge is exactly {0, 0.25, 0.5, 0.75, 1.0}. 0.8 sat in the GAP between band
   4 (described to the judge as good work) and band 5, so it silently read
   'PERFECT ONLY': every floor in {0.60, 0.70, 0.75} produced identical gate
   behaviour on the real corpus, as did every floor in {0.80, 0.90, 1.00}.
   0.75 is the lattice point that says what we mean — band 4 or better
   qualifies — and 'good work counts' is now an explicit policy rather than an
   accident of where a number fell between bands. If band 4 later proves too
   permissive the lever is one ratified value ON the lattice (0.75 -> 1.00),
   not a redesign. Anything reading these as '80%' is wrong; they are lattice
   points, and the spec says so.

   :dimension-window is new: the dimension axis reads a TRAILING window, never
   a raw lifetime mean (see every-dimension-qualified?)."
  {:min-occurrences    10
   :dimension-floor    0.75
   :dimension-window   10
   :consistency-window 5
   :consistency-floor  0.75
   :max-shapes-ratio   0.5})

(def floor-comparison-tolerance
  "The spec's `config floor_comparison_tolerance` (CC-29): floor verdicts are
   exact on the DISCRETE judge scale. Judge scores are band values on a 1-5
   scale mapped (n-1)/4 — the achievable set is {0, 0.25, 0.5, 0.75, 1.0},
   quantum 0.25 at one judge (0.25/J when J judges are averaged). CORRECTED
   2026-08-12: this said 0.05, which was never true of the shipped scale; the
   tolerance argument only gets SAFER with the real, larger quantum. That the
   FLOORS sit between bands is CC-24's crux, not this tolerance's concern. Any
   legitimate below-floor mean differs from the floor by at
   least quantum/occurrence-count — orders of magnitude above binary-
   representation error, which is what actually produced a rejection: a class
   whose every score was exactly 0.8 projected its lifetime mean as
   0.7999999999999999 (double accumulation) and was rejected by the >= 0.8
   floor (measured, CC-26 real-stream check). A floor comparison therefore
   tolerates representation error strictly smaller than the scale's quantum;
   the tolerance can never change a verdict between two values the scale can
   actually distinguish.

   SAFE WINDOW: legitimate distinctions are >= quantum/count (>= 0.25/40 =
   6.25e-3 at forty occurrences, and larger below that; ~1e-5 even at absurd
   counts —
   realistic counts), representation error is ~1e-13, so 1e-9 keeps at least
   three orders of magnitude of margin on EACH side. Applied at the floor-
   COMPARISON seam only — `x >= floor` becomes `x >= (- floor tolerance)` in
   the two floor predicates below — never exact/rational arithmetic in the
   folds, which would change read-model state shapes (version bumps, cache
   serialization) for a problem the comparison seam fixes completely. The
   gate's integer thresholds (:min-occurrences) and the exactly-computed
   shapes ratio need no tolerance: no comparable artifact is possible there."
  1.0E-9)

(def known-judge-dimension-count
  "LOAD-BEARING ASSUMPTION, recorded so the next change is a DECISION and not
   an accident.

   The corpus this gate was calibrated against has EXACTLY ONE judge
   dimension (implementation-turn/coding-outcome, 120/120 scores). Two things
   depend on that number:

   1. `:dimension-floor` is UNIFORM. The spec's `dimension_floor(dimension)`
      is per-dimension; with one dimension there is nothing to differentiate,
      so a single value is faithful. A SECOND judge makes per-dimension floors
      a real question — a strict judge and a lenient judge cannot share a
      floor without one of them being wrong.
   2. The per-dimension floor is a PROVABLE NO-OP at this count: with one
      value, `every? >= floor` and `mean >= floor` are the same predicate
      (pinned by cc26-dimension-floor-is-a-no-op-at-one-judge). That equality
      is what made the dimensional half of CC-26 safe to ship without
      re-baselining the live corpus. It stops holding at TWO judges, and at
      FIVE judges the collapsed mean promotes a class with a dimension
      scoring ZERO (measured: 3 judges -> 0.667 fails, 4 -> 0.750 fails,
      5 -> exactly 0.800, promotes).

   `maybe-harvest!` logs ::judge-dimension-count-changed whenever the observed
   count differs from this, so the assumption announces its own expiry."
  1)

(defn consistently-qualified?
  "The spec's `consistently_qualified(tree_class, consistency_window)`: the
   LAST `consistency-window` scored occurrences must EACH clear
   `consistency-floor`.

   DELIBERATELY NOT a mean over the window. A mean over the window would
   reintroduce the identical defect one level up — 4 zeros among 20 average to
   0.800 exactly the way they did before, just over a shorter span. 'Has
   qualified repeatedly' is a property of every element of the window, not of
   its average.

   A class with FEWER than `consistency-window` scored occurrences has not yet
   demonstrated repetition and does not qualify — the conservative bar IS the
   safety. Unscored occurrences carry no judge signal and simply do not
   participate in the window (an unscored occurrence is not a zero; cf. the
   spec's ExcludeAbstainedEvaluations).

   CC-29: the floor comparison tolerates representation error — see
   `floor-comparison-tolerance`. A window score is a COMPUTED per-occurrence
   aggregate (mean over that occurrence's judge scores), so a set of band
   values whose true mean sits exactly ON the floor can accumulate to a double
   strictly below the floor literal (e.g. bands 0.6/0.9/0.9 -> 0.7999999999999999)
   and must not be rejected for it. Genuine deficits are >= quantum/judge-count,
   far outside the tolerance."
  [occurrence-scores consistency-window consistency-floor]
  (boolean
    (and (number? consistency-window)
         (pos? consistency-window)
         (number? consistency-floor)
         (sequential? occurrence-scores)
         (let [window (take-last consistency-window occurrence-scores)
               effective-floor (- consistency-floor floor-comparison-tolerance)]
           (and (= (count window) consistency-window)
                (every? #(and (number? %) (>= % effective-floor)) window))))))

(defn every-dimension-qualified?
  "The spec's `quality.all(dimension => dimension.score >= dimension_floor)`:
   EVERY judge dimension clears `dimension-floor`.

   CC-24b (ADR 0029): `judge-averages` is each judge's mean over its most
   recent `dimension_window` SCORED occurrences — a TRAILING WINDOW, never a
   raw lifetime mean. Measured: the lifetime mean against the floor fired 0
   times in 105 real positions at BOTH 0.80 and 0.75 (the dominant class would
   have needed ~97 consecutive perfect occurrences, and its zero-blocks came
   from ENGINE defects since fixed); trailing-10 fires 38/105 and abstains
   67/105 — it can do both on real data, which is ADR 0027's bar. Eligibility
   can therefore be LOST as well as gained; that is intended (enforcement is
   continuously earned).

   One catastrophic dimension vetoes promotion and cannot be compensated for
   by strength elsewhere — which a MEAN over the dimensions does allow
   (measured: at five judges, one dimension scoring ZERO still averages to
   exactly 0.800 and promoted).

   No judge signal at all (nil / empty) never qualifies: a class that was
   never judged has not been judged well.

   Note this is the OTHER axis from consistently-qualified? — per JUDGE over
   the class's recent scored occurrences, versus per OCCURRENCE over the
   consistency window. They are the two marginals of the (judge x occurrence)
   matrix, are not interchangeable, and must not be collapsed back into one
   scalar.

   CC-29: the floor comparison tolerates representation error — see
   `floor-comparison-tolerance`. This is the MEASURED artifact site: a class
   whose every score was exactly 0.8 projected its mean as
   0.7999999999999999 (double accumulation in the standing read-model) and was
   rejected by the raw >= 0.8 floor. An at-floor mean qualifies; a genuinely
   below-floor mean (>= quantum/window short) is still rejected."
  [judge-averages dimension-floor]
  (boolean
    (and (number? dimension-floor)
         (map? judge-averages)
         (seq judge-averages)
         (let [effective-floor (- dimension-floor floor-comparison-tolerance)]
           (every? #(and (number? %) (>= % effective-floor))
                   (vals judge-averages))))))

(defn harvest-candidate?
  "Pure conservative gate. Returns true iff the class is RECURRING and
   well-scored on BOTH axes (every DIMENSION over its recent scored
   occurrences, every recent OCCURRENCE over the consistency window), per
   config.

   Metrics:
     :occurrences               lifetime count of verdict-qualified campaigns
     :judge-trailing-averages   {judge-name -> mean over that judge's most
                                recent :dimension-window scored occurrences} —
                                the DIMENSION axis. CC-24b (ADR 0029) renamed
                                this from :judge-averages, deliberately: the
                                LIFETIME mean is a different projection, is
                                still live for logging/parity, and firing the
                                gate on it measured 0/105 real positions. A
                                name that no longer says which projection it
                                is would let that regression back in silently.
     :occurrence-scores         per-occurrence aggregate judge score, in
                                temporal order (most recent LAST) — the
                                CONSISTENCY axis

   RR-21: the COHERENCE clause (winning-shape-coherence, the ratio of
   distinct successful terminal shapes to successful campaigns) is computed
   and REPORTED (see `winning-shape-coherence` and the durable
   :ontology/shape-coherence-reported event) but never gates here — the
   spec's `rule PromoteWellScoredClass` carries no coherence `requires`, and
   ADR 0029's dossier note is explicit: a threshold that has never fired
   cannot be distinguished from one that never will, so it is measured
   before it is trusted to block. Threshold calibration is a later,
   data-driven slice.

   A non-positive occurrence count never passes (avoids divide-by-zero +
   seeds/total=0 slipping through)."
  [{:keys [occurrences judge-trailing-averages occurrence-scores]}
   {:keys [min-occurrences dimension-floor
           consistency-window consistency-floor]}]
  (boolean
    (and (number? occurrences)
         (pos? occurrences)
         (>= occurrences min-occurrences)
         (every-dimension-qualified? judge-trailing-averages dimension-floor)
         (consistently-qualified? occurrence-scores consistency-window consistency-floor))))

(defn harvest-gate-report
  "The gate's verdict, CLAUSE BY CLAUSE, so a decision nobody watched can be
   read back afterwards. `:candidate?` is `harvest-candidate?` itself — this
   is a lens on the gate, never a second implementation of it.

   RR-21: the `:coherence` clause reports `winning-shape-coherence`'s
   `:status` (`:qualified` | `:rejected` | `:not-measurable` — see that fn)
   verbatim as `:verdict`, alongside the counts and ratio it observed. Unlike
   every other clause here, `:coherence`'s verdict is NEVER folded into
   `:candidate?` — see `harvest-candidate?`. `:not-measurable` replaces the
   pre-RR-21 `:abstained` (the all-trees `distinct-tree-shapes` measure,
   which passed vacuously on 100% of occurrences in both real stores and is
   now retired from harvest — the consolidator keeps its own independent
   descriptive aggregate for reflection)."
  [{:keys [occurrences judge-trailing-averages occurrence-scores winning-shape-coherence]
    :as metrics}
   {:keys [min-occurrences dimension-floor dimension-window
           consistency-window consistency-floor max-shapes-ratio] :as config}]
  {:recurring   {:verdict (if (and (number? occurrences)
                                   (pos? occurrences)
                                   (>= occurrences min-occurrences))
                            :qualified :rejected)
                 :occurrences occurrences
                 :threshold min-occurrences}
   :dimension   {:verdict (cond
                            (not (seq judge-trailing-averages)) :abstained
                            (every-dimension-qualified? judge-trailing-averages
                                                        dimension-floor) :qualified
                            :else :rejected)
                 :trailing-averages judge-trailing-averages
                 :window dimension-window
                 :floor dimension-floor}
   :consistency {:verdict (if (consistently-qualified? occurrence-scores
                                                       consistency-window
                                                       consistency-floor)
                            :qualified :rejected)
                 :window (vec (take-last (or consistency-window 0) occurrence-scores))
                 :floor consistency-floor}
   :coherence   {:verdict (:status winning-shape-coherence)
                 :ratio (:ratio winning-shape-coherence)
                 :successful-campaigns (:successful-campaigns winning-shape-coherence)
                 :successful-shape-observations (:successful-shape-observations
                                                  winning-shape-coherence)
                 :distinct-successful-shapes (:distinct-successful-shapes
                                              winning-shape-coherence)
                 :max-shapes-ratio max-shapes-ratio}
   :candidate?  (harvest-candidate? metrics config)})

(defn harvest-behavioral-subtree-uri [id] (str "behavioral-subtree:" id))

(defn durable-through
  "`events` (already in durable event-store order) truncated to include
   everything up to and INCLUDING the event whose `:event/id` is
   `upper-bound-event-id`. nil bound means unbounded — 'everything so far'.
   Falls back to unbounded if the named id is not found (defensive only; the
   report command always names an event it just read from the same store)."
  [events upper-bound-event-id]
  (if (nil? upper-bound-event-id)
    (vec events)
    (let [truncated
          (persistent!
           (reduce (fn [acc event]
                     (let [acc (conj! acc event)]
                       (if (= upper-bound-event-id (:event/id event))
                         (reduced acc)
                         acc)))
                   (transient [])
                   events))]
      (if (and (seq truncated) (= upper-bound-event-id (:event/id (peek truncated))))
        truncated
        (vec events)))))

(defn best-recommended-pattern
  "RR-20 (exact behavioral change 4): the worked DSL for the class.

   Only `:strengths` entries ever carry `:recommended-pattern` — a failed
   shape is recorded as a `:weakness` (RR-20's writer, `todo-processors/
   enrich-tree-class-with-emitted-dsl!`), so it surfaces under
   `:recommended-alternative` in `:weaknesses`, never here. A failed-only
   class therefore returns nil by construction: never a failed shape offered
   as the worked pattern.

   Ranked by `:verdict-corroborations` descending FIRST, then `:confidence`
   (support) descending, then `:last-reinforced-at` descending (most
   recently reinforced first). This IS 'prefer success-backed shapes
   corroborated by verdict occurrences over bare emitted artefacts', made
   an EXPLICIT primary sort key rather than left to support alone: a bare
   artefact re-emitted repeatedly earns support too (CV-2's ordinary
   `:support` reinforcement), so ranking on raw `:confidence` alone could
   let enough bare repetition outrank a shape corroborated by only ONE real
   campaign verdict — a class-of-evidence conflation the acceptance
   criterion 'prefers... over' does not tolerate. `:verdict-corroborations`
   (absent/nil treated as 0) is durable ONLY on the claims RR-19's
   `todo-processors/corroborate-worked-patterns-from-occurrence!` reinforced
   with `:evidence-basis :campaign-verdict` (`read-models/reinforce-claim`),
   so ANY corroborated shape (>= 1) outranks EVERY merely-repeated one (= 0)
   before support is ever compared. No cross-event query belongs here:
   `harvest-body`'s signature (`desc` + `occurrences`) is the propagated
   RR-20 contract and stays exactly that — the field is read straight off
   the already-assembled `:strengths` entries."
  [desc]
  (->> (:strengths desc)
       (filter :recommended-pattern)
       (sort (fn [a b]
               (let [by-corroboration (compare (or (:verdict-corroborations b) 0)
                                               (or (:verdict-corroborations a) 0))]
                 (if-not (zero? by-corroboration)
                   by-corroboration
                   (let [by-confidence (compare (or (:confidence b) 0.0)
                                                (or (:confidence a) 0.0))]
                     (if (zero? by-confidence)
                       (compare (or (:last-reinforced-at b) "")
                                (or (:last-reinforced-at a) ""))
                       by-confidence))))))
       first
       :recommended-pattern))

(defn harvest-name [class-id] (str "harvested-tree-class-" class-id))

;; --- Rule extraction ---

(defn format-episode-for-extraction
  "Format a successful episode for rule extraction analysis.

   Domain-agnostic: preserves all conditions and actions as-is.
   The LLM will interpret them based on the domain context.

   Supports both new field names (context-conditions) and legacy (state-conditions)."
  [{:keys [pattern-uri confidence scenario-context context-conditions state-conditions
           action-taken domain-type expected-outcome recorded-at]}]
  (let [
        conditions (or context-conditions state-conditions {})
        outcome (or expected-outcome
                    (:expected-behavior scenario-context)
                    (:name scenario-context))]
    {:pattern-uri pattern-uri
     :confidence confidence
     :domain-type (or domain-type "unknown")
     :expected-outcome outcome
     :scenario (when scenario-context
                 {:name (:name scenario-context)
                  :difficulty (:difficulty scenario-context)})
     :conditions conditions
     :action (when action-taken
               {:type (:type action-taken)
                :target (:target action-taken)
                :reason (:reason action-taken)})
     :timestamp recorded-at}))

(defn group-episodes-by-outcome
  "Code node executor: Group episodes by expected outcome for pattern analysis.

   Domain-agnostic: groups by expected-outcome (string) rather than fixed behavior enums.
   Summarizes conditions as generic key-value aggregations."
  [{:keys [inputs]}]
  (let [episodes (get inputs "episodes")
        grouped (->> episodes
                     (group-by :expected-outcome)
                     (map (fn [[outcome eps]]
                            (let [
                                  all-conditions (mapcat #(seq (:conditions %)) eps)
                                  numeric-conditions (->> all-conditions
                                                          (filter #(number? (second %)))
                                                          (group-by first)
                                                          (map (fn [[k vs]]
                                                                 [k {:avg (/ (reduce + (map second vs))
                                                                             (count vs))
                                                                     :min (apply min (map second vs))
                                                                     :max (apply max (map second vs))}]))
                                                          (into {}))]
                              {:expected-outcome (str (or outcome "unknown"))
                               :episodes eps
                               :count (count eps)
                               :conditions-summary numeric-conditions
                               :action-types (frequencies (keep #(get-in % [:action :type]) eps))})))
                     vec)]
    {"grouped-episodes" grouped}))

(def ^:private scenario-schema
  [:map
   [:name {:optional true} :string]
   [:difficulty {:optional true} [:or :string :keyword :int :double]]])

(def ^:private episode-schema
  [:map
   [:pattern-uri :string]
   [:confidence :double]
   [:domain-type :string]
   [:expected-outcome {:optional true} [:or :string :keyword]]
   [:scenario {:optional true} scenario-schema]
   [:conditions schemas/extracted-rule-conditions]
   [:action {:optional true} schemas/extracted-rule-action]])

(def rule-extraction-workflow
  "ORC workflow that extracts condition-action rules from successful episodes.

   Domain-agnostic: Works with any domain by accepting domain-type and domain-description.

   Blackboard:
   - tree-id: UUID of tree to analyze (input)
   - domain-type: Type of domain, e.g. 'drone-control', 'legal-review' (input)
   - domain-description: Human-readable domain context for LLM (input)
   - episodes: Formatted successful episodes
   - grouped-episodes: Episodes grouped by expected outcome
   - extracted-rules: Condition-action rules (output)

   Flow:
   1. Load successful episodes with rich context
   2. Group by expected outcome
   3. Extract condition-action rules with LLM (domain-aware)"
  (orc/workflow "ontology-rule-extraction"
    (orc/blackboard
      {
       :tree-id :uuid
       :domain-type {:default "unknown"} :string
       :domain-description {:default "General task execution"} :string

       :episode-count :int
       :episodes [:vector episode-schema]
       :grouped-episodes [:vector [:map
                                   [:expected-outcome :string]
                                   [:episodes [:vector episode-schema]]
                                   [:count :int]
                                   [:conditions-summary
                                    [:map-of :keyword
                                     [:map [:avg :double] [:min :double] [:max :double]]]]
                                   [:action-types [:map-of [:or :keyword :string] :int]]]]

       :extracted-rules [:vector [:map
                                  [:condition-description :string]
                                  [:conditions schemas/extracted-rule-conditions]
                                  [:action-description :string]
                                  [:action schemas/extracted-rule-action]
                                  [:confidence :double]
                                  [:success-rate :double]
                                  [:evidence-count :int]
                                  [:expected-outcome :string]]]})

    (orc/sequence "extract"
      (orc/code "load-episodes"
        :fn "ai.obney.orc.ontology.core.rule-extraction/load-episodes-for-extraction"
        :reads ["tree-id"]
        :writes ["episode-count" "episodes"])

      (orc/code "group-episodes"
        :fn "ai.obney.orc.ontology.core.rule-extraction/group-episodes-by-outcome"
        :reads ["episodes"]
        :writes ["grouped-episodes"])

      (orc/llm "extract-rules"
        :model "anthropic/claude-sonnet-4"
        :instruction "You are analyzing successful episodes to extract reusable condition-action rules.

DOMAIN CONTEXT:
- Domain type: {{domain-type}}
- Description: {{domain-description}}

Your task is to identify CONDITION-ACTION patterns that led to success in this domain.

Review the grouped-episodes which contain:
- Expected outcome (what the episode was trying to achieve)
- Episode conditions (domain-specific state at decision time)
- Actions taken (what actions led to success)
- Condition summaries (min/max/avg values for numeric conditions)

For each outcome group with 2+ episodes, extract rules in this JSON format:

{
  \"condition_description\": \"When [human-readable condition description]\",
  \"conditions\": {
    \"field_name\": [\"operator\", value],  // e.g., [\">\", 0.2], [\"<\", 100], [\"between\", 10, 50]
    ...
  },
  \"action_description\": \"[Human-readable action to take]\",
  \"action\": {
    \"type\": \"action_type\",
    \"field\": \"value\",
    ...
  },
  \"confidence\": 0.92,
  \"success_rate\": 0.95,
  \"evidence_count\": 5,
  \"expected_outcome\": \"outcome_name\"
}

Rules should be:
1. SPECIFIC - Include numeric thresholds based on the data
2. ACTIONABLE - Describe what action to take, not just what to avoid
3. DOMAIN-APPROPRIATE - Use terminology fitting the domain context
4. EVIDENCED - Based on actual successful episodes

Extract 1-3 rules per outcome group. Focus on the most consistent patterns.
Return an array of rule objects. If insufficient data, return empty array []."
        :reads ["grouped-episodes" "episode-count" "domain-type" "domain-description"]
        :writes ["extracted-rules"]))))

(defn build-extraction-workflow!
  "Build the rule extraction workflow. Returns sheet-id."
  [ctx]
  (orc/build-workflow! ctx rule-extraction-workflow))

;; --- Task classifier ---

(defn search-descriptions
  [ctx opts]
  ((integrations/capability :ontology :search-descriptions)
   ctx opts))

(def classifier-intent
  "Text passed as :rerank-with-intent to the reranker. Communicates
   that the caller is doing tree-class assignment so the reranker
   reasons about structural fit rather than free-form relevance."
  (str "I'm classifying a task to find the best-matching tree-class. "
       "Return the candidate whose recommended pattern best fits the "
       "task's structural shape and inputs/outputs. Weight domain / "
       "subject-matter fit, not only structural shape: if a candidate's "
       "avoid-when matches what the task is doing, down-rank it even if its "
       "shape fits. Higher fitness = better tree-class match."))

(def behavioral-classifier-intent
  "R05b: text passed as :rerank-with-intent when classify-behaviors
   queries the behavioral-subtree corpus. Shipped INTO the reranker
   prompt verbatim. Shared with R05d (consolidator behavioral
   inference) — defined once here, referenced from both call sites.

   Public (no ^:private) so R05d can require + reuse the same constant
   without copying — drift between R05b and R05d would silently change
   the reranker's interpretation of behavioral matches."
  (str "I'm classifying a task to find behavioral subtrees — reusable "
       "competencies (analysis / validation / research / design / etc.) "
       "— that compose into the structural shape this task needs. Return "
       "candidates whose recommended-pattern best fits what the task "
       "ACCOMPLISHES, not just what shape it has. Weight domain / "
       "subject-matter fit, not only structural shape: if a candidate's "
       "avoid-when matches what the task is doing, down-rank it even if its "
       "shape fits. Higher fitness = better behavioral match."))

(def classify-opts-schema
  [:map
   [:task-signature         :string]
   [:parent-summary         {:optional true} [:maybe :string]]
   [:threshold              [:and number? [:>= 0.0] [:<= 1.0]]]
   [:walk-down?             {:optional true} :boolean]
   [:specificity-threshold  {:optional true}
                            [:and number? [:>= 0.0] [:<= 1.0]]]
   [:bundle-threshold       {:optional true}
                            [:and number? [:>= 0.0] [:<= 1.0]]]
   [:retrieval-gate         {:optional true} [:and int? [:>= 0]]]
   [:model                  {:optional true} [:maybe :string]]])

(def default-walk-down? true)

(def default-specificity-threshold 0.9)

(def default-bundle-threshold 0.6)

(def default-retrieval-gate 3)

(def ^:private default-merge-candidate-count 5)

(def ^:private max-walk-depth
  "Hard depth cap on walk-down recursion (R-C2d-2 mitigation). At
   this depth we stop walking even if more children would fit and log
   ::walk-down-depth-cap-hit so HITL can surface pathological taxonomies."
  5)

(defn build-effective-signature
  "Concatenate the caller's task-signature with the optional parent
   summary. Pure string operation."
  [task-signature parent-summary]
  (if (and parent-summary (pos? (count parent-summary)))
    (str task-signature
         "\n\nPARENT CONTEXT (the surrounding tree this node lives in):\n"
         parent-summary)
    task-signature))

(defn coerce-to-uuid
  "Normalize a target-id to a UUID:
   - already a UUID → return as-is
   - string that PARSES as a UUID (e.g. round-tripped through JSON) →
     parse it back to the original UUID
   - other string (e.g. fingerprint like 'seed:tree:ChunkedExtraction')
     → derive a stable UUID via nameUUIDFromBytes

   The middle case is critical: ColBERT serializes target-id values
   through JSON, so UUID seeds come back as strings like
   '00000000-c1c1-4001-b001-d0c0a0a0a0a1'. Treating those as opaque
   bytes (hashing) produces a DIFFERENT UUID that doesn't match the
   original seed — the walk-down classifier would return the right
   candidate then lose its identity at the coerce step. Parsing first
   preserves the round-trip."
  [v]
  (cond
    (uuid? v) v
    (string? v) (try (java.util.UUID/fromString v)
                     (catch IllegalArgumentException _
                       (java.util.UUID/nameUUIDFromBytes (.getBytes v "UTF-8"))))
    :else (java.util.UUID/nameUUIDFromBytes (.getBytes (str v) "UTF-8"))))

(def ^:private tree-class-uri-prefix "tree-class:")

(defn- uri->target-id
  "Strip the 'tree-class:' prefix from a concept URI and parse as UUID
   when possible (matches the URI scheme set up by C-2d-1's reactive
   processor)."
  [uri]
  (let [bare (cond-> uri
               (str/starts-with? uri tree-class-uri-prefix)
               (subs (count tree-class-uri-prefix)))]
    (try (java.util.UUID/fromString bare)
         (catch Exception _ bare))))

(defn- task-classifier-get-narrower-concepts [ctx uri]
  ((integrations/capability :ontology :get-narrower-concepts)
   ctx uri))

(defn- get-description [ctx granularity target-id]
  ((integrations/capability :ontology :get-description)
   ctx granularity target-id))

(defn- task-classifier-get-concept-by-uri [ctx uri]
  ((integrations/capability :ontology :get-concept-by-uri)
   ctx uri))

(defn default-domain-family-parent-fn
  "CV-A item 3 (`DomainFamilyIsALeafOnTheDomainAxis`) — the real default for
   the `:domain-family-parent-fn` injected capability: when `target-id`'s
   own tree-class concept IS a domain family (agent-authored provenance, a
   non-blank label that is not its own bare id — the same discriminator
   `default-domain-children-fn` uses per child), returns
   `{:parent-id :domain-label}` — its OWN birth shape (first skos:broader
   edge) and its own concept label. Returns nil when `target-id` is not a
   family (an ordinary shape class), so the caller never mistakes 'not a
   family' for a lookup failure."
  [ctx target-id]
  (do
    (let [uri (str tree-class-uri-prefix target-id)
          concept (task-classifier-get-concept-by-uri ctx uri)
          label (:label concept)]
      (when (and concept
                 (= :agent-authored (:kind (:provenance concept)))
                 (string? label)
                 (not (str/blank? label))
                 (not= label (str target-id)))
        (let [broader-uri (first (:broader concept))]
          {:parent-id (when broader-uri (uri->target-id broader-uri))
           :domain-label label})))))

(defn- non-family-child?
  "CV-E (`DomainFamilyIsALeafOnTheDomainAxis`, revised C5'): true when
   `child-id` is NOT a domain family — walk-down only ever descends into a
   non-family child; a family is a leaf on the domain axis, never a
   shape-ranking or walk-down candidate. Same discriminator CV-A's
   `shape-candidates-only` / classify-task's own `family-candidate?` use,
   via the `:domain-family-parent-fn` seam (default: the real store read).
   Fail closed: a failed lookup is NOT knowledge that the child is safe to
   descend into — it is excluded too, same as a confirmed family."
  [ctx child-id]
  (let [family-parent-fn (or (:domain-family-parent-fn ctx) default-domain-family-parent-fn)]
    (try (nil? (family-parent-fn ctx child-id))
         (catch Throwable t
           (u/log :ai.obney.orc.ontology.core.task-classifier/walk-down-family-lookup-failed :target-id child-id :error (.getMessage t))
           false))))

;; --- Reranker ---

(def default-model
  "RR-2 (ADR 0020 decision 5, AMENDED — see grill GR-2 Q2): the reranker's
   default :model when the caller supplies none.

   The original default was `qwen/qwen3.5-flash-02-23`, described as
   validated-by-repro. CH-1 MEASURED that claim false on this path, N=10
   per arm against the real corpus via direct `rerank!`:

     shipped qwen ...... valid ranking  0/10 | silent marker fallback 10/10
     gemini-3-flash .... valid ranking 10/10 | silent marker fallback  0/10

   The model is not the root cause. `dscloj` emits `:tool_choice` while
   litellm-clj's OpenRouter transform reads `:tool-choice`, so the forced
   tool choice is dropped and function-calling silently degrades to the
   marker parsing this node was configured to avoid (ADR 0025 fixes that
   upstream). But qwen is a thinking model, and Alibaba returns HTTP 400
   for a *forced* tool choice in thinking mode — confirmed by raw curl —
   so it is unsuitable BOTH before and after the key fix. Gemini calls
   the offered tool voluntarily today and can be forced tomorrow.

   This is therefore the evidence-based default under the amended
   understanding, not an interim patch. Re-open only if the post-ADR-0025
   re-measurement surprises us."
  "google/gemini-3-flash-preview")

(defn resolve-model
  "RR-CFG: which model the reranker's 'rerank' node is pinned to for THIS
   call. Per-call opt > per-deployment context slot > the ratified default —
   the same precedence `resolve-timeout-ms` gives the reranker's other
   policy knob, and the same CONTEXT-carried seam the ontology's other model
   slot uses (`:ontology-consolidator-model`, consolidator.clj).

   Why this exists: `default-model` alone made the reranker's model
   unreachable from configuration. During the PR-6 free shakeout every model
   slot was set to a `:free` model and the store still recorded PAID
   `google/gemini-3-flash-preview` executions — a run configured entirely
   free silently billed a model outside every configured slot, because there
   was no slot to configure. The ratified default (grill GR-2 Q2 / CC-9c,
   ADR 0025) is UNCHANGED; it is now the FALLBACK rather than the only
   possibility.

   Set `:ontology-reranker-model` on the context wherever the deployment's
   other model slots are wired."
  [ctx model]
  (or model (:ontology-reranker-model ctx) default-model))

(def ^:private domain-coverage-section
  "RS-1 (proven by RS-P1b run 2 — `development/bench/ood-stress-results/rs-p1b2-sibling-reuse-separated-probe/FINDINGS.md`):
   the domain-coverage section of the reranker instruction, and its
   replacement six-key output contract. Inserted into `reranker-instruction`
   VERBATIM, in place of the shipped three-key output-contract paragraph.
   Kept as its own constant (OUR text, not model prose) so a test can assert
   the instruction carries it byte-for-byte without matching on anything the
   model itself produces."
  "DOMAIN COVERAGE — A SEPARATE VERDICT, NOT A NUMBER.
fitness_score means how well the candidate's SHAPE and intent fit the task.
Separately, for each candidate, judge whether the candidate's DECLARED DOMAIN
— its representative uses and its avoid-when guards — covers the DOMAIN of the
task (what the task is about: the subject matter, the material, the kind of
output). Give a discrete verdict:
  covered    — a representative use names this task's SUBJECT MATTER, its
               MATERIAL (what is read) and its OUTPUT KIND. A shared kind of
               processing ('a pipeline', 'a sequence of passes', 'draft then
               revise') is NOT a domain and never makes a candidate covered.
  partial    — a representative use shares the subject matter but not the
               material or the output kind, or the reverse
  uncovered  — nothing the candidate declares names this task's subject
               matter, material or output kind; the fit, if any, is shape only
  unknown    — you cannot tell from what the candidate declares
The label names the FAMILY of task the domain belongs to: its subject matter
and its output kind — never the instance's own material, dates, names or
numbers. \"marathon-training-plan\" names a family; \"16-week marathon plan for
a first-time runner starting June 3\" names one instance of it. Two tasks that
share subject matter and output kind but differ in material (a different
runner's numbers, a different contract's clauses) are the SAME family and
must get the SAME label.

You are shown existing-domain-labels: the tenant's own list of family labels
already in use (bounded, most-recently-minted first) — and a candidate may
ALSO carry existing-domain-children: labels of domain children already minted
under that ONE candidate. Both serve ONE purpose — label reuse, never
coverage. Reuse a listed label VERBATIM as domain_label only when this task
shares BOTH the subject matter AND the output kind of that label's family. If
the subject matter differs, or the output kind differs, it is a different
family: coin a new label, even when the other one matches (a chess study plan
and a language-course curriculum are both structured plans, but their subject
matter differs, so they are different families). When you are unsure whether a
listed label covers this task, coin a new label: a new family can later be
merged by a judge that reads full descriptions, while a wrong reuse is final.
existing-domain-labels and
existing-domain-children MUST NOT influence domain_coverage: coverage is
judged solely against the candidate's OWN representative uses and content. A
child (or a tenant label) naming this task's domain does not make a parent
covered — a parent with a matching child is exactly the case where the task
belongs to the child, not to the parent.
Write domain_reasoning BEFORE choosing the verdict: name the representative use
or guard you matched, or state the gap. Also give domain_label: a 2-4 word
kebab-case label of the TASK's own domain (the same label for every candidate
of this task), e.g. \"marathon-training-plan\", \"recipe-scaling\",
\"security-findings-haiku\".

PRODUCE a JSON string of a vector, descending by fitness_score. Each
element is an object with EXACTLY these six keys:
  {\"document_id\":     \"<echo the candidate's document-id verbatim>\",
   \"reasoning\":       \"<concrete, actionable; references specific content>\",
   \"fitness_score\":   <number in [0.0, 1.0]>,
   \"domain_reasoning\": \"<the representative use / guard matched, or the gap>\",
   \"domain_coverage\": \"<covered|partial|uncovered|unknown>\",
   \"domain_label\":    \"<2-4 word kebab-case label of the task's domain>\"}

Example shape:
  [{\"document_id\":\"a\",\"reasoning\":\"...\",\"fitness_score\":0.91,
    \"domain_reasoning\":\"...\",\"domain_coverage\":\"covered\",\"domain_label\":\"contract-comparison\"},
   {\"document_id\":\"b\",\"reasoning\":\"...\",\"fitness_score\":0.42,
    \"domain_reasoning\":\"...\",\"domain_coverage\":\"uncovered\",\"domain_label\":\"contract-comparison\"}]")

(def ^:private reranker-instruction
  (str "You are ranking candidate descriptions by their fitness for a caller's intent.

INPUTS DESCRIBED
- query       — the natural-language query the caller wrote
- intent      — the caller's goal/context: what they are trying to build or decide
- candidates  — a JSON vector of candidate descriptions, each with
                  content (the description's summary text),
                  score (raw ColBERT similarity),
                  document-id (stable id you must echo back),
                  document-metadata {granularity, target-id, confidence, last-update},
                  avoid-when (OPTIONAL — a vector of judge-grounded DOMAIN
                    guards: contexts where this candidate is the WRONG choice
                    even if its summary/shape looks similar),
                  strengths (OPTIONAL — vector of {trait, good-when,
                    recommended-pattern}: when this candidate is the RIGHT fit),
                  weaknesses (OPTIONAL — vector of {trait, avoid-when,
                    recommended-alternative}).
- existing-domain-labels — a bounded JSON vector of strings: every domain
                  family label already in use across the WHOLE tenant (not
                  just one candidate's own children), most-recently-minted
                  first. See DOMAIN COVERAGE below for how to use it.

YOUR JOB
Rank the candidates by how well they FIT THE INTENT, not by raw lexical
overlap with the query. Cross-reference each candidate's CONTENT
against what the caller is actually trying to accomplish. Weight DOMAIN /
subject-matter fit (what the task IS), not just structural shape (what the
task LOOKS like).

AVOID-WHEN IS A HARD RULE — NOT A HINT.
For each candidate that carries an avoid-when list, read it FIRST, before its
content/strengths. If ANY avoid-when entry describes what the task is actually
doing, DOWN-rank that candidate sharply (assign a low fitness_score) EVEN IF
its structural shape or summary fits well — a strong shape match does NOT
override a matching domain guard. When an avoid-when entry fires, your
reasoning MUST quote it and say the task matches it. Prefer a more general
candidate that has no firing guard over an over-specific one whose avoid-when
matches the task.

"
       domain-coverage-section
       "

The output MUST be a raw JSON string starting with `[` and ending with
`]`. No surrounding prose, no code fences, no leading/trailing
explanation.

SCORE DEFINITION
1.0 = perfect fit for the caller's intent.
0.0 = irrelevant to the intent.

REASONING DISCIPLINE (HARD RULE)
Your reasoning MUST be principle-shaped — concrete and actionable. It
must reference something specific in the candidate's content (a node
type, a structural pattern, a confidence trait, a recommended pattern)
that ties to the caller's intent.

DO NOT produce status-shaped reasoning. Forbidden shapes:
- 'looks ok' / 'seems fine' / 'unclear if relevant'
- 'could investigate' / 'might work' / 'further evaluation needed'
- 'matches the query' (vague) / 'general fit' (vague)
- restating the query or the candidate summary without explaining the FIT

Every reasoning entry must answer: 'Why does THIS candidate's specific
content advance the caller's stated intent?' If you cannot answer that
concretely, assign a low fitness_score and say WHAT is missing.

Return ALL candidates — including low-fitness ones, and ones that arrive
with only content, score and document-id — the caller may want the full
ranking. Do not drop any. The output vector MUST contain exactly one entry
per input candidate."))

(def ^:private compact-principle-entry
  "A strengths/weaknesses entry at the RERANKER's provider boundary: the
   COMPACT principle shape EL-2's enrichment actually sends — an actionable
   :trait plus optional guard/advice — with the body-side weight signal
   (:confidence/:evidence-count) OPTIONAL rather than required. A floor,
   not a dialect: a full `ontology-schemas/principle-entry` also validates."
  [:map
   [:trait :string]
   [:good-when               {:optional true} :string]
   [:avoid-when              {:optional true} :string]
   [:recommended-pattern     {:optional true} :string]
   [:recommended-alternative {:optional true} :string]
   [:confidence              {:optional true} :double]
   [:evidence-count          {:optional true} :int]
   [:first-observed-at       {:optional true} :string]
   [:last-reinforced-at      {:optional true} :string]])

(def ^:private candidate-schema
  [:map
   [:content :string]
   [:score number?]
   [:document-id :string]
   [:document-metadata {:optional true}
    [:map
     [:granularity :keyword]
     [:target-id [:or :string :uuid]]
     [:confidence {:optional true} number?]
     [:last-update {:optional true} :string]]]
   [:avoid-when {:optional true} [:vector :string]]
   [:strengths {:optional true} [:vector compact-principle-entry]]
   [:weaknesses {:optional true} [:vector compact-principle-entry]]
   [:existing-domain-children {:optional true} [:vector :string]]])

(defn- reranker-workflow-name
  "The workflow's sheet-identity is deterministic from its NAME
   (orc-service `build-workflow!` derives a v5-UUID sheet-id from the
   name alone, then content-hashes the definition to decide whether to
   rebuild in place). Keep the DEFAULT model's identity pinned to the
   original stable name so existing deployments/dashboards that know it
   as \"ontology-description-reranker\" keep resolving the same sheet.
   A caller-supplied override gets its OWN distinct name/identity —
   never reusing the default sheet's name with different content, which
   would otherwise thrash that sheet's content hash (clear + rebuild in
   place) every time a different model is requested against the same
   name."
  [model]
  (if (= model default-model)
    "ontology-description-reranker"
    (str "ontology-description-reranker--" model)))

(defn reranker-workflow
  "Build the single-:llm-node ORC workflow for the description reranker,
   pinning the 'rerank' node's :model to `model` (RR-2). Pure data — no
   I/O — so tests can assert on the resolved node without a real LLM
   call.

   Inputs (blackboard): :query, :intent, :candidates, :existing-domain-labels
   Output (one :writes slot): :reranked-json
     — a JSON string of the reranked list (parsed back to Clojure in
       `rerank!`). We use a JSON-string output rather than a native
       vector-of-maps because some LLM providers (including
       gemini-3-flash-preview) hang or fail when asked to produce
       deeply-nested structured output via U11 :output-schemas. A
       string output trivially passes structured-output validation;
       we own the parse + validate step downstream."
  [model]
  (orc/workflow (reranker-workflow-name model)
    (orc/blackboard
      {:query                   :string
       :intent                  :string
       :candidates              [:vector candidate-schema]
       :existing-domain-labels  [:vector :string]
       :reranked-json           :string})

    (orc/llm "rerank"
      :model model
      :instruction reranker-instruction
      :reads [:query :intent :candidates :existing-domain-labels]
      :writes [:reranked-json]
      :options {:max-retries 3
                :retry-delay-ms [500 1500 3000]
                :use-function-calling? true})))

(def ^:private max-timeout-retries
  "How many times a TIMED-OUT rerank call is retried (same model, same
   call) before the caller falls through to its fallback path. One retry:
   a timeout is transient infra, not epistemic uncertainty (ADR 0015's
   spirit), and the retry is cheap now that classify is off the blocking
   path. A genuine throw / nil / empty result is NOT retried here — that is
   the reranker failing to rank, and `apply-rerank`'s ColBERT fallback owns
   it (unchanged)."
  1)

(def default-rerank-timeout-ms
  "Fixed, GENEROUS execution budget for one reranker call, replacing the
   generic 300000ms `orc/execute` default the classify->rerank path used to
   inherit (the cliff a live run tripped by ~2s at 302147ms).

   Sizing (from the measured evidence, not a guess):
     - worst controlled-repro completion: 10525 tokens (ARM-E)
     - worst LIVE observed throughput:    ~25 tok/s (the degraded run)
     => worst realistic wall time ~= 10525 / 25 = 421s

   900000ms (15 min) clears that by ~2.1x. The margin is deliberate: this is
   a BACKSTOP against a hung/pathologically-degraded call, not a latency
   control — a bare match to the worst observed case would re-create the
   same cliff one bad provider-hour later. Affordable because RR-1 also
   moves classify off the dispatch thread: a slow rerank now costs turn
   latency only.

   Override per-call with `:timeout-ms` on `rerank!`'s opts, or per-
   deployment with `:rerank-timeout-ms` on the context."
  900000)

(defn- resolve-timeout-ms
  "Per-call opt > per-deployment ctx knob > the fixed default."
  [ctx timeout-ms]
  (or timeout-ms (:rerank-timeout-ms ctx) default-rerank-timeout-ms))

(def ^:private timed-out-result
  "What `rerank!` returns when the call AND its one retry both timed out.

   An EMPTY vector, so every existing caller — all of which test the result
   with `(seq …)` / `(first …)` — behaves exactly as it does for a nil or
   empty reranker result today (fall back / stop descent). The
   `:rerank-timeout? true` metadata is the ADDITIVE signal: a caller that
   cares can ask `timed-out?` and record 'infra was slow' distinctly from
   'the reranker could not rank'. Metadata is invisible to value equality,
   so nothing downstream changes shape."
  (with-meta [] {:rerank-timeout? true}))

(def ^:private domain-coverage-values
  "RS-1: the four values of `specs/ontology.allium`'s `enum DomainCoverage`.
   `parse-reranked-json` reads any `:domain_coverage` outside this set
   (missing, or a string the model sent that is not one of these four) as
   `:unknown` rather than coercing it."
  #{:covered :partial :uncovered :unknown})

(defn- parse-reranked-json
  "Parse + canonicalize + validate the reranker's JSON payload from a
   SUCCESSFUL execution result. Extracted from `rerank!` unchanged when
   RR-1 gave that function its budget/retry/timeout concerns — pure over
   the result map, no execution semantics."
  [result]
  (let [raw-json (get-in result [:outputs :reranked-json])
        json-payload (when (string? raw-json)
                       (let [start (.indexOf raw-json "[")
                             end (.lastIndexOf raw-json "]")]
                         (when (and (>= start 0) (> end start))
                           (subs raw-json start (inc end)))))
        parsed (try
                 (when json-payload
                   (json/read-str json-payload :key-fn keyword))
                 (catch Throwable t
                   (u/log :ai.obney.orc.ontology.core.reranker/rerank-parse-failed
                           :error (.getMessage t)
                           :raw-preview (when raw-json
                                          (subs raw-json 0
                                                (min 200 (count raw-json)))))
                   nil))
        canon (when (sequential? parsed)
                (mapv (fn [e]
                        (cond-> e
                          (:document_id e)   (-> (assoc :document-id (:document_id e))
                                                 (dissoc :document_id))
                          (:fitness_score e) (-> (assoc :fitness-score (:fitness_score e))
                                                 (dissoc :fitness_score))
                          true (assoc :domain-coverage
                                      (let [v (some-> (:domain_coverage e) keyword)]
                                        (if (contains? domain-coverage-values v)
                                          v
                                          :unknown)))
                          true (assoc :domain-label (:domain_label e))
                          true (assoc :domain-reasoning (:domain_reasoning e))
                          true (dissoc :domain_coverage :domain_label :domain_reasoning)
                          true (select-keys [:document-id :reasoning :fitness-score
                                              :domain-coverage :domain-label :domain-reasoning])))
                      parsed))
        valid (when (sequential? canon)
                (filterv #(m/validate schemas/reranked-result %) canon))]
    (when (and (sequential? canon)
               (not= (count canon) (count (or valid []))))
      (u/log :ai.obney.orc.ontology.core.reranker/rerank-dropped-malformed-entries
              :raw-count (count canon)
              :valid-count (count valid)))
    valid))

(defn reranker-rerank!
  "Invoke the reranker workflow with (query, intent, candidates).

   Returns the :reranked-results vector — a vector of
   {:document-id :reasoning :fitness-score} entries in descending
   :fitness-score order. Returns nil if the workflow fails.

   RR-1: when the call TIMED OUT and the one retry ALSO timed out, the
   return value is an EMPTY vector carrying `{:rerank-timeout? true}`
   metadata (read it with `timed-out?`). Callers that only check
   `(seq …)` — every caller today — see it exactly as they see a nil/empty
   result and take their existing fallback path unchanged; the metadata
   only lets a caller distinguish 'infra was slow' from 'the reranker
   could not rank' when it wants to (see apply-rerank's
   :timeout-fallback stamp).

   Args:
     ctx        — context with :event-store / :llm-provider
     opts       — {:query :intent :candidates :model}
       :query       — original NL query string
       :intent      — caller's goal/context string
       :candidates  — vector of candidate maps (each with at least
                      :content :score :document-id :document-metadata)
       :timeout-ms  — optional explicit execution budget for the rerank
                      workflow. Defaults to default-rerank-timeout-ms
                      (see its docstring for the sizing evidence).
       :model       — OPTIONAL OpenRouter model id override for the
                      'rerank' node (RR-2). RR-CFG: when absent, the
                      model resolves from the context slot
                      `:ontology-reranker-model`, and only then falls
                      back to the ratified `default-model`. See
                      `resolve-model`.
       :existing-domain-labels — CV-A item 6: OPTIONAL bounded vector of
                      the tenant's existing domain-family labels, for the
                      reranker's label-reuse instruction. Defaults to []
                      so every existing caller (walk-down's pick-best-child,
                      every pre-CV-A test) that supplies none still fills
                      the blackboard's required slot."
  [ctx {:keys [query intent candidates timeout-ms model existing-domain-labels]}]
  (let [budget-ms (resolve-timeout-ms ctx timeout-ms)
        resolved-model (resolve-model ctx model)
        sheet-id (orc/build-workflow! ctx (reranker-workflow resolved-model))
        inputs   {:query query
                  :intent intent
                  :candidates candidates
                  :existing-domain-labels (or existing-domain-labels [])}
        result   (loop [attempt 0]
                   (let [r (orc/execute ctx sheet-id inputs :timeout-ms budget-ms)]
                     (if (and (= :timeout (:status r))
                              (< attempt max-timeout-retries))
                       (do (u/log :ai.obney.orc.ontology.core.reranker/rerank-timed-out-retrying
                                   :attempt (inc attempt)
                                   :timeout-ms budget-ms
                                   :duration-ms (:duration-ms r))
                           (recur (inc attempt)))
                       r)))]
    (when-not (= :success (:status result))
      (u/log :ai.obney.orc.ontology.core.reranker/rerank-workflow-failed
              :status (:status result)
              :error (:error result)
              :duration-ms (:duration-ms result)))
    (case (:status result)
      :timeout (do (u/log :ai.obney.orc.ontology.core.reranker/rerank-timeout-exhausted
                           :timeout-ms budget-ms
                           :attempts (inc max-timeout-retries))
                   timed-out-result)
      :success (parse-reranked-json result)
      nil)))

;; --- Task classifier ---

(defn- task-classifier-rerank! [ctx opts]
  (#'reranker-rerank!
   ctx opts))

(defn- existing-domain-families* [ctx]
  ((integrations/capability :ontology :existing-domain-families)
   ctx))

(defn- hybrid-search* [ctx opts]
  ((integrations/capability :ontology :hybrid-search)
   ctx opts))

;; --- Reranker ---

(def ^:private family-schema
  "One candidate family shown to the merge judge — CV-C item 3's
   `nearest-families` retrieval, JOINED back here into the blackboard
   input. `:id` a STRING (JSON round-trip — `default-domain-merge-fn`
   re-derives the typed id from its OWN candidate list by string
   comparison, never trusts the judge's echoed id verbatim)."
  [:map
   [:id :string]
   [:label {:optional true} [:maybe :string]]
   [:description :string]])

(def ^:private family-merge-instruction
  "RS-P3's verdict (`development/bench/ood-stress-results/rs-p3-family-merge-probe/FINDINGS.md`):
   both arms converged 8/8 corpus groups; the ONE cross-group false merge
   (recipe scaling merged into a marathon-plan family) happened because the
   instruction stated 'same family = shared subject matter AND output kind'
   but never stated the CONVERSE — the judge treated a shared output kind
   alone as sufficient. This instruction states both converses explicitly
   (verdict's fix #1); covered-seed protection running BEFORE this judge
   (verdict's fix #2) is CV-A's existing ordering, unchanged here."
  (str "You decide whether a NEW task belongs to an EXISTING domain family or starts a new one.\n\n"
       "A domain family is the set of tasks that share BOTH its SUBJECT MATTER (what the task is "
       "about) AND its OUTPUT KIND (what kind of thing the task produces) — e.g. every request to "
       "scale a tested recipe to a different batch size is ONE family whatever the dish, venue or "
       "equipment; a request to compute nutrition labels is a DIFFERENT family even though it also "
       "involves recipes. The CONCRETE INSTANCE (the dish, the database engine, the runner's age) "
       "never makes a new family.\n\n"
       "BOTH axes must match for \"same\": a DIFFERENT subject matter is a NEW family even when the "
       "output kind matches (a marathon-training plan and a recipe-scaling plan share the output "
       "kind \"a structured, multi-component plan derived from specific input parameters\" but "
       "differ in subject matter — DIFFERENT families); a DIFFERENT output kind is a NEW family "
       "even when the subject matter matches (recipe scaling and nutrition-label computation share "
       "the subject matter \"recipes\" but differ in output kind — DIFFERENT families).\n\n"
       "Judge each axis at the level of the candidate family's OWN label and description, never at an "
       "umbrella category above it. \"Physical activity scheduling\" is an umbrella over marathon "
       "training and injury rehabilitation, which are DIFFERENT subject matters; \"technical "
       "troubleshooting\" is an umbrella over log triage and query optimisation, which are DIFFERENT "
       "subject matters. If you find yourself naming a broader category to make two tasks match, the "
       "axis does not match.\n\n"
       "INPUTS DESCRIBED\n"
       "- task        — the new task's instruction text\n"
       "- reasoning   — the reranker's domain reasoning for the new task (why the matched shape "
       "does not cover this task's domain)\n"
       "- candidates  — a JSON array of the NEAREST EXISTING domain families, each with id, label "
       "and description (its purpose, the shape it was born under, and the signature that minted "
       "it)\n\n"
       "Write merge_reasoning FIRST, before choosing the verdict: name the candidate you compared "
       "against and say explicitly what is SHARED and what DIFFERS on BOTH axes (subject matter and "
       "output kind). Then give the verdict:\n"
       "  same    — with the family id, when exactly one candidate is the SAME family (both axes "
       "match)\n"
       "  new     — when no candidate is the same family (at least one axis differs from every "
       "candidate)\n"
       "  unknown — when you cannot tell from what is shown\n\n"
       "Respond with a JSON object with EXACTLY these five keys:\n"
       "  {\"merge_reasoning\": \"<name the candidate compared; state what is shared and what "
       "differs in subject matter and output kind>\",\n"
       "   \"subject_matter_same\": \"yes\"|\"no\"  (for the candidate you compared, at its own level),\n"
       "   \"output_kind_same\": \"yes\"|\"no\"  (for the candidate you compared),\n"
       "   \"verdict\": \"same\"|\"new\"|\"unknown\",\n"
       "   \"family\": \"<the compared candidate's id when verdict is same, otherwise null>\"}\n"
       "No surrounding prose, no code fences."))

(defn- domain-family-merge-workflow-name
  "Same deterministic-sheet-identity reasoning as `reranker-workflow-name`:
   keep the DEFAULT model's identity pinned to a stable name; a
   caller-supplied override gets its own distinct sheet identity."
  [model]
  (if (= model default-model)
    "ontology-domain-family-merge"
    (str "ontology-domain-family-merge--" model)))

(defn domain-family-merge-workflow
  "CV-C item 1: the merge judge's single-:llm-node ORC workflow. Pure data
   (no I/O), mirroring `reranker-workflow`'s shape.

   Inputs (blackboard): :task, :reasoning, :candidates
   Output (one :writes slot): :merge-json — a JSON string (function-calling
     may hand back a MAP directly; `parse-merge-answer` accepts both — see
     its docstring for the RS-P3 run-1 parser defect this guards)."
  [model]
  (orc/workflow (domain-family-merge-workflow-name model)
    (orc/blackboard
      {:task       :string
       :reasoning  :string
       :candidates [:vector family-schema]
       :merge-json :string})

    (orc/llm "merge"
      :model model
      :instruction family-merge-instruction
      :reads [:task :reasoning :candidates]
      :writes [:merge-json]
      :options {:max-retries 3
                :retry-delay-ms [500 1500 3000]
                :use-function-calling? true})))

(defn- parse-merge-answer
  "CV-C: the merge workflow's structured answer arrives either as a MAP
   (function calling — the shipped path) or as a JSON STRING. Accept both:
   RS-P3 run 1 expected a string only and silently dropped 16 valid map
   verdicts, every one counted as :unknown
   (`development/bench/ood-stress-results/rs-p3-family-merge-probe/FINDINGS.md`,
   'Run 1 (parser defect)'). A malformed/unparseable answer returns nil —
   the caller reads that as verdict :unknown, never coerced."
  [raw]
  (cond
    (map? raw) (into {} (map (fn [[k v]] [(keyword (name k)) v])) raw)
    (string? raw)
    (let [s (.indexOf raw "{") e (.lastIndexOf raw "}")]
      (when (and (>= s 0) (> e s))
        (try (json/read-str (subs raw s (inc e)) :key-fn keyword)
             (catch Throwable _ nil))))
    :else nil))

(defn combine-merge-verdict
  "The judge's verdict, held to its own two axis answers. A \"same\" stands only
   when the judge ALSO answered yes on both subject matter and output kind; a
   \"same\" beside a \"no\" on either axis is the judge's own evidence of a
   different family and becomes :new (live finding: the judge wrote \"the subject
   matter differs\" and still answered same, by lifting both tasks to an umbrella
   category). A \"same\" with a missing or malformed axis answer is :unknown. The
   axis answers are the judge's structured output, not a reading of its prose.
   :new and :unknown pass through; anything out of set is :unknown."
  [verdict-kind subject-matter-same output-kind-same]
  (let [axis (fn [v] ({"yes" :yes "no" :no} (some-> v str str/trim str/lower-case)))
        sm (axis subject-matter-same)
        ok (axis output-kind-same)]
    (case verdict-kind
      :same (cond (and (= :yes sm) (= :yes ok)) :same
                  (or (= :no sm) (= :no ok)) :new
                  :else :unknown)
      :new :new
      :unknown)))

(defn merge-family!
  "CV-C item 2: invoke the domain-family-merge workflow with (signature,
   reasoning, candidates). Model resolution like `rerank!` (`resolve-model`
   — per-call opt > per-deployment ctx slot > the ratified default).

   Returns {:kind :same|:new|:unknown :family <id-or-nil> :reasoning
   <string-or-nil> :usage <map-or-nil>} — :kind is ALWAYS one of the three
   verdict values (an out-of-set or missing verdict, or a non-:success
   execution, reads as :unknown, never coerced/guessed).

   Args:
     ctx   — context with :event-store / :llm-provider
     opts  — {:signature :reasoning :candidates :model :timeout-ms}
       :signature   — the new task's signature text
       :reasoning   — the reranker's domain reasoning for the new task
       :candidates  — vector of {:id :label :description} (CV-C item 3's
                      `nearest-families`); :id is coerced to a string for
                      the workflow's :candidates blackboard input
       :model       — OPTIONAL model override (RR-2-style; see `resolve-model`)
       :timeout-ms  — OPTIONAL explicit execution budget (see
                      `resolve-timeout-ms`; shares the reranker's
                      `:rerank-timeout-ms` ctx knob and default budget —
                      this is the same shape of single-:llm-node call)."
  [ctx {:keys [signature reasoning candidates model timeout-ms]}]
  (let [budget-ms (resolve-timeout-ms ctx timeout-ms)
        resolved-model (resolve-model ctx model)
        sheet-id (orc/build-workflow! ctx (domain-family-merge-workflow resolved-model))
        inputs {:task (or signature "")
                :reasoning (or reasoning "")
                :candidates (mapv (fn [{:keys [id label description]}]
                                    {:id (str id)
                                     :label (or label "")
                                     :description (or description "")})
                                  candidates)}
        result (orc/execute ctx sheet-id inputs :timeout-ms budget-ms)]
    (if (not= :success (:status result))
      (do (u/log :ai.obney.orc.ontology.core.reranker/merge-family-workflow-failed
                  :status (:status result)
                  :error (:error result)
                  :duration-ms (:duration-ms result))
          {:kind :unknown :family nil :reasoning nil :usage nil})
      (let [parsed (parse-merge-answer (get-in result [:outputs :merge-json]))
            verdict-kind (some-> (:verdict parsed) name keyword)
            kind (combine-merge-verdict verdict-kind
                                        (:subject_matter_same parsed)
                                        (:output_kind_same parsed))]
        (when (nil? verdict-kind)
          (u/log :ai.obney.orc.ontology.core.reranker/merge-answer-unparseable
                  :raw-preview (let [raw (get-in result [:outputs :merge-json])]
                                (cond (string? raw) (subs raw 0 (min 200 (count raw)))
                                      (some? raw) (pr-str raw)))))
        (cond-> {:kind kind :family nil :reasoning nil :usage (:usage result)}
          (:family parsed) (assoc :family (:family parsed))
          (:merge_reasoning parsed) (assoc :reasoning (:merge_reasoning parsed)))))))

;; --- Task classifier ---

(defn- merge-family!* [ctx opts]
  (#'merge-family!
   ctx opts))

(def rerank-fallback-sources
  "Every :rerank-source value that means THE RERANKER DID NOT RANK — the
   result set is raw ColBERT ordering with nil :fitness-score/:reasoning.

     :colbert-fallback — genuine rerank failure (threw / nil / empty)
     :timeout-fallback — RR-1 (ADR 0020): the call timed out and so did its
                         one retry. Transient infra, not epistemic
                         uncertainty about the corpus — but from the
                         classifier's seat the epistemic state is identical
                         (we do NOT know the fit), so the ACTION is
                         identical: EL-3 / ADR 0015 detect-and-defer.

   Membership, not equality: a new fallback flavour must be added HERE, or
   it silently skips the defer and re-creates the 8/8 fallback-mint
   conflation (a nil fitness reads as 0.0 → below threshold → fresh mint)."
  #{:colbert-fallback :timeout-fallback})

(defn rerank-fallback?*
  "True when a candidate's :rerank-source says the reranker did not rank."
  [candidate]
  (contains? rerank-fallback-sources (:rerank-source candidate)))

(def classify-retrieval-k
  "The classifier's retrieval :k — AND the bound on the audited pre-gate
   ranking snapshot (:ranked-candidates on :ontology/task-classified /
   :ontology/task-classification-deferred; the schemas' {:max 5} mirrors
   this number). One value so the retrieval and the audit bound cannot
   drift apart."
  5)

(defn reduce-ranked-candidate
  "CC-23, the spec's DecidedRankingIsRecorded: reduce ONE retrieval
   candidate to its audit identity — candidate identity (target-id), the
   granularity/axis, the reranker's :fitness-score, the raw ColBERT
   retrieval :score when present, and the :rerank-source.

   NEVER :content, NEVER :summary, no description text of any kind — the
   CC-21 lesson and the spec's explicit payload bound (measured: candidate
   content was 59.4% of the tree-class evidence payload). Optional fields
   are OMITTED, not nil (the CC-31 idiom): a fallback candidate's nil
   :fitness-score is an absent key, so `(contains? entry :fitness-score)`
   distinguishes 'never scored' from 'scored 0.0'."
  [c]
  (let [granularity (-> c :document-metadata :granularity)]
    (cond-> {:target-id (or (-> c :document-metadata :target-id)
                            (:document-id c))
             :granularity (if (keyword? granularity)
                            granularity
                            (keyword (str granularity)))}
      (some? (:rerank-source c)) (assoc :rerank-source (:rerank-source c))
      (some? (:fitness-score c)) (assoc :fitness-score (:fitness-score c))
      (some? (:score c))         (assoc :score (:score c)))))

(defn reduce-ranked-candidates
  "The bounded pre-gate ranking snapshot for one classification decision:
   the RAW (ungated) candidates, in decision order, capped at
   `classify-retrieval-k`, each entry reduced by `reduce-ranked-candidate`."
  [raw-candidates]
  (into [] (comp (take classify-retrieval-k)
                 (map reduce-ranked-candidate))
        raw-candidates))

(defn- get-tree-class-children
  "Return the children of `parent-target-id` as {:target-id :description}
   maps. Reads the concepts read-model for narrower URIs and pulls each
   child's description from the descriptions read-model, trying :tree-class
   scope FIRST (RS-3: a runtime-emergent domain child describes itself
   through the CV-1 signature claim route, which lands under :tree-class —
   C-Loop-1) and falling back to :tree-fingerprint (the seeded instances,
   C-2d-1's projector). RS-P2's Q-c finding: reading :tree-fingerprint only
   made every domain child invisible to this walk even though its parent
   edge existed. Children without a description under EITHER scope are
   dropped (the rerank step needs content).

   RS-5 (gap A): each returned map also carries `:scope` — the SCOPE the
   description was actually found under (`:tree-class` or
   `:tree-fingerprint`), never hardcoded — so `pick-best-child` can stamp
   the synthetic candidate's `:document-metadata :granularity` with the
   scope it was read under instead of a hardcoded axis.

   CV-E (`DomainFamilyIsALeafOnTheDomainAxis`, revised C5'): a child that IS
   a domain family (`non-family-child?`) is dropped BEFORE its description
   is even read — walk-down never descends into a family, so a shape whose
   only child is a family returns the shape itself (empty children here is
   the genuine-leaf case one level up in `walk-down-from`)."
  [ctx parent-target-id]
  (let [parent-uri (str tree-class-uri-prefix parent-target-id)
        child-uris (or (task-classifier-get-narrower-concepts ctx parent-uri) #{})]
    (vec
      (keep (fn [child-uri]
              (let [child-id (uri->target-id child-uri)]
                (when (non-family-child? ctx child-id)
                  (let [tree-class-desc (get-description ctx :tree-class child-id)
                        scope (if tree-class-desc :tree-class :tree-fingerprint)
                        desc (or tree-class-desc
                                 (get-description ctx :tree-fingerprint child-id))]
                    (when desc
                      {:target-id child-id
                       :scope scope
                       :description desc})))))
            child-uris))))

(defn- pick-best-child
  "Re-rank a candidate list of children against the caller's intent.
   Returns {:target-id :fitness-score :reasoning} for the best child
   that meets the auto-classify threshold, or nil if no child does.

   RR-2: `model` is an OPTIONAL reranker :model override, threaded
   straight through to `rerank!`'s own :model opt — nil is a no-op.

   RS-5 (gap A): each candidate's `:document-metadata :granularity` is the
   SCOPE `get-tree-class-children` actually read the child's description
   under (`:scope`, RS-5) — `:tree-class` for a runtime-emergent domain
   child (CV-1's signature route) or `:tree-fingerprint` for a seeded
   instance — never a hardcoded axis. Defaults to `:tree-fingerprint` when
   a caller-supplied child map carries no `:scope` (defensive; every child
   `get-tree-class-children` returns carries one)."
  [ctx intent children threshold model]
  (when (seq children)
    (let [candidates (mapv (fn [c]
                             {:content (or (-> c :description :summary) "")
                              :score 0.0
                              :document-id (str (:target-id c))
                              :document-metadata {:granularity (or (:scope c) :tree-fingerprint)
                                                  :target-id (:target-id c)
                                                  :confidence 1.0
                                                  :last-update "—"}})
                           children)
          reranked (task-classifier-rerank! ctx {:query intent
                                 :intent intent
                                 :candidates candidates
                                 :model model})
          top-1 (first reranked)]
      (when (and top-1 (>= (or (:fitness-score top-1) 0.0) threshold))
        (let [matching (some (fn [c]
                               (when (= (str (:target-id c)) (:document-id top-1))
                                 c))
                             children)]
          {:target-id (:target-id matching)
           :fitness-score (:fitness-score top-1)
           :reasoning (or (:reasoning top-1) "")})))))

(defn walk-down-from
  "Descend the tree-class hierarchy from `current` until a leaf is
   reached, the depth cap fires, or no child meets the auto-classify
   threshold.

   Returns {:assigned-tree-id :confidence :reasoning :parent-tree-id
            :was-fresh-mint?}.

   Specificity-threshold is the ENTRY gate (handled by `classify-task`
   before invoking this fn); it does not affect mid-walk behavior. Once
   a walk starts, it continues as long as some child meets the
   auto-classify threshold or the depth cap is reached.

   Algorithm:
   - Depth cap (>= 5): stop with current as the answer; log.
   - Otherwise: look at children.
     - No children OR no child passes auto-classify-threshold:
       - If we walked here (depth > 0): mint fresh leaf under current
         (current is the deepest matched ancestor per C-2d Decision 3).
       - If we never walked (depth = 0): return current as-is — we only
         LOOKED at children, never committed to descending.
     - A child passes: recurse on it at depth+1, carrying current's
       target-id forward as the new parent for the child.

   RR-2: `model` is an OPTIONAL reranker :model override, threaded
   unchanged through every `pick-best-child` call and the recursion —
   nil is a no-op (each rerank! resolves its own default)."
  [ctx intent current depth auto-threshold parent-id model]
  (cond
    (>= depth max-walk-depth)
    (do (u/log :ai.obney.orc.ontology.core.task-classifier/walk-down-depth-cap-hit
               :depth depth
               :target-id (:target-id current))
        {:assigned-tree-id (:target-id current)
         :confidence (:fitness-score current)
         :reasoning (or (:reasoning current) "")
         :parent-tree-id parent-id
         :was-fresh-mint? false})

    :else
    (let [children (get-tree-class-children ctx (:target-id current))]
      (cond
        (empty? children)
        {:assigned-tree-id (:target-id current)
         :confidence (:fitness-score current)
         :reasoning (or (:reasoning current) "")
         :parent-tree-id parent-id
         :was-fresh-mint? false}

        :else
        (let [best (pick-best-child ctx intent children auto-threshold model)]
          (cond
            (nil? best)
            (if (pos? depth)
              {:assigned-tree-id (random-uuid)
               :confidence (:fitness-score current)
               :reasoning "Walk-down considered children of matched ancestor; none met threshold. Minting fresh leaf under the deepest matched ancestor."
               :parent-tree-id (:target-id current)
               :was-fresh-mint? true}
              {:assigned-tree-id (:target-id current)
               :confidence (:fitness-score current)
               :reasoning (or (:reasoning current) "")
               :parent-tree-id parent-id
               :was-fresh-mint? false})

            :else
            (recur ctx intent best (inc depth) auto-threshold
                   (:target-id current) model)))))))

(defn tree-class-candidate? [c]
  (= :tree-class (-> c :document-metadata :granularity)))

(defn candidate-class-id
  "Stable tree-class id for a candidate — reuses coerce-to-uuid so a seed
   tree-class with a string target-id (e.g. 'seed:tree:SequentialPipeline')
   is a valid bundle target rather than being silently dropped (a real
   prototype finding)."
  [c]
  (some-> c :document-metadata :target-id coerce-to-uuid))

(def ^:private rrf-k
  "RRF constant, mirrored from graph/compute-rrf-scores (the fusion primitive
   we mirror here rather than fork, to keep classify-task free of a graph dep)."
  60)

(defn- rrf-fuse
  "Mirror of graph/compute-rrf-scores: Reciprocal Rank Fusion over several
   ranked signal-batches. Each batch is a seq of [id score]; higher score =
   better rank within its batch. Returns [[id fused-score] ...] sorted desc.
   Used to FUSE the multi-signal candidate evidence (reranker fitness +
   ColBERT semantic score) into one ranking — its correct role is fusion, NOT
   an absolute-similarity gate (the bundle DECISION gates on absolute fitness,
   see bundle-decision)."
  [batches]
  (let [ranked (for [batch batches]
                 (->> batch
                      (sort-by second >)
                      (map-indexed (fn [rank [id _]] [id rank]))))
        scores (reduce
                 (fn [acc rl]
                   (reduce (fn [a [id rank]]
                             (update a id (fnil + 0.0) (/ 1.0 (+ rrf-k (inc rank)))))
                           acc rl))
                 {} ranked)]
    (->> scores (sort-by val >) vec)))

(defn bundle-decision
  "EL-1b Part 1. Given the retrieved candidates, decide whether a confident
   no-match (:novel) should BUNDLE onto an existing tree-class instead of
   scattering a fresh random-uuid.

   Multi-signal: RRF-fuses the :tree-class candidates' reranker fitness
   (intent fit, EL-2-grounded) with their ColBERT raw score (semantic /
   description match) to pick the single best :tree-class candidate, then
   gates that winner on ABSOLUTE fitness in the bundle band
   [bundle-threshold, threshold). (RRF chooses; the absolute fitness gates —
   pure rank-RRF cannot separate a variant from junk, a prototype finding.)

   Returns the existing class's id to bundle onto, or nil to mint fresh.
   Only :tree-class candidates are considered — convergence rides the
   instruction-aware axis, never :tree-fingerprint."
  [candidates bundle-threshold threshold]
  (let [tcs (filterv tree-class-candidate? candidates)
        fit-by-id (into {} (keep (fn [c] (when-let [id (candidate-class-id c)]
                                           [id (or (:fitness-score c) 0.0)]))
                                 tcs))
        sem-by-id (into {} (keep (fn [c] (when-let [id (candidate-class-id c)]
                                           [id (or (:score c) 0.0)]))
                                 tcs))
        intent-batch (seq fit-by-id)
        sem-batch    (seq sem-by-id)
        batches (filterv seq [intent-batch sem-batch])
        fused (when (seq batches) (rrf-fuse batches))
        [top-id _] (first fused)
        fit (get fit-by-id top-id 0.0)]
    (when (and top-id
               (>= fit bundle-threshold)
               (< fit threshold))
      top-id)))

(defn shape-candidates-only
  "The candidates a SHAPE-axis decision (the bundle) may land on: every
   :tree-class candidate that is not a domain family. A family is never a shape
   class (DomainFamilyIsALeafOnTheDomainAxis) and the bundle must not place a
   task in one unjudged (live finding: a marathon task bundled into a
   recipe-scaling family). CV-E: match and walk-down can no longer reach a
   family at all (search-descriptions excludes them from the ranking, and
   walk-down excludes them from its children), so this is now belt-and-braces
   for the bundle's own candidate set specifically. A failed lookup drops the
   candidate too: a bundle never lands on an unverified class. Non-tree-class
   candidates pass through (bundle-decision ignores them)."
  [ctx candidates]
  (let [family-parent-fn (or (:domain-family-parent-fn ctx) default-domain-family-parent-fn)]
    (filterv (fn [c]
               (or (not (tree-class-candidate? c))
                   (let [id (candidate-class-id c)]
                     (and id
                          (try (nil? (family-parent-fn ctx id))
                               (catch Throwable t
                                 (u/log :ai.obney.orc.ontology.core.task-classifier/bundle-family-lookup-failed :target-id id :error (.getMessage t))
                                 false))))))
             candidates)))

(defn family-candidate?
  "The `:exclude?` predicate classify-task hands `search-descriptions`: true
   when `candidate` is a domain family, so search-descriptions drops it
   BEFORE the granularity-filtered set is handed to `tree-class-representatives`
   and the reranker's take — never after, when it would already have crowded
   out a generic seed (families carry specific birth descriptions and outrank
   them). Same discriminator as `shape-candidates-only` / `family-concept?` —
   agent-authored provenance, a label that is not its own bare id — via the
   `:domain-family-parent-fn` seam (default: the real store read). Only
   :tree-class candidates are ever families; every other axis passes straight
   through. Fail closed: a failed lookup (or an id that fails to resolve)
   EXCLUDES the candidate — never lets an unverified class compete.
   `search-descriptions` caches this predicate per distinct candidate id, so
   one classification costs at most one read per distinct candidate, not one
   per axis row."
  [ctx candidate]
  (boolean
    (and (tree-class-candidate? candidate)
         (let [id (candidate-class-id candidate)]
           (or (nil? id)
               (let [family-parent-fn (or (:domain-family-parent-fn ctx) default-domain-family-parent-fn)]
                 (try (some? (family-parent-fn ctx id))
                      (catch Throwable t
                        (u/log :ai.obney.orc.ontology.core.task-classifier/classify-family-exclusion-lookup-failed
                               :target-id id :error (.getMessage t))
                        true))))))))

(defn default-newborn?-fn
  "CV-A item 4 (`NewbornFamilyIsMatchableNotSurfaced`) — the real default
   for the `:newborn?-fn` injected capability: true when `target-id`'s
   tree-class concept carries agent-authored provenance (a runtime-minted
   domain family), false for a curated/seeded class and for any read that
   cannot be resolved (this seam only governs SURFACING, never an
   identity/mint decision, so it is safe to read a failure as \"not a
   newborn\" rather than deferring the whole classification over it)."
  [ctx target-id]
  (try
    (boolean
     (when (:event-store ctx)
       (let [uri (str tree-class-uri-prefix target-id)
             concept (task-classifier-get-concept-by-uri ctx uri)]
         (= :agent-authored (:kind (:provenance concept))))))
    (catch Throwable _ false)))

(defn- format-keys [keys]
  (if (seq keys)
    (str/join " " (map pr-str keys))
    "(none)"))

(defn- format-tools [tools]
  (if (seq tools)
    (str/join " " tools)
    "(none)"))

(defn build-task-signature
  "Pure: build the static-config signature string for classification.

   Includes the node's :instruction + :reads keyword names + :writes
   keyword names + :mcp-tools + :browser-tools. Per the C-2c sub-grill
   Decision 6 (Framing B), this is the static slice of the signature;
   the caller concatenates parent-context separately via classify-task's
   :parent-summary opt.

   Designed for repl-researcher nodes. Works for :llm nodes too (no
   :mcp-tools / :browser-tools → '(none)')."
  [node]
  (str "INSTRUCTION:\n"
       (or (:instruction node) "(no instruction)")
       "\n\nREADS: " (format-keys (:reads node))
       "\nWRITES: " (format-keys (:writes node))
       "\nMCP-TOOLS: " (format-tools (:mcp-tools node))
       "\nBROWSER-TOOLS: " (format-tools (:browser-tools node))))

(defn confidence-gate-report
  "CC-20 (ADR 0027 decision 2): what the classify confidence gate DID on one
   classification — pure over the classify result + the threshold it judged
   against.

   The CC-20 derivation deliberately did NOT migrate this gate's form:
   its property is 'is the BEST match good enough to assign', judged on the
   top-1, and every candidate-set-relative form is degenerate there —
   rank(top-1) is identically 1 and min-max/TMM(top-1) is identically 1.0
   (the CC-20 issue's documented caution verbatim), while z(top-1) measures
   separation-from-the-pack, a different property. The thresholded quantity is
   the reranker's rubric-anchored fitness, not a corpus-scale similarity
   score. What CC-19 DID measure (N=156 real classifications) is that the
   gate is near-inert in [0.4, 0.9] (rejects 2-4 of 156; median 0.98) with
   its real signal in the low tail (the two fresh-mints sat at 0.35 and 0.0)
   — and nothing could report that. This report is the fix ADR 0027 names:
   a gate must be able to say whether it is doing anything."
  [{:keys [outcome confidence top-candidates was-fresh-mint? rerank-fallback?]} threshold]
  (let [top-score (double (or confidence 0.0))]
    (cond-> {:outcome outcome
             :threshold (double threshold)
             :top-score top-score
             :margin-to-threshold (- top-score (double threshold))
             :candidate-count (count top-candidates)
             :rerank-fallback? (boolean rerank-fallback?)}
      (some? was-fresh-mint?) (assoc :was-fresh-mint? (boolean was-fresh-mint?)))))

(defn log-confidence-gate!
  "Emit the ADR-0027 confidence-gate report for one classification."
  [result threshold]
  (let [report (confidence-gate-report result threshold)]
    (u/log :ai.obney.orc.ontology.core.task-classifier/classify-confidence-gate
           :outcome (:outcome report)
           :threshold (:threshold report)
           :top-score (:top-score report)
           :margin-to-threshold (:margin-to-threshold report)
           :candidate-count (:candidate-count report)
           :rerank-fallback? (:rerank-fallback? report)
           :report report)
    result))

(defn- canonicalize-domain-label
  "RS-2: the ONLY normalisation applied to the reranker's JUDGED
   :domain-label — trim, lower-case, then collapse internal whitespace runs
   to a single hyphen. A FORMATTING rule, not a matching rule: no synonym
   lists, no fuzzy/regex matching over model prose (D7/D7b). A nil or blank
   label canonicalises to nil, which callers below treat as an absent label
   (`DomainCoverageIsJudgedNotInferred` — no child is ever minted from an
   empty label)."
  [label]
  (when (and (string? label) (not (str/blank? label)))
    (let [canonical (-> label
                        str/trim
                        str/lower-case
                        (str/replace #"\s+" "-"))]
      (when-not (str/blank? canonical) canonical))))

(defn- stable-domain-child-identity
  "RS-2's `stable_domain_child_identity` (@invariant
   DomainChildIdentityIsStable): the domain-child identity derived
   DETERMINISTICALLY from the parent tree-class id + the CANONICAL (chosen)
   label — same task, same label, same identity every time. Same
   derivation as `commands.clj`'s private `stable-uuid-from`
   (`UUID/nameUUIDFromBytes` over a namespaced string); copied here as a
   one-liner rather than importing `commands` into this pure ns."
  [parent-id canonical-label]
  (java.util.UUID/nameUUIDFromBytes
    (.getBytes (str "domain-child:" parent-id ":" canonical-label) "UTF-8")))

(defn- default-domain-children-fn
  "The real default for the `:domain-children-fn` injected capability: the
   existing domain children of a :tree-class parent, as
   {:target-id :domain-label} maps. Reads the parent's narrower concepts
   (the same graph edge walk-down's own child lookup uses) and each
   child's CONCEPT :label — RS-3's `:ontology/mint-domain-child` stamps the
   judged domain label directly onto the child's tree-class concept at
   birth (RS-P2: the command path, never a description body write — CC-6).
   A child whose :label is blank, or equals its own id (the generic
   placeholder `ensure-tree-class-concept!` lazy-creates for an ordinary,
   non-domain tree-class concept it happens to have as a narrower — never a
   judged domain label), is excluded — absent -> no children.

   Does NOT fail open. A store failure here is not \"no children yet\" —
   reading it that way would mint a fresh sibling for a domain that already
   has a child (the scatter this arc exists to prevent). The failure
   propagates and `assign-domain-child` turns it into a domain-axis deferral
   (`:children-lookup-failed`) that leaves the pre-existing assignment
   untouched and mints nothing (DomainCoverageIsJudgedNotInferred: what
   cannot be resolved defers and records why)."
  [ctx parent-target-id]
  (let [parent-uri (str tree-class-uri-prefix parent-target-id)
        child-uris (or (task-classifier-get-narrower-concepts ctx parent-uri) #{})]
    (vec
      (keep (fn [child-uri]
              (let [child-id (uri->target-id child-uri)
                    concept (task-classifier-get-concept-by-uri ctx child-uri)
                    label (:label concept)]
                (when (and (string? label) (not (str/blank? label))
                           (not= label (str child-id)))
                  {:target-id child-id :domain-label label})))
            child-uris))))

(defn- domain-deferral
  "RS-2's `:domain-deferral` marker: the domain axis could not be resolved
   (coverage :unknown/missing/malformed, a blank/nil label where a child
   would have been minted — 'no child from an empty label' — a failed
   children/families lookup, an unresolved merge verdict, or CV-D's
   `:label-taken` identity collision). No child is created; the
   pre-existing match/walk-down assignment is left exactly as it was, and
   the reason is recorded."
  ([] (domain-deferral :unknown-coverage))
  ([reason] {:axis :domain :reason reason}))

(defn default-domain-families-fn
  "CV-A item 1 — the real default for the `:domain-families-fn` injected
   capability: every domain family in the tenant, tenant-wide (see
   `ontology/existing-domain-families`), most-recently-minted first."
  [ctx]
  (existing-domain-families* ctx))

(defn- family-concept?
  "Same discriminator `default-domain-family-parent-fn` / `existing-domain-
   families` use: agent-authored provenance, a non-blank :label that is not
   the concept's own bare id (the generic placeholder an ordinary,
   non-domain tree-class concept carries as its label)."
  [concept bare-id]
  (and concept
       (= :agent-authored (:kind (:provenance concept)))
       (string? (:label concept))
       (not (str/blank? (:label concept)))
       (not= (:label concept) (str bare-id))))

(defn nearest-families
  "CV-C item 3 (`DomainFamilyMergeIsJudged`) — the nearest EXISTING domain
   families BY RANK for a would-be mint's merge judge: `ontology/hybrid-
   search` (graph + embedding, rank-fused) over the tree-class ontology,
   query text = signature + reasoning, seeded by the RANKING's own
   :tree-class candidate URIs, filtered to agent-authored family concepts
   (an ordinary shape class anywhere in the neighbourhood is dropped —
   never shown to the judge), bounded to merge-candidate-count. Rank order
   PRESERVED; no score is ever read for a decision (grill C3) — and
   `:min-similarity` is pinned to 0.0 so hybrid-search's own similarity
   floor can never silently act as the cutoff the spec forbids ('no
   similarity cutoff').

   `:label`/`:description` are re-read from the LIVE event-sourced concept
   (`get-concept-by-uri`), never taken from hybrid-search's own enrichment:
   `fuse-and-enrich` resolves :label/:description from the STATIC seed
   ontology only (`static-ontology/get-concept-by-uri`), which never
   contains a runtime-minted :tree-class family — trusting it would show
   the judge every family with a BLANK body (found by reading the
   retrieval code, not assumed; root-caused rather than routed around).

   CV-D (`DomainFamilyMergeIsJudged`, revised C3'): `:proposed` — the
   family a landing would choose without a judge (a tenant-wide canonical-
   label match, a per-parent existing child, or a match/walk-down that
   REACHES a family) — is ALWAYS included among the returned candidates
   when non-nil. When the hybrid search already surfaced it, nothing
   changes. When it did not, its concept is fetched directly
   (`get-concept-by-uri`) and prepended, and the bound (merge-candidate-
   count) still applies to the REST of the list — the proposed family is
   never the one dropped to make room.

   Returns [{:id :label :description} ...]. Fail closed: any exception
   here PROPAGATES — the caller (`default-domain-merge-fn`, by way of
   `mint-domain-family-via-merge`'s try/catch) reads it as :unknown, never
   as \"no families\"."
  [ctx {:keys [signature reasoning ranking proposed]}]
  (let [merge-candidate-count (or (:merge-candidate-count ctx) default-merge-candidate-count)
        seed-uris (into []
                        (comp (filter tree-class-candidate?)
                              (keep candidate-class-id)
                              (map #(str tree-class-uri-prefix %))
                              (distinct))
                        ranking)
        query-text (str/trim (str (or signature "") " " (or reasoning "")))
        {:keys [results]} (hybrid-search* ctx {:seed-uris seed-uris
                                               :query-text query-text
                                               :scope :tree-class
                                               :min-similarity 0.0
                                               :limit (* 4 merge-candidate-count)})
        found (into []
                    (comp (keep (fn [{:keys [uri]}]
                                  (let [id (uri->target-id uri)
                                        concept (task-classifier-get-concept-by-uri ctx uri)]
                                    (when (family-concept? concept id)
                                      {:id id
                                       :label (:label concept)
                                       :description (:description concept)}))))
                          (take merge-candidate-count))
                    results)]
    (if (or (nil? proposed) (some #(= proposed (:id %)) found))
      found
      (let [proposed-uri (str tree-class-uri-prefix proposed)
            proposed-concept (task-classifier-get-concept-by-uri ctx proposed-uri)]
        (if (family-concept? proposed-concept proposed)
          (into [{:id proposed
                  :label (:label proposed-concept)
                  :description (:description proposed-concept)}]
                (take (dec merge-candidate-count) found))
          found)))))

(defn default-domain-merge-fn
  "CV-C (`DomainFamilyMergeIsJudged`) — the seam's real default: retrieves
   `nearest-families` and, when the neighbourhood is non-empty, asks ONE
   discrete question (`reranker/merge-family!`) with their full
   descriptions in view. An EMPTY neighbourhood is `:new` WITHOUT a call
   (grill C3 — nothing to compare against yet). `:same` naming a family
   NOT among the candidates actually shown to the judge is read as
   `:unknown` (a hallucinated / out-of-context id never lands); the
   returned `:family` is always OUR OWN copy of the shown candidate's id,
   never the judge's echoed string, so a JSON round-trip can never change
   its type/identity. An out-of-set or malformed verdict kind is
   `:unknown`, never coerced.

   `query` carries `:ranking` (`mint-domain-family-via-merge`'s `candidates`
   — the pre-gate retrieval ranking) alongside `:signature`/`:reasoning`;
   a caller-supplied `:domain-merge-fn` stub that ignores extra keys is
   unaffected (existing CV-A tests stub this seam with a 2-arg `[ctx
   query]` fn exactly as before). CV-D adds `:proposed` — the family a
   landing would choose without a judge — threaded straight to
   `nearest-families` so it is always among the shown candidates."
  [ctx {:keys [signature reasoning ranking proposed]}]
  (let [candidates (nearest-families ctx {:signature signature
                                          :reasoning reasoning
                                          :ranking ranking
                                          :proposed proposed})]
    (if (empty? candidates)
      {:kind :new}
      (let [{judge-kind :kind judge-family :family judge-reasoning :reasoning}
            (merge-family!* ctx {:signature signature
                                 :reasoning reasoning
                                 :candidates candidates})
            shown (some #(when (= (str (:id %)) (str judge-family)) %) candidates)
            kind (cond
                   (not (contains? #{:same :new :unknown} judge-kind)) :unknown
                   (and (= judge-kind :same) (not shown)) :unknown
                   :else judge-kind)]
        (cond-> {:kind kind}
          (and (= kind :same) shown) (assoc :family (:id shown))
          judge-reasoning (assoc :reasoning judge-reasoning))))))

(defn- family-by-canonical-label
  "The tenant-wide family, if any, whose OWN label canonicalises to
   `canonical-label` — the cheap, judge-free landing path (C1)."
  [families canonical-label]
  (some (fn [f]
          (when (= canonical-label (canonicalize-domain-label (:domain-label f)))
            f))
        families))

(defn- family-parented-under?
  [families target-id]
  (some #(= target-id (:parent-id %)) families))

(defn covered-leaf-neighbour
  "CV-A item 2 (`CoveredSeedProtection`): the first :tree-class candidate
   (RANK ORDER — `candidates` is the pre-gate ranking) that is judged
   `:covered`, has no domain families of its own (neither via the
   tenant-wide `families` set nor the legacy per-parent
   `:existing-domain-children` enrichment), and whose fitness is at or
   above `threshold` — or nil.

   Pure: no store access — `candidates` already carry RS-1's per-candidate
   domain verdict (`apply-rerank`'s JOIN) and `:existing-domain-children`
   (EL-2's enrichment); `families` is the tenant-wide set the caller already
   fetched once for the landing step below it."
  [candidates families threshold]
  (some (fn [c]
          (when (and (tree-class-candidate? c)
                     (= :covered (:domain-coverage c))
                     (not (family-parented-under? families (candidate-class-id c)))
                     (empty? (:existing-domain-children c))
                     (>= (or (:fitness-score c) 0.0) threshold))
            {:target-id (candidate-class-id c)}))
        candidates))

(defn- identity-already-taken?
  "CV-D item 4 (`MintDomainFamily`'s identity-collision guard): is a `:new`
   verdict's derived identity (`stable-domain-child-identity` of the shape
   + canonical label) already an EXISTING family's identity? Nothing else
   ever creates a concept at that exact derived URI, so a hit here means
   minting again would silently duplicate/overwrite it — the judge said
   :new, but the identity says otherwise.

   Reuses the `:domain-family-parent-fn` seam (CV-A item 3's own \"is this
   id a family\" check, `default-domain-family-parent-fn`) rather than
   reading the concept directly: every caller already provides or defaults
   this seam, so a pure test's declared world (no store, or a stubbed
   family set) governs this check exactly as it governs every other family
   lookup in this namespace — no second, unseamed store touch. Not
   fail-closed itself — the caller wraps this in its own try/catch and
   treats a failed check as unresolved, never as \"free\"."
  [ctx child-identity]
  (let [family-parent-fn (or (:domain-family-parent-fn ctx) default-domain-family-parent-fn)]
    (boolean (family-parent-fn ctx child-identity))))

(defn- mint-domain-family-via-merge
  "CV-A item 7 / CV-C / CV-D (`DomainFamilyMergeIsJudged`, revised C3') —
   the ONE call site where every proposal and every would-be mint is
   judged via the `:domain-merge-fn` seam (CV-C's `default-domain-merge-fn`
   real implementation, or a caller-supplied stub): `:kind :new` mints
   (guarded by the identity-collision check below), with the raw verdict
   carried on `:merge-verdict` so the wedge knows this is a genuine family
   birth (item 5's rich description + embedding); `:kind :same` lands on
   the named family instead (which may differ from `proposed`); anything
   else — including a seam failure — defers `:merge-unresolved`, mints
   nothing.

   `proposed` (CV-D) is the family a landing would choose WITHOUT a judge —
   a tenant-wide canonical-label match, a per-parent existing child, or a
   match/walk-down that reached a family — or nil for a genuine would-be
   FIRST mint with no such candidate. Threaded to the seam so
   `nearest-families` always shows it to the judge."
  [ctx base parent-id canonical-label verdict signature ranking proposed]
  (let [merge-fn (or (:domain-merge-fn ctx) default-domain-merge-fn)
        query {:signature signature
               :reasoning (:domain-reasoning verdict)
               :label canonical-label
               :ranking ranking
               :proposed proposed}
        verdict* (try (or (merge-fn ctx query) {:kind :unknown})
                      (catch Throwable t
                        (u/log :ai.obney.orc.ontology.core.task-classifier/domain-merge-failed :error (.getMessage t))
                        {:kind :unknown}))]
    (case (:kind verdict*)
      :new
      (let [child-identity (stable-domain-child-identity parent-id canonical-label)
            taken (try {:taken? (identity-already-taken? ctx child-identity)}
                       (catch Throwable t
                         (u/log :ai.obney.orc.ontology.core.task-classifier/domain-identity-check-failed :error (.getMessage t))
                         {:failed? true}))]
        (cond
          (:failed? taken)
          (-> base
              (assoc :domain-deferral (domain-deferral :merge-unresolved))
              (assoc :merge-verdict verdict*))

          (:taken? taken)
          (-> base
              (assoc :domain-deferral (domain-deferral :label-taken))
              (assoc :merge-verdict verdict*))

          :else
          (-> base
              (assoc :assigned-tree-id child-identity)
              (assoc :assigned-via :mint-domain-child)
              (assoc :parent-tree-id parent-id)
              (assoc :domain-label canonical-label)
              (assoc :was-fresh-mint? true)
              (assoc :merge-verdict verdict*))))

      :same
      (let [family-id (:family verdict*)
            family-parent-fn (or (:domain-family-parent-fn ctx) default-domain-family-parent-fn)
            family-parent (when family-id (family-parent-fn ctx family-id))]
        (if-not (:parent-id family-parent)
          (-> base
              (assoc :domain-deferral (domain-deferral :merge-unresolved))
              (assoc :merge-verdict verdict*))
        (-> base
            (assoc :assigned-tree-id family-id)
            (assoc :assigned-via :land-on-domain-child)
            (assoc :parent-tree-id (:parent-id family-parent))
            (assoc :domain-label (or (:domain-label family-parent) canonical-label))
            (assoc :was-fresh-mint? false)
            (assoc :merge-verdict verdict*))))

      (-> base
          (assoc :domain-deferral (domain-deferral :merge-unresolved))
          (assoc :merge-verdict verdict*)))))

(defn- assign-domain-child
  "RS-2 + CV-A: apply the LEGACY per-parent domain-child logic
   (LandOnDomainChild / MintSiblingDomainChild when this shape already has
   domain children of its own; a plain :covered no-op / defer when it does
   not) to a :match result already known to be on the :tree-class axis and
   already known NOT to land on an existing tenant-wide family, with
   `parent-id` = the assigned class and `verdict` = the assigned
   candidate's {:domain-coverage :domain-label :domain-reasoning} (RS-1).

   The one case this delegates onward: NO legacy children AND a would-mint
   coverage — CV-A's `MintDomainFamily`, judged by the merge seam
   (`mint-domain-family-via-merge`) rather than minted unconditionally.

   Every branch carries :domain-verdict (the RAW verdict, unnormalised) and
   :domain-children-considered (the RAW labels the seam returned) so RS-3
   can record them."
  [ctx result parent-id verdict signature ranking]
  (let [children-fn (or (:domain-children-fn ctx) default-domain-children-fn)
        children (try (or (children-fn ctx parent-id) [])
                      (catch Throwable t
                        (u/log :ai.obney.orc.ontology.core.task-classifier/domain-children-lookup-failed
                               :parent-target-id parent-id
                               :error (.getMessage t))
                        :ai.obney.orc.ontology.core.task-classifier/lookup-failed))
        lookup-failed? (= :ai.obney.orc.ontology.core.task-classifier/lookup-failed children)
        children (if lookup-failed? [] children)
        considered (mapv :domain-label children)
        canonical-label (canonicalize-domain-label (:domain-label verdict))
        coverage (:domain-coverage verdict)
        base (-> result
                (assoc :domain-verdict verdict)
                (assoc :domain-children-considered considered))]
    (cond
      lookup-failed?
      (assoc base :domain-deferral (domain-deferral :children-lookup-failed))

      (seq children)
      (if-not canonical-label
        (assoc base :domain-deferral (domain-deferral))
        (let [existing (some (fn [c]
                               (when (= canonical-label
                                       (canonicalize-domain-label (:domain-label c)))
                                 c))
                             children)]
          (mint-domain-family-via-merge ctx base parent-id canonical-label verdict signature ranking
                                        (:target-id existing))))

      :else
      (case coverage
        :covered base

        (:partial :uncovered)
        (if-not canonical-label
          (assoc base :domain-deferral (domain-deferral))
          (mint-domain-family-via-merge ctx base parent-id canonical-label verdict signature ranking nil))

        (assoc base :domain-deferral (domain-deferral))))))

(defn- run-domain-axis-for-match
  "CV-A/CV-D/CV-E: the domain axis for a :match already known to be on the
   :tree-class axis and already known NOT to itself be a domain family
   (`maybe-assign-domain-child` checks that first — DomainFamilyIsALeafOnTheDomainAxis).

   Order (grill C3/C4, `DomainChildrenAreAlwaysConsidered`): covered-seed
   protection → the judged question (a tenant-wide canonical-label match, or
   a genuine would-be mint — the legacy per-parent mechanism's own
   child-label proposal included) — CV-D (`DomainFamilyMergeIsJudged`,
   revised C3'): there is no unjudged landing; a proposed family is always
   among the judge's candidates.

   `families` is fetched ONCE (a failed read defers
   `:families-lookup-failed`, never read as \"no families\") and reused by
   both protection and the tenant-wide proposal, so a pre-existing
   mis-parented family never captures a task a covered seed should have
   kept (`protection-runs-before-family-landing`).

   CV-E (`DomainFamilyIsALeafOnTheDomainAxis`, revised C5'): there is no
   longer a `reached-family-id` — `search-descriptions` excludes every
   family from the ranking and walk-down excludes them from its children,
   so a match can never REACH a family in the first place. A family is
   proposed to the judge only by its label (the tenant-wide match below) or
   a shape's own existing child label (`assign-domain-child`)."
  [ctx result candidates verdict threshold signature]
  (let [parent-id (:assigned-tree-id result)
        canonical-label (canonicalize-domain-label (:domain-label verdict))
        coverage (:domain-coverage verdict)
        base (assoc result :domain-verdict verdict)]
    (if-not canonical-label
      (assign-domain-child ctx base parent-id verdict signature candidates)
      (let [families-fn (or (:domain-families-fn ctx) default-domain-families-fn)
            fetch (try {:families (or (families-fn ctx) [])}
                       (catch Throwable t
                         (u/log :ai.obney.orc.ontology.core.task-classifier/domain-families-lookup-failed :error (.getMessage t))
                         {:failed? true}))
            families (:families fetch)
            neighbour (when (and (contains? #{:partial :uncovered} coverage) (not (:failed? fetch)))
                        (covered-leaf-neighbour candidates families threshold))
            label-family (when-not (:failed? fetch)
                          (family-by-canonical-label families canonical-label))
            proposed-id (:target-id label-family)]
        (cond
          (:failed? fetch)
          (assoc base :domain-deferral (domain-deferral :families-lookup-failed))

          neighbour
          (-> base
              (assoc :assigned-tree-id (:target-id neighbour))
              (assoc :assigned-via :match)
              (assoc :was-fresh-mint? false)
              (assoc :domain-selection {:preferred (:target-id neighbour)
                                        :over parent-id
                                        :reason :covered-leaf-neighbour}))

          (and proposed-id (contains? #{:partial :uncovered} coverage))
          (mint-domain-family-via-merge ctx base parent-id canonical-label verdict signature candidates proposed-id)

          :else
          (assign-domain-child ctx base parent-id verdict signature candidates))))))

(defn maybe-assign-domain-child
  "RS-2 + CV-A entry point: after the existing match/bundle/walk-down/
   deferral logic produces `result`, widen it with the domain-axis outcome.

   :bundle, walk-down's own :mint, :walk-down proper, :uncertain, and a
   :tree-fingerprint-axis :match pass through UNTOUCHED. Only a :match that
   resolves to a :tree-class candidate runs the domain axis.

   CV-E (`DomainFamilyIsALeafOnTheDomainAxis`, revised C5'): there is no
   longer a family-reach check here. Before this bundle, a :match or
   :walk-down could land ON a domain family (search-descriptions' ranking
   and walk-down's own children both used to include them), so this
   function first asked whether the assigned class was itself a family and,
   if so, rewrote the result into a match on the family's OWN parent shape.
   Now `search-descriptions` excludes every family from the ranking
   (`family-candidate?`, passed as classify-task's own `:exclude?`) and
   walk-down excludes them from its children (`get-tree-class-children`'s
   `non-family-child?`), so neither route can ever hand this function a
   family as the assigned class — the lookup and rewrite are UNREACHABLE
   from any live caller (classify-task is the only caller) and have been
   removed. A family is still reachable, but only as a PROPOSAL to the
   merge judge: its label (`assign-domain-child` / `run-domain-axis-for-
   match`'s tenant-wide match) or a shape's own existing child label
   (`assign-domain-child`)."
  [ctx result top-1 candidates threshold signature]
  (let [via (:assigned-via result)
        tree-class-match? (and (= :match via)
                               (= :tree-class (-> top-1 :document-metadata :granularity)))]
    (if tree-class-match?
      (run-domain-axis-for-match ctx result candidates
                                 (select-keys top-1 [:domain-coverage :domain-label :domain-reasoning])
                                 threshold signature)
      result)))

(def classify-behaviors-opts-schema
  [:map
   [:task-signature      :string]
   [:threshold           [:and number? [:>= 0.0] [:<= 1.0]]]
   [:structural-context  {:optional true} [:maybe :uuid]]
   [:top-n               {:optional true} :int]
   [:model               {:optional true} [:maybe :string]]])

;; --- Domain penalty ---

(def default-penalty-config
  "The shipped penalty configuration (CC-20, ADR 0027 — form derived before
   value, both derived ONCE from the banked CC-16 cells:
   doc/build-timeline/evidence/cc16/cc16-cells-{shipped,32}.edn — 559 real
   cells x 2 positive-signal variants x 2 query-token limits, real JVM
   ColBERT, real production bodies).

     :scorer          — :colbert (DEFAULT) | :embedding. Selects the backend.
     :embedding-model — embedding model id when :scorer is :embedding (nil =>
                        the embedding component default).
     :penalty-cap     — caps the penalty so it stays GRADED, never a hard zero
                        (demoted, not annihilated — reversible). Shared by
                        both gate forms.
     :gate            — the CC-20 gate: {:form :z-score ...} (see
                        z-gate-penalty + gate-config). Derived values:
                          :z-threshold 1.5 — inside the measured separability
                            band at the shipped query limit: ABOVE every
                            ground-truth own-domain cell (max witnessed z
                            +1.236, rename-symbol x Rename-move-symbol) and
                            BELOW the refactor force-fit (+1.729 at limit 464,
                            +3.011 at limit 32 — the form survives the ceiling
                            knob that moved the raw contrast 7x). Also the
                            exact bound below which a population of n<=4
                            PROVABLY cannot fire (sample z <= (n-1)/sqrt(n)).
                          :z-scale 0.1 — the demotion contract: the witnessed
                            force-fit excess (1.729-1.5) becomes p=0.0229,
                            1.58x P-B's measured demotion bar (0.0145); the
                            most extreme witnessed cell (z +4.24) stays at
                            p=0.27, well under the cap.
                          :min-population 5 — no population, no z: a
                            single-candidate pass (the banked web-search
                            canary probe) abstains STRUCTURALLY.
                        HOW THE GATE BEHAVES AS N GROWS (the property that
                        motivated CC-20): the population is the pass's own
                        candidates, so corpus growth refines the mean/sd
                        estimate instead of walking a fixed cutoff off the
                        distribution (Lee 2210.13678 §5.1; Hawking & Robertson
                        2003 §5.4), and per-query scale differences divide out
                        (Rossi CIKM'24 — the old margin's 6/559 firings were
                        all on the one longest-query task's scale).
     :positive-signal — CC-16 / ADR 0026: which reading of the positive signal
                        is APPLIED to the fitness (both are ALWAYS computed
                        and stamped; the other is shadow). :good-when is the
                        ADR-0026 reading — the use-case description ADR 0016's
                        contrast actually names. STAGE 2 FLIPPED BY CC-20: the
                        three ADR-0026 watch conditions are met on the banked
                        cells under the z gate — firing rate SEEN (42/559 at
                        the shipped limit); the web-search zero-FP case
                        exactly 0 (the probe pass abstains structurally, the
                        real Research cell sits at z +0.76); the force-fit
                        DEMOTES (p=0.0229 > 0.0145). Under :content+good-when
                        the force-fit contrast is NEGATIVE (-0.0018, rank 8/43
                        — the summary cancels its own guard), so NO gate of
                        any form can demote it: the flip is a precondition of
                        the EL-5 acceptance contract, not a preference.
     :colbert-norm    — {:max-score nil :method :batch-relative} (JVM-ColBERT
                        Slice 3, amended by CC-17 + CC-27): nil means 'the
                        ceiling of the ENCODING THAT PRODUCED THE SCORE' —
                        read per rerank call off the truncation report the
                        encode stamped on the results (CC-25's
                        results-maxsim-ceiling), NEVER the process default.
                        The process default is only that seam's loud,
                        no-identity fallback. Explicit numeric :max-score
                        configs keep their exact old behavior; the default
                        :batch-relative method never touches the ceiling.

   The pre-CC-20 absolute knobs (:margin 0.010, :penalty-scale 10.0) are
   DELIBERATELY ABSENT — not dead config. See legacy-absolute-defaults +
   gate-config: a config that names a :margin explicitly selects the frozen
   absolute form with its historical semantics."
  {:scorer :colbert
   :embedding-model nil
   :penalty-cap 0.6
   :gate {:form :z-score
          :z-threshold 1.5
          :z-scale 0.1
          :min-population 5}
   :positive-signal :good-when
   :colbert-norm {:max-score nil :method :batch-relative}})

(defn clamp
  "Clamp x to [lo, hi]."
  [x lo hi]
  (-> x (max lo) (min hi)))

(def legacy-absolute-defaults
  "The FROZEN pre-CC-20 absolute-gate calibration (Slice-4b margin retune +
   ADR 0016 scale). These are NOT the shipped defaults any more — CC-20
   (ADR 0027 decision 3) removed the absolute form from the default config
   because every value fitted to this scale was measured either inert (0/154,
   then 0/559 at margin 0.010 on the with-content signal) or firing on
   per-query scale rather than signal (6/559, all on the one longest-query
   task). They remain ONLY as the :or fallbacks of the explicit :absolute
   form, so an operator config or test that names a :margin without restating
   every knob keeps its exact historical meaning."
  {:penalty-scale 10.0
   :margin 0.010})

(defn contrast-population
  "CC-20: the population a z-gated pass judges against — {:n :mean :sd} over
   the pass's contrasts. Sample sd (n-1); degenerate populations (n<=1,
   all-equal) report :sd 0.0 rather than fabricating spread."
  [contrasts]
  (let [xs (mapv double contrasts)
        n (count xs)]
    (if (zero? n)
      {:n 0 :mean 0.0 :sd 0.0}
      (let [mean (/ (reduce + xs) (double n))
            sd (if (< n 2)
                 0.0
                 (Math/sqrt (/ (reduce + (map (fn [x] (let [d (- x mean)] (* d d))) xs))
                               (double (dec n)))))]
        {:n n :mean mean :sd sd}))))

(defn z-gate-penalty
  "CC-20: the :z-score gate for ONE candidate's contrast against the pass's
   population. Fires iff ALL of:
     - the population is estimable: n >= :min-population AND sd > 0
       (a single-candidate pass — e.g. the banked web-search canary probe —
       abstains STRUCTURALLY: a z against no population is not a z);
     - contrast > 0 — the ADR 0016 theory anchor: the penalty asserts 'the
       task is more the avoid-condition than the use-case', and a non-positive
       contrast means it is not, however extreme its z within the pass;
     - z = (contrast - mean) / sd  >  :z-threshold.
   Then penalty = clamp(z-scale * (z - z-threshold), 0, penalty-cap) —
   graded by HOW FAR outside the pass's own distribution the cell sits,
   capped so demotion never annihilates."
  [contrast {:keys [n mean sd] :as _population}
   {:keys [z-threshold z-scale min-population]
    :or {z-threshold 1.5 z-scale 0.1 min-population 5}}
   penalty-cap]
  (let [c (double contrast)]
    (if (or (< n (long min-population))
            (not (pos? (double sd)))
            (<= c 0.0))
      0.0
      (let [z (/ (- c (double mean)) (double sd))]
        (clamp (* (double z-scale) (max 0.0 (- z (double z-threshold))))
               0.0
               (double penalty-cap))))))

(defn gate-config
  "CC-20: resolve which gate FORM a config selects, plus that form's knobs.

   An explicit :margin ANYWHERE in the config selects the frozen :absolute
   form — an operator's calibrated pre-CC-20 override (or a test pinning the
   absolute arithmetic) keeps its exact historical meaning; missing absolute
   knobs fall back to legacy-absolute-defaults. Otherwise the config's :gate
   map, defaulted key-by-key from the shipped :z-score gate. The precedence
   matters for the production merge site (interface apply-rerank merges
   default-penalty-config UNDER the operator's :domain-penalty-config): the
   merged map always carries the default :gate, so :margin-present is the only
   honest signal that the operator asked for the absolute form."
  [config]
  (if (contains? config :margin)
    {:form :absolute
     :margin (double (:margin config))
     :penalty-scale (double (get config :penalty-scale
                                 (:penalty-scale legacy-absolute-defaults)))}
    (merge (:gate default-penalty-config) (:gate config))))

(defn domain-penalty
  "The CONTRASTIVE penalty for one candidate, given its two scores.

     cos-avoid — score(:avoid-when, task)  (MAX over the candidate's avoid guards)
     cos-good  — score(:good-when/:summary, task) (MAX over the positive signals)

   Returns a penalty in [0, penalty-cap]. Fires (> 0) ONLY when
   cos-avoid - cos-good > margin: the task is more the avoid-condition than the
   use-case, beyond scorer noise. good >= avoid (or within margin) => 0.

   CC-20: this is the frozen :absolute-form PRIMITIVE — a pure per-cell fn of
   two cosines, reachable only from an explicit-:margin config (gate-config).
   The shipped default gate is population-relative (z-gate-penalty) and does
   not route through here; missing absolute knobs fall back to the frozen
   legacy-absolute-defaults, never to the shipped config (which no longer
   carries them)."
  ([cos-avoid cos-good]
   (domain-penalty cos-avoid cos-good legacy-absolute-defaults))
  ([cos-avoid cos-good {:keys [penalty-scale margin penalty-cap]
                        :or {penalty-scale (:penalty-scale legacy-absolute-defaults)
                             margin (:margin legacy-absolute-defaults)
                             penalty-cap (:penalty-cap default-penalty-config)}}]
   (let [contrast (- (double cos-avoid) (double cos-good) (double margin))]
     (clamp (* (double penalty-scale) (max 0.0 contrast))
            0.0
            (double penalty-cap)))))

(defn apply-penalty
  "final_fitness = llm_fitness * (1 - penalty). nil fitness passes through nil
   (the :colbert-fallback path leaves fitness nil on purpose)."
  [fitness penalty]
  (when (some? fitness)
    (* (double fitness) (- 1.0 (double penalty)))))

(defn avoid-strings
  "The candidate's NEGATIVE signals — the strings allowed to SUPPRESS it.

   CC-9a (ADR 0022, `invariant.OnlyValidatedClaimsEnforce`): when EL-2's
   enrichment stamped `::enforcing-avoid-when`, that vector IS the answer.
   The candidate is CLAIM-BACKED, and the stamp is the subset of its guards
   that reached `:validated` — an unproven claim stays in `:avoid-when` (the
   reranker still reads it) but is not here, so it cannot move the ranking.
   PRESENT-AND-EMPTY is meaningful: 'this target has claims and none of them
   has earned enforcement yet'.

   ABSENT means there is no claim set to gate on — a legacy wholesale-recorded
   body, or a candidate map built by hand — and the reading is unchanged from
   before CC-9a: the top-level :avoid-when vector unioned with any per-weakness
   :avoid-when guards. EL-2's enrichment already folds the per-weakness guards
   into the top-level vector, but we union defensively so the penalty reads
   every guard the body carries."
  [candidate]
  (if (contains? candidate :ai.obney.orc.ontology.core.domain-penalty/enforcing-avoid-when)
    (vec (distinct (:ai.obney.orc.ontology.core.domain-penalty/enforcing-avoid-when candidate)))
    (vec (distinct (concat (:avoid-when candidate)
                           (keep :avoid-when (:weaknesses candidate)))))))

(defn positive-strings
  "The candidate's POSITIVE signal (ADR 0026): every `:good-when` from the
   enriched strengths, and NOTHING else. MAX over these is cos-good — the
   use-case description the avoid-condition must beat for the penalty to fire.

   `:content` — the whole indexed description — used to be in here, and removing
   it is framed as RESTORING ADR 0016's intent rather than changing it: that ADR
   says the avoid-condition must beat 'the use-case description', and
   `:good-when` IS the use-case description; `:content` is the whole document.

   MEASURED CAUSE (P-B / P-C: real 17-behavior corpus, real JVM-native ColBERT).
   The avoid guards and the positive signal go into ONE rerank call, and
   `batch-relative-scores` divides every score in that call by THAT CALL'S max —
   so whichever side scores highest is pinned at exactly 1.0. A ~800-char
   consolidated summary that restates the behavior's own avoid-conditions in
   prose therefore CANCELLED ITS OWN GUARD: `cos-good` was `1.000000` in 153 of
   154 cells and the penalty fired 0 times. A ~1,700-char document was competing
   against ~99-char guards (n=44, mean 99.2) for the same normalizer.

   Dropping `:content` does three things at once: the key stops competing with
   its own guard; the two sides become length-matched (`:good-when` ~=
   `:avoid-when` ~= 99 chars); and any future retrieval-key work is decoupled
   from EL-5 entirely."
  [candidate]
  (vec (distinct (keep :good-when (:strengths candidate)))))

(defn legacy-positive-strings
  "The PRE-ADR-0026 positive signal: the indexed description (`:content`) PLUS
   every `:good-when`. Retained ONLY so the penalty can compute and report BOTH
   readings on every pass — ADR 0027 requires a gate to be able to report
   whether it is doing anything, and silence must stay distinguishable from
   absence.

   This is a SUPERSET of `positive-strings`, which is why the shadow is FREE:
   cos-good is a MAX over already-scored strings, so one rerank call over this
   set yields both variants with zero additional round-trips."
  [candidate]
  (vec (distinct (concat (when (:content candidate) [(:content candidate)])
                         (keep :good-when (:strengths candidate))))))

(def positive-signal-variants
  "The two readings of the positive signal, by name:

     :good-when          — ADR 0026 (`positive-strings`)
     :content+good-when  — pre-ADR-0026 (`legacy-positive-strings`)

   BOTH are computed on every pass; `:positive-signal` in the config selects
   which one is APPLIED to the fitness. The other is stamped as shadow."
  #{:good-when :content+good-when})

(defn positive-strings-for
  "The positive signal under one named variant."
  [variant candidate]
  (case variant
    :good-when (positive-strings candidate)
    (legacy-positive-strings candidate)))

(defn scored-strings
  "Every string ONE rerank/embed call must cover for a candidate: its avoid
   guards plus the UNION of both positive-signal readings. Because the legacy
   reading is a superset of the ADR-0026 one, this is EXACTLY the document set
   the pre-CC-16 code already sent — computing the shadow costs zero extra calls
   AND zero extra documents."
  [candidate]
  (vec (distinct (concat (avoid-strings candidate)
                         (legacy-positive-strings candidate)))))

(defn applied-positive-signal
  "Which positive-signal variant this config APPLIES to the fitness. An
   unrecognised value falls back to the shipped default and is LOGGED — a
   typo'd operator config must never silently change which signal bites."
  [config]
  (let [v (get config :positive-signal (:positive-signal default-penalty-config))]
    (if (positive-signal-variants v)
      v
      (do (u/log :ai.obney.orc.ontology.core.domain-penalty/unknown-positive-signal
                  :positive-signal v
                  :falling-back-to (:positive-signal default-penalty-config))
          (:positive-signal default-penalty-config)))))

(defn- dual-cosines
  "Compute {:cos-avoid :cos-good} under BOTH positive-signal readings from the
   SAME already-scored strings, and alias the APPLIED reading onto the canonical
   :cos-avoid / :cos-good keys so the scorer contract is unchanged for callers
   that only read those two.

   `variant->cosines` is (fn [variant] -> {:cos-avoid :cos-good})."
  [variant->cosines applied]
  (let [with-content (variant->cosines :content+good-when)
        sans-content (variant->cosines :good-when)
        app (if (= :good-when applied) sans-content with-content)]
    {:cos-avoid (:cos-avoid app)
     :cos-good  (:cos-good app)
     :cos-avoid-with-content (:cos-avoid with-content)
     :cos-good-with-content  (:cos-good with-content)
     :cos-avoid-sans-content (:cos-avoid sans-content)
     :cos-good-sans-content  (:cos-good sans-content)
     :positive-signal applied}))

(defn- resolve-colbert-fns
  "Lazily resolve the colbert interface fns the :colbert backend needs.
   Returns {:rerank <var> :normalize <var> :ceiling <var>} when the colbert
   component is on the classpath, nil otherwise. Resolving VARS (not fn values)
   preserves with-redefs / re-def semantics — a var call derefs at invocation
   time, exactly like the previous static `colbert/rerank` call.

   CC-27: `:ceiling` is CC-25's `results-maxsim-ceiling` — the ceiling of the
   encoding that produced a result collection, read off its truncation-report
   metadata. The :linear/:sigmoid scorer paths normalize against IT when
   `:colbert-norm {:max-score nil}` is configured (the process default is only
   its own loud, no-identity fallback)."
  []
  (let [rerank    (integrations/capability :colbert :rerank)
        normalize (integrations/capability :colbert :normalize-colbert-score)
        ceiling   (integrations/capability :colbert :results-maxsim-ceiling)]
    (when (and rerank normalize ceiling)
      {:rerank rerank :normalize normalize :ceiling ceiling})))

(def ^:dynamic *colbert-resolver*
  "The INJECTABLE resolver seam (defaults to the registered ColBERT provider).
   A fn of no args returning {:rerank <ifn> :normalize <ifn>} or nil when the
   colbert component is absent. Tests bind this to (constantly nil) to
   simulate 'colbert not on the classpath', or to a fake-returning resolver
   to drive the present path deterministically."
  resolve-colbert-fns)

(defn- colbert-unavailable-ex
  "The precise, loud-at-the-source error for 'colbert explicitly requested but
   not on the classpath'."
  []
  (ex-info
   (str "Domain-penalty scorer :colbert was requested, but the colbert component "
        "(ai.obney.orc.colbert.interface) is not on the classpath. The shipped "
        "orc-ontology project deliberately excludes colbert (and its Python-venv "
        "bridge) so ontology runs venv-free. Either add the orc-colbert package "
        "to your classpath, or configure {:scorer :embedding} (the embed+cosine "
        "backend) in :domain-penalty-config.")
   {:error :colbert-unavailable
    :missing-component 'ai.obney.orc.colbert.interface
    :requested-scorer :colbert
    :alternative {:scorer :embedding}}))

(defn- scorer-explicit?
  "Did the CALLER explicitly choose the scorer, vs inheriting the default?
   Drives the colbert-absent semantics: explicit :colbert => loud ex-info;
   default => graceful :embedding degradation with a warning.

   The public search path (ontology interface apply-rerank) MERGES
   default-penalty-config with the operator's ctx :domain-penalty-config
   BEFORE calling in, so the merged config ALWAYS carries :scorer — there the
   operator's PRE-merge map (still on ctx) is the honest explicitness signal.
   Direct callers may state it outright via a :scorer-explicit? entry in
   config, or implicitly by passing a config that names a :scorer and is not
   just the shipped default map (penalize-candidates' default 3-arity passes
   default-penalty-config itself, which stays the DEFAULT case)."
  [ctx config]
  (cond
    (contains? config :scorer-explicit?)
    (boolean (:scorer-explicit? config))

    (map? (:domain-penalty-config ctx))
    (contains? (:domain-penalty-config ctx) :scorer)

    :else
    (and (contains? config :scorer)
         (not= config default-penalty-config))))

(defn- colbert-backend-or-degrade
  "Availability gate for a :colbert backend selection. Semantics:
     resolvable                => (colbert-ctor) — EXACTLY today's behavior.
     absent + EXPLICIT config  => throw the precise colbert-unavailable ex-info.
     absent + DEFAULT config   => (embedding-ctor) — the existing :embedding
                                  backend — with a mulog warning naming the
                                  substitution (graceful degradation, matching
                                  every other ontology->colbert touchpoint)."
  [ctx config colbert-ctor embedding-ctor]
  (if (*colbert-resolver*)
    (colbert-ctor)
    (if (scorer-explicit? ctx config)
      (throw (colbert-unavailable-ex))
      (do (u/log :ai.obney.orc.ontology.core.domain-penalty/colbert-unavailable-fallback
                  :requested-scorer :colbert
                  :selected-scorer :embedding
                  :reason "colbert component not on the classpath; default config degrades to the :embedding backend")
          (embedding-ctor)))))

;; --- Embedding ---

(defn dot-product
  "Compute dot product of two vectors."
  [a b]
  (reduce + (map * a b)))

(defn magnitude
  "Compute magnitude (L2 norm) of a vector."
  [v]
  (Math/sqrt (reduce + (map #(* % %) v))))

(defn embedding-cosine-similarity
  "Compute cosine similarity between two vectors.

   Returns:
     Similarity score in range [-1, 1], or 0.0 if either vector is zero."
  [a b]
  (let [dot (dot-product a b)
        mag-a (magnitude a)
        mag-b (magnitude b)]
    (if (or (zero? mag-a) (zero? mag-b))
      0.0
      (/ dot (* mag-a mag-b)))))

;; --- Domain penalty ---

(defn- max-cos
  "MAX cosine of the task embedding against a collection of strings. Embeds each
   non-blank string via the embedding interface; returns 0.0 when there is
   nothing to compare (so a missing signal never fabricates a penalty — a
   candidate with no :avoid-when can't be penalized; a candidate with no
   positive signal has cos-good 0.0, the conservative side)."
  [embed-fn task-embedding strings]
  (let [vals (->> strings
                  (remove (fn [s] (or (nil? s) (str/blank? s))))
                  (keep (fn [s]
                          (when-let [e (embed-fn s)]
                            (embedding-cosine-similarity task-embedding e)))))]
    (if (seq vals) (apply max vals) 0.0)))

(defn embedding-scorer
  "The :embedding backend (original (b) path). embed+cosine, MAX over guards.
   The embedding MODEL is config-swappable via :embedding-model (nil => the
   embedding component default). Pure given embed-fn; embeds the task ONCE per
   candidate-batch by closing over a memo. Returns a scorer fn (candidate task).

   In production, embed-fn defaults to the real embedding interface; tests pass a
   deterministic fake embed-fn so no DJL model loads."
  ([config] (embedding-scorer config embedding-embed-text))
  ([{:keys [embedding-model] :as config} embed-fn]
   (let [embed (if embedding-model
                 (fn [s] (embed-fn s {:model-id embedding-model}))
                 embed-fn)
         task->emb (memoize embed)
         applied (applied-positive-signal config)]
     (fn [candidate task]
       (let [task-emb (task->emb task)]
         (if (nil? task-emb)
           {:cos-avoid 0.0 :cos-good 0.0}
           (let [cos-avoid (max-cos embed task-emb (avoid-strings candidate))]
             (dual-cosines
              (fn [variant]
                {:cos-avoid cos-avoid
                 :cos-good (max-cos embed task-emb (positive-strings-for variant candidate))})
              applied))))))))

(defn colbert-rerank-scores
  "Adapter helper (PURE given rerank-fn): given a rerank-fn that maps
   {:query :documents} -> [{:content :score}], the candidate's avoid + positive
   strings, and the task, run ONE rerank call over (concat avoid good) so every
   guard is scored against the SAME query on the SAME scale, split the results
   back BY CONTENT, take MAX over each group, and NORMALIZE each via norm-fn ->
   [0,1]. Returns {:cos-avoid :cos-good}.

   No guards on a side => that side's score is 0.0 (the conservative side: a
   candidate with no :avoid-when can't be penalized). Deterministic given
   rerank-fn, so tests stub rerank-fn and assert split+max+normalize without
   touching the bridge.

   ⚠ CC-27: norm-fn arrives here already CLOSED over its ceiling — this
   adapter cannot thread a per-encoding ceiling itself. Whoever constructs
   norm-fn owns that obligation: a fixed-ceiling norm-fn built from
   normalize-colbert-score must carry the PRODUCING call's ceiling
   (results-maxsim-ceiling), not the process default. The production scorers
   (colbert-scorer / batch-colbert-scorer) do this; only tests call this
   adapter directly today."
  [rerank-fn norm-fn avoid good task]
  (let [avoid (vec (remove (fn [s] (or (nil? s) (str/blank? s))) avoid))
        good  (vec (remove (fn [s] (or (nil? s) (str/blank? s))) good))
        docs  (vec (distinct (concat avoid good)))]
    (if (empty? docs)
      {:cos-avoid 0.0 :cos-good 0.0}
      (let [res (rerank-fn {:query task :documents docs})
            by-content (into {} (map (juxt :content :score)) res)
            max-norm (fn [strings]
                       (let [scores (keep by-content strings)]
                         (if (seq scores)
                           (norm-fn (apply max scores))
                           0.0)))]
        {:cos-avoid (max-norm avoid)
         :cos-good  (max-norm good)}))))

(defn batch-relative-scores
  "The :batch-relative normalization (JVM-ColBERT Slice 3): given one rerank
   call's content->RAW-score map, divide every score by the call's MAX raw
   score (the normalize-result-scores idiom) so each guard expresses affinity
   RELATIVE to the strongest guard in the call. This is what restores the
   contrastive margin on the answerai checkpoint, whose MASK-expansion floor
   (~30/32) makes fixed-ceiling normalization collapse all cosines together.

   Bounds: empty map => {} (nothing fabricated); non-positive max =>
   normalizer 1.0 (raw pass-through — an all-zero call can never fabricate a
   contrast, and division by zero is impossible); all-equal positive scores =>
   every score 1.0 (contrast 0)."
  [content->raw]
  (if (empty? content->raw)
    {}
    (let [mx (apply max (vals content->raw))
          normalizer (if (pos? mx) (double mx) 1.0)]
      (into {} (map (fn [[content score]]
                      [content (/ (double score) normalizer)]))
            content->raw))))

(defn- relative-cosines
  "ONE variant's :batch-relative cosines: normalize the candidate's own
   (avoid ++ good) sub-map by ITS max raw score, then MAX per side.

   CC-16: the variant's own guard set is what defines the normalizer, so
   dropping `:content` from the positive side ALSO drops it from the divisor —
   which is the whole point. When `:content` was the call's max (P-C:
   `:winner-of-normalizer :POSITIVE`), removing it moves cos-avoid to 1.0 and
   un-pins cos-good from 1.0. Both variants are still served from the SAME raw
   scores, because MaxSim is per-document independent: this is arithmetic on
   already-scored strings, not a second rerank call."
  [raw-score-map avoid good]
  (let [sub (into {}
                  (keep (fn [s] (when-some [v (raw-score-map s)] [s v])))
                  (distinct (concat avoid good)))
        rel (batch-relative-scores sub)
        max-over (fn [strings]
                   (let [vs (keep rel strings)]
                     (if (seq vs) (apply max vs) 0.0)))]
    {:cos-avoid (max-over avoid)
     :cos-good  (max-over good)}))

(defn- candidate-relative-cosines-fn
  "Per-candidate :batch-relative lookup: given a SHARED content->RAW-score map
   (from one physical rerank call — per-candidate or batched across candidates;
   raw MaxSim is per-doc independent, so sharing is results-neutral), return
     (fn [candidate] -> {:cos-avoid :cos-good <+ both variants>})
   where each candidate's guards are normalized by the MAX raw score among
   THAT CANDIDATE'S OWN guards (batch-relative-scores over its sub-map), then
   maxed per side. Scoping the normalizer to the candidate keeps the batched
   hot path IDENTICAL to the per-candidate scorer (no cross-candidate
   contamination of the contrast) and matches 'normalize by the call's max' —
   the call being the candidate's own avoid+good rerank. A side with no scored
   guards is 0.0 (the conservative side, never fabricated)."
  [raw-score-map config]
  (let [applied (applied-positive-signal config)]
    (fn [candidate]
      (let [avoid (avoid-strings candidate)]
        (dual-cosines
         (fn [variant] (relative-cosines raw-score-map avoid
                                         (positive-strings-for variant candidate)))
         applied)))))

(defn colbert-scorer
  "The :colbert backend (DEFAULT). In-memory MaxSim via colbert/rerank — NO
   index. Returns a scorer fn (candidate task). Closes over ctx + the
   normalization opts. rerank-fn / norm-fn default to the real colbert interface;
   tests inject stubs.

   NB: one rerank call PER CANDIDATE (its own guard set), each scoring all of
   that candidate's guards against the task in a single bridge round-trip.

   CC-27: on the explicit :linear/:sigmoid paths, a `:max-score nil` config
   means the ceiling of the ENCODING THAT PRODUCED THE SCORES — resolved
   per rerank call by `ceiling-fn` (the real backend wires CC-25's
   `results-maxsim-ceiling`, which reads `maximum_query_tokens` off the
   truncation report the encode call stamped on the result collection). It is
   NOT the process default: an encoding above the default would clamp every
   score to 1.0 (order destroyed — `invariant.BoundedNormalization`), below it
   the scale silently compresses. The process default is reached only when no
   encoding identity exists (no truncation report), and that fallback is LOUD
   (mulogged on the colbert side), never silent.

   The 2-arity resolves the colbert fns via *colbert-resolver* at construction
   time; it IS an explicit colbert request, so it throws the precise
   colbert-unavailable ex-info when the component is absent (make-scorer /
   make-batch-scorer own the default-config graceful degradation). The
   injected arities: the 5-arity takes `ceiling-fn` (results -> ceiling or
   nil); the 4-arity keeps its historical contract — no ceiling-fn, so a nil
   :max-score flows to norm-fn as-is, exactly as before CC-27 (injected
   norm-fn stubs own their ceiling semantics)."
  ([ctx config]
   (let [{:keys [rerank normalize ceiling]} (or (*colbert-resolver*)
                                                (throw (colbert-unavailable-ex)))]
     (colbert-scorer ctx config
                     (fn [opts] (rerank ctx opts))
                     normalize
                     (or ceiling (fn [_results] nil)))))
  ([ctx config rerank-fn norm-fn]
   (colbert-scorer ctx config rerank-fn norm-fn (fn [_results] nil)))
  ([_ctx {:keys [colbert-norm] :as config} rerank-fn norm-fn ceiling-fn]
   (let [{:keys [max-score method]
          :or {max-score (:max-score (:colbert-norm default-penalty-config))
               method (:method (:colbert-norm default-penalty-config))}} colbert-norm
         applied (applied-positive-signal config)]
     (if (= :batch-relative method)
       (fn [candidate task]
         (let [docs (vec (remove (fn [s] (or (nil? s) (str/blank? s)))
                                 (scored-strings candidate)))]
           (if (empty? docs)
             {:cos-avoid 0.0 :cos-good 0.0}
             (let [res (rerank-fn {:query task :documents docs})
                   raw (into {} (map (juxt :content :score)) res)]
               ((candidate-relative-cosines-fn raw config) candidate)))))
       (fn [candidate task]
         (let [avoid (vec (remove (fn [s] (or (nil? s) (str/blank? s)))
                                  (avoid-strings candidate)))
               docs  (vec (remove (fn [s] (or (nil? s) (str/blank? s)))
                                  (scored-strings candidate)))]
           (if (empty? docs)
             {:cos-avoid 0.0 :cos-good 0.0}
             (let [res (rerank-fn {:query task :documents docs})
                   ceiling (if (some? max-score) max-score (ceiling-fn res))
                   norm (fn [score] (norm-fn score :max-score ceiling :method method))
                   by-content (into {} (map (juxt :content :score)) res)
                   max-norm (fn [strings]
                              (let [scores (keep by-content strings)]
                                (if (seq scores) (norm (apply max scores)) 0.0)))
                   cos-avoid (max-norm avoid)]
               (dual-cosines
                (fn [variant]
                  {:cos-avoid cos-avoid
                   :cos-good (max-norm (remove (fn [s] (or (nil? s) (str/blank? s)))
                                               (positive-strings-for variant candidate)))})
                applied)))))))))

(defn make-scorer
  "Select + construct the PER-CANDIDATE scorer from config (ADR 0016 amendment):
   :colbert (DEFAULT) or :embedding. Default real backend; tests bypass this and
   inject a fake scorer directly into score-candidate / penalize-candidates.

   NB: this is the N-call (one bridge round-trip per candidate) seam, retained for
   score-candidate's pure contract + the proto. The HOT PATH uses make-batch-scorer
   (EL-5.1 — one call for the whole batch)."
  [ctx {:keys [scorer] :or {scorer (:scorer default-penalty-config)} :as config}]
  (case scorer
    :embedding (embedding-scorer config)
    :colbert   (colbert-backend-or-degrade ctx config
                                           #(colbert-scorer ctx config)
                                           #(embedding-scorer config))
    (do (u/log :ai.obney.orc.ontology.core.domain-penalty/unknown-scorer :scorer scorer :falling-back-to :colbert)
        (colbert-backend-or-degrade ctx config
                                    #(colbert-scorer ctx config)
                                    #(embedding-scorer config)))))

(defn- distinct-guards
  "All DISTINCT non-blank guard strings across the candidate set, split into the
   :avoid and :good universes (deduped within each), and the combined distinct
   document set for ONE rerank/embed call.

   CC-16: the :good universe is the LEGACY (superset) reading, so ONE call still
   scores both positive-signal variants — the document set is byte-identical to
   the pre-CC-16 one and the shadow costs nothing."
  [candidates]
  (let [non-blank (fn [ss] (remove (fn [s] (or (nil? s) (str/blank? s))) ss))
        avoid (vec (distinct (non-blank (mapcat avoid-strings candidates))))
        good  (vec (distinct (non-blank (mapcat legacy-positive-strings candidates))))
        docs  (vec (distinct (concat avoid good)))]
    {:avoid avoid :good good :docs docs}))

(defn- candidate-cosines-fn
  "Given a content->normalized-score map (the SHARED scores from the single
   batched call), return a pure per-candidate lookup
     (fn [candidate] -> {:cos-avoid :cos-good <+ both variants>})
   that maxes each candidate's avoid-strings / positive strings over the map.
   A guard absent from the map (e.g. blank, never scored) contributes nothing; a
   side with no scored guards is 0.0 (never fabricated — the conservative side).

   Fixed-ceiling normalization is per-score, so the two positive-signal variants
   differ only in which strings the good-side MAX ranges over."
  [score-map config]
  (let [applied (applied-positive-signal config)
        max-over (fn [strings]
                   (let [vs (keep score-map strings)]
                     (if (seq vs) (apply max vs) 0.0)))]
    (fn [candidate]
      (let [cos-avoid (max-over (avoid-strings candidate))]
        (dual-cosines
         (fn [variant] {:cos-avoid cos-avoid
                        :cos-good (max-over (positive-strings-for variant candidate))})
         applied)))))

(defn batch-colbert-scorer
  "The :colbert BATCH backend (DEFAULT, EL-5.1). Returns a factory
     (fn [candidates task] -> (fn [candidate] {:cos-avoid :cos-good}))
   that makes EXACTLY ONE colbert/rerank call over the DISTINCT guard set across
   ALL candidates (one shared task query), normalizes each score, and serves every
   candidate from the shared map. rerank-fn / norm-fn default to the real colbert
   interface; tests inject a stubbed rerank-fn (with a call counter — the headline
   guardrail).

   RESULTS-NEUTRAL: MaxSim(query, doc) is per-doc independent of the other docs in
   the call, and all candidates share the same task query, so the shared map gives
   the IDENTICAL per-guard scores as the N-call colbert-scorer.

   CC-27: same per-encoding ceiling obligation as colbert-scorer — on the
   explicit :linear/:sigmoid paths a nil :max-score means the ceiling of the
   encoding that produced THIS batch call's scores (`ceiling-fn`, wired to
   CC-25's `results-maxsim-ceiling` by the real resolver), never the process
   default. The 4-arity keeps its historical injected-stub contract (no
   ceiling-fn => a nil :max-score flows to norm-fn as-is).

   The 2-arity resolves the colbert fns via *colbert-resolver* at construction
   time; it IS an explicit colbert request, so it throws the precise
   colbert-unavailable ex-info when the component is absent (make-scorer /
   make-batch-scorer own the default-config graceful degradation)."
  ([ctx config]
   (let [{:keys [rerank normalize ceiling]} (or (*colbert-resolver*)
                                                (throw (colbert-unavailable-ex)))]
     (batch-colbert-scorer ctx config
                           (fn [opts] (rerank ctx opts))
                           normalize
                           (or ceiling (fn [_results] nil)))))
  ([ctx config rerank-fn norm-fn]
   (batch-colbert-scorer ctx config rerank-fn norm-fn (fn [_results] nil)))
  ([_ctx {:keys [colbert-norm] :as config} rerank-fn norm-fn ceiling-fn]
   (let [{:keys [max-score method]
          :or {max-score (:max-score (:colbert-norm default-penalty-config))
               method (:method (:colbert-norm default-penalty-config))}} colbert-norm]
     (fn [candidates task]
       (let [{:keys [docs]} (distinct-guards candidates)]
         (if (empty? docs)
           (fn [_candidate] {:cos-avoid 0.0 :cos-good 0.0})
           (let [res (rerank-fn {:query task :documents docs})]
             (if (= :batch-relative method)
               (candidate-relative-cosines-fn
                (into {} (map (juxt :content :score)) res)
                config)
               (let [ceiling (if (some? max-score) max-score (ceiling-fn res))
                     norm (fn [score] (norm-fn score :max-score ceiling :method method))
                     score-map (into {} (map (juxt :content (comp norm :score))) res)]
                 (candidate-cosines-fn score-map config))))))))))

(defn batch-embedding-scorer
  "The :embedding BATCH backend (EL-5.1). Returns a factory
     (fn [candidates task] -> (fn [candidate] {:cos-avoid :cos-good}))
   that embeds the task ONCE and the DISTINCT guard set ONCE (across ALL
   candidates), builds a content->cosine map, and serves every candidate from it.
   The embedding MODEL is config-swappable via :embedding-model. Pure given
   embed-fn; tests pass a deterministic fake embed-fn so no DJL model loads.

   Same RESULTS-NEUTRAL property as the N-call embedding-scorer: cosine(task,
   guard) is independent of the other guards, so the shared map matches."
  ([config] (batch-embedding-scorer config embedding-embed-text))
  ([{:keys [embedding-model] :as config} embed-fn]
   (let [embed (if embedding-model
                 (fn [s] (embed-fn s {:model-id embedding-model}))
                 embed-fn)]
     (fn [candidates task]
       (let [task-emb (embed task)]
         (if (nil? task-emb)
           (fn [_candidate] {:cos-avoid 0.0 :cos-good 0.0})
           (let [{:keys [docs]} (distinct-guards candidates)
                 score-map (into {}
                                 (keep (fn [s]
                                         (when-let [e (embed s)]
                                           [s (embedding-cosine-similarity task-emb e)])))
                                 docs)]
             (candidate-cosines-fn score-map config))))))))

(defn make-batch-scorer
  "Select + construct the BATCH scorer factory from config (EL-5.1): :colbert
   (DEFAULT) or :embedding. Returns (fn [candidates task] -> per-candidate-fn).
   Default real backend; tests bypass this and inject a fake batch factory (or use
   the deterministic stub rerank-fn / embed-fn). Mirrors make-scorer's selection."
  [ctx {:keys [scorer] :or {scorer (:scorer default-penalty-config)} :as config}]
  (case scorer
    :embedding (batch-embedding-scorer config)
    :colbert   (colbert-backend-or-degrade ctx config
                                           #(batch-colbert-scorer ctx config)
                                           #(batch-embedding-scorer config))
    (do (u/log :ai.obney.orc.ontology.core.domain-penalty/unknown-scorer :scorer scorer :falling-back-to :colbert)
        (colbert-backend-or-degrade ctx config
                                    #(batch-colbert-scorer ctx config)
                                    #(batch-embedding-scorer config)))))

(defn- variant-record
  "The {cos-avoid, cos-good, penalty} record for ONE shadowed positive-signal
   variant, or nil when the scorer did not report that variant. An INJECTED FAKE
   scorer returning only {:cos-avoid :cos-good} therefore stays valid — the
   shadow keys are simply absent, never fabricated."
  [cosines a-key g-key p-key config]
  (when (and (some? (get cosines a-key)) (some? (get cosines g-key)))
    (let [a (double (get cosines a-key))
          g (double (get cosines g-key))]
      {a-key a g-key g p-key (domain-penalty a g config)})))

(defn- contrast-record
  "Normalize a scorer's return into the full contrast+penalty record: the
   APPLIED :cos-avoid / :cos-good / :penalty, plus whichever positive-signal
   variants the scorer reported (CC-16 shadow mode, ADR 0027)."
  [cosines config]
  (let [cos-avoid (double (or (:cos-avoid cosines) 0.0))
        cos-good  (double (or (:cos-good cosines) 0.0))]
    (merge {:cos-avoid cos-avoid
            :cos-good cos-good
            :penalty (domain-penalty cos-avoid cos-good config)}
           (when-let [ps (:positive-signal cosines)] {:positive-signal ps})
           (variant-record cosines :cos-avoid-with-content :cos-good-with-content
                           :domain-penalty-with-content config)
           (variant-record cosines :cos-avoid-sans-content :cos-good-sans-content
                           :domain-penalty-sans-content config))))

(defn- z-contrast-records
  "CC-20 :z-score stamping for a WHOLE PASS: every key-pair's penalties are
   judged against that pair's contrast population across the pass. The applied
   pair's population is all candidates (missing cosines read 0.0, exactly as
   contrast-record always did); a shadow variant's population is the
   candidates that reported that variant (absent stays absent — never
   fabricated). When every candidate reports both variants — every production
   backend does — the applied record's penalty is identical to its aliased
   variant's, because they are the same contrasts against the same population."
  [cosines-seq gate cap]
  (let [applied-contrast (fn [cos] (- (double (or (:cos-avoid cos) 0.0))
                                      (double (or (:cos-good cos) 0.0))))
        applied-pop (contrast-population (map applied-contrast cosines-seq))
        pair-pop (fn [a-key g-key]
                   (contrast-population
                    (keep (fn [cos]
                            (when (and (some? (get cos a-key)) (some? (get cos g-key)))
                              (- (double (get cos a-key)) (double (get cos g-key)))))
                          cosines-seq)))
        with-pop (pair-pop :cos-avoid-with-content :cos-good-with-content)
        sans-pop (pair-pop :cos-avoid-sans-content :cos-good-sans-content)
        vrec (fn [cos a-key g-key p-key pop]
               (when (and (some? (get cos a-key)) (some? (get cos g-key)))
                 (let [a (double (get cos a-key))
                       g (double (get cos g-key))]
                   {a-key a
                    g-key g
                    p-key (z-gate-penalty (- a g) pop gate cap)})))]
    (mapv (fn [cos]
            (merge {:cos-avoid (double (or (:cos-avoid cos) 0.0))
                    :cos-good  (double (or (:cos-good cos) 0.0))
                    :penalty (z-gate-penalty (applied-contrast cos) applied-pop gate cap)}
                   (when-let [ps (:positive-signal cos)] {:positive-signal ps})
                   (vrec cos :cos-avoid-with-content :cos-good-with-content
                         :domain-penalty-with-content with-pop)
                   (vrec cos :cos-avoid-sans-content :cos-good-sans-content
                         :domain-penalty-sans-content sans-pop)))
          cosines-seq)))

(defn- contrast-records
  "The contrast+penalty records for EVERY cosines map in one pass, gate-form
   aware (CC-20): :absolute => the pre-CC-20 per-cell arithmetic, byte for
   byte; :z-score (the default) => population-relative via z-contrast-records."
  [cosines-seq config]
  (let [g (gate-config config)]
    (if (= :absolute (:form g))
      (mapv #(contrast-record % config) cosines-seq)
      (z-contrast-records cosines-seq g
                          (double (get config :penalty-cap
                                       (:penalty-cap default-penalty-config)))))))

(defn- stamp-all
  "Stamp every candidate with its contrast record + :domain-penalty and its
   penalized :fitness-score (the fitness is penalized by the APPLIED variant
   ONLY; the shadow is observability, never behaviour)."
  [candidates cosines-seq config]
  (mapv (fn [c record]
          (let [penalty (:penalty record)]
            (-> c
                (merge (dissoc record :penalty))
                (assoc :domain-penalty penalty)
                (assoc :fitness-score (apply-penalty (:fitness-score c) penalty)))))
        candidates
        (contrast-records cosines-seq config)))

(defn- scorer-cosines
  "Call the injected per-candidate scorer, FAILING OPEN to {0,0} on any throw."
  [candidate task scorer]
  (try
    (scorer candidate task)
    (catch Throwable t
      (u/log :ai.obney.orc.ontology.core.domain-penalty/scorer-failed
              :document-id (:document-id candidate)
              :error (.getMessage t))
      {:cos-avoid 0.0 :cos-good 0.0})))

(defn score-candidate
  "Compute {:cos-avoid :cos-good :penalty} for one enriched candidate against the
   task, using the injected SCORER. The scorer ((scorer candidate task) ->
   {:cos-avoid :cos-good}) is the seam: :colbert / :embedding in production, a
   deterministic fake in tests. Pure given the scorer + config.

   CC-16: when the scorer also reports the two positive-signal variants (every
   production backend does), the record additionally carries
   :cos-avoid/:cos-good/:domain-penalty -with-content and -sans-content. Those
   keys are ADDITIVE — a fake scorer that returns only the two canonical keys is
   still a valid scorer and simply produces no shadow.

   FAIL OPEN: if the scorer throws (e.g. the ColBERT bridge is unavailable), the
   candidate scores {0,0} -> penalty 0 (the LLM ordering is left untouched, never
   a FABRICATED penalty). The penalty layer is BEST-EFFORT/additive — a scoring
   outage must degrade retrieval gracefully, exactly like the reranker's own
   try/catch fallback, not crash it.

   CC-20: under the default :z-score gate a SINGLE candidate is a population
   of one, so this fn's penalties are 0 by construction (no population, no z)
   — the pass-level penalize-candidates is where the shipped gate bites. An
   explicit-:margin config keeps the pre-CC-20 per-cell behavior exactly."
  [candidate task scorer config]
  (first (contrast-records [(scorer-cosines candidate task scorer)] config)))

(defn- percentile
  "Nearest-rank percentile over an already-sorted vector."
  [sorted p]
  (let [n (count sorted)]
    (nth sorted (min (dec n) (max 0 (int (Math/ceil (- (* (/ p 100.0) n) 1))))))))

(defn- distribution
  "min / p25 / p50 / p75 / p95 / max / mean over a collection of numbers, or nil
   when there is nothing to describe (never a fabricated zero)."
  [xs]
  (when (seq xs)
    (let [v (vec (sort (map double xs)))]
      {:n (count v)
       :min (first v)
       :p25 (percentile v 25)
       :p50 (percentile v 50)
       :p75 (percentile v 75)
       :p95 (percentile v 95)
       :max (peek v)
       :mean (/ (reduce + v) (double (count v)))})))

(defn- variant-summary
  "One positive-signal variant's report over a stamped candidate set."
  [stamped a-key g-key p-key]
  (let [rows (keep (fn [c]
                     (when (and (some? (get c a-key)) (some? (get c g-key)))
                       {:contrast (- (double (get c a-key)) (double (get c g-key)))
                        :penalty (double (or (get c p-key) 0.0))}))
                   stamped)]
    (when (seq rows)
      (let [fired (count (filter #(pos? (:penalty %)) rows))]
        {:n (count rows)
         :fired fired
         :fired-rate (/ (double fired) (count rows))
         :contrast (distribution (map :contrast rows))
         :penalty (distribution (map :penalty rows))}))))

(defn penalty-pass-report
  "ADR 0027: what the domain penalty DID on this pass — its firing rate and the
   contrast distribution it saw, per positive-signal variant, alongside the knobs
   it judged against. PURE: takes the stamped candidates, returns the report.

   `:variants` is keyed by `positive-signal-variants`; a variant the scorer did
   not report is ABSENT rather than zero, so 'the shadow was not computed' can
   never be read as 'the shadow never fired'."
  [stamped config]
  (let [g (gate-config config)
        base {:candidate-count (count stamped)
              :applied-positive-signal (applied-positive-signal config)
              :gate-form (:form g)
              :penalty-cap (double (get config :penalty-cap (:penalty-cap default-penalty-config)))
              :variants (into {}
                              (remove (comp nil? val))
                              {:content+good-when
                               (variant-summary stamped :cos-avoid-with-content
                                                :cos-good-with-content :domain-penalty-with-content)
                               :good-when
                               (variant-summary stamped :cos-avoid-sans-content
                                                :cos-good-sans-content :domain-penalty-sans-content)})}]
    (if (= :absolute (:form g))
      (assoc base
             :margin (:margin g)
             :penalty-scale (:penalty-scale g))
      (assoc base
             :z-threshold (double (:z-threshold g))
             :z-scale (double (:z-scale g))
             :min-population (long (:min-population g))
             :population (contrast-population
                          (map (fn [c] (- (double (or (:cos-avoid c) 0.0))
                                          (double (or (:cos-good c) 0.0))))
                               stamped))))))

(defn- log-penalty-pass!
  "Emit the ADR-0027 pass report. The headline firing counts are FLAT so they
   are greppable in a log line without parsing the nested distributions."
  [stamped config]
  (let [report (penalty-pass-report stamped config)]
    (u/log :ai.obney.orc.ontology.core.domain-penalty/domain-penalty-pass
            :candidate-count (:candidate-count report)
            :applied-positive-signal (:applied-positive-signal report)
            :gate-form (:gate-form report)
            :fired-with-content (get-in report [:variants :content+good-when :fired])
            :fired-sans-content (get-in report [:variants :good-when :fired])
            :report report)))

(defn penalize-candidates
  "EL-5 penalty PASS (EL-5.1: ONE bridge call for the whole batch, not N): given
   enriched candidates (each carrying :fitness-score + :avoid-when/:content/
   :strengths from EL-2) and the task string, compute the contrastive domain
   penalty per candidate, multiply it into :fitness-score, stamp :domain-penalty +
   :cos-avoid + :cos-good for observability, and RE-SORT by the new fitness
   (descending). Candidates without a usable fitness (:colbert-fallback, nil) keep
   nil fitness and sort last. Output map shape is otherwise unchanged (the contract
   {:document-id :reasoning :fitness-score} is preserved; the extra keys are
   additive observability).

   EL-5.1 SCORING: the SELECTED backend's BATCH scorer factory (make-batch-scorer)
   is invoked ONCE per pass — it makes EXACTLY ONE colbert/rerank (or one
   embed-task + one embed-distinct-guards) call over the DISTINCT guard set across
   ALL candidates and returns a pure per-candidate lookup. This is RESULTS-NEUTRAL
   vs the old per-candidate (N-call) path (per-doc MaxSim/cosine is set-independent
   under the shared task query) and collapses M bridge round-trips into 1.

   FAIL OPEN: if the single batched call throws (e.g. the ColBERT bridge is
   unavailable), EVERY candidate scores {0,0} -> penalty 0, the LLM ordering is
   left UNTOUCHED (never a fabricated penalty), matching the reranker's own
   try/catch fallback.

   Pure given the backend — the 5-arity injects a PER-CANDIDATE scorer
   ((scorer candidate task) -> {:cos-avoid :cos-good}) for full determinism (no
   ColBERT/LLM/DJL); used by the unit re-sort/fail-open tests + the proto. The
   4-arity is the production hot path (batch)."
  ([ctx candidates task]
   (penalize-candidates ctx candidates task default-penalty-config))
  ([ctx candidates task config]
   (let [per-candidate-cosines
         (try
           (let [batch-factory (make-batch-scorer ctx config)
                 lookup (batch-factory candidates task)]
             (mapv (fn [c] (or (lookup c) {:cos-avoid 0.0 :cos-good 0.0})) candidates))
           (catch Throwable t
             (u/log :ai.obney.orc.ontology.core.domain-penalty/batch-scorer-failed :error (.getMessage t)
                     :candidate-count (count candidates))
             (mapv (constantly {:cos-avoid 0.0 :cos-good 0.0}) candidates)))
         stamped (stamp-all candidates per-candidate-cosines config)]
     (log-penalty-pass! stamped config)
     (vec (sort-by (fn [c] (or (:fitness-score c) -1.0)) > stamped))))
  ([_ctx candidates task config scorer]
   (let [cosines (mapv (fn [c] (scorer-cosines c task scorer)) candidates)
         stamped (stamp-all candidates cosines config)]
     (log-penalty-pass! stamped config)
     (vec (sort-by (fn [c] (or (:fitness-score c) -1.0)) > stamped)))))

;; --- Evolutionary builder ---

(def default-config
  "Default configuration for evolutionary building"
  {:base-uri "http://ontology.local/"
   :similarity-threshold 0.85
   :emit-owl-sameAs? true
   :use-type-blocking? true
   :include-metadata? true
   :enable-colbert? true
   :colbert-index-name nil
   :colbert-fields nil
   :auto-detect-colbert-fields? true
   :enable-embeddings? true
   :embedding-fields nil
   :auto-detect-embedding-fields? true})

(defn normalize-ontology-id
  "Normalize ontology-id to UUID for Grain schema compliance.

   Grain's tag schema requires [:tuple :keyword :uuid], so ontology-ids
   must be UUIDs. This function:
   - Passes through existing UUIDs unchanged
   - Converts strings to deterministic UUIDs using nameUUIDFromBytes

   Deterministic conversion means the same string always produces the
   same UUID, preserving query semantics."
  [id]
  (if (uuid? id)
    id
    (try (java.util.UUID/fromString (str id))
         (catch IllegalArgumentException _
           (java.util.UUID/nameUUIDFromBytes (.getBytes (str id) "UTF-8"))))))

(defn load-source-content
  "Load content from a source specification.
   For SQL databases, returns the path (no content loading needed).
   Returns {:content string :file-size int :db-path string (for SQL)}"
  [{:keys [path content type]}]
  (cond
    (or (= type "sql") (= type "sqlite") (= type "db")
        (and path (str/ends-with? path ".db")))
    (if (and path (.exists (java.io.File. ^String path)))
      {:content path
       :db-path path
       :file-size (.length (java.io.File. ^String path))}
      (throw (ex-info "Database file not found" {:path path})))

    content
    {:content content
     :file-size (count (.getBytes ^String content "UTF-8"))}

    (and path (.exists (java.io.File. ^String path)))
    {:content (slurp path)
     :file-size (.length (java.io.File. ^String path))}

    :else
    (throw (ex-info "Source not found" {:path path}))))

(defn parse-csv-string
  "Parse a CSV string into a vector of maps with keyword keys.
   First row is treated as headers."
  [csv-string]
  (with-open [reader (java.io.StringReader. csv-string)]
    (let [[header & rows] (csv/read-csv reader)
          header-keys (mapv keyword header)]
      (mapv #(zipmap header-keys %) rows))))

(def ^:private orc-sheet-ids
  "Atom to cache built ORC sheet IDs by infrastructure, tenant, source type,
  and pinned model. A process-global type-only cache leaks sheet IDs across
  tenants and clean replay contexts."
  (atom {}))

(defn- sheet-cache-key [ctx source-type model]
  [(System/identityHashCode (:event-store ctx))
   (:tenant-id ctx)
   source-type
   model])

(defn- evolutionary-builder-ensure-csv-sheet!
  "Ensure the CSV ontology ORC sheet is built, return its ID."
  [ctx model]
  (let [cache-key (sheet-cache-key ctx :csv model)]
    (or (get @orc-sheet-ids cache-key)
      (let [
            csv-ns #'build-csv-ontology-pipeline!
            sheet-id (csv-ns ctx model)]
        (swap! orc-sheet-ids assoc cache-key sheet-id)
        sheet-id))))

(defn- evolutionary-builder-ensure-text-sheet!
  "Ensure the taxonomy ORC sheet is built, return its ID."
  [ctx model]
  (let [cache-key (sheet-cache-key ctx :text model)]
    (or (get @orc-sheet-ids cache-key)
      (let [
            text-ns #'build-taxonomy-pipeline!
            sheet-id (text-ns ctx model)]
        (swap! orc-sheet-ids assoc cache-key sheet-id)
        sheet-id))))

(defn- evolutionary-builder-ensure-sql-sheet!
  "Ensure the SQL ontology ORC sheet is built, return its ID."
  [ctx model]
  (let [cache-key (sheet-cache-key ctx :sql model)]
    (or (get @orc-sheet-ids cache-key)
      (let [
            sql-ns #'build-sql-ontology-pipeline!
            sheet-id (sql-ns ctx model)]
        (swap! orc-sheet-ids assoc cache-key sheet-id)
        sheet-id))))

(defn- evolutionary-builder-ensure-json-sheet!
  "Ensure the JSON ontology ORC sheet is built, return its ID."
  [ctx model]
  (let [cache-key (sheet-cache-key ctx :json model)]
    (or (get @orc-sheet-ids cache-key)
      (let [
            json-ns #'build-json-ontology-pipeline!
            sheet-id (json-ns ctx model)]
        (swap! orc-sheet-ids assoc cache-key sheet-id)
        sheet-id))))

(defn extract-concepts-from-csv
  "Extract concepts from CSV source using csv_ontology ORC sheet.

   Args:
     ctx - Context with :event-store
     source-id - UUID of the source
     csv-data - Vector of maps (parsed CSV) or CSV string
     config - Configuration with :base-uri, :entity-column, :entity-type

   Returns:
     {:concepts [...] :relationships [...] :tbox {...} :owl-output string}"
  [ctx source-id csv-data config]
  (let [
        parsed-data (if (string? csv-data)
                      (parse-csv-string csv-data)
                      csv-data)

        entity-column (or (:entity-column config)
                          (when (seq parsed-data)
                            (name (first (keys (first parsed-data)))))
                          "name")

        sheet-id (evolutionary-builder-ensure-csv-sheet! ctx (:model config))

        run-fn #'run-csv-to-ontology

        result (run-fn ctx sheet-id
                 {:csv-data parsed-data
                  :entity-column entity-column
                  :entity-type (or (:entity-type config) "Entity")
                  :base-uri (or (:base-uri config) "http://ontology.local/")})]

    (if (= :success (:status result))
      (let [entities (or (:entities result) [])
            relationships (or (:relationships result) [])
            discovered-rels (or (:discovered-relationships result) [])
            tbox (:tbox result)
            abox (:abox result)]
        {:concepts
         (if (seq abox)
           (mapv (fn [individual]
                   {:uri (:uri individual)
                    :label (:label individual)
                    :definition (str (or (get-in individual [:properties "purpose"]) ""))
                    :entity-type (or (:entity-type config) "Entity")
                    :source-id source-id
                    :confidence 1.0})
                 abox)
           (mapv (fn [e]
                   (let [name (or (get e "name") (get e :name) "Unknown")]
                     {:uri (str (:base-uri config) (str/replace name #"\s+" "_"))
                      :label name
                      :definition (or (get e "description") (get e :description) "")
                      :entity-type (or (:entity-type config) "Entity")
                      :source-id source-id
                      :confidence 1.0}))
                 entities))

         :relationships
         (into
           (mapv (fn [r]
                   {:subject (or (get r "subject") (get r :subject) "")
                    :predicate (or (get r "predicate") (get r :predicate) "relatedTo")
                    :object (or (get r "object") (get r :object) "")})
                 relationships)
           (mapv (fn [r]
                   {:subject (or (get r "subject") (get r :subject) "")
                    :predicate (or (get r "predicate") (get r :predicate) "relatedTo")
                    :object (or (get r "object") (get r :object) "")
                    :confidence (or (get r "confidence") (get r :confidence) 0.8)})
                 discovered-rels))

         :tbox {:classes (or (:classes tbox) [])
                :object-properties (or (:object-properties tbox) [])
                :datatype-properties (or (:datatype-properties tbox) [])}

         :abox (or abox [])

         :owl-output (:owl-output result)
         :trace-id (:trace-id result)})

      (throw (ex-info (str "ORC CSV extraction failed: "
                           (or (:error result) "unknown execution failure"))
                      {:error (:error result)
                       :trace-id (:trace-id result)
                       :source-id source-id
                       :status (:status result)})))))

(defn extract-concepts-from-text
  "Extract concepts from text source using ontology_exploration ORC sheet.

   Args:
     ctx - Context with :event-store
     source-id - UUID of the source
     text-content - Text string to extract concepts from
     config - Configuration with :base-uri, :domain, :existing-concepts

   Returns:
     {:concepts [...] :relationships [...] :skos-output string}"
  [ctx source-id text-content config]
  (let [
        sheet-id (evolutionary-builder-ensure-text-sheet! ctx (:model config))

        run-fn #'run-taxonomy-pipeline

        result (run-fn ctx sheet-id
                 {:source-text text-content
                  :domain (or (:domain config) "general")
                  :existing-concepts (or (:existing-concepts config) [])})]

    (if (= :success (:status result))
      (let [concepts (or (:concepts result) [])
            relationships (or (:relationships result) [])
            causal-rels (get-in result [:validation :causal-relations] [])]
        {:concepts
         (mapv (fn [c]
                 (let [label (or (get c "label") (get c :label) "Unknown")
                       raw-alt-labels (or (get c "alt_labels") (get c :alt_labels))
                       alt-labels (cond
                                    (nil? raw-alt-labels) []
                                    (string? raw-alt-labels) (if (str/blank? raw-alt-labels)
                                                               []
                                                               (vec (map str/trim (str/split raw-alt-labels #","))))
                                    (sequential? raw-alt-labels) (vec raw-alt-labels)
                                    :else [])]
                   {:uri (str (:base-uri config) (str/replace label #"\s+" "_"))
                    :label label
                    :definition (or (get c "definition") (get c :definition) "")
                    :entity-type (or (get c "entity_type") (get c :entity_type) "Concept")
                    :alt-labels alt-labels
                    :source-id source-id
                    :confidence (or (get c "confidence") (get c :confidence) 0.8)}))
               concepts)

         :relationships
         (into
           (mapv (fn [r]
                   {:subject (str (:base-uri config)
                                  (str/replace (or (get r "narrower") (get r :narrower) "") #"\s+" "_"))
                    :predicate "skos:broader"
                    :object (str (:base-uri config)
                                 (str/replace (or (get r "broader") (get r :broader) "") #"\s+" "_"))})
                 relationships)
           (mapv (fn [rp]
                   {:subject (str (:base-uri config)
                                  (str/replace (or (get rp "concept1") (get rp :concept1) "") #"\s+" "_"))
                    :predicate "skos:related"
                    :object (str (:base-uri config)
                                 (str/replace (or (get rp "concept2") (get rp :concept2) "") #"\s+" "_"))
                    :reason (or (get rp "reason") (get rp :reason) "")})
                 (or (:related-pairs result) [])))

         :top-concepts (:top-concepts result)
         :skos-output (:skos-output result)
         :trace-id (:trace-id result)})

      (throw (ex-info (str "ORC text extraction failed: "
                           (or (:error result) "unknown execution failure"))
                      {:error (:error result)
                       :trace-id (:trace-id result)
                       :source-id source-id
                       :status (:status result)})))))

(defn extract-concepts-from-sql
  "Extract concepts from SQL database using sql_ontology ORC sheet.

   Args:
     ctx - Context with :event-store
     source-id - UUID of the source
     db-path - Path to SQLite database file
     config - Configuration with :base-uri, :max-tables, :max-instances

   Returns:
     {:concepts [...] :relationships [...] :tbox {...} :abox [...] :owl-output string}"
  [ctx source-id db-path config]
  (let [
        sheet-id (evolutionary-builder-ensure-sql-sheet! ctx (:model config))

        run-fn #'run-sql-to-ontology

        result (run-fn ctx sheet-id
                 {:db-path db-path
                  :base-uri (or (:base-uri config) "http://db.local/")
                  :max-tables (or (:max-tables config) 50)
                  :max-instances (or (:max-instances config) 10)})]

    (if (= :success (:status result))
      (let [table-entities (or (:table-entities result) {})
            tbox (:tbox result)
            abox (or (:abox result) [])]
        {:concepts
         (vec
          (for [[table-name entity-info] table-entities]
            {:uri (str (:base-uri config)
                       (or (:class-name entity-info) table-name))
             :label (or (:class-name entity-info) table-name)
             :definition (or (:definition entity-info) "")
             :entity-type (or (:entity-type entity-info) "DatabaseEntity")
             :source-id source-id
             :source-table table-name
             :confidence 1.0}))

         :relationships
         (vec
          (for [prop (get tbox :object-properties [])]
            {:subject (:domain prop)
             :predicate (:label prop)
             :object (:range prop)
             :source "foreign-key"}))

         :tbox tbox
         :abox abox
         :domain (:domain result)
         :domain-description (:domain-description result)
         :owl-output (:owl-output result)
         :trace-id (:trace-id result)})

      (throw (ex-info "ORC SQL extraction failed"
                      {:error (:error result)
                       :source-id source-id
                       :db-path db-path
                       :status (:status result)})))))

(defn extract-concepts-from-json
  "Extract concepts from JSON source using json_ontology ORC sheet.

   Args:
     ctx - Context with :event-store
     source-id - UUID of the source
     json-data - Parsed JSON data (map or vector) or JSON string
     config - Configuration with :base-uri, :domain

   Returns:
     {:concepts [...] :relationships [...] :tbox {...} :abox [...] :owl-output string}"
  [ctx source-id json-data config]
  (let [
        parsed-data (if (string? json-data)
                      (json/read-str json-data :key-fn keyword)
                      json-data)

        sheet-id (evolutionary-builder-ensure-json-sheet! ctx (:model config))

        run-fn #'run-json-to-ontology

        result (run-fn ctx sheet-id
                 {:json-data parsed-data
                  :base-uri (or (:base-uri config) "http://json.local/")
                  :domain (:domain config)})]

    (if (= :success (:status result))
      (let [concepts (or (:concepts result) [])
            relationships (or (:relationships result) [])
            tbox (:tbox result)
            abox (or (:abox result) [])]
        {:concepts
         (mapv (fn [c]
                 {:uri (or (:uri c) (str (:base-uri config) (str/replace (or (:label c) "Unknown") #"\s+" "_")))
                  :label (or (:label c) "Unknown")
                  :definition (or (:definition c) "")
                  :entity-type (or (:entity-type c) "Entity")
                  :source-id source-id
                  :confidence (or (:confidence c) 0.8)})
               concepts)

         :relationships
         (mapv (fn [r]
                 {:subject (or (:subject r) "")
                  :predicate (or (:predicate r) "relatedTo")
                  :object (or (:object r) "")
                  :confidence (or (:confidence r) 0.8)})
               relationships)

         :tbox {:classes (or (:classes tbox) [])
                :object-properties (or (:object-properties tbox) [])
                :datatype-properties (or (:datatype-properties tbox) [])}

         :abox abox
         :owl-output (:owl-output result)
         :trace-id (:trace-id result)})

      (throw (ex-info (str "ORC JSON extraction failed: "
                           (or (:error result) "unknown execution failure"))
                      {:error (:error result)
                       :trace-id (:trace-id result)
                       :source-id source-id
                       :status (:status result)})))))

(defn extract-from-source
  "Extract concepts and relationships from a source.
   Dispatches to the appropriate ORC extraction pipeline based on source type.

   Args:
     ctx - Context with :event-store (needed for ORC sheet execution)
     source-id - UUID of the source
     source-type - Type string: 'csv', 'text', 'json', 'sql', 'sqlite', 'db', 'rdf'
     content - Content to extract from (string, parsed data, or db path for SQL)
     config - Configuration options (may include :db-path for SQL sources)

   Returns:
     {:concepts [...] :relationships [...]}"
  [ctx source-id source-type content config]
  (case source-type
    "csv" (extract-concepts-from-csv ctx source-id content config)
    "text" (extract-concepts-from-text ctx source-id content config)

    ("sql" "sqlite" "db")
    (let [
          db-path (or (when (and (string? content)
                                 (str/ends-with? content ".db"))
                        content)
                      (:db-path config)
                      (:path config))]
      (if db-path
        (extract-concepts-from-sql ctx source-id db-path config)
        (throw (ex-info "SQL extraction requires :db-path or .db file path"
                        {:source-id source-id :source-type source-type}))))

    "json" (extract-concepts-from-json ctx source-id content config)
    "rdf" (rdf/extract-concepts-from-rdf source-id content config)

    (cond
      (and (string? content) (str/ends-with? content ".db"))
      (extract-concepts-from-sql ctx source-id content config)

      (and (string? content) (str/includes? content ",") (str/includes? content "\n"))
      (extract-concepts-from-csv ctx source-id content config)

      :else
      (extract-concepts-from-text ctx source-id content config))))

;; --- Seeds ---

(defn read-seeds
  "Slurp a seed resource from the classpath, parse as EDN, return the vector
   of seed maps. Throws if the resource is missing — that's a build-time
   error, not a runtime fallback."
  [resource-path]
  (if-let [r (io/resource resource-path)]
    (edn/read-string (slurp r))
    (throw (ex-info (str "Seed resource not found on classpath: " resource-path)
                    {:resource resource-path}))))

(defn baseline-seeds
  "Pure-data query: return the loaded seed catalog as a map of
   {:node-types <vec> :tree-classes <vec> :behavioral-subtrees <vec>}.

   Useful for consumers that want to inspect the corpus without dispatching
   commands (e.g., tests that walk seed bodies for invariants, or tooling
   that diffs the corpus against an app-specific extension)."
  []
  {:node-types (read-seeds "seeds/node-types.edn")
   :tree-classes (read-seeds "seeds/tree-classes.edn")
   :behavioral-subtrees (read-seeds "seeds/behavioral-subtrees.edn")
   :behavioral-subtree-children (read-seeds "seeds/behavioral-subtree-children.edn")})

;; --- Embedding ---

(def default-dimensions
  "Dimension count for default model."
  384)

(defn close-embedding-model!
  "Close and remove a specific embedding model.

   Args:
     model-id: Model identifier to close"
  [model-id]
  (locking embedding-models
    (when-let [model (get @embedding-models model-id)]
      (println "[DJL] Closing embedding model:" model-id)
      (try
        (.close model)
        (catch Exception e
          (println "[DJL] Warning closing model:" (.getMessage e))))
      (swap! embedding-models dissoc model-id)
      (println "[DJL] Model closed:" model-id))))

(defn close-all-embedding-models!
  "Close all loaded embedding models. Useful for REPL cleanup."
  []
  (locking embedding-models
    (doseq [[model-id _] @embedding-models]
      (close-embedding-model! model-id))
    (reset! embedding-models {})
    (println "[DJL] All embedding models closed.")))

(defn normalize-vector
  "Normalize a vector to unit length."
  [v]
  (let [mag (magnitude v)]
    (if (zero? mag)
      v
      (mapv #(/ % mag) v))))

(defn tree-profile->embedding-text
  "Convert a tree profile to text suitable for embedding.

   Summarizes strengths, weaknesses, and problem types.

   Args:
     profile: Tree profile map

   Returns:
     Summary text for embedding"
  [profile]
  (let [strengths (->> (:strengths profile)
                       (map :pattern)
                       (str/join ", "))
        weaknesses (->> (:weaknesses profile)
                        (map :failure)
                        (str/join ", "))
        problems (->> (:solves profile)
                      (map :problem-uri)
                      (str/join ", "))]
    (str/join ". "
              (remove str/blank?
                      [(when (seq strengths)
                         (str "Strengths: " strengths))
                       (when (seq weaknesses)
                         (str "Weaknesses: " weaknesses))
                       (when (seq problems)
                         (str "Solves: " problems))]))))

(defn evaluation-feedback->embedding-text
  "Convert evaluation feedback to text for embedding.

   Args:
     feedback: Feedback string
     dimension: Evaluation dimension name

   Returns:
     Formatted text for embedding"
  [feedback dimension]
  (str dimension " feedback: " feedback))

(def ^:private semantic-field-names
  "Field names that likely contain semantic text worth embedding."
  #{:description :feedback :label :name :title :summary
    :content :text :message :explanation :reasoning
    :instructions :notes :comments})

(def ^:private vector-semantic-names
  "Vector field names that likely contain semantic content."
  #{:indicators :triggers :keywords :tags :reasons
    :examples :hints :suggestions})

(defn- string-schema?
  "Check if a schema represents a string type."
  [schema]
  (when schema
    (let [type-name (m/type schema)]
      (or (= type-name :string)
          (= type-name 'string)
          (= schema :string)))))

(defn- string-vector-schema?
  "Check if a schema represents a vector of strings."
  [schema]
  (when schema
    (let [type-name (m/type schema)]
      (and (= type-name :vector)
           (let [children (m/children schema)]
             (and (= 1 (count children))
                  (string-schema? (first children))))))))

(defn- has-description-property?
  "Check if schema has a :description property in metadata."
  [schema]
  (when schema
    (let [props (m/properties schema)]
      (contains? props :description))))

(defn- explain-embeddability
  "Explain why a field is embeddable."
  [field-name schema]
  (cond
    (contains? semantic-field-names field-name)
    "Semantic field name indicates natural language content"

    (contains? vector-semantic-names field-name)
    "Vector field with semantic content items"

    (has-description-property? schema)
    "Field has description metadata"

    (string-schema? schema)
    "String field that may contain text"

    (string-vector-schema? schema)
    "Vector of strings"

    :else
    "Unknown"))

(defn detect-embeddable-fields
  "Analyze a Malli schema to find fields suitable for embedding.

   Heuristics:
   - :string fields with semantic names (:description, :feedback, :label)
   - [:vector :string] fields (indicators, triggers)
   - Fields with {:description ...} metadata

   Args:
     schema: Malli schema to analyze

   Returns:
     {:embeddable-fields [:description :feedback ...]
      :reasoning {'description' 'reason...'}}

   Returns nil if schema is not a map schema."
  [schema]
  (when (and schema (= :map (m/type schema)))
    (let [entries (m/entries schema)
          embeddable (filter
                       (fn [[field-name field-schema]]
                         (or (contains? semantic-field-names field-name)
                             (contains? vector-semantic-names field-name)
                             (has-description-property? field-schema)
                             (and (string-schema? field-schema)
                                  (not (contains? #{:id :uuid :uri :url :email :phone
                                                    :created-at :updated-at :timestamp}
                                                  field-name)))))
                       entries)]
      {:embeddable-fields (mapv first embeddable)
       :reasoning (into {}
                        (map (fn [[k v]] [k (explain-embeddability k v)])
                             embeddable))})))

(defn analyze-concept-schema
  "Analyze concept schemas to determine embedding strategy.

   Returns fields to embed for different concept types."
  []
  {:failure {:fields #{:label :description :indicators}
             :strategy "Combine label, description, and indicators for failure detection"}
   :success {:fields #{:label :description}
             :strategy "Combine label and description for pattern matching"}
   :problem {:fields #{:label :description}
             :strategy "Combine label and description for problem similarity"}
   :tree-profile {:fields #{:strengths :weaknesses :solves}
                  :strategy "Summarize profile into text then embed"}
   :evaluation {:fields #{:feedback :dimension}
                :strategy "Embed feedback with dimension context"}})

(defn find-most-similar
  "Find the most similar items from a collection based on embedding similarity.

   Args:
     query-embedding: Query vector
     items: Collection of maps with :embedding key
     opts: {:limit 10, :min-similarity 0.0}

   Returns:
     Vector of items with :similarity score, sorted descending"
  [query-embedding items & {:keys [limit min-similarity]
                            :or {limit 10 min-similarity 0.0}}]
  (when (and query-embedding (seq items))
    (->> items
         (filter :embedding)
         (map (fn [item]
                (let [sim (embedding-cosine-similarity query-embedding (:embedding item))]
                  (assoc item :similarity sim))))
         (filter #(>= (:similarity %) min-similarity))
         (sort-by :similarity >)
         (take limit)
         vec)))

(defn search-concepts-by-embedding
  "Search concepts by semantic similarity.

   Args:
     query-embedding: Query vector
     concept-embeddings: Map of uri -> {:embedding [...]}
     opts: {:limit 10, :min-similarity 0.5}

   Returns:
     Vector of {:uri :similarity} sorted by similarity"
  [query-embedding concept-embeddings & {:keys [limit min-similarity]
                                          :or {limit 10 min-similarity 0.5}}]
  (when query-embedding
    (->> concept-embeddings
         (map (fn [[uri data]]
                (when (:embedding data)
                  {:uri uri
                   :similarity (embedding-cosine-similarity query-embedding (:embedding data))})))
         (remove nil?)
         (filter #(>= (:similarity %) min-similarity))
         (sort-by :similarity >)
         (take limit)
         vec)))

(defn concept->embedding-text-multi-field
  "Convert a concept to text using multiple fields.

   Unlike concept->embedding-text which only supports a fixed set of fields,
   this function handles any field detected by field analysis.

   Args:
     concept: Concept map
     fields: Vector or set of field keywords to include

   Returns:
     Combined text string for embedding"
  [concept fields]
  (let [fields-set (set fields)
        parts (->> fields-set
                   (map (fn [field-key]
                          (let [value (get concept field-key)]
                            (cond
                              (string? value) value
                              (sequential? value) (str/join ", " value)
                              :else nil))))
                   (remove str/blank?))]
    (str/join ". " parts)))

(defn embed-concepts-batch!
  "Embed all concepts for an ontology (used by evolutionary builder).

   Auto-detects fields if not provided, using same logic as ColBERT.
   Returns results for event emission by the caller.

   This function is designed to mirror the ColBERT indexing pattern in
   evolutionary_builder.clj, enabling automatic embedding during ontology builds.

   Args:
     concepts: Vector of concept maps to embed
     opts: {:embedding-fields nil     ;; Auto-detect if nil
            :auto-detect? true        ;; Use field analyzer
            :model-id nil             ;; Use default MiniLM
            :batch-size 32            ;; Process in batches
            :ctx nil}                 ;; Context for LLM field detection

   Returns:
     {:embedded-count N
      :fields-used [:label :description ...]
      :model-id \"sentence-transformers/all-MiniLM-L6-v2\"
      :embeddings [{:uri \"...\" :embedding [...] :text-embedded \"...\"}]
      :skipped-count N}"
  [concepts {:keys [embedding-fields auto-detect? model-id batch-size ctx]
             :or {auto-detect? true
                  model-id default-model-id
                  batch-size 32}}]
  (if (empty? concepts)
    (do
      (u/log :ai.obney.orc.ontology.core.embedding/embed-concepts-batch-empty)
      {:embedded-count 0
       :fields-used []
       :model-id model-id
       :embeddings []
       :skipped-count 0})

    (let [
        fields (cond
                 (seq embedding-fields)
                 (vec embedding-fields)

                 auto-detect?
                 (let [detected (if ctx
                                  (detect-embedding-fields ctx concepts)
                                  (detect-embedding-fields concepts))]
                   (:embedding-fields detected))

                 :else
                 [:label :description])

        _ (u/log :ai.obney.orc.ontology.core.embedding/embed-concepts-batch-start
                  :concept-count (count concepts)
                  :fields-to-use fields
                  :model-id model-id)

        texts-with-uris (->> concepts
                             (map (fn [concept]
                                    (let [text (concept->embedding-text-multi-field concept fields)]
                                      {:uri (or (:uri concept) (str (random-uuid)))
                                       :text text})))
                             (filter #(not (str/blank? (:text %)))))

        start-time (System/currentTimeMillis)
        embeddings-result
        (loop [remaining texts-with-uris
               results []]
          (if (empty? remaining)
            results
            (let [batch (take batch-size remaining)
                  rest-items (drop batch-size remaining)
                  texts (mapv :text batch)
                  embedded (embedding-embed-texts-batch texts {:model-id model-id})
                  batch-results (mapv (fn [item emb]
                                        {:uri (:uri item)
                                         :embedding emb
                                         :text-embedded (:text item)})
                                      batch
                                      embedded)]
              (recur rest-items (into results batch-results)))))

        duration-ms (- (System/currentTimeMillis) start-time)
        successful (filter :embedding embeddings-result)
        skipped (- (count concepts) (count texts-with-uris))]

      (u/log :ai.obney.orc.ontology.core.embedding/embed-concepts-batch-complete
              :embedded-count (count successful)
              :skipped-count skipped
              :duration-ms duration-ms)

      {:embedded-count (count successful)
       :fields-used fields
       :model-id model-id
       :embeddings successful
       :skipped-count skipped
       :duration-ms duration-ms})))

;; --- Classifier ---

(def failure-severity-weights
  "Base severity weights for failure categories.
   Grounding and Reasoning failures are typically more severe."
  {"failure:Grounding" :high
   "failure:InstructionFollowing" :medium
   "failure:Reasoning" :high
   "failure:Completeness" :medium
   "failure:Hallucination" :critical
   "failure:Contradiction" :high
   "failure:CircularReasoning" :high
   "failure:TruncatedOutput" :low})

(defn estimate-severity
  "Estimate severity level for a classified failure.
   Returns :low, :medium, :high, or :critical"
  [{:keys [uri confidence dimension-score]}]
  (let [base-severity (get failure-severity-weights uri
                           (get failure-severity-weights
                                (get classifier-dimension->failure-uri (:dimension uri))
                                :medium))]
    (cond
      (and (= base-severity :critical) (>= confidence 0.8)) :critical
      (and (#{:high :critical} base-severity) (>= confidence 0.7)) :high
      (and (>= confidence 0.5) (<= dimension-score 0.3)) :high
      (>= confidence 0.3) :medium
      :else :low)))

(defn extract-triggers
  "Extract trigger phrases from failure evidence.
   Triggers are short phrases that describe what caused the failure."
  [failures]
  (->> failures
       (mapcat (fn [{:keys [evidence]}]
                 (when (seq evidence)
                   (let [sentences (str/split evidence #"[.!?]")]
                     (->> sentences
                          (filter #(> (count %) 10))
                          (filter #(< (count %) 200))
                          (map str/trim)
                          (take 3))))))
       distinct
       vec))

;; --- Evidence projection ---

(defn- namespaced-key?
  "The discriminator the emitter uses: blackboard keys are always SIMPLE
   keywords (see `declare-key`); the engine's map-each correlation keys are
   namespaced. So namespaced => correlation metadata that must survive;
   simple => a read value that is durable elsewhere and becomes shape here."
  [k]
  (and (keyword? k) (some? (namespace k))))

(defn- retain
  "assoc that never overwrites. An observation emitted AFTER the producer-side
   fix already carries `:read-keys`/`:input-profile`/`:write-keys`/
   `:write-profile` computed at the source; the source's own account wins over
   anything derived here."
  [m k v]
  (if (contains? m k) m (assoc m k v)))

(defn project-observation
  "Reduce one observation's value payloads to shape, keeping every field the
   evidence identity depends on.

   `:inputs` keeps only its namespaced (execution-context) keys; the read
   VALUES become `:read-keys` + `:input-profile`. `:writes` becomes
   `:write-keys` + `:write-profile`. Everything else — including the
   `[sheet-id tick-id]` occurrence pair CC-4's guard and CC-7's support counts
   resolve against — is passed through untouched.

   Pure, total, and idempotent: a non-map, or an observation with no value
   payload (every `:tree-class` observation, for instance), comes back
   unchanged."
  [observation]
  (if-not (map? observation)
    observation
    (let [inputs (when (map? (:inputs observation)) (:inputs observation))
          writes (when (map? (:writes observation)) (:writes observation))
          exec-context (into {} (filter (fn [[k _]] (namespaced-key? k))) inputs)
          read-values (into {} (remove (fn [[k _]] (namespaced-key? k))) inputs)]
      (cond-> observation
        (some? inputs)     (dissoc :inputs)
        (seq exec-context) (assoc :inputs exec-context)
        (seq read-values)  (-> (retain :read-keys (vec (keys read-values)))
                               (retain :input-profile (orc/profile-values read-values)))
        (some? writes)     (dissoc :writes)
        (seq writes)       (-> (retain :write-keys (vec (keys writes)))
                               (retain :write-profile (orc/profile-values writes)))))))

;; --- Consolidator ---

(def ^:private evidence-prompt-overhead-chars
  "CC-21 §1: the measured non-window share of the rendered reflection prompt
   (instruction + claim-set scaffolding). prompt chars ~ window EDN chars +
   THIS, within `evidence-predictor-envelope` on all 10 provider anchors."
  9000)

(def ^:private evidence-predictor-envelope
  "CC-21 §1: the banked relative error of the overhead predictor —
   |measured − predicted| / predicted ≤ 1.6% across all 10 anchors (the
   E-w20 anchor sits at 1.5995%, so the denominator is the PREDICTION;
   dividing by the measurement instead puts E-w20 at 1.6255% and falsifies
   the banked '±1.6%, all 10')."
  0.016)

(def ^:private banked-reflection-instruction-chars
  "The length of `reflection-instruction` at the time the CC-21 envelope was
   banked against production prompts. Pinned EXACTLY by test: any edit to the
   instruction changes the +9,000-char overhead, so the envelope must be
   re-measured and this constant re-banked — never silently absorbed."
  5251)

(def ^:private banked-claim-reflection-instruction-chars
  "As `banked-reflection-instruction-chars`, for the claim path's
   `claim-reflection-instruction`."
  3920)

(def ^:private evidence-density-chars-per-token
  "CC-21's banked per-target-type density constants (chars of rendered prompt
   per provider token). Each measured entry is the FLOOR (2 decimals, rounded
   DOWN) of the minimum provider-measured density for that type, so predicted
   tokens are never underestimated by the constant:

     :node-type     1.99 — the E anchor (:repl-researcher), 1,145,212 chars /
                    574,914 provider tokens. The densest payload measured.
     :node-instance 3.17 — the A/B/C anchors (3.175–3.970); the floor is
                    A-w500. CAVEAT, stated not hidden: a :node-instance of a
                    dense node-type (the repl-researcher twin carries the
                    byte-identical 178-event payload as E) tokenises at ~2.0,
                    which this constant under-predicts by ~1.6x. Post-CC-21b
                    projection that window is ~134 KB — bounded far below
                    both provider ceilings — and the exclusion events (PR-2)
                    make any resulting truncation observable.
     :tree-class    3.47 — NO provider anchor exists; this is CC-21 §3's
                    fitted estimate for the join-path window (1,183,844 B →
                    340,207 est tokens, p_cls model, ≤10.8% residual).
                    Weaker provenance than the anchors, flagged as such.
     :tree-fingerprint 1.99 — unmeasurable today (CC-21 adjacent defect B:
                    0 events carry the target), so the global measured floor.

   Recovering headroom for an under-predicted type requires NEW anchors,
   not a bigger constant."
  {:node-type        1.99
   :node-instance    3.17
   :tree-fingerprint 1.99
   :tree-class       3.47})

(def ^:private evidence-density-fallback-chars-per-token
  "For a target-type outside the table: the global measured floor (the E
   anchor), the conservative direction — more predicted tokens, never fewer."
  1.99)

(defn evidence-density-constants
  "The CC-21 density constants for one target-type, in the shape
   `select-evidence-window` consumes: {:chars-per-token :overhead-chars}.
   Public for test access."
  [target-type]
  {:chars-per-token (get evidence-density-chars-per-token target-type
                         evidence-density-fallback-chars-per-token)
   :overhead-chars evidence-prompt-overhead-chars})

(defn select-evidence-window
  "PURE selection of the reflection's evidence window (spec invariant
   BoundedReflectionEvidence, ADR 0030).

   `events` is the target's observation window in event-store order (most
   recent LAST — the order `gather-recent-events` produces). The selection
   walks NEWEST-first and keeps the longest contiguous newest suffix whose
   PREDICTED prompt tokens fit `budget-tokens`, where

     predicted chars  = overhead-chars + window EDN chars
                        (window EDN chars = (count (pr-str selected-vector)),
                         accounted incrementally: Σ per-event pr-str chars
                         + n separators/brackets + 1)
     predicted tokens = ⌈predicted chars / chars-per-token⌉

   — CC-21's banked predictor, per-type density supplied by the caller via
   `evidence-density-constants`. The walk stops at the FIRST event that
   overflows (a contiguous suffix, so recency order is never violated by
   skipping a large event to admit an older small one), which also makes the
   split deterministic: a pure function of (events, constants, budget).

   Returns {:selected :excluded :predicted-prompt-chars
   :predicted-prompt-tokens} — both vectors chronological (oldest first);
   `:excluded` is the oldest prefix squeezed out by the budget. PR-2 makes
   every excluded event OBSERVABLE as a durable exclusion event; this slice
   returns them and logs, and MUST NOT emit.

   Degenerate case, deliberate: a budget too small for even the newest event
   (or the bare overhead) selects NOTHING — the empty window then walks into
   CC-4's evidence guard exactly as an empty gather does today, and the
   exclusions carry the why."
  [events {:keys [chars-per-token overhead-chars]} budget-tokens]
  (let [predict-tokens (fn [chars]
                         (long (Math/ceil (/ (double chars)
                                             (double chars-per-token)))))
        window-chars (fn [sum-chars n]
                       (if (zero? n) 2 (+ sum-chars n 1)))
        result (fn [selected excluded sum-chars n]
                 (let [chars (+ overhead-chars (window-chars sum-chars n))]
                   {:selected (vec selected)
                    :excluded excluded
                    :predicted-prompt-chars chars
                    :predicted-prompt-tokens (predict-tokens chars)}))]
    (loop [remaining (reverse events)
           selected ()
           sum-chars 0
           n 0]
      (if-let [ev (first remaining)]
        (let [sum' (+ sum-chars (count (pr-str ev)))
              n' (inc n)]
          (if (<= (predict-tokens (+ overhead-chars (window-chars sum' n')))
                  budget-tokens)
            (recur (rest remaining) (cons ev selected) sum' n')
            (result selected (vec (reverse remaining)) sum-chars n)))
        (result selected [] sum-chars n)))))

(def ^:private reflection-instruction
  "Instruction text for the reflection LLM. The model is asked to
   synthesize a principle-shaped description body from the inputs."
  (str "You are consolidating an evolving description of a behavior-tree component "
       "from observed execution evidence. Your job is to produce a principle-shaped "
       "description body that captures the component's strengths, weaknesses, and "
       "actionable usage guidance.\n\n"
       "ANTI-RECENCY DISCIPLINE — read this carefully:\n"
       "  :aggregate-metrics is the STABLE BASELINE accumulated across the target's lifetime.\n"
       "  :recent-events is a LEADING INDICATOR slice (most recent events only).\n"
       "  :recent-vs-historical-delta is the computed gap between recent and historical.\n"
       "  Update strength/weakness confidences GRADUALLY — large recent swings against a strong\n"
       "  historical baseline should produce SMALL confidence changes, not big ones. Erasing a\n"
       "  prior strong principle because of one bad burst is the failure mode to avoid.\n"
       "  A consistent + substantial recent shift (delta of >0.20 sustained, OR aggregate\n"
       "  evidence-count high but recent counter-evidence consistent) warrants larger changes.\n\n"
       "INPUTS PROVIDED:\n"
       "- :target-type — the granularity: :node-type, :node-instance, :tree-fingerprint, or :tree-class\n"
       "  (:tree-class is the substrate the model's prompt-injection reads from —\n"
       "  per-class descriptions evolve across runs of tasks assigned to the same class)\n"
       "- :target-id — the identity within that granularity (keyword, [sheet node] tuple, or string/UUID)\n"
       "- :current-description — the latest existing description body, or nil if this is the first consolidation\n"
       "- :recent-events — the last W events involving this target (status, duration, etc.).\n"
       "  Each observation MAY also carry :judge-scores, a vector of per-event evaluator outputs from "
       "judges attached to the host node — each entry has :judge-name, :score (0.0-1.0), and :feedback.\n"
       "- :aggregate-metrics — accumulated rolling metrics across this target's lifetime.\n"
       "  May include :judge-averages — a map of {judge-name -> mean-score} computed across all "
       "observations of this target's lifetime. This is the STABLE BASELINE for judge signal.\n"
       "- :recent-vs-historical-delta — {:recent-success-rate :historical-success-rate :delta}, or nil on first consolidation\n"
       "  JUDGE WEIGHTING: when a recent observation's :judge-scores diverge from "
       ":judge-averages, treat that the same way you treat success-rate deltas — a single bad "
       "judge score against a long-stable :judge-averages baseline is NOT grounds to invert a "
       "strength; consistent + substantial divergence across multiple recent observations IS.\n"
       "- :structural-context — for trees: the canonical tree-raw S-expression; "
       "for instances: the node config; for node-types: the keyword alone\n\n"
       "OUTPUT — :description-body is a single map with ALL of these top-level keys:\n"
       "  :capabilities         — vector of short strings, what the component CAN do\n"
       "  :strengths            — vector of principle-entry maps (see below)\n"
       "  :weaknesses           — vector of principle-entry maps (see below)\n"
       "  :representative-uses  — vector of short strings, concrete situations the component is good for\n"
       "  :avoid-when           — vector of short strings, contexts to AVOID using the component\n"
       "  :summary              — one-paragraph free-text synthesis (self-contained — NO file paths, NO internal slice names)\n"
       "  :version              — int (any value; consolidator overwrites with computed)\n"
       "  :consolidated-from-event-count — int (any value; consolidator overwrites with computed)\n\n"
       "EVERY one of those 8 keys MUST be present in your output. Empty vectors are acceptable for\n"
       ":capabilities, :strengths, :weaknesses, :representative-uses, :avoid-when when truly nothing applies,\n"
       "but you must include the key with an empty vector ([]) — DO NOT OMIT THE KEY.\n\n"
       "PRINCIPLE-ENTRY shape — every map in :strengths or :weaknesses must contain:\n"
       "  :trait                   — a concrete observable pattern (NOT a status like 'investigate')\n"
       "  :good-when               — context guard (strengths only)\n"
       "  :avoid-when              — context guard (weaknesses only)\n"
       "  :recommended-pattern     — actionable advice (strengths only)\n"
       "  :recommended-alternative — actionable advice (weaknesses only)\n"
       "  :confidence              — 0.0–1.0 weight signal\n"
       "  :evidence-count          — integer count of supporting observations\n"
       "  :first-observed-at       — ISO timestamp string\n"
       "  :last-reinforced-at      — ISO timestamp string\n\n"
       "NEVER produce status-shaped entries (avoid words like 'investigate', 'observed', "
       "'unclear'). Every entry, even at low confidence, must carry concrete actionable guidance.\n\n"
       "EXAMPLE OUTPUT (illustrative shape — your content will differ):\n"
       "  {:capabilities [\"runs a sub-LLM call\" \"produces structured output\"]\n"
       "   :strengths [{:trait \"...\" :good-when \"...\" :recommended-pattern \"...\"\n"
       "                :confidence 1.0 :evidence-count 8\n"
       "                :first-observed-at \"2026-05-20T00:00:00Z\"\n"
       "                :last-reinforced-at \"2026-05-26T00:00:00Z\"}]\n"
       "   :weaknesses [{:trait \"...\" :avoid-when \"...\" :recommended-alternative \"...\"\n"
       "                 :confidence 1.0 :evidence-count 2\n"
       "                 :first-observed-at \"2026-05-20T00:00:00Z\"\n"
       "                 :last-reinforced-at \"2026-05-26T00:00:00Z\"}]\n"
       "   :representative-uses [\"per-chunk extraction inside :map-each\"]\n"
       "   :avoid-when [\"deterministic work — use :code instead\"]\n"
       "   :summary \"One-paragraph synthesis.\"\n"
       "   :version 1\n"
       "   :consolidated-from-event-count 10}"))

(def ^:private current-description-schema
  [:map
   [:summary {:optional true} :string]
   [:capabilities {:optional true} [:vector :string]]
   [:version {:optional true} :int]])

(def ^:private recent-event-schema
  [:map
   [:event/type {:optional true} :keyword]
   [:status {:optional true} :keyword]
   [:confidence {:optional true} :double]])

(def ^:private aggregate-metrics-schema
  [:map
   [:success-count {:optional true} :int]
   [:failure-count {:optional true} :int]
   [:total-count {:optional true} :int]
   [:success-rate {:optional true} :double]])

(def ^:private history-delta-schema
  [:map
   [:recent-success-rate :double]
   [:historical-success-rate :double]
   [:delta :double]])

(def ^:private target-identity-schema
  [:or :keyword [:tuple :uuid :uuid] :uuid :string])

(def ^:private reflection-max-retries
  "The executor-level retry budget BOTH reflection workflows configure
   (see the :options on their :llm nodes). Named because CC-28's failure
   accounting derives the terminal attempt count from it: the executor's
   exception path only escapes after exhausting exactly this budget, so
   a terminal exception-class failure consumed (inc reflection-max-retries)
   provider attempts."
  3)

(defn classify-reflection-failure
  "CC-28 (FailureIsVisible) — map a terminal non-:success exec-result from
   a reflection workflow to {:reason <closed-class> :attempts <count>}.

   PURE, and public for test access. The reason classes are the CLOSED set
   `ontology-schemas/consolidation-failure-reason`; the matching here is a
   heuristic over the executor's terminal shapes, but what it PRODUCES is
   always one of the five contract classes:

     :timeout           — the exec-result's own :status. The executor does
       not surface which attempt the deadline died on, so :attempts is the
       certain floor of 1.
     :unparseable       — the executor's nil-extraction terminal, matched
       on its verbatim error prefix (\"LLM output unparseable for keys\",
       executor.clj). The provider ANSWERED — one terminal attempt's output
       could not be extracted — so :attempts is the floor of 1.
     :provider-rejected — the provider refused the call outright: token/
       context-length caps, invalid-request shapes. The executor retries
       these like any exception (it cannot tell permanent from transient),
       so the full budget was consumed: (inc reflection-max-retries).
     :retries-exhausted — every other EVIDENCED exception-terminal failure:
       the provider kept erroring until the retry budget ran out. Still the
       fallback for an unrecognized but evidenced terminal (an unknown error
       string, an unknown structured kind) — 'died for a reason we could not
       classify' is still 'died', and the closed set means an unknown shape
       maps to the honest superclass rather than minting a new one. It is NO
       LONGER the fallback for an evidence-LESS terminal; that is
       :caller-interrupted. Attempts: (inc reflection-max-retries), because
       reaching this class now REQUIRES evidence the exception path ran.
     :caller-interrupted — the terminal carries no error message, no
       structured :failure-kind and no deadline status: nothing whatsoever
       says a provider attempt was consumed, because the caller killed the
       call before the provider could answer. :attempts is the certain floor
       of 1 — see SIO-4/SIO-4b below.

   SIO-4 (attempt-count honesty). The full budget is a claim about how
   many provider attempts were consumed, and CC-28 stamped it on EVERY
   unrecognized terminal — including terminals carrying no :error, no
   :failure-kind and no deadline status, where nothing whatsoever says the
   executor's retry loop ran. Measured over the durable store: 12 of 43
   real failure records were exactly that shape, each stamped with 4
   consumed attempts on zero evidence. Their terminal is IDENTIFIED, not
   unknown: the harness JVM was stopped mid-consolidation, grain's
   stop-tenant-poller called ExecutorService.shutdownNow, and the
   interrupt made runtime/execute's blocking `deref` throw
   java.lang.InterruptedException — whose .getMessage is nil — which
   `execute-reflection`'s (catch Exception e) turns into
   {:status :failure :error nil}. The executor never returned; zero
   attempts are attributable to a retry budget. So an evidence-free
   terminal reports the CERTAIN FLOOR of 1, the same doctrine :timeout and
   the legacy :unparseable shape already use.

   SIO-4b (reason-class honesty). SIO-4 fixed the COUNT and deliberately
   left the CLASS alone, so the durable store went on saying 'retries
   exhausted' about events where nothing was retried — a false fact in the
   source of truth, and leaning on :attempts 1 to imply 'interrupted' is
   the implicit-meaning trap CC-24 spent a slice removing. The tended spec
   (contract ClaimConsolidation) now names caller interruption as a third
   fact alongside 'produced no trustworthy knowledge' and 'never answered',
   and AttemptCountIsEvidenced states that 'the reason class must carry the
   distinction rather than leaving it implied by an attempt count of 1'. So
   the evidence-free terminal — the one the forensic IDENTIFIED above —
   yields :caller-interrupted with the same floor of 1. The set stays
   CLOSED at five; this mints exactly one named class for a shape we
   measured, and moves no neighbour: a terminal carrying a message, a
   structured kind, or a deadline status classifies exactly as before."
  [{:keys [status error failure-kind]}]
  (let [error-str (some-> error str)
        no-evidence? (and (str/blank? error-str) (nil? failure-kind))
        full (inc reflection-max-retries)
        context-rejection? (and error-str
                                (re-find #"(?i)too long|too large|context.{0,8}length|maximum context|token limit|exceeds?.{0,24}(maximum|limit)|invalid_request"
                                         error-str))]
    (cond
      (= :timeout status)
      {:reason :timeout :attempts 1}

      failure-kind
      (case failure-kind
        (:tool-call-parsing-failed
         :missing-forced-tool-call
         :schema-validation-failed) {:reason :unparseable :attempts full}
        :empty-provider-response    {:reason :provider-rejected :attempts full}
        :transport-failure          (if context-rejection?
                                      {:reason :provider-rejected :attempts full}
                                      {:reason :retries-exhausted :attempts full})
        {:reason :retries-exhausted :attempts full})

      (and error-str (re-find #"LLM output unparseable" error-str))
      {:reason :unparseable :attempts 1}

      context-rejection?
      {:reason :provider-rejected :attempts full}

      no-evidence?
      {:reason :caller-interrupted :attempts 1}

      :else
      {:reason :retries-exhausted :attempts full})))

(defn execute-reflection
  "Run the reflection workflow, converting a THROW from the execute call
   itself into the same terminal shape the executor returns for its own
   failures — so both reflection paths have exactly ONE failure seam and
   a thrown reflection cannot slip past FailureIsVisible into the
   processor's catch-all log line."
  [context sheet-id inputs]
  (try
    (orc/execute context sheet-id inputs)
    (catch Exception e
      (let [{:keys [failure-kind provider-evidence]} (ex-data e)]
        (cond-> {:status :failure :error (.getMessage e)}
          failure-kind (assoc :failure-kind failure-kind)
          provider-evidence (assoc :provider-evidence provider-evidence))))))

(defn reflection-workflow
  "Single-:llm-node ORC workflow for the consolidator's reflection call.

   The blackboard schemas for each :writes key drive llm's structured-
   output spec — that's how the LLM is told the per-field types. Each of
   the six top-level description-body fields is its own :writes slot with
   a precise schema; in particular :strengths/:weaknesses use the rich
   principle-entry schema so the LLM is told to produce maps with
   :trait/:good-when/:recommended-pattern/:confidence/:evidence-count/...
   fields, not just `:map`."
  [model]
  (orc/workflow "ontology-consolidator-reflection"
    (orc/blackboard
      {:target-type [:enum :node-type :node-instance :tree-fingerprint :tree-class]
       :target-id target-identity-schema
       :current-description [:maybe current-description-schema]
       :recent-events [:vector recent-event-schema]
       :aggregate-metrics [:maybe aggregate-metrics-schema]
       :recent-vs-historical-delta [:maybe history-delta-schema]
       :structural-context [:maybe target-identity-schema]
       :capabilities [:vector :string]
       :strengths [:vector schemas/principle-entry]
       :weaknesses [:vector schemas/principle-entry]
       :representative-uses [:vector :string]
       :avoid-when [:vector :string]
       :summary :string})

    (cond->
      (orc/llm "reflect"
        :instruction reflection-instruction
        :reads [:target-type :target-id :current-description
                :recent-events :aggregate-metrics
                :recent-vs-historical-delta :structural-context]
        :writes [:capabilities :strengths :weaknesses
                 :representative-uses :avoid-when :summary]
        :options {:max-retries reflection-max-retries
                  :retry-delay-ms [500 1500 3000]})
      model (assoc :model model))))

(def ^:private claim-operation-proposal
  "What ONE reflection operation may look like ON THE WIRE.

   Deliberately a SUPERSET-tolerant shape rather than `claim-delta` itself:
   `:kind` is optional here because a `:support` / `:contradict` / `:edit`
   operation names a claim that already HAS a kind, and demanding the model
   restate it is one more field to get wrong. `prepare-operations` fills it from
   the named claim before anything is dispatched.

   `:episodes` and `:from-legacy-corpus` are absent BY DESIGN — see element 4
   above."
  [:map
   [:operation      [:enum :add :support :contradict :edit]]
   [:target-claim   {:optional true} [:maybe :string]]
   [:kind           {:optional true} [:maybe [:enum :capability :strength :weakness
                                              :guard :representative-use]]]
   [:content        {:optional true} [:maybe :string]]
   [:context-guard  {:optional true} [:maybe :string]]
   [:recommendation {:optional true} [:maybe :string]]])

(def ^:private claim-reflection-instruction
  (str
    "You are updating an accumulated set of CLAIMS about a behavior-tree "
    "component from newly observed execution evidence.\n\n"

    "You are NOT writing a description. You do not rewrite, summarise or "
    "replace anything. You emit a list of OPERATIONS over the numbered claim "
    "set you are given, and deterministic code applies them. A claim you say "
    "nothing about survives unchanged.\n\n"

    "INPUTS:\n"
    "- :claim-set — the target's CURRENT claims, numbered, one per line. Each "
    "line gives the claim's id, its kind, the support it has accumulated, and "
    "its content in quotes. The id is the string you must copy verbatim into "
    ":target-claim.\n"
    "- :recent-events — the observation window this consolidation reasons over. "
    "Each observation may carry :judge-scores (:judge-name, :score 0.0-1.0, "
    ":feedback) from judges attached to the host node.\n"
    "- :aggregate-metrics — accumulated metrics across this target's lifetime, "
    "the STABLE BASELINE. May include :judge-averages, the mean score per judge "
    "across the whole lifetime.\n"
    "- :recent-vs-historical-delta — the computed gap between the recent window "
    "and the historical baseline, or nil on a first consolidation.\n"
    "- :target-type / :target-id / :structural-context — what the target IS.\n\n"

    "FOLLOW THIS PROCEDURE IN ORDER.\n\n"

    "STEP 1 — MANDATORY, AND YOU MUST DO IT BEFORE STEP 2. Walk the claim set "
    "from claim 1 to the last claim, IN ORDER, and for EACH one ask: does this "
    "evidence window bear on this claim?\n"
    "  - The evidence CORROBORATES it -> emit :support naming its id.\n"
    "  - The evidence corroborates it AND you can state it more precisely, or "
    "it belongs to a different kind now -> emit :edit naming its id, with the "
    "improved :content (and the new :kind if it moved).\n"
    "  - The evidence CONTRADICTS it -> emit :contradict naming its id.\n"
    "  - The evidence says nothing about it -> emit nothing for it.\n"
    "REINFORCING A CLAIM THE EVIDENCE SUPPORTS IS NOT OPTIONAL. Support is how "
    "a claim earns the right to act; a claim that keeps being true and keeps "
    "being passed over in silence decays relative to the ones that are named. "
    "Do not skip a claim because it is 'already obvious' or 'already known' — "
    "if this window shows it again, say so.\n\n"

    "STEP 2 — only now, look for insights in the evidence that NO existing "
    "claim covers. Before proposing one, check it against EVERY claim in the "
    "set, including claims of a different kind: an insight is frequently the "
    "same knowledge as an existing claim wearing different words, and it is far "
    "better to :edit an existing claim than to :add a near-duplicate. Only when "
    "an insight matches nothing in the set, emit :add.\n\n"

    "OPERATIONS — the permitted keys, and NOTHING ELSE:\n"
    "  :operation      — one of :add, :support, :contradict, :edit\n"
    "  :target-claim   — the claim id copied VERBATIM from the claim set. "
    "Required for :support, :contradict and :edit. Omit it for :add.\n"
    "  :kind           — one of :capability, :strength, :weakness, :guard, "
    ":representative-use. Required for :add. Supply it on :edit only when the "
    "claim's kind is changing.\n"
    "  :content        — ONE LINE of plain prose stating the claim itself. Not "
    "a label, not a heading, not a bracketed tag, not a multi-line block, and "
    "never a continuation of another field. Required for :add and :edit.\n"
    "  :context-guard  — optional, one line: when this claim does NOT apply.\n"
    "  :recommendation — optional, one line: what to do about it.\n"
    "Any other key is REJECTED and its operation is discarded.\n\n"

    "Do NOT invent occurrence ids, episode references, event ids, timestamps, "
    "confidences or evidence counts. Those are supplied by the system from the "
    "evidence window. You have no way to know them and a guess is worse than "
    "an omission.\n\n"

    ":content must be a concrete observable pattern with actionable meaning. "
    "Never produce status-shaped content — words like 'investigate', "
    "'observed' or 'unclear' describe your process, not the component.\n\n"

    "Emit an empty operation list only when the evidence genuinely bears on "
    "nothing in the set and contains no new insight."))

(defn claim-reflection-workflow
  "Single-:llm-node ORC workflow for the CLAIM reflection call.

   `:operations` is typed as a vector of `claim-operation-proposal` so the llm
   structured-output spec tells the model the per-field types. The schema is an
   OPEN malli map — which is exactly why `prepare-operations` has to reject
   unknown keys itself: a drifted key VALIDATES here and would vanish silently.

   The input schemas mirror `reflection-workflow`'s: both workflows are fed by
   the same gatherers (`gather-recent-events`, `gather-aggregate-metrics`,
   `compute-delta`, `gather-structural-context`), and the DSL layer rejects
   unconstrained schemas (`:any` / bare `:map`) outright.

   CC-31: parameterized by `model` exactly as `reflection-workflow` is — the
   node's `:model` is what the engine stamps onto the completion event, and
   that stamp is what makes the recorded deltas' provenance extractable. A
   nil model leaves the node unpinned (ambient provider default), which is
   the pre-CC-31 shape unchanged."
  [model]
  (orc/workflow "ontology-consolidator-claim-reflection"
    (orc/blackboard
      {:target-type [:enum :node-type :node-instance :tree-fingerprint :tree-class]
       :target-id target-identity-schema
       :claim-set :string
       :recent-events [:vector recent-event-schema]
       :aggregate-metrics [:maybe aggregate-metrics-schema]
       :recent-vs-historical-delta [:maybe history-delta-schema]
       :structural-context [:maybe target-identity-schema]
       :operations [:vector claim-operation-proposal]})

    (cond->
      (orc/llm "propose-claim-operations"
        :instruction claim-reflection-instruction
        :reads [:target-type :target-id :claim-set
                :recent-events :aggregate-metrics
                :recent-vs-historical-delta :structural-context]
        :writes [:operations]
        :options {:max-retries reflection-max-retries
                  :retry-delay-ms [500 1500 3000]})
      model (assoc :model model))))

(defn source-event-type-for-target
  "Which source event-type carries observations for a given target-type?"
  [target-type]
  (case target-type
    :node-type        :sheet/node-execution-completed
    :node-instance    :sheet/node-execution-completed
    :tree-fingerprint :sheet/rlm-tree-execution-completed
    :tree-class       :ontology/task-classified))

(defn event-matches-target?
  "Filter predicate for an event against a (target-type, target-id) target."
  [target-type target-id event]
  (case target-type
    :node-type        (= target-id (:node-type event))
    :node-instance    (and (= (first target-id) (:sheet-id event))
                           (= (second target-id) (:node-id event)))
    :tree-fingerprint (= target-id (:tree-fingerprint event))
    :tree-class       (= target-id (:assigned-tree-id event))))

(defn clean-event-for-llm
  "Strip non-JSON-serializable fields (event-store metadata, timestamps,
   tag sets) from a source event before it's passed to the LLM. Keeps
   only the semantic content the LLM needs to reason about — status,
   duration, target identity, usage. Timestamps are stringified."
  [event]
  (cond-> (-> event
              (dissoc :event/id :event/tags :event/timestamp))
    (some? (:event/timestamp event))
    (assoc :timestamp (str (:event/timestamp event)))
    true project-observation))

(defn- success-rate
  "Fraction of events with :status :success. nil for empty input."
  [events]
  (when (seq events)
    (/ (count (filter #(= :success (:status %)) events))
       (double (count events)))))

(defn compute-delta
  "Compute recent-vs-historical comparison for the LLM. Returns a small
   map with :recent-success-rate, :historical-success-rate, and the
   :delta (recent minus historical). Returns nil when aggregate-metrics
   is unavailable (first consolidation) — the prompt then omits the
   delta section."
  [aggregate-metrics recent-events]
  (when (and aggregate-metrics (seq recent-events))
    (let [succ (:success-count aggregate-metrics)
          fail (:failure-count aggregate-metrics)
          total (+ (or succ 0) (or fail 0))]
      (when (pos? total)
        (let [historical (double (/ succ total))
              recent (success-rate recent-events)]
          {:recent-success-rate recent
           :historical-success-rate historical
           :delta (- recent historical)})))))

(defn gather-structural-context
  "Pull structural context for this target (tree-raw / node config / kw)."
  [_ctx _target-type target-id]
  target-id)

(defn parse-vector-of-maps
  "llm returns simple-typed vector fields as native Clojure data, but
   vector-of-map fields (like :strengths / :weaknesses with their rich
   per-entry schema) sometimes arrive as EDN-or-JSON-encoded strings.
   Parse those back into native data. Pass already-parsed vectors through."
  [v]
  (cond
    (vector? v) v
    (sequential? v) (vec v)
    (not (string? v)) v
    (str/blank? v) []
    :else
    (try
      (let [parsed (read-string v)]
        (if (sequential? parsed) (vec parsed) v))
      (catch Exception _
        (try
          (cheshire/parse-string v true)
          (catch Exception _ v))))))

(defn record-description-command
  "PR-2: `request-id` (optional, may be nil) is the consolidation request
   this description answers — omitted from the command when nil so direct
   callers' commands stay byte-shaped."
  ([target-type target-id body model-provenance]
   (record-description-command target-type target-id body model-provenance nil))
  ([target-type target-id body model-provenance request-id]
   (let [cmd-name (case target-type
                    :node-type        :ontology/record-node-type-description
                    :node-instance    :ontology/record-node-instance-description
                    :tree-fingerprint :ontology/record-tree-description
                    :tree-class       :ontology/record-tree-class-description)]
     (cond-> {:command/name cmd-name
              :command/id (random-uuid)
              :command/timestamp (time/now)
              :target-id target-id
              :body body
              :model-provenance model-provenance}
       (some? request-id) (assoc :request-id request-id)))))

(defn next-version [current-description]
  (if current-description
    (inc (or (:version current-description) 0))
    1))

(def parent-inference-threshold
  "Confidence threshold used when asking classify-task to identify the
   abstract parent of a brand-new tree-fingerprint. Mirrors the
   :auto-classify-threshold default from the C-2c-2 wedge — one knob
   to tune for both call sites."
  0.7)

(defn- build-parent-inference-signature
  "Build a task-signature for the consolidator's parent-inference call
   from the just-consolidated description body. Uses :summary,
   :capabilities, and :representative-uses so the classifier sees what
   the tree IS rather than what produced it."
  [body]
  (str "TREE SUMMARY:\n"
       (or (:summary body) "(none)")
       "\n\nCAPABILITIES:\n"
       (str/join "\n" (map #(str "- " %) (or (:capabilities body) [])))
       "\n\nREPRESENTATIVE USES:\n"
       (str/join "\n" (map #(str "- " %) (or (:representative-uses body) [])))))

;; --- Read models ---

(defn read-models-by-earned-support
  "Rank claims strongest-first, with `:claim-id` as a deterministic tie-break.

   The ORDER IS LOAD-BEARING and is not cosmetic: EL-2's
   `enrich-candidate-evidence` truncates with `(take 3)` in body order — it
   does NOT sort — so whatever lands first is what the reranker sees. Ranking
   here means the best-supported claims survive every downstream truncation,
   including the ones that do not sort for themselves. Tie-breaking on
   `:claim-id` (deterministic from the recording event) keeps a rebuild from
   the log byte-identical."
  [claims]
  (sort-by (juxt (comp - :support) :claim-id) claims))

;; --- Consolidator ---

(def ^:private consolidator-by-earned-support
  "read-models' claim ranking (support desc, tie-break :claim-id) — reached
   through its own var so this seam and the fold's rendering order cannot
   drift apart. The order is the spec's `global support-rank` and the
   tie-break keeps a rebuild from the log byte-identical."
  @#'read-models-by-earned-support)

(defn- claim-query-render
  "One-pass render of a claim seq for the parent-inference query: the
   assemble-summary section order (Capabilities, Representative uses,
   Strengths, Known weaknesses, Avoid when), each claim's content exactly
   ONCE (invariant.NoDuplicateClaimContent — the measured 1.64x inflation
   was summary+sections double-rendering, not knowledge growth). Bounding
   happens by claim RANK upstream, never by token position, so the guards
   cannot be pushed last-and-truncated by the encoder."
  [claims]
  (let [contents (fn [kind]
                   (into [] (comp (filter #(= kind (:kind %)))
                                  (map :content)
                                  (distinct))
                         claims))
        section (fn [label items]
                  (when (seq items)
                    (str label ": " (str/join "; " items) ".")))
        parts (remove nil?
                      [(section "Capabilities" (contents :capability))
                       (section "Representative uses" (contents :representative-use))
                       (section "Strengths" (contents :strength))
                       (section "Known weaknesses" (contents :weakness))
                       (section "Avoid when" (contents :guard))])]
    (str "TREE SUMMARY:\n" (str/join " " parts))))

(defn- query-fits-fn
  "A predicate `(fits? query-string)` — true when the string fits the
   CONFIGURED encoder query budget (`maximum_query_tokens` minus the query
   special tokens), measured by the REAL tokenizer via the colbert
   interface's `query-truncation` (tokenizer-only, the same resolution order
   production searches use — never a hardcoded budget, never chars/4).

   Resolved lazily: ontology ships without a ColBERT dependency. nil when
   colbert is not on the classpath — there is then no ColBERT query budget
   to enforce (classification runs on graph + DJL-embedding signals) and the
   caller LOUDLY reports the unbounded render."
  []
  (when-let [qt (integrations/capability :colbert :query-truncation)]
    (fn [q] (not (:query-truncated? (qt {} {:query q}))))))

(defn bounded-inference-query
  "CC-22b (contract ParentInferenceQuery): render a target's parent-inference
   query at the signature seam.

   LEGACY (empty claim set — every production target today): delegates to
   `build-parent-inference-signature`, BYTE-IDENTICALLY. Behavior-neutral
   until migration day.

   CLAIM-BACKED: renders the largest global support-rank PREFIX of the claim
   set (tie-break :claim-id; first-overflow stop) whose one-pass render fits
   the configured budget. Exclusion is OBSERVABLE — the unrendered claim ids
   are returned AND logged (`::inference-query-claims-excluded`), never
   silently capped; every excluded claim stays durable in the claim set.

   Returns {:query :rendered-claim-ids :excluded-claim-ids :claim-backed?}.

   Public: the production callers are the consolidator's two hydration
   inference calls; tests exercise this seam with the banked real fixtures."
  ([body claims] (bounded-inference-query body claims nil))
  ([body claims {:keys [target-id]}]
   (if (empty? claims)
     {:query (build-parent-inference-signature body)
      :rendered-claim-ids []
      :excluded-claim-ids []
      :claim-backed? false}
     (let [ranked (vec (consolidator-by-earned-support claims))
           fits? (query-fits-fn)
           kept-n (if fits?
                    (try
                      (loop [n 1]
                        (cond
                          (> n (count ranked)) (count ranked)
                          (fits? (claim-query-render (subvec ranked 0 n))) (recur (inc n))
                          :else (dec n)))
                      (catch Exception e
                        (u/log :ai.obney.orc.ontology.core.consolidator/inference-query-budget-unavailable
                               :target-id target-id
                               :reason :tokenizer-error
                               :error (.getMessage e))
                        (count ranked)))
                    (do (u/log :ai.obney.orc.ontology.core.consolidator/inference-query-budget-unavailable
                               :target-id target-id
                               :reason :colbert-not-on-classpath)
                        (count ranked)))
           kept (subvec ranked 0 kept-n)
           excluded (subvec ranked kept-n)]
       (when (seq excluded)
         (u/log :ai.obney.orc.ontology.core.consolidator/inference-query-claims-excluded
                :target-id target-id
                :rendered-claim-count (count kept)
                :excluded-claim-count (count excluded)
                :excluded-claim-ids (mapv :claim-id excluded)))
       {:query (claim-query-render kept)
        :rendered-claim-ids (mapv :claim-id kept)
        :excluded-claim-ids (mapv :claim-id excluded)
        :claim-backed? true}))))

(def behavioral-inference-threshold
  "Confidence threshold for the consolidator's behavioral inference call.
   Mirrors the classify-behaviors default. Distinct from
   parent-inference-threshold so the two retrieval surfaces can be tuned
   independently as the corpus matures."
  0.7)

(def behavioral-inference-top-n
  "How many top behaviors to stamp on the body. Three keeps the
   downstream display compact while giving the RLM researcher enough
   examples to triangulate."
  3)

(defn claim-path-target-type?
  "Which granularities consolidate as CLAIM DELTAS rather than as a whole body.

   Today: `:tree-class` only, and the boundary is a consequence of two concrete
   blockers rather than a preference.

   `:node-type` and `:node-instance` CANNOT join the claim model at all. CC-1's
   `:ontology/record-claim-deltas` types `:target-identifier` as
   `[:or :string :uuid]` (so does the recorded event, and so does the spec's
   ClaimDeltasRecorded). A node-type target is a KEYWORD (`:llm`) and a
   node-instance target is a `[sheet-id node-id]` TUPLE. Neither is
   representable, and stringifying them would key the claim set at a DIFFERENT
   read-model node from the description they belong to — `[:node-type \":llm\"]`
   is not `[:node-type :llm]` — which would fork one target's Living
   Description into two. That is a schema change (CC-1's contract and the
   spec's), not something this slice may make silently.

   `:tree-fingerprint` is blocked by a different thing: its body is the ONLY
   carrier for the C-2d-2 `:parent-tree-id` and R05d `:behavioral-subtree-ids`
   graph metadata, which the reactive projector and the behavior:composes-into
   edge growth both read off the emitted description event. CC-3's
   `assemble-body` can only CARRY those keys FORWARD from a previous body, so a
   brand-new tree-fingerprint consolidating through the claim path would never
   be parented and would never grow its composes-into edges. A ClaimDelta has
   no field for graph metadata, so giving it one is a spec change too.

   Both are reported as findings rather than worked around. What matters for
   the two-writers-on-one-slot defect is that the split is TOTAL: a target that
   has a claim path never takes the body path, and a target that takes the body
   path can never have claims. No `:current` slot has two writers."
  [target-type]
  (= :tree-class target-type))

(def ^:private permitted-delta-keys
  "The key set an operation may carry, DERIVED from `claim-delta` rather than
   restated, so the two cannot drift apart.

   This exists because the delta schema is an OPEN malli map. A drifted key —
   P-A observed a `:guard` where `:context-guard` belonged, once in 178
   operations — VALIDATES, rides through the command, lands on the event, and
   its data is then never read by anything. Silent. Rejecting the operation
   here is the only place that failure is visible at all."
  (into #{} (map first) (m/children (m/schema schemas/claim-delta))))

(def ^:private operation-precedence
  "Which operation wins when a consolidation names the same claim twice.

   `:edit` outranks everything because CC-2 makes an edit a SUPERSET of a
   support — it reinforces AND rewords — so collapsing the `{:support, :edit}`
   pair P-A observed in 4 of 20 rows loses nothing at all. `:contradict`
   outranks a bare `:support` because withheld disagreement is the signal this
   corpus is thinnest on, and with the seed at 2 a single contradiction can no
   longer erase anything."
  {:edit 3 :contradict 2 :support 1})

(defn- collapse-operations
  "ONE CONSOLIDATION IS ONE OCCURRENCE.

   CC-2 applies deltas sequentially, so two operations naming the same claim
   increment its support twice from a single evidence window. That inflation
   feeds straight into CC-7's validation threshold and CC-9's enforcement
   weighting — a claim would earn the right to suppress behaviours by being
   mentioned twice in one paragraph. P-A measured this in 4 of 20 real rows.

   Keeps at most one operation per `:target-claim` (highest precedence, ties
   broken by the model's own last mention) and at most one `:add` per
   (kind, content) pair, and preserves the model's ordering otherwise."
  [ops]
  (let [indexed (vec (map-indexed vector ops))
        add-keep (:keep (reduce (fn [{:keys [seen keep]} [i op]]
                                  (let [k [(:kind op) (:content op)]]
                                    (if (contains? seen k)
                                      {:seen seen :keep keep}
                                      {:seen (conj seen k) :keep (conj keep i)})))
                                {:seen #{} :keep #{}}
                                (filter (fn [[_ op]] (= :add (:operation op))) indexed)))
        other-keep (->> indexed
                        (filter (fn [[_ op]] (not= :add (:operation op))))
                        (group-by (fn [[_ op]] (:target-claim op)))
                        (map (fn [[_ group]]
                               (first (last (sort-by (fn [[i op]]
                                                       [(get operation-precedence (:operation op) 0) i])
                                                     group)))))
                        (into #{}))
        keep (into add-keep other-keep)]
    (mapv second (filter (fn [[i _]] (contains? keep i)) indexed))))

(defn- ->enum-keyword
  "Coerce ONE enum-valued field from the wire.

   The reflection arrives as structured JSON, so its enums come back as STRINGS
   — the live model answers `{:operation \"support\"}` where the schema declares
   `:support`. This is the wire boundary and it belongs here rather than in the
   schema, because the schema is also what documents the value the rest of the
   system passes around.

   A LIVE-QA FINDING, and the reason a stub is never sufficient: every stubbed
   test in this repo hands the consolidator keywords, so the whole contract
   suite was green while the real model's every operation was being rejected as
   malformed. Tolerates `\"support\"`, `\":support\"` and `:support` alike;
   anything else is left untouched so the schema rejects it."
  [v]
  (cond
    (keyword? v) v
    (string? v)  (let [s (str/trim v)]
                   (if (str/blank? s) nil (keyword (str/replace s #"^:" ""))))
    :else v))

(defn- coerce-proposal
  "Normalise one proposed operation off the wire before it is validated."
  [op]
  (cond-> op
    (contains? op :operation) (update :operation ->enum-keyword)
    (contains? op :kind)      (update :kind ->enum-keyword)))

(defn prepare-operations
  "Turn the reflection's proposed operations into dispatchable claim deltas.

   Five things happen here, and none of them can be done by a prompt:

   0. WIRE ENUMS ARE COERCED to keywords (see `->enum-keyword`).
   1. UNKNOWN KEYS ARE REJECTED (see `permitted-delta-keys`).
   2. Malformed operations are rejected against `claim-operation-proposal`
      rather than reaching a command that would take them.
   3. `:kind` AND `:content` are FILLED from the named claim for non-`:add`
      operations. The model is not asked to restate the kind or the wording of a
      claim it is only reinforcing — and observably does not — while
      `claim-delta` requires both.
   4. Operations are COLLAPSED per target claim (see `collapse-operations`).

   Finally the episodes are attached IN CODE from the evidence window — the
   model is never asked for occurrence ids, which is what keeps id fabrication
   at zero. An operation that arrived carrying its own episodes (a mechanical
   writer rather than the reflection LLM) keeps them.

   Returns `{:deltas [...] :rejected [{:operation .. :reason ..} ...]}`."
  [operations claims episodes]
  (let [by-id (into {} (map (juxt :claim-id identity)) claims)
        rejected (volatile! [])
        reject! (fn [op reason] (vswap! rejected conj {:operation op :reason reason}) nil)
        normalized
        (into []
              (keep (fn [raw]
                      (let [op (when (map? raw) (coerce-proposal raw))]
                        (cond
                          (not (map? raw))
                          (reject! raw :not-a-map)

                          (seq (remove permitted-delta-keys (keys op)))
                          (reject! op :unknown-keys)

                          (not (m/validate claim-operation-proposal op))
                          (reject! op :malformed-operation)

                          :else
                          (let [add? (= :add (:operation op))
                                target (:target-claim op)
                                claim (get by-id target)
                                kind (or (:kind op) (:kind claim))
                                content (or (not-empty (str/trim (str (:content op))))
                                            (:content claim))]
                            (cond
                              (and add? (nil? (:kind op)))
                              (reject! op :add-without-kind)

                              (and add? (nil? content))
                              (reject! op :add-without-content)

                              (and (not add?) (str/blank? (str target)))
                              (reject! op :missing-target-claim)

                              (or (nil? kind) (nil? content))
                              (reject! op :unresolvable-target-claim)

                              :else
                              (cond-> (assoc op :kind kind :content content)
                                add? (dissoc :target-claim))))))))
              operations)]
    {:deltas (mapv (fn [op]
                     (assoc op
                            :episodes (vec (or (seq (:episodes op)) episodes))
                            :from-legacy-corpus (boolean (:from-legacy-corpus op))
                            :evidence-basis :judged-occurrences))
                   (collapse-operations normalized))
     :rejected @rejected}))

(def ^:private max-episodes-per-delta
  "How many of the evidence window's occurrences ride on one delta.

   The window itself is bounded by the evidence token budget (PR-1; formerly
   the 500-event `recent-window-size` cap). Filing every windowed occurrence on
   every claim on every consolidation would grow `:supporting-episodes`
   without bound — CC-2's fold appends without de-duplicating, and it is not
   this slice's to change — which is the same unbounded-growth defect CC-13
   found in the injection sidecar, relocated into the claim set. The most
   RECENT occurrences are kept: they are the ones a reader would re-examine and
   the ones most likely to still resolve against judge evidence."
  50)

(defn- occurrence-pair
  "The HP-2 `[sheet-id tick-id]` occurrence pair for one observation.

   Prefers `:source-*`: a bookend's own `:sheet-id` is the EPHEMERAL Phase-2
   sheet, a domain disjoint from the classified HOST sheet, and a static
   task-shape's sheet-id is shared across every turn — the tick is what makes
   the reference resolvable. That is the SJ-1 join lesson, and getting it wrong
   here would hand CC-4's guard occurrences it can never ground."
  [ev]
  (let [s (or (:source-sheet-id ev) (:sheet-id ev))
        t (or (:source-tick-id ev) (:tick-id ev))]
    (when (and (uuid? s) (uuid? t)) [s t])))

(defn evidence-window-episodes
  "The occurrences this consolidation reasoned over, oldest first, deduped and
   capped. This is what CC-4's guard resolves against judge evidence."
  [recent-events]
  (->> recent-events
       (keep occurrence-pair)
       distinct
       vec
       (take-last max-episodes-per-delta)
       vec))

(defn render-claim-set
  "The numbered pool, ONE LINE PER CLAIM, quoted, in the delta's own
   vocabulary. See the four load-bearing prompt elements above: this rendering
   alone eliminated three of the four failure shapes P-A observed."
  [claims]
  (if (empty? claims)
    "(this target has no claims yet — the set is empty)"
    (->> claims
         (map-indexed
           (fn [i c]
             (str (inc i) ". "
                  "claim-id=" (pr-str (:claim-id c)) " "
                  "kind=" (pr-str (:kind c)) " "
                  "support=" (:support c) " "
                  "content=" (pr-str (:content c)))))
         (str/join "\n"))))

(defn legacy-body->claim-deltas
  "Every insight a legacy whole-body description holds, expressed as `:add`
   deltas — the body's sections mapped onto the claim kinds CC-3 assembles them
   back out of."
  [body]
  (let [add (fn [kind content guard recommendation]
              (when-not (str/blank? (str content))
                {:operation :add
                 :kind kind
                 :content (str content)
                 :context-guard guard
                 :recommendation recommendation
                 :episodes []
                 :from-legacy-corpus true}))]
    (vec (concat
           (keep #(add :capability % nil nil) (:capabilities body))
           (keep #(add :representative-use % nil nil) (:representative-uses body))
           (keep #(add :guard % nil nil) (:avoid-when body))
           (keep #(add :strength (:trait %) (:good-when %) (:recommended-pattern %))
                 (:strengths body))
           (keep #(add :weakness (:trait %) (:avoid-when %) (:recommended-alternative %))
                 (:weaknesses body))))))

(def orphan-request-grace-ms
  "How old an unanswered consolidation request must be before the sweep may
   declare it dead. Bounds the false-positive window for a consolidation
   that is legitimately STILL RUNNING: the executor's default 300,000 ms
   deadline times the retry budget (1 + reflection-max-retries attempts)
   plus the inter-attempt delays is < 21 minutes, so 30 minutes is past any
   wall-clock a live reflection can still be inside. Overridable per
   context via :orphan-grace-ms (tests) or per call via :older-than-ms."
  (* 30 60 1000))

(defn checked-graph-result [result]
  (if (:cognitect.anomalies/category result)
    (throw (ex-info (:cognitect.anomalies/message result) result))
    result))

(defn registered-sources [ctx ontology-id]
  (let [result (ontology-lib/get-sources ctx ontology-id)]
    (if (= :cognitect.anomalies/not-found (:cognitect.anomalies/category result))
      []
      (checked-graph-result result))))

(defn get-source-by-hash [ctx ontology-id content-hash]
  (first (filter #(= content-hash (:content-hash %)) (registered-sources ctx ontology-id))))

;; --- Source registry ---

(defn check-source-processed [ctx ontology-id source-uri content]
  (let [hash (sources/compute-content-hash source-uri content)
        source (get-source-by-hash ctx ontology-id hash)]
    {:processed? (boolean source) :source-id (:source-id source) :computed-hash hash}))

(defn get-source [ctx ontology-id source-id]
  (first (filter #(= source-id (:source-id %)) (registered-sources ctx ontology-id))))

;; --- Read models ---

(defn harvested-tree-class-tag
  "Stable event-store tag identifying a `:ontology/behavioral-subtree-
   minted` event as the harvested mint FOR `class-id`. Tag values must be
   UUIDs (event-store-v3), so a non-UUID class-id is not native here —
   class-ids ARE UUIDs, but the derivation still goes through a stable hash
   (matching the idiom `commands.clj`'s other `:description-target` tags
   use) so the emit site (`mint-behavioral-subtree`) and every reader
   (`harvest.clj`'s `already-harvested?`) compute the SAME tag value from
   ONE derivation, never two independently-computed hashes that could
   drift apart."
  [class-id]
  [:harvested-tree-class
   (java.util.UUID/nameUUIDFromBytes
    (.getBytes (str "harvested-tree-class:" class-id) "UTF-8"))])

(def description-events
  "C-2: events that affect the Living Description read model.

   One event type per granularity (node-type, node-instance, tree-fingerprint).
   Append-only — each event is a new version of the target's description;
   the projection maintains both 'current' (latest body) and 'history'
   (chronological vector of all versions)."
  #{:ontology/node-type-description-updated
    :ontology/node-instance-description-updated
    :ontology/tree-description-updated})

(def claim-events
  "CC-1 (ADR 0021): events that affect the CLAIM side of the Living
   Description read model.

   Claims live in the same versioned read model as descriptions because
   they hang off the same (granularity, target-identifier) key — the
   spec's `LivingDescription.claims` relationship. Keeping them in one
   projection means one rebuild, and means a target's claim set and its
   assembled body can never drift into separate cache generations."
  #{:ontology/claim-deltas-recorded})

(defmulti tree-profiles*
  "Apply event to tree profiles read model.
   State: {tree-id -> profile-map}"
  (fn [_state event] (:event/type event)))

(defmethod tree-profiles* :ontology/tree-strength-recorded
  [state event]
  (let [tree-id (:tree-id event)]
    (-> state
        (assoc-in [tree-id :tree-id] tree-id)
        (update-in [tree-id :strengths]
                   (fnil conj [])
                   (cond-> {:pattern (:pattern-uri event)
                            :confidence (:confidence event)
                            :evidence-count (count (:evidence-trace-ids event))
                            :avg-score (:avg-score event)
                            :recorded-at (str (:recorded-at event))}
                     (:context-conditions event)
                     (assoc :context-conditions (:context-conditions event))
                     (and (nil? (:context-conditions event)) (:state-conditions event))
                     (assoc :context-conditions (:state-conditions event))
                     (:action-taken event)
                     (assoc :action-taken (:action-taken event))
                     (:domain-type event)
                     (assoc :domain-type (:domain-type event))
                     (:expected-outcome event)
                     (assoc :expected-outcome (:expected-outcome event)))))))

(defmethod tree-profiles* :ontology/tree-weakness-recorded
  [state event]
  (let [tree-id (:tree-id event)]
    (-> state
        (assoc-in [tree-id :tree-id] tree-id)
        (update-in [tree-id :weaknesses]
                   (fnil conj [])
                   (cond-> {:failure (:failure-uri event)
                            :subtype (:subtype-uri event)
                            :frequency (:frequency event)
                            :severity (:severity event)
                            :triggers (:triggers event)
                            :recorded-at (str (:recorded-at event))}
                     (:failure-context event)
                     (assoc :failure-context (:failure-context event))
                     (and (nil? (:failure-context event)) (:failure-conditions event))
                     (assoc :failure-context (:failure-conditions event))
                     (:attempted-action event)
                     (assoc :attempted-action (:attempted-action event))
                     (:domain-type event)
                     (assoc :domain-type (:domain-type event)))))))

(defmethod tree-profiles* :ontology/tree-problem-mapping-created
  [state event]
  (let [tree-id (:tree-id event)]
    (-> state
        (assoc-in [tree-id :tree-id] tree-id)
        (update-in [tree-id :solves]
                   (fnil conj [])
                   {:problem-uri (:problem-uri event)
                    :success-rate (:success-rate event)
                    :execution-count (:execution-count event)
                    :recorded-at (str (:recorded-at event))}))))

(defmethod tree-profiles* :ontology/tree-problem-mapping-updated
  [state event]
  (let [tree-id (:tree-id event)
        problem-uri (:problem-uri event)]
    (update-in state [tree-id :solves]
               (fn [solves]
                 (mapv (fn [s]
                         (if (= problem-uri (:problem-uri s))
                           {:problem-uri problem-uri
                            :success-rate (:success-rate event)
                            :execution-count (:execution-count event)
                            :updated-at (str (:updated-at event))}
                           s))
                       (or solves []))))))

(defmethod tree-profiles* :ontology/domain-knowledge-added
  [state event]
  (let [tree-id (:tree-id event)]
    (-> state
        (assoc-in [tree-id :tree-id] tree-id)
        (update-in [tree-id :domain-knowledge]
                   (fnil conj [])
                   {:id (:knowledge-id event)
                    :description (:description event)
                    :node-id (:node-id event)
                    :impact-score (:impact-score event)
                    :added-at (str (:added-at event))}))))

(defmethod tree-profiles* :default [state _] state)

(defn tree-profiles
  "Build tree profiles from events."
  [initial-state events]
  (reduce tree-profiles* initial-state events))

(defmulti descriptions*
  "Apply an event to the Living Description read-model state."
  (fn [_state event] (:event/type event)))

(defmethod descriptions* :default [state _] state)

(defn- apply-description-event
  "Generic projection: the event has :target-type (granularity), :target-id
   (granularity-specific key), :body (the description body), :recorded-at.
   Replaces :current with the new body; appends a versioned entry to :history."
  [state event]
  (let [granularity (:target-type event)
        target-id (:target-id event)
        body (:body event)
        recorded-at (:recorded-at event)
        history-entry {:body body
                       :recorded-at recorded-at
                       :event-id (:event/id event)}]
    (-> state
        (assoc-in [granularity target-id :current] body)
        (update-in [granularity target-id :history]
                   (fnil conj []) history-entry))))

(defmethod descriptions* :ontology/node-type-description-updated [state event]
  (apply-description-event state event))

(defmethod descriptions* :ontology/node-instance-description-updated [state event]
  (apply-description-event state event))

(defmethod descriptions* :ontology/tree-description-updated [state event]
  (apply-description-event state event))

(def ^:private confidence-support-scale
  "`k` in the derived-confidence curve `c(s) = s / (s + k)`. See
   `derive-confidence` for why the curve, and why k = 3."
  3)

(def ^:private enforcement-confidence-floor
  "The derived confidence a claim must reach before it is allowed to ENFORCE.

   0.6 is not a new number invented here: it is the waypoint `derive-confidence`
   is already calibrated around and the one CC-9's guard strength is specified
   against. Naming it makes the coupling explicit instead of leaving two
   literals to agree by coincidence."
  6/10)

(def ^:private validation-support-threshold
  "The spec's `validation_support_threshold` — the net support at which a claim
   that has post-guard evidence earns `:validated`. **It is 5.**

   DERIVED, NOT PLUCKED. It is the least integer support whose derived
   confidence clears `enforcement-confidence-floor`:

       c(s) >= f   <=>   s/(s+k) >= f   <=>   s >= f*k/(1-f)
       f = 0.6, k = 3  ->  s >= 4.5  ->  s = 5,  and c(5) = 0.625

   so `:status = :validated` implies `confidence >= 0.6` BY CONSTRUCTION, for
   whatever k the curve is ever re-calibrated to. The alternative — a literal 5
   sitting next to a literal 3 — makes that implication true today and silently
   false after the first tuning.

   WHY 0.6 IS THE RIGHT PLACE TO PUT THE LINE, which is where the judgement
   actually lives. Support is NET (corroborations minus contradictions, CC-2),
   so s = 5 means 'five more episodes agreed than disagreed', not 'five
   episodes were seen'.

   - LOWER (s = 3, c = 0.50) is precisely even odds — the state in which a
     claim must NOT be acting on retrieval. The forensic behind ADR 0021 found
     low-episode hypotheses to be the noisiest signal in the corpus; a
     threshold of 3 sits inside that noise, and under ADR 0022 a negative claim
     is a suppression lever, so the cost of a false positive is a muted
     behavior rather than a wrong sentence in a prompt.
   - HIGHER (s = 7, c = 0.70) means nothing enforces until a target has been
     consolidated seven net times. Consolidation is low-frequency by design, so
     that is the loop learning and never being permitted to act — the
     protection-induced starvation ADR 0021 exists to end, arriving as a
     threshold instead of as a whole-body validator.
   - AT 5 a thinly-corroborated claim stays fully VISIBLE to the model (it is
     still in the assembled body, still ranked by support, still readable via
     `get-claims`) and cannot mute anything. Visibility and enforcement are
     separated, which is the whole point of the status field.

   WHERE THAT SEPARATION IS ACTUALLY ENFORCED (CC-9a). Until CC-9a it was not:
   this docstring asserted it while `assemble-body` filtered on `:kind` alone,
   so an unproven guard reached EL-5's penalty through the body like any other.
   The threshold is now honoured at ONE seam — EL-2's `enrich-candidate-evidence`
   (ontology `interface`), which stamps the candidate with the
   `get-enforcing-claims` subset for `domain-penalty/avoid-strings` to read
   while `:avoid-when`/`:weaknesses` keep carrying everything for the render.
   A number that nothing reads is not a threshold; if that stamp is ever
   removed, this paragraph is false again.

   NOT A DEADBAND. Promotion and demotion share this one number, because the
   spec declares one threshold and a symmetric pair of transition edges. A
   claim oscillating across exactly 5 therefore flips status. Accepted rather
   than papered over: support moves only on consolidation events, which are
   rare, and a hysteresis band would be a second calibrated number the spec
   does not have and nothing has yet measured a need for."
  (long (Math/ceil (double (/ (* enforcement-confidence-floor confidence-support-scale)
                              (- 1 enforcement-confidence-floor))))))

(def ^:private initial-claim-support
  "The spec's `config.initial_claim_support`. A newly-added claim is seeded
   ABOVE the retirement floor. Support is EARNED from here: incremented by
   support/edit, decremented by contradict, and a claim retires only when a
   contradiction lands on support <= 1 (all CC-2).

   WHY 2 AND NOT 1. At a seed of 1 the very first `:contradict` retires a
   brand-new claim outright, which contradicts ADR 0021's own stated property
   that *no single consolidation and no single judgement can erase accumulated
   knowledge*. That is not theoretical: the CC-5 prototype (P-A) replayed the
   real reflection contract over 20 real consolidations and watched ONE
   consolidation retire SIX of a target's twelve claims in a single fold —
   because that evidence window happened to be all successes, so the model
   contradicted every negative claim at once. Two of those six had already been
   adjudicated as genuinely present. Anti-recency had re-entered through
   `:contradict`, wearing a different hat from the whole-body validator ADR
   0021 retired.

   At 2, one contradiction takes a fresh claim to 1 — visible, weakened, no
   longer enforcing — and a SECOND retires it. That is what \"accumulated
   contradiction\" means, and it is ExpeL's measured design (insights start at
   importance 2; removal requires accumulation).

   Consequence accepted deliberately: under CC-3's `c(s) = s/(s+k)` with k=3, a
   fresh claim now displays at 0.40 confidence rather than 0.25 and needs three
   further supports to validate rather than four. CC-7's threshold is DERIVED
   from the confidence floor, not from this seed, so it is unaffected."
  2)

(defn- claim-id-for
  "Deterministic identity for the claim created by the delta at `idx` of
   `event`. Derived from the recording event's id, which is permanent, so
   a rebuild from the log reproduces byte-identical claim ids — and so a
   later delta can name this claim by string as the spec's
   `ClaimDelta.target_claim` requires."
  [event idx]
  (str (:event/id event) "#" idx))

(def ^:private authored-basis
  "The spec's `EvidenceBasis.authored` — designer-written corpus knowledge.

   Named once because THREE spec constructs compare against this exact value
   (`ValidateAuthoredClaimAtCreation`, the `evidence_basis != authored`
   precondition of `DemoteUnderSupportedClaim`, and the widened
   `OnlyValidatedClaimsEnforce`). Three literal keywords agreeing by
   coincidence is how the exemption would half-disappear under a rename."
  :authored)

(defn- authored?
  "CC-9d: does this claim rest on AUTHORSHIP rather than on accrual?

   Reads the claim's own durable `:evidence-basis`, never a delta's — a claim's
   basis is set at creation and an edit preserves it, so a later delta cannot
   argue its way into (or out of) the exemption."
  [claim]
  (= authored-basis (:evidence-basis claim)))

(defn- add-claim
  "Apply an `:add` delta: create a claim carrying the episodes that produced it.
   `recorded-at` comes off the event (the command handler stamped it), never
   from a clock read inside the fold — a fold that reads the wall clock does not
   rebuild to the same state.

   CC-9d: `:evidence-basis` is recorded here and ONLY here — the spec's 'set at
   CREATION only'. It is stored verbatim from the delta (`nil` when the delta
   declared nothing) rather than normalised through the guard's
   `declared-basis`, so `nil` keeps meaning 'declared nothing' on the claim,
   which is exactly the optionality the spec's `evidence_basis: EvidenceBasis?`
   carries.

   STATUS AT CREATION is `:candidate` — EXCEPT for an authored basis, which is
   BORN `:validated` (spec `ValidateAuthoredClaimAtCreation`). A curated guard
   must enforce at full strength from day one; seeding it as a candidate would
   silently disarm the proven regression corpus at the moment of seeding.

   This is deliberately NOT routed through `with-earned-status`. The two paths
   stay distinct for the reason that function's docstring gives — so that
   raising `initial-claim-support` could never quietly mint pre-validated claims
   — and CC-9d does not weaken that: the ONLY thing that mints a validated claim
   here is an explicit, auditable declaration of authorship, which the
   consolidator forbids the reflection LLM from making."
  [claims delta claim-id recorded-at]
  (let [basis (:evidence-basis delta)]
    (conj (or claims [])
          {:claim-id               claim-id
           :kind                   (:kind delta)
           :content                (:content delta)
           :context-guard          (:context-guard delta)
           :recommendation         (:recommendation delta)
           :support                initial-claim-support
           :status                 (if (= authored-basis basis) :validated :candidate)
           :supporting-episodes    (vec (:episodes delta))
           :contradicting-episodes []
           :legacy-provenance      (boolean (:from-legacy-corpus delta))
           :evidence-basis         basis
           :created-at             recorded-at
           :updated-at             recorded-at})))

(defn- post-guard-episode-count
  "The spec's `post_guard_episode_count(claim)` — how many of this claim's
   supporting occurrences were RESOLVED against real judge evidence by CC-4's
   evidence guard.

   Every delta that reaches this fold has already passed the guard, so the
   question is not whether an episode was checked but whether the claim rests
   on any checked episode AT ALL. CC-4's `:from-legacy-corpus` arm is the
   discriminator: a legacy delta naming no occurrence is admitted by DECLARED
   PROVENANCE rather than by resolved judge evidence, contributes no episode
   here, and therefore contributes nothing that can validate.

   That is the second property this slice exists for, and it is not
   bureaucracy. Pre-guard evidence has unknown starvation content BY
   DEFINITION — CC-4 measured three starved judgements in a small historical
   corpus, one of which scored 1.0 while reasoning 'assuming the document
   exists…' against an empty input context. A backfilled claim may be visible,
   well-supported and sorted high, and still must not be able to suppress a
   behavior on the strength of evidence nobody can re-examine."
  [claim]
  (count (remove nil? (:supporting-episodes claim))))

(defn- earned-status
  "The spec's `ValidateWellSupportedClaim` rule AND its `validated ->
   candidate` transition edge, expressed as ONE total function of the claim's
   own state.

   Written as a recomputation rather than as two mutating branches, for three
   reasons:

   1. BOTH EDGES ARE DECLARED, so enforcement is continuously earned rather
      than granted once. A claim whose support decays back below the threshold
      returns to candidate — it does not keep a privilege it no longer merits.
   2. IT IS THE ONLY SHAPE IN WHICH NO THIRD STATUS IS REACHABLE. The range of
      this function is literally `#{:candidate :validated}`, so a future
      operation added to the fold cannot invent a status by forgetting a
      check.
   3. A REBUILD FROM THE LOG MUST REPRODUCE IT. Status derived from the
      claim's own support and episodes — both of which are themselves folded
      from permanent events — cannot drift from the events that produced them.

   The apparent asymmetry (promotion needs post-guard evidence, demotion is
   governed by support alone) is not one: `:supporting-episodes` never shrinks,
   because a contradiction files onto `:contradicting-episodes` instead, so the
   post-guard condition is monotone once satisfied.

   CC-9d ADDS ONE ARM: an AUTHORED claim is validated at every support level.
   That is the spec's `evidence_basis != authored` precondition on
   `DemoteUnderSupportedClaim`, expressed in the same total-function shape
   rather than as a branch bolted onto the demotion path — the range is still
   literally `#{:candidate :validated}`, and the exemption is still a pure
   function of the claim's own durable state.

   IT IS NOT IMMORTALITY, and the distinction matters. This function decides
   STATUS, not existence. Contradiction still decrements the claim's support on
   every disagreement, and `apply-claim-delta`'s retirement arm still removes it
   outright when that support is exhausted — the spec's own stated erosion path
   for authored knowledge. What authorship buys is that a curated guard is not
   quietly disarmed by sitting at a support number that was never a threshold
   count for it in the first place."
  [claim]
  (if (or (authored? claim)
          (and (>= (or (:support claim) 0) validation-support-threshold)
               (pos? (post-guard-episode-count claim))))
    :validated
    :candidate))

(defn- with-earned-status
  "Re-derive `:status` after an operation that changed a claim's support —
   the spec rule's `when: ClaimSupportChanged` trigger.

   `:add` deliberately does NOT route through here. A brand-new claim is
   `:candidate` by construction (CC-1), creation is not a support change, and
   keeping the two paths distinct means raising `initial-claim-support` could
   never quietly mint pre-validated claims."
  [claim]
  (assoc claim :status (earned-status claim)))

(def ^:private campaign-verdict-basis
  "RR-20: the evidence basis a shape claim's occurrence-corroboration
   reinforcement declares (`todo-processors/corroborate-worked-patterns-
   from-occurrence!`). Distinct from `authored-basis` above — a different
   axis (`reinforce-claim` below counts it; `earned-status`/`add-claim`
   never treat it specially)."
  :campaign-verdict)

(defn- reinforce-claim
  "CC-2, spec `ReinforceClaim`: a `:support` (or `:edit`) delta earns the
   claim one more unit of support and files the episode that earned it.
   The episodes are what make the support RESOLVABLE — a support count
   with no episodes behind it is an unfalsifiable number.

   CC-7: the earned support may cross the validation threshold, so status is
   re-derived here — after the episodes are filed, because the post-guard
   condition reads them.

   RR-20: when THIS delta declares `:evidence-basis :campaign-verdict`,
   `:verdict-corroborations` is incremented (defaulting an absent/nil prior
   value to 0) — the durable count of how many distinct RR-19 campaign
   verdicts have corroborated this shape, which
   `harvest/best-recommended-pattern` ranks on ahead of raw support. A
   `:support` delta that declares any OTHER (or no) basis leaves this field
   untouched — an ordinary re-emit of the same bookend is not a campaign
   verdict."
  [claim delta recorded-at]
  (cond-> (-> claim
              (update :support inc)
              (update :supporting-episodes (fnil into []) (:episodes delta))
              (assoc :updated-at recorded-at)
              (with-earned-status))
    (= campaign-verdict-basis (:evidence-basis delta))
    (update :verdict-corroborations (fnil inc 0))))

(defn- edit-claim
  "CC-2, spec `ReinforceClaim` for `delta.operation = edit`: an edit
   reinforces AND rewords.

   `:kind` is MUTABLE and that is the point. The forensic behind ADR 0021
   found that rephrasings systematically migrate between description
   sections (weakness→guard, strength→capability); treating that as a new
   claim is how accumulated knowledge got lost. Here the claim keeps its
   identity, its provenance and every unit of support it earned, and only
   its wording moves.

   `:context-guard` / `:recommendation` are optional on the delta, so they
   are refreshed only when the delta actually SUPPLIES one — an edit that
   is silent about them must not erase what an earlier delta established.

   CC-9d — `:evidence-basis` IS ABSENT FROM THIS FUNCTION ON PURPOSE, and the
   omission is load-bearing rather than an oversight. The spec says the basis is
   set at CREATION only and that an edit PRESERVES it. That is the
   anti-laundering rule: `:edit` is precisely the operation the reflection LLM
   uses to reword a claim while it keeps its identity and its earned support, so
   an edit that also re-set the basis would let an ordinary rewording of a
   curated guard silently drop it out of enforcement into weaker
   earned-evidence accounting — at a moment nobody is watching. It cuts both
   ways: it also stops a delta asserting its way INTO the curated corpus after
   the fact.

   DO NOT ADD `:evidence-basis` TO THE `assoc` BELOW. If you do, the failure is
   not even loud: `reinforce-claim` re-derives `:status` BEFORE this `assoc`
   runs, so the claim would be left with a status derived from the OLD basis and
   a NEW basis recorded beside it — an internally inconsistent claim that
   violates `OnlyValidatedClaimsEnforce`. Guarded by
   `cc9d-authored-evidence-basis-test`, both directions."
  [claim delta recorded-at]
  (cond-> (-> (reinforce-claim claim delta recorded-at)
              (assoc :content (:content delta)
                     :kind    (:kind delta)))
    (some? (:context-guard delta))  (assoc :context-guard (:context-guard delta))
    (some? (:recommendation delta)) (assoc :recommendation (:recommendation delta))))

(defn- contradict-claim
  "CC-2, spec `ContradictClaim`: disagreement is RECORDED, not resolved by
   whoever wrote last. One unit of support is withdrawn and the episode
   that disagreed is filed on `:contradicting-episodes` — deliberately NOT
   on `:supporting-episodes`, so the two ledgers stay readable against each
   other.

   Only ever called when `support > 1`; at `<= 1` the claim retires
   instead (spec `RetireExhaustedClaim`), which is the complementary arm.

   CC-7: this is the DEMOTION edge. Withdrawn support can carry a claim back
   under the validation threshold, and when it does the claim stops enforcing
   immediately — while remaining present and visible, because demotion is not
   deletion."
  [claim delta recorded-at]
  (-> claim
      (update :support dec)
      (update :contradicting-episodes (fnil into []) (:episodes delta))
      (assoc :updated-at recorded-at)
      (with-earned-status)))

(defn- retire-claim
  "CC-2, spec `RetireExhaustedClaim` — the ONLY path by which a claim
   leaves the projection, and never a single consolidation's decision: it
   fires when accumulated contradiction has exhausted the support a claim
   earned over many episodes.

   The claim VANISHES from `:claims` (so nothing downstream has to filter
   tombstones out of an assembled body) and the retirement FACT is filed on
   `:retired-claims`, carrying the claim exactly as it stood — including
   the contradiction that finished it. Nothing is erased: the deltas that
   built the claim and the delta that retired it are both permanent in the
   log, and a rebuild reproduces this same retirement."
  [claim delta recorded-at]
  {:claim      (-> claim
                   (update :contradicting-episodes (fnil into []) (:episodes delta))
                   (assoc :updated-at recorded-at))
   :reason     :support-exhausted
   :retired-at recorded-at})

(defn- resolve-claim-index
  "Position of the claim named by `:target-claim`, or nil when this target
   has no such claim — the fold tolerance CC-1 established, which CC-2
   makes load-bearing rather than merely defensive: now that claims can
   retire, a delta naming a claim that has since left is a NORMAL
   occurrence, not an error."
  [claims delta]
  (let [target-id (:target-claim delta)]
    (first (keep-indexed (fn [i c] (when (= target-id (:claim-id c)) i)) claims))))

(defn- apply-claim-delta
  "Fold ONE delta into a target's claim NODE — a map of `:claims` and
   `:retired-claims`. Returns the node unchanged for anything it cannot
   apply; never throws.

   Deletion is absent by construction: no branch here removes a claim
   except `retire-claim`, and that one is reachable only through
   `:contradict` against an already-exhausted claim. A delta whose
   `:target-claim` names a claim this target does not have STAYS a no-op —
   resolving it must not become a throw, because the event it came from
   can never be unwritten."
  [node [idx delta] event recorded-at]
  (let [{:keys [claims]} node
        op  (:operation delta)
        pos (when (not= :add op) (resolve-claim-index claims delta))]
    (cond
      (= :add op)
      (update node :claims add-claim delta (claim-id-for event idx) recorded-at)

      (or (nil? pos) (not (contains? #{:support :edit :contradict} op)))
      node

      (= :support op)
      (update-in node [:claims pos] reinforce-claim delta recorded-at)

      (= :edit op)
      (update-in node [:claims pos] edit-claim delta recorded-at)

      (> (:support (nth claims pos)) 1)
      (update-in node [:claims pos] contradict-claim delta recorded-at)

      :else
      (let [claim (nth claims pos)]
        (-> node
            (assoc :claims (into (vec (subvec claims 0 pos)) (subvec claims (inc pos))))
            (update :retired-claims (fnil conj [])
                    (retire-claim claim delta recorded-at)))))))

(defn- derive-confidence
  "Confidence DERIVED from earned support, never asserted by an LLM. This is
   the property ADR 0021 keeps the field for: the number now means something
   because it is a function of how many episodes corroborated the claim minus
   how many contradicted it (`:support` already nets those — CC-2).

   The curve is `c(s) = s / (s + k)` with k = 3:

       s=1 → 0.25   s=2 → 0.40   s=3 → 0.50   s=5 → 0.63
       s=7 → 0.70   s=12 → 0.80  s=27 → 0.90  s→∞ → 1.0 (never reached)

   Why this shape, given it silently governs which traits R-Inject injects
   (`format-seed-body` sorts by :confidence and keeps only the top two per
   candidate) and, after CC-9, how hard a guard suppresses retrieval:

   1. BOUNDED AND MONOTONE BY CONSTRUCTION, not by clamping. s/(s+k) is in
      (0,1) and strictly increasing for every s > 0, so
      `DescriptionConfidenceIsBounded` cannot be violated by any support value
      the fold can produce, and a `max`/`min` clamp — which would silently
      flatten a mis-scaled curve into a plateau and hide the bug — is not
      needed. (A defensive clamp is still applied, but it is dead code for
      every reachable support; see below.)

   2. CONCAVE — the second episode is worth far more than the twentieth. Ten
      repetitions of the same episode is not ten times the knowledge one gave;
      an unbounded or linear curve would let a chatty target out-shout a
      well-corroborated quiet one purely on volume.

   3. NEVER 1.0. No finite pile of episodes proves a claim about a
      probabilistic system. A curve that saturates at exactly 1 invites
      downstream code to treat a claim as a fact.

   4. THE CALIBRATION (k = 3) is where the judgement is, so state it plainly:
      k is the support at which confidence is 0.5 — 'three independent
      corroborations is even odds'. It puts a single-episode claim at 0.25,
      comfortably below anything that reads as advice, and crosses 0.6 at
      s = 5. A smaller k (k=1: one episode → 0.5) would let the
      single-episode hypotheses ADR 0021's forensic found to be the noisiest
      signal in the corpus dominate the R-Inject top-two. A larger k (k=10)
      would leave a genuinely well-established claim below 0.5 after a month
      of episodes and effectively mute the loop.

   NOTE ON THE 0.6 NUMBER: the brief describes 0.6 as R-Inject's 'display
   floor'. Read against the code, R-Inject's 0.6 (`min-seed-score` in
   orc-service `todo_processors.clj`) is a RERANK-SCORE floor on candidates,
   not a confidence floor — confidence there governs RANK (top-two per
   candidate), not admission. The curve is nonetheless calibrated so 0.6 is a
   meaningful waypoint (s=5), because CC-9's guard strength is specified
   against it."
  [support]
  (let [s (double (max 0 (or support 0)))]
    (-> (/ s (+ s (double confidence-support-scale)))
        (max 0.0)
        (min 1.0))))

(defn- claim-contents
  "The de-duplicated contents of every claim of `kind`, best-supported first —
   the shape the string-vector body sections take."
  [ranked-claims kind]
  (into [] (comp (filter #(= kind (:kind %))) (map :content) (distinct))
        ranked-claims))

(def ^:private inline-fn-placeholder
  "The literal `sanitize-tree-for-events` (G6, `rlm_tree_executor.clj`)
   writes in place of an already-compiled `:code` node's `:fn` before the
   tree becomes an event — a placeholder where the runnable logic was. A
   pattern carrying this text cannot be adopted (ADOPT requires runnable
   code), so it declares no bindings either: advertising a rebind for logic
   that is not there would be worse than advertising nothing."
  "<inline-fn>")

(defn- pattern-tree-node?
  [x]
  (and (vector? x) (keyword? (first x))))

(defn- pattern-node-opts
  "The first map among a node's args — every node type that carries
   `:reads`/`:writes`/`:keys` (`:llm`, `:code`, `:chunk-document`,
   `:aggregate`, `:map-each`, `:final`) puts it there; `:sequence` and
   `:parallel` have none."
  [args]
  (first (filter map? args)))

(defn- pattern-node-children
  "A node's child tree nodes, in source order — every arg that is itself a
   parseable tree node. Covers `:sequence`/`:parallel` (all args are
   children) and `:map-each` (opts, then one child) uniformly."
  [args]
  (filterv pattern-tree-node? args))

(defn- flatten-pattern-nodes
  "Every node in the tree, tree order, depth-first parent-before-children —
   the same left-to-right order the source text itself reads in."
  [node]
  (when (pattern-tree-node? node)
    (let [[node-type & args] node]
      (cons {:node-type node-type :opts (pattern-node-opts args)}
            (mapcat flatten-pattern-nodes (pattern-node-children args))))))

(defn- pattern-code-placeholder?
  [{:keys [node-type opts]}]
  (and (= :code node-type) (= inline-fn-placeholder (:fn opts))))

(defn- fold-pattern-bindings
  "Walk `nodes` in tree order, threading forward the set of keys WRITTEN so
   far: a node's `:reads` count as the pattern's external inputs only for
   the keys not already in that set (an earlier node's output, not an
   outside input). `:writes` accumulates every key written, in order.
   `:final`'s `:keys` become `:outputs`."
  [nodes]
  (reduce
   (fn [{:keys [written reads writes] :as acc} {:keys [node-type opts]}]
     (let [
           node-reads  (vec (distinct (concat (when-let [from (:from opts)] [from])
                                              (:reads opts))))
           node-writes (some-> (:writes opts) vec)
           external    (remove written node-reads)]
       (cond-> (assoc acc
                      :written (into written node-writes)
                      :reads   (into reads (remove (set reads) external))
                      :writes  (into writes node-writes))
         (= :final node-type) (assoc :outputs (some-> (:keys opts) vec)))))
   {:written #{} :reads [] :writes [] :outputs nil}
   nodes))

(defn pattern-key-bindings
  "Derive a pattern's declared key bindings from its EXACT source text (a
   `:recommended-pattern` / `:generated-tree-source` string): `:reads` — the
   keys the pattern reads before any node inside it wrote them, i.e. its
   external inputs; `:writes` — every key any node writes; `:outputs` — the
   `[:final {:keys […]}]` node's keys, when the pattern has one.

   Returns nil — never throws — for text that is not a string, that fails
   to parse as Clojure data, or whose code was elided (RR-22's placeholder
   check): an unusable pattern declares nothing rather than a binding that
   would mislead the model rebinding it."
  [source]
  (when (string? source)
    (try
      (let [parsed (binding [*read-eval* false] (read-string source))
            nodes  (seq (flatten-pattern-nodes parsed))]
        (when (and nodes (not-any? pattern-code-placeholder? nodes))
          (let [{:keys [reads writes outputs]} (fold-pattern-bindings nodes)]
            (cond-> {:reads reads :writes writes}
              (seq outputs) (assoc :outputs outputs)))))
      (catch Exception _ nil))))

(defn- principle-entry
  "One `:strengths` / `:weaknesses` entry, principle-shaped per the shipped
   `schemas/principle-entry`: an actionable trait, its context guard, its
   advice, plus the derived weight signal.

   `guard-key` / `advice-key` differ by section — strengths carry
   `:good-when` + `:recommended-pattern`, weaknesses carry `:avoid-when` +
   `:recommended-alternative` — which is exactly the branch R-Inject's
   `format-principle-entry` and EL-5's `avoid-strings` take. Both are
   `{:optional true} :string` (not `:maybe`) on the shipped schema, so a nil
   guard must be ABSENT, not present-and-nil.

   RR-20: `:verdict-corroborations` is forwarded ONLY when positive — a
   claim never corroborated by an RR-19 campaign verdict carries no such
   key at all, matching every claim recorded before this slice and keeping
   the additive-field contract exact (existing consumers see byte-identical
   shapes for every OTHER field).

   RR-22: `:pattern-reads`/`:pattern-writes`/`:pattern-outputs` are
   forwarded ONLY for the strengths branch (`advice-key = :recommended-
   pattern` — a worked-example DSL; weaknesses' `:recommended-alternative`
   is prose, not a pattern to parse) and ONLY when the pattern's exact
   source actually parses. Additive: every other field, and any entry
   whose pattern does not parse, is unchanged."
  [claim guard-key advice-key]
  (let [pattern-text (when (= advice-key :recommended-pattern) (:recommendation claim))
        bindings     (some-> pattern-text pattern-key-bindings)]
    (cond-> {:trait          (:content claim)
             :confidence     (derive-confidence (:support claim))
             :evidence-count (count (:supporting-episodes claim))}
      (:context-guard claim)  (assoc guard-key (:context-guard claim))
      (:recommendation claim) (assoc advice-key (:recommendation claim))
      (:created-at claim)     (assoc :first-observed-at (:created-at claim))
      (:updated-at claim)     (assoc :last-reinforced-at (:updated-at claim))
      (pos? (or (:verdict-corroborations claim) 0))
      (assoc :verdict-corroborations (:verdict-corroborations claim))
      bindings (assoc :pattern-reads (:reads bindings) :pattern-writes (:writes bindings))
      (:outputs bindings) (assoc :pattern-outputs (:outputs bindings)))))

(def ^:private empty-body-summary
  "What `:summary` says for a target whose claim set is empty — reachable
   when every delta in an event was a no-op, or when the last surviving claim
   retired. Honest rather than blank: `:summary` is what ColBERT indexes
   today, and an empty string would index as a match for everything."
  "No claims have been recorded for this target yet.")

(defn- assemble-summary
  "Derive `:summary` from the claims themselves.

   `:summary` is THE ColBERT retrieval key — permanently, not provisionally.
   CC-8 would have replaced it with a synthesised `retrieval_description`;
   CC-8 is CLOSED, not built (ADR 0026: its stated goal measured false and its
   premise was never in the literature). Nothing is scheduled to demolish this,
   so 'don't invest in the phrasing, it's going away' is no longer a reason for
   anything.

   Consumers read it directly (R-Inject's `derive-seed-name` parses it, EL-2's
   terse candidates carry it as `:content`), so it cannot be blank. What it
   must be is FAITHFUL: every sentence is claim content, nothing is invented,
   and the ordering is the support ordering, so the best-corroborated content
   leads. That faithfulness constraint — not an impending replacement — is what
   keeps the assembly mechanical."
  [{:keys [capabilities representative-uses strengths weaknesses avoid-when]}]
  (let [section (fn [label items]
                  (when (seq items) (str label ": " (str/join "; " items) ".")))
        parts (remove nil?
                      [(section "Capabilities" capabilities)
                       (section "Representative uses" representative-uses)
                       (section "Strengths" (map :trait strengths))
                       (section "Known weaknesses" (map :trait weaknesses))
                       (section "Avoid when" avoid-when)])]
    (if (seq parts) (str/join " " parts) empty-body-summary)))

(def ^:private carried-body-keys
  "Body fields that are NOT derivable from claims and must survive an
   assembly, because they are graph/identity metadata rather than knowledge:
   the SKOS hierarchy axes (`:parent-tree-id`, `:parent-behavior`), the
   retrieval dimension (`:scope`), the behavior→shell edges
   (`:composes-into`) and the consolidator's inferred behavioral ids.

   Dropping these would silently unparent a tree-class the first time a claim
   landed on it — the assembled body replaces the KNOWLEDGE, not the target's
   place in the graph."
  [:parent-tree-id :scope :composes-into :parent-behavior :behavioral-subtree-ids])

(defn- assemble-body
  "The spec's `assemble_body(description.claims)` — a pure function of the
   claim set (plus the carried-forward graph metadata and the previous
   version number). Called from the fold, so it must never read a clock or
   any state outside its arguments: a rebuild from the log has to reproduce
   this body exactly."
  [claims prev-body evidence-event-count]
  (let [ranked     (read-models-by-earned-support claims)
        of-kind    (fn [k] (filter #(= k (:kind %)) ranked))
        sections   {:capabilities        (claim-contents ranked :capability)
                    :representative-uses (claim-contents ranked :representative-use)
                    :avoid-when          (claim-contents ranked :guard)
                    :strengths           (mapv #(principle-entry % :good-when :recommended-pattern)
                                               (of-kind :strength))
                    :weaknesses          (mapv #(principle-entry % :avoid-when :recommended-alternative)
                                               (of-kind :weakness))}
        prev-version (:version prev-body)]
    (merge (select-keys (or prev-body {}) carried-body-keys)
           sections
           {:summary (assemble-summary sections)
            :version (inc (if (int? prev-version) prev-version 0))
            :consolidated-from-event-count (or evidence-event-count 0)})))

(defmethod descriptions* :ontology/claim-deltas-recorded
  [state event]
  (let [granularity (:granularity event)
        target-id   (:target-identifier event)
        recorded-at (:recorded-at event)
        path        [granularity target-id]
        node        (reduce (fn [acc indexed-delta]
                              (apply-claim-delta acc indexed-delta event recorded-at))
                            {:claims         (or (get-in state (conj path :claims)) [])
                             :retired-claims (or (get-in state (conj path :retired-claims)) [])}
                            (map-indexed vector (:deltas event)))
        assembled   (assemble-body (:claims node)
                                   (get-in state (conj path :current))
                                   (:evidence-event-count event))]
    (-> state
        (assoc-in (conj path :claims) (:claims node))
        (assoc-in (conj path :retired-claims) (:retired-claims node))
        (assoc-in (conj path :current) assembled)
        (assoc-in (conj path :updated-at) recorded-at)
        (update-in (conj path :history) (fnil conj [])
                   {:body assembled :recorded-at recorded-at :event-id (:event/id event)})
        (update-in (conj path :claim-set-version) (fnil inc 0)))))

(defn descriptions
  "Build the Living Description state from a seq of events."
  [initial-state events]
  (reduce descriptions* initial-state events))

(def default-consolidation-threshold
  "Default delta-since-last-consolidation count that triggers a
   consolidation request when no per-target-type override has been set."
  10)

(defmulti consolidation-thresholds*
  "Apply an event to the threshold-config state map.
   State: {target-type → int}."
  (fn [_state event] (:event/type event)))

(defmethod consolidation-thresholds* :default [state _] state)

(defmethod consolidation-thresholds* :ontology/consolidation-threshold-set
  [state event]
  (assoc state (:target-type event) (:threshold event)))

(defn consolidation-thresholds
  "Build the threshold-config state from a seq of events."
  [initial-state events]
  (reduce consolidation-thresholds* initial-state events))

(defmulti living-description-enabled*
  (fn [_state event] (:event/type event)))

(defmethod living-description-enabled* :default [state _] state)

(defmethod living-description-enabled* :ontology/living-description-enabled-set
  [state event]
  (assoc state :enabled? (boolean (:enabled? event))))

(defn living-description-enabled
  "Build the opt-in flag state from a seq of events."
  [initial-state events]
  (reduce living-description-enabled* initial-state events))

(def default-consolidation-budget
  "Default consolidations-per-hour-per-target-type allowed when no
   per-target-type override has been set."
  100)

(defmulti consolidation-budgets*
  (fn [_state event] (:event/type event)))

(defmethod consolidation-budgets* :default [state _] state)

(defmethod consolidation-budgets* :ontology/consolidation-budget-set
  [state event]
  (assoc state (:target-type event) (:budget event)))

(defn consolidation-budgets
  "Build the budget-config state from a seq of events."
  [initial-state events]
  (reduce consolidation-budgets* initial-state events))

(def provider-context-token-limit
  "The provider's context ceiling, proved VERBATIM by CC-21 from the real
   rejection message: 'This endpoint's maximum context length is 1048576
   tokens.' (CC21-MEASUREMENT.md §1 — the chars/4 pre-flight identity was
   proved against this same number to within 0.4%.)"
  1048576)

(def smallest-timeout-class-prompt-tokens
  "The floor of the MEASURED failing prompt-size class under the executor's
   default 300,000 ms deadline (orc/execute :timeout-ms): the E-w20 anchor,
   574,914 provider-measured prompt tokens. Prompts of this class are
   failure-dominated today — W39: 0/5 claim-path outcomes since 08-14 with
   3 :timeout at ~600K-token prompts; W25: marker OMISSION observed at the
   same scale. (E-w20 itself completed once in P-E's era; its class no
   longer does.)"
  574914)

(def largest-clean-anchor-prompt-tokens
  "The largest of CC-21's 10 provider anchors BELOW the failing class:
   A-w500, 327,681 provider-measured prompt tokens — accepted, answered,
   no recorded failure mode at any deadline."
  327681)

(def default-evidence-token-budget
  "The DERIVED default evidence budget (predicted prompt tokens) — the
   derivation IS the value, never a bare number:

     (min (quot provider-context-token-limit 2)   ; = 524,288
          largest-clean-anchor-prompt-tokens)     ; = 327,681
     => 327,681

   Ceiling 1 — provider, halved: CC-21's two-ceiling finding is that the
   chars/4 pre-flight gate under-reads real tokens by up to 50% on dense
   (2.0 chars/token) payload, so a prompt at half the nominal limit is the
   largest one that cannot cross the REAL tokenizer ceiling even at maximum
   measured density — and it leaves the other half of the context for the
   completion.

   Ceiling 2 — acceptable generation time: under the executor's default
   300,000 ms deadline the ~575-600K-token class is measured
   failure-dominated (W39 timeouts, W25 marker omission — see
   `smallest-timeout-class-prompt-tokens`), so the time-derived ceiling is
   the largest banked prompt measured CLEAN below that class:
   `largest-clean-anchor-prompt-tokens`.

   The min binds at ceiling 2. Raising it is an operator decision through
   :ontology/set-evidence-token-budget, with the derivation above as the
   thing being consciously overridden."
  (min (quot provider-context-token-limit 2)
       largest-clean-anchor-prompt-tokens))

(defmulti evidence-token-budget*
  (fn [_state event] (:event/type event)))

(defmethod evidence-token-budget* :default [state _] state)

(defmethod evidence-token-budget* :ontology/evidence-token-budget-set
  [_state event]
  {:budget-tokens (:budget-tokens event)})

(defn evidence-token-budget
  "Build the evidence-token-budget config state from a seq of events."
  [initial-state events]
  (reduce evidence-token-budget* initial-state events))

(defmulti reflection-evidence-exclusions*
  "State: {[target-type target-id] [exclusion-entry ...]} — entries oldest
   first, one per consolidation-with-exclusions."
  (fn [_state event] (:event/type event)))

(defmethod reflection-evidence-exclusions* :default [state _event] state)

(defmethod reflection-evidence-exclusions* :ontology/reflection-evidence-excluded
  [state event]
  (update state [(:target-type event) (:target-id event)]
          (fnil conj [])
          (cond-> {:budget-tokens (:budget-tokens event)
                   :selected-count (:selected-count event)
                   :excluded-count (:excluded-count event)
                   :predicted-prompt-tokens (:predicted-prompt-tokens event)
                   :oldest-excluded-at (:oldest-excluded-at event)
                   :newest-excluded-at (:newest-excluded-at event)
                   :excluded-at (:excluded-at event)}
            (some? (:request-id event)) (assoc :request-id (:request-id event)))))

(defn reflection-evidence-exclusions
  "Build the reflection-evidence-exclusions state from a seq of events."
  [initial-state events]
  (reduce reflection-evidence-exclusions* initial-state events))

(def default-reindex-config
  {:reindex-threshold-events 10
   :reindex-timer-minutes 5})

(defmulti reindex-config*
  (fn [_state event] (:event/type event)))

(defmethod reindex-config* :default [state _] state)

(defmethod reindex-config* :ontology/reindex-config-set
  [_state event]
  {:reindex-threshold-events (:reindex-threshold-events event)
   :reindex-timer-minutes (:reindex-timer-minutes event)})

(defn reindex-config
  "Build the re-index config state from a seq of events."
  [initial-state events]
  (reduce reindex-config* initial-state events))

(def initial-reindex-state
  {:events-since-last-rebuild 0
   :last-rebuild-timestamp nil
   :index-built? false})

(defmulti reindex-state*
  (fn [_state event] (:event/type event)))

(defmethod reindex-state* :default [state _] state)

(defmethod reindex-state* :ontology/node-type-description-updated [state _event]
  (update state :events-since-last-rebuild (fnil inc 0)))

(defmethod reindex-state* :ontology/node-instance-description-updated [state _event]
  (update state :events-since-last-rebuild (fnil inc 0)))

(defmethod reindex-state* :ontology/tree-description-updated [state _event]
  (update state :events-since-last-rebuild (fnil inc 0)))

(defmethod reindex-state* :ontology/claim-deltas-recorded [state _event]
  (update state :events-since-last-rebuild (fnil inc 0)))

(defmethod reindex-state* :colbert/index-created [state event]
  (if (= "ontology-descriptions" (:index-name event))
    (assoc state
           :events-since-last-rebuild 0
           :last-rebuild-timestamp (str (or (:event/timestamp event)
                                            (java.time.Instant/now)))
           :index-built? true)
    state))

(defn reindex-state
  "Build the re-index state from a seq of events."
  [initial-state events]
  (reduce reindex-state* (or initial-state initial-reindex-state) events))

(defmulti recent-consolidations*
  (fn [_state event] (:event/type event)))

(defmethod recent-consolidations* :default [state _] state)

(defn- record-consolidation-timestamp [state event]
  (let [target-type (:target-type event)
        ts (or (some-> event :event/timestamp str) (str (java.time.Instant/now)))]
    (update state target-type (fnil conj []) ts)))

(defmethod recent-consolidations* :ontology/node-type-description-updated [state event]
  (record-consolidation-timestamp state event))

(defmethod recent-consolidations* :ontology/node-instance-description-updated [state event]
  (record-consolidation-timestamp state event))

(defmethod recent-consolidations* :ontology/tree-description-updated [state event]
  (record-consolidation-timestamp state event))

(defmethod recent-consolidations* :ontology/claim-deltas-recorded [state event]
  (record-consolidation-timestamp state (assoc event :target-type (:granularity event))))

(defmethod recent-consolidations* :ontology/description-consolidation-failed [state event]
  (record-consolidation-timestamp state (assoc event :target-type (:granularity event))))

(defn recent-consolidations
  "Build the recent-consolidations state from a seq of events."
  [initial-state events]
  (reduce recent-consolidations* initial-state events))

(defn ts->instant [^String s]
  (try (java.time.Instant/parse s)
       (catch Exception _
         (try (.toInstant (java.time.OffsetDateTime/parse s))
              (catch Exception _ nil)))))

(def consolidation-outcome-event-types
  "Every event type that ANSWERS a consolidation request: deltas recorded,
   the ClaimSetUnchanged exclusion shape, a stale refusal, a failure record,
   a description update, or the budget gate's skip."
  #{:ontology/claim-deltas-recorded
    :ontology/promotion-evidence-excluded
    :ontology/claim-deltas-refused
    :ontology/description-consolidation-failed
    :ontology/node-type-description-updated
    :ontology/node-instance-description-updated
    :ontology/tree-description-updated
    :ontology/consolidation-skipped})

(defn consolidation-request-ledger*
  "Apply one event to the ledger state:
   {:requests {request-id {:target-type … :target-id … :requested-at <str>}}
    :outcomes {request-id [{:event-type … :at <str> (:reason …)} …]}}"
  [state event]
  (if (= :ontology/consolidation-requested (:event/type event))
    (assoc-in state [:requests (:event/id event)]
              {:target-type (:target-type event)
               :target-id (:target-id event)
               :requested-at (str (:event/timestamp event))})
    (if-let [rid (:request-id event)]
      (update-in state [:outcomes rid]
                 (fnil conj [])
                 (cond-> {:event-type (:event/type event)
                          :at (str (:event/timestamp event))}
                   (some? (:reason event)) (assoc :reason (:reason event))))
      state)))

(defn consolidation-request-ledger
  "Build the ledger state from a seq of events."
  [initial-state events]
  (reduce consolidation-request-ledger* initial-state events))

(defn- bump-counter
  "Increment both :delta and :total at the given target path."
  [state path]
  (-> state
      (update-in (conj path :delta) (fnil inc 0))
      (update-in (conj path :total) (fnil inc 0))))

(defmulti consolidation-delta-counters*
  (fn [_state event] (:event/type event)))

(defmethod consolidation-delta-counters* :default [state _] state)

(defmethod consolidation-delta-counters* :sheet/node-execution-completed
  [state event]
  (let [node-type (:node-type event)
        sheet-id  (:sheet-id event)
        node-id   (:node-id event)]
    (cond-> state
      (some? node-type)
      (bump-counter [:node-type node-type])

      (and (some? sheet-id) (some? node-id))
      (bump-counter [:node-instance [sheet-id node-id]]))))

(defmethod consolidation-delta-counters* :sheet/rlm-tree-execution-completed
  [state event]
  (if-let [fp (:tree-fingerprint event)]
    (bump-counter state [:tree-fingerprint fp])
    state))

(defmethod consolidation-delta-counters* :ontology/tree-class-occurrence-recorded
  [state event]
  (if-let [tree-class-id (:assigned-tree-id event)]
    (bump-counter state [:tree-class tree-class-id])
    state))

(defmethod consolidation-delta-counters* :ontology/consolidation-requested
  [state event]
  (let [target-type (:target-type event)
        target-id   (:target-id event)]
    (assoc-in state [target-type target-id :delta] 0)))

(defn consolidation-delta-counters
  "Build the delta-counter state from a seq of events."
  [initial-state events]
  (reduce consolidation-delta-counters* initial-state events))

(def recent-occurrence-bound
  "How many of a class's most recent occurrence keys the read-model retains.
   CC-22's lesson: anything that could grow with history must be bounded
   DELIBERATELY, with the bound argued rather than picked.

   4 x max(dimension_window 10, consistency_window 5) = 40.

   The factor exists because the retained sequence counts OCCURRENCES while
   the dimension window counts SCORED occurrences, and a third of real
   occurrences carry no judge signal at all (measured, CC-24a: 51 of the
   durable dominant class's 156 occurrences are unscored — 32.7% — because
   `ExcludeAbstainedEvaluations` skips rather than zeroes). Measured on that
   real stream, the worst case needs **29 consecutive occurrences** to contain
   ten scored ones; 40 clears that worst case with room, and is still O(1) per
   class. Pinned by cc24b-recent-occurrence-state-is-bounded, which fails if a
   future window makes the bound too small to compute its own answer."
  40)

(defn- conj-recent-occurrence
  "Append `occurrence-key` to a class's bounded recent-occurrence sequence,
   preserving occurrence order and dropping the oldest past the bound. An
   occurrence already inside the retained window is not re-appended (a
   re-delivery of the SAME verdict fact is one occurrence, exactly as
   harvest/occurrence-scores' `distinct` treats it).

   The trim COPIES rather than `subvec`s, and that is load-bearing: `subvec`
   returns a VIEW that pins the whole underlying vector alive, and `conj` on a
   SubVector appends to that underlying vector, so a subvec-based trim keeps a
   window of 40 over a root that grows without limit. Measured directly: 200
   folded keys leave (count v) = 40 with an underlying root of 200. That is
   the CC-22 hazard wearing a bound's clothing — the count says bounded while
   the retention is not. `into []` builds a fresh 40-element vector, which is
   O(bound) work once per event past the bound and genuinely bounded
   retention."
  [v occurrence-key]
  (let [v (or v [])]
    (if (some #(= % occurrence-key) v)
      v
      (let [v (conj v occurrence-key)]
        (if (> (count v) recent-occurrence-bound)
          (into [] (take-last recent-occurrence-bound v))
          v)))))

(defmulti tree-class-judge-averages*
  (fn [_state event] (:event/type event)))

(defmethod tree-class-judge-averages* :default [state _] state)

(defmethod tree-class-judge-averages* :ontology/task-classified
  [state event]
  (if-let [class-id (:assigned-tree-id event)]
    (-> state
        (assoc-in [:sheet->class (:source-sheet-id event)] class-id)
        (assoc-in [:occurrence->class [(:source-sheet-id event) (:source-tick-id event)]] class-id))
    state))

(defmethod tree-class-judge-averages* :ontology/tree-class-occurrence-recorded
  [state event]
  (if-let [class-id (:assigned-tree-id event)]
    (update-in state [:class->recent-occurrences class-id]
               conj-recent-occurrence
               [(:source-sheet-id event) (:source-tick-id event)])
    state))

(defmethod tree-class-judge-averages* :judge/score-emitted
  [state event]
  (let [{:keys [sheet-id tick-id judge-name score]} event]
    (if (and sheet-id tick-id judge-name (number? score))
      (-> state
          (update-in [:sheet-judge [sheet-id tick-id] judge-name :sum] (fnil + 0.0) score)
          (update-in [:sheet-judge [sheet-id tick-id] judge-name :count] (fnil inc 0)))
      state)))

(defn tree-class-judge-averages-projection
  "Join the two accumulated maps into {class-id -> {judge-name -> {:sum :count}}}.
   SJ-1: joined by [sheet-id tick-id] OCCURRENCE identity (via :occurrence->class),
   not bare sheet-id — a static task-shape's sheet-id is shared across every
   turn, so a bare-sheet-id join would misattribute every occurrence's score
   onto whichever class the sheet was MOST RECENTLY (re)classified to. A score
   whose (sheet-id, tick-id) has no matching classification yet is excluded."
  [state]
  (let [{:keys [occurrence->class sheet-judge]} state]
    (reduce-kv
      (fn [acc occurrence-key judges]
        (if-let [class-id (get occurrence->class occurrence-key)]
          (reduce-kv
            (fn [a judge-name {:keys [sum count]}]
              (-> a
                  (update-in [class-id judge-name :sum] (fnil + 0.0) (or sum 0.0))
                  (update-in [class-id judge-name :count] (fnil + 0) (or count 0))))
            acc judges)
          acc))
      {} sheet-judge)))

(defn tree-class-recent-judge-scores-projection
  "CC-24b: {judge-name -> [score ...]} for one class, in OCCURRENCE order, over
   the retained recent-occurrence window. Each entry is that judge's score at
   that occurrence (its per-occurrence mean when a judge scored the same
   occurrence more than once) — the raw material of the dimension axis's
   trailing mean.

   Occurrences the class no longer owns are skipped: :occurrence->class is
   consulted per key exactly as the lifetime projection does, so a
   re-classification onto a sibling class cannot leak scores across classes
   (SJ-1). An occurrence with no judge score contributes nothing to any
   judge's sequence — an unscored occurrence is not a zero (the spec's
   ExcludeAbstainedEvaluations)."
  [state tree-class-id]
  (let [{:keys [occurrence->class sheet-judge class->recent-occurrences]} state]
    (reduce
      (fn [acc occurrence-key]
        (if (= tree-class-id (get occurrence->class occurrence-key))
          (reduce-kv
            (fn [a judge-name {:keys [sum count]}]
              (if (and (number? sum) (number? count) (pos? count))
                (update a judge-name (fnil conj []) (/ sum (double count)))
                a))
            acc (get sheet-judge occurrence-key))
          acc))
      {} (get class->recent-occurrences tree-class-id []))))

(defmulti node-experiences*
  "Apply event to node experiences read model.
   State: {node-type -> {pattern-type -> {:effective [...] :ineffective [...]}}}
   Aggregates across all nodes of the same type."
  (fn [_state event] (:event/type event)))

(defmethod node-experiences* :ontology/node-pattern-learned
  [state event]
  (let [node-type (:node-type event)
        pattern-type (:pattern-type event)
        category (if (:effective? event) :effective :ineffective)]
    (update-in state [node-type pattern-type category]
               (fnil conj [])
               {:pattern (:pattern-description event)
                :metrics (:metrics event)
                :evidence-count (count (:evidence-trace-ids event))
                :node-id (:node-id event)
                :sheet-id (:sheet-id event)
                :learned-at (str (:learned-at event))})))

(defmethod node-experiences* :default [state _] state)

(defn node-experiences
  "Build node experiences from events (aggregated by node-type)."
  [initial-state events]
  (reduce node-experiences* initial-state events))

(defmulti tree-profile-embeddings*
  "Apply event to tree profile embeddings read model.
   State: {tree-id -> {:embedding [...] :text-embedded ...}}"
  (fn [_state event] (:event/type event)))

(defmethod tree-profile-embeddings* :ontology/tree-profile-embedded
  [state event]
  (assoc state (:tree-id event)
         {:tree-id (:tree-id event)
          :embedding (:embedding event)
          :text-embedded (:text-embedded event)
          :model-id (:model-id event)
          :embedded-at (str (:embedded-at event))}))

(defmethod tree-profile-embeddings* :default [state _] state)

(defn tree-profile-embeddings
  "Build tree profile embeddings from events."
  [initial-state events]
  (reduce tree-profile-embeddings* initial-state events))

(defmulti embedding-config*
  "Apply event to embedding config read model.
   State: {scope -> {:model-id ... :dimensions ...}}"
  (fn [_state event] (:event/type event)))

(defmethod embedding-config* :ontology/embedding-model-configured
  [state event]
  (assoc state (:scope event)
         {:model-id (:model-id event)
          :dimensions (:dimensions event)
          :configured-at (str (:configured-at event))}))

(defmethod embedding-config* :default [state _] state)

(defn embedding-config
  "Build embedding configuration from events."
  [initial-state events]
  (reduce embedding-config* initial-state events))

(def ontology-colbert-events
  "Events that track ontology-to-ColBERT index mappings."
  #{:evolutionary/colbert-indexed
    :evolutionary/colbert-index-updated})

(defn ontology-colbert-indexes*
  "Reducer for ontology-colbert-indexes read model.
   Tracks which ColBERT index is associated with each ontology.

   NB: events arrive with :event/type and their body flattened to the top level
   (v3 event store), exactly like every other read model here — NOT as a nested
   {:type :body}. Reading :type/:body meant this projection never fired."
  [state event]
  (case (:event/type event)
    :evolutionary/colbert-indexed
    (assoc state (:ontology-id event)
           {:colbert-index-id (:index-id event)
            :index-name (:index-name event)
            :colbert-fields (vec (:colbert-fields event))
            :document-count (:document-count event)
            :indexed-at (:indexed-at event)})

    :evolutionary/colbert-index-updated
    (update state (:ontology-id event) merge
            {:colbert-index-id (:index-id event)
             :updated-at (:updated-at event)})

    state))

(def ontology-embedding-events
  "Events that track ontology embedding state."
  #{:evolutionary/concepts-embedded
    :evolutionary/concepts-embedding-updated})

(defn ontology-embedding-state*
  "Reducer for ontology-embedding-state read model.
   Tracks which concepts have been embedded for each ontology."
  [state {:keys [type body]}]
  (case type
    :evolutionary/concepts-embedded
    (assoc state (:ontology-id body)
           {:embedded? true
            :build-id (:build-id body)
            :embedded-count (:embedded-count body)
            :embedding-fields (vec (:embedding-fields body))
            :model-id (:model-id body)
            :embedded-at (:embedded-at body)})

    :evolutionary/concepts-embedding-updated
    (update state (:ontology-id body)
            (fn [existing]
              (merge existing
                     {:embedded? true
                      :embedded-count (:total-embedded-count body)
                      :embedding-fields (vec (:embedding-fields body))
                      :updated-at (:updated-at body)})))

    state))

(defmulti learned-rules*
  "Apply event to learned rules read model.
   State: {tree-id -> [rule ...]}"
  (fn [_state event] (:event/type event)))

(defmethod learned-rules* :ontology/learned-rule-extracted
  [state event]
  (let [tree-id (:tree-id event)
        rule {:rule-id (:rule-id event)
              :condition (get-in event [:rule :condition])
              :action (get-in event [:rule :action])
              :confidence (get-in event [:rule :confidence])
              :success-rate (get-in event [:rule :success-rate])
              :evidence-episodes (vec (get-in event [:rule :evidence-episodes] []))
              :problem-type (:problem-type event)
              :domain-type (:domain-type event)
              :extracted-at (str (:extracted-at event))}]
    (update state tree-id (fnil conj []) rule)))

(defmethod learned-rules* :default [state _] state)

(defn learned-rules
  "Build learned rules from events."
  [initial-state events]
  (reduce learned-rules* initial-state events))

(defmulti site-registry*
  "Apply event to site registry read model.
   State: {:by-domain {domain -> site}
           :by-trust [domains sorted by trust]
           :patterns {domain -> [patterns]}}"
  (fn [_state event] (:event/type event)))

(defmethod site-registry* :site/registered
  [state event]
  (let [{:keys [site-id domain display-name category discovered-via
                url-pattern requires-headed known-challenges notes registered-at]} event
        site {:site-id site-id
              :domain domain
              :display-name display-name
              :category category
              :discovered-via discovered-via
              :url-pattern url-pattern
              :requires-headed (boolean requires-headed)
              :known-challenges (vec (or known-challenges []))
              :notes notes
              :trust-score 0.5
              :extraction-count 0
              :registered-at (str registered-at)}]
    (-> state
        (assoc-in [:by-domain domain] site)
        (update :by-trust (fn [domains]
                            (->> (conj (or domains []) domain)
                                 (sort-by (fn [d]
                                            (- (get-in state [:by-domain d :trust-score] 0.5))))
                                 vec))))))

(defmethod site-registry* :site/trust-updated
  [state event]
  (let [{:keys [domain trust-score extraction-count
                last-success-at last-failure-at updated-at]} event]
    (-> state
        (update-in [:by-domain domain] merge
                   {:trust-score trust-score
                    :extraction-count extraction-count
                    :last-success-at last-success-at
                    :last-failure-at last-failure-at})
        (update :by-trust (fn [domains]
                            (->> (or domains [])
                                 (sort-by (fn [d]
                                            (- (get-in state [:by-domain d :trust-score] 0.5))))
                                 vec))))))

(defmethod site-registry* :site/pattern-learned
  [state event]
  (let [{:keys [domain pattern-type pattern-data confidence learned-at]} event
        pattern {:pattern-type pattern-type
                 :pattern-data pattern-data
                 :confidence confidence
                 :learned-at (str learned-at)}]
    (update-in state [:patterns domain] (fnil conj []) pattern)))

(defmethod site-registry* :default [state _] state)

(defn site-registry
  "Build site registry from events."
  [initial-state events]
  (reduce site-registry* initial-state events))

;; --- Colbert indexer ---

(defn- require-colbert
  "Resolve a fn from the colbert component, or throw a clear error.
   colbert-indexer's index/search functions inherently need the colbert
   component (an optional Layer-5 upgrade). This namespace LOADS without
   colbert on the classpath — so the ontology builds colbert-free — but
   calling these functions without it is a clear, named error rather than
   an obscure NPE."
  [fn-name]
  (or (integrations/capability :colbert (keyword fn-name))
      (throw (ex-info (str "ColBERT indexing requires the colbert component on the "
                           "classpath (optional Layer-5 upgrade — see COMPONENT-MAP.md).")
                      {:fn fn-name}))))

(defn- format-field-value
  "Format a field value for document content."
  [value]
  (cond
    (nil? value) nil
    (string? value) (when-not (str/blank? value) value)
    (sequential? value) (when (seq value) (str/join ", " value))
    (map? value) (pr-str value)
    :else (str value)))

(defn- format-field-with-label
  "Format a field with its name as a label."
  [field-name value]
  (when-let [formatted (format-field-value value)]
    (str (-> field-name name str/upper-case (str/replace #"[-_]" " ")) ": " formatted)))

(defn build-document
  "Build a single ColBERT document from a concept and its fields.

   Args:
     concept: Concept map with :uri and various fields
     colbert-fields: Vector of field keywords to include
     opts: {:include-field-labels? true, :separator \"\\n\\n\"}

   Returns:
     {:document-id \"uri\" :content \"combined text\"}"
  [concept colbert-fields & [{:keys [include-field-labels? separator]
                               :or {include-field-labels? true
                                    separator "\n\n"}}]]
  (let [uri (or (:uri concept) (str "concept:" (random-uuid)))
        content-parts (if include-field-labels?
                        (keep (fn [field]
                                (format-field-with-label field (get concept field)))
                              colbert-fields)
                        (keep (fn [field]
                                (format-field-value (get concept field)))
                              colbert-fields))]
    {:document-id uri
     :content (str/join separator content-parts)}))

(defn build-enriched-document
  "Build enriched document with related data (tasks, skills, knowledge).

   For O*NET occupations, related data includes:
   - :tasks - Core tasks for the occupation
   - :skills - Required skills
   - :knowledge - Knowledge areas
   - :abilities - Physical/cognitive abilities

   Args:
     concept: Base concept with :uri, :label, :description
     related-data: Map with :tasks, :skills, :knowledge, :abilities
     opts: {:max-tasks 8, :max-skills 6, :max-knowledge 6}

   Returns:
     {:document-id \"uri\" :content \"enriched text\"}"
  [concept related-data & [{:keys [max-tasks max-skills max-knowledge max-abilities]
                             :or {max-tasks 8
                                  max-skills 6
                                  max-knowledge 6
                                  max-abilities 6}}]]
  (let [uri (or (:uri concept) (str "concept:" (random-uuid)))
        label (or (:label concept) "")
        description (or (:description concept) "")

        tasks (take max-tasks (:tasks related-data))
        skills (take max-skills (:skills related-data))
        knowledge (take max-knowledge (:knowledge related-data))
        abilities (take max-abilities (:abilities related-data))

        content (str "OCCUPATION: " label
                     (when-not (str/blank? description)
                       (str "\n\nDESCRIPTION: " description))
                     (when (seq tasks)
                       (str "\n\nKEY TASKS:\n- " (str/join "\n- " tasks)))
                     (when (seq skills)
                       (str "\n\nSKILLS: " (str/join ", " skills)))
                     (when (seq knowledge)
                       (str "\n\nKNOWLEDGE: " (str/join ", " knowledge)))
                     (when (seq abilities)
                       (str "\n\nABILITIES: " (str/join ", " abilities))))]
    {:document-id uri
     :content content}))

(defn build-documents-from-concepts
  "Build ColBERT documents for all concepts.

   Args:
     concepts: Vector of concept maps
     opts: {:colbert-fields nil ;; auto-detect if nil
            :include-field-labels? true
            :separator \"\\n\\n\"}

   Returns:
     Vector of {:document-id :content} maps"
  [concepts & [{:keys [colbert-fields include-field-labels? separator]
                :or {include-field-labels? true
                     separator "\n\n"}}]]
  (let [
        detected-fields (when (nil? colbert-fields)
                          (:colbert-fields (detect-colbert-fields concepts)))
        fields-to-use (or colbert-fields detected-fields [:label :description])]

    (u/log :ai.obney.orc.ontology.core.colbert-indexer/building-documents
            :concept-count (count concepts)
            :fields-to-use fields-to-use)

    (mapv #(build-document % fields-to-use
                           {:include-field-labels? include-field-labels?
                            :separator separator})
          concepts)))

(defn index-concepts!
  "Create a ColBERT index from concepts with auto-detected fields.

   Args:
     ctx: Context map with :event-store
     concepts: Vector of concept maps
     config: {:index-name \"my-index\"
              :colbert-fields [:description :tasks ...] ;; or nil for auto-detect
              :auto-detect-colbert-fields true}

   Returns:
     {:index-id uuid
      :document-count int
      :colbert-fields [...]}"
  [ctx concepts {:keys [index-name colbert-fields auto-detect-colbert-fields]
                 :or {auto-detect-colbert-fields true}}]
  (when (empty? concepts)
    (throw (ex-info "No concepts to index" {:concepts-count 0})))

  (let [
        detected (when (and auto-detect-colbert-fields (nil? colbert-fields))
                   (detect-colbert-fields concepts))
        fields-to-use (or colbert-fields
                          (:colbert-fields detected)
                          [:label :description])

        _ (u/log :ai.obney.orc.ontology.core.colbert-indexer/indexing-concepts
                  :concept-count (count concepts)
                  :index-name index-name
                  :colbert-fields fields-to-use)

        documents (build-documents-from-concepts concepts
                    {:colbert-fields fields-to-use})

        valid-docs (filterv #(not (str/blank? (:content %))) documents)

        _ (when (< (count valid-docs) (count documents))
            (u/log :ai.obney.orc.ontology.core.colbert-indexer/filtered-empty-documents
                    :original-count (count documents)
                    :valid-count (count valid-docs)))

        final-index-name (or index-name
                             (str "ontology-" (subs (str (random-uuid)) 0 8)))

        index-id ((require-colbert (quote create-index!)) ctx
                   {:collection (mapv :content valid-docs)
                    :document-ids (mapv :document-id valid-docs)
                    :index-name final-index-name})]

    (u/log :ai.obney.orc.ontology.core.colbert-indexer/index-created
            :index-id index-id
            :document-count (count valid-docs)
            :index-name final-index-name)

    {:index-id index-id
     :index-name final-index-name
     :document-count (count valid-docs)
     :colbert-fields fields-to-use
     :detected-confidence (when detected (:confidence-scores detected))}))

(defn index-with-related-data!
  "Create enriched ColBERT index with related data.

   For O*NET-style data, provide related-data-fn that looks up
   tasks/skills/knowledge by concept URI.

   Args:
     ctx: Context with :event-store
     concepts: Vector of base concepts
     related-data-fn: (fn [uri] {:tasks [...] :skills [...] :knowledge [...] :abilities [...]})
     config: {:index-name \"enriched-index\"
              :max-tasks 8
              :max-skills 6
              :max-knowledge 6}

   Returns:
     {:index-id uuid :document-count int :avg-doc-length float}"
  [ctx concepts related-data-fn {:keys [index-name max-tasks max-skills
                                         max-knowledge max-abilities]
                                  :or {max-tasks 8
                                       max-skills 6
                                       max-knowledge 6
                                       max-abilities 6}
                                  :as config}]
  (when (empty? concepts)
    (throw (ex-info "No concepts to index" {:concepts-count 0})))

  (u/log :ai.obney.orc.ontology.core.colbert-indexer/building-enriched-index
          :concept-count (count concepts)
          :index-name index-name)

  (let [
        documents (mapv (fn [concept]
                          (let [uri (:uri concept)
                                related (when related-data-fn
                                          (related-data-fn uri))]
                            (build-enriched-document concept related config)))
                        concepts)

        valid-docs (filterv #(not (str/blank? (:content %))) documents)

        avg-length (when (seq valid-docs)
                     (/ (reduce + (map #(count (:content %)) valid-docs))
                        (count valid-docs)))

        final-index-name (or index-name
                             (str "ontology-enriched-" (subs (str (random-uuid)) 0 8)))

        index-id ((require-colbert (quote create-index!)) ctx
                   {:collection (mapv :content valid-docs)
                    :document-ids (mapv :document-id valid-docs)
                    :index-name final-index-name})]

    (u/log :ai.obney.orc.ontology.core.colbert-indexer/enriched-index-created
            :index-id index-id
            :document-count (count valid-docs)
            :avg-doc-length avg-length)

    {:index-id index-id
     :index-name final-index-name
     :document-count (count valid-docs)
     :avg-doc-length avg-length
     :config config}))

;; --- Commands ---

(defn now-str []
  (str (time/now)))

(defn generate-uuid []
  (random-uuid))

(defn nonblank? [value]
  (and (string? value) (not (str/blank? value))))

(defn anomaly [category message]
  {:cognitect.anomalies/category category :cognitect.anomalies/message message})

(defn no-events?
  "Return true only when an IReduce event stream is empty, without realizing it."
  [events]
  (reduce (fn [_ _] (reduced false)) true events))

(defn commands-tenant-scoped? [ctx]
  (some? (:tenant-id ctx)))

(defn stable-uuid-from
  "Derive a deterministic UUID from a stringifiable value. Used for
   tag values where the target-id is not natively a UUID (node-type
   keyword, tree-fingerprint hash)."
  [v]
  (java.util.UUID/nameUUIDFromBytes (.getBytes (str v) "UTF-8")))

(defn researcher-logical-action-tag
  "Stable event-store tag for one researcher-owned ontology action."
  [logical-action-identity]
  [:researcher-logical-action
   (stable-uuid-from
    (str "researcher-logical-action:" logical-action-identity))])

(defn claim-target-uuid
  "Tag value for a claim set. Event-store tags must be UUIDs, and a
   target-identifier may be a UUID (tree-class) or a string (fingerprint),
   so derive a stable one. Namespaced by granularity so two granularities
   that happen to share an identifier keep DISJOINT claim sets — the SJ-1
   join lesson applied to the CAS query as well as the projection."
  [granularity target-identifier]
  (stable-uuid-from (str "claims:" (name granularity) ":" target-identifier)))

(defn description-target-uuid
  "Tag value for a consolidation target, derived EXACTLY as the four
   record-*-description commands derive theirs — same namespacing, same
   stable-uuid — so a target's failure events land in the same
   [:description-target …] tag stream as its successes."
  [granularity target-identifier]
  (case granularity
    :node-instance (let [[sheet-id node-id] target-identifier]
                     (stable-uuid-from (str "node-instance:" sheet-id ":" node-id)))
    (stable-uuid-from (str (name granularity) ":" target-identifier))))

(defn active-researcher-classification-cas
  "Fence a checkpointed classification fact to its active campaign frontier."
  [source-tick-id source-node-id ownership-epoch]
  {:types #{:rlm/researcher-frontier-claimed
            :sheet/node-execution-completed
            :sheet/tick-cancelled
            :sheet/tree-tick-completed}
   :tags #{[:tick source-tick-id]}
   :predicate-fn
   (fn [existing]
     (let [events (into [] existing)
           frontier-epochs
           (keep (fn [event]
                   (when (and (= :rlm/researcher-frontier-claimed
                                  (:event/type event))
                              (= source-node-id (:node-id event)))
                     (:ownership-epoch event)))
                 events)
           terminal?
           (some (fn [event]
                   (or (= :sheet/tick-cancelled (:event/type event))
                       (and (= :sheet/tree-tick-completed (:event/type event))
                            (not= :running (:root-status event)))
                       (and (= :sheet/node-execution-completed
                               (:event/type event))
                            (= source-node-id (:node-id event))
                            (= :terminal (:completion-kind event)))))
                 events)]
       (and (not terminal?)
            (= ownership-epoch
               (when (seq frontier-epochs)
                 (apply max frontier-epochs))))))})

(defn classification-assigned-for-occurrence?
  "True when the durable stream already contains an assignment for one
   campaign occurrence.  Command ids are deliberately not part of this
   identity: retries may carry a fresh command id after recovery."
  [events source-sheet-id source-tick-id]
  (reduce (fn [_ event]
            (if (and (= :ontology/task-classified (:event/type event))
                     (= source-sheet-id (:source-sheet-id event))
                     (= source-tick-id (:source-tick-id event)))
              (reduced true)
              false))
          false
          events))

(defn task-classification-occurrence-cas
  "First-writer-wins guard for [source-sheet-id source-tick-id].  When the
   assignment belongs to a checkpointed researcher campaign, evaluate the
   existing live-epoch fence against the same append-time snapshot."
  [source-sheet-id source-tick-id source-node-id ownership-epoch]
  (let [active-cas (when (some? ownership-epoch)
                     (active-researcher-classification-cas
                      source-tick-id source-node-id ownership-epoch))]
    {:types (cond-> #{:ontology/task-classified}
              active-cas (into (:types active-cas)))
     :tags #{[:tick source-tick-id]}
     :predicate-fn
     (fn [existing]
       (let [events (into [] existing)]
         (and (not (classification-assigned-for-occurrence?
                    events source-sheet-id source-tick-id))
              (or (nil? active-cas)
                  ((:predicate-fn active-cas) events)))))}))

(defn- tree-class-occurrence-recorded?
  [events source-sheet-id source-tick-id]
  (reduce (fn [_ event]
            (if (and (= :ontology/tree-class-occurrence-recorded
                        (:event/type event))
                     (= source-sheet-id (:source-sheet-id event))
                     (= source-tick-id (:source-tick-id event)))
              (reduced true)
              false))
          false
          events))

(defn- infrastructure-terminal-event?
  [event source-sheet-id source-tick-id]
  (and (= source-sheet-id (:sheet-id event))
       (= source-tick-id (:tick-id event))
       (or (= :sheet/tick-cancelled (:event/type event))
           (and (= :sheet/tree-tick-completed (:event/type event))
                (not= :running (:root-status event))))))

(defn- matching-classification?
  [events source-sheet-id source-tick-id source-node-id assigned-tree-id]
  (reduce (fn [_ event]
            (if (and (= :ontology/task-classified (:event/type event))
                     (= source-sheet-id (:source-sheet-id event))
                     (= source-tick-id (:source-tick-id event))
                     (= source-node-id (:source-node-id event))
                     (= assigned-tree-id (:assigned-tree-id event)))
              (reduced true)
              false))
          false
          events))

(defn- terminal-researcher-completion?
  "A terminal completion of THIS researcher campaign's node, whatever its
   status. The campaign's status has no outbound transition once terminal, so
   the first such completion in durable order is the campaign's only verdict
   (blocked included); any later completion for the same node is a stray fact,
   never a verdict."
  [event source-sheet-id source-tick-id source-node-id]
  (and (= :sheet/node-execution-completed (:event/type event))
       (= source-sheet-id (:sheet-id event))
       (= source-tick-id (:tick-id event))
       (= source-node-id (:node-id event))
       (= :repl-researcher (:node-type event))
       (= :terminal (:completion-kind event))))

(defn- matching-terminal-completion?
  [event source-completion-event-id verdict]
  (and (= source-completion-event-id (:event/id event))
       (= verdict (:status event))))

(defn- valid-completion-before-infrastructure-ending?
  "True only when the named completion is the campaign's FIRST terminal
   completion in durable order and no cancellation or parent termination
   precedes it. An earlier terminal completion for the node — any status —
   settles the campaign, so the named later one is not admissible."
  [events source-sheet-id source-tick-id source-node-id
   source-completion-event-id verdict]
  (reduce (fn [_ event]
            (cond
              (infrastructure-terminal-event?
               event source-sheet-id source-tick-id)
              (reduced false)

              (terminal-researcher-completion?
               event source-sheet-id source-tick-id source-node-id)
              (reduced (matching-terminal-completion?
                        event source-completion-event-id verdict))

              :else false))
          false
          events))

(defn occurrence-admissible?
  [events source-sheet-id source-tick-id source-node-id
   source-completion-event-id assigned-tree-id verdict]
  (and (not (tree-class-occurrence-recorded?
             events source-sheet-id source-tick-id))
       (matching-classification?
        events source-sheet-id source-tick-id source-node-id assigned-tree-id)
       (valid-completion-before-infrastructure-ending?
        events source-sheet-id source-tick-id source-node-id
        source-completion-event-id verdict)))

(defn tree-class-occurrence-cas
  [source-sheet-id source-tick-id source-node-id
   source-completion-event-id assigned-tree-id verdict]
  {:types #{:ontology/tree-class-occurrence-recorded
            :ontology/task-classified
            :sheet/tick-cancelled
            :sheet/tree-tick-completed
            :sheet/node-execution-completed}
   :tags #{[:tick source-tick-id]}
   :predicate-fn
   (fn [existing]
     (occurrence-admissible?
      existing source-sheet-id source-tick-id source-node-id
      source-completion-event-id assigned-tree-id verdict))})

(defn shape-coherence-reported?
  [events tree-class source-sheet-id source-tick-id]
  (reduce (fn [_ event]
            (if (and (= :ontology/shape-coherence-reported (:event/type event))
                     (= tree-class (:tree-class event))
                     (= source-sheet-id (:source-sheet-id event))
                     (= source-tick-id (:source-tick-id event)))
              (reduced true)
              false))
          false
          events))

(defn shape-coherence-report-cas
  [tree-class source-sheet-id source-tick-id]
  {:types #{:ontology/shape-coherence-reported}
   :tags #{[:tick source-tick-id]}
   :predicate-fn
   (fn [existing]
     (not (shape-coherence-reported? existing tree-class source-sheet-id source-tick-id)))})

(defn domain-deferral-recorded-for-occurrence?
  "RS-6 weed (ClassificationIsOnePerCampaign): true when the stream already
   carries a domain-axis deferral for this campaign occurrence. Command ids
   are not part of the identity: a retried tick carries a fresh one."
  [events source-sheet-id source-tick-id]
  (boolean
   (some (fn [event]
           (and (= :ontology/task-classification-deferred (:event/type event))
                (= :domain-coverage (:fallback-source event))
                (= source-sheet-id (:source-sheet-id event))
                (= source-tick-id (:source-tick-id event))))
         events)))

(defn mint-reindex-forced-tag
  "Stable event-store tag guarding at-most-once forced reindex per minted
   event id."
  [minted-event-id]
  [:mint-reindex-forced
   (stable-uuid-from (str "mint-reindex-forced:" minted-event-id))])

;; --- Reranker ---

(defn timed-out?
  "True when a `rerank!` return value is the exhausted-timeout marker (the
   call timed out and so did its retry). False for every other result,
   including nil, a genuine empty result, and success."
  [rerank-result]
  (boolean (:rerank-timeout? (meta rerank-result))))

;; --- Sql ontology ---

(defn get-connection
  "Get a JDBC connection to the SQLite database."
  [db-path]
  (when (and db-path (.exists (io/file db-path)))
    (java.sql.DriverManager/getConnection (str "jdbc:sqlite:" db-path))))

(defn query-db
  "Execute a query and return results as vector of maps."
  [conn sql]
  (try
    (with-open [stmt (.createStatement conn)
                rs (.executeQuery stmt sql)]
      (let [meta (.getMetaData rs)
            col-count (.getColumnCount meta)
            col-names (mapv #(.getColumnName meta (inc %)) (range col-count))]
        (loop [results []]
          (if (.next rs)
            (recur (conj results
                         (into {}
                               (map-indexed
                                (fn [idx col-name]
                                  [(keyword col-name) (.getObject rs (inc idx))])
                                col-names))))
            results))))
    (catch Exception e
      (println "SQL Error:" (.getMessage e))
      [])))

(defn get-tables
  "Get all table names from SQLite database."
  [conn]
  (->> (query-db conn "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
       (mapv :name)))

(defn get-columns
  "Get column info for a table using PRAGMA."
  [conn table-name]
  (query-db conn (str "PRAGMA table_info('" table-name "')")))

(defn get-foreign-keys
  "Get foreign key relationships for a table."
  [conn table-name]
  (query-db conn (str "PRAGMA foreign_key_list('" table-name "')")))

(defn get-indexes
  "Get indexes for a table."
  [conn table-name]
  (query-db conn (str "PRAGMA index_list('" table-name "')")))

(defn get-row-count
  "Get row count for a table."
  [conn table-name]
  (let [result (query-db conn (str "SELECT COUNT(*) as cnt FROM \"" table-name "\""))]
    (or (:cnt (first result)) 0)))

(defn get-sample-values
  "Get sample values from a column."
  [conn table-name column-name & {:keys [limit] :or {limit 5}}]
  (->> (query-db conn (str "SELECT DISTINCT \"" column-name "\" FROM \"" table-name
                           "\" WHERE \"" column-name "\" IS NOT NULL LIMIT " limit))
       (mapv (keyword column-name))))

(def sqlite-to-xsd-type
  "Map SQLite types to XSD datatypes."
  {"INTEGER" "xsd:integer"
   "REAL" "xsd:decimal"
   "TEXT" "xsd:string"
   "BLOB" "xsd:base64Binary"
   "NUMERIC" "xsd:decimal"
   "VARCHAR" "xsd:string"
   "CHAR" "xsd:string"
   "BOOLEAN" "xsd:boolean"
   "DATE" "xsd:date"
   "DATETIME" "xsd:dateTime"
   "TIMESTAMP" "xsd:dateTime"})

(def semantic-column-patterns
  "Patterns for detecting semantic column types from names."
  {:identifier  #"(?i)(^id$|_id$|unitid|code$)"
   :label       #"(?i)(name|title|label|instnm)"
   :description #"(?i)(desc|description|definition|notes)"
   :url         #"(?i)(url|link|website|webaddr)"
   :date        #"(?i)(date|created|updated|timestamp|year)"
   :currency    #"(?i)(price|cost|earning|salary|income|tuition|fee)"
   :percentage  #"(?i)(rate|percent|ratio|pct)"
   :code        #"(?i)(cip|soc|fips|naics|_code$)"
   :boolean     #"(?i)(flag|is_|has_|can_)"})

(defn detect-semantic-type
  "Detect semantic type from column name."
  [col-name]
  (or (some (fn [[type pattern]]
              (when (re-find pattern (str col-name)) type))
            semantic-column-patterns)
      :data))

(defn sql-ontology-to-pascal-case
  "Convert string to PascalCase for class names."
  [s]
  (if (str/blank? s)
    ""
    (let [words (str/split (str/replace (str s) #"[_\-\s]+" " ") #"\s+")]
      (apply str (map str/capitalize words)))))

(defn sql-ontology-to-camel-case
  "Convert string to camelCase for property names."
  [s]
  (if (str/blank? s)
    ""
    (let [words (str/split (str/replace (str s) #"[_\-\s]+" " ") #"\s+")]
      (if (empty? words)
        ""
        (str (str/lower-case (first words))
             (apply str (map str/capitalize (rest words))))))))

(defn sql-ontology-slugify
  "Convert string to URL-safe slug."
  [s]
  (if (str/blank? s)
    ""
    (-> (str s)
        str/lower-case
        (str/replace #"[^a-z0-9\s-]" "")
        (str/replace #"\s+" "-")
        (str/replace #"-+" "-"))))

(defn connect-to-database-fn
  "Connect to SQLite database and extract basic metadata.
   Note: Connection is NOT returned to avoid ORC serialization issues."
  [{:keys [inputs]}]
  (let [db-path (get inputs :db-path)
        conn (when db-path (get-connection db-path))]
    (if conn
      (let [tables (get-tables conn)]
        (.close conn)
        {:db-connected true
         :table-names tables
         :table-count (count tables)})
      {:db-connected false
       :error "Could not connect to database"})))

(defn analyze-schema-fn
  "Analyze database schema - tables, columns, types, and foreign keys."
  [{:keys [inputs]}]
  (let [db-path (get inputs :db-path)
        table-names (get inputs :table-names [])
        max-tables (get inputs :max-tables 50)]
    (if-let [conn (get-connection db-path)]
      (try
        (let [tables-to-analyze (take max-tables table-names)

              table-analysis
              (vec
               (for [table-name tables-to-analyze]
                 (let [columns (get-columns conn table-name)
                       foreign-keys (get-foreign-keys conn table-name)
                       row-count (get-row-count conn table-name)
                       pk-cols (filterv #(pos? (or (:pk %) 0)) columns)]
                   {:table-name table-name
                    :row-count row-count
                    :columns (mapv (fn [col]
                                     {:name (:name col)
                                      :type (or (:type col) "TEXT")
                                      :nullable (zero? (or (:notnull col) 0))
                                      :primary-key (pos? (or (:pk col) 0))
                                      :default (:dflt_value col)
                                      :semantic-type (detect-semantic-type (:name col))
                                      :xsd-type (get sqlite-to-xsd-type
                                                     (str/upper-case (or (:type col) "TEXT"))
                                                     "xsd:string")})
                                   columns)
                    :primary-keys (mapv :name pk-cols)
                    :foreign-keys (mapv (fn [fk]
                                          {:from-column (:from fk)
                                           :to-table (:table fk)
                                           :to-column (:to fk)})
                                        foreign-keys)})))

              total-columns (reduce + (map #(count (:columns %)) table-analysis))
              total-rows (reduce + (map :row-count table-analysis))
              total-fks (reduce + (map #(count (:foreign-keys %)) table-analysis))]

          (.close conn)
          {:table-analysis table-analysis
           :schema-summary {:table-count (count table-analysis)
                            :total-columns total-columns
                            :total-rows total-rows
                            :total-foreign-keys total-fks}})
        (catch Exception e
          {:error (.getMessage e)
           :table-analysis []
           :schema-summary {}}))
      {:error "Could not connect to database"
       :table-analysis []
       :schema-summary {}})))

(defn build-schema-context-fn
  "Build context strings for LLM schema analysis."
  [{:keys [inputs]}]
  (let [table-analysis (get inputs :table-analysis [])
        schema-summary (get inputs :schema-summary {})

        table-summary
        (str "DATABASE SCHEMA (" (:table-count schema-summary) " tables, "
             (:total-rows schema-summary) " total rows)\n\n"
             (str/join "\n\n"
               (for [t table-analysis]
                 (str "TABLE: " (:table-name t) " (" (:row-count t) " rows)\n"
                      "  Columns:\n"
                      (str/join "\n"
                        (for [col (:columns t)]
                          (str "    - " (:name col) " (" (:type col) ")"
                               (when (:primary-key col) " [PK]")
                               (when-not (:nullable col) " NOT NULL"))))
                      (when (seq (:foreign-keys t))
                        (str "\n  Foreign Keys:\n"
                             (str/join "\n"
                               (for [fk (:foreign-keys t)]
                                 (str "    - " (:from-column fk) " -> "
                                      (:to-table fk) "." (:to-column fk))))))))))

        fk-summary
        (let [fks (mapcat (fn [t]
                           (map #(assoc % :from-table (:table-name t))
                                (:foreign-keys t)))
                         table-analysis)]
          (str "FOREIGN KEY RELATIONSHIPS (" (count fks) " total):\n"
               (str/join "\n"
                 (for [fk fks]
                   (str "  " (:from-table fk) "." (:from-column fk)
                        " -> " (:to-table fk) "." (:to-column fk))))))]

    {:table-summary table-summary
     :fk-summary fk-summary
     :detected-patterns (str "Tables: " (count table-analysis)
                             ", FKs: " (:total-foreign-keys schema-summary))}))

(defn prepare-table-context-fn
  "Prepare context for individual table entity extraction."
  [{:keys [inputs]}]
  (let [current-table (get inputs :current-table {})
        table-name (or (get current-table :table-name)
                       (get current-table "table-name") "")
        columns (or (get current-table :columns)
                    (get current-table "columns") [])
        row-count (or (get current-table :row-count)
                      (get current-table "row-count") 0)

        label-cols (filterv #(= :label (:semantic-type %)) columns)
        id-cols (filterv #(= :identifier (:semantic-type %)) columns)
        desc-cols (filterv #(= :description (:semantic-type %)) columns)]

    {:table-name table-name
     :row-count row-count
     :column-names (mapv :name columns)
     :label-columns (mapv :name label-cols)
     :id-columns (mapv :name id-cols)
     :description-columns (mapv :name desc-cols)}))

(defn collect-table-entities-fn
  "Collect entity definitions from map-each results."
  [{:keys [inputs]}]
  (let [entity-results (get inputs :entity-results [])
        table-analysis (get inputs :table-analysis [])]
    {:table-entities
     (into {}
           (map-indexed
            (fn [idx table]
              (let [table-name (or (:table-name table) (get table "table-name"))
                    result (nth entity-results idx nil)]
                [table-name
                 {:class-name (or (:class-name result) (get result "class-name")
                                  (sql-ontology-to-pascal-case table-name))
                  :definition (or (:definition result) (get result "definition") "")
                  :entity-type (or (:entity-type result) (get result "entity-type") "Entity")
                  :key-properties (or (:key-properties result) (get result "key-properties") [])}]))
            table-analysis))}))

(defn parse-table-entities-fn
  "Parse batch JSON output from LLM table entity extraction.

   Expected JSON format from LLM:
   {
     \"table_name\": {
       \"class_name\": \"ClassName\",
       \"definition\": \"Entity definition...\",
       \"entity_type\": \"Person|Organization|Event|...\"
     },
     ...
   }

   Returns :table-entities map with normalized keys."
  [{:keys [inputs]}]
  (let [table-analysis (get inputs :table-analysis [])
        json-str (get inputs :table-entities-json "")

        clean-json (cond
                     (str/blank? json-str) "{}"

                     (str/includes? json-str "```json")
                     (-> json-str
                         (str/replace #"(?s).*```json\s*" "")
                         (str/replace #"```.*$" "")
                         str/trim)

                     (str/includes? json-str "```")
                     (-> json-str
                         (str/replace #"(?s).*```\s*" "")
                         (str/replace #"```.*$" "")
                         str/trim)

                     :else (str/trim json-str))

        parsed (try
                 (json/read-str clean-json :key-fn keyword)
                 (catch Exception _
                   {}))

        table-names (set (map :table-name table-analysis))

        table-entities
        (into {}
          (for [table-name table-names
                :let [
                      entry (or (get parsed (keyword table-name))
                               (get parsed table-name)
                               (get parsed (str/lower-case table-name))
                               (get parsed (keyword (str/lower-case table-name))))]]
            [table-name
             (if entry
               {:class-name (or (:class_name entry)
                               (:className entry)
                               (:class-name entry)
                               (get entry "class_name")
                               (get entry "className")
                               (sql-ontology-to-pascal-case table-name))
                :definition (or (:definition entry)
                               (get entry "definition")
                               (str "Entity representing " table-name " records"))
                :entity-type (or (:entity_type entry)
                                (:entityType entry)
                                (:entity-type entry)
                                (get entry "entity_type")
                                (get entry "entityType")
                                "Entity")
                :key-properties (or (:key_properties entry)
                                   (:keyProperties entry)
                                   (:key-properties entry)
                                   [])}
               {:class-name (sql-ontology-to-pascal-case table-name)
                :definition (str "Entity representing " table-name " records")
                :entity-type "Entity"
                :key-properties []})]))]

    {:table-entities table-entities}))

(defn build-sql-tbox-fn
  "Build TBox (ontology schema) from database schema."
  [{:keys [inputs]}]
  (let [table-analysis (get inputs :table-analysis [])
        table-entities (get inputs :table-entities {})
        discovered-relationships (get inputs :discovered-relationships [])
        base-uri (get inputs :base-uri "http://example.org/db#")

        classes
        (vec
         (for [t table-analysis
               :let [table-name (:table-name t)
                     entity (get table-entities table-name {})
                     class-name (or (:class-name entity) (sql-ontology-to-pascal-case table-name))]]
           {:uri (str base-uri class-name)
            :label class-name
            :comment (or (:definition entity) (str "Entity representing " table-name " table"))
            :source-table table-name}))

        datatype-properties
        (vec
         (for [t table-analysis
               col (:columns t)
               :let [table-name (:table-name t)
                     prop-name (sql-ontology-to-camel-case (str table-name "_" (:name col)))
                     domain-class (or (:class-name (get table-entities table-name {}))
                                      (sql-ontology-to-pascal-case table-name))]]
           {:uri (str base-uri prop-name)
            :label (:name col)
            :domain (str base-uri domain-class)
            :range (:xsd-type col)
            :source-column (:name col)
            :source-table table-name}))

        object-properties
        (vec
         (concat
          (for [t table-analysis
                fk (:foreign-keys t)
                :let [from-table (:table-name t)
                      from-class (or (:class-name (get table-entities from-table {}))
                                     (sql-ontology-to-pascal-case from-table))
                      to-table (:to-table fk)
                      to-class (or (:class-name (get table-entities to-table {}))
                                   (sql-ontology-to-pascal-case to-table))
                      prop-name (sql-ontology-to-camel-case (str "has" (sql-ontology-to-pascal-case to-table)))]]
            {:uri (str base-uri prop-name)
             :label prop-name
             :domain (str base-uri from-class)
             :range (str base-uri to-class)
             :source-fk fk})
          (for [r discovered-relationships
                :let [subj (or (get r "subject") (get r :subject))
                      pred (or (get r "predicate") (get r :predicate))
                      obj (or (get r "object") (get r :object))]]
            {:uri (str base-uri (sql-ontology-to-camel-case pred))
             :label pred
             :domain (str base-uri (sql-ontology-to-pascal-case subj))
             :range (str base-uri (sql-ontology-to-pascal-case obj))})))]

    {:tbox {:classes classes
            :object-properties (vec (distinct object-properties))
            :datatype-properties datatype-properties}}))

(defn extract-sample-instances-fn
  "Extract sample instances from database for A-box."
  [{:keys [inputs]}]
  (let [db-path (get inputs :db-path)
        table-analysis (get inputs :table-analysis [])
        table-entities (get inputs :table-entities {})
        base-uri (get inputs :base-uri "http://example.org/db#")
        max-instances (get inputs :max-instances 10)]
    (if-let [conn (get-connection db-path)]
      (try
        (let [abox
              (vec
               (for [t table-analysis
                     :let [table-name (:table-name t)
                           entity (get table-entities table-name {})
                           class-name (or (:class-name entity) (sql-ontology-to-pascal-case table-name))
                           columns (:columns t)
                           label-col (or (first (filter #(= :label (:semantic-type %)) columns))
                                         (first (filter :primary-key columns))
                                         (first columns))
                           label-col-name (:name label-col)
                           rows (query-db conn
                                  (str "SELECT * FROM \"" table-name "\" LIMIT " max-instances))]
                     row rows
                     :let [label-val (or (get row (keyword label-col-name)) "instance")
                           instance-id (sql-ontology-slugify (str class-name "-" label-val "-" (rand-int 10000)))]]
                 {:uri (str base-uri instance-id)
                  :type (str base-uri class-name)
                  :label (str label-val)
                  :source-table table-name
                  :properties (into {}
                                (for [[k v] row
                                      :when (and v (not= "" v))]
                                  [(sql-ontology-to-camel-case (str table-name "_" (name k))) v]))}))]
          (.close conn)
          {:abox abox})
        (catch Exception e
          {:abox []
           :error (.getMessage e)}))
      {:abox []
       :error "Could not connect to database"})))

(defn serialize-sql-to-owl-fn
  "Serialize database ontology to OWL Turtle format."
  [{:keys [inputs]}]
  (let [tbox (get inputs :tbox {})
        abox (get inputs :abox [])
        base-uri (get inputs :base-uri "http://example.org/db#")
        db-path (get inputs :db-path "")

        prefixes (str "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                      "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                      "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n"
                      "@prefix : <" base-uri "> .\n\n")

        ontology-decl (str "# Ontology generated from SQL database: "
                           (or db-path "unknown") "\n"
                           "<" base-uri "> a owl:Ontology ;\n"
                           "  rdfs:label \"Database Ontology\"@en ;\n"
                           "  rdfs:comment \"Generated from SQL schema by ORC SQL Ontology Builder\"@en .\n\n")

        classes-str (str "# Classes (from tables)\n"
                         (str/join "\n"
                           (for [c (get tbox :classes [])]
                             (str "<" (:uri c) "> a owl:Class ;\n"
                                  "  rdfs:label \"" (:label c) "\"@en"
                                  (when (seq (:comment c))
                                    (str " ;\n  rdfs:comment \"" (:comment c) "\"@en"))
                                  " .\n"))))

        obj-props-str (str "\n# Object Properties (from foreign keys)\n"
                           (str/join "\n"
                             (for [p (get tbox :object-properties [])]
                               (str "<" (:uri p) "> a owl:ObjectProperty ;\n"
                                    "  rdfs:label \"" (:label p) "\"@en ;\n"
                                    "  rdfs:domain <" (:domain p) "> ;\n"
                                    "  rdfs:range <" (:range p) "> .\n"))))

        key-props (take 100 (get tbox :datatype-properties []))
        data-props-str (str "\n# Datatype Properties (from columns, first 100)\n"
                            (str/join "\n"
                              (for [p key-props]
                                (str "<" (:uri p) "> a owl:DatatypeProperty ;\n"
                                     "  rdfs:label \"" (:label p) "\"@en ;\n"
                                     "  rdfs:domain <" (:domain p) "> ;\n"
                                     "  rdfs:range <" (:range p) "> .\n"))))

        individuals-str (str "\n# Sample Individuals\n"
                             (str/join "\n"
                               (for [i (take 50 abox)]
                                 (str "<" (:uri i) "> a <" (:type i) "> ;\n"
                                      "  rdfs:label \"" (:label i) "\"@en .\n"))))]

    {:owl-output (str prefixes ontology-decl classes-str obj-props-str
                      data-props-str individuals-str)}))

(defn compute-sql-statistics-fn
  "Compute statistics for the SQL-to-ontology conversion."
  [{:keys [inputs]}]
  (let [table-analysis (get inputs :table-analysis [])
        tbox (get inputs :tbox {})
        abox (get inputs :abox [])
        schema-summary (get inputs :schema-summary {})]
    {:statistics
     {:table-count (count table-analysis)
      :column-count (:total-columns schema-summary)
      :row-count (:total-rows schema-summary)
      :fk-count (:total-foreign-keys schema-summary)
      :class-count (count (get tbox :classes []))
      :object-property-count (count (get tbox :object-properties []))
      :datatype-property-count (count (get tbox :datatype-properties []))
      :individual-count (count abox)
      :triple-count (+ (* (count (get tbox :classes [])) 3)
                       (* (count (get tbox :object-properties [])) 4)
                       (* (count (get tbox :datatype-properties [])) 4)
                       (* (count abox) 3))}}))

(def sql-ontology-sheet-id #uuid "c3d4e5f6-a7b8-9012-cdef-345678901234")

;; --- Ontology exploration ---

(def sample-source-text
  "Sample text for taxonomy extraction testing.

Machine learning is a subset of artificial intelligence. AI encompasses
various approaches to building intelligent systems. Deep learning is a
specialized form of machine learning that uses neural networks with many layers.

Natural language processing (NLP) is another branch of AI focused on
understanding human language. Computer vision deals with visual data processing.

Supervised learning uses labeled data, while unsupervised learning discovers
patterns without labels. Reinforcement learning involves agents learning through
interaction with environments.

Transfer learning allows models trained on one task to be adapted for another.
Few-shot learning enables learning from very few examples.")

(def sample-domain "artificial intelligence and machine learning")

(defn ingest-sources-fn
  "Phase 1: Load and combine multiple source files into single text.
   Python equivalent: multi_loader.load_all() + combine()"
  [{:keys [inputs]}]
  (let [source-paths (get inputs :source-paths [])
        source-text (get inputs :source-text)]
    {:source-text (or source-text
                      (when (seq source-paths)
                        (str/join "\n\n" (map slurp source-paths)))
                      "")}))

(defn- get-concept-field
  "Get a field from concept, handling both keyword and string keys."
  [concept field default]
  (or (get concept (keyword field))
      (get concept (name field))
      default))

(defn- dedupe-within-block
  "Deduplicate concepts within a single type block using label similarity.
   Returns vector of unique concepts with normalized keys."
  [concepts]
  (let [seen-labels (atom #{})]
    (reduce
      (fn [acc concept]
        (let [label (str/lower-case (get-concept-field concept "label" ""))]
          (if (or (empty? label) (contains? @seen-labels label))
            acc
            (do
              (swap! seen-labels conj label)
              (conj acc {"label" (get-concept-field concept "label" "")
                         "alt_labels" (let [al (get-concept-field concept "alt_labels" "")]
                                        (if (vector? al) (str/join ", " al) (or al "")))
                         "definition" (get-concept-field concept "definition" "")
                         "entity_type" (get-concept-field concept "entity_type" "UNKNOWN")
                         "confidence" 0.8})))))
      []
      concepts)))

(defn ontology-exploration-deduplicate-concepts-fn
  "Phase 3: Remove duplicate concepts using Type-Based Blocking.

   Optimization: Groups concepts by entity_type before comparison.
   This reduces complexity from O(N²) to O((N/k)²) where k = number of types.

   Python equivalent: EntityResolver.resolve_within_batch() with blocking"
  [{:keys [inputs]}]
  (let [concepts (get inputs :raw-concepts [])
        by-type (group-by #(get-concept-field % "entity_type" "UNKNOWN") concepts)
        unique-concepts (vec (mapcat dedupe-within-block (vals by-type)))
        blocking-stats {:type-count (count by-type)
                        :types (vec (keys by-type))
                        :concepts-per-type (into {} (map (fn [[t cs]] [t (count cs)]) by-type))}]
    {:concepts unique-concepts
     :blocking-stats blocking-stats}))

(defn extract-labels-fn
  "Extract just the labels from concepts for hierarchy building.
   Python equivalent: [c['label'] for c in concepts]"
  [{:keys [inputs]}]
  (let [concepts (get inputs :concepts [])
        labels (mapv (fn [c] (or (get c "label") (get c :label) "")) concepts)]
    {:concept-labels labels}))

(defn quality-is-good?
  "Check if validation quality is 'good'.
   Python equivalent: quality == 'good'"
  [{:keys [inputs]}]
  (= "good" (get inputs :quality)))

(defn apply-auto-fixes-fn
  "Phase 7: Apply auto-fixable validation issues.
   Python equivalent: _refine() for auto_fixable issues"
  [{:keys [inputs]}]
  (let [issues (get inputs :issues [])
        top-concepts (get inputs :top-concepts [])
        orphan-issues (filter #(= "orphans" (get % "type")) issues)
        orphan-labels (mapcat #(get % "affected_concepts" []) orphan-issues)]
    {:top-concepts (vec (distinct (concat top-concepts orphan-labels)))}))

(def causal-type-mapping
  "Normalize relation type variations to canonical forms."
  {"leads_to" "causes"
   "results_in" "causes"
   "allows" "enables"
   "makes_possible" "enables"
   "blocks" "prevents"
   "inhibits" "prevents"
   "prerequisite_for" "preparesFor"
   "prepares_for" "preparesFor"
   "preparesfor" "preparesFor"})

(def valid-causal-types
  #{"causes" "enables" "prevents" "preparesFor"})

(defn validate-causal-relations-fn
  "Phase 5.5: Validate and normalize causal relations.

   - Normalizes relation types (leads_to → causes, etc.)
   - Validates that cause/effect concepts exist in concept-labels
   - Clamps confidence to [0.0, 1.0]

   Python equivalent: CausalExtractorModule with validation"
  [{:keys [inputs]}]
  (let [relations (get inputs :causal-relations [])
        concept-labels (set (get inputs :concept-labels []))

        normalize-type (fn [t]
                         (let [t-lower (str/lower-case (str t))]
                           (get causal-type-mapping t-lower t-lower)))

        clamp-confidence (fn [c]
                           (max 0.0 (min 1.0 (or c 0.8))))

        validated (->> relations
                       (map (fn [r]
                              (let [rel-type (normalize-type (get-concept-field r "relation_type" ""))]
                                {"cause" (get-concept-field r "cause" "")
                                 "effect" (get-concept-field r "effect" "")
                                 "relation_type" rel-type
                                 "confidence" (clamp-confidence (get-concept-field r "confidence" nil))
                                 "evidence" (get-concept-field r "evidence" "")})))
                       (filter #(valid-causal-types (get % "relation_type")))
                       (filter #(and (seq (get % "cause"))
                                     (seq (get % "effect"))))
                       vec)]
    {:validated-causal-relations validated
     :causal-stats {:total-extracted (count relations)
                    :total-validated (count validated)
                    :by-type (frequencies (map #(get % "relation_type") validated))}}))

(defn build-taxonomy-fn
  "Phase 8: Build SKOS taxonomy structure from extracted data.
   Python equivalent: _build_taxonomy() -> Taxonomy object"
  [{:keys [inputs]}]
  (let [concepts (get inputs :concepts [])
        relationships (get inputs :relationships [])
        top-concepts (get inputs :top-concepts [])
        related-pairs (get inputs :related-pairs [])
        causal-relations (get inputs :validated-causal-relations [])
        domain (get inputs :domain "")]
    {:taxonomy {:concepts concepts
                :relationships relationships
                :top-concepts top-concepts
                :related related-pairs
                :causal-relations causal-relations
                :domain domain
                :base-uri "http://example.org/taxonomy#"}}))

(defn serialize-to-skos-fn
  "Phase 9: Serialize taxonomy to SKOS/OWL Turtle format.
   Python equivalent: taxonomy.serialize(format='turtle')

   Includes causal OWL properties: causes/causedBy, enables/enabledBy,
   prevents/preventedBy, preparesFor/preparedBy"
  [{:keys [inputs]}]
  (let [taxonomy (get inputs :taxonomy {})
        format (get inputs :output-format "turtle")
        concepts (get taxonomy :concepts [])
        relationships (get taxonomy :relationships [])
        top-concepts (get taxonomy :top-concepts [])
        causal-relations (get taxonomy :causal-relations [])
        base-uri (get taxonomy :base-uri "http://example.org/taxonomy#")

        prefixes (str "@prefix skos: <http://www.w3.org/2004/02/skos/core#> .\n"
                      "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                      "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                      "@prefix ex: <http://example.org/taxonomy#> .\n\n")

        causal-props (when (seq causal-relations)
                       (str "# Causal Object Properties\n"
                            "ex:causes a owl:ObjectProperty ; rdfs:label \"causes\"@en ; owl:inverseOf ex:causedBy .\n"
                            "ex:causedBy a owl:ObjectProperty ; rdfs:label \"caused by\"@en .\n"
                            "ex:enables a owl:ObjectProperty ; rdfs:label \"enables\"@en ; owl:inverseOf ex:enabledBy .\n"
                            "ex:enabledBy a owl:ObjectProperty ; rdfs:label \"enabled by\"@en .\n"
                            "ex:prevents a owl:ObjectProperty ; rdfs:label \"prevents\"@en ; owl:inverseOf ex:preventedBy .\n"
                            "ex:preventedBy a owl:ObjectProperty ; rdfs:label \"prevented by\"@en .\n"
                            "ex:preparesFor a owl:ObjectProperty ; rdfs:label \"prepares for\"@en ; owl:inverseOf ex:preparedBy .\n"
                            "ex:preparedBy a owl:ObjectProperty ; rdfs:label \"prepared by\"@en .\n\n"))

        concept-triples (str/join "\n"
                          (for [c concepts
                                :let [label (get c "label" "Unknown")
                                      local-name (str/replace label #"[^a-zA-Z0-9]" "")
                                      definition (get c "definition" "")]]
                            (str "ex:" local-name " a skos:Concept ;\n"
                                 "  skos:prefLabel \"" label "\"@en ;\n"
                                 (when (seq definition)
                                   (str "  skos:definition \"" definition "\"@en ;\n"))
                                 "  .")))

        hierarchy-triples (str/join "\n"
                            (for [r relationships
                                  :let [broader (str/replace (get r "broader" "") #"[^a-zA-Z0-9]" "")
                                        narrower (str/replace (get r "narrower" "") #"[^a-zA-Z0-9]" "")]]
                              (str "ex:" narrower " skos:broader ex:" broader " .")))

        causal-triples (when (seq causal-relations)
                         (str "\n# Causal Relationships\n"
                              (str/join "\n"
                                (for [cr causal-relations
                                      :let [cause (str/replace (get cr "cause" "") #"[^a-zA-Z0-9]" "")
                                            effect (str/replace (get cr "effect" "") #"[^a-zA-Z0-9]" "")
                                            rel-type (get cr "relation_type" "causes")]]
                                  (str "ex:" cause " ex:" rel-type " ex:" effect " .")))))]

    {:skos-output (str prefixes
                       (or causal-props "")
                       "# Concepts\n" concept-triples
                       "\n\n# Hierarchy\n" hierarchy-triples
                       (or causal-triples ""))}))

(def taxonomy-sheet-id #uuid "a1b2c3d4-e5f6-7890-abcd-ef1234567890")

(defn ontology-exploration-run-demo
  "Run the taxonomy pipeline with sample data."
  [context sheet-id]
  (run-taxonomy-pipeline context sheet-id
    {:source-text sample-source-text
     :domain sample-domain}))

(defn compare-with-python
  "Compare Clojure output with Python output for parity testing.

   This function will be used to verify 1:1 match with Python DSPy:
   1. Run Python pipeline, capture raw I/O
   2. Run Clojure pipeline with same input
   3. Compare prompts, field markers, and outputs"
  [clojure-result python-result]
  (let [clj-concepts (count (:concepts clojure-result))
        py-concepts (count (:concepts python-result))
        diff (Math/abs (- clj-concepts py-concepts))
        tolerance (* 0.2 (max clj-concepts py-concepts))]
    {:concepts-match? (< diff tolerance)
     :clojure-count clj-concepts
     :python-count py-concepts
     :relationships-match? (= (count (:relationships clojure-result))
                              (count (:relationships python-result)))
     :top-concepts-match? (= (set (:top-concepts clojure-result))
                             (set (:top-concepts python-result)))}))

(defn ensure-context-has-provider
  "Ensure the context has a llm-provider set for real LLM execution."
  [context]
  (if (:llm-provider context)
    context
    (do
      (println "Warning: Context missing :llm-provider, setting to :openrouter")
      (assoc context :llm-provider :openrouter))))

;; --- Csv ontology ---

(def column-type-patterns
  "Regex patterns for column type detection from column names.
   Matches Python's detect_column_type() in csv_ontology.py"
  {:embedding   #"(?i)embedding"
   :identifier  #"(?i)(^id$|_id$|index)"
   :url         #"(?i)(url|link|website)"
   :code        #"(?i)(cip_code|soc_code|_code$)"
   :percentage  #"(?i)(rate|percent|ratio)"
   :currency    #"(?i)(price|cost|earning|debt|salary|income)"
   :label       #"(?i)(name|title|label)"
   :description #"(?i)(description|notes|comment|summary)"
   :date        #"(?i)(date|created|updated|timestamp)"})

(def temporal-column-patterns
  "Patterns for detecting temporal columns.
   Python equivalent: TemporalInterval.from_text() patterns"
  [#"(?i).*_date$"
   #"(?i).*_year$"
   #"(?i)^date_"
   #"(?i)^year_"
   #"(?i)^effective"
   #"(?i)^start_"
   #"(?i)^end_"
   #"(?i)year$"
   #"(?i)created_at"
   #"(?i)updated_at"
   #"(?i)timestamp"])

(defn temporal-column?
  "Check if column name matches temporal patterns."
  [col-name]
  (some #(re-find % (str col-name)) temporal-column-patterns))

(defn extract-year-from-value
  "Extract year from various date/year formats.
   Returns nil if not parseable."
  [value]
  (when value
    (let [s (str value)]
      (cond
        (re-matches #"^\d{4}$" s)
        (Integer/parseInt s)

        (re-matches #"^(\d{4})-\d{2}-\d{2}.*" s)
        (Integer/parseInt (second (re-matches #"^(\d{4})-.*" s)))

        (re-matches #"^\d{2}/\d{2}/(\d{4}).*" s)
        (Integer/parseInt (second (re-matches #".*(\d{4})$" s)))

        (re-find #"\b(19|20)\d{2}\b" s)
        (Integer/parseInt (re-find #"(19|20)\d{2}" s))

        :else nil))))

(defn csv-ontology-compute-temporal-relevance
  "Compute temporal relevance score based on year difference.
   Python equivalent: compute_temporal_relevance() in temporal.py

   Returns:
   - 1.0 for current year
   - 1.1 for future years (slight boost)
   - Decaying score for past years (min 0.1)
   - 0.5 for entities without temporal info"
  [entity-year reference-year & {:keys [decay-per-year] :or {decay-per-year 0.15}}]
  (cond
    (nil? entity-year) 0.5
    (= entity-year reference-year) 1.0
    (> entity-year reference-year) 1.1
    :else (max 0.1 (- 1.0 (* (- reference-year entity-year) decay-per-year)))))

(defn detect-column-type
  "Detect column type using pattern matching + value analysis.
   Python equivalent: detect_column_type() in csv_ontology.py"
  [column-name values]
  (let [
        pattern-type (some (fn [[type pattern]]
                             (when (re-find pattern column-name) type))
                           column-type-patterns)]
    (if pattern-type
      (name pattern-type)
      (let [non-null (remove nil? (remove #(= "" %) values))
            unique-count (count (set non-null))
            total-count (count non-null)]
        (cond
          (and (pos? total-count)
               (every? #{"true" "false" "1" "0" "yes" "no" "t" "f" "y" "n"}
                       (map #(str/lower-case (str %)) non-null)))
          "boolean"

          (and (pos? total-count)
               (every? #(try (Double/parseDouble (str %)) true
                             (catch Exception _ false))
                       non-null))
          "numeric"

          (and (pos? total-count)
               (<= unique-count 50)
               (< unique-count (* 0.5 total-count)))
          "categorical"

          (and (pos? total-count)
               (> (/ (reduce + (map #(count (str %)) non-null))
                     (max 1 total-count))
                  100))
          "description"

          :else "label")))))

(defn csv-ontology-to-camel-case
  "Convert string to camelCase for property names.
   Python equivalent: to_camel_case() in csv_ontology.py"
  [s]
  (if (str/blank? s)
    ""
    (let [words (str/split (str/replace s #"[_\-\s]+" " ") #"\s+")]
      (if (empty? words)
        ""
        (str (str/lower-case (first words))
             (apply str (map str/capitalize (rest words))))))))

(defn csv-ontology-to-pascal-case
  "Convert string to PascalCase for class names.
   Python equivalent: to_pascal_case() in csv_ontology.py"
  [s]
  (if (str/blank? s)
    ""
    (let [words (str/split (str/replace s #"[_\-\s]+" " ") #"\s+")]
      (apply str (map str/capitalize words)))))

(defn csv-ontology-slugify
  "Convert string to URL-safe slug.
   Python equivalent: slugify() in csv_ontology.py"
  [s]
  (if (str/blank? s)
    ""
    (let [transformed (-> s
                          str/lower-case
                          (str/replace #"[^a-z0-9\s-]" "")
                          (str/replace #"\s+" "-")
                          (str/replace #"-+" "-"))]
      (subs transformed 0 (min (count transformed) 100)))))

(defn load-csv-fn
  "Load CSV file and parse into vector of maps.
   Python equivalent: pd.read_csv()"
  [{:keys [inputs]}]
  (let [csv-path (get inputs :csv-path)]
    (if (and csv-path (.exists (io/file csv-path)))
      (with-open [reader (io/reader csv-path)]
        (let [[header & rows] (csv/read-csv reader)
              header-keys (mapv keyword header)]
          {:csv-data (mapv #(zipmap header-keys %) rows)}))
      {:csv-data (get inputs :csv-data [])})))

(defn detect-temporal-columns-fn
  "Detect columns containing temporal data (dates, years).
   Python equivalent: TemporalSpreading._get_effective_year() detection chain"
  [{:keys [inputs]}]
  (let [column-analysis (get inputs :column-analysis [])
        date-columns (->> column-analysis
                          (filter #(or (= "date" (:column-type %))
                                       (temporal-column? (:name %))))
                          (mapv :name))]
    {:date-columns date-columns
     :has-temporal-data (boolean (seq date-columns))}))

(defn extract-temporal-metadata-fn
  "Extract temporal metadata from entities based on detected date columns.
   Python equivalent: TemporalInterval dataclass creation"
  [{:keys [inputs]}]
  (let [csv-data (get inputs :csv-data [])
        date-columns (get inputs :date-columns [])
        entity-column (get inputs :csv-entity-column)]
    (if (and (seq date-columns) (seq csv-data))
      (let [
            year-col (some #(when (re-find #"(?i)year" %) %) date-columns)
            start-col (some #(when (re-find #"(?i)start" %) %) date-columns)
            end-col (some #(when (re-find #"(?i)end" %) %) date-columns)
            primary-col (or year-col start-col (first date-columns))

            temporal-entities
            (vec
              (for [row csv-data
                    :let [entity-label (get row (keyword entity-column))
                          effective-year (when primary-col
                                           (extract-year-from-value
                                             (get row (keyword primary-col))))
                          start-date (when start-col
                                       (str (get row (keyword start-col))))
                          end-date (when end-col
                                     (str (get row (keyword end-col))))]]
                {:entity-label (str entity-label)
                 :effective-year effective-year
                 :start-date start-date
                 :end-date end-date
                 :temporal-type (cond
                                  (and start-date end-date) "interval"
                                  start-date "point"
                                  effective-year "point"
                                  :else "unknown")}))]
        {:temporal-entities temporal-entities})
      {:temporal-entities []})))

(defn analyze-csv-structure-fn
  "Rule-based CSV structure analysis.
   Python equivalent: CSVOntologyBuilder.analyze_structure()"
  [{:keys [inputs]}]
  (let [csv-data (get inputs :csv-data [])
        entity-col (get inputs :entity-column)
        columns (if (seq csv-data)
                  (keys (first csv-data))
                  [])]
    {:column-analysis
     (vec
       (for [col columns]
         (let [col-name (name col)
               values (map #(get % col) csv-data)
               non-null (remove nil? (remove #(= "" %) values))
               col-type (detect-column-type col-name values)]
           {:name col-name
            :column-type col-type
            :unique-count (count (set non-null))
            :null-count (- (count values) (count non-null))
            :sample-values (vec (take 5 non-null))
            :suggested-property (csv-ontology-to-camel-case col-name)
            :is-fk-candidate (boolean (re-find #"(?i)_id$" col-name))})))

     :detected-classes
     (if entity-col
       [(csv-ontology-to-pascal-case entity-col)]
       (when-let [first-label (some #(when (= "label" (:column-type %))
                                       (:name %))
                                    (for [col columns]
                                      {:name (name col)
                                       :column-type (detect-column-type
                                                      (name col)
                                                      (map #(get % col) csv-data))}))]
         [(csv-ontology-to-pascal-case first-label)]))

     :detected-properties []
     :detected-hierarchies []
     :detected-foreign-keys []}))

(defn build-csv-context-fn
  "Build context strings for LLM schema analysis.
   Python equivalent: CSVEnrichmentPipeline._build_context()"
  [{:keys [inputs]}]
  (let [column-analysis (get inputs :column-analysis [])
        csv-data (get inputs :csv-data [])
        detected-classes (get inputs :detected-classes [])
        detected-hierarchies (get inputs :detected-hierarchies [])
        detected-foreign-keys (get inputs :detected-foreign-keys [])

        column-summary (str "COLUMNS (" (count column-analysis) " total):\n"
                           (str/join "\n"
                             (for [col column-analysis]
                               (str "- " (:name col)
                                    " (" (:column-type col) ")"
                                    " - " (:unique-count col) " unique values"
                                    (when (pos? (:null-count col))
                                      (str ", " (:null-count col) " nulls"))
                                    "\n  Samples: " (str/join ", " (take 3 (:sample-values col)))))))

        sample-rows (str "SAMPLE ROWS (" (min 5 (count csv-data)) " of " (count csv-data) "):\n"
                        (str/join "\n"
                          (for [row (take 5 csv-data)]
                            (pr-str row))))

        detected-patterns (str "DETECTED PATTERNS:\n"
                              "- Classes: " (str/join ", " detected-classes) "\n"
                              "- Hierarchies: " (count detected-hierarchies) " detected\n"
                              "- Foreign Keys: " (count detected-foreign-keys) " candidates")]

    {:column-summary column-summary
     :sample-rows sample-rows
     :detected-patterns detected-patterns}))

(defn prepare-entity-context-fn
  "Prepare context for entity definition enrichment.
   Python equivalent: CSVEnrichmentPipeline._get_class_samples()"
  [{:keys [inputs]}]
  (let [current-entity (get inputs :current-entity {})
        csv-data (get inputs :csv-data [])
        column-analysis (get inputs :column-analysis [])
        entities (get inputs :entities [])
        entity-name (or (get current-entity "name")
                        (get current-entity :name)
                        "Unknown")
        source-columns (or (get current-entity "source_columns")
                           (get current-entity :source_columns)
                           [])
        sample-instances (vec (take 5 (map #(get % (keyword (first source-columns)) "") csv-data)))
        related-entities (vec (remove #{entity-name}
                                      (map #(or (get % "name") (get % :name))
                                           entities)))]
    {:entity-name entity-name
     :source-columns (vec source-columns)
     :sample-instances sample-instances
     :related-entities related-entities}))

(defn collect-entity-definitions-fn
  "Collect entity definitions from map-each results.
   Aggregates the definition-results from map-each into a map keyed by entity name."
  [{:keys [inputs]}]
  (let [definition-results (get inputs :definition-results [])
        entities (get inputs :entities [])]
    {:entity-definitions
     (into {}
           (map-indexed
            (fn [idx entity]
              (let [entity-name (or (get entity "name") (get entity :name))
                    result (nth definition-results idx nil)]
                [entity-name
                 {:definition (or (:definition result) (get result "definition") "")
                  :scope-note (or (:scope-note result) (get result "scope-note") "")
                  :external-alignments (or (:external-alignments result) (get result "external-alignments") [])}]))
            entities))}))

(defn identify-categorical-columns-fn
  "Identify columns that are categorical for hierarchy enrichment.
   Python equivalent: filter by ColumnType.CATEGORICAL"
  [{:keys [inputs]}]
  (let [column-analysis (get inputs :column-analysis [])]
    {:categorical-columns
     (vec (map :name
               (filter #(= "categorical" (:column-type %))
                       column-analysis)))}))

(defn prepare-hierarchy-context-fn
  "Prepare context for hierarchy suggestion.
   Python equivalent: CSVEnrichmentPipeline.enrich_hierarchies() context building"
  [{:keys [inputs]}]
  (let [current-column (get inputs :current-column "")
        csv-data (get inputs :csv-data [])
        col-key (keyword current-column)
        values (map #(get % col-key) csv-data)
        non-null (remove nil? (remove #(= "" %) values))
        unique-values (vec (distinct non-null))
        value-freq (frequencies non-null)
        value-counts (str/join ", "
                       (for [[v c] (take 10 (sort-by val > value-freq))]
                         (str v ": " c)))]
    {:column-name current-column
     :unique-values (vec (take 50 unique-values))
     :value-counts value-counts}))

(defn collect-hierarchies-fn
  "Collect hierarchies from map-each results.
   Aggregates the hierarchy-results from map-each into a map keyed by column name."
  [{:keys [inputs]}]
  (let [hierarchy-results (get inputs :hierarchy-results [])
        categorical-columns (get inputs :categorical-columns [])]
    {:hierarchies
     (into {}
           (map-indexed
            (fn [idx col-name]
              (let [result (nth hierarchy-results idx nil)]
                [col-name
                 {:has-hierarchy (or (:has-hierarchy result) (get result "has-hierarchy") false)
                  :hierarchy-type (or (:hierarchy-type result) (get result "hierarchy-type") "none")
                  :hierarchy-relationships (or (:hierarchy-relationships result) (get result "hierarchy-relationships") [])
                  :top-level (or (:top-level result) (get result "top-level") [])}]))
            categorical-columns))}))

(defn prepare-relationship-context-fn
  "Prepare context for relationship discovery.
   Python equivalent: CSVEnrichmentPipeline.discover_relationships() context"
  [{:keys [inputs]}]
  (let [entities (get inputs :entities [])
        csv-data (get inputs :csv-data [])
        column-analysis (get inputs :column-analysis [])
        relationships (get inputs :relationships [])

        entities-info (mapv (fn [e]
                              {:name (or (get e "name") (get e :name))
                               :properties (mapv (fn [c] {:name (:name c)
                                                          :type (:column-type c)})
                                                 column-analysis)})
                            entities)

        sample-data (str/join "\n" (map pr-str (take 5 csv-data)))

        existing-relationships (mapv (fn [r]
                                       (str (or (get r "subject") (get r :subject))
                                            " -> "
                                            (or (get r "predicate") (get r :predicate))
                                            " -> "
                                            (or (get r "object") (get r :object))))
                                     relationships)]

    {:entities-info (pr-str entities-info)
     :sample-data sample-data
     :existing-relationships existing-relationships}))

(defn prepare-validation-context-fn
  "Prepare context for quality validation.
   Python equivalent: CSVEnrichmentPipeline.validate_quality() context"
  [{:keys [inputs]}]
  (let [entities (get inputs :entities [])
        entity-definitions (get inputs :entity-definitions {})
        property-mappings (get inputs :property-mappings [])]
    {:terms-to-validate (vec (map #(or (get % "name") (get % :name)) entities))
     :existing-definitions entity-definitions
     :mappings-to-validate property-mappings}))

(defn prepare-mapping-validation-fn
  "Prepare context for column mapping validation."
  [{:keys [inputs]}]
  (let [current-mapping (get inputs :current-mapping {})
        csv-data (get inputs :csv-data [])
        col-name (or (get current-mapping "column") (get current-mapping :column) "")
        col-key (keyword col-name)
        values (map #(get % col-key) csv-data)
        sample-values (vec (take 5 (remove nil? values)))]
    {:column-name col-name
     "suggested-property" (or (get current-mapping "suggested_name")
                              (get current-mapping :suggested_name)
                              (csv-ontology-to-camel-case col-name))
     "property-type" (or (get current-mapping "property_type")
                         (get current-mapping :property_type)
                         "data")
     "sample-values" sample-values}))

(defn collect-mapping-issues-fn
  "Collect mapping issues from validation."
  [{:keys [inputs]}]
  {:mapping-issues []})

(defn csv-ontology-build-tbox-fn
  "Build TBox (ontology schema) from entities and relationships.
   Python equivalent: CSVOntologyBuilder.build_tbox()"
  [{:keys [inputs]}]
  (let [raw-entities (get inputs :entities [])
        entity-definitions (get inputs :entity-definitions {})
        relationships (get inputs :relationships [])
        discovered-relationships (get inputs :discovered-relationships [])
        property-mappings (get inputs :property-mappings [])
        base-uri (get inputs :base-uri "http://example.org/ontology#")

        entities (filterv #(not (str/blank? (or (get % "name") (get % :name)))) raw-entities)

        classes (mapv (fn [e]
                        (let [name (or (get e "name") (get e :name))
                              def-info (get entity-definitions name {})]
                          {:uri (str base-uri (csv-ontology-to-pascal-case name))
                           :label name
                           :comment (or (get def-info :definition)
                                        (get def-info :definition)
                                        (get e "description")
                                        (get e :description)
                                        "")}))
                      entities)

        object-properties (vec
                            (distinct
                              (concat
                                (for [r relationships]
                                  {:uri (str base-uri (csv-ontology-to-camel-case
                                                        (or (get r "predicate") (get r :predicate) "relatedTo")))
                                   :domain (str base-uri (csv-ontology-to-pascal-case
                                                           (or (get r "subject") (get r :subject) "")))
                                   :range (str base-uri (csv-ontology-to-pascal-case
                                                          (or (get r "object") (get r :object) "")))})
                                (for [r discovered-relationships]
                                  {:uri (str base-uri (csv-ontology-to-camel-case
                                                        (or (get r "predicate") (get r :predicate) "relatedTo")))
                                   :domain (str base-uri (csv-ontology-to-pascal-case
                                                           (or (get r "subject") (get r :subject) "")))
                                   :range (str base-uri (csv-ontology-to-pascal-case
                                                          (or (get r "object") (get r :object) "")))}))))

        datatype-properties (vec
                              (for [m property-mappings
                                    :when (= "data" (or (get m "property_type") (get m :property_type)))]
                                {:uri (str base-uri (or (get m "suggested_name")
                                                        (get m :suggested_name)
                                                        (csv-ontology-to-camel-case (get m "column" ""))))
                                 :domain (str base-uri (first (mapv :label classes)))
                                 :range "http://www.w3.org/2001/XMLSchema#string"}))]

    {:tbox {:classes classes
             :object-properties object-properties
             :datatype-properties datatype-properties}}))

(defn csv-ontology-build-abox-fn
  "Build ABox (instances) from CSV data.
   Python equivalent: CSVOntologyBuilder.build_abox()"
  [{:keys [inputs]}]
  (let [tbox (get inputs :tbox {})
        csv-data (get inputs :csv-data [])
        column-analysis (get inputs :column-analysis [])
        entity-column (get inputs :entity-column)
        entity-type (get inputs :entity-type)
        base-uri (get inputs :base-uri "http://example.org/ontology#")

        entity-col-key (keyword entity-column)
        type-uri (str base-uri (or entity-type "Entity"))

        individuals (vec
                      (map-indexed
                        (fn [idx row]
                          (let [label (or (get row entity-col-key) (str "instance_" idx))
                                uri (str base-uri (csv-ontology-slugify (str label)) "_" idx)
                                props (into {}
                                        (for [[k v] row
                                              :when (and v (not= "" v) (not= k entity-col-key))]
                                          [(csv-ontology-to-camel-case (name k)) v]))]
                            {:uri uri
                             :type type-uri
                             :label (str label)
                             :properties props}))
                        csv-data))]

    {:abox individuals}))

(defn csv-ontology-serialize-to-owl-fn
  "Serialize ontology to OWL Turtle format.
   Python equivalent: ontology.serialize(format='turtle')
   Includes TemporalEntity TBox when temporal data is present."
  [{:keys [inputs]}]
  (let [tbox (get inputs :tbox {})
        abox (get inputs :abox [])
        temporal-entities (get inputs :temporal-entities [])
        base-uri (get inputs :base-uri "http://example.org/ontology#")

        has-temporal (seq temporal-entities)

        prefixes (str "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                     "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                     "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n"
                     "@prefix : <" base-uri "> .\n\n")

        temporal-tbox (when has-temporal
                        (str "# Temporal Reasoning TBox\n"
                             ":TemporalEntity a owl:Class ;\n"
                             "  rdfs:label \"Temporal Entity\"@en .\n\n"
                             ":startDate a owl:DatatypeProperty, owl:FunctionalProperty ;\n"
                             "  rdfs:domain :TemporalEntity ;\n"
                             "  rdfs:range xsd:date .\n\n"
                             ":endDate a owl:DatatypeProperty, owl:FunctionalProperty ;\n"
                             "  rdfs:domain :TemporalEntity ;\n"
                             "  rdfs:range xsd:date .\n\n"
                             ":effectiveYear a owl:DatatypeProperty, owl:FunctionalProperty ;\n"
                             "  rdfs:domain :TemporalEntity ;\n"
                             "  rdfs:range xsd:gYear .\n\n"))

        classes-str (str/join "\n"
                      (for [c (get tbox :classes [])]
                        (str "<" (:uri c) "> a owl:Class ;\n"
                             "  rdfs:label \"" (:label c) "\"@en"
                             (when (seq (:comment c))
                               (str " ;\n  rdfs:comment \"" (:comment c) "\"@en"))
                             " .\n")))

        obj-props-str (str/join "\n"
                        (for [p (get tbox :object-properties [])]
                          (str "<" (:uri p) "> a owl:ObjectProperty ;\n"
                               "  rdfs:domain <" (:domain p) "> ;\n"
                               "  rdfs:range <" (:range p) "> .\n")))

        data-props-str (str/join "\n"
                         (for [p (get tbox :datatype-properties [])]
                           (str "<" (:uri p) "> a owl:DatatypeProperty ;\n"
                                "  rdfs:domain <" (:domain p) "> ;\n"
                                "  rdfs:range <" (:range p) "> .\n")))

        individuals-str (str/join "\n"
                          (for [i abox]
                            (str "<" (:uri i) "> a <" (:type i) "> ;\n"
                                 "  rdfs:label \"" (:label i) "\"@en"
                                 (when (seq (:properties i))
                                   (str " ;\n"
                                        (str/join " ;\n"
                                          (for [[k v] (:properties i)]
                                            (str "  :" k " \"" v "\"")))))
                                 " .\n")))

        temporal-str (when has-temporal
                       (str "\n# Temporal Instances\n"
                            (str/join "\n"
                              (for [te temporal-entities
                                    :let [label (or (:entity-label te) "unknown")
                                          local-name (str/replace label #"[^a-zA-Z0-9]" "_")
                                          eff-year (:effective-year te)]
                                    :when eff-year]
                                (str ":" local-name "_temporal a :TemporalEntity ;\n"
                                     "  :effectiveYear \"" eff-year "\"^^xsd:gYear .\n")))))]

    {:owl-output (str prefixes
                      (or temporal-tbox "")
                      "\n# Classes\n" classes-str
                      "\n# Object Properties\n" obj-props-str
                      "\n# Datatype Properties\n" data-props-str
                      "\n# Individuals\n" individuals-str
                      (or temporal-str ""))}))

(defn compute-csv-statistics-fn
  "Compute statistics for the CSV-to-ontology conversion.
   Includes temporal statistics when temporal data is present."
  [{:keys [inputs]}]
  (let [csv-data (get inputs :csv-data [])
        tbox (get inputs :tbox {})
        abox (get inputs :abox [])
        temporal-entities (get inputs :temporal-entities [])
        date-columns (get inputs :date-columns [])]
    {:statistics
     {:row-count (count csv-data)
      :column-count (count (keys (first csv-data)))
      :class-count (count (get tbox :classes []))
      :property-count (+ (count (get tbox :object-properties []))
                         (count (get tbox :datatype-properties [])))
      :individual-count (count abox)
      :triple-count (* (count abox) 3)
      :temporal-column-count (count date-columns)
      :temporal-entity-count (count (filter :effective-year temporal-entities))}}))

(def csv-ontology-sheet-id #uuid "b2c3d4e5-f6a7-8901-bcde-f23456789012")

(def sample-csv-data
  "Sample education programs data for testing."
  [{:name "Computer Science BS"
    :institution "MIT"
    :degree_type "Bachelor"
    :field "STEM"
    :credits 120
    :tuition 55000}
   {:name "Data Science MS"
    :institution "Stanford"
    :degree_type "Master"
    :field "STEM"
    :credits 60
    :tuition 65000}
   {:name "Business Administration MBA"
    :institution "Harvard"
    :degree_type "Master"
    :field "Business"
    :credits 48
    :tuition 73000}
   {:name "Nursing BSN"
    :institution "Johns Hopkins"
    :degree_type "Bachelor"
    :field "Healthcare"
    :credits 128
    :tuition 45000}
   {:name "Machine Learning PhD"
    :institution "CMU"
    :degree_type "Doctorate"
    :field "STEM"
    :credits 72
    :tuition 0}])

(defn csv-ontology-run-demo
  "Run the CSV-to-ontology pipeline with sample data."
  [context sheet-id]
  (run-csv-to-ontology context sheet-id
    {:csv-data sample-csv-data
     :entity-column "name"
     :entity-type "Program"
     :base-uri "http://example.org/education#"}))

;; --- Json ontology ---

(defn- infer-type
  "Infer the type of a JSON value."
  [v]
  (cond
    (nil? v) :null
    (string? v) :string
    (number? v) (if (integer? v) :integer :number)
    (boolean? v) :boolean
    (vector? v) :array
    (map? v) :object
    :else :unknown))

(defn- analyze-field
  "Analyze a single field across multiple objects."
  [field-name values]
  (let [non-nil-values (remove nil? values)
        types (set (map infer-type non-nil-values))
        sample-values (take 3 (distinct non-nil-values))]
    {:name field-name
     :type (if (= 1 (count types)) (first types) :mixed)
     :nullable? (some nil? values)
     :sample-values (vec sample-values)
     :distinct-count (count (distinct non-nil-values))}))

(defn analyze-json-structure
  "Analyze the structure of arbitrary JSON data.
   Returns a map describing root type, fields, nesting, etc."
  [json-data]
  (cond
    (and (vector? json-data) (every? map? json-data))
    (let [all-keys (distinct (mapcat keys json-data))
          fields (for [k all-keys]
                   (analyze-field (name k) (map #(get % k) json-data)))]
      {:root-type :array
       :element-type :object
       :total-count (count json-data)
       :fields (vec fields)
       :has-nested-objects? (some #(some map? (vals %)) json-data)
       :has-arrays? (some #(some vector? (vals %)) json-data)})

    (vector? json-data)
    {:root-type :array
     :element-type :primitive
     :total-count (count json-data)
     :fields []
     :sample-values (vec (take 5 json-data))}

    (map? json-data)
    (let [fields (for [[k v] json-data]
                   {:name (name k)
                    :type (infer-type v)
                    :sample-values [(if (coll? v) (str (type v)) v)]})]
      {:root-type :object
       :element-type nil
       :total-count 1
       :fields (vec fields)
       :has-nested-objects? (some map? (vals json-data))
       :has-arrays? (some vector? (vals json-data))})

    :else
    {:root-type :primitive
     :element-type nil
     :total-count 1
     :fields []}))

(defn format-structure-for-llm
  "Format structure analysis in a way that's useful for LLM reasoning."
  [structure json-data]
  (let [field-details (str/join "\n"
                        (for [f (:fields structure)]
                          (format "  - %s: %s (samples: %s)"
                                  (:name f)
                                  (name (:type f))
                                  (str/join ", " (map pr-str (:sample-values f))))))]
    (format "Root type: %s
Element type: %s
Total records: %d
Has nested objects: %s
Has arrays: %s

Field Details:
%s

Sample Data (first 2 records):
%s"
            (name (:root-type structure))
            (if (:element-type structure) (name (:element-type structure)) "N/A")
            (:total-count structure)
            (:has-nested-objects? structure)
            (:has-arrays? structure)
            field-details
            (with-out-str
              (clojure.pprint/pprint (take 2 (if (vector? json-data) json-data [json-data])))))))

(defn parse-json-fn
  "Phase 1: Parse JSON and analyze structure.
   Handles both pre-parsed data and JSON strings."
  [{:keys [inputs]}]
  (let [{:keys [json-data json-path content]} inputs
        data (cond
               json-data json-data
               content (json/read-str content :key-fn keyword)
               json-path (json/read-str (slurp json-path) :key-fn keyword)
               :else (throw (ex-info "No JSON source provided" {})))
        structure (analyze-json-structure data)]
    {:json-data data
     :structure structure
     :structure-summary (format-structure-for-llm structure data)}))

(defn extract-concepts-from-analysis-fn
  "Phase 4: Extract concepts from LLM-discovered entity types."
  [{:keys [inputs]}]
  (let [{:keys [entity-types base-uri json-data]} inputs
        concepts (if (seq entity-types)
                   (mapv (fn [et]
                           {:uri (str base-uri (:name et))
                            :label (:name et)
                            :definition (:definition et)
                            :entity-type (:type et)
                            :source-fields (:source-fields et)
                            :confidence (:confidence et)})
                         entity-types)
                   [{:uri (str base-uri "Entity")
                     :label "Entity"
                     :definition "A generic entity extracted from JSON"
                     :entity-type "Entity"
                     :source-fields []
                     :confidence 0.5}])]
    {:concepts concepts}))

(defn json-ontology-deduplicate-concepts-fn
  "Phase 5: Remove duplicate concepts by label (case-insensitive)."
  [{:keys [inputs]}]
  (let [{:keys [concepts]} inputs
        seen (atom #{})
        deduped (reduce (fn [acc concept]
                          (let [key (str/lower-case (:label concept))]
                            (if (@seen key)
                              acc
                              (do (swap! seen conj key)
                                  (conj acc concept)))))
                        []
                        concepts)]
    {:concepts deduped
     :dedup-stats {:before (count concepts)
                   :after (count deduped)
                   :removed (- (count concepts) (count deduped))}}))

(defn json-ontology-build-tbox-fn
  "Phase 8: Build T-Box (OWL classes and properties)."
  [{:keys [inputs]}]
  (let [{:keys [concepts relationships base-uri]} inputs
        classes (mapv (fn [c]
                        {:uri (:uri c)
                         :label (:label c)
                         :definition (:definition c)
                         :type "owl:Class"})
                      concepts)
        object-properties (mapv (fn [r]
                                  {:uri (str base-uri (:predicate r))
                                   :label (:predicate r)
                                   :domain (:subject r)
                                   :range (:object r)
                                   :type "owl:ObjectProperty"})
                                relationships)]
    {:tbox {:classes classes
            :object-properties object-properties
            :datatype-properties []}}))

(defn- extract-label
  "Extract label from item using fallback chain (Issue 003).

   Fallback order:
   1. Use LLM-specified label-field if present
   2. Fall back to first source-field
   3. Fall back to synthetic label (type-index)"
  [item label-field source-fields primary-type idx]
  (let [
        get-field (fn [field-name]
                    (when field-name
                      (or (get item (keyword field-name))
                          (get item field-name))))
        label-from-field (get-field label-field)
        label-from-source (when (and (nil? label-from-field) (seq source-fields))
                            (get-field (first source-fields)))]
    (or (some-> label-from-field str)
        (some-> label-from-source str)
        (str primary-type "-" idx))))

(defn json-ontology-build-abox-fn
  "Phase 9: Build A-Box (instances from JSON data).

   Issue 003: Includes :label field in each individual for Grain schema compliance.
   Label is extracted using fallback chain: label-field → source-field → synthetic."
  [{:keys [inputs]}]
  (let [{:keys [json-data concepts base-uri]} inputs
        primary-concept (first concepts)
        primary-type (or (:entity-type primary-concept) "Entity")
        label-field (:label-field primary-concept)
        source-fields (:source-fields primary-concept)
        data-items (if (vector? json-data) json-data [json-data])
        instances (map-indexed
                    (fn [idx item]
                      {:uri (str base-uri (str/lower-case primary-type) "-" idx)
                       :type primary-type
                       :label (extract-label item label-field source-fields primary-type idx)
                       :properties (into {} (map (fn [[k v]] [(name k) v]) item))})
                    data-items)]
    {:abox (vec instances)}))

(defn json-ontology-serialize-to-owl-fn
  "Phase 10: Serialize to OWL Turtle format."
  [{:keys [inputs]}]
  (let [{:keys [tbox abox base-uri]} inputs
        prefixes (format "@prefix owl: <http://www.w3.org/2002/07/owl#> .
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
@prefix : <%s> .

" base-uri)
        classes-ttl (str/join "\n"
                      (for [c (:classes tbox)]
                        (format ":%s a owl:Class ;
    rdfs:label \"%s\" ;
    rdfs:comment \"%s\" ."
                                (:label c)
                                (:label c)
                                (or (:definition c) ""))))
        owl-output (str prefixes classes-ttl)]
    {:owl-output owl-output}))

(def ^:private structure-schema
  [:map
   [:root-type :keyword]
   [:element-type {:optional true} [:maybe :keyword]]
   [:total-count :int]
   [:fields [:vector structure-field-schema]]
   [:has-nested-objects? {:optional true} :boolean]
   [:has-arrays? {:optional true} :boolean]
   [:sample-values {:optional true} [:vector json-value-schema]]])

;; --- Unified ontology ---

(defn extract-file
  "Extract ontology from a file path, auto-detecting type."
  [ctx file-path & {:keys [base-uri domain] :as opts}]
  (extract ctx (assoc opts :path file-path)))

(defn extract-text-content
  "Extract ontology from inline text content."
  [ctx text & {:keys [domain] :or {domain "general"}}]
  (extract ctx {:content text :type :text :domain domain}))

(defn extract-csv-file
  "Extract ontology from a CSV file."
  [ctx csv-path & {:keys [entity-column entity-type base-uri]}]
  (extract ctx {:path csv-path
                :type :csv
                :entity-column entity-column
                :entity-type entity-type
                :base-uri base-uri}))

(defn extract-database
  "Extract ontology from a SQLite database."
  [ctx db-path & {:keys [base-uri max-tables max-instances]}]
  (extract ctx {:path db-path
                :type :sql
                :base-uri base-uri
                :max-tables max-tables
                :max-instances max-instances}))

(defonce build-registry (atom {}))

(defn get-build-progress
  "Get progress of a background build.

   Args:
     build-id - UUID returned from build-from-sources-async!

   Returns:
     {:status :running/:completed/:failed
      :started-at timestamp-ms
      :progress 0-100 (when available)
      :result build-result (when completed)
      :error string (when failed)
      :duration-ms int}"
  [build-id]
  (get @build-registry build-id))

(defn await-build!
  "Block until build completes. Returns result or throws on failure.

   Args:
     build-id - UUID from build-from-sources-async!
     opts:
       :timeout-ms - Max wait time (default: 600000 = 10 minutes)
       :poll-interval-ms - Check interval (default: 1000)

   Returns:
     Build result map (same as build-from-sources)

   Throws:
     ExceptionInfo if build failed or timed out"
  [build-id & [{:keys [timeout-ms poll-interval-ms]
                :or {timeout-ms 600000
                     poll-interval-ms 1000}}]]
  (let [start (System/currentTimeMillis)]
    (loop []
      (let [{:keys [status result error exception]} (get-build-progress build-id)]
        (case status
          :completed result
          :failed (throw (ex-info (str "Build failed: " error)
                                  {:build-id build-id :error error}
                                  exception))
          :running (if (> (- (System/currentTimeMillis) start) timeout-ms)
                     (throw (ex-info "Build timed out"
                                     {:build-id build-id
                                      :timeout-ms timeout-ms}))
                     (do (Thread/sleep poll-interval-ms) (recur)))
          (throw (ex-info "Build not found" {:build-id build-id})))))))

(defn list-builds
  "List all tracked builds.

   Args:
     opts:
       :status - Filter by status (:running, :completed, :failed)
       :limit - Max results (default: 100)

   Returns:
     Vector of [build-id build-info] pairs"
  [& [{:keys [status limit] :or {limit 100}}]]
  (let [all-builds @build-registry
        filtered (if status
                   (filter (fn [[_ info]] (= status (:status info))) all-builds)
                   all-builds)]
    (->> filtered
         (sort-by (fn [[_ info]] (or (:started-at info) 0)) >)
         (take limit)
         vec)))

(defn clear-build-registry!
  "Clear completed/failed builds from registry.
   Running builds are preserved.

   Returns: Number of builds cleared"
  []
  (let [before (count @build-registry)]
    (swap! build-registry
           (fn [reg]
             (into {}
                   (filter (fn [[_ info]] (= :running (:status info)))
                           reg))))
    (- before (count @build-registry))))

(defn get-concepts [ctx & [opts]] (ontology-lib/get-concepts ctx (or opts {})))

(defn get-concept-by-uri
  ([ctx uri] (let [matches (filter #(= uri (:uri %)) (checked-graph-result (ontology-lib/get-concepts ctx)))]
               (when (= 1 (count matches)) (first matches))))
  ([ctx oid uri] (ontology-lib/get-concept-by-uri ctx oid uri)))

(defn get-concept-map
  "ORC vocabulary view over the standalone tenant graph. Qualified identities
   are retained when URIs collide; composition is interpreted by ORC."
  [ctx]
  (let [concepts (checked-graph-result (ontology-lib/get-concepts ctx))
        frequencies (frequencies (map :uri concepts))]
    (into {} (map (fn [c]
                    [(if (= 1 (get frequencies (:uri c))) (:uri c)
                         [(:ontology-id c) (:uri c)])
                     (assoc c :composes-into
                            (set (map second (get-in c [:relations "behavior:composes-into"])))
                            :composed-by
                            (set (keep (fn [source]
                                         (when (contains? (get-in source [:relations "behavior:composes-into"] #{})
                                                          [(:ontology-id c) (:uri c)])
                                           (:uri source))) concepts)))])
                  concepts))))

(defn get-narrower-concepts
  "Get all concepts that are narrower than the given URI."
  [ctx uri]
  (get-in (get-concept-map ctx) [uri :narrower] #{}))

(defn get-broader-concepts
  "Get all concepts that are broader than the given URI."
  [ctx uri]
  (get-in (get-concept-map ctx) [uri :broader] #{}))

(defn concept-statistics
  "Get statistics about the concept graph."
  [ctx]
  (let [concept-graph (get-concept-map ctx)
        by-scope (group-by :scope (vals concept-graph))]
    {:total-concepts (count concept-graph)
     :by-scope (into {} (map (fn [[k v]] [k (count v)]) by-scope))
     :with-indicators (count (filter #(seq (:indicators %)) (vals concept-graph)))}))

(defn read-models-get-all-concept-embeddings
  "Read vectors from standalone concepts, filtered before indexing.
   Unique URIs retain URI keys; colliding URIs use [ontology-id uri] keys."
  [ctx & [opts]]
  (let [concepts (filterv :embedding (checked-graph-result (ontology-lib/get-concepts ctx (or opts {}))))
        counts (frequencies (map :uri concepts))]
    (into {} (map (fn [c]
                    [(if (= 1 (get counts (:uri c))) (:uri c) [(:ontology-id c) (:uri c)])
                     (assoc (select-keys c [:uri :ontology-id :scope :embedding])
                            :concept-id (:concept-id c) :model-id (:embedding-model c))])) concepts)))

(defn get-concept-embedding
  "Read a stored vector; qualify the ontology when the URI is ambiguous."
  ([ctx uri] (get (read-models-get-all-concept-embeddings ctx) uri))
  ([ctx ontology-id uri]
   (get (read-models-get-all-concept-embeddings ctx {:ontology-id ontology-id}) uri)))

(defn interface-get-concepts
  "Get concepts from the event-sourced concept graph.
   Options:
   - :scope - Filter by scope
   - :broader-uri - Filter by parent concept"
  [ctx & [opts]]
  (get-concepts ctx opts))

(defn existing-domain-families
  "CV-A item 1 (`DomainChildIdentityIsStable`, `DomainChildrenAreAlwaysConsidered`):
   every tree-class concept minted as a domain family, TENANT-WIDE across
   every parent — provenance :agent-authored, a non-blank :label that is
   not the concept's own bare id (the generic placeholder an ordinary,
   non-domain tree-class concept carries as its label). Most-recently-
   minted first, bounded by `family-label-list-bound`.

   Returns [{:target-id :domain-label :parent-id} ...] — :parent-id is the
   family's own birth shape (its first skos:broader edge), nil when the
   concept carries none.

   A genuine read failure PROPAGATES — never caught here. The classifier's
   `:domain-families-fn` seam (and the reranker's label-list builder) decide
   how a failure is handled; a lookup that cannot be resolved must defer,
   never silently read as \"no families\" (DomainCoverageIsJudgedNotInferred)."
  [ctx]
  (let [concepts (get-concepts ctx {:scope :tree-class})]
    (->> concepts
         (keep (fn [c]
                 (let [label (:label c)
                       uri (:uri c)
                       bare (if (and (string? uri) (str/starts-with? uri "tree-class:"))
                              (subs uri (count "tree-class:"))
                              uri)]
                   (when (and (string? label)
                              (not (str/blank? label))
                              (not= label bare))
                     (let [broader-uri (first (:broader c))]
                       {:target-id (tree-class-uri->target-id uri)
                        :domain-label label
                        :parent-id (when broader-uri (tree-class-uri->target-id broader-uri))
                        :ai.obney.orc.ontology.interface/created-at (:created-at c)})))))
         (sort-by :ai.obney.orc.ontology.interface/created-at #(compare %2 %1))
         (take family-label-list-bound)
         (mapv #(dissoc % :ai.obney.orc.ontology.interface/created-at)))))

(defn existing-domain-child-labels
  "R-Inject specialisation (weed 1.1, DomainChildIdentityIsStable): the labels
   of a tree-class candidate's existing domain children — its narrower
   concepts whose :label is a judged domain label (non-blank and not the
   concept's own identifier; every other tree-class concept carries its
   identifier as its label). Shown to the reranker so a repeat of a known
   domain REUSES the label instead of coining a variant that would mint a
   sibling. Best-effort: a failed read yields nil (the reranker simply is not
   shown children; RS-2's own lookup still defers on failure)."
  [ctx target-id]
  (try
    (let [parent-uri (str "tree-class:" target-id)
          child-uris (or (get-narrower-concepts ctx parent-uri) #{})]
      (->> child-uris
           (keep (fn [uri]
                   (let [label (:label (get-concept-by-uri ctx uri))
                         bare (subs uri (min (count uri) (count "tree-class:")))]
                     (when (and (string? label)
                                (not (str/blank? label))
                                (not= label bare))
                       label))))
           sort
           vec
           not-empty))
    (catch Exception _ nil)))

(defn queries-get-all-concept-embeddings
  "Get all concept embeddings, optionally filtered by scope."
  [ctx & [opts]]
  (read-models-get-all-concept-embeddings ctx opts))

(defn retrieval-semantic-search-concepts
  "Search concepts using embedding similarity.

   Generates an embedding for the query text and finds similar concepts
   based on cosine similarity with stored concept embeddings.

   Args:
     event-store: Grain event store (required)
     query-text: Natural language search query
     opts:
       :scope - Filter to specific ontology scope (:failure, :success, :problem)
       :ontology-id - Filter by single ontology-id
       :ontology-ids - Filter by multiple ontology-ids (returns union)
       :limit - Maximum results (default 10)
       :min-similarity - Minimum cosine similarity threshold (default 0.5)
       :model-id - Embedding model to use (default: all-MiniLM-L6-v2)

   Returns:
     Vector of {:uri :similarity :label :description} sorted by similarity

   Requires concept embeddings to be generated first via ontology/embed-concepts-batch."
  [ctx query-text & {:keys [scope ontology-id ontology-ids limit min-similarity model-id]
                      :or {limit 10 min-similarity 0.5}}]
  (when (and ctx query-text)
    (let [model (or model-id default-model-id)
          filters (cond-> {} scope (assoc :scope scope)
                             ontology-id (assoc :ontology-id ontology-id)
                             ontology-ids (assoc :ontology-ids ontology-ids))
          concepts (checked-graph-result (get-concepts ctx filters))
          candidates (filter #(and (:embedding %) (= model (:embedding-model %))) concepts)]
      (when (seq candidates)
        (when-let [query-vector (embedding-embed-text query-text {:model-id model})]
          (->> candidates
               (keep (fn [concept]
                       (when-let [score (entity-resolver/cosine-similarity query-vector (:embedding concept))]
                         (when (>= score min-similarity)
                           (assoc (select-keys concept [:ontology-id :concept-id :uri :label :description :scope])
                                  :similarity score)))))
               (sort-by (juxt (comp - :similarity) :uri :ontology-id))
               (take limit)
               vec))))))

(defn interface-semantic-search-concepts
  "Compare a query embedding to stored concept vectors from the same model.
   Select graphs with :ontology-id or :ontology-ids and restrict concepts with
   :scope. Accepts :model-id, :min-similarity and :limit. Results include each
   concept's ontology ID, URI, label, description and similarity."
  [ctx query-text & opts]
  (apply retrieval-semantic-search-concepts ctx query-text opts))

(defn- graph-hits [concepts opts]
  (let [{:keys [seed-uris max-depth decay limit ontology-id ontology-ids]
         :or {max-depth 2 decay 0.6 limit 10}} opts
        stored (for [[oid records] (group-by :ontology-id concepts)
                     :let [by-uri (into {} (map (juxt :uri identity)) records)
                           seeds (filter by-uri seed-uris)]
                     :when (seq seeds)
                     hit (retrieval-expand-concept-neighborhood seeds :graph (graph/concepts->graph by-uri)
                                                      :max-depth max-depth :decay decay)
                     :let [concept (get by-uri (:uri hit))]
                     :when concept]
                 (merge hit (select-keys concept [:ontology-id :concept-id :label :description :scope])))
        static-seeds (when-not (or ontology-id ontology-ids)
                       (remove (set (map :uri concepts)) seed-uris))
        predefined (when (seq static-seeds)
                     (retrieval-expand-concept-neighborhood static-seeds :max-depth max-depth :decay decay))]
    (->> (concat stored predefined)
         (sort-by (juxt (comp - :score) :uri :ontology-id))
         (take (* 2 limit)) vec)))

(defn- qualify-index-hits [hits by-uri scoped?]
  (keep (fn [{:keys [uri ontology-id] :as hit}]
          (let [candidates (cond->> (get by-uri uri)
                             ontology-id (filter #(= ontology-id (:ontology-id %))))]
            (cond
              (= 1 (count candidates))
              (merge hit (select-keys (first candidates) [:ontology-id :concept-id :label :description :scope]))
              (and (empty? candidates) (not scoped?) (nil? ontology-id)) hit)))
        hits))

(defn- retrieval-concepts [ctx opts]
  (when (:event-store ctx)
    (let [filters (into {} (remove (comp nil? val)) (select-keys opts [:ontology-id :ontology-ids :scope]))]
      (checked-graph-result (get-concepts ctx filters)))))

(defn hybrid-search
  "Hybrid search combining graph-based BFS, embedding similarity, and ColBERT via RRF.

   This is the primary search function that leverages up to three signals:
   1. Graph structure - BFS spreading activation from seed concepts
   2. Semantic similarity - Embedding-based similarity to query text
   3. ColBERT late-interaction - Token-level retrieval (when index provided)

   RRF (Reciprocal Rank Fusion) merges all ranked lists to produce
   results that capture structural relationships, semantic meaning,
   AND token-level precision.

   Args:
     event-store: Grain event store (also passed as ctx for ColBERT)
     opts:
       :seed-uris - Collection of starting concept URIs for graph expansion
       :query-text - Natural language query for embedding/ColBERT search
       :scope - Filter to specific ontology scope
       :ontology-id - Filter by single ontology-id
       :ontology-ids - Filter by multiple ontology-ids (returns union)
       :limit - Maximum results (default 10)
       :min-similarity - Minimum embedding similarity (default 0.3)
       :max-depth - BFS expansion depth (default 2)
       :decay - BFS decay factor (default 0.6)
       :colbert-index-id - UUID of ColBERT index (required for ColBERT signal)
       :weights - {:graph N :embedding N :colbert N} weights for RRF
                  (default {:graph 1.0 :embedding 1.0 :colbert 1.0})
       :signals - Set of enabled signals #{:graph :embedding :colbert}
                  (default: all signals enabled)

   Returns:
     {:results [{:uri :score :graph-rank :embedding-rank :colbert-rank :label :description}]
      :graph-results [...]
      :embedding-results [...]
      :colbert-results [...]
      :method \"rrf\"
      :batches-used [:graph :embedding :colbert]}"
  [ctx {:keys [seed-uris query-text scope ontology-id ontology-ids limit min-similarity max-depth decay
                       colbert-index-id weights signals]
                :or {limit 10 min-similarity 0.3 max-depth 2 decay 0.6
                     weights {:graph 1.0 :embedding 1.0 :colbert 1.0}
                     signals #{:graph :embedding :colbert}} :as opts}]
  (let [
        graph-enabled? (contains? signals :graph)
        embedding-enabled? (contains? signals :embedding)
        colbert-enabled? (contains? signals :colbert)

        concepts (when (or (and graph-enabled? (seq seed-uris)) (and colbert-enabled? colbert-index-id))
                   (retrieval-concepts ctx opts))
        graph-results (when (and graph-enabled? (seq seed-uris)) (graph-hits concepts opts))

        embedding-results (when (and embedding-enabled? query-text)
                            (retrieval-semantic-search-concepts ctx query-text
                                                       :scope scope
                                                       :ontology-id ontology-id
                                                       :ontology-ids ontology-ids
                                                       :model-id (:model-id opts)
                                                       :limit (* 2 limit)
                                                       :min-similarity min-similarity))

        colbert-results (when (and colbert-enabled? query-text colbert-index-id)
                          (colbert-search-concepts ctx query-text
                                                    :colbert-index-id colbert-index-id
                                                    :limit (* 2 limit)
                                                    :weight (:colbert weights 1.0)))]

    (fuse-and-enrich {:graph-results graph-results
                      :embedding-results embedding-results
                      :colbert-results (qualify-index-hits colbert-results (group-by :uri concepts) (or ontology-id ontology-ids))
                      :weights weights
                      :limit limit
                      :signals signals})))

(defn retrieval-hybrid-search-failures
  "Search failure concepts using hybrid graph + embedding + ColBERT search.

   Specialized search for finding relevant failure types.

   Args:
     event-store: Grain event store
     query-text: Description of the failure to find
     opts:
       :seed-failure-uri - Optional seed failure URI for graph expansion
       :colbert-index-id - Optional ColBERT index for enhanced retrieval
       :signals - Set of enabled signals #{:graph :embedding :colbert} (default: all)
       :limit - Maximum results (default 5)

   Returns:
     Vector of failure concepts with scores"
  [ctx query-text & {:keys [seed-failure-uri colbert-index-id signals limit]
                              :or {limit 5
                                   signals #{:graph :embedding :colbert}}}]
  (let [seed-uris (if seed-failure-uri
                    [seed-failure-uri]
                    ["failure:Root"])

        {:keys [results]} (hybrid-search ctx
                                          {:seed-uris seed-uris
                                           :query-text query-text
                                           :scope :failure
                                           :colbert-index-id colbert-index-id
                                           :signals signals
                                           :limit limit
                                           :min-similarity 0.3})]
    (filterv #(= :failure (:scope %)) results)))

(defn interface-hybrid-search-failures
  "Search failure concepts using hybrid graph + embedding search.

   Specialized search for finding relevant failure types.

   Args:
     event-store: Grain event store
     query-text: Description of the failure to find
     opts:
       :seed-failure-uri - Optional seed failure URI for graph expansion
       :limit - Maximum results (default 5)

   Returns:
     Vector of failure concepts with scores"
  [event-store query-text & {:keys [seed-failure-uri limit]
                              :or {limit 5}}]
  (retrieval-hybrid-search-failures event-store query-text
                                     :seed-failure-uri seed-failure-uri
                                     :limit limit))

(defn retrieval-hybrid-search-patterns
  "Search success patterns using hybrid graph + embedding + ColBERT search.

   Specialized search for finding relevant success patterns.

   Args:
     event-store: Grain event store
     query-text: Description of the pattern to find
     opts:
       :seed-pattern-uri - Optional seed pattern URI for graph expansion
       :colbert-index-id - Optional ColBERT index for enhanced retrieval
       :signals - Set of enabled signals #{:graph :embedding :colbert} (default: all)
       :limit - Maximum results (default 5)

   Returns:
     Vector of success pattern concepts with scores"
  [ctx query-text & {:keys [seed-pattern-uri colbert-index-id signals limit]
                              :or {limit 5
                                   signals #{:graph :embedding :colbert}}}]
  (let [seed-uris (if seed-pattern-uri
                    [seed-pattern-uri]
                    ["success:Root"])

        {:keys [results]} (hybrid-search ctx
                                          {:seed-uris seed-uris
                                           :query-text query-text
                                           :scope :success
                                           :colbert-index-id colbert-index-id
                                           :signals signals
                                           :limit limit
                                           :min-similarity 0.3})]
    (filterv #(= :success (:scope %)) results)))

(defn interface-hybrid-search-patterns
  "Search success patterns using hybrid graph + embedding search.

   Specialized search for finding relevant success patterns.

   Args:
     event-store: Grain event store
     query-text: Description of the pattern to find
     opts:
       :seed-pattern-uri - Optional seed pattern URI for graph expansion
       :limit - Maximum results (default 5)

   Returns:
     Vector of success pattern concepts with scores"
  [event-store query-text & {:keys [seed-pattern-uri limit]
                              :or {limit 5}}]
  (retrieval-hybrid-search-patterns event-store query-text
                                     :seed-pattern-uri seed-pattern-uri
                                     :limit limit))

(defn build-concept-graph
  "Build a concept graph from event store or static ontology.

   Args:
     ctx: Context with :event-store and :tenant-id (optional, uses static if nil)

   Returns:
     Graph map with :nodes and :edges suitable for graph algorithms"
  [{:keys [event-store] :as ctx}]
  (let [
        concepts (if event-store
                   (get-concept-map ctx)
                   (reduce (fn [acc concept]
                             (assoc acc (:uri concept)
                                    {:uri (:uri concept)
                                     :label (:label concept)
                                     :scope (:scope concept)
                                     :broader (set (or (:broader concept) []))
                                     :narrower #{}
                                     :related #{}}))
                           {}
                           (get-all-static-concepts)))]
    (graph/concepts->graph concepts)))

(defn hybrid-search-batch
  "Batched hybrid-search over MANY query-texts. The ColBERT signal is computed in ONE
   batched pass (index loaded ONCE, one bridge round-trip); embedding + graph stay
   per-query (in-JVM, no subprocess). Returns a vector of per-query result-maps, each
   shaped EXACTLY like hybrid-search's output, aligned to `:query-texts`.

   Use this instead of mapping hybrid-search per query over a whole transcript: it
   collapses N ColBERT round-trips (and N index loads) into one, with no per-search
   reload, while keeping the embedding+ColBERT RRF fusion substance-identical to the
   single-query path.

   Args:
     ctx - Context map containing :event-store
     opts - same as hybrid-search, except:
       :query-texts - Vector of natural-language queries (instead of :query-text)
       :seed-uris   - shared graph seeds applied to every query (usually nil)"
  [ctx {:keys [query-texts seed-uris scope ontology-id ontology-ids limit min-similarity
               max-depth decay colbert-index-id weights signals]
        :or {limit 10 min-similarity 0.3 max-depth 2 decay 0.6
             weights {:graph 1.0 :embedding 1.0 :colbert 1.0}
             signals #{:graph :embedding :colbert}} :as opts}]
  (let [graph-enabled? (contains? signals :graph)
        embedding-enabled? (contains? signals :embedding)
        colbert-enabled? (contains? signals :colbert)
        query-texts (vec query-texts)
        concepts (when (or (and graph-enabled? (seq seed-uris)) (and colbert-enabled? colbert-index-id))
                   (retrieval-concepts ctx opts))
        by-uri (group-by :uri concepts)
        graph-results (when (and graph-enabled? (seq seed-uris)) (graph-hits concepts opts))
        colbert-batches (when (and colbert-enabled? (seq query-texts) colbert-index-id)
                          (colbert-search-concepts-batch ctx query-texts
                                                         :colbert-index-id colbert-index-id
                                                         :limit (* 2 limit)
                                                         :weight (:colbert weights 1.0)))]
    (mapv (fn [i query-text]
            (let [embedding-results (when (and embedding-enabled? query-text)
                                      (retrieval-semantic-search-concepts ctx query-text
                                                                 :scope scope
                                                                 :ontology-id ontology-id
                                                                 :ontology-ids ontology-ids
                                                                 :model-id (:model-id opts)
                                                                 :limit (* 2 limit)
                                                                 :min-similarity min-similarity))
                  colbert-results (when colbert-batches (nth colbert-batches i nil))]
              (fuse-and-enrich {:graph-results graph-results
                                :embedding-results embedding-results
                                :colbert-results (qualify-index-hits colbert-results by-uri (or ontology-id ontology-ids))
                                :weights weights
                                :limit limit
                                :signals signals})))
          (range)
          query-texts)))

(defn- domain-label-for
  "RS-5 (gap B): the domain label lives on the class's tree-class CONCEPT —
   RS-3's `mint-domain-child` stamps the judged domain label directly onto
   the child's concept at birth (RS-P2: the command path, never a
   description body write — CC-6) — never in the assembled description
   `harvest-body` transplants. Returns nil (no ctx/class-id, no concept, a
   blank label, or the generic placeholder `ensure-tree-class-concept!`
   lazy-creates for an ordinary non-domain tree-class, whose :label equals
   its own id) so an ordinary tree-class's harvest stays byte-shaped."
  [ctx class-id]
  (when (and ctx class-id)
    (let [concept (get-concept-by-uri ctx (str "tree-class:" class-id))
          label (:label concept)]
      (when (and (string? label)
                 (not (str/blank? label))
                 (not= label (str class-id)))
        label))))

(defn harvest-body
  "Assemble the harvested behavior's body by REUSING the consolidator's
   already-synthesized tree-class description (no second synthesis LLM):
   transplant :capabilities/:strengths/:weaknesses/:representative-uses/
   :avoid-when/:summary, add the worked DSL as :recommended-pattern, and
   stamp :version + :consolidated-from-event-count so anti-recency engages.
   Returns nil when the class has no consolidated description yet.

   RS-5 (gap B): the 4-arg arity additionally stamps :domain-label when the
   class is a domain child (`domain-label-for`) — the label lives on the
   CONCEPT, so the description alone can't carry it forward. The 2-arg
   arity (desc + occurrences, the propagated RR-20 contract) is UNCHANGED —
   every existing caller's body stays byte-identical."
  ([desc occurrences] (harvest-body nil desc occurrences nil))
  ([ctx desc occurrences class-id]
   (when desc
     (cond-> {:capabilities         (vec (:capabilities desc))
              :strengths            (vec (:strengths desc))
              :weaknesses           (vec (:weaknesses desc))
              :representative-uses  (vec (:representative-uses desc))
              :avoid-when           (vec (:avoid-when desc))
              :summary              (or (:summary desc) "")
              :version              1
              :consolidated-from-event-count occurrences}
       (best-recommended-pattern desc)
       (assoc :recommended-pattern (best-recommended-pattern desc))
       (domain-label-for ctx class-id)
       (assoc :domain-label (domain-label-for ctx class-id))))))

(defn classify-behaviors
  "R05b: pure behavioral classification. Given a task signature +
   threshold (+ optional :structural-context + :top-n), returns the
   top-N behavioral-subtree examples from the R05a Layer-2 corpus.

   The corpus is FEW-SHOT EXAMPLE MATERIAL for the recursive RLM
   researcher, NOT a routing taxonomy. classify-behaviors returns
   top-N candidates with reasoning + recommended-pattern snippets;
   the model reasons over them and decides reuse/adapt/mint.

   Args:
     ctx  — context with :event-store / :tenant-id / etc. (passed
            through to search-descriptions)
     opts — {:task-signature :threshold :structural-context :top-n}
       :task-signature      — required string. Caller-built static-config
                              text (same shape classify-task receives).
       :threshold           — required double in [0.0, 1.0]. Candidates
                              below this drop out of the result.
       :structural-context  — optional UUID. The structural tree-class
                              already assigned to this task (typically
                              the :assigned-tree-id from a prior
                              classify-task call). When provided, the
                              candidate set is narrowed via the
                              behavior:composes-into graph to behaviors
                              that compose into this shell. RETRIEVAL
                              HINT — when the narrowed set is empty
                              (no Layer-2 behaviors compose into this
                              shell yet), the result FALLS BACK to the
                              unfiltered candidate set so the model
                              still sees relevant examples.
       :top-n               — optional int. Maximum number of behavioral
                              candidates to return above threshold.
                              Defaults to 3.

   Returns:
     {:behaviors        [{:behavior-id <uuid>
                          :confidence <0.0-1.0>
                          :was-fresh-mint? <bool>
                          :reasoning <string>
                          :rerank-source <:reranker | :colbert-fallback
                                          | :timeout-fallback>}
                         ...]
      :rerank-fallback? <bool>}

   When NO candidate passes :threshold, :behaviors carries exactly one
   fresh-mint marker with :was-fresh-mint? true + a fresh UUID +
   reasoning string. Per R01 (+ RR-1): :rerank-fallback? is derived from
   top-1's :rerank-source — true for any member of
   `rerank-fallback-sources` (:colbert-fallback OR :timeout-fallback)."
  [ctx opts]
  (when-let [err (m/explain classify-behaviors-opts-schema opts)]
    (throw (ex-info "Invalid classify-behaviors opts"
                    {:explain err
                     :opts opts})))
  (let [{:keys [task-signature threshold top-n structural-context model]
         :or {top-n default-classify-behaviors-top-n}} opts
        candidates (search-descriptions ctx
                     {:query task-signature
                      :granularity :behavioral-subtree
                      :rerank-with-intent behavioral-classifier-intent
                      :k (* 2 top-n)
                      :model model})
        filtered (if structural-context
                   (let [concepts (get-concept-map ctx)
                         shell-uri (str "tree-class:" structural-context)
                         composer-uris (get-in concepts [shell-uri :composed-by] #{})
                         composer? (fn [c]
                                     (contains? composer-uris
                                                (str "behavioral-subtree:"
                                                     (-> c :document-metadata :target-id))))
                         composers (filterv composer? candidates)
                         non-composers (filterv (complement composer?) candidates)]
                     (vec (distinct (concat composers non-composers))))
                   candidates)
        top-1 (first filtered)
        query-tokens (->> (clojure.string/lower-case task-signature)
                          (re-seq #"[a-z0-9][a-z0-9-]+")
                          (filter #(>= (count %) 3))
                          set)
        exact-token-candidate
        (when (>= (count query-tokens) 3)
          (some (fn [candidate]
                  (let [content-tokens (->> (or (:content candidate) "")
                                            clojure.string/lower-case
                                            (re-seq #"[a-z0-9][a-z0-9-]+")
                                            set)]
                    (when (clojure.set/subset? query-tokens content-tokens)
                      candidate)))
                filtered))
        rerank-fallback? (and (some? top-1)
                              (rerank-fallback?* top-1))
        above-threshold (filter #(let [s (or (:fitness-score %) 0.0)]
                                   (>= s threshold))
                                filtered)
        kept (take top-n above-threshold)]
    (cond
      (and rerank-fallback? exact-token-candidate)
      {:behaviors
       [{:behavior-id (coerce-to-uuid
                       (-> exact-token-candidate :document-metadata :target-id))
         :confidence 1.0
         :was-fresh-mint? false
         :reasoning "Exact query-token coverage in the canonical behavior summary"
         :rerank-source :exact-token-fallback}]
       :outcome :matched
       :rerank-fallback? true}

      rerank-fallback?
      {:behaviors
       (mapv (fn [c]
               {:behavior-id (coerce-to-uuid
                               (-> c :document-metadata :target-id))
                :confidence (or (:fitness-score c) 0.0)
                :was-fresh-mint? false
                :reasoning (or (:reasoning c) "")
                :rerank-source (:rerank-source c)})
             (take top-n filtered))
       :outcome :uncertain
       :rerank-fallback? true}

      (seq kept)
      {:behaviors
       (mapv (fn [c]
               {:behavior-id (coerce-to-uuid
                               (-> c :document-metadata :target-id))
                :confidence (or (:fitness-score c) 0.0)
                :was-fresh-mint? false
                :reasoning (or (:reasoning c) "")
                :rerank-source (:rerank-source c)})
             kept)
       :outcome :matched
       :rerank-fallback? rerank-fallback?}

      :else
      {:behaviors
       [{:behavior-id (random-uuid)
         :confidence 0.0
         :was-fresh-mint? true
         :reasoning (if (seq candidates)
                      "No candidate above threshold; minting fresh"
                      "No behavioral candidates returned; minting fresh")
         :rerank-source (:rerank-source top-1)}]
       :outcome :novel
       :rerank-fallback? rerank-fallback?})))

(defn require-result! [result]
  (if (:cognitect.anomalies/category result)
    (throw (ex-info (:cognitect.anomalies/message result) result))
    result))

(defn get-ontology-ttl [ctx ontology-id]
  (:ttl-output (require-result! (ontology-lib/export-snapshot ctx ontology-id))))

(defn was-processed? [ctx ontology-id source-uri content]
  (:processed? (check-source-processed ctx ontology-id source-uri content)))

(defn get-all-sources [ctx ontology-id & [opts]]
  (cond->> (registered-sources ctx ontology-id)
    (:source-type opts) (filterv #(= (:source-type opts) (:type %)))))

(defn evolutionary-get-concepts [ctx ontology-id] (ontology-lib/get-concepts ctx {:ontology-id ontology-id}))

(defn map-query-result [result f]
  (if (:cognitect.anomalies/category result) result (f result)))

(defn get-concepts-by-source [ctx ontology-id source-id]
  (map-query-result (evolutionary-get-concepts ctx ontology-id)
                (fn [concepts] (filterv #(= (str source-id) (get-in % [:provenance :source-reference])) concepts))))

(defn get-concepts-by-type [ctx ontology-id entity-type]
  (map-query-result (evolutionary-get-concepts ctx ontology-id)
                (fn [concepts] (filterv #(= entity-type (:entity-type %)) concepts))))

(defn get-canonical-uri [ctx ontology-id uri]
  (map-query-result (ontology-lib/get-canonical-mappings ctx ontology-id) #(get % uri)))

(defn- commit!
  "Retry store CAS conflicts using the same semantic request key."
  [operation]
  (loop [attempt 0]
    (let [result (operation)]
      (if (and (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result))
               (:cas result) (< attempt 20))
        (do (Thread/sleep (long (rand-int (min 64 (bit-shift-left 2 (min attempt 5))))))
            (recur (inc attempt)))
        (checked-graph-result result)))))

(defn- ensure-record!
  "Accept a record created concurrently under another request key after reading it back."
  [present? create!]
  (when-not (present?)
    (commit! (fn []
               (let [result (create!)]
                 (if (and (= :cognitect.anomalies/conflict (:cognitect.anomalies/category result))
                          (present?))
                   nil
                   result))))))

(defn- retry-key [value]
  (java.util.UUID/nameUUIDFromBytes (.getBytes (pr-str value) "UTF-8")))

(defn managed-graph-definition [scope]
  (let [tree? (= :tree-class scope)
        label (if tree? "Tree class ontology" "Behavioral subtree ontology")]
    {:request-id (retry-key [:orc/managed-graph scope])
     :scope scope :name label :description (str "ORC managed " label)
     :base-uri (if tree? "tree-class:" "behavioral-subtree:")
     :predicate-policies {"behavior:composes-into" {:cross-ontology? true}}}))

(defn ontology-id! [ctx scope]
  (:ontology-id (commit! #(ontology-lib/create-ontology! ctx (managed-graph-definition scope)))))

(defn ensure-concept! [ctx scope target]
  (let [oid (ontology-id! ctx scope)
        uri (str (name scope) ":" target)]
    (ensure-record! #(checked-graph-result (ontology-lib/get-concept-by-uri ctx oid uri))
                    #(ontology-lib/create-concept! ctx oid
                 {:request-id (retry-key [:orc/concept oid uri])
                  :uri uri :scope scope :label (str target)
                  :description (str (if (= scope :tree-class) "Tree-class" "Behavioral subtree")
                                    " concept for " target)
                  :provenance {:kind :system-static}}))
    {:ontology-id oid :uri uri}))

(defn ensure-edge! [ctx source target predicate]
  (let [edge {:source-ontology-id (:ontology-id source) :source-uri (:uri source)
              :target-ontology-id (:ontology-id target) :target-uri (:uri target)
              :predicate predicate}]
    (ensure-record!
      #(some (fn [existing] (= edge (select-keys existing (keys edge))))
             (checked-graph-result (ontology-lib/get-relationships ctx (:ontology-id source))))
      #(ontology-lib/create-relationship! ctx
         (assoc edge :request-id (retry-key [:orc/edge (:ontology-id source) (:uri source)
                                             predicate (:ontology-id target) (:uri target)]))))))

(defn on-tree-description-updated-project-concept
  [{:keys [event] :as ctx}]
  (when-let [parent (get-in event [:body :parent-tree-id])]
    (when (= :tree-fingerprint (:target-type event))
      (let [source (ensure-concept! ctx :tree-class (:target-id event))
            target (ensure-concept! ctx :tree-class parent)]
        (ensure-edge! ctx source target "skos:broader")))))

(defn on-behavioral-subtree-description-updated-project-concept
  [{:keys [event] :as ctx}]
  (let [{:keys [scope parent-behavior composes-into]} (:body event)]
    (when (and (= :tree-fingerprint (:target-type event)) (= :behavioral-subtree scope))
      (let [source (ensure-concept! ctx scope (:target-id event))]
        (when parent-behavior
          (ensure-edge! ctx source (ensure-concept! ctx scope parent-behavior) "skos:broader"))
        (doseq [shell composes-into]
          (ensure-edge! ctx source (ensure-concept! ctx :tree-class shell)
                                "behavior:composes-into"))))))

(defn dispatch-observed-composes-into-edges!
  "R05d: for each behavioral-subtree id the consolidator inferred for
   this tree-fingerprint, dispatch :ontology/create-relationship with
   predicate \"behavior:composes-into\" when the edge is not already
   present in the concept graph. Sticky once added (the read-model's
   :composes-into set is conj-only); the no-op skip makes repeat calls
   safe.

   No-op when `behavior-ids` is nil or empty.

   Public for test access; the consolidator calls this after the
   description-updated event lands."
  [context shell-id behavior-ids]
  (when (seq behavior-ids)
    (let [target (ensure-concept! context :tree-class shell-id)]
      (doseq [id behavior-ids]
        (ensure-edge! context (ensure-concept! context :behavioral-subtree id)
                              target "behavior:composes-into")))))

(defn run-workflow [ctx workflow keys]
  (try
    (let [result (workflow (dissoc ctx :command) (select-keys (:command ctx) keys))]
      {:command-result/events (vec (:events result))
       :command-result/data (dissoc result :events)})
    (catch clojure.lang.ExceptionInfo e
      (if (:cognitect.anomalies/category (ex-data e)) (ex-data e) (throw e)))))

(def build-lifecycle-events #{:evolutionary/build-started :evolutionary/build-completed :evolutionary/build-failed})

(defn prepare-source [source]
  (let [loaded (load-source-content source)
        content (:content loaded)]
    {:source source :loaded-content content :db-path (:db-path loaded)
     :source-id (or (:source-id source) (random-uuid))
     :content-hash (if-let [path (:db-path loaded)]
                     (sources/compute-content-hash path nil)
                     (sources/sha256 content))}))

(defn embed-stored-concept! [ctx concept fields]
  (let [text (embedding-concept->embedding-text concept (or (some-> fields set) #{:label :description}))
        vector (embedding-embed-text text)]
    (if-not vector
      {:cognitect.anomalies/category :cognitect.anomalies/fault :cognitect.anomalies/message "Failed to generate embedding" :uri (:uri concept)}
      (let [result (ontology-lib/update-concept! ctx (:ontology-id concept) (:concept-id concept)
                     {:changes {:embedding (mapv double vector) :embedding-model default-model-id}})]
        (if (:cognitect.anomalies/category result) result
            {:uri (:uri concept) :ontology-id (:ontology-id concept)
             :dimensions (count vector) :text-embedded text})))))
