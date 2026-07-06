(ns ai.obney.orc.ontology.core.model-subbehavior
  "EB3 — the MODEL subbehavior as a delegatable ORC sheet.

   The SECOND real subbehavior on the EB1 registry/delegation pattern (after EB2
   Survey). A subbehavior is a first-class composed ORC sheet, built via the DSL
   + `build-workflow!`, registered under a stable name → deterministic sheet-id,
   and invoked from a central evolver tree via `:delegate` (child tick, isolated
   blackboard, mapped `:reads`/`:writes`).

   ## What Model does (its ONE job)

   Turn the GOAL × the EB2 PROFILE into the MODELING DECISION — the entity model:
   the entity types (each with its URI-keying fields + GRAIN strategy), the SCOPE
   filter the goal asks for, the edges between entity types, PLUS two EB3
   additions: the EMBED-WORTHY FIELDS (P2 — the fields the embed+index step EB7
   embeds) and the CANDIDATE TBox/AXIOMS (feeds EB6 — disjointness / functional /
   subClass candidates the axiom step asserts).

   It re-houses the proven DT3 grain/scope reasoning verbatim (the V17/V20
   over-extraction fix made a first-class decision) and ADDS only the two new
   output signals. It does NOT re-profile and does NOT author a transform (EB4
   owns the transform).

   ## A SINGLE `:llm` node — NOT a repl-researcher (F3 does not apply)

   Modeling is single-turn reasoning over goal + profile: read the inputs, think,
   emit the model-spec. There is NO iterative tool-using session here (Survey
   already explored the source; the profile carries the sample). So the body is
   ONE `:llm` node — not a `:repl-researcher`, no recursion, no F3 Phase-2 tick.

   ## C1 (EB1/EB2 carry-forward) — the model-spec crosses `:delegate` PARSED, and
   for an `:llm` node the STRUCTURED schema is the LOAD-BEARING fix

   The model-spec is a MAP that crosses the `:delegate` seam back to the central
   tree. C1 is NODE-TYPE-specific (refined by the EB1/EB2 inspections):

     - For EB2's `:repl-researcher` Survey, the PRIMARY fix was the PROMPT
       (`final!` with real EDN), the structured schema defense-in-depth.
     - For THIS `:llm` Model node it is the OPPOSITE: an `:llm` node returns TEXT
       that the AI executor parses against the node's `:writes` BLACKBOARD SCHEMA.
       A BARE `:map` write has no field structure to parse into, so the executor
       hands back a JSON STRING — which then crosses `:delegate` verbatim as a
       string (the C1 failure mode). The fix is to declare a STRUCTURED Malli
       `[:map …]` schema for every map write: the executor flattens the structured
       fields into DSCloj output fields and REASSEMBLES them into a nested PARSED
       MAP (`build-module` / `flatten-output-schema` / `reassemble-flattened-
       outputs`). So for an `:llm` node the structured `[:map …]`/`[:map-of …]`
       schema is the LOAD-BEARING mechanism, not defense-in-depth.

   Both map writes here (`:model-spec`, `:candidate-axioms`) therefore declare
   STRUCTURED schemas; the live verify reads the model-spec back off the PARENT
   tick blackboard via the projection and asserts `(map? …)` (a JSON string would
   FAIL the assert).

   ## #13 — `:reasoning` written FIRST

   The `:llm` node writes `:reasoning` FIRST in its `:writes` (chain-of-thought
   before the structured model-spec — force think-before-emit). This is a SINGLE
   node (no `:parallel` / `:map-each`), so a bare `:reasoning` key is fine (no
   blackboard-trample risk).

   ## Re-orchestration, not rewrite (8) + domain-agnostic (12)

   The grain/scope DECISION PROSE is re-housed from DT3
   (`discovery-tree/model-node-prompt`) verbatim through the promotion seam — no
   fork of the over-extraction reasoning. The only EB3 prompt additions are the
   embed-fields + candidate-axioms instruction tails and the `:llm`-node I/O
   framing (read `:reads` keys / emit the `:writes`, no `final!`). No vertical
   knowledge: the entity model, scope, embed-fields, and axioms are all decided
   from goal + profile at runtime — no CIP/SOC/IPEDS/industry schema baked in."
  (:require [ai.obney.orc.orc-service.interface :as dsl]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]
            [ai.obney.orc.ontology.core.resilience :as res]
            [ai.obney.orc.ontology.core.synthesize-vocab-subbehavior :as synth]
            [ai.obney.orc.ontology.core.graph-context-snapshot :as gcs]
            [clojure.string :as str]))

;; =============================================================================
;; The model-spec contract (re-housed from DT3) + the EB3 additions
;; =============================================================================

