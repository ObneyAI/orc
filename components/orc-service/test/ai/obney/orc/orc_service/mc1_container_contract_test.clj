(ns ai.obney.orc.orc-service.mc1-container-contract-test
  "MC-1 — the uniform CONTAINER contract + format/directory resolution.

   This slice DECLARES the format-agnostic container contract every per-format
   V06 specialist conforms to (sql/csv FULLY; excel rows are positional until
   MC-2) and FIXES source resolution so a `:type`-keyed descriptor and a
   directory-of-workbooks resolve to real tools instead of throwing
   \"no source tools for descriptor\".

   Tests, in tracer-bullet order (behavior through the PUBLIC `source-tools`
   surface, never the per-format internals):

   - Resolution: `{:type :excel :path <dir>}` and `{:format :excel}` with no
     resolvable extension both resolve to the excel tool-map (RED on pre-fix).
   - Resolution: a directory path with no `:type`/`:format` resolves to :excel.
   - Discipline #5: a genuinely-unknown `:type`/`:format` STILL throws (no silent
     skip); a blank path STILL throws.
   - Contract conformance: each specialist (csv/sql/excel) exposes the four
     contract operations through `container-contract`, returning the declared
     uniform shapes (list-containers → [{:name …}]; sample-rows → keyed maps for
     csv/sql; excel positional is the declared MC-2 gap, asserted as such).

   Fixtures are built at runtime (no committed binaries): a tiny SQLite DB, a
   tiny CSV, and a temp directory of synthetic .xlsx workbooks. A SEPARATE live
   verification against the real IPEDS / cip_soc_crosswalk / O*NET fixtures is
   run out-of-band (Discipline #4); these durable tests guard the contract on
   every run."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.core.source-tools :as st])
  (:import [java.io File FileOutputStream]
           [java.util.zip ZipOutputStream ZipEntry]
           [java.sql DriverManager]))

;; =============================================================================
;; Synthetic fixtures (runtime, no committed binaries)
;; =============================================================================

(defn- temp-file [prefix suffix]
  (doto (File/createTempFile prefix suffix) (.deleteOnExit)))

(defn- make-csv! ^File [^File f]
  (spit f "CIP_Code,CIP_Title,SOC_Code\n01.0000,Agriculture General,19-1011\n11.0701,Computer Science,15-1252\n")
  f)

(defn- make-sqlite! ^File [^File f]
  ;; Build a 2-table DB with a real foreign key so `relations` has something to
  ;; return (proves the contract maps onto foreign-keys, not just empty []).
  (.delete f)
  (with-open [conn (DriverManager/getConnection (str "jdbc:sqlite:" (.getAbsolutePath f)))
              st   (.createStatement conn)]
    (.execute st "CREATE TABLE department (id INTEGER PRIMARY KEY, name TEXT)")
    (.execute st (str "CREATE TABLE employee (id INTEGER PRIMARY KEY, name TEXT, "
                      "dept_id INTEGER, FOREIGN KEY(dept_id) REFERENCES department(id))"))
    (.execute st "INSERT INTO department VALUES (1, 'Engineering')")
    (.execute st "INSERT INTO employee VALUES (1, 'Ada', 1)"))
  f)

