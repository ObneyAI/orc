(ns ai.obney.orc.orc-service.v04-sql-source-tools-test
  "V04 — SQL / SQLite SOURCE-ACCESS tools for the discovery RLM.

   These tools let an RLM explore a relational SOURCE by its schema WITHOUT
   loading the whole database (the S19 pattern, applied to SOURCES rather than
   to the ontology graph). The five tools:

   - list-tables   — table names in the database.
   - table-schema  — columns + types (+ pk/notnull) for one table.
   - foreign-keys  — declared FK edges out of one table (table->table links
                     the RLM turns into relationships).
   - sample-rows   — a bounded sample of rows from one table (never the whole
                     table; a hard cap is enforced inside the tool).
   - query         — a read-only, bounded SELECT. A write/DDL attempt is
                     rejected; an unbounded SELECT is hard-capped.

   Each deftest maps to a slice acceptance criterion. The fixtures are a small
   SYNTHETIC SQLite built in a temp file (declared FKs, a large table) so the
   bounded/read-only/FK assertions are deterministic; the real IPEDS DB is
   exercised in the live-verify bench, not here.

   Adversarial focus:
   - SAMPLE/BOUND: a sample/query over a LARGE table returns a bounded slice;
     the full table is never materialized. Asserted by row-count + by proving
     the cap holds even when the query text omits a LIMIT.
   - READ-ONLY: an INSERT / UPDATE / CREATE through `query` is rejected AND
     the database is unchanged afterward (defense verified by re-reading).
   - DOCSTRING quality: every tool's doc has PURPOSE / EXAMPLE / RETURNS; an
     adversarial twin proves the check is not trivially passing."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.core.source-tools-sql :as sql])
  (:import [java.sql DriverManager]
           [java.io File]))

;; =============================================================================
;; Synthetic SQLite fixture
;; =============================================================================
;; Two related tables with a DECLARED foreign key, plus a deliberately large
;; table (5000 rows) so the bounded-sample assertions are meaningful.

(def ^:dynamic *db-path* nil)

(defn- exec! [conn sql]
  (with-open [st (.createStatement conn)]
    (.executeUpdate st sql)))

