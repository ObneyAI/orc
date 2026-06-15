(ns ai.obney.orc.orc-service.core.source-tools-csv
  "V03 — per-format SOURCE-ACCESS tools for CSV, granted to the discovery
   RLM so it can EXPLORE a CSV by its shape WITHOUT loading the whole file
   into context.

   This is the CSV leg of the format-aware-ingestion ADR (PRD module M-P1).
   It mirrors the S19 graph-tools pattern (ai.obney.orc.orc-service.core
   .sandbox-tools) — a {symbol -> fn} bindings-map builder whose fns carry
   self-contained docstrings (PURPOSE / EXAMPLE / RETURNS) and enforce their
   own bounds INSIDE the tool rather than trusting the model. There, the
   isolation invariant was the granted ontology scope; here it is the
   BOUNDED READ: the tools sample/profile, they never dump.

   ## The three tools

   - peek-columns   — the header columns + an inferred type per column,
                      plus FK-like / crosswalk relationship hints. Reads the
                      header + a small inference sample only.
   - sample-rows    — at most N rows (hard-capped) as maps keyed by header.
   - profile-column — distinct-count / cardinality-ratio / example values /
                      top values over a BOUNDED scan window.

   ## Ported csv_ontology knowledge

   The extraction knowledge from the old ai.obney.orc.ontology.sheets
   .csv-ontology sheet is ported into these tools' internals (reuse, not
   rewrite): columns -> properties (camelCase suggested-property), label-like
   columns -> classes (PascalCase suggested-class), rows -> individuals
   (the shape sample-rows exposes), and adjacent FK-like / code columns ->
   relationships (the crosswalk relationship-hints peek-columns surfaces).
   The column-type name-patterns + camel/pascal-case helpers are ported
   verbatim in spirit.

   ## Bounded-read invariant (adversarial requirement)

   Every read goes through `read-lines-bounded`, which takes AT MOST `n`
   physical lines off a line-seq and records the count it consumed in
   `*last-lines-read*`. No tool ever realizes the whole file: peek reads
   header + a small sample; sample-rows reads header + N (hard-capped at
   `max-sample-rows`); profile-column reads header + at most
   `max-profile-scan-rows`. The test instruments `*last-lines-read*` to
   prove a full-file dump never happens.

   ## Read-side only

   The tools take a `:csv-path` and read the file system. They hold no
   event-store and have no write surface — there is nothing to emit events
   through. (The S19 read-side discipline, here for sources.)

   ## Wiring

   `csv-source-tools` returns the {symbol -> fn} map a sandbox can merge
   into its bindings, given a `:csv-path`. It returns nil when no source is
   supplied — the sandbox MUST NOT silently expose source-less tools. The
   unified source-tool registry (selecting csv/sql/excel by format) is wired
   separately in V06; this namespace owns only the CSV leg."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; Bounds (the sample-never-dump invariant)
;; =============================================================================

(def max-sample-rows
  "Hard cap on sample-rows — an absurd N is clamped to this so a model can
   never dump the file through sample-rows."
  100)

(def max-profile-scan-rows
  "profile-column scans at most this many data rows. Cardinality over a
   bounded window with an honest :scan-capped? flag, never a full-file scan."
  2000)

(def peek-sample-rows
  "peek-columns infers a column's type from at most this many data rows."
  20)

(def ^:dynamic *last-lines-read*
  "Instrumentation: the number of physical lines the most recent
   `read-lines-bounded` call consumed. The adversarial sample-never-dump
   test rebinds/reads this to assert no tool reads the whole file."
  (atom 0))

