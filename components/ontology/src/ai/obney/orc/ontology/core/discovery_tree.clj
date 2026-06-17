(ns ai.obney.orc.ontology.core.discovery-tree
  "DT1 — Discovery behavior-tree scaffold + orchestration skeleton.

   The FOUNDATIONAL tracer bullet of the discovery-tree redesign (PRD
   `2026-06-16-discovery-behavior-tree-redesign.md`). It replaces — eventually —
   the monolithic open-ended `run-discovery!` loop with a COHESIVE discovery
   behavior tree: a fixed-core sequence of focused reasoning nodes feeding the
   intact deterministic skeleton (`build!`), with an adaptive CQ loop and
   RLM-chosen branches at the edges.

   ## Two levels (PRD M1)

     GRAPH-LEVEL ORCHESTRATION   (this namespace's `run-discovery-tree!`)
       goal/intent ── orients every node (an input, not a node)
       PER-SOURCE SUB-TREE:  Profile → Model → Transform
                             → [V20 deterministic full-extraction apply-step]
                             → build!  (the intact deterministic skeleton)
                             → read the CQ verdict back onto the tree result

   At DT1 the per-source sub-tree is the only level wired end-to-end for ONE
   source; DT5 adds graph-level CQ derivation across all sources and DT7 adds
   cross-source reconciliation. The structure here is shaped so those slices add
   stages WITHOUT restructuring (see the named branch-point stubs).

   ## Structural guarantee (the whole point — PRD problem statement)

   The mega-prompt treadmill failed because more prose does not STRUCTURALLY
   guarantee a step happens. Here each step is its own node in a fixed sequence
   the orchestrator drives deterministically — you CANNOT skip the
   'decide grain + scope' step when it is its own node with the profile as its
   explicit input. The spine is deterministic; the knowledge work at each node
   is done by a recursive-RLM `:repl-researcher` session (Discipline #10 —
   'deterministic skeleton' is the spine, NOT LLM-free).

   ## The inter-node contract (FROZEN at DT1 — PRD M2)

   Data flows between nodes on a BLACKBOARD map. Each node reads its
   predecessor's output from the blackboard (the `node-output` drill-down
   mechanism — see `node-output`) and writes its own contract-shaped output.
   The three contract shapes (frozen so DT2/DT3/DT4 build on them, NOT free to
   drift):

     Profile   → {:entity-candidates [...] :identifying-keys {...}
                  :scope-fields [...] :linking-keys [...]
                  :grain-signals [...] :sample [...]}
     Model     → {:entity-types [{:type ... :uri-keying-fields [...]
                                   :grain-strategy (:canonical-row-filter
                                                    | :breakdown-as-entity)}]
                  :scope-filter ... :edges [...]}
     Transform → {:transform-source \"(fn [row] {:concept-drafts [...]
                                                  :relationship-drafts [...]})\"
                  :selector \"<table-or-sheet>\"}   ; the V20 extraction shape

   See `profile-contract-keys` / `model-contract-keys` / `transform-contract-keys`
   for the machine-readable frozen key vectors and `valid-grain-strategies` for
   the grain-strategy enum.

   ## Reuse, not rewrite (Discipline #8)

   The deterministic skeleton `build!` and the V20 `apply-extraction-transform!`
   apply-step are invoked UNCHANGED as sub-calls. The per-medium specialist tools
   (V06/V19) are bound at the node leaf by reusing `run-discovery!`'s existing
   `:granted-source` seam + the format-exploration prompt assembly — discovery
   does NOT duplicate the tool registry. The thin nodes in this slice carry NO
   domain knowledge (Discipline #12); the focus comes from the runtime goal.

   ## Branch points (NAMED STUBS — filled by later slices)

   The four RLM-chosen branch points (PRD M1) exist as explicit, named, no-op
   stubs so DT8/DT9 fill them WITHOUT restructuring the spine:
     `recovery-branch-stub`            (DT8 — focused single-node re-run)
     `cq-reextract-branch-stub`        (DT8 — CQ-driven re-extract loop)
     `greenfield-vs-maintain-branch-stub` (DT9 — greenfield is built; maintain deferred)
     `full-extract-vs-inline-branch-stub` (small-source: sample already covers it)

   ## Thin nodes (DT1)

   The Profile/Model/Transform nodes here are intentionally THIN — minimal,
   single-purpose prompts that prove the contract flows. DT2/DT3/DT4 replace the
   thin prompt of each node with a focused, prototyped one WITHOUT touching the
   orchestration (the prompt for each node goes through the `*-node-prompt`
   promotion seam, PRD M6)."
  (:require [ai.obney.orc.ontology.core.rlm-discovery :as rlm-discovery]
            [ai.obney.orc.ontology.core.deterministic-skeleton :as skeleton]
            [clojure.string :as str]))

;; =============================================================================
;; Frozen inter-node contract (PRD M2) — the load-bearing freeze of DT1
;; =============================================================================
;; These vectors/enums are the machine-readable form of the contract DT2/DT3/DT4
;; build on. They are PUBLIC so node tests assert against them and downstream
;; slices reference them rather than re-typing key names that could drift.

(def profile-contract-keys
  "The frozen Profile-node output contract (PRD M2). A profile characterizes the
   source: candidate entities, the fields that identify each, the goal-scoping
   fields, cross-source linking keys, breakdown/grain signals, and a small
   sample. Read by the Model node."
  [:entity-candidates :identifying-keys :scope-fields
   :linking-keys :grain-signals :sample])

(def model-contract-keys
  "The frozen Model-node output contract (PRD M2). The grain + scope decision:
   the entity types (each with its URI-keying fields + grain strategy), the
   scope filter derived from the goal, and the edges between entity types. Read
   by the Transform node."
  [:entity-types :scope-filter :edges])

(def transform-contract-keys
  "The frozen Transform-node output contract (PRD M2) — the V20 extraction-
   transform shape. A per-row pure transform (authored + sample-validated by the
   node, as a string of Clojure source) and the table/sheet selector it applies
   to. Consumed by the V20 deterministic full-extraction apply-step."
  [:transform-source :selector])

