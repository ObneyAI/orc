# Two semantic attempts in an ordinary tree

Use this when a bounded knowledge task needs one targeted revision after a semantic check. This is a hand-assembled `llm`/`condition`/`code`/`fallback` tree at ORC `490816267b76b0428c53437052980a25f5905209`. It requires no researcher, loop node or decorator. The parent goal is **a grounded proposal recorded through a command and confirmed in the query projection**.

The first method generates and assesses a draft. Its typed verdict either accepts it, permits one revision with useful feedback, or defers honestly. The second method is eligible only for the explicit repair verdict. An accepted draft whose save fails cannot accidentally enter the semantic-repair method. Provider/schema failures also remain failures unless another declared method handles them; they are not semantic rejection.

Define support at the claim level. An exact quotation and valid source ID prove where text came from, not that the surrounding conclusion is warranted. A historical success rate does not by itself establish the failure probability of a future case. An estimate remains an estimate; retain whether it is self-reported, independently assessed or unverified when that qualification matters. An expiring opportunity does not by itself establish that immediate action is necessary or guarantees the outcome. Check the strength of comparisons and recommendations as well as their citations.

Give the assessment the actual current draft, its evidence and the original acceptance criteria. Request unsupported inferences, lost qualifications and specific repair feedback in its typed result. An ordinary LLM assessment, or a suitably verified finite decision model, can perform this bounded task without an advanced judge/training framework. Schema, ID and quote checks remain separate mechanical guards. Reassess any revision before accepting it. A well-typed positive verdict and one successful check do not establish general semantic accuracy.

Put the following in `src/my_app/repair.clj`. The injected `:app/record-proposal!` capability has signature `(context proposal-id summary) -> projected-record`; the [Grain example](../../orc-grain-application/references/contracts.md) implements that boundary with an explicit guard, full command/query envelopes and projection read-back. Supply it to the asynchronous [poller context](../../orc-runtime-bootstrap/references/runtime.md).

```clojure
(ns my-app.repair
  (:require [ai.obney.orc.orc-service.interface :as orc]))

(defn publish-first [{:keys [inputs] :as ctx}]
  {:recorded-proposal ((:app/record-proposal! ctx) ctx
                       (:proposal-id inputs) (:first-draft inputs))})

(defn publish-revised [{:keys [inputs] :as ctx}]
  {:recorded-proposal ((:app/record-proposal! ctx) ctx
                       (:proposal-id inputs) (:revised-draft inputs))})

(def workflow
  (orc/workflow "grounded-proposal-two-attempts-v1"
    (orc/blackboard
      {:goal [:string {:description "The user's proposal requirements and acceptance criteria"}]
       :proposal-id [:uuid {:description "Host-owned identity for the requested domain proposal"}]
       :evidence [:vector {:description "Retrieved source evidence available for this proposal"}
                  [:map
                   [:source-id [:string {:description "Exact opaque ID returned by retrieval"}]]
                   [:passage [:string {:description "Attributable source content; absent facts are unknown"}]]]]
       :first-generation-reasoning [:string {:description "How the first draft uses the supplied evidence"}]
       :first-draft [:string {:description "First proposed grounded summary, including source attribution"}]
       :first-check-reasoning [:string {:description "Assessment of the first draft against goal and source evidence"}]
       :first-verdict [:enum {:description "accept meets the goal; repair is fixable with this evidence in one revision; defer lacks adequate evidence or a feasible repair"}
                       "accept" "repair" "defer"]
       :first-feedback [:string {:description "Specific unmet requirements and evidence needed for a targeted revision"}]
       :repair-reasoning [:string {:description "How the revision addresses the actual first-check feedback"}]
       :revised-draft [:string {:description "Revised grounded summary preserving actual source attribution"}]
       :second-check-reasoning [:string {:description "Fresh assessment of the revised draft and original goal/evidence"}]
       :second-verdict [:enum {:description "accept meets all required postconditions; reject remains incomplete"}
                        "accept" "reject"]
       :second-feedback [:string {:description "Remaining gaps if the final allowed attempt is rejected"}]
       :recorded-proposal [:map {:description "Exact domain query record confirmed after the accepted command"}
                           [:proposal-id :uuid] [:summary :string]]})
    (orc/fallback "establish-grounded-recorded-proposal"
      (orc/sequence "first-method"
        (orc/llm "generate-first"
          :instruction "Produce a useful proposal meeting the goal from the supplied evidence. Attribute factual claims to source IDs. Expose evidence gaps; do not supply unsupported facts from memory."
          :reads [:goal :evidence]
          :writes [:first-generation-reasoning :first-draft])
        (orc/llm "assess-first"
          :instruction "Assess the actual first draft against each goal requirement and its source evidence. Accept only useful, supported work. Choose repair only when one revision using the current evidence can close the specific gaps; describe them in feedback. Otherwise choose defer."
          :reads [:goal :evidence :first-draft]
          :writes [:first-check-reasoning :first-verdict :first-feedback])
        (orc/condition "first-is-accepted"
          :check {:key :first-verdict :op :equals :value "accept"})
        (orc/code "record-first"
          :fn "my-app.repair/publish-first"
          :reads [:proposal-id :first-draft]
          :writes [:recorded-proposal]))
      (orc/sequence "one-targeted-revision"
        (orc/condition "repair-is-eligible"
          :check {:key :first-verdict :op :equals :value "repair"})
        (orc/llm "revise"
          :instruction "Revise the actual first draft to address its specific feedback and satisfy the goal using the supplied evidence. Preserve source attribution and expose gaps that the evidence cannot close."
          :reads [:goal :evidence :first-draft :first-feedback]
          :writes [:repair-reasoning :revised-draft])
        (orc/llm "assess-revision"
          :instruction "Assess the revised draft against the original goal and evidence. Accept only if every required postcondition holds and factual claims are supported. Otherwise reject and describe remaining gaps; this is the last allowed semantic attempt."
          :reads [:goal :evidence :revised-draft]
          :writes [:second-check-reasoning :second-verdict :second-feedback])
        (orc/condition "revision-is-accepted"
          :check {:key :second-verdict :op :equals :value "accept"})
        (orc/code "record-revision"
          :fn "my-app.repair/publish-revised"
          :reads [:proposal-id :revised-draft]
          :writes [:recorded-proposal])))))
```

