(ns ai.obney.orc.ontology.connect2-source-relations-excel-test
  "CONNECT-2 (consumer side) — `source-relations-fn` now returns a FN for an EXCEL
   source (it was nil, because the excel container-contract's `:relations` was nil).
   This is the wiring that reaches MC-6: with a relations-fn in hand, MC-6's
   deterministic cross-sheet edge-derivation can fire for excel sources (O*NET),
   where before it never did → occupations got 0 cross-sheet edges.

   Hermetic tracer: a tiny temp dir of two single-sheet workbooks sharing an id-like
   `record_code` column. Plus a skip-if-absent LIVE assertion on the real O*NET dir
   that the relations-fn surfaces the shared SOC-code join through the consumer."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.ontology.core.extract-subbehavior :as extract])
  (:import [java.util.zip ZipOutputStream ZipEntry]
           [java.io File FileOutputStream]))

;; =============================================================================
;; Minimal .xlsx-dir fixture (zip-of-XML) — a directory of single-sheet workbooks.
;; =============================================================================

(defn- zentry! [^ZipOutputStream zos name ^String content]
  (.putNextEntry zos (ZipEntry. ^String name))
  (.write zos (.getBytes content "UTF-8"))
  (.closeEntry zos))

(def ^:private content-types
  "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/></Types>")

(def ^:private root-rels
  "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")

(defn- ss-xml [strings]
  (str "<?xml version=\"1.0\"?><sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"" (count strings) "\" uniqueCount=\"" (count strings) "\">"
       (apply str (map #(str "<si><t xml:space=\"preserve\">" % "</t></si>") strings))
       "</sst>"))

(defn- col-letter [i] (str (char (+ 65 i))))

(defn- row-xml [rnum cells]
  (str "<row r=\"" rnum "\">"
       (apply str (map-indexed
                   (fn [i s-idx]
                     (str "<c r=\"" (col-letter i) rnum "\" t=\"s\"><v>" s-idx "</v></c>"))
                   cells))
       "</row>"))

(defn- write-single-sheet-xlsx! [^File f sheet-name header data]
  (let [strings (vec (distinct (concat [sheet-name] header (mapcat identity data))))
        idx (into {} (map-indexed (fn [i s] [s i]) strings))]
    (with-open [zos (ZipOutputStream. (FileOutputStream. f))]
      (zentry! zos "[Content_Types].xml" content-types)
      (zentry! zos "_rels/.rels" root-rels)
      (zentry! zos "xl/workbook.xml"
               (str "<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"" sheet-name "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"))
      (zentry! zos "xl/_rels/workbook.xml.rels"
               "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/></Relationships>")
      (zentry! zos "xl/sharedStrings.xml" (ss-xml strings))
      (zentry! zos "xl/worksheets/sheet1.xml"
               (str "<?xml version=\"1.0\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
                    (row-xml 1 (mapv idx header))
                    (apply str (map-indexed (fn [ri row] (row-xml (+ 2 ri) (mapv idx row))) data))
                    "</sheetData></worksheet>")))
    f))

(defn- shared-key-dir! []
  (let [d (File. (System/getProperty "java.io.tmpdir")
                 (str "connect2-onto-" (System/nanoTime)))]
    (.mkdirs d)
    (write-single-sheet-xlsx! (File. d "Orders.xlsx") "Orders"
                              ["record_code" "amount"] [["A1" "10"] ["A2" "20"]])
    (write-single-sheet-xlsx! (File. d "Lines.xlsx") "Lines"
                              ["record_code" "qty"] [["A1" "3"] ["A2" "5"]])
    d))

;; =============================================================================
;; Tracer 6 — source-relations-fn returns a FN for an excel source (was nil).
;; =============================================================================

(deftest source-relations-fn-returns-fn-for-excel
  (testing "source-relations-fn returns a fn for an excel source (was nil — the
            excel :relations op landing in CONNECT-2 is what reaches MC-6)"
    (let [d (shared-key-dir!)
          f (extract/source-relations-fn {:type :excel :path (.getAbsolutePath d)})]
      (is (fn? f)
          "source-relations-fn now returns a fn for excel (RED before CONNECT-2: nil)")
      (testing "and the fn surfaces the shared-key relation MC-6 will join on"
        (let [rels (f "Orders")]
          (is (some (fn [r] (and (= "record_code" (:via r))
                                 (str/starts-with? (:from r) "Orders.")
                                 (str/starts-with? (:to r) "Lines.")))
                    rels)
              "Orders relates to Lines :via the shared id-like record_code column"))))))

;; =============================================================================
;; LIVE (skip-if-absent) — the real O*NET dir through the consumer.
;; =============================================================================

(def ^:private onet-dir "/Users/darylroberts/Downloads/db_30_1_excel")

(deftest source-relations-fn-surfaces-onet-soc-join-live
  (if-not (.isDirectory (File. onet-dir))
    (println "[connect2] SKIP — O*NET dir" onet-dir "absent")
    (testing "LIVE: through source-relations-fn, the real O*NET dir surfaces the
              Occupation Data -> content-sheet SOC join MC-6 will materialize"
      (let [f (extract/source-relations-fn {:type :excel :path onet-dir})]
        (is (fn? f) "source-relations-fn returns a fn for the real O*NET excel dir")
        (let [soc-rels (filter #(= "O*NET-SOC Code" (:via %)) (f "Occupation Data"))]
          (is (seq soc-rels)
              "LOAD-BEARING: the consumer sees SOC-code relations for Occupation Data")
          (is (some (fn [r] (contains? #{"Skills" "Abilities" "Task Statements" "Knowledge"}
                                       (first (str/split (:to r) #"\." 2))))
                    soc-rels)
              "Occupation Data relates to a skills/abilities/tasks/knowledge sheet :via SOC"))))))