(def embed-fields-key
  "EB3 addition #1 (P2): the embed-worthy FIELDS the Model commits to for the
   embed+index step (EB7). EB2's profile SURFACED candidate embed-worthy fields
   by value-shape; the Model DECIDES which of them are worth embedding given the
   entity model + goal (e.g. the title/name/description field of a kept entity,
   not a code/id). Carried verbatim from the profile's embed signal when the model
   keeps it. A vector of field-name strings (empty if the source is all codes)."
  :embed-fields)

(def linking-keys-key
  "GC-11a addition: the cross-source LINKING-KEY column NAMES carried forward onto
   the model-spec so the Extract AUTHOR (which reads the model-spec, NOT the
   profile) sees them and carries their per-row VALUES into every draft's
   `:attributes`. The Survey discovered them on the profile (`:linking-keys` —
   CODES/KEYS that likely identify the SAME entity in OTHER sources); the Model
   copies them here (optionally normalized to the shared vocabulary's column names
   via its `:aliases`). The cross-source spine (GC-11b) recovers the linking VALUE
   from `:attributes` via GC-1 `recover-via-value`, so the value MUST be carried —
   and a linking key is often NOT the entity's own keying field (IPEDS keys a
   program by local-num; its linking key is the CIP code), which is exactly why
   GC-1's keying-field-only carry left it absent. A vector of field-name strings;
   nil/absent when the survey discovered no linking key (the no-op path)."
  :linking-keys)

(def candidate-axioms-key
  "EB3 addition #2: CANDIDATE TBox/axioms that feed EB6 (the axiom step). The
   Model proposes ontology-level constraints implied by the entity model — e.g.
   two entity types are DISJOINT, an identifying key is FUNCTIONAL/an inverse-
   functional id, or one type is a subClassOf another. EB6 reads these candidates
   and asserts the real axioms via the S07 axiom commands. A vector of axiom-
   candidate maps; empty if the model proposes none."
  :candidate-axioms)

(def model-spec-contract-keys
  "The EB3 Model model-spec contract: the DT3-frozen model-contract keys
   (`:entity-types :scope-filter :edges`) PLUS the EB3 embed-fields signal. The
   frozen DT3 keys are re-used verbatim (no drift) from
   `discovery-tree/model-contract-keys`. `:candidate-axioms` is a SEPARATE
   sibling write (its own structured schema), not nested in the model-spec, so
   EB6 can `:delegate`-read it without the rest of the spec. GC-11a adds the
   `:linking-keys` carry-forward (the cross-source linking-key column NAMES the
   Extract AUTHOR carries the VALUES of into `:attributes`)."
  (conj (vec dt/model-contract-keys) embed-fields-key linking-keys-key))