The application accepts only the declared initial inputs (`:goal`, `:proposal-id`, `:evidence`) from its trusted preparation path. Do not seed attempt outputs or old verdicts as new-run inputs. Each attempt owns its draft, reasoning, verdict and feedback keys; the shared final record has mutually exclusive writers. If delegating these methods, preserve the same ownership at each seam. Record the evidence/artifact version in the contract when either can change during a run.

The finite tree bounds business repair to one revision. LLM/provider retries, execution timeout, call budget and application restarts are separate budgets; configure and verify them separately. The tree does not reevaluate by writing a stage counter or returning `{:status :running}` from a code function. A code function returns declared write values; `execute-code` assigns its node status. An error map returned as a single write may be valid data and produce node success. The persistence capability must reject/throw on denied or failed effects and must verify the exact requested projection before returning its record.

If both methods fail, the fallback fails. Preserve first/second feedback in the durable execution record and surface the application's declared incomplete/deferred outcome. A newly generated clarification can be another useful outcome only when the parent's contract accepts it. Do not add an unguarded successful leaf, canned response, or regex/phrase quality gate to make exhausted work look complete.

## Required proof

- First draft accepted: one generation, one assessment, one recorded domain fact; no revision runs.
- First draft repairable: feedback reaches the revision, the fresh check sees the revised artifact, and only the accepted revision is saved.
- First verdict defer or final verdict reject: no save occurs; remaining gaps and terminal failure are visible.
- Provider/schema error: no stale verdict authorizes repair or save.
- Mechanically valid but semantically overstated draft: correct source IDs/quotes cannot disguise a forecast invented from historical observations, a lost estimate qualification, or an unsupported necessity/guarantee. The check sees that exact artifact and evidence; rejection prevents publication. Inspect fresh useful generation separately from known-bad-artifact rejection.
- Accepted draft's command denied/failed: the second method is ineligible; no old projection or unrelated effect disguises failure.

Six independent deterministic runs loaded this exact tree and supplied controlled outputs at the public `llm/predict` boundary while running real ORC and Grain. First acceptance and eligible repair acceptance each recorded exactly one proposal; repair consumed the actual first feedback and its check saw the revised artifact. Defer, final rejection, denied save and unavailable provider produced failure with no recorded proposal; denied save ran no revision. This establishes the tested branch/effect contracts, not live semantic quality, provider request construction or SIO parsing.

Use a real ordinary model to verify those remaining model boundaries and observed task performance. Capture actual model inputs, attempt writes, accepted domain events and the exact projected summary. Construction or a scripted verdict proves neither useful live output nor general completion accuracy. Source anchors: `core/dsl.clj` `llm`/`code`/`condition`/`fallback`; `core/executor.clj` `execute-code`; `core/todo_processors.clj` fallback completion. Execute the consuming application's own contract and adverse paths.

## Revision context

The cited `4908162` observations are historical. Decision/tool APIs were inspected at ORC `1851212396cced2fc4533fda30b824bd4b691390`; see [current source status](../../SOURCE-STATUS.md) for the public integration checkpoint. Preserve those original experiment claims at their original pin; use current owning skills for supported integration.
