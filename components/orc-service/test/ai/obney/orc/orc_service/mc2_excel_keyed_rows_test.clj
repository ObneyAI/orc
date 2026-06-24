(ns ai.obney.orc.orc-service.mc2-excel-keyed-rows-test
  "MC-2 — the Excel V06 specialist conforms to the MC-1 container contract by
   returning KEYED row maps (column-header → value) for `sample-rows` / `stream-all`,
   reusing the existing V05 `sheet-columns` header-detection (P8 — do NOT fork).

   Before MC-2 the excel specialist emitted POSITIONAL cell vectors (the one
   declared non-conformance MC-1 recorded, and the root cause of the O*NET extract
   blocker — Excel grounded to `[]`). After MC-2 an Excel source keys exactly like
   CSV/SQL.

   Tests, in tracer-bullet order, behavior through the PUBLIC excel tool surface +
   the format-agnostic `container-contract`:

   - `sample-rows` over a real O*NET sheet returns keyed maps whose keys are the
     detected column headers (NOT positional vectors).
   - PSEO (title-row case): keys are the REAL headers (`agg_level_pseo` …), not the
     leading title row.
   - `container-contract`'s excel `:sample-rows` / `:stream-all` return keyed rows
     for a directory container (`:path` + `:sheet`).
   - `stream-all` windows are keyed too, and still cover every data row.

   REAL fixtures, skip-if-absent (Discipline #4 — no fabricated row shapes):
     O*NET `~/Downloads/db_30_1_excel`, PSEO `~/Downloads/pseo_la.xlsx`.
   A synthetic header-on-row-1 + title-row workbook proves the keyed shape
   deterministically on every run (no committed binary)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.source-tools-excel :as ex]
            [ai.obney.orc.orc-service.core.source-tools :as st])
  (:import [java.util.zip ZipOutputStream ZipEntry]
           [java.io File FileOutputStream]))

;; =============================================================================
;; Synthetic .xlsx fixture builders (zip-of-XML, mirrors the v05 test approach)
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
       (apply str
              (keep-indexed
               (fn [i c]
                 (when c
                   (let [ref (str (col-letter i) rnum)]
                     (cond
                       (:s c) (str "<c r=\"" ref "\" t=\"s\"><v>" (:s c) "</v></c>")
                       (:n c) (str "<c r=\"" ref "\"><v>" (:n c) "</v></c>")
                       :else ""))))
               cells))
       "</row>"))

(defn- sheet-xml [rows-xml]
  (str "<?xml version=\"1.0\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
       (apply str rows-xml) "</sheetData></worksheet>"))

