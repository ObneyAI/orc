(ns ai.obney.orc.orc-service.core.model-call-journal
  "Durable candidate claims and run-scoped successful model selection."
  (:require [clojure.string :as str]
            [ai.obney.grain.command-processor-v2.interface :as cp :refer [defcommand]]
            [ai.obney.grain.query-processor.interface :as qp :refer [defquery]]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.event-store-v3.interface :as es :refer [->event]]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.orc-service.core.value-log :as value-log]
            [ai.obney.orc.orc-service.core.researcher-effects :as effects]
            [cognitect.anomalies :as anom]))

(def owner-fields [[:sheet-id :uuid] [:tick-id :uuid] [:node-id :uuid]
                   [:node-start-id :uuid] [:exec-context [:fn map?]]
                   [:role [:enum :node :prime :subcall]] [:configuration-id :string]
                   [:logical-action-id :string]])
(def owner-keys (mapv first owner-fields))
(defschemas model-call-commands
  {:sheet/claim-model-candidate (into [:map] (conj owner-fields [:candidate-index [:int {:min 0}]]))
   :sheet/record-model-request (into [:map] (conj owner-fields [:request [:fn map?]]))
   :sheet/record-model-candidate (into [:map] (conj owner-fields [:entry [:fn map?]]))
   :sheet/expose-model-candidate-output (into [:map] (conj owner-fields [:candidate-index [:int {:min 0}]]))
   :sheet/select-model-candidate (into [:map] (conj owner-fields [:candidate-index [:int {:min 0}]]))})
(defschemas model-call-events
  {:sheet/model-candidate-claimed (into [:map] (conj owner-fields [:candidate-index [:int {:min 0}]] [:selection-scope [:fn vector?]]))
   :sheet/model-request-recorded (into [:map] (conj owner-fields [:request [:fn map?]] [:selection-scope [:fn vector?]]))
   :sheet/model-candidate-recorded (into [:map] (conj owner-fields [:entry [:fn map?]] [:selection-scope [:fn vector?]]))
   :sheet/model-candidate-output-exposed (into [:map] (conj owner-fields [:candidate-index [:int {:min 0}]] [:selection-scope [:fn vector?]]))
   :sheet/model-candidate-selected (into [:map] (conj owner-fields [:candidate-index [:int {:min 0}]] [:selection-scope [:fn vector?]]))})
(defschemas model-call-queries
  {:sheet/model-candidate-history [:map [:tick-id :uuid]]
   :sheet/model-candidate-history-result [:vector [:fn map?]]})
(def event-types #{:sheet/model-candidate-claimed :sheet/model-candidate-recorded :sheet/model-candidate-selected :sheet/model-candidate-output-exposed :sheet/model-request-recorded})
(defn authorized? [ctx] (and (:tenant-id ctx) (:auth-claims ctx)))
(defn- rejected [message] {::anom/category ::anom/conflict ::anom/message message})
(defn- events [ctx tags types]
  (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :tags tags :types types})))
(defn- call-tag [owner] [:model-call (java.util.UUID/nameUUIDFromBytes
                                    (.getBytes (pr-str [(:tick-id owner) (:node-id owner)
                                                       (:exec-context owner) (:logical-action-id owner)
                                                       (:configuration-id owner)]) "UTF-8"))])
