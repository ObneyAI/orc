(ns ai.obney.orc.ontology.core.validate-cq-subbehavior
  "EB8 — the VALIDATE+CQ subbehavior as a delegatable ORC sheet.

   The SIXTH real subbehavior on the EB1 registry/delegation pattern (after EB2
   Survey + EB3 Model + EB4 Extract + EB5 Reconcile + EB6 Axiom/TBox + EB7
   Embed+Index). A subbehavior is a first-class composed ORC sheet, built via the
   DSL + `build-workflow!`, registered under a stable name → deterministic
   sheet-id, and invoked from a central evolver tree via `:delegate` (child tick,
   isolated blackboard, mapped `:reads`/`:writes`).

   ## What Validate+CQ does (its ONE job)

   It is the graph-level REQUIREMENTS + EXIT-GATE step, run AFTER every source is
   profiled (and, for the gate, after the graph is built):

     1. DERIVE the competency questions (CQs) from the GOAL ⨯ the source
        PROFILE(s) — GROUNDED (answerable-in-principle from what the profiles show
        the sources actually contain) AND GOAL-ANCHORED (testing what the graph
        SHOULD answer per the goal, not merely what was extracted). Re-houses DT5's
        `cq-node-prompt` (no fork), EXTENDED with the `:llm`-node I/O framing + the
        #13 reasoning-first output.
     2. PERSIST the CQs as the S14 ORSD spec — the SAME spec build!'s S15
        exit-criterion reads. Reuses `record-competency-questions!` (the
        `:ontology/record-ontology-spec` command path — no bare append, Grain
        discipline). Read back via `get-ontology-spec` (discipline 7).
     3. GATE — run the S15 CQ runner (`evaluate-cqs!`) over the persisted spec:
        per-CQ three-layer retrieve-then-judge (SEMANTIC validation, NOT structural
        lints / hardcoded phrase matching) → `:pass`/`:fail`/`:unknown` per CQ +
        the graph-health metric. Read back via `get-cq-evaluation-latest`
        (discipline 7). An `:unknown` is a FIRST-CLASS honest verdict (the graph
        lacks that fact-kind), NEVER silently dropped.
     4. HITL — the derived CQs + per-CQ rationale are first-class OUTPUTS the
        orchestrator surfaces for human review/override.

   CONSUMER OVERRIDE: a consumer-supplied CQ set (`:consumer-cqs`) OVERRIDES the
   derived set — derivation output is discarded and the supplied questions are
   persisted as the ORSD spec (the HITL override path).

   ## A THREE-node sheet: :llm DERIVE → :code PERSIST → :code GATE

   Deriving the CQs is single-turn reasoning over goal + profile(s): read the
   inputs, think, emit the questions. There is NO iterative tool-using session
   (Survey already explored the sources; the profiles carry the samples). So
   DERIVE is ONE `:llm` node — not a `:repl-researcher`, no recursion, no F3
   Phase-2 tick. PERSIST + GATE are DETERMINISTIC orchestration (a command + the
   reused S15 runner) → two `:code` nodes, no `:llm` involvement there.

     1. `:llm`  DERIVE  — re-house `cq-node-prompt` (#13 :reasoning FIRST).
     2. `:code` PERSIST — REUSE `record-competency-questions!` (consumer override).
     3. `:code` GATE    — REUSE `evaluate-cqs!` (S15 retrieve-then-judge).

   ## C1 — what crosses (and is read back)

   The verdict + graph-health are produced by `:code` nodes (the S15 runner returns
   real Clojure data), so they cross `:delegate` PARSED (the C1 `:llm` JSON-string
   failure mode is node-type-specific to the AI executor's schema-coercion path,
   which a `:code` write does not traverse). The `:llm` DERIVE node's
   `:competency-questions` write is a CONCRETE `[:vector :string]` (the C1
   per-field-type fix — a concrete collection spec makes DSCloj parse the field
   into real Clojure data rather than returning raw text); `:rationale` likewise.
   The verdict/graph-health writes declare STRUCTURED schemas (defense-in-depth).

   ## Re-orchestration, not rewrite (8) + domain-agnostic (12) + semantic (#7/#12)

   No fork: the CQ-derivation PROSE is re-housed from DT5 (`cq-node-prompt` via the
   promotion seam); the persist is the reused `record-competency-questions!` (the
   `record-ontology-spec` command); the gate is the reused `evaluate-cqs!` (the S15
   three-layer retrieve-then-judge runner). The only EB8 additions are the
   `:llm`-node I/O framing (read `:reads`, emit `:writes`, no tool session), the
   profile-grounding instruction tail, the consumer-override seam, and the thin
   `:code` wrappers. NO vertical knowledge — the CQs come from the runtime goal ⨯
   the profiles; NO hardcoded phrase matching as the validation (the gate is the
   S15 retrieve-then-judge semantic runner)."
  (:require [ai.obney.orc.orc-service.interface :as dsl]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]
            [ai.obney.orc.ontology.core.cq-runner :as cqr]
            [ai.obney.orc.ontology.core.resilience :as res]
            [clojure.string :as str]))

