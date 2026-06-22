**Archived (2025).** This section described features that have since shipped. See the current docs for their actual state.

For current shipping status, see [COMPONENT-MAP.md](../COMPONENT-MAP.md), [GEPA-GUIDE.md](../GEPA-GUIDE.md), [ONTOLOGY.md](../ONTOLOGY.md), [LIVING-DESCRIPTIONS.md](../LIVING-DESCRIPTIONS.md), and [SELF-IMPROVING-LOOP.md](../SELF-IMPROVING-LOOP.md).

---

## Part 1: Current State Assessment

### What's Built & Working

| Component | Status | Key Capabilities |
|-----------|--------|------------------|
| **Sheet Service (ORC)** | ✅ Production | Behavior trees, all node types, versioning, full tracing |
| **Grain Event Store** | ✅ Production | Immutable events, read models, trace storage |
| **Evaluation Component** | ✅ Working | 4 judges (grounding, instruction, reasoning, completeness), ScoreWithFeedback |
| **MCP Sheet Builder** | ✅ Complete (5 phases) | Intent analysis, pattern selection, tool relevance, REPL Researcher |
| **DSCloj** | ✅ Integrated | Clojure-native DSPy, explicit schemas, output flattening |
| **Langfuse Integration** | ✅ Working | External observability, token tracking |

### Key Infrastructure Already Available

1. **Full execution tracing** - Every node execution captured with inputs/outputs/timing
2. **Sheet versioning** - Publish versions (v1, v2, v3...), execute specific versions, A/B ready
3. **Trace extraction** - Query historical LLM node executions from event store
4. **ScoreWithFeedback** - GEPA-compatible evaluation format with actionable feedback
5. **Intent analysis** - LLM-based understanding of user goals
6. **Pattern library** - 8 agent patterns with relationship-aware generation

### What's NOT Built Yet

> **2026 audit:** Items 1–4 have shipped since this table was written. See current docs for their actual state.

1. ~~NOT built~~ **Shipped** — **GEPA optimization loop** — see [GEPA-GUIDE.md](../GEPA-GUIDE.md) and `components/gepa/`
2. ~~NOT built~~ **Shipped** — **Rolling average monitoring** — `get-node-rolling-metrics`, `get-tree-rolling-metrics` shipped in `orc-service`; auto-threshold alerts and training triggers remain forward-looking
3. ~~NOT built~~ **Shipped** — **Tree self-description** — see [LIVING-DESCRIPTIONS.md](../LIVING-DESCRIPTIONS.md) and `components/ontology/`
4. ~~NOT built~~ **Shipped** — **Tree library/ontology** — see [ONTOLOGY.md](../ONTOLOGY.md) and `components/ontology/` (tree profiles, semantic search, RRF + ColBERT hybrid retrieval)
5. **Conversational debugging** — Can't "talk to traces" — **still not shipped**
6. **Personality layer** — No customer-facing message filtering — **still not shipped**

---

## Appendix: Ontology Component Structure

When implementing Phase 4a, create the following component structure:

```
components/ontology/
├── deps.edn
└── src/ai/obney/workshop/ontology/
    ├── interface.clj                 ;; Public API
    ├── interface/schemas.clj         ;; Event schemas
    └── core/
        ├── static_ontology.clj       ;; Core failure/success concepts
        ├── read_models.clj           ;; Event → state reconstruction
        ├── serialization.clj         ;; TTL export (reuse from ontology_exploration)
        ├── classifier.clj            ;; Evaluation → failure type mapping
        ├── recorder.clj              ;; Emit ontology events
        ├── retrieval.clj             ;; Few-shot retrieval
        ├── discovery.clj             ;; LLM pattern discovery
        └── sheets.clj                ;; ORC workflows for ontology ops
```

**Reuse existing infrastructure:**
- `development/src/ontology_exploration.clj` - SKOS serialization functions
- `development/src/graph_search.clj` - BFS spreading activation, RRF scoring
- `development/src/unified_ontology.clj` - Multi-source unification patterns

---

## Appendix B: Ontology Implementation Reference

### B.1 The Anterior Pattern Applied to Trees

**Key Insights from Christopher Lovejoy's Talk:**
1. **Failure Mode Ontology** - Categorize all the ways AI can fail, not just "it failed"
2. **Domain Expert Dashboard** - Review failures and tag with failure mode
3. **Failure → Metric Correlation** - Which failure modes cause the most impact?
4. **Ready-made Datasets** - Each failure mode becomes an eval set for iteration
5. **Domain Knowledge Suggestions** - Experts add knowledge that fixes specific failure patterns

