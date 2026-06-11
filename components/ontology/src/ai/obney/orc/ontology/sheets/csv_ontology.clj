(ns ai.obney.orc.ontology.sheets.csv-ontology
  "Clojure ORC implementation of Python CSV-to-Ontology pipeline.
   Maps 1:1 to the Python CSVOntologyBuilder and CSVEnrichmentPipeline from:
   - /Users/darylroberts/Desktop/Code/area_51/ontology_exploration/src/pipeline/csv_ontology.py
   - /Users/darylroberts/Desktop/Code/area_51/ontology_exploration/src/pipeline/csv_enrichment.py

   DSPy Signatures Implemented:
   - AnalyzeCSVSchema
   - EnrichEntityDefinition
   - SuggestHierarchy
   - DiscoverImplicitRelationships
   - DetectAmbiguity
   - ValidateColumnMapping"
  (:require [ai.obney.orc.orc-service.interface :as sheet]
            [clojure.string :as str]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]))

;; =============================================================================
;; Column Type Detection (matches Python's 13 types)
;; =============================================================================

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

;; =============================================================================
;; Temporal Reasoning Support
;; Python equivalent: src/core/temporal.py
;; =============================================================================

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
        ;; Pure 4-digit year
        (re-matches #"^\d{4}$" s)
        (Integer/parseInt s)

        ;; Year at start of ISO date (2024-01-15)
        (re-matches #"^(\d{4})-\d{2}-\d{2}.*" s)
        (Integer/parseInt (second (re-matches #"^(\d{4})-.*" s)))

        ;; Year at end of US date (01/15/2024)
        (re-matches #"^\d{2}/\d{2}/(\d{4}).*" s)
        (Integer/parseInt (second (re-matches #".*(\d{4})$" s)))

        ;; Any 4-digit year in string
        (re-find #"\b(19|20)\d{2}\b" s)
        (Integer/parseInt (re-find #"(19|20)\d{2}" s))

        :else nil))))

(defn compute-temporal-relevance
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
    (> entity-year reference-year) 1.1  ;; Future - slight boost
    :else (max 0.1 (- 1.0 (* (- reference-year entity-year) decay-per-year)))))

(defn detect-column-type
  "Detect column type using pattern matching + value analysis.
   Python equivalent: detect_column_type() in csv_ontology.py"
  [column-name values]
  (let [;; 1. Pattern-based detection from column name
        pattern-type (some (fn [[type pattern]]
                             (when (re-find pattern column-name) type))
                           column-type-patterns)]
    (if pattern-type
      (name pattern-type)
      ;; 2. Value-based detection
      (let [non-null (remove nil? (remove #(= "" %) values))
            unique-count (count (set non-null))
            total-count (count non-null)]
        (cond
          ;; Boolean: values in {true, false, 1, 0, yes, no}
          (and (pos? total-count)
               (every? #{"true" "false" "1" "0" "yes" "no" "t" "f" "y" "n"}
                       (map #(str/lower-case (str %)) non-null)))
          "boolean"

          ;; Numeric: all parseable as numbers
          (and (pos? total-count)
               (every? #(try (Double/parseDouble (str %)) true
                             (catch Exception _ false))
                       non-null))
          "numeric"

          ;; Categorical: low cardinality
          (and (pos? total-count)
               (<= unique-count 50)
               (< unique-count (* 0.5 total-count)))
          "categorical"

          ;; Long text -> description
          (and (pos? total-count)
               (> (/ (reduce + (map #(count (str %)) non-null))
                     (max 1 total-count))
                  100))
          "description"

          :else "label")))))

(defn to-camel-case
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

(defn to-pascal-case
  "Convert string to PascalCase for class names.
   Python equivalent: to_pascal_case() in csv_ontology.py"
  [s]
  (if (str/blank? s)
    ""
    (let [words (str/split (str/replace s #"[_\-\s]+" " ") #"\s+")]
      (apply str (map str/capitalize words)))))

(defn slugify
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

;; =============================================================================
;; Code Executor Functions
;; =============================================================================

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
     "has-temporal-data" (boolean (seq date-columns))}))

(defn extract-temporal-metadata-fn
  "Extract temporal metadata from entities based on detected date columns.
   Python equivalent: TemporalInterval dataclass creation"
  [{:keys [inputs]}]
  (let [csv-data (get inputs :csv-data [])
        date-columns (get inputs :date-columns [])
        entity-column (get inputs :csv-entity-column)]
    (if (and (seq date-columns) (seq csv-data))
      (let [;; Prioritize columns by type (year > start > end > other)
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
            :suggested-property (to-camel-case col-name)
            :is-fk-candidate (boolean (re-find #"(?i)_id$" col-name))})))

     "detected-classes"
     (if entity-col
       [(to-pascal-case entity-col)]
       (when-let [first-label (some #(when (= "label" (:column-type %))
                                       (:name %))
                                    (for [col columns]
                                      {:name (name col)
                                       :column-type (detect-column-type
                                                      (name col)
                                                      (map #(get % col) csv-data))}))]
         [(to-pascal-case first-label)]))

     "detected-properties" []
     "detected-hierarchies" []
     "detected-foreign-keys" []}))

(defn build-csv-context-fn
  "Build context strings for LLM schema analysis.
   Python equivalent: CSVEnrichmentPipeline._build_context()"
  [{:keys [inputs]}]
  (let [column-analysis (get inputs :column-analysis [])
        csv-data (get inputs :csv-data [])
        detected-classes (get inputs :detected-classes [])
        detected-hierarchies (get inputs :detected-hierarchies [])
        detected-foreign-keys (get inputs :detected-foreign-keys [])

        ;; Build column summary
        column-summary (str "COLUMNS (" (count column-analysis) " total):\n"
                           (str/join "\n"
                             (for [col column-analysis]
                               (str "- " (:name col)
                                    " (" (:column-type col) ")"
                                    " - " (:unique-count col) " unique values"
                                    (when (pos? (:null-count col))
                                      (str ", " (:null-count col) " nulls"))
                                    "\n  Samples: " (str/join ", " (take 3 (:sample-values col)))))))

        ;; Build sample rows (first 5 rows as string)
        sample-rows (str "SAMPLE ROWS (" (min 5 (count csv-data)) " of " (count csv-data) "):\n"
                        (str/join "\n"
                          (for [row (take 5 csv-data)]
                            (pr-str row))))

        ;; Build detected patterns
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
        ;; Get sample instances from CSV
        sample-instances (vec (take 5 (map #(get % (keyword (first source-columns)) "") csv-data)))
        ;; Get related entities
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
    ;; Build a map from entity name to definition info
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
    ;; Build a map from column name to hierarchy info
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
                              (to-camel-case col-name))
     "property-type" (or (get current-mapping "property_type")
                         (get current-mapping :property_type)
                         "data")
     "sample-values" sample-values}))

(defn collect-mapping-issues-fn
  "Collect mapping issues from validation."
  [{:keys [inputs]}]
  {:mapping-issues []})

(defn build-tbox-fn
  "Build TBox (ontology schema) from entities and relationships.
   Python equivalent: CSVOntologyBuilder.build_tbox()"
  [{:keys [inputs]}]
  (let [raw-entities (get inputs :entities [])
        entity-definitions (get inputs :entity-definitions {})
        relationships (get inputs :relationships [])
        discovered-relationships (get inputs :discovered-relationships [])
        property-mappings (get inputs :property-mappings [])
        base-uri (get inputs :base-uri "http://example.org/ontology#")

        ;; Filter out entities with nil/blank names (LLM sometimes returns incomplete data)
        entities (filterv #(not (str/blank? (or (get % "name") (get % :name)))) raw-entities)

        ;; Build classes
        classes (mapv (fn [e]
                        (let [name (or (get e "name") (get e :name))
                              def-info (get entity-definitions name {})]
                          {:uri (str base-uri (to-pascal-case name))
                           :label name
                           :comment (or (get def-info :definition)
                                        (get def-info :definition)
                                        (get e "description")
                                        (get e :description)
                                        "")}))
                      entities)

        ;; Build object properties from relationships
        object-properties (vec
                            (distinct
                              (concat
                                (for [r relationships]
                                  {:uri (str base-uri (to-camel-case
                                                        (or (get r "predicate") (get r :predicate) "relatedTo")))
                                   :domain (str base-uri (to-pascal-case
                                                           (or (get r "subject") (get r :subject) "")))
                                   :range (str base-uri (to-pascal-case
                                                          (or (get r "object") (get r :object) "")))})
                                (for [r discovered-relationships]
                                  {:uri (str base-uri (to-camel-case
                                                        (or (get r "predicate") (get r :predicate) "relatedTo")))
                                   :domain (str base-uri (to-pascal-case
                                                           (or (get r "subject") (get r :subject) "")))
                                   :range (str base-uri (to-pascal-case
                                                          (or (get r "object") (get r :object) "")))}))))

        ;; Build datatype properties from mappings
        datatype-properties (vec
                              (for [m property-mappings
                                    :when (= "data" (or (get m "property_type") (get m :property_type)))]
                                {:uri (str base-uri (or (get m "suggested_name")
                                                        (get m :suggested_name)
                                                        (to-camel-case (get m "column" ""))))
                                 :domain (str base-uri (first (mapv :label classes)))
                                 :range "http://www.w3.org/2001/XMLSchema#string"}))]

    {:tbox {:classes classes
             :object-properties object-properties
             :datatype-properties datatype-properties}}))

(defn build-abox-fn
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
                                uri (str base-uri (slugify (str label)) "_" idx)
                                props (into {}
                                        (for [[k v] row
                                              :when (and v (not= "" v) (not= k entity-col-key))]
                                          [(to-camel-case (name k)) v]))]
                            {:uri uri
                             :type type-uri
                             :label (str label)
                             :properties props}))
                        csv-data))]

    {:abox individuals}))

(defn serialize-to-owl-fn
  "Serialize ontology to OWL Turtle format.
   Python equivalent: ontology.serialize(format='turtle')
   Includes TemporalEntity TBox when temporal data is present."
  [{:keys [inputs]}]
  (let [tbox (get inputs :tbox {})
        abox (get inputs :abox [])
        temporal-entities (get inputs :temporal-entities [])
        base-uri (get inputs :base-uri "http://example.org/ontology#")

        has-temporal (seq temporal-entities)

        ;; Prefixes
        prefixes (str "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                     "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                     "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n"
                     "@prefix : <" base-uri "> .\n\n")

        ;; Temporal TBox (Python: src/core/temporal.py)
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

        ;; Classes
        classes-str (str/join "\n"
                      (for [c (get tbox :classes [])]
                        (str "<" (:uri c) "> a owl:Class ;\n"
                             "  rdfs:label \"" (:label c) "\"@en"
                             (when (seq (:comment c))
                               (str " ;\n  rdfs:comment \"" (:comment c) "\"@en"))
                             " .\n")))

        ;; Object Properties
        obj-props-str (str/join "\n"
                        (for [p (get tbox :object-properties [])]
                          (str "<" (:uri p) "> a owl:ObjectProperty ;\n"
                               "  rdfs:domain <" (:domain p) "> ;\n"
                               "  rdfs:range <" (:range p) "> .\n")))

        ;; Datatype Properties
        data-props-str (str/join "\n"
                         (for [p (get tbox :datatype-properties [])]
                           (str "<" (:uri p) "> a owl:DatatypeProperty ;\n"
                                "  rdfs:domain <" (:domain p) "> ;\n"
                                "  rdfs:range <" (:range p) "> .\n")))

        ;; Individuals
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

        ;; Temporal instances
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
      ;; Temporal stats
      :temporal-column-count (count date-columns)
      :temporal-entity-count (count (filter :effective-year temporal-entities))}}))  ;; Entities with year data

;; =============================================================================
;; CSV-to-Ontology Pipeline Workflow
;; =============================================================================

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

  (sheet/workflow "csv-to-ontology"

    ;; =========================================================================
    ;; BLACKBOARD SCHEMA
    ;; Field descriptions align with Python DSPy InputField/OutputField patterns
    ;; ChainOfThought: Each LLM node has a reasoning field for reliable outputs
    ;;
    ;; IMPORTANT: Descriptions go on CONTAINER types (vector, map), not leaf types
    ;; Pattern: [:vector {:description "..."} [:map [:field :type]]]
    ;; =========================================================================
    (sheet/blackboard
      ;; === Inputs ===
      {:csv-path [:string {:description "Path to the CSV file to process"}]
       :csv-data [:vector {:description "Parsed CSV data as vector of maps"} [:map-of :keyword :any]]
       :entity-column [:string {:description "Column to use as entity label/identifier"}]
       :entity-type [:string {:description "Class name for instances (e.g., 'Program', 'Student')"}]
       :base-uri [:string {:description "Ontology namespace URI (e.g., 'http://example.org/ontology#')"}]

       ;; === ChainOfThought Reasoning Fields ===
       ;; Critical for reliable LLM outputs - each LLM node has a reasoning field
       :schema-reasoning [:string {:description "Step-by-step reasoning for analyzing CSV schema and identifying domain patterns"}]
       :definition-reasoning [:string {:description "Step-by-step reasoning for generating entity definitions"}]
       ;; Note: suggest-hierarchy already has :reasoning field below
       :relationship-reasoning [:string {:description "Step-by-step reasoning for discovering implicit relationships"}]
       :ambiguity-reasoning [:string {:description "Step-by-step reasoning for detecting ambiguous terms"}]

       ;; === Phase 1: Structure Analysis ===
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

       ;; === Phase 1.5: Temporal Column Detection ===
       :date-columns [:vector {:description "Columns containing date/temporal data"} :string]
       :has-temporal-data :boolean
       :temporal-entities [:vector {:description "Entities with extracted temporal metadata"}
                           [:map
                            [:entity-label :string]
                            [:effective-year [:maybe :int]]
                            [:start-date [:maybe :string]]
                            [:end-date [:maybe :string]]
                            [:temporal-type :string]]]

       ;; === Phase 2: Schema Analysis (LLM) ===
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

       ;; === Phase 3: Definition Generation ===
       :current-entity [:map {:description "Entity currently being processed"}
                        [:name :string] [:source_columns [:vector :string]] [:description :string]]
       :entity-name :string
       :source-columns [:vector {:description "Source CSV columns for current entity"} :string]
       :sample-instances [:vector {:description "Sample values from entity column"} :string]
       :related-entities [:vector {:description "Other entity types related to current entity"} :string]
       :definition [:string {:description "Formal 2-3 sentence definition for the entity class"}]
       :scope-note [:string {:description "Usage guidance and boundaries for this concept"}]
       :external-alignments [:vector {:description "Alignments to standard ontologies (e.g., 'schema:Course')"} :string]
       :definition-results [:vector :any]
       :entity-definitions [:map-of :string :any]

       ;; === Phase 4: Hierarchy Enrichment ===
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
       :hierarchy-results [:vector :any]
       :hierarchies [:map-of :string :any]

       ;; === Phase 5: Relationship Discovery ===
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

       ;; === Phase 6: Quality Validation ===
       :terms-to-validate [:vector {:description "Terms to check for ambiguity"} :string]
       :existing-definitions [:map-of :string :any]
       :mappings-to-validate [:vector :any]
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
       :mapping-issues [:vector :any]

       ;; === Phase 7-9: TBox/ABox ===
       :tbox [:map {:description "Ontology schema (classes and properties)"}
              [:classes [:vector [:map [:uri :string] [:label :string] [:comment :string]]]]
              [:object-properties [:vector [:map [:uri :string] [:domain :string] [:range :string]]]]
              [:datatype-properties [:vector [:map [:uri :string] [:domain :string] [:range :string]]]]]
       :abox [:vector {:description "Ontology instances"}
              [:map
               [:uri :string]
               [:type :string]
               [:label :string]
               [:properties [:map-of :string :any]]]]

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
    (sheet/sequence "csv-main-pipeline"

      ;; =======================================================================
      ;; PHASE 1: STRUCTURE ANALYSIS (Code - Rule-based)
      ;; =======================================================================
      (sheet/code "load-csv"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/load-csv-fn"
        :reads [:csv-path :csv-data]
        :writes [:csv-data])

      (sheet/code "analyze-structure"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/analyze-csv-structure-fn"
        :reads [:csv-data :entity-column]
        :writes [:column-analysis :detected-classes :detected-properties
                 :detected-hierarchies :detected-foreign-keys])

      ;; =======================================================================
      ;; PHASE 1.5: TEMPORAL COLUMN DETECTION
      ;; Python equivalent: src/core/temporal.py, src/search/temporal_spreading.py
      ;; =======================================================================
      (sheet/code "detect-temporal-columns"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/detect-temporal-columns-fn"
        :reads [:column-analysis]
        :writes [:date-columns :has-temporal-data])

      (sheet/code "extract-temporal-metadata"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/extract-temporal-metadata-fn"
        :reads [:csv-data :date-columns :entity-column]
        :writes [:temporal-entities])

      (sheet/code "build-context-strings"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/build-csv-context-fn"
        :reads [:column-analysis :csv-data :detected-classes
                :detected-hierarchies :detected-foreign-keys]
        :writes [:column-summary :sample-rows :detected-patterns])

      ;; =======================================================================
      ;; PHASE 2: SCHEMA ANALYSIS (LLM) with ChainOfThought
      ;; =======================================================================
      (sheet/llm "analyze-csv-schema"
        :model "google/gemini-2.5-flash"
        :instruction "Analyze CSV structure to understand domain and suggest ontology design. Identify 1-5 main entity types, suggest meaningful relationship names, map columns to property names (camelCase), and distinguish data properties (literals) from object properties (references)."
        :reads [:column-summary :sample-rows :detected-patterns]
        :writes [:schema-reasoning :domain :domain-description :entities :relationships :property-mappings]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      ;; =======================================================================
      ;; PHASE 3: DEFINITION GENERATION (LLM per class)
      ;; =======================================================================
      (sheet/map-each "generate-definitions" :from :entities :as :current-entity :into :definition-results
        (sheet/code "prepare-entity-context"
          :fn "ai.obney.orc.ontology.sheets.csv-ontology/prepare-entity-context-fn"
          :reads [:entities]
          :writes [:entity-name :source-columns :sample-instances :related-entities])

        (sheet/llm "enrich-entity-definition"
          :model "google/gemini-2.5-flash"
          :instruction "Generate a formal 2-3 sentence definition for this entity class. Include usage boundaries in the scope note. Suggest alignments to standard ontologies (schema.org, SKOS, Dublin Core)."
          :reads [:domain]
          :writes [:definition-reasoning :definition :scope-note :external-alignments]
          :retry {:max-attempts 2 :backoff-ms [200 1000]}))

      (sheet/code "collect-definitions"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/collect-entity-definitions-fn"
        :reads [:entities :definition-results]
        :writes [:entity-definitions])

      ;; =======================================================================
      ;; PHASE 4: HIERARCHY ENRICHMENT (LLM per categorical column)
      ;; =======================================================================
      (sheet/code "identify-categorical-columns"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/identify-categorical-columns-fn"
        :reads [:column-analysis]
        :writes [:categorical-columns])

      (sheet/map-each "build-hierarchies" :from :categorical-columns :as :current-column :into :hierarchy-results
        (sheet/code "prepare-hierarchy-context"
          :fn "ai.obney.orc.ontology.sheets.csv-ontology/prepare-hierarchy-context-fn"
          :reads [:csv-data]
          :writes [:column-name :unique-values :value-counts])

        (sheet/llm "suggest-hierarchy"
          :model "google/gemini-2.5-flash"
          :instruction "Determine if these categorical values have a natural hierarchy (ordering, containment, or specialization). Not all columns have hierarchies - return has_hierarchy=false if none exists."
          :reads [:value-counts]
          :writes [:has-hierarchy :hierarchy-type :hierarchy-relationships :top-level :reasoning]
          :retry {:max-attempts 2 :backoff-ms [200 1000]}))

      (sheet/code "collect-hierarchies"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/collect-hierarchies-fn"
        :reads [:categorical-columns :hierarchy-results]
        :writes [:hierarchies])

      ;; =======================================================================
      ;; PHASE 5: RELATIONSHIP DISCOVERY (LLM)
      ;; =======================================================================
      (sheet/code "prepare-relationship-context"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/prepare-relationship-context-fn"
        :reads [:relationships]
        :writes [:entities-info :sample-data :existing-relationships])

      (sheet/llm "discover-implicit-relationships"
        :model "google/gemini-2.5-flash"
        :instruction "Discover implicit relationships between entity types not captured by explicit foreign keys. Look for co-occurrence patterns, domain-specific relationships, and inverse relationships. Don't duplicate existing relationships."
        :reads [:existing-relationships]
        :writes [:relationship-reasoning :discovered-relationships :inverse-relationships]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      ;; =======================================================================
      ;; PHASE 6: QUALITY VALIDATION (LLM)
      ;; =======================================================================
      (sheet/code "prepare-validation-context"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/prepare-validation-context-fn"
        :reads [:property-mappings]
        :writes [:terms-to-validate :existing-definitions :mappings-to-validate])

      (sheet/llm "detect-ambiguity"
        :model "google/gemini-2.5-flash"
        :instruction "Detect ambiguous terms: those with multiple meanings, overlapping concepts that should be merged or distinguished, and terms with unclear scope."
        :reads [:existing-definitions]
        :writes [:ambiguity-reasoning :ambiguous-terms]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      ;; Note: In full implementation, would have map-each for validate-column-mapping
      ;; Simplified for initial implementation

      (sheet/code "collect-mapping-issues"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/collect-mapping-issues-fn"
        :reads [:mappings-to-validate]
        :writes [:mapping-issues])

      ;; =======================================================================
      ;; PHASE 7: TBOX CONSTRUCTION (Code)
      ;; =======================================================================
      (sheet/code "build-tbox"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/build-tbox-fn"
        :reads [:entities :entity-definitions :relationships :discovered-relationships
                :property-mappings :mapping-issues :hierarchies :base-uri]
        :writes [:tbox])

      ;; =======================================================================
      ;; PHASE 8: ABOX CONSTRUCTION (Code)
      ;; =======================================================================
      (sheet/code "build-abox"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/build-abox-fn"
        :reads [:tbox :csv-data :column-analysis :entity-column :entity-type
                :hierarchies :base-uri]
        :writes [:abox])

      ;; =======================================================================
      ;; PHASE 9: SERIALIZATION (Code)
      ;; =======================================================================
      (sheet/code "serialize-to-owl"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/serialize-to-owl-fn"
        :reads [:tbox :abox :temporal-entities :base-uri]
        :writes [:owl-output])

      (sheet/code "compute-statistics"
        :fn "ai.obney.orc.ontology.sheets.csv-ontology/compute-csv-statistics-fn"
        :reads [:csv-data :tbox :abox :temporal-entities :date-columns]
        :writes [:statistics]))))

;; =============================================================================
;; API Functions
;; =============================================================================

(def csv-ontology-sheet-id #uuid "b2c3d4e5-f6a7-8901-bcde-f23456789012")

(defn build-csv-ontology-pipeline!
  "Build the CSV-to-ontology pipeline workflow. Returns sheet-id."
  [context]
  (sheet/build-workflow! context csv-to-ontology-pipeline))

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
  (let [result (sheet/execute context sheet-id
                 {:csv-path csv-path
                  :csv-data csv-data
                  :entity-column entity-column
                  :entity-type entity-type
                  :base-uri base-uri})]
    (if (= :success (:status result))
      {:status :success
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
       :error (:error result)})))

;; =============================================================================
;; Sample Data for Testing
;; =============================================================================

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

(defn run-demo
  "Run the CSV-to-ontology pipeline with sample data."
  [context sheet-id]
  (run-csv-to-ontology context sheet-id
    {:csv-data sample-csv-data
     :entity-column "name"
     :entity-type "Program"
     :base-uri "http://example.org/education#"}))

(comment
  ;; ============================================================
  ;; TESTING
  ;; ============================================================

  ;; Step 1: Start the service (run in repl_stuff.clj first)

  ;; Step 2: Build the workflow
  (build-csv-ontology-pipeline! rs/context)

  ;; Step 3: Run with sample data
  (run-demo rs/context csv-ontology-sheet-id)

  ;; Step 4: Run with custom CSV file
  (run-csv-to-ontology rs/context csv-ontology-sheet-id
    {:csv-path "/path/to/your/data.csv"
     :entity-column "name"
     :entity-type "MyEntity"
     :base-uri "http://example.org/my-ontology#"})

  "")
