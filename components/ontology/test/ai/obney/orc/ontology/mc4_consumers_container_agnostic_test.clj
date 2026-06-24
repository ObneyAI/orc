(ns ai.obney.orc.ontology.mc4-consumers-container-agnostic-test
  "MC-4 — the two extract-pipeline consumers routed through the uniform MC-1
   container contract, so they work for ANY format / container (one container per
   call; the multi-container LOOP is MC-5).

   The two consumers under test, through their PUBLIC surface:
     - `discovery-tree/mechanical-sample-rows` (the DT4 grounding) — returns
       KEYED rows for csv AND sql AND excel via the contract's `:sample-rows`,
       with NO format branch and NO `:else []` (the Excel grounding hole closed).
     - `rlm-discovery/apply-extraction-transform!` (the V20 apply) — streams +
       applies a transform via the contract's `:stream-all` for csv / sql / excel
       AND an excel DIRECTORY descriptor, with the committed MC-0 guards intact
       (sql selector validate-vs-tables, :max-windows, dual-key).

   Real fixtures, skip-if-absent (Discipline #4 — no fabricated row shapes). A
   tabular source whose first row keys are column headers (csv), a relational
   source of many tables (sql), and a directory of single-sheet workbooks (excel)
   each live on the verifying machine; absent → the test prints SKIP. Domain-
   agnostic: assertions key on STRUCTURE (keyed maps, non-empty rows, MC-0 guard
   counts), never on any field/table name."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]
            [ai.obney.orc.ontology.core.rlm-discovery :as rlm-discovery]
            [ai.obney.orc.orc-service.core.source-tools :as source-tools])
  (:import [java.io File]))

;; =============================================================================
;; Real fixtures (skip-if-absent)
;; =============================================================================

(def ^:private csv-path
  (str (System/getProperty "user.home") "/Downloads/cip_soc_crosswalk.csv"))
(def ^:private sql-path
  (str (System/getProperty "user.home") "/Downloads/output.db"))
(def ^:private excel-dir
  (str (System/getProperty "user.home") "/Downloads/db_30_1_excel"))

(defn- file-present? [p] (.exists (File. ^String p)))
(defn- dir-present? [p] (.isDirectory (File. ^String p)))

