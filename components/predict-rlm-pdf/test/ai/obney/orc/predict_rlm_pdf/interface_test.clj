(ns ai.obney.orc.predict-rlm-pdf.interface-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [ai.obney.orc.predict-rlm-pdf.interface :as pdf])
  (:import [java.util Base64]))

;; Fixtures copied from predict-rlm under MIT license.
;; See development/bench/predict-rlm-comparison/references/predict-rlm/LICENSE.
(def single-page-pdf
  (.getPath (io/resource "fixtures/sample-invoice.pdf")))

(def multi-page-pdf
  (.getPath (io/resource "fixtures/sample-employment-agreement.pdf")))

(def ^:private png-signature
  ;; PNG file magic bytes: 89 50 4E 47 0D 0A 1A 0A
  (byte-array [(unchecked-byte 0x89)
               (unchecked-byte 0x50)
               (unchecked-byte 0x4E)
               (unchecked-byte 0x47)
               (unchecked-byte 0x0D)
               (unchecked-byte 0x0A)
               (unchecked-byte 0x1A)
               (unchecked-byte 0x0A)]))

(defn- starts-with-png? [^bytes bs]
  (and (>= (alength bs) 8)
       (every? true?
               (for [i (range 8)]
                 (= (aget bs i) (aget png-signature i))))))

;; Sanity check on the fixtures themselves before exercising the interface
(deftest fixtures-are-loadable
  (testing "fixture PDF resources exist and are non-empty"
    (is (some? single-page-pdf) "single-page fixture must resolve")
    (is (some? multi-page-pdf) "multi-page fixture must resolve")
    (is (pos? (.length (io/file single-page-pdf))))
    (is (pos? (.length (io/file multi-page-pdf))))))

(deftest page-count-matches-fixtures
  (testing "page-count returns the exact page count for known fixtures"
    (is (= 1 (pdf/page-count single-page-pdf))
        "sample-invoice has 1 page")
    (is (= 6 (pdf/page-count multi-page-pdf))
        "sample-employment-agreement has 6 pages")))

(deftest render-pages-as-data-uris-returns-vector-with-correct-shape
  (testing "render-pages-as-data-uris produces one entry per page"
    (let [uris (pdf/render-pages-as-data-uris multi-page-pdf)]
      (is (vector? uris) "result is a vector")
      (is (= 6 (count uris)) "one URI per page")
      (doseq [[i u] (map-indexed vector uris)]
        (is (string? u) (str "entry " i " is a string"))
        (is (.startsWith ^String u "data:image/png;base64,")
            (str "entry " i " has data URI prefix")))))

  (testing "decoded payload of each URI starts with PNG signature"
    (let [uris (pdf/render-pages-as-data-uris single-page-pdf)
          payload (subs (first uris) (count "data:image/png;base64,"))
          decoded (.decode (Base64/getDecoder) ^String payload)]
      (is (starts-with-png? decoded)
          "decoded base64 payload begins with PNG file signature"))))

(deftest extract-pages-as-text-returns-text-per-page
  (testing "extract-pages-as-text produces one string per page"
    (let [pages (pdf/extract-pages-as-text multi-page-pdf)]
      (is (vector? pages))
      (is (= 6 (count pages)) "one string per page")
      (doseq [[i s] (map-indexed vector pages)]
        (is (string? s) (str "entry " i " is a string"))
        (is (pos? (count s)) (str "entry " i " is non-empty")))))

  (testing "extract-pages-as-text on the single-page invoice contains expected vendor text"
    (let [pages (pdf/extract-pages-as-text single-page-pdf)
          text  (first pages)]
      (is (= 1 (count pages)))
      ;; The acme invoice sample mentions "Acme" and an invoice number prefix.
      (is (.contains ^String text "Acme")
          "page text should contain 'Acme'"))))

(deftest render-pages-dpi-affects-output-size
  (testing "higher DPI produces larger PNG byte counts than lower DPI"
    (let [low  (pdf/render-pages-as-data-uris single-page-pdf {:dpi 75})
          high (pdf/render-pages-as-data-uris single-page-pdf {:dpi 300})
          decoder (Base64/getDecoder)
          payload-bytes (fn [uri]
                          (let [b64 (subs uri (count "data:image/png;base64,"))]
                            (alength (.decode decoder ^String b64))))]
      (is (= (count low) (count high)) "DPI does not change page count")
      (is (> (payload-bytes (first high))
             (payload-bytes (first low)))
          "300 DPI render produces strictly more bytes than 75 DPI"))))
