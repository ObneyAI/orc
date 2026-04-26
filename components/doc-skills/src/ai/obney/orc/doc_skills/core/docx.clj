(ns ai.obney.orc.doc-skills.core.docx
  "Apache POI XWPF wrapper for writing .docx files.

   Documents are described as a vector of element forms — a tiny Hiccup-like
   DSL that an LLM can construct from plain Clojure data:

     [[:h1 \"Report Title\"]
      [:h2 \"Section\"]
      [:p  \"A paragraph of text.\"]
      [:bullets [\"item one\" \"item two\"]]
      [:table {:headers [\"Col1\" \"Col2\"]
               :rows    [[\"a\" \"b\"] [\"c\" \"d\"]]}]]

   Heading levels :h1..:h6 map to POI heading styles."
  (:require [clojure.java.io :as io])
  (:import [org.apache.poi.xwpf.usermodel
            XWPFDocument XWPFParagraph XWPFRun XWPFTable XWPFTableRow XWPFTableCell
            ParagraphAlignment]
           [java.io File FileOutputStream]))

(set! *warn-on-reflection* true)

(defn- ^File ->file [path]
  (cond
    (instance? File path) path
    (string? path) (io/file path)
    :else (throw (ex-info "Expected file path or File"
                          {:type :docx/bad-path :got path}))))

(defn- ensure-parent! [^File f]
  (when-let [parent (.getParentFile f)]
    (.mkdirs parent)))

(defn- run-text! [^XWPFParagraph p text & {:keys [bold size]}]
  (let [^XWPFRun r (.createRun p)]
    (.setText r (str text))
    (when bold (.setBold r true))
    (when size (.setFontSize r (int size)))
    r))

(defn- heading-size [level]
  (case (int level) 1 22 2 18 3 16 14))

(defn- heading! [^XWPFDocument doc level text]
  (let [p (.createParagraph doc)]
    (.setStyle p (str "Heading" level))
    (run-text! p text :bold true :size (heading-size level))))

(defn- paragraph! [^XWPFDocument doc text]
  (let [p (.createParagraph doc)]
    (run-text! p (str text))))

(defn- bullets! [^XWPFDocument doc items]
  ;; POI's bullet/numbering API requires building XWPFNumbering CT objects;
  ;; for our LLM-output use case, prefixing with a Unicode bullet renders
  ;; identically in Word and avoids the per-doc numbering definition dance.
  (doseq [item items]
    (let [p (.createParagraph doc)]
      (run-text! p (str "• " item)))))

(defn- table! [^XWPFDocument doc {:keys [headers rows]}]
  (let [n-cols (max (count headers) (apply max 0 (map count rows)))
        ^XWPFTable tbl (.createTable doc 1 (int n-cols))
        ^XWPFTableRow header-row (.getRow tbl 0)]
    (when (seq headers)
      (doseq [[i h] (map-indexed vector headers)]
        (let [^XWPFTableCell cell (.getCell header-row (int i))
              p (or (first (.getParagraphs cell)) (.addParagraph cell))]
          (run-text! p (str h) :bold true))))
    (doseq [row rows]
      (let [^XWPFTableRow tr (.createRow tbl)]
        (doseq [[i v] (map-indexed vector row)]
          (let [^XWPFTableCell cell (.getCell tr (int i))
                p (or (first (.getParagraphs cell)) (.addParagraph cell))]
            (run-text! p (str v))))))))

(defn- render-element! [^XWPFDocument doc element]
  (let [[tag & args] element]
    (case tag
      :h1 (heading! doc 1 (first args))
      :h2 (heading! doc 2 (first args))
      :h3 (heading! doc 3 (first args))
      :h4 (heading! doc 4 (first args))
      :h5 (heading! doc 5 (first args))
      :h6 (heading! doc 6 (first args))
      :p  (paragraph! doc (first args))
      :bullets (bullets! doc (first args))
      :table (table! doc (first args))
      :br (.createParagraph doc)
      (paragraph! doc (pr-str element)))))

(defn write-docx
  "Write a .docx document to `out-path` from a vector of element forms.
   Returns out-path."
  [out-path elements]
  (let [out (->file out-path)]
    (ensure-parent! out)
    (with-open [doc (XWPFDocument.)
                fos (FileOutputStream. out)]
      (doseq [el elements]
        (render-element! doc el))
      (.write doc fos))
    (str out)))

(defn markdown->elements
  "Lightweight markdown → element vector translator.
   Supports # h1, ## h2, ### h3, blank-line-separated paragraphs,
   and `- ` bullet lines. No inline emphasis processing."
  [md]
  (let [lines (clojure.string/split-lines (or md ""))
        flush-bullets (fn [acc bs]
                        (if (seq bs) (conj acc [:bullets bs]) acc))
        flush-para (fn [acc para-lines]
                     (if (seq para-lines)
                       (conj acc [:p (clojure.string/join " " para-lines)])
                       acc))]
    (loop [acc []
           bullets []
           para []
           [line & more] lines]
      (cond
        (nil? line)
        (-> acc (flush-bullets bullets) (flush-para para))

        (clojure.string/starts-with? line "### ")
        (recur (-> acc (flush-bullets bullets) (flush-para para)
                   (conj [:h3 (subs line 4)]))
               [] [] more)

        (clojure.string/starts-with? line "## ")
        (recur (-> acc (flush-bullets bullets) (flush-para para)
                   (conj [:h2 (subs line 3)]))
               [] [] more)

        (clojure.string/starts-with? line "# ")
        (recur (-> acc (flush-bullets bullets) (flush-para para)
                   (conj [:h1 (subs line 2)]))
               [] [] more)

        (clojure.string/starts-with? line "- ")
        (recur (flush-para acc para) (conj bullets (subs line 2)) [] more)

        (clojure.string/blank? line)
        (recur (-> acc (flush-bullets bullets) (flush-para para))
               [] [] more)

        :else
        (recur (flush-bullets acc bullets) [] (conj para line) more)))))

(defn write-markdown-as-docx
  "Convenience: parse `md` markdown into elements and write a .docx."
  [out-path md]
  (write-docx out-path (markdown->elements md)))
