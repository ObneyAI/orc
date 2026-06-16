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

(defn profile-node-prompt
  "PROMOTION SEAM (PRD M6) for the Profile node. DT1 thin body: characterize the
   source via the granted specialist tools and emit the frozen profile contract.
   DT2 replaces this body with a focused, prototyped prompt — orchestration
   unchanged. `goal` orients the node (PRD user-story 17)."
  [goal]
  (str "GOAL (orients this step): " goal "\n\n"
       "STEP: PROFILE the source. Using the granted source-access tools (sample, "
       "never dump), characterize what this source contains. Identify the "
       "candidate entities, which field(s) IDENTIFY each entity, which fields "
       "could SCOPE the source (region / subset / time window the goal names), "
       "which fields are CODES/KEYS that could LINK to other sources, any "
       "breakdown/grain signals (a column whose repeats make rows finer than "
       "entities), and keep a small representative sample of rows."
       (contract-block
        profile-contract-keys
        "  :entity-candidates  — vector of candidate entity descriptions (strings)\n  :identifying-keys   — map of entity-candidate -> the field(s) that identify it\n  :scope-fields       — vector of fields that could scope the source to the goal\n  :linking-keys       — vector of code/key fields that link to other sources\n  :grain-signals      — vector of signals that rows are finer-grained than entities\n  :sample             — vector of a few representative row maps")))

(defn model-node-prompt
  "PROMOTION SEAM (PRD M6) for the Model node. DT1 thin body: read goal + the
   profile (its predecessor's output on the blackboard) and decide entity types,
   URI-keying, grain strategy, and the scope filter. DT3 replaces this body with
   a focused, prototyped prompt — orchestration unchanged."
  [goal]
  (str "GOAL (orients this step): " goal "\n\n"
       "STEP: MODEL the entities. You are given the PROFILE of this source as "
       "the input key :profile (read it with (get-input :profile)). Decide the "
       "ENTITY TYPES this source contributes, which field(s) KEY each entity's "
       "URI (so the same real entity collapses to one node, not one-per-row), the "
       "GRAIN STRATEGY for each entity type (one of " (pr-str valid-grain-strategies)
       " — :canonical-row-filter keeps one entity per canonical row and drops "
       "breakdown rows; :breakdown-as-entity mints each breakdown as its own "
       "entity), the SCOPE FILTER the goal implies (the field + values to keep), "
       "and the EDGES between entity types."
       (contract-block
        model-contract-keys
        "  :entity-types  — vector of {:type <str> :uri-keying-fields [<field> ...] :grain-strategy <one of the enum above>}\n  :scope-filter  — a description (or map) of the field + values to keep for the goal's scope\n  :edges         — vector of {:source-type <str> :target-type <str> :predicate <str>}")))

(defn transform-node-prompt
  "PROMOTION SEAM (PRD M6) for the Transform node. DT1 thin body: read goal + the
   model-spec (its predecessor's output) + a sample and author a per-row
   extraction TRANSFORM (the V20 shape), sample-validate it, and emit it. DT4
   replaces this body with a focused, prototyped prompt — orchestration
   unchanged. The V20 apply-step (downstream) is already proven; this node just
   AUTHORS the transform."
  [goal]
  (str "GOAL (orients this step): " goal "\n\n"
       "STEP: DESIGN the extraction TRANSFORM. You are given the MODEL-SPEC as the "
       "input key :model-spec (read it with (get-input :model-spec)). Author a PURE "
       "per-row transform — a (fn [row] {:concept-drafts [...] :relationship-drafts "
       "[...]}) — that mints the entity types the model-spec decided at the grain + "
       "scope it decided (return EMPTY drafts for breakdown/out-of-scope rows). "
       "VALIDATE it on a sample (map it over rows you sample), then hand it back AS "
       "A STRING via :transform-source. It runs in the restricted sandbox (only "
       "clojure.core / clojure.string / clojure.set; no Java interop, no tool calls "
       "inside it). Carry the row's measures as :attributes on the entity concept. "
       "EVERY concept-draft your transform returns MUST carry BOTH a :uri and a "
       ":label (a human-readable name — derive it from the row when the row has a "
       "name/title field, else from the identifying value), plus an :evidence "
       "vector with a verbatim quote; every relationship-draft MUST carry "
       ":source-uri, :target-uri, and :predicate. A draft missing :uri or :label "
       "is rejected downstream — emit both for every draft (including any node an "
       "edge points to)."
       (contract-block
        transform-contract-keys
        "  :transform-source — the (fn [row] ...) AS A STRING of Clojure source\n  :selector         — the table/sheet name the transform applies to (omit for csv)")))

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
  [ctx {:keys [node-key prompt source contract-keys extra-inputs model budget debug?]}]
  (let [result (rlm-discovery/run-node-session!
                ctx
                {:node-name node-key
                 :instruction prompt
                 :source source
                 :writes contract-keys
                 :extra-inputs extra-inputs
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
        profile-r (run-node! ctx {:node-key :profile
                                  :prompt (profile-node-prompt goal)
                                  :source source
                                  :contract-keys profile-contract-keys
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
            model-r (run-node! ctx {:node-key :model
                                    :prompt (model-node-prompt goal)
                                    :source source
                                    :contract-keys model-contract-keys
                                    :extra-inputs {:profile (node-output bb :profile)}
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

                ;; --- Node 3: TRANSFORM-design (reads the model-spec via the blackboard) ---
                transform-r (run-node! ctx {:node-key :transform
                                            :prompt (transform-node-prompt goal)
                                            :source source
                                            :contract-keys transform-contract-keys
                                            :extra-inputs {:model-spec (node-output bb :model)}
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

                    ;; --- full-extract-vs-inline branch (DT1 stub: always full-extract) ---
                    fx-branch (full-extract-vs-inline-branch-stub
                               ctx {:row-count nil :sample-covers? false})

                    ;; --- [V20] deterministic full-extraction apply-step (REUSED UNCHANGED) ---
                    ;; The Transform node authored the transform; this stage APPLIES it
                    ;; over the WHOLE source (V19 stream-all), per PRD M4. A transform
                    ;; that fails to EVALUATE or a bad selector is a real fault — we
                    ;; capture it as ::extract-error so it surfaces honestly as
                    ;; :failed-at-extract below (no false green, no silent sample
                    ;; fallback — Discipline #5).
                    full-extraction
                    (when (and (string? transform-source)
                               (seq (str/trim transform-source)))
                      (try
                        (rlm-discovery/apply-extraction-transform!
                         {:descriptor {:type (:type source) :path (:path source)}
                          :selector selector
                          :transform-source transform-source})
                        (catch Throwable t
                          {::extract-error (.getMessage t)})))
                    extract-error (::extract-error full-extraction)]
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
                     :branch-points {:greenfield-vs-maintain gf-branch
                                     :full-extract-vs-inline fx-branch
                                     :cq-reextract cq-branch
                                     :recovery (recovery-branch-stub
                                                ctx {:failed-node nil :error nil})}}))))))))))))

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
