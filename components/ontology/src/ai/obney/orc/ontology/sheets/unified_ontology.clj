(ns ai.obney.orc.ontology.sheets.unified-ontology
  "Unified ontology extraction - handles any source type.

   This namespace provides a single entry point for ontology extraction
   that auto-detects source type and routes to the appropriate pipeline:
   - CSV → csv_ontology.clj
   - Text → ontology_exploration.clj
   - SQL/SQLite → sql_ontology.clj
   - JSON → (planned)
   - RDF → (planned)

   The unified extractor is useful when:
   1. You don't know the source type ahead of time
   2. You want a consistent API across all source types
   3. You want auto-detection based on file extension or content"
  (:require [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.ontology.sheets.csv-ontology :as csv-ont]
            [ai.obney.orc.ontology.sheets.ontology-exploration :as text-ont]
            [ai.obney.orc.ontology.sheets.sql-ontology :as sql-ont]
            [ai.obney.orc.ontology.sheets.json-ontology :as json-ont]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.data.json :as json]))

;; =============================================================================
;; Source Type Detection
;; =============================================================================

(defn detect-source-type
  "Auto-detect source type from path or content.

   Returns one of: :csv, :text, :sql, :json, :rdf, :unknown

   Detection priority:
   1. Explicit :type in source spec
   2. File extension (.csv, .db, .sqlite, .json, .ttl, .rdf, .txt)
   3. Content inspection (commas + newlines → CSV, starts with { → JSON, etc.)"
  [{:keys [path content type]}]
  (cond
    ;; Explicit type provided
    type
    (keyword type)

    ;; Detect from file extension
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

    ;; Detect from content
    (and content (string? content))
    (let [trimmed (str/trim content)]
      (cond
        ;; JSON starts with { or [
        (or (str/starts-with? trimmed "{")
            (str/starts-with? trimmed "["))
        :json

        ;; RDF/Turtle starts with @prefix or <
        (or (str/starts-with? trimmed "@prefix")
            (str/starts-with? trimmed "PREFIX")
            (and (str/starts-with? trimmed "<")
                 (str/includes? trimmed ">")))
        :rdf

        ;; CSV has commas and newlines with consistent structure
        (and (str/includes? content ",")
             (str/includes? content "\n")
             (let [lines (str/split-lines content)]
               (and (> (count lines) 1)
                    ;; Check if lines have similar comma counts
                    (let [comma-counts (map #(count (re-seq #"," %)) (take 5 lines))]
                      (apply = comma-counts)))))
        :csv

        ;; Default to text
        :else :text))

    :else :unknown))

(defn file-exists?
  "Check if a file exists at the given path."
  [path]
  (and path (.exists (io/file path))))

;; =============================================================================
;; Sheet Management
;; =============================================================================

(def ^:private sheet-ids
  "Cache for built sheet IDs."
  (atom {}))

(defn- ensure-csv-sheet! [ctx]
  (or (get @sheet-ids :csv)
      (let [id (csv-ont/build-csv-ontology-pipeline! ctx)]
        (swap! sheet-ids assoc :csv id)
        id)))

(defn- ensure-text-sheet! [ctx]
  (or (get @sheet-ids :text)
      (let [id (text-ont/build-taxonomy-pipeline! ctx)]
        (swap! sheet-ids assoc :text id)
        id)))

(defn- ensure-sql-sheet! [ctx]
  (or (get @sheet-ids :sql)
      (let [id (sql-ont/build-sql-ontology-pipeline! ctx)]
        (swap! sheet-ids assoc :sql id)
        id)))

(defn- ensure-json-sheet! [ctx]
  (or (get @sheet-ids :json)
      (let [id (json-ont/build-json-ontology-pipeline! ctx)]
        (swap! sheet-ids assoc :json id)
        id)))

;; =============================================================================
;; Extraction Routing
;; =============================================================================

(defn extract-csv
  "Extract ontology from CSV source."
  [ctx {:keys [path content entity-column entity-type base-uri]}]
  (let [sheet-id (ensure-csv-sheet! ctx)
        csv-data (cond
                   content content
                   (and path (file-exists? path)) (slurp path)
                   :else (throw (ex-info "CSV source not found" {:path path})))]
    (csv-ont/run-csv-to-ontology ctx sheet-id
      {:csv-data csv-data
       :entity-column (or entity-column "name")
       :entity-type (or entity-type "Entity")
       :base-uri (or base-uri "http://unified.ai/")})))

(defn extract-text
  "Extract ontology from text source."
  [ctx {:keys [path content domain existing-concepts]}]
  (let [sheet-id (ensure-text-sheet! ctx)
        text-content (cond
                       content content
                       (and path (file-exists? path)) (slurp path)
                       :else (throw (ex-info "Text source not found" {:path path})))]
    (text-ont/run-taxonomy-pipeline ctx sheet-id
      {:source-text text-content
       :domain (or domain "general")
       :existing-concepts (or existing-concepts [])})))

(defn extract-sql
  "Extract ontology from SQL/SQLite database."
  [ctx {:keys [path base-uri max-tables max-instances]}]
  (let [sheet-id (ensure-sql-sheet! ctx)]
    (if (and path (file-exists? path))
      (sql-ont/run-sql-to-ontology ctx sheet-id
        {:db-path path
         :base-uri (or base-uri "http://db.ai/")
         :max-tables (or max-tables 50)
         :max-instances (or max-instances 10)})
      (throw (ex-info "SQL database not found" {:path path})))))

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
  (let [sheet-id (ensure-json-sheet! ctx)
        json-data (cond
                    content
                    (json/read-str content :key-fn keyword)

                    (and path (file-exists? path))
                    (json/read-str (slurp path) :key-fn keyword)

                    :else
                    (throw (ex-info "JSON source not found" {:path path})))]
    (json-ont/run-json-to-ontology ctx sheet-id
      {:json-data json-data
       :base-uri (or base-uri "http://json.ai/")
       :domain domain})))

(defn extract-rdf
  "Import existing RDF/OWL ontology."
  [_ctx {:keys [path content]}]
  ;; TODO: Implement RDF import sheet
  {:status :not-implemented
   :message "RDF import not yet implemented"
   :path path
   :content-provided? (boolean content)})

;; =============================================================================
;; Unified API
;; =============================================================================

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
  (let [source-type (detect-source-type source)]
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

;; =============================================================================
;; Convenience Functions
;; =============================================================================

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

(comment
  ;; ============================================================
  ;; TESTING
  ;; ============================================================

  ;; Auto-detect from file extension
  (extract rs/context {:path "/path/to/data.csv"})
  (extract rs/context {:path "/path/to/database.db"})
  (extract rs/context {:path "/path/to/document.txt"})

  ;; Explicit type
  (extract rs/context {:path "/path/to/file" :type :csv})

  ;; Inline content
  (extract rs/context {:content "Machine learning is a subset of AI." :type :text :domain "AI"})

  ;; Multiple sources
  (extract-multiple rs/context
    [{:path "data.csv"}
     {:path "database.db"}
     {:content "Some text" :type :text}])

  ;; Convenience functions
  (extract-file rs/context "/path/to/file.csv")
  (extract-database rs/context "/Users/darylroberts/Downloads/output.db")
  (extract-text-content rs/context "AI is transforming education" :domain "education")

  "")
