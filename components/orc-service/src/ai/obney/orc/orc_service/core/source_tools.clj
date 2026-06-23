(ns ai.obney.orc.orc-service.core.source-tools
  "V06 — the UNIFIED per-format source-tool registry + the MC-1 uniform
   container contract.

   ## MC-1 — the uniform CONTAINER contract (format-agnostic)

   A source is a COLLECTION of CONTAINERS: a SQL database is a collection of
   tables, an Excel workbook (or a directory of workbooks) is a collection of
   sheets, a CSV is a single-container source. Every per-format specialist
   exposes the SAME four logical operations over its containers, so the extract
   pipeline (MC-4/MC-5) calls ONE format-agnostic surface and SQL / Excel / CSV
   become a single code path.

   The four contract operations + their UNIFORM return shapes (locked by the
   MC-1 prototype against the real IPEDS DB / cip_soc_crosswalk.csv / O*NET
   `db_30_1_excel` directory):

   - `list-containers`  → `[{:name <str> ...}]`
       The containers in the source. sql: one entry per table; excel: one entry
       per sheet across the workbook(s)/dir; csv: the single file; text: n/a.
       Each entry ALWAYS carries `:name`; a specialist MAY carry extra keys it
       needs to address the container later (excel adds `:path` + `:sheet`).
   - `sample-rows` *(container, n)*  → `[keyed-map]`
       A vector of column-name→value maps — KEYED, never positional, UNIFORM
       across media. (csv keys by header string, sql by column keyword; the
       excel specialist's keyed conversion lands in MC-2 — today it still emits
       positional cell vectors, the one declared NON-conformance MC-1 records.)
   - `stream-all` *(container, opts)*  → keyed-map windows
       Bounded windows that together cover every row of a container exactly
       once (the substrate the deterministic full-extraction transform runs
       over). Same keyed-row shape as `sample-rows`.
   - `relations` *(container)*  → `[{:from :to :via}]`  (OPTIONAL)
       Cross-container links. sql: declared foreign-keys; csv/excel: a
       shared-column heuristic. May be `[]` (IPEDS declares no FKs — those
       sources link by shared key columns; the heuristic lands in MC-2/MC-3).

   `container-contract` (below) maps the EXISTING per-format tool symbols onto
   these four logical names WITHOUT forking the registry — the per-format
   specialist still owns the medium-specific reads (P8 re-orchestrate). MC-2/
   MC-3 make excel / sql FULLY conform (keyed excel rows; sql shared-key
   relations); MC-1 DECLARES the contract + the resolvable surface and fixes
   format/directory resolution.

   ## V06 — the UNIFIED per-format source-tool registry.

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
            [clojure.java.io :as io]
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

(defn- directory-of-workbooks?
  "True when `path` is an existing DIRECTORY (the O*NET `db_30_1_excel` shape —
   a folder of .xlsx workbooks). A directory has no file extension, so the
   extension table can never resolve it; this is the second resolution gap MC-1
   closes (excel-dir-sheets reads exactly this shape). We treat ANY directory as
   the excel-directory container source — the excel specialist's
   `excel-dir-sheets` is what enumerates the .xlsx files inside (and surfaces a
   per-file error for a non-workbook), so we deliberately do NOT pre-scan
   contents here."
  [path]
  (boolean (and path (string? path) (seq path)
                (.isDirectory (io/file path)))))

