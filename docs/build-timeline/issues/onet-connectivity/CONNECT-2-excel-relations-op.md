# CONNECT-2 — Excel `:relations` op: heuristic shared-key cross-sheet relations

**Type:** AFK · **Blocked by:** None (self-contained; CONNECT-1 landed)

## What to build
The excel container-contract exposes `:relations` = **nil** today (proven: `(:relations (container-contract {:type :excel :path onet-dir}))` → nil, TYPE nil), while SQL exposes an FK-derived `:relations` fn and CSV exposes `:relationship-hints`. So `source-relations-fn` (extract_subbehavior.clj:895) returns nil for excel-dir → MC-6's deterministic cross-sheet edge-derivation (extract_subbehavior.clj:875-892 — joins entities across containers sharing a `:via` key VALUE recovered from each concept's `:attributes`) NEVER fires → O*NET occupations get 0 cross-sheet edges.

Implement the excel `:relations` op (in `source_tools_excel.clj`, wired into the container-contract in `source_tools.clj`, same shape SQL/CSV plug into): a **deterministic, domain-agnostic heuristic** that inspects the sheets' HEADER columns and emits `{:from <sheetA> :to <sheetB> :via <column>}` relations for **ID-like key columns that appear in ≥2 sheets** (e.g. `O*NET-SOC Code` appears in Occupation Data, Abilities, Skills, Knowledge, Task Statements → occupation↔each via that column). It must name NO domain column — the shared-key detection is purely structural (a column NAME shared across sheets, biased toward key-shaped columns: high cross-sheet co-occurrence, ID/code-like, not a free-text/measure column). Mirror CSV's `:relationship-hints` approach (source_tools_csv.clj:319-343). Return `[]` honestly when no shared keys (never fabricate).

## Acceptance criteria
- [ ] `(:relations (container-contract {:type :excel :path onet-dir}))` is a FN (not nil).
- [ ] Called for a container, it returns `{:from :to :via}` relations joining sheets that share a key column — on the real O*NET dir, occupation-bearing sheets are related to the SOC-bearing junction sheets via the shared SOC column (assert a relation whose `:via` is the shared SOC-code column exists between an occupation sheet and a skills/abilities/tasks sheet).
- [ ] Domain-agnostic: the detection names no column; a synthetic 2-sheet fixture sharing a made-up `"widget_id"` column yields a `{:via "widget_id"}` relation, and two sheets sharing NO column yield `[]`.
- [ ] `source-relations-fn` (extract_subbehavior) now returns a fn for an excel source (was nil) — the wiring reaches MC-6.
- [ ] Existing SQL/CSV relations paths + the excel container-contract's other ops (`:list-containers`/`:sample-rows`/`:stream-all`) unchanged; ontology + orc-service brick gates green.

## Disciplines (verbatim — a subagent MUST NOT skip these)
- **Never assume. Chase every bug to its ROOT CAUSE.** No band-aids, no "probably," no jumping to conclusions. Reproduce → minimize → fix the actual cause. Rule out the *harness itself* (a stuck flag, a stale fixture, a tool that stores results oddly can fake a symptom): distinguish "the work is wrong" from "my measurement is wrong."
- **TDD for real logic: red → green → refactor, one test at a time.** Vertical tracer-bullet slices, never horizontal. Test **behavior through public interfaces**, so tests survive refactors.
- **Injected-capability seam pattern.** Keep logic pure and testable; inject effects (the sheet-header reader) as capabilities that default to the real impl and are faked in tests.
- **Durable tests AND live QA.** A passing unit test is necessary, not sufficient — also drive the real thing (the real O*NET dir). Turn what you verified into a durable test.
- **Dispatch sub-work to fresh agents, then INDEPENDENTLY and ADVERSARIALLY verify it.** Never trust a "done / all green" report — re-run the proof, re-read the code, try to break the claims.
- **Report faithfully** — including your own mis-steps and anything you couldn't verify.
- No hardcoded domain matching — the shared-key heuristic is structural, names no O*NET column. Commit-LOCAL only, never push. JVM hygiene: detached, one at a time, `pgrep -f`, 0 orphans.
