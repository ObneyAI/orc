# Handoff — CONNECT-2: Excel `:relations` op (heuristic shared-key cross-sheet relations)

**Issue:** `docs/build-timeline/issues/onet-connectivity/CONNECT-2-excel-relations-op.md`. **The upstream root fix** for O*NET disconnection: excel's container-contract `:relations` is nil (proven), so MC-6 never derives cross-sheet edges → occupations 0 edges. Implement it (excel gets what SQL/CSV have).
**Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. `pgrep -f` hygiene; detached `nohup … &` for gates.

## Read first (understand the exact contract before coding)
- `components/orc-service/src/ai/obney/orc/orc_service/core/source_tools_sql.clj:269+` — the SQL `:relations` op (MC-3): the SHAPE it returns. **Match this shape exactly** so MC-6 consumes it unchanged.
- `components/orc-service/src/ai/obney/orc/orc_service/core/source_tools_csv.clj:319-343` — CSV `:relationship-hints` (`{:from :to :kind :note}` from shared/similar column names) — the HEURISTIC pattern to mirror.
- `components/ontology/src/ai/obney/orc/ontology/core/extract_subbehavior.clj:875-911` — MC-6 (`source-relations-fn` + the join): the CONSUMER. `source-relations-fn` binds `(:relations contract)` and calls `(relations container-name)` expecting `[{:from :to :via} …]`. MC-6 joins entities across containers sharing the `:via` key VALUE (recovered from each concept's `:attributes`). Your op must return that shape.
- `components/orc-service/src/ai/obney/orc/orc_service/core/source_tools.clj` — the `container-contract` builder: where each format's ops are wired onto the contract map (`:list-containers :sample-rows :stream-all :relations`). The excel branch sets `:relations` to nil today — wire your op here.
- `components/orc-service/src/ai/obney/orc/orc_service/core/source_tools_excel.clj` — `do-excel-dir-sheets` / `excel-dir-sheets` (lists files + sheets + how to read headers). Your op reads sheet HEADERS via the existing header/column capability here (do NOT re-implement excel reading — reuse `sheet-columns`/the header reader).

## The exact change
Add an excel `:relations` op = a fn `(container-name) -> [{:from <str> :to <str> :via <str>} …]` (MATCH the SQL shape MC-6 consumes; if SQL uses `:from "A.col"`/`:to "B.col"` qualified names, match that — verify against MC-6's join which reads `:via` + matches key VALUES from attributes). Deterministic, domain-agnostic heuristic:
1. Enumerate the dir's sheets + each sheet's HEADER columns (reuse the excel header capability — inject it as a capability defaulting to the real reader, faked in tests).
2. Detect **shared KEY columns**: a column NAME (normalized case/whitespace, tolerant) that appears in ≥2 sheets AND is key-shaped — bias toward ID/code-like columns (short, code/id/"code"/"id"-suffixed or high-distinct-low-freetext), NOT free-text/measure columns. Name NO specific column — purely structural.
3. For the queried `container-name`, emit one relation per OTHER sheet sharing a key column: `{:from container-name :to other-sheet :via <shared-column>}`. Return `[]` honestly when none (never fabricate).
4. Wire it into the excel container-contract in `source_tools.clj` so `(:relations cc)` is this fn.

## TDD cycle (tests FIRST, red→green, one at a time)
1. **Op present:** `(:relations (container-contract {:type :excel :path <fixture-dir>}))` is a fn (RED: nil today).
2. **Shared key → relation:** a synthetic 2-sheet fixture both carrying a made-up `"widget_id"` column → the op returns a relation with `:via "widget_id"` between the two sheets. (Use a tiny temp xlsx-dir fixture, or fake the injected header-reader capability — prefer the faked capability for a pure unit.)
3. **No shared column → `[]`:** two sheets sharing NO column → `[]` (honest, no fabrication).
4. **Key-shaped bias:** two sheets sharing a free-text/measure column (e.g. `"Description"`/`"Data Value"`) but no id-like column → NOT related (avoid joining on noise); sharing an id-like column → related.
5. **Real O*NET (live QA, durable):** on `/Users/darylroberts/Downloads/db_30_1_excel`, an occupation-bearing sheet is related to a skills/abilities/tasks sheet via the shared SOC-code column (assert a relation whose `:via` is that column exists). Turn this into a durable test guarded on a small committed-or-fixture header sample if the real dir isn't always present.
6. **Consumer wiring:** `source-relations-fn` (extract_subbehavior) returns a FN for an excel source (was nil).

## Do NOT touch
MC-6 itself (extract_subbehavior — it's the consumer, unchanged), the SQL/CSV relations, the other excel ops (`:list-containers`/`:sample-rows`/`:stream-all`), the grain (CONNECT-3), CONNECT-1's normalizer. Reuse the excel header capability — no fork of excel reading. No baked domain column names.

## Live QA the orchestrator will run (after inspect)
On the real O*NET dir, confirm the op returns SOC-based relations; the bounded connectivity build (CONNECT-4) confirms MC-6 then emits occupation edges — NOT this slice's gate, but this slice UNBLOCKS it.

## Gate + hygiene
`clj -M:poly test brick:orc-service` (the op lives in orc-service) AND `clj -M:poly test brick:ontology` (MC-6 wiring) — green, detached. ONE JVM at a time; 0 orphan this-repo JVMs; consolidator flake — isolate ×3 if sole red.

## Deliverable — final message: tracers red→green (final gate line, exit 0); the excel `:relations` op diff + the container-contract wiring; the shape you matched (SQL) + how MC-6 consumes it; quote the synthetic shared-key→`:via` assertion + the no-shared→`[]` assertion + the real-O*NET SOC-relation assertion; confirm source-relations-fn now returns a fn for excel; existing SQL/CSV/excel ops + gates green; anything not verified; no commit/push; 0 orphan JVMs.

## Core Disciplines (verbatim)
1. NEVER assume — chase to ROOT CAUSE; rule out the harness (a fixture that fakes the header wrong fakes the symptom). 2. Verify QUALITY: the op must return the shape MC-6 actually consumes — assert against MC-6's expectation, not just "returns something". 3. Instrument if a case resists. 4. Live/real: the real O*NET dir is the floor; durable test guards it. 5. No silent fallback — `[]` on no-shared is honest, never a fabricated edge. 6. TDD, tests first, one at a time, behavior through public fns. 7. No hardcoded domain matching — structural shared-key detection, names no column. 8. Re-orchestrate — reuse the excel header capability + match the SQL relations shape; don't fork excel reading or MC-6. 9. Adversarial: shared-id→relation, shared-freetext→none, no-shared→[], real-O*NET→SOC relation. 10. Deterministic. 11. JVM hygiene (detached, one at a time, `pgrep -f`, 0 orphans). 12. Domain-agnostic (general system tested with O*NET). 13. n/a.