(defn- read-lines-bounded
  "Read AT MOST `n` physical lines from the CSV at `path`, returning a vector
   of raw line strings. Streams via line-seq and `take` — never realizes the
   whole file. Records the number of lines actually consumed in
   `*last-lines-read*`.

   Returns {:lines [...] :more? bool} where :more? indicates at least one
   line existed BEYOND the `n` we took (so callers can flag :capped? /
   :scan-capped? honestly). Returns {:error ...} when the file is missing
   or unreadable — honest data, not a thrown exception.

   `data-offset` (default 0) skips that many DATA lines (after the header line)
   before taking — so a caller can sample a window DEEPER in the file (the data
   at the top of a large sorted file may not overlap the keys other sources
   carry). The header line is always preserved as the first returned line; the
   skipped data lines are streamed-and-dropped (never realized)."
  ([path n] (read-lines-bounded path n 0))
  ([path n data-offset]
   (try
     (if (and path (.exists (io/file path)))
       (with-open [rdr (io/reader path)]
         (let [skip (max 0 (or data-offset 0))]
           (if (zero? skip)
             ;; Take n+1 so we can tell whether MORE lines existed beyond n,
             ;; without realizing the rest of the file.
             (let [taken (vec (take (inc n) (line-seq rdr)))
                   more? (> (count taken) n)
                   lines (vec (take n taken))]
               (reset! *last-lines-read* (count taken))
               {:lines lines :more? more?})
             ;; Offset: keep the header (first physical line), then drop `skip`
             ;; data lines and take the next n (plus 1 to detect :more?).
             (let [ls (line-seq rdr)
                   header (first ls)
                   after (drop (inc skip) ls)
                   taken (vec (take (inc n) after))
                   more? (> (count taken) n)
                   data (vec (take n taken))
                   lines (vec (cons header data))]
               (reset! *last-lines-read* (inc (count taken)))
               {:lines lines :more? more?}))))
       (do (reset! *last-lines-read* 0)
           {:error (str "CSV source not found or unreadable: " path)}))
     (catch Exception e
       (reset! *last-lines-read* 0)
       {:error (str "Failed to read CSV source " path ": " (.getMessage e))}))))

;; =============================================================================
;; Minimal RFC4180-ish single-line parser
;; (orc-service can't depend on the ontology component — where data.csv
;;  lives — so we parse one line at a time, which also keeps reads bounded.)
;; =============================================================================

