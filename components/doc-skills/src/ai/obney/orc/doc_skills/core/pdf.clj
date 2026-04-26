(ns ai.obney.orc.doc-skills.core.pdf
  "Apache PDFBox 3.0.x wrapper for use as an SCI-exposed skill.

   PDFs are passed as file paths; functions open documents on demand and
   close them deterministically. Page numbers are 0-indexed throughout
   (matches predict-rlm's pymupdf API)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [org.apache.pdfbox Loader]
           [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.text PDFTextStripper TextPosition]
           [org.apache.pdfbox.rendering PDFRenderer ImageType]
           [org.apache.pdfbox.pdmodel.graphics.color PDColor PDDeviceRGB]
           [java.awt.image BufferedImage]
           [java.io File ByteArrayOutputStream]
           [javax.imageio ImageIO]
           [java.util Base64]))

(set! *warn-on-reflection* true)

(defn- ^File ->file
  "Coerce a path/string/File into a java.io.File."
  [path]
  (cond
    (instance? File path) path
    (string? path) (io/file path)
    :else (throw (ex-info "Expected file path or File"
                          {:type :pdf/bad-path :got path}))))

(defn ^PDDocument load-doc
  "Open a PDF document. Caller is responsible for (.close doc)."
  [path]
  (Loader/loadPDF (->file path)))

(defn close-doc [^PDDocument doc] (.close doc))

(defn page-count
  "Return the number of pages in the PDF at `path`."
  [path]
  (with-open [doc (load-doc path)]
    (.getNumberOfPages doc)))

(defn page-text
  "Return the extracted text of page `n` (0-indexed)."
  [path n]
  (with-open [doc (load-doc path)]
    (let [stripper (doto (PDFTextStripper.)
                     (.setStartPage (inc n))
                     (.setEndPage (inc n)))]
      (.getText stripper doc))))

(defn document-text
  "Return the full extracted text of the document."
  [path]
  (with-open [doc (load-doc path)]
    (.getText (PDFTextStripper.) doc)))

(defn- ensure-parent! [^File f]
  (when-let [parent (.getParentFile f)]
    (.mkdirs parent)))

(defn page-image
  "Render page `n` (0-indexed) as a PNG file at `out-path`. Returns out-path.
   Options:
     :dpi  - DPI for rendering (default 200)"
  [path n out-path & {:keys [dpi] :or {dpi 200}}]
  (with-open [doc (load-doc path)]
    (let [renderer (PDFRenderer. doc)
          ^BufferedImage img (.renderImageWithDPI renderer (int n) (float dpi) ImageType/RGB)
          out (->file out-path)]
      (ensure-parent! out)
      (ImageIO/write img "png" out)
      (str out))))

(defn page-image-data-uri
  "Render page `n` as a PNG and return a base64 data URI string. Useful for
   feeding into vision LMs that expect inline image data."
  [path n & {:keys [dpi] :or {dpi 200}}]
  (with-open [doc (load-doc path)]
    (let [renderer (PDFRenderer. doc)
          ^BufferedImage img (.renderImageWithDPI renderer (int n) (float dpi) ImageType/RGB)
          baos (ByteArrayOutputStream.)]
      (ImageIO/write img "png" baos)
      (str "data:image/png;base64,"
           (.encodeToString (Base64/getEncoder) (.toByteArray baos))))))

(defn search-text
  "Search for `query` text on page `n` (0-indexed). Returns a vector of hits,
   each a map with :rect [x0 y0 x1 y1] (PDF points, top-left origin) and
   :match (the matched substring).

   This is a coordinate-search by re-running text extraction with a position
   capture; suitable for guiding redaction. For complex layouts, consider
   rendering the page and using a vision model for bounding boxes."
  [path n query]
  (with-open [doc (load-doc path)]
    (let [hits (volatile! [])
          stripper (proxy [PDFTextStripper] []
                     (writeString [^String text positions]
                       (when (str/includes? text query)
                         (let [start (str/index-of text query)
                               end (+ start (count query))
                               sub (->> positions
                                        (drop start)
                                        (take (- end start))
                                        (mapv (fn [^TextPosition p] p)))
                               xs0 (mapv (fn [^TextPosition p] (.getXDirAdj p)) sub)
                               xs1 (mapv (fn [^TextPosition p]
                                           (+ (.getXDirAdj p) (.getWidthDirAdj p)))
                                         sub)
                               ys  (mapv (fn [^TextPosition p] (.getYDirAdj p)) sub)
                               hs  (mapv (fn [^TextPosition p] (.getHeightDir p)) sub)
                               x0 (apply min xs0)
                               x1 (apply max xs1)
                               y-bottom (apply min ys)
                               max-h (apply max hs)]
                           (vswap! hits conj
                                   {:rect  [x0 (- y-bottom max-h) x1 y-bottom]
                                    :match query})))))]
      (.setStartPage ^PDFTextStripper stripper (inc n))
      (.setEndPage ^PDFTextStripper stripper (inc n))
      (.getText ^PDFTextStripper stripper doc)
      @hits)))

(defn redact-rects
  "Apply redaction rectangles to specific pages of a PDF and save to `out-path`.

   `rect-specs` is a vector of {:page n :rect [x0 y0 x1 y1] :fill [r g b]?}
   Page numbers are 0-indexed. Coordinates are PDF points (top-left origin,
   72pt/inch). Default fill color is black [0 0 0].

   Returns out-path."
  [path out-path rect-specs]
  (with-open [doc (load-doc path)]
    (let [grouped (group-by :page rect-specs)]
      (doseq [[page-num specs] grouped]
        (let [page (.getPage doc (int page-num))]
          (doseq [{:keys [rect fill] :or {fill [0 0 0]}} specs]
            (let [[x0 y0 x1 y1] rect
                  redaction (org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquare.)
                  rect-pdf (org.apache.pdfbox.pdmodel.common.PDRectangle.
                             (float x0) (float y0)
                             (float (- x1 x0)) (float (- y1 y0)))
                  color (PDColor. (float-array (mapv float fill)) PDDeviceRGB/INSTANCE)]
              (.setRectangle redaction rect-pdf)
              (.setInteriorColor redaction color)
              (.setColor redaction color)
              (.setConstantOpacity redaction (float 1.0))
              (.add (.getAnnotations page) redaction)))))
      (let [out (->file out-path)]
        (ensure-parent! out)
        (.save doc out)
        (str out)))))

(defn page-bounds
  "Return the page rectangle for page `n` as {:width w :height h} in PDF points."
  [path n]
  (with-open [doc (load-doc path)]
    (let [page (.getPage doc (int n))
          rect (.getMediaBox page)]
      {:width (.getWidth rect)
       :height (.getHeight rect)})))
