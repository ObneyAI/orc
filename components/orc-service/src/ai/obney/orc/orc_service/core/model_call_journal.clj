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
            [ai.obney.orc.orc-service.core.budget-facts :as budget-facts]
            [ai.obney.orc.orc-service.core.emitted-model-authority :as emitted-authority]
            [ai.obney.orc.orc-service.core.researcher-effects :as effects]
            [malli.core :as m]
            [cognitect.anomalies :as anom]))

(def retained-option-keys
  #{:temperature :max-tokens :top-p :top-k :frequency-penalty :presence-penalty
    :seed :reasoning :stop :reasoning-effort :thinking :max-retries :retry-delay-ms :timeout-ms :output-mode
    :use-function-calling? :force-tool-choice? :prompt-cache? :generic-tool-description?
    :response-format :extra-body :provider-options :privacy :data-collection :plugins :transforms
    :stream-options :do-sample :tool-stream :prompt-cache-key :safety-identifier :request-id :user-id
    :unparseable-is-schema-error? :optional-writes})

(defn- configuration-data? [x]
  (cond
    (or (nil? x) (string? x) (boolean? x) (number? x) (keyword? x)) true
    (record? x) false
    (map? x) (every? (fn [[k v]]
                      (and (or (keyword? k) (string? k))
                           (not (contains? #{"api-key" "api_key" "authorization" "credentials"
                                             "access-token" "password" "secret" "headers"}
                                           (str/lower-case (name k))))
                           (configuration-data? v))) x)
    (vector? x) (every? configuration-data? x)
    (set? x) (every? configuration-data? x)
    :else false))

(defn valid-retained-options?
  "Retain only known data-only request controls; never credentials or effects."
  [options]
  (and (map? options) (every? retained-option-keys (keys options))
       (configuration-data? options)))

(def retained-plan-schema
  [:map {:closed true}
   [:version [:= 1]]
   [:policy [:enum :temporary-transport :all-transport]]
   [:candidates
    [:vector {:min 1}
     [:map {:closed true}
      [:provider :keyword] [:model [:maybe :string]]
      [:protocol [:enum :chat :decision]]
      [:options [:and [:fn map?]
                 [:fn valid-retained-options?]]]
      [:profile [:map {:closed true} [:version :string]
                 [:output-mode [:maybe [:enum :function-calling :json-schema]]]
                 [:force-tool-choice? :boolean] [:strict-arguments? :boolean]]]]]]])

(defn retained-configuration-id [plan]
  (str "effective-v1:"
       (effects/provider-logical-action-identity {:module plan :inputs nil :options {}})))

(def owner-fields [[:sheet-id :uuid] [:tick-id :uuid] [:node-id :uuid]
                   [:node-start-id :uuid] [:exec-context [:fn map?]]
                   [:role [:enum :node :prime :subcall]] [:configuration-id :string]
                   [:logical-action-id :string]
                   [:ownership-epoch {:optional true} [:int {:min 1}]]])
(def owner-keys (mapv first owner-fields))
(defschemas model-call-commands
  {:sheet/retain-model-plan (into [:map] (conj owner-fields [:plan-scope-id :string] [:plan retained-plan-schema]))
   :sheet/claim-model-candidate (into [:map] (conj owner-fields [:candidate-index [:int {:min 0}]]))
   :sheet/record-model-request (into [:map] (conj owner-fields [:request [:fn map?]]))
   :sheet/record-model-candidate (into [:map] (conj owner-fields [:entry [:fn map?]]))
   :sheet/expose-model-candidate-output (into [:map] (conj owner-fields [:candidate-index [:int {:min 0}]]))
   :sheet/select-model-candidate (into [:map] (conj owner-fields [:candidate-index [:int {:min 0}]]))})
(defschemas model-call-events
  {:sheet/model-plan-retained (into [:map] (conj owner-fields [:plan-scope-id :string] [:plan retained-plan-schema]))
   :sheet/model-candidate-claimed (into [:map] (conj owner-fields [:candidate-index [:int {:min 0}]] [:selection-scope [:fn vector?]]))
   :sheet/model-request-recorded (into [:map] (conj owner-fields [:request [:fn map?]] [:selection-scope [:fn vector?]]))
   :sheet/model-candidate-recorded (into [:map] (conj owner-fields [:entry [:fn map?]] [:selection-scope [:fn vector?]]))
   :sheet/model-candidate-output-exposed (into [:map] (conj owner-fields [:candidate-index [:int {:min 0}]] [:selection-scope [:fn vector?]]))
   :sheet/model-candidate-selected (into [:map] (conj owner-fields [:candidate-index [:int {:min 0}]] [:selection-scope [:fn vector?]]))})
(defschemas model-call-queries
  {:sheet/model-candidate-history [:map [:tick-id :uuid]]
   :sheet/model-candidate-history-result [:vector [:fn map?]]})
