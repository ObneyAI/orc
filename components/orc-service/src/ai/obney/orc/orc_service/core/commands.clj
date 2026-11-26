(ns ai.obney.orc.orc-service.core.commands
  "Behavior Tree Sheet command handlers.

   All commands follow the Grain pattern:
   - Take context including event-store and command data
   - Return {:command-result/events [...]} on success
   - Return cognitect anomaly on failure
   - Last write wins (no optimistic concurrency)"
  (:require [ai.obney.grain.anomalies.interface :as anomalies]
            [ai.obney.grain.command-processor-v2.interface :as cp :refer [defcommand]]
            [ai.obney.grain.event-store-v3.interface :as es :refer [->event]]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.integrations.interface :as integrations]
            [ai.obney.orc.orc-service.core.logic :as logic]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as u]
            [malli.core :as m]
            [malli.error :as me])
  (:import [java.util UUID]))

(defn extract-tree-metadata
  "Extract metadata from a built sheet.

   Analyzes the tree structure to identify:
   - Node types used
   - Structural patterns (validation, retry, parallel, etc.)
   - Inferred capabilities
   - Inferred success patterns

   Args:
   - event-store: Event store for reading sheet data
   - sheet-id: UUID of the sheet to analyze

   Returns map with extracted metadata."
  [{:keys [event-store tenant-id] :as ctx} sheet-id]
  (let [sheet (rm/get-sheet ctx sheet-id)
        nodes (rm/get-nodes-for-sheet ctx sheet-id)
        node-types (set (map logic/classify-node-type nodes))
        node-names (map (comp str/lower-case str :name) nodes)]

    {:sheet-id sheet-id
     :name (:name sheet)
     :node-types (vec node-types)
     :node-count (count nodes)

     :has-validation? (boolean (some #(str/includes? % "valid") node-names))
     :has-retry? (contains? node-types :fallback)
     :has-parallel? (contains? node-types :parallel)
     :has-map-each? (contains? node-types :map-each)

     :capabilities (cond-> []
                     (contains? node-types :repl-researcher) (conj "tool-use")
                     (contains? node-types :parallel) (conj "parallel-processing")
                     (contains? node-types :map-each) (conj "batch-iteration")
                     (some #(str/includes? % "valid") node-names) (conj "validation-loop")
                     (some #(str/includes? % "research") node-names) (conj "research")
                     (some #(str/includes? % "verify") node-names) (conj "verification"))

     :patterns (cond-> []
                 (contains? node-types :parallel)
                   (conj "success:ParallelGathering")
                 (some #(re-find #"schema|explicit" %) node-names)
                   (conj "success:ExplicitSchema")
                 (contains? node-types :fallback)
                   (conj "success:RetryWithFallback")
                 (some #(str/includes? % "valid") node-names)
                   (conj "success:ValidationLoop"))

     :problem-types []
     :description nil
     :execution-count nil
     :avg-score nil
     :last-executed nil
     :extracted-at (str (java.time.Instant/now))}))

;; --- Execution lease ---

;; ---------------------------------------------------------------------------
;; Renewal
;; ---------------------------------------------------------------------------

(defn renew!
  [context {:keys [sheet-id tick-id node-id exec-context start-event-id]}]
  (try
    (let [{:keys [lease-owner lease-expires-at]} (logic/fields context)]
      (cp/process-command
       (assoc context :command
              (cond-> {:command/id (random-uuid)
                       :command/timestamp (time/now)
                       :command/name :sheet/renew-node-execution-lease
                       :sheet-id sheet-id
                       :tick-id tick-id
                       :node-id node-id
                       :start-event-id start-event-id
                       :lease-owner lease-owner
                       :lease-expires-at lease-expires-at}
                (seq exec-context) (assoc :exec-context exec-context)))))
    (catch Throwable t
      (u/log :ai.obney.orc.orc-service.core.execution-lease/lease-renewal-failed :tick-id tick-id :node-id node-id
             :error (ex-message t)))))

(defn- lifecycle-view
  "{:ended? :start-id} for a queued entry, from the durable tick events: the
   latest open start of the node's execution that this worker owns."
  [{:keys [context tick-id node-id exec-context owner]}]
  (let [events (into [] (es/read (:event-store context)
                                 (cond-> {:tags #{[:tick tick-id]}}
                                   (:tenant-id context) (assoc :tenant-id (:tenant-id context)))))
        target [node-id (or exec-context {})]
        ended-tick? (some #(contains? #{:sheet/tree-tick-completed :sheet/tick-cancelled}
                                      (:event/type %))
                          events)
        open (reduce (fn [open e]
                       (case (:event/type e)
                         :sheet/node-execution-started
                         (if (= target (logic/execution-key e)) (assoc open :start e) open)
                         :sheet/node-execution-completed
                         (if (= target (logic/execution-key e)) (dissoc open :start) open)
                         open))
                     {} events)
        start (:start open)]
    {:ended? (boolean ended-tick?)
     :start-id (when (and start (= owner (:lease-owner start))) (:event/id start))}))

(defn- sweep-entry!
  "Renew one queued entry. Returns true to keep it, false to drop it."
  [k {:keys [context tick-id node-id exec-context created-ns lease-ms] :as entry}]
  (try
    (let [{:keys [ended? start-id]} (lifecycle-view entry)]
      (cond
        ended? false
        start-id (do (renew! context {:sheet-id (:sheet-id entry) :tick-id tick-id
                                      :node-id node-id :exec-context exec-context
                                      :start-event-id start-id})
                     true)
        :else (< (quot (- (System/nanoTime) created-ns) 1000000)
                 (* logic/unseen-grace-multiple lease-ms))))
    (catch Throwable t
      (u/log :ai.obney.orc.orc-service.core.execution-lease/queued-lease-sweep-failed :tick-id tick-id :node-id node-id
             :error (ex-message t))
      false)))

(defn- sweep! []
  (doseq [[k entry] @logic/queued]
    (when-not (sweep-entry! k entry)
      (swap! logic/queued dissoc k))))

(defn- run-renewer []
  (loop [last-sweep (System/nanoTime)]
    (Thread/sleep ^long logic/poll-ms)
    (let [entries (vals @logic/queued)
          interval-ms (if (seq entries)
                        (apply min (map #(max 1 (quot (:lease-ms %) 3)) entries))
                        logic/poll-ms)
          now (System/nanoTime)
          due? (>= (quot (- now last-sweep) 1000000) interval-ms)
          _ (when due? (sweep!))]
      (if (locking logic/queue-lock
            (if (empty? @logic/queued)
              (do (reset! logic/queue-renewer nil) false)
              true))
        (recur (if due? (System/nanoTime) last-sweep))
        nil))))

(defn- ensure-renewer! []
  (locking logic/queue-lock
    (when-not (logic/renewer-alive?)
      (reset! logic/queue-renewer
              (doto (Thread. ^Runnable run-renewer "orc-queued-lease-renewer")
                (.setDaemon true)
                (.start))))))

(defn track-queued!
  "Record that this worker stamped a start for `node` and keeps it owned while
   it waits to run. No-op for nodes that are not leased."
  [context node {:keys [sheet-id tick-id node-id inputs]}]
  (when (and (logic/leased-node? node) (:event-store context))
    (let [exec-context (logic/exec-context inputs)
          k (logic/queued-key tick-id node-id exec-context)]
      (swap! logic/queued assoc k
             {:context (dissoc context :command :event :command-result)
              :sheet-id sheet-id :tick-id tick-id :node-id node-id
              :exec-context exec-context
              :owner (logic/instance-id context)
              :lease-ms (logic/lease-ms context)
              :created-ns (System/nanoTime)})
      (ensure-renewer!))))

(defn stamp
  "Add the ownership lease to a leased node's start body (leaf, delegate,
   researcher) and track the start as queued so its lease keeps being renewed
   until it begins; composites and other node kinds are not leased."
  [context node body]
  (if (logic/leased-node? node)
    (do (track-queued! context node body)
        (merge body (logic/fields context)))
    body))

;; --- Emitted model authority ---

(defn- read-events [ctx tags]
  (let [r (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :tags tags :types logic/emitted-model-authority-fact-types})]
    (when (:cognitect.anomalies/category r)
      (throw (ex-info "Cannot establish emitted model authority" {:failure-kind :model-plan-incompatible :reason :emitted-authority-read-failed})))
    (into [] r)))

(defn fact-state [ctx owner campaign]
  (let [tag (logic/campaign-tag (:sheet-id campaign) (:tick-id campaign) (:node-id campaign))
        indexed (read-events ctx #{tag})
        old (logic/emitted-model-authority-merge-facts (read-events ctx #{[:tick (:tick-id owner)]})
                         (read-events ctx #{[:tick (:tick-id campaign)]}))
        legacy (filterv #(not (contains? (:event/tags %) tag)) old)]
    {:legacy legacy :facts (logic/emitted-model-authority-merge-facts legacy indexed)}))

(defcommand :sheet authorize-emitted-model-work
  {:authorized? (fn [ctx] (and (:tenant-id ctx) (:auth-claims ctx)))}
  [ctx]
  (let [owner (select-keys (:command ctx) logic/emitted-model-authority-owner-keys)
        campaign (rm/researcher-campaign ctx (:tick-id owner))
        state (when campaign (fact-state ctx owner campaign))
        decision (when campaign (logic/assess owner campaign (:facts state)))]
    (case (:status decision)
      (:original :authorized) {:command-result/events []}
      :ready {:command-result/events
              [(rm/event ctx {:type logic/event-type
                 :tags #{[:sheet (:sheet-id owner)] [:tick (:tick-id owner)]
                         (logic/campaign-tag (:sheet-id campaign) (:tick-id campaign) (:node-id campaign))}
                 :body (:authority decision)})]
              :command-result/cas
              {:tags #{(logic/campaign-tag (:sheet-id campaign) (:tick-id campaign) (:node-id campaign))}
               :types logic/emitted-model-authority-fact-types
               :predicate-fn (fn [stream]
                               (let [fresh (logic/assess owner campaign (logic/emitted-model-authority-merge-facts (:legacy state) (into [] stream)))]
                                 (and (= :ready (:status fresh)) (= (:authority decision) (:authority fresh)))))}}
      {:cognitect.anomalies/category :cognitect.anomalies/conflict
       :cognitect.anomalies/message "Emitted model work has no current recovery authority"
       :failure-kind :model-plan-incompatible :reason (or (:reason decision) :emitted-campaign-lineage-unavailable)})))

;; --- Model call journal ---

(defn events [ctx tags types]
  (let [result (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :tags tags :types types})]
    (when (:cognitect.anomalies/category result)
      (throw (ex-info "Cannot read durable model history"
                      {:failure-kind :model-plan-incompatible :reason :model-history-unavailable})))
    (into [] result)))

(defn- tick-options [ctx owner]
  (:options (first (filter #(= (:tick-id owner) (:tick-id %))
                          (events ctx #{[:tick (:tick-id owner)]} #{:sheet/tree-tick-started})))))

(defn scope [ctx owner]
  (let [o (tick-options ctx owner) child? (and (= :subcall (:role owner)) (:researcher-campaign-tick-id o))]
    [(if child? (:researcher-campaign-sheet-id o) (:sheet-id owner))
     (if child? (:researcher-campaign-tick-id o) (:tick-id owner))
     (if child? (:researcher-campaign-node-id o) (:node-id owner))
     (:role owner) (if child? (or (:researcher-campaign-exec-context o) {}) (:exec-context owner))
     (:configuration-id owner)]))

(defn specialist-topology [ctx owner]
  (let [campaign (rm/researcher-campaign ctx (:tick-id owner))
        campaign-owner (if campaign
                         (merge (select-keys campaign [:sheet-id :tick-id :node-id :exec-context]) {:role :subcall})
                         (select-keys owner [:sheet-id :tick-id :node-id :exec-context :role]))]
    {:emitted? (boolean campaign) :child-tick-id (:tick-id owner) :campaign campaign :role-owner campaign-owner
     :tag (logic/campaign-tag (:sheet-id campaign-owner) (:tick-id campaign-owner) (:node-id campaign-owner))}))

(defn specialist-state [ctx owner types]
  (let [{:keys [tag role-owner] :as topology} (specialist-topology ctx owner)
        indexed (events ctx #{tag} types)
        old (logic/model-call-journal-merge-facts (events ctx #{[:tick (:tick-id owner)]} types)
                         (when (not= (:tick-id owner) (:tick-id role-owner))
                           (events ctx #{[:tick (:tick-id role-owner)]} types)))
        legacy (filterv #(not (contains? (:event/tags %) tag)) old)]
    {:topology topology :legacy legacy :facts (logic/model-call-journal-merge-facts legacy indexed)}))

(defn- owns? [ctx owner]
  (if (= :subcall (:role owner))
    (let [{:keys [topology facts]} (specialist-state ctx owner logic/specialist-event-types)]
      (logic/specialist-owned? topology owner facts))
    (logic/owns-facts? owner (events ctx #{[:tick (:tick-id owner)]}
                               #{:sheet/node-execution-started :sheet/model-plan-retained logic/frontier-event-type}))))

(defn- journal-event [ctx owner definition]
  (->event (cond-> definition
             (= :subcall (:role owner))
             (update :tags conj (:tag (specialist-topology ctx owner))))))

(defn- specialist-cas [ctx owner original fresh? index]
  (let [{:keys [topology legacy facts]} (specialist-state ctx owner logic/specialist-event-types)
        expected (logic/specialist-plan-facts topology facts owner nil)
        selection-scope (scope ctx owner)]
    {:tags #{(:tag topology)} :types logic/specialist-event-types
     :predicate-fn
     (fn [stream]
       (let [xs (logic/model-call-journal-merge-facts legacy (into [] stream))]
         (and (logic/specialist-owned? topology owner xs)
              (logic/specialist-configuration-admitted? topology owner xs fresh? index)
              (= expected (logic/specialist-plan-facts topology xs owner nil))
              ((:predicate-fn original)
               (filterv #(and (contains? (:types original) (:event/type %))
                              (if (= #{(logic/call-tag owner)} (:tags original))
                                (and (logic/same-operation? owner %) (= (:configuration-id owner) (:configuration-id %)))
                                (= selection-scope (:selection-scope %)))) xs)))))}))

(defn- retain-specialist-plan [ctx owner scope-id]
  (let [{:keys [topology legacy facts]} (specialist-state ctx owner logic/specialist-event-types)
        selected (logic/specialist-plan-facts topology facts owner scope-id)
        plans (logic/specialist-plans topology selected)
        retained (first (filter #(= scope-id (:plan-scope-id %)) plans))
        configurations (set (map :configuration-id plans))
        work (filter #(and (contains? logic/model-call-journal-event-types (:event/type %))
                           (not= :sheet/model-plan-retained (:event/type %))) selected)
        incompatible? (or (some #(not (contains? configurations (:configuration-id %))) work)
                          (some #(and (logic/same-operation? owner %)
                                      (or (nil? retained) (not= (:configuration-id retained) (:configuration-id %)))) work))
        plan (get-in ctx [:command :plan])
        retention-eligible? (fn [xs]
                              (or (logic/fresh-specialist-work? topology owner xs)
                                  (and (:emitted? topology)
                                       (logic/new-plan-retention-eligible?
                                        owner (:campaign topology) xs))))
        provisional? (not (logic/fresh-specialist-work? topology owner facts))]
    (cond
      (not (logic/specialist-owned? topology owner facts)) (logic/model-call-journal-rejected "Model plan does not own the current node start")
      (not (retention-eligible? facts)) (logic/model-call-journal-rejected "Model plan does not own the current campaign")
      (not= (:configuration-id owner) (logic/retained-configuration-id plan)) (logic/model-call-journal-rejected "Model plan configuration identity does not match its retained data")
      incompatible? (logic/model-call-journal-rejected "Historical model work already owns this operation")
      (and provisional? retained (not= (:configuration-id owner) (:configuration-id retained)))
      (logic/model-call-journal-rejected "An existing retained plan owns this configuration scope")
      retained {:command-result/events []}
      :else
      {:command-result/events
       [(journal-event ctx owner {:type :sheet/model-plan-retained
                                  :tags #{[:sheet (:sheet-id owner)] [:tick (:tick-id owner)] (logic/call-tag owner)}
                                  :body (assoc owner :selection-scope (scope ctx owner) :plan-scope-id scope-id :plan plan)})]
       :command-result/cas
       {:tags #{(:tag topology)} :types logic/specialist-event-types
        :predicate-fn (fn [stream]
                        (let [xs (logic/model-call-journal-merge-facts legacy (into [] stream))]
                          (and (logic/specialist-owned? topology owner xs)
                               (retention-eligible? xs)
                               (= (:configuration-id owner) (logic/retained-configuration-id plan))
                               (= selected (logic/specialist-plan-facts topology xs owner scope-id)))))}})))

(defcommand :sheet retain-model-plan {:authorized? logic/model-call-journal-authorized?} [ctx]
  (if (= :subcall (get-in ctx [:command :role]))
    (retain-specialist-plan ctx (select-keys (:command ctx) logic/model-call-journal-owner-keys) (get-in ctx [:command :plan-scope-id]))
  (let [owner (select-keys (:command ctx) logic/model-call-journal-owner-keys)
        scope-id (get-in ctx [:command :plan-scope-id])
        types logic/admission-event-types
        facts (logic/plan-facts (events ctx #{[:tick (:tick-id owner)]} types) owner scope-id)
        latest (last (filter #(= :sheet/node-execution-started (:event/type %)) facts))
        retained (first (filter #(and (= :sheet/model-plan-retained (:event/type %))
                                     (or (= :node (:role owner)) (= scope-id (:plan-scope-id %)))) facts))
        journal-facts (filter #(and (contains? logic/model-call-journal-event-types (:event/type %))
                                    (not= :sheet/model-plan-retained (:event/type %))) facts)
        incompatible-history?
        (if (= :subcall (:role owner))
          (let [retained-configs (set (map :configuration-id (logic/inline-role-plans owner facts)))
                current-request (filter #(logic/same-operation? owner %) journal-facts)]
            (or (some #(not (contains? retained-configs (:configuration-id %))) journal-facts)
                (and (seq current-request)
                     (or (nil? retained)
                         (some #(not= (:configuration-id retained) (:configuration-id %)) current-request)))))
          (and (seq journal-facts)
               (or (nil? retained)
                   (some #(not= (:configuration-id retained) (:configuration-id %)) journal-facts))))]
    (cond
      (not (and (= (:node-start-id owner) (:event/id latest))
                (= (:sheet-id owner) (:sheet-id latest))
                (or (= :node (:role owner))
                    (and (pos-int? (:ownership-epoch owner))
                         (= (:ownership-epoch owner)
                            (:ownership-epoch (last (filter #(logic/prime-frontier? owner %) facts))))))))
      (logic/model-call-journal-rejected "Model plan does not own the current node start")
      (not (#{:node :prime :subcall} (:role owner))) (logic/model-call-journal-rejected "Unsupported retained model role")
      (not= (:configuration-id owner) (logic/retained-configuration-id (get-in ctx [:command :plan])))
      (logic/model-call-journal-rejected "Model plan configuration identity does not match its retained data")
      incompatible-history?
      (logic/model-call-journal-rejected "Historical model work already owns this operation")
      retained {:command-result/events []}
      :else
      {:command-result/events
       [(->event {:type :sheet/model-plan-retained
                  :tags #{[:sheet (:sheet-id owner)] [:tick (:tick-id owner)] (logic/call-tag owner)}
                  :body (assoc owner :plan-scope-id scope-id :plan (get-in ctx [:command :plan]))})]
       :command-result/cas {:tags #{[:tick (:tick-id owner)]} :types types
                            :predicate-fn (fn [stream]
                                            (let [xs (into [] stream)]
                                              (and (logic/owns-facts? owner xs)
                                                   (= facts (logic/plan-facts xs owner scope-id)))))}}))))

(defn call-events [ctx owner] (events ctx #{(logic/call-tag owner)} logic/model-call-journal-event-types))

(defn- parent-inline-role? [ctx owner]
  (and (= :subcall (:role owner))
       (nil? (:researcher-campaign-node-id (tick-options ctx owner)))))

(defn- prime-owned-cas [ctx owner cas & [fresh-request? candidate-index]]
  (if (= :subcall (:role owner))
    (specialist-cas ctx owner cas fresh-request? candidate-index)
  (if-not (or (= :prime (:role owner)) (parent-inline-role? ctx owner)) cas
    (let [inline? (parent-inline-role? ctx owner)
          facts (logic/plan-facts (events ctx #{[:tick (:tick-id owner)]} logic/admission-event-types) owner nil)
          selection-scope (scope ctx owner)]
      {:tags #{[:tick (:tick-id owner)]} :types logic/admission-event-types
       :predicate-fn (fn [stream]
                       (let [xs (into [] stream)]
                         (and (logic/owns-facts? owner xs)
                              (or (not inline?) (logic/inline-configuration-admitted? owner xs fresh-request? candidate-index))
                              (= facts (logic/plan-facts xs owner nil))
                              ((:predicate-fn cas)
                               (filterv #(and (contains? (:types cas) (:event/type %))
                                              (if (= #{(logic/call-tag owner)} (:tags cas))
                                                (and (logic/same-operation? owner %)
                                                     (= (:configuration-id owner) (:configuration-id %)))
                                                (= selection-scope (:selection-scope %)))) xs)))))}))))

(defn- append [ctx owner type body predicate]
  (let [s (scope ctx owner) tags #{[:sheet (:sheet-id owner)] [:tick (:tick-id owner)]
                                 (logic/call-tag owner) (logic/scope-tag s)}]
    {:command-result/events [(journal-event ctx owner {:type type :tags tags
                                       :body (merge owner {:selection-scope s} body)})]
     :command-result/cas (prime-owned-cas ctx owner
                          {:tags #{(logic/call-tag owner)} :types logic/model-call-journal-event-types
                           :predicate-fn #(predicate (into [] %))}
                          (and (= :sheet/model-request-recorded type)
                               (= :started (get-in body [:request :phase])))
                          (or (:candidate-index body) (get-in body [:request :candidate-index])
                              (get-in body [:entry :candidate-index])))}))

(defcommand :sheet claim-model-candidate {:authorized? logic/model-call-journal-authorized?} [ctx]
  (let [owner (select-keys (:command ctx) logic/model-call-journal-owner-keys) index (get-in ctx [:command :candidate-index])
        inline? (parent-inline-role? ctx owner)
        ordinary? (#{:node :prime} (:role owner))
        facts (when ordinary?
                (logic/plan-facts (events ctx #{[:tick (:tick-id owner)]} logic/admission-event-types) owner nil))
        xs (if ordinary?
             (filterv #(and (contains? logic/model-call-journal-event-types (:event/type %))
                            (logic/same-operation? owner %)
                            (= (:configuration-id owner) (:configuration-id %))) facts)
             (call-events ctx owner))
        same (last (logic/candidate-events xs index))
        incompatible-plan? (if (= :subcall (:role owner))
                             (let [{:keys [topology facts]} (specialist-state ctx owner logic/specialist-event-types)]
                               (not (logic/specialist-configuration-admitted? topology owner facts false index)))
                             (some #(and (= :sheet/model-plan-retained (:event/type %))
                                         (not= (:configuration-id owner) (:configuration-id %))) facts))]
    (cond
      (not (owns? ctx owner)) (logic/model-call-journal-rejected "Model candidate does not own the current node start")
      incompatible-plan? (logic/model-call-journal-rejected "Retained model plan already owns this operation")
      (= :sheet/model-candidate-recorded (:event/type same)) (logic/model-call-journal-rejected "Model candidate already completed")
      (= (:node-start-id owner) (:node-start-id same)) (assoc (logic/model-call-journal-rejected "Model candidate already claimed by this node start") :model-call-in-flight? true)
      :else (cond-> (append ctx owner :sheet/model-candidate-claimed {:candidate-index index}
                            #(= xs %))
              ordinary?
              (assoc :command-result/cas
                     {:tags #{[:tick (:tick-id owner)]} :types logic/admission-event-types
                      :predicate-fn (fn [stream]
                                      (let [xs (into [] stream)]
                                        (and (logic/owns-facts? owner xs)
                                             (or (not inline?) (logic/inline-configuration-admitted? owner xs false index))
                                             (= facts (logic/plan-facts xs owner nil)))))})))))

(defcommand :sheet record-model-candidate {:authorized? logic/model-call-journal-authorized?} [ctx]
  (let [owner (select-keys (:command ctx) logic/model-call-journal-owner-keys) entry (get-in ctx [:command :entry])
        xs (call-events ctx owner) same (last (logic/candidate-events xs (:candidate-index entry)))]
    (cond
      (not (owns? ctx owner)) (logic/model-call-journal-rejected "Model outcome does not own the current node start")
      (and (= :sheet/model-candidate-recorded (:event/type same)) (= entry (:entry same))) {:command-result/events []}
      (not (and (= :sheet/model-candidate-claimed (:event/type same))
                 (= (:node-start-id owner) (:node-start-id same)))) (logic/model-call-journal-rejected "Model outcome requires its exact candidate claim")
      :else (append ctx owner :sheet/model-candidate-recorded {:entry entry} #(= xs %)))))

(defcommand :sheet record-model-request {:authorized? logic/model-call-journal-authorized?} [ctx]
  (let [owner (select-keys (:command ctx) logic/model-call-journal-owner-keys) request (get-in ctx [:command :request])
        xs (call-events ctx owner) candidate (last (logic/candidate-events xs (:candidate-index request)))
        previous (filter #(= (:request-id request) (get-in % [:request :request-id])) xs)
        matching (some #(when (= (:phase request) (get-in % [:request :phase])) %) previous)]
    (cond
      (not (owns? ctx owner)) (logic/model-call-journal-rejected "Model request does not own the current node start")
      matching (if (= request (:request matching)) {:command-result/events []}
                   (logic/model-call-journal-rejected "Model request receipt conflicts with its recorded phase"))
      (not (and (= :sheet/model-candidate-claimed (:event/type candidate))
                 (= (:node-start-id owner) (:node-start-id candidate)))) (logic/model-call-journal-rejected "Model request requires its exact candidate claim")
      (and (= :completed (:phase request)) (empty? previous)) (logic/model-call-journal-rejected "Model request completion requires its start")
      :else (append ctx owner :sheet/model-request-recorded {:request request} #(= xs %)))))

(defcommand :sheet expose-model-candidate-output {:authorized? logic/model-call-journal-authorized?} [ctx]
  (let [owner (select-keys (:command ctx) logic/model-call-journal-owner-keys) index (get-in ctx [:command :candidate-index])
        xs (call-events ctx owner) same (last (logic/candidate-events xs index))]
    (cond
      (not (owns? ctx owner)) (logic/model-call-journal-rejected "Model stream does not own the current node start")
      (some #(= :sheet/model-candidate-output-exposed (:event/type %)) xs) {:command-result/events []}
      (not (and (= :sheet/model-candidate-claimed (:event/type same))
                 (= (:node-start-id owner) (:node-start-id same)))) (logic/model-call-journal-rejected "Visible output requires its exact candidate claim")
      :else (append ctx owner :sheet/model-candidate-output-exposed {:candidate-index index} #(= xs %)))))

(defcommand :sheet select-model-candidate {:authorized? logic/model-call-journal-authorized?} [ctx]
  (let [owner (select-keys (:command ctx) logic/model-call-journal-owner-keys) index (get-in ctx [:command :candidate-index])
        same (last (logic/candidate-events (call-events ctx owner) index)) s (scope ctx owner)
        selected (events ctx #{(logic/scope-tag s)} #{:sheet/model-candidate-selected})
        floor (reduce max 0 (map :candidate-index selected))]
    (cond
      (not (owns? ctx owner)) (logic/model-call-journal-rejected "Model selection does not own the current node start")
      (not= :success (get-in same [:entry :status])) (logic/model-call-journal-rejected "Model selection requires a successful candidate outcome")
      (<= index floor) {:command-result/events []}
      :else {:command-result/events [(journal-event ctx owner {:type :sheet/model-candidate-selected
                                              :tags #{[:sheet (:sheet-id owner)] [:tick (:tick-id owner)]
                                                      (logic/call-tag owner) (logic/scope-tag s)}
                                              :body (assoc owner :selection-scope s :candidate-index index)})]
             :command-result/cas (prime-owned-cas ctx owner
                                  {:tags #{(logic/scope-tag s)} :types #{:sheet/model-candidate-selected}
                                   :predicate-fn #(= floor (reduce (fn [m e] (max m (:candidate-index e))) 0 %))}
                                  false index)})))

;; --- Provider observations ---

(defn- invocation-events [ctx invocation-id]
  (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)
                                      :tags #{[:provider-invocation invocation-id]}
                                      :types logic/provider-observations-event-types})))

(defn canonical-start [ctx tick-id node-start-id]
  (some #(when (= node-start-id (:event/id %)) %)
        (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)
                                            :tags #{[:tick tick-id]}
                                            :types #{:sheet/node-execution-started}}))))

(defn- owns-start? [ctx owner]
  (when-let [event (canonical-start ctx (:tick-id owner) (:node-start-id owner))]
    (and (= (select-keys owner [:sheet-id :tick-id :node-id])
            (select-keys event [:sheet-id :tick-id :node-id]))
         (= (:exec-context owner) (logic/exec-context (:inputs event))))))

(defcommand :sheet start-provider-attempt
  {:authorized? logic/provider-observations-authorized?}
  [ctx]
  (if-not (some? (:tenant-id ctx))
    (logic/provider-observations-rejected :cognitect.anomalies/forbidden "Provider observation tenant is required")
    (let [owner (select-keys (:command ctx) logic/provider-observations-owner-keys)
          events (invocation-events ctx (:invocation-id owner))
          started (first events)]
      (cond
        (not (owns-start? ctx owner)) (logic/provider-observations-rejected :cognitect.anomalies/incorrect "Provider attempt does not own the canonical node start")
        started (if (= owner (select-keys started logic/provider-observations-owner-keys))
                  {:command-result/events []}
                  (logic/provider-observations-rejected :cognitect.anomalies/conflict "Provider invocation identity already belongs to another attempt"))
        :else (logic/append-transition :sheet/provider-attempt-started owner nil empty?)))))

(defcommand :sheet complete-provider-attempt
  {:authorized? logic/provider-observations-authorized?}
  [ctx]
  (if-not (some? (:tenant-id ctx))
    (logic/provider-observations-rejected :cognitect.anomalies/forbidden "Provider observation tenant is required")
    (let [owner (select-keys (:command ctx) logic/provider-observations-owner-keys)
          outcome (get-in ctx [:command :outcome])
          events (invocation-events ctx (:invocation-id owner))
          started (first events)
          completed (some #(when (= :sheet/provider-attempt-completed (:event/type %)) %) events)
          owns? (and started (= owner (select-keys started logic/provider-observations-owner-keys)))]
      (cond
        (not owns?) (logic/provider-observations-rejected :cognitect.anomalies/incorrect "Provider outcome requires its exact started attempt")
        completed (if (= outcome (:outcome completed))
                    {:command-result/events []}
                    (logic/provider-observations-rejected :cognitect.anomalies/conflict "Provider attempt already has a different outcome"))
        :else (logic/append-transition :sheet/provider-attempt-completed owner outcome
                                 #(and (= 1 (count %))
                                       (= owner (select-keys (first %) logic/provider-observations-owner-keys))))))))

(defn provider-attempts [ctx execution]
  (qp/process-query (assoc ctx :query (merge execution {:query/name :sheet/provider-attempts
                                                       :query/id (random-uuid) :query/timestamp (time/now)}))))

(defn publish!
  "Publish `trace` exactly once as a creation fact, or as a strictly newer
   revision after another publisher wins the creation claim.

   Command identity and time are injectable through `:orc/new-command-id!` and
   `:orc/now!`; ordinary runtime contexts default to UUIDv4 and Grain time."
  [context trace]
  (let [creation-result
        (cp/process-command
         (assoc context :command
                (logic/command-envelope context :sheet/store-execution-trace trace)))]
    (if (= :cognitect.anomalies/conflict (:cognitect.anomalies/category creation-result))
      (cp/process-command
       (assoc context :command
              (logic/command-envelope context :sheet/refresh-execution-trace trace)))
      creation-result)))

;; --- Runtime ---

(defn- partial-timeout-node-traces
  "Reconstruct partial node history from durable lifecycle events. Active
   attempts enrich the durable timeout trace; lifecycle identity and completed
   outcomes always come from the event log."
  [context tick-id completed-at attempts]
  (let [tick-ctx (rm/get-tick-execution-context context tick-id)
        nodes-by-id (:nodes-by-id tick-ctx)
        events (into [] (es/read (:event-store context)
                                {:tenant-id (:tenant-id context)
                                 :tags #{[:tick tick-id]}}))
        started (filter #(= :sheet/node-execution-started (:event/type %)) events)
        completed (filter #(= :sheet/node-execution-completed (:event/type %)) events)
        execution-context (fn [event]
                            (select-keys (or (:inputs event) {})
                                         [:ai.obney.orc.orc-service.core.todo-processors/map-each-index
                                          :ai.obney.orc.orc-service.core.todo-processors/map-each-parent]))
        execution-key (fn [event] [(:node-id event) (execution-context event)])
        started-by (into {} (map (juxt execution-key identity) started))
        completed-by (into {} (map (juxt execution-key identity) completed))]
    (vec
     (keep
      (fn [start]
        (when-let [node (get nodes-by-id (:node-id start))]
          (let [completion (get completed-by (execution-key start))
                active-attempt (get attempts [(:node-id start) (execution-context start)])
                parent-start (when-let [parent-id (:parent-id node)]
                               (or (get started-by [parent-id (execution-context start)])
                                   (get started-by [parent-id {}])))
                timed-out? (nil? completion)]
            (cond-> {:node-id (:node-id start)
                     :trace-instance-id (:event/id start)
                     :node-name (:name node)
                     :node-type (:type node)
                     :path (logic/timeout-node-path nodes-by-id (:node-id start))
                     :status (if timed-out? :timeout (:status completion))
                     :started-at (str (:event/timestamp start))
                     :completed-at (str (or (:event/timestamp completion) completed-at))}
              (:executor node) (assoc :executor (:executor node))
              (:parent-id node) (assoc :parent-id (:parent-id node))
              parent-start (assoc :parent-trace-instance-id (:event/id parent-start))
              (:duration-ms completion) (assoc :duration-ms (:duration-ms completion))
              (seq (:read-keys completion))
              (assoc :read-keys (:read-keys completion)
                     :input-profile (:input-profile completion))
              (seq (:write-keys completion))
              (assoc :write-keys (:write-keys completion)
                     :output-profile (:write-profile completion))
              (seq (:missing-write-keys completion))
              (assoc :missing-write-keys (:missing-write-keys completion))
              (seq (:rejected-write-keys completion))
              (assoc :rejected-write-keys (:rejected-write-keys completion)
                     :rejected-output-profile (:rejected-write-profile completion))
              (:error completion) (assoc :error (:error completion))
              timed-out? (assoc :error "Execution timed out")
              active-attempt (merge active-attempt)
              (and timed-out? (nil? active-attempt))
              (assoc :node-attempt 1
                     :max-node-attempts (or (get-in node [:retry :max-attempts]) 1)
                     :execution-budget-remaining-ms 0)))))
      started))))

(defn collect-tick-classification
  "Query the event store for :ontology/task-classified events tagged with
   [:tick tick-id]; if any, return the original as a run-result envelope
   map {:tree-id :confidence :top-candidates :was-fresh-mint?}. Returns
   nil when no classification event exists for this tick."
  [context tick-id]
  (try
    (when-let [event-store (:event-store context)]
      (let [events (->> (es/read event-store
                                  {:tenant-id (:tenant-id context)
                                   :types #{:ontology/task-classified}
                                   :tags #{[:tick tick-id]}})
                        (into []))]
        (when-let [e (first events)]
          {:tree-id (:assigned-tree-id e)
           :confidence (:confidence e)
           :top-candidates (vec (take 3 (:top-candidates e)))
           :was-fresh-mint? (:was-fresh-mint? e)})))
    (catch Exception _ nil)))

;; =============================================================================
;; Execution Snapshot Builder
;; =============================================================================

(defn build-execution-snapshot
  "Load sheet, resolve version, and build an executable snapshot.

   This is a pure read-model query — no side effects. Returns a map with:
     :sheet-id      - UUID of the sheet
     :sheet-name    - Name of the sheet
     :nodes-by-id   - Map of node-id -> node record
     :root-node-id  - UUID of the root node
     :blackboard-entries - Map of key-name -> {:key, :schema, :value, :version}
     :version-number - Version number if using published version (nil for draft)

   When :sheet-tenant-id is provided, reads sheet definitions from that tenant
   instead of the context's :tenant-id. This enables cross-tenant execution where
   workflows live in a system tenant but executions run in user tenants.

   Or returns an anomaly map if the sheet/version is not found."
  [context sheet-id & {:keys [use-version force-draft instruction-overrides sheet-tenant-id]}]
  (let [read-ctx (if sheet-tenant-id
                   (assoc context :tenant-id sheet-tenant-id)
                   context)
        sheet (rm/get-sheet read-ctx sheet-id)]
    (cond
      (not sheet)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Sheet not found"}

      :else
      (let [execution-mode (or (:execution-mode sheet) :draft)
            version-to-use (cond
                             use-version use-version
                             force-draft nil
                             (= :published execution-mode) (:published-version sheet)
                             :else nil)
            version-snapshot (when version-to-use
                               (rm/get-version read-ctx sheet-id version-to-use))
            {:keys [nodes-by-id root-id blackboard-entries version-number]}
            (if version-snapshot
              (let [parsed (logic/parse-snapshot-for-execution (:snapshot version-snapshot)
                                                  (rm/get-nodes-by-id read-ctx sheet-id)
                                                  sheet-id
                                                  (:version-number version-snapshot))]
                {:nodes-by-id (:nodes-by-id parsed)
                 :root-id (:root-id parsed)
                 :blackboard-entries (:blackboard parsed)
                 :version-number (:version-number version-snapshot)})
              {:nodes-by-id (rm/get-nodes-by-id read-ctx sheet-id)
               :root-id (:root-node-id sheet)
               :blackboard-entries (rm/get-blackboard-by-key-from-events read-ctx sheet-id)
               :version-number nil})]
        (if-not root-id
          {:cognitect.anomalies/category :cognitect.anomalies/not-found
           :cognitect.anomalies/message "Sheet has no root node"}
          (cond-> {:sheet-id sheet-id
                   :sheet-name (:name sheet)
                   :nodes-by-id nodes-by-id
                   :root-node-id root-id
                   :blackboard-entries blackboard-entries
                   :version-number version-number}
            (seq instruction-overrides)
            (assoc :instruction-overrides instruction-overrides)))))))

(defn- durable-trace-lineage
  [context tick-id]
  (let [store (:event-store context)
        tenant-id (:tenant-id context)
        started (fn [id]
                  (first (into [] (es/read store {:tenant-id tenant-id
                                                  :types #{:sheet/tree-tick-started}
                                                  :tags #{[:tick id]}}))))
        parent-id (:parent-tick-id (started tick-id))
        root-id (loop [id tick-id]
                  (if-let [parent (:parent-tick-id (started id))]
                    (recur parent)
                    id))
        child-ids (mapv :tick-id
                        (into [] (es/read store {:tenant-id tenant-id
                                                :types #{:sheet/tree-tick-started}
                                                :tags #{[:parent-tick tick-id]}})))]
    {:parent-trace-id parent-id
     :root-trace-id root-id
     :child-trace-ids child-ids}))

(defn timeout-execution!
  "Make a caller deadline terminal, interrupt active work, and synchronously
   persist the partial event-derived trace before returning.

   The optional opts map accepts :error — an override for the durable trace's
   error string (and the cancel reason), used by `expire-run!` so a WEDGED
   run's trace carries the liveness attribution instead of the generic
   \"Execution timed out\". The returned map keeps :status :timeout either
   way; wedge-distinct RESULT shaping is `expire-run!`'s job."
  ([context sheet-id tick-id inputs duration-ms]
   (timeout-execution! context sheet-id tick-id inputs duration-ms nil))
  ([context sheet-id tick-id inputs duration-ms {:keys [error]}]
  (let [error (or error "Execution timed out")
        active-attempts (logic/attempts-for-tick tick-id)
        cancel-command-time (time/now)
        cancel-result (cp/process-command
                       (assoc context :command
                              {:command/id (random-uuid)
                               :command/timestamp cancel-command-time
                               :command/name :sheet/cancel-tick
                               :sheet-id sheet-id :tick-id tick-id
                               :reason error}))
        cancellation-events (into [] (es/read (:event-store context)
                                              {:tenant-id (:tenant-id context)
                                               :types #{:sheet/tick-cancelled}
                                               :tags #{[:tick tick-id]}}))
        completed-at (or (:event/timestamp (last cancellation-events))
                         cancel-command-time)
        tick-events (into [] (es/read (:event-store context)
                                     {:tenant-id (:tenant-id context)
                                      :types #{:sheet/tree-tick-started}
                                      :tags #{[:tick tick-id]}}))
        started-at (or (:event/timestamp (first tick-events)) completed-at)
        started-at-str (logic/canonical-string started-at)
        completed-at-str (logic/canonical-string completed-at)
        trace-duration-ms (or (logic/elapsed-ms started-at completed-at)
                              duration-ms)
        node-traces (partial-timeout-node-traces context tick-id completed-at-str
                                                 active-attempts)
        {:keys [parent-trace-id root-trace-id child-trace-ids]}
        (durable-trace-lineage context tick-id)
        tick-ctx (rm/get-tick-execution-context context tick-id)
        _terminal (cp/process-command
                   (assoc context :command
                          (cond-> {:command/id (random-uuid)
                                   :command/timestamp (time/now)
                                   :command/name :sheet/emit-tick-completed
                                   :sheet-id sheet-id
                                   :tick-id tick-id
                                   :root-status :timeout
                                   :terminal-reason :timeout
                                   :error error}
                            (:correlation-id tick-ctx)
                            (assoc :correlation-id (:correlation-id tick-ctx))
                            (get-in tick-ctx [:options :max-ticks])
                            (assoc :configured-max-ticks
                                   (get-in tick-ctx [:options :max-ticks])))))
        source-event-count
        (->> (es/read (:event-store context)
                      {:tenant-id (:tenant-id context)
                       :tags #{[:tick tick-id]}})
             (into [])
             (remove #(contains? #{:sheet/execution-traced
                                   :sheet/execution-trace-refreshed}
                                 (:event/type %)))
             count)
        _cancelled-work (logic/cancel-active-work! tick-id)]
    (publish!
     context
     (cond-> {:trace-id tick-id :sheet-id sheet-id
              :root-trace-id root-trace-id
              :child-trace-ids child-trace-ids
              :started-at started-at-str
              :completed-at completed-at-str
              :duration-ms trace-duration-ms :status :timeout
              :input-snapshot (logic/profile-values (or inputs {}))
              :output-snapshot {}
              :source-event-count source-event-count
              :node-traces node-traces
              :error error}
       parent-trace-id
       (assoc :parent-trace-id parent-trace-id)))
    (logic/clear-tick! tick-id)
    (logic/forget-ephemeral-context! tick-id)
    {:status :timeout
     :trace-id tick-id
     :error error
     :duration-ms duration-ms})))

(defn run-liveness-verdict
  "Classify a run whose completion-promise deref just expired: WEDGED or
   merely over budget. Reads only the engine's own evidence:

   - In-flight provider attempts (`execution-budget/attempts-for-tick`).
     A recorded attempt is a SELF-BOUNDED wait — the executor caps each
     provider request at the remaining deadline — so its presence proves
     the engine is alive inside a bounded call: NOT wedged.
   - The run's durable tick events. If the newest event is older than
     `grace-ms`, the engine went silent for longer than its own declared
     result-delivery slack (see `default-result-grace-ms`) while still
     owing a result: WEDGED. A run that never emitted a single event has
     been silent since dispatch, so its silence age is the whole wait
     (`waited-ms`) — a short-budget run whose tick simply had not started
     yet stays an ordinary :timeout instead of a false wedge.

   Registered :code work (`active-work`) is deliberately NOT liveness
   evidence — it has no self-bound, and work-that-never-finishes is exactly
   the wedge shape this classifier exists to name (the forensic's
   forever-blocking leaf). A run silent because a nested run under it is
   busy is still, at THIS seam, past its own budget with no bounded wait to
   point at; retrying it would relaunch the nested work — the observed
   W2R-4 harm — so it too classifies as wedged.

   Returns {:wedged? bool
            :in-flight-attempts n
            :last-activity-age-ms ms   ;; the whole wait when the run never
            :grace-ms ms}              ;; emitted a single event"
  [context tick-id grace-ms waited-ms]
  (let [attempts (logic/attempts-for-tick tick-id)
        events (into [] (es/read (:event-store context)
                                 {:tenant-id (:tenant-id context)
                                  :tags #{[:tick tick-id]}}))
        now (time/now)
        last-activity-age-ms (or (when-let [newest (:event/timestamp (last events))]
                                   (logic/elapsed-ms newest now))
                                 waited-ms)
        wedged? (and (empty? attempts)
                     (>= last-activity-age-ms grace-ms))]
    {:wedged? wedged?
     :in-flight-attempts (count attempts)
     :last-activity-age-ms last-activity-age-ms
     :grace-ms grace-ms}))

(defn expire-run!
  "Handle a completion-promise deref that expired at the run's bound.

   Classifies the expiry via `run-liveness-verdict`, then runs the same
   durable containment either way (`timeout-execution!`: cancel the tick,
   interrupt active work, persist the partial trace, clear budgets).

   - Over budget but alive (recent events or an in-flight bounded provider
     attempt): the existing contract, {:status :timeout} — retryable.
   - WEDGED (silent past the run's own budget + the engine's own grace):
     a DISTINCT, attributable, NON-RETRYABLE failure:
       {:status :failure         ;; never :timeout — no retry loop matches
        :wedged? true
        :error \"Run wedged: ...\"  ;; carries no retryable fragment
        :liveness {:seam :tick-id :sheet-id :waited-ms
                   :last-activity-age-ms :grace-ms :in-flight-attempts}}
     The durable trace keeps :status :timeout (schema untouched) but its
     :error carries the wedge attribution.

   `seam` names the deref site that expired (e.g. :orc.runtime/execute)."
  [context sheet-id tick-id inputs duration-ms {:keys [grace-ms seam]}]
  (let [grace-ms (or grace-ms logic/default-result-grace-ms)
        {:keys [wedged? in-flight-attempts last-activity-age-ms]
         :as verdict} (run-liveness-verdict context tick-id grace-ms duration-ms)]
    (if-not wedged?
      (timeout-execution! context sheet-id tick-id inputs duration-ms)
      (let [error (str "Run wedged at " seam ": engine silent for "
                       last-activity-age-ms
                       " ms during a " duration-ms " ms wait on tick " tick-id
                       " (grace " grace-ms " ms); a wedged run is terminal"
                       " and must not be retried")]
        (u/log :ai.obney.orc.orc-service.core.runtime/run-wedged
               :seam seam
               :tick-id tick-id
               :sheet-id sheet-id
               :waited-ms duration-ms
               :last-activity-age-ms last-activity-age-ms
               :grace-ms grace-ms
               :in-flight-attempts in-flight-attempts)
        (timeout-execution! context sheet-id tick-id inputs duration-ms
                            {:error error})
        {:status :failure
         :wedged? true
         :error error
         :outputs {}
         :trace-id tick-id
         :duration-ms duration-ms
         :liveness (assoc verdict
                          :seam seam
                          :tick-id tick-id
                          :sheet-id sheet-id
                          :waited-ms duration-ms)}))))

(defn durable-terminal-result
  "Reconstruct the result of an already-terminal tick from durable facts.
   This is the process-recovery path for callers reattaching after the
   completion promise and its observer thread were lost."
  [{:keys [event-store tenant-id] :as context} tick-id]
  (when event-store
    (when-let [completion
               (last (filter #(and (= :sheet/tree-tick-completed (:event/type %))
                                   (some? (logic/terminal-root-status->result-status
                                           (:root-status %))))
                             (into [] (es/read event-store
                                               {:tenant-id tenant-id
                                                :types #{:sheet/tree-tick-completed}
                                                :tags #{[:tick tick-id]}}))))]
      (let [tick-ctx (rm/get-tick-execution-context context tick-id)
            status (logic/terminal-root-status->result-status
                    (:root-status completion))]
        (cond-> {:status status
                 :trace-id tick-id
                 :error (:error completion)
                 :configured-max-ticks (:configured-max-ticks completion)
                 :consumed-ticks (:consumed-ticks completion)
                 :terminal-reason (:terminal-reason completion)}
          (not= :timeout status)
          (assoc :outputs (rm/final-values context tenant-id tick-id)
                 :output-sources (rm/final-sources context tenant-id tick-id))
          (:version-number tick-ctx)
          (assoc :executed-version (:version-number tick-ctx))
          (= :blocked (:root-status completion))
          (assoc :block-payload (:block-payload completion)))))))

(defn execute
  "Execute a sheet (behavior tree) by dispatching to async pipeline and waiting.

   This is a blocking call that:
   1. Dispatches a tick-tree command with an execution snapshot
   2. Waits for the async pipeline to complete (todo processors)
   3. Returns the result delivered by the completion registry

   Args:
     context - Map with :event-store, :pubsub, and optional :llm-provider
     sheet-id - UUID of the sheet to execute
     inputs - Map of blackboard key -> value for initial inputs

   Options:
     :timeout-ms - Max execution time in ms (default 300000 = 5 minutes)
     :execution-deadline-ms - Optional absolute deadline cap, inherited by
                              delegates, for provider admission. The local
                              timeout may tighten it; a spent cap stays spent.
                              It does not shorten the blocking caller wait or
                              interrupt arbitrary code; those retain timeout-ms.
     :result-grace-ms - Silence tolerance used to classify an expired wait
                        as WEDGED vs merely over budget (default
                        `default-result-grace-ms`, the engine's own
                        stream-reaping slack; see `run-liveness-verdict`)
     :use-version - Specific version number to execute (overrides execution-mode)
     :force-draft - Force draft execution even if execution-mode is :published
     :trace? - Enable tracing (passed to async pipeline via options)
     :langfuse-client - Langfuse client (passed to async pipeline via options)
     :store-trace? - Store trace in event store (default true, passed via options)
     :max-ticks - Override re-tick budget for this execution (defaults to *max-tick-iterations*)
     :tick-id - Optional caller-supplied execution id for correlating live progress
     :parent-tick-id - Optional lineage marker when this execution is a child of
                       another tick (RLM Phase 2 trees, delegate nodes)
     :correlation-id - Optional UUID grouping independent root executions into
                       one caller-defined operation. Overrides
                       :orc/correlation-id on context.
     :input-sources - Internal {key {:tick-id :event-id}} provenance map; values
                      already durable elsewhere are referenced, not copied
     :return-references? - Internal flag to include :output-sources for delegates
     :llm-call-budget - Max LLM calls before failing (opt-in only, NO default)
     :assessment-origin - {:assessment-id uuid}: marks this run as work done for
                          an assessment. Recorded durably on the run's start
                          event and inherited by every child run, so no
                          completion under it is ever auto-assessed (read it
                          back with `assessment-origin`).
     :durability-mode - Internal comparison mode; :legacy restores per-node
                        routing lifecycle events (default uses summarized routing)

   Returns:
     {:status :success | :failure | :timeout
      :outputs {\"key\" value ...}
      :duration-ms 1234
      :error string?             ;; Present if status is :failure
      :executed-version ...      ;; Version number if published version was used
      :wedged? true              ;; Present only when the run was declared
      :liveness {...}}           ;; wedged — see `expire-run!` (non-retryable)"
  [context sheet-id inputs & {:keys [timeout-ms execution-deadline-ms result-grace-ms use-version force-draft
                                      trace? langfuse-client store-trace?
                                      max-ticks llm-call-budget tick-id parent-tick-id
                                      delegate-parent-sheet-id delegate-parent-node-id
                                      delegate-parent-exec-context delegate-parent-read-inputs
                                      delegate-parent-read-sources
                                      correlation-id input-sources return-references?
                                      durability-mode checkpointed-campaign?
                                      llm-budget-root-sheet-id llm-budget-root-tick-id
                                      assessment-origin]
                               :or {timeout-ms 300000
                                    result-grace-ms logic/default-result-grace-ms
                                    store-trace? true}}]
  (let [correlation-id (or correlation-id (:orc/correlation-id context))
        tick-id (or tick-id (random-uuid))
        p (logic/register-completion! tick-id)
        _ (when-let [ephemeral (not-empty (select-keys context [:mcp-session :call-tool-fn]))]
            (swap! logic/ephemeral-context-registry assoc tick-id ephemeral))
        start-time (System/currentTimeMillis)
        execution-now-ms-fn (or (:execution-now-ms-fn context)
                                #(System/currentTimeMillis))
        execution-start-ms (long (execution-now-ms-fn))
        local-deadline-ms (+ execution-start-ms timeout-ms)
        effective-deadline-ms (if (some? execution-deadline-ms)
                                (min local-deadline-ms execution-deadline-ms)
                                local-deadline-ms)
        cmd-result (cp/process-command
                     (assoc context :command
                            (cond-> {:command/id (random-uuid)
                                     :command/timestamp (time/now)
                                     :command/name :sheet/tick-tree
                                     :sheet-id sheet-id
                                     :tick-id tick-id
                                     :inputs (or inputs {})
                                     :options (cond-> {:timeout-ms timeout-ms
                                                        :execution-deadline-ms effective-deadline-ms
                                                        :store-trace? store-trace?}
                                                 trace? (assoc :trace? true)
                                                 langfuse-client (assoc :langfuse-client langfuse-client)
                                                 max-ticks (assoc :max-ticks max-ticks)
                                                 delegate-parent-sheet-id
                                                 (assoc :delegate-parent-sheet-id delegate-parent-sheet-id)
                                                 delegate-parent-node-id
                                                 (assoc :delegate-parent-node-id delegate-parent-node-id)
                                                 (seq delegate-parent-exec-context)
                                                 (assoc :delegate-parent-exec-context delegate-parent-exec-context)
                                                 (seq delegate-parent-read-inputs)
                                                 (assoc :delegate-parent-read-inputs delegate-parent-read-inputs)
                                                 (seq delegate-parent-read-sources)
                                                 (assoc :delegate-parent-read-sources delegate-parent-read-sources)
                                                 llm-call-budget (assoc :llm-call-budget llm-call-budget)
                                                 checkpointed-campaign?
                                                 (assoc :checkpointed-campaign? true)
                                                 llm-budget-root-sheet-id
                                                 (assoc :llm-budget-root-sheet-id
                                                        llm-budget-root-sheet-id)
                                                 llm-budget-root-tick-id
                                                 (assoc :llm-budget-root-tick-id
                                                        llm-budget-root-tick-id)
                                                 assessment-origin
                                                 (assoc :assessment-origin assessment-origin)
                                                 durability-mode (assoc :durability-mode durability-mode))}
                              parent-tick-id (assoc :parent-tick-id parent-tick-id)
                              correlation-id (assoc :correlation-id correlation-id)
                              (seq input-sources) (assoc :input-sources input-sources)
                              use-version (assoc :use-version use-version)
                              force-draft (assoc :force-draft force-draft)
                              (:tool-context context)
                              (assoc :tool-context (:tool-context context)))))]
    (if (:cognitect.anomalies/category cmd-result)
      (do (swap! logic/completion-registry dissoc tick-id)
          (logic/forget-ephemeral-context! tick-id)
          {:status :failure
           :error (:cognitect.anomalies/message cmd-result)
           :duration-ms (- (System/currentTimeMillis) start-time)})
      (let [result (or (durable-terminal-result context tick-id)
                       (deref p timeout-ms :ai.obney.orc.orc-service.core.runtime/timeout))
            duration-ms (- (System/currentTimeMillis) start-time)
            classification (collect-tick-classification context tick-id)]
        (swap! logic/completion-registry dissoc tick-id)
        (if (= result :ai.obney.orc.orc-service.core.runtime/timeout)
          (expire-run! context sheet-id tick-id inputs duration-ms
                       {:grace-ms result-grace-ms
                        :seam :orc.runtime/execute})
          (cond-> (assoc (if return-references?
                           result
                           (dissoc result :output-sources))
                         :duration-ms duration-ms)
            classification
            (assoc :auto-classification classification)))))))

;; =============================================================================
;; Sheet Commands
;; =============================================================================

(defcommand :sheet create-sheet
  {:authorized? logic/commands-authenticated?}
  "Create a new sheet. Name must be unique.
   If sheet-id is provided, uses that ID (for deterministic UUIDs).
   Otherwise generates a random UUID."
  [{{:keys [name sheet-id]} :command
    :as ctx}]
  (let [existing (rm/get-sheet-by-name ctx name)]
    (if existing
      {:cognitect.anomalies/category :cognitect.anomalies/conflict
       :cognitect.anomalies/message (str "Sheet with name '" name "' already exists")
       :existing-sheet-id (:id existing)}
      (let [new-sheet-id (or sheet-id (random-uuid))]
        {:command-result/events
         [(->event
           {:type :sheet/sheet-created
            :tags #{[:sheet new-sheet-id]}
            :body {:sheet-id new-sheet-id
                   :name name}})]}))))

(defcommand :sheet rename-sheet
  {:authorized? logic/commands-authenticated?}
  "Rename an existing sheet. New name must be unique."
  [{{:keys [sheet-id name]} :command
    :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)
        existing (rm/get-sheet-by-name ctx name)]
    (cond
      (not sheet)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Sheet not found"}

      (and existing (not= (:id existing) sheet-id))
      {:cognitect.anomalies/category :cognitect.anomalies/conflict
       :cognitect.anomalies/message (str "Sheet with name '" name "' already exists")
       :existing-sheet-id (:id existing)}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/sheet-renamed
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id
                 :old-name (:name sheet)
                 :new-name name}})]})))

(defcommand :sheet delete-sheet
  {:authorized? logic/commands-authenticated?}
  "Delete a sheet and all its nodes."
  [{{:keys [sheet-id]} :command
    :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)]
    (if-not sheet
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Sheet not found"}
      {:command-result/events
       [(->event
         {:type :sheet/sheet-deleted
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id}})]})))

(defcommand :sheet set-content-hash
  {:authorized? logic/commands-authenticated?}
  "Store a content hash on a sheet for idempotent rebuild detection."
  [{{:keys [sheet-id content-hash]} :command
    :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)]
    (if-not sheet
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Sheet not found"}
      {:command-result/events
       [(->event
         {:type :sheet/content-hash-set
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id
                 :content-hash content-hash}})]})))

;; =============================================================================
;; Node Commands
;; =============================================================================

(defcommand :sheet create-node
  {:authorized? logic/commands-authenticated?}
  "Create a new node in a sheet.
   If parent-id is nil, creates a root node.
   Index specifies position among siblings (default 0)."
  [{{:keys [sheet-id node-id type parent-id index]} :command
    :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)
        has-root? (some? (:root-node-id sheet))
        parent (when parent-id (rm/get-node ctx sheet-id parent-id))]
    (cond
      (not sheet)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Sheet not found"}

      (and (nil? parent-id) has-root?)
      {:cognitect.anomalies/category :cognitect.anomalies/conflict
       :cognitect.anomalies/message "Sheet already has a root node"}

      (and parent-id (not parent))
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Parent node not found"}

      :else
      (let [new-node-id (or node-id (random-uuid))]
        {:command-result/events
         [(->event
           {:type :sheet/node-created
            :tags #{[:sheet sheet-id]
                    [:node new-node-id]}
            :body (cond-> {:sheet-id sheet-id
                           :node-id new-node-id
                           :type type}
                    parent-id (assoc :parent-id parent-id)
                    index (assoc :index index))})]}))))

(defcommand :sheet move-node
  {:authorized? logic/commands-authenticated?}
  "Move a node to a new parent at specified index."
  [{{:keys [sheet-id node-id new-parent-id index]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)
        new-parent (rm/get-node ctx sheet-id new-parent-id)
        nodes-by-id (rm/get-nodes-by-id ctx sheet-id)]
    (cond
      (not node)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Node not found"}

      (not new-parent)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "New parent node not found"}

      (logic/is-descendant? nodes-by-id node-id new-parent-id)
      {:cognitect.anomalies/category :cognitect.anomalies/conflict
       :cognitect.anomalies/message "Cannot move node under its own descendant"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-moved
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :new-parent-id new-parent-id
                         :index index}
                  (:parent-id node) (assoc :old-parent-id (:parent-id node)))})]})))

(defcommand :sheet reorder-node
  {:authorized? logic/commands-authenticated?}
  "Reorder a node among its siblings."
  [{{:keys [sheet-id node-id index]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)
        parent-id (:parent-id node)
        parent (when parent-id (rm/get-node ctx sheet-id parent-id))
        current-children (when parent (:children-ids parent))
        current-index (when current-children
                        (.indexOf (vec current-children) node-id))]
    (cond
      (not node)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Node not found"}

      (nil? parent-id)
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "Cannot reorder root node"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-reordered
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body {:sheet-id sheet-id
                 :node-id node-id
                 :old-index (or current-index 0)
                 :new-index index}})]})))

(defcommand :sheet delete-node
  {:authorized? logic/commands-authenticated?}
  "Delete a node from a sheet.
   Node must have no children (delete children first)."
  [{{:keys [sheet-id node-id]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)]
    (cond
      (not node)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Node not found"}

      (seq (:children-ids node))
      {:cognitect.anomalies/category :cognitect.anomalies/conflict
       :cognitect.anomalies/message "Cannot delete node with children. Delete children first."}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-deleted
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body {:sheet-id sheet-id
                 :node-id node-id}})]})))

(defcommand :sheet set-node-name
  {:authorized? logic/commands-authenticated?}
  "Set the name of a node."
  [{{:keys [sheet-id node-id name]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)]
    (cond
      (not node)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Node not found"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-name-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :name name}
                  (:name node) (assoc :previous-name (:name node)))})]})))

(defcommand :sheet set-node-instruction
  {:authorized? logic/commands-authenticated?}
  "Set the instruction for a leaf node."
  [{{:keys [sheet-id node-id instruction]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)]
    (cond
      (not node)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Node not found"}

      (not= :leaf (:type node))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "Only leaf nodes can have instructions"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-instruction-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :instruction instruction}
                  (:instruction node) (assoc :previous-instruction (:instruction node)))})]})))

(defcommand :sheet set-node-context
  {:authorized? logic/commands-authenticated?}
  "Set the ontology context for a node (for self-learning injection).

   Originally restricted to :leaf nodes; relaxed in C-1 to also accept
   :repl-researcher nodes, since the runtime apply-ontology-context
   pipeline (todo_processors.clj) reads :context from any node with
   :instruction and prepends formatted principles to it. The read-model
   projection is generic so no other change was needed."
  [{{:keys [sheet-id node-id context]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)]
    (cond
      (not node)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Node not found"}

      (not (#{:leaf :repl-researcher} (:type node)))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "Only :leaf and :repl-researcher nodes can have context"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-context-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :context context}
                  (:context node) (assoc :previous-context (:context node)))})]})))

(defcommand :sheet set-node-io
  {:authorized? logic/commands-authenticated?}
  "Set the reads/writes (blackboard keys) for a leaf node."
  [{{:keys [sheet-id node-id reads writes]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)
        blackboard (rm/get-blackboard-by-key ctx sheet-id)
        all-keys (set (keys blackboard))
        unknown-reads (remove all-keys reads)
        unknown-writes (remove all-keys writes)]
    (cond
      (not node)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Node not found"}

      (not= :leaf (:type node))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "Only leaf nodes can have reads/writes"}

      (seq unknown-reads)
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message (str "Unknown blackboard keys in reads: " (vec unknown-reads))}

      (seq unknown-writes)
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message (str "Unknown blackboard keys in writes: " (vec unknown-writes))}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-io-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :reads (vec reads)
                         :writes (vec writes)}
                  (seq (:reads node)) (assoc :previous-reads (:reads node))
                  (seq (:writes node)) (assoc :previous-writes (:writes node)))})]})))

(defcommand :sheet set-node-decorators
  {:authorized? logic/commands-authenticated?}
  "Set decorators for a node."
  [{{:keys [sheet-id node-id decorators]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)]
    (cond
      (not node)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Node not found"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-decorators-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :decorators (vec decorators)}
                  (seq (:decorators node)) (assoc :previous-decorators (:decorators node)))})]})))

(defcommand :sheet set-node-check
  {:authorized? logic/commands-authenticated?}
  "Set the condition check for a condition node."
  [{{:keys [sheet-id node-id check]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)
        blackboard (rm/get-blackboard-by-key ctx sheet-id)
        check-key (:key check)]
    (cond
      (not node)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Node not found"}

      (not= :condition (:type node))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "Only condition nodes can have checks"}

      (not (contains? blackboard check-key))
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message (str "Blackboard key '" check-key "' not declared")}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-check-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :check check}
                  (:check node) (assoc :previous-check (:check node)))})]})))

(defcommand :sheet set-node-executor
  {:authorized? logic/commands-authenticated?}
  "Set the executor configuration for a leaf node.
   - :ai executor uses ORC LLM with optional model selection
   - :code executor runs a Clojure function
   - :tool executor directly invokes a tool"
  [{{:keys [sheet-id node-id executor model fn tool tools options tool-caller-fn tool-contracts recoverable-fn
                options-from bands-from min-confidence abstain]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)]
    (cond
      (not node)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Node not found"}

      (not= :leaf (:type node))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "Only leaf nodes can have executors"}

      (and (= executor :code) (not fn))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "Code executor requires :fn (fully-qualified function symbol)"}

      (and (= executor :tool)
           (not (and (string? tool) (not (str/blank? tool)))))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "Tool executor requires :tool (the authored tool name)"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-executor-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :executor executor}
                  model (assoc :model model)
                  fn (assoc :fn fn)
                  recoverable-fn (assoc :recoverable-fn recoverable-fn)
                  tool (assoc :tool tool)
                  tools (assoc :tools (vec tools))
                  options (assoc :options options)
                  tool-caller-fn (assoc :tool-caller-fn tool-caller-fn)
                  tool-contracts (assoc :tool-contracts tool-contracts)
                  options-from (assoc :options-from options-from)
                  bands-from (assoc :bands-from bands-from)
                  min-confidence (assoc :min-confidence min-confidence)
                  (some? abstain) (assoc :abstain abstain)
                  (:executor node) (assoc :previous-executor (:executor node))
                  (:model node) (assoc :previous-model (:model node))
                  (:fn node) (assoc :previous-fn (:fn node))
                  (:tools node) (assoc :previous-tools (:tools node))
                  (:options node) (assoc :previous-options (:options node)))})]})))

(defcommand :sheet set-node-model-policy
  {:authorized? logic/commands-authenticated?}
  "Persist provider routing and ordered backups for a model-capable node."
  [{{:keys [sheet-id node-id] :as command} :command :as ctx}]
  (if-let [node (rm/get-node ctx sheet-id node-id)]
    (if (or (#{:llm-condition :repl-researcher} (:type node))
            (and (= :leaf (:type node))
                 (or (nil? (:executor node)) (#{:ai :decision} (:executor node)))))
      {:command-result/events
       [(->event {:type :sheet/node-model-policy-set
                  :tags #{[:sheet sheet-id] [:node node-id]}
                  :body (merge {:sheet-id sheet-id :node-id node-id}
                               (select-keys (logic/validate-config! command) [:provider :fallback-models :fallback-policy :options]))})]}
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect :cognitect.anomalies/message "Node cannot call a model"})
    {:cognitect.anomalies/category :cognitect.anomalies/not-found :cognitect.anomalies/message "Node not found"}))

(defcommand :sheet set-node-retry
  {:authorized? logic/commands-authenticated?}
  "Set retry configuration for a leaf node."
  [{{:keys [sheet-id node-id retry]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)]
    (cond
      (not node)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Node not found"}

      (not= :leaf (:type node))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "Only leaf nodes can have retry configuration"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-retry-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :retry retry}
                  (:retry node) (assoc :previous-retry (:retry node)))})]})))

(defcommand :sheet set-parallel-config
  {:authorized? logic/commands-authenticated?}
  "Set configuration for a parallel node."
  [{{:keys [sheet-id node-id success-policy failure-policy]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)]
    (cond
      (not node)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Node not found"}

      (not= :parallel (:type node))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "Only parallel nodes can have parallel configuration"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/parallel-config-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id}
                  success-policy (assoc :success-policy success-policy)
                  failure-policy (assoc :failure-policy failure-policy)
                  (:success-policy node) (assoc :previous-success-policy (:success-policy node))
                  (:failure-policy node) (assoc :previous-failure-policy (:failure-policy node)))})]})))

(defcommand :sheet set-map-each-config
  {:authorized? logic/commands-authenticated?}
  "Set configuration for a map-each node."
  [{{:keys [sheet-id node-id source-key item-key output-key max-concurrency preserve-failures?]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)
        blackboard (rm/get-blackboard-by-key ctx sheet-id)]
    (cond
      (not node)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Node not found"}

      (not= :map-each (:type node))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "Only map-each nodes can have map-each configuration"}

      (not (contains? blackboard source-key))
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message (str "Source key '" source-key "' not declared in blackboard")}

      (not (contains? blackboard item-key))
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message (str "Item key '" item-key "' not declared in blackboard")}

      (not (contains? blackboard output-key))
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message (str "Output key '" output-key "' not declared in blackboard")}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/map-each-config-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :source-key source-key
                         :item-key item-key
                         :output-key output-key}
                  max-concurrency (assoc :max-concurrency max-concurrency)
                  (some? preserve-failures?) (assoc :preserve-failures? preserve-failures?)
                  (:source-key node) (assoc :previous-source-key (:source-key node))
                  (:item-key node) (assoc :previous-item-key (:item-key node))
                  (:output-key node) (assoc :previous-output-key (:output-key node))
                  (:max-concurrency node) (assoc :previous-max-concurrency (:max-concurrency node)))})]})))

(defcommand :sheet set-llm-condition-config
  {:authorized? logic/commands-authenticated?}
  "Set configuration for an llm-condition node."
  [{{:keys [sheet-id node-id instruction reads model]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)
        blackboard (rm/get-blackboard-by-key ctx sheet-id)
        all-keys (set (keys blackboard))
        unknown-reads (remove all-keys reads)]
    (cond
      (not node)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Node not found"}

      (not= :llm-condition (:type node))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "Only llm-condition nodes can have llm-condition configuration"}

      (seq unknown-reads)
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message (str "Unknown blackboard keys in reads: " (vec unknown-reads))}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/llm-condition-config-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :instruction instruction
                         :reads (vec reads)}
                  model (assoc :model model)
                  (:instruction node) (assoc :previous-instruction (:instruction node))
                  (seq (:reads node)) (assoc :previous-reads (:reads node))
                  (:model node) (assoc :previous-model (:model node)))})]})))

(defcommand :sheet set-repl-researcher-config
  {:authorized? logic/commands-authenticated?}
  "Set configuration for a repl-researcher node."
  [{{:keys [sheet-id node-id instruction reads writes mcp-tools tool-contracts tool-caller-fn browser-tools model max-iterations rlm timeout-ms options]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)
        blackboard (rm/get-blackboard-by-key ctx sheet-id)
        all-keys (set (keys blackboard))
        unknown-reads (remove all-keys reads)
        unknown-writes (remove all-keys writes)]
    (cond
      (not node)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Node not found"}

      (not= :repl-researcher (:type node))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "Only repl-researcher nodes can have repl-researcher configuration"}

      (seq unknown-reads)
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message (str "Unknown blackboard keys in reads: " (vec unknown-reads))}

      (seq unknown-writes)
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message (str "Unknown blackboard keys in writes: " (vec unknown-writes))}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/repl-researcher-config-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :instruction instruction
                         :reads (vec reads)
                         :writes (vec writes)
                         :mcp-tools (vec (or mcp-tools []))}
                  tool-contracts (assoc :tool-contracts tool-contracts)
                  browser-tools (assoc :browser-tools (vec browser-tools))
                  tool-caller-fn (assoc :tool-caller-fn tool-caller-fn)
                  model (assoc :model model)
                  max-iterations (assoc :max-iterations max-iterations)
                  (some? rlm) (assoc :rlm rlm)
                  options (assoc :options options)
                  timeout-ms (assoc :timeout-ms timeout-ms)
                  (:instruction node) (assoc :previous-instruction (:instruction node))
                  (seq (:reads node)) (assoc :previous-reads (:reads node))
                  (seq (:writes node)) (assoc :previous-writes (:writes node))
                  (seq (:mcp-tools node)) (assoc :previous-mcp-tools (:mcp-tools node))
                  (:tool-contracts node) (assoc :previous-tool-contracts (:tool-contracts node))
                  (:tool-caller-fn node) (assoc :previous-tool-caller-fn (:tool-caller-fn node))
                  (seq (:browser-tools node)) (assoc :previous-browser-tools (:browser-tools node))
                  (:model node) (assoc :previous-model (:model node))
                  (:max-iterations node) (assoc :previous-max-iterations (:max-iterations node))
                  (:options node) (assoc :previous-options (:options node))
                  (:timeout-ms node) (assoc :previous-timeout-ms (:timeout-ms node)))})]})))

(defcommand :sheet set-delegate-config
  {:authorized? logic/commands-authenticated?}
  "Set configuration for a delegate node.
   Delegate nodes execute another sheet with isolated blackboard,
   mapping inputs from parent and outputs back to parent."
  [{{:keys [sheet-id node-id target-sheet-id reads writes timeout-ms max-ticks inherit-ontology?]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)
        target-sheet (when target-sheet-id
                       (rm/get-sheet ctx target-sheet-id))
        blackboard (rm/get-blackboard-by-key ctx sheet-id)
        all-keys (set (keys blackboard))
        unknown-reads (remove all-keys reads)
        unknown-writes (remove all-keys writes)]
    (cond
      (not node)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Node not found"}

      (not= :delegate (:type node))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "Only delegate nodes can have delegate configuration"}

      (and target-sheet-id (not target-sheet))
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Target sheet not found"}

      (seq unknown-reads)
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message (str "Unknown blackboard keys in reads: " (vec unknown-reads))}

      (seq unknown-writes)
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message (str "Unknown blackboard keys in writes: " (vec unknown-writes))}

      (and (some? timeout-ms) (not (pos-int? timeout-ms)))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "Delegate timeout-ms must be a positive integer"}

      (and (some? max-ticks) (not (pos-int? max-ticks)))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "Delegate max-ticks must be a positive integer"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/delegate-config-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :target-sheet-id target-sheet-id
                         :reads (vec reads)
                         :writes (vec writes)}
                  timeout-ms (assoc :timeout-ms timeout-ms)
                  max-ticks (assoc :max-ticks max-ticks)
                  (some? inherit-ontology?) (assoc :inherit-ontology? inherit-ontology?)
                  (:target-sheet-id node) (assoc :previous-target-sheet-id (:target-sheet-id node))
                  (seq (:reads node)) (assoc :previous-reads (:reads node))
                  (seq (:writes node)) (assoc :previous-writes (:writes node))
                  (:delegate-timeout-ms node) (assoc :previous-timeout-ms (:delegate-timeout-ms node))
                  (:delegate-max-ticks node) (assoc :previous-max-ticks (:delegate-max-ticks node))
                  (some? (:inherit-ontology? node)) (assoc :previous-inherit-ontology? (:inherit-ontology? node)))})]})))