;; =============================================================================
;; The CQ-derivation contract (re-housed from DT5) — the `:llm` DERIVE node output
;; =============================================================================

(def derive-contract-keys
  "The DERIVE `:llm` node's declared `:writes`: #13 `:reasoning` FIRST, then the
   DT5-frozen CQ contract (`:competency-questions :rationale`). Only
   `:competency-questions` persists into the ORSD spec; `:rationale` is surfaced
   for HITL review (the DT5 carry-forward), and `:reasoning` is the #13
   chain-of-thought."
  (into [:reasoning] dt/cq-contract-keys))

(def competency-questions-schema
  "C1 — the CONCRETE `[:vector :string]` schema for the `:competency-questions`
   write. For the `:llm` DERIVE node this is the load-bearing C1 per-field-type
   fix: a concrete collection spec makes the AI executor parse the field's text
   into real Clojure data (a bare `:any` would arrive as raw EDN/JSON text). It is
   also EXACTLY the ORSD-spec `:competency-questions` shape (`[:vector :string]`),
   so the derived value is persistable verbatim."
  [:vector :string])

(def rationale-schema
  "C1 — the CONCRETE `[:vector :string]` schema for the `:rationale` write (the
   per-CQ HITL-review rationale, parallel to `:competency-questions`). Concrete so
   DSCloj parses it into a real vector; not gated on (surfaced for review)."
  [:vector :string])

;; =============================================================================
;; The public OUTPUT contract — the gate verdict + the graph-health metric
;; =============================================================================

(def cq-verdict-key
  "The Validate+CQ subbehavior's gate-verdict OUTPUT: the per-CQ latest verdicts
   the S15 runner produced (`get-cq-evaluation-latest` shape — one entry per
   cq-index with `:verdict` ∈ {:pass :fail :unknown}, `:reasoning`,
   `:evidence-uris`, `:layer`, `:judged-by?`). Produced by a `:code` node → it
   crosses `:delegate` PARSED."
  :cq-verdict)

(def graph-health-key
  "The Validate+CQ subbehavior's graph-health OUTPUT: the S15 graph-health metric
   derived from the latest verdict per CQ (pass/unknown/fail counts + rates,
   layer-counts, judge-share). `:unknown-rate` is a FIRST-CLASS metric — NOT folded
   into fail-rate — surfacing 'what does the graph not know yet'."
  :graph-health)

(def competency-questions-key
  "The Validate+CQ subbehavior's derived-CQ OUTPUT: the CQs that were persisted as
   the ORSD spec (the derived set, or the consumer-supplied override). Surfaced
   for HITL review (verbatim)."
  :competency-questions)