(def event-types #{:sheet/model-plan-retained :sheet/model-candidate-claimed :sheet/model-candidate-recorded :sheet/model-candidate-selected :sheet/model-candidate-output-exposed :sheet/model-request-recorded})
(defn authorized? [ctx] (and (:tenant-id ctx) (:auth-claims ctx)))
(defn- rejected [message] {::anom/category ::anom/conflict ::anom/message message})
(defn- events [ctx tags types]
  (let [result (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :tags tags :types types})]
    (when (::anom/category result)
      (throw (ex-info "Cannot read durable model history"
                      {:failure-kind :model-plan-incompatible :reason :model-history-unavailable})))
    (into [] result)))
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
(defn- prime-frontier? [owner event]
  (and (#{:prime :subcall} (:role owner)) (= effects/frontier-event-type (:event/type event))
       (= (:sheet-id owner) (:sheet-id event)) (= (:tick-id owner) (:tick-id event))
       (= (:node-id owner) (:node-id event))))

(defn- owns-facts? [owner xs]
  (let [latest (last (filter #(and (= :sheet/node-execution-started (:event/type %))
                                   (= (:tick-id owner) (:tick-id %)) (= (:node-id owner) (:node-id %))
                                   (= (:exec-context owner) (value-log/exec-context (:inputs %)))) xs))
        prime? (#{:prime :subcall} (:role owner))
        retained-prime? (and prime?
                             (some #(and (= :sheet/model-plan-retained (:event/type %))
                                         (= (select-keys owner [:sheet-id :tick-id :node-id :exec-context :role])
                                            (select-keys % [:sheet-id :tick-id :node-id :exec-context :role]))) xs))
        guarded-prime? (and prime? (or retained-prime? (some? (:ownership-epoch owner))))]
    (and (= (:node-start-id owner) (:event/id latest))
         (= (:sheet-id owner) (:sheet-id latest))
         (or (not guarded-prime?)
             (and (pos-int? (:ownership-epoch owner))
                  (= (:ownership-epoch owner)
                     (:ownership-epoch (last (filter #(prime-frontier? owner %) xs)))))))))


(def operation-fields [:sheet-id :tick-id :node-id :exec-context :role :logical-action-id])

(defn- same-operation? [owner event]
  (= (select-keys owner operation-fields) (select-keys event operation-fields)))

(defn- inline-role-plans [owner xs]
  (filterv #(and (= :sheet/model-plan-retained (:event/type %))
                 (= (select-keys owner [:sheet-id :tick-id :node-id :exec-context :role])
                    (select-keys % [:sheet-id :tick-id :node-id :exec-context :role]))) xs))

(defn- inline-configuration-admitted? [owner xs fresh-request? candidate-index]
  (let [plans (inline-role-plans owner xs)
        operation (filter #(and (same-operation? owner %)
                                 (= (:configuration-id owner) (:configuration-id %))
                                 (= candidate-index (or (:candidate-index %)
                                                        (get-in % [:request :candidate-index])
                                                        (get-in % [:entry :candidate-index])))) xs)
        known-response? (some #(or (and (= :sheet/model-request-recorded (:event/type %))
                                        (= :completed (get-in % [:request :phase]))
                                        (map? (get-in % [:request :outcome])))
                                   (and (= :sheet/model-candidate-recorded (:event/type %))
                                        (false? (get-in % [:entry :advanced?]))
                                        (map? (get-in % [:entry :outcome])))) operation)]
    (or (empty? plans)
        (some #(= (:configuration-id owner) (:configuration-id %)) plans)
        (and (not fresh-request?) known-response?))))

(def ^:private admission-event-types (conj event-types :sheet/node-execution-started effects/frontier-event-type))

(defn- plan-facts [xs owner scope-id]
  (filterv #(or (and (contains? event-types (:event/type %))
                     (or (same-operation? owner %)
                         (and (= :sheet/model-plan-retained (:event/type %))
                              (some? scope-id) (= scope-id (:plan-scope-id %)))
                         (and (#{:prime :subcall} (:role owner))
                              (= (select-keys owner [:sheet-id :tick-id :node-id :exec-context :role])
                                 (select-keys % [:sheet-id :tick-id :node-id :exec-context :role])))))
                (prime-frontier? owner %)
                (and (= :sheet/node-execution-started (:event/type %))
                     (= (:node-id owner) (:node-id %))
                     (= (:exec-context owner) (value-log/exec-context (:inputs %))))) xs))

(declare incompatible!)

(defn- specialist-topology [ctx owner]
  (let [campaign (budget-facts/researcher-campaign ctx (:tick-id owner))
        campaign-owner (if campaign
                         (merge (select-keys campaign [:sheet-id :tick-id :node-id :exec-context]) {:role :subcall})
                         (select-keys owner [:sheet-id :tick-id :node-id :exec-context :role]))]
    {:emitted? (boolean campaign) :child-tick-id (:tick-id owner) :campaign campaign :role-owner campaign-owner
     :tag (effects/campaign-tag (:sheet-id campaign-owner) (:tick-id campaign-owner) (:node-id campaign-owner))}))

(defn- merge-facts [& groups]
  (->> groups (apply concat) (reduce #(assoc %1 (:event/id %2) %2) {}) vals
       (sort-by (comp str :event/id)) vec))

(defn- specialist-state [ctx owner types]
  (let [{:keys [tag role-owner] :as topology} (specialist-topology ctx owner)
        indexed (events ctx #{tag} types)
        old (merge-facts (events ctx #{[:tick (:tick-id owner)]} types)
                         (when (not= (:tick-id owner) (:tick-id role-owner))
                           (events ctx #{[:tick (:tick-id role-owner)]} types)))
        legacy (filterv #(not (contains? (:event/tags %) tag)) old)]
    {:topology topology :legacy legacy :facts (merge-facts legacy indexed)}))

(defn- specialist-role-event? [{:keys [role-owner]} event]
  (and (= :subcall (:role event))
       (if (:selection-scope event)
         (= (mapv role-owner [:sheet-id :tick-id :node-id :role :exec-context])
            (vec (take 5 (:selection-scope event))))
         (= (select-keys role-owner [:sheet-id :tick-id :node-id :exec-context :role])
            (select-keys event [:sheet-id :tick-id :node-id :exec-context :role])))))

(defn- specialist-plans [topology xs]
  (filterv #(and (= :sheet/model-plan-retained (:event/type %))
                 (specialist-role-event? topology %)) xs))

(defn- ended? [owner xs]
  (let [lifecycle (last (filter #(and (= (:tick-id owner) (:tick-id %))
                                     (= (:node-id owner) (:node-id %))
                                     (= (:exec-context owner) (value-log/exec-context (:inputs %)))
                                     (#{:sheet/node-execution-started :sheet/node-execution-completed} (:event/type %))) xs))]
    (or (= :sheet/node-execution-completed (:event/type lifecycle))
        (some #(and (= (:tick-id owner) (:tick-id %))
                    (#{:sheet/tick-cancelled :sheet/tree-tick-completed} (:event/type %))) xs))))

(defn- specialist-owned? [{:keys [emitted?]} owner xs]
  (and (not (ended? owner xs))
       (if emitted?
         ;; The actual child leaf owns a node start, not a local researcher frontier.
         (owns-facts? (assoc owner :role :node) xs)
         (owns-facts? owner xs))))

(defn- fresh-specialist-work? [{:keys [emitted? campaign]} owner xs]
  (or (not emitted?) (nil? (:ownership-epoch campaign))
      (emitted-authority/fresh-authorized? owner campaign xs)))

(defn- specialist-configuration-admitted? [topology owner xs fresh? index]
  (let [plans (specialist-plans topology xs)
        actual (filter #(and (same-operation? owner %)
                              (= (:configuration-id owner) (:configuration-id %))
                              (= index (or (:candidate-index %) (get-in % [:request :candidate-index])
                                           (get-in % [:entry :candidate-index])))) xs)
        saved? (some #(or (and (= :sheet/model-request-recorded (:event/type %))
                               (= :completed (get-in % [:request :phase])) (map? (get-in % [:request :outcome])))
                          (and (= :sheet/model-candidate-recorded (:event/type %))
                               (false? (get-in % [:entry :advanced?])) (map? (get-in % [:entry :outcome])))) actual)]
    (and (or (empty? plans) (some #(= (:configuration-id owner) (:configuration-id %)) plans)
             (and (not fresh?) saved?))
         (or (not fresh?) (fresh-specialist-work? topology owner xs)))))

(def ^:private specialist-event-types
  (into (into admission-event-types emitted-authority/fact-types)
        #{effects/claim-event-type :sheet/node-execution-completed
          :sheet/tick-cancelled :sheet/tree-tick-completed}))

(defn- specialist-plan-facts [topology xs owner scope-id]
  (filterv #(or (and (contains? event-types (:event/type %))
                     (or (same-operation? owner %)
                         (specialist-role-event? topology %)
                         (and scope-id (= scope-id (:plan-scope-id %)))))
                (and (contains? specialist-event-types (:event/type %))
                     (not (contains? event-types (:event/type %))))) xs))

(defn- owns? [ctx owner]
  (if (= :subcall (:role owner))
    (let [{:keys [topology facts]} (specialist-state ctx owner specialist-event-types)]
      (specialist-owned? topology owner facts))
    (owns-facts? owner (events ctx #{[:tick (:tick-id owner)]}
                               #{:sheet/node-execution-started :sheet/model-plan-retained effects/frontier-event-type}))))

(defn- journal-event [ctx owner definition]
  (->event (cond-> definition
             (= :subcall (:role owner))
             (update :tags conj (:tag (specialist-topology ctx owner))))))

(defn- specialist-cas [ctx owner original fresh? index]
  (let [{:keys [topology legacy facts]} (specialist-state ctx owner specialist-event-types)
        expected (specialist-plan-facts topology facts owner nil)
        selection-scope (scope ctx owner)]
    {:tags #{(:tag topology)} :types specialist-event-types
     :predicate-fn
     (fn [stream]
       (let [xs (merge-facts legacy (into [] stream))]
         (and (specialist-owned? topology owner xs)
              (specialist-configuration-admitted? topology owner xs fresh? index)
              (= expected (specialist-plan-facts topology xs owner nil))
              ((:predicate-fn original)
               (filterv #(and (contains? (:types original) (:event/type %))
                              (if (= #{(call-tag owner)} (:tags original))
                                (and (same-operation? owner %) (= (:configuration-id owner) (:configuration-id %)))
                                (= selection-scope (:selection-scope %)))) xs)))))}))

(defn- retain-specialist-plan [ctx owner scope-id]
  (let [{:keys [topology legacy facts]} (specialist-state ctx owner specialist-event-types)
        selected (specialist-plan-facts topology facts owner scope-id)
        plans (specialist-plans topology selected)
        retained (first (filter #(= scope-id (:plan-scope-id %)) plans))
        configurations (set (map :configuration-id plans))
        work (filter #(and (contains? event-types (:event/type %))
                           (not= :sheet/model-plan-retained (:event/type %))) selected)
        incompatible? (or (some #(not (contains? configurations (:configuration-id %))) work)
                          (some #(and (same-operation? owner %)
                                      (or (nil? retained) (not= (:configuration-id retained) (:configuration-id %)))) work))
        plan (get-in ctx [:command :plan])]
    (cond
      (not (specialist-owned? topology owner facts)) (rejected "Model plan does not own the current node start")
      (not (fresh-specialist-work? topology owner facts)) (rejected "Model plan does not own the current campaign")
      (not= (:configuration-id owner) (retained-configuration-id plan)) (rejected "Model plan configuration identity does not match its retained data")
      incompatible? (rejected "Historical model work already owns this operation")
      retained {:command-result/events []}
      :else
      {:command-result/events
       [(journal-event ctx owner {:type :sheet/model-plan-retained
                                  :tags #{[:sheet (:sheet-id owner)] [:tick (:tick-id owner)] (call-tag owner)}
                                  :body (assoc owner :selection-scope (scope ctx owner) :plan-scope-id scope-id :plan plan)})]
       :command-result/cas
       {:tags #{(:tag topology)} :types specialist-event-types
        :predicate-fn (fn [stream]
                        (let [xs (merge-facts legacy (into [] stream))]
                          (and (specialist-owned? topology owner xs)
                               (fresh-specialist-work? topology owner xs)
                               (= selected (specialist-plan-facts topology xs owner scope-id)))))}})))

(defcommand :sheet retain-model-plan {:authorized? authorized?} [ctx]
  (if (= :subcall (get-in ctx [:command :role]))
    (retain-specialist-plan ctx (select-keys (:command ctx) owner-keys) (get-in ctx [:command :plan-scope-id]))
  (let [owner (select-keys (:command ctx) owner-keys)
        scope-id (get-in ctx [:command :plan-scope-id])
        types admission-event-types
        facts (plan-facts (events ctx #{[:tick (:tick-id owner)]} types) owner scope-id)
        latest (last (filter #(= :sheet/node-execution-started (:event/type %)) facts))
        retained (first (filter #(and (= :sheet/model-plan-retained (:event/type %))
                                     (or (= :node (:role owner)) (= scope-id (:plan-scope-id %)))) facts))
        journal-facts (filter #(and (contains? event-types (:event/type %))
                                    (not= :sheet/model-plan-retained (:event/type %))) facts)
        incompatible-history?
        (if (= :subcall (:role owner))
          (let [retained-configs (set (map :configuration-id (inline-role-plans owner facts)))
                current-request (filter #(same-operation? owner %) journal-facts)]
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
                            (:ownership-epoch (last (filter #(prime-frontier? owner %) facts))))))))
      (rejected "Model plan does not own the current node start")
      (not (#{:node :prime :subcall} (:role owner))) (rejected "Unsupported retained model role")
      (not= (:configuration-id owner) (retained-configuration-id (get-in ctx [:command :plan])))
      (rejected "Model plan configuration identity does not match its retained data")
      incompatible-history?
      (rejected "Historical model work already owns this operation")
      retained {:command-result/events []}
      :else
      {:command-result/events
       [(->event {:type :sheet/model-plan-retained
                  :tags #{[:sheet (:sheet-id owner)] [:tick (:tick-id owner)] (call-tag owner)}
                  :body (assoc owner :plan-scope-id scope-id :plan (get-in ctx [:command :plan]))})]
       ;; The fence includes same-operation journal facts, independent of their
       ;; configuration hash. An old claim racing this preparation must not
       ;; escape into a second bucket, in either append ordering.
       :command-result/cas {:tags #{[:tick (:tick-id owner)]} :types types
                            :predicate-fn (fn [stream]
                                            (let [xs (into [] stream)]
                                              (and (owns-facts? owner xs)
                                                   (= facts (plan-facts xs owner scope-id)))))}}))))
(defn- call-events [ctx owner] (events ctx #{(call-tag owner)} event-types))
(defn- candidate-events [xs index]
  (filter #(and (#{:sheet/model-candidate-claimed :sheet/model-candidate-recorded} (:event/type %))
                  (= index (or (:candidate-index %) (get-in % [:entry :candidate-index])))) xs))
(defn- parent-inline-role? [ctx owner]
  ;; Emitted leaves use :subcall too, but their persisted child tick carries
  ;; campaign routing. Keep their cross-child selection CAS unchanged.
  (and (= :subcall (:role owner))
       (nil? (:researcher-campaign-node-id (tick-options ctx owner)))))

(defn- prime-owned-cas [ctx owner cas & [fresh-request? candidate-index]]
  (if (= :subcall (:role owner))
    (specialist-cas ctx owner cas fresh-request? candidate-index)
  (if-not (or (= :prime (:role owner)) (parent-inline-role? ctx owner)) cas
    (let [inline? (parent-inline-role? ctx owner)
          facts (plan-facts (events ctx #{[:tick (:tick-id owner)]} admission-event-types) owner nil)
          selection-scope (scope ctx owner)]
      {:tags #{[:tick (:tick-id owner)]} :types admission-event-types
       :predicate-fn (fn [stream]
                       (let [xs (into [] stream)]
                         (and (owns-facts? owner xs)
                              (or (not inline?) (inline-configuration-admitted? owner xs fresh-request? candidate-index))
                              (= facts (plan-facts xs owner nil))
                              ((:predicate-fn cas)
                               (filterv #(and (contains? (:types cas) (:event/type %))
                                              (if (= #{(call-tag owner)} (:tags cas))
                                                (and (same-operation? owner %)
                                                     (= (:configuration-id owner) (:configuration-id %)))
                                                (= selection-scope (:selection-scope %)))) xs)))))}))))

(defn- append [ctx owner type body predicate]
  (let [s (scope ctx owner) tags #{[:sheet (:sheet-id owner)] [:tick (:tick-id owner)]
                                 (call-tag owner) (scope-tag s)}]
    {:command-result/events [(journal-event ctx owner {:type type :tags tags
                                       :body (merge owner {:selection-scope s} body)})]
     :command-result/cas (prime-owned-cas ctx owner
                          {:tags #{(call-tag owner)} :types event-types
                           :predicate-fn #(predicate (into [] %))}
                          (and (= :sheet/model-request-recorded type)
                               (= :started (get-in body [:request :phase])))
                          (or (:candidate-index body) (get-in body [:request :candidate-index])
                              (get-in body [:entry :candidate-index])))}))

(defcommand :sheet claim-model-candidate {:authorized? authorized?} [ctx]
  (let [owner (select-keys (:command ctx) owner-keys) index (get-in ctx [:command :candidate-index])
        inline? (parent-inline-role? ctx owner)
        ordinary? (#{:node :prime} (:role owner))
        facts (when ordinary?
                (plan-facts (events ctx #{[:tick (:tick-id owner)]} admission-event-types) owner nil))
        xs (if ordinary?
             (filterv #(and (contains? event-types (:event/type %))
                            (same-operation? owner %)
                            (= (:configuration-id owner) (:configuration-id %))) facts)
             (call-events ctx owner))
        same (last (candidate-events xs index))
        incompatible-plan? (if (= :subcall (:role owner))
                             (let [{:keys [topology facts]} (specialist-state ctx owner specialist-event-types)]
                               (not (specialist-configuration-admitted? topology owner facts false index)))
                             (some #(and (= :sheet/model-plan-retained (:event/type %))
                                         (not= (:configuration-id owner) (:configuration-id %))) facts))]
    (cond
      (not (owns? ctx owner)) (rejected "Model candidate does not own the current node start")
      incompatible-plan? (rejected "Retained model plan already owns this operation")
      (= :sheet/model-candidate-recorded (:event/type same)) (rejected "Model candidate already completed")
      (= (:node-start-id owner) (:node-start-id same)) (assoc (rejected "Model candidate already claimed by this node start") :model-call-in-flight? true)
      :else (cond-> (append ctx owner :sheet/model-candidate-claimed {:candidate-index index}
                            #(= xs %))
              ordinary?
              (assoc :command-result/cas
                     {:tags #{[:tick (:tick-id owner)]} :types admission-event-types
                      :predicate-fn (fn [stream]
                                      (let [xs (into [] stream)]
                                        (and (owns-facts? owner xs)
                                             (or (not inline?) (inline-configuration-admitted? owner xs false index))
                                             (= facts (plan-facts xs owner nil)))))})))))

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
      :else {:command-result/events [(journal-event ctx owner {:type :sheet/model-candidate-selected
                                              :tags #{[:sheet (:sheet-id owner)] [:tick (:tick-id owner)]
                                                      (call-tag owner) (scope-tag s)}
                                              :body (assoc owner :selection-scope s :candidate-index index)})]
             :command-result/cas (prime-owned-cas ctx owner
                                  {:tags #{(scope-tag s)} :types #{:sheet/model-candidate-selected}
                                   :predicate-fn #(= floor (reduce (fn [m e] (max m (:candidate-index e))) 0 %))}
                                  false index)})))

(defreadmodel :sheet model-candidate-ledger {:events event-types :version 3} [state event]
  (update state :entries (fnil conj [])
          (select-keys event (concat owner-keys [:event/type :event/id :candidate-index :entry :request :selection-scope :plan-scope-id :plan]))))
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
  (let [owner (assoc owner :configuration-id (or (:configuration-id owner) (configuration-id candidates policy)))
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
     :fresh-authority! (when (and (= :subcall (:role owner))
                                  (budget-facts/researcher-campaign ctx (:tick-id owner)))
                         (fn [deadline] (emitted-authority/ensure! ctx owner deadline)))
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

(defn- incompatible! [reason]
  (throw (ex-info (str "Model plan cannot be resumed [" (name reason) "]")
                  {:failure-kind :model-plan-incompatible :reason reason})))

(defn- checked-plan [plan]
  (when-not (and (m/validate retained-plan-schema plan)
                (every? (fn [{:keys [protocol options profile]}]
                          (let [mode (:output-mode profile)
                                version (:version profile)]
                            (and (= (:force-tool-choice? profile)
                                    (and (= :chat protocol) (boolean (:force-tool-choice? options))))
                                 (case version
                                   "native-decision-v1"
                                   (and (= :decision protocol) (nil? mode)
                                        (not (contains? options :output-mode))
                                        (false? (:strict-arguments? profile)))
                                   "paired-function-v1-strict"
                                   (and (= :chat protocol) (= :function-calling mode (:output-mode options))
                                        (true? (:strict-arguments? profile)))
                                   "strict-json-v1"
                                   (and (= :chat protocol) (= :json-schema mode (:output-mode options))
                                        (false? (:strict-arguments? profile)))
                                   "legacy-function-v1"
                                   (and (= :chat protocol) (nil? mode) (nil? (:output-mode options))
                                        (true? (:use-function-calling? options)) (false? (:strict-arguments? profile)))
                                   "legacy-marker-v1"
                                   (and (= :chat protocol) (nil? mode) (nil? (:output-mode options))
                                        (false? (:use-function-calling? options)) (false? (:strict-arguments? profile)))
                                   false)))) (:candidates plan)))
    (incompatible! :retained-profile-version-unsupported))
  plan)

(defn plan-scope-id
  "Stable authored scope; provider-registry defaults and live deadline overlays
   are deliberately absent. Used before resolving any current provider."
  [owner authored-config]
  (effects/provider-logical-action-identity
   {:module (select-keys owner operation-fields) :inputs authored-config :options {}}))

(defn- legacy-terminal-plan [history]
  ;; These placeholders are never dispatched: run-candidates immediately replays
  ;; the exact terminal outcome. Preserve its original configuration ID instead
  ;; of making a new hash bucket or consulting a now-missing provider registry.
  (let [entries (keep :entry history)
        last-index (reduce max 0 (concat (keep :candidate-index entries)
                                        (keep :candidate-index history)
                                        (keep #(get-in % [:request :candidate-index]) history)))]
    (mapv (fn [index]
            (let [entry (last (filter #(= index (:candidate-index %)) entries))
                  request (last (filter #(= index (:candidate-index %)) (keep :request history)))]
              {:provider (or (:provider entry) (:provider request) :historical-unresolved)
               :model (or (:requested-model entry) (:requested-model request)) :options {}}))
          (range (inc last-index)))))

(defn prepare-ordinary-call
  "Retain one effective ordinary model plan under current node ownership.
   resolve-plan! is lazy: exact historical terminal replay never calls it.
   Researcher prime/effect identities do not use this preparation yet."
  [ctx owner authored-config resolve-plan!]
  (let [scope-id (plan-scope-id owner authored-config)
        history (events ctx #{[:tick (:tick-id owner)]} event-types)
        operation (filterv #(same-operation? owner %) history)
        plans (filterv #(= :sheet/model-plan-retained (:event/type %)) operation)
        legacy (filterv #(not= :sheet/model-plan-retained (:event/type %)) operation)
        retained (first plans)]
    (when (or (> (count plans) 1)
              (and retained
                   (or (not= scope-id (:plan-scope-id retained))
                       (some #(not= (:configuration-id retained) (:configuration-id %)) legacy))))
      (incompatible! :historical-operation-ambiguous))
    (if (and (nil? retained) (seq legacy))
      (let [configs (set (map :configuration-id legacy))
            terminal (last (filter #(= :sheet/model-candidate-recorded (:event/type %)) legacy))
            entry (:entry terminal)]
        (when-not (= 1 (count configs)) (incompatible! :historical-operation-ambiguous))
        (when-not (and terminal (false? (:advanced? entry)) (map? (:outcome entry)))
          (incompatible! :legacy-effective-profile-unretained))
        (let [candidates (legacy-terminal-plan legacy)
              policy (or (get-in entry [:outcome :model-fallback :policy]) :temporary-transport)
              bound (callbacks ctx (assoc owner :configuration-id (first configs)) candidates policy)]
          (when-not (:completed-outcome bound) (incompatible! :historical-operation-ambiguous))
          {:candidates candidates :policy policy :callbacks bound :historical-terminal? true}))
      (let [retained
            (or retained
                (let [plan (checked-plan (resolve-plan!))
                      config-id (retained-configuration-id plan)
                      result (command! ctx :sheet/retain-model-plan
                                       (assoc owner :configuration-id config-id
                                              :plan-scope-id scope-id :plan plan))]
                  (when (::anom/category result) (incompatible! :model-plan-ownership-lost))
                  (or (first (filter #(and (= :sheet/model-plan-retained (:event/type %))
                                          (same-operation? owner %))
                                     (events ctx #{[:tick (:tick-id owner)]} event-types)))
                      (incompatible! :model-plan-not-retained))))
            _ (when-not (= scope-id (:plan-scope-id retained))
                (incompatible! :historical-operation-ambiguous))
            plan (:plan retained)
            _ (when-not (m/validate retained-plan-schema plan)
                (incompatible! :retained-profile-version-unsupported))
            _ (when-not (= (:configuration-id retained) (retained-configuration-id plan))
                (incompatible! :retained-plan-identity-mismatch))
            bound (callbacks ctx (assoc owner :configuration-id (:configuration-id retained))
                             (:candidates plan) (:policy plan))
            ;; A terminal result is already decoded durable data. Replaying it
            ;; does not interpret a response protocol or authorize another call.
            ;; Only unfinished work needs a profile this runtime can dispatch.
            replay-only?
            (when-not (:completed-outcome bound)
              (try
                (checked-plan plan)
                false
                (catch clojure.lang.ExceptionInfo error
                  (if (and (= :retained-profile-version-unsupported (:reason (ex-data error)))
                           (some #(and (= (:start-index bound) (:candidate-index %))
                                       (= :completed (:phase %)) (map? (:outcome %)))
                                 (:request-receipts bound)))
                    ;; The executor may consume exact physical receipts, but it
                    ;; must refuse before any unrecorded attempt or candidate.
                    true
                    (throw error)))))]
        {:candidates (:candidates plan) :policy (:policy plan) :callbacks bound
         :plan plan :configuration-id (:configuration-id retained)
         :replay-only? (boolean replay-only?)}))))


(def prime-role-fields [:sheet-id :tick-id :node-id :exec-context :role])

(defn- same-prime-role? [owner event]
  (= (select-keys owner prime-role-fields) (select-keys event prime-role-fields)))

(defn prime-plan-scope-id
  "Campaign-role configuration identity, independent of a conversation turn."
  [owner authored-config]
  (str "prime-role-v1:"
       (effects/provider-logical-action-identity
        {:module (select-keys owner prime-role-fields) :inputs authored-config :options {}})))

(defn prepare-prime-call
  "Find immutable role data before registry resolution, then bind one actual
   iteration's original logical request. Legacy IDs are never recalculated."
  [ctx owner authored-config iteration effect-claims resolve-plan! request-id!]
  (let [scope-id (prime-plan-scope-id owner authored-config)
        history (filterv #(same-prime-role? owner %)
                         (events ctx #{[:tick (:tick-id owner)]} event-types))
        plans (filterv #(and (= :sheet/model-plan-retained (:event/type %))
                             (= scope-id (:plan-scope-id %))) history)
        _ (when (> (count plans) 1) (incompatible! :historical-operation-ambiguous))
        retained (first plans)
        legacy (filterv #(not= :sheet/model-plan-retained (:event/type %)) history)
        iteration-ids (set (map :logical-action-identity
                               (filter #(and (= iteration (:iteration-index %))
                                             (= :provider (:kind %))) effect-claims)))
        associated (filterv #(contains? iteration-ids (:logical-action-id %)) legacy)
        historical-ids (set (map :logical-action-id associated))]
    (when (> (count historical-ids) 1) (incompatible! :historical-operation-ambiguous))
    (cond
      ;; An exact role+iteration association locates canonical completion before
      ;; interpreting any retained profile or recomputing a newer request hash.
      (and (= 1 (count historical-ids))
           (some #(and (= (first historical-ids) (:logical-action-identity %))
                       (= :completed (:status %))) effect-claims))
      {:logical-action-id (first historical-ids) :historical-effect-only? true}

      (and (:legacy-completed-action-id owner) (nil? retained)
           (empty? historical-ids)
           (every? #{(:legacy-completed-action-id owner)} iteration-ids))
      {:logical-action-id (:legacy-completed-action-id owner) :historical-effect-only? true}

      (and (nil? retained) (or (seq legacy) (seq iteration-ids)))
      (let [logical-id (first historical-ids)
            configs (set (map :configuration-id associated))
            _ (when-not (and logical-id (= 1 (count configs)))
                (incompatible! :legacy-effective-profile-unretained))
            candidates (legacy-terminal-plan associated)
            policy (or (last (keep #(get-in % [:entry :outcome :model-fallback :policy]) associated))
                       (:fallback-policy authored-config) :temporary-transport)
            bound (callbacks ctx (assoc owner :logical-action-id logical-id
                                       :configuration-id (first configs))
                             candidates policy)
            completed-receipt? (some #(and (= :completed (:phase %))
                                           (= (:start-index bound) (:candidate-index %))
                                           (map? (:outcome %))) (:request-receipts bound))]
        (when-not (or (:completed-outcome bound) completed-receipt?)
          (incompatible! :legacy-effective-profile-unretained))
        {:logical-action-id logical-id :configuration-id (first configs)
         :candidates candidates :policy policy :callbacks bound
         :replay-only? true :historical-terminal? true})
      :else
      (let [retained
            (or retained
                (let [plan (checked-plan (resolve-plan!))
                      config-id (retained-configuration-id plan)
                      logical-id (request-id! plan config-id)
                      result (command! ctx :sheet/retain-model-plan
                                       (assoc owner :logical-action-id logical-id
                                              :configuration-id config-id :plan-scope-id scope-id :plan plan))]
                  (when (::anom/category result) (incompatible! :model-plan-ownership-lost))
                  (or (first (filter #(and (= :sheet/model-plan-retained (:event/type %))
                                          (same-prime-role? owner %) (= scope-id (:plan-scope-id %)))
                                     (events ctx #{[:tick (:tick-id owner)]} event-types)))
                      (incompatible! :model-plan-not-retained))))
            plan (:plan retained)
            _ (when-not (m/validate retained-plan-schema plan)
                (incompatible! :retained-profile-version-unsupported))
            config-id (:configuration-id retained)
            _ (when-not (= config-id (retained-configuration-id plan))
                (incompatible! :retained-plan-identity-mismatch))
            computed-id (request-id! plan config-id)
            historical-id (first historical-ids)
            historical-configs (set (map :configuration-id associated))
            _ (when (or (> (count historical-configs) 1)
                        (and (:legacy-completed-action-id owner) (empty? historical-ids)))
                (incompatible! :historical-operation-ambiguous))
            original-config (or (first historical-configs) config-id)
            logical-id (or historical-id computed-id)
            mismatched? (or (not= logical-id computed-id) (not= original-config config-id))
            candidates (if (= original-config config-id) (:candidates plan) (legacy-terminal-plan associated))
            bound (callbacks ctx (assoc owner :logical-action-id logical-id :configuration-id original-config)
                             candidates (:policy plan))
            completed-receipt? (some #(and (= (:start-index bound) (:candidate-index %))
                                          (= :completed (:phase %)) (map? (:outcome %))) (:request-receipts bound))
            _ (when (and mismatched? (not (:completed-outcome bound)) (not completed-receipt?))
                (incompatible! :legacy-effective-profile-unretained))
            replay-only?
            (if mismatched? true
            (when-not (:completed-outcome bound)
              (try
                (checked-plan plan)
                false
                (catch clojure.lang.ExceptionInfo error
                  (if (and (= :retained-profile-version-unsupported (:reason (ex-data error)))
                           (some #(and (= (:start-index bound) (:candidate-index %))
                                       (= :completed (:phase %)) (map? (:outcome %)))
                                 (:request-receipts bound)))
                    true
                    (throw error))))))]
        {:logical-action-id logical-id :configuration-id original-config :plan plan
         :candidates candidates :policy (:policy plan) :callbacks bound
         :replay-only? (boolean replay-only?)}))))


(defn inline-plan-scope-id
  "One parent-researcher specialist configuration, independent of request input."
  [owner authored-config]
  (str "inline-role-v1:"
       (effects/provider-logical-action-identity
        {:module (select-keys owner prime-role-fields) :inputs authored-config :options {}})))

(defn- prepare-specialist-call
  "Bind the original inline effect request before provider resolution. The
   parent researcher owns this role; emitted child nodes do not use this seam."
  [ctx owner authored-config resolve-plan!]
  (let [{:keys [topology facts]} (specialist-state ctx owner specialist-event-types)
        scope-id (inline-plan-scope-id (:role-owner topology) authored-config)
        history (filterv #(specialist-role-event? topology %) facts)
        plans (filterv #(and (= :sheet/model-plan-retained (:event/type %))
                             (= scope-id (:plan-scope-id %))) history)
        _ (when (> (count plans) 1) (incompatible! :historical-operation-ambiguous))
        retained (first plans)
        operation (filterv #(same-operation? owner %) history)
        legacy (filterv #(not= :sheet/model-plan-retained (:event/type %)) operation)
        configurations (set (map :configuration-id legacy))
        _ (when (> (count configurations) 1) (incompatible! :historical-operation-ambiguous))
        ;; Preparation runs before THIS execution's new guarded effect claim.
        ;; A prior claim with no effective profile is not evidence of a fresh call.
        prior-effect? (some #(= (:logical-action-id owner) (:logical-action-identity %))
                            (events ctx #{[:tick (:tick-id owner)]}
                                    #{effects/claim-event-type effects/completion-event-type
                                      effects/indeterminate-event-type}))
        retained-configurations (set (map :configuration-id
                                          (filter #(= :sheet/model-plan-retained (:event/type %)) history)))
        unretained-history? (some #(and (not= :sheet/model-plan-retained (:event/type %))
                                        (not (contains? retained-configurations (:configuration-id %)))) history)
        legacy-config (first configurations)
        historical? (and legacy-config
                         (or (nil? retained) (not= legacy-config (:configuration-id retained))))]
    (if historical?
      (let [candidates (legacy-terminal-plan legacy)
            policy (or (last (keep #(get-in % [:entry :outcome :model-fallback :policy]) legacy))
                       (:fallback-policy authored-config) :temporary-transport)
            bound (callbacks ctx (assoc owner :configuration-id legacy-config) candidates policy)
            physical? (some #(and (= (:start-index bound) (:candidate-index %))
                                  (= :completed (:phase %)) (map? (:outcome %)))
                            (:request-receipts bound))]
        (when-not (or (:completed-outcome bound) physical?)
          (incompatible! :legacy-effective-profile-unretained))
        {:logical-action-id (:logical-action-id owner) :configuration-id legacy-config
         :candidates candidates :policy policy :callbacks bound
         :replay-only? true :historical-terminal? true})
      (let [_ (when (and (nil? retained)
                         (or prior-effect? unretained-history?))
                (incompatible! :legacy-effective-profile-unretained))
            retained
            (or retained
                (let [plan (checked-plan (resolve-plan!))
                      result (command! ctx :sheet/retain-model-plan
                                       (assoc owner :configuration-id (retained-configuration-id plan)
                                              :plan-scope-id scope-id :plan plan))]
                  (when (::anom/category result) (incompatible! :model-plan-ownership-lost))
                  (or (first (filter #(and (= :sheet/model-plan-retained (:event/type %))
                                          (= scope-id (:plan-scope-id %))
                                          (specialist-role-event? topology %))
                                     (:facts (specialist-state ctx owner specialist-event-types))))
                      (incompatible! :model-plan-not-retained))))
            plan (:plan retained)
            _ (when-not (m/validate retained-plan-schema plan)
                (incompatible! :retained-profile-version-unsupported))
            _ (when-not (= (:configuration-id retained) (retained-configuration-id plan))
                (incompatible! :retained-plan-identity-mismatch))
            bound (callbacks ctx (assoc owner :configuration-id (:configuration-id retained))
                             (:candidates plan) (:policy plan))
            replay-only?
            (when-not (:completed-outcome bound)
              (try (checked-plan plan) false
                   (catch clojure.lang.ExceptionInfo error
                     (if (and (= :retained-profile-version-unsupported (:reason (ex-data error)))
                              (some #(and (= (:start-index bound) (:candidate-index %))
                                          (= :completed (:phase %)) (map? (:outcome %)))
                                    (:request-receipts bound)))
                       true (throw error)))))]
        {:logical-action-id (:logical-action-id owner) :configuration-id (:configuration-id retained)
         :plan plan :candidates (:candidates plan) :policy (:policy plan) :callbacks bound
         :replay-only? (boolean replay-only?)}))))


(defn prepare-inline-call
  "Bind a checkpointed inline request to its original shared specialist role plan."
  [ctx owner authored-config resolve-plan!]
  (prepare-specialist-call ctx owner authored-config resolve-plan!))

(defn prepare-emitted-call
  "Bind an actual generated child leaf. Known results precede fresh lineage admission."
  [ctx owner authored-config resolve-plan!]
  (let [{:keys [campaign emitted? role-owner]} (specialist-topology ctx owner)
        original (first (filter #(= (:tick-id owner) (:tick-id %))
                                (events ctx #{[:tick (:tick-id owner)]} #{:sheet/tree-tick-started})))
        lineage? (loop [parent (:parent-tick-id original) seen #{}]
                   (cond
                     (= parent (:tick-id role-owner)) true
                     (or (nil? parent) (contains? seen parent)) false
                     :else (let [start (first (filter #(= parent (:tick-id %))
                                                      (events ctx #{[:tick parent]} #{:sheet/tree-tick-started})))]
                             (recur (:parent-tick-id start) (conj seen parent)))))
        admit-fresh! (fn []
                       (when-not (and emitted? lineage? (pos-int? (:ownership-epoch campaign))
                                      (nat-int? (:iteration-index campaign)))
                         (incompatible! :emitted-campaign-lineage-unavailable)))
        prepared (prepare-specialist-call ctx owner authored-config
                                          #(do (admit-fresh!) (resolve-plan!)))
        callbacks (:callbacks prepared)
        known? (or (:completed-outcome callbacks)
                   (some #(and (= (:start-index callbacks) (:candidate-index %))
                                (= :completed (:phase %)) (map? (:outcome %)))
                         (:request-receipts callbacks)))]
    (when-not known? (admit-fresh!))
    prepared))