(defcommand :sheet declare-key
  {:authorized? logic/commands-authenticated?}
  "Declare a new key in the blackboard with a Malli schema."
  [{{:keys [sheet-id key schema]} :command
    :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)
        blackboard (rm/get-blackboard-by-key ctx sheet-id)
        specificity-feedback (logic/schema-specificity-feedback key schema)]
    (cond
      (not sheet)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Sheet not found"}

      (contains? blackboard key)
      {:cognitect.anomalies/category :cognitect.anomalies/conflict
       :cognitect.anomalies/message (str "Key '" key "' already declared")}

      specificity-feedback
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message (str "Blackboard key " (pr-str key) " is invalid: "
                           specificity-feedback)}

      (not (logic/valid-malli-schema? schema))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "Invalid Malli schema"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/key-declared
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id
                 :key key
                 :schema schema}})]})))

(defcommand :sheet update-key-schema
  {:authorized? logic/commands-authenticated?}
  "Update the schema for an existing blackboard key."
  [{{:keys [sheet-id key schema]} :command
    :as ctx}]
  (let [blackboard (rm/get-blackboard-by-key ctx sheet-id)
        entry (get blackboard key)
        specificity-feedback (logic/schema-specificity-feedback key schema)]
    (cond
      (not entry)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message (str "Key '" key "' not declared")}

      specificity-feedback
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message (str "Blackboard key " (pr-str key) " is invalid: "
                           specificity-feedback)}

      (not (logic/valid-malli-schema? schema))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "Invalid Malli schema"}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/key-schema-updated
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id
                 :key key
                 :schema schema
                 :previous-schema (:schema entry)}})]})))

