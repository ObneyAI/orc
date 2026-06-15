(ns ai.obney.orc.orc-service.core.source-tools
  "V06 — the UNIFIED per-format source-tool registry.

   The V03/V04/V05 legs each own a per-format namespace (source-tools-csv,
   source-tools-sql, source-tools-excel) so they could be built in parallel
   without a shared-file conflict. This namespace is the seam that V06 owns:
   it DISPATCHES BY FORMAT to exactly ONE leg, so the discovery RLM is granted
   the format-appropriate source-access tools and nothing else.

   ## Why dispatch, not merge

   Two legs export a COLLIDING symbol: V03 (csv) and V05 (excel) both expose
   `sample-rows`, with DIFFERENT arities (csv: 0/1-arg `(sample-rows [n])`;
   excel: 2/3-arg `(sample-rows path sheet [n])`). Naively merging the binding
   maps would silently shadow one with the other and hand the model a tool whose
   arity doesn't match its format. So we SELECT one leg's full tool-set by the
   detected format; we never merge across formats. A single source is exactly
   one format, so a single discovery session ever needs exactly one leg.

   ## Format detection

   `detect-format` honors an explicit `:format` on the descriptor; otherwise it
   infers from the path extension. A `:text` source (or anything with no
   structured-source path) routes to NO source tools — text discovery uses the
   existing blackboard `:content` path, not the bounded-read source tools.

   ## Loud on the unknown (adversarial requirement, Discipline #5)

   A descriptor whose format is neither a known structured format nor `:text`
   fails LOUDLY with a clear error — there is NO silent skip. A recognized
   structured format with a missing/blank path also surfaces (the per-leg
   builder returns nil, which `source-tools-for` reports as a loud anomaly
   rather than silently granting no tools)."
  (:require [ai.obney.orc.orc-service.core.source-tools-csv :as csv]
            [ai.obney.orc.orc-service.core.source-tools-sql :as sql]
            [ai.obney.orc.orc-service.core.source-tools-excel :as excel]
            [clojure.string :as str]))

;; =============================================================================
;; Format detection
;; =============================================================================

(def ^:private extension->format
  "File-extension → structured-source format. Anything not here (and with no
   explicit :format) is treated as :text."
  {".csv"     :csv
   ".tsv"     :csv
   ".db"      :sql
   ".sqlite"  :sql
   ".sqlite3" :sql
   ".xlsx"    :excel})

(defn detect-format
  "Resolve a source descriptor's format. An explicit `:format` keyword wins;
   otherwise infer from the `:path` extension; otherwise `:text`.

   Returns one of `:csv` `:sql` `:excel` `:text`, or a non-nil keyword that is
   NOT one of those when the caller forced an unknown `:format` (the dispatch
   site turns that into a loud failure)."
  [{:keys [format path]}]
  (or format
      (when (and path (string? path))
        (let [p (str/lower-case path)]
          (some (fn [[ext fmt]] (when (str/ends-with? p ext) fmt))
                extension->format)))
      :text))

;; =============================================================================
;; Unified dispatch
;; =============================================================================

(def known-formats
  "The structured-source formats this registry can build tools for, plus
   `:text` (which routes to NO source tools — text uses the blackboard path)."
  #{:csv :sql :excel :text})

(defn source-tools-for
  "Return the `{symbol -> fn}` source-access tool bindings for a single
   structured-source descriptor, dispatched by its detected format.

   descriptor keys:
     :format  optional — `:csv` `:sql` `:excel` `:text`. When absent, inferred
              from `:path`.
     :path    the absolute path to the source file (a .csv / .db / .xlsx /
              directory of .xlsx for excel-dir). REQUIRED for every structured
              format.

   Dispatch (never a merge — see ns docstring on the `sample-rows` collision):
     :csv   → csv/csv-source-tools   {:csv-path path}
     :sql   → sql/sql-source-tools   {:db-path  path}
     :excel → excel/excel-source-tools  (filesystem readers; path passed per-call)
     :text  → nil (no source tools; text discovery uses blackboard :content)

   Adversarial (Discipline #5 — no silent skip):
     - An unknown format (neither structured nor :text) throws.
     - A recognized structured format whose per-leg builder returns nil (missing/
       blank path) throws — the sandbox must NOT be granted source tools without
       a source."
  [{:keys [path] :as descriptor}]
  (let [fmt (detect-format descriptor)]
    (when-not (contains? known-formats fmt)
      (throw (ex-info (str "source-tools-for: unknown source format " (pr-str fmt)
                           "; known structured formats: " (disj known-formats :text)
                           " (or :text for unstructured content)")
                      {:descriptor descriptor :format fmt})))
    (case fmt
      :text nil
      ;; Every structured format needs a real, non-blank path. We assert this
      ;; up front (not all per-leg builders reject a blank path — the csv leg
      ;; treats "" as truthy, the excel leg takes no cfg at all), so a missing
      ;; source fails LOUDLY here rather than silently granting nothing or
      ;; granting tools bound to an empty path.
      (do
        (when-not (and (string? path) (seq path))
          (throw (ex-info (str "source-tools-for: format " fmt
                               " requires a non-blank :path; got " (pr-str path))
                          {:descriptor descriptor :format fmt})))
        (case fmt
          :csv   (csv/csv-source-tools {:csv-path path})
          :sql   (sql/sql-source-tools {:db-path path})
          :excel (excel/excel-source-tools))))))

(defn source-tool-docs-for
  "Return the `{symbol -> docstring}` map for a descriptor's format, so an
   orientation surface or a discovery prompt can show the model the same
   docstrings the sandbox will expose — WITHOUT building bindings (which need a
   path). `:text` → nil. Unknown format throws (same discipline as
   `source-tools-for`)."
  [descriptor]
  (let [fmt (detect-format descriptor)]
    (when-not (contains? known-formats fmt)
      (throw (ex-info (str "source-tool-docs-for: unknown source format " (pr-str fmt))
                      {:descriptor descriptor :format fmt})))
    (case fmt
      :csv   csv/csv-source-tool-docs
      :sql   sql/sql-source-tool-docs
      :excel excel/excel-source-tool-docs
      :text  nil)))