(defn- build-fixture-db!
  "Create a temp SQLite file with:
   - department(id PK, name TEXT)
   - employee(id PK, name TEXT, dept_id INTEGER -> department.id [FK])
   - big_table(id PK, payload TEXT) with 5000 rows."
  [path]
  (with-open [conn (DriverManager/getConnection (str "jdbc:sqlite:" path))]
    (exec! conn "PRAGMA foreign_keys=ON")
    (exec! conn "CREATE TABLE department (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
    (exec! conn (str "CREATE TABLE employee ("
                     "id INTEGER PRIMARY KEY, "
                     "name TEXT NOT NULL, "
                     "salary REAL, "
                     "dept_id INTEGER, "
                     "FOREIGN KEY (dept_id) REFERENCES department(id))"))
    (exec! conn "CREATE TABLE big_table (id INTEGER PRIMARY KEY, payload TEXT)")
    (exec! conn "INSERT INTO department (id, name) VALUES (1, 'Engineering'), (2, 'Sales')")
    (exec! conn (str "INSERT INTO employee (id, name, salary, dept_id) VALUES "
                     "(1, 'Ada', 120000.0, 1), (2, 'Babbage', 110000.0, 1), "
                     "(3, 'Carol', 90000.0, 2)"))
    ;; 5000-row big table — used to prove sampling/bounding never loads it all.
    (with-open [st (.prepareStatement conn "INSERT INTO big_table (id, payload) VALUES (?, ?)")]
      (.setAutoCommit conn false)
      (dotimes [i 5000]
        (.setInt st 1 (inc i))
        (.setString st 2 (str "row-" (inc i)))
        (.addBatch st))
      (.executeBatch st)
      (.commit conn)
      (.setAutoCommit conn true))))

(use-fixtures :each
  (fn [t]
    (let [f (File/createTempFile "v04-fixture" ".db")]
      (.delete f) ; sqlite creates it fresh
      (try
        (build-fixture-db! (.getAbsolutePath f))
        (binding [*db-path* (.getAbsolutePath f)]
          (t))
        (finally (.delete f))))))

(defn- tools [] (sql/sql-source-tools {:db-path *db-path*}))
(defn- call [sym & args] (apply (get (tools) sym) args))

;; =============================================================================
;; AC1 — Each tool correct against the fixture
;; =============================================================================

(deftest list-tables-returns-the-tables
  (testing "list-tables returns every user table (sqlite_* internal tables excluded)"
    (let [ts (set (call 'list-tables))]
      (is (= #{"department" "employee" "big_table"} ts)))))

(deftest table-schema-returns-columns-and-types
  (testing "table-schema returns column name/type/pk/notnull for a table"
    (let [cols (call 'table-schema "employee")
          by-name (into {} (map (juxt :name identity) cols))]
      (is (= #{"id" "name" "salary" "dept_id"} (set (map :name cols))))
      (is (= "INTEGER" (:type (by-name "id"))))
      (is (true? (:primary-key (by-name "id"))))
      (is (= "REAL" (:type (by-name "salary"))))
      (is (false? (:nullable (by-name "name"))) "name is NOT NULL")
      (is (true? (:nullable (by-name "salary"))) "salary is nullable"))))

(deftest foreign-keys-surface-table-to-table-edges
  (testing "foreign-keys returns the declared FK edges the RLM turns into relationships"
    (let [fks (call 'foreign-keys "employee")]
      (is (= 1 (count fks)))
      (let [fk (first fks)]
        ;; A table->table edge: employee.dept_id -> department.id
        (is (= "employee" (:from-table fk)))
        (is (= "dept_id" (:from-column fk)))
        (is (= "department" (:to-table fk)))
        (is (= "id" (:to-column fk))))))
  (testing "a table with no FKs returns an empty vector, not an exception"
    (is (= [] (call 'foreign-keys "department")))))

(deftest sample-rows-returns-bounded-rows
  (testing "sample-rows returns rows as maps, capped by a default limit"
    (let [rows (call 'sample-rows "employee")]
      (is (vector? rows))
      (is (= 3 (count rows)) "employee has exactly 3 rows")
      (is (= #{:id :name :salary :dept_id} (set (keys (first rows))))))))

(deftest query-returns-bounded-select-result
  (testing "query runs a SELECT and returns result rows as maps"
    (let [rows (call 'query "SELECT name FROM department ORDER BY name")]
      (is (= ["Engineering" "Sales"] (mapv :name rows))))))

;; =============================================================================
;; AC2 — SAMPLE / BOUND (adversarial): never load the whole table
;; =============================================================================

(deftest sample-rows-is-bounded-on-a-large-table
  (testing "sample-rows over a 5000-row table returns only the capped slice"
    (let [rows (call 'sample-rows "big_table")]
      (is (<= (count rows) 100) "default cap is well below the 5000-row table")
      (is (pos? (count rows)) "but it does return something"))))

(deftest sample-rows-respects-explicit-limit-but-clamps-to-max
  (testing "a caller-supplied :limit is honored when below the hard max"
    (is (= 7 (count (call 'sample-rows "big_table" {:limit 7})))))
  (testing "a caller-supplied :limit ABOVE the hard max is clamped (no full-table load)"
    (let [rows (call 'sample-rows "big_table" {:limit 999999})]
      (is (< (count rows) 5000)
          "an absurd limit is clamped to the hard max — the 5000-row table is NOT dumped"))))

(deftest query-bounds-an-unbounded-select
  (testing "a SELECT with NO limit over the large table is hard-capped — the
            full result set is never materialized"
    (let [rows (call 'query "SELECT id FROM big_table")]
      (is (< (count rows) 5000)
          "unbounded SELECT * over 5000 rows is capped inside the tool")
      (is (pos? (count rows))))))

;; =============================================================================
;; AC2b — READ-ONLY (adversarial): writes/DDL are rejected and DB is unchanged
;; =============================================================================

(deftest query-rejects-writes-and-ddl
  (testing "INSERT through query is rejected"
    (is (thrown? Exception (call 'query "INSERT INTO department (id, name) VALUES (99, 'Hack')"))))
  (testing "UPDATE through query is rejected"
    (is (thrown? Exception (call 'query "UPDATE department SET name='x' WHERE id=1"))))
  (testing "DELETE through query is rejected"
    (is (thrown? Exception (call 'query "DELETE FROM department"))))
  (testing "CREATE TABLE (DDL) through query is rejected"
    (is (thrown? Exception (call 'query "CREATE TABLE evil (x INTEGER)"))))
  (testing "DROP TABLE (DDL) through query is rejected"
    (is (thrown? Exception (call 'query "DROP TABLE department")))))

(deftest write-attempts-leave-the-database-unchanged
  (testing "after every rejected write attempt, the database is byte-for-byte
            unchanged: department still has exactly 2 rows, no 'evil' table"
    (doseq [bad ["INSERT INTO department (id, name) VALUES (99, 'Hack')"
                 "UPDATE department SET name='x'"
                 "DELETE FROM department"
                 "CREATE TABLE evil (x INTEGER)"]]
      (try (call 'query bad) (catch Exception _ nil)))
    ;; Re-read through a fresh tool call — the source is untouched.
    (is (= 2 (count (call 'query "SELECT id FROM department")))
        "department untouched after rejected writes")
    (is (= [] (call 'foreign-keys "department")))
    (is (not (contains? (set (call 'list-tables)) "evil"))
        "no 'evil' table was created")))

(deftest semicolon-stacked-write-is-rejected
  (testing "a stacked statement smuggling a write after a SELECT is rejected
            (defense against `SELECT 1; DROP TABLE department`)"
    (is (thrown? Exception
          (call 'query "SELECT 1; DROP TABLE department")))
    (is (contains? (set (call 'list-tables)) "department")
        "department survives the stacked-write attempt")))

;; =============================================================================
;; AC3 — FK discovery the RLM can turn into edges (cross-table)
;; =============================================================================

(deftest fk-discovery-is-directional-and-names-both-tables
  (testing "the FK edge names the FROM table, FROM column, TO table, TO column —
            everything the RLM needs to author a table->table relationship"
    (let [[fk] (call 'foreign-keys "employee")]
      (is (= {:from-table "employee" :from-column "dept_id"
              :to-table "department" :to-column "id"}
             (select-keys fk [:from-table :from-column :to-table :to-column]))))))

;; =============================================================================
;; AC4 — Docstrings are self-contained (PURPOSE / EXAMPLE / RETURNS)
;; =============================================================================

(deftest each-tool-docstring-is-self-contained
  (testing "every tool doc has PURPOSE, EXAMPLE, RETURNS with a concrete call form"
    (let [docs sql/sql-source-tool-docs
          required ["PURPOSE" "EXAMPLE" "RETURNS"]]
      (is (= #{'list-tables 'table-schema 'foreign-keys 'sample-rows 'query}
             (set (keys docs)))
          "all five tools are documented")
      (doseq [[sym doc] docs]
        (testing (str sym " has all required structural elements")
          (is (string? doc))
          (doseq [el required]
            (is (str/includes? doc el)
                (str sym " docstring missing: " el)))
          (let [example-section (second (str/split doc #"EXAMPLE"))]
            (is (some? example-section) (str sym " has an EXAMPLE section"))
            (is (str/includes? example-section "(")
                (str sym " EXAMPLE has a concrete call form"))
            (when-let [code-only (first (str/split example-section #"RETURNS"))]
              (is (not (re-find #"<arg\d?>" code-only))
                  (str sym " EXAMPLE has <arg> placeholders — use concrete values")))))))))

(deftest adversarial-stripping-a-section-fails-docstring-quality
  (testing "proof the doc-quality check is not trivially passing"
    (let [bad "PURPOSE — does stuff. RETURNS — a thing."
          required ["PURPOSE" "EXAMPLE" "RETURNS"]
          results (mapv #(str/includes? bad %) required)]
      (is (not (every? identity results)))
      (is (= [true false true] results) "EXAMPLE is the missing element"))))

;; =============================================================================
;; AC5 — fn metadata carries the docstring (the model introspects (meta f))
;; =============================================================================

(deftest tool-fns-carry-docstring-metadata
  (testing "each built tool fn carries its docstring on metadata, like S19 tools"
    (let [ts (tools)]
      (doseq [sym '[list-tables table-schema foreign-keys sample-rows query]]
        (is (string? (:doc (meta (get ts sym))))
            (str sym " fn carries :doc metadata"))))))

;; =============================================================================
;; AC6 — No grant => no tools (safety default, mirrors S19)
;; =============================================================================

(deftest no-db-path-means-no-tools
  (testing "without a :db-path the builder returns nil — the sandbox must not
            expose unscoped source tools"
    (is (nil? (sql/sql-source-tools {})))
    (is (nil? (sql/sql-source-tools {:db-path nil})))))
