# 0007. Domain children converge as tenant-wide families, and a would-be mint is judged against them

## Status

Accepted (supersedes the lookup clause of ADR 0006; its derivation clause stands).

## Context

ADR 0006 mints a domain child under the shape class a task matched, with an identity derived from that parent and
the reranker's canonical domain label, and the rules that land a later task look only among the matched parent's
children. Two live sweeps on exact repeats converged (identity stable on 18 of 21). A sweep of 24 paraphrases in
8 domains did not: zero landings; every off-domain group minted two or three children. The reranker's top-1 shape
moves between paraphrases within its normal variance, so a parent-scoped lookup cannot find a family minted under
another shape; and the reranker coins labels at instance granularity even when shown the sibling's label (reuse
16 of 17 on repeats, 0 of 12 on paraphrases). Harvest promotes one class at ten occurrences; fragmented families
never get there. A newborn child was also reached as a top match by the next task and became a parent itself, and
an in-domain task pulled to a neighbouring shape minted a mis-parented child while its own seed sat in the top-k
judged covered.

## Decision

- A domain converges as one **domain family** per tenant: the child minted first for it, under whichever shape
  minted it. Identity is still derived from the birth shape and the canonical label (every existing identity stays
  valid); a later occurrence looks the canonical label up across all of the tenant's domain families through the
  concept graph, not only the matched parent's children, and lands there. The birth shape stays the family's parent
  and the render's context.
- A family describes itself richly from birth (purpose, birth shape and its role, birth signature, and the slots
  that fill as evidence lands) through the claim path, and its concept is embedded at birth, so the ontology's
  hybrid search (graph and embeddings, rank-fused) can find it by meaning and by proximity.
- A classification that would mint asks one more discrete question first: the nearest existing families by rank
  are shown with their full descriptions and the judge answers same family (named), new, or unknown, reason before
  verdict. Same lands, new mints, unknown defers on the domain axis. No call on a landing or a covered match.
- Before the domain axis, a candidate in the ranking judged covered, with no families of its own, at or above the
  ordinary match threshold, wins over a top-1 that would mint (covered-seed protection).
- A family is a leaf on the domain axis: reaching one by match or walk-down is a landing; there are no
  grandchildren. A newborn family is matchable but not surfaced until the retrieval gate.

## Alternatives rejected

Label-only identity (orphans every existing child and merges silently); preferring only a top-k candidate that
owns the label (a subset of the tenant-wide lookup); deterministic embedding thresholds for merging (an inferred
rule the classification contract forbids); the merge judge on landings (a call per recurrence); covered-seed
protection regardless of children (re-imports the known bias of a parent that already has children).

## Consequences

Convergence no longer depends on retrieval's top-1 being stable. One model call is added only when a mint would
otherwise happen. The three domain rules, the identity and children invariants, the retrieval gate's zero band and
the config block change in `specs/ontology.allium`; the glossary gains domain family, merge verdict, covered-seed
protection and newborn family. The proof is deterministic tests on structured data; the realistic-traffic sweeps
report metrics against expectations stated beforehand and are never pass/fail gates.
