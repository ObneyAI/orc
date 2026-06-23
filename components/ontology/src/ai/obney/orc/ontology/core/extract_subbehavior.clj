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

(defn extract-subbehavior-def
  "The Extract subbehavior workflow definition.

   Body: a `:code` → `:llm` → `:code` sequence:
     1. `:code` sample-rows-code   — REUSE `mechanical-sample-rows` (DT4-grounding)
     2. `:llm`  author             — re-house DT4 `transform-node-prompt` (#13)
     3. `:code` apply-transform    — REUSE V20 `apply-extraction-transform!`

   Contract (the public `:reads`/`:writes`):
     :reads  [:model-spec :source]
     :writes [:concept-drafts :relationship-drafts :extraction-report]
   The internal inter-node keys (`:sample-rows`, `:reasoning`, `:transform-source`,
   `:selector`) live on the sheet blackboard between nodes.

   `key-shape` (optional) lets a caller render the DT4-grounding key-shape block
   STATICALLY into the prompt at build time. Normally it is nil here — the prompt
   instructs the `:llm` node to ground field access in the `sample-rows` input it
   is GIVEN at runtime (Node 1's real sample), which is what makes ONE sheet serve
   any source. (The static path exists for parity with DT4's seam; the runtime
   `sample-rows` input is the load-bearing grounding.)

   `resilient?` (EB9, optional) wraps the failure-prone AUTHOR→APPLY sub-pipeline
   in a `with-resilience` sub-tree: the PRIMARY (author → apply) is gated on a
   non-empty draft set (the 0-concept false-empty the DT4 mis-ground produces); on
   a gate failure a ROBUST author (extra grounding emphasis) re-attempts; if BOTH
   produce an empty set, a troubleshoot `:llm` node lands a structured `:diagnosis`
   and the subbehavior returns a CLEAN `:failure` (never a poisoned empty success —
   #4/#5). The public `:reads`/`:writes` contract is UNCHANGED."
  [{:keys [model key-shape resilient?]}]
  (let [nm (extract-subbehavior-name)
        mdl (or model "google/gemini-3-flash-preview")
        ;; the AUTHOR → APPLY sub-pipeline (the failure-prone unit — the
        ;; concept-count is only known AFTER apply). `author-prompt` selects the
        ;; primary or the robust (extra-grounding) author body.
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
              :fn "ai.obney.orc.ontology.core.extract-subbehavior/apply-transform-code"
              :reads [:source :transform-source :selector]
              ;; EB9 — declare the FLAT :concept-count so the sanity :condition
              ;; can gate the intermediate state.
              :writes [:concept-drafts :relationship-drafts
                       :extraction-report :concept-count])))
        ;; the RESILIENT AUTHOR→APPLY unit (only when :resilient?). When not
        ;; resilient the original flat "author"/"apply-transform" nodes are used
        ;; below (unchanged — EB4 contract preserved).
        resilient-author-apply
        (res/with-resilience
          {:step "extract"
           :primary (author-apply "primary" (transform-author-prompt key-shape))
           :robust  (author-apply "robust"  (robust-author-prompt key-shape))
           ;; deterministic sanity gate — a non-empty draft set (the 0-concept
           ;; false-empty is the DT4 mis-ground failure mode). NO hardcoded
           ;; phrase matching — a structural threshold on the declared key.
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
        {;; public :reads — the EB3 model-spec + the source descriptor
         :model-spec [:map {:closed false}]
         :source [:map {:closed false}]
         ;; internal inter-node keys
         :sample-rows [:vector [:map {:closed false}]]
         :reasoning :string
         :transform-source :string
         :selector [:maybe :string]
         ;; EB9 — the flat intermediate-state count the sanity gate checks.
         :concept-count :int
         ;; public :writes — the draft set + the coverage report
         :concept-drafts concept-drafts-schema
         :relationship-drafts relationship-drafts-schema
         :extraction-report extraction-report-schema}
        ;; EB9 — the resilience-internal keys (the troubleshoot's #13 reasoning +
        ;; the structured :diagnosis + the always-fail sentinel) when resilient.
        (when resilient? (res/resilience-blackboard-keys))))
      (if resilient?
        (dsl/sequence "extract-root"
          ;; Node 1 — SAMPLE real rows + key-shape (REUSE mechanical-sample-rows).
          (dsl/code "sample-rows"
            :fn "ai.obney.orc.ontology.core.extract-subbehavior/sample-rows-code"
            :reads [:source]
            :writes [:sample-rows])
          ;; Node 2/3 — the RESILIENT AUTHOR → APPLY :fallback (EB9).
          resilient-author-apply)
        ;; the ORIGINAL flat three-node body (EB4 — unchanged contract + names).
        (dsl/sequence "extract-root"
          (dsl/code "sample-rows"
            :fn "ai.obney.orc.ontology.core.extract-subbehavior/sample-rows-code"
            :reads [:source]
            :writes [:sample-rows])
          (dsl/llm "author"
            :model mdl
            :instruction (transform-author-prompt key-shape)
            :reads [:model-spec :sample-rows]
            :writes [:reasoning :transform-source :selector])
          (dsl/code "apply-transform"
            :fn "ai.obney.orc.ontology.core.extract-subbehavior/apply-transform-code"
            :reads [:source :transform-source :selector]
            :writes [:concept-drafts :relationship-drafts :extraction-report]))))))

(defn register-extract-subbehavior!
  "REGISTER (build, idempotent) the Extract subbehavior sheet and return its
   deterministic sheet-id. Re-registering an unchanged def is a no-op (same id).
   The central evolver tree resolves the name → id via `extract-sheet-id-for` and
   `:delegate`s to it."
  [ctx {:keys [model key-shape resilient?]}]
  (dsl/build-workflow! ctx (extract-subbehavior-def
                            {:model model :key-shape key-shape :resilient? resilient?})))
