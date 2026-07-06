# Handoff — MT-9: raise the vocabulary-recovery retry bound (C1 double-loss reliability)

**Parent:** the MT-7c acceptance reliability (the last blocker to a green 2-run acceptance). **Blocked-by:** none (MT-7d landed the mechanism at `d138d09a`).
**Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. ONE bounded build at a time; `pgrep -f` hygiene.

## The problem (probe-confirmed live — don't re-derive)
MT-7d's vocabulary-recovery retry (in `central_evolver/delegate-model-extract!`) FIRES correctly (live probe: retries 0→1, recovers when the 2nd attempt crosses clean). But the per-attempt C1 `:delegate`-crossing loss rate is high (~0.4–0.6), so a DOUBLE loss (both attempts empty) ≈ 25–36%. With the bound at **1** (2 attempts), ~1 in 3 comprehensive runs still dies at `:failed-at-model-extract` — and a 2-run acceptance needs BOTH runs to complete, so it rarely goes green even though each completing run passes (run 2 of the MT-8 verify passed every gating criterion; run 1 double-C1-failed). The durable cure is the dscloj `[:vector [:map]]` parse root fix (tracked in memory, a separate dependency-level effort); THIS slice is the interim reliability lever.

## The change (one const + the test made const-driven)
1. **`components/ontology/src/ai/obney/orc/ontology/core/central_evolver.clj`** — raise `max-vocabulary-recovery-retries` from `1` to **`3`** (4 total attempts → double/quad-loss drops to ~6–13%). Update the const's docstring (it currently says "at most ONE re-run" / "never a third" — make it "at most N re-runs" where N is the const; keep the "loud stop stands on final recurrence" + "never an unbounded loop" language). NOTHING else in `delegate-model-extract!` changes — the loop already reads the const; it is ALREADY correctly bounded by the const (verified: `(< retries max-vocabulary-recovery-retries)`).

2. **`components/ontology/test/ai/obney/orc/ontology/eb10_central_evolver_subbehavior_test.clj`** — the `mt7d-double-empty-failure-returns-honestly-bounded-test` currently HARDCODES the bound (stub returns 2 distinct raws; asserts `(= 2 @calls)` and `(= 1 ce/max-vocabulary-recovery-retries)`). Rewrite it to be **const-driven** so it survives any future bound change:
   - stub ALWAYS returns an empty-vocab failure (any N of calls);
   - assert `(= (inc ce/max-vocabulary-recovery-retries) @calls)` — total attempts = 1 initial + N retries, NEVER one more (the bound bites, no unbounded loop);
   - assert `:vocabulary-retries` on the return `= ce/max-vocabulary-recovery-retries` (the exhausted count surfaced);
   - assert `:degraded-model-spec-raw` = the FIRST attempt's raw verbatim (keep a per-call-distinct raw so "first, not last" is still proven — e.g. stub returns `{:entity-types "" :call n}` so the first differs from later ones);
   - DROP the `(= 1 ce/max-vocabulary-recovery-retries)` magic-literal assertion (that's what made it brittle).
   The OTHER two MT-7d tests (`fires-once-and-recovers`, `no-retry-on-genuine-non-empty-vocab-failure`) are UNAFFECTED by the bound (they exercise 1 recovery / 0 retries) — they must stay GREEN unchanged; cite them.

DELIBERATELY NOT: no change to the retry CONDITION (`empty-vocabulary?` predicate, never the error string), no change to the hard stop, no unbounded loop, no new machinery. This is purely the bound value + a brittleness fix in its test.

## /prototype
Not needed — a one-const change over a proven, probe-confirmed mechanism.

## TDD cycle (tests FIRST, red→green)
1. **Rewrite the double-empty test const-driven (RED first):** with the const still at 1, rewrite the test to assert `(inc const)` calls + `:vocabulary-retries = const`; confirm it passes at 1 (2 calls). THEN bump the const to 3 → the SAME test now expects 4 calls / retries 3 and passes (proving the loop honors the raised bound, still bounded — never a 5th). Witness both: the const-driven test green at 1, then green at 3.
2. **The recover + no-retry tests stay green** unchanged (the bound doesn't affect a 1-recovery or 0-retry path).

## Live-QA (the reviewer's `/inspect-orc`)
Re-run the MT-7c bounded acceptance (`clj -J-Xmx6g -M:dev:test -m mt7c-acceptance`). With 4 attempts/run, both runs should now reliably clear model-extract → the acceptance goes GREEN (honest-terminal + zero fragmentation + cq-gate-answered + not-hollow on BOTH). If a run STILL double-fails through 4 attempts, that's a higher-than-modeled loss rate → report it honestly (the dscloj root fix becomes urgent, not the bound).

## Do NOT touch
The retry condition / hard stop / vocabulary-binding line / MT-8 CQ evidence / the grain. No error-string matching. No unbounded loop.

## Core Disciplines (binding — verbatim)
1. NEVER assume; NEVER "flaky" — the double-loss is probe-quantified; this is a bounded, root-informed mitigation while the dscloj root fix is queued. 2. Verify QUALITY not completion — a test that hardcodes the bound is brittle (that's the bug being fixed); make it const-driven so it can't silently drift. 3. Instrument to root cause. 4. Live REAL everything; the re-acceptance is the proof; no false green. 5. No silent fallback — the loud stop STILL stands after N attempts; the exhausted retry count is surfaced. 6. TDD, tests first, const-driven. 7. Retry condition stays the deterministic `empty-vocabulary?` predicate — NO error-string matching. 8. Re-orchestrate — change the bound value; the loop already honors it; no fork. 9. Adversarial verdict — confirm the loop NEVER runs a `(+ 2 const)`-th attempt (bound bites). 10. Deterministic bound around the LLM step — verify both. 11. Key = env var; JVM hygiene (one build at a time, 0 orphans, `pgrep -f`). 12. Domain-agnostic. 13. `:reasoning` first on `:llm` nodes (none new here).
