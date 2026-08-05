(ns ai.obney.orc.file-store.interface
  (:require [ai.obney.orc.file-store.interface.protocol :as p]))

(defmethod p/start-file-store :default
  [config]
  (throw (ex-info "Unknown file store type" {:type (:type config)})))

(defn start
  [config]
  (p/start-file-store config))

(defn stop
  [config]
  (p/stop config))

(defn put-file
  [file-store args]
  (p/put-file file-store args))

(defn get-file
  [file-store args]
  (p/get-file file-store args))

(defn locate-file
  [file-store args]
  (p/locate-file file-store args))
