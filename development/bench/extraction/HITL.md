# HITL Extension Surface — Extraction Bench

This document is the explicit demarcation between what the S16 AFK
slice ships and what requires human authoring / review. A future
contributor (human OR a future-Claude session under user direction)
should be able to add a fixture, validate an expected graph, and mark
a fixture HITL-reviewed by reading THIS file alone.

## What needs human authoring

### 1. New fixtures

When adding coverage for a NEW source / domain (e.g., a CSV adapter
fixture, a SQL ingest fixture, a new ontology domain), the four files
under `fixtures/<name>/` must be human-authored:

- `source.ttl` (today) — the input source. Future adapters: `source.csv`,
  `source.json`, `source.sql`. The harness's `load-fixture` reads
  `source.ttl` today; non-TTL adapters are an HITL extension target.
- `spec.edn` — the ORSD spec (`:purpose` + `:competency-questions`).
- `expected.ttl` — the known-good graph. This is the GATE'S GROUND
  TRUTH; getting it wrong invalidates every future verdict.
- `notes.md` — derivation provenance + `HITL-REVIEW-REQUIRED` marker
  while pending validation.

### 2. Expected-graph validation for the 3 AFK-derived fixtures

The current corpus is AFK-derived from existing bench artifacts. Each
fixture's `notes.md` carries `HITL-REVIEW-REQUIRED`. **A human must
review every `expected.ttl` to confirm it represents the SEMANTIC
ground truth** — not just what the previous pipeline produced.

The three fixtures awaiting review:

| Fixture | Path |
|---------|------|
| document-analysis | [`fixtures/document-analysis/expected.ttl`](fixtures/document-analysis/expected.ttl) |
| risk-analysis | [`fixtures/risk-analysis/expected.ttl`](fixtures/risk-analysis/expected.ttl) |
| legal-issue-detection | [`fixtures/legal-issue-detection/expected.ttl`](fixtures/legal-issue-detection/expected.ttl) |

---

## Step-by-step: adding a new fixture

1. **Create the directory**.

   ```bash
   mkdir -p development/bench/extraction/fixtures/<name>
   ```

2. **Author `source.ttl`** — the input. Today the harness only handles
   TTL sources. For non-TTL formats (CSV / JSON / SQL), see "Extending
   to new source formats" below.

3. **Author `spec.edn`** — `{:purpose "..." :competency-questions
   [<string> ...]}`. Author CQs that:
   - Are answerable from `expected.ttl` (Layer-1 deterministic CQs like
     "Is there a <Concept> concept?" pass via the concept-existence
     projection without an LLM judge).
   - Include 1-2 ADVERSARIAL Layer-1 CQs (concepts NOT in the fixture)
     — these prove the substrate doesn't fabricate, and they drive the
     CQ pass-rate to a realistic-but-passing value (e.g., 5 pass + 1
     adversarial-fail = 0.83 ≥ 0.8 gate threshold).

4. **Author `expected.ttl`** — the SEMANTIC ground truth. Iterate
   against the substrate's emission conventions (the document-analysis
   fixture's `expected.ttl` is a canonical reference):

   - `skos:prefLabel` emits as a PLAIN literal (lang tag stripped
     by `concepts->turtle`).
   - `rdfs:comment` retains the source's `@en` lang tag.
   - `skos:scopeNote "custom"` lands per concept (TTL ingest defaults
     concept scope to `:custom`).
   - The `<base#scheme>` ConceptScheme block emits per build (three
     triples).
   - `owl:disjointWith` axioms emit in CANONICAL endpoint order
     (URI-sorted, `src < target` lexicographically — symmetric pair
     deduplicated to one directed triple). See `disjointness->turtle`
     in `components/ontology/src/ai/obney/orc/ontology/core/serialization.clj`.

   Run `(extraction.harness/run-fixture! "<name>")` and inspect
   `:triple-diff :missing` — that's the recall gap your `expected.ttl`
   must close. Iterate until `:missing #{}`.

5. **Author `notes.md`** — must include:
   - `HITL-REVIEW-REQUIRED` marker (while pending review).
   - Derivation provenance (what real run / source document the
     fixture mirrors).
   - "What this fixture tests" — which substrate features it exercises.
   - "Known limitations" — what's NOT covered yet.

6. **Run the harness** and verify the fixture lands in the report:

   ```clojure
   (require '[extraction.harness :as h])
   (h/run-fixture! "<name>")
   (h/run-all!)
   ;; → development/bench/extraction/extraction-RESULTS.md
   ```

---

## Step-by-step: validating an expected graph

When reviewing one of the AFK-derived fixtures, ask:

1. **Is every concept in `expected.ttl` something the source document
   ACTUALLY discusses?** A fabricated concept in the ground truth would
   train the rebuild to produce hallucinations.

