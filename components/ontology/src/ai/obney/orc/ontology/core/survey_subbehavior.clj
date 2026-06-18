(ns ai.obney.orc.ontology.core.survey-subbehavior
  "EB2 — the SURVEY subbehavior as a delegatable ORC sheet.

   The FIRST real subbehavior on the EB1 registry/delegation pattern. A
   subbehavior is a first-class composed ORC sheet, built via the DSL +
   `build-workflow!`, registered under a stable name → deterministic sheet-id,
   and invoked from a central evolver tree via `:delegate` (child tick, isolated
   blackboard, mapped `:reads`/`:writes`).

   ## What Survey does (its ONE job)

   Explore a source BY SHAPE — a few specialist-tool calls (V06/V19: csv / sql /
   excel / text) — and emit the frozen PROFILE CONTRACT that characterizes what
   the source is ABOUT, PLUS the EMBED-WORTHY-FIELD signal that EB7 (Embed+Index
   / P2) consumes. It re-houses the proven DT2 Profile logic
   (`discovery-tree/profile-node-prompt` + the focused per-medium tool catalog),
   adding only the embed-field signal.

   ## The ONE subbehavior that warrants a repl-researcher (TERMINAL mode)

   Survey genuinely needs an iterative tool-using session (look at the shape,
   sample some rows, characterize) — so its body is a single `:repl-researcher`.
   But it is TERMINAL, not recursive: a few tool calls then `(final! …)`. The
   executor defaults a `:repl-researcher` to RECURSIVE
   (`recursive-mode? (not= false (get-in node [:rlm :recursive?]))`), so terminal
   mode is the EXPLICIT opt-out `:rlm {:recursive? false …}`. Terminal means the
   model's first `(final! …)` returns directly — NO `emit-tree!`, NO F3 Phase-2
   sub-tick. This is exactly the Survey shape: profile, don't build.

   ## C1 (EB1 carry-forward) — the profile crosses `:delegate` PARSED

   The profile contract is a MAP that crosses the `:delegate` seam back to the
   central tree. Two mechanisms keep it a PARSED MAP (and the EB2 live verify
   confirmed it arrives parsed, not as a JSON string):

     1. PRIMARY (the actual mechanism for a repl-researcher body): a TERMINAL
        repl-researcher's `(final! {:profile <map>})` captures a real Clojure map
        in the SCI sandbox, the `complete-node-execution` write persists it
        verbatim, and `execute-delegate-node` maps the child's `:outputs` to the
        parent blackboard VERBATIM (no stringify). The load-bearing enabler is
        therefore the PROMPT: it forbids `emit-tree!` and requires real Clojure
        (EDN) data, NOT a JSON string — because routing the profile through an
        emitted tree's `:llm` leaf is what turns it into JSON text (the failure
        mode the EB2 prototype hit and the prompt fixed).
     2. DEFENSE-IN-DEPTH: the `:profile` write declares a STRUCTURED Malli
        `[:map …]` schema (`profile-contract-schema`), NEVER a bare `:map`. EB1's
        `/inspect-orc` proved a bare `:map` write arrives across `:delegate` as a
        JSON STRING when the value flows through the AI/`:llm` executor coercion
        path (which coerces only STRUCTURED `[:map …]`/`[:map-of …]` schemas). The
        structured schema makes the contract robust if any sub-step ever routes
        the profile through that path, and documents the contract shape.

   ## Re-orchestration, not rewrite (discipline 8) + domain-agnostic (12)

   The per-medium tool catalog + the profiling instruction are re-housed from
   DT2 verbatim through the discovery-tree promotion seam — no fork. The
   profiling INSTRUCTION is medium-agnostic; the only specialization is naming
   which tools to call for csv vs sql vs excel. No vertical/domain knowledge."
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]
            [clojure.string :as str]))

;; =============================================================================
;; The profile contract (re-housed from DT2) + the EB2 embed-field addition
;; =============================================================================