**Translation to Trees/Sheets:**

| Anterior Concept | Tree/Sheet Equivalent |
|------------------|----------------------|
| **False Approvals** (north star) | Low-scoring executions (aggregate < 0.7) |
| **Medical Record Extraction** (failure category) | Input Processing Failures |
| **Clinical Reasoning** (failure category) | LLM Reasoning Failures |
| **Rules Interpretation** (failure category) | Instruction Following Failures |
| **Conservative Therapy (ambiguity)** | Schema Ambiguity, Context Gaps |
| **Domain Knowledge Addition** | Tree/Node Instruction Refinement |

---

### B.2 Three-Layer Ontology Taxonomy

```
┌─────────────────────────────────────────────────────────────────────┐
│ LAYER 1: FAILURE ONTOLOGY                                           │
│ "Why things go wrong"                                                │
├─────────────────────────────────────────────────────────────────────┤
│ failure:Hallucination                                                │
│   ├── failure:FactHallucination                                     │
│   ├── failure:RelationshipHallucination                             │
│   └── failure:NumberHallucination                                   │
│ failure:InstructionViolation                                        │
│   ├── failure:FormatViolation                                       │
│   ├── failure:ConstraintViolation                                   │
│   └── failure:RequirementMissed                                     │
│ failure:ReasoningDefect                                             │
│   ├── failure:LogicalGap                                            │
│   ├── failure:UnjustifiedLeap                                       │
│   └── failure:CircularReasoning                                     │
│ failure:CompletenessFailure                                         │
│   ├── failure:MissingEntity                                         │
│   ├── failure:InsufficientDetail                                    │
│   └── failure:TruncatedOutput                                       │
│ failure:ContextFailure                                              │
│   ├── failure:MissingContext                                        │
│   ├── failure:IgnoredContext                                        │
│   └── failure:MisinterpretedContext                                 │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ LAYER 2: SUCCESS ONTOLOGY                                           │
│ "What makes things work"                                             │
├─────────────────────────────────────────────────────────────────────┤
│ success:PatternCategory                                             │
│   ├── success:ResearchPattern                                       │
│   │   ├── success:MultiSourceGathering                              │
│   │   ├── success:IterativeRefinement                               │
│   │   └── success:ValidationLoop                                    │
│   ├── success:AnalysisPattern                                       │
│   │   ├── success:StructuredDecomposition                           │
│   │   ├── success:ComparativeAnalysis                               │
│   │   └── success:SynthesisAggregation                              │
│   └── success:ExecutionPattern                                      │
│       ├── success:ParallelIndependent                               │
│       ├── success:SequentialPipeline                                │
│       └── success:FallbackRecovery                                  │
│                                                                      │
│ success:EffectiveTechnique                                          │
│   ├── success:ExplicitSchemaDefinition                              │
│   ├── success:FewShotExamples                                       │
│   ├── success:ChainOfThought                                        │
│   ├── success:ValidationStep                                        │
│   └── success:RetryWithFeedback                                     │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ LAYER 3: PROBLEM DOMAIN ONTOLOGY                                    │
│ "What types of problems exist"                                       │
├─────────────────────────────────────────────────────────────────────┤
│ problem:Category                                                     │
│   ├── problem:InformationRetrieval                                  │
│   │   ├── problem:DocumentSearch                                    │
│   │   ├── problem:DataExtraction                                    │
│   │   └── problem:KnowledgeQuery                                    │
│   ├── problem:ContentGeneration                                     │
│   │   ├── problem:Summarization                                     │
│   │   ├── problem:Translation                                       │
│   │   └── problem:CreativeWriting                                   │
│   ├── problem:Analysis                                              │
│   │   ├── problem:Classification                                    │
│   │   ├── problem:Scoring                                           │
│   │   └── problem:Comparison                                        │
│   └── problem:Workflow                                              │
│       ├── problem:MultiStepProcess                                  │
│       ├── problem:ConditionalBranching                              │
│       └── problem:IterativeRefinement                               │
└─────────────────────────────────────────────────────────────────────┘
```

---

### B.3 Tree-Level Ontology Profile Data Structure

Each tree/sheet gets an **ontology profile** that evolves over time:

```clojure
{:tree-id UUID
 :name "lead-qualification"
 :version 3

 ;; What this tree is good at (success ontology)
 :strengths
 [{:pattern :success/MultiSourceGathering
   :confidence 0.92
   :evidence-count 150
   :avg-score 0.88}
  {:pattern :success/StructuredDecomposition
   :confidence 0.85
   :evidence-count 120
   :avg-score 0.82}]

 ;; What problems this tree solves (problem ontology)
 :solves
 [{:problem :problem/Classification
   :subtype :problem/LeadScoring
   :success-rate 0.94
   :execution-count 500}]

 ;; What this tree struggles with (failure ontology)
 :weaknesses
 [{:failure :failure/ContextFailure
   :subtype :failure/MissingContext
   :frequency 0.08  ;; 8% of executions
   :severity :medium
   :common-triggers ["incomplete CRM data" "missing company info"]}]

 ;; Learned domain knowledge
 :domain-knowledge
 [{:id UUID
   :description "Lead score should weight recent activity 2x"
   :added-at timestamp
   :impact-score 0.15  ;; Improved accuracy by 15%
   :based-on-failures [trace-id-1 trace-id-2]}]}
```

---

### B.4 Node-Level Ontology (Learned Experience)

Individual nodes can have their own learned experience ontology:

```clojure
{:node-id UUID
 :node-type :llm  ;; or :code, :repl-researcher
 :sheet-id UUID

 ;; For search/retrieval nodes
 :search-patterns
 {:effective-queries
  [{:query-pattern "specific entity + context"
    :success-rate 0.92
    :avg-results-quality 0.85}
   {:query-pattern "broad topic exploration"
    :success-rate 0.65
    :avg-results-quality 0.60}]

  :ineffective-queries
  [{:query-pattern "vague single word"
    :failure-rate 0.80
    :common-issues [:too-broad :irrelevant-results]}]}

 ;; For LLM nodes
 :instruction-patterns
 {:effective
  [{:pattern "explicit output format + examples"
    :grounding-score 0.92
    :instruction-score 0.95}]
  :ineffective
  [{:pattern "vague open-ended instruction"
    :common-failures [:hallucination :incomplete-coverage]}]}

 ;; For code executor nodes
 :execution-patterns
 {:common-errors
  [{:error-type :null-pointer
    :trigger-conditions ["missing optional field"]
    :fix-applied "added nil check"
    :resolved true}]}}
```

---

### B.5 Ontology Event Schemas

Store ontology data as Grain events that can reconstruct to TTL:

```clojure
;; ontology/interface/schemas.clj

(defschemas events
  ;; Concept lifecycle
  {:ontology/concept-created
   [:map
    [:ontology-id :uuid]           ;; Which ontology (failure, success, problem)
    [:concept-id :uuid]
    [:uri :string]                 ;; e.g., "failure:Hallucination"
    [:label :string]
    [:description :string]
    [:broader {:optional true} :string]  ;; Parent URI (SKOS broader)
    [:properties {:optional true} [:map-of :keyword :any]]]

   :ontology/concept-relationship-created
   [:map
    [:ontology-id :uuid]
    [:relationship-id :uuid]
    [:source-uri :string]
    [:target-uri :string]
    [:predicate :string]           ;; "skos:broader", "skos:related", "owl:causes"
    [:properties {:optional true} [:map-of :keyword :any]]]

   ;; Tree-level ontology profiles
   :ontology/tree-strength-recorded
   [:map
    [:tree-id :uuid]
    [:pattern-uri :string]         ;; e.g., "success:MultiSourceGathering"
    [:confidence :double]
    [:evidence-trace-ids [:vector :uuid]]
    [:avg-score :double]
    [:recorded-at :string]]

   :ontology/tree-weakness-recorded
   [:map
    [:tree-id :uuid]
    [:failure-uri :string]         ;; e.g., "failure:Hallucination"
    [:subtype-uri {:optional true} :string]
    [:frequency :double]
    [:severity [:enum :low :medium :high :critical]]
    [:triggers [:vector :string]]
    [:evidence-trace-ids [:vector :uuid]]
    [:recorded-at :string]]

   :ontology/tree-problem-mapping-created
   [:map
    [:tree-id :uuid]
    [:problem-uri :string]         ;; e.g., "problem:Classification"
    [:success-rate :double]
    [:execution-count :int]
    [:recorded-at :string]]

   ;; Node-level learned experience
   :ontology/node-pattern-learned
   [:map
    [:node-id :uuid]
    [:sheet-id :uuid]
    [:pattern-type [:enum :search :instruction :execution]]
    [:effective? :boolean]
    [:pattern-description :string]
    [:metrics [:map
               [:success-rate {:optional true} :double]
               [:avg-score {:optional true} :double]
               [:failure-rate {:optional true} :double]]]
    [:evidence-trace-ids [:vector :uuid]]
    [:learned-at :string]]

   :ontology/domain-knowledge-added
   [:map
    [:knowledge-id :uuid]
    [:tree-id :uuid]
    [:node-id {:optional true} :uuid]
    [:description :string]
    [:based-on-failure-traces [:vector :uuid]]
    [:impact-score {:optional true} :double]
    [:added-at :string]]})
```