(def cq-verdict-schema
  "STRUCTURED Malli schema for the `:cq-verdict` write — a VECTOR of per-CQ verdict
   MAPS (the `get-cq-evaluation-latest` shape). A concrete `[:vector [:map …]]`
   (NOT a bare `:map`/`:any`) documents the shape + keeps the contract robust
   across `:delegate`. `{:closed false}` + `:any` leaf values tolerate the rich
   verdict shape (evidence-uris, gaps, layer)."
  [:vector [:map {:closed false}
            [:cq-index {:optional true} :any]
            [:cq-text {:optional true} :any]
            [:verdict {:optional true} :any]]])

(def graph-health-schema
  "STRUCTURED Malli schema for the `:graph-health` write — the S15 graph-health
   metric map (or nil when no CQs were evaluated). `{:closed false}` + `:any` leaf
   values tolerate the metric's rich shape; `:maybe` allows the honest-nil case."
  [:maybe [:map {:closed false}
           [:total-cqs {:optional true} :any]
           [:pass-count {:optional true} :any]
           [:unknown-count {:optional true} :any]
           [:fail-count {:optional true} :any]
           [:unknown-rate {:optional true} :any]]])

(def consumer-cqs-schema
  "Schema for the optional `:consumer-cqs` override read — a vector of strings (the
   consumer's CQs) or nil. When non-empty, derivation is OVERRIDDEN: the supplied
   questions are persisted instead of the derived set (the HITL override path)."
  [:maybe [:vector :string]])

(def profile-read-schema
  "Schema for the `:profile` read — the EB2 Survey profile(s) the CQs are grounded
   in. A SINGLE profile map OR a VECTOR of per-source profile maps (read tolerantly
   — `normalize-profiles` coerces either to a vector). `{:closed false}` + `:any`
   leaves tolerate the DT2 value-shape variance (a profile field may be prose or a
   structured map/vector)."
  [:maybe :any])

;; =============================================================================
;; Profile normalization — accept one profile map OR a vector of profile maps
;; =============================================================================

