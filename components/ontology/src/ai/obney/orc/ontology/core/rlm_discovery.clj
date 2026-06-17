(ns ai.obney.orc.ontology.core.rlm-discovery
  "S18 — Recursive-RLM ontology discovery wiring + the
   `:rlm-discovery` source-type adapter that feeds discovery output
   into S17's deterministic skeleton (`build!`).

   ## What this namespace owns

   Two coordinated entry points:

   1. `run-discovery!` — construct a recursive-RLM session granted
      S19's ontology tools + S20's orientation card (via the existing
      `rlm-sandbox` wiring inside `execute-repl-researcher`) + access
      to the S18 ontology-discovery seed corpus through
      `classify-behaviors`. The session is asked to extract concept,
      relationship, and axiom drafts from the supplied source(s). The
      returned shape is consumable by `compile-discovery-source!`.

   2. `compile-discovery-source!` — the `:rlm-discovery` source-type
      ADAPTER. Translates the discovery output into the
      `:inline-concepts` / `:inline-relationships` source-types S17's
      `parse-stage` already knows. This keeps S17 unchanged — its
      pipeline driver still sees only the source-types it ships with;
      this namespace is the bridge that lets recursive-RLM discovery
      plug INTO that pipeline without modifying S17's namespace.

   ## R-Inject boundary

   `:auto-classify?` is NOT a precondition. The recursive-RLM session
   runs unconditionally — designing trees, calling tools, using the
   card. R-Inject opt-in only affects whether the classify-task /
   classify-behaviors path PREPENDS retrieved patterns into the
   model's design prompt. The discovery wiring opts in by default
   here (the seed corpus exists to be retrieved); a caller can opt
   out by setting `:auto-classify? false` on the rlm config.

   ## HITL gating

   When `:require-hitl-reviewed-patterns? true` is supplied, the
   pattern corpus offered to the session via classify-behaviors is
   FILTERED to entries explicitly marked `:hitl-status :hitl-reviewed`.
   Default behavior includes `:hitl-status :auto-derived` patterns
   (the bench-derived seeds shipped with the component).

   ## Recursive-only

   The session is constructed with `:rlm {:recursive? true ...}` —
   terminal-mode RLM is NOT used for discovery. A caller passing
   `:recursive? false` will see the session refuse construction
   (defensive — discovery without the multi-tree iteration loop loses
   the recovery-from-failed-leaves affordance the recursive mode
   provides)."
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.ontology.core.seeds :as seeds]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.dedup-cascade :as dedup]
            [clojure.string :as str]))

;; =============================================================================
;; Lazy resolution of orc-service executor + rlm-sandbox.
;; =============================================================================
;; Ontology depends on orc-service (sheets/ namespace) so the require IS
;; available, but we lazy-resolve here so the discovery namespace doesn't
;; force-load the executor at compile time (the executor's compile-time
;; deps are heavy and surface in test classpaths without it).

(defn- executor-fn [sym]
  (or (requiring-resolve sym)
      (throw (ex-info (str "rlm-discovery requires orc-service.core.executor on the classpath. "
                           "Could not resolve: " sym)
                      {:symbol sym}))))

(defn- resolve-or-throw
  "Lazy-resolve a fully-qualified symbol from a peer component (orc-service),
   throwing a clear classpath error when it's absent. Mirrors `executor-fn`'s
   discipline — the ontology component depends on orc-service at runtime (the
   per-format source tools + the SCI sandbox both live there), but we keep the
   resolution deferred so the ontology brick's isolated compile doesn't force
   orc-service onto its declared deps."
  [sym what]
  (or (requiring-resolve sym)
      (throw (ex-info (str "rlm-discovery requires " what " on the classpath. "
                           "Could not resolve: " sym)
                      {:symbol sym}))))

;; V20 — forward declaration; the deterministic full-extraction apply-step is
;; defined further down (after run-discovery!) but run-discovery! calls it when
;; the model hands back a sample-validated transform.
(declare apply-extraction-transform!)

;; =============================================================================
;; Discovery prompt assembly
;; =============================================================================

(def default-discovery-prompt
  "The default discovery instruction handed to the recursive-RLM
   session. Discovery callers can override per-task via
   `:discovery-prompt`. Self-contained — no external file references,
   no slice names. The prompt explicitly orients the model on the
   shape it must produce so `compile-discovery-source!` can ingest
   without ambiguity."
  (str
   "TASK: Extract an ontology draft (concepts + relationships + axioms) "
   "from the supplied source content.\n\n"
   "You have a recursive RLM environment. Tools available include:\n"
   "  - graph-search, neighborhood, get-concept, exists?, absent-in-graph?, "
   "filter-by-label-pattern (S19 ontology tools — these query the EXISTING graph "
   "so you don't duplicate concepts that already exist)\n"
   "  - classify-task, classify-behaviors (existing classifier surface — calling "
   "(classify-behaviors {:task-signature \"<your goal>\"}) retrieves patterns that "
   "fit your discovery task)\n"
   "  - emit-tree! (the recursive RLM tree designer — design ONE tree per discovery pass)\n\n"
   "FIRST: call (classify-behaviors {:task-signature \"ontology discovery from <type> source\"}) "
   "to see which ontology-discovery patterns the corpus suggests. The retrieved patterns are "
   "behavioral subtrees specialized for discovery — choose the one that fits your source's "
   "size and shape, adapt it, or design a fresh tree if none fit.\n\n"
   "ENTITY-AS-NODE MODELING (read carefully — this shapes a USEFUL graph): mint as "
   "CONCEPTS the entities that BEAR THEIR OWN ATTRIBUTES or are REFERENCED BY OTHER "
   "ENTITIES. A thing that HAS PROPERTIES, or that you would RETRIEVE / RECOMMEND / "
   "DESCRIBE, is a NODE — give it its own :uri and :concept-draft. Do NOT represent a "
   "real entity ONLY as an EDGE (a :relationship-draft) between two other concepts: an "
   "edge has no identity, no attributes, and cannot be retrieved or described. For "
   "example, if each row of a source is a distinct thing with its own values, that thing "
   "is a NODE carrying those values in :attributes — and the categories / owners / codes "
   "it refers to are ALSO nodes, with edges CONNECTING the node to them. Edges are for the "
   "RELATIONSHIPS between nodes, never a substitute for a node.\n\n"
   "OUTPUT SHAPE (via (final! ...)):\n"
   "  {:concept-drafts [{:uri <str> :label <str> :description <str> :scope <kw> "
   "                     :attributes {<key> <value> ...} "
   "                     :evidence [{:source <str> :quote <str>}]} ...]\n"
   "     — :attributes is an OPTIONAL map of QUERYABLE facts about the concept. "
   "Put any NUMERIC OUTCOME or grounding value here (e.g. earnings/wage figures, "
   "tuition / net-cost, employment counts, growth rates, percentiles) keyed by a "
   "short name, with the value kept as its native type (a NUMBER stays a number — "
   "do NOT stringify it). These attributes are how a downstream query reads the "
   "outcome back, so a program/occupation concept that has an earnings or wage "
   "figure MUST carry it in :attributes (not only in prose).\n"
   "   :relationship-drafts [{:source-uri <str> :target-uri <str> :predicate <str> "
   "                          :confidence-class :extracted "
   "                          :evidence [{:source <str> :quote <str>}]} ...]\n"
   "   :axiom-drafts [{:axiom-type <one of \"disjointness\" / "
   "\"property-characteristic\" / \"sub-property\" / \"chain\"> "
   ":body <map> :evidence [{:source <str> :quote <str>}]} ...]\n"
   "     — axiom :body shapes by :axiom-type:\n"
   "         \"disjointness\"            {:class-uris [<concept-uri> <concept-uri> ...]}  (>=2 URIs)\n"
   "         \"property-characteristic\" {:predicate <str> :characteristic [<one or more of "
   "\"functional\" \"transitive\" \"symmetric\">] :inverse-of <str, optional>}\n"
   "         \"sub-property\"            {:sub-predicate <str> :super-predicate <str>}\n"
   "         \"chain\"                   {:chain [<predicate> <predicate> ...] (>=2) :derived-predicate <str>}\n"
   "     Only emit an axiom the source TEXT supports; do NOT invent OWL structure.\n"
   "   :rlm-trace [<your iteration summaries — what you classified, what tree "
   "               you emitted, what failures you recovered from>]}\n\n"
   "GROUNDING DISCIPLINE: every :concept-drafts / :relationship-drafts entry MUST carry "
   "a verbatim quote in :evidence. Drafts without quotes are dropped during ingest. "
   "Do NOT speculate beyond what the source text states."))

;; =============================================================================
;; V06 — format-aware source exploration
;; =============================================================================
;; A discovery source can be a RAW STRUCTURED SOURCE (a CSV / SQLite / Excel
;; file path) rather than inline text content. For these, the session is
;; granted the per-format SOURCE-ACCESS tools (V03/04/05) via the rlm-config's
;; :granted-source seam, and the prompt is augmented with format-specific
;; EXPLORATION guidance so the model samples the source by its shape WITHOUT
;; loading it, and mints SHAREABLE code-system URIs so the graph CONNECTS
;; across sources (the keystone of the format-aware-ingestion ADR).

(def ^:private structured-formats
  "The structured source formats that route to per-format source tools. A
   source whose :type is one of these is explored via the granted tools and
   carries a :path; anything else is treated as inline text content."
  #{:csv :sql :excel})

(defn- structured-source?
  "True when a discovery source is a raw structured source (has a structured
   :type and a :path) rather than inline text."
  [source]
  (and (contains? structured-formats (:type source))
       (string? (:path source))
       (seq (:path source))))

(def ^:private cross-source-linking-rule
  "The load-bearing instruction (validated in the V06 prototype): mint concepts
   with STABLE, SHAREABLE code-system URIs so other sources keyed on the same
   codes resolve to the SAME concept — the graph CONNECTS rather than forming
   per-source piles."
  (str
   "CROSS-SOURCE LINKING (CRITICAL — this is why the graph must CONNECT): when a "
   "column / field is a CODE or KEY that ALSO identifies an entity appearing in "
   "OTHER sources (e.g. a CIP code, a SOC code, an institution UNITID), mint each "
   "distinct code value as a concept whose :uri is derived from the CODE SYSTEM and "
   "the code value — a STABLE, SHAREABLE uri (e.g. \"cip:01.0000\", \"soc:19-1011\", "
   "\"unitid:100654\") — NOT a row-local or file-local id. Use the SAME uri scheme a "
   "DIFFERENT source would use for the same code, so concepts MERGE by shared uri. "
   "Then emit the relationships those rows encode as :relationship-drafts between "
   "those shareable uris (a crosswalk row CIP->SOC becomes an edge linking "
   "\"cip:<code>\" to \"soc:<code>\"). This is how a crosswalk CONNECTS the CIP and "
   "SOC concepts that other sources contribute."))

(defmulti ^:private format-exploration-guidance
  "Per-format EXPLORATION guidance prepended to the discovery prompt for a
   structured source. Dispatches on the source :type."
  (fn [source] (:type source)))

(defmethod format-exploration-guidance :csv
  [_]
  (str
   "SOURCE FORMAT: CSV. You have these source-access tools (sample, never dump):\n"
   "  (peek-columns)            — FIRST CALL. Header columns, an inferred type per "
   "column, which columns look like foreign keys, and :relationship-hints (two code "
   "columns = a crosswalk that should become an EDGE).\n"
   "  (sample-rows N)           — at most N real data rows as maps keyed by header.\n"
   "  (profile-column \"<col>\")  — distinct-count / cardinality of one column over a "
   "bounded scan (low ratio = a category/class candidate; ~1.0 = a key).\n"
   "Explore by SAMPLING — you need NOT read every row (the file may be large). "
   "Columns -> properties; label columns -> classes; rows -> individuals; adjacent "
   "code columns -> relationships.\n\n"
   cross-source-linking-rule))