(def embed-field-signal-key
  "The EB2 addition to the DT2 profile contract: the embed-worthy-field signal
   EB7 (Embed+Index / P2) consumes. It names the field(s) whose VALUES carry
   free-text / natural-language meaning worth embedding for late-interaction
   retrieval (a title / name / description / label column) — as opposed to pure
   codes/ids/numbers (which identify but don't carry embeddable semantics).
   Survey produces it at profile time because it already has the source's shape +
   a real sample in hand; producing it here avoids EB7 re-surveying the source."
  :embed-worthy-fields)

(def profile-contract-keys
  "The EB2 Survey profile contract: the DT2-frozen profile keys PLUS the EB2
   embed-worthy-field signal. The frozen DT2 keys are re-used verbatim (no drift)
   from `discovery-tree/profile-contract-keys`."
  (conj (vec dt/profile-contract-keys) embed-field-signal-key))

(def profile-contract-schema
  "C1 — the STRUCTURED Malli `[:map …]` schema for the Survey profile contract.
   This is the load-bearing C1 fix: declared as the `:profile` write's blackboard
   schema (on BOTH the Survey sheet and the central tree), it makes the contract
   cross the `:delegate` seam as a PARSED MAP rather than a JSON string.

   Every entry is `{:optional true}` + a permissive value schema: the DT2 live
   verify documented that profile field VALUES are model-variable (a field may
   come back as a vector of strings, a vector of maps, or a prose string). The
   contract freezes the KEY SET + the structured-map SHAPE (what C1 needs), NOT
   the per-field value-shape — a consumer reads each field tolerantly (the DT3/DT5
   carry-forward). `{:closed false}` tolerates extra keys the model may add."
  [:map {:closed false}
   [:entity-candidates {:optional true} :any]
   [:identifying-keys {:optional true} :any]
   [:scope-fields {:optional true} :any]
   [:linking-keys {:optional true} :any]
   [:grain-signals {:optional true} :any]
   [:sample {:optional true} :any]
   [embed-field-signal-key {:optional true} :any]])

;; =============================================================================
;; The Survey prompt — re-housed DT2 Profile body + the embed-field instruction
;; =============================================================================

(defn- embed-field-block
  "The ONLY EB2 addition to the DT2 profile prompt: instruct the node to also
   surface the embed-worthy-field signal. Domain-agnostic — it asks WHICH fields
   carry free-text meaning worth embedding (by their value SHAPE: prose/name/
   title/description vs code/id/number), naming no domain field."
  []
  (str "\n\n  - EMBED-WORTHY FIELDS: which field(s) hold FREE-TEXT / "
       "natural-language VALUES whose MEANING is worth embedding for semantic "
       "retrieval later — a name, title, label, or description column whose value "
       "is human-readable prose, NOT a pure code / id / number (those identify but "
       "carry no embeddable semantics). Judge by the VALUE you sampled, not the "
       "column name. List the field name(s); empty if the source is all "
       "codes/ids/numbers with no free-text field. This signal is consumed by a "
       "later embed+index step."))

(def ^:private runtime-goal-sentinel
  "The DT2 Profile body interpolates the goal inline. The Survey subbehavior gets
   its goal at RUNTIME as a `:delegate` :reads input (so one per-source sheet
   serves any goal — the goal is not part of sheet identity). We pass this
   sentinel where the DT2 body expects the goal text, then prepend an explicit
   instruction to read the real goal from `(get-input :goal)`. This keeps the DT2
   body re-housed verbatim (no fork) while sourcing the goal from the blackboard."
  "the goal provided to you at runtime as the input key :goal")

(defn survey-prompt
  "The Survey node prompt: the DT2 Profile body (focused, single-purpose, medium-
   agnostic instruction + the per-medium tool catalog) re-housed through the
   discovery-tree promotion seam, PLUS the EB2 embed-worthy-field instruction and
   an explicit instruction to emit ALL the contract keys (incl. the embed signal)
   with `(final! …)`.

   The goal is read at RUNTIME from `(get-input :goal)` (a `:delegate` :reads
   input) so a single per-source sheet serves any goal; `fmt` selects the
   per-medium tool catalog (csv/sql/excel/text). Domain-agnostic (12): no
   vertical knowledge — characterizes ANY source."
  [fmt]
  (str
   "Your GOAL and a SOURCE DESCRIPTOR are provided as runtime inputs. Read the "
   "GOAL with (get-input :goal) and the source descriptor with "
   "(get-input :source-descriptor) before you begin — the GOAL orients what to "
   "look for; the descriptor tells you the source's format + path (explore it via "
   "the granted source-access tools named below).\n\n"
   ;; HARD anti-emit-tree directive — this is a DIRECT tool-use session, not a
   ;; tree-design session. The framework prompt biases toward emit-tree!; the
   ;; Survey job is a few tool calls then finalize. Emitting a tree routes the
   ;; profile through an :llm leaf that returns JSON TEXT, which then crosses the
   ;; :delegate seam as a STRING instead of a parsed map (the C1 failure). So:
   "*** HOW TO RUN THIS NODE (read carefully) ***\n"
   "This is a DIRECT tool-use session, NOT a tree-design session. DO NOT call "
   "(emit-tree! …). DO NOT design a behavior tree. Ignore any general guidance "
   "above about emitting trees as your main loop — for THIS task you call the "
   "source-access tools DIRECTLY (as bare top-level expressions), look at what "
   "they return, then assemble your answer and call (final! …). A couple of tool "
   "calls then (final! …) — that is the whole loop.\n\n"
   "*** EMIT REAL CLOJURE DATA, NOT JSON TEXT ***\n"
   "Build the profile as a real Clojure MAP whose values are real Clojure data "
   "(vectors, maps, strings) and pass it DIRECTLY to (final! {:profile {…}}). Do "
   "NOT serialize it to a JSON string; do NOT wrap it in quotes; do NOT return a "
   "string that looks like JSON. The downstream consumer reads it as a parsed "
   "Clojure map — a JSON/prose STRING is unusable and will be rejected.\n\n"
   ;; Re-house the DT2 focused Profile body verbatim through the promotion seam
   ;; (discipline 8 — no fork). It already carries: the ONE-job framing, the
   ;; per-medium tool catalog, the "sample by shape, do NOT emit-tree, do NOT
   ;; page the whole source, do NOT mint" guidance, the six frozen contract
   ;; fields, and the (final! …) contract block for those six keys. The goal
   ;; slot gets the runtime-read sentinel (the real goal is read above).
   (dt/assemble-node-prompt :profile {:goal runtime-goal-sentinel :fmt fmt})
   ;; The EB2 addition: also surface the embed-worthy-field signal.
   "\n\nADDITIONALLY characterize:"
   (embed-field-block)
   ;; OVERRIDE the DT2 contract-block's per-key (final!) shape: THIS node's sole
   ;; declared write is :profile, so the WHOLE contract is ONE map nested under
   ;; :profile. (The DT2 body above lists the per-key contract for the SHAPE of
   ;; the fields; here we say how to package them for final!.)
   "\n\n*** FINAL OUTPUT — the WHOLE contract goes under ONE key, :profile ***\n"
   "Your node's only declared write is :profile. Call (final! {…}) with EXACTLY "
   "one key, :profile, whose value is a MAP containing ALL of these fields (a "
   "real Clojure map, NOT a JSON string):\n"
   "  (final! {:profile {" (str/join " " (map (fn [k] (str k " …")) profile-contract-keys)) "}})\n"
   "where:\n"
   "  :entity-candidates — vector of candidate entity-type descriptions\n"
   "  :identifying-keys  — map of entity-candidate -> the field(s) that identify it\n"
   "  :scope-fields      — vector of field names that COULD scope the source\n"
   "  :linking-keys      — vector of code/key field names that likely link to other sources\n"
   "  :grain-signals     — vector of notes on where rows are finer-grained than entities\n"
   "  :sample            — vector of a few representative row maps read from the source\n"
   "  " (name embed-field-signal-key)
   " — vector of field name(s) whose VALUES are free-text worth embedding (empty if none)\n"
   "Do NOT put these keys at the top level of (final! …); they all live INSIDE "
   "the :profile map."))

