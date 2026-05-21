(ns ai.obney.orc.predict-rlm-image-tools.interface-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [ai.obney.orc.predict-rlm-image-tools.interface :as img])
  (:import [java.util Base64]))

(def fixture-png
  (.getPath (io/resource "fixtures/tiny.png")))

(def ^:private png-signature
  (byte-array [(unchecked-byte 0x89)
               (unchecked-byte 0x50)
               (unchecked-byte 0x4E)
               (unchecked-byte 0x47)
               (unchecked-byte 0x0D)
               (unchecked-byte 0x0A)
               (unchecked-byte 0x1A)
               (unchecked-byte 0x0A)]))

(defn- decoded-bytes [^String data-uri]
  (let [b64 (subs data-uri (inc (.indexOf data-uri ",")))]
    (.decode (Base64/getDecoder) b64)))

(deftest png-data-uri-has-correct-prefix
  (testing "PNG file produces 'data:image/png;base64,...' URI"
    (let [uri (img/image->data-uri fixture-png)]
      (is (string? uri))
      (is (.startsWith ^String uri "data:image/png;base64,")))))

(deftest png-data-uri-decodes-back-to-png
  (testing "decoded payload begins with PNG signature"
    (let [uri (img/image->data-uri fixture-png)
          decoded (decoded-bytes uri)]
      (is (>= (alength decoded) 8))
      (is (every? true?
                  (for [i (range 8)]
                    (= (aget decoded i) (aget png-signature i))))))))

(deftest mime-detected-by-extension
  (testing "extension drives the MIME segment of the data URI"
    ;; We don't have fixture files for these formats — fake them by copying tiny.png
    ;; to a temp file with a different extension. The bytes don't have to be valid
    ;; for the format we claim; the function only inspects the extension for MIME.
    (let [src (io/file fixture-png)
          tmp (java.io.File/createTempFile "imgtools" ".tmp")
          rename (fn [^java.io.File f ^String ext]
                   (let [renamed (io/file (.getParent f) (str (.getName f) "." ext))]
                     (io/copy src renamed)
                     (.deleteOnExit renamed)
                     (.getPath renamed)))]
      (.delete tmp)
      (is (.startsWith ^String (img/image->data-uri (rename tmp "jpg"))  "data:image/jpeg;base64,"))
      (is (.startsWith ^String (img/image->data-uri (rename tmp "jpeg")) "data:image/jpeg;base64,"))
      (is (.startsWith ^String (img/image->data-uri (rename tmp "webp")) "data:image/webp;base64,"))
      (is (.startsWith ^String (img/image->data-uri (rename tmp "PNG"))  "data:image/png;base64,")
          "extension matching is case-insensitive"))))

(deftest unsupported-extension-throws-clear-error
  (testing "unknown extension throws ex-info with helpful data"
    (let [src (io/file fixture-png)
          tmp (java.io.File/createTempFile "imgtools" ".bmp")
          path (.getPath tmp)]
      (io/copy src tmp)
      (.deleteOnExit tmp)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unsupported image extension"
                            (img/image->data-uri path))))))
