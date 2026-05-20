# Document Redaction Deep Dive — Development Journey

This is the development-focused companion to [`02_document_redaction.md`](02_document_redaction.md). It documents the iterations, failure modes, and framework upgrade (U11) that emerged during PR08. For the external-facing apples-to-apples comparison, read the clean report instead.

## Headline outcome (same as clean report)

| Metric | predict-rlm | ORC |
|---|---:|---:|
| Total redactions | 89 | **92** |
| Wall clock | 87s (1m 27s, per predict-rlm's `sample/output/output.md`) | **28.9s** (3.0× faster) |
| Tree shape | (REPL) | `[:map-each :code :code :map-each :code :code :code :final]` 8 nodes |
| Real PII catches unique to ORC (vs predict-rlm's published 78 targets) | — | **6+** (transit number, business number, multi-year date ranges) |

## What PR08 surfaced as a framework gap

Document redaction is the first port that REQUIRES the chain "`:llm` produces structured data → downstream `:code` consumes structured data". Image analysis didn't reveal this gap because its workflow is "LLM produces text → code transforms text → final answer" — every LLM output is intentionally a string.

**Redaction's chain:**
- `:map-each` per page → `:llm` writes `:targets` (each target a `{:text :category :reason}` map)
- `:aggregate` (or model-authored inline `:code`) combines all per-page targets into one vector
- `:code` apply-redactions reads that vector and produces redacted text + result struct

**Without U11**, every `:llm` write was stored as a raw string by dscloj's text-mode parsing. The model's downstream `:code` fns expected vectors of maps. Two failure modes resulted:

1. **Model assumes structured data** → `(mapv #(assoc % :page idx) raw-string)` → `Cannot cast Character to Associative` cast error mid-tree, Phase 2 fails with status `:failure`.
2. **Model defensively skips invalid shapes** (apply-redactions's shape-detect was added during the iteration) → `:total-redactions = 0`, status `:success` but useless output.

Both failure modes were observed across 6 runs before U11's design landed.

## Framework upgrade U11 — Schema-driven structured output for `:llm` writes

**The right fix:** let the model declare the SHAPE of each `:llm` write directly on the `:llm` node via `:output-schemas`. The framework then propagates that schema to the child-sheet's blackboard key declaration. `build-module` looks up the schema; dscloj's existing `complex-spec?` mechanism detects vector/map/etc. specs and:

1. Instructs the LLM "respond with valid JSON" in the prompt
2. Parses the response back into Clojure data via `parse-json-value`

The model gets back parsed data automatically. No model-side JSON-parsing code, no heuristic auto-detection, no need to enable function-calling at the provider level.

**Design tension addressed:** function-calling mode (which is "tool calls at the provider API") was disabled framework-wide due to historical OpenRouter/Gemini unreliability. U11 keeps function-calling disabled and uses dscloj's text-mode JSON-output path instead. Schema is the orchestration layer's contract; the provider just returns text we parse.

**Why this is framework-native, not a hack:**
- ORC already declares Malli schemas on blackboard keys (for runtime validation, schema-aware previews, etc.)
- dscloj already detects complex specs and handles JSON encoding/decoding
- The gap was JUST the bridge: model-emitted-tree → child-sheet declare-key → blackboard schema → dscloj spec
- U11 implementation = 1 helper (`extract-key-schemas`) + 2-line modification to the existing declare-keys loop

**Implementation footprint:**
- `rlm_tree_executor.clj` — `extract-key-schemas` walks the tree collecting `{key schema}` from `:llm` nodes' `:output-schemas`. The declare-keys loop prefers model-declared schemas over the `:any` fallback.
- `executor.clj` — framework prompt teaches the model the `:output-schemas` syntax with a concrete example.
- `rlm_dsl.clj` — no change. The DSL translator passes opts through, so `:output-schemas` round-trips for free.
- `rlm_dsl_test.clj` — two unit tests: round-trip of `:output-schemas` through the DSL translator, and `extract-key-schemas` correctly collecting from nested trees.

## Other issues surfaced during PR08 (and resolved)

### Issue D-A: Vector-of-large-strings preview inflated prompt to 1.1M chars

`preview-vector` was returning the first 3 ELEMENTS of a vector as-is, NOT previewed. For `:page-images` (6-element vector of ~150KB data URIs each), that meant ~450KB of data URI text per request. The runner saw `module :instructions length = 1133317` (1.1MB prompts).

**Fix:** `preview-vector` now recursively previews each sample element — strings > 200 chars get head/tail-truncated by `preview-string`, collections get their own previews. Primitives stay raw so simple vectors like `[1 2 3]` still display naturally.

This is also a real framework gap any vision-with-multiple-images benchmark would hit. Captured as a framework fix; image_analysis (single image) didn't expose it because the preview wasn't recursing into a vector.

### Issue D-B: LMDB map-size exhaustion under image-heavy ticks

The runner's LMDB-backed read-model cache was allocated 10MB (the default). Document redaction with 6 pre-rendered page images (~900KB total) plus per-tick state projection across ~150 events exhausted the cache during the run, producing `Environment mapsize reached (-30792)` errors and stalling the tick.

**Fix:** bumped LMDB map-size to 512MB in the comparison runner's `create-context`. ~5-line change.

### Issue D-C: `:available-code-nodes` catalog text triggered `:code nil` parser failure on gpt-5.4

When `:available-code-nodes` was set to my multi-line markdown catalog (with embedded `:fn "ns/sym"` Clojure-DSL examples and escaped quotes), gpt-5.4 reliably returned 1K+ completion tokens but dscloj's text-mode parser extracted `:code` as `nil`. The same prompt with the catalog REMOVED produced clean code. Independent of catalog text length (even a single-line catalog triggered it). Independent of model — gemini-3-flash-preview ALSO failed when catalog was set, but in a different mode (gave a 1.6M-token prompt because the catalog content somehow re-inflated images? — separate investigation).

**Workaround used in PR08:** embed the catalog text directly in the task's `:instruction` string and NOT set `:available-code-nodes` on the task definition. This keeps the dscloj request shape identical to working benchmarks (single `:task` field, no extra input field). The catalog content is identical; the model has the same affordance.

**Open root cause:** the framework prompt addition when `:available-code-nodes` is present uses `[:code {:fn \"...\"}]` with escaped quotes inside a string. Suspected this confuses gpt-5.4's response format, but the same pattern works for image_analysis (which was tested with the catalog earlier). Likely an interaction between the extra dscloj input field, the framework prompt addition, and gpt-5.4's structured response detection. Tracked for future framework investigation; the workaround is fine for now.

### Issue D-D: gpt-5.4 produced inline `:code` fns that crashed on string-as-data

Before U11 landed, multiple gpt-5.4 runs designed correct-shape trees but the inline `:code` fns crashed with `Cannot cast Character to Associative`. Diagnosed: the model wrote `(mapv #(assoc % :page idx) (:targets item))`, assuming `:targets` was a vector of maps. The reality post-LLM-call was that `:targets` was a JSON string (LLM produced JSON text; dscloj returned it unparsed). Iterating over a string gives characters; `assoc` on a character throws.

**Fix:** U11 — schema-driven structured output. The model declares `:output-schemas {:targets [:vector [:map ...]]}`; dscloj recognizes the complex spec; LLM produces JSON; dscloj parses JSON; downstream `:code` gets parsed Clojure data; `(:targets item)` returns the actual vector of maps; the model's inline transforms work.

### Issue D-E: apply-redactions hardcoded `:targets` key

Initial `apply-redactions` did `(get inputs :targets [])`. The model declared `:reads [:page-texts :all-targets]` — using a different key name (its own naming, not the function's hardcoded one). apply-redactions received `:inputs {:page-texts ... :all-targets ...}` and looked for `:targets` → got `[]` → 0 redactions.

**Fix:** apply-redactions now shape-detects its inputs. Vector-of-strings → page-texts; vector-of-maps → targets. The model can name its `:reads` keys whatever it wants; the function infers semantics by shape.

This is a small UX improvement for any pre-built `:code` fn the framework ships: don't hardcode expected key names, infer from value shape when possible.

## Run journey (chronological)

| Timestamp | Result | What happened |
|---|---|---|
| `document-redaction_2026-05-20_160427.edn` | Phase-1 hang on giant prompt | preview-vector dumped 1.1MB of data URIs into the prompt; LLM gave "Cannot invoke CharSequence.length()" error |
| `document-redaction_2026-05-20_161118.edn` | Phase-2 cast error | gemini-3-flash designed a tree but its own inline-fn hit `Cannot cast Character to Associative` |
| `document-redaction_2026-05-20_161428.edn` | `:success` but 0 redactions | Without `:available-code-nodes`, model wrote all-inline tree; same cast issue surfaced as silent 0-count |
| `document-redaction_2026-05-20_162046.edn` | `:code nil` from dscloj parser | With simplified catalog text, gpt-5.4 still failed to produce `:code` |
| `document-redaction_2026-05-20_162347.edn` | Phase-2 cast error | Catalog moved into instruction; tree generated but inline-fn cast crash |
| `document-redaction_2026-05-20_162703.edn` | `:success` but 0 redactions | gemini same-model; similar LLM-string-not-parsed pattern |
| **`document-redaction_2026-05-20_165215.edn`** | **`:success` 92 redactions / 28.9s / 52K tokens** | **After U11 landed: schema-driven structured output works end-to-end. Model uses apply-redactions twice via path A.** |

## What's locked-in for upstream main

Per the upgrades-to-main plan in [`docs/prd/orc-rlm-upgrades.md`](../../../docs/prd/orc-rlm-upgrades.md), this work locks in:

- **U11 — Schema-driven structured output for `:llm` writes.** New capability enabler. Critical for any benchmark that chains LLM-structured-data → code-transform. Without it, redaction-style workflows fail silently.

Other framework changes from PR08 that should land upstream:

- **`preview-vector` recursive previewing** for collections of large strings — image-heavy benchmarks need this
- **Bench runner LMDB map-size bump to 512MB** for image-heavy benchmarks — could be a configurable default

## Ground truth analysis methodology

The clean report's ground-truth section is built from:

1. **Predict-rlm's published 78 unique table entries** parsed from `output.md` regex on the markdown tables
2. **ORC's 92 `:targets-applied`** read from the run EDN
3. **Set-difference analysis** with label-stripping normalization (predict-rlm's "Date: March 15, 2025" matches ORC's "March 15, 2025")
4. **Manual review of the 23 ORC-only and 9 predict-rlm-only diffs** against the source PDF text and the criteria specification

Both the union AND a strict-criteria interpretation yield similar conclusions:
- ~85-90% recall for both systems
- ORC catches 6+ items predict-rlm clearly missed (transit number, business number, employment date ranges, recurring address occurrences)
- predict-rlm catches some granularity-merged address forms; ORC has them as constituent parts
- Both have 3-4 debatable inclusions (asset tags / Canada / city portions per criteria interpretation)

A rough manual count over the source document estimates **95-110 unique PII items** (depending on granularity choices). Both systems clear ~85% of that ceiling, with ORC slightly ahead on the specific items where the systems diverge.

## EDN artifacts preserved

All run files in `development/bench/predict-rlm-comparison/results/document-redaction_*` (both `.edn` data and `.trace.edn` mulog logs). The trace files include the full event-store activity per run — useful for diagnosing the journey above.
