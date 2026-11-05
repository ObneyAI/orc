(ns ai.obney.orc.orc-service.core.execution-family
  "The execution family of a run: every node execution of the execution and of
   every execution nested beneath it (delegated workflows, generated child
   trees), in durable completion order, each with what it read, wrote, failed
   with and caused (ExecutionFamilyIsQueryable).

   Values are RESOLVED from the canonical write log (core/value-log), the same
   resolution the trace detail query and the judge runtime use, and are never
   truncated."
  (:require [ai.obney.orc.orc-service.core.family-usage :as family-usage]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.value-log :as value-log]))

(defn- tool-receipts
  "The tool-effect receipts one tick recorded for one node: every claimed tool
   effect joined with its completion (result) or indeterminate resolution."
  [events tick-id node-id]
  (let [mine? #(and (= tick-id (:tick-id %)) (= node-id (:node-id %)))
        resolution (reduce (fn [acc e]
                             (case (:event/type e)
                               (:rlm/researcher-effect-completed
                                :rlm/researcher-effect-indeterminate)
                               (if (mine? e)
                                 (assoc acc [(:logical-action-identity e) (:attempt-identity e)] e)
                                 acc)
                               acc))
                           {} events)]
    (->> events
         (filter #(and (= :rlm/researcher-effect-claimed (:event/type %))
                       (mine? %)
                       (= :tool (:kind %))))
         (mapv (fn [claim]
                 (let [done (get resolution [(:logical-action-identity claim)
                                             (:attempt-identity claim)])]
                   (cond-> (select-keys claim [:logical-action-identity :attempt-identity
                                               :attempt-ordinal :iteration-index :kind
                                               :claimed-at])
                     done (assoc :status (:status done)
                                 :resolved-at (:resolved-at done))
                     (nil? done) (assoc :status (:status claim))
                     (contains? done :result) (assoc :result (:result done)))))))))

(defn- failure-of [completed]
  (when (or (= :failure (:status completed))
            (= :timeout (:status completed))
            (:failure-kind completed))
    (cond-> {}
      (:failure-kind completed) (assoc :kind (:failure-kind completed))
      (:error completed) (assoc :message (:error completed))
      (:provider-evidence completed) (assoc :provider-evidence (:provider-evidence completed))
      (:raw-response completed) (assoc :raw-response (:raw-response completed)))))

(defn- seed-values
  "The tick's canonical seed values: its originating start event's
   :seed-sources, each resolved. Mirrors value-log/tick-seeds."
  [resolve-ref tick-events]
  (let [started (some #(when (and (= :sheet/tree-tick-started (:event/type %))
                                  (:execution-snapshot %))
                         %)
                      tick-events)]
    (reduce-kv (fn [acc k value-source]
                 (let [value (resolve-ref value-source)]
                   (if (some? value) (assoc acc k value) acc)))
               {} (or (:seed-sources started) {}))))

(defn- tick-index
  "Everything entry resolution needs from ONE tick's events, computed in a
   single pass over events that were read once. Semantics mirror
   value-log/resolve-reads, resolve-writes and rejected-writes-for."
  [resolve-ref tick-events]
  (let [value-events (filterv value-log/value-event? tick-events)
        output-sources (reduce (fn [acc e]
                                 (if (value-log/input-seed? e)
                                   acc
                                   (assoc-in acc [(value-log/execution-key e) (:key e)]
                                             (or (:source e) (value-log/source-ref e)))))
                               {} value-events)
        rejected (reduce (fn [acc e]
                           (if (= :sheet/execution-value-rejected (:event/type e))
                             (assoc-in acc [(value-log/execution-key e) (:key e)] (:value e))
                             acc))
                         {} tick-events)
        seeds (delay (seed-values resolve-ref tick-events))]
    {:output-sources output-sources
     :logged-writes (value-log/writes-by-execution tick-events)
     :rejected rejected
     :iteration-seeds (value-log/input-seeds-by-iteration tick-events)
     :tick-seeds seeds}))

(defn- resolved-reads [resolve-ref index completion]
  (let [sources (or (:read-sources completion) {})
        iteration-seeds (get (:iteration-seeds index)
                             (value-log/exec-context (:inputs completion)))]
    (reduce (fn [acc k]
              (let [source (get sources k)
                    value (cond
                            source (resolve-ref source)
                            (contains? iteration-seeds k) (get iteration-seeds k)
                            :else (get @(:tick-seeds index) k))]
                (if (some? value) (assoc acc k value) acc)))
            {}
            (or (:read-keys completion) []))))