---

### B.6 Read Models for Ontology Reconstruction

```clojure
;; ontology/core/read_models.clj

(def ontology-events
  #{:ontology/concept-created
    :ontology/concept-relationship-created
    :ontology/tree-strength-recorded
    :ontology/tree-weakness-recorded
    :ontology/tree-problem-mapping-created
    :ontology/node-pattern-learned
    :ontology/domain-knowledge-added})

;; Build concept graph
(defmulti concepts* (fn [_state event] (:event/type event)))

(defmethod concepts* :ontology/concept-created [state event]
  (assoc state (:uri event)
         {:uri (:uri event)
          :id (:concept-id event)
          :ontology-id (:ontology-id event)
          :label (:label event)
          :description (:description event)
          :broader (:broader event)
          :properties (:properties event)
          :narrower #{}  ;; Will be populated by inverse
          :related #{}}))

(defmethod concepts* :ontology/concept-relationship-created [state event]
  (let [{:keys [source-uri target-uri predicate]} event]
    (case predicate
      "skos:broader" (-> state
                         (update-in [source-uri :broader] (fnil conj #{}) target-uri)
                         (update-in [target-uri :narrower] (fnil conj #{}) source-uri))
      "skos:related" (-> state
                         (update-in [source-uri :related] (fnil conj #{}) target-uri)
                         (update-in [target-uri :related] (fnil conj #{}) source-uri))
      state)))

;; Build tree profiles
(defmulti tree-profiles* (fn [_state event] (:event/type event)))

(defmethod tree-profiles* :ontology/tree-strength-recorded [state event]
  (update-in state [(:tree-id event) :strengths]
             (fnil conj [])
             {:pattern (:pattern-uri event)
              :confidence (:confidence event)
              :evidence-count (count (:evidence-trace-ids event))
              :avg-score (:avg-score event)}))

(defmethod tree-profiles* :ontology/tree-weakness-recorded [state event]
  (update-in state [(:tree-id event) :weaknesses]
             (fnil conj [])
             {:failure (:failure-uri event)
              :subtype (:subtype-uri event)
              :frequency (:frequency event)
              :severity (:severity event)
              :triggers (:triggers event)}))

;; Build node experiences
(defmulti node-experiences* (fn [_state event] (:event/type event)))

(defmethod node-experiences* :ontology/node-pattern-learned [state event]
  (let [category (if (:effective? event) :effective :ineffective)]
    (update-in state [(:node-id event) (:pattern-type event) category]
               (fnil conj [])
               {:pattern (:pattern-description event)
                :metrics (:metrics event)
                :evidence-count (count (:evidence-trace-ids event))})))
```

---

### B.7 TTL Serialization from Read Model

