(ns ai.obney.orc.ontology.interface.evolutionary
  "Public API for the Evolutionary Ontology Builder.

   This namespace provides the public interface for building ontologies
   from multiple data sources with incremental evolution support.

   Quick Start:
   ```clojure
   (require '[ai.obney.orc.ontology.interface.evolutionary :as evolutionary])

   ;; Build from sources (batch mode)
   (def result
     (evolutionary/build-from-sources ctx
       {:sources [{:path \"data.csv\" :type \"csv\"}]
        :config {:base-uri \"http://example.org/\"}}))

   ;; Get TTL output
   (:ttl-output result)

   ;; Evolve with new sources (incremental)
   (evolutionary/evolve ctx
     {:ontology-id (:ontology-id result)
      :sources [{:path \"new-data.csv\" :type \"csv\"}]})
   ```

   Architecture:
   - Layer 1: Extraction (delegates to ORC sheets)
   - Layer 2: Entity Resolution (type-based blocking + semantic matching)
   - Layer 3: Graph Evolution (merge, deduplicate, generate TTL)

   All operations emit Grain events for full audit trail."
  (:require [ai.obney.orc.ontology.core.commands :as commands]
            [ai.obney.orc.ontology.core.logic :as logic]
            [ai.obney.orc.ontology.core.queries :as queries]
            [ai.obney.orc.ontology.core.read-models :as read-models]
            [ai.obney.orc.ontology.interface.schemas :as schemas]))

