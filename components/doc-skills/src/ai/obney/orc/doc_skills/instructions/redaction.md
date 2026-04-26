# Redaction skill (PDFBox via Clojure SCI)

Use this in conjunction with the PDF skill to redact sensitive content from
PDF documents. Direct port of predict-rlm's redaction skill, adapted to the
`pdf/` Clojure API.

## Text redaction

Find rectangles for a literal text string on a page, then apply them:

    (let [hits (pdf/search-text "/sandbox/input/doc.pdf" 0 "John Doe")
          rects (mapv (fn [{:keys [rect]}]
                        {:page 0 :rect rect :fill [0 0 0]})
                      hits)]
      (pdf/redact-rects "/sandbox/input/doc.pdf"
                        "/sandbox/output/redacted.pdf"
                        rects))

If `pdf/search-text` returns no hits, try a shorter substring or different
casing — text in PDFs may be split across lines or have extra whitespace.

## Area redaction (signatures, logos, images)

For non-text elements, redact by bounding box. Coordinates are PDF points
(72pt/inch), top-left origin:

    (pdf/redact-rects "/sandbox/input/doc.pdf"
                      "/sandbox/output/redacted.pdf"
                      [{:page 0 :rect [400 50 580 110] :fill [0 0 0]}])

To estimate coordinates, render the page as an image and ask the sub-LM to
identify a bounding box:

    (let [uri (pdf/page-image-data-uri "/sandbox/input/doc.pdf" 0)
          {:keys [bbox]} (predict
                          {:name "find-signature-bbox"
                           :inputs {:page-image uri}
                           :instructions "Identify the bounding box of the signature.
                                          Return [x0 y0 x1 y1] in PDF points."
                           :schema [:map [:bbox [:vector :double]]]})]
      (pdf/redact-rects "/sandbox/input/doc.pdf"
                        "/sandbox/output/redacted.pdf"
                        [{:page 0 :rect bbox :fill [0 0 0]}]))

## Visual verification

After redaction, re-render the page and ask a vision sub-LM if any PII
remains visible:

    (let [check-uri (pdf/page-image-data-uri "/sandbox/output/redacted.pdf" 0)
          {:keys [remaining-pii]}
          (predict
            {:name "verify-redaction"
             :inputs {:page-image check-uri}
             :instructions "List any PII still visible on this page."
             :schema [:map [:remaining-pii [:vector :string]]]})]
      (when (seq remaining-pii)
        (println "WARNING: still visible:" remaining-pii)))

## Important

- `pdf/redact-rects` writes a NEW PDF — it does not mutate the input. Pass
  distinct in/out paths.
- Coordinates are in PDF points, top-left origin. Width = x1 - x0.
- Save redacted PDFs under `/sandbox/output/redacted_documents/` so the
  output sync collects them.
- If you redact multiple pages, build one combined `rect-specs` vector and
  call `pdf/redact-rects` once per source PDF.
