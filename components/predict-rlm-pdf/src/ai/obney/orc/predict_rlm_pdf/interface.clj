(ns ai.obney.orc.predict-rlm-pdf.interface
  "Host-side PDF rendering and per-page text extraction via Apache PDFBox.

   Pure I/O functions. No LLM, no state, deterministic.

   Used by per-benchmark task runners to pre-load PDF content into the
   blackboard before the RLM researcher starts."
  (:import [java.awt.image BufferedImage]
           [java.io ByteArrayOutputStream File]
           [java.util Base64]
           [javax.imageio ImageIO]
           [org.apache.pdfbox Loader]
           [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.rendering ImageType PDFRenderer]
           [org.apache.pdfbox.text PDFTextStripper]))

(def ^:private default-dpi 200)

(defn page-count
  "Return the number of pages in the PDF at the given path.

   Args:
     path - absolute or relative filesystem path to a PDF file (string)
   Returns:
     non-negative integer page count"
  [^String path]
  (with-open [^PDDocument doc (Loader/loadPDF (File. path))]
    (.getNumberOfPages doc)))

(defn- render-page-data-uri
  [^PDFRenderer renderer encoder ^long dpi ^long page-index]
  (let [^BufferedImage img (.renderImageWithDPI renderer (int page-index) (float dpi) ImageType/RGB)
        baos (ByteArrayOutputStream.)]
    (ImageIO/write img "png" baos)
    (str "data:image/png;base64,"
         (.encodeToString ^java.util.Base64$Encoder encoder (.toByteArray baos)))))

(defn render-pages-as-data-uris
  "Render each page of the PDF at `path` as a base64-encoded PNG data URI.

   Args:
     path - filesystem path to a PDF
     opts - optional map; supports :dpi (default 200)
   Returns:
     vector of strings, each starting with `data:image/png;base64,`"
  ([^String path]
   (render-pages-as-data-uris path nil))
  ([^String path {:keys [dpi] :or {dpi default-dpi}}]
   (with-open [^PDDocument doc (Loader/loadPDF (File. path))]
     (let [renderer (PDFRenderer. doc)
           encoder (Base64/getEncoder)
           n (.getNumberOfPages doc)]
       (mapv #(render-page-data-uri renderer encoder dpi %)
             (range n))))))

(defn extract-pages-as-text
  "Extract the text content of each page in the PDF at `path`.

   Args:
     path - filesystem path to a PDF
   Returns:
     vector of strings, one per page, in document order"
  [^String path]
  (with-open [^PDDocument doc (Loader/loadPDF (File. path))]
    (let [n (.getNumberOfPages doc)
          stripper (PDFTextStripper.)]
      (mapv (fn [page-1-indexed]
              (.setStartPage stripper (int page-1-indexed))
              (.setEndPage stripper (int page-1-indexed))
              (.getText stripper doc))
            (range 1 (inc n))))))