(defn- resolved-writes [resolve-ref index completion]
  (let [ek (value-log/execution-key completion)
        sources (merge (or (:write-sources completion) {})
                       (get (:output-sources index) ek))]
    (if (seq sources)
      (reduce-kv (fn [acc k value-source]
                   (let [v (resolve-ref value-source)]
                     (if (some? v) (assoc acc k v) acc)))
                 {} sources)
      (or (not-empty (get (:logged-writes index) ek))
          (:writes completion)
          {}))))

(defn- entry-for
  [resolve-ref index tick-events {:keys [tick-id sheet-id parent-tick-id nodes-by-id attributed-to]} completed]
  (let [node (get nodes-by-id (:node-id completed))
        inputs (resolved-reads resolve-ref index completed)
        outputs (resolved-writes resolve-ref index completed)
        rejected (get (:rejected index) (value-log/execution-key completed))
        receipts (tool-receipts tick-events tick-id (:node-id completed))
        failure (failure-of completed)]
    (cond-> {:event-id (:event/id completed)
             :completed-at (:event/timestamp completed)
             :tick-id tick-id
             :sheet-id sheet-id
             :node-id (:node-id completed)
             :node-name (:name node)
             :node-type (or (:node-type completed) (:type node))
             :executor (:executor node)
             :instruction (:instruction node)
             :status (:status completed)
             :exec-context (value-log/exec-context (:inputs completed))
             :inputs inputs
             :outputs outputs}
      parent-tick-id (assoc :parent-tick-id parent-tick-id)
      attributed-to (assoc :spawned-by-node-id attributed-to)
      (seq rejected) (assoc :rejected-outputs rejected)
      failure (assoc :failure failure)
      (some? (:condition-answer completed)) (assoc :condition-answer (:condition-answer completed))
      (:decision completed) (assoc :decision (:decision completed))
      (:usage completed) (assoc :usage (:usage completed))
      (:own-usage completed) (assoc :own-usage (:own-usage completed))
      (:model completed) (assoc :model (:model completed))
      (:requested-model completed) (assoc :requested-model (:requested-model completed))
      (:resolved-model completed) (assoc :resolved-model (:resolved-model completed))
      (:completion-kind completed) (assoc :completion-kind (:completion-kind completed))
      (seq receipts) (assoc :tool-receipts receipts))))

(defn- make-tick-reader
  "A memoized reader of a tick's events: each tick's events are read from the
   store at most ONCE per family query, however many entries (or cross-tick
   references) consult them."
  [{:keys [tenant-id] :as ctx}]
  (let [cache (atom {})]
    (fn [tick-id]
      (or (get @cache tick-id)
          (let [events (value-log/read-tick-events ctx tenant-id tick-id)
                by-ref (reduce (fn [acc e]
                                 (if (value-log/value-event? e)
                                   (let [acc (assoc acc (:event/id e) e)]
                                     (if (:value-id e) (assoc acc (:value-id e) e) acc))
                                   acc))
                               {} events)
                v {:events events :by-ref by-ref}]
            (swap! cache assoc tick-id v)
            v)))))

