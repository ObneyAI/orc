# PDF skill (Apache PDFBox via Clojure SCI)

You can read, render, and inspect PDF files via the `pdf/` namespace. PDFs
are passed by file path. Page numbers are 0-indexed.

## Inspecting a document

    (pdf/page-count "/sandbox/input/doc.pdf")
    ;; => 12

    (pdf/page-text "/sandbox/input/doc.pdf" 0)
    ;; => "First page text…"

    (pdf/document-text "/sandbox/input/doc.pdf")
    ;; => "Full document text…"

## Rendering a page as an image (for vision models)

Render and write to a file:

    (pdf/page-image "/sandbox/input/doc.pdf" 0
                    "/sandbox/output/page-0.png"
                    :dpi 200)
    ;; => "/sandbox/output/page-0.png"

Or get a base64 data URI directly (good for inline `(predict {…})` calls
with image inputs):

    (pdf/page-image-data-uri "/sandbox/input/doc.pdf" 0 :dpi 200)
    ;; => "data:image/png;base64,iVBORw0KGgo…"

## Coordinate-aware text search (for redaction)

`pdf/search-text` returns rectangles for matches:

    (pdf/search-text "/sandbox/input/doc.pdf" 0 "John Doe")
    ;; => [{:rect [120.4 633.1 187.2 645.3] :match "John Doe"} ...]

Coordinates are in PDF points (72pt/inch), top-left origin.

If `search-text` returns no hits, try a shorter substring or different
casing. PDF text may be split across lines or have extra whitespace.

## Page geometry

    (pdf/page-bounds "/sandbox/input/doc.pdf" 0)
    ;; => {:width 612.0 :height 792.0}

## Pattern: parallel page extraction

When extracting structured data from many pages, render each page once and
fan out with `predict-all`:

    (let [pages (vec (range (pdf/page-count "/sandbox/input/doc.pdf")))
          uris  (mapv #(pdf/page-image-data-uri "/sandbox/input/doc.pdf" %) pages)
          results (predict-all
                    {:name "extract-page"
                     :items uris
                     :as :image
                     :inputs {}
                     :instructions "Extract … from this page image."
                     :schema [:map [:fields [:vector :string]]]
                     :max-concurrency 8})]
      ;; results is a vector aligned with pages
      …)
