(ns ai.obney.orc.ontology.core.resilience
  "EB9 — a REUSABLE resilience sub-tree builder composed INTO the EB2-EB8
   subbehavior sheets so a subbehavior SELF-CORRECTS or FAILS CLEANLY WITH A
   DIAGNOSIS before poisoning downstream or returning.

   ## The three reused DSL primitives (via `orc-service.interface`, boundary-
   ## correct — NOT `core.dsl`)

     - `:fallback`  — runs children IN ORDER, succeeds on the FIRST success
       (primary/cheap path → robust path). The proven HAND-COMPOSED fallback that
       works in top-level sheets today (NOT the deferred RLM-emitted Phase-2
       fallback). (dsl `fallback`.)
     - `:condition` (`{:check {:key … :op … :value …} :on-fail :failure}`) /
       `:llm-condition` (`{:model … :instruction \"<yes/no>\" :reads […]}`) — a
       SANITY GATE on the node's intermediate state (is the output sane / non-empty
       / scoped?). A `:condition` passes → `:success`, fails → `:on-fail`
       (`:failure`); it gates the FLOW, it does not write to the blackboard.
     - a troubleshoot `:llm` node — composes Investigation (root-cause) + Validation
       (check): on a bad intermediate state it reasons about WHY and emits a
       STRUCTURED DIAGNOSIS. `:reasoning` is written FIRST (#13). It REUSES the
       Investigation/Validation corpus PATTERN (hypothesis → evidence → root-cause +
       recommended-fix; the failure structured as `{:rule :reason …}` entries) — no
       fork of those behaviors.

   ## The resilient sub-tree SHAPE (the load-bearing wiring)

   The builder wraps a failure-prone step as:

       (fallback \"<step>-resilient\"
         ;; RECOVER branch — primary→robust, each gated by the SANITY gate.
         (sequence \"<step>-recover\"
           (fallback \"<step>-primary-or-robust\"
             (sequence \"<step>-primary\" <primary> <sanity-gate>)
             (sequence \"<step>-robust\"  <robust>  <sanity-gate>)))
         ;; DIAGNOSE branch — runs ONLY when RECOVER failed (both paths failed the
         ;; gate). Troubleshoot lands a STRUCTURED :diagnosis, then an ALWAYS-FAIL
         ;; condition forces the whole step to a CLEAN :failure.
         (sequence \"<step>-diagnose\"
           <troubleshoot>
           (condition \"<step>-fail-with-diagnosis\"
             :check {:key ::never :op :exists} :on-fail :failure)))

   ## Why this NEVER returns a fake success (#4 no-false-green / #5 no band-aid)

   The DIAGNOSE branch is the outer fallback's LAST child. If the troubleshoot
   `:llm` node were the last child on its own, its SUCCESS would make the outer
   fallback SUCCEED — masking the failure as a fake green (forbidden). So the
   troubleshoot is wrapped in a `:sequence` with an ALWAYS-FAIL `:condition`
   (`{:key ::never :op :exists}` — `::never` is never on the blackboard, so
   `:exists` → false → `:on-fail :failure`). The troubleshoot still RUNS and lands
   its `:diagnosis` on the blackboard (a node's `:writes` persist on completion
   regardless of a LATER sibling's status), but the step's OVERALL status is
   `:failure`. So the only two outcomes are:

     (a) RECOVER succeeds  → `:success`, downstream sees a GOOD result (self-correct
         via the primary OR the robust path);
     (b) RECOVER fails     → the troubleshoot lands a structured `:diagnosis` AND the
         step returns a CLEAN `:failure` carrying that diagnosis — downstream is NOT
         poisoned with an empty/degenerate fake success.

   There is NO third outcome where a bad intermediate state is laundered into a
   success.

   ## Domain-agnostic (#12) + no hardcoded phrase matching (#7/#12)

   The builder bakes in NO vertical knowledge: the sanity-check predicate is a
   STRUCTURAL `:condition` (a count / existence / threshold on a declared key) OR a
   `:llm-condition` (a semantic yes/no the model judges) — NEVER a hardcoded
   failure-phrase list. The troubleshoot prompt reasons over whatever intermediate
   state it is given. The same builder hardens a CSV-extract step and an
   axiom-emit step identically.

   ## Composability (#8 re-orchestration, not rewrite)

   The builder takes the step's EXISTING primary node (the EB2-EB8 node verbatim) +
   a robust alternative + a sanity-check spec + an OPTIONAL troubleshoot node, and
   returns a drop-in resilient sub-tree node that slots into the subbehavior's
   `:sequence` body in place of the bare node. The subbehaviors' `:reads`/`:writes`
   contracts are unchanged — resilience is INTERNAL to a step."
  (:require [ai.obney.orc.orc-service.interface :as dsl]
            [clojure.string :as str]))

;; =============================================================================
;; The ALWAYS-FAIL sentinel — forces the DIAGNOSE branch to a clean :failure
;; =============================================================================

(def never-key
  "A blackboard key that is NEVER written by any subbehavior. An `:exists` check
   on it ALWAYS fails → the DIAGNOSE branch's terminal `:condition` forces the
   whole resilient step to a clean `:failure` (so a troubleshoot path can NEVER
   masquerade as a success — #4/#5). Namespaced so it cannot collide with a real
   contract key."
  :ai.obney.orc.ontology.core.resilience/never-written)

;; =============================================================================
;; The troubleshoot `:llm` node — Investigation (root-cause) + Validation (check)
;; =============================================================================

(def diagnosis-key
  "The troubleshoot node's STRUCTURED-diagnosis write. A consumer (the
   subbehavior's caller / a HITL surface) reads this off the blackboard after a
   clean failure to learn WHY the step failed + what to do next. It carries the
   Investigation root-cause + the Validation check verdict + a recommended fix."
  :diagnosis)

(def diagnosis-schema
  "C1 — a STRUCTURED Malli `[:map …]` schema for the `:diagnosis` write (the `:llm`
   troubleshoot node-type case — a bare `:map` would arrive across `:delegate` as a
   JSON string; a structured map is flattened + reassembled into a parsed map).
   Mirrors the Investigation corpus output shape (`:root-cause` +
   `:recommended-fix`) PLUS the Validation check verdict + the per-issue
   `{:rule :reason}` entries the corpus prescribes. `{:closed false}` + `:any`
   leaves tolerate the model-variable per-field shape."
  [:map {:closed false}
   [:symptom {:optional true} :any]
   [:root-cause {:optional true} :any]
   [:ruled-out {:optional true} :any]
   [:check-failed {:optional true} :any]
   [:issues {:optional true} :any]
   [:recommended-fix {:optional true} :any]
   [:recoverable? {:optional true} :any]])

(defn troubleshoot-prompt
  "The troubleshoot node prompt: composes Investigation (root-cause) + Validation
   (check) over a bad intermediate state. REUSES the Investigation corpus PATTERN
   (enumerate candidate causes from the symptom → rule out alternatives with
   evidence → converge on a root-cause + recommended-fix) and the Validation corpus
   PATTERN (structure the failure as `{:rule :reason}` entries) — NO fork of those
   behaviors.

   `step-label` orients the model to which step failed (e.g. \"the per-row
   extraction transform\"); `expectation` states what a SANE intermediate state
   would look like (e.g. \"a non-empty, scoped set of concept drafts\"). Both are
   domain-AGNOSTIC framing — the model reasons over the ACTUAL state it is given
   (its `:reads`), naming no vertical field. `:reasoning` is written FIRST (#13)."
  [step-label expectation]
  (str
   "*** HOW THIS NODE WORKS (read carefully) ***\n"
   "You are a TROUBLESHOOT step. The prior step (" step-label ") produced a BAD "
   "intermediate state — a sanity gate REJECTED its output. You are GIVEN that "
   "state as context (your `:reads`). You do NOT call any tools and you do NOT "
   "emit a behavior tree — you INVESTIGATE why the state is bad and emit a "
   "STRUCTURED DIAGNOSIS. Ignore any general guidance about tool sessions, "
   "`get-input`, `final!`, or `emit-tree!`.\n\n"
   "A SANE intermediate state would be: " expectation ".\n\n"
   "*** INVESTIGATE (root-cause) THEN VALIDATE (check) ***\n"
   "  1. ENUMERATE the candidate causes the symptom could have (do not converge on "
   "the first plausible one).\n"
   "  2. RULE OUT alternatives using EVIDENCE from the state you are given (cite the "
   "actual values).\n"
   "  3. CONVERGE on the single ROOT CAUSE + a concrete RECOMMENDED FIX.\n"
   "  4. Decide whether the failure is RECOVERABLE (a different / "
   "more-robust approach to the SAME step would succeed) or NOT (the inputs "
   "themselves cannot support a sane result).\n\n"
   "*** YOUR OUTPUT — produce these fields, REASONING FIRST (#13) ***\n"
   "  1. `reasoning` — FIRST, before anything else: your chain-of-thought across "
   "the four steps above (enumerate → rule out → converge → recoverable?). Think "
   "BEFORE the structured diagnosis.\n"
   "  2. `diagnosis` — a MAP with: `:symptom` (what the gate rejected, in one "
   "line), `:root-cause` (the single converged cause), `:ruled-out` (a vector of "
   "candidate causes you ruled out, each with the evidence that ruled it out), "
   "`:check-failed` (which sanity expectation was violated), `:issues` (a vector of "
   "`{:rule … :reason …}` entries — the Validation-style structured failure), "
   "`:recommended-fix` (the concrete next step), and `:recoverable?` (boolean — "
   "could a more-robust approach to THIS step succeed). Emit real structured "
   "Clojure data, NOT a JSON string."))

(defn troubleshoot-node
  "Build the troubleshoot `:llm` node (Investigation + Validation). It READS the
   intermediate-state keys it should diagnose over (the step's output keys + any
   inputs) and WRITES `:reasoning` FIRST (#13) then the structured `:diagnosis`.

   Options:
     :name          — node name (required, stable identity)
     :model         — OpenRouter model id (default gemini-3-flash-preview)
     :reads         — blackboard keys carrying the bad intermediate state + inputs
     :reasoning-key — the #13 reasoning write key (default :reasoning; pass a
                      NODE-SCOPED key in concurrent contexts so siblings don't
                      trample, #13)
     :step-label / :expectation — domain-agnostic framing for the prompt."
  [{:keys [name model reads reasoning-key step-label expectation]
    :or {model "google/gemini-3-flash-preview"
         reasoning-key :reasoning
         step-label "the prior step"
         expectation "a sane, non-empty, in-scope result"}}]
  (dsl/llm name
    :model model
    :instruction (troubleshoot-prompt step-label expectation)
    :reads (vec reads)
    ;; #13 — reasoning FIRST, then the structured diagnosis.
    :writes [reasoning-key diagnosis-key]))

;; =============================================================================
;; The sanity GATE — a deterministic `:condition` or a semantic `:llm-condition`
;; =============================================================================

(defn sanity-gate
  "Build a SANITY-GATE node that gates a step's intermediate state. Two flavors:

     - DETERMINISTIC `:condition` — pass a `:check` map
       (`{:key … :op … :value …}`). Cheap, hermetic, no LLM. `:on-fail` defaults to
       `:failure` so a bad state aborts the path (and the fallback tries the next).
     - SEMANTIC `:llm-condition` — pass `:llm-check {:instruction \"<yes/no>\"
       :reads […] :model …}`. The model judges the state with a yes/no question
       (true → pass/`:success`, false → `:failure`). Use ONLY when the sanity check
       needs judgment (#7 — never a hardcoded phrase list).

   Exactly one of `:check` / `:llm-check` must be supplied."
  [{:keys [name check llm-check]}]
  (cond
    check
    (dsl/condition name :check check :on-fail :failure)

    llm-check
    (dsl/llm-condition name
      :model (or (:model llm-check) "google/gemini-3-flash-preview")
      :instruction (:instruction llm-check)
      :reads (vec (:reads llm-check)))

    :else
    (throw (ex-info "sanity-gate requires exactly one of :check / :llm-check"
                    {:name name}))))

;; =============================================================================
;; The resilient-step builder — the reusable resilience sub-tree
;; =============================================================================

(defn with-resilience
  "Wrap a failure-prone step as a RESILIENT sub-tree:
   `:fallback`[primary→robust] guarded by a sanity `:condition`/`:llm-condition`,
   with a troubleshoot `:llm` node that lands a structured `:diagnosis` and a clean
   `:failure` on UNRECOVERABLE failure (NEVER a fake success — #4/#5).

   Required options:
     :step          — a stable label for the step (e.g. \"extract-apply\"); used to
                      name the composite nodes.
     :primary       — the EXISTING subbehavior node (the EB2-EB8 node, verbatim —
                      #8 re-orchestration not rewrite).
     :robust        — a more-robust ALTERNATIVE node tried when the primary's output
                      fails the gate (its `:writes` should overlap the primary's so
                      the gate re-checks the SAME key, and downstream reads the same
                      contract key).
     :gate          — a `sanity-gate` SPEC map (`{:check …}` or `{:llm-check …}`)
                      WITHOUT a `:name` — the builder names the per-path gate nodes.

   Optional:
     :troubleshoot  — a `troubleshoot-node` SPEC map (`{:reads … :step-label …
                      :expectation … :model … :reasoning-key …}`) WITHOUT a `:name`.
                      When omitted, the builder synthesizes one reading the primary's
                      `:reads` + `:writes` (so it diagnoses over the inputs + the bad
                      output). Pass an explicit spec to tune the framing / model.

   Returns ONE composite `:fallback` node ready to drop into a subbehavior's
   `:sequence` body in place of the bare `:primary`."
  [{:keys [step primary robust gate troubleshoot]}]
  (when-not (and step primary robust gate)
    (throw (ex-info "with-resilience requires :step, :primary, :robust, :gate"
                    {:have (keys {:step step :primary primary :robust robust :gate gate})})))
  (let [;; the two per-path sanity gates (distinct names, same spec).
        primary-gate (sanity-gate (assoc gate :name (str step "-primary-gate")))
        robust-gate  (sanity-gate (assoc gate :name (str step "-robust-gate")))
        ;; default troubleshoot reads = primary inputs + primary outputs (so it
        ;; sees both the inputs and the bad output it must diagnose over).
        default-ts-reads (vec (distinct (concat (:reads primary) (:writes primary))))
        ts-spec (merge {:name (str step "-troubleshoot")
                        :reads default-ts-reads}
                       troubleshoot
                       ;; the builder always owns the node NAME (stable identity).
                       {:name (str step "-troubleshoot")})
        troubleshoot* (troubleshoot-node ts-spec)
        ;; the ALWAYS-FAIL condition that forces the DIAGNOSE branch to :failure
        ;; AFTER the troubleshoot has landed its :diagnosis (so the step fails
        ;; cleanly WITH a diagnosis — never a fake success).
        fail-with-diagnosis
        (dsl/condition (str step "-fail-with-diagnosis")
          :check {:key never-key :op :exists}
          :on-fail :failure)]
    (dsl/fallback (str step "-resilient")
      ;; RECOVER branch: primary→robust, each gated by the SANITY gate. The
      ;; per-path SEQUENCE wrappers carry distinct names (NOT colliding with the
      ;; caller-supplied primary/robust node names — node identity is by name).
      (dsl/sequence (str step "-recover")
        (dsl/fallback (str step "-primary-or-robust")
          (dsl/sequence (str step "-primary-path") primary primary-gate)
          (dsl/sequence (str step "-robust-path")  robust  robust-gate)))
      ;; DIAGNOSE branch: troubleshoot (lands :diagnosis) → ALWAYS-FAIL (clean
      ;; :failure carrying the diagnosis, no poison — #4/#5).
      (dsl/sequence (str step "-diagnose")
        troubleshoot*
        fail-with-diagnosis))))

;; =============================================================================
;; Blackboard-schema additions — a subbehavior composing resilience must declare
;; the resilience-internal keys (the troubleshoot's #13 reasoning + the diagnosis)
;; =============================================================================

(defn resilience-blackboard-keys
  "The blackboard keys a subbehavior must add when it composes `with-resilience`:
   the troubleshoot node's `:diagnosis` (structured) + its reasoning key. Merge
   this into the subbehavior's `(dsl/blackboard {…})` so the resilience-internal
   writes have a declared schema. `reasoning-key` defaults to `:reasoning` (already
   declared by most subbehaviors); pass a node-scoped key when the step runs in a
   concurrent context (#13)."
  ([] (resilience-blackboard-keys :reasoning))
  ([reasoning-key]
   {reasoning-key :string
    diagnosis-key diagnosis-schema
    ;; the ALWAYS-FAIL sentinel the DIAGNOSE branch's terminal `:condition`
    ;; checks `:exists` on — it is NEVER written, so the check ALWAYS fails →
    ;; the step fails cleanly WITH a diagnosis. It must be DECLARED on the
    ;; blackboard (the build-time `:set-node-check` command validates that a
    ;; condition's `:check :key` is a declared key), even though nothing writes
    ;; it. `:maybe :boolean` documents 'unset by design'.
    never-key [:maybe :boolean]}))
