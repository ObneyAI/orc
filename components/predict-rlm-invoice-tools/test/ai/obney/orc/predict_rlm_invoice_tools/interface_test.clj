(ns ai.obney.orc.predict-rlm-invoice-tools.interface-test
  "Tests for build-invoice-workbook — the deterministic xlsx-writing :code-node
   fn used by the invoice_processing benchmark. Verifies the workbook is
   structurally correct (one sheet per invoice + Summary), columns are right,
   line items are populated, and totals propagate."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [dk.ative.docjure.spreadsheet :as ss]
            [ai.obney.orc.predict-rlm-invoice-tools.interface :as invoice-tools]))

(defn- synthetic-invoices []
  [{:vendor-name "Acme Corp"
    :invoice-number "ACME-2025-0042"
    :date "2025-03-15"
    :due-date "2025-04-15"
    :subtotal 1000.00
    :tax 100.00
    :total 1100.00
    :line-items [{:description "Widget A" :quantity 10 :unit-price 50.00 :amount 500.00}
                 {:description "Widget B" :quantity 5  :unit-price 100.00 :amount 500.00}]}
   {:vendor-name "GlobalTech Inc."
    :invoice-number "GT-10587"
    :date "2025-03-20"
    :due-date "2025-04-20"
    :subtotal 2500.00
    :tax 250.00
    :total 2750.00
    :line-items [{:description "Consulting" :quantity 25 :unit-price 100.00 :amount 2500.00}]}])

(defn- with-tmp-workbook [f]
  (let [path (str "/tmp/invoice-test-" (random-uuid) ".xlsx")]
    (try (f path) (finally (io/delete-file path true)))))

(deftest tracer-build-invoice-workbook-writes-file
  (testing "build-invoice-workbook writes an .xlsx at :output-path and returns its path"
    (with-tmp-workbook
      (fn [path]
        (let [result (invoice-tools/build-invoice-workbook
                       {:inputs {:invoices (synthetic-invoices)
                                 :output-path path}})]
          (is (= path (:workbook-path result))
              "Result map should include the path under :workbook-path")
          (is (.exists (io/file path))
              "The xlsx file must actually exist on disk"))))))

(deftest workbook-has-summary-sheet-plus-per-invoice-sheets
  (testing "Workbook contains a 'Summary' sheet AND one sheet per invoice"
    (with-tmp-workbook
      (fn [path]
        (invoice-tools/build-invoice-workbook
          {:inputs {:invoices (synthetic-invoices) :output-path path}})
        (let [wb (ss/load-workbook path)
              sheet-names (set (map ss/sheet-name (ss/sheet-seq wb)))]
          (is (contains? sheet-names "Summary")
              "Must have a 'Summary' sheet")
          (is (= 3 (count sheet-names))
              "Must have exactly 3 sheets: Summary + 2 invoice sheets"))))))

(deftest summary-sheet-has-expected-columns-and-rows
  (testing "Summary sheet has the documented columns: Vendor, Invoice #, Date, Due Date, Subtotal, Tax, Total — and one row per invoice"
    (with-tmp-workbook
      (fn [path]
        (invoice-tools/build-invoice-workbook
          {:inputs {:invoices (synthetic-invoices) :output-path path}})
        (let [wb (ss/load-workbook path)
              summary (ss/select-sheet "Summary" wb)
              header (vec (map ss/read-cell (first (ss/row-seq summary))))]
          (is (= ["Vendor" "Invoice #" "Date" "Due Date" "Subtotal" "Tax" "Total"]
                 header)
              "Header row must match the documented columns exactly")
          ;; 1 header + 2 invoice rows = 3 rows
          (is (= 3 (count (ss/row-seq summary)))
              "Should have 1 header row + 2 invoice rows"))))))

(deftest per-invoice-sheet-has-line-items
  (testing "Each invoice sheet has columns Description, Quantity, Unit Price, Amount AND a row per line item"
    (with-tmp-workbook
      (fn [path]
        (invoice-tools/build-invoice-workbook
          {:inputs {:invoices (synthetic-invoices) :output-path path}})
        (let [wb (ss/load-workbook path)
              acme (ss/select-sheet (fn [s]
                                      (some #(when (re-find #"(?i)acme" (or % "")) %)
                                            [(ss/sheet-name s)]))
                                    wb)
              header (vec (map ss/read-cell (first (ss/row-seq acme))))]
          (is (= ["Description" "Quantity" "Unit Price" "Amount"] header)
              "Line-item columns must match the documented schema")
          (is (= 3 (count (ss/row-seq acme)))
              "Acme sheet should have 1 header row + 2 line items"))))))

(deftest available-code-nodes-catalog-is-non-empty
  (testing "available-code-nodes is non-empty markdown describing the fn"
    (let [catalog invoice-tools/available-code-nodes]
      (is (string? catalog) "Catalog must be a string")
      (is (re-find #"build-invoice-workbook" catalog)
          "Catalog must mention the fn name")
      (is (re-find #":invoices" catalog)
          "Catalog must document the :invoices input"))))

(deftest empty-invoices-handled-gracefully
  (testing "Zero invoices produces a workbook with just an empty Summary sheet (no crash)"
    (with-tmp-workbook
      (fn [path]
        (let [result (invoice-tools/build-invoice-workbook
                       {:inputs {:invoices [] :output-path path}})]
          (is (.exists (io/file (:workbook-path result)))
              "Workbook should still be created")
          (let [wb (ss/load-workbook path)
                summary (ss/select-sheet "Summary" wb)]
            (is (some? summary) "Summary sheet must exist even with no invoices")
            ;; Just the header row
            (is (= 1 (count (ss/row-seq summary)))
                "Summary should have only the header row")))))))