(defn- tick-options [ctx owner]
  (:options (first (filter #(= (:tick-id owner) (:tick-id %))
                          (events ctx #{[:tick (:tick-id owner)]} #{:sheet/tree-tick-started})))))
(defn- scope [ctx owner]
  (let [o (tick-options ctx owner) child? (and (= :subcall (:role owner)) (:researcher-campaign-tick-id o))]
    [(if child? (:researcher-campaign-sheet-id o) (:sheet-id owner))
     (if child? (:researcher-campaign-tick-id o) (:tick-id owner))
     (if child? (:researcher-campaign-node-id o) (:node-id owner))
     (:role owner) (if child? (or (:researcher-campaign-exec-context o) {}) (:exec-context owner))
     (:configuration-id owner)]))
(defn- scope-tag [s] [:model-selection (java.util.UUID/nameUUIDFromBytes (.getBytes (pr-str s) "UTF-8"))])
(defn- owns? [ctx owner]
  (let [starts (filter #(and (= (:tick-id owner) (:tick-id %))
                             (= (:node-id owner) (:node-id %))
                             (= (:exec-context owner) (value-log/exec-context (:inputs %))))
                       (events ctx #{[:tick (:tick-id owner)]} #{:sheet/node-execution-started}))
        latest (last starts)]
    (and (= (:node-start-id owner) (:event/id latest))
         (= (:sheet-id owner) (:sheet-id latest)))))
(defn- call-events [ctx owner] (events ctx #{(call-tag owner)} event-types))
(defn- candidate-events [xs index]
  (filter #(and (#{:sheet/model-candidate-claimed :sheet/model-candidate-recorded} (:event/type %))
                  (= index (or (:candidate-index %) (get-in % [:entry :candidate-index])))) xs))
(defn- append [ctx owner type body predicate]
  (let [s (scope ctx owner) tags #{[:sheet (:sheet-id owner)] [:tick (:tick-id owner)]
                                 (call-tag owner) (scope-tag s)}]
    {:command-result/events [(->event {:type type :tags tags
                                       :body (merge owner {:selection-scope s} body)})]
     :command-result/cas {:tags #{(call-tag owner)} :types event-types
                          :predicate-fn #(predicate (into [] %))}}))

(defcommand :sheet claim-model-candidate {:authorized? authorized?} [ctx]
  (let [owner (select-keys (:command ctx) owner-keys) index (get-in ctx [:command :candidate-index])
        xs (call-events ctx owner) same (last (candidate-events xs index))]
    (cond
      (not (owns? ctx owner)) (rejected "Model candidate does not own the current node start")
      (= :sheet/model-candidate-recorded (:event/type same)) (rejected "Model candidate already completed")
      (= (:node-start-id owner) (:node-start-id same)) (assoc (rejected "Model candidate already claimed by this node start") :model-call-in-flight? true)
      :else (append ctx owner :sheet/model-candidate-claimed {:candidate-index index}
                    #(= xs %)))))

(defcommand :sheet record-model-candidate {:authorized? authorized?} [ctx]
  (let [owner (select-keys (:command ctx) owner-keys) entry (get-in ctx [:command :entry])
        xs (call-events ctx owner) same (last (candidate-events xs (:candidate-index entry)))]
    (cond
      (not (owns? ctx owner)) (rejected "Model outcome does not own the current node start")
      (and (= :sheet/model-candidate-recorded (:event/type same)) (= entry (:entry same))) {:command-result/events []}
      (not (and (= :sheet/model-candidate-claimed (:event/type same))
                 (= (:node-start-id owner) (:node-start-id same)))) (rejected "Model outcome requires its exact candidate claim")
      :else (append ctx owner :sheet/model-candidate-recorded {:entry entry} #(= xs %)))))

(defcommand :sheet record-model-request {:authorized? authorized?} [ctx]
  (let [owner (select-keys (:command ctx) owner-keys) request (get-in ctx [:command :request])
        xs (call-events ctx owner) candidate (last (candidate-events xs (:candidate-index request)))
        previous (filter #(= (:request-id request) (get-in % [:request :request-id])) xs)
        matching (some #(when (= (:phase request) (get-in % [:request :phase])) %) previous)]
    (cond
      (not (owns? ctx owner)) (rejected "Model request does not own the current node start")
      matching (if (= request (:request matching)) {:command-result/events []}
                   (rejected "Model request receipt conflicts with its recorded phase"))
      (not (and (= :sheet/model-candidate-claimed (:event/type candidate))
                 (= (:node-start-id owner) (:node-start-id candidate)))) (rejected "Model request requires its exact candidate claim")
      (and (= :completed (:phase request)) (empty? previous)) (rejected "Model request completion requires its start")
      :else (append ctx owner :sheet/model-request-recorded {:request request} #(= xs %)))))

(defcommand :sheet expose-model-candidate-output {:authorized? authorized?} [ctx]
  (let [owner (select-keys (:command ctx) owner-keys) index (get-in ctx [:command :candidate-index])
        xs (call-events ctx owner) same (last (candidate-events xs index))]
    (cond
      (not (owns? ctx owner)) (rejected "Model stream does not own the current node start")
      (some #(= :sheet/model-candidate-output-exposed (:event/type %)) xs) {:command-result/events []}
      (not (and (= :sheet/model-candidate-claimed (:event/type same))
                 (= (:node-start-id owner) (:node-start-id same)))) (rejected "Visible output requires its exact candidate claim")
      :else (append ctx owner :sheet/model-candidate-output-exposed {:candidate-index index} #(= xs %)))))

(defcommand :sheet select-model-candidate {:authorized? authorized?} [ctx]
  (let [owner (select-keys (:command ctx) owner-keys) index (get-in ctx [:command :candidate-index])
        same (last (candidate-events (call-events ctx owner) index)) s (scope ctx owner)
        selected (events ctx #{(scope-tag s)} #{:sheet/model-candidate-selected})
        floor (reduce max 0 (map :candidate-index selected))]
    (cond
      (not (owns? ctx owner)) (rejected "Model selection does not own the current node start")
      (not= :success (get-in same [:entry :status])) (rejected "Model selection requires a successful candidate outcome")
      (<= index floor) {:command-result/events []}
      :else {:command-result/events [(->event {:type :sheet/model-candidate-selected
                                              :tags #{[:sheet (:sheet-id owner)] [:tick (:tick-id owner)]
                                                      (call-tag owner) (scope-tag s)}
                                              :body (assoc owner :selection-scope s :candidate-index index)})]
             :command-result/cas {:tags #{(scope-tag s)} :types #{:sheet/model-candidate-selected}
                                  :predicate-fn #(= floor (reduce (fn [m e] (max m (:candidate-index e))) 0 %))}})))

(defreadmodel :sheet model-candidate-ledger {:events event-types :version 2} [state event]
  (update state :entries (fnil conj [])
          (select-keys event (concat owner-keys [:event/type :event/id :candidate-index :entry :request :selection-scope]))))
(defquery :sheet model-candidate-history {:authorized? authorized?} [ctx]
  {:query/result (vec (:entries (rmp/project ctx :sheet/model-candidate-ledger
                                            {:tags #{[:tick (get-in ctx [:query :tick-id])]}})))})

(defn- command! [ctx name data]
  (loop [attempt 0]
    (let [result (cp/process-command (assoc ctx :command
                                          (merge data {:command/name name :command/id (random-uuid)
                                                       :command/timestamp (time/now)})))]
      (if (and (= ::anom/conflict (::anom/category result)) (< attempt 3))
        (recur (inc attempt)) result))))
(def stable-option-keys
  #{:temperature :max-tokens :top-p :top-k :frequency-penalty :presence-penalty :seed
    :reasoning :max-retries :retry-delay-ms :timeout-ms :output-mode :use-function-calling?
    :force-tool-choice? :prompt-cache? :generic-tool-description?
    :response-format :provider-options :privacy :data-collection :plugins :transforms})
(defn configuration-id [candidates policy]
  (effects/provider-logical-action-identity
   {:module (mapv #(-> (select-keys % [:provider :model :options])
                       (update :options (fn [o] (select-keys o stable-option-keys)))) candidates)
    :inputs policy :options {}}))
(defn callbacks
  "Bind durable capabilities for one logical model call. Owner is current actual
   node ownership plus :role and :logical-action-id. No raw context is persisted."
  [ctx owner candidates policy]
  (let [owner (assoc owner :configuration-id (configuration-id candidates policy))
        xs (call-events ctx owner)
        records (filter #(= :sheet/model-candidate-recorded (:event/type %)) xs)
        terminal (last records)
        selected (events ctx #{(scope-tag (scope ctx owner))} #{:sheet/model-candidate-selected})
        floor (reduce max 0 (map :candidate-index selected))
        claims (filter #(= :sheet/model-candidate-claimed (:event/type %)) xs)
        cursor (cond (and terminal (get-in terminal [:entry :advanced?]))
                     (inc (get-in terminal [:entry :candidate-index]))
                     terminal (get-in terminal [:entry :candidate-index])
                     (seq claims) (:candidate-index (first claims))
                     :else floor)]
    {:start-index cursor
     :initial-usage (when (get-in terminal [:entry :advanced?]) (get-in terminal [:entry :outcome :usage]))
     :prior-attempts (if (get-in terminal [:entry :advanced?])
                       (get-in terminal [:entry :outcome :model-fallback :attempts]) [])
     :request-receipts (mapv :request (filter #(= :sheet/model-request-recorded (:event/type %)) xs))
     :record-request! (fn [request] (command! ctx :sheet/record-model-request (assoc owner :request request)))
     :output-exposed? (boolean (some #(= :sheet/model-candidate-output-exposed (:event/type %)) xs))
     :mark-output-exposed! (fn [index] (command! ctx :sheet/expose-model-candidate-output (assoc owner :candidate-index index)))
     :completed-outcome (when (and terminal (not (get-in terminal [:entry :advanced?])))
                          (get-in terminal [:entry :outcome]))
     :claim! (fn [index _] (command! ctx :sheet/claim-model-candidate (assoc owner :candidate-index index)))
     :record! (fn [entry] (command! ctx :sheet/record-model-candidate (assoc owner :entry entry)))
     :select! (fn [index _] (command! ctx :sheet/select-model-candidate (assoc owner :candidate-index index)))}))
