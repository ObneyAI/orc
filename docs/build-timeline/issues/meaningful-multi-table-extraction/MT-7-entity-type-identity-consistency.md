# MT-7 — Entity-type identity consistency — SUPERSEDED by MT-7a / MT-7b / MT-7c

**Status: superseded.** This issue originally scoped the fix as a GC-1 canonicalize patch (resolve a draft's canonical entity-type by the keying-field set it satisfies). The `/prototype` (`development/src/mt7_resolution_prototype.clj`) DISPROVED that approach on real drafts: with an empty/unreliable model-spec the resolution has nothing to resolve against (100% unresolved), and per-container authors freelance type names independent of any spec — so a post-hoc patch cannot work.

The problem was re-grounded in a `/grill-with-docs` design session. The decision is recorded in
[`components/ontology/docs/adr/0001-canonical-vocabulary-binding.md`](../../../../components/ontology/docs/adr/0001-canonical-vocabulary-binding.md)
and the sharpened terms (canonical entity-type vocabulary, vocabulary freelancing, entity-type fragmentation, vocabulary proposal) in
[`components/ontology/CONTEXT.md`](../../../../components/ontology/CONTEXT.md).

The work is now sliced as:
- [MT-7a — vocabulary binding + enforcement seam](MT-7a-vocabulary-binding-enforcement.md)
- [MT-7b — vocabulary proposal path](MT-7b-vocabulary-proposal-path.md)
- [MT-7c — acceptance: unfragmented comprehensive build + CQ-gate](MT-7c-acceptance-unfragmented-build.md)
