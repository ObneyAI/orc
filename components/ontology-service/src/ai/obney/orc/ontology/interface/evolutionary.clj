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
  (:require [ai.obney.ontology.interface :as ontology-lib]
            [ai.obney.ontology.core.source-registry :as sources]
            [ai.obney.ontology.core.entity-resolver :as entity-resolver]
            [ai.obney.orc.ontology.core.commands :as commands]
            [ai.obney.orc.ontology.core.logic :as logic]
            [ai.obney.orc.ontology.core.queries :as queries]
            [ai.obney.orc.ontology.core.read-models :as read-models]
            [ai.obney.orc.ontology.interface.schemas :as schemas]))

(defn build-from-sources
  [ctx params]
  (commands/evolutionary-build-from-sources ctx params))

(defn evolve
  [ctx params]
  (commands/evolutionary-evolve ctx params))

(defn check-source-processed
  [ctx ontology-id source-uri content]
  (logic/check-source-processed ctx ontology-id source-uri content))

(defn was-processed?
  [ctx ontology-id source-uri content]
  (logic/was-processed? ctx ontology-id source-uri content))

(defn generate-ttl [ctx ontology-id] (ontology-lib/export-snapshot ctx ontology-id))

(defn get-source
  [ctx ontology-id source-id]
  (logic/get-source ctx ontology-id source-id))

(defn get-all-sources
  {:arglists '([ctx ontology-id & [opts]])}
  [ctx ontology-id & options]
  (apply logic/get-all-sources ctx ontology-id options))

(defn get-concepts
  [ctx ontology-id]
  (logic/evolutionary-get-concepts ctx ontology-id))

(defn get-concepts-by-source
  [ctx ontology-id source-id]
  (logic/get-concepts-by-source ctx ontology-id source-id))

(defn get-concepts-by-type
  [ctx ontology-id entity-type]
  (logic/get-concepts-by-type ctx ontology-id entity-type))

(defn get-canonical-uri
  [ctx ontology-id uri]
  (logic/get-canonical-uri ctx ontology-id uri))

(defn get-all-canonical-mappings
  [ctx ontology-id]
  (ontology-lib/get-canonical-mappings ctx ontology-id))

(defn get-evolution-state
  [ctx ontology-id]
  (ontology-lib/export-snapshot ctx ontology-id))

(defn get-build-history
  {:arglists '([ctx ontology-id & [opts]])}
  [ctx ontology-id & options]
  (apply queries/get-build-history ctx ontology-id options))

(defn get-statistics
  [ctx ontology-id]
  (queries/get-statistics ctx ontology-id))

(def compute-content-hash sources/sha256)

(def normalize-label entity-resolver/normalize-label)

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
