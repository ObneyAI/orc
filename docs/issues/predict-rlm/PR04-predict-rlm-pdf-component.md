# PR04 — `predict-rlm-pdf` component (PDFBox wrapper)

## Parent

`docs/prd/predict-rlm-benchmark-ports.md`

## What to build

A new Polylith brick that wraps Apache PDFBox to provide host-side PDF rendering and per-page text extraction. Used by the runner to pre-load PDFs into the blackboard before the RLM researcher starts. Deep module: pure I/O against PDF files, no LLM, deterministic.

Brick layout:

- `components/predict-rlm-pdf/deps.edn` — declares `org.apache.pdfbox/pdfbox` (current stable version) as the only non-Clojure dep
- `components/predict-rlm-pdf/src/ai/obney/orc/predict_rlm_pdf/interface.clj` — public API
- `components/predict-rlm-pdf/src/ai/obney/orc/predict_rlm_pdf/core/render.clj` — PDFRenderer wrapping
- `components/predict-rlm-pdf/src/ai/obney/orc/predict_rlm_pdf/core/extract.clj` — PDFTextStripper wrapping
- `components/predict-rlm-pdf/test/ai/obney/orc/predict_rlm_pdf/interface_test.clj` — unit tests
- `components/predict-rlm-pdf/test/fixtures/` — small committed fixture PDF (≤50KB)

Public API:

```
(render-pages-as-data-uris path & {:keys [dpi] :or {dpi 200}})
;; → vector of "data:image/png;base64,..." strings, one per page

(extract-pages-as-text path)
;; → vector of strings, one per page
```

Register the brick in the Polylith workspace (`workspace.edn` or equivalent) and add it as a `:local/root` dep in any consumer.

## Acceptance criteria

- [ ] Component exists at `components/predict-rlm-pdf/` with the layout above
- [ ] `deps.edn` declares the PDFBox dep and nothing else beyond standard Clojure
- [ ] Workspace registration so `clj -M:poly test brick:predict-rlm-pdf` discovers it
- [ ] Fixture PDF (1-2 pages, small) committed under `test/fixtures/`
- [ ] `(render-pages-as-data-uris fixture-path)` returns a vector of length equal to fixture page count
- [ ] Each returned string starts with `"data:image/png;base64,"` and decodes via `java.util.Base64` to non-empty PNG bytes (first 8 bytes match PNG signature `89 50 4E 47 0D 0A 1A 0A`)
- [ ] `(extract-pages-as-text fixture-path)` returns a vector of length equal to fixture page count; each element contains expected fixture substrings
- [ ] DPI parameter influences output size — assert that `(dpi 100)` and `(dpi 300)` produce different byte counts for the same page
- [ ] All unit tests pass via `clj -M:poly test brick:predict-rlm-pdf`
- [ ] No other component pulls PDFBox transitively (verify via dep tree inspection)

## Blocked by

PR01
