(ns risk-analysis
  "Task 02 — Risk & Obligation Analysis (Analytical reasoning on a large RFP).

   Analytical task, not extraction: classify obligations by type, identify
   penalties with clause references, produce a HIGH/MEDIUM/LOW risk matrix
   with reasoning, and a strategic executive summary.

   Pattern: analytical reasoning (single doc -> classified output).

   Run from REPL:
     (require '[risk-analysis :as t])
     (require '[runner])
     (runner/start!)
     (runner/run! t/task)
     (runner/stop!)
  ")

(def task
  {:slug "risk-analysis"
   :name "Risk & Obligation Analysis"
   :pattern "Analytical reasoning (single doc -> classified output)"
   :documents [:yyj_rfp]
   :description "Analyze a large RFP document to identify and classify all contractual obligations, penalties, and risks."
   :instruction "Document available: :yyj_rfp (large RFP, ~280K chars)

Task: Perform a comprehensive RISK AND OBLIGATION ANALYSIS from the bidder's perspective.

This is analytical work, not extraction. Reason about risks and consequences, do not just list clauses.

Produce four outputs:
- :obligations - What the bidder must do, classified by type (Financial / Operational / Compliance / Reporting / Timeline)
- :penalties - Consequences for non-compliance (termination triggers, financial penalties, disqualification criteria) with specific clause references
- :risk-matrix - Each major obligation mapped to a risk level (HIGH/MEDIUM/LOW) with reasoning about difficulty and consequence severity
- :executive-summary - Top strategic concerns and recommendations for the bidder

Quality requirements: Risk classifications must be justified by document content. Do not invent obligations or penalties not in the document."
   :writes [:obligations :penalties :risk-matrix :executive-summary]
   :rlm {:auto-classify? true}
   :evaluation-criteria
   ["Identifies mandatory pre-bid meeting requirement (disqualification if missed)"
    "Identifies Letter of Credit requirement (5% of management fee)"
    "Identifies insurance requirements (specific coverage amounts)"
    "Identifies WorkSafeBC registration requirement"
    "Identifies proposal validity period (60 days)"
    "Classifies financial vs operational vs compliance obligations"
    "Identifies contract termination triggers"
    "Provides risk assessment with HIGH/MEDIUM/LOW ratings"
    "Does NOT hallucinate obligations not in the document"]})
