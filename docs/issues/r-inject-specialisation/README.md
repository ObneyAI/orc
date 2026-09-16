# R-Inject specialisation — domain children on the identity waterfall

Make the learning identity specialise by domain: a task that matches a shape class on fitness but whose domain the
reranker judges uncovered gets its own stable class under that shape, and the loop learns per domain.

**PRD:** [`docs/prd/r-inject-specialisation.md`](../../prd/r-inject-specialisation.md)
**Decisions:** [`docs/build-timeline/grill-sessions/r-inject-specialisation-decisions.md`](../../build-timeline/grill-sessions/r-inject-specialisation-decisions.md) — D1–D6
**ADR:** [0006 — domain children are minted at classification from a judged coverage verdict](../../adr/0006-domain-children-are-minted-at-classification-from-a-judged-coverage-verdict.md)
**Spec:** `specs/ontology.allium` — `contract TaskClassification`, `enum DomainCoverage`, `value DomainVerdict`, `rule MintDomainChild`
**Evidence:** `development/bench/ood-stress-results/2026-09-15_111739-classify-only-post-emergence-loop/FINDINGS-V3.md`
**Branch:** `feature/r-inject-specialisation`, off the RR-durable arc at RR-33

## Slices

| Slice | Type | Blocked by |
|---|---|---|
| [RS-P1](RS-P1-prototype-the-reranker-answers-the-separated-question.md) — *Prototype:* the reranker answers the separated question | HITL | — |
| [RS-P2](RS-P2-prototype-a-parent-edge-can-be-born-from-the-claim-path.md) — *Prototype:* a parent edge can be born from the claim path | HITL | — |
| [RS-P1b](RS-P1b-prototype-the-reranker-reuses-a-sibling-label.md) — *Prototype:* the reranker reuses a sibling's domain label when one fits | HITL | RS-P1 |
| [RS-1](RS-1-the-reranker-gives-a-domain-verdict-beside-fitness.md) — The reranker gives a domain verdict beside fitness | AFK | RS-P1, RS-P1b |
| [RS-2](RS-2-the-classifier-mints-a-domain-child-from-an-uncovered-leaf-match.md) — The classifier mints a domain child from an uncovered leaf match | AFK | RS-1 |
| [RS-3](RS-3-the-domain-child-exists-durably-from-birth.md) — The domain child exists durably from birth | AFK | RS-2, RS-P2 |
| [RS-4](RS-4-r-inject-renders-the-waterfall-top-down.md) — R-Inject renders the waterfall top down | AFK | RS-3 |
| [RS-5](RS-5-the-chain-reaches-retrieval-and-harvest.md) — The chain reaches retrieval and harvest | AFK | RS-3 |
| [RS-6](RS-6-integration-live-proof-and-truth-pass.md) — **Integration, live proof and truth pass** | HITL | all |

## Handoff cadence

A brief is written only after its blocker has landed and been inspected, from real signatures: RS-1 after RS-P1;
RS-2 after RS-1; RS-3 after RS-2 and RS-P2; RS-4 and RS-5 together after RS-3, dispatched one after the other; RS-6 is
orchestrator solo. Before each brief the orchestrator runs `/propagate` scoped to the slice's obligations and confirms
the generated tests are red. Implementers are Sonnet 5, one at a time; every slice gets `/inspect-orc` before its
ledger and commit.