(def valid-grain-strategies
  "The frozen grain-strategy enum a Model-node :entity-types entry may carry
   (PRD M2). `:canonical-row-filter` keeps one entity per canonical/summary row
   and drops breakdown rows; `:breakdown-as-entity` mints each breakdown as its
   own entity with the breakdown key in its URI. Pick ONE per entity type — this
   is the V17/V20 over-extraction fix made a first-class decision."
  #{:canonical-row-filter :breakdown-as-entity})

(defn normalize-grain-strategy
  "Normalize a model-spec :grain-strategy value to its frozen-enum KEYWORD,
   tolerating the value-shape variance the DT3 live verify documented: the model
   emits the strategy as the bare keyword `:canonical-row-filter` on some runs and
   as the STRING form `\":canonical-row-filter\"` on others (the frozen contract
   freezes the KEY SET + the ENUM, not the literal value-shape). A symbol form is
   also tolerated. Returns the keyword (`:canonical-row-filter` /
   `:breakdown-as-entity`) when it maps onto the enum, else nil — so a consumer
   can read the DECISION regardless of which value-shape arrived. This is the
   carry-forward the DT4 Transform node MUST apply when reading the model-spec
   (the transform's grain handling keys off the keyword, not a sentence)."
  [v]
  (let [k (cond
            (keyword? v) v
            (symbol? v)  (keyword (str/replace (str v) #"^:" ""))
            (string? v)  (keyword (str/replace (str/trim v) #"^:" ""))
            :else        nil)]
    (when (contains? valid-grain-strategies k) k)))

;; =============================================================================
;; Promotion seam (PRD M6) — static now, living later
;; =============================================================================
;; Each node's prompt is assembled through ONE seam returning a static focused
;; prompt TODAY, shaped so a later slice (DT6) can source it from
;; classify-behaviors / the seed corpus and participate in minting — a flip
;; behind the seam, NOT a node rewrite. The seam does NOT couple to current
;; minting internals (the minting process is being reworked separately).
;;
;; The DT1 prompts are THIN (Discipline: prove orchestration, not node
;; intelligence). DT2/DT3/DT4 replace the body each seam returns with a focused,
;; prototyped prompt. The contract block is appended verbatim so a thin node
;; still emits the frozen shape.

(defn- contract-block
  "Render the frozen output contract for a node as an explicit instruction tail.
   Keeps the thin DT1 prompt honest: even a near-stub node is told exactly which
   keys to emit (the frozen contract), so the blackboard carries the right shape
   between nodes."
  [keys-vec extra]
  (str "\n\nOUTPUT (call (final! {...}) writing EXACTLY these keys):\n  "
       (str/join " " (map (fn [k] (str k)) keys-vec))
       (when extra (str "\n" extra))))

;; --- Focused per-medium tool catalog (DT2) ----------------------------------
;; The executor binds the per-medium source-access tools via the :granted-source
;; seam but does NOT auto-list them in the prompt — a node only learns which
;; tools exist from its prompt text. The retired mega-prompt listed them inline
;; alongside modeling/transform/scope guidance; a FOCUSED node must name the
;; tools WITHOUT that cross-concern guidance. We pull the SAME docstrings the
;; specialist tools ship (V06/V19 `source-tool-docs-for`) — no re-typing of tool
;; names that could drift — and render a lean name + one-line PURPOSE catalog.

(defn- resolve-source-tool-docs
  "Lazy-resolve the orc-service per-medium tool-docs accessor (a peer component;
   resolved at runtime, same discipline as the executor resolution in
   rlm-discovery). Returns `{symbol -> docstring}` for a format, or nil for a
   format with no source tools (`:text` uses the inline blackboard path)."
  [fmt]
  (let [f (requiring-resolve 'ai.obney.orc.orc-service.core.source-tools/source-tool-docs-for)]
    (f {:format fmt})))

(defn- tool-purpose-line
  "Extract the one-line PURPOSE summary from a specialist tool docstring (they
   open with `PURPOSE — <text>` then an EXAMPLE / RETURNS block). Falls back to
   the docstring's first non-blank line. Keeps the focused catalog small."
  [doc]
  (let [doc (str doc)
        purpose (some->> (re-find #"(?s)PURPOSE\s*[—\-:]\s*(.+?)(?:\n\s*\n|\nEXAMPLE|\nRETURNS)" doc)
                         second)
        line (or purpose (first (remove str/blank? (str/split-lines doc))))]
    (-> (or line "")
        (str/replace #"\s+" " ")
        str/trim)))

(defn- focused-tool-catalog
  "Render a lean per-medium tool catalog (tool name + one-line PURPOSE) for the
   focused Profile prompt, pulled from the specialist tools' own docstrings. This
   is the ONLY medium specialization in the Profile prompt — it names which tools
   to call for csv / sql / excel; the profiling INSTRUCTION is medium-agnostic.
   A `:text` source (no source tools) returns a short note pointing at the inline
   content. Domain-agnostic (discipline 12): tool ergonomics, never a domain."
  [fmt]
  (if-let [docs (resolve-source-tool-docs fmt)]
    (str "SOURCE-ACCESS TOOLS for this " (name fmt) " source (call them as bare "
         "top-level expressions — e.g. just `(" (-> docs keys first name) ")` on "
         "its own line; do NOT wrap a tool call in `(code ...)`):\n"
         (str/join "\n"
                   (map (fn [[sym doc]]
                          (str "  (" (name sym) " …) — " (tool-purpose-line doc)))
                        docs))
         "\n(You may call `(meta " (-> docs keys first name) ")` to read a tool's "
         "full docstring with arg shapes + examples.)")
    (str "SOURCE CONTENT is provided inline on the blackboard (read it with "
         "(get-input …)). Profile it directly — there are no source-access tools "
         "for this medium.")))

(defn profile-node-prompt
  "PROMOTION SEAM (PRD M6) for the Profile node. DT2 FOCUSED body: a small,
   single-purpose prompt that does ONE job — CHARACTERIZE what the source is
   ABOUT — and emits the frozen profile contract. It carries NO modeling, NO
   transform-design, NO scope DECISION (DT3/DT4 own those); the only medium
   specialization is the per-medium tool catalog naming which tools to call.
   Domain-agnostic (discipline 12): no CIP/SOC/industry knowledge — it
   characterizes ANY structured source. `goal` ORIENTS the node (it points the
   characterization at the kinds of things the goal cares about, PRD user-story
   17) but the node does NOT decide scope here.

   `fmt` is the source format (`:csv` / `:sql` / `:excel` / `:text`) used only to
   bind the focused tool catalog. Orchestration unchanged — this body is returned
   through the same seam the thin DT1 body was."
  [goal fmt]
  (str "You are the PROFILE step of an ontology-discovery pipeline. Your ONE job "
       "is to CHARACTERIZE what this source is ABOUT — not to model it, design a "
       "transform, or decide what to keep. Later steps do that; do NOT do their "
       "work.\n\n"
       "GOAL (orients what to look for, but you make NO scope decision here): "
       goal "\n\n"
       (focused-tool-catalog fmt) "\n\n"
       "HOW TO PROFILE (a couple of quick tool calls, then finalize — do NOT use "
       "emit-tree!, do NOT page the whole source, do NOT mint any concepts):\n"
       "  1. Look at the source's SHAPE (its columns / tables / sheets / fields).\n"
       "  2. SAMPLE a few real rows to see actual values.\n"
       "  3. From the shape + sample, characterize the source into the contract "
       "below, then call (final! {…}).\n\n"
       "WHAT TO CHARACTERIZE (this is the whole job):\n"
       "  - ENTITY CANDIDATES: what real-world thing(s) does each row / record "
       "describe? Name the candidate entity TYPES (a row is usually about one or a "
       "few kinds of thing).\n"
       "  - IDENTIFYING KEYS: for each entity candidate, which field(s) IDENTIFY a "
       "single instance of it (the field whose value names that one thing).\n"
       "  - SCOPE FIELDS: which fields COULD later be used to narrow the source to "
       "a region / subset / time window / category (a state, a year, a group, a "
       "status column). Just LIST the candidate fields — do NOT pick values or "
       "filter; that is a later step's decision.\n"
       "  - LINKING KEYS: which fields are CODES or KEYS that likely identify the "
       "SAME entity in OTHER sources (a shared code / id a different source would "
       "also carry), so the graph can connect across sources later.\n"
       "  - GRAIN SIGNALS: any sign that rows are FINER-grained than entities — a "
       "column whose repeats split one entity across many rows (a breakdown / "
       "category / demographic / sub-measure / year dimension), or a "
       "total/summary marker. Note the column(s) responsible.\n"
       "  - SAMPLE: keep a few representative row maps verbatim (as you read them) "
       "so the next step sees real values."
       (contract-block
        profile-contract-keys
        "  :entity-candidates  — vector of candidate entity-type descriptions (strings)\n  :identifying-keys   — map of entity-candidate (string) -> vector of field(s) that identify it\n  :scope-fields       — vector of field names that COULD scope the source (no value chosen)\n  :linking-keys       — vector of code/key field names that likely link to other sources\n  :grain-signals      — vector of strings noting where rows are finer-grained than entities\n  :sample             — vector of a few representative row maps read from the source")))

(defn model-node-prompt
  "PROMOTION SEAM (PRD M6) for the Model node. DT3 FOCUSED body: a small,
   single-purpose prompt that does ONE job — turn the GOAL + the DT2 profile into
   the MODELING DECISION (the entity model) — and emits the frozen model-spec
   contract. It does NOT re-profile and does NOT author a transform (DT4 owns the
   transform); it makes ONLY the grain + scope + entity decision.

   This node is the KEYSTONE of the V17/V20 OVER-EXTRACTION FIX. The retired
   mega-prompt buried 'decide grain + scope' in a wall of prose the loop kept
   dropping, so the builder dumped one concept per raw row (one node per national
   sub-row, no requested-region scope). Here the two load-bearing decisions are
   the node's WHOLE job and a structurally-guaranteed step:

     - GRAIN: when the profile's grain-signals show an entity recurs across
       breakdown / sub-rows (a demographic / category / period / sub-measure
       split, or repeated identifying keys), choose `:canonical-row-filter`
       (keep the ONE summary/total row per entity, drop breakdown rows) OR
       `:breakdown-as-entity` (the breakdown is ITSELF a distinct entity, keyed
       into its URI). NEVER one-concept-per-raw-row.
     - SCOPE: if the GOAL states a scope (a region, subset, time window,
       category), emit a `:scope-filter` keyed to a scope-field the profile
       discovered. The scope comes from the RUNTIME GOAL — never invented, never
       hardcoded.

   The profile is read TOLERANTLY: the DT1-frozen contract freezes the KEY SET,
   not value shapes, and the DT2 live verify showed some profile fields come back
   as prose STRINGS rather than maps/vectors (model-variable). The node is told
   the keys may be strings OR structured and to read whichever it gets.

   Domain-agnostic (discipline 12): no CIP/SOC/industry/education knowledge — it
   models ANY structured source from goal + profile. `goal` is the ONLY domain
   reference; it orients the entity choice AND supplies the scope. Orchestration
   unchanged — this body is returned through the same seam the thin DT1 body was."
  [goal]
  (str "You are the MODEL step of an ontology-discovery pipeline. Your ONE job is "
       "to decide the ENTITY MODEL for this source — the entity types, how each is "
       "keyed, the GRAIN to keep, and the SCOPE the goal asks for. You do NOT "
       "re-profile the source and you do NOT write any extraction code; a later "
       "step authors the transform. Make ONLY the modeling decision, then call "
       "(final! {…}).\n\n"
       "GOAL (orients the entity choice AND states the scope to honor): " goal "\n\n"
       "INPUT: you are given the PROFILE of this source as the input key :profile "
       "(read it with (get-input :profile)). The profile characterizes the source: "
       "its entity-candidates, identifying-keys (field(s) that name one instance), "
       "scope-fields (fields that COULD narrow the source), linking-keys (shared "
       "codes that connect to other sources), grain-signals (where rows are FINER "
       "than entities), and a sample of real rows. READ IT TOLERANTLY: any field "
       "may come back as a plain STRING (prose) OR as a structured map/vector — "
       "handle whichever you get; never assume a rigid shape. You may sample a few "
       "rows from the source to confirm a field name, but do NOT re-do the profile.\n\n"
       "MAKE TWO LOAD-BEARING DECISIONS (this is the whole job — get these right "
       "and the source will NOT over-extract):\n\n"
       "  1. GRAIN — for EACH entity type, choose how the rows map to entities so "
       "you NEVER mint one concept per raw row when rows are finer than entities. "
       "Look at the profile's grain-signals + the repeats in the sample. If an "
       "entity recurs across breakdown / sub-rows (a category, period, status, "
       "subgroup, or sub-measure split — or its identifying key repeats across "
       "many rows), pick ONE grain-strategy for it from " (pr-str valid-grain-strategies)
       ":\n"
       "       :canonical-row-filter — the entity has ONE summary/total/canonical "
       "row per instance and the rest are breakdown rows of that same entity; keep "
       "the canonical row, drop the breakdowns (note which field marks the "
       "canonical/total row so the transform step can filter on it).\n"
       "       :breakdown-as-entity — the breakdown is ITSELF a real entity worth a "
       "node; mint each breakdown as its own entity and FOLD the breakdown key into "
       "its URI so the rows don't collapse.\n"
       "     Pick whichever fits what the GOAL wants to count. If rows are already "
       "one-per-entity (the profile shows no finer grain), :canonical-row-filter "
       "with no breakdown is fine — the point is you DECIDED, not that you dumped "
       "every row.\n"
       "     URI-KEYING: derive each entity's :uri-keying-fields from the profile's "
       "identifying-keys for that entity (the field(s) whose value names one "
       "instance), so the same real entity collapses to ONE node, not one-per-row. "
       "For :breakdown-as-entity, INCLUDE the breakdown key in the keying fields.\n\n"
       "  2. SCOPE — does the GOAL state a scope (a region, a subset, a time "
       "window, a category)? If YES, emit a :scope-filter that keeps ONLY the "
       "in-scope rows, keyed to a scope-field the profile discovered (pick the "
       "field from the profile's scope-fields that matches what the goal asks for) "
       "with the value(s) the GOAL names. The scope VALUE comes from the GOAL — "
       "do NOT invent a scope the goal didn't ask for, and do NOT hardcode one. "
       "If the goal states NO scope, set :scope-filter to nil (keep everything).\n\n"
       "Then decide the EDGES between your entity types (the relationships the "
       "source supports — e.g. one entity links to another via a shared key the "
       "profile flagged as a linking-key).\n\n"
       "EMIT EDN DATA, NOT STRINGS: the values you (final! …) must be real "
       "Clojure data structures — :entity-types a VECTOR of MAPS, :edges a VECTOR "
       "of MAPS, :scope-filter a MAP (or nil) — NOT a JSON string and NOT a prose "
       "string. CRITICAL: each entity's :grain-strategy MUST be EXACTLY one of "
       "these two keywords — " (pr-str (vec valid-grain-strategies)) " — a bare "
       "keyword, NOT a sentence describing it. The downstream transform step reads "
       "the grain-strategy keyword directly; a prose description there is unusable."
       (contract-block
        model-contract-keys
        "  :entity-types  — VECTOR of MAPS: [{:type <str> :uri-keying-fields [<field> ...] :grain-strategy <EXACTLY :canonical-row-filter OR :breakdown-as-entity, a bare keyword>} ...]\n                   (you MAY add :canonical-row-marker <field/value note> for a :canonical-row-filter entity, or :breakdown-key <field> for a :breakdown-as-entity entity — the transform step reads these)\n  :scope-filter  — nil if the goal states no scope, else a MAP {:field <scope-field from the profile> :values [<value(s) the GOAL names>]}; the value comes from the GOAL, never invented\n  :edges         — VECTOR of MAPS: [{:source-type <str> :target-type <str> :predicate <str>} ...]")))

;; =============================================================================
;; DT4-grounding — surface the REAL sampled-row key shape into the transform seam
;; =============================================================================
;; The honest negative from DT4: the model authored STRUCTURALLY-correct
;; transforms whose per-row FIELD ACCESS was grounded in ASSUMED key names/shapes
;; rather than the source's REAL ones — e.g. (get row "unitid") where the real
;; SQL key is the keyword :UNITID; (get row :CIP2020Code) where the real CSV key
;; is the string "CIP_Code". A wrong key silently yields nil for EVERY row → a
;; 0-concept false-empty. Root cause: the prompt did not FORCE grounding in the
;; EXACT row-key shape, which differs by medium (SQL/excel rows have KEYWORD
;; keys; CSV rows have STRING keys keyed by the header).
;;
;; The fix is mechanical + domain-agnostic (discipline 12): take a REAL sample
;; row, extract its EXACT key set + key TYPE, and inject them + the format's exact
;; access idiom into the prompt with a hard "use these verbatim; do NOT invent /
;; rename / guess" instruction. NO hardcoded domain field names — every key comes
;; from the runtime sample, exactly like the goal orients the prompt. It is
;; format-AWARE (the key shape genuinely differs by medium), which is allowed.

(defn- map-rows-from-sample
  "Pull the row MAPS out of whatever a sample/stream call returned for a medium.
   csv/sql sample-rows return {:rows [...]} (csv: string-keyed maps; sql:
   keyword-keyed maps); the sql `query` tool returns a bare VECTOR of maps;
   stream-all returns a vector of window maps each with :rows. Returns the first
   non-empty vector of MAPS found, else []."
  [sampled]
  (let [maps-only (fn [coll] (filterv map? (or coll [])))]
    (cond
      ;; a bare vector — either of row maps (query) or of window maps (stream-all)
      (and (sequential? sampled) (seq sampled) (every? map? sampled))
      (let [rows (maps-only sampled)]
        (if (and (seq rows) (some :rows rows))
          ;; these are window maps; descend into their :rows
          (maps-only (mapcat #(or (:rows %) []) rows))
          rows))
      (sequential? sampled) (maps-only sampled)
      ;; a single {:rows [...]} map
      (and (map? sampled) (contains? sampled :rows)) (maps-only (:rows sampled))
      :else [])))

(defn mechanical-sample-rows
  "Pull a small set of REAL rows DIRECTLY from the source via the V06 per-format
   source-tool registry — the SAME tools the V20 apply-step streams through, so
   the row shape returned is EXACTLY what the transform will see at apply time.
   This is the authoritative key-shape source: it does NOT trust the profile
   node's emitted `:sample` (an LLM may re-key / stringify it), it reads the
   medium's tools directly.

   `descriptor` is `{:type :csv|:sql|:excel :path <str>}`. `selector` is the
   table/sheet name when known (sql/excel); for sql, when no selector is given we
   pick the table with the MOST rows (the data table is the extraction target far
   more often than a tiny lookup table) so the surfaced shape is representative.
   Returns a vector of real row maps (capped small), or [] when the source can't
   be sampled (the caller then falls back / renders no grounding block — never a
   fabricated shape). Domain-agnostic: it reads keys, names no field."
  ([descriptor] (mechanical-sample-rows descriptor nil 5))
  ([descriptor selector] (mechanical-sample-rows descriptor selector 5))
  ([descriptor selector n]
   (try
     (let [tools-for (requiring-resolve
                      'ai.obney.orc.orc-service.core.source-tools/source-tools-for)
           tools (tools-for {:type (:type descriptor) :format (:format descriptor)
                             :path (:path descriptor)})]
       (if-not (map? tools)
         []
         (let [sample (get tools 'sample-rows)
               fmt (or (:type descriptor) (:format descriptor))]
           (cond
             ;; CSV: (sample-rows N) -> {:rows [string-keyed maps]}
             (= :csv fmt)
             (map-rows-from-sample (sample n))
             ;; SQL: (sample-rows table {:limit N}) -> [keyword-keyed maps]
             (= :sql fmt)
             (let [tbl (cond
                         (string? selector) selector
                         (map? selector) (or (:name selector) (:table selector))
                         :else
                         ;; pick the largest table as the representative target
                         (let [list-tables (get tools 'list-tables)
                               count-rows (get tools 'count-rows)
                               tables (when (fn? list-tables) (list-tables))]
                           (when (seq tables)
                             (->> tables
                                  (map (fn [t] [t (try (:row-count (count-rows t))
                                                       (catch Throwable _ 0))]))
                                  (sort-by second >)
                                  ffirst))))]
               (if (and tbl (fn? sample))
                 (map-rows-from-sample (sample tbl {:limit n}))
                 []))
             ;; Excel: rows are positional cell vectors, not maps — no map key
             ;; shape to surface; the caller renders no keyword/string idiom.
             :else []))))
     (catch Throwable _ []))))

(defn sample-row-key-shape
  "Mechanically derive the EXACT row-key shape from a REAL sample of the source —
   the load-bearing input to the DT4-grounding fix. DOMAIN-AGNOSTIC: it reads the
   keys that are actually present on a sampled row; it bakes in NO field names.

   `descriptor` is the source descriptor ({:type :csv|:sql|:excel ...}); `rows`
   is a real sample (the row maps a sample/stream tool returned, in any of the
   shapes `map-rows-from-sample` accepts). When `rows` yields no map rows, this
   falls back to `mechanical-sample-rows` — reading the source's own tools
   directly — so an unreliable profile-emitted sample never silences the
   grounding. Returns:

     {:keys      [<the EXACT keys of a representative row, verbatim>]
      :key-type  :keyword | :string | :other   ; how the row's keys are typed
      :format    <the source format>
      :sample-row <one representative row map, verbatim>}

   or nil when no map row could be sampled (the caller renders no grounding block
   and the legacy prompt stands — no false grounding from an absent sample).

   The key TYPE is what differs by medium and is the exact trap the honest
   negative hit: sql/excel rows are KEYWORD-keyed, csv rows STRING-keyed."
  ([descriptor rows] (sample-row-key-shape descriptor rows nil))
  ([descriptor rows selector]
   (let [from-passed (map-rows-from-sample rows)
         ;; The profile-emitted sample is best-effort (an LLM may re-key it);
         ;; when it yields no map rows, read the source's OWN tools directly so
         ;; the grounding is never silenced by an unreliable upstream sample.
         row-maps (if (seq from-passed)
                    from-passed
                    (mechanical-sample-rows descriptor selector))
         ;; pick the row with the MOST keys as representative (defends against a
         ;; first row with nil-trimmed columns).
         rep (when (seq row-maps)
               (apply max-key (comp count keys) row-maps))]
     (when (and (map? rep) (seq rep))
       (let [ks (vec (keys rep))
             key-type (cond
                        (every? keyword? ks) :keyword
                        (every? string? ks)  :string
                        :else                :other)]
         {:keys ks
          :key-type key-type
          :format (or (:type descriptor) (:format descriptor))
          :sample-row rep})))))

(defn- access-idiom-for
  "The EXACT per-row field-access idiom for a key type, as the model should write
   it — shown with a real key from the sample so it is concrete, not abstract."
  [key-type a-key]
  (case key-type
    :keyword (str "(" (pr-str a-key) " row)  — or  (get row " (pr-str a-key) ")  "
                  "(the keys are KEYWORDS)")
    :string  (str "(get row " (pr-str a-key) ")  (the keys are STRINGS — there is "
                  "NO keyword form; (" (pr-str a-key) " row) would NOT work)")
    (str "(get row " (pr-str a-key) ")  (use the EXACT key form shown)")))

(defn key-shape-block
  "Render the REAL sampled-row key shape as a hard grounding instruction for the
   transform prompt. Empty string when no key shape was sampled (back-compat: the
   prompt renders nothing extra, preserving the domain-agnostic guarantee — the
   keys, like the goal, are RUNTIME input, never baked into the body).

   The block lists the EXACT keys verbatim, the format's exact access idiom keyed
   to a real example key, and a hard 'use these verbatim; do NOT invent, rename,
   case-fold, or guess; derive every value from the row' instruction. This is the
   seam that fixes the honest negative — it forces the model to ground field
   access in the source's REAL key shape rather than an assumed one."
  [key-shape]
  (let [{:keys [keys key-type sample-row]} key-shape]
    (if (or (nil? key-shape) (empty? (or keys [])))
      ""
      (let [a-key (first keys)]
        (str "\n\nREAL ROW KEY SHAPE (sampled from THIS source — the transform "
             "receives each row as a map with EXACTLY these keys). Ground EVERY "
             "field access in these — do NOT invent, rename, case-fold, abbreviate, "
             "or guess a key, and do NOT trust a profile/prose variant of a name "
             "over what is shown here:\n"
             "  KEYS (verbatim): " (str/join "  " (map pr-str keys)) "\n"
             "  KEY TYPE: " (name (or key-type :other)) "\n"
             "  ACCESS A FIELD LIKE: " (access-idiom-for key-type a-key) "\n"
             "  ONE REAL ROW (verbatim): " (pr-str sample-row) "\n"
             "  Use these EXACT keys; access a field with the idiom above. A key "
             "that does not appear above is NOT in the row — accessing it yields nil "
             "for EVERY row and extracts NOTHING. Derive every emitted value FROM "
             "the row (id-sets too: never fabricate ids — resolve them from a real "
             "tool query and embed the returned values).")))))

(defn validate-transform-on-sample
  "DT4 sample-validation seam — assert a candidate transform yields NON-EMPTY
   drafts on REAL sample rows BEFORE the full-scale apply, so a mis-grounded
   transform (the false-empty failure mode) is caught at authoring time.

   `transform-source` is the model-authored `(fn [row] …)` source string; `rows`
   is a vector of REAL sampled row maps (the same shape the transform will see at
   apply time). The transform is eval'd in the SAME restricted sandbox the V20
   apply-step uses (reuse, not fork — Discipline #8) and mapped over the sample.

   Returns:
     {:status :ok        :concept-yield <int> :rows-tested <int>}
       when at least one sample row produced a non-empty concept-draft.
     {:status :rejected  :rejection-kind <kw> :reason <str>
                         :concept-yield <int> :rows-tested <int> :rows-threw <int>}
       :rejection-kind is :eval-failure when the source did not evaluate to a fn
       or THREW on every sampled row (a definite fault — e.g. a JS builtin like
       js/parseInt, a wrong contract shape, an unresolved symbol). It is
       :empty-yield when the fn evaluated cleanly but produced no concept-draft on
       any sampled row (a key-shape mis-grounding OR a legitimately out-of-scope
       window — the caller decides how hard to gate on each kind).

   This is honest (Discipline #5): an empty yield / unevaluable transform is
   REJECTED, never silently accepted; the :rejection-kind lets the orchestration
   hard-block a definite fault while not false-rejecting a correctly-scoped
   transform whose small sample window happens to be all out-of-scope."
  [transform-source rows]
  (let [eval-fn (requiring-resolve
                 'ai.obney.orc.ontology.core.rlm-discovery/eval-transform-fn)
        rows (vec (or rows []))]
    (let [f (try (eval-fn transform-source)
                 (catch Throwable t {::err (.getMessage t)}))]
      (if (or (map? f) (not (fn? f)))
        {:status :rejected
         :rejection-kind :eval-failure
         :reason (str "transform did not evaluate to a (fn [row] …): "
                      (or (::err f) (str "got " (pr-str (type f)))))
         :concept-yield 0
         :rows-tested (count rows)
         :rows-threw (count rows)}
        (let [{:keys [yields threw]}
              (reduce
               (fn [acc row]
                 (let [r (try (f row) (catch Throwable _ ::threw))]
                   (cond
                     (= ::threw r) (update acc :threw inc)
                     (and (map? r) (sequential? (:concept-drafts r)))
                     (update acc :yields + (count (:concept-drafts r)))
                     :else acc)))
               {:yields 0 :threw 0} rows)]
          (cond
            (pos? yields)
            {:status :ok :concept-yield yields :rows-tested (count rows)
             :rows-threw threw}
            ;; the fn THREW on every row (e.g. a bad builtin / wrong shape that
            ;; only blows up at invocation) — a definite fault, not a scope miss.
            (and (pos? threw) (= threw (count rows)))
            {:status :rejected
             :rejection-kind :eval-failure
             :reason (str "the transform THREW on ALL " (count rows) " sampled rows "
                          "(it runs but errors per-row — a wrong builtin, wrong "
                          "contract shape, or bad field access).")
             :concept-yield 0
             :rows-tested (count rows)
             :rows-threw threw}
            :else
            {:status :rejected
             :rejection-kind :empty-yield
             :reason (str "the transform produced EMPTY concept-drafts on ALL "
                          (count rows) " sampled rows — a false-empty. Its field "
                          "access or scope test may be mis-grounded in the real row "
                          "key shape (the keys must match the sampled row verbatim), "
                          "or the sampled window is entirely out of scope.")
             :concept-yield 0
             :rows-tested (count rows)
             :rows-threw threw}))))))

(defn transform-node-prompt
  "PROMOTION SEAM (PRD M6) for the Transform node. DT4 FOCUSED body: a small,
   single-purpose prompt that does ONE job — AUTHOR + sample-VALIDATE the per-row
   extraction TRANSFORM (the V20 shape) that HONORS the model-spec — and emits the
   frozen transform contract. It does NOT re-model, re-profile, or re-decide grain/
   scope (DT2/DT3 own those); it TRANSLATES the model-spec into executable
   per-row code and proves it on a sample before scale.

   This node is where GRAIN + SCOPE actually TAKE EFFECT on the data. The DT3
   Model node DECIDED them; here the transform ENFORCES them per row — the V17/V20
   over-extraction fix made real at extraction time:

     - GRAIN. For a :canonical-row-filter entity, the transform returns EMPTY
       drafts for breakdown/non-canonical rows (so demographic / sub-measure splits
       collapse into the ONE canonical concept and its :attributes — never one
       concept per raw row). For a :breakdown-as-entity entity, the breakdown key
       is folded into the :uri so each breakdown is its own node.
     - SCOPE. If the model-spec carries a :scope-filter, the transform returns
       EMPTY drafts for OUT-OF-SCOPE rows. When the scope field is present in the
       row, test it directly; when the scope field lives in ANOTHER table the row
       does NOT carry, RESOLVE the in-scope key set ONCE during authoring (sample
       the other table with the source tools) and EMBED it as a literal set in the
       transform so the per-row test is pure (the transform runs in a sandbox with
       no tool access — it cannot query at apply time).
     - URI-KEYING. Key each concept's :uri from the model-spec's entity's
       :uri-keying-fields, so the same real entity collapses to ONE node.
     - EDGES. Emit a relationship-draft per the model-spec's :edges between the
       URIs of the entities a row produces.

   The model-spec is read TOLERANTLY (the DT3 carry-forward): :grain-strategy may
   arrive as the bare keyword OR its string form — the prompt tells the node to
   normalize it; every other field may be a map/vector or prose.

   DT4-grounding: the optional `key-shape` (from `sample-row-key-shape` over a
   REAL sample) is rendered as a hard grounding block (`key-shape-block`) that
   names the EXACT row keys + the format's exact access idiom, so the model
   grounds field access verbatim instead of assuming a key name/shape (the
   honest-negative fix). With no key-shape the block is empty and the legacy
   prompt stands — the keys, like the goal, are RUNTIME input, never baked into
   the body (so the prompt body remains domain-agnostic).

   Domain-agnostic (discipline 12): no CIP/SOC/industry/education knowledge — it
   authors a transform for ANY structured source from the model-spec the prior
   node produced. `goal` only orients; the modeling decisions come from the
   model-spec. Orchestration unchanged — returned through the same seam the thin
   DT1 body was."
  ([goal] (transform-node-prompt goal nil))
  ([goal key-shape]
  (str "You are the TRANSFORM step of an ontology-discovery pipeline. Your ONE job "
       "is to AUTHOR a pure per-row extraction transform that ENFORCES the decisions "
       "the prior MODEL step already made, VALIDATE it on a sample, then emit it. "
       "You do NOT re-model, re-profile, or re-decide grain/scope — those are "
       "decided; you TRANSLATE them into code. Make ONLY the transform, then call "
       "(final! {…}).\n\n"
       "GOAL (orients only): " goal "\n\n"
       "INPUT: you are given the MODEL-SPEC as the input key :model-spec (read it "
       "with (get-input :model-spec)). It has :entity-types (each with :type, "
       ":uri-keying-fields, and a :grain-strategy), an optional :scope-filter "
       "{:field … :values […]} (nil = keep everything), and :edges. READ IT "
       "TOLERANTLY: a field may be a map/vector or prose. CRITICAL — :grain-strategy "
       "may arrive as a bare keyword (e.g. :canonical-row-filter) OR as its STRING "
       "form (e.g. \":canonical-row-filter\"); NORMALIZE it (strip a leading colon, "
       "treat the two as equal) before you branch on it. The two strategies are "
       (pr-str (vec valid-grain-strategies)) ".\n\n"
       "WHAT THE TRANSFORM MUST DO (this is the whole job — get grain + scope right "
       "and the source will NOT over-extract):\n"
       "  1. SAMPLE a few real rows from the source (a quick tool call) and LOOK at "
       "the EXACT map a row is. The transform receives each row as the SAME map the "
       "sample tool returns — so access its fields with the EXACT key type the "
       "sample shows (if the sampled row's keys print as :SOME_FIELD they are "
       "KEYWORDS — use (:SOME_FIELD row) or (get row :SOME_FIELD); if they print as "
       "\"SOME_FIELD\" they are STRINGS). Do NOT assume — copy the key form from the "
       "real sampled row. A key-type mismatch silently yields nil for EVERY row and "
       "extracts NOTHING (a 0-concept false-empty — the failure to avoid).\n"
       "  2. AUTHOR a (fn [row] {:concept-drafts [...] :relationship-drafts [...]}):\n"
       "     - SCOPE: if :scope-filter is non-nil, return {:concept-drafts [] "
       ":relationship-drafts []} for rows OUTSIDE the scope. If the scope field is "
       "IN the row, test it directly. If the scope field is NOT in the row (it lives "
       "in another table the row doesn't carry), resolve the in-scope key set NOW "
       "during authoring by ACTUALLY RUNNING a tool query against the OTHER table "
       "for the COMPLETE set of in-scope identifying values (e.g. select the id "
       "field where the scope field matches the goal's value). Use the REAL VALUES "
       "the query RETURNS — NEVER invent, guess, or hand-type id values you did not "
       "get back from a tool call (fabricated ids match nothing and silently extract "
       "ZERO). Get them ALL (not just one sample window, or you scope to a tiny "
       "fraction). EMBED that returned set as a literal (e.g. a closed-over #{…}) so "
       "the per-row test is PURE — the transform CANNOT "
       "call tools at apply time. Use the SAME key/value TYPE for the set members "
       "that the per-row field will have (e.g. if the row's key is an integer, store "
       "integers, or stringify both sides consistently).\n"
       "     - GRAIN: for a :canonical-row-filter entity, return EMPTY drafts for "
       "non-canonical / breakdown rows (keep ONE concept per entity; fold the row's "
       "measures into that concept's :attributes — never one concept per raw row). "
       "For a :breakdown-as-entity entity, FOLD the breakdown key into the :uri so "
       "each breakdown becomes its own node.\n"
       "     - URI: key each concept's :uri from that entity's :uri-keying-fields "
       "(join the row's values for those fields) so the same real entity collapses "
       "to ONE node.\n"
       "     - EDGES: for each :edges entry, emit a relationship-draft between the "
       "URIs of the source-type and target-type concepts the row produced.\n"
       "  3. VALIDATE on the REAL sampled rows (not a hand-typed copy): map your fn "
       "over the ACTUAL row maps the sample tool returned and CONFIRM the result is "
       "sane — AT LEAST ONE in-scope row must produce a NON-EMPTY concept-draft "
       "carrying a :uri + :label, and out-of-scope / breakdown rows produce empty "
       "drafts. If EVERY sampled in-scope row comes back empty, your field-key access "
       "or scope test is wrong (the key-type trap above) — FIX it and re-validate. "
       "Only finalize once the sample actually produces drafts.\n\n"
       "SANDBOX: the transform is eval'd in a restricted sandbox — clojure.core / "
       "clojure.string / clojure.set ONLY; NO Java interop, NO tool calls inside the "
       "fn. Do all sampling/scope-resolution BEFORE the fn and bake the result into "
       "the source.\n\n"
       "EVERY concept-draft MUST carry BOTH a :uri and a :label (a human-readable "
       "name — from the row's name/title field if present, else from the identifying "
       "value), plus an :evidence vector with a verbatim quote from the row; carry "
       "the row's measures as :attributes. EVERY relationship-draft MUST carry "
       ":source-uri, :target-uri, and :predicate. A draft missing :uri or :label is "
       "rejected downstream — emit both for every draft (including any node an edge "
       "points to)."
       (key-shape-block key-shape)
       (contract-block
        transform-contract-keys
        "  :transform-source — the (fn [row] {:concept-drafts [...] :relationship-drafts [...]}) AS A STRING of Clojure source, scope+grain enforced, sample-validated\n  :selector         — the EXACT table/sheet name whose ROWS the transform consumes (the same table/sheet you SAMPLED above, so the apply-step streams the right table). For sql this is the table NAME (e.g. the table you sampled), NOT a function name and NOT `identity`. Omit (or nil) for a csv source."))))

;; =============================================================================
;; node-output — the inter-node contract READ mechanism
;; =============================================================================
;; PRD M2: nodes read their predecessor's output via the existing drill-down
;; primitives (node-output / node-input-profile). In this orchestration the
;; blackboard IS the channel between nodes; `node-output` is the read accessor
;; that pulls a named node's emitted contract off the blackboard — the same
;; affordance the RLM `(node-output node-id)` primitive provides inside a tree,
;; surfaced here at the orchestration level so a downstream node (or a test, or a
;; verification reviewer per user-story 25) reads exactly what a node produced.

(defn node-output
  "Read the contract output a named node emitted, off the discovery blackboard.

   This is the orchestration-level analogue of the RLM `(node-output node-id)`
   drill-down primitive (RLM-GUIDE): given the blackboard and a node key
   (`:profile` / `:model` / `:transform`), return that node's emitted
   contract map (the frozen PRD M2 shape) — or nil if the node hasn't run.

   It is how DT2/DT3/DT4 read a predecessor's output, and how a verification
   reviewer reads each node's output step-by-step (PRD user-story 25)."
  [blackboard node-key]
  (get-in blackboard [node-key :output]))

;; =============================================================================
;; Per-medium tool-leaf (Discipline #8 reuse — V06/V19 via run-discovery!)
;; =============================================================================
;; One tree shape, per-medium leaves: the SAME three steps run for csv / sql /
;; excel / text; only the bound tools differ. We do NOT reimplement the tool
;; binding — we reuse `run-discovery!`'s proven `:granted-source` seam, which
;; threads the V06 per-format source-access tools (V19 schema/sample/query/
;; stream) into the executor and augments the node prompt with that format's
;; exploration guidance. A node runs as a one-shot recursive-RLM session via the
;; same executor entry `run-discovery!` uses.

(defn- run-node!
  "Run ONE thin reasoning node as a recursive-RLM `:repl-researcher` session.

   The medium's specialist tools (V06/V19) are bound by REUSING the discovery
   stack: we hand the node's prompt + the structured source to
   `rlm-discovery/run-node-session!`, which constructs the synthetic recursive-
   RLM node (with `:granted-source` for the source's format) and executes it
   through the same executor `run-discovery!` uses. The node's declared writes
   are exactly its frozen contract keys, so its `(final! ...)` output IS the
   contract.

   Returns `{:status :ok :output <contract-map> :usage ... :session <raw>}` on a
   successful emission, or `{:status :failed :error <msg> :session <raw>}` — the
   orchestrator surfaces a node failure honestly (no false green)."
  [ctx {:keys [node-key prompt source contract-keys extra-inputs
               focused-prompt? model budget debug?]}]
  (let [result (rlm-discovery/run-node-session!
                ctx
                {:node-name node-key
                 :instruction prompt
                 :source source
                 :writes contract-keys
                 :extra-inputs extra-inputs
                 :focused-prompt? focused-prompt?
                 :model model
                 :budget budget
                 :debug? debug?})]
    result))

;; =============================================================================
;; Branch points — NAMED STUBS (PRD M1; filled by DT8/DT9)
;; =============================================================================
;; These exist as explicit, named, no-op functions so later slices fill them in
;; place WITHOUT restructuring the spine. Each is invoked at its branch point in
;; `run-discovery-tree!` and returns `{:branch <name> :taken? false :reason
;; :stub-not-yet-implemented}` today. A test asserts they are present + named.

(defn recovery-branch-stub
  "BRANCH STUB (DT8) — focused single-node recovery. When a node in the sequence
   fails, DT8 will re-run just that node reading the surviving blackboard vars +
   the failure (the self-improving-loop focused-failure-recovery pattern). DT1:
   no-op — a node failure is surfaced honestly to the caller, not recovered."
  [_ctx {:keys [failed-node error]}]
  {:branch :recovery
   :taken? false
   :reason :stub-not-yet-implemented
   :failed-node failed-node
   :error error})

(defn cq-reextract-branch-stub
  "BRANCH STUB (DT8) — CQ-driven re-extract loop. When the build!'s CQ verdict
   FAILS, DT8 will inspect the failing CQ + graph-health (drill-down), trace it
   to the node whose output the gap traces to, FOCUSED re-extract/re-link, and
   re-gate — or surface an honestly-unanswerable CQ + terminate. DT1: no-op —
   the CQ verdict is read + surfaced; no loop runs."
  [_ctx {:keys [build-status graph-health]}]
  {:branch :cq-reextract
   :taken? false
   :reason :stub-not-yet-implemented
   :build-status build-status
   :graph-health graph-health})

(defn greenfield-vs-maintain-branch-stub
  "BRANCH STUB (DT9) — greenfield vs maintain. Greenfield is the BUILT arm (DT1
   builds into a fresh ontology). DT9 will add the maintain/incremental arm
   (deferred — see the maintain handoff); M5's reconcile-against-current-graph-
   state seam is what makes maintain a clean drop-in later. DT1: always
   greenfield."
  [_ctx {:keys [mode]}]
  {:branch :greenfield-vs-maintain
   :taken? false
   :reason :stub-not-yet-implemented
   :selected (or mode :greenfield)})

(defn full-extract-vs-inline-branch-stub
  "BRANCH STUB — full-extract vs inline. For a source SMALL enough that the
   sample already covers every row, the model's sample drafts stand and the V20
   full-extraction apply-step is skipped. A later slice decides this from the
   profile's row-count signal. DT1: always full-extract (the V20 path) when the
   node hands back a transform."
  [_ctx {:keys [row-count sample-covers?]}]
  {:branch :full-extract-vs-inline
   :taken? false
   :reason :stub-not-yet-implemented
   :selected :full-extract
   :row-count row-count
   :sample-covers? sample-covers?})

;; =============================================================================
;; Graph-level orchestration — the fixed-core per-source sub-tree
;; =============================================================================

(defn run-discovery-tree!
  "DT1 — run the discovery behavior tree for ONE source, end-to-end:

     Profile → Model → Transform
       → [V20 deterministic full-extraction apply-step]
       → build!  (the intact deterministic skeleton)
       → read the CQ verdict back onto the result.

   The three reasoning nodes run as a FIXED sequence the orchestrator drives
   deterministically — each step is structurally guaranteed (you cannot skip the
   grain/scope step; it is its own node with the profile as its explicit input).
   Each node's output is the frozen PRD M2 contract, carried on the blackboard
   and read by the next node via `node-output`.

   Required `params`:
     :ontology-id  — the granted scope (REQUIRED).
     :source       — ONE structured source descriptor
                     `{:name <kw> :type :csv|:sql|:excel :path <str>}`.
     :goal         — the domain goal/intent that orients every node (a string).

   Optional `params`:
     :model        — OpenRouter model id. Default gemini-3-flash-preview.
     :budget       — per-node `{:max-iterations N :total-budget-ms N :max-retries N}`.
     :judge-fn     — passed to build!'s CQ stage when the spec has non-Layer-1 CQs.
     :exit-criterion — overrides build!'s default CQ gate.
     :debug?       — debug logging on each node session.

   Returns:
     {:status      :complete | :failed-cq | :failed-at-<stage>
                   | :failed-at-profile | :failed-at-model | :failed-at-transform
      :ontology-id <uuid>
      :goal <str>
      :blackboard {:profile {:output <profile-contract>} ...}   ; the inter-node contract, verbatim
      :nodes-run [:profile :model :transform]
      :full-extraction <V20 coverage report — nil when no transform / inline>
      :build-result <the verbatim build! result>
      ;; surfaced from build! (PRD M7 — the build!-CQ boundary):
      :build-status :graph-health :exit-criterion
      :branch-points {<branch-name> <stub result>}            ; the named stubs, evaluated as no-ops
      }

   A node failure (profile/model/transform) surfaces honestly as
   :failed-at-<node> with the node's session error — no fabricated downstream
   steps run (Discipline #5; no false green). `build!` is invoked UNCHANGED."
  [ctx {:keys [ontology-id source goal model budget judge-fn exit-criterion debug?]
        :or {model "google/gemini-3-flash-preview"}}]
  (when-not ontology-id
    (throw (ex-info "run-discovery-tree! requires :ontology-id (the granted scope)"
                    {:params {:ontology-id ontology-id}})))
  (when-not (and (map? source) (:type source) (:path source))
    (throw (ex-info "run-discovery-tree! requires :source {:type :csv|:sql|:excel :path <str>}"
                    {:source source})))
  (when-not (and (string? goal) (seq goal))
    (throw (ex-info "run-discovery-tree! requires :goal (a non-blank string that orients every node)"
                    {:goal goal})))

  (let [;; Thread the granted scope onto ctx so each node session's S19/S20
        ;; wiring (graph tools + orientation card) is scoped to this ontology.
        ;; `run-node-session!` reads `:granted-ontology-id` off ctx.
        ctx (assoc ctx :granted-ontology-id ontology-id :ontology-id ontology-id)

        ;; The greenfield-vs-maintain branch is decided FIRST (DT9 stub): DT1 is
        ;; always greenfield (build into the supplied ontology-id).
        gf-branch (greenfield-vs-maintain-branch-stub ctx {:mode :greenfield})

        ;; --- Node 1: PROFILE (structurally guaranteed first step) ---
        ;; DT2: the Profile node is FOCUSED — its prompt is used verbatim
        ;; (:focused-prompt? true) so the retired mega-prompt's modeling / grain /
        ;; scope / transform guidance is NOT prepended. The per-medium tool
        ;; catalog is assembled inside profile-node-prompt from the source format.
        profile-r (run-node! ctx {:node-key :profile
                                  :prompt (profile-node-prompt goal (:type source))
                                  :source source
                                  :contract-keys profile-contract-keys
                                  :focused-prompt? true
                                  :model model :budget budget :debug? debug?})]
    (if (not= :ok (:status profile-r))
      {:status :failed-at-profile
       :ontology-id ontology-id :goal goal
       :nodes-run [:profile]
       :error (:error profile-r)
       :branch-points {:greenfield-vs-maintain gf-branch
                       :recovery (recovery-branch-stub ctx {:failed-node :profile
                                                            :error (:error profile-r)})}
       :session (:session profile-r)}

      (let [bb {:profile {:output (:output profile-r)}}

            ;; --- Node 2: MODEL+grain+scope (reads the profile via the blackboard) ---
            ;; DT3: the Model node is FOCUSED — its prompt is used verbatim
            ;; (:focused-prompt? true) so the retired mega-prompt's profiling /
            ;; transform / coverage guidance is NOT prepended. The focused body
            ;; makes ONLY the grain + scope + entity decision (the V17/V20
            ;; over-extraction fix as a guaranteed step). The granted source-access
            ;; tools are still bound via :granted-source so the node can sample a
            ;; row to confirm a field name without re-profiling.
            model-r (run-node! ctx {:node-key :model
                                    :prompt (model-node-prompt goal)
                                    :source source
                                    :contract-keys model-contract-keys
                                    :extra-inputs {:profile (node-output bb :profile)}
                                    :focused-prompt? true
                                    :model model :budget budget :debug? debug?})]
        (if (not= :ok (:status model-r))
          {:status :failed-at-model
           :ontology-id ontology-id :goal goal
           :nodes-run [:profile :model]
           :blackboard bb
           :error (:error model-r)
           :branch-points {:greenfield-vs-maintain gf-branch
                           :recovery (recovery-branch-stub ctx {:failed-node :model
                                                                :error (:error model-r)})}
           :session (:session model-r)}

          (let [bb (assoc bb :model {:output (:output model-r)})

                ;; --- DT4-grounding: surface the REAL sampled-row key shape ---
                ;; The honest-negative fix. Sample REAL rows DIRECTLY from the
                ;; source via the SAME per-medium tools the V20 apply-step streams
                ;; through (mechanical-sample-rows) — NOT the profile node's emitted
                ;; :sample, which an LLM may re-key (the CSV grounding-miss). The
                ;; exact row-key shape is then injected into the transform prompt so
                ;; the model grounds field access verbatim instead of assuming a key
                ;; name/shape, and the SAME rows feed the sample-validation gate.
                ;; Mechanical + domain-agnostic — the keys come from the runtime
                ;; sample, never baked in. Empty when the source can't be sampled
                ;; (the prompt then stands without a grounding block; no false shape).
                grounding-rows (mechanical-sample-rows
                                {:type (:type source) :path (:path source)})
                key-shape (sample-row-key-shape
                           source (or (seq grounding-rows)
                                      (:sample (node-output bb :profile))))

                ;; --- Node 3: TRANSFORM-design (reads the model-spec via the blackboard) ---
                ;; DT4: the Transform node is FOCUSED — its prompt is used verbatim
                ;; (:focused-prompt? true) so the retired mega-prompt's profiling /
                ;; modeling / scope-DECISION guidance is NOT prepended. The focused
                ;; body's WHOLE job is authoring + sample-validating the per-row
                ;; transform that ENFORCES the model-spec's grain + scope (the V17/
                ;; V20 over-extraction fix made real at extraction time). The granted
                ;; source-access tools are still bound via :granted-source so the
                ;; node can sample rows (and any cross-table scope key set) before it
                ;; bakes them into the pure transform source.
                transform-r (run-node! ctx {:node-key :transform
                                            :prompt (transform-node-prompt goal key-shape)
                                            :source source
                                            :contract-keys transform-contract-keys
                                            :extra-inputs {:model-spec (node-output bb :model)}
                                            :focused-prompt? true
                                            :model model :budget budget :debug? debug?})]
            (if (not= :ok (:status transform-r))
              {:status :failed-at-transform
               :ontology-id ontology-id :goal goal
               :nodes-run [:profile :model :transform]
               :blackboard bb
               :error (:error transform-r)
               :branch-points {:greenfield-vs-maintain gf-branch
                               :recovery (recovery-branch-stub ctx {:failed-node :transform
                                                                    :error (:error transform-r)})}
               :session (:session transform-r)}

              (let [bb (assoc bb :transform {:output (:output transform-r)})
                    transform-out (node-output bb :transform)
                    transform-source (:transform-source transform-out)
                    selector (:selector transform-out)

                    ;; --- DT4-grounding: sample-validation gate (BEFORE full-scale apply) ---
                    ;; Assert the authored transform yields NON-EMPTY drafts on REAL
                    ;; rows of the table/sheet the transform ACTUALLY targets (its
                    ;; own :selector — so the validation rows match what the apply
                    ;; step streams), catching a mis-grounded (false-empty) transform
                    ;; HERE rather than after streaming the full source to a
                    ;; 0-concept dump. Falls back to the grounding sample when the
                    ;; selector yields nothing. Only runs when we have real sample
                    ;; rows AND a transform source — else it would false-fail; the
                    ;; V20 apply still surfaces a true empty.
                    validation-rows
                    (or (seq (mechanical-sample-rows
                              {:type (:type source) :path (:path source)} selector 100))
                        (seq grounding-rows))
                    sample-validation
                    (when (and (seq validation-rows)
                               (string? transform-source)
                               (seq (str/trim transform-source)))
                      (validate-transform-on-sample transform-source (vec validation-rows)))
                    ;; Hard-block ONLY a DEFINITE fault (the transform does not
                    ;; evaluate, or throws on every row — e.g. a JS builtin, an
                    ;; unresolved symbol, a wrong contract shape). An :empty-yield
                    ;; is NOT hard-blocked: a small window may legitimately be all
                    ;; out-of-scope, and the V20 apply surfaces the true full-scale
                    ;; count honestly (no false green either way). It is recorded.
                    validation-fatal?
                    (= :eval-failure (:rejection-kind sample-validation))

                    ;; --- full-extract-vs-inline branch (DT1 stub: always full-extract) ---
                    fx-branch (full-extract-vs-inline-branch-stub
                               ctx {:row-count nil :sample-covers? false})

                    ;; --- [V20] deterministic full-extraction apply-step (REUSED UNCHANGED) ---
                    ;; The Transform node authored the transform; this stage APPLIES it
                    ;; over the WHOLE source (V19 stream-all), per PRD M4. A transform
                    ;; that fails to EVALUATE or a bad selector is a real fault — we
                    ;; capture it as ::extract-error so it surfaces honestly as
                    ;; :failed-at-extract below (no false green, no silent sample
                    ;; fallback — Discipline #5). SKIPPED only when the gate found a
                    ;; DEFINITE fault (eval-failure) — we do not stream the full
                    ;; source to evaluate a transform that cannot run. An empty-yield
                    ;; is allowed through; the apply surfaces the true count honestly.
                    full-extraction
                    (when (and (string? transform-source)
                               (seq (str/trim transform-source))
                               (not validation-fatal?))
                      (try
                        (rlm-discovery/apply-extraction-transform!
                         {:descriptor {:type (:type source) :path (:path source)}
                          :selector selector
                          :transform-source transform-source})
                        (catch Throwable t
                          {::extract-error (.getMessage t)})))
                    extract-error (::extract-error full-extraction)]
                (if validation-fatal?
                  ;; The authored transform is a DEFINITE fault (does not evaluate /
                  ;; throws on every row) — surface honestly at authoring time, do
                  ;; NOT run the full apply or fake a pass (Discipline #5; the
                  ;; honest-negative fix).
                  {:status :failed-at-transform-validation
                   :ontology-id ontology-id :goal goal
                   :nodes-run [:profile :model :transform]
                   :blackboard bb
                   :sample-validation sample-validation
                   :error (str "transform sample-validation failed: "
                               (:reason sample-validation))
                   :branch-points {:greenfield-vs-maintain gf-branch
                                   :full-extract-vs-inline fx-branch
                                   :recovery (recovery-branch-stub
                                              ctx {:failed-node :transform
                                                   :error (:reason sample-validation)})}}

                (if extract-error
                  ;; The transform failed to evaluate / stream — surface honestly.
                  {:status :failed-at-extract
                   :ontology-id ontology-id :goal goal
                   :nodes-run [:profile :model :transform]
                   :blackboard bb
                   :error (str "extraction-transform failed: " extract-error)
                   :branch-points {:greenfield-vs-maintain gf-branch
                                   :full-extract-vs-inline fx-branch
                                   :recovery (recovery-branch-stub
                                              ctx {:failed-node :extract
                                                   :error extract-error})}}

                  (let [;; Emit the extracted drafts as events through the existing
                        ;; commands (Grain discipline: commands → schema-validated
                        ;; events). We reuse compile-discovery-source! by handing it a
                        ;; run-discovery!-shaped output carrying the full-extraction
                        ;; draft set — no fork of the compile path (Discipline #8).
                        ;;
                        ;; compile validates each draft STRICTLY (a draft missing
                        ;; :uri/:label raises — correct: a malformed draft is a real
                        ;; fault, NOT silently dropped). When a THIN DT1 transform
                        ;; emits an imperfect draft, that surfaces here as a clean
                        ;; :failed-at-compile status rather than crashing the run
                        ;; (no false green; the draft-quality fix is the focused
                        ;; Transform node's job — DT4 — not a relaxed validator).
                        compile-outcome
                        (when full-extraction
                          (try
                            {:ok (rlm-discovery/compile-discovery-source!
                                  ctx ontology-id
                                  {:status :emitted-drafts
                                   :emitted-concepts (vec (:concept-drafts full-extraction))
                                   :emitted-relationships (vec (:relationship-drafts full-extraction))
                                   :emitted-axioms []
                                   :rlm-trace []})}
                            (catch Throwable t
                              {:error (.getMessage t) :data (ex-data t)})))]
                    (if (and compile-outcome (:error compile-outcome))
                      {:status :failed-at-compile
                       :ontology-id ontology-id :goal goal
                       :nodes-run [:profile :model :transform]
                       :blackboard bb
                       :full-extraction (dissoc full-extraction :concept-drafts
                                                :relationship-drafts)
                       :error (str "compile of extracted drafts failed: "
                                   (:error compile-outcome))
                       :error-data (:data compile-outcome)
                       :branch-points {:greenfield-vs-maintain gf-branch
                                       :full-extract-vs-inline fx-branch
                                       :recovery (recovery-branch-stub
                                                  ctx {:failed-node :compile
                                                       :error (:error compile-outcome)})}}

                      (let [compiled (:ok compile-outcome)

                        ;; --- build! — the intact deterministic skeleton (INVOKED UNCHANGED) ---
                        ;; PRD M7: the tree owns the loop; build! stays a deterministic
                        ;; sub-call. The drafts already landed as events via compile, so
                        ;; build! sees a single zero-concept :inline-concepts source and
                        ;; runs normalize → dedup → validate → embed → index → CQ over the
                        ;; graph the discovery nodes built.
                        build-result
                        (skeleton/build!
                         ctx
                         (cond-> {:ontology-id ontology-id
                                  :sources [{:type :inline-concepts :concepts []}]}
                           judge-fn       (assoc :judge-fn judge-fn)
                           exit-criterion (assoc :exit-criterion exit-criterion)))

                        ;; --- read the CQ verdict back (PRD M7) ---
                        cq-branch (cq-reextract-branch-stub
                                   ctx {:build-status (:status build-result)
                                        :graph-health (:graph-health build-result)})]
                    {:status (:status build-result)
                     :ontology-id ontology-id
                     :goal goal
                     :blackboard bb
                     :nodes-run [:profile :model :transform]
                     :full-extraction (when full-extraction
                                        (dissoc full-extraction :concept-drafts
                                                :relationship-drafts))
                     :compile-provenance (:discovery-provenance compiled)
                     :build-result build-result
                     :build-status (:status build-result)
                     :graph-health (:graph-health build-result)
                     :exit-criterion (:exit-criterion build-result)
                     :referential-integrity (:referential-integrity build-result)
                     :concepts-count (:concepts-count build-result)
                     :relationships-count (:relationships-count build-result)
                     :sample-validation sample-validation
                     :branch-points {:greenfield-vs-maintain gf-branch
                                     :full-extract-vs-inline fx-branch
                                     :cq-reextract cq-branch
                                     :recovery (recovery-branch-stub
                                                ctx {:failed-node nil :error nil})}})))))))))))))

;; =============================================================================
;; Composable behavior-tree node (PRD user-story 24)
;; =============================================================================
;; The discovery tree is itself a first-class workflow primitive — composable as
;; a node inside a larger behavior tree. We expose it as a `:code`-style leaf
;; descriptor that wraps `run-discovery-tree!`: the orchestration runs when the
;; node ticks, and the result (including the CQ verdict) is written to the
;; node's declared write key. A parent sequence/fallback/parallel composes it
;; like any other leaf.

(defn discovery-tree-node
  "Build a composable behavior-tree node descriptor that runs the discovery tree
   for one source and writes its result to `write-key`.

   Returns a `:code` leaf descriptor `{:node-type :code :name ... :reads [...]
   :writes [write-key] :fn (fn [{:keys [inputs ctx]}] ...)}` whose `:fn` invokes
   `run-discovery-tree!` with the node's configured source/goal and the runtime
   ctx. This makes discovery a node a larger behavior tree can sequence (PRD
   user-story 24) — the same way `:repl-researcher` composes inside a tree
   (RLM-GUIDE 'Composition').

   `config` carries the static `:source` / `:goal` / `:model` / `:budget`; the
   runtime ctx + ontology-id are supplied as `:inputs` when the node ticks."
  [{:keys [name write-key reads source goal model budget]
    :or {name :discovery-tree
         write-key :discovery-result
         reads [:ontology-id]}}]
  {:node-type :code
   :name name
   :reads reads
   :writes [write-key]
   :fn (fn [{:keys [inputs ctx]}]
         (let [oid (or (:ontology-id inputs) (get inputs "ontology-id"))]
           {write-key
            (run-discovery-tree!
             ctx
             (cond-> {:ontology-id oid
                      :source (or source (:source inputs))
                      :goal (or goal (:goal inputs))}
               model  (assoc :model model)
               budget (assoc :budget budget)))}))})
