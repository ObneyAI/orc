(ns ai.obney.orc.orc-service.v03-csv-source-tools-test
  "V03 — CSV source-access tools for RLM-controlled ingestion.

   Per-format SOURCE-ACCESS tools (mirroring the S19 graph-tools pattern,
   now for SOURCES) so the discovery RLM can EXPLORE a CSV by its shape
   WITHOUT loading the whole file into context.

   Each deftest maps to one acceptance criterion in
   docs/build-timeline/issues/ontology-verification/V03-csv-source-access-tools.md:

   - peek-columns: returns the header columns + an inferred type per
     column (name-pattern knowledge ported from csv_ontology, with a
     value-based fallback), and surfaces FK-like / crosswalk relationship
     hints (adjacent code columns -> a relationship between them).
   - sample-rows: returns at most N rows as maps keyed by header, capped,
     with an honest :capped? flag — NEVER the whole file.
   - profile-column: distinct-count / cardinality-ratio / example values /
     top values over a BOUNDED scan window, with an honest :scan-capped?
     flag.
   - SAMPLE never DUMP (adversarial): over the real ~6097-row crosswalk a
     tool reads a BOUNDED slice — asserted by instrumenting the line
     counter so a full-file read would trip the assertion.
   - Docstrings self-contained (PURPOSE / EXAMPLE / RETURNS) — the model
     can use each tool from the docstring alone (S19 docstring-quality
     pattern), plus an adversarial twin proving a stripped docstring fails.
   - Read-side only: the tools never emit events (they hold no event-store
     and only read the file system).
   - Empty / edge CSV (no data rows, weird header) -> honest empty, not an
     error or fabricated data."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [ai.obney.orc.orc-service.core.source-tools-csv :as csv-tools]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(def ^:private real-crosswalk-path
  "The real CIP-SOC crosswalk: 4 columns, ~6097 data rows. Used for the
   bounded-read (sample-never-dump) adversarial assertions."
  "/Users/darylroberts/Downloads/cip_soc_crosswalk.csv")

(defn- write-tmp-csv!
  "Write `content` to a fresh temp .csv file; return its absolute path."
  [content]
  (let [f (java.io.File/createTempFile "v03-fixture" ".csv")]
    (.deleteOnExit f)
    (spit f content)
    (.getAbsolutePath f)))

;; A tiny synthetic crosswalk fixture: two code columns (so the relationship
;; hint fires), a quoted field containing a comma (RFC4180 quoting), a label
;; column, and an integer column.
(def ^:private synthetic-csv
  (str "CIP_Code,CIP_Title,SOC_Code,credits\n"
       "01.0000,\"Agriculture, General.\",19-1011,120\n"
       "01.0000,\"Agriculture, General.\",19-1012,120\n"
       "11.0701,Computer Science.,15-1252,60\n"))

(defn- tools-for [path]
  (csv-tools/csv-source-tools {:csv-path path}))

(defn- tool [path sym]
  (get (tools-for path) sym))

;; =============================================================================
;; AC1 — peek-columns: header + inferred types + relationship hints
;; =============================================================================