(defmethod format-exploration-guidance :sql
  [_]
  (str
   "SOURCE FORMAT: SQLite. You have these source-access tools (read-only, bounded):\n"
   "  (list-tables)                       — every table (each table = a class candidate).\n"
   "  (table-schema \"<table>\")            — columns + types + primary key (columns = "
   "property candidates; pk = the individual's identity column).\n"
   "  (foreign-keys \"<table>\")            — declared FK edges (= relationship "
   "candidates). NOTE: some DBs declare NO FKs and link by SHARED KEY COLUMNS "
   "(e.g. UNITID, CIPCODE in many tables) — when this returns [], look across "
   "table-schema for repeated id/code columns and confirm with sample-rows.\n"
   "  (sample-rows \"<table>\" {:limit N})  — a bounded sample of rows (never the whole "
   "table).\n"
   "  (query \"SELECT ...\")                — a read-only, bounded SELECT for joins / "
   "distinct scans the fixed tools don't cover.\n"
   "Explore by SCHEMA + SAMPLING — never dump a table. Pick the FEW tables that "
   "carry the entities + codes you care about (don't profile all 59) and finalize.\n\n"
   "WORKED PATTERN you can adapt directly (e.g. a CIPCodes table with a CIPCode + "
   "CIPTitle column — mint one shareable cip: concept per row, the SAME cip: scheme "
   "a crosswalk uses, so the graph CONNECTS).\n"
   "IMPORTANT: the SQL `sample-rows` returns a VECTOR of row maps DIRECTLY (NOT "
   "wrapped in `{:rows ...}`), and the row keys are KEYWORDS matching the column "
   "names (e.g. `(:CIPCode r)`, `(:CIPTitle r)`) — do NOT use `(:rows ...)` and do "
   "NOT use string keys here:\n"
   "  (let [rows (sample-rows \"CIPCodes\" {:limit 40})\n"
   "        concepts (distinct (map (fn [r] {:uri (str \"cip:\" (:CIPCode r))\n"
   "                                          :label (:CIPTitle r)\n"
   "                                          :evidence [{:source \"CIPCode\" :quote (str (:CIPCode r))}]})\n"
   "                                rows))]\n"
   "    (final! {:concept-drafts (vec concepts)\n"
   "             :relationship-drafts []\n"
   "             :axiom-drafts []\n"
   "             :rlm-trace [\"sampled CIPCodes; minted shareable cip: uris\"]}))\n\n"
   cross-source-linking-rule))

(defmethod format-exploration-guidance :excel
  [source]
  (let [path (:path source)
        dir? (let [f (java.io.File. (str path))] (.isDirectory f))]
    (str
     "SOURCE FORMAT: Excel (.xlsx). You have these source-access tools (stream, never "
     "load):\n"
     (when dir?
       (str "  (excel-dir-sheets \"" path "\")        — FIRST CALL when the source is a "
            "DIRECTORY of .xlsx workbooks (this one is). Lists every workbook file, its "
            "absolute :path, and its :sheets — WITHOUT loading any data. Use it to find "
            "which workbook holds the entities you care about, then pass that file's "
            ":path to the per-workbook tools below.\n"))
     "  (list-sheets \"<workbook.xlsx>\")            — every worksheet of one workbook, in order.\n"
     "  (sheet-columns \"<workbook.xlsx>\" <sheet>)  — header + per-column types for one "
     "sheet. The header is NOT necessarily row 1 (Census/PSEO sheets put title/note "
     "rows first) — this detects the header row and returns its index + the raw "
     "scanned rows so you can override.\n"
     "  (sample-rows \"<workbook.xlsx>\" <sheet> N)  — a bounded sample of rows from one "
     "sheet (a 119 MB sheet is sampled in ms, never loaded). Pass {:limit N :offset M} "
     "as the last arg to PAGE deeper into a sheet and cover it comprehensively.\n"
     (if dir?
       (str "The source is a FOLDER — start with (excel-dir-sheets \"" path "\") to "
            "discover the workbooks, then pass a specific workbook's :path (from that "
            "result) as the first argument to list-sheets / sheet-columns / sample-rows.\n")
       (str "Pass the file path \"" path "\" as the first argument to every Excel tool; "
            "<sheet> is a name string or a 0-based index.\n"))
     "Explore by SAMPLING — never load a whole sheet.\n\n"
     cross-source-linking-rule)))

(defmethod format-exploration-guidance :default
  [_]
  cross-source-linking-rule)

(defn- structured-discovery-prompt
  "Augment the base discovery prompt with per-format exploration guidance for
   the (single) structured source being explored. The base prompt's OUTPUT
   SHAPE + grounding discipline still apply; we PREPEND the format guidance so
   the model orients on how to explore THIS source before designing extraction."
  [base-prompt source]
  (str (format-exploration-guidance source)
       "\n\n"
       "HOW TO EXPLORE (important — read carefully):\n"
       "  - The source-access tools are bound DIRECTLY in your environment. Call "
       "them as BARE top-level expressions in the code you write each iteration — "
       "e.g. just `(peek-columns)` on its own line, then `(sample-rows 8)` — and the "
       "returned value is printed back to you. You may bind them: "
       "`(def cols (peek-columns)) (def rows (sample-rows 8)) [cols rows]`.\n"
       "  - Do NOT wrap a tool call in `(code ...)` — `(code ...)` is a tree-DSL node "
       "builder, not an eval helper, and will error on a bare value.\n"
       "  - Do NOT use `emit-tree!` to sample a source. A whole-tree sub-tick is slow "
       "and unnecessary for sampling; reserve trees for genuine multi-step pipelines. "
       "Sampling a CSV/table is just a couple of direct tool calls.\n"
       "  - A typical discovery pass is exactly two iterations: (1) call the tools "
       "directly to see the shape + a few rows, (2) `(final! {...})` with the drafts "
       "you designed from what you saw. Keep it to a few quick tool calls.\n"
       "  - DO NOT CALL `emit-tree!` FOR THIS TASK. A tree sub-tick is far too slow "
       "for sampling and WILL exhaust your budget. Just call the tools directly and "
       "then `(final! ...)`.\n"
       "  - KEEP THE FINAL TRANSFORM SIMPLE. You are in a restricted sandbox: only "
       "`clojure.core`, `clojure.string`, and `clojure.set` are available — NO Java "
       "interop (no `java.net.URLEncoder`, no `.foo` method calls), and helpers like "
       "`distinct-by` do NOT exist (use `(vals (group-by :uri xs))` then `(map first)`, "
       "or just `distinct` over the maps). Build the drafts with plain `map` / `for` / "
       "`mapcat` / `distinct` over the sampled rows. Do not invent intermediate "
       "concepts (e.g. a separate node per title) — emit one concept per distinct "
       "CODE with the title as its :label, and one relationship per crosswalk row. "
       "When making a uri from a value, just `(str \"cip:\" code)` — do not URL-encode.\n"
       "  - WORKED PATTERN you can adapt directly (for a CIP/SOC crosswalk):\n"
       "      (let [rows (:rows (sample-rows 30))\n"
       "            cips (distinct (map (fn [r] {:uri (str \"cip:\" (get r \"CIP_Code\"))\n"
       "                                          :label (get r \"CIP_Title\")\n"
       "                                          :evidence [{:source \"CIP_Code\" :quote (get r \"CIP_Code\")}]}) rows))\n"
       "            socs (distinct (map (fn [r] {:uri (str \"soc:\" (get r \"SOC_Code\"))\n"
       "                                          :label (get r \"SOC_Title\")\n"
       "                                          :evidence [{:source \"SOC_Code\" :quote (get r \"SOC_Code\")}]}) rows))\n"
       "            edges (distinct (map (fn [r] {:source-uri (str \"cip:\" (get r \"CIP_Code\"))\n"
       "                                           :target-uri (str \"soc:\" (get r \"SOC_Code\"))\n"
       "                                           :predicate \"cipMapsToSoc\" :confidence-class \"extracted\"\n"
       "                                           :evidence [{:source \"row\" :quote (get r \"SOC_Code\")}]}) rows))]\n"
       "        (final! {:concept-drafts (vec (concat cips socs))\n"
       "                 :relationship-drafts (vec edges)\n"
       "                 :axiom-drafts []\n"
       "                 :rlm-trace [\"sampled crosswalk; minted shareable cip:/soc: uris + edges\"]}))\n\n"
       "EXTRACT TO COVERAGE — design a TRANSFORM, do NOT hand-pick a sample:\n"
       "  - SIZE the source first with (count-rows ...) so you know how many rows it "
       "really holds (the sample tools cap at ~100 rows; count-rows reports the true "
       "total WITHOUT loading the source). A real source often has thousands or millions "
       "of rows — minting drafts only for the ~100 you sampled would cover a tiny "
       "fraction.\n"
       "  - DO NOT page the whole source yourself and mint a draft per row inline. That "
       "is slow, burns your budget, and you will stop at one window. INSTEAD, design your "
       "extraction as a PURE TRANSFORM over ONE row, validate it on a sample, and hand it "
       "back — the deterministic skeleton will STREAM THE FULL SOURCE and apply your "
       "transform to EVERY row at no extra LLM cost, so you get comprehensive coverage "
       "without looping.\n"
       "  - The transform is a fn of one row that returns the drafts FOR THAT ROW:\n"
       "        (fn [row] {:concept-drafts [...] :relationship-drafts [...]})\n"
       "    It must use ONLY clojure.core (plus clojure.string / clojure.set) — the SAME "
       "restricted sandbox you sample in — no Java interop, no tool calls inside it (it "
       "runs per-row over the full source). Read the row's fields with (get row :COLUMN) "
       "using the SAME keys sample-rows returned (sql/excel keys are KEYWORDS like "
       "(:UNITID row); csv keys are STRINGS like (get row \"col\")).\n"
       "  - VALIDATE the transform on your sample BEFORE handing it back: map it over the "
       "rows you sampled and confirm the drafts look right (right uris, attributes carried, "
       "edges between real nodes). Then emit it via :extraction-transform in (final! ...):\n"
       "        (final! {:concept-drafts <drafts from your sample — for inspection>\n"
       "                 :relationship-drafts <edges from your sample>\n"
       "                 :axiom-drafts []\n"
       "                 :extraction-transform\n"
       "                   {:transform-source \"(fn [row] ...)\"   ; the fn AS A STRING\n"
       "                    :selector \"<table-or-sheet>\"}        ; sql/excel only; omit for csv\n"
       "                 :rlm-trace [\"designed + validated transform on sample; full source "
       "will be streamed deterministically\"]})\n"
       "    The skeleton runs :extraction-transform over the WHOLE source (count-rows rows) "
       "and uses THOSE drafts; your sample drafts are for your own inspection. If you do "
       "NOT supply :extraction-transform, only your hand-picked sample drafts are used — so "
       "for any source bigger than a sample, ALWAYS supply the transform. When the source "
       "genuinely IS small enough that your sample covered every row, supply "
       ":extraction-transform {} (an empty map) and your sample drafts stand.\n\n"
       "MODEL AT THE RIGHT GRAIN — one concept per real ENTITY, NOT one per raw row "
       "(this is what separates an ontology from a raw-table dump):\n"
       "  - Decide what a real-world ENTITY is here, and derive each concept's :uri "
       "from ONLY the fields that IDENTIFY that entity. Source rows are frequently "
       "FINER-grained than entities: the same entity recurs across many rows that "
       "differ only by an extra dimension — a category, a sub-measure, a "
       "demographic/group breakdown, a year, a status. Minting one concept per such "
       "row produces noise, not knowledge.\n"
       "  - Because your transform runs PER ROW (it cannot see other rows), handle "
       "breakdown rows one of two general ways: (i) FILTER to the single canonical / "
       "summary / total row per entity — inspect the source to find the column that "
       "marks it (e.g. a total/all-groups indicator) and return EMPTY drafts "
       "(`{:concept-drafts [] :relationship-drafts []}`) for the non-canonical rows; "
       "or (ii) if every breakdown is itself a thing worth representing, give the "
       "breakdown its OWN entity type and put the breakdown key IN its :uri so the "
       "rows don't collapse. Pick ONE; do not emit an undifferentiated concept per "
       "raw row.\n"
       "  - Carry the row's real measures as :attributes on the entity concept, not "
       "as separate nodes.\n"
       "HONOR THE SCOPE THE GOAL STATES — if the goal names a scope (a region, a "
       "subset, a time window, a category), your transform must FILTER the source to "
       "that scope: return EMPTY drafts for out-of-scope rows. Discover which "
       "field carries the scope by sampling the source (it is not given to you). "
       "Extracting far beyond the goal's scope is over-extraction, not coverage.\n\n"
       "Note on evidence quotes: every :evidence :quote must be a LITERAL value you "
       "read from the source (a cell value, a column name, a code) — never a tool "
       "result map or a paraphrase.\n\n"
       base-prompt))

