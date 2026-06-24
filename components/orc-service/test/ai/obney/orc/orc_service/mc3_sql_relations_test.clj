(ns ai.obney.orc.orc-service.mc3-sql-relations-test
  "MC-3 — the SQL specialist's `relations` tool: the uniform container-contract
   relations op, derived from declared foreign-keys AND a DOMAIN-AGNOSTIC
   shared-key heuristic.

   The contract relations shape is `[{:from :to :via}]` (source_tools.clj MC-1).
   `relations(container)` returns the cross-container links OUT OF one container
   (table) — same per-table signature as `foreign-keys`, so the specialist's
   surface stays consistent (P8, re-orchestrate not fork). Two sources of edges:

   1. DECLARED foreign keys — convert `foreign-keys`' raw
      `{:from-table :from-column :to-table :to-column}` into `{:from :to :via}`
      where :from/:to are \"table.column\" and :via is the shared key column.

   2. SHARED-KEY heuristic — IPEDS declares NO foreign keys, yet its tables link
      by a shared CODE COLUMN (the institution id) that is part of the PRIMARY
      KEY in many tables. The heuristic is DOMAIN-AGNOSTIC: it reads each table's
      `table-schema` and treats a column that is part of the declared primary key
      in >= 2 tables as a join key, emitting a relation from the container to each
      other table sharing that key column. It bakes in NO field names (no UNITID /
      CIPCODE / SOC) — Discipline #12.

   Tests, in tracer-bullet order, through the specialist's PUBLIC surface
   (`sql/sql-source-tools`):

   - FK shaping: a synthetic DB with a DECLARED FK yields the {:from :to :via}
     edge derived from foreign-keys. (RED before the relations tool exists.)
   - Domain-agnostic heuristic: a synthetic 2-table DB sharing an ARBITRARY
     pk column name yields a relation :via that column — no baked field names.
   - The heuristic does NOT emit spurious edges for a column that co-occurs but is
     NOT a key in any table (a data column shared by coincidence).
   - REAL IPEDS (skip-if-absent): list-containers returns all ~59 tables; and the
     LOAD-BEARING result — the heuristic finds NON-empty relations for a table
     where `foreign-keys` returns []."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.core.source-tools-sql :as sql])
  (:import [java.sql DriverManager]
           [java.io File]))

;; =============================================================================
;; Synthetic fixture helpers
;; =============================================================================

(defn- exec! [conn sql]
  (with-open [st (.createStatement conn)]
    (.executeUpdate st sql)))

(defn- with-temp-db
  "Build a temp SQLite via `build-fn` (given a jdbc connection), then call `f`
   with the db-path. Cleans up."
  [build-fn f]
  (let [file (doto (File/createTempFile "mc3-rel" ".db") (.delete))
        path (.getAbsolutePath file)]
    (try
      (with-open [conn (DriverManager/getConnection (str "jdbc:sqlite:" path))]
        (build-fn conn))
      (f path)
      (finally (.delete file)))))

(defn- tools [db-path] (sql/sql-source-tools {:db-path db-path}))
(defn- call [db-path sym & args] (apply (get (tools db-path) sym) args))

;; =============================================================================
;; AC1 — DECLARED foreign keys → the {:from :to :via} contract shape
;; =============================================================================

