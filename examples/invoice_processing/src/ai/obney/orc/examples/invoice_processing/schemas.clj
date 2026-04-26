(ns ai.obney.orc.examples.invoice-processing.schemas
  "Malli schemas — direct port of predict-rlm/examples/invoice_processing/schema.py.")

(def line-item
  [:map
   [:description {:description "Description of the item or service"} :string]
   [:quantity {:description "Quantity of items"} :double]
   [:unit-price {:description "Price per unit in dollars"} :double]
   [:amount {:description "Total amount for this line item in dollars"} :double]])

(def invoice
  [:map
   [:vendor-name {:description "Name of the vendor/supplier"} :string]
   [:invoice-number {:description "Invoice number or reference"} :string]
   [:date {:description "Invoice date in ISO format (YYYY-MM-DD)"} :string]
   [:due-date {:description "Payment due date in ISO format (YYYY-MM-DD)"} :string]
   [:subtotal {:description "Subtotal before tax and discounts"} :double]
   [:tax {:description "Tax amount in dollars"} :double]
   [:total {:description "Total amount due in dollars"} :double]
   [:line-items {:description "Individual line items from the invoice"}
    [:vector line-item]]])

(def invoice-extraction-result
  [:map
   [:invoices {:description "Extracted data from each invoice"}
    [:vector invoice]]
   [:total-amount {:description "Combined total across all invoices in dollars"} :double]
   [:summary {:description "Brief summary of the invoices processed"} :string]])

(def page-extract
  "Per-page extraction. Continuation pages should leave header fields as
   empty strings / 0.0 and only populate :line-items. We use plain types
   (not :maybe) because DSCloj's flattening doesn't reliably surface the
   actual value through the [:maybe …] wrapper."
  [:map
   [:vendor-name {:description "Vendor/supplier on THIS page (\"\" if continuation page)"} :string]
   [:invoice-number {:description "Invoice number on THIS page (\"\" if continuation)"} :string]
   [:date {:description "ISO YYYY-MM-DD on THIS page (\"\" if continuation)"} :string]
   [:due-date {:description "ISO YYYY-MM-DD on THIS page (\"\" if continuation)"} :string]
   [:subtotal {:description "Subtotal on THIS page (0.0 if continuation)"} :double]
   [:tax {:description "Tax on THIS page (0.0 if continuation)"} :double]
   [:total {:description "Total on THIS page (0.0 if continuation)"} :double]
   [:line-items {:description "Line items visible on THIS page"} [:vector line-item]]])

(def blackboard
  {:invoices       [:vector {:field-type :file} :string]   ;; input paths
   :pages          [:vector :map]                          ;; {:path :n :text}
   :page           :map                                    ;; map-each item
   :page-extract   page-extract                            ;; structured per-page extraction
   :page-extracts  [:vector page-extract]
   :result         invoice-extraction-result
   :workbook       [:string {:field-type :file}]})