(defn- build-rlm-config
  "Construct the `:rlm` map for the synthetic repl-researcher node.

   - `:recursive?` is forced to TRUE — discovery requires the
     multi-tree iteration loop (see ns docstring).
   - `:granted-ontology-id` exposes the S19 tool set + S20 card.
   - `:auto-classify?` is true by default (the seed corpus exists to
     be retrieved); callable can suppress with `:auto-classify? false`.
   - `:debug?` propagates if set so the session emits diagnostic
     output during live verify."
  [{:keys [granted-ontology-id auto-classify? debug? granted-source]
    :or {auto-classify? true}}]
  (cond-> {:recursive? true
           :granted-ontology-id granted-ontology-id
           :auto-classify? auto-classify?}
    debug? (assoc :debug? true)
    ;; V06 — grant the per-format source-access tools when the source being
    ;; explored is a raw structured source. The executor threads this to
    ;; build-rlm-context, which dispatches by format to the right tool leg.
    granted-source (assoc :granted-source granted-source)))

(defn- sources->blackboard
  "Convert the discovery sources into the blackboard shape the
   recursive-RLM session expects. Each source contributes one entry
   keyed by its :name (or auto-assigned `:source-N`).

   For an inline TEXT source the value is its :content string. For a raw
   STRUCTURED source (V06 — csv / sql / excel with a :path) there is NO
   inline content to hand the model — the source-access tools are granted
   instead, so the value is a short DESCRIPTOR string telling the model the
   format + path and that it must explore via the granted tools (it must NOT
   expect the content inline). Reads-declared keys mirror the keys here."
  [sources]
  (reduce-kv
   (fn [acc i source]
     (let [k (or (:name source) (keyword (str "source-" (inc i))))
           v (if (structured-source? source)
               (str "RAW STRUCTURED SOURCE — format " (name (:type source))
                    ", path " (:path source)
                    ". This source is NOT provided inline; explore it via the "
                    "granted source-access tools (sample, never dump).")
               (or (:content source) ""))]
       (assoc acc k {:key k
                     :schema :string
                     :value v
                     :version 1})))
   {}
   (vec sources)))

