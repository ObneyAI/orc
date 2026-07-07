(ns ai.obney.orc.orc-service.connect2-excel-relations-test
  "CONNECT-2 — the excel container-contract `:relations` op: a deterministic,
   DOMAIN-AGNOSTIC shared-key cross-sheet heuristic that matches the SQL relations
   SHAPE (`{:from \"<sheet>.<col>\" :to \"<other>.<col>\" :via \"<col>\"}`) MC-6
   consumes. It is the upstream root fix for O*NET disconnection: excel's
   `:relations` was nil, so MC-6's cross-sheet edge-derivation never fired.

   The heuristic reads each sheet's HEADER columns (reusing the excel header
   capability — injected, faked in the pure units below) and treats a column whose
   NAME is ID/code-shaped and appears in >= 2 sheets as a JOIN KEY, emitting a
   relation from the queried sheet to every OTHER sheet carrying that key column.
   It names NO domain column (structural only, biased toward id/code columns and
   AWAY from free-text/measure columns). Returns [] honestly when no shared key.

   Tracers, in order, through the PUBLIC surface:
   1. Op present: `(:relations (container-contract {:type :excel :path <dir>}))` is
      a fn (RED — nil before this slice).
   2. Shared key → relation: a synthetic 2-sheet header set both carrying a made-up
      `widget_id` yields a relation :via \"widget_id\".
   3. No shared column → [] (honest, no fabrication).
   4. Key-shaped bias: a shared free-text/measure column (Description / Data Value)
      does NOT relate; a shared id-like column does.
   5. Real O*NET (durable faked-header mirror + live skip-if-absent): an occupation
      sheet is related to a skills/abilities/tasks sheet via the shared SOC column."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.core.source-tools :as st]
            [ai.obney.orc.orc-service.core.source-tools-excel :as excel])
  (:import [java.util.zip ZipOutputStream ZipEntry]
           [java.io File FileOutputStream]))

;; =============================================================================
;; Minimal .xlsx-dir fixture (zip-of-XML) — a directory of single-sheet workbooks,
;; the O*NET shape. Reused for the container-contract + real-reader tracers.
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

(defn- write-single-sheet-xlsx!
  "Write one single-sheet .xlsx to `f`. `sheet-name` names the sheet; `header` is a
   vector of header column strings; `data` is a vector of row-vectors whose cells
   are strings (all string cells, resolved via sharedStrings)."
  [^File f sheet-name header data]
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
               (sheet-xml (cons (row-xml 1 (mapv (fn [h] {:s (idx h)}) header))
                                (map-indexed
                                 (fn [ri row]
                                   (row-xml (+ 2 ri) (mapv (fn [v] {:s (idx v)}) row)))
                                 data)))))
    f))

(defn- temp-dir []
  (let [d (File. (System/getProperty "java.io.tmpdir")
                 (str "connect2-xlsx-" (System/nanoTime)))]
    (.mkdirs d)
    d))

(defn- two-sheet-shared-key-dir!
  "A temp DIR of two single-sheet workbooks that BOTH carry an id-like `record_code`
   column (plus a non-key label), so the real reader path finds a shared key."
  []
  (let [d (temp-dir)]
    (write-single-sheet-xlsx! (File. d "Orders.xlsx") "Orders"
                              ["record_code" "amount"] [["A1" "10"] ["A2" "20"]])
    (write-single-sheet-xlsx! (File. d "Lines.xlsx") "Lines"
                              ["record_code" "qty"] [["A1" "3"] ["A2" "5"]])
    d))

;; =============================================================================
;; Tracer 1 — the op is PRESENT on the excel container-contract (RED: nil today)
;; =============================================================================

