(ns ai.obney.orc.ontology.core.rule-extraction
  "Domain-agnostic rule extraction from successful episodes.

   Analyzes tree-strength-recorded events with rich context to extract
   condition-action rules that can be injected into future LLM prompts.

   Works with ANY domain - drones, legal, sales, construction, etc.
   Domain-specific interpretation happens via the LLM prompt.

   Key insight: Instead of storing abstract pattern labels like 'PrecisionHover',
   we extract explicit rules like:
     When: [domain conditions met]
     Action: [domain-specific action]
     Evidence: Episodes 3, 7, 12 (success rate: 92%)

   Integration:
   - Reads :ontology/tree-strength-recorded events with context-conditions
   - Uses LLM to extract condition-action patterns for any domain
   - Emits :ontology/learned-rule-extracted events"
  (:require [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [clojure.string :as str]))

;; =============================================================================
;; Event Extraction
;; =============================================================================

(defn get-successful-episodes
  "Get successful episode events with rich context for rule extraction.

   Reads :ontology/tree-strength-recorded events and filters to those
   with context (either new context-conditions or legacy state-conditions).

   Args:
   - ctx: Context with event-store
   - tree-id: UUID of the tree to analyze
   - options:
     - :min-confidence - Minimum confidence threshold (default 0.8)
     - :limit - Max episodes to return (default 50)

   Returns vector of maps with episode context for rule extraction."
  [{:keys [event-store tenant-id] :as ctx} tree-id {:keys [min-confidence limit]
                                                    :or {min-confidence 0.8 limit 50}}]
  (let [;; Read strength events for this tree
        strength-events (into [] (es/read event-store
                                          {:types #{:ontology/tree-strength-recorded}
                                           :tags #{[:tree tree-id]}
                                           :tenant-id tenant-id}))

        ;; Filter to those with rich context (support both new and legacy field names)
        ;; New: :context-conditions, Legacy: :state-conditions
        with-context (->> strength-events
                          (filter #(and (or (:scenario-context %)
                                            (:domain-type %))  ;; Has some context info
                                        (or (:context-conditions %)
                                            (:state-conditions %))  ;; Has conditions
                                        (>= (:confidence %) min-confidence)))
                          (take limit)
                          vec)]
    with-context))

(defn format-episode-for-extraction
  "Format a successful episode for rule extraction analysis.

   Domain-agnostic: preserves all conditions and actions as-is.
   The LLM will interpret them based on the domain context.

   Supports both new field names (context-conditions) and legacy (state-conditions)."
  [{:keys [pattern-uri confidence scenario-context context-conditions state-conditions
           action-taken domain-type expected-outcome recorded-at]}]
  (let [;; Use new field names if available, fall back to legacy
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
     :conditions conditions  ;; Pass through all conditions as-is
     :action (when action-taken
               {:type (:type action-taken)
                :target (:target action-taken)
                :reason (:reason action-taken)})
     :timestamp recorded-at}))

;; =============================================================================
;; Code Node Executors (for workflow)
;; =============================================================================

(defn load-episodes-for-extraction
  "Code node executor: Load and format successful episodes."
  [{:keys [ctx inputs]}]
  (let [tree-id (get inputs "tree-id")
        episodes (get-successful-episodes ctx tree-id {:min-confidence 0.8 :limit 50})
        formatted (mapv format-episode-for-extraction episodes)]
    {"episode-count" (count formatted)
     "episodes" formatted}))

