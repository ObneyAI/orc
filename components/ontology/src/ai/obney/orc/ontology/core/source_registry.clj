(ns ai.obney.orc.ontology.core.source-registry
  "Source Registry - Tracks processed data sources with SHA-256 deduplication.

   This is the first layer of the evolutionary ontology builder, responsible for:
   1. Registering new sources with content hashing
   2. Detecting duplicate sources by hash
   3. Maintaining source metadata and processing stats

   1:1 Python parity with source_registry.py from evolutionary_ontology_builder."
  (:require [clojure.string :as str]
            [ai.obney.grain.event-store-v3.interface :refer [->event]])
  (:import [java.security MessageDigest]
           [java.io File]
           [java.nio.file Files Paths]))

;; =============================================================================
;; SHA-256 Hashing (matches Python hashlib.sha256)
;; =============================================================================

(defn sha256
  "Compute SHA-256 hash of a string.
   Returns hex string, matches Python: hashlib.sha256(content.encode()).hexdigest()"
  [^String content]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.digest digest (.getBytes content "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))

(defn compute-content-hash
  "Compute content hash for a source.
   For file paths, reads the file content.
   For direct content, hashes the content string."
  [source-uri content]
  (cond
    ;; Direct content provided
    (and content (string? content))
    (sha256 content)

    ;; File path - read and hash
    (and source-uri (.exists (File. ^String source-uri)))
    (sha256 (slurp source-uri))

    ;; URL or non-file path - hash the URI itself
    :else
    (sha256 (or source-uri ""))))

(defn get-file-size
  "Get file size in bytes, or content length for strings."
  [source-uri content]
  (cond
    (and content (string? content))
    (count (.getBytes ^String content "UTF-8"))

    (and source-uri (.exists (File. ^String source-uri)))
    (.length (File. ^String source-uri))

    :else
    0))

;; =============================================================================
;; Source ID Generation
;; =============================================================================

(defn generate-source-id
  "Generate a UUID source ID.
   Note: We use UUIDs because grain event tags require UUID values."
  [_content-hash]
  (java.util.UUID/randomUUID))

;; =============================================================================
;; Source Type Detection
;; =============================================================================

(defn detect-source-type
  "Auto-detect source type from URI/path.
   Matches Python's _detect_source_type method."
  [source-uri]
  (when source-uri
    (let [lower-uri (str/lower-case source-uri)]
      (cond
        (str/ends-with? lower-uri ".csv") "csv"
        (str/ends-with? lower-uri ".json") "json"
        (str/ends-with? lower-uri ".ttl") "rdf"
        (str/ends-with? lower-uri ".rdf") "rdf"
        (str/ends-with? lower-uri ".owl") "rdf"
        (str/ends-with? lower-uri ".txt") "text"
        (str/ends-with? lower-uri ".md") "text"
        (str/starts-with? lower-uri "jdbc:") "sql"
        (str/starts-with? lower-uri "postgresql:") "sql"
        (str/starts-with? lower-uri "mysql:") "sql"
        :else "text"))))

;; =============================================================================
;; Source Registration Logic
;; =============================================================================

(defn check-source-processed
  "Check if a source has already been processed.
   Returns {:processed? bool, :source-id string} or {:processed? false}.

   Checks by:
   1. Content hash (if content provided)
   2. Source URI (if path provided)"
  [{:keys [source-registry content-hash-index]} source-uri content]
  (let [hash (compute-content-hash source-uri content)]
    (if-let [existing-source-id (get content-hash-index hash)]
      {:processed? true
       :source-id existing-source-id
       :existing-source (get source-registry existing-source-id)}
      {:processed? false
       :computed-hash hash})))

(defn register-source!
  "Register a new data source for processing.

   Args:
     source-uri - Path or URI of the source
     source-type - Type of source (csv, sql, text, rdf, json) or auto-detect
     content - Optional direct content (for text/json sources)
     namespace - Optional namespace prefix for generated URIs
     metadata - Optional additional metadata

   Returns:
     {:source-id string
      :content-hash string
      :already-registered? bool
      :events [...]} - events to emit

   Matches Python: SourceRegistry.register_source()"
  [{:keys [source-registry content-hash-index] :as read-models}
   {:keys [source-uri source-type content namespace metadata]}]
  (let [;; Compute hash and check for duplicates
        content-hash (compute-content-hash source-uri content)
        existing-source-id (get content-hash-index content-hash)

        ;; Detect source type if not provided
        detected-type (or source-type (detect-source-type source-uri) "text")

        ;; Compute file size
        file-size (get-file-size source-uri content)

        ;; Generate source ID
        source-id (or existing-source-id (generate-source-id content-hash))

        ;; Determine namespace (default to URI-based)
        ns-prefix (or namespace
                      (when source-uri
                        (str "http://ontology.local/"
                             (-> source-uri
                                 (str/replace #"[^a-zA-Z0-9]" "_")
                                 (str/lower-case))
                             "#"))
                      "http://ontology.local/source#")]

    (if existing-source-id
      ;; Already registered - return existing info
      {:source-id existing-source-id
       :content-hash content-hash
       :already-registered? true
       :existing-source (get source-registry existing-source-id)
       :events []}

      ;; New source - return registration event
      {:source-id source-id
       :content-hash content-hash
       :already-registered? false
       :events [(->event {:type :evolutionary/source-registered
                          :tags #{[:source source-id]}
                          :body {:source-id source-id  ;; Keep as UUID - schema expects :uuid
                                 :source-uri (or source-uri "inline-content")
                                 :source-type detected-type
                                 :content-hash content-hash
                                 :file-size file-size
                                 :namespace ns-prefix
                                 :metadata (or metadata {})  ;; Always pass map, not nil
                                 :registered-at (str (java.time.Instant/now))}})]})))

(defn update-source-stats!
  "Update processing statistics for a source after extraction.

   source-id must be a UUID.
   Returns event to emit."
  [source-id concepts-extracted triples-generated entities-resolved]
  {:pre [(uuid? source-id)]}
  (->event {:type :evolutionary/source-stats-updated
            :tags #{[:source source-id]}
            :body {:source-id source-id  ;; Keep as UUID
                   :concepts-extracted concepts-extracted
                   :triples-generated triples-generated
                   :entities-resolved entities-resolved
                   :updated-at (str (java.time.Instant/now))}}))

;; =============================================================================
;; Read Model Projections
;; =============================================================================

(defn source-registry-projection
  "Reduce function for source-registry read model.
   State: {source-id -> source-entry}"
  [state event]
  (case (or (:event/type event) (:type event))
    :evolutionary/source-registered
    (let [{:keys [source-id] :as body} (or (:body event) event)]
      (assoc state source-id body))

    :evolutionary/source-stats-updated
    (let [{:keys [source-id concepts-extracted triples-generated entities-resolved]}
          (or (:body event) event)]
      (update state source-id merge
              {:concepts-extracted concepts-extracted
               :triples-generated triples-generated
               :entities-resolved entities-resolved}))

    ;; Default - unchanged
    state))

(defn content-hash-index-projection
  "Reduce function for content-hash-index read model.
   State: {content-hash -> source-id}"
  [state event]
  (case (or (:event/type event) (:type event))
    :evolutionary/source-registered
    (let [{:keys [content-hash source-id]} (or (:body event) event)]
      (assoc state content-hash source-id))

    ;; Default - unchanged
    state))

(defn build-read-models
  "Build source registry read models from events."
  [events]
  {:source-registry (reduce source-registry-projection {} events)
   :content-hash-index (reduce content-hash-index-projection {} events)})

;; =============================================================================
;; Query Functions
;; =============================================================================

(defn get-source
  "Get source entry by ID."
  [source-registry source-id]
  (get source-registry source-id))

(defn get-source-by-hash
  "Get source by content hash."
  [{:keys [source-registry content-hash-index]} content-hash]
  (when-let [source-id (get content-hash-index content-hash)]
    (get source-registry source-id)))

(defn was-processed?
  "Check if content was already processed."
  [read-models source-uri content]
  (:processed? (check-source-processed read-models source-uri content)))

(defn all-sources
  "Get all registered sources, optionally filtered by type."
  [source-registry & {:keys [source-type]}]
  (let [sources (vals source-registry)]
    (if source-type
      (filter #(= source-type (:source-type %)) sources)
      sources)))

(defn sources-by-type
  "Group sources by type."
  [source-registry]
  (group-by :source-type (vals source-registry)))

(comment
  ;; Example usage

  ;; Register a CSV source
  (register-source! {:source-registry {}
                     :content-hash-index {}}
                    {:source-uri "/path/to/data.csv"
                     :source-type "csv"
                     :namespace "http://example.org/data#"})

  ;; Register inline text
  (register-source! {:source-registry {}
                     :content-hash-index {}}
                    {:content "Some text to process"
                     :source-type "text"})

  ;; Check if processed
  (was-processed? {:source-registry {}
                   :content-hash-index {"abc123" "source-abc123"}}
                  nil
                  "same content that produces abc123 hash")

  ,)
