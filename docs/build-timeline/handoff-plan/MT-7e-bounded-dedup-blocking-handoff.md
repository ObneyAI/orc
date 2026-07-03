# Handoff — MT-7e: bound the dedup-cascade LSH blocking (comprehensive-scale OOM)

**Parent:** the MT-7 line (surfaced by the MT-7c acceptance). **Blocked-by:** none (MT-7a/b/d landed).
**Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. ONE bounded build at a time; `pgrep -f` hygiene.

## The problem (verify-first PINNED — don't re-derive)
The comprehensive build now travels far enough to reach the S12 post-landing dedup cascade, which OOMs (`dedup-cascade/shingles`, via `lsh-candidate-pairs`). A `/prototype` (`development/src/mt7e_dedup_oom_probe.clj`) profiled the REAL draft set: **52,910 concepts** (the fine observation grain — occupation×element measurements), labels SMALL (max 139 chars — NOT a giant-label OOM). The labels are element names repeated across occupations (`deductive reasoning` ×273, …), so LSH buckets fill with same-label-different-occupation concepts. The driver is **sheer concept volume × O(k²) within-bucket pair enumeration accumulated into one candidate-pair vector** — the SAME missing-bound family as GC-2 (bounded cross-container relating) and GC-7 (bounded reconcile/extract). The wasted work is real: those 273² comparisons all resolve `:distinct` (same label, different occupation) — bounding loses NO genuine merges.

## The change (a GC-2-style HONEST bound in `lsh-candidate-pairs`)
`dedup_cascade.clj` `lsh-candidate-pairs` (~277):

1. **Per-bucket pair cap.** Within a bucket, enumerate at most `default-max-pairs-per-bucket` ordered pairs (a named const; deterministic order — the existing `a-uri < b-uri` + members already in a stable order); DROP the excess. A huge bucket is a low-information label collision, not a duplicate cluster.
2. **Total-pairs ceiling.** A named `default-max-candidate-pairs` bound on the `out` vector overall (defensive against many moderate buckets summing large); stop admitting once hit.
3. **HONEST truncation (#4/#5 — never a silent top-N).** Return the truncation signal so the caller can surface it: either change the return to `{:pairs [...] :truncated {:buckets-capped n :pairs-dropped n :total-cap-hit? bool}}` OR (to keep the `[pair …]` contract the callers expect) attach the truncation report via metadata / a sibling fn — YOUR call, but the truncation MUST be observable (mirror GC-2's `:truncated-relations`). Update the ONE caller (`deterministic_skeleton/candidate-pairs` ~298) + wherever its result's truncation should ride into the reconcile/build report.
4. **Bounds are OVERRIDABLE** via the existing opts map (like `:perms`/`:bands`) so a caller/test can tighten them; the defaults are the production ceiling.

DELIBERATELY NOT: no grain change (the fine grain is the model's valid choice — ADR-0001); no new similarity notion; no fuzzy anything; the RECALL-bias contract stays (the cap only removes provably-wasted excess within an already-recall-biased neighborhood — document that a capped bucket is a same-signature cluster, so a dropped pair is near-certainly a non-merge, and this is surfaced honestly, never silent).

## /prototype
Done (the OOM-driver diagnosis above). Not needed again.

## TDD cycle list (tests FIRST, red→green, PUBLIC `lsh-candidate-pairs`)
1. **Per-bucket cap:** a fixture with one giant same-signature bucket (e.g. 500 concepts sharing a label) → at most `default-max-pairs-per-bucket` pairs from it; the truncation signal reports it. A small bucket → all pairs (behavior-preserving).
2. **Total-pairs ceiling:** many moderate buckets whose sum exceeds the total cap → the `out` is bounded to the ceiling, truncation surfaced; under the ceiling → unchanged.
3. **Recall preserved for genuine neighborhoods:** a small set of genuinely-similar labels (the existing dedup tests' fixtures) → the SAME candidate pairs as before (the cap doesn't bite normal-scale graphs — cite an existing dedup test staying green).
4. **Bounds overridable:** a tightened `:max-pairs-per-bucket` opt changes the cap (proves the knob).

(The LIVE proof — the comprehensive O\*NET build reaching + completing dedup without OOM, truncation surfaced honestly in the run report — is the reviewer's `/inspect-orc`, folded into the MT-7c re-acceptance.)

## Do NOT touch
The tier logic in `run-cascade` (T1–T9), the Jaccard/JW similarity, the LLM budget; the vocabulary-binding line; the grain. NO fuzzy matching; NO domain names.

## Core Disciplines (binding — verbatim)
1. NEVER assume; NEVER "flaky" — the OOM is root-caused (52,910 concepts × unbounded bucket pairs). 2. Verify QUALITY not completion — a bound that drops a GENUINE merge candidate is wrong; test recall preservation on normal-scale fixtures. 3. Instrument to root cause. 4. Live REAL everything; no false green (an "unfragmented" dedup that silently dropped real merges is a false green). 5. No silent fallback — truncation is SURFACED (the GC-2 pattern), never a silent top-N. 6. TDD, tests first, public fn. 7. Assert via return + report; NO fuzzy/hardcoded matching. 8. Re-orchestrate — bound the existing blocker; do NOT fork the cascade. 9. Adversarial verdict — hunt a dropped genuine candidate; surface honest negatives. 10. Deterministic bound around the LSH skeleton — verify recall + the cap. 11. Key = env var; JVM hygiene (one build at a time, 0 orphans, `pgrep -f`). 12. Domain-agnostic — a structural pair-count bound naming no field; the general system TESTED with O\*NET. 13. `:reasoning` first on `:llm` nodes (none new here).
