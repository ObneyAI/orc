(ns ai.obney.orc.ontology.core.central-evolver
  "EB10 — the CENTRAL evolver loop (the keystone). CQ-satisfaction as the OBJECTIVE.

   This is the RE-ORCHESTRATION slice: it RE-HOUSES DT8's tree-owned adaptive
   CQ-loop + focused-recovery logic and DT9's greenfield-vs-maintain front-of-tree
   decision, and REUSES the deterministic skeleton `build!` (dedup + the S15
   exit-criterion) — but instead of DT1-DT9's INLINE Profile/Model/Transform nodes
   it composes the EB2-EB9 SUBBEHAVIORS via `:delegate`. Nothing here forks the
   subbehaviors or the loop machinery; the new work is the COMPOSITION + the ROUTE.

   ## The central tree (the OBJECTIVE is CQ-satisfaction, not a terminal report)

     :condition greenfield-vs-maintain (re-house DT9 decision)
       → Survey (per source, :delegate ontology-survey/…@v1)
       → derive CQs (Validate+CQ derive, :delegate) + persist ORSD
       → bounded LOOP [
            :map-each sources (delegate Model→Extract; the fixed per-source sheet,
                               optionally :parallel)
            → :code land drafts + delegate Reconcile + delegate Axiom/TBox
            → delegate Embed+Index (guaranteed P2)
            → :code build! (dedup + the S15 exit-criterion)     ; deterministic skeleton
            → run the CQ gate (evaluate-cqs! IN-PROCESS w/ the judge capability —
                               the judge fn CANNOT cross :delegate)
            → :condition on the verdict:
                 pass         → done
                 fail         → ROUTE (ONE adaptive :llm/decision node, :reasoning
                                FIRST: map the failing CQ + graph-health → the
                                subbehavior that closes the gap) → re-invoke FOCALLY
                                → re-gate
                 unanswerable → terminate HONESTLY (no spin, no false-green; reason)
          ]   ; budget-bounded — ALWAYS terminates with a surfaced reason

   ## RE-HOUSE / REUSE (discipline 8 — no fork)

   - The LOOP + the honest-negative ethos + the budget bound + the unanswerable
     detection are DT8's `cq-driven-loop!` SHAPE, re-housed here so each step
     invokes a SUBBEHAVIOR rather than the inline transform node. The DT8 loop's
     `focused-reextract!` re-ran the inline transform node; EB10's ROUTE re-invokes
     the CLOSING SUBBEHAVIOR focally (missing entity → Extract, missing link →
     Reconcile, missing class/attr → Axiom/Model, absent-in-source → terminate).
   - The greenfield-vs-maintain `:condition` is `dt/greenfield-vs-maintain-branch-stub`
     (DT9) — reused verbatim. Maintain short-circuits to the EXPLICIT, NAMED
     `dt/maintain-deferred-stub` (no silent gap, no partial build).
   - `build!` (skeleton) runs UNCHANGED (dedup + S15 exit-criterion + the graph-
     health verdict shape).
   - The CQ gate is the SUBBEHAVIOR's own `run-gate!` (EB8 `evaluate-cqs!` — the S15
     three-layer retrieve-then-judge runner), run IN-PROCESS with the judge
     capability, because the judge is a Clojure FN VALUE that cannot cross the
     `:delegate` blackboard (event-sourced). Survey/Model/Extract/Reconcile/Axiom/
     Embed all cross `:delegate`; the gate's JUDGE does not, so the gate runs
     in-process (the EB8/EB9 fn-value boundary).

   ## The ROUTE — ONE adaptive :llm/decision node (NOT hardcoded phrase matching)

   On a `:failed-cq` verdict the ROUTE reads the FAILING CQ + the graph-health and
   maps the gap to the subbehavior that closes it. It is ONE `:llm`/decision node
   that writes `:reasoning` FIRST (#13). The mapping is the model's reasoning over
   the CQ text + graph-health (NOT a string-equality table, #7/#12). The decision
   space is the closeable subbehaviors + `:terminate` (genuinely-absent-in-source).
   `route-decision` is the injected seam (production wires the real `:llm` route
   node sheet; tests stub the verdict→route mapping deterministically).

   ## Always terminates, never false-greens (#4/#9)

   The loop stops on a gate PASS (`:cq-gate-passed`), on every remaining CQ being
   UNANSWERABLE/terminate-routed (`:all-remaining-unanswerable`), or on budget
   exhaustion (`:budget-exhausted`) — and ALWAYS surfaces a `:termination-reason`.
   An unanswerable/budget termination is `:status :failed-cq` carrying the
   surfaced reason + the unanswered CQs, NEVER a fake `:complete`."
  (:require [ai.obney.orc.orc-service.interface :as dsl]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]
            [ai.obney.orc.ontology.core.deterministic-skeleton :as skeleton]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.survey-subbehavior :as survey]
            [ai.obney.orc.ontology.core.model-subbehavior :as model]
            [ai.obney.orc.ontology.core.synthesize-vocab-subbehavior :as synth]
            [ai.obney.orc.ontology.core.graph-context-snapshot :as gcs]
            [ai.obney.orc.ontology.core.extract-subbehavior :as extract]
            [ai.obney.orc.ontology.core.container-select :as csel]
            [ai.obney.orc.ontology.core.reconcile-subbehavior :as reconcile]
            [ai.obney.orc.ontology.core.axiom-tbox-subbehavior :as axiom]
            [ai.obney.orc.ontology.core.embed-index-subbehavior :as embed]
            [ai.obney.orc.ontology.core.validate-cq-subbehavior :as vcq]
            [ai.obney.orc.ontology.core.vocabulary-binding :as vb]
            [clojure.string :as str]))

;; =============================================================================
;; The fixed-composed per-source PIPELINE sheet — Model → Extract via :delegate
;; (the structural :delegate-composition proof; :map-each-able / :parallel-able)
;; =============================================================================
;;
;; The per-source Model → Extract pair is the part of the loop that is a FIXED
;; composition over the subbehaviors (vs. the dynamic land/reconcile/axiom/embed/
;; gate/route steps that read the runtime graph state + the judge). We build it as
;; a real central ORC sheet whose two nodes `:delegate` to the Model + Extract
;; subbehaviors, mapping `:writes`→`:reads` (Model `:model-spec` → Extract) on the
;; ISOLATED child blackboard. The central evolver runs this sheet per source (the
;; `:map-each` over sources; `:parallel` is the executor's per-source concurrency).

;; GC-8 — forward declaration so the fixed pipeline sheet (below) can size its INNER
;; `delegate-extract` `:timeout-ms` to the same cap-scaled budget the OUTER delegate
;; uses (the fn + its knobs are defined later in this ns; resolved at sheet-build
;; time, after ns load). Without this the inner Extract delegate kept a flat 180s and
;; timed out the 25-container serial extract at ~187s BEFORE the outer 810s applied.
(declare model-extract-timeout-ms)

;; MT-2 — the container relevance-RANK subbehavior registrar is defined later in this
;; ns (alongside the other :delegate seams); `register-pipeline-sheets!` (above those
;; defs) registers it, so forward-declare it here (same precedent as the timeout fn).
(declare register-select-rank-subbehavior!)

(def model-extract-pipeline-name
  "Canonical registry name for the fixed Model → Extract per-source pipeline sheet.
   Source-agnostic (Model + Extract are both source-agnostic @v1 sheets), so ONE
   pipeline sheet serves every source — the runtime goal/profile/source are the
   `:reads` inputs."
  "ontology-central/model-extract-pipeline@v1")

(defn model-extract-pipeline-sheet-id-for
  "Pure name→deterministic sheet-id lookup for the Model→Extract pipeline sheet."
  []
  (dsl/sheet-id-for-name model-extract-pipeline-name))

(def default-llm-delegate-timeout-ms
  "MS-5 — the deref-timeout for the SMALL LLM-backed delegates (survey,
   select-rank, synthesize-vocab, derive-CQs, axiom, embed) that previously
   rode `delegate-subbehavior!`'s flat 180s destructuring default. Each is a
   handful of LLM calls (a survey is a recursive-RLM session), so 180s assumed
   a fast provider — on a slow-provider day the survey delegate timed out at
   EXACTLY 180s three times in a row (witnessed live on the incremental
   accretion series), the third member of the flat-180s family after extract
   (GC-8-sized) and reconcile (MS-2-sized). 10 min absorbs provider latency
   while still bounding a genuine hang. The two WORK-SCALED delegates keep
   their derived budgets (`model-extract-timeout-ms`, `reconcile-timeout-ms`)."
  600000)

