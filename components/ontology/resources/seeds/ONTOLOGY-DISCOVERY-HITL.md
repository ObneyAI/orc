# Ontology-Discovery Patterns — HITL Extension Surface

The patterns in `ontology-discovery-patterns.edn` are consumed by the
recursive-RLM discovery session that S18's `run-discovery!` constructs.
Each pattern describes a shape the model can adapt when designing a
behavior tree to extract concepts + relationships + axioms from a
source.

This document is the contract for HITL (human-in-the-loop) review and
extension of the corpus. The shipped seeds are **AFK-derived** from
captured bench runs and marked `:hitl-status :auto-derived` until a
human reviews their semantics.

---

## What needs human authoring

Three review surfaces:

1. **HITL-review of derived seeds.** The 5 shipped patterns
   (DirectExtraction, SequentialPipeline, AdversarialGrounding,
   ChunkedSynthesis, SpecializedSynthesis) are auto-derived from
   bench captures. A human reviews the body content — `:summary`,
   `:strengths`, `:weaknesses`, `:recommended-pattern` snippets — and
   marks the seed `:hitl-status :hitl-reviewed` when the body is
   semantically correct.

2. **New pattern entries.** When a discovery session encounters a
   source shape no existing pattern fits (e.g., highly-structured
   tabular data, multi-modal sources, sources whose extraction needs
   external knowledge lookup), a human authors a new pattern entry.

3. **Behavioral mints from the wild.** When a discovery session calls
   `(mint-behavior! ...)` because no existing pattern fit, the minted
   entry surfaces in the descriptions corpus immediately. The HITL
   review confirms the mint's body is principle-shaped and signs it
   off into `ontology-discovery-patterns.edn` for permanent
   inclusion (or marks it noise and removes it).

---

## Step-by-step: validating an auto-derived pattern

The 5 shipped patterns ship as `:hitl-status :auto-derived`. To
validate one and promote it to `:hitl-reviewed`:

1. **Open** `ontology-discovery-patterns.edn` and locate the entry
   by `:target-id` or by reading the `:summary`.

2. **Read the body cold.** Without referencing any other context, can
   you tell:
   - What the pattern is good for? (the `:summary`)
   - When to use it vs not? (the `:strengths`/`:weaknesses` + `:avoid-when`)
   - What a tree using it looks like? (the `:recommended-pattern`)
   If any answer is unclear, the body needs editing before review.

3. **Cross-reference the provenance.** Each seed carries a
   `:provenance` field documenting its bench origin. Open the bench
   capture (RESULTS.md, the per-task .md file under
   `development/bench/tasks/`, or the corresponding `.edn` capture) and
   confirm the `:recommended-pattern` snippet matches the bench's
   actual tree shape verbatim. Truncation is a red flag — the model
   needs the full snippet to adapt cleanly.

4. **Hand-trace the application.** Pick a concrete discovery task
   that should fit this pattern. Walk through:
   - What `:reads` keys the discovery session would expose
   - What the per-stage prompts would produce
   - Whether the output shape matches the
     `:concept-drafts / :relationship-drafts / :axiom-drafts`
     contract the discovery wiring expects
   If the trace surfaces a gap, edit the body to close it.

5. **Verify against real-task evidence.** This is the floor, not the
   ceiling: synthetic shape-validation is not enough. Run a discovery
   session on a real source whose shape the pattern claims to fit and
   inspect the captured `:rlm-trace` for whether the model selected,
   adapted, or rejected the pattern as expected.

6. **Mark reviewed.** Change `:hitl-status :auto-derived` to
   `:hitl-status :hitl-reviewed`. Optionally add a `:hitl-reviewed-at`
   ISO-8601 timestamp + `:hitl-reviewed-by` for audit. The map is
   open — additional fields don't break the schema.

A reviewed seed is offered when a discovery session is configured
with `:require-hitl-reviewed-patterns? true` (a production-safe
default for consumers who do not yet trust the auto-derived corpus).

---

## Step-by-step: adding a new ontology-discovery pattern

1. **Identify the gap.** When does this pattern apply, and what
   existing pattern fails or under-fits for that case? Capture this
   answer in `:provenance` — it becomes the rationale future
   reviewers see.

2. **Generate a stable UUID** for `:target-id`. Use either:
   - `(random-uuid)` and inline the literal, OR
   - A deterministic UUID derived from the pattern's canonical name
     via `java.util.UUID/nameUUIDFromBytes` so the id is stable across
     ORC versions (the existing tree-class seeds use this convention).