;; =============================================================================
;; The delegatable Survey sheet — built on the EB1 registry pattern
;; =============================================================================

(defn source-descriptor-string
  "The blackboard descriptor string handed to the Survey node for a STRUCTURED
   source — the same shape `rlm-discovery/sources->blackboard` uses: the content
   is NOT inline, the node explores via the granted per-medium tools. For a
   `:text` source the content rides inline (handed by the caller as the value)."
  [{:keys [type path]}]
  (str "RAW STRUCTURED SOURCE — format " (name type) ", path " path
       ". This source is NOT provided inline; explore it via the granted "
       "source-access tools (sample, never dump)."))

(defn survey-subbehavior-name
  "Canonical registry name for the Survey subbehavior, specialized per medium so
   each format's sheet (and its baked-in `:granted-source` path) is its own
   deterministic, separately-evolvable sheet. `\"<family>/<behavior>@v<N>\"` with
   the medium + a stable path tag folded in so distinct sources never collide on
   one sheet-id (the path is part of the node's baked `:granted-source`, so it is
   part of identity)."
  [{:keys [type path]}]
  (str "ontology-survey/survey@v1-" (name type) "-"
       (format "%08x" (bit-and (hash path) 0xffffffff))))

(defn survey-sheet-id-for
  "Look up the deterministic sheet-id for a Survey subbehavior by its source
   descriptor (pure — no event-store read). The central tree points its
   `:delegate` `:target-sheet-id` here without rebuilding the subbehavior."
  [source]
  (dsl/sheet-id-for-name (survey-subbehavior-name source)))

