(ns ai.obney.orc.examples.contract-comparison.schemas
  "Malli — direct port of predict-rlm/examples/contract_comparison/schema.py.")

(def section-diff
  [:map
   [:section-name {:description "Name or title of the section being compared"} :string]
   [:document-a-text {:description "Key text or summary from Document A"} :string]
   [:document-b-text {:description "Key text or summary from Document B"} :string]
   [:difference-summary {:description "Summary of what changed"} :string]
   [:significance {:description "How significant the difference is"}
    [:enum "major" "minor" "identical"]]])

(def key-difference
  [:map
   [:area {:description "Area of the contract affected (e.g. pricing, liability)"} :string]
   [:description {:description "Description of the difference"} :string]
   [:impact {:description "Potential impact or implication"} :string]])

(def comparison-result
  [:map
   [:report {:description "Full comparison report in markdown format"} :string]
   [:section-diffs {:description "Per-section comparison"} [:vector section-diff]]
   [:key-differences {:description "High-level key differences"} [:vector key-difference]]
   [:summary {:description "Executive summary"} :string]])

(def blackboard
  {:contracts       [:vector {:field-type :file} :string]
   :doc-summaries   [:vector :map]                ;; per-doc {:path :sections}
   :doc             :map                          ;; map-each item
   :doc-summary     :map                          ;; map-each output
   :result          comparison-result})
