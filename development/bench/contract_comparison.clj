(ns contract-comparison
  "Task 03 — Contract Comparison (Cross-document diff).

   Compare two contract versions and identify additions, removals, modifications
   with classification (MAJOR/MINOR/COSMETIC), impact analysis (Supplier vs OPA),
   and an executive summary.

   Pattern: cross-document diff (two docs -> delta report).

   Run from REPL:
     (require '[contract-comparison :as t])
     (require '[runner])
     (runner/start!)
     (runner/run! t/task)
     (runner/stop!)
  ")

(def task
  {:slug "contract-comparison"
   :name "Contract Comparison"
   :pattern "Cross-document diff (two docs -> delta report)"
   :documents [:contract_v2 :contract_v3]
   :description "Compare two versions of a legal contract and identify all differences with impact analysis."
   :instruction "Documents available:
- :contract_v2 - Ontario microFIT Contract Version 2.0 (56K chars)
- :contract_v3 - Ontario microFIT Contract Version 3.1.1 (49K chars)

Task: Compare these two contract versions and identify what changed between them.

Produce five outputs:
- :document-survey - Structure overview of both documents (sections, appendices)
- :section-diffs - Specific additions, removals, and modifications with exact section/clause references
- :major-changes - Significant changes classified as MAJOR (legal/financial impact) vs MINOR (clarification) vs COSMETIC (formatting)
- :impact-analysis - Who is affected by each change (Supplier vs OPA) and how risk has shifted
- :executive-summary - High-level overview of how the contract evolved between versions

Quality requirements:
- Every claimed difference must cite a specific section number or clause text
- If a section is essentially identical between versions, say so explicitly rather than inventing differences
- Do not assume changes based on general knowledge about microFIT contracts - rely only on what is actually present in the documents"
   :writes [:document-survey :section-diffs :major-changes :impact-analysis :executive-summary]
   :rlm {:auto-classify? true}
   :evaluation-criteria
   ["Correctly identifies both documents as microFIT contracts"
    "Notes version numbers (2.0 vs 3.1.1)"
    "Identifies page count difference (23 vs 22 pages)"
    "Finds Ministerial Direction reference addition in v3.1.1"
    "Finds anti-splitting provisions added in v3.1.1"
    "Finds domestic content changes between versions"
    "Finds battery backup restriction changes"
    "Classifies changes as MAJOR/MINOR appropriately"
    "Identifies which party benefits from changes"
    "Does NOT hallucinate differences that don't exist"]})