3. **Author the body** following the description-body schema:
   ```clojure
   {:target-id #uuid "..."
    :body
    {:capabilities ["... what the pattern does ..."]
     :strengths    [{:trait              "... single-sentence trait ..."
                     :good-when          "... gate when this trait holds ..."
                     :recommended-pattern "... DSL snippet, NEVER truncated ..."
                     :confidence          1.0
                     :evidence-count      1
                     :first-observed-at   "ISO-8601"
                     :last-reinforced-at  "ISO-8601"}]
     :weaknesses   [{:trait                   "... weakness ..."
                     :avoid-when              "... gate when to avoid ..."
                     :recommended-alternative "... what to do instead ..."
                     :confidence              1.0
                     :evidence-count          1
                     :first-observed-at       "ISO-8601"
                     :last-reinforced-at      "ISO-8601"}]
     :representative-uses ["... concrete discovery-task examples ..."]
     :avoid-when          ["... contexts where the pattern doesn't fit ..."]
     :summary             "... 2-4 sentences ..."
     :version 1
     :consolidated-from-event-count 0
     :scope :behavioral-subtree
     :discovery-pattern? true
     :hitl-status :hitl-reviewed
     :provenance "... origin + rationale, self-contained ..."
     :composes-into [#uuid "00000000-c1c1-4001-b005-d0c0a0a0a0a5"]}}
   ```

4. **Body discipline (binding):**
   - `:summary`, `:strengths`, `:weaknesses` are SELF-CONTAINED —
     substance in the field, no internal slice names, no file paths,
     no commit SHAs (the model can't dereference those). Provenance
     is its own field.
   - `:recommended-pattern` snippets are VERBATIM — never truncated.
     The model needs the full snippet to adapt without lossy
     paraphrase.
   - Confidence + evidence-count are real signals; if you author a
     pattern from your own experience without bench evidence, mark
     it `:confidence 0.7 :evidence-count 0` and `:hitl-status
     :hand-authored-pending-evidence`.

5. **Append to the EDN file.** The vector at the top level just adds
   one more `{:target-id ... :body ...}` entry. Re-run the seed test
   suite to confirm the entry loads cleanly and the count increments.

6. **Verify on a real task.** The same floor applies — synthetic
   shape-validity is not proof of utility. Run a discovery session
   on a source the new pattern should help with and confirm the
   captured rlm-trace shows the model retrieving + adapting (or
   rejecting) the pattern as expected.

---

## Pattern body anatomy

A pattern entry is a `{:target-id <uuid> :body <description-body>}`
map. The body is the same shape every other tree-fingerprint /
behavioral-subtree description in the corpus uses, plus three
ontology-discovery-specific additions:

| Field | Purpose |
|-------|---------|
| `:capabilities` | One-line strings describing what the pattern enables. The classify-behaviors retrieval surface matches against these via embeddings — they are the pattern's discoverable summary. |
| `:strengths` | Principle-entries: `:trait` + `:good-when` + `:recommended-pattern`. The DSL snippet is what the model adapts. |
| `:weaknesses` | Principle-entries: `:trait` + `:avoid-when` + `:recommended-alternative`. Tells the model when to switch patterns. |
| `:representative-uses` | Concrete discovery-task examples — useful for the model when matching against its current task. |
| `:avoid-when` | Coarse-grained contraindications outside any single weakness — the "go look at another pattern instead" signals. |
| `:summary` | 2-4 sentence prose used by ColBERT indexing for semantic retrieval. Must be self-contained. |
| `:scope :behavioral-subtree` | Routes the entry through the behavioral-subtree retrieval path so `classify-behaviors` returns it. |
| `:discovery-pattern? true` | Flag the recursive-RLM discovery session uses to distinguish ontology-discovery patterns from generic behavioral subtrees. |
| `:hitl-status` | `:auto-derived` / `:hitl-reviewed` / `:hand-authored-pending-evidence` — gates inclusion when `:require-hitl-reviewed-patterns? true`. |
| `:provenance` | Free-text origin + rationale. NOT injected into the model's design prompt — used by reviewers only. |

---

## Opting in to HITL-only mode

A consumer that does not yet trust the auto-derived corpus configures
discovery with:

```clojure
(ontology/run-discovery!
  ctx
  {:ontology-id <granted-scope>
   :sources [...]
   :require-hitl-reviewed-patterns? true})
```

In that mode, the seed corpus offered to the session via
`classify-behaviors` is filtered down to entries with
`:hitl-status :hitl-reviewed`. If the filter eliminates ALL patterns
(none reviewed yet), the session proceeds with NO patterns — the
recursive-RLM model designs from scratch using only the S19 tool set
+ S20 orientation card. This is recorded explicitly in the result's
`:patterns-offered` field; the session does NOT crash.

---

## What this surface does NOT cover

- **Schema changes** to `description-body` — schema-level extensions
  (new top-level fields) require a separate ADR and a schema commit;
  HITL review here only authors content under the existing shape.
- **R-Inject auto-classification triggers** — the `:auto-classify?`
  knob on the discovery session is independent of HITL gating.
- **Bench RESULTS verification** — the AFK-derived patterns reference
  bench captures by name in their `:provenance`. Verifying that those
  bench captures still match the patterns' claimed behavior is a
  separate task tracked under the bench's own regression suite.
