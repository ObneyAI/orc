# Entity-types are bound to the discovered canonical vocabulary — never freelanced, never fuzzy-matched

Per-container extraction authors were naming entity-types freely, so the same real-world entity was minted under variant type names (`Occupation` vs `job-zones/occupation`; `job-element` vs `Job Element`) and could never merge — one entity split across thousands of nodes. We decided: the model-spec's `:entity-types` (itself discovered at runtime from goal × profiles) is the single authoritative vocabulary for every container of a source; every extraction author must emit either one of those types verbatim or an explicit **vocabulary proposal** — enforced by deterministic normalized-EXACT matching at the seam (one re-ask on mismatch, then honest per-container failure). An empty/unparseable vocabulary is a hard stop for extraction, never a silent proceed.

## Considered Options

- **Post-hoc canonicalize patch (resolve a draft's type by keying-field match)** — rejected: prototyped on real drafts and failed (100% unresolved when the spec is empty; subset keying sets over-merge distinct types).
- **Fuzzy/substring type matching** (snap `job-zones/occupation` → `Occupation`) — rejected: brittle string matching is banned in this codebase, and it silently over-merges genuinely distinct types.
- **Closed vocabulary (no proposals)** — rejected: the survey samples containers, so an unsampled container can legitimately hold a type the spec missed; forcing degrade there silently loses data.
- **Shared mutable vocabulary during concurrent extraction** — rejected: proposals stay local to their container; the orchestrator deduplicates post-extract (normalized-name collision → auto-merge as alias; same-keying-different-name → surfaced `:requires-review`, never auto-merged — two distinct types can share a key field).
- **Hardcoding a domain shape** (e.g. occupation-centric) — rejected on principle: this is a general system tested with O\*NET, not built for it. The code enforces only referential consistency against a vocabulary the model itself discovered.

## Consequences

- Cross-RUN structural reproducibility is deliberately NOT required: two runs may discover different (both valid) vocabularies/grains. The acceptance is the semantic CQ-gate (consistent with the earlier decision that the CQ-gate, not graph structure, is THE acceptance); within-run consistency is what is enforced.
- A2-vs-B style comparisons must compare capability (what questions each graph answers), not shape (node counts per type).
- Run-to-run convergence, if wanted, comes later as evolution: persist discovered vocabularies keyed by (goal, source-shape) and retrieve them as priors for synthesis — learned stability, not a cage.
