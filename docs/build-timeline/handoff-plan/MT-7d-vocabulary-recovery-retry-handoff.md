# Handoff — MT-7d: bounded vocabulary-recovery retry (the C1 crossing loss recovery)

**Parent:** the MT-7 line (ADR-0001). **Blocked-by:** MT-7a (`71861f75`) + MT-7b (`68a12cd2`).
**Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. ONE bounded build at a time; `pgrep -f` hygiene.

## The problem (evidence-grounded — don't re-derive)
The Model authors `:entity-types`, but the C1 `:delegate`-crossing intermittently degrades them to `[]` (sibling fields — `:edges` referencing the authored types, `:embed-fields`, `:linking-keys` — arrive intact; observed repeatedly today, ~3 of 5 auto-model runs). MT-7a's empty-vocabulary hard stop correctly converts what was silent 100%-freelancing into a LOUD `:failed-at-model-extract` (`ex-data {:reason :empty-entity-type-vocabulary}`) — but the MT-7c acceptance now fails on it (both runs). The failure is a transient parse/crossing loss: an immediate re-run typically produces a good spec (witnessed today). The deeper dscloj parse fix is tracked separately; the correct SYSTEM-level recovery now is ONE bounded, SURFACED retry.

## The change (small, general, at the seam)
In `central_evolver.clj` `delegate-model-extract!` (~403 — the ONE seam every caller uses: the initial build AND `focal-close!`):

1. Run the pipeline delegate as today; read back + normalize the model-spec (the existing GC-10 read-back at ~458).
2. **Retry condition (deterministic — NO string matching on `:error`, banned #7):** the delegate did NOT succeed **AND** `(vb/empty-vocabulary? model-spec-read-back)` — the exact predicate the hard stop uses (`ai.obney.orc.ontology.core.vocabulary-binding/empty-vocabulary?`; add the require). This also covers a nil read-back honestly (a Model that never wrote a spec could not have extracted either way; the retry is equally sane) — document that.
3. On the condition: re-run the SAME delegate ONCE (identical inputs, fresh call). Return the second attempt's result (success or failure) with the retry SURFACED:
   - `:vocabulary-retries 1` (else `0` on the untouched paths),
   - `:degraded-model-spec-raw` — the FIRST attempt's raw (pre-normalize) `:model-spec` output, VERBATIM (never truncate model output — #11): the dossier hook for the dscloj root-cause fix.
4. Bounded: at most ONE retry (a named const, e.g. `max-vocabulary-recovery-retries` = 1). A second empty-vocabulary failure returns the honest failure (with `:vocabulary-retries 1`) — the loud stop stands.
5. A failure whose read-back vocabulary is NON-empty (a genuine extract-stage failure) → NO retry, returned as today (behavior-preserving).

## TDD cycle (tests FIRST, red→green; `with-redefs` on `delegate-subbehavior!` — the seam is public in the same ns)
1. **Retry fires exactly once and recovers:** attempt 1 fails with outputs whose model-spec normalizes empty; attempt 2 succeeds with a good spec → result `:success`, `:vocabulary-retries 1`, `:degraded-model-spec-raw` = attempt 1's raw spec verbatim, exactly 2 delegate calls.
2. **No retry on a non-empty-vocab failure:** attempt 1 fails but its read-back spec has entity-types → exactly 1 call, failure returned, `:vocabulary-retries 0`.
3. **Double empty-failure is honest:** both attempts fail empty → exactly 2 calls (never 3), `:status` failure, `:vocabulary-retries 1` surfaced.
4. **Clean success untouched:** success on attempt 1 → 1 call, `:vocabulary-retries 0`, return shape otherwise byte-identical (behavior-preserving — cite existing central_evolver tests staying green).

## Do NOT touch
The hard stop itself (it stands); the binding/proposal seams; the Model subbehavior; the pipeline sheet def. NO string/phrase matching on error text. NO unbounded loop. Truncate nothing.

## Live-QA (the reviewer's `/inspect-orc`)
Re-run the MT-7c acceptance (comprehensive ×2): the retry absorbs a C1 loss (surfaced `:vocabulary-retries` in the run capture), builds complete, the verdict is re-judged for real.

## Core Disciplines (binding — verbatim)
1. NEVER assume; NEVER "variance/transient/flaky" as an explanation — this retry is a ROOT-CAUSED recovery for a DIAGNOSED transient crossing loss, bounded + surfaced, with the raw evidence captured for the deeper fix. 2. Verify quality not completion — a retry that silently swallows a REAL failure is the false green; test the no-retry path. 3. Instrument when symptoms resist. 4. "It ran" is the floor; the live re-acceptance is the proof. 5. No silent fallbacks — the retry is SURFACED in the return, and the loud stop stands on recurrence. 6. TDD, tests first, public seams. 7. No hardcoded phrase matching — the retry condition is the deterministic `empty-vocabulary?` predicate, never the error string. 8. Re-orchestrate — one seam, every caller covered; no fork. 9. Adversarial verdict — hunt the swallowed genuine failure. 10. Deterministic recovery around an LLM step — verify both. 11. Key = env var; NEVER truncate the captured raw model-spec; JVM hygiene. 12. Domain-agnostic. 13. `:reasoning` first on `:llm` nodes (none new here).
