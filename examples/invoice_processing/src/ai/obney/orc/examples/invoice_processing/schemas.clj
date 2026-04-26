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

(def blackboard
  {:invoices       [:vector {:field-type :file} :string]   ;; input paths
   :pages          [:vector :map]                          ;; {:path :n :text}
   :page           :map                                    ;; map-each item
   :page-extract   :map                                    ;; per-page extraction
   :page-extracts  [:vector :map]
   :result         invoice-extraction-result
   :workbook       [:string {:field-type :file}]})
