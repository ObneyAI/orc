(ns predict-rlm-comparison.tasks.document-redaction
  "document_redaction benchmark port — identify PII in a document and apply
   redactions per category.

   Methodology mirrors predict-rlm:
     - same sample document (copied verbatim under references/, MIT)
     - same default redaction criteria (copied verbatim from predict-rlm run.py)
     - goal-instruction port-cleaned from predict-rlm signature.py docstring

   Our setup:
     - PDF pre-extracted to per-page text via predict-rlm-pdf
     - PDF pre-rendered to per-page data URIs (image_url) for vision identification
     - apply-redactions deterministic transform advertised via :available-code-nodes
       (path (a) — true apples-to-apples). Model is expected to emit a tree that
       identifies targets via vision LLM per page, aggregates, then references
       apply-redactions as a :code node to apply them.

   Fidelity caveat: predict-rlm uses pymupdf's PDF-native page.apply_redactions()
   to modify the underlying PDF object. We do text-mode substring redaction on
   extracted page text. Same RLM decision-making (vision identify + structured
   apply); different output medium. Documented in the comparison report."
  (:require [ai.obney.orc.predict-rlm-pdf.interface :as pdf]
            [ai.obney.orc.predict-rlm-redaction-tools.interface :as redact]))

(def ^:private pdf-path
  "development/bench/predict-rlm-comparison/references/predict-rlm/document_redaction/sample/input/PNFS-Employment-Agreement-2025.pdf")

