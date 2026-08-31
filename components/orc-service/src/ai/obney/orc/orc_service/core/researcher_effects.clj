(ns ai.obney.orc.orc-service.core.researcher-effects
  "Pure ownership-fence primitives for checkpointed researcher effects.

   The event store supplies `events` as an IReduce stream.  These predicates
   therefore consume it once and never assume Seqable semantics."
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util UUID)))

(def frontier-event-type :rlm/researcher-frontier-claimed)
(def claim-event-type :rlm/researcher-effect-claimed)
(def completion-event-type :rlm/researcher-effect-completed)

(defn campaign-tag-id
  "Stable event-store tag UUID for one researcher campaign occurrence."
  [sheet-id tick-id node-id]
  (UUID/nameUUIDFromBytes
   (.getBytes (pr-str [:researcher-campaign sheet-id tick-id node-id])
              StandardCharsets/UTF_8)))

(defn campaign-tag
  [sheet-id tick-id node-id]
  [:researcher-campaign (campaign-tag-id sheet-id tick-id node-id)])

(declare canonical-value)

(defn- ordered
  [values]
  (sort-by pr-str values))

(defn- canonical-value
  "Typed, traversal-order-independent data used only as a hash preimage."
  [value]
  (cond
    (nil? value) [:nil]
    ;; Records are maps to Clojure's predicates, but their runtime type is
    ;; part of their meaning. A registered checkpoint codec must turn them
    ;; into tagged durable data before they reach this boundary.
    (record? value)
    (throw (ex-info "unsupported researcher effect identity value"
                    {:value-class (.getName (class value))}))
    (map-entry? value) [:map-entry (canonical-value (key value))
                        (canonical-value (val value))]
    (map? value) [:map (->> value
                            (map (fn [[k v]] [(canonical-value k)
                                              (canonical-value v)]))
                            ordered
                            vec)]
    (set? value) [:set (->> value (map canonical-value) ordered vec)]
    (vector? value) [:vector (mapv canonical-value value)]
    (list? value) [:list (mapv canonical-value value)]
    (keyword? value) [:keyword (namespace value) (name value)]
    (symbol? value) [:symbol (namespace value) (name value)]
    (uuid? value) [:uuid (str value)]
    ;; Preserve the established scalar representation for durable EDN values
    ;; (including instants) while rejecting JVM identity-bearing fallbacks.
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
           arguments]}]
  (sha-256
   (canonical-value [tick-id node-id iteration-index generated-code-hash
                     kind target arguments])))

(defn provider-logical-action-identity
  "Stable pre-dispatch identity for a Phase-1 provider request.

   Provider intent cannot depend on the code that the request has not produced
   yet.  Its content is the canonical module, inputs, stable transport options,
   and provider/model target.  Deadline and local idempotency metadata are not
   request content and are excluded so replay time cannot change the identity."
  [{:keys [tick-id node-id iteration-index provider model module inputs options]}]
  (let [stable-options (dissoc (or options {}) :timeout-ms :orc/idempotency-key)]
    (sha-256
     (canonical-value [tick-id node-id iteration-index :provider provider model
                       module inputs stable-options]))))

(defn generated-code-hash
  "Stable digest of the exact generated source that invoked an inline effect."
  [source]
  (sha-256 (canonical-value source)))

(defn attempt-identity
  "Stable identity for one physical dispatch of a logical action."
  [logical-action-identity ownership-epoch attempt-ordinal]
  (when-not (and (integer? attempt-ordinal) (not (neg? attempt-ordinal)))
    (throw (ex-info "Effect attempt ordinal must be a non-negative integer"
                    {:attempt-ordinal attempt-ordinal})))
  (sha-256
   (canonical-value [logical-action-identity ownership-epoch attempt-ordinal])))

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
   :types #{frontier-event-type claim-event-type completion-event-type}
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

                state))
            {:frontier-epoch 0 :matching-claim? false :resolved? false}
            events)]
       (and (= candidate-epoch frontier-epoch)
            matching-claim?
            (not resolved?))))})