(defn normalize-profiles
  "Coerce the `:profile` read into a VECTOR of per-source profile maps for the
   derivation prompt. The contract reads a single `:profile` (a `:delegate` :reads
   input), but the CQ derivation grounds in the goal ⨯ ALL profiled sources — so we
   tolerate either a single profile MAP or a VECTOR of profile maps. Read tolerantly
   (the DT2 carry-forward): blanks/nils dropped. Returns [] for nothing usable (the
   honest-empty signal — the caller guards on it)."
  [profile]
  (cond
    (nil? profile)        []
    (map? profile)        [profile]
    (sequential? profile) (vec (remove #(or (nil? %) (and (map? %) (empty? %))) profile))
    :else                 []))

;; =============================================================================
;; Node 1 (`:llm`) — DERIVE the CQs (re-house DT5 `cq-node-prompt`; #13 first)
;; =============================================================================

(def ^:private runtime-goal-sentinel
  "DT5's `cq-node-prompt` interpolates the goal inline. The Validate+CQ
   subbehavior gets its goal at RUNTIME as a `:delegate` :reads input (so one sheet
   serves any goal — the goal is not part of sheet identity). We pass this sentinel
   where the DT5 body expects the goal text, then frame the `:llm` node to read the
   real goal from the `goal` blackboard input. This keeps the DT5 CQ-derivation
   reasoning re-housed verbatim (no fork) while sourcing the goal from the
   blackboard."
  "the GOAL provided to you at runtime as the blackboard input `goal`")

(defn- llm-io-framing
  "Adapt the DT5 body (written for a `:repl-researcher`'s `(get-input …)` /
   `(final! …)` mechanics — it instructs the node to read `:profiles` via
   `get-input` and finalize) to THIS `:llm` node's I/O model. An `:llm` node is
   GIVEN its `:reads` keys as context (`goal` + `profile`) and PRODUCES its
   `:writes` keys as parsed output — there is NO `get-input`/`final!`/tool session.
   This block tells the model exactly that AND that the `profile` input may be a
   single profile map OR a vector of per-source profile maps, so the re-housed DT5
   prose (which references `:profiles` + `get-input` + `final!`) reads correctly."
  []
  (str "*** HOW THIS NODE WORKS (read carefully) ***\n"
       "You are a single REASONING step. You are GIVEN two inputs as context: the "
       "GOAL (`goal`) and the source PROFILE(S) (`profile`, the structured output "
       "of the earlier Survey step — either ONE profile map or a VECTOR of "
       "per-source profile maps, one per profiled source). You do NOT call any "
       "tools, you do NOT explore the sources, and you do NOT emit a behavior tree "
       "— you THINK over the goal + the profile(s) and PRODUCE the structured "
       "outputs described below. Ignore any general guidance about tool sessions, "
       "`get-input`, `final!`, or `emit-tree!`, and wherever the instructions below "
       "say to read the profiles via `(get-input :profiles)`, READ THEM FROM the "
       "`profile` input you are given instead — for THIS node you simply read the "
       "two inputs and emit the declared output fields.\n\n"))

(defn- output-framing
  "Spell out the `:llm` DERIVE node's declared `:writes` for the model —
   `:reasoning` FIRST (#13), then the `:competency-questions` vector, then the
   parallel `:rationale` vector. The DT5 contract-block already lists the CQ +
   rationale shapes; this block names the THREE separate write keys + the #13
   ordering so the `:llm` executor produces the right blackboard shape (and emits
   real Clojure vectors, not JSON strings)."
  []
  (str "\n\n*** YOUR OUTPUT — produce these fields, REASONING FIRST (#13) ***\n"
       "  1. `reasoning` — FIRST, before anything else: briefly think through "
       "which parts of the GOAL the graph must be able to answer, and which of "
       "those are GROUNDED by what the profile(s) show the sources actually "
       "contain (the entity-candidates, identifying/linking keys, scope-fields, "
       "grain). Chain-of-thought BEFORE the questions.\n"
       "  2. `competency-questions` — a VECTOR of natural-language question "
       "STRINGS (the load-bearing exit-gate questions; these persist as the ORSD "
       "spec the gate judges). Each is ONE clear, specific question, GOAL-ANCHORED "
       "and GROUNDED in the profile(s).\n"
       "  3. `rationale` — a VECTOR of one-line strings, PARALLEL to "
       "`competency-questions`, each naming (a) the part of the GOAL the CQ tests "
       "and (b) the profile field(s)/source(s) that GROUND it (for HITL review; "
       "not gated on).\n"
       "Emit real Clojure VECTORS for `competency-questions` and `rationale`, NOT "
       "JSON strings and NOT prose — the downstream steps read them as parsed "
       "Clojure data."))

(defn derive-prompt
  "The DERIVE node prompt: the DT5 CQ-derivation reasoning body
   (`discovery-tree/cq-node-prompt`, sourced through the promotion seam) re-housed
   VERBATIM — its two load-bearing properties (GROUNDED + GOAL-ANCHORED) unchanged
   — wrapped with the `:llm`-node I/O framing and the #13 reasoning-first output
   framing.

   The goal is read at RUNTIME from the `goal` blackboard input (a `:delegate`
   :reads input) so a single sheet serves any goal; the profile(s) are the other
   :reads input. Domain-agnostic (12): no vertical knowledge — it derives CQs for
   ANY set of profiled sources from goal + profile(s)."
  []
  (str
   (llm-io-framing)
   ;; Re-house the DT5 CQ-derivation body VERBATIM through the promotion seam
   ;; (discipline 8 — no fork). It already carries: the ONE-job framing, the TWO
   ;; load-bearing properties (GROUNDED + GOAL-ANCHORED), the tolerant-profile
   ;; instruction, the focused-set (4-8) guidance, the per-CQ rationale, and the
   ;; CQ-contract block. The goal slot gets the runtime-read sentinel (the real
   ;; goal is read as a blackboard input).
   (dt/assemble-node-prompt :requirements {:goal runtime-goal-sentinel})
   ;; The #13 reasoning-first output framing across the three write keys.
   (output-framing)))

;; =============================================================================
;; Node 2 (`:code`) — PERSIST the CQs as the ORSD spec (REUSE
;; `record-competency-questions!`; consumer override) → read back (discipline 7)
;; =============================================================================

(defn persist-cqs!
  "PERSIST the CQs as the S14 ORSD spec — the SAME spec build!'s S15 exit-criterion
   reads. REUSES `discovery-tree/record-competency-questions!` (the
   `:ontology/record-ontology-spec` command — command → schema-validated event →
   projection; NO bare append, Grain discipline #7). Returns the persisted CQs +
   their origin + the read-back-confirmed spec (discipline 7 — assert the events
   LANDED by reading the projection back, NOT by trusting the command return).

   CONSUMER OVERRIDE: when `consumer-cqs` is non-empty, the supplied questions are
   AUTHORITATIVE — they are persisted instead of the derived set (`:origin
   :supplied`), and the derived set is discarded. Otherwise the derived set is
   persisted (`:origin :derived`).

   HONEST EMPTY (#4/#5): if neither a consumer set nor a non-empty derived set is
   present, NOTHING is persisted and `:status :failed` is returned — the exit gate
   must never have nothing to judge (no false green: an empty spec is a FAIL, not
   a silent pass)."
  [ctx {:keys [ontology-id goal derived-cqs consumer-cqs]}]
  (when-not ontology-id
    (throw (ex-info "persist-cqs! requires :ontology-id (the granted scope)"
                    {:ontology-id ontology-id})))
  (let [supplied (dt/string-cqs consumer-cqs)
        derived  (dt/string-cqs derived-cqs)
        [cqs origin] (if (seq supplied) [supplied :supplied] [derived :derived])]
    (if (empty? cqs)
      {:status :failed
       :origin origin
       :competency-questions []
       :error (str "Validate+CQ produced NO competency questions to persist "
                   "(the exit gate would have nothing to judge against). "
                   "origin=" origin)}
      (let [rec (dt/record-competency-questions! ctx ontology-id cqs goal)
            ;; DISCIPLINE 7: assert the events LANDED by reading the projection
            ;; back — NOT by trusting the command return value.
            spec (ontology/get-ontology-spec ctx ontology-id)
            persisted (:competency-questions spec)]
        {:status :ok
         :origin origin
         :competency-questions cqs
         :spec-recorded? (= (vec cqs) (vec (or persisted [])))
         :persisted-cqs persisted
         :record-result rec}))))

(defn persist-code
  "The PERSIST `:code` node `:fn`. Receives the code-node calling convention
   (`{:inputs {:ontology-id … :goal … :competency-questions <derived> :rationale …
   :consumer-cqs <override>}}`). Reuses `persist-cqs!` (the
   `record-competency-questions!` command path) with the consumer-override seam,
   and writes the persisted `:competency-questions` (surfaced for HITL review) +
   the inter-node `:persist-result` (carrying the origin + read-back signal the
   GATE node and the public report consume)."
  [{:keys [inputs] :as ctx}]
  (let [{:keys [ontology-id goal competency-questions consumer-cqs]} inputs
        result (persist-cqs!
                (dissoc ctx :inputs :execution-context)
                {:ontology-id ontology-id
                 :goal goal
                 :derived-cqs competency-questions
                 :consumer-cqs consumer-cqs})]
    {competency-questions-key (vec (:competency-questions result))
     :persist-result result}))

;; =============================================================================
;; Node 3 (`:code`) — GATE: run the S15 CQ runner (REUSE `evaluate-cqs!`) → the
;; per-CQ verdict + graph-health (SEMANTIC retrieve-then-judge) — read back (#7)
;; =============================================================================

(defn run-gate!
  "Run the S15 CQ gate over the PERSISTED ORSD spec. REUSES
   `ontology/evaluate-cqs!` (the three-layer retrieve-then-judge runner — Layer-1
   deterministic existence, Layer-2 judge-over-retrieved-evidence, Layer-3
   explicit-unknown) — NO fork. This is SEMANTIC validation (CQ/retrieve-grounded),
   NOT structural lints or hardcoded phrase matching (#7/#12).

   Required: `:ontology-id`. Optional `:judge-fn` (the real LLM judge a production
   caller wires; tests inject a controlled judge).

   THE NO-JUDGE BOUNDARY (honest, not a crash): the Layer-2/3 retrieve-then-judge
   REQUIRES a judge-fn — a Clojure FN VALUE that cannot cross the `:delegate`
   blackboard (which is event-sourced). So when NO `:judge-fn` is supplied AND the
   persisted spec contains any non-Layer-1 (semantic) CQ, this does NOT call the
   S15 runner (which would NPE on the missing judge — a #5 root cause, not a
   band-aid). Instead it surfaces the gate honestly with `:run-reason
   :no-judge-non-layer1` and an empty verdict — the delegated path proves
   DERIVE+PERSIST while reporting that the SEMANTIC gate needs a wired judge (the
   direct/production path wires it). When a judge IS supplied, OR every CQ is
   Layer-1-resolvable (deterministic, zero-LLM), the full S15 runner runs.

   Returns the runner result PLUS the per-CQ latest verdicts read back from the
   projection (discipline 7 — `get-cq-evaluation-latest`, NOT the runner return
   value) and the graph-health metric. An `:unknown` verdict is preserved
   first-class (the graph lacks that fact-kind), never collapsed into a pass/fail."
  [ctx {:keys [ontology-id judge-fn]}]
  (when-not ontology-id
    (throw (ex-info "run-gate! requires :ontology-id (the granted scope)"
                    {:ontology-id ontology-id})))
  (let [spec (ontology/get-ontology-spec ctx ontology-id)
        cqs (vec (:competency-questions spec))
        any-non-layer-1? (some #(not= :layer-1-structural
                                      (:layer (cqr/classify-cq-layer %)))
                               cqs)
        ;; The judge can only be invoked when present; if it is absent AND some CQ
        ;; needs it, do not run the LLM-requiring runner (it would NPE) — surface
        ;; the no-judge boundary honestly.
        needs-judge-but-missing? (and (nil? judge-fn) any-non-layer-1?)
        run-result (when-not needs-judge-but-missing?
                     (ontology/evaluate-cqs!
                      (cond-> {:ctx ctx :ontology-id ontology-id}
                        judge-fn (assoc :judge-fn judge-fn))))
        ;; DISCIPLINE 7: read the verdict back from the projection — NOT from the
        ;; runner return value.
        latest (ontology/get-cq-evaluation-latest ctx ontology-id)
        health (ontology/get-graph-health ctx ontology-id)]
    {:cq-verdict (vec latest)
     :graph-health health
     :run-reason (if needs-judge-but-missing?
                   :no-judge-non-layer1
                   (:reason run-result))
     :evaluated-count (count (:evaluated run-result))}))

(defn gate-code
  "The GATE `:code` node `:fn`. Receives the code-node calling convention
   (`{:inputs {:ontology-id … :judge-fn <optional>}}`). Reuses `run-gate!` (the S15
   `evaluate-cqs!` retrieve-then-judge runner) and writes the public `:cq-verdict`
   (per-CQ latest verdicts, read back from the projection — #7) + `:graph-health`
   (the S15 metric). Both are native Clojure (a `:code`-node output) → they cross
   `:delegate` PARSED.

   A production caller wires the real LLM judge by passing `:judge-fn` on the
   blackboard (rendered from `cq-runner-judge-prompt-template` — the SAME
   production prompt the S15 live verify uses)."
  [{:keys [inputs] :as ctx}]
  (let [{:keys [ontology-id judge-fn]} inputs
        result (run-gate!
                (dissoc ctx :inputs :execution-context)
                {:ontology-id ontology-id
                 :judge-fn judge-fn})]
    {cq-verdict-key (:cq-verdict result)
     graph-health-key (:graph-health result)}))

;; =============================================================================
;; The delegatable Validate+CQ sheet — built on the EB1-EB7 registry pattern
;; =============================================================================

(defn validate-cq-subbehavior-name
  "Canonical registry name for the Validate+CQ subbehavior. Like EB3-EB7 (and
   UNLIKE per-source Survey), it bakes in NO source path — it derives CQs over the
   GOAL ⨯ the PROFILE(S) and gates the `:ontology-id` it is handed (all `:reads`
   inputs), so a SINGLE Validate+CQ sheet serves every source and graph.
   `\"<family>/<behavior>@v<N>\"` — version is part of identity (a new version is a
   new, separately-evolvable sheet; callers pinned to @v1 are never rebuilt out
   from under them)."
  []
  "ontology-validate-cq/validate-cq@v1")

(defn validate-cq-sheet-id-for
  "Look up the deterministic sheet-id for the Validate+CQ subbehavior (pure — no
   event-store read). The central tree points its `:delegate` `:target-sheet-id`
   here without rebuilding the subbehavior."
  []
  (dsl/sheet-id-for-name (validate-cq-subbehavior-name)))

(defn- robust-derive-tail
  "EB9 — the ROBUST CQ-derivation author's extra grounding emphasis. The
   failure-prone DERIVE output is an EMPTY (or non-goal-grounded) CQ set — the
   exit gate would then have nothing to judge. The ROBUST author is the SAME
   `derive-prompt` PLUS this tail forcing at least one goal-anchored, profile-
   grounded question. A more-robust SECOND attempt (#8), tried by the `:fallback`
   only when the primary's CQ set failed the sanity gate. Domain-agnostic (#12)."
  []
  (str "\n\n*** ROBUST CQ DERIVATION (a careful re-attempt) ***\n"
       "A prior attempt produced NO usable competency questions. Re-derive "
       "carefully: in your `reasoning`, FIRST name the concrete parts of the GOAL "
       "the graph must answer + the profile field(s) that GROUND each, then commit "
       "to AT LEAST ONE clear, specific, goal-anchored question grounded in what "
       "the profile(s) actually show. Do NOT return an empty set — the exit gate "
       "must have something to judge."))

(defn robust-derive-prompt
  "The ROBUST DERIVE prompt: the primary `derive-prompt` PLUS the EB9 robust tail."
  []
  (str (derive-prompt) (robust-derive-tail)))

(defn validate-cq-subbehavior-def
  "The Validate+CQ subbehavior workflow definition.

   Body: a `:llm` → `:code` → `:code` sequence:
     1. `:llm`  derive  — re-house DT5 `cq-node-prompt` (#13 :reasoning FIRST)
     2. `:code` persist — REUSE `record-competency-questions!` (consumer override)
     3. `:code` gate    — REUSE `evaluate-cqs!` (S15 retrieve-then-judge)

   Contract (the public `:reads`/`:writes`):
     :reads  [:ontology-id :goal :profile (:consumer-cqs) (:judge-fn)]
     :writes [:competency-questions :cq-verdict :graph-health]
   The internal inter-node keys (`:reasoning`, `:rationale`, `:persist-result`)
   live on the sheet blackboard between nodes. The CQ-derivation writes declare
   CONCRETE `[:vector :string]` schemas (the `:llm`-node C1 per-field-type fix);
   the verdict/graph-health writes declare STRUCTURED schemas (defense-in-depth).

   `:consumer-cqs` (optional read) OVERRIDES derivation when supplied. `:judge-fn`
   (optional read) is the real LLM judge a production caller wires into the S15
   gate; when omitted, the S15 runner surfaces non-Layer-1 gaps honestly.

   `resilient?` (EB9, optional) wraps the failure-prone DERIVE `:llm` step in a
   `with-resilience` sub-tree: the PRIMARY derivation is gated by a SEMANTIC
   `:llm-condition` (did it produce a non-empty, goal-grounded CQ set?); on a gate
   failure a ROBUST author re-attempts; if BOTH still produce nothing usable, a
   troubleshoot `:llm` lands a structured `:diagnosis` and the subbehavior returns
   a CLEAN `:failure` (the exit gate never has nothing to judge dressed as a pass —
   #4/#5). The public `:reads`/`:writes` contract is UNCHANGED."
  [{:keys [model resilient?]}]
  (let [nm (validate-cq-subbehavior-name)
        mdl (or model "google/gemini-3-flash-preview")
        derive-node
        (fn [path-label prompt]
          (dsl/llm (str "derive-" path-label)
            :model mdl
            :instruction prompt
            :reads [:goal :profile]
            ;; #13 — :reasoning FIRST (chain-of-thought before the questions).
            :writes derive-contract-keys))
        derive-body
        (if resilient?
          (res/with-resilience
            {:step "derive"
             :primary (derive-node "primary" (derive-prompt))
             :robust  (derive-node "robust" (robust-derive-prompt))
             ;; SEMANTIC gate — a non-empty, goal-grounded CQ set (the CQ vector
             ;; can't be checked for non-emptiness by a flat deterministic
             ;; :condition; a yes/no :llm-condition judges it — NOT a hardcoded
             ;; phrase list, #7).
             :gate {:llm-check
                    {:model mdl
                     :reads [:competency-questions]
                     :instruction
                     (str "You are a sanity gate. Below is a set of competency "
                          "questions a derivation step produced. Answer YES only if "
                          "it is a NON-EMPTY list containing at least one clear, "
                          "specific, answerable question. Answer NO if the list is "
                          "empty or contains no usable question. Answer strictly yes "
                          "or no.")}}
             :troubleshoot
             {:reads [:goal :profile :competency-questions]
              :model mdl
              :step-label "the competency-question derivation (goal × profile → CQs)"
              :expectation (str "a non-empty, goal-anchored set of competency "
                                "questions grounded in the profile")}})
          (dsl/llm "derive"
            :model mdl
            :instruction (derive-prompt)
            :reads [:goal :profile]
            :writes derive-contract-keys))]
    (dsl/workflow nm
      (dsl/blackboard
       (merge
        {;; public :reads — the granted scope + goal + profile(s)
         :ontology-id :any
         :goal :string
         :profile profile-read-schema
         ;; optional override + judge wiring
         :consumer-cqs consumer-cqs-schema
         :judge-fn :any
         ;; internal inter-node keys
         :reasoning :string
         :rationale rationale-schema
         :persist-result [:map {:closed false}]
         ;; public :writes — the derived CQs (HITL) + the gate verdict
         competency-questions-key competency-questions-schema
         cq-verdict-key cq-verdict-schema
         graph-health-key graph-health-schema}
        ;; EB9 — the resilience-internal keys (#13 reasoning + structured
        ;; :diagnosis + the always-fail sentinel) when resilient.
        (when resilient? (res/resilience-blackboard-keys))))
      (dsl/sequence "validate-cq-root"
        ;; Node 1 — DERIVE the CQs (resilient :fallback when :resilient?).
        derive-body
        ;; Node 2 — PERSIST as the ORSD spec (REUSE record-competency-questions!;
        ;; consumer override). Reads the derived CQs + the consumer override.
        (dsl/code "persist"
          :fn "ai.obney.orc.ontology.core.validate-cq-subbehavior/persist-code"
          :reads [:ontology-id :goal :competency-questions :consumer-cqs]
          :writes [competency-questions-key :persist-result])
        ;; Node 3 — GATE (REUSE evaluate-cqs! — S15 retrieve-then-judge).
        (dsl/code "gate"
          :fn "ai.obney.orc.ontology.core.validate-cq-subbehavior/gate-code"
          :reads [:ontology-id :judge-fn]
          :writes [cq-verdict-key graph-health-key])))))

(defn register-validate-cq-subbehavior!
  "REGISTER (build, idempotent) the Validate+CQ subbehavior sheet and return its
   deterministic sheet-id. Re-registering an unchanged def is a no-op (same id).
   The central evolver tree resolves the name → id via `validate-cq-sheet-id-for`
   and `:delegate`s to it."
  [ctx {:keys [model resilient?]}]
  (dsl/build-workflow! ctx (validate-cq-subbehavior-def {:model model :resilient? resilient?})))