(defcommand :sheet set-key-value
  {:authorized? logic/commands-authenticated?}
  "Set a value for a blackboard key. Value is validated against the key's Malli schema."
  [{{:keys [sheet-id key value]} :command
    :as ctx}]
  (let [blackboard (rm/get-blackboard-by-key ctx sheet-id)
        entry (get blackboard key)
        schema (:schema entry)
        validation-error (when schema (logic/validate-against-schema schema value))]
    (cond
      (not entry)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message (str "Key '" key "' not declared")}

      validation-error
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message (str "Value doesn't match schema: " (:message validation-error))}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/key-value-set
          :tags #{[:sheet sheet-id]}
          :body (cond-> {:sheet-id sheet-id
                         :key key
                         :value value
                         :version (inc (:version entry))}
                  (some? (:value entry)) (assoc :previous-value (:value entry)))})]})))

(defcommand :sheet delete-key
  {:authorized? logic/commands-authenticated?}
  "Delete a key from the blackboard."
  [{{:keys [sheet-id key]} :command
    :as ctx}]
  (let [blackboard (rm/get-blackboard-by-key ctx sheet-id)
        entry (get blackboard key)
        nodes (rm/get-nodes-for-sheet ctx sheet-id)
        nodes-using-key (filter (fn [node]
                                  (or (some #{key} (:reads node))
                                      (some #{key} (:writes node))))
                                nodes)]
    (cond
      (not entry)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message (str "Key '" key "' not declared")}

      (seq nodes-using-key)
      {:cognitect.anomalies/category :cognitect.anomalies/conflict
       :cognitect.anomalies/message (str "Cannot delete key '" key "': still in use by " (count nodes-using-key) " node(s)")}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/key-deleted
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id
                 :key key}})]})))