(deftest relations-shapes-a-declared-fk
  (testing "relations converts a DECLARED foreign key into a {:from :to :via} edge"
    (with-temp-db
      (fn [conn]
        (exec! conn "PRAGMA foreign_keys=ON")
        (exec! conn "CREATE TABLE department (id INTEGER PRIMARY KEY, name TEXT)")
        (exec! conn (str "CREATE TABLE employee ("
                         "id INTEGER PRIMARY KEY, name TEXT, dept_id INTEGER, "
                         "FOREIGN KEY (dept_id) REFERENCES department(id))")))
      (fn [db]
        (let [rels (call db 'relations "employee")]
          (is (vector? rels))
          (is (some (fn [r]
                      (= r {:from "employee.dept_id"
                            :to   "department.id"
                            :via  "dept_id"}))
                    rels)
              "the declared FK is shaped as {:from \"employee.dept_id\" :to \"department.id\" :via \"dept_id\"}")
          (testing "every relation has the uniform contract keys"
            (is (every? (fn [r] (= #{:from :to :via} (set (keys r)))) rels))))))))

;; =============================================================================
;; AC2 — DOMAIN-AGNOSTIC shared-key heuristic (no baked field names)
;; =============================================================================

(deftest relations-finds-shared-key-with-no-declared-fk
  (testing "two FK-less tables sharing an ARBITRARY pk column link via that column —
            the heuristic reads pk-membership from table-schema generically, with
            NO field names baked in (Discipline #12). The shared column here is a
            made-up name no production code could special-case."
    (with-temp-db
      (fn [conn]
        ;; NO foreign keys declared. Two tables share `widget_ref`, which is part
        ;; of the PRIMARY KEY in BOTH — the generic join-key signal.
        (exec! conn (str "CREATE TABLE alpha (widget_ref INTEGER, alpha_val TEXT, "
                         "PRIMARY KEY (widget_ref))"))
        (exec! conn (str "CREATE TABLE beta (widget_ref INTEGER, beta_seq INTEGER, beta_val TEXT, "
                         "PRIMARY KEY (widget_ref, beta_seq))")))
      (fn [db]
        ;; No declared FKs at all.
        (is (= [] (call db 'foreign-keys "alpha"))
            "precondition: no declared foreign keys")
        (let [rels (call db 'relations "alpha")]
          (is (seq rels) "the shared-key heuristic finds a relation despite NO declared FK")
          (is (some (fn [r]
                      (and (= "widget_ref" (:via r))
                           (str/starts-with? (:from r) "alpha.")
                           (str/starts-with? (:to r) "beta.")))
                    rels)
              "alpha links to beta :via the shared pk column widget_ref")
          (testing "uniform contract keys on heuristic edges too"
            (is (every? (fn [r] (= #{:from :to :via} (set (keys r)))) rels))))))))

(deftest heuristic-ignores-coincidentally-shared-non-key-columns
  (testing "a column that co-occurs in two tables but is NOT part of any primary
            key (a data column shared by coincidence) does NOT produce a relation —
            the heuristic keys on declared-pk membership, not mere co-occurrence,
            so it does not flood MC-6 with spurious links"
    (with-temp-db
      (fn [conn]
        ;; `note` is a plain data column in BOTH tables (never a pk). `code` is a
        ;; pk in both — only `code` should yield a relation.
        (exec! conn (str "CREATE TABLE t1 (code INTEGER, note TEXT, PRIMARY KEY (code))"))
        (exec! conn (str "CREATE TABLE t2 (code INTEGER, seq INTEGER, note TEXT, "
                         "PRIMARY KEY (code, seq))")))
      (fn [db]
        (let [rels (call db 'relations "t1")
              vias (set (map :via rels))]
          (is (contains? vias "code") "the shared PK column links the tables")
          (is (not (contains? vias "note"))
              "the shared NON-key data column does NOT produce a spurious relation"))))))

(deftest relations-empty-when-no-fk-and-no-shared-key
  (testing "a table whose key columns are NOT shared by any other table, and which
            declares no FK, yields no relations — the heuristic does not invent links"
    (with-temp-db
      (fn [conn]
        (exec! conn "CREATE TABLE solo (solo_id INTEGER PRIMARY KEY, payload TEXT)")
        (exec! conn "CREATE TABLE other (other_id INTEGER PRIMARY KEY, payload TEXT)"))
      (fn [db]
        (is (= [] (call db 'relations "solo"))
            "no declared FK and no shared key column => no relations")))))

;; =============================================================================
;; AC3 — relations tool is documented + carries metadata (specialist convention)
;; =============================================================================

(deftest relations-is-documented-and-carries-metadata
  (testing "the relations tool is in the doc map with PURPOSE/EXAMPLE/RETURNS and
            its fn carries :doc metadata (the model introspects (meta f))"
    (with-temp-db
      (fn [conn]
        (exec! conn "CREATE TABLE t (id INTEGER PRIMARY KEY)"))
      (fn [db]
        (let [doc (get sql/sql-source-tool-docs 'relations)]
          (is (string? doc) "relations is in sql-source-tool-docs")
          (doseq [el ["PURPOSE" "EXAMPLE" "RETURNS"]]
            (is (str/includes? doc el) (str "relations doc missing " el))))
        (is (string? (:doc (meta (get (tools db) 'relations))))
            "relations fn carries :doc metadata")))))

;; =============================================================================
;; AC4 — REAL IPEDS (skip-if-absent): the LOAD-BEARING live verification
;; =============================================================================

(def ^:private ipeds-path
  (str (System/getProperty "user.home") "/Downloads/output.db"))

(defn- ipeds-present? [] (.exists (File. ipeds-path)))

(deftest ipeds-list-containers-and-nonempty-heuristic-relations
  (if-not (ipeds-present?)
    (println "[mc3] SKIP — IPEDS" ipeds-path "absent")
    (testing "REAL IPEDS: all user tables list; and the load-bearing result —
              non-empty heuristic relations where foreign-keys returns []"
      (let [table-names (call ipeds-path 'list-tables)]
        (is (>= (count table-names) 55)
            (str "IPEDS lists ~59 user tables; got " (count table-names)))
        (is (not-any? #(str/starts-with? % "sqlite_") table-names)
            "no internal sqlite_* tables leak into the listing")
        ;; IPEDS declares NO foreign keys anywhere — the shared-key heuristic is
        ;; the ONLY source of within-source relations. Pick a real table that
        ;; shares the institution id and assert foreign-keys is [] but relations
        ;; is NON-empty.
        (let [tbl (some #{"HD2022" "IC2022" "ADM2022"} table-names)
              tbl (or tbl (first table-names))
              fks  (call ipeds-path 'foreign-keys tbl)
              rels (call ipeds-path 'relations tbl)]
          (is (= [] fks)
              (str tbl " declares NO foreign keys (IPEDS-wide)"))
          (is (seq rels)
              (str "LOAD-BEARING: the shared-key heuristic finds relations for "
                   tbl " where foreign-keys returned [] (" (count rels) " edges)"))
          (is (every? (fn [r] (= #{:from :to :via} (set (keys r)))) rels)
              "every IPEDS heuristic edge has the uniform {:from :to :via} shape")
          (println "[mc3] IPEDS:" (count table-names) "tables;" tbl
                   "foreign-keys=" (count fks) "relations=" (count rels)
                   "via=" (pr-str (distinct (map :via rels)))))))))