```clojure
;; ontology/core/serialization.clj

(defn concepts->turtle
  "Reconstruct TTL from concept read model state"
  [concepts-state {:keys [base-uri ontology-type]}]
  (let [prefixes (str "@prefix skos: <http://www.w3.org/2004/02/skos/core#> .\n"
                      "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                      "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                      "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n"
                      "@prefix : <" base-uri "> .\n\n")]
    (str prefixes
         (str/join "\n\n"
           (for [[uri concept] concepts-state]
             (concept->turtle concept))))))

(defn concept->turtle [{:keys [uri label description broader narrower related properties]}]
  (str uri " a skos:Concept ;\n"
       "  skos:prefLabel \"" label "\"@en ;\n"
       "  skos:definition \"" description "\"@en"
       (when (seq broader)
         (str " ;\n  skos:broader " (str/join ", " broader)))
       (when (seq narrower)
         (str " ;\n  skos:narrower " (str/join ", " narrower)))
       (when (seq related)
         (str " ;\n  skos:related " (str/join ", " related)))
       " .\n"))

(defn tree-profile->turtle
  "Serialize tree ontology profile to extended TTL"
  [tree-profile {:keys [base-uri]}]
  (let [{:keys [tree-id name strengths weaknesses solves]} tree-profile]
    (str ":tree-" tree-id " a :BehaviorTree ;\n"
         "  rdfs:label \"" name "\" ;\n"
         ;; Strengths
         (when (seq strengths)
           (str "  :hasStrength "
                (str/join ", " (map #(str "["
                                          ":pattern " (:pattern %) " ; "
                                          ":confidence " (:confidence %) "^^xsd:double"
                                          "]") strengths))
                " ;\n"))
         ;; Weaknesses
         (when (seq weaknesses)
           (str "  :hasWeakness "
                (str/join ", " (map #(str "["
                                          ":failureType " (:failure %) " ; "
                                          ":frequency " (:frequency %) "^^xsd:double ; "
                                          ":severity \"" (name (:severity %)) "\""
                                          "]") weaknesses))
                " ;\n"))
         " .\n")))
```

---

### B.8 Tree Builder Integration

#### Few-Shot Example Retrieval

```clojure
;; tree-library/core/retrieval.clj

(defn find-similar-successful-trees
  "Find trees that successfully solved similar problems"
  [event-store {:keys [problem-type required-patterns min-success-rate limit]}]
  (let [tree-profiles (tree-profiles {}
                        (event-store/read event-store {:types tree-profile-events}))

        ;; Filter by problem type match
        matching-trees (filter
                        (fn [[_id profile]]
                          (some #(= (:problem-uri %) problem-type)
                                (:solves profile)))
                        tree-profiles)

        ;; Filter by success rate
        successful-trees (filter
                          (fn [[_id profile]]
                            (let [problem-entry (first (filter #(= (:problem-uri %) problem-type)
                                                               (:solves profile)))]
                              (>= (:success-rate problem-entry) min-success-rate)))
                          matching-trees)

        ;; Sort by combined score (success rate + pattern match)
        scored (map (fn [[id profile]]
                      (let [pattern-match (count (filter #(contains? required-patterns (:pattern %))
                                                         (:strengths profile)))
                            success-entry (first (filter #(= (:problem-uri %) problem-type)
                                                         (:solves profile)))]
                        {:tree-id id
                         :profile profile
                         :score (+ (:success-rate success-entry)
                                   (* 0.1 pattern-match))}))
                    successful-trees)]

    (->> scored
         (sort-by :score >)
         (take limit))))

(defn find-common-failure-patterns
  "Find failure patterns to avoid for a problem type"
  [event-store {:keys [problem-type min-frequency]}]
  (let [tree-profiles (tree-profiles {}
                        (event-store/read event-store {:types tree-profile-events}))

        ;; Get trees that attempted this problem type
        relevant-trees (filter
                        (fn [[_id profile]]
                          (some #(= (:problem-uri %) problem-type)
                                (:solves profile)))
                        tree-profiles)

        ;; Aggregate failure patterns
        all-weaknesses (mapcat (fn [[_id profile]] (:weaknesses profile))
                               relevant-trees)

        ;; Group by failure type and calculate aggregate frequency
        failure-stats (reduce
                       (fn [acc weakness]
                         (update acc (:failure weakness)
                                 (fnil (fn [stats]
                                         (-> stats
                                             (update :count inc)
                                             (update :total-frequency + (:frequency weakness))
                                             (update :triggers into (:triggers weakness))))
                                       {:count 0 :total-frequency 0 :triggers #{}})))
                       {}
                       all-weaknesses)]

    (->> failure-stats
         (map (fn [[failure-uri stats]]
                {:failure failure-uri
                 :avg-frequency (/ (:total-frequency stats) (:count stats))
                 :tree-count (:count stats)
                 :common-triggers (take 5 (frequencies (:triggers stats)))}))
         (filter #(>= (:avg-frequency %) min-frequency))
         (sort-by :avg-frequency >))))
```

#### Tree Builder Prompt Enhancement

