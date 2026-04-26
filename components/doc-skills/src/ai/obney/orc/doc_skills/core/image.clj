(ns ai.obney.orc.doc-skills.core.image
  "Lightweight image helpers for SCI exposure.

   The SCI sandbox forbids Java interop, so LLM-authored Clojure code can't
   call `java.nio.file.Files/readAllBytes` or `java.util.Base64` directly.
   This namespace provides host-side helpers it can call instead."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.nio.file Files]
           [java.util Base64]))

(set! *warn-on-reflection* true)

(def ^:private ext->mime
  {".png"  "image/png"
   ".jpg"  "image/jpeg"
   ".jpeg" "image/jpeg"
   ".webp" "image/webp"
   ".gif"  "image/gif"
   ".bmp"  "image/bmp"})

(defn- guess-mime [^String path]
  (let [low (str/lower-case path)]
    (or (some (fn [[ext m]] (when (.endsWith low ^String ext) m)) ext->mime)
        "application/octet-stream")))

(defn encode-data-uri
  "Encode raw bytes as a `data:<mime>;base64,…` URI. The single source of
   truth for data-URI shape used by both `image/load-data-uri` and
   `pdf/page-image-data-uri`."
  [^String mime ^bytes bytes]
  (str "data:" mime ";base64," (.encodeToString (Base64/getEncoder) bytes)))

(defn load-data-uri
  "Read an image file from disk and return a `data:<mime>;base64,…` URI
   suitable for passing to a sub-LM as a vision input.

   Recognized extensions: png, jpg/jpeg, webp, gif, bmp. Anything else gets
   `application/octet-stream`.

   Throws if the file is missing."
  [path]
  (let [^File f (io/file path)]
    (when-not (.exists f)
      (throw (ex-info (str "Image file not found: " path)
                      {:type :image/not-found :path path})))
    (encode-data-uri (guess-mime (.getName f))
                     (Files/readAllBytes (.toPath f)))))

(defn file-info
  "Return basic metadata about an image file: {:path :name :size-bytes :mime}.
   Useful for letting the LLM see what's there before deciding whether to
   load the bytes."
  [path]
  (let [^File f (io/file path)]
    (when-not (.exists f)
      (throw (ex-info (str "Image file not found: " path)
                      {:type :image/not-found :path path})))
    {:path (.getAbsolutePath f)
     :name (.getName f)
     :size-bytes (.length f)
     :mime (guess-mime (.getName f))}))