;; =============================================================================
;; Judge Commands
;; =============================================================================

(defcommand :sheet declare-judge
  {:authorized? logic/commands-authenticated?}
  "Declare an evaluation judge for a sheet.
   Judge config specifies the judge type and custom criteria:
   {:type :completeness  ;; :grounding, :completeness, :instruction-following, :reasoning, :custom
    :criteria \"Must include X, Y, Z\"
    :weight 0.35
    :sheet-id UUID}  ;; For :custom type only"
  [{{:keys [sheet-id judge-name judge-config]} :command
    :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)
        judges (rm/get-judges ctx sheet-id)
        existing-judge (get judges judge-name)]
    (cond
      (not sheet)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Sheet not found"}

      existing-judge
      {:cognitect.anomalies/category :cognitect.anomalies/conflict
       :cognitect.anomalies/message (str "Judge '" judge-name "' already declared")}

      (logic/config-error judge-config)
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message (logic/config-error judge-config)}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/judge-declared
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id
                 :judge-name judge-name
                 :judge-config judge-config
                 :revision-number 1
                 :criteria-version 1}})]})))

(defcommand :sheet revise-judge
  {:authorized? logic/commands-authenticated?}
  "Revise a declared judge's definition (rubric, purposes, model, type, ...).
   The config is the COMPLETE new definition. The judge keeps its name and its
   revision number increases, so results graded by the old and new definitions
   never blend. Revising to an identical definition is a conflict: it is not a
   new revision."
  [{{:keys [sheet-id judge-name judge-config]} :command
    :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)
        judge (rm/get-judge ctx sheet-id judge-name)]
    (cond
      (not sheet)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Sheet not found"}

      (not judge)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message (str "Judge '" judge-name "' not declared; declare it first")}

      (logic/config-error judge-config)
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message (logic/config-error judge-config)}

      (= judge-config (:judge-config (peek (:revisions judge))))
      {:cognitect.anomalies/category :cognitect.anomalies/conflict
       :cognitect.anomalies/message (str "Judge '" judge-name "' unchanged: revising to the same "
                           "definition is not a new revision")}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/judge-revised
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id
                 :judge-name judge-name
                 :judge-config judge-config
                 :revision-number (inc (:revision-number judge))}})]})))

