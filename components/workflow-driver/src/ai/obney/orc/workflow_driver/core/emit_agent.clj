(ns ai.obney.orc.workflow-driver.core.emit-agent
  "The emit agent: a single LLM call that takes the current Sheet
   snapshot + an objective and returns a complete (workflow …) DSL
   form as a Clojure source string.

   Milestone 2 validation question: when the LLM is given a curated
   playbook and a structured snapshot, does it emit parseable,
   schema-conformant DSL? This namespace answers that.

   The system prompt is the load-bearing piece — it teaches the
   DSL grammar, the blackboard contract, and the stable-name
   invariant. The orc-engineering analog of predict-rlm's
   PREDICT_RLM_INSTRUCTIONS."
  (:require [dscloj.core :as dscloj]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.workflow-driver.core.describe-agent :as describe]))

(def ^:private playbook
  "The curated 'you are an ORC workflow engineer' instructions. Kept
   in one place so it can be evolved (and eventually GEPA-optimized)
   independently."
  (str
"You are an ORC workflow engineer. Your job is to emit a behavior-tree DSL
form that improves a target Sheet against a stated objective. You see the
Sheet's current structure and recent performance; you respond with one
complete (workflow …) form expressed in the ORC DSL.

# Output format

Return raw Clojure source — NO markdown fences, NO commentary, NO prose.
Your :workflow-form output must be a single top-level (workflow …) form
that evaluates against the DSL constructors below. The :reasoning output
is a short paragraph explaining what you changed and why.

# DSL grammar

A workflow is built from these constructors. Names are required and must
be unique within the Sheet — they're the stable identity that judges,
GEPA optimizations, and per-node trace history are attached to.

  (workflow \"sheet-name\"
    (blackboard {…})            ;; blackboard schema (Malli)
    (judges {…})                ;; optional named judge configs
    <root-node>)                ;; one root node — usually a composite

Composite nodes (control flow):

  (sequence \"name\" <child> <child> …)
    Run children in order; fail on first child failure.

  (fallback \"name\" <child> <child> …)
    Run children in order; succeed on first child success.

  (parallel \"name\" {:success-policy :all|:any|:majority
                     :failure-policy :any|:all}
            <child> <child> …)
    Run all children concurrently; success per policy.

  (map-each \"name\" :from :source-key :as :item-key :into :output-key
                    :parallel n
            <child>)
    Iterate over the vector at :source-key, binding each item to
    :item-key, run the child, collect into :output-key.

Leaf nodes (do work):

  (llm \"name\"
    :model \"openrouter/...\"      ;; e.g. \"google/gemini-2.5-flash\"
    :instruction \"…\"             ;; the prompt — this is what you tune
    :reads [:key …]
    :writes [:key]
    :judges [\"judge-name\" …]     ;; optional, attached judges
    :retry {:max-attempts 2 :backoff-ms [200 500]})

  (code \"name\"
    :fn \"namespaced.fn/symbol\"   ;; fully-qualified Clojure fn
    :reads [:key …]
    :writes [:key])

  (repl-researcher \"name\"
    :model \"…\" :instruction \"…\"
    :reads […] :writes […]
    :rlm {:enabled? true …})       ;; optional RLM mode for agentic sub-step

  (delegate \"name\"
    :target-sheet-id #uuid \"…\"
    :reads […] :writes […])

  (condition \"name\" :check {:key :k :op :equals :value v} :on-fail :failure)
  (llm-condition \"name\" :model \"…\" :instruction \"…\" :reads […])

# Blackboard contract

Every leaf declares :reads and :writes as vectors of keywords. The
keywords must appear in (blackboard {…}). When you add a new
intermediate value, declare its key in the blackboard schema with a
Malli type.

# The stable-name invariant — IMPORTANT

Node IDs are deterministic from sheet-id + node name. If you keep a
node's name across emissions, the node keeps its judge attachments,
GEPA history, and per-node metrics. If you rename a node — even if its
role is unchanged — that history is LOST and the node looks brand new.

Rule: only change a name when the role actually changed. If you're
tuning an instruction or swapping a model, keep the name. If you're
splitting a node into two distinct stages, the new stages get fresh
names.

The driver enforces this passively — your diff will show :removed for
any name you dropped and :added for any new name. Renames look like
(:removed [name] :added [name]) which is the signal that history is
gone.

# Workflow-name invariant

The (workflow \"…\") name must equal the target Sheet's name. If you
emit a different name, the driver will reject your turn — a different
name would create a different Sheet, not modify this one.

# Strategy

- Make the smallest change that addresses the objective.
- Prefer instruction tuning over structural change when both could fix it.
- Add a verification fallback only when the objective explicitly asks for
  groundedness or a recovery path.
- If the snapshot shows no execution history, propose a smoke run —
  don't speculate about failures you can't see.
"))

(def ^:private emit-module
  {:inputs [{:name :sheet-name
             :spec :string
             :description "The target Sheet's name. The :workflow-form output's first argument MUST equal this exact string — emitting a different name will be rejected."}
            {:name :snapshot
             :spec :string
             :description "Structured snapshot of the current Sheet — DSL form, blackboard schema, per-node metrics, recent ticks."}
            {:name :objective
             :spec :string
             :description "What to improve about this Sheet on this turn. May reference specific nodes by name."}
            {:name :prior-attempts
             :spec :string
             :description "Plain-text summary of previous turns and their outcomes (rejections, eval failures). Empty string on the first turn. Use this to avoid repeating mistakes."}]
   :outputs [{:name :reasoning
              :spec :string
              :description "1-3 sentences explaining what you changed and why. Cite node names. Be terse."}
             {:name :workflow-form
              :spec :string
              :description "Raw Clojure source for one complete (workflow …) form. No markdown fences. The first arg must equal :sheet-name."}]
   :instructions playbook})

(defn render-prior-attempts
  "Format a vector of prior-attempt maps into the plain-text block the
   emit-module's :prior-attempts input expects. Each item shape:
     {:turn n :status :rejected|:eval-failed :reason \"…\"}"
  [attempts]
  (if (empty? attempts)
    ""
    (str "Prior attempts on this objective (avoid repeating these mistakes):\n"
         (->> attempts
              (map (fn [{:keys [turn status reason]}]
                     (str "  turn " turn ": " (clojure.core/name status)
                          (when reason (str " — " reason)))))
              (clojure.string/join "\n")))))

(defn propose-tree-via-llm!
  "Run one turn of the emit agent. Calls the LLM, returns the raw
   :workflow-form string and :reasoning, plus usage and the resolved
   model. Does NOT submit — pair with `submit-tree!` to apply.

   Options:
     :model            DSCloj model id (default \"google/gemini-2.5-flash\")
     :provider         DSCloj provider (default :openrouter)
     :tick-limit       how many recent ticks to include (default 10)
     :prior-attempts   vector of prior-attempt maps for the agent's
                       iteration history (default [] — first turn)"
  [ctx sheet-id objective & [{:keys [model provider tick-limit prior-attempts]
                              :or {model "google/gemini-2.5-flash"
                                   provider :openrouter
                                   tick-limit 10
                                   prior-attempts []}}]]
  (let [sheet (orc/get-sheet ctx sheet-id)
        sheet-name (:name sheet)
        snapshot-text (describe/build-describe-input ctx sheet-id tick-limit)
        history-text (render-prior-attempts prior-attempts)
        effective-provider (orc/get-provider-with-model provider model)
        result (dscloj/predict
                 effective-provider
                 emit-module
                 {:sheet-name sheet-name
                  :snapshot snapshot-text
                  :objective objective
                  :prior-attempts history-text}
                 {:validate? false
                  :with-metadata? true})
        outputs (or (:outputs result) result)]
    {:sheet-name sheet-name
     :snapshot snapshot-text
     :objective objective
     :reasoning (:reasoning outputs)
     :workflow-form (:workflow-form outputs)
     :usage (:usage result)
     :model (or (:model result) model)}))
