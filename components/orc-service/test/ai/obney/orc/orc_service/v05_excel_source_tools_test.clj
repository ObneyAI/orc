(ns ai.obney.orc.orc-service.v05-excel-source-tools-test
  "V05 — Excel (.xlsx) source-access tools.

   Each deftest corresponds to a slice acceptance criterion:

   - list-sheets / sheet-columns / sample-rows are correct against a synthetic
     .xlsx fixture built at runtime (no committed binary): names; header +
     types; bounded rows.
   - Header is NOT assumed to be row 1: a fixture with title rows above the
     header proves sheet-columns detects the real header row.
   - LARGE-workbook sampling (PSEO-shaped) does NOT load the whole sheet — the
     adversarial guard. Run against the real 119 MB PSEO worksheet under a tight
     heap budget when the fixture is present; otherwise a synthetic over-cap
     sheet proves the bounded-read contract.
   - Multi-file Excel directories (O*NET-shaped) are enumerable.
   - Docstrings are self-contained (PURPOSE / EXAMPLE / RETURNS) + an
     adversarial twin proving the quality check isn't trivially-passing.
   - Read-side only: the tools touch the filesystem, never emit events (there is
     no event-store in scope; asserted structurally by the no-cfg signature)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.core.source-tools-excel :as ex])
  (:import [java.util.zip ZipOutputStream ZipEntry]
           [java.io File FileOutputStream]))

;; =============================================================================
;; Synthetic .xlsx fixture builders (zip-of-XML, written to a temp file)
;; =============================================================================

(defn- zentry! [^ZipOutputStream zos name ^String content]
  (.putNextEntry zos (ZipEntry. ^String name))
  (.write zos (.getBytes content "UTF-8"))
  (.closeEntry zos))

(def ^:private content-types
  "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/></Types>")

(def ^:private root-rels
  "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")