```clojure
;; mcp-sheet-builder/core/ontology_context.clj

(defn build-ontology-context
  "Generate few-shot context from ontology for tree builder"
  [event-store {:keys [problem-type patterns]}]
  (let [successful-trees (find-similar-successful-trees event-store
                           {:problem-type problem-type
                            :required-patterns patterns
                            :min-success-rate 0.8
                            :limit 3})

        failure-patterns (find-common-failure-patterns event-store
                           {:problem-type problem-type
                            :min-frequency 0.1})]

    {:few-shot-examples
     (for [{:keys [tree-id profile]} successful-trees]
       {:name (:name profile)
        :structure (get-tree-structure event-store tree-id)
        :strengths (map :pattern (:strengths profile))
        :success-rate (-> profile :solves first :success-rate)
        :why-it-works (summarize-success-factors profile)})

     :patterns-to-avoid
     (for [{:keys [failure avg-frequency common-triggers]} failure-patterns]
       {:failure-type failure
        :how-often (str (int (* 100 avg-frequency)) "% of trees")
        :triggered-by common-triggers
        :how-to-avoid (get-avoidance-strategy failure)})}))

(defn enhance-tree-builder-instruction
  "Add ontology context to tree builder prompt"
  [base-instruction ontology-context]
  (str base-instruction
       "\n\n## Successful Examples\n"
       (str/join "\n"
         (for [example (:few-shot-examples ontology-context)]
           (str "- **" (:name example) "** (success rate: " (:success-rate example) ")\n"
                "  Strengths: " (str/join ", " (:strengths example)) "\n"
                "  " (:why-it-works example))))
       "\n\n## Patterns to Avoid\n"
       (str/join "\n"
         (for [pattern (:patterns-to-avoid ontology-context)]
           (str "- **" (:failure-type pattern) "** (" (:how-often pattern) " occurrence)\n"
                "  Triggered by: " (str/join ", " (:triggered-by pattern)) "\n"
                "  Avoid by: " (:how-to-avoid pattern))))))
```

---

### B.9 Integration Pipelines

#### Evaluation → Ontology Pipeline

```
Execution Trace
      │
      ▼
┌─────────────────┐
│ Evaluation      │ (4 judges: grounding, instruction, reasoning, completeness)
│ Component       │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Failure         │ Map evaluation results to ontology concepts
│ Classifier      │ e.g., ungrounded_claims → failure:Hallucination
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Ontology        │ Emit events: tree-weakness-recorded, node-pattern-learned
│ Recorder        │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Read Models     │ Aggregate into tree profiles, node experiences
│ (Grain)         │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ TTL Export      │ Reconstruct SKOS/OWL on demand
│ (On-demand)     │
└─────────────────┘
```

#### Tree Builder → Ontology Pipeline

```
User Request: "Build a lead qualification tree"
      │
      ▼
┌─────────────────┐
│ Intent          │ Classify: problem:Classification, problem:Scoring
│ Analyzer        │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Ontology        │ Query: find-similar-successful-trees
│ Retrieval       │        find-common-failure-patterns
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Context         │ Inject few-shot examples + patterns-to-avoid
│ Enhancer        │ into tree builder prompt
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Tree            │ Generate tree with ontology-informed context
│ Generator       │
└─────────────────┘
```

---

### B.10 Static Core Ontology Definitions

