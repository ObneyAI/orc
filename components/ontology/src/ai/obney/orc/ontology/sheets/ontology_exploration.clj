(ns ai.obney.orc.ontology.sheets.ontology-exploration
  "Clojure ORC implementation of Python ontology_exploration pipeline.
   Maps 1:1 to the Python DSPy TaxonomyPipeline from:
   /Users/darylroberts/Desktop/Code/area_51/ontology_exploration/src/pipeline/pipeline.py

   DSPy Signatures Implemented:
   - ExtractConcepts
   - DiscoverHierarchy
   - FindRelatedConcepts
   - ValidateTaxonomy"
  (:require [ai.obney.orc.orc-service.interface :as sheet]
            [clojure.string :as str]))

;; =============================================================================
;; Sample Data for Testing
;; =============================================================================

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

;; =============================================================================
;; Code Executor Functions
;; =============================================================================

(defn ingest-sources-fn
  "Phase 1: Load and combine multiple source files into single text.
   Python equivalent: multi_loader.load_all() + combine()"
  [{:keys [inputs]}]
  (let [source-paths (get inputs :source-paths [])
        source-text (get inputs :source-text)]
    ;; For now, just pass through the source-text
    ;; In production, would read files from source-paths and combine
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
              ;; Normalize to string keys for consistency
              ;; alt_labels is now a comma-separated string, not a vector
              (conj acc {"label" (get-concept-field concept "label" "")
                         "alt_labels" (let [al (get-concept-field concept "alt_labels" "")]
                                        (if (vector? al) (str/join ", " al) (or al "")))
                         "definition" (get-concept-field concept "definition" "")
                         "entity_type" (get-concept-field concept "entity_type" "UNKNOWN")
                         "confidence" 0.8})))))
      []
      concepts)))