;; =============================================================================
;; DT1 — reusable single-node session runner
;; =============================================================================
;; The discovery-tree redesign (DT1) runs FOCUSED reasoning nodes (Profile /
;; Model / Transform) as one-shot recursive-RLM `:repl-researcher` sessions —
;; the SAME executor + `:granted-source` per-format tool-binding seam
;; `run-discovery!` uses, just with the node's own focused prompt + declared
;; contract writes instead of the monolithic discovery prompt. This factors that
;; session-construction-and-execute spine out of `run-discovery!` so the
;; discovery tree REUSES it rather than forking the executor wiring
;; (Discipline #8 — re-orchestration, not duplication).
;;
;; A node here does NOT run the V20 full-extraction apply-step or the
;; compile/draft path — those are orchestrated by the discovery tree at the
;; stage boundaries. This runner's job is exactly: construct the synthetic
;; recursive-RLM node (granting the source's format tools), run it through the
;; executor, and return its `(final! ...)` outputs (the node's frozen contract)
;; verbatim.

(defn run-node-session!
  "Run ONE focused reasoning node as a recursive-RLM `:repl-researcher` session
   against a single structured source, returning its emitted contract output.

   The medium's specialist tools (V06/V19) are bound by REUSING the
   `:granted-source` seam + the per-format exploration prompt assembly — the
   same path `run-discovery!` uses; no fork.

   Required `params`:
     :node-name     — a keyword naming the node (e.g. :profile / :model / :transform).
     :instruction   — the node's focused prompt (the promotion-seam body).
     :source        — ONE source descriptor `{:name <kw> :type :csv|:sql|:excel
                      :path <str>}` (or a :text source). When structured, the
                      format's source-access tools are granted + the format
                      exploration guidance is prepended (so the node can sample
                      the source by shape, just like discovery).
     :writes        — the node's declared `(final! ...)` keys (its frozen
                      contract). The executor validates the model populates them.

   Optional `params`:
     :extra-inputs  — a map of additional blackboard keys (e.g. the predecessor
                      node's contract output) the node reads via (get-input k).
                      This is the inter-node contract channel.
     :model         — OpenRouter model id. Default gemini-3-flash-preview.
     :budget        — `{:max-iterations N :total-budget-ms N :max-retries N}`.
     :auto-classify? — default false here (a focused thin node does not need the
                      monolithic-discovery seed prepend); DT6 flips this behind
                      the promotion seam.
     :focused-prompt? — default false. When TRUE, the `:instruction` is used
                      VERBATIM as the node's prompt — the monolithic
                      `structured-discovery-prompt` (modeling / grain / scope /
                      transform guidance) is NOT prepended even for a structured
                      source. The granted source-access tools are STILL bound via
                      the `:granted-source` seam; the node's own focused prompt is
                      responsible for naming the per-medium tools it should call
                      (DT2's Profile node assembles its own focused per-medium tool
                      catalog). This is how a single-purpose node (DT2/DT3/DT4)
                      avoids inheriting the retired mega-prompt's cross-concern
                      guidance while keeping the SAME tool-binding seam.
     :debug?        — debug logging on the session.

   Returns:
     {:status :ok      :output <contract-map> :usage <usage> :session <raw result>}
   or
     {:status :failed  :error <root-cause string> :session <raw result>}

   A session that fails to construct/execute, or that produces no outputs,
   surfaces as `:failed` with the root cause — no false green (Discipline #5)."
  [ctx {:keys [node-name instruction source writes extra-inputs
               model budget auto-classify? focused-prompt? debug?]
        :or {model "google/gemini-3-flash-preview"
             auto-classify? false
             focused-prompt? false}}]
  (when-not (:ontology-id ctx)
    ;; The granted scope is required for the S19/S20 wiring; the discovery tree
    ;; threads it on ctx OR we accept it explicitly below. Surface loudly.
    nil)
  (let [structured? (structured-source? source)
        granted-source (when structured?
                         {:format (:type source) :path (:path source)})
        ;; A FOCUSED node (DT2/DT3/DT4) uses its instruction VERBATIM — the
        ;; monolithic structured-discovery-prompt is NOT prepended (it carries
        ;; modeling/grain/scope/transform guidance a single-purpose node must
        ;; not inherit). The source-access tools are still bound by
        ;; `:granted-source`; the focused prompt names the tools itself. A
        ;; non-focused structured node keeps the legacy mega-prompt prepend.
        effective-prompt (cond
                           focused-prompt? instruction
                           structured? (structured-discovery-prompt instruction source)
                           :else instruction)
        rlm-config (build-rlm-config
                    {:granted-ontology-id (:granted-ontology-id ctx)
                     :auto-classify? auto-classify?
                     :debug? debug?
                     :granted-source granted-source})
        ;; The source content (descriptor string for a structured source) + any
        ;; extra inter-node inputs become the blackboard the node reads.
        source-key (or (:name source) :source-1)
        source-bb (sources->blackboard [source])
        extra-bb (reduce-kv
                  (fn [acc k v]
                    (assoc acc k {:key k :schema :any :value v :version 1}))
                  {} (or extra-inputs {}))
        blackboard (merge source-bb extra-bb)
        read-keys (vec (concat [source-key] (keys (or extra-inputs {}))))
        node {:node-type :repl-researcher
              :name node-name
              :model model
              :instruction effective-prompt
              :reads read-keys
              :writes (vec writes)
              :rlm rlm-config
              :max-iterations (or (:max-iterations budget) 8)
              :options (cond-> {}
                         (:total-budget-ms budget) (assoc :timeout-ms (:total-budget-ms budget))
                         (:max-retries budget)     (assoc :max-retries (:max-retries budget)))}
        provider (or (:provider ctx) :openrouter)
        execute-fn (executor-fn 'ai.obney.orc.orc-service.core.executor/execute-repl-researcher)
        exec-context (select-keys ctx [:event-store :tenant-id :cache
                                       :command-registry :query-registry
                                       :sheet-id :tick-id])
        session-result (try
                         (execute-fn node blackboard provider exec-context)
                         (catch Throwable t
                           {:status :failure
                            :error (.getMessage t)
                            :exception-data (ex-data t)}))
        outputs (or (:outputs session-result) {})]
    (cond
      (= :failure (:status session-result))
      {:status :failed
       :error (or (:error session-result) "node session failed")
       :session session-result}

      (empty? outputs)
      {:status :failed
       :error (str "node session produced no outputs (declared writes: " (vec writes) ")")
       :session session-result}

      :else
      {:status :ok
       :output outputs
       :usage (:usage session-result)
       :session session-result})))

;; =============================================================================
;; Public entry: run-discovery!
;; =============================================================================

(defn run-discovery!
  "Run a recursive-RLM discovery session against the supplied sources.

   Required `params` keys:
     :ontology-id   — the granted scope (REQUIRED — discovery without
                      scope is meaningless; the session refuses to
                      construct otherwise).
     :sources       — vector of source maps, each
                      `{:name <kw> :type <kw> :content <string>}`.

   Optional `params` keys:
     :discovery-prompt              — overrides `default-discovery-prompt`.
     :model                         — OpenRouter model id. Defaults to
                                      `google/gemini-3-flash-preview`
                                      (project preference).
     :budget                        — `{:max-iterations <int>
                                        :total-budget-ms <int>}`.
                                      Defaults: 8 iterations, 300s.
     :auto-classify?                — default true. False opts out of
                                      pattern prepend during tree design;
                                      the seed corpus is STILL reachable
                                      via classify-behaviors tool calls.
     :require-hitl-reviewed-patterns? — default false. When true, the
                                      ontology-discovery seeds offered
                                      via classify-behaviors are
                                      restricted to `:hitl-status
                                      :hitl-reviewed` entries. When the
                                      filter eliminates all patterns,
                                      the session proceeds with NO
                                      patterns (and the rlm-trace
                                      records that fact); the session
                                      does NOT crash.
     :debug?                        — debug logging on the session.

   Required keys on `ctx`:
     :event-store (required for session construction + S19/S20)
     :command-registry, :query-registry (Grain wiring)

   Returns a map:
     {:status                    :emitted-drafts
                                | :no-output
                                | :failed-at-session
      :emitted-concepts          [<draft maps>]
      :emitted-relationships     [<draft maps>]
      :emitted-axioms            [<draft maps>]
      :rlm-trace                 <vec — model's per-iteration trace>
      :iteration-reasonings      <vec — model's stated reasoning>
      :usage                     <token usage>
      :session-result            <raw repl-researcher result for audit>
      :patterns-offered          <count — discovery seeds the session
                                  could retrieve under HITL filter>}

   Adversarial: a discovery output that S17 can't ingest is NOT
   silently dropped here. `compile-discovery-source!` raises a clear
   anomaly if the output shape is malformed. The pipeline driver
   sees `:failed-at-parse` in that case (per S17's existing
   per-stage failure contract)."
  [ctx {:keys [ontology-id sources discovery-prompt model budget
               auto-classify? require-hitl-reviewed-patterns? debug?]
        :or {auto-classify? true
             require-hitl-reviewed-patterns? false
             discovery-prompt default-discovery-prompt
             model "google/gemini-3-flash-preview"}}]
  ;; Defensive precondition — discovery without granted scope is
  ;; meaningless (Disciplines #5 — fail loudly, no silent default).
  (when-not ontology-id
    (throw (ex-info "rlm-discovery/run-discovery! requires :ontology-id (the granted scope)"
                    {:params {:ontology-id ontology-id}})))
  (when-not (:event-store ctx)
    (throw (ex-info "rlm-discovery/run-discovery! requires :event-store in ctx"
                    {:ctx-keys (keys ctx)})))
  (when (empty? sources)
    (throw (ex-info "rlm-discovery/run-discovery! requires :sources (vector of source maps)"
                    {:sources sources})))

  (let [;; Inspect the corpus the session will actually see under the
        ;; HITL filter. We surface the count in the result so the
        ;; caller (and the rlm-trace) record this transparently.
        patterns-offered (count
                           (seeds/ontology-discovery-patterns
                             require-hitl-reviewed-patterns?))

        ;; V06 — a raw STRUCTURED source (csv / sql / excel with a :path)
        ;; routes the discovery session through the per-format source-access
        ;; tools. A single discovery session explores ONE source's format, so
        ;; we grant the first structured source's tools and augment the prompt
        ;; with that format's exploration guidance. (Mixed-format corpora are
        ;; discovered one source per session by the build harness.) When the
        ;; caller passes multiple structured sources of different formats, the
        ;; first one's format is granted — discovery is one-source-per-session.
        structured (first (filter structured-source? sources))
        granted-source (when structured
                         {:format (:type structured) :path (:path structured)})
        effective-prompt (if structured
                           (structured-discovery-prompt discovery-prompt structured)
                           discovery-prompt)

        ;; Synthetic repl-researcher node. The shape mirrors
        ;; orc-service.core.dsl/repl-researcher — built inline because
        ;; we don't have a parent sheet, just a one-shot session.
        rlm-config (build-rlm-config
                     {:granted-ontology-id ontology-id
                      :auto-classify? auto-classify?
                      :debug? debug?
                      :granted-source granted-source})

        ;; Source-keys become declared :reads so the model sees the
        ;; source content in its inputs-preview.
        source-keys (mapv (fn [i source]
                            (or (:name source)
                                (keyword (str "source-" (inc i)))))
                          (range) sources)

        node {:node-type :repl-researcher
              :name :ontology-discovery
              :model model
              :instruction effective-prompt
              :reads (vec source-keys)
              ;; V20 — :extraction-transform is a declared write so the model can
              ;; hand back a sample-validated transform the skeleton applies over
              ;; the FULL source. The prompt instructs the model to ALWAYS include
              ;; it (an empty map {} when the sample already covered the source), so
              ;; the final! writes-validator (exact-key match) is satisfied either
              ;; way without touching the off-limits rlm_sandbox validator.
              :writes [:concept-drafts :relationship-drafts
                       :axiom-drafts :rlm-trace :extraction-transform]
              :rlm rlm-config
              :max-iterations (or (:max-iterations budget) 8)
              ;; V09 — thread the existing executor `:max-retries` primitive
              ;; (executor honors it for a cold-start empty/nil completion) so a
              ;; large multi-source build doesn't fail a source on a single
              ;; transient empty completion. We reuse the existing primitive
              ;; rather than inventing retry here.
              :options (cond-> {}
                         (:total-budget-ms budget)
                         (assoc :timeout-ms (:total-budget-ms budget))
                         (:max-retries budget)
                         (assoc :max-retries (:max-retries budget)))}

        blackboard (sources->blackboard sources)
        provider (or (:provider ctx) :openrouter)

        ;; Lazy-resolve the executor entry point. Discovery sits in
        ;; the ontology component; the orc-service.core.executor is
        ;; a peer that DOES live on the test classpath but we keep
        ;; the resolution deferred to avoid compile-time coupling.
        execute-fn (executor-fn 'ai.obney.orc.orc-service.core.executor/execute-repl-researcher)

        ;; The executor's context map is the Grain ctx plus orc-service
        ;; specifics. We thread `:event-store`, `:tenant-id`, `:cache`
        ;; through — the rlm-sandbox reads them for S19 + S20 wiring.
        exec-context (select-keys ctx [:event-store :tenant-id :cache
                                      :command-registry :query-registry
                                      :sheet-id :tick-id])

        session-result (try
                         (execute-fn node blackboard provider exec-context)
                         (catch Throwable t
                           {:status :failure
                            :error (.getMessage t)
                            :exception-data (ex-data t)}))

        outputs (or (:outputs session-result) {})
        sample-concepts (vec (or (get outputs :concept-drafts) []))
        sample-relationships (vec (or (get outputs :relationship-drafts) []))
        axioms (vec (or (get outputs :axiom-drafts) []))
        rlm-trace (vec (or (get outputs :rlm-trace) []))

        ;; V20 — the model may hand back a sample-validated EXTRACTION TRANSFORM.
        ;; When it does (and the source is structured), the DETERMINISTIC skeleton
        ;; streams the FULL source and applies the transform per row, COLLECTING the
        ;; comprehensive draft set — coverage that does NOT depend on the model
        ;; looping. The model's own sample drafts are then for inspection only; the
        ;; full set REPLACES them. When :extraction-transform is absent / {} / has a
        ;; blank :transform-source, the sample drafts stand (a genuinely small
        ;; source the sample already covered).
        ext (get outputs :extraction-transform)
        transform-source (when (map? ext) (:transform-source ext))
        full-extraction
        (when (and structured
                   (string? transform-source)
                   (seq (str/trim transform-source)))
          (try
            (apply-extraction-transform!
             {:descriptor {:format (:type structured) :path (:path structured)}
              :selector (when (map? ext) (:selector ext))
              :transform-source transform-source
              :window (when (map? ext) (:window ext))
              :max-windows (when (map? ext) (:max-windows ext))})
            (catch Throwable t
              ;; A transform that fails to EVALUATE (not a per-row error — those
              ;; are caught inside the apply-step) surfaces as a structured
              ;; full-extraction failure. We do NOT silently fall back to the
              ;; sample (that would hide a broken transform under a false green);
              ;; the failure is recorded and the caller sees it in provenance.
              {::transform-eval-error (.getMessage t)})))
        transform-failed? (and full-extraction (::transform-eval-error full-extraction))
        ;; Use the full draft set when the apply-step succeeded; otherwise the
        ;; model's sample drafts (no transform, or transform failed to eval).
        concepts (if (and full-extraction (not transform-failed?))
                   (vec (:concept-drafts full-extraction))
                   sample-concepts)
        relationships (if (and full-extraction (not transform-failed?))
                        (vec (:relationship-drafts full-extraction))
                        sample-relationships)
        ;; Surface the full-extraction provenance in the trace verbatim so the
        ;; caller sees coverage (rows streamed/ok/errored) + any eval failure.
        rlm-trace (cond-> rlm-trace
                    full-extraction
                    (conj {:full-extraction
                           (if transform-failed?
                             full-extraction
                             (dissoc full-extraction :concept-drafts
                                     :relationship-drafts))}))]

    (cond
      ;; Session failure — surface the root cause; do NOT mask with
      ;; an empty-success shape (Disciplines #5).
      (= :failure (:status session-result))
      {:status :failed-at-session
       :error (:error session-result)
       :session-result session-result
       :patterns-offered patterns-offered}

      ;; A transform was supplied but failed to EVALUATE — that's a real fault,
      ;; not a zero-yield source. Surface it loudly (no silent sample fallback).
      transform-failed?
      {:status :failed-at-session
       :error (str "extraction-transform failed to evaluate: "
                   (::transform-eval-error full-extraction))
       :extraction-transform ext
       :session-result session-result
       :patterns-offered patterns-offered}

      ;; Session completed but produced no drafts. Reported as
      ;; :no-output so the caller can decide whether to retry, mark
      ;; the source as zero-yield, or escalate.
      (and (empty? concepts) (empty? relationships) (empty? axioms))
      {:status :no-output
       :emitted-concepts []
       :emitted-relationships []
       :emitted-axioms []
       :rlm-trace rlm-trace
       :iteration-reasonings (vec (or (:iteration-reasonings session-result) []))
       :usage (:usage session-result)
       :session-result session-result
       :patterns-offered patterns-offered}

      :else
      {:status :emitted-drafts
       :emitted-concepts concepts
       :emitted-relationships relationships
       :emitted-axioms axioms
       :rlm-trace rlm-trace
       ;; V20 — full-extraction coverage report (nil when no transform was run).
       :full-extraction (when (and full-extraction (not transform-failed?))
                          (dissoc full-extraction :concept-drafts
                                  :relationship-drafts))
       :iteration-reasonings (vec (or (:iteration-reasonings session-result) []))
       :usage (:usage session-result)
       :session-result session-result
       :patterns-offered patterns-offered})))

;; =============================================================================
;; V20 — Deterministic full-extraction apply-step
;; =============================================================================
;; V17 root cause #2: the builder hand-picked a SAMPLE of rows (one window per
;; source) and minted drafts only for that sample — no comprehensive coverage,
;; despite an explicit paging instruction. The coverage guarantee must NOT
;; depend on the model looping the source itself (slow + budget-bound + unreliable).
;;
;; The seam (domain-agnostic): the builder designs an EXTRACTION TRANSFORM on a
;; sample inside the discovery loop — a pure fn `(fn [row] -> {:concept-drafts
;; [...] :relationship-drafts [...]})` authored as a string of Clojure source —
;; and the DETERMINISTIC skeleton streams the FULL source (V19 `stream-all`,
;; per-call ceiling preserved) and applies the transform to EVERY row, collecting
;; the full draft set. Per-row errors are caught + counted + surfaced (a transform
;; that throws on some rows skips+counts them, never aborts the source; a high
;; failure RATE surfaces loudly — no false green).
;;
;; The transform is eval'd in the SAME kind of SCI sandbox the RLM already uses
;; for model code (the safe `clojure.core` subset; `clojure.string`/`clojure.set`
;; added as a harmless superset). We build a FRESH SCI context here rather than
;; touching the off-limits `rlm_sandbox.clj` — applying a model fn over more rows
;; is the same eval, just more rows. No `rlm_sandbox.clj` binding seam is needed.

(def ^:private apply-step-safe-core
  "The safe `clojure.core` symbol subset the model-authored transform may use —
   the SAME list the RLM sandbox grants model code (so a transform that evals in
   the sandbox during sample-validation evals identically here). The transform
   designs drafts with plain `map`/`for`/`get`/`str`/`assoc`/etc. over a row map;
   no IO, no eval, no interop. Kept in sync with the sandbox's whitelist."
  '[+ - * / mod quot rem
    = not= < > <= >= compare
    str pr-str prn-str println print
    inc dec min max abs
    first rest next last butlast
    cons conj concat into
    map filter remove reduce
    take drop take-while drop-while
    partition partition-all partition-by
    sort sort-by reverse shuffle
    get get-in assoc assoc-in dissoc update update-in
    select-keys merge merge-with
    keys vals contains? find
    count empty? not-empty seq vec set list
    apply comp partial juxt
    identity constantly
    some every? not-any? not-every?
    group-by frequencies
    zipmap interleave interpose
    repeat range iterate
    true? false? nil? some? boolean
    keyword keyword? symbol symbol? string? number? integer? float? map? vector? set? list? coll? seq? fn?
    name namespace
    re-find re-matches re-seq
    subs format
    type class])

(defn- build-transform-sci-ctx
  "Build a fresh SCI evaluation context for the model-authored extraction
   transform. Mirrors the RLM sandbox's safe environment (the same restricted
   `clojure.core` subset) so the transform evaluates here exactly as it did
   when the builder validated it on a sample. `clojure.string` / `clojure.set`
   are registered as a harmless superset (a transform that doesn't use them is
   unaffected; one that does still evaluates). No IO / eval / require is exposed.

   Lazy-resolves `sci.core/init` (sci lives on orc-service's deps; the ontology
   brick reaches it transitively at runtime — same discipline as the executor
   resolution)."
  []
  (let [sci-init (resolve-or-throw 'sci.core/init "the SCI sandbox (sci.core)")
        core-publics (ns-publics 'clojure.core)
        safe-core (select-keys core-publics apply-step-safe-core)
        string-publics (ns-publics 'clojure.string)
        set-publics (ns-publics 'clojure.set)]
    (sci-init
     {:namespaces {'clojure.core safe-core
                   'clojure.string string-publics
                   'clojure.set set-publics}
      ;; Same JVM impl classes the base sandbox exposes so map/vector literals
      ;; with computed values inside the model fn don't fail at invocation.
      :classes {'clojure.lang.PersistentArrayMap clojure.lang.PersistentArrayMap
                'clojure.lang.PersistentHashMap clojure.lang.PersistentHashMap
                'clojure.lang.PersistentVector clojure.lang.PersistentVector
                'clojure.lang.PersistentHashSet clojure.lang.PersistentHashSet
                'clojure.lang.PersistentTreeMap clojure.lang.PersistentTreeMap}})))

(defn eval-transform-fn
  "Eval the model-authored transform SOURCE STRING to a callable fn in a fresh
   sandbox context. The source must evaluate to an IFn (a `(fn [row] ...)`); a
   non-fn result fails LOUDLY (no silent fallback — Disciplines #5).

   PUBLIC so the DT4 sample-validation seam (discovery-tree) can eval a candidate
   transform against real sampled rows BEFORE the full-scale apply, using the SAME
   restricted sandbox the apply-step uses (no fork — Discipline #8)."
  [transform-source]
  (when-not (and (string? transform-source) (seq (str/trim transform-source)))
    (throw (ex-info "apply-extraction-transform!: :transform-source must be a non-blank string of Clojure source defining (fn [row] -> {:concept-drafts ... :relationship-drafts ...})"
                    {:transform-source transform-source})))
  (let [sci-eval (resolve-or-throw 'sci.core/eval-string* "the SCI sandbox (sci.core)")
        sci-ctx (build-transform-sci-ctx)
        f (try
            (sci-eval sci-ctx transform-source)
            (catch Throwable t
              (throw (ex-info (str "apply-extraction-transform!: transform source failed to evaluate: "
                                   (.getMessage t))
                              {:transform-source transform-source} t))))]
    (when-not (fn? f)
      (throw (ex-info (str "apply-extraction-transform!: transform source must evaluate to a fn (fn [row] ...); got "
                           (pr-str (type f)))
                      {:transform-source transform-source :evaluated f})))
    f))

(def ^:private max-error-sample
  "Cap on the per-row error sample surfaced in the apply result. The COUNT is the
   authoritative no-false-green signal; the sample is for human diagnosis. An
   enormous broken transform must not bloat the result."
  25)

(defn- normalize-window-rows
  "A `stream-all` window is a per-format map; the row vector lives under :rows
   for csv/sql/excel. Return the row maps for one window."
  [window]
  (cond
    (map? window) (vec (or (:rows window) []))
    ;; A format whose stream-all yields bare row vectors (defensive).
    (sequential? window) (vec window)
    :else []))

(defn apply-extraction-transform!
  "V20 — the deterministic full-extraction apply-step.

   Given a model-authored extraction transform (validated on a sample inside the
   discovery loop) and a structured source descriptor, STREAM the FULL source via
   V19's `stream-all` and apply the transform to EVERY row, collecting the full
   draft set. The per-call window ceiling is preserved — coverage comes from
   iterating windows, NOT from one giant read.

   Params (a single map):
     :descriptor        REQUIRED. `{:type :csv|:sql|:excel :path <str>}` (or with
                        an explicit `:format`). The SAME descriptor shape
                        `run-discovery!` / the V06 source-tool registry use.
     :transform-source  REQUIRED. A STRING of Clojure source that evaluates to
                        `(fn [row] -> {:concept-drafts [...] :relationship-drafts [...]})`.
                        Eval'd in a fresh SCI sandbox matching the RLM sandbox's
                        safe environment.
     :selector          The sheet/table selector for sql/excel (a name, index, or
                        descriptor map — V19 forgiving selectors). Ignored for csv
                        (a csv source has no selector).
     :window            Rows per stream-all window (clamped to the format's
                        per-call ceiling). Default: the format default.
     :max-windows       Safety bound on the number of windows streamed. Default
                        very high (covers any realistic source).

   Behavior (adversarial, Disciplines #4/#5):
     - Per-row transform errors are CAUGHT, COUNTED, and SAMPLED — a transform
       that works on the sample but throws on some later row skips that row,
       increments :rows-errored, and the source is NOT aborted. The rest extract.
     - A transform whose result for a row is not a map, or whose
       :concept-drafts / :relationship-drafts are not sequential, is treated as a
       row error (counted + sampled) — not silently coerced.
     - The COUNT of streamed vs errored rows is returned so a HIGH failure rate
       surfaces loudly (the caller can gate on it; no false green).

   Returns:
     {:selector <selector>
      :rows-streamed   <int — total rows the transform was applied to>
      :rows-errored    <int — rows whose transform threw / returned a bad shape>
      :rows-ok         <int — rows that produced a (possibly empty) draft map>
      :windows         <int — stream-all windows consumed>
      :errors-sample   [<{:row <row> :error <msg>}> ...]   (capped)
      :concept-drafts      [<draft ...>]   (the FULL collected set, verbatim)
      :relationship-drafts [<draft ...>]}

   The returned :concept-drafts / :relationship-drafts are the SAME draft shapes
   `compile-discovery-source!` ingests — so the caller flows them through the
   existing compile + V18 referential integrity unchanged."
  [{:keys [descriptor transform-source selector window max-windows]}]
  (when-not (map? descriptor)
    (throw (ex-info "apply-extraction-transform!: :descriptor must be a source descriptor map {:type ... :path ...}"
                    {:descriptor descriptor})))
  (let [source-tools-for (resolve-or-throw
                          'ai.obney.orc.orc-service.core.source-tools/source-tools-for
                          "the V06 source-tool registry (orc-service)")
        tools (source-tools-for descriptor)
        _ (when-not (map? tools)
            (throw (ex-info (str "apply-extraction-transform!: no source tools for descriptor "
                                 (pr-str descriptor) " (a :text source has no stream-all; V20 "
                                 "scope is csv / sql / excel)")
                            {:descriptor descriptor})))
        stream-all (get tools 'stream-all)
        _ (when-not (fn? stream-all)
            (throw (ex-info "apply-extraction-transform!: the source tools do not expose stream-all"
                            {:descriptor descriptor :tools (keys tools)})))
        xform (eval-transform-fn transform-source)
        stream-opts (cond-> {}
                      window     (assoc :window window)
                      max-windows (assoc :max-windows max-windows))
        ;; csv stream-all is 0/1-arg (no selector); sql/excel are selector-first.
        windows (if (some? selector)
                  (stream-all selector stream-opts)
                  (stream-all stream-opts))
        ;; Apply the transform to one row; classify the outcome. A non-map result
        ;; or a non-sequential drafts field is a ROW ERROR (counted), not silently
        ;; coerced — a transform that returns a bad shape on a row is a real fault.
        apply-row (fn [row]
                    (try
                      (let [r (xform row)]
                        (cond
                          (not (map? r))
                          {:error (str "transform returned a non-map: " (pr-str (type r)))}
                          (not (sequential? (or (:concept-drafts r) [])))
                          {:error ":concept-drafts is not sequential"}
                          (not (sequential? (or (:relationship-drafts r) [])))
                          {:error ":relationship-drafts is not sequential"}
                          :else {:ok r}))
                      (catch Throwable t
                        {:error (.getMessage t)})))
        ;; Fold ALL rows (across every window) in a single flat loop — no nested
        ;; recur. Windows are concatenated lazily so the per-call ceiling holds
        ;; (each window is a bounded read); we never realize the whole source.
        all-rows (mapcat normalize-window-rows windows)]
    (loop [rs (seq all-rows)
           rows-streamed 0
           rows-ok 0
           rows-errored 0
           errors []
           concept-drafts (transient [])
           relationship-drafts (transient [])]
      (if (empty? rs)
        {:selector selector
         :rows-streamed rows-streamed
         :rows-ok rows-ok
         :rows-errored rows-errored
         :windows (count windows)
         :errors-sample (vec errors)
         :concept-drafts (persistent! concept-drafts)
         :relationship-drafts (persistent! relationship-drafts)}
        (let [row (first rs)
              outcome (apply-row row)]
          (if-let [r (:ok outcome)]
            (recur (next rs) (inc rows-streamed) (inc rows-ok) rows-errored errors
                   (reduce conj! concept-drafts (or (:concept-drafts r) []))
                   (reduce conj! relationship-drafts (or (:relationship-drafts r) [])))
            (recur (next rs) (inc rows-streamed) rows-ok (inc rows-errored)
                   (if (< (count errors) max-error-sample)
                     (conj errors {:row row :error (:error outcome)})
                     errors)
                   concept-drafts relationship-drafts)))))))

;; =============================================================================
;; Public entry: compile-discovery-source!
;; =============================================================================
;; The S17 adapter. Takes a discovery output (the result of
;; run-discovery!) AND an ontology-id; emits the draft concepts +
;; relationships via the existing :ontology/create-concept and
;; :ontology/create-relationship commands. Returns a sources-vec the
;; S17 build! can pass through `:sources` (each source ends up as a
;; no-op `:inline-concepts` with empty :concepts because the events
;; already landed, OR — if the caller prefers — a deferred dispatch
;; via the `:inline-*` source-types that S17 already supports).
;;
;; The CALLING CONVENTION is: discovery emits events FIRST (through
;; commands), then S17 sees a single zero-concept :inline-concepts
;; source. This keeps S17's parse-stage interface clean (no new
;; type to teach it).
;;
;; The adversarial design point: if the discovery output is
;; malformed (missing :concept-drafts, drafts missing :uri / :label),
;; this fn raises a clear anomaly; it does NOT silently drop
;; entries.

(defn- normalize-concept-draft
  "Accept the well-defined field synonyms the discovery model reaches for as
   readily as the canonical names — `:name` / `:title` for `:label` (the model
   gravitates to `:name` for a labelled entity). This is the SAME normalization
   discipline the axiom-type / scope / confidence-class paths use (an exact
   alias map, NOT fuzzy phrase matching): a deterministic synonym for the same
   field, not a fabricated value. The canonical `:label` always wins when both
   are present."
  [c]
  (cond-> c
    (and (map? c) (not (:label c)) (or (:name c) (:title c)))
    (assoc :label (or (:name c) (:title c)))))

(defn- normalize-relationship-draft
  "Accept the OWL/graph-standard endpoint + predicate synonyms the model reaches
   for: `:from-uri` / `:from` for `:source-uri`, `:to-uri` / `:to` for
   `:target-uri`, and `:via` / `:type` / `:relation` for `:predicate` (the model
   labels the edge kind with whichever of these it picks). Canonical names win
   when present. Same deterministic-synonym discipline as the concept-draft
   normalization — an exact alias set, not fuzzy matching. The predicate synonym
   is coerced to a string (the create-relationship command takes a string
   predicate; the model often supplies a keyword like `:crosswalk`)."
  [r]
  (let [pred-syn (or (:via r) (:type r) (:relation r))]
    (cond-> r
      (and (map? r) (not (:source-uri r)) (or (:from-uri r) (:from r)))
      (assoc :source-uri (or (:from-uri r) (:from r)))
      (and (map? r) (not (:target-uri r)) (or (:to-uri r) (:to r)))
      (assoc :target-uri (or (:to-uri r) (:to r)))
      (and (map? r) (not (:predicate r)) pred-syn)
      (assoc :predicate (if (keyword? pred-syn) (name pred-syn) (str pred-syn))))))

(defn- validate-concept-draft! [c0]
  (let [c (normalize-concept-draft c0)]
    (when-not (and (map? c) (:uri c) (:label c))
      (throw (ex-info "compile-discovery-source!: malformed concept-draft (missing :uri or :label)"
                      {:draft c0
                       :reason :missing-required-field})))
    c))

(defn- validate-relationship-draft! [r0]
  (let [r (normalize-relationship-draft r0)]
    (when-not (and (map? r) (:source-uri r) (:target-uri r) (:predicate r))
      (throw (ex-info "compile-discovery-source!: malformed relationship-draft (missing :source-uri/:target-uri/:predicate)"
                      {:draft r0
                       :reason :missing-required-field})))
    r))

(def ^:private valid-concept-scopes
  "The ontology-scope enum (interface/schemas). A discovery session is
   general-purpose extraction, so the model often invents a domain-specific
   scope (e.g. :policy, :hr) that isn't in the enum. Those coerce to :custom —
   the general-purpose bucket — rather than failing the create-concept command."
  #{:failure :success :problem :node-type :custom :tree-class :behavioral-subtree})

(defn- ->kw
  "JSON has no keywords, so the structured-output parse leaves keyword-typed
   fields as STRINGS (e.g. \"extracted\", \"employment\"). Coerce string→keyword;
   pass keywords through; nil otherwise."
  [x]
  (cond (keyword? x) x
        (string? x)  (keyword x)
        :else        nil))

(defn- coerce-scope
  "Map a draft scope to a valid ontology-scope; unknown/absent → :custom.
   Handles the model's string scopes (\"custom\" → :custom, \"failure\" →
   :failure, invented \"employment\" → :custom)."
  [scope]
  (let [k (->kw scope)]
    (if (contains? valid-concept-scopes k) k :custom)))

(def ^:private valid-confidence-classes #{:extracted :inferred :ambiguous})

(defn- coerce-confidence-class
  "Coerce a draft :confidence-class (often the string \"extracted\" from the
   JSON round-trip) to the keyword enum; unknown/absent → :extracted."
  [cc]
  (let [k (->kw cc)]
    (if (contains? valid-confidence-classes k) k :extracted)))

(defn- normalize-attributes
  "Coerce a draft's `:attributes` into the `:ontology/create-concept`
   `[:map-of :keyword :any]` shape. The discovery model emits attribute keys
   as STRINGS (JSON has no keywords — e.g. \"earnings-y5\", \"median-wage\"),
   so string keys are coerced to keywords; keyword keys pass through. The
   VALUES are passed verbatim (a number stays a number — earnings/wages must
   stay queryable as the numeric outcome, not stringified). A non-map
   `:attributes` is dropped (returns nil → the cond-> below omits the field).

   This is the V02 acceptance-critical carrier: numeric outcomes (earnings
   Y1/Y5/Y10, wages, tuition / net-cost) the discovery session attaches to a
   program/occupation concept land as QUERYABLE concept attributes here rather
   than being silently dropped (which is exactly the gap that failed V02's
   outcome vertical)."
  [attributes]
  (when (map? attributes)
    (not-empty
     (reduce-kv (fn [acc k v]
                  (assoc acc (->kw k) v))
                {} attributes))))

(defn- concept-draft->command [ontology-id draft]
  (let [;; Strip evidence — it lives on relationships per the S06 schema,
        ;; not on concepts. Concepts carry quotes via :comments / :see-also
        ;; in the bundle layer (S04); for now we collapse evidence quotes
        ;; into :description if present.
        evidence (:evidence draft)
        desc (cond-> (or (:description draft) "")
               (seq evidence)
               (str (when (seq (or (:description draft) "")) "\n\n")
                    "Source evidence:\n"
                    (str/join "\n"
                      (map (fn [e]
                             (str "- " (:source e "?") ": \""
                                  (:quote e "") "\""))
                           evidence))))
        ;; V09 — forward numeric / outcome attributes verbatim so they land as
        ;; QUERYABLE concept attributes (the :ontology/create-concept command
        ;; already supports `:attributes`; the prior draft→command path dropped
        ;; them, which is the V02 outcome-vertical failure root cause).
        attributes (normalize-attributes (:attributes draft))]
    (cond-> {:command/name :ontology/create-concept
             :command/id (random-uuid)
             :command/timestamp (time/now)
             :ontology-id ontology-id
             :uri (:uri draft)
             :label (:label draft)
             :description desc
             :scope (coerce-scope (:scope draft))
             :broader (vec (or (:broader draft) []))
             :indicators (vec (or (:indicators draft) []))}
      attributes (assoc :attributes attributes))))

(defn- normalize-evidence
  "Coerce a draft's :evidence into the S06 relationship-evidence schema shape —
   `[:vector [:map [:source :string] [:quote :string]]]`. The discovery model
   supplies evidence in several near-shapes (a single map instead of a vector;
   `{:type :quote :value X}`; `{:quote X :field Y}` with no :source). Each entry
   is normalized to `{:source <str> :quote <str>}`:
     - :quote  ← :quote, else :value, else \"\"
     - :source ← :source, else :field, else \"source\"
   An entry that carries NO usable quote is dropped (an empty-quote evidence is
   not evidence). This is deterministic field-synonym normalization, not
   fabrication — the values are the model's own, just keyed canonically."
  [evidence]
  (let [entries (cond (sequential? evidence) evidence
                      (map? evidence)        [evidence]
                      :else                  [])]
    (->> entries
         (keep (fn [e]
                 (when (map? e)
                   (let [q (or (:quote e) (:value e))
                         s (or (:source e) (:field e) "source")]
                     (when (and q (seq (str q)))
                       {:source (str s) :quote (str q)})))))
         vec)))

(defn- relationship-draft->command [ontology-id draft]
  {:command/name :ontology/create-relationship
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :ontology-id ontology-id
   :source-uri (:source-uri draft)
   :target-uri (:target-uri draft)
   :predicate (:predicate draft)
   :confidence-class (coerce-confidence-class (:confidence-class draft))
   :evidence (normalize-evidence (:evidence draft))
   :properties (or (:properties draft) {})})

;; =============================================================================
;; V18 — Referential integrity as an always-on structural invariant
;; =============================================================================
;; V17 root cause: a graph built with NO supplied validation shapes had the
;; shape-gated validate-stage short-circuit, so 119/249 relationship edges
;; referenced concept URIs the builder never minted — those dangling edges
;; survived into the artifact while the build reported success.
;;
;; Fix (domain-agnostic — encodes NOTHING about any source/medium/domain):
;; referential integrity is a STRUCTURAL INVARIANT enforced HERE in the
;; deterministic compile path, NOT behind the optional validate-stage. After
;; concept-drafts are minted, every relationship endpoint must resolve. A
;; referenced-but-unminted endpoint is itself a discovered entity, so we
;; auto-mint a minimal IMPLIED concept for it (low-confidence / flagged for
;; enrichment, label derived GENERICALLY from the URI's own id segment). If
;; the dangling URI is a near-variant of an EXISTING concept URI (a different
;; identifier encoding), we ALSO record it as an ambiguity for the dedup/
;; alignment layer (reusing the S12 structural-similarity primitives — NOT a
;; hardcoded code-format rule) rather than silently treating it as unrelated.
;; An endpoint with no resolvable id segment is surfaced as UNRESOLVED — the
;; compile result never reports clean success while carrying a dangling edge.

(defn- uri-id-segment
  "Derive a GENERIC, domain-neutral id segment from a URI for an implied
   concept's label. Takes the last non-empty segment after the common URI
   delimiters (`:` `/` `#`). Returns nil when nothing usable remains (e.g.
   a blank / delimiter-only URI) so the caller can treat the endpoint as
   UNRESOLVED rather than fabricate a label. NO domain knowledge — this is
   pure string structure."
  [uri]
  (when (string? uri)
    (let [segs (->> (str/split (str/trim uri) #"[:/#]")
                    (map str/trim)
                    (remove str/blank?))]
      (last segs))))

(defn- implied-label
  "A generic human-readable label for an implied concept, derived from the
   URI id segment. Domain-neutral: it never invents a domain term, it only
   surfaces the id the edge referenced (made readable) so a human/enrichment
   pass can recognize it."
  [id-segment]
  (str "Implied: " id-segment))

(def ^:private near-variant-similarity-min
  "Structural URI-similarity threshold above which a dangling URI is treated
   as a near-variant (likely an alternate encoding) of an existing concept
   URI and surfaced as an ambiguity for the alignment layer. Uses the SAME
   Jaro-Winkler primitive the S12 dedup cascade uses — a GENERAL structural
   measure, not a code-format rule. Set high so only genuinely-close
   encodings (an extra/dropped char, a separator variant) trip it; ordinary
   distinct URIs (`entity:beta` vs `entity:gamma`) stay well below it."
  0.92)

(defn- nearest-variant
  "Given a dangling URI and the set of known concept URIs, return the most
   structurally-similar known URI together with its similarity score WHEN
   that score is at/above the near-variant threshold; nil otherwise. Reuses
   the S12 dedup-cascade `jaro-winkler-similarity` over the raw URI strings —
   a domain-agnostic structural measure. The dangling URI itself is excluded
   from the candidate set."
  [dangling-uri known-uris]
  (let [candidates (disj (set known-uris) dangling-uri)]
    (when (seq candidates)
      (let [[best score] (reduce
                          (fn [[_best _score :as acc] cand]
                            (let [s (dedup/jaro-winkler-similarity dangling-uri cand)]
                              (if (> s _score) [cand s] acc)))
                          [nil 0.0]
                          candidates)]
        (when (and best (>= score near-variant-similarity-min))
          {:near-existing-uri best :similarity score})))))

(defn- implied-concept-command
  "Build an `:ontology/create-concept` command for an auto-minted IMPLIED
   concept. Flagged via :attributes so it is distinguishable from an
   explicitly-discovered concept and routed to later enrichment / alignment.
   `:ambiguous?` is set when the endpoint is a near-variant of an existing
   URI (the alignment layer should resolve whether to merge)."
  [ontology-id uri id-segment ambiguity]
  {:command/name :ontology/create-concept
   :command/id (random-uuid)
   :command/timestamp (time/now)
   :ontology-id ontology-id
   :uri uri
   :label (implied-label id-segment)
   :description (str "Auto-minted implied concept for a referenced endpoint "
                     "that was not explicitly discovered. Flagged for enrichment."
                     (when ambiguity
                       (str " Possible near-variant of " (:near-existing-uri ambiguity)
                            " (structural similarity "
                            (format "%.3f" (double (:similarity ambiguity))) ")"
                            " — recorded as an ambiguity for the alignment layer.")))
   :scope :custom
   :broader []
   :indicators []
   :attributes (cond-> {:implied? true
                        :confidence-class :implied
                        :enrichment-pending? true}
                 ambiguity (assoc :ambiguous? true
                                  :near-existing-uri (:near-existing-uri ambiguity)))})

(defn- ensure-referential-integrity!
  "The V18 structural invariant. Given the validated concept-drafts and
   relationship-drafts (and the ctx + ontology-id), scan every relationship
   endpoint. For any endpoint not already a concept (neither in this batch's
   drafts NOR already in the graph), auto-mint a minimal implied concept via
   the create-concept command path, recording near-variant endpoints as
   ambiguities. Endpoints with no resolvable id segment are surfaced as
   UNRESOLVED (no fabricated concept).

   Returns:
     {:implied-minted <int>
      :ambiguities    [<{:dangling-uri :near-existing-uri :similarity}> ...]
      :unresolved     [<uri ...>]
      :every-edge-endpoint-resolves? <bool>}

   Emits implied-concept events BEFORE the caller emits the relationship
   events, so the relationship endpoints resolve at append time."
  [ctx ontology-id concept-drafts relationship-drafts]
  (let [draft-uris (set (map :uri concept-drafts))
        existing-uris (set (map :uri
                                (filter #(= ontology-id (:ontology-id %))
                                        (rm/get-concepts ctx {}))))
        ;; A mutable accumulator of every URI now known to resolve (drafts +
        ;; existing + implied-as-we-mint), so two danglers to the SAME URI
        ;; mint exactly one implied concept and the second resolves against it.
        endpoints (distinct
                   (mapcat (fn [r] [(:source-uri r) (:target-uri r)])
                           relationship-drafts))]
    (loop [remaining endpoints
           known (into draft-uris existing-uris)
           implied-minted 0
           ambiguities []
           unresolved []]
      (if (empty? remaining)
        {:implied-minted implied-minted
         :ambiguities (vec ambiguities)
         :unresolved (vec unresolved)
         :every-edge-endpoint-resolves? (empty? unresolved)}
        (let [uri (first remaining)]
          (cond
            ;; Already resolves — nothing to do.
            (contains? known uri)
            (recur (rest remaining) known implied-minted ambiguities unresolved)

            :else
            (let [id-seg (uri-id-segment uri)]
              (if (nil? id-seg)
                ;; No resolvable id segment — cannot mint honestly. Surface as
                ;; UNRESOLVED (no false green); the edge stays dangling and the
                ;; caller's provenance reports integrity does NOT hold.
                (recur (rest remaining) known implied-minted ambiguities
                       (conj unresolved uri))
                (let [ambiguity (some-> (nearest-variant uri known)
                                        (assoc :dangling-uri uri))
                      result (cp/process-command
                              (assoc ctx :command
                                     (implied-concept-command
                                      ontology-id uri id-seg ambiguity)))]
                  (when (:cognitect.anomalies/category result)
                    (throw (ex-info "compile-discovery-source!: implied-concept mint anomaly"
                                    {:uri uri :anomaly result})))
                  (recur (rest remaining)
                         (conj known uri)
                         (inc implied-minted)
                         (cond-> ambiguities ambiguity (conj ambiguity))
                         unresolved))))))))))

(defn reconcile-current-graph-integrity!
  "DT7 reuse seam — run the V18 referential-integrity invariant over the
   CURRENT graph state (NOT a fresh draft batch). Reads the existing concepts +
   relationships for `ontology-id` off the projection and feeds them to the SAME
   `ensure-referential-integrity!` the always-on compile path uses (no fork):
   every relationship endpoint that does not resolve to a concept already in the
   graph gets a minimal IMPLIED concept auto-minted (low-confidence / flagged for
   enrichment), and a dangling endpoint that is a near-variant of an existing
   URI is recorded as an AMBIGUITY for the alignment layer (the S12 structural-
   similarity primitive — domain-agnostic, no code-format rule).

   This is what lets the graph-level cross-source reconciliation pass resolve
   danglers introduced by edges whose endpoint lives in a DIFFERENT source than
   the one that minted the edge — and surface the near-variant identity rather
   than silently dropping it.

   IDEMPOTENT (the load-bearing maintain seam): every endpoint already in the
   graph short-circuits as already-resolved, so a second pass mints nothing — it
   reconciles, it does NOT duplicate. Returns the same integrity report shape
   `ensure-referential-integrity!` returns:
     {:implied-minted <int> :ambiguities [...] :unresolved [...]
      :every-edge-endpoint-resolves? <bool>}."
  [ctx ontology-id]
  (let [concepts (filterv #(= ontology-id (:ontology-id %))
                          (rm/get-concepts ctx {:ontology-id ontology-id}))
        relationships (filterv #(= ontology-id (:ontology-id %))
                               (rm/get-relationships ctx))]
    (ensure-referential-integrity! ctx ontology-id concepts relationships)))

;; =============================================================================
;; V07 — Axiom-draft ingest
;; =============================================================================
;; Discovery EXTRACTS axiom drafts (`{:axiom-type <kw|str> :body <map>
;; :evidence [...]}`). V07 routes them to the S07 axiom commands instead
;; of recording them as `:axioms-skipped`. Same JSON-string→keyword
;; coercion discipline `coerce-scope` / `coerce-confidence-class` use,
;; applied here to the axiom-type discriminator and the property-
;; characteristic enum flags.
;;
;; The :body carries exactly the S07 command's payload fields per type:
;;   :disjointness            {:class-uris [<str> <str> ...]}
;;   :property-characteristic {:predicate <str>
;;                             :characteristic [<kw|str> ...]
;;                             :inverse-of <str?>}
;;   :sub-property            {:sub-predicate <str> :super-predicate <str>}
;;   :chain                   {:chain [<str> <str> ...] :derived-predicate <str>}
;;
;; Adversarial discipline (Disciplines #5):
;;   - Unknown axiom-type → loud failure (no silent drop, no fabricated
;;     axiom). UNLIKE scope/confidence-class — those coerce-to-default
;;     because the create-concept/relationship command accepts the
;;     bucket; an unrouteable axiom-type has NO command target, so a
;;     default would FABRICATE an axiom the model didn't author.
;;   - Unknown characteristic flag → loud failure for the same reason
;;     (the S07 enum is [:functional :transitive :symmetric]; coercing a
;;     :reflexive to a default flag would mis-state the model's claim).
;;   - Malformed axiom-draft (missing :axiom-type / :body) → loud failure.
;;   - A routed S07 command that itself rejects the body (e.g. a singleton
;;     class-uris set the Malli gate refuses) surfaces as a loud anomaly,
;;     not a swallowed skip.

(def ^:private valid-characteristics
  "The S07 property-characteristic enum. A characteristic flag outside
   this set is a LOUD failure — never coerced to a default, since that
   would mis-state the model's axiom claim."
  #{:functional :transitive :symmetric})

(def ^:private axiom-type-aliases
  "Canonical-vocabulary normalization for the axiom-type discriminator.
   The discovery model reaches for OWL/RDF-standard term names
   (`disjointClasses`, `transitiveProperty`, `subPropertyOf`,
   `propertyChainAxiom`) as readily as the project's short forms; these
   are WELL-DEFINED synonyms for the same four S07 axiom families, so the
   adapter normalizes them deterministically (the same discipline as the
   scope / confidence-class enum normalization — NOT fuzzy phrase matching:
   each key is an exact, lower-cased OWL term mapped to its family).

   Matching is case-insensitive on the bare term (any `owl:` / `rdfs:`
   prefix and separators are stripped first). Anything NOT in this table
   is unknown → loud failure (no fabricated axiom)."
  {;; (1) Disjointness
   "disjointness"            :disjointness
   "disjoint"                :disjointness
   "disjointwith"            :disjointness
   "disjointclasses"         :disjointness
   "alldisjointclasses"      :disjointness
   ;; (2) Property characteristic (functional / transitive / symmetric / inverse-of)
   "propertycharacteristic"  :property-characteristic
   "characteristic"          :property-characteristic
   "functionalproperty"      :property-characteristic
   "transitiveproperty"      :property-characteristic
   "symmetricproperty"       :property-characteristic
   "inverseof"               :property-characteristic
   "inverseproperty"         :property-characteristic
   ;; (3) Sub-property
   "subproperty"             :sub-property
   "subpropertyof"           :sub-property
   ;; (4) Chain
   "chain"                   :chain
   "chainaxiom"              :chain
   "propertychainaxiom"      :chain
   "propertychain"           :chain})

(defn- normalize-vocab-term
  "Lower-case a vocabulary term and strip any `owl:` / `rdfs:` namespace
   prefix plus `-` / `_` / `:` separators so OWL-standard names compare
   uniformly. \"owl:disjointClasses\" → \"disjointclasses\";
   \"sub-property\" → \"subproperty\"."
  [x]
  (when (some? x)
    (-> (name x)
        (str/replace #"^(?i)(owl|rdfs|rdf):" "")
        (str/replace #"[-_: ]" "")
        str/lower-case)))

(defn- coerce-axiom-type
  "Coerce a draft :axiom-type to one of the four S07 family keywords.
   Accepts the project short forms (`:disjointness`) AND the OWL/RDF
   standard term names the model gravitates to (`\"disjointClasses\"`)
   via `axiom-type-aliases`. Returns nil for an unrecognized term — the
   routing site turns that into a LOUD failure (never a fabricated axiom)."
  [axiom-type]
  (get axiom-type-aliases (normalize-vocab-term axiom-type)))

(def ^:private characteristic-aliases
  "Canonical normalization for the property-characteristic enum flags.
   The model emits `\"functionalProperty\"` / `\"transitive\"` etc.;
   these map to the S07 enum [:functional :transitive :symmetric].
   Unknown → loud failure."
  {"functional"          :functional
   "functionalproperty"  :functional
   "transitive"          :transitive
   "transitiveproperty"  :transitive
   "symmetric"           :symmetric
   "symmetricproperty"   :symmetric})

(defn- coerce-characteristic-flag!
  "Coerce one characteristic flag (string or keyword) to the S07 enum,
   accepting the OWL term names (`\"transitiveProperty\"`) via the alias
   table. An unknown flag fails LOUDLY — no silent drop, no default."
  [draft flag]
  (let [k (get characteristic-aliases (normalize-vocab-term flag))]
    (when-not (contains? valid-characteristics k)
      (throw (ex-info (str "compile-discovery-source!: unknown characteristic flag " (pr-str flag)
                           "; valid: " valid-characteristics)
                      {:flag flag
                       :draft draft
                       :reason :unknown-characteristic})))
    k))

(defn- characteristic-from-axiom-type
  "When the model encodes the characteristic IN the axiom-type term
   (`\"transitiveProperty\"`, `\"functionalProperty\"`,
   `\"symmetricProperty\"`) and omits an explicit body :characteristic,
   recover the flag from the term. Returns nil for inverse-of /
   generic `propertyCharacteristic` (no scalar flag implied)."
  [axiom-type]
  (get characteristic-aliases (normalize-vocab-term axiom-type)))

(defn- axiom-draft->command
  "Route an axiom-draft to its S07 axiom command, coercing the
   axiom-type discriminator and any enum-valued body fields from JSON
   strings to keywords. Returns a command map ready for cp/process-command.

   Unknown axiom-type / characteristic / malformed draft → loud ex-info."
  [ontology-id draft]
  (when-not (and (map? draft) (:axiom-type draft) (map? (:body draft)))
    (throw (ex-info "compile-discovery-source!: malformed axiom-draft (missing :axiom-type or :body)"
                    {:draft draft
                     :reason :missing-required-field})))
  (let [axiom-type (coerce-axiom-type (:axiom-type draft))
        body (:body draft)
        base {:command/id (random-uuid)
              :command/timestamp (time/now)
              :ontology-id ontology-id}]
    (case axiom-type
      :disjointness
      ;; Accept the OWL-standard `:classes` body key as a synonym for the
      ;; S07 `:class-uris` field.
      (assoc base
             :command/name :ontology/assert-disjointness
             :class-uris (vec (or (:class-uris body) (:classes body))))

      :property-characteristic
      ;; The flag may live in the body (:characteristic) OR be encoded in
      ;; the axiom-type term (`transitiveProperty`). Accept `:property` as
      ;; an OWL-standard synonym for the S07 `:predicate` field.
      (let [body-flags (mapv #(coerce-characteristic-flag! draft %)
                             (or (:characteristic body) []))
            term-flag (characteristic-from-axiom-type (:axiom-type draft))
            flags (vec (distinct (cond-> body-flags
                                   (and (some? term-flag)
                                        (not (some #{term-flag} body-flags)))
                                   (conj term-flag))))
            inverse-of (or (:inverse-of body) (:inverse body))]
        (cond-> (assoc base
                       :command/name :ontology/assert-property-characteristic
                       :predicate (or (:predicate body) (:property body))
                       :characteristic flags)
          inverse-of (assoc :inverse-of inverse-of)))

      :sub-property
      (assoc base
             :command/name :ontology/assert-sub-property
             :sub-predicate (or (:sub-predicate body) (:sub-property body) (:sub body))
             :super-predicate (or (:super-predicate body) (:super-property body) (:super body)))

      :chain
      (assoc base
             :command/name :ontology/assert-chain-axiom
             :chain (vec (or (:chain body) (:properties body)))
             :derived-predicate (or (:derived-predicate body) (:derived body)
                                    (:predicate body) (:property body)))

      ;; No command target — a default would FABRICATE an axiom the
      ;; model never authored. Fail loudly (Disciplines #5).
      (throw (ex-info (str "compile-discovery-source!: unknown axiom-type " (pr-str (:axiom-type draft))
                           "; valid: #{:disjointness :property-characteristic :sub-property :chain}"
                           " (OWL-standard term names also accepted)")
                      {:axiom-type (:axiom-type draft)
                       :coerced axiom-type
                       :draft draft
                       :reason :unknown-axiom-type})))))

(defn compile-discovery-source!
  "Adapter from a `run-discovery!` output to S17-ingestible events.

   Emits the draft concepts and relationships through the existing
   `:ontology/create-concept` and `:ontology/create-relationship`
   commands. Returns a 'source stub' the S17 build! can pass through
   its `:sources` list — by design the stub is an `:inline-concepts`
   with empty `:concepts` (because we already emitted), giving S17 a
   no-op parse stage to record provenance against.

   This is the `:type :rlm-discovery` source-type integration —
   instead of teaching S17 a new type, we EMIT the events here and
   hand S17 a stub it already knows how to handle. The S17 namespace
   stays unchanged.

   Adversarial: a malformed draft raises an anomaly with the offending
   draft attached. The S17 pipeline driver sees this as a parse-stage
   failure with the root cause visible — NOT a silent drop.

   Returns:
     {:type :inline-concepts :concepts []
      :discovery-provenance {:status :ingested
                             :concepts-emitted <int>
                             :relationships-emitted <int>
                             :axioms-emitted <int>
                             :rlm-trace <vec>}}

   V07 — axiom-drafts are now ROUTED to the S07 axiom commands
   (`assert-disjointness` / `assert-property-characteristic` /
   `assert-sub-property` / `assert-chain-axiom`) per their
   `:axiom-type` discriminator, applying the same JSON-string→keyword
   coercion the scope / confidence-class paths use. The provenance
   count reports `:axioms-emitted` (the prior `:axioms-skipped` gap is
   closed). An unknown axiom-type / characteristic, a malformed draft,
   or an S07 command rejection all fail LOUDLY — no silent drop, no
   fabricated axiom."
  [ctx ontology-id discovery-output]
  (when-not (= :emitted-drafts (:status discovery-output))
    (throw (ex-info "compile-discovery-source!: discovery output must have :status :emitted-drafts"
                    {:status (:status discovery-output)
                     :discovery-output discovery-output})))
  (let [concepts (mapv validate-concept-draft! (:emitted-concepts discovery-output))
        relationships (mapv validate-relationship-draft! (:emitted-relationships discovery-output))
        axioms (:emitted-axioms discovery-output)
        rlm-trace (:rlm-trace discovery-output)]

    ;; Emit concept events first so relationship endpoints resolve
    ;; against existing concepts. Order matters when downstream
    ;; consumers care about temporal ordering of events.
    (doseq [c concepts]
      (let [result (cp/process-command
                     (assoc ctx :command (concept-draft->command ontology-id c)))]
        (when (:cognitect.anomalies/category result)
          (throw (ex-info "compile-discovery-source!: concept emission anomaly"
                          {:draft c :anomaly result})))))

    ;; V18 — enforce referential integrity as an ALWAYS-ON structural
    ;; invariant (not behind the optional shape-gated validate-stage that
    ;; let V17's 119 dangling edges through). Auto-mint implied concepts
    ;; for referenced-but-unminted endpoints and surface near-variant
    ;; ambiguities — BEFORE the relationship events land so every endpoint
    ;; resolves at append time.
    (let [integrity (ensure-referential-integrity!
                      ctx ontology-id concepts relationships)]

      (doseq [r relationships]
        (let [result (cp/process-command
                       (assoc ctx :command (relationship-draft->command ontology-id r)))]
          (when (:cognitect.anomalies/category result)
            (throw (ex-info "compile-discovery-source!: relationship emission anomaly"
                            {:draft r :anomaly result})))))

      ;; V07 — route axiom-drafts to their S07 axiom commands. The
      ;; axiom-draft->command transform coerces axiom-type + characteristic
      ;; enums from JSON strings and fails loudly on anything unrouteable
      ;; (no silent drop, no fabricated axiom). A command-level rejection
      ;; (e.g. singleton class-uris) surfaces as a loud anomaly too.
      (doseq [a (or axioms [])]
        (let [result (cp/process-command
                       (assoc ctx :command (axiom-draft->command ontology-id a)))]
          (when (:cognitect.anomalies/category result)
            (throw (ex-info "compile-discovery-source!: axiom emission anomaly"
                            {:draft a :anomaly result})))))

      {:type :inline-concepts
       :concepts []
       :discovery-provenance {:status :ingested
                            :concepts-emitted (count concepts)
                            :relationships-emitted (count relationships)
                            :axioms-emitted (count (or axioms []))
                            ;; V18 — referential-integrity report (always on).
                            :implied-concepts-minted (:implied-minted integrity)
                            :ambiguities-flagged (count (:ambiguities integrity))
                            :ambiguities (:ambiguities integrity)
                            :unresolved-endpoints (count (:unresolved integrity))
                            :unresolved-endpoint-uris (:unresolved integrity)
                            :every-edge-endpoint-resolves?
                            (:every-edge-endpoint-resolves? integrity)
                            :rlm-trace rlm-trace}})))

;; =============================================================================
;; Public convenience: discover-and-build!
;; =============================================================================
;; A one-call convenience that chains run-discovery! +
;; compile-discovery-source! and threads the resulting source stub
;; into the S17 build!. Callers who want fine-grained control over
;; the two stages can call them individually. The deterministic
;; skeleton namespace is loaded lazily here to avoid a circular
;; dependency at compile time.

(defn discover-and-build!
  "Convenience: run discovery, emit drafts as events, then drive S17's
   skeleton end-to-end. Returns the S17 `build!` result augmented
   with `:discovery-provenance` (the rlm-trace + emitted counts).

   Required keys mirror `run-discovery!` + S17 `build!`:
     :ontology-id     — granted scope
     :sources         — discovery sources (same shape as run-discovery!)
     ...              — any S17 `build!` keys are passed through

   When discovery returns `:status :no-output` (model produced no
   drafts), the build proceeds with NO new sources — typically
   surfaces as a build with `:concepts-count` unchanged and
   `:discovery-provenance {:status :no-output ...}` on the result.

   When discovery fails (`:failed-at-session`), this raises an
   anomaly — the caller sees the root cause; we do NOT mask a
   discovery failure with an empty build."
  [ctx {:keys [ontology-id sources discovery-prompt model budget
               auto-classify? require-hitl-reviewed-patterns?
               debug?]
        :as build-params}]
  (let [discovery-out (run-discovery!
                        ctx
                        {:ontology-id ontology-id
                         :sources sources
                         :discovery-prompt discovery-prompt
                         :model model
                         :budget budget
                         :auto-classify? auto-classify?
                         :require-hitl-reviewed-patterns? require-hitl-reviewed-patterns?
                         :debug? debug?})
        skeleton-build (requiring-resolve
                         'ai.obney.orc.ontology.core.deterministic-skeleton/build!)]

    (cond
      (= :failed-at-session (:status discovery-out))
      (throw (ex-info "discover-and-build!: discovery session failed"
                      {:discovery-output discovery-out
                       :reason :failed-at-session}))

      (= :no-output (:status discovery-out))
      (assoc (skeleton-build
               ctx
               (assoc build-params
                      :sources [{:type :inline-concepts :concepts []}]))
             :discovery-provenance {:status :no-output
                                    :rlm-trace (:rlm-trace discovery-out)
                                    :patterns-offered (:patterns-offered discovery-out)})

      :else
      (let [source-stub (compile-discovery-source! ctx ontology-id discovery-out)]
        (assoc (skeleton-build
                 ctx
                 (assoc build-params :sources [source-stub]))
               :discovery-provenance (assoc (:discovery-provenance source-stub)
                                            :patterns-offered (:patterns-offered discovery-out)))))))