```clojure
;; ontology/core/static_ontology.clj

(def FAILURE_ONTOLOGY_CORE
  "Static core of failure ontology - LLM discovers subtypes"
  {:concepts
   [{:uri "failure:Root"
     :label "Failure"
     :description "Root concept for all failure types"}

    ;; Level 1: Main categories (from 4 judges)
    {:uri "failure:Grounding"
     :label "Grounding Failure"
     :broader "failure:Root"
     :description "Output not supported by input context"}
    {:uri "failure:InstructionFollowing"
     :label "Instruction Following Failure"
     :broader "failure:Root"
     :description "Did not follow given instruction"}
    {:uri "failure:Reasoning"
     :label "Reasoning Failure"
     :broader "failure:Root"
     :description "Logical or reasoning defects"}
    {:uri "failure:Completeness"
     :label "Completeness Failure"
     :broader "failure:Root"
     :description "Missing required content or aspects"}

    ;; Level 2: Known subtypes (static)
    {:uri "failure:Hallucination"
     :label "Hallucination"
     :broader "failure:Grounding"
     :description "Generated claims not in sources"
     :indicators ["claim not found" "made up" "invented"]}
    {:uri "failure:Contradiction"
     :label "Contradiction"
     :broader "failure:Grounding"
     :description "Output contradicts input"}
    {:uri "failure:FormatViolation"
     :label "Format Violation"
     :broader "failure:InstructionFollowing"
     :description "Wrong output format"}
    {:uri "failure:LogicalGap"
     :label "Logical Gap"
     :broader "failure:Reasoning"
     :description "Missing reasoning step"}
    {:uri "failure:MissingEntity"
     :label "Missing Entity"
     :broader "failure:Completeness"
     :description "Required entity not included"}]

   :relationships
   [{:source "failure:Hallucination" :target "failure:Contradiction" :predicate "skos:related"}]})

(def SUCCESS_ONTOLOGY_CORE
  "Static core of success patterns"
  {:concepts
   [{:uri "success:Root"
     :label "Success Pattern"
     :description "Root concept for success patterns"}

    ;; Pattern categories
    {:uri "success:StructuralPattern"
     :label "Structural Pattern"
     :broader "success:Root"
     :description "Effective tree structure patterns"}
    {:uri "success:InstructionPattern"
     :label "Instruction Pattern"
     :broader "success:Root"
     :description "Effective instruction patterns"}
    {:uri "success:DataFlowPattern"
     :label "Data Flow Pattern"
     :broader "success:Root"
     :description "Effective data flow patterns"}

    ;; Specific patterns
    {:uri "success:ExplicitSchema"
     :label "Explicit Schema Definition"
     :broader "success:InstructionPattern"
     :description "Using explicit output schemas with descriptions"}
    {:uri "success:ValidationLoop"
     :label "Validation Loop"
     :broader "success:StructuralPattern"
     :description "Including validation step after generation"}
    {:uri "success:MultiSourceGathering"
     :label "Multi-Source Gathering"
     :broader "success:DataFlowPattern"
     :description "Gathering from multiple sources in parallel"}]})
```

---

### B.11 Node Type Learning Patterns

```clojure
;; Node type learning aggregates across all nodes of same type

(def NODE_TYPE_LEARNING_CONFIG
  {:llm
   {:learn-from [:instruction-patterns :output-quality :failure-types]
    :aggregate-across :all-llm-nodes
    :min-samples 10}

   :repl-researcher
   {:learn-from [:query-patterns :iteration-counts :tool-selection]
    :aggregate-across :all-researcher-nodes
    :min-samples 5}

   :code
   {:learn-from [:error-types :input-validation :execution-time]
    :aggregate-across :all-code-nodes
    :min-samples 20}

   :map-each
   {:learn-from [:parallelism-effectiveness :batch-size-impact]
    :aggregate-across :all-map-each-nodes
    :min-samples 15}})

;; Query: "What have all :llm nodes learned?"
(defn get-node-type-learnings
  [event-store node-type]
  (let [events (event-store/read event-store
                 {:types #{:ontology/node-pattern-learned}
                  :tags #{[:node-type node-type]}})

        learnings (node-experiences {} events)]

    {:effective-patterns (aggregate-effective-patterns learnings)
     :ineffective-patterns (aggregate-ineffective-patterns learnings)
     :sample-count (count events)}))
```

---

### B.12 ORC Sheets for Ontology Operations

```clojure
;; ontology/core/sheets.clj

(def failure-classification-sheet
  "Classify evaluation results into failure ontology"
  (sheet/workflow "failure-classifier"
    (sheet/blackboard
      {:evaluation-result [:map
                           [:score :double]
                           [:dimensions [:vector [:map
                                                  [:name :string]
                                                  [:score :double]
                                                  [:feedback :string]]]]]
       :failure-types [:vector [:map
                                [:uri :string]
                                [:confidence :double]
                                [:evidence :string]]]})

    (sheet/sequence "classify"
      ;; Use existing failure ontology as context
      (sheet/code "load-ontology"
        :fn "ontology/get-failure-concepts"
        :reads []
        :writes ["failure-ontology"])

      ;; LLM classifies into ontology
      (sheet/llm "classify-failures"
        :model "google/gemini-2.5-flash"
        :instruction "Given the evaluation result and failure ontology,
                     classify the failures into ontology concepts.
                     Return URI, confidence (0-1), and evidence quote."
        :reads ["evaluation-result" "failure-ontology"]
        :writes ["failure-types"]))))

(def pattern-discovery-sheet
  "Discover new failure subtypes from traces"
  (sheet/workflow "pattern-discovery"
    (sheet/blackboard
      {:failure-traces [:vector TraceSchema]
       :existing-ontology :string  ;; TTL of current failure ontology
       :discovered-concepts [:vector [:map
                                       [:proposed-uri :string]
                                       [:label :string]
                                       [:description :string]
                                       [:broader :string]
                                       [:evidence-count :int]]]})

    (sheet/sequence "discover"
      (sheet/llm "analyze-patterns"
        :model "anthropic/claude-sonnet-4"
        :instruction "Analyze these failure traces and propose new
                     failure subtypes not in the existing ontology.
                     Each must have a broader parent in existing ontology."
        :reads ["failure-traces" "existing-ontology"]
        :writes ["discovered-concepts"]))))
```

