(ns ai.obney.orc.predict-rlm-invoice-tools.interface
  "Invoice processing tools for the predict-rlm comparison benchmark.

   Provides a pre-built :code-node function that takes a vector of Invoice
   maps and produces an .xlsx workbook via docjure (Apache POI underneath).

   The model identifies invoice data via :llm nodes (vision per page); this
   brick handles the deterministic xlsx-writing step.

   Public API:
   - (build-invoice-workbook {:keys [inputs]}) → {:workbook-path \"...\"}
   - available-code-nodes — markdown catalog string"
  (:require [clojure.java.io :as io]
            [dk.ative.docjure.spreadsheet :as ss]))

;; =============================================================================
;; Workbook construction
;; =============================================================================

(def ^:private summary-columns
  ["Vendor" "Invoice #" "Date" "Due Date" "Subtotal" "Tax" "Total"])

(def ^:private line-item-columns
  ["Description" "Quantity" "Unit Price" "Amount"])

(defn- invoice->summary-row
  "Project an invoice map onto the Summary sheet's column order."
  [{:keys [vendor-name invoice-number date due-date subtotal tax total]}]
  [(or vendor-name "") (or invoice-number "") (or date "") (or due-date "")
   (or subtotal 0.0) (or tax 0.0) (or total 0.0)])

(defn- line-item->row
  "Project a line-item map onto the per-invoice sheet's column order."
  [{:keys [description quantity unit-price amount]}]
  [(or description "") (or quantity 0) (or unit-price 0.0) (or amount 0.0)])

(defn- sheet-name-for
  "Generate a unique sheet name for an invoice. Excel limits sheet names to
   31 characters and disallows certain chars. Fall back to position-based if
   vendor-name is missing or duplicated."
  [{:keys [vendor-name invoice-number]} idx]
  (let [raw (cond
              (and vendor-name (seq vendor-name)) vendor-name
              (and invoice-number (seq invoice-number)) invoice-number
              :else (str "Invoice " (inc idx)))
        ;; Replace Excel-invalid chars and truncate to 31 chars
        cleaned (-> raw
                    (clojure.string/replace #"[\\/?*\[\]:]" "-")
                    (subs 0 (min 31 (count raw))))]
    cleaned))

(defn- build-summary-sheet-data
  "Return docjure sheet data [header-row & invoice-rows] for the Summary sheet."
  [invoices]
  (cons summary-columns
        (map invoice->summary-row invoices)))

(defn- build-invoice-sheet-data
  "Return docjure sheet data [header-row & line-item-rows] for one invoice."
  [invoice]
  (cons line-item-columns
        (map line-item->row (:line-items invoice))))

(defn build-invoice-workbook
  "Build an .xlsx workbook from a vector of invoice maps.

   :inputs map MUST contain:
     :invoices    — vector of invoice maps with keys :vendor-name, :invoice-number,
                    :date (ISO YYYY-MM-DD), :due-date, :subtotal, :tax, :total,
                    :line-items (vector of {:description :quantity :unit-price :amount})
     :output-path — absolute or relative file path where the .xlsx should be written.

   Workbook structure:
     - One \"Summary\" sheet with columns
       Vendor, Invoice #, Date, Due Date, Subtotal, Tax, Total
       (one row per invoice)
     - One sheet per invoice (sheet name derived from :vendor-name, truncated
       to 31 chars and stripped of Excel-invalid chars) with columns
       Description, Quantity, Unit Price, Amount
       (one row per line item)

   Returns {:workbook-path <absolute path string>}.

   The function is deterministic and idempotent — re-running with the same
   inputs produces a byte-equivalent file (modulo POI's own internal timestamps)."
  [{:keys [inputs]}]
  (let [invoices (or (:invoices inputs) [])
        output-path (:output-path inputs)
        _ (when-not output-path
            (throw (ex-info "build-invoice-workbook requires :output-path"
                            {:inputs (keys inputs)})))
        ;; Build the Summary sheet first
        wb (ss/create-workbook "Summary" (build-summary-sheet-data invoices))
        ;; Add one sheet per invoice
        used-names (atom #{"Summary"})]
    (doseq [[idx invoice] (map-indexed vector invoices)]
      (let [base-name (sheet-name-for invoice idx)
            ;; Disambiguate duplicates by appending a suffix
            unique-name (loop [n 1
                               candidate base-name]
                          (if (contains? @used-names candidate)
                            (recur (inc n)
                                   (let [suffix (str " (" n ")")
                                         max-len (- 31 (count suffix))]
                                     (str (subs base-name 0 (min max-len (count base-name)))
                                          suffix)))
                            candidate))]
        (swap! used-names conj unique-name)
        (let [sheet (.createSheet wb unique-name)
              rows (build-invoice-sheet-data invoice)]
          (ss/add-rows! sheet rows))))
    ;; Save
    (ss/save-workbook! output-path wb)
    {:workbook-path (.getAbsolutePath (io/file output-path))}))

;; =============================================================================
;; Available code nodes catalog (for :available-code-nodes plumbing)
;; =============================================================================

(def available-code-nodes
  "Catalog of pre-built `:code` fns in this brick, formatted for inclusion in
   the model's `:available-code-nodes` input. The model can reference these
   inside emit-tree! :code nodes via {:fn \"ns/sym\"}."
  (str
    "## predict-rlm-invoice-tools\n\n"
    "Pre-built deterministic Clojure code-node functions for the\n"
    "invoice_processing benchmark.\n\n"
    "### `ai.obney.orc.predict-rlm-invoice-tools.interface/build-invoice-workbook`\n\n"
    "Write a vector of Invoice maps to an .xlsx workbook on disk.\n\n"
    "**Reads:** an `:invoices` key bound to a vector of invoice maps with this shape:\n"
    "```clojure\n"
    "{:vendor-name     \"Acme Corp\"\n"
    " :invoice-number  \"ACME-2025-0042\"\n"
    " :date            \"2025-03-15\"        ;; ISO YYYY-MM-DD\n"
    " :due-date        \"2025-04-15\"\n"
    " :subtotal        1000.00\n"
    " :tax             100.00\n"
    " :total           1100.00\n"
    " :line-items      [{:description \"Widget A\"\n"
    "                    :quantity    10\n"
    "                    :unit-price  50.00\n"
    "                    :amount      500.00}\n"
    "                   ...]}\n"
    "```\n"
    "Plus an `:output-path` key bound to the absolute path string where the\n"
    "workbook should be written.\n\n"
    "**Writes:** `:workbook-path` — absolute path to the produced .xlsx.\n\n"
    "**Workbook structure:**\n"
    "  - One `Summary` sheet with columns Vendor, Invoice #, Date, Due Date,\n"
    "    Subtotal, Tax, Total (one row per invoice).\n"
    "  - One sheet per invoice (named after vendor) with columns\n"
    "    Description, Quantity, Unit Price, Amount (one row per line item).\n\n"
    "**Example usage in a tree:**\n"
    "```clojure\n"
    "[:code {:fn \"ai.obney.orc.predict-rlm-invoice-tools.interface/build-invoice-workbook\"\n"
    "        :reads [:invoices :output-path]\n"
    "        :writes [:workbook-path]}]\n"
    "```\n"))
