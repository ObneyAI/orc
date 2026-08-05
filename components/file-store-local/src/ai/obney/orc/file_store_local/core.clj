(ns ai.obney.orc.file-store-local.core
  (:require [ai.obney.orc.file-store.interface.protocol :as p]
            [com.brunobonacci.mulog :as u])
  (:import (java.nio.file Files Path Paths StandardOpenOption)))

(defn- storage-root [{:keys [storage-dir-path]}]
  (when-not (seq storage-dir-path)
    (throw (ex-info "Local file store requires :storage-dir-path" {})))
  (-> (Paths/get storage-dir-path (make-array String 0))
      .toAbsolutePath
      .normalize))

(defn- file-path [config file-id]
  (let [root (storage-root config)
        path (-> root (.resolve (str file-id)) .normalize)]
    (when-not (.startsWith path root)
      (throw (ex-info "File id escapes the local file-store directory"
                      {:file-id file-id :storage-dir-path (str root)})))
    path))

(defn put-file
  [{{:keys [storage-dir-path] :as config} :config}
   {:keys [file-id file-contents]}]
  (u/trace
   ::put-file
   [::file-id file-id ::storage-dir-path storage-dir-path]
   (let [^Path path (file-path config file-id)]
     (Files/createDirectories (.getParent path)
                              (make-array java.nio.file.attribute.FileAttribute 0))
     (Files/write path ^bytes file-contents
                  (into-array StandardOpenOption
                              [StandardOpenOption/CREATE
                               StandardOpenOption/TRUNCATE_EXISTING
                               StandardOpenOption/WRITE]))
     {})))

(defn get-file
  [{{:keys [storage-dir-path] :as config} :config}
   {:keys [file-id]}]
  (u/trace
   ::get-file
   [::file-id file-id ::storage-dir-path storage-dir-path]
   (Files/readAllBytes (file-path config file-id))))

(defn locate-file
  [{{:keys [storage-dir-path] :as config} :config}
   {:keys [file-id]}]
  (u/trace
   ::locate-file
   [::file-id file-id ::storage-dir-path storage-dir-path]
   {:file-path (str (file-path config file-id))}))

(defrecord LocalFileStore [config]
  p/FileStore
  (start [this]
    (Files/createDirectories (storage-root config)
                             (make-array java.nio.file.attribute.FileAttribute 0))
    this)
  (stop [this] this)
  (put-file [this args] (put-file this args))
  (get-file [this args] (get-file this args))
  (locate-file [this args] (locate-file this args)))

(defmethod p/start-file-store :local
  [config]
  (p/start (->LocalFileStore config)))
