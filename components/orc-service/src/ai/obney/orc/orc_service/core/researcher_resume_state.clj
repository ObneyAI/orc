(ns ai.obney.orc.orc-service.core.researcher-resume-state
  "Durable encoding and verified hydration for researcher continuation state."
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(declare canonical-value)

(defn- ordered [values]
  (sort-by pr-str values))

(defn- canonical-value
  "Typed, traversal-order-independent data used as the sandbox hash preimage."
  [value]
  (cond
    (nil? value) [:nil]
    (record? value)
    (throw (ex-info "unsupported researcher sandbox value"
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
    (or (string? value) (boolean? value) (number? value) (char? value)
        (inst? value))
    [:scalar (.getName (class value)) (pr-str value)]
    :else
    (throw (ex-info "unsupported researcher sandbox value"
                    {:value-class (.getName (class value))}))))

(defn sandbox-hash
  "Stable SHA-256 identity of a complete sandbox value map."
  [sandbox]
  (let [bytes (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str (canonical-value sandbox))
                                  StandardCharsets/UTF_8))]
    (str "sha256:"
         (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes)))))

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

(defn hydrate
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
        (when-not (= expected actual)
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
          (when-not (= prior-hash (:predecessor-state-hash fact))
            (throw (ex-info "researcher sandbox delta predecessor hash mismatch"
                            {:revision (:revision fact)
                             :expected (:predecessor-state-hash fact)
                             :actual prior-hash})))
          (let [sandbox (merge (apply dissoc prior-sandbox
                                      (:sandbox-deletes fact))
                               (:sandbox-puts fact))
                actual (sandbox-hash sandbox)]
            (when-not (= actual (:resulting-state-hash fact))
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
    ;; Version 1 is the legacy checkpoint path. Version 2 is already hydrated.
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
    (reduce hydrate nil replay-facts)))