(defn- parse-csv-line
  "Split one CSV line into fields. Handles double-quoted fields containing
   commas and escaped (\"\") quotes. Good enough for the structured sources
   the discovery RLM explores; the goal is SHAPE, not a full streaming CSV
   reader."
  [line]
  (loop [chars (seq line)
         field (StringBuilder.)
         fields []
         in-q? false]
    (if (empty? chars)
      (conj fields (.toString field))
      (let [c (first chars)
            r (rest chars)]
        (cond
          (and in-q? (= c \") (= (first r) \"))
          (do (.append field \") (recur (rest r) field fields true))

          (= c \")
          (recur r field fields (not in-q?))

          (and (= c \,) (not in-q?))
          (recur r (StringBuilder.) (conj fields (.toString field)) false)

          :else
          (do (.append field c) (recur r field fields in-q?)))))))

;; =============================================================================
;; Ported csv_ontology knowledge: name->case helpers + type patterns + FK
;; =============================================================================

(defn- to-camel-case
  "Convert a column name to camelCase for a property name.
   Ported from ai.obney.orc.ontology.sheets.csv-ontology/to-camel-case."
  [s]
  (if (str/blank? s)
    ""
    (let [words (str/split (str/replace s #"[_\-\s]+" " ") #"\s+")]
      (if (empty? words)
        ""
        (str (str/lower-case (first words))
             (apply str (map str/capitalize (rest words))))))))

(defn- to-pascal-case
  "Convert a column name to PascalCase for a class name.
   Ported from ai.obney.orc.ontology.sheets.csv-ontology/to-pascal-case."
  [s]
  (if (str/blank? s)
    ""
    (let [words (str/split (str/replace s #"[_\-\s]+" " ") #"\s+")]
      (apply str (map str/capitalize words)))))

(def ^:private column-type-name-patterns
  "Name-based column type detection, ordered. Ported from
   ai.obney.orc.ontology.sheets.csv-ontology/column-type-patterns (the
   subset meaningful for source exploration). Checked BEFORE value-based
   inference so a semantic code column (e.g. SOC_Code = \"19-1011\") is a
   \"code\", not mis-inferred as a string/decimal."
  [[:identifier  #"(?i)(^id$|_id$|index)"]
   [:code        #"(?i)(_code$|^code$|cip_code|soc_code)"]
   [:url         #"(?i)(url|link|website)"]
   [:percentage  #"(?i)(rate|percent|ratio)"]
   [:currency    #"(?i)(price|cost|earning|debt|salary|income|tuition|wage)"]
   [:label       #"(?i)(name|title|label)"]
   [:description #"(?i)(description|notes|comment|summary)"]
   [:date        #"(?i)(date|created|updated|timestamp|year)"]])

(defn- fk-candidate?
  "csv_ontology knowledge: *_id / ^id / *_code columns are FK candidates —
   the columns that, when adjacent, encode a relationship between entities."
  [col-name]
  (boolean (re-find #"(?i)(^id$|_id$|_code$|^code$)" col-name)))

(defn- infer-type
  "Infer a column type. Name-pattern first (ported csv_ontology), then a
   value-based fallback over the supplied sample values."
  [col-name sample-values]
  (let [name-type (some (fn [[t pat]] (when (re-find pat col-name) t))
                        column-type-name-patterns)]
    (if name-type
      (name name-type)
      (let [non-blank (remove str/blank? sample-values)]
        (cond
          (empty? non-blank) "unknown"
          (every? #(re-matches #"-?\d+" %) non-blank) "integer"
          (every? #(re-matches #"-?\d+\.\d+" %) non-blank) "decimal"
          (and (seq non-blank)
               (every? #{"true" "false" "yes" "no" "t" "f" "y" "n" "0" "1"}
                       (map str/lower-case non-blank)))
          "boolean"
          :else "string")))))

(defn- label-type? [t] (= "label" t))

;; =============================================================================
;; Header parsing
;; =============================================================================

(defn- read-header+sample
  "Read the header line + up to `sample-n` data lines, parsed. Returns
   {:header [..] :sample [[..] ..] :more? bool} or {:error ..}."
  [path sample-n]
  (let [{:keys [lines more? error]} (read-lines-bounded path (inc sample-n))]
    (if error
      {:error error}
      (if (empty? lines)
        {:header [] :sample [] :more? false :empty? true}
        {:header (parse-csv-line (first lines))
         :sample (mapv parse-csv-line (rest lines))
         :more? more?}))))

;; =============================================================================
;; Tool: peek-columns
;; =============================================================================

(defn- make-peek-columns-fn
  [{:keys [csv-path]}]
  (fn peek-columns []
    (let [{:keys [header sample error empty?]}
          (read-header+sample csv-path peek-sample-rows)]
      (cond
        error {:error error :columns []}
        empty? {:columns [] :has-header? false :sample-row-count 0}
        :else
        (let [columns
              (vec
               (map-indexed
                (fn [i col-name]
                  (let [vals (map #(nth % i "") sample)
                        t (infer-type col-name vals)]
                    (cond-> {:name col-name
                             :inferred-type t
                             :fk-candidate? (fk-candidate? col-name)
                             :suggested-property (to-camel-case col-name)}
                      (label-type? t)
                      (assoc :suggested-class (to-pascal-case col-name)))))
                header))
              ;; csv_ontology knowledge: adjacent FK-like / code columns
              ;; encode a relationship between the entities they reference.
              ;; A crosswalk (two code columns) -> a relationship hint.
              code-cols (filterv #(or (= "code" (:inferred-type %))
                                      (= "identifier" (:inferred-type %)))
                                 columns)
              rel-hints (vec
                         (for [a code-cols
                               b code-cols
                               :when (neg? (compare (:name a) (:name b)))]
                           {:from (:name a)
                            :to (:name b)
                            :kind :crosswalk
                            :note (str "Adjacent code columns '" (:name a)
                                       "' and '" (:name b)
                                       "' likely encode a relationship "
                                       "(crosswalk) — extract as an edge "
                                       "between the entities they identify.")}))]
          {:columns columns
           :has-header? true
           :sample-row-count (count sample)
           :relationship-hints rel-hints})))))

(def peek-columns-doc
  "PURPOSE — Inspect a CSV's SHAPE without loading it: the header columns,
   an inferred type per column, which columns look like foreign keys, and
   whether the file is a crosswalk (two code columns that should become a
   relationship edge). Call this FIRST to decide how to extract a source.

   EXAMPLE
     (peek-columns)
     ;; => {:columns [{:name \"CIP_Code\" :inferred-type \"code\"
     ;;                :fk-candidate? true :suggested-property \"cipCode\"}
     ;;               {:name \"CIP_Title\" :inferred-type \"label\"
     ;;                :fk-candidate? false :suggested-property \"cipTitle\"
     ;;                :suggested-class \"CipTitle\"}
     ;;               {:name \"SOC_Code\" :inferred-type \"code\"
     ;;                :fk-candidate? true :suggested-property \"socCode\"}]
     ;;     :has-header? true
     ;;     :sample-row-count 20
     ;;     :relationship-hints [{:from \"CIP_Code\" :to \"SOC_Code\"
     ;;                           :kind :crosswalk :note \"...\"}]}

   RETURNS — {:columns [{:name :inferred-type :fk-candidate? :suggested-property
              :suggested-class?} ...] :has-header? :sample-row-count
              :relationship-hints [{:from :to :kind :note} ...]}.
   Inferred types: code, identifier, label, description, integer, decimal,
   boolean, currency, percentage, date, url, string, unknown. A label
   column also gets a PascalCase :suggested-class. A missing file returns
   {:error ... :columns []} (data, not a crash); an empty file returns
   {:columns [] :has-header? false}.

   SCOPE — reads only the header + a small inference sample. It does NOT
   read the whole file.")

;; =============================================================================
;; Tool: sample-rows
;; =============================================================================

(defn- make-sample-rows-fn
  [{:keys [csv-path]}]
  (fn sample-rows
    ([] (sample-rows 10))
    ([n-or-opts]
     (let [opts? (map? n-or-opts)
           n (cond (integer? n-or-opts) n-or-opts
                   opts? (or (:limit n-or-opts) (:n n-or-opts) 10)
                   :else 10)
           offset (if opts? (or (:offset n-or-opts) 0) 0)
           requested (if (and (integer? n) (pos? n)) n 10)
           capped-n (min requested max-sample-rows)
           {:keys [lines more? error]} (read-lines-bounded csv-path (inc capped-n) offset)]
       (cond
         error {:rows [] :capped? false :error error}
         (empty? lines) {:rows [] :capped? false}
         :else
         (let [header (parse-csv-line (first lines))
               data-lines (rest lines)
               rows (mapv (fn [l] (zipmap header (parse-csv-line l)))
                          data-lines)]
           {:rows rows
            :returned (count rows)
            :requested requested
            :offset offset
            ;; capped? when more data rows existed beyond what we returned
            ;; (whether because of the hard cap or just a bigger file).
            :capped? (boolean more?)}))))))

(def sample-rows-doc
  "PURPOSE — Read the first N data rows of a CSV as maps keyed by the header,
   so you can see concrete example records (rows become individuals) without
   loading the file. Use after peek-columns to inspect real values.

   EXAMPLE
     (sample-rows 3)
     ;; => {:rows [{\"CIP_Code\" \"01.0000\" \"CIP_Title\" \"Agriculture, General.\"
     ;;             \"SOC_Code\" \"19-1011\"}
     ;;            {\"CIP_Code\" \"01.0000\" \"CIP_Title\" \"Agriculture, General.\"
     ;;             \"SOC_Code\" \"19-1012\"}
     ;;            {\"CIP_Code\" \"11.0701\" \"CIP_Title\" \"Computer Science.\"
     ;;             \"SOC_Code\" \"15-1252\"}]
     ;;     :returned 3 :requested 3 :capped? true}

   OFFSET — the arg may instead be a map {:limit <int> :offset <int>} to skip
   the first :offset DATA rows and sample a window DEEPER in the file. Use this
   when the top of a large (often sorted) file does not overlap the codes other
   sources carry, so the bridge they form would otherwise miss:
     (sample-rows {:limit 40 :offset 2000})  ; header + rows 2001..2040
   The header is always returned; skipped rows are streamed-and-dropped.

   RETURNS — {:rows [{header->value} ...] :returned :requested :offset :capped?}.
   :capped? is true when more data rows existed beyond what was returned.
   N is hard-capped (an absurd N is clamped) so this can never dump the
   file; with no data rows :rows is [] and :capped? is false (honest empty).

   SCOPE — reads only the header + at most N rows. It does NOT read the
   whole file.")

;; =============================================================================
;; Tool: profile-column
;; =============================================================================

(defn- make-profile-column-fn
  [{:keys [csv-path]}]
  (fn profile-column [column-name]
    (let [{:keys [lines more? error]}
          (read-lines-bounded csv-path (inc max-profile-scan-rows))]
      (cond
        error
        {:column column-name :found? false :error error
         :rows-scanned 0 :distinct-count 0 :examples [] :top-values []}

        (empty? lines)
        {:column column-name :found? false
         :rows-scanned 0 :distinct-count 0 :examples [] :top-values []
         :scan-capped? false}

        :else
        (let [header (parse-csv-line (first lines))
              idx (.indexOf ^java.util.List (vec header) column-name)]
          (if (neg? idx)
            ;; Honest: the column isn't in the header. NOT a fabricated profile.
            {:column column-name :found? false
             :rows-scanned 0 :distinct-count 0 :examples [] :top-values []
             :available-columns (vec header)
             :scan-capped? false}
            (let [data-lines (rest lines)
                  vals (map #(nth (parse-csv-line %) idx "") data-lines)
                  non-blank (remove str/blank? vals)
                  freq (frequencies non-blank)
                  scanned (count vals)
                  distinct-count (count freq)]
              {:column column-name
               :found? true
               :rows-scanned scanned
               :distinct-count distinct-count
               :null-count (- scanned (count non-blank))
               :cardinality-ratio (if (pos? scanned)
                                     (/ (double distinct-count) (double scanned))
                                     0.0)
               :examples (vec (take 5 (keys freq)))
               :top-values (vec (take 5 (sort-by val > freq)))
               ;; honest: did we hit the scan window before the file ended?
               :scan-capped? (boolean more?)})))))))

(def profile-column-doc
  "PURPOSE — Profile ONE column over a bounded scan: how many distinct
   values, the cardinality ratio (distinct/scanned — low means categorical /
   a good class or hierarchy candidate, ~1.0 means a key/identifier),
   example values and the most frequent values. Use to decide whether a
   column is an entity key, a category, or free text.

   EXAMPLE
     (profile-column \"SOC_Code\")
     ;; => {:column \"SOC_Code\" :found? true
     ;;     :rows-scanned 2000 :distinct-count 196 :null-count 0
     ;;     :cardinality-ratio 0.098
     ;;     :examples [\"19-1011\" \"19-1012\" \"15-1252\" \"25-1062\" \"11-9013\"]
     ;;     :top-values [[\"25-1062\" 50] [\"25-1041\" 49] [\"11-9013\" 33]]
     ;;     :scan-capped? true}

   RETURNS — {:column :found? :rows-scanned :distinct-count :null-count
              :cardinality-ratio :examples :top-values :scan-capped?}.
   :found? is false (with empty stats + :available-columns) when the column
   name is not in the header — honest, never a fabricated profile.
   :scan-capped? is true when more rows existed beyond the scan window.

   SCOPE — scans only a bounded window of rows. It does NOT read the whole
   file.")

;; =============================================================================
;; Public binding builder + docs map
;; =============================================================================

(def csv-source-tool-docs
  "The {symbol -> docstring} map for the three CSV source tools. Exposed so
   a source-orientation card or seed-corpus author can pull the same
   docstrings the sandbox sees without building bindings (which needs a
   :csv-path). Same role as S19's ontology-tool-docs."
  {'peek-columns   peek-columns-doc
   'sample-rows    sample-rows-doc
   'profile-column profile-column-doc})

(defn csv-source-tools
  "Return the SCI {symbol -> fn} map for the three CSV source-access tools,
   bound to the source at `:csv-path`. Same flavor as S19's
   build-ontology-tool-bindings.

   cfg keys:
     :csv-path  REQUIRED. Absolute path to the CSV source to explore.

   Returns nil when no :csv-path is supplied — the sandbox MUST NOT silently
   expose source-less tools (mirrors S19's no-grant -> nil safety default).

   Each fn carries its docstring on metadata (via with-meta) so a sandbox's
   (meta peek-columns) introspection returns the same doc the docstring-
   quality test reads."
  [{:keys [csv-path] :as cfg}]
  (when csv-path
    (let [with-doc (fn [f doc] (with-meta f {:doc doc}))]
      {'peek-columns   (with-doc (make-peek-columns-fn cfg) peek-columns-doc)
       'sample-rows    (with-doc (make-sample-rows-fn cfg) sample-rows-doc)
       'profile-column (with-doc (make-profile-column-fn cfg) profile-column-doc)})))
