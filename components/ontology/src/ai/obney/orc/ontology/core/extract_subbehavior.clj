(ns ai.obney.orc.ontology.core.extract-subbehavior
  "EB4 — the EXTRACT subbehavior as a delegatable ORC sheet.

   The THIRD real subbehavior on the EB1 registry/delegation pattern (after EB2
   Survey + EB3 Model). A subbehavior is a first-class composed ORC sheet, built
   via the DSL + `build-workflow!`, registered under a stable name → deterministic
   sheet-id, and invoked from a central evolver tree via `:delegate` (child tick,
   isolated blackboard, mapped `:reads`/`:writes`).

   ## What Extract does (its ONE job)

   Turn the EB3 MODEL-SPEC × the SOURCE into the actual DRAFT SET — author a
   per-row extraction transform that ENFORCES the model-spec's grain + scope +
   URI-keying + edges, then APPLY it over the FULL source and collect the drafts
   with per-row error counting. It re-houses DT4 (the focused Transform node) +
   the DT4-grounding field-grounding fix (the real-row-key-shape surfacing) as a
   first-class subbehavior. It does NOT re-profile, re-model, or re-decide
   grain/scope (EB2/EB3 own those) — it TRANSLATES the model-spec into executable
   per-row code and applies it.

   ## A THREE-node sheet: :code → :llm → :code (NOT a single node)

   Extract is genuinely a three-step pipeline, and the steps split cleanly across
   node types (the V17/V20/DT4 lessons made first-class):

     1. `:code` SAMPLE — call `discovery-tree/mechanical-sample-rows` on the
        source to pull a small set of REAL rows DIRECTLY from the V06 per-format
        source-tool registry. This is the DT4-grounding fix: the AUTHORITATIVE
        key-shape (csv → string-keyed maps; sql/excel → keyword-keyed maps),
        read from the medium's own tools — NOT trusted from an LLM-emitted
        profile sample (which may re-key / stringify it). The `:code` node returns
        native Clojure, so the rows cross to the next node PARSED (C1: `:code`
        output is parsed naturally — no JSON-string hazard).

     2. `:llm` AUTHOR — author the per-row transform. Given the REAL sample-row
        KEYS (from node 1) + the EB3 model-spec's entity-types/grain/scope, the
        node emits a `(fn [row] -> {:concept-drafts [...] :relationship-drafts [...]})`
        AS A STRING that mints NODES (not edges), honors the grain (one concept
        per entity, not per raw row) + the scope-filter, and grounds every field
        access in the ACTUAL keys. `:reasoning` is written FIRST (#13). The
        transform-source is a STRING, so it crosses `:delegate` fine (no
        structured-map C1 issue — the C1 `:llm` failure mode only bites MAP
        writes that need flatten+reassemble; a string write is returned verbatim).

     3. `:code` APPLY — call `rlm-discovery/apply-extraction-transform!` with the
        source descriptor + the authored transform-source + the selector. It
        streams the FULL source via V19 `stream-all`, applies the transform to
        EVERY row, and returns the FULL draft set + per-row error count (a high
        failure rate surfaces loudly; the source is not aborted). Native return.

   ## Re-orchestration, not rewrite (8) + domain-agnostic (12)

   The transform-authoring PROSE is re-housed from DT4
   (`discovery-tree/transform-node-prompt` + `key-shape-block`) VERBATIM through
   the promotion seam — no fork of the grain/scope/grounding reasoning. The
   sampling + apply are the REUSED DT4-grounding `mechanical-sample-rows` + V20
   `apply-extraction-transform!` (no fork). The only EB4 additions are the thin
   `:code` wrapper fns (so the V06/V20 fns slot into the code-node `:fn` calling
   convention) and the `:llm`-node I/O framing. No vertical knowledge: the entity
   model, scope, and field access are all decided/grounded from the model-spec +
   the real sampled rows at runtime — no CIP/SOC/IPEDS/industry schema baked in.

   ## C1 — what crosses `:delegate` here

   The OUTPUT contract that crosses `:delegate` back to the central tree is the
   draft set + the report. `:concept-drafts` / `:relationship-drafts` are VECTORS
   produced by a `:code` node (apply-extraction-transform! returns real Clojure
   data); a `:code`-node output is parsed naturally across `:delegate` (the C1
   `:llm` JSON-string failure mode is node-type-specific to the AI executor's
   schema-coercion path, which a `:code` write does not traverse). The blackboard
   still declares STRUCTURED schemas for them so the contract shape is documented
   and any later AI-path routing stays robust (the EB2/EB3 defense-in-depth)."
  (:require [ai.obney.orc.orc-service.interface :as dsl]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]
            [ai.obney.orc.ontology.core.rlm-discovery :as rlm-discovery]
            [ai.obney.orc.ontology.core.resilience :as res]
            [clojure.string :as str]))

;; =============================================================================
;; MC-5 — multi-container traversal helpers
;;
;; A structured source is one OR MANY containers (a csv: one; a relational store:
;; many tables; a workbook directory: many sheets). The uniform container contract
;; (`list-containers` → `[{:name …}]`) makes every format a single code path; the
;; per-container `sample-rows` / `stream-all` already accept a container selector
;; by name. So the multi-container loop iterates the contract's containers and runs
;; the SAME per-container SAMPLE → AUTHOR → APPLY unit once per container, accumu-
;; lating the drafts — never collapsing a many-container source to its largest one.
;; Domain-agnostic (12): it reads container names + keys, names no field/container.
;; =============================================================================