(defn survey-subbehavior-def
  "The Survey subbehavior workflow definition for ONE structured source.

   Body: a single `:repl-researcher` in TERMINAL mode
   (`:rlm {:recursive? false :granted-source {:format … :path …}}`) — it explores
   the source via the medium's granted specialist tools, then `(final! …)` the
   profile contract. The `:granted-source` is baked in at build time (it carries
   the source path), which is why the registry name is per-source.

   Contract (the public `:reads`/`:writes`):
     :reads  [:goal :source-descriptor]
     :writes [:profile]   ← STRUCTURED `[:map …]` schema (C1)"
  [{:keys [source model]}]
  (let [{:keys [type path]} source
        nm (survey-subbehavior-name source)]
    (dsl/workflow nm
      (dsl/blackboard {:goal :string
                       :source-descriptor :string
                       ;; C1 — STRUCTURED schema for the map contract that
                       ;; crosses :delegate; NEVER a bare :map.
                       :profile profile-contract-schema})
      (dsl/sequence "survey-root"
        (dsl/repl-researcher "survey"
          :model (or model "google/gemini-3-flash-preview")
          ;; The goal is read at runtime off the blackboard by the node
          ;; ((get-input :goal)); the prompt body is the static focused profile
          ;; body + the per-medium tool catalog for this format.
          :instruction (survey-prompt type)
          :reads [:goal :source-descriptor]
          :writes [:profile]
          :max-iterations 8
          ;; TERMINAL mode (explicit opt-out of the recursive default) + V06
          ;; granted source-access tools for this medium.
          :rlm {:recursive? false
                :granted-source {:format type :path path}})))))

(defn register-survey-subbehavior!
  "REGISTER (build, idempotent) the Survey subbehavior sheet for a source and
   return its deterministic sheet-id. Re-registering an unchanged def is a no-op
   (same id). The central evolver tree resolves the name → id via
   `survey-sheet-id-for` and `:delegate`s to it."
  [ctx {:keys [source model]}]
  (dsl/build-workflow! ctx (survey-subbehavior-def {:source source :model model})))
