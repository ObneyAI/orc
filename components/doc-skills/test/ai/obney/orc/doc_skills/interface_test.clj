(ns ai.obney.orc.doc-skills.interface-test
  "Tests the public interface — sci-bindings, instructions, call-tool-fn."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.obney.orc.doc-skills.interface :as ds]
            [ai.obney.orc.doc-skills.core.pdf :as pdf]
            [clojure.java.io :as io])
  (:import [org.apache.pdfbox.pdmodel PDDocument PDPage PDPageContentStream]
           [org.apache.pdfbox.pdmodel.font PDType1Font Standard14Fonts$FontName]
           [java.io File]
           [java.nio.file Files]))

(def ^:dynamic *tmp-dir* nil)

(defn tmp-fixture [t]
  (let [dir (.toFile (Files/createTempDirectory "doc-skills-iface-" (into-array java.nio.file.attribute.FileAttribute [])))]
    (try
      (binding [*tmp-dir* dir] (t))
      (finally
        (doseq [^File f (reverse (file-seq dir))] (.delete f))))))

(use-fixtures :each tmp-fixture)

(defn- write-tiny-pdf! [out-path]
  (with-open [doc (PDDocument.)]
    (let [page (PDPage.)]
      (.addPage doc page)
      (with-open [cs (PDPageContentStream. doc page)]
        (.beginText cs)
        (.setFont cs (PDType1Font. Standard14Fonts$FontName/HELVETICA) 12)
        (.newLineAtOffset cs 50 700)
        (.showText cs "interface test page")
        (.endText cs)))
    (.save doc (io/file out-path)))
  out-path)

;; =============================================================================
;; SCI bindings shape
;; =============================================================================

