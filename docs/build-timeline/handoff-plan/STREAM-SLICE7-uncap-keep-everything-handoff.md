# Handoff — STREAM Slice 7: the uncapping payoff (collect-mode + top-N → keep-everything by default)

**Parent plan:** `/Users/darylroberts/.claude/plans/precious-sleeping-kurzweil.md`. **THE PAYOFF** — the drop the user flagged first. Projection OOMs are eliminated (Slices 1-5, `f46b9a90`): whole-graph passes STREAM and FIELD-PROJECT the heavy attribute lists away (dedup/ref-integrity drop `:attributes`; CQ keeps them bounded by MT-8's cap), and the aggregating fold already CHUNK-PAGES its source. So uncapping lands on already-bounded infrastructure — the payoff, not a band-aid.
**Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. `pgrep -f` hygiene; detached `nohup … &`.

## Scope (aggregating-path caps only — the user's trigger)
`components/ontology/src/ai/obney/orc/ontology/core/container_aggregate.clj`. Make BOTH aggregating caps **opt-in, default = keep-everything**:

1. **Collect-mode (list) — `max-list-size` (line 60) + `add-distinct-bounded` (111-117):** today a hard 25-cap DROPS the 26th+ distinct value per key (the trigger). Change: the cap becomes an OPT-IN spec field `:max-list-size` (read from `spec` in `aggregate-step`). Default (absent/nil) = **UNBOUNDED** — keep ALL distinct values (still DISTINCT-deduped, just no cap). `add-distinct-bounded` becomes: dedup always; cap only when `:max-list-size` is a positive int in the spec.
2. **Top-N mode — `n` (line 108, default 10) + `keep-topn` (110):** today `n` defaults to 10, DROPPING non-top-10 ranked pairs. Change: `:n` becomes OPT-IN. Default (absent/nil) = **keep ALL** ranked pairs (still sorted by `:value` desc — order preserved, nothing dropped); `:n` present = cap to top-N (the existing behavior, now explicit).

**Honesty (#2/#5):** the `:peak-acc-entries` / accumulator-bound witnesses in `aggregate-finalize` must now reflect the UNBOUNDED reality (report the real per-key list sizes / peak), and where a cap IS opt-in-applied, surface it (`:list-truncated?` / `:topn-truncated?` or similar) — a cap that fires must be VISIBLE, never silent. Do NOT silently keep the 25-default.

## Why this is safe now (the foundation is in place — don't re-add a band-aid)
- The aggregating fold streams its source in chunks (MT-5 `apply-aggregation-transform!`); only `:acc` grows — and `:acc` = keys × distinct-values is the IRREDUCIBLE "collect all distinct values" working set (what the user explicitly wants retained), honestly witnessed.
- Downstream whole-graph passes (dedup/ref-integrity, Slices 3/4) FIELD-PROJECT `:attributes` away → the bigger lists never bloat them. CQ evidence (Slice 5) keeps `:attributes` but bounded by MT-8's `enum-attr-concept-cap`. Embed uses label/description, not the lists.
- FULL-scale caveat (documented, NOT this slice): the transient `reduce-concepts` `state` + embed's (Slice-2-deferred) concept read still hold full attributes → Slice 8 (separate attribute facts) + the Slice-2 embed field-projection are the full-scale follow-ups. For the VALIDATED bounded scale (mt7c / a real O*NET source), uncapped lists are manageable.

## Do NOT
Touch the per-row window cap / `apply-extraction-transform!` (that's the deferred Slice 6 — a different path/drop), the container cap (MT-12 coverage-aware relevance selection — a deliberate choice, not a blind drop), the projection slices (1-5), the CQ MT-8 evidence cap. No domain names. Keep the DISTINCT dedup (that's lossless, not a drop).

## TDD (tests first, red→green — `container_aggregate_test`)
1. **Collect keeps EVERYTHING by default (the trigger):** a collect-mode spec fed >25 DISTINCT element values for one key yields a concept whose list carries ALL of them (not 25). RED first against the current 25-cap.
2. **Collect opt-in cap still works:** the same with `:max-list-size 25` in the spec caps at 25 AND surfaces the truncation flag.
3. **Top-N keeps all by default:** a top-N spec (has `:value-col`) with NO `:n` keeps ALL ranked pairs (sorted desc); with `:n 10` caps to 10 + surfaces truncation.
4. **DISTINCT still holds:** duplicates are still deduped (lossless) in both modes; counters (`:rows-kept` etc.) honest.
5. **Witness honesty:** `aggregate-finalize`'s accumulator witness reflects the real (possibly >25) per-key sizes.
6. The existing `container_aggregate_test` (paged==whole, peak-acc) + mt7c acceptance stay green (mt7c should retain MORE values now — that's the win, not a regression).

## LIVE nothing-dropped verification (the plan's acceptance — REQUIRED, not just units)
After green: run a REAL aggregating extraction on a `:long-form` source with a key that has >25 distinct values (a real O*NET sheet, or the mt7c fixture) via a detached live script, and SHOW the landed concept carries ALL the values (the exact scenario the user raised). This is the "a >25-distinct source yields concepts carrying ALL values" gate from the plan. Report the before (25) vs after (all N) count.

## Gate + hygiene
`clj -M:poly test brick:ontology` green (detached) + the live nothing-dropped run. ONE JVM at a time; 0 orphan this-repo JVMs; consolidator flake — isolate ×3 if sole red.

## Deliverable — final message: tracers red→green (final gate line, exit 0); the container_aggregate diff (both caps → opt-in, default keep-everything) + the witness/truncation-flag changes; quote the "collect keeps ALL >25 distinct" assertion + the opt-in-cap assertion; the LIVE nothing-dropped result (before 25 → after all N on a real source); mt7c green (and whether coverage improved); confirm DISTINCT dedup preserved + caps honestly surfaced when opt-in-applied; anything not verified; the Slice 6 / Slice 8 full-scale follow-ups noted; no commit/push; 0 orphan JVMs.

## Core Disciplines (verbatim)
1. NEVER assume — root-cause; the default must genuinely keep everything (not a higher hidden cap). 2. Verify QUALITY: a live real-source run showing ALL values retained is the proof, not just a unit. 3. Instrument the accumulator witness to real sizes. 4. Live/real nothing-dropped run is MANDATORY (durable test AND live QA). 5. No silent fallback — an opt-in cap that fires must be surfaced. 6. TDD, tests first, red first against the 25-cap. 7. No hardcoded domain matching. 8. Re-orchestrate — spec-driven opt-in, don't fork the fold. 9. Adversarial: confirm nothing is silently dropped at the new default (distinct dedup is lossless; no residual cap). 10. Deterministic — sorted top-N order preserved. 11. JVM hygiene (detached, one at a time, `pgrep -f`, 0 orphans). 12. Domain-agnostic (general system tested with O*NET). 13. n/a.
