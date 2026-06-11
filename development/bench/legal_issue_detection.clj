(ns legal-issue-detection
  "Task 05 — Legal Issue Detection on a SMALL document.

   Analyze a small employment agreement (~7K chars) from the employee's
   perspective. Tests whether the model intelligently chooses NOT to use
   emit-tree! when the document is small enough to handle directly.

   Pattern: analytical reasoning on a small document.

   Run from REPL:
     (require '[legal-issue-detection :as t])
     (require '[runner])
     (runner/start!)
     (runner/run! t/task)
     (runner/stop!)
  ")

(def task
  {:slug "legal-issue-detection"
   :name "Legal Issue Detection"
   :pattern "Analytical reasoning on SMALL document (7K chars)"
   :documents [:employment_agreement]
   :description "Analyze a small employment agreement for potential issues, ambiguities, and missing protections."
   :instruction "Document available: :employment_agreement (small employment contract, ~7K chars)

Task: Review this employment agreement from the EMPLOYEE'S perspective.

Produce four outputs:
- :issues - Potential problems or concerns for the employee (broad scope clauses, unfavorable terms, etc.)
- :ambiguities - Unclear or ambiguous language that could be interpreted unfavorably
- :missing - Standard protections or clauses that are NOT included in this agreement
- :recommendations - Suggested negotiation points before signing

Quality requirements: All issues must be traceable to specific clause text. Do not invent problems not in the document."
   :writes [:issues :ambiguities :missing :recommendations]
   :rlm {:auto-classify? true}
   :evaluation-criteria
   ["Identifies non-compete clause scope concerns"
    "Notes confidentiality obligations extent"
    "Identifies termination notice period adequacy"
    "Notes bonus/incentive discretionary language"
    "Identifies intellectual property assignment scope"
    "Notes any missing standard protections"
    "Provides actionable negotiation recommendations"
    "Does NOT hallucinate issues not in the document"]})