(def model-spec-contract-schema
  "C1 — the STRUCTURED Malli `[:map …]` schema for the model-spec contract. For
   an `:llm` node this is the LOAD-BEARING C1 fix, and it has TWO parts proven by
   the EB3 prototype:

     1. OUTER STRUCTURE — declared as the `:model-spec` write's blackboard schema,
        the AI executor FLATTENS the `[:map …]` fields into separate DSCloj output
        fields and REASSEMBLES them into a nested map. A bare `:map` has no fields
        to flatten, so the `:llm` executor returns the whole map as a JSON STRING
        (the C1 failure mode).

     2. PER-FIELD TYPE — each flattened field must carry a CONCRETE structural
        spec (`[:vector …]` / `[:map …]` / `[:maybe …]`), NOT `:any`. The
        prototype proved that an `:any`-typed flattened field is NOT parsed by
        DSCloj — it comes back as the raw EDN/JSON TEXT (`:entity-types` arrived as
        the STRING \"[{:type …}]\"). A concrete collection spec makes DSCloj parse
        the field's text into real Clojure data. So each field is typed to its
        collection SHAPE while its LEAF values stay `:any` (the DT3 model-variance
        tolerance: grain-strategy may be a keyword or a `\":canonical-row-filter\"`
        string; an inner field may be prose or structured).

   `{:closed false}` (outer + the entity/edge inner maps) tolerates extra keys the
   model adds (e.g. `:canonical-row-marker`, `:breakdown-key`). A consumer still
   reads each field TOLERANTLY (the DT3/DT5 carry-forward; grain-strategy via
   `discovery-tree/normalize-grain-strategy`)."
  [:map {:closed false}
   ;; concrete VECTOR-OF-MAPS so DSCloj parses it (not :any → raw string)
   [:entity-types {:optional true}
    [:vector {:description "The entity types discovered in this source"}
     [:map {:closed false}
      [:type {:optional true} [:string {:description "The entity type name, e.g. Occupation"}]]
      [:uri-keying-fields {:optional true}
       [:vector {:description "Column name(s) that uniquely identify this entity"} :string]]
      ;; PROTOTYPE — string :enum (not :any / bare keyword): the model emits a QUOTED
      ;; string value (valid in JSON AND EDN), killing the bare-keyword-in-JSON hybrid
      ;; that broke both parsers. normalize-grain-strategy already accepts the string form.
      [:grain-strategy {:optional true}
       [:enum "canonical-row-filter" "breakdown-as-entity"]]]]]
   ;; a MAP or nil — :maybe keeps the structural parse while allowing no-scope.
   ;; MT-11 — concrete leaf types + descriptions (not :any): an :any leaf comes
   ;; back as raw EDN/JSON TEXT (the same parse root the :entity-types prototype
   ;; fixed), so DSCloj must be told the field is a string / a vector of strings.
   [:scope-filter {:optional true}
    [:maybe [:map {:closed false}
             [:field {:optional true}
              [:string {:description "The column/field the scope filters on"}]]
             [:values {:optional true}
              [:vector {:description "The value(s) the goal restricts the scope to"} :string]]]]]
   [:edges {:optional true}
    [:vector [:map {:closed false}
              [:source-type {:optional true}
               [:string {:description "The entity type this edge points FROM"}]]
              [:target-type {:optional true}
               [:string {:description "The entity type this edge points TO"}]]
              [:predicate {:optional true}
               [:string {:description "The relationship name connecting the two entity types"}]]]]]
   [embed-fields-key {:optional true} [:vector :string]]
   ;; GC-11a — the cross-source LINKING-KEY column NAMES the Model copies forward
   ;; from the profile's discovered :linking-keys (optionally vocabulary-normalized).
   ;; OPTIONAL [:maybe …] so the no-linking-key path is behavior-preserving: a source
   ;; whose survey discovered no linking key carries no :linking-keys and the Extract
   ;; AUTHOR's linking-value carry is a no-op. A CONCRETE [:vector :any] (not :any) so
   ;; the `:llm` executor parses it into real Clojure data, not raw text (the C1
   ;; per-field fix). Domain-agnostic: the column NAMES are the survey's runtime
   ;; discovery — no field baked here.
   [linking-keys-key {:optional true} [:maybe [:vector :any]]]])

(def candidate-axioms-schema
  "C1 — the STRUCTURED Malli schema for the `:candidate-axioms` write (the EB6
   feed). The natural shape is a VECTOR of axiom-candidate MAPS, wrapped under a
   one-key structured map `{:axioms [...]}` — the `[:map …]` wrapper is what makes
   the `:llm` executor FLATTEN + reassemble the write rather than return a JSON
   string (the C1 outer-structure fix). The `:axioms` field is typed
   `[:vector [:map …]]` (NOT `:any`) so DSCloj parses the vector into real Clojure
   data (the C1 per-field-type fix the prototype proved). `{:closed false}` + `:any`
   leaf values keep it tolerant of whatever axiom shapes the model proposes (EB6
   reads them tolerantly and maps onto the S07 axiom commands)."
  [:map {:closed false}
   [:axioms {:optional true}
    [:vector [:map {:closed false}
              [:kind {:optional true} :any]
              [:rationale {:optional true} :any]]]]])

;; =============================================================================
;; The Model prompt — re-housed DT3 grain/scope body + the EB3 additions
;; =============================================================================

(def ^:private runtime-goal-sentinel
  "DT3's `model-node-prompt` interpolates the goal inline. The Model subbehavior
   gets its goal at RUNTIME as a `:delegate` :reads input (so one sheet serves any
   goal — the goal is not part of sheet identity). We pass this sentinel where the
   DT3 body expects the goal text, then frame the `:llm` node to read the real
   goal from the `goal` blackboard input. This keeps the DT3 grain/scope reasoning
   re-housed verbatim (no fork) while sourcing the goal from the blackboard."
  "the GOAL provided to you at runtime as the blackboard input `goal`")

(defn- llm-io-framing
  "Adapt the DT3 body (written for a `:repl-researcher`'s `(get-input …)` /
   `(final! …)` mechanics) to THIS `:llm` node's I/O model. An `:llm` node is
   given its `:reads` keys as context and produces its `:writes` keys as parsed
   output — there is NO `get-input`/`final!`/tool session here. This block tells
   the model exactly that, so the re-housed DT3 prose (which references those
   primitives in passing) reads correctly for an `:llm` node."
  []
  (str "*** HOW THIS NODE WORKS (read carefully) ***\n"
       "You are a single REASONING step. You are GIVEN as context: the GOAL "
       "(`goal`), the source PROFILE (`profile`, the structured output of the "
       "earlier Survey step), and OPTIONALLY a shared `vocabulary` (a discovered "
       "cross-source set of canonical entity types — see the constraint below). "
       "You do NOT call any tools, you do NOT explore the "
       "source, and you do NOT emit a behavior tree — you THINK over the goal + "
       "profile and PRODUCE the structured outputs described below. Ignore any "
       "general guidance about tool sessions, `get-input`, `final!`, or "
       "`emit-tree!`; for THIS node you simply read the two inputs and emit the "
       "declared output fields.\n\n"))

