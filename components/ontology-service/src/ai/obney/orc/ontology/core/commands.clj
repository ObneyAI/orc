(ns ai.obney.orc.ontology.core.commands
  "Commands for the event-sourced ontology."
  (:require [com.brunobonacci.mulog :as u]
            [clojure.string :as str]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.ontology.core.logic :as logic]
            [ai.obney.orc.ontology.core.queries :as queries]
            [ai.obney.orc.ontology.core.read-models :as read-models]))

;; --- Public operations ---

(defn- process-ontology-command [ctx command-name params]
  (let [command-id (or (:command/id params) (random-uuid))
        result (cp/process-command
                (assoc ctx :command
                       (merge {:command/id command-id
                               :command/timestamp (or (:command/timestamp params) (time/now))
                               :command/name command-name}
                              (dissoc params :command/id :command/timestamp))))]
    (if (:cognitect.anomalies/category result)
      result
      (merge (:command-result/data result)
             {:events (:command-result/events result)}))))

(defn create-ontology! [ctx params]
  (process-ontology-command ctx :ontology/create-ontology params))

(defn create-concept! [ctx ontology-id concept]
  (process-ontology-command ctx :ontology/create-concept
                            (assoc concept :ontology-id ontology-id)))

(defn update-concept!
  ([ctx ontology-id concept-id changes]
   (update-concept! ctx ontology-id concept-id changes nil))
  ([ctx ontology-id concept-id changes command-meta]
   (process-ontology-command ctx :ontology/update-concept
                             (merge {:ontology-id ontology-id
                                     :concept-id concept-id
                                     :changes changes}
                                    command-meta))))

(defn create-relationship! [ctx relationship]
  (process-ontology-command ctx :ontology/create-relationship relationship))

