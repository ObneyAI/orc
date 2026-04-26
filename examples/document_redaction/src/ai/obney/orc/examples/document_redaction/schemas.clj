(ns ai.obney.orc.examples.document-redaction.schemas
  "Malli — direct port of predict-rlm/examples/document_redaction/schema.py.")

(def redaction-target
  [:map
   [:page {:description "0-indexed page number"} :int]
   [:text {:description "Exact text to redact as it appears in the document"} :string]
   [:category {:description "Category, e.g. 'person_name', 'phone_number', 'address'"} :string]
   [:reason {:description "Why this text should be redacted"} :string]])

(def page-redaction-summary
  [:map
   [:page {:description "0-indexed page number"} :int]
   [:redaction-count {:description "Number of redactions applied on this page"} :int]
   [:categories {:description "Distinct categories redacted on this page"}
    [:vector :string]]])

(def redaction-result
  [:map
   [:total-redactions {:description "Total number of redactions applied"} :int]
   [:page-summaries {:description "Per-page summary"} [:vector page-redaction-summary]]
   [:targets {:description "All redaction targets identified"} [:vector redaction-target]]])

(def blackboard
  {:documents          [:vector {:field-type :file} :string]
   :criteria           :string
   :doc-survey         [:vector :map]            ;; {:path :pages [{:n :text}]}
   :doc                :map                      ;; map-each item
   :doc-targets        [:vector redaction-target]
   :all-targets        [:vector redaction-target]
   :doc-redactions     [:vector :map]            ;; {:path :out-path :targets :result}
   :redacted-documents [:vector {:field-type :file} :string]
   :result             redaction-result})