(defcommand :sheet set-node-judges
  {:authorized? logic/commands-authenticated?}
  "Set which evaluation judges apply to a node.

   Any node of the sheet may carry judges: leaves, researchers, composites
   (sequence, parallel, fallback, map-each), delegates, conditions and the
   root. An attached judge monitors the completions of the node it is
   attached to, so a judge on a composite or delegate grades the whole.
   An unknown node, or a judge name not declared on the sheet, is rejected."
  [{{:keys [sheet-id node-id judges]} :command
    :as ctx}]
  (let [node (rm/get-node ctx sheet-id node-id)
        declared-judges (rm/get-judges ctx sheet-id)
        unknown-judges (remove #(contains? declared-judges %) judges)]
    (cond
      (not node)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Node not found"}

      (seq unknown-judges)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message (str "Unknown judges: " (vec unknown-judges)
                          ". Declare them first using sheet/judges.")}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/node-judges-set
          :tags #{[:sheet sheet-id]
                  [:node node-id]}
          :body (cond-> {:sheet-id sheet-id
                         :node-id node-id
                         :judges (vec judges)}
                  (:judges node) (assoc :previous-judges (:judges node)))})]})))

;; =============================================================================
;; Execution Commands
;; =============================================================================

(defcommand :sheet tick-tree
  {:authorized? logic/commands-authenticated?}
  "Start a tree tick (execute from root).
   When inputs are provided, stores a structure-only execution snapshot and
   canonical seed writes/references for isolated async execution."
  [{{:keys [sheet-id tick-id parent-tick-id correlation-id inputs input-sources use-version force-draft options
            tool-context]} :command
    :as context}]
  (let [new-tick-id (or tick-id (random-uuid))
        options (rm/start-options context sheet-id new-tick-id parent-tick-id options)
        tick-start-cas {:types #{:sheet/tree-tick-started}
                        :tags #{[:tick new-tick-id]}
                        :predicate-fn
                        (fn [existing]
                          (not-any? #(= :sheet/tree-tick-started (:event/type %))
                                    (into [] existing)))}
        already-started? (and tick-id
                              (seq (into [] (es/read (:event-store context)
                                                    {:tenant-id (:tenant-id context)
                                                     :types #{:sheet/tree-tick-started}
                                                     :tags #{[:tick tick-id]}}))))
        inputs (when inputs
                 (reduce-kv (fn [acc k v]
                              (assoc acc (if (string? k) (keyword k) k) v))
                            {} inputs))
        input-sources (reduce-kv (fn [acc k source]
                                   (assoc acc (if (string? k) (keyword k) k) source))
                                 {} (or input-sources {}))]
    (if already-started?
      {:command-result/events []}
      (if inputs
      (let [instruction-overrides (:gepa/patched-instructions context)
            sheet-tenant-id (when (not= (:system-tenant-id context) (:tenant-id context))
                              (:system-tenant-id context))
            snapshot (build-execution-snapshot
                       context sheet-id
                       :use-version use-version
                       :force-draft force-draft
                       :instruction-overrides instruction-overrides
                       :sheet-tenant-id sheet-tenant-id)]
        (if (:cognitect.anomalies/category snapshot)
          snapshot
          (if-let [validation-error (logic/invalid-seed-input snapshot inputs)]
            {:cognitect.anomalies/category :cognitect.anomalies/incorrect
             :cognitect.anomalies/message validation-error}
            (let [snapshot-values (reduce-kv
                                  (fn [acc k entry]
                                    (if (some? (:value entry))
                                      (assoc acc k (:value entry))
                                      acc))
                                  {}
                                  (:blackboard-entries snapshot))
                seed-values (merge snapshot-values inputs)
                new-seed-events (into {}
                                      (for [[k v] seed-values
                                            :when (not (contains? input-sources k))]
                                        (let [value-id (random-uuid)]
                                          [k (->event
                                             {:type :sheet/execution-value-written
                                              :tags #{[:sheet sheet-id] [:tick new-tick-id]}
                                              :body (logic/prepare-write
                                                     context
                                                     {:tick-id new-tick-id
                                                      :sheet-id sheet-id
                                                      :key k
                                                      :value v
                                                      :value-id value-id
                                                      :tick-seed? true})})])))
                seed-sources (merge input-sources
                                    (into {} (map (fn [[k event]]
                                                   [k {:tick-id new-tick-id
                                                       :event-id (:value-id event)}])
                                                 new-seed-events)))
                structural-snapshot (update snapshot :blackboard-entries
                                            (fn [entries]
                                              (reduce-kv (fn [acc k entry]
                                                           (assoc acc k (dissoc entry :value)))
                                                         {} (or entries {}))))
                started-event (rm/event context
             {:type :sheet/tree-tick-started
              :tags (cond-> #{[:sheet sheet-id]
                              [:tick new-tick-id]}
                      parent-tick-id (conj [:parent-tick parent-tick-id])
                      (and (true? (:checkpointed-campaign? options))
                           (get-in options [:llm-budget-root-tick-id])
                           (not= new-tick-id
                                 (get-in options [:llm-budget-root-tick-id])))
                      (conj [:tick (get-in options
                                           [:llm-budget-root-tick-id])])
                      correlation-id (conj [:correlation correlation-id]))
              :body (cond-> {:sheet-id sheet-id
                             :tick-id new-tick-id
                             :seed-sources seed-sources
                             :execution-snapshot structural-snapshot}
                      parent-tick-id (assoc :parent-tick-id parent-tick-id)
                      correlation-id (assoc :correlation-id correlation-id)
                      (:version-number snapshot) (assoc :version-number (:version-number snapshot))
                      options (assoc :options options)
                      tool-context (assoc :tool-context tool-context))})]
              {:command-result/events
               (into [started-event] (vals new-seed-events))
               :command-result/cas tick-start-cas}))))
      (let [read-ctx (if (not= (:system-tenant-id context) (:tenant-id context))
                       (assoc context :tenant-id (:system-tenant-id context))
                       context)
            sheet (rm/get-sheet read-ctx sheet-id)
            root-node-id (:root-node-id sheet)]
        (cond
          (not sheet)
          {:cognitect.anomalies/category :cognitect.anomalies/not-found
           :cognitect.anomalies/message "Sheet not found"}

          (not root-node-id)
          {:cognitect.anomalies/category :cognitect.anomalies/incorrect
           :cognitect.anomalies/message "Sheet has no root node"}

          :else
          {:command-result/events
           [(rm/event context
             {:type :sheet/tree-tick-started
              :tags (cond-> #{[:sheet sheet-id]
                              [:tick new-tick-id]}
                      parent-tick-id (conj [:parent-tick parent-tick-id])
                      correlation-id (conj [:correlation correlation-id]))
              :body (cond-> {:sheet-id sheet-id
                             :tick-id new-tick-id}
                      parent-tick-id (assoc :parent-tick-id parent-tick-id)
                      correlation-id (assoc :correlation-id correlation-id)
                      tool-context (assoc :tool-context tool-context))})]
           :command-result/cas tick-start-cas}))))))

(defcommand :sheet tick-node
  {:authorized? logic/commands-authenticated?}
  "Start a single node tick (for testing or manual execution)."
  [{{:keys [sheet-id node-id tick-id overrides]} :command
    :as ctx}]
  (let [read-ctx (if (not= (:system-tenant-id ctx) (:tenant-id ctx))
                   (assoc ctx :tenant-id (:system-tenant-id ctx))
                   ctx)
        node (rm/get-node read-ctx sheet-id node-id)
        blackboard (rm/get-blackboard-by-key read-ctx sheet-id)
        inputs (reduce (fn [acc k]
                         (if-let [entry (get blackboard k)]
                           (assoc acc k (:value entry))
                           acc))
                       {}
                       (:reads node))
        inputs-with-overrides (merge inputs (or overrides {}))]
    (cond
      (not node)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Node not found"}

      (not= :leaf (:type node))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "Only leaf nodes can be ticked directly"}

      (not (:instruction node))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "Node has no instruction"}

      :else
      (let [new-tick-id (or tick-id (random-uuid))]
        {:command-result/events
         [(rm/event ctx
           {:type :sheet/node-execution-started
            :tags #{[:sheet sheet-id]
                    [:node node-id]
                    [:tick new-tick-id]}
            :body (stamp
                   ctx node
                   {:sheet-id sheet-id
                    :tick-id new-tick-id
                    :node-id node-id
                    :inputs inputs-with-overrides})})]}))))

(defcommand :sheet resume-node-execution
  {:authorized? logic/commands-authenticated?}
  "Re-enqueue one abandoned map, leaf, delegate, or researcher start after restart.

   The original start event id is a durable idempotency key. If that start has
   already completed, or a recovery start already points at it, this command is
   a no-op. A map start rebuilds its process-local coordinator from durable
   child evidence; other composite parents remain in progress and consume the
   recovered frontier's normal completion."
  [{{:keys [sheet-id tick-id node-id original-start-event-id inputs
            researcher-ownership-epoch]} :command
    :as ctx}]
  (let [tick-events (into [] (es/read (:event-store ctx)
                                      {:tenant-id (:tenant-id ctx)
                                       :tags #{[:tick tick-id]}}))
        original-index (first (keep-indexed
                               (fn [index event]
                                 (when (= original-start-event-id (:event/id event))
                                   index))
                               tick-events))
        events-after-original (if (some? original-index)
                                (subvec tick-events (inc original-index))
                                [])
        target-execution-key [node-id (logic/exec-context inputs)]
        completed? (some #(and (= :sheet/node-execution-completed (:event/type %))
                               (= target-execution-key
                                  (logic/execution-key %)))
                         events-after-original)
        cancelled? (rm/is-tick-or-ancestor-cancelled? ctx tick-id)
        already-resumed? (some #(and (= :sheet/node-execution-started (:event/type %))
                                     (= original-start-event-id
                                        (:resumed-from-event-id %)))
                               tick-events)]
    (cond
      (nil? original-index)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Original node execution start not found"}

      (or cancelled? completed? already-resumed?)
      {:command-result/events []}

      :else
      {:command-result/events
       [(rm/event ctx
         {:type :sheet/node-execution-started
          :tags #{[:sheet sheet-id] [:node node-id] [:tick tick-id]}
          :body (cond-> {:sheet-id sheet-id
                         :tick-id tick-id
                         :node-id node-id
                         :inputs inputs
                         :resumed-from-event-id original-start-event-id}
                  (logic/leased-node?
                   (get-in (rm/get-tick-execution-context ctx tick-id)
                           [:nodes-by-id node-id]))
                  (as-> body
                        (do (track-queued!
                             ctx (get-in (rm/get-tick-execution-context ctx tick-id)
                                         [:nodes-by-id node-id])
                             {:sheet-id sheet-id :tick-id tick-id
                              :node-id node-id :inputs inputs})
                            (merge body (logic/fields ctx))))
                  researcher-ownership-epoch
                  (assoc :researcher-ownership-epoch
                         researcher-ownership-epoch))})]
       :command-result/cas
       {:types #{:sheet/node-execution-started :sheet/node-execution-completed}
        :tags #{[:tick tick-id] [:node node-id]}
        :predicate-fn
        (fn [existing]
          (let [events (into [] existing)
                original-index (first (keep-indexed
                                       (fn [index event]
                                         (when (= original-start-event-id (:event/id event))
                                           index))
                                       events))
                later (if (some? original-index)
                        (subvec events (inc original-index))
                        [])]
            (and (some? original-index)
                 (not-any? #(or (and (= :sheet/node-execution-completed
                                         (:event/type %))
                                      (= target-execution-key
                                         (logic/execution-key %)))
                                 (and (= :sheet/node-execution-started
                                         (:event/type %))
                                      (= original-start-event-id
                                         (:resumed-from-event-id %))))
                           later))))}})))

(defcommand :sheet renew-node-execution-lease
  {:authorized? logic/commands-authenticated?}
  "Extend the durable ownership lease of one running leaf or delegate start."
  [{{:keys [sheet-id tick-id node-id exec-context start-event-id
            lease-owner lease-expires-at]} :command}]
  {:command-result/events
   [(->event
     {:type :sheet/node-execution-lease-renewed
      :tags #{[:sheet sheet-id] [:tick tick-id] [:node node-id]}
      :body (cond-> {:sheet-id sheet-id
                     :tick-id tick-id
                     :node-id node-id
                     :start-event-id start-event-id
                     :lease-owner lease-owner
                     :lease-expires-at lease-expires-at}
              (seq exec-context) (assoc :exec-context exec-context))})]})

(defn- commit-researcher-iteration-v2
  [{{:keys [sheet-id tick-id node-id resume-state iteration-record inputs resume?
            sandbox-snapshot-interval]}
    :command :as ctx}]
  (let [iteration-index (:iteration-index iteration-record)
        attempt-ordinal (:attempt-ordinal iteration-record)
        durable-facts (into [] (es/read (:event-store ctx)
                                        {:tenant-id (:tenant-id ctx)
                                         :types #{:rlm/researcher-iteration-recorded
                                                  :rlm/researcher-resume-state-saved}
                                         :tags #{[:tick tick-id] [:node node-id]}}))
        recorded? (some #(and (= :rlm/researcher-iteration-recorded (:event/type %))
                              (= iteration-index (:iteration-index %))
                              (= attempt-ordinal (:attempt-ordinal %)))
                        durable-facts)
        resume-facts (filterv #(= :rlm/researcher-resume-state-saved
                                  (:event/type %))
                              durable-facts)
        latest-durable-resume-state (some-> resume-facts last :resume-state)
        latest-resume-state
        (logic/hydrate-latest
         (mapv :resume-state resume-facts))
        latest-max-observed-quantum-duration-ms
        (long (or (:max-observed-quantum-duration-ms latest-resume-state) 0))
        proposed-observed-quantum-duration-ms
        (:observed-quantum-duration-ms resume-state)
        resume-state
        (if (some? proposed-observed-quantum-duration-ms)
          (assoc resume-state
                 :max-observed-quantum-duration-ms
                 (max latest-max-observed-quantum-duration-ms
                      (long (or (:max-observed-quantum-duration-ms resume-state)
                                0))
                      (long proposed-observed-quantum-duration-ms)))
          (if (contains? latest-resume-state
                         :max-observed-quantum-duration-ms)
            (assoc resume-state
                   :max-observed-quantum-duration-ms
                   latest-max-observed-quantum-duration-ms)
            (dissoc resume-state :max-observed-quantum-duration-ms)))
        resume-order (fn [state]
                       [(or (:revision state) -1)
                        (or (:next-iteration state) -1)])
        proposed-order (resume-order resume-state)
        stale-resume-state?
        (and latest-resume-state
             (not (neg? (compare (resume-order latest-resume-state)
                                 proposed-order))))
        cancelled? (rm/is-tick-or-ancestor-cancelled? ctx tick-id)
        already-recorded? (or cancelled? recorded? stale-resume-state?)
        durable-resume-state
        (logic/encode
         resume-state latest-durable-resume-state latest-resume-state
         (or sandbox-snapshot-interval 1))
        now (str (java.time.Instant/now))]
    (cond-> {:command-result/events
             (if already-recorded?
               []
               (cond->
                [(->event
                  {:type :rlm/researcher-iteration-recorded
                   :tags #{[:sheet sheet-id] [:tick tick-id] [:node node-id]}
                   :body {:sheet-id sheet-id
                          :tick-id tick-id
                          :node-id node-id
                          :iteration-index iteration-index
                          :attempt-ordinal attempt-ordinal
                          :iteration-record iteration-record
                          :recorded-at now}})
                 (->event
                  {:type :rlm/researcher-resume-state-saved
                   :tags #{[:sheet sheet-id] [:tick tick-id] [:node node-id]}
                   :body {:sheet-id sheet-id
                          :tick-id tick-id
                          :node-id node-id
                          :revision (:revision resume-state)
                          :next-iteration (:next-iteration resume-state)
                          :resume-state durable-resume-state
                          :yielded? (not= false resume?)
                          :saved-at now}})]
                 (not= false resume?)
                 (conj
                  (rm/event ctx
                   {:type :sheet/node-execution-started
                    :tags #{[:sheet sheet-id] [:tick tick-id] [:node node-id]}
                    :body {:sheet-id sheet-id
                           :tick-id tick-id
                           :node-id node-id
                           :inputs inputs
                           :researcher-resume? true
                           :checkpoint-version (:version resume-state)
                           :checkpoint-next-iteration (:next-iteration resume-state)}}))))}
      (not already-recorded?)
      (assoc :command-result/cas
             {:types #{:rlm/researcher-iteration-recorded
                       :rlm/researcher-resume-state-saved
                       :rlm/researcher-frontier-claimed
                       :rlm/researcher-classification-expired
                       :sheet/node-execution-completed
                       :sheet/tree-tick-completed
                       :sheet/tick-cancelled}
              :tags #{[:tick tick-id]}
              :predicate-fn
              (fn [existing]
                (let [{:keys [iteration-recorded? latest-state
                              frontier-seen? frontier-epoch terminal?]}
                      (reduce
                       (fn [state event]
                         (case (:event/type event)
                           :rlm/researcher-frontier-claimed
                           (if (= node-id (:node-id event))
                             (-> state
                                 (assoc :frontier-seen? true)
                                 (update :frontier-epoch max
                                         (:ownership-epoch event)))
                             state)

                           :rlm/researcher-iteration-recorded
                           (if (and (= node-id (:node-id event))
                                    (= iteration-index
                                       (:iteration-index event))
                                    (= attempt-ordinal
                                       (:attempt-ordinal event)))
                             (assoc state :iteration-recorded? true)
                             state)

                           :rlm/researcher-resume-state-saved
                           (if (= node-id (:node-id event))
                             (let [candidate (:resume-state event)]
                               (if (or (nil? (:latest-state state))
                                       (neg? (compare
                                              (resume-order (:latest-state state))
                                              (resume-order candidate))))
                                 (assoc state :latest-state candidate)
                                 state))
                             state)

                           :rlm/researcher-classification-expired
                           (if (= node-id (:node-id event))
                             (assoc state :terminal? true)
                             state)

                           :sheet/node-execution-completed
                           (if (and (= node-id (:node-id event))
                                    (or (= :terminal (:completion-kind event))
                                        (contains? #{:success :failure :timeout
                                                     :blocked}
                                                   (:status event))))
                             (assoc state :terminal? true)
                             state)

                           :sheet/tree-tick-completed
                           (if (not= :running (:root-status event))
                             (assoc state :terminal? true)
                             state)

                           :sheet/tick-cancelled
                           (assoc state :terminal? true)

                           state))
                       {:iteration-recorded? false
                        :latest-state nil
                        :frontier-seen? false
                        :frontier-epoch 0
                        :terminal? false}
                       existing)]
                  (and
                   (not terminal?)
                   (not iteration-recorded?)
                   (or (nil? latest-state)
                       (neg? (compare (resume-order latest-state)
                                     proposed-order)))
                   (>= (long (or (:max-observed-quantum-duration-ms resume-state)
                                 0))
                       (long (or (:max-observed-quantum-duration-ms latest-state)
                                 0)))
                   (or (not frontier-seen?)
                       (= frontier-epoch (:ownership-epoch resume-state))))))}))))