;; A first excel WORKBOOK inside the directory (a single .xlsx) — used to exercise
;; the single-file excel descriptor path (vs the directory descriptor path).
(defn- first-excel-workbook []
  (when (dir-present? excel-dir)
    (->> (.listFiles (File. ^String excel-dir))
         (filter (fn [^File f]
                   (and (.isFile f)
                        (.endsWith (.toLowerCase (.getName f)) ".xlsx"))))
         (sort-by #(.getName ^File %))
         first
         (#(some-> ^File % .getAbsolutePath)))))

;; A transform that reads a row map's keys WHATEVER they are and always yields one
;; concept-draft — domain-agnostic, structure-only. It reads BOTH a keyword and a
;; string form of the first key via the dual-key presentation, so it produces a
;; non-empty :uri regardless of the medium's key type. We embed the first key name
;; at authoring time (from a real sample) so the body bakes in no field literal.
(defn- always-yield-transform [a-key]
  ;; Reference the key as a STRING literal and as a runtime-built keyword
  ;; `(keyword "…")` — so a header with spaces (excel) never has to be printed as
  ;; a bare keyword literal (which would be unreadable source). The dual-key
  ;; presentation means whichever form the row carries resolves.
  (let [s (pr-str (str a-key))]
    (str "(fn [row]"
         "  (let [v (or (get row " s ") (get row (keyword " s ")))]"
         "    {:concept-drafts"
         "     [{:uri (str \"row:\" v)"
         "       :label (str v)"
         "       :scope :custom"
         "       :evidence [{:source \"k\" :quote (str v)}]}]"
         "     :relationship-drafts []}))")))

(defn- first-key-name
  "Pick a representative key name from a sampled row (the row with the most keys)."
  [rows]
  (when (seq rows)
    (let [rep (apply max-key (comp count keys) rows)]
      (some-> (first (keys rep)) name))))

;; =============================================================================
;; AC1 — mechanical-sample-rows returns KEYED rows for csv / sql / excel
;; =============================================================================

(deftest mechanical-sample-rows-csv-keyed
  (testing "csv: keyed row maps via the contract (no selector → single container)"
    (if-not (file-present? csv-path)
      (println "[MC-4] SKIP csv mechanical-sample-rows — absent at" csv-path)
      (let [rows (dt/mechanical-sample-rows {:type :csv :path csv-path} nil 3)]
        (is (seq rows) "csv grounding returns non-empty rows")
        (is (every? map? rows) "csv rows are keyed maps")
        (is (every? string? (keys (first rows))) "csv keys are header STRINGS")))))

(deftest mechanical-sample-rows-sql-keyed
  (testing "sql: keyed row maps via the contract (no selector → largest container)"
    (if-not (file-present? sql-path)
      (println "[MC-4] SKIP sql mechanical-sample-rows — absent at" sql-path)
      (let [rows (dt/mechanical-sample-rows {:type :sql :path sql-path} nil 3)]
        (is (seq rows) "sql grounding returns non-empty rows")
        (is (every? map? rows) "sql rows are keyed maps")
        (is (every? keyword? (keys (first rows))) "sql keys are column KEYWORDS")))))

(deftest mechanical-sample-rows-excel-keyed
  (testing "excel: keyed row maps via the contract — the Excel grounding hole the
            old :else [] left empty is now CLOSED (RED on pre-MC-4 code)."
    (if-not (dir-present? excel-dir)
      (println "[MC-4] SKIP excel mechanical-sample-rows — dir absent at" excel-dir)
      ;; A directory descriptor resolves (MC-1) and the default container is the
      ;; first sheet; rows must be non-empty KEYED maps (NOT [] and NOT positional).
      (let [rows (dt/mechanical-sample-rows {:type :excel :path excel-dir} nil 3)]
        (is (seq rows)
            "excel grounding returns non-empty rows (the :else [] hole is closed)")
        (is (every? map? rows) "excel rows are KEYED maps, not positional vectors")
        (is (every? string? (keys (first rows))) "excel keys are header STRINGS")))))

(deftest mechanical-sample-rows-excel-single-workbook
  (testing "excel single .xlsx file (not a directory): keyed rows too."
    (if-let [wb (first-excel-workbook)]
      (let [rows (dt/mechanical-sample-rows {:type :excel :path wb} nil 3)]
        (is (seq rows) "single-workbook excel grounding returns non-empty rows")
        (is (every? map? rows) "rows are keyed maps"))
      (println "[MC-4] SKIP excel single-workbook — no .xlsx under" excel-dir))))

;; =============================================================================
;; AC2 — apply-extraction-transform! streams + applies for csv / sql / excel
;;       and an excel DIRECTORY descriptor, with NO "no source tools" / SQLITE
;;       error
;; =============================================================================

(deftest apply-transform-csv
  (testing "csv: stream + apply with no error; coverage non-trivial"
    (if-not (file-present? csv-path)
      (println "[MC-4] SKIP csv apply — absent at" csv-path)
      (let [rows (dt/mechanical-sample-rows {:type :csv :path csv-path} nil 3)
            xform (always-yield-transform (first-key-name rows))
            result (rlm-discovery/apply-extraction-transform!
                    {:descriptor {:type :csv :path csv-path}
                     :transform-source xform
                     :max-windows 3})]
        (is (pos? (:rows-streamed result)) "csv rows streamed")
        (is (= 0 (:rows-errored result)) "no per-row errors")
        (is (seq (:concept-drafts result)) "drafts produced")
        (is (every? #(not= "row:" (:uri %)) (:concept-drafts result))
            "URIs are non-empty (field access grounded in the real key shape)")))))

(deftest apply-transform-sql
  (testing "sql: stream + apply via the contract with the MC-0 selector validation
            intact; no SQLITE no-such-table error (a real container is streamed)."
    (if-not (file-present? sql-path)
      (println "[MC-4] SKIP sql apply — absent at" sql-path)
      (let [rows (dt/mechanical-sample-rows {:type :sql :path sql-path} nil 3)
            xform (always-yield-transform (first-key-name rows))
            ;; pass a deliberately BAD selector — the MC-0 validation must resolve
            ;; it to a REAL table (the largest), never pass "null" to the engine.
            result (rlm-discovery/apply-extraction-transform!
                    {:descriptor {:type :sql :path sql-path}
                     :transform-source xform
                     :selector "null"
                     :max-windows 2})]
        (is (string? (:selector result)) "selector resolved to a real table name")
        (is (not= "null" (:selector result))
            "the bad \"null\" selector was NOT passed through (MC-0 validation)")
        (is (pos? (:rows-streamed result)) "sql rows streamed (no SQLITE error)")
        (is (= 0 (:rows-errored result)) "no per-row errors")
        (is (seq (:concept-drafts result)) "drafts produced")))))

(deftest apply-transform-excel-single-workbook
  (testing "excel single .xlsx: stream + apply via the contract; no error."
    (if-let [wb (first-excel-workbook)]
      (let [rows (dt/mechanical-sample-rows {:type :excel :path wb} nil 3)
            xform (always-yield-transform (first-key-name rows))
            result (rlm-discovery/apply-extraction-transform!
                    {:descriptor {:type :excel :path wb}
                     :transform-source xform
                     :max-windows 2})]
        (is (pos? (:rows-streamed result)) "excel rows streamed")
        (is (= 0 (:rows-errored result)) "no per-row errors")
        (is (seq (:concept-drafts result)) "drafts produced"))
      (println "[MC-4] SKIP excel single-workbook apply — no .xlsx under" excel-dir))))

(deftest apply-transform-excel-directory
  (testing "excel DIRECTORY descriptor: resolves through MC-1 + streams ONE
            container (the first sheet) via the contract; no \"no source tools\"
            error. (MC-5 will iterate ALL containers; MC-4 = one per call.)"
    (if-not (dir-present? excel-dir)
      (println "[MC-4] SKIP excel-directory apply — dir absent at" excel-dir)
      (let [rows (dt/mechanical-sample-rows {:type :excel :path excel-dir} nil 3)
            xform (always-yield-transform (first-key-name rows))
            result (rlm-discovery/apply-extraction-transform!
                    {:descriptor {:type :excel :path excel-dir}
                     :transform-source xform
                     :max-windows 2})]
        (is (pos? (:rows-streamed result))
            "excel-directory descriptor resolved + streamed (no 'no source tools')")
        (is (= 0 (:rows-errored result)) "no per-row errors")
        (is (seq (:concept-drafts result)) "drafts produced from the streamed sheet")))))

;; =============================================================================
;; AC3 — the contract's :sample-rows / :stream-all are uniformly (container, opts)
;;       callable across all three formats (the gap MC-4 closes)
;; =============================================================================

(deftest contract-per-row-uniformly-container-opts-callable
  (testing "csv / sql / excel all answer the SAME (container, opts) per-row call —
            no per-format signature divergence remains."
    (doseq [[label descriptor present?]
            [[:csv   {:type :csv :path csv-path}     (file-present? csv-path)]
             [:sql   {:type :sql :path sql-path}     (file-present? sql-path)]
             [:excel {:type :excel :path excel-dir}  (dir-present? excel-dir)]]]
      (if-not present?
        (println "[MC-4] SKIP uniform per-row" label "— fixture absent")
        (let [c (source-tools/container-contract descriptor)
              containers ((:list-containers c))
              container (first containers)
              ;; the SAME 2-arity call shape for every format
              sample ((:sample-rows c) container {:limit 2})
              ;; csv/excel sample-rows return {:rows [...]}; sql returns a bare
              ;; vector of row maps — normalize without a per-format branch.
              rows (cond (and (map? sample) (contains? sample :rows)) (:rows sample)
                         (sequential? sample) sample
                         :else [])
              windows ((:stream-all c) container {:window 50 :max-windows 1})]
          (is (map? c) (str label " resolves to a contract"))
          (is (seq rows) (str label " uniform sample-rows returns keyed rows"))
          (is (every? map? rows) (str label " rows are keyed maps"))
          (is (vector? windows) (str label " uniform stream-all returns windows"))
          (is (seq (mapcat :rows windows))
              (str label " uniform stream-all windows carry rows")))))))
