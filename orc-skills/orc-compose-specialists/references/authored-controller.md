# A bounded authored controller

Use this for repeated selection of known specialists. Read [goal design](../../orc-design-tree/references/authored-goal-solving.md) first if the goal, catalog or missing-information contract is unclear. The [two-attempt repair](../../orc-design-tree/references/bounded-repair.md) remains a simpler option for one planned revision.

**Illustrative application wrapper.** The structure below uses inspected DSL calls and a repeat/delegate pattern exercised in controlled and live consumers. It is not a universal drop-in workflow. The application supplies a registered one-pass sheet, its concrete payload schema, an independent summary sheet and a receipt-producing function. It supplies the semantic work; the wrapper only bounds and repeats it. A success-only receipt node does not handle an execution failure; design that separate outcome explicitly when required.

## The one-pass contract

The registered one-pass sheet reads and writes `:payload` and `:solver`. Define `:payload` with the application's explicit Malli records: goal/obligation, relevant prior draft, feedback, qualified evidence, current invocation and pending information request. Do not replace it with an untyped context bag.

Within that sheet:

- A semantic decision selects from the currently eligible, described known catalog; exact guards route to registered delegates. Use [decision models](../../orc-decision-models/SKILL.md) and [tools](../../orc-tools/SKILL.md) at their respective boundaries.
- Build the selected specialist's packet before delegation. Match its returned invocation and obligation; merge only its current result. Preserve other work and completed effect receipts.
- A missing-information result retains the original obligation and typed request. Its resolver answer resumes that obligation, with the prior draft and new evidence. A new invocation is a fresh execution of the same registered specialist, not reuse of old output under its name.
- Reassess the actual goal. Return `"continue"` only when another useful eligible pass remains; otherwise return the honest outcome and specific remaining gap. The one-pass **node status** is successful when it produced this valid result, even when the **business outcome** remains unresolved.

