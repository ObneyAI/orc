(ns ai.obney.orc.doc-skills.pdf-test
  "Roundtrip tests for the PDFBox wrapper. Generates a tiny PDF in-test
   so no fixture files are required."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.obney.orc.doc-skills.core.pdf :as pdf]
            [clojure.java.io :as io])
  (:import [org.apache.pdfbox.pdmodel PDDocument PDPage PDPageContentStream]
           [org.apache.pdfbox.pdmodel.font PDType1Font Standard14Fonts$FontName]
           [java.io File]
           [java.nio.file Files]))

(def ^:dynamic *tmp-dir* nil)

(defn tmp-fixture [t]
  (let [dir (.toFile (Files/createTempDirectory "doc-skills-pdf-" (into-array java.nio.file.attribute.FileAttribute [])))]
    (try
      (binding [*tmp-dir* dir] (t))
      (finally
        (doseq [^File f (reverse (file-seq dir))]
          (.delete f))))))

(use-fixtures :each tmp-fixture)

(defn- write-tiny-pdf!
  "Write a 2-page PDF with controlled text to `out-path`. Returns out-path."
  [out-path]
  (with-open [doc (PDDocument.)]
    (doseq [[_idx text] [[0 "Page one. Hello world. SECRET_TOKEN_AAA appears here."]
                         [1 "Page two. Final notes."]]]
      (let [page (PDPage.)]
        (.addPage doc page)
        (with-open [cs (PDPageContentStream. doc page)]
          (.beginText cs)
          (.setFont cs (PDType1Font. Standard14Fonts$FontName/HELVETICA) 12)
          (.newLineAtOffset cs 50 700)
          (.showText cs ^String text)
          (.endText cs))))
    (.save doc (io/file out-path)))
  out-path)

(deftest page-count-test
  (let [pdf-path (str (.getPath *tmp-dir*) "/tiny.pdf")
        _ (write-tiny-pdf! pdf-path)]
    (is (= 2 (pdf/page-count pdf-path)))))

(deftest page-text-roundtrip-test
  (let [pdf-path (str (.getPath *tmp-dir*) "/tiny.pdf")
        _ (write-tiny-pdf! pdf-path)]
    (testing "page-text returns text written for that page"
      (is (re-find #"Page one" (pdf/page-text pdf-path 0)))
      (is (re-find #"SECRET_TOKEN_AAA" (pdf/page-text pdf-path 0)))
      (is (re-find #"Page two" (pdf/page-text pdf-path 1)))
      (is (not (re-find #"Page one" (pdf/page-text pdf-path 1)))))))

(deftest document-text-test
  (let [pdf-path (str (.getPath *tmp-dir*) "/tiny.pdf")
        _ (write-tiny-pdf! pdf-path)
        full (pdf/document-text pdf-path)]
    (is (re-find #"Page one" full))
    (is (re-find #"Page two" full))))

(deftest page-image-test
  (let [pdf-path (str (.getPath *tmp-dir*) "/tiny.pdf")
        _ (write-tiny-pdf! pdf-path)
        out-path (str (.getPath *tmp-dir*) "/page-0.png")
        result (pdf/page-image pdf-path 0 out-path :dpi 72)]
    (is (= out-path result))
    (is (.exists (io/file out-path)))
    (is (pos? (.length (io/file out-path))))))

(deftest page-image-data-uri-test
  (let [pdf-path (str (.getPath *tmp-dir*) "/tiny.pdf")
        _ (write-tiny-pdf! pdf-path)
        uri (pdf/page-image-data-uri pdf-path 0 :dpi 72)]
    (is (string? uri))
    (is (.startsWith ^String uri "data:image/png;base64,"))))

(deftest page-bounds-test
  (let [pdf-path (str (.getPath *tmp-dir*) "/tiny.pdf")
        _ (write-tiny-pdf! pdf-path)
        bounds (pdf/page-bounds pdf-path 0)]
    (is (number? (:width bounds)))
    (is (number? (:height bounds)))
    (is (pos? (:width bounds)))
    (is (pos? (:height bounds)))))

(deftest search-text-test
  (let [pdf-path (str (.getPath *tmp-dir*) "/tiny.pdf")
        _ (write-tiny-pdf! pdf-path)
        hits (pdf/search-text pdf-path 0 "SECRET_TOKEN_AAA")]
    (is (seq hits) "Should find at least one match")
    (let [{:keys [rect match]} (first hits)]
      (is (= "SECRET_TOKEN_AAA" match))
      (is (= 4 (count rect))))))

(deftest redact-rects-test
  (let [pdf-path (str (.getPath *tmp-dir*) "/tiny.pdf")
        _ (write-tiny-pdf! pdf-path)
        out-path (str (.getPath *tmp-dir*) "/redacted.pdf")
        ;; Redact a generous box covering the SECRET token area on page 0
        result (pdf/redact-rects pdf-path out-path
                                 [{:page 0 :rect [50 690 580 720] :fill [0 0 0]}])]
    (is (= out-path result))
    (is (.exists (io/file out-path)))
    (is (= 2 (pdf/page-count out-path)))))
