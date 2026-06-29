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
   orchestration (the prompt for each node goes through the DT6
   `assemble-node-prompt` promotion seam, PRD M6 — static now, a clean flip to a
   living-behavior source later)."
  (:require [ai.obney.orc.ontology.core.rlm-discovery :as rlm-discovery]
            [ai.obney.orc.ontology.core.deterministic-skeleton :as skeleton]
            [ai.obney.orc.ontology.core.dedup-cascade :as dedup]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.time.interface :as time]
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

(defn- contract-for
  "Resolve a source descriptor to its MC-1 uniform container-contract surface
   (lazy-resolved from orc-service, same discipline as the executor resolution).
   Returns the contract map ({:format :list-containers :sample-rows :stream-all
   :relations}) or nil for a `:text` / unresolvable descriptor."
  [descriptor]
  (let [cc (requiring-resolve
            'ai.obney.orc.orc-service.core.source-tools/container-contract)]
    (cc {:type (:type descriptor) :format (:format descriptor)
         :path (:path descriptor)})))

(defn default-container
  "Pick a DEFAULT container for grounding when the caller gave no selector, from
   the contract's `:list-containers`. Domain-agnostic + format-agnostic: for a
   source whose containers expose row counts (sql tables, via `count-rows`) it
   picks the LARGEST (the data container is the extraction target far more often
   than a tiny lookup table); otherwise it picks the deterministic FIRST container
   (csv's single file; excel's first sheet across the dir/workbook, sorted by file
   name). When a selector IS given, resolve it to the matching container (by
   :name) so the contract's per-row addressing (excel: the :path+:sheet) is
   carried.

   `contract` is the container-contract map; `selector` is a name string, a
   descriptor map, or nil; `tools` is the raw per-format tool map (for the sql
   `count-rows` largest-container pick). Returns ONE container map (a
   `:list-containers` entry) or nil when the source lists no containers."
  [contract selector tools]
  (let [containers (try ((:list-containers contract)) (catch Throwable _ nil))
        sel-name   (cond (string? selector) selector
                         (map? selector) (or (:name selector) (:table selector)
                                             (:sheet selector))
                         :else nil)]
    (cond
      (empty? containers) nil
      ;; an explicit selector resolves to the matching container (carrying its
      ;; medium-specific addressing) — fall back to a bare-name container when the
      ;; listing doesn't include it (e.g. a sql table the listing wrapped plainly).
      sel-name (or (first (filter #(= sel-name (:name %)) containers))
                   {:name sel-name})
      ;; no selector + a count-rows tool (sql) → the largest container.
      (and (map? tools) (fn? (get tools 'count-rows)))
      (let [count-rows (get tools 'count-rows)]
        (->> containers
             (map (fn [c] [c (try (:row-count (count-rows (:name c)))
                                  (catch Throwable _ 0))]))
             (sort-by second >)
             ffirst))
      ;; otherwise the deterministic first container (csv single file; excel first
      ;; sheet — list-containers is already sorted by file name).
      :else (first containers))))

(defn mechanical-sample-rows
  "Pull a small set of REAL rows DIRECTLY from the source via the MC-1 uniform
   CONTAINER CONTRACT — the SAME `:sample-rows` surface the V20 apply-step's
   `:stream-all` shares, so the row shape returned is EXACTLY what the transform
   will see at apply time. This is the authoritative key-shape source: it does
   NOT trust the profile node's emitted `:sample` (an LLM may re-key / stringify
   it), it reads the medium's tools directly. Container-agnostic (MC-4): CSV / SQL
   / EXCEL are ONE code path — there is no per-format branch and no `:else []`.

   `descriptor` is `{:type :csv|:sql|:excel :path <str>}`. `selector` is the
   container (table/sheet) name when known; when no selector is given we pick a
   DEFAULT container from `:list-containers` — the LARGEST for a source with row
   counts (sql tables: the data container is the extraction target far more often
   than a tiny lookup table), else the deterministic FIRST container (csv's single
   file; excel's first sheet). Returns a vector of real row maps (capped small),
   or [] when the source can't be sampled (the caller then falls back / renders no
   grounding block — never a fabricated shape). Domain-agnostic: it reads keys,
   names no field."
  ([descriptor] (mechanical-sample-rows descriptor nil 5))
  ([descriptor selector] (mechanical-sample-rows descriptor selector 5))
  ([descriptor selector n]
   (try
     (let [tools-for (requiring-resolve
                      'ai.obney.orc.orc-service.core.source-tools/source-tools-for)
           tools (tools-for {:type (:type descriptor) :format (:format descriptor)
                             :path (:path descriptor)})
           contract (contract-for descriptor)]
       (if-not (and (map? contract) (fn? (:sample-rows contract)))
         []
         (let [container (default-container contract selector
                                            (when (map? tools) tools))]
           (if (nil? container)
             []
             (map-rows-from-sample ((:sample-rows contract) container {:limit n}))))))
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
       "to ONE node. NOTE: this :uri is now only a SOFT HINT — a deterministic "
       "post-step re-derives each concept's canonical identity from its "
       ":entity-type + that type's :uri-keying-fields VALUES, so getting the :uri "
       "format exactly right is no longer load-bearing; what IS load-bearing is "
       "(a) carrying the keying-field VALUES in :attributes and (b) tagging the "
       "concept with its :entity-type (next bullet).\n"
       "     - ENTITY-TYPE (REQUIRED for canonical identity): tag EVERY "
       "concept-draft with :entity-type set to the model-spec :type this concept IS "
       "(the entity-types entry whose :uri-keying-fields you keyed the :uri from). "
       "Your transform already KNOWS this — it is the entity it decided the row "
       "produces. The post-step uses :entity-type to look up the right "
       ":uri-keying-fields and mint ONE canonical URI per real entity across every "
       "container, so the SAME entity in two tables/sheets collapses to one node. A "
       "concept-draft with no :entity-type keeps its raw :uri (no canonical "
       "identity) — so DO tag it.\n"
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
       "EVERY concept-draft MUST carry a :uri, a :label (a human-readable "
       "name — from the row's name/title field if present, else from the identifying "
       "value), AND an :entity-type (the model-spec :type this concept is — used for "
       "canonical identity), plus an :evidence vector with a verbatim quote from the "
       "row; carry the row's keying-field VALUES + measures as :attributes (the "
       "canonical-URI post-step recovers the keying VALUES from :attributes, so the "
       "field(s) you key identity on MUST appear there). ALSO carry the LINKING-KEY "
       "VALUES: if the model-spec carries a :linking-keys vector (cross-source code/"
       "key COLUMN NAMES that identify the SAME entity in OTHER sources), put EACH "
       "such column's VALUE from the row into :attributes under that column's name — "
       "in ADDITION to the keying fields — so the cross-source spine can recover the "
       "linking VALUE later. A linking key is OFTEN a DIFFERENT column from the "
       "entity's own keying field, so carrying the keying field alone is NOT enough; "
       "carry the linking column(s) too. If a row genuinely lacks a linking column "
       "(it is not in the real row keys above), carry NO value for it — do NOT "
       "fabricate one. EVERY relationship-draft MUST carry "
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
;; DT5 — graph-level Requirements / competency-question node (PRD M3 + M7)
;; =============================================================================
;; A GRAPH-LEVEL node that runs AFTER every source has been profiled. It derives
;; competency questions (CQs) from the GOAL ⨯ the profiles — grounded in what the
;; sources actually contain (the profiles' entity-candidates / keys / fields) AND
;; goal-anchored (testing what the graph SHOULD answer per the goal, not merely
;; what was extracted, so the exit gate is not self-fulfilling). The derived CQs
;; are persisted as the S14 ORSD spec the S15 exit-criterion judges (PRD M7), and
;; surfaced for HITL review/override. A consumer-supplied CQ set seeds/overrides
;; the derived set (when CQs are supplied, derivation is skipped/merged).
;;
;; The CQ prompt carries NO domain knowledge (discipline 12): the CQs come from
;; the runtime goal + the profiles. The node reads profiles TOLERANTLY (the DT2
;; value-shape variance: a profile field may be prose strings OR maps/vectors).

(def cq-contract-keys
  "The frozen Requirements/CQ-node output contract (PRD M3). The node emits the
   derived competency questions (a vector of natural-language question strings) —
   the load-bearing exit-gate questions S15 judges — plus the rationale that ties
   each back to the goal + the profiles (so the HITL reviewer can see WHY each was
   derived). Only `:competency-questions` persists into the ORSD spec; the
   rationale is surfaced for review, not gated on."
  [:competency-questions :rationale])

(defn cq-node-prompt
  "PROMOTION SEAM (PRD M6) for the graph-level Requirements/CQ node. DT5 FOCUSED
   body: a small, single-purpose prompt that does ONE job — derive COMPETENCY
   QUESTIONS from the GOAL ⨯ the source profiles — and emits the frozen CQ
   contract. It does NOT profile, model, transform, or extract; it reasons over
   the ALREADY-PRODUCED profiles + the goal to author the questions the finished
   graph must be able to answer.

   The two load-bearing properties (both required, in tension — this is the whole
   job):

     - GROUNDED: each CQ must be answerable IN PRINCIPLE from what the profiles
       show the sources actually CONTAIN — the entity-candidates, identifying/
       linking keys, scope fields, and grain the profiles surfaced. A CQ about a
       thing no profile mentions is ungrounded; do not author it.
     - GOAL-ANCHORED: the CQs test what the graph SHOULD answer to satisfy the
       GOAL — not merely what happens to get extracted. This keeps the exit gate
       from being self-fulfilling: the questions come from the goal's intent,
       checked against what the sources can support, NOT read back off the
       extraction. Prefer questions that exercise the goal's core relationships /
       connections across sources, not trivial single-field lookups.

   The profiles are read TOLERANTLY (the DT2 carry-forward): any profile field may
   arrive as a prose STRING or as a structured map/vector — handle whichever.

   Domain-agnostic (discipline 12): no CIP/SOC/industry/education knowledge — the
   CQs are derived for ANY set of profiled sources from the runtime goal + the
   profiles. `goal` is the ONLY domain reference. Orchestration unchanged — this
   body is returned through the same seam the focused DT2/DT3/DT4 bodies are."
  [goal]
  (str "You are the REQUIREMENTS step of an ontology-discovery pipeline. Your ONE "
       "job is to derive the COMPETENCY QUESTIONS the finished graph must be able "
       "to answer — the natural-language questions that, once the graph is built, "
       "are the acceptance test for whether discovery achieved the goal. You do "
       "NOT profile, model, transform, or extract; the sources are ALREADY "
       "profiled. Reason over the goal + the profiles, then call (final! {…}).\n\n"
       "GOAL (the questions test whether the graph satisfies THIS): " goal "\n\n"
       "INPUT: you are given the PROFILES of every source as the input key "
       ":profiles (read it with (get-input :profiles)) — a vector of per-source "
       "profile maps. Each profile characterizes one source: its entity-candidates "
       "(the real-world things its rows describe), identifying-keys (the field(s) "
       "that name one instance), scope-fields (fields that could narrow it), "
       "linking-keys (shared codes that connect it to OTHER sources), grain-signals "
       "(where rows are finer than entities), and a sample of real rows. READ THEM "
       "TOLERANTLY: any field may be a prose STRING or a structured map/vector — "
       "handle whichever you get; never assume a rigid shape.\n\n"
       "DERIVE THE COMPETENCY QUESTIONS (this is the whole job — get BOTH "
       "properties right):\n\n"
       "  1. GOAL-ANCHORED — author the questions from what the GOAL says the graph "
       "should answer, NOT from whatever happens to get extracted. The questions "
       "are the goal made testable. Favour questions that exercise the goal's CORE "
       "relationships — especially CONNECTIONS ACROSS sources (use the profiles' "
       "linking-keys to find where two sources describe the same entity), not "
       "trivial single-field lookups. Do NOT just paraphrase the extraction; the "
       "gate must NOT be self-fulfilling.\n"
       "  2. GROUNDED — every question must be answerable, at least in theory, from "
       "what the profiles show the sources actually CONTAIN (their entity-candidates, "
       "keys, scope-fields, grain). If the goal wants something NO profile "
       "supports, do NOT author a question for it (an honest gap is better than a "
       "question the sources cannot answer — that is a later honest-unanswerable "
       "signal, not a CQ to mint here).\n\n"
       "Write each CQ as ONE clear, specific natural-language question (e.g. "
       "\"Which X are linked to a given Y?\", \"What is the Z attribute of each W "
       "in <the goal's scope>?\"). Honor any scope the GOAL states. Aim for a "
       "focused set (roughly 4–8) that COVERS the goal's intent without padding — "
       "each question earns its place by testing a distinct part of the goal that "
       "the profiles can support.\n\n"
       "For EACH question, also give a one-line RATIONALE naming (a) which part of "
       "the GOAL it tests and (b) which profile fields/sources GROUND it — so a "
       "human reviewer can see WHY it was derived and override it if wrong.\n\n"
       "Do NOT mint concepts, do NOT emit a tree, do NOT design a transform — just "
       "derive the questions and finalize."
       (contract-block
        cq-contract-keys
        "  :competency-questions — VECTOR of natural-language question STRINGS (the load-bearing exit-gate questions; these persist as the ORSD spec)\n  :rationale            — VECTOR of one-line strings, parallel to :competency-questions, each naming the goal-part the CQ tests + the profile field(s)/source(s) that ground it (for HITL review; not gated on)")))

;; =============================================================================
;; DT6 — the prompt-assembly promotion seam (PRD M6): ONE seam, static now
;; =============================================================================
;; Every focused node above defines its STATIC focused body as a `*-node-prompt`
;; fn (DT2/DT3/DT4/DT5). DT6 routes ALL of them through ONE assembly seam so the
;; orchestration has a SINGLE point where a node's prompt is produced — and a
;; SINGLE point a later slice flips from the static body to a LIVING-BEHAVIOR
;; source (classify-behaviors → inject the top-fitting behavior's worked-examples
;; + participate in minting). Today the seam returns the static prompt verbatim:
;; NO behavior change (the DT2-DT5 node tests still pin the static bodies, and
;; the seam returns them byte-identical).
;;
;; *** THE PROMOTION CONTRACT (the whole point of this seam) ***
;; The seam takes a `prompt-source` fn — `(fn [node-kind params] <prompt-string>)`
;; — defaulting to `static-node-prompt-source`. Promotion to a living behavior is
;; a CLEAN FLIP behind the seam: a future slice supplies a prompt-source that, for
;; a node-kind + params, calls `classify-behaviors` over the node's task shape,
;; prepends the top-fitting seed-corpus behavior's capabilities + worked-example
;; DSL snippets, and lets the node `(mint-behavior! …)` when no pattern fits —
;; WITHOUT rewriting any node or its call site (they keep calling the seam).
;;
;; *** NO COUPLING TO CURRENT MINTING INTERNALS (the load-bearing boundary) ***
;; The seam takes a PLAIN injected fn; it does NOT call into today's
;; `classify-behaviors` / mint code (the minting process is being reworked
;; separately — DT6 must not bind to it). The injection point is a clean fn
;; boundary, so the promotion source can be composed/wrapped (corpus-sourced with
;; a static fall-back) and threaded in later. This namespace requires NO
;; classify/mint symbol for the seam to work — proof the boundary is clean.

(defn static-node-prompt-source
  "The DEFAULT prompt-source for `assemble-node-prompt`: returns the node's
   STATIC focused body (the DT2-DT5 `*-node-prompt` fns) for a node-kind + params.
   This is the `(fn [node-kind params] <prompt-string>)` contract a promotion
   slice composes/replaces behind the seam (e.g. corpus-sourced-or-fall-back-to-
   static) — it is PUBLIC so a later source can wrap it rather than fork the
   static bodies.

   `node-kind` ∈ #{:profile :model :transform :requirements}. `params` carries
   the per-node inputs the static body needs:
     :profile      → :goal :fmt        (fmt selects the per-medium tool catalog)
     :model        → :goal
     :transform    → :goal :key-shape  (key-shape = the optional DT4 grounding block; nil → none)
     :requirements → :goal

   An unknown node-kind throws (Discipline #5 — a routing bug surfaces loudly,
   never a silent/wrong prompt)."
  [node-kind {:keys [goal fmt key-shape]}]
  (case node-kind
    :profile      (profile-node-prompt goal fmt)
    :model        (model-node-prompt goal)
    :transform    (transform-node-prompt goal key-shape)
    :requirements (cq-node-prompt goal)
    (throw (ex-info "static-node-prompt-source: unknown node-kind"
                    {:node-kind node-kind
                     :known #{:profile :model :transform :requirements}}))))

(defn assemble-node-prompt
  "THE PROMOTION SEAM (PRD M6) — assemble a focused discovery node's prompt
   through ONE point. Every node's prompt (Profile/Model/Transform/Requirements)
   flows through here, so there is a SINGLE place a later slice flips the source
   from the static focused body to a living-behavior source.

   TODAY this returns the node's STATIC focused prompt verbatim (via
   `static-node-prompt-source`) — NO behavior change; the DT2-DT5 node bodies are
   byte-identical through the seam.

   `node-kind` is the node being assembled (`:profile` / `:model` / `:transform`
   / `:requirements`). `params` is the per-node input map the source needs (see
   `static-node-prompt-source` for the per-kind keys).

   `opts` (optional) is where promotion plugs in:
     :prompt-source — `(fn [node-kind params] <prompt-string>)`. DEFAULTS to
                      `static-node-prompt-source`. A promotion slice supplies a
                      source that sources from classify-behaviors / the seed
                      corpus and participates in minting — a CLEAN FLIP behind
                      this seam, no node rewrite. nil falls back to static.

   The seam does NOT couple to current minting internals: it invokes the source
   as a plain 2-arg fn and depends on NO classify/mint symbol (the minting rework
   is separate). Promotion is opt-in by injecting a source — the default is
   static."
  ([node-kind params] (assemble-node-prompt node-kind params nil))
  ([node-kind params {:keys [prompt-source]}]
   (let [source (or prompt-source static-node-prompt-source)]
     (source node-kind params))))

(defn string-cqs
  "Coerce a model-emitted :competency-questions value into a clean vector of
   non-blank question STRINGS (the ORSD-spec shape). Tolerant of the value-shape
   variance the DT2/DT3 live verifies documented: the model may emit a vector of
   strings, a single newline-joined string, or a vector of {:question …}/{:cq …}
   maps. Returns [] for anything with no recoverable question text — so an empty
   derivation surfaces honestly (no false green) rather than persisting garbage."
  [cqs]
  (let [->str (fn [x]
                (cond
                  (string? x) x
                  (map? x)    (or (:question x) (:cq x) (:text x)
                                  (get x "question") (get x "cq") (get x "text"))
                  :else       nil))
        items (cond
                (sequential? cqs) (keep ->str cqs)
                (string? cqs)     (str/split-lines cqs)
                :else             [])]
    (->> items
         (map str)
         (map str/trim)
         (remove str/blank?)
         (distinct)
         vec)))

(defn record-competency-questions!
  "Persist the competency questions as the S14 ORSD spec for the ontology-id, via
   the existing `:ontology/record-ontology-spec` command (Grain discipline —
   command → schema-validated event → projection; NO bare append). The CQs land
   in the spec body's `:competency-questions` so build!'s S15 exit-criterion stage
   reads them and judges the graph against them (PRD M7).

   The spec is APPEND-only (S14): we read the CURRENT spec body (if any), MERGE the
   derived/supplied CQs into its `:competency-questions`, and optionally stamp the
   goal as the spec `:purpose` when the spec has none — so recording CQs never
   destroys a spec a consumer already recorded (it grows it). Returns the command
   result so a caller can surface the dispatch outcome honestly."
  [ctx ontology-id cqs goal]
  (let [cqs (string-cqs cqs)
        existing (ontology/get-ontology-spec ctx ontology-id)
        body (cond-> (or existing {})
               true               (assoc :competency-questions cqs)
               (and goal (str/blank? (str (:purpose existing))))
               (assoc :purpose (str goal)))]
    (cp/process-command
     (assoc ctx :command
            {:command/name :ontology/record-ontology-spec
             :command/id (random-uuid)
             :command/timestamp (time/now)
             :ontology-id ontology-id
             :body body}))))

(defn requirements-cq-node!
  "DT5 — the graph-level Requirements/CQ node. Runs AFTER all sources are profiled.

   Derives competency questions from the GOAL ⨯ the supplied profiles (grounded +
   goal-anchored), persists them as the S14 ORSD spec the S15 exit-criterion
   judges (PRD M7), and returns the derived CQs surfaced for HITL review/override.

   A consumer-supplied CQ set SEEDS/OVERRIDES the derived set: when `:cqs` is
   non-empty, derivation is SKIPPED and the supplied questions are persisted as-is
   (the consumer is authoritative); otherwise the node derives them from the
   profiles. (Merge-on-top of a consumer set can be added behind this seam later;
   today supplied = override, which is the safe HITL default.)

   Required `params`:
     :ontology-id  — the granted scope the spec is recorded against (REQUIRED).
     :goal         — the runtime goal the CQs are anchored to (REQUIRED, non-blank).

   One of (REQUIRED unless :cqs supplied):
     :profiles     — a vector of the per-source DT2 profile contract maps (the
                     output of the Profile node for each source). Read tolerantly.

   Optional `params`:
     :cqs          — consumer-supplied competency questions (a vector of strings).
                     When non-empty, derivation is SKIPPED and these are persisted.
     :source       — a source descriptor used ONLY to give the derivation session a
                     blackboard slot (the node reasons over :profiles, not source
                     tools); defaults to a :text slot so no source tools are bound.
     :model :budget :debug? — passed to the derivation session.

   Returns:
     {:status :ok
      :origin :derived | :supplied
      :competency-questions [<question string> ...]   ; surfaced for HITL review
      :rationale [<one-line string> ...]              ; nil for :supplied
      :spec-recorded? <bool>                          ; the CQs persisted as the ORSD spec
      :record-result <the command result, verbatim>}
   or
     {:status :failed :error <root-cause> ...}        ; honest — no false green.

   A derivation that produces NO questions surfaces as :failed (the exit gate has
   nothing to judge against — Discipline #5, no silent empty)."
  [ctx {:keys [ontology-id goal profiles cqs source model budget debug?]
        :or {model "google/gemini-3-flash-preview"}}]
  (when-not ontology-id
    (throw (ex-info "requirements-cq-node! requires :ontology-id (the granted scope)"
                    {:params {:ontology-id ontology-id}})))
  (when-not (and (string? goal) (seq goal))
    (throw (ex-info "requirements-cq-node! requires :goal (a non-blank string)"
                    {:goal goal})))
  (let [ctx (assoc ctx :granted-ontology-id ontology-id :ontology-id ontology-id)
        supplied (string-cqs cqs)]
    (if (seq supplied)
      ;; CONSUMER OVERRIDE: supplied CQs are authoritative — skip derivation,
      ;; persist them as the ORSD spec the gate judges (PRD M3).
      (let [rec (record-competency-questions! ctx ontology-id supplied goal)]
        {:status :ok
         :origin :supplied
         :competency-questions supplied
         :rationale nil
         :spec-recorded? (not (some? (:cognitect.anomalies/category rec)))
         :record-result rec})
      ;; DERIVE from goal ⨯ profiles.
      (let [profiles (vec (or profiles []))]
        (when (empty? profiles)
          (throw (ex-info "requirements-cq-node! requires :profiles (a vector of source profiles) when no :cqs are supplied"
                          {:profiles profiles})))
        (let [derive-r (rlm-discovery/run-node-session!
                        ctx
                        {:node-name :requirements
                         :instruction (assemble-node-prompt :requirements {:goal goal})
                         :source (or source {:name :requirements :type :text :content ""})
                         :writes cq-contract-keys
                         :extra-inputs {:profiles profiles}
                         :focused-prompt? true
                         :model model :budget budget :debug? debug?})]
          (if (not= :ok (:status derive-r))
            {:status :failed
             :error (str "CQ derivation session failed: " (:error derive-r))
             :session (:session derive-r)}
            (let [out (:output derive-r)
                  derived (string-cqs (:competency-questions out))
                  rationale (:rationale out)]
              (if (empty? derived)
                {:status :failed
                 :error "CQ derivation produced NO competency questions (the exit gate would have nothing to judge against)"
                 :raw-output out
                 :session (:session derive-r)}
                (let [rec (record-competency-questions! ctx ontology-id derived goal)]
                  {:status :ok
                   :origin :derived
                   :competency-questions derived
                   :rationale rationale
                   :spec-recorded? (not (some? (:cognitect.anomalies/category rec)))
                   :record-result rec})))))))))

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
;; Branch points — DT8 FILLS recovery + cq-reextract; DT9 fills greenfield/maintain
;; =============================================================================
;; These were NAMED STUBS at DT1 so later slices fill them in place WITHOUT
;; restructuring the spine. DT8 fills `recovery-branch-stub` (focused single-node
;; recovery) and `cq-reextract-branch-stub` (the CQ-driven re-extract loop) with
;; their real branch DECISION logic; the two DT9 stubs stay no-ops. Each is still
;; invoked at its branch point in `run-discovery-tree!` and each still returns a
;; `{:branch <name> :taken? <bool> ...}` map so the surfaced branch-points read
;; uniformly. The HEAVY lifting (the loop / the recovery re-run) lives in the
;; `cq-driven-loop!` / `focused-node-recovery!` orchestration fns below; these
;; branch fns are the thin DECIDERS that say whether (and why) a branch is taken.

(defn recovery-branch-stub
  "BRANCH (DT8) — focused single-node recovery DECISION. Given a failed node +
   error, decide whether the focused single-node recovery branch should be taken.
   It is taken whenever a real node/stage failure is present (a named
   `:failed-node` with an `:error`) — the orchestrator then runs
   `focused-node-recovery!` (re-run JUST that node reading the surviving
   blackboard vars + the failure, the self-improving-loop focused-failure-recovery
   pattern), NOT a full rebuild. With no failure (the happy path) the branch is
   not taken. Kept named `recovery-branch-stub` so the DT1 spine wiring + the
   surfaced branch-points key are unchanged."
  [_ctx {:keys [failed-node error]}]
  (if failed-node
    {:branch :recovery
     :taken? true
     :reason :node-failure
     :strategy :focused-single-node-recovery
     :failed-node failed-node
     :error error}
    {:branch :recovery
     :taken? false
     :reason :no-node-failure
     :failed-node nil
     :error nil}))

(defn cq-reextract-branch-stub
  "BRANCH (DT8) — CQ-driven re-extract DECISION. Given build!'s CQ verdict
   (`:build-status` ∈ {:complete :failed-cq} + `:graph-health`), decide whether
   the adaptive CQ-driven re-extract loop should be entered. It is taken when the
   verdict is `:failed-cq` (the gate did NOT pass) — the orchestrator then runs
   `cq-driven-loop!`, which inspects the failing CQs + graph-health, FOCUSED
   re-extracts/re-links the node the gap traces to, re-gates, and terminates
   honestly (gate-pass / all-remaining-unanswerable / budget) — NEVER spins,
   NEVER false-greens. On `:complete` the branch is not taken. Kept named
   `cq-reextract-branch-stub` so the DT1 spine wiring is unchanged."
  [_ctx {:keys [build-status graph-health]}]
  (if (= :failed-cq build-status)
    {:branch :cq-reextract
     :taken? true
     :reason :failed-cq
     :strategy :focused-reextract-loop
     :build-status build-status
     :graph-health graph-health}
    {:branch :cq-reextract
     :taken? false
     :reason :cq-gate-passed
     :build-status build-status
     :graph-health graph-health}))

;; `graph-exists?` (defined below) reads the CURRENT graph projection via DT7's
;; `current-graph-concepts` — declared above so the branch decider + the spine
;; (both defined before that read) can call it without reordering the file.
(declare graph-exists?)

(defn greenfield-vs-maintain-branch-stub
  "BRANCH (DT9) — greenfield-vs-maintain front-of-tree DECISION. The hybrid edge
   branch (PRD M1): does a graph ALREADY EXIST for this ontology-id?

     - GREENFIELD (no graph yet) — the BUILT arm: run the full discovery tree
       into a fresh ontology, exactly as DT1-DT8 verified. The default arm;
       `:taken?` is false (the spine proceeds normally).
     - MAINTAIN (a graph already exists) — the branch is TAKEN and selects the
       EXPLICIT, NAMED `maintain-deferred-stub` (see below). The maintain /
       incremental build itself is DEFERRED (the maintain handoff). It is NOT a
       silent gap and NOT a partial build — a caller sees `:selected :maintain`
       + `:maintain :deferred`.

   Existence is read off the CURRENT graph projection via `graph-exists?`, which
   reuses DT7's `current-graph-concepts` read — the SAME projection the M5
   reconcile pass reconciles against (no forked notion). That is what makes the
   deferred maintain build ADDITIVE (per the handoff §1+§3): a thin flip of this
   decider's maintain arm from the deferred stub to a per-source run +
   `reconcile-graph!` (DT7) against the existing graph — NO restructure of the
   spine. `:reuses` names that reconcile path.

   `params` carries `:ontology-id` (REQUIRED — the scope whose graph existence is
   checked). An optional `:mode` forces the selection (`:greenfield`/`:maintain`)
   for tests/HITL override without inspecting the graph."
  [ctx {:keys [ontology-id mode]}]
  (let [selected (or mode (if (graph-exists? ctx ontology-id) :maintain :greenfield))]
    (case selected
      :maintain
      {:branch :greenfield-vs-maintain
       :taken? true
       :reason :graph-already-exists
       :selected :maintain
       :maintain :deferred
       :reuses 'reconcile-graph!}
      ;; :greenfield (default arm — full tree runs unchanged)
      {:branch :greenfield-vs-maintain
       :taken? false
       :reason :no-existing-graph
       :selected :greenfield})))

(defn maintain-deferred-stub
  "BRANCH RESULT (DT9) — the EXPLICIT, NAMED deferred maintain surface.

   Returned by `run-discovery-tree!` when the front-of-tree condition selects
   maintain (a graph already exists for the ontology-id). It is a CLEAR
   `:status :maintain-deferred` result a caller CANNOT mistake for a completed
   build or a silent no-op: NO discovery node ran, NOTHING was built, and the
   shape says so. The maintain / incremental-discovery branch (Pillar 4) is
   intentionally DEFERRED to the maintain handoff.

   This is the clean-additive seam: the deferred build replaces THIS stub with a
   per-source discovery run that reconciles into the existing graph via
   `reconcile-graph!` (DT7), which already reconciles against current graph state
   (PRD M5 / handoff §3) — a thin condition flip + reuse, not a restructure.
   `:reuses` names that reconcile path so the next implementer sees the seam.

   `params` mirrors `run-discovery-tree!`'s (`:ontology-id`, `:goal`, `:source`)
   so the deferred surface echoes WHAT was asked of maintain."
  [_ctx {:keys [ontology-id goal source]}]
  {:status :maintain-deferred
   :ontology-id ontology-id
   :goal goal
   :source source
   :nodes-run nil
   :build-result nil
   :reuses 'reconcile-graph!
   :deferred (str "A graph already exists for this ontology-id; the maintain / "
                  "incremental-discovery branch is intentionally DEFERRED. The "
                  "greenfield discovery tree builds into a fresh graph; maintain "
                  "re-runs a source against an EXISTING graph and reconciles the "
                  "new extraction into it. This deferred arm is a thin later "
                  "addition: run the per-source discovery and reconcile against "
                  "the current graph state (the reconcile pass already reads + "
                  "reconciles current graph state, so it merges rather than "
                  "rebuilds). Nothing was built on this run.")
   :branch-points {:greenfield-vs-maintain
                   {:branch :greenfield-vs-maintain
                    :taken? true
                    :reason :graph-already-exists
                    :selected :maintain
                    :maintain :deferred
                    :reuses 'reconcile-graph!}}})

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

;; Forward declarations: the DT8 adaptive-loop + focused-recovery orchestration
;; fns are DEFINED below `reconcile-graph!` (cq-driven-loop! reuses DT7 re-link),
;; but the per-source spine here invokes them — declare so the spine compiles.
(declare focused-node-recovery! cq-driven-loop!)

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
  [ctx {:keys [ontology-id source goal model budget judge-fn exit-criterion debug?
               cq-loop-config recover-nodes?]
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

        ;; --- DT9: the greenfield-vs-maintain front-of-tree condition (decided
        ;;     FIRST). Does a graph ALREADY EXIST for this ontology-id? Read off
        ;;     the CURRENT graph projection (the same read M5's reconcile pass
        ;;     reconciles against — `graph-exists?` reuses DT7's
        ;;     `current-graph-concepts`, no forked notion). GREENFIELD (no graph)
        ;;     runs the full tree below, exactly as DT1-DT8 verified. MAINTAIN (a
        ;;     graph exists) is intentionally DEFERRED — short-circuit to the
        ;;     EXPLICIT, NAMED `maintain-deferred-stub` WITHOUT running any node or
        ;;     building (no silent gap, no partial build). The deferred build is a
        ;;     clean later addition: a thin flip of this arm to a per-source run +
        ;;     `reconcile-graph!` against the existing graph (handoff §1+§3). ---
        gf-branch (greenfield-vs-maintain-branch-stub ctx {:ontology-id ontology-id})]
    (if (= :maintain (:selected gf-branch))
      (maintain-deferred-stub ctx {:ontology-id ontology-id :goal goal :source source})

      (let [;; --- Node 1: PROFILE (structurally guaranteed first step) ---
        ;; DT2: the Profile node is FOCUSED — its prompt is used verbatim
        ;; (:focused-prompt? true) so the retired mega-prompt's modeling / grain /
        ;; scope / transform guidance is NOT prepended. The per-medium tool
        ;; catalog is assembled inside profile-node-prompt from the source format.
        profile-r (run-node! ctx {:node-key :profile
                                  :prompt (assemble-node-prompt :profile {:goal goal :fmt (:type source)})
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
            model-r0 (run-node! ctx {:node-key :model
                                     :prompt (assemble-node-prompt :model {:goal goal})
                                     :source source
                                     :contract-keys model-contract-keys
                                     :extra-inputs {:profile (node-output bb :profile)}
                                     :focused-prompt? true
                                     :model model :budget budget :debug? debug?})
            ;; --- DT8 focused single-node recovery (opt-in) ---
            ;; On a Model-node failure, re-run JUST the Model node reading the
            ;; SURVIVING Profile output + the failure (the focused-failure-recovery
            ;; pattern) — NOT a full rebuild. Off by default so the honest
            ;; :failed-at-model surfacing is preserved unless a caller opts in.
            model-r (if (and recover-nodes? (not= :ok (:status model-r0)))
                      (focused-node-recovery!
                       ctx {:failed-node :model :error (:error model-r0)
                            :blackboard bb :source source :goal goal
                            :model model :budget budget :debug? debug?})
                      model-r0)]
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
                transform-r0 (run-node! ctx {:node-key :transform
                                             :prompt (assemble-node-prompt :transform {:goal goal :key-shape key-shape})
                                             :source source
                                             :contract-keys transform-contract-keys
                                             :extra-inputs {:model-spec (node-output bb :model)}
                                             :focused-prompt? true
                                             :model model :budget budget :debug? debug?})
                ;; --- DT8 focused single-node recovery (opt-in) ---
                ;; On a Transform-node failure, re-run JUST the Transform node
                ;; reading the SURVIVING model-spec + the failure — NOT a rebuild.
                transform-r (if (and recover-nodes? (not= :ok (:status transform-r0)))
                              (focused-node-recovery!
                               ctx {:failed-node :transform :error (:error transform-r0)
                                    :blackboard bb :source source :goal goal
                                    :key-shape key-shape
                                    :model model :budget budget :debug? debug?})
                              transform-r0)]
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
                        initial-build
                        (skeleton/build!
                         ctx
                         (cond-> {:ontology-id ontology-id
                                  :sources [{:type :inline-concepts :concepts []}]}
                           judge-fn       (assoc :judge-fn judge-fn)
                           exit-criterion (assoc :exit-criterion exit-criterion)))

                        ;; --- read the CQ verdict back + DECIDE the cq-reextract
                        ;;     branch (PRD M7). On :failed-cq the tree OWNS the
                        ;;     adaptive loop: focused re-extract → re-gate →
                        ;;     terminate honestly (pass / unanswerable / budget). On
                        ;;     :complete the branch is not taken; build! stands. ---
                        cq-branch (cq-reextract-branch-stub
                                   ctx {:build-status (:status initial-build)
                                        :graph-health (:graph-health initial-build)})
                        build-result
                        (if (:taken? cq-branch)
                          (cq-driven-loop!
                           ctx
                           {:ontology-id ontology-id :source source :goal goal
                            :blackboard bb :key-shape key-shape
                            :judge-fn judge-fn :exit-criterion exit-criterion
                            :model model :budget budget :debug? debug?
                            :cq-loop-config cq-loop-config
                            ;; single-source run: no cross-source re-link to do.
                            :reconcile-fn false
                            :initial-build-result initial-build})
                          initial-build)]
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
                     :cq-loop (:cq-loop build-result)
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
                                                ctx {:failed-node nil :error nil})}})))))))))))))))

;; =============================================================================
;; DT7 — Cross-source linking / reconciliation (PRD M5)
;; =============================================================================
;; A GRAPH-LEVEL reconciliation pass that runs AFTER every source has extracted
;; (the per-source sub-trees have each landed their drafts as events). It is
;; where the cross-source graph becomes CONNECTED and where "discover
;; ambiguities" lives. It re-orchestrates proven machinery — it forks NOTHING
;; (Discipline #8):
;;
;;   LINK across sources
;;     - SHARED CANONICAL URI (the load-bearing collapse): two sources that mint
;;       the SAME canonical URI (e.g. both emit `cip:01.0101`) ALREADY collapse
;;       to ONE node in the URI-keyed `:ontology/concepts` projection
;;       (last-write-wins on the URI key). The reconcile pass does NOT need to
;;       merge them — it READS the current projection and REPORTS the collapse
;;       (the shared-URI concepts that now resolve to a single node). This is the
;;       cheapest, most reliable cross-source link and it needs zero LLM.
;;     - NEAR-MATCH (different encodings of the same real thing): the S12 dedup
;;       cascade (`dedup/lsh-candidate-pairs` blocking + the `run-dedup-cascade`
;;       command, project-once disjointness) runs over the CURRENT graph's
;;       concepts. A `:merge` verdict records an S08 equivalence into an S03
;;       alignment SECTION — so the graph is connected WITHOUT corrupting either
;;       source's URI-keyed concept (the S08 Path-B discipline).
;;
;;   SURFACE ambiguities (the "discover ambiguities" capability)
;;     - the cascade's `:requires-review` verdicts (the ambiguity band the
;;       deterministic guards can't close — surfaced, NEVER silently merged or
;;       dropped) PLUS the V18 near-variant ambiguities the always-on compile
;;       backstop recorded. Both are surfaced on the result for HITL review.
;;
;;   0 DANGLING (V18 referential integrity) — the always-on structural invariant
;;     re-checked over the CURRENT graph (`skeleton`'s pure report, reused).
;;
;; *** THE LOAD-BEARING SEAM (PRD M5 / the maintain handoff §3) ***
;; The pass reads EXISTING concepts/relationships from the projection and
;; reconciles against the CURRENT GRAPH STATE — it does NOT assume an empty
;; graph. Proof that this makes maintain a clean later addition: running
;; reconciliation TWICE (or against a pre-populated graph) RECONCILES — it
;; merges / no-ops — it does NOT duplicate. This holds structurally because
;; (a) re-minting a shared URI is a projection no-op (same key), and (b) an S08
;; equivalence pair is a SET (re-assertion is a set no-op). The reconcile pass
;; therefore has no empty-graph assumption to remove when the maintain branch
;; (greenfield-vs-maintain edge + this same stage over an existing graph) lands.
;;
;; Domain-agnostic (Discipline #12): linking is generic — shared-id collapse /
;; structural similarity / alignment. NO CIP/SOC/education/industry knowledge,
;; NO hardcoded phrase matching. The candidate generation + verdicts are the
;; same medium-agnostic S12 primitives the intra-source dedup stage uses.

(defn- current-graph-concepts
  "Read the CURRENT concepts for an ontology-id off the projection (NOT an empty
   graph) — the section-keyed scoped view, coerced to the dedup-cascade's
   candidate shape (`:uri :label :description :type :broader`). This is the
   load-bearing read: reconciliation operates over whatever is already in the
   graph, so a second pass / a pre-populated graph reconciles rather than
   rebuilds. The :broader set is vectorized for the cascade command schema —
   identical coercion to S17's dedup-stage (no forked notion)."
  [ctx ontology-id]
  (->> (rm/get-concepts ctx {:ontology-id ontology-id})
       (mapv (fn [c]
               (cond-> (select-keys c [:uri :label :description :type])
                 (seq (:broader c)) (assoc :broader (vec (:broader c))))))))

(defn graph-exists?
  "Does a graph ALREADY EXIST for this ontology-id? — the DT9 greenfield-vs-
   maintain existence read. True when the CURRENT graph projection has at least
   one concept for the scope, false otherwise (an empty/never-built graph).

   It reuses DT7's `current-graph-concepts` read — the SAME projection the M5
   reconcile pass reconciles against — so existence and reconciliation share ONE
   notion of 'the current graph' (no forked read). A nil ontology-id is treated
   as no-graph (false) so the front-of-tree decider defaults to greenfield and
   the spine's own `:ontology-id` guard surfaces the missing scope loudly.

   GREENFIELD IS THE SAFE DEFAULT: when the projection cannot be read because no
   graph store is wired into ctx (a no-store ctx — e.g. a node-only orchestration
   test that never builds), there IS no existing graph, so this returns false
   (greenfield). It does NOT mask a real fault: a wired-but-empty store reads
   cleanly as empty (also false), and the maintain arm is only ever selected by a
   genuinely-populated projection. Existence is a READ-ONLY probe — it never
   writes; an unreadable read is treated as 'no graph yet', not an error to crash
   the front of the tree on."
  [ctx ontology-id]
  (boolean
   (and ontology-id
        (try (seq (current-graph-concepts ctx ontology-id))
             (catch Throwable _ false)))))

(defn shared-uri-links
  "The cross-source SHARED-CANONICAL-URI links present in the current graph.

   Given the per-source draft URI-sets the reconcile pass was handed
   (`source-uri-sets` — a vector of `{:source <name> :uris #{...}}`, one per
   extracted source), return the URIs that appear in MORE THAN ONE source's
   draft set AND resolve to a single node in the current graph. Each such URI is
   a cross-source merge that the URI-keyed projection collapsed automatically —
   one node, not two. Domain-agnostic: pure set intersection over whatever URIs
   the sources minted; it names no domain.

   Returns:
     {:shared-uris       [<uri> ...]                 ; in ≥2 sources + resolves
      :shared-uri-count  <int>
      :by-uri            {<uri> #{<source-name> ...}} ; which sources contributed}"
  [source-uri-sets current-concept-uris]
  (let [current (set current-concept-uris)
        contributing (reduce
                      (fn [acc {:keys [source uris]}]
                        (reduce (fn [a u]
                                  (update a u (fnil conj #{}) source))
                                acc
                                (set uris)))
                      {}
                      (or source-uri-sets []))
        shared (->> contributing
                    (filter (fn [[uri sources]]
                              (and (> (count sources) 1)
                                   (contains? current uri))))
                    (map first)
                    sort
                    vec)]
    {:shared-uris shared
     :shared-uri-count (count shared)
     :by-uri (select-keys contributing shared)}))

(defn default-alignment-section-id
  "Derive the DEFAULT cross-source alignment-section id for an ontology — a
   DISTINCT, stable UUID, NOT the ontology-id itself. S08's Path-B discipline
   forbids recording an equivalence against a primary section (it would corrupt
   the URI-keyed concept projection via last-write-wins collapse), and the S03
   register command rejects a self-section (a duplicate [:ontology id] tag). So
   the cross-source equivalences land in a derived companion section. Stable
   (a deterministic v3/name-based UUID over the ontology-id) so re-running
   reconcile targets the SAME alignment section — idempotent, not a fresh
   section per run. Domain-agnostic: pure id derivation, names no domain."
  [ontology-id]
  (java.util.UUID/nameUUIDFromBytes
   (.getBytes (str "dt7-cross-source-alignment:" ontology-id) "UTF-8")))

(defn reconcile-graph!
  "DT7 — the graph-level cross-source reconciliation pass (PRD M5).

   Runs AFTER all per-source extraction has landed in the graph. Reconciles the
   CURRENT GRAPH STATE (it READS existing concepts/relationships from the
   projection — it does NOT assume an empty graph), linking entities from
   different sources, surfacing ambiguities, and re-checking referential
   integrity. Reuses S03 (alignment registry) + S12 (dedup cascade + LSH
   blocking + project-once) + V18 (referential integrity) — NO fork.

   Required `params`:
     :ontology-id  — the granted scope whose current graph is reconciled.

   Optional `params`:
     :source-uri-sets — a vector of `{:source <name> :uris #{<uri> ...}}`, one
                        per source the tree extracted. When supplied, the result
                        reports the cross-source SHARED-URI links (URIs minted by
                        ≥1 source that appear in ≥2 sources' draft sets and
                        resolve to a single node — the automatic projection
                        collapse). Omit it and the shared-URI report is empty
                        (the near-match link + integrity still run over the full
                        graph).
     :alignment-ontology-id — the S03 alignment SECTION cross-source `:merge`
                        equivalences are recorded into (S08). Defaults to a
                        DISTINCT stable derived section (`default-alignment-
                        section-id`) — NOT the primary (S08 Path-B forbids
                        recording equivalences against a primary; the S03 register
                        command rejects a self-section). A caller maintaining its
                        own alignment section threads its id.
     :llm-budget   — max LLM calls the S12 cascade may spend on the ambiguity
                     band. DEFAULT 0 — reconciliation is deterministic; the
                     ambiguity-band pairs the deterministic guards can't close
                     surface as `:requires-review` ambiguities (honest, no LLM,
                     never silently merged). Raise it to let the cascade's LLM
                     tier adjudicate the band.
     :llm-fn       — the cascade's T9 verdict fn (threaded onto ctx); only used
                     when :llm-budget > 0.

   Returns:
     {:status :ok
      :ontology-id <uuid>
      :alignment-ontology-id <uuid>
      ;; --- LINK ---
      :concepts-in-scope <int>                  ; size of the current graph read
      :shared-uri-links {:shared-uris [...] :shared-uri-count <int> :by-uri {...}}
      :candidate-pairs <int>                    ; S12 LSH-blocked cross-graph pairs
      :merges <int>                             ; near-match links recorded (S08)
      :merge-equivalences [<{:source-uri :target-uri :kind}> ...]
      ;; --- AMBIGUITIES (surfaced, never silently merged/dropped) ---
      :ambiguities-surfaced <int>
      :ambiguities [<verdict-or-near-variant> ...]
      ;; --- 0 DANGLING (V18) ---
      :referential-integrity {:every-edge-endpoint-resolves? <bool>
                              :dangling-edge-count <int> :dangling-edges [...]}
      :relationships-in-scope <int>}

   Idempotent by construction (the load-bearing seam): re-running merges / no-ops
   (re-mint of a shared URI is a projection no-op; an S08 equivalence pair is a
   set, so re-assertion is a set no-op) — it does NOT duplicate."
  [ctx {:keys [ontology-id source-uri-sets alignment-ontology-id llm-budget]
        :or {llm-budget 0}}]
  (when-not ontology-id
    (throw (ex-info "reconcile-graph! requires :ontology-id (the granted scope)"
                    {:params {:ontology-id ontology-id}})))
  (let [alignment-ontology-id (or alignment-ontology-id
                                  (default-alignment-section-id ontology-id))

        ;; --- V18: reconcile referential integrity over the CURRENT graph FIRST.
        ;;     A cross-source edge can reference an endpoint a DIFFERENT source
        ;;     owns; the always-on V18 invariant (reused verbatim — no fork) auto-
        ;;     mints the implied endpoint and surfaces a near-variant identity as
        ;;     an AMBIGUITY. Idempotent: a second pass finds the endpoints already
        ;;     resolved and mints nothing. This runs BEFORE the near-match pass so
        ;;     the freshly-implied endpoint participates in dedup. ---
        v18 (rlm-discovery/reconcile-current-graph-integrity! ctx ontology-id)

        ;; --- READ THE CURRENT GRAPH STATE (the load-bearing seam) ---
        ;; Read AFTER the V18 pass so any auto-minted implied endpoint is in
        ;; scope for the near-match cascade + the final integrity report.
        concepts (current-graph-concepts ctx ontology-id)
        relationships (filterv #(= ontology-id (:ontology-id %))
                               (rm/get-relationships ctx))
        concept-uris (set (map :uri concepts))

        ;; --- S03: ensure the alignment section exists so cross-source
        ;;     equivalences register against a real section (idempotent: the
        ;;     register command is additive; re-registering is a set no-op).
        ;;     A self-section (alignment == primary) is degenerate AND rejected
        ;;     by the S03 command (duplicate tag) — skip it (a caller that passed
        ;;     alignment == primary deliberately gets no section, not a fault).
        ;;     A genuine command rejection surfaces loudly (Discipline #5). ---
        _ (when (not= alignment-ontology-id ontology-id)
            (let [reg (cp/process-command
                       (assoc ctx :command
                              {:command/name :ontology/register-alignment-section
                               :command/id (random-uuid)
                               :command/timestamp (time/now)
                               :primary-ontology-id ontology-id
                               :alignment-ontology-id alignment-ontology-id}))]
              (when (:cognitect.anomalies/category reg)
                (throw (ex-info "reconcile-graph!: alignment-section registration anomaly"
                                {:anomaly reg
                                 :primary ontology-id
                                 :alignment alignment-ontology-id})))))

        ;; --- LINK: shared canonical URI (the automatic projection collapse) ---
        shared (shared-uri-links source-uri-sets concept-uris)

        ;; --- LINK: near-match via S12 (LSH blocking + project-once cascade) ---
        ;; Block the CURRENT graph's concepts into candidate pairs with the SAME
        ;; LSH/MinHash blocker the intra-source dedup-stage uses (no forked
        ;; similarity notion). Project the S07 disjointness ONCE (project-once
        ;; discipline — not per pair).
        pairs (dedup/lsh-candidate-pairs concepts)
        disjointness (or (get-in (rmp/project ctx :ontology/axioms)
                                 [ontology-id :disjointness])
                         {})
        existing-evidence (rmp/project ctx :ontology/concept-evidence)
        ;; Pure pre-filter resolves the cheap tiers (no command, no event);
        ;; only survivors (real merge / ambiguity-band candidates) dispatch the
        ;; full cascade command — IDENTICAL orchestration to S17's dedup-stage.
        survivors (->> pairs
                       (remove (fn [[a b]]
                                 (dedup/prefilter-verdict
                                  {:a a :b b :disjointness-map disjointness})))
                       vec)
        survivor-verdicts
        (mapv (fn [[a b]]
                (let [result (cp/process-command
                              (assoc ctx :command
                                     {:command/name :ontology/run-dedup-cascade
                                      :command/id (random-uuid)
                                      :command/timestamp (time/now)
                                      :ontology-id ontology-id
                                      :alignment-ontology-id alignment-ontology-id
                                      :a a :b b
                                      :llm-budget llm-budget
                                      :disjointness disjointness
                                      :existing-evidence existing-evidence}))]
                  (when (:cognitect.anomalies/category result)
                    (throw (ex-info "reconcile-graph!: cascade command returned anomaly"
                                    {:anomaly result :a a :b b})))
                  (assoc (get-in result [:command-result/data :verdict])
                         :a-uri (:uri a) :b-uri (:uri b))))
              survivors)
        merge-verdicts (filterv #(= :merge (:verdict %)) survivor-verdicts)
        review-verdicts (filterv #(= :requires-review (:verdict %)) survivor-verdicts)

        ;; --- SURFACE AMBIGUITIES (never silently merged or dropped) ---
        ;; Two honest sources, unified: (a) the V18 near-variant ambiguities the
        ;; integrity pass just recorded (a dangling endpoint that is a near-
        ;; variant of an existing URI — likely an alternate encoding of the same
        ;; real thing); (b) the cascade's :requires-review verdicts (the near-
        ;; match ambiguity band the deterministic guards can't close — surfaced
        ;; rather than guessed). Both go on the result for HITL review.
        v18-ambiguities
        (mapv (fn [a] (assoc a :source :v18-referential-integrity))
              (:ambiguities v18))
        ambiguities (into v18-ambiguities review-verdicts)

        ;; --- 0 DANGLING (V18 referential integrity over the CURRENT graph) ---
        ;; Reuse the skeleton's pure report (no fork). It needs concepts with
        ;; :uri and relationships with :source-uri/:target-uri — exactly the
        ;; projection shapes. After the V18 reconcile above, every endpoint that
        ;; had a resolvable id segment is now minted, so this should report 0
        ;; dangling (an unresolved endpoint with no id segment stays surfaced).
        integrity (skeleton/referential-integrity-report concepts relationships)]
    {:status :ok
     :ontology-id ontology-id
     :alignment-ontology-id alignment-ontology-id
     :concepts-in-scope (count concepts)
     :relationships-in-scope (count relationships)
     :shared-uri-links shared
     :implied-endpoints-minted (:implied-minted v18)
     :candidate-pairs (count pairs)
     :merges (count merge-verdicts)
     :merge-equivalences (mapv (fn [v]
                                 {:source-uri (:a-uri v)
                                  :target-uri (:b-uri v)
                                  :kind (:kind v)})
                               merge-verdicts)
     :ambiguities-surfaced (count ambiguities)
     :ambiguities ambiguities
     :referential-integrity integrity}))

;; =============================================================================
;; DT8 — the tree-owned adaptive CQ-driven loop + focused recovery (PRD M7)
;; =============================================================================
;; The two RLM-chosen branches the DT1 spine left named-stubbed are filled here
;; as REAL orchestration around the intact `build!` sub-call. build! stays a
;; deterministic sub-call (Discipline #8 — no fork); the TREE owns the loop:
;;
;;   build! ─CQ verdict─┬─ :complete ──────────────────────────────────→ DONE
;;                      └─ :failed-cq → inspect failing CQs + graph-health
;;                                      → FOCUSED re-extract the node the gap
;;                                        traces to (NOT a full rebuild)
;;                                      → re-gate (build! again)
;;                                      → unanswerable? surface + terminate
;;                                      → budget? terminate with the reason
;;
;; And, orthogonally, when a NODE/STAGE in the per-source sub-tree FAILS (throws),
;; `focused-node-recovery!` re-runs JUST that node reading the surviving
;; blackboard vars + the failure (mirrors the self-improving-loop focused-failure-
;; recovery pattern) rather than rebuilding the whole pipeline.
;;
;; HONEST-NEGATIVE ETHOS (V17 / discipline 9 / PRD US-20): the loop distinguishes
;; "re-extract to close the gap" from "honestly unanswerable from these sources".
;; A failing CQ is judged unanswerable by S15 against the RUNTIME CQs — NOT by any
;; domain rule (discipline 12). Operationally: a CQ stays failing AND a focused
;; re-extract aimed at it supplied NO new graph data closing it (the graph did not
;; grow toward that CQ across the iteration) ⇒ the sources genuinely lack the data
;; ⇒ the CQ is surfaced as unanswerable and the loop stops chasing it. The loop is
;; ALWAYS budget-bounded (max iterations) so it terminates even if every iteration
;; grows the graph; the termination reason is always surfaced. NEVER an infinite
;; loop, NEVER a false green.

(def default-cq-loop-config
  "DT8 adaptive-loop budget + gate knobs. `:max-iterations` is the HARD bound on
   focused re-extract iterations after the initial build! (so the loop ALWAYS
   terminates regardless of model behavior). `:exit-criterion` is build!'s CQ
   gate (defaults to skeleton/default-exit-criterion: pass-rate >= 0.8,
   unknown-rate <= 0.3); the loop re-gates against the SAME criterion each pass."
  {:max-iterations 3})

(defn failing-cq-verdicts
  "The per-CQ verdicts that represent a GAP the loop should try to close — the
   load-bearing read for both re-extract targeting AND unanswerable detection.

   `evaluated` is the `:evaluated` vector S15's `evaluate-cqs!` returns (one
   `{:cq-text :verdict :reasoning :evidence-uris :layer :gaps}` per CQ). A CQ is a
   GAP when its verdict is `:fail` (the graph contradicts/closes-out the answer)
   OR `:unknown` (open-world: the graph cannot confirm it). A `:pass` is not a
   gap. Domain-agnostic: it reads the S15 verdict, names no domain. Returns the
   gap verdicts verbatim (so the re-extract can read each CQ's text + gaps)."
  [evaluated]
  (filterv (fn [v] (contains? #{:fail :unknown} (:verdict v)))
           (or evaluated [])))

(defn- graph-size
  "A cheap (concepts + relationships) size signal for an ontology-id off the
   projection — the load-bearing delta the unanswerable test keys off. If a
   focused re-extract aimed at a gap CQ grows neither concepts nor relationships,
   it supplied NO new data toward that CQ. Domain-agnostic count, names nothing."
  [ctx ontology-id]
  (let [cs (count (filterv #(= ontology-id (:ontology-id %))
                           (rm/get-concepts ctx {:ontology-id ontology-id})))
        rs (count (filterv #(= ontology-id (:ontology-id %))
                           (rm/get-relationships ctx)))]
    {:concepts cs :relationships rs :total (+ cs rs)}))

(defn focused-node-recovery!
  "DT8 — focused SINGLE-NODE recovery (mirrors the self-improving-loop
   focused-failure-recovery pattern). When a node/stage in the per-source sub-tree
   FAILED, re-run JUST that node reading the SURVIVING blackboard vars + the
   failure — NOT a full rebuild.

   `failed-node` is the node key that failed (`:profile`/`:model`/`:transform`);
   `error` is its failure (the `tree-failures`/`failed-leaves` analogue surfaced
   by run-node-session!). `blackboard` carries the SURVIVING upstream node outputs
   (the work that already succeeded — re-running it would re-pay for it). The
   recovery re-runs the failed node with its predecessor's surviving contract as
   :extra-inputs, plus a short recovery preamble naming the prior error so the
   model resumes from the surviving data rather than rebuilding the pipeline.

   `run-node` is the injected node runner (`run-node!` in production; a stub in
   tests) so the recovery is testable deterministically. Returns the node session
   result `{:status :ok :output …}` / `{:status :failed :error …}` verbatim plus
   `:recovery? true` + `:recovered-node` so the caller can see it was a recovery
   (no false green — a recovery that fails again surfaces honestly)."
  [ctx {:keys [failed-node error blackboard source goal model budget debug? run-node
                key-shape]}]
  (let [run-node (or run-node run-node!)
        ;; The surviving predecessor output the failed node reads (the inter-node
        ;; contract channel) — exactly what run-discovery-tree! threads on the
        ;; happy path, so recovery resumes from surviving work, not a rebuild.
        extra-inputs (case failed-node
                       :model     {:profile (node-output blackboard :profile)}
                       :transform {:model-spec (node-output blackboard :model)}
                       {})
        contract-keys (case failed-node
                        :profile   profile-contract-keys
                        :model     model-contract-keys
                        :transform transform-contract-keys
                        nil)
        base-prompt (case failed-node
                      :profile   (assemble-node-prompt :profile {:goal goal :fmt (:type source)})
                      :model     (assemble-node-prompt :model {:goal goal})
                      :transform (assemble-node-prompt :transform {:goal goal :key-shape key-shape})
                      nil)
        recovery-preamble
        (str "RECOVERY RE-RUN of the " (name (or failed-node :node)) " step: the "
             "previous attempt FAILED with:\n  " (pr-str error) "\n"
             "The upstream steps SUCCEEDED — their outputs are on your inputs "
             "(read them with (get-input …)); do NOT redo them. Re-do ONLY THIS "
             "step, fixing what caused the failure above, then (final! {…}).\n\n")]
    (if (nil? contract-keys)
      {:status :failed
       :recovery? true
       :recovered-node failed-node
       :error (str "focused-node-recovery!: not a recoverable node: "
                   (pr-str failed-node))}
      (let [r (run-node ctx {:node-key failed-node
                             :prompt (str recovery-preamble base-prompt)
                             :source source
                             :contract-keys contract-keys
                             :extra-inputs extra-inputs
                             :focused-prompt? true
                             :model model :budget budget :debug? debug?})]
        (assoc r :recovery? true :recovered-node failed-node)))))

(defn focused-reextract!
  "DT8 — FOCUSED re-extract aimed at a set of failing CQs (NOT a full rebuild).

   Re-runs the TRANSFORM node (the extraction node whose output a graph gap traces
   to) oriented by the failing CQs + the surviving blackboard (the profile +
   model-spec that already succeeded), re-applies the V20 full-extraction step,
   re-compiles the drafts as events, and returns a summary of what it landed. The
   caller (`cq-driven-loop!`) then re-runs build! to RE-GATE.

   The failing CQs are injected as the node's orientation so the re-extract is
   TARGETED (close THESE gaps) rather than a blind re-run. The transform node
   re-reads the surviving model-spec; the surviving profile/model work is NOT
   re-paid. Domain-agnostic: the CQ text comes from the runtime spec, not a domain
   rule.

   `run-node` / `apply-fn` / `compile-fn` are injected so the loop is testable
   deterministically (production wires run-node!, apply-extraction-transform!,
   compile-discovery-source!). Returns:
     {:status :ok :concepts-added <int> :relationships-added <int>
      :transform-output <verbatim> :before <size> :after <size>}
   or {:status :failed :error <root-cause>} — honest; a re-extract that throws or
   yields a bad transform surfaces, never a silent pass."
  [ctx {:keys [ontology-id source goal failing-cqs blackboard key-shape
                model budget debug? run-node apply-fn compile-fn]}]
  (let [run-node (or run-node run-node!)
        apply-fn (or apply-fn rlm-discovery/apply-extraction-transform!)
        compile-fn (or compile-fn rlm-discovery/compile-discovery-source!)
        before (graph-size ctx ontology-id)
        cq-texts (mapv :cq-text failing-cqs)
        reextract-preamble
        (str "FOCUSED RE-EXTRACT: the graph BUILT but did NOT answer these "
             "competency questions yet:\n"
             (str/join "\n" (map-indexed (fn [i q] (str "  " (inc i) ". " q)) cq-texts))
             "\nRe-author the per-row extraction transform so the graph CARRIES "
             "the entities/relationships/attributes these questions need (the prior "
             "model-spec is on your inputs as :model-spec — read it with "
             "(get-input :model-spec)). If the SOURCES genuinely do not contain the "
             "data a question needs, do NOT fabricate it — extract only what is "
             "really there; the loop surfaces a still-unanswered question honestly.\n\n")
        node-r (run-node ctx {:node-key :transform
                              :prompt (str reextract-preamble
                                           (assemble-node-prompt
                                            :transform {:goal goal :key-shape key-shape}))
                              :source source
                              :contract-keys transform-contract-keys
                              :extra-inputs {:model-spec (node-output blackboard :model)
                                             :failing-cqs cq-texts}
                              :focused-prompt? true
                              :model model :budget budget :debug? debug?})]
    (if (not= :ok (:status node-r))
      {:status :failed
       :error (str "focused re-extract transform node failed: " (:error node-r))
       :before before :after before
       :concepts-added 0 :relationships-added 0}
      (let [t-out (:output node-r)
            t-src (:transform-source t-out)
            selector (:selector t-out)]
        (if-not (and (string? t-src) (seq (str/trim t-src)))
          {:status :failed
           :error "focused re-extract produced no transform-source"
           :transform-output t-out
           :before before :after before
           :concepts-added 0 :relationships-added 0}
          (let [extraction (try
                             (apply-fn {:descriptor {:type (:type source) :path (:path source)}
                                        :selector selector
                                        :transform-source t-src})
                             (catch Throwable t {::extract-error (.getMessage t)}))]
            (if-let [ex (::extract-error extraction)]
              {:status :failed
               :error (str "focused re-extract apply failed: " ex)
               :transform-output t-out
               :before before :after before
               :concepts-added 0 :relationships-added 0}
              (let [compile-r (try
                               {:ok (compile-fn
                                     ctx ontology-id
                                     {:status :emitted-drafts
                                      :emitted-concepts (vec (:concept-drafts extraction))
                                      :emitted-relationships (vec (:relationship-drafts extraction))
                                      :emitted-axioms []
                                      :rlm-trace []})}
                               (catch Throwable t {:error (.getMessage t)}))]
                (if (:error compile-r)
                  {:status :failed
                   :error (str "focused re-extract compile failed: " (:error compile-r))
                   :transform-output t-out
                   :before before :after before
                   :concepts-added 0 :relationships-added 0}
                  (let [after (graph-size ctx ontology-id)]
                    {:status :ok
                     :transform-output t-out
                     :before before
                     :after after
                     :concepts-added (- (:concepts after) (:concepts before))
                     :relationships-added (- (:relationships after) (:relationships before))}))))))))))

(defn cq-driven-loop!
  "DT8 — the tree-owned adaptive CQ-driven loop around the intact `build!`.

   Entered when build!'s FIRST CQ verdict is `:failed-cq`. Each iteration:
     1. read the failing CQs (S15 per-CQ verdicts: :fail / :unknown);
     2. FOCUSED re-extract aimed at them (`focused-reextract!` — re-run the
        transform node, re-apply, re-compile — NOT a full rebuild);
     3. re-link the cross-source graph (`reconcile-graph!`, DT7 reuse) so a gap a
        re-extract closed via a shared/near key is connected before re-gating;
     4. re-run `build!` to RE-GATE against the SAME exit-criterion;
     5. branch: gate now passes → DONE (:complete); the re-extract supplied NO new
        data AND the same CQs are still failing → those CQs are UNANSWERABLE from
        the sources → surface + terminate honestly; else loop (budget permitting).

   The loop ALWAYS terminates: it stops on a pass, on all-remaining-CQs-
   unanswerable, or on budget exhaustion — and ALWAYS surfaces a
   `:termination-reason` ∈ {:cq-gate-passed :all-remaining-unanswerable
   :budget-exhausted}. NEVER an infinite loop; NEVER a false green (an
   unanswerable termination is `:status :failed-cq` carrying the unanswerable CQs,
   NOT a fake :complete).

   Injected seams (production defaults; tests stub them):
     :build-fn        — (fn [ctx params] build-result). Default skeleton/build!.
     :reextract-fn    — (fn [ctx opts] reextract-result). Default focused-reextract!.
     :reconcile-fn    — (fn [ctx opts] reconcile-result). Default reconcile-graph!.
                        Pass false to skip re-link (single-source runs).
     :evaluate-cqs-fn — (fn [ctx ontology-id judge-fn] {:evaluated [...] ...}).
                        Default reads S15's evaluate-cqs! for the per-CQ verdicts.

   Returns the FINAL build! result (verbatim, from the last re-gate) augmented
   with:
     {:cq-loop {:iterations <int>
                :termination-reason <kw>
                :unanswerable-cqs [<cq-text> ...]   ; surfaced honestly (V17)
                :history [{:iteration :build-status :failing-cqs
                           :reextract :graph-delta} ...]}}"
  [ctx {:keys [ontology-id source goal blackboard key-shape judge-fn exit-criterion
                model budget debug? cq-loop-config
                build-fn reextract-fn reconcile-fn evaluate-cqs-fn
                source-uri-sets initial-build-result]}]
  (let [{:keys [max-iterations]} (merge default-cq-loop-config cq-loop-config)
        build-fn (or build-fn skeleton/build!)
        reextract-fn (or reextract-fn focused-reextract!)
        reconcile-fn (if (contains? #{nil true} reconcile-fn)
                       reconcile-graph!
                       reconcile-fn)   ; false disables re-link
        evaluate-cqs-fn
        (or evaluate-cqs-fn
            (fn [ctx ontology-id judge-fn]
              (ontology/evaluate-cqs! {:ctx ctx
                                       :ontology-id ontology-id
                                       :judge-fn judge-fn})))
        re-build! (fn []
                    (build-fn ctx
                              (cond-> {:ontology-id ontology-id
                                       :sources [{:type :inline-concepts :concepts []}]}
                                judge-fn       (assoc :judge-fn judge-fn)
                                exit-criterion (assoc :exit-criterion exit-criterion))))]
    (loop [iteration 0
           build-result initial-build-result
           history []
           unanswerable #{}]
      (cond
        ;; the gate passed (either initially or after a re-extract) — DONE.
        (= :complete (:status build-result))
        (assoc build-result
               :cq-loop {:iterations iteration
                         :termination-reason :cq-gate-passed
                         :unanswerable-cqs (vec unanswerable)
                         :history history})

        ;; budget exhausted — terminate honestly with the still-failing verdict.
        (>= iteration max-iterations)
        (assoc build-result
               :cq-loop {:iterations iteration
                         :termination-reason :budget-exhausted
                         :unanswerable-cqs (vec unanswerable)
                         :history history})

        :else
        ;; :failed-cq — inspect, focused re-extract, re-gate.
        (let [evald (evaluate-cqs-fn ctx ontology-id judge-fn)
              gap-verdicts (failing-cq-verdicts (:evaluated evald))
              ;; CQs still failing that we have NOT already proven unanswerable.
              targetable (filterv #(not (contains? unanswerable (:cq-text %)))
                                  gap-verdicts)]
          (if (empty? targetable)
            ;; every still-failing CQ is already known-unanswerable — terminate
            ;; honestly rather than re-extracting for data the sources lack.
            (assoc build-result
                   :cq-loop {:iterations iteration
                             :termination-reason :all-remaining-unanswerable
                             :unanswerable-cqs (vec unanswerable)
                             :history history})
            (let [rx (reextract-fn ctx {:ontology-id ontology-id
                                        :source source :goal goal
                                        :failing-cqs targetable
                                        :blackboard blackboard
                                        :key-shape key-shape
                                        :model model :budget budget :debug? debug?})
                  ;; re-link the cross-source graph (DT7 reuse) so a closed gap is
                  ;; connected before the re-gate. Skipped when reconcile-fn=false.
                  _ (when (and reconcile-fn (= :ok (:status rx)))
                      (reconcile-fn ctx {:ontology-id ontology-id
                                         :source-uri-sets source-uri-sets}))
                  graph-grew? (and (= :ok (:status rx))
                                   (pos? (+ (long (or (:concepts-added rx) 0))
                                            (long (or (:relationships-added rx) 0)))))
                  ;; UNANSWERABLE detection (honest negative): a focused re-extract
                  ;; aimed at THESE CQs supplied NO new graph data — the sources
                  ;; genuinely lack what they need. Mark them unanswerable so the
                  ;; loop stops chasing them (the gate stays failed-cq, surfaced).
                  newly-unanswerable (if graph-grew?
                                       #{}
                                       (set (map :cq-text targetable)))
                  unanswerable' (into unanswerable newly-unanswerable)
                  next-build (if graph-grew?
                               (re-build!)        ; re-gate over the grown graph
                               build-result)      ; no growth → no point re-gating
                  entry {:iteration (inc iteration)
                         :failing-cqs (mapv :cq-text targetable)
                         :reextract (dissoc rx :transform-output)
                         :graph-grew? graph-grew?
                         :newly-unanswerable (vec newly-unanswerable)
                         :build-status (:status next-build)}]
              (recur (inc iteration) next-build (conj history entry) unanswerable'))))))))

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