(defcommand :sheet checkpoint-researcher-iteration
  {:authorized? logic/commands-authenticated?}
  "Atomically persist a completed researcher iteration and enqueue its next quantum."
  [{{:keys [sheet-id tick-id node-id checkpoint resume-state iteration-record
            inputs resume?]}
    :command :as ctx}]
  (if (and resume-state iteration-record)
    (commit-researcher-iteration-v2 ctx)
    (let [latest (some->> (into [] (es/read (:event-store ctx)
                                          {:tenant-id (:tenant-id ctx)
                                           :tags #{[:tick tick-id] [:node node-id]}}))
                        (filter #(= :rlm/researcher-checkpointed (:event/type %)))
                        last
                        :checkpoint)
        latest-order [(or (:revision latest) 0) (or (:next-iteration latest) -1)]
        checkpoint-order [(or (:revision checkpoint) 0) (:next-iteration checkpoint)]
        cancelled? (rm/is-tick-or-ancestor-cancelled? ctx tick-id)
        already-recorded? (or cancelled?
                              (not (neg? (compare latest-order checkpoint-order))))]
    (cond-> {:command-result/events
             (if already-recorded?
               []
               (cond->
                [(->event
                  {:type :rlm/researcher-checkpointed
                   :tags #{[:sheet sheet-id] [:tick tick-id] [:node node-id]}
                   :body {:sheet-id sheet-id
                          :tick-id tick-id
                          :node-id node-id
                          :checkpoint checkpoint
                          :yielded? (not= false resume?)
                          :checkpointed-at (str (java.time.Instant/now))}})]
                 (not= false resume?)
                 (conj
                  (rm/event ctx
                   {:type :sheet/node-execution-started
                    :tags #{[:sheet sheet-id] [:tick tick-id] [:node node-id]}
                    :body {:sheet-id sheet-id
                           :tick-id tick-id
                           :node-id node-id
                           :inputs inputs
                           :researcher-resume? true
                           :checkpoint-version (:version checkpoint)
                           :checkpoint-next-iteration (:next-iteration checkpoint)}}))))}
      (not already-recorded?)
      (assoc :command-result/cas
             {:types #{:rlm/researcher-checkpointed}
              :tags #{[:tick tick-id] [:node node-id]}
              :predicate-fn
              (fn [existing]
                (not-any?
                 #(and (= :rlm/researcher-checkpointed (:event/type %))
                       (not (neg?
                             (compare [(get-in % [:checkpoint :revision] 0)
                                       (get-in % [:checkpoint :next-iteration] -1)]
                                      checkpoint-order))))
                 (into [] existing))) })))))

(defcommand :sheet claim-researcher-frontier
  {:authorized? logic/commands-authenticated?}
  "Atomically acquire one checkpointed researcher campaign frontier epoch."
  [{{:keys [sheet-id tick-id node-id budget-root-tick-id ownership-epoch claimed-at
            campaign-started-at-ms campaign-deadline-ms]} :command :as ctx}]
  (let [campaign-tag (logic/campaign-tag sheet-id tick-id node-id)]
    {:command-result/events
     [(rm/event ctx
       {:type :rlm/researcher-frontier-claimed
        :tags (cond-> #{[:sheet sheet-id] [:tick tick-id]
                        [:node node-id] campaign-tag}
                (and budget-root-tick-id
                     (not= budget-root-tick-id tick-id))
                (conj [:tick budget-root-tick-id]))
        :body (cond-> {:sheet-id sheet-id
                       :tick-id tick-id
                       :node-id node-id
                       :ownership-epoch ownership-epoch
                       :claimed-at claimed-at}
                (some? campaign-started-at-ms)
                (assoc :campaign-started-at-ms campaign-started-at-ms)
                (some? campaign-deadline-ms)
                (assoc :campaign-deadline-ms campaign-deadline-ms))})]
     :command-result/cas
     (logic/frontier-cas campaign-tag ownership-epoch)}))

(defcommand :sheet claim-researcher-effect
  {:authorized? logic/commands-authenticated?}
  "Atomically claim an effect under the campaign's current ownership epoch."
  [{{:keys [sheet-id tick-id node-id budget-root-tick-id iteration-index logical-action-identity
            attempt-identity attempt-ordinal ownership-epoch kind claimed-at]}
    :command :as ctx}]
  (let [campaign-tag (logic/campaign-tag sheet-id tick-id node-id)
        expected-attempt-identity
        (logic/attempt-identity
         logical-action-identity ownership-epoch attempt-ordinal)]
    (if (not= expected-attempt-identity attempt-identity)
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message
       "Researcher effect attempt identity does not match logical action, epoch, and ordinal"}
      {:command-result/events
       [(rm/event ctx
         {:type :rlm/researcher-effect-claimed
          :tags (cond-> #{[:sheet sheet-id] [:tick tick-id]
                          [:node node-id] campaign-tag}
                  (and budget-root-tick-id
                       (not= budget-root-tick-id tick-id))
                  (conj [:tick budget-root-tick-id]))
          :body {:sheet-id sheet-id
                 :tick-id tick-id
                 :node-id node-id
                 :iteration-index iteration-index
                 :logical-action-identity logical-action-identity
                 :attempt-identity attempt-identity
                 :attempt-ordinal attempt-ordinal
                 :ownership-epoch ownership-epoch
                 :kind kind
                 :status :claimed
                 :claimed-at claimed-at
                 :resolved-at nil}})]
       :command-result/cas
       (logic/claim-cas campaign-tag ownership-epoch
                                     logical-action-identity attempt-identity)})))

(defcommand :sheet reserve-provider-call
  {:authorized? logic/commands-authenticated?}
  "Atomically consume one durable provider-call slot before dispatch."
  [{{:keys [budget-sheet-id budget-tick-id tick-id node-id admission-kind
            campaign-sheet-id campaign-tick-id campaign-node-id invocation-identity]
     :as reservation} :command}]
  (if (not= (logic/reservation-identity reservation) invocation-identity)
    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
     :cognitect.anomalies/message "Provider invocation identity does not match its canonical owner and physical attempt"}
    {:command-result/events
     [(->event
       {:type logic/reservation-event-type
        :tags (cond-> #{[:sheet budget-sheet-id] [:tick budget-tick-id]
                        [:llm-budget-root budget-tick-id]
                        [:provider-invoking-tick tick-id] [:provider-node node-id]}
                (not= :ordinary admission-kind)
                (conj [:researcher-campaign-tick campaign-tick-id]
                      (logic/campaign-tag campaign-sheet-id campaign-tick-id campaign-node-id)))
        :body (select-keys reservation
                           [:admission-kind :budget-sheet-id :budget-tick-id
                            :sheet-id :tick-id :node-id :node-start-id :exec-context
                            :candidate-index :node-attempt
                            :campaign-sheet-id :campaign-tick-id :campaign-node-id
                            :iteration-index :logical-action-identity :invocation-identity
                            :provider-attempt-ordinal :ownership-epoch :reserved-at
                            :model-configuration-id :model-logical-action-id])})]
     :command-result/cas (logic/reservation-cas reservation)}))

(defcommand :sheet complete-researcher-effect
  {:authorized? logic/commands-authenticated?}
  "Atomically record an observed effect outcome while its claim still owns the frontier."
  [{{:keys [sheet-id tick-id node-id logical-action-identity attempt-identity
            ownership-epoch result resolved-at]}
    :command}]
  (let [campaign-tag (logic/campaign-tag sheet-id tick-id node-id)]
    {:command-result/events
     [(->event
       {:type :rlm/researcher-effect-completed
        :tags #{[:sheet sheet-id] [:tick tick-id] [:node node-id] campaign-tag}
        :body {:sheet-id sheet-id
               :tick-id tick-id
               :node-id node-id
               :logical-action-identity logical-action-identity
               :attempt-identity attempt-identity
               :ownership-epoch ownership-epoch
               :status :completed
               :result result
               :resolved-at resolved-at}})]
     :command-result/cas
     (logic/completion-cas campaign-tag ownership-epoch
                                        logical-action-identity attempt-identity)}))

(defcommand :sheet mark-researcher-effect-indeterminate
  {:authorized? logic/commands-authenticated?}
  "Atomically settle an unresolved effect after a newer campaign frontier wins."
  [{{:keys [sheet-id tick-id node-id logical-action-identity attempt-identity
            ownership-epoch resolved-at]}
    :command}]
  (let [campaign-tag (logic/campaign-tag sheet-id tick-id node-id)]
    {:command-result/events
     [(->event
       {:type :rlm/researcher-effect-indeterminate
        :tags #{[:sheet sheet-id] [:tick tick-id] [:node node-id] campaign-tag}
        :body {:sheet-id sheet-id
               :tick-id tick-id
               :node-id node-id
               :logical-action-identity logical-action-identity
               :attempt-identity attempt-identity
               :resolved-by-ownership-epoch ownership-epoch
               :status :indeterminate
               :resolved-at resolved-at}})]
     :command-result/cas
     (logic/indeterminate-cas
      campaign-tag ownership-epoch logical-action-identity attempt-identity)}))

