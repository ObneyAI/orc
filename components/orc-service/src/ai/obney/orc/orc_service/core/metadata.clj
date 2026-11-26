(ns ai.obney.orc.orc-service.core.metadata
  "Tree metadata extraction.

   Extracts and stores metadata about behavior tree structure and capabilities,
   enabling search by problem type and capabilities."
  (:require [clojure.string :as str]
            [ai.obney.orc.orc-service.core.read-models :as rm]))

;; =============================================================================
;; Metadata Extraction
;; =============================================================================

(defn- classify-node-type
  "Classify a node's type, handling leaf nodes specially.

   Leaf nodes are further classified by their executor:
   - :executor :ai / :decision -> :llm (a decision calls a model)
   - :executor :code -> :code
   - otherwise -> :leaf"
  [node]
  (let [base-type (:type node)]
    (if (= :leaf base-type)
      (case (:executor node)
        (:ai :decision) :llm
        :code :code
        :leaf)
      base-type)))

(defn extract-tree-metadata
  "Extract metadata from a built sheet.

   Analyzes the tree structure to identify:
   - Node types used
   - Structural patterns (validation, retry, parallel, etc.)
   - Inferred capabilities
   - Inferred success patterns

   Args:
   - event-store: Event store for reading sheet data
   - sheet-id: UUID of the sheet to analyze

   Returns map with extracted metadata."
  [{:keys [event-store tenant-id] :as ctx} sheet-id]
  (let [sheet (rm/get-sheet ctx sheet-id)
        nodes (rm/get-nodes-for-sheet ctx sheet-id)
        node-types (set (map classify-node-type nodes))
        node-names (map (comp str/lower-case str :name) nodes)]

    {:sheet-id sheet-id
     :name (:name sheet)
     :node-types (vec node-types)
     :node-count (count nodes)

     ;; Structural detection
     :has-validation? (boolean (some #(str/includes? % "valid") node-names))
     :has-retry? (contains? node-types :fallback)
     :has-parallel? (contains? node-types :parallel)
     :has-map-each? (contains? node-types :map-each)

     ;; Inferred capabilities based on node types
     :capabilities (cond-> []
                     (contains? node-types :repl-researcher) (conj "tool-use")
                     (contains? node-types :parallel) (conj "parallel-processing")
                     (contains? node-types :map-each) (conj "batch-iteration")
                     (some #(str/includes? % "valid") node-names) (conj "validation-loop")
                     (some #(str/includes? % "research") node-names) (conj "research")
                     (some #(str/includes? % "verify") node-names) (conj "verification"))

     ;; Inferred patterns based on structure
     :patterns (cond-> []
                 (contains? node-types :parallel)
                   (conj "success:ParallelGathering")
                 (some #(re-find #"schema|explicit" %) node-names)
                   (conj "success:ExplicitSchema")
                 (contains? node-types :fallback)
                   (conj "success:RetryWithFallback")
                 (some #(str/includes? % "valid") node-names)
                   (conj "success:ValidationLoop"))

     ;; Placeholders for user-defined and runtime data
     :problem-types []
     :description nil
     :execution-count nil
     :avg-score nil
     :last-executed nil
     :extracted-at (str (java.time.Instant/now))}))

(defn infer-problem-types
  "Heuristically infer problem types from tree structure.

   Analyzes node names and blackboard keys to guess what types of
   problems this tree is designed to solve.

   Args:
   - metadata: Extracted metadata (for node info)
   - nodes: Vector of node maps
   - blackboard: Vector of blackboard entry maps

   Returns vector of problem URIs."
  [_metadata nodes blackboard]
  (let [node-names (map (comp str/lower-case str :name) nodes)
        bb-keys (map (comp str/lower-case str :key) blackboard)
        all-terms (concat node-names bb-keys)]
    (cond-> []
      ;; Classification indicators
      (some #(re-find #"classif|categor|label|type" %) all-terms)
        (conj "problem:Classification")

      ;; Scoring indicators
      (some #(re-find #"score|rating|rank|evaluat" %) all-terms)
        (conj "problem:Scoring")

      ;; Extraction indicators
      (some #(re-find #"extract|parse|pull|field" %) all-terms)
        (conj "problem:DataExtraction")

      ;; Summarization indicators
      (some #(re-find #"summar|condense|brief|tldr" %) all-terms)
        (conj "problem:Summarization")

      ;; Generation indicators
      (some #(re-find #"generat|creat|write|draft|compos" %) all-terms)
        (conj "problem:Generation")

      ;; QA indicators
      (some #(re-find #"question|answer|qa|respond" %) all-terms)
        (conj "problem:QuestionAnswering")

      ;; Transformation indicators
      (some #(re-find #"transform|convert|format|translat" %) all-terms)
        (conj "problem:Transformation")

      ;; Comparison indicators
      (some #(re-find #"compar|diff|match|similar" %) all-terms)
        (conj "problem:Comparison"))))

(defn merge-metadata
  "Merge extracted metadata with user-provided overrides.

   Args:
   - extracted: Metadata from extract-tree-metadata
   - overrides: Map with optional :problem-types, :description, etc.

   Returns merged metadata map."
  [extracted overrides]
  (let [{:keys [problem-types description]} overrides]
    (cond-> extracted
      (seq problem-types) (assoc :problem-types problem-types)
      description (assoc :description description))))