(defn- resolver
  "resolve-ref over {:tick-id :event-id} references, following cross-tick
   references like value-log/resolve-source (missing, foreign and cyclic
   references resolve to nil), reading each tick once."
  [read-tick]
  (fn resolve-ref
    ([source] (resolve-ref source #{}))
    ([{:keys [tick-id event-id] :as source} seen]
     (when (and tick-id event-id (not (contains? seen source)))
       (when-let [event (get (:by-ref (read-tick tick-id)) event-id)]
         (if (= :sheet/execution-value-referenced (:event/type event))
           (resolve-ref (:source event) (conj seen source))
           (:value event)))))))

(defn- tick-entries
  "Every node-execution entry of one tick, plus the tick's lineage facts."
  [ctx read-tick resolve-ref tick-id]
  (let [tick-events (:events (read-tick tick-id))
        started (some #(when (and (= :sheet/tree-tick-started (:event/type %))
                                  (= tick-id (:tick-id %)))
                         %)
                      tick-events)
        sheet-id (:sheet-id started)
        nodes-by-id (or (rm/get-tick-nodes-by-id ctx tick-id)
                        (when sheet-id (rm/get-nodes-by-id ctx sheet-id))
                        {})
        options (:options started)
        tick {:tick-id tick-id
              :sheet-id sheet-id
              :parent-tick-id (:parent-tick-id started)
              :nodes-by-id nodes-by-id
              ;; The node of the parent tick that spawned this tick: the
              ;; delegate node, or the researcher that generated the tree.
              :attributed-to (or (:delegate-parent-node-id options)
                                 (:researcher-campaign-node-id options))}
        index (tick-index resolve-ref tick-events)]
    {:tick tick
     :entries (->> tick-events
                   (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                 (= tick-id (:tick-id %))))
                   (mapv #(entry-for resolve-ref index tick-events tick %)))}))

(defn- descendants
  "node-id's descendants in a node map."
  [nodes-by-id node-id]
  (loop [acc #{} frontier [node-id]]
    (if (empty? frontier)
      acc
      (let [kids (into [] (mapcat #(:children-ids (get nodes-by-id %))) frontier)]
        (recur (into acc kids) kids)))))

(defn- scope-to-node
  "Keep only the entries beneath the executions of `node-id`: the node's
   descendants in the tick it ran in, plus every execution of any tick spawned
   (transitively) from those nodes."
  [entries ticks node-id]
  (let [ticks-by-id (into {} (map (juxt :tick-id identity)) ticks)
        home-ticks (into #{} (comp (filter #(= node-id (:node-id %))) (map :tick-id)) entries)
        ;; node-ids in each tick that count as "beneath"
        beneath-nodes (into {}
                            (map (fn [t] [t (descendants (:nodes-by-id (get ticks-by-id t)) node-id)]))
                            home-ticks)
        ;; delegate/generated ticks spawned from a node that is node-id or beneath it
        spawned-from? (fn [tick scope-by-tick]
                         (let [parent (:parent-tick-id tick)
                               nodes (get scope-by-tick parent)]
                           (and parent nodes (:attributed-to tick)
                                (or (contains? nodes (:attributed-to tick))
                                    (and (contains? home-ticks parent)
                                         (= node-id (:attributed-to tick)))))))]
    (loop [scope beneath-nodes
           whole #{}]
      (let [new-whole (into #{}
                            (comp (remove #(contains? whole (:tick-id %)))
                                  (filter #(or (spawned-from? % scope)
                                               (contains? whole (:parent-tick-id %))))
                                  (map :tick-id))
                            ticks)]
        (if (empty? new-whole)
          (filterv (fn [e]
                     (or (contains? whole (:tick-id e))
                         (contains? (get scope (:tick-id e)) (:node-id e))))
                   entries)
          (recur scope (into whole new-whole)))))))

(defn get-execution-family
  "Every node execution of `trace-id` and of every nested child execution
   (delegated workflows, generated child trees), in durable completion order.

   Each entry: :tick-id :parent-tick-id (child ticks only) :sheet-id :node-id
   :node-name :node-type :executor :instruction :status :exec-context :inputs
   and :outputs (resolved VALUES, never truncated), :failure {:kind :message
   :provider-evidence} when it failed, :tool-receipts when the tick recorded
   tool effects for it, usage/model fields when recorded, :event-id and
   :completed-at.

   Option :node-id scopes the family to the executions beneath the executions
   of that composite or delegate node (the node itself is excluded)."
  ([ctx trace-id] (get-execution-family ctx trace-id {}))
  ([{:keys [event-store tenant-id] :as ctx} trace-id {:keys [node-id]}]
   (let [tick-ids (family-usage/tick-family-ids event-store tenant-id trace-id)
         read-tick (make-tick-reader ctx)
         resolve-ref (resolver read-tick)
         per-tick (mapv #(tick-entries ctx read-tick resolve-ref %) tick-ids)
         entries (into [] (mapcat :entries) per-tick)
         ticks (mapv :tick per-tick)
         scoped (if node-id (scope-to-node entries ticks node-id) entries)]
     ;; Event ids are time-ordered UUIDv7s, so their text order is the
     ;; durable append order across ticks.
     (->> scoped
          (sort-by #(str (:event-id %)))
          vec))))
