# Configured model fallback

## Problem and result

Safe provider evidence alone leaves each consumer responsible for selecting the exact failed attempt and building a compatible candidate chain. Provide an ORC-owned configuration path for ordered ordinary LLM candidates, backed by durable behavior-tree execution. Extend PR #48; keep it open for review.

## Behavioral scope

- One authored task supplies common instruction, described reads/writes, and generation defaults. Each ordered candidate supplies an explicit model and optional compatible generation overrides. Invalid configurations fail before execution.
- Temporary-transport admission is the conservative default. A separately named all-transport policy is explicit compatibility behavior. Unknown or contradictory evidence, schema errors, valid negative answers, permission blocks, cancellation and exhausted budgets do not become temporary outages. No error-message matching in ORC.
- Advance only from the exact candidate completion in the owning tenant, tick, node and execution context. Respect final node retry outcome; duplicate/stale deliveries cannot admit another request. Parallel items and concurrent users remain isolated.
- Use existing execution-family call accounting and inherited absolute deadline. No new task, reset allowance, or replay of preceding effects when changing models.
- Keep every attempted candidate and safe failure reason visible in canonical events and public projections. Preserve the selected candidate and useful terminal failure on denial/exhaustion. Never invent a served-model identity.
- Ordinary blocking LLM generation is the delivery boundary. Partial streams, custom native client classification, cross-provider credential routing and arbitrary model compatibility are not silently promised. SIO changes require a demonstrated missing capability.

## Implementation sequence

1. Root records behavioral obligations in the existing Allium contract and test ledger. Inspect DSL persistence, snapshot/export and normal/ephemeral routing before finalizing the API.
2. Build one reusable public constructor from existing ORC primitives. Centralize policy at the engine's exact completion boundary rather than copying consumer event scans. Keep ordinary fallback behavior compatible.
3. Focused proof through public authoring/execution, real Grain datastore and local HTTP: ordered success/exhaustion; valid false and permanent/schema denial; concurrent ownership; mapped/delegated execution; call/deadline exhaustion; duplicate completion control. Check canonical inputs, outputs and public trace, not only return values. Add deterministic public configuration/round-trip checks where runtime proof cannot cover malformed definitions.
4. Independently inspect source and rerun the focused proof once. Compare Allium diagnostics with the existing baseline and record uncovered obligations honestly. Do not run broad suites after each edit.
5. Update the portable ORC skill with the proven public API, policy choices, model options, trace inspection, adoption/pin caveats and explicit support limits. Have a fresh agent follow the recipe. Update PR title/body using the PR description skill; do not merge.

## Checkpoints and status

- Planning: source and original handoff review complete. Public `llm-fallback` compiles ordinary candidates under a durable policy-bearing fallback. Candidate configuration is frozen with each execution.
- Implementation: complete and independently verified in the dedicated PR worktree.
- Proof: 11 tests/288 assertions passed independently. Fresh-agent recipe passed with three local HTTP calls; public trace and canonical evidence agree. Skill promotion and PR publication follow this acceptance.

## Work boundaries

Worktree: `/private/tmp/orc-fallback-pr`, branch `fix/safe-provider-evidence`. Preserve the ontology checkout and all other lanes. Root owns Allium and acceptance records; delegated implementation must not edit specs or weaken existing tests. Use the smallest failing behavioral witness before implementation and one independent focused proof after it; no ritual per-edit suites. No paid model calls are needed to establish outage routing. Semantic quality of any chosen real backup requires its own task evaluation.

## Public authoring shape

```clojure
(orc/llm-fallback "assess"
  :candidates [{:model "provider/primary"}
               {:model "provider/backup" :options {:max-tokens 32768}}]
  :policy :temporary-transport
  :instruction instruction
  :reads [:request :evidence]
  :writes [:assessment-reasoning :assessment]
  :options {:output-mode :json-schema})
```

Candidates default to one node attempt and zero provider retries. Explicit bounded retry configuration must retain physical-request accounting. Each running execution retains its original candidate snapshot while another version runs concurrently. Shared privacy settings must not disappear when candidate options merge. No candidate may override the shared task instruction/read/write contract.

## Inspection discoveries addressed by this slice

The existing generic fallback continues on every failure without a policy gate; its successor startup carries execution context but not all mapped task overrides; duplicate completion delivery lacks the sequence continuation guard. Its ephemeral evaluator also discards evidence. Policy-bearing fallback therefore needs a durable boundary, owned continuation, attributed write sources, and terminal reasons. Generic fallback semantics remain unchanged except any narrowly justified shared correctness fix.

## Source handoffs

Original portable contract: `orc-skills/handoffs/model-fallback/02-repair-handoff.md`; evidence/status: `experiments/model-fallback/ordinary-repair/HANDOFF-COVERAGE.md`. The historical consumer evidence is a reference, not new application migration authority.
