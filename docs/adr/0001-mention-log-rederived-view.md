# ADR 0001 — Entity-vs-attribute role is a re-derived view over an append-only mention log

**Status:** Accepted (2026-07-01)
**Context doc:** [`../build-timeline/grill-sessions/wide-stats-observation-modeling.md`](../build-timeline/grill-sessions/wide-stats-observation-modeling.md)

## Context

The multi-source ontology builder must decide, per source, whether incoming data is a new ENTITY or an ATTRIBUTE/measure of an existing entity — and this decision depends on what the graph already contains, which depends on the order sources are processed. A wide stats source processed first (pseo) mints "earnings" as entities; the same data processed after IPEDS should be measures of a program. We want the FINAL graph to be the same regardless of source order (order-independence), while still CORRECTING earlier modeling mistakes as more evidence arrives.

The prior approach (GC-16) mutated the model-spec / graph in place to "repair" mistakes. It regressed clean sources and could not converge.

## Decision

The entity-vs-attribute **role** and canonical identity of a concept are a **re-derived VIEW over an append-only log of immutable MENTIONS**, not state mutated in place. Every extraction appends mentions; the concepts/relationships/roles are DERIVED by projecting the mention log, and are RE-DERIVED (incrementally, for the affected subgraph) as new mentions arrive. "Promote/demote entity↔attribute" is the view re-deriving with more evidence — never a destructive retraction.

## Rationale (the CALM constraint)

The CALM theorem (Hellerstein, PODS 2010; Hellerstein & Alvaro, CACM 2020) states a computation has a consistent, coordination-free (order-independent) implementation **iff it is monotonic**. Adding facts is monotonic → order-independent for free. **Demoting a mistakenly-minted entity is a retraction = non-monotonic** → it CANNOT be order-independent if we mutate a live graph with corrective events. The only way to get order-independent convergence WITH correction is to make the role a monotone function (a re-derivation) of an append-only fact set. ORC is already event-sourced, so the append-only substrate exists.

## Alternatives considered

- **In-place compensating events** (`entity-folded-to-attribute`, `retype`, `repoint`) + a CQ-loop `:refactor` route. Reuses the most existing machinery, but per CALM is NOT truly order-independent, and two independent 2025–26 multi-agent-KGC studies found in-place repair loops degrade (hallucination loops, state amnesia, file bloat). Rejected as the primary model (may still appear as an implementation detail of the derivation).
- **Full global re-derive on every source.** Strongest convergence (pure function of the mention set) but recompute cost grows with graph size; likely too slow for the maximal build. Rejected in favor of INCREMENTAL re-derivation of the affected region (the touched dimension/linking-key neighborhood), accepting a small, documented order-sensitivity at the incrementality boundary as a tractability trade-off.

## Consequences

- **Positive:** order-independent convergence; earlier modeling mistakes self-correct as evidence accumulates; fits event sourcing; avoids the non-monotonic-retraction trap and the degrade-prone in-place repair loop.
- **Negative / cost:** a real re-architecture of the build path (a `mention` event + a derivation projection; concepts become a derived view); incremental (not global) re-derivation admits a bounded order-sensitivity at the window boundary; re-derivation cost must be bounded to the affected region to stay tractable.
- **Reversibility:** hard to reverse once the build path is mention-based — hence this ADR. Mitigated by slicing: Slice 2 introduces the mention log with byte-for-baseline parity on clean sources before any reformation depends on it.

## Verification

Order-independence is the load-bearing acceptance: two different source orders must yield the same final graph (within LLM variance). Clean-source parity (the GC-13 447-concept baseline) guards against regression. Both are live-verified, not unit-only.