(def default-max-containers
  "The default ceiling on how many containers the multi-container orchestrator
   traverses in one run (MC-0 discipline — every command bounded). A source with
   hundreds of containers would otherwise run hundreds of per-container `:llm`
   authors; this caps it. The report records the TOTAL vs the PROCESSED count so
   the bound is HONEST (no false-green). A caller can raise it via the optional
   `:max-containers` input."
  25)

(defn list-source-containers
  "Enumerate the source's containers via the uniform container contract
   (`list-containers` → `[{:name …}]`). Returns a vector of container maps (one
   entry for a single-container source, many for a relational store / workbook
   directory), or `[]` when the source exposes no container contract. The result's
   `:name` is the per-container selector the SAMPLE / APPLY steps key on. Domain-
   agnostic + format-agnostic — one code path for every medium."
  [source]
  (let [container-contract (requiring-resolve
                            'ai.obney.orc.orc-service.core.source-tools/container-contract)
        cc (try (container-contract {:type (:type source)
                                     :format (:format source)
                                     :path (:path source)})
                (catch Throwable _ nil))]
    (if (and (map? cc) (fn? (:list-containers cc)))
      (vec (try ((:list-containers cc)) (catch Throwable _ nil)))
      [])))

;; =============================================================================
;; The extract contract — the transform contract (re-housed from DT4) + the
;; apply-step return shape (re-housed from V20)
;; =============================================================================

(def transform-contract-keys
  "The DT4-frozen transform-node output contract, re-used verbatim (no drift)
   from `discovery-tree/transform-contract-keys` — the `:llm` AUTHOR node's
   structured output: the per-row transform SOURCE string + the table/sheet
   selector. The `:llm` node also writes `:reasoning` FIRST (#13)."
  (vec dt/transform-contract-keys))

(def draft-contract-keys
  "The EXTRACT subbehavior's public OUTPUT contract — the draft set the APPLY
   `:code` node produces (the V20 `apply-extraction-transform!` return), surfaced
   to the central tree across `:delegate`. `:concept-drafts` /
   `:relationship-drafts` are the SAME draft shapes `compile-discovery-source!`
   ingests (so the caller flows them through compile + V18 integrity unchanged);
   `:extraction-report` carries the per-row coverage/error counts (the no-false-
   green signal — a high `:rows-errored` or a 0-concept result is a FAIL the
   caller can gate on)."
  [:concept-drafts :relationship-drafts :extraction-report])

(def concept-drafts-schema
  "STRUCTURED Malli schema for the `:concept-drafts` write — a VECTOR of draft
   MAPS (each carries at least `:uri` + `:label`; other fields tolerated). A
   concrete `[:vector [:map …]]` (NOT a bare `:map`/`:any`) documents the shape +
   keeps the contract robust across `:delegate`. `{:closed false}` + `:any` leaf
   values tolerate the model-variable per-draft shape (attributes/evidence)."
  [:vector [:map {:closed false}
            [:uri {:optional true} :any]
            [:label {:optional true} :any]]])

(def relationship-drafts-schema
  "STRUCTURED Malli schema for the `:relationship-drafts` write — a VECTOR of
   edge-draft MAPS (each carries `:source-uri` + `:target-uri` + `:predicate`)."
  [:vector [:map {:closed false}
            [:source-uri {:optional true} :any]
            [:target-uri {:optional true} :any]
            [:predicate {:optional true} :any]]])

(def extraction-report-schema
  "STRUCTURED Malli schema for the `:extraction-report` write — the V20
   apply-step coverage/error counts (the no-false-green signal). Mirrors
   `apply-extraction-transform!`'s return minus the verbatim draft vectors
   (those are the sibling writes). `{:closed false}` tolerates extra keys."
  [:map {:closed false}
   [:selector {:optional true} :any]
   [:rows-streamed {:optional true} :any]
   [:rows-ok {:optional true} :any]
   [:rows-errored {:optional true} :any]
   [:windows {:optional true} :any]
   [:concept-count {:optional true} :any]
   [:relationship-count {:optional true} :any]
   [:errors-sample {:optional true} :any]])

;; =============================================================================
;; Node 1 (`:code`) — SAMPLE real rows + their EXACT key-shape (REUSE DT4-grounding
;; `mechanical-sample-rows`; no fork)
;; =============================================================================

(defn sample-rows-code
  "The Node-1 `:code` `:fn`. Reuses `discovery-tree/mechanical-sample-rows` (the
   DT4-grounding fix) to pull REAL rows DIRECTLY from the source's own V06 tools —
   the AUTHORITATIVE key-shape the transform will see at apply time, NOT an
   LLM-re-keyed profile sample. Receives the code-node calling convention
   (`{:inputs {:source <descriptor>}}`) and writes `:sample-rows` (native Clojure
   — crosses to the `:llm` node parsed). Domain-agnostic: it reads keys, names no
   field."
  [{:keys [inputs]}]
  (let [{:keys [source]} inputs
        ;; an authored model-spec MAY carry a selector hint for sql/excel; we let
        ;; mechanical-sample-rows pick the largest table when none is given (its
        ;; documented default), so the surfaced shape is the extraction target.
        selector (:selector source)
        rows (dt/mechanical-sample-rows source selector)]
    {:sample-rows (vec rows)}))

;; =============================================================================
;; Node 2 (`:llm`) — AUTHOR the per-row transform, grounded in the REAL sample-row
;; keys + the model-spec (re-house DT4 `transform-node-prompt` + `key-shape-block`)
;; =============================================================================

(def ^:private runtime-goal-sentinel
  "DT4's `transform-node-prompt` interpolates a goal that ONLY orients. The
   Extract subbehavior gets its modeling decisions from the model-spec (a
   `:delegate` :reads input), so a single sheet serves any goal. We pass this
   sentinel where the DT4 body expects the orienting goal text, then frame the
   `:llm` node to read the model-spec + sample-rows from the blackboard. This
   keeps the DT4 transform-authoring reasoning re-housed verbatim (no fork)."
  "the GOAL is reflected in the model-spec provided to you at runtime")

(defn- llm-io-framing
  "Adapt the DT4 body (written for a `:repl-researcher`'s `(get-input …)` /
   `(final! …)` / source-sampling tool session) to THIS `:llm` node's I/O model.
   An `:llm` node is GIVEN its `:reads` keys as context (the model-spec + the
   REAL sampled rows — already pulled by Node 1) and PRODUCES its `:writes` keys
   as parsed output. There is NO tool session here — the sampling already
   happened in the `:code` node, so the model does NOT sample, does NOT call
   tools, and does NOT emit a tree; it reads the two inputs and emits the
   transform source + selector. This block tells the model exactly that, so the
   re-housed DT4 prose (which references sampling + `final!` + tools) reads
   correctly for an `:llm` node."
  []
  (str "*** HOW THIS NODE WORKS (read carefully) ***\n"
       "You are a single AUTHORING step. You are GIVEN two inputs as context: the "
       "MODEL-SPEC (`model-spec`, the EB3 modeling decision — entity-types with "
       "their grain-strategy + uri-keying-fields, the scope-filter, the edges) and "
       "a set of REAL SAMPLE ROWS (`sample-rows`, pulled DIRECTLY from this source "
       "by the prior step — the EXACT maps the transform will receive at apply "
       "time, with the source's REAL keys). You do NOT call any tools, you do NOT "
       "sample the source yourself (it was already sampled for you), and you do NOT "
       "emit a behavior tree — you AUTHOR a pure per-row transform grounded in "
       "those inputs and PRODUCE the declared output fields below. Ignore any "
       "general guidance about tool sessions, `get-input`, `final!`, `emit-tree!`, "
       "or sampling the source — for THIS node you read the two inputs and emit the "
       "declared output fields.\n\n"
       "GROUND YOUR FIELD ACCESS IN THE PROVIDED `sample-rows`: the REAL ROW KEY "
       "SHAPE block below was derived from those exact sampled rows. Access every "
       "field with the EXACT key (and key TYPE) shown there — a key that does not "
       "appear in the sample is NOT in the row and yields nil for EVERY row "
       "(a 0-concept false-empty). The scope-filter and the in-scope value set, if "
       "any, must likewise be grounded in the real rows' values.\n\n"))