(deftest peek-columns-returns-columns-and-types
  (testing "peek-columns returns each header column with an inferred type
            and FK/crosswalk relationship hints, reading only the header +
            a small sample (never the whole file)."
    (let [path (write-tmp-csv! synthetic-csv)
          peek (tool path 'peek-columns)
          result (peek)
          cols (:columns result)
          by-name (into {} (map (juxt :name identity) cols))]
      (is (= ["CIP_Code" "CIP_Title" "SOC_Code" "credits"]
             (mapv :name cols))
          "all four header columns, in order")
      ;; Name-pattern knowledge ported from csv_ontology: *_code$ -> code,
      ;; title/name/label -> label, value-based integer fallback for credits.
      (is (= "code" (:inferred-type (by-name "CIP_Code"))))
      (is (= "code" (:inferred-type (by-name "SOC_Code"))))
      (is (= "label" (:inferred-type (by-name "CIP_Title"))))
      (is (= "integer" (:inferred-type (by-name "credits"))))
      ;; csv_ontology: *_code / *_id columns are FK candidates.
      (is (true? (:fk-candidate? (by-name "CIP_Code"))))
      (is (true? (:fk-candidate? (by-name "SOC_Code"))))
      (is (false? (:fk-candidate? (by-name "credits"))))
      ;; Two adjacent code columns -> a crosswalk relationship hint (the
      ;; csv_ontology knowledge: adjacent FK-like columns become a
      ;; relationship between the two entities they encode).
      (is (seq (:relationship-hints result))
          "two code columns yield a crosswalk relationship hint")
      (is (some (fn [h] (= #{"CIP_Code" "SOC_Code"}
                           (set [(:from h) (:to h)])))
                (:relationship-hints result))
          "the hint links the two code columns"))))

(deftest peek-columns-suggests-property-and-class-mappings
  (testing "peek-columns ports the csv_ontology mapping knowledge: each
            column carries a camelCase suggested-property, and label-like
            columns suggest a PascalCase class name (columns->properties,
            label-columns->classes)."
    (let [path (write-tmp-csv! synthetic-csv)
          peek (tool path 'peek-columns)
          by-name (into {} (map (juxt :name identity) (:columns (peek))))]
      (is (= "cipCode" (:suggested-property (by-name "CIP_Code"))))
      (is (= "cipTitle" (:suggested-property (by-name "CIP_Title"))))
      (is (some? (:suggested-class (by-name "CIP_Title")))
          "a label column suggests a class")
      (is (= "CipTitle" (:suggested-class (by-name "CIP_Title")))))))

;; =============================================================================
;; AC2 — sample-rows: N capped rows as maps; never the whole file
;; =============================================================================

(deftest sample-rows-returns-n-capped-rows
  (testing "sample-rows returns at most N rows as maps keyed by header,
            with an honest :capped? flag."
    (let [path (write-tmp-csv! synthetic-csv)
          sample (tool path 'sample-rows)]
      (testing "default + explicit N"
        (let [r (sample 2)]
          (is (= 2 (count (:rows r))))
          (is (= {"CIP_Code" "01.0000"
                  "CIP_Title" "Agriculture, General."
                  "SOC_Code" "19-1011"
                  "credits" "120"}
                 (first (:rows r)))
              "row is a map keyed by header; quoted comma preserved")
          (is (true? (:capped? r))
              "3 data rows, asked for 2 -> capped")))
      (testing "asking for more rows than exist is honest (not capped, not padded)"
        (let [r (sample 100)]
          (is (= 3 (count (:rows r))) "only 3 data rows exist")
          (is (false? (:capped? r))))))))

(deftest sample-rows-is-hard-capped
  (testing "sample-rows enforces an internal hard cap so a model asking for
            a huge N cannot dump the file."
    (let [path real-crosswalk-path]
      (when (.exists (io/file path))
        (let [sample (tool path 'sample-rows)
              r (sample 1000000)]
          (is (<= (count (:rows r)) csv-tools/max-sample-rows)
              "an absurd N is clamped to the hard cap")
          (is (true? (:capped? r))))))))

;; =============================================================================
;; AC3 — profile-column: cardinality / distinct / examples (bounded scan)
;; =============================================================================

(deftest profile-column-returns-cardinality-and-examples
  (testing "profile-column returns distinct-count, cardinality-ratio,
            example values and top values over a bounded scan."
    (let [path (write-tmp-csv! synthetic-csv)
          profile (tool path 'profile-column)
          r (profile "CIP_Code")]
      (is (= "CIP_Code" (:column r)))
      (is (= 3 (:rows-scanned r)) "all 3 data rows scanned (small file)")
      (is (= 2 (:distinct-count r)) "01.0000 (x2) and 11.0701 -> 2 distinct")
      (is (= 2 (count (:examples r))))
      ;; cardinality-ratio = distinct / scanned
      (is (= (/ 2.0 3.0) (double (:cardinality-ratio r))))
      ;; top values include the duplicated 01.0000 with count 2
      (is (some (fn [[v c]] (and (= "01.0000" v) (= 2 c))) (:top-values r))))))

(deftest profile-column-unknown-column-is-honest
  (testing "profiling a column that is not in the header returns an honest
            empty/marker shape, NOT a fabricated profile."
    (let [path (write-tmp-csv! synthetic-csv)
          profile (tool path 'profile-column)
          r (profile "NoSuchColumn")]
      (is (false? (:found? r))
          "the column isn't in the header -> :found? false")
      (is (= 0 (:distinct-count r)))
      (is (= [] (:examples r))))))

;; =============================================================================
;; AC4 — SAMPLE NEVER DUMP (adversarial, real large file)
;; =============================================================================

(deftest sample-never-dumps-the-whole-file
  (testing "Over the real ~6097-row crosswalk, NO tool reads the whole file.
            We instrument the bounded line reader: the test rebinds the
            line-count budget and asserts each tool stays under it, while a
            full-file read (6098 lines) would blow it."
    (let [path real-crosswalk-path]
      (when (.exists (io/file path))
        ;; The shared bounded reader records how many physical lines each
        ;; call consumed in this atom. A full-file dump would record >6000;
        ;; a bounded sample records only a small slice.
        (let [budget 200]
          (testing "peek-columns reads only header + a small inference sample"
            (reset! csv-tools/*last-lines-read* 0)
            ((tool path 'peek-columns))
            (is (< @csv-tools/*last-lines-read* budget)
                (str "peek-columns read " @csv-tools/*last-lines-read*
                     " lines — must be a bounded slice, not the whole file")))
          (testing "sample-rows reads only header + N rows"
            (reset! csv-tools/*last-lines-read* 0)
            ((tool path 'sample-rows) 5)
            (is (< @csv-tools/*last-lines-read* budget)
                (str "sample-rows read " @csv-tools/*last-lines-read* " lines")))
          (testing "profile-column reads only its bounded scan window"
            (reset! csv-tools/*last-lines-read* 0)
            ((tool path 'profile-column) "SOC_Code")
            ;; bounded read = header (1) + scan window + 1 lookahead probe
            ;; line (the +1 read-lines-bounded uses to set :more? without
            ;; realizing the rest of the file).
            (is (<= @csv-tools/*last-lines-read*
                    (+ csv-tools/max-profile-scan-rows 2))
                (str "profile-column read " @csv-tools/*last-lines-read*
                     " lines — must be capped at the profile scan window"))
            (is (< @csv-tools/*last-lines-read* 6098)
                "profile-column did NOT read the whole 6098-line file")))))))

(deftest profile-column-on-large-file-flags-scan-capped
  (testing "Over the real crosswalk, profile-column scans only a bounded
            window and reports :scan-capped? true honestly."
    (let [path real-crosswalk-path]
      (when (.exists (io/file path))
        (let [r ((tool path 'profile-column) "SOC_Code")]
          (is (= csv-tools/max-profile-scan-rows (:rows-scanned r)))
          (is (true? (:scan-capped? r))
              "more rows exist than the scan window -> honestly capped")
          (is (pos? (:distinct-count r))))))))

;; =============================================================================
;; AC5 — Docstring quality (PURPOSE / EXAMPLE / RETURNS), S19 pattern
;; =============================================================================

(deftest each-tool-docstring-is-self-contained
  (testing "Every CSV tool's docstring contains PURPOSE, EXAMPLE, and
            RETURNS — the model can use the tool from the docstring alone.
            The EXAMPLE must contain a concrete call form, not <placeholder>
            tokens."
    (let [docs csv-tools/csv-source-tool-docs
          required ["PURPOSE" "EXAMPLE" "RETURNS"]]
      (is (= #{'peek-columns 'sample-rows 'profile-column}
             (set (keys docs)))
          "exactly the three CSV source tools are documented")
      (doseq [[sym doc] docs]
        (testing (str sym " docstring has all required structural elements")
          (is (string? doc) (str sym " has a docstring"))
          (doseq [el required]
            (is (str/includes? doc el)
                (str sym " docstring missing required element: " el)))
          (let [example-section (second (str/split doc #"EXAMPLE"))]
            (is (some? example-section)
                (str sym " has an EXAMPLE section"))
            (is (str/includes? example-section "(")
                (str sym " EXAMPLE has a concrete code form"))
            (when-let [code-only (first (str/split example-section #"RETURNS"))]
              (is (not (re-find #"<arg\d?>" code-only))
                  (str sym " EXAMPLE has placeholder <arg> tokens")))))))))

(deftest the-fn-objects-carry-the-docstring-metadata
  (testing "Each bound fn carries its docstring on metadata so a sandbox
            (meta tool) introspection returns it — same wiring as S19."
    (let [path (write-tmp-csv! synthetic-csv)
          bindings (tools-for path)]
      (doseq [sym ['peek-columns 'sample-rows 'profile-column]]
        (is (string? (:doc (meta (get bindings sym))))
            (str sym " fn carries :doc metadata"))
        (is (= (get csv-tools/csv-source-tool-docs sym)
               (:doc (meta (get bindings sym))))
            (str sym " fn :doc matches the docs map"))))))

(deftest adversarial-stripping-a-section-fails-docstring-quality
  (testing "Proof the docstring-quality check is not trivially-passing: a
            docstring missing one of the three required sections fails it."
    (let [bad-doc "PURPOSE — does stuff. RETURNS — a map."
          required ["PURPOSE" "EXAMPLE" "RETURNS"]
          results (mapv #(str/includes? bad-doc %) required)]
      (is (not (every? identity results))
          "a docstring missing EXAMPLE fails the quality check")
      (is (= [true false true] results)
          "specifically EXAMPLE is the missing element"))))

;; =============================================================================
;; AC6 — Read-side only: no events, tools take no event-store
;; =============================================================================

(deftest tools-are-read-side-only
  (testing "csv-source-tools needs only a :csv-path — it accepts no
            event-store and the tools only read the file system; there is no
            write surface to emit events through."
    (let [path (write-tmp-csv! synthetic-csv)
          bindings (tools-for path)]
      (is (= #{'peek-columns 'sample-rows 'profile-column}
             (set (keys bindings)))
          "exactly the three read-side tools are exposed")
      ;; Calling every tool produces only data; no IO beyond reading the file.
      (is (map? ((get bindings 'peek-columns))))
      (is (map? ((get bindings 'sample-rows) 1)))
      (is (map? ((get bindings 'profile-column) "CIP_Code"))))))

(deftest builder-returns-nil-without-a-source
  (testing "Without a :csv-path the builder returns nil — the sandbox must
            not silently expose tools bound to no source (mirrors S19's
            no-grant -> nil safety default)."
    (is (nil? (csv-tools/csv-source-tools {})))
    (is (nil? (csv-tools/csv-source-tools {:csv-path nil})))))

;; =============================================================================
;; AC7 — Empty / edge CSV -> honest empty, not error or fabrication
;; =============================================================================

(deftest empty-and-edge-csv-are-honest
  (testing "A header-only CSV (no data rows) and a blank file return honest
            empty results, never an error or fabricated data."
    (testing "header-only file: columns present, zero rows"
      (let [path (write-tmp-csv! "a,b,c\n")
            bindings (tools-for path)]
        (let [pc ((get bindings 'peek-columns))]
          (is (= ["a" "b" "c"] (mapv :name (:columns pc))))
          (is (= 0 (:sample-row-count pc))
              "honest: zero data rows were available to sample types from"))
        (let [sr ((get bindings 'sample-rows) 5)]
          (is (= [] (:rows sr)))
          (is (false? (:capped? sr)) "no rows to cap"))
        (let [pr ((get bindings 'profile-column) "a")]
          (is (true? (:found? pr)) "column 'a' IS in the header")
          (is (= 0 (:rows-scanned pr)))
          (is (= 0 (:distinct-count pr)))
          (is (= [] (:examples pr))))))
    (testing "completely empty file: honest empty, no exception"
      (let [path (write-tmp-csv! "")
            bindings (tools-for path)]
        (let [pc ((get bindings 'peek-columns))]
          (is (= [] (:columns pc)) "no header -> no columns")
          (is (false? (:has-header? pc))))
        (let [sr ((get bindings 'sample-rows) 5)]
          (is (= [] (:rows sr))))))
    (testing "nonexistent file: honest :error marker, not a thrown exception"
      (let [bindings (tools-for "/no/such/file/anywhere.csv")
            pc ((get bindings 'peek-columns))]
        (is (some? (:error pc))
            "missing source surfaces as data, not a crash")))))