(defn- embed-fields-block
  "EB3 addition #1 (P2): instruct the node to DECIDE the embed-worthy FIELDS for
   the embed+index step (EB7). Domain-agnostic — it asks which field(s) of the
   KEPT entity model carry free-text meaning worth embedding, grounded in the
   profile's surfaced embed signal, naming no domain field."
  []
  (str "\n\nALSO decide the EMBED-WORTHY FIELDS (consumed by a later "
       "embed+index step):\n"
       "  - The profile may carry an embed-signal (a field set it flagged as "
       "free-text / natural-language values — a name, title, label, or "
       "description column whose value is human-readable prose, NOT a pure "
       "code/id/number). From the ENTITY MODEL you just decided, pick the "
       "field(s) whose VALUES are worth embedding for semantic retrieval — the "
       "free-text field(s) of the entities you are keeping. Judge by the VALUE, "
       "not the column name. Empty if the source is all codes/ids/numbers with "
       "no free-text field. Emit them as `embed-fields` (a vector of field-name "
       "strings)."))

(defn- candidate-axioms-block
  "EB3 addition #2: instruct the node to propose CANDIDATE TBox/axioms (the EB6
   feed). Domain-agnostic — it asks for ontology-level constraints IMPLIED BY the
   entity model the node just decided (disjointness, functional/identifying keys,
   subClass), each justified, naming no domain axiom."
  []
  (str "\n\nALSO propose CANDIDATE AXIOMS (TBox constraints, consumed by a later "
       "axiom step):\n"
       "  - From the entity model you decided, propose any ontology-level "
       "constraints it IMPLIES. Common kinds:\n"
       "      :disjoint   — two entity types are mutually exclusive (no instance "
       "is both).\n"
       "      :functional — an identifying/URI-keying field that takes at most ONE "
       "value per instance (a single-valued identifier / inverse-functional id).\n"
       "      :sub-class  — one entity type is a specialization (subClassOf) of "
       "another.\n"
       "  - Propose ONLY constraints the entity model + profile actually support; "
       "do NOT invent constraints the data doesn't justify. Each candidate is a "
       "MAP with at least `:kind` (one of the kinds above) plus the types/fields "
       "it concerns and a short `:rationale`. Emit them as `candidate-axioms` "
       "under the key `:axioms` (a vector of these maps; empty if none are "
       "warranted)."))

(defn- linking-keys-block
  "GC-11a: instruct the Model to COPY the profile's discovered `:linking-keys` (the
   cross-source code/key COLUMN NAMES that identify the SAME entity in OTHER
   sources) forward onto the model-spec as `:linking-keys`. The Extract AUTHOR
   reads the model-spec, NOT the profile, so unless the linking-key NAMES ride the
   model-spec the apply-step has no list to carry the VALUES of. Domain-agnostic
   (#12): the column names come from the profile at runtime; this names no field.
   When the profile carries no linking-keys, emit an empty `:linking-keys` (the
   no-op path — behavior-preserving)."
  []
  (str "\n\nALSO carry the LINKING KEYS forward (consumed by the extraction step "
       "to connect this source to OTHERS):\n"
       "  - The profile carries `:linking-keys` — the cross-source CODE/KEY column "
       "NAME(s) that likely identify the SAME real entity in OTHER sources (a shared "
       "code an other source would also carry). COPY those column name(s) onto your "
       "model-spec as `:linking-keys` (a vector of the column-name strings). If a "
       "shared `vocabulary` is provided and it canonicalizes the column name for the "
       "matched entity, prefer the column name THIS source actually carries for that "
       "key (the value must be recoverable from THIS source's rows). A linking key "
       "is OFTEN a DIFFERENT column from an entity's own uri-keying-field, so carry "
       "it EVEN WHEN it is not a keying field. Emit `:linking-keys` as part of the "
       "model-spec map (an empty vector if the profile surfaced none)."))

