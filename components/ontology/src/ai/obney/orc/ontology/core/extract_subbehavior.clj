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

(def default-max-extract-windows
  "GC-7 — the default ceiling on `stream-all` windows the per-container extract
   consumes. Each window is one bounded read (the sql per-window hard cap is 100
   rows), so this caps a pathologically large table (IPEDS `C2022_A` ≈ 1.6M rows)
   to a representative sample within the node timeout rather than streaming the
   whole table. The extraction report records `:windows`, `:rows-streamed`, AND a
   `:truncated?` flag (windows hit the ceiling → more rows remained) so the cap is
   HONEST (no false-green, Discipline 4). Behavior-preserving: this is the value
   (50) the two extract apply nodes hardcoded before GC-7 named it."
  50)

(defn extract-truncated?
  "GC-7 — HONEST truncation signal: the stream hit the `:max-windows` ceiling, so
   the table almost certainly had more rows than were sampled. `stream-all` stops
   either at a short/empty window (table exhausted → NOT truncated) or at the
   `:max-windows` guard (ceiling bit → truncated). A windows count at the ceiling
   is the ceiling-bit case."
  [windows max-windows]
  (boolean (and (number? windows) (number? max-windows) (>= windows max-windows))))

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
   ;; MC-6 — the cross-container relating summary (edge count + honest
   ;; unmaterialized-relation surfacing). `:any` tolerates the nested map shape.
   [:cross-container-relations {:optional true} :any]
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
  (let [{:keys [source transform-source selector max-windows]} inputs
        ;; GC-7 — the window ceiling is the named default (50), caller-overridable
        ;; for an even tighter cap on a known-huge table.
        max-windows (or max-windows default-max-extract-windows)
        result (rlm-discovery/apply-extraction-transform!
                {:descriptor source
                 :transform-source transform-source
                 :selector selector
                 ;; Bound the stream so a pathologically large table (e.g. IPEDS
                 ;; national completions, millions of rows) yields a representative
                 ;; SAMPLE within the node timeout rather than timing out wholesale.
                 ;; Small csv/excel sources finish in far fewer windows, so this only
                 ;; bites the huge-table case. The :extraction-report records
                 ;; :windows + :rows-streamed + :truncated? so the truncation is
                 ;; HONEST (no false-green); the model's :canonical-row-filter grain
                 ;; collapses breakdown rows, the cap is the backstop.
                 :max-windows max-windows})
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
      ;; GC-7 — honest truncation signal (the cap bit; more rows remained).
      :max-windows max-windows
      :truncated? (extract-truncated? (:windows result) max-windows)
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
  (let [{:keys [source transform-source selector container max-windows]} inputs
        ;; the container's name wins (the loop is traversing it); fall back to the
        ;; author-emitted selector for the single-container path.
        effective-selector (or (:name container) selector)
        ;; GC-7 — named default window ceiling (caller-overridable for tighter caps).
        max-windows (or max-windows default-max-extract-windows)
        result (rlm-discovery/apply-extraction-transform!
                {:descriptor source
                 :transform-source transform-source
                 :selector effective-selector
                 :max-windows max-windows})
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
      ;; GC-7 — honest truncation signal (the cap bit; more rows remained).
      :max-windows max-windows
      :truncated? (extract-truncated? (:windows result) max-windows)
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

;; =============================================================================
;; MC-6 — CROSS-CONTAINER relating. The per-container passes (above) yield concepts
;; from N containers but each per-row transform only emits INTRA-row edges, so the
;; cross-table/cross-sheet edges are missing (relationship-count = 0 across the
;; containers). MC-6 fills them in DETERMINISTICALLY (set logic, NO :llm): for each
;; source-level relation {:from "A.col" :to "B.col" :via "key"} it joins entities
;; from container A against entities from container B that share the same `key`
;; VALUE, emitting one relationship-draft per matched pair. Domain-agnostic (#12):
;; the join column comes from the source's OWN `relations`, never a baked field.
;;
;; KEY-VALUE RECOVERY (the wrinkle): each entity needs its `:via` key VALUE to be
;; matched. We recover it from the concept-draft's `:attributes` — the AUTHOR is
;; prompted to "carry the row's measures as :attributes", so the row's key columns
;; live there. We match the `:via` column name case/type-tolerantly against the
;; draft's attribute keys (a keyword vs string vs differently-cased key still
;; resolves), and compare the VALUES as normalized strings. We deliberately do NOT
;; parse the URI: a composite/prefixed URI ties recovery to a minting convention
;; and to which fields are uri-keying-fields, which is brittle + couples this to a
;; vertical. When a draft carries no attribute for the `:via` key, that entity
;; contributes no edge (honest); when an entire relation materializes zero pairs it
;; is surfaced in :unmaterialized-relations — NEVER a fabricated edge (#4/#5).
;; =============================================================================

(defn source-relations-fn
  "Return a `(container-name -> [{:from :to :via} ...])` fn for the source via the
   uniform container contract's `:relations` op (MC-3), or nil when the source
   exposes no relations op (e.g. a csv single container — no cross-container edges,
   not an error). Reuses `container-contract` (no fork). Domain-agnostic — it reads
   the source's own declared/heuristic relations, names no field."
  [source]
  (let [container-contract (requiring-resolve
                            'ai.obney.orc.orc-service.core.source-tools/container-contract)
        cc (try (container-contract {:type (:type source)
                                     :format (:format source)
                                     :path (:path source)})
                (catch Throwable _ nil))
        relations (when (map? cc) (:relations cc))]
    (when (fn? relations)
      (fn relations-for [container-name]
        (try (vec (relations container-name)) (catch Throwable _ []))))))

(defn- normalize-key-name
  "Normalize a column/attribute key NAME for case/type-tolerant matching: lower-case
   the string form (a keyword's `name`, or the string itself) and strip every
   non-alphanumeric character. So `:MyKey`, `\"my_key\"`, `\"MY-KEY\"` all collapse
   to `\"mykey\"`. Domain-agnostic — purely structural normalization."
  [k]
  (when (some? k)
    (-> (if (keyword? k) (name k) (str k))
        (str/lower-case)
        (str/replace #"[^a-z0-9]" ""))))

(defn- normalize-value
  "Normalize a join VALUE to a canonical comparable string: trim + lower-case its
   string form, dropping a trailing `.0` an int-as-double picks up (so `100` and
   `100.0` join). Returns nil for a nil/blank value (no join key → no edge)."
  [v]
  (when (some? v)
    (let [s (-> (str v) str/trim)]
      (when (seq s)
        (-> s str/lower-case (str/replace #"\.0$" ""))))))

(defn- recover-via-value
  "Recover a concept-draft's `:via` key VALUE from its `:attributes`, matching the
   `:via` column name case/type-tolerantly (see `normalize-key-name`). Returns the
   normalized join value, or nil when the draft carries no attribute for that key
   (the honest-negative signal — that entity simply contributes no edge)."
  [draft via]
  (let [target (normalize-key-name via)
        attrs (:attributes draft)]
    (when (and target (map? attrs))
      (some (fn [[k v]]
              (when (= target (normalize-key-name k))
                (normalize-value v)))
            attrs))))

;; =============================================================================
;; GC-1 — CANONICAL URI minting (the keystone). The per-container AUTHOR writes its
;; OWN free-form `:uri` for each concept-draft, so two containers can mint DIFFERENT
;; URIs for the SAME real entity (one keys `program:010000`, another keys
;; `degree_program/01.00.00`). EB5 reconcile merges by canonical `:uri`, so those
;; never collapse → the graph fragments and the cross-container edges strand.
;;
;; The fix: stop trusting the AUTHOR's free `:uri` for IDENTITY. Each concept-draft
;; carries an explicit `:entity-type` (the model-spec `:type` it is — emitted by the
;; AUTHOR's transform, the step that already decided "this row → a program"). This
;; DETERMINISTIC post-step looks up that type's `:uri-keying-fields` in the
;; model-spec, recovers the field VALUES from the draft's `:attributes` (REUSING the
;; SAME `normalize-key-name` / `normalize-value` MC-6 uses — it deliberately does NOT
;; parse the old URI, which would tie identity to a minting convention), and mints a
;; canonical URI `<normalized-entity-type>/<normalized-key-1>[/<normalized-key-2>…]`
;; in ONE format so the SAME entity is byte-identical across containers. It builds an
;; old→canonical rewrite map from the concept-drafts and applies it to concept-draft
;; `:uri` AND relationship-draft `:source-uri`/`:target-uri` together (no dangling
;; edges). It runs BEFORE MC-6 cross-container relating, so MC-6 joins/edges operate
;; on canonical URIs.
;;
;; HONEST DEGRADE (#4/#5): a draft with NO `:entity-type`, or an `:entity-type` not
;; in the model-spec, or whose keying-field VALUES can't be recovered from
;; `:attributes`, keeps its ORIGINAL `:uri` and is SURFACED in `:degraded` — NEVER
;; given a fabricated canonical URI. Domain-agnostic (#12): the entity-type, the
;; keying fields, and the values all come from the model-spec + the draft at runtime;
;; this names NO domain field.
;; =============================================================================

(defn- entity-type-keying-fields
  "Build a `{normalized-type -> [uri-keying-field …]}` index from the model-spec's
   `:entity-types`. The lookup key is the entity-type NAME normalized the SAME way
   `normalize-key-name` normalizes any name (case/separator-tolerant) so a draft's
   `:entity-type` matches the spec's `:type` regardless of casing/spacing. A spec
   entry with no `:type` or an empty `:uri-keying-fields` is dropped (it cannot key
   a canonical URI). Reads the spec TOLERANTLY (the DT3 value-shape tolerance:
   `:uri-keying-fields` may be a vector of strings)."
  [model-spec]
  (reduce
   (fn [acc {:keys [type uri-keying-fields]}]
     (let [t (normalize-key-name type)
           fields (vec (remove nil? (or uri-keying-fields [])))]
       (if (and t (seq fields))
         (assoc acc t fields)
         acc)))
   {}
   (or (:entity-types model-spec) [])))

(defn- mint-canonical-uri
  "Mint the canonical URI for a concept-draft given its entity-type's
   `:uri-keying-fields`. Recover each field's VALUE from the draft's `:attributes`
   (REUSE `recover-via-value` — the SAME MC-6 attribute recovery + normalization;
   NOT parsing the old URI). The canonical URI is
   `<normalized-entity-type>/<normalized-key-1>[/<normalized-key-2>…]` — a FIXED
   separator (`/`), the spec's field ORDER, and per-value normalization, so the SAME
   entity is byte-identical across containers. Returns nil when ANY keying field's
   value cannot be recovered (an honest degrade — a partial key would mis-merge or
   fabricate); the caller keeps the draft's original URI and surfaces it."
  [normalized-type uri-keying-fields draft]
  (let [values (map (fn [field] (recover-via-value draft field)) uri-keying-fields)]
    (when (every? some? values)
      (str normalized-type "/" (str/join "/" values)))))

(defn canonicalize-drafts
  "GC-1 — the PURE canonicalizer (model-spec + drafts → rewritten drafts). NO Grain,
   NO LLM — unit-testable in isolation.

   For each concept-draft: look up its `:entity-type`'s `:uri-keying-fields` in the
   model-spec, recover those values from `:attributes` (MC-6 helpers — NOT the URI),
   and mint a canonical URI in ONE deterministic format. Build an old→canonical
   rewrite map from the concept-drafts and apply it to concept-draft `:uri` AND
   relationship-draft `:source-uri`/`:target-uri` (so the two same-entity drafts
   collapse AND no edge dangles).

   A draft whose `:entity-type` is absent / unknown to the spec, or whose keying-field
   values can't be recovered, KEEPS its original `:uri` and is surfaced in
   `:degraded` (#4/#5 — never a fabricated canonical URI). Its URI is therefore NOT
   in the rewrite map, so edges pointing at it stay pointed at its original URI.

   Returns:
     {:concept-drafts       [<draft with :uri rewritten where minted> …]
      :relationship-drafts  [<draft with endpoints rewritten via the map> …]
      :uri-rewrite-map      {<old-uri> <canonical-uri> …}   (only the rewrites)
      :degraded             [{:uri <old-uri> :entity-type <as-given> :reason <kw>} …]}"
  [model-spec concept-drafts relationship-drafts]
  (let [type-index (entity-type-keying-fields model-spec)
        ;; Per concept-draft: decide the canonical URI (or an honest-degrade reason).
        decided
        (mapv
         (fn [draft]
           (let [et (:entity-type draft)
                 norm-et (normalize-key-name et)
                 fields (get type-index norm-et)]
             (cond
               (nil? et)
               {:draft draft :canonical nil :reason :no-entity-type}

               (nil? fields)
               {:draft draft :canonical nil :reason :unknown-entity-type}

               :else
               (if-let [canon (mint-canonical-uri norm-et fields draft)]
                 {:draft draft :canonical canon}
                 {:draft draft :canonical nil :reason :unrecoverable-keying-values}))))
         (or concept-drafts []))
        ;; old→canonical rewrite map — only the drafts that successfully minted a
        ;; canonical URI (a degraded draft keeps its original URI, so it is NOT in the
        ;; map; edges to it stay on the original URI — no fabrication, no dangling).
        rewrite-map (reduce (fn [m {:keys [draft canonical]}]
                              (if canonical
                                (assoc m (:uri draft) canonical)
                                m))
                            {}
                            decided)
        rewrite (fn [uri] (get rewrite-map uri uri))
        out-concepts (mapv (fn [{:keys [draft canonical]}]
                             (if canonical (assoc draft :uri canonical) draft))
                           decided)
        out-rels (mapv (fn [r]
                         (cond-> r
                           (:source-uri r) (update :source-uri rewrite)
                           (:target-uri r) (update :target-uri rewrite)))
                       (or relationship-drafts []))
        degraded (->> decided
                      (remove :canonical)
                      (mapv (fn [{:keys [draft reason]}]
                              {:uri (:uri draft)
                               :entity-type (:entity-type draft)
                               :reason reason})))]
    {:concept-drafts out-concepts
     :relationship-drafts out-rels
     :uri-rewrite-map rewrite-map
     :degraded degraded}))

;; GC-2 — the per-`:via`-value DISTINCT-ENTITY fan-out cap. After GC-1 every draft's
;; `:uri` is canonical, so `index-by-via-value` collapses the rows-per-entity
;; multiplication to ONE draft per distinct `:uri` BEFORE pairing (case (a) — the
;; dominant OOM driver — disappears). This cap bounds the GENUINE distinct-entity
;; 1:many fan-out (case (b)): if a single `:via` value pairs more than this many
;; DISTINCT (source,target) entities, the excess is DROPPED and surfaced HONESTLY in
;; `:truncated-relations` (never a silent top-N). Measured on the REAL IPEDS
;; completions table (`C2022_A`): distinct entities per join key max ≈ 581, and only
;; 3 of 6042 join values exceed 500 distinct pairs against an institution-level
;; table — so 500 admits real fan-out while blocking the combinatorial blow-up (the
;; naive rows×rows pairing reached 1.5M+ pairs / 2929² per single key). Domain-
;; agnostic — a structural bound on pair count, naming no field.
(def default-max-pairs-per-via 500)

(defn- index-by-via-value
  "Build {normalized-via-value -> [draft ...]} for a container's concept-drafts,
   keying each draft by its recovered `:via` value (drafts with no recoverable
   value are omitted — they cannot join).

   GC-2: each bucket is DEDUPED to ONE draft per DISTINCT canonical `:uri` (the
   first draft seen for a `:uri` wins — they are the SAME entity after GC-1, so the
   choice is immaterial). This collapses the rows-per-entity multiplication BEFORE
   the cross-container pairing, so the loop iterates distinct-entities ×
   distinct-entities, not rows × rows — the dominant MC-6 OOM driver."
  [drafts via]
  (reduce-kv
   (fn [acc v ds]
     (assoc acc v (->> ds
                       (reduce (fn [{:keys [seen out] :as a} d]
                                 (let [u (:uri d)]
                                   (if (contains? seen u)
                                     a
                                     {:seen (conj seen u) :out (conj out d)})))
                               {:seen #{} :out []})
                       :out)))
   {}
   (reduce (fn [acc d]
             (if-let [v (recover-via-value d via)]
               (update acc v (fnil conj []) d)
               acc))
           {} drafts)))

(defn- via-predicate
  "Derive the cross-container edge predicate from the relation's `:via` column —
   domain-agnostic (the via name comes from `relations`, not hardcoded). A stable,
   descriptive label so the within-source join is legible in the graph."
  [via]
  (str "related-via-" via))

(defn cross-container-relationship-drafts
  "DETERMINISTIC cross-container relating (MC-6). Given the per-container results
   (each `{:container <name> :concept-drafts [...]}`) and a `relations-fn`
   (`container-name -> [{:from :to :via} ...]`, or nil for a no-relations source),
   join entities ACROSS containers by the shared `:via` key VALUE and return
     {:relationship-drafts        [{:source-uri :target-uri :predicate} ...]
      :unmaterialized-relations   [{:from :to :via :reason} ...]
      :truncated-relations        [{:from :to :via :value :distinct-pairs :cap
                                     :dropped-pairs :reason} ...]
      :pairs-considered           <int>}.

   For each source relation `{:from \"A.col\" :to \"B.col\" :via \"key\"}`: index
   container A's drafts and container B's drafts by their recovered `:via` value,
   then for every shared value emit one edge per (A-entity × B-entity) pair. A SELF
   pair (same URI both sides — the two containers minted the SAME URI for the same
   key, so EB5 reconcile-MERGES them, no edge needed) is dropped. A relation that
   yields zero pairs (no carryable value on a side) surfaces in
   `:unmaterialized-relations` — NEVER a fabricated edge (#4/#5). The container set
   for `:to` is matched on the table/sheet NAME prefix of `:to` (the relation's own
   target). Deterministic order (sorted by source then target URI) so output is
   stable. NO `:llm` — pure set logic over the drafts the per-container passes
   already produced. Domain-agnostic — the join column is the relation's own
   `:via`, not a baked field.

   GC-2 — BOUNDED. `index-by-via-value` dedupes each bucket to one draft per
   DISTINCT canonical `:uri` (post-GC-1) BEFORE pairing, so the loop iterates
   distinct-entities × distinct-entities, not rows × rows (case (a) — pure
   row-multiplication — collapses; the dominant MC-6 OOM driver disappears). Where a
   single `:via` value still pairs more than `max-pairs-per-via` (default
   `default-max-pairs-per-via`) DISTINCT entities (case (b) — a genuine 1:many
   fan-out), the excess pairs are DROPPED and surfaced in `:truncated-relations`
   with the dropped count + reason — an HONEST cap, never a silent top-N (#4/#5).
   `:pairs-considered` reports the bounded distinct-pair work the loop performed."
  ([per-container-results relations-fn]
   (cross-container-relationship-drafts per-container-results relations-fn
                                        default-max-pairs-per-via))
  ([per-container-results relations-fn max-pairs-per-via]
   (let [;; {container-name -> [concept-draft ...]} for the join's two sides.
         by-container (into {}
                            (map (juxt :container #(vec (:concept-drafts %))))
                            per-container-results)
         container-of (fn [table-or-sheet]
                        ;; the relation's :from/:to carry "<container>.<col>"; the
                        ;; container is the part before the first dot.
                        (first (str/split (str table-or-sheet) #"\." 2)))
         edges (atom [])
         unmat (atom [])
         truncated (atom [])
         ;; GC-2 — HONEST accounting of the DISTINCT-entity pairs the bound let
         ;; through (the iteration is now distinct-entities × distinct-entities, not
         ;; rows × rows — `index-by-via-value` deduped the buckets to one draft per
         ;; canonical :uri). Surfaced so the bound is observable, not implicit.
         pairs-considered (atom 0)
         seen (atom #{})]
     (when (fn? relations-fn)
       (doseq [{a-name :container} per-container-results
               rel (relations-fn a-name)
               :let [{:keys [from to via]} rel
                     a-container (or (container-of from) a-name)
                     b-container (container-of to)
                     a-drafts (get by-container a-container)
                     b-drafts (get by-container b-container)]
               ;; only relate containers that are BOTH in this run's result set
               ;; (a relation pointing at an un-processed container can't join here).
               :when (and (seq a-drafts) (seq b-drafts) (some? via))]
         (let [;; GC-2 — buckets are DEDUPED to distinct canonical :uri before
               ;; pairing (case (a) — row-multiplication — collapsed).
               a-idx (index-by-via-value a-drafts via)
               b-idx (index-by-via-value b-drafts via)
               shared (filter #(contains? b-idx %) (keys a-idx))]
           (if (seq shared)
             (doseq [v shared
                     :let [a-ds (get a-idx v)
                           b-ds (get b-idx v)
                           ;; GC-2 — bound the GENUINE distinct-entity fan-out for
                           ;; THIS :via value. After dedup these are distinct
                           ;; entities; if their pair count exceeds the cap we take
                           ;; the cap and surface the dropped count HONESTLY (#4/#5)
                           ;; — never a silent top-N. Deterministic order (sorted by
                           ;; :uri) so the kept pairs are stable.
                           a-sorted (sort-by :uri a-ds)
                           b-sorted (sort-by :uri b-ds)
                           full-pairs (* (count a-sorted) (count b-sorted))
                           pairs (for [a-d a-sorted b-d b-sorted] [a-d b-d])
                           capped? (> full-pairs max-pairs-per-via)
                           kept (if capped? (take max-pairs-per-via pairs) pairs)]]
               (swap! pairs-considered + (count kept))
               (when capped?
                 (swap! truncated conj
                        {:from from :to to :via via :value v
                         :distinct-pairs full-pairs
                         :cap max-pairs-per-via
                         :dropped-pairs (- full-pairs max-pairs-per-via)
                         :reason (str "distinct-entity fan-out " full-pairs
                                      " for this join value exceeds the per-value cap "
                                      max-pairs-per-via
                                      "; the excess pairs are dropped (an entity-merge"
                                      " hint is preferable to N×M weak edges)")}))
               (doseq [[a-d b-d] kept
                       :let [su (:uri a-d) tu (:uri b-d)
                             ;; de-dup symmetric/duplicate edges by an UNDIRECTED
                             ;; pair + via (relations are emitted both A->B and B->A
                             ;; by the shared-key heuristic). A SORTED pair (not a
                             ;; set) is the undirected key — a set of two equal URIs
                             ;; throws.
                             dkey [(sort [su tu]) via]]
                       ;; drop SELF pairs (same URI → reconcile-merge, no edge) + dups
                       :when (and su tu (not= su tu) (not (contains? @seen dkey)))]
                 (swap! seen conj dkey)
                 (swap! edges conj {:source-uri su :target-uri tu
                                    :predicate (via-predicate via)})))
             ;; honest negative — this relation materialized no pair.
             (swap! unmat conj
                    {:from from :to to :via via
                     :reason (cond
                               (empty? a-idx)
                               "no entity in the source container carried the join key value"
                               (empty? b-idx)
                               "no entity in the target container carried the join key value"
                               :else
                               "the two containers share no join key value")})))))
     {:relationship-drafts (vec (sort-by (juxt :source-uri :target-uri) @edges))
      :unmaterialized-relations @unmat
      :truncated-relations @truncated
      :pairs-considered @pairs-considered})))

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
        ;; GC-1 — CANONICAL URI minting (the keystone), BEFORE MC-6 cross-container
        ;; relating. Each container's AUTHOR minted its OWN free-form :uri, so two
        ;; containers can mint DIFFERENT URIs for the SAME real entity. The pure
        ;; `canonicalize-drafts` rewrites each container's concept-draft :uri (and its
        ;; intra-row edge endpoints) to a canonical URI derived deterministically from
        ;; (model-spec :entity-type + its :uri-keying-fields VALUES recovered from
        ;; :attributes) in ONE byte-identical format — so the SAME entity collapses
        ;; across containers when EB5 reconcile merges by URI, and MC-6 joins/edges
        ;; operate on canonical URIs. Per-container canonicalization is globally
        ;; consistent because the canonical URI is a pure fn of the entity's identity
        ;; values, not of any cross-container map. A draft whose :entity-type is
        ;; missing/unknown (or whose keying values can't be recovered) keeps its
        ;; original URI and is surfaced in :degraded — never fabricated (#4/#5).
        canon-per-container
        (mapv (fn [r]
                (let [c (canonicalize-drafts model-spec
                                             (:concept-drafts r)
                                             (:relationship-drafts r))]
                  (assoc r
                         :concept-drafts (:concept-drafts c)
                         :relationship-drafts (:relationship-drafts c)
                         :uri-degraded (:degraded c))))
              results)
        results canon-per-container
        canon-degraded (vec (mapcat :uri-degraded results))
        all-concepts (vec (mapcat :concept-drafts results))
        ;; the per-container INTRA-row edges (each transform only emits these) — now
        ;; with canonical endpoints (GC-1 rewrote them above).
        intra-rels (vec (mapcat :relationship-drafts results))
        ;; MC-6 — the CROSS-CONTAINER edges: join entities across containers by the
        ;; source's OWN relations (the shared :via key VALUE). Deterministic set
        ;; logic, NO :llm. nil relations-fn (csv single container) → no edges.
        ;; Operates on the CANONICALIZED results so cross-container edges point at
        ;; canonical URIs (no dangling against the canonical concept set).
        relations-fn (source-relations-fn source)
        cross (cross-container-relationship-drafts results relations-fn)
        cross-rels (:relationship-drafts cross)
        unmaterialized (:unmaterialized-relations cross)
        ;; GC-2 — the HONEST truncation report: any :via value whose genuine
        ;; distinct-entity fan-out exceeded the per-value cap, with its dropped count
        ;; (never a silent top-N — #4/#5), plus the bounded distinct-pair work done.
        truncated (:truncated-relations cross)
        pairs-considered (:pairs-considered cross)
        ;; union the intra-row edges with the cross-container edges.
        all-rels (vec (concat intra-rels cross-rels))]
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
      ;; MC-6 — the cross-container relating summary (HONEST: surfaces the edge
      ;; count derived from relations AND any relation that could not be
      ;; materialized — no fabricated edge, no silent drop).
      :cross-container-relations
      {:edge-count (count cross-rels)
       :unmaterialized-count (count unmaterialized)
       :unmaterialized unmaterialized
       ;; GC-2 — the bounded distinct-pair work + the HONEST truncation entries (a
       ;; high truncated-count is a signal that an entity-merge hint beats N×M weak
       ;; edges, never a silent drop — #4/#5).
       :pairs-considered pairs-considered
       :truncated-count (count truncated)
       :truncated truncated}
      ;; GC-1 — the canonical-URI-minting summary (HONEST: surfaces how many drafts
      ;; could NOT be canonicalized and kept their original URI, with the reason —
      ;; a high degrade count is a signal, never a silent fabrication).
      :canonicalization
      {:concepts-total (count all-concepts)
       :degraded-count (count canon-degraded)
       :degraded canon-degraded}
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