2. **Is the concept set COMPLETE for the use case?** The AFK seed
   includes 4-5 concepts; a real-use ground truth might require 10-20.
   Add the missing ones. Run `(run-fixture! "<name>")` after each
   addition; iterate until `:missing #{}`.

3. **Are the disjointness axioms (and other S07 axioms) JUSTIFIED by
   the semantic domain?** E.g., "Contractor disjointWith
   VictoriaAirportAuthority" is justified (parties to a contract are
   distinct legal entities); but "LatePaymentInterest disjointWith
   IrrevocableLetterOfCredit" needs a HITL reviewer's call on whether
   that ontological commitment is semantically faithful.

4. **Are the CQs in `spec.edn` well-formed?** Adversarial Layer-1
   CQs should target concepts the SOURCE DOCUMENT genuinely doesn't
   define — not concepts the AFK author forgot to include.

5. **Are NO relationships, quantities, or sequences MISSING from the
   ground truth?** The AFK seed deliberately does NOT exercise S05
   (quantities), S06 (edge metadata), S08 (equivalences). Extending
   coverage is an HITL job.

When validation completes:

- Remove the `HITL-REVIEW-REQUIRED` marker from `notes.md`.
- Add a `HITL-reviewed-at: <ISO-timestamp>` line in `notes.md` (so
  future review-history is tractable).
- Re-run `(extraction.harness/run-all!)` — the report's headline summary
  now shows the HITL-reviewed count incremented.

---

## Step-by-step: marking a fixture HITL-reviewed

The harness checks `notes.md` for the literal string
`HITL-REVIEW-REQUIRED`. Presence → `:auto-derived`. Absence →
`:hitl-reviewed`.

```bash
# 1. After your validation pass, edit notes.md and DELETE the line
#    containing HITL-REVIEW-REQUIRED.
# 2. Add a confirmation line below for audit:
echo "HITL-reviewed-at: $(date -Iseconds)" >> fixtures/<name>/notes.md
# 3. Re-run the harness; verify the report flips the status.
clj -M:dev -e '(require (quote [extraction.harness :as h])) (h/run-all!)'
grep "HITL-reviewed expected graphs" development/bench/extraction/extraction-RESULTS.md
```

---

## What's AFK-built vs HITL (table)

| Component | AFK | HITL |
|-----------|-----|------|
| Harness functions | YES — `run-fixture!`, `run-all!`, `passes-G2?` ship | — |
| S09 canonicalizer reuse | YES — wired through `triple-diff` | — |
| Triple-diff direction | YES — adversarially tested | — |
| Report generator | YES — `extraction-RESULTS.md` shape ships | — |
| 3 initial fixtures | YES — derived from existing bench artifacts | their `expected.ttl` validation |
| New fixtures for new domains | scaffold + run + report | author 4 files |
| CSV / JSON / SQL adapters | — | extend `load-fixture` + S17 to read new source types |
| Precision tightening (extras as gate-fail) | — | author the threshold + adjust gate fn |
| Old-sheets baseline | — | rerun the harness against old-sheets path; commit the baseline scores |

---

## Open extension targets (HITL roadmap)

These are deliberate non-goals of the AFK slice — surfaced here so a
future contributor can pick them up cleanly:

- **HITL-1 — Validate the 3 expected.ttl files**. Highest-priority
  follow-up; without this the gate's ground truth is shaped by what
  the prior bench produced, not by intended semantics.

- **HITL-2 — Add a CSV-source fixture**. Today the harness handles
  TTL only. The S17 skeleton's parse stage has an `:inline-concepts`
  and `:inline-relationships` source-type — wiring a CSV adapter is
  small (read the CSV, map to inline-concepts, register).

- **HITL-3 — Quantity coverage (S05)**. Add a fixture whose source
  carries a `qudt:value`-shaped quantity (e.g., the 5% ILOC threshold
  from the RFP). Author the expected.ttl to assert the quantity event
  round-trips.

- **HITL-4 — Old-sheets baseline run**. Per PRD M8, the old sheets must
  be scored against this same corpus first to establish the regression
  baseline the rebuild path must beat. Wire the old-sheets path as a
  second `run-fixture!`-style driver and commit the baseline scores.

- **HITL-5 — Precision tightening**. The G2 gate today tolerates extras.
  When the corpus is mature, the gate should fail on extras above some
  threshold (`(<= (count :extra) max-extras)`). Add the threshold knob
  + a test.

- **HITL-6 — Live judge wire-up**. The harness uses
  `always-pass-judge` for CQ verdicts that aren't Layer-1-shaped. A
  live-verify variant should wire `dscloj/predict` against
  `cq-runner-judge-prompt-template` (OpenRouter) — same posture as the
  existing bench `runner.clj`.
