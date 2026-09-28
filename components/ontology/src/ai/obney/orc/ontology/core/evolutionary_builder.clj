(ns ai.obney.orc.ontology.core.evolutionary-builder
  "ORC source loading and extraction workflows connected to the ontology contribution API."
  (:require [ai.obney.orc.ontology.core.build-lifecycle :as lifecycle]
            [ai.obney.ontology.interface :as ontology]
            [ai.obney.ontology.algorithms :as algorithms]
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
  "Atom to cache built ORC sheet IDs by infrastructure, tenant, source type,
  and pinned model. A process-global type-only cache leaks sheet IDs across
  tenants and clean replay contexts."
  (atom {}))

(defn- sheet-cache-key [ctx source-type model]
  [(System/identityHashCode (:event-store ctx))
   (:tenant-id ctx)
   source-type
   model])

(defn- ensure-csv-sheet!
  "Ensure the CSV ontology ORC sheet is built, return its ID."
  [ctx model]
  (let [cache-key (sheet-cache-key ctx :csv model)]
    (or (get @orc-sheet-ids cache-key)
      (let [;; Require dynamically to avoid circular deps at compile time
            csv-ns (requiring-resolve 'ai.obney.orc.ontology.sheets.csv-ontology/build-csv-ontology-pipeline!)
            sheet-id (csv-ns ctx model)]
        (swap! orc-sheet-ids assoc cache-key sheet-id)
        sheet-id))))

(defn- ensure-text-sheet!
  "Ensure the taxonomy ORC sheet is built, return its ID."
  [ctx model]
  (let [cache-key (sheet-cache-key ctx :text model)]
    (or (get @orc-sheet-ids cache-key)
      (let [;; Require dynamically to avoid circular deps at compile time
            text-ns (requiring-resolve 'ai.obney.orc.ontology.sheets.ontology-exploration/build-taxonomy-pipeline!)
            sheet-id (text-ns ctx model)]
        (swap! orc-sheet-ids assoc cache-key sheet-id)
        sheet-id))))

(defn- ensure-sql-sheet!
  "Ensure the SQL ontology ORC sheet is built, return its ID."
  [ctx model]
  (let [cache-key (sheet-cache-key ctx :sql model)]
    (or (get @orc-sheet-ids cache-key)
      (let [;; Require dynamically to avoid circular deps at compile time
            sql-ns (requiring-resolve 'ai.obney.orc.ontology.sheets.sql-ontology/build-sql-ontology-pipeline!)
            sheet-id (sql-ns ctx model)]
        (swap! orc-sheet-ids assoc cache-key sheet-id)
        sheet-id))))

(defn- ensure-json-sheet!
  "Ensure the JSON ontology ORC sheet is built, return its ID."
  [ctx model]
  (let [cache-key (sheet-cache-key ctx :json model)]
    (or (get @orc-sheet-ids cache-key)
      (let [;; Require dynamically to avoid circular deps at compile time
            json-ns (requiring-resolve 'ai.obney.orc.ontology.sheets.json-ontology/build-json-ontology-pipeline!)
            sheet-id (json-ns ctx model)]
        (swap! orc-sheet-ids assoc cache-key sheet-id)
        sheet-id))))

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
        sheet-id (ensure-csv-sheet! ctx (:model config))

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

         ;; T-box: OWL classes and properties (schema)
         :tbox {:classes (or (:classes tbox) [])
                :object-properties (or (:object-properties tbox) [])
                :datatype-properties (or (:datatype-properties tbox) [])}

         ;; A-box: OWL individuals (instances)
         :abox (or abox [])

         :owl-output (:owl-output result)
         :trace-id (:trace-id result)})

      ;; NEVER fall back - throw so the issue can be debugged and fixed
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
  (let [;; Build/get ORC sheet
        sheet-id (ensure-text-sheet! ctx (:model config))

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
         :skos-output (:skos-output result)
         :trace-id (:trace-id result)})

      ;; NEVER fall back - throw so the issue can be debugged and fixed
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
  (let [;; Build/get ORC sheet
        sheet-id (ensure-sql-sheet! ctx (:model config))

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
         :owl-output (:owl-output result)
         :trace-id (:trace-id result)})

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
        sheet-id (ensure-json-sheet! ctx (:model config))

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
         :owl-output (:owl-output result)
         :trace-id (:trace-id result)})

      ;; NEVER fall back - throw so the issue can be debugged and fixed
      (throw (ex-info (str "ORC JSON extraction failed: "
                           (or (:error result) "unknown execution failure"))
                      {:error (:error result)
                       :trace-id (:trace-id result)
                       :source-id source-id
                       :status (:status result)})))))

