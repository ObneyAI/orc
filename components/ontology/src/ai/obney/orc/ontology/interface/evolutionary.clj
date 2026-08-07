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
  (:require [ai.obney.orc.ontology.interface.schemas] ;; CRITICAL: Register evolutionary event schemas
            [ai.obney.orc.ontology.core.evolutionary-builder :as builder]
            [ai.obney.orc.ontology.core.evolutionary-commands] ;; Register defcommand handlers
            [ai.obney.orc.ontology.core.source-registry :as source-registry]
            [ai.obney.orc.ontology.core.entity-resolver :as entity-resolver]
            [ai.obney.orc.ontology.core.graph-evolver :as graph-evolver]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Build Operations
;; =============================================================================

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
  (let [result (cp/process-command
                (assoc ctx :command
                  (merge {:command/id (random-uuid)
                          :command/timestamp (time/now)
                          :command/name :ontology/build-from-sources}
                         params)))]
    ;; Preserve Grain anomalies. Merging nil :command/result used to turn a
    ;; rejected live build into the misleading value {:events nil}, erasing
    ;; the provider/schema failure that operators and acceptance tests need.
    (if (:cognitect.anomalies/category result)
      result
      (merge (:command/result result)
             {:events (:command-result/events result)}))))

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
  (let [result (cp/process-command
                (assoc ctx :command
                  (merge {:command/id (random-uuid)
                          :command/timestamp (time/now)
                          :command/name :ontology/evolve}
                         params)))]
    (if (:cognitect.anomalies/category result)
      result
      (merge (:command/result result)
             {:events (:command-result/events result)}))))