(deftest excel-relations-op-is-present-on-container-contract
  (testing "the excel container-contract exposes :relations as a FN (was nil — the
            proven upstream root cause of O*NET disconnection)"
    (let [d (two-sheet-shared-key-dir!)
          cc (st/container-contract {:type :excel :path (.getAbsolutePath d)})]
      (is (= :excel (:format cc)))
      (is (fn? (:relations cc))
          "excel :relations must be a fn (RED before CONNECT-2 — it was nil)"))))

;; =============================================================================
;; Tracer 2 — a shared id-like key column yields a {:via key} relation. Uses the
;; FAKED injected header capability (a pure unit — no filesystem).
;; =============================================================================

(defn- fake-reader
  "Return a header-reader capability that ignores its path and returns `sheets`
   (a vector of {:sheet :columns})."
  [sheets]
  (fn [_path] sheets))

(deftest shared-key-yields-via-relation
  (testing "two sheets both carrying a made-up id-like `widget_id` link :via that
            column — the join key is discovered structurally, NO column baked in"
    (let [reader (fake-reader [{:sheet "SheetA" :columns ["widget_id" "alpha_val"]}
                               {:sheet "SheetB" :columns ["widget_id" "beta_val"]}])
          relations (excel/make-excel-relations-fn "/ignored" reader)
          rels (relations "SheetA")]
      (is (vector? rels))
      (is (some (fn [r]
                  (= r {:from "SheetA.widget_id"
                        :to   "SheetB.widget_id"
                        :via  "widget_id"}))
                rels)
          "SheetA links to SheetB :via the shared id-like column widget_id, in the
           SQL {:from \"<sheet>.<col>\" :to \"<other>.<col>\" :via \"<col>\"} shape")
      (testing "every relation carries the uniform contract keys MC-6 consumes"
        (is (every? (fn [r] (= #{:from :to :via} (set (keys r)))) rels))))))

;; =============================================================================
;; Tracer 3 — no shared column → [] (honest, never a fabricated edge)
;; =============================================================================

(deftest no-shared-column-yields-empty
  (testing "two sheets that share NO column yield [] — the heuristic never invents
            a link"
    (let [reader (fake-reader [{:sheet "SheetA" :columns ["a_id" "a_val"]}
                               {:sheet "SheetB" :columns ["b_id" "b_val"]}])
          relations (excel/make-excel-relations-fn "/ignored" reader)]
      (is (= [] (relations "SheetA"))
          "no shared key column => no relations (honest negative)"))))

;; =============================================================================
;; Tracer 4 — key-shaped bias: a shared FREE-TEXT / MEASURE column is NOT a join
;; key; a shared id-like column is.
;; =============================================================================

(deftest key-shaped-bias-excludes-freetext-and-measures
  (testing "sheets sharing only a free-text (Description) / measure (Data Value)
            column do NOT relate — those carry no id/code token; adding a shared
            id-like column DOES relate. Biases toward keys, away from noise."
    (let [noise-reader
          (fake-reader [{:sheet "SheetA" :columns ["Description" "Data Value" "a_only"]}
                        {:sheet "SheetB" :columns ["Description" "Data Value" "b_only"]}])
          noise-rels ((excel/make-excel-relations-fn "/ignored" noise-reader) "SheetA")]
      (is (= [] noise-rels)
          "a shared free-text/measure column is NOT treated as a join key")
      (let [keyed-reader
            (fake-reader [{:sheet "SheetA" :columns ["Description" "Data Value" "record_code"]}
                         {:sheet "SheetB" :columns ["Description" "Data Value" "record_code"]}])
            keyed-rels ((excel/make-excel-relations-fn "/ignored" keyed-reader) "SheetA")]
        (is (= #{"record_code"} (set (map :via keyed-rels)))
            "only the shared id-like column becomes a relation — NOT Description /
             Data Value")))))

;; =============================================================================
;; Tracer 5a — DURABLE O*NET mirror (always runs): the real O*NET header shape,
;; faked so the join is guarded even when the real dir is absent.
;; =============================================================================

(def ^:private onet-header-mirror
  "The REAL O*NET header shape (verified against db_30_1_excel): the occupation
   sheet and the content-model sheets all carry `O*NET-SOC Code`; the content
   sheets also carry `Title`, `Data Value`, `Date`, `Domain Source` etc."
  [{:sheet "Occupation Data" :columns ["O*NET-SOC Code" "Title" "Description"]}
   {:sheet "Skills" :columns ["O*NET-SOC Code" "Title" "Element ID" "Element Name"
                              "Scale ID" "Data Value" "N" "Standard Error" "Date"
                              "Domain Source"]}
   {:sheet "Abilities" :columns ["O*NET-SOC Code" "Title" "Element ID" "Element Name"
                                 "Scale ID" "Data Value" "N" "Date" "Domain Source"]}
   {:sheet "Task Statements" :columns ["O*NET-SOC Code" "Title" "Task ID" "Task"
                                       "Task Type" "Date" "Domain Source"]}])

(deftest onet-occupation-related-to-content-sheets-via-soc-durable
  (testing "DURABLE: on the real O*NET header shape, Occupation Data is related to a
            skills/abilities/tasks sheet :via `O*NET-SOC Code` — and is NOT related
            via the shared but non-key `Title`"
    (let [relations (excel/make-excel-relations-fn "/ignored" (fake-reader onet-header-mirror))
          rels (relations "Occupation Data")
          vias (set (map :via rels))]
      (is (contains? vias "O*NET-SOC Code")
          "the SOC code is discovered as the join key")
      (is (not (contains? vias "Title"))
          "Title co-occurs everywhere but is NOT id/code-shaped — no spurious link")
      (is (some (fn [r] (and (= "O*NET-SOC Code" (:via r))
                             (str/starts-with? (:from r) "Occupation Data.")
                             (contains? #{"Skills" "Abilities" "Task Statements"}
                                        (first (str/split (:to r) #"\." 2)))))
                rels)
          "Occupation Data relates to a skills/abilities/tasks sheet :via the SOC code")
      (testing "uniform contract keys on every edge"
        (is (every? (fn [r] (= #{:from :to :via} (set (keys r)))) rels))))))

;; =============================================================================
;; Tracer 5b — LIVE O*NET (skip-if-absent): the real dir through the real
;; container-contract + real header reader — the load-bearing proof.
;; =============================================================================

(def ^:private onet-dir "/Users/darylroberts/Downloads/db_30_1_excel")

(deftest onet-live-soc-relation-through-real-container-contract
  (if-not (.isDirectory (File. onet-dir))
    (println "[connect2] SKIP — O*NET dir" onet-dir "absent")
    (testing "LIVE: the real excel container-contract's :relations op, on the real
              O*NET dir, relates Occupation Data to a content sheet :via the shared
              SOC-code column"
      (let [cc (st/container-contract {:type :excel :path onet-dir})
            relations (:relations cc)]
        (is (fn? relations) "the real excel container-contract exposes :relations as a fn")
        (let [rels (relations "Occupation Data")
              soc-rels (filter #(= "O*NET-SOC Code" (:via %)) rels)]
          (is (seq soc-rels)
              "LOAD-BEARING: Occupation Data has SOC-code relations on the real dir")
          (is (some (fn [r] (contains? #{"Skills" "Abilities" "Task Statements"
                                         "Knowledge"}
                                       (first (str/split (:to r) #"\." 2))))
                    soc-rels)
              "Occupation Data relates to a skills/abilities/tasks/knowledge sheet :via SOC")
          (is (every? (fn [r] (= #{:from :to :via} (set (keys r)))) rels)
              "every real edge has the uniform {:from :to :via} shape")
          (println "[connect2] O*NET live: Occupation Data relations="
                   (count rels) "soc-relations=" (count soc-rels)
                   "targets=" (pr-str (mapv #(first (str/split (:to %) #"\." 2)) soc-rels))))))))
