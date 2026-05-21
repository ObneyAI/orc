(ns predict-rlm-comparison.tasks.contract-comparison
  "Contract comparison benchmark — predict-rlm port for apples-to-apples.
   The model compares two versions of a contract (microFIT v2.0 vs v3.1.1)
   and produces a structured ComparisonResult with a markdown report,
   per-section diffs, key high-level differences, and an executive
   summary.

   Source task: predict-rlm/examples/contract_comparison/signature.py
   Reference data: development/bench/predict_rlm_comparison/references/
                    predict-rlm/contract_comparison/sample/

   Models: gpt-5.4 main + gpt-5.1-chat sub for apples-to-apples.

   This file is also a complete worked example of how to compose an ORC
   RLM benchmark — see the :task map at the bottom for the full shape
   (name, slug, model + sub-model, instruction, input/output schemas,
   input-loader, writes, evaluation-criteria, predict-rlm-reported
   metadata).

   Run from REPL:
     (require '[predict-rlm-comparison.tasks.contract-comparison :as t])
     (require '[predict-rlm-comparison.runner :as r])
     (r/start!)
     (r/run! t/task)
     (r/stop!)

   Or via the standalone Clojure runner:
     clj -M:dev:test -m predict-rlm-comparison.run.contract-comparison"
  (:require [ai.obney.orc.predict-rlm-pdf.interface :as pdf]))

(def ^:private references-dir
  "development/bench/predict_rlm_comparison/references/predict-rlm/contract_comparison/sample/input")

(def ^:private contract-a-path
  (str references-dir "/microFIT-Contract-Version-2-0.pdf"))

(def ^:private contract-b-path
  (str references-dir "/microFIT-Contract-Version-3-1-1.pdf"))

(def instruction
  "Port-cleaned from predict-rlm's signature.py docstring."
  "Compare PDF contracts and produce a structured report of differences.

   Goal:
     Read two versions of the same contract (provided as pre-rendered
     page images on the blackboard, keyed :contract-a-pages and
     :contract-b-pages), identify corresponding sections, compare them
     systematically, and produce a structured ComparisonResult.

   Output schema:
     :report — markdown string, the full comparison report.
     :section-diffs — vector of maps with keys :section-name (string),
       :document-a-text (string, key text or summary), :document-b-text
       (string), :difference-summary (string), :significance (one of
       \"major\", \"minor\", \"identical\").
     :key-differences — vector of maps with keys :area (e.g. \"pricing\",
       \"liability\"), :description (string), :impact (string).
     :summary — executive summary of the most important differences
       (string, used by the runner as a quick-glance result).

   QUALITY REQUIREMENTS:
     - All differences must be traceable to actual contract content; do
       not invent sections or clauses.
     - For each :section-diff, classify :significance accurately —
       \"identical\" should mean the sections are functionally equivalent
       even if wording varies, \"major\" should be reserved for changes
       to terms, dates, obligations, pricing, or liability.
     - The markdown :report should cover Document Survey, Section
       Comparison, Key Differences, and Executive Summary.

   STRUCTURAL VERIFICATION REQUIREMENT: After producing an initial
   comparison, adversarially re-examine:
     - Did you survey BOTH documents' full structure (not just the first
       few sections)?
     - Did you identify sections that exist in one document but not the
       other (additions/removals between versions)?
     - Are significance classifications accurate, or did you over-call
       \"major\" / under-call \"minor\"?

   COMPOSITION CONSIDERATIONS — read carefully:

   - The blackboard provides two text vectors: :contract-a-pages and
     :contract-b-pages, each a vector of plain text strings (one per
     PDF page). Process them such that you can correlate sections
     across documents.

   - If your design iterates pages within a document, each per-iteration
     sub-call sees one page in isolation. The sub-call cannot infer its
     position in the source collection from content alone. If you need
     positional context, thread it explicitly.

   - Cross-document comparison ultimately requires aligning content
     from BOTH documents. This usually means (a) extracting structured
     section data per document, (b) aligning by section name or topic,
     (c) computing per-section diffs.

   - Behavior-tree primitives (sequence, parallel, map-each, llm, code,
     final) are the durable observable record. Prefer emitting a tree
     over coordinating multiple sub-calls inline as imperative Phase-1
     Clojure code.")

(defn- load-inputs []
  ;; Text-mode extraction (PDFBox) — same approach as document_analysis.
  ;; predict-rlm's run uses vision (45 pages, ~5.5min, 173K tokens).
  ;; Our path: extract text per page so the model can :map-each / read
  ;; pages without triggering vision routing limitations.
  {:contract-a-pages (vec (pdf/extract-pages-as-text contract-a-path))
   :contract-b-pages (vec (pdf/extract-pages-as-text contract-b-path))})

(def task
  {:name "Contract Comparison (predict-rlm port)"
   :slug "contract-comparison-predict-rlm"
   :pattern "Two-document cross-version comparison (vision LLM per page) → structured diff"
   :model "openai/gpt-5.4"
   :sub-model "openai/gpt-5.1-chat"
   :instruction instruction
   :input-schemas {:contract-a-pages [:vector :string]
                   :contract-b-pages [:vector :string]}
   :output-schemas {:report :string
                    :section-diffs [:vector [:map-of :any :any]]
                    :key-differences [:vector [:map-of :any :any]]
                    :summary :string}
   :input-loader load-inputs
   :writes [:report :section-diffs :key-differences :summary]
   :evaluation-criteria
   ["Both contract versions surveyed (page counts, structure)"
    "Per-section diffs cover Term, Pricing, Environmental Attributes, etc."
    "Key differences identified for pricing, term length, dispute resolution if changed"
    "Sections only in v3.1.1 (additions) flagged"
    "Sections only in v2.0 (removals) flagged if any"
    ":significance classifications reasonable"]
   ;; predict-rlm publishes Run Stats in their comparison-report.md
   :predict-rlm-reported {:main-lm-calls 4 :sub-lm-calls 80
                          :input-tokens 120927 :output-tokens 52130
                          :total-tokens 173057
                          :cost-usd 0.71
                          :duration-approx "~5.5 minutes"
                          :pages 45
                          :cost-per-page 0.016
                          :note "From their sample/output/comparison-report.md Run Stats table"}})
