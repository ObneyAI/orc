(ns ai.obney.orc.orc-service.core.emitted-model-authority
  "One immutable recovery binding between an actual child start and its parent claim."
  (:require [ai.obney.grain.command-processor-v2.interface :as cp :refer [defcommand]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.orc-service.core.budget-facts :as budget-facts]
            [ai.obney.orc.orc-service.core.researcher-effects :as effects]
            [ai.obney.orc.orc-service.core.value-log :as value-log]))

(def event-type :sheet/emitted-model-work-authorized)
(def fact-types
  #{:sheet/tree-tick-started :sheet/tree-tick-completed :sheet/tick-cancelled
    :sheet/node-execution-started :sheet/node-execution-completed
    :rlm/researcher-frontier-claimed :rlm/researcher-effect-claimed :rlm/researcher-effect-completed
    :sheet/model-plan-retained :sheet/model-candidate-claimed :sheet/model-candidate-recorded
    :sheet/model-request-recorded :sheet/emitted-model-work-authorized})
(def owner-keys [:sheet-id :tick-id :node-id :node-start-id :exec-context :configuration-id :logical-action-id])
(def owner-fields [[:sheet-id :uuid] [:tick-id :uuid] [:node-id :uuid] [:node-start-id :uuid]
                   [:exec-context [:fn map?]] [:configuration-id {:optional true} :string] [:logical-action-id :string]])
(def binding-fields
  (into owner-fields [[:campaign-sheet-id :uuid] [:campaign-tick-id :uuid] [:campaign-node-id :uuid]
                      [:parent-node-start-id :uuid] [:parent-frontier-id :uuid]
                      [:generated-claim-id :uuid] [:generated-action-id :string]
                      [:iteration-index [:int {:min 0}]] [:ownership-epoch [:int {:min 1}]]]))
(def binding-keys (mapv first binding-fields))
(defschemas authority-commands {:sheet/authorize-emitted-model-work (into [:map] owner-fields)})
(defschemas authority-events {event-type (into [:map] binding-fields)})

(defn- same-node? [owner event]
  (and (= (:sheet-id owner) (:sheet-id event)) (= (:tick-id owner) (:tick-id event))
       (= (:node-id owner) (:node-id event))
       (= (or (:exec-context owner) {}) (value-log/exec-context (:inputs event)))))
