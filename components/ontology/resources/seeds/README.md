# Baseline Seed Corpus

This directory ships the baseline description corpus with the ontology
component. Consumers using ORC via git deps get these seeds automatically
when they call `(ai.obney.orc.ontology.interface/seed-baseline-corpus! ctx)`.

Without this baseline, the R-Inject classifier path retrieves against an
empty corpus and the model designing a behavior tree sees no examples in
its prompt — the entire self-improving loop has nothing to bootstrap
against.

## Files

| File | Entries | Granularity | Notes |
|---|---|---|---|
| `node-types.edn` | 10 | `:node-type` | One per primitive: `:llm` `:code` `:map-each` `:parallel` `:sequence` `:fallback` `:condition` `:chunk-document` `:aggregate` `:final` |
| `tree-classes.edn` | 23 | `:tree-fingerprint` + `:tree-class` | Structural patterns — task-specific (legal-issue-detection, risk-analysis, contract-comparison, document-analysis, chunked-extraction) + generic patterns (ChunkedExtraction, SequentialPipeline, ParallelIndependent, ValidationLoop, FallbackRecovery, MapReduce, ResearchThenSynthesize) + R02 children (etl-pipeline, scheduling, producer-validator, draft-critique, primary-backup, model-cascade, parallel-sum, parallel-classify-aggregate, briefing-generation, comparative-summary, iterative-refinement) |
| `behavioral-subtrees.edn` | 12 | `:tree-fingerprint` (body carries `:scope :behavioral-subtree`) | Accomplishment-shaped behaviors — Research, Extraction, Analysis, Synthesis, Ideation, Design, Critique, Validation, Code-building, Transformation, Classification, Investigation |

Each entry is `{:target-id <uuid-or-keyword-or-string> :body <description-body>}`.
The body schema is defined in
`components/ontology/src/ai/obney/orc/ontology/interface/schemas.clj` as
`description-body`. Bodies are principle-shaped: capabilities + strengths
with concrete worked-example DSL snippets + weaknesses with recommended
fixes + representative-uses + a prose summary.

## How consumers bootstrap

```clojure
(require '[ai.obney.orc.ontology.interface :as ontology])

(let [ctx (your-grain-ctx)]
  (ontology/seed-baseline-corpus! ctx))
;; => vec of command-results, one per dispatch (68 total — see below)
```

`seed-baseline-corpus!` dispatches the appropriate `:ontology/record-*-description`
command per seed. The 23 tree-class seeds are dual-emitted under both
`:tree-fingerprint` and `:tree-class` scopes so the R-Inject prepend
assembler's body-fetch hits from bootstrap onward. Total dispatches:

```
10 node-type + 23 tree-fingerprint + 23 tree-class + 12 behavioral = 68
```

The call is idempotent — re-running appends new
`:tree-description-updated` events with the same body. The descriptions
read-model projects the latest as `:current` while preserving `:history`
for audit.

## How consumers extend the corpus

Three growth paths, in order of effort:

### Hand-author task-specific seeds

If your application has a stable family of tasks with known-good tree
shapes, hand-author seeds for them on top of the baseline:

```clojure
(let [ctx (your-grain-ctx)]
  (ontology/seed-baseline-corpus! ctx)
  ;; Then your app-specific seeds via the same command path:
  (cp/process-command
    (assoc ctx :command
      {:command/name :ontology/record-tree-description
       :command/id (random-uuid)
       :command/timestamp (now)
       :target-id your-task-class-uuid
       :body {:capabilities [...]
              :strengths [...]
              :weaknesses [...]
              :representative-uses [...]
              :summary "..."
              :version 1
              :consolidated-from-event-count 0}})))
```

### Let the system mint behaviors at runtime

When `:rlm {:auto-classify? true :recursive? true}` is set on a
`repl-researcher` node and the model encounters work no existing pattern
fits at meaningful confidence, the model can contribute a new pattern
via the SCI sandbox primitive `(mint-behavior! ...)`. The minted body
persists in the corpus alongside the baseline and is retrievable for
future tasks across all consumers that share the event store.

### Let the consolidator improve existing patterns

As tasks run against any pattern, the consolidator periodically reads
recent execution evidence and updates the body — adding new
`:strengths`/`:weaknesses` from observation, climbing
`:evidence-count` on existing entries, bumping `:version`. Bodies
evolve from observation, not new hand-authoring. Anti-recency safeguards
prevent single-bad-burst overcorrection.

See [`docs/SELF-IMPROVING-LOOP.md`](../../../../docs/SELF-IMPROVING-LOOP.md)
for the end-to-end consumer walkthrough and
[`docs/LIVING-DESCRIPTIONS.md`](../../../../docs/LIVING-DESCRIPTIONS.md)
for the architecture detail.

## Stability guarantees

- **UUIDs are stable across ORC versions.** Each tree-class and
  behavioral-subtree target-id is derived from a deterministic seed
  string via `nameUUIDFromBytes`. Renaming a seed's `:summary` or
  growing its `:strengths` does NOT change the target-id. Downstream
  consumers that reference these UUIDs by literal value keep working
  across ORC upgrades.
- **The 10 + 23 + 12 = 45 named seeds are part of the public surface.**
  Removing a seed would be a breaking change for consumers relying on
  it; doing so requires a major version bump and migration notes.
- **Body shape may evolve additively.** New optional fields can be added
  to the description body schema; consumers should accept unknown fields
  silently.

## Editing the EDN

These files are now the source of truth for seed body content. The
historical dev namespace `development/src/seed_descriptions.clj` is
a thin shim that loads bodies from this directory and re-exports the
named-var surface for in-tree tests.

To edit a seed:

1. Edit the relevant `.edn` file directly. Each entry is
   `{:target-id <id> :body {...}}`.
2. Run the ontology test suite to verify round-trip + invariants:
   ```bash
   clj -M:dev:test -e "(require '[clojure.test :as t]) \
     (require '[ai.obney.orc.ontology.description-events-test]) \
     (require '[ai.obney.orc.ontology.seeds-test]) \
     (t/run-tests 'ai.obney.orc.ontology.description-events-test \
                  'ai.obney.orc.ontology.seeds-test)"
   ```
3. Live-verify by re-running a bench task that touches the seed.

The `components/ontology/scripts/regen-seeds.clj` script remains
available as a maintenance tool for regenerating the EDN from the dev
shim's view of the catalog — useful if you ever restore the original
3250-line Clojure form of `seed_descriptions.clj` (e.g., from `git
show HEAD:.../seed_descriptions.clj`) and want to re-derive the EDN.
For routine edits, modify the `.edn` directly.

When changing a seed's `:recommended-pattern` or
`:recommended-alternative` snippet, the
`all-seed-recommended-patterns-validate-via-rlm-dsl` test ensures the
snippet parses cleanly via `rlm-dsl/rlm-dsl->orc-dsl`. Common
violations the test catches:
- `:code (fn ...)` should be `:fn (fn ...)` inside a `:code` node
- `:map-each` requires a child node (it's a composite, not a leaf)
- `:options {:max-retries N}` on `:llm` should be `:retry {:max-attempts N}`