(defn detect-format
  "Resolve a source descriptor's format. Resolution order:

     1. An explicit `:format` keyword wins.
     2. MC-1: a `:type` keyword is honored as a FALLBACK for `:format` — extract
        descriptors carry `{:type :excel :path …}` (not `:format`), and that
        `:type` must resolve to the same format the survey's `:granted-source`
        gets via `:format`.
     3. A `:path` ending in a known extension (.csv/.db/.xlsx/…).
     4. MC-1: a `:path` that is an existing DIRECTORY → `:excel` (a directory of
        workbooks has no extension, so steps 3 falls through; this is the gap
        that threw \"no source tools for descriptor\" on the O*NET directory).
     5. Otherwise `:text`.

   Returns one of `:csv` `:sql` `:excel` `:text`, or a non-nil keyword that is
   NOT one of those when the caller forced an unknown `:format`/`:type` (the
   dispatch site turns that into a loud failure — Discipline #5, no silent skip)."
  [{:keys [format type path]}]
  (or format
      type
      (when (and path (string? path))
        (let [p (str/lower-case path)]
          (some (fn [[ext fmt]] (when (str/ends-with? p ext) fmt))
                extension->format)))
      (when (directory-of-workbooks? path) :excel)
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
     :format  optional — `:csv` `:sql` `:excel` `:text`. When absent, falls back
              to `:type` (extract descriptors pass `:type`), then the `:path`
              extension, then a directory→`:excel` check (MC-1).
     :type    optional — the SAME format keyword under the extract pipeline's
              key. Honored as a fallback for `:format` (MC-1 resolution fix).
     :path    the absolute path to the source file (a .csv / .db / .xlsx) OR a
              DIRECTORY of .xlsx workbooks (resolves to `:excel` via the
              directory check). REQUIRED for every structured format.

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

;; =============================================================================
;; MC-1 — the uniform CONTAINER contract surface
;; =============================================================================

(def contract-operations
  "The four logical operations of the uniform container contract (see the ns
   docstring for the full shapes). Format-agnostic — the names a consumer calls."
  #{:list-containers :sample-rows :stream-all :relations})

(def ^:private contract->tool-symbol
  "DECLARATION: for each structured format, which EXISTING per-format tool
   symbol implements each logical contract operation. This is the seam that maps
   the medium-specific tool names onto the four uniform operations WITHOUT
   forking the registry (P8) — the specialist still owns the read; this only
   records the mapping. `nil` = the format does not (yet) expose that operation
   as a single tool (e.g. csv has no per-container `relations` tool — its
   crosswalk hints ride on `peek-columns`; excel's keyed `sample-rows`/keyed
   `stream-all` + shared-column `relations` are MC-2).

   Read this as the per-format conformance ledger MC-2/MC-3 drive to full:
     - sql is FULLY conformant for list/sample/stream; `relations` =
       `foreign-keys` (empty on FK-less IPEDS — the shared-key heuristic is MC-3).
     - csv exposes list (synthesized: the single file) + sample + stream; it has
       no standalone `relations` tool (crosswalk hints live on `peek-columns`).
     - excel exposes list (`excel-dir-sheets`/`list-sheets`) + sample + stream,
       but its rows are POSITIONAL today — keyed `sample-rows`/`stream-all` and a
       `relations` heuristic are the MC-2 work this ledger tracks."
  {:sql   {:list-containers 'list-tables
           :sample-rows     'sample-rows
           :stream-all      'stream-all
           :relations       'foreign-keys}
   :csv   {:list-containers nil           ; single-container — synthesized below
           :sample-rows     'sample-rows
           :stream-all      'stream-all
           :relations       nil}          ; crosswalk hints ride on peek-columns
   :excel {:list-containers 'excel-dir-sheets ; or list-sheets for one workbook
           :sample-rows     'sample-rows  ; POSITIONAL today — keyed in MC-2
           :stream-all      'stream-all   ; POSITIONAL today — keyed in MC-2
           :relations       nil}})        ; shared-column heuristic is MC-2

(defn container-contract
  "Resolve a descriptor to its uniform container-contract surface: a map of the
   four logical operations → the BOUND per-format fn implementing each (or nil
   when the format doesn't expose that operation as a standalone tool). This is
   the ONE format-agnostic surface the extract pipeline (MC-4/MC-5) calls, so
   SQL / Excel / CSV are a single code path.

   Returns nil for a `:text` descriptor (text uses the blackboard path). Throws
   on an unknown format / blank path — same loud discipline as `source-tools-for`
   (Discipline #5), since it builds the bindings through it.

   The returned `:list-containers` is ALWAYS a 0-arg fn returning `[{:name …}]`,
   even for the single-container csv case (synthesized from the path) — so a
   consumer never special-cases a format. The per-row operations are the bound
   per-format fns; their UNIFORM keyed-map shape is the contract MC-2/MC-3 drive
   the specialists to (excel rows are positional until MC-2 — the one declared
   non-conformance)."
  [{:keys [path] :as descriptor}]
  (let [fmt   (detect-format descriptor)
        tools (source-tools-for descriptor)]   ; throws on unknown / blank path
    ;; :text has no container contract (no structured tools — blackboard path).
    (when-not (= :text fmt)
      (let [mapping (get contract->tool-symbol fmt)
            tool-fn (fn [op] (when-let [sym (get mapping op)] (get tools sym)))]
        {:format          fmt
         :list-containers (case fmt
                            ;; csv is a single container: synthesize the
                            ;; uniform [{:name …}] shape from the file path so a
                            ;; consumer never special-cases the single-file case.
                            :csv (fn list-containers []
                                   [{:name (.getName (io/file path)) :path path}])
                            ;; sql / excel expose a real listing tool; wrap its
                            ;; result into the uniform [{:name …}] shape.
                            :sql (let [f (tool-fn :list-containers)]
                                   (fn list-containers []
                                     (mapv (fn [t] {:name (str t)}) (f))))
                            :excel (let [dir?         (directory-of-workbooks? path)
                                         list-sheets  (get tools 'list-sheets)
                                         dir-sheets   (get tools 'excel-dir-sheets)]
                                     ;; Excel tools are path-per-call (no cfg) — the
                                     ;; source :path is what addresses them. A
                                     ;; DIRECTORY enumerates workbooks via
                                     ;; excel-dir-sheets; a single workbook via
                                     ;; list-sheets. Both normalize to one container
                                     ;; per sheet carrying :name + the :path + :sheet
                                     ;; a per-row op needs to read it.
                                     (fn list-containers []
                                       (if dir?
                                         (vec
                                          (mapcat
                                           (fn [{:keys [file path sheets]}]
                                             (if (sequential? sheets)
                                               (map (fn [s] {:name s :path path :sheet s
                                                             :workbook file})
                                                    sheets)
                                               [{:name file :path path :error sheets}]))
                                           (dir-sheets path)))
                                         (mapv (fn [{:keys [name]}]
                                                 {:name name :path path :sheet name})
                                               (list-sheets path))))))
         :sample-rows     (tool-fn :sample-rows)
         :stream-all      (tool-fn :stream-all)
         :relations       (tool-fn :relations)}))))
