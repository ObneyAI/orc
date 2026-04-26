(ns ai.obney.orc.workflow-driver.interface
  "Public interface for the workflow-driver agent.

   The driver agent's primitive is the orc behavior-tree DSL itself —
   each turn it observes the target Sheet, emits a complete (workflow …)
   form, ticks it, and scores. Milestone 1 covers the read-only
   observation surface only. Mutation (`submit-tree!`), tick driving,
   and publish guards are later milestones.

   See docs/RLM-DEEP-ANALYSIS.md Part 8 for the full design."
  (:require [ai.obney.orc.workflow-driver.core.observe :as observe]
            [ai.obney.orc.workflow-driver.core.format :as fmt]
            [ai.obney.orc.workflow-driver.core.describe-agent :as describe]
            [ai.obney.orc.workflow-driver.core.emit :as emit]
            [ai.obney.orc.workflow-driver.core.emit-agent :as emit-agent]
            [ai.obney.orc.workflow-driver.core.eval-set :as eval-set]
            [ai.obney.orc.workflow-driver.core.publish :as publish]
            [ai.obney.orc.workflow-driver.core.loop :as drv-loop]
            [ai.obney.orc.workflow-driver.core.bt-node :as bt-node]
            ;; Force schema registration on load so :driver/* events
            ;; are appendable wherever workflow-driver is required.
            [ai.obney.orc.workflow-driver.interface.schemas]
            [ai.obney.grain.event-store-v3.interface :as es]))

;; =============================================================================
;; Read ops — structured data
;; =============================================================================

(def sheet-snapshot
  "Full structural snapshot of a Sheet. Returns a map with :sheet,
   :root-node, :nodes-by-id, :blackboard-schema, :latest-version,
   :tree-metadata. nil if the sheet does not exist."
  observe/sheet-snapshot)

(def sheet-as-dsl
  "Round-trip the Sheet into a DSL form (the same shape the agent will
   emit when it gets `submit-tree!`). Use this to show the agent the
   current tree in its own writing vocabulary."
  observe/sheet-as-dsl)

(def recent-ticks
  "List recent ticks for a sheet, most recent first.
   Options: :limit (default 20), :status (:running/:completed/:cancelled)."
  observe/recent-ticks)

(def tick-snapshot
  "Return one tick's full state. nil if the tick does not exist."
  observe/tick-snapshot)

(def node-summary
  "Per-node rolling metrics joined with the node tree. The agent uses
   this to spot slow / failing / declining nodes."
  observe/node-summary)

(def pareto
  "Cost vs quality frontier datapoints across recent completed ticks."
  observe/pareto)

;; =============================================================================
;; Render ops — text for LLM consumption
;; =============================================================================

(def render-sheet-snapshot fmt/render-sheet-snapshot)
(def render-node-summary fmt/render-node-summary)
(def render-recent-ticks fmt/render-recent-ticks)
(def render-tick-snapshot fmt/render-tick-snapshot)
(def render-pareto fmt/render-pareto)

;; =============================================================================
;; Composed views — the bundles the describe-agent will call
;; =============================================================================

(def describe-via-llm!
  "Validate Milestone 1: hand a Sheet snapshot to an LLM and ask it to
   summarize purpose, trouble signs, and the first thing to optimize.
   Requires a configured DSCloj provider (e.g. :openrouter via
   OPENROUTER_API_KEY). See `core/describe_agent.clj` for the full
   prompt and module."
  describe/describe-via-llm!)

;; =============================================================================
;; Emit ops — apply LLM emissions to a target Sheet
;; =============================================================================

(def parse-workflow-form
  "Parse an LLM-emitted DSL source string into a workflow-def map. Pure
   — no side effects. Returns {:status :ok :workflow-def …} or
   {:status :parse-error :error <msg>}."
  emit/parse-workflow-form)

(def submit-tree!
  "Apply an LLM-emitted DSL string to the target Sheet via build-workflow!
   (idempotent on content hash). Returns {:status :ok :sheet-id … :diff
   {:added […] :modified […] :removed […]}} or a structured rejection
   {:status :parse-error|:name-mismatch|:build-error :error <msg>} that
   the driver loop surfaces to the agent on its next turn."
  emit/submit-tree!)

(def diff-nodes
  "Compute structural diff between two node-id→node maps. Exposed so
   downstream code can render diffs from arbitrary snapshots."
  emit/diff-nodes)

(def propose-tree-via-llm!
  "Milestone 2 emit agent: ask the LLM to propose a complete DSL
   workflow form that improves the target Sheet against an objective.
   Returns {:workflow-form <source string> :reasoning <…> :usage … :model …}.
   Pair with `submit-tree!` to actually apply the proposal."
  emit-agent/propose-tree-via-llm!)

;; =============================================================================
;; Eval set + publish/revert
;; =============================================================================

(def run-eval-set!
  "Run the target Sheet against every item in an eval-set sequentially.
   Returns {:total :pass-count :fail-count :pass-rate :results [...]}."
  eval-set/run-eval-set!)

(def publish!
  "Promote the current draft to a new published version, gated by the
   eval-set's pass-rate. Returns {:status :ok|:refused|:error …}."
  publish/publish!)

(def revert!
  "Discard the draft and restore from a previously-published version.
   The dirty draft is stashed automatically by grain so this is reversible."
  publish/revert!)

(def commit-version!
  "Publish the current draft as a new version WITHOUT re-running the
   eval-set. Use when the caller has already evaluated the draft and
   wants to skip a redundant (variance-prone) second pass."
  publish/commit-version!)

;; =============================================================================
;; Driver loop (M4)
;; =============================================================================

(def run-driver-loop!
  "Multi-turn driver: observe → propose → submit → evaluate → decide,
   iterating with rejection feedback until publish, surrender, or
   error. See `core/loop.clj` for the full contract."
  drv-loop/run-driver-loop!)

;; =============================================================================
;; Driver as a composable BT node
;; =============================================================================

(def driver-node
  "DSL helper: produce a code-node that runs the driver loop against a
   target Sheet. The parent workflow's blackboard supplies the driver
   config under `:driver-config` (or whatever key you pass via :reads).
   See `core/bt_node.clj` for the config shape."
  bt-node/driver-node)

(def driver-loop-code-fn
  "Code-fn referenced by `driver-node`. Exposed so the FQN is
   resolvable through the orc-service public interface."
  bt-node/driver-loop-code-fn)

;; =============================================================================
;; Meta-trace replay
;; =============================================================================

(defn replay-session
  "Read every driver event for `session-id` from the event store, in
   the order they were written. The full optimization arc — session
   start, every turn's proposal/submit/eval/decision, session end —
   reconstructs from this single query.

   Returns a vector of event maps:
     [{:event/id :event/type :event/timestamp
       :session-id :sheet-id …event-specific keys…} …]"
  [{:keys [event-store tenant-id] :as _ctx} session-id]
  (->> (es/read event-store
         {:tags #{[:driver/session session-id]}
          :tenant-id tenant-id})
       (into [])
       (sort-by :event/timestamp)
       vec))

(defn describe-sheet
  "Compose the read-only operations into the single text block an LLM
   agent receives when asked 'describe this Sheet's recent
   performance.' Returns a string.

   Bundles: structural snapshot + DSL form + per-node rolling metrics +
   recent ticks + cost/duration frontier."
  [ctx sheet-id & [{:keys [tick-limit] :or {tick-limit 10}}]]
  (let [snapshot (sheet-snapshot ctx sheet-id)]
    (if-not snapshot
      (str "No Sheet found for id " sheet-id)
      (let [dsl-form (sheet-as-dsl ctx sheet-id)
            nodes (node-summary ctx sheet-id)
            ticks (recent-ticks ctx sheet-id {:limit tick-limit})
            front (pareto ctx sheet-id {:limit tick-limit})]
        (str
          (render-sheet-snapshot snapshot)
          "\n\n"
          "Current tree (DSL form):\n"
          (or dsl-form "  <unable to render>")
          "\n\n"
          (render-node-summary nodes)
          "\n\n"
          (render-recent-ticks ticks)
          "\n\n"
          (render-pareto front))))))