(defn record-action-outcome
  "Record the outcome of an action for future learning.

   This is the general-purpose recording function that can be used by
   any code executor to record outcomes. It wraps the existing ontology
   commands with convenient defaults.

   Args:
     ctx: Context with event-store
     outcome-map:
       :tree-id - UUID of the behavior tree (required)
       :domain - Domain/site where action occurred (e.g., 'rent.com')
       :action-type - Keyword like :click, :scroll, :navigate, :fill
       :action-target - The target of the action (selector, URL, etc.)
       :success - Boolean indicating if action succeeded
       :outcome-type - Keyword like :success, :failure, :bot-detection, :redirect, :timeout
       :error-message - Optional error message
       :redirected-to - Optional URL if redirected
       :browser-mode - Optional :headed or :headless
       :page-url - Optional current page URL

   Returns:
     Command result map

   Example:
     (record-action-outcome ctx
       {:tree-id sheet-id
        :domain \"rent.com\"
        :action-type :click
        :action-target \"@e123\"
        :success false
        :outcome-type :bot-detection
        :redirected-to \"ratelimited.rent.com\"})"
  [ctx {:keys [tree-id domain action-type action-target success
               outcome-type error-message redirected-to
               browser-mode page-url]
        :as outcome-map}]


  (let [process-fn #'cp/process-command
        now-fn #'time/now
        domain-type "browser-automation"

        context-conditions (cond-> {:domain domain
                                    :action-type (name action-type)}
                             browser-mode (assoc :browser-mode (name browser-mode))
                             page-url (assoc :page-url page-url))

        action-desc {:type (name action-type)
                     :target action-target
                     :reason (str "executing " (name action-type) " on " domain)}]

    (if success
      (process-fn
       (assoc ctx :command
              {:command/id (random-uuid)
               :command/timestamp (now-fn)
               :command/name :ontology/record-tree-strength
               :tree-id tree-id
               :pattern-uri (str "success:" (name action-type) "-on-" domain)
               :confidence 0.8
               :evidence-trace-ids []
               :avg-score 1.0
               :context-conditions context-conditions
               :action-taken action-desc
               :domain-type domain-type
               :expected-outcome (str (name action-type) " completed successfully")}))

      (process-fn
       (assoc ctx :command
              {:command/id (random-uuid)
               :command/timestamp (now-fn)
               :command/name :ontology/record-tree-weakness
               :tree-id tree-id
               :failure-uri (str "failure:" (name (or outcome-type :action-failed)))
               :frequency 1.0
               :severity :medium
               :triggers [(str "action:" (name action-type))]
               :evidence-trace-ids []
               :failure-context (cond-> context-conditions
                                  redirected-to (assoc :redirected-to redirected-to)
                                  error-message (assoc :error-message error-message)
                                  outcome-type (assoc :outcome-type (name outcome-type)))
               :attempted-action action-desc
               :domain-type domain-type})))))

;; --- Evolutionary builder ---

(defn extract-from-all-sources
  "Extract concepts from all registered sources.

   Args:
     ctx - Context with :event-store (needed for ORC sheet execution)
     registered-sources - Vector of registration results
     config - Configuration options"
  [ctx registered-sources config]
  (reduce (fn [{:keys [all-concepts all-relationships all-tbox all-abox events]} reg]
            (let [{:keys [source-id loaded-content source db-path]} reg
                  source-config (if db-path
                                  (assoc config :db-path db-path :path (:path source))
                                  config)
                  extraction (logic/extract-from-source
                               ctx
                               source-id
                               (:type source)
                               loaded-content
                               source-config)
                  model-calls (when-let [trace-id (:trace-id extraction)]
                                (->> (es/read
                                      (:event-store ctx)
                                      {:tenant-id (:tenant-id ctx)
                                       :types #{:sheet/node-execution-completed}})
                                     (into [])
                                     (filter #(and (= trace-id (:tick-id %)) (:model %)))
                                     vec))
                  model-provenance (when (seq model-calls)
                                     {:trace-id (:trace-id extraction)
                                      :models (vec (distinct (map :model model-calls)))
                                      :calls (mapv #(select-keys % [:node-id :model :usage])
                                                   model-calls)})
                  tagged-concepts (mapv #(if (:source-id %)
                                           %
                                           (assoc % :source-id source-id))
                                        (:concepts extraction))

                  tbox (:tbox extraction)
                  abox (:abox extraction)

                  base-events [(es/->event {:type :evolutionary/concepts-extracted
                                         :tags #{[:source source-id]
                                                 [:ontology (:ontology-id config)]}
                                         :body {:source-id source-id
                                                :ontology-id (:ontology-id config)
                                                :concepts tagged-concepts
                                                :model-provenance model-provenance
                                                :extracted-at (str (java.time.Instant/now))}})
                               (es/->event {:type :evolutionary/relationships-extracted
                                         :tags #{[:source source-id]
                                                 [:ontology (:ontology-id config)]}
                                         :body {:source-id source-id
                                                :ontology-id (:ontology-id config)
                                                :relationships (:relationships extraction)
                                                :extracted-at (str (java.time.Instant/now))}})]

                  tbox-event (when (or (seq (:classes tbox))
                                       (seq (:object-properties tbox))
                                       (seq (:datatype-properties tbox)))
                               (es/->event {:type :evolutionary/tbox-extracted
                                         :tags #{[:source source-id]
                                                 [:ontology (:ontology-id config)]}
                                         :body {:source-id source-id
                                                :ontology-id (:ontology-id config)
                                                :classes (vec (:classes tbox))
                                                :object-properties (vec (:object-properties tbox))
                                                :datatype-properties (vec (:datatype-properties tbox))
                                                :extracted-at (str (java.time.Instant/now))}}))

                  abox-event (when (seq abox)
                               (es/->event {:type :evolutionary/abox-extracted
                                         :tags #{[:source source-id]
                                                 [:ontology (:ontology-id config)]}
                                         :body {:source-id source-id
                                                :ontology-id (:ontology-id config)
                                                :individuals (vec abox)
                                                :extracted-at (str (java.time.Instant/now))}}))

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

;; --- Colbert indexer ---

(defn emit-colbert-indexed-event!
  "Record ColBERT index creation via command processor."
  [ctx {:keys [ontology-id index-id index-name document-count colbert-fields]}]
  (cp/process-command
   (assoc ctx :command {:command/id (random-uuid)
                        :command/timestamp (time/now)
                        :command/name :ontology/record-colbert-index
                        :ontology-id ontology-id
                        :index-id index-id
                        :index-name index-name
                        :document-count document-count
                        :colbert-fields (vec colbert-fields)})))

;; --- Evolutionary builder ---

(defn evolutionary-builder-build-from-sources
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
  (let [config (merge logic/default-config config)
        ontology-id (logic/normalize-ontology-id (or (:ontology-id config) (java.util.UUID/randomUUID)))
        build-id (java.util.UUID/randomUUID)
        config (assoc config :ontology-id ontology-id)

        lifecycle-event (es/->event {:type :ontology/ontology-created
                                  :tags #{[:ontology ontology-id]}
                                  :body {:ontology-id ontology-id
                                         :name (or (:name config) (str "Source ontology " ontology-id))
                                         :scope :custom
                                         :description "Ontology built from registered sources"
                                         :base-uri (:base-uri config)
                                         :created-at (str (java.time.Instant/now))}})

        start-event (es/->event {:type :evolutionary/build-started
                              :tags #{[:ontology ontology-id]
                                      [:build build-id]}
                              :body {:build-id build-id
                                     :ontology-id ontology-id
                                     :mode "batch"
                                     :source-count (count sources)
                                     :config config
                                     :started-at (str (java.time.Instant/now))}})

        start-time (System/currentTimeMillis)

        existing-events (into []
                              (es/read event-store
                                                (cond-> {:types #{:evolutionary/source-registered}
                                                         :tags #{[:ontology ontology-id]}}
                                                  (:tenant-id ctx)
                                                  (assoc :tenant-id (:tenant-id ctx)))))
        read-models (logic/build-read-models existing-events)

        {:keys [registered events]} (logic/register-sources read-models sources ontology-id)
        registration-events events

        {:keys [all-concepts all-relationships all-tbox all-abox events]}
        (extract-from-all-sources ctx registered config)
        extraction-events events

        resolution-result (logic/resolve-within-batch
                            all-concepts
                            {:similarity-threshold (:similarity-threshold config)
                             :emit-owl-sameAs? (:emit-owl-sameAs? config)})
        resolution-event (logic/emit-entities-resolved-event
                           ontology-id :batch resolution-result)

        source-ids (mapv :source-id registered)
        evolution-result (logic/evolve-graph!
                           {:ontology-id ontology-id
                            :source-ids source-ids
                            :extracted-concepts all-concepts
                            :extracted-relationships all-relationships
                            :resolution-result resolution-result})
        evolution-events (:events evolution-result)

        snapshot-result (logic/generate-owl-ttl-snapshot
                          {:tbox all-tbox
                           :abox all-abox
                           :concepts (:concepts (:graph evolution-result))
                           :relationships (:relationships (:graph evolution-result))}
                          {:base-uri (:base-uri config)
                           :include-metadata? (:include-metadata? config)})
        snapshot-event (logic/emit-ttl-snapshot-event
                         ontology-id snapshot-result :turtle)

        colbert-result (when (:enable-colbert? config)
                         (try
                           (logic/index-concepts! ctx
                             all-concepts
                             {:index-name (or (:colbert-index-name config)
                                              (str "ontology-" (subs (str ontology-id) 0 8)))
                              :colbert-fields (:colbert-fields config)
                              :auto-detect-colbert-fields (:auto-detect-colbert-fields? config)})
                           (catch Exception e
                             (println "[WARNING] ColBERT indexing failed:" (.getMessage e))
                             nil)))

        _ (when colbert-result
            (emit-colbert-indexed-event! ctx
              {:ontology-id ontology-id
               :index-id (:index-id colbert-result)
               :index-name (:index-name colbert-result)
               :document-count (:document-count colbert-result)
               :colbert-fields (:colbert-fields colbert-result)}))

        embedding-result (when (:enable-embeddings? config)
                           (try
                             (u/log :ai.obney.orc.ontology.core.evolutionary-builder/embedding-concepts-start
                                     :concept-count (count all-concepts)
                                     :ontology-id ontology-id)
                             (logic/embed-concepts-batch!
                               all-concepts
                               {:embedding-fields (or (:embedding-fields config)
                                                      (:colbert-fields colbert-result))
                                :auto-detect? (:auto-detect-embedding-fields? config)
                                :ctx ctx})
                             (catch Exception e
                               (u/log :ai.obney.orc.ontology.core.evolutionary-builder/embedding-failed :error (.getMessage e))
                               nil)))

        embedding-summary-event (when (and embedding-result
                                           (pos? (:embedded-count embedding-result 0)))
                                  (es/->event {:type :evolutionary/concepts-embedded
                                            :tags #{[:ontology ontology-id]
                                                    [:build build-id]}
                                            :body {:ontology-id ontology-id
                                                   :build-id build-id
                                                   :embedded-count (:embedded-count embedding-result)
                                                   :embedding-fields (or (:fields-used embedding-result) [])
                                                   :model-id (:model-id embedding-result)
                                                   :embedded-at (str (java.time.Instant/now))}}))

        embedding-concept-events (when (and embedding-result (seq (:embeddings embedding-result)))
                                   (let [fields-used (:fields-used embedding-result)
                                         model-id (:model-id embedding-result)
                                         embedded-at (str (java.time.Instant/now))]
                                     (mapv (fn [{:keys [uri embedding text-embedded]}]
                                             (es/->event {:type :ontology/concept-embedded
                                                       :tags #{[:ontology ontology-id]
                                                               [:concept uri]}
                                                       :body {:uri uri
                                                              :ontology-id ontology-id
                                                              :embedding (mapv double embedding)
                                                              :text-embedded text-embedded
                                                              :field-source (str/join "+" (map name fields-used))
                                                              :model-id model-id
                                                              :embedded-at embedded-at}}))
                                           (:embeddings embedding-result))))

        duration-ms (- (System/currentTimeMillis) start-time)
        completion-event (es/->event {:type :evolutionary/build-completed
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

        all-events (concat [lifecycle-event start-event]
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
     :embedding-result embedding-result
     :duration-ms duration-ms
     :events all-events}))

;; --- Public operations ---

(defn build-ontology-from-sources
  "Build ontology from multiple sources using the evolutionary pipeline.

   This is the main entry point for batch ontology construction.

   Args:
     ctx: Context with :event-store
     params:
       :sources - Vector of source maps:
         [{:path \"data.csv\" :type \"csv\"}
          {:path \"database.db\" :type \"sql\"}
          {:content \"text...\" :type \"text\"}]
       :config - Configuration:
         :base-uri - Ontology namespace (default: http://ontology.local/)
         :similarity-threshold - Entity resolution threshold (default: 0.85)
         :enable-colbert? - Enable ColBERT indexing (default: true)
         :enable-embeddings? - Enable embedding generation (default: true)

   Supported source types:
     - \"csv\" - CSV files (provide :entity-column, :entity-type in config)
     - \"text\" - Text documents (provide :domain in config)
     - \"sql\"/\"sqlite\"/\"db\" - SQLite databases
     - \"json\" - JSON files/data (provide :domain in config for better extraction)
     - \"rdf\" - Deterministic N-Triples RDF imports

   Returns:
     {:ontology-id uuid
      :build-id uuid
      :total-sources int
      :total-concepts int
      :total-triples int
      :ttl-output string
      :events [...]}

   Example:
     (build-ontology-from-sources ctx
       {:sources [{:path \"/data/programs.csv\" :type \"csv\"}
                  {:path \"/data/ipeds.db\" :type \"sql\"}]
        :config {:base-uri \"http://education.ai/\"
                 :entity-column \"name\"
                 :entity-type \"Program\"}})"
  [ctx params]

  (#'evolutionary-builder-build-from-sources ctx params))

;; --- Evolutionary builder ---

(defn evolutionary-builder-evolve
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
  (let [config (merge logic/default-config config)
        ontology-id (logic/normalize-ontology-id ontology-id)
        build-id (java.util.UUID/randomUUID)
        config (assoc config :ontology-id ontology-id)

        existing-events (into []
                              (es/read event-store
                                                (cond-> {:types #{:ontology/ontology-created
                                                                  :ontology/concept-created
                                                                  :ontology/relationship-created
                                                                  :evolutionary/source-registered
                                                                  :evolutionary/concepts-extracted
                                                                  :evolutionary/relationships-extracted
                                                                  :evolutionary/entities-resolved}
                                                         :tags #{[:ontology ontology-id]}}
                                                  (:tenant-id ctx)
                                                  (assoc :tenant-id (:tenant-id ctx)))))

        read-models (logic/build-read-models existing-events)

        existing-concept-events (filter #(contains? #{:ontology/concept-created
                                                      :evolutionary/concepts-extracted}
                                                    (:event/type %))
                                        existing-events)
        existing-concepts (->> existing-concept-events
                               (mapcat #(if (= :ontology/concept-created (:event/type %))
                                          [(cond-> {:uri (:uri %)
                                                    :label (:label %)
                                                    :definition (:description %)
                                                    :entity-type (name (:scope %))
                                                    :concept-id (:concept-id %)
                                                    :provenance (:provenance %)}
                                             (:created-at %) (assoc :created-at (:created-at %)))]
                                          (or (get-in % [:body :concepts])
                                              (:concepts %))))
                               vec)
        existing-uris (set (map :uri existing-concepts))
        existing-relationships (->> existing-events
                                    (filter #(contains? #{:ontology/relationship-created
                                                         :evolutionary/relationships-extracted}
                                                       (:event/type %)))
                                    (mapcat #(if (= :ontology/relationship-created (:event/type %))
                                               [{:subject (:source-uri %)
                                                 :predicate (:predicate %)
                                                 :object (:target-uri %)}]
                                               (or (get-in % [:body :relationships])
                                                   (:relationships %))))
                                    vec)

        start-event (es/->event {:type :evolutionary/build-started
                              :tags #{[:ontology ontology-id]
                                      [:build build-id]}
                              :body {:build-id build-id
                                     :ontology-id ontology-id
                                     :mode "incremental"
                                     :source-count (count sources)
                                     :config config
                                     :started-at (str (java.time.Instant/now))}})

        start-time (System/currentTimeMillis)

        {:keys [registered events]} (logic/register-sources read-models sources ontology-id)
        registration-events events

        new-sources (filter (complement :already-registered?) registered)

        {:keys [all-concepts all-relationships all-tbox all-abox events]}
        (extract-from-all-sources ctx new-sources config)
        extraction-events events

        resolution-result (logic/resolve-incremental
                            all-concepts
                            existing-concepts
                            existing-uris
                            {:similarity-threshold (:similarity-threshold config)
                             :prefer-existing-uris? true})
        resolution-event (logic/emit-entities-resolved-event
                           ontology-id :incremental resolution-result)

        existing-graph-events (into []
                                    (es/read event-store
                                                      (cond-> {:types #{:evolutionary/graph-merged}
                                                               :tags #{[:ontology ontology-id]}}
                                                        (:tenant-id ctx)
                                                        (assoc :tenant-id (:tenant-id ctx)))))
        existing-graph {:concepts (into {} (map (juxt :uri identity) existing-concepts))
                        :relationships existing-relationships}

        source-ids (mapv :source-id new-sources)
        evolution-result (logic/evolve-graph!
                           {:ontology-id ontology-id
                            :source-ids source-ids
                            :extracted-concepts all-concepts
                            :extracted-relationships all-relationships
                            :resolution-result resolution-result
                            :existing-graph existing-graph})
        evolution-events (:events evolution-result)

        snapshot-result (logic/generate-owl-ttl-snapshot
                          {:tbox all-tbox
                           :abox all-abox
                           :concepts (:concepts (:graph evolution-result))
                           :relationships (:relationships (:graph evolution-result))}
                          {:base-uri (:base-uri config)
                           :include-metadata? (:include-metadata? config)})
        snapshot-event (logic/emit-ttl-snapshot-event
                         ontology-id snapshot-result :turtle)

        duration-ms (- (System/currentTimeMillis) start-time)
        completion-event (es/->event {:type :evolutionary/build-completed
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

;; --- Public operations ---

(defn evolve-ontology
  "Evolve existing ontology with new sources (incremental mode).

   Extends an existing ontology without rebuilding from scratch.

   Args:
     ctx: Context with :event-store
     params:
       :ontology-id - UUID of existing ontology
       :sources - Vector of new sources to add
       :config - Configuration options

   Returns:
     Same as build-ontology-from-sources"
  [ctx params]

  (#'evolutionary-builder-evolve ctx params))

;; --- Todo processors ---

(defn run-command!
  "Execute a command through the command processor."
  [context command]
  (cp/process-command
    (assoc context :command command)))

;; --- Seeds ---

(defn- emit-seed!
  "Dispatch the appropriate :ontology/record-*-description command for a
   single seed at the given granularity."
  [ctx granularity {:keys [target-id body]}]
  (let [cmd-name (case granularity
                   :node-type        :ontology/record-node-type-description
                   :node-instance    :ontology/record-node-instance-description
                   :tree-fingerprint :ontology/record-tree-description
                   :tree-class       :ontology/record-tree-class-description)]
    (cp/process-command
      (assoc ctx :command {:command/name cmd-name
                           :command/id (random-uuid)
                           :command/timestamp (time/now)
                           :target-id target-id
                           :body body}))))

(defn- emit-behavioral-child!
  "E3 (ADR 0014): emit one DURABLE coding subbehavior via the
   :ontology/mint-behavioral-subtree command. The command owns identity —
   it derives the STABLE id nameUUIDFromBytes(\"mint:\" + name + \":\" +
   parent-behavior), so callers pass :name + :parent-behavior + :body, NOT
   a :target-id. Re-emitting the same (name, parent) lands at the same
   concept (accrues, never scatters). :provenance is :human-authored —
   these are curated bootstrap seeds, not runtime volitional mints."
  [ctx {:keys [name parent-behavior body]}]
  (cp/process-command
    (assoc ctx :command {:command/name :ontology/mint-behavioral-subtree
                         :command/id (random-uuid)
                         :command/timestamp (time/now)
                         :name name
                         :parent-behavior parent-behavior
                         :body body
                         :provenance :human-authored})))

(defn seed-baseline-corpus!
  "Emit every baseline seed shipped with the ontology component into the
   caller's event store. Returns a vec of command-results.

   Three granularities are emitted:
     :node-type        — 10 entries from node-types.edn
     :tree-fingerprint — 23 structural seeds + 12 behavioral seeds
                         (behavioral bodies carry :scope :behavioral-subtree
                         which routes them to the behavioral-subtree
                         reactive processor)
     :tree-class       — same 23 structural seeds dual-emitted under
                         :tree-class scope so the R-Inject prepend
                         assembler's read path (which keys by tree-class)
                         finds them from bootstrap onward.

   E3 (ADR 0014): the durable coding SUBBEHAVIORS (children) from
   behavioral-subtree-children.edn are then emitted via the
   :ontology/mint-behavioral-subtree command (stable derived id +
   :parent-behavior -> skos:broader), AFTER the abstract behavioral
   parents so the parent concepts exist when the broader edge is drawn.

   Idempotent semantics inherit from the underlying :ontology/record-*
   commands — re-emitting the same target-id appends a new
   :tree-description-updated event with the same body, and the read-model
   projects the latest as `:current` while preserving `:history`."
  [ctx]
  (let [node-types (logic/read-seeds "seeds/node-types.edn")
        tree-classes (logic/read-seeds "seeds/tree-classes.edn")
        behaviorals (logic/read-seeds "seeds/behavioral-subtrees.edn")
        behavioral-children (logic/read-seeds "seeds/behavioral-subtree-children.edn")]
    (vec
      (concat
        (mapv #(emit-seed! ctx :node-type %) node-types)
        (mapv #(emit-seed! ctx :tree-fingerprint %) tree-classes)
        (mapv #(emit-seed! ctx :tree-class %) tree-classes)
        (mapv #(emit-seed! ctx :tree-fingerprint %) behaviorals)
        (mapv #(emit-behavioral-child! ctx %) behavioral-children)))))

;; --- Commands ---

(defn concept-by-id [ctx ontology-id concept-id]
  (some #(when (and (= ontology-id (:ontology-id %))
                    (= concept-id (:id %))) %)
        (read-models/get-concepts ctx {:ontology-id ontology-id})))

(defn missing-broader-uri [ctx ontology-id broader]
  (some #(when-not (read-models/get-concept-by-uri ctx ontology-id %) %) broader))

(defn broader-reaches?
  [ctx ontology-id start-uri target-uri]
  (loop [pending [start-uri] seen #{}]
    (if-let [uri (peek pending)]
      (cond
        (= uri target-uri) true
        (contains? seen uri) (recur (pop pending) seen)
        :else (let [parents (:broader (read-models/get-concept-by-uri ctx ontology-id uri))]
                (recur (into (pop pending) parents) (conj seen uri))))
      false)))

(defn evidence-grounded?
  "The spec's `evidence_is_grounded(delta)` precondition — CC-1's seam,
   filled in by CC-4.

   A delta must be trustworthy in provenance before it is recorded: its
   episodes must resolve to occurrences that were actually judged, by a
   judge that actually had input to judge. `evidence-guard/evidence-verdict`
   answers that — deterministically first (no model call, which is what
   makes the starved shape free to catch), then via an injected
   explanation-verifier for the residue the cheap check cannot settle.

   Kept as the same public 2-arity boolean predicate CC-1 published; the
   handler uses the richer verdict directly so it can RECORD why a delta
   was excluded."
  [ctx delta]
  (:grounded? (queries/evidence-verdict ctx delta)))

(defn recorded-claim-delta-count
  "The target's CURRENT claim-set version, computed exactly as the append
   CAS computes it: the number of claim-delta events carrying this target's
   tag.

   Read from the STORE rather than the descriptions projection on purpose.
   The projection is one fold away from the log and could lag, and a
   staleness verdict that disagrees with the CAS would either refuse a
   valid consolidation or emit a refusal for a write that then succeeded.
   Same query, same answer."
  [{:keys [event-store tenant-id]} target-uuid]
  (count (into [] (es/read event-store
                           {:types #{:ontology/claim-deltas-recorded}
                            :tags #{[:claim-target target-uuid]}
                            :tenant-id tenant-id}))))

;; --- Evolutionary ontology operations ---

(defn evolutionary-build-from-sources
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
    (if (:cognitect.anomalies/category result)
      result
      (merge (:command/result result)
             {:events (:command-result/events result)}))))

(defn evolutionary-evolve
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
  (let [events (es/read event-store
                                 {:types #{:evolutionary/source-registered}})
        read-models (logic/build-read-models events)]
    (logic/register-source! read-models params)))

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

    (swap! logic/build-registry assoc build-id
           {:status :running
            :started-at start-time
            :progress 0
            :params params})

    (future
      (try
        (println "[async-build]" build-id "Starting build...")
        (let [result (evolutionary-build-from-sources ctx params)
              duration-ms (- (System/currentTimeMillis) start-time)]
          (swap! logic/build-registry assoc build-id
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
            (swap! logic/build-registry assoc build-id
                   {:status :failed
                    :error (.getMessage e)
                    :exception e
                    :duration-ms duration-ms
                    :failed-at (System/currentTimeMillis)})
            (println "[async-build]" build-id "FAILED after" duration-ms "ms:" (.getMessage e))
            (when on-error (on-error e))))))

    build-id))

;; --- Evolutionary commands ---

(cp/defcommand :ontology build-from-sources
  "Build ontology from sources in batch mode."
  [ctx]
  (let [params (select-keys (:command ctx) [:sources :config])
        result (evolutionary-builder-build-from-sources ctx params)
        events (filterv :event/type (or (:events result) []))]
    {:command-result/events events
     :command/result (dissoc result :events)}))

(cp/defcommand :ontology evolve
  "Evolve existing ontology with new sources."
  [ctx]
  (let [params (select-keys (:command ctx) [:ontology-id :sources :config])]
    (if-not (read-models/ontology-exists? ctx (:ontology-id params))
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message (str "Ontology not found: " (:ontology-id params))}
      (let [result (evolutionary-builder-evolve ctx params)
            events (vec (or (:events result) []))]
        {:command-result/events events
         :command/result (dissoc result :events)}))))

(cp/defcommand :ontology record-colbert-index
  "Record that a ColBERT index was created for an ontology."
  [{{:keys [ontology-id index-id index-name document-count colbert-fields]} :command}]
  {:command-result/events
   [(es/->event
     {:type :evolutionary/colbert-indexed
      :tags #{[:ontology ontology-id] [:colbert-index index-id]}
      :body {:ontology-id ontology-id
             :index-id index-id
             :index-name index-name
             :document-count document-count
             :colbert-fields (vec colbert-fields)
             :indexed-at (str (time/now))}})]})

;; --- Commands ---

(cp/defcommand :ontology create-ontology
  "Create an initially empty custom ontology."
  {:authorized? logic/commands-tenant-scoped?}
  [{{:keys [name scope description base-uri] command-id :command/id} :command
    :as ctx}]
  (if-let [existing (and command-id (read-models/get-ontology ctx command-id))]
    {:command-result/events []
     :command-result/data {:ontology-id (:ontology-id existing)}}
    (if-not (logic/nonblank? name)
    (logic/anomaly :cognitect.anomalies/incorrect "Ontology name must be nonblank")
    (let [ontology-id (or command-id (logic/generate-uuid))
          now (logic/now-str)]
      {:command-result/events
       [(es/->event
         {:type :ontology/ontology-created
          :tags #{[:ontology ontology-id]}
          :body (cond-> {:ontology-id ontology-id
                         :name name
                         :scope scope
                         :created-at now}
                  description (assoc :description description)
                  base-uri (assoc :base-uri base-uri))})]
       :command-result/data {:ontology-id ontology-id}}))))

(cp/defcommand :ontology initialize-static-ontology
  "Initialize the static ontology by emitting concept-created events.
   Optional :scope to only initialize specific ontology layer."
  [{{:keys [scope]} :command
    :keys [event-store]}]
  (let [ontology-id (logic/generate-uuid)
        concepts (if scope
                   (logic/get-concepts-by-scope scope)
                   (logic/get-all-static-concepts))
        relationships (logic/get-all-static-relationships)
        now (logic/now-str)

        ontology-event (es/->event
                        {:type :ontology/ontology-created
                         :tags #{[:ontology ontology-id]}
                         :body {:ontology-id ontology-id
                                :name (case scope
                                        :failure "Failure Ontology"
                                        :success "Success Ontology"
                                        :problem "Problem Domain Ontology"
                                        "ObneyAI Workshop Ontology")
                                :scope (or scope :all)
                                :description "Three-layer ontology system: Failure, Success, Problem Domain"
                                :base-uri "http://obney.ai/workshop/ontology/"
                                :created-at now}})

        concept-events (mapv (fn [concept]
                               (let [concept-id (logic/generate-uuid)]
                                 (es/->event
                                  {:type :ontology/concept-created
                                   :tags #{[:ontology ontology-id]
                                           [:concept concept-id]}
                                   :body {:ontology-id ontology-id
                                          :concept-id concept-id
                                          :uri (:uri concept)
                                          :label (:label concept)
                                          :description (:description concept)
                                          :scope (:scope concept)
                                          :broader (:broader concept)
                                          :indicators (:indicators concept)
                                          :created-at now}})))
                             concepts)

        relationship-events (mapv (fn [rel]
                                    (es/->event
                                     {:type :ontology/relationship-created
                                      :tags #{[:ontology ontology-id]}
                                      :body {:relationship-id (logic/generate-uuid)
                                             :source-uri (:source rel)
                                             :target-uri (:target rel)
                                             :predicate (:predicate rel)
                                             :created-at now}}))
                                  relationships)]

    {:command-result/events (vec (concat [ontology-event]
                                         concept-events
                                         relationship-events))}))

(cp/defcommand :ontology record-tree-strength
  "Record that a tree demonstrates a particular success pattern.

   Domain-agnostic fields:
   - context-conditions: Map of any conditions at decision time (replaces state-conditions)
   - action-taken: Map describing the action that led to success
   - domain-type: String identifier like \"drone-control\", \"legal-review\", \"sales-outreach\"
   - expected-outcome: String describing what success looks like"
  [{{:keys [tree-id pattern-uri confidence evidence-trace-ids avg-score
            context-conditions state-conditions
            action-taken domain-type expected-outcome]} :command
    :keys [event-store]}]
  (let [now (logic/now-str)
        conditions (or context-conditions state-conditions)]
    {:command-result/events
     [(es/->event
       {:type :ontology/tree-strength-recorded
        :tags #{[:tree tree-id]}
        :body (cond-> {:tree-id tree-id
                       :pattern-uri pattern-uri
                       :confidence confidence
                       :evidence-trace-ids (vec evidence-trace-ids)
                       :avg-score avg-score
                       :recorded-at now}
                conditions (assoc :context-conditions conditions
                                  :state-conditions conditions)
                action-taken (assoc :action-taken action-taken)
                domain-type (assoc :domain-type domain-type)
                expected-outcome (assoc :expected-outcome expected-outcome))})]}))

(cp/defcommand :ontology record-tree-weakness
  "Record that a tree exhibits a particular failure pattern.

   Domain-agnostic fields:
   - failure-context: Map of conditions when failure occurred (replaces failure-conditions)
   - attempted-action: Map describing the action that was attempted
   - domain-type: String identifier like \"drone-control\", \"legal-review\", \"sales-outreach\""
  [{{:keys [tree-id failure-uri subtype-uri frequency severity triggers evidence-trace-ids
            failure-context failure-conditions
            attempted-action domain-type]} :command
    :keys [event-store]}]
  (let [now (logic/now-str)
        severity-kw (if (keyword? severity) severity (keyword severity))
        context (or failure-context failure-conditions)]
    {:command-result/events
     [(es/->event
       {:type :ontology/tree-weakness-recorded
        :tags #{[:tree tree-id]}
        :body (cond-> {:tree-id tree-id
                       :failure-uri failure-uri
                       :frequency frequency
                       :severity severity-kw
                       :triggers (vec triggers)
                       :evidence-trace-ids (vec evidence-trace-ids)
                       :recorded-at now}
                subtype-uri (assoc :subtype-uri subtype-uri)
                context (assoc :failure-context context
                               :failure-conditions context)
                attempted-action (assoc :attempted-action attempted-action)
                domain-type (assoc :domain-type domain-type))})]}))

(cp/defcommand :ontology record-problem-mapping
  "Record that a tree solves a particular problem type."
  [{{:keys [tree-id problem-uri success-rate execution-count]} :command
    :keys [event-store] :as ctx}]
  (let [now (logic/now-str)
        existing-profile (read-models/get-tree-profile ctx tree-id)
        existing-mapping (some #(when (= problem-uri (:problem-uri %)) %)
                               (:solves existing-profile))]
    (if existing-mapping
      {:command-result/events
       [(es/->event
         {:type :ontology/tree-problem-mapping-updated
          :tags #{[:tree tree-id]}
          :body {:tree-id tree-id
                 :problem-uri problem-uri
                 :success-rate success-rate
                 :execution-count execution-count
                 :updated-at now}})]}
      {:command-result/events
       [(es/->event
         {:type :ontology/tree-problem-mapping-created
          :tags #{[:tree tree-id]}
          :body {:tree-id tree-id
                 :problem-uri problem-uri
                 :success-rate success-rate
                 :execution-count execution-count
                 :recorded-at now}})]})))

(cp/defcommand :ontology add-domain-knowledge
  "Add domain knowledge learned from tree execution."
  [{{:keys [tree-id node-id description based-on-failure-traces impact-score]} :command
    :keys [event-store]}]
  (let [now (logic/now-str)
        knowledge-id (logic/generate-uuid)]
    {:command-result/events
     [(es/->event
       {:type :ontology/domain-knowledge-added
        :tags #{[:tree tree-id]
                (when node-id [:node node-id])}
        :body {:knowledge-id knowledge-id
               :tree-id tree-id
               :node-id node-id
               :description description
               :based-on-failure-traces (vec based-on-failure-traces)
               :impact-score impact-score
               :added-at now}})]}))

(cp/defcommand :ontology record-node-pattern
  "Record a pattern learned from node execution."
  [{{:keys [node-id sheet-id node-type pattern-type effective? pattern-description
            metrics evidence-trace-ids]} :command
    :keys [event-store]}]
  (let [now (logic/now-str)
        node-type-kw (if (keyword? node-type) node-type (keyword node-type))
        pattern-type-kw (if (keyword? pattern-type) pattern-type (keyword pattern-type))]
    {:command-result/events
     [(es/->event
       {:type :ontology/node-pattern-learned
        :tags #{[:node node-id]
                [:sheet sheet-id]}
        :body {:node-id node-id
               :sheet-id sheet-id
               :node-type node-type-kw
               :pattern-type pattern-type-kw
               :effective? effective?
               :pattern-description pattern-description
               :metrics metrics
               :evidence-trace-ids (vec evidence-trace-ids)
               :learned-at now}})]}))

(cp/defcommand :ontology classify-evaluation
  "Classify an evaluation result and optionally auto-record tree weaknesses.

   Takes evaluation results from the evaluation component and:
   1. Classifies failures using the failure ontology
   2. Optionally records weaknesses on the tree profile

   Args:
   - trace-id: The trace that was evaluated
   - sheet-id: The sheet (tree) that was executed
   - node-id: The specific node that was evaluated
   - evaluation-result: {:score :dimensions [{:name :score :feedback}]}
   - auto-record?: If true, emit tree-weakness-recorded events"
  [{{:keys [trace-id sheet-id node-id evaluation-result auto-record?]} :command
    :keys [event-store]}]
  (let [classification (logic/classifier-classify-evaluation evaluation-result)
        now (logic/now-str)

        weakness-events (when (and auto-record? (seq (:failures classification)))
                          (mapv (fn [{:keys [uri base-uri subtype-uri confidence evidence dimension]}]
                                  (es/->event
                                   {:type :ontology/tree-weakness-recorded
                                    :tags #{[:tree sheet-id]
                                            [:trace trace-id]}
                                    :body {:tree-id sheet-id
                                           :failure-uri base-uri
                                           :subtype-uri subtype-uri
                                           :frequency confidence
                                           :severity (logic/estimate-severity
                                                      {:uri uri
                                                       :confidence confidence
                                                       :dimension-score (some #(when (= dimension (:name %))
                                                                                  (:score %))
                                                                              (:dimensions evaluation-result))})
                                           :triggers (logic/extract-triggers [{:evidence evidence}])
                                           :evidence-trace-ids [trace-id]
                                           :recorded-at now}}))
                                (:failures classification)))]

    {:command-result/data {:classification classification
                           :auto-recorded? (boolean auto-record?)
                           :recorded-weaknesses (count (or weakness-events []))}
     :command-result/events (or weakness-events [])}))

(cp/defcommand :ontology create-concept
  "Create a new concept in the ontology."
  {:authorized? logic/commands-tenant-scoped?}
  [{{:keys [ontology-id uri label description scope broader indicators provenance]
     command-name :command/name command-id :command/id} :command
    :as ctx}]
  (cond
    (and command-id (concept-by-id ctx ontology-id command-id))
    {:command-result/events []
     :command-result/data {:ontology-id ontology-id :concept-id command-id :uri uri}}

    (and command-name (not (read-models/ontology-exists? ctx ontology-id)))
    (logic/anomaly :cognitect.anomalies/not-found (str "Ontology not found: " ontology-id))

    (not-every? logic/nonblank? [uri label description])
    (logic/anomaly :cognitect.anomalies/incorrect "Concept URI, label, and description must be nonblank")

    (read-models/get-concept-by-uri ctx ontology-id uri)
    (logic/anomaly :cognitect.anomalies/conflict (str "Concept URI already exists in ontology: " uri))

    (missing-broader-uri ctx ontology-id (or broader []))
    (logic/anomaly :cognitect.anomalies/not-found "Every broader URI must exist in the same ontology")

    :else
    (let [concept-id (or command-id (logic/generate-uuid))
          now (logic/now-str)
          scope-kw (if (keyword? scope) scope (keyword scope))
          provenance (or provenance {:kind :human-authored})]
      {:command-result/events
       [(es/->event
         {:type :ontology/concept-created
          :tags #{[:ontology ontology-id]
                  [:concept concept-id]}
          :body {:ontology-id ontology-id
                 :concept-id concept-id
                 :uri uri
                 :label label
                 :description description
                 :scope scope-kw
                 :broader (vec (or broader []))
                 :indicators (vec (or indicators []))
                 :provenance provenance
                 :created-at now}})]
       :command-result/data {:ontology-id ontology-id
                             :concept-id concept-id
                             :uri uri}})))

(cp/defcommand :ontology update-concept
  "Update the mutable descriptive fields of an existing concept."
  {:authorized? logic/commands-tenant-scoped?}
  [{{:keys [ontology-id concept-id changes] command-id :command/id} :command
    :keys [event-store tenant-id] :as ctx}]
  (let [concept (concept-by-id ctx ontology-id concept-id)
        retried (some #(when (= command-id (:update-id %)) %)
                      (into [] (es/read event-store
                                       (cond-> {:types #{:ontology/concept-updated}}
                                         tenant-id (assoc :tenant-id tenant-id)))))
        changed (select-keys changes [:label :description :broader :indicators])]
    (cond
      retried
      {:command-result/events []
       :command-result/data {:ontology-id ontology-id
                             :concept-id concept-id
                             :uri (:uri concept)}}

      (not (read-models/ontology-exists? ctx ontology-id))
      (logic/anomaly :cognitect.anomalies/not-found (str "Ontology not found: " ontology-id))

      (nil? concept)
      (logic/anomaly :cognitect.anomalies/not-found (str "Concept not found: " concept-id))

      (empty? changed)
      (logic/anomaly :cognitect.anomalies/incorrect "At least one permitted field must change")

      (or (and (contains? changed :label) (not (logic/nonblank? (:label changed))))
          (and (contains? changed :description) (not (logic/nonblank? (:description changed)))))
      (logic/anomaly :cognitect.anomalies/incorrect "Updated label and description must be nonblank")

      (and (:broader changed)
           (missing-broader-uri ctx ontology-id (:broader changed)))
      (logic/anomaly :cognitect.anomalies/not-found "Every broader URI must exist in the same ontology")

      (and (:broader changed)
           (some #(broader-reaches? ctx ontology-id % (:uri concept))
                 (:broader changed)))
      (logic/anomaly :cognitect.anomalies/incorrect "The update would create a broader-concept cycle")

      (= changed (select-keys concept (keys changed)))
      (logic/anomaly :cognitect.anomalies/conflict "The requested update does not change the concept")

      :else
      (let [now (logic/now-str)]
        {:command-result/events
         [(es/->event {:type :ontology/concept-updated
                    :tags #{[:ontology ontology-id] [:concept concept-id]}
                    :body {:update-id command-id
                           :ontology-id ontology-id
                           :concept-id concept-id
                           :changes changed
                           :updated-at now}})]
         :command-result/data {:ontology-id ontology-id
                               :concept-id concept-id
                               :uri (:uri concept)}}))))

(cp/defcommand :ontology create-relationship
  "Create a relationship between two concepts."
  {:authorized? logic/commands-tenant-scoped?}
  [{{:keys [source-ontology-id target-ontology-id source-uri target-uri predicate metadata]
     command-name :command/name command-id :command/id} :command
    :keys [event-store tenant-id] :as ctx}]
  (let [source (read-models/get-concept-by-uri ctx source-ontology-id source-uri)
        target (read-models/get-concept-by-uri ctx target-ontology-id target-uri)
        relationship-events (into []
                                  (es/read event-store
                                           (cond-> {:types #{:ontology/relationship-created}}
                                             tenant-id (assoc :tenant-id tenant-id))))
        retried (some #(when (= command-id (:relationship-id %)) %) relationship-events)
        projected-duplicate? (contains? (get source
                                             (case predicate
                                               "skos:broader" :broader
                                               "skos:narrower" :narrower
                                               "skos:related" :related
                                               "behavior:composes-into" :composes-into
                                               :related)
                                             #{})
                                        target-uri)
        duplicate? (some #(and (= source-ontology-id (:source-ontology-id %))
                               (= target-ontology-id (:target-ontology-id %))
                               (= source-uri (:source-uri %))
                               (= target-uri (:target-uri %))
                               (= predicate (:predicate %)))
                         relationship-events)]
    (cond
      retried
      {:command-result/events []
       :command-result/data {:relationship-id command-id
                             :source-ontology-id (:source-ontology-id retried)
                             :target-ontology-id (:target-ontology-id retried)
                             :source-uri (:source-uri retried)
                             :target-uri (:target-uri retried)
                             :predicate (:predicate retried)}}

      (and command-name (not (read-models/ontology-exists? ctx source-ontology-id)))
      (logic/anomaly :cognitect.anomalies/not-found (str "Source ontology not found: " source-ontology-id))
      (and command-name (not (read-models/ontology-exists? ctx target-ontology-id)))
      (logic/anomaly :cognitect.anomalies/not-found (str "Target ontology not found: " target-ontology-id))
      (nil? source) (logic/anomaly :cognitect.anomalies/not-found (str "Source concept not found: " source-uri))
      (nil? target) (logic/anomaly :cognitect.anomalies/not-found (str "Target concept not found: " target-uri))
      (not (logic/nonblank? predicate)) (logic/anomaly :cognitect.anomalies/incorrect "Relationship predicate must be nonblank")
      (not (contains? logic/supported-relationship-predicates predicate))
      (logic/anomaly :cognitect.anomalies/incorrect (str "Unsupported relationship predicate: " predicate))
      (and (not= source-ontology-id target-ontology-id)
           (not= "behavior:composes-into" predicate))
      (logic/anomaly :cognitect.anomalies/incorrect "Cross-ontology relationships require an explicitly supported predicate")
      (and (= source-ontology-id target-ontology-id) (= source-uri target-uri))
      (logic/anomaly :cognitect.anomalies/incorrect "Self relationships are not supported")
      (and (= source-ontology-id target-ontology-id)
           (= predicate "skos:broader")
           (broader-reaches? ctx source-ontology-id target-uri source-uri))
      (logic/anomaly :cognitect.anomalies/incorrect "The relationship would create a broader-concept cycle")
      (and (= source-ontology-id target-ontology-id)
           (= predicate "skos:narrower")
           (broader-reaches? ctx source-ontology-id source-uri target-uri))
      (logic/anomaly :cognitect.anomalies/incorrect "The relationship would create a broader-concept cycle")
      (or duplicate? projected-duplicate?)
      (logic/anomaly :cognitect.anomalies/conflict "Relationship already exists")
      :else
      (let [relationship-id (or command-id (logic/generate-uuid))
            now (logic/now-str)]
        {:command-result/events
         [(es/->event
           {:type :ontology/relationship-created
            :tags (cond-> #{[:relationship relationship-id]
                            [:ontology source-ontology-id]}
                    (not= source-ontology-id target-ontology-id)
                    (conj [:ontology target-ontology-id]))
            :body (cond-> {:relationship-id relationship-id
                           :source-ontology-id source-ontology-id
                           :target-ontology-id target-ontology-id
                           :source-uri source-uri
                           :target-uri target-uri
                           :predicate predicate
                           :created-at now}
                    metadata (assoc :metadata metadata))})]
         :command-result/data {:relationship-id relationship-id
                               :source-ontology-id source-ontology-id
                               :target-ontology-id target-ontology-id
                               :source-uri source-uri
                               :target-uri target-uri
                               :predicate predicate}}))))

(cp/defcommand :ontology mint-domain-child
  "RS-3 + CV-A item 5: birth a domain child under a tree-class parent
   through ONE command — the child's tree-class concept (labelled with the
   judged domain label) and its skos:broader edge to the parent, plus the
   audit-trail :ontology/domain-child-minted event. Dispatched by the wedge
   BEFORE the CV-1 signature claim and BEFORE :ontology/assign-task-class,
   so the concept and edge exist before anything references the child.

   CV-A: an OPTIONAL `:birth-description` string — sent only for a genuine
   tenant-wide family birth, never the legacy per-parent sibling mint —
   becomes the child concept's `:description` (in place of the generic
   placeholder) AND is embedded synchronously into the SAME event batch as
   an `:ontology/concept-embedded` event
   (`DomainFamilyDescribesItselfFromBirth`: \"its concept is embedded at
   birth\"). Embedding here, rather than as a second dispatched command,
   is deliberate: a checkpointed campaign prepares every staged effect from
   ONE pre-commit snapshot, so a separate embed command could never read
   back a concept THIS SAME commit is creating (empirically confirmed — a
   staged `:ontology/embed-concept` effect threw \"Concept not found\")."
  [{{:keys [parent-tree-id child-tree-id domain-label birth-description
            source-sheet-id source-tick-id source-node-id]} :command
    :as ctx}]
  (let [parent-uri (logic/commands-tree-class-uri parent-tree-id)
        child-uri (logic/commands-tree-class-uri child-tree-id)
        now (logic/now-str)
        parent-concept (read-models/get-concept-by-uri ctx logic/commands-tree-class-ontology-id parent-uri)
        child-concept (read-models/get-concept-by-uri ctx logic/commands-tree-class-ontology-id child-uri)
        already-linked? (contains? (:broader child-concept) parent-uri)
        child-concept-id (random-uuid)
        parent-concept-event
        (when-not parent-concept
          (es/->event
           {:type :ontology/concept-created
            :tags #{[:ontology logic/commands-tree-class-ontology-id] [:concept (random-uuid)]}
            :body {:ontology-id logic/commands-tree-class-ontology-id
                   :concept-id (random-uuid)
                   :uri parent-uri
                   :label (str parent-tree-id)
                   :description (str "Tree-class concept for " parent-tree-id)
                   :scope :tree-class
                   :broader []
                   :indicators []
                   :provenance {:kind :system-static}
                   :created-at now}}))
        child-is-new? (not child-concept)
        child-concept-event
        (when child-is-new?
          (es/->event
           {:type :ontology/concept-created
            :tags #{[:ontology logic/commands-tree-class-ontology-id] [:concept child-concept-id]}
            :body {:ontology-id logic/commands-tree-class-ontology-id
                   :concept-id child-concept-id
                   :uri child-uri
                   :label domain-label
                   :description (or birth-description
                                    (str "Domain child of " parent-tree-id))
                   :scope :tree-class
                   :broader [parent-uri]
                   :indicators []
                   :provenance {:kind :agent-authored}
                   :created-at now}}))
        relationship-event
        (when-not already-linked?
          (es/->event
           {:type :ontology/relationship-created
            :tags #{[:relationship (random-uuid)] [:ontology logic/commands-tree-class-ontology-id]}
            :body {:relationship-id (random-uuid)
                   :source-ontology-id logic/commands-tree-class-ontology-id
                   :target-ontology-id logic/commands-tree-class-ontology-id
                   :source-uri child-uri
                   :target-uri parent-uri
                   :predicate "skos:broader"
                   :created-at now}}))
        embed-event
        (when (and child-is-new? (seq birth-description))
          (let [embedding-vec (logic/embedding-embed-text birth-description)]
            (when-not embedding-vec
              (throw (ex-info "Failed to generate the domain family's birth embedding"
                              {:child-tree-id child-tree-id :uri child-uri
                               :requirement :birth-embedding})))
            (do
              (es/->event
               {:type :ontology/concept-embedded
                :tags #{[:concept child-concept-id]}
                :body {:concept-id child-concept-id
                       :uri child-uri
                       :ontology-id logic/commands-tree-class-ontology-id
                       :scope :tree-class
                       :text-embedded birth-description
                       :field-source "birth-description"
                       :embedding embedding-vec
                       :model-id logic/default-model-id
                       :embedded-at now}}))))
        already-minted? (and (some? child-concept) already-linked?)]
    (if already-minted?
      {:command-result/events []}
      {:command-result/events
       (into [] (remove nil?)
             [parent-concept-event
              child-concept-event
              relationship-event
              embed-event
              (es/->event
               {:type :ontology/domain-child-minted
                :tags #{[:tree-class-child child-tree-id]
                        [:description-target child-tree-id]}
                :body (cond-> {:parent-tree-id parent-tree-id
                               :child-tree-id child-tree-id
                               :domain-label domain-label
                               :minted-at now}
                        source-sheet-id (assoc :source-sheet-id source-sheet-id)
                        source-tick-id (assoc :source-tick-id source-tick-id)
                        source-node-id (assoc :source-node-id source-node-id))})])
       :command-result/data {:parent-tree-id parent-tree-id
                             :child-tree-id child-tree-id
                             :domain-label domain-label}})))

(cp/defcommand :ontology propose-failure-subtype
  "Propose a new failure subtype discovered from analysis."
  [{{:keys [parent-uri proposed-uri label description evidence-count]} :command
    :keys [event-store]}]
  (let [discovery-id (logic/generate-uuid)
        now (logic/now-str)]
    {:command-result/events
     [(es/->event
       {:type :ontology/failure-subtype-discovered
        :tags #{[:discovery discovery-id]}
        :body {:discovery-id discovery-id
               :parent-uri parent-uri
               :proposed-uri proposed-uri
               :label label
               :description description
               :evidence-count evidence-count
               :discovered-at now
               :status :proposed}})]}))

(cp/defcommand :ontology configure-embedding-model
  "Configure an embedding model for a specific ontology scope.

   This allows different scopes (failure, success, problem) to use
   different embedding models optimized for their content."
  [{{:keys [scope model-id dimensions]} :command
    :keys [event-store]}]
  (let [ontology-id (logic/generate-uuid)
        now (logic/now-str)
        scope-kw (if (keyword? scope) scope (keyword scope))
        dims (or dimensions logic/default-dimensions)]
    {:command-result/events
     [(es/->event
       {:type :ontology/embedding-model-configured
        :tags #{[:ontology ontology-id]}
        :body {:ontology-id ontology-id
               :scope scope-kw
               :model-id model-id
               :dimensions dims
               :configured-at now}})]}))

(cp/defcommand :ontology embed-concept
  "Generate and store embedding for a concept.

   Args:
   - uri: The concept URI to embed
   - fields: Optional set of fields to include (:label :description :indicators :triggers)"
  [{{:keys [uri fields]} :command
    :keys [event-store] :as ctx}]
  (let [
        concept (or (logic/static-ontology-get-concept-by-uri uri)
                    (read-models/get-concept-by-uri ctx uri))
        _ (when-not concept
            (throw (ex-info "Concept not found" {:uri uri
                                                 :cognitect.anomalies/category :cognitect.anomalies/not-found})))

        fields-set (or (when fields (set fields)) #{:label :description})
        text (logic/embedding-concept->embedding-text concept fields-set)

        embedding-vec (logic/embedding-embed-text text)
        _ (when-not embedding-vec
            (throw (ex-info "Failed to generate embedding" {:uri uri
                                                            :cognitect.anomalies/category :cognitect.anomalies/fault})))

        concept-id (or (:id concept) (logic/generate-uuid))
        now (logic/now-str)]
    {:command-result/data {:uri uri
                            :dimensions (count embedding-vec)
                            :text-embedded text}
     :command-result/events
     [(es/->event
       {:type :ontology/concept-embedded
        :tags #{[:concept concept-id]}
        :body {:concept-id concept-id
               :uri uri
               :ontology-id (:ontology-id concept)
               :scope (:scope concept)
               :text-embedded text
               :field-source (name (first fields-set))
               :embedding embedding-vec
               :model-id logic/default-model-id
               :embedded-at now}})]}))

(cp/defcommand :ontology embed-concepts-batch
  "Embed multiple concepts in batch.

   Args:
   - scope: Optional scope to filter concepts (:failure :success :problem)
   - uris: Optional specific URIs to embed (otherwise all in scope)
   - fields: Optional set of fields to include"
  [{{:keys [scope uris fields]} :command
    :keys [event-store] :as ctx}]
  (let [
        concepts (cond
                   (seq uris)
                   (keep #(or (logic/static-ontology-get-concept-by-uri %)
                              (read-models/get-concept-by-uri ctx %))
                         uris)

                   scope
                   (logic/get-concepts-by-scope scope)

                   :else
                   (logic/get-all-static-concepts))

        fields-set (or (when fields (set fields)) #{:label :description})
        now (logic/now-str)

        events (reduce
                 (fn [acc concept]
                   (let [text (logic/embedding-concept->embedding-text concept fields-set)
                         embedding-vec (logic/embedding-embed-text text)
                         concept-id (or (:id concept) (logic/generate-uuid))]
                     (if embedding-vec
                       (conj acc
                             (es/->event
                              {:type :ontology/concept-embedded
                               :tags #{[:concept concept-id]}
                               :body {:concept-id concept-id
                                      :uri (:uri concept)
                                      :ontology-id (:ontology-id concept)
                                      :scope (:scope concept)
                                      :text-embedded text
                                      :field-source (name (first fields-set))
                                      :embedding embedding-vec
                                      :model-id logic/default-model-id
                                      :embedded-at now}}))
                       acc)))
                 []
                 concepts)]

    {:command-result/data {:embedded-count (count events)
                            :total-concepts (count concepts)}
     :command-result/events events}))

(cp/defcommand :ontology embed-tree-profile
  "Generate and store embedding for a tree profile summary."
  [{{:keys [tree-id]} :command
    :keys [event-store] :as ctx}]
  (let [profile (read-models/get-tree-profile ctx tree-id)
        _ (when-not profile
            (throw (ex-info "Tree profile not found" {:tree-id tree-id
                                                      :cognitect.anomalies/category :cognitect.anomalies/not-found})))

        text (logic/tree-profile->embedding-text profile)
        embedding-vec (logic/embedding-embed-text text)
        _ (when-not embedding-vec
            (throw (ex-info "Failed to generate embedding" {:tree-id tree-id
                                                            :cognitect.anomalies/category :cognitect.anomalies/fault})))
        now (logic/now-str)]

    {:command-result/data {:tree-id tree-id
                            :dimensions (count embedding-vec)
                            :text-embedded text}
     :command-result/events
     [(es/->event
       {:type :ontology/tree-profile-embedded
        :tags #{[:tree tree-id]}
        :body {:tree-id tree-id
               :text-embedded text
               :embedding embedding-vec
               :model-id logic/default-model-id
               :embedded-at now}})]}))

(cp/defcommand :ontology embed-evaluation-feedback
  "Generate and store embedding for evaluation feedback.

   Useful for finding similar evaluation feedback across traces."
  [{{:keys [trace-id dimension feedback failure-uri]} :command
    :keys [event-store]}]
  (let [text (logic/evaluation-feedback->embedding-text feedback dimension)
        embedding-vec (logic/embedding-embed-text text)
        _ (when-not embedding-vec
            (throw (ex-info "Failed to generate embedding" {:trace-id trace-id
                                                            :cognitect.anomalies/category :cognitect.anomalies/fault})))
        now (logic/now-str)]

    {:command-result/data {:trace-id trace-id
                            :dimension dimension
                            :dimensions (count embedding-vec)}
     :command-result/events
     [(es/->event
       {:type :ontology/evaluation-embedded
        :tags #{[:trace trace-id]}
        :body {:trace-id trace-id
               :dimension dimension
               :feedback feedback
               :embedding embedding-vec
               :failure-uri failure-uri
               :model-id logic/default-model-id
               :embedded-at now}})]}))

(cp/defcommand :ontology extract-learned-rules
  "Extract condition-action rules from successful episodes.

   Domain-agnostic: Works with any domain by accepting domain-type and domain-description.

   Analyzes tree-strength-recorded events with rich context to extract
   reusable condition-action rules that can be injected into future LLM prompts.

   Args:
   - tree-id: UUID of the tree to analyze
   - problem-type: Problem type URI being solved
   - min-episodes: Minimum episodes required (default 5)
   - domain-type: Domain identifier, e.g. 'drone-control', 'legal-review'
   - domain-description: Human-readable context for LLM"
  [{{:keys [tree-id problem-type min-episodes domain-type domain-description]} :command
    :keys [event-store] :as ctx}]
  (let [result (queries/extract-rules ctx tree-id
                 {:domain-type (or domain-type "unknown")
                  :domain-description (or domain-description "General task execution")
                  :min-episodes (or min-episodes 5)})]

    (if (:skipped result)
      {:command-result/data result}

      (let [now (logic/now-str)
            events (mapv (fn [rule]
                           (es/->event
                             {:type :ontology/learned-rule-extracted
                              :tags #{[:tree tree-id]}
                              :body {:rule-id (logic/generate-uuid)
                                     :tree-id tree-id
                                     :rule {:condition (or (:conditions rule) {})
                                            :action (or (:action rule) {})
                                            :confidence (or (:confidence rule) 0.8)
                                            :success-rate (or (:success-rate rule) 0.9)
                                            :evidence-episodes []}
                                     :problem-type problem-type
                                     :domain-type (:domain-type result)
                                     :extracted-at now}}))
                         (:rules result))]

        {:command-result/data {:extracted (count events)
                                :analyzed-episodes (:analyzed-episodes result)
                                :domain-type (:domain-type result)
                                :tree-id tree-id}
         :command-result/events events}))))

(cp/defcommand :ontology record-node-type-description
  "Record (or update) the description for a node-type — a cross-sheet
   aggregation across every node of this :type. Emits the
   :ontology/node-type-description-updated event."
  [{{:keys [target-id body model-provenance request-id]} :command}]
  {:command-result/events
   [(es/->event
     {:type :ontology/node-type-description-updated
      :tags #{[:description-target (logic/stable-uuid-from (str "node-type:" target-id))]}
      :body (cond-> {:target-type :node-type
                     :target-id target-id
                     :body body
                     :recorded-at (logic/now-str)
                     :model-provenance model-provenance}
              (some? request-id) (assoc :request-id request-id))})]})

(cp/defcommand :ontology record-node-instance-description
  "Record (or update) the description for a specific node instance —
   keyed by [sheet-id node-id]. Emits the
   :ontology/node-instance-description-updated event."
  [{{:keys [target-id body model-provenance request-id]} :command}]
  (let [[sheet-id node-id] target-id]
    {:command-result/events
     [(es/->event
       {:type :ontology/node-instance-description-updated
        :tags #{[:sheet sheet-id]
                [:node node-id]
                [:description-target (logic/stable-uuid-from
                                       (str "node-instance:" sheet-id ":" node-id))]}
        :body (cond-> {:target-type :node-instance
                       :target-id target-id
                       :body body
                       :recorded-at (logic/now-str)
                       :model-provenance model-provenance}
                (some? request-id) (assoc :request-id request-id))})]}))

(cp/defcommand :ontology record-tree-description
  "Record (or update) the description for a tree-fingerprint —
   identifying all trees with the same canonical structure. Emits the
   :ontology/tree-description-updated event."
  [{{:keys [target-id body model-provenance request-id]} :command}]
  {:command-result/events
   [(es/->event
     {:type :ontology/tree-description-updated
      :tags #{[:description-target (logic/stable-uuid-from
                                     (str "tree-fingerprint:" target-id))]}
      :body (cond-> {:target-type :tree-fingerprint
                     :target-id target-id
                     :body body
                     :recorded-at (logic/now-str)
                     :model-provenance model-provenance}
              (some? request-id) (assoc :request-id request-id))})]})

(cp/defcommand :ontology record-tree-class-description
  "C-Loop-1: record (or update) the description for a tree-class —
   the substrate R-Inject's classifier reads via get-description.
   Distinct from :tree-fingerprint, which keys on canonical-S-expr
   SHAs of observed trees; :tree-class keys on the stable seed UUID
   (or fresh-mint root UUID) the classifier assigns. Emits the same
   :ontology/tree-description-updated event with :target-type :tree-class."
  [{{:keys [target-id body model-provenance request-id]} :command}]
  {:command-result/events
   [(es/->event
     {:type :ontology/tree-description-updated
      :tags #{[:description-target (logic/stable-uuid-from
                                     (str "tree-class:" target-id))]}
      :body (cond-> {:target-type :tree-class
                     :target-id target-id
                     :body body
                     :recorded-at (logic/now-str)
                     :model-provenance model-provenance}
              (some? request-id) (assoc :request-id request-id))})]})

(cp/defcommand :ontology record-claim-deltas
  "CC-1: record a consolidation's proposed claim deltas against a target's
   claim set. Emits :ontology/claim-deltas-recorded.

   Appends under CAS on the claim-set version. The version IS the count of
   claim-delta events already recorded for this target, so the predicate
   compares the caller's `:claim-set-version` against that count inside the
   store's own transaction: a consolidation computed against a stale
   version writes nothing, rather than racing a concurrent consolidation
   and last-write-winning.

   CC-4 adds the two things that make a REJECTED write observable, since
   both are the same concern and both live on this seam:

   1. The stale-refusal FACT — the spec's `ClaimDeltasRefused`. The
      handler compares the caller's version against the store's own count
      first and emits `:ontology/claim-deltas-refused` (carrying BOTH
      versions) instead of recording. The CAS stays as the authority for
      a consolidation that goes stale between that read and the append:
      that one still loses at the store with ::anom/conflict, and emits
      no fact. Callers that must retry read `:ontology/refused` on the
      result; observers read `get-claim-delta-refusals`.

   2. The evidence guard — the spec's `evidence_is_grounded` precondition
      (see `evidence-grounded?`), applied PER DELTA. A batch keeps its
      grounded deltas and drops only the ungrounded ones, each recorded
      as `:ontology/promotion-evidence-excluded` and readable through
      `get-excluded-evidence`. Rejecting the whole batch (what the
      inert-seam version of this handler did) would be the same
      protection-induced starvation the `claim_exists` decision below
      rejects.

   CC-2 RE-DECISION — no handler-side `claim_exists` guard. The spec's
   `RecordClaimDeltas` has a third precondition, that every non-add delta
   names an existing claim. It stays enforced at the FOLD (an unresolvable
   `:target-claim` merges to nothing) rather than here, and CC-2 makes
   that choice stronger rather than weaker: now that a claim can RETIRE at
   exhausted support, a delta naming a claim that has since left is a
   normal outcome of concurrent learning, not a malformed command.
   Rejecting the command would fail the whole batch — including its valid
   `:add`s — over one stale reference, which is exactly the
   protection-induced starvation ADR 0021 exists to remove. It would also
   make command validity depend on projection freshness. The two
   preconditions that protect the WRITE (`evidence_is_grounded`, the
   claim-set version) are enforced here, where they belong."
  [{{:keys [granularity target-identifier deltas
            evidence-event-count claim-set-version model-provenance
            request-id]} :command
    :as ctx}]
  (let [target-uuid (logic/claim-target-uuid granularity target-identifier)
        current-version (recorded-claim-delta-count ctx target-uuid)]
    (if (not= claim-set-version current-version)
      {:command-result/events
       [(es/->event
          {:type :ontology/claim-deltas-refused
           :tags #{[:claim-target target-uuid]}
           :body (cond-> {:granularity granularity
                          :target-identifier target-identifier
                          :reason :stale-claim-set
                          :attempted-version claim-set-version
                          :current-version current-version
                          :delta-count (count deltas)
                          :refused-at (logic/now-str)}
                   (some? request-id) (assoc :request-id request-id))})]
       :ontology/refused {:reason :stale-claim-set
                          :attempted-version claim-set-version
                          :current-version current-version}}
      (let [verdicts (mapv #(queries/evidence-verdict ctx %) deltas)
            paired (mapv vector deltas verdicts)
            kept (mapv first (filterv (comp :grounded? second) paired))
            dropped (filterv (comp not :grounded? second) paired)
            exclusion-events
            (mapv (fn [[delta verdict]]
                    (es/->event
                      {:type :ontology/promotion-evidence-excluded
                       :tags #{[:claim-target target-uuid]}
                       :body (cond-> {:granularity granularity
                                      :target-identifier target-identifier
                                      :reason (:reason verdict)
                                      :episodes (vec (:episodes verdict))
                                      :operation (:operation delta)
                                      :kind (:kind delta)
                                      :content (:content delta)
                                      :settled-by (:settled-by verdict)
                                      :excluded-at (logic/now-str)}
                               (some? request-id) (assoc :request-id request-id))}))
                  dropped)]
        (if (empty? kept)
          {:command-result/events exclusion-events}
          {:command-result/events
           (into [(es/->event
                    {:type :ontology/claim-deltas-recorded
                     :tags #{[:claim-target target-uuid]}
                     :body (cond-> {:granularity granularity
                                    :target-identifier target-identifier
                                    :deltas kept
                                    :evidence-event-count (or evidence-event-count 0)
                                    :claim-set-version claim-set-version
                                    :recorded-at (logic/now-str)}
                             (some? model-provenance)
                             (assoc :model-provenance model-provenance)
                             (some? request-id)
                             (assoc :request-id request-id))})]
                 exclusion-events)
           :command-result/cas
           {:types #{:ontology/claim-deltas-recorded}
            :tags #{[:claim-target target-uuid]}
            :predicate-fn (fn [existing]
                            (= claim-set-version (count (into [] existing))))}})))))

(cp/defcommand :ontology record-consolidation-failure
  "CC-28, the spec's FailureIsVisible: record a reflection call that never
   answered — provider rejection, timeout, exhausted retries, unparseable
   output, caller interruption (SIO-4b). Emits
   :ontology/description-consolidation-failed.

   ONE command per terminal attempt-set; :attempts carries the count. The
   consolidator dispatches it from BOTH reflection paths (claim and legacy
   body). The recent-consolidations read-model folds the event so failures
   consume the same hourly budget successes do (FailuresConsumeBudget)."
  [{{:keys [granularity target-identifier reason error attempts request-id]} :command}]
  {:command-result/events
   [(es/->event
     {:type :ontology/description-consolidation-failed
      :tags #{[:description-target (logic/description-target-uuid granularity target-identifier)]}
      :body (cond-> {:granularity granularity
                     :target-identifier target-identifier
                     :reason reason
                     :attempts attempts
                     :failed-at (logic/now-str)}
              (some? error) (assoc :error error)
              (some? request-id) (assoc :request-id request-id))})]})

(cp/defcommand :ontology record-consolidation-skip
  "PR-2 (EveryConsolidationRequestYieldsAnOutcome): record a deliberate
   non-write as a durable ANSWER — the budget gate's skip
   (:budget-exhausted, carrying the gate's two numbers) and the
   zero-proposal success (:no-operations-proposed, inspection ruling / spec
   tend e8c15571). Emits :ontology/consolidation-skipped — its own event
   type, deliberately NOT a failure record: FailuresConsumeBudget folds
   failure events into the hourly budget, and a budget skip that consumed
   no LLM attempt must not extend the very exhaustion that caused it."
  [{{:keys [target-type target-id reason budget recent-count request-id]} :command}]
  {:command-result/events
   [(es/->event
     {:type :ontology/consolidation-skipped
      :tags #{[:description-target (logic/stable-uuid-from
                                     (str (name target-type) ":" target-id))]}
      :body (cond-> {:target-type target-type
                     :target-id target-id
                     :reason reason
                     :skipped-at (logic/now-str)}
              (some? budget) (assoc :budget budget)
              (some? recent-count) (assoc :recent-count recent-count)
              (some? request-id) (assoc :request-id request-id))})]})

(cp/defcommand :ontology request-consolidation
  "Emit an :ontology/consolidation-requested event for the given target.

   Two paths:

   1. On-demand (:on-demand? true) — always emits. Used by REPL helpers
      and tests that want a consolidation regardless of counter state.

   2. Threshold-driven (:on-demand? false / unset) — uses Grain CAS to
      enforce exactly-once-per-threshold-crossing semantics:

         * Reads the target's lifetime :total source-event count and
           configured threshold from read-models.
         * Derives crossing-number = (quot total threshold) — stable
           across all concurrent handlers within the same threshold
           window of source events.
         * Tags the event with [:crossing <stable-uuid-from-crossing>]
           and CAS-guards on that tag being absent. The first concurrent
           handler's append succeeds; subsequent handlers' appends get
           ::anom/conflict and emit no event.

      This survives burst-event races where N processor handlers all
      observe delta >= threshold before any prior reset has propagated
      to projections — only the first handler's CAS predicate evaluates
      true at append time inside the event store."
  [{{:keys [target-id target-type on-demand?]} :command :as ctx}]
  (let [target-uuid (logic/stable-uuid-from
                      (str (name target-type) ":" target-id))
        threshold-driven? (not on-demand?)
        crossing-uuid (when threshold-driven?
                        (let [total (read-models/get-consolidation-total
                                      ctx target-type target-id)
                              threshold (read-models/get-consolidation-threshold
                                          ctx target-type)
                              crossing-num (quot total threshold)]
                          (logic/stable-uuid-from
                            (str (name target-type) ":" target-id
                                 ":crossing-" crossing-num))))
        event (es/->event
                (cond-> {:type :ontology/consolidation-requested
                         :tags (cond-> #{[:description-target target-uuid]}
                                 crossing-uuid (conj [:crossing crossing-uuid]))
                         :body {:target-type target-type
                                :target-id target-id
                                :on-demand? (boolean on-demand?)
                                :requested-at (logic/now-str)}}))]
    (cond-> {:command-result/events [event]}
      threshold-driven?
      (assoc :command-result/cas
             {:types #{:ontology/consolidation-requested}
              :tags #{[:crossing crossing-uuid]}
              :predicate-fn (fn [existing] (empty? (into [] existing)))}))))

(cp/defcommand :ontology set-consolidation-threshold
  "Set the consolidation threshold for a target-type. Emits
   :ontology/consolidation-threshold-set; the threshold-config read-model
   projects this for runtime threshold lookup."
  [{{:keys [target-type threshold]} :command}]
  {:command-result/events
   [(es/->event
     {:type :ontology/consolidation-threshold-set
      :tags #{[:description-target (logic/stable-uuid-from
                                     (str "threshold:" (name target-type)))]}
      :body {:target-type target-type
             :threshold threshold
             :set-at (logic/now-str)}})]})

(cp/defcommand :ontology set-living-description-enabled
  "Gap-1: flip the system-level opt-in for the Living Description loop.
   When set to true, the writing side of the loop activates — consolidator
   handles requests, threshold processor's consolidation-requested emissions
   are honored, the per-event evaluator runtime auto-executes attached
   judges, and (future C-3) judge feedback feeds into consolidator inputs.
   Default false (consumer must opt in)."
  [{{:keys [enabled?]} :command}]
  {:command-result/events
   [(es/->event
     {:type :ontology/living-description-enabled-set
      :tags #{[:description-target (logic/stable-uuid-from "living-description-enabled-config")]}
      :body {:enabled? enabled?
             :set-at (logic/now-str)}})]})

(cp/defcommand :ontology set-consolidation-budget
  "C-2a-3c: set the hourly consolidation budget for a target-type.
   Emits :ontology/consolidation-budget-set; the budget-config read-model
   projects this for runtime budget lookup. Default 100/hour applies
   when no override has been set."
  [{{:keys [target-type budget]} :command}]
  {:command-result/events
   [(es/->event
     {:type :ontology/consolidation-budget-set
      :tags #{[:description-target (logic/stable-uuid-from
                                     (str "budget:" (name target-type)))]}
      :body {:target-type target-type
             :budget budget
             :set-at (logic/now-str)}})]})

(cp/defcommand :ontology set-evidence-token-budget
  "PR-1 (ADR 0030): set the consolidator's evidence token budget — the
   predicted-prompt-token bound the newest-first evidence window selection
   respects (spec invariant BoundedReflectionEvidence). GLOBAL, not
   per-target-type. Emits :ontology/evidence-token-budget-set; the
   evidence-token-budget read-model projects it for runtime lookup. The
   DERIVED default (read-models/default-evidence-token-budget) applies when
   no override has been set."
  [{{:keys [budget-tokens]} :command}]
  {:command-result/events
   [(es/->event
     {:type :ontology/evidence-token-budget-set
      :tags #{[:description-target (logic/stable-uuid-from "evidence-token-budget-config")]}
      :body {:budget-tokens budget-tokens
             :set-at (logic/now-str)}})]})

(cp/defcommand :ontology record-reflection-evidence-exclusion
  "PR-2 (ADR 0030, spec invariant BoundedReflectionEvidence): record that a
   consolidation's evidence window was CUT by the token budget. ONE compact
   summary event per consolidation-with-exclusions (see the event schema for
   the volume argument); the consolidator dispatches it only when the
   excluded half is non-empty, so a zero-exclusion consolidation emits
   nothing. Emits :ontology/reflection-evidence-excluded."
  [{{:keys [target-type target-id budget-tokens selected-count excluded-count
            predicted-prompt-tokens oldest-excluded-at newest-excluded-at
            request-id]} :command}]
  {:command-result/events
   [(es/->event
     {:type :ontology/reflection-evidence-excluded
      :tags #{[:description-target (logic/stable-uuid-from
                                     (str (name target-type) ":" target-id))]}
      :body (cond-> {:target-type target-type
                     :target-id target-id
                     :budget-tokens budget-tokens
                     :selected-count selected-count
                     :excluded-count excluded-count
                     :predicted-prompt-tokens predicted-prompt-tokens
                     :oldest-excluded-at oldest-excluded-at
                     :newest-excluded-at newest-excluded-at
                     :excluded-at (logic/now-str)}
              (some? request-id) (assoc :request-id request-id))})]})

(cp/defcommand :ontology set-reindex-config
  "C-2b-1: set the global ColBERT re-index config (event-count threshold
   + timer-minutes). Emits :ontology/reindex-config-set; the reindex-config
   read-model projects this for runtime lookup. Defaults (10 events, 5
   minutes) apply when no override has been set."
  [{{:keys [reindex-threshold-events reindex-timer-minutes]} :command}]
  {:command-result/events
   [(es/->event
     {:type :ontology/reindex-config-set
      :tags #{[:description-target (logic/stable-uuid-from "reindex-config")]}
      :body {:reindex-threshold-events reindex-threshold-events
             :reindex-timer-minutes reindex-timer-minutes
             :set-at (logic/now-str)}})]})

(cp/defcommand :ontology assign-task-class
  "C-2c-2 + C-2d-2: record an auto-classification decision. Takes the
   result of `ontology/classify-task` plus the (source-sheet-id,
   source-tick-id, source-node-id) provenance triple and emits
   :ontology/task-classified once per (source-sheet-id, source-tick-id)
   occurrence. The classification machinery (classify-task) is pure and
   runs upstream in the executor wedge.

   C-2d-2 — optional :parent-tree-id forwarded from the walk-down
   classifier when the result is a deep match or fresh-mint under a
   matched ancestor. Omitted on top-level matches and when walk-down
   is disabled."
  [{{:keys [source-sheet-id source-tick-id source-node-id
            assigned-tree-id confidence top-candidates reasoning
            was-fresh-mint? parent-tree-id rerank-failed?
            behavioral-subtrees ranked-candidates assigned-via outcome
            researcher-ownership-epoch classification-context
            domain-verdict domain-label domain-children-considered
            domain-deferral domain-selection merge-verdict]} :command
    :keys [event-store tenant-id]}]
  (let [existing (es/read event-store
                          {:tenant-id tenant-id
                           :types #{:ontology/task-classified}
                           :tags #{[:tick source-tick-id]}})]
    (if (logic/classification-assigned-for-occurrence?
         existing source-sheet-id source-tick-id)
      {:command-result/events []}
      {:command-result/events
       [(es/->event
         {:type :ontology/task-classified
          :tags #{[:tick source-tick-id]
                  [:description-target assigned-tree-id]}
          :body (cond-> {:source-sheet-id source-sheet-id
                         :source-tick-id source-tick-id
                         :source-node-id source-node-id
                         :assigned-tree-id assigned-tree-id
                         :confidence confidence
                         :top-candidates top-candidates
                         :reasoning reasoning
                         :classified-at (logic/now-str)
                         :was-fresh-mint? was-fresh-mint?}
                  (some? researcher-ownership-epoch)
                  (assoc :researcher-ownership-epoch researcher-ownership-epoch)
                  (some? classification-context)
                  (assoc :classification-context classification-context)
                  parent-tree-id (assoc :parent-tree-id parent-tree-id)
                  (some? rerank-failed?) (assoc :rerank-failed? rerank-failed?)
                  (some? behavioral-subtrees)
                  (assoc :behavioral-subtrees behavioral-subtrees)
                  (some? ranked-candidates)
                  (assoc :ranked-candidates ranked-candidates)
                  (some? assigned-via)
                  (assoc :assigned-via assigned-via)
                  (some? outcome)
                  (assoc :outcome outcome)
                  (some? domain-verdict)
                  (assoc :domain-verdict domain-verdict)
                  (some? domain-label)
                  (assoc :domain-label domain-label)
                  (some? domain-children-considered)
                  (assoc :domain-children-considered domain-children-considered)
                  (some? domain-deferral)
                  (assoc :domain-deferral domain-deferral)
                  (some? domain-selection)
                  (assoc :domain-selection domain-selection)
                  (some? merge-verdict)
                  (assoc :merge-verdict merge-verdict))})]
       :command-result/cas
       (logic/task-classification-occurrence-cas
        source-sheet-id source-tick-id source-node-id
        researcher-ownership-epoch)})))

(cp/defcommand :ontology record-tree-class-occurrence
  "RR-19: record the one recurrence fact for a classified researcher
   campaign after it reaches success, failure, or timeout. The closed schema
   excludes blocked, cancelled, and abandoned endings. Campaign identity is
   [source-sheet-id source-tick-id]; retries with fresh command ids are
   successful no-ops, and append-time CAS makes concurrent contenders
   first-writer-wins. The handler accepts only a matching durable
   classification and exact terminal researcher completion; callers cannot
   manufacture recurrence by naming an unverified verdict."
  [{{:keys [source-sheet-id source-tick-id source-node-id
            source-completion-event-id
            assigned-tree-id verdict]} :command
    :keys [event-store tenant-id]}]
  (let [existing (into []
                       (es/read event-store
                                {:tenant-id tenant-id
                                 :types #{:ontology/tree-class-occurrence-recorded
                                          :ontology/task-classified
                                          :sheet/tick-cancelled
                                          :sheet/tree-tick-completed
                                          :sheet/node-execution-completed}
                                 :tags #{[:tick source-tick-id]}}))]
    (if-not (logic/occurrence-admissible?
             existing source-sheet-id source-tick-id source-node-id
             source-completion-event-id assigned-tree-id verdict)
      {:command-result/events []}
      {:command-result/events
       [(es/->event
         {:type :ontology/tree-class-occurrence-recorded
          :tags #{[:tick source-tick-id]
                  [:node source-node-id]
                  [:description-target assigned-tree-id]}
          :body {:source-sheet-id source-sheet-id
                 :source-tick-id source-tick-id
                 :source-node-id source-node-id
                 :source-completion-event-id source-completion-event-id
                 :assigned-tree-id assigned-tree-id
                 :verdict verdict
                 :recorded-at (logic/now-str)}})]
       :command-result/cas
       (logic/tree-class-occurrence-cas
        source-sheet-id source-tick-id source-node-id
        source-completion-event-id assigned-tree-id verdict)})))

(cp/defcommand :ontology report-shape-coherence
  "RR-21 (rule ReportSuccessfulShapeCoherence): after EVERY
   :ontology/tree-class-occurrence-recorded verdict (no threshold — this is
   not the harvest gate), record the class's winning-shape coherence measure
   durably: distinct successful terminal shapes divided by successful
   campaigns, one winning shape per successful campaign (the LAST
   `:status :success` Phase-2 bookend in durable order; see
   `harvest/winning-shape-coherence`). Report-only rollout (ADR 0029 dossier
   G11): this axis has never been witnessed, so it is computed and reported
   without ever gating promotion — see `harvest/harvest-candidate?`.

   INSPECTION FINDING (reproduced, fixed): both `:verdict-occurrences` and the
   coherence measure are bounded to `source-occurrence-event-id` — the
   triggering occurrence event's OWN `:event/id` (UUIDv7, durably time-
   ordered) — via `harvest/class-occurrences-through` /
   `harvest/winning-shape-coherence`'s 4-arity. Computing either number from
   the event store's CURRENT state at PROCESSING time is a scheduling-
   dependent race, not a measure of the occurrence being reported on: a
   backlog (several verdicts landing before the handler processes any of
   them, or delivered out of the handler's own pace) made every report read
   back the FINAL count/ratio instead of its own durable position. Both
   numbers are derived from the SAME bounded snapshot, so they can never
   disagree with each other about 'as of when'.

   Idempotent per occurrence — [tree-class source-sheet-id source-tick-id]:
   retries with fresh command ids are successful no-ops, and append-time CAS
   makes concurrent contenders first-writer-wins, mirroring RR-19's
   `record-tree-class-occurrence`."
  [{{:keys [tree-class source-sheet-id source-tick-id source-occurrence-event-id]} :command
    :keys [event-store tenant-id] :as ctx}]
  (let [existing (into []
                       (es/read event-store
                                {:tenant-id tenant-id
                                 :types #{:ontology/shape-coherence-reported}
                                 :tags #{[:tick source-tick-id]}}))]
    (if (logic/shape-coherence-reported? existing tree-class source-sheet-id source-tick-id)
      {:command-result/events []}
      (let [verdict-occurrences (count (queries/class-occurrences-through
                                         ctx tree-class source-occurrence-event-id))
            {:keys [successful-campaigns successful-shape-observations
                    distinct-successful-shapes ratio status]}
            (queries/winning-shape-coherence
             ctx tree-class logic/default-harvest-config source-occurrence-event-id)]
        {:command-result/events
         [(es/->event
           {:type :ontology/shape-coherence-reported
            :tags #{[:tick source-tick-id] [:description-target tree-class]}
            :body {:tree-class tree-class
                   :source-sheet-id source-sheet-id
                   :source-tick-id source-tick-id
                   :source-occurrence-event-id source-occurrence-event-id
                   :verdict-occurrences verdict-occurrences
                   :successful-campaigns successful-campaigns
                   :successful-shape-observations successful-shape-observations
                   :distinct-successful-shapes distinct-successful-shapes
                   :ratio ratio
                   :status status
                   :maximum-shape-ratio (:max-shapes-ratio logic/default-harvest-config)
                   :recorded-at (logic/now-str)}})]
         :command-result/cas
         (logic/shape-coherence-report-cas tree-class source-sheet-id source-tick-id)}))))

(cp/defcommand :ontology record-task-classification-deferral
  "CC-23, the spec's DeferralIsVisible: record a classification that
   DEFERRED on an axis. On the fitness axes (:colbert-fallback,
   :timeout-fallback) the semantic reranker fell back, fitness is unknown,
   and the wedge withholds assignment. On the domain axis
   (:domain-coverage, RS-3) the verdict could not be resolved: the
   structural assignment STANDS and this deferral rides beside it for the
   same occurrence — at most once per occurrence (a retried tick emits
   nothing new). 'Uncertain' and 'nothing happened' are different facts;
   counting :ontology/task-classification-deferred events PARTITIONED BY
   :fallback-source is the per-axis deferral rate, never an inference from
   missing :ontology/task-classified events and never a count that mixes
   the axes.

   ONE command per deferred axis, dispatched by the classify CALL SITE (the
   C-2c-2 wedge) — the classify fn itself stays a pure decision fn. A
   fitness-axis deferral structurally cannot ride
   :ontology/assign-task-class (it REQUIRES an :assigned-tree-id), which is
   why this is its own command + event.

   The [:tick source-tick-id] tag mirrors :ontology/task-classified so a
   tick's outcome is one tag-query away — a tick may carry an assignment
   AND a domain-axis deferral."
  [{{:keys [source-sheet-id source-tick-id source-node-id
            fallback-source ranked-candidates reasoning
            researcher-ownership-epoch classification-context]} :command
    :keys [event-store tenant-id]}]
  (if (and (= :domain-coverage fallback-source)
           (logic/domain-deferral-recorded-for-occurrence?
            (into [] (es/read event-store
                              {:tenant-id tenant-id
                               :types #{:ontology/task-classification-deferred}
                               :tags #{[:tick source-tick-id]}}))
            source-sheet-id source-tick-id))
    {:command-result/events []}
  (cond->
   {:command-result/events
    [(es/->event
     {:type :ontology/task-classification-deferred
      :tags #{[:tick source-tick-id]}
      :body (cond-> {:source-sheet-id source-sheet-id
                     :source-tick-id source-tick-id
                     :source-node-id source-node-id
                     :fallback-source fallback-source
                     :ranked-candidates ranked-candidates
                     :reasoning reasoning
                     :deferred-at (logic/now-str)}
              (some? researcher-ownership-epoch)
              (assoc :researcher-ownership-epoch researcher-ownership-epoch)
              (some? classification-context)
              (assoc :classification-context classification-context))})]}
   (some? researcher-ownership-epoch)
   (assoc :command-result/cas
          (logic/active-researcher-classification-cas
           source-tick-id source-node-id researcher-ownership-epoch)))))

(cp/defcommand :ontology mint-behavioral-subtree
  "R05c: mint a new behavioral-subtree concept. Returns two events:
   the provenance-tagged audit-trail event and the standard
   tree-description-updated event the R05a processor projects into the
   concept graph. :provenance is MANDATORY — never default; mixing
   agent-minted and human-authored entries breaks the audit trail for
   future C-3 review queues."
  [{{:keys [name body parent-behavior provenance
            minted-by-sheet-id minted-by-tick-id
            harvested-from-tree-class logical-action-identity
            attempt-identity researcher-iteration
            attempt-ordinal ownership-epoch]} :command}]
  (when (and harvested-from-tree-class logical-action-identity)
    (throw (ex-info
            (str "mint-behavioral-subtree cannot combine the harvested "
                 "tree-class fence with a researcher logical-action fence")
            {:cognitect.anomalies/category :cognitect.anomalies/incorrect
             :harvested-from-tree-class harvested-from-tree-class
             :logical-action-identity logical-action-identity})))
  (let [body-scope (:scope body)]
    (when (and (some? body-scope) (not= body-scope :behavioral-subtree))
      (throw (ex-info
               (str "mint-behavioral-subtree rejects body with :scope " body-scope
                    "; mint affordance only routes to :behavioral-subtree.")
               {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                :body-scope body-scope}))))
  (let [identity-bytes (.getBytes (str "mint:" name ":" parent-behavior) "UTF-8")
        target-id (java.util.UUID/nameUUIDFromBytes identity-bytes)
        minted-at (logic/now-str)
        stamped-body (cond-> (assoc body :scope :behavioral-subtree)
                       parent-behavior (assoc :parent-behavior parent-behavior))
        harvest-crossing (when harvested-from-tree-class
                           (second (logic/harvested-tree-class-tag harvested-from-tree-class)))
        logical-action-tag
        (when logical-action-identity
          (logic/researcher-logical-action-tag logical-action-identity))]
    (cond->
     {:command-result/events
       [(es/->event
          {:type :ontology/behavioral-subtree-minted
           :tags (cond-> #{[:behavioral-subtree-minted target-id]}
                   harvest-crossing (conj [:harvested-tree-class harvest-crossing])
                   logical-action-tag (conj logical-action-tag))
           :body (cond-> {:target-id target-id
                          :name name
                          :provenance provenance
                          :minted-at minted-at}
                   parent-behavior   (assoc :parent-behavior parent-behavior)
                   minted-by-sheet-id (assoc :minted-by-sheet-id minted-by-sheet-id)
                   minted-by-tick-id  (assoc :minted-by-tick-id minted-by-tick-id)
                   logical-action-identity
                   (assoc :logical-action-identity logical-action-identity
                          :attempt-identity attempt-identity
                          :researcher-iteration researcher-iteration)
                   (some? attempt-ordinal) (assoc :attempt-ordinal attempt-ordinal)
                   (some? ownership-epoch) (assoc :ownership-epoch ownership-epoch)
                   harvested-from-tree-class (assoc :harvested-from-tree-class
                                                    harvested-from-tree-class))})
        (es/->event
          {:type :ontology/tree-description-updated
           :tags #{[:description-target target-id]}
           :body {:target-type :tree-fingerprint
                  :target-id target-id
                  :body stamped-body
                  :recorded-at minted-at}})]}
     harvest-crossing
     (assoc :command-result/cas
            {:types #{:ontology/behavioral-subtree-minted}
             :tags #{[:harvested-tree-class harvest-crossing]}
             :predicate-fn logic/no-events?})

     logical-action-tag
     (assoc :command-result/cas
            {:types #{:ontology/behavioral-subtree-minted}
             :tags #{logical-action-tag}
             :predicate-fn logic/no-events?}))))

(cp/defcommand :ontology mark-mint-reindex-forced
  "RR-24: durable at-most-once marker recording that the forced ColBERT
   reindex triggered by one specific :ontology/behavioral-subtree-minted
   event id has already run. CAS'd on that event id so a redelivered
   trigger conflicts and the caller skips the (expensive) rebuild instead
   of re-paying it."
  [{{:keys [minted-event-id]} :command}]
  {:command-result/events
   [(es/->event
     {:type :ontology/mint-reindex-forced
      :tags #{(logic/mint-reindex-forced-tag minted-event-id)}
      :body {:minted-event-id minted-event-id :forced-at (logic/now-str)}})]
   :command-result/cas
   {:types #{:ontology/mint-reindex-forced}
    :tags #{(logic/mint-reindex-forced-tag minted-event-id)}
    :predicate-fn logic/no-events?}})

(cp/defcommand :site register-site
  "Register a new apartment listing site.

   Checks if site already exists by domain.
   If exists, returns existing site-id without emitting event.
   If new, emits site-registered event with initial trust score."
  [{{:keys [domain display-name category discovered-via
            url-pattern requires-headed known-challenges notes]} :command
    :keys [event-store] :as ctx}]
  (let [existing (read-models/get-site-by-domain ctx domain)]
    (if existing
      {:command-result/message "Site already registered"
       :command-result/data {:site-id (:site-id existing)
                             :domain domain}}
      (let [site-id (logic/generate-uuid)
            now (logic/now-str)]
        {:command-result/events
         [(es/->event
           {:type :site/registered
            :tags #{[:site site-id]}
            :body (cond-> {:site-id site-id
                           :domain domain
                           :display-name display-name
                           :category category
                           :discovered-via discovered-via
                           :registered-at now}
                    url-pattern (assoc :url-pattern url-pattern)
                    (some? requires-headed) (assoc :requires-headed requires-headed)
                    (seq known-challenges) (assoc :known-challenges (vec known-challenges))
                    notes (assoc :notes notes))})]
         :command-result/data {:site-id site-id
                               :domain domain}}))))

(cp/defcommand :site update-site-trust
  "Update trust score for a site based on extraction success/failure.

   Trust score calculation:
   - Success: trust = (current * count + 1) / (count + 1)
   - Failure: trust = (current * count + 0) / (count + 1)

   This is a moving average that weighs recent results appropriately."
  [{{:keys [domain success? listings-extracted]} :command
    :keys [event-store] :as ctx}]
  (let [existing (read-models/get-site-by-domain ctx domain)]
    (if-not existing
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message (str "Site not found: " domain)}

      (let [now (logic/now-str)
            current-trust (or (:trust-score existing) 0.5)
            current-count (or (:extraction-count existing) 0)
            new-count (inc current-count)
            new-trust (/ (+ (* current-trust current-count)
                            (if success? 1.0 0.0))
                         new-count)]
        {:command-result/events
         [(es/->event
           {:type :site/trust-updated
            :tags #{[:site (:site-id existing)]}
            :body (cond-> {:site-id (:site-id existing)
                           :domain domain
                           :trust-score new-trust
                           :extraction-count new-count
                           :updated-at now}
                    success? (assoc :last-success-at now)
                    (not success?) (assoc :last-failure-at now))})]
         :command-result/data {:domain domain
                               :trust-score new-trust
                               :extraction-count new-count}}))))

(cp/defcommand :site record-site-pattern
  "Record a learned navigation/extraction pattern for a site.

   Patterns are site-specific tactics learned over time:
   - navigation: How to navigate to listings page
   - search: How to use search filters
   - extraction: Selectors and strategies for extracting listings
   - bot-bypass: Techniques for avoiding bot detection
   - pagination: How to navigate between pages"
  [{{:keys [domain pattern-type pattern-data confidence]} :command
    :keys [event-store] :as ctx}]
  (let [existing (read-models/get-site-by-domain ctx domain)]
    (if-not existing
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message (str "Site not found: " domain)}

      (let [now (logic/now-str)
            pattern-type-kw (if (keyword? pattern-type)
                              pattern-type
                              (keyword pattern-type))]
        {:command-result/events
         [(es/->event
           {:type :site/pattern-learned
            :tags #{[:site (:site-id existing)]}
            :body {:site-id (:site-id existing)
                   :domain domain
                   :pattern-type pattern-type-kw
                   :pattern-data pattern-data
                   :confidence (or confidence 0.8)
                   :learned-at now}})]
         :command-result/data {:domain domain
                               :pattern-type pattern-type-kw}}))))