(deftest sci-bindings-namespaced-test
  (let [bindings ds/sci-bindings]
    (is (contains? bindings 'pdf))
    (is (contains? bindings 'xlsx))
    (is (contains? bindings 'docx))
    (is (fn? (get-in bindings ['pdf 'page-count])))
    (is (fn? (get-in bindings ['xlsx 'write-workbook])))
    (is (fn? (get-in bindings ['docx 'write-docx])))))

(deftest sci-flat-bindings-test
  (let [flat ds/sci-flat-bindings]
    (is (contains? flat 'pdf/page-count))
    (is (contains? flat 'xlsx/write-workbook))
    (is (contains? flat 'docx/write-docx))
    (is (fn? (get flat 'pdf/page-count)))))

;; =============================================================================
;; Instructions
;; =============================================================================

(deftest instructions-loaded-test
  (testing "all four skill instruction docs load from resources"
    (doseq [k [:pdf :xlsx :docx :redaction]]
      (let [s (ds/instruction k)]
        (is (string? s))
        (is (pos? (count s)) (str "Instruction for " k " should be non-empty"))))))

(deftest compose-instructions-test
  (let [combined (ds/compose-instructions :pdf :xlsx)]
    (is (re-find #"PDF skill" combined))
    (is (re-find #"XLSX skill" combined))))

;; =============================================================================
;; call-tool-fn dispatch
;; =============================================================================

(deftest call-tool-fn-pdf-page-count
  (let [path (str (.getPath *tmp-dir*) "/iface.pdf")
        _ (write-tiny-pdf! path)
        dispatch (ds/call-tool-fn)]
    (is (= 1 (dispatch "pdf/page-count" {:path path})))))

(deftest call-tool-fn-pdf-page-text
  (let [path (str (.getPath *tmp-dir*) "/iface.pdf")
        _ (write-tiny-pdf! path)
        dispatch (ds/call-tool-fn)]
    (is (re-find #"interface test page"
                 (dispatch "pdf/page-text" {:path path :n 0})))))

(deftest call-tool-fn-xlsx-roundtrip
  (let [out (str (.getPath *tmp-dir*) "/x.xlsx")
        dispatch (ds/call-tool-fn)
        _ (dispatch "xlsx/write-workbook"
                    {:out-path out
                     :sheets-spec [{:name "S"
                                    :columns [{:header "A"} {:header "B"}]
                                    :rows [["x" 1] ["y" 2]]}]})
        rows (dispatch "xlsx/read-sheet-as-maps"
                       {:path out :sheet-name "S"})]
    (is (= [{:A "x" :B 1.0} {:A "y" :B 2.0}] rows))))

(deftest call-tool-fn-docx-write
  (let [out (str (.getPath *tmp-dir*) "/d.docx")
        dispatch (ds/call-tool-fn)
        path (dispatch "docx/write-docx"
                       {:out-path out
                        :elements [[:h1 "T"] [:p "body"]]})]
    (is (= out path))
    (is (.exists (io/file out)))))

(deftest call-tool-fn-unknown-test
  (let [dispatch (ds/call-tool-fn)
        result (dispatch "pdf/nope" {})]
    (is (= {:error "Unknown tool: pdf/nope"} result))))

(deftest all-tool-names-coverage-test
  (testing "every name in all-tool-names is dispatchable (does not return Unknown)"
    (let [dispatch (ds/call-tool-fn)
          ;; We can't actually call them all without setup, but we can use
          ;; with-redefs to avoid side effects; here we just verify the
          ;; dispatch table mentions each name (case branch reachable).
          ;; Easiest check: passing nil args doesn't return :error "Unknown".
          missing (filter (fn [name]
                            (let [r (try (dispatch name {})
                                         (catch Exception _ :threw))]
                              (and (map? r)
                                   (= (str "Unknown tool: " name) (:error r)))))
                          ds/all-tool-names)]
      (is (empty? missing) (str "Missing dispatch cases for: " (vec missing))))))

;; =============================================================================
;; Strict shape validation (NO silent slip-through on wrong/missing keys)
;; =============================================================================

(deftest call-tool-fn-rejects-wrong-keys-test
  (testing "Passing :path instead of :out-path on xlsx/write-workbook throws
            ex-info with a precise 'expected … got …' message — no silent
            nil defaulting."
    (let [dispatch (ds/call-tool-fn)]
      (try
        (dispatch "xlsx/write-workbook" {:path "/tmp/x.xlsx"
                                         :sheets [{:name "S" :rows []}]})
        (is false "Should have thrown")
        (catch Exception e
          (let [data (ex-data e)]
            (is (= :doc-skills/missing-keys (:type data)))
            (is (contains? (set (:missing data)) :out-path))
            (is (contains? (set (:missing data)) :sheets-spec))
            (is (re-find #":out-path" (.getMessage e)))
            (is (re-find #":sheets-spec" (.getMessage e)))))))))

(deftest call-tool-fn-rejects-missing-required-test
  (testing "Calling pdf/page-text with only :path (missing :n) throws
            with a clear missing-keys message"
    (let [dispatch (ds/call-tool-fn)]
      (try
        (dispatch "pdf/page-text" {:path "/tmp/x.pdf"})
        (is false "Should have thrown")
        (catch Exception e
          (let [data (ex-data e)]
            (is (= :doc-skills/missing-keys (:type data)))
            (is (= [:n] (:missing data)))))))))

(deftest call-tool-fn-rejects-non-map-args-test
  (testing "Passing a non-map (e.g. a string) as args throws with a clear
            'expects a single map argument' message"
    (let [dispatch (ds/call-tool-fn)]
      (try
        (dispatch "pdf/page-count" "/tmp/x.pdf")
        (is false "Should have thrown")
        (catch Exception e
          (let [data (ex-data e)]
            (is (= :doc-skills/bad-arg-shape (:type data)))
            (is (re-find #"single map argument" (.getMessage e)))))))))

(deftest call-tool-fn-accepts-correct-shape-test
  (testing "Validation does not interfere with correct calls (regression-safe)"
    ;; Use markdown->elements since it has no I/O side effects
    (let [dispatch (ds/call-tool-fn)
          result (dispatch "docx/markdown->elements" {:md "# Title\n\nbody"})]
      (is (vector? result))
      (is (= [:h1 "Title"] (first result))))))