---

### B.13 Graph Search for Few-Shot Retrieval

```clojure
;; Reuse existing graph_search.clj patterns

(defn find-related-trees-via-graph
  "Use spreading activation to find related successful trees"
  [ontology-graph seed-problem-uri {:keys [max-depth decay]}]
  (let [;; BFS from problem type to find related patterns
        activated (bfs-spreading-activation
                    ontology-graph
                    #{seed-problem-uri}
                    {:max-depth (or max-depth 3)
                     :decay (or decay 0.5)
                     :min-activation 0.01})

        ;; Find trees that have strengths matching activated patterns
        matching-trees (filter-trees-by-patterns activated)]

    ;; Apply temporal scoring (recent successes weighted higher)
    (apply-temporal-scoring matching-trees)))
```

---

### B.14 Ontology Implementation Phases

| Phase | Focus | Duration | Key Deliverables |
|-------|-------|----------|------------------|
| **1** | Static Ontology Foundation | Week 1 | `components/ontology/`, static schemas, basic read models, TTL export |
| **2** | Evaluation → Ontology Pipeline | Week 2 | Failure classifier, `record-tree-weakness!`, tree profile read model |
| **3** | Tree Builder Integration | Week 3 | Few-shot retrieval, context enhancer, MCP builder integration |
| **4** | Node Type Learning | Week 4 | Node-type patterns for :llm, :search, :code, pattern aggregation |
| **5** | LLM Discovery + Graph Search | Week 5 | Pattern discovery sheet, RRF + temporal scoring, ontology extension |

---

### B.15 Verification Plan

```clojure
;; development/src/ontology_demo.clj

;; 1. Initialize static ontology
(ontology/initialize-static-ontology! ctx)

;; 2. Evaluate some traces
(def results (eval/evaluate-traces traces))

;; 3. Classify failures into ontology
(def classified (ontology/classify-failures results))
;; => [{:trace-id ... :failures [{:uri "failure:Hallucination" :confidence 0.9}]}]

;; 4. Record to tree profile
(ontology/record-weaknesses! ctx sheet-id classified)

;; 5. Query tree profile
(def profile (ontology/get-tree-profile ctx sheet-id))
(assert (seq (:weaknesses profile)))

;; 6. Export to TTL
(def ttl (ontology/export-ttl ctx :failure))
(assert (str/includes? ttl "skos:Concept"))

;; 7. Build tree with ontology context
(def context (ontology/build-context ctx {:problem-type "problem:Classification"}))
(def tree (msb/build-sheet-from-mcp! ctx mcp-opts {:ontology-context context}))

;; 8. Verify few-shot was used
(assert (str/includes? (:instruction tree) "Successful Examples"))
```

---

### B.16 Files to Create/Modify

| File | Action | Purpose |
|------|--------|---------|
| `components/ontology/` | **Create** | New Polylith component |
| `ontology/interface.clj` | Create | Public API |
| `ontology/interface/schemas.clj` | Create | Event schemas |
| `ontology/core/static_ontology.clj` | Create | Core failure/success concepts |
| `ontology/core/read_models.clj` | Create | Event → state reconstruction |
| `ontology/core/serialization.clj` | Create | TTL export |
| `ontology/core/classifier.clj` | Create | Evaluation → failure type mapping |
| `ontology/core/recorder.clj` | Create | Emit ontology events |
| `ontology/core/retrieval.clj` | Create | Few-shot retrieval |
| `ontology/core/discovery.clj` | Create | LLM pattern discovery |
| `ontology/core/sheets.clj` | Create | ORC workflows for ontology ops |
| `development/src/ontology_exploration.clj` | Modify | Extract reusable serialization functions |
| `development/src/graph_search.clj` | Modify | Extract reusable graph traversal functions |
| `components/evaluation/interface.clj` | Modify | Add `classify-failures` function |
| `components/mcp-sheet-builder/interface.clj` | Modify | Add `ontology-context` option |
| `components/sheet-service/interface/schemas.clj` | Modify | Add ontology event types |


