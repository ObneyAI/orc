# CONNECT-4 — Connectivity acceptance: occupation↔skill/task cross-sheet edges on real O*NET

**Type:** AFK · **Blocked by:** CONNECT-2 + CONNECT-3 — craft after both land.

## What to build
A durable acceptance (pure verdict fn + a bounded live O*NET driver) that proves graph B is CONNECTED: occupations participate in cross-sheet edges to their skills/knowledge/tasks/abilities (not 0 edges as measured today via `onet_overmint_forensics` edge-scan). Measured directly from the build store (occupation edge-participation count > 0; a sampled occupation reaches ≥1 skill/task/knowledge across sheets). Bounded caps (6 containers is fine — enough now that the ranking works + relations exist) so it completes in minutes, not the 100-min default-cap run.

## Acceptance criteria
- [ ] Pure verdict fn: given a graph summary, PASS iff occupation cross-sheet edge-participation exceeds a threshold (occupations are not isolated).
- [ ] Live bounded O*NET build: occupations carry `requires`/`performed_in_occupation`/skill/knowledge edges across sheets (edge-scan: occupation edge-participation > 0; before this line it was 0).
- [ ] A sampled occupation (e.g. by SOC) resolves to ≥1 skill AND ≥1 task/knowledge across sheets.
- [ ] The connectivity is DETERMINISTIC (MC-6 from source relations), reproducible across runs — not dependent on LLM ranking variance for the connection itself.
- [ ] Ontology brick gate green; mt7c stays green.

## Disciplines (verbatim — a subagent MUST NOT skip these)
- **Never assume. Chase to ROOT CAUSE.** No band-aids. Rule out the harness (a green acceptance that measured the wrong store/ontology-id is a false green — verify the ontology-id from the tags, as the forensics caught twice).
- **TDD for real logic: red → green → refactor.** Test behavior through public interfaces.
- **Injected-capability seam pattern.**
- **Durable tests AND live QA** — the live bounded build IS the proof; turn the connectivity check into a durable verdict.
- **Dispatch to fresh agents, then INDEPENDENTLY + ADVERSARIALLY verify** — re-read the edges from the store yourself, confirm they're cross-sheet occupation edges, not within-sheet noise.
- **Report faithfully.** Domain-agnostic. Commit-LOCAL only. JVM hygiene (`pgrep -f`, one at a time, detached, 0 orphans).
