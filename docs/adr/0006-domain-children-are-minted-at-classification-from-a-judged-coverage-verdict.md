# Domain children are minted at classification from a judged coverage verdict

Off-domain tasks match the corpus's shape classes at high fitness, so their
evidence accrues on a shape ("ETL pipeline") and the waterfall the corpus was
designed around never forms a domain-specific class below it. Measured twice on
the 21-task out-of-distribution corpus: 14 of 21 confident matches in June, 20
of 21 in September after the emergence loop, with the domain guard firing on
none because every seed's guard is written in terms of shape. We decided that a
task matching a leaf shape class whose declared domain does not cover the task
gets its own class under that leaf, minted at classification, with an identity
derived from the parent and the reranker's canonical domain label, and that
domain coverage is a discrete verdict the reranker gives alongside fitness
rather than a threshold over any score.

## Considered options

- **Author domain-specialised seeds** (the R04 handoff's Path A) — rejected.
  Specialises by authoring rather than evidence, would need re-authoring for
  every domain the corpus meets, and contradicts the ratified rule that a
  behavior is earned by a tree that worked.
- **Split classes after the fact from consolidation evidence** — rejected.
  The split arrives only after many blurred occurrences, and re-attributing
  durable recurrence fights the one-verdict-per-campaign fact model.
- **A separate meta-judge call for coverage** (Path B) — rejected as a
  mechanism, kept as the signal. One more model call per classification and a
  second place for the shape bias to reappear; the same question asked as two
  typed fields of the rerank call that already runs costs nothing extra.
- **Random identity for the child, convergence by retrieval and bundling** —
  rejected. The retrieval gate hides a runtime class between its first and
  third verdict, so the second and third occurrences would scatter. A derived
  identity converges without retrieval, the rule already ratified for
  behavioral mints.

## Consequences

- Fitness keeps meaning shape-and-intent fit; coverage is a separate verdict,
  and an unknown verdict defers on the domain axis exactly as a reranker
  fallback defers on fitness. Nothing is fabricated on uncertainty.
- Domain children are cheap identities, not corpus bodies. There can be many;
  the behavioral layer still decides which of them earn a body through harvest
  under the nearest abstract behavior, so the child-only granularity rule for
  authored behavioral children is unchanged.
- The identity layer's parent edge now exists from mint time, so walk-down can
  reach children the classifier itself created — the first time the classified
  event's parent has meant anything in the concept graph.