(defn model-extract-pipeline-def
  "The fixed-composed per-source pipeline sheet: `:delegate` Model → `:delegate`
   Extract, mapping Model's `:model-spec` → Extract's `:reads` on the ISOLATED
   child blackboard.

   Contract (the public `:reads`/`:writes`):
     :reads  [:goal :profile :source]
     :writes [:model-spec :candidate-axioms :embed-fields
              :concept-drafts :relationship-drafts :extraction-report]
   Model writes `:reasoning :model-spec :candidate-axioms` (and `:embed-fields`
   inside the model-spec); we re-surface `:embed-fields` as a top-level write for
   the loop's Embed step. Extract reads `:model-spec :source` and writes the draft
   set. Both delegated sheets must be registered first (the loop registers them)."
  [{:keys [_model _resilient?]}]
  (let [model-sid (model/model-sheet-id-for)
        extract-sid (extract/extract-sheet-id-for)]
    (dsl/workflow model-extract-pipeline-name
      (dsl/blackboard
       {;; public :reads
        :goal :string
        :profile [:map {:closed false}]
        :source [:map {:closed false}]
        ;; GC-6 — the shared discovered vocabulary, delegated IN to the Model so the
        ;; same real entity gets the same canonical type/key across sources. Optional
        ;; ([:maybe …]) so the pre-GC-6 / no-vocab path still runs.
        :vocabulary [:maybe synth/vocabulary-schema]
        ;; GM-1 — the graph-context snapshot (existing entity types + keying + predicates
        ;; + a sample), delegated IN to the Model so it models new data AGAINST the graph
        ;; built so far. Optional ([:maybe …]) so the empty-graph first-source path runs.
        gcs/graph-context-key [:maybe gcs/graph-context-schema]
        ;; GC-9 — the reduced-cap knobs, delegated IN to the Extract step (below) so a
        ;; caller can bound the per-source draft volume for a reduced-cap build.
        ;; Optional ([:maybe …]) so the unset → extract-default path still runs.
        :max-containers [:maybe :int]
        :max-windows [:maybe :int]
        ;; MT-2 — the survey-driven relevance SELECTION (structural pre-filter → LLM
        ;; relevance rank → bounded), delegated IN to the Extract step so the
        ;; orchestrator drives EXACTLY the selected containers. Optional ([:maybe …])
        ;; so the no-selection / csv single-container path falls back to take-cap.
        :selected-containers [:maybe csel/selected-containers-schema]
        ;; Model outputs (cross :delegate as parsed maps — the EB3 C1 schemas)
        :reasoning :string
        :model-spec model/model-spec-contract-schema
        :candidate-axioms model/candidate-axioms-schema
        ;; Extract outputs
        :concept-drafts extract/concept-drafts-schema
        :relationship-drafts extract/relationship-drafts-schema
        :extraction-report extract/extraction-report-schema})
      (dsl/sequence "model-extract-root"
        ;; STEP 1 — :delegate Model (goal × profile × vocabulary → model-spec +
        ;; candidate-axioms). GC-6 — the shared vocabulary is read into the Model so
        ;; its entity-type naming is constrained to the canonical type/key.
        (dsl/delegate "delegate-model"
          :target-sheet-id model-sid
          ;; GM-1 — also thread the graph-context snapshot into the Model.
          :reads [:goal :profile :vocabulary gcs/graph-context-key]
          :writes [:reasoning :model-spec :candidate-axioms]
          ;; MS-5b — the LAST flat-180s literal: the Model node is ~10s on a
          ;; fast provider, but on a slow-provider day a single Model call can
          ;; exceed 180s, and every EB9 ladder rung re-pays it (witnessed as a
          ;; 36-min :failed-at-model-extract churn on the accretion series).
          :timeout-ms default-llm-delegate-timeout-ms)
        ;; STEP 2 — :delegate Extract (model-spec × source → drafts). Reads the
        ;; model-spec Model just wrote onto the central child blackboard.
        (dsl/delegate "delegate-extract"
          :target-sheet-id extract-sid
          ;; GC-9 — cross the reduced-cap knobs onto the Extract sheet (the Extract
          ;; orchestrate node reads :max-containers/:max-windows; unset → its defaults).
          ;; MT-2 — also cross the :selected-containers survey-driven selection so the
          ;; orchestrator drives exactly the selected containers (unset → take-cap).
          :reads [:model-spec :source :max-containers :max-windows :selected-containers]
          :writes [:concept-drafts :relationship-drafts :extraction-report]
          ;; GC-8/GC-13 — the Extract step runs up to default-max-containers
          ;; containers (GC-13: now with BOUNDED CONCURRENCY, was serial); size its
          ;; budget to that work (cap-scaled, = the outer delegate's budget), NOT a
          ;; flat 180s (which cut the 25-container extract at container ~10-11 with 0
          ;; drafts). The budget stays cap-scaled — harmlessly generous under parallel
          ;; extract (the build just finishes sooner). delegate-model above stays 180s
          ;; — the Model node is ~10s.
          :timeout-ms (model-extract-timeout-ms
                       {:max-containers extract/default-max-containers}))))))

(defn register-pipeline-sheets!
  "Register (idempotent) the Model + Extract subbehaviors AND the fixed
   Model→Extract pipeline sheet, returning their deterministic sheet-ids. The
   pipeline `:delegate`s to Model/Extract by their deterministic ids, so they must
   exist first. `resilient?` (EB9) is threaded to Model + Extract."
  [ctx {:keys [model resilient?]}]
  (let [model-sid (model/register-model-subbehavior! ctx {:model model :resilient? resilient?})
        extract-sid (extract/register-extract-subbehavior! ctx {:model model :resilient? resilient?})
        ;; GC-6 — register the synthesize-vocab subbehavior so the real
        ;; `delegate-synthesize-vocab!` seam resolves its sheet by name.
        synth-sid (synth/register-synthesize-vocab-subbehavior! ctx {:model model})
        ;; MT-2 — register the container relevance-RANK subbehavior so the real
        ;; `delegate-select-containers!` seam resolves its sheet by name.
        select-rank-sid (register-select-rank-subbehavior! ctx {:model model})
        pipeline-sid (dsl/build-workflow!
                      ctx (model-extract-pipeline-def {:model model :resilient? resilient?}))]
    {:model-sheet-id model-sid
     :extract-sheet-id extract-sid
     :synthesize-vocab-sheet-id synth-sid
     :select-rank-sheet-id select-rank-sid
     :pipeline-sheet-id pipeline-sid}))

;; =============================================================================
;; The ROUTE — one adaptive :llm/decision node (the gap → closing subbehavior map)
;; =============================================================================

(def routable-subbehaviors
  "The decision space the ROUTE node maps a failing CQ + graph-health onto — the
   subbehaviors that can CLOSE a gap, plus `:terminate` for a genuinely-absent-in-
   source CQ. The mapping is the model's reasoning over the CQ text + graph-health
   (NOT a hardcoded phrase table — #7/#12). The four close-paths mirror the handoff:
     :extract   — a MISSING ENTITY the source has but the graph lacks (re-extract).
     :reconcile — a MISSING LINK between entities the graph already holds.
     :axiom     — a MISSING CLASS/ATTRIBUTE constraint (TBox the graph lacks).
     :model     — a MIS-MODELED grain/scope the re-extract needs re-decided first.
     :terminate — the source genuinely lacks the data the CQ needs (honest #4/#9)."
  #{:extract :reconcile :axiom :model :terminate})

(def route-node-name
  "Registry name for the standalone ROUTE :llm/decision sheet (production seam).
   ONE adaptive node, `:reasoning` FIRST (#13), source-agnostic."
  "ontology-central/route-decision@v1")

(defn route-node-sheet-id-for []
  (dsl/sheet-id-for-name route-node-name))

(defn route-prompt
  "The ROUTE node prompt — map a FAILING competency question + the graph-health to
   the subbehavior that CLOSES the gap. The model reasons over the CQ + graph-health
   (NOT a phrase table, #7/#12); `:reasoning` is written FIRST (#13). Domain-
   agnostic (#12) — it names no vertical; it reasons about graph-shape gaps."
  []
  (str
   "*** HOW THIS NODE WORKS (read carefully) ***\n"
   "You are a single ROUTING decision step. You are GIVEN as context a FAILING "
   "competency question (`failing-cq`, a question the built graph could NOT answer) "
   "and the GRAPH-HEALTH metric (`graph-health`, the per-CQ pass/unknown/fail counts "
   "+ rates). You do NOT call tools, you do NOT emit a tree — you THINK over the gap "
   "and DECIDE which ONE focused step is most likely to close it, then PRODUCE the "
   "declared outputs. Ignore any general guidance about tool sessions or "
   "`emit-tree!`.\n\n"
   "*** YOUR OUTPUT — produce these fields, REASONING FIRST (#13) ***\n"
   "  1. `reasoning` — FIRST, before anything else: think through WHY the graph "
   "cannot answer the failing CQ. Is the needed ENTITY missing (the source has it "
   "but it was not extracted)? Is the needed LINK between two entities missing "
   "(both exist but are not connected)? Is a CLASS/ATTRIBUTE-level CONSTRAINT "
   "(disjointness / sub-class / functional key) missing? Was the grain/scope "
   "MIS-MODELED so the right entities were never extracted? Or does the SOURCE "
   "GENUINELY NOT CONTAIN the data the question needs (in which case no re-run can "
   "close it — terminate honestly)? Chain-of-thought BEFORE the decision.\n"
   "  2. `route` — EXACTLY ONE keyword naming the focused step that closes the gap:\n"
   "       :extract   — a MISSING ENTITY the source has but the graph lacks "
   "(re-author + re-apply the per-row extraction).\n"
   "       :reconcile — a MISSING LINK between entities the graph already holds "
   "(re-link / cross-source reconcile).\n"
   "       :axiom     — a MISSING CLASS/ATTRIBUTE CONSTRAINT (a TBox axiom the graph "
   "lacks — disjointness / sub-class / functional).\n"
   "       :model     — a MIS-MODELED grain/scope (re-decide the entity model before "
   "re-extracting).\n"
   "       :terminate — the SOURCE GENUINELY LACKS the data the CQ needs; no re-run "
   "can close it (terminate honestly — do NOT fabricate data, do NOT spin).\n"
   "Emit `route` as a real keyword (one of :extract :reconcile :axiom :model "
   ":terminate), NOT prose and NOT a JSON string."))

(def route-decision-schema
  "Schema for the ROUTE node's `:route` write — a keyword in the decision space.
   Concrete (NOT `:any`) so the `:llm` executor parses it into a real keyword."
  [:enum :extract :reconcile :axiom :model :terminate])

(defn route-node-def
  "The standalone ROUTE :llm/decision sheet — ONE adaptive node, `:reasoning` FIRST.

   Contract:
     :reads  [:failing-cq :graph-health]
     :writes [:reasoning :route]
   `:route` ∈ routable-subbehaviors. Source-agnostic; the central evolver invokes
   it per failing CQ to decide the closing subbehavior (or :terminate)."
  [{:keys [model]}]
  (let [mdl (or model "google/gemini-3-flash-preview")]
    (dsl/workflow route-node-name
      (dsl/blackboard {:failing-cq :string
                       :graph-health [:maybe [:map {:closed false}]]
                       :reasoning :string
                       :route route-decision-schema})
      (dsl/sequence "route-root"
        (dsl/llm "route"
          :model mdl
          :instruction (route-prompt)
          :reads [:failing-cq :graph-health]
          ;; #13 — :reasoning FIRST (chain-of-thought before the decision).
          :writes [:reasoning :route])))))

(defn register-route-node!
  "Register (idempotent) the ROUTE :llm/decision sheet, returning its sheet-id."
  [ctx {:keys [model]}]
  (dsl/build-workflow! ctx (route-node-def {:model model})))

;; =============================================================================
;; Delegation seams — run a subbehavior via :delegate and read the writes back off
;; the PARENT tick blackboard (discipline 7 — the projection, not the return value)
;; =============================================================================

(defn delegate-subbehavior!
  "Run a subbehavior sheet via a CENTRAL `:delegate` tree and read its `:writes`
   back off the PARENT tick blackboard from the projection (discipline 7 — NOT the
   `execute` return value). This is the production delegation seam every subbehavior
   step uses: it builds a thin one-node central `:delegate` sheet for the target,
   executes it with the mapped `:reads`, and returns `{:status … :outputs {…}}`.

   `central-name` is a stable per-target name; `bb-schema` is the central tree's
   blackboard (declaring the SAME key names + schemas as the target — `:delegate`
   maps by name, and the structured schemas keep the contract parsed across the
   seam). `inputs` is the string-keyed `:reads` map dsl/execute wants.

   The judge-fn (a Clojure fn value) CANNOT cross `:delegate` (event-sourced
   blackboard) — so a step needing the judge (the CQ gate) does NOT use this seam;
   it runs in-process (see `run-cq-gate!`)."
  [ctx {:keys [central-name target-sheet-id bb-schema reads writes inputs timeout-ms]
        :or {timeout-ms 180000}}]
  (let [central-id (dsl/build-workflow!
                    ctx (dsl/workflow central-name
                          (dsl/blackboard bb-schema)
                          (dsl/sequence "central-root"
                            (dsl/delegate "to-subbehavior"
                              :target-sheet-id target-sheet-id
                              :reads (vec reads)
                              :writes (vec writes)
                              :timeout-ms timeout-ms))))
        tick-id (random-uuid)
        result (dsl/execute ctx central-id inputs
                                :timeout-ms timeout-ms :tick-id tick-id)
        parent-bb (dsl/get-tick-blackboard ctx tick-id)
        outputs (reduce (fn [acc k]
                          (assoc acc k (get-in parent-bb [k :value])))
                        {} writes)]
    {:status (:status result)
     :tick-id tick-id
     :outputs outputs
     :error (:error result)}))

;; ------- the per-subbehavior production delegation seams (default fns) --------

(defn delegate-survey!
  "Production Survey seam: register the per-source Survey sheet + `:delegate` it.
   Returns `{:status … :profile <map>}`.

   MS-5 — sized `:timeout-ms` (`default-llm-delegate-timeout-ms`) instead of the
   flat 180s default, which cut a slow-provider survey three times running."
  [ctx {:keys [source goal model]}]
  (let [sub-id (survey/register-survey-subbehavior! ctx {:source source :model model})
        r (delegate-subbehavior!
           ctx {:central-name (str "ontology-central/survey-" (name (:type source)) "@v1")
                :target-sheet-id sub-id
                :timeout-ms default-llm-delegate-timeout-ms  ;; MS-5 — not the flat 180s
                :bb-schema {:goal :string
                            :source-descriptor :string
                            :profile survey/profile-contract-schema}
                :reads [:goal :source-descriptor]
                :writes [:profile]
                :inputs {"goal" goal
                         "source-descriptor" (survey/source-descriptor-string source)}})]
    {:status (:status r)
     :profile (get-in r [:outputs :profile])
     :tick-id (:tick-id r)
     :error (:error r)}))

(def default-per-container-budget-ms
  "GC-8 — the per-container time budget the Model→Extract delegate sizes its
   deref-timeout against. The Extract orchestrator runs up to
   `extract/default-max-containers` containers SERIALLY (`extract_subbehavior.clj`
   `orchestrate-extract-containers` — a `mapv`, NOT parallel), each ~6-22s PLUS the
   EB9 resilience cascade's extra LLM calls on a `:failure` container. This budget
   (30s) covers the observed worst-case ~22s container + that cascade + margin, so
   `(cap × this)` sizes the OUTER delegate deref-timeout to the realistic serial
   work instead of the flat 180s `delegate-subbehavior!` default — which cut the
   build at container ~10-11 and landed 0 drafts at `:failed-at-model-extract`.
   Named + overridable (mirrors `default-max-containers` / `default-max-extract-
   windows`) so the budget is legible + tunable, not a magic literal. DERIVE the
   budget from the cap (below) so it stays correct if the cap changes — do NOT bump
   a literal.

   RECALIBRATED 30s → 60s (2026-07-11): the 30s figure was measured under SERIAL
   extraction with a fast provider. Since then (a) GC-13 made container extraction
   concurrently bounded — the cap-scaled ceiling is margin, not pacing (`central
   -evolver` caller note: 'harmlessly generous under parallel extract — the build
   just finishes sooner'), and (b) the 13.5-min ceiling FIRED on two real uncapped
   O*NET runs (2026-07-08, 2026-07-11 — provider-latency variance + the EB9
   resilience cascade's extra calls on :failure containers), killing 50-80 min
   multi-source builds at :failed-at-model-extract with real drafts on the floor.
   The ceiling exists to prevent HANGS, not to cut slow-but-working extraction;
   60s/container (25-container ceiling: 26 min) absorbs a slow-provider day while
   still bounding a genuine hang."
  60000)

(def model-extract-overhead-budget-ms
  "GC-8 — the Model node + delegation/parse overhead allowance ADDED to the
   serial-container budget (the Model node alone is ~10s; `:delegate` build/execute
   + blackboard read-back add a little). Keeps a small source's ceiling comfortably
   above its real ~12s without depending on a per-container term."
  60000)

(defn model-extract-timeout-ms
  "GC-8 — DERIVE the Model→Extract delegate's deref-timeout from the container cap:
   `(cap × default-per-container-budget-ms) + model-extract-overhead-budget-ms`.
   It SCALES with the cap (a larger `:max-containers` → a larger ceiling), so the
   budget tracks the real serial work rather than a flat 180s. Absent
   `:max-containers`, resolves the cap to `extract/default-max-containers` (NOT a
   hardcoded 25) — the same value the Extract orchestrator reads. Pure + public so
   the budget is assertable (and behavior-preserving: a 1-container source's ceiling
   is `(1 × 30s) + 60s = 90s`, far above its real ~12s, so the ceiling never fires)."
  [{:keys [max-containers]}]
  (let [cap (or max-containers extract/default-max-containers)]
    (+ (* cap default-per-container-budget-ms)
       model-extract-overhead-budget-ms)))

(def max-vocabulary-recovery-retries
  "MT-7d — the BOUND on the vocabulary-recovery retry in `delegate-model-extract!`:
   at most N re-runs of the pipeline delegate when a failure's read-back
   model-spec normalizes to an EMPTY vocabulary (the diagnosed transient C1
   `:delegate`-crossing loss — the Model authors `:entity-types` but they arrive
   degraded to `[]` while sibling fields survive; an immediate re-run typically
   recovers).

   MT-9 — raised 1 → 3 (4 total attempts). The retry mechanism is probe-confirmed
   to fire and recover, but the per-attempt C1 loss rate is high (~0.4–0.6), so a
   double loss at bound=1 killed ~1-in-3 comprehensive runs at
   `:failed-at-model-extract`; 4 attempts drop the all-empty rate to ~6–13%. This
   is the interim reliability lever while the durable dscloj `[:vector [:map]]`
   parse root fix is queued.

   After N empty-vocabulary failures the HONEST failure is returned — the MT-7a
   loud stop stands on recurrence (#5); the exhausted retry count is surfaced;
   never an unbounded loop."
  3)

(defn delegate-model-extract!
  "Production Model→Extract seam: `:delegate` the fixed per-source pipeline sheet
   (Model → Extract). Returns the drafts + the model-spec + candidate-axioms +
   embed-fields the loop's land/axiom/embed steps consume.

   GC-8 — sizes the OUTER delegate `:timeout-ms` to the realistic SERIAL container
   work (`model-extract-timeout-ms`, derived from the container cap) instead of
   inheriting `delegate-subbehavior!`'s flat 180s default, which cut a multi-
   container build at container ~10-11 and landed 0 drafts. `max-containers` is
   threaded from the caller (the same cap the Extract orchestrator reads); absent,
   it resolves to `extract/default-max-containers`.

   MT-7d — bounded vocabulary-recovery retry. When the delegate did NOT succeed
   AND the read-back model-spec normalizes to an EMPTY vocabulary
   (`vb/empty-vocabulary?` — the SAME deterministic predicate as the MT-7a hard
   stop; NEVER matched on the error string, #7), the delegate is re-run up to
   `max-vocabulary-recovery-retries` times with identical inputs. A nil read-back is
   covered honestly by the same predicate (a Model that never wrote a spec could
   not have extracted either way; the retry is equally sane). The retry is
   SURFACED, never silent (#5): every return carries `:vocabulary-retries`
   (0..`max-vocabulary-recovery-retries`),
   and a retried call also carries `:degraded-model-spec-raw` — the FIRST
   attempt's raw PRE-normalize `:model-spec` output VERBATIM (never truncated,
   #11: the dossier for the deeper dscloj parse root-cause fix). A failure whose
   read-back vocabulary is NON-empty (a genuine extract-stage failure) never
   retries — behavior-preserving apart from the surfaced `:vocabulary-retries 0`."
  [ctx {:keys [source goal profile vocabulary graph-context pipeline-sheet-id max-containers max-windows
               selected-containers]}]
  (let [run-delegate!
        (fn []
          (delegate-subbehavior!
           ctx {:timeout-ms (model-extract-timeout-ms {:max-containers max-containers})
                :central-name (str "ontology-central/pipeline-" (name (:type source)) "@v1")
                :target-sheet-id pipeline-sheet-id
                :bb-schema {:goal :string
                            :profile [:map {:closed false}]
                            :source [:map {:closed false}]
                            ;; GC-6 — the shared discovered vocabulary (optional;
                            ;; [:maybe …] tolerates the no-vocab path). STRUCTURED so
                            ;; it crosses :delegate parsed into the Model.
                            :vocabulary [:maybe synth/vocabulary-schema]
                            ;; GM-1 — the graph-context snapshot (optional; [:maybe …]
                            ;; tolerates the empty-graph first-source path). STRUCTURED
                            ;; so it crosses :delegate parsed into the Model.
                            gcs/graph-context-key [:maybe gcs/graph-context-schema]
                            ;; GC-9 — the reduced-cap knobs (optional; [:maybe …]
                            ;; tolerates the unset → extract-default path). STRUCTURED
                            ;; so they cross :delegate parsed into the Extract sheet.
                            :max-containers [:maybe :int]
                            :max-windows [:maybe :int]
                            ;; MT-2 — the survey-driven relevance selection (optional;
                            ;; [:maybe …] tolerates the no-select → take-cap path).
                            ;; STRUCTURED so it crosses :delegate parsed into Extract.
                            :selected-containers [:maybe csel/selected-containers-schema]
                            :model-spec model/model-spec-contract-schema
                            :candidate-axioms model/candidate-axioms-schema
                            :concept-drafts extract/concept-drafts-schema
                            :relationship-drafts extract/relationship-drafts-schema
                            :extraction-report extract/extraction-report-schema}
                :reads [:goal :profile :source :vocabulary gcs/graph-context-key
                        :max-containers :max-windows :selected-containers]
                :writes [:model-spec :candidate-axioms
                         :concept-drafts :relationship-drafts :extraction-report]
                :inputs {"goal" goal "profile" profile "source" source
                         "vocabulary" vocabulary
                         ;; GM-1 — forward the graph-context snapshot to the Model.
                         "graph-context" graph-context
                         ;; GC-9 — forward the caps so they reach the Extract orchestrator
                         ;; (nil → the orchestrator/APPLY falls back to its own defaults).
                         "max-containers" max-containers
                         "max-windows" max-windows
                         ;; MT-2 — forward the survey-driven selection to the Extract
                         ;; orchestrator (nil → the orchestrator falls back to take-cap).
                         "selected-containers" selected-containers}}))]
    ;; MT-7d — bounded retry loop: run the delegate; when the attempt FAILED and
    ;; its read-back model-spec normalizes to an EMPTY vocabulary (the transient
    ;; C1 crossing loss), re-run up to `max-vocabulary-recovery-retries` times, keeping
    ;; attempt 1's raw PRE-normalize spec verbatim as the dossier.
    (loop [retries 0
           first-degraded-raw nil]
      (let [r (run-delegate!)
            raw-model-spec (get-in r [:outputs :model-spec])
            ;; GC-10 Fix A — coerce a STRING-form `:entity-types` (the intermittent C1
            ;; parse failure) back to a vector of maps at the model-spec read-back
            ;; boundary, so every downstream consumer of the model-spec (the land/axiom/
            ;; embed steps AND the Extract orchestrator's canonicalizer) sees clean data,
            ;; never a 100%-degrade from a reduce over the string's characters.
            ;; Behavior-preserving for an already-parsed vector.
            model-spec (extract/normalize-model-spec raw-model-spec)]
        (if (and (< retries max-vocabulary-recovery-retries)
                 (not= :success (:status r))
                 ;; the retry condition is the SAME deterministic predicate as the
                 ;; MT-7a hard stop — NEVER the error string (#7). Applied to the
                 ;; NORMALIZED read-back (nil read-back → {:entity-types []} →
                 ;; empty, covered honestly). A non-empty vocabulary = a GENUINE
                 ;; extract-stage failure → no retry (never swallow a real failure).
                 (vb/empty-vocabulary? model-spec))
          ;; the FIRST attempt's raw pre-normalize spec is the dossier; keep it
          ;; VERBATIM (#11) across any further attempt.
          (recur (inc retries)
                 (if (zero? retries) raw-model-spec first-degraded-raw))
          (cond-> {:status (:status r)
                   :model-spec model-spec
                   :candidate-axioms (get-in r [:outputs :candidate-axioms])
                   ;; embed-fields are folded into the model-spec (EB3); surface them for Embed.
                   :embed-fields (vec (or (model/embed-fields-key model-spec) []))
                   :concept-drafts (vec (or (get-in r [:outputs :concept-drafts]) []))
                   :relationship-drafts (vec (or (get-in r [:outputs :relationship-drafts]) []))
                   :extraction-report (get-in r [:outputs :extraction-report])
                   :tick-id (:tick-id r)
                   :error (:error r)
                   ;; MT-7d — the retry is SURFACED on EVERY return (#5).
                   :vocabulary-retries retries}
            (pos? retries) (assoc :degraded-model-spec-raw first-degraded-raw)))))))

(def per-probe-budget-ms
  "MS-2 (GC-8 style) — the per-DRAFT time budget the Reconcile delegate sizes its
   deref-timeout against. Reconcile's dominant cost is the check-before-mint
   probe: up to `reconcile/default-max-probe` SEQUENTIAL P3 hybrid-search probes
   (graph BFS + embedding + ColBERT), observed at ≥1.5s each on an 18k-concept
   populated graph (2026-07-11 all-5-sources forensic — the flat 180s default
   ceiling was exceeded by EVERY post-first source, silently landing ZERO
   concepts). 2s/probe is the honest ceiling until MS-3 shrinks the real cost
   (URI-exact-match fast path + batched probe embedding). Named + overridable-by-
   derivation (mirrors `default-per-container-budget-ms`) — never a magic literal."
  2000)

(def reconcile-overhead-budget-ms
  "MS-2 (GC-8 style) — the non-probe Reconcile allowance ADDED to the per-probe
   budget: landing the drafts (compile-discovery-source!), the DT7 entity
   reconcile, the EB5 attribute reconcile, and the `:delegate` build/execute +
   blackboard read-back. Keeps a small/zero-draft reconcile's ceiling comfortably
   above its real work without depending on a per-probe term (mirrors
   `model-extract-overhead-budget-ms`)."
  120000)

(defn reconcile-timeout-ms
  "MS-2 (GC-8 style) — DERIVE the Reconcile delegate's deref-timeout from the
   DRAFT COUNT it will probe:
   `(min(draft-count, default-max-probe) × per-probe-budget-ms) + overhead`.
   It SCALES with the real probe work (GC-7 caps that work at
   `reconcile/default-max-probe`, so the ceiling caps there too) instead of the
   flat 180s `delegate-subbehavior!` default — which every post-first source's
   reconcile exceeded, timing out SILENTLY with ZERO concepts landed
   (2026-07-11 forensic). Pure + public so the budget is assertable. A
   zero/absent draft-count floors at the overhead (never 0)."
  [{:keys [draft-count]}]
  (+ (* (min (or draft-count 0) reconcile/default-max-probe)
        per-probe-budget-ms)
     reconcile-overhead-budget-ms))

(defn delegate-reconcile!
  "Production Reconcile seam: `:delegate` Reconcile (land + entity/attr reconcile).

   MS-2 — sizes the delegate `:timeout-ms` to the ACTUAL draft count it is handed
   (`reconcile-timeout-ms`) instead of inheriting `delegate-subbehavior!`'s flat
   180s default (the silent-zero-landing root cause)."
  [ctx {:keys [ontology-id concept-drafts relationship-drafts source-uri-sets model]}]
  (let [sub-id (reconcile/register-reconcile-subbehavior! ctx {:model model})
        r (delegate-subbehavior!
           ctx {:central-name "ontology-central/reconcile@v1"
                :target-sheet-id sub-id
                ;; MS-2 — size the deref-timeout to the ACTUAL probe work (the
                ;; concept-draft count is what check-before-mint probes).
                :timeout-ms (reconcile-timeout-ms {:draft-count (count concept-drafts)})
                :bb-schema {:ontology-id :any
                            :concept-drafts [:vector [:map {:closed false}]]
                            :relationship-drafts [:vector [:map {:closed false}]]
                            :source-uri-sets [:maybe [:vector [:map {:closed false}]]]
                            reconcile/reconcile-report-key reconcile/reconcile-report-schema}
                :reads [:ontology-id :concept-drafts :relationship-drafts :source-uri-sets]
                :writes [reconcile/reconcile-report-key]
                :inputs {"ontology-id" ontology-id
                         "concept-drafts" (vec (or concept-drafts []))
                         "relationship-drafts" (vec (or relationship-drafts []))
                         "source-uri-sets" source-uri-sets}})]
    {:status (:status r)
     :reconcile-report (get-in r [:outputs reconcile/reconcile-report-key])
     :tick-id (:tick-id r)
     :error (:error r)}))

(defn land-family-detail-hierarchy!
  "GC-10 Fix B2 — the GLOBAL family↔detail SKOS hierarchy step. After EVERY source
   has landed + reconciled, the graph holds same-canonical-type concepts at
   DIFFERENT code-system grains (e.g. IPEDS keys a field at the 2-digit family
   `fieldofstudy/01`, the crosswalk at the 6-digit detail `fieldofstudy/01.0407`).
   These are correctly DISTINCT entities (different identity values) but never
   relate — so a program→field(family) chain dead-ends before the
   field(detail)→occupation edges. This DETERMINISTIC step (NO LLM) reads the global
   concept set, runs the pure structural prefix-at-separator detector
   (`extract/hierarchy-relationship-drafts`), and LANDS the `skos:narrower`
   family→detail edges via the SAME Reconcile relationship-draft path (the
   create-relationship command), so the read-model populates family:narrower +
   detail:broader reciprocally and the graph traversal can hop family↔detail.

   Domain-agnostic (#12) + bounded (the detector caps per-parent fan-out + reports
   honestly). Returns the detector's report `{:edge-count :truncated …}` so the
   caller can surface it (no false-green — a high truncated count is a signal)."
  [ctx {:keys [ontology-id model reconcile-fn source-uri-sets]}]
  (let [reconcile-fn (or reconcile-fn delegate-reconcile!)
        concepts (rm/get-concepts ctx {:ontology-id ontology-id})
        ;; the read-model returns a {uri -> concept} map OR a seq of concepts;
        ;; normalize to a seq of {:uri …} so the pure detector reads :uri uniformly.
        concept-seq (cond
                      (map? concepts) (mapv (fn [[uri c]]
                                              (if (map? c) (assoc c :uri (or (:uri c) uri))
                                                  {:uri uri}))
                                            concepts)
                      (sequential? concepts) (vec concepts)
                      :else [])
        {:keys [relationship-drafts truncated-relations pairs-considered]}
        (extract/hierarchy-relationship-drafts concept-seq)]
    (when (seq relationship-drafts)
      ;; LAND via the SAME Reconcile relationship-draft path (no new concepts).
      (reconcile-fn ctx {:ontology-id ontology-id
                         :concept-drafts []
                         :relationship-drafts relationship-drafts
                         :source-uri-sets source-uri-sets :model model}))
    {:edge-count (count relationship-drafts)
     :truncated-count (count truncated-relations)
     :truncated truncated-relations
     :pairs-considered pairs-considered}))

(defn land-linking-key-spine!
  "GC-11b — the GLOBAL deterministic linking-key code-node spine (the cross-source
   JOIN). After EVERY source has landed + reconciled, the graph holds concepts from
   DIFFERENT sources whose `:attributes` carry the SAME cross-source linking-key
   VALUE (GC-11a copies the discovered linking-key values into every draft's
   `:attributes` even when the value is not the keying field). Those concepts are
   correctly DISTINCT entities (different identity URIs) and never relate — so a
   program→…→occupation chain that should hop on a shared code value strands.

   This DETERMINISTIC step (NO LLM) AGGREGATES the discovered linking-key NAMES
   across the per-source model-specs (`:linking-keys` — NO baked key names), reads
   the global concept set, runs the pure spine
   (`extract/linking-key-relationship-drafts`), and LANDS the minted CODE NODES +
   the `identified-by` attach edges via the SAME Reconcile draft path (the
   create-concept / create-relationship commands). The read-model lands
   `identified-by` in `:typed-edges` (keyed by the predicate) AND `:related`, so the
   graph traversal can hop carrier↔code↔carrier and the cross-source join is
   traversable.

   Domain-agnostic (#12) — the linking-key NAMES come from the survey/model-specs
   at runtime; the predicate is a fixed structural label. Bounded (#4/#5) — the
   spine caps per-code attach fan-out + reports honestly. Honest absence — a
   concept with no recoverable linking-key value mints/attaches nothing. Returns
   the spine's report `{:code-node-count :edge-count :linking-keys :truncated …}`
   so the caller can surface it (no false-green — a real linking-key set that mints
   ZERO code nodes is the GC-11a honest-gap signal, not a silent pass).

   `model-specs` is the per-source model-spec seq (each may carry `:linking-keys`);
   the aggregation is the union of their `:linking-keys`, deduped at the spine."
  [ctx {:keys [ontology-id model reconcile-fn source-uri-sets model-specs]}]
  (let [reconcile-fn (or reconcile-fn delegate-reconcile!)
        ;; AGGREGATE the discovered linking-key NAMES across the per-source
        ;; model-specs (NO baked key names — they are the survey's discovery). Union
        ;; + dedup (order-stable); the spine normalizes/dedups again defensively.
        linking-keys (->> (or model-specs [])
                          (mapcat (fn [ms] (or (:linking-keys ms) [])))
                          (remove nil?)
                          (distinct)
                          (vec))
        concepts (rm/get-concepts ctx {:ontology-id ontology-id})
        ;; the read-model returns a {uri -> concept} map OR a seq of concepts;
        ;; normalize to a seq of {:uri … :attributes …} so the pure spine reads
        ;; uniformly (it recovers values from :attributes, not the URI).
        concept-seq (cond
                      (map? concepts) (mapv (fn [[uri c]]
                                              (if (map? c) (assoc c :uri (or (:uri c) uri))
                                                  {:uri uri}))
                                            concepts)
                      (sequential? concepts) (vec concepts)
                      :else [])
        {:keys [concept-drafts relationship-drafts truncated-relations pairs-considered]}
        (if (seq linking-keys)
          (extract/linking-key-relationship-drafts concept-seq linking-keys)
          ;; no discovered linking key on ANY source → no spine (honest no-op,
          ;; behavior-preserving for a single-source / no-cross-source-code run).
          {:concept-drafts [] :relationship-drafts [] :truncated-relations []
           :pairs-considered 0})]
    (when (or (seq concept-drafts) (seq relationship-drafts))
      ;; LAND the code nodes + attach edges via the SAME Reconcile draft path
      ;; (create-concept for the code nodes, create-relationship for the edges).
      (reconcile-fn ctx {:ontology-id ontology-id
                         :concept-drafts concept-drafts
                         :relationship-drafts relationship-drafts
                         :source-uri-sets source-uri-sets :model model}))
    {:code-node-count (count concept-drafts)
     :edge-count (count relationship-drafts)
     :linking-keys linking-keys
     :truncated-count (count truncated-relations)
     :truncated truncated-relations
     :pairs-considered pairs-considered}))

(defn delegate-axiom!
  "Production Axiom/TBox seam: `:delegate` Axiom/TBox (candidate axioms → S07)."
  [ctx {:keys [ontology-id candidate-axioms model-spec model]}]
  (let [sub-id (axiom/register-axiom-tbox-subbehavior! ctx {:model model})
        r (delegate-subbehavior!
           ctx {:central-name "ontology-central/axiom-tbox@v1"
                :target-sheet-id sub-id
                :timeout-ms default-llm-delegate-timeout-ms  ;; MS-5 — not the flat 180s
                :bb-schema {:ontology-id :any
                            :candidate-axioms [:map {:closed false}]
                            :model-spec [:maybe [:map {:closed false}]]
                            axiom/axiom-report-key axiom/axiom-report-schema}
                :reads [:ontology-id :candidate-axioms :model-spec]
                :writes [axiom/axiom-report-key]
                :inputs {"ontology-id" ontology-id
                         "candidate-axioms" (or candidate-axioms {:axioms []})
                         "model-spec" model-spec}})]
    {:status (:status r)
     :axiom-report (get-in r [:outputs axiom/axiom-report-key])
     :tick-id (:tick-id r)
     :error (:error r)}))

(defn delegate-embed!
  "Production Embed+Index seam: `:delegate` Embed+Index (GUARANTEED P2)."
  [ctx {:keys [ontology-id embed-fields model]}]
  (let [sub-id (embed/register-embed-index-subbehavior! ctx {:model model})
        r (delegate-subbehavior!
           ctx {:central-name "ontology-central/embed-index@v1"
                :target-sheet-id sub-id
                :timeout-ms default-llm-delegate-timeout-ms  ;; MS-5 — not the flat 180s
                :bb-schema {:ontology-id :any
                            :embed-fields [:maybe [:vector :string]]
                            embed/embed-index-report-key embed/embed-index-report-schema}
                :reads [:ontology-id :embed-fields]
                :writes [embed/embed-index-report-key]
                :inputs {"ontology-id" ontology-id
                         "embed-fields" (vec (or embed-fields []))}})]
    {:status (:status r)
     :embed-index-report (get-in r [:outputs embed/embed-index-report-key])
     :tick-id (:tick-id r)
     :error (:error r)}))

(defn delegate-derive-cqs!
  "Production Validate+CQ DERIVE seam: `:delegate` Validate+CQ to DERIVE the CQs
   from goal × profile(s) and PERSIST them as the ORSD spec. The gate runs there
   with NO judge (the judge can't cross `:delegate`); the IN-PROCESS gate runs the
   judged retrieve-then-judge (`run-cq-gate!`). Returns the persisted CQs."
  [ctx {:keys [ontology-id goal profile consumer-cqs model resilient?]}]
  (let [sub-id (vcq/register-validate-cq-subbehavior! ctx {:model model :resilient? resilient?})
        r (delegate-subbehavior!
           ctx {:central-name "ontology-central/derive-cqs@v1"
                :target-sheet-id sub-id
                :timeout-ms default-llm-delegate-timeout-ms  ;; MS-5 — not the flat 180s
                :bb-schema {:ontology-id :any
                            :goal :string
                            :profile vcq/profile-read-schema
                            :consumer-cqs vcq/consumer-cqs-schema
                            :judge-fn :any
                            vcq/competency-questions-key vcq/competency-questions-schema
                            vcq/cq-verdict-key vcq/cq-verdict-schema
                            vcq/graph-health-key vcq/graph-health-schema}
                :reads [:ontology-id :goal :profile :consumer-cqs]
                :writes [vcq/competency-questions-key]
                :inputs (cond-> {"ontology-id" ontology-id
                                 "goal" goal
                                 "profile" profile}
                          (seq consumer-cqs) (assoc "consumer-cqs" (vec consumer-cqs)))})]
    {:status (:status r)
     :competency-questions (get-in r [:outputs vcq/competency-questions-key])
     :tick-id (:tick-id r)
     :error (:error r)}))

(defn delegate-synthesize-vocab!
  "GC-6 production SYNTHESIZE-VOCAB seam: `:delegate` the synthesize-vocab
   subbehavior to DISCOVER the ONE shared canonical entity-type vocabulary from the
   goal × the FULL per-source `profiles` vector (the same aggregation precedent as
   `delegate-derive-cqs!`). Returns the discovered `:vocabulary` map (read back off
   the parent tick blackboard, discipline 7). A clone of `delegate-derive-cqs!` — no
   fork; the synthesis subbehavior is just another `:delegate`d sheet."
  [ctx {:keys [goal profile model]}]
  (let [sub-id (synth/register-synthesize-vocab-subbehavior! ctx {:model model})
        r (delegate-subbehavior!
           ctx {:central-name "ontology-central/synthesize-vocab@v1"
                :target-sheet-id sub-id
                :timeout-ms default-llm-delegate-timeout-ms  ;; MS-5 — not the flat 180s
                :bb-schema {:goal :string
                            :profile vcq/profile-read-schema
                            synth/vocabulary-key synth/vocabulary-schema}
                :reads [:goal :profile]
                :writes [synth/vocabulary-key]
                :inputs {"goal" goal
                         "profile" profile}})]
    {:status (:status r)
     ;; GC-6 robustness — NORMALIZE at the threading boundary so the Model always
     ;; receives a clean vocabulary even when the :llm-node C1 parse arrives as an
     ;; EDN string / double-nested (else a malformed vocab would silently drop the
     ;; constraint and re-fragment — the exact bug GC-6 closes).
     :vocabulary (synth/normalize-vocabulary (get-in r [:outputs synth/vocabulary-key]))
     :tick-id (:tick-id r)
     :error (:error r)}))

;; =============================================================================
;; MT-2 — the SELECT-CONTAINERS seam: structural pre-filter (MT-1, deterministic) →
;; LLM relevance RANK (a delegated :llm sheet, mirror synthesize-vocab) → bounded
;; take, producing the `:selected-containers` list the Extract orchestrator consumes.
;; =============================================================================

(def select-rank-subbehavior-name
  "Canonical registry name for the MT-2 container relevance-RANK subbehavior. Like
   synthesize-vocab it bakes in NO source path — it ranks the containers it is handed
   by relevance to the GOAL read at runtime, so a SINGLE sheet serves every source."
  "ontology-select-rank/rank@v1")

(defn select-rank-sheet-id-for []
  (dsl/sheet-id-for-name select-rank-subbehavior-name))

(def selected-container-names-key
  "The rank subbehavior's OUTPUT: the container NAMES ordered most-relevant-first.
   Relevance ONLY — the model reorders the names it is GIVEN; it never invents or
   renames a container (the central seam reconciles against the known names)."
  :selected-container-names)

(def selected-container-names-schema
  "C1 — the CONCRETE `[:vector :string]` schema for the `:selected-container-names`
   write (the same load-bearing per-field-type fix competency-questions uses, so the
   `:llm` executor parses the field into real Clojure data, not raw EDN/JSON text).
   MT-12 — SUPERSEDED as the ranker's output by `container-coverage-schema` (the
   coverage MAP); kept for the SLICE-1 back-compat flat-name path + old-caller docs."
  [:vector :string])

(def container-coverage-key
  "MT-12 — the coverage-aware ranker's OUTPUT key: the container COVERAGE MAP (a
   vector of `{:name … :serves-cqs [<idx> …] :relevance …}` entries, vector ORDER =
   relevance ranking, most-relevant FIRST). Replaces the flat `selected-container-
   names-key` so the ranker reports, per container, WHICH numbered competency
   questions it helps answer — the signal SLICE-1 `select-containers` promotes on."
  :container-coverage)

(def container-coverage-schema
  "MT-12 — the coverage-aware ranker output schema. A CONCRETE `[:vector [:map …]]`
   with CONCRETE leaf types + `:description`s + a STRING `:enum` (the MT-11 C1 lesson,
   VERBATIM — a bare `:any`/keyword leaf would render \"any value\" and the `:llm`
   executor would hand back a raw string instead of parsed data). Vector ORDER =
   relevance ranking (most-relevant FIRST); each entry names the container + the
   0-based indices of the numbered competency-questions it helps ANSWER.
   Domain-agnostic (#12): names + indices are runtime-discovered — CODE names none."
  [:vector [:map {:closed false}
            [:name [:string {:description "the container's EXACT :name, copied verbatim from the candidates"}]]
            [:serves-cqs [:vector {:description "0-based indices of the numbered competency-questions this container helps ANSWER (may be empty)"}
                          :int]]
            [:relevance {:optional true}
             [:enum {:description "overall relevance of this container to the goal"} "high" "medium" "low"]]]])

(def rank-candidates-schema
  "The rank node's READ input — a vector of container SUMMARIES (name + structural
   shape + columns + approx-row-count). The model sees the REAL column headers to
   judge relevance, but CODE names none (#12) — every summary is discovered from the
   source at runtime. `{:closed false}` + `:any` leaves tolerate per-medium shape."
  [:vector [:map {:closed false}
            [:name {:optional true} :any]
            [:shape {:optional true} :any]
            [:columns {:optional true} [:vector :any]]
            [:approx-row-count {:optional true} :any]]])

(defn select-rank-prompt
  "MT-12 — the COVERAGE-AWARE relevance-rank node prompt: given the GOAL, a NUMBERED
   list of COMPETENCY QUESTIONS the built ontology must be able to answer, and the
   container SUMMARIES, for EACH container decide its overall relevance + WHICH of the
   numbered competency questions it helps ANSWER (by 0-based index), and order the
   containers most-relevant-FIRST. `:reasoning` FIRST (#13). RELEVANCE + COVERAGE only —
   use the EXACT names given, never rename / invent / merge a container.

   `cq-list` — the CQ strings, rendered into a numbered list here at sheet-build so the
   0-based indices are UNAMBIGUOUS (the same list is ALSO a runtime `:competency-
   questions` read; the SLICE-0 prototype proved rendering + reading both parses).
   nil/empty `cq-list` → NO numbered questions → pure relevance ranking with empty
   `:serves-cqs` (back-compat: SLICE-1 then no-ops promotion → today's take-cap).

   Domain-agnostic (#12): the goal + CQs + summaries are runtime data; no vertical
   entity / column / table name is baked in."
  ([] (select-rank-prompt nil))
  ([cq-list]
   (let [cqs (vec cq-list)
         numbered (if (seq cqs)
                    (str/join "\n" (map-indexed (fn [i q] (str "  " i ". " q)) cqs))
                    "  (none provided — rank on relevance alone; leave :serves-cqs empty)")]
     (str
      "*** HOW THIS NODE WORKS (read carefully) ***\n"
      "You are a single REASONING step. You are GIVEN three inputs as context: the GOAL "
      "(`goal`, provided at runtime), a NUMBERED list of COMPETENCY QUESTIONS the built "
      "ontology must be able to answer (`competency-questions`, provided at runtime and "
      "reproduced below), and a list of container SUMMARIES (`candidates`, one per "
      "structurally-meaningful container that survived the deterministic pre-filter). "
      "Each summary carries the container's `:name` (its EXACT identifier), its "
      "structural `:shape`, its `:columns` (the real column headers), and its "
      "`:approx-row-count`. You do NOT call tools, you do NOT explore the source, and "
      "you do NOT emit a behavior tree — you THINK over the goal + questions + summaries "
      "and PRODUCE the structured output below. Ignore any general guidance about tool "
      "sessions or `emit-tree!`.\n\n"
      "*** THE NUMBERED COMPETENCY QUESTIONS (0-based) ***\n"
      numbered
      "\n\n*** YOUR JOB — RANK by RELEVANCE + map each container to the questions it serves ***\n"
      "Order the containers MOST-RELEVANT-FIRST (judge relevance from each container's "
      "columns + shape + name against what the goal is about — central ranks high, "
      "peripheral/off-topic ranks low). For EACH container, ALSO decide WHICH of the "
      "numbered competency questions it helps ANSWER — by their 0-based indices. Judge "
      "coverage from the container's columns + shape + name: a container may serve zero, "
      "one, or several questions. You MAY omit a container you judge clearly irrelevant "
      "(it will simply be considered last).\n"
      "CRITICAL: this is a RELEVANCE + COVERAGE judgment ONLY. Use the EXACT `:name` "
      "strings you were given — do NOT rename a container, do NOT invent a name, do NOT "
      "merge or split containers. Use each name verbatim exactly as it appears in the "
      "summaries.\n\n"
      "*** YOUR OUTPUT — produce these fields, REASONING FIRST (#13) ***\n"
      "  1. `reasoning` — FIRST, before anything else: think through, per question, "
      "which containers can answer it, and which containers the goal most needs and why "
      "(reading their columns/shape/name). Chain-of-thought BEFORE the ordering.\n"
      "  2. `container-coverage` — a VECTOR of {:name <exact> :serves-cqs [<int idx> …] "
      ":relevance \"high\"|\"medium\"|\"low\"}, ordered MOST-RELEVANT-FIRST. Use the "
      "exact `:name` from the summaries; `:serves-cqs` is the 0-based indices of the "
      "numbered questions above (may be empty). Emit REAL structured Clojure data (a "
      "vector of maps), NOT a JSON string and NOT prose."))))

(defn select-rank-subbehavior-def
  "MT-12 — the COVERAGE-AWARE relevance-rank subbehavior workflow definition — a
   single `:llm` node (single-turn reasoning over goal + numbered CQs + candidate
   summaries; clone of the synthesize-vocab / derive-cqs node shape). NOT a
   `:repl-researcher`.

   `competency-questions` — the runtime CQ set, rendered into the prompt's numbered
   list at sheet-build (unambiguous 0-based indices) AND declared as a runtime
   `:competency-questions` read (the SLICE-0 prototype proved both). nil/empty →
   pure relevance ranking with empty `:serves-cqs` (back-compat).

   Contract (public `:reads`/`:writes`):
     :reads  [:goal :competency-questions :candidates]
     :writes [:reasoning :container-coverage]           (#13 reasoning FIRST)"
  [{:keys [model competency-questions]}]
  (let [nm select-rank-subbehavior-name
        mdl (or model "google/gemini-3-flash-preview")]
    (dsl/workflow nm
      (dsl/blackboard
       {:goal :string
        :competency-questions [:vector :string]
        :candidates rank-candidates-schema
        :reasoning :string
        container-coverage-key container-coverage-schema})
      (dsl/sequence "select-rank-root"
        (dsl/llm "rank"
          :model mdl
          :instruction (select-rank-prompt competency-questions)
          :reads [:goal :competency-questions :candidates]
          ;; #13 — :reasoning FIRST (chain-of-thought before the coverage map).
          :writes [:reasoning container-coverage-key])))))

(defn register-select-rank-subbehavior!
  "REGISTER (build, idempotent) the relevance-rank subbehavior sheet and return its
   deterministic sheet-id. `competency-questions` (MT-12) is threaded into the def so
   the numbered CQ list renders into the prompt; nil/empty → the pure-relevance sheet.
   Re-registering an unchanged def is a no-op (same id); a changed CQ set rebuilds the
   sheet in place (build-workflow! is idempotent + serialized per sheet-id)."
  [ctx {:keys [model competency-questions]}]
  (dsl/build-workflow! ctx (select-rank-subbehavior-def
                            {:model model :competency-questions competency-questions})))

(defn delegate-select-containers!
  "MT-2 production SELECT-CONTAINERS seam: turn a source's containers into the
   SELECTED, ranked, bounded `:selected-containers` list the Extract orchestrator
   consumes. Runs the pure pipeline (`csel/classify-source-containers` →
   `csel/select-containers`) with the delegated `:llm` relevance rank as `rank-fn`.

   - Single-container (or empty) source → NO selection (`:selected-containers` nil),
     so the Extract orchestrator keeps today's take-cap path unchanged (the csv N=1
     path stays green — backward compat).
   - The structural pre-filter drops bridge/reference noise DETERMINISTICALLY (MT-1);
     the survivors are RANKED by the delegated `:llm` sheet, RECONCILED against the
     known survivor names (invented names ignored, omitted survivors appended — never
     a silent drop), and bounded by `cap`.
   - If the rank `:delegate` FAILS, degrade HONESTLY to the structural survivors in
     list order with the reason SURFACED in `:selection-report` (NOT a silent swallow
     — #5). Any hard failure (no contract, unreadable source) likewise degrades to
     NO selection with a surfaced reason (take-cap fallback), never a throw.

   MT-12 — `competency-questions` (the runtime CQ set) is threaded into the ranker
   sheet (the numbered list + a `:competency-questions` read), the rank returns the
   coverage MAP (`:container-coverage`), and `:cqs` is passed to SLICE-1
   `select-containers` so an under-served CQ can PROMOTE its serving container above
   the flat take-cap. nil/empty CQs → empty `:serves-cqs` → no promotion (back-compat).

   Returns `{:selected-containers [<container+shape+roles> …]|nil :selection-report …}`.
   `list-fn`/`sample-fn` default to the uniform container contract; tests inject fakes."
  [ctx {:keys [source goal model max-containers list-fn sample-fn competency-questions]}]
  (try
    (let [list-fn (or list-fn csel/default-list-fn)
          all (vec (list-fn source))
          cap (or max-containers extract/default-max-containers)
          ;; MT-12 — normalize the runtime CQ set to a vector ONCE (nil → []), so the
          ;; sheet input satisfies the `[:vector :string]` bb-schema and SLICE-1
          ;; `select-containers` sees a concrete `:cqs` (empty → no-op promotion).
          cqs (vec competency-questions)]
      (if (<= (count all) 1)
        {:selected-containers nil
         :selection-report {:containers-total (count all)
                            :reason (str "single-container (or empty) source — no "
                                         "selection needed; Extract uses take-cap")}}
        (let [candidates (csel/classify-source-containers
                          source (cond-> {:list-fn (constantly all)}
                                   sample-fn (assoc :sample-fn sample-fn)))
              rank-degrade (atom nil)
              rank-fn
              (fn [g survivors]
                (try
                  (let [summaries (mapv (fn [c] {:name (:name c)
                                                 :shape (:shape c)
                                                 :columns (vec (:header c))
                                                 :approx-row-count (:row-count c)})
                                        survivors)
                        sub-id (register-select-rank-subbehavior!
                                ctx {:model model :competency-questions cqs})
                        r (delegate-subbehavior!
                           ctx {:central-name "ontology-central/select-rank@v1"
                                :target-sheet-id sub-id
                                :timeout-ms default-llm-delegate-timeout-ms  ;; MS-5 — not the flat 180s
                                :bb-schema {:goal :string
                                            :competency-questions [:vector :string]
                                            :candidates rank-candidates-schema
                                            :reasoning :string
                                            container-coverage-key
                                            container-coverage-schema}
                                :reads [:goal :competency-questions :candidates]
                                :writes [container-coverage-key]
                                :inputs {"goal" (or g "")
                                         "competency-questions" cqs
                                         "candidates" summaries}})
                        ;; MT-12 — the coverage MAP is a `[:vector [:map …]]` write that
                        ;; crosses `:delegate`, so (like the model-spec, MT-10) it
                        ;; INTERMITTENTLY arrives as an unparsed STRING (measured ~1/6).
                        ;; Coerce the read-back with the SAME generic string→vector-of-maps
                        ;; coercion (EDN → JSON → hybrid → []; keywordizes keys) so
                        ;; select-containers always sees a real vector, never char-iterates
                        ;; a degraded string. A genuinely-unparseable string → [] (honest
                        ;; degrade → list order), never garbage.
                        ;; MT-12 — the coverage MAP is a `[:vector [:map …]]` write that
                        ;; crosses `:delegate`, so (like model-spec, MT-10) it intermittently
                        ;; degrades in TWO C1-family ways (both measured live): (1) it arrives
                        ;; as an unparsed STRING; (2) it arrives as a vector whose entry keys
                        ;; are NAMESPACED (`::name` instead of `:name`), so plain-key reads
                        ;; return nil. Coerce the string (EDN → JSON → hybrid → []) AND
                        ;; normalize every entry key to a PLAIN keyword (strip any namespace),
                        ;; so select-containers always reads `:name`/`:serves-cqs`. A
                        ;; genuinely-unparseable value → [] (honest degrade → list order).
                        ;; CONNECT-1 — the C1 crossing ALSO mangles keys to
                        ;; `(keyword ":name")` (prints `::name`), which the old
                        ;; `(keyword (name k))` did NOT repair (name is still
                        ;; ":name") → `(:name entry)` nil → ranking DROPPED →
                        ;; alphabetical extract → disconnected graph. Reuse the ONE
                        ;; shared leading-colon-stripping normalizer (no fork).
                        plain-keys (fn [m]
                                     (if (map? m)
                                       (into {} (map (fn [[k v]]
                                                       [(if (or (keyword? k) (string? k))
                                                          (vb/normalize-entry-key k) k)
                                                        v]))
                                             m)
                                       m))
                        coverage (mapv plain-keys
                                       (vb/coerce-entity-types
                                        (get-in r [:outputs container-coverage-key])))]
                    (if (= :success (:status r))
                      ;; the COVERAGE MAP (vector of {:name :serves-cqs …}); SLICE-1
                      ;; select-containers coerces + reconciles it. nil-on-success →
                      ;; select-containers falls back to survivor list order.
                      coverage
                      ;; honest degrade — surface the reason, return nil so
                      ;; select-containers falls back to survivor list order (#5).
                      (do (reset! rank-degrade (or (:error r) :rank-delegate-failed))
                          nil)))
                  (catch Throwable t
                    (reset! rank-degrade (.getMessage t))
                    nil)))
              result (csel/select-containers candidates
                                             {:goal goal :cqs cqs :cap cap
                                              :rank-fn rank-fn})]
          {:selected-containers (:selected result)
           :selection-report (assoc (:report result)
                                    :rank-degraded (boolean @rank-degrade)
                                    :rank-degrade-reason @rank-degrade)})))
    (catch Throwable t
      {:selected-containers nil
       :selection-report {:reason (str "container selection failed; Extract falls back "
                                       "to take-cap (honest degrade — #5)")
                          :error (.getMessage t)}})))

;; =============================================================================
;; The CQ gate — run IN-PROCESS with the judge capability (the judge fn cannot
;; cross :delegate). REUSE the EB8 subbehavior's `run-gate!` (S15 evaluate-cqs!).
;; =============================================================================

(defn run-cq-gate!
  "Run the S15 CQ gate IN-PROCESS with the judge capability. REUSES the EB8
   subbehavior's `run-gate!` (which reuses `evaluate-cqs!` — the S15 three-layer
   retrieve-then-judge runner) — NO fork. The judge-fn is a Clojure FN VALUE that
   cannot cross the `:delegate` blackboard, so the gate runs in-process here.

   Returns `{:cq-verdict [...] :graph-health <map> :evaluated <vector>}`. The
   verdict is read back off the projection (discipline 7, inside `run-gate!`).
   `:evaluated` is the per-CQ `{:cq-text :verdict …}` shape `failing-cq-verdicts`
   reads (re-housed from the raw verdict so the DT8 unanswerable read still works)."
  [ctx {:keys [ontology-id judge-fn]}]
  (let [gate (vcq/run-gate! ctx {:ontology-id ontology-id :judge-fn judge-fn})
        verdict (vec (:cq-verdict gate))
        evaluated (mapv (fn [v]
                          {:cq-text (or (:cq-text v) (:cq-index v))
                           :verdict (:verdict v)
                           :reasoning (:reasoning v)
                           :gaps (:gaps v)})
                        verdict)]
    {:cq-verdict verdict
     :graph-health (:graph-health gate)
     :evaluated evaluated
     :run-reason (:run-reason gate)}))

(defn gate-passed?
  "Apply build!'s exit-criterion to a graph-health metric — the SAME gate
   `build!`/the deterministic skeleton uses (REUSE `skeleton/default-exit-criterion`:
   pass-rate ≥ 0.8 AND unknown-rate ≤ 0.3). Domain-agnostic, no fork."
  [graph-health exit-criterion]
  (let [{:keys [pass-rate-min unknown-rate-max]}
        (merge skeleton/default-exit-criterion exit-criterion)
        pass-rate (or (:pass-rate graph-health) 0.0)
        unk-rate (or (:unknown-rate graph-health) 0.0)]
    (and (>= pass-rate pass-rate-min) (<= unk-rate unknown-rate-max))))

;; =============================================================================
;; The ROUTE seam — map a failing CQ + graph-health → the closing subbehavior
;; (production: the :llm/decision sheet; tests stub a deterministic mapping)
;; =============================================================================

(defn route-decision!
  "Production ROUTE seam: invoke the ROUTE `:llm`/decision sheet for ONE failing CQ
   + the graph-health, returning the routed subbehavior keyword (one of
   `routable-subbehaviors`). `:reasoning` is written FIRST on the node (#13). The
   decision is the model's reasoning over the CQ + graph-health (NOT a phrase table,
   #7/#12). The node is invoked DIRECTLY (not via `:delegate`) because a route
   decision needs no isolated child blackboard — it is a single reasoning step on
   the central tree's own scope."
  [ctx {:keys [route-sheet-id failing-cq graph-health]}]
  (let [tick-id (random-uuid)
        result (dsl/execute ctx route-sheet-id
                                {"failing-cq" failing-cq
                                 "graph-health" graph-health}
                                :timeout-ms 120000 :tick-id tick-id)
        parent-bb (dsl/get-tick-blackboard ctx tick-id)
        route (get-in parent-bb [:route :value])
        route-kw (cond
                   (keyword? route) route
                   (string? route) (keyword (str/replace route #"^:" ""))
                   :else :terminate)]
    {:status (:status result)
     :route (if (contains? routable-subbehaviors route-kw) route-kw :terminate)
     :reasoning (get-in parent-bb [:reasoning :value])
     :tick-id tick-id}))

;; =============================================================================
;; The CENTRAL EVOLVER LOOP — re-house DT8's cq-driven loop; route to subbehaviors
;; =============================================================================

(def default-evolver-config
  "EB10 adaptive-loop budget + gate knobs (re-housed from `dt/default-cq-loop-config`).
   `:max-iterations` is the HARD bound on focused route-and-close iterations after
   the initial build (so the loop ALWAYS terminates regardless of model behavior)."
  {:max-iterations 3})

(def reconcile-error-max-chars
  "MS-4 — the bound on a reconcile error string carried in a source-report /
   focal-close return. The FULL error lives in the node-execution events; the
   report carries a readable prefix so forensics never require decoding those
   giant payloads (the live accretion series' reconcile :failure was
   unreadable — a targeted 8g es/read OOMed on the fressian decode of the
   node event that held the error). Named, never a magic literal."
  500)

(defn- bounded-error-str
  "MS-4 — the reconcile error as a BOUNDED string: a string error is kept
   verbatim (truncated to `reconcile-error-max-chars`); a non-string error
   (anomaly map, ex-data) travels as its pr-str prefix; nil stays nil."
  [error]
  (when (some? error)
    (let [s (if (string? error) error (pr-str error))]
      (subs s 0 (min (count s) reconcile-error-max-chars)))))

(defn- focal-close!
  "Re-invoke the routed CLOSING subbehavior FOCALLY (NOT a full rebuild) for a
   failing CQ. Maps the ROUTE keyword to the subbehavior seam:
     :extract / :model → re-run the per-source Model→Extract pipeline (re-decide /
                         re-author the extraction), re-land via Reconcile.
     :reconcile        → re-link the current graph (Reconcile over the existing
                         landed drafts — no new extraction).
     :axiom            → re-emit TBox axioms (Axiom/TBox) from the held candidates.
     :terminate        → no close (handled by the caller — the source lacks it).
   Returns `{:status :ok/:failed :closed <route> …}`; honest on failure (#5)."
  [ctx {:keys [route ontology-id source goal profile vocabulary model resilient?
               pipeline-sheet-id source-uri-sets held-candidate-axioms held-model-spec
               held-embed-fields max-containers max-windows
               seams]}]
  (let [{:keys [model-extract-fn reconcile-fn axiom-fn embed-fn]} seams]
    (case route
      (:extract :model)
      ;; re-run the per-source pipeline (re-model + re-extract) → re-land + re-embed.
      ;; GC-6 — the re-model/re-extract obeys the SAME shared vocabulary.
      ;; GM-1 — the focal re-model sees the CURRENT (now fully-built) graph in context,
      ;; so a re-model attaches to the existing entities instead of re-minting them.
      (let [graph-context (gcs/graph-context-snapshot ctx ontology-id)
            mx ((or model-extract-fn delegate-model-extract!)
                ctx {:source source :goal goal :profile profile :vocabulary vocabulary
                     :graph-context graph-context
                     ;; GC-9 — the focal re-extract obeys the SAME reduced caps.
                     :max-containers max-containers :max-windows max-windows
                     :pipeline-sheet-id pipeline-sheet-id :model model :resilient? resilient?})]
        (if (not= :success (:status mx))
          {:status :failed :closed route :error (:error mx) :stage :model-extract}
          (let [rc ((or reconcile-fn delegate-reconcile!)
                    ctx {:ontology-id ontology-id
                         :concept-drafts (:concept-drafts mx)
                         :relationship-drafts (:relationship-drafts mx)
                         :source-uri-sets source-uri-sets :model model})
                _ ((or embed-fn delegate-embed!)
                   ctx {:ontology-id ontology-id :embed-fields (:embed-fields mx) :model model})]
            (cond-> {:status :ok :closed route
                     :concept-drafts (:concept-drafts mx)
                     ;; MS-2 — THREAD the reconcile's status (previously `rc` was read
                     ;; only for :reconcile-report — a timed-out focal reconcile vanished
                     ;; into :status :ok). Never drop a non-:success.
                     :reconcile-status (:status rc)
                     :reconcile-report (:reconcile-report rc)}
              ;; MS-4 — the reconcile ERROR travels too (bounded) — the loop
              ;; history entry is forensically readable without the giant
              ;; node-execution events.
              (some? (:error rc))
              (assoc :reconcile-error (bounded-error-str (:error rc)))))))

      :reconcile
      ;; re-link the CURRENT graph (no new extraction — connect what is already
      ;; landed). Reconcile with empty drafts re-runs the entity/attr reconcile.
      (let [rc ((or reconcile-fn delegate-reconcile!)
                ctx {:ontology-id ontology-id
                     :concept-drafts [] :relationship-drafts []
                     :source-uri-sets source-uri-sets :model model})]
        (cond-> {:status (if (= :success (:status rc)) :ok :failed)
                 :closed route
                 ;; MS-2 — the same :reconcile-status key as the :extract/:model route
                 ;; (uniform read across routes).
                 :reconcile-status (:status rc)
                 :reconcile-report (:reconcile-report rc) :error (:error rc)}
          ;; MS-4 — the same :reconcile-error key as the :extract/:model route
          ;; (uniform, bounded).
          (some? (:error rc))
          (assoc :reconcile-error (bounded-error-str (:error rc)))))

      :axiom
      ;; re-emit TBox axioms from the held candidate-axioms (no new extraction).
      (let [ax ((or axiom-fn delegate-axiom!)
                ctx {:ontology-id ontology-id
                     :candidate-axioms held-candidate-axioms
                     :model-spec held-model-spec :model model})]
        {:status (if (= :success (:status ax)) :ok :failed)
         :closed route :axiom-report (:axiom-report ax) :error (:error ax)})

      ;; :terminate or unknown — no close attempted.
      {:status :ok :closed :terminate})))

(defn cq-objective-loop!
  "EB10 — the central evolver's CQ-objective loop. RE-HOUSES DT8's `cq-driven-loop!`
   SHAPE, but each focused close re-invokes the ROUTED SUBBEHAVIOR (not the inline
   transform node). The CQ-gate is the loop OBJECTIVE: a failing CQ ROUTES (the
   adaptive :llm/decision node, `:reasoning` first) to the subbehavior that closes
   it, re-invoked FOCALLY, re-gated → pass; a genuinely-unanswerable / :terminate-
   routed CQ terminates HONESTLY (no spin, no false-green); budget-bounded.

   Each iteration:
     1. read the per-CQ verdicts (the IN-PROCESS S15 gate);
     2. for each STILL-FAILING, not-yet-unanswerable CQ, ROUTE (the :llm/decision
        node) → the closing subbehavior (or :terminate);
     3. re-invoke the routed subbehavior FOCALLY (`focal-close!`);
     4. re-GATE in-process; branch:
          gate now passes        → DONE (:complete);
          route said :terminate, OR the close grew the graph by NOTHING toward the
            CQ                    → that CQ is UNANSWERABLE → surface + stop chasing;
          else loop (budget permitting).

   ALWAYS terminates: stops on a pass, on all-remaining-unanswerable, or on budget
   exhaustion — and ALWAYS surfaces a `:termination-reason` ∈ {:cq-gate-passed
   :all-remaining-unanswerable :budget-exhausted}. NEVER spins; NEVER false-greens
   (an unanswerable/budget termination is `:status :failed-cq` carrying the reason).

   Injected seams (production defaults; tests stub them deterministically):
     :gate-fn   — (fn [ctx {:ontology-id :judge-fn}] {:cq-verdict :graph-health
                  :evaluated}). Default `run-cq-gate!` (in-process S15 + judge).
     :route-fn  — (fn [ctx {:route-sheet-id :failing-cq :graph-health}] {:route kw}).
                  Default `route-decision!` (the :llm/decision node).
     :model-extract-fn / :reconcile-fn / :axiom-fn / :embed-fn — the focal-close
                  subbehavior seams. Defaults delegate to the real subbehaviors."
  [ctx {:keys [ontology-id source goal profile vocabulary judge-fn exit-criterion model
               resilient? evolver-config pipeline-sheet-id route-sheet-id
               source-uri-sets held-candidate-axioms held-model-spec held-embed-fields
               max-containers max-windows
               gate-fn route-fn model-extract-fn reconcile-fn axiom-fn embed-fn]}]
  (let [{:keys [max-iterations]} (merge default-evolver-config evolver-config)
        gate-fn (or gate-fn run-cq-gate!)
        route-fn (or route-fn route-decision!)
        seams {:model-extract-fn model-extract-fn :reconcile-fn reconcile-fn
               :axiom-fn axiom-fn :embed-fn embed-fn}
        graph-size (fn [] (let [cs (count (rm/get-concepts ctx {:ontology-id ontology-id}))
                                rs (count (filterv #(= ontology-id (:ontology-id %))
                                                   (rm/get-relationships ctx)))]
                            (+ cs rs)))
        ;; the initial gate over the freshly-built graph.
        initial (gate-fn ctx {:ontology-id ontology-id :judge-fn judge-fn})]
    (loop [iteration 0
           gate initial
           history []
           unanswerable #{}]
      (let [passed? (gate-passed? (:graph-health gate) exit-criterion)
            gaps (dt/failing-cq-verdicts (:evaluated gate))
            targetable (filterv #(not (contains? unanswerable (:cq-text %))) gaps)]
        (cond
          ;; the gate passed — DONE (the OBJECTIVE is met).
          passed?
          {:status :complete
           :ontology-id ontology-id
           :graph-health (:graph-health gate)
           :cq-verdict (:cq-verdict gate)
           :cq-loop {:iterations iteration
                     :termination-reason :cq-gate-passed
                     :unanswerable-cqs (vec unanswerable)
                     :history history}}

          ;; budget exhausted — terminate honestly with the still-failing verdict.
          (>= iteration max-iterations)
          {:status :failed-cq
           :ontology-id ontology-id
           :graph-health (:graph-health gate)
           :cq-verdict (:cq-verdict gate)
           :cq-loop {:iterations iteration
                     :termination-reason :budget-exhausted
                     :unanswerable-cqs (vec unanswerable)
                     :history history}}

          ;; every still-failing CQ is already known-unanswerable — terminate
          ;; honestly rather than re-routing for data the sources lack.
          (empty? targetable)
          {:status :failed-cq
           :ontology-id ontology-id
           :graph-health (:graph-health gate)
           :cq-verdict (:cq-verdict gate)
           :cq-loop {:iterations iteration
                     :termination-reason :all-remaining-unanswerable
                     :unanswerable-cqs (vec unanswerable)
                     :history history}}

          :else
          ;; :failed-cq with targetable gaps — ROUTE the first, close it focally,
          ;; re-gate. One CQ per iteration (the most-actionable gap), budget-bounded.
          (let [failing (first targetable)
                cq-text (:cq-text failing)
                before (graph-size)
                routed (route-fn ctx {:route-sheet-id route-sheet-id
                                      :failing-cq (str cq-text)
                                      :graph-health (:graph-health gate)})
                route (:route routed)]
            (if (= :terminate route)
              ;; the ROUTE judged the source genuinely lacks the data — UNANSWERABLE.
              (let [unanswerable' (conj unanswerable cq-text)
                    entry {:iteration (inc iteration)
                           :failing-cq cq-text
                           :route :terminate
                           :route-reasoning (:reasoning routed)
                           :graph-grew? false
                           :newly-unanswerable [cq-text]}]
                (recur (inc iteration) gate (conj history entry) unanswerable'))
              ;; a closeable route — re-invoke the subbehavior FOCALLY, re-gate.
              (let [close (focal-close!
                           ctx {:route route :ontology-id ontology-id :source source
                                :goal goal :profile profile :vocabulary vocabulary :model model
                                :resilient? resilient? :pipeline-sheet-id pipeline-sheet-id
                                ;; GC-9 — the focal re-extract obeys the SAME reduced caps.
                                :max-containers max-containers :max-windows max-windows
                                :source-uri-sets source-uri-sets
                                :held-candidate-axioms held-candidate-axioms
                                :held-model-spec held-model-spec
                                :held-embed-fields held-embed-fields
                                :seams seams})
                    after (graph-size)
                    graph-grew? (> after before)
                    ;; UNANSWERABLE detection (honest negative): a focused close
                    ;; aimed at THIS CQ supplied NO new graph data → the source
                    ;; genuinely lacks what it needs. Mark it unanswerable so the
                    ;; loop stops chasing it.
                    newly-unanswerable (if (and (= :ok (:status close)) graph-grew?)
                                         #{} #{cq-text})
                    unanswerable' (into unanswerable newly-unanswerable)
                    next-gate (if graph-grew?
                                (gate-fn ctx {:ontology-id ontology-id :judge-fn judge-fn})
                                gate)
                    entry {:iteration (inc iteration)
                           :failing-cq cq-text
                           :route route
                           :route-reasoning (:reasoning routed)
                           :close-status (:status close)
                           :graph-grew? graph-grew?
                           :before before :after after
                           :newly-unanswerable (vec newly-unanswerable)}]
                (recur (inc iteration) next-gate (conj history entry) unanswerable')))))))))

;; =============================================================================
;; run-evolver-pipeline! — the SHARED per-source subbehavior pipeline (survey →
;; derive CQs → model→extract → reconcile → axiom → embed → build → CQ loop).
;; BOTH the greenfield AND the maintain arm run THIS — the only difference is the
;; recorded branch decision + the `:mode` tag, because the subbehaviors already
;; READ CURRENT GRAPH STATE (EB5 reconcile-drafts! reads pre-existing-uris before
;; landing; greenfield's empty graph and maintain's populated graph are the same
;; code path against a different starting projection). EB11 flips the maintain arm
;; from `dt/maintain-deferred-stub` to THIS shared pipeline — RE-ORCHESTRATION,
;; not a fork (discipline 8): no subbehavior or loop machinery is duplicated.
;; =============================================================================

(defn source-report
  "MS-2 — the PER-SOURCE outcome report (pure). Built from a per-source pipeline
   entry (the model-extract return `mx` augmented with the CAPTURED
   `:reconcile-result` / `:axiom-result` / `:embed-result` seam returns — which
   were previously fire-and-forget: the 2026-07-11 all-5-sources forensic showed
   4 sources extract ~230k drafts and land ZERO concepts because their reconcile
   delegates timed out SILENTLY). A seam that never ran (its model-extract failed,
   or the pipeline aborted upstream) reports `:not-run` — never a fabricated
   status. `:landed` is read from the reconcile subbehavior's PUBLIC report shape
   (`:reconcile-report :landed :concepts-emitted` — `reconcile_subbehavior.clj`
   → `rlm_discovery.clj` discovery-provenance); nil when the reconcile failed or
   the report doesn't carry it (nil, not a fabricated 0)."
  [{:keys [source concept-drafts relationship-drafts
           reconcile-result axiom-result embed-result]}]
  {:source (select-keys source [:type :path])
   :extracted {:concepts (count concept-drafts)
               :relationships (count relationship-drafts)}
   ;; MS-4 — a non-:success reconcile's ERROR travels IN the report (bounded):
   ;; without it, the only copy lives in a giant node-execution event that a
   ;; forensic es/read cannot cheaply decode. Absent error → no :error key
   ;; (the :success shape is unchanged).
   :reconcile (cond-> {:status (or (:status reconcile-result) :not-run)
                       :landed (get-in reconcile-result
                                       [:reconcile-report :landed :concepts-emitted])}
                (some? (:error reconcile-result))
                (assoc :error (bounded-error-str (:error reconcile-result))))
   :axiom {:status (or (:status axiom-result) :not-run)}
   :embed {:status (or (:status embed-result) :not-run)}})

(defn- reconcile-not-success?
  "MS-2 — did this per-source pipeline entry's reconcile RUN and return
   non-:success? (`:not-run` — the model-extract failed so reconcile never ran —
   is NOT a reconcile failure; that source surfaces via model-extract's own
   abort semantics.)"
  [{:keys [reconcile-result]}]
  (and (some? reconcile-result)
       (not= :success (:status reconcile-result))))

(defn- run-evolver-pipeline!
  "The shared evolver pipeline (EB10 STEP 2-6) both arms run. `mode` is
   `:greenfield` | `:maintain` (recorded on the result as `:mode`); `gf-branch` is
   the DT9 decision recorded under `:branch-points`. For `:maintain`, the EXISTING
   graph is the starting projection — the per-source reconcile (EB5) reads it and
   reconciles-not-duplicates (idempotent), the new source's NEW classes/attrs land
   ALONGSIDE the existing graph, and the CQ loop re-gates the UPDATED graph. The
   pipeline body is IDENTICAL across modes because the subbehaviors are
   against-graph-state by construction (handoff §3 — the single load-bearing seam).

   Injected seams default to the production `:delegate`s; tests stub them."
  [ctx {:keys [ontology-id sources goal model resilient? judge-fn exit-criterion
               consumer-cqs evolver-config mode gf-branch source-uri-sets
               max-containers max-windows
               survey-fn derive-cqs-fn synthesize-vocab-fn graph-context-fn select-fn
               model-extract-fn reconcile-fn
               axiom-fn embed-fn build-fn gate-fn route-fn]}]
  (let [;; seam defaults (production delegate; tests stub)
        survey-fn (or survey-fn delegate-survey!)
        derive-cqs-fn (or derive-cqs-fn delegate-derive-cqs!)
        synthesize-vocab-fn (or synthesize-vocab-fn delegate-synthesize-vocab!)
        ;; MT-2 — the survey-driven container SELECT step (default: the real
        ;; classify→rank→bound seam; tests stub it). Runs per source (below), next
        ;; to graph-context, producing the `:selected-containers` the Extract consumes.
        select-fn (or select-fn delegate-select-containers!)
        ;; GM-1 — the pre-Model graph-context step (default: the real read-only
        ;; snapshot; tests stub it). Computed FRESH per source (below), so a source
        ;; processed AFTER an entity-defining source sees that entity in context.
        graph-context-fn (or graph-context-fn gcs/graph-context-snapshot)
        model-extract-fn (or model-extract-fn delegate-model-extract!)
        reconcile-fn (or reconcile-fn delegate-reconcile!)
        axiom-fn (or axiom-fn delegate-axiom!)
        embed-fn (or embed-fn delegate-embed!)
        build-fn (or build-fn skeleton/build!)
        ;; register the per-source pipeline + route sheets once (idempotent).
        {:keys [pipeline-sheet-id]}
        (register-pipeline-sheets! ctx {:model model :resilient? resilient?})
        route-sheet-id (register-route-node! ctx {:model model})
        ;; the common result envelope keys (the branch decision + the mode tag are
        ;; recorded identically across modes; only their VALUES differ).
        envelope {:ontology-id ontology-id
                  :goal goal
                  :mode mode
                  :branch-points {:greenfield-vs-maintain gf-branch}}]
    ;; --- STEP 2: SURVEY each source (:delegate) ---
    (let [surveys (mapv (fn [src] (assoc (survey-fn ctx {:source src :goal goal :model model})
                                         :source src))
                        sources)
          survey-fail (first (filter #(not= :success (:status %)) surveys))]
      (if survey-fail
        (merge envelope
               {:status :failed-at-survey
                :error (:error survey-fail)
                :failed-source (:source survey-fail)})

        (let [profiles (mapv :profile surveys)
              ;; --- STEP 3: DERIVE the CQs (:delegate) + persist ORSD ---
              derive (derive-cqs-fn ctx {:ontology-id ontology-id :goal goal
                                         :profile profiles :consumer-cqs consumer-cqs
                                         :model model :resilient? resilient?})]
          (if (not= :success (:status derive))
            (merge envelope
                   {:status :failed-at-derive-cqs
                    :survey-profiles profiles
                    :error (:error derive)})

            ;; --- STEP 3.5: SYNTHESIZE the shared DISCOVERED vocabulary (GC-6, the
            ;; keystone) — consume the SAME full `profiles` vector STEP 3 derived
            ;; the CQs from, DISCOVER one canonical entity-type + key vocabulary, and
            ;; thread it into EVERY per-source Model so the same real entity gets the
            ;; same canonical URI (→ GC-1 mints ONE → reconcile merges → connected).
            ;; Honest terminal mirroring :failed-at-derive-cqs (#5; no model-extract
            ;; runs after a synthesis failure — no false green).
            (let [synth (synthesize-vocab-fn ctx {:goal goal :profile profiles
                                                  :model model :resilient? resilient?})]
              (if (not= :success (:status synth))
                (merge envelope
                       {:status :failed-at-synthesize-vocabulary
                        :survey-profiles profiles
                        :error (:error synth)})

            (let [vocab (:vocabulary synth)
                  ;; --- STEP 4: per-source Model→Extract → land/reconcile/axiom
                  ;;             → embed (the per-source loop body) ---
                  per-source
                  (mapv
                   (fn [src profile]
                     (let [;; GM-1 — snapshot the graph built SO FAR (by the sources
                           ;; already reconciled earlier in this sequential mapv) and
                           ;; thread it into THIS source's Model, so it models new data
                           ;; AGAINST the existing entities (attach/reify, not mint per-row).
                           ;; The mapv is sequential, so an entity-defining source
                           ;; processed FIRST is already landed + in this snapshot.
                           graph-context (graph-context-fn ctx ontology-id)
                           ;; MT-2 — compute the survey-driven container SELECTION for
                           ;; THIS source (structural pre-filter → LLM relevance rank →
                           ;; bounded), next to graph-context. A single-container source
                           ;; / a failed rank degrades HONESTLY to nil selection → the
                           ;; Extract orchestrator falls back to take-cap (#5).
                           ;; MT-12 SLICE 3 — thread the STEP-3-derived competency
                           ;; questions into selection so it is CQ-COVERAGE-AWARE: the
                           ;; ranker judges which CQ each container serves, and a
                           ;; bounded promotion guarantees the selected set covers the
                           ;; goal's facets (any residual uncovered CQ is surfaced in
                           ;; the :selection-report, feeding the STEP-6 CQ-gate loop).
                           ;; nil/empty CQs → today's take-cap (back-compat).
                           sel (select-fn ctx {:source src :goal goal :model model
                                               :max-containers max-containers
                                               :competency-questions (:competency-questions derive)})
                           mx (model-extract-fn
                               ctx {:source src :goal goal :profile profile
                                    :vocabulary vocab
                                    ;; GM-1 — the pre-Model graph-context snapshot.
                                    :graph-context graph-context
                                    ;; GC-9 — thread the reduced-cap knobs into EVERY
                                    ;; per-source Model→Extract (nil → extract defaults).
                                    :max-containers max-containers
                                    :max-windows max-windows
                                    ;; MT-2 — thread the selected containers into Extract
                                    ;; (nil → the orchestrator falls back to take-cap).
                                    :selected-containers (:selected-containers sel)
                                    :pipeline-sheet-id pipeline-sheet-id
                                    :model model :resilient? resilient?})]
                       ;; MS-2 — the reconcile/axiom/embed returns are CAPTURED per
                       ;; source (they were fire-and-forget: a reconcile that TIMED
                       ;; OUT left the source "counted" with ZERO concepts landed and
                       ;; the run still claimed the CQ loop's status — the 2026-07-11
                       ;; all-5-sources silent-zero root cause). A non-:success
                       ;; reconcile does NOT abort the run (deliberately unlike
                       ;; model-extract: extraction failing means no data; reconcile
                       ;; failing means data exists but didn't land, and the
                       ;; REMAINING sources are independent — keep landing them).
                       ;; The statuses surface via :source-reports + the final
                       ;; :partial-reconcile status (below).
                       (let [ok? (= :success (:status mx))
                             ;; LAND + RECONCILE (Reconcile lands the drafts via
                             ;; compile-discovery-source! then entity/attr reconcile
                             ;; AGAINST CURRENT GRAPH STATE — the maintain seam: an
                             ;; existing entity reconciles-not-duplicates, a new
                             ;; class/attr lands alongside the existing graph).
                             rc (when ok?
                                  (reconcile-fn ctx {:ontology-id ontology-id
                                                     :concept-drafts (:concept-drafts mx)
                                                     :relationship-drafts (:relationship-drafts mx)
                                                     :source-uri-sets source-uri-sets :model model}))
                             ;; AXIOM/TBox from the held candidate-axioms (NEW classes/
                             ;; properties + how they relate to existing — TBox evolution).
                             ax (when ok?
                                  (axiom-fn ctx {:ontology-id ontology-id
                                                 :candidate-axioms (:candidate-axioms mx)
                                                 :model-spec (:model-spec mx) :model model}))
                             ;; EMBED+INDEX (guaranteed P2)
                             em (when ok?
                                  (embed-fn ctx {:ontology-id ontology-id
                                                 :embed-fields (:embed-fields mx) :model model}))]
                         ;; MT-2 — carry THIS source's selection-report (drop reasons +
                         ;; the rank-degraded flag) so the build surfaces it honestly
                         ;; (no false-green: a silently-degraded LLM rank is VISIBLE).
                         (assoc mx :source src :selection-report (:selection-report sel)
                                ;; MS-2 — the captured per-source seam returns.
                                :reconcile-result rc :axiom-result ax :embed-result em))))
                   sources profiles)
                  ;; MS-2 — the per-source outcome reports + the honest run status.
                  source-reports (mapv source-report per-source)
                  partial-reconcile? (boolean (some reconcile-not-success? per-source))
                  mx-fail (first (filter #(not= :success (:status %)) per-source))
                  ;; hold the last source's model-spec / candidate-axioms /
                  ;; embed-fields for the focal-close re-invokes.
                  last-ok (last (filter #(= :success (:status %)) per-source))]
              (if mx-fail
                (merge envelope
                       {:status :failed-at-model-extract
                        :survey-profiles profiles
                        :error (:error mx-fail)
                        :failed-source (:source mx-fail)
                        ;; MS-2 — the per-source outcomes are surfaced EVEN on the
                        ;; model-extract abort (the mapv ran EVERY source; earlier
                        ;; sources may have landed — that must be readable).
                        :source-reports source-reports})

                (let [;; --- STEP 4.5: GC-10 Fix B2 — GLOBAL family↔detail SKOS
                      ;;     hierarchy. Now that EVERY source has landed + reconciled,
                      ;;     bridge same-canonical-type concepts that sit at DIFFERENT
                      ;;     code-system grains (family `…/01` ↔ detail `…/01.0407`) so
                      ;;     the program→field(family)→field(detail)→occupation chain
                      ;;     connects. Deterministic (NO LLM), domain-agnostic,
                      ;;     bounded — lands skos:narrower via the normal Reconcile path.
                      hierarchy-report
                      (land-family-detail-hierarchy!
                       ctx {:ontology-id ontology-id :model model
                            :reconcile-fn reconcile-fn :source-uri-sets source-uri-sets})
                      ;; --- STEP 4.6: GC-11b — the deterministic linking-key
                      ;;     code-node spine (the cross-source JOIN). Now that
                      ;;     EVERY source has landed + reconciled, AGGREGATE the
                      ;;     discovered linking-key NAMES across the per-source
                      ;;     model-specs (GC-11a's :linking-keys carry-forward),
                      ;;     mint ONE code node per distinct (linking-key, value),
                      ;;     and attach every carrier via identified-by — so two
                      ;;     concepts from DIFFERENT sources sharing a code value
                      ;;     join through the one node. Deterministic (NO LLM),
                      ;;     domain-agnostic, bounded — lands via the normal
                      ;;     Reconcile create-concept / create-relationship path.
                      linking-key-report
                      (land-linking-key-spine!
                       ctx {:ontology-id ontology-id :model model
                            :reconcile-fn reconcile-fn :source-uri-sets source-uri-sets
                            :model-specs (keep :model-spec per-source)})
                      ;; --- STEP 5: build! (deterministic skeleton — dedup +
                      ;;             S15 exit-criterion over the landed graph) ---
                      build-result (build-fn ctx (cond-> {:ontology-id ontology-id
                                                          :sources [{:type :inline-concepts
                                                                     :concepts []}]}
                                                   judge-fn (assoc :judge-fn judge-fn)
                                                   exit-criterion (assoc :exit-criterion exit-criterion)))
                      ;; --- STEP 6: the CQ-OBJECTIVE LOOP ---
                      loop-result (cq-objective-loop!
                                   ctx {:ontology-id ontology-id :source (first sources)
                                        :goal goal :profile (first profiles)
                                        ;; GC-6 — thread the discovered vocabulary into
                                        ;; the focal-close re-invokes (re-model/re-extract
                                        ;; must obey the SAME shared vocabulary).
                                        :vocabulary vocab
                                        ;; GC-9 — the focal-close re-extracts must obey
                                        ;; the SAME reduced caps as the initial extract.
                                        :max-containers max-containers
                                        :max-windows max-windows
                                        :judge-fn judge-fn :exit-criterion exit-criterion
                                        :model model :resilient? resilient?
                                        :evolver-config evolver-config
                                        :pipeline-sheet-id pipeline-sheet-id
                                        :route-sheet-id route-sheet-id
                                        :source-uri-sets source-uri-sets
                                        :held-candidate-axioms (:candidate-axioms last-ok)
                                        :held-model-spec (:model-spec last-ok)
                                        :held-embed-fields (:embed-fields last-ok)
                                        :gate-fn gate-fn :route-fn route-fn
                                        :model-extract-fn model-extract-fn
                                        :reconcile-fn reconcile-fn
                                        :axiom-fn axiom-fn :embed-fn embed-fn})]
                  (merge
                   envelope
                   (select-keys loop-result [:status :graph-health :cq-verdict :cq-loop])
                   ;; MS-2 — HONEST final status: the run "completing" while ≥1
                   ;; source's reconcile was non-:success means that source's
                   ;; drafts NEVER LANDED. Surface :partial-reconcile instead of
                   ;; claiming the CQ loop's status alone; the loop's own status
                   ;; stays observable as :cq-loop-status.
                   (when partial-reconcile?
                     {:status :partial-reconcile
                      :cq-loop-status (:status loop-result)})
                   {:survey-profiles profiles
                    :competency-questions (:competency-questions derive)
                    ;; MS-2 — the per-source outcome reports (extracted counts +
                    ;; reconcile/axiom/embed statuses + landed counts): a
                    ;; zero-landed source is readable at a glance.
                    :source-reports source-reports
                    ;; GC-10 Fix B2 — surface the family↔detail hierarchy bridging
                    ;; report (edge-count + honest truncation) so it is observable.
                    :hierarchy-report hierarchy-report
                    ;; MT-2 — surface the per-source survey-driven SELECTION reports
                    ;; (containers-total vs selected + drop reasons + rank-degraded)
                    ;; so the dropped noise + any LLM-rank degrade is OBSERVABLE, not a
                    ;; false-green. Keyed by source for legibility; nil for a source
                    ;; that took the take-cap fallback (single-container / no select).
                    :selection-reports (mapv (fn [r] {:source (:source r)
                                                      :selection-report (:selection-report r)})
                                             per-source)
                    :build-result build-result}))))))))))))

;; =============================================================================
;; run-central-evolver! — the keystone entry point (greenfield-vs-maintain →
;; survey → derive CQs → bounded loop → CQ verdict). RE-HOUSES the DT1 spine.
;; =============================================================================

(defn run-central-evolver!
  "EB10 — the CENTRAL evolver. Pursues CQ-satisfaction as its OBJECTIVE over a set
   of sources by COMPOSING the EB2-EB9 subbehaviors via `:delegate` and re-housing
   DT8/DT9's loop + DT9's greenfield-vs-maintain decision. The keystone:

     1. greenfield-vs-maintain `:condition` (DT9 reuse): a graph already exists for
        the ontology-id? BOTH arms run the SAME evolver pipeline below
        (`run-evolver-pipeline!`) — the difference is the recorded branch decision +
        the `:mode` tag. MAINTAIN (EB11) runs the pipeline AGAINST THE EXISTING
        graph: the EB5 reconcile reads current graph state so an existing entity
        reconciles-not-duplicates (idempotent), a NEW source's NEW classes/attrs
        land alongside the existing graph (TBox evolution via EB6), and the CQ loop
        re-gates the UPDATED graph. GREENFIELD runs it against an empty graph. It is
        the SAME code path (handoff §3 — the subbehaviors are against-graph-state by
        construction); EB11 flipped the maintain arm from the deferred stub.
     2. SURVEY each source (`:delegate` ontology-survey/…@v1) → per-source profile.
     3. DERIVE the CQs (`:delegate` Validate+CQ derive) + persist the ORSD spec.
     4. For each source: `:delegate` Model→Extract (the fixed pipeline sheet), then
        `:code` LAND + `:delegate` Reconcile + `:delegate` Axiom/TBox, then
        `:delegate` Embed+Index (guaranteed P2).
     5. `:code` build! (the deterministic skeleton — dedup + the S15 exit-criterion).
     6. the CQ-OBJECTIVE LOOP (`cq-objective-loop!`): run the gate IN-PROCESS w/ the
        judge, route failing CQs to the closing subbehavior, re-invoke focally,
        re-gate — until pass / all-unanswerable / budget. ALWAYS terminates with a
        surfaced reason.

   Required `params`:
     :ontology-id — the granted scope (REQUIRED).
     :sources     — vector of source descriptors `{:type :csv|:sql|:excel :path …}`.
     :goal        — the runtime goal that orients every subbehavior (a string).

   Optional:
     :model :budget :resilient? :judge-fn :exit-criterion :consumer-cqs
     :evolver-config :debug? :mode (force :greenfield/:maintain)
     + the injected loop/seam fns (tests stub them; production delegates for real).

   Returns:
     {:status :complete | :partial-reconcile | :failed-cq | :failed-at-survey
              | :failed-at-derive-cqs | :failed-at-model-extract
      :mode :greenfield | :maintain           ; which arm ran (EB11)
      :ontology-id :goal :graph-health :cq-verdict
      :branch-points {:greenfield-vs-maintain <DT9 decision>}
      :survey-profiles [<per-source profile> …]
      :competency-questions [<CQ> …]
      :source-reports [{:source {:type :path}          ; MS-2 — per-source outcomes
                        :extracted {:concepts N :relationships N}
                        :reconcile {:status <kw> :landed <N-or-nil>}
                        :axiom {:status <kw>} :embed {:status <kw>}} …]
      :build-result <verbatim build! result>
      :cq-loop {:iterations :termination-reason :unanswerable-cqs :history}}
   A subbehavior failure surfaces honestly as :failed-at-<step> (#5; no false green).
   MS-2 — a run that reached the end with ≥1 source's reconcile non-:success is
   :status :partial-reconcile (that source's drafts NEVER landed — never claim the
   CQ loop's status alone); the loop's own status stays on :cq-loop-status, and the
   run CONTINUES past a failed reconcile data-maximizing (later sources are
   independent — unlike model-extract's abort semantics, which are unchanged).

   EB11: BOTH the greenfield AND the maintain arm now run the SHARED
   `run-evolver-pipeline!` (the maintain arm was flipped from `maintain-deferred-
   stub`). The maintain arm runs the pipeline AGAINST THE EXISTING graph (the EB5
   reconcile is against-graph-state, so this is RE-ORCHESTRATION, not a fork — the
   existing graph is the input; the new source's discoveries reconcile-not-
   duplicate and grow the TBox). `:mode` distinguishes which arm ran."
  [ctx {:keys [ontology-id sources goal model budget resilient? judge-fn exit-criterion
               consumer-cqs evolver-config debug? mode source-uri-sets
               max-containers max-windows
               survey-fn derive-cqs-fn synthesize-vocab-fn graph-context-fn select-fn
               model-extract-fn reconcile-fn
               axiom-fn embed-fn build-fn gate-fn route-fn]
        :or {model "google/gemini-3-flash-preview"}}]
  (when-not ontology-id
    (throw (ex-info "run-central-evolver! requires :ontology-id (the granted scope)"
                    {:ontology-id ontology-id})))
  (when-not (and (sequential? sources) (seq sources))
    (throw (ex-info "run-central-evolver! requires a non-empty :sources vector" {:sources sources})))
  (when-not (and (string? goal) (seq goal))
    (throw (ex-info "run-central-evolver! requires :goal (a non-blank string)" {:goal goal})))
  (let [ctx (assoc ctx :granted-ontology-id ontology-id :ontology-id ontology-id)
        ;; --- STEP 1: DT9 greenfield-vs-maintain (re-house the decision) ---
        gf-branch (dt/greenfield-vs-maintain-branch-stub ctx (cond-> {:ontology-id ontology-id}
                                                               mode (assoc :mode mode)))
        ;; EB11: BOTH arms run the SAME pipeline. The maintain arm runs it AGAINST
        ;; THE EXISTING graph (the subbehaviors are against-graph-state by
        ;; construction — handoff §3); the only difference is the `:mode` tag and
        ;; the recorded branch decision. This is the FLIP of the deferred stub:
        ;; re-orchestration (reuse the pipeline + the loop + the subbehaviors),
        ;; NOT a rewrite (discipline 8).
        run-mode (:selected gf-branch)]
    (run-evolver-pipeline!
     ctx {:ontology-id ontology-id :sources sources :goal goal :model model
          :resilient? resilient? :judge-fn judge-fn :exit-criterion exit-criterion
          :consumer-cqs consumer-cqs :evolver-config evolver-config
          ;; GC-9 — the reduced-cap knobs (default nil → the extract uses its own
          ;; defaults — behavior-preserving). Thread them to every per-source extract.
          :max-containers max-containers :max-windows max-windows
          :mode run-mode :gf-branch gf-branch :source-uri-sets source-uri-sets
          :survey-fn survey-fn :derive-cqs-fn derive-cqs-fn
          :synthesize-vocab-fn synthesize-vocab-fn
          ;; GM-1 — the pre-Model graph-context step (default: the real snapshot).
          :graph-context-fn graph-context-fn
          ;; MT-2 — the survey-driven container SELECT step (default: the real seam).
          :select-fn select-fn
          :model-extract-fn model-extract-fn :reconcile-fn reconcile-fn
          :axiom-fn axiom-fn :embed-fn embed-fn
          :build-fn build-fn :gate-fn gate-fn :route-fn route-fn})))