(defn build-from-sources
  "Build ontology from sources in BATCH mode.

   Builds a complete ontology from scratch:
   1. Registers all sources (with SHA-256 deduplication)
   2. Extracts concepts from each source
   3. Resolves entities across sources
   4. Merges into unified graph
   5. Generates TTL snapshot

   Args:
     ctx - Context map with :event-store
     params:
       :sources - Vector of source specs:
         [{:path \"file.csv\" :type \"csv\"}
          {:content \"text content\" :type \"text\"}
          {:path \"data.json\" :type \"json\"}]
       :config - Optional configuration:
         :base-uri - Base URI for generated entities (default: \"http://ontology.local/\")
         :similarity-threshold - Min similarity for semantic matching (default: 0.85)
         :emit-owl-sameAs? - Generate owl:sameAs triples (default: true)
         :include-metadata? - Include metadata in TTL (default: true)

   Returns:
     {:ontology-id uuid
      :build-id uuid
      :total-sources int
      :total-concepts int
      :total-triples int
      :entities-resolved int
      :resolution-stats {:exact-matches :semantic-matches :unmatched}
      :schema-extensions {:new-classes [...] :new-properties [...]}
      :ttl-output string
      :duration-ms int
      :events [...]} - Events to persist

   Example:
     (build-from-sources ctx
       {:sources [{:path \"occupations.csv\" :type \"csv\"}
                  {:path \"programs.csv\" :type \"csv\"}]
        :config {:base-uri \"http://bryc.ai/ontology#\"
                 :similarity-threshold 0.85}})"
  [ctx params]
  (commands/evolutionary-build-from-sources ctx params))

(defn evolve
  "Evolve existing ontology with new sources (INCREMENTAL mode).

   Extends an existing ontology:
   1. Loads existing state from events
   2. Registers new sources (skips duplicates via hash)
   3. Extracts with TBox context from existing ontology
   4. Resolves entities (incremental - prefers existing URIs)
   5. Merges into existing graph
   6. Generates new TTL snapshot

   Args:
     ctx - Context map with :event-store
     params:
       :ontology-id - UUID of existing ontology
       :sources - Vector of new source specs
       :config - Optional configuration (same as build-from-sources)

   Returns: Same as build-from-sources, plus:
     :new-sources-processed int - Number of new (non-duplicate) sources

   Example:
     (evolve ctx
       {:ontology-id #uuid \"12345678-...\"
        :sources [{:path \"new-data.csv\" :type \"csv\"}]
        :config {:similarity-threshold 0.9}})"
  [ctx params]
  (commands/evolutionary-evolve ctx params))

(defn register-source
  "Register a data source without extracting concepts.

   Useful for pre-registering sources or checking for duplicates.

   Args:
     ctx - Context with :event-store
     params:
       :source-uri - Path or URI of the source
       :source-type - Type: \"csv\", \"text\", \"json\", \"sql\", \"rdf\"
       :content - Optional direct content (for text/json)
       :namespace - Optional namespace prefix for URIs
       :metadata - Optional additional metadata

   Returns:
     {:source-id string
      :content-hash string (SHA-256)
      :already-registered? boolean
      :events [...]}

   Example:
     (register-source ctx
       {:source-uri \"data.csv\"
        :source-type \"csv\"
        :namespace \"http://example.org/data#\"})"
  {:arglists '([{:keys [event-store]} params])}
  [context params]
  (commands/register-source context params))

(defn check-source-processed
  "Check if a source has already been processed.

   Uses SHA-256 hash comparison to detect duplicates.

   Args:
     ctx - Context with :event-store
     source-uri - Path/URI to check (optional)
     content - Content to check (optional)

   Returns:
     {:processed? boolean
      :source-id string (if processed)
      :computed-hash string}

   Example:
     (check-source-processed ctx \"data.csv\" nil)
     (check-source-processed ctx nil \"inline content\")"
  {:arglists '([{:keys [event-store]} source-uri content])}
  [context source-uri content]
  (queries/check-source-processed context source-uri content))

(defn was-processed?
  "Simple check if content was already processed.

   Args:
     ctx - Context with :event-store
     source-uri-or-content - Path/URI or content string

   Returns: boolean"
  {:arglists '([{:keys [event-store]} source-uri-or-content])}
  [context source-uri-or-content]
  (queries/was-processed? context source-uri-or-content))

(defn resolve-entities
  "Resolve entities across concepts using type-based blocking.

   Standalone entity resolution for custom pipelines.

   Args:
     concepts - Vector of concept maps with:
       :uri :label :entity-type :source-id :embedding (optional)
     config:
       :similarity-threshold - Min similarity for semantic match (default: 0.85)
       :emit-owl-sameAs? - Generate alignment triples (default: true)
       :existing-uris - Set of existing URIs (for incremental mode)

   Returns:
     {:matches [{:source1-uri :source2-uri :similarity-score :match-type}]
      :canonical-map {uri -> canonical-uri}
      :alignment-triples [[s p o] ...]
      :exact-matches int
      :semantic-matches int
      :unmatched [concepts...]}

   Example:
     (resolve-entities
       [{:uri \"a\" :label \"Apple\" :entity-type \"Fruit\" :source-id \"s1\"}
        {:uri \"b\" :label \"apple\" :entity-type \"Fruit\" :source-id \"s2\"}]
       {:similarity-threshold 0.85})"
  [concepts config]
  (logic/resolve-within-batch concepts config))

(defn generate-ttl
  "Generate TTL from concepts and relationships.

   Standalone TTL generation for custom pipelines.

   Args:
     graph:
       :concepts {uri -> concept-map}
       :relationships [{:subject :predicate :object}]
     config:
       :base-uri - Base URI (default: \"http://ontology.local/\")
       :include-metadata? - Include generation metadata (default: true)

   Returns:
     {:ttl-string string
      :triple-count int
      :checksum string}

   Example:
     (generate-ttl
       {:concepts {\"ex:Apple\" {:uri \"ex:Apple\" :label \"Apple\"}}
        :relationships [{:subject \"ex:Apple\" :predicate \"rdf:type\" :object \"ex:Fruit\"}]}
       {:base-uri \"http://example.org/\"})"
  [graph config]
  (logic/generate-ttl-snapshot graph config))

(defn get-source
  "Get source entry by ID."
  [ctx source-id]
  (queries/get-source ctx source-id))

(defn get-all-sources
  "Get all registered sources.

   Args:
     ctx - Context with :event-store
     opts:
       :source-type - Filter by type (optional)

   Returns: Vector of source entries"
  {:arglists '([ctx & [opts]])}
  [ctx & options]
  (apply queries/get-all-sources ctx options))

(defn get-concepts
  "Get concepts extracted for an ontology.

   Args:
     ctx - Context with :event-store
     ontology-id - UUID of the ontology

   Returns: Vector of concept maps"
  [ctx ontology-id]
  (queries/evolutionary-get-concepts ctx ontology-id))

(defn get-concepts-by-source
  "Get concepts extracted from a specific source."
  [ctx source-id]
  (queries/get-concepts-by-source ctx source-id))

(defn get-concepts-by-type
  "Get concepts of a specific entity type."
  [ctx entity-type]
  (queries/get-concepts-by-type ctx entity-type))

(defn get-canonical-uri
  "Get canonical URI for a given URI."
  [ctx uri]
  (queries/get-canonical-uri ctx uri))

(defn get-all-canonical-mappings
  "Get all canonical URI mappings."
  [ctx]
  (queries/get-all-canonical-mappings ctx))

(defn get-evolution-state
  "Get current evolution state for an ontology."
  [ctx ontology-id]
  (queries/get-evolution-state ctx ontology-id))

(defn get-build-history
  "Get build history for an ontology.

   Args:
     ctx - Context with :event-store
     ontology-id - UUID of the ontology
     opts:
       :limit - Max results (default: 10)

   Returns: Vector of build completion records"
  {:arglists '([ctx ontology-id & [opts]])}
  [ctx ontology-id & options]
  (apply queries/get-build-history ctx ontology-id options))

(defn get-statistics
  "Get comprehensive statistics about the evolutionary ontology."
  [ctx ontology-id]
  (queries/get-statistics ctx ontology-id))

(defn compute-content-hash
  "Compute SHA-256 hash of content.

   Useful for pre-checking if content was already processed.

   Args:
     content - String content to hash

   Returns: SHA-256 hex string"
  [content]
  (logic/sha256 content))

(defn normalize-label
  "Normalize a label for matching.

   Applies same normalization as entity resolver:
   lowercase, trim, replace - and _ with space.

   Example:
     (normalize-label \"Chief-Executive_Officer\")
     ;; => \"chief executive officer\""
  [label]
  (logic/normalize-label label))

(defn build-from-sources-async!
  "Start background evolutionary build. Returns build-id immediately.

   Use `get-build-progress` to check status.
   Use `await-build!` to block until completion.

   Args:
     ctx - Context map with :event-store
     params - Same as build-from-sources
     opts:
       :on-progress - callback fn called with progress map (not yet implemented)
       :on-complete - callback fn called with result on success
       :on-error - callback fn called with exception on failure

   Returns:
     build-id (UUID) - Use to check progress or await completion

   Example:
     (def build-id (build-from-sources-async! ctx
                     {:sources [{:content data :type \"csv\"}]
                      :config {:base-uri \"http://example.org/\"}}
                     {:on-complete (fn [result] (println \"Done!\" (:total-concepts result)))}))
     (get-build-progress build-id)
     (await-build! build-id)"
  {:arglists '([ctx params & [{:keys [on-progress on-complete on-error]}]])}
  [ctx params & options]
  (apply commands/build-from-sources-async! ctx params options))

(defn get-build-progress
  "Get progress of a background build.

   Args:
     build-id - UUID returned from build-from-sources-async!

   Returns:
     {:status :running/:completed/:failed
      :started-at timestamp-ms
      :progress 0-100 (when available)
      :result build-result (when completed)
      :error string (when failed)
      :duration-ms int}"
  [build-id]
  (logic/get-build-progress build-id))

(defn await-build!
  "Block until build completes. Returns result or throws on failure.

   Args:
     build-id - UUID from build-from-sources-async!
     opts:
       :timeout-ms - Max wait time (default: 600000 = 10 minutes)
       :poll-interval-ms - Check interval (default: 1000)

   Returns:
     Build result map (same as build-from-sources)

   Throws:
     ExceptionInfo if build failed or timed out"
  {:arglists '([build-id & [{:keys [timeout-ms poll-interval-ms]
                :or {timeout-ms 600000
                     poll-interval-ms 1000}}]])}
  [build-id & options]
  (apply logic/await-build! build-id options))

(defn list-builds
  "List all tracked builds.

   Args:
     opts:
       :status - Filter by status (:running, :completed, :failed)
       :limit - Max results (default: 100)

   Returns:
     Vector of [build-id build-info] pairs"
  {:arglists '([& [{:keys [status limit] :or {limit 100}}]])}
  [& options]
  (apply logic/list-builds options))

(defn clear-build-registry!
  "Clear completed/failed builds from registry.
   Running builds are preserved.

   Returns: Number of builds cleared"
  []
  (logic/clear-build-registry!))
