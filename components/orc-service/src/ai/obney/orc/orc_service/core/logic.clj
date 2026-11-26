(ns ai.obney.orc.orc-service.core.logic
  "Behavior tree execution, validation and projection helpers."
  (:refer-clojure :exclude [descendants sequence])
  (:require [ai.obney.grain.event-store-v3.interface :refer [->event]]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.file-store.interface :as fs]
            [ai.obney.orc.integrations.interface :as integrations]
            [ai.obney.orc.langfuse.interface :as langfuse]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.core.block :as block]
            [ai.obney.orc.orc-service.core.execution-budget :as execution-budget]
            [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [com.brunobonacci.mulog :as u]
            [malli.core :as m]
            [malli.error :as me]
            [malli.registry :as mr]
            [malli.transform :as mt]
            [sci.core :as sci]
            [taoensso.nippy :as nippy])
  (:import [ai.obney.orc.orc_service.core.block IBlockingCondition]
           [java.io StringWriter]
           [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.time Instant OffsetDateTime ZoneId ZonedDateTime]
           [java.time.format DateTimeFormatter]
           [java.util UUID]
           [java.util.concurrent Callable Future FutureTask]))

;; --- Blackboard schema ---

(def ^:private unconstrained-scalars #{:any :some})

(def ^:private collection-tags #{:vector :sequential :set :list :coll :every})

(def ^:private branch-tags #{:or :and :tuple :cat :alt :merge :union})

(def ^:private named-branch-tags #{:orn :altn :catn :multi})

(def ^:private wrapper-tags #{:maybe :not :? :* :+ :repeat})

(def ^:private positional-collection-tags #{:tuple :cat :alt :catn :altn})

(defn- schema-args
  [schema]
  (let [args (subvec schema 1)]
    (if (map? (first args)) (subvec args 1) args)))

(defn- violation
  [path reason schema]
  [{:path path :reason reason :schema schema}])

(defn- map-violations
  [inspect schema path]
  (let [entries (schema-args schema)]
    (if (empty? entries)
      (if (and (map? (second schema)) (true? (:closed (second schema))))
        []
        (violation path :map-fields-missing schema))
      (mapcat
       (fn [entry]
         (if (and (vector? entry) (<= 2 (count entry)))
           (let [field (first entry)
                 field-schema (if (map? (second entry))
                                (nth entry 2 nil)
                                (second entry))]
             (if (nil? field-schema)
               (violation (conj path field) :map-field-schema-missing entry)
               (inspect field-schema (conj path field))))
           []))
       entries))))

(defn- map-of-violations
  [inspect schema path]
  (let [[key-schema value-schema] (schema-args schema)]
    (cond-> []
      (nil? key-schema)
      (into (violation (conj path :keys) :map-key-schema-missing schema))

      (some? key-schema)
      (into (inspect key-schema (conj path :keys)))

      (nil? value-schema)
      (into (violation (conj path :values) :map-value-schema-missing schema))

      (some? value-schema)
      (into (inspect value-schema (conj path :values))))))

(defn- collection-violations
  [inspect schema path]
  (let [item-schema (first (schema-args schema))]
    (if (nil? item-schema)
      (violation (conj path :items) :collection-item-schema-missing schema)
      (inspect item-schema (conj path :items)))))

(defn violations
  "Return every blackboard-specificity violation in schema.

   Paths describe the value shape rather than Malli's vector indexes, so a
   consumer sees locations such as [:payload :items] and [:values]."
  ([schema] (violations schema []))
  ([schema path]
   (cond
     (contains? unconstrained-scalars schema)
     (violation path :unconstrained-type schema)

     (= :map schema)
     (violation path :map-fields-missing schema)

     (= :map-of schema)
     (map-of-violations violations [:map-of] path)

     (contains? collection-tags schema)
     (violation (conj path :items) :collection-item-schema-missing schema)

     (contains? positional-collection-tags schema)
     (violation (conj path :items) :collection-item-schema-missing schema)

     (vector? schema)
     (let [tag (first schema)]
       (cond
         (= :map tag) (map-violations violations schema path)
         (= :map-of tag) (map-of-violations violations schema path)
         (contains? collection-tags tag) (collection-violations violations schema path)
         (contains? wrapper-tags tag)
         (if-let [inner (first (schema-args schema))]
           (violations inner path)
           (violation path :wrapped-schema-missing schema))
         (= :schema tag)
         (let [properties (when (map? (second schema)) (second schema))]
           (concat
            (mapcat (fn [[name registered-schema]]
                      (violations registered-schema (conj path :registry name)))
                    (:registry properties))
            (when-let [root-schema (first (schema-args schema))]
              (violations root-schema path))))
         (contains? branch-tags tag)
         (let [branches (schema-args schema)]
           (if (and (contains? positional-collection-tags tag) (empty? branches))
             (violation (conj path :items) :collection-item-schema-missing schema)
             (mapcat (fn [[index branch]]
                       (violations branch (conj path index)))
                     (map-indexed vector branches))))
         (contains? named-branch-tags tag)
         (let [branches (schema-args schema)]
           (if (and (contains? positional-collection-tags tag) (empty? branches))
             (violation (conj path :items) :collection-item-schema-missing schema)
             (mapcat (fn [entry]
                       (if (and (vector? entry) (<= 2 (count entry)))
                         (violations (second entry) (conj path (first entry)))
                         []))
                     branches)))
         :else []))

     :else [])))

(defn schema-map-violations
  "Return violations enriched with their top-level blackboard key."
  [schema-map]
  (->> schema-map
       (mapcat (fn [[key schema]]
                 (map #(assoc % :key key) (violations schema))))
       vec))

(defn violation-location
  [{:keys [key path]}]
  (pr-str (into [key] path)))

(defn feedback
  [violations]
  (str "Unconstrained blackboard schemas at "
       (str/join ", " (map #(str (violation-location %) " (" (pr-str (:schema %)) ")")
                            violations))
       " are forbidden"
       "; use the most specific schema possible for each value's intent"))

(defn- collection-item-schema [infer-item values path]
  (when (empty? values)
    (throw (ex-info "Cannot infer the intent of an empty collection; provide an explicit, specific blackboard schema"
                    {:reason :empty-collection :path path})))
  (let [schemas (vec (distinct (map-indexed (fn [index value]
                                             (infer-item value (conj path index)))
                                           values)))]
    (if (= 1 (count schemas)) (first schemas) (into [:or] schemas))))

(defn infer-specific-schema
  "Infer observed structure, never semantic meaning, from an existing value.
   Use an authored schema first. Empty containers cannot reveal their intended
   member shape and fail with the exact value path instead of becoming :any."
  ([value] (infer-specific-schema value []))
  ([value path]
   (when (or (ratio? value)
             (and (float? value) (not (Double/isFinite (double value)))))
     (throw (ex-info "Cannot represent this numeric value losslessly in a model input; provide an explicit representation and contract"
                     {:reason :unrepresentable-value :path path :value-type (.getName (class value))})))
   (cond
     (nil? value) :nil
     (string? value) :string
     (boolean? value) :boolean
     (int? value) :int
     (integer? value) 'integer?
     (double? value) :double
     (float? value) 'float?
     (decimal? value) 'decimal?
     (keyword? value) :keyword
     (uuid? value) :uuid
     (inst? value) 'inst?
     (map? value)
     (do
       (when (empty? value)
         (throw (ex-info "Cannot infer the intent of an empty map; provide an explicit, field-specific blackboard schema"
                         {:reason :empty-map :path path})))
       (into [:map]
             (map (fn [[key item]] [key (infer-specific-schema item (conj path key))]))
             (sort-by (comp pr-str key) value)))
     (vector? value) [:vector (collection-item-schema infer-specific-schema value path)]
     (set? value) [:set (collection-item-schema infer-specific-schema (sort-by pr-str value) path)]
     (sequential? value) [:sequential (collection-item-schema infer-specific-schema value path)]
     :else
     (throw (ex-info "Cannot infer a specific blackboard schema; provide an explicit contract for this value"
                     {:reason :unrepresentable-value :path path :value-type (.getName (class value))})))))

;; --- Block ---

(defn block!
  "Throw the orc block signal carrying `payload` (opaque to orc). The carrier
   is an AssertionError subclass, so a Phase-1-style `catch AssertionError`
   still catches it; the engine's leaf path recognizes it via
   `blocking-condition?` and completes the node :blocked."
  [payload]
  (throw (proxy [AssertionError IBlockingCondition] ["orc blocking condition"]
           (blockPayload [] payload))))

(defn blocking-condition?
  "True iff `t` is an orc block signal (as raised by `block!`)."
  [t]
  (instance? IBlockingCondition t))

(defn block-payload
  "The opaque payload carried by a block signal, or nil if `t` is not one."
  [t]
  (when (instance? IBlockingCondition t)
    (.blockPayload ^IBlockingCondition t)))

;; --- Researcher effects ---

(def frontier-event-type :rlm/researcher-frontier-claimed)

(def claim-event-type :rlm/researcher-effect-claimed)

(def completion-event-type :rlm/researcher-effect-completed)

(def indeterminate-event-type :rlm/researcher-effect-indeterminate)

(defn campaign-tag-id
  "Stable event-store tag UUID for one researcher campaign occurrence."
  [sheet-id tick-id node-id]
  (UUID/nameUUIDFromBytes
   (.getBytes (pr-str [:researcher-campaign sheet-id tick-id node-id])
              StandardCharsets/UTF_8)))

(defn campaign-tag
  [sheet-id tick-id node-id]
  [:researcher-campaign (campaign-tag-id sheet-id tick-id node-id)])

(defn- researcher-effects-ordered
  [values]
  (sort-by pr-str values))

(defn- researcher-effects-canonical-value
  "Typed, traversal-order-independent data used only as a hash preimage."
  [value]
  (cond
    (nil? value) [:nil]
    (record? value)
    (throw (ex-info "unsupported researcher effect identity value"
                    {:value-class (.getName (class value))}))
    (map-entry? value) [:map-entry (researcher-effects-canonical-value (key value))
                        (researcher-effects-canonical-value (val value))]
    (map? value) [:map (->> value
                            (map (fn [[k v]] [(researcher-effects-canonical-value k)
                                              (researcher-effects-canonical-value v)]))
                            researcher-effects-ordered
                            vec)]
    (set? value) [:set (->> value (map researcher-effects-canonical-value) researcher-effects-ordered vec)]
    (vector? value) [:vector (mapv researcher-effects-canonical-value value)]
    (list? value) [:list (mapv researcher-effects-canonical-value value)]
    (keyword? value) [:keyword (namespace value) (name value)]
    (symbol? value) [:symbol (namespace value) (name value)]
    (uuid? value) [:uuid (str value)]
    (or (string? value) (boolean? value) (number? value) (char? value)
        (inst? value))
    [:scalar (.getName (class value)) (pr-str value)]
    :else
    (throw (ex-info "unsupported researcher effect identity value"
                    {:value-class (.getName (class value))}))))

(defn- sha-256
  [value]
  (let [bytes (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str value) StandardCharsets/UTF_8))]
    (str "sha256:"
         (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes)))))

(defn logical-action-identity
  "Stable content identity for one logical researcher action.

   Attempt number and evaluation order are deliberately absent."
  [{:keys [tick-id node-id iteration-index generated-code-hash kind target
           arguments child-node-id]}]
  (sha-256
   (researcher-effects-canonical-value (cond-> [tick-id node-id iteration-index generated-code-hash
                             kind target arguments]
                      child-node-id (conj child-node-id)))))

(defn provider-logical-action-identity
  "Stable pre-dispatch identity for a Phase-1 provider request.

   Provider intent cannot depend on the code that the request has not produced
   yet.  Its content is the canonical module, inputs, stable transport options,
   and provider/model target.  Deadline and local idempotency metadata are not
   request content and are excluded so replay time cannot change the identity."
  [{:keys [tick-id node-id iteration-index provider model module inputs options]}]
  (let [stable-options (dissoc (or options {}) :timeout-ms :orc/idempotency-key)]
    (sha-256
     (researcher-effects-canonical-value [tick-id node-id iteration-index :provider provider model
                       module inputs stable-options]))))

(defn generated-code-hash
  "Stable digest of the exact generated source that invoked an inline effect."
  [source]
  (sha-256 (researcher-effects-canonical-value source)))

(defn attempt-identity
  "Stable identity for one physical dispatch of a logical action."
  [logical-action-identity ownership-epoch attempt-ordinal]
  (when-not (and (integer? attempt-ordinal) (not (neg? attempt-ordinal)))
    (throw (ex-info "Effect attempt ordinal must be a non-negative integer"
                    {:attempt-ordinal attempt-ordinal})))
  (sha-256
   (researcher-effects-canonical-value [logical-action-identity ownership-epoch attempt-ordinal])))

(defn frontier-cas
  "CAS that advances one campaign frontier from the immediately prior epoch.

   Epoch 1 is the only legal initial claim.  Callers derive later candidates
   from the durable frontier they are processing, never from this reducer."
  [campaign-tag candidate-epoch]
  {:tags #{campaign-tag}
   :types #{frontier-event-type}
   :predicate-fn
   (fn [events]
     (let [latest-epoch
           (reduce (fn [latest event]
                     (max latest (:ownership-epoch event)))
                   0
                   events)]
       (= candidate-epoch (inc latest-epoch))))})

(defn claim-cas
  "CAS that fences a claim on the campaign's current frontier epoch.

   Rejects both a duplicate logical action in the same epoch and reuse of a
   physical attempt identity."
  [campaign-tag candidate-epoch logical-action-identity attempt-identity]
  {:tags #{campaign-tag}
   :types #{frontier-event-type claim-event-type}
   :predicate-fn
   (fn [events]
     (let [{:keys [frontier-epoch duplicate?]}
           (reduce
            (fn [state event]
              (case (:event/type event)
                :rlm/researcher-frontier-claimed
                (update state :frontier-epoch max (:ownership-epoch event))

                :rlm/researcher-effect-claimed
                (if (or (= attempt-identity (:attempt-identity event))
                        (and (= logical-action-identity
                                (:logical-action-identity event))
                             (= candidate-epoch (:ownership-epoch event))))
                  (assoc state :duplicate? true)
                  state)

                state))
            {:frontier-epoch 0 :duplicate? false}
            events)]
       (and (= candidate-epoch frontier-epoch)
            (not duplicate?))))})

(defn completion-cas
  "CAS that resolves an existing claim only while its epoch still owns the
   campaign frontier, and only once."
  [campaign-tag candidate-epoch logical-action-identity attempt-identity]
  {:tags #{campaign-tag}
   :types #{frontier-event-type claim-event-type completion-event-type
            indeterminate-event-type}
   :predicate-fn
   (fn [events]
     (let [{:keys [frontier-epoch matching-claim? resolved?]}
           (reduce
            (fn [state event]
              (case (:event/type event)
                :rlm/researcher-frontier-claimed
                (update state :frontier-epoch max (:ownership-epoch event))

                :rlm/researcher-effect-claimed
                (if (and (= logical-action-identity
                            (:logical-action-identity event))
                         (= attempt-identity (:attempt-identity event))
                         (= candidate-epoch (:ownership-epoch event)))
                  (assoc state :matching-claim? true)
                  state)

                :rlm/researcher-effect-completed
                (if (= attempt-identity (:attempt-identity event))
                  (assoc state :resolved? true)
                  state)

                :rlm/researcher-effect-indeterminate
                (if (= attempt-identity (:attempt-identity event))
                  (assoc state :resolved? true)
                  state)

                state))
            {:frontier-epoch 0 :matching-claim? false :resolved? false}
            events)]
       (and (= candidate-epoch frontier-epoch)
            matching-claim?
            (not resolved?))))})

(defn indeterminate-cas
  "CAS that settles one unresolved claim after a newer frontier takes over.

   The candidate epoch names the resolving owner, while the matched claim keeps
   the older epoch that authorised the uncertain effect.  Current-epoch claims
   and claims with either terminal resolution are rejected."
  [campaign-tag candidate-epoch logical-action-identity attempt-identity]
  {:tags #{campaign-tag}
   :types #{frontier-event-type claim-event-type completion-event-type
            indeterminate-event-type}
   :predicate-fn
   (fn [events]
     (let [{:keys [frontier-epoch claim-epoch resolved?]}
           (reduce
            (fn [state event]
              (case (:event/type event)
                :rlm/researcher-frontier-claimed
                (update state :frontier-epoch max (:ownership-epoch event))

                :rlm/researcher-effect-claimed
                (if (and (= logical-action-identity
                            (:logical-action-identity event))
                         (= attempt-identity (:attempt-identity event)))
                  (assoc state :claim-epoch (:ownership-epoch event))
                  state)

                :rlm/researcher-effect-completed
                (if (= attempt-identity (:attempt-identity event))
                  (assoc state :resolved? true)
                  state)

                :rlm/researcher-effect-indeterminate
                (if (= attempt-identity (:attempt-identity event))
                  (assoc state :resolved? true)
                  state)

                state))
            {:frontier-epoch 0 :claim-epoch nil :resolved? false}
            events)]
       (and (= candidate-epoch frontier-epoch)
            (some? claim-epoch)
            (< claim-epoch candidate-epoch)
            (not resolved?))))})

;; --- Ai model receipts ---

(defn- request-identity [receipt]
  (dissoc receipt :phase :outcome :provider-outcome))

(defn- execution-identity [receipt]
  (select-keys (:observation-owner receipt)
    [:node-start-id :node-attempt :node-id :exec-context]))

(defn select-response
  "Select an exact, uniquely accepted native response inside the callbacks'
   already-scoped operation/configuration. validate-response is the AI executor's
   original declared-write validator. Predictor success alone is not acceptance.
   Accounting describes retained runtime evidence, never inferred provider usage."
  [receipts validate-response]
  (let [requests (vals (group-by :request-id receipts))
        shared-invocations (vals (group-by #(get-in % [:observation-owner :invocation-id])
                                    (filter #(get-in % [:observation-owner :invocation-id]) receipts)))
        contradictory? (or (some #(> (count (set (map :request-id %))) 1) shared-invocations)
                           (some #(or (> (count (set (map request-identity %))) 1)
                                  (> (count (set (filter (comp #{:completed} :phase) %))) 1)) requests))
        completed (mapv #(first (filter (comp #{:completed} :phase) %)) requests)
        completed (filterv some? completed)
        accepted (filterv
                   (fn [receipt]
                     (let [prediction (:outcome receipt)
                           validation (validate-response prediction)]
                       (and (= :success (:status prediction))
                            (not (:error prediction))
                            (or (nil? (:provider-outcome receipt))
                                (= :success (get-in receipt [:provider-outcome :status])))
                            (empty? (:unparseable-keys validation))
                            (= :success (get-in validation [:schema-result :status])))))
                   completed)]
    (cond
      (or contradictory? (> (count accepted) 1)
          (some #(not (and (some? (:request-id %)) (nat-int? (:attempt-ordinal %)))) receipts))
      {:failure {:status :failure :retryable? false :failure-kind :model-plan-incompatible
                 :error "Model plan cannot be resumed [ambiguous-physical-response]"}}

      (= 1 (count accepted))
      (let [selected (first accepted)
            groups (vals (group-by execution-identity (map first requests)))
            missing (reduce + 0
                      (for [group groups
                            :let [ordinals (set (map :attempt-ordinal group))
                                  last-ordinal (apply max ordinals)]]
                        (- (inc last-ordinal) (count ordinals))))
            incomplete (- (count requests) (count completed))
            usage (reduce (fn [acc receipt]
                            (when (or acc (get-in receipt [:outcome :usage]))
                              (merge-with + (or acc {}) (or (get-in receipt [:outcome :usage]) {}))))
                          nil completed)
            partial? (or (pos? missing) (pos? incomplete)
                         (some #(not (map? (get-in % [:outcome :usage]))) completed))]
        {:receipt selected :completed completed :usage usage
         :accounting (cond-> {:source :runtime-receipts :coverage (if partial? :partial :complete)
                              :completed-receipts (count completed) :missing-earlier-receipts missing}
                       (pos? incomplete) (assoc :incomplete-receipts incomplete))})

      :else nil)))

;; --- Decision ---

(defn schema-options
  "Offered options of a STATIC answer-key schema, or nil when the schema is
   neither boolean nor a finite enum.
   => {:kind :boolean|:enum :ids [...] :descriptions {id description}}"
  [schema]
  (when schema
    (let [s (try (m/schema schema) (catch Exception _ nil))]
      (case (some-> s m/type)
        :boolean {:kind :boolean :ids [true false] :descriptions {}}
        :enum (when (seq (m/children s))
                {:kind :enum
                 :ids (vec (m/children s))
                 :descriptions (or (:descriptions (m/properties s)) {})})
        nil))))

(defn declaration-error
  "Build-time rejection message for a decision node, or nil when it is
   well-formed. `node` is the workflow-DSL leaf map; `schema-of` maps a
   blackboard key to its (effective) schema."
  [node schema-of]
  (let [{:keys [name writes reads options-from bands-from min-confidence abstain instruction]} node
        problem (fn [msg] (str "Decision '" name "' " msg))]
    (cond
      (not (and (string? instruction) (not (str/blank? instruction))))
      (problem "requires an :instruction")

      (not= 1 (count writes))
      (problem (str "must write exactly one answer key, got " (pr-str writes)))

      (and bands-from options-from)
      (problem ":bands-from and :options-from are mutually exclusive: a decision offers either unordered options or ordered bands")

      (and bands-from (not (some #{bands-from} reads)))
      (problem (str ":bands-from " (pr-str bands-from) " must also be declared in :reads"))

      (and bands-from (or (some? min-confidence) (some? abstain)))
      (problem ":min-confidence and :abstain are not supported on a banded decision (:bands-from)")

      (and bands-from (let [answer (schema-of (first writes))]
                        (not (and answer
                                  (= :int (some-> (try (m/schema answer) (catch Exception _ nil))
                                                  m/type))))))
      (problem (str "answer key " (pr-str (first writes))
                    " must be an integer schema when :bands-from supplies the bands"))

      bands-from nil

      (and options-from (not (some #{options-from} reads)))
      (problem (str ":options-from " (pr-str options-from) " must also be declared in :reads"))

      (and (some? min-confidence) (nil? abstain))
      (problem ":min-confidence requires :abstain (the option written when the floor is not met)")

      (and (some? abstain) (nil? min-confidence))
      (problem ":abstain is only meaningful with :min-confidence")

      (and (some? min-confidence) (not (<= 0.0 min-confidence 1.0)))
      (problem ":min-confidence must be between 0 and 1")

      options-from
      (let [answer (schema-of (first writes))]
        (when-not (and answer (#{:string :enum} (m/type (m/schema answer))))
          (problem (str "answer key " (pr-str (first writes))
                        " must be a :string (or enum) schema when :options-from supplies the options"))))

      :else
      (let [answer (schema-of (first writes))
            offered (schema-options answer)]
        (cond
          (nil? offered)
          (problem (str "answer key " (pr-str (first writes))
                        " must have a :boolean or finite [:enum ...] schema (or supply options "
                        "with :options-from); got " (pr-str answer)))

          (and (some? abstain) (not (some #(= abstain %) (:ids offered))))
          (problem (str ":abstain " (pr-str abstain) " must be one of the offered options "
                        (pr-str (:ids offered)))))))))

(defn runtime-options
  "Normalise a run-time options value into [{:id :description}], or nil when
   it is not a non-empty sequence of identified options."
  [value]
  (when (and (sequential? value) (seq value)
             (every? #(and (map? %) (some? (:id %))) value))
    (mapv (fn [o] {:id (:id o) :description (:description o)}) value)))

(defn render-options
  "Provider-facing text offering every option id with its description."
  [kind options]
  (if (= :boolean kind)
    "Answer with exactly one of: true, false."
    (str "Choose exactly one of the following options and answer with its id verbatim:\n"
         (str/join "\n"
                   (for [{:keys [id description]} options]
                     (str "- " id (when-not (str/blank? description)
                                    (str ": " description))))))))

(defn rubric
  "Validate a run-time rubric value for a banded decision. A rubric is a map
   `{:bands {1 \"description\" 2 \"description\" ...} :criterion str? :stance str?}`
   whose bands are integer-keyed, contiguous, at least two levels, every
   description a non-blank string. Never guesses: returns
   {:rubric {:bands [[level description] ...] :criterion c :stance s}} when
   valid, or {:error message}."
  [value]
  (let [bands (when (map? value) (:bands value))
        levels (when (map? bands) (sort (keys bands)))]
    (cond
      (not (map? value))
      {:error (str "rubric must be a map with :bands, got " (pr-str value))}

      (not (and (map? bands) (seq bands)))
      {:error "rubric :bands must be a non-empty map of integer level to description"}

      (not (every? integer? levels))
      {:error (str "rubric band levels must be integers, got " (pr-str levels))}

      (< (count levels) 2)
      {:error "rubric must offer at least two bands"}

      (not= levels (range (first levels) (+ (first levels) (count levels))))
      {:error (str "rubric band levels must be contiguous, got " (pr-str levels))}

      (some #(not (and (string? (get bands %)) (not (str/blank? (get bands %))))) levels)
      {:error (str "every rubric band needs a non-blank description; missing or blank for levels "
                   (pr-str (filterv #(not (and (string? (get bands %)) (not (str/blank? (get bands %)))))
                                    levels)))}

      (and (some? (:criterion value)) (not (string? (:criterion value))))
      {:error "rubric :criterion must be a string"}

      (and (some? (:stance value)) (not (string? (:stance value))))
      {:error "rubric :stance must be a string"}

      :else
      {:rubric (cond-> {:bands (mapv (fn [l] [l (get bands l)]) levels)}
                 (not (str/blank? (:criterion value))) (assoc :criterion (:criterion value))
                 (not (str/blank? (:stance value))) (assoc :stance (:stance value)))})))

(defn render-bands
  "Provider-facing text offering the rubric: criterion, stance, then every band
   as \"n: description\" in order."
  [{:keys [bands criterion stance]}]
  (str/join "\n"
            (concat
             (when criterion [(str "Criterion: " criterion)])
             (when stance [(str "Stance: " stance)])
             ["Choose exactly the one band that applies and answer with its number verbatim:"]
             (for [[level description] bands]
               (str level ": " description)))))

(defn judge
  "Judge a provider answer against the set actually offered, applying the
   confidence floor. Returns
   {:valid? bool :value v :record {...}}; `:value` is what is written when valid."
  [{:keys [offered abstain min-confidence]} answer confidence]
  (let [in-set? (and (some? answer) (some #(= % answer) offered))
        base {:offered (vec offered)}]
    (cond
      (not in-set?)
      {:valid? false
       :record (cond-> (assoc base :abstained? false)
                 (some? answer) (assoc :answer answer))}

      (and (some? min-confidence)
           (or (nil? confidence) (< confidence min-confidence)))
      {:valid? true
       :value abstain
       :record (assoc base :answer answer :abstained? true :set-aside answer)}

      :else
      {:valid? true
       :value answer
       :record (assoc base :answer answer :abstained? false)})))

;; --- Iteration evidence ---

(def reasoning-max-chars
  "Measured maximum reasoning length in the checked-in live RR-5 evidence bank."
  438)

(def error-excerpt-max-chars
  "Measured maximum error length in the checked-in live RR-5 evidence bank."
  175)

(defn bound-text
  "Keep at most max-chars from the exact prefix of one durable evidence string."
  [text max-chars]
  (when (some? text)
    (let [text (str text)]
      (subs text 0 (min (count text) max-chars)))))

;; --- Judge definition ---

(def all-purposes #{:monitoring :learning})

(def max-alert-window
  "The largest trailing window an alert may watch: the number of recent outcomes
   a node version's performance keeps in order."
  500)

(defn alert-error
  "nil when `alert` is a valid opt-in performance alert, else a message. An alert
   watches the last `:window` outcomes of a node version and signals when their
   mean score falls below `:below`, provided at least `:min-coverage` of them were
   scored."
  [alert]
  (let [fraction? #(and (number? %) (< 0.0 % 1.000000001))]
    (cond
      (not (map? alert))
      (str "judge :alert must be a map {:below :window :min-coverage}, got " (pr-str alert))

      (not (fraction? (:below alert)))
      (str "judge alert :below must be a number above 0 and at most 1, got " (pr-str (:below alert)))

      (not (and (pos-int? (:window alert)) (<= (:window alert) max-alert-window)))
      (str "judge alert :window must be a positive integer of at most " max-alert-window
           ", got " (pr-str (:window alert)))

      (not (fraction? (:min-coverage alert)))
      (str "judge alert :min-coverage must be a number above 0 and at most 1, got "
           (pr-str (:min-coverage alert))))))

(defn rubric-error
  "nil when `rubric` is a valid judge rubric, else a message. Band rules are the
   banded decision's own (`decision/rubric`); a judge rubric additionally needs
   a non-blank criterion and a feedback mode."
  [rubric]
  (cond
    (not (map? rubric))
    (str "judge rubric must be a map, got " (pr-str rubric))

    (or (not (string? (:criterion rubric))) (str/blank? (:criterion rubric)))
    "judge rubric :criterion must be a non-blank string"

    (not (contains? #{:required :none} (:feedback rubric)))
    (str "judge rubric :feedback must be :required or :none, got " (pr-str (:feedback rubric)))

    :else (:error (ai.obney.orc.orc-service.core.logic/rubric rubric))))

(defn effective-purposes
  "The purposes a declared config serves. Absent, they default for compatibility
   (every declared judge has fed the learning loops) to monitoring + learning,
   except a rubric whose feedback is :none, which can only monitor."
  [{:keys [purposes rubric]}]
  (cond
    (some? purposes) (set purposes)
    (= :none (:feedback rubric)) #{:monitoring}
    :else all-purposes))

(defn config-error
  "nil when the judge config is a valid definition, else a message."
  [{:keys [purposes rubric timeout-ms alert] :as config}]
  (cond
    (and (contains? config :assess-failures?) (not (boolean? (:assess-failures? config))))
    (str "judge :assess-failures? must be a boolean, got " (pr-str (:assess-failures? config)))

    (and (= :custom (:type config)) (not (:sheet-id config)))
    "Custom judge type requires :sheet-id"

    (and (some? purposes)
         (not (and (or (set? purposes) (sequential? purposes))
                   (seq purposes)
                   (every? all-purposes purposes))))
    (str "judge :purposes must be a non-empty subset of " (pr-str all-purposes)
         ", got " (pr-str purposes))

    (and (some? rubric) (rubric-error rubric))
    (rubric-error rubric)

    (and (contains? config :alert) (alert-error alert))
    (alert-error alert)

    (and (some? timeout-ms) (not (pos-int? timeout-ms)))
    (str "judge :timeout-ms must be a positive integer, got " (pr-str timeout-ms))

    (and (contains? (effective-purposes config) :learning)
         (some? rubric)
         (not= :required (:feedback rubric)))
    "a learning judge must require feedback: :learning purpose is incompatible with a rubric :feedback :none (Living Descriptions, harvest and GEPA need real feedback)"))

;; --- Model fallback ---

(def policies #{:temporary-transport :all-transport})

(defn temporary-transport? [{:keys [type http-status]}]
  (boolean
    (if (nil? type)
      (and (integer? http-status) (or (= 408 http-status) (<= 500 http-status 599)))
    (case type
      :litellm/rate-limit (or (nil? http-status) (= 429 http-status))
      :litellm/timeout (or (nil? http-status) (#{408 504} http-status))
      :litellm/connection-error (nil? http-status)
      (:litellm/server-error :litellm/provider-error)
      (and (integer? http-status) (<= 500 http-status 599))
      false))))

(defn admission
  "Classify the actual final candidate completion, never error prose."
  [policy completion]
  (let [transport? (and (= :failure (:status completion))
                        (= :transport-failure (:failure-kind completion)))
        allowed? (and transport?
                      (case policy
                        :all-transport true
                        :temporary-transport (temporary-transport? (get-in completion [:provider-evidence :transport]))
                        false))]
    {:allowed? (boolean allowed?)
     :reason (if allowed? :eligible-transport :policy-refused)}))

(def candidate-option-keys
  #{:temperature :max-tokens :top-p :top-k :frequency-penalty :presence-penalty
    :seed :reasoning :stop :reasoning-effort :thinking :max-retries :timeout-ms})

(def reserved-option-keys
  #{:model :tick-id :sheet-id :node-id :node-start-id :context :stream :exec-context
    :execution-deadline-ms :llm-call-budget :llm-budget-root-tick-id :llm-budget-root-sheet-id
    :provider-reservation-context :node-attempt :max-node-attempts :provider-attempt-ordinal
    :logical-action-identity :ownership-epoch :budget :budget-tick-id :budget-sheet-id})

(defn invalid-options? [options]
  (or (not (map? options))
      (some #(or (not (keyword? %)) (namespace %) (reserved-option-keys %)
                 (str/ends-with? (name %) "!")) (keys options))
      (some fn? (tree-seq coll? seq options))))

(defn valid-retry? [retry]
  (and (map? retry) (every? #{:max-attempts :backoff-ms} (keys retry))
       (pos-int? (:max-attempts retry)) (vector? (:backoff-ms retry))
       (every? nat-int? (:backoff-ms retry))))

(defn valid-generation? [o]
  (and (or (not (contains? o :retry-delay-ms))
           (nat-int? (:retry-delay-ms o))
           (and (vector? (:retry-delay-ms o)) (seq (:retry-delay-ms o))
                (every? nat-int? (:retry-delay-ms o))))
       (or (not (contains? o :max-retries)) (nat-int? (:max-retries o)))
       (every? #(or (not (contains? o %)) (pos-int? (get o %))) [:max-tokens :timeout-ms :top-k])
       (every? #(or (not (contains? o %)) (number? (get o %)))
               [:temperature :top-p :frequency-penalty :presence-penalty])
       (or (not (contains? o :seed)) (integer? (:seed o)))))

(defn candidate-plan
  "Resolve an ordered request plan. Omitted backup providers always inherit the
   primary provider, not the previous candidate. Shared task options are retained."
  [node provider options]
  (let [provider (or (:provider node) provider)
        options (merge {:max-retries 0} options)
        primary {:provider provider :model (:model node) :options options}]
    (into [primary]
          (map (fn [candidate]
                 {:provider (or (:provider candidate) provider)
                  :model (:model candidate)
                  :options (merge options (:options candidate))}))
          (:fallback-models node))))

(defn durable-outcome [outcome]
  (cond-> (select-keys outcome [:status :outputs :usage :model :resolved-model :error
                                :failure-kind :provider-evidence :decisions :retryable?
                                :model-fallback :timeout-kind :output-exposed?])
    (:result outcome) (assoc :result (select-keys (:result outcome)
                                  [:outputs :code :usage :model :decisions :provider-evidence
                                   :provider-observations]))))

(defn run-candidates
  "Execute candidates within one logical call. Durable claims precede dispatch;
   outcomes precede advancement. A completed receipt replays without provider work."
  [{:keys [candidates policy start-index invoke! record! select! claim! completed-outcome output-exposed? initial-usage prior-attempts]
    :or {policy :temporary-transport start-index 0}}]
  (when-not (and (vector? candidates) (seq candidates)
                 (nat-int? start-index) (< start-index (count candidates))
                 (policies policy) (fn? invoke!) (fn? record!))
    (throw (ex-info "Invalid candidate execution contract" {:type :invalid-model-fallback})))
  (if completed-outcome
    (let [index (get-in completed-outcome [:model-fallback :candidate-index])
          selected (when (and (= :success (:status completed-outcome)) select! (some? index))
                     (select! index (nth candidates index)))]
      (if (:cognitect.anomalies/category selected)
        {:status :failure :retryable? false :error "Model selection replay could not be persisted"}
        completed-outcome))
    (if output-exposed?
      {:status :failure :retryable? false :output-exposed? true
       :error "Interrupted model stream already exposed output"}
    (loop [index start-index usage initial-usage attempts (vec prior-attempts)]
      (let [candidate (nth candidates index)
            claimed (when claim! (claim! index candidate))]
        (if (:cognitect.anomalies/category claimed)
          (if (:model-call-in-flight? claimed)
            {:status :running :model-call-in-flight? true :retryable? false}
            {:status :failure :retryable? false :usage usage :error "Model candidate claim was refused"})
          (let [outcome (invoke! index candidate)
                usage (when (or usage (:usage outcome))
                        (merge-with + (or usage {}) (or (:usage outcome) {})))
                eligible? (:allowed? (admission policy outcome))
                advance? (and eligible? (< (inc index) (count candidates))
                              (not (:output-exposed? outcome)))
                reason (cond (= :success (:status outcome)) :selected
                             (:output-exposed? outcome) :output-exposed
                             advance? :eligible-transport
                             eligible? :candidates-exhausted
                             :else :policy-refused)
                entry {:candidate-index index :provider (:provider candidate)
                       :requested-model (:model candidate) :status (:status outcome)
                       :failure-kind (:failure-kind outcome)
                       :provider-evidence (:provider-evidence outcome)
                       :reason reason :advanced? (boolean advance?)}
                attempts (conj attempts entry)
                result (assoc outcome :usage usage :retryable? false
                              :model-fallback {:policy policy :reason reason
                                               :candidate-index index
                                               :requested-model (:model candidate)
                                               :provider (:provider candidate) :attempts attempts})
                result (if-let [accounting (get-in outcome [:model-fallback :recovery-accounting])]
                         (assoc-in result [:model-fallback :recovery-accounting] accounting)
                         result)
                recorded (record! (assoc entry :outcome (durable-outcome result)))]
            (if (:cognitect.anomalies/category recorded)
              {:status :failure :retryable? false :usage usage
               :error "Model fallback outcome could not be persisted"}
              (if advance?
                (recur (inc index) usage attempts)
                (let [selected (when (and (= :success (:status outcome)) select!)
                                 (select! index candidate))]
                  (if (:cognitect.anomalies/category selected)
                    {:status :failure :retryable? false :usage usage
                     :error "Model fallback selection could not be persisted"}
                    result)))))))))))

(defn validate-config!
  "Validate node routing and inherited subcall configuration before building."
  [node]
  (when (contains? node :model-fallback-policy)
    (throw (ex-info "Composite model fallback is obsolete; configure fallback-models on a model node"
                    {:type :invalid-model-fallback :node (:name node)})))
  (when (or (contains? node :provider) (contains? node :fallback-models)
            (contains? node :fallback-policy))
    (let [kind (or (:node-type node) (:type node))]
      (when-not
        (and (or (nil? kind) (#{:llm-condition :repl-researcher} kind)
                 (and (= :leaf kind) (#{:ai :decision} (:executor node))))
             (or (not (contains? node :fallback-models))
                 (and (not (invalid-options? (or (:options node) {})))
                      (valid-generation? (or (:options node) {}))))
             (or (not (contains? node :provider)) (keyword? (:provider node)))
             (or (not (contains? node :fallback-policy)) (policies (:fallback-policy node)))
             (or (not (contains? node :fallback-models))
                 (and (vector? (:fallback-models node))
                      (every? (fn [candidate]
                                (and (map? candidate)
                                     (every? #{:model :provider :options} (keys candidate))
                                     (string? (:model candidate))
                                     (not (str/blank? (:model candidate)))
                                     (or (not (contains? candidate :provider))
                                         (keyword? (:provider candidate)))
                                     (or (not (contains? candidate :options))
                                         (and (map? (:options candidate))
                                              (every? candidate-option-keys (keys (:options candidate)))
                                              (valid-generation? (:options candidate))))))
                              (:fallback-models node)))))
        (throw (ex-info "Invalid node model fallback configuration"
                        {:type :invalid-model-fallback :node (:name node)})))))
  (when (map? (:rlm node))
    (let [rlm (:rlm node)
          mapping {:sub-provider :provider :sub-model :model :sub-options :options
                   :sub-fallback-models :fallback-models :sub-fallback-policy :fallback-policy}]
      (validate-config! (reduce-kv (fn [m source target]
                                    (if (contains? rlm source) (assoc m target (get rlm source)) m))
                                  {} mapping))))
  node)

;; --- Observability ---

;; =============================================================================
;; Configuration
;; =============================================================================

(def slow-thresholds-ms
  "Thresholds for slow execution warnings by node type (in milliseconds)."
  {:condition 100
   :leaf 30000
   :llm-condition 30000
   :sequence 60000
   :fallback 60000
   :parallel 120000
   :map-each 300000
   :execution 300000})

;; =============================================================================
;; Error Categorization
;; =============================================================================

(def error-categories
  "Error category definitions for classification."
  {:ai-provider-error "AI provider returned error"
   :ai-timeout "AI call timed out"
   :ai-rate-limit "Rate limited by AI provider"
   :ai-invalid-response "AI returned invalid/nil outputs"
   :code-execution-error "Code executor threw exception"
   :code-resolution-error "Could not resolve code function"
   :validation-error "Input/output validation failed"
   :timeout "Overall execution timed out"
   :tree-structure-error "BT structure issue"
   :blackboard-error "Blackboard key issue"
   :unknown "Uncategorized error"})

(defn categorize-error
  "Categorize an error based on exception or error message.
   Returns a keyword from error-categories."
  [error-or-exception]
  (let [msg (str (if (instance? Exception error-or-exception)
                   (.getMessage ^Exception error-or-exception)
                   error-or-exception))]
    (cond
      (re-find #"(?i)rate.?limit|429|too many requests" msg) :ai-rate-limit
      (re-find #"(?i)timeout|timed out" msg) :ai-timeout
      (re-find #"(?i)could not resolve|function not found|unable to resolve" msg) :code-resolution-error
      (re-find #"(?i)node not found|missing node" msg) :tree-structure-error
      (re-find #"(?i)blackboard key|not declared|key not found" msg) :blackboard-error
      (re-find #"(?i)validation|invalid|schema" msg) :validation-error
      (re-find #"(?i)nil output|empty output" msg) :ai-invalid-response
      :else :unknown)))

;; =============================================================================
;; Active Executions Gauge
;; =============================================================================

(defonce ^:private active-executions (atom 0))

(defn inc-active-executions!
  "Increment the active executions counter. Returns new count."
  []
  (swap! active-executions inc))

(defn dec-active-executions!
  "Decrement the active executions counter. Returns new count."
  []
  (swap! active-executions dec))

(defn get-active-executions
  "Get the current count of active executions."
  []
  @active-executions)

;; =============================================================================
;; Slow Execution Detection
;; =============================================================================

(defn slow?
  "Check if a duration exceeds the slow threshold for a node type."
  [node-type duration-ms]
  (let [threshold (get slow-thresholds-ms node-type (:execution slow-thresholds-ms))]
    (> duration-ms threshold)))

(defn get-threshold
  "Get the slow threshold for a node type."
  [node-type]
  (get slow-thresholds-ms node-type (:execution slow-thresholds-ms)))

;; =============================================================================
;; Logging Helpers - Execution Level
;; =============================================================================

(defn log-execution-started!
  "Log the start of a behavior tree execution."
  [{:keys [sheet-id trace-id input-keys root-node-id]}]
  (inc-active-executions!)
  (u/log :ai.obney.orc.orc-service.core.observability/execution-started
         :sheet-id sheet-id
         :trace-id trace-id
         :root-node-id root-node-id
         :input-keys input-keys
         :active-executions (get-active-executions)))

(defn log-execution-completed!
  "Log the completion of a behavior tree execution."
  [{:keys [sheet-id trace-id status duration-ms output-keys error]}]
  (dec-active-executions!)
  (u/log :ai.obney.orc.orc-service.core.observability/execution-completed
         :sheet-id sheet-id
         :trace-id trace-id
         :status status
         :duration-ms duration-ms
         :output-keys output-keys
         :active-executions (get-active-executions)
         :error (when error (str error))))

(defn log-execution-timeout!
  "Log a timeout of a behavior tree execution."
  [{:keys [sheet-id trace-id duration-ms timeout-ms]}]
  (dec-active-executions!)
  (u/log :ai.obney.orc.orc-service.core.observability/execution-timeout
         :level :warn
         :sheet-id sheet-id
         :trace-id trace-id
         :duration-ms duration-ms
         :timeout-ms timeout-ms))

;; =============================================================================
;; Logging Helpers - Node Level
;; =============================================================================

(defn log-node-started!
  "Log the start of a node execution."
  [{:keys [node-id node-name node-type trace-id]}]
  (u/log :ai.obney.orc.orc-service.core.observability/node-started
         :level :debug
         :node-id node-id
         :node-name node-name
         :node-type node-type
         :trace-id trace-id))

(defn log-node-completed!
  "Log the completion of a node execution. Includes slow detection."
  [{:keys [node-id node-name node-type status duration-ms trace-id error]}]
  (let [is-slow (slow? node-type duration-ms)]
    (u/log :ai.obney.orc.orc-service.core.observability/node-completed
           :level (cond
                    (= status :failure) :warn
                    is-slow :warn
                    :else :debug)
           :node-id node-id
           :node-name node-name
           :node-type node-type
           :status status
           :duration-ms duration-ms
           :trace-id trace-id
           :error (when error (str error))
           :slow is-slow)
    (when is-slow
      (u/log :ai.obney.orc.orc-service.core.observability/node-slow
             :level :warn
             :node-id node-id
             :node-name node-name
             :node-type node-type
             :duration-ms duration-ms
             :threshold-ms (get-threshold node-type)
             :exceeded-by-ms (- duration-ms (get-threshold node-type))
             :trace-id trace-id))))

;; =============================================================================
;; Logging Helpers - Error Level
;; =============================================================================

(defn log-error!
  "Log an error with categorization."
  [{:keys [node-id node-name node-type error trace-id]}]
  (let [category (categorize-error error)]
    (u/log :ai.obney.orc.orc-service.core.observability/error
           :level :error
           :node-id node-id
           :node-name node-name
           :node-type node-type
           :error-category category
           :error-message (str error)
           :trace-id trace-id)))

;; =============================================================================
;; Logging Helpers - AI Execution
;; =============================================================================

(defn log-ai-execution!
  "Log an AI node execution with token usage."
  [{:keys [node-id node-name model executor duration-ms status usage trace-id error]}]
  (u/log :ai.obney.orc.orc-service.core.observability/ai-execution
         :level (if (= status :failure) :warn :debug)
         :node-id node-id
         :node-name node-name
         :model model
         :executor executor
         :duration-ms duration-ms
         :status status
         :prompt-tokens (:prompt-tokens usage)
         :completion-tokens (:completion-tokens usage)
         :total-tokens (:total-tokens usage)
         :trace-id trace-id
         :error (when error (str error))))

(defn log-retry!
  "Log a retry attempt."
  [{:keys [node-id node-name attempt max-attempts reason trace-id]}]
  (u/log :ai.obney.orc.orc-service.core.observability/retry-attempt
         :level :info
         :node-id node-id
         :node-name node-name
         :attempt attempt
         :max-attempts max-attempts
         :reason reason
         :trace-id trace-id))

(defn log-unparseable-output!
  "Log an LLM response that parsed to nil for declared writes.

   Carries the FULL raw response (verbatim, no truncation) so the parse
   failure is diagnosable from the trace alone — without this the model's
   actual output is unrecoverable once the node completes."
  [{:keys [node-id node-name model nil-keys raw-length raw-response trace-id]}]
  (u/log :ai.obney.orc.orc-service.core.observability/unparseable-output
         :level :warn
         :node-id node-id
         :node-name node-name
         :model model
         :nil-keys nil-keys
         :raw-length raw-length
         :raw-response raw-response
         :trace-id trace-id))

;; =============================================================================
;; Logging Helpers - Composite Nodes
;; =============================================================================

(defn log-parallel-started!
  "Log the start of a parallel node execution."
  [{:keys [node-id node-name child-count trace-id]}]
  (u/log :ai.obney.orc.orc-service.core.observability/parallel-started
         :level :info
         :node-id node-id
         :node-name node-name
         :child-count child-count
         :trace-id trace-id))

(defn log-map-each-started!
  "Log the start of a map-each node execution."
  [{:keys [node-id node-name item-count concurrency trace-id]}]
  (u/log :ai.obney.orc.orc-service.core.observability/map-each-started
         :level :info
         :node-id node-id
         :node-name node-name
         :item-count item-count
         :concurrency concurrency
         :trace-id trace-id))

(defn log-sequence-progress!
  "Log progress through a sequence node."
  [{:keys [node-id node-name child-index total-children trace-id]}]
  (u/log :ai.obney.orc.orc-service.core.observability/sequence-progress
         :level :debug
         :node-id node-id
         :node-name node-name
         :child-index child-index
         :total-children total-children
         :trace-id trace-id))

(defn log-map-each-progress!
  "Log progress through a map-each node."
  [{:keys [node-id node-name item-index total-items trace-id]}]
  (u/log :ai.obney.orc.orc-service.core.observability/map-each-progress
         :level :debug
         :node-id node-id
         :node-name node-name
         :item-index item-index
         :total-items total-items
         :trace-id trace-id))

;; --- Output validation ---

(def ^:private declared-string-key-transformer
  (mt/transformer
   {:decoders
    {:map
     {:compile
      (fn [schema _]
        (let [declared-keys (set (map first (m/children schema)))
              string-keys (into #{} (filter string?) declared-keys)]
          (fn [x]
            (if (map? x)
              (reduce-kv (fn [m k v]
                           (assoc m
                                  (cond
                                    (contains? declared-keys k) k
                                    (and (string? k) (not (contains? string-keys k)))
                                    (keyword k)
                                    (and (keyword? k) (nil? (namespace k))
                                         (contains? string-keys (name k)))
                                    (name k)
                                    :else k)
                                  v))
                         {}
                         x)
              x))))}}}))

(def ^:private durable-scalar-classes
  #{String Boolean Character Short Integer Long Float Double
    java.math.BigInteger java.math.BigDecimal clojure.lang.BigInt clojure.lang.Ratio
    clojure.lang.Keyword clojure.lang.Symbol java.util.UUID java.util.Date
    java.time.Instant java.time.OffsetDateTime java.net.URI
    (class (byte-array 0)) (class (boolean-array 0)) (class (int-array 0))
    (class (long-array 0)) (class (float-array 0)) (class (double-array 0))})

(defn unsupported-durable-type
  "Return the class of the first value that cannot use ORC's built-in event
   and file codecs. Inspect keys as well as values and never realize lazy data."
  [value]
  (or (when-let [metadata (meta value)] (unsupported-durable-type metadata))
      (cond
        (nil? value) nil
        (contains? durable-scalar-classes (class value)) nil
        (and (instance? java.util.regex.Pattern value)
             (zero? (.flags ^java.util.regex.Pattern value))) nil
        (record? value) (.getName (class value))
        (map? value) (reduce-kv (fn [_ k v]
                                  (when-let [bad (or (unsupported-durable-type k)
                                                     (unsupported-durable-type v))]
                                    (reduced bad)))
                                nil value)
        (or (vector? value) (set? value) (list? value))
        (some unsupported-durable-type value)
        :else (.getName (class value)))))

(defn durable-value? [value]
  (nil? (unsupported-durable-type value)))

(defn rejection-evidence [writes]
  (reduce-kv (fn [result key value]
               (assoc result key
                      (if-let [class-name (unsupported-durable-type value)]
                        {:orc.rejection/reason :unsupported-durable-type
                         :orc.rejection/type class-name}
                        value)))
             {} writes))

(defn validate-outputs
  "Decode provider JSON values and normalize object keys according to each
   declared Malli schema, then reject an invalid successful leaf result before
   its writes become canonical. Provider JSON numbers are canonicalized through
   the schema; numeric strings remain invalid. Non-provider leaves receive only
   schema-directed key normalization and retain strict JVM value types."
  ([blackboard result]
   (validate-outputs blackboard result false))
  ([blackboard result provider-output?]
   (if (not= :success (:status result))
     result
     (let [key-transformer declared-string-key-transformer
           rejected-writes (:outputs result)
           normalized (reduce-kv
                       (fn [outputs key value]
                         (assoc outputs key
                                (if-let [schema (when (durable-value? value)
                                                  (get-in blackboard [key :schema]))]
                                  (if provider-output?
                                    (llm/decode-provider-value schema value)
                                    (m/decode schema value key-transformer))
                                  value)))
                       {}
                       (:outputs result))]
       (if-let [[key explanation]
                (some (fn [[key value]]
                        (if-let [unsupported (unsupported-durable-type value)]
                          [key (str "unsupported durable type " unsupported)]
                          (when-let [schema (get-in blackboard [key :schema])]
                            (try
                              (when-not (m/validate schema value)
                                [key (me/humanize (m/explain schema value))])
                              (catch Exception e
                                [key {:invalid-schema (.getMessage e)}])))))
                      normalized)]
         (-> result
             (assoc :status :failure
                    :error (str "Blackboard schema validation failed for "
                                (pr-str key) ": " explanation)
                    :outputs {}
                    :rejected-writes (rejection-evidence rejected-writes)))
         (assoc result :outputs normalized))))))

;; --- Profile ---

(defn profile-value
  "Profile a single value's shape. Pure and deterministic."
  [v]
  (cond
    (string? v)
    {:type :string
     :length (count v)
     :word-count (count (str/split v #"\s+"))
     :line-count (count (str/split-lines v))}

    (sequential? v)
    {:type :vector
     :length (count v)}

    (map? v)
    {:type :map
     :length (count v)}

    :else
    {:type :other
     :length (count (str v))}))

(defn profile-values
  "Profile every value in a {key -> value} map, preserving the keys.
   nil values are dropped — a key with no value has no shape to record."
  [m]
  (reduce-kv (fn [acc k v]
               (if (nil? v) acc (assoc acc k (profile-value v))))
             {}
             (or m {})))

(defn compute-input-profile
  "Given a node's :reads list and a blackboard, build a profile map keyed by
   read key."
  [reads blackboard]
  (reduce (fn [acc k]
            (let [v (get-in blackboard [k :value])]
              (if (nil? v) acc (assoc acc k (profile-value v)))))
          {}
          (or reads [])))

;; --- Researcher mode ---

(defn checkpointed?
  "True when a repl-researcher uses durable checkpoint execution.

   An explicit :checkpointed? value is authoritative in every RLM mode.
   Otherwise recursive RLM researchers checkpoint by default while terminal
   and non-RLM researchers preserve their prior execution mode."
  [node]
  (when (and (= :repl-researcher (:type node))
             (:rlm node))
    (let [rlm-config (if (map? (:rlm node)) (:rlm node) {})]
      (if (contains? rlm-config :checkpointed?)
        (true? (:checkpointed? rlm-config))
        (not= false (:recursive? rlm-config))))))

;; --- Researcher setup diagnostics ---

(defn- humanized? [value]
  (cond
    (or (nil? value) (string? value)) true
    (vector? value) (every? humanized? value)
    (and (map? value) (not (record? value)))
    (every? (fn [[field detail]]
              (and (or (keyword? field) (string? field) (int? field))
                   (humanized? detail))) value)
    :else false))

(defn from-refusal
  "Select only protocol identity and Grain's humanized field explanation.
   The intended public refusal message is retained only when it is a string.
   Raw commands and unrelated handler data are not diagnostics."
  [command-name result]
  (when (and (keyword? command-name)
             (keyword? (:cognitect.anomalies/category result)))
    (cond-> {:command-name command-name
             :category (:cognitect.anomalies/category result)
             :reason :generated-child-setup-rejected}
      (string? (:cognitect.anomalies/message result))
      (assoc :message (:cognitect.anomalies/message result))
      (and (some? (:error/explain result)) (humanized? (:error/explain result)))
      (assoc :error/explain (:error/explain result)))))

(defn from-exception
  "Revalidate the same allowlist when crossing an exception boundary."
  [error]
  (let [diagnostic (:setup-diagnostic (ex-data error))]
    (when (= :generated-child-setup-rejected (:reason diagnostic))
      (from-refusal (:command-name diagnostic)
                    (cond-> {:cognitect.anomalies/category (:category diagnostic)}
                      (string? (:message diagnostic))
                      (assoc :cognitect.anomalies/message (:message diagnostic))
                      (contains? diagnostic :error/explain)
                      (assoc :error/explain (:error/explain diagnostic)))))))

(defn- field-paths [detail path]
  (cond
    (map? detail)
    (mapcat (fn [[field value]] (field-paths value (conj path field)))
            (sort-by (comp pr-str key) detail))
    (vector? detail)
    (if (every? string? detail)
      (when (seq detail) [path])
      (mapcat (fn [[index value]] (field-paths value (conj path index)))
              (map-indexed vector detail)))
    (string? detail) [path]
    :else []))

(defn render
  "Put the affected field before optional details for bounded iteration excerpts.
   Full humanized detail remains separately available as structured evidence."
  [diagnostic]
  (str (when-let [field (first (field-paths (:error/explain diagnostic) []))]
         (str "Setup field " (pr-str field) "; "))
       "command " (:command-name diagnostic)
       "; category " (:category diagnostic)
       "; reason " (:reason diagnostic)
       (when-let [message (:message diagnostic)] (str "\nRefusal: " message))
       (when (contains? diagnostic :error/explain)
         (str "\nSchema explanation: " (pr-str (:error/explain diagnostic))))))

;; --- Rlm fingerprint ---

(def ^:private fn-placeholder
  "Canonical placeholder for :fn values in the normalized tree. Collapses
   inline fn values, legacy [:fn \"<inline-fn>\"] markers, source forms, and
   qualified-symbol-string fn references to the same canonical form."
  :fingerprint/fn)

(def ^:private instruction-placeholder
  "Canonical placeholder for :instruction string values in the normalized
   tree. Instructions are content; the fingerprint is keyed on structure."
  :fingerprint/instruction)

(defn- normalize
  "Walk a tree and replace each :fn / :instruction map-entry value with
   a canonical placeholder so content variations collapse on the same
   hash while structural choices are preserved."
  [tree]
  (walk/postwalk
    (fn [node]
      (cond
        (and (map-entry? node) (= :fn (key node)))
        [:fn fn-placeholder]

        (and (map-entry? node) (= :instruction (key node)))
        [:instruction instruction-placeholder]

        :else node))
    tree))

(defn fingerprint
  "Compute a deterministic fingerprint hash for a tree S-expression.
   Normalizes the tree (collapsing :fn representations to a canonical
   placeholder) before hashing."
  [tree]
  (str (hash (normalize tree))))

;; --- Trace time ---

(defn ->instant [value]
  (cond
    (nil? value) nil
    (instance? Instant value) value
    (instance? OffsetDateTime value) (.toInstant ^OffsetDateTime value)
    (instance? ZonedDateTime value) (.toInstant ^ZonedDateTime value)
    (string? value) (try
                      (Instant/from (.parse DateTimeFormatter/ISO_DATE_TIME ^String value))
                      (catch Exception _ nil))
    :else nil))

(defn canonical-string [value]
  (when value
    (if-let [instant (->instant value)] (str instant) (str value))))

(defn compare-timestamps [left right]
  (let [left-instant (->instant left)
        right-instant (->instant right)]
    (if (and left-instant right-instant)
      (compare left-instant right-instant)
      (compare (str left) (str right)))))

(defn elapsed-ms [started-at completed-at]
  (when-let [started (->instant started-at)]
    (when-let [completed (->instant completed-at)]
      (max 0 (- (.toEpochMilli completed) (.toEpochMilli started))))))

;; --- Tracing ---

(def ^:private max-output-bytes (* 3 1024 1024))

;; =============================================================================
;; Trace Event Builders
;; =============================================================================

(defn- timestamp-str []
  (str (time/now)))

(defn- millis->iso
  "Convert epoch milliseconds to ISO 8601 timestamp string."
  [epoch-ms]
  (-> (Instant/ofEpochMilli epoch-ms)
      (.atZone (ZoneId/systemDefault))
      (.format DateTimeFormatter/ISO_OFFSET_DATE_TIME)))

(defn trace-start-event
  "Create a trace-create event for starting a workflow execution."
  [trace-id sheet-name inputs]
  {:id (random-uuid)
   :timestamp (timestamp-str)
   :type "trace-create"
   :body {:id (str trace-id)
          :name (or sheet-name "Workflow Execution")
          :startTime (timestamp-str)
          :input inputs
          :metadata {:type "behavior-tree"}}})

(defn- limit-output-size
  "Truncate output if it exceeds max-size bytes when serialized.
   Returns truncation summary if too large, otherwise returns original output."
  [outputs max-size]
  (try
    (let [json-str (json/generate-string outputs)
          json-size (count json-str)]
      (if (> json-size max-size)
        {:_truncated true
         :original-size json-size
         :message "Output truncated due to size. See individual node traces for details."}
        outputs))
    (catch Exception _
      {:_error "Failed to serialize output"
       :message "See individual node traces for details."})))

(defn trace-end-event
  "Create a trace-create event for completing a workflow execution.
   Large outputs are truncated to stay under Langfuse's payload size limit.
   Includes trace-name to preserve it (Langfuse updates can clear missing fields)."
  [trace-id trace-name status outputs duration-ms error]
  {:id (random-uuid)
   :timestamp (timestamp-str)
   :type "trace-create"
   :body (cond-> {:id (str trace-id)
                  :name (or trace-name "Workflow Execution")
                  :endTime (timestamp-str)
                  :output (limit-output-size outputs max-output-bytes)
                  :metadata {:status (name status)
                             :duration-ms duration-ms}}
           error (assoc-in [:metadata :error] error))})

(defn span-event
  "Create a span-create event for a node execution.
   Uses the passed observation-id for unique identification per execution."
  [trace-id node-id node-name node-type start-time end-time inputs outputs status error
   & {:keys [observation-id parent-observation-id]}]
  {:id (random-uuid)
   :timestamp (timestamp-str)
   :type "span-create"
   :body (cond-> {:id observation-id
                  :traceId (str trace-id)
                  :name (or node-name (str node-type " node"))
                  :startTime (millis->iso start-time)
                  :endTime (millis->iso end-time)
                  :input inputs
                  :output outputs
                  :metadata {:node-id (str node-id)
                             :node-type (name node-type)
                             :status (name status)}}
           parent-observation-id (assoc :parentObservationId parent-observation-id)
           error (assoc-in [:metadata :error] error))})

(defn generation-event
  "Create a generation-create event for an AI node execution.
   Uses the passed observation-id for unique identification per execution.

   Langfuse usage format:
   - :promptTokens - Number of tokens in the prompt
   - :completionTokens - Number of tokens in the completion
   - :totalTokens - Total tokens used"
  [trace-id node-id node-name model start-time end-time inputs outputs status error
   & {:keys [observation-id parent-observation-id usage]}]
  (let [
        langfuse-usage (when usage
                         (let [prompt (:prompt-tokens usage)
                               completion (:completion-tokens usage)
                               total (:total-tokens usage)]
                           (cond-> {}
                             prompt (assoc :promptTokens prompt)
                             completion (assoc :completionTokens completion)
                             total (assoc :totalTokens total))))]
    {:id (random-uuid)
     :timestamp (timestamp-str)
     :type "generation-create"
     :body (cond-> {:id observation-id
                    :traceId (str trace-id)
                    :name (or node-name "AI Generation")
                    :startTime (millis->iso start-time)
                    :endTime (millis->iso end-time)
                    :input inputs
                    :output outputs
                    :model model
                    :metadata {:node-id (str node-id)
                               :status (name status)}}
             parent-observation-id (assoc :parentObservationId parent-observation-id)
             (seq langfuse-usage) (assoc :usage langfuse-usage)
             error (assoc-in [:metadata :error] error))}))

;; =============================================================================
;; Trace Context
;; =============================================================================

(defn create-trace-context
  "Create a new trace context for a workflow execution.

   Returns a map with:
   - :trace-id - UUID for the trace
   - :langfuse-client - Client for sending events (may be nil)
   - :events - Atom collecting events to send
   - :enabled? - Whether tracing is enabled
   - :trace-name-atom - Atom to store trace name for use in end event"
  [langfuse-client]
  {:trace-id (random-uuid)
   :langfuse-client langfuse-client
   :events (atom [])
   :trace-name-atom (atom nil)
   :enabled? (and langfuse-client
                  (:public-key langfuse-client)
                  (:secret-key langfuse-client))})

(defn record-event!
  "Record an event and send it immediately to Langfuse.
   Events are streamed as they occur to avoid large batch payloads."
  [trace-ctx event]
  (when (:enabled? trace-ctx)
    (swap! (:events trace-ctx) conj event)
    (langfuse/ingestion (:langfuse-client trace-ctx) [event])))

(defn- reorder-events-for-langfuse
  "Reorder events so parents are sent before children.
   Langfuse requires parent observations to exist before children reference them."
  [events]
  (let [
        trace-start (filter #(and (= "trace-create" (:type %))
                                  (not (get-in % [:body :endTime]))) events)
        trace-end (filter #(and (= "trace-create" (:type %))
                                (get-in % [:body :endTime])) events)
        observations (filter #(#{"span-create" "generation-create"} (:type %)) events)

        obs-by-id (into {} (map (fn [e] [(get-in e [:body :id]) e]) observations))

        sorted-obs (loop [remaining (set (keys obs-by-id))
                          result []
                          processed #{}]
                     (if (empty? remaining)
                       result
                       (let [ready (filter (fn [id]
                                             (let [parent (get-in obs-by-id [id :body :parentObservationId])]
                                               (or (nil? parent)
                                                   (contains? processed parent))))
                                           remaining)]
                         (if (empty? ready)
                           (concat result (map obs-by-id remaining))
                           (recur (apply disj remaining ready)
                                  (concat result (map obs-by-id ready))
                                  (into processed ready))))))]
    (concat trace-start sorted-obs trace-end)))

(defn flush-events!
  "No-op for Langfuse since events are sent immediately via record-event!.
   Kept for backward compatibility and local trace collection."
  [_trace-ctx]
  nil)

;; =============================================================================
;; High-Level Tracing Functions
;; =============================================================================

(defn start-trace!
  "Start a new trace for a workflow execution.
   Stores the sheet-name in trace-ctx for use in end-trace!."
  [trace-ctx sheet-name inputs]
  (when (:trace-name-atom trace-ctx)
    (reset! (:trace-name-atom trace-ctx) sheet-name))
  (record-event! trace-ctx
                 (trace-start-event (:trace-id trace-ctx) sheet-name inputs)))

(defn end-trace!
  "End the current trace with results."
  [trace-ctx status outputs duration-ms error]
  (let [trace-name (when-let [atom (:trace-name-atom trace-ctx)]
                     @atom)]
    (record-event! trace-ctx
                   (trace-end-event (:trace-id trace-ctx) trace-name status outputs duration-ms error))
    (flush-events! trace-ctx)))

(defn trace-node!
  "Record a node execution span."
  [trace-ctx {:keys [node-id node-name node-type executor model
                     start-time end-time inputs outputs status error
                     observation-id parent-observation-id usage]}]
  (let [event (if (= :ai executor)
                (generation-event (:trace-id trace-ctx) node-id node-name model
                                  start-time end-time inputs outputs status error
                                  :observation-id observation-id
                                  :parent-observation-id parent-observation-id
                                  :usage usage)
                (span-event (:trace-id trace-ctx) node-id node-name node-type
                            start-time end-time inputs outputs status error
                            :observation-id observation-id
                            :parent-observation-id parent-observation-id))]
    (record-event! trace-ctx event)))

(defn create-local-trace
  "Create a local trace collector that doesn't send to Langfuse.
   Useful for REPL testing and debugging."
  []
  {:trace-id (random-uuid)
   :langfuse-client nil
   :events (atom [])
   :enabled? true
   :local? true})

(defn get-trace-summary
  "Get a summary of the local trace for display."
  [trace-ctx]
  (when (:local? trace-ctx)
    (let [events @(:events trace-ctx)
          nodes (->> events
                     (filter #(#{"span-create" "generation-create"} (:type %)))
                     (map (fn [e]
                            (let [body (:body e)]
                              {:name (:name body)
                               :type (:type e)
                               :status (get-in body [:metadata :status])
                               :duration-ms (when-let [start (:startTime body)]
                                              (when-let [end (:endTime body)]
                                                nil))}))))]
      {:trace-id (:trace-id trace-ctx)
       :node-count (count nodes)
       :nodes nodes})))

(defn create-internal-trace
  "Create an internal trace collector for event storage.
   This always collects traces for storing in the event store,
   independent of Langfuse tracing."
  []
  {:trace-id (random-uuid)
   :node-traces (atom [])
   :started-at nil
   :completed-at nil})

(defn start-internal-trace!
  "Mark the start time for internal trace collection."
  [internal-trace]
  (assoc internal-trace :started-at (time/now)))

(defn record-node-trace!
  "Record a node trace entry for internal storage.

   Args:
     internal-trace - The internal trace context
     node-trace - Map with:
       :node-id, :node-name, :node-type
       :parent-id, :path, :child-index
       :status, :started-at, :completed-at, :duration-ms
       :inputs, :outputs, :error"
  [internal-trace node-trace]
  (swap! (:node-traces internal-trace) conj node-trace))

(defn complete-internal-trace!
  "Mark the completion time and return the finalized trace."
  [internal-trace status error]
  (assoc internal-trace
         :completed-at (time/now)
         :status status
         :error error))

(defn get-node-traces
  "Get all collected node traces."
  [internal-trace]
  @(:node-traces internal-trace))

;; --- Tree layout ---

(defn compute-layout
  "Given a root node and a map of nodes-by-id, compute grid positions.

   Arguments:
   - root-node: The root node of the tree
   - nodes-by-id: Map of node-id -> node

   Returns:
   - Vector of {:node-id uuid :row int :start-col double :end-col double}"
  [root-node nodes-by-id]
  (when root-node
    (letfn [(layout-node [node row start-col end-col]
              (let [children-ids (:children-ids node)
                    children (keep #(get nodes-by-id %) children-ids)
                    child-count (count children)
                    col-width (if (pos? child-count)
                                (/ (- end-col start-col) child-count)
                                0)]
                (into [{:node-id (:id node)
                        :row row
                        :start-col (double start-col)
                        :end-col (double end-col)}]
                      (mapcat (fn [i child]
                                (let [child-start (+ start-col (* i col-width))
                                      child-end (+ child-start col-width)]
                                  (layout-node child (inc row) child-start child-end)))
                              (range)
                              children))))]
      (vec (layout-node root-node 0 0.0 1.0)))))

(defn compute-tree-depth
  "Compute the maximum depth of the tree.
   Root is at depth 0."
  [root-node nodes-by-id]
  (when root-node
    (letfn [(node-depth [node]
              (let [children (keep #(get nodes-by-id %) (:children-ids node))]
                (if (empty? children)
                  0
                  (inc (apply max (map node-depth children))))))]
      (node-depth root-node))))

(defn layout-to-grid
  "Convert fractional layout to grid coordinates.

   Arguments:
   - layout: Vector of layout entries
   - num-cols: Total number of columns in grid

   Returns:
   - Vector of {:node-id uuid :row int :col-start int :col-span int}"
  [layout num-cols]
  (mapv (fn [{:keys [node-id row start-col end-col]}]
          (let [col-start (Math/round (* start-col num-cols))
                col-end (Math/round (* end-col num-cols))
                col-span (max 1 (- col-end col-start))]
            {:node-id node-id
             :row row
             :col-start col-start
             :col-span col-span}))
        layout))

;; --- Tool invocation ---

(defn- declared
  "The explicitly declared schema for `side` (:arguments / :result) of `tool`,
   or nil when none is declared."
  [contracts tool side]
  (let [schema (get-in contracts [tool side])]
    (when (and (some? schema) (not= :untyped schema))
      schema)))

(defn- outcome
  ([kind tool message] (outcome kind tool message nil))
  ([kind tool message cause]
   (ex-info message
            {:orc.tool/outcome kind
             :orc.tool/name tool}
            cause)))

(defn- key-spelling [k]
  (if (keyword? k) (name k) k))

(defn- alias-collision?
  "True when any map in `x`, at any depth, holds one name under two spellings
   (e.g. both :k and \"k\")."
  [x]
  (cond
    (map? x)
    (or (let [spellings (map key-spelling (keys x))]
          (not= (count spellings) (count (set spellings))))
        (some alias-collision? (vals x)))

    (sequential? x)
    (boolean (some alias-collision? x))

    (set? x)
    (boolean (some alias-collision? x))

    :else false))

(def ^:private key-decoder
  "Decodes ONLY map keys: a string key becomes the schema's keyword key of the
   same name. Values are never coerced, so validation judges what was supplied."
  (mt/transformer
   {:name :tool-contract-keys
    :decoders
    {:map {:compile
           (fn [schema _]
             (let [declared-keys (into #{} (filter keyword?) (map first (m/entries schema)))
                   by-name (into {} (map (juxt name identity)) declared-keys)]
               (fn [x]
                 (if (map? x)
                   (reduce-kv (fn [acc k v]
                                (assoc acc (if (string? k) (get by-name k k) k) v))
                              {} x)
                   x))))}}}))

(defn- decoded-view [schema value]
  (m/decode schema value key-decoder))

(defn- declared-keys
  "Every map-entry key declared anywhere in `schema`."
  [schema]
  (let [acc (volatile! #{})]
    (m/walk schema
            (fn [sch _ children _]
              (when (#{:map :multi} (m/type sch))
                (when (= :map (m/type sch))
                  (vswap! acc into (map first) (m/entries sch))))
              children))
    @acc))

(defn- safe-path
  "A failing path rendered so it can never echo what the caller supplied: an
   element survives only when it is an index or a key DECLARED in the schema;
   any other (a supplied undeclared or map-of key) becomes a placeholder."
  [declared path]
  (mapv (fn [el] (if (or (integer? el) (contains? declared el)) el "<undeclared>"))
        path))

(defn- failing-paths
  [schema value]
  (let [declared (declared-keys schema)]
    (->> (:errors (m/explain schema (decoded-view schema value)))
         (map (comp (partial safe-path declared) :in))
         distinct
         vec)))

(defn- check!
  "Throw the structured outcome `kind` unless `value` honours `schema` with no
   aliased keys."
  [kind tool schema value what]
  (when schema
    (when (alias-collision? value)
      (throw (outcome kind tool
                      (str "Tool " tool ": " what
                           " supply one field under two spellings of the same name"))))
    (let [decoded (decoded-view schema value)]
      (when-not (m/validate schema decoded)
        (throw (outcome kind tool
                        (str "Tool " tool ": " what
                             " violate the declared contract at "
                             (pr-str (failing-paths schema value)))))))))

(defn validate-arguments!
  "Throw {:orc.tool/outcome :invalid-arguments} unless `args` honour the
   declared argument contract of `tool`. No-op without a declared contract."
  [contracts tool args]
  (check! :invalid-arguments tool (declared contracts tool :arguments) args "arguments"))

(defn validate-result!
  "Throw {:orc.tool/outcome :invalid-result} unless `result` honours the
   declared result contract of `tool`. No-op without a declared contract."
  [contracts tool result]
  (check! :invalid-result tool (declared contracts tool :result) result "result"))

(defn tool-error
  "The structured :tool-error outcome for an exception thrown by the tool.
   The safe message carries the exception class, plus the message the host
   explicitly declared safe to share as `:orc.tool/message` in its ex-data.
   The exception's own message and data are never copied (they may echo
   credentials or request contents)."
  [tool ^Throwable e]
  (let [declared (:orc.tool/message (ex-data e))]
    (outcome :tool-error tool
             (str "Tool " tool " failed (" (.getName (class e)) ")"
                  (when (string? declared) (str ": " declared)))
             e)))

(defn contracted?
  "True when `tool` has any declared argument or result contract."
  [contracts tool]
  (boolean (or (declared contracts tool :arguments)
               (declared contracts tool :result))))

(defn guarded-call-tool-fn
  "Wrap `call-tool-fn` so each call is checked against `contracts` (tool name ->
   {:arguments schema :result schema}). The result has the same arities as
   `call-tool-fn` ([tool args] and [tool args tool-context]).

   `:wrap-tool-errors?` (default true) turns any exception the tool itself
   throws into the safe :tool-error outcome, for every tool, typed or not —
   the model-facing (researcher) behaviour. A code leaf passes false: consumer
   code owns its host capability and sees the tool's own exception unchanged;
   it still receives the contract outcomes."
  [call-tool-fn contracts & {:keys [wrap-tool-errors?] :or {wrap-tool-errors? true}}]
  (if (or (nil? call-tool-fn) (and (empty? contracts) (not wrap-tool-errors?)))
    call-tool-fn
    (let [guarded (fn [tool args invoke]
                    (let [typed? (contracted? contracts tool)]
                      (when typed? (validate-arguments! contracts tool args))
                      (let [result (if wrap-tool-errors?
                                     (try
                                       (invoke)
                                       (catch Exception e
                                         (throw (tool-error tool e))))
                                     (invoke))]
                        (when typed? (validate-result! contracts tool result))
                        result)))]
      (fn
        ([tool args] (guarded tool args #(call-tool-fn tool args)))
        ([tool args tool-context]
         (guarded tool args #(call-tool-fn tool args tool-context)))))))

;; --- Sheet command constructors ---

(defn make-create-sheet-command
  "Create a create-sheet command with defaults.
   If sheet-id is provided, uses that ID (for deterministic UUIDs)."
  [& {:keys [name sheet-id]
      :or {name "Test Sheet"}}]
  (cond-> {:command/name :sheet/create-sheet
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :name name}
    sheet-id (assoc :sheet-id sheet-id)))

(defn make-create-node-command
  "Create a create-node command."
  [sheet-id node-type & {:keys [node-id parent-id index]}]
  (cond-> {:command/name :sheet/create-node
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :type node-type}
    node-id (assoc :node-id node-id)
    parent-id (assoc :parent-id parent-id)
    index (assoc :index index)))

(defn make-delete-node-command
  "Create a delete-node command."
  [sheet-id node-id]
  {:command/name :sheet/delete-node
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :node-id node-id})

(defn make-set-node-name-command
  "Create a set-node-name command."
  [sheet-id node-id name]
  {:command/name :sheet/set-node-name
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :node-id node-id
   :name name})

(defn make-set-node-instruction-command
  "Create a set-node-instruction command."
  [sheet-id node-id instruction]
  {:command/name :sheet/set-node-instruction
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :node-id node-id
   :instruction instruction})

(defn make-set-node-io-command
  "Create a set-node-io command."
  [sheet-id node-id reads writes]
  {:command/name :sheet/set-node-io
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :node-id node-id
   :reads reads
   :writes writes})

(defn make-set-node-executor-command
  "Create a set-node-executor command.
   executor-type: :ai, :code, or :tool
   opts: {:model \"...\", :fn \"...\", :tools [...], :options {...}}"
  [sheet-id node-id executor-type & {:keys [model fn tool tools options options-from bands-from min-confidence abstain tool-caller-fn tool-contracts]}]
  (cond-> {:command/name :sheet/set-node-executor
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :node-id node-id
           :executor executor-type}
    model (assoc :model model)
    fn (assoc :fn fn)
    tool (assoc :tool tool)
    tools (assoc :tools tools)
    options (assoc :options options)
    options-from (assoc :options-from options-from)
    bands-from (assoc :bands-from bands-from)
    tool-caller-fn (assoc :tool-caller-fn tool-caller-fn)
    tool-contracts (assoc :tool-contracts tool-contracts)
    min-confidence (assoc :min-confidence min-confidence)
    (some? abstain) (assoc :abstain abstain)))

(defn make-set-node-retry-command
  "Create a set-node-retry command."
  [sheet-id node-id max-attempts backoff-ms]
  {:command/name :sheet/set-node-retry
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :node-id node-id
   :retry {:max-attempts max-attempts
           :backoff-ms backoff-ms}})

(defn make-set-node-check-command
  "Create a set-node-check command."
  [sheet-id node-id check]
  {:command/name :sheet/set-node-check
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :node-id node-id
   :check check})

(defn make-set-parallel-config-command
  "Create a set-parallel-config command."
  [sheet-id node-id & {:keys [success-policy failure-policy]}]
  (cond-> {:command/name :sheet/set-parallel-config
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :node-id node-id}
    success-policy (assoc :success-policy success-policy)
    failure-policy (assoc :failure-policy failure-policy)))

(defn make-set-map-each-config-command
  "Create a set-map-each-config command."
  [sheet-id node-id source-key item-key output-key & {:keys [max-concurrency preserve-failures?]}]
  (cond-> {:command/name :sheet/set-map-each-config
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :node-id node-id
           :source-key source-key
           :item-key item-key
           :output-key output-key}
    max-concurrency (assoc :max-concurrency max-concurrency)
    preserve-failures? (assoc :preserve-failures? preserve-failures?)))

(defn make-set-llm-condition-config-command
  "Create a set-llm-condition-config command."
  [sheet-id node-id instruction reads & {:keys [model]}]
  (cond-> {:command/name :sheet/set-llm-condition-config
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :node-id node-id
           :instruction instruction
           :reads (vec reads)}
    model (assoc :model model)))

(defn make-set-repl-researcher-config-command
  "Create a set-repl-researcher-config command."
  [sheet-id node-id instruction reads writes mcp-tools & {:keys [model tool-caller-fn tool-contracts max-iterations browser-tools rlm timeout-ms options]}]
  (cond-> {:command/name :sheet/set-repl-researcher-config
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :node-id node-id
           :instruction instruction
           :reads (vec reads)
           :writes (vec writes)
           :mcp-tools (vec (or mcp-tools []))}
    model (assoc :model model)
    tool-caller-fn (assoc :tool-caller-fn tool-caller-fn)
    tool-contracts (assoc :tool-contracts tool-contracts)
    max-iterations (assoc :max-iterations max-iterations)
    browser-tools (assoc :browser-tools (vec browser-tools))
    (some? rlm) (assoc :rlm rlm)
    timeout-ms (assoc :timeout-ms timeout-ms)
    options (assoc :options options)))

(defn make-set-delegate-config-command
  "Create a set-delegate-config command.
   Delegate nodes execute another sheet with isolated blackboard."
  [sheet-id node-id target-sheet-id & {:keys [reads writes timeout-ms max-ticks inherit-ontology?]}]
  (cond-> {:command/name :sheet/set-delegate-config
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :node-id node-id
           :target-sheet-id target-sheet-id
           :reads (vec (or reads []))
           :writes (vec (or writes []))}
    timeout-ms (assoc :timeout-ms timeout-ms)
    max-ticks (assoc :max-ticks max-ticks)
    (some? inherit-ontology?) (assoc :inherit-ontology? inherit-ontology?)))

(defn make-declare-judge-command
  "Create a declare-judge command.

   Judge config specifies the judge type and custom criteria:
   {:type :completeness  ;; :grounding, :completeness, :instruction-following, :reasoning, :custom
    :criteria \"Must include X, Y, Z\"
    :weight 0.35
    :sheet-id UUID}  ;; For :custom type only"
  [sheet-id judge-name judge-config]
  {:command/name :sheet/declare-judge
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :judge-name judge-name
   :judge-config judge-config})

(defn make-revise-judge-command
  "Create a revise-judge command. `judge-config` is the COMPLETE new definition
   (rubric, purposes, model, ...), not a patch."
  [sheet-id judge-name judge-config]
  {:command/name :sheet/revise-judge
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :judge-name judge-name
   :judge-config judge-config})

(defn make-set-node-judges-command
  "Create a set-node-judges command."
  [sheet-id node-id judges]
  {:command/name :sheet/set-node-judges
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :node-id node-id
   :judges (vec judges)})

(defn make-set-node-context-command
  "Create a set-node-context command for self-learning injection."
  [sheet-id node-id context]
  {:command/name :sheet/set-node-context
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :node-id node-id
   :context context})

(defn make-declare-key-command
  "Create a declare-key command with a Malli schema."
  [sheet-id key schema]
  {:command/name :sheet/declare-key
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :key key
   :schema schema})

(defn make-delete-key-command
  "Create a delete-key command."
  [sheet-id key]
  {:command/name :sheet/delete-key
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :key key})

(defn make-set-content-hash-command
  "Create a set-content-hash command."
  [sheet-id content-hash]
  {:command/name :sheet/set-content-hash
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :content-hash content-hash})

(defn stream-envelope-schema
  "Specialize the live envelope discriminator and append its event-specific fields."
  [base-envelope type-kw & entries]
  (into (assoc base-envelope 1 [:orc.stream/type [:= type-kw]]) entries))

;; --- Agent runtime fixtures ---

(defn mock-value-for-type
  "Generate a mock value for a field type."
  [field-type field-name]
  (case field-type
    :text (str "Mock " field-name " output")
    :number 42
    :list ["item1" "item2" "item3"]
    :document (str "# Mock Document\n\nThis is mock content for " field-name ".")
    :image "https://example.com/mock-image.png"
    :table [{"column1" "row1-value1" "column2" "row1-value2"}
            {"column1" "row2-value1" "column2" "row2-value2"}]
    :yes-no true
    (str "unknown-type-" field-name)))

(defn generate-mock-outputs
  "Generate mock outputs based on signature output definitions."
  [output-definitions]
  (into {}
        (map (fn [output-def]
               [(:name output-def)
                {:type (:type output-def)
                 :value (mock-value-for-type (:type output-def) (:name output-def))}])
             output-definitions)))

(defn mock-runtime-from-options
  [constructor {:keys [delay-ms responses] :or {delay-ms 100 responses {}}}]
  (constructor delay-ms responses))

(defn configurable-runtime-from-options
  [constructor {:keys [delay-ms error-rate responses]
                :or {delay-ms 100 error-rate 0 responses {}}}]
  (constructor delay-ms error-rate responses))

;; --- Execution budget ---

(defonce ^:private active-attempts (atom {}))

(defonce ^:private active-work (atom {}))

(def ^:dynamic ^:private *registered-work* nil)

(def ^:private monitor-startup-timeout-ms 5000)

(def ^:private monitor-stop-timeout-ms 5000)

(defn remaining-ms [deadline-ms]
  (when deadline-ms
    (- (long deadline-ms) (System/currentTimeMillis))))

(defn record-attempt! [tick-id node-id state]
  (when (and tick-id node-id)
    (swap! active-attempts assoc [tick-id node-id (or (:exec-context state) {})] state))
  state)

(defn attempts-for-tick [tick-id]
  (into {} (for [[[candidate-tick-id node-id exec-context] state] @active-attempts
                 :when (= tick-id candidate-tick-id)]
             [[node-id exec-context] state])))

(defn clear-node! [tick-id node-id]
  (when (and tick-id node-id)
    (swap! active-attempts
           (fn [attempts]
             (into {} (remove (fn [[[candidate-tick-id candidate-node-id _] _]]
                                (and (= tick-id candidate-tick-id)
                                     (= node-id candidate-node-id)))
                              attempts))))))

(defn clear-tick! [tick-id]
  (swap! active-attempts
         (fn [attempts]
           (into {} (remove (fn [[[candidate-tick-id _ _] _]]
                              (= tick-id candidate-tick-id))
                            attempts))))
  (swap! active-work dissoc tick-id))

(defn register-work! [tick-id node-id work]
  (swap! active-work assoc-in [tick-id node-id] work)
  work)

(defn current-work
  "Return the exact registered Future bound to the current worker thread.

   This is intentionally identity-based rather than a lookup by tick/node: a
   later quantum may already have replaced that mutable slot."
  []
  *registered-work*)

(defn deregister-work!
  "Forget work only when it is still the registration for this node.

   A later quantum may replace the same [tick-id node-id] slot before an older
   future finishes. Identity checking prevents the older future's cleanup from
   erasing the newer cancellation target."
  [tick-id node-id work]
  (swap! active-work
         (fn [work-by-tick]
           (if (identical? work (get-in work-by-tick [tick-id node-id]))
             (let [remaining (dissoc (get work-by-tick tick-id) node-id)]
               (if (seq remaining)
                 (assoc work-by-tick tick-id remaining)
                 (dissoc work-by-tick tick-id)))
             work-by-tick)))
  nil)

(defn start-work!
  "Start f asynchronously after registering its future for cancellation.

   The start gate closes the create-future/register race: f cannot complete
   and run cleanup before its future is visible in active-work."
  [tick-id node-id f]
  (let [start-signal (promise)
        work-ref (promise)
        work (future
               (try
                 @start-signal
                 (binding [*registered-work* @work-ref]
                   (f))
                 (finally
                   (deregister-work! tick-id node-id @work-ref))))]
    (deliver work-ref work)
    (register-work! tick-id node-id work)
    (deliver start-signal true)
    work))

(defmacro registered-future
  "Use the cancellation ledger only for work that opted into registration.

   The false branch expands to the original `(future body...)` expression so
   callers outside the registered boundary keep their prior behavior."
  [registered? tick-id node-id & body]
  `(if ~registered?
     (start-work! ~tick-id ~node-id (fn [] ~@body))
     (future ~@body)))

(defn cancel-work-when!
  "Monitor active? and cooperatively cancel the exact supplied Future on loss.

   wait-for-next-check! is an injected blocking capability. Production uses a
   timed wait; tests can release a barrier without scheduler-duration claims.
   The monitor owns a daemon thread rather than sharing Clojure's process-global
   future executor: unrelated application futures cannot prevent the ownership
   fence from starting. Construction does not return until that thread has made
   its initial live ownership check, so campaign work cannot outrun an unarmed
   monitor. Returns a Future-compatible OwnershipMonitor containing the
   authoritative :initial-state and an exit signal for bounded cleanup."
  [work active? wait-for-next-check! on-loss!]
  (let [initial-fence (promise)
        exited (promise)
        monitoring-current-work? (identical? work (current-work))
        monitor
        (FutureTask.
         ^Callable
         (fn []
           (try
             (loop [initial? true]
               (if (future-done? work)
                 (when initial?
                   (deliver initial-fence :work-settled))
                 (let [owned?
                       (try
                         (boolean (active?))
                         (catch Throwable _
                           false))]
                   (if owned?
                     (do
                       (when initial?
                         (deliver initial-fence :owned))
                       (wait-for-next-check!)
                       (recur false))
                     (do
                       (on-loss!)
                       (when initial?
                         (deliver initial-fence :ownership-lost))
                       (when-not (and initial? monitoring-current-work?)
                         (future-cancel work)))))))
             (catch InterruptedException _
               (deliver initial-fence :interrupted)
               nil)
             (catch Throwable _
               (on-loss!)
               (deliver initial-fence :failed)
               (future-cancel work))
             (finally
               (deliver exited true)))))
        monitor-thread
        (doto (Thread. monitor "orc-researcher-lease-monitor")
          (.setDaemon true))]
    (.start monitor-thread)
    (let [initial-state
          (deref initial-fence monitor-startup-timeout-ms :startup-timeout)]
      (when (= :startup-timeout initial-state)
        (on-loss!)
        (future-cancel monitor))
      (execution-budget/->OwnershipMonitor monitor monitor-thread
                          (if (= :startup-timeout initial-state)
                            :failed
                            initial-state)
                          exited))))

(defn stop-ownership-monitor!
  "Interrupt monitor and prove its daemon thread exited within a bounded wait.

   Injected wait capabilities must honour interruption. A false return would
   silently turn cleanup into a thread leak, so failure is surfaced. Cleanup
   itself is uninterruptible within the bound and restores the caller's prior
   or newly observed interrupt status before returning."
  [{:keys [monitor thread]}]
  (let [deadline-ns (+ (System/nanoTime)
                       (* monitor-stop-timeout-ms 1000000))
        interrupted? (volatile! false)
        timeout-error
        #(ex-info "Researcher ownership monitor did not stop"
                  {:timeout-ms monitor-stop-timeout-ms
                   :thread-name (some-> ^Thread thread .getName)})]
    (try
      (when monitor
        (future-cancel monitor))
      (when thread
        (loop []
          (when (.isAlive ^Thread thread)
            (let [remaining-ns (- deadline-ns (System/nanoTime))]
              (when-not (pos? remaining-ns)
                (throw (timeout-error)))
              (try
                (.join ^Thread thread
                       (max 1 (quot remaining-ns 1000000)))
                (catch InterruptedException _
                  (vreset! interrupted? true)))
              (recur)))))
      true
      (finally
        (when @interrupted?
          (.interrupt (Thread/currentThread)))))))

(defn cancel-active-work! [tick-id]
  (doseq [[_ work] (get @active-work tick-id)]
    (future-cancel work))
  (swap! active-work dissoc tick-id))

(defn run-owned-cancellation!
  "Finish coordinated terminal writes independently of the model Future they
   cancel. This scoped task returns only after joining its actual raw thread;
   caller interruption is deferred and restored, never treated as task exit.

   There is no separate helper deadline or detached fallback. Event-store I/O
   retains its own bounds; if it never settles, this scope cannot claim cleanup.
   Task failures propagate after the join, preserving command fault evidence."
  [task]
  (let [interrupted? (volatile! (Thread/interrupted))
        work (FutureTask. ^Callable task)
        thread (Thread. work "orc-budget-cancellation")]
    (try
      (.start thread)
      (loop []
        (when (.isAlive thread)
          (try (.join thread)
               (catch InterruptedException _ (vreset! interrupted? true)))
          (recur)))
      (try (.get work)
           (catch java.util.concurrent.ExecutionException e
             (throw (.getCause e))))
      (finally
        (when @interrupted? (.interrupt (Thread/currentThread)))))))

;; --- Execution lease ---

(def default-lease-ms 90000)

(defonce ^:private process-instance-id (random-uuid))

(defn instance-id [context]
  (or (:orc/instance-id context) process-instance-id))

(defn lease-ms [context]
  (or (:orc/execution-lease-ms context) default-lease-ms))

(defn now-instant ^Instant [context]
  (if-let [clock (:orc/clock-fn context)]
    (clock)
    (.toInstant ^OffsetDateTime (time/now))))

(defn fields
  "The lease a start recorded by this worker carries right now."
  [context]
  {:lease-owner (instance-id context)
   :lease-expires-at (str (.plusMillis (now-instant context) (lease-ms context)))})

(defn leased-node? [node]
  (contains? #{:leaf :delegate :repl-researcher} (:type node)))

(defonce ^:private live-work (atom {}))

(defn work-key [tick-id node-id exec-context]
  [tick-id node-id (or exec-context {})])

(defn- exec-context-of [inputs]
  (select-keys (or inputs {})
               [:ai.obney.orc.orc-service.core.todo-processors/map-each-index
                :ai.obney.orc.orc-service.core.todo-processors/map-each-parent
                :ai.obney.orc.orc-service.core.todo-processors/tick-iteration]))

(defn live?
  "True while this process is running the execution."
  [tick-id node-id exec-context]
  (contains? @live-work (work-key tick-id node-id exec-context)))

(defn mark-live! [k]
  (swap! live-work update k (fnil inc 0)))

(defn unmark-live! [k]
  (swap! live-work
         (fn [work]
           (let [n (dec (get work k 0))]
             (if (pos? n) (assoc work k n) (dissoc work k))))))

(defonce queued (atom {}))

(defonce queue-renewer (atom nil))

(defonce queue-lock (Object.))

(def poll-ms 100)

(def unseen-grace-multiple 3)

(defn queued-key [tick-id node-id exec-context]
  [tick-id node-id (or exec-context {})])

(defn queued-count
  "Number of starts this process is keeping leased while they wait to run."
  []
  (count @queued))

(defn renewer-alive? []
  (when-let [^Thread t @queue-renewer] (.isAlive t)))

(defn untrack-queued! [k]
  (swap! queued dissoc k))

(defn end!
  "Stop renewing and forget the execution. Idempotent per handle; always call
   from a `finally`."
  [{:keys [key stop ^Thread renewer ended?]}]
  (when (compare-and-set! ended? false true)
    (deliver stop true)
    (unmark-live! key)
    (try
      (.join renewer 5000)
      (catch InterruptedException _
        (.interrupt (Thread/currentThread))))))

;; ---------------------------------------------------------------------------
;; Recovery decision
;; ---------------------------------------------------------------------------

(defn- parse-instant [s]
  (when s
    (try (Instant/parse s)
         (catch Exception _ (.toInstant (OffsetDateTime/parse s))))))

(defn latest-lease-expiry
  "Latest known lease expiry for a start: its own and its renewals'."
  [start renewals]
  (->> (cons (:lease-expires-at start) (map :lease-expires-at renewals))
       (keep parse-instant)
       (reduce (fn [latest i] (if (or (nil? latest) (.isAfter ^Instant i ^Instant latest)) i latest))
               nil)))

(defn abandoned?
  "True when recovery may resume `start`. `renewals` are the lease-renewed
   events naming this start.

   - running in this process: never abandoned, unless the work is epoch-fenced
     (`:fenced? true`, a checkpointed researcher): its durable frontier rejects
     a stale worker's append, so a live-but-unrenewed owner (a paused worker
     whose lease has expired) is recoverable and only the lease decides
   - otherwise abandoned only once the latest lease has expired (a legacy
     lease-less start counts as expired). Ownership by this worker is NOT a
     shortcut: a start this worker recorded but has not yet run is healthy
     queued work, and a real crash means a new process with a new identity."
  ([context start renewals exec-context]
   (abandoned? context start renewals exec-context nil))
  ([context start renewals exec-context {:keys [fenced?]}]
   (and (or fenced? (not (live? (:tick-id start) (:node-id start) exec-context)))
        (let [expiry (latest-lease-expiry start renewals)]
          (or (nil? expiry)
              (.isAfter (now-instant context) expiry))))))

;; --- Sci sandbox ---

;; ============================================================================
;; Safe Function Whitelist
;; ============================================================================

(def safe-clojure-core
  "Safe clojure.core functions allowed in the sandbox."
  '[+ - * / mod quot rem
    = not= < > <= >= compare
    str pr-str prn-str println print
    inc dec min max abs
    first rest next last butlast
    cons conj concat into
    map filter remove reduce
    take drop take-while drop-while
    partition partition-all partition-by
    sort sort-by reverse shuffle
    get get-in assoc assoc-in dissoc update update-in
    select-keys merge merge-with
    keys vals contains? find
    count empty? not-empty seq vec set list
    apply comp partial juxt
    identity constantly
    some every? not-any? not-every?
    group-by frequencies
    zipmap interleave interpose
    repeat range iterate
    true? false? nil? some? boolean
    keyword keyword? symbol symbol? string? number? integer? float? map? vector? set? list? coll? seq? fn?
    name namespace
    re-find re-matches re-seq
    subs format
    type class])

;; ============================================================================
;; MCP Tool Binding
;; ============================================================================

(defn- create-mcp-tool-fn
  "Create a function that calls an MCP tool and returns the result.

   call-tool-fn is a function of (tool-name args-map) -> result.

   The returned function accepts either:
   - A map of arguments: (tool {:query \"search term\"})
   - Keyword arguments: (tool :query \"search term\")"
  [call-tool-fn tool-name]
  (fn [& args]
    (let [args-map (cond
                     (and (= 1 (count args)) (map? (first args)))
                     (first args)

                     (even? (count args))
                     (apply hash-map args)

                     :else
                     {"arg" (first args)})]
      (u/trace :ai.obney.orc.orc-service.core.sci-sandbox/sci-mcp-call {:tool tool-name :args args-map}
        (try
          (call-tool-fn tool-name args-map)
          (catch Exception e
            (if-let [kind (:orc.tool/outcome (ex-data e))]
              {:error (.getMessage e) :orc.tool/outcome kind}
              {:error (.getMessage e)})))))))

(defn- parse-tool-name
  "Parse a tool name into [server-sym fn-sym] or [nil fn-sym].
   \"linear/list_issues\" -> ['linear 'list_issues]
   \"lookup\"             -> [nil 'lookup]"
  [tool-name]
  (if-let [idx (str/index-of tool-name "/")]
    [(symbol (subs tool-name 0 idx))
     (symbol (subs tool-name (inc idx)))]
    [nil (symbol tool-name)]))

(defn build-tool-bindings
  "Build SCI bindings for MCP tools.

   Flat names ('lookup') are bound in the user namespace (backward compat).
   Namespaced names ('linear/list_issues') are bound in per-server SCI namespaces,
   enabling namespace-qualified calls like (linear/list_issues {:project \"abc\"}).

   Returns {:flat {sym fn} :namespaces {ns-sym {fn-sym fn}}}

   Public so the recursive RLM sandbox (rlm_sandbox.clj) can wire the SAME MCP
   tool bindings the plain researcher gets — see build-rlm-context."
  [call-tool-fn mcp-tools]
  (if (nil? call-tool-fn)
    {:flat {} :namespaces {}}
    (let [parsed (map (fn [t]
                        (let [[server sym] (parse-tool-name t)]
                          {:full t :server server :sym sym}))
                      mcp-tools)
          flat-tools (filter (comp nil? :server) parsed)
          ns-tools   (remove (comp nil? :server) parsed)]
      {:flat (reduce (fn [acc {:keys [full sym]}]
                       (assoc acc sym (create-mcp-tool-fn call-tool-fn full)))
                     {} flat-tools)
       :namespaces (reduce-kv
                     (fn [acc server tools]
                       (assoc acc server
                              (reduce (fn [m {:keys [full sym]}]
                                        (assoc m sym (create-mcp-tool-fn call-tool-fn full)))
                                      {} tools)))
                     {}
                     (group-by :server ns-tools))})))

(defn- build-browser-tool-bindings
  "Build SCI bindings for agent-browser tools.

   browser-tools is a vector of tool names to expose.
   Returns a map of {symbol -> fn}."
  [browser-tools]
  (when (seq browser-tools)
    (try
      (let [all-tools @(integrations/capability :agent-browser :browser-tools)
            selected (select-keys all-tools browser-tools)]
        (reduce-kv
         (fn [acc name fn]
           (assoc acc (symbol name) fn))
         {}
         selected))
      (catch Exception e
        (u/log :ai.obney.orc.orc-service.core.sci-sandbox/browser-tools-not-available :error (.getMessage e))
        {}))))

;; ============================================================================
;; SCI Context Building
;; ============================================================================

(defn build-sci-context
  "Build a SCI execution context with MCP and browser tools injected.

   The context provides:
   - Safe subset of clojure.core
   - MCP tools as callable functions (via :call-tool-fn)
   - Browser tools as direct functions (via :browser-tools)
   - Custom bindings for print functions (capture stdout)

   Options:
   - :call-tool-fn - Function (tool-name args-map) -> result for MCP calls
   - :mcp-tools - Vector of MCP tool names to inject
   - :browser-tools - Vector of browser tool names to inject (e.g., [\"open\" \"snapshot\" \"click\"])
   - :stdout-writer - StringWriter to capture stdout (optional)
   - :deny - Additional global symbols to reject during SCI analysis (optional)"
  [{:keys [call-tool-fn mcp-tools browser-tools stdout-writer deny]}]
  (let [{:keys [flat namespaces]} (build-tool-bindings call-tool-fn mcp-tools)

        browser-bindings (build-browser-tool-bindings browser-tools)

        core-publics (ns-publics 'clojure.core)
        safe-core (select-keys core-publics safe-clojure-core)

        safe-core-final (if stdout-writer
                          (let [print-fn (fn [& args]
                                          (.write stdout-writer (str (apply str args))))
                                println-fn (fn [& args]
                                             (.write stdout-writer (str (apply str args) "\n")))
                                prn-fn (fn [& args]
                                         (.write stdout-writer (str (apply str (map pr-str args)) "\n")))]
                            (assoc safe-core
                                   'print print-fn
                                   'println println-fn
                                   'prn prn-fn))
                          safe-core)

        print-bindings (when stdout-writer
                         {'print (fn [& args]
                                   (.write stdout-writer (str (apply str args))))
                          'println (fn [& args]
                                     (.write stdout-writer (str (apply str args) "\n")))
                          'prn (fn [& args]
                                 (.write stdout-writer (str (apply str (map pr-str args)) "\n")))})

        realize-all (fn realize-all [x]
                      (cond
                        (map? x) (into {} (map (fn [[k v]] [k (realize-all v)]) x))
                        (coll? x) (into (empty x) (map realize-all x))
                        :else x))

        final-answer-fn (fn [value]
                          (let [realized (realize-all value)]
                            (str "FINAL_ANSWER: " (pr-str realized))))

        all-bindings (merge flat browser-bindings print-bindings
                            {'FINAL_ANSWER final-answer-fn
                             'realize-all realize-all})]

    (sci/init
     {:deny deny
      :namespaces (merge {'clojure.core safe-core-final
                          'user (merge flat browser-bindings)}
                         namespaces)
      :bindings all-bindings
      :classes {'clojure.lang.PersistentArrayMap clojure.lang.PersistentArrayMap
                'clojure.lang.PersistentHashMap clojure.lang.PersistentHashMap
                'clojure.lang.PersistentVector clojure.lang.PersistentVector
                'clojure.lang.PersistentHashSet clojure.lang.PersistentHashSet
                'clojure.lang.PersistentTreeMap clojure.lang.PersistentTreeMap}})))

;; ============================================================================
;; Code Execution
;; ============================================================================

(defn call-with-output-writer
  "Run one evaluation or generated invocation with matching host and SCI output.
   Both dynamic bindings are restored even when the supplied function throws."
  [writer f]
  (binding [*out* writer]
    (sci/with-bindings {sci/out writer}
      (f))))

(defn sci-sandbox-execute-code
  "Execute Clojure code in the SCI sandbox.

   Returns a map with:
   - :stdout - Captured stdout as string
   - :result - The evaluation result (pr-str'd)
   - :error - Error message if execution failed
   - :raw-result - The actual Clojure value (not stringified)"
  [sci-ctx code-string]
  (let [stdout-writer (StringWriter.)]
    (try
      (let [result (call-with-output-writer stdout-writer
                     #(sci/eval-string* sci-ctx code-string))]
        {:stdout (str stdout-writer)
         :result (pr-str result)
         :raw-result result
         :error nil})
      (catch Exception e
        {:stdout (str stdout-writer)
         :result nil
         :raw-result nil
         :error (.getMessage e)}))))

;; ============================================================================
;; FINAL_ANSWER Detection
;; ============================================================================

(def final-answer-patterns
  "Patterns that indicate a final answer in LLM output or code result.
   Order matters - more specific patterns first, generic patterns last.
   Uses non-greedy matching and explicit boundary handling to avoid
   capturing trailing quotes. Uses (?s) for multi-line JSON support."
  [
   #"\(str\s+\"FINAL_ANSWER:\s*\"\s*([^)\s]+)\s*\)"
   #"\"FINAL_ANSWER:\s*([^\"]+)\""
   #"(?s)FINAL_ANSWER:\s*(.+?)\"?\z"
   #"(?s)FINAL-ANSWER:\s*(.+?)\"?\z"])

(defn- try-parse-edn
  "Try to parse a string as EDN. Returns parsed value or nil on failure.

   Handles escaped quotes from pr-str output by unescaping first."
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (let [
          unescaped (-> s
                        (str/replace "\\\"" "\"")
                        (str/replace "\\\\" "\\"))]
      (try
        (edn/read-string unescaped)
        (catch Exception _
          (try
            (edn/read-string s)
            (catch Exception _ nil)))))))

(defn extract-final-answer
  "Extract the final answer from a string if present.
   Returns nil if no final answer pattern is found.

   Attempts to parse the extracted value as EDN if it looks like a data structure.
   Returns the parsed EDN if successful, otherwise returns the raw string."
  [s]
  (when (string? s)
    (some (fn [pattern]
            (when-let [[_ answer] (re-find pattern s)]
              (let [trimmed (str/trim answer)]
                (if (or (str/starts-with? trimmed "[")
                        (str/starts-with? trimmed "{")
                        (str/starts-with? trimmed "("))
                  (or (try-parse-edn trimmed) trimmed)
                  trimmed))))
          final-answer-patterns)))

(defn contains-final-answer?
  "Check if a string contains a FINAL_ANSWER marker."
  [s]
  (boolean (extract-final-answer s)))

;; ============================================================================
;; Convergence Detection
;; ============================================================================

(defn repeated-output?
  "Check if the current output matches previous outputs (indicating convergence)."
  [history current-result]
  (let [current-str (str (:stdout current-result) (:result current-result))
        recent-outputs (->> history
                            (take-last 2)
                            (map #(str (:stdout %) (:result %))))]
    (some #(= % current-str) recent-outputs)))

;; ============================================================================
;; High-Level Execution
;; ============================================================================

(defn execute-with-mcp
  "Execute code with MCP and/or browser tools available.

   This is a convenience function that builds context and executes in one step.

   Options:
   - :call-tool-fn - Function (tool-name args-map) -> result for MCP tools
   - :mcp-tools - Vector of MCP tool names to inject
   - :browser-tools - Vector of agent-browser tool names to inject
   - :code - Code string to execute

   Returns:
   - :stdout - Captured output
   - :result - Evaluation result
   - :error - Error if any
   - :final-answer - Extracted answer if FINAL_ANSWER found"
  [{:keys [call-tool-fn mcp-tools browser-tools code]}]
  (let [ctx (build-sci-context {:call-tool-fn call-tool-fn
                                :mcp-tools mcp-tools
                                :browser-tools browser-tools})
        exec-result (sci-sandbox-execute-code ctx code)
        raw (when (string? (:raw-result exec-result))
              (:raw-result exec-result))
        final-answer (or (when raw (extract-final-answer raw))
                         (extract-final-answer (:result exec-result))
                         (extract-final-answer (:stdout exec-result)))]
    (assoc exec-result :final-answer final-answer)))

(defn usage-number [usage & ks]
  (some (fn [k] (let [v (get usage k)] (when (number? v) v))) ks))

;; --- Metadata ---

;; =============================================================================
;; Metadata Extraction
;; =============================================================================

(defn classify-node-type
  "Classify a node's type, handling leaf nodes specially.

   Leaf nodes are further classified by their executor:
   - :executor :ai / :decision -> :llm (a decision calls a model)
   - :executor :code -> :code
   - otherwise -> :leaf"
  [node]
  (let [base-type (:type node)]
    (if (= :leaf base-type)
      (case (:executor node)
        (:ai :decision) :llm
        :code :code
        :leaf)
      base-type)))

(defn infer-problem-types
  "Heuristically infer problem types from tree structure.

   Analyzes node names and blackboard keys to guess what types of
   problems this tree is designed to solve.

   Args:
   - metadata: Extracted metadata (for node info)
   - nodes: Vector of node maps
   - blackboard: Vector of blackboard entry maps

   Returns vector of problem URIs."
  [_metadata nodes blackboard]
  (let [node-names (map (comp str/lower-case str :name) nodes)
        bb-keys (map (comp str/lower-case str :key) blackboard)
        all-terms (concat node-names bb-keys)]
    (cond-> []
      (some #(re-find #"classif|categor|label|type" %) all-terms)
        (conj "problem:Classification")

      (some #(re-find #"score|rating|rank|evaluat" %) all-terms)
        (conj "problem:Scoring")

      (some #(re-find #"extract|parse|pull|field" %) all-terms)
        (conj "problem:DataExtraction")

      (some #(re-find #"summar|condense|brief|tldr" %) all-terms)
        (conj "problem:Summarization")

      (some #(re-find #"generat|creat|write|draft|compos" %) all-terms)
        (conj "problem:Generation")

      (some #(re-find #"question|answer|qa|respond" %) all-terms)
        (conj "problem:QuestionAnswering")

      (some #(re-find #"transform|convert|format|translat" %) all-terms)
        (conj "problem:Transformation")

      (some #(re-find #"compar|diff|match|similar" %) all-terms)
        (conj "problem:Comparison"))))

(defn merge-metadata
  "Merge extracted metadata with user-provided overrides.

   Args:
   - extracted: Metadata from extract-tree-metadata
   - overrides: Map with optional :problem-types, :description, etc.

   Returns merged metadata map."
  [extracted overrides]
  (let [{:keys [problem-types description]} overrides]
    (cond-> extracted
      (seq problem-types) (assoc :problem-types problem-types)
      description (assoc :description description))))

;; --- Provider observations ---

(def provider-observations-owner-keys [:sheet-id :tick-id :node-id :node-start-id :exec-context
                 :node-attempt :provider-attempt :invocation-id
                 :candidate-index :requested-provider :requested-model])

(def provider-observations-event-types #{:sheet/provider-attempt-started :sheet/provider-attempt-completed})

(defn provider-observations-authorized? [ctx] (and (some? (:tenant-id ctx)) (some? (:auth-claims ctx))))

(defn provider-observations-rejected [category message] {:cognitect.anomalies/category category :cognitect.anomalies/message message})

(defn append-transition [type owner outcome predicate]
  {:command-result/events
   [(->event {:type type
              :tags #{[:sheet (:sheet-id owner)] [:tick (:tick-id owner)]
                      [:provider-node-start (:node-start-id owner)]
                      [:provider-invocation (:invocation-id owner)]}
              :body (cond-> owner outcome (assoc :outcome outcome))})]
   :command-result/cas {:tags #{[:provider-invocation (:invocation-id owner)]}
                        :types provider-observations-event-types :predicate-fn #(predicate (into [] %))}})

;; --- Researcher resume state ---

(defn- researcher-resume-state-ordered [values]
  (sort-by pr-str values))

(defn- researcher-resume-state-canonical-value
  "Typed, traversal-order-independent data used as the sandbox hash preimage.

   Two families are normalized to a value-only representation instead of a
   class-derived tag, because a durable round trip through the event store
   can legitimately change the concrete class without changing the value:

   - Clojure lists and vectors both become `:sequential`. Grain's Postgres
     event store fressian-encodes/decodes event bodies, and its
     `deep-clojurize` step (ai.obney.grain.fressian-util.interface) converts
     any `java.util.List` that is not already a `PersistentVector` — which
     includes an already-correct Clojure `PersistentList` fressian itself
     reconstructed — into a vector via `(mapv ...)`. So a sandbox value that
     was a list when hashed at write time comes back a vector when re-hashed
     at hydrate time on Postgres (never on the in-memory store, which returns
     the original object), and the old class-tagged `:list`/`:vector` split
     turned that legitimate, `=`-preserving round trip into a hash mismatch.
     Confirmed live: `docker exec orc-rs7-postgres createdb -U orc
     rs7_diag_arc1`, then `es/append` + `es/read` a value containing
     `(list 1 2 3)` through that real Postgres store — it comes back
     `[1 2 3]`, `=` true, class changed. A synthetic sandbox built the same
     way as the researcher's, appended through that same real store, and
     rehashed, reproduces the exact `:expected`/`:actual` hash pair from a
     genuinely stalled rs7-traffic-sweep Postgres campaign
     (tick 927fde10-4363-4886-b6ee-6acd9e2e9936).
   - Any `inst?` value (java.time.Instant, java.util.Date, ...) is reduced to
     its epoch millisecond (`inst-ms`) instead of `(pr-str value)`. Without a
     registered print-method, `pr-str` on a bare `java.time.Instant` falls
     back to the default `#object[... <identity-hash> ...]` form, which
     differs between two independently-constructed but `=`-equal Instants —
     including the original in-memory instant and the one read back after
     any round trip. This only happens to stay invisible when something
     elsewhere in the process has already registered a stable print-method
     (e.g. `time-literals`) — relying on that incidental load order is not a
     stable hash. `inst-ms` is class- and load-order-independent and equal
     for any two equal instants.
   - Any `integer?` value (java.lang.Integer, Long, Short, Byte, BigInteger,
     clojure.lang.BigInt) is reduced to `(str value)` under one `:integer`
     tag instead of the class-tagged scalar branch. Confirmed live from a
     genuinely stalled rs7-traffic-sweep Postgres campaign, comparing the
     write-time canonical form (dumped from `encode-full`) against the
     read-time canonical form of the SAME real stored fact character by
     character: ORC's own `execute-two-revision-campaign`-style RLM tree
     bookkeeping (`:nodes-failed`/`:nodes-succeeded`/`:nodes-total`, produced
     via Java interop such as a collection's `.size()`) is a bare
     `java.lang.Integer` at write time; after a Postgres round trip through
     `ai.obney.grain.fressian-util.interface` it reads back a
     `java.lang.Long` — the fressian Clojure handlers normalize every
     integral width to Long on decode regardless of what was encoded. `=`
     holds (`(= (int 0) (long 0))` is true) but the old class-tagged scalar
     form did not. Doubles/Floats/Ratios/BigDecimals showed no such class
     drift in the same live comparison, so they stay on the generic scalar
     branch below rather than being folded in without evidence."
  [value]
  (cond
    (nil? value) [:nil]
    (record? value)
    (throw (ex-info "unsupported researcher sandbox value"
                    {:value-class (.getName (class value))}))
    (map-entry? value) [:map-entry (researcher-resume-state-canonical-value (key value))
                        (researcher-resume-state-canonical-value (val value))]
    (map? value) [:map (->> value
                            (map (fn [[k v]] [(researcher-resume-state-canonical-value k)
                                              (researcher-resume-state-canonical-value v)]))
                            researcher-resume-state-ordered
                            vec)]
    (set? value) [:set (->> value (map researcher-resume-state-canonical-value) researcher-resume-state-ordered vec)]
    (or (vector? value) (list? value))
    [:sequential (mapv researcher-resume-state-canonical-value value)]
    (keyword? value) [:keyword (namespace value) (name value)]
    (symbol? value) [:symbol (namespace value) (name value)]
    (uuid? value) [:uuid (str value)]
    (inst? value) [:inst (inst-ms value)]
    (integer? value) [:integer (str value)]
    (or (string? value) (boolean? value) (number? value) (char? value))
    [:scalar (.getName (class value)) (pr-str value)]
    :else
    (throw (ex-info "unsupported researcher sandbox value"
                    {:value-class (.getName (class value))}))))

(defn sandbox-hash
  "Stable SHA-256 identity of a complete sandbox value map."
  [sandbox]
  (let [bytes (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str (researcher-resume-state-canonical-value sandbox))
                                  StandardCharsets/UTF_8))]
    (str "sha256:"
         (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes)))))

(defn- legacy-canonical-value
  "The hash preimage before value-family canonicalization, kept verbatim so a
   resume fact saved under it still verifies after the upgrade."
  [value]
  (cond
    (nil? value) [:nil]
    (record? value)
    (throw (ex-info "unsupported researcher sandbox value"
                    {:value-class (.getName (class value))}))
    (map-entry? value) [:map-entry (legacy-canonical-value (key value))
                        (legacy-canonical-value (val value))]
    (map? value) [:map (->> value
                            (map (fn [[k v]] [(legacy-canonical-value k)
                                              (legacy-canonical-value v)]))
                            researcher-resume-state-ordered
                            vec)]
    (set? value) [:set (->> value (map researcher-resume-state-canonical-value) researcher-resume-state-ordered vec)]
    (vector? value) [:vector (mapv researcher-resume-state-canonical-value value)]
    (list? value) [:list (mapv researcher-resume-state-canonical-value value)]
    (keyword? value) [:keyword (namespace value) (name value)]
    (symbol? value) [:symbol (namespace value) (name value)]
    (uuid? value) [:uuid (str value)]
    (or (string? value) (boolean? value) (number? value) (char? value)
        (inst? value))
    [:scalar (.getName (class value)) (pr-str value)]
    :else
    (throw (ex-info "unsupported researcher sandbox value"
                    {:value-class (.getName (class value))}))))

(defn- legacy-sandbox-hash [sandbox]
  (let [bytes (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str (legacy-canonical-value sandbox))
                                  StandardCharsets/UTF_8))]
    (str "sha256:"
         (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes)))))

(defn- hash-matches?
  "A stored hash verifies when it matches the current preimage, or the
   pre-upgrade one for a fact written before value-family canonicalization.
   Both are checked explicitly; a hash that matches neither still fails."
  [expected sandbox]
  (or (= expected (sandbox-hash sandbox))
      (= expected (legacy-sandbox-hash sandbox))))

(defn encode-full
  "Encode a complete V2 continuation state as one V3 full-snapshot fact."
  [resume-state]
  (let [sandbox (:sandbox-vars resume-state)]
    (-> resume-state
        (dissoc :sandbox-vars)
        (assoc :version 3
               :sandbox-fact-kind :full-snapshot
               :resulting-state-hash (sandbox-hash sandbox)
               :sandbox-snapshot sandbox))))

(defn- sandbox-puts [prior current]
  (into {}
        (filter (fn [[key value]]
                  (or (not (contains? prior key))
                      (not= value (get prior key)))))
        current))

(defn- sandbox-deletes [prior current]
  (into #{} (remove #(contains? current %)) (keys prior)))

(def ^:private delta-fields
  #{:predecessor-revision :predecessor-state-hash
    :sandbox-puts :sandbox-deletes})

(defn valid-v3-shape?
  "Whether a V3 resume fact contains exactly the fields required by its kind."
  [fact]
  (and (string? (:resulting-state-hash fact))
       (case (:sandbox-fact-kind fact)
         :full-snapshot
         (and (contains? fact :sandbox-snapshot)
              (map? (:sandbox-snapshot fact))
              (not-any? #(contains? fact %) delta-fields))

         :sandbox-delta
         (and (not (contains? fact :sandbox-snapshot))
              (every? #(contains? fact %) delta-fields)
              (nat-int? (:predecessor-revision fact))
              (string? (:predecessor-state-hash fact))
              (map? (:sandbox-puts fact))
              (set? (:sandbox-deletes fact))
              (every? keyword? (:sandbox-deletes fact)))

         false)))

(defn- validate-v3-shape! [fact]
  (when-not (valid-v3-shape? fact)
    (throw (ex-info "researcher sandbox resume fact shape is invalid"
                    {:revision (:revision fact)
                     :kind (:sandbox-fact-kind fact)}))))

(defn encode
  "Encode V2 state using a full snapshot initially and at interval boundaries;
   otherwise emit a value-bearing delta from the immediately prior fact."
  [resume-state predecessor-fact predecessor-state snapshot-interval]
  (when-not (and (integer? snapshot-interval) (pos? snapshot-interval))
    (throw (ex-info "researcher sandbox snapshot interval must be positive"
                    {:snapshot-interval snapshot-interval})))
  (if (or (nil? predecessor-fact)
          (not= 3 (:version predecessor-fact))
          (zero? (mod (:revision resume-state) snapshot-interval)))
    (encode-full resume-state)
    (let [sandbox (:sandbox-vars resume-state)
          prior-sandbox (:sandbox-vars predecessor-state)]
      (-> resume-state
          (dissoc :sandbox-vars)
          (assoc :version 3
                 :sandbox-fact-kind :sandbox-delta
                 :predecessor-revision (:revision predecessor-fact)
                 :predecessor-state-hash (:resulting-state-hash predecessor-fact)
                 :resulting-state-hash (sandbox-hash sandbox)
                 :sandbox-puts (sandbox-puts prior-sandbox sandbox)
                 :sandbox-deletes (sandbox-deletes prior-sandbox sandbox))))))

(defn researcher-resume-state-hydrate
  "Verify and hydrate a durable resume fact to the V2 shape consumed publicly."
  [prior-resume-state fact]
  (case (:version fact)
    3
    (do
      (validate-v3-shape! fact)
      (case (:sandbox-fact-kind fact)
      :full-snapshot
      (let [sandbox (:sandbox-snapshot fact)
            expected (:resulting-state-hash fact)
            actual (sandbox-hash sandbox)]
        (when-not (hash-matches? expected sandbox)
          (throw (ex-info "researcher sandbox full snapshot hash mismatch"
                          {:revision (:revision fact)
                           :expected expected
                           :actual actual})))
        (-> fact
            (dissoc :sandbox-fact-kind :resulting-state-hash
                    :sandbox-snapshot :predecessor-revision
                    :predecessor-state-hash :sandbox-puts :sandbox-deletes)
            (assoc :version 2 :sandbox-vars sandbox)))
      :sandbox-delta
      (do
        (when-not prior-resume-state
          (throw (ex-info "researcher sandbox delta is missing its predecessor"
                          {:revision (:revision fact)})))
        (let [prior-revision (:revision prior-resume-state)
              prior-sandbox (:sandbox-vars prior-resume-state)
              prior-hash (sandbox-hash prior-sandbox)]
          (when-not (= (dec (:revision fact))
                       (:predecessor-revision fact)
                       prior-revision)
            (throw (ex-info "researcher sandbox delta predecessor revision mismatch"
                            {:revision (:revision fact)
                             :predecessor-revision
                             (:predecessor-revision fact)
                             :actual-prior-revision prior-revision})))
          (when-not (hash-matches? (:predecessor-state-hash fact) prior-sandbox)
            (throw (ex-info "researcher sandbox delta predecessor hash mismatch"
                            {:revision (:revision fact)
                             :expected (:predecessor-state-hash fact)
                             :actual prior-hash})))
          (let [sandbox (merge (apply dissoc prior-sandbox
                                      (:sandbox-deletes fact))
                               (:sandbox-puts fact))
                actual (sandbox-hash sandbox)]
            (when-not (hash-matches? (:resulting-state-hash fact) sandbox)
              (throw (ex-info "researcher sandbox delta resulting hash mismatch"
                              {:revision (:revision fact)
                               :expected (:resulting-state-hash fact)
                               :actual actual})))
            (-> fact
                (dissoc :sandbox-fact-kind :resulting-state-hash
                        :sandbox-snapshot :predecessor-revision
                        :predecessor-state-hash :sandbox-puts :sandbox-deletes)
                (assoc :version 2 :sandbox-vars sandbox)))))
      (throw (ex-info "unsupported researcher sandbox resume fact kind"
                      {:revision (:revision fact)
                       :kind (:sandbox-fact-kind fact)}))))
    fact))

(defn hydrate-latest
  "Hydrate from the newest V3 full snapshot, or from the beginning of a
   legacy-only stream. Earlier facts cannot affect state after a verified full
   snapshot and are deliberately excluded from delta replay."
  [facts]
  (let [facts (vec facts)
        snapshot-index
        (last (keep-indexed
               (fn [index fact]
                 (when (and (= 3 (:version fact))
                            (= :full-snapshot (:sandbox-fact-kind fact)))
                   index))
               facts))
        replay-facts (if snapshot-index
                       (subvec facts snapshot-index)
                       facts)]
    (reduce researcher-resume-state-hydrate nil replay-facts)))

(defonce context-fn (atom nil))

(defn register-context-fn!
  "Register `f`, a function from tenant id to that tenant's ORC context (or nil
   when unknown). Replaces any earlier registration. Returns a no-arg function
   that removes this registration (only if it is still the current one)."
  [f]
  (reset! context-fn f)
  (fn [] (compare-and-set! context-fn f nil)))

;; --- Rlm dsl ---

(defn- validate-tree!
  "Validate the entire vector grammar before transforming any node.
   Paths are vector indexes in the original emitted tree, not execution IDs."
  [tree path]
  (let [fail! (fn [expected prefix]
                (throw (ex-info (str (when prefix (str prefix "; "))
                                     "Invalid emitted tree at " (pr-str path)
                                     ": expected " expected)
                                {:node-path path :expected expected :tree tree
                                 :preflight-rejected? true
                                 :failure-kind :invalid-emitted-tree})))]
    (when (nil? tree)
      (fail! "a node vector" "Cannot transform nil tree"))
    (when-not (vector? tree)
      (fail! "a node vector" nil))
    (let [tag (first tree)
          fixed-arity ({:llm 1 :code 1 :final 1 :chunk-document 1
                        :aggregate 1 :map-each 2} tag)]
      (cond
        (#{:sequence :parallel} tag)
        (doseq [i (range 1 (count tree))]
          (validate-tree! (nth tree i) (conj path i)))

        fixed-arity
        (do
          (when-not (= (inc fixed-arity) (count tree))
            (fail! (if (= 1 fixed-arity)
                     "exactly 1 options map"
                     "exactly 1 options map and 1 child node") nil))
          (when-not (map? (second tree))
            (fail! "an options map at operand 1" nil))
          (when (= :map-each tag)
            (validate-tree! (nth tree 2) (conj path 2))))

        :else (fail! "a recognized node tag"
                     (str "Unknown node type: " tag))))))

(defn validate-emitted-tree!
  "Validate the existing emitted vector grammar before quoted source becomes
   executable. Returns the original data tree; adds no node or option support."
  [tree]
  (validate-tree! tree [])
  tree)

(defn- opts-map->keyword-args
  "Convert an options map to a flat keyword argument list.
   {:instruction \"foo\" :reads [:a]} -> [:instruction \"foo\" :reads [:a]]"
  [opts]
  (mapcat (fn [[k v]] [k v]) opts))

(def ^:private default-llm-retry
  "Default retry configuration for LLM nodes.
   3 attempts with exponential-ish backoff: 1s, 2s, 4s"
  {:max-attempts 3
   :backoff-ms [1000 2000 4000]})

(defn- add-default-retry
  "Add default retry config to opts if not already present."
  [opts]
  (if (:retry opts)
    opts
    (assoc opts :retry default-llm-retry)))

(defn- transform-node
  "Transform S-expr RLM DSL to canonical ORC DSL.

   Input: [:sequence [:llm {...}] [:final {...}]]
   Output: (sheet/sequence (sheet/llm ...) (final! ...))

   Supported node types:
   - :sequence - Sequential execution
   - :llm - LLM call node
   - :map-each - Parallel iteration
   - :final - Terminal output
   - :chunk-document - Document chunking helper
   - :aggregate - Result aggregation helper
   - :parallel - Parallel execution"
  [tree]
  (when (nil? tree)
    (throw (ex-info "Cannot transform nil tree" {:tree tree})))

  (let [[node-type & args] tree]
    (case node-type
      :sequence
      (let [children (vec args)]
        (apply list 'sheet/sequence (mapv transform-node children)))

      :llm
      (let [opts (first args)]
        (apply list 'sheet/llm (opts-map->keyword-args opts)))

      :map-each
      (let [opts (first args)
            child (second args)
            transformed-child (transform-node child)]
        (apply list 'sheet/map-each (concat (opts-map->keyword-args opts)
                                            [transformed-child])))

      :chunk-document
      (let [{:keys [from size]} (first args)
            output-key (:into (first args))
            chunk-fn (fn [{:keys [inputs]}]
                       (let [doc (get inputs from)
                             chunk-size (or size 5000)
                             doc-len (count doc)
                             chunks (vec (loop [i 0 result []]
                                           (if (>= i doc-len)
                                             result
                                             (recur (+ i chunk-size)
                                                    (conj result (subs doc i (min (+ i chunk-size) doc-len)))))))]
                         {output-key chunks}))]
        (list 'sheet/code
              :reads [from]
              :writes [output-key]
              :output-schemas {output-key [:vector :string]}
              :fn chunk-fn))

      :aggregate
      (let [{:keys [from writes output-schemas]} (first args)
            aggregate-fn (fn [{:keys [inputs]}]
                           (let [results (get inputs from)
                                 merged (reduce (fn [acc result-map]
                                                  (reduce-kv (fn [a k v]
                                                               (update a k (fnil conj []) v))
                                                             acc
                                                             result-map))
                                                {}
                                                results)]
                             (if (and (= 1 (count writes))
                                      (not (contains? merged (first writes))))
                               {(first writes) merged}
                               merged)))]
        (cond-> (list 'sheet/code
                      :reads [from]
                      :writes writes
                      :fn aggregate-fn)
          output-schemas (concat (list :output-schemas output-schemas))))

      :parallel
      (let [children (vec args)]
        (apply list 'sheet/parallel (mapv transform-node children)))

      :final
      (let [opts (first args)]
        (list 'final! opts))

      :code
      (let [{:keys [reads writes tool-caller-fn tool-contracts output-schemas] :as opts} (first args)
            fn-ref (:fn opts)]
        (when-not (or (and (string? fn-ref) (seq fn-ref))
                      (fn? fn-ref))
          (throw (ex-info ":code node missing required :fn (qualified-symbol string or inline function)"
                          {:node-type :code :tree tree :opts opts})))
        (cond-> (list 'sheet/code
                      :fn fn-ref
                      :reads reads
                      :writes writes)
          tool-caller-fn (concat (list :tool-caller-fn tool-caller-fn))
          (seq tool-contracts) (concat (list :tool-contracts tool-contracts))
          output-schemas (concat (list :output-schemas output-schemas))))

      (throw (ex-info (str "Unknown node type: " node-type)
                      {:node-type node-type :tree tree})))))

(defn rlm-dsl->orc-dsl
  "Validate the complete emitted vector tree, then transform to canonical DSL.
   Fixed nodes consume exactly their documented operands; composite children
   remain variadic. Option values are left to their existing contract checks."
  [tree]
  (validate-emitted-tree! tree)
  (transform-node tree))

;; --- Streaming ---

;; =============================================================================
;; Constants
;; =============================================================================

(def default-buffer 4096)

(def default-ttl-ms (* 60 60 1000))

(def ^:private value-size-limit 16384)

(def ^:private tapped-event-types
  #{:sheet/tree-tick-started
    :sheet/node-execution-started
    :sheet/node-execution-completed
    :sheet/sequence-progress-updated
    :sheet/map-each-progress-updated
    :sheet/tree-tick-completed
    :sheet/tick-cancelled
    :rlm/researcher-iteration-recorded
    :rlm/tree-generated})

(def ^:private streaming-map-each-index-key
  :ai.obney.orc.orc-service.core.todo-processors/map-each-index)

(def ^:private streaming-map-each-parent-key
  :ai.obney.orc.orc-service.core.todo-processors/map-each-parent)

;; =============================================================================
;; Registries
;; =============================================================================

(defonce subscriptions (atom {}))

(defonce ^:private streaming-lineage (atom {}))

(defonce ^:private taps (atom {}))

;; =============================================================================
;; Lineage
;; =============================================================================

(defn descendants-of
  "All known descendant tick-ids of tick-id (transitive), from the
   in-memory lineage registry."
  [tick-id]
  (let [children-by-parent (reduce-kv (fn [acc c p] (update acc p (fnil conj #{}) c))
                                      {} @streaming-lineage)]
    (loop [frontier [tick-id]
           acc #{}]
      (if-let [t (first frontier)]
        (let [children (remove acc (get children-by-parent t))]
          (recur (into (rest frontier) children) (into acc children)))
        acc))))

(defn- subs-covering
  "Subscriptions whose :tick-ids include tick-id."
  [tick-id]
  (vals (into {} (filter (fn [[_ sub]] (contains? (:tick-ids sub) tick-id)) @subscriptions))))

(defn wanted?
  "Cheap check used by executors before building ephemeral events:
   is anyone streaming this tick?"
  [tick-id]
  (boolean (some (fn [sub] (contains? (:tick-ids sub) tick-id))
                 (vals @subscriptions))))

(defn delta-config
  "When some subscription covering tick-id opted into LLM token deltas
   (:llm-deltas? true), returns {:fields? true :raw? bool}; nil otherwise.
   Drives the Stage 2 streaming branch in execute-ai."
  [tick-id]
  (let [wanting (filter #(and (contains? (:tick-ids %) tick-id)
                              (get-in % [:opts :llm-deltas?]))
                        (vals @subscriptions))]
    (when (seq wanting)
      {:fields? true
       :raw? (boolean (some #(get-in % [:opts :raw-deltas?]) wanting))})))

(defn link-child!
  "Record that child-tick-id was spawned under parent-tick-id. Expands any
   subscription covering the parent to also cover the child and informs it
   with a :child-tick-linked envelope. Call BEFORE dispatching the child
   tick so no child events are missed."
  [parent-tick-id child-tick-id]
  (swap! streaming-lineage assoc child-tick-id parent-tick-id)
  (doseq [[sub-id sub] @subscriptions
          :when (contains? (:tick-ids sub) parent-tick-id)]
    (swap! subscriptions
           (fn [m]
             (if (contains? m sub-id)
               (update-in m [sub-id :tick-ids] conj child-tick-id)
               m)))
    (async/put! (:src-chan sub)
                {:orc.stream/type :child-tick-linked
                 :tick-id parent-tick-id
                 :parent-tick-id parent-tick-id
                 :child-tick-id child-tick-id})))

(defn unlink-tick!
  "Drop lineage entries rooted at tick-id. Called on subscription close for
   the root; safe to call redundantly."
  [tick-id]
  (let [ds (descendants-of tick-id)]
    (swap! streaming-lineage (fn [l] (apply dissoc l ds)))))

(defn emit!
  "Push an ephemeral stream event for tick-id into every covering
   subscription. The map must carry :orc.stream/type; the router adds
   :seq/:ts/:root-tick-id. No-op when nobody is subscribed. Never blocks:
   src-chans are sliding-buffered."
  [tick-id m]
  (doseq [sub (subs-covering tick-id)]
    (async/put! (:src-chan sub) (assoc m :tick-id tick-id))))

;; =============================================================================
;; Value truncation
;; =============================================================================

(defn truncate-value
  "Cap a single value at value-size-limit when printed. Oversized values are
   replaced with {:orc.stream/truncated true :preview <string> :full-size n};
   the raw value is always recoverable from the event store. Public so
   executors can cap ephemeral payloads at the emission site."
  [v]
  (let [s (if (string? v) v (pr-str v))]
    (if (> (count s) value-size-limit)
      {:orc.stream/truncated true
       :preview (subs s 0 value-size-limit)
       :full-size (count s)}
      v)))

(defn truncate-values [m]
  (when m
    (reduce-kv (fn [acc k v] (assoc acc k (truncate-value v))) {} m)))

;; =============================================================================
;; Envelope normalization
;; =============================================================================

(defn map-each-context
  "Extract map-each correlation from event :inputs without leaking the
   internal namespaced keys."
  [inputs]
  (let [idx (get inputs streaming-map-each-index-key)
        parent (get inputs streaming-map-each-parent-key)]
    (when (and (some? idx) (some? parent))
      {:parent parent :index idx})))

;; =============================================================================
;; Subscription lifecycle
;; =============================================================================

(defn close-subscription!
  "Idempotent: emit the terminal :stream-closed through the router (via the
   ::close sentinel) exactly once."
  [sub-id reason]
  (when-let [sub (get @subscriptions sub-id)]
    (async/put! (:src-chan sub) {:ai.obney.orc.orc-service.core.streaming/close reason})))

(defn finalize-subscription!
  "Called by the router after it has emitted :stream-closed. Tears down
   channels and registry entries."
  [sub-id]
  (when-let [sub (get @subscriptions sub-id)]
    (swap! subscriptions dissoc sub-id)
    (unlink-tick! (:root-tick-id sub))
    (async/close! (:src-chan sub))
    (async/close! (:events-ch sub))))

(defn ephemeral-for-sub
  "Per-subscription filtering of ephemeral envelopes: token-delta events are
   delivered only to subscriptions that opted in (delta-config is a per-tick
   union that ACTIVATES streaming; delivery still honors each subscriber)."
  [sub m]
  (case (:orc.stream/type m)
    :llm-fields (when (get-in sub [:opts :llm-deltas?]) m)
    :llm-raw-delta (when (get-in sub [:opts :raw-deltas?]) m)
    m))

;; =============================================================================
;; Pubsub tap
;; =============================================================================

(defn ensure-tap!
  "Start (once per pubsub instance) the always-draining tap that forwards
   matching durable events into covering subscriptions. One channel covers
   every durable event type so their published order cannot be changed by
   competing tap loops. The channel is sliding-buffered so a burst can never
   park async/pub distribution."
  [ps]
  (when (and ps (not (contains? @taps ps)))
    (locking taps
      (when-not (contains? @taps ps)
        (let [ch (async/chan (async/sliding-buffer 1024))]
          (doseq [event-type tapped-event-types]
            (pubsub/sub ps {:topic event-type :sub-chan ch}))
          (async/thread
            (loop []
              (when-let [event (async/<!! ch)]
                (try
                  (doseq [sub (subs-covering
                               (if (= :rlm/tree-generated (:event/type event))
                                 (:execution-id event)
                                 (:tick-id event)))
                          :when (or (nil? (:tenant-id sub))
                                    (nil? (:grain/tenant-id event))
                                    (= (:tenant-id sub) (:grain/tenant-id event)))]
                    (async/put! (:src-chan sub) event))
                  (catch Exception _ nil))
                (recur))))
          (swap! taps assoc ps [ch]))))))

(defn shutdown-taps!
  "Close all tap channels. Test/REPL hygiene only — taps are otherwise
   process-lifetime."
  []
  (doseq [[_ chans] @taps, ch chans]
    (async/close! ch))
  (reset! taps {}))

(defn reset-all!
  "Test/REPL hygiene: synchronously tear down every subscription (without
   emitting :stream-closed), clear lineage, and shut down taps."
  []
  (doseq [sub-id (keys @subscriptions)]
    (finalize-subscription! sub-id))
  (reset! streaming-lineage {})
  (shutdown-taps!))

;; =============================================================================
;; Public API
;; =============================================================================

(defn resolve-pubsub [context]
  (or (:event-pubsub context)
      (get-in (:event-store context) [:config :event-pubsub])))

;; --- Telemetry exporter ---

(defn- event-id [event]
  (:event/id event))

(defn- accepted? [response id]
  (contains? (set (:accepted-ids response)) id))

(defn start!
  "Start an exporter subscribed to `event-types`.

   `export-fn` receives a one-element vector and returns
   `{:accepted-ids #{event-id ...}}`. Throws and missing acknowledgements are
   retried up to `max-attempts`. Queue overflow and exhausted retries are
   visible in `stats`. `event-predicate` optionally scopes events before they
   enter the queue or affect statistics. Pub/sub callbacks only use non-blocking
   queue offer."
  [event-pubsub event-types export-fn
   & {:keys [capacity max-attempts event-predicate]
      :or {capacity 256 max-attempts 3 event-predicate (constantly true)}}]
  {:pre [(pos? capacity) (pos? max-attempts) (seq event-types)]}
  (let [queue (java.util.concurrent.ArrayBlockingQueue. capacity)
        running? (atom true)
        stats (atom {:offered 0 :accepted 0 :retried 0 :dropped 0
                     :failures 0 :max-occupancy 0 :accepted-ids []
                     :dropped-ids []})
        chans (mapv (fn [event-type]
                      (let [ch (async/chan (async/sliding-buffer capacity))]
                        (pubsub/sub event-pubsub {:topic event-type :sub-chan ch})
                        (async/go-loop []
                          (when-let [event (async/<! ch)]
                            (when (event-predicate event)
                              (let [offered? (.offer queue event)]
                                (swap! stats
                                       (fn [s]
                                         (cond-> (-> s
                                                     (update :offered inc)
                                                     (update :max-occupancy max (.size queue)))
                                           (not offered?)
                                           (-> (update :dropped inc)
                                               (update :dropped-ids conj (event-id event))))))))
                            (recur)))
                        ch))
                    event-types)
        worker (Thread.
                (fn []
                  (while (or @running? (not (.isEmpty queue)))
                    (when-let [event (.poll queue 50 java.util.concurrent.TimeUnit/MILLISECONDS)]
                      (loop [attempt 1]
                        (let [response (try
                                         (export-fn [event])
                                         (catch Throwable _
                                           (swap! stats update :failures inc)
                                           nil))
                              id (event-id event)]
                          (if (accepted? response id)
                            (swap! stats (fn [s]
                                           (-> s
                                               (update :accepted inc)
                                               (update :accepted-ids conj id))))
                            (if (< attempt max-attempts)
                              (do (swap! stats update :retried inc)
                                  (recur (inc attempt)))
                              (swap! stats (fn [s]
                                             (-> s
                                                 (update :dropped inc)
                                                 (update :dropped-ids conj id))))))))))))]
    (.setName worker "orc-telemetry-exporter")
    (.setDaemon worker true)
    (.start worker)
    {:queue queue :running? running? :stats stats :channels chans :worker worker
     :capacity capacity}))

(defn stats
  "Return an immutable exporter status snapshot."
  [exporter]
  (assoc @(:stats exporter)
         :occupancy (.size ^java.util.concurrent.ArrayBlockingQueue (:queue exporter))
         :capacity (:capacity exporter)
         :running? @(:running? exporter)))

(defn stop!
  "Stop accepting events and drain queued work within `timeout-ms`.
   Returns the final status with `:stopped?`."
  [exporter & {:keys [timeout-ms] :or {timeout-ms 2000}}]
  (doseq [ch (:channels exporter)] (async/close! ch))
  (reset! (:running? exporter) false)
  (.join ^Thread (:worker exporter) timeout-ms)
  (let [stopped? (not (.isAlive ^Thread (:worker exporter)))]
    (when-not stopped? (.interrupt ^Thread (:worker exporter)))
    (assoc (stats exporter) :stopped? stopped?)))

(defn command-envelope
  [context command-name trace]
  (merge {:command/id ((or (:orc/new-command-id! context) random-uuid))
          :command/timestamp ((or (:orc/now! context) time/now))
          :command/name command-name}
         trace))

;; --- Commands ---

;; =============================================================================
;; Auth Predicate
;; =============================================================================

(defn commands-authenticated?
  "Authorization predicate - checks if auth-claims are present in context."
  [ctx]
  (some? (:auth-claims ctx)))

(defn- schema-error
  "Return a stable explanation when value violates schema, otherwise nil."
  [key schema value]
  (try
    (when-not (m/validate schema value)
      (str "Blackboard schema validation failed for " (pr-str key) ": "
           (me/humanize (m/explain schema value))))
    (catch Exception e
      (str "Blackboard schema validation failed for " (pr-str key)
           " because its schema is invalid: " (.getMessage e)))))

(defn invalid-seed-input
  "Validate only caller-supplied keys. Blackboard defaults are already durable
   values and are not reinterpreted as required execution inputs here."
  [snapshot inputs]
  (some (fn [[key value]]
          (when-let [schema (get-in snapshot [:blackboard-entries key :schema])]
            (schema-error key schema value)))
        inputs))

(defn snapshot-schema-feedback
  [snapshot]
  (when-let [violations (seq (schema-map-violations
                              (:blackboard-schema snapshot)))]
    (feedback violations)))

;; =============================================================================
;; Blackboard Commands
;; =============================================================================

(defn valid-malli-schema?
  "Check if a value is a valid Malli schema."
  [schema]
  (try
    (m/schema schema)
    true
    (catch Exception _
      false)))

(defn schema-specificity-feedback
  [key schema]
  (when-let [violations (seq (map #(assoc % :key key)
                                  (ai.obney.orc.orc-service.core.logic/violations schema)))]
    (feedback violations)))

(defn validate-against-schema
  "Validate a value against a Malli schema.
   Returns nil if valid, or an error map if invalid."
  [schema value]
  (try
    (let [malli-schema (m/schema schema)]
      (if (m/validate malli-schema value)
        nil
        {:error :validation-failed
         :message (me/humanize (m/explain malli-schema value))}))
    (catch Exception e
      {:error :invalid-schema
       :message (.getMessage e)})))

(def ^:private default-cancellation-reason "tick cancelled")

(defn normalize-cancellation-reason [reason]
  (if (and (string? reason) (not (str/blank? reason)))
    reason
    default-cancellation-reason))

(def trace-publication-event-types
  #{:sheet/execution-traced :sheet/execution-trace-refreshed})

(defn execution-trace-event
  [event-type {:keys [trace-id sheet-id parent-trace-id correlation-id root-trace-id child-trace-ids
                      version-number started-at completed-at
                      duration-ms status configured-max-ticks consumed-ticks terminal-reason
                      input-snapshot output-snapshot node-traces researcher-iterations
                      researcher-events source-event-count error]}]
  (->event {:type event-type
            :tags (cond-> #{[:sheet sheet-id] [:trace trace-id] [:tick trace-id]}
                    correlation-id (conj [:correlation correlation-id]))
            :body (cond-> {:trace-id trace-id
                           :sheet-id sheet-id
                           :root-trace-id root-trace-id
                           :child-trace-ids child-trace-ids
                           :started-at started-at
                           :completed-at completed-at
                           :duration-ms duration-ms
                           :status status
                           :input-snapshot input-snapshot
                           :output-snapshot output-snapshot
                           :node-traces node-traces}
                    (seq researcher-iterations)
                    (assoc :researcher-iterations researcher-iterations)
                    (seq researcher-events)
                    (assoc :researcher-events researcher-events)
                    parent-trace-id (assoc :parent-trace-id parent-trace-id)
                    correlation-id (assoc :correlation-id correlation-id)
                    version-number (assoc :version-number version-number)
                    configured-max-ticks (assoc :configured-max-ticks configured-max-ticks)
                    consumed-ticks (assoc :consumed-ticks consumed-ticks)
                    terminal-reason (assoc :terminal-reason terminal-reason)
                    (some? source-event-count) (assoc :source-event-count source-event-count)
                    error (assoc :error error))}))

;; =============================================================================
;; Versioning Commands
;; =============================================================================

(defn commands-build-node-tree
  "Convert flat nodes map to nested tree structure for snapshots.
   Preserves original node IDs for accurate structural diffing."
  [nodes-by-id root-id]
  (when root-id
    (let [node (get nodes-by-id root-id)]
      (when node
        (cond-> {:id (:id node)
                 :source-node-id (:id node)
                 :type (:type node)
                 :name (:name node)}
          true (merge (select-keys node [:provider :fallback-models :fallback-policy :options]))
          (:model-fallback-policy node) (assoc :model-fallback-policy (:model-fallback-policy node))
          (= :leaf (:type node))
          (merge (cond-> {}
                   (:executor node) (assoc :executor (:executor node))
                   (:model node) (assoc :model (:model node))
                   (:options node) (assoc :options (:options node))
                   (:fn node) (assoc :fn (:fn node))
                   (:instruction node) (assoc :instruction (:instruction node))
                   (seq (:reads node)) (assoc :reads (:reads node))
                   (seq (:writes node)) (assoc :writes (:writes node))
                   (:tools node) (assoc :tools (:tools node))
                   (:retry node) (assoc :retry (:retry node))
                   (= :decision (:executor node))
                   (merge (cond-> {}
                            (:options-from node) (assoc :options-from (:options-from node))
                            (:bands-from node) (assoc :bands-from (:bands-from node))
                            (:min-confidence node) (assoc :min-confidence (:min-confidence node))
                            (some? (:abstain node)) (assoc :abstain (:abstain node))
                            (:options node) (assoc :options (:options node))))))
          (= :condition (:type node))
          (merge (cond-> {}
                   (:check node) (assoc :check (:check node))))
          (= :llm-condition (:type node))
          (merge (cond-> {}
                   (:instruction node) (assoc :instruction (:instruction node))
                   (seq (:reads node)) (assoc :reads (:reads node))
                   (:model node) (assoc :model (:model node))))
          (= :repl-researcher (:type node))
          (merge (select-keys node [:instruction :reads :writes :model :options :rlm
                                    :mcp-tools :browser-tools :tool-contracts :tool-caller-fn
                                    :max-iterations :context :judges]))
          (= :parallel (:type node))
          (merge {:success-policy (or (:success-policy node) :all)
                  :failure-policy (or (:failure-policy node) :any)})
          (= :map-each (:type node))
          (merge (cond-> {}
                   (:source-key node) (assoc :source-key (:source-key node))
                   (:item-key node) (assoc :item-key (:item-key node))
                   (:output-key node) (assoc :output-key (:output-key node))
                   (:max-concurrency node) (assoc :max-concurrency (:max-concurrency node))
                   (some? (:preserve-failures? node)) (assoc :preserve-failures? (:preserve-failures? node))))
          (= :delegate (:type node))
          (merge (cond-> {:target-sheet-id (:target-sheet-id node)
                          :reads (vec (or (:reads node) []))
                          :writes (vec (or (:writes node) []))}
                   (:delegate-timeout-ms node) (assoc :delegate-timeout-ms (:delegate-timeout-ms node))
                   (:delegate-max-ticks node) (assoc :delegate-max-ticks (:delegate-max-ticks node))
                   (some? (:inherit-ontology? node)) (assoc :inherit-ontology? (:inherit-ontology? node))))
          (seq (:children-ids node))
          (assoc :children
                 (vec (keep #(commands-build-node-tree nodes-by-id %) (:children-ids node)))))))))

(defn researcher-classification-commit-cas
  [tick-id node-id ownership-epoch]
  {:types #{:rlm/researcher-frontier-claimed
            :sheet/node-execution-completed
            :sheet/tick-cancelled
            :sheet/tree-tick-completed
            :ontology/task-classified
            :ontology/task-classification-deferred
            :rlm/researcher-classification-expired}
   :tags #{[:tick tick-id]}
   :predicate-fn
   (fn [existing]
     (let [events (into [] existing)
           latest-epoch
           (reduce (fn [latest event]
                     (if (and (= :rlm/researcher-frontier-claimed
                                  (:event/type event))
                              (= node-id (:node-id event)))
                       (max latest (:ownership-epoch event))
                       latest))
                   0
                   events)
           terminal?
           (some (fn [event]
                   (or (= :sheet/tick-cancelled (:event/type event))
                       (and (= :sheet/tree-tick-completed (:event/type event))
                            (not= :running (:root-status event)))
                       (and (= :sheet/node-execution-completed
                               (:event/type event))
                            (= node-id (:node-id event))
                            (= :terminal (:completion-kind event)))))
                 events)
           already-settled?
           (some (fn [event]
                   (or
                    (and (contains? #{:ontology/task-classified
                                      :ontology/task-classification-deferred}
                                    (:event/type event))
                         (= node-id (:source-node-id event))
                         (= ownership-epoch
                            (:researcher-ownership-epoch event)))
                    (and (= :rlm/researcher-classification-expired
                            (:event/type event))
                         (= node-id (:node-id event))
                         (= ownership-epoch (:ownership-epoch event)))))
                 events)]
       (and (= ownership-epoch latest-epoch)
            (not terminal?)
            (not already-settled?))))})

(defn- cas-filter-events
  "Recreate one Grain CAS guard's filtered view from a wider transactional
   view. Tags retain Grain's all-tags (set-subset) semantics."
  [events {:keys [types tags]}]
  (filter
   (fn [event]
     (and (or (nil? types)
              (contains? types (:event/type event)))
          (or (nil? tags)
              (every? (:event/tags event) tags))))
   events))

(defn compose-researcher-classification-cas
  "Compose the campaign fence with every prepared effect's own CAS.

   Grain evaluates one filtered CAS view per append. Classification spans the
   tick stream and, for a fresh mint, a disjoint ontology claim-target stream,
   so the composed guard reads the union of their event types transactionally
   and recreates each child view before invoking its predicate."
  [guards]
  (let [guards (vec guards)
        all-type-filtered? (every? :types guards)
        selected-types (when all-type-filtered?
                         (into #{} (mapcat :types) guards))]
    (cond->
     {:predicate-fn
      (fn [existing]
        (let [events (into [] existing)]
          (every?
           (fn [{:keys [predicate-fn] :as guard}]
             (predicate-fn (cas-filter-events events guard)))
           guards)))}
      selected-types (assoc :types selected-types))))

(def ^:private commands-researcher-classification-outcome-command-names
  #{:ontology/assign-task-class
    :ontology/record-task-classification-deferral})

(def ^:private researcher-classification-effect-priority
  {
   :ontology/mint-domain-child 5
   :ontology/record-claim-deltas 10
   :sheet/record-injection 20
   :ontology/assign-task-class 100
   :ontology/record-task-classification-deferral 100})

(def ^:private commands-domain-mint-assignment-vias
  #{:mint-domain-child :mint-sibling-domain-child})

(defn- commands-domain-axis-deferral?
  "RS-3: the domain-axis deferral recorded BESIDE a structural assignment
   (the wedge's :domain-classification-deferral effect) — the deferral
   command with :fallback-source :domain-coverage. By name it looks like a
   second outcome; it is the domain axis of the same outcome."
  [effect]
  (and (= :ontology/record-task-classification-deferral (:command/name effect))
       (= :domain-coverage (:fallback-source effect))))

(defn- researcher-classification-effect-order
  "One order on both paths (spec ClassificationEffectsCommitAsOneBoundedSet):
   mint, capture, injection, assignment, then the domain-axis deferral that
   rides the assignment."
  [effect]
  (if (commands-domain-axis-deferral? effect)
    110
    (get researcher-classification-effect-priority (:command/name effect) 50)))

(defn researcher-classification-commit-error
  [{:keys [sheet-id tick-id node-id ownership-epoch effects]}]
  (cond
    (not (and (uuid? sheet-id) (uuid? tick-id) (uuid? node-id)))
    "Researcher classification commit requires sheet, tick, and node identifiers"

    (not (and (integer? ownership-epoch) (pos? ownership-epoch)))
    "Researcher classification commit requires a positive ownership epoch"

    (not (vector? effects))
    "Researcher classification commit requires an effects vector"

    (not-every? map? effects)
    "Researcher classification commit effects must be command maps"

    :else
    (let [outcomes
          (filterv
           #(and (contains? commands-researcher-classification-outcome-command-names
                            (:command/name %))
                 (not (commands-domain-axis-deferral? %)))
           effects)
          outcome (first outcomes)
          injections
          (filterv #(= :sheet/record-injection (:command/name %)) effects)
          convergence-captures
          (filterv #(= :ontology/record-claim-deltas (:command/name %)) effects)
          convergence-capture (first convergence-captures)
          assignment? (= :ontology/assign-task-class (:command/name outcome))
          fresh-mint-assignment?
          (and assignment? (true? (:was-fresh-mint? outcome)))
          domain-mint-assignment?
          (and assignment?
               (contains? commands-domain-mint-assignment-vias (:assigned-via outcome)))
          mints (filterv #(= :ontology/mint-domain-child (:command/name %)) effects)
          mint (first mints)
          domain-deferrals (filterv commands-domain-axis-deferral? effects)
          domain-deferral (first domain-deferrals)
          bound-to-campaign?
          (fn [effect]
            (= [sheet-id tick-id node-id]
               [(:source-sheet-id effect)
                (:source-tick-id effect)
                (:source-node-id effect)]))]
      (cond
        (not= 1 (count outcomes))
        "Researcher classification commit requires exactly one outcome"

        (not (and (bound-to-campaign? outcome)
                  (= ownership-epoch (:researcher-ownership-epoch outcome))))
        "Researcher classification outcome does not belong to the committing campaign epoch"

        (> (count injections) 1)
        "Researcher classification commit accepts at most one injection record"

        (not-every? #(= [sheet-id tick-id node-id]
                        [(:sheet-id %) (:tick-id %) (:node-id %)])
                    injections)
        "Researcher classification injection does not belong to the committing campaign"

        (> (count convergence-captures) 1)
        "Researcher classification commit accepts at most one convergence capture"

        (and fresh-mint-assignment?
             (not= 1 (count convergence-captures)))
        "Fresh-mint classification requires one convergence capture"

        (and (not fresh-mint-assignment?) (seq convergence-captures))
        "Only a fresh-mint assignment may publish convergence capture"

        (and fresh-mint-assignment?
             (or (not= :tree-class (:granularity convergence-capture))
                 (not= (:assigned-tree-id outcome)
                       (:target-identifier convergence-capture))))
        "Researcher convergence capture does not belong to the assigned tree class"

        (> (count mints) 1)
        "Researcher classification commit accepts at most one domain-child mint"

        (and domain-mint-assignment? (not= 1 (count mints)))
        "Domain-child classification requires its domain-child mint"

        (and (not domain-mint-assignment?) (seq mints))
        "Only a domain-child assignment may publish a domain-child mint"

        (and domain-mint-assignment?
             (or (not (bound-to-campaign? mint))
                 (not= (:assigned-tree-id outcome) (:child-tree-id mint))))
        "Researcher domain-child mint does not belong to the assigned domain child"

        (> (count domain-deferrals) 1)
        "Researcher classification commit accepts at most one domain-axis deferral"

        (and (some? domain-deferral)
             (or (not assignment?)
                 (not (bound-to-campaign? domain-deferral))
                 (not= ownership-epoch
                       (:researcher-ownership-epoch domain-deferral))))
        "Researcher domain-axis deferral does not belong to the committing assignment"

        :else nil))))

(defn canonical-researcher-classification-effects
  [effects]
  (->> effects
       (sort-by researcher-classification-effect-order)
       vec))

;; --- Budget facts ---

(def accounting-version 1)

(defn campaign-options [start]
  (let [options (:options start)
        fields [:researcher-campaign-sheet-id :researcher-campaign-tick-id :researcher-campaign-node-id]
        ids (mapv #(get options %) fields)
        advertised? (some #(contains? options %) (conj fields :researcher-ownership-epoch :researcher-iteration-index))]
    (when (and advertised?
               (not (and (every? uuid? ids)
                         (or (nil? (:researcher-campaign-exec-context options))
                             (map? (:researcher-campaign-exec-context options)))
                         (or (not (contains? options :researcher-ownership-epoch))
                             (pos-int? (:researcher-ownership-epoch options)))
                         (or (not (contains? options :researcher-iteration-index))
                             (nat-int? (:researcher-iteration-index options))))))
      (throw (ex-info "Canonical model campaign metadata is incomplete"
                      {:failure-kind :model-plan-incompatible :reason :emitted-campaign-lineage-unavailable})))
    (when (every? uuid? ids)
      {:sheet-id (nth ids 0) :tick-id (nth ids 1) :node-id (nth ids 2)
       :exec-context (or (:researcher-campaign-exec-context options) {})
       :ownership-epoch (:researcher-ownership-epoch options)
       :iteration-index (:researcher-iteration-index options)
       :parent-tick-id (:parent-tick-id start)})))

(defn campaign-tags [start body]
  (if-let [campaign (campaign-options start)]
    #{(campaign-tag (:sheet-id campaign) (:tick-id campaign) (:node-id campaign))}
    (let [nodes (get-in start [:execution-snapshot :nodes-by-id])
          node-id (:node-id body)
          researchers (if node-id
                        (when (= :repl-researcher (:type (get nodes node-id))) [node-id])
                        (keep (fn [[id node]] (when (= :repl-researcher (:type node)) id)) nodes))]
      (into #{} (map #(campaign-tag (:sheet-id start) (:tick-id start) %) researchers)))))

;; --- Dsl ---

(def ^:private ^UUID sheets-namespace
  "Namespace UUID for generating sheet IDs from workflow names."
  #uuid "a1b2c3d4-e5f6-4789-abcd-ef0123456789")

(defn- uuid->bytes
  "Convert a UUID to a 16-byte array."
  [^UUID uuid]
  (let [bb (ByteBuffer/allocate 16)]
    (.putLong bb (.getMostSignificantBits uuid))
    (.putLong bb (.getLeastSignificantBits uuid))
    (.array bb)))

(defn- bytes->uuid
  "Convert a 16-byte array to a UUID with version 5 and variant bits set."
  [^bytes b]
  (let [bb (ByteBuffer/wrap b)]
    (aset b 6 (unchecked-byte (bit-or (bit-and (aget b 6) 0x0f) 0x50)))
    (aset b 8 (unchecked-byte (bit-or (bit-and (aget b 8) 0x3f) 0x80)))
    (UUID. (.getLong (ByteBuffer/wrap b))
           (.getLong (ByteBuffer/wrap b 8 8)))))

(defn uuid-v5
  "Generate a deterministic UUID v5 from a namespace UUID and a name string.
   Same inputs always produce the same UUID."
  [^UUID namespace-uuid ^String name]
  (let [md (MessageDigest/getInstance "SHA-1")
        namespace-bytes (uuid->bytes namespace-uuid)
        name-bytes (.getBytes name "UTF-8")]
    (.update md namespace-bytes)
    (.update md name-bytes)
    (let [hash (.digest md)
          uuid-bytes (byte-array 16)]
      (System/arraycopy hash 0 uuid-bytes 0 16)
      (bytes->uuid uuid-bytes))))

(defn sheet-id-for-name
  "Generate a deterministic sheet-id from a workflow name."
  [workflow-name]
  (uuid-v5 sheets-namespace workflow-name))

(defn node-id-for-name
  "Generate a deterministic node-id from a sheet-id and node name."
  [sheet-id node-name]
  (uuid-v5 sheet-id node-name))

;; =============================================================================
;; Content Hashing
;; =============================================================================

(defn- unresolved-refs
  "The `:ref` schemas still present in a dereferenced schema. Malli's
   deref-recursive does not walk over refs, so these are exactly the recursive
   references that cannot be inlined."
  [schema]
  (let [acc (volatile! [])]
    (m/walk schema
            (fn [s _ _ _]
              (when (= :ref (m/type s)) (vswap! acc conj s))
              s))
    @acc))

(defn- fresh-ref-id
  "A ref id of the same kind as `id` that is not in `taken`."
  [id taken]
  (let [candidate (fn [n]
                    (cond
                      (keyword? id) (keyword (or (namespace id) "snapshot")
                                             (str (name id) "-" n))
                      (string? id) (str id "-" n)
                      :else (str (pr-str id) "-" n)))]
    (first (remove taken (map candidate (iterate inc 2))))))

(defn- ref-placeholder
  "A `:ref` schema to `id`, carrying the properties of `ref-schema`. Resolves
   to `:any`; it exists so the form can be read back as `[:ref id]`."
  [ref-schema id]
  (let [props (not-empty (m/properties ref-schema))
        registry (mr/composite-registry m/default-registry {id :any})]
    (m/schema (if props [:ref props id] [:ref id]) {:registry registry})))

(defn- effective-schema-form
  "Resolve every registry reference reachable from a Malli schema and return
   the resulting data form. The form is both hashed and persisted, making a
   built workflow a snapshot of the schema registry state it depends on.

   Non-recursive references are inlined. A recursive reference cannot be
   inlined, and deref-recursive leaves it as a bare `[:ref id]` whose defining
   registry is lost, so the form would no longer be a valid schema. Each
   recursive reference's definition is therefore carried in a registry on a
   wrapping `:schema`, closed over every definition reachable from it.

   A reference is identified by its id AND the registry it resolves in, so two
   local registries that define the same id differently stay distinct: the
   first keeps its id and later ones are renamed (`::n` -> `::n-2`) in every
   reference that resolves to them. Registry entries are ordered by printed id
   (ids may be keywords or strings) so the form, and the content hash derived
   from it, is deterministic."
  [schema]
  (try
    (let [top (m/deref-recursive (m/schema schema))]
      (if (empty? (unresolved-refs top))
        (m/form top)
        (let [scopes (atom {})
              taken (atom #{})
              queue (atom [])
              canon! (fn [r]
                       (let [id (m/-ref r)
                             k [id (:registry (m/options r))]]
                         (or (get @scopes k)
                             (let [sid (if (contains? @taken id)
                                         (fresh-ref-id id @taken)
                                         id)]
                               (swap! scopes assoc k sid)
                               (swap! taken conj sid)
                               (swap! queue conj [sid r])
                               sid))))
              rewrite (fn [sch]
                        (m/form
                         (m/walk sch
                                 (fn [s _ children _]
                                   (if (= :ref (m/type s))
                                     (ref-placeholder s (canon! s))
                                     (m/-set-children s children))))))
              top-form (rewrite top)
              registry (loop [registry (sorted-map-by #(compare (pr-str %1) (pr-str %2)))]
                         (if-let [[sid r] (first @queue)]
                           (do (swap! queue subvec 1)
                               (recur (assoc registry sid
                                             (rewrite (m/deref-recursive (m/deref r))))))
                           registry))]
          [:schema {:registry registry} top-form])))
    (catch Exception e
      (throw (ex-info (str "Unable to resolve blackboard schema " (pr-str schema))
                      {:schema schema}
                      e)))))

(defn effective-workflow-definition
  [workflow-def]
  (update workflow-def :blackboard-schema
          (fn [schema-map]
            (into (empty schema-map)
                  (map (fn [[key schema]]
                         [key (effective-schema-form schema)]))
                  schema-map))))

(defn workflow-content-hash
  "Compute a deterministic SHA-256 hash of a workflow definition."
  [workflow-def]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.digest digest (.getBytes (pr-str workflow-def) "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))

(defn- validate-model-config-keys! [config allowed]
  (when (and (contains? config :fallback-models)
             (not-every? allowed (keys config)))
    (throw (ex-info "Unknown configured model node option" {:type :invalid-model-fallback})))
  config)

(defn llm
  "Define an LLM executor leaf node.

   Options:
     :provider - Optional registered provider for this node's primary model
     :fallback-models - Ordered backup maps {:model id :provider optional :options optional};
       [] explicitly disables inherited backups
     :fallback-policy - :temporary-transport (default) or explicit :all-transport
     :model - OpenRouter model ID (e.g., \"google/gemini-2.5-flash\")
     :instruction - Prompt for the LLM
     :reads - Vector of blackboard keys to read (e.g., [:student-profile :programs])
     :writes - Vector of blackboard keys to write (e.g., [:result])
     :retry - {:max-attempts n :backoff-ms [100 500]}
     :judges - Vector of judge names (defined in sheet/judges)
     :context - Ontology context injection config:
                {:problem-type \"problem:Classification\"
                 :include-patterns true
                 :include-failures true
                 :tree-id uuid  ;; for self-learning
                 :self-learning? true}
     :options - Executor options passed through to ORC LLM for this node"
  [name & {:keys [model instruction reads writes retry judges context options provider fallback-models fallback-policy] :as config}]
  (validate-model-config-keys! config #{:model :instruction :reads :writes :retry :judges :context :options :provider :fallback-models :fallback-policy})
  (validate-config!
   (merge (select-keys config [:provider :fallback-models :fallback-policy])
         (cond-> {:node-type :leaf
           :name name
           :executor :ai
           :model model
           :instruction instruction
           :reads (vec reads)
           :writes (vec writes)}
    retry (assoc :retry retry)
    judges (assoc :judges (vec judges))
    context (assoc :context context)
    options (assoc :options options)))))

(defn llm-decision
  "Define a model decision: a data-producing leaf that asks the model to choose
   and writes its answer to ONE declared blackboard key. The key's blackboard
   schema determines the decision: `:boolean` asks whether a proposition holds;
   a finite `[:enum {:descriptions {id description}} id ...]` asks which member
   applies. Options may instead be supplied at run time by a declared read key
   (`:options-from`) holding a vector of `{:id :description}`; the answer key may
   then be `:string`. Any other answer key is rejected when the workflow is built.

   A valid answer is a successful assessment (false and a declared none/other
   option included). A missing, malformed or out-of-set answer fails the node and
   writes nothing.

   Options:
     :provider - Optional registered provider for this node's primary model
     :fallback-models - Ordered backup maps {:model id :provider optional :options optional};
       [] explicitly disables inherited backups
     :fallback-policy - :temporary-transport (default) or explicit :all-transport
     :instruction - What the model is deciding
     :reads - Evidence keys (like every node); must include :options-from's key
     :writes - Exactly one answer key
     :options-from - Optional read key holding run-time `[{:id :description}]`
     :bands-from - Optional read key holding a run-time rubric
                   `{:bands {1 \"desc\" 2 \"desc\" ...} :criterion \"..\" :stance \"..\"}`;
                   the model selects ONE ordered band and the integer band number is
                   written to the (integer) answer key. Exclusive with :options-from;
                   rejects :min-confidence/:abstain.
     :min-confidence - Optional confidence floor; requires :abstain
     :abstain - Option written instead when confidence is below the floor or
                none is reported; must be one of the offered options
     :model - Optional per-node model
     :retry - {:max-attempts n :backoff-ms [100 500]}
     :judges - Vector of judge names (defined in sheet/judges)
     :options - Executor options passed through to ORC LLM for this node"
  [name & {:keys [model instruction reads writes options-from bands-from min-confidence abstain retry judges options provider fallback-models fallback-policy] :as config}]
  (validate-model-config-keys! config #{:model :instruction :reads :writes :options-from :bands-from :min-confidence :abstain :retry :judges :options :provider :fallback-models :fallback-policy})
  (validate-config!
   (merge (select-keys config [:provider :fallback-models :fallback-policy])
         (cond-> {:node-type :leaf
           :name name
           :executor :decision
           :model model
           :instruction instruction
           :reads (vec reads)
           :writes (vec writes)}
    options-from (assoc :options-from options-from)
    bands-from (assoc :bands-from bands-from)
    min-confidence (assoc :min-confidence (double min-confidence))
    (some? abstain) (assoc :abstain abstain)
    retry (assoc :retry retry)
    judges (assoc :judges (vec judges))
    options (assoc :options options)))))

(defn code
  "Define a code executor leaf node.

   Options:
     :fn - Fully-qualified function symbol string
     :reads - Vector of blackboard keys to read (e.g., [:input])
     :writes - Vector of blackboard keys to write (e.g., [:output])
     :retry - {:max-attempts n :backoff-ms [100 500]}
     :judges - Vector of judge names (defined in sheet/judges)
     :tool-caller-fn - Optional FQN of a consumer tool-gate builder
                       (fn [blackboard context] call-tool-fn). When named, every
                       tool call the leaf makes uses that gate; an unresolvable
                       gate fails the node, never falling back to the ungated
                       caller."
  [name & {:keys [fn reads writes retry judges tool-caller-fn tool-contracts]}]
  (cond-> {:node-type :leaf
           :name name
           :executor :code
           :fn fn
           :reads (vec reads)
           :writes (vec writes)
           :retry retry}
    judges (assoc :judges (vec judges))
    tool-caller-fn (assoc :tool-caller-fn tool-caller-fn)
    tool-contracts (assoc :tool-contracts tool-contracts)))

(defn tool
  "Define a tool executor leaf node: one tool, named when the workflow is
   authored, called through the node's tool caller.

   Options:
     :tool - the authored tool name (required, non-blank)
     :reads - blackboard keys whose values become the tool's arguments
              (read key -> keyword-keyed argument; nil reads are omitted)
     :writes - exactly one blackboard key; receives the tool's result
     :retry - {:max-attempts n :backoff-ms [100 500]}
     :judges - Vector of judge names (defined in sheet/judges)
     :tool-caller-fn - Optional FQN of a consumer tool-gate builder, applied
                       exactly as for a code leaf
     :tool-contracts - Declared per-tool argument/result contracts, applied
                       exactly as for a code leaf"
  [name & {:keys [tool reads writes retry judges tool-caller-fn tool-contracts]}]
  (when-not (and (string? tool) (not (str/blank? tool)))
    (throw (ex-info "A tool leaf requires an authored :tool name"
                    {:node name :tool tool})))
  (when-not (= 1 (count writes))
    (throw (ex-info "A tool leaf declares exactly one write key"
                    {:node name :writes writes})))
  (cond-> {:node-type :leaf
           :name name
           :executor :tool
           :tool tool
           :reads (vec reads)
           :writes (vec writes)
           :retry retry}
    judges (assoc :judges (vec judges))
    tool-caller-fn (assoc :tool-caller-fn tool-caller-fn)
    tool-contracts (assoc :tool-contracts tool-contracts)))

(defn condition
  "Define a condition node.

   Options:
     :check - {:key :bb-key :op :equals/:gt/:lt/etc :value expected}
     :on-fail - :failure (default) or :success
     :judges - Vector of judge names (defined in sheet/judges). A condition
               completes like any node, so a judge may monitor it."
  [name & {:keys [check on-fail judges]}]
  (cond-> {:node-type :condition
           :name name
           :check check
           :on-fail (or on-fail :failure)}
    judges (assoc :judges (vec judges))))

(defn llm-condition
  "Define an LLM condition node - uses LLM to evaluate true/false.

   Options:
     :provider - Optional registered provider for this node's primary model
     :fallback-models - Ordered backup maps {:model id :provider optional :options optional};
       [] explicitly disables inherited backups
     :fallback-policy - :temporary-transport (default) or explicit :all-transport
     :model - OpenRouter model ID (e.g., \"google/gemini-2.5-flash\")
     :instruction - Prompt describing what to evaluate (should be a yes/no question)
     :reads - Vector of blackboard keys to read as context (e.g., [:student-profile])
     :options - Shared generation settings, including bounded same-model retries
     :judges - Vector of judge names (defined in sheet/judges)"
  [name & {:keys [model instruction reads judges options provider fallback-models fallback-policy] :as config}]
  (validate-model-config-keys! config #{:model :instruction :reads :judges :options :provider :fallback-models :fallback-policy})
  (validate-config!
   (merge (select-keys config [:provider :fallback-models :fallback-policy :options])
         (cond-> {:node-type :llm-condition
           :name name
           :model model
           :instruction instruction
           :reads (vec reads)}
    judges (assoc :judges (vec judges))))))

(defn repl-researcher
  "Define a repl-researcher node for iterative LLM+code research.

   The node executes an iteration loop where:
   1. LLM generates Clojure code that calls available tools
   2. Code executes in SCI sandbox with tools injected
   3. Stdout and results feed back to LLM
   4. Loop continues until FINAL_ANSWER or max iterations

   Options:
     :provider - Optional registered provider for this node's primary model
     :fallback-models - Ordered backup maps {:model id :provider optional :options optional};
       [] explicitly disables inherited backups
     :fallback-policy - :temporary-transport (default) or explicit :all-transport
     :model - OpenRouter model ID (e.g., \"google/gemini-2.5-flash\")
     :instruction - Research goal/question
     :reads - Vector of blackboard keys (metadata only shown to LLM, not values)
     :writes - Vector of output keys (e.g., [:final-answer :iterations])
     :mcp-tools - Vector of MCP tool names (require :call-tool-fn in context)
     :tool-contracts - Map of tool name to {:arguments Malli :result Malli};
       omitted schemas are advertised to Phase 1 as :untyped
     :tool-caller-fn - FQN of a consumer builder (fn [blackboard context] call-tool-fn)
     :browser-tools - Vector of agent-browser tool names (e.g., [\"open\" \"snapshot\" \"click\"])
     :max-iterations - Max research iterations (default 10)
     :timeout-ms - Optional positive total Phase 1 + cumulative Phase 2 budget in ms
     :rlm - Enable RLM mode with BT primitives (default: false)
     :judges - Vector of judge names (defined in sheet/judges)
     :options - Per-node executor/ORC LLM options map. W38-F2:
       :tool-arg-specs {\"tool-name\" [\"arg-key\" ...]} declares a bound
       tool's REAL argument keys; declared tools render those keys verbatim
       in the Phase-1 tool advertisement instead of the generic
       {\"arg\" \"value\"} example (undeclared tools keep the generic shape)

   Browser tools are shell-based (no session management) and include:
     open, snapshot, click, fill, type, press, scroll, wait,
     get-text, get-url, get-title, back, forward, screenshot

   RLM model defaults:
     Node routing config applies to prime calls. :rlm may separately declare
     :sub-model, :sub-provider, :sub-options, :sub-fallback-models and
     :sub-fallback-policy for inline/emitted specialists. Subcall overrides win;
     overriding only :model retains inherited backups.

   RLM Mode:
     When :rlm true, the sandbox gains behavior tree primitives:
     - (llm \"name\" :instruction \"...\" :writes [:key]) - Execute sub-LLM call
     - (sequence \"name\" child1 child2 ...) - Execute in order
     - (parallel \"name\" child1 child2 ...) - Execute concurrently
     - (map-each \"name\" coll f) - Process collection items
     - (fallback \"name\" child1 child2 ...) - First non-nil result
     - (condition \"name\" pred then else) - Branch on predicate
     - (code \"name\" :writes [:key] :body expr) - Pure computation
     - (final! {:key value}) - Capture validated output
     - (get-input :key) - Load full input value
     - inputs - Preview map (metadata only)"
  [name & {:keys [model instruction reads writes mcp-tools tool-contracts tool-caller-fn browser-tools max-iterations rlm judges context options provider fallback-models fallback-policy timeout-ms] :as config}]
  (validate-model-config-keys! config #{:model :instruction :reads :writes :mcp-tools :tool-contracts :tool-caller-fn :browser-tools :max-iterations :rlm :judges :context :options :provider :fallback-models :fallback-policy :timeout-ms})
  (when (and (some? timeout-ms) (not (pos-int? timeout-ms)))
    (throw (ex-info "Researcher :timeout-ms must be a positive integer"
                    {:timeout-ms timeout-ms})))
  (validate-config!
   (merge (select-keys config [:provider :fallback-models :fallback-policy])
         (cond-> {:node-type :repl-researcher
           :name name
           :model model
           :instruction instruction
           :reads (vec (or reads []))
           :writes (vec (or writes []))
           :mcp-tools (vec (or mcp-tools []))
           :browser-tools (vec (or browser-tools []))
           :max-iterations (or max-iterations 10)}
    (some? rlm) (assoc :rlm rlm)
    (some? timeout-ms) (assoc :timeout-ms timeout-ms)
    judges (assoc :judges (vec judges))
    tool-contracts (assoc :tool-contracts tool-contracts)
    tool-caller-fn (assoc :tool-caller-fn tool-caller-fn)
    context (assoc :context context)
    options (assoc :options options)))))

(defn delegate
  "Define a delegate node for subworkflow execution.

   Executes another sheet (workflow) with isolated blackboard,
   mapping inputs from parent and outputs back to parent.
   This enables modular, composable workflow design.

   Options:
     :target-sheet-id - UUID of the sheet to delegate to (required)
     :reads - Vector of blackboard keys to pass as inputs to target
     :writes - Vector of blackboard keys to receive outputs from target
     :timeout-ms - Max execution time for the delegation (default: 300000ms)
     :max-ticks - Positive child retick limit (default: runtime default)
     :inherit-ontology? - Share ontology context with target (default: true)
     :judges - Vector of judge names (defined in sheet/judges); a judge on a
               delegate grades the whole delegated behaviour

   Example:
   ```clojure
   (delegate \"analyze-data\"
     :target-sheet-id #uuid \"...\"
     :reads [:input-data :config]
     :writes [:analysis-result :metrics]
     :timeout-ms 60000)
   ```"
  [name & {:keys [target-sheet-id reads writes timeout-ms max-ticks inherit-ontology? judges]
           :or {inherit-ontology? true}}]
  (when (and (some? timeout-ms) (not (pos-int? timeout-ms)))
    (throw (ex-info "Delegate :timeout-ms must be a positive integer"
                    {:timeout-ms timeout-ms})))
  (when (and (some? max-ticks) (not (pos-int? max-ticks)))
    (throw (ex-info "Delegate :max-ticks must be a positive integer"
                    {:max-ticks max-ticks})))
  (cond-> {:node-type :delegate
           :name name
           :target-sheet-id target-sheet-id
           :reads (vec (or reads []))
           :writes (vec (or writes []))}
    timeout-ms (assoc :timeout-ms timeout-ms)
    max-ticks (assoc :max-ticks max-ticks)
    judges (assoc :judges (vec judges))
    (some? inherit-ontology?) (assoc :inherit-ontology? inherit-ontology?)))

(defn- split-leading-options
  "Split a composite's trailing arguments into [options children]. Leading
   keyword/value pairs are options (`:judges [\"j\"]`); a leading options map is
   also accepted. Everything from the first node map onward is a child."
  [args]
  (loop [args args opts {}]
    (cond
      (and (keyword? (first args)) (next args))
      (recur (drop 2 args) (assoc opts (first args) (second args)))

      :else [opts (vec args)])))

(defn- with-judges
  "Attach `:judges` (a vector of judge names) to a node when given."
  [node judges]
  (cond-> node
    (seq judges) (assoc :judges (vec judges))))

(defn sequence
  "Define a sequence node (runs children in order, fails on first failure).

   Name is required for stable identity across rebuilds.

   Options (leading keyword arguments, before the children):
     :judges - Vector of judge names (defined in sheet/judges); a judge on a
               composite grades the whole of what the composite did."
  [name & args]
  (let [[opts children] (split-leading-options args)]
    (with-judges {:node-type :sequence
                  :name name
                  :children children}
      (:judges opts))))

(defn fallback
  "Define a fallback node (runs children in order, succeeds on first success).

   Name is required for stable identity across rebuilds.

   Options (leading keyword arguments, before the children):
     :judges - Vector of judge names (defined in sheet/judges)"
  [name & args]
  (let [[opts children] (split-leading-options args)]
    (when (contains? opts :model-fallback-policy)
      (throw (ex-info "Configure fallback-models on a model node instead"
                      {:type :invalid-model-fallback})))
    (with-judges {:node-type :fallback :name name :children children}
      (:judges opts))))

(defn parallel
  "Define a parallel node (runs all children concurrently).

   Name is required for stable identity across rebuilds.

   Options:
     :success-policy - :all (default), :any, :majority
     :failure-policy - :any (default), :all
     :judges - Vector of judge names (defined in sheet/judges)"
  [name & args]
  (let [[opts children] (if (and (map? (first args))
                                 (or (contains? (first args) :success-policy)
                                     (contains? (first args) :failure-policy)
                                     (contains? (first args) :judges)))
                          [(first args) (rest args)]
                          [{} args])]
    (with-judges {:node-type :parallel
                  :name name
                  :success-policy (or (:success-policy opts) :all)
                  :failure-policy (or (:failure-policy opts) :any)
                  :children (vec children)}
      (:judges opts))))

(defn map-each
  "Define a map-each node (iterates over a list).

   Options:
     :from - Blackboard key containing the source list (e.g., :programs)
     :as - Blackboard key for the current item (e.g., :current-program)
     :into - Blackboard key for the results list (e.g., :scored-programs)
     :parallel - Max parallel executions (default 1 = sequential)
     :judges - Vector of judge names (defined in sheet/judges)"
  [name & args]
  (let [[opts children] (if (keyword? (first args))
                          (let [kw-args (take-while #(or (keyword? %) (not (map? %))) args)
                                rest-args (drop (count kw-args) args)
                                opts-map (apply hash-map kw-args)]
                            [opts-map rest-args])
                          [{} args])]
    (with-judges {:node-type :map-each
                  :name name
                  :source-key (:from opts)
                  :item-key (:as opts)
                  :output-key (:into opts)
                  :max-concurrency (:parallel opts)
                  :preserve-failures? (:preserve-failures? opts)
                  :children (vec children)}
      (:judges opts))))

;; =============================================================================
;; Blackboard Schema Builder
;; =============================================================================

(defn- reject-unconstrained-schemas!
  [schema-map]
  (when-let [violations (seq (schema-map-violations schema-map))]
    (throw
     (ex-info
      (feedback violations)
      {:blackboard-keys (vec (distinct (map :key violations)))
       :schema-violations (vec violations)}))))

(defn dsl-blackboard
  "Define the blackboard schema.

   Takes a map of key-name -> malli-schema:
   ```
   (blackboard
     {:input :string
      :items [:vector [:map [:id :string]]]
      :result :int})
  ```"
  [schema-map]
  (reject-unconstrained-schemas! schema-map)
  {:blackboard-schema schema-map})

;; =============================================================================
;; Judge Definitions
;; =============================================================================

(defn dsl-judges
  "Define evaluation judges for the workflow.

   Takes a map of judge-name -> judge-config:
   ```
   (judges
     {:my-completeness
      {:type :completeness
       :criteria \"Must include X, Y, Z\"
       :weight 0.35}
      :my-grounding
      {:type :grounding
       :criteria \"All claims must trace to inputs\"}
      :custom-judge
      {:type :custom
       :sheet-id #uuid \"...\"
       :weight 0.2}})
   ```

   Optional fields on any judge:
     :rubric     - {:criterion str :stance str :bands {1 \"...\" 2 \"...\"}
                    :feedback :required|:none} the judge grades by
     :purposes   - subset of #{:monitoring :learning}; default both, or
                   #{:monitoring} when the rubric's feedback is :none. A
                   :learning judge must require feedback.
     :model      - model id or registered provider name; absent resolves like
                   any ORC model node (declared model, else runtime provider)
     :timeout-ms - positive integer
     :assess-failures? - boolean, default false. A completion that failed,
                   timed out or blocked is recorded ungradable (:subject-failed)
                   without running the judge; true judges it like any other.
   Rebuilding a workflow whose judge definition changed REVISES the judge
   (its revision number increases); an unchanged judge is left alone.

   Judge types:
     :grounding - Hallucination detection
     :completeness - Coverage of requirements
     :instruction-following - Task compliance
     :reasoning - Logical coherence
     :custom - User-defined judge sheet"
  [judge-map]
  {:judges-schema judge-map})

;; =============================================================================
;; Workflow Definition
;; =============================================================================

(defn workflow
  "Define a complete workflow.

   Args:
     name - Name of the workflow
     & parts - Blackboard definition, judge definitions, and root node"
  [name & parts]
  (let [bb-def (first (filter :blackboard-schema parts))
        judges-def (first (filter :judges-schema parts))
        root-node (first (filter :node-type parts))]
    (cond-> {:workflow-name name
             :blackboard-schema (:blackboard-schema bb-def)
             :root-node root-node}
      judges-def (assoc :judges-schema (:judges-schema judges-def)))))

;; ---------------------------------------------------------------------------
;; Per-sheet build serialization
;; ---------------------------------------------------------------------------

(defonce ^:private build-locks (java.util.concurrent.ConcurrentHashMap.))

(defn build-lock-for
  "Return the canonical lock object for a sheet-id, interning a fresh one the
   first time a given sheet-id is seen."
  [sheet-id]
  (.computeIfAbsent build-locks sheet-id
                    (reify java.util.function.Function
                      (apply [_ _] (Object.)))))

(defn validate-workflow-definition!
  "Validate graph-wide invariants before the first mutating command. Command
   handlers still validate each operation, but doing the deterministic checks
   up front prevents an invalid initial definition from leaving a partial
   event-sourced graph."
  [{:keys [workflow-name blackboard-schema root-node]}]
  (when-not (and (string? workflow-name) (seq workflow-name))
    (throw (ex-info "Workflow requires a non-empty name" {:workflow-name workflow-name})))
  (let [_ (reject-unconstrained-schemas! blackboard-schema)
        declared (set (keys (or blackboard-schema {})))
        nodes (if root-node (tree-seq #(seq (:children %)) :children root-node) [])
        names (keep :name nodes)
        duplicate-names (->> names frequencies (keep (fn [[n c]] (when (> c 1) n))) vec)]
    (when (seq duplicate-names)
      (throw (ex-info (str "Workflow node names must be unique: " duplicate-names)
                      {:duplicate-node-names duplicate-names})))
    (doseq [node nodes]
      (validate-config! node))
    (doseq [node nodes
            :when (= :decision (:executor node))
            :let [error (declaration-error node blackboard-schema)]
            :when error]
      (throw (ex-info error {:node (:name node) :decision-declaration-error true})))
    (doseq [node nodes
            :when (= :delegate (or (:node-type node) (:type node)))
            :let [by-child-input (group-by name (distinct (:reads node)))
                  collision (first (filter #(> (count (val %)) 1) by-child-input))]
            :when collision]
      (throw (ex-info (str "Delegate " (pr-str (:name node))
                           " reads distinct parent keys " (pr-str (vec (val collision)))
                           " that would all supply the child input "
                           (pr-str (key collision)))
                      {:node (:name node)
                       :child-input (key collision)
                       :colliding-keys (vec (val collision))})))
    (doseq [node nodes
            key (concat (:reads node) (:writes node)
                        (when-let [k (get-in node [:check :key])] [k])
                        (when (= :map-each (:node-type node))
                          [(:source-key node) (:item-key node) (:output-key node)]))
            :when (and key (not (contains? declared key)))]
      (throw (ex-info (str "Unknown blackboard key " (pr-str key)
                           " referenced by node " (pr-str (:name node))
                           " in reads/writes/config")
                      {:node (:name node) :key key}))))
  true)

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn print-tree
  "Print a visual representation of a workflow definition."
  ([workflow-def]
   (print-tree (:root-node workflow-def) 0))
  ([node depth]
   (let [indent (apply str (repeat depth "  "))
         node-type (:node-type node)
         name-str (or (:name node) (name node-type))]
     (println (str indent "├── [" (name node-type) "] " name-str
                   (when (:executor node) (str " (" (name (:executor node)) ")"))
                   (when (:model node) (str " model=" (:model node)))))
     (doseq [child (:children node)]
       (print-tree child (inc depth))))))

(defn describe-workflow
  "Print a description of a workflow."
  [workflow-def]
  (println "Workflow:" (:workflow-name workflow-def))
  (println "\nBlackboard Schema:")
  (doseq [[k v] (:blackboard-schema workflow-def)]
    (println "  " (name k) "->" v))
  (println "\nTree Structure:")
  (print-tree workflow-def))

;; =============================================================================
;; Export/Import Functions
;; =============================================================================

(defn dsl-build-node-tree
  "Convert flat nodes map to nested tree structure for export.
   Preserves original node IDs for accurate structural diffing."
  [nodes-by-id root-id]
  (when root-id
    (let [node (get nodes-by-id root-id)]
      (when node
        (cond-> {:id (:id node)
                 :type (:type node)
                 :name (:name node)}
          true (merge (cond-> (select-keys node [:provider :fallback-models :fallback-policy :options])
                        (nil? (:options node)) (dissoc :options)))
          (:model-fallback-policy node) (assoc :model-fallback-policy (:model-fallback-policy node))
          (seq (:judges node)) (assoc :judges (vec (:judges node)))
          (= :leaf (:type node))
          (merge (cond-> {}
                   (:executor node) (assoc :executor (:executor node))
                   (:model node) (assoc :model (:model node))
                   (:options node) (assoc :options (:options node))
                   (:fn node) (assoc :fn (:fn node))
                   (and (= :tool (:executor node)) (:tool node))
                   (assoc :tool (:tool node))
                   (and (#{:code :tool} (:executor node)) (:tool-caller-fn node))
                   (assoc :tool-caller-fn (:tool-caller-fn node))
                   (and (#{:code :tool} (:executor node)) (:tool-contracts node))
                   (assoc :tool-contracts (:tool-contracts node))
                   (:instruction node) (assoc :instruction (:instruction node))
                   (seq (:reads node)) (assoc :reads (:reads node))
                   (seq (:writes node)) (assoc :writes (:writes node))
                   (:retry node) (assoc :retry (:retry node))
                   (:context node) (assoc :context (:context node))
                   (= :decision (:executor node))
                   (merge (cond-> {}
                            (:options-from node) (assoc :options-from (:options-from node))
                            (:bands-from node) (assoc :bands-from (:bands-from node))
                            (:min-confidence node) (assoc :min-confidence (:min-confidence node))
                            (some? (:abstain node)) (assoc :abstain (:abstain node))
                            (:options node) (assoc :options (:options node))))))
          (= :condition (:type node))
          (merge (cond-> {}
                   (:check node) (assoc :check (:check node))
                   (:on-fail node) (assoc :on-fail (:on-fail node))))
          (= :llm-condition (:type node))
          (merge (cond-> {}
                   (:instruction node) (assoc :instruction (:instruction node))
                   (seq (:reads node)) (assoc :reads (:reads node))
                   (:model node) (assoc :model (:model node))
                   (:options node) (assoc :options (:options node))))
          (= :parallel (:type node))
          (merge {:success-policy (or (:success-policy node) :all)
                  :failure-policy (or (:failure-policy node) :any)})
          (= :map-each (:type node))
          (merge (cond-> {}
                   (:source-key node) (assoc :source-key (:source-key node))
                   (:item-key node) (assoc :item-key (:item-key node))
                   (:output-key node) (assoc :output-key (:output-key node))
                   (:max-concurrency node) (assoc :max-concurrency (:max-concurrency node))
                   (some? (:preserve-failures? node)) (assoc :preserve-failures? (:preserve-failures? node))))
          (= :repl-researcher (:type node))
          (merge (cond-> {}
                   (:instruction node) (assoc :instruction (:instruction node))
                   (seq (:reads node)) (assoc :reads (:reads node))
                   (seq (:writes node)) (assoc :writes (:writes node))
                   (seq (:mcp-tools node)) (assoc :mcp-tools (:mcp-tools node))
                   (:tool-contracts node) (assoc :tool-contracts (:tool-contracts node))
                   (:tool-caller-fn node) (assoc :tool-caller-fn (:tool-caller-fn node))
                   (seq (:browser-tools node)) (assoc :browser-tools (:browser-tools node))
                   (:model node) (assoc :model (:model node))
                   (:options node) (assoc :options (:options node))
                   (:max-iterations node) (assoc :max-iterations (:max-iterations node))
                   (some? (:timeout-ms node)) (assoc :timeout-ms (:timeout-ms node))
                   (some? (:rlm node)) (assoc :rlm (:rlm node))
                   (:context node) (assoc :context (:context node))
                   (:options node) (assoc :options (:options node))))
          (= :delegate (:type node))
          (merge (cond-> {}
                   (:target-sheet-id node) (assoc :target-sheet-id (:target-sheet-id node))
                   (seq (:reads node)) (assoc :reads (:reads node))
                   (seq (:writes node)) (assoc :writes (:writes node))
                   (:delegate-timeout-ms node) (assoc :timeout-ms (:delegate-timeout-ms node))
                   (:delegate-max-ticks node) (assoc :max-ticks (:delegate-max-ticks node))
                   (some? (:inherit-ontology? node)) (assoc :inherit-ontology? (:inherit-ontology? node))))
          (seq (:children-ids node))
          (assoc :children
                 (vec (keep #(dsl-build-node-tree nodes-by-id %) (:children-ids node)))))))))

;; =============================================================================
;; DSL Code Generation
;; =============================================================================

(def ^:dynamic *dsl-ns* nil)

(defn- dsl-sym
  "Create a DSL symbol, optionally prefixed with *dsl-ns*."
  [sym-name]
  (if *dsl-ns*
    (symbol (str *dsl-ns*) (name sym-name))
    (symbol (name sym-name))))

(defn- escape-string
  "Escape special characters in strings for Clojure source code."
  [s]
  (when s
    (-> s
        (str/replace "\\" "\\\\")
        (str/replace "\"" "\\\"")
        (str/replace "\n" "\\n")
        (str/replace "\t" "\\t")
        (str/replace "\r" "\\r"))))

(defn- build-keyword-args
  "Build keyword argument pairs from a map, filtering nil/empty values except
   keys explicitly named in preserve-empty-keys.
   Returns a flat sequence of [:key value :key value ...] sorted by key name."
  ([opts-map]
   (build-keyword-args opts-map #{:fallback-models}))
  ([opts-map preserve-empty-keys]
   (->> opts-map
        (remove (fn [[k v]] (or (nil? v)
                                (and (coll? v)
                                     (empty? v)
                                     (not (contains? preserve-empty-keys k))))))
        (sort-by (comp name first))
        (mapcat (fn [[k v]] [(keyword (name k)) v])))))

(defn- leaf-node->form
  "Convert a leaf node to llm or code DSL form."
  [node]
  (let [executor (:executor node)
        name (:name node)]
    (case executor
      :ai (let [opts (build-keyword-args
                       {:provider (:provider node)
                        :fallback-models (:fallback-models node)
                        :fallback-policy (:fallback-policy node)
                        :model (:model node)
                        :options (:options node)
                        :instruction (:instruction node)
                        :reads (:reads node)
                        :writes (:writes node)
                        :retry (:retry node)
                        :judges (:judges node)})]
            (if (empty? opts)
              (list (dsl-sym 'llm) name)
              (apply list (dsl-sym 'llm) name opts)))

      :code (let [opts (build-keyword-args
                         {:fn (:fn node)
                          :tool-caller-fn (:tool-caller-fn node)
                          :tool-contracts (:tool-contracts node)
                          :reads (:reads node)
                          :writes (:writes node)
                          :retry (:retry node)
                        :judges (:judges node)})]
              (if (empty? opts)
                (list (dsl-sym 'code) name)
                (apply list (dsl-sym 'code) name opts)))

      :tool (let [opts (build-keyword-args
                         {:tool (:tool node)
                          :tool-caller-fn (:tool-caller-fn node)
                          :tool-contracts (:tool-contracts node)
                          :reads (:reads node)
                          :writes (:writes node)
                          :retry (:retry node)
                        :judges (:judges node)})]
              (apply list (dsl-sym 'tool) name opts))

      :decision (let [opts (build-keyword-args
                             {:provider (:provider node)
                        :fallback-models (:fallback-models node)
                        :fallback-policy (:fallback-policy node)
                        :model (:model node)
                              :instruction (:instruction node)
                              :reads (:reads node)
                              :writes (:writes node)
                              :options-from (:options-from node)
                              :bands-from (:bands-from node)
                              :min-confidence (:min-confidence node)
                              :abstain (:abstain node)
                              :retry (:retry node)
                              :options (:options node)
                              :judges (:judges node)})]
                  (if (empty? opts)
                    (list (dsl-sym 'llm-decision) name)
                    (apply list (dsl-sym 'llm-decision) name opts)))

      (list (dsl-sym 'llm) name))))

(defn- condition-node->form
  "Convert a condition node to DSL form."
  [node]
  (let [check (:check node)
        on-fail (:on-fail node)
        opts (build-keyword-args
               (cond-> {:check (when check (into (sorted-map) check))
                        :judges (:judges node)}
                 (and on-fail (not= on-fail :failure))
                 (assoc :on-fail on-fail)))]
    (if (empty? opts)
      (list (dsl-sym 'condition) (:name node))
      (apply list (dsl-sym 'condition) (:name node) opts))))

(defn- llm-condition-node->form
  "Convert an llm-condition node to DSL form."
  [node]
  (let [opts (build-keyword-args
               {:provider (:provider node)
                        :fallback-models (:fallback-models node)
                        :fallback-policy (:fallback-policy node)
                        :model (:model node)
                :options (:options node)
                :instruction (:instruction node)
                :reads (:reads node)
                :judges (:judges node)})]
    (if (empty? opts)
      (list (dsl-sym 'llm-condition) (:name node))
      (apply list (dsl-sym 'llm-condition) (:name node) opts))))

(defn- repl-researcher-node->form
  "Convert a repl-researcher node to DSL form."
  [node]
  (let [opts (build-keyword-args
               {:provider (:provider node)
                        :fallback-models (:fallback-models node)
                        :fallback-policy (:fallback-policy node)
                        :model (:model node)
                :instruction (:instruction node)
                :reads (:reads node)
                :writes (:writes node)
                :mcp-tools (:mcp-tools node)
                :tool-contracts (:tool-contracts node)
                :tool-caller-fn (:tool-caller-fn node)
                :browser-tools (:browser-tools node)
                :max-iterations (:max-iterations node)
                :timeout-ms (:timeout-ms node)
                :rlm (:rlm node)
                :judges (:judges node)
                :context (:context node)
                :options (:options node)}
               #{:rlm :context :fallback-models :options})]
    (if (empty? opts)
      (list (dsl-sym 'repl-researcher) (:name node))
      (apply list (dsl-sym 'repl-researcher) (:name node) opts))))

(defn- delegate-node->form
  "Convert a delegate node to DSL form."
  [node]
  (let [opts (build-keyword-args
               {:target-sheet-id (:target-sheet-id node)
                :reads (:reads node)
                :writes (:writes node)
                :timeout-ms (or (:timeout-ms node) (:delegate-timeout-ms node))
                :max-ticks (or (:max-ticks node) (:delegate-max-ticks node))
                :inherit-ontology? (:inherit-ontology? node)
                :judges (:judges node)})]
    (if (empty? opts)
      (list (dsl-sym 'delegate) (:name node))
      (apply list (dsl-sym 'delegate) (:name node) opts))))

(defn- composite-node->form
  "Convert a sequence or fallback node to DSL form."
  [render-child fn-sym node]
  (let [name (:name node)
        children (mapv render-child (:children node))
        opts (build-keyword-args (cond-> {:judges (:judges node)}
                                         (:model-fallback-policy node) (assoc :model-fallback-policy (:model-fallback-policy node))))]
    (apply list (dsl-sym fn-sym) name (concat opts children))))

(defn- parallel-node->form
  "Convert a parallel node to DSL form."
  [render-child node]
  (let [name (:name node)
        success-policy (:success-policy node)
        failure-policy (:failure-policy node)
        children (mapv render-child (:children node))
        opts (cond-> {}
               (and success-policy (not= success-policy :all))
               (assoc :success-policy success-policy)
               (and failure-policy (not= failure-policy :any))
               (assoc :failure-policy failure-policy)
               (seq (:judges node))
               (assoc :judges (vec (:judges node))))]
    (if (empty? opts)
      (apply list (dsl-sym 'parallel) name children)
      (apply list (dsl-sym 'parallel) name opts children))))

(defn- map-each-node->form
  "Convert a map-each node to DSL form."
  [render-child node]
  (let [name (:name node)
        children (mapv render-child (:children node))
        opts (build-keyword-args
               {:from (:source-key node)
                :as (:item-key node)
                :into (:output-key node)
                :parallel (:max-concurrency node)
                :preserve-failures? (:preserve-failures? node)
                :judges (:judges node)})]
    (apply list (dsl-sym 'map-each) name (concat opts children))))

(defn- node->dsl-form
  "Convert an exported node to a DSL form (unevaluated Clojure code as data)."
  [node]
  (case (:type node)
    :leaf (leaf-node->form node)
    :condition (condition-node->form node)
    :llm-condition (llm-condition-node->form node)
    :repl-researcher (repl-researcher-node->form node)
    :delegate (delegate-node->form node)
    :sequence (composite-node->form node->dsl-form 'sequence node)
    :fallback (composite-node->form node->dsl-form 'fallback node)
    :parallel (parallel-node->form node->dsl-form node)
    :map-each (map-each-node->form node->dsl-form node)
    (composite-node->form node->dsl-form 'sequence node)))

(defn- blackboard->form
  "Convert blackboard schema to DSL form."
  [schema-map]
  (when (and schema-map (seq schema-map))
    (list (dsl-sym 'blackboard) (into (sorted-map) schema-map))))

(defn- judges->form
  "Convert declared judges to a `judges` DSL form."
  [judges-schema]
  (list (dsl-sym 'judges) (into (sorted-map) judges-schema)))

(defn- workflow->form
  "Convert an exported sheet to a complete workflow DSL form."
  [exported]
  (let [name (get-in exported [:sheet :name])
        bb-schema (:blackboard-schema exported)
        judges-schema (:judges-schema exported)
        root-node (:nodes exported)
        parts (cond-> []
                (seq bb-schema) (conj (blackboard->form bb-schema))
                (seq judges-schema) (conj (judges->form judges-schema))
                root-node (conj (node->dsl-form root-node)))]
    (apply list (dsl-sym 'workflow) name parts)))

;; =============================================================================
;; Pretty Printing
;; =============================================================================

(defn- indent-str
  "Generate indentation string for given level."
  [level]
  (apply str (repeat (* 2 level) " ")))

(defn- format-keyword-args
  "Format keyword arguments with proper indentation for leaf nodes."
  [args indent-level]
  (let [indent (indent-str indent-level)
        pairs (partition 2 args)]
    (->> pairs
         (map (fn [[k v]]
                (str "\n" indent "  " k " "
                     (cond
                       (and (= k :instruction) (string? v) (> (count v) 50))
                       (str "\"" (escape-string v) "\"")
                       (string? v)
                       (str "\"" (escape-string v) "\"")
                       :else
                       (pr-str v)))))
         (str/join))))

(defn- leaf-form->pretty-string
  "Pretty print a leaf node form (llm, code, condition, llm-condition)."
  [form indent-level]
  (let [indent (indent-str indent-level)
        [fn-sym name & args] form]
    (if (empty? args)
      (str "(" fn-sym " \"" (escape-string name) "\")")
      (str "(" fn-sym " \"" (escape-string name) "\""
           (format-keyword-args args indent-level) ")"))))

(defn- composite-form->pretty-string
  "Pretty print a composite node form (sequence, fallback)."
  [render-child form indent-level]
  (let [indent (indent-str indent-level)
        child-indent (indent-str (inc indent-level))
        [fn-sym name & args] form
        [opts children] (let [[o c] (split-with (complement list?) args)]
                          [(partition 2 o) c])
        opts-str (apply str (map (fn [[k v]] (str " " k " " (pr-str v))) opts))]
    (if (empty? children)
      (str "(" fn-sym " \"" (escape-string name) "\"" opts-str ")")
      (str "(" fn-sym " \"" (escape-string name) "\"" opts-str "\n"
           (->> children
                (map #(str child-indent (render-child % (inc indent-level))))
                (str/join "\n"))
           ")"))))

(defn- parallel-form->pretty-string
  "Pretty print a parallel node form."
  [render-child form indent-level]
  (let [indent (indent-str indent-level)
        child-indent (indent-str (inc indent-level))
        [fn-sym name first-arg & rest-args] form
        [opts children] (if (map? first-arg)
                          [first-arg rest-args]
                          [nil (if first-arg (cons first-arg rest-args) [])])]
    (if (and (nil? opts) (empty? children))
      (str "(" fn-sym " \"" (escape-string name) "\")")
      (str "(" fn-sym " \"" (escape-string name) "\""
           (when opts (str " " (pr-str opts)))
           (when (seq children)
             (str "\n"
                  (->> children
                       (map #(str child-indent (render-child % (inc indent-level))))
                       (str/join "\n"))))
           ")"))))

(defn- map-each-form->pretty-string
  "Pretty print a map-each node form."
  [render-child form indent-level]
  (let [indent (indent-str indent-level)
        child-indent (indent-str (inc indent-level))
        [fn-sym name & args] form
        [kw-args children] (loop [remaining args
                                   kw-acc []
                                   child-acc []]
                             (if (empty? remaining)
                               [kw-acc child-acc]
                               (let [item (first remaining)]
                                 (if (keyword? item)
                                   (recur (drop 2 remaining)
                                          (conj kw-acc item (second remaining))
                                          child-acc)
                                   (recur (rest remaining)
                                          kw-acc
                                          (conj child-acc item))))))]
    (str "(" fn-sym " \"" (escape-string name) "\""
         (->> (partition 2 kw-args)
              (map (fn [[k v]]
                     (str "\n" indent "  " k " "
                          (if (string? v)
                            (str "\"" (escape-string v) "\"")
                            (pr-str v)))))
              (str/join))
         (when (seq children)
           (str "\n"
                (->> children
                     (map #(str child-indent (render-child % (inc indent-level))))
                     (str/join "\n"))))
         ")")))

(defn- blackboard-form->pretty-string
  "Pretty print a blackboard form."
  [form indent-level]
  (let [[fn-sym schema-map] form]
    (str "(" fn-sym "\n"
         (indent-str (inc indent-level))
         (pr-str schema-map) ")")))

(defn- workflow-form->pretty-string
  "Pretty print a complete workflow form."
  [render-child form]
  (let [[fn-sym wf-name & parts] form
        part-name (fn [p] (name (first p)))
        bb-form (first (filter #(and (list? %) (= "blackboard" (part-name %))) parts))
        judges-form (first (filter #(and (list? %) (= "judges" (part-name %))) parts))
        root-form (first (filter #(and (list? %) (not (#{"blackboard" "judges"} (part-name %)))) parts))]
    (str "(" fn-sym " \"" (escape-string wf-name) "\"\n"
         (when bb-form
           (str "  " (blackboard-form->pretty-string bb-form 1) "\n\n"))
         (when judges-form
           (str "  " (blackboard-form->pretty-string judges-form 1) "\n\n"))
         (when root-form
           (str "  " (render-child root-form 1)))
         ")")))

(defn- sym-name=
  "Compare symbol name, ignoring namespace prefix."
  [sym name-str]
  (= (name sym) name-str))

(defn- form->pretty-string
  "Convert a DSL form to a pretty-printed string."
  [form indent-level]
  (let [fn-sym (when (list? form) (first form))
        fn-name (when fn-sym (name fn-sym))]
    (cond
      (and fn-sym (= "workflow" fn-name))
      (workflow-form->pretty-string form->pretty-string form)

      (and fn-sym (contains? #{"sequence" "fallback"} fn-name))
      (composite-form->pretty-string form->pretty-string form indent-level)

      (and fn-sym (= "parallel" fn-name))
      (parallel-form->pretty-string form->pretty-string form indent-level)

      (and fn-sym (= "map-each" fn-name))
      (map-each-form->pretty-string form->pretty-string form indent-level)

      (and fn-sym (contains? #{"llm" "code" "condition" "llm-condition" "llm-decision"} fn-name))
      (leaf-form->pretty-string form indent-level)

      (and fn-sym (= "blackboard" fn-name))
      (blackboard-form->pretty-string form indent-level)

      :else (pr-str form))))

;; =============================================================================
;; Public DSL Generation API
;; =============================================================================

(defn export-to-dsl
  "Generate idiomatic Clojure DSL code from an exported sheet structure.

   Returns a string containing valid Clojure code that, when evaluated with
   the DSL namespace required, produces a workflow definition equivalent
   to the original sheet.

   Options:
     :pretty? - Pretty-print with indentation (default true)
     :ns      - Namespace prefix for DSL functions (e.g., \"sheet\" produces sheet/workflow)

   Example:
     (export-to-dsl (export-sheet ctx sheet-id))
     ;; => \"(workflow \\\"my-workflow\\\" ...)\"

     (export-to-dsl (export-sheet ctx sheet-id) :ns \"sheet\")
     ;; => \"(sheet/workflow \\\"my-workflow\\\" ...)\"

   Round-trip usage:
     (let [exported (export-sheet ctx sheet-id)
           dsl-code (export-to-dsl exported)
           workflow-def (eval (read-string dsl-code))]
       (build-workflow! ctx workflow-def))"
  [exported & {:keys [pretty? ns] :or {pretty? true}}]
  (binding [*dsl-ns* ns]
    (let [form (workflow->form exported)]
      (if pretty?
        (form->pretty-string form 0)
        (pr-str form)))))

;; --- Queries ---

;; =============================================================================
;; Auth Predicate
;; =============================================================================

(defn queries-authenticated?
  "Authorization predicate - checks if auth-claims are present in context."
  [ctx]
  (some? (:auth-claims ctx)))

(defn- flatten-node-tree
  "Convert nested tree structure back to flat list of nodes.
   Preserves existing IDs if present, otherwise generates new ones.
   Returns {:nodes-by-id {...} :root-id uuid}."
  ([tree-node] (flatten-node-tree tree-node nil (atom {})))
  ([tree-node parent-id nodes-atom]
   (when tree-node
     (let [node-id (or (:id tree-node) (random-uuid))
           children (:children tree-node)
           child-results (mapv #(flatten-node-tree % node-id nodes-atom) children)
           children-ids (vec (keep :node-id child-results))
           base-node {:id node-id
                      :type (:type tree-node)
                      :name (:name tree-node)
                      :status :idle
                      :parent-id parent-id
                      :children-ids children-ids}
           node (cond-> base-node
                  (:executor tree-node) (assoc :executor (:executor tree-node))
                  (:model tree-node) (assoc :model (:model tree-node))
                  (:fn tree-node) (assoc :fn (:fn tree-node))
                  (:instruction tree-node) (assoc :instruction (:instruction tree-node))
                  (seq (:reads tree-node)) (assoc :reads (:reads tree-node))
                  (seq (:writes tree-node)) (assoc :writes (:writes tree-node))
                  (:retry tree-node) (assoc :retry (:retry tree-node))
                  (= :decision (:executor tree-node))
                  (merge (cond-> {}
                           (:options-from tree-node) (assoc :options-from (:options-from tree-node))
                           (:bands-from tree-node) (assoc :bands-from (:bands-from tree-node))
                           (:min-confidence tree-node) (assoc :min-confidence (:min-confidence tree-node))
                           (some? (:abstain tree-node)) (assoc :abstain (:abstain tree-node))
                           (:options tree-node) (assoc :options (:options tree-node))))
                  (:check tree-node) (assoc :check (:check tree-node))
                  (:on-fail tree-node) (assoc :on-fail (:on-fail tree-node))
                  (:success-policy tree-node) (assoc :success-policy (:success-policy tree-node))
                  (:failure-policy tree-node) (assoc :failure-policy (:failure-policy tree-node))
                  (:source-key tree-node) (assoc :source-key (:source-key tree-node))
                  (:item-key tree-node) (assoc :item-key (:item-key tree-node))
                  (:output-key tree-node) (assoc :output-key (:output-key tree-node))
                  (:max-concurrency tree-node) (assoc :max-concurrency (:max-concurrency tree-node))
                  (some? (:preserve-failures? tree-node)) (assoc :preserve-failures? (:preserve-failures? tree-node)))]
       (swap! nodes-atom assoc node-id node)
       {:node-id node-id :nodes-atom nodes-atom}))))

(defn snapshot->nodes
  "Convert a snapshot's nested tree to a flat list of nodes."
  [snapshot]
  (if-let [tree (:nodes snapshot)]
    (let [nodes-atom (atom {})
          result (flatten-node-tree tree nil nodes-atom)
          nodes-by-id @nodes-atom]
      {:nodes (vec (vals nodes-by-id))
       :nodes-by-id nodes-by-id
       :root-id (:node-id result)})
    {:nodes [] :nodes-by-id {} :root-id nil}))

(defn snapshot->blackboard
  "Convert a snapshot's blackboard-schema to a blackboard list."
  [snapshot]
  (mapv (fn [[k schema]]
          {:key k
           :schema schema
           :value nil})
        (:blackboard-schema snapshot)))

;; =============================================================================
;; Export Sheet Query
;; =============================================================================

(defn queries-build-node-tree
  "Convert flat nodes map to nested tree structure for export.
   Preserves original node IDs for accurate structural diffing."
  [nodes-by-id root-id]
  (when root-id
    (let [node (get nodes-by-id root-id)]
      (when node
        (cond-> {:id (:id node)
                 :type (:type node)
                 :name (:name node)}
          (= :leaf (:type node))
          (merge (cond-> {}
                   (:executor node) (assoc :executor (:executor node))
                   (:model node) (assoc :model (:model node))
                   (:fn node) (assoc :fn (:fn node))
                   (:instruction node) (assoc :instruction (:instruction node))
                   (seq (:reads node)) (assoc :reads (:reads node))
                   (seq (:writes node)) (assoc :writes (:writes node))
                   (:retry node) (assoc :retry (:retry node))
                   (= :decision (:executor node))
                   (merge (cond-> {}
                            (:options-from node) (assoc :options-from (:options-from node))
                            (:bands-from node) (assoc :bands-from (:bands-from node))
                            (:min-confidence node) (assoc :min-confidence (:min-confidence node))
                            (some? (:abstain node)) (assoc :abstain (:abstain node))
                            (:options node) (assoc :options (:options node))))))
          (= :condition (:type node))
          (merge (cond-> {}
                   (:check node) (assoc :check (:check node))
                   (:on-fail node) (assoc :on-fail (:on-fail node))))
          (= :llm-condition (:type node))
          (merge (cond-> {}
                   (:instruction node) (assoc :instruction (:instruction node))
                   (seq (:reads node)) (assoc :reads (:reads node))
                   (:model node) (assoc :model (:model node))))
          (= :parallel (:type node))
          (merge {:success-policy (or (:success-policy node) :all)
                  :failure-policy (or (:failure-policy node) :any)})
          (= :map-each (:type node))
          (merge (cond-> {}
                   (:source-key node) (assoc :source-key (:source-key node))
                   (:item-key node) (assoc :item-key (:item-key node))
                   (:output-key node) (assoc :output-key (:output-key node))
                   (:max-concurrency node) (assoc :max-concurrency (:max-concurrency node))
                   (some? (:preserve-failures? node)) (assoc :preserve-failures? (:preserve-failures? node))))
          (seq (:children-ids node))
          (assoc :children
                 (vec (keep #(queries-build-node-tree nodes-by-id %) (:children-ids node)))))))))

;; =============================================================================
;; Execution Traces
;; =============================================================================

(defn compare-traces-ascending [a b]
  (let [by-time (compare-timestamps (:started-at a) (:started-at b))]
    (if (zero? by-time)
      (compare (str (:trace-id a)) (str (:trace-id b)))
      by-time)))

(defn compare-traces-descending [a b]
  (let [by-time (compare-timestamps (:started-at b) (:started-at a))]
    (if (zero? by-time)
      (compare (str (:trace-id a)) (str (:trace-id b)))
      by-time)))

(defn trace-family-summary [trace]
  (cond-> (select-keys (assoc trace :node-count (count (:node-traces trace)))
                       [:trace-id :sheet-id :correlation-id :root-trace-id :child-trace-ids
                        :status :started-at :duration-ms :node-count])
    (:parent-trace-id trace) (assoc :parent-trace-id (:parent-trace-id trace))))

(defn instant->str
  "Convert an Instant or OffsetDateTime to ISO string for Transit serialization."
  [t]
  (canonical-string t))

(defn- serialize-node-trace
  "Serialize a node trace for Transit, converting timestamps."
  [nt]
  (-> nt
      (update :started-at instant->str)
      (update :completed-at instant->str)))

(defn- serialize-node-trace-summary
  "Serialize a node trace summary (without inputs/outputs) for Transit."
  [nt]
  (-> nt
      (dissoc :inputs :outputs)
      (update :started-at instant->str)
      (update :completed-at instant->str)))

(defn serialize-trace
  "Serialize a full trace for Transit, converting all timestamps.
   Uses summary node-traces (no inputs/outputs) to keep payload small."
  [trace]
  (when trace
    (-> trace
        (update :started-at instant->str)
        (update :completed-at instant->str)
        (update :node-traces #(mapv serialize-node-trace-summary %)))))

;; =============================================================================
;; Structural Diff
;; =============================================================================

(defn- flatten-tree-with-paths
  "Flatten a nested snapshot tree to a map of {node-id {:node ... :path [...]}}."
  ([tree] (flatten-tree-with-paths tree []))
  ([tree path]
   (when tree
     (let [node-id (:id tree)
           node-name (:name tree)
           current-path (conj path node-name)
           children (or (:children tree) [])
           child-results (mapcat #(flatten-tree-with-paths % current-path) children)]
       (cons {node-id {:node tree :path current-path}}
             child-results)))))

(defn- diff-node-fields
  "Compare two nodes and return a list of changes."
  [from-node to-node]
  (let [fields-to-compare [:type :name :executor :model :instruction :fn
                           :options-from :bands-from :min-confidence :abstain
                           :reads :writes :check :on-fail
                           :success-policy :failure-policy
                           :source-key :item-key :output-key :max-concurrency
                           :preserve-failures?]]
    (for [field fields-to-compare
          :let [from-val (get from-node field)
                to-val (get to-node field)]
          :when (not= from-val to-val)]
      {:field field
       :old from-val
       :new to-val})))

(defn diff-node-trees
  "Compute structural diff between two node trees."
  [from-tree to-tree]
  (let [
        from-flat (into {} (flatten-tree-with-paths from-tree))
        to-flat (into {} (flatten-tree-with-paths to-tree))
        from-ids (set (keys from-flat))
        to-ids (set (keys to-flat))
        added-ids (clojure.set/difference to-ids from-ids)
        removed-ids (clojure.set/difference from-ids to-ids)
        common-ids (clojure.set/intersection from-ids to-ids)]
    {:added-nodes (mapv (fn [id]
                          (let [{:keys [node path]} (get to-flat id)]
                            {:id id
                             :name (:name node)
                             :type (:type node)
                             :path path}))
                        added-ids)
     :removed-nodes (mapv (fn [id]
                            (let [{:keys [node path]} (get from-flat id)]
                              {:id id
                               :name (:name node)
                               :type (:type node)
                               :path path}))
                          removed-ids)
     :modified-nodes (vec (for [id common-ids
                                :let [from-entry (get from-flat id)
                                      to-entry (get to-flat id)
                                      changes (diff-node-fields (:node from-entry) (:node to-entry))]
                                :when (seq changes)]
                            {:id id
                             :name (:name (:node to-entry))
                             :path (:path to-entry)
                             :changes (vec changes)}))}))

(defn diff-blackboard-schemas
  "Compute diff between two blackboard schemas."
  [from-schema to-schema]
  (let [from-keys (set (keys from-schema))
        to-keys (set (keys to-schema))
        added-keys (clojure.set/difference to-keys from-keys)
        removed-keys (clojure.set/difference from-keys to-keys)
        common-keys (clojure.set/intersection from-keys to-keys)]
    {:added (vec added-keys)
     :removed (vec removed-keys)
     :modified (vec (for [k common-keys
                          :let [from-val (get from-schema k)
                                to-val (get to-schema k)]
                          :when (not= from-val to-val)]
                      {:key k
                       :old-schema from-val
                       :new-schema to-val}))}))

;; =============================================================================
;; Node Statistics
;; =============================================================================

(defn- percentile
  "Calculate the nth percentile from a sorted list of values."
  [sorted-values n]
  (when (seq sorted-values)
    (let [idx (int (* (/ n 100.0) (dec (count sorted-values))))]
      (nth sorted-values (min idx (dec (count sorted-values)))))))

(defn- top-errors
  "Get the top N most common errors."
  [traces n]
  (->> traces
       (keep :error)
       (frequencies)
       (sort-by val >)
       (take n)
       (mapv (fn [[msg cnt]] {:message msg :count cnt}))))

(defn compute-node-stats
  "Compute statistics for a single node from its traces."
  [node-id traces]
  (when (seq traces)
    (let [first-trace (first traces)
          durations (sort (keep :duration-ms traces))
          statuses (frequencies (map :status traces))
          total (count traces)
          success-count (get statuses :success 0)]
      {:node-id node-id
       :node-name (:node-name first-trace)
       :node-type (:node-type first-trace)
       :execution-count total
       :success-count success-count
       :failure-count (get statuses :failure 0)
       :success-rate (if (pos? total) (double (/ success-count total)) 0.0)
       :avg-duration-ms (when (seq durations)
                          (double (/ (reduce + durations) (count durations))))
       :p50-duration-ms (percentile durations 50)
       :p95-duration-ms (percentile durations 95)
       :common-errors (top-errors traces 5)})))

;; --- Value storage ---

(defn- hex [^bytes bs]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bs)))

(defn content-hash [^bytes bs]
  (str "sha256:" (hex (.digest (MessageDigest/getInstance "SHA-256") bs))))

(defn storage-type [context]
  (or (get-in context [:orc/value-storage :type]) :event-store))

(defn- configured-file-store [context]
  (or (get-in context [:orc/value-storage :file-store])
      (:orc/file-store context)
      (throw (ex-info "Orc :file-store value storage requires a configured file store"
                      {:orc/value-storage (:orc/value-storage context)}))))

(defn- object-key [context tick-id value-id]
  (str (or (get-in context [:orc/value-storage :prefix]) "orc/values")
       "/" (or (:tenant-id context) "default")
       "/" tick-id "/" value-id ".nippy"))

(defn prepare-write
  "Prepare a value-bearing execution event body according to runtime config.
   In file-store mode the object write completes before this returns, so callers
   append no event that points at an object which was never stored."
  [context {:keys [tick-id value] :as body}]
  (case (storage-type context)
    :event-store body
    :file-store
    (let [value-id (or (:value-id body) (random-uuid))
          bytes (nippy/freeze value)
          file-id (object-key context tick-id value-id)
          result (fs/put-file (configured-file-store context)
                              {:file-id file-id :file-contents bytes})]
      (when (or (:cognitect.anomalies/category result) (:cognitect.anomalies/category result))
        (throw (ex-info "Failed to store Orc execution value"
                        {:file-id file-id :anomaly result})))
      (-> body
          (dissoc :value)
          (assoc :value-id value-id
                 :value-profile (profile-value value)
                 :value-reference {:file-id file-id
                                   :byte-size (alength bytes)
                                   :content-hash (content-hash bytes)
                                   :format :nippy})))
    (throw (ex-info "Unknown Orc value storage type"
                    {:type (storage-type context)}))))

(defn event-value
  "Return an execution write's value, loading and verifying a file reference
   when necessary. Throws on missing, truncated, or corrupt objects."
  [context event]
  (if-let [{:keys [file-id byte-size format]
            expected-hash :content-hash} (:value-reference event)]
    (let [bytes (fs/get-file (configured-file-store context) {:file-id file-id})]
      (when-not bytes
        (throw (ex-info "Orc execution value object is missing" {:file-id file-id})))
      (when-not (= byte-size (alength ^bytes bytes))
        (throw (ex-info "Orc execution value object size mismatch"
                        {:file-id file-id :expected byte-size :actual (alength ^bytes bytes)})))
      (let [actual-hash (content-hash bytes)]
        (when-not (= expected-hash actual-hash)
          (throw (ex-info "Orc execution value object hash mismatch"
                          {:file-id file-id :expected expected-hash :actual actual-hash}))))
      (case format
        :nippy (nippy/thaw bytes)
        (throw (ex-info "Unknown Orc execution value format"
                        {:file-id file-id :format format}))))
    (:value event)))

(defn hydrate-event [context event]
  (if (and (contains? #{:sheet/execution-value-written
                        :sheet/execution-value-rejected}
                      (:event/type event))
           (:value-reference event))
    (assoc event :value (event-value context event))
    event))

(defn hydrate-events [context events]
  (mapv #(hydrate-event context %) events))

;; --- Schemas ---

(def durable-value
  [:fn {:error/message "expected a built-in serializable workflow value"} durable-value?])

(def workflow-values [:map-of :keyword durable-value])

;; =============================================================================
;; Command Schemas
;; =============================================================================

(def ^:private schemas-researcher-classification-outcome-command-names
  #{:ontology/assign-task-class
    :ontology/record-task-classification-deferral})

(def ^:private schemas-domain-mint-assignment-vias
  #{:mint-domain-child :mint-sibling-domain-child})

(defn- schemas-domain-axis-deferral?
  "RS-3: the domain-axis deferral recorded BESIDE a structural assignment
   (the wedge's :domain-classification-deferral effect). It reuses the
   deferral command with :fallback-source :domain-coverage, so by name it
   looks like a second outcome — it is not; it is the domain axis of the
   same outcome."
  [effect]
  (and (= :ontology/record-task-classification-deferral (:command/name effect))
       (= :domain-coverage (:fallback-source effect))))

(defn valid-researcher-classification-commit?
  [{:keys [sheet-id tick-id node-id ownership-epoch effects]}]
  (let [outcomes
        (filterv
         #(and (contains? schemas-researcher-classification-outcome-command-names
                          (:command/name %))
               (not (schemas-domain-axis-deferral? %)))
         effects)
        outcome (first outcomes)
        injections
        (filterv #(= :sheet/record-injection (:command/name %)) effects)
        convergence-captures
        (filterv #(= :ontology/record-claim-deltas (:command/name %)) effects)
        fresh-mint-assignment?
        (and (= :ontology/assign-task-class (:command/name outcome))
             (true? (:was-fresh-mint? outcome)))
        convergence-capture (first convergence-captures)
        assignment? (= :ontology/assign-task-class (:command/name outcome))
        domain-mint-assignment?
        (and assignment?
             (contains? schemas-domain-mint-assignment-vias (:assigned-via outcome)))
        mints (filterv #(= :ontology/mint-domain-child (:command/name %)) effects)
        mint (first mints)
        domain-deferrals (filterv schemas-domain-axis-deferral? effects)
        domain-deferral (first domain-deferrals)
        bound-to-campaign?
        (fn [effect]
          (and (= sheet-id (:source-sheet-id effect))
               (= tick-id (:source-tick-id effect))
               (= node-id (:source-node-id effect))))]
    (and (= 1 (count outcomes))
         (bound-to-campaign? outcome)
         (= ownership-epoch (:researcher-ownership-epoch outcome))
         (<= (count injections) 1)
         (every? #(and (= sheet-id (:sheet-id %))
                       (= tick-id (:tick-id %))
                       (= node-id (:node-id %)))
                 injections)
         (if fresh-mint-assignment?
           (and (= 1 (count convergence-captures))
                (= :tree-class (:granularity convergence-capture))
                (= (:assigned-tree-id outcome)
                   (:target-identifier convergence-capture)))
           (empty? convergence-captures))
         (if domain-mint-assignment?
           (and (= 1 (count mints))
                (bound-to-campaign? mint)
                (= (:assigned-tree-id outcome) (:child-tree-id mint)))
           (empty? mints))
         (<= (count domain-deferrals) 1)
         (or (nil? domain-deferral)
             (and assignment?
                  (bound-to-campaign? domain-deferral)
                  (= ownership-epoch
                     (:researcher-ownership-epoch domain-deferral)))))))

(def schemas-retained-option-keys
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

(defn schemas-valid-retained-options?
  "Retain only known data-only request controls; never credentials or effects."
  [options]
  (and (map? options) (every? schemas-retained-option-keys (keys options))
       (configuration-data? options)))

(def schemas-retained-plan-schema
  [:map {:closed true}
   [:version [:= 1]]
   [:policy [:enum :temporary-transport :all-transport]]
   [:candidates
    [:vector {:min 1}
     [:map {:closed true}
      [:provider :keyword] [:model [:maybe :string]]
      [:protocol [:enum :chat :decision]]
      [:options [:and [:fn map?]
                 [:fn schemas-valid-retained-options?]]]
      [:profile [:map {:closed true} [:version :string]
                 [:output-mode [:maybe [:enum :function-calling :json-schema]]]
                 [:force-tool-choice? :boolean] [:strict-arguments? :boolean]]]]]]])

(def model-call-owner-fields [[:sheet-id :uuid] [:tick-id :uuid] [:node-id :uuid]
                   [:node-start-id :uuid] [:exec-context workflow-values]
                   [:role [:enum :node :prime :subcall]] [:configuration-id :string]
                   [:logical-action-id :string]
                   [:ownership-epoch {:optional true} [:int {:min 1}]]])

(def emitted-model-owner-fields [[:sheet-id :uuid] [:tick-id :uuid] [:node-id :uuid] [:node-start-id :uuid]
                   [:exec-context workflow-values] [:configuration-id {:optional true} :string] [:logical-action-id :string]])

(def emitted-model-binding-fields
  (into emitted-model-owner-fields [[:campaign-sheet-id :uuid] [:campaign-tick-id :uuid] [:campaign-node-id :uuid]
                      [:parent-node-start-id :uuid] [:parent-frontier-id :uuid]
                      [:generated-claim-id :uuid] [:generated-action-id :string]
                      [:iteration-index [:int {:min 0}]] [:ownership-epoch [:int {:min 1}]]]))

;; --- Recoverable function ---

(defn source-sha256 [source]
  (format "%064x" (java.math.BigInteger. 1
                   (.digest (java.security.MessageDigest/getInstance "SHA-256")
                            (.getBytes ^String source "UTF-8")))))

(defn compile-binding
  "Verify and compile one exact function form in a fresh bounded SCI context.
   Captured bindings are intentionally unsupported in version 1."
  [{:keys [version context-version source sha256 bindings]}]
  (when-not (and (= 1 version) (= 1 context-version)
                 (string? source) (= sha256 (source-sha256 source)) (= {} bindings))
    (throw (ex-info "Invalid recoverable generated function binding"
                    {:reason :invalid-recoverable-function-binding})))
  (let [form (try
               (with-open [reader (java.io.PushbackReader. (java.io.StringReader. source))]
                 (let [form (edn/read {:eof :ai.obney.orc.orc-service.core.recoverable-function/eof} reader)]
                   (when-not (= :ai.obney.orc.orc-service.core.recoverable-function/eof (edn/read {:eof :ai.obney.orc.orc-service.core.recoverable-function/eof} reader))
                     (throw (ex-info "Recoverable source contains trailing forms" {})))
                   form))
               (catch Exception e
                 (throw (ex-info "Recoverable source must contain exactly one EDN function form"
                                 {:reason :invalid-recoverable-function-source} e))))]
    (when-not (and (seq? form) (#{'fn 'fn*} (first form)))
      (throw (ex-info "Recoverable source must be one function form"
                      {:reason :invalid-recoverable-function-source})))
    (try
      (let [f (sci/eval-form (build-sci-context {}) form)]
        (when-not (fn? f)
          (throw (ex-info "Recoverable source did not compile to a function" {})))
        (with-meta f (assoc (meta f) :orc/recoverable-fn
                            {:version version :context-version context-version
                             :source source :sha256 sha256 :bindings bindings})))
      (catch Exception e
        (throw (ex-info "Generated function requires unsupported compilation bindings"
                        {:reason :unsupported-recoverable-function-bindings} e))))))

(defn prepare-binding [form]
  (let [source (pr-str form)
        binding {:version 1 :context-version 1 :source source
                 :sha256 (source-sha256 source) :bindings {}}]
    (compile-binding binding)
    binding))

(defn- resolution-symbol [resolved]
  (when (and (:name (meta resolved)) (:ns (meta resolved)))
    (sci/var->symbol resolved)))

(defn- resolution-value [resolved]
  (if (instance? clojure.lang.IDeref resolved) @resolved resolved))

(defn- same-resolution? [actual canonical]
  (and (= (resolution-symbol actual) (resolution-symbol canonical))
       (= (:macro (meta actual)) (:macro (meta canonical)))
       (let [a (resolution-value actual) b (resolution-value canonical)]
         (if (or (fn? a) (fn? b)) (identical? a b) (= a b)))))

(defn prepare-function
  "Accept only functions independent of captured or shadowed producer globals.
   SCI checks denied globals after lexical lookup, preserving real fn/let locals.
   The accepted function uses the same versioned context as later restoration."
  [form producer-context]
  (let [binding (prepare-binding form)
        canonical-context (build-sci-context {})
        differing (into #{}
                        (mapcat (fn [sym]
                                  (let [actual (sci/resolve producer-context sym)
                                        canonical (sci/resolve canonical-context sym)]
                                    (when-not (same-resolution? actual canonical)
                                      (remove nil? [sym (resolution-symbol actual)
                                                       (resolution-symbol canonical)])))))
                        (filter symbol? (tree-seq coll? seq form)))]
    (when (seq differing)
      (try
        (sci/eval-form (build-sci-context {:deny differing}) form)
        (catch Exception e
          (throw (ex-info "Generated function uses captured or shadowed compilation bindings"
                          {:reason :unsupported-recoverable-function-context} e)))))
    (compile-binding binding)))

;; --- Rlm sandbox ---

(def quoted-inline-function-source-message
  "The rejection message emit-tree!-fn throws when a checkpointed campaign's
   :code node carries an already-evaluated closure instead of quoted (fn ...)
   source. Exported (not private) and used BOTH at the throw site below and
   by the executor's diagnose-parse-error reactive hint, so the two stay
   byte-identical by construction and the hint can key off this constant's
   text (or the ex-data :requirement keyword) instead of a regex over prose."
  (str "Durable emit-tree! code nodes require quoted (fn ...) source; an "
       "already-evaluated closure cannot be recorded as durable source"))

(defn- preview-string
  "Preview a string value with head/tail truncation for large strings."
  [s max-preview]
  (let [len (count s)]
    (if (<= len max-preview)
      {:type :string :size len :value s}
      (let [head-size (quot max-preview 2)
            tail-size (- max-preview head-size 20)
            head (subs s 0 head-size)
            tail (subs s (- len tail-size))
            hidden (- len head-size tail-size)]
        {:type :string
         :size len
         :preview (str head " [..." hidden " chars...] " tail)}))))

(defn- preview-map
  "Preview a map showing its keys and structure."
  [m]
  {:type :map
   :keys (vec (keys m))
   :size (count m)})

(defn- preview-vector
  "Preview a vector showing length and a small sample.

   U12: Primitive scalars in the sample (numbers, keywords, booleans, short
   strings) are passed through unchanged so the preview reads naturally
   for small data. LARGE strings and collections are recursively previewed
   so that, e.g., vectors of data-URI image renders don't inline their
   full content into the prompt (which would blow up the prompt size by
   hundreds of KB)."
  [preview-item v max-sample]
  {:type :vector
   :length (count v)
   :sample (vec (map (fn [item]
                       (cond
                         (and (string? item) (> (count item) 200)) (preview-item item)
                         (coll? item) (preview-item item)
                         :else item))
                     (take max-sample v)))})

(defn preview-value
  "Create a preview of a value for LLM context (token space).

   Small values are shown in full. Large values get metadata preview:
   - Strings: head + tail with middle truncated
   - Maps: key names and size
   - Vectors: length and sample elements"
  [value & {:keys [max-string max-sample] :or {max-string 500 max-sample 3}}]
  (cond
    (nil? value)
    {:type :nil :value nil}

    (string? value)
    (preview-string value max-string)

    (map? value)
    (preview-map value)

    (vector? value)
    (preview-vector preview-value value max-sample)

    (coll? value)
    (preview-vector preview-value (vec value) max-sample)

    :else
    {:type (keyword (.getSimpleName (type value))) :value value}))

(defn build-inputs-preview
  "Build preview map of all inputs for LLM context."
  [inputs]
  (reduce-kv
   (fn [acc k v]
     (assoc acc k (if (contains? v :value)
                    (preview-value (:value v))
                    {:type :absent})))
   {}
   inputs))

;; =============================================================================
;; LLM Primitive Execution
;; =============================================================================

(defn rlm-sandbox-schema-field-type
  "Extract :field-type from a Malli schema's properties map, if present.
   Mirrors the same helper in executor.clj's build-field — kept inline here
   to avoid a cross-file dependency for a few LOC.

   Returns the field-type keyword (e.g. :image) or nil."
  [schema]
  (when (and schema (vector? schema))
    (try
      (:field-type (m/properties schema))
      (catch Exception _ nil))))

(defn inline-contract
  [node-name key declared value direction]
  (let [schema (or declared
                   (if (= :input direction)
                     (try
                       (infer-specific-schema value [key])
                       (catch clojure.lang.ExceptionInfo e
                         (throw (ex-info (str "Inline LLM '" node-name "' needs a specific input contract at "
                                              (pr-str (:path (ex-data e))) "; declare its blackboard schema")
                                         (merge (ex-data e)
                                                {:reason :missing-inline-contract :node-name node-name
                                                 :direction direction :contract-reason (:reason (ex-data e))})
                                         e))))
                     (throw (ex-info (str "Inline LLM '" node-name "' needs an output contract at "
                                          (pr-str [key]) "; declare its blackboard schema")
                                     {:reason :missing-inline-contract :node-name node-name
                                      :direction direction :path [key]
                                      :contract-reason :missing-output-schema}))))]
    (when-let [violation (first (violations schema [key]))]
      (throw (ex-info (str "Inline LLM '" node-name "' needs a specific contract at "
                           (pr-str (:path violation)))
                      (merge violation {:reason :missing-inline-contract :node-name node-name
                                        :direction direction :contract-reason (:reason violation)}))))
    schema))

(defn- empty-value?
  "True if v is nil, an empty collection, or an empty string. Numbers and
   booleans are never considered empty (zero is a legitimate value)."
  [v]
  (cond
    (nil? v) true
    (string? v) (zero? (count v))
    (coll? v) (empty? v)
    :else false))

(defn validate-final!
  "Validate that final! output matches declared writes.

   Throws if:
   - Output contains keys not in writes
   - Output is missing required keys from writes
   - Output is non-trivially all-empty: every declared write maps to nil,
     an empty collection, or an empty string. This catches the failure
     mode where the model calls (final! ...) with all-blank values to
     satisfy the schema without doing actual work — common in recursive
     mode when the model never emits a tree and gives up at max-iterations."
  ([output declared-writes]
   (validate-final! output declared-writes #{}))
  ([output declared-writes optional-writes]
   (let [output-keys (set (keys output))
         writes-set (set declared-writes)
         required-writes (clojure.set/difference writes-set (set optional-writes))
         extra-keys (clojure.set/difference output-keys writes-set)
         missing-keys (clojure.set/difference required-writes output-keys)]
     (when (seq extra-keys)
       (throw (ex-info (str "final! contains keys not in :writes declaration: " extra-keys
                            ". Declared writes: " declared-writes)
                       {:extra-keys extra-keys
                        :declared-writes declared-writes
                        :output-keys output-keys})))
     (when (seq missing-keys)
       (throw (ex-info (str "final! is missing required keys from :writes: " missing-keys
                            ". Declared writes: " declared-writes)
                       {:missing-keys missing-keys
                        :declared-writes declared-writes
                        :output-keys output-keys})))
     (when (and (seq declared-writes)
                (every? #(empty-value? (get output %)) declared-writes))
       (throw (ex-info (str "final! called with all empty values — every declared write "
                            "maps to nil/empty-collection/empty-string. The model should "
                            "produce meaningful work via emit-tree! before terminating. "
                            "Declared writes: " declared-writes)
                       {:empty-keys (vec declared-writes)
                        :declared-writes declared-writes
                        :output output})))
     output)))

(defn execute-rlm-code
  "Execute Clojure code in the RLM sandbox.

   Returns:
   - :stdout - Captured stdout
   - :result - Evaluation result
   - :error - Error message if failed
   - :error-data - CV-B (C7): (ex-data e) when the thrown exception carries
     structured ex-data (e.g. emit-tree!-fn's {:requirement
     :quoted-inline-function-source}), else nil. Carried alongside :error so
     the executor's reactive hint can key off the requirement keyword instead
     of parsing the message.
   - :final-output - The validated output from final! (if called)
   - :sub-llm-usage - Aggregated token usage from all sub-LLM calls"
  [{:keys [sci-ctx final-output usage-tracker]} code-string]
  (let [stdout-writer (StringWriter.)]
    (try
      (let [result (call-with-output-writer stdout-writer
                     #(sci/eval-string* sci-ctx code-string))]
        {:stdout (str stdout-writer)
         :result (pr-str result)
         :raw-result result
         :error nil
         :final-output @final-output
         :sub-llm-usage @usage-tracker})
      (catch Exception e
        {:stdout (str stdout-writer)
         :result nil
         :raw-result nil
         :error-class (.getName (class e))
         :error (.getMessage e)
         :error-data (or (ex-data (ex-cause e)) (ex-data e))
         :final-output @final-output
         :sub-llm-usage @usage-tracker}))))

;; --- Read models ---

;; =============================================================================
;; Event Type Sets
;; =============================================================================

(def sheet-core-events
  "Core events that directly affect sheet read model"
  #{:sheet/sheet-created
    :sheet/sheet-renamed
    :sheet/sheet-deleted
    :sheet/node-created
    :sheet/node-deleted
    :sheet/version-published
    :sheet/draft-stashed
    :sheet/draft-reverted
    :sheet/stash-restored
    :sheet/execution-mode-set
    :sheet/content-hash-set})

(def node-events
  "Events that affect node read model"
  #{:sheet/node-created
    :sheet/node-moved
    :sheet/node-reordered
    :sheet/node-deleted
    :sheet/node-name-set
    :sheet/node-instruction-set
    :sheet/node-context-set
    :sheet/node-io-set
    :sheet/node-decorators-set
    :sheet/node-check-set
    :sheet/node-executor-set
    :sheet/node-model-policy-set
    :sheet/model-fallback-policy-set
    :sheet/node-retry-set
    :sheet/node-judges-set
    :sheet/parallel-config-set
    :sheet/map-each-config-set
    :sheet/llm-condition-config-set
    :sheet/repl-researcher-config-set
    :sheet/delegate-config-set
    :sheet/node-execution-started
    :sheet/node-execution-completed})

(def blackboard-events
  "Events that affect blackboard read model"
  #{:sheet/key-declared
    :sheet/key-schema-updated
    :sheet/key-value-set
    :sheet/key-deleted})

(def judge-events
  "Events that affect judge read model"
  #{:sheet/judge-declared
    :sheet/judge-revised})

(def tick-events
  "Events that affect tick/execution read model"
  #{:sheet/tree-tick-started
    :sheet/node-execution-started
    :sheet/node-execution-completed
    :sheet/tree-tick-completed
    :sheet/tick-cancelled})

(def version-events
  "Events that affect version/stash read model"
  #{:sheet/version-published
    :sheet/draft-stashed
    :sheet/draft-reverted
    :sheet/stash-restored
    :sheet/execution-mode-set
    :sheet/content-hash-set})

(def draft-dirty-events
  "Events that mark draft as dirty (differs from published)"
  #{:sheet/node-created
    :sheet/node-moved
    :sheet/node-reordered
    :sheet/node-deleted
    :sheet/node-name-set
    :sheet/node-instruction-set
    :sheet/node-context-set
    :sheet/node-io-set
    :sheet/node-decorators-set
    :sheet/node-check-set
    :sheet/node-executor-set
    :sheet/node-model-policy-set
    :sheet/model-fallback-policy-set
    :sheet/node-retry-set
    :sheet/node-judges-set
    :sheet/parallel-config-set
    :sheet/map-each-config-set
    :sheet/llm-condition-config-set
    :sheet/repl-researcher-config-set
    :sheet/delegate-config-set
    :sheet/key-declared
    :sheet/key-schema-updated
    :sheet/key-deleted
    :sheet/judge-declared
    :sheet/judge-revised})

(def sheet-events
  "Events that affect sheet read model (includes draft-dirty tracking and versioning)"
  (clojure.set/union sheet-core-events draft-dirty-events version-events))

(def all-sheet-events
  "All events for a complete sheet view"
  (clojure.set/union sheet-events node-events blackboard-events tick-events version-events))

;; =============================================================================
;; Sheets Projection
;; =============================================================================

(defmulti sheets*
  "Apply event to sheets read model"
  (fn [_state event] (:event/type event)))

(defmethod sheets* :sheet/sheet-created
  [state event]
  (assoc state (:sheet-id event)
         {:id (:sheet-id event)
          :name (:name event)
          :root-node-id nil
          :created-at (str (:event/timestamp event))}))

(defmethod sheets* :sheet/sheet-renamed
  [state event]
  (assoc-in state [(:sheet-id event) :name] (:new-name event)))

(defmethod sheets* :sheet/node-created
  [state event]
  (if (nil? (:parent-id event))
    (assoc-in state [(:sheet-id event) :root-node-id] (:node-id event))
    state))

(defmethod sheets* :sheet/node-deleted
  [state event]
  (let [sheet-id (:sheet-id event)
        node-id (:node-id event)
        current-root (get-in state [sheet-id :root-node-id])]
    (if (= current-root node-id)
      (assoc-in state [sheet-id :root-node-id] nil)
      state)))

(defmethod sheets* :sheet/sheet-deleted
  [state event]
  (dissoc state (:sheet-id event)))

(defmethod sheets* :sheet/version-published
  [state event]
  (-> state
      (assoc-in [(:sheet-id event) :published-version] (:version-number event))
      (assoc-in [(:sheet-id event) :draft-dirty?] false)))

(defmethod sheets* :sheet/draft-stashed
  [state event]
  (assoc-in state [(:sheet-id event) :has-stash?] true))

(defmethod sheets* :sheet/draft-reverted
  [state event]
  (assoc-in state [(:sheet-id event) :draft-dirty?] false))

(defmethod sheets* :sheet/stash-restored
  [state event]
  (-> state
      (assoc-in [(:sheet-id event) :has-stash?] false)
      (assoc-in [(:sheet-id event) :draft-dirty?] true)))

(defmethod sheets* :sheet/execution-mode-set
  [state event]
  (assoc-in state [(:sheet-id event) :execution-mode] (:mode event)))

(defmethod sheets* :sheet/content-hash-set
  [state event]
  (assoc-in state [(:sheet-id event) :content-hash] (:content-hash event)))

(defmethod sheets* :default
  [state event]
  (let [sheet-id (:sheet-id event)
        event-type (:event/type event)]
    (if (and sheet-id
             (contains? draft-dirty-events event-type)
             (get-in state [sheet-id :published-version]))
      (assoc-in state [sheet-id :draft-dirty?] true)
      state)))

(defn sheets
  "Build sheets read model from events"
  [initial-state events]
  (reduce sheets* initial-state events))

;; =============================================================================
;; Nodes Projection
;; =============================================================================

(defmulti nodes*
  "Apply event to nodes read model"
  (fn [_state event] (:event/type event)))

(defmethod nodes* :sheet/node-created
  [state event]
  (let [node-id (:node-id event)
        parent-id (:parent-id event)
        index (or (:index event) 0)]
    (-> state
        (assoc node-id
               {:id node-id
                :sheet-id (:sheet-id event)
                :type (:type event)
                :name (case (:type event)
                        :leaf "New Leaf"
                        :sequence "Sequence"
                        :fallback "Fallback"
                        :condition "Condition"
                        :llm-condition "LLM Condition"
                        :parallel "Parallel"
                        :map-each "Map Each"
                        :repl-researcher "REPL Researcher"
                        :delegate "Delegate")
                :parent-id parent-id
                :children-ids []
                :status :idle
                :instruction nil
                :reads []
                :writes []
                :decorators []
                :executor nil
                :model nil
                :fn nil
                :tools nil
                :retry nil
                :check nil
                :success-policy nil
                :failure-policy nil
                :source-key nil
                :item-key nil
                :output-key nil
                :max-concurrency nil
                :preserve-failures? nil
                :last-error nil})
        (cond-> parent-id
          (update-in [parent-id :children-ids]
                     (fn [children]
                       (let [children (or children [])]
                         (vec (concat (take index children)
                                      [node-id]
                                      (drop index children))))))))))

(defmethod nodes* :sheet/node-moved
  [state event]
  (let [node-id (:node-id event)
        old-parent-id (:old-parent-id event)
        new-parent-id (:new-parent-id event)
        index (:index event)]
    (-> state
        (assoc-in [node-id :parent-id] new-parent-id)
        (cond-> old-parent-id
          (update-in [old-parent-id :children-ids]
                     (fn [children]
                       (vec (remove #(= % node-id) children)))))
        (update-in [new-parent-id :children-ids]
                   (fn [children]
                     (let [children (or children [])]
                       (vec (concat (take index children)
                                    [node-id]
                                    (drop index children)))))))))

(defmethod nodes* :sheet/node-reordered
  [state event]
  (let [node-id (:node-id event)
        parent-id (get-in state [node-id :parent-id])
        new-index (:new-index event)]
    (if parent-id
      (update-in state [parent-id :children-ids]
                 (fn [children]
                   (let [without-node (vec (remove #(= % node-id) children))]
                     (vec (concat (take new-index without-node)
                                  [node-id]
                                  (drop new-index without-node))))))
      state)))

(defmethod nodes* :sheet/node-deleted
  [state event]
  (let [node-id (:node-id event)
        parent-id (get-in state [node-id :parent-id])]
    (-> state
        (cond-> parent-id
          (update-in [parent-id :children-ids]
                     (fn [children]
                       (vec (remove #(= % node-id) children)))))
        (dissoc node-id))))

(defmethod nodes* :sheet/node-name-set
  [state event]
  (assoc-in state [(:node-id event) :name] (:name event)))

(defmethod nodes* :sheet/node-instruction-set
  [state event]
  (assoc-in state [(:node-id event) :instruction] (:instruction event)))

(defmethod nodes* :sheet/node-context-set
  [state event]
  (assoc-in state [(:node-id event) :context] (:context event)))

(defmethod nodes* :sheet/node-io-set
  [state event]
  (-> state
      (assoc-in [(:node-id event) :reads] (:reads event))
      (assoc-in [(:node-id event) :writes] (:writes event))))

(defmethod nodes* :sheet/node-decorators-set
  [state event]
  (assoc-in state [(:node-id event) :decorators] (:decorators event)))

(defmethod nodes* :sheet/node-check-set
  [state event]
  (assoc-in state [(:node-id event) :check] (:check event)))

(defmethod nodes* :sheet/node-executor-set
  [state event]
  (-> state
      (assoc-in [(:node-id event) :executor] (:executor event))
      (assoc-in [(:node-id event) :model] (:model event))
      (assoc-in [(:node-id event) :fn] (:fn event))
      (assoc-in [(:node-id event) :recoverable-fn] (:recoverable-fn event))
      (assoc-in [(:node-id event) :tool] (:tool event))
      (assoc-in [(:node-id event) :tools] (:tools event))
      (assoc-in [(:node-id event) :options] (:options event))
      (assoc-in [(:node-id event) :tool-caller-fn] (:tool-caller-fn event))
      (assoc-in [(:node-id event) :tool-contracts] (:tool-contracts event))
      (assoc-in [(:node-id event) :options-from] (:options-from event))
      (assoc-in [(:node-id event) :bands-from] (:bands-from event))
      (assoc-in [(:node-id event) :min-confidence] (:min-confidence event))
      (assoc-in [(:node-id event) :abstain] (:abstain event))))

(defmethod nodes* :sheet/node-model-policy-set [state event]
  (update state (:node-id event) merge
          (select-keys event [:provider :fallback-models :fallback-policy :options])))

(defmethod nodes* :sheet/model-fallback-policy-set [state event]
  (assoc-in state [(:node-id event) :model-fallback-policy] (:model-fallback-policy event)))

(defmethod nodes* :sheet/node-retry-set
  [state event]
  (assoc-in state [(:node-id event) :retry] (:retry event)))

(defmethod nodes* :sheet/parallel-config-set
  [state event]
  (-> state
      (assoc-in [(:node-id event) :success-policy] (:success-policy event))
      (assoc-in [(:node-id event) :failure-policy] (:failure-policy event))))

(defmethod nodes* :sheet/map-each-config-set
  [state event]
  (cond-> state
    true (assoc-in [(:node-id event) :source-key] (:source-key event))
    true (assoc-in [(:node-id event) :item-key] (:item-key event))
    true (assoc-in [(:node-id event) :output-key] (:output-key event))
    true (assoc-in [(:node-id event) :max-concurrency] (:max-concurrency event))
    (some? (:preserve-failures? event))
    (assoc-in [(:node-id event) :preserve-failures?] (:preserve-failures? event))))

(defmethod nodes* :sheet/llm-condition-config-set
  [state event]
  (-> state
      (assoc-in [(:node-id event) :instruction] (:instruction event))
      (assoc-in [(:node-id event) :reads] (:reads event))
      (assoc-in [(:node-id event) :model] (:model event))))

(defmethod nodes* :sheet/repl-researcher-config-set
  [state event]
  (-> state
      (assoc-in [(:node-id event) :instruction] (:instruction event))
      (assoc-in [(:node-id event) :reads] (:reads event))
      (assoc-in [(:node-id event) :writes] (:writes event))
      (assoc-in [(:node-id event) :mcp-tools] (:mcp-tools event))
      (assoc-in [(:node-id event) :tool-contracts] (:tool-contracts event))
      (assoc-in [(:node-id event) :tool-caller-fn] (:tool-caller-fn event))
      (assoc-in [(:node-id event) :browser-tools] (:browser-tools event))
      (assoc-in [(:node-id event) :model] (:model event))
      (assoc-in [(:node-id event) :max-iterations] (:max-iterations event))
      (assoc-in [(:node-id event) :rlm] (:rlm event))
      (assoc-in [(:node-id event) :options] (:options event))
      (assoc-in [(:node-id event) :timeout-ms] (:timeout-ms event))))

(defmethod nodes* :sheet/delegate-config-set
  [state event]
  (-> state
      (assoc-in [(:node-id event) :target-sheet-id] (:target-sheet-id event))
      (assoc-in [(:node-id event) :reads] (:reads event))
      (assoc-in [(:node-id event) :writes] (:writes event))
      (assoc-in [(:node-id event) :delegate-timeout-ms] (:timeout-ms event))
      (assoc-in [(:node-id event) :delegate-max-ticks] (:max-ticks event))
      (assoc-in [(:node-id event) :inherit-ontology?] (:inherit-ontology? event))))

(defmethod nodes* :sheet/node-judges-set
  [state event]
  (assoc-in state [(:node-id event) :judges] (:judges event)))

(defmethod nodes* :sheet/node-execution-started
  [state event]
  (assoc-in state [(:node-id event) :status] :running))

(defmethod nodes* :sheet/node-execution-completed
  [state event]
  (-> state
      (assoc-in [(:node-id event) :status] (:status event))
      (assoc-in [(:node-id event) :last-error] (:error event))))

(defmethod nodes* :default [state _] state)

(defn nodes
  "Build nodes read model from events"
  [initial-state events]
  (reduce nodes* initial-state events))

;; =============================================================================
;; Blackboard Projection
;; =============================================================================

(defn blackboard-entity-id
  "Blackboard key names are local to a sheet. Grain's partitioned projection
   engine requires entity ids to be globally unique across all partitions, so
   include the sheet id instead of letting a same-named key look like an entity
   moving from one sheet partition to another."
  [event-or-entry]
  [(:sheet-id event-or-entry) (:key event-or-entry)])

(defmulti blackboard*
  "Apply event to blackboard read model"
  (fn [_state event] (:event/type event)))

(defmethod blackboard* :sheet/key-declared
  [state event]
  (assoc state (blackboard-entity-id event)
         {:sheet-id (:sheet-id event)
          :key (:key event)
          :schema (:schema event)
          :value nil
          :version 0}))

(defmethod blackboard* :sheet/key-schema-updated
  [state event]
  (assoc-in state [(blackboard-entity-id event) :schema] (:schema event)))

(defmethod blackboard* :sheet/key-value-set
  [state event]
  (-> state
      (assoc-in [(blackboard-entity-id event) :value] (:value event))
      (assoc-in [(blackboard-entity-id event) :version] (:version event))))

(defmethod blackboard* :sheet/key-deleted
  [state event]
  (dissoc state (blackboard-entity-id event)))

(defmethod blackboard* :default [state _] state)

(defn read-models-blackboard
  "Build blackboard read model from events"
  [initial-state events]
  (reduce blackboard* (or initial-state {}) events))

;; =============================================================================
;; Judges Projection
;; =============================================================================

(defn judge-entity-id
  "Judge names, like blackboard keys, are local to their host sheet."
  [event-or-entry]
  [(:sheet-id event-or-entry) (:judge-name event-or-entry)])

(defn- judge-definition-entry
  "The current-definition view of a declared judge config at a revision. The
   declared config is kept as the host of the entry (existing readers use its
   keys directly) and enriched with the DEFAULTED purposes."
  [event]
  (let [jc (:judge-config event)
        jc+eval (cond-> jc
                  (:sheet-id jc) (assoc :eval-sheet-id (:sheet-id jc)))]
    (merge jc+eval
           {:sheet-id (:sheet-id event)
            :judge-name (:judge-name event)
            :purposes (effective-purposes jc)})))

(defmulti judges*
  "Apply event to judges read model"
  (fn [_state event] (:event/type event)))

(defmethod judges* :sheet/judge-declared
  [state event]
  (let [revision (or (:revision-number event) 1)]
    (assoc state (judge-entity-id event)
           (merge (judge-definition-entry event)
                  {:criteria-version (or (:criteria-version event) 1)
                   :revision-number revision
                   :revisions [{:revision-number revision
                                :judge-config (:judge-config event)}]}))))

(defmethod judges* :sheet/judge-revised
  [state event]
  (let [k (judge-entity-id event)
        previous (get state k)
        revision (:revision-number event)]
    (assoc state k
           (merge (judge-definition-entry event)
                  {:criteria-version (or (:criteria-version previous) 1)
                   :revision-number revision
                   :revisions (conj (vec (:revisions previous))
                                    {:revision-number revision
                                     :judge-config (:judge-config event)})}))))

(defmethod judges* :default [state _] state)

(defn read-models-judges
  "Build judges read model from events"
  [initial-state events]
  (reduce judges* (or initial-state {}) events))

(defmulti ticks*
  "Apply event to ticks read model"
  (fn [_state event] (:event/type event)))

(defmethod ticks* :sheet/tree-tick-started
  [state event]
  (let [tick-id (:tick-id event)
        iteration (or (:iteration event) 1)
        existing (get state tick-id)]
    (if existing
      (-> state
          (assoc-in [tick-id :iteration] iteration)
          (assoc-in [tick-id :status] :running))
      (assoc state tick-id
             (cond-> {:id tick-id
                      :sheet-id (:sheet-id event)
                      :status :running
                      :iteration iteration
                      :started-at (str (:event/timestamp event))
                      :completed-at nil
                      :root-status nil}
               (:parent-tick-id event)
               (assoc :parent-tick-id (:parent-tick-id event))
               (:correlation-id event)
               (assoc :correlation-id (:correlation-id event)))))))

(defmethod ticks* :sheet/tree-tick-completed
  [state event]
  (let [tick-id (:tick-id event)]
    (cond-> (-> state
                (assoc-in [tick-id :root-status] (:root-status event))
                (assoc-in [tick-id :completed-at] (str (:event/timestamp event))))
      (not= :cancelled (get-in state [tick-id :status]))
      (assoc-in [tick-id :status] :completed))))

(defmethod ticks* :sheet/tick-cancelled
  [state event]
  (-> state
      (assoc-in [(:tick-id event) :status] :cancelled)
      (assoc-in [(:tick-id event) :cancelled-at] (str (:event/timestamp event)))))

(defmethod ticks* :default [state _] state)

(defn ticks
  "Build ticks read model from events"
  [initial-state events]
  (reduce ticks* (or initial-state {}) events))

;; =============================================================================
;; Versions Projection
;; =============================================================================

(defmulti versions*
  "Apply event to versions read model.
   State structure: {sheet-id {version-number version-snapshot}}"
  (fn [_state event] (:event/type event)))

(defmethod versions* :sheet/version-published
  [state event]
  (let [sheet-id (:sheet-id event)
        version-num (:version-number event)]
    (assoc-in state [sheet-id version-num]
              {:snapshot-id (:snapshot-id event)
               :sheet-id sheet-id
               :version-number version-num
               :published-at (str (:event/timestamp event))
               :description (:description event)
               :snapshot (:snapshot event)})))

(defmethod versions* :default [state _] state)

(defn versions
  "Build versions read model from events"
  [initial-state events]
  (reduce versions* (or initial-state {}) events))

;; =============================================================================
;; Stash Projection
;; =============================================================================

(defmulti stashes*
  "Apply event to stashes read model.
   State structure: {sheet-id stash} - only one stash per sheet"
  (fn [_state event] (:event/type event)))

(defmethod stashes* :sheet/draft-stashed
  [state event]
  (let [sheet-id (:sheet-id event)]
    (assoc state sheet-id
           {:stash-id (:stash-id event)
            :sheet-id sheet-id
            :stashed-at (str (:event/timestamp event))
            :snapshot (:snapshot event)})))

(defmethod stashes* :sheet/stash-restored
  [state event]
  (dissoc state (:sheet-id event)))

(defmethod stashes* :sheet/draft-reverted
  [state event]
  state)

(defmethod stashes* :default [state _] state)

(defn stashes
  "Build stashes read model from events"
  [initial-state events]
  (reduce stashes* (or initial-state {}) events))

(defn is-descendant?
  "Check if potential-descendant is a descendant of ancestor-id"
  [nodes-by-id ancestor-id potential-descendant-id]
  (loop [to-check #{potential-descendant-id}
         visited #{}]
    (if (empty? to-check)
      false
      (let [current (first to-check)
            remaining (disj to-check current)]
        (if (visited current)
          (recur remaining visited)
          (let [node (get nodes-by-id current)
                parent-id (:parent-id node)]
            (cond
              (= parent-id ancestor-id) true
              (nil? parent-id) (recur remaining (conj visited current))
              :else (recur (conj remaining parent-id) (conj visited current)))))))))

;; =============================================================================
;; Traces Projection
;; =============================================================================

(def trace-events
  "Events that affect execution traces read model"
  #{:sheet/execution-traced :sheet/execution-trace-refreshed})

(defmulti traces*
  "Apply event to traces read model.
   State structure: {trace-id trace}"
  (fn [_state event] (:event/type event)))

(defmethod traces* :sheet/execution-traced
  [state event]
  (let [trace-id (:trace-id event)]
    (assoc state trace-id
           (cond-> {:trace-id trace-id
                    :sheet-id (:sheet-id event)
                    :correlation-id (:correlation-id event)
                    :root-trace-id (:root-trace-id event)
                    :child-trace-ids (vec (or (:child-trace-ids event) []))
                    :started-at (canonical-string (:started-at event))
                    :completed-at (canonical-string (:completed-at event))
                    :duration-ms (:duration-ms event)
                    :status (:status event)
                    :configured-max-ticks (:configured-max-ticks event)
                    :consumed-ticks (:consumed-ticks event)
                    :terminal-reason (:terminal-reason event)
                    :input-snapshot (:input-snapshot event)
                    :output-snapshot (:output-snapshot event)
                    :researcher-iterations (vec (or (:researcher-iterations event) []))
                    :researcher-events (vec (or (:researcher-events event) []))
                    :node-traces (mapv (fn [node-trace]
                                         (-> node-trace
                                             (update :started-at canonical-string)
                                             (update :completed-at canonical-string)))
                                       (:node-traces event))}
             (nil? (:correlation-id event)) (dissoc :correlation-id)
             (:parent-trace-id event) (assoc :parent-trace-id (:parent-trace-id event))
             (:version-number event) (assoc :version-number (:version-number event))
             (some? (:source-event-count event))
             (assoc :source-event-count (:source-event-count event))
             (:error event) (assoc :error (:error event))))))

(defmethod traces* :sheet/execution-trace-refreshed
  [state event]
  (traces* state (assoc event :event/type :sheet/execution-traced)))

(defmethod traces* :default [state _] state)

(defn traces
  "Build traces read model from events"
  [initial-state events]
  (reduce traces* (or initial-state {}) events))

(def tick-execution-context-events
  "Events that affect tick-scoped execution contexts"
  #{:sheet/tree-tick-started
    :sheet/execution-value-written
    :sheet/execution-value-referenced})

(defn- metadata-entry
  "A blackboard entry reduced to shape: no :value, plus a size :profile when
   there is a value to describe. nil values get no profile — there is no shape
   to record — but the ENTRY still exists, which is what tells a consumer the
   key was declared."
  [entry value]
  (cond-> (dissoc entry :value)
    (some? value) (assoc :profile (profile-value value))))

(defmulti tick-execution-contexts*
  "Apply event to tick execution contexts read model.
   State structure: {tick-id {:nodes-by-id {...}
                              :root-node-id uuid
                              :blackboard {key {:key k :schema s :version n
                                                :profile {...}
                                                :source {:tick-id uuid :event-id uuid}}}
                              :version-number int?
                              :options {...}}}

   Note the blackboard carries no :value — see the section comment above."
  (fn [_state event] (:event/type event)))

(defmethod tick-execution-contexts* :sheet/tree-tick-started
  [state event]
  (if-let [snapshot (:execution-snapshot event)]
    (let [
          bb-entries (reduce-kv (fn [acc k entry]
                                  (assoc acc k (dissoc entry :value)))
                                {}
                                (or (:blackboard-entries snapshot) {}))
          blackboard (reduce-kv
                       (fn [bb key-name source]
                         (let [k (if (string? key-name) (keyword key-name) key-name)]
                           (if (contains? bb k)
                             (assoc-in bb [k :source] source)
                             bb)))
                       bb-entries
                       (or (:seed-sources event) {}))]
      (assoc state (:tick-id event)
             (cond-> {:sheet-id (:sheet-id event)
                      :nodes-by-id (:nodes-by-id snapshot)
                      :root-node-id (:root-node-id snapshot)
                      :blackboard blackboard
                      :version-number (:version-number snapshot)
                      :options (:options event)}
               (:instruction-overrides snapshot)
               (assoc :instruction-overrides (:instruction-overrides snapshot))
               (:correlation-id event)
               (assoc :correlation-id (:correlation-id event))
               (:tool-context event)
               (assoc :tool-context (:tool-context event)))))
    state))

(defmethod tick-execution-contexts* :sheet/execution-value-written
  [state event]
  (let [tick-id (:tick-id event)
        k (:key event)
        v (:value event)
        value-profile (:value-profile event)]
    (if (contains? state tick-id)
      (-> state
          (update-in [tick-id :blackboard k]
                     (fn [entry]
                       (cond-> (metadata-entry (or entry {}) v)
                         value-profile (assoc :profile value-profile))))
          (update-in [tick-id :blackboard k :version] (fnil inc 0))
          (assoc-in [tick-id :blackboard k :source]
                    {:tick-id tick-id :event-id (:event/id event)}))
      state)))

(defmethod tick-execution-contexts* :sheet/execution-value-referenced
  [state event]
  (let [tick-id (:tick-id event)
        k (:key event)]
    (if (get-in state [tick-id :blackboard k])
      (-> state
          (update-in [tick-id :blackboard k :version] (fnil inc 0))
          (assoc-in [tick-id :blackboard k :source] (:source event)))
      state)))

(defmethod tick-execution-contexts* :default [state _] state)

(defn tick-execution-contexts
  "Build tick execution contexts read model from events"
  [initial-state events]
  (reduce tick-execution-contexts* (or initial-state {}) events))

;; =============================================================================
;; In-Progress Executions Projection
;; =============================================================================

(def in-progress-execution-events
  "Events that affect in-progress execution tracking"
  #{:sheet/tree-tick-started
    :sheet/node-execution-started
    :sheet/node-execution-completed
    :sheet/tree-tick-completed
    :sheet/tick-cancelled
    :sheet/execution-traced
    :sheet/sequence-progress-updated
    :sheet/map-each-progress-updated})

(defmulti in-progress-executions*
  "Apply event to in-progress executions read model.
   State structure: {tick-id execution-context}
   where execution-context contains:
   - :tick-id, :sheet-id, :status, :started-at
   - :current-node-id - which node is currently executing
   - :nodes-in-progress - set of node-ids currently executing
   - :sequence-progress - {node-id {:index N :total M}}
   - :map-each-progress - {node-id {:index N :total M}}"
  (fn [_state event] (:event/type event)))

(defmethod in-progress-executions* :sheet/tree-tick-started
  [state event]
  (let [tick-id (:tick-id event)]
    (assoc state tick-id
           {:tick-id tick-id
            :sheet-id (:sheet-id event)
            :status :running
            :started-at (str (:event/timestamp event))
            :current-node-id nil
            :nodes-in-progress #{}
            :sequence-progress {}
            :map-each-progress {}})))

(defmethod in-progress-executions* :sheet/node-execution-started
  [state event]
  (let [tick-id (:tick-id event)
        node-id (:node-id event)]
    (if (contains? state tick-id)
      (-> state
          (assoc-in [tick-id :current-node-id] node-id)
          (update-in [tick-id :nodes-in-progress] conj node-id))
      state)))

(defmethod in-progress-executions* :sheet/node-execution-completed
  [state event]
  (let [tick-id (:tick-id event)
        node-id (:node-id event)]
    (if (contains? state tick-id)
      (-> state
          (update-in [tick-id :nodes-in-progress] disj node-id)
          (update tick-id
                  (fn [ctx]
                    (let [remaining (disj (:nodes-in-progress ctx) node-id)]
                      (assoc ctx :current-node-id (first remaining)))))
          (update-in [tick-id :sequence-progress] dissoc node-id)
          (update-in [tick-id :map-each-progress] dissoc node-id))
      state)))

(defmethod in-progress-executions* :sheet/tree-tick-completed
  [state event]
  (let [tick-id (:tick-id event)]
    (-> state
        (assoc-in [tick-id :status] :completed)
        (assoc-in [tick-id :completed-at] (str (:event/timestamp event)))
        (assoc-in [tick-id :root-status] (:root-status event)))))

(defmethod in-progress-executions* :sheet/tick-cancelled
  [state event]
  (let [tick-id (:tick-id event)]
    (-> state
        (assoc-in [tick-id :status] :cancelled)
        (assoc-in [tick-id :cancelled-at] (str (:event/timestamp event))))))

(defmethod in-progress-executions* :sheet/execution-traced
  [state event]
  (let [trace-id (:trace-id event)]
    state))

(defmethod in-progress-executions* :sheet/sequence-progress-updated
  [state event]
  (let [tick-id (:tick-id event)
        node-id (:node-id event)]
    (if (contains? state tick-id)
      (assoc-in state [tick-id :sequence-progress node-id]
                {:index (:child-index event)
                 :total (:total-children event)})
      state)))

(defmethod in-progress-executions* :sheet/map-each-progress-updated
  [state event]
  (let [tick-id (:tick-id event)
        node-id (:node-id event)]
    (if (contains? state tick-id)
      (assoc-in state [tick-id :map-each-progress node-id]
                {:index (:item-index event)
                 :total (:total-items event)})
      state)))

(defmethod in-progress-executions* :default [state _] state)

(defn in-progress-executions
  "Build in-progress executions read model from events"
  [initial-state events]
  (reduce in-progress-executions* (or initial-state {}) events))

;; =============================================================================
;; Tree Metadata Read Model
;; =============================================================================

(def tree-metadata-events
  "Events for tree metadata"
  #{:sheet/tree-metadata-extracted
    :sheet/sheet-deleted})

(defmulti tree-metadata* (fn [_state event] (:event/type event)))

(defmethod tree-metadata* :default [state _] state)

(defmethod tree-metadata* :sheet/tree-metadata-extracted [state event]
  (assoc state (:sheet-id event) (:metadata event)))

(defmethod tree-metadata* :sheet/sheet-deleted [state event]
  (dissoc state (:sheet-id event)))

(defn tree-metadata
  "Reduce function for tree metadata.
   Returns map of sheet-id -> metadata."
  [initial-state events]
  (reduce tree-metadata* initial-state events))

;; =============================================================================
;; Rolling Metrics Read Model
;; =============================================================================

(def rolling-window-size
  "Number of recent executions to track for rolling metrics"
  100)

(defmulti rolling-metrics* (fn [_state event] (:event/type event)))

(defmethod rolling-metrics* :default [state _] state)

(defmethod rolling-metrics* :sheet/node-execution-completed [state event]
  (let [{:keys [sheet-id node-id status duration-ms]} event
        key [sheet-id node-id]
        success? (= status :success)]
    (update state key
      (fn [metrics]
        (let [metrics (or metrics {:sheet-id sheet-id
                                   :executions []
                                   :success-count 0
                                   :failure-count 0
                                   :total-duration 0})]
          (-> metrics
              (update :executions #(->> (conj % {:status status
                                                  :duration-ms duration-ms
                                                  :timestamp (str (:event/timestamp event))})
                                        (take-last rolling-window-size)
                                        vec))
              (update :success-count #(if success? (inc %) %))
              (update :failure-count #(if (not success?) (inc %) %))
              (update :total-duration #(+ % (or duration-ms 0)))))))))

(def rolling-metrics-events
  "Events that affect rolling metrics read model"
  #{:sheet/node-execution-completed})

(defn rolling-metrics
  "Reduce function for rolling metrics.
   Returns map of [sheet-id node-id] -> metrics."
  [initial-state events]
  (reduce rolling-metrics* initial-state events))

(defn calculate-trend
  "Calculate recent trend: :improving, :declining, or :stable.
   Requires at least 10 executions to calculate."
  [executions]
  (when (>= (count executions) 10)
    (let [recent (take-last 10 executions)
          older (take 10 (drop (- (count executions) 20) executions))
          recent-rate (/ (count (filter #(= :success (:status %)) recent)) 10.0)
          older-rate (when (seq older)
                       (/ (count (filter #(= :success (:status %)) older))
                          (double (count older))))]
      (cond
        (nil? older-rate) :stable
        (> recent-rate (+ older-rate 0.1)) :improving
        (< recent-rate (- older-rate 0.1)) :declining
        :else :stable))))

(defn compute-node-metrics
  "Compute metrics from a rolling metrics entry.
   Returns map with success-rate, failure-rate, avg-duration-ms, trend."
  [metrics]
  (let [n (count (:executions metrics))
        durations (keep :duration-ms (:executions metrics))]
    (when (pos? n)
      {:execution-count n
       :success-rate (double (/ (:success-count metrics) n))
       :failure-rate (double (/ (:failure-count metrics) n))
       :avg-duration-ms (when (seq durations)
                          (double (/ (reduce + durations) (count durations))))
       :recent-trend (calculate-trend (:executions metrics))})))

(defmulti node-type-rolling-metrics* (fn [_state event] (:event/type event)))

(defmethod node-type-rolling-metrics* :default [state _] state)

(defmethod node-type-rolling-metrics* :sheet/node-execution-completed [state event]
  (if-let [nt (:node-type event)]
    (let [{:keys [status duration-ms]} event
          success? (= status :success)]
      (update state nt
        (fn [metrics]
          (let [metrics (or metrics {:node-type nt
                                     :executions []
                                     :success-count 0
                                     :failure-count 0
                                     :total-duration 0})]
            (-> metrics
                (update :executions #(->> (conj % {:status status
                                                    :duration-ms duration-ms
                                                    :timestamp (str (:event/timestamp event))})
                                          (take-last rolling-window-size)
                                          vec))
                (update :success-count #(if success? (inc %) %))
                (update :failure-count #(if (not success?) (inc %) %))
                (update :total-duration #(+ % (or duration-ms 0))))))))
    state))

(defmulti tree-fingerprint-rolling-metrics* (fn [_state event] (:event/type event)))

(defmethod tree-fingerprint-rolling-metrics* :default [state _] state)

(defmethod tree-fingerprint-rolling-metrics* :sheet/rlm-tree-execution-completed [state event]
  (if-let [fp (:tree-fingerprint event)]
    (let [{:keys [status duration-ms]} event
          success? (= status :success)]
      (update state fp
        (fn [metrics]
          (let [metrics (or metrics {:tree-fingerprint fp
                                     :executions []
                                     :success-count 0
                                     :failure-count 0
                                     :total-duration 0})]
            (-> metrics
                (update :executions #(->> (conj % {:status status
                                                    :duration-ms duration-ms
                                                    :timestamp (str (:event/timestamp event))})
                                          (take-last rolling-window-size)
                                          vec))
                (update :success-count #(if success? (inc %) %))
                (update :failure-count #(if (not success?) (inc %) %))
                (update :total-duration #(+ % (or duration-ms 0))))))))
    state))

(def injection-record-events
  "Events that affect the injection-records read model."
  #{:intervention/injection-recorded})

(defn injection-record-entity-id
  "Scope the entity by the whole occurrence, not by node.

   Node ids are local to their sheet and a node re-renders on every tick, so a
   bare node-id would make one entity look like it was moving between sheet
   partitions AND would let a later tick clobber an earlier tick's record —
   the same failure the blackboard's [sheet-id key] scoping exists to prevent."
  [event-or-entry]
  [(:sheet-id event-or-entry) (:tick-id event-or-entry) (:node-id event-or-entry)])

(defmulti injection-records*
  "Apply event to the injection-records read model."
  (fn [_state event] (:event/type event)))

(defmethod injection-records* :default [state _event] state)

(defmethod injection-records* :intervention/injection-recorded
  [state event]
  (assoc state (injection-record-entity-id event)
         (into {} (remove (fn [[k _]] (= "event" (namespace k))) event))))

(defmulti researcher-checkpoints* (fn [_state event] (:event/type event)))

(defmethod researcher-checkpoints* :default [state _event] state)

(defmethod researcher-checkpoints* :rlm/researcher-checkpointed [state event]
  (assoc state [(:tick-id event) (:node-id event)]
         (select-keys event [:sheet-id :tick-id :node-id :checkpoint :checkpointed-at])))

(def researcher-campaign-events
  #{:sheet/tree-tick-started
    :rlm/researcher-frontier-claimed
    :rlm/researcher-resume-state-saved
    :sheet/node-execution-completed
    :sheet/tree-tick-completed
    :sheet/tick-cancelled})

(def ^:private researcher-campaign-terminal-statuses
  #{:success :failure :timeout :blocked :cancelled :abandoned})

(defn- terminal-researcher-campaign? [campaign]
  (contains? researcher-campaign-terminal-statuses (:status campaign)))

(defn- parent-status->campaign-status [tick-state]
  (case (:execution-status tick-state)
    :completed :abandoned
    :cancelled :cancelled
    nil))

(defn- new-or-active-campaign-status [tick-state requested]
  (or (parent-status->campaign-status tick-state) requested))

(defn- update-active-campaigns [campaigns f]
  (into {}
        (map (fn [[node-id campaign]]
               [node-id (if (terminal-researcher-campaign? campaign)
                          campaign
                          (f campaign))]))
        campaigns))

(def ^:private node-status->campaign-status
  {:success :success
   :failure :failure
   :timeout :timeout
   :blocked :blocked})

(defmulti researcher-campaigns* (fn [_state event] (:event/type event)))

(defmethod researcher-campaigns* :default [state _event] state)

(defmethod researcher-campaigns* :sheet/tree-tick-started
  [state event]
  (update state (:tick-id event)
          #(merge {:sheet-id (:sheet-id event)
                   :execution-status :running
                   :campaigns {}}
                  %)))

(defmethod researcher-campaigns* :rlm/researcher-frontier-claimed
  [state event]
  (let [tick-id (:tick-id event)
        node-id (:node-id event)
        tick-state (get state tick-id)
        campaign (get-in tick-state [:campaigns node-id])]
    (if (terminal-researcher-campaign? campaign)
      state
      (assoc-in state [tick-id :campaigns node-id]
                (merge campaign
                       {:sheet-id (:sheet-id event)
                        :tick-id tick-id
                        :node-id node-id
                        :status (new-or-active-campaign-status tick-state :running)
                        :ownership-epoch
                        (max (or (:ownership-epoch campaign) 0)
                             (:ownership-epoch event))
                        :campaign-started-at-ms
                        (or (:campaign-started-at-ms campaign)
                            (:campaign-started-at-ms event))
                        :campaign-deadline-ms
                        (or (:campaign-deadline-ms campaign)
                            (:campaign-deadline-ms event))
                        :started-at (or (:started-at campaign)
                                        (some-> (:campaign-started-at-ms event)
                                                long
                                                java.time.Instant/ofEpochMilli
                                                str)
                                        (:claimed-at event)
                                        (str (:event/timestamp event)))
                        :deadline (or (:deadline campaign)
                                      (some-> (:campaign-deadline-ms event)
                                              long
                                              java.time.Instant/ofEpochMilli
                                              str))
                        :completed-at
                        (when (= :cancelled
                                 (parent-status->campaign-status tick-state))
                          (:parent-completed-at tick-state))})))))

(defmethod researcher-campaigns* :rlm/researcher-resume-state-saved
  [state event]
  (let [tick-id (:tick-id event)
        node-id (:node-id event)
        tick-state (get state tick-id)
        campaign (get-in tick-state [:campaigns node-id])
        resume-state (:resume-state event)]
    (if (terminal-researcher-campaign? campaign)
      state
      (assoc-in state [tick-id :campaigns node-id]
                (cond-> (merge campaign
                               {:sheet-id (:sheet-id event)
                        :tick-id tick-id
                        :node-id node-id
                        :status
                        (new-or-active-campaign-status
                         tick-state
                         (if (:yielded? event) :yielded :running))
                        :ownership-epoch (:ownership-epoch resume-state)
                        :next-iteration-index (:next-iteration resume-state)
                        :started-at
                        (or (:started-at campaign)
                            (some-> (:campaign-started-at-ms resume-state)
                                    long
                                    java.time.Instant/ofEpochMilli
                                    str))
                        :deadline
                        (some-> (:campaign-deadline-ms resume-state)
                                long
                                java.time.Instant/ofEpochMilli
                                str)
                        :completed-at
                        (when (= :cancelled
                                 (parent-status->campaign-status tick-state))
                          (:parent-completed-at tick-state))})
                  (some? (:observed-quantum-duration-ms resume-state))
                  (assoc :observed-quantum-duration-ms
                         (:observed-quantum-duration-ms resume-state))

                  (or (some? (:max-observed-quantum-duration-ms campaign))
                      (some? (:max-observed-quantum-duration-ms resume-state)))
                  (assoc :max-observed-quantum-duration-ms
                         (max (long (or (:max-observed-quantum-duration-ms campaign)
                                        0))
                              (long (or (:max-observed-quantum-duration-ms resume-state)
                                        0)))))))))

(defmethod researcher-campaigns* :sheet/node-execution-completed
  [state event]
  (let [path [(:tick-id event) :campaigns (:node-id event)]
        campaign (get-in state path)
        terminal-status (get node-status->campaign-status (:status event))]
    (if (and campaign terminal-status
             (not (terminal-researcher-campaign? campaign)))
      (assoc-in state path
                (cond-> (assoc campaign
                               :status terminal-status
                               :completed-at (str (:event/timestamp event)))
                  (:error event) (assoc :terminal-reason (:error event))

                  (= :blocked terminal-status)
                  (assoc :terminal-reason (:block-payload event))

                  (some? (:observed-quantum-duration-ms event))
                  (assoc :observed-quantum-duration-ms
                         (:observed-quantum-duration-ms event))

                  (or (some? (:max-observed-quantum-duration-ms campaign))
                      (some? (:max-observed-quantum-duration-ms event)))
                  (assoc :max-observed-quantum-duration-ms
                         (max (long (or (:max-observed-quantum-duration-ms campaign)
                                        0))
                              (long (or (:max-observed-quantum-duration-ms event)
                                        0))))))
      state)))

(defmethod researcher-campaigns* :sheet/tree-tick-completed
  [state event]
  (if (= :running (:root-status event))
    state
    (update state (:tick-id event)
            (fn [tick-state]
              (let [completed-at (str (:event/timestamp event))]
                (-> (merge {:sheet-id (:sheet-id event) :campaigns {}}
                           tick-state)
                    (assoc :execution-status :completed
                           :parent-completed-at completed-at)
                    (update :campaigns update-active-campaigns
                            #(-> %
                                 (assoc :status :abandoned)
                                 (dissoc :completed-at :verdict-at
                                         :terminal-reason)))))))))

(defmethod researcher-campaigns* :sheet/tick-cancelled
  [state event]
  (update state (:tick-id event)
          (fn [tick-state]
            (let [completed-at (str (:event/timestamp event))]
              (-> (merge {:sheet-id (:sheet-id event) :campaigns {}}
                         tick-state)
                  (assoc :execution-status :cancelled
                         :parent-completed-at completed-at)
                  (update :campaigns update-active-campaigns
                          #(cond-> (assoc %
                                         :status :cancelled
                                         :completed-at completed-at)
                             (:reason event)
                             (assoc :terminal-reason (:reason event)))))))))

(defmulti researcher-resume-states* (fn [_state event] (:event/type event)))

(defmethod researcher-resume-states* :default [state _event] state)

(defmethod researcher-resume-states* :rlm/researcher-resume-state-saved
  [state event]
  (let [entity-key [(:tick-id event) (:node-id event)]
        prior (get-in state [entity-key :resume-state])]
    (assoc state entity-key
           {:sheet-id (:sheet-id event)
            :resume-state (researcher-resume-state-hydrate
                           prior (:resume-state event))})))

(defmulti researcher-iteration-records* (fn [_state event] (:event/type event)))

(defmethod researcher-iteration-records* :default [state _event] state)

(defmethod researcher-iteration-records* :rlm/researcher-iteration-recorded
  [state event]
  (assoc state [(:tick-id event) (:node-id event)
                (:iteration-index event) (:attempt-ordinal event)]
         {:sheet-id (:sheet-id event)
          :iteration-record (:iteration-record event)}))

(defmulti researcher-actions* (fn [_state event] (:event/type event)))

(defmethod researcher-actions* :default [state _event] state)

(defmethod researcher-actions* :rlm/researcher-action-completed [state event]
  (assoc state [(:tick-id event) (:node-id event) (:action-id event)]
         (select-keys event [:sheet-id :tick-id :node-id :action-id :action-kind
                             :iteration :result :completed-at])))

(defmulti researcher-effect-claims* (fn [_state event] (:event/type event)))

(defmethod researcher-effect-claims* :default [state _event] state)

(defmethod researcher-effect-claims* :rlm/researcher-effect-claimed [state event]
  (assoc state [(:tick-id event) (:node-id event) (:attempt-identity event)]
         (select-keys event [:sheet-id :tick-id :node-id :iteration-index
                             :logical-action-identity :attempt-identity
                             :attempt-ordinal :ownership-epoch :kind :status
                             :claimed-at :resolved-at])))

(defmethod researcher-effect-claims* :rlm/researcher-effect-completed [state event]
  (update state [(:tick-id event) (:node-id event) (:attempt-identity event)]
          merge
          (select-keys event [:status :resolved-at :result])))

(defmethod researcher-effect-claims* :rlm/researcher-effect-indeterminate [state event]
  (update state [(:tick-id event) (:node-id event) (:attempt-identity event)]
          merge
          (select-keys event [:status :resolved-at
                              :resolved-by-ownership-epoch])))

(def provider-call-reservation-fields
  [:admission-kind :node-start-id :exec-context :candidate-index :node-attempt :campaign-sheet-id
   :budget-sheet-id :budget-tick-id :sheet-id :tick-id :node-id
   :campaign-tick-id :campaign-node-id :iteration-index
   :logical-action-identity :invocation-identity :provider-attempt-ordinal
   :ownership-epoch :reserved-at])

(defn provider-call-reservations*
  "Project immutable provider-call reservations by invocation identity."
  [state event]
  (if (= :sheet/provider-call-reserved (:event/type event))
    (assoc state [(:budget-tick-id event) (:invocation-identity event)]
           (select-keys event provider-call-reservation-fields))
    state))

;; --- Value log ---

(defn event-store-of [source]
  (if (and (map? source) (contains? source :event-store))
    (:event-store source)
    source))

(defn value-log-hydrate [source events]
  (if (and (map? source) (contains? source :event-store))
    (hydrate-events source events)
    (vec events)))

(def ^:private value-log-map-each-index-key
  :ai.obney.orc.orc-service.core.todo-processors/map-each-index)

(def ^:private value-log-map-each-parent-key
  :ai.obney.orc.orc-service.core.todo-processors/map-each-parent)

(def ^:private tick-iteration-key
  :ai.obney.orc.orc-service.core.todo-processors/tick-iteration)

(defn exec-context
  "The execution-disambiguating context from a map of inputs. Mirrors
   todo-processors/trace-execution-context; defined here to avoid a
   dependency cycle between commands, processors and this namespace."
  [inputs]
  (select-keys (or inputs {})
               [value-log-map-each-index-key value-log-map-each-parent-key tick-iteration-key]))

(defn engine-key?
  "True for a blackboard/inputs key the engine itself uses for bookkeeping
   (map-each iteration markers, tick iteration, durable order): every key in the
   namespace of the map-each markers. They are not data any node declared, so
   evidence shown to a judge never includes them."
  [k]
  (and (keyword? k) (= (namespace k) (namespace value-log-map-each-index-key))))

(defn execution-key
  "Correlation key for one node execution: [node-id exec-context].
   Works for :sheet/execution-value-written events (which carry
   :exec-context) and for node lifecycle events (which carry the same keys
   inside :inputs)."
  [event]
  [(:node-id event)
   (if (contains? event :exec-context)
     (exec-context (:exec-context event))
     (exec-context (:inputs event)))])

(defn input-seed?
  "True when a write PROVIDES a value to the execution it names rather than
   being that execution's output. map-each item writes are the case: the
   parent emits them before starting each child, and a child commonly reads
   and writes the same item key, so direction cannot be inferred from
   attribution alone."
  [event]
  (true? (:input-seed? event)))

(defn value-event?
  "True for a value-bearing write or a lightweight write reference."
  [event]
  (contains? #{:sheet/execution-value-written
               :sheet/execution-value-referenced}
             (:event/type event)))

(defn source-ref [event]
  {:tick-id (:tick-id event) :event-id (or (:event/id event) (:value-id event))})

(defn writes-by-execution
  "Index a tick's OUTPUT writes as {[node-id exec-context] {key value}}.

   Later writes win within one execution, matching blackboard semantics.
   Input seeds are excluded: they are inputs TO the named execution, not
   outputs OF it, so including them would let a map-each item masquerade as
   the child's own result for the same key."
  [events]
  (reduce (fn [acc e]
            (if (and (= :sheet/execution-value-written (:event/type e))
                     (not (input-seed? e)))
              (assoc-in acc [(execution-key e) (:key e)] (:value e))
              acc))
          {}
          events))

(defn input-seeds-by-iteration
  "Input seeds indexed by ITERATION: {exec-context {key value}}.

   Keyed on exec-context alone, NOT on [node-id exec-context]. The iteration
   identity is (map-each parent, index) — every node executing inside that
   iteration shares it, whatever its own node-id. A map-each child is often a
   composite, so the node that actually reads the item is a descendant of the
   child the write was stamped with; keying on node-id would miss it and fall
   back to the shared blackboard slot, which concurrent iterations clobber.

   This is how a map-each iteration's OWN item is recovered."
  [events]
  (reduce (fn [acc e]
            (if (and (= :sheet/execution-value-written (:event/type e))
                     (input-seed? e))
              (assoc-in acc [(exec-context (:exec-context e)) (:key e)] (:value e))
              acc))
          {}
          events))

(defn writes-for
  "The {key value} map a specific node execution wrote.

   `completion` is that node's :sheet/node-execution-completed event; its
   :node-id and :inputs supply the correlation key.

   Prefers the canonical write log, falling back to an inlined :writes map on
   the completion event when the log has nothing for this execution — which
   is the case for non-tick-scoped completions. Returns {} when neither has
   anything."
  [events completion]
  (let [from-log (get (writes-by-execution events) (execution-key completion))]
    (or (not-empty from-log)
        (:writes completion)
        {})))

(defn rejected-writes-for
  "The {key rejected-value} evidence recorded for one failed node execution.
   Rejected values are trace-only and are never included by writes-for or any
   canonical blackboard projection."
  [events completion]
  (reduce (fn [acc event]
            (if (and (= :sheet/execution-value-rejected (:event/type event))
                     (= (execution-key completion) (execution-key event)))
              (assoc acc (:key event) (:value event))
              acc))
          {}
          events))

(defn latest-values
  "The final {key value} map for a tick, ignoring attribution — last write
   per key wins. Use when you want the blackboard's end state rather than
   one node's contribution."
  [events]
  (reduce (fn [acc e]
            (if (= :sheet/execution-value-written (:event/type e))
              (assoc acc (:key e) (:value e))
              acc))
          {}
          events))

(defn writes-by-iteration
  "Non-seed writes indexed by ITERATION: {exec-context {key value}} — the
   output mirror of input-seeds-by-iteration.

   execute-composite-node forwards its :inputs (which carry ::map-each-index /
   ::map-each-parent) to each child, and complete-node-execution copies that
   namespaced subset onto both the completion event and every write event's
   :exec-context. So every node running inside one map-each iteration shares
   that iteration's exec-context whatever its own node-id or depth — which is
   what [node-id exec-context] attribution cannot express when the map-each
   child is a composite and the real writers are its descendants."
  [events]
  (reduce (fn [acc e]
            (if (and (= :sheet/execution-value-written (:event/type e))
                     (not (input-seed? e)))
              (let [ctx (exec-context (:exec-context e))]
                (if (seq ctx)
                  (assoc-in acc [ctx (:key e)] (:value e))
                  acc))
              acc))
          {}
          events))

;; =============================================================================
;; Resolving values behind a METADATA blackboard
;; =============================================================================

(defonce tick-seeds*
  (atom {}))

(defn forget-tick!
  "Drop a tick's memoized seed values. Call once the tick can no longer be
   read from — after its result has been delivered or its cancellation
   handled."
  [tick-id]
  (swap! tick-seeds* dissoc tick-id)
  nil)

(defn index-writes
  "{id event} over a tick's write events, id being :event/id or :value-id.
   An id maps to the FIRST event, in read order, that carries it as either, which is
   what scanning the events for the first match returns."
  [events]
  (reduce (fn [idx event]
            (let [idx (if-some [id (:event/id event)]
                        (if (contains? idx id) idx (assoc idx id event))
                        idx)]
              (if-some [id (:value-id event)]
                (if (contains? idx id) idx (assoc idx id event))
                idx)))
          {}
          events))

(defn new-write-index-cache
  "One per resolution call: the tick's write events are read (and hydrated) once
   per tick the call touches, not once per key."
  []
  (volatile! {}))

;; --- Rlm tree executor ---

;; =============================================================================
;; Ephemeral Function Registry
;; =============================================================================

;; =============================================================================
;; Per-Node Usage Aggregation
;; =============================================================================

(defn- input-lookup
  "Find a key in an inputs map by simple-name match, regardless of namespace.
   Returns the value or nil. Used because map-each context keys are namespaced
   keywords (::map-each-index, ::map-each-parent) and the namespace depends
   on where they're defined."
  [inputs simple-name]
  (let [k (some (fn [k]
                  (when (and (keyword? k) (= (name k) simple-name))
                    k))
                (keys (or inputs {})))]
    (when k (get inputs k))))

(defn compute-node-path
  "Compute a structured path identifying a node's position in the execution
   context. Returns a vector of segments:
     - For a leaf executed outside any map-each:
       [{:type :leaf :node-id <uuid>}]
     - For a leaf executed under a map-each (iteration N of parent M):
       [{:type :map-each :parent <uuid> :index N} {:type :leaf :node-id <uuid>}]

   Pure function — testable in isolation. Used as the key for per-node usage
   aggregation so that the same leaf node executed multiple times under
   different map-each iterations produces distinct entries.

   Args:
     node-id - the leaf node's id
     inputs  - the event :inputs map (may carry ::map-each-index / ::map-each-parent
               keys from the execution context, regardless of source namespace)"
  [node-id inputs]
  (let [map-each-index (input-lookup inputs "map-each-index")
        map-each-parent (input-lookup inputs "map-each-parent")
        leaf-segment {:type :leaf :node-id node-id}]
    (if (and (some? map-each-index) (some? map-each-parent))
      [{:type :map-each :parent map-each-parent :index map-each-index}
       leaf-segment]
      [leaf-segment])))

(defn aggregate-by-node
  "Given a seq of :sheet/node-execution-completed events, build a per-node
   usage map keyed by the structured node-path. Each value is a map of
   {:prompt-tokens N :completion-tokens N :total-tokens N} summed across
   all completions matching that path.

   When the same leaf node runs N times under a map-each, each iteration
   gets its own entry (path includes the iteration index). This is critical
   for benchmarks where map-each is the dominant token consumer.

   Pure function — testable in isolation."
  [completion-events]
  (reduce
    (fn [acc event]
      (let [node-id (:node-id event)
            inputs (:inputs event)
            usage (:usage event)]
        (if (and node-id usage)
          (let [path (compute-node-path node-id inputs)]
            (update acc path
                    (fn [existing]
                      (merge-with +
                                  (or existing {:prompt-tokens 0
                                                :completion-tokens 0
                                                :total-tokens 0})
                                  (select-keys usage
                                               [:prompt-tokens
                                                :completion-tokens
                                                :total-tokens])))))
          acc)))
    {}
    completion-events))

;; =============================================================================
;; Ephemeral Function Registry
;; =============================================================================

(defonce ephemeral-fn-registry (atom {}))

(defn register-ephemeral-fn!
  "Register an inline function for ephemeral execution. Returns a symbol string
   that can be used as the :fn value in a code executor command."
  [f]
  (let [fn-id (str "ephemeral-fn-" (random-uuid))]
    (swap! ephemeral-fn-registry assoc fn-id f)
    fn-id))

(defn lookup-ephemeral-fn
  "Look up an ephemeral function by its registry key."
  [fn-id]
  (get @ephemeral-fn-registry fn-id))

(defn restore-ephemeral-fn!
  "Restore this node's durable binding under its original registry identity."
  [fn-id binding]
  (let [f (compile-binding binding)]
    (get (swap! ephemeral-fn-registry
                (fn [registry]
                  (if-let [existing (get registry fn-id)]
                    (if (= binding (:orc/recoverable-fn (meta existing)))
                      registry
                      (throw (ex-info "Generated function identity has a different source binding"
                                      {:reason :recoverable-function-identity-collision
                                       :function-id fn-id})))
                    (assoc registry fn-id f)))) fn-id)))

(defn clear-ephemeral-fn!
  "Remove an ephemeral function from the registry."
  [fn-id]
  (swap! ephemeral-fn-registry dissoc fn-id))

(defn rlm-tree-executor-make-create-sheet-command
  "Create a create-sheet command with unique name."
  []
  {:command/name :sheet/create-sheet
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :name (str "RLM-Tree-" (random-uuid))})

(defn rlm-tree-executor-make-create-node-command
  "Create a create-node command."
  [sheet-id node-type & {:keys [parent-id index]}]
  (cond-> {:command/name :sheet/create-node
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :type node-type}
    parent-id (assoc :parent-id parent-id)
    index (assoc :index index)))

(defn rlm-tree-executor-make-declare-key-command
  "Create a declare-key command."
  [sheet-id key schema]
  {:command/name :sheet/declare-key
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :sheet-id sheet-id
   :key key
   :schema schema})

;; =============================================================================
;; Tree Analysis
;; =============================================================================

(defn extract-output-keys
  "Extract output keys from a generated tree by finding final! nodes.
   Trees are lists like: (sheet/sequence (sheet/llm ...) (final! {:keys [...]}))
   Returns a vector of output key keywords."
  [tree]
  (cond
    (and (seq? tree) (= 'final! (first tree)))
    (let [opts (second tree)]
      (vec (:keys opts)))

    (seq? tree)
    (vec (mapcat extract-output-keys (rest tree)))

    :else []))

(defn extract-all-keys
  "Extract all blackboard keys (reads and writes) from the tree.
   Used to pre-declare all keys before creating nodes."
  [tree]
  (cond
    (and (seq? tree) (= 'sheet/llm (first tree)))
    (let [args (rest tree)
          opts (apply hash-map args)]
      (concat (:reads opts) (:writes opts)))

    (and (seq? tree) (= 'sheet/code (first tree)))
    (let [opts (apply hash-map (rest tree))]
      (concat (:reads opts) (:writes opts)))

    (and (seq? tree) (= 'sheet/map-each (first tree)))
    (let [args (rest tree)
          child-node (last args)
          keyword-args (butlast args)
          opts (apply hash-map keyword-args)]
      (concat [(:from opts) (:as opts) (:into opts)]
              (extract-all-keys child-node)))

    (and (seq? tree) (= 'final! (first tree)))
    (let [opts (second tree)]
      (:keys opts))

    (seq? tree)
    (mapcat extract-all-keys (rest tree))

    :else []))

(defn merge-contracts
  "Resolve declarations without losing authored properties or silently picking a winner.
   Unequal schema forms must be omitted in favor of the inherited contract."
  [scope & declarations]
  (reduce (fn [resolved declaration]
            (reduce-kv
              (fn [result k schema]
                (when (and (contains? result k)
                           (not= (m/form (get result k)) (m/form schema)))
                  (throw (ex-info
                           (str "Conflicting generated contract for " k
                                "; omit the redeclaration and reuse the existing contract")
                           {:preflight-rejected? true :reason :conflicting-contract
                            :contract-scope scope :blackboard-key k})))
                (if (contains? result k) result (assoc result k schema)))
              resolved (or declaration {})))
          {} declarations))

(defn extract-key-schemas
  "U11: Walk the canonical tree and collect {write-key → Malli-schema} from
   leaf nodes that declared :output-schemas.

   When a write key's schema is structured (vector/map/etc.), the child
   sheet's declare-key uses it. build-module then passes it to llm,
   which detects complex-spec? → asks the LLM for JSON → parses the
   response back into Clojure data. Without this, the LLM's structured
   output arrives at downstream :code nodes as raw JSON text.

   Returns a map {key schema}. Conflicting declarations fail before execution.
   Returns {} when no :output-schemas declarations exist anywhere in the tree."
  [tree]
  (cond
    (and (seq? tree) (#{'sheet/llm 'sheet/code} (first tree)))
    (let [opts (apply hash-map (rest tree))]
      (or (:output-schemas opts) {}))

    (and (seq? tree)
         (#{'sheet/sequence 'sheet/parallel} (first tree)))
    (apply merge-contracts :generated (map extract-key-schemas (rest tree)))

    (and (seq? tree) (= 'sheet/map-each (first tree)))
    (let [args (rest tree)
          child-node (last args)
          opts (apply hash-map (butlast args))]
      (merge-contracts :generated (:output-schemas opts)
                     (extract-key-schemas child-node)))

    :else {}))

;; =============================================================================
;; Tree Compilation
;; =============================================================================

(defn parse-keyword-args
  "Parse keyword arguments from a flat list into a map.
   (parse-keyword-args [:instruction \"foo\" :reads [:a] :writes [:b]])
   => {:instruction \"foo\" :reads [:a] :writes [:b]}"
  [args]
  (apply hash-map args))

;; --- Emitted model authority ---

(def event-type :sheet/emitted-model-work-authorized)

(def emitted-model-authority-fact-types
  #{:sheet/tree-tick-started :sheet/tree-tick-completed :sheet/tick-cancelled
    :sheet/node-execution-started :sheet/node-execution-completed
    :rlm/researcher-frontier-claimed :rlm/researcher-effect-claimed :rlm/researcher-effect-completed
    :sheet/model-plan-retained :sheet/model-candidate-claimed :sheet/model-candidate-recorded
    :sheet/model-request-recorded :sheet/emitted-model-work-authorized})

(def emitted-model-authority-owner-keys [:sheet-id :tick-id :node-id :node-start-id :exec-context :configuration-id :logical-action-id])

(def binding-fields emitted-model-binding-fields)

(def binding-keys (mapv first binding-fields))

(defn- same-node? [owner event]
  (and (= (:sheet-id owner) (:sheet-id event)) (= (:tick-id owner) (:tick-id event))
       (= (:node-id owner) (:node-id event))
       (= (or (:exec-context owner) {}) (exec-context (:inputs event)))))

(defn- latest-start [owner xs]
  (last (filter #(and (= :sheet/node-execution-started (:event/type %)) (same-node? owner %)) xs)))

(defn- emitted-model-authority-ended? [owner xs]
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
      (and node (not (seq (:fallback-models node))) (empty? operation)))))

(defn- assess-decision
  "Pure canonical decision. A binding is single-assignment per actual child start,
   independent of configuration buckets. Callers must check it again in append CAS."
  [owner campaign xs retaining-plan?]
  (let [parent (select-keys campaign [:sheet-id :tick-id :node-id :exec-context])
        child-start (latest-start owner xs) parent-start (latest-start parent xs)
        child-tree (first (filter #(and (= :sheet/tree-tick-started (:event/type %)) (= (:tick-id owner) (:tick-id %))) xs))
        frontiers (filter #(and (= frontier-event-type (:event/type %))
                                 (= (:sheet-id parent) (:sheet-id %)) (= (:tick-id parent) (:tick-id %))
                                 (= (:node-id parent) (:node-id %))) xs)
        frontier (last frontiers)
        epoch (:ownership-epoch frontier)
        claim (last (filter #(and (= claim-event-type (:event/type %)) (= :generated-child (:kind %))
                                  (= (:sheet-id parent) (:sheet-id %)) (= (:tick-id parent) (:tick-id %))
                                  (= (:node-id parent) (:node-id %))
                                  (= (:iteration-index campaign) (:iteration-index %))
                                  (= (:tick-id owner) (java.util.UUID/nameUUIDFromBytes
                                                        (.getBytes (:logical-action-identity %) "UTF-8")))) xs))
        binding (first (filter #(and (= event-type (:event/type %)) (= (:node-start-id owner) (:node-start-id %))) xs))
        positions (zipmap (map :event/id xs) (range))
        parent-frontier (first (filter #(< (get positions (:event/id parent-start) Long/MAX_VALUE)
                                             (get positions (:event/id %) -1)) frontiers))
        declared-parent-epoch (:researcher-ownership-epoch parent-start)
        current-parent-frontier?
        (and parent-frontier (= (:event/id parent-frontier) (:event/id frontier))
             (or (not (contains? parent-start :researcher-ownership-epoch))
                 (and (pos-int? declared-parent-epoch) (= epoch declared-parent-epoch))))
        parent-frontier-superseded?
        (or (and parent-frontier (not= (:event/id parent-frontier) (:event/id frontier)))
            (and (pos-int? declared-parent-epoch) (pos-int? epoch) (> epoch declared-parent-epoch))
            (and parent-frontier (contains? parent-start :researcher-ownership-epoch)
                 (not= epoch declared-parent-epoch)))
        claim-completed? (some #(and (= completion-event-type (:event/type %))
                                     (= (:logical-action-identity claim) (:logical-action-identity %))
                                     (= (:tick-id parent) (:tick-id %))) xs)
        ordered? (and parent-start frontier claim
                      (< (get positions (:event/id parent-start) Long/MAX_VALUE)
                         (get positions (:event/id frontier) -1)
                         (get positions (:event/id claim) -1)))
        current? (and (= (:node-start-id owner) (:event/id child-start))
                      (= (:sheet-id owner) (:sheet-id child-tree))
                      (= (:tick-id parent) (:parent-tick-id child-tree))
                      (not (emitted-model-authority-ended? owner xs)) (not (emitted-model-authority-ended? parent xs)))
        claim-current? (and (pos-int? epoch) (= epoch (:ownership-epoch claim))
                            (or (not (contains? parent-start :researcher-ownership-epoch))
                                (= epoch (:researcher-ownership-epoch parent-start))) ordered?
                            (not claim-completed?))
        first-after-parent?
        (and (pos-int? (:ownership-epoch campaign))
             (nil? (:resumed-from-event-id child-start))
             (= (:event/id child-start)
                (:event/id (first (filter #(and (= :sheet/node-execution-started (:event/type %))
                                                (same-node? owner %)) xs))))
             (< (get positions (:event/id parent-start) Long/MAX_VALUE)
                (get positions (:event/id child-start) -1))
             (or (and (pos-int? declared-parent-epoch)
                      (> declared-parent-epoch (:ownership-epoch campaign)))
                 (and current-parent-frontier? (pos-int? epoch)
                      (> epoch (:ownership-epoch campaign)))))
        authority (merge (select-keys owner emitted-model-authority-owner-keys)
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
      (and (nil? (:resumed-from-event-id child-start))
           (= (:ownership-epoch campaign) epoch) claim-current?)
      {:status :original :authority authority}
      (not (or (recovered-start? owner child-start xs) first-after-parent?))
      {:status :refused :reason :emitted-resumed-start-required}
      (and retaining-plan? (not first-after-parent?))
      {:status :refused :reason :emitted-resumed-start-required}
      (and first-after-parent? (or parent-frontier-superseded? claim-completed?))
      {:status :refused :reason :emitted-authority-superseded}
      (and first-after-parent?
           (or (not current-parent-frontier?) (not claim-current?)
               (not (> (or epoch 0) (:ownership-epoch campaign)))))
      {:status :pending :reason :emitted-parent-reclaim-pending}
      (and (not retaining-plan?) (not (configuration-admitted? owner campaign xs)))
      {:status :refused :reason :emitted-configuration-mismatch}
      (or (not claim-current?) (not (> (or epoch 0) (or (:ownership-epoch campaign) 0))))
      {:status :pending :reason :emitted-parent-reclaim-pending}
      :else {:status :ready :authority authority})))

(defn assess
  "Canonical invocation authority, including exact retained configuration."
  [owner campaign xs]
  (assess-decision owner campaign xs false))

(defn new-plan-retention-eligible?
  "Retention-only eligibility for a first node after a proven newer parent start.
   The frontier/claim may still be pending. Callers must validate and CAS the
   proposed plan; this never authorizes a request or provider reservation."
  [owner campaign xs]
  (contains? #{:ready :pending} (:status (assess-decision owner campaign xs true))))

(defn fresh-authorized? [owner campaign xs]
  (contains? #{:original :authorized} (:status (assess owner campaign xs))))

(defn emitted-model-authority-merge-facts [& groups]
  (->> groups (apply concat) (reduce #(assoc %1 (:event/id %2) %2) {}) vals
       (sort-by (comp str :event/id)) vec))

;; --- Execution family ---

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
                 (if-let [entry (resolve-ref value-source)]
                   (assoc acc k (:value entry))
                   acc))
               {} (or (:seed-sources started) {}))))

(defn tick-index
  "Everything entry resolution needs from ONE tick's events, computed in a
   single pass over events that were read once. Semantics mirror
   value-log/resolve-reads, resolve-writes and rejected-writes-for."
  [resolve-ref tick-events]
  (let [value-events (filterv value-event? tick-events)
        output-sources (reduce (fn [acc e]
                                 (if (input-seed? e)
                                   acc
                                   (assoc-in acc [(execution-key e) (:key e)]
                                             (or (:source e) (source-ref e)))))
                               {} value-events)
        rejected (reduce (fn [acc e]
                           (if (= :sheet/execution-value-rejected (:event/type e))
                             (assoc-in acc [(execution-key e) (:key e)] (:value e))
                             acc))
                         {} tick-events)
        seeds (delay (seed-values resolve-ref tick-events))]
    {:output-sources output-sources
     :logged-writes (writes-by-execution tick-events)
     :rejected rejected
     :iteration-seeds (input-seeds-by-iteration tick-events)
     :tick-seeds seeds}))

(defn- resolved-reads [resolve-ref index completion]
  (let [sources (or (:read-sources completion) {})
        iteration-seeds (get (:iteration-seeds index)
                             (exec-context (:inputs completion)))]
    (reduce (fn [acc k]
              (let [source (get sources k)
                    entry (cond
                            source (resolve-ref source)
                            (contains? iteration-seeds k) {:value (get iteration-seeds k)}
                            (contains? @(:tick-seeds index) k) {:value (get @(:tick-seeds index) k)})]
                (if entry (assoc acc k (:value entry)) acc)))
            {}
            (or (:read-keys completion) []))))

(defn- resolved-writes [resolve-ref index completion]
  (let [ek (execution-key completion)
        sources (merge (or (:write-sources completion) {})
                       (get (:output-sources index) ek))]
    (if (seq sources)
      (reduce-kv (fn [acc k value-source]
                   (if-let [entry (resolve-ref value-source)]
                     (assoc acc k (:value entry))
                     acc))
                 {} sources)
      (or (not-empty (get (:logged-writes index) ek))
          (:writes completion)
          {}))))

(defn entry-for
  [resolve-ref index tick-events {:keys [tick-id sheet-id parent-tick-id nodes-by-id attributed-to]} completed]
  (let [node (get nodes-by-id (:node-id completed))
        inputs (resolved-reads resolve-ref index completed)
        outputs (resolved-writes resolve-ref index completed)
        rejected (get (:rejected index) (execution-key completed))
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
             :exec-context (exec-context (:inputs completed))
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

(defn resolver
  "resolve-ref over {:tick-id :event-id} references, following cross-tick
   references as found-value envelopes, including explicit nil. Missing,
   foreign and cyclic references resolve to nil; each tick is read once."
  [read-tick]
  (fn resolve-ref
    ([source] (resolve-ref source #{}))
    ([{:keys [tick-id event-id] :as source} seen]
     (when (and tick-id event-id (not (contains? seen source)))
       (when-let [event (get (:by-ref (read-tick tick-id)) event-id)]
         (if (= :sheet/execution-value-referenced (:event/type event))
           (resolve-ref (:source event) (conj seen source))
           (when (contains? event :value) (select-keys event [:value]))))))))

(defn- descendants
  "node-id's descendants in a node map."
  [nodes-by-id node-id]
  (loop [acc #{} frontier [node-id]]
    (if (empty? frontier)
      acc
      (let [kids (into [] (mapcat #(:children-ids (get nodes-by-id %))) frontier)]
        (recur (into acc kids) kids)))))

(defn scope-to-node
  "Keep only the entries beneath the executions of `node-id`: the node's
   descendants in the tick it ran in, plus every execution of any tick spawned
   (transitively) from those nodes."
  [entries ticks node-id]
  (let [ticks-by-id (into {} (map (juxt :tick-id identity)) ticks)
        home-ticks (into #{} (comp (filter #(= node-id (:node-id %))) (map :tick-id)) entries)
        beneath-nodes (into {}
                            (map (fn [t] [t (descendants (:nodes-by-id (get ticks-by-id t)) node-id)]))
                            home-ticks)
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

;; --- Rlm drill down ---

(defn- event-of-type [events event-type]
  (first (filter #(= event-type (:event/type %)) events)))

(defn- events-of-type [events event-type]
  (filter #(= event-type (:event/type %)) events))

(defn- tick-outputs-from-events
  "Reconstruct a tick's final output values from its own events.

   :sheet/tree-tick-completed names the keys the tick wrote (:output-keys)
   but does not carry their values — they are already durable in the tick's
   :sheet/execution-value-written events, which are the canonical record of
   every write. Replaying those in append order (last write per key wins)
   reproduces exactly what the tick-completed event used to inline.

   Stays a pure function of the event vector, so drill-down needs no
   read-model or event-store access."
  [events tick-completed]
  (let [written (reduce (fn [acc e] (assoc acc (:key e) (:value e)))
                        {}
                        (events-of-type events :sheet/execution-value-written))]
    (if-let [ks (seq (:output-keys tick-completed))]
      (into {} (for [k ks :when (contains? written k)] [k (get written k)]))
      written)))

(defn tree-detail-from-events
  "Build a structured projection of a tree's full execution detail from
   the events tagged with its tick-id.

   Inputs:
     events  — vec of event maps for one tick
     tree-raw — the raw S-expr tree the model wrote (from :generated-tree-raw)

   Returns:
     {:tick-id <uuid>
      :status :success | :partial | :failure | :timeout
      :tree-raw [...]
      :outputs {<writes-key> value ...}
      :nodes [{:node-id :status :duration-ms :writes :usage :input-profile} ...]}

   When the events don't include a :sheet/tree-tick-completed bookend
   (e.g. an in-flight or otherwise truncated tick), returns nil."
  [events tree-raw]
  (when-let [tick-completed (event-of-type events :sheet/tree-tick-completed)]
    (let [tick-id (:tick-id tick-completed)
          node-completions (events-of-type events :sheet/node-execution-completed)
          rlm-by-node (reduce (fn [acc e] (assoc acc (:node-id e) e))
                              {}
                              (events-of-type events :sheet/rlm-tree-node-completed))
          nodes (mapv (fn [c]
                        (let [n-id (:node-id c)
                              rlm (get rlm-by-node n-id)]
                          (cond-> {:node-id n-id
                                   :status (:status c)
                                   :duration-ms (:duration-ms c)
                                   :writes (writes-for events c)}
                            (:usage c) (assoc :usage (:usage c))
                            (:input-profile rlm) (assoc :input-profile (:input-profile rlm))
                            (:partial-summary c) (assoc :partial-summary (:partial-summary c)))))
                      node-completions)]
      {:tick-id tick-id
       :status (:root-status tick-completed)
       :tree-raw tree-raw
       :outputs (tick-outputs-from-events events tick-completed)
       :nodes nodes})))

(defn tree-failures-from-events
  "Return the failure entries for a tree's tick events.

   Two sources of failures are joined:

   1. Direct leaf failures — :sheet/node-execution-completed events with
      :status :failure. Each entry includes the :error string and (if
      available) the :input-profile from the matching
      :sheet/rlm-tree-node-completed event.

   2. Map-each :partial-summary failures — the :failure-indices /
      :failure-reasons / :failure-input-profiles fields from D-008's
      :partial-summary block. Each index becomes its own failure entry.

   Returns:
     [{:node-id :status :error :input-profile?} ...]    ; direct leaf failures
     [{:index :error :input-profile?} ...]              ; map-each per-index failures
     [] when nothing failed."
  [events]
  (let [node-completions (events-of-type events :sheet/node-execution-completed)
        rlm-by-node (reduce (fn [acc e] (assoc acc (:node-id e) e))
                            {}
                            (events-of-type events :sheet/rlm-tree-node-completed))
        leaf-failures (for [c node-completions
                            :when (and (= :failure (:status c))
                                       (not (:partial-summary c)))]
                        (let [rlm (get rlm-by-node (:node-id c))]
                          (cond-> {:node-id (:node-id c)
                                   :status :failure}
                            (:error c) (assoc :error (:error c))
                            (:input-profile rlm) (assoc :input-profile (:input-profile rlm)))))
        partial-summary-failures
        (mapcat (fn [c]
                  (when-let [ps (:partial-summary c)]
                    (for [idx (:failure-indices ps)]
                      (cond-> {:index idx}
                        (get-in ps [:failure-reasons idx]) (assoc :error (get-in ps [:failure-reasons idx]))
                        (get-in ps [:failure-input-profiles idx])
                        (assoc :input-profile (get-in ps [:failure-input-profiles idx]))))))
                node-completions)]
    (vec (concat leaf-failures partial-summary-failures))))

(defn tree-trajectory-from-events
  "Return the chronological per-event log for a tree's tick.

   The bookend :sheet/rlm-tree-execution-completed event captures the full
   trajectory at completion time (see the O03 work). We just pass that
   vector through. Returns nil when no bookend event is present."
  [events]
  (when-let [bookend (event-of-type events :sheet/rlm-tree-execution-completed)]
    (vec (:trajectory bookend))))

(defn node-output-from-events
  "Return the :writes map of the specified node's :sheet/node-execution-completed
   event. Returns nil when no matching event exists.

   For parse-failure completions the event carries :raw-response (the
   verbatim LLM text that could not be parsed into declared writes). In
   that case the return shape is {:writes <map> :raw-response <string>
   :error <string>} — the raw text IS the node's output as far as
   diagnosis is concerned, untruncated."
  [events node-id]
  (let []
    (some (fn [e]
            (when (and (= :sheet/node-execution-completed (:event/type e))
                       (= node-id (:node-id e)))
              (let [writes (writes-for events e)]
                (if (:raw-response e)
                  (cond-> {:writes writes
                           :raw-response (:raw-response e)}
                    (:error e) (assoc :error (:error e)))
                  (not-empty writes)))))
          events)))

(defn node-input-profile-from-events
  "Return the :input-profile of the specified node's :sheet/rlm-tree-node-completed
   event. Returns nil when no matching event exists (or the event has no
   :input-profile)."
  [events node-id]
  (some (fn [e]
          (when (and (= :sheet/rlm-tree-node-completed (:event/type e))
                     (= node-id (:node-id e)))
            (:input-profile e)))
        events))

;; --- Runtime ---

(defn timeout-node-path [nodes-by-id node-id]
  (loop [current node-id path () seen #{}]
    (if (or (nil? current) (contains? seen current))
      (vec path)
      (let [node (get nodes-by-id current)]
        (recur (:parent-id node) (conj path (:name node)) (conj seen current))))))

;; =============================================================================
;; Snapshot Parsing for Published Version Execution
;; =============================================================================

(defn- parse-snapshot-nodes
  "Parse a nested snapshot tree into a flat nodes-by-id map.
   Generates deterministic UUIDs based on tree position for consistent execution."
  [snapshot-node parent-id index path]
  (when snapshot-node
    (let [
          node-id (java.util.UUID/nameUUIDFromBytes
                   (.getBytes (str path) "UTF-8"))
          children (or (:children snapshot-node) [])
          node-record (merge
                       (select-keys snapshot-node [:provider :fallback-models :fallback-policy
                                                   :model-fallback-policy :rlm :browser-tools
                                                   :tool-contracts :tool-caller-fn :judges])
                       {:id node-id
                       :type (:type snapshot-node)
                       :name (:name snapshot-node)
                       :source-node-id (:source-node-id snapshot-node)
                       :parent-id parent-id
                       :children-ids (mapv (fn [i _]
                                             (java.util.UUID/nameUUIDFromBytes
                                              (.getBytes (str path "/" i) "UTF-8")))
                                           (range (count children))
                                           children)
                       :status :idle
                       :instruction (:instruction snapshot-node)
                       :reads (or (:reads snapshot-node) [])
                       :writes (or (:writes snapshot-node) [])
                       :decorators []
                       :executor (:executor snapshot-node)
                       :model (:model snapshot-node)
                       :fn (:fn snapshot-node)
                       :tools (:tools snapshot-node)
                       :options (:options snapshot-node)
                       :retry (:retry snapshot-node)
                       :options-from (:options-from snapshot-node)
                       :bands-from (:bands-from snapshot-node)
                       :min-confidence (:min-confidence snapshot-node)
                       :abstain (:abstain snapshot-node)
                       :check (:check snapshot-node)
                       :success-policy (:success-policy snapshot-node)
                       :failure-policy (:failure-policy snapshot-node)
                       :source-key (:source-key snapshot-node)
                       :item-key (:item-key snapshot-node)
                       :output-key (:output-key snapshot-node)
                       :max-concurrency (:max-concurrency snapshot-node)
                       :preserve-failures? (:preserve-failures? snapshot-node)
                       :mcp-tools (or (:mcp-tools snapshot-node) [])
                       :max-iterations (:max-iterations snapshot-node)
                       :target-sheet-id (:target-sheet-id snapshot-node)
                       :delegate-timeout-ms (or (:delegate-timeout-ms snapshot-node)
                                                (:timeout-ms snapshot-node))
                       :delegate-max-ticks (or (:delegate-max-ticks snapshot-node)
                                               (:max-ticks snapshot-node))
                       :inherit-ontology? (if (contains? snapshot-node :inherit-ontology?)
                                            (:inherit-ontology? snapshot-node)
                                            true)
                       :context (:context snapshot-node)})
          child-records (mapcat (fn [i child]
                                  (parse-snapshot-nodes child node-id i (str path "/" i)))
                                (range)
                                children)]
      (cons [node-id node-record] child-records))))

(defn- recover-source-node-ids
  "Link the run nodes of a snapshot published before draft ids were recorded to
   their draft nodes, where that is unambiguous: a run node takes the id of the
   live draft node of the same name only when exactly one node of that name
   exists in the snapshot AND exactly one in the live sheet. Nodes that cannot be
   linked are left without a source id - they are not judged - and the run says
   so loudly; a guess between same-named nodes would attribute assessments to
   the wrong node."
  [nodes-by-id live-nodes sheet-id version-number]
  (let [by-name (fn [nodes] (group-by :name (remove #(nil? (:name %)) nodes)))
        run-by-name (by-name (vals nodes-by-id))
        live-by-name (by-name (vals live-nodes))
        recovered (reduce-kv
                   (fn [acc id node]
                     (if (:source-node-id node)
                       acc
                       (let [run-same (get run-by-name (:name node))
                             live-same (get live-by-name (:name node))]
                         (if (and (= 1 (count run-same)) (= 1 (count live-same)))
                           (assoc acc id (assoc node :source-node-id (:id (first live-same))))
                           acc))))
                   nodes-by-id
                   nodes-by-id)
        unlinked (into [] (comp (remove :source-node-id) (map #(or (:name %) (:id %))))
                       (vals recovered))]
    (when (seq unlinked)
      (u/log :ai.obney.orc.orc-service.core.runtime/published-nodes-cannot-be-judged
             :sheet-id sheet-id
             :version-number version-number
             :node-names unlinked
             :reason "snapshot predates source node ids and the node name does not identify exactly one draft node"))
    recovered))

(defn- carry-source-judges
  "Stamp each run node of a published version with the judges attached to the
   draft node it was published from (`:source-node-id`), as they stand when the
   run starts. Attachments live on the draft node, never in the snapshot, so a
   judge attached after publishing still monitors the published version; the
   run needs them to know which composites are durable boundaries. `:judges`
   is not part of a node's definition (node-version), so this forks no
   node version."
  [nodes-by-id live-nodes]
  (reduce-kv (fn [acc id node]
               (if-let [judges (some->> (:source-node-id node) (get live-nodes) :judges seq)]
                 (assoc acc id (assoc node :judges (vec judges)))
                 acc))
             nodes-by-id
             nodes-by-id))

(defn parse-snapshot-for-execution
  "Parse a version snapshot into the format expected by execute.
   Returns {:nodes-by-id {...} :root-id uuid :blackboard {...}}"
  ([snapshot] (parse-snapshot-for-execution snapshot nil nil nil))
  ([snapshot live-nodes sheet-id version-number]
  (let [snapshot-nodes (:nodes snapshot)
        blackboard-schema (:blackboard-schema snapshot)
        node-pairs (parse-snapshot-nodes snapshot-nodes nil 0 "root")
        nodes-by-id (cond-> (into {} node-pairs)
                      live-nodes (-> (recover-source-node-ids live-nodes sheet-id version-number)
                                     (carry-source-judges live-nodes)))
        root-id (when (seq node-pairs) (first (first node-pairs)))
        blackboard (reduce (fn [bb [k schema]]
                            (assoc bb k {:key k
                                         :schema schema
                                         :value nil
                                         :version 0}))
                          {}
                          blackboard-schema)]
    {:nodes-by-id nodes-by-id
     :root-id root-id
     :blackboard blackboard})))

(defonce completion-registry (atom {}))

(defonce ephemeral-context-registry (atom {}))

(defn ephemeral-context-for [tick-id]
  (get @ephemeral-context-registry tick-id))

(defn forget-ephemeral-context! [tick-id]
  (swap! ephemeral-context-registry dissoc tick-id))

(def default-result-grace-ms
  "How much silence beyond the run budget the engine is granted before a run
   is declared wedged, when the caller does not pass :result-grace-ms.

   DERIVED, not invented: this is the engine's own result-delivery slack —
   execute-stream keeps a run's stream subscription alive for
   (+ timeout-ms default-result-grace-ms) (\"stream at least as long as the
   execution\"), i.e. the engine itself declares that a run's machinery may
   lag its budget by at most this much before the stream is reaped. The
   consumer-side W35 bound uses the same relation:
   (:timeout-ms execution | 300000) + (:result-grace-ms | 60000). An engine
   silent for longer than its own reaping slack is not slow — it is gone."
  60000)

(defn register-completion!
  "Register a promise for a tick-id. Returns the promise."
  [tick-id]
  (let [p (promise)]
    (swap! completion-registry assoc tick-id p)
    p))

(defn deliver-completion!
  "Deliver a result to any waiting promise for a tick-id."
  [tick-id result]
  (when-let [p (get @completion-registry tick-id)]
    (deliver p result)
    (swap! completion-registry dissoc tick-id)))

(defn deregister-completion!
  "Remove a tick-id's promise without delivering. Use when the upstream
   command dispatch was rejected (anomaly) and no events will ever
   resolve the promise — otherwise the caller hangs on (deref p timeout)
   for the full budget."
  [tick-id]
  (swap! completion-registry dissoc tick-id))

(defn terminal-root-status->result-status
  "Map a durable tree root status to the public result status shared by live
   and replay reconstruction. A :running completion is an intermediate retick
   bookend, so it has no terminal result."
  [root-status]
  (case root-status
    :running nil
    :success :success
    :failure :failure
    :tree-generated :tree-generated
    :partial :partial
    :timeout :timeout
    :blocked :blocked
    :cancelled :cancelled
    :failure))

(defn tick-open-starts
  "Unfinished starts of one tick and the lease renewals naming each start, from
   the tick's durable events."
  [tick-events]
  (let [
        open-starts-by-key
        (reduce (fn [starts event]
                  (case (:event/type event)
                    :sheet/node-execution-started
                    (assoc starts (execution-key event) event)

                    :sheet/node-execution-completed
                    (dissoc starts (execution-key event))

                    starts))
                {}
                tick-events)
        completed-map-contexts
        (into #{}
              (keep (fn [event]
                      (let [exec-context (ai.obney.orc.orc-service.core.logic/exec-context (:inputs event))]
                        (when (and (= :sheet/node-execution-completed
                                      (:event/type event))
                                   (contains? exec-context
                                              :ai.obney.orc.orc-service.core.todo-processors/map-each-parent))
                          (execution-key event)))))
              tick-events)]
    {:open-starts (vals (apply dissoc open-starts-by-key completed-map-contexts))
     :renewals-by-start
     (group-by :start-event-id
               (filter #(= :sheet/node-execution-lease-renewed (:event/type %))
                       tick-events))}))

(defn recoverable-start?
  "OwnedWorkIsNotAbandoned / AbandonedWorkStaysRecoverable: a leaf or delegate
   start (original or resumed) is resumed only when its owner is known not to
   be working on it. Researchers use the same lease decision for WHETHER to
   resume (checkpointed or not) and keep their epoch-based path for WHAT is
   resumed: only original starts are candidates."
  [context nodes-by-id renewals-by-start start]
  (let [node (get nodes-by-id (:node-id start))
        renewals (get renewals-by-start (:event/id start))
        exec-context (ai.obney.orc.orc-service.core.logic/exec-context (:inputs start))]
    (case (:type node)
      (:leaf :delegate)
      (abandoned? context start renewals exec-context)
      :repl-researcher (and (nil? (:resumed-from-event-id start))
                            (abandoned?
                             context start renewals exec-context
                             {:fenced? (boolean
                                        (checkpointed? node))}))
      false)))

(defn unresumed-map-coordinator?
  "True when an in-progress map-each node has an original start that no resumed
   start references yet: `resume-in-progress!` rebuilds that coordinator (once;
   the resume command refuses a second resume of the same start)."
  [nodes-by-id nodes-in-progress tick-events]
  (let [resumed-ids (into #{} (keep :resumed-from-event-id) tick-events)]
    (boolean
     (some (fn [node-id]
             (and (= :map-each (:type (get nodes-by-id node-id)))
                  (some #(and (= :sheet/node-execution-started (:event/type %))
                              (= node-id (:node-id %))
                              (nil? (:resumed-from-event-id %))
                              (not (contains? resumed-ids (:event/id %))))
                        tick-events)))
           nodes-in-progress))))

;; --- Model call journal ---

(def model-call-journal-retained-option-keys schemas-retained-option-keys)

(def model-call-journal-valid-retained-options? schemas-valid-retained-options?)

(def model-call-journal-retained-plan-schema schemas-retained-plan-schema)

(defn retained-configuration-id [plan]
  (str "effective-v1:"
       (provider-logical-action-identity {:module plan :inputs nil :options {}})))

(def owner-fields model-call-owner-fields)

(def model-call-journal-owner-keys (mapv first owner-fields))

(def model-call-journal-event-types #{:sheet/model-plan-retained :sheet/model-candidate-claimed :sheet/model-candidate-recorded :sheet/model-candidate-selected :sheet/model-candidate-output-exposed :sheet/model-request-recorded})

(defn model-call-journal-authorized? [ctx] (and (:tenant-id ctx) (:auth-claims ctx)))

(defn model-call-journal-rejected [message] {:cognitect.anomalies/category :cognitect.anomalies/conflict :cognitect.anomalies/message message})

(defn call-tag [owner] [:model-call (java.util.UUID/nameUUIDFromBytes
                                    (.getBytes (pr-str [(:tick-id owner) (:node-id owner)
                                                       (:exec-context owner) (:logical-action-id owner)
                                                       (:configuration-id owner)]) "UTF-8"))])

(defn scope-tag [s] [:model-selection (java.util.UUID/nameUUIDFromBytes (.getBytes (pr-str s) "UTF-8"))])

(defn prime-frontier? [owner event]
  (and (#{:prime :subcall} (:role owner)) (= frontier-event-type (:event/type event))
       (= (:sheet-id owner) (:sheet-id event)) (= (:tick-id owner) (:tick-id event))
       (= (:node-id owner) (:node-id event))))

(defn owns-facts? [owner xs]
  (let [latest (last (filter #(and (= :sheet/node-execution-started (:event/type %))
                                   (= (:tick-id owner) (:tick-id %)) (= (:node-id owner) (:node-id %))
                                   (= (:exec-context owner) (exec-context (:inputs %)))) xs))
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

(defn same-operation? [owner event]
  (= (select-keys owner operation-fields) (select-keys event operation-fields)))

(defn inline-role-plans [owner xs]
  (filterv #(and (= :sheet/model-plan-retained (:event/type %))
                 (= (select-keys owner [:sheet-id :tick-id :node-id :exec-context :role])
                    (select-keys % [:sheet-id :tick-id :node-id :exec-context :role]))) xs))

(defn inline-configuration-admitted? [owner xs fresh-request? candidate-index]
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

(def admission-event-types (conj model-call-journal-event-types :sheet/node-execution-started frontier-event-type))

(defn plan-facts [xs owner scope-id]
  (filterv #(or (and (contains? model-call-journal-event-types (:event/type %))
                     (or (same-operation? owner %)
                         (and (= :sheet/model-plan-retained (:event/type %))
                              (some? scope-id) (= scope-id (:plan-scope-id %)))
                         (and (#{:prime :subcall} (:role owner))
                              (= (select-keys owner [:sheet-id :tick-id :node-id :exec-context :role])
                                 (select-keys % [:sheet-id :tick-id :node-id :exec-context :role])))))
                (prime-frontier? owner %)
                (and (= :sheet/node-execution-started (:event/type %))
                     (= (:node-id owner) (:node-id %))
                     (= (:exec-context owner) (exec-context (:inputs %))))) xs))

(defn model-call-journal-merge-facts [& groups]
  (->> groups (apply concat) (reduce #(assoc %1 (:event/id %2) %2) {}) vals
       (sort-by (comp str :event/id)) vec))

(defn specialist-role-event? [{:keys [role-owner]} event]
  (and (= :subcall (:role event))
       (if (:selection-scope event)
         (= (mapv role-owner [:sheet-id :tick-id :node-id :role :exec-context])
            (vec (take 5 (:selection-scope event))))
         (= (select-keys role-owner [:sheet-id :tick-id :node-id :exec-context :role])
            (select-keys event [:sheet-id :tick-id :node-id :exec-context :role])))))

(defn specialist-plans [topology xs]
  (filterv #(and (= :sheet/model-plan-retained (:event/type %))
                 (specialist-role-event? topology %)) xs))

(defn- model-call-journal-ended? [owner xs]
  (let [lifecycle (last (filter #(and (= (:tick-id owner) (:tick-id %))
                                     (= (:node-id owner) (:node-id %))
                                     (= (:exec-context owner) (exec-context (:inputs %)))
                                     (#{:sheet/node-execution-started :sheet/node-execution-completed} (:event/type %))) xs))]
    (or (= :sheet/node-execution-completed (:event/type lifecycle))
        (some #(and (= (:tick-id owner) (:tick-id %))
                    (#{:sheet/tick-cancelled :sheet/tree-tick-completed} (:event/type %))) xs))))

(defn specialist-owned? [{:keys [emitted?]} owner xs]
  (and (not (model-call-journal-ended? owner xs))
       (if emitted?
         (owns-facts? (assoc owner :role :node) xs)
         (owns-facts? owner xs))))

(defn fresh-specialist-work? [{:keys [emitted? campaign]} owner xs]
  (or (not emitted?) (nil? (:ownership-epoch campaign))
      (fresh-authorized? owner campaign xs)))

(defn specialist-configuration-admitted? [topology owner xs fresh? index]
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

(def specialist-event-types
  (into (into admission-event-types emitted-model-authority-fact-types)
        #{claim-event-type :sheet/node-execution-completed
          :sheet/tick-cancelled :sheet/tree-tick-completed}))

(defn specialist-plan-facts [topology xs owner scope-id]
  (filterv #(or (and (contains? model-call-journal-event-types (:event/type %))
                     (or (same-operation? owner %)
                         (specialist-role-event? topology %)
                         (and scope-id (= scope-id (:plan-scope-id %)))))
                (and (contains? specialist-event-types (:event/type %))
                     (not (contains? model-call-journal-event-types (:event/type %))))) xs))

(defn candidate-events [xs index]
  (filter #(and (#{:sheet/model-candidate-claimed :sheet/model-candidate-recorded} (:event/type %))
                  (= index (or (:candidate-index %) (get-in % [:entry :candidate-index])))) xs))

(def stable-option-keys
  #{:temperature :max-tokens :top-p :top-k :frequency-penalty :presence-penalty :seed
    :reasoning :max-retries :retry-delay-ms :timeout-ms :output-mode :use-function-calling?
    :force-tool-choice? :prompt-cache? :generic-tool-description?
    :response-format :provider-options :privacy :data-collection :plugins :transforms})

(defn configuration-id [candidates policy]
  (provider-logical-action-identity
   {:module (mapv #(-> (select-keys % [:provider :model :options])
                       (update :options (fn [o] (select-keys o stable-option-keys)))) candidates)
    :inputs policy :options {}}))

(defn model-call-journal-incompatible! [reason]
  (throw (ex-info (str "Model plan cannot be resumed [" (name reason) "]")
                  {:failure-kind :model-plan-incompatible :reason reason})))

(defn checked-plan [plan]
  (when-not (and (m/validate model-call-journal-retained-plan-schema plan)
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
    (model-call-journal-incompatible! :retained-profile-version-unsupported))
  plan)

(defn plan-scope-id
  "Stable authored scope; provider-registry defaults and live deadline overlays
   are deliberately absent. Used before resolving any current provider."
  [owner authored-config]
  (provider-logical-action-identity
   {:module (select-keys owner operation-fields) :inputs authored-config :options {}}))

(defn legacy-terminal-plan [history]
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

(def prime-role-fields [:sheet-id :tick-id :node-id :exec-context :role])

(defn same-prime-role? [owner event]
  (= (select-keys owner prime-role-fields) (select-keys event prime-role-fields)))

(defn prime-plan-scope-id
  "Campaign-role configuration identity, independent of a conversation turn."
  [owner authored-config]
  (str "prime-role-v1:"
       (provider-logical-action-identity
        {:module (select-keys owner prime-role-fields) :inputs authored-config :options {}})))

(defn inline-plan-scope-id
  "One parent-researcher specialist configuration, independent of request input."
  [owner authored-config]
  (str "inline-role-v1:"
       (provider-logical-action-identity
        {:module (select-keys owner prime-role-fields) :inputs authored-config :options {}})))

;; --- Provider call reservations ---

(def reservation-event-type :sheet/provider-call-reserved)

(defn invocation-identity
  "Deterministic identity for one physical provider attempt.

   The durable budget root makes identities distinct across the root-local CAS
   streams. Logical action identity remains stable across retries; the
   non-negative physical ordinal distinguishes spent attempts within a root."
  [budget-tick-id logical-action-identity ownership-epoch provider-attempt-ordinal]
  (when-not (and (integer? provider-attempt-ordinal)
                 (not (neg? provider-attempt-ordinal)))
    (throw (ex-info "Provider attempt ordinal must be a non-negative integer"
                    {:provider-attempt-ordinal provider-attempt-ordinal})))
  (str "budget-root/" budget-tick-id "/" logical-action-identity
       "/epoch/" ownership-epoch
       "/provider-attempt/" provider-attempt-ordinal))

(def provider-call-reservations-fact-types
  #{:sheet/tree-tick-started :sheet/tree-tick-completed :sheet/tick-cancelled
    :sheet/node-execution-started :sheet/node-execution-completed
    :rlm/researcher-frontier-claimed :rlm/researcher-effect-claimed
    :sheet/provider-call-reserved :sheet/emitted-model-work-authorized
    :sheet/model-plan-retained :sheet/model-candidate-claimed :sheet/model-candidate-recorded
    :sheet/model-request-recorded :rlm/researcher-effect-completed})

(defn reservation-identity
  "Keep campaign identities compatible; ordinary ownership includes the exact
   canonical start, item context, logical action, candidate and node attempt."
  [{:keys [admission-kind budget-tick-id tick-id node-start-id exec-context
           logical-action-identity candidate-index node-attempt ownership-epoch
           provider-attempt-ordinal]}]
  (if (= :ordinary admission-kind)
    (pr-str [:ordinary budget-tick-id tick-id node-start-id
             (mapv #(get exec-context %) [:ai.obney.orc.orc-service.core.todo-processors/map-each-index
                                         :ai.obney.orc.orc-service.core.todo-processors/map-each-parent
                                         :ai.obney.orc.orc-service.core.todo-processors/tick-iteration])
             logical-action-identity candidate-index node-attempt provider-attempt-ordinal])
    (invocation-identity budget-tick-id logical-action-identity ownership-epoch
                         provider-attempt-ordinal)))

(defn- provider-call-reservations-lineage [starts tick-id root-id]
  (loop [tick tick-id seen #{} path []]
    (cond
      (or (nil? tick) (contains? seen tick) (nil? (get starts tick))) nil
      (= tick root-id) (conj path tick)
      :else (recur (:parent-tick-id (get starts tick)) (conj seen tick) (conj path tick)))))

(defn reservation-decision
  "Consume the dedicated root stream once. Duplicates cannot masquerade as
   exhaustion; canonical ownership and terminal fences are inside the CAS."
  [{:keys [budget-sheet-id budget-tick-id sheet-id tick-id node-id node-start-id
           exec-context admission-kind campaign-sheet-id campaign-tick-id
           campaign-node-id iteration-index invocation-identity ownership-epoch model-configuration-id model-logical-action-id]
    :as reservation}
   events]
  (let [events (into [] events)
        execution-key [tick-id node-id (or exec-context {})]
        state (reduce
                (fn [state event]
                  (let [type (:event/type event)
                        node-key [(:tick-id event) (:node-id event) (ai.obney.orc.orc-service.core.logic/exec-context (:inputs event))]]
                    (case type
                      :sheet/tree-tick-started
                      (update state :starts #(if (contains? % (:tick-id event)) % (assoc % (:tick-id event) event)))
                      :sheet/tick-cancelled (update state :terminals conj (:tick-id event))
                      :sheet/tree-tick-completed
                      (if (= :running (:root-status event)) state (update state :terminals conj (:tick-id event)))
                      :sheet/node-execution-started
                      (-> state (assoc-in [:node-starts node-key] event) (update :node-completed disj node-key))
                      :sheet/node-execution-completed
                      (if (= :tree-generated (:completion-kind event)) state (update state :node-completed conj node-key))
                      :rlm/researcher-frontier-claimed
                      (if (and (= campaign-sheet-id (:sheet-id event)) (= campaign-tick-id (:tick-id event))
                               (= campaign-node-id (:node-id event)))
                        (update state :epoch max (:ownership-epoch event)) state)
                      :rlm/researcher-effect-claimed
                      (if (and (= campaign-sheet-id (:sheet-id event)) (= campaign-tick-id (:tick-id event))
                               (= campaign-node-id (:node-id event)) (= ownership-epoch (:ownership-epoch event)))
                        (update state :iterations conj (:iteration-index event)) state)
                      :sheet/provider-call-reserved
                      (-> state (update :count inc)
                          (update :duplicate? #(or % (= invocation-identity (:invocation-identity event)))))
                      state)))
                {:starts {} :node-starts {} :node-completed #{} :terminals #{}
                 :epoch 0 :iterations #{} :count 0 :duplicate? false} events)
        starts (:starts state)
        root (get starts budget-tick-id)
        invoking (get starts tick-id)
        campaign (get starts campaign-tick-id)
        path (provider-call-reservations-lineage starts tick-id budget-tick-id)
        campaign-path (when campaign-tick-id (provider-call-reservations-lineage starts campaign-tick-id budget-tick-id))
        start (get-in state [:node-starts execution-key])
        ordinary? (= :ordinary admission-kind)
        budget (get-in root [:options :llm-call-budget])
        history-known? (= 1 (get-in root [:options :llm-budget-accounting-version]))
        valid? (and root invoking path
                    (= budget-sheet-id (:sheet-id root)) (= sheet-id (:sheet-id invoking))
                    (integer? budget) (pos? budget)
                    (not-any? (:terminals state) path)
                    (if ordinary?
                      (and start (= node-start-id (:event/id start)) (= sheet-id (:sheet-id start))
                           (not (true? (get-in invoking [:options :checkpointed-campaign?])))
                           (not (contains? (:node-completed state) execution-key)))
                      (and campaign campaign-path
                           start (= node-start-id (:event/id start))
                           (= sheet-id (:sheet-id start))
                           (not (contains? (:node-completed state) execution-key))
                           (= campaign-sheet-id (:sheet-id campaign))
                           (not-any? (:terminals state) campaign-path)
                           (= ownership-epoch (:epoch state))
                           (or (= tick-id campaign-tick-id)
                               (fresh-authorized?
                                 (cond-> {:sheet-id sheet-id :tick-id tick-id :node-id node-id :node-start-id node-start-id
                                          :exec-context (or exec-context {}) :logical-action-id model-logical-action-id}
                                   (some? model-configuration-id) (assoc :configuration-id model-configuration-id))
                                 {:sheet-id campaign-sheet-id :tick-id campaign-tick-id :node-id campaign-node-id
                                  :exec-context (or (get-in invoking [:options :researcher-campaign-exec-context]) {})
                                  :ownership-epoch (get-in invoking [:options :researcher-ownership-epoch])
                                  :iteration-index iteration-index} events))
                           (contains? (:iterations state) iteration-index))))]
    (merge {:current (:count state) :budget budget :root-tick-id budget-tick-id :root-sheet-id budget-sheet-id}
           {:decision (cond (not history-known?) :incomplete-history
                            (not valid?) :rejected
                            (:duplicate? state) :duplicate
                            (>= (:count state) budget) :exhausted
                            :else :admit)})))

(defn reservation-cas [reservation]
  {:tags #{[:llm-budget-root (:budget-tick-id reservation)]}
   :types provider-call-reservations-fact-types
   :predicate-fn (fn [events] (= :admit (:decision (reservation-decision reservation events))))})

;; --- Todo processors ---

;; =============================================================================
;; Output Key Normalization
;; =============================================================================

(defn normalize-output-keys
  "Convert output map keys to keywords for command schema compatibility.
   Handles both string and keyword keys, preserving values."
  [outputs]
  (when outputs
    (reduce-kv (fn [acc k v]
                 (assoc acc (if (keyword? k) k (keyword k)) v))
               {}
               outputs)))

(defonce ^:private tick-llm-counts (atom {}))

(def ^:private budget-cancellation-claim-ttl-ms 300000)

(defonce budget-cancellation-claims (atom {}))

(defn claim-budget-cancellation!
  [tick-id]
  (let [claimed? (atom false)
        now (System/currentTimeMillis)]
    (swap! budget-cancellation-claims
           (fn [claims]
             (let [claims (into {} (filter (fn [[_ claimed-at]]
                                              (< (- now claimed-at)
                                                 budget-cancellation-claim-ttl-ms)))
                                claims)]
               (if (contains? claims tick-id)
                 claims
                 (do (reset! claimed? true)
                     (assoc claims tick-id now))))))
    @claimed?))

(defn clear-llm-count!
  "Clear LLM count for a tick (called on tick completion)."
  [tick-id]
  (swap! tick-llm-counts dissoc tick-id))

;; =============================================================================
;; Tick-Scoped Usage Tracking
;; =============================================================================

(defonce ^:private tick-usage (atom {}))

(defn add-usage!
  "Add usage from a single LLM call to the tick's total."
  [tick-id usage]
  (when (and tick-id usage)
    (swap! tick-usage update tick-id
           (fn [u]
             (let [current (or u {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0})]
               {:prompt-tokens (+ (:prompt-tokens current 0)
                                  (or (:prompt-tokens usage) (:prompt_tokens usage) 0))
                :completion-tokens (+ (:completion-tokens current 0)
                                      (or (:completion-tokens usage) (:completion_tokens usage) 0))
                :total-tokens (+ (:total-tokens current 0)
                                 (or (:total-tokens usage) (:total_tokens usage) 0))})))))

(defn get-tick-usage
  "Get aggregated usage for a tick."
  [tick-id]
  (get @tick-usage tick-id {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0}))

(defn clear-tick-usage!
  "Clear usage for a tick (called on tick completion)."
  [tick-id]
  (swap! tick-usage dissoc tick-id))

(defn reserve-llm-call!
  "Atomically reserve one call against the root tick's shared LLM budget.
   Returns nil when reserved, or an exceeded descriptor when no capacity
   remains. With no explicit budget, records the call without imposing a cap.

   Takes the ALREADY-PROJECTED tick execution context rather than projecting
   its own. Every projection of :sheet/tick-execution-contexts decodes the
   tick's cached blob, and callers here already hold it."
  [tick-ctx tick-id]
  (let [budget (get-in tick-ctx [:options :llm-call-budget])
        budget-tick-id (or (get-in tick-ctx [:options :llm-budget-root-tick-id])
                           tick-id)
        budget-sheet-id (get-in tick-ctx [:options :llm-budget-root-sheet-id])
        outcome (atom nil)]
    (swap! tick-llm-counts
           (fn [counts]
             (let [current (get counts budget-tick-id 0)]
               (if (and budget (>= current budget))
                 (do (reset! outcome {:exceeded true
                                      :current current
                                      :budget budget
                                      :root-tick-id budget-tick-id
                                      :root-sheet-id budget-sheet-id})
                     counts)
                 (assoc counts budget-tick-id (inc current))))))
    @outcome))

;; =============================================================================
;; Tick-Scoped Resolution Helpers
;; =============================================================================

(defn ephemeral-routing-tree?
  "True when a tick's tree may run on the ephemeral fast path: only
   sequence/fallback/condition/leaf nodes, and no composite carrying judges.
   Ephemeral routing records no per-composite completion, so a judge on a
   composite (or the root) could never fire; a judged composite is a durable
   boundary and makes the run durable. A tree with no judged composite keeps
   the fast path unchanged. `:judges` is on every run node: draft nodes carry
   their own, a published run's nodes are stamped with their source draft
   node's at the start of the run (see `build-execution-snapshot`), so the
   answer is fixed for the life of the tick."
  [nodes-by-id]
  (and (seq nodes-by-id)
       (every? #(contains? #{:sequence :fallback :condition :leaf} (:type %))
               (vals nodes-by-id))
       (not-any? #(or (:model-fallback-policy %)
                       (and (not= :leaf (:type %)) (seq (:judges %))))
                 (vals nodes-by-id))))

(defn apply-instruction-override
  "If instruction overrides exist for this node's name, apply them."
  [node overrides]
  (if-let [override (and overrides (:name node) (get overrides (:name node)))]
    (assoc node :instruction override)
    node))

(defn maybe-lookup-parent-summary
  "Best-effort lookup of the parent sheet's consolidated :summary from
   the ontology corpus. Returns the summary string when available, nil
   otherwise. Graceful when the ontology component isn't loaded.

   The parent sheet's description lives in the :tree-fingerprint
   granularity, keyed by the sheet's canonical-tree-raw hash. C-2c-2's
   first cut uses sheet-id as a proxy — the corpus may have the
   description tagged under either form. We try sheet-id first; later
   slices can add fingerprint translation."
  [context sheet-id]
  (try
    (let [get-description (integrations/capability :ontology :get-description)]
      (when get-description
        (let [body (get-description context :tree-fingerprint sheet-id)]
          (:summary body))))
    (catch Exception _ nil)))

(defn run-or-defer-classification-effect!
  "Run a classification preparation effect immediately on the legacy path, or
   stage it for the checkpointed campaign's bounded commit.  Staging keeps a
   classifier timeout from exposing a durable decision before all of the work
   needed to prepare that decision has settled."
  [context kind command run!]
  (if-let [effects (:classification-effects context)]
    (swap! effects conj {:kind kind :command command})
    (run!)))

(defn domain-family-birth-description
  "CV-A item 5, as corrected in inspection: the rich, self-contained text
   minted onto the family's OWN concept :description and embedded at birth
   (`DomainFamilyDescribesItselfFromBirth`). Carries substance only: the
   family's label, its purpose (the verdict's domain-reasoning), the birth
   shape's OWN summary (the role it plays), and the birth task signature. No
   ids: the embedding and the merge judge read this text, and an id means
   nothing to either. Pure — no I/O."
  [domain-label parent-summary domain-reasoning signature]
  (str "Domain family \"" domain-label "\". "
       "Purpose (subject matter, material, output kind): "
       (or domain-reasoning "(no domain reasoning recorded at birth)") " "
       "It specialises the shape: "
       (or parent-summary "(the birth shape's summary was unavailable)") " "
       "Birth task: " signature))

(defn ->uuid
  "Normalize a target-id to a java.util.UUID before the read-model lookup.

   The classifier's :top-candidates carry :document-metadata.:target-id as
   a STRING (ColBERT bridges metadata through JSON, so a UUID seed comes
   back as \"5a08300e-10e3-305a-80c1-17eafea15ff7\"). The descriptions
   read-model keys bodies under the literal java.util.UUID. Without this
   coercion the lookup misses silently and the prepend degrades to
   summary-only.

   Pass-through for already-UUID input keeps the function idempotent.
   String input that does not parse as a UUID returns nil — that's a
   genuine miss and the prepend renders without the rich body (rather
   than crashing the request)."
  [v]
  (cond
    (uuid? v) v
    (string? v) (try (java.util.UUID/fromString v)
                     (catch IllegalArgumentException _ nil))
    :else nil))

(defn fetch-tree-body
  "Pull a tree-class description body via ontology/get-description.

   C-Loop-1: reads from :tree-class scope so the Living Description
   loop's consolidator-updated bodies surface in the next R-Inject
   run's prepend. Seeds are also recorded under :tree-class (via
   seed-all!), so first-time runs see seed content via the same read
   path. The classifier search index continues to partition by
   :tree-fingerprint — that's the index lookup, not the body read.

   T2-Hardening-B: coerce target-id to UUID before the lookup. The
   classifier's candidate metadata round-trips target-id through JSON
   so it arrives as a stringified UUID; the read-model keys by UUID.

   Returns nil on any failure (helper is best-effort — the prepend
   degrades to summary-only when the full body is unavailable)."
  [ctx target-id]
  (when-let [uuid-target (->uuid target-id)]
    (let [get-description (integrations/capability :ontology :get-description)]
      (try
        (when get-description
          (get-description ctx :tree-class uuid-target))
        (catch Exception _ nil)))))

(def ^:private behavioral-cap
  "Maximum behavioral suggestions surfaced to the model. We aim for up
   to 5 strong matches; weaker ones below min-display-confidence are
   filtered out before this cap takes effect. The wedge calls
   classify-behaviors with `:top-n 5` so the candidate set is sized
   to feed this cap. Adjust both in tandem if changing."
  5)

(def ^:private structural-cap
  "Maximum structural candidates surfaced to the model. classify-task
   retrieves :k 5 from the corpus; the reranker scores all of them.
   We aim for up to 5 strong matches; the min-display-confidence floor
   below prunes weak ones. So you see breadth WHEN the corpus has
   strong adjacent shapes, and a tight focused set when it doesn't."
  5)

(def ^:private min-display-confidence
  "Floor for surfacing a candidate in the prepend. Candidates that
   score below this on the reranker are dropped before the cap is
   applied — keeps weak/noisy matches out of the model's prompt
   regardless of how many slots we'd otherwise have. Tuned to the
   reranker's typical 'cliff' between strong matches (>=0.85) and
   noise (<=0.4): 0.6 catches the middle ground where a candidate
   is plausibly related but not a clear fit."
  0.6)

(def ^:private traits-per-seed-cap
  "Per matched seed, the max number of strengths AND max number of
   weaknesses included in the prepend. Strengths/weaknesses are sorted
   by :confidence descending; the top-N from each list are surfaced.
   Caps prompt bloat — production seeds may have 3-5 strengths each."
  2)

(defn- format-key-list
  "`[:doc :glossary]` -> \":doc, :glossary\" — keyword `str` already carries
   the leading colon, so the rendered text is the exact key a model would
   write in a blackboard map."
  [ks]
  (str/join ", " (map str ks)))

(defn- format-pattern-bindings
  "RR-22 (`OfferedPatternsAreUsable`): one line naming a pattern's declared
   key bindings, offered as ADVICE for rebinding — never as an instruction
   to run the pattern. Only called when at least one of reads/writes/outputs
   is present (`format-principle-entry`'s caller-side gate)."
  [pattern-reads pattern-writes pattern-outputs]
  (str "    - Reads: " (if (seq pattern-reads) (format-key-list pattern-reads) "(none declared)")
       " · Writes: " (if (seq pattern-writes) (format-key-list pattern-writes) "(none declared)")
       (when (seq pattern-outputs) (str " · Outputs: " (format-key-list pattern-outputs)))
       " — rebind these keys to your task's blackboard; the logic stays as written.\n"))

(defn- format-principle-entry
  "Render one entry from :strengths or :weaknesses as a prose block the
   model can read. `kind` is :strength or :weakness; selects which
   fields to include and how to label them.

   RR-22 (`OfferedPatternsAreUsable`, ratified no-truncation decision):
   `:recommended-pattern` is rendered WHOLE — no character cap, no emergency
   bound. A pattern offered as proven that elides its own code cannot be
   adopted; the cap that used to clip it here is gone, not raised."
  [kind entry]
  (let [{:keys [trait good-when avoid-when recommended-pattern
                recommended-alternative confidence evidence-count
                pattern-reads pattern-writes pattern-outputs]} entry
        ev-suffix (cond
                    (and confidence evidence-count)
                    (format " (confidence %.2f, evidence-count %d)"
                            (double confidence) (int evidence-count))
                    confidence
                    (format " (confidence %.2f)" (double confidence))
                    :else "")]
    (case kind
      :strength
      (str "  - **Trait:** " (or trait "(no trait recorded)") ev-suffix "\n"
           (when good-when
             (str "    - Good when: " good-when "\n"))
           (when recommended-pattern
             (str "    - Worked example DSL (corpus reference — adapt to your task):\n"
                  "      ```clojure\n"
                  "      " recommended-pattern "\n"
                  "      ```\n"
                  (when (or (seq pattern-reads) (seq pattern-writes) (seq pattern-outputs))
                    (format-pattern-bindings pattern-reads pattern-writes pattern-outputs)))))
      :weakness
      (str "  - **Failure mode:** " (or trait "(no trait recorded)") ev-suffix "\n"
           (when avoid-when
             (str "    - Avoid when: " avoid-when "\n"))
           (when recommended-alternative
             (str "    - Recommended fix: " recommended-alternative "\n"))))))

(defn- format-seed-body
  "Render the rich seed body (capabilities + strengths + weaknesses +
   representative-uses) as prose the model can read. Each section is
   optional — missing fields are skipped silently. Returns nil if the
   body is nil (caller-side gate).

   `traits-cap` is the per-list maximum count for strengths and
   weaknesses (sorted by :confidence desc before truncation).

   `suppress-claims?` is the CLAIM-ONLY holdout arm (see *claim-holdout*).
   When true the weakness list renders as if it were empty, so the whole
   `Weaknesses (…)` section — header included, since the header exists only
   because claims do — drops out and every other section is byte-identical.
   Nothing else on this path is arm-dependent."
  [body traits-cap suppress-claims?]
  (when body
    (let [{:keys [capabilities strengths weaknesses representative-uses]} body
          rank-by-conf (fn [coll] (sort-by (fn [e] (- (double (or (:confidence e) 0.0))))
                                           (or coll [])))
          top-strengths (take traits-cap (rank-by-conf strengths))
          top-weaknesses (when-not suppress-claims?
                           (take traits-cap (rank-by-conf weaknesses)))]
      (str
        (when (seq capabilities)
          (str "Capabilities:\n"
               (->> capabilities (map (fn [c] (str "  - " c))) (str/join "\n"))
               "\n\n"))
        (when (seq top-strengths)
          (str "Strengths (proven traits — these patterns have been observed to work; mimic where they fit, adapt as needed):\n"
               (->> top-strengths
                    (map (partial format-principle-entry :strength))
                    (str/join "\n"))
               "\n"))
        (when (seq top-weaknesses)
          (str "Weaknesses (observed failure modes — avoid these patterns, apply the recommended fix where applicable):\n"
               (->> top-weaknesses
                    (map (partial format-principle-entry :weakness))
                    (str/join "\n"))
               "\n"))
        (when (seq representative-uses)
          (str "Representative uses (concrete tasks this pattern has shipped on):\n"
               (->> representative-uses (map (fn [u] (str "  - " u))) (str/join "\n"))
               "\n"))))))

(def ^:private known-weaknesses-label
  "The `assemble-summary` section label that carries weakness CLAIM content
   into an assembled `:summary`."
  "Known weaknesses: ")

(def ^:private section-after-known-weaknesses
  "The label `assemble-summary` emits immediately AFTER `Known weaknesses`.
   Used only as the fallback terminator, below."
  " Avoid when: ")

(defn- claim-summary-span
  "`[start end)` of the `Known weaknesses: …` sentence inside `summary`, or nil
   when there is none.

   Preferred path: reconstruct the sentence EXACTLY as assemble-summary built
   it from the body's own `:weaknesses` and locate that literal — nothing
   adjacent can then be clipped, however the claim text is punctuated.

   Fallback, for a `:summary` that cannot be reconstructed (no body was
   readable, or the classifier surfaced an indexed summary the body has since
   moved past): cut from the label to the next section assemble-summary emits,
   or to the end. Weaker, because a claim containing the literal text
   ` Avoid when: ` would terminate it early — hence it is the fallback."
  [summary weaknesses]
  (when-let [label-at (and (string? summary) (str/index-of summary known-weaknesses-label))]
    (let [literal (when (seq weaknesses)
                    (str known-weaknesses-label
                         (str/join "; " (map :trait weaknesses))
                         "."))
          literal-at (when literal (str/index-of summary literal))]
      (if literal-at
        [literal-at (+ literal-at (count literal))]
        [label-at (or (str/index-of summary section-after-known-weaknesses label-at)
                      (count summary))]))))

(defn- strip-claim-summary-section
  "`summary` with its `Known weaknesses: …` sentence removed — including the
   single space assemble-summary joined it with, so the surviving sentences
   stay spaced exactly as they were. The joining space sits BEFORE the sentence
   unless the sentence leads the summary (a body whose only claims are
   weaknesses), in which case it sits after. Returns `summary` unchanged when
   it carries no weakness sentence."
  [summary weaknesses]
  (if-let [[start end] (claim-summary-span summary weaknesses)]
    (let [before-space? (and (pos? start) (= \space (.charAt ^String summary (dec start))))
          after-space? (and (not before-space?)
                            (< end (count summary))
                            (= \space (.charAt ^String summary end)))
          start (if before-space? (dec start) start)
          end (if after-space? (inc end) end)]
      (str (subs summary 0 start) (subs summary end)))
    summary))

(defn- guidance-summary
  "The `:summary` text the render quotes, on the arm it is rendering.

   On the claim-only holdout the weakness sentence is removed; everything else
   — including the seed-name derivation, which reads the ORIGINAL string — is
   untouched, so the two arms differ by the claim sentence and nothing else."
  [summary body suppress-claims?]
  (if suppress-claims?
    (strip-claim-summary-section summary (:weaknesses body))
    summary))

(defn- fetch-behavioral-body
  "Pull a behavioral-subtree description body via ontology/get-description.

   E3 Part-1 (scope fix): behavioral seed bodies — and minted children —
   land in the Living-Description read-model under the :tree-fingerprint
   granularity, NOT :tree-class. Both emission paths stamp
   :target-type :tree-fingerprint on the description-updated event:
     - seed-baseline-corpus! emits behavioral seeds via
       :ontology/record-tree-description (commands.clj record-tree-
       description → :target-type :tree-fingerprint)
     - :ontology/mint-behavioral-subtree emits the same event with
       :target-type :tree-fingerprint, keyed by the derived stable id.
   (Structural tree-class seeds are DUAL-emitted under :tree-class for the
   structural read path — behavioral ones are not, so a behavioral id read
   against :tree-class misses → rich body nil → no strengths rendered.)

   This is the behavioral counterpart of fetch-tree-body: same UUID
   coercion + best-effort nil-on-failure semantics, but reads the
   :tree-fingerprint scope where behavioral bodies actually live. The
   structural fetch-tree-body path is deliberately left reading
   :tree-class (C-Loop-1) and is NOT changed."
  [ctx target-id]
  (when-let [uuid-target (->uuid target-id)]
    (let [get-description (integrations/capability :ontology :get-description)]
      (try
        (when get-description
          (get-description ctx :tree-fingerprint uuid-target))
        (catch Exception _ nil)))))

(defn- derive-seed-name
  "Extract a human-readable seed name from the start of a `:summary`
   string. Most seeds begin with their own name followed by a stative
   verb or 'trees' / 'is' / 'evaluates' / etc.:
     'Legal-issue-detection trees flag...' → 'Legal-issue-detection'
     'Risk-analysis trees identify...'     → 'Risk-analysis'
     'Critique evaluates...'                → 'Critique'
     'Comparative summary is the...'        → 'Comparative summary'
     'ResearchThenSynthesize is a...'       → 'ResearchThenSynthesize'

   Falls back to nil when no clear name can be extracted (caller can
   then use a generic header). Best-effort — corpus authors should keep
   summaries written with the name first to make this work."
  [summary]
  (when (and (string? summary) (not (str/blank? summary)))
    (let [name-tail-re #"(?i)^(.+?)\s+(trees?|is|are|evaluates?|reasons?|integrates?|turns?|checks?|flag|identif(?:y|ies)|compare|extract|generates?|produces?|synthes(?:ize|izes)|investigates?|handles?|composes?|consists?|builds?|classifies?|validates?|gather|gathers|analyzes?)\b"
          m (re-find name-tail-re summary)]
      (when m
        (let [candidate (str/trim (nth m 1))]
          (when (<= (count candidate) 50)
            candidate))))))

(defn- rerank-source-caution
  "Per-candidate annotation naming WHY the reranker's score is missing.

   RR-1 (ADR 0020): the reranker's fallback is now stamped with WHICH
   failure it was — :colbert-fallback (it could not rank) vs
   :timeout-fallback (it ran out of budget, twice). Both mean the same
   thing for the reader — the score below is raw ColBERT similarity, not a
   grounded fitness — so both are cautioned; naming the reason keeps the
   distinction the signal now carries visible to the model and to anyone
   auditing the rendered prompt. Returns nil for a successful rerank."
  [rerank-source]
  (case rerank-source
    :colbert-fallback " [reranker fell back to ColBERT — treat with caution]"
    :timeout-fallback " [reranker timed out (retry included) and fell back to ColBERT — treat with caution]"
    nil))

(defn- format-structural-candidate
  "Render one structural candidate with its full seed body.
   `idx` is the 1-based position in the top-N list (used to label
   primary vs alternative)."
  [ctx suppress-claims? idx candidate]
  (let [{:keys [content fitness-score reasoning rerank-source
                document-metadata]} candidate
        target-id (:target-id document-metadata)
        body (fetch-tree-body ctx target-id)
        rich (format-seed-body body traits-per-seed-cap suppress-claims?)
        seed-name (derive-seed-name content)
        rank-label (cond
                     (= 1 idx) "Top match"
                     :else (str "Alternative #" (dec idx)))
        header-label (if seed-name
                       (str rank-label " — " seed-name)
                       rank-label)]
    (str "#### " header-label " (confidence: "
         (format "%.2f" (double (or fitness-score 0.0))) ")"
         (rerank-source-caution rerank-source)
         "\n"
         "Why this fits: " (or reasoning "(no reasoning recorded)") "\n\n"
         "Pattern guidance (seed `:summary`):\n"
         (or (guidance-summary content body suppress-claims?)
             "(no guidance content recorded)") "\n\n"
         (or rich ""))))

(defn- plain-match-candidates
  "The gated, capped top-candidates list a plain match renders: the display-
   confidence floor applied BEFORE the cap so a weak candidate doesn't push
   out the slot for a stronger one. Shared by the plain-match branch and,
   from RS-4, by a newborn domain assignment (whose display is the parent's
   plain-match view plus one child-assignment line)."
  [top-candidates]
  (->> (or top-candidates [])
       (filter (fn [c] (>= (double (or (:fitness-score c) 0.0))
                           min-display-confidence)))
       (reduce (fn [[seen out] c]
                 (let [k (str (get-in c [:document-metadata :target-id]))]
                   (if (seen k) [seen out] [(conj seen k) (conj out c)])))
               [#{} []])
       second
       (take structural-cap)
       vec))

(defn- domain-child-consolidated?
  "RS-4's discriminator between a newborn domain child (render the parent's
   full entry, the shape the task matched) and a consolidated one (render
   the child as the primary entry): the child's tree-class body is present
   AND its :consolidated-from-event-count is >= 1 — the LAST claim event's
   :evidence-event-count. The birth claim RS-3's wedge records carries 0 (one
   representative-use, unconsolidated); a harvest or reflection claim carries
   >= 1. Nil-ness is NOT the discriminator: the birth claim already
   assembles a body with one representative use, so a nil body only means
   the read genuinely missed (best-effort, like every other fetch-tree-body
   caller here) — treated as NOT consolidated, never as a reason to crash."
  [ctx child-tree-id]
  (let [body (fetch-tree-body ctx child-tree-id)]
    (and (some? body)
         (>= (long (or (:consolidated-from-event-count body) 0)) 1))))

(defn- format-domain-child-line
  "RS-4 newborn branch: ONE line, built only from the payload's :domain
   values, naming the child this task was assigned to and its label —
   follows the parent's full entry (rendered exactly as a plain match)
   rather than replacing it."
  [{:keys [child-tree-id domain-label]}]
  (str "Assigned to domain child " child-tree-id
       " (label: " domain-label ") under the top match — "
       "this campaign's outcome is that child's first evidence.\n"))

(defn- format-domain-shape-context-line
  "RS-4 consolidated branch: the parent drops from a full entry to this one
   line — its identity and its own :summary, whole (the summary is the
   mechanical one-line join the descriptions read-model assembles; it is
   never parsed or truncated here) — since the child is now the primary
   entry and the parent's full body (strengths, representative uses, etc.)
   is not repeated."
  [ctx suppress-claims? parent-tree-id]
  (let [parent-body (fetch-tree-body ctx parent-tree-id)
        summary (guidance-summary (:summary parent-body) parent-body suppress-claims?)]
    (str "Shape context — parent " parent-tree-id
         (when (seq summary) (str ": " summary))
         "\n")))

(defn- consolidated-domain-child-candidate
  "RS-4: the ONE candidate a consolidated domain child renders as — built
   from the payload, not from top-candidates: the child is the target (a
   :tree-class candidate, so the injection record names the child at its
   own body version — CC-13's single source of truth), the :content quoted
   as 'Pattern guidance' is the child's own body :summary, and the top
   match's :fitness-score/:reasoning/:rerank-source stay attached (that
   score and reasoning are WHY this class was reached)."
  [ctx {:keys [domain top-candidates]}]
  (let [{:keys [child-tree-id]} domain
        top-1 (first top-candidates)
        child-body (fetch-tree-body ctx child-tree-id)]
    {:content (:summary child-body)
     :fitness-score (:fitness-score top-1)
     :reasoning (:reasoning top-1)
     :rerank-source (:rerank-source top-1)
     :document-metadata {:granularity :tree-class :target-id child-tree-id}}))

(defn- format-consolidated-domain-child
  "RS-4: the child HAS a consolidated body — it renders as the PRIMARY
   entry (its own :summary + strengths via format-structural-candidate)
   from the candidate structural-display-candidates produced, then the
   parent as one shape-context line."
  [ctx suppress-claims? candidates {:keys [domain]}]
  (str "### Structural patterns (top " (count candidates) " from corpus retrieval)\n"
       (->> candidates
            (map-indexed (fn [i c] (format-structural-candidate ctx suppress-claims? (inc i) c)))
            (str/join "\n"))
       "\n"
       (format-domain-shape-context-line ctx suppress-claims? (:parent-tree-id domain))))

(defn- family-substance-candidate
  "CV-B (C7): the family's OWN candidate for the NEWBORN branch — added
   ALONGSIDE the parent's plain-match candidates (never in place of them),
   so the injection record (CC-13, via structural-display-candidates ->
   injected-candidates) names both the parent shape the task matched AND
   the family's own accrued substance rendered beneath the child line.

   Fetches the family's body ONCE (fetch-tree-body) here; the returned
   candidate stashes the fetched body under :family-body so format-
   structural-section's newborn branch can render the same substance
   without a second fetch. :document-metadata carries :granularity
   :tree-class (a tree-class read, like the consolidated candidate) and
   :target-id the child — the id CC-13 records a version for.

   Returns nil (no candidate, nothing rendered beneath the child line) when
   the family has no substance beyond its birth line — format-seed-body
   returns nil/blank for a body with no capabilities, strengths, weaknesses,
   or representative uses, and D5 says the parent stays primary either way."
  [ctx suppress-claims? {:keys [domain top-candidates]}]
  (let [{:keys [child-tree-id]} domain
        top-1 (first top-candidates)
        family-body (fetch-tree-body ctx child-tree-id)]
    (when (seq (format-seed-body family-body traits-per-seed-cap suppress-claims?))
      {:content (:summary family-body)
       :fitness-score (:fitness-score top-1)
       :reasoning (:reasoning top-1)
       :rerank-source (:rerank-source top-1)
       :document-metadata {:granularity :tree-class :target-id child-tree-id}
       :family-body family-body})))

(defn- structural-display-candidates
  "The structural candidates the render actually puts in front of the model.

   Apply the display-confidence floor BEFORE the cap so a weak candidate
   doesn't push out the slot for a stronger one. In practice classify-task
   returns 5; the reranker scores spread from 1.00 down to 0.0 — only the
   >= floor entries reach the model. The fresh-mint branch shows NO
   candidates at all (it renders guidance instead), so it yields none.

   RS-4: a domain assignment (:domain present) overrides :was-fresh-mint? —
   a newborn domain child (not yet consolidated) renders the PARENT's
   top-candidates entry exactly like a plain match (the top one IS the
   parent), regardless of RS-2 stamping :was-fresh-mint? true on the mint.
   A CONSOLIDATED domain child renders ONE candidate: the child itself,
   built from the payload rather than from top-candidates
   (consolidated-domain-child-candidate) — so the injection record names
   the child it showed, at the child's own body version.

   CV-B (C7): the newborn branch APPENDS the family's own candidate
   (family-substance-candidate) after the parent's plain-match candidates,
   when the family has substance beyond its birth line. Byte-identical to
   RS-4 when it does not.

   `suppress-claims?` (default false via the 2-arity form) is the claim-only
   holdout arm — threaded through so a candidate is only ever counted as
   'shown' under the SAME arm the render actually used (CC-13's single
   source of truth).

   CC-13: single source of truth for the render AND for the injection record.
   A record of 'what was injected' computed from a different filter than the
   render's would be a measurement of something that never happened."
  ([ctx structural] (structural-display-candidates ctx false structural))
  ([ctx suppress-claims? {:keys [was-fresh-mint? top-candidates domain] :as structural}]
   (cond
     (and domain (not (domain-child-consolidated? ctx (:child-tree-id domain))))
     (let [child-id (str (:child-tree-id domain))
           parent-id (:parent-tree-id domain)
           shown (into [] (remove #(= child-id (str (get-in % [:document-metadata :target-id]))))
                       (plain-match-candidates top-candidates))
           parent-shown? (some #(= (str parent-id) (str (get-in % [:document-metadata :target-id]))) shown)
           parent-candidate (when (and parent-id (not parent-shown?))
                              (when-let [parent-body (fetch-tree-body ctx parent-id)]
                                (let [top-1 (first top-candidates)]
                                  {:content (:summary parent-body)
                                   :fitness-score (:fitness-score top-1)
                                   :reasoning (:reasoning top-1)
                                   :rerank-source (:rerank-source top-1)
                                   :document-metadata {:granularity :tree-class :target-id parent-id}})))
           base (if parent-candidate (into [parent-candidate] shown) shown)
           family-candidate (family-substance-candidate ctx suppress-claims? structural)]
       (cond-> base
         family-candidate (conj family-candidate)))

     domain
     [(consolidated-domain-child-candidate ctx structural)]

     was-fresh-mint?
     []

     :else
     (plain-match-candidates top-candidates))))

(defn format-structural-section [ctx suppress-claims? structural]
  (let [{:keys [was-fresh-mint? rerank-fallback? domain]} structural
        candidates (structural-display-candidates ctx suppress-claims? structural)]
    (cond
      (and domain (domain-child-consolidated? ctx (:child-tree-id domain)))
      (format-consolidated-domain-child ctx suppress-claims? candidates structural)

      domain
      (let [child-tree-id (:child-tree-id domain)
            parent-candidates (remove #(= (str child-tree-id)
                                          (str (get-in % [:document-metadata :target-id])))
                                      candidates)
            family-candidate (some #(when (= (str child-tree-id)
                                             (str (get-in % [:document-metadata :target-id])))
                                       %)
                                    candidates)
            family-substance (:family-body family-candidate)]
        (str "### Structural patterns (top "
             (count parent-candidates)
             " from corpus retrieval)\n"
             (when rerank-fallback?
               "Classifier reranker fell back to similarity scoring; treat suggestions with caution and prioritize your own reading of the task.\n\n")
             (->> parent-candidates
                  (map-indexed (fn [i c] (format-structural-candidate ctx suppress-claims? (inc i) c)))
                  (str/join "\n"))
             "\n"
             (format-domain-child-line domain)
             (when family-substance
               (or (format-seed-body family-substance traits-per-seed-cap suppress-claims?) ""))))

      was-fresh-mint?
      (str "### Structural patterns\n"
           "No high-confidence structural match was returned by the classifier. "
           "Design at the abstract pattern level — the structural shape is the "
           "primary signal you have. The behavioral suggestions below reflect "
           "what the task ACCOMPLISHES.\n")

      (seq candidates)
      (str "### Structural patterns (top "
           (count candidates)
           " from corpus retrieval)\n"
           (when rerank-fallback?
             "Classifier reranker fell back to similarity scoring; treat suggestions with caution and prioritize your own reading of the task.\n\n")
           (->> candidates
                (map-indexed (fn [i c] (format-structural-candidate ctx suppress-claims? (inc i) c)))
                (str/join "\n"))))))

(defn- format-behavioral-entry [ctx suppress-claims? idx behavior]
  (let [{:keys [behavior-id confidence was-fresh-mint? reasoning
                rerank-source]} behavior]
    (if was-fresh-mint?
      (str (inc idx) ". Behavioral suggestion (no candidate above threshold)\n"
           "   BEHAVIORALLY-NOVEL TASK SIGNAL: classify-behaviors returned no candidate "
           "above threshold for this task — none of the existing corpus behaviors "
           "matched the task semantics by retrieval+rerank score. This is a SIGNAL the "
           "task may exercise a behavioral pattern the corpus does not yet capture.\n\n"
           "   The existing behavioral categories in the corpus are: research, "
           "extraction, analysis, synthesis, ideation, design, critique, validation, "
           "code-building, transformation, classification, investigation.\n\n"
           "   Before designing your tree, in your :reasoning output, state EITHER:\n"
           "     (a) which existing category fits this task (e.g., \"this is a "
           "design task — produces a single committed plan from constraints\"), OR\n"
           "     (b) why the task is genuinely novel relative to all 12 (name the "
           "behavioral pattern your tree will exercise that isn't captured above).\n\n"
           "   If (b) — the task IS behaviorally novel AND your tree exercises a "
           "generalizable pattern future tasks could reuse — contribute it via:\n\n"
           "       (mint-behavior! \"<short-kebab-name>\"\n"
           "                       {:capabilities [\"<concrete action verb + object — 1 per capability>\"\n"
           "                                       \"<another concrete capability>\"]\n"
           "                        :strengths [{:trait \"<what works AND why\"\n"
           "                                     :good-when \"<context guard — when this trait fires>\"\n"
           "                                     :recommended-pattern \"<concrete DSL snippet showing the shape>\"\n"
           "                                     :confidence 0.7\n"
           "                                     :evidence-count 1}]\n"
           "                        :weaknesses [{:trait \"<what fails AND why>\"\n"
           "                                      :avoid-when \"<context guard — when this trait fires>\"\n"
           "                                      :recommended-alternative \"<concrete fix or alternative\"\n"
           "                                      :confidence 0.7\n"
           "                                      :evidence-count 1}]\n"
           "                        :representative-uses [\"<concrete task this pattern shipped on>\"\n"
           "                                              \"<another concrete task>\"]\n"
           "                        :avoid-when [\"<concrete anti-context>\"]\n"
           "                        :summary \"<2-3 sentences naming the domain + the load-bearing trait + when to prefer this over existing categories>\"\n"
           "                        :version 1\n"
           "                        :consolidated-from-event-count 0}\n"
           "                       :parent nil)\n\n"
           "   SPECIALIZE vs ROOT-MINT: `:parent nil` mints a NEW root behavior. If, instead, one "
           "of the nearest references listed above is a broad-but-related parent (it shares the "
           "task's shape even though it fell below threshold), prefer specializing a CHILD of it — "
           "pass that reference's behavior-id as `:parent <id>` so the new behavior accrues evidence "
           "under the proven parent rather than scattering as an unrelated root.\n\n"
           "   CRITICAL: :strengths and :weaknesses are VECTORS OF MAPS, not vectors of "
           "strings. Each entry is principle-shaped — :trait + context-guard + concrete "
           "recommended action + confidence + evidence-count. A vector of bare strings "
           "fails schema validation; the rejection is returned in iteration history for correction. Look at any "
           "existing seed body in the corpus retrieval above to see the expected shape.\n\n"
           "   The minted behavior will be retrievable on subsequent classify-behaviors "
           "calls — your contribution persists in the corpus for future tasks. Without "
           "minting, a genuinely novel pattern is LOST when this task completes.\n\n"
           "   If (a) — the task fits an existing category by your judgement — no mint "
           "is needed; designing the tree using known patterns is the right call.\n")
      (let [body (fetch-behavioral-body ctx behavior-id)
            summary (:summary body)
            rich (format-seed-body body traits-per-seed-cap suppress-claims?)
            seed-name (derive-seed-name summary)
            header-label (if seed-name
                           (str (inc idx) ". Behavioral: " seed-name)
                           (str (inc idx) ". Behavioral suggestion"))]
        (str header-label " (confidence: "
             (format "%.2f" (double (or confidence 0.0))) ")"
             (rerank-source-caution rerank-source)
             "\n"
             "   Why this fits: " (or reasoning "(no reasoning recorded)") "\n\n"
             "   Guidance (seed `:summary`):\n"
             "   " (or (guidance-summary summary body suppress-claims?)
                       reasoning "(no guidance recorded)") "\n\n"
             (or rich "")
             "\n   Adopt / adapt / SPECIALIZE / mint-adjacent — use the evidence above, not the score alone:\n"
             "   - ADOPT this pattern as-is if it is an EXACT fit for your task.\n"
             "   - ADAPT it (keep the shape, override the specifics) if it mostly fits.\n"
             "   - SPECIALIZE — if this is a BROAD fit rather than an exact one (its `:summary`/strengths"
             " describe a more general shape than your task, or its `:avoid-when` flags your context),"
             " mint a domain-specialized CHILD of THIS behavior. The child keeps the parent's proven shape"
             " but pins your domain, gets a STABLE derived id, and ACCRUES evidence under the parent"
             " (it is retrievable on subsequent classify-behaviors calls):\n\n"
             "       (mint-behavior! \"<short-kebab-name>\"\n"
             "                       {:capabilities [\"<concrete action verb + object>\"]\n"
             "                        :strengths [{:trait \"<what works AND why>\"\n"
             "                                     :good-when \"<context guard>\"\n"
             "                                     :recommended-pattern \"<concrete DSL snippet>\"\n"
             "                                     :confidence 0.7\n"
             "                                     :evidence-count 1}]\n"
             "                        :weaknesses [{:trait \"<what fails AND why>\"\n"
             "                                      :avoid-when \"<context guard>\"\n"
             "                                      :recommended-alternative \"<concrete fix>\"\n"
             "                                      :confidence 0.7\n"
             "                                      :evidence-count 1}]\n"
             "                        :representative-uses [\"<concrete task this child shipped on>\"]\n"
             "                        :avoid-when [\"<concrete anti-context>\"]\n"
             "                        :summary \"<2-3 sentences naming the domain + load-bearing trait + when to prefer this over the parent>\"\n"
             "                        :version 1\n"
             "                        :consolidated-from-event-count 0}\n"
             "                       :parent " behavior-id ")\n\n"
             "   - MINT-ADJACENT — if your task is related-but-DISTINCT (not a child of this behavior),"
             " mint an adjacent behavior the same way with `:parent nil` (or under whichever listed"
             " reference is the true nearest parent).\n"
             "   :strengths/:weaknesses are VECTORS OF MAPS (principle-shaped: :trait + context-guard +"
             " concrete recommended action + :confidence + :evidence-count) — a vector of bare strings"
             " fails schema validation; the rejection is returned in iteration history for correction.\n")))))

(defn- behavioral-display-entries
  "The behavioral entries the render puts in front of the model. Companion to
   structural-display-candidates — shared by the render and the CC-13 record."
  [{:keys [behaviors]}]
  (vec (take behavioral-cap (or behaviors []))))

(defn format-behavioral-section [ctx suppress-claims? behavioral]
  (let [{:keys [rerank-fallback?]} behavioral
        entries (behavioral-display-entries behavioral)]
    (when (seq entries)
      (str "### Behavioral competencies (top " (count entries)
           " from corpus retrieval)\n"
           (when rerank-fallback?
             "Classifier reranker fell back on the behavioral axis; treat the suggestions below with caution.\n\n")
           (->> entries
                (map-indexed (partial format-behavioral-entry ctx suppress-claims?))
                (str/join "\n"))))))

(defn current-node-type-guidance
  "Read the current Living Description for this node type when the ontology
   component is present. R-Inject already marks the execution as opted into
   adaptive guidance; this adds the complementary node-type learning to the
   retrieved tree/behavior guidance without introducing a production
   dependency from orc-service to ontology."
  [ctx node]
  (when-let [node-type (:type node)]
    (try
      (when-let [get-description
                 (integrations/capability :ontology :get-description)]
        (when-let [body (get-description ctx :node-type node-type)]
          {:target-id node-type
           :version (:version body)
           :body body}))
      (catch Throwable _ nil))))

(defn format-node-type-guidance [guidance]
  (when guidance
    (str "### Current learned guidance for node type `"
         (:target-id guidance) "` (version " (:version guidance) ")\n"
         "This is the current persisted Living Description for the executor "
         "you are using. Apply it alongside the retrieved corpus examples.\n"
         (pr-str (:body guidance)) "\n")))

(defn sha256-hex
  "SHA-256 of a string, lowercase hex. Lets the record IDENTIFY the rendered
   block — two turns that saw the same thing collapse to the same hash —
   without storing the block twice."
  [^String s]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) digest))))

(def default-baseline-policy-id
  "Name of the control condition when a holdout config does not supply one.

   A CONSTANT, not a lookup into *injection-holdout*: an experiment binds that
   var to a partial map, and a row whose baseline is nil is a row that cannot
   be analysed — the record must stay valid whatever the caller left out."
  "r-inject/no-injection")

(def ^:dynamic *injection-holdout*
  "The randomized-holdout policy for R-Inject renders. **OFF BY DEFAULT.**

   A holdout is what makes attribution CAUSAL rather than observational: a
   recorded fraction of turns render under the control condition, so a change
   to the render can be compared against turns that ran without it in the SAME
   codebase state. The earlier pattern-injection study had no such arm — its
   baseline came from a different build — which is why its cost table carries
   no defensible quality attribution.

   It is default-off because a standing holdout is a standing tax on output
   quality. Turning it on is a deliberate, per-experiment choice:

     - process-wide:  (binding [*injection-holdout* {:enabled? true
                                                     :fraction 0.2}] …)
     - per-run:       (assoc ctx :injection-holdout {:enabled? true
                                                     :fraction 0.2})

   Keys:
     :enabled?           master switch; nothing is held out while false
     :fraction           P(holdout) in [0.0, 1.0]
     :baseline-policy-id names the control condition on BOTH arms, so treated
                         rows say what they were compared against"
  {:enabled? false
   :fraction 0.0
   :baseline-policy-id default-baseline-policy-id})

(def default-claim-baseline-policy-id
  "Name of the CLAIM-ONLY control condition. A constant, for the same reason
   default-baseline-policy-id is one."
  "r-inject/no-claim-injection")

(def ^:dynamic *claim-holdout*
  "The CLAIM-ONLY holdout policy for R-Inject renders. **OFF BY DEFAULT.**
   EXPERIMENT-ONLY, exactly like *injection-holdout*, and it is a second,
   independent arm rather than a mode of that one.

   Why it exists. *injection-holdout* is all-or-nothing: it suppresses the
   whole `## Suggested patterns from corpus` block. An A/B against it therefore
   compares tens of thousands of characters of corpus content against none, so
   (a) a positive result cannot be attributed to any single claim, and (b) the
   ~30x prompt-size gap is itself a confound running against the treated arm.
   This holdout keeps the block and suppresses CLAIM-DERIVED content only —
   both places a weakness claim reaches the prompt:

     1. the `Weaknesses (…)` section format-seed-body renders from a body's
        `:weaknesses` (header included: it exists only because claims do), and
     2. the `Known weaknesses: …` sentence the SAME claims put inside the
        assembled `:summary` the render quotes as `Pattern guidance`.

   Everything else in the block is byte-identical between the arms — that is
   the property the W2P-1 acceptance test asserts by reconstruction, and it is
   the only thing that makes the contrast attributable to the claim.

   NOT suppressed, deliberately: a node-type Living Description
   (format-node-type-guidance) is rendered whole and untouched. It is not a
   corpus candidate's claim, and it is IDENTICAL on both arms, so it cannot
   confound the contrast.

   HOW TO SET IT — and why `binding` is usually wrong. The render runs on the
   app's poller thread, not on the caller's, so a thread-local `binding` in a
   REPL or a harness NEVER REACHES IT and produces a silent no-op: both arms
   render treated and the experiment reports a confident false null with no
   symptom. W2 learned this the hard way. Use one of:

     - process-wide (what an experiment harness wants):
         (alter-var-root #'*claim-holdout*
                         (constantly {:enabled? true :fraction 1.0}))
     - per-run, no global at all (preferred where the caller owns the context):
         (assoc ctx :claim-holdout {:enabled? true :fraction 1.0})

   `binding` is correct ONLY when the render demonstrably runs on the binding
   thread — i.e. in unit tests that call apply-r05-classifier-context directly.

   Keys mirror *injection-holdout*: :enabled?, :fraction, :baseline-policy-id.
   Arm assignment goes through the same *holdout-assignment* seam, salted with
   :claim so the two holdouts do not co-assign the same occurrences. Running
   BOTH at once is not a supported configuration; if it happens,
   *injection-holdout* wins, because it is the stronger suppression."
  {:enabled? false
   :fraction 0.0
   :baseline-policy-id default-claim-baseline-policy-id})

(def ^:dynamic *capture-rendered-block?*
  "Whether the injection record carries the VERBATIM rendered block.

   Off by default: the block runs to tens of thousands of characters and the
   record already carries its hash, its size and the ids+versions it was built
   from. A bench or experiment run that wants to quote what the model literally
   saw turns it on — process-wide via binding, or per-run via
   `(assoc ctx :injection-capture-rendered-block? true)`."
  false)

(defn- occurrence-bucket
  "A deterministic, uniform value in [0.0, 1.0) for one render occurrence.

   Hash-bucketing rather than a call to `rand`: the assignment must be STABLE
   for a given turn, so a retry of the same occurrence cannot switch arms
   halfway through and produce a row whose arm does not describe the prompt
   that was actually sent. Uniform across occurrences, reproducible, stateless."
  [occurrence]
  (let [bits (Long/parseLong (subs (sha256-hex (str/join "|" (map str occurrence))) 0 15) 16)]
    (/ (double bits) (double (bit-shift-left 1 60)))))

(defn- default-holdout-assignment
  "True when this occurrence falls in the holdout arm."
  [occurrence fraction]
  (< (occurrence-bucket occurrence) (double fraction)))

(def ^:dynamic *holdout-assignment*
  "Capability seam for arm assignment: (fn [occurrence fraction] -> holdout?).

   Defaults to the real deterministic bucketing and is faked in tests, so no
   test has to depend on real randomness to prove the two arms behave."
  default-holdout-assignment)

(defn holdout-config
  "The effective holdout policy: the process default, overridden per-run by
   `:injection-holdout` on the context."
  [ctx]
  (merge *injection-holdout* (:injection-holdout ctx)))

(defn claim-holdout-config
  "The effective CLAIM-ONLY holdout policy: the process default, overridden
   per-run by `:claim-holdout` on the context."
  [ctx]
  (merge *claim-holdout* (:claim-holdout ctx)))

(defn- holds-out?
  "True when this occurrence falls into `config`'s holdout. `salt` distinguishes
   the two holdouts' assignments, so enabling both would not hand the same
   occurrences to both arms."
  [ctx {:keys [enabled? fraction]} node-id salt]
  (let [f (double (or fraction 0.0))]
    (boolean (and enabled?
                  (pos? f)
                  (*holdout-assignment*
                    (cond-> [(:sheet-id ctx) (:tick-id ctx) node-id]
                      salt (conj salt))
                    f)))))

(defn assign-arm
  "Which arm this render occurrence is in.

   :holdout       — the whole block is suppressed (*injection-holdout*)
   :claim-holdout — the block renders WITHOUT claim-derived content
                    (*claim-holdout*)
   :treatment     — the full render

   The all-or-nothing holdout is decided first and wins: it already suppresses
   every claim, so a row cannot honestly be both."
  [ctx holdout claim-holdout node-id]
  (cond
    (holds-out? ctx holdout node-id nil) :holdout
    (holds-out? ctx claim-holdout node-id :claim) :claim-holdout
    :else :treatment))

(defn injected-candidates
  "The candidate set the render actually showed, each with the VERSION of the
   description body its content came from.

   Version is per-candidate, not per-turn: the corpus bodies move
   independently, and 'this turn saw behaviour X at v4' is the granularity an
   attribution needs. Reads the same bodies the render reads (best-effort —
   an unavailable body records a nil version rather than dropping the
   candidate, because the candidate WAS shown).

   `suppress-claims?` (default false via the 2-arity form) threads the same
   holdout arm the render used into structural-display-candidates, so a
   CV-B family candidate is recorded exactly when its substance was actually
   shown under THIS arm — cc13's single-source rule."
  ([ctx payload] (injected-candidates ctx false payload))
  ([ctx suppress-claims? {:keys [structural behavioral]}]
  (vec
    (concat
      (for [c (structural-display-candidates ctx suppress-claims? structural)
            :let [tid (get-in c [:document-metadata :target-id])]
            :when (some? tid)]
        {:axis :structural
         :candidate-id (str tid)
         :version (:version (fetch-tree-body ctx tid))
         :score (some-> (:fitness-score c) double)})
      (for [b (behavioral-display-entries behavioral)
            :let [bid (:behavior-id b)]
            :when (and (some? bid) (not (:was-fresh-mint? b)))]
        {:axis :behavioral
         :candidate-id (str bid)
         :version (:version (fetch-behavioral-body ctx bid))
         :score (some-> (:confidence b) double)})))))

(defn format-specialize-bullet
  "The four-move menu's SPECIALIZE bullet, as a function of the payload's
   structural axis.

   RS-4: when `:domain` is present the runtime has ALREADY assigned this
   task to a domain child (RS-2/RS-3's assign-domain-child, dispatched
   before the model ever sees this prompt) — inviting the model to
   `mint-behavior!` a structural specialization here would invite a SECOND,
   redundant structural mint on top of the one the runtime already made.
   The bullet points at that assignment instead.

   Without `:domain` the bullet is BYTE-IDENTICAL to the pre-RS-4 text —
   every other render (plain match, walk-down's own mint, top-level fresh
   mint, uncertain) is unaffected."
  [structural]
  (if-let [domain (:domain structural)]
    (str "  - SPECIALIZE — the runtime has already assigned this task to a "
         "domain child of the top match (label: " (:domain-label domain)
         "); design for that domain under the parent's shape, do NOT mint a "
         "structural child yourself.\n")
    (str "  - SPECIALIZE — mint a CHILD of the nearest reference (`mint-behavior!` with `:parent <that behavior-id>`) when the top hit is a BROAD shape rather than an exact fit; the child keeps the proven shape, pins your domain, and accrues evidence under the parent. This is the RECOMMENDED move when a match cleared threshold only on shape.\n")))

(defn- apply-ontology-context
  "If node has :context parameter, inject ontology context into instruction.

   The :context parameter can include:
   - :problem-type - Problem URI for context lookup
   - :include-patterns - Include success patterns
   - :include-failures - Include failure patterns
   - :tree-id - Enable self-learning mode
   - :self-learning? - Enable self-learning context

   This requires the ontology component to be available in the context."
  [node context]
  (let [ctx-config (:context node)]
    (if (and ctx-config (:instruction node))
      (try
        (let [ontology-ns (integrations/capability :ontology :build-ontology-context)
              format-fn (integrations/capability :ontology :format-context-for-llm)]
          (if (and ontology-ns format-fn)
            (let [
                  tree-id (or (:tree-id ctx-config)
                              (when (:self-learning? ctx-config) (:sheet-id context)))
                  ontology-ctx (ontology-ns context
                                            (cond-> {:problem-type (:problem-type ctx-config)}
                                              tree-id (assoc :tree-id tree-id)
                                              (:self-learning? ctx-config) (assoc :self-learning? true)))
                  formatted (format-fn ontology-ctx
                                       {:include (cond-> #{}
                                                   (:include-patterns ctx-config) (conj :patterns)
                                                   (:include-failures ctx-config) (conj :failures))
                                        :max-items 5})]
              (if (and formatted (not (str/blank? formatted)))
                (update node :instruction
                        (fn [inst]
                          (str "## Ontology Context\n\n"
                               formatted
                               "\n\n---\n\n"
                               inst)))
                node))
            node))
        (catch Exception _e
          node))
      node)))

(defn make-bb-write-event
  "Create a tick-scoped blackboard write event (isolated per execution).

   `attribution` is an optional {:node-id _ :exec-context _} naming the node
   EXECUTION this write belongs to. It matters most for map-each item writes:
   the parent emits them before starting each child, and all iterations share
   one blackboard key, so without attribution a consumer cannot tell which
   iteration a given item write was for — and the blackboard slot itself only
   ever holds the most recent one. With it, each item write is addressable by
   the same [node-id exec-context] identity every other write uses."
  ([context sheet-id tick-id key value]
   (make-bb-write-event context sheet-id tick-id key value nil))
  ([context sheet-id tick-id key value attribution]
   (->event
    {:type :sheet/execution-value-written
     :tags #{[:sheet sheet-id]
             [:tick tick-id]}
     :body (prepare-write
            context
            (cond-> {:tick-id tick-id
                     :sheet-id sheet-id
                     :key key
                     :value value}
              (:node-id attribution) (assoc :node-id (:node-id attribution))
              (seq (:exec-context attribution))
              (assoc :exec-context (:exec-context attribution))
              (:input-seed? attribution) (assoc :input-seed? true)))})))

;; =============================================================================
;; Tick Execution Processor
;; =============================================================================

;; =============================================================================
;; Node Execution Processor
;; =============================================================================

(def ^:dynamic *default-llm-provider* :openrouter)

(defn extract-execution-context
  "Extract map-each execution context from inputs.
   Returns a map with only the context keys needed for correlation."
  [inputs]
  (select-keys inputs [:ai.obney.orc.orc-service.core.todo-processors/map-each-index :ai.obney.orc.orc-service.core.todo-processors/map-each-parent :ai.obney.orc.orc-service.core.todo-processors/tick-iteration]))

(defn delegate-child-tick-id
  "Return the durable identity of one logical delegate invocation.

   The parent tick iteration is the durable invocation ordinal: duplicate
   delivery and recovery within an iteration resolve to the same child, while
   reaching the same delegate after a Running frontier starts a new child.
   Map-each identity remains included so concurrent invocations stay isolated."
  [parent-tick-id node-id execution-context]
  (let [work-context (select-keys execution-context
                                  [:ai.obney.orc.orc-service.core.todo-processors/map-each-index :ai.obney.orc.orc-service.core.todo-processors/map-each-parent
                                   :ai.obney.orc.orc-service.core.todo-processors/tick-iteration])]
    (java.util.UUID/nameUUIDFromBytes
     (.getBytes (pr-str [parent-tick-id node-id (into (sorted-map) work-context)])
                "UTF-8"))))

(defonce ^:private delegate-claims (atom #{}))

(defn claim-delegate!
  "Claim process-local responsibility for observing one durable child tick.
   The tick id remains the cross-process claim; this set closes the interval
   before its originating event lands and bounds duplicate-delivery futures."
  [child-tick-id]
  (let [claimed? (volatile! false)]
    (swap! delegate-claims
           (fn [claims]
             (if (contains? claims child-tick-id)
               claims
               (do (vreset! claimed? true)
                   (conj claims child-tick-id)))))
    @claimed?))

(defn release-delegate! [child-tick-id]
  (swap! delegate-claims disj child-tick-id))

(def todo-processors-profile-value
  "See core.profile — extracted so the command side can profile too."
  profile-value)

(def todo-processors-profile-values profile-values)

(def todo-processors-compute-input-profile compute-input-profile)

(def ^:private max-trace-input-chars 20000)

(defn- truncate-trace-value
  "Truncate a single read value for inclusion in a trace's :inputs.
   Strings longer than max-trace-input-chars are cut with a marker; other
   values pass through unchanged (vectors/maps are bounded structurally by
   the reads themselves and stay small in practice)."
  [v]
  (if (and (string? v) (> (count v) max-trace-input-chars))
    (str (subs v 0 max-trace-input-chars)
         "\n…[truncated " (- (count v) max-trace-input-chars) " chars]")
    v))

(defn read-sources
  "For each of a node's declared :reads, the id of the write event that
   produced the value the node is about to see: {read-key event-id}.

   Returns {} inside a map-each iteration. A root tick iteration is sequential
   and therefore retains an exact blackboard source; it must not be treated as
   map-each merely because it also carries execution-disambiguating context.
   Inside map-each the blackboard holds ONE :source per key, and
   concurrent iterations sharing the item key clobber it, so any id captured
   here would name an arbitrary iteration's write. The iteration's own
   exec-context is the reliable identity and travels on both the completion
   and the item write; recording a plausible-but-wrong id alongside it is
   worse than recording nothing, because a consumer would trust it.

   Recorded on the completion event so a consumer can rehydrate the node's
   inputs EXACTLY. Resolving a read by key name alone yields the last write
   to that key in the tick, which — when a key is written more than once —
   can be a value produced after this node had already finished.

   Keys with no entry are ones seeded into the tick rather than written
   during it (they have no write event); consumers fall back to the tick's
   seeded inputs for those. Pure function — testable in isolation."
  ([reads blackboard] (read-sources reads blackboard nil))
  ([reads blackboard exec-context]
   (if (or (contains? exec-context :ai.obney.orc.orc-service.core.todo-processors/map-each-index)
           (contains? exec-context :ai.obney.orc.orc-service.core.todo-processors/map-each-parent))
     {}
     (reduce (fn [acc k]
               (if-let [src (get-in blackboard [k :source])]
                 (assoc acc k src)
                 acc))
             {}
             (or reads [])))))

(defn extract-read-inputs
  "Build the {read-key -> value} map a node actually read from the blackboard,
   restricted to the node's declared :reads. Large string values are truncated
   (see max-trace-input-chars) so the eval trace carries real grounding context
   without bloating events. Returns {} when reads is empty or no values present.

   This is the honest-grounding capture: control nodes (sequence/fallback)
   emit a non-leaf child's node-execution-started with only the map-each
   context, so the synthesis/answer node's real inputs were dropped from the
   trace. Capturing them at execution time — when the blackboard is fully
   resolved (prior siblings' writes already merged) — gives judges the
   evidence the answer was grounded on. Pure function — testable in isolation."
  [reads blackboard]
  (reduce
    (fn [acc k]
      (let [entry (get blackboard k)]
        (if (contains? entry :value)
          (assoc acc k (truncate-trace-value (:value entry)))
          acc)))
    {}
    (or reads [])))

(defn command-rejection
  "The anomaly a command returned when it was rejected, else nil.
   cp/process-command reports a rejected command (schema failure, failed
   authorization, append conflict) by RETURNING an anomaly, not by throwing."
  [result]
  (when (and (map? result) (:cognitect.anomalies/category result))
    result))

;; =============================================================================
;; REPL Researcher Node Execution Processor
;; =============================================================================

(defn strip-nil-researcher-optional-writes
  "Treat only a literal nil top-level researcher write as optional absence.
   Present structured values must cross their declared Malli schema boundary,
   even when every nested value is nil."
  [outputs optional-writes]
  (if (and (map? outputs) (seq optional-writes))
    (into {} (remove (fn [[k v]]
                       (and (contains? optional-writes k) (nil? v)))
                     outputs))
    outputs))

(defn checkpoint->v2-researcher-facts
  "Split the executor's compatibility checkpoint shape at the durable boundary.
   The executor may still return a history-bearing map to direct callers, but
  public execution persists the latest completed attempt once and keeps only
  continuation data in the supersedable resume state."
  [checkpoint]
  (let [history (vec (:history checkpoint))
        history-entry (last history)
        explicit-record (:iteration-record checkpoint)
        iteration-index (or (:iteration-index explicit-record)
                            (:iteration-index history-entry)
                            (dec (:next-iteration checkpoint)))
        attempt-ordinal (or (:attempt-ordinal explicit-record)
                            (:attempt-ordinal history-entry)
                            0)
        terminal-status (get-in checkpoint [:terminal-result :status])
        status (or (:status explicit-record)
                   terminal-status
                   (:status history-entry)
                   (if (:error history-entry) :failure :success))]
    {:resume-state (-> checkpoint
                       (dissoc :history :terminal-result :iteration-record)
                       (assoc :version 2))
     :iteration-record
     (cond-> (assoc (or explicit-record history-entry {})
                    :iteration-index iteration-index
                    :attempt-ordinal attempt-ordinal
                    :status status)
       (nil? explicit-record)
       (assoc :generated-code-recorded?
              (boolean (seq (:code history-entry)))
              :emitted-tree-recorded?
              (boolean (or (:generated-tree-raw history-entry)
                           (:tree-outcome history-entry)))))}))

;; =============================================================================
;; Delegate Node Execution Processor
;; =============================================================================

(def ^:dynamic *max-tick-iterations*
  "Default maximum number of tree ticks before a Running execution fails."
  10)

;; =============================================================================
;; Condition Node Execution Processor
;; =============================================================================

(defn- normalize-for-comparison
  "Normalize values for comparison, handling yesno/boolean cases.
   Converts 'yes'/'true'/true to true, 'no'/'false'/false to false."
  [v]
  (cond
    (boolean? v) v
    (string? v) (let [lower (str/lower-case v)]
                  (cond
                    (#{"yes" "true" "1"} lower) true
                    (#{"no" "false" "0"} lower) false
                    :else (try (Double/parseDouble v) (catch Exception _ v))))
    :else v))

(defn evaluate-condition-check
  "Evaluate a condition check against a blackboard value.
   Returns true if check passes, false otherwise.
   Handles yesno fields by normalizing 'yes'/'no' strings to booleans."
  [check blackboard]
  (let [{:keys [key op value]} check
        entry (get blackboard key)
        bb-value (:value entry)
        norm-bb (normalize-for-comparison bb-value)
        norm-val (normalize-for-comparison value)]
    (case op
      :equals (= norm-bb norm-val)
      :not-equals (not= norm-bb norm-val)
      :gt (and (number? norm-bb) (number? norm-val) (> norm-bb norm-val))
      :lt (and (number? norm-bb) (number? norm-val) (< norm-bb norm-val))
      :gte (and (number? norm-bb) (number? norm-val) (>= norm-bb norm-val))
      :lte (and (number? norm-bb) (number? norm-val) (<= norm-bb norm-val))
      :contains (and (string? bb-value) (string? value) (.contains bb-value value))
      :exists (some? bb-value)
      :truthy (boolean norm-bb)
      false)))

(defn ephemeral-summary-event [sheet-id tick-id iteration steps]
  (when (seq steps)
    (->event {:type :sheet/ephemeral-evaluations-recorded
              :tags #{[:sheet sheet-id] [:tick tick-id]}
              :body {:sheet-id sheet-id
                     :tick-id tick-id
                     :iteration iteration
                     :steps (mapv #(assoc % :trace-instance-id (random-uuid)) steps)}})))

(defn completed-statuses [events]
  (reduce (fn [statuses event]
            (if (= :sheet/node-execution-completed (:event/type event))
              (assoc statuses (:node-id event)
                     (select-keys event [:status :error :block-payload]))
              statuses))
          {} events))

(defn evaluate-ephemeral-node [node-id nodes-by-id blackboard completed decisions]
  (let [node (get nodes-by-id node-id)]
    (case (:type node)
      :leaf
      (if-let [completion (get completed node-id)]
        (assoc completion :steps [])
        {:boundary node-id :steps []})

      :condition
      (let [check (:check node)
            passed? (and check (evaluate-condition-check check blackboard))
            recorded? (contains? decisions node-id)
            status (or (get decisions node-id)
                       (if passed? :success (get check :on-fail :failure)))]
        {:status status
         :steps (if recorded?
                  []
                  [{:node-id node-id :status status :node-type :condition}])})

      :sequence
      (loop [children (:children-ids node) steps []]
        (if-let [child-id (first children)]
          (let [result (evaluate-ephemeral-node child-id nodes-by-id blackboard completed decisions)
                next-steps (into steps (:steps result))]
            (cond
              (:boundary result) (assoc result :steps next-steps)
              (contains? #{:success :tree-generated :partial} (:status result))
              (recur (rest children) next-steps)
              :else (assoc (select-keys result [:status :error :block-payload])
                           :steps (conj next-steps {:node-id node-id
                                                   :status (:status result)
                                                   :node-type :sequence}))))
          {:status :success
           :steps (conj steps {:node-id node-id :status :success :node-type :sequence})}))

      :fallback
      (loop [children (:children-ids node) steps [] last-failure nil]
        (if-let [child-id (first children)]
          (let [result (evaluate-ephemeral-node child-id nodes-by-id blackboard completed decisions)
                next-steps (into steps (:steps result))]
            (cond
              (:boundary result) (assoc result :steps next-steps)
              (= :failure (:status result)) (recur (rest children) next-steps result)
              :else (assoc (select-keys result [:status :error :block-payload])
                           :steps (conj next-steps {:node-id node-id
                                                   :status (:status result)
                                                   :node-type :fallback}))))
          (assoc (select-keys last-failure [:error :block-payload])
                 :status :failure
                 :steps (conj steps {:node-id node-id :status :failure :node-type :fallback})))))))

;; =============================================================================
;; Child Completion Handler
;; =============================================================================

(defn matches-execution-context?
  "Check if an event's inputs match the given execution context.
   Empty context matches everything (for non-map-each executions)."
  [event exec-context]
  (if (empty? exec-context)
    true
    (let [event-inputs (or (:inputs event) {})]
      (every? (fn [[k v]]
                (= (get event-inputs k) v))
              exec-context))))

(defmulti next-child-strategy
  "Select the next sibling for an ordered control node.

   Custom strategies dispatch on :strategy-id. Missing and unknown ids retain
   the engine's linear behavior."
  (fn [{:keys [strategy-id]}] (or strategy-id :linear)))

(defmethod next-child-strategy :linear
  [{:keys [siblings child-index]}]
  (get (vec siblings) (inc child-index)))

(defmethod next-child-strategy :default
  [opts]
  (next-child-strategy (assoc opts :strategy-id :linear)))

(defn- evaluate-parallel-completion
  "Evaluate if a parallel node should complete based on its policies.
   Returns nil if not ready, or {:status :success/:failure} if ready."
  [child-counts success-policy failure-policy]
  (let [{:keys [success failure total-children completed]} child-counts
        success-policy (or success-policy :all)
        failure-policy (or failure-policy :any)]
    (cond
      (and (= failure-policy :any) (> failure 0))
      {:status :failure}

      (and (= failure-policy :all) (= failure total-children))
      {:status :failure}

      (and (= success-policy :all) (= success total-children))
      {:status :success}

      (and (= success-policy :any) (> success 0))
      {:status :success}

      (and (= success-policy :majority) (> success (/ total-children 2)))
      {:status :success}

      (= completed total-children)
      {:status :failure}

      :else nil)))

(defmulti parallel-completion-strategy
  "Evaluate parallel completion through an extensible strategy id."
  (fn [{:keys [strategy-id]}] (or strategy-id :boolean)))

(defmethod parallel-completion-strategy :boolean
  [{:keys [child-counts success-policy failure-policy]}]
  (evaluate-parallel-completion child-counts success-policy failure-policy))

(defmethod parallel-completion-strategy :default
  [opts]
  (parallel-completion-strategy (assoc opts :strategy-id :boolean)))

;; =============================================================================
;; Map-Each Node Execution Processor
;; =============================================================================

(defonce map-each-state (atom {}))

(defn map-each-key [tick-id node-id]
  (str tick-id "-" node-id))

(defn classify-map-each-outcome
  "Classify the outcome of a map-each based on its results vector.

   Input:
     {:results    [...]   ;; raw value for success, {:__status :failure ...} for failure
      :item-count N}      ;; total items started

   Returns a 3-tuple: [status partial-summary-or-nil output-vector]
     status  = :success | :partial | :failure
     summary = nil OR {:total :succeeded :failed :failure-indices :failure-reasons}
     output  = successes-only vector

   Rule table (Q6 from D-008 PRD):
     - Empty input          -> [:success nil []]
     - All items succeeded  -> [:success nil <full vector>]
     - 0 < failed < N       -> [:partial <summary> <successes-only>]
     - All items failed     -> [:failure <summary> []]"
  [{:keys [results item-count]}]
  (if (zero? item-count)
    [:success nil []]
    (let [failure? (fn [r] (and (map? r) (= :failure (:__status r))))
          indexed (map-indexed vector results)
          failed-pairs (filter (fn [[_ r]] (failure? r)) indexed)
          successful-pairs (remove (fn [[_ r]] (failure? r)) indexed)
          failed-count (count failed-pairs)]
      (cond
        (zero? failed-count)
        [:success nil (vec results)]

        :else
        (let [summary {:total item-count
                       :succeeded (- item-count failed-count)
                       :failed failed-count
                       :failure-indices (mapv first failed-pairs)
                       :failure-reasons (into {} (map (fn [[i r]] [i (or (:__error r) "(no error reason — node timed out or returned nil)")]) failed-pairs))}
              status (if (= failed-count item-count) :failure :partial)
              output (mapv second successful-pairs)]
          [status summary output])))))

(defn deliver-cancellation
  "When a tick is cancelled, unblock any caller waiting on runtime/execute
   immediately instead of letting it hang until its timeout. The status
   stays :failure (existing callers switch on :status); :cancelled? true is
   the additive discriminator. Idempotent with the eventual terminal
   delivery: whichever fires first wins the promise, the other no-ops."
  [{:keys [event]}]
  (let [tick-id (:tick-id event)]
    (deliver-completion! tick-id
      {:status :failure
       :error (or (:reason event) "tick cancelled")
       :cancelled? true
       :outputs {}
       :trace-id tick-id})
    (swap! tick-llm-counts dissoc tick-id)
    (cancel-active-work! tick-id)
    (clear-tick! tick-id)
    (forget-tick! tick-id)
    (forget-ephemeral-context! tick-id))
  nil)

(defn trace-execution-context
  "Return the execution-disambiguating context for trace correlation.
   This distinguishes repeated executions of the same node under map-each."
  [inputs]
  (select-keys (or inputs {})
               [:ai.obney.orc.orc-service.core.todo-processors/map-each-index :ai.obney.orc.orc-service.core.todo-processors/map-each-parent :ai.obney.orc.orc-service.core.todo-processors/tick-iteration]))

(defn trace-execution-key
  "Correlation key for matching started/completed events in trace assembly."
  [event]
  [(:node-id event) (trace-execution-context (:inputs event))])

(defn timestamp-ms [timestamp]
  (cond
    (instance? java.time.Instant timestamp) (.toEpochMilli ^java.time.Instant timestamp)
    (instance? java.time.OffsetDateTime timestamp) (-> ^java.time.OffsetDateTime timestamp .toInstant .toEpochMilli)
    :else (-> (java.time.OffsetDateTime/parse (str timestamp)) .toInstant .toEpochMilli)))

(defn todo-processors-elapsed-ms [started completed]
  (when (and started completed)
    (max 0 (- (timestamp-ms (:event/timestamp completed))
              (timestamp-ms (:event/timestamp started))))))

(defn trace-node-path [nodes-by-id node-id]
  (loop [current node-id, path (), seen #{}, depth 0]
    (if (or (nil? current) (contains? seen current) (>= depth 100))
      (vec path)
      (let [node (get nodes-by-id current)]
        (recur (:parent-id node)
               (conj path (:name node))
               (conj seen current)
               (inc depth))))))

(defn order-researcher-events
  "Order a campaign's trace entries by DURABLE position — the string form of the
   source event's UUIDv7 id (the same order the event store itself returns), with
   a yield placed right after the checkpoint that carried it — and strip the
   ordering key. Never sort by the rendered `:at` strings: they are stamped by
   different producers at different precisions and Java drops trailing zero
   groups, so `…49.64Z` sorts lexically AFTER `…49.640123Z` although it is
   earlier (seen once in five full brick runs as a claim ordered after its own
   effect)."
  [entries]
  (->> entries
       (sort-by :ai.obney.orc.orc-service.core.todo-processors/durable-order)
       (mapv #(dissoc % :ai.obney.orc.orc-service.core.todo-processors/durable-order))))

;; =============================================================================
;; Todo Processor Registry
;; =============================================================================

;; =============================================================================
;; Snapshot Restore Processor
;; =============================================================================

(defn flatten-snapshot-nodes
  "Flatten a nested snapshot tree into a list of [node parent-id] pairs,
   in creation order (parent before children)."
  [snapshot-node parent-id]
  (when snapshot-node
    (let [node-id (random-uuid)
          node-record [node-id parent-id snapshot-node]
          children (or (:children snapshot-node) [])]
      (cons node-record
            (mapcat #(flatten-snapshot-nodes % node-id) children)))))

(defn collect-deletion-order
  "Collect nodes in deletion order (children before parents, leaf-first).
   Returns vector of node-ids to delete."
  [nodes-by-id root-id]
  (letfn [(collect [node-id]
            (let [node (get nodes-by-id node-id)
                  children-ids (:children-ids node)]
              (concat (mapcat collect children-ids)
                      [node-id])))]
    (when root-id
      (vec (collect root-id)))))

(defn- ordinary-model-plan-incompatible! [reason]
  (throw (ex-info (str "Model plan cannot be resumed [" (name reason) "]")
                  {:failure-kind :model-plan-incompatible :reason reason})))

(defn resolve-plan
  "Preflight a new call once, propagating the primary conversational mode before
   backup preflight. Native finite candidates keep their own protocol."
  [node provider module options]
  (let [raw (candidate-plan node provider options)
        transient-keys #{:model :orc/explicit-provider? :validate? :with-metadata?
                         :with-provider-evidence?}
        _ (doseq [candidate raw]
            (when-not (model-call-journal-valid-retained-options?
                       (apply dissoc (:options candidate) transient-keys))
              (ordinary-model-plan-incompatible! :unsupported-or-unsafe-options)))
        preflight (fn [index candidate inherited-mode]
                    (let [explicit? (or (contains? node :provider)
                                        (and (pos? index)
                                             (contains? (nth (:fallback-models node) (dec index)) :provider)))
                          opts (cond-> (:options candidate)
                                 inherited-mode (assoc :output-mode inherited-mode)
                                 (:model candidate) (assoc :model (:model candidate))
                                 explicit? (assoc :orc/explicit-provider? true))]
                      (llm/preflight-provider! (:provider candidate) module opts)))
        resolved
        (loop [index 0 remaining raw selected-mode :ai.obney.orc.orc-service.core.ordinary-model-plan/unset result []]
          (if-let [candidate (first remaining)]
            (let [resolved (preflight index candidate (when-not (= :ai.obney.orc.orc-service.core.ordinary-model-plan/unset selected-mode) selected-mode))
                  chat? (= :chat (:protocol resolved))
                  mode (:output-mode resolved)]
              (when (and chat? (not= :ai.obney.orc.orc-service.core.ordinary-model-plan/unset selected-mode) (not= selected-mode mode))
                (ordinary-model-plan-incompatible! :effective-profile-cannot-be-preserved))
              (recur (inc index) (rest remaining)
                     (if (and chat? (= :ai.obney.orc.orc-service.core.ordinary-model-plan/unset selected-mode)) mode selected-mode)
                     (conj result resolved)))
            result))
        candidates
        (mapv (fn [{:keys [provider model protocol output-mode options]}]
                (let [native? (= :decision protocol)
                      paired? (contains? #{:json-schema :function-calling} output-mode)
                      force? (and (not native?) (boolean (:force-tool-choice? options)))
                      legacy-function? (and (not native?) (not paired?)
                                            (true? (:use-function-calling? options)))
                      profile {:version (cond native? "native-decision-v1"
                                              (= output-mode :function-calling) "paired-function-v1-strict"
                                              (= output-mode :json-schema) "strict-json-v1"
                                              legacy-function? "legacy-function-v1"
                                              :else "legacy-marker-v1")
                               :output-mode (when-not native? output-mode)
                               :force-tool-choice? force?
                               :strict-arguments? (= output-mode :function-calling)}
                      options (-> (select-keys options model-call-journal-retained-option-keys)
                                  (dissoc :model))
                      options (cond native? (dissoc options :output-mode :force-tool-choice? :use-function-calling?)
                                    paired? (-> options (dissoc :use-function-calling?)
                                                (assoc :output-mode output-mode :force-tool-choice? force?))
                                    :else (-> options (dissoc :output-mode)
                                              (assoc :use-function-calling? (boolean legacy-function?)
                                                     :force-tool-choice? force?)))]
                  {:provider provider :model model :protocol protocol
                   :options options :profile profile})) resolved)]
    {:version 1 :policy (or (:fallback-policy node) :temporary-transport)
     :candidates candidates}))

;; --- Researcher models ---

(defn subcall-defaults [node]
  (let [rlm (if (map? (:rlm node)) (:rlm node) {})]
    (cond-> {}
      (contains? rlm :sub-model) (assoc :model (:sub-model rlm))
      (contains? rlm :sub-provider) (assoc :provider (:sub-provider rlm))
      (contains? rlm :sub-options) (assoc :options (:sub-options rlm))
      (contains? rlm :sub-fallback-models) (assoc :fallback-models (:sub-fallback-models rlm))
      (contains? rlm :sub-fallback-policy) (assoc :fallback-policy (:sub-fallback-policy rlm)))))

(defn inherit [defaults opts]
  (cond-> (merge defaults opts)
    (or (:options defaults) (:options opts))
    (assoc :options (merge (:options defaults) (:options opts)))))

(defn resolved-plan [node provider module options]
  (mapv (fn [index candidate]
          (let [declared (if (zero? index) node (nth (:fallback-models node) (dec index)))
                opts (cond-> (:options candidate)
                       (:model candidate) (assoc :model (:model candidate))
                       (or (contains? node :provider) (contains? declared :provider))
                       (assoc :orc/explicit-provider? true))]
            (if (or (contains? node :fallback-models) (contains? node :provider))
              (select-keys (llm/preflight-provider! (:provider candidate) module opts) [:provider :model :options])
              (assoc candidate :options (dissoc opts :orc/explicit-provider?)))))
        (range) (candidate-plan node provider options)))

(defn logical-id [context role name module inputs node options]
  (let [provider (or (:provider node) (:llm-provider context) (:provider context))
        plan (resolved-plan node provider module options)]
    (provider-logical-action-identity
     {:tick-id (:tick-id context) :node-id (:node-id context)
      :iteration-index (:researcher-iteration context)
      :provider provider :model (:model node) :module module :inputs inputs
      :options {:role role :name name
                :configuration-id (configuration-id plan (or (:fallback-policy node) :temporary-transport))}})))

(defn owner [context role logical-action-id]
  (merge (get-in context [:model-role-context :owner])
         {:sheet-id (:sheet-id context) :tick-id (:tick-id context) :node-id (:node-id context)
          :node-start-id (or (:node-start-id context) (get-in context [:event :event/id]))
          :exec-context (or (:exec-context context) (exec-context (get-in context [:event :inputs])))
          :role role :logical-action-id logical-action-id}))

(defn persist! [record! receipt]
  (when record!
    (let [r (record! receipt)]
      (when (:cognitect.anomalies/category r)
        (throw (ex-info "Model request receipt could not be persisted"
                        {:failure-kind :observation-persistence-failed}))))))

(defn distinct-completed-receipts [prepared receipts]
  (let [receipts (vec (distinct receipts))
        by-attempt (group-by :attempt-ordinal receipts)]
    (when (and prepared
               (or (some #(not (nat-int? (:attempt-ordinal %))) receipts)
                   (some #(< 1 (count %)) (vals by-attempt))))
      (throw (ex-info "Historical model request ordinal has conflicting receipts"
                      {:failure-kind :model-plan-incompatible :reason :historical-operation-ambiguous})))
    receipts))

(defn exact-replay-success
  "A saved success authorizes incorporation, never another physical request.
   Earlier missing outcomes do not make that exact result disappear."
  [prepared receipts]
  (when (:replay-only? prepared)
    (let [successes (filterv #(= :success (get-in % [:outcome :status])) receipts)]
      (when (< 1 (count successes))
        (throw (ex-info "Historical model request has competing successful receipts"
                        {:failure-kind :model-plan-incompatible :reason :historical-operation-ambiguous})))
      (when-let [receipt (first successes)]
        (let [ordinals (vec (sort (map :attempt-ordinal receipts)))
              missing (- (inc (last ordinals)) (count ordinals))
              unreported (mapv :attempt-ordinal (filter #(nil? (get-in % [:outcome :usage])) receipts))
              known-usage (keep #(get-in % [:outcome :usage]) receipts)
              usage (when (seq known-usage) (reduce #(merge-with + %1 %2) {} known-usage))
              outcome (:outcome receipt)
              accounting {:source :retained-model-request-receipts
                          :scope :candidate-physical-attempts
                          :selected-request-id (:request-id receipt)
                          :selected-attempt-ordinal (:attempt-ordinal receipt)
                          :known-completed-attempt-ordinals ordinals
                          :unrecorded-attempt-ordinal-count missing
                          :unreported-usage-attempt-ordinals unreported
                          :usage-complete? (and (zero? missing) (empty? unreported))}]
          (-> outcome
              (assoc :usage usage)
              (update :provider-evidence #(assoc (or % {}) :runtime-derived-recovery-accounting accounting))))))))

;; --- Executor ---

(defn checkpoint-durable-value?
  "True when value can cross the researcher checkpoint event boundary without
   retaining JVM identity. Registered event-store scalar codecs are included;
   functions, records, atoms, futures and lazy sequences are deliberately not."
  [value]
  (letfn [(durable? [v]
            (cond
              (or (nil? v) (string? v) (boolean? v) (number? v)
                  (keyword? v) (symbol? v) (char? v)
                  (uuid? v) (inst? v)) true
              (record? v) false
              (map? v) (every? (fn [[k x]] (and (durable? k) (durable? x))) v)
              (vector? v) (every? durable? v)
              (set? v) (every? durable? v)
              (list? v) (every? durable? v)
              :else false))]
    (durable? value)))

(def ^:private checkpoint-codec-tag :orc.researcher.codec/tag)

(def ^:private checkpoint-codec-value :orc.researcher.codec/value)

(def iteration-reasoning-max-chars
  "Measured maximum reasoning length in the checked-in live RR-5 evidence bank."
  reasoning-max-chars)

(def iteration-error-excerpt-max-chars
  "Measured maximum error length in the checked-in live RR-5 evidence bank."
  error-excerpt-max-chars)

(defn bound-iteration-evidence-text
  "Keep at most max-chars from the exact prefix of one durable evidence string."
  [text max-chars]
  (bound-text text max-chars))

(defn- unsupported-checkpoint-kind [value]
  (cond
    (fn? value) "function"
    (instance? clojure.lang.IAtom value) "atom"
    (instance? clojure.lang.IPending value) "pending-value"
    (record? value) (str "record:" (.getName (class value)))
    :else (or (some-> value class .getName) "unknown")))

(defn encode-checkpoint-value
  "Convert a checkpoint value to canonical durable data. Codecs are maps with
   :tag, :match? and :encode. Their encoded payload must itself be durable."
  [codecs value]
  (letfn [(encode* [v path]
            (if-let [{:keys [tag encode]}
                     (some (fn [{:keys [match?] :as codec}]
                             (when (and match? (match? v)) codec))
                           codecs)]
              (let [payload (encode v)
                    encoded-payload (encode* payload (conj path :codec-payload))]
                (when-not (and tag (checkpoint-durable-value? encoded-payload))
                  (throw (ex-info
                          (str "Researcher checkpoint codec produced a non-durable value at "
                               (pr-str path))
                          {:codec tag :path path})))
                {checkpoint-codec-tag tag checkpoint-codec-value encoded-payload})
              (cond
                (map? v) (into (empty v)
                               (map (fn [[k x]]
                                      [(encode* k (conj path :map-key))
                                       (encode* x
                                                (conj path
                                                      (if (checkpoint-durable-value? k)
                                                        k
                                                        :map-value)))])
                                    v))
                (vector? v) (mapv (fn [index x]
                                    (encode* x (conj path index)))
                                  (range)
                                  v)
                (set? v) (into #{} (map #(encode* % (conj path :set-member))) v)
                (list? v) (apply list
                                 (map (fn [index x]
                                        (encode* x (conj path index)))
                                      (range)
                                      v))
                (checkpoint-durable-value? v) v
                :else
                (let [kind (unsupported-checkpoint-kind v)]
                  (throw
                   (ex-info
                    (str "Researcher checkpoint contains an unsupported runtime value at "
                         (pr-str path) " (kind " kind ")")
                    {:path path :value-kind kind}))))))]
    (encode* value [])))

(defn decode-checkpoint-value
  "Rehydrate values encoded by encode-checkpoint-value. Missing codecs fail
   explicitly so a restarted runtime cannot silently change sandbox meaning."
  [codecs value]
  (let [by-tag (into {} (map (juxt :tag identity)) codecs)]
    (letfn [(decode* [v]
              (if (and (map? v)
                       (= #{checkpoint-codec-tag checkpoint-codec-value} (set (keys v))))
                (let [tag (get v checkpoint-codec-tag)
                      codec (get by-tag tag)]
                  (when-not (:decode codec)
                    (throw (ex-info "Researcher checkpoint codec is not registered"
                                    {:codec tag})))
                  ((:decode codec) (decode* (get v checkpoint-codec-value))))
                (cond
                  (map? v) (into (empty v) (map (fn [[k x]] [(decode* k) (decode* x)])) v)
                  (vector? v) (mapv decode* v)
                  (set? v) (into #{} (map decode*) v)
                  (list? v) (apply list (map decode* v))
                  :else v)))]
      (decode* value))))

(def provider-classification-lead-ms
  "How much EARLIER than the transport's deadline ORC's own provider deadline fires.

   ORC bounds every provider call twice: its own `bounded-call` deadline, and the
   request timeout it hands the transport through the LLM component. Both were the
   SAME value, so which fired first was a coin flip — and they classify
   differently. ORC's timer yields `:timeout-kind :provider`, which retries the
   iteration from its checkpoint (`TimedOutIterationRetriesFromCheckpoint`). The
   transport's timer raises an exception that reaches us with its cause chain
   already flattened to a string by the provider library, so nothing structural
   survives to identify it as a deadline expiry rather than a genuine provider
   error — and it terminated the campaign with the retry budget unspent.

   Measured on a live pinned-model campaign: consecutive attempts on ONE campaign
   were classified `:timeout` at 1,226 ms and `:failure` at 1,213 ms.

   The fix shortens OUR timer rather than lengthening the transport's: the
   transport must never be handed more than the enclosing workflow's live
   remainder (`EveryCampaignOperationIsBounded`), so the outer bound is the one
   that has to stay exact."
  250)

(defn provider-classification-deadline-ms
  "ORC's own deadline for a provider call: strictly earlier than the deadline the
   transport gets, so ORC's timer is the one that classifies a deadline expiry.

   The lead is capped at a tenth of the budget so a deliberately tiny deadline
   shrinks proportionally instead of collapsing to nothing, and the result is
   never less than 1 ms while the budget itself is positive."
  [provider-timeout-ms]
  (if (pos? provider-timeout-ms)
    (max 1 (- provider-timeout-ms
              (min provider-classification-lead-ms
                   (quot provider-timeout-ms 10))))
    provider-timeout-ms))

(defn transport-timeout?
  "Recognize durable researcher deadlines and the LLM boundary's structural
   transport timeout contract. No exception cause is needed after replay."
  [throwable]
  (llm/transport-timeout? throwable))

(defn bounded-call
  "Invoke f within timeout-ms. Returns a tagged result and interrupts the worker
   on timeout. Kept small so deadline composition can be tested deterministically."
  ([timeout-ms f]
   (bounded-call timeout-ms f nil))
  ([timeout-ms f on-timeout!]
   (if (and (number? timeout-ms) (<= timeout-ms 0))
     (do
       (when on-timeout! (on-timeout!))
       {:timeout? true})
     (let [worker (future (try {:value (f)}
                               (catch Throwable t {:throwable t})))]
       (try
         (let [result (if (number? timeout-ms)
                        (deref worker timeout-ms :ai.obney.orc.orc-service.core.executor/timeout)
                        @worker)]
           (if (= :ai.obney.orc.orc-service.core.executor/timeout result)
             (do
               (when on-timeout! (on-timeout!))
               (future-cancel worker)
               {:timeout? true})
             result))
         (catch InterruptedException interrupted
           (when on-timeout! (on-timeout!))
           (future-cancel worker)
           (throw interrupted)))))))

(def ^:private phase2-default-budget-ms
  "Hardcoded fallback budget when no :timeout-ms is set on the repl-researcher
   node and no :timeout-ms is set in the parent tick options. Preserves the
   pre-D-003 behavior of a generous 15-minute ceiling for Phase 2."
  900000)

(defn resolve-researcher-campaign-timing
  "Establish the immutable absolute timing facts for one researcher campaign.

   The same resolver is used before checkpointed classification and inside the
   executor so preparation cannot live outside a second, later-created clock.
   A durable checkpoint always wins; otherwise the existing campaign timeout
   precedence is preserved exactly.  The enclosing workflow deadline remains
   independent and is composed at each operation boundary rather than folded
   into the campaign fact."
  [{:keys [node checkpoint started-at-ms parent-timeout-ms]}]
  (let [timeout-config (or (get-in node [:rlm :timeouts]) {})
        campaign-started-at-ms (or (:campaign-started-at-ms checkpoint)
                                   started-at-ms)
        campaign-timeout-ms (or (:campaign-ms timeout-config)
                                (:timeout-ms node)
                                parent-timeout-ms
                                phase2-default-budget-ms)
        campaign-deadline-ms (or (:campaign-deadline-ms checkpoint)
                                 (+ campaign-started-at-ms
                                    campaign-timeout-ms))]
    {:campaign-started-at-ms campaign-started-at-ms
     :campaign-deadline-ms campaign-deadline-ms
     :campaign-timeout-ms campaign-timeout-ms}))

(defn resolve-phase2-budget
  "Resolve the budget that Phase 2 (tree execution) should be allowed to consume,
   given the repl-researcher node config, the parent tick's timeout (if any),
   and the wall-time already spent in Phase 1.

   Lookup order:
     1. (:timeout-ms node)        → :source :node
     2. :parent-timeout-ms arg    → :source :tick
     3. 900_000 ms hardcoded      → :source :hardcoded

   Returns:
     {:total-budget-ms N
      :remaining-ms N             ;; clamped to >= 0
      :source :node | :tick | :hardcoded
      :exhausted? boolean}        ;; true iff remaining <= 0"
  [{:keys [node parent-timeout-ms phase1-elapsed-ms]}]
  (let [[total source] (cond
                         (:timeout-ms node) [(:timeout-ms node) :node]
                         parent-timeout-ms  [parent-timeout-ms :tick]
                         :else              [phase2-default-budget-ms :hardcoded])
        remaining-raw (- total phase1-elapsed-ms)
        remaining (max 0 remaining-raw)]
    {:total-budget-ms total
     :remaining-ms remaining
     :source source
     :exhausted? (<= remaining-raw 0)}))

(def ^:private outputs-preview-string-limit
  "Maximum characters of a string value to surface in `:outputs-previews`.

   R-7a: this preview is a SAMPLE for the model's reasoning context — it is
   not a substitute for `(get-var :key)` or `(node-output ...)`, which still
   return the full untruncated value. Cap is intentionally generous so the
   model can spot semantic anomalies (e.g. all-zero letter counts after a
   non-empty transcription) directly from the summary."
  500)

(defn compute-output-preview
  "Build a single preview value for one entry in `:outputs-previews`.

   Shape per type:
     string → first N chars + overflow marker when len > N (verbatim
              content otherwise)
     vector/seq/list → {:count N :sample-3 [first three pr-str'd values]}
     map → {:keys [sorted-keys] :sample-3 [[k v-preview] ...]}
     scalar (number, boolean, keyword, nil, other) → pr-str
   "
  [v]
  (cond
    (string? v)
    (let [n (count v)]
      (if (<= n outputs-preview-string-limit)
        v
        (str (subs v 0 outputs-preview-string-limit)
             "…(truncated, full " n " chars)")))

    (or (vector? v) (seq? v) (list? v))
    (let [items (vec (take 3 v))]
      {:count (count v)
       :sample-3 (mapv #(let [s (pr-str %)]
                          (if (<= (count s) 200) s (str (subs s 0 200) "…")))
                       items)})

    (map? v)
    (let [ks (sort-by pr-str (keys v))
          ks-sample (take 3 ks)]
      {:keys (vec ks)
       :sample-3 (mapv (fn [k]
                         (let [vp (compute-output-preview (get v k))]
                           [k vp]))
                       ks-sample)})

    :else
    (pr-str v)))

(defn detect-nil-writes
  "Return the subset of :writes-declared whose value in :outputs is nil or empty.

   C-Loop-4 soft signal: a tree can return :status :success while some declared
   writes are nil / empty-collection (the risk-analysis broken-aggregator case).
   The model on the next iteration needs to SEE this so it can decide whether
   to recover or whether 'empty' is the correct answer for the task. This
   helper is a pure detector; it does NOT fail the run.

   'Empty' means: nil, \"\", [], (), {}, #{}.
   'Empty' does NOT mean: 0 (numeric zero), false (boolean) — both can be real
   answers a task is asking for.

   Declared keys absent from :outputs entirely are treated as nil — the model
   contracted to produce them and didn't."
  [{:keys [outputs writes-declared]}]
  (vec
    (for [k writes-declared
          :let [v (get outputs k)]
          :when (or (nil? v)
                    (and (or (string? v)
                             (coll? v))
                         (empty? v)))]
      k)))

(def researcher-control-keys
  #{:tree-results :generated-tree :generated-tree-raw :generated-tree-source
    :iteration-reasonings :preflight-required-schema-keys})

(defn scoped-child-node? [nodes node-id]
  (loop [parent-id (:parent-id (get nodes node-id)) seen #{}]
    (when (and parent-id (not (contains? seen parent-id)))
      (let [parent (get nodes parent-id)]
        (or (= :map-each (:type parent))
            (recur (:parent-id parent) (conj seen parent-id)))))))

(defn child-declared-writes [nodes]
  (vec (distinct
         (remove nil?
           (mapcat (fn [[id node]]
                     (when-not (scoped-child-node? nodes id)
                       (concat (:writes node)
                               (when (= :map-each (:type node)) [(:output-key node)]))))
                   nodes)))))

(defn compute-tree-result-summary
  "Build the lightweight :tree-results summary entry for a Phase 2 tree execution.

   Inputs:
     {:phase2-result <result from tree-executor/execute-tree>
      :tick-events   <vec of events tagged with [:tick child-tick-id]>
      :tree-raw      <S-expr the model wrote — from sandbox-vars :generated-tree-raw>
      :writes        [<node :writes declared keys>]}

   Returns a map shaped per the R-1 PRD (factual fields only — no prose,
   no severity, no retry hints, no full trajectory).

   Conditional fields:
     - :failure-indices + :failure-reasons appear only when :status is :partial
       or :failure (derived from D-008's :partial-summary in the map-each's
       :sheet/node-execution-completed event).
     - :phase2-elapsed-ms + :budget-remaining-ms + :nodes-completed-before-cancel
       appear only when :status is :timeout (from D-003's response shape)."
  [{:keys [phase2-result tick-events tree-raw writes]}]
  (let [node-completions (filter #(= :sheet/node-execution-completed (:event/type %))
                                 tick-events)
        partial-summaries (keep :partial-summary node-completions)
        leaf-completions (remove :partial-summary
                                 (filter #(contains? #{:success :failure} (:status %))
                                         node-completions))
        succeeded (count (filter #(= :success (:status %)) leaf-completions))
        failed (count (filter #(= :failure (:status %)) leaf-completions))
        total (count leaf-completions)
        writes-set (set writes)
        outputs-keys (vec (filter writes-set (keys (:outputs phase2-result))))
        outputs-previews (into {}
                               (map (fn [k] [k (compute-output-preview
                                                 (get (:outputs phase2-result) k))]))
                               outputs-keys)
        nil-writes (detect-nil-writes {:outputs (:outputs phase2-result)
                                       :writes-declared writes})
        status (:status phase2-result)
        all-failure-indices (vec (mapcat :failure-indices partial-summaries))
        all-failure-reasons (apply merge {} (map :failure-reasons partial-summaries))
        all-leaf-failures (tree-failures-from-events tick-events)
        direct-leaf-failures (vec (filter :node-id all-leaf-failures))]
    (cond-> {:tick-id (:trace-id phase2-result)
             :tree-raw tree-raw
             :status status
             :elapsed-ms (:duration-ms phase2-result)
             :outputs-keys outputs-keys
             :outputs-previews outputs-previews
             :missing-writes (vec (remove #(contains? (:outputs phase2-result) %) writes))
             :present-empty-writes (vec (filter #(contains? (:outputs phase2-result) %) nil-writes))
             :nodes-succeeded succeeded
             :nodes-failed failed
             :nodes-total total
             :usage (:usage phase2-result)}
      (seq nil-writes)
      (assoc :nil-writes nil-writes)

      (and (contains? #{:partial :failure} status) (seq partial-summaries))
      (assoc :failure-indices all-failure-indices
             :failure-reasons all-failure-reasons)

      (seq direct-leaf-failures)
      (assoc :failed-leaves direct-leaf-failures)

      (= :timeout status)
      (assoc :phase2-elapsed-ms (:phase2-elapsed-ms phase2-result)
             :budget-remaining-ms (:budget-remaining-ms phase2-result)
             :nodes-completed-before-cancel (count leaf-completions))

      (:error phase2-result)
      (assoc :error (:error phase2-result))

      (:admission-rejected? phase2-result)
      (assoc :admission-rejected? true)

      (:setup-diagnostic phase2-result)
      (assoc :setup-diagnostic (:setup-diagnostic phase2-result))

      (:preflight-rejected? phase2-result)
      (assoc :preflight-rejected? true))))

(defn merge-tree-result-into-sandbox
  "Return new sandbox-vars after a Phase 2 tree execution.

   - Merges Phase 2's :writes-declared output keys into sandbox-vars (NOT input
     blackboard keys, even though they appear in phase2-result :outputs).
   - Appends the summary entry to :tree-results (existing vector preserved;
     nil → []).
   - Dissoc's :generated-tree (the canonical dispatch marker) so the dispatch
     doesn't re-fire on the same tree on the next iteration. PRESERVES
     :generated-tree-raw so the final! return path (and downstream consumers
     like benchmark EDN capture) can record WHAT the model actually designed."
  [sandbox-vars phase2-result writes summary]
  (let [writes-set (set writes)
        outputs-to-merge (into {}
                               (filter (comp some? val))
                               (select-keys (:outputs phase2-result) writes-set))
        prior-results (get sandbox-vars :tree-results [])]
    (-> sandbox-vars
        (dissoc :generated-tree)
        (merge outputs-to-merge)
        (assoc :tree-results (conj prior-results summary)))))

(defn surviving-vars-from-events
  "Successful intermediate blackboard writes inside a Phase 2 tree.

   A failed tree usually contains nodes that SUCCEEDED before the failure
   (e.g. 12 batch extractions feeding a synthesis node that died). Their
   writes land as :sheet/execution-value-written events but were never
   merged into sandbox-vars — so recovery trees re-paid for work that was
   already done. This collects those writes so the recursive recur can
   merge them.

   exclude-keys: the tree's declared output writes (merged separately by
   merge-tree-result-into-sandbox), the researcher's own input reads, and
   reserved sandbox keys. Later writes of the same key win."
  [tick-events exclude-keys]
  (let [excluded (set exclude-keys)]
    (into {}
          (comp (filter #(= :sheet/execution-value-written (:event/type %)))
                (map (juxt :key :value))
                (remove (fn [[k _]] (contains? excluded k))))
          tick-events)))

(defn render-tree-outcome
  "Render a :tree-results summary entry into the compact text block that is
   PUSHED into the next iteration's history (stored as :tree-outcome on the
   iteration entry).

   This is the push channel for tree results. Before it existed, the only
   outcome signal in history was :vars-created — key-presence-based and
   nil-blind — so a tree could write nils under :status :success and the
   model's default view said the data existed. Status, nil-writes,
   failed-leaf errors (which carry raw-response previews), and surviving
   vars now reach the model without requiring a (get-var :tree-results)
   probe.

   Excludes :tree-raw — the model already sees its own emit-tree! code in
   the same history entry."
  [summary]
  (let [{:keys [status elapsed-ms nodes-succeeded nodes-failed nodes-total
                outputs-keys outputs-previews nil-writes failed-leaves
                failure-indices failure-reasons surviving-vars
                preflight-rejected? admission-rejected? error missing-writes present-empty-writes
                source-iteration action-id tick-id scoped-outputs protected-writes]} summary
        preview-str (fn [k]
                      (let [p (get outputs-previews k)]
                        (if (string? p) p (pr-str p))))
        nil-set (set nil-writes)
        ok-keys (vec outputs-keys)]
    (cond
      preflight-rejected?
      (str "TREE REJECTED BEFORE EXECUTION — no child tick or leaf ran. "
           error
           " Correct the reported setup error before re-emitting the intended "
           "tree. Reuse existing declared contracts; declare specific schemas "
           "for new computed keys. No child result was produced.")
      admission-rejected?
      (str "CHILD ADMISSION REJECTED BEFORE EXECUTION — no child tick or leaf ran. "
           error
           " The refusal occurred before child code could change its inputs. "
           "No child result was produced.")
      :else
      (str "Tree executed — status: " status
         " | child tick: " tick-id
         (when action-id (str " | action: " action-id))
         (when (some? source-iteration) (str " | source iteration: " source-iteration))
         " | nodes: " nodes-succeeded "/" nodes-total " succeeded"
         (when (and nodes-failed (pos? nodes-failed))
           (str ", " nodes-failed " failed"))
         (when elapsed-ms (str " | " elapsed-ms " ms"))
         (when error (str "\nExecution error: " error))
         (when (seq ok-keys)
           (str "\nOutputs merged (readable via get-var):\n"
                (str/join "\n" (map (fn [k] (str "  " k " = " (preview-str k)))
                                    ok-keys))))
         (when (seq missing-writes)
           (str "\nMissing child writes (no completed value): " (pr-str missing-writes)))
         (when (seq present-empty-writes)
           (str "\nPresent null/empty child writes: " (pr-str present-empty-writes)
                " — completed values, not absence. Assess their meaning for the task."))
         (when (and (seq nil-writes) (nil? missing-writes))
           (str "\nNIL/EMPTY WRITES: " (str/join ", " (map str nil-writes))
                " — legacy summary combines missing and present empty values; inspect producer evidence."))
         (when (seq scoped-outputs)
           (str "\nPer-item completed outputs (previews; inspect by owner, not global get-var):\n"
                (str/join "\n" (map pr-str scoped-outputs))))
         (when (seq protected-writes)
           (str "\nChild writes excluded from researcher control state: " (pr-str protected-writes)))
         (when (seq failed-leaves)
           (str "\nFailed leaves:\n"
                (str/join "\n" (map (fn [{:keys [node-id error]}]
                                      (str "  - node " node-id ": " error))
                                    failed-leaves))))
         (when (seq failure-indices)
           (str "\nMap-each failures at indices " (vec failure-indices)
                (when (seq failure-reasons)
                  (str ":\n"
                       (str/join "\n" (map (fn [[i r]] (str "  " i " → " r))
                                           failure-reasons))))))
         (when (seq surviving-vars)
           (str "\nSurviving intermediate vars (successful work inside this"
                " tree, readable via get-var — do NOT recompute): "
           (str/join ", " (map str surviving-vars))))))))

;; =============================================================================
;; Levenshtein Distance and Variable Suggestions
;; =============================================================================

(defn levenshtein-distance
  "Calculate the Levenshtein (edit) distance between two strings."
  [s1 s2]
  (let [len1 (count s1)
        len2 (count s2)]
    (cond
      (zero? len1) len2
      (zero? len2) len1
      :else
      (let [matrix (make-array Long/TYPE (inc len1) (inc len2))]
        (doseq [i (range (inc len1))]
          (aset matrix i 0 (long i)))
        (doseq [j (range (inc len2))]
          (aset matrix 0 j (long j)))
        (doseq [i (range 1 (inc len1))
                j (range 1 (inc len2))]
          (let [cost (if (= (nth s1 (dec i)) (nth s2 (dec j))) 0 1)]
            (aset matrix i j
                  (long (min (inc (aget matrix (dec i) j))
                             (inc (aget matrix i (dec j)))
                             (+ (aget matrix (dec i) (dec j)) cost))))))
        (aget matrix len1 len2)))))

(defn suggest-similar-key
  "Suggest a similar key from available keys using Levenshtein distance.
   Returns the most similar key if:
   - The distance is <= half the longer string's length + 1
   - Or one is a prefix/substring of the other
   This allows 'doc' to match 'document' and 'chunk' to match 'chunks'."
  [missing-key available-keys]
  (let [missing-str (name missing-key)
        scored-keys (for [k available-keys
                         :let [k-str (name k)
                               dist (levenshtein-distance missing-str k-str)
                               max-len (max (count missing-str) (count k-str))
                               threshold (inc (quot max-len 2))
                               is-prefix? (or (str/starts-with? k-str missing-str)
                                             (str/starts-with? missing-str k-str))]
                         :when (or (<= dist threshold) is-prefix?)]
                     [k dist])
        best (first (sort-by second scored-keys))]
    (first best)))

(defn format-error-with-suggestions
  "Format an error message with helpful suggestions.
   For missing variable errors, includes available variables and suggestions."
  [error-msg available-vars]
  (let [
        missing-key-match (re-find #":(\w+)" error-msg)
        missing-key (when missing-key-match (keyword (second missing-key-match)))
        suggestion (when missing-key (suggest-similar-key missing-key available-vars))]
    (str error-msg
         (when (seq available-vars)
           (str "\nAvailable variables: " (str/join ", " (map str available-vars))))
         (when suggestion
           (str "\nDid you mean: " suggestion "?")))))

;; =============================================================================
;; Usage Normalization
;; =============================================================================

(defn usage-cost
  "The provider-reported :cost of a usage map when it is a number, else nil."
  [usage]
  (let [c (:cost usage)] (when (number? c) c)))

(defn add-cost
  "Add `usage`'s :cost (when present) onto the accumulator's :cost."
  [acc usage]
  (if-let [c (usage-cost usage)]
    (assoc acc :cost (+ (or (:cost acc) 0) c))
    acc))

(defn normalize-usage
  "Normalize ORC LLM/litellm usage map to kebab-case.
   Handles both snake_case (raw API) and kebab-case (already normalized) inputs."
  [usage]
  (when usage
    (cond-> {:prompt-tokens (or (:prompt-tokens usage) (:prompt_tokens usage) 0)
             :completion-tokens (or (:completion-tokens usage) (:completion_tokens usage) 0)
             :total-tokens (or (:total-tokens usage) (:total_tokens usage) 0)}
      (number? (:cost usage)) (assoc :cost (:cost usage)))))

;; =============================================================================
;; Schema Description Generation
;; =============================================================================

(defn- provider-enum-spelling
  "Render an enum choice as the provider must spell it in JSON.

   JSON has no keyword literal syntax. Malli's JSON transformer decodes
   `\"changed\"` to :changed, while the EDN spelling `\":changed\"` is a
   different string and correctly remains invalid. Preserve namespaces so
   :decision/changed is presented as `decision/changed`."
  [value]
  (if (keyword? value)
    (if-let [keyword-namespace (namespace value)]
      (str keyword-namespace "/" (name value))
      (name value))
    (str value)))

(defn malli-schema->description
  "Generate a human-readable description from a Malli schema for AI context.
   This helps the AI understand what structure to produce.

   Handles Malli schemas with optional properties maps:
     :string                               -> \"string\"
     [:string {:description \"...\"}]      -> \"string\"
     [:map [:field :type]]                 -> \"object with {...}\""
  [schema]
  (cond
    (keyword? schema)
    (case schema
      :string "string"
      :int "integer"
      :double "number"
      :number "number"
      :boolean "boolean (true/false)"
      :any "any value"
      :uuid "UUID string"
      (name schema))

    (vector? schema)
    (let [[schema-type & args] schema
          args (if (and (seq args) (map? (first args)))
                 (rest args)
                 args)]
      (case schema-type
        :map
        (let [fields (filter vector? args)
              field-descs (for [field fields]
                            (let [[field-key & rest] field
                                  opts (when (map? (first rest)) (first rest))
                                  field-schema (if opts (second rest) (first rest))
                                  optional? (:optional opts)]
                              (str (name field-key)
                                   (when optional? " (optional)")
                                   ": " (malli-schema->description field-schema))))]
          (str "object with {" (str/join ", " field-descs) "}"))

        :vector
        (str "list of " (malli-schema->description (first args)))

        :enum
        (str "one of: "
             (str/join ", " (map provider-enum-spelling args)))

        :maybe
        (str (malli-schema->description (first args)) " or null")

        :or
        (str "either " (str/join " or " (map malli-schema->description args)))

        :map-of
        (let [[key-schema val-schema] args]
          (str "JSON object with " (malli-schema->description key-schema)
               " keys and " (malli-schema->description val-schema) " values"))

        (if (and (keyword? schema-type) (empty? args))
          (malli-schema->description schema-type)
          (str schema-type " " (str/join " " (map malli-schema->description args))))))

    :else (pr-str schema)))

(defn- sanitize-field-name
  "Sanitize field name for ORC LLM - remove ? and other problematic chars"
  [key-name]
  (-> key-name
      (str/replace "?" "")
      (str/replace "!" "")
      (str/replace #"[^a-zA-Z0-9_-]" "_")))

(defn- extract-schema-description
  "Extract :description from Malli schema properties if present.

   Malli schemas with properties look like:
     [:string {:description \"The question to answer\"}]
     [:map {:description \"A map of...\"} [:field :type]]

   A recursive schema is persisted as `[:schema {:registry ..} <root>]`; the
   registry wrapper carries no description of its own, so the root's
   description is the key's description.

   Returns the description string or nil if not present."
  [schema]
  (when (vector? schema)
    (or (when (and (> (count schema) 1) (map? (second schema)))
          (:description (second schema)))
        (when (and (= :schema (first schema)) (> (count schema) 2))
          (extract-schema-description (last schema))))))

(defn- map-schema?
  "Check if a schema is a Malli :map schema that should be flattened."
  [schema]
  (and (vector? schema) (= :map (first schema))))

(defn- map-of-schema?
  "Check if a schema is a Malli :map-of schema (dynamic keys, can't be flattened)."
  [schema]
  (and (vector? schema) (= :map-of (first schema))))

(defn- flatten-output-schema
  "Flatten a nested :map schema into separate output fields.

   Given blackboard key 'academic-score' with schema:
     [:map [:score :double] [:reasoning :string] [:keyFactors [:vector :string]]]

   Returns vector of flattened fields:
     [{:name :score :original-key 'academic-score' :nested-key 'score' :spec :double :description ...}
      {:name :reasoning :original-key 'academic-score' :nested-key 'reasoning' :spec :string ...}
      {:name :keyFactors :original-key 'academic-score' :nested-key 'keyFactors' :spec [:vector :string] ...}]

   This matches Python DSPy's approach of having separate output fields.

   Supports custom :description in Malli field options:
     [:map
      [:score [:double {:description \"Academic fit score from 0.0 to 1.0\"}]]
      [:reasoning [:string {:description \"Detailed explanation\"}]]]"
  [key-name schema]
  (if (and (map-schema? schema)
           (or (seq (filter vector? (rest schema)))
               (not (true? (:closed (second schema))))))
    (let [fields (filter vector? (rest schema))]
      (vec
       (for [[field-key & rest] fields
             :let [opts (when (map? (first rest)) (first rest))
                   field-spec (if opts (second rest) (first rest))
                   field-name (name field-key)
                   custom-desc (or (:description opts)
                                   (extract-schema-description field-spec))
                   strict-desc (str/join "\n" (distinct (remove str/blank?
                                              [(:description opts)
                                               (extract-schema-description field-spec)])))
                   type-desc (malli-schema->description field-spec)
                   description (if custom-desc
                                 (str custom-desc " (" type-desc ")")
                                 (str field-name " - " type-desc))]]
         {:name (keyword field-name)
          :original-key key-name
          :nested-key field-name
          :original-nested-key field-key
          :string-key? (string? field-key)
          :spec field-spec
          :optional (true? (:optional opts))
          :orc/strict-description (not-empty strict-desc)
          :description description})))
    (if (map-of-schema? schema)
      (let [custom-desc (extract-schema-description schema)
            type-desc (malli-schema->description schema)]
        [{:name key-name
          :original-key key-name
          :nested-key nil
          :spec schema
          :orc/strict-description custom-desc
          :description (if custom-desc
                         (str custom-desc " (" type-desc ")")
                         (str key-name " - " type-desc))}])
      (let [custom-desc (extract-schema-description schema)
            type-desc (malli-schema->description schema)]
        [{:name key-name
          :original-key key-name
          :nested-key nil
          :spec schema
          :orc/strict-description custom-desc
          :description (if custom-desc
                         (str custom-desc " (" type-desc ")")
                         (str "Output: " key-name " - " type-desc))}]))))

(defn reassemble-flattened-outputs
  "Reassemble flattened outputs back into nested structure for blackboard.

   Given ORC LLM outputs:
     {:score 0.85 :reasoning '...' :keyFactors [...]}

   And output-mapping:
     {:score {:original-key 'academic-score' :nested-key 'score'}
      :reasoning {:original-key 'academic-score' :nested-key 'reasoning'}
      ...}

   Returns:
     {'academic-score' {:score 0.85 :reasoning '...' :keyFactors [...]}}"
  ([raw-outputs output-mapping]
   (reassemble-flattened-outputs raw-outputs output-mapping false))
  ([raw-outputs output-mapping complete?]
  (reduce-kv
   (fn [acc output-key output-value]
     (if-let [mapping (get output-mapping output-key)]
       (let [original-key (:original-key mapping)
             nested-key (:nested-key mapping)
             optional-null? (and (:optional mapping)
                                 (nil? output-value)
                                 (not (try
                                        (m/validate (:spec mapping) nil)
                                        (catch Exception _ false))))]
         (cond
           optional-null?
           acc

           nested-key
           (update acc original-key assoc
                   (if (contains? mapping :original-nested-key)
                     (:original-nested-key mapping)
                     (if (:string-key? mapping) nested-key (keyword nested-key)))
                   output-value)

           :else
           (assoc acc original-key output-value)))
       (assoc acc output-key output-value)))
   (if (and complete? (map? raw-outputs))
     (into {} (keep (fn [[parent fields]]
                      (when (every? :optional fields) [parent {}])))
           (group-by :original-key (filter :nested-key (vals output-mapping))))
     {})
   raw-outputs)))

(defn- executor-schema-field-type
  "Extract :field-type from a Malli schema's properties, if present.
   E.g., [:vector {:field-type :image} :string] → :image"
  [schema]
  (when (and schema (vector? schema))
    (try
      (:field-type (m/properties schema))
      (catch Exception _ nil))))

(defn- build-field
  "Build a ORC LLM INPUT field definition from a blackboard key and its entry.

   The field's :description is its declared meaning and nothing else: the
   :description property of its Malli schema (e.g. [:string {:description \"...\"}]),
   or no description at all when the schema declares none. The input field guide
   states each shown field's name and type itself, and its nested fields only
   where the request carries them, so a description that also spelled out the
   declared type (or invented \"Blackboard key: ...\" text) would name nested
   fields the request does not carry. Output fields are built elsewhere
   (flatten-output-schema) and keep their type text.

   If the Malli schema has a :field-type property (e.g., [:vector {:field-type :image} :string]),
   it will be set as :type on the ORC LLM field definition, enabling multimodal support."
  [key-name blackboard-entry]
  (let [schema (:schema blackboard-entry)
        field-type (executor-schema-field-type schema)
        description (extract-schema-description schema)]
    (cond-> {:name key-name
             :original-key key-name
             :spec (or schema :any)}
      description (assoc :description description)
      field-type (assoc :type field-type))))

;; =============================================================================
;; Module Builder
;; =============================================================================

(defn- preserve-output-identities
  "Assign deterministic wire aliases only where flattened names collide.
   Original typed field keys remain in the reassembly mapping."
  [outputs]
  (let [wire-name (fn [field] (name (:name field)))
        counts (frequencies (map wire-name outputs))
        used (atom (set (map wire-name outputs)))
        next-id (atom -1)]
    (mapv (fn [field]
            (if (> (get counts (wire-name field)) 1)
              (let [alias (loop []
                            (let [candidate (str "__orc_field_" (swap! next-id inc))]
                              (if (contains? @used candidate) (recur) candidate)))]
                (swap! used conj alias)
                (let [identity-description
                      (str " Original output path: "
                           (pr-str (cond-> [(:original-key field)]
                                     (contains? field :original-nested-key)
                                     (conj (:original-nested-key field)))) ".")]
                  (-> field
                      (assoc :name (keyword alias))
                      (update :description str identity-description)
                      (update :orc/strict-description str identity-description))))
              field)) outputs)))

(defn build-module
  "Build a ORC LLM module from a leaf node and blackboard metadata.

   Args:
     node - The leaf node map with :instruction, :reads, :writes
     blackboard - Map of key -> {:key, :type, :value, :version}

   Returns a ORC LLM module map with :inputs, :outputs, :instructions
   and :output-mapping for converting flattened outputs back to nested structure.

   OUTPUT FLATTENING (Python DSPy Alignment):
   When an output has a :map schema, we flatten it into separate fields.
   E.g., 'academic-score' with schema [:map [:score :double] [:reasoning :string]]
   becomes separate fields: 'score', 'reasoning' - matching how Python DSPy works."
  [node blackboard]
  (let [missing-reads (->> (:reads node)
                           (remove #(some? (get-in blackboard [% :schema])))
                           vec)
        missing-writes (->> (:writes node)
                            (remove #(some? (get-in blackboard [% :schema])))
                            vec)
        _ (when (or (seq missing-reads) (seq missing-writes))
            (throw
             (ex-info
              (str "LLM node '" (:name node)
                   "' is missing blackboard schemas"
                   (when (seq missing-reads) (str " for reads " missing-reads))
                   (when (seq missing-writes) (str " for writes " missing-writes)))
              {:node-name (:name node)
               :missing-reads missing-reads
               :missing-writes missing-writes
               :blackboard-keys (vec (sort (keys blackboard)))})))
        inputs (mapv (fn [key-name]
                       (build-field key-name (get blackboard key-name)))
                     (:reads node))
        outputs (->> (:writes node)
                     (mapcat (fn [key-name]
                               (flatten-output-schema
                                key-name
                                (get-in blackboard [key-name :schema]))))
                     vec
                     preserve-output-identities)
        output-groups
        (into []
              (keep (fn [key-name]
                      (let [fields (filter #(and (= key-name (:original-key %))
                                                 (contains? % :original-nested-key)) outputs)
                            description (extract-schema-description
                                         (get-in blackboard [key-name :schema]))]
                        (when (and (seq fields) (not (str/blank? description)))
                          {:path [key-name]
                           :description description
                           :fields (mapv (comp name :name) fields)}))))
              (:writes node))
        _ (when (some #(map-of-schema? (:spec %)) outputs)
            (println "[WARN] Node" (:name node) "uses [:map-of ...] schema for LLM output."
                     "Consider using explicit [:map [:field :type] ...] for better reliability."))
        output-mapping (into {}
                             (map (fn [o]
                                    [(:name o)
                                     {:original-key (:original-key o)
                                      :nested-key (:nested-key o)
                                      :original-nested-key (:original-nested-key o)
                                      :string-key? (:string-key? o)
                                      :optional (:optional o)
                                      :spec (:spec o)}])
                                  outputs))]
    {:inputs inputs
     :outputs outputs
     :orc/output-groups output-groups
     :instructions (or (:instruction node) "Execute this task.")
     :output-mapping output-mapping}))

(defn serialize-for-llm
  "Serialize a value for LLM consumption.
   Complex values (maps, vectors) are serialized as JSON.
   Simple values (strings, numbers, booleans) are passed as-is."
  [value]
  (cond
    (nil? value) ""
    (map? value) (json/generate-string value)
    (vector? value) (json/generate-string value)
    (coll? value) (json/generate-string (vec value))
    :else value))

(defn gather-inputs
  "Gather input values from the blackboard for the node's reads.

   Args:
     node - The leaf node with :reads
     blackboard - Map of key -> {:key, :schema, :value, :version}

   Returns native values for ORC LLM, including present nil. A declared entry
   with no :value is absent. Shared request rendering serializes text inputs;
   field-type inputs (e.g. images) retain their native content-part values."
  [node blackboard]
  (reduce (fn [acc key-name]
            (if (contains? (get blackboard key-name) :value)
              (assoc acc key-name (get-in blackboard [key-name :value]))
              acc))
          {}
          (:reads node)))

(defn inject-sub-model
  "Walk a canonical-DSL emit-tree! tree and inject :model sub-model into each
   (sheet/llm ...) form that does not already specify :model.

   No-op when sub-model is nil (single-model setup).

   Used by the Phase-2 dispatch in execute-repl-researcher-rlm to route
   sub-LLM calls through a different model than the Phase-1 researcher
   (e.g. main-LM gpt-5.4 for tree-design + sub-LM gpt-5.1-chat for the
   actual leaf executions, matching predict-rlm's apples-to-apples setup).

   :llm forms with an explicit :model are left untouched."
  [tree sub-model]
  (if (nil? sub-model)
    tree
    (walk/postwalk
      (fn [node]
        (if (and (seq? node)
                 (= 'sheet/llm (first node))
                 (let [opts (try (apply hash-map (rest node)) (catch Exception _ nil))]
                   (and opts (not (contains? opts :model)))))
          (concat node [:model sub-model])
          node))
      tree)))

(defn inject-subcall-config [tree defaults]
  (walk/postwalk
   (fn [form]
     (if (and (seq? form) (= 'sheet/llm (first form)))
       (let [opts (apply hash-map (rest form))
             effective (inherit defaults opts)]
         (apply list 'sheet/llm (mapcat identity effective)))
       form)) tree))

(defn inject-tool-caller-fn
  "Phase 4B: Walk a canonical-DSL emit-tree! tree and inject the parent
   repl-researcher's `:tool-caller-fn` (gated tool-caller builder FQN) into
   each (sheet/code ...) form, overriding any gate the model declared; the
   researcher's contracts are merged over any model-declared ones.

   No-op when tool-caller-fn is nil (no parent gate) — backwards compatible,
   the child code nodes use the static (:call-tool-fn context) unchanged.

   This is the async-safe half of the inheritance model: the parent RLM
   node's gated caller is a closure (built from :tool-context) that CANNOT
   thread across the child-tick command boundary. Instead we stamp the
   BUILDER FQN onto the generated code nodes so the child tick rebuilds the
   gated caller from its own blackboard :tool-context (plain data, which
   does survive the boundary) — exactly how the parent node builds it."
  ([tree tool-caller-fn]
   (inject-tool-caller-fn tree tool-caller-fn nil))
  ([tree tool-caller-fn tool-contracts]
   (if (and (nil? tool-caller-fn) (empty? tool-contracts))
     tree
     (walk/postwalk
       (fn [node]
         (if (and (seq? node)
                  (= 'sheet/code (first node)))
           (let [opts (try (apply hash-map (rest node)) (catch Exception _ nil))]
             (if opts
               (let [opts' (cond-> opts
                             tool-caller-fn (assoc :tool-caller-fn tool-caller-fn)
                             (seq tool-contracts)
                             (assoc :tool-contracts
                                    (merge (when (map? (:tool-contracts opts))
                                             (:tool-contracts opts))
                                           tool-contracts)))]
                 (if (= opts opts')
                   node
                   (apply list 'sheet/code (mapcat identity opts'))))
               node))
           node))
       tree))))

;; =============================================================================
;; Code Executor
;; =============================================================================

(defonce ^:private application-classloader
  (.getContextClassLoader (Thread/currentThread)))

(defn- resolve-qualified-var
  [qualified-sym classloader]
  (let [thread (Thread/currentThread)
        previous (.getContextClassLoader thread)]
    (try
      (.setContextClassLoader thread classloader)
      (try
        (let [application-ns (symbol (namespace qualified-sym))
              function-name (symbol (name qualified-sym))]
          (or (some-> (find-ns application-ns) (ns-resolve function-name))
              (integrations/code-function qualified-sym)
              (do (require application-ns)
                  (ns-resolve application-ns function-name))))
        (catch Exception _ nil))
      (finally
        (.setContextClassLoader thread previous)))))

(defn resolve-fn
  "Resolve a trusted, fully-qualified JVM function symbol string to a function.
   Consumer applications may reference functions from their own classpath
   namespaces; model-generated code is constrained separately by SCI.
   Also supports ephemeral functions registered via tree-executor for Phase 2.
   Returns {:fn f} on success or {:error msg} on failure."
  [fn-symbol-str]
  (cond
    (str/starts-with? fn-symbol-str "ephemeral-fn-")
    (if-let [f (lookup-ephemeral-fn fn-symbol-str)]
      {:fn f}
      {:error (str "Ephemeral function not found: " fn-symbol-str)})

    :else
    (try
      (let [qualified-sym (symbol fn-symbol-str)
            ns-sym (some-> qualified-sym namespace symbol)
            fn-sym (some-> qualified-sym name symbol)
            thread-loader (.getContextClassLoader (Thread/currentThread))
            resolved (when (and ns-sym fn-sym)
                       (or (resolve-qualified-var qualified-sym thread-loader)
                           (when-not (identical? thread-loader application-classloader)
                             (resolve-qualified-var qualified-sym application-classloader))
                           (when-let [ns-obj (find-ns ns-sym)]
                             (ns-resolve ns-obj fn-sym))))]
        (if resolved
          {:fn (if (var? resolved) @resolved resolved)}
          {:error (str (if (find-ns ns-sym)
                         "Function not found: "
                         "Failed to resolve function: ")
                       fn-symbol-str
                       " (namespace-loaded=" (boolean (find-ns ns-sym))
                       ", thread=" (.getName (Thread/currentThread)) ")")}))
      (catch Exception e
        {:error (str "Failed to resolve function: " fn-symbol-str " - " (.getMessage e))}))))

(defn node-call-tool-fn
  "Resolve the tool-caller a repl-researcher node should use.

   Leaf tool invocation is context-aware but policy-agnostic: a node may
   declare `:tool-caller-fn \"fully.qualified/builder\"`, an opt-in hook
   resolved like a code-node `:fn`. When present, the builder is invoked
   as `(builder blackboard context)` and must return the `call-tool-fn`
   used to build this node's tool sandbox — letting the consumer thread
   per-execution context (identity, consent, confinement, routing) into
   how the node's tools behave. When ABSENT, the static
   `(:call-tool-fn context)` is used exactly as before — so existing
   nodes are unaffected. orc knows nothing about what the builder does;
   it only resolves and calls it."
  [node blackboard context]
  (if-let [builder-sym (:tool-caller-fn node)]
    (let [{builder :fn resolve-error :error} (resolve-fn builder-sym)]
      (when-not builder
        (throw (ex-info (str "Configured tool-caller builder could not be resolved: "
                             builder-sym " (" resolve-error ")")
                        {:tool-caller-fn builder-sym})))
      (let [caller (builder blackboard context)]
        (when-not (fn? caller)
          (throw (ex-info (str "Configured tool-caller builder did not return a function: "
                               builder-sym)
                          {:tool-caller-fn builder-sym
                           :returned-type (some-> caller type str)})))
        caller))
    (:call-tool-fn context)))

(defn- supports-tool-caller-arity?
  "True when call-tool-fn can be invoked with arity without probing it.

   Fixed-arity Clojure functions declare their supported invoke methods on the
   generated function class. RestFn instances accept every arity at or above
   their required arity. Inspecting those shapes avoids using a real tool call
   as an arity probe (which could dispatch an effect before failing)."
  [call-tool-fn arity]
  (and (ifn? call-tool-fn)
       (or (some (fn [^java.lang.reflect.Method method]
                   (and (= "invoke" (.getName method))
                        (= arity (alength (.getParameterTypes method)))))
                 (.getDeclaredMethods (class call-tool-fn)))
           (and (instance? clojure.lang.RestFn call-tool-fn)
                (>= arity
                    (.getRequiredArity ^clojure.lang.RestFn call-tool-fn))))))

(defn tool-caller-target
  "Return the callable whose arity and effect checkpointing will use.

   Vars expose generic IFn invoke methods for every arity even when their bound
   function supports only one. Dereference a bound Var once so preflight and
   dispatch share the same real contract; leave ordinary callers unchanged."
  [call-tool-fn]
  (if (var? call-tool-fn)
    (when (bound? call-tool-fn) @call-tool-fn)
    call-tool-fn))

(defn tool-caller-arity
  "Classify the host caller contract without invoking the caller.

   Prefer the context-aware three-argument contract when both are supported.
   Recognising the legacy two-argument shape lets checkpointed execution reject
   it with an actionable error before any model or effect dispatch."
  [call-tool-fn]
  (cond
    (supports-tool-caller-arity? call-tool-fn 3) 3
    (supports-tool-caller-arity? call-tool-fn 2) 2
    :else nil))

(def tool-caller-arity-error
  (str "Checkpointed effectful tool caller must accept the context-aware "
       "3-argument contract (tool-name, args, tool-context) so ORC can supply "
       "the stable idempotency key"))

(defn dispatch-claimed-tool-effect!
  "The one claim -> dispatch -> validate -> complete sequence for a
   checkpoint-safe tool call (LeafExecutor/CheckpointedResearcherExecution
   EffectsAreClaimedBeforeTheyHappen). Both the inline researcher wrapper and
   the generated-child wrapper run through here.

   The claim is taken (when a claim capability exists) BEFORE the tool is
   called; a claim anomaly throws. The tool's result must honour its declared
   result contract before it is recorded; the durable completion is then
   written under the same attempt identity, and an anomaly on completion
   (ownership lost) throws. `wrap-errors?` turns a tool exception into the
   safe :tool-error outcome (the model-facing researcher behaviour); a code
   leaf passes false and sees the tool's own exception.

   Returns {:result r :durable-result encoded-r}."
  [{:keys [claim! complete! codecs contracts tool-name args action-id attempt-id
           attempt-ordinal iteration-index caller caller-arity tool-context
           wrap-errors?]}]
  (let [claim-result (when claim!
                       (claim! {:iteration-index iteration-index
                                :logical-action-identity action-id
                                :attempt-identity attempt-id
                                :attempt-ordinal attempt-ordinal
                                :kind :tool}))
        _ (when (:cognitect.anomalies/category claim-result)
            (throw (ex-info "Researcher tool claim conflicted"
                            {:claim-result claim-result
                             :logical-action-identity action-id
                             :attempt-identity attempt-id})))
        result (case caller-arity
                 3 (if wrap-errors?
                     (try
                       (caller tool-name args tool-context)
                       (catch Exception e
                         (throw (tool-error tool-name e))))
                     (caller tool-name args tool-context))
                 (throw
                  (ex-info
                   tool-caller-arity-error
                   {:expected-arities [3]})))
        _ (validate-result! contracts tool-name result)
        durable-result (encode-checkpoint-value codecs result)]
    (when complete!
      (let [completion (complete! {:logical-action-identity action-id
                                   :attempt-identity attempt-id
                                   :result durable-result})]
        (when (:cognitect.anomalies/category completion)
          (throw (ex-info "Researcher tool outcome lost ownership"
                          {:completion-result completion
                           :logical-action-identity action-id
                           :attempt-identity attempt-id})))))
    {:result result :durable-result durable-result}))

(defn- checkpointed-campaign-epoch
  "The ownership epoch of the checkpointed researcher campaign this tick
   belongs to, or nil when it belongs to none."
  [context]
  (let [epoch (get-in context [:tick-options :researcher-ownership-epoch])]
    (when (and (integer? epoch) (pos? epoch)) epoch)))

(defn- checkpoint-child-call-tool-fn
  "Wrap a generated child leaf's tool caller so each call is a campaign effect
   exactly like an inline researcher tool call
   (GeneratedChildToolCallsAreCheckpointed): the tool must be declared
   checkpoint-safe; the call has a stable logical identity under the CAMPAIGN's
   tick, node and iteration that also names the child node; it is claimed under
   the campaign's ownership epoch before it begins; it completes with a durable
   receipt. A call whose receipt is already complete returns the recorded
   result without calling the tool; a claim with no completed receipt is
   treated as an inline call's unresolved claim is (the new claim conflicts and
   the call fails).

   Identity: the child's generated code hash is not available to the child
   leaf, so the child node id stands in for it. Returns a caller with the same
   [tool args] / [tool args tool-context] arities."
  [node context caller]
  (let [tick-options (:tick-options context)
        epoch (checkpointed-campaign-epoch context)
        campaign-tick-id (:researcher-campaign-tick-id tick-options)
        campaign-node-id (:researcher-campaign-node-id tick-options)
        iteration-index (:researcher-iteration-index tick-options)
        codecs (vec (or (:researcher-checkpoint-codecs context) []))
        claim! (:claim-researcher-effect! context)
        complete! (:complete-researcher-effect! context)
        target (tool-caller-target caller)
        arity (tool-caller-arity target)
        completed (atom (reduce (fn [acc claim]
                                  (if (= :completed (:status claim))
                                    (assoc acc (:logical-action-identity claim)
                                           (update claim :result
                                                   #(decode-checkpoint-value codecs %)))
                                    acc))
                                {}
                                (or (:researcher-effect-claims context) [])))
        call (fn [tool-name args supplied-context]
               (when-not (true? (get-in (:tool-contracts node)
                                        [tool-name :checkpoint-safe?]))
                 (throw (ex-info
                         (str "Tool " tool-name
                              " is not declared :checkpoint-safe? for resumable execution")
                         {:tool tool-name :checkpoint-safe? false})))
               (when-not (and (ifn? claim!) (ifn? complete!))
                 (throw (ex-info
                         "Checkpointed campaign child has no effect claim capability"
                         {:tool tool-name})))
               (validate-arguments! (:tool-contracts node) tool-name args)
               (let [action-id (logical-action-identity
                                {:tick-id campaign-tick-id
                                 :node-id campaign-node-id
                                 :iteration-index iteration-index
                                 :child-node-id (:id node)
                                 :kind :tool
                                 :target tool-name
                                 :arguments (encode-checkpoint-value codecs args)})]
                 (if-let [receipt (get @completed action-id)]
                   (:result receipt)
                   (let [attempt-ordinal 0
                         attempt-id (attempt-identity
                                     action-id epoch attempt-ordinal)
                         tool-context (merge supplied-context
                                             (:tool-context context)
                                             {:orc/idempotency-key action-id
                                              :orc/researcher-iteration iteration-index})
                         {:keys [result]}
                         (dispatch-claimed-tool-effect!
                          {:claim! claim!
                           :complete! complete!
                           :codecs codecs
                           :contracts (:tool-contracts node)
                           :tool-name tool-name
                           :args args
                           :action-id action-id
                           :attempt-id attempt-id
                           :attempt-ordinal attempt-ordinal
                           :iteration-index iteration-index
                           :caller target
                           :caller-arity arity
                           :tool-context tool-context
                           :wrap-errors? false})]
                     (swap! completed assoc action-id {:status :completed :result result})
                     result))))]
    (fn
      ([tool-name args] (call tool-name args nil))
      ([tool-name args tool-context] (call tool-name args tool-context)))))

(defn- resolve-node-tool-caller
  "The node's tool caller exactly as a code leaf uses it: the gate (when
   declared) or the static caller, guarded by the node's declared contracts
   (the consumer's own tool exceptions pass through unchanged), and, inside a
   checkpointed campaign's generated child, checkpointed as a campaign effect.
   Returns nil when no caller is available."
  [node blackboard context]
  (let [host-caller (node-call-tool-fn node blackboard context)
        checkpointed? (and host-caller (checkpointed-campaign-epoch context))
        _ (when (and checkpointed?
                     (not= 3 (tool-caller-arity (tool-caller-target host-caller))))
            (throw (ex-info tool-caller-arity-error {:expected-arities [3]})))
        call-tool-fn (guarded-call-tool-fn host-caller (:tool-contracts node)
                                                          :wrap-tool-errors? false)]
    (if checkpointed?
      (checkpoint-child-call-tool-fn node context call-tool-fn)
      call-tool-fn)))

(defn executor-execute-code
  "Execute a Clojure function as a leaf node.

   The function receives a context map with:
   - :event-store - The event store (if provided)
   - :inputs - Map of blackboard key -> value for node's reads

   The function should return a map of blackboard key -> value for writes.

   Args:
     node - The leaf node map with :fn (fully-qualified symbol string or inline fn)
     blackboard - Map of key -> {:key, :type, :value, :version}
     context - Additional context (event-store, etc.)

   Returns:
     {:status :success/:failure
      :outputs {string-key value}
      :error string?
      :duration-ms int}"
  [node blackboard context]
  (let [start-time (System/currentTimeMillis)
        fn-or-symbol (:fn node)
        resolved (if (fn? fn-or-symbol)
                   {:fn fn-or-symbol}
                   (if (and (:recoverable-fn node)
                            (string? fn-or-symbol)
                            (str/starts-with? fn-or-symbol "ephemeral-fn-"))
                     (try
                       {:fn (restore-ephemeral-fn!
                              fn-or-symbol (:recoverable-fn node))}
                       (catch Exception e
                         {:error (str "Cannot restore generated function " fn-or-symbol ": "
                                      (ex-message e))}))
                     (resolve-fn fn-or-symbol)))]
    (if (:error resolved)
      {:status :failure
       :error (:error resolved)
       :duration-ms (- (System/currentTimeMillis) start-time)}
      (try
        (let [f (:fn resolved)
              inputs (reduce (fn [acc key-name]
                               (if-let [entry (get blackboard key-name)]
                                 (assoc acc key-name (:value entry))
                                 acc))
                             {}
                             (:reads node))
              gate-context (cond-> (assoc context :node node)
                             (:id node) (assoc :node-id (:id node)))
              call-tool-fn (resolve-node-tool-caller node blackboard gate-context)
              code-context (assoc gate-context :call-tool-fn call-tool-fn)
              result (call-with-output-writer *out*
                       #(f (assoc code-context :inputs inputs :execution-context code-context)))
              duration-ms (- (System/currentTimeMillis) start-time)
              writes (:writes node)
              writes-set (set writes)
              result-keys (when (map? result) (set (keys result)))
              has-some-write? (and result-keys
                                   (seq (clojure.set/intersection writes-set result-keys)))
              outputs (cond
                        (nil? result) nil
                        has-some-write? (select-keys result writes)
                        (and (= 1 (count writes)) (not (map? result)))
                        {(first writes) result}
                        (and (= 1 (count writes)) (map? result))
                        {(first writes) result}
                        :else nil)]
          (let [missing-writes (when (seq writes)
                                 (filterv #(not (contains? outputs %)) writes))]
          (if (and (map? outputs) (empty? missing-writes))
            {:status :success
             :outputs outputs
             :duration-ms duration-ms}
            {:status :failure
             :error (if (seq missing-writes)
                      (str "Code executor omitted declared writes "
                           (pr-str missing-writes))
                      (str "Code executor functions must return a map compatible with declared :writes "
                           (pr-str writes)
                           ". Function returned: "
                           (cond
                             (nil? result) "nil"
                             (map? result) (str "map with keys " (pr-str (keys result)))
                             :else (str (type result)))))
             :duration-ms duration-ms})))
        (catch Exception e
          {:status :failure
           :error (.getMessage e)
           :duration-ms (- (System/currentTimeMillis) start-time)})
        (catch Throwable t
          (if (blocking-condition? t)
            {:status :blocked
             :block-payload (block-payload t)
             :duration-ms (- (System/currentTimeMillis) start-time)}
            (throw t)))))))

(defn execute-tool
  "Execute a tool leaf: call the ONE tool named when the workflow was authored
   (`:tool`) through the node's tool caller (gate, declared contract and, in a
   checkpointed campaign's child, effect receipts — exactly as a code leaf),
   with arguments taken from the declared read keys, and write the result to
   the single declared write key (LeafExecutor ToolLeafCallsOnlyAuthoredTools).
   A tool name is never taken from the blackboard or from a model. No tool
   caller available fails explicitly, naming the tool."
  [node blackboard context]
  (let [start-time (System/currentTimeMillis)
        tool-name (:tool node)
        elapsed #(- (System/currentTimeMillis) start-time)
        failure (fn [message] {:status :failure :error message :duration-ms (elapsed)})]
    (cond
      (not (and (string? tool-name) (not (str/blank? tool-name))))
      (failure "Tool executor requires an authored :tool name")

      (not= 1 (count (:writes node)))
      (failure (str "Tool executor for " tool-name " requires exactly one declared write"))

      :else
      (try
        (let [args (reduce (fn [acc key-name]
                             (let [value (:value (get blackboard key-name))]
                               (if (nil? value) acc (assoc acc key-name value))))
                           {}
                           (:reads node))
              gate-context (cond-> (assoc context :node node)
                             (:id node) (assoc :node-id (:id node)))
              call-tool-fn (resolve-node-tool-caller node blackboard gate-context)]
          (if-not call-tool-fn
            (failure (str "No tool caller available to call tool " tool-name))
            (let [write-key (first (:writes node))
                  raw (call-tool-fn tool-name args)
                  result (if (and (map? raw) (contains? raw write-key))
                           (get raw write-key)
                           raw)]
              (if (nil? result)
                (failure (str "Tool " tool-name " returned nil for declared write "
                              (pr-str write-key)))
                {:status :success
                 :outputs {write-key result}
                 :duration-ms (elapsed)}))))
        (catch Exception e
          (failure (.getMessage e)))
        (catch Throwable t
          (if (blocking-condition? t)
            {:status :blocked
             :block-payload (block-payload t)
             :duration-ms (elapsed)}
            (throw t)))))))

;; =============================================================================
;; AI Execution
;; =============================================================================

(defn validate-leaf-outputs
  "Normalize and validate declared leaf outputs before their writes become canonical."
  ([blackboard result]
   (validate-outputs blackboard result false))
  ([blackboard result provider-output?]
   (validate-outputs blackboard result provider-output?)))

(def ^:private prediction-envelope-keys
  "Keys llm/predict adds around :outputs when called with :with-metadata?."
  #{:outputs :usage :model :raw-response :provider-evidence :decisions :provider-observations})

(defn prediction-envelope?
  "True when `result` is llm/predict's metadata envelope rather than a direct
   flat output map: it has an :outputs key and every key is prediction metadata."
  [result]
  (and (map? result)
       (contains? result :outputs)
       (every? prediction-envelope-keys (keys result))))

(defn- unparseable-output-keys
  "Nil-shaped results are missing-value evidence only when their actual declared
   schema rejects them. Required nullable values and valid all-null/empty maps
   must cross the normal schema boundary without being relabelled as absence."
  [blackboard outputs]
  (into []
        (keep (fn [[k value]]
                (when (and (or (nil? value)
                               (and (map? value) (every? nil? (vals value))))
                           (not (when-let [schema (get-in blackboard [k :schema])]
                                  (try
                                    (m/validate schema (llm/decode-provider-value schema value))
                                    (catch Exception _ false)))))
                  k)))
        outputs))

(defn- strip-nil-optional-writes
  "Drop declared-OPTIONAL writes that parsed to nil, so a node can mark a
   best-effort output (e.g. an evidence array the model legitimately omits under
   prompt load) as droppable. A stripped (absent) value neither fails the nil-gate
   below NOR is written to the blackboard as nil — downstream code reads it as
   absent and defaults it. Opt in via the node's `:options :optional-writes`
   (a coll of write keywords); `:options` already round-trips to execution, so this
   needs no new persisted node field. `outputs` may be nil (error path) — pass it
   through untouched."
  [outputs optional-writes]
  (if (and (map? outputs) (seq optional-writes))
    (into {} (remove (fn [[k v]]
                       (and (contains? optional-writes k)
                            (or (nil? v)
                                (and (map? v) (every? nil? (vals v))))))
                     outputs))
    outputs))

(defn merge-usage
  "Accumulate usage across every provider attempt, including attempts whose
   structured output fails schema validation."
  [acc usage]
  (when (or acc usage)
    (merge-with + (or acc {}) (or usage {}))))

(def non-retryable-failure-kinds
  "Failures that are the provider's or the configuration's deterministic answer:
   an exact decision tie, and a provider that was never configured. Another
   invocation cannot change them, only spend budget."
  #{:undecided :provider-not-configured :model-plan-incompatible})

(defn persist-provider-attempt
  "Run the injected durable transition. Return a bounded failure marker only;
   persistence exceptions must not leak transport/configuration details."
  [callback attempt]
  (when callback
    (try
      (let [result (callback attempt)]
        (when (:cognitect.anomalies/category result) :observation-persistence-failed))
      (catch Exception _ :observation-persistence-failed))))

(defn validate-ai-prediction
  [node blackboard optional-writes unparseable-is-schema-error? prediction]
  (let [{:keys [outputs error raw-response]} prediction
            outputs (strip-nil-optional-writes outputs optional-writes)
            missing-output-keys (vec (remove #(or (contains? optional-writes %)
                                                  (contains? outputs %))
                                            (:writes node)))
            unparseable-keys (into missing-output-keys
                                   (unparseable-output-keys blackboard outputs))
            schema-result (when (and (not error)
                                     (or unparseable-is-schema-error?
                                         (empty? unparseable-keys)))
                            (if (seq missing-output-keys)
                              {:status :failure :outputs {} :missing-write-keys missing-output-keys :rejected-writes (or outputs {})
                               :error (str "Missing required output keys " missing-output-keys)}
                              (validate-leaf-outputs
                               blackboard
                               (cond-> {:status :success :outputs outputs}
                                 raw-response (assoc :raw-response raw-response))
                               true)))
            schema-error (when (= :failure (:status schema-result))
                           (:error schema-result))]
    {:outputs outputs :missing-output-keys missing-output-keys
     :unparseable-keys unparseable-keys :schema-result schema-result :schema-error schema-error}))

(defn build-blackboard-metadata
  "Build metadata description of blackboard variables for LLM context.
   Returns a string describing available variables and their types.
   Values are NOT included - only names, types, and descriptions."
  [node blackboard]
  (let [reads (:reads node)
        writes (:writes node)]
    (str "Available variables:\n"
         (str/join "\n"
                   (for [key-name reads
                         :let [entry (get blackboard key-name)
                               schema (:schema entry)
                               desc (malli-schema->description schema)
                               custom-desc (extract-schema-description schema)]]
                     (str "- " key-name ": " desc
                          (when custom-desc (str " - " custom-desc)))))
         "\n\nOutput variables (write your FINAL_ANSWER to these):\n"
         (str/join "\n"
                   (for [key-name writes
                         :let [entry (get blackboard key-name)
                               schema (:schema entry)
                               desc (malli-schema->description schema)]]
                     (str "- " key-name ": " desc))))))

(defn- parse-error-position
  "If `error` is a SCI parse error of the form
   '... at [L C]' (line L, column C — 1-indexed in SCI), return [L C].
   Returns nil otherwise."
  [error]
  (when (string? error)
    (when-let [m (re-find #"at\s+\[(\d+)\s+(\d+)\]" error)]
      [(Long/parseLong (nth m 1)) (Long/parseLong (nth m 2))])))

(defn- diagnose-parse-error
  "Look at a SCI parse error message and return a short actionable hint
   when the failure matches a known recurring pattern. Returns nil for
   error messages that don't match a known pattern.

   Patterns recognized:
   - 'Unmatched delimiter' — follow the parser's actual unexpected and
     expected delimiters, without guessing the enclosing API or form.
   - 'EOF while reading' — code truncated mid-form; usually a brace or
     paren never closed.
   - 'Could not resolve symbol' — usually a typo or missing require.
   - CV-B (C7, reactive closure hint): the sandbox's own
     :quoted-inline-function-source requirement — checked FIRST via the
     ex-data keyword when `error-data` is available, falling back to a
     literal (str/includes?) check against the sandbox's exported message
     constant when only the message text survives (never a regex over
     prose, since the message is our own template text, not model-authored)."
  ([error] (diagnose-parse-error error nil))
  ([error error-data]
   (cond
    (or (= :quoted-inline-function-source (:requirement error-data))
        (str/includes? (or error "") quoted-inline-function-source-message))
    (str "Diagnostic hint: this is the durable-checkpoint closure-rejection guard. "
         "Write every `:code` node's `:fn` as a literal quoted `(fn ...)` form "
         "inside THIS `emit-tree!` call — never bind the tree or a function to a "
         "name first and pass the name in. An already-evaluated closure cannot be "
         "recorded as durable source, so the whole tree is rejected before it is "
         "appended; re-emit the tree with the `:fn` written inline as `(fn "
         "[{:keys [inputs]}] ...)`.")

    (re-find #"(?i)unmatched delimiter" error)
    (str "Diagnostic hint: follow the unexpected delimiter and expected closing "
         "delimiter in the parser message. A position introduced by 'to match' "
         "identifies the opening form, not the unexpected closing character. "
         "Check the nesting of that form in the submitted code.")

    (re-find #"(?i)eof while reading" error)
    (str "Diagnostic hint: 'EOF while reading' means a delimiter "
         "(paren/bracket/brace) was opened but never closed. Re-emit the "
         "complete code carefully — count opens vs closes at each depth.")

    (re-find #"(?i)could not resolve symbol" error)
    (str "Diagnostic hint: 'Could not resolve symbol' usually means a typo. "
         "Only the primitives listed in your instruction (llm, code, "
         "emit-tree!, final!, get-input, get-var, store!) are bound. "
         "Don't reference clojure.core fns by alias.")

    :else nil)))

(defn- format-error-with-context
  "R-6: For SCI parse errors with [line col] markers, append the offending
   line of code + a caret pointer pinning the column. This surfaces the
   structural-exact location so the model can fix the specific character
   rather than re-emitting similar-broken code on the next iteration.

   Adds a diagnostic hint when the error matches a known recurring pattern
   (Unmatched delimiter / EOF while reading / Could not resolve symbol).

   Non-parse errors (or codes that don't have the indicated line) pass
   through with just the error message + the diagnostic hint when known.

   `error-data` (CV-B / C7) is the history entry's :error-data, when present
   — carried through to diagnose-parse-error so the reactive closure-rejection
   hint can key off the sandbox's own ex-data requirement keyword."
  ([code error] (format-error-with-context code error nil))
  ([code error error-data]
  (let [hint (diagnose-parse-error (or error "") error-data)
        hint-line (when hint (str "\n" hint))]
    (if-let [[line col] (parse-error-position error)]
      (let [lines (str/split (or code "") #"\n")
            target-line (when (<= 1 line (count lines))
                          (nth lines (dec line)))]
        (if target-line
          (str "Error: " error "\n"
               "At that line:\n"
               "  " target-line "\n"
               "  " (apply str (repeat (dec col) " ")) "^"
               hint-line)
          (str "Error: " error hint-line)))
      (str "Error: " error hint-line)))))

(defn build-iteration-history
  "Format iteration history for LLM context.

   Version-1 history retains its original verbatim rendering. Version-2
   immutable records render authored evidence, bounded errors/reasoning,
   profiles, and created/updated/removed key names without payload values.

   R-6: When an iteration's :error is a SCI parse error with a [line col]
   marker, the formatted history also includes the offending line + a
   caret pointer pinning the column. This makes the structural-exact
   location of the parse failure visible so the model can fix the
   specific character rather than retrying similar broken code."
  [history]
  (when (seq history)
    (str "\n\n## Previous Iterations\n"
         (str/join "\n\n"
                   (map-indexed
                    (fn [idx {:keys [code result stdout error vars-created tree-outcome]
                             :as entry}]
                      (if-let [delta (:variable-delta entry)]
                        (let [{:keys [created-keys updated-keys removed-keys]} delta]
                          (str "### Iteration " (inc idx) "\n"
                               "Status: " (:status entry) "\n"
                               "Code:\n```clojure\n" code "\n```\n"
                               (when-let [reasoning (:reasoning entry)]
                                 (str "Reasoning: " reasoning "\n"))
                               (when-let [error-excerpt (:error-excerpt entry)]
                                 (str "Error [" (:error-class entry) "]: "
                                      error-excerpt "\n"
                                      (when-let [hint (diagnose-parse-error
                                                        error-excerpt (:error-data entry))]
                                        (str hint "\n"))))
                               (when-let [result-profile (:result-profile entry)]
                                 (str "Result profile: " (pr-str result-profile) "\n"))
                               (when-let [stdout-profile (:stdout-profile entry)]
                                 (str "Stdout profile: " (pr-str stdout-profile) "\n"))
                               (if-let [emitted-tree-source
                                        (:emitted-tree-source entry)]
                                 (str "Emitted tree: " emitted-tree-source "\n")
                                 (when-let [emitted-tree (:emitted-tree entry)]
                                   (str "Emitted tree: " (pr-str emitted-tree) "\n")))
                               (when (seq created-keys)
                                 (str "Variables created: "
                                      (str/join ", " (map str created-keys)) "\n"))
                               (when (seq updated-keys)
                                 (str "Variables updated: "
                                      (str/join ", " (map str updated-keys)) "\n"))
                               (when (seq removed-keys)
                                 (str "Variables removed: "
                                      (str/join ", " (map str removed-keys)) "\n"))))
                        (str "### Iteration " (inc idx) "\n"
                             "Code:\n```clojure\n" code "\n```\n"
                             (when (seq stdout) (str "Output:\n" stdout "\n"))
                             (cond
                               error (format-error-with-context code error (:error-data entry))
                               tree-outcome (str "Result: " tree-outcome)
                               :else (str "Result: " result))
                             (when (seq vars-created)
                               (str "\nVariables created: "
                                    (str/join ", " (map str vars-created)))))))
                    history)))))

(def ^:private native-evidence-guidance
  (str "## Native evidence ownership\n"
       "Preserve native evidence unchanged when the task requires exact carry-through: "
       "authoritative records, opaque identifiers, literal values and material qualifications. "
       "Keep interpretation separate from evidence. Give semantic work ownership of new "
       "judgments, explanations or decisions, rather than asking it to reproduce fields "
       "already available as native values. Use code to assemble the declared final structure "
       "from the original evidence and those new interpretations. Preserve required order, "
       "duplicates, false, zero and null. Do not fuzzy-match, normalize, or repair identifiers "
       "to hide changed evidence. Task-requested transformations remain valid; apply them "
       "explicitly under the task's contracts. Use existing bindings, typed contracts and "
       "primitives; this principle requires no new helper API, fixed tree shape or extra "
       "model call.\n\n"))

(defn build-code-generation-module
  "Build ORC LLM module for generating Clojure code."
  [node blackboard-metadata history mcp-tools browser-tools]
  (let [has-mcp? (seq mcp-tools)
        has-browser? (seq browser-tools)
        has-namespaced? (some #(str/includes? % "/") mcp-tools)
        mcp-tool-list (str/join ", " mcp-tools)
        browser-tool-list (str/join ", " browser-tools)]
    {:inputs [{:name :task
               :spec :string
               :description "The research task to complete"}
              {:name :context
               :spec :string
               :description "Available variables and their types"}
              {:name :history
               :spec :string
               :description "Results from previous iterations (if any)"}
              {:name :tools
               :spec :string
               :description "Available tools you can call as functions"}]
     :outputs [{:name :code
                :spec :string
                :description "Clojure code to execute. Call tools as functions, use println to log progress, and output FINAL_ANSWER: <result> when done."}]
     :instructions (str "You are a research assistant that writes Clojure code to solve tasks.\n\n"
                        native-evidence-guidance
                        "IMPORTANT RULES:\n"
                        "1. Write valid Clojure code that calls the available tools\n"
                        "2. Use println to log your progress and findings\n"
                        "3. When you have the final answer, output it as: (str \"FINAL_ANSWER: \" your-answer)\n"

                        (when has-browser?
                          (str "\n## BROWSER TOOLS (agent-browser)\n"
                               "Available: " browser-tool-list "\n"
                               "These control a real browser. Key functions:\n"
                               "- (open \"https://url.com\") - Navigate to URL\n"
                               "- (snapshot) - Get accessibility tree with @refs (e.g., @e1, @e2)\n"
                               "- (click \"@e1\") - Click element by ref\n"
                               "- (fill \"@e2\" \"text\") - Fill form field\n"
                               "- (press \"Enter\") - Press key\n"
                               "- (get-text \"@e1\") - Get element text\n"
                               "- (get-title) - Get page title\n"
                               "- (wait 2000) - Wait milliseconds\n\n"
                               "Workflow: open -> snapshot -> interact using @refs -> snapshot to see result\n"))

                        (when has-mcp?
                          (str "\n## MCP TOOLS\n"
                               "Available: " mcp-tool-list "\n"
                               (if has-namespaced?
                                 (str "Namespaced: (server/tool {:arg \"value\"})\n")
                                 "")
                               "Each takes a map of arguments.\n"))

                        "\n## GENERAL\n"
                        "- Use standard Clojure: map, filter, reduce, str, get, get-in, etc.\n"
                        "- Do NOT use require, eval, slurp, or any I/O functions\n\n"
                        "Your task: " (:instruction node))}))

(defn phase1-call-tool-fn
  "The effective Phase-1 sandbox tool caller for a repl-researcher context.

   The SCI sandbox invokes MCP tools via a two-arg (tool-name args) caller.
   When the execution context carries an OPAQUE :tool-context (ADR 0018 G1 —
   the per-tick value the host threads across the async command boundary and
   which the Phase-2 tree :code path already re-threads to every leaf), wrap
   the raw :call-tool-fn so each INLINE Phase-1 tool call FORWARDS that
   tool-context as the third argument (the same 3-arity gate contract the
   Phase-2 :code leaf uses, e.g. ce2's gated-tool-leaf-fn). This lets the host
   gate an inline mutate with the SAME context Phase-2 receives, instead of
   the inline call fail-closing on a missing tool-context (the WS-5b resumed
   FALSE SUCCESS: a granted inline apply_patch that never re-applied).

   Absent :tool-context (or absent :call-tool-fn) -> the raw fn unchanged
   (backward-compatible; non-coding hosts and tool-context-free contexts see
   no change)."
  [context]
  (let [raw (:call-tool-fn context)
        tool-context (:tool-context context)]
    (if (and raw tool-context)
      (fn [tool-name args] (raw tool-name args tool-context))
      raw)))

(defn- format-variable-preview
  "Format a single variable preview for the Available Variables section."
  [k preview source]
  (let [type-str (name (or (:type preview) :unknown))
        size-str (cond
                   (:size preview) (str (:size preview) (if (= :string (:type preview)) " chars" " entries"))
                   (:length preview) (str (:length preview) " items")
                   :else nil)
        type-size (if size-str
                    (str "(" type-str ", " size-str ")")
                    (str "(" type-str ")"))
        preview-str (cond
                      (:preview preview) (str "  Preview: \"" (:preview preview) "\"")
                      (contains? preview :value) (str "  Value: " (pr-str (:value preview)))
                      (:sample preview) (str "  Sample: " (pr-str (:sample preview)))
                      (:keys preview) (str "  Keys: " (pr-str (:keys preview)))
                      :else nil)]
    (str k " " type-size " " source
         (when preview-str (str "\n" preview-str)))))

(defn- build-available-variables-section
  "Build the Available Variables section for the RLM prompt."
  [blackboard sandbox-vars-map var-creation-times]
  (let [keys-in-view (distinct (concat (keys blackboard) (keys sandbox-vars-map)))
        entries (for [k keys-in-view]
                  (cond
                    (contains? sandbox-vars-map k)
                    (format-variable-preview k (preview-value (get sandbox-vars-map k))
                                             (str "[current local binding; created iteration "
                                                  (get var-creation-times k "unrecorded") "]"))
                    (contains? (get blackboard k) :value)
                    (format-variable-preview k (preview-value (get-in blackboard [k :value]))
                                             "[original blackboard value]")
                    :else
                    (format-variable-preview k {:type :absent}
                                             "[declared; no current value]")))]
    (when (seq entries)
      (str "## Available Variables — current binding previews\n\n"
           "Previews are bounded. get-var reads current local bindings; get-input loads original blackboard values.\n\n"
           (str/join "\n\n" entries)
           "\n\n"))))

(defn- declared-write-schema-contract
  "Return the Phase-1 output contract in the node's declared write order.

   Schemas come directly from the parent blackboard projection. Public workflow
   construction guarantees that every declared write has a schema; keeping this
   helper as a projection also preserves compatibility for private prompt-only
   callers that construct partial test nodes."
  [node blackboard]
  (into (array-map)
        (map (fn [key-name]
               [key-name (get-in blackboard [key-name :schema])]))
        (:writes node)))

(defn- available-schema-contracts
  "Project declared contracts from the same blackboard as Available Variables.
   Preserve whole Malli forms, including nested descriptions; do not discover
   keys from other sheets or manufacture contracts for computed values."
  [blackboard]
  (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
        (keep (fn [[key-name entry]]
                (when (some? (:schema entry))
                  [key-name (:schema entry)])))
        blackboard))

(defn bound-tool-contracts
  "Build the complete Phase-1 contract for the tools actually bound to a node."
  [node mcp-tools]
  (let [declared (:tool-contracts node)
        legacy-arguments (get-in node [:options :tool-arg-specs])]
    (into (array-map)
          (map (fn [tool]
                 (let [contract (get declared tool)
                       arguments (cond
                                   (some? (:arguments contract)) (:arguments contract)
                                   (seq (get legacy-arguments tool))
                                   (into [:map]
                                         (map (fn [key-name] [key-name :any]))
                                         (get legacy-arguments tool))
                                   :else :untyped)
                       result (if (some? (:result contract))
                                (:result contract)
                                :untyped)]
                   [tool (cond-> {:arguments arguments :result result}
                           (contains? contract :checkpoint-safe?)
                           (assoc :checkpoint-safe? (:checkpoint-safe? contract)))])))
          mcp-tools)))

(defn registered-mint-behavior-contract
  "Return the SCI mint affordance contract when its Grain command is available.

   The registered command schema is the authority for the public name, body,
   and parent shapes.  Deriving the contract here keeps orc-service independent
   of the optional ontology component and prevents the prompt from drifting from
   the schema Grain actually validates."
  [command-registry]
  (when (contains? command-registry :ontology/mint-behavioral-subtree)
    (let [command-form (m/form
                         (m/deref
                           (m/schema :ontology/mint-behavioral-subtree)))
          field-schemas (into {}
                              (keep (fn [entry]
                                      (when (vector? entry)
                                        [(first entry) (last entry)])))
                              (rest command-form))
          required-fields [:name :body :parent-behavior]
          missing-fields (into []
                               (remove #(some? (get field-schemas %)))
                               required-fields)]
      (when (seq missing-fields)
        (throw (ex-info
                 "mint-behavior command schema must declare name, body, and parent-behavior fields"
                 {:command :ontology/mint-behavioral-subtree
                  :missing-fields missing-fields
                  :schema-form command-form})))
      {:arguments (array-map
                    :name (:name field-schemas)
                    :body (:body field-schemas)
                    :parent {:optional true
                             :schema (:parent-behavior field-schemas)})
       :returns :string})))

(defn- build-ontology-examples-section
  "Build a section of the prompt with few-shot examples from ontology.
   Returns nil if no examples available."
  [node]
  (when-let [examples-fn (get-in node [:rlm :examples-fn])]
    (let [examples (try (examples-fn {}) (catch Exception _ []))]
      (when (seq examples)
        (str "## Example from ontology (successful patterns)\n\n"
             (str/join "\n\n"
                       (for [{:keys [task tree score]} examples]
                         (str "**Task**: " task "\n"
                              "**Score**: " (when score (format "%.2f" (double score))) "\n"
                              "```clojure\n"
                              "(emit-tree! " (pr-str tree) ")\n"
                              "```")))
             "\n\n")))))

(defn build-rlm-code-generation-module
  "Build ORC LLM module for generating code in RLM mode.

   In RLM mode, the LLM generates code that can:
   - Call (llm \"name\" :instruction \"...\" :writes [:key]) to execute sub-LLM calls
   - Call (final! {:key value}) to return validated results
   - Call (get-input :key) to load input values into variable space
   - Call store!/get-var to manage computed variables
   - Access 'inputs' map for metadata previews of available data

   U9: When (:rlm node) is a map containing :available-code-nodes (string), that
   catalog is surfaced as an extra llm input field so the model can use
   the listed functions inside emit-tree! :code nodes.

   CE-6b (ADR 0018): when mcp-tools is non-empty, the module advertises the
   bound tools (re-housing the non-RLM researcher's tool-advertisement
   pattern — declared :tools input + instructions section): tool names, the
   fact that they are bound as directly callable functions in the Phase-1
   sandbox, the generic call shape, and inline-effects guidance — and the
   large-data emit-tree! example line drops its exclusivity claim. With
   empty/absent mcp-tools the module is byte-identical to the pre-CE-6b
   output (backward-compatible). Tool names come from the node config; no
   tool semantics are hardcoded beyond the generic call shape.

   W38-F2: a tool declared in the node's :options :tool-arg-specs
   ({\"tool-name\" [\"arg-key\" ...]}) renders its REAL argument keys in the
   Bound Tools call examples instead of the generic {\"arg\" \"value\"}
   placeholder (which models copy byte-for-byte — the store-proven wrong-key
   defect). Tools without a spec keep the generic example; with no specs the
   advertisement is byte-identical to pre-W38."
  ([node inputs-preview history blackboard sandbox-vars-map var-creation-times]
   (build-rlm-code-generation-module node inputs-preview history blackboard
                                     sandbox-vars-map var-creation-times
                                     (or (:mcp-tools node) [])))
  ([node inputs-preview history blackboard sandbox-vars-map var-creation-times mcp-tools]
   (build-rlm-code-generation-module node inputs-preview history blackboard
                                     sandbox-vars-map var-creation-times
                                     mcp-tools {}))
  ([node inputs-preview history blackboard sandbox-vars-map var-creation-times mcp-tools
    {:keys [mint-behavior-contract]}]
  (let [rlm-config (let [rlm (:rlm node)] (if (map? rlm) rlm {}))
        checkpointed? (boolean (:checkpointed? rlm-config))
        available-code-nodes (get rlm-config :available-code-nodes)
        has-mcp? (boolean (seq mcp-tools))
        mcp-tool-list (str/join ", " mcp-tools)
        has-namespaced? (some #(str/includes? % "/") mcp-tools)
        tool-arg-specs (get-in node [:options :tool-arg-specs])
        specced-tools (filterv #(seq (get tool-arg-specs %)) mcp-tools)
        unspecced-tools (filterv #(not (seq (get tool-arg-specs %))) mcp-tools)
        tool-contracts (bound-tool-contracts node mcp-tools)
        write-schema-contract (declared-write-schema-contract node blackboard)
        base-inputs [{:name :task
                      :spec :string
                      :description "The research task to complete"}
                     {:name :inputs-info
                      :spec :string
                      :description "Available inputs with metadata previews (type, size, sample)"}
                     {:name :history
                      :spec :string
                      :description "Results from previous iterations (if any)"}]
        all-inputs (cond-> base-inputs
                     mint-behavior-contract
                     (conj {:name :mint-behavior-contract
                            :spec :map
                            :description "Authoritative call contract for the built-in mint-behavior! primitive"})
                     available-code-nodes
                     (conj {:name :available-code-nodes
                            :spec :string
                            :description "Catalog of pre-built Clojure functions you can reference in emit-tree! :code nodes via {:fn \"ns/sym\" ...}. Read this carefully if present."})
                     has-mcp?
                     (conj {:name :tools
                            :spec :string
                            :description "Available tools you can call as functions"}
                           {:name :tool-contracts
                            :spec :map
                            :description "Authoritative argument and result schemas for bound tools; :untyped means no schema was declared"}))]
    {:inputs all-inputs
   :outputs [{:name :reasoning
              :spec :string
              :description "Brief explanation (2-5 sentences) of WHY you are choosing this tree shape: which inputs are driving the design, which primitives you picked and why, what alternative you considered and rejected, and which prepended corpus suggestions (if any) you adopted or skipped. Write this BEFORE you write :code."}
             {:name :code
              :spec :string
              :description "Clojure code to execute"}]
   :instructions (str "You are an RLM (Recursive Language Model) that constructs behavior trees to solve tasks.\n\n"
                      native-evidence-guidance
                      "## Two-Space Architecture\n\n"
                      "RLM separates **Variable Space** (full data in sandbox memory) from **Token Space** (what LLMs see).\n"
                      "- Available Variables show PREVIEWS (type, size, sample) - not full content\n"
                      "- Use (get-input :key) to load full values into variable space\n"
                      "- Sub-LLM calls via :reads receive FULL values; bound their input sizes explicitly in your code\n\n"
                      (build-available-variables-section blackboard sandbox-vars-map var-creation-times)
                      "## Available Declared Contracts\n\n"
                      (pr-str (available-schema-contracts blackboard)) "\n\n"
                      "These are existing contracts for inputs, intermediate variables, and final outputs in this workflow. "
                      "A declaration describes a value's shape and meaning; it does not prove that a value has been produced. "
                      "Inline calls reuse these declared contracts for their :reads and :writes. "
                      "Every inline :writes key must already have a declared contract. "
                      "The separate Output Contract below identifies the final keys this researcher must return.\n\n"
                      "## Available Primitives\n\n"
                      "### llm - Execute a sub-LLM call\n"
                      "```clojure\n"
                      "(llm \"name\" :instruction \"What to do\" :reads [:key] :writes [:output-key])\n"
                      "```\n"
                      "- Returns a map with the :writes keys populated\n"
                      "- :reads [...] passes FULL values to the sub-LLM (no truncation)\n"
                      "- You control chunk sizes in your code - sub-LLMs receive exactly what you pass\n"
                      "- :reads keys MUST match exact names from Available Variables or keys you have populated with store!\n"
                      "- `:output-schemas` belongs to emitted nodes; it is not an inline llm option. Use an existing declared :writes key for inline calls\n\n"
                      "### final! - Return validated result\n"
                      "```clojure\n"
                      "(final! {:key value})\n"
                      "```\n"
                      "- MUST include exactly the keys declared in :writes\n"
                      "- Validates output matches contract\n\n"
                      "### get-input - Load full value into variable space\n"
                      "```clojure\n"
                      "(get-input :key)\n"
                      "```\n"
                      "- Returns the full value (not just preview)\n"
                      "- Use this to access input data for processing\n\n"
                      "### store!/get-var - Manage computed variables\n"
                      "```clojure\n"
                      "(store! :name value)  ;; Store and return value\n"
                      "(get-var :name)       ;; Retrieve stored value (nil if not found)\n"
                      "(list-vars)           ;; List all variables with previews\n"
                      "```\n"
                      "Lexical bindings are not automatically keyed inputs. Before a sub-LLM reads a value computed in a let binding, store it under the exact :reads key:\n"
                      "```clojure\n"
                      "(let [selected (get-input :document)]\n"
                      "  (store! :work selected))\n"
                      "```\n"
                      "A later :reads [:work] receives that complete stored value. Storing a value does not create an output contract for an inline :writes key.\n\n"
                      "### sequence - Execute children in order\n"
                      "```clojure\n"
                      "(sequence \"name\" child1 child2 ...)\n"
                      "```\n"
                      "- Executes each child in sequence\n"
                      "- Merges all result maps together\n\n"
                      "### map-each - Process collection items\n"
                      "```clojure\n"
                      "(map-each \"name\" :collection-key :as :item-name\n"
                      "  (fn [] (llm \"process\" :reads [:item-name] :writes [:result])))\n"
                      "```\n"
                      "- Iterates over collection from :collection-key in variables\n"
                      "- Stores each complete item under :item-name for the body's :reads\n"
                      "- Body function called for each item\n"
                      "- Returns vector of results\n\n"
                      "### code - Pure computation (no LLM)\n"
                      "```clojure\n"
                      "(code \"name\" :writes [:result] :body (+ 1 2))\n"
                      "```\n"
                      "- Executes pure Clojure computation\n"
                      "- No LLM call involved\n"
                      "- Result stored in :writes key\n\n"
                      (when mint-behavior-contract
                        (str "### mint-behavior! - Persist a reusable behavioral subtree\n"
                             "```clojure\n"
                             "(mint-behavior! name body)\n"
                             "(mint-behavior! name body :parent parent-id)\n"
                             "```\n"
                             "The authoritative call contract is:\n"
                             (pr-str mint-behavior-contract) "\n"
                             "Use the declared body schema exactly; the return value is the minted behavior ID as a string.\n\n"))
                      "### emit-tree! - Generate a behavior tree for execution\n"
                      "```clojure\n"
                      "(emit-tree! (quote [:sequence\n"
                      "              [:chunk-document {:from :document :size 5000 :into :chunks}]\n"
                      "              [:map-each {:from :chunks :as :chunk :into :results\n"
                      "                          :output-schemas {:results [:vector [:map [:info :string]]]}}\n"
                      "                [:llm {:instruction \"Extract key info\" :reads [:chunk] :writes [:info]\n"
                      "                       :output-schemas {:info :string}}]]\n"
                      "              [:aggregate {:from :results :writes [:all-info]\n"
                      "                           :output-schemas {:all-info [:map-of :keyword [:vector :string]]}}]\n"
                      "              [:llm {:instruction \"Summarize the extracted information\" :reads [:all-info] :writes [:summary]\n"
                      "                     :output-schemas {:summary :string}}]\n"
                      "              [:final {:keys [:summary]}]]))\n"
                      "```\n"
                      "- Emits a behavior tree S-expression for two-phase execution\n"
                      "- Use this when you need to design a processing pipeline\n"
                      "- Available node types:\n"
                      "  - :sequence - Execute children in order\n"
                      "  - :parallel - Execute children concurrently (independent work only)\n"
                      "  - :llm - Execute a sub-LLM call with {:instruction :reads :writes}\n"
                      "      Existing Available Declared Contracts are inherited for shared :reads and :writes, even before a value exists.\n"
                      "      Omit :output-schemas for those keys; an identical redeclaration is allowed, but a conflicting shape or meaning is rejected.\n"
                      "      For NEW computed keys, declare :output-schemas {<write-key> <Malli-schema>} with the complete specific shape and meaning.\n"
                      "      When the schema is structured (e.g. [:vector [:map [:name :string]]],\n"
                      "      [:map [:foo :string] [:bar :int]]), the framework asks the LLM for valid JSON and parses\n"
                      "      the response back into Clojure data automatically. A generated tree with any unresolved write\n"
                      "      schema is rejected before execution. Use the most specific schema matching the value's intent.\n"
                      "      The examples illustrate new keys; reuse an existing declared contract when a name is already declared.\n"
                      "      Example:\n"
                      "        [:llm {:instruction \"Identify PII targets on this page; return :targets as a vector of maps.\"\n"
                      "               :reads [:page-text]\n"
                      "               :writes [:targets]\n"
                      "               :output-schemas {:targets [:vector [:map [:name :string]]]}}]\n"
                      "  - :map-each - Process collection items with {:from :as :into :max-concurrency N} (N=1 default, use 3-5 for parallel independent items)\n"
                      "  - :chunk-document - Split document into chunks with {:from :size :into}\n"
                      "  - :aggregate - Combine results with {:from :writes}\n"
                      "  - :code - Deterministic Clojure computation. Two forms:\n"
                      "      (a) Pre-built fn by qualified-symbol string: {:fn \"ns/sym\" :reads [...] :writes [...]}\n"
                      "          (see :available-code-nodes for fns available in this task, if any)\n"
                      "      (b) INLINE function written by you: {:fn (fn [{:keys [inputs]}] {...output-map...}) :reads [...] :writes [...]}\n"
                      "          The inline fn receives a context map with :inputs (the :reads keys -> values).\n"
                      "          It must return either a map with the :writes keys, or a single value (auto-wrapped under the\n"
                      "          single declared :write). Use this when no pre-built fn exists and you need a deterministic transform.\n"
                      "          Example: [:code {:fn (fn [{:keys [inputs]}] (let [s (-> inputs vals first)] {:counts (frequencies s)}))\n"
                      "                            :reads [:text] :writes [:counts]}]\n"
                      "  - :final - Return validated output with {:keys [...]}\n"
                      "- The tree is stored for learning and can be reused\n\n"
                      (if has-mcp?
                        (str "## Bound Tools\n\n"
                             "The following tools are bound in your sandbox as directly callable functions: "
                             mcp-tool-list "\n"
                             "Their authoritative argument and result schemas are:\n"
                             (pr-str tool-contracts) "\n"
                             "Use the declared result field names when inspecting successful responses. "
                             "A schema marked :untyped was not declared and must be inspected defensively.\n"
                             "Each takes a single map of arguments and returns a result map:\n"
                             "```clojure\n"
                             (apply str
                                    (for [tool specced-tools]
                                      (str "(" tool " {"
                                           (str/join " " (map #(str "\"" % "\" \"...\"")
                                                              (get tool-arg-specs tool)))
                                           "})  ;; => result map\n")))
                             (when (seq unspecced-tools)
                               (str "(" (first unspecced-tools) " {\"arg\" \"value\"})  ;; => result map\n"))
                             "```\n"
                             (when (seq specced-tools)
                               "The argument keys shown above are each tool's REAL parameter names — use them verbatim.\n")
                             (if has-namespaced?
                               "Namespaced names (server/tool) are bound per-namespace — call them exactly as listed above.\n"
                               "")
                             "Perform workspace/file effects by calling these bound tools INLINE in your Phase-1 code "
                             "(read → patch/write → verify) rather than emitting a tree for them. Reserve emit-tree! "
                             "for genuinely parallel or structured sub-work.\n\n")
                        "")
                      "## Default Mode: emit-tree!\n\n"
                      "**emit-tree! is your default execution mode.** For ANY non-trivial workflow — anything\n"
                      "with multiple steps, parallel sub-tasks, deterministic transforms alongside LLM calls,\n"
                      "large inputs, or quality/verification requirements — emit a tree.\n\n"
                      "The narrow exceptions (when emit-tree! is overkill):\n"
                      "- The task is trivially small: single short input, single output, no intermediate work.\n"
                      "- A single (llm ...) call OR a single :code computation would clearly suffice end-to-end.\n\n"
                      "### Anti-pattern: chained sequential (llm ...) calls in Phase 1\n\n"
                      "**If you find yourself writing 2+ sequential (llm ...) calls in Phase 1 code, that is\n"
                      "a strong signal you should use emit-tree! instead.** The chained pattern translates\n"
                      "directly to [:sequence [:llm ...] [:llm ...] [:final {...}]]. The tree gives you:\n"
                      "- Per-node observability and retry\n"
                      "- Composition with :code nodes for deterministic transforms\n"
                      "- Event-trace coverage that direct Phase-1 chaining does not produce\n\n"
                      "### Use :code nodes for deterministic transforms\n\n"
                      "For deterministic work — counting, regex matching, deduplication, string\n"
                      "replacement, aggregation, format conversion — prefer a :code node over a\n"
                      "sub-LLM call. Counting characters/items via (llm ...) is hallucination-\n"
                      "prone; deterministic code still needs validation against the declared contract and task.\n\n"
                      "### Common emit-tree! patterns\n\n"
                      "For any large data that exceeds token limits, use emit-tree! to design a processing pipeline:\n"
                      "- **Documents**: :chunk-document to split text, then :map-each + :llm per chunk, then :aggregate.\n"
                      "- **Graphs/Ontology**: Traverse by neighborhood or sample subgraphs, then :map-each + :llm.\n"
                      "- **Collections**: Partition into batches, then :map-each + :llm per batch.\n"
                      "- **Vision over multiple images**: :map-each over an image vector with :llm reading individual image keys.\n\n"
                      "The pattern is always: break into bounded sub-problems → sub-LLM per piece → :code or :aggregate.\n"
                      "Previews adapt to data type: text samples for documents, T-box/A-box summaries for graphs.\n\n"
                      (or (build-ontology-examples-section node) "")
                      (if (not= false (get-in node [:rlm :recursive?]))
                        (str "## Recursive mode (this mode)\n\n"
                             "In this mode, `emit-tree!` is how you do work. Design a "
                             "tree for one piece of the task, run it, see the result, "
                             "decide what to do next. The loop is: `(emit-tree! ...)` → "
                             "inspect outputs → decide → repeat, until you call "
                             "`(final! {...})`.\n\n"
                             "### Preferred per-iteration pattern\n"
                             "Each iteration, prefer one of:\n"
                             "1. `(emit-tree! ...)` to make progress on the task, OR\n"
                             "2. `(final! {...})` to terminate when the work is done.\n\n"
                             "emit-tree! does NOT return tree outputs in the same iteration — "
                             "never call final! in the same code block as emit-tree!.\n\n"
                             "Direct `(llm ...)` / `(code ...)` calls in Phase 1 are for "
                             "narrow inspection or decision flows — they should not be "
                             "your main work loop. If you find yourself iterating direct "
                             "`(llm ...)` calls without emitting trees, switch to "
                             "`emit-tree!`. Each `emit-tree!` is a full sub-tick that "
                             "the framework executes, records, and surfaces results from.\n\n"
                             "### After each `emit-tree!` completes\n"
                             "The tree's outputs are merged into your variables (use "
                             "`(get-var :summary)` etc.), a summary entry is appended "
                             "to `:tree-results`, and control returns to you. The loop "
                             "ends only when you call `(final! {...})` or you exceed "
                             ":max-iterations.\n\n"
                             "Your iteration history shows the tree's OUTCOME directly: "
                             "status, node counts, merged outputs with value previews, "
                             "any NIL/EMPTY WRITES (declared writes whose values did not "
                             "land — `(get-var ...)` returns nil/empty for those), failed "
                             "leaves with their error text, and surviving intermediate "
                             "vars. READ IT before deciding your next step — `Variables "
                             "created:` lists only writes whose values actually landed.\n\n"
                             "### Accessing prior tree outputs — use `get-var`, NOT `get-input`\n"
                             "When you call `(emit-tree! ...)`, the tree's `:writes`-declared keys "
                             "land in your sandbox variables. Subsequent iterations access them via "
                             "`(get-var :key)`, NOT via `(get-input :key)`. `get-input` only returns "
                             "the repl-researcher node's declared input keys (the data passed INTO "
                             "the researcher) — it does NOT see prior-tree outputs. If you try to "
                             "access a prior tree's write via `get-input`, you'll get nil, and the "
                             "subsequent `(final! ...)` will be rejected as all-empty.\n\n"
                             "Concretely, after `(emit-tree! ...)` writes `:total-redactions` and "
                             "`:targets-applied`:\n"
                             "```clojure\n"
                             ";; CORRECT — read prior tree's writes:\n"
                             "(final! {:total-redactions (get-var :total-redactions)\n"
                             "         :targets-applied  (get-var :targets-applied)})\n"
                             "```\n"
                             "Re-emitting the same tree to recompute data you already have is "
                             "wasteful — check `(list-vars)` or `:tree-results` to see what's "
                             "already in your sandbox before designing the next tree.\n\n"
                             "### Reading `:tree-results`\n"
                             "Each entry has `:status` — one of:\n"
                             "  - `:success` — the tree completed and all sub-nodes succeeded\n"
                             "  - `:partial` — some sub-nodes failed; outputs reflect the successful subset\n"
                             "  - `:failure` — the tree did not produce useful outputs\n"
                             "  - `:timeout` — the tree was cancelled before completion (budget exhausted)\n\n"
                             "Other fields per entry: `:elapsed-ms`, `:outputs-keys` (what was merged), "
                             "`:nodes-succeeded`, `:nodes-failed`, `:nodes-total`, `:surviving-vars` "
                             "(successful intermediate writes preserved from inside the tree). On "
                             "`:partial` or `:failure` you also get `:failure-indices` + "
                             "`:failure-reasons` (verbatim error strings).\n\n"
                             "An `:llm` leaf whose response could not be parsed into its declared "
                             "writes FAILS (after its node-level retries) — its `:failed-leaves` "
                             "error names the unparseable keys and includes a preview of the raw "
                             "response text; `(node-output node-id)` returns the full verbatim "
                             "raw text so you can see exactly what the sub-model wrote.\n\n"
                             "### Interpretation depends on your task\n"
                             "For some tasks `:partial` is acceptable (e.g., document summarization "
                             "with 22 of 24 chunks succeeded is usually fine). For others it requires "
                             "follow-up (e.g., obligations extraction where missing chunks could miss "
                             "obligations). You decide based on your task and the outputs you got.\n\n"

                             "### Recover from a failed tree — investigate then resume, don't rebuild\n"
                             "When `:tree-results` shows `:status :failure`, `:partial`, or `:timeout`, "
                             "DO NOT re-emit the same tree from scratch — that wastes every sub-LLM "
                             "call that already succeeded. Successful writes are preserved in your "
                             "sandbox variables across iterations. Use them.\n\n"
                             "ALSO trigger recovery when `:status :success` but `:nil-writes` "
                             "is non-empty on the most-recent `:tree-results` entry — this same "
                             "signal appears as the `NIL/EMPTY WRITES:` line in your iteration "
                             "history's tree outcome. `:nil-writes` "
                             "lists declared writes that came back as `nil`, empty string, empty "
                             "vector/map/seq/set — i.e. nil/empty values for keys the tree was "
                             "contracted to produce. The run did not fail; the values are just "
                             "missing or empty. Decide whether that is the CORRECT answer for "
                             "your task (some tasks legitimately return empty results) or whether "
                             "downstream nodes (often aggregator `:code` fns) silently produced "
                             "nothing useful and you need to recover. NEVER pass a nil-written "
                             "key's `(get-var ...)` value into `(final! ...)` without making "
                             "that decision explicitly. If recovery is needed, the "
                             "same flow below applies — investigate, inventory surviving vars, "
                             "and design a smaller resume tree.\n\n"
                             "Recovery flow:\n"
                             "  1. **Investigate**: read `:failed-leaves` (each entry has `:node-id` "
                             "+ `:error` — surfaces direct-leaf runtime failures), `:failure-reasons` "
                             "and `:failure-indices` (map-each per-child failures), OR `:nil-writes` "
                             "keys on a `:status :success` entry whose declared writes came back "
                             "nil/empty. If those fields aren't specific enough, drill in with "
                             "`(tree-failures)` / `(tree-detail tick-id)` / `(node-output node-id)`.\n"
                             "  2. **Inventory surviving vars**: check `:surviving-vars` and "
                             "`:outputs-keys` on the same `:tree-results` entry (also listed in "
                             "your iteration history's tree outcome), or `(list-vars)`, to see "
                             "EXACTLY what data already exists in your sandbox. Successful "
                             "intermediate writes from a failed tree ARE preserved — often the "
                             "failure is downstream of work that produced perfectly usable "
                             "intermediate outputs.\n"
                             "  3. **Design a smaller resume tree** that reads the surviving vars via "
                             "`(get-var ...)` and ONLY runs the nodes needed to finish the work the "
                             "failed tree didn't complete. Do not include nodes that recompute data "
                             "you already have.\n\n"
                             "Concrete pattern:\n"
                             "```clojure\n"
                             ";; Prior tree produced :findings and :missing_items successfully,\n"
                             ";; but the recommendations :llm and the final :code transform failed.\n"
                             ";; DO NOT re-emit the whole extraction + gap-analysis tree.\n"
                             ";; RESUME from the surviving structured data:\n"
                             "(emit-tree!\n"
                             "  [:sequence\n"
                             "   [:llm {:instruction \"Generate recommendations from findings + gaps\"\n"
                             "          :reads [:findings :missing_items]\n"
                             "          :writes [:recommendation_data]\n"
                             "          :output-schemas {:recommendation_data :string}}]\n"
                             "   [:code {:fn shape-final-writes\n"
                             "           :reads [:findings :missing_items :recommendation_data]\n"
                             "           :writes [:issues :ambiguities :missing :recommendations]\n"
                             "           :output-schemas {:issues [:vector :string]\n"
                             "                            :ambiguities [:vector :string]\n"
                             "                            :missing [:vector :string]\n"
                             "                            :recommendations [:vector :string]}}]\n"
                             "   [:final {:keys [:issues :ambiguities :missing :recommendations]}]])\n"
                             "```\n\n"
                             "Re-emitting a tree is appropriate ONLY when the failure is fundamental "
                             "to the design (e.g., the prior tree never produced ANY usable outputs, "
                             "or the failure exposes that the original design was structurally wrong "
                             "and needs a different shape). In those cases, treat the prior tree as "
                             "diagnostic information — what you learned from its failure should inform "
                             "the new design, not be discarded.\n\n"

                             "### After a tree completes\n"
                             "You can:\n"
                             "  - Call `(emit-tree! ...)` again to run another tree\n"
                             "  - Run `(llm ...)` or `(code ...)` inline for follow-up work\n"
                             "  - Call `(final! {...})` to terminate and return your answer\n\n"
                             "### Drill-down primitives — use only when the summary is insufficient\n"
                             "If `:tree-results` summary fields don't give you enough to decide your next step, "
                             "you can read the full event-store record of the most-recent (or a specific) tree:\n"
                             "  - `(tree-detail)` / `(tree-detail tick-id)` — full structured record: per-node "
                             "`:status`, `:duration-ms`, `:writes`, `:usage`, `:input-profile`, and any `:partial-summary`\n"
                             "  - `(tree-trajectory)` / `(tree-trajectory tick-id)` — chronological per-event "
                             "log of what happened inside the tree\n"
                             "  - `(tree-failures)` — failure entries with errors + per-failure input profiles "
                             "(joins direct leaf failures with map-each `:partial-summary` failure indices)\n"
                             "  - `(node-output node-id)` — writes map of a specific completed node; "
                             "for a parse-failed `:llm` leaf this includes `:raw-response`, the full "
                             "verbatim text the sub-model wrote (use it to see WHY parsing failed "
                             "and decide how to rephrase the node's instruction)\n"
                             "  - `(node-input-profile node-id)` — input profile (chunk shape, etc.) of a specific node\n\n"
                             "These return potentially large data — prefer the `:tree-results` summary first and only "
                             "drill down when you genuinely need the extra detail to make a decision.\n\n"
                             "### Verify before final\n"
                             "Finalization is the DEFAULT next step once the required outputs are "
                             "populated, unless the task explicitly requires a multi-stage process, "
                             "failure/recovery audit, verification step, or a particular sequence of "
                             "primitive calls. Those process requirements remain mandatory even when "
                             "an output could be produced early. Before `(final! {...})`, audit every "
                             "task-level MUST against your iteration history, then glance at "
                             "`:outputs-previews` on "
                             "the most recent `:tree-results` entry to spot CLEARLY broken payloads — "
                             "e.g. an A-Z letter count block that reads `A: 0, B: 0, ...` for "
                             "non-empty transcription text, a required structured array that came "
                             "back `[]`, or `nil` where structured data should be. Cosmetic or "
                             "stylistic differences alone do not require another tree. Missing required "
                             "evidence, changed native values or material qualifications, and unsupported "
                             "certainty are correctness failures even when the schema and previews look "
                             "complete. Use focused repair within the remaining allowance, or a "
                             "contract-permitted unresolved result when the evidence genuinely prevents "
                             "closure. Do not invent missing evidence or use an unresolved result to "
                             "conceal an avoidable error.\n\n")
                        "")
                      "## Common pitfalls\n"
                      "These are recurring SCI/Clojure footguns that hit model-authored code. "
                      "Reading this list once now prevents the retry loop from rediscovering "
                      "each pitfall via execution failure. Each entry pairs the failure mode "
                      "with the concrete fix.\n\n"
                      "- **Computed set elements must be distinct.** A literal such as "
                      "`#{(get m :a) (get m :b)}` works when the values differ, but "
                      "equal values cause a duplicate-key error. Use `(set [(get m :a) "
                      "(get m :b)])` when runtime values may repeat.\n"
                      "- **`frequencies` counts nil, false and zero as distinct values.** "
                      "Preserve nil when it is a valid domain value; filter it only when "
                      "the declared task explicitly excludes it. Do not discard evidence "
                      "merely because a value is false, zero or nil.\n"
                      "- **Check presence and producer completion before using stored values.** "
                      "`(contains? (list-vars) :foo)` distinguishes an absent key from a "
                      "present key; false and explicit nil are values, so `when-let` is "
                      "not a presence check. Read the value with `(get-var :foo)`. A retained "
                      "value may be from an earlier successful producer even when a later "
                      "tree failed; inspect the producing result before assuming freshness.\n"
                      "- **Use supported sandbox capabilities, not arbitrary JVM APIs.** "
                      "Available examples include `clojure.string/join` and `clojure.set/intersection`. "
                      "Do not assume every namespace, Java class or core function is available. "
                      "Typed input values do not need string parsing; preserve their declared "
                      "types and presence instead of inventing coercions or default values.\n"
                      "- **`:output-schemas` must match downstream `:code` consumer's "
                      "expectations.** If the `:llm` declares `:output-schemas {:foo "
                      ":string}` but the downstream `:code` expects a parsed map, the "
                      "consumer crashes on `(get parsed-map ...)`. Match the Malli shape "
                      "to the actual data shape: `[:map [:a :string] [:b :int]]`, NOT "
                      "`[:map :a :string :b :int]`. Nested map fields require `[:map [:k "
                      ":type]]` brace nesting.\n"
                      "- **Unbound symbols can fail during SCI analysis or later execution.** "
                      "A function body may be rejected before it is called; quoted emitted "
                      "source is analyzed when that source is compiled. Check that each name "
                      "is bound by the function inputs, a local binding, or a supported "
                      "sandbox capability. Do not infer when a failure occurs from its "
                      "position inside a function.\n"
                      (if checkpointed?
                        (str "- **In a checkpointed campaign, write every `:code` node's `:fn` "
                             "as a literal quoted `(fn ...)` inside the `emit-tree!` call — "
                             "never bind the tree or a function to a name first.** An "
                             "already-evaluated closure cannot be recorded as durable source "
                             "and the tree is rejected.\n")
                        "")
                      "- **A nil lookup is not proof of a failed producer.** "
                      "`(get-in nil [:k1 :k2])` returns nil. Nil may be a valid nullable "
                      "value or an absent path; inspect key presence, the declared schema "
                      "and producer outcome. Operations differ: `(count nil)` is zero, "
                      "while others reject nil. Validate what the next operation requires.\n"
                      "- **For-comprehensions are lazy until consumed.** Dropping an "
                      "unconsumed `(for [x xs] (process! x))` does not run its body. "
                      "`count` realizes a finite lazy sequence and therefore runs those "
                      "bodies; do not use count just to trigger effects. Use `mapv` or "
                      "`doall` when eager realization is intended, and keep effects "
                      "within the task's permitted capabilities.\n\n"

                      "## Output Contract\n"
                      "Authoritative declared write schemas:\n"
                      (pr-str write-schema-contract) "\n\n"
                      "You MUST call (final! {...}) with keys: " (pr-str (:writes node)) "\n"
                      "Every value passed to final! MUST satisfy its declared Malli schema exactly. "
                      "Closed maps reject undeclared keys; preserve nested, optional, collection, and union shapes. "
                      "The examples below are illustrative; this declared contract is authoritative.\n\n"
                      "## Code Value\n"
                      "Put RAW Clojure code in the `code` output field. "
                      "The field value must contain NO markdown code fences, "
                      "NO ```clojure or ``` tags, and NO marker/delimiter lines — "
                      "just the code itself. Use the response format specified by "
                      "the output contract.\n\n"
                      "## Example: Simple Analysis (small data)\n"
                      "For a workflow that declares :document, :work, :analysis, and final :answer as strings, one inline call can reuse the :analysis contract:\n"
                      "```clojure\n"
                      "(let [selected (get-input :document)]\n"
                      "  (store! :work selected)\n"
                      "  (let [result (llm \"analyze\"\n"
                      "                   :instruction \"Analyze this text\"\n"
                      "                   :reads [:work]\n"
                      "                   :writes [:analysis])]\n"
                      "    (final! {:answer (:analysis result)})))\n"
                      "```\n\n"
                      "## Example: Processing Large Data (PREFERRED - use emit-tree!)\n"
                      (if has-mcp?
                        "For large DATA processing (documents, collections), use emit-tree!; for workspace/file effects, call your bound tools directly inline:\n"
                        "For ANY large data (documents, collections, etc.), ALWAYS use emit-tree!:\n")
                      "```clojure\n"
                      "(emit-tree! (quote\n"
                      "  [:sequence\n"
                      "   [:chunk-document {:from :document :size 8000 :into :chunks}]\n"
                      "   [:map-each {:from :chunks :as :chunk :into :chunk_results\n"
                      "               :output-schemas {:chunk_results [:vector [:map [:info :string]]]}}\n"
                      "    [:llm {:instruction \"Extract key information from this section\"\n"
                      "           :reads [:chunk]\n"
                      "           :writes [:info]\n"
                      "           :output-schemas {:info :string}}]]\n"
                      "   [:aggregate {:from :chunk_results :writes [:all_info]\n"
                      "                :output-schemas {:all_info [:map-of :keyword [:vector :string]]}}]\n"
                      "   [:llm {:instruction \"Synthesize the extracted information into a final summary\"\n"
                      "          :reads [:all_info]\n"
                      "          :writes [:summary]\n"
                      "          :output-schemas {:summary :string}}]\n"
                      "   [:final {:keys [:summary]}]]))\n"
                      "```\n"
                      "This is the PREFERRED approach because:\n"
                      "- Tree structure is stored for learning and reuse\n"
                      "- Proper chunking with :chunk-document\n"
                      "- Clean separation of concerns\n"
                      "- Integrates with ORC behavior tree engine\n\n"
                      "## Your Task\n"
                      (:instruction node)
                      (when available-code-nodes
                        "\n\nA catalog of pre-built code-node functions is provided in the :available-code-nodes input above. Use them via [:code {:fn \"...\"}] in your emit-tree! tree when their semantics match what you need. Their input/output shapes are documented there.\n\n"))})))

(defn get-backoff
  "Get backoff duration for a given attempt (0-indexed)."
  [retry-config attempt]
  (let [backoff-ms (:backoff-ms retry-config)]
    (get backoff-ms (min attempt (dec (count backoff-ms))))))

(defn execute-with-retry
  "Execute a function with retry logic.

   Args:
     execute-fn - Zero-arg function that returns {:status :success/:failure ...}
     retry-config - {:max-attempts n :backoff-ms [100 500 2000]}
     node - (optional) the node map, for retry-attempt observability

   Returns the result of execute-fn, retrying on failure up to max-attempts."
  ([execute-fn retry-config]
   (execute-with-retry execute-fn retry-config nil))
  ([execute-fn retry-config node]
   (let [max-attempts (or (:max-attempts retry-config) 1)]
     (loop [attempt 0]
       (let [result (execute-fn)]
         (if (or (contains? #{:success :timeout :blocked} (:status result))
                 (false? (:retryable? result))
                 (>= (inc attempt) max-attempts))
           result
           (do
             (log-retry!
               {:node-id (:id node) :node-name (:name node)
                :attempt (inc attempt) :max-attempts max-attempts
                :reason (or (:error result) (str "status " (:status result)))
                :trace-id nil})
             (when-let [backoff (get-backoff retry-config attempt)]
               (Thread/sleep backoff))
             (recur (inc attempt)))))))))

(defn execute-leaf-mock
  "Mock executor that returns success with placeholder outputs.
   Useful for testing the behavior tree flow without AI calls."
  [node _blackboard]
  (let [start-time (System/currentTimeMillis)
        outputs (into {}
                      (map (fn [k] [k (str "mock-value-for-" k)])
                           (:writes node)))
        duration-ms (- (System/currentTimeMillis) start-time)]
    {:status :success
     :outputs outputs
     :duration-ms duration-ms}))

;; =============================================================================
;; Provider Setup
;; =============================================================================

(defn setup-providers!
  "Set up ORC LLM providers from environment variables.
   Call this at application startup."
  []
  (llm/quick-setup!))

(defn list-available-providers
  "List all registered ORC LLM providers."
  []
  (llm/list-providers))