(defn- make-simple-xlsx!
  "Sheet 'People' header on ROW 1 (name/age/city) + 2 data rows. Header-is-row-1
   happy path for keyed-row assertions."
  [^File f]
  (with-open [zos (ZipOutputStream. (FileOutputStream. f))]
    (zentry! zos "[Content_Types].xml" content-types)
    (zentry! zos "_rels/.rels" root-rels)
    (zentry! zos "xl/workbook.xml"
             "<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"People\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
    (zentry! zos "xl/_rels/workbook.xml.rels"
             "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/></Relationships>")
    (zentry! zos "xl/sharedStrings.xml"
             (ss-xml ["name" "age" "city" "Alice" "London" "Bob" "Paris"]))
    (zentry! zos "xl/worksheets/sheet1.xml"
             (sheet-xml [(row-xml 1 [{:s 0} {:s 1} {:s 2}])
                         (row-xml 2 [{:s 3} {:n 30} {:s 4}])
                         (row-xml 3 [{:s 5} {:n 25} {:s 6}])])))
  f)

(defn- make-titled-xlsx!
  "PSEO-shaped: 3 title rows + 1 blank + header on ROW 5, then data. Proves keyed
   rows use the DETECTED header, not the leading title row."
  [^File f]
  (with-open [zos (ZipOutputStream. (FileOutputStream. f))]
    (zentry! zos "[Content_Types].xml" content-types)
    (zentry! zos "_rels/.rels" root-rels)
    (zentry! zos "xl/workbook.xml"
             "<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Earnings\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
    (zentry! zos "xl/_rels/workbook.xml.rels"
             "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/></Relationships>")
    (zentry! zos "xl/sharedStrings.xml"
             (ss-xml ["PSEO Earnings Louisiana" "Source: Census" "Release 2025Q4"
                      "institution" "cipcode" "earnings" "Tulane" "11.0701"]))
    (zentry! zos "xl/worksheets/sheet1.xml"
             (sheet-xml [(row-xml 1 [{:s 0}])
                         (row-xml 2 [{:s 1}])
                         (row-xml 3 [{:s 2}])
                         (row-xml 5 [{:s 3} {:s 4} {:s 5}])  ; HEADER
                         (row-xml 6 [{:s 6} {:s 7} {:n 52000}])
                         (row-xml 7 [{:s 6} {:s 7} {:n 61000}])])))
  f)

(defn- make-overcap-xlsx!
  "A single sheet 'Big' with header on row 1 (k0/k1) + many data rows; col0 of
   data row i equals i. Proves stream-all keyed coverage."
  [^File f n-rows]
  (with-open [zos (ZipOutputStream. (FileOutputStream. f))]
    (zentry! zos "[Content_Types].xml" content-types)
    (zentry! zos "_rels/.rels" root-rels)
    (zentry! zos "xl/workbook.xml"
             "<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Big\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
    (zentry! zos "xl/_rels/workbook.xml.rels"
             "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/></Relationships>")
    (zentry! zos "xl/sharedStrings.xml" (ss-xml ["k0" "k1"]))
    (zentry! zos "xl/worksheets/sheet1.xml"
             (sheet-xml (cons (row-xml 1 [{:s 0} {:s 1}])
                              (map (fn [i] (row-xml (+ 2 i) [{:n i} {:n (* i 2)}]))
                                   (range n-rows))))))
  f)

(defn- temp-file [suffix]
  (doto (File/createTempFile "mc2-fixture" suffix) (.deleteOnExit)))

(def ^:private onet-dir "/Users/darylroberts/Downloads/db_30_1_excel")
(def ^:private pseo-path "/Users/darylroberts/Downloads/pseo_la.xlsx")

;; =============================================================================
;; AC1 — sample-rows returns KEYED maps (synthetic header-row-1)
;; =============================================================================

(deftest sample-rows-keyed-by-header-synthetic
  (testing "sample-rows over a header-on-row-1 sheet returns column-name→value
            maps (NOT positional vectors); the header row is the KEY source, not a
            data row."
    (let [f (make-simple-xlsx! (temp-file ".xlsx"))
          tools (ex/excel-source-tools)
          r ((get tools 'sample-rows) (.getAbsolutePath f) "People")]
      (is (vector? (:rows r)))
      (is (map? (first (:rows r))) "each row is a keyed map, not a positional vector")
      (is (= #{"name" "age" "city"} (set (keys (first (:rows r))))))
      (is (= {"name" "Alice" "age" "30" "city" "London"} (first (:rows r))))
      (is (= {"name" "Bob" "age" "25" "city" "Paris"} (second (:rows r))))
      (is (= 2 (:row-count r)) "row-count counts DATA rows (header excluded)")
      (is (= ["name" "age" "city"] (:header r))
          "the detected header is surfaced alongside the keyed rows"))))

;; =============================================================================
;; AC2 — PSEO title-row case: keys are the REAL headers, not the title row
;; =============================================================================

(deftest sample-rows-keyed-skips-title-rows-synthetic
  (testing "When title/source/release lines precede the header, keyed rows use the
            DETECTED header (institution/cipcode/earnings), and the title rows are
            NOT emitted as data rows."
    (let [f (make-titled-xlsx! (temp-file ".xlsx"))
          tools (ex/excel-source-tools)
          r ((get tools 'sample-rows) (.getAbsolutePath f) "Earnings")]
      (is (every? map? (:rows r)) "all rows keyed")
      (is (= #{"institution" "cipcode" "earnings"} (set (keys (first (:rows r))))))
      (is (= {"institution" "Tulane" "cipcode" "11.0701" "earnings" "52000"}
             (first (:rows r))))
      (is (= 2 (:row-count r)) "only the 2 real data rows — title rows excluded")
      (is (= ["institution" "cipcode" "earnings"] (:header r))))))

(deftest real-pseo-sample-rows-keyed
  (testing "REAL PSEO Earnings (title-row case): keyed rows' keys are the real PSEO
            headers (agg_level_pseo …), NOT the leading 'Post-Secondary…' title."
    (if-not (.exists (File. pseo-path))
      (println "[MC-2] SKIP real-pseo — fixture absent at" pseo-path)
      (let [tools (ex/excel-source-tools)
            r ((get tools 'sample-rows) pseo-path "Earnings" 50)]
        (is (every? map? (:rows r)) "real PSEO rows are keyed maps")
        (let [ks (set (keys (first (:rows r))))]
          (is (contains? ks "agg_level_pseo")
              "keys are the detected PSEO header, not the title row")
          (is (not (some #(re-find #"(?i)post-secondary|source:|release:" (str %)) ks))
              "no title-row fragment leaked into the keys"))
        (is (> (count (:rows r)) 0) "real data rows returned")))))

;; =============================================================================
;; AC1 (real) — O*NET sample-rows keyed
;; =============================================================================

(deftest real-onet-sample-rows-keyed
  (testing "REAL O*NET Abilities.xlsx: sample-rows returns keyed maps whose keys
            are the detected O*NET column headers, not positional vectors."
    (if-not (.isDirectory (File. onet-dir))
      (println "[MC-2] SKIP real-onet — dir absent at" onet-dir)
      (let [p (str onet-dir "/Abilities.xlsx")
            tools (ex/excel-source-tools)
            r ((get tools 'sample-rows) p "Abilities" 5)]
        (is (every? map? (:rows r)) "O*NET rows are keyed maps, not positional")
        (let [ks (set (keys (first (:rows r))))]
          (is (contains? ks "O*NET-SOC Code"))
          (is (contains? ks "Element Name"))
          (is (contains? ks "Data Value")))
        (is (= "11-1011.00" (get (first (:rows r)) "O*NET-SOC Code"))
            "the keyed value aligns with the right column")
        (is (= "Chief Executives" (get (first (:rows r)) "Title")))))))

;; =============================================================================
;; AC3 — stream-all windows are keyed AND cover every data row
;; =============================================================================

(deftest stream-all-windows-keyed-and-cover
  (testing "stream-all windows carry KEYED rows and together cover every DATA row
            exactly once (header excluded from the data)."
    (let [f (make-overcap-xlsx! (temp-file ".xlsx") 1200)
          tools (ex/excel-source-tools)
          windows ((get tools 'stream-all) (.getAbsolutePath f) "Big" {:window 300})
          rows (mapcat :rows windows)]
      (is (> (count windows) 1) "multiple windows required")
      (is (every? map? rows) "every streamed row is a keyed map")
      (is (= #{"k0" "k1"} (set (keys (first rows)))))
      (is (= 1200 (count rows)) "every data row covered, header not double-counted")
      (is (= (range 0 1200)
             (mapv #(long (Double/parseDouble (str (get % "k0")))) rows))
          "data rows covered in order, exactly once, keyed by header"))))

;; =============================================================================
;; AC — container-contract excel :sample-rows / :stream-all yield keyed rows
;;       for a DIRECTORY container (:path + :sheet)
;; =============================================================================

(defn- make-xlsx-dir! ^File []
  (let [d (doto (File. (System/getProperty "java.io.tmpdir")
                       (str "mc2-xlsx-dir-" (System/nanoTime)))
            (.mkdirs) (.deleteOnExit))]
    (make-simple-xlsx! (doto (File. d "People.xlsx") (.deleteOnExit)))
    (make-titled-xlsx! (doto (File. d "Earnings.xlsx") (.deleteOnExit)))
    d))

(deftest container-contract-excel-dir-yields-keyed-rows
  (testing "container-contract's excel :sample-rows / :stream-all return keyed rows
            for a directory container, addressing the named (:path + :sheet)."
    (let [dir (make-xlsx-dir!)
          c   (st/container-contract {:type :excel :path (.getAbsolutePath dir)})
          containers ((:list-containers c))
          people (first (filter #(= "People" (:name %)) containers))]
      (is (= :excel (:format c)))
      (is (:path people))
      (is (= "People" (:sheet people)))
      ;; MC-4 — UNIFORM (container, opts) per-row call: pass the listed container
      ;; (carrying :path + :sheet); the contract routes the excel addressing.
      (let [r ((:sample-rows c) people {:limit 5})]
        (is (every? map? (:rows r)) "contract excel sample-rows are KEYED for a dir container")
        (is (= #{"name" "age" "city"} (set (keys (first (:rows r)))))))
      ;; stream-all over the named container -> keyed window rows
      (let [windows ((:stream-all c) people {:window 500})
            rows (mapcat :rows windows)]
        (is (every? map? rows) "contract excel stream-all windows are KEYED")
        (is (= #{"name" "age" "city"} (set (keys (first rows)))))))))

(deftest real-onet-container-contract-keyed
  (testing "container-contract over the REAL O*NET directory yields keyed rows for a
            named sheet — the live-QA the MC-1 blocker was about."
    (if-not (.isDirectory (File. onet-dir))
      (println "[MC-2] SKIP real-onet-contract — dir absent at" onet-dir)
      (let [c   (st/container-contract {:type :excel :path onet-dir})
            containers ((:list-containers c))
            ab  (first (filter #(= "Abilities" (:name %)) containers))]
        (is (some? ab) "Abilities container enumerated from the O*NET dir")
        ;; MC-4 — UNIFORM (container, opts) call over the listed container.
        (let [r ((:sample-rows c) ab {:limit 5})]
          (is (every? map? (:rows r)) "real O*NET contract sample-rows are KEYED")
          (is (contains? (set (keys (first (:rows r)))) "O*NET-SOC Code")))))))