(defn- latest-start [owner xs]
  (last (filter #(and (= :sheet/node-execution-started (:event/type %)) (same-node? owner %)) xs)))
(defn- ended? [owner xs]
  (let [last-node (last (filter #(and (#{:sheet/node-execution-started :sheet/node-execution-completed} (:event/type %))
                                     (same-node? owner %)
                                     (not= :tree-generated (:completion-kind %))) xs))]
    (or (= :sheet/node-execution-completed (:event/type last-node))
        (some #(and (= (:tick-id owner) (:tick-id %))
                    (or (= :sheet/tick-cancelled (:event/type %))
                        (and (= :sheet/tree-tick-completed (:event/type %)) (not= :running (:root-status %))))) xs))))
(defn- recovered-start? [owner start xs]
  (let [by-id (into {} (map (juxt :event/id identity) xs))]
    (loop [event start seen #{} recovered? false]
      (cond
        (or (nil? event) (contains? seen (:event/id event))
            (not= :sheet/node-execution-started (:event/type event)) (not (same-node? owner event))) false
        (:resumed-from-event-id event)
        (recur (get by-id (:resumed-from-event-id event)) (conj seen (:event/id event)) true)
        :else recovered?))))
(defn- configuration-admitted? [owner campaign xs]
  (let [scope [(:sheet-id campaign) (:tick-id campaign) (:node-id campaign) :subcall (or (:exec-context campaign) {})]
        plans (filter #(and (= :sheet/model-plan-retained (:event/type %))
                             (= scope (vec (take 5 (:selection-scope %))))) xs)
        operation (filter #(and (= (:tick-id owner) (:tick-id %)) (= (:node-id owner) (:node-id %))
                               (= (:exec-context owner) (:exec-context %))
                               (= (:logical-action-id owner) (:logical-action-id %))
                               (:configuration-id %)) xs)
        child-tree (first (filter #(and (= :sheet/tree-tick-started (:event/type %))
                                        (= (:tick-id owner) (:tick-id %))) xs))
        node (get-in child-tree [:execution-snapshot :nodes-by-id (:node-id owner)])]
    (if (contains? owner :configuration-id)
      (and (some #(= (:configuration-id owner) (:configuration-id %)) plans)
           (every? #(= (:configuration-id owner) (:configuration-id %)) operation))
      ;; A primary-only leaf has execution ownership without a model-plan ID.
      ;; The canonical node and exact operation must both prove that absence;
      ;; omitting configuration cannot evade a retained configured operation.
      (and node (not (seq (:fallback-models node))) (empty? operation)))))

(defn assess
  "Pure canonical decision. A binding is single-assignment per actual child start,
   independent of configuration buckets. Callers must check it again in append CAS."
  [owner campaign xs]
  (let [parent (select-keys campaign [:sheet-id :tick-id :node-id :exec-context])
        child-start (latest-start owner xs) parent-start (latest-start parent xs)
        child-tree (first (filter #(and (= :sheet/tree-tick-started (:event/type %)) (= (:tick-id owner) (:tick-id %))) xs))
        frontier (last (filter #(and (= effects/frontier-event-type (:event/type %))
                                     (= (:sheet-id parent) (:sheet-id %)) (= (:tick-id parent) (:tick-id %))
                                     (= (:node-id parent) (:node-id %))) xs))
        epoch (:ownership-epoch frontier)
        claim (last (filter #(and (= effects/claim-event-type (:event/type %)) (= :generated-child (:kind %))
                                  (= (:sheet-id parent) (:sheet-id %)) (= (:tick-id parent) (:tick-id %))
                                  (= (:node-id parent) (:node-id %))
                                  (= (:iteration-index campaign) (:iteration-index %))
                                  (= (:tick-id owner) (java.util.UUID/nameUUIDFromBytes
                                                        (.getBytes (:logical-action-identity %) "UTF-8")))) xs))
        binding (first (filter #(and (= event-type (:event/type %)) (= (:node-start-id owner) (:node-start-id %))) xs))
        positions (zipmap (map :event/id xs) (range))
        ordered? (and parent-start frontier claim
                      (< (get positions (:event/id parent-start) Long/MAX_VALUE)
                         (get positions (:event/id frontier) -1)
                         (get positions (:event/id claim) -1)))
        current? (and (= (:node-start-id owner) (:event/id child-start))
                      (= (:sheet-id owner) (:sheet-id child-tree))
                      (= (:tick-id parent) (:parent-tick-id child-tree))
                      (not (ended? owner xs)) (not (ended? parent xs)))
        claim-current? (and (pos-int? epoch) (= epoch (:ownership-epoch claim))
                            (or (not (contains? parent-start :researcher-ownership-epoch))
                                (= epoch (:researcher-ownership-epoch parent-start))) ordered?
                            (not-any? #(and (= effects/completion-event-type (:event/type %))
                                            (= (:logical-action-identity claim) (:logical-action-identity %))
                                            (= (:tick-id parent) (:tick-id %))) xs))
        authority (merge (select-keys owner owner-keys)
                         {:campaign-sheet-id (:sheet-id campaign) :campaign-tick-id (:tick-id campaign)
                          :campaign-node-id (:node-id campaign) :parent-node-start-id (:event/id parent-start)
                          :parent-frontier-id (:event/id frontier) :generated-claim-id (:event/id claim)
                          :generated-action-id (:logical-action-identity claim)
                          :iteration-index (:iteration-index campaign) :ownership-epoch epoch})]
    (cond
      (not current?) {:status :refused :reason :emitted-child-start-superseded}
      binding (if (and claim-current? (= authority (select-keys binding binding-keys)))
                {:status :authorized :authority authority}
                {:status :refused :reason :emitted-authority-superseded})
      ;; Original delivery keeps its existing epoch semantics. A resumed delivery
      ;; waits for the parent's actual reclaim rather than borrowing the old claim.
      (and (nil? (:resumed-from-event-id child-start))
           (= (:ownership-epoch campaign) epoch) claim-current?)
      {:status :original :authority authority}
      (not (recovered-start? owner child-start xs)) {:status :refused :reason :emitted-resumed-start-required}
      (not (configuration-admitted? owner campaign xs)) {:status :refused :reason :emitted-configuration-mismatch}
      (or (not claim-current?) (not (> (or epoch 0) (or (:ownership-epoch campaign) 0))))
      {:status :pending :reason :emitted-parent-reclaim-pending}
      :else {:status :ready :authority authority})))

(defn fresh-authorized? [owner campaign xs]
  (contains? #{:original :authorized} (:status (assess owner campaign xs))))
(defn- merge-facts [& groups]
  (->> groups (apply concat) (reduce #(assoc %1 (:event/id %2) %2) {}) vals
       (sort-by (comp str :event/id)) vec))
(defn- read-events [ctx tags]
  (let [r (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :tags tags :types fact-types})]
    (when (:cognitect.anomalies/category r)
      (throw (ex-info "Cannot establish emitted model authority" {:failure-kind :model-plan-incompatible :reason :emitted-authority-read-failed})))
    (into [] r)))
(defn- fact-state [ctx owner campaign]
  (let [tag (effects/campaign-tag (:sheet-id campaign) (:tick-id campaign) (:node-id campaign))
        indexed (read-events ctx #{tag})
        old (merge-facts (read-events ctx #{[:tick (:tick-id owner)]})
                         (read-events ctx #{[:tick (:tick-id campaign)]}))
        legacy (filterv #(not (contains? (:event/tags %) tag)) old)]
    {:legacy legacy :facts (merge-facts legacy indexed)}))
(defn read-facts [ctx owner campaign] (:facts (fact-state ctx owner campaign)))
(defcommand :sheet authorize-emitted-model-work
  {:authorized? (fn [ctx] (and (:tenant-id ctx) (:auth-claims ctx)))}
  [ctx]
  (let [owner (select-keys (:command ctx) owner-keys)
        campaign (budget-facts/researcher-campaign ctx (:tick-id owner))
        state (when campaign (fact-state ctx owner campaign))
        decision (when campaign (assess owner campaign (:facts state)))]
    (case (:status decision)
      (:original :authorized) {:command-result/events []}
      :ready {:command-result/events
              [(budget-facts/event ctx {:type event-type
                 :tags #{[:sheet (:sheet-id owner)] [:tick (:tick-id owner)]
                         (effects/campaign-tag (:sheet-id campaign) (:tick-id campaign) (:node-id campaign))}
                 :body (:authority decision)})]
              :command-result/cas
              {:tags #{(effects/campaign-tag (:sheet-id campaign) (:tick-id campaign) (:node-id campaign))}
               :types fact-types
               :predicate-fn (fn [stream]
                               (let [fresh (assess owner campaign (merge-facts (:legacy state) (into [] stream)))]
                                 (and (= :ready (:status fresh)) (= (:authority decision) (:authority fresh)))))}}
      {:cognitect.anomalies/category :cognitect.anomalies/conflict
       :cognitect.anomalies/message "Emitted model work has no current recovery authority"
       :failure-kind :model-plan-incompatible :reason (or (:reason decision) :emitted-campaign-lineage-unavailable)})))

(defn ensure!
  "Wait only for actual parent recovery facts, under the original absolute deadline.
   No model outcome is recorded while a legitimate recovery binding is pending."
  [ctx owner deadline-ms]
  (when-let [campaign (budget-facts/researcher-campaign ctx (:tick-id owner))]
    (let [now-ms (or (:orc/model-authority-now-ms ctx) #(System/currentTimeMillis))
          sleep! (or (:orc/model-authority-sleep! ctx) #(Thread/sleep %))
          deadline (or deadline-ms (+ (now-ms) 30000))]
      (loop []
        (when (>= (now-ms) deadline)
          (throw (ex-info "Emitted model recovery authority deadline exhausted"
                          {:failure-kind :model-plan-incompatible :reason :emitted-authority-deadline})))
        (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException. "Emitted model recovery interrupted")))
        (let [{:keys [status authority reason]} (assess owner campaign (read-facts ctx owner campaign))]
          (case status
            (:original :authorized) authority
            :ready (let [r (cp/process-command (assoc ctx :command
                             (merge owner {:command/name :sheet/authorize-emitted-model-work
                                           :command/id (random-uuid) :command/timestamp (time/now)})))]
                     (when-let [category (:cognitect.anomalies/category r)]
                       (when-not (= :cognitect.anomalies/conflict category)
                         (throw (ex-info "Emitted model recovery authority could not be recorded"
                                         {:failure-kind :model-plan-incompatible :reason :emitted-authority-write-failed})))
                       (sleep! 5))
                     (recur))
            :pending (if (< (now-ms) deadline)
                       (do (sleep! (min 25 (max 1 (- deadline (now-ms))))) (recur))
                       (throw (ex-info "Emitted model recovery authority was not established before the execution deadline"
                                       {:failure-kind :model-plan-incompatible :reason :emitted-authority-deadline})))
            (throw (ex-info "Emitted model recovery authority is no longer current"
                            {:failure-kind :model-plan-incompatible :reason reason}))))))))
