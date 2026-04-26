(ns ai.obney.orc.doc-skills.core.xlsx
  "Apache POI XSSF wrapper for reading/writing .xlsx files.

   Designed to be called from inside a SCI sandbox via `xlsx/write-workbook`,
   `xlsx/read-sheet`, etc. Workbooks are represented as a small EDN-friendly
   spec rather than direct POI objects, so an LLM can construct them from
   plain Clojure data."
  (:require [clojure.java.io :as io])
  (:import [org.apache.poi.xssf.usermodel XSSFWorkbook]
           [org.apache.poi.ss.usermodel
            Workbook Sheet Row Cell CellType WorkbookFactory]
           [java.io File FileInputStream FileOutputStream]))

(set! *warn-on-reflection* true)

(defn- ^File ->file [path]
  (cond
    (instance? File path) path
    (string? path) (io/file path)
    :else (throw (ex-info "Expected file path or File"
                          {:type :xlsx/bad-path :got path}))))

(defn- ensure-parent! [^File f]
  (when-let [parent (.getParentFile f)]
    (.mkdirs parent)))

(defn- set-cell! [^Cell cell v]
  (cond
    (nil? v) (.setBlank cell)
    (number? v) (.setCellValue cell (double v))
    (boolean? v) (.setCellValue cell (boolean v))
    :else (.setCellValue cell (str v))))

(defn- write-row! [^Sheet sheet row-idx values]
  (let [^Row row (.createRow sheet (int row-idx))]
    (doseq [[col v] (map-indexed vector values)]
      (set-cell! (.createCell row (int col)) v))))

(defn- auto-size! [^Sheet sheet column-count]
  (dotimes [i column-count]
    (.autoSizeColumn sheet (int i))))

(defn write-workbook
  "Write an .xlsx workbook to `out-path`. `sheets-spec` is a vector of:
     {:name        \"Sheet name\"
      :columns     [{:header \"Vendor\" :width 20} ...]   ;; optional
      :rows        [[v1 v2 ...] [v1 v2 ...] ...]
      :auto-size?  true                                    ;; default true}

   Returns out-path."
  [out-path sheets-spec]
  (let [out (->file out-path)]
    (ensure-parent! out)
    (with-open [wb (XSSFWorkbook.)
                fos (FileOutputStream. out)]
      (doseq [{:keys [name columns rows auto-size?] :or {auto-size? true}} sheets-spec]
        (let [^Sheet sheet (.createSheet wb (str name))
              col-count (max (count columns)
                             (apply max 0 (map count rows)))]
          ;; Header row from columns spec
          (when (seq columns)
            (write-row! sheet 0 (mapv :header columns)))
          ;; Data rows offset by header (if present)
          (let [start (if (seq columns) 1 0)]
            (doseq [[i row-vals] (map-indexed vector rows)]
              (write-row! sheet (+ start i) row-vals)))
          (when auto-size? (auto-size! sheet col-count))))
      (.write wb fos))
    (str out)))

(defn list-sheets
  "Return a vector of sheet names in the workbook at `path`."
  [path]
  (with-open [in (FileInputStream. (->file path))]
    (let [^Workbook wb (WorkbookFactory/create in)]
      (mapv (fn [i] (.getSheetName wb (int i)))
            (range (.getNumberOfSheets wb))))))

(defn- cell-value [^Cell cell]
  (when cell
    (case (.name (.getCellType cell))
      "STRING"  (.getStringCellValue cell)
      "NUMERIC" (.getNumericCellValue cell)
      "BOOLEAN" (.getBooleanCellValue cell)
      "FORMULA" (try (.getNumericCellValue cell)
                     (catch Exception _ (.getStringCellValue cell)))
      "BLANK"   nil
      nil)))

(defn read-sheet
  "Read a sheet (by name) into a vector of row vectors.
   First row is included as-is; caller can decide whether it's a header."
  [path sheet-name]
  (with-open [in (FileInputStream. (->file path))]
    (let [^Workbook wb (WorkbookFactory/create in)
          ^Sheet sheet (.getSheet wb (str sheet-name))]
      (when sheet
        (let [last-row (.getLastRowNum sheet)]
          (vec (for [i (range 0 (inc last-row))
                     :let [^Row row (.getRow sheet (int i))]
                     :when row]
                 (let [last-col (.getLastCellNum row)]
                   (vec (for [c (range 0 last-col)]
                          (cell-value (.getCell row (int c)))))))))))))

(defn read-sheet-as-maps
  "Read a sheet treating row 0 as header. Returns a vector of maps keyed by
   keywordized header names."
  [path sheet-name]
  (let [rows (read-sheet path sheet-name)]
    (when (seq rows)
      (let [headers (mapv (fn [h] (keyword (str h))) (first rows))]
        (mapv (fn [row]
                (zipmap headers row))
              (rest rows))))))
