(ns ai.obney.orc.orc-service.core.source-tools-sql
  "V04 — SQL / SQLite SOURCE-ACCESS tools for the discovery RLM.

   The per-format tool leg of the format-aware-ingestion ADR (M-P1). Where the
   S19 ontology tools (sandbox_tools.clj) let a recursive-RLM session explore an
   ontology GRAPH without loading it, these let the same session explore a
   relational SOURCE — a SQLite database — by its SCHEMA, sampling and querying
   it WITHOUT ever dumping the whole database into the model context. A 60+-table
   IPEDS-shaped DB is fully explorable through these five tools.

   The five tools, in the order they appear here:

   - list-tables   — every user table name (the class candidates: each table
                     maps to an ontology class).
   - table-schema  — columns + types + pk/notnull for one table (the property
                     candidates: each column maps to a datatype property).
   - foreign-keys  — declared FK edges out of one table (the relationship
                     candidates: each FK maps to an object property /
                     table->table relationship).
   - sample-rows   — a bounded sample of rows from one table (the individual
                     candidates: each row maps to an A-box individual). NEVER
                     the whole table — a hard cap is enforced inside the tool.
   - query         — a READ-ONLY, BOUNDED SELECT for targeted exploration the
                     fixed tools don't cover (joins, distinct value scans,
                     counts). Writes/DDL are rejected; unbounded results are
                     hard-capped.

   The schema->ontology mapping knowledge (tables->classes, columns->properties,
   foreign-keys->relationships, rows->individuals) is the old `sql_ontology`
   sheet's PRAGMA-introspection knowledge, ported into these tools' internals
   (reuse, not rewrite). The RLM reads the schema through these tools and designs
   the extraction; it does not re-derive how to read SQLite.

   ## Read-only invariant (adversarial requirement)

   EVERY connection is opened SQLITE_OPEN_READONLY (open_mode=1). A write or DDL
   reaching the database is rejected by the SQLite engine itself — this is the
   authoritative guard, not string inspection. `query` additionally rejects any
   statement that is not a single SELECT (or PRAGMA/EXPLAIN read) BEFORE it
   reaches the driver, so a smuggled `SELECT 1; DROP TABLE t` fails with a clear
   teaching message rather than a confusing driver error. Both layers hold; the
   engine-level read-only mode is the one that cannot be bypassed.

   ## Bounded invariant (adversarial requirement)

   sample-rows and query NEVER materialize a whole table. A hard JDBC-level row
   cap (Statement.setMaxRows) is set on every statement, independent of the query
   text, so even a `SELECT * FROM huge_table` with no LIMIT stops at the cap. A
   caller-supplied :limit is honored only up to that hard max.

   ## Read-side only

   These tools open a read-only connection and project schema/rows; they emit no
   Grain events and cannot mutate the source. They are deliberately separate from
   the write-side primitives.

   ## Wiring

   `sql-source-tools` returns the {symbol -> fn} map a SCI sandbox can merge into
   its bindings (the V06 unified source-tool registry composes this with the csv
   and excel legs). It returns nil when no :db-path is granted — the sandbox MUST
   NOT silently expose source tools without a source."
  (:require [clojure.string :as str])
  (:import [java.sql DriverManager Statement ResultSet]
           [java.util Properties]))

;; =============================================================================
;; Read-only connection
;; =============================================================================

;; SQLITE_OPEN_READONLY = 0x1. Opening with this open_mode makes the SQLite
;; engine itself reject any write/DDL — the authoritative read-only guard. We
;; never open a writable connection from these tools.
(def ^:private sqlite-open-readonly "1")

(defn- read-only-connection
  "Open a SQLITE_OPEN_READONLY JDBC connection to db-path. The engine rejects
   any write reaching it; this is the read-only invariant's authoritative layer."
  [db-path]
  (let [props (doto (Properties.)
                (.setProperty "open_mode" sqlite-open-readonly))]
    (DriverManager/getConnection (str "jdbc:sqlite:" db-path) props)))

;; =============================================================================
;; Bounded result reading
;; =============================================================================

;; Hard JDBC-level cap applied to EVERY statement regardless of query text. This
;; is what makes "never dump the whole table" true even for a LIMIT-less SELECT.
(def ^:private hard-max-rows 100)

