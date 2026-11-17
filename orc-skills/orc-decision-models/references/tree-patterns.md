# Decision patterns for hand-assembled trees

These sketches describe responsibilities, not literal DSL. Read the consuming ORC DSL before translating. At ORC `1851212396cced2fc4533fda30b824bd4b691390`, use `llm-decision` for one finite answer through conversational or registered native providers, and `llm-condition` for boolean eligibility. Ordinary `llm` leaves remain appropriate for explanations and dynamic argument maps; custom code clients are for unsupported native primitives.

## Guarded routing to finite tools

```text
Sequence
  collect evidence and legal candidates
  assess route + hypothetical finite arguments
  validate response, freshness and joint plan; write top-level route
  Fallback
    Sequence condition route=lookup  -> code lookup tool -> verify projection
    Sequence condition route=write   -> delegate authorized write workflow
    Sequence condition route=clarify -> clarification action
    Sequence condition route=defer   -> stronger-model or information specialist
    Sequence condition route=fault   -> declared provider recovery
```

Separate observed semantic no from uncertain, invalid and unavailable. If the parent postcondition includes obtaining clarification, that branch may succeed honestly; if the parent promises a completed domain action, clarification remains a distinct incomplete outcome. A guarded fallback expresses alternatives, not permission to deliver fabricated success. A selected tool failure must enter that method's declared recovery or remain failure. Guard the final no-tool branch too: an unconditional no-tool leaf can turn a failed eligible action into successful no-tool handling. A native assessment returning valid data can succeed while its host policy rejects the proposed action. Static ORC conditions compare top-level blackboard keys; materialize a nested route with code or write it directly as a typed key.

Only consume arguments for the selected tool. A Choice cannot supply a newly written message or unrestricted refund amount. Route such work to an LLM action or exact extraction/computation with its own typed contract. An LLM can select a hand-assembled tree of code tools through `delegate`; tools do not need to be dynamically generated or exposed to a researcher merely to be used.

## Three kinds of parallel work

| Pattern | What shares a snapshot | What still needs validation |
| --- | --- | --- |
| One native batch | Route, candidate arguments, independent checks | Each question explicitly references known evidence; selected joint plan is legal |
| ORC `parallel` specialists | Several leaves/subtrees using declared inputs | Disjoint writes, result aggregation, error/partial policies and state version |
| Concurrent effects | Actions that can overlap | Compatibility, permissions, idempotence, freshness and projected outcomes |

Independent control axes can be assessed together when they use the same snapshot; assess explicit hypothetical action heads and recheck plan freshness/per-step eligibility before dispatch. This does not justify executing incompatible specialists together. Do not infer effect cancellation from a parallel parent's early success.

Questions in one Jev call do not see sibling answers. “Will the action you chose cause failure?” is under-specified. Either ask explicit hypothetical checks per candidate and consume the selected check, or first select the action and then assess a new state containing it. Fetching a chosen document and evaluating its contents likewise needs a sequence. A stale response should refresh/reassess or defer before the effect; a correct old judgment is not authorization to act on changed evidence.

## Completion checks and bounded repair

```text
generate artifact -> obtain external evidence -> assess atomic requirements
  -> host route accept | targeted repair | refresh evidence | defer
  -> repair specialist when eligible -> reassess revised artifact
  -> deliver only when parent postconditions hold
```

Ask separate questions for individual requirements, unsupported claims, contradictions and missing evidence. Host policy maps findings to specific follow-up work and preserves conflicting answers. Do not combine high supported and high contradicted judgments into unconditional acceptance. Assessment of an agent's prose is not proof a test ran or a command effect happened; use actual tool logs and domain projections as evidence.

Set repair bounds and counters in deterministic structure. Rechecks must reference the revised artifact/evidence version. If the bound is exhausted, return the declared incomplete/defer outcome. Stagnation supervision and task completion are separate contracts; progress does not mean done. Keep intervention severity and provider availability observable instead of turning every recommendation into generic failure followed by a blind retry.

## Wide evidence and ranking

Retrieval tool -> stable candidate records -> independent relevance/support assessments -> validate coverage -> rank/select -> fetch full source -> synthesis delegate. Choice selects the relative best among offered candidates; an existence/answerability judgment can detect that none is useful. Independent per-candidate scoring is preferable when an absolute relevance rubric matters. Do not directly compare normalized Choice probabilities from different candidate groups.

Track input, assessed, omitted and failed candidate IDs. Missing judgments are unavailable, not zero relevance. Choose explicit full-batch failure or typed partial coverage; do not silently mix fresh decision scores with fallback retrieval scores. Snippets, PDF text and compressed passages need truncation/coverage metadata, and may omit decisive exceptions. Preserve original source identity and read full passages where needed. A stricter evidence gate can reduce useful coverage; evaluate the resulting end-to-end task rather than assuming more checks help.

## A rejected check is not a diagnosis

A finite completion verdict can be useful without free text. It does not explain which criterion failed. If a retry needs a diagnosis, either assess explicit atomic criteria with known owners, or invoke an ordinary LLM diagnostic step with the actual goal, current artifacts, qualified evidence, phase and observed effects. The diagnostic must identify an evidenced deficiency and eligible repair target, or explicitly report verifier disagreement. It must not invent the finite model's hidden reasoning.

Reply-only defects should revisit the writer, not replay valid service effects. Preserve the authoritative receipts during disagreement. Recheck against current evidence within the authored bound; if disagreement remains unresolved, report that limit without denying actions that demonstrably occurred. Evaluate false rejection as well as false acceptance: adding a checker can reduce useful task completion when its evidence or criterion is ambiguous. Probabilities alone neither prove satisfaction nor supply a repair cause.
