# RF2 — `seed-descriptions` test-classpath gap (tracer bullet · deferred)

**Type:** AFK · deferred · **Parent:** `docs/build-timeline/issues/rlm-finalization/README.md`

## What to build
Make the 5 ontology tests that require the `seed-descriptions` namespace runnable
under the canonical `clj -M:poly test`. Today `seed-descriptions` lives under
`development/src` (on the `:dev` classpath ONLY), so these tests load under
`clj -M:dev:test` but FAIL TO LOAD under poly:
`description-events-test`, `r02-flat-pattern-children-test`,
`r07-investigation-behavioral-seed-test`, `r05a-behavioral-subtree-foundation-test`,
`tree-class-hierarchy-test`.

The fix is a test-infra relocation/exposure decision (e.g. move the seed corpus
source onto the ontology brick's path, or provide a test-classpath shim) so the
canonical gate exercises these namespaces rather than silently skipping/erroring
on them. Surface, don't hide — a namespace that can't load under the canonical
runner is a real coverage hole.

## Acceptance criteria
- [ ] The 5 named tests load + run under `clj -M:poly test` (no `FileNotFoundException: seed_descriptions`).
- [ ] No behavior change to the seed corpus itself; only its reachability from the test classpath.
- [ ] Green under BOTH `clj -M:poly test` AND `:dev:test`; no other brick regressed.

## Blocked by
None (independent of RF1). Deferred — separate, smaller, test-infra blast radius;
kept out of RF1 to keep that cycle clean.

## Core Disciplines
Binding Core Disciplines block 1–13 — identical to RF1 (see
`docs/build-timeline/issues/rlm-finalization/RF1-terminal-final-bang.md`). In
particular: verify under BOTH runners (#4), no false green / no silently-skipped
namespaces (#9), branch `feature/ontology-architecture`, one commit per slice,
co-author trailer, JVM hygiene (#11).
