(ns ai.obney.orc.orc-service.core.value-storage
  "Runtime-configured persistence for canonical execution values.

   A runtime chooses one mode for every value:
   - absent / :event-store keeps :value in the canonical event
   - :file-store writes Nippy bytes first and stores only a descriptor

   There are deliberately no size thresholds or value-shape policies."
  (:require [ai.obney.orc.file-store.interface :as fs]
            [ai.obney.orc.orc-service.core.profile :as profile]
            [cognitect.anomalies :as anom]
            [taoensso.nippy :as nippy])
  (:import (java.security MessageDigest)))

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
  "Prepare a canonical execution-value-written body according to runtime config.
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
      (when (or (::anom/category result) (:cognitect.anomalies/category result))
        (throw (ex-info "Failed to store Orc execution value"
                        {:file-id file-id :anomaly result})))
      (-> body
          (dissoc :value)
          (assoc :value-id value-id
                 :value-profile (profile/profile-value value)
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
  (if (and (= :sheet/execution-value-written (:event/type event))
           (:value-reference event))
    (assoc event :value (event-value context event))
    event))

(defn hydrate-events [context events]
  (mapv #(hydrate-event context %) events))
