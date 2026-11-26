# Typed decisions and execution evidence

Current API guidance is source-inspected at ORC `1851212396cced2fc4533fda30b824bd4b691390`. Use explicit Malli types and descriptions on every shared, intermediate and parent blackboard key. Keep evidence, finite answer, host policy and observed effect distinct.

## Predicate versus data

`llm-condition` has reads and no canonical answer write. It returns success for true and failure for false. Completion/details retain `:condition-answer`, inputs, model and usage. A semantic false has no provider `:failure-kind`; malformed/transport failures do. A fallback cannot distinguish them by status alone.

`llm-decision` is a leaf with exactly one answer write. Boolean false and a declared none/clarify are successful assessments. Missing, malformed and out-of-set answers fail without writes. Its `:decision` record retains offered options, original answer, abstention/set-aside answer and provider evidence when supplied. Ordinary reasoning-first `llm` conventions do not add a second write here.

Static answer schema is boolean or described enum. Runtime options use `:options-from` referencing a declared read with a nonempty vector of `{:id :description}`, and the answer may be string. Literal IDs survive; schema validity alone does not establish authority, eligibility or freshness. Reject duplicate/missing catalog identities at the application boundary. Runtime `:abstain` must be offered.

Confidence floor requires abstain. At this pin the public builder incorrectly rejects boolean `:abstain false`: its membership check treats the matching false value as no match. This is a reproduced build defect, not an unsupported false answer; boolean decisions without this option still retain valid false as successful data. Use an explicitly described enum with clarify/defer when abstention is needed, and retain this repair obligation. Missing confidence triggers abstention; native Noul probability is not automatically transformed into confidence. Preserve both assessed answer and written policy outcome. Define uncertainty rules from task error costs, not a universal threshold.

## Executable enum route

```clojure
(orc/sequence "main"
  (orc/llm-decision "route"
    :model "fast-decisions"
    :instruction "Choose the eligible described capability; choose none if none applies."
    :reads [:request :catalog] :options-from :catalog :writes [:route])
  (orc/fallback "dispatch"
    (orc/sequence "lookup-branch"
      (orc/condition "lookup-selected" :check {:key :route :op :equals :value "lookup"})
      (orc/tool "lookup" :tool "lookup" :reads [:record-id] :writes [:record]))
    (orc/sequence "none-branch"
      (orc/condition "none-selected" :check {:key :route :op :equals :value "none"})
      (orc/llm "clarify" :instruction "Explain what task information is missing."
        :reads [:request :catalog] :writes [:clarification-reasoning :clarification]))))
```

This node fragment requires described schemas for every referenced key, a runtime catalog including lookup/none, and an injected permitted tool caller. It is not a standalone runtime. Guarding the last branch prevents a failed lookup from becoming successful no-tool handling. If clarification does not satisfy the parent's promised domain action, return a distinct incomplete outcome. Use [tools](../../orc-tools/SKILL.md) for argument/result contracts and effect proof.

## Evidence and accounting

Inspect these boundaries separately:

1. Actual evidence, catalog identity and any truncation/coverage supplied to the node.
2. Provider-specific serialized HTTP request, not a guessed SIO prompt or fake map capture.
3. Parsed/raw response, resolved model, reported probabilities/usage and provider request identity when available.
4. Validation, abstention, selected branch and actual arguments.
5. Accepted command events and projected effect bound to the requested operation.

Registered `:protocol :decision` boolean/Choice calls use the normal provider execution path and shared family budgets. `get-family-usage` reports descendants once; compare it to captured physical requests and preserve reported usage/cost only when supplied. Do not overwrite resolved model with a benchmark cohort label. Root budget is an execution call budget, not automatically a global physical-attempt cap across provider retries or unrelated roots. Custom code HTTP clients still need explicit application monitoring and budgets.

Keep exact tenant/tick/node instance/attempt correlation. Retain options and policy/rubric versions. Exclude credentials; HTTP exceptions can contain request headers and payloads. Use allowlisted failures and verify canonical completion events. Read [model/SIO](../../orc-model-sio/SKILL.md) and [observability](../../orc-observe-debug/SKILL.md).

## Acceptance

Cover selected/none/false outcomes, literal runtime identities, missing confidence, malformed outputs, outage, stale evidence, joint incompatibility, failed tools and bounded repairs. Verify no unintended effects. Synthetic provider fixtures establish wiring; live labeled cases establish observed task quality. Preserve first-attempt failures and assisted repairs separately; no successful call proves general calibration.

## Current adverse evidence

Registered model admission and normal completion were exercised live. Preserve the open boolean-false abstain build defect and family cancellation/readiness defect. Native decision completion model identity is resolved; ordinary generation completion model identity is configured/requested, so capture actual response metadata separately. Read [current proofs](current-proofs-and-limits.md) for the exact evidence limits.
