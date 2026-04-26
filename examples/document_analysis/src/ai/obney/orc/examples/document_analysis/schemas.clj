(ns ai.obney.orc.examples.document-analysis.schemas
  "Malli schemas — direct port of predict-rlm/examples/document_analysis/schema.py.")

(def key-date
  "A key date extracted from a document."
  [:map
   [:name {:description "e.g. 'Submission Deadline', 'Effective Date'"} :string]
   [:date {:description "ISO format date (YYYY-MM-DD)"} :string]
   [:time {:optional true :description "24-hour HH:MM, e.g. 14:00"} [:maybe :string]]
   [:timezone {:optional true :description "e.g. EST, PST, UTC"} [:maybe :string]]])

(def key-entity
  "A key entity (person, organization, or role) extracted from a document."
  [:map
   [:name {:description "Name of the person, organization, or role"} :string]
   [:role {:optional true :description "Role or relationship to the document"} [:maybe :string]]
   [:contact {:optional true :description "Contact info if available"} [:maybe :string]]])

(def document-analysis
  "Structured analysis of a document set."
  [:map
   [:report {:description "Full analysis as a well-formatted markdown report"} :string]
   [:key-dates {:description "Important dates found in the documents"}
    [:vector key-date]]
   [:key-entities {:description "Key people, organizations, or roles mentioned"}
    [:vector key-entity]]])

(def blackboard
  "Full blackboard schema for the document_analysis workflow."
  {:documents     [:vector {:field-type :file} :string]
   :criteria      :string
   :document-meta [:vector :map]                ;; per-doc {:path :pages}
   :document      :map                          ;; map-each iteration item
   :doc-summary   :string                       ;; map-each output
   :doc-summaries [:vector :string]
   :analysis      document-analysis
   :docx-report   [:string {:field-type :file}]})