(defn- vocabulary-constraint-block
  "GC-6: CONSTRAIN entity-type naming to a SHARED DISCOVERED vocabulary so the SAME
   real entity resolves to the SAME `(:type, :uri-keying-fields)` across sources (so
   GC-1 mints ONE canonical URI and the graph connects instead of fragmenting).

   The `vocabulary` input (when present) is the cross-source vocabulary the
   synthesize-vocab step discovered: a set of canonical entity types, each with a
   canonical `:type`, canonical `:uri-keying-fields`, the `:aliases` the sources
   used, and a `:description`. The constraint: when THIS source's entity matches a
   vocabulary entry — by its `:description` + `:aliases` (NOT exact label) — the
   model MUST use that entry's canonical `:type` + `:uri-keying-fields` (not a free
   name of its own). A genuinely-NOVEL entity (one no vocabulary entry covers) is
   STILL minted as a new type (the discovery-preservation guard — #10/#4: do NOT
   suppress real discovery). Domain-agnostic (#12): the vocabulary itself is
   discovered at runtime; this block names no vertical entity-type."
  []
  (str "\n\nCONSTRAIN your entity-type NAMING to the SHARED VOCABULARY (when "
       "provided):\n"
       "  - You may be given a `vocabulary` input: a DISCOVERED, cross-source set "
       "of CANONICAL entity types (`:canonical-entity-types`), each with a "
       "canonical `:type`, canonical `:uri-keying-fields`, a list of `:aliases` "
       "(the different names other sources used for the SAME entity), and a "
       "`:description`. It exists so the SAME real entity gets the SAME identity "
       "across every source — do not undo that by naming your types freely.\n"
       "  - For EACH entity you decide to model: check whether it MATCHES one of "
       "the canonical entity types — match by the entity's MEANING against the "
       "entry's `:description` + `:aliases` (a semantic/alias match, NOT an exact "
       "label match; your source may call it something else). If it matches, you "
       "MUST use that entry's canonical `:type` (verbatim) as your entity's "
       "`:type`, and its canonical `:uri-keying-fields` — but mapped to the COLUMN "
       "NAME this source actually carries for that key (the value must be "
       "recoverable from THIS source's rows). Do NOT invent a different type name "
       "for an entity the vocabulary already covers.\n"
       "  - If an entity you find is GENUINELY NOVEL — no canonical entry covers it "
       "by meaning or alias — STILL model it as a NEW entity type (do NOT drop real "
       "discovery to force-fit the vocabulary). Real new entities are expected and "
       "wanted.\n"
       "  - If NO vocabulary is provided, name your entity types from the profile "
       "as usual."))

