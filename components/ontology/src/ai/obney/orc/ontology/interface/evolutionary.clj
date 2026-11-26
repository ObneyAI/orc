(ns ai.obney.orc.ontology.interface.evolutionary
  "ORC extraction workflows and scoped queries over the standalone ontology API."
  (:require [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.evolutionary-commands]
            [ai.obney.orc.ontology.core.source-registry :as source-registry]
            [ai.obney.ontology.interface :as ontology]
            [ai.obney.ontology.core.source-registry :as sources]
            [ai.obney.orc.ontology.core.entity-resolver :as resolver]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.time.interface :as time]))

(defn- dispatch! [ctx command-name params]
  (let [result (cp/process-command
                (assoc ctx :command (merge {:command/id (random-uuid)
                                            :command/timestamp (time/now)
                                            :command/name command-name} params)))]
    (if (:cognitect.anomalies/category result) result
        (assoc (:command-result/data result) :events (:command-result/events result)))))

(defn build-from-sources [ctx params] (dispatch! ctx :ontology/build-from-sources params))
(defn evolve [ctx params] (dispatch! ctx :ontology/evolve params))

(defn check-source-processed [ctx ontology-id source-uri content]
  (source-registry/check-source-processed ctx ontology-id source-uri content))
(defn was-processed? [ctx ontology-id source-uri content]
  (:processed? (check-source-processed ctx ontology-id source-uri content)))
(defn get-source [ctx ontology-id source-id]
  (source-registry/get-source ctx ontology-id source-id))
(defn get-all-sources [ctx ontology-id & [opts]]
  (cond->> (source-registry/sources ctx ontology-id)
    (:source-type opts) (filterv #(= (:source-type opts) (:type %)))))
(defn get-concepts [ctx ontology-id] (ontology/get-concepts ctx {:ontology-id ontology-id}))
(defn- query-result [result f]
  (if (:cognitect.anomalies/category result) result (f result)))

(defn get-concepts-by-source [ctx ontology-id source-id]
  (query-result (get-concepts ctx ontology-id)
                (fn [concepts] (filterv #(= (str source-id) (get-in % [:provenance :source-reference])) concepts))))
(defn get-concepts-by-type [ctx ontology-id entity-type]
  (query-result (get-concepts ctx ontology-id)
                (fn [concepts] (filterv #(= entity-type (:entity-type %)) concepts))))
(defn get-canonical-uri [ctx ontology-id uri]
  (query-result (ontology/get-canonical-mappings ctx ontology-id) #(get % uri)))
(defn get-all-canonical-mappings [ctx ontology-id] (ontology/get-canonical-mappings ctx ontology-id))
(defn get-evolution-state [ctx ontology-id] (ontology/export-snapshot ctx ontology-id))
(defn generate-ttl [ctx ontology-id] (ontology/export-snapshot ctx ontology-id))

(defn get-build-history [ctx ontology-id & [opts]]
  (when-not (:tenant-id ctx) (throw (ex-info "Build history requires tenant-id" {})))
  (->> (event-store/read (:event-store ctx)
                        {:tenant-id (:tenant-id ctx) :types #{:evolutionary/build-completed :evolutionary/build-failed}
                         :tags #{[:ontology ontology-id]}})
       (into []) (take-last (or (:limit opts) 10)) vec))
(defn get-statistics [ctx ontology-id]
  (query-result (get-concepts ctx ontology-id)
                (fn [concepts]
                  (let [history (get-build-history ctx ontology-id)]
                    {:ontology-id ontology-id :build-count (count history)
                     :concept-count (count concepts) :latest-build (last history)}))))
(def compute-content-hash sources/sha256)
(def normalize-label resolver/normalize-label)

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
              _ (when (:cognitect.anomalies/category result)
                  (throw (ex-info (:cognitect.anomalies/message result) result)))
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
