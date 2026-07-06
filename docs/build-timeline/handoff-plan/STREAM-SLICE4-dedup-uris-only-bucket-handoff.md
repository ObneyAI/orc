# Handoff — STREAM Slice 4: dedup streaming load + URIs-only LSH bucket (the O(n×bands) fix)

**Parent plan:** `/Users/darylroberts/.claude/plans/precious-sleeping-kurzweil.md`. Foundation + embed + ref-integrity landed (`6a24b15c`). This is the HARD slice: the dedup LSH bucket is genuinely O(n×bands)-memory (it holds full signed-concept COPIES in every band). **VERDICT-INVARIANCE is the invariant — dedup verdicts must be byte-identical.**
**Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. `pgrep -f` hygiene; detached `nohup … &` for gate.

## Part A — stream the dedup-stage concept load
`deterministic_skeleton.clj` **~line 383**: `concepts (->> (rm/get-concepts ctx {}) …)` (the dedup-stage load — Slice 3 only converted normalize-stage) → `cs/reduce-concepts` with a `:project-fn` keeping ONLY the fields the LSH signing + the cascade read: **`:uri :label :description :type`** (confirm by reading the `->>` pipeline's `select-keys`/field access AND `dedup_cascade.clj`'s `run-cascade` — it reads `a-label`/`a-desc`/`b-label`/`b-desc` (lines ~544/553) + `:type` (T5 type-blocking) + `:uri`). Keep the SAME phantom filter (`:ontology-id`) if the existing pipeline filters (mirror line 296). Byte-identical concept set + fields → same verdicts.

## Part B — URIs-only LSH bucket (`dedup_cascade.clj` `lsh-candidate-pairs` ~374-432)
Today: `signed` = each concept `(assoc c ::wsig ::ssig)`; `buckets` = `(update a k (fnil conj []) c)` — so each concept's FULL map is copied into ~2×bands buckets → **O(n × bands × concept-size)**. Fix, preserving verdicts exactly:
1. Build a compact side map **`by-uri = {uri → {:uri :label :description :type ::wsig ::ssig}}`** (the light concept + sigs), ONE entry per concept (not per band).
2. Buckets store **`(:uri c)` strings** instead of `c`: `(update a k (fnil conj []) (:uri c))`. Bucket memory drops from full-concept-copies to URI strings.
3. At pair enumeration (the MT-7e-bounded loop), resolve each URI back to its light concept via `by-uri` when emitting `[a b]` pairs; `(dissoc a ::wsig ::ssig)` stays so the emitted pair carries `{:uri :label :description :type}` — EXACTLY what `run-cascade` reads.
4. **Keep MT-7e caps UNCHANGED** (`max-pairs-per-bucket`, `max-candidate-pairs`, `:over-cap-dropped`/`:total-cap-hit?` surfacing, deterministic bucket order) — the pair SET + order must be identical, so the enumeration logic is byte-preserved; only WHAT the buckets hold (uri vs concept) changes.

**Verdict-invariance is the whole game:** the enumerated pair set (same collision neighborhoods, same MT-7e caps, same order) and the fields on each emitted pair (`:uri :label :description :type`) must be IDENTICAL to today, so every T1–T9 verdict is unchanged. If the light-concept is missing a field `run-cascade` reads, a verdict would change — that's the failure to hunt.

## Do NOT
Touch reduce-concepts/relationships (Slices 1/3), CQ-retrieval (5), caps/ingestion (6-7). Do NOT change the cascade tiers, the MT-7e caps, the minhash/band-keys, or the pair ORDER. The SQLite-temp bucketing (extreme scale) is a documented EXTENSION POINT — build it ONLY if a 50k prototype shows the URIs-only bucket still walls (it should not). No domain names.

## TDD (tests first, red→green — `s12_dedup_cascade_test`)
1. **Bucket holds URIs, not concepts:** on a fixture, assert (via a bounded probe or by inspecting) that `lsh-candidate-pairs` no longer copies full concept maps into buckets — the memory-shape change. (If not directly assertable, prove it via the pair-set equality below + a comment.)
2. **Pair-set + field invariance:** `lsh-candidate-pairs` returns the SAME set of `[a b]` pairs (same URIs, same order, same MT-7e `:blocking-truncation`) as before, and each `a`/`b` carries `{:uri :label :description :type}` (the cascade-read fields). Compare against a captured pre-refactor expectation on a fixture with a giant same-signature bucket (exercise the MT-7e cap) + genuine near-dup families (the existing recall-preservation fixture).
3. **VERDICT-INVARIANCE end-to-end:** run the dedup cascade (the existing s12 tests) — every verdict (:merge/:distinct/:skip/:requires-review) UNCHANGED. This is the load-bearing guard; the existing s12 recall/merge/distinct tests must stay green.
4. **Streamed load (Part A):** dedup-stage's concept set is byte-identical to the pre-conversion `get-concepts`-filtered version (single-ontology).

## Gate + hygiene
`clj -M:poly test brick:ontology` green (detached). ONE JVM at a time; 0 orphan this-repo JVMs after; consolidator flake — isolate ×3 if sole red.

## Deliverable — final message: tracers red→green (final gate line, exit 0); the `lsh-candidate-pairs` diff (URIs-only bucket + by-uri side map) + the dedup-stage load diff + project-fn field set; quote the pair-set-invariance assertion AND the verdict-invariance (existing s12 tests green); confirm the MT-7e caps + pair order are byte-unchanged; the light-concept field set matches what run-cascade reads; anything not verified; no commit/push; 0 orphan JVMs.

## Core Disciplines (verbatim)
1. NEVER assume — verify the exact fields run-cascade reads; root-cause any verdict change. 2. Verify QUALITY: a refactor that changes ONE verdict is wrong — VERDICT-INVARIANCE is the gate, not "it runs". 3. Instrument if a verdict shifts. 4. Live/real (the s12 tests on the real cascade) is the proof. 5. No silent fallback. 6. TDD, tests first. 7. No hardcoded domain matching; no fuzzy — the minhash/caps are byte-unchanged. 8. Re-orchestrate — only WHAT the bucket holds changes; the enumeration + caps + tiers are untouched. 9. Adversarial: hunt a verdict that shifts because the light-concept dropped a field the cascade reads. 10. Deterministic — pair set + order + verdicts identical. 11. JVM hygiene (detached, one at a time, `pgrep -f`, 0 orphans). 12. Domain-agnostic. 13. n/a.