(defn- grain-reify-block
  "GM-1 (ROUND 2 — TUNED for precision): MODEL AGAINST THE GRAPH SO FAR + a NARROW,
   HIGH-BAR reify EXCEPTION. Round 1's block over-fired: it framed EVERY numeric
   column as a reifiable 'measure' and read a secondary CATEGORICAL column (an
   element/attribute label, a wide-vs-long layout artifact) as a second 'subject
   dimension', so clean single-subject sources (an entity with many rating columns,
   a code crosswalk, a plain entity catalog) grew spurious Observation nodes +
   dimension edges (the 447/338 → 701/992 regression).

   The tune: reification is a RARE EXCEPTION, not the default. The DEFAULT — for
   normal entity sources and single-subject multi-numeric sources alike — is to
   model EXACTLY as the grain/scope step above already says (entities keyed from
   identifying-keys; numbers as flat native-number `:attributes`). Reify to an
   Observation ONLY when a single row's numbers are genuinely co-qualified by TWO OR
   MORE INDEPENDENT REAL-WORLD ENTITIES (not entity × attribute-category). When in
   any doubt: do NOT reify.

   The `graph-context` input (when present) previews the graph BUILT SO FAR so the
   Model can ATTACH to existing entities (reducing duplicate mints) — it is NOT a
   licence to ADD nodes/edges; a clean entity source models the same with or without
   it. Deterministic code only PROVIDES this context — it NEVER rewrites the
   decision.

   Domain-agnostic (#12): names NO vertical entity-type, column, or format — the
   discriminator is purely 'is a single row's number co-qualified by ≥2 independent
   real-world entities?', not any column list."
  []
  (str "\n\nMODEL AGAINST THE GRAPH SO FAR (attach, don't duplicate):\n"
       "  - You may be given a `graph-context` input: a preview of the graph BUILT "
       "SO FAR — its existing entity types (`:entity-types`, each with a `:type`, a "
       "`:count`, a `:uri-keying-sample` of real existing URIs, and the "
       "`:attribute-fields` already on that type), the `:predicates` in use, and a "
       "`:content-sample` of real existing concepts. These entities ALREADY EXIST. "
       "Use it ONLY to ATTACH to them (match by meaning/key so the SAME real entity "
       "resolves to the SAME node) — it is NOT a reason to ADD entity types, "
       "Observation nodes, or edges you would not otherwise model. A normal entity "
       "source is modeled the SAME whether or not a graph-context is present.\n"
       "\n"
       "THE DEFAULT (this is what nearly every source does — do this UNLESS the rare "
       "reify test below clearly fires):\n"
       "  - Model the entities the source defines exactly as the GRAIN + SCOPE step "
       "above already tells you: one node per real entity (keyed from its "
       "identifying-keys), edges only where a linking-key genuinely connects two "
       "entity types. Carry EVERY numeric column (a count, rate, percentile, score, "
       "amount, rating) as a flat native-number `:attribute` on the entity it "
       "describes. This includes a source that is ONE kind of entity with MANY "
       "numeric columns (e.g. one thing carrying dozens of ratings/scores): that is "
       "a legitimate single-subject multi-numeric entity — keep it FLAT, mint NO "
       "Observation, add NO extra edges. A wide table that lists many measure "
       "columns for one subject, OR a long table that names the measure in one "
       "column and its value in another (a measure/element/attribute LABEL column), "
       "is STILL that ONE subject's own attributes — the label column is a LAYOUT "
       "artifact, NOT a second real-world subject. Plain entity lists, code "
       "crosswalks, and catalogs of things are all this default case; the rule "
       "below changes NOTHING for them.\n"
       "\n"
       "A RELATIONSHIP BETWEEN TWO ENTITIES IS AN EDGE, NOT A NODE:\n"
       "  - If a row simply PAIRS one entity with another (a mapping, a crosswalk, a "
       "correspondence, a lookup between two code systems, a 'this relates to that' "
       "link) and carries NO numeric measure of its own, it is a plain EDGE between "
       "those two entity types — model it in `:edges`. NEVER mint a node for the "
       "pairing itself (no 'mapping' node, no 'pathway' node, no 'correspondence' "
       "node, no 'link' node — one node per PAIR is exactly the cross-product "
       "explosion to avoid). A two-column code-to-code table is edges, PERIOD.\n"
       "\n"
       "THE RARE REIFY EXCEPTION (apply ONLY when BOTH conditions below hold — "
       "otherwise use the default or a plain edge):\n"
       "  - Condition 1 — there are actual NUMERIC MEASURE VALUES in the row (a "
       "count, rate, percentile, score, amount) that need somewhere to live. If a "
       "row has NO numeric measures, there is NOTHING to reify — it is an entity "
       "(default) or an edge (a pairing). Reification exists ONLY to house measures "
       "that belong to a multi-entity grain.\n"
       "  - Condition 2 — those measures are meaningless unless you name MORE THAN "
       "ONE INDEPENDENT REAL-WORLD ENTITY (the value is specific to entity A AND a "
       "DIFFERENT entity B, two distinct subjects that each exist in their own right "
       "as their own nodes), optionally plus a period/time qualifier. A category/"
       "level/element/band that merely LABELS or SLICES ONE subject's own measures "
       "is NOT a second entity and does NOT qualify.\n"
       "  - ONLY when BOTH hold: model ONE Observation entity type whose ONE instance "
       "is ONE grain-tuple (its `:uri-keying-fields` are the COMBINATION of the two "
       "dimension keys, so each distinct pair is one node — NOT one node per raw row, "
       "NOT a cross-product of unrelated values). Its numeric MEASURES ride as "
       "native-number `:attributes`; its two ENTITY-dimensions become EDGES to the "
       "EXISTING entities from `graph-context` (match them by meaning/key — do NOT "
       "re-mint them). Any period/band qualifier rides as an attribute on the "
       "observation, not as its own node.\n"
       "  - WHEN IN DOUBT, DO NOT REIFY. If EITHER condition is unclear, use the "
       "default (numbers as flat attributes on the one entity) or a plain edge (a "
       "pairing). A missed reification is a minor loss; an over-eager one fabricates "
       "one node per row and fragments the graph.\n"))

