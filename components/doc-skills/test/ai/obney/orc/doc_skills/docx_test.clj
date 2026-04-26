(ns ai.obney.orc.doc-skills.docx-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.obney.orc.doc-skills.core.docx :as docx]
            [clojure.java.io :as io])
  (:import [java.io File FileInputStream]
           [java.nio.file Files]
           [org.apache.poi.xwpf.usermodel XWPFDocument]))

(def ^:dynamic *tmp-dir* nil)

(defn tmp-fixture [t]
  (let [dir (.toFile (Files/createTempDirectory "doc-skills-docx-" (into-array java.nio.file.attribute.FileAttribute [])))]
    (try
      (binding [*tmp-dir* dir] (t))
      (finally
        (doseq [^File f (reverse (file-seq dir))] (.delete f))))))

(use-fixtures :each tmp-fixture)

(defn- read-back-text [path]
  (with-open [in (FileInputStream. (io/file path))
              doc (XWPFDocument. in)]
    (clojure.string/join "\n"
      (concat
        (mapv #(.getText %) (.getParagraphs doc))
        (for [t (.getTables doc)
              row (.getRows t)
              cell (.getTableCells row)]
          (.getText cell))))))

(deftest write-docx-roundtrip
  (testing "headings, paragraphs, bullets and tables are present in output"
    (let [out (str (.getPath *tmp-dir*) "/out.docx")
          path (docx/write-docx out
                  [[:h1 "Report Title"]
                   [:p  "Intro paragraph text."]
                   [:h2 "Bullets"]
                   [:bullets ["item one" "item two"]]
                   [:h2 "Table"]
                   [:table {:headers ["Col1" "Col2"]
                            :rows    [["a" "b"] ["c" "d"]]}]])
          text (read-back-text out)]
      (is (= out path))
      (is (.exists (io/file out)))
      (is (re-find #"Report Title" text))
      (is (re-find #"Intro paragraph text\." text))
      (is (re-find #"item one" text))
      (is (re-find #"item two" text))
      (is (re-find #"Col1" text))
      (is (re-find #"a" text))
      (is (re-find #"d" text)))))

(deftest markdown->elements-test
  (testing "tiny markdown parser handles headings, paragraphs, and bullets"
    (let [els (docx/markdown->elements
                "# Title\n\nIntro line.\n\n## Section\n\n- one\n- two\n\nClose paragraph.")]
      (is (= [:h1 "Title"] (first els)))
      (is (some #(= [:h2 "Section"] %) els))
      (is (some #(= :bullets (first %)) els))
      (is (some (fn [el] (= [:p "Close paragraph."] el)) els)))))

(deftest write-markdown-as-docx-test
  (let [out (str (.getPath *tmp-dir*) "/md.docx")
        _ (docx/write-markdown-as-docx out "# H\n\nbody one.\n\n- a\n- b")
        text (read-back-text out)]
    (is (re-find #"H" text))
    (is (re-find #"body one" text))
    (is (re-find #"• a" text))
    (is (re-find #"• b" text))))