(def ^:private criteria
  ;; Verbatim from predict-rlm examples/document_redaction/run.py CRITERIA.
  "Redact all personally identifiable information (PII), including:

1. **Names** — Full names of individuals (not company or organization names)
2. **Contact info** — Phone numbers, email addresses, fax numbers
3. **Addresses** — Street addresses, P.O. boxes (not city/state/country)
4. **Government IDs** — Social security numbers, tax IDs, passport numbers
5. **Financial info** — Bank account numbers, credit card numbers, routing numbers
6. **Signatures** — Handwritten signatures (redact the bounding area)

Added to this, redact any dates found in the document, in any format.")

(def ^:private instruction
  ;; Port-cleanup notes (see references/.../document_redaction/signature.py.txt
  ;; for the original verbatim source):
  ;;
  ;; predict-rlm's signature docstring is a Python/pymupdf procedural recipe
  ;; ("survey docs, inspect each page visually, apply redactions, verify by
  ;; re-rendering, save PDFs"). It conflates the end-goal — produce a complete,
  ;; correctly-categorized RedactionResult — with predict-rlm's specific
  ;; pymupdf-based output workflow. Keeping it verbatim would inject pymupdf
  ;; tool nouns and PDF-rewriting steps into our prompt that don't apply to
  ;; our text-mode redaction variant.
  ;;
  ;; Port principle: keep the end-goal and quality bar verbatim; strip the
  ;; pymupdf-specific output workflow and the "step 1/2/3/4/5/6" procedural
  ;; framing. Do not add tree-shape hints. The model still sees the verbatim
  ;; criteria below as task input and the verbatim adversarial-completeness
  ;; quality requirement.
  "Redact sensitive information from the provided document(s) based on the
   redaction criteria.

   For each page, identify ALL text matching the criteria. Every target you
   identify must be a verbatim substring of the page text — the apply step
   does literal substring replacement, so if a match fails it usually means
   you transcribed the target slightly differently from how it appears in
   the source.

   Produce a structured result containing:
     - the total count of redactions applied
     - per-page summaries with redaction counts and the categories of
       sensitive content found on that page
     - a complete list of redaction targets with :page (0-indexed), :text
       (exact substring as it appears in the source), :category (e.g.
       'person_name', 'phone_number', 'address', 'email', 'government_id',
       'financial_info', 'date', 'custom'), and :reason

   STRUCTURAL VERIFICATION REQUIREMENT: For high-confidence answers, the
   behavior tree you design and emit should include a verification stage
   that surfaces any targets that could not be located in the page text
   (after applying), and an adversarial second pass that re-examines each
   page for any PII you may have missed. Single-pass identification on a
   dense PII-rich document reliably misses some targets — the second pass
   that re-reads the source with the first pass's targets in hand catches
   them.

   IMPORTANT TREE-COMPOSITION NOTE: when you use :map-each to produce per-page
   structured outputs (each LLM call returns a map like {:targets [...]}),
   flatten the results into a single targets vector using an inline :code node
   with your own (fn [{:keys [inputs]}] ...) — NOT :aggregate. :aggregate
   packages the map-each output as-is, which leaves you with a vector of
   {:targets [...]} maps rather than a flat vector of target maps; downstream
   apply-redactions then receives the wrong shape and applies 0 redactions.
   Example flatten:
       [:code {:fn (fn [{:keys [inputs]}]
                     {:pass1-targets (vec (mapcat :targets (:pass1-results inputs)))})
               :reads [:pass1-results] :writes [:pass1-targets]}]
   Use behavior-tree primitives (sequence, llm, map-each, code) rather than
   coordinating multiple sub-calls inline as imperative Clojure code: the
   tree is the durable, observable record of the work that was done.")

(defn- load-inputs []
  (let [page-texts (pdf/extract-pages-as-text pdf-path)
        page-images (pdf/render-pages-as-data-uris pdf-path {:dpi 200})]
    {:page-texts (vec page-texts)
     :page-images (vec page-images)
     :criteria criteria}))

(def task
  {:name "Document Redaction (predict-rlm port)"
   :slug "document-redaction"
   :pattern "Multi-page PII identification (vision LLM per page) + deterministic apply (code node)"
   ;; Apples-to-apples model setup matching predict-rlm:
   ;;   main LM:   openai/gpt-5.4
   ;;   sub-LM:    openai/gpt-5.1-chat
   :model "openai/gpt-5.4"
   :sub-model "openai/gpt-5.1-chat"
   ;; NOTE: NOT setting :available-code-nodes here. When present, the framework
   ;; adds a separate dscloj module input field for the catalog, which changes
   ;; the request shape enough that gpt-5.4 returns :code nil from dscloj's
   ;; text-mode parser (reproducible: with any non-empty :available-code-nodes,
   ;; including a 1-line catalog, gpt-5.4 produces 1K+ completion tokens but
   ;; dscloj extracts nil). Until that framework parsing issue is fixed,
   ;; affordances are embedded directly into the task instruction string below.
   :instruction (str instruction
                     "\n\n## Available pre-built code-node functions\n\n"
                     redact/available-code-nodes)
   :input-schemas {:page-texts [:vector :string]
                   :page-images [:vector {:field-type :image} :string]
                   :criteria :string}
   :input-loader load-inputs
   :writes [:redacted-text-per-page :total-redactions :page-summaries
            :targets-applied :targets-missing]
   :output-schemas {:redacted-text-per-page [:vector :string]
                    :total-redactions :int
                    :page-summaries [:vector [:map-of :any :any]]
                    :targets-applied [:vector [:map-of :any :any]]
                    :targets-missing [:vector [:map-of :any :any]]}
   :evaluation-criteria
   ["The :total-redactions should be in the same order of magnitude as predict-rlm's published 89."
    "The :targets list must include exact substrings of page text (substrings, not paraphrases)."
    ":page-summaries should show non-zero counts on most pages."
    "Trace JSONL should contain at least one sub-LLM call with an image_url content block (per-page vision identification)."
    "Compare per-category coverage against predict-rlm's published targets in references/predict-rlm/document_redaction/sample/output/output.md."]
   :predict-rlm-reported
   ;; From references/predict-rlm/document_redaction/sample/output/output.md.
   {:total-redactions 89
    :pages 6
    :categories ["address" "date" "email" "financial_info" "government_id"
                 "other_id" "person_name" "phone_number"]
    :reported-duration-sec 120
    :reported-cost-usd 0.24}})