(defn- output-framing
  "Spell out the `:llm` node's declared `:writes` for the model — `:reasoning`
   FIRST (#13), then the model-spec map (with the embed-fields folded in), then
   the candidate-axioms map. The DT3 contract-block already lists the model-spec
   field shapes; this block names the THREE separate write keys + the #13 ordering
   so the `:llm` executor produces the right blackboard shape."
  []
  (str "\n\n*** YOUR OUTPUT — produce these fields, REASONING FIRST (#13) ***\n"
       "  1. `reasoning` — FIRST, before anything else: briefly think through the "
       "grain decision (per entity), the scope decision (what the goal asks for), "
       "the embed-fields, and the candidate axioms. Chain-of-thought BEFORE the "
       "structured output.\n"
       "  2. `model-spec` — a MAP with EXACTLY these keys: "
       (str/join ", " (map str model-spec-contract-keys)) ". `:entity-types`, "
       "`:edges`, `:embed-fields`, `:linking-keys` are VECTORS; `:scope-filter` is "
       "a MAP or nil. "
       "(See the field shapes described above; `:embed-fields` as decided in the "
       "embed-worthy step.)\n"
       "  3. `candidate-axioms` — a MAP `{:axioms [<candidate maps>]}` as decided "
       "in the candidate-axioms step (empty axioms vector if none).\n"
       "Emit real structured data for the maps/vectors, NOT JSON strings and NOT "
       "prose strings — the downstream steps read them as parsed Clojure data."))

(defn model-prompt
  "The Model node prompt: the DT3 grain/scope/entity-model reasoning body
   (`discovery-tree/model-node-prompt`) re-housed VERBATIM through the promotion
   seam — the V17/V20 over-extraction fix as a focused node — wrapped with the
   `:llm`-node I/O framing and EXTENDED with the two EB3 outputs (embed-fields +
   candidate-axioms) and the #13 reasoning-first output framing.

   The goal is read at RUNTIME from the `goal` blackboard input (a `:delegate`
   :reads input) so a single Model sheet serves any goal; the profile is the other
   :reads input. Domain-agnostic (12): no vertical knowledge — it models ANY
   structured source from goal + profile."
  []
  (str
   (llm-io-framing)
   ;; Re-house the DT3 grain/scope/entity-model body VERBATIM through the
   ;; promotion seam (discipline 8 — no fork of the over-extraction reasoning).
   ;; It already carries: the ONE-job framing, the TWO load-bearing decisions
   ;; (GRAIN via :canonical-row-filter|:breakdown-as-entity, SCOPE via
   ;; :scope-filter from the goal), URI-keying from the profile, the edges step,
   ;; the EDN-not-strings directive, and the model-contract block. The goal slot
   ;; gets the runtime-read sentinel (the real goal is read as a blackboard input).
   (dt/assemble-node-prompt :model {:goal runtime-goal-sentinel})
   ;; The two EB3 additions.
   (embed-fields-block)
   (candidate-axioms-block)
   ;; GC-11a — copy the profile's discovered linking-keys forward onto the model-spec.
   (linking-keys-block)
   ;; GC-6 — constrain entity-type naming to the shared discovered vocabulary.
   (vocabulary-constraint-block)
   ;; GM-1 — model against the graph so far + the grain/reify principle (measures of a
   ;; grain reify as Observations IFF multi-subject-qualified; single-subject → flat).
   (grain-reify-block)
   ;; The #13 reasoning-first output framing across the three write keys.
   (output-framing)))

;; =============================================================================
;; The delegatable Model sheet — built on the EB1/EB2 registry pattern
;; =============================================================================

(defn model-subbehavior-name
  "Canonical registry name for the Model subbehavior. UNLIKE Survey, the Model
   node bakes in NO source path — it reasons over the GOAL + the PROFILE (both
   runtime `:reads` inputs), so a SINGLE Model sheet serves every source and goal.
   `\"<family>/<behavior>@v<N>\"` — version is part of identity (a new version is a
   new, separately-evolvable sheet; callers pinned to @v1 are never rebuilt out
   from under them)."
  []
  "ontology-model/model@v1")

(defn model-sheet-id-for
  "Look up the deterministic sheet-id for the Model subbehavior (pure — no
   event-store read). The central tree points its `:delegate` `:target-sheet-id`
   here without rebuilding the subbehavior."
  []
  (dsl/sheet-id-for-name (model-subbehavior-name)))

