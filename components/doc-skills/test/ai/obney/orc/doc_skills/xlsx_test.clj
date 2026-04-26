(ns ai.obney.orc.doc-skills.xlsx-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.obney.orc.doc-skills.core.xlsx :as xlsx]
            [clojure.java.io :as io])
  (:import [java.io File]
           [java.nio.file Files]))

(def ^:dynamic *tmp-dir* nil)

(defn tmp-fixture [t]
  (let [dir (.toFile (Files/createTempDirectory "doc-skills-xlsx-" (into-array java.nio.file.attribute.FileAttribute [])))]
    (try
      (binding [*tmp-dir* dir] (t))
      (finally
        (doseq [^File f (reverse (file-seq dir))] (.delete f))))))

(use-fixtures :each tmp-fixture)

(deftest write-and-read-roundtrip
  (testing "write-workbook then list-sheets + read-sheet returns the same shape"
    (let [out (str (.getPath *tmp-dir*) "/out.xlsx")
          spec [{:name "Summary"
                 :columns [{:header "Vendor"     :width 20}
                           {:header "Invoice #"  :width 14}
                           {:header "Total"      :width 12}]
                 :rows    [["Acme"     "INV-1" 100.0]
                           ["Glob Co." "GT-2"  250.5]]}
                {:name "Details"
                 :columns [{:header "Item" :width 20}
                           {:header "Qty"  :width 8}]
                 :rows    [["Widget A" 10]
                           ["Service B" 5]]}]
          path (xlsx/write-workbook out spec)]
      (is (= out path))
      (is (.exists (io/file out)))
      (is (= ["Summary" "Details"] (xlsx/list-sheets out)))
      (let [summary (xlsx/read-sheet out "Summary")]
        (is (= ["Vendor" "Invoice #" "Total"] (first summary)))
        (is (= "Acme" (get-in summary [1 0])))
        (is (= 100.0 (get-in summary [1 2])))
        (is (= "INV-1" (get-in summary [1 1])))))))

(deftest read-sheet-as-maps-test
  (let [out (str (.getPath *tmp-dir*) "/out.xlsx")
        _ (xlsx/write-workbook out
                               [{:name "Rows"
                                 :columns [{:header "Name"} {:header "Score"}]
                                 :rows [["alice" 1.0] ["bob" 2.0]]}])
        rows (xlsx/read-sheet-as-maps out "Rows")]
    (is (= [{:Name "alice" :Score 1.0}
            {:Name "bob"   :Score 2.0}]
           rows))))

(deftest mixed-types-test
  (testing "strings, numbers, booleans, nil all write/read correctly"
    (let [out (str (.getPath *tmp-dir*) "/out.xlsx")
          _ (xlsx/write-workbook out
                                 [{:name "Mixed"
                                   :rows [["s" 1 true nil 3.14]]}])
          rows (xlsx/read-sheet out "Mixed")]
      (is (= 1 (count rows)))
      (let [r (first rows)]
        (is (= "s" (nth r 0)))
        (is (= 1.0 (nth r 1)))
        (is (= true (nth r 2)))
        (is (nil? (nth r 3)))
        (is (= 3.14 (nth r 4)))))))
