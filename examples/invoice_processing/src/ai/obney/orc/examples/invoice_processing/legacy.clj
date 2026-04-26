(ns ai.obney.orc.examples.invoice-processing.legacy
  "Legacy mode — repl-researcher WITHOUT :rlm. The pre-RLM iterative
   coding agent: blackboard values are template-substituted into the
   instruction (no metadata-only previews), the SCI sandbox gets
   :mcp-tools but NO predict / predict-all / final! host functions,
   and the model commits its result by emitting `FINAL_ANSWER: …`
   in stdout.

   Architectural baseline against which Style A, Style B (RLM), and
   Hybrid (Style A + RLM fallback) are measured. Pre-RLM-paper shape:
   one model, one context, tools-as-functions, text-pattern submit."
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.doc-skills.interface :as ds]
            [ai.obney.orc.examples.invoice-processing.schemas :as schemas]))

(def signature-strategy
  (str
"Extract structured invoice data from PDFs and write an Excel workbook.

INPUTS (substituted at runtime):
  Invoice PDF paths: {invoices}

YOUR JOB (multi-iteration plan)
================================
ITERATION 1: read both docs.
   (def acme-text (pdf/document-text {:path \"/path/to/acme.pdf\"}))
   (def gt-text   (pdf/document-text {:path \"/path/to/globaltech.pdf\"}))
   (println acme-text)
   (println gt-text)

ITERATION 2+: HAND-EXTRACT the values from the text you printed
in iteration 1. DO NOT write a generic parser function — for 2
invoices that's overkill and a known failure mode. Just copy the
values literally into a Clojure data structure:

   (def invoices
     [{:vendor-name    \"Acme Corporation\"          ;; ← copy from printed text
       :invoice-number \"INV-2025-0042\"             ;; ← copy from printed text
       :date           \"2025-04-01\"                ;; ← copy from printed text
       …
       :line-items     [{:description \"…\" :quantity 1
                         :unit-price 100.0 :amount 100.0}
                        ;; ← one entry per line-item row in the text
                        ]}
      {…second invoice…}])

   ;; then write the workbook and emit FINAL_ANSWER

The point is: you SEE the text in your prior iteration's stdout —
just look at it and transcribe. No regex, no Double/parseDouble,
no parser functions. Strings of text in the document → string and
number literals in your code.

TOOLS (single-map arg, all in scope as namespaced functions)
============================================================
  (pdf/page-count       {:path \"…\"})       -> int
  (pdf/page-text        {:path \"…\" :n 0})  -> string
  (pdf/document-text    {:path \"…\"})       -> full string
  (xlsx/write-workbook  {:out-path \"…\" :sheets-spec [...]}) -> path
                              ⚠ keys MUST be :out-path and :sheets-spec
                              (NOT :path, NOT :sheets — those throw)

FORBIDDEN (sandbox rejects these BEFORE eval)
=============================================
  ❌ Java interop:  System/currentTimeMillis, Math/abs, Class., .method
  ❌ Unsafe fns:    slurp, spit, eval, require, load, sh, exec
  ❌ predict / predict-all / final!  (RLM-mode only — not available here)
  ❌ (ns …) and (:require …)  — DO NOT declare a namespace or require
                                anything. Write top-level forms directly.
                                clojure.string is already in scope; call
                                its functions with the full namespace
                                (clojure.string/split, etc.).

SUBMIT FORMAT
=============
When done, your LAST println MUST be exactly this shape:

    (println (str \"FINAL_ANSWER: \" (pr-str <result-map>)))

where <result-map> is:

    {:result   {:invoices [<invoice> <invoice> ...]
                :total-amount <number>
                :summary       \"<short string>\"}
     :workbook \"/tmp/invoice-extraction.xlsx\"}

and each <invoice> is a map with the real extracted values:

    {:vendor-name    \"Acme Corporation\"
     :invoice-number \"INV-2025-0042\"
     :date           \"2025-04-01\"
     :due-date       \"2025-05-01\"
     :subtotal       3700.00
     :tax            386.40
     :total          4086.40
     :line-items     [{:description \"…\" :quantity 1
                       :unit-price 100.00 :amount 100.00}
                      ...]}

CRITICAL — DO NOT SUBMIT PLACEHOLDERS
=====================================
The example above shows the SHAPE; you must populate it with real
values pulled from pdf/document-text output. Submitting placeholder
strings (\"…\", \"<extract>\", or schema fields with empty values)
is a hard failure — the workflow is judged on whether the values
match what's actually in the PDFs.

If the document text spans multiple iterations of reasoning, that's
fine — read it, extract on the next iteration, write the workbook
on the iteration after that. Use println to log intermediate state
(but the FINAL_ANSWER println must be the last thing you do).
"))

(def workflow
  (dsl/workflow "invoice-processing-legacy"
    (dsl/blackboard schemas/blackboard)
    (dsl/repl-researcher "orchestrate"
      :model "openai/gpt-5"
      :instruction
      (str (ds/compose-instructions :pdf :xlsx) "\n\n" signature-strategy)
      :reads [:invoices]
      :writes [:result :workbook]
      :mcp-tools (vec ds/all-tool-names)
      :max-iterations 15)))