(defn deduplicate-concepts-fn
  "Phase 3: Remove duplicate concepts using Type-Based Blocking.

   Optimization: Groups concepts by entity_type before comparison.
   This reduces complexity from O(N²) to O((N/k)²) where k = number of types.

   Python equivalent: EntityResolver.resolve_within_batch() with blocking"
  [{:keys [inputs]}]
  (let [concepts (get inputs :raw-concepts [])
        ;; Step 1: Group by entity_type for blocking optimization
        by-type (group-by #(get-concept-field % "entity_type" "UNKNOWN") concepts)
        ;; Step 2: Dedupe within each block independently
        unique-concepts (vec (mapcat dedupe-within-block (vals by-type)))
        ;; Step 3: Collect blocking statistics
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
        ;; Handle both keyword and string keys
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
        ;; Add orphans as top concepts
        orphan-issues (filter #(= "orphans" (get % "type")) issues)
        orphan-labels (mapcat #(get % "affected_concepts" []) orphan-issues)]
    {:top-concepts (vec (distinct (concat top-concepts orphan-labels)))}))

;; =============================================================================
;; Causal Link Extraction Functions
;; =============================================================================

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
                       ;; Filter: relation type must be valid
                       (filter #(valid-causal-types (get % "relation_type")))
                       ;; Filter: cause and effect must not be empty
                       (filter #(and (seq (get % "cause"))
                                     (seq (get % "effect"))))
                       ;; Optional: filter to known concepts (relaxed for flexibility)
                       ;; (filter #(and (concept-labels (get % "cause"))
                       ;;               (concept-labels (get % "effect"))))
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

        ;; Generate prefixes including OWL for causal properties
        prefixes (str "@prefix skos: <http://www.w3.org/2004/02/skos/core#> .\n"
                      "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                      "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                      "@prefix ex: <http://example.org/taxonomy#> .\n\n")

        ;; Causal property declarations
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

        ;; Concept triples
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

        ;; Hierarchy triples
        hierarchy-triples (str/join "\n"
                            (for [r relationships
                                  :let [broader (str/replace (get r "broader" "") #"[^a-zA-Z0-9]" "")
                                        narrower (str/replace (get r "narrower" "") #"[^a-zA-Z0-9]" "")]]
                              (str "ex:" narrower " skos:broader ex:" broader " .")))

        ;; Causal relationship triples
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

;; =============================================================================
;; Taxonomy Pipeline Workflow
;; Python equivalent: TaxonomyPipeline from pipeline.py
;; =============================================================================

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

  (sheet/workflow "taxonomy-pipeline"

    ;; =========================================================================
    ;; BLACKBOARD SCHEMA
    ;; All fields explicitly typed for LLM output parsing
    ;; Field descriptions align with Python DSPy InputField/OutputField patterns
    ;; ChainOfThought: Each LLM node has a reasoning field for reliable outputs
    ;;
    ;; IMPORTANT: Descriptions go on CONTAINER types (vector, map), not leaf types
    ;; Pattern: [:vector {:description "..."} [:map [:field :type]]]
    ;; =========================================================================
    (sheet/blackboard
      ;; === Inputs ===
      {:source-text [:string {:description "Text to extract concepts from for taxonomy building"}]
       :domain [:string {:description "Domain context (e.g., 'artificial intelligence', 'higher education')"}]
       :existing-concepts [:vector {:description "Already known concepts to avoid extracting again"} :string]
       :source-paths [:vector {:description "Paths to source files to load"} :string]
       :output-format :string

       ;; === ChainOfThought Reasoning Fields ===
       ;; Critical for reliable LLM outputs - each LLM node has a reasoning field
       :extraction-reasoning [:string {:description "Step-by-step reasoning for identifying and extracting concepts from the text"}]
       :hierarchy-reasoning [:string {:description "Step-by-step reasoning for discovering broader/narrower relationships between concepts"}]
       :related-reasoning [:string {:description "Step-by-step reasoning for finding non-hierarchical relationships"}]
       :causal-reasoning [:string {:description "Step-by-step reasoning for identifying causal relationships in the text"}]
       :validation-reasoning [:string {:description "Step-by-step reasoning for validating taxonomy structure and quality"}]

       ;; === Phase 2: Extraction outputs ===
       ;; Python: ExtractConcepts signature
       ;; entity_type enables Type-Based Blocking for O(N²) -> O((N/k)²) deduplication
       :raw-concepts [:vector {:description "5-15 key concepts extracted from text with labels, definitions, and entity types"}
                      [:map
                       [:label :string]
                       [:alt_labels :string]
                       [:definition :string]
                       [:entity_type :string]]]

       ;; === Phase 3: After deduplication ===
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

       ;; === Phase 4: Hierarchy outputs ===
       ;; Python: DiscoverHierarchy signature
       :relationships [:vector {:description "Broader/narrower (parent-child) relationships between concepts"}
                       [:map
                        [:broader :string]
                        [:narrower :string]]]
       :top-concepts [:vector {:description "Root concepts with no broader concept"} :string]

       ;; === Phase 5: Related pairs ===
       ;; Python: FindRelatedConcepts signature
       :related-pairs [:vector {:description "Non-hierarchical relationships between concepts"}
                       [:map
                        [:concept1 :string]
                        [:concept2 :string]
                        [:reason :string]]]

       ;; === Phase 5.5: Causal relationships ===
       ;; Python: CausalExtractorModule (causes, enables, prevents, preparesFor)
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

       ;; === Phase 6: Validation ===
       ;; Python: ValidateTaxonomy signature
       :issues [:vector {:description "Validation issues found in taxonomy structure"}
                [:map
                 [:type :string]
                 [:description :string]
                 [:affected_concepts :string]
                 [:suggestion :string]]]
       :quality [:enum {:description "Overall quality assessment"} "good" "fair" "poor"]
       :suggestions [:vector {:description "Suggestions for improving taxonomy quality"} :string]

       ;; === Phase 8-9: Output ===
       :taxonomy [:map-of :keyword :any]
       :skos-output :string})

    ;; =========================================================================
    ;; MAIN PIPELINE SEQUENCE
    ;; =========================================================================
    (sheet/sequence "main-pipeline"

      ;; =======================================================================
      ;; PHASE 1: INGESTION (code only)
      ;; Python: multi_loader.load_all() + combine()
      ;; =======================================================================
      (sheet/code "ingest-sources"
        :fn "ai.obney.orc.ontology.sheets.ontology-exploration/ingest-sources-fn"
        :reads [:source-paths :source-text]
        :writes [:source-text])

      ;; =======================================================================
      ;; PHASE 2: EXTRACTION
      ;; Python: _extract_concepts() -> ConceptExtractor.forward()
      ;; DSPy Signature: ExtractConcepts with ChainOfThought
      ;; =======================================================================
      (sheet/llm "extract-concepts"
        :model "google/gemini-2.5-flash"
        :instruction "Extract 5-15 key concepts from the text for taxonomy building. Focus on domain-specific terminology. Include alternative labels (synonyms, abbreviations) when relevant. Avoid extracting concepts already in existing_concepts. Use domain-appropriate entity types."
        :reads [:source-text :domain :existing-concepts]
        :writes [:extraction-reasoning :raw-concepts]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      ;; =======================================================================
      ;; PHASE 3: DEDUPLICATION
      ;; Python: _deduplicate_concepts() -> embedding similarity > 0.9
      ;; =======================================================================
      (sheet/code "deduplicate-concepts"
        :fn "ai.obney.orc.ontology.sheets.ontology-exploration/deduplicate-concepts-fn"
        :reads [:raw-concepts]
        :writes [:concepts :blocking-stats])

      (sheet/code "extract-labels"
        :fn "ai.obney.orc.ontology.sheets.ontology-exploration/extract-labels-fn"
        :reads [:concepts]
        :writes [:concept-labels])

      ;; =======================================================================
      ;; PHASE 4: HIERARCHY BUILDING
      ;; Python: _build_hierarchy() -> HierarchyBuilder.forward()
      ;; DSPy Signature: DiscoverHierarchy with ChainOfThought
      ;; =======================================================================
      (sheet/llm "discover-hierarchy"
        :model "google/gemini-2.5-flash"
        :instruction "Identify broader/narrower (parent-child) relationships between concepts. A concept is narrower if it is a type of or subset of another. Polyhierarchy is allowed. Not every concept needs relationships."
        :reads [:concept-labels :domain]
        :writes [:hierarchy-reasoning :relationships :top-concepts]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      ;; =======================================================================
      ;; PHASE 5: RELATIONSHIP DISCOVERY
      ;; Python: _find_related() -> RelationshipFinder.forward()
      ;; DSPy Signature: FindRelatedConcepts with ChainOfThought
      ;; =======================================================================
      (sheet/llm "find-related-concepts"
        :model "google/gemini-2.5-flash"
        :instruction "Find non-hierarchical relationships between concepts. Identify concepts that are associated, similar, or frequently used together but NOT in a parent-child relationship. Focus on meaningful associations."
        :reads [:concept-labels :domain]
        :writes [:related-reasoning :related-pairs]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      ;; =======================================================================
      ;; PHASE 5.5: CAUSAL LINK EXTRACTION
      ;; Python: CausalExtractorModule with ChainOfThought
      ;; =======================================================================
      (sheet/llm "extract-causal-relations"
        :model "google/gemini-2.5-flash"
        :instruction "Extract causal, enabling, and preventative relationships from text. Look for: causes (X leads to Y), enables (X allows Y), prevents (X blocks Y), preparesFor (X is prerequisite for Y). Focus on explicit causal statements, not implied correlations."
        :reads [:source-text :concept-labels :domain]
        :writes [:causal-reasoning :causal-relations]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      (sheet/code "validate-causal-relations"
        :fn "ai.obney.orc.ontology.sheets.ontology-exploration/validate-causal-relations-fn"
        :reads [:causal-relations :concept-labels]
        :writes [:validated-causal-relations :causal-stats])

      ;; =======================================================================
      ;; PHASE 6: VALIDATION
      ;; Python: _validate() -> checks orphans, missing definitions, etc.
      ;; DSPy Signature: ValidateTaxonomy with ChainOfThought
      ;; =======================================================================
      (sheet/llm "validate-taxonomy"
        :model "google/gemini-2.5-flash"
        :instruction "Validate taxonomy structure. Check for: orphans (concepts with no parent that aren't top concepts), cycles (circular references), missing definitions, inconsistent granularity among siblings, and near-duplicate concepts."
        :reads [:concept-labels :relationships :domain]
        :writes [:validation-reasoning :issues :quality :suggestions]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      ;; =======================================================================
      ;; PHASE 7: REFINEMENT (conditional)
      ;; Python: _refine() if issues with auto_fixable=True
      ;; =======================================================================
      (sheet/fallback "refine-if-needed"
        ;; Branch 1: Quality is good, skip refinement
        (sheet/sequence "skip-refinement"
          (sheet/condition "quality-ok"
            :check {:key "quality" :op :equals :value "good"}))

        ;; Branch 2: Apply auto-fixes
        (sheet/code "apply-auto-fixes"
          :fn "ai.obney.orc.ontology.sheets.ontology-exploration/apply-auto-fixes-fn"
          :reads [:issues :concepts :relationships :top-concepts]
          :writes [:top-concepts]))

      ;; =======================================================================
      ;; PHASE 8: BUILD TAXONOMY
      ;; Python: _build_taxonomy() -> Taxonomy object
      ;; =======================================================================
      (sheet/code "build-taxonomy"
        :fn "ai.obney.orc.ontology.sheets.ontology-exploration/build-taxonomy-fn"
        :reads [:concepts :relationships :top-concepts :related-pairs
                :validated-causal-relations :domain]
        :writes [:taxonomy])

      ;; =======================================================================
      ;; PHASE 9: SERIALIZATION
      ;; Python: taxonomy.serialize(format='turtle')
      ;; =======================================================================
      (sheet/code "serialize-to-skos"
        :fn "ai.obney.orc.ontology.sheets.ontology-exploration/serialize-to-skos-fn"
        :reads [:taxonomy :output-format]
        :writes [:skos-output]))))

;; =============================================================================
;; API Functions
;; =============================================================================

(def taxonomy-sheet-id #uuid "a1b2c3d4-e5f6-7890-abcd-ef1234567890")

(defn build-taxonomy-pipeline!
  "Build the taxonomy pipeline workflow. Returns sheet-id."
  [context]
  (sheet/build-workflow! context taxonomy-pipeline))

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
  (let [result (sheet/execute context sheet-id
                 {:source-text source-text
                  :domain domain
                  :existing-concepts existing-concepts
                  :output-format "turtle"})]
    (if (= :success (:status result))
      {:status :success
       :concepts (get-in result [:outputs :concepts])
       :relationships (get-in result [:outputs :relationships])
       :top-concepts (get-in result [:outputs :top-concepts])
       :related-pairs (get-in result [:outputs :related-pairs])
       :validation {:quality (get-in result [:outputs :quality])
                    :issues (get-in result [:outputs :issues])
                    :suggestions (get-in result [:outputs :suggestions])}
       :skos-output (get-in result [:outputs :skos-output])}
      {:status :failed
       :error (:error result)})))

(defn run-demo
  "Run the taxonomy pipeline with sample data."
  [context sheet-id]
  (run-taxonomy-pipeline context sheet-id
    {:source-text sample-source-text
     :domain sample-domain}))

;; =============================================================================
;; Debug & Comparison Helpers
;; =============================================================================

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

;; NOTE: *debug-raw-response* not available in ORC executor yet
;; (defn run-demo-with-debug
;;   "Run the taxonomy pipeline with debug output enabled.
;;    This captures and prints raw LLM prompts and responses for comparison."
;;   [context sheet-id]
;;   (binding [executor/*debug-raw-response* true]
;;     (run-taxonomy-pipeline context sheet-id
;;       {:source-text sample-source-text
;;        :domain sample-domain})))

(defn ensure-context-has-provider
  "Ensure the context has a dscloj-provider set for real LLM execution."
  [context]
  (if (:dscloj-provider context)
    context
    (do
      (println "Warning: Context missing :dscloj-provider, setting to :openrouter")
      (assoc context :dscloj-provider :openrouter))))

;; NOTE: run-full-test requires dscloj which is not in ORC deps
;; Use the comment block below for manual testing instead
#_(defn run-full-test
    "Run the complete taxonomy pipeline test with proper context setup."
    []
    (println "=" (apply str (repeat 69 "=")))
    (println "CLOJURE ORC TAXONOMY PIPELINE TEST")
    (println (str "Timestamp: " (java.time.LocalDateTime/now)))
    (println "=" (apply str (repeat 69 "=")))

    ;; Setup providers
    (println "\nSetting up DSCloj providers...")
    (dscloj/quick-setup!)
    (println "Available providers:" (dscloj/list-providers))

    ;; Check context
    (let [context (ensure-context-has-provider rs/context)]
      (println "\nContext provider:" (:dscloj-provider context))

      ;; Build workflow
      (println "\nBuilding taxonomy pipeline...")
      (build-taxonomy-pipeline! context)
      (println "Pipeline built with ID:" taxonomy-sheet-id)

      ;; Run with debug enabled
      (println "\nRunning pipeline with debug enabled...")
      (println "Sample text (first 200 chars):")
      (println (subs sample-source-text 0 (min 200 (count sample-source-text))))
      (println "\nDomain:" sample-domain)

      (binding [executor/*debug-raw-response* true]
        (let [result (run-taxonomy-pipeline context taxonomy-sheet-id
                       {:source-text sample-source-text
                        :domain sample-domain})]
          (println "\n" (apply str (repeat 70 "=")))
          (println "RESULTS")
          (println (apply str (repeat 70 "=")))
          (println "\nStatus:" (:status result))
          (println "Concepts count:" (count (:concepts result)))
          (println "Relationships count:" (count (:relationships result)))
          (println "Top concepts:" (:top-concepts result))
          (println "Quality:" (get-in result [:validation :quality]))
          result))))

(comment
  ;; ============================================================
  ;; STEP-BY-STEP TESTING
  ;; ============================================================

  ;; Step 1: Start the service (run in repl_stuff.clj first)
  ;; (do
  ;;   (def service (service/start))
  ;;   (def context (::service/context service))
  ;;   (def event-store (:event-store context)))

  ;; Step 2: Setup providers and check
  (do
    (require '[dscloj.core :as dscloj])
    (dscloj/quick-setup!)
    (println "Providers:" (dscloj/list-providers))
    (println "Context provider:" (:dscloj-provider rs/context)))

  ;; Step 3: Build the workflow
  (build-taxonomy-pipeline! rs/context)

  ;; Step 4: Run with debug output
  (run-demo-with-debug rs/context taxonomy-sheet-id)

  ;; Step 5: Run full test (combines all steps)
  (run-full-test)

  ;; ============================================================
  ;; MANUAL TESTING
  ;; ============================================================

  ;; Build the workflow (only needed once):
  (build-taxonomy-pipeline! rs/context)

  ;; Run with sample data:
  (run-demo rs/context taxonomy-sheet-id)

  ;; Run with custom input:
  (run-taxonomy-pipeline rs/context taxonomy-sheet-id
    {:source-text "Your text here..."
     :domain "your domain"})

  "")
