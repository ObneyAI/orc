(ns document-analysis
  "Task 01 — Document Analysis (Extraction on a large RFP).

   Extract a structured representation of a large document: an executive
   summary, all key dates with context, and important entities.

   Pattern: extraction (single doc -> summary/dates/entities).

   Run from REPL:
     (require '[document-analysis :as t])
     (require '[runner])
     (runner/start!)
     (runner/run! t/task)
     (runner/stop!)
  ")

(def task
  {:slug "document-analysis"
   :name "Document Analysis (Extraction)"
   :pattern "Extraction (single doc -> summary/dates/entities)"
   :documents [:yyj_rfp]
   :description "Extract summary, key dates, and entities from a large RFP document."
   :instruction "Document available: :yyj_rfp (large RFP, ~280K chars)

Task: Produce a structured extraction of this RFP with three outputs:
- :summary - Executive summary of the RFP
- :key-dates - All important dates with their context (issue date, deadlines, milestones)
- :entities - Important entities mentioned (people with their roles, organizations, contact info)

Quality requirements: Information must be accurate and traceable to the source. Do not invent dates, people, or organizations not present in the document."
   :writes [:summary :key-dates :entities]
   :rlm {:auto-classify? true}
   :evaluation-criteria
   ["Identifies RFP issue/response dates"
    "Extracts contact information (Procurement Manager, CFO, etc.)"
    "Identifies key organizations (VAA, LDC, etc.)"
    "Captures monetary thresholds (revenue figures, MAG, etc.)"
    "Identifies key facility specifications (parking spaces, occupancy)"
    "Does NOT hallucinate dates or entities not in the document"]})