(defn group-episodes-by-outcome
  "Code node executor: Group episodes by expected outcome for pattern analysis.

   Domain-agnostic: groups by expected-outcome (string) rather than fixed behavior enums.
   Summarizes conditions as generic key-value aggregations."
  [{:keys [inputs]}]
  (let [episodes (get inputs "episodes")
        grouped (->> episodes
                     (group-by :expected-outcome)
                     (map (fn [[outcome eps]]
                            (let [;; Aggregate numeric conditions across episodes
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

;; =============================================================================
;; Rule Extraction Workflow
;; =============================================================================

(def ^:private condition-value-schema
  [:or :string :int :double :boolean :keyword
   [:vector [:or :string :int :double :boolean :keyword]]])

(def ^:private conditions-schema
  [:map-of :keyword condition-value-schema])

(def ^:private scenario-schema
  [:map
   [:name {:optional true} :string]
   [:difficulty {:optional true} [:or :string :keyword :int :double]]])

(def ^:private action-schema
  [:map
   [:type {:optional true} [:or :string :keyword]]
   [:target {:optional true} [:or :string :keyword]]
   [:reason {:optional true} :string]])

(def ^:private episode-schema
  [:map
   [:pattern-uri :string]
   [:confidence :double]
   [:domain-type :string]
   [:expected-outcome {:optional true} [:or :string :keyword]]
   [:scenario {:optional true} scenario-schema]
   [:conditions conditions-schema]
   [:action {:optional true} action-schema]])

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
  (sheet/workflow "ontology-rule-extraction"
    (sheet/blackboard
      {;; Input - domain configuration
       :tree-id :uuid
       :domain-type {:default "unknown"} :string
       :domain-description {:default "General task execution"} :string

       ;; Intermediate
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

       ;; Output: extracted rules with natural language descriptions
       :extracted-rules [:vector [:map
                                  [:condition-description :string]
                                  [:conditions conditions-schema]
                                  [:action-description :string]
                                  [:action action-schema]
                                  [:confidence :double]
                                  [:success-rate :double]
                                  [:evidence-count :int]
                                  [:expected-outcome :string]]]})

    (sheet/sequence "extract"
      ;; Step 1: Load successful episodes
      (sheet/code "load-episodes"
        :fn "ai.obney.orc.ontology.core.rule-extraction/load-episodes-for-extraction"
        :reads ["tree-id"]
        :writes ["episode-count" "episodes"])

      ;; Step 2: Group by expected outcome
      (sheet/code "group-episodes"
        :fn "ai.obney.orc.ontology.core.rule-extraction/group-episodes-by-outcome"
        :reads ["episodes"]
        :writes ["grouped-episodes"])

      ;; Step 3: Extract rules with LLM (domain-agnostic prompt)
      (sheet/llm "extract-rules"
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
  (sheet/build-workflow! ctx rule-extraction-workflow))

;; =============================================================================
;; Public API
;; =============================================================================

(defn extract-rules
  "High-level API to extract rules from a tree's successful episodes.

   Domain-agnostic: Works with any domain by accepting domain-type and domain-description.

   Args:
   - ctx: Context with event-store
   - tree-id: Tree to analyze
   - options:
     - :domain-type - Domain identifier, e.g. 'drone-control', 'legal-review' (required)
     - :domain-description - Human-readable context for LLM (required)
     - :min-episodes - Minimum episodes required (default 5)

   Returns map with:
   - :extracted - count of rules extracted
   - :analyzed-episodes - count of episodes analyzed
   - :rules - vector of rule maps (with condition-description and action-description)
   - :skipped - true if insufficient episodes
   - :domain-type - Domain that was analyzed"
  [ctx tree-id {:keys [domain-type domain-description min-episodes]
                :or {min-episodes 5
                     domain-type "unknown"
                     domain-description "General task execution"}}]
  (let [;; Get successful episodes with rich context
        episodes (get-successful-episodes ctx tree-id {:min-confidence 0.8})
        episode-count (count episodes)]

    (if (< episode-count min-episodes)
      {:skipped true
       :reason "insufficient-episodes"
       :found episode-count
       :required min-episodes
       :domain-type domain-type}

      (let [;; Build workflow if needed
            extraction-sheet-id (build-extraction-workflow! ctx)

            ;; Execute extraction workflow with domain context
            result (sheet/execute ctx extraction-sheet-id
                                  {"tree-id" tree-id
                                   "domain-type" domain-type
                                   "domain-description" domain-description})

            rules (get-in result [:outputs "extracted-rules"] [])]

        {:extracted (count rules)
         :analyzed-episodes episode-count
         :rules rules
         :domain-type domain-type
         :tree-id tree-id}))))

(defn extract-rules-from-profiles
  "Extract rules from tree profiles projection (no direct event access needed).

   This is useful when you have the aggregated profile data but not direct
   event store access.

   Args:
   - ctx: Context for read model access
   - tree-id: Tree to analyze

   Returns rules extracted from the tree profile's strength data."
  [ctx tree-id]
  (let [profiles (rmp/project ctx :ontology/tree-profiles)
        profile (get profiles tree-id)]
    (when profile
      ;; For now, just return the strengths - full extraction requires events
      ;; Check for either new (context-conditions) or legacy (scenario-context) fields
      {:strengths (:strengths profile)
       :has-rich-context? (some #(or (contains? % :context-conditions)
                                     (contains? % :scenario-context))
                                (:strengths profile))})))