;; =============================================================================
;; Source Registry Operations
;; =============================================================================

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
  [{:keys [event-store]} params]
  (let [events (event-store/read event-store
                                 {:types #{:evolutionary/source-registered}})
        read-models (source-registry/build-read-models events)]
    (source-registry/register-source! read-models params)))

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
  [{:keys [event-store]} source-uri content]
  (let [events (event-store/read event-store
                                 {:types #{:evolutionary/source-registered}})
        read-models (source-registry/build-read-models events)]
    (source-registry/check-source-processed read-models source-uri content)))

(defn was-processed?
  "Simple check if content was already processed.

   Args:
     ctx - Context with :event-store
     source-uri-or-content - Path/URI or content string

   Returns: boolean"
  [{:keys [event-store]} source-uri-or-content]
  (let [events (event-store/read event-store
                                 {:types #{:evolutionary/source-registered}})
        read-models (source-registry/build-read-models events)]
    (source-registry/was-processed? read-models
                                    (when (and source-uri-or-content
                                               (.exists (java.io.File. source-uri-or-content)))
                                      source-uri-or-content)
                                    (when (and source-uri-or-content
                                               (not (.exists (java.io.File. source-uri-or-content))))
                                      source-uri-or-content))))

;; =============================================================================
;; Entity Resolution Operations
;; =============================================================================

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
  (entity-resolver/resolve-within-batch concepts config))

;; =============================================================================
;; Graph Operations
;; =============================================================================

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
  (graph-evolver/generate-ttl-snapshot graph config))

;; =============================================================================
;; Query Operations
;; =============================================================================

(defn- read-events
  "Read immutable evolutionary evidence within the caller's tenant. Public
  evolutionary queries must never silently widen a tenant-scoped context to
  the whole shared event store."
  ([{:keys [event-store tenant-id]} types]
   (into [] (event-store/read event-store
                              (cond-> {:types types}
                                tenant-id (assoc :tenant-id tenant-id)))))
  ([{:keys [event-store tenant-id]} types tags]
   (into [] (event-store/read event-store
                              (cond-> {:types types :tags tags}
                                tenant-id (assoc :tenant-id tenant-id))))))

(defn- body [event] (or (:body event) event))

(defn get-source
  "Get source entry by ID."
  [ctx source-id]
  (-> (read-events ctx #{:evolutionary/source-registered})
      source-registry/build-read-models
      :source-registry
      (source-registry/get-source source-id)))

(defn get-all-sources
  "Get all registered sources.

   Args:
     ctx - Context with :event-store
     opts:
       :source-type - Filter by type (optional)

   Returns: Vector of source entries"
  [ctx & [opts]]
  (let [registry (-> (read-events ctx #{:evolutionary/source-registered})
                     source-registry/build-read-models
                     :source-registry)]
    (vec (source-registry/all-sources registry
                                     :source-type (:source-type opts)))))

(defn get-concepts
  "Get concepts extracted for an ontology.

   Args:
     ctx - Context with :event-store
     ontology-id - UUID of the ontology

   Returns: Vector of concept maps"
  [ctx ontology-id]
  (->> (read-events ctx #{:evolutionary/concepts-extracted}
                    #{[:ontology ontology-id]})
       (mapcat (comp :concepts body))
       vec))

(defn get-concepts-by-source
  "Get concepts extracted from a specific source."
  [ctx source-id]
  (->> (read-events ctx #{:evolutionary/concepts-extracted}
                    #{[:source source-id]})
       (mapcat (comp :concepts body))
       vec))

(defn get-concepts-by-type
  "Get concepts of a specific entity type."
  [ctx entity-type]
  (->> (read-events ctx #{:evolutionary/concepts-extracted})
       (mapcat (comp :concepts body))
       (filterv #(= entity-type (:entity-type %)))))

(defn get-canonical-uri
  "Get canonical URI for a given URI."
  [ctx uri]
  (get (reduce entity-resolver/canonical-uri-map-projection {}
               (read-events ctx #{:evolutionary/entities-resolved
                                  :evolutionary/canonical-uri-assigned}))
       uri))

(defn get-all-canonical-mappings
  "Get all canonical URI mappings."
  [ctx]
  (reduce entity-resolver/canonical-uri-map-projection {}
          (read-events ctx #{:evolutionary/entities-resolved
                             :evolutionary/canonical-uri-assigned})))

(defn get-evolution-state
  "Get current evolution state for an ontology."
  [ctx ontology-id]
  (some-> (read-events ctx #{:evolutionary/graph-merged}
                       #{[:ontology ontology-id]})
          last body))

(defn get-build-history
  "Get build history for an ontology.

   Args:
     ctx - Context with :event-store
     ontology-id - UUID of the ontology
     opts:
       :limit - Max results (default: 10)

   Returns: Vector of build completion records"
  [ctx ontology-id & [opts]]
  (let [limit (or (:limit opts) 10)]
    (->> (read-events ctx #{:evolutionary/build-completed}
                      #{[:ontology ontology-id]})
         (take-last limit)
         (mapv body))))

(defn get-statistics
  "Get comprehensive statistics about the evolutionary ontology."
  [ctx ontology-id]
  (let [history (get-build-history ctx ontology-id)
        concepts (get-concepts ctx ontology-id)]
    {:ontology-id ontology-id
     :build-count (count history)
     :concept-count (count concepts)
     :latest-build (last history)}))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn compute-content-hash
  "Compute SHA-256 hash of content.

   Useful for pre-checking if content was already processed.

   Args:
     content - String content to hash

   Returns: SHA-256 hex string"
  [content]
  (source-registry/sha256 content))

(defn normalize-label
  "Normalize a label for matching.

   Applies same normalization as entity resolver:
   lowercase, trim, replace - and _ with space.

   Example:
     (normalize-label \"Chief-Executive_Officer\")
     ;; => \"chief executive officer\""
  [label]
  (entity-resolver/normalize-label label))

;; =============================================================================
;; Async Build Operations
;; =============================================================================

;; Registry for tracking background builds.
;; {build-id -> {:status :started-at :progress :result :error :duration-ms}}
(defonce ^:private build-registry (atom {}))

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
  [ctx params & [{:keys [on-progress on-complete on-error]}]]
  (let [build-id (java.util.UUID/randomUUID)
        start-time (System/currentTimeMillis)]

    ;; Register build as running
    (swap! build-registry assoc build-id
           {:status :running
            :started-at start-time
            :progress 0
            :params params})

    ;; Run in future
    (future
      (try
        (println "[async-build]" build-id "Starting build...")
        (let [result (build-from-sources ctx params)
              duration-ms (- (System/currentTimeMillis) start-time)]
          (swap! build-registry assoc build-id
                 {:status :completed
                  :result result
                  :duration-ms duration-ms
                  :completed-at (System/currentTimeMillis)})
          (println "[async-build]" build-id "Completed in" duration-ms "ms")
          (println "[async-build]" build-id "Total concepts:" (:total-concepts result))
          (when on-complete (on-complete result))
          result)
        (catch Exception e
          (let [duration-ms (- (System/currentTimeMillis) start-time)]
            (swap! build-registry assoc build-id
                   {:status :failed
                    :error (.getMessage e)
                    :exception e
                    :duration-ms duration-ms
                    :failed-at (System/currentTimeMillis)})
            (println "[async-build]" build-id "FAILED after" duration-ms "ms:" (.getMessage e))
            (when on-error (on-error e))))))

    build-id))

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
  (get @build-registry build-id))

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
  [build-id & [{:keys [timeout-ms poll-interval-ms]
                :or {timeout-ms 600000
                     poll-interval-ms 1000}}]]
  (let [start (System/currentTimeMillis)]
    (loop []
      (let [{:keys [status result error exception]} (get-build-progress build-id)]
        (case status
          :completed result
          :failed (throw (ex-info (str "Build failed: " error)
                                  {:build-id build-id :error error}
                                  exception))
          :running (if (> (- (System/currentTimeMillis) start) timeout-ms)
                     (throw (ex-info "Build timed out"
                                     {:build-id build-id
                                      :timeout-ms timeout-ms}))
                     (do (Thread/sleep poll-interval-ms) (recur)))
          (throw (ex-info "Build not found" {:build-id build-id})))))))

(defn list-builds
  "List all tracked builds.

   Args:
     opts:
       :status - Filter by status (:running, :completed, :failed)
       :limit - Max results (default: 100)

   Returns:
     Vector of [build-id build-info] pairs"
  [& [{:keys [status limit] :or {limit 100}}]]
  (let [all-builds @build-registry
        filtered (if status
                   (filter (fn [[_ info]] (= status (:status info))) all-builds)
                   all-builds)]
    (->> filtered
         (sort-by (fn [[_ info]] (or (:started-at info) 0)) >)
         (take limit)
         vec)))

(defn clear-build-registry!
  "Clear completed/failed builds from registry.
   Running builds are preserved.

   Returns: Number of builds cleared"
  []
  (let [before (count @build-registry)]
    (swap! build-registry
           (fn [reg]
             (into {}
                   (filter (fn [[_ info]] (= :running (:status info)))
                           reg))))
    (- before (count @build-registry))))

(comment
  ;; === Complete Example Workflow ===

  ;; 1. Build initial ontology from CSV
  (def result1
    (build-from-sources ctx
      {:sources [{:path "occupations.csv" :type "csv"}]
       :config {:base-uri "http://bryc.ai/"}}))

  ;; 2. Check what was built
  (get-statistics ctx (:ontology-id result1))
  ;; => {:total-sources 1 :total-concepts 50 ...}

  ;; 3. Get the TTL output
  (spit "ontology.ttl" (:ttl-output result1))

  ;; 4. Evolve with new data
  (def result2
    (evolve ctx
      {:ontology-id (:ontology-id result1)
       :sources [{:path "programs.csv" :type "csv"}]}))

  ;; 5. Check resolution
  (get-all-canonical-mappings ctx)
  ;; => {"prog:CEO" "onet:11-1011" ...}

  ;; 6. Query by entity type
  (get-concepts-by-type ctx "Occupation")
  ;; => [{:uri "onet:11-1011" :label "Chief Executives" ...} ...]

  ;; 7. Check build history
  (get-build-history ctx (:ontology-id result1))
  ;; => [{:build-id ... :total-concepts 100 :completed-at ...} ...]

  ,)
