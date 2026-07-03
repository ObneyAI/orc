# Handoff — MT-7c: acceptance — unfragmented comprehensive build, CQ-gate as the cross-run criterion

**Issue:** [`../issues/meaningful-multi-table-extraction/MT-7c-acceptance-unfragmented-build.md`](../issues/meaningful-multi-table-extraction/MT-7c-acceptance-unfragmented-build.md)
**Design:** ADR-0001 (settled). **Blocked-by:** MT-7a (`71861f75`) + MT-7b (`68a12cd2`) — LANDED + live-verified.
**Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. ONE bounded build at a time; `pgrep -f` hygiene. **HITL — the final verdict goes to the user.**

## What this slice is
The end-to-end acceptance for the vocabulary-binding line: TWO clean solo comprehensive O\*NET builds through the FULL composed pipeline (the eb12 central evolver: survey → derive-CQs → synth-vocab → model → MT-2 select → extract with binding+proposals → merge → land → embed → CQ-gate), measured by:

1. **Within-run fragmentation GONE (gating, per run):** zero case/variant URI-scheme splits (two distinct schemes normalizing to one — the exact 7a-fixed signature, e.g. `Skill/` vs `skill/`, `job-zones/occupation` vs `occupation`); the same-entity split does not recur. (A small GC-1 unrecoverable-keying degrade tail — the known 0.3% family — is reported as a diagnostic, distinguished from freelancing.)
2. **Honest terminal (gating, per run):** `:complete` or `:failed-cq` — never crash/timeout/OOM.
3. **CQ-gate answered (gating, per run, the ADR criterion):** ≥1 `:pass` CQ verdict (the GC-11c precedent — the semantic gate, not structure).
4. **Cross-run structural sameness: NON-gating diagnostic** — the two runs' vocabularies/grains may legitimately differ (record them; convergence is the later persistence-as-prior lever).
5. **Capability-based A2-vs-B (report, not gate):** facet retrievability on B's occupations vs the measured A2 baseline (topSkills/topKnowledge 4%, jobZone/riasec 46% over 880) — comparing what each graph carries, acknowledging structural difference honestly.
6. **Ledgers + honest failures surfaced per run** (freelanced counts, proposals, per-container failures — a hollow "unfragmented via mass failure" must be visible, #2).
7. **Domain-agnostic scan:** grep the MT-7a/7b IMPLEMENTATION (not drivers) for domain names.

## /tdd (small): a pure `mt7c-acceptance-verdict`
`(verdict {:runs [run1 run2]})` where each run = `{:status … :schemes {<scheme> count} :cq-verdict […] :containers-failed n :containers-processed n}` → `{:pass? :reasons […]}` with the gating criteria above + the non-gating diagnostics (structural-sameness, scheme counts, failure ratio). Fixture-tested: a good pair passes; a variant-split run fails; a no-CQ-pass run fails; a mass-failure run surfaces the hollow-pass diagnostic; differing-vocabulary runs still pass when both are internally clean.

## The driver
Extend the eb12/mt5 machinery (`run! {:only [:onet] :max-containers 12 :store :sqlite}` + heap ~6g): read the landed projection per run (concept URI schemes via the first-`/` prefix, normalized via `vb/normalize-name` for the split check), the `:cq-verdict`, the per-run stats; run the pure verdict over both; print the capability table vs the A2 baseline (reuse `mt5-acceptance/a2-baseline`). Runs are SEQUENTIAL (one bounded build at a time), each on a fresh ctx.

## Do NOT touch
Production code — this slice EXERCISES it. A root-caused defect it surfaces gets its own fix+test, reported to the user first (HITL).

## Core Disciplines (binding — verbatim)
1. NEVER assume; NEVER "variance/transient/flaky" — root-cause every unexpected behavior. 2. Verify QUALITY not completion — hunt the hollow pass (unfragmented-via-mass-failure; empty-set PASS). 3. Instrument when a symptom resists hypothesis. 4. "It ran" is the FLOOR; live REAL everything; no false green. 5. No silent fallbacks. 6. TDD the verdict fn. 7. Assert via projections; no bare appends. 8. Re-orchestrate (the composed pipeline), don't fork. 9. Adversarial verdict; honest negatives. 10. Verify the deterministic skeleton AND the LLM-discovered vocabulary quality. 11. Key = env var; never truncate model output; JVM hygiene (`pgrep -f`, one build at a time, 0 orphans after). 12. Domain-agnostic — scan the impl, not the drivers. 13. `:reasoning` first on any `:llm` node.
