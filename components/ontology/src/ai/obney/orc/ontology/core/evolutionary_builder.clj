(ns ai.obney.orc.ontology.core.evolutionary-builder
  "Evolutionary Ontology Builder - Orchestrates the complete pipeline.

   This is the main entry point for building ontologies from multiple sources.
   Matches Python's evolutionary_ontology_builder.py with EXACT 1:1 parity.

   Architecture:
   ┌─────────────────────────────────────────────────────────────────────────┐
   │  Layer 1: EXTRACTION (delegates to existing ORC sheets)                 │
   │    csv_ontology.clj, ontology_exploration.clj, unified_ontology.clj    │
   ├─────────────────────────────────────────────────────────────────────────┤
   │  Layer 2: RESOLUTION (this namespace)                                   │
   │    source_registry.clj, entity_resolver.clj                            │
   ├─────────────────────────────────────────────────────────────────────────┤
   │  Layer 3: EVOLUTION (this namespace)                                    │
   │    graph_evolver.clj                                                    │
   └─────────────────────────────────────────────────────────────────────────┘

   Usage:
     ;; Batch mode - build from scratch
     (evolutionary/build-from-sources ctx
       {:sources [{:path 'data.csv' :type 'csv'}]
        :config {:base-uri 'http://example.org/'}})

     ;; Incremental mode - extend existing
     (evolutionary/evolve ctx
       {:ontology-id existing-id
        :sources [{:path 'new-data.csv' :type 'csv'}]})"
  (:require [ai.obney.orc.ontology.core.source-registry :as source-registry]
            [ai.obney.orc.ontology.core.entity-resolver :as entity-resolver]
            [ai.obney.orc.ontology.core.graph-evolver :as graph-evolver]
            [ai.obney.orc.ontology.core.colbert-indexer :as colbert-indexer]
            [ai.obney.orc.ontology.core.field-analyzer :as field-analyzer]
            [ai.obney.orc.ontology.core.embedding :as embedding]
            [ai.obney.grain.event-store-v3.interface :as event-store :refer [->event]]
            [ai.obney.orc.orc-service.interface :as sheet]
            [clojure.string :as str]
            [clojure.data.csv :as csv]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [com.brunobonacci.mulog :as mu]))

;; =============================================================================
;; Configuration Defaults
;; =============================================================================

(def default-config
  "Default configuration for evolutionary building"
  {:base-uri "http://ontology.local/"
   :similarity-threshold 0.85
   :emit-owl-sameAs? true
   :use-type-blocking? true
   :include-metadata? true
   ;; ColBERT indexing options (enabled by default for pre-computed search)
   :enable-colbert? true            ;; Auto-create ColBERT index
   :colbert-index-name nil          ;; Auto-generate if nil (uses ontology-id prefix)
   :colbert-fields nil              ;; Auto-detect if nil
   :auto-detect-colbert-fields? true
   ;; Embedding indexing options (enabled by default for RRF search)
   :enable-embeddings? true         ;; Auto-create MiniLM embeddings
   :embedding-fields nil            ;; Use ColBERT fields if nil, or auto-detect
   :auto-detect-embedding-fields? true})

;; =============================================================================
;; Ontology ID Normalization (Issue 001: Grain Schema Compliance)
;; =============================================================================

(defn- normalize-ontology-id
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
    (java.util.UUID/nameUUIDFromBytes (.getBytes (str id)))))

;; =============================================================================
;; Source Loading
;; =============================================================================

(defn load-source-content
  "Load content from a source specification.
   For SQL databases, returns the path (no content loading needed).
   Returns {:content string :file-size int :db-path string (for SQL)}"
  [{:keys [path content type]}]
  (cond
    ;; SQL database - don't load content, just validate path exists
    (or (= type "sql") (= type "sqlite") (= type "db")
        (and path (str/ends-with? path ".db")))
    (if (and path (.exists (java.io.File. ^String path)))
      {:content path  ;; For SQL, content IS the path
       :db-path path
       :file-size (.length (java.io.File. ^String path))}
      (throw (ex-info "Database file not found" {:path path})))

    ;; Direct content provided
    content
    {:content content
     :file-size (count (.getBytes ^String content "UTF-8"))}

    ;; File path - read content
    (and path (.exists (java.io.File. ^String path)))
    {:content (slurp path)
     :file-size (.length (java.io.File. ^String path))}

    ;; Path doesn't exist
    :else
    (throw (ex-info "Source not found" {:path path}))))

