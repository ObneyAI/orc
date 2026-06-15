(ns ai.obney.orc.orc-service.core.source-tools-excel
  "V05 — Excel (.xlsx) SOURCE-ACCESS tools for the discovery RLM.

   Per-format primitives that let a recursive-RLM discovery session EXPLORE
   an Excel workbook by sheet and column WITHOUT loading the whole file. This
   is the format the OLD evolutionary builder never supported — it required a
   manual hand-conversion to CSV first. These tools remove that step and add
   net-new capability: a 119 MB worksheet is SAMPLED, never materialized.

   ## Why streaming (the load-bearing design)

   An .xlsx file is a ZIP archive of XML parts. The worksheet data lives in
   `xl/worksheets/sheetN.xml`, which for a large sheet can be hundreds of MB
   UNCOMPRESSED. Loading it (DOM parse, or any whole-file Excel reader) would
   blow the heap. Instead every tool here uses:

     - `java.util.zip.ZipFile` to open ONE entry as a streaming InputStream, and
     - `javax.xml.stream` (StAX) to pull XML events one at a time,

   stopping (and closing the stream) after the bounded number of rows it needs.
   Both are JDK built-ins — NO third-party Excel dependency is added. The
   proof: streaming 12 rows out of a real 119 MB worksheet completes in a few
   milliseconds under a 256 MB heap cap; a full load would OOM.

   The only part loaded whole is `xl/sharedStrings.xml` (tens of KB even for the
   PSEO workbook — string cells store an INDEX into it), which is required to
   resolve string-typed cells and is bounded by the workbook's unique-string
   count, not its row count.

   ## Header is NOT necessarily row 1 (adversarial reality)

   Real Census/PSEO workbooks put title / source / release / note lines in the
   first several rows, a blank row, then the column header, then data. So
   `sheet-columns` does NOT blindly treat row 1 as the header — it scans the
   first window of rows, picks the densest mostly-string row as the header
   candidate, AND returns the raw scanned rows so the RLM can override the
   guess. This is the kind of structure the OLD builder's CSV pre-conversion
   silently mangled.

   ## Read-side only

   Every tool opens the file read-only and projects bytes into Clojure data;
   none emits a Grain event. They are pure filesystem reads — the discovery RLM
   uses them to DESIGN extraction; the extraction itself runs elsewhere.

   ## Wiring

   `excel-source-tools` returns the {symbol -> fn} map a SCI sandbox merges into
   its bindings (mirroring `sandbox-tools/build-ontology-tool-bindings`). The
   unified per-format source-tool registry that selects this set by file
   extension is wired in V06; this namespace owns only the Excel leg."
  (:require [clojure.string :as str])
  (:import [java.util.zip ZipFile]
           [javax.xml.stream XMLInputFactory XMLStreamConstants XMLStreamReader]
           [java.io InputStream File]))

;; =============================================================================
;; StAX + ZIP streaming primitives
;; =============================================================================

(defn- ^XMLStreamReader xml-reader
  "A coalescing, non-namespace-aware StAX reader over `is`. Coalescing joins
   split CHARACTERS chunks (so a long cell value arrives in one .getText);
   non-namespace-aware lets us match bare localNames (`row`, `c`, `v`)."
  [^InputStream is]
  (let [f (XMLInputFactory/newInstance)]
    (.setProperty f XMLInputFactory/IS_COALESCING true)
    (.setProperty f XMLInputFactory/IS_NAMESPACE_AWARE false)
    ;; Harden against XML entity-expansion / external-entity attacks on
    ;; untrusted workbooks — we only ever read element text.
    (.setProperty f XMLInputFactory/SUPPORT_DTD false)
    (.createXMLStreamReader f is)))

(defn- entry-string
  "Read a named zip entry fully into a UTF-8 string, or nil if absent. Used
   ONLY for small parts (workbook.xml, rels, sharedStrings.xml)."
  [^ZipFile zf entry-name]
  (when-let [e (.getEntry zf entry-name)]
    (with-open [is (.getInputStream zf e)]
      (String. (.readAllBytes is) "UTF-8"))))