(defcommand :sheet record-researcher-action
  {:authorized? logic/commands-authenticated?}
  "Durably record one logical provider/tool action result before Phase 1 continues."
  [{{:keys [sheet-id tick-id node-id action-id action-kind iteration result completed-at]}
    :command :as ctx}]
  (let [action-tag-id (java.util.UUID/nameUUIDFromBytes (.getBytes action-id "UTF-8"))
        recorded? (seq (into [] (es/read (:event-store ctx)
                                         {:tenant-id (:tenant-id ctx)
                                          :types #{:rlm/researcher-action-completed}
                                          :tags #{[:researcher-action action-tag-id]}})))]
    (cond-> {:command-result/events
     (if recorded?
       []
       [(->event
       {:type :rlm/researcher-action-completed
        :tags #{[:sheet sheet-id] [:tick tick-id] [:node node-id]
                [:researcher-action action-tag-id]}
        :body {:sheet-id sheet-id
               :tick-id tick-id
               :node-id node-id
               :action-id action-id
               :action-kind action-kind
               :iteration iteration
               :result result
               :completed-at completed-at}})])}
      (not recorded?)
      (assoc :command-result/cas
             {:types #{:rlm/researcher-action-completed}
              :tags #{[:researcher-action action-tag-id]}
              :predicate-fn (fn [existing]
                              (not-any? #(= action-id (:action-id %))
                                        (into [] existing)))}))))

(defcommand :sheet complete-node-execution
  {:authorized? logic/commands-authenticated?}
  "Complete a node execution (internal command from todo processor).
   For tick-scoped executions, also emits execution-value-written events
   atomically with the completion event to avoid race conditions.

   Optional :usage carries per-node token counts from LLM calls."
  [{{:keys [sheet-id tick-id node-id completion-id researcher-ownership-epoch
            status writes rejected-writes missing-write-keys write-sources write-references? duration-ms
            observed-quantum-duration-ms max-observed-quantum-duration-ms error inputs usage own-usage model
            node-type completion-kind raw-response failure-kind provider-evidence model-fallback
            condition-answer decision block-payload read-sources
            requested-model resolved-model]} :command
    :as ctx}]
  (if (or (rm/is-tick-or-ancestor-cancelled? ctx tick-id)
          (and completion-id
               (some #(and (= completion-id (:completion-id %))
                           (= (logic/exec-context inputs)
                              (logic/exec-context (:inputs %))))
                     (into [] (es/read (:event-store ctx)
                                       {:tenant-id (:tenant-id ctx)
                                        :types #{:sheet/node-execution-completed}
                                        :tags #{[:tick tick-id]}})))))
    {:command-result/events []}
    (let [researcher-resume-events
          (when researcher-ownership-epoch
            (into []
                  (es/read (:event-store ctx)
                           {:tenant-id (:tenant-id ctx)
                            :types #{:rlm/researcher-resume-state-saved}
                            :tags #{[:tick tick-id] [:node node-id]}})))
          latest-researcher-resume-state
          (some-> researcher-resume-events last :resume-state)
          latest-max-observed-quantum-duration-ms
          (long (or (:max-observed-quantum-duration-ms
                     latest-researcher-resume-state)
                    0))
          max-observed-quantum-duration-ms
          (when (some? observed-quantum-duration-ms)
            (max latest-max-observed-quantum-duration-ms
                 (long (or max-observed-quantum-duration-ms 0))
                 (long observed-quantum-duration-ms)))
        derived-kind (when (and (nil? completion-kind)
                                (= :repl-researcher node-type))
                       (cond
                         (= :tree-generated status) :tree-iteration
                         (contains? #{:success :failure :timeout} status) :terminal
                         :else nil))
        effective-completion-kind (or completion-kind derived-kind)
        exec-context (into {} (filter (fn [[k _]] (and (keyword? k) (namespace k))) inputs))
        read-values (into {} (filter (fn [[k _]] (and (keyword? k) (nil? (namespace k)))) inputs))
        tick-scoped? (some? (rm/get-tick-execution-context ctx tick-id))
        externalize-writes? (and tick-scoped?
                                 (#{:success :tree-generated :blocked} status)
                                 (seq writes))
        forwarded-sources (into {}
                                (for [[k source] (or write-sources {})]
                                  [k source]))
        forwarded-writes (when (seq forwarded-sources)
                           (rm/resolve-values
                            ctx (:tenant-id ctx) tick-id
                            (into {} (map (fn [[k source]] [k {:source source}]))
                                  forwarded-sources)
                            (keys forwarded-sources)))
        write-profile (logic/profile-values (merge forwarded-writes writes))
        cross-tick-sources (if write-references?
                             (into {}
                                   (filter (fn [[_ source]]
                                             (not= tick-id (:tick-id source))))
                                   forwarded-sources)
                             {})
        reference-events (mapv (fn [[k source]]
                                 (->event {:type :sheet/execution-value-referenced
                                           :tags #{[:sheet sheet-id] [:tick tick-id]}
                                           :body (cond-> {:tick-id tick-id
                                                          :sheet-id sheet-id
                                                          :key k
                                                          :source source
                                                          :node-id node-id}
                                                   (seq exec-context)
                                                   (assoc :exec-context exec-context))}))
                               cross-tick-sources)
        completion-event (rm/event ctx
                           {:type :sheet/node-execution-completed
                            :tags #{[:sheet sheet-id]
                                    [:node node-id]
                                    [:tick tick-id]}
                            :body (cond-> {:sheet-id sheet-id
                                           :tick-id tick-id
                                           :node-id node-id
                                           :status status}
                                    completion-id (assoc :completion-id completion-id)
                                    researcher-ownership-epoch
                                    (assoc :researcher-ownership-epoch
                                           researcher-ownership-epoch)
                                    (or (seq writes) (seq forwarded-sources))
                                    (assoc :write-keys (vec (distinct (concat (keys writes)
                                                                             (keys forwarded-sources))))
                                           :write-profile write-profile)
                                    (seq missing-write-keys) (assoc :missing-write-keys missing-write-keys)
                                    (seq rejected-writes)
                                    (assoc :rejected-write-keys (vec (keys rejected-writes))
                                           :rejected-write-profile (logic/profile-values rejected-writes))
                                    (seq forwarded-sources)
                                    (assoc :write-sources forwarded-sources)
                                    (and (seq writes) (not externalize-writes?))
                                    (assoc :writes writes)
                                    duration-ms (assoc :duration-ms duration-ms)
                                    (some? observed-quantum-duration-ms)
                                    (assoc :observed-quantum-duration-ms
                                           observed-quantum-duration-ms)
                                    (some? max-observed-quantum-duration-ms)
                                    (assoc :max-observed-quantum-duration-ms
                                           max-observed-quantum-duration-ms)
                                    error (assoc :error error)
                                    raw-response (assoc :raw-response raw-response)
                                    failure-kind (assoc :failure-kind failure-kind)
                                    model-fallback (assoc :model-fallback model-fallback)
                                    (boolean? condition-answer) (assoc :condition-answer condition-answer)
                                    decision (assoc :decision decision)
                                    provider-evidence (assoc :provider-evidence provider-evidence)
                                    (seq exec-context) (assoc :inputs exec-context)
                                    (seq read-values)
                                    (assoc :read-keys (vec (keys read-values))
                                           :input-profile (logic/profile-values read-values))
                                    (seq read-sources)
                                    (assoc :read-sources read-sources)
                                    (seq usage) (assoc :usage usage)
                                    (seq own-usage) (assoc :own-usage own-usage)
                                    model (assoc :model model)
                                    requested-model (assoc :requested-model requested-model)
                                    resolved-model (assoc :resolved-model resolved-model)
                                    (some? node-type) (assoc :node-type node-type)
                                    (some? effective-completion-kind)
                                    (assoc :completion-kind effective-completion-kind)
                                    (= :blocked status)
                                    (assoc :block-payload block-payload))})
        bb-write-events (when externalize-writes?
                          (mapv (fn [[k v]]
                                  (->event
                                    {:type :sheet/execution-value-written
                                     :tags #{[:sheet sheet-id]
                                             [:tick tick-id]}
                                     :body (logic/prepare-write
                                            ctx
                                            (cond-> {:tick-id tick-id
                                                     :sheet-id sheet-id
                                                     :key k
                                                     :value v
                                                     :node-id node-id}
                                              (seq exec-context)
                                              (assoc :exec-context exec-context)))}))
                                writes))
        rejected-write-events
        (mapv (fn [[k v]]
                (->event
                 {:type :sheet/execution-value-rejected
                  :tags #{[:sheet sheet-id] [:tick tick-id]}
                  :body (logic/prepare-write
                         ctx
                         (cond-> {:tick-id tick-id
                                  :sheet-id sheet-id
                                  :key k
                                  :value v
                                  :node-id node-id}
                           (seq exec-context)
                           (assoc :exec-context exec-context)))}))
              rejected-writes)]
      (cond-> {:command-result/events
               (into [] (concat bb-write-events rejected-write-events reference-events [completion-event]))}
        researcher-ownership-epoch
        (assoc :command-result/cas
               {:types #{:rlm/researcher-frontier-claimed
                         :rlm/researcher-resume-state-saved
                         :sheet/node-execution-completed
                         :sheet/tree-tick-completed
                         :sheet/tick-cancelled}
                :tags #{[:tick tick-id]}
                :predicate-fn
                (fn [existing]
                  (let [{:keys [frontier-epoch latest-max terminal?]}
                        (reduce
                         (fn [state event]
                           (case (:event/type event)
                             :rlm/researcher-frontier-claimed
                             (if (= node-id (:node-id event))
                               (update state :frontier-epoch max
                                       (:ownership-epoch event))
                               state)

                             :rlm/researcher-resume-state-saved
                             (if (= node-id (:node-id event))
                               (update state :latest-max max
                                       (long
                                        (or (get-in event
                                                    [:resume-state
                                                     :max-observed-quantum-duration-ms])
                                            0)))
                               state)

                             :sheet/node-execution-completed
                             (if (= node-id (:node-id event))
                               (assoc state :terminal? true)
                               state)

                             :sheet/tree-tick-completed
                             (if (not= :running (:root-status event))
                               (assoc state :terminal? true)
                               state)

                             :sheet/tick-cancelled
                             (assoc state :terminal? true)

                             state))
                         {:frontier-epoch 0
                          :latest-max 0
                          :terminal? false}
                         existing)]
                    (and (not terminal?)
                         (= frontier-epoch researcher-ownership-epoch)
                         (>= (long (or max-observed-quantum-duration-ms 0))
                             latest-max))))})

        (and completion-id (not researcher-ownership-epoch))
        (assoc :command-result/cas
               {:types #{:sheet/node-execution-completed}
                :tags #{[:tick tick-id]}
                :predicate-fn
                (fn [existing]
                  (not-any? #(and (= completion-id (:completion-id %))
                                  (= (logic/exec-context inputs)
                                     (logic/exec-context (:inputs %))))
                            (into [] existing)))})))))

(defcommand :sheet record-rlm-tree-node-completion
  {:authorized? logic/commands-authenticated?}
  "Emit a :sheet/rlm-tree-node-completed event with the precomputed
   structured node-path, the node's :usage, and an optional :input-profile
   capturing input characteristics for each :reads key. Fires alongside
   the generic complete-node-execution event for LLM leaf calls.
   Reserved for the RLM learning loop — future fields (scores, feedback)
   will be added by downstream judges."
  [{{:keys [sheet-id tick-id node-id node-path usage input-profile]} :command
    :as _ctx}]
  {:command-result/events
   [(->event
      {:type :sheet/rlm-tree-node-completed
       :tags #{[:sheet sheet-id]
               [:node node-id]
               [:tick tick-id]}
       :body (cond-> {:sheet-id sheet-id
                      :tick-id tick-id
                      :node-id node-id
                      :node-path node-path
                      :usage usage}
               (seq input-profile) (assoc :input-profile input-profile))})]})

(defcommand :sheet record-rlm-tree-execution-completion
  {:authorized? logic/commands-authenticated?}
  "Emit a :sheet/rlm-tree-execution-completed bookend event when a Phase 2
   RLM tree-execution finishes. Carries the full trajectory of events for
   the tick, total usage, task-fingerprint placeholder (issue 012), and
   the C-2a-2 fields :tree-fingerprint + :status + :duration-ms that drive
   the per-tree-fingerprint rolling-metrics aggregator. The new fields are
   carried in the event body — the partition-by-fingerprint read-model
   reads :tree-fingerprint from the event body directly (tag values must
   be UUIDs in event-store-v3, so we don't tag with the string fingerprint).

   RR-23: also tags the event `[:source-tick source-tick-id]` when
   `:source-tick-id` is present — the SAME campaign identity already
   carried in the body (HP-2), now also a tag, so the bookend is
   addressable by the campaign that produced it rather than only by its
   own ephemeral Phase-2 [:sheet :tick]. This is the missing half of the
   bookend's identity: `:sheet`/`:tick` above name the throwaway execution
   it ran IN; `:source-tick` names the campaign it belongs TO. Omitted
   (like the body field) when `:source-tick-id` is absent — a bookend
   written before this slice, or one whose caller never supplied it, is
   correctly untagged rather than tagged with a fabricated value."
  [{{:keys [sheet-id tick-id trajectory total-usage task-fingerprint
            tree-fingerprint status duration-ms generated-tree
            generated-tree-source source-sheet-id source-tick-id]} :command
    :as _ctx}]
  {:command-result/events
   [(->event
      (cond-> {:type :sheet/rlm-tree-execution-completed
               :tags (cond-> #{[:sheet sheet-id]
                               [:tick tick-id]}
                       (some? source-tick-id) (conj [:source-tick source-tick-id]))
               :body {:sheet-id sheet-id
                      :tick-id tick-id
                      :trajectory trajectory
                      :total-usage total-usage
                      :timestamp (java.time.Instant/now)
                      :task-fingerprint task-fingerprint}}
        (some? tree-fingerprint) (assoc-in [:body :tree-fingerprint] tree-fingerprint)
        (some? status)           (assoc-in [:body :status] status)
        (some? duration-ms)      (assoc-in [:body :duration-ms] duration-ms)
        (some? generated-tree)   (assoc-in [:body :generated-tree] generated-tree)
        (some? generated-tree-source)
        (assoc-in [:body :generated-tree-source] generated-tree-source)
        (some? source-sheet-id)  (assoc-in [:body :source-sheet-id] source-sheet-id)
        (some? source-tick-id)   (assoc-in [:body :source-tick-id] source-tick-id)))]})

(defcommand :sheet fail-node-execution
  {:authorized? logic/commands-authenticated?}
  "Mark a node execution as failed (internal command from todo processor)."
  [{{:keys [sheet-id tick-id node-id error duration-ms
            researcher-expected-frontier-epoch]} :command
    :as ctx}]
  (if (rm/is-tick-or-ancestor-cancelled? ctx tick-id)
    {:command-result/events []}
    (cond->
     {:command-result/events
      [(rm/event ctx
        {:type :sheet/node-execution-completed
         :tags #{[:sheet sheet-id]
                 [:node node-id]
                 [:tick tick-id]}
         :body (cond-> {:sheet-id sheet-id
                        :tick-id tick-id
                        :node-id node-id
                        :status :failure}
                 error (assoc :error error)
                 duration-ms (assoc :duration-ms duration-ms))})]}
      (some? researcher-expected-frontier-epoch)
      (assoc
       :command-result/cas
       {:types #{:rlm/researcher-frontier-claimed
                 :sheet/node-execution-completed
                 :sheet/tree-tick-completed
                 :sheet/tick-cancelled}
        :tags #{[:tick tick-id]}
        :predicate-fn
        (fn [existing]
          (let [{:keys [frontier-epoch terminal?]}
                (reduce
                 (fn [state event]
                   (case (:event/type event)
                     :rlm/researcher-frontier-claimed
                     (if (= node-id (:node-id event))
                       (update state :frontier-epoch max
                               (:ownership-epoch event))
                       state)

                     :sheet/node-execution-completed
                     (if (= node-id (:node-id event))
                       (assoc state :terminal? true)
                       state)

                     :sheet/tree-tick-completed
                     (if (not= :running (:root-status event))
                       (assoc state :terminal? true)
                       state)

                     :sheet/tick-cancelled
                     (assoc state :terminal? true)

                     state))
                 {:frontier-epoch 0 :terminal? false}
                 existing)]
            (and (not terminal?)
                 (= frontier-epoch
                    researcher-expected-frontier-epoch))))}))))

(defcommand :sheet cancel-tick
  {:authorized? logic/commands-authenticated?}
  "Cancel a running tick. Prevents further re-ticks."
  [{{:keys [sheet-id tick-id reason]} :command
    :as ctx}]
  (let [reason (logic/normalize-cancellation-reason reason)]
    (if (contains? #{:completed :cancelled} (:status (rm/get-tick ctx tick-id)))
      {:command-result/events []}
      {:command-result/events
       [(rm/event ctx
         {:type :sheet/tick-cancelled
          :tags #{[:sheet sheet-id]
                  [:tick tick-id]}
          :body {:sheet-id sheet-id
                 :tick-id tick-id
                 :reason reason}})]
       :command-result/cas
       {:types #{:sheet/tree-tick-completed :sheet/tick-cancelled}
        :tags #{[:tick tick-id]}
        :predicate-fn
        (fn [existing]
          (not-any? #(or (= :sheet/tick-cancelled (:event/type %))
                         (and (= :sheet/tree-tick-completed (:event/type %))
                              (not= :running (:root-status %))))
                    (into [] existing)))}})))

(defcommand :sheet emit-tick-started
  {:authorized? logic/commands-authenticated?}
  "System command: record that a tree tick execution has started (for in-progress tracking)."
  [{{:keys [sheet-id tick-id]} :command :as ctx}]
  {:command-result/events
   [(rm/event ctx {:type :sheet/tree-tick-started
              :tags #{[:sheet sheet-id] [:tick tick-id]}
              :body {:sheet-id sheet-id
                     :tick-id tick-id}})]})

(defcommand :sheet emit-tick-completed
  {:authorized? logic/commands-authenticated?}
  "System command: record that a tree tick execution has completed."
  [{{:keys [sheet-id tick-id correlation-id root-status iteration
            configured-max-ticks consumed-ticks terminal-reason output-keys
            block-payload error]} :command
    :as ctx}]
  {:command-result/events
     [(rm/event ctx {:type :sheet/tree-tick-completed
                :tags (cond-> #{[:sheet sheet-id] [:tick tick-id]}
                        correlation-id (conj [:correlation correlation-id]))
                :body (cond-> {:sheet-id sheet-id
                               :tick-id tick-id
                               :root-status root-status}
                        correlation-id (assoc :correlation-id correlation-id)
                        iteration (assoc :iteration iteration)
                        configured-max-ticks (assoc :configured-max-ticks configured-max-ticks)
                        consumed-ticks (assoc :consumed-ticks consumed-ticks)
                        terminal-reason (assoc :terminal-reason terminal-reason)
                        output-keys (assoc :output-keys output-keys)
                        (= :blocked root-status)
                        (assoc :block-payload block-payload)
                        error (assoc :error error))})]
     :command-result/cas
     {:types #{:sheet/tree-tick-completed :sheet/tick-cancelled}
      :tags #{[:tick tick-id]}
      :predicate-fn
      (fn [existing]
        (not-any? #(or (and (= :sheet/tick-cancelled (:event/type %))
                            (not= :timeout root-status))
                       (and (= :sheet/tree-tick-completed (:event/type %))
                            (not= :running (:root-status %))))
                  (into [] existing)))}})

(defcommand :sheet store-execution-trace
  {:authorized? logic/commands-authenticated?}
  "System command: publish the singular creation fact for an execution trace."
  [{{:keys [trace-id] :as command} :command}]
  {:command-result/events
   [(logic/execution-trace-event :sheet/execution-traced command)]
   :command-result/cas
   {:types logic/trace-publication-event-types
    :tags #{[:trace trace-id]}
    :predicate-fn (fn [existing] (empty? (into [] existing)))}})

(defcommand :sheet refresh-execution-trace
  {:authorized? logic/commands-authenticated?}
  "System command: publish a strictly newer revision of an existing execution trace."
  [{{:keys [trace-id source-event-count] :as command} :command}]
  {:command-result/events
   [(logic/execution-trace-event :sheet/execution-trace-refreshed command)]
   :command-result/cas
   {:types logic/trace-publication-event-types
    :tags #{[:trace trace-id]}
    :predicate-fn
    (fn [existing]
      (let [existing (into [] existing)]
        (and (seq existing)
             (every? #(let [existing-count (:source-event-count %)]
                        (and (some? existing-count)
                             (< existing-count source-event-count)))
                     existing))))}})

(defn- create-snapshot
  "Create a snapshot of the current sheet state."
  [ctx sheet-id]
  (let [sheet (rm/get-sheet ctx sheet-id)
        nodes-by-id (rm/get-nodes-by-id ctx sheet-id)
        blackboard (rm/get-blackboard-for-sheet ctx sheet-id)]
    {:sheet {:name (:name sheet) :id sheet-id}
     :blackboard-schema (into {}
                              (map (fn [bb] [(:key bb) (:schema bb)])
                                   blackboard))
     :nodes (logic/commands-build-node-tree nodes-by-id (:root-node-id sheet))}))

(defcommand :sheet publish-version
  {:authorized? logic/commands-authenticated?}
  "Create a new published version from the current draft state.
   Captures all nodes and blackboard schema as an immutable snapshot."
  [{{:keys [sheet-id description]} :command
    :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)]
    (cond
      (not sheet)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Sheet not found"}

      (not (:root-node-id sheet))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "Cannot publish sheet without root node"}

      :else
      (let [snapshot (create-snapshot ctx sheet-id)
            current-version (or (:published-version sheet) 0)
            new-version (inc current-version)
            snapshot-id (random-uuid)]
        {:command-result/events
         [(->event
           {:type :sheet/version-published
            :tags #{[:sheet sheet-id]
                    [:version snapshot-id]}
            :body (cond-> {:sheet-id sheet-id
                           :snapshot-id snapshot-id
                           :version-number new-version
                           :snapshot snapshot}
                    description (assoc :description description))})]}))))

(defcommand :sheet revert-to-version
  {:authorized? logic/commands-authenticated?}
  "Discard current draft and restore state from a published version.
   If the draft has changes, it will be stashed first for recovery."
  [{{:keys [sheet-id version-number]} :command
    :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)
        version (rm/get-version ctx sheet-id version-number)
        specificity-feedback (some-> version :snapshot logic/snapshot-schema-feedback)]
    (cond
      (not sheet)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Sheet not found"}

      (not version)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message (str "Version " version-number " not found")}

      specificity-feedback
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message (str "Cannot restore version: " specificity-feedback)}

      :else
      (let [
            draft-dirty? (:draft-dirty? sheet)
            stash-events (when draft-dirty?
                           (let [snapshot (create-snapshot ctx sheet-id)
                                 stash-id (random-uuid)]
                             [(->event
                               {:type :sheet/draft-stashed
                                :tags #{[:sheet sheet-id]}
                                :body {:sheet-id sheet-id
                                       :stash-id stash-id
                                       :snapshot snapshot}})]))
            revert-event (->event
                          {:type :sheet/draft-reverted
                           :tags #{[:sheet sheet-id]}
                           :body {:sheet-id sheet-id
                                  :target-version version-number
                                  :snapshot-id (:snapshot-id version)
                                  :snapshot (:snapshot version)}})]
        {:command-result/events
         (if stash-events
           (conj (vec stash-events) revert-event)
           [revert-event])}))))

(defcommand :sheet restore-stash
  {:authorized? logic/commands-authenticated?}
  "Restore the stashed draft. The stash is consumed after restoration."
  [{{:keys [sheet-id]} :command
    :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)
        stash (rm/get-stash ctx sheet-id)
        specificity-feedback (some-> stash :snapshot logic/snapshot-schema-feedback)]
    (cond
      (not sheet)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Sheet not found"}

      (not stash)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "No stash found"}

      specificity-feedback
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message (str "Cannot restore stash: " specificity-feedback)}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/stash-restored
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id
                 :stash-id (:stash-id stash)
                 :snapshot (:snapshot stash)}})]})))

(defcommand :sheet set-execution-mode
  {:authorized? logic/commands-authenticated?}
  "Toggle between executing draft or published version."
  [{{:keys [sheet-id mode]} :command
    :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)]
    (cond
      (not sheet)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Sheet not found"}

      (and (= mode :published) (nil? (:published-version sheet)))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "No published version exists. Publish first."}

      :else
      {:command-result/events
       [(->event
         {:type :sheet/execution-mode-set
          :tags #{[:sheet sheet-id]}
          :body {:sheet-id sheet-id
                 :mode mode
                 :previous-mode (or (:execution-mode sheet) :draft)}})]})))

;; =============================================================================
;; Execution Commands
;; =============================================================================

(defcommand :sheet execute-version
  {:authorized? logic/commands-authenticated?}
  "Execute a specific published version of a sheet.

   This command executes a sheet workflow using a specific version snapshot,
   not the current draft state. Useful for comparing behavior across versions.

   Traces are always stored and the trace-id is returned in the result."
  [{{:keys [sheet-id version-number inputs]} :command
    :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)
        version (when sheet (rm/get-version ctx sheet-id version-number))]
    (cond
      (not sheet)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Sheet not found"}

      (not version)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message (str "Version " version-number " not found")}

      :else
      (let [result (execute ctx sheet-id (or inputs {})
                                    :use-version version-number)]
        {:command-result/events
         [(->event {:type :sheet/version-executed
                    :tags #{[:sheet sheet-id]}
                    :body {:sheet-id sheet-id
                           :version-number version-number
                           :trace-id (:trace-id result)
                           :status (:status result)
                           :duration-ms (:duration-ms result)}})]
         :command-result/data result}))))