The one-pass sheet preserves `:solver/:passes`; the wrapper owns that counter. Check any semantic no-progress policy there using evidence/criterion revisions. Do not count wording changes as progress. A pass may execute a whole subtree or parallel method before assessing completion; it is not limited to retrying a single leaf. See [post-execution reassessment](../../orc-design-tree/references/authored-goal-solving.md#reassess-after-the-behavior-has-run).

## Show the admitted headroom and actual evidence access

Pass completed business passes, the currently admitted pass limit and remaining passes as typed, described facts from the same host profile. State whether remaining includes the next action. Show these facts to routing and synthesis consumers when they plan remaining work; a completed counter alone does not reveal its ceiling. Exposing headroom grants no additional passes, calls or deadline and does not force an action. Verify actual values and meanings in the serialized request.

Keep method eligibility distinct from whether its evidence is obtainable or newly informative. An eligible method may return an exact unavailable observation. Under an admitted immutable snapshot, repeating the same target/status does not itself add facts; under mutable availability, retry needs an explicit freshness or re-evaluation policy. A grounded unknown may complete a decision-support goal while leaving an operational prerequisite unresolved. Assess that against the original goal, actual owner finding and evidence; do not infer either task completion or prohibition from unavailability alone.

The [consumer checkpoint](../../orc-blackboard-contracts/references/context-evidence.md#investigation-consumer-contract-checkpoint) converged after the context corrections but still repeated an identical unavailable lookup. It supports the context contract, not a universal no-progress policy, a duplicate-lookup ban or a required discovery/owner itinerary. Preserve useful partial work and distinguish the last report draft, its currentness, outstanding obligation and terminal outcome; a final work-request string alone may not deliver the assessment the user asked for.

When a partial shortlist omits a useful registered capability, expose a bounded way to inspect the eligible catalog. Keep ordinary source reads and capability-scoped reads under their own host permissions. On consultation, bind the observed method and source revision to the original obligation and return the exact source to its owner for reassessment. If exhaustive inspection shows the method is unavailable, retain independent work and qualify the affected conclusion. The real SQLite and strict request proof exercised both paths with controlled responses, including selective invalidation after the signed source changed. The first two live Luna arms did not discover the omitted capability. All 20 responses and 148 raw fields matched strict schemas, complete goal/input meanings and canonical reads/writes, and the initial owner intervals overlapped. Nevertheless, both exhausted six controller passes by repeatedly reading a policy document label as a source handle, despite retained `source-not-in-current-view` observations; neither wrote a current report. The available arm returned one read observation to its policy owner, but never inspected the catalog or invoked the archive; revision two was correctly not admitted. This is a witnessed selection/non-adaptation failure in this consumer, not evidence of dropped keys or a broken parser. The v5 author also omitted the admitted pass limit and remaining passes already required above: transport preserved every declared field, but that does not establish a complete control packet. This setup omission is not proven to be the sole cause of repeated reads. Its finalizer also substituted the last work request for an absent report, contrary to the separate artifact/remaining-work contract. Preserve those consumer gaps rather than attributing the whole outcome to the model. A model-visible catalog-inspection action alone does not establish reliable spontaneous discovery. No automatic route or larger pass bound was introduced to make it pass. Available audit (historical local experiment; private archive not bundled) Unavailable audit (historical local experiment; private archive not bundled) Held-out mechanism proof (historical local experiment; private archive not bundled)

A later one-decision catalog-exposure probe retained the exact failed controller packet. Three real Luna/MiniMax calls and 27 fields matched wire/native/canonical values. Both complete-catalog samples selected a correctly parameterized archive consultation; the MiniMax incomplete-shortlist sample repeated the unavailable source read. No consultation, owner reassessment or report ran. This shows those sampled models can select an observed eligible method without a special parser; it does not establish spontaneous discovery, and the single samples/provider-route difference prevent causal attribution. Decision-only evidence (historical local experiment; private archive not bundled)

## Recover an ineligible selection without losing the obligation

Derive the described eligible methods from trusted lifecycle state for each obligation. A pending question awaiting an answer permits its resolver; a recorded answer with a fresh resumed owner permits owner reassessment. Expose exact observation references required by that reassessment. Validate the selected method and references against current host state before issuing an owner, resolver or effect.

For a declared pre-dispatch eligibility refusal, retain a typed rejection with the original decision, actual lifecycle status, literal reason, eligible methods and required references. Supply it to the next bounded semantic assessment. The model chooses again; the host does not silently substitute another method. Count the rejected pass, preserve the final-delivery reserve, and leave prior artifacts, answers and effects intact. A rejected stop/delivery selection must not terminate the loop merely because its unaccepted route says stop. Unknown execution failures still need their explicit unavailable/handoff path.

A source-frozen authored consumer at merged ORC `4966c8e35e3bb5939570bfc645eac52a8d18eb6e` demonstrated the failure: a valid consultation choice reused the requesting owner after its answer had issued a resumed owner. The authority guard refused that stale identity, and the consumer ended the whole loop. Its 15 raw Luna responses satisfied their complete sent JSON schemas and retained the full original goal and every supplied input description; the task remained incomplete. This is a consumer control failure with faithful transport.

The focused correction passed an independently executed public ORC/SQLite trajectory and the existing construction case: 2 tests, 57 assertions. The trajectory covers answer → rejected repeated consultation → model-selected owner revisit, exact prior artifact/answer transfer, unchanged stale-owner refusal and no resolver/tool/artifact effects for the rejected choice. A live follow-up has resumed the owner and continued source discovery; complete repair and broad model reliability are still unproved. This checkpoint does not require a fixed source itinerary or automatic consultation routing.

## Carry substantive correction to the owner

For a semantic revisit, build a typed correction packet containing the original obligation and current intent revision, assessed artifact identity/version and actual content, named unmet criterion, relevant prior observation or failed attempt, current governing evidence/authority, and requested recheck. Pass only relevant sibling dependencies; retain accepted independent work and completed effect receipts outside the correction.

A finite decision may select a described criterion ID when that criterion plus the actual evidence tells the owner what to reconsider. A route such as `revise-owner` alone does not identify the defect. Use ordinary structured generation when a new question or critique is needed, and preserve its separate provenance; do not invent a finite model's explanation from its confidence.

Verify that the owner actually receives the selected criterion’s meaning. A finite label and an arbitrary schema metadata map are not proof that its explanation reaches an ordinary LLM request. Use a described criterion record (ID, meaning and rubric version) or verified model-visible instructions, and inspect the serialized input. Keep this host-authored rubric meaning separate from model-generated feedback.

Keep the failed observation as history with its attempt identity. Distinguish “no attempt in this new invocation” from “this obligation was never attempted.” After correction, reconcile only the matching fresh result and assess that replacement against the original criterion. A final unresolved status does not repair an incorrect reader-facing draft: either revise the actual delivered artifact or provide an accurate useful unresolved result.

Prove packet transmission and effect retention through the public tree boundary before claiming semantic correction. Controlled responses establish wiring; a live meaningful rejection and reassessment establish the observed repair on that case. The packet checklist addresses a source-confirmed weak revisit seam; it is not itself evidence that every model will repair correctly.

## Executable repetition and parent structure

Place these functions in `src/example/goal_controller.clj`, or change the namespace and matching `:fn` together. Register the one-pass and summary sheets before calling `build!`.

```clojure
(ns example.goal-controller
  (:require [ai.obney.orc.orc-service.interface :as bt]))

(def outcome-schema
  [:enum {:description "continue needs another pass; achieved meets the goal; other values preserve unresolved work"}
   "continue" "achieved" "needs-input" "unavailable" "exhausted"])

(def solver-schema
  [:map {:closed true :description "Business outcome and bounded pass accounting"}
   [:passes [:int {:min 0 :description "Completed controller passes; wrapper owns this counter"}]]
   [:outcome outcome-schema]
   [:gap [:string {:description "Actual unmet criterion or observed blocker; empty only when none"}]]])

(def initial-solver {:passes 0 :outcome "continue" :gap ""})

(defn account-pass [{:keys [inputs]}]
  (let [solver (:solver inputs)
        passes (inc (:passes solver))
        exhausted? (and (= "continue" (:outcome solver)) (>= passes 6))
        next-solver (cond-> (assoc solver :passes passes)
                      exhausted? (assoc :outcome "exhausted"))]
    {:solver next-solver
     :settled? (not= "continue" (:outcome next-solver))}))

(defn build! [ctx one-pass-id summary-id payload-schema record-outcome-fqn]
  (let [receipt-schema
        [:map {:description "Projected application receipt for this terminal business outcome"}
         [:operation-id :string] [:record-id :uuid] [:outcome outcome-schema]]
        board {:payload payload-schema
               :solver solver-schema
               :settled? [:boolean {:description "No further controller pass is admitted"}]
               :summary-request [:string {:description "Focused independent summary obligation"}]
               :summary [:string {:description "The independent specialist's completed summary"}]
               :outcome-receipt receipt-schema}
        solver-id
        (bt/build-workflow! ctx
          (bt/workflow "example-bounded-solver-v1"
            (bt/blackboard board)
            (bt/sequence "one-pass-and-account"
              (bt/delegate "current-goal-pass"
                :target-sheet-id one-pass-id :timeout-ms 30000
                :reads [:payload :solver] :writes [:payload :solver])
              (bt/code "account-pass"
                :fn "example.goal-controller/account-pass"
                :reads [:solver] :writes [:solver :settled?])
              (bt/condition "settled"
                :check {:key :settled? :op :equals :value true
                        :on-fail :running}))))]
    (bt/build-workflow! ctx
      (bt/workflow "example-goal-parent-v1"
        (bt/blackboard board)
        (bt/sequence "solve-and-record"
          (bt/parallel "independent-obligations"
            {:success-policy :all :failure-policy :all}
            (bt/delegate "summary-once"
              :target-sheet-id summary-id :timeout-ms 60000
              :reads [:summary-request] :writes [:summary])
            (bt/delegate "bounded-goal-work"
              :target-sheet-id solver-id :timeout-ms 210000 :max-ticks 7
              :reads [:payload :solver] :writes [:payload :solver]))
          (bt/code "record-terminal-outcome"
            :fn record-outcome-fqn
            :reads [:payload :solver :summary]
            :writes [:outcome-receipt]))))))

(defn run! [ctx root-id payload summary-request]
  (bt/execute ctx root-id
    {:payload payload :solver initial-solver :summary-request summary-request}
    :timeout-ms 240000 :max-ticks 8 :llm-call-budget 24))
```

`record-outcome-fqn` must return `{:outcome-receipt projected-receipt}` through the application's authorized command/query boundary. It records the actual outcome, including gaps and observed blockers from `:payload`; it must not report every successful tree as an achieved goal. Use [Grain application wiring](../../orc-grain-application/SKILL.md) for that boundary. Choose budgets and policies for the actual task; the numbers here demonstrate their relationship.

### Why the placement matters

The failed terminal **condition** returns `:running` because `:on-fail` is inside `:check`. That status propagates to this child root, and ORC starts its next iteration. A code function returning `{:status :running}` merely returns data; it does not control re-ticking. Do not invent a repeat decorator.

The business limit closes still-open work at pass six, before the child's engine cap of seven. The outer root is capped at eight because a delegate's effective cap is the smaller of its requested cap and its parent's remaining iterations. A root cap of two would cap this child at two even though the delegate requests seven. Nested parents must leave enough remaining iterations too. Family call budget and remaining deadline are additional limits; child options cannot grant new family budget.

Only the solver root returns Running. The outer parallel root does not repeat, so the successful summary is retained while the solver invokes more specialists. Disjoint writes are necessary; durable effect identity is still required inside any branch that can be revisited. With these `:all`/`:all` policies, one failed branch plus one successful branch eventually produces parent failure, not a successful terminal receipt. If the application promises a durable handoff for execution/provider failure as well, add an explicit eligible failure-handling path; the illustrated business-outcome path covers successfully reported unresolved work.

This independent summary is outside the solver's acceptance dependencies. When a semantic completion decision needs every parallel output, move that join and reassessment into the one-pass sheet. Guard already completed obligations there by their current postconditions/receipts so another pass retains healthy work rather than rerunning it.

## Boundary errors to prevent

- Optional omission and nil differ. A declared delegate read may arrive as nil when the parent has no value. If absence is valid, use `[:maybe concrete-schema]` and handle it, or omit that read on a separate eligible route. A required-map child can otherwise fail before its first node.
- Every declared code write must be present and non-nil on every successful branch. Return one concrete outcome envelope or complete write sets; an optional receipt should not be an unconditional non-null write. Check shape before committing an effect whose result would then be rejected.
- Code receives declared values under `:inputs`; runtime capabilities are on the received context itself (also supplied as `:execution-context`). Use `[{ :keys [inputs] :as ctx}]`, not an assumed nested `:context`. Install capabilities in asynchronous worker/poller context as well as the calling thread.
- Tool caller builders receive `(blackboard context)` and return the caller. Normal authored tool/code invocation uses `(caller tool-name arguments)`. Declare `:tool-contracts` with `:arguments` and `:result`; read [current invocation](../../orc-tools/references/current-invocation.md) before using other execution modes.
- Resolve durable node names and outputs through their actual events/public queries. Completion events do not inherently include human-readable node names. A mismatched measurement harness is not an engine failure.

## Source and proof boundary

Inspected at ORC `9f7c27357b9f9bcf765fbd86baaedf5911261cd8` (containing main `1851212396cced2fc4533fda30b824bd4b691390`). Source anchors under `components/orc-service/src/ai/obney/orc/orc_service/core/`: `dsl.clj` (`code`, `condition`, `delegate`, `parallel`); `todo_processors.clj` (`execute-delegate-node`, parent remaining tick cap; `execute-condition-node`, nested `:on-fail`; `complete-tree-tick`, repeated iterations and terminal exhaustion); `executor.clj` (`execute-code`, context and complete write validation); `tool_invocation.clj` (`guarded-call-tool-fn`).

A controlled-provider integration demonstrated repeated A → B → A, fresh evidence transfer, retained independent work and projected receipts at this pin. That is mechanics evidence, not live semantic quality. This wrapper is an illustrative adaptation; run the consuming application's own registered sheets, inspect exact fresh requests and query projections, then report live and restart-recovery evidence separately. Historical composition guidance also inspected main `490816267b76b0428c53437052980a25f5905209`; do not transfer an old defect claim to a newer checkout without reproducing it. Current adverse-family evidence belongs in [tool proof limits](../../orc-tools/references/current-proofs-and-limits.md).

## Repeated parallel at the inspected local repair pin

Source recheck at merged ORC `250de6e6de2ad95647ef6da4c69220c2bd631279`: `orc_service/core/todo_processors.clj`, the `:parallel` branch of `handle-child-completion`, still counts children and records completion inputs with execution context, but its append CAS selects only tick/node and tests whether any completion exists, without filtering execution context. The #45 merge therefore does not supersede the affected-path workaround below. This was independently reread by the root reviewer; it is source verification, not a fresh runtime reproduction.

The merged `4966c8e35e3bb5939570bfc645eac52a8d18eb6e` source still uses the tick/node-only completion guard in `handle-child-completion`. The parallel branch counts children using the execution context and stores that context on the completion. Its append predicate treats any earlier completion of the same tick and node as a duplicate. The current pin’s source therefore still supports using a fresh delegate tick around repeated parallel work. The runtime reproduction below used the older pin.

A deterministic public reproduction at the local ORC repair stack based on `9f7c27357b9f9bcf765fbd86baaedf5911261cd8` exposed a repeated-parallel join defect: iteration two's children completed, but the join's append guard counted iteration one's completion under the same tick/node. The guard omitted execution-context identity. This was independently reproduced without models; longer timeouts do not repair it. Recheck consuming source before transferring this limitation to another revision.

At that affected pin, place a parallel pass in a delegated sheet invoked once per child tick. A repeated controller gets a new child tick for each pass. Keep explicit validity guards so completed independent work and effects remain retained; a new tick alone does not prevent duplicate work. This contains the defect rather than repairing it. Preserve the failing reproduction and hand off the library fix with coverage for duplicate suppression within one invocation and completion across distinct iterations.

## Account for work inside each pass

A six-pass limit can consume more than six model calls: each pass may route and then generate arguments, followed by intake or final reporting outside the loop. Calculate the maximum admitted path including diagnostics, rechecks, retries and descendant calls; leave budget for the explicit terminal explanation. The business bound must settle before the family call cap. Raising only the tick ceiling does not fix a smaller call budget.

For experiments, account for every case sharing an admission ledger: an earlier case can consume the allowance needed for the next case’s correction. Preserve admission failure as a harness limit rather than evidence that the semantic repair failed. An explicitly authorized new application continuation is distinct from automatic recovery of a failed tick.

Align model-call, child, controller and root deadlines. A child timeout shorter than the admitted model call can fail while that request is still running. Budget-bound stopping and deadline exhaustion are different outcomes; retain completed effects when either occurs. A no-op/retain action is not evidence of progress toward an unresolved sibling goal. Carry the actual last action observation and outstanding obligation into reassessment, and stop with useful unresolved work when no admitted continuation can change it. Do not prescribe a fixture-specific route to escape a loop.

## Keep reassessment feedback current

When an owner records a fresh finding, update the controller's last selected-action observation in the same accepted transition. An old failed read remains historical evidence, but must not masquerade as the result of the newer reassessment. Initial parallel owners should not race to overwrite one global feedback slot. Carry original goal scope, admitted/remaining work, exact source-handle semantics and invocation-relative citation meanings in the described contracts. A work-request asking an owner to read a document is not itself execution of a separate read tool.

Judge completion against the declared goal. A decision-support report may be complete while explicitly reporting that one factual answer is unknown; this does not authorize an external action or make that factual question resolved. Preserve both the report outcome and the unresolved finding rather than rewriting one into the other.

The corrected authored discovery pair at ORC4966c8e ran30 real Luna responses/197 canonical fields: available evidence led to catalog inspection, registered consultation, owner revisit and selective revision; unavailable operative authority remained unknown in the delivered report. This is one model/pair and several combined contract corrections, not individual-fix causality or general reliability. The authored discovery review is a historical local experiment; its private archive is not bundled.