;; =============================================================================
;; CSV String Parsing
;; =============================================================================

(defn parse-csv-string
  "Parse a CSV string into a vector of maps with keyword keys.
   First row is treated as headers."
  [csv-string]
  (with-open [reader (java.io.StringReader. csv-string)]
    (let [[header & rows] (csv/read-csv reader)
          header-keys (mapv keyword header)]
      (mapv #(zipmap header-keys %) rows))))

;; =============================================================================
;; ORC Sheet Management
;; =============================================================================

(def ^:private orc-sheet-ids
  "Atom to cache built ORC sheet IDs."
  (atom {}))

(defn- ensure-csv-sheet!
  "Ensure the CSV ontology ORC sheet is built, return its ID."
  [ctx]
  (or (get @orc-sheet-ids :csv)
      (let [;; Require dynamically to avoid circular deps at compile time
            csv-ns (requiring-resolve 'ai.obney.orc.ontology.sheets.csv-ontology/build-csv-ontology-pipeline!)
            sheet-id (csv-ns ctx)]
        (swap! orc-sheet-ids assoc :csv sheet-id)
        sheet-id)))

(defn- ensure-text-sheet!
  "Ensure the taxonomy ORC sheet is built, return its ID."
  [ctx]
  (or (get @orc-sheet-ids :text)
      (let [;; Require dynamically to avoid circular deps at compile time
            text-ns (requiring-resolve 'ai.obney.orc.ontology.sheets.ontology-exploration/build-taxonomy-pipeline!)
            sheet-id (text-ns ctx)]
        (swap! orc-sheet-ids assoc :text sheet-id)
        sheet-id)))

(defn- ensure-sql-sheet!
  "Ensure the SQL ontology ORC sheet is built, return its ID."
  [ctx]
  (or (get @orc-sheet-ids :sql)
      (let [;; Require dynamically to avoid circular deps at compile time
            sql-ns (requiring-resolve 'ai.obney.orc.ontology.sheets.sql-ontology/build-sql-ontology-pipeline!)
            sheet-id (sql-ns ctx)]
        (swap! orc-sheet-ids assoc :sql sheet-id)
        sheet-id)))

(defn- ensure-json-sheet!
  "Ensure the JSON ontology ORC sheet is built, return its ID."
  [ctx]
  (or (get @orc-sheet-ids :json)
      (let [;; Require dynamically to avoid circular deps at compile time
            json-ns (requiring-resolve 'ai.obney.orc.ontology.sheets.json-ontology/build-json-ontology-pipeline!)
            sheet-id (json-ns ctx)]
        (swap! orc-sheet-ids assoc :json sheet-id)
        sheet-id)))

;; =============================================================================
;; NOTE: Fallback extractors have been REMOVED
;; =============================================================================
;;
;; POLICY: Never use fallback extractors. ORC sheets must work correctly.
;; If extraction fails, the error should be propagated so the root cause
;; can be identified and fixed. Fallbacks mask real issues and are useless
;; for production testing.
;;

;; =============================================================================
;; Extraction Dispatching (ORC Sheet Integration)
;; =============================================================================

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
  (let [;; Parse CSV string if needed
        parsed-data (if (string? csv-data)
                      (parse-csv-string csv-data)
                      csv-data)

        ;; Determine entity column (default to first column)
        entity-column (or (:entity-column config)
                          (when (seq parsed-data)
                            (name (first (keys (first parsed-data)))))
                          "name")

        ;; Build/get ORC sheet
        sheet-id (ensure-csv-sheet! ctx)

        ;; Require run function dynamically
        run-fn (requiring-resolve 'ai.obney.orc.ontology.sheets.csv-ontology/run-csv-to-ontology)

        ;; Execute ORC workflow
        result (run-fn ctx sheet-id
                 {:csv-data parsed-data
                  :entity-column entity-column
                  :entity-type (or (:entity-type config) "Entity")
                  :base-uri (or (:base-uri config) "http://ontology.local/")})]

    (if (= :success (:status result))
      ;; Map ORC output to evolutionary format
      (let [entities (or (:entities result) [])
            relationships (or (:relationships result) [])
            discovered-rels (or (:discovered-relationships result) [])
            tbox (:tbox result)
            abox (:abox result)]
        {:concepts
         (mapv (fn [e]
                 (let [name (or (get e "name") (get e :name) "Unknown")]
                   {:uri (str (:base-uri config) (str/replace name #"\s+" "_"))
                    :label name
                    :definition (or (get e "description") (get e :description) "")
                    :entity-type (or (:entity-type config) "Entity")
                    :source-id source-id
                    :confidence 1.0}))
               entities)

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

         ;; T-box: OWL classes and properties (schema)
         :tbox {:classes (or (:classes tbox) [])
                :object-properties (or (:object-properties tbox) [])
                :datatype-properties (or (:datatype-properties tbox) [])}

         ;; A-box: OWL individuals (instances)
         :abox (or abox [])

         :owl-output (:owl-output result)})

      ;; NEVER fall back - throw so the issue can be debugged and fixed
      (throw (ex-info "ORC CSV extraction failed"
                      {:error (:error result)
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
  (let [;; Build/get ORC sheet
        sheet-id (ensure-text-sheet! ctx)

        ;; Require run function dynamically
        run-fn (requiring-resolve 'ai.obney.orc.ontology.sheets.ontology-exploration/run-taxonomy-pipeline)

        ;; Execute ORC workflow
        result (run-fn ctx sheet-id
                 {:source-text text-content
                  :domain (or (:domain config) "general")
                  :existing-concepts (or (:existing-concepts config) [])})]

    (if (= :success (:status result))
      ;; Map ORC output to evolutionary format
      (let [concepts (or (:concepts result) [])
            relationships (or (:relationships result) [])
            causal-rels (get-in result [:validation :causal-relations] [])]
        {:concepts
         (mapv (fn [c]
                 (let [label (or (get c "label") (get c :label) "Unknown")
                       ;; Ensure alt-labels is always a vector of strings
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
           ;; Broader/narrower → skos:broader
           (mapv (fn [r]
                   {:subject (str (:base-uri config)
                                  (str/replace (or (get r "narrower") (get r :narrower) "") #"\s+" "_"))
                    :predicate "skos:broader"
                    :object (str (:base-uri config)
                                 (str/replace (or (get r "broader") (get r :broader) "") #"\s+" "_"))})
                 relationships)
           ;; Related pairs → skos:related
           (mapv (fn [rp]
                   {:subject (str (:base-uri config)
                                  (str/replace (or (get rp "concept1") (get rp :concept1) "") #"\s+" "_"))
                    :predicate "skos:related"
                    :object (str (:base-uri config)
                                 (str/replace (or (get rp "concept2") (get rp :concept2) "") #"\s+" "_"))
                    :reason (or (get rp "reason") (get rp :reason) "")})
                 (or (:related-pairs result) [])))

         :top-concepts (:top-concepts result)
         :skos-output (:skos-output result)})

      ;; NEVER fall back - throw so the issue can be debugged and fixed
      (throw (ex-info "ORC text extraction failed"
                      {:error (:error result)
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
  (let [;; Build/get ORC sheet
        sheet-id (ensure-sql-sheet! ctx)

        ;; Require run function dynamically
        run-fn (requiring-resolve 'ai.obney.orc.ontology.sheets.sql-ontology/run-sql-to-ontology)

        ;; Execute ORC workflow
        result (run-fn ctx sheet-id
                 {:db-path db-path
                  :base-uri (or (:base-uri config) "http://db.local/")
                  :max-tables (or (:max-tables config) 50)
                  :max-instances (or (:max-instances config) 10)})]

    (if (= :success (:status result))
      ;; Map ORC output to evolutionary format
      (let [table-entities (or (:table-entities result) {})
            tbox (:tbox result)
            abox (or (:abox result) [])]
        {:concepts
         ;; Create concepts from table entities (classes)
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
         ;; Extract relationships from object properties
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
         :owl-output (:owl-output result)})

      ;; NEVER fall back - throw so the issue can be debugged and fixed
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
  (let [;; Parse JSON string if needed
        parsed-data (if (string? json-data)
                      (json/read-str json-data :key-fn keyword)
                      json-data)

        ;; Build/get ORC sheet
        sheet-id (ensure-json-sheet! ctx)

        ;; Require run function dynamically
        run-fn (requiring-resolve 'ai.obney.orc.ontology.sheets.json-ontology/run-json-to-ontology)

        ;; Execute ORC workflow
        result (run-fn ctx sheet-id
                 {:json-data parsed-data
                  :base-uri (or (:base-uri config) "http://json.local/")
                  :domain (:domain config)})]

    (if (= :success (:status result))
      ;; Map ORC output to evolutionary format
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
         :owl-output (:owl-output result)})

      ;; NEVER fall back - throw so the issue can be debugged and fixed
      (throw (ex-info "ORC JSON extraction failed"
                      {:error (:error result)
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

    ;; SQL/SQLite database extraction
    ("sql" "sqlite" "db")
    (let [;; For SQL, content should be the db path, or look in config
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
    "rdf" {:concepts [] :relationships []}   ;; TODO: RDF import

    ;; Auto-detect based on content/path
    (cond
      ;; Looks like a SQLite database path
      (and (string? content) (str/ends-with? content ".db"))
      (extract-concepts-from-sql ctx source-id content config)

      ;; Looks like CSV (starts with headers or has commas)
      (and (string? content) (str/includes? content ",") (str/includes? content "\n"))
      (extract-concepts-from-csv ctx source-id content config)

      ;; Default - treat as text
      :else
      (extract-concepts-from-text ctx source-id content config))))

;; =============================================================================
;; Build Pipeline
;; =============================================================================

(defn register-sources
  "Register all sources, returning registration results and events."
  [read-models sources]
  (reduce (fn [{:keys [registered events]} source]
            (let [{:keys [path content type]} source
                  content-loaded (when path (load-source-content (assoc source :type type)))
                  actual-content (or content (:content content-loaded))
                  db-path (:db-path content-loaded)  ;; For SQL sources

                  result (source-registry/register-source!
                           read-models
                           {:source-uri path
                            :source-type type
                            :content actual-content})]

              {:registered (conj registered
                                 (assoc result
                                        :source source
                                        :loaded-content actual-content
                                        :db-path db-path))  ;; Preserve db-path for SQL
               :events (into events (:events result))}))
          {:registered [] :events []}
          sources))

(defn extract-from-all-sources
  "Extract concepts from all registered sources.

   Args:
     ctx - Context with :event-store (needed for ORC sheet execution)
     registered-sources - Vector of registration results
     config - Configuration options"
  [ctx registered-sources config]
  (reduce (fn [{:keys [all-concepts all-relationships all-tbox all-abox events]} reg]
            (let [{:keys [source-id loaded-content source db-path]} reg
                  ;; Merge db-path into config for SQL sources
                  source-config (if db-path
                                  (assoc config :db-path db-path :path (:path source))
                                  config)
                  extraction (extract-from-source
                               ctx
                               source-id
                               (:type source)
                               loaded-content
                               source-config)
                  ;; Tag concepts with source-id (may already be tagged by ORC extraction)
                  tagged-concepts (mapv #(if (:source-id %)
                                           %
                                           (assoc % :source-id source-id))
                                        (:concepts extraction))

                  ;; Get T-box and A-box from extraction
                  tbox (:tbox extraction)
                  abox (:abox extraction)

                  ;; Create extraction events (concepts + relationships)
                  base-events [(->event {:type :evolutionary/concepts-extracted
                                         :tags #{[:source source-id]}
                                         :body {:source-id source-id
                                                :ontology-id (:ontology-id config)
                                                :concepts tagged-concepts
                                                :extracted-at (str (java.time.Instant/now))}})
                               (->event {:type :evolutionary/relationships-extracted
                                         :tags #{[:source source-id]}
                                         :body {:source-id source-id
                                                :ontology-id (:ontology-id config)
                                                :relationships (:relationships extraction)
                                                :extracted-at (str (java.time.Instant/now))}})]

                  ;; Add T-box event if we have schema data (classes/properties)
                  tbox-event (when (or (seq (:classes tbox))
                                       (seq (:object-properties tbox))
                                       (seq (:datatype-properties tbox)))
                               (->event {:type :evolutionary/tbox-extracted
                                         :tags #{[:source source-id]}
                                         :body {:source-id source-id
                                                :ontology-id (:ontology-id config)
                                                :classes (vec (:classes tbox))
                                                :object-properties (vec (:object-properties tbox))
                                                :datatype-properties (vec (:datatype-properties tbox))
                                                :extracted-at (str (java.time.Instant/now))}}))

                  ;; Add A-box event if we have individuals
                  abox-event (when (seq abox)
                               (->event {:type :evolutionary/abox-extracted
                                         :tags #{[:source source-id]}
                                         :body {:source-id source-id
                                                :ontology-id (:ontology-id config)
                                                :individuals (vec abox)
                                                :extracted-at (str (java.time.Instant/now))}}))

                  ;; Combine all events
                  extract-events (cond-> base-events
                                   tbox-event (conj tbox-event)
                                   abox-event (conj abox-event))]

              {:all-concepts (into all-concepts tagged-concepts)
               :all-relationships (into all-relationships (:relationships extraction))
               :all-tbox (merge-with into all-tbox tbox)
               :all-abox (into all-abox abox)
               :events (into events extract-events)}))
          {:all-concepts [] :all-relationships [] :all-tbox {} :all-abox [] :events []}
          (filter (complement :already-registered?) registered-sources)))

;; =============================================================================
;; Main Entry Points
;; =============================================================================

(defn build-from-sources
  "Build ontology from sources in BATCH mode.

   This builds a complete ontology from scratch:
   1. Register all sources (with deduplication)
   2. Extract concepts from each source
   3. Resolve entities across sources (batch mode)
   4. Merge into unified graph
   5. Generate TTL snapshot
   6. Create ColBERT index (optional, if :enable-colbert? true)

   Args:
     ctx - Context with :event-store
     params - {:sources [{:path :type :content}...]
               :config {:base-uri :similarity-threshold ...
                        :enable-colbert? true  ;; Enable ColBERT indexing
                        :colbert-fields [:description :tasks :skills]  ;; or nil for auto-detect
                        :colbert-index-name \"my-index\"}}

   Returns:
     {:ontology-id uuid
      :build-id uuid
      :total-sources int
      :total-concepts int
      :total-triples int
      :entities-resolved int
      :ttl-output string
      :colbert-index-id uuid  ;; if ColBERT enabled
      :colbert-fields [...]   ;; detected/used fields
      :events [...]}"
  [{:keys [event-store] :as ctx} {:keys [sources config]}]
  (let [config (merge default-config config)
        ;; Normalize ontology-id to UUID for Grain schema compliance (Issue 001)
        ontology-id (normalize-ontology-id (or (:ontology-id config) (java.util.UUID/randomUUID)))
        build-id (java.util.UUID/randomUUID)
        config (assoc config :ontology-id ontology-id)

        ;; Build start event
        start-event (->event {:type :evolutionary/build-started
                              :tags #{[:ontology ontology-id]
                                      [:build build-id]}
                              :body {:build-id build-id
                                     :ontology-id ontology-id
                                     :mode "batch"
                                     :source-count (count sources)
                                     :config config
                                     :started-at (str (java.time.Instant/now))}})

        start-time (System/currentTimeMillis)

        ;; Phase 1: Load existing read models
        existing-events (event-store/read event-store
                                          {:types #{:evolutionary/source-registered}})
        read-models (source-registry/build-read-models existing-events)

        ;; Phase 2: Register sources
        {:keys [registered events]} (register-sources read-models sources)
        registration-events events

        ;; Phase 3: Extract from sources (using ORC sheets)
        {:keys [all-concepts all-relationships all-tbox all-abox events]}
        (extract-from-all-sources ctx registered config)
        extraction-events events

        ;; Phase 4: Entity resolution (batch mode)
        resolution-result (entity-resolver/resolve-within-batch
                            all-concepts
                            {:similarity-threshold (:similarity-threshold config)
                             :emit-owl-sameAs? (:emit-owl-sameAs? config)})
        resolution-event (entity-resolver/emit-entities-resolved-event
                           ontology-id :batch resolution-result)

        ;; Phase 5: Graph evolution
        source-ids (mapv :source-id registered)
        evolution-result (graph-evolver/evolve-graph!
                           {:ontology-id ontology-id
                            :source-ids source-ids
                            :extracted-concepts all-concepts
                            :extracted-relationships all-relationships
                            :resolution-result resolution-result})
        evolution-events (:events evolution-result)

        ;; Phase 6: Generate TTL snapshot (OWL with T-box/A-box if available)
        snapshot-result (graph-evolver/generate-owl-ttl-snapshot
                          {:tbox all-tbox
                           :abox all-abox
                           :concepts (:concepts (:graph evolution-result))
                           :relationships (:relationships (:graph evolution-result))}
                          {:base-uri (:base-uri config)
                           :include-metadata? (:include-metadata? config)})
        snapshot-event (graph-evolver/emit-ttl-snapshot-event
                         ontology-id snapshot-result :turtle)

        ;; Phase 7: ColBERT indexing (optional)
        colbert-result (when (:enable-colbert? config)
                         (try
                           (colbert-indexer/index-concepts! ctx
                             all-concepts
                             {:index-name (or (:colbert-index-name config)
                                              (str "ontology-" (subs (str ontology-id) 0 8)))
                              :colbert-fields (:colbert-fields config)
                              :auto-detect-colbert-fields (:auto-detect-colbert-fields? config)})
                           (catch Exception e
                             (println "[WARNING] ColBERT indexing failed:" (.getMessage e))
                             nil)))

        ;; Emit ColBERT index event if successful
        _ (when colbert-result
            (colbert-indexer/emit-colbert-indexed-event! ctx
              {:ontology-id ontology-id
               :index-id (:index-id colbert-result)
               :index-name (:index-name colbert-result)
               :document-count (:document-count colbert-result)
               :colbert-fields (:colbert-fields colbert-result)}))

        ;; Phase 8: Embedding indexing (for RRF search)
        embedding-result (when (:enable-embeddings? config)
                           (try
                             (mu/log ::embedding-concepts-start
                                     :concept-count (count all-concepts)
                                     :ontology-id ontology-id)
                             (embedding/embed-concepts-batch!
                               all-concepts
                               {:embedding-fields (or (:embedding-fields config)
                                                      (:colbert-fields colbert-result))
                                :auto-detect? (:auto-detect-embedding-fields? config)
                                :ctx ctx})  ;; Pass context for LLM-driven field detection
                             (catch Exception e
                               (mu/log ::embedding-failed :error (.getMessage e))
                               nil)))

        ;; Emit embedding events if successful and there are actual embeddings
        ;; 1. Summary event for tracking (only if embeddings were generated)
        embedding-summary-event (when (and embedding-result
                                           (pos? (:embedded-count embedding-result 0)))
                                  (->event {:type :evolutionary/concepts-embedded
                                            :tags #{[:ontology ontology-id]
                                                    [:build build-id]}
                                            :body {:ontology-id ontology-id
                                                   :build-id build-id
                                                   :embedded-count (:embedded-count embedding-result)
                                                   :embedding-fields (or (:fields-used embedding-result) [])
                                                   :model-id (:model-id embedding-result)
                                                   :embedded-at (str (java.time.Instant/now))}}))

        ;; 2. Per-concept events with actual vectors (for read model caching/cold-start)
        embedding-concept-events (when (and embedding-result (seq (:embeddings embedding-result)))
                                   (let [fields-used (:fields-used embedding-result)
                                         model-id (:model-id embedding-result)
                                         embedded-at (str (java.time.Instant/now))]
                                     (mapv (fn [{:keys [uri embedding text-embedded]}]
                                             (->event {:type :ontology/concept-embedded
                                                       :tags #{[:ontology ontology-id]
                                                               [:concept uri]}
                                                       :body {:uri uri
                                                              :ontology-id ontology-id
                                                              :embedding (mapv double embedding)  ;; CRITICAL: ensure :double
                                                              :text-embedded text-embedded
                                                              :field-source (str/join "+" (map name fields-used))
                                                              :model-id model-id
                                                              :embedded-at embedded-at}}))
                                           (:embeddings embedding-result))))

        ;; Build completion
        duration-ms (- (System/currentTimeMillis) start-time)
        completion-event (->event {:type :evolutionary/build-completed
                                   :tags #{[:ontology ontology-id]
                                           [:build build-id]}
                                   :body {:build-id build-id
                                          :ontology-id ontology-id
                                          :total-sources (count source-ids)
                                          :total-concepts (count (:concepts (:graph evolution-result)))
                                          :total-triples (:triple-count snapshot-result)
                                          :entities-resolved (+ (:exact-matches resolution-result)
                                                                (:semantic-matches resolution-result))
                                          :colbert-index-id (:index-id colbert-result)
                                          :embedded-count (:embedded-count embedding-result)
                                          :duration-ms duration-ms
                                          :completed-at (str (java.time.Instant/now))}})

        ;; Collect all events (embedding events may be nil)
        all-events (concat [start-event]
                           registration-events
                           extraction-events
                           [resolution-event]
                           evolution-events
                           [snapshot-event]
                           (when embedding-summary-event [embedding-summary-event])
                           (or embedding-concept-events [])
                           [completion-event])]

    {:ontology-id ontology-id
     :build-id build-id
     :total-sources (count source-ids)
     :total-concepts (count (:concepts (:graph evolution-result)))
     :total-triples (:triple-count snapshot-result)
     :entities-resolved (+ (:exact-matches resolution-result)
                           (:semantic-matches resolution-result))
     :resolution-stats {:exact-matches (:exact-matches resolution-result)
                        :semantic-matches (:semantic-matches resolution-result)
                        :unmatched (count (:unmatched resolution-result))}
     :schema-extensions (:schema-extensions evolution-result)
     :ttl-output (:ttl-string snapshot-result)
     :colbert-index-id (:index-id colbert-result)
     :colbert-fields (:colbert-fields colbert-result)
     :embedding-result embedding-result  ;; NEW: Include embedding result
     :duration-ms duration-ms
     :events all-events}))

(defn evolve
  "Evolve existing ontology with new sources (INCREMENTAL mode).

   This extends an existing ontology:
   1. Load existing state from events
   2. Register new sources (skip already processed)
   3. Extract with TBox context from existing ontology
   4. Resolve entities (incremental - prefer existing URIs)
   5. Merge into existing graph
   6. Generate new TTL snapshot

   Args:
     ctx - Context with :event-store
     params - {:ontology-id uuid
               :sources [{:path :type :content}...]
               :config {...}}

   Returns: Same as build-from-sources"
  [{:keys [event-store] :as ctx} {:keys [ontology-id sources config]}]
  (let [config (merge default-config config)
        ;; Normalize ontology-id to UUID for Grain schema compliance (Issue 001)
        ontology-id (normalize-ontology-id ontology-id)
        build-id (java.util.UUID/randomUUID)
        config (assoc config :ontology-id ontology-id)

        ;; Load existing state
        existing-events (event-store/read event-store
                                          {:types #{:evolutionary/source-registered
                                                    :evolutionary/concepts-extracted
                                                    :evolutionary/entities-resolved}
                                           :tags #{[:ontology ontology-id]}})

        read-models (source-registry/build-read-models existing-events)

        ;; Get existing labels/URIs for incremental resolution
        existing-concept-events (filter #(= :evolutionary/concepts-extracted (:event/type %))
                                        existing-events)
        existing-concepts (->> existing-concept-events
                               (mapcat #(get-in % [:body :concepts]))
                               vec)
        existing-labels (mapv :label existing-concepts)
        existing-uris (set (map :uri existing-concepts))

        ;; Build start event
        start-event (->event {:type :evolutionary/build-started
                              :tags #{[:ontology ontology-id]
                                      [:build build-id]}
                              :body {:build-id build-id
                                     :ontology-id ontology-id
                                     :mode "incremental"
                                     :source-count (count sources)
                                     :config config
                                     :started-at (str (java.time.Instant/now))}})

        start-time (System/currentTimeMillis)

        ;; Register new sources (will skip duplicates)
        {:keys [registered events]} (register-sources read-models sources)
        registration-events events

        ;; Extract only from new sources (using ORC sheets)
        new-sources (filter (complement :already-registered?) registered)

        {:keys [all-concepts all-relationships all-tbox all-abox events]}
        (extract-from-all-sources ctx new-sources config)
        extraction-events events

        ;; Incremental entity resolution
        resolution-result (entity-resolver/resolve-incremental
                            all-concepts
                            existing-labels
                            existing-uris
                            {:similarity-threshold (:similarity-threshold config)
                             :prefer-existing-uris? true})
        resolution-event (entity-resolver/emit-entities-resolved-event
                           ontology-id :incremental resolution-result)

        ;; Load existing graph for merging
        existing-graph-events (event-store/read event-store
                                                {:types #{:evolutionary/graph-merged}
                                                 :tags #{[:ontology ontology-id]}})
        ;; For simplicity, rebuild from concepts (full graph would need more state)
        existing-graph {:concepts (into {} (map (juxt :uri identity) existing-concepts))
                        :relationships []}

        ;; Graph evolution
        source-ids (mapv :source-id new-sources)
        evolution-result (graph-evolver/evolve-graph!
                           {:ontology-id ontology-id
                            :source-ids source-ids
                            :extracted-concepts all-concepts
                            :extracted-relationships all-relationships
                            :resolution-result resolution-result
                            :existing-graph existing-graph})
        evolution-events (:events evolution-result)

        ;; Generate new TTL snapshot (OWL with T-box/A-box if available)
        snapshot-result (graph-evolver/generate-owl-ttl-snapshot
                          {:tbox all-tbox
                           :abox all-abox
                           :concepts (:concepts (:graph evolution-result))
                           :relationships (:relationships (:graph evolution-result))}
                          {:base-uri (:base-uri config)
                           :include-metadata? (:include-metadata? config)})
        snapshot-event (graph-evolver/emit-ttl-snapshot-event
                         ontology-id snapshot-result :turtle)

        ;; Build completion
        duration-ms (- (System/currentTimeMillis) start-time)
        completion-event (->event {:type :evolutionary/build-completed
                                   :tags #{[:ontology ontology-id]
                                           [:build build-id]}
                                   :body {:build-id build-id
                                          :ontology-id ontology-id
                                          :total-sources (+ (count (:source-registry read-models))
                                                            (count new-sources))
                                          :total-concepts (count (:concepts (:graph evolution-result)))
                                          :total-triples (:triple-count snapshot-result)
                                          :entities-resolved (+ (:exact-matches resolution-result)
                                                                (:semantic-matches resolution-result))
                                          :duration-ms duration-ms
                                          :completed-at (str (java.time.Instant/now))}})

        all-events (concat [start-event]
                           registration-events
                           extraction-events
                           [resolution-event]
                           evolution-events
                           [snapshot-event completion-event])]

    {:ontology-id ontology-id
     :build-id build-id
     :total-sources (+ (count (:source-registry read-models)) (count new-sources))
     :new-sources-processed (count new-sources)
     :total-concepts (count (:concepts (:graph evolution-result)))
     :total-triples (:triple-count snapshot-result)
     :entities-resolved (+ (:exact-matches resolution-result)
                           (:semantic-matches resolution-result))
     :ttl-output (:ttl-string snapshot-result)
     :duration-ms duration-ms
     :events all-events}))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn get-build-result
  "Get the result of a completed build by build-id."
  [event-store build-id]
  (let [events (event-store/read event-store
                                 {:types #{:evolutionary/build-completed}
                                  :tags #{[:build build-id]}})]
    (some-> events first :body)))

(defn get-ontology-ttl
  "Get the latest TTL snapshot for an ontology."
  [event-store ontology-id]
  (let [events (event-store/read event-store
                                 {:types #{:evolutionary/ttl-snapshot-created}
                                  :tags #{[:ontology ontology-id]}})]
    (some-> events last :body)))

(comment
  ;; Example usage

  ;; Build from CSV sources
  (build-from-sources ctx
    {:sources [{:path "/path/to/occupations.csv" :type "csv"}
               {:path "/path/to/programs.csv" :type "csv"}]
     :config {:base-uri "http://bryc.ai/ontology#"
              :similarity-threshold 0.85}})

  ;; Build from inline content
  (build-from-sources ctx
    {:sources [{:content "Software Developer, writes code"
                :type "text"}]
     :config {:base-uri "http://example.org/"}})

  ;; Incremental evolution
  (evolve ctx
    {:ontology-id (random-uuid)  ;; Use actual ontology-id from build-from-sources
     :sources [{:path "/path/to/new-data.csv" :type "csv"}]})

  ,)