(defcommand :sheet batch-execute
  {:authorized? logic/commands-authenticated?}
  "Execute a sheet with multiple input sets in parallel.

   This command runs the same sheet multiple times with different inputs,
   executing them concurrently for efficiency. Each execution stores its own trace.

   Returns results for all executions along with total wall-clock time."
  [{{:keys [sheet-id version-number inputs]} :command
    :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)
        version (when (and sheet version-number)
                  (rm/get-version ctx sheet-id version-number))]
    (cond
      (not sheet)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Sheet not found"}

      (and version-number (not version))
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message (str "Version " version-number " not found")}

      (empty? inputs)
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "inputs cannot be empty"}

      :else
      (let [start-time (System/currentTimeMillis)
            futures (mapv (fn [input-set]
                            (future
                              (try
                                (execute ctx sheet-id (or input-set {})
                                                 :use-version version-number)
                                (catch Exception e
                                  {:status :failure
                                   :error (.getMessage e)}))))
                          inputs)
            results (mapv deref futures)
            end-time (System/currentTimeMillis)
            successful-count (count (filter #(= :success (:status %)) results))
            failed-count (count (filter #(not= :success (:status %)) results))]
        {:command-result/events
         [(->event {:type :sheet/batch-executed
                    :tags #{[:sheet sheet-id]}
                    :body (cond-> {:sheet-id sheet-id
                                   :total-executions (count results)
                                   :successful-count successful-count
                                   :failed-count failed-count
                                   :duration-ms (- end-time start-time)}
                             version-number (assoc :version-number version-number))})]
         :command-result/data
         {:results results
          :total-executions (count results)
          :successful-count successful-count
          :failed-count failed-count
          :duration-ms (- end-time start-time)}}))))

;; =============================================================================
;; Tree Metadata Commands
;; =============================================================================

(defcommand :sheet extract-tree-metadata
  {:authorized? logic/commands-authenticated?}
  "Extract and store metadata for a sheet.

   Analyzes the tree structure to identify node types, capabilities,
   and patterns. Optionally accepts user-provided problem types and
   description.

   Args:
   - sheet-id: Sheet to extract metadata from
   - problem-types: Optional manual problem type URIs (e.g., [\"problem:Classification\"])
   - description: Optional manual description

   Returns the extracted metadata and emits :sheet/tree-metadata-extracted event."
  [{{:keys [sheet-id problem-types description]} :command
    :keys [event-store] :as ctx}]
  (let [sheet (rm/get-sheet ctx sheet-id)]
    (if (not sheet)
      {:cognitect.anomalies/category :cognitect.anomalies/not-found
       :cognitect.anomalies/message "Sheet not found"}

      (let [
            extracted (extract-tree-metadata event-store sheet-id)

            nodes (rm/get-nodes-for-sheet ctx sheet-id)
            blackboard (rm/get-blackboard-for-sheet ctx sheet-id)

            inferred-problems (when (empty? problem-types)
                                (logic/infer-problem-types extracted nodes blackboard))

            final-metadata (-> extracted
                               (assoc :problem-types (or problem-types inferred-problems []))
                               (cond-> description (assoc :description description)))]

        {:command-result/data final-metadata
         :command-result/events
         [(->event
            {:type :sheet/tree-metadata-extracted
             :tags #{[:sheet sheet-id]}
             :body {:sheet-id sheet-id
                    :metadata final-metadata}})]}))))

(defcommand :sheet record-injection
  {:authorized? logic/commands-authenticated?}
  "Record ONE R-Inject render occurrence as an `:intervention/injection-recorded`
   event — what corpus content was put in front of the model on this turn.

   Dispatched in-process by the render (todo-processors
   apply-r05-classifier-context), the same way store-execution-trace is
   dispatched from trace assembly.

   Tagged [:sheet] [:tick] [:node] so the row is reachable by the same
   occurrence pair the judge score events carry."
  [{{:keys [sheet-id tick-id node-id recorded-at] :as command} :command}]
  {:command-result/events
   [(->event
      {:type :intervention/injection-recorded
       :tags #{[:sheet sheet-id] [:tick tick-id] [:node node-id]}
       :body (assoc (dissoc command :command/name :command/id :command/timestamp)
                    :recorded-at (or recorded-at (str (java.time.Instant/now))))})]})

(defn- prepare-researcher-classification-effect
  [ctx effect-command]
  (let [command-name (:command/name effect-command)
        handler (case command-name
                  :sheet/record-injection sheet-record-injection
                  (:ontology/record-claim-deltas :ontology/assign-task-class
                   :ontology/record-task-classification-deferral :ontology/mint-domain-child)
                  (when (integrations/load-provider! :ontology)
                    (get-in (cp/global-command-registry) [command-name :handler-fn]))
                  nil)]
    (if (and handler
             (m/validate (:command/name effect-command) effect-command))
      (handler (assoc ctx :command effect-command))
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message
       (str "Unsupported or invalid researcher classification effect: "
            (:command/name effect-command))
       :error/explain
       (when handler
         (me/humanize
         (m/explain (:command/name effect-command) effect-command)))})))

(defcommand :sheet commit-researcher-classification
  {:authorized? logic/commands-authenticated?}
  "Atomically publish a fully prepared checkpointed classification under the
   active campaign epoch.  Timeout/cancellation and this append contend on the
   same durable CAS, so an interruption-ignoring worker cannot publish late or
   leave convergence/injection facts without its classification outcome."
  [{:keys [command] :as ctx}]
  (if-let [boundary-error (logic/researcher-classification-commit-error command)]
    {:cognitect.anomalies/category :cognitect.anomalies/incorrect
     :cognitect.anomalies/message boundary-error}
    (let [{:keys [tick-id node-id ownership-epoch]} command
          effects (logic/canonical-researcher-classification-effects
                   (:effects command))
          results (mapv #(prepare-researcher-classification-effect ctx %) effects)
          anomaly (some #(when (:cognitect.anomalies/category %) %) results)
          refusal (some #(when (:ontology/refused %) %) results)]
      (cond
        anomaly anomaly

        refusal
        {:cognitect.anomalies/category :cognitect.anomalies/conflict
         :cognitect.anomalies/message "Researcher classification convergence capture was stale"}

        :else
        {:command-result/events
         (into [] (mapcat :command-result/events) results)
         :command-result/cas
         (logic/compose-researcher-classification-cas
          (into [(logic/researcher-classification-commit-cas
                  tick-id node-id ownership-epoch)]
                (keep :command-result/cas)
                results))}))))

(defcommand :sheet expire-researcher-classification
  {:authorized? logic/commands-authenticated?}
  "Durably win the classification-timeout race. The expiration and the atomic
   classification commit use the same CAS predicate, so exactly one becomes
   visible even when the classification worker ignores interruption."
  [{{:keys [sheet-id tick-id node-id ownership-epoch expired-at]} :command}]
  {:command-result/events
   [(->event
     {:type :rlm/researcher-classification-expired
      :tags #{[:sheet sheet-id] [:tick tick-id] [:node node-id]}
      :body {:sheet-id sheet-id
             :tick-id tick-id
             :node-id node-id
             :ownership-epoch ownership-epoch
             :expired-at expired-at}})]
   :command-result/cas
   (logic/researcher-classification-commit-cas
    tick-id node-id ownership-epoch)})

;; --- Command dispatch ---

(defn run-command
  "Dispatch a sheet command through Grain and return its result."
  [ctx command]
  (cp/process-command (assoc ctx :command command)))

;; --- Dsl ---

;; =============================================================================
;; Build Functions
;; =============================================================================

(defn run-build-command!
  "Run a single build command through the real command processor and CHECK the
   result. If the command returns an anomaly, throw — a failed build must be
   loud, never a silently-swallowed half-build.

   This replaces the deprecated `h/run-and-apply!` (which returned the command
   result unchecked) on the production `build-workflow!` path. The command
   processor stores events for a successful result; on an anomaly it returns a
   map carrying `::anom/category`, which we surface here as an ex-info."
  [ctx command]
  (let [result (cp/process-command (assoc ctx :command command))]
    (if (anomalies/anomaly? result)
      (throw (ex-info (str "build-workflow! command failed: "
                           (:cognitect.anomalies/message result))
                      {:command (:command/name command)
                       :anomaly result}))
      result)))

(defn- build-node!
  "Recursively build a node and its children. Returns the node-id.
   Node ID is deterministic based on sheet-id and node name (v5 UUID)."
  [ctx sheet-id node parent-id index]
  (let [node-type (:node-type node)
        node-name (:name node)
        node-id (when node-name (logic/node-id-for-name sheet-id node-name))
        create-result (run-build-command! ctx
                        (logic/make-create-node-command sheet-id node-type
                          :node-id node-id
                          :parent-id parent-id
                          :index index))]

    (when node-name
      (run-build-command! ctx (logic/make-set-node-name-command sheet-id node-id node-name)))

    (when (or (some #(contains? node %) [:provider :fallback-models :fallback-policy])
              (and (= :llm-condition node-type) (contains? node :options)))
      (run-build-command! ctx
        (merge {:command/id (random-uuid) :command/timestamp (time/now)
                :command/name :sheet/set-node-model-policy
                :sheet-id sheet-id :node-id node-id}
               (select-keys node [:provider :fallback-models :fallback-policy :options]))))



    (case node-type
      :leaf
      (do
        (run-build-command! ctx
          (logic/make-set-node-executor-command sheet-id node-id (:executor node)
            :model (:model node)
            :fn (:fn node)
            :tool (:tool node)
            :tool-caller-fn (:tool-caller-fn node)
            :tool-contracts (when (#{:code :tool} (:executor node)) (:tool-contracts node))
            :options (:options node)
            :options-from (:options-from node)
            :bands-from (:bands-from node)
            :min-confidence (:min-confidence node)
            :abstain (:abstain node)))
        (when (:instruction node)
          (run-build-command! ctx
            (logic/make-set-node-instruction-command sheet-id node-id (:instruction node))))
        (run-build-command! ctx
          (logic/make-set-node-io-command sheet-id node-id (:reads node) (:writes node)))
        (when-let [retry (:retry node)]
          (run-build-command! ctx
            (logic/make-set-node-retry-command sheet-id node-id
              (:max-attempts retry) (:backoff-ms retry))))
        (when-let [context (:context node)]
          (run-build-command! ctx
            (logic/make-set-node-context-command sheet-id node-id context))))

      :condition
      (when-let [check (:check node)]
        (run-build-command! ctx
          (logic/make-set-node-check-command sheet-id node-id check)))

      :llm-condition
      (do
        (run-build-command! ctx
          (logic/make-set-llm-condition-config-command sheet-id node-id
            (:instruction node) (:reads node)
            :model (:model node))))

      :repl-researcher
      (do
        (run-build-command! ctx
          (logic/make-set-repl-researcher-config-command sheet-id node-id
            (:instruction node) (:reads node) (:writes node) (:mcp-tools node)
            :tool-contracts (:tool-contracts node)
            :model (:model node)
            :tool-caller-fn (:tool-caller-fn node)
            :max-iterations (:max-iterations node)
            :browser-tools (:browser-tools node)
            :rlm (:rlm node)
            :timeout-ms (:timeout-ms node)
            :options (:options node)))
        (when-let [context (:context node)]
          (run-build-command! ctx
            (logic/make-set-node-context-command sheet-id node-id context))))

      :delegate
      (do
        (run-build-command! ctx
          (logic/make-set-delegate-config-command sheet-id node-id
            (:target-sheet-id node)
            :reads (:reads node)
            :writes (:writes node)
            :timeout-ms (:timeout-ms node)
            :max-ticks (:max-ticks node)
            :inherit-ontology? (:inherit-ontology? node))))

      :parallel
      (do
        (run-build-command! ctx
          (logic/make-set-parallel-config-command sheet-id node-id
            :success-policy (:success-policy node)
            :failure-policy (:failure-policy node)))
        (doseq [[idx child] (map-indexed vector (:children node))]
          (build-node! ctx sheet-id child node-id idx)))

      :map-each
      (do
        (run-build-command! ctx
          (logic/make-set-map-each-config-command sheet-id node-id
            (:source-key node) (:item-key node) (:output-key node)
            :max-concurrency (:max-concurrency node)
            :preserve-failures? (:preserve-failures? node)))
        (doseq [[idx child] (map-indexed vector (:children node))]
          (build-node! ctx sheet-id child node-id idx)))

      (doseq [[idx child] (map-indexed vector (:children node))]
        (build-node! ctx sheet-id child node-id idx)))

    (when-let [judges (seq (:judges node))]
      (run-build-command! ctx
        (logic/make-set-node-judges-command sheet-id node-id (vec judges))))

    node-id))

(defn- delete-node-tree!
  "Delete a node and all its descendants, bottom-up (leaves first)."
  [ctx sheet-id node-id]
  (let [node (rm/get-node ctx sheet-id node-id)]
    (when node
      (doseq [child-id (:children-ids node)]
        (delete-node-tree! ctx sheet-id child-id))
      (run-build-command! ctx (logic/make-delete-node-command sheet-id node-id)))))

(defn- clear-sheet-content!
  "Clear all nodes and blackboard keys from a sheet, preparing it for rebuild."
  [ctx sheet-id]
  (let [sheet (rm/get-sheet ctx sheet-id)
        root-id (:root-node-id sheet)
        bb-keys (rm/get-blackboard-for-sheet ctx sheet-id)]

    (when root-id
      (delete-node-tree! ctx sheet-id root-id))

    (doseq [bb-entry bb-keys]
      (run-build-command! ctx (logic/make-delete-key-command sheet-id (:key bb-entry))))))

(defn- build-sheet-content!
  "Build blackboard, judges, and node tree for a sheet from workflow definition."
  [ctx sheet-id {:keys [blackboard-schema judges-schema root-node]}]
  (doseq [[key-name schema] blackboard-schema]
    (run-build-command! ctx
      (logic/make-declare-key-command sheet-id key-name schema)))

  (doseq [[judge-name judge-config] judges-schema
          :let [judge-name (name judge-name)
                existing (rm/get-judge ctx sheet-id judge-name)]]
    (cond
      (nil? existing)
      (run-build-command! ctx (logic/make-declare-judge-command sheet-id judge-name judge-config))

      (not= judge-config (:judge-config (peek (:revisions existing))))
      (run-build-command! ctx (logic/make-revise-judge-command sheet-id judge-name judge-config))))

  (when root-node
    (build-node! ctx sheet-id root-node nil 0)))

(defn build-workflow!
  "Idempotent workflow builder. Creates or updates a workflow by name.

   - If the definition hasn't changed (same content hash): no-op, zero events
   - If the definition has changed: clears and rebuilds the sheet in place
   - If the sheet doesn't exist: creates a new one

   The workflow name is the identity:
   - Sheet ID is deterministic (v5 UUID from workflow name)
   - Node IDs are deterministic (v5 UUID from sheet-id + node name)
   - A SHA-256 content hash of the workflow definition is stored on the sheet
   - Unchanged definitions produce zero events (true no-op)
   - Safe to call on every application startup

   Args:
     ctx - Context with :event-store
     workflow-def - Workflow definition from (workflow ...)

   Concurrency: the hot path (hash match → zero events) is lock-free. Any
   structural mutation (create, or clear+rebuild of a changed sheet) is
   serialized per sheet-id, so two concurrent rebuilds of the SAME sheet run
   sequentially rather than interleaving their delete/declare commands — there
   is no observable half-built sheet. Distinct sheets never contend.

   Returns the sheet-id (deterministic, based on workflow name)."
  [ctx workflow-def]
  (let [workflow-def (logic/effective-workflow-definition workflow-def)
        _ (logic/validate-workflow-definition! workflow-def)
        {:keys [workflow-name]} workflow-def
        sheet-id (logic/sheet-id-for-name workflow-name)
        new-hash (logic/workflow-content-hash workflow-def)]

    (if (= (:content-hash (rm/get-sheet-by-name ctx workflow-name)) new-hash)
      sheet-id

      (locking (logic/build-lock-for sheet-id)
        (let [existing (rm/get-sheet-by-name ctx workflow-name)]
          (cond
            (= (:content-hash existing) new-hash)
            sheet-id

            existing
            (do
              (clear-sheet-content! ctx sheet-id)
              (build-sheet-content! ctx sheet-id workflow-def)
              (run-build-command! ctx (logic/make-set-content-hash-command sheet-id new-hash))
              sheet-id)

            :else
            (do
              (run-build-command! ctx (logic/make-create-sheet-command
                                       :name workflow-name
                                       :sheet-id sheet-id))
              (build-sheet-content! ctx sheet-id workflow-def)
              (run-build-command! ctx (logic/make-set-content-hash-command sheet-id new-hash))
              sheet-id)))))))

(defn build-workflow!!
  "DEPRECATED: Use build-workflow! instead, which is now idempotent by default.

   This function is kept for backwards compatibility but simply delegates
   to build-workflow!."
  {:deprecated "Use build-workflow! instead"}
  [ctx workflow-def]
  (build-workflow! ctx workflow-def))

(defn export-sheet
  "Export a sheet from the event store as an EDN structure.

   Returns a map that can be saved to file and later imported.

   Example:
     (export-sheet ctx sheet-id)
     ;; => {:version 1 :sheet {...} :blackboard-schema {...} :nodes {...}}"
  [ctx sheet-id]
  (let [sheet (rm/get-sheet ctx sheet-id)
        nodes (rm/get-nodes-by-id ctx sheet-id)
        blackboard (rm/get-blackboard-for-sheet ctx sheet-id)]
    {:version 1
     :exported-at (time/now)
     :sheet {:name (:name sheet)
             :id sheet-id}
     :blackboard-schema (into {}
                              (map (fn [bb] [(:key bb) (:schema bb)])
                                   blackboard))
     :judges-schema (into {}
                          (map (fn [[judge-name judge]]
                                 [(keyword judge-name)
                                  (:judge-config (peek (:revisions judge)))]))
                          (rm/get-judges ctx sheet-id))
     :nodes (logic/dsl-build-node-tree nodes (:root-node-id sheet))}))

(defn- import-node!
  "Recursively import a node and its children from exported structure."
  [ctx sheet-id node parent-id index]
  (let [node-type (:type node)
        create-result (run-command ctx
                        (logic/make-create-node-command sheet-id node-type
                          :parent-id parent-id
                          :index index))
        node-id (-> create-result :command-result/events first :node-id)]

    (when (:name node)
      (run-command ctx (logic/make-set-node-name-command sheet-id node-id (:name node))))

    (when (or (some #(contains? node %) [:provider :fallback-models :fallback-policy])
              (and (= :llm-condition node-type) (contains? node :options)))
      (run-build-command! ctx
        (merge {:command/id (random-uuid) :command/timestamp (time/now)
                :command/name :sheet/set-node-model-policy
                :sheet-id sheet-id :node-id node-id}
               (select-keys node [:provider :fallback-models :fallback-policy :options]))))



    (case node-type
      :leaf
      (do
        (when (:executor node)
          (run-command ctx
            (logic/make-set-node-executor-command sheet-id node-id (:executor node)
              :model (:model node)
              :fn (:fn node)
              :tool (:tool node)
              :tool-caller-fn (:tool-caller-fn node)
              :tool-contracts (when (#{:code :tool} (:executor node)) (:tool-contracts node))
              :options (:options node)
              :options-from (:options-from node)
              :bands-from (:bands-from node)
              :min-confidence (:min-confidence node)
              :abstain (:abstain node))))
        (when (:instruction node)
          (run-command ctx
            (logic/make-set-node-instruction-command sheet-id node-id (:instruction node))))
        (when (or (seq (:reads node)) (seq (:writes node)))
          (run-command ctx
            (logic/make-set-node-io-command sheet-id node-id
              (vec (:reads node)) (vec (:writes node)))))
        (when-let [retry (:retry node)]
          (run-command ctx
            (logic/make-set-node-retry-command sheet-id node-id
              (:max-attempts retry) (:backoff-ms retry)))))

      :condition
      (when-let [check (:check node)]
        (run-command ctx
          (logic/make-set-node-check-command sheet-id node-id check)))

      :llm-condition
      (when (:instruction node)
        (run-command ctx
          (logic/make-set-llm-condition-config-command sheet-id node-id
            (:instruction node) (vec (:reads node))
            :model (:model node))))

      :repl-researcher
      (do
        (when (:instruction node)
          (run-command ctx
            (logic/make-set-repl-researcher-config-command sheet-id node-id
              (:instruction node) (vec (:reads node)) (vec (:writes node))
              (vec (:mcp-tools node))
              :tool-contracts (:tool-contracts node)
              :model (:model node)
              :tool-caller-fn (:tool-caller-fn node)
              :max-iterations (:max-iterations node)
              :browser-tools (:browser-tools node)
              :rlm (:rlm node)
              :timeout-ms (:timeout-ms node)
              :options (:options node))))
        (when-let [context (:context node)]
          (run-command ctx
            (logic/make-set-node-context-command sheet-id node-id context))))

      :delegate
      (when (:target-sheet-id node)
        (run-command ctx
          (logic/make-set-delegate-config-command sheet-id node-id
            (:target-sheet-id node)
            :reads (vec (:reads node))
            :writes (vec (:writes node))
            :timeout-ms (:timeout-ms node)
            :max-ticks (:max-ticks node)
            :inherit-ontology? (:inherit-ontology? node))))

      :parallel
      (do
        (run-command ctx
          (logic/make-set-parallel-config-command sheet-id node-id
            :success-policy (:success-policy node)
            :failure-policy (:failure-policy node)))
        (doseq [[idx child] (map-indexed vector (:children node))]
          (import-node! ctx sheet-id child node-id idx)))

      :map-each
      (do
        (run-command ctx
          (logic/make-set-map-each-config-command sheet-id node-id
            (:source-key node) (:item-key node) (:output-key node)
            :max-concurrency (:max-concurrency node)
            :preserve-failures? (:preserve-failures? node)))
        (doseq [[idx child] (map-indexed vector (:children node))]
          (import-node! ctx sheet-id child node-id idx)))

      (doseq [[idx child] (map-indexed vector (:children node))]
        (import-node! ctx sheet-id child node-id idx)))

    (when-let [judges (seq (:judges node))]
      (run-command ctx
        (logic/make-set-node-judges-command sheet-id node-id (vec judges))))

    node-id))

(defn import-sheet
  "Import a sheet from an exported EDN structure.

   Creates a new sheet with the same structure. Returns the new sheet-id.

   Example:
     (import-sheet ctx exported-data)"
  [ctx exported]
  (let [sheet-name (get-in exported [:sheet :name])
        blackboard-schema (:blackboard-schema exported)
        root-node (:nodes exported)
        _ (doseq [node (tree-seq #(seq (:children %)) :children root-node)]
            (logic/validate-config! node))

        sheet-result (run-command ctx
                       (logic/make-create-sheet-command :name sheet-name))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)]

    (doseq [[key-name schema] blackboard-schema]
      (run-command ctx
        (logic/make-declare-key-command sheet-id key-name schema)))

    (doseq [[judge-name judge-config] (:judges-schema exported)]
      (run-command ctx
        (logic/make-declare-judge-command sheet-id (name judge-name) judge-config)))

    (when root-node
      (import-node! ctx sheet-id root-node nil 0))

    sheet-id))

;; =============================================================================
;; File I/O Helpers
;; =============================================================================

(defn save-sheet!
  "Export a sheet and save to file.

   Example:
     (save-sheet! ctx sheet-id \"development/sheets/my-workflow.edn\")"
  [ctx sheet-id filepath]
  (let [exported (export-sheet ctx sheet-id)]
    (io/make-parents filepath)
    (spit filepath (with-out-str (pprint/pprint exported)))
    (println "Saved sheet to:" filepath)
    filepath))

(defn load-sheet!
  "Load a sheet from file and import it. Returns the new sheet-id.

   Example:
     (load-sheet! ctx \"development/sheets/my-workflow.edn\")"
  [ctx filepath]
  (let [exported (read-string (slurp filepath))
        sheet-id (import-sheet ctx exported)]
    (println "Loaded sheet from:" filepath "-> sheet-id:" sheet-id)
    sheet-id))

(defn save-all-sheets!
  "Export all sheets to a directory.

   Example:
     (save-all-sheets! ctx \"development/sheets\")"
  [ctx dir-path]
  (let [sheets (rm/get-sheets-all ctx)]
    (doseq [sheet sheets]
      (let [safe-name (-> (:name sheet)
                          (str/replace #"[^a-zA-Z0-9-_]" "_"))
            filename (str safe-name ".edn")
            filepath (str dir-path "/" filename)]
        (save-sheet! ctx (:id sheet) filepath)))
    (println "Saved" (count sheets) "sheets to:" dir-path)))

(defn load-all-sheets!
  "Load all sheets from a directory.

   Example:
     (load-all-sheets! ctx \"development/sheets\")"
  [ctx dir-path]
  (let [dir (io/file dir-path)
        files (when (.exists dir)
                (->> (.listFiles dir)
                     (filter #(.endsWith (.getName %) ".edn"))
                     (sort-by #(.getName %))))]
    (doseq [file files]
      (load-sheet! ctx (.getPath file)))
    (println "Loaded" (count files) "sheets from:" dir-path)))

(defn sheet->dsl
  "Generate DSL code directly from a sheet-id.

   Convenience function combining export-sheet and export-to-dsl.

   Example:
     (sheet->dsl ctx sheet-id)"
  [ctx sheet-id & opts]
  (let [exported (export-sheet ctx sheet-id)]
    (apply logic/export-to-dsl exported opts)))

(defn save-sheet-as-dsl!
  "Export a sheet to a .clj file containing DSL code.

   The generated file can be loaded and evaluated to recreate the workflow.

   Example:
     (save-sheet-as-dsl! ctx sheet-id \"development/sheets/my-workflow.clj\")"
  [ctx sheet-id filepath]
  (let [dsl-code (sheet->dsl ctx sheet-id)]
    (io/make-parents filepath)
    (spit filepath dsl-code)
    (println "Saved sheet as DSL to:" filepath)
    filepath))