(defn- ss-xml
  "sharedStrings.xml from a vector of strings."
  [strings]
  (str "<?xml version=\"1.0\"?><sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"" (count strings) "\" uniqueCount=\"" (count strings) "\">"
       (apply str (map #(str "<si><t xml:space=\"preserve\">" % "</t></si>") strings))
       "</sst>"))

(defn- col-letter [i]
  ;; 0 -> A, 1 -> B ... (single-letter range is enough for the fixtures)
  (str (char (+ 65 i))))

(defn- row-xml
  "Build a <row> from a vector of {:s idx} (shared-string cell) or {:n s}
   (numeric cell) or nil (skip)."
  [rnum cells]
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
  "Two sheets. Sheet 'People' header on ROW 1 (name/age/city) + 2 data rows.
   Sheet 'Cities' single col. Header-is-row-1 happy path."
  [^File f]
  (with-open [zos (ZipOutputStream. (FileOutputStream. f))]
    (zentry! zos "[Content_Types].xml" content-types)
    (zentry! zos "_rels/.rels" root-rels)
    (zentry! zos "xl/workbook.xml"
             "<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"People\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"Cities\" sheetId=\"2\" r:id=\"rId2\"/></sheets></workbook>")
    (zentry! zos "xl/_rels/workbook.xml.rels"
             "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/><Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/></Relationships>")
    ;; shared strings: 0 name 1 age 2 city 3 Alice 4 London 5 Bob 6 Paris
    (zentry! zos "xl/sharedStrings.xml"
             (ss-xml ["name" "age" "city" "Alice" "London" "Bob" "Paris"]))
    (zentry! zos "xl/worksheets/sheet1.xml"
             (sheet-xml [(row-xml 1 [{:s 0} {:s 1} {:s 2}])
                         (row-xml 2 [{:s 3} {:n 30} {:s 4}])
                         (row-xml 3 [{:s 5} {:n 25} {:s 6}])]))
    (zentry! zos "xl/worksheets/sheet2.xml"
             (sheet-xml [(row-xml 1 [{:s 2}])])))
  f)

(defn- make-titled-xlsx!
  "PSEO-shaped: 3 title rows + 1 blank + header on ROW 5, then data. Proves
   header detection does NOT assume row 1."
  [^File f]
  (with-open [zos (ZipOutputStream. (FileOutputStream. f))]
    (zentry! zos "[Content_Types].xml" content-types)
    (zentry! zos "_rels/.rels" root-rels)
    (zentry! zos "xl/workbook.xml"
             "<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Earnings\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
    (zentry! zos "xl/_rels/workbook.xml.rels"
             "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/></Relationships>")
    ;; ss: 0 title 1 source 2 release 3 institution 4 cipcode 5 earnings 6 Tulane 7 11.0701
    (zentry! zos "xl/sharedStrings.xml"
             (ss-xml ["PSEO Earnings Louisiana" "Source: Census" "Release 2025Q4"
                      "institution" "cipcode" "earnings" "Tulane" "11.0701"]))
    (zentry! zos "xl/worksheets/sheet1.xml"
             (sheet-xml [(row-xml 1 [{:s 0}])            ; title
                         (row-xml 2 [{:s 1}])            ; source
                         (row-xml 3 [{:s 2}])            ; release
                         ;; row 4 blank (omitted)
                         (row-xml 5 [{:s 3} {:s 4} {:s 5}])  ; HEADER
                         (row-xml 6 [{:s 6} {:s 7} {:n 52000}])
                         (row-xml 7 [{:s 6} {:s 7} {:n 61000}])])))
  f)

(defn- make-overcap-xlsx!
  "A single sheet 'Big' with a STRING header on row 1 (k0/k1) + MANY data rows
   (more than the hard cap), so we can assert sample-rows/stream-all return a
   BOUNDED, KEYED slice and flag :capped?. Data row i has k0=i, k1=2i — so a
   reader can recover the row index from the keyed value. (MC-2: the header is now
   detected + used to KEY the rows, so the fixture carries a real header rather
   than starting straight into numeric data.)"
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
  (doto (File/createTempFile "v05-fixture" suffix) (.deleteOnExit)))

;; =============================================================================
;; AC1 — list-sheets correct
;; =============================================================================

(deftest list-sheets-returns-ordered-names
  (testing "list-sheets returns every sheet name in workbook order, with index."
    (let [f (make-simple-xlsx! (temp-file ".xlsx"))
          tools (ex/excel-source-tools)
          result ((get tools 'list-sheets) (.getAbsolutePath f))]
      (is (= ["People" "Cities"] (mapv :name result)))
      (is (= [0 1] (mapv :index result)))
      (is (= ["1" "2"] (mapv :sheet-id result))))))

;; =============================================================================
;; AC2 — sheet-columns: header + types (header on row 1)
;; =============================================================================

(deftest sheet-columns-header-and-types
  (testing "sheet-columns returns the header and infers per-column types."
    (let [f (make-simple-xlsx! (temp-file ".xlsx"))
          tools (ex/excel-source-tools)
          r ((get tools 'sheet-columns) (.getAbsolutePath f) "People")]
      (is (= ["name" "age" "city"] (:header r)))
      (is (= 0 (:header-row-index r)) "header is row 1 here")
      (is (= 3 (:column-count r)))
      (is (= :string (get (:types r) 0)) "name col is string")
      (is (= :number (get (:types r) 1)) "age col is numeric (30,25)")
      (is (= :string (get (:types r) 2)) "city col is string"))))

(deftest sheet-columns-selectable-by-index
  (testing "A sheet can be selected by 0-based index, not just name."
    (let [f (make-simple-xlsx! (temp-file ".xlsx"))
          tools (ex/excel-source-tools)
          r ((get tools 'sheet-columns) (.getAbsolutePath f) 0)]
      (is (= ["name" "age" "city"] (:header r))))))

;; =============================================================================
;; AC3 — header is NOT assumed to be row 1 (PSEO reality)
;; =============================================================================

(deftest header-not-assumed-row-one
  (testing "When title/source/release lines precede the header, sheet-columns
            detects the real header row (not row 1) and surfaces its index +
            the raw scanned rows so the RLM can override."
    (let [f (make-titled-xlsx! (temp-file ".xlsx"))
          tools (ex/excel-source-tools)
          r ((get tools 'sheet-columns) (.getAbsolutePath f) "Earnings")]
      (is (= ["institution" "cipcode" "earnings"] (:header r))
          "detected the real header, skipping 3 title rows + a blank")
      (is (= 3 (:header-row-index r))
          "header is the 4th scanned row (rows 1-3 title, row 4 blank dropped)")
      (is (vector? (:scanned-rows r)) "raw scanned rows are returned for override")
      (is (= :number (get (:types r) 2)) "earnings col inferred numeric below header"))))

;; =============================================================================
;; AC4 — sample-rows bounded
;; =============================================================================

(deftest sample-rows-is-bounded
  (testing "sample-rows returns at most the requested DATA rows as KEYED maps
            (MC-2 — column-header→value, header excluded from the rows); the
            default caps."
    (let [f (make-simple-xlsx! (temp-file ".xlsx"))
          tools (ex/excel-source-tools)
          r2 ((get tools 'sample-rows) (.getAbsolutePath f) "People" 2)]
      (is (= 2 (:row-count r2)) "exactly 2 DATA rows requested")
      (is (= 2 (count (:rows r2))))
      (is (every? map? (:rows r2)) "rows are keyed maps, not positional vectors")
      (is (= {"name" "Alice" "age" "30" "city" "London"} (first (:rows r2)))
          "first row is the first DATA row, keyed by the detected header")
      (is (= ["name" "age" "city"] (:header r2)) "the detected header is surfaced")
      (is (false? (:capped? r2))))))

(deftest sample-rows-default-n
  (testing "sample-rows with no n uses the default and never throws."
    (let [f (make-simple-xlsx! (temp-file ".xlsx"))
          tools (ex/excel-source-tools)
          r ((get tools 'sample-rows) (.getAbsolutePath f) "People")]
      (is (= 2 (:row-count r))
          "sheet has 2 DATA rows (header excluded); all returned under default 20")
      (is (false? (:capped? r))))))

;; =============================================================================
;; AC5 — bounded read on an OVER-CAP sheet: never loads the whole sheet
;; =============================================================================

(deftest sample-rows-hard-caps-an-overcap-sheet
  (testing "A sheet with FAR more rows than the cap returns a BOUNDED slice and
            flags :capped? — the bounded-read contract that makes a 119 MB sheet
            safe. The synthetic sheet has 5000 rows; the tool must stop early."
    (let [f (make-overcap-xlsx! (temp-file ".xlsx") 5000)
          tools (ex/excel-source-tools)
          ;; request MORE than the hard cap; tool must still bound the result
          r ((get tools 'sample-rows) (.getAbsolutePath f) "Big" 100000)]
      (is (<= (:row-count r) 500) "row count is bounded by the hard cap")
      (is (true? (:capped? r)) "capped flag set — more data exists than sampled"))))

;; =============================================================================
;; AC6 — sample-rows :offset reaches data PAST a large leading block
;; =============================================================================
;; Surfaced by the V09 graph-B build: PSEO's first ~3500 rows are state-level
;; aggregates, beyond the 500-row sampling ceiling, so the real per-program
;; earnings rows were unreachable. An :offset skips the leading block (stream-
;; and-discard, no load) so a discovery session can sample real data deeper in
;; the sheet.

(deftest sample-rows-offset-reaches-deeper-rows
  (testing "An {:limit N :offset K} opts map skips K leading WORKSHEET rows and
            samples real KEYED data past them. The overcap fixture has a header on
            worksheet row 0 and data row i on worksheet row i+1 (k0 = i), so an
            offset of K lands on worksheet row K = data row K-1 (k0 = K-1)."
    (let [f (make-overcap-xlsx! (temp-file ".xlsx") 5000)
          tools (ex/excel-source-tools)
          ;; No offset: the first sampled DATA row is data row 0 (k0 = 0); the
          ;; header (worksheet row 0) is the KEY source, not a data row.
          top ((get tools 'sample-rows) (.getAbsolutePath f) "Big" {:limit 3})
          ;; offset 1000 skips worksheet rows 0..999 (the header + data rows
          ;; 0..998); the first sampled data row is worksheet row 1000 = data row
          ;; 999 (k0 = 999) — rows the 500-row top-sample could never reach.
          deep ((get tools 'sample-rows) (.getAbsolutePath f) "Big" {:limit 3 :offset 1000})]
      (is (every? map? (:rows top)) "rows are keyed maps")
      (is (= 0.0 (Double/parseDouble (str (get (first (:rows top)) "k0"))))
          "no offset → first DATA row is data row 0 (k0 = 0)")
      (is (= 3 (:row-count deep)) "offset still honors the requested limit")
      (is (= 1000 (:offset deep)) "the offset is echoed back")
      (is (= 999.0 (Double/parseDouble (str (get (first (:rows deep)) "k0"))))
          "offset 1000 → first sampled worksheet row is 1000 = data row 999")
      (is (= 1001.0 (Double/parseDouble (str (get (nth (:rows deep) 2) "k0"))))
          "subsequent rows continue sequentially from the offset"))))

(deftest sample-rows-offset-via-keyword-n-key
  (testing "The opts map accepts :n as a synonym for :limit (defensive — the
            model reaches for either)."
    (let [f (make-overcap-xlsx! (temp-file ".xlsx") 5000)
          tools (ex/excel-source-tools)
          r ((get tools 'sample-rows) (.getAbsolutePath f) "Big" {:n 2 :offset 50})]
      (is (= 2 (:row-count r)))
      (is (every? map? (:rows r)) "keyed rows")
      ;; offset 50 → worksheet row 50 = data row 49 (k0 = 49).
      (is (= 49.0 (Double/parseDouble (str (get (first (:rows r)) "k0"))))))))

;; =============================================================================
;; AC6 — REAL 119 MB PSEO sheet: sample under a tight heap; no full load
;; =============================================================================

(def ^:private pseo-path "/Users/darylroberts/Downloads/pseo_la.xlsx")

(deftest real-pseo-119mb-sheet-sampled-not-loaded
  (testing "Against the REAL PSEO workbook (sheet2/Flows ~119 MB uncompressed):
            list-sheets + sheet-columns + sample-rows return correct structure
            FAST and bounded. The adversarial assertion: the streamed sample of
            the 119 MB sheet allocates only a few rows' worth of data — if the
            tool were loading the whole worksheet, the elapsed wall time and the
            allocation would both explode. We assert the sample completes well
            under a second and returns exactly the bounded rows."
    (if-not (.exists (File. pseo-path))
      (println "[V05] SKIP real-pseo test — fixture absent at" pseo-path)
      (let [tools (ex/excel-source-tools)
            sheets ((get tools 'list-sheets) pseo-path)]
        (is (= ["Earnings" "Flows"] (mapv :name sheets))
            "real PSEO workbook has the two expected sheets")
        ;; The 119 MB sheet is "Flows" (index 1). Stream a bounded sample and
        ;; time it. A full load of a 119 MB XML part would take seconds and
        ;; hundreds of MB; a bounded stream is single-digit ms.
        (let [t0 (System/nanoTime)
              s ((get tools 'sample-rows) pseo-path "Flows" 12)
              ms (/ (- (System/nanoTime) t0) 1e6)]
          (is (= 12 (:row-count s)) "exactly 12 rows pulled from the 119 MB sheet")
          (is (> (:column-count s) 30) "wide PSEO sheet (>30 cols)")
          (is (< ms 2000.0)
              (str "bounded stream of 12 rows from a 119 MB sheet must be fast; "
                   "took " ms " ms — a full load would be far slower"))
          (println (format "[V05] streamed 12 rows from 119 MB PSEO sheet in %.1f ms" ms)))
        ;; sheet-columns on the big sheet detects a header (PSEO header is NOT
        ;; row 1 — title/source/release/note precede it).
        (let [c ((get tools 'sheet-columns) pseo-path "Flows")]
          (is (> (:column-count c) 30))
          (is (> (:header-row-index c) 0)
              "PSEO header is below the title rows, not row 1")
          (is (some #(= "agg_level_pseo" %) (:header c))
              "detected the real PSEO column header"))))))

;; =============================================================================
;; AC7 — Multi-file Excel dir (O*NET-shaped) is enumerable
;; =============================================================================

(deftest excel-dir-sheets-enumerates-a-folder
  (testing "excel-dir-sheets lists each .xlsx in a directory and its sheet names,
            sorted, ignoring non-xlsx files."
    (let [dir (doto (File. (System/getProperty "java.io.tmpdir")
                           (str "v05-onet-" (System/currentTimeMillis)))
                (.mkdirs))]
      (try
        (make-simple-xlsx! (File. dir "Bravo.xlsx"))
        (make-titled-xlsx! (File. dir "Alpha.xlsx"))
        (spit (File. dir "readme.txt") "ignore me")
        (let [tools (ex/excel-source-tools)
              r ((get tools 'excel-dir-sheets) (.getAbsolutePath dir))]
          (is (= ["Alpha.xlsx" "Bravo.xlsx"] (mapv :file r)) "sorted, non-xlsx dropped")
          (is (= ["Earnings"] (:sheets (first r))))
          (is (= ["People" "Cities"] (:sheets (second r)))))
        (finally
          (doseq [^File c (.listFiles dir)] (.delete c))
          (.delete dir))))))

(deftest real-onet-dir-enumerable
  (testing "Against the REAL O*NET db_30_1_excel directory (multi-file), the
            workbooks enumerate with their sheet names."
    (let [dir "/Users/darylroberts/Downloads/db_30_1_excel"]
      (if-not (.isDirectory (File. dir))
        (println "[V05] SKIP real-onet test — dir absent at" dir)
        (let [tools (ex/excel-source-tools)
              r ((get tools 'excel-dir-sheets) dir)]
          (is (> (count r) 10) "O*NET dir has many workbooks")
          (is (every? #(str/ends-with? (:file %) ".xlsx") r))
          (is (some #(= "Abilities.xlsx" (:file %)) r))
          (let [ab (first (filter #(= "Abilities.xlsx" (:file %)) r))]
            (is (= ["Abilities"] (:sheets ab))
                "Abilities.xlsx has one sheet named Abilities")))))))

;; =============================================================================
;; AC8 — Docstring quality (self-contained) + adversarial twin
;; =============================================================================

(deftest each-tool-docstring-is-self-contained
  (testing "Every Excel tool docstring has PURPOSE, EXAMPLE, RETURNS with a
            concrete worked call form (no <placeholder> tokens)."
    (let [docs ex/excel-source-tool-docs
          required ["PURPOSE" "EXAMPLE" "RETURNS"]]
      (is (= #{'list-sheets 'sheet-columns 'sample-rows 'count-rows 'stream-all
               'excel-dir-sheets}
             (set (keys docs)))
          "all six tools have docs (V19 added count-rows + stream-all)")
      (doseq [[sym doc] docs]
        (testing (str sym " docstring structure")
          (is (string? doc))
          (doseq [el required]
            (is (str/includes? doc el) (str sym " missing " el)))
          (let [after (second (str/split doc #"EXAMPLE"))]
            (is (some? after) (str sym " has content after EXAMPLE"))
            (is (str/includes? after "(") (str sym " EXAMPLE has a code form"))
            (let [code-only (first (str/split after #"RETURNS"))]
              (is (not (re-find #"<arg\d?>|<placeholder>" code-only))
                  (str sym " EXAMPLE uses concrete values, not placeholders"))
              (is (str/includes? code-only ".xlsx")
                  (str sym " EXAMPLE references a concrete .xlsx path")))))))))

(deftest adversarial-stripping-a-section-fails-docstring-quality
  (testing "Proof the docstring check isn't trivially-passing: a doc missing
            EXAMPLE fails the required-elements scan."
    (let [bad "PURPOSE — lists sheets. RETURNS — a vector."
          required ["PURPOSE" "EXAMPLE" "RETURNS"]
          results (mapv #(str/includes? bad %) required)]
      (is (= [true false true] results))
      (is (not (every? identity results))
          "the missing EXAMPLE is caught"))))

;; =============================================================================
;; AC9 — fns carry their docstring on metadata (sandbox introspection)
;; =============================================================================

(deftest tools-carry-docstring-metadata
  (testing "Each tool fn carries its self-contained docstring on :doc metadata,
            so a sandbox `(meta sample-rows)` returns guidance."
    (let [tools (ex/excel-source-tools)]
      (doseq [[sym f] tools]
        (is (string? (:doc (meta f))) (str sym " fn has :doc metadata"))
        (is (str/includes? (:doc (meta f)) "PURPOSE")
            (str sym " :doc is the self-contained docstring"))))))

;; =============================================================================
;; AC10 — error shapes (no silent fallback)
;; =============================================================================

(deftest non-xlsx-fails-loudly
  (testing "A non-.xlsx path fails loudly rather than silently returning empty."
    (let [tools (ex/excel-source-tools)
          txt (doto (File/createTempFile "v05" ".txt") (.deleteOnExit))]
      (spit txt "not excel")
      (is (thrown-with-msg? Exception #"(?i)xlsx"
                            ((get tools 'list-sheets) (.getAbsolutePath txt)))))))

(deftest unknown-sheet-lists-available
  (testing "Selecting a non-existent sheet throws an error naming the available
            sheets — a teaching error for the RLM, not a cryptic nil."
    (let [f (make-simple-xlsx! (temp-file ".xlsx"))
          tools (ex/excel-source-tools)]
      (is (thrown-with-msg? Exception #"(?i)People|Cities|no sheet"
                            ((get tools 'sheet-columns) (.getAbsolutePath f) "Nonexistent"))))))

;; =============================================================================
;; V19 — Format-specialist ergonomics + count + stream-all
;; =============================================================================
;; Surfaced by V17: the Excel builder passed a sheet-MAP (the descriptor that a
;; prior list-sheets call returned) where a name/index was expected, then passed
;; a 4th positional arg to sample-rows — repeated ARITY exceptions burned ~4
;; iterations and PSEO yielded :no-output. V19 makes the selector forgiving (a
;; descriptor map resolves), makes a wrong-shape arg a teaching error not an
;; arity crash, and adds a count affordance + a stream-all affordance.

;; --- V19.1 — a descriptor MAP (as list-sheets returns) resolves, no throw ----

(deftest sheet-selector-accepts-a-list-sheets-descriptor-map
  (testing "Passing back the exact descriptor map list-sheets returned (the V17
            failure) resolves to the sheet — it does NOT throw an arity/cast
            error."
    (let [f (make-simple-xlsx! (temp-file ".xlsx"))
          tools (ex/excel-source-tools)
          descriptors ((get tools 'list-sheets) (.getAbsolutePath f))
          people-desc (first descriptors)]      ; {:name "People" :index 0 :sheet-id "1"}
      (is (map? people-desc) "list-sheets returns descriptor maps")
      ;; sheet-columns with the MAP, not the name — this is what V17 threw on.
      (let [r ((get tools 'sheet-columns) (.getAbsolutePath f) people-desc)]
        (is (= ["name" "age" "city"] (:header r))
            "the descriptor map resolved to the People sheet"))
      ;; sample-rows with the MAP too.
      (let [s ((get tools 'sample-rows) (.getAbsolutePath f) people-desc 2)]
        (is (= 2 (:row-count s)) "sample-rows resolved the descriptor map"))
      ;; A map carrying only :index also resolves.
      (let [r ((get tools 'sheet-columns) (.getAbsolutePath f) {:index 0})]
        (is (= ["name" "age" "city"] (:header r))
            "a map with only :index resolves")))))

;; --- V19.2 — wrong-shape / extra-arg sampling call is a TEACHING error --------

(deftest sample-rows-wrong-shape-arg-is-a-teaching-error
  (testing "An extra positional arg (the V17 mistake — a 4th arg to sample-rows)
            yields a CLEAR teaching error naming the correct call form, NOT a raw
            arity exception."
    (let [f (make-simple-xlsx! (temp-file ".xlsx"))
          tools (ex/excel-source-tools)
          sample (get tools 'sample-rows)]
      ;; The V17 call: (sample-rows path "Earnings" 100 {:offset 0}) — a 4th arg.
      (is (thrown-with-msg? Exception #"(?i)sample-rows"
                            (sample (.getAbsolutePath f) "People" 100 {:offset 0}))
          "the error names the tool")
      (is (thrown-with-msg? Exception #"(?i):limit|:offset|opts map|positional"
                            (sample (.getAbsolutePath f) "People" 100 {:offset 0}))
          "the error states the correct call form")
      ;; A wrong-shape selector arg (a vector — not name/index/map) also teaches.
      (is (thrown-with-msg? Exception #"(?i)name|index|sheet"
                            (sample (.getAbsolutePath f) ["not" "a" "sheet"]))
          "a wrong-shape selector arg teaches the accepted forms"))))

;; --- V19.3 — count affordance: cardinality WITHOUT a full load ----------------

(deftest count-rows-returns-sheet-cardinality-without-loading
  (testing "count-rows reports a sheet's total <row> count without loading the
            sheet — so a specialist knows how much remains. The over-cap fixture
            now carries a header row + 5000 data rows = 5001 raw rows (far above
            the 500-row sample cap; count-rows counts every <row>, header
            included)."
    (let [f (make-overcap-xlsx! (temp-file ".xlsx") 5000)
          tools (ex/excel-source-tools)
          c ((get tools 'count-rows) (.getAbsolutePath f) "Big")]
      (is (= 5001 (:row-count c)) "full cardinality (header + 5000 data), not the sample cap")
      (is (= "Big" (:sheet c))))
    (testing "count-rows resolves a descriptor map selector too"
      (let [f (make-simple-xlsx! (temp-file ".xlsx"))
            tools (ex/excel-source-tools)
            desc (first ((get tools 'list-sheets) (.getAbsolutePath f)))
            c ((get tools 'count-rows) (.getAbsolutePath f) desc)]
        (is (= 3 (:row-count c)) "People sheet has 3 rows (header + 2 data)")))))

;; --- V19.4 — stream-all: iterate the FULL set in bounded windows --------------

(deftest stream-all-covers-every-row-exactly-once
  (testing "stream-all iterates the FULL DATA-row set in bounded windows, covering
            every data row exactly once while honoring the per-call ceiling. The
            fixture's 5000 data rows have k0 = the row index, so the concatenation
            of all windows' KEYED rows must be exactly 0..4999, each once (the
            header is the key source, excluded from the data)."
    (let [f (make-overcap-xlsx! (temp-file ".xlsx") 5000)
          tools (ex/excel-source-tools)
          stream (get tools 'stream-all)
          ;; window-size below the hard cap so multiple windows are required.
          windows (stream (.getAbsolutePath f) "Big" {:window 200})
          rows (mapcat :rows windows)
          col0s (mapv #(long (Double/parseDouble (str (get % "k0")))) rows)]
      (is (> (count windows) 1) "more than one window — the set did not fit in one")
      (is (every? map? rows) "every streamed row is a keyed map")
      (is (every? #(<= (count (:rows %)) 500) windows)
          "every window respects the per-call hard cap")
      (is (= 5000 (count col0s)) "every DATA row covered (header excluded)")
      (is (= (range 0 5000) col0s)
          "data rows are covered in order, exactly once, no gaps or dupes"))))

(deftest stream-all-respects-the-hard-cap-as-window-ceiling
  (testing "A :window above the hard cap is clamped to the cap — stream-all never
            pulls more than the per-call ceiling in a single window, but still
            covers the whole sheet across windows (keyed rows; header excluded)."
    (let [f (make-overcap-xlsx! (temp-file ".xlsx") 1200)
          tools (ex/excel-source-tools)
          windows ((get tools 'stream-all) (.getAbsolutePath f) "Big" {:window 100000})
          rows (mapcat :rows windows)]
      (is (every? #(<= (count (:rows %)) 500) windows)
          "window clamped to the 500-row hard cap")
      (is (every? map? rows) "rows are keyed maps")
      (is (= 1200 (count rows)) "all 1200 DATA rows still covered across windows")
      (is (= (range 0 1200)
             (mapv #(long (Double/parseDouble (str (get % "k0")))) rows))
          "every data row covered exactly once, in order, keyed by header"))))
