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

(defn- normalize-selector
  "V19 — forgiving sheet selector. Accepts:
     - a name STRING                          (\"Earnings\")
     - a 0-based INDEX                        (1)
     - a DESCRIPTOR MAP as list-sheets returns ({:name \"Earnings\" :index 0 ...})
       — passing back the exact map a prior list-sheets call returned must
       resolve, not throw (the V17 failure).
   A descriptor map is reduced to its :name (preferred) or :index. Any other
   shape (a vector, a set, nil) is left as-is so sheet-target-for raises a
   teaching error naming the accepted forms."
  [selector]
  (cond
    (map? selector) (let [{nm :name idx :index} selector]
                      (cond (string? nm) nm
                            (integer? idx) idx
                            :else selector))   ; an empty/foreign map -> loud below
    :else selector))

(defn- sheet-target-for
  "Resolve a sheet selector (name string, 0-based index, 1-based sheetId, OR a
   descriptor MAP as list-sheets returns) to its worksheet entry path. Throws a
   clear TEACHING error listing the available sheet names + the accepted selector
   forms if the selector matches nothing."
  [^ZipFile zf selector]
  (let [sheets (workbook-sheets zf)
        rmap (rid->target zf)
        sel (normalize-selector selector)
        by-name (cond
                  (string? sel)
                  (first (filter #(= sel (:name %)) sheets))
                  (integer? sel)
                  (get (vec sheets) sel)
                  :else nil)
        chosen (or by-name
                   ;; fall back: treat a numeric string as an index
                   (when (and (string? sel) (re-matches #"\d+" sel))
                     (get (vec sheets) (Integer/parseInt sel))))]
    (when-not chosen
      (throw (ex-info (str "Excel source tool: no sheet matching " (pr-str selector)
                           ". A sheet selector is a NAME string, a 0-based INDEX, or "
                           "the descriptor MAP a list-sheets call returned "
                           "(e.g. {:name \"Earnings\" :index 0}). Available sheets: "
                           (mapv :name sheets))
                      {:selector selector :available (mapv :name sheets)})))
    (or (get rmap (:rid chosen))
        ;; positional fallback when rels lacks the r:id
        (str "xl/worksheets/sheet" (inc (.indexOf (vec sheets) chosen)) ".xml"))))

(defn- selector-display-name
  "The sheet NAME for a selector (for echoing in a result :sheet), resolving a
   descriptor map / index against the workbook. Falls back to the raw selector
   when it cannot be resolved to a name (it will already have thrown upstream if
   truly unresolvable)."
  [^ZipFile zf selector]
  (let [sel (normalize-selector selector)
        sheets (workbook-sheets zf)]
    (cond
      (string? sel) sel
      (integer? sel) (:name (nth (vec sheets) sel nil))
      :else (str selector))))

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

(defn- count-worksheet-rows
  "V19 — stream a worksheet and COUNT its <row> elements WITHOUT accumulating any
   cell data. The whole point: report a sheet's cardinality (so a specialist
   knows how much remains / V20 can bound a run) without a full load — only a
   counter is kept in memory, never the rows. Closes the stream when done."
  [^ZipFile zf target]
  (let [e (or (.getEntry zf target)
              (throw (ex-info (str "Excel source tool: worksheet entry missing: " target)
                              {:target target})))]
    (with-open [is (.getInputStream zf e)]
      (let [r (xml-reader is)
            n (volatile! 0)]
        (try
          (while (.hasNext r)
            (when (and (= (.next r) XMLStreamConstants/START_ELEMENT)
                       (= "row" (.getLocalName r)))
              (vswap! n inc)))
          (finally (.close r)))
        @n))))

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

(def ^:private header-scan-window
  "Rows pulled from the TOP of a worksheet to detect the header. Matches the
   window do-sheet-columns scans, so MC-2's keyed sample/stream key by the SAME
   header sheet-columns reports (P8 — one header-detection path, no fork)."
  20)

(defn- detect-sheet-header
  "Reuse the V05 header detection (the SAME logic do-sheet-columns uses) to find,
   for an already-open workbook + resolved worksheet target, the column header and
   its 0-based worksheet-row index. Returns {:header [<cell>...] :header-row-index
   <int> :column-count <int>}. Streams only the top `header-scan-window` rows — the
   bounded-read guarantee holds even when the caller goes on to sample deep rows."
  [^ZipFile zf target shared]
  (let [{:keys [rows max-col]} (stream-rows zf target shared header-scan-window)
        hidx (detect-header-index rows)]
    {:header (nth rows hidx nil)
     :header-row-index hidx
     :column-count max-col}))

(defn- key-name
  "The map key for a column: its detected header cell when present + non-blank,
   else a positional fallback (\"column-<idx>\") so a data cell that sits past the
   header width — or under a blank header cell — is NEVER silently dropped
   (Discipline #5)."
  [header idx]
  (let [h (nth header idx nil)]
    (if (and (string? h) (seq (str/trim h)))
      h
      (str "column-" idx))))

(defn- key-row
  "Project ONE positional cell vector into a column-name→value map, keyed by
   `header`. Every cell index gets a key (header cell, or a positional fallback for
   cells past the header width / under a blank header), so no value is lost."
  [header row]
  (into {} (map-indexed (fn [idx v] [(key-name header idx) v]) row)))

(defn- key-rows
  "Project the positional `rows` of a window that began at absolute worksheet row
   `offset` into KEYED maps, DROPPING any rows at-or-above the header row (the
   header itself + any leading title rows). A row at window position j is absolute
   worksheet row (offset + j); it is data iff (offset + j) > header-row-index."
  [header header-row-index offset rows]
  (->> rows
       (map-indexed (fn [j r] [(+ (long (or offset 0)) j) r]))
       (filter (fn [[abs _]] (> abs (long header-row-index))))
       (mapv (fn [[_ r]] (key-row header r)))))

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
      {:sheet (selector-display-name zf selector)
       :header header
       :header-row-index hidx
       :column-count max-col
       :types types
       :scanned-rows (vec rows)})))

(defn- do-sample-rows
  "Sample up to `n` rows from a worksheet, returned as KEYED maps
   (column-header→value) — NOT positional cell vectors. The header is detected via
   the SAME logic sheet-columns uses (P8), so the keys are the real column headers
   even when title/source/note lines precede the header (the PSEO/Census case); the
   header row and any leading title rows are EXCLUDED from `:rows`.

   The optional final arg may be an integer `n` OR an opts map
   `{:limit <int> :offset <int>}`. `:offset` SKIPS that many leading worksheet rows
   before sampling — so a caller can reach real data that sits past a large leading
   block (title/note rows, or thousands of aggregate/subtotal rows) without ever
   loading the sheet. `n`/`:limit` bounds the number of DATA rows returned (the
   header/title rows it drops do not count against it)."
  ([path selector] (do-sample-rows path selector 20))
  ([path selector n-or-opts]
   (assert-xlsx! path)
   ;; V19 — a wrong-shape limit/offset arg is a TEACHING error, not a confusing
   ;; downstream cast. Accept an integer N or an opts map {:limit :offset};
   ;; anything else names the correct call form.
   (when-not (or (integer? n-or-opts) (map? n-or-opts))
     (throw (ex-info (str "sample-rows takes (sample-rows path sheet) , "
                          "(sample-rows path sheet N) , or "
                          "(sample-rows path sheet {:limit N :offset K}). The 3rd "
                          "arg must be an integer row count OR an options map with "
                          ":limit / :offset — got " (pr-str n-or-opts) ". To page a "
                          "sheet, put :offset inside the opts map (do NOT pass it as "
                          "a 4th argument).")
                     {:bad-arg n-or-opts})))
   (let [n (cond (integer? n-or-opts) n-or-opts
                 (map? n-or-opts) (or (:limit n-or-opts) (:n n-or-opts) 20)
                 :else 20)
         offset (if (map? n-or-opts) (or (:offset n-or-opts) 0) 0)]
     (with-open [zf (ZipFile. (str path))]
       (let [target (sheet-target-for zf selector)
             shared (shared-strings zf)
             req (if (integer? n) n 20)
             {:keys [header header-row-index]} (detect-sheet-header zf target shared)
             ;; When the sample window starts at/above the header (small offset),
             ;; the header + any leading title rows fall INSIDE it and are dropped;
             ;; over-fetch by that many physical rows so `req` DATA rows still come
             ;; back. The hard cap inside stream-rows still bounds the actual pull.
             dropped-leading (max 0 (- (inc (long header-row-index)) (long offset)))
             fetch (+ (max 0 (long req)) dropped-leading)
             {:keys [rows max-col]} (stream-rows zf target shared fetch offset)
             physical (count rows)
             keyed-all (key-rows header header-row-index offset rows)
             keyed (vec (take req keyed-all))
             ;; PHYSICAL worksheet rows consumed to produce `keyed` — the absolute
             ;; offset a pager (stream-all) must RESUME from so windows tile the
             ;; sheet without overlap or gap. It is NOT the same as :row-count once
             ;; header/title rows are dropped: at a small offset we over-fetch and
             ;; discard the leading block, so we resume PAST those physical rows.
             consumed (if (<= (count keyed-all) req)
                        physical                            ; whole window taken
                        (+ dropped-leading (count keyed)))  ; stopped at req data rows
             next-offset (+ (long offset) (long consumed))
             ;; The sheet is EXHAUSTED for this window when the stream yielded
             ;; fewer physical rows than we asked to fetch AND the hard cap did not
             ;; truncate us. stream-all uses this (not the data-row count) to know
             ;; when to stop, since over-fetching to refill dropped header/title
             ;; rows can make a full window return < :limit data rows.
             cap-limited? (>= physical max-rows-hard-cap)
             exhausted? (and (< physical fetch) (not cap-limited?))]
         {:sheet (selector-display-name zf selector)
          :rows keyed
          :header header
          :header-row-index header-row-index
          :row-count (count keyed)
          :offset offset
          :next-offset next-offset
          :exhausted? exhausted?
          :column-count max-col
          :capped? cap-limited?}))))
  ;; V19 — a 4th positional arg (the V17 mistake) is caught with a teaching error
  ;; instead of a raw arity exception. The fix: put :offset in the opts map.
  ([path selector n-or-opts & extra]
   (throw (ex-info (str "sample-rows takes at most 3 args: "
                        "(sample-rows path sheet {:limit N :offset K}). You passed "
                        (+ 3 (count extra)) " args (an extra " (pr-str (vec extra))
                        "). Pass :limit and :offset INSIDE the opts map, not as "
                        "separate positional args, e.g. "
                        "(sample-rows path \"Earnings\" {:limit 100 :offset 0}).")
                   {:path path :selector selector
                    :n-or-opts n-or-opts :extra (vec extra)}))))

(defn- do-count-rows
  "V19 — total <row> count of a sheet WITHOUT loading it (count-worksheet-rows
   keeps only a counter). The selector is forgiving (name / index / descriptor
   map)."
  [path selector]
  (assert-xlsx! path)
  (with-open [zf (ZipFile. (str path))]
    (let [target (sheet-target-for zf selector)]
      {:sheet (selector-display-name zf selector)
       :row-count (count-worksheet-rows zf target)})))

(defn- do-stream-all
  "V19 — iterate a sheet's FULL row set in bounded windows. Each window is a
   do-sample-rows-shaped map; consecutive windows use :offset so together they
   cover every worksheet row exactly once. The per-call hard cap is preserved:
   a :window above the cap is clamped to it (the iteration, not a single call, is
   what achieves coverage — the substrate V20's full extraction applies a
   transform over).

   opts: {:window <int, default cap> :offset <int, start, default 0>
          :max-windows <int, safety ceiling, default 100000>}
   Returns a VECTOR of window maps. Stops when a window comes back short of the
   window size (the sheet is exhausted)."
  ([path selector] (do-stream-all path selector {}))
  ([path selector opts]
   (assert-xlsx! path)
   (when-not (map? (or opts {}))
     (throw (ex-info (str "stream-all opts must be a map {:window N :offset K}; got "
                          (pr-str opts))
                     {:bad-arg opts})))
   (let [opts (or opts {})
         req-win (or (:window opts) (:limit opts) max-rows-hard-cap)
         win (min (max 1 (long (if (number? req-win) req-win max-rows-hard-cap)))
                  max-rows-hard-cap)
         start (long (or (:offset opts) 0))
         max-windows (long (or (:max-windows opts) 100000))]
     (loop [offset start
            windows []
            guard 0]
       (if (>= guard max-windows)
         windows
         (let [w (do-sample-rows path selector {:limit win :offset offset})
               n (:row-count w)]
           (cond
             (zero? n) windows
             ;; Stop when the underlying stream is EXHAUSTED (not merely when this
             ;; window returned < :limit DATA rows): over-fetching to refill dropped
             ;; header/title rows can make a full window return fewer keyed rows than
             ;; :limit while the sheet still has more — keying on :row-count would
             ;; stop early and lose coverage.
             (:exhausted? w) (conj windows w)         ; last (sheet exhausted) window
             ;; Resume from the PHYSICAL rows consumed (:next-offset), not the
             ;; data-row count — once header/title rows are dropped the two diverge,
             ;; so stepping by :row-count would overlap/skip rows (MC-2 keyed
             ;; conversion makes this distinction load-bearing).
             :else (recur (:next-offset w) (conj windows w) (inc guard)))))))))

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
;; CONNECT-2 — the container-contract `relations` op: a DOMAIN-AGNOSTIC shared-key
;; cross-sheet heuristic.
;;
;; The uniform container contract (source_tools.clj MC-1) calls for a `relations`
;; operation per container returning `[{:from :to :via}]`, which MC-6
;; (extract_subbehavior) consumes to derive CROSS-CONTAINER edges (it joins entities
;; across containers sharing the `:via` key VALUE). SQL derives it from declared FKs
;; + a shared-PRIMARY-KEY heuristic; excel exposed NOTHING (nil), so for an O*NET
;; directory MC-6 never fired and occupations got 0 cross-sheet edges.
;;
;; This op MATCHES the SQL relations SHAPE exactly — `{:from "<sheet>.<col>"
;; :to "<other-sheet>.<col>" :via "<col>"}` (source_tools_sql make-relations-fn) —
;; so MC-6 consumes it unchanged. It reads each sheet's HEADER columns (REUSING the
;; V05 header capability, injected + faked in tests — NO fork of excel reading) and
;; treats a column that is (a) shared across >= 2 sheets AND (b) ID/code-SHAPED by
;; NAME as a JOIN KEY, emitting a relation from the queried sheet to every OTHER
;; sheet carrying that key column.
;;
;; Discipline #7/#12 — the heuristic bakes in NO field name. The key-shape test keys
;; on a GENERIC identifier vocabulary token ("id"/"code"/"key"/…) carried by the
;; column name, the universal language of join keys (mirroring the csv peek-columns
;; code/identifier inference), NOT on any literal column ("O*NET-SOC Code" qualifies
;; only because it carries the generic token "code" — never because "soc" is baked
;; in). Biasing toward id/code names over mere co-occurrence is what keeps a column
;; that co-occurs everywhere but is a free-text label or a measure ("Title",
;; "Description", "Data Value", "Date") from producing a spurious relation.
;; =============================================================================

(def ^:private id-like-name-tokens
  "Generic, DOMAIN-AGNOSTIC identifier vocabulary. A column whose NAME carries one
   of these as a token is ID/code-shaped — the universal language of join keys. This
   names NO specific column."
  #{"id" "code" "key" "ref" "uuid" "guid"})

(defn- name-tokens
  "Tokenize a column NAME for the key-shape test: split camelCase boundaries, then
   split on every non-alphanumeric run, lower-cased. So `O*NET-SOC Code` ->
   [\"o\" \"net\" \"soc\" \"code\"], `widget_id` -> [\"widget\" \"id\"],
   `customerId` -> [\"customer\" \"id\"]."
  [col-name]
  (->> (-> (str col-name)
           (str/replace #"([a-z])([A-Z])" "$1 $2")
           str/lower-case)
       (#(str/split % #"[^a-z0-9]+"))
       (remove str/blank?)))

(defn- id-like-column?
  "Structural key-shape test: TRUE when the column NAME carries a generic identifier
   token (see `id-like-name-tokens`). Domain-agnostic — biases toward id/code
   columns and AWAY from free-text/measure columns (`Description`, `Data Value`,
   `Title`, `Date` carry no such token, so they never become spurious join keys)."
  [col-name]
  (boolean (some id-like-name-tokens (name-tokens col-name))))

(defn- normalize-col-name
  "Normalize a column NAME for case/whitespace-tolerant cross-sheet grouping: lower
   the string form and strip every non-alphanumeric character, so `O*NET-SOC Code`,
   `o*net-soc code`, `ONETSOCCode` all collapse to `onetsoccode`. Purely structural."
  [col-name]
  (-> (str col-name) str/lower-case (str/replace #"[^a-z0-9]+" "")))

(defn- read-dir-sheet-headers
  "The DEFAULT (real) header-reader capability: enumerate every sheet across the
   excel SOURCE (a DIRECTORY of workbooks via `do-excel-dir-sheets`, or a single
   workbook via `do-list-sheets`) and return its detected HEADER column names,
   REUSING `do-sheet-columns` (the V05 header detection — NO fork). Returns
   `[{:sheet <name> :columns [<col-name> …]} …]`; a sheet whose header can't be read
   contributes empty `:columns` (honest, never a crash). Streams only each sheet's
   header window — the bounded-read guarantee holds."
  [source-path]
  (let [f (File. (str source-path))
        sheet-refs (if (.isDirectory f)
                     (for [{:keys [path sheets]} (do-excel-dir-sheets source-path)
                           :when (sequential? sheets)
                           s sheets]
                       {:path path :sheet s})
                     (for [{:keys [name]} (do-list-sheets source-path)]
                       {:path (str source-path) :sheet name}))]
    (mapv (fn [{:keys [path sheet]}]
            {:sheet sheet
             :columns (try
                        (->> (:header (do-sheet-columns path sheet))
                             (filter (fn [h] (and (string? h) (seq (str/trim h)))))
                             vec)
                        (catch Throwable _ []))})
          sheet-refs)))

(defn- container-name-of
  "The sheet NAME a relations call selects on, from a bare name string OR a
   `list-containers` entry map. Tolerant so the contract may hand either."
  [container]
  (cond
    (map? container) (or (:name container) (:sheet container) (:table container))
    :else container))

(defn make-excel-relations-fn
  "CONNECT-2 — build the excel container-contract `:relations` op for an excel
   SOURCE at `source-path` (a directory of workbooks, or a single workbook). Returns
   a `(container-name) -> [{:from :to :via} …]` fn matching the SQL relations SHAPE
   MC-6 consumes: `:from \"<sheet>.<col>\"`, `:to \"<other-sheet>.<col>\"`,
   `:via \"<col>\"`.

   Deterministic, DOMAIN-AGNOSTIC shared-key heuristic (see the section comment): a
   column whose NAME is id/code-shaped (`id-like-column?`) and appears in >= 2 sheets
   is a JOIN KEY; for the queried sheet, emit one relation to every OTHER sheet
   carrying that key column, sorted by (:to :via) for stable output. Returns [] when
   the sheet shares no key column (honest — never a fabricated edge). Names NO domain
   column.

   `headers-reader` is the injected header capability (defaults to the real
   `read-dir-sheet-headers`; faked in tests): `(source-path) -> [{:sheet :columns}]`.
   The headers are read ONCE (lazily, on first call) and cached, so repeated
   per-container calls do not re-scan the workbooks."
  ([source-path] (make-excel-relations-fn source-path read-dir-sheet-headers))
  ([source-path headers-reader]
   (let [;; normalized-col -> {sheet-name -> display-col-name}, built once, keeping
         ;; only ID/code-shaped columns SHARED across >= 2 sheets (a join needs both
         ;; sides). Deterministic — pure set logic over the header names.
         key-index
         (delay
           (let [by-norm (reduce
                          (fn [acc {:keys [sheet columns]}]
                            (reduce
                             (fn [a col]
                               (if (id-like-column? col)
                                 (update a (normalize-col-name col)
                                         (fnil assoc {}) sheet col)
                                 a))
                             acc
                             columns))
                          {}
                          (headers-reader source-path))]
             (into {} (filter (fn [[_ sheet->col]] (>= (count sheet->col) 2)) by-norm))))]
     (fn relations [container-selector]
       (let [sheet (container-name-of container-selector)]
         (->> @key-index
              (mapcat (fn [[_ sheet->col]]
                        (when-let [my-col (get sheet->col sheet)]
                          (for [[other other-col] sheet->col
                                :when (not= other sheet)]
                            {:from (str sheet "." my-col)
                             :to   (str other "." other-col)
                             :via  my-col}))))
              (remove nil?)
              (sort-by (juxt :to :via))
              vec))))))

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

(def count-rows-doc
  "PURPOSE — Report a sheet's TOTAL row count WITHOUT loading it, so you know how
   much data remains before you page or stream. The sample tools cap at 500 rows;
   this tells you the real size (e.g. a PSEO sheet with tens of thousands of
   rows) so you can decide how many windows stream-all will need.

   EXAMPLE
     (count-rows \"/data/pseo_la.xlsx\" \"Earnings\")
     ;; => {:sheet \"Earnings\" :row-count 41234}
     ;; The sheet selector is forgiving — a name, a 0-based index, or the
     ;; descriptor map list-sheets returned all work:
     (count-rows \"/data/pseo_la.xlsx\" {:name \"Earnings\" :index 0})

   RETURNS — {:sheet :row-count}. :row-count is the full <row> count of the
   sheet (header rows included), computed by streaming and counting only — no
   cell data is held in memory, so it is safe on a 119 MB sheet.")

(def stream-all-doc
  "PURPOSE — Iterate a sheet's ENTIRE row set in bounded windows, so you can
   cover every row without ever loading the whole sheet into context. Builds on
   the :offset paging sample-rows already has: each window is one bounded
   sample-rows result, and consecutive windows step by :offset so together they
   cover every row exactly once. This is the substrate a deterministic
   full-extraction transform runs over.

   EXAMPLE
     (stream-all \"/data/pseo_la.xlsx\" \"Earnings\")            ; default window
     (stream-all \"/data/pseo_la.xlsx\" \"Earnings\" {:window 500})
     ;; => [{:sheet \"Earnings\" :rows [[...] ...] :row-count 500 :offset 0 ...}
     ;;     {:sheet \"Earnings\" :rows [[...] ...] :row-count 500 :offset 500 ...}
     ;;     ... last window is short when the sheet is exhausted]
     ;; Concatenate the windows' :rows to get every row, in order:
     (mapcat :rows (stream-all \"/data/pseo_la.xlsx\" \"Earnings\" {:window 500}))

   WINDOW — :window is the rows-per-window; it is CLAMPED to the 500-row per-call
   hard cap (the cap is never lifted — coverage comes from MANY windows, not one
   big call). Start partway in with :offset; bound the loop with :max-windows.
   The selector is forgiving (name / index / descriptor map).

   RETURNS — a VECTOR of window maps, each shaped like sample-rows
   ({:sheet :rows :row-count :offset :column-count :capped?}). Iteration stops at
   the first short/empty window (the sheet is exhausted).")

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
                                   ([path sheet n] (do-sample-rows path sheet n))
                                   ;; V19 — a 4th+ arg (the V17 mistake) is a
                                   ;; teaching error, not a raw arity exception.
                                   ([path sheet n & extra]
                                    (apply do-sample-rows path sheet n extra)))
                         sample-rows-doc)
     'count-rows       (with-doc (fn count-rows [path sheet] (do-count-rows path sheet))
                         count-rows-doc)
     'stream-all       (with-doc (fn stream-all
                                   ([path sheet] (do-stream-all path sheet))
                                   ([path sheet opts] (do-stream-all path sheet opts)))
                         stream-all-doc)
     'excel-dir-sheets (with-doc (fn excel-dir-sheets [dir] (do-excel-dir-sheets dir))
                         excel-dir-sheets-doc)}))

(def excel-source-tool-docs
  "The {symbol -> docstring} map for the V05 Excel source tools. Exposed so the
   V06 unified registry + orientation surfaces can pull the same docstrings the
   sandbox sees without building bindings."
  {'list-sheets      list-sheets-doc
   'sheet-columns    sheet-columns-doc
   'sample-rows      sample-rows-doc
   'count-rows       count-rows-doc
   'stream-all       stream-all-doc
   'excel-dir-sheets excel-dir-sheets-doc})
