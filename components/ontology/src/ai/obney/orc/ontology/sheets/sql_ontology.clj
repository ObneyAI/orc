(ns ai.obney.orc.ontology.sheets.sql-ontology
  "Clojure ORC implementation for SQL database to ontology extraction.
   Analyzes database schema (tables, columns, foreign keys) and extracts
   an OWL ontology with T-box (schema) and A-box (individuals).

   Key Features:
   - Schema introspection via SQLite PRAGMA
   - Foreign key relationship discovery
   - LLM-assisted semantic enrichment
   - OWL Turtle serialization

   Designed for databases like IPEDS (Integrated Postsecondary Education Data System)."
  (:require [ai.obney.orc.orc-service.interface :as sheet]
            [litellm.router :as litellm-router]
            [litellm.config :as litellm-config]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.data.json :as json]))

;; =============================================================================
;; SQLite Database Utilities
;; =============================================================================

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

;; =============================================================================
;; Column Type Detection
;; =============================================================================

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

;; =============================================================================
;; Name Conversion Utilities
;; =============================================================================

(defn to-pascal-case
  "Convert string to PascalCase for class names."
  [s]
  (if (str/blank? s)
    ""
    (let [words (str/split (str/replace (str s) #"[_\-\s]+" " ") #"\s+")]
      (apply str (map str/capitalize words)))))

(defn to-camel-case
  "Convert string to camelCase for property names."
  [s]
  (if (str/blank? s)
    ""
    (let [words (str/split (str/replace (str s) #"[_\-\s]+" " ") #"\s+")]
      (if (empty? words)
        ""
        (str (str/lower-case (first words))
             (apply str (map str/capitalize (rest words))))))))

(defn slugify
  "Convert string to URL-safe slug."
  [s]
  (if (str/blank? s)
    ""
    (-> (str s)
        str/lower-case
        (str/replace #"[^a-z0-9\s-]" "")
        (str/replace #"\s+" "-")
        (str/replace #"-+" "-"))))

;; =============================================================================
;; Code Executor Functions
;; =============================================================================

(defn connect-to-database-fn
  "Connect to SQLite database and extract basic metadata.
   Note: Connection is NOT returned to avoid ORC serialization issues."
  [{:keys [inputs]}]
  (let [db-path (get inputs :db-path)
        conn (when db-path (get-connection db-path))]
    (if conn
      (let [tables (get-tables conn)]
        ;; Close connection - we'll reconnect in analyze-schema-fn
        ;; Don't return connection object as it causes ORC blackboard issues
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

              ;; Summary stats
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

        ;; Build table summary
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

        ;; Build FK relationship summary
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

        ;; Identify key columns
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
                                  (to-pascal-case table-name))
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

        ;; Extract JSON from LLM response (may be wrapped in markdown code block)
        clean-json (cond
                     (str/blank? json-str) "{}"

                     ;; Handle markdown code blocks
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

        ;; Parse JSON using clojure.data.json
        parsed (try
                 (json/read-str clean-json :key-fn keyword)
                 (catch Exception _
                   ;; Fallback: empty map if JSON parsing fails
                   {}))

        ;; Get table names we need entities for
        table-names (set (map :table-name table-analysis))

        ;; Normalize parsed entries to match expected structure
        table-entities
        (into {}
          (for [table-name table-names
                :let [;; Try different key formats the LLM might use
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
                               (to-pascal-case table-name))
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
               ;; Fallback for tables not in LLM response
               {:class-name (to-pascal-case table-name)
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

        ;; Build classes from tables
        classes
        (vec
         (for [t table-analysis
               :let [table-name (:table-name t)
                     entity (get table-entities table-name {})
                     class-name (or (:class-name entity) (to-pascal-case table-name))]]
           {:uri (str base-uri class-name)
            :label class-name
            :comment (or (:definition entity) (str "Entity representing " table-name " table"))
            :source-table table-name}))

        ;; Build datatype properties from columns
        datatype-properties
        (vec
         (for [t table-analysis
               col (:columns t)
               :let [table-name (:table-name t)
                     prop-name (to-camel-case (str table-name "_" (:name col)))
                     domain-class (or (:class-name (get table-entities table-name {}))
                                      (to-pascal-case table-name))]]
           {:uri (str base-uri prop-name)
            :label (:name col)
            :domain (str base-uri domain-class)
            :range (:xsd-type col)
            :source-column (:name col)
            :source-table table-name}))

        ;; Build object properties from foreign keys
        object-properties
        (vec
         (concat
          ;; From explicit FKs
          (for [t table-analysis
                fk (:foreign-keys t)
                :let [from-table (:table-name t)
                      from-class (or (:class-name (get table-entities from-table {}))
                                     (to-pascal-case from-table))
                      to-table (:to-table fk)
                      to-class (or (:class-name (get table-entities to-table {}))
                                   (to-pascal-case to-table))
                      prop-name (to-camel-case (str "has" (to-pascal-case to-table)))]]
            {:uri (str base-uri prop-name)
             :label prop-name
             :domain (str base-uri from-class)
             :range (str base-uri to-class)
             :source-fk fk})
          ;; From discovered relationships
          (for [r discovered-relationships
                :let [subj (or (get r "subject") (get r :subject))
                      pred (or (get r "predicate") (get r :predicate))
                      obj (or (get r "object") (get r :object))]]
            {:uri (str base-uri (to-camel-case pred))
             :label pred
             :domain (str base-uri (to-pascal-case subj))
             :range (str base-uri (to-pascal-case obj))})))]

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
                           class-name (or (:class-name entity) (to-pascal-case table-name))
                           columns (:columns t)
                           ;; Find label column for instance naming
                           label-col (or (first (filter #(= :label (:semantic-type %)) columns))
                                         (first (filter :primary-key columns))
                                         (first columns))
                           label-col-name (:name label-col)
                           ;; Query sample rows
                           rows (query-db conn
                                  (str "SELECT * FROM \"" table-name "\" LIMIT " max-instances))]
                     row rows
                     :let [label-val (or (get row (keyword label-col-name)) "instance")
                           instance-id (slugify (str class-name "-" label-val "-" (rand-int 10000)))]]
                 {:uri (str base-uri instance-id)
                  :type (str base-uri class-name)
                  :label (str label-val)
                  :source-table table-name
                  :properties (into {}
                                (for [[k v] row
                                      :when (and v (not= "" v))]
                                  [(to-camel-case (str table-name "_" (name k))) v]))}))]
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

        ;; Prefixes
        prefixes (str "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                      "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                      "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n"
                      "@prefix : <" base-uri "> .\n\n")

        ;; Ontology declaration
        ontology-decl (str "# Ontology generated from SQL database: "
                           (or db-path "unknown") "\n"
                           "<" base-uri "> a owl:Ontology ;\n"
                           "  rdfs:label \"Database Ontology\"@en ;\n"
                           "  rdfs:comment \"Generated from SQL schema by ORC SQL Ontology Builder\"@en .\n\n")

        ;; Classes
        classes-str (str "# Classes (from tables)\n"
                         (str/join "\n"
                           (for [c (get tbox :classes [])]
                             (str "<" (:uri c) "> a owl:Class ;\n"
                                  "  rdfs:label \"" (:label c) "\"@en"
                                  (when (seq (:comment c))
                                    (str " ;\n  rdfs:comment \"" (:comment c) "\"@en"))
                                  " .\n"))))

        ;; Object Properties
        obj-props-str (str "\n# Object Properties (from foreign keys)\n"
                           (str/join "\n"
                             (for [p (get tbox :object-properties [])]
                               (str "<" (:uri p) "> a owl:ObjectProperty ;\n"
                                    "  rdfs:label \"" (:label p) "\"@en ;\n"
                                    "  rdfs:domain <" (:domain p) "> ;\n"
                                    "  rdfs:range <" (:range p) "> .\n"))))

        ;; Datatype Properties (limit to key columns to avoid huge output)
        key-props (take 100 (get tbox :datatype-properties []))
        data-props-str (str "\n# Datatype Properties (from columns, first 100)\n"
                            (str/join "\n"
                              (for [p key-props]
                                (str "<" (:uri p) "> a owl:DatatypeProperty ;\n"
                                     "  rdfs:label \"" (:label p) "\"@en ;\n"
                                     "  rdfs:domain <" (:domain p) "> ;\n"
                                     "  rdfs:range <" (:range p) "> .\n"))))

        ;; Individuals (limited sample)
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

;; =============================================================================
;; SQL-to-Ontology Pipeline Workflow
;; =============================================================================

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

  (sheet/workflow "sql-to-ontology"

    ;; =========================================================================
    ;; BLACKBOARD SCHEMA
    ;; =========================================================================
    (sheet/blackboard
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
       :current-table [:map {:description "Table being processed"} :any]
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
       :entity-results [:vector :any]
       :table-entities-json [:string {:description "JSON output from batch table entity extraction"}]
       :table-entities [:map-of :string :any]

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
               [:properties [:map-of :string :any]]]]

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
    (sheet/sequence "sql-main-pipeline"

      ;; =======================================================================
      ;; PHASE 1: CONNECT TO DATABASE
      ;; =======================================================================
      (sheet/code "connect-to-database"
        :fn "ai.obney.orc.ontology.sheets.sql-ontology/connect-to-database-fn"
        :reads [:db-path]
        :writes [:db-connected :table-names :table-count])

      ;; =======================================================================
      ;; PHASE 2: ANALYZE SCHEMA
      ;; =======================================================================
      (sheet/code "analyze-schema"
        :fn "ai.obney.orc.ontology.sheets.sql-ontology/analyze-schema-fn"
        :reads [:db-path :table-names :max-tables]
        :writes [:table-analysis :schema-summary])

      (sheet/code "build-schema-context"
        :fn "ai.obney.orc.ontology.sheets.sql-ontology/build-schema-context-fn"
        :reads [:table-analysis :schema-summary]
        :writes [:table-summary :fk-summary :detected-patterns])

      ;; =======================================================================
      ;; PHASE 3: SEMANTIC ANALYSIS (LLM)
      ;; =======================================================================
      (sheet/llm "analyze-database-domain"
        :model "google/gemini-2.5-flash"
        :instruction "Analyze this database schema to understand its domain and purpose. Identify the main domain (e.g., 'Higher Education', 'Healthcare', 'Finance'), describe what this database is used for, and note any domain-specific patterns or conventions."
        :reads [:table-summary :fk-summary]
        :writes [:schema-reasoning :domain :domain-description :database-purpose]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      ;; =======================================================================
      ;; PHASE 4: TABLE ENTITY EXTRACTION (BATCH LLM)
      ;; =======================================================================
      ;; Note: Using single LLM call for all tables instead of map-each
      ;; to avoid ORC map-each + LLM compatibility issues
      (sheet/llm "generate-table-entities"
        :model "google/gemini-2.5-flash"
        :instruction "For each table in the schema, generate an OWL class definition. For each table provide: a meaningful class name (PascalCase), a 1-2 sentence definition, and an entity type (Person, Organization, Event, Artifact, Concept, Location, etc.). Return as a JSON object with table names as keys."
        :reads [:table-summary :domain :domain-description]
        :writes [:table-reasoning :table-entities-json]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      (sheet/code "parse-table-entities"
        :fn "ai.obney.orc.ontology.sheets.sql-ontology/parse-table-entities-fn"
        :reads [:table-analysis :table-entities-json]
        :writes [:table-entities])

      ;; =======================================================================
      ;; PHASE 5: RELATIONSHIP DISCOVERY (LLM)
      ;; =======================================================================
      (sheet/llm "discover-relationships"
        :model "google/gemini-2.5-flash"
        :instruction "Discover implicit relationships between tables beyond explicit foreign keys. Look for: naming conventions suggesting relationships, semantic associations between entities, common patterns in the domain. Don't duplicate relationships already captured by foreign keys."
        :reads [:table-summary :fk-summary :domain :domain-description]
        :writes [:relationship-reasoning :discovered-relationships]
        :retry {:max-attempts 2 :backoff-ms [200 1000]})

      ;; =======================================================================
      ;; PHASE 6: TBOX CONSTRUCTION
      ;; =======================================================================
      (sheet/code "build-tbox"
        :fn "ai.obney.orc.ontology.sheets.sql-ontology/build-sql-tbox-fn"
        :reads [:table-analysis :table-entities :discovered-relationships :base-uri]
        :writes [:tbox])

      ;; =======================================================================
      ;; PHASE 7: ABOX EXTRACTION
      ;; =======================================================================
      (sheet/code "extract-abox"
        :fn "ai.obney.orc.ontology.sheets.sql-ontology/extract-sample-instances-fn"
        :reads [:db-path :table-analysis :table-entities :base-uri :max-instances]
        :writes [:abox])

      ;; =======================================================================
      ;; PHASE 8: SERIALIZATION
      ;; =======================================================================
      (sheet/code "serialize-to-owl"
        :fn "ai.obney.orc.ontology.sheets.sql-ontology/serialize-sql-to-owl-fn"
        :reads [:tbox :abox :base-uri :db-path]
        :writes [:owl-output])

      (sheet/code "compute-statistics"
        :fn "ai.obney.orc.ontology.sheets.sql-ontology/compute-sql-statistics-fn"
        :reads [:table-analysis :tbox :abox :schema-summary]
        :writes [:statistics]))))

;; =============================================================================
;; API Functions
;; =============================================================================

(def sql-ontology-sheet-id #uuid "c3d4e5f6-a7b8-9012-cdef-345678901234")

(defn build-sql-ontology-pipeline!
  "Build the SQL-to-ontology pipeline workflow. Returns sheet-id."
  [context]
  (sheet/build-workflow! context sql-to-ontology-pipeline))

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
                          timeout-ms 600000}}]  ;; 10 minute default for multi-LLM workflow
  ;; Pre-register model-specific provider for map-each compatibility
  ;; This ensures the provider config is available in parallel execution contexts
  (let [base-config (litellm-router/get-config :openrouter)
        model-name "google/gemini-2.5-flash"
        model-provider-key :openrouter/google/gemini-2.5-flash]
    (when (and base-config (not (litellm-router/get-config model-provider-key)))
      (litellm-router/register! model-provider-key
        {:provider :openrouter
         :model model-name
         :config (if (:config base-config)
                   (:config base-config)
                   (dissoc base-config :provider :model))})))
  (let [result (sheet/execute context sheet-id
                 {:db-path db-path
                  :base-uri base-uri
                  :max-tables max-tables
                  :max-instances max-instances}
                 {:timeout-ms timeout-ms})]
    (if (= :success (:status result))
      {:status :success
       :domain (get-in result [:outputs :domain])
       :domain-description (get-in result [:outputs :domain-description])
       :database-purpose (get-in result [:outputs :database-purpose])
       :table-entities (get-in result [:outputs :table-entities])
       :tbox (get-in result [:outputs :tbox])
       :abox (get-in result [:outputs :abox])
       :owl-output (get-in result [:outputs :owl-output])
       :statistics (get-in result [:outputs :statistics])}
      {:status :failed
       :error (:error result)})))

(comment
  ;; ============================================================
  ;; TESTING with IPEDS Database
  ;; ============================================================

  ;; Step 1: Start the service (run in repl_stuff.clj first)

  ;; Step 2: Build the workflow
  (build-sql-ontology-pipeline! rs/context)

  ;; Step 3: Run with IPEDS database
  (run-sql-to-ontology rs/context sql-ontology-sheet-id
    {:db-path "/Users/darylroberts/Downloads/output.db"
     :base-uri "http://ipeds.ai/ontology#"
     :max-tables 20
     :max-instances 5})

  "")