;; --- minimal synthetic .xlsx (mirrors the v05 test's zip-of-XML approach) ---

(def ^:private content-types
  "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/></Types>")

(def ^:private root-rels
  "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")

(defn- zentry! [^ZipOutputStream zos name ^String content]
  (.putNextEntry zos (ZipEntry. ^String name))
  (.write zos (.getBytes content "UTF-8"))
  (.closeEntry zos))

(defn- make-xlsx! ^File [^File f sheet-name]
  (with-open [zos (ZipOutputStream. (FileOutputStream. f))]
    (zentry! zos "[Content_Types].xml" content-types)
    (zentry! zos "_rels/.rels" root-rels)
    (zentry! zos "xl/workbook.xml"
             (str "<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"" sheet-name "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"))
    (zentry! zos "xl/_rels/workbook.xml.rels"
             "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/></Relationships>")
    (zentry! zos "xl/sharedStrings.xml"
             "<?xml version=\"1.0\"?><sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"3\" uniqueCount=\"3\"><si><t>colA</t></si><si><t>colB</t></si><si><t>val1</t></si></sst>")
    (zentry! zos "xl/worksheets/sheet1.xml"
             "<?xml version=\"1.0\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row><row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"B2\"><v>42</v></c></row></sheetData></worksheet>"))
  f)

(defn- make-xlsx-dir! ^File []
  (let [d (doto (File. (System/getProperty "java.io.tmpdir")
                       (str "mc1-xlsx-dir-" (System/nanoTime)))
            (.mkdirs) (.deleteOnExit))]
    (make-xlsx! (doto (File. d "Abilities.xlsx") (.deleteOnExit)) "Abilities")
    (make-xlsx! (doto (File. d "Interests.xlsx") (.deleteOnExit)) "Interests")
    d))

;; =============================================================================
;; AC — Resolution fix (RED on pre-fix code)
;; =============================================================================

(deftest type-keyed-excel-directory-resolves-to-tools
  (testing "{:type :excel :path <dir>} resolves to the excel tool-map (not a throw)."
    (let [dir   (make-xlsx-dir!)
          tools (st/source-tools-for {:type :excel :path (.getAbsolutePath dir)})]
      (is (map? tools) "a :type-keyed excel directory descriptor must build tools")
      (is (contains? tools 'excel-dir-sheets)
          "the excel leg's directory enumerator must be present")
      (is (contains? tools 'list-sheets)))))

(deftest explicit-format-no-extension-resolves
  (testing "{:format :excel} with a directory path (no extension) resolves to excel tools."
    (let [dir   (make-xlsx-dir!)
          tools (st/source-tools-for {:format :excel :path (.getAbsolutePath dir)})]
      (is (map? tools))
      (is (contains? tools 'excel-dir-sheets)))))

(deftest bare-directory-path-resolves-to-excel
  (testing "A directory path with NO :type/:format resolves to :excel via the dir check."
    (let [dir (make-xlsx-dir!)]
      (is (= :excel (st/detect-format {:path (.getAbsolutePath dir)})))
      (is (map? (st/source-tools-for {:path (.getAbsolutePath dir)}))))))

(deftest type-keyed-sql-and-csv-resolve
  (testing ":type is honored as a fallback for :format on sql/csv too."
    (let [db  (make-sqlite! (temp-file "mc1-db" ".db"))
          csv (make-csv! (temp-file "mc1-csv" ".csv"))]
      (is (= :sql (st/detect-format {:type :sql :path (.getAbsolutePath db)})))
      (is (contains? (st/source-tools-for {:type :sql :path (.getAbsolutePath db)})
                     'list-tables))
      (is (= :csv (st/detect-format {:type :csv :path (.getAbsolutePath csv)})))
      (is (contains? (st/source-tools-for {:type :csv :path (.getAbsolutePath csv)})
                     'sample-rows)))))

;; =============================================================================
;; AC — Discipline #5: loud on genuinely-unknown / blank (no silent skip)
;; =============================================================================

(deftest unknown-type-still-throws
  (testing "An unknown :type still throws — :type fallback must NOT mask Discipline #5."
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown source format"
          (st/source-tools-for {:type :parquet :path "/tmp/x.parquet"})))))

(deftest unknown-format-still-throws
  (testing "An unknown :format still throws."
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown source format"
          (st/source-tools-for {:format :avro :path "/tmp/x.avro"})))))

(deftest blank-path-still-throws
  (testing "A recognized format with a blank path still throws (no source-less grant)."
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires a non-blank :path"
          (st/source-tools-for {:type :sql :path ""})))))

;; =============================================================================
;; AC — Contract conformance: each specialist exposes the four operations
;; =============================================================================

(deftest contract-declares-four-operations
  (testing "The contract operation set is the four uniform logical names."
    (is (= #{:list-containers :sample-rows :stream-all :relations}
           st/contract-operations))))

(deftest sql-conforms-to-container-contract
  (testing "sql exposes all four operations; list/sample/stream are FULLY conformant."
    (let [db (make-sqlite! (temp-file "mc1-db" ".db"))
          c  (st/container-contract {:type :sql :path (.getAbsolutePath db)})]
      (is (= :sql (:format c)))
      (is (every? fn? (map c [:list-containers :sample-rows :stream-all :relations]))
          "sql implements every contract operation as a bound fn")
      ;; list-containers -> uniform [{:name …}] (tables wrapped)
      (let [containers ((:list-containers c))]
        (is (= [{:name "department"} {:name "employee"}]
               (vec (sort-by :name containers)))
            "list-containers wraps tables into the uniform [{:name …}] shape"))
      ;; MC-4 — UNIFORM (container, opts): the container's :name is the table.
      (let [rows ((:sample-rows c) {:name "employee"} {:limit 5})]
        (is (vector? rows))
        (is (map? (first rows)) "sql sample-rows yields keyed maps, not positional")
        (is (= #{:id :name :dept_id} (set (keys (first rows))))))
      ;; relations -> declared foreign keys (proves it maps onto foreign-keys)
      (let [rels ((:relations c) "employee")]
        (is (= [{:from-table "employee" :from-column "dept_id"
                 :to-table "department" :to-column "id"}]
               rels)
            "relations maps onto the declared FK edge")))))

(deftest csv-conforms-to-container-contract
  (testing "csv synthesizes a single container; sample/stream are keyed."
    (let [csv (make-csv! (temp-file "mc1-csv" ".csv"))
          c   (st/container-contract {:type :csv :path (.getAbsolutePath csv)})]
      (is (= :csv (:format c)))
      ;; single container, synthesized to the uniform [{:name …}] shape
      (let [containers ((:list-containers c))]
        (is (= 1 (count containers)))
        (is (str/ends-with? (:name (first containers)) ".csv")))
      ;; MC-4 — UNIFORM (container, opts): csv is a single container, opts only.
      (let [{:keys [rows]} ((:sample-rows c) (first ((:list-containers c))) {:limit 2})]
        (is (map? (first rows)))
        (is (= #{"CIP_Code" "CIP_Title" "SOC_Code"} (set (keys (first rows))))))
      ;; csv has no standalone relations tool (crosswalk hints ride on peek-columns)
      (is (nil? (:relations c))
          "csv exposes no standalone relations op — the contract records that honestly"))))

(deftest excel-conforms-to-container-contract-keyed-rows
  (testing "excel exposes list-containers across a dir; rows are KEYED maps (MC-2)."
    (let [dir (make-xlsx-dir!)
          c   (st/container-contract {:type :excel :path (.getAbsolutePath dir)})]
      (is (= :excel (:format c)))
      (is (fn? (:list-containers c)))
      (is (fn? (:sample-rows c)))
      (is (fn? (:stream-all c)))
      ;; list-containers across the directory -> one container per sheet, :name keyed
      (let [containers ((:list-containers c))]
        (is (= #{"Abilities" "Interests"} (set (map :name containers)))
            "excel list-containers enumerates the dir's sheets into uniform [{:name …}]")
        (is (every? :path containers) "each excel container carries its workbook :path"))
      ;; MC-2 CONFORMANCE: excel sample-rows now returns KEYED column-header→value
      ;; maps (the synthetic fixture's sheet header is colA/colB), uniform with
      ;; csv/sql — the declared MC-1 non-conformance is closed. The fixture sheet
      ;; has header row [colA colB] + one data row [val1 42].
      (let [container (first ((:list-containers c)))
            result ((:sample-rows c) container {:limit 5})]
        (is (vector? (:rows result)))
        (is (map? (first (:rows result)))
            "excel rows are now KEYED maps (MC-2), not positional cell vectors")
        (is (= #{"colA" "colB"} (set (keys (first (:rows result)))))
            "keys are the detected column headers")
        (is (= {"colA" "val1" "colB" "42"} (first (:rows result)))
            "the single data row is keyed by header; the header row is excluded")))))
