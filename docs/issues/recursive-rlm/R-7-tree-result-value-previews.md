# R-7: Tree-result value previews + verify-before-final prompt nudge

## Parent

`docs/prd/rlm-recursive-emit-tree.md`. Observability gap discovered while root-causing the image_analysis A-Z-zero output during the R-Default verification sweep on `feature/core-orc-upgrades`.

## Why this exists (root-cause from the audit)

In the post-R-Default image_analysis run (2026-05-22, sub-model gemini-3-flash-preview), the model emitted a tree that:

1. Successfully transcribed the image via two OCR LLM passes (real text in `:ocr_pass1` and `:ocr_pass2`).
2. Ran a `:code` node that reconciled the passes and computed A-Z letter counts.
3. Wrote the formatted result to `:answer`.

The `:code` node had a **char-vs-string type bug** in the model-authored Clojure:

```clojure
letters (->> reconciled clojure.string/lower-case (re-seq #"[a-z]") frequencies)
;; letters is keyed by single-char STRINGS: {"o" 1234, "a" 987, ...}
alphabet (map char (range (int \a) (inc (int \z))))
;; alphabet is a sequence of CHARS: (\a \b \c ...)
(get letters ch 0)
;; ch is \a (char); letters is keyed by "a" (string) → always nil → 0
```

Result: `:answer` contained correct OCR text plus a broken counts block of `A: 0 / B: 0 / ... / Z: 0`. The tree's `:status :success` was structurally correct (no node failed; no validate-final! violation) but the **payload was semantically broken** and the model never noticed.

At iter 1 the model called `(final! {:answer (get-var :answer)})` — accepting the broken output — because:

- The `build-iteration-history` "Result:" field shows the **compiled tree S-expression**, not the value of any written variable.
- The `:tree-results` summary (`compute-tree-result-summary`) reports `:outputs-keys [:answer]`, `:status :success`, `:nodes-succeeded 3` — but **no preview of the actual content** of the written variables.
- The recursive-mode prompt does not encourage a "verify before `(final! ...)`" step.

The model could have inspected via `(get-var :answer)` and would have spotted the issue, but nothing in the context signaled that inspection was warranted.

## What to build

Two cooperating mitigations, both small and additive:

### R-7a — value previews in `:tree-results` summary

For each key in `:outputs-keys`, add a per-key `:preview` map keyed by output-key so the model sees a sample of what was written. Shape:

```clojure
{:outputs-keys [:answer]
 :outputs-previews {:answer "Image 1\nExtracted text:\nONTARIO POWER AUTHORITY\nTM\nFEED-IN TARIFF microFIT CONTRACT\nVersion 2.0\n..."}}
```

Rules:

- Strings: first 500 chars (truncate marker `"…(truncated, full N chars)"` on overflow). Embedded newlines preserved verbatim — the user's standing "no truncating model output" rule applies to model-written code/trees/results when feeding them back into a *prompt*. The preview here is a NEW summary string explicitly labeled as a sample; total content remains accessible via `(get-var :answer)` and `(node-output ...)`.
- Collections (vec/seq/list): include `:count`, the first 3 elements via pr-str (each capped at 200 chars).
- Maps: include `:keys` (sorted), and the first 3 `(k, v-preview)` entries.
- Scalars (number, boolean, keyword, nil): pr-str directly.
- The preview is purely informational; it does not change `:outputs` retrieval or sandbox merge.

### R-7c — recursive-mode prompt: verify-before-final guidance

Add a short section to the recursive-mode researcher prompt that recommends a peek-and-sanity-check step before terminating:

> Before calling `(final! {...})` on a key whose value came from a prior `emit-tree!`, briefly inspect the value via `(get-var :key)` and confirm it looks like a sane final answer (right shape, plausible content, no obviously broken markers like "A: 0 / B: 0 / ..." when the input clearly contained text). If anything looks off, emit a corrective tree to fix it rather than finalizing on a broken payload.

Keep it under ~80 words so it doesn't dilute the existing prompt. Sentinel phrasing: "verify before final".

## End-to-end behavior

- R-7a alone gives the model visible-by-default evidence that something is wrong (the broken A-Z block shows up in the next prompt's `:tree-results` summary).
- R-7c reinforces by explicitly telling the model to peek.
- Together: in the image_analysis case, iter 1 would (a) see the preview with all zeros, (b) emit a corrective `:code` node tree that fixes the char/string mismatch, (c) `(final! ...)` on the corrected answer.

## Acceptance criteria

- [ ] Unit test (R-7a): `compute-tree-result-summary` for a phase2-result whose `:outputs` contains `{:answer "ONTARIO POWER AUTHORITY\n..." (3KB)}` includes `:outputs-previews {:answer "ONTARIO POWER AUTHORITY\n..."}` truncated at 500 chars with the overflow marker.
- [ ] Unit test (R-7a, collection): summary for `:outputs` `{:lines (vec of 50 strings)}` includes `:outputs-previews {:lines {:count 50 :sample-3 [...]}}`.
- [ ] Unit test (R-7a, map): summary for `:outputs` `{:report {:title "..." :body "..." :score 4}}` includes `:outputs-previews {:report {:keys [:body :score :title] :sample-3 [[:body "..."] [:score 4] [:title "..."]]}}` (or equivalent compact shape).
- [ ] Unit test (R-7c): recursive-mode prompt builder output (the string passed to the LLM) contains the literal sentinel phrase "verify before final" so a regex assertion can pin it.
- [ ] Live verify: re-run `image_analysis` (gemini sub-model) — model now either (a) inspects `(get-var :answer)` mid-iter, OR (b) emits a corrective tree on iter 2 after seeing the preview show all-zero counts. Final `:answer` letter counts are non-zero.
- [ ] No regression: all RLM brick tests stay GREEN. The other 4 predict-rlm benchmarks (document_redaction, invoice_processing, contract_comparison, document_analysis) re-run with the new preview field and prompt still produce valid outputs.

## Blocked by

- R-3 + R-4 + R-5 + R-6 + R-Default (all shipped).

## Notes

- The preview field is purely additive — existing readers of `:tree-results` (currently only the prompt context and the `(tree-detail ...)` primitive) don't break; they just see an extra key.
- Token cost of R-7a is bounded: ≤ 500 chars per output key, typically 1-4 output keys per tree, so ≤ ~2K chars per `:tree-results` entry. Negligible at the iteration-history level.
- R-7c is purely advisory; if the model ignores the guidance the failure mode is what we already have today, so this can only help.
- Out of scope: programmatic semantic validators (e.g., "letter counts must be ≥ 1 if any text exists") — those require task knowledge and belong in evaluation criteria, not the framework.