(defn- output-framing
  "Spell out the `:llm` node's declared `:writes` for the model — `:reasoning`
   FIRST (#13), then the transform-source STRING, then the selector. The DT4
   contract-block already lists the transform-source + selector shapes; this
   block names the write keys + the #13 ordering and reiterates that the
   transform-source is a STRING of Clojure source (it crosses `:delegate` as a
   string, not a structured map)."
  []
  (str "\n\n*** YOUR OUTPUT — produce these fields, REASONING FIRST (#13) ***\n"
       "  1. `reasoning` — FIRST, before anything else: briefly think through how "
       "you ground field access in the sampled row keys, how you enforce the "
       "model-spec's grain (one concept per entity, never one-per-raw-row) and "
       "scope-filter, how you key each concept's :uri from the entity's "
       "uri-keying-fields, and which edges you emit. Chain-of-thought BEFORE the "
       "transform.\n"
       "  2. `transform-source` — a STRING of Clojure source that evaluates to a "
       "`(fn [row] {:concept-drafts [...] :relationship-drafts [...]})`. Field "
       "access grounded in the REAL sampled-row keys; grain + scope enforced "
       "(out-of-scope / non-canonical / breakdown rows return EMPTY drafts); every "
       "concept-draft carries :uri + :label; every relationship-draft carries "
       ":source-uri + :target-uri + :predicate. It is a STRING, not a fn value and "
       "not a structured map.\n"
       "  3. `selector` — the EXACT table/sheet name whose ROWS the transform "
       "consumes (for sql/excel, the table/sheet the sample rows came from; omit "
       "or nil for a csv source).\n"
       "Emit the transform-source as a real STRING of Clojure source — do NOT wrap "
       "it in extra quotes or JSON; the downstream apply-step evals it directly."))

(defn transform-author-prompt
  "The EXTRACT AUTHOR node prompt: the DT4 transform-authoring body
   (`discovery-tree/transform-node-prompt` — which already carries the grain/scope/
   URI-keying/edges reasoning AND the DT4-grounding `key-shape-block` when a
   key-shape is provided) re-housed VERBATIM through the promotion seam — wrapped
   with the `:llm`-node I/O framing (no tool session; the rows were pre-sampled by
   Node 1) and the #13 reasoning-first output framing.

   `key-shape` is the REAL sampled-row key shape (`sample-row-key-shape` output);
   when provided, the DT4 body renders the hard grounding block naming the EXACT
   keys + the format's access idiom (the honest-negative fix). With no key-shape
   the block is empty (back-compat). The model-spec is read at runtime from the
   blackboard; the goal is reflected in the model-spec, so the goal slot gets the
   runtime sentinel. Domain-agnostic (12): no vertical knowledge — it authors a
   transform for ANY structured source from the model-spec + the real rows."
  ([] (transform-author-prompt nil))
  ([key-shape]
   (str
    (llm-io-framing)
    ;; Re-house the DT4 transform-authoring body VERBATIM through the promotion
    ;; seam (discipline 8 — no fork of the grain/scope/grounding reasoning). It
    ;; already carries: the ONE-job framing, the grain (canonical-row-filter /
    ;; breakdown-as-entity) + scope enforcement, URI-keying, edges, the EDN/string
    ;; directive, the DT4-grounding key-shape-block (when key-shape is provided),
    ;; and the transform-contract block. The goal slot gets the runtime sentinel.
    (dt/transform-node-prompt runtime-goal-sentinel key-shape)
    ;; The #13 reasoning-first output framing across the two write keys.
    (output-framing))))

(defn- robust-grounding-tail
  "EB9 — the ROBUST author's extra grounding emphasis. The PRIMARY author's most
   common failure (the gate the resilient step checks: a 0-concept false-empty) is
   MIS-GROUNDING — keying field access off a column the rows do not carry, so every
   draft uri is nil. The ROBUST author is the SAME re-housed DT4 body PLUS this
   tail, which spends extra tokens forcing the model to ECHO the real keys it will
   use BEFORE authoring, then ground every access in them. This is a more-robust
   SECOND attempt of the SAME step (re-orchestration, not a new algorithm — #8),
   tried by the `:fallback` only when the primary's output failed the sanity gate.
   Domain-agnostic (#12): it names no field, only the discipline."
  []
  (str "\n\n*** ROBUST GROUNDING (a careful re-attempt) ***\n"
       "A prior attempt at this step produced an EMPTY result — the most likely "
       "cause is mis-grounded field access (a key that is not actually in the "
       "rows). Be EXTRA careful: in your `reasoning`, FIRST list verbatim the EXACT "
       "keys present in the provided `sample-rows` (copy them character-for-"
       "character, with their key TYPE — string vs keyword). Then author the "
       "transform accessing ONLY those exact keys. Do NOT guess, normalize, or "
       "invent a key name; if a field you expect is absent, work from what IS "
       "present. Re-check that the scope-filter value(s) actually occur in the "
       "sampled rows' values before filtering on them — an over-tight or "
       "mis-typed scope filter is the other common cause of an empty result."))

(defn robust-author-prompt
  "The ROBUST author prompt: the primary `transform-author-prompt` PLUS the EB9
   robust-grounding tail. Used as the `:fallback`'s robust alternative for the
   author step."
  ([] (robust-author-prompt nil))
  ([key-shape]
   (str (transform-author-prompt key-shape) (robust-grounding-tail))))

;; =============================================================================
;; Node 3 (`:code`) — APPLY the transform over the FULL source (REUSE V20
;; `apply-extraction-transform!`; no fork) → drafts + per-row error count
;; =============================================================================

(defn apply-transform-code
  "The Node-3 `:code` `:fn`. Reuses `rlm-discovery/apply-extraction-transform!`
   (the V20 apply-step) — NO fork. Receives the code-node calling convention
   (`{:inputs {:source <descriptor> :transform-source <string> :selector <sel>}}`),
   streams the FULL source, applies the authored transform to every row, and
   writes the THREE output-contract keys: the full `:concept-drafts` /
   `:relationship-drafts` vectors (verbatim — never truncated, #11) and the
   `:extraction-report` (the V20 coverage/error counts + the derived
   concept/relationship counts — the no-false-green signal).

   Honest (#5): a transform that throws on some rows increments :rows-errored and
   the source is NOT aborted; the COUNT is returned so a high failure rate (or a
   0-concept result) surfaces loudly for the caller to gate on."
  [{:keys [inputs]}]
  (let [{:keys [source transform-source selector]} inputs
        result (rlm-discovery/apply-extraction-transform!
                {:descriptor source
                 :transform-source transform-source
                 :selector selector
                 ;; Bound the stream so a pathologically large table (e.g. IPEDS
                 ;; national completions, millions of rows) yields a representative
                 ;; SAMPLE within the node timeout rather than timing out wholesale.
                 ;; Small csv/excel sources finish in far fewer windows, so this only
                 ;; bites the huge-table case. The :extraction-report records
                 ;; :windows + :rows-streamed so the truncation is HONEST (no
                 ;; false-green); comprehensive multi-table SQL coverage is the
                 ;; deeper follow-up.
                 :max-windows 50})
        concept-drafts (vec (:concept-drafts result))
        relationship-drafts (vec (:relationship-drafts result))]
    {:concept-drafts concept-drafts
     :relationship-drafts relationship-drafts
     ;; EB9 — a FLAT concept-count so a deterministic resilience sanity
     ;; `:condition` can gate the intermediate state (a `:condition` reads the
     ;; blackboard VALUE, not a nested map field). Mirrors :extraction-report's
     ;; nested :concept-count; written only when the apply node declares it.
     :concept-count (count concept-drafts)
     :extraction-report
     {:selector (:selector result)
      :rows-streamed (:rows-streamed result)
      :rows-ok (:rows-ok result)
      :rows-errored (:rows-errored result)
      :windows (:windows result)
      :concept-count (count concept-drafts)
      :relationship-count (count relationship-drafts)
      :errors-sample (vec (:errors-sample result))}}))

;; =============================================================================
;; MC-5 — the per-container SAMPLE / APPLY `:code` `:fn`s. These REUSE the SAME
;; `mechanical-sample-rows` (Node 1) + `apply-extraction-transform!` (Node 3) the
;; single-container fns call — no fork — but key the per-row ops on the ONE
;; container the loop is currently traversing (the `:container` map, a
;; `list-containers` entry). The container's `:name` is the selector both ops
;; resolve through the contract.
;; =============================================================================

(defn sample-rows-for-container-code
  "The per-container SAMPLE `:code` `:fn`. REUSES `mechanical-sample-rows` (Node 1,
   no fork) keyed on the CURRENT container's `:name` (a `list-containers` entry,
   passed as `:container`) instead of letting the default-container heuristic pick
   one — so a many-container source grounds EACH container, not just the largest.
   Falls back to the source's own `:selector` (then the default) when no container
   is supplied (a single-container source). Domain-agnostic: reads keys, names no
   field."
  [{:keys [inputs]}]
  (let [{:keys [source container]} inputs
        selector (or (:name container) (:selector source))
        rows (dt/mechanical-sample-rows source selector)]
    {:sample-rows (vec rows)}))

(defn apply-transform-for-container-code
  "The per-container APPLY `:code` `:fn`. REUSES `apply-extraction-transform!`
   (Node 3, no fork) — same bounded `:max-windows`, same per-row error counting —
   but FORCES the selector to the CURRENT container's `:name` so the authored
   transform is applied to the container it was grounded on (not the author's
   loosely-emitted selector, which is per-container irrelevant in the loop). When
   no container is supplied (single-container source) it falls back to the author's
   `:selector` — the original single-container behavior, unchanged. Writes the
   per-row draft set + a flat `:concept-count` (the resilience sanity-gate key) +
   the `:extraction-report` (the no-false-green coverage signal)."
  [{:keys [inputs]}]
  (let [{:keys [source transform-source selector container]} inputs
        ;; the container's name wins (the loop is traversing it); fall back to the
        ;; author-emitted selector for the single-container path.
        effective-selector (or (:name container) selector)
        result (rlm-discovery/apply-extraction-transform!
                {:descriptor source
                 :transform-source transform-source
                 :selector effective-selector
                 :max-windows 50})
        concept-drafts (vec (:concept-drafts result))
        relationship-drafts (vec (:relationship-drafts result))]
    {:concept-drafts concept-drafts
     :relationship-drafts relationship-drafts
     :concept-count (count concept-drafts)
     :extraction-report
     {:selector (:selector result)
      :rows-streamed (:rows-streamed result)
      :rows-ok (:rows-ok result)
      :rows-errored (:rows-errored result)
      :windows (:windows result)
      :concept-count (count concept-drafts)
      :relationship-count (count relationship-drafts)
      :errors-sample (vec (:errors-sample result))}}))

;; =============================================================================
;; The delegatable Extract sheet — built on the EB1/EB2/EB3 registry pattern
;; =============================================================================

(defn extract-subbehavior-name
  "Canonical registry name for the Extract subbehavior. Like the EB3 Model sheet
   (and UNLIKE per-source Survey), the Extract node bakes in NO source path — it
   reasons over the MODEL-SPEC + samples the SOURCE at RUNTIME (both `:reads`
   inputs), so a SINGLE Extract sheet serves every source and goal.
   `\"<family>/<behavior>@v<N>\"` — version is part of identity (a new version is
   a new, separately-evolvable sheet; callers pinned to @v1 are never rebuilt out
   from under them)."
  []
  "ontology-extract/extract@v1")

(defn extract-sheet-id-for
  "Look up the deterministic sheet-id for the Extract subbehavior (pure — no
   event-store read). The central tree points its `:delegate` `:target-sheet-id`
   here without rebuilding the subbehavior."
  []
  (dsl/sheet-id-for-name (extract-subbehavior-name)))

(defn extract-per-container-name
  "Canonical registry name for the PER-CONTAINER Extract unit (MC-5). The public
   `@v1` Extract sheet is a thin orchestrator that drives THIS unit once per
   container; this unit is the SAMPLE → AUTHOR → APPLY pass (with-resilience) over
   ONE container. Separately versioned so the unit can evolve under its own
   identity. Like the public sheet it bakes in NO source path — it reads the
   source + model-spec + the one container it is pointed at, at runtime."
  []
  "ontology-extract/extract-per-container@v1")

(defn extract-per-container-sheet-id-for
  "Look up the deterministic sheet-id for the per-container Extract unit (pure).
   The orchestrator resolves the name → id and drives a child tick per container."
  []
  (dsl/sheet-id-for-name (extract-per-container-name)))

(defn extract-per-container-def
  "The PER-CONTAINER Extract unit (MC-5) — the SAMPLE → AUTHOR → APPLY pass over
   ONE container, the building block the multi-container orchestrator drives once
   per container. This IS the original single-container Extract body, RE-HOUSED to
   key its SAMPLE + APPLY on a `:container` input (a `list-containers` entry) so a
   many-container source grounds + applies EACH container — not just the largest.

   Body: a `:code` → `:llm` → `:code` sequence:
     1. `:code` sample-rows-for-container — REUSE `mechanical-sample-rows` keyed on
        the container (Node 1, no fork — the DT4-grounding fix).
     2. `:llm`  author                    — re-house DT4 `transform-node-prompt`,
        `:reasoning` FIRST (#13). The SAME author prompt — it grounds in whatever
        sample-rows it is GIVEN, so it serves any container's columns.
     3. `:code` apply-transform-for-container — REUSE `apply-extraction-transform!`
        forced to the container (Node 3, no fork — the V20 apply-step).

   Contract (the per-container unit's `:reads`/`:writes`):
     :reads  [:model-spec :source :container]
     :writes [:concept-drafts :relationship-drafts :extraction-report]

   `resilient?` (EB9, default true here — the orchestrator wants per-container
   gating) wraps the failure-prone AUTHOR→APPLY in a `with-resilience` sub-tree:
   the PRIMARY (author → apply) is gated on a non-empty draft set (the 0-concept
   false-empty); on a gate failure a ROBUST author re-attempts; if BOTH produce an
   empty set, a troubleshoot `:llm` lands a structured `:diagnosis` and THIS
   container's unit returns a CLEAN `:failure` (never a poisoned empty success —
   #4/#5) — which the orchestrator records HONESTLY in the per-container report."
  [{:keys [model key-shape resilient?] :or {resilient? true}}]
  (let [nm (extract-per-container-name)
        mdl (or model "google/gemini-3-flash-preview")
        ;; the AUTHOR → APPLY sub-pipeline (the failure-prone unit — the
        ;; concept-count is only known AFTER apply). `author-prompt` selects the
        ;; primary or the robust (extra-grounding) author body. The APPLY node
        ;; reads `:container` so it forces the per-container selector.
        author-apply
        (fn [path-label author-prompt]
          (dsl/sequence (str "extract-" path-label)
            (dsl/llm (str "extract-" path-label "-author")
              :model mdl
              :instruction author-prompt
              :reads [:model-spec :sample-rows]
              ;; #13 — :reasoning FIRST (chain-of-thought before the transform).
              :writes [:reasoning :transform-source :selector])
            (dsl/code (str "extract-" path-label "-apply")
              :fn "ai.obney.orc.ontology.core.extract-subbehavior/apply-transform-for-container-code"
              :reads [:source :transform-source :selector :container]
              ;; EB9 — declare the FLAT :concept-count so the sanity :condition
              ;; can gate the intermediate state.
              :writes [:concept-drafts :relationship-drafts
                       :extraction-report :concept-count])))
        resilient-author-apply
        (res/with-resilience
          {:step "extract"
           :primary (author-apply "primary" (transform-author-prompt key-shape))
           :robust  (author-apply "robust"  (robust-author-prompt key-shape))
           ;; deterministic sanity gate — a non-empty draft set (the 0-concept
           ;; false-empty). NO hardcoded phrase matching — a structural threshold.
           :gate {:check {:key :concept-count :op :gt :value 0}}
           :troubleshoot
           {:reads [:model-spec :sample-rows :transform-source
                    :concept-count :extraction-report]
            :model mdl
            :step-label "the per-row extraction transform (author → apply)"
            :expectation (str "a NON-EMPTY, scoped set of concept drafts grounded "
                              "in the source's real keys")}})]
    (dsl/workflow nm
      (dsl/blackboard
       (merge
        {;; :reads — the EB3 model-spec + the source descriptor + the ONE container
         :model-spec [:map {:closed false}]
         :source [:map {:closed false}]
         :container [:map {:closed false}]
         ;; internal inter-node keys
         :sample-rows [:vector [:map {:closed false}]]
         :reasoning :string
         :transform-source :string
         :selector [:maybe :string]
         ;; EB9 — the flat intermediate-state count the sanity gate checks.
         :concept-count :int
         ;; :writes — the draft set + the coverage report (this container's)
         :concept-drafts concept-drafts-schema
         :relationship-drafts relationship-drafts-schema
         :extraction-report extraction-report-schema}
        (when resilient? (res/resilience-blackboard-keys))))
      (if resilient?
        (dsl/sequence "extract-root"
          ;; Node 1 — SAMPLE this container's real rows (REUSE mechanical-sample-rows).
          (dsl/code "sample-rows"
            :fn "ai.obney.orc.ontology.core.extract-subbehavior/sample-rows-for-container-code"
            :reads [:source :container]
            :writes [:sample-rows])
          ;; Node 2/3 — the RESILIENT AUTHOR → APPLY :fallback (EB9), per container.
          resilient-author-apply)
        ;; the flat three-node body (unchanged contract + names) — per container.
        (dsl/sequence "extract-root"
          (dsl/code "sample-rows"
            :fn "ai.obney.orc.ontology.core.extract-subbehavior/sample-rows-for-container-code"
            :reads [:source :container]
            :writes [:sample-rows])
          (dsl/llm "author"
            :model mdl
            :instruction (transform-author-prompt key-shape)
            :reads [:model-spec :sample-rows]
            :writes [:reasoning :transform-source :selector])
          (dsl/code "apply-transform"
            :fn "ai.obney.orc.ontology.core.extract-subbehavior/apply-transform-for-container-code"
            :reads [:source :transform-source :selector :container]
            :writes [:concept-drafts :relationship-drafts :extraction-report]))))))

;; =============================================================================
;; MC-5 — the multi-container ORCHESTRATOR `:code` `:fn`. The public `@v1` Extract
;; sheet is this ONE `:code` node: it enumerates the source's containers and drives
;; the per-container unit (above) once per container via a CHILD TICK, accumulating
;; the drafts across containers + an HONEST per-container report. Why a `:code`
;; orchestrator and not `:map-each`: a `:map-each` leaf must be a PRIMITIVE
;; (ORC-PRINCIPLES §14) — the per-container unit is irreducibly a SAMPLE→AUTHOR→
;; APPLY composite (the AUTHOR is an `:llm`, SAMPLE/APPLY are `:code`), so it cannot
;; be a single primitive leaf; and a `:delegate` map-each leaf was MEASURED (the
;; MC-5 prototype) to collect EMPTY per-iteration writes — a silent false-green
;; (status :success, 0 drafts). A `:code` orchestrator driving N child ticks keeps
;; each container's pass in its OWN isolated blackboard (its OWN resilience gate,
;; its OWN #13 reasoning — no cross-container trample), accumulates explicitly, and
;; cannot scramble or bleed. A single-container source is just N=1.
;; =============================================================================

(defn orchestrate-extract-containers
  "The MC-5 multi-container orchestrator `:code` `:fn`. REUSES the per-container
   Extract unit (`extract-per-container-def`) — no fork — once per container:
   `list-containers` the source, then per container drive a CHILD TICK of the
   per-container unit (the `:code` node receives the full execution `context`,
   which carries the event-store + registries `runtime/execute` needs), read its
   drafts back off the child tick blackboard (discipline 7 — the projection, not a
   bare return), and ACCUMULATE. Writes the public contract: the union of every
   container's `:concept-drafts` / `:relationship-drafts` (verbatim, never
   truncated — #11) + an HONEST `:extraction-report` aggregating per-container
   coverage (containers seen / streamed / with-drafts, rows, and a per-container
   breakdown surfacing a 0-draft OR cleanly-FAILED container — no false-green, #4).

   `model` (optional, in `inputs`) lets the orchestrator pass the model down to the
   per-container unit; defaults to gemini-3-flash-preview. Domain-agnostic (12): it
   names no field/container — the per-container unit grounds in real keys at
   runtime."
  [{:keys [inputs] :as context}]
  (let [{:keys [source model-spec max-containers]} inputs
        ;; the execution context for child ticks = the orchestrator's context minus
        ;; the node-scoped keys (`runtime/execute` needs the event-store + registries
        ;; + pubsub + cache the executor threaded into this `:code` node's context).
        child-ctx (dissoc context :inputs :execution-context)
        sub-sheet-id (extract-per-container-sheet-id-for)
        all-containers (list-source-containers source)
        ;; BOUND the traversal (MC-0 discipline — every command bounded). A source
        ;; with hundreds of containers would run hundreds of per-container :llm
        ;; authors; the bound caps that at a sane ceiling. The report records BOTH
        ;; the total + the processed count so the truncation is HONEST (no
        ;; false-green) — comprehensive whole-source coverage is the deeper follow-up.
        cap (or max-containers default-max-containers)
        containers (vec (take cap all-containers))
        results
        (mapv
         (fn [container]
           (let [child-tick-id (random-uuid)
                 r (dsl/execute child-ctx sub-sheet-id
                                {"model-spec" model-spec
                                 "source" source
                                 "container" container}
                                :timeout-ms 280000
                                :tick-id child-tick-id
                                :parent-tick-id (:tick-id context))
                 ;; discipline 7 — read the per-container drafts back off the CHILD
                 ;; tick blackboard (the projection), not the bare execute return.
                 bb (dsl/get-tick-blackboard child-ctx child-tick-id)
                 concept-drafts (vec (get-in bb [:concept-drafts :value]))
                 relationship-drafts (vec (get-in bb [:relationship-drafts :value]))
                 report (get-in bb [:extraction-report :value])]
             {:container (:name container)
              :status (:status r)
              :concept-drafts concept-drafts
              :relationship-drafts relationship-drafts
              :concept-count (count concept-drafts)
              :relationship-count (count relationship-drafts)
              :rows-streamed (:rows-streamed report)
              :rows-errored (:rows-errored report)
              :diagnosis (get-in bb [:diagnosis :value])}))
         containers)
        all-concepts (vec (mapcat :concept-drafts results))
        all-rels (vec (mapcat :relationship-drafts results))]
    {:concept-drafts all-concepts
     :relationship-drafts all-rels
     :extraction-report
     {:containers-total (count all-containers)
      :containers-processed (count results)
      ;; back-compat alias — :containers-seen is the processed count.
      :containers-seen (count results)
      :containers-streamed (count (filter #(pos? (or (:rows-streamed %) 0)) results))
      :containers-with-drafts (count (filter #(pos? (or (:concept-count %) 0)) results))
      :containers-failed (count (filter #(= :failure (:status %)) results))
      :rows-streamed (reduce + 0 (keep :rows-streamed results))
      :concept-count (count all-concepts)
      :relationship-count (count all-rels)
      ;; the HONEST per-container breakdown — a 0-draft / cleanly-FAILED container
      ;; surfaces here (no false-green): its :concept-count is 0 and/or :status is
      ;; :failure with a :diagnosis the troubleshoot landed.
      :per-container (mapv #(select-keys % [:container :status :concept-count
                                            :relationship-count :rows-streamed
                                            :rows-errored :diagnosis])
                           results)}}))

(defn extract-subbehavior-def
  "The Extract subbehavior workflow definition (MC-5 — MULTI-container).

   The public `@v1` Extract sheet is a thin ORCHESTRATOR: ONE `:code` node that
   enumerates the source's containers and drives the per-container SAMPLE → AUTHOR
   → APPLY unit (`extract-per-container-def`) once per container, accumulating the
   drafts across containers. So a relational store extracts from EVERY table and a
   workbook directory from EVERY sheet — not just the largest container. A single-
   container source (csv / one-table sql / one workbook) is simply N=1: the SAME
   orchestrator, one child tick. The hard, irreducibly-composite per-container unit
   (the `:llm` AUTHOR with `:code` SAMPLE/APPLY around it) runs in its OWN isolated
   child blackboard — its OWN `with-resilience` gate + its OWN `:reasoning` (#13) —
   so the per-container passes never race / bleed (the ORC-PRINCIPLES §14 hazard a
   `:map-each` composite leaf would hit).

   Contract (the public `:reads`/`:writes`) — UNCHANGED from single-container:
     :reads  [:model-spec :source]
     :writes [:concept-drafts :relationship-drafts :extraction-report]
   `:concept-drafts` / `:relationship-drafts` are the UNION across containers; the
   `:extraction-report` aggregates per-container coverage HONESTLY (a 0-draft or
   cleanly-failed container surfaces — no false-green).

   `key-shape` (optional) flows to the per-container unit's author prompt. `model`
   (optional) flows down to the per-container `:llm` author. `resilient?` (default
   true) gates the per-container unit (NOT the orchestrator — resilience is per
   container, so one bad container fails CLEANLY without poisoning the others).

   IMPORTANT: the per-container unit must be REGISTERED before this sheet runs —
   `register-extract-subbehavior!` registers BOTH (the orchestrator resolves the
   unit by its deterministic name at runtime)."
  [{:keys [model key-shape resilient?]}]
  (let [nm (extract-subbehavior-name)]
    (dsl/workflow nm
      (dsl/blackboard
       {;; public :reads — the EB3 model-spec + the source descriptor
        :model-spec [:map {:closed false}]
        :source [:map {:closed false}]
        ;; optional bound on how many containers to traverse (default ceiling
        ;; applies when unset). Declared so a caller can pass it in via :reads.
        :max-containers [:maybe :int]
        ;; public :writes — the draft set (union across containers) + the report
        :concept-drafts concept-drafts-schema
        :relationship-drafts relationship-drafts-schema
        :extraction-report extraction-report-schema})
      (dsl/sequence "extract-root"
        ;; the multi-container orchestrator — list-containers + per-container child
        ;; ticks of the per-container unit + accumulation (REUSE, no fork).
        (dsl/code "orchestrate-containers"
          :fn "ai.obney.orc.ontology.core.extract-subbehavior/orchestrate-extract-containers"
          :reads [:source :model-spec :max-containers]
          :writes [:concept-drafts :relationship-drafts :extraction-report])))))

(defn register-extract-subbehavior!
  "REGISTER (build, idempotent) the Extract subbehavior sheets and return the
   PUBLIC `@v1` orchestrator's deterministic sheet-id. Registers BOTH the per-
   container unit (the orchestrator drives it by name at runtime) AND the public
   orchestrator sheet. Re-registering unchanged defs is a no-op (same ids). The
   central evolver tree resolves the public name → id via `extract-sheet-id-for`
   and `:delegate`s to it (unchanged from single-container)."
  [ctx {:keys [model key-shape resilient?]}]
  ;; the per-container unit FIRST (the orchestrator resolves it by name at runtime).
  (dsl/build-workflow! ctx (extract-per-container-def
                            {:model model :key-shape key-shape :resilient? resilient?}))
  ;; the public orchestrator sheet — its id is what the central tree delegates to.
  (dsl/build-workflow! ctx (extract-subbehavior-def
                            {:model model :key-shape key-shape :resilient? resilient?})))
