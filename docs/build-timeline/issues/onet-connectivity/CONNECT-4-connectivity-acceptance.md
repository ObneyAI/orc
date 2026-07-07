# CONNECT-4 — Connectivity acceptance: occupation↔skill/task cross-sheet edges on real O*NET

**Type:** AFK · **Blocked by:** CONNECT-2 + CONNECT-3 — craft after both land.

## What to build
A durable acceptance (pure verdict fn + a bounded live O*NET driver) that proves graph B is CONNECTED **and confirms CONNECT-3c on a REAL build** — the edges must attach to the CANONICAL `occupation/<SOC>` nodes (the unit test proved the SCHEME; the live build confirms the full URI matches the canonical occupation minted from Occupation Data). Measured directly from the post-CONNECT-3c build store via `onet_overmint_forensics` edge-scan: occupation cross-sheet edge-participation > 0; edges' source-uris are `occupation/<SOC>` (canonical), NOT `entity/<SOC>` stubs; a sampled canonical occupation reaches ≥1 skill AND ≥1 task/knowledge across sheets; BFS `occupation/<SOC>` → element → a DIFFERENT `occupation/<SOC>` traverses.

**Perf caveat (known, separate):** the CQ loop re-embeds the whole graph ~13× per iteration, so a full build is slow to terminate. Connectivity edges come from EXTRACTION+LANDING (upstream of the CQ loop), so the driver runs the bounded build, waits for extraction+landing to complete (embedding begins), then reads the edges forensically from the persisted store — it does NOT depend on the CQ loop finishing. Bounded caps (`:max-containers 10 :max-windows 5`).

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