(def extract-concepts-from-rdf algorithms/extract-concepts-from-rdf)

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
    "rdf" (extract-concepts-from-rdf source-id content config)

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

(defn- require-result! [result]
  (if (:cognitect.anomalies/category result)
    (throw (ex-info (:cognitect.anomalies/message result) result))
    result))

(defn- prepare-source [source]
  (let [loaded (load-source-content source)
        content (:content loaded)]
    {:source source :loaded-content content :db-path (:db-path loaded)
     :source-id (or (:source-id source) (random-uuid))
     :content-hash (if (:db-path loaded)
                     ;; Hash database bytes, never its path or an attempted text decoding.
                     (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
                       (with-open [in (io/input-stream (:db-path loaded))]
                         (let [buffer (byte-array 8192)]
                           (loop []
                             (let [n (.read in buffer)]
                               (when (pos? n) (.update digest buffer 0 n) (recur))))))
                       (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest))))
                     (algorithms/sha256 content))}))

(defn- extraction-model-provenance [ctx extraction]
  (or (:model-provenance extraction)
      (when-let [trace-id (:trace-id extraction)]
        (let [calls (->> (event-store/read (:event-store ctx)
                                         {:tenant-id (:tenant-id ctx)
                                          :types #{:sheet/node-execution-completed}})
                         (into [])
                         (filter #(and (= trace-id (:tick-id %)) (:model %)))
                         vec)]
          (when (seq calls)
            {:trace-id trace-id :models (vec (distinct (map :model calls)))
             :calls (mapv #(select-keys % [:node-id :model :usage]) calls)})))))

(defn- extract-and-contribute! [ctx ontology-id build-id config prepared]
  (let [{:keys [source source-id content-hash loaded-content db-path]} prepared
        extraction (extract-from-source ctx source-id
                     (or (:type source) (algorithms/detect-source-type (:path source)))
                     loaded-content (cond-> config db-path (assoc :db-path db-path :path (:path source))))
        concepts (mapv #(-> %
                            (assoc :description (or (:description %) (:definition %) "")
                                   :scope (or (:scope %) (:entity-type %) :custom))
                            (dissoc :definition)) (:concepts extraction))
        embedding-result (when (:enable-embeddings? config)
                           (try
                             (embedding/embed-concepts-batch! concepts
                               {:embedding-fields (:embedding-fields config)
                                :auto-detect? (:auto-detect-embedding-fields? config) :ctx ctx})
                             (catch Exception e
                               (mu/log ::embedding-failed :error (.getMessage e)) nil)))
        embeddings (into {} (map (juxt :uri identity)) (:embeddings embedding-result))
        concepts (mapv (fn [c]
                         (if-let [e (get embeddings (:uri c))]
                           (assoc c :embedding (mapv double (:embedding e))
                                    :embedding-model (:model-id embedding-result)) c)) concepts)
        result (require-result!
                 (ontology/import-contribution! ctx
                   {:ontology-id ontology-id :contribution-id (random-uuid)
                    :expected-revision (ontology/get-revision ctx ontology-id)
                    :source {:source-id source-id :content-hash content-hash
                             :uri (:path source) :type (:type source)
                             :producer {:application "orc" :build-reference (str build-id)
                                        :trace-reference (some-> (:trace-id extraction) str)
                                        :model-provenance (extraction-model-provenance ctx extraction)}}
                    :concepts concepts :relationships (vec (:relationships extraction))
                    :tbox (or (:tbox extraction) {}) :abox (vec (:abox extraction))
                    :rdf-triples (vec (:rdf-triples extraction))
                    :resolution-policy {:match-labels? true
                                        :similarity-threshold (:similarity-threshold config)}}))]
    {:result result :embedding-result embedding-result}))

(defn- run-source-workflow! [ctx ontology-id sources config mode]
  (let [build-id (random-uuid)
        started (System/currentTimeMillis)
        config (assoc (merge default-config config) :ontology-id ontology-id)]
    (lifecycle/record! ctx :evolutionary/build-started
                       {:build-id build-id :ontology-id ontology-id :mode mode
                        :source-count (count sources) :config config
                        :started-at (str (java.time.Instant/now))})
    (try
      (let [results (reduce (fn [results source]
                              (let [prepared (prepare-source source)
                                    prior (ontology/get-sources ctx ontology-id)]
                                (if (and (not (:reextract? config))
                                         (some #(= (:content-hash prepared) (:content-hash %)) prior))
                                  results
                                  (conj results (extract-and-contribute! ctx ontology-id build-id config prepared)))))
                            [] sources)
            snapshot (ontology/export-snapshot ctx ontology-id)
            concepts (:concepts snapshot)
            colbert-result (when (:enable-colbert? config)
                             (try
                               (colbert-indexer/index-concepts! ctx concepts
                                 {:index-name (or (:colbert-index-name config)
                                                  (str "ontology-" (subs (str ontology-id) 0 8)))
                                  :colbert-fields (:colbert-fields config)
                                  :auto-detect-colbert-fields (:auto-detect-colbert-fields? config)})
                               (catch Exception e
                                 (mu/log ::colbert-index-failed :error (.getMessage e)) nil)))
            _ (when colbert-result
                (colbert-indexer/emit-colbert-indexed-event! ctx
                  {:ontology-id ontology-id :index-id (:index-id colbert-result)
                   :index-name (:index-name colbert-result) :document-count (:document-count colbert-result)
                   :colbert-fields (:colbert-fields colbert-result)}))
            entities-resolved (count (filter (fn [[a b]] (not= a b)) (ontology/get-canonical-mappings ctx ontology-id)))
            summary {:ontology-id ontology-id :build-id build-id
                     :total-sources (count (ontology/get-sources ctx ontology-id))
                     :new-sources-processed (count results)
                     :total-concepts (count concepts) :total-triples (:triple-count snapshot)
                     :entities-resolved entities-resolved :revision (:revision snapshot)
                     :ttl-output (:ttl-output snapshot) :checksum (:checksum snapshot)
                     :duration-ms (- (System/currentTimeMillis) started)}]
        (lifecycle/record! ctx :evolutionary/build-completed
                           (assoc summary :completed-at (str (java.time.Instant/now))))
        (assoc summary :colbert-index-id (:index-id colbert-result)
                       :colbert-fields (:colbert-fields colbert-result) :events []))
      (catch Exception e
        (lifecycle/record! ctx :evolutionary/build-failed
                           {:ontology-id ontology-id :build-id build-id
                            :error (or (.getMessage e) "Build failed") :failed-at-stage "source-workflow"
                            :failed-at (str (java.time.Instant/now))})
        (throw e)))))

(defn build-from-sources
  "ORC loads and extracts sources; the ontology service owns graph contributions."
  [ctx {:keys [sources config]}]
  (let [config (merge default-config config)
        requested-id (some-> (:ontology-id config) normalize-ontology-id)
        created (require-result!
                  (ontology/create-ontology! ctx
                    (cond-> {:name (or (:name config) "Source ontology") :scope :custom
                             :description "Ontology built from source contributions"
                             :base-uri (:base-uri config)
                             :predicate-policies (or (:predicate-policies config) {})}
                      requested-id (assoc :command/id requested-id))))]
    (run-source-workflow! ctx (:ontology-id created) sources config "batch")))

(defn evolve
  "Run new extraction work and submit it to an existing standalone ontology."
  [ctx {:keys [ontology-id sources config]}]
  (let [ontology-id (normalize-ontology-id ontology-id)]
    (when-not (ontology/ontology-exists? ctx ontology-id)
      (throw (ex-info "Ontology not found"
                      {:cognitect.anomalies/category :cognitect.anomalies/not-found
                       :cognitect.anomalies/message "Ontology not found" :ontology-id ontology-id})))
    (doseq [[predicate policy] (:predicate-policies config)
            :when (not (get-in (ontology/get-ontology ctx ontology-id) [:predicate-policies predicate]))]
      (require-result! (ontology/register-predicate! ctx ontology-id predicate policy)))
    (run-source-workflow! ctx ontology-id sources config "incremental")))

(defn get-build-result [ctx build-id]
  (first (into [] (event-store/read (:event-store ctx)
                                   {:tenant-id (:tenant-id ctx)
                                    :types #{:evolutionary/build-completed}
                                    :tags #{[:build build-id]}}))))

(defn get-ontology-ttl [ctx ontology-id]
  (:ttl-output (ontology/export-snapshot ctx ontology-id)))