(defn- robust-model-tail
  "EB9 — the ROBUST model author's extra grounding emphasis. A failure-prone Model
   output is one with NO usable entity model (an empty `:entity-types`) — the
   profile was thin or the goal was mis-read. The ROBUST author is the SAME prompt
   PLUS this tail forcing the model to re-derive at least one well-grounded entity
   type from whatever the profile shows. A more-robust SECOND attempt of the SAME
   step (#8), tried by the `:fallback` only when the primary's spec failed the
   sanity gate. Domain-agnostic (#12): names no field, only the discipline."
  []
  (str "\n\n*** ROBUST MODELING (a careful re-attempt) ***\n"
       "A prior attempt produced NO usable entity model (an empty entity-types). "
       "Re-derive carefully: in your `reasoning`, FIRST list verbatim the concrete "
       "entity-candidates + identifying keys the PROFILE actually surfaced, then "
       "commit to AT LEAST ONE entity type grounded in them (with its uri-keying "
       "field(s) + grain). Do NOT return an empty model — if the profile is thin, "
       "model the single most-supported entity the goal needs. Re-check the scope "
       "against the goal so you don't over-narrow to nothing."))

(defn robust-model-prompt
  "The ROBUST Model prompt: the primary `model-prompt` PLUS the EB9 robust tail."
  []
  (str (model-prompt) (robust-model-tail)))

(defn model-subbehavior-def
  "The Model subbehavior workflow definition.

   Body: a single `:llm` node — single-turn reasoning over goal + profile. NOT a
   `:repl-researcher` (no tool session, no recursion, F3 does not apply).

   Contract (the public `:reads`/`:writes`):
     :reads  [:goal :profile]
     :writes [:reasoning :model-spec :candidate-axioms]   (#13 reasoning FIRST)
   The two MAP writes (`:model-spec`, `:candidate-axioms`) declare STRUCTURED
   `[:map …]` schemas — the LOAD-BEARING C1 fix for the `:llm` node-type.

   `resilient?` (EB9, optional) wraps the `:llm` model node in a `with-resilience`
   sub-tree: the PRIMARY author is gated by a SEMANTIC `:llm-condition` (is the
   model-spec a USABLE entity model with at least one entity type?); on a gate
   failure a ROBUST author (extra grounding emphasis) re-attempts; if BOTH still
   produce no usable model, a troubleshoot `:llm` lands a structured `:diagnosis`
   and the subbehavior returns a CLEAN `:failure` (never an empty model dressed as
   success — #4/#5). The Model gate uses the `:llm-condition` flavor (judgment over
   the model-spec MAP — there is no flat count to gate deterministically), whereas
   Extract uses the deterministic `:condition` flavor; together they exercise both
   gate flavors of the builder. The public `:reads`/`:writes` contract is
   UNCHANGED."
  [{:keys [model resilient?]}]
  (let [nm (model-subbehavior-name)
        mdl (or model "google/gemini-3-flash-preview")
        model-node
        (fn [path-label prompt]
          (dsl/llm (str "model-" path-label)
            :model mdl
            :instruction prompt
            ;; GC-6 — also read the shared discovered :vocabulary (when threaded in).
            ;; GM-1 — also read the :graph-context snapshot (when threaded in).
            :reads [:goal :profile :vocabulary gcs/graph-context-key]
            ;; #13 — :reasoning FIRST (chain-of-thought before the structured spec).
            :writes [:reasoning :model-spec :candidate-axioms]))
        body
        (if resilient?
          (res/with-resilience
            {:step "model"
             :primary (model-node "primary" (model-prompt))
             :robust  (model-node "robust" (robust-model-prompt))
             ;; SEMANTIC gate — the model-spec MAP cannot be checked by a flat
             ;; deterministic :condition, so a yes/no :llm-condition judges
             ;; usability (NOT a hardcoded phrase list — #7).
             :gate {:llm-check
                    {:model mdl
                     :reads [:model-spec]
                     :instruction
                     (str "You are a sanity gate. Below is a model-spec produced by "
                          "a modeling step. Answer YES only if it is a USABLE entity "
                          "model — it has a NON-EMPTY entity-types list with at least "
                          "one entity type that carries a type name and at least one "
                          "uri-keying field. Answer NO if entity-types is empty/"
                          "missing or no entity type is usable. Answer strictly yes "
                          "or no.")}}
             :troubleshoot
             {:reads [:goal :profile :model-spec]
              :model mdl
              :step-label "the entity-model derivation (goal × profile → model-spec)"
              :expectation (str "a usable entity model — a non-empty entity-types "
                                "list grounded in the profile")}})
          (dsl/llm "model"
            :model mdl
            :instruction (model-prompt)
            ;; GC-6 — also read the shared discovered :vocabulary (when threaded in).
            ;; GM-1 — also read the :graph-context snapshot (when threaded in).
            :reads [:goal :profile :vocabulary gcs/graph-context-key]
            :writes [:reasoning :model-spec :candidate-axioms]))]
    (dsl/workflow nm
      (dsl/blackboard
       (merge
        {:goal :string
         ;; the profile is read tolerantly; declare it structured so
         ;; it can also be delegated IN as a parsed map.
         :profile [:map {:closed false}]
         ;; GC-6 — the shared DISCOVERED vocabulary (optional; threaded in by the
         ;; central evolver after synthesize-vocab). STRUCTURED so it can be
         ;; delegated IN as a parsed map; [:maybe …] tolerates the no-vocab path.
         :vocabulary [:maybe synth/vocabulary-schema]
         ;; GM-1 — the graph-context snapshot (optional; threaded in by the central
         ;; evolver's pre-Model step). STRUCTURED so it crosses :delegate parsed;
         ;; [:maybe …] tolerates the empty-graph first-source path.
         gcs/graph-context-key [:maybe gcs/graph-context-schema]
         :reasoning :string
         ;; C1 — STRUCTURED schemas for the map contracts that cross
         ;; :delegate; NEVER a bare :map (the :llm-node failure mode).
         :model-spec model-spec-contract-schema
         :candidate-axioms candidate-axioms-schema}
        ;; EB9 — the resilience-internal keys (#13 reasoning + structured
        ;; :diagnosis + the always-fail sentinel) when resilient.
        (when resilient? (res/resilience-blackboard-keys))))
      (dsl/sequence "model-root" body))))

(defn register-model-subbehavior!
  "REGISTER (build, idempotent) the Model subbehavior sheet and return its
   deterministic sheet-id. Re-registering an unchanged def is a no-op (same id).
   The central evolver tree resolves the name → id via `model-sheet-id-for` and
   `:delegate`s to it."
  [ctx {:keys [model resilient?]}]
  (dsl/build-workflow! ctx (model-subbehavior-def {:model model :resilient? resilient?})))
