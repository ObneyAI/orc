(ns contract-comparison-validated
  "Task 04 — Contract Comparison with Adversarial Validation requirement.

   Same as contract-comparison plus a quality requirement: every claim must
   survive adversarial validation against source text. This task tests whether
   the model can translate a quality requirement into tree design choices
   (e.g. cross-reference data flow) without being shown how.

   Pattern: cross-document diff with quality requirement.

   Run from REPL:
     (require '[contract-comparison-validated :as t])
     (require '[runner])
     (runner/start!)
     (runner/run! t/task)
     (runner/stop!)
  ")

(def task
  {:slug "contract-comparison-validated"
   :name "Contract Comparison (with Adversarial Validation Requirement)"
   :pattern "Cross-document diff with quality requirement: every claim must be adversarially validated"
   :documents [:contract_v2 :contract_v3]
   :description "Compare two contract versions. Quality bar: every claim must survive adversarial validation against source text."
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

QUALITY REQUIREMENT — ADVERSARIAL VALIDATION:
Every claim in your final outputs must survive adversarial validation. An adversarial validator is an antagonistic reviewer whose job is to disprove each claim by examining the source text. A claim that cannot be defended with exact text from the source documents must NOT appear in your final outputs.

In other words: if an antagonistic reviewer cannot find textual evidence for a claim in :contract_v2 and :contract_v3, that claim is invalid and should be excluded. Your final outputs should be the SURVIVING claims after adversarial scrutiny.

Other quality requirements:
- Every claimed difference must cite a specific section number or clause text
- If a section is essentially identical between versions, say so explicitly rather than inventing differences
- Do not rely on general knowledge about microFIT contracts - only on what is actually present in the documents
- Prefer fewer verified claims over many unverified ones"
   :writes [:document-survey :section-diffs :major-changes :impact-analysis :executive-summary]
   :rlm {:auto-classify? true}
   :evaluation-criteria
   ["Tree designed by model includes some form of validation/verification stage (observe what shape it takes)"
    "Final claims include citations or text quotes from source"
    "No fabricated 'Deemed Single Property' addition"
    "No fabricated 'Anti-splitting Warranty' addition"
    "No fabricated 'Section 6.6 new in v3' claim"
    "Domestic Content removal correctly identified"
    "Section 6.5 emergency planning correctly identified as v3 addition"
    "Tree pattern is observably different from G08 baseline (does the model add structure for validation?)"]})