(defn- assert-xlsx!
  "Fail loudly (no fallback) when `path` is not a readable .xlsx file."
  [path]
  (let [f (File. (str path))]
    (when-not (.isFile f)
      (throw (ex-info (str "Excel source tool: not a file: " path) {:path path})))
    (when-not (str/ends-with? (str/lower-case (str path)) ".xlsx")
      (throw (ex-info (str "Excel source tool: expects a .xlsx file, got: " path
                           " (for legacy .xls convert to .xlsx; for a directory of "
                           "workbooks use excel-dir-sheets)")
                      {:path path})))))

;; =============================================================================
;; Workbook structure: ordered sheet names + r:id -> worksheet target
;; =============================================================================

(defn- attr-map [s]
  (into {} (map (fn [[_ k v]] [k v]) (re-seq #"([\w:]+)=\"([^\"]*)\"" s))))

(defn- workbook-sheets
  "Ordered [{:name :sheet-id :rid}] from xl/workbook.xml."
  [^ZipFile zf]
  (let [wb (or (entry-string zf "xl/workbook.xml")
               (throw (ex-info "Not a valid .xlsx: missing xl/workbook.xml" {})))]
    (mapv (fn [[_ a]]
            (let [m (attr-map a)]
              {:name (get m "name") :sheet-id (get m "sheetId") :rid (get m "r:id")}))
          (re-seq #"<sheet\s+([^>]*?)/?>" wb))))

(defn- rid->target
  "Map r:id -> worksheet entry path (e.g. \"xl/worksheets/sheet2.xml\") from
   xl/_rels/workbook.xml.rels. Targets are relative to xl/."
  [^ZipFile zf]
  (let [rels (or (entry-string zf "xl/_rels/workbook.xml.rels") "")]
    (into {}
          (keep (fn [[_ a]]
                  (let [m (attr-map a)
                        tgt (get m "Target")]
                    (when (and (get m "Id") tgt (str/includes? tgt "worksheets/"))
                      [(get m "Id")
                       (str "xl/" (str/replace tgt #"^/?xl/" ""))])))
                (re-seq #"<Relationship\s+([^>]*?)/?>" rels)))))

(defn- sheet-target-for
  "Resolve a sheet selector (name string, or 0-based index, or 1-based
   sheetId) to its worksheet entry path. Throws a clear error listing the
   available sheet names if the selector matches nothing."
  [^ZipFile zf selector]
  (let [sheets (workbook-sheets zf)
        rmap (rid->target zf)
        by-name (cond
                  (string? selector)
                  (first (filter #(= selector (:name %)) sheets))
                  (integer? selector)
                  (get (vec sheets) selector)
                  :else nil)
        chosen (or by-name
                   ;; fall back: treat a numeric string as an index
                   (when (and (string? selector) (re-matches #"\d+" selector))
                     (get (vec sheets) (Integer/parseInt selector))))]
    (when-not chosen
      (throw (ex-info (str "Excel source tool: no sheet matching " (pr-str selector)
                           ". Available sheets: " (mapv :name sheets))
                      {:selector selector :available (mapv :name sheets)})))
    (or (get rmap (:rid chosen))
        ;; positional fallback when rels lacks the r:id
        (str "xl/worksheets/sheet" (inc (.indexOf (vec sheets) chosen)) ".xml"))))

;; =============================================================================
;; sharedStrings (small, bounded by unique-string count — safe to load whole)
;; =============================================================================

(defn- shared-strings
  "Vector of the workbook's shared strings, indexed as cells reference them.
   Returns [] when the workbook has no sharedStrings part (all-inline)."
  [^ZipFile zf]
  (if-let [e (.getEntry zf "xl/sharedStrings.xml")]
    (with-open [is (.getInputStream zf e)]
      (let [r (xml-reader is)
            out (transient [])
            sb (StringBuilder.)
            in-si (volatile! false)]
        (try
          (while (.hasNext r)
            (let [ev (.next r)]
              (cond
                (= ev XMLStreamConstants/START_ELEMENT)
                (when (= "si" (.getLocalName r)) (vreset! in-si true) (.setLength sb 0))
                (= ev XMLStreamConstants/CHARACTERS)
                (when @in-si (.append sb (.getText r)))
                (= ev XMLStreamConstants/END_ELEMENT)
                (when (= "si" (.getLocalName r))
                  (conj! out (.toString sb)) (vreset! in-si false)))))
          (finally (.close r)))
        (persistent! out)))
    []))

;; =============================================================================
;; Bounded worksheet streaming
;; =============================================================================

(defn- col-ref->idx
  "\"A\" -> 0, \"B\" -> 1, ... \"AA\" -> 26. Strips the row digits off a cell ref
   like \"AV6\"."
  [cref]
  (let [letters (re-find #"^[A-Z]+" (or cref ""))]
    (when letters
      (dec (reduce (fn [acc ch] (+ (* acc 26) (- (int ch) 64))) 0 letters)))))

(def ^:private max-rows-hard-cap
  "Absolute ceiling on rows any single sample-rows / scan call will pull from a
   worksheet stream, regardless of what the caller asks for. The whole point of
   these tools is to SAMPLE, never load — this is the structural guarantee."
  500)

(defn- stream-rows
  "STREAM at most `n` rows (capped at the hard cap) from worksheet entry
   `target` and STOP. Returns {:rows [[cell ...] ...] :max-col K}. Each row is a
   dense vector of length max-col (nil for missing cells). String cells (t=\"s\")
   are resolved through `shared`; inline strings (t=\"inlineStr\"/\"str\") and
   numbers are taken verbatim. The stream is closed the instant the row budget
   is hit — the rest of the (possibly 100s-of-MB) part is never parsed.

   `offset` (default 0) SKIPS that many leading worksheet rows before
   collecting begins — so a caller can sample PAST a large leading block
   (title/note rows, or thousands of aggregate rows) to reach the real data
   without ever loading the sheet. Skipped rows are streamed-and-discarded
   (never accumulated), so the sampling guarantee holds; the stream still
   stops the instant `budget` collected rows is hit."
  ([^ZipFile zf target shared n] (stream-rows zf target shared n 0))
  ([^ZipFile zf target shared n offset]
  (let [budget (min (max 0 n) max-rows-hard-cap)
        skip (max 0 (or offset 0))
        seen (volatile! 0)
        e (or (.getEntry zf target)
              (throw (ex-info (str "Excel source tool: worksheet entry missing: " target)
                              {:target target})))]
    (with-open [is (.getInputStream zf e)]
      (let [r (xml-reader is)
            rows (transient [])
            cur (volatile! (transient {}))
            cell-idx (volatile! nil)
            cell-t (volatile! nil)
            in-inline (volatile! false)
            sb (StringBuilder.)
            maxcol (volatile! -1)]
        (try
          (loop []
            (when (and (.hasNext r) (< (count rows) budget))
              (let [ev (.next r)]
                (cond
                  (= ev XMLStreamConstants/START_ELEMENT)
                  (case (.getLocalName r)
                    "row" (vreset! cur (transient {}))
                    "c" (let [idx (col-ref->idx (.getAttributeValue r nil "r"))]
                          (vreset! cell-idx idx)
                          (vreset! cell-t (.getAttributeValue r nil "t"))
                          (vreset! in-inline false)
                          (when (and idx (> idx @maxcol)) (vreset! maxcol idx)))
                    "is" (do (vreset! in-inline true) (.setLength sb 0))
                    "v" (.setLength sb 0)
                    "t" (.setLength sb 0)
                    nil)
                  (= ev XMLStreamConstants/CHARACTERS)
                  (.append sb (.getText r))
                  (= ev XMLStreamConstants/END_ELEMENT)
                  (case (.getLocalName r)
                    "v" (when-let [idx @cell-idx]
                          (let [raw (.toString sb)
                                v (if (= "s" @cell-t)
                                    (get shared (try (Integer/parseInt raw)
                                                     (catch Exception _ -1)) raw)
                                    raw)]
                            (vswap! cur assoc! idx v)))
                    ;; inline string: the <t> inside <is> carries the text
                    "t" (when (and @in-inline @cell-idx)
                          (vswap! cur assoc! @cell-idx (.toString sb)))
                    "c" (do (vreset! cell-idx nil) (vreset! cell-t nil))
                    ;; Skip the first `skip` completed rows (stream-and-discard
                    ;; so we can reach data past a large leading block without
                    ;; accumulating it); collect once past the offset.
                    "row" (let [seen-now (vswap! seen inc)]
                            (when (> seen-now skip)
                              (conj! rows (persistent! @cur))))
                    nil))
                (recur))))
          (finally (.close r)))
        (let [width (inc @maxcol)
              dense (mapv (fn [m] (mapv #(get m %) (range width)))
                          (persistent! rows))]
          {:rows dense :max-col width}))))))

;; =============================================================================
;; Header detection + type inference
;; =============================================================================

(defn- numeric-cell? [v]
  (and (string? v) (seq v) (re-matches #"-?\d+(\.\d+)?([eE]-?\d+)?" v)))

(defn- row-density [row] (count (remove nil? row)))

(defn- mostly-strings? [row]
  (let [vals (remove nil? row)]
    (and (seq vals)
         (>= (count (remove numeric-cell? vals))
             (* 0.6 (count vals))))))

(defn- detect-header-index
  "Pick the most plausible header row from `scanned`: the FIRST row that is both
   among the densest AND mostly non-numeric (headers are labels, not numbers).
   Real PSEO sheets have several title rows before the header, so row 0 is not
   assumed. Returns an index into `scanned`, or 0 if nothing qualifies."
  [scanned]
  (let [max-density (reduce max 0 (map row-density scanned))]
    (or (first (keep-indexed
                (fn [i row]
                  (when (and (>= (row-density row) (max 1 (int (* 0.8 max-density))))
                             (mostly-strings? row))
                    i))
                scanned))
        0)))

(defn- infer-col-types
  "Infer per-column type from the data rows BELOW the header. :number when all
   present values are numeric, :string when none are, :mixed otherwise, :empty
   when the column has no values in the sample."
  [data-rows width]
  (into {}
        (for [c (range width)]
          (let [vals (keep #(get % c) data-rows)
                nums (filter numeric-cell? vals)]
            [c (cond
                 (empty? vals) :empty
                 (= (count nums) (count vals)) :number
                 (zero? (count nums)) :string
                 :else :mixed)]))))

;; =============================================================================
;; Tool implementations
;; =============================================================================

(defn- do-list-sheets [path]
  (assert-xlsx! path)
  (with-open [zf (ZipFile. (str path))]
    (let [sheets (workbook-sheets zf)]
      (vec (map-indexed (fn [i s] {:name (:name s) :index i :sheet-id (:sheet-id s)})
                        sheets)))))

(defn- do-sheet-columns [path selector]
  (assert-xlsx! path)
  (with-open [zf (ZipFile. (str path))]
    (let [target (sheet-target-for zf selector)
          shared (shared-strings zf)
          {:keys [rows max-col]} (stream-rows zf target shared 20)
          hidx (detect-header-index rows)
          header (nth rows hidx nil)
          data-rows (drop (inc hidx) rows)
          types (infer-col-types data-rows max-col)]
      {:sheet (if (string? selector) selector (:name (nth (workbook-sheets zf) selector nil)))
       :header header
       :header-row-index hidx
       :column-count max-col
       :types types
       :scanned-rows (vec rows)})))

(defn- do-sample-rows
  "Sample up to `n` rows from a worksheet. The optional final arg may be an
   integer `n` OR an opts map `{:limit <int> :offset <int>}`. `:offset` SKIPS
   that many leading worksheet rows before sampling — so a caller can reach
   real data that sits past a large leading block (title/note rows, or
   thousands of aggregate/subtotal rows) without ever loading the sheet."
  ([path selector] (do-sample-rows path selector 20))
  ([path selector n-or-opts]
   (assert-xlsx! path)
   (let [n (cond (integer? n-or-opts) n-or-opts
                 (map? n-or-opts) (or (:limit n-or-opts) (:n n-or-opts) 20)
                 :else 20)
         offset (if (map? n-or-opts) (or (:offset n-or-opts) 0) 0)]
     (with-open [zf (ZipFile. (str path))]
       (let [target (sheet-target-for zf selector)
             shared (shared-strings zf)
             req (if (integer? n) n 20)
             {:keys [rows max-col]} (stream-rows zf target shared req offset)]
         {:sheet (if (string? selector) selector selector)
          :rows rows
          :row-count (count rows)
          :offset offset
          :column-count max-col
          :capped? (>= (count rows) max-rows-hard-cap)})))))

(defn- do-excel-dir-sheets [dir]
  (let [d (File. (str dir))]
    (when-not (.isDirectory d)
      (throw (ex-info (str "excel-dir-sheets: not a directory: " dir) {:dir dir})))
    (->> (.listFiles d)
         (filter (fn [^File f]
                   (and (.isFile f)
                        (str/ends-with? (str/lower-case (.getName f)) ".xlsx"))))
         (sort-by #(.getName ^File %))
         (mapv (fn [^File f]
                 (let [p (.getAbsolutePath f)]
                   {:file (.getName f)
                    :path p
                    :sheets (try (mapv :name (do-list-sheets p))
                                 (catch Exception e
                                   {:error (.getMessage e)}))}))))))

;; =============================================================================
;; Docstrings (self-contained: PURPOSE / EXAMPLE / RETURNS)
;; =============================================================================

(def list-sheets-doc
  "PURPOSE — List every worksheet in an .xlsx workbook, in workbook order,
   WITHOUT loading any sheet's data. The first call you make when handed an
   Excel source: it tells you what tabs exist so you can target the rest.

   EXAMPLE
     (list-sheets \"/data/pseo_la.xlsx\")
     ;; => [{:name \"Earnings\" :index 0 :sheet-id \"1\"}
     ;;     {:name \"Flows\"    :index 1 :sheet-id \"2\"}]

   RETURNS — vector of {:name :index :sheet-id} maps, ordered as in the
   workbook. :index is the 0-based position you can pass to sheet-columns /
   sample-rows instead of the name.")

(def sheet-columns-doc
  "PURPOSE — Get the header + per-column types for ONE sheet by sampling its
   first rows only (never the whole sheet). Excel files routinely put title /
   source / note lines ABOVE the real header, so this does NOT assume row 1 is
   the header — it detects the densest mostly-text row and reports its index,
   and also returns the raw scanned rows so you can override the guess.

   EXAMPLE
     (sheet-columns \"/data/pseo_la.xlsx\" \"Flows\")
     ;; => {:sheet \"Flows\"
     ;;     :header [\"agg_level_pseo\" \"label_agg_level_pseo\" \"inst_level\" ...]
     ;;     :header-row-index 6      ; header was the 7th row, not the 1st
     ;;     :column-count 42
     ;;     :types {0 :string 1 :string 16 :number ...}  ; col-idx -> type
     ;;     :scanned-rows [[...] [...] ...]}             ; first ~20 rows raw
     ;; You may also select by 0-based index: (sheet-columns path 1)

   RETURNS — {:sheet :header :header-row-index :column-count :types
   :scanned-rows}. :types maps 0-based column index to one of
   :string / :number / :mixed / :empty, inferred from the rows below the
   detected header.")

(def sample-rows-doc
  "PURPOSE — Pull a BOUNDED sample of rows from one sheet by streaming the
   worksheet and stopping early — so a 119 MB sheet is sampled in milliseconds,
   never loaded. Use it to see real values before you design extraction.

   EXAMPLE
     (sample-rows \"/data/pseo_la.xlsx\" \"Earnings\" 5)
     ;; => {:sheet \"Earnings\"
     ;;     :rows [[\"agg_level_pseo\" \"label_agg_level_pseo\" ...]
     ;;            [\"26\" \"Degree Level\" \"*\" ...]
     ;;            ...]            ; at most 5 rows, each a dense cell vector
     ;;     :row-count 5
     ;;     :column-count 42
     ;;     :capped? false}
     ;; n defaults to 20 and is hard-capped (these tools SAMPLE, never load):
     (sample-rows \"/data/pseo_la.xlsx\" 0)   ; first 20 rows of sheet index 0

   OFFSET — the third arg may instead be a map {:limit <int> :offset <int>} to
   SKIP a large leading block and sample real data deeper in the sheet. This is
   essential for sheets whose first thousands of rows are subtotal/aggregate
   rows (e.g. PSEO's leading 'All Programs / All Degree Fields' state-level
   aggregates) before the real per-row data begins:
     (sample-rows \"/data/pseo_la.xlsx\" \"Earnings\" {:limit 30 :offset 4000})
     ;; => skips the first 4000 worksheet rows, then samples up to 30. The
     ;;    skipped rows are streamed-and-discarded (never loaded), so the
     ;;    sampling guarantee holds.

   RETURNS — {:sheet :rows :row-count :offset :column-count :capped?}. :rows is
   a vector of dense cell vectors (nil for absent cells). :capped? is true when
   the hard row cap was reached (there is more data than was sampled).")

(def excel-dir-sheets-doc
  "PURPOSE — Enumerate a DIRECTORY of .xlsx workbooks (the O*NET shape: dozens
   of single-sheet files), listing each file and its sheet names, WITHOUT
   loading any sheet's data. Use when the source is a folder of Excel files
   rather than one multi-sheet workbook.

   EXAMPLE
     (excel-dir-sheets \"/data/db_30_1_excel\")
     ;; => [{:file \"Abilities.xlsx\"
     ;;      :path \"/data/db_30_1_excel/Abilities.xlsx\"
     ;;      :sheets [\"Abilities\"]}
     ;;     {:file \"Alternate Titles.xlsx\" :path \"...\" :sheets [\"Alternate Titles\"]}
     ;;     ...]

   RETURNS — vector of {:file :path :sheets} maps, sorted by file name. :path is
   the absolute path you pass to list-sheets / sheet-columns / sample-rows for
   that workbook. A per-file read error surfaces as {:error \"...\"} in :sheets
   rather than aborting the whole enumeration.")

;; =============================================================================
;; Public binding builder
;; =============================================================================

(defn excel-source-tools
  "Return the SCI {symbol -> fn} map of the four Excel source-access tools,
   each carrying its self-contained docstring on the fn metadata (so a sandbox
   `(meta list-sheets)` and the docstring-quality test both read it).

   Tools:
     list-sheets       (path)                  -> [{:name :index :sheet-id}]
     sheet-columns     (path sheet)            -> {:header :header-row-index ...}
     sample-rows       (path sheet [n])        -> {:rows :row-count ...} (bounded)
     excel-dir-sheets  (dir)                   -> [{:file :path :sheets}]

   Read-side only: every tool opens the workbook read-only and returns data;
   none emits a Grain event. Takes no cfg — these are filesystem readers, not
   graph-scoped tools (contrast sandbox-tools/build-ontology-tool-bindings,
   which needs an event-store + grant)."
  []
  (let [with-doc (fn [f doc] (with-meta f {:doc doc}))]
    {'list-sheets      (with-doc (fn list-sheets [path] (do-list-sheets path))
                         list-sheets-doc)
     'sheet-columns    (with-doc (fn sheet-columns [path sheet] (do-sheet-columns path sheet))
                         sheet-columns-doc)
     'sample-rows      (with-doc (fn sample-rows
                                   ([path sheet] (do-sample-rows path sheet))
                                   ([path sheet n] (do-sample-rows path sheet n)))
                         sample-rows-doc)
     'excel-dir-sheets (with-doc (fn excel-dir-sheets [dir] (do-excel-dir-sheets dir))
                         excel-dir-sheets-doc)}))

(def excel-source-tool-docs
  "The {symbol -> docstring} map for the V05 Excel source tools. Exposed so the
   V06 unified registry + orientation surfaces can pull the same docstrings the
   sandbox sees without building bindings."
  {'list-sheets      list-sheets-doc
   'sheet-columns    sheet-columns-doc
   'sample-rows      sample-rows-doc
   'excel-dir-sheets excel-dir-sheets-doc})