(defn- rs->maps
  "Read a ResultSet into a vector of {col-keyword -> value} maps, preserving
   column order. Bounded by the statement's max-rows (set by the caller)."
  [^ResultSet rs]
  (let [meta (.getMetaData rs)
        n (.getColumnCount meta)
        cols (mapv (fn [i] [(keyword (.getColumnLabel meta (inc i)))
                            (inc i)])
                   (range n))]
    (loop [acc (transient [])]
      (if (.next rs)
        (recur (conj! acc (reduce (fn [m [k idx]] (assoc m k (.getObject rs (int idx))))
                                  {} cols)))
        (persistent! acc)))))

(defn- run-bounded
  "Open a read-only connection, run `sql` as a query, and return up to `max-rows`
   result maps. `max-rows` is enforced via Statement.setMaxRows (a hard cap that
   the query text cannot exceed). Closes everything."
  [db-path sql max-rows]
  (with-open [conn (read-only-connection db-path)
              st   (.createStatement conn)]
    (.setMaxRows ^Statement st (int max-rows))
    (with-open [rs (.executeQuery st sql)]
      (rs->maps rs))))

;; =============================================================================
;; Statement-shape guard for `query` (teaching layer atop the engine guard)
;; =============================================================================

(defn- strip-sql-comments
  "Remove -- line comments and /* */ block comments so the leading-keyword and
   single-statement checks see the real SQL."
  [sql]
  (-> sql
      (str/replace #"(?s)/\*.*?\*/" " ")
      (str/replace #"--[^\n]*" " ")))

(defn- single-read-statement?
  "True iff `sql` is a single read statement (SELECT / WITH / PRAGMA / EXPLAIN)
   with no smuggled trailing statement after a semicolon. A trailing bare `;`
   (with only whitespace after) is tolerated."
  [sql]
  (let [clean (str/trim (strip-sql-comments sql))
        ;; Drop one optional trailing semicolon, then ensure no further `;`
        ;; introduces a second statement.
        no-trailing (str/replace clean #";\s*$" "")
        single? (not (str/includes? no-trailing ";"))
        lead (-> no-trailing (str/split #"\s+") first (or "") str/upper-case)]
    (and single?
         (contains? #{"SELECT" "WITH" "PRAGMA" "EXPLAIN"} lead))))

;; =============================================================================
;; V19 — forgiving table selector (consistent with the Excel sheet selector)
;; =============================================================================

(defn- resolve-table-name
  "V19 — accept a table selector as a NAME string OR a descriptor MAP carrying
   :name (or :table) — so a builder can pass back a descriptor map (the shape the
   Excel list-sheets tools return) without an arity/cast error. Any other shape
   raises a TEACHING error naming the accepted forms and the example call.

   `tool` is the tool name for the message (e.g. \"sample-rows\")."
  [tool selector]
  (let [nm (cond
             (string? selector) selector
             (map? selector) (let [n (or (:name selector) (:table selector))]
                               (when (string? n) n)))]
    (or nm
        (throw (ex-info (str tool " takes a table-NAME string or the descriptor "
                             "MAP a table listing returns (e.g. {:name \"HD2022\"}), "
                             "then an optional opts map. Got: " (pr-str selector))
                        {:selector selector})))))

;; =============================================================================
;; Tool: list-tables
;; =============================================================================

(defn- make-list-tables-fn [db-path]
  (fn list-tables []
    (->> (run-bounded db-path
                      (str "SELECT name FROM sqlite_master "
                           "WHERE type='table' AND name NOT LIKE 'sqlite_%' "
                           "ORDER BY name")
                      ;; table count is small + bounded by definition; allow a
                      ;; generous cap so a 60-table DB lists fully.
                      10000)
         (mapv (comp str :name)))))

(def list-tables-doc
  "PURPOSE — List every user table in the SQLite source (internal sqlite_*
   tables excluded). Each table is a class candidate (table -> ontology class).
   This is your starting point: see what's in the database before you read any
   schema. Loads ZERO row data.

   EXAMPLE
     (list-tables)
     ;; => [\"CIPCodes\" \"HD2022\" \"IC2022\" ...]   ;; e.g. the 59 IPEDS tables

   RETURNS — a vector of table-name strings, alphabetically sorted. Empty vector
   for a database with no user tables.")

;; =============================================================================
;; Tool: table-schema
;; =============================================================================

(defn- make-table-schema-fn [db-path]
  (fn table-schema [table-selector]
    (let [table-name (resolve-table-name "table-schema" table-selector)]
    (->> (run-bounded db-path
                      ;; PRAGMA table_info — the sql_ontology schema-read knowledge.
                      (str "PRAGMA table_info('" table-name "')")
                      10000)
         (mapv (fn [c]
                 {:name (str (:name c))
                  :type (or (some-> (:type c) str) "")
                  :primary-key (pos? (long (or (:pk c) 0)))
                  :nullable (zero? (long (or (:notnull c) 0)))
                  :default (:dflt_value c)}))))))

(def table-schema-doc
  "PURPOSE — Read one table's COLUMNS + TYPES (plus primary-key and nullability)
   without reading any rows. Each column is a property candidate (column ->
   datatype property); the primary key tells you the individual's identity
   column. Use after list-tables to design what a table contributes to the graph.

   EXAMPLE
     (table-schema \"employee\")
     ;; => [{:name \"id\"      :type \"INTEGER\" :primary-key true  :nullable false :default nil}
     ;;     {:name \"name\"    :type \"TEXT\"    :primary-key false :nullable false :default nil}
     ;;     {:name \"salary\"  :type \"REAL\"    :primary-key false :nullable true  :default nil}
     ;;     {:name \"dept_id\" :type \"INTEGER\" :primary-key false :nullable true  :default nil}]

   RETURNS — a vector of column maps {:name :type :primary-key :nullable
   :default}, in column order. Empty vector if the table has no columns or does
   not exist (no exception for a missing table — PRAGMA returns nothing).")

;; =============================================================================
;; Tool: foreign-keys
;; =============================================================================

(defn- make-foreign-keys-fn [db-path]
  (fn foreign-keys [table-selector]
    (let [table-name (resolve-table-name "foreign-keys" table-selector)]
    (->> (run-bounded db-path
                      ;; PRAGMA foreign_key_list — the sql_ontology FK-read knowledge.
                      (str "PRAGMA foreign_key_list('" table-name "')")
                      10000)
         (mapv (fn [fk]
                 ;; A directional table->table edge the RLM turns into an object
                 ;; property: <from-table>.<from-column> -> <to-table>.<to-column>
                 {:from-table table-name
                  :from-column (str (:from fk))
                  :to-table (str (:table fk))
                  :to-column (str (:to fk))}))))))

(def foreign-keys-doc
  "PURPOSE — List the DECLARED foreign-key edges out of one table. Each FK is a
   relationship candidate (foreign key -> object property): a directional
   table->table link the RLM can author as a graph edge. Use to discover how
   tables connect before designing relationships.

   NOTE — this returns only FKs DECLARED in the schema. Some databases (IPEDS
   among them) declare no FKs and instead link tables by SHARED KEY COLUMNS
   (e.g. UNITID, CIPCODE appearing in many tables). When this returns [], scan
   table-schema across tables for repeated id/code columns and sample-rows to
   confirm the join — those shared keys are real relationships too.

   EXAMPLE
     (foreign-keys \"employee\")
     ;; => [{:from-table \"employee\" :from-column \"dept_id\"
     ;;      :to-table \"department\" :to-column \"id\"}]

   RETURNS — a vector of edge maps {:from-table :from-column :to-table
   :to-column}. Empty vector when the table declares no foreign keys.")

;; =============================================================================
;; Tool: sample-rows
;; =============================================================================

(defn- clamp-limit
  "Resolve the effective row cap: a caller :limit honored only up to hard-max-rows
   (the bounded invariant — an absurd :limit can never dump the whole table)."
  [opts]
  (let [req (get opts :limit hard-max-rows)
        req (if (and (number? req) (pos? req)) (long req) hard-max-rows)]
    (min req hard-max-rows)))

(defn- clamp-offset
  "Resolve the effective OFFSET — a non-negative integer to skip before the
   window. The per-call cap is unchanged by the offset (paging covers a large
   table in bounded windows; a single call still never dumps the table)."
  [opts]
  (let [off (get opts :offset 0)]
    (if (and (number? off) (not (neg? off))) (long off) 0)))

(defn- make-sample-rows-fn [db-path]
  (fn sample-rows
    ([table-selector] (sample-rows table-selector {}))
    ([table-selector opts]
     (let [table-name (resolve-table-name "sample-rows" table-selector)]
       ;; V19 — a wrong-shape opts arg is a TEACHING error, not a confusing
       ;; downstream failure. opts must be a map {:limit :offset} (or omitted).
       (when-not (map? opts)
         (throw (ex-info (str "sample-rows takes a table (name or descriptor map) and "
                              "an optional opts map, e.g. "
                              "(sample-rows \"HD2022\" {:limit 100 :offset 0}). The "
                              "2nd arg must be that opts map with :limit / :offset — "
                              "got " (pr-str opts) ". To page, put :offset INSIDE the "
                              "opts map (do NOT pass it as a separate argument).")
                         {:bad-arg opts})))
       (let [n (clamp-limit opts)
             off (clamp-offset opts)]
         ;; Double-bound: an explicit LIMIT/OFFSET in the SQL *and* setMaxRows. The
         ;; DB never streams more than `n` rows to us. An :offset skips that many
         ;; rows first, so consecutive windows ({:offset 0} {:offset n} ...) page
         ;; through the WHOLE table in bounded slices — comprehensive coverage
         ;; without ever dumping the table (mirrors the CSV/Excel :offset path).
         (run-bounded db-path
                      (str "SELECT * FROM \"" table-name "\" LIMIT " n
                           (when (pos? off) (str " OFFSET " off)))
                      n))))
    ;; V19 — an extra positional arg (the Excel-style 4th-arg mistake) is a
    ;; teaching error instead of a raw arity exception. The fix: opts is ONE map.
    ([table-selector opts & extra]
     (throw (ex-info (str "sample-rows takes at most 2 args: (sample-rows table "
                          "{:limit N :offset K}). You passed an extra "
                          (pr-str (vec extra)) ". Put :limit and :offset INSIDE the "
                          "single opts map, not as separate positional args.")
                     {:table table-selector :opts opts :extra (vec extra)})))))

(def sample-rows-doc
  "PURPOSE — Read a small, BOUNDED sample of rows from one table — never the
   whole table. Each row is an individual candidate (row -> A-box individual);
   the sample shows you real values so you can decide which columns carry labels,
   ids, descriptions, codes. Safe on a 6,000-row or 6,000,000-row table alike:
   the result is hard-capped inside the tool.

   EXAMPLE
     (sample-rows \"department\")            ;; default cap
     ;; => [{:id 1 :name \"Engineering\"} {:id 2 :name \"Sales\"}]

     (sample-rows \"HD2022\" {:limit 5})     ;; ask for 5
     ;; => [{:UNITID 100654 :INSTNM \"Alabama A & M University\" ...} ...]

   OFFSET / PAGING — the opts map may carry {:limit <int> :offset <int>} to skip
   the first :offset rows and read the NEXT window. Consecutive windows page
   through the WHOLE table in bounded slices, so you can cover a large table
   comprehensively without ever dumping it:
     (sample-rows \"C2022_A\" {:limit 100 :offset 0})    ;; rows 1..100
     (sample-rows \"C2022_A\" {:limit 100 :offset 100})  ;; rows 101..200, etc.
   Build the full set deterministically by looping offsets until a short/empty
   window comes back.

   RETURNS — a vector of row maps (column-keyword -> value), at most the cap
   (default 100). A :limit above the hard cap is clamped — a single call can
   never dump the whole table; use :offset paging to cover it. Empty vector for
   an empty table or an offset past the end.")

;; =============================================================================
;; Tool: query
;; =============================================================================

(defn- make-query-fn [db-path]
  (fn query
    ([sql] (query sql {}))
    ([sql opts]
     (when-not (string? sql)
       (throw (ex-info (str "query takes a read-only SELECT STRING, e.g. "
                            "(query \"SELECT name FROM department\"). Got: "
                            (pr-str (type sql)))
                       {:sql sql})))
     ;; Teaching layer: reject anything that isn't a single read statement BEFORE
     ;; it reaches the driver, so the model gets a clear contract message rather
     ;; than a confusing engine error. The read-only CONNECTION is the
     ;; authoritative guard underneath this (a write reaching it is rejected by
     ;; SQLite itself).
     (when-not (single-read-statement? sql)
       (throw (ex-info (str "query is READ-ONLY and accepts a SINGLE SELECT (or "
                            "WITH / PRAGMA / EXPLAIN) statement only. Writes (INSERT/"
                            "UPDATE/DELETE), DDL (CREATE/DROP/ALTER), and stacked "
                            "statements (`SELECT 1; DROP ...`) are rejected. To read "
                            "a table use sample-rows; to inspect schema use "
                            "table-schema / foreign-keys. Got: " (pr-str sql))
                       {:sql sql})))
     (let [n (clamp-limit (or opts {}))
           off (clamp-offset (or opts {}))
           ;; An :offset pages a SELECT result in bounded windows. We wrap the
           ;; (already validated single-read) statement in a bounded outer query
           ;; rather than string-splicing into the user's SQL, so the offset
           ;; applies to the FULL ordered result regardless of the inner shape.
           ;; A trailing `;` is stripped first so the wrap is a single statement.
           bounded-sql (if (pos? off)
                         (str "SELECT * FROM (" (str/replace sql #";\s*$" "")
                              ") LIMIT " n " OFFSET " off)
                         sql)]
       (run-bounded db-path bounded-sql n)))))

(def query-doc
  "PURPOSE — Run a READ-ONLY, BOUNDED SELECT for targeted exploration the fixed
   tools don't cover: a join across tables, a DISTINCT scan of a code column, a
   COUNT, a filtered lookup. Use when list-tables / table-schema / sample-rows
   aren't specific enough. The result is hard-capped — never a full-table dump.

   EXAMPLE
     (query \"SELECT DISTINCT STABBR FROM HD2022\")
     ;; => [{:STABBR \"AL\"} {:STABBR \"AK\"} ...]

     (query \"SELECT e.name, d.name AS dept FROM employee e
              JOIN department d ON e.dept_id = d.id\")
     ;; => [{:name \"Ada\" :dept \"Engineering\"} ...]

     (query \"SELECT name FROM department\" {:limit 10})   ;; bound it yourself

     (query \"SELECT * FROM employee ORDER BY id\" {:limit 100 :offset 200})
     ;; rows 201..300 of the ordered result — page a big join/scan window by window

   OFFSET / PAGING — the opts map may carry {:limit <int> :offset <int>}. The
   offset applies to the full ORDERED result of your SELECT, so consecutive
   windows page through a large join or scan in bounded slices. Cover a big
   result comprehensively by looping the offset until a short/empty window
   returns (always give your SELECT a deterministic ORDER BY so windows align).

   RETURNS — a vector of result-row maps (column-keyword -> value), at most the
   cap (default 100; an over-cap :limit is clamped — page with :offset to cover
   more). READ-ONLY: any INSERT / UPDATE / DELETE / CREATE / DROP / ALTER, or a
   stacked statement, is REJECTED with a clear error and the source is never
   modified.")

;; =============================================================================
;; Tool: count-rows (V19)
;; =============================================================================

(defn- make-count-rows-fn [db-path]
  (fn count-rows [table-selector]
    (let [table-name (resolve-table-name "count-rows" table-selector)
          ;; COUNT(*) over the table — the engine returns one row; no table data
          ;; is materialized, so this is safe on a multi-million-row table.
          rows (run-bounded db-path
                            (str "SELECT COUNT(*) AS n FROM \"" table-name "\"")
                            1)]
      {:table table-name
       :row-count (long (or (:n (first rows)) 0))})))

(def count-rows-doc
  "PURPOSE — Report a table's TOTAL row count WITHOUT loading it (a COUNT(*) the
   engine answers from its own bookkeeping), so you know how much data remains
   before you page or stream. The sample tools cap at 100 rows; this tells you
   the real size so you can decide how many windows stream-all will need.

   EXAMPLE
     (count-rows \"C2022_A\")
     ;; => {:table \"C2022_A\" :row-count 234517}
     ;; The table selector is forgiving — a name OR the descriptor map a table
     ;; listing returns both work:
     (count-rows {:name \"C2022_A\"})

   RETURNS — {:table :row-count}. :row-count is the table's full cardinality; no
   row data is read into memory.")

;; =============================================================================
;; Tool: stream-all (V19)
;; =============================================================================

(defn- make-stream-all-fn [db-path sample-rows-fn]
  (fn stream-all
    ([table-selector] (stream-all table-selector {}))
    ([table-selector opts]
     (let [table-name (resolve-table-name "stream-all" table-selector)
           opts (or opts {})]
       (when-not (map? opts)
         (throw (ex-info (str "stream-all opts must be a map {:window N :offset K}; "
                              "got " (pr-str opts))
                         {:bad-arg opts})))
       (let [req-win (or (:window opts) (:limit opts) hard-max-rows)
             win (min (max 1 (long (if (number? req-win) req-win hard-max-rows)))
                      hard-max-rows)
             start (long (or (:offset opts) 0))
             max-windows (long (or (:max-windows opts) 1000000))]
         ;; Build windows by stepping :offset; each window is one bounded
         ;; sample-rows call (the per-call cap is preserved — coverage comes from
         ;; iteration). Stop at the first short/empty window (table exhausted).
         (loop [offset start
                windows []
                guard 0]
           (if (>= guard max-windows)
             windows
             (let [rows (sample-rows-fn table-name {:limit win :offset offset})
                   n (count rows)
                   w {:table table-name :rows rows :row-count n :offset offset}]
               (cond
                 (zero? n) windows
                 (< n win) (conj windows w)
                 :else (recur (+ offset n) (conj windows w) (inc guard)))))))))))

(def stream-all-doc
  "PURPOSE — Iterate a table's ENTIRE row set in bounded windows, so you can
   cover every row without ever dumping the table. Builds on the :offset paging
   sample-rows already has: each window is one bounded sample-rows result, and
   consecutive windows step by :offset so together they cover every row exactly
   once. This is the substrate a deterministic full-extraction transform runs
   over.

   EXAMPLE
     (stream-all \"C2022_A\")                       ; default window
     (stream-all \"C2022_A\" {:window 100})
     ;; => [{:table \"C2022_A\" :rows [{...} ...] :row-count 100 :offset 0}
     ;;     {:table \"C2022_A\" :rows [{...} ...] :row-count 100 :offset 100}
     ;;     ... last window is short when the table is exhausted]
     ;; Concatenate the windows' :rows to get every row, in order:
     (mapcat :rows (stream-all \"C2022_A\" {:window 100}))

   WINDOW — :window is rows-per-window, CLAMPED to the 100-row per-call hard cap
   (the cap is never lifted — coverage comes from MANY windows, not one big
   call). Start partway with :offset; bound the loop with :max-windows. The
   selector is forgiving (name or descriptor map). For a stable order on a table
   with no rowid, prefer the `query` tool's :offset paging with an explicit
   ORDER BY.

   RETURNS — a VECTOR of window maps {:table :rows :row-count :offset}. Iteration
   stops at the first short/empty window (the table is exhausted).")

;; =============================================================================
;; Public binding builder
;; =============================================================================

(defn sql-source-tools
  "Return the SCI {symbol -> fn} map for the five SQL/SQLite source-access tools,
   bound to the granted :db-path. The grant (the source path) is fixed at build
   time, exactly as the S19 ontology tools fix their granted ontology scope.

   cfg keys:
     :db-path   REQUIRED. Absolute path to the SQLite database file.

   Returns nil when no :db-path is supplied — the sandbox MUST NOT expose source
   tools without a source (mirrors the S19 no-grant safety default).

   Each fn carries its docstring on metadata (via with-meta), so sandbox-side
   introspection `(meta sample-rows)` returns the doc the model reads."
  [{:keys [db-path]}]
  (when (and db-path (string? db-path) (seq db-path))
    (let [with-doc (fn [f doc] (with-meta f {:doc doc}))
          sample-rows-fn (make-sample-rows-fn db-path)]
      {'list-tables  (with-doc (make-list-tables-fn db-path) list-tables-doc)
       'table-schema (with-doc (make-table-schema-fn db-path) table-schema-doc)
       'foreign-keys (with-doc (make-foreign-keys-fn db-path) foreign-keys-doc)
       'sample-rows  (with-doc sample-rows-fn sample-rows-doc)
       'count-rows   (with-doc (make-count-rows-fn db-path) count-rows-doc)
       'stream-all   (with-doc (make-stream-all-fn db-path sample-rows-fn) stream-all-doc)
       'query        (with-doc (make-query-fn db-path) query-doc)})))

(def sql-source-tool-docs
  "The {symbol -> docstring} map for the five SQL/SQLite source-access tools.

   Exposed (like S19's ontology-tool-docs) so the V06 source-tool registry and
   any orientation card can pull the same docstrings the sandbox sees without
   building bindings (which requires a :db-path)."
  {'list-tables  list-tables-doc
   'table-schema table-schema-doc
   'foreign-keys foreign-keys-doc
   'sample-rows  sample-rows-doc
   'count-rows   count-rows-doc
   'stream-all   stream-all-doc
   'query        query-doc})
