(ns ai.obney.orc.examples.invoice-processing.legacy
  "Legacy mode — repl-researcher WITHOUT :rlm. Pre-RLM iterative coding
   agent: blackboard values are template-substituted into the instruction
   (no metadata-only previews), :mcp-tools available in SCI sandbox, NO
   predict / predict-all / final! host functions, submission via
   FINAL_ANSWER text marker.

   Architectural baseline against Style A, Style B (RLM), and Hybrid.
   Pre-RLM-paper shape: one model, one context, tools-as-functions,
   text-pattern submit."
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.doc-skills.interface :as ds]
            [ai.obney.orc.examples.invoice-processing.schemas :as schemas]))

(def signature-strategy
  (str
"Extract structured invoice data from PDF files and write an Excel workbook.

INPUTS (substituted at runtime — these become Clojure literals):
  Invoice paths: {invoices}

HOW THIS RUNTIME WORKS — read carefully
========================================
You're in a SCI sandbox. You'll get up to 15 iterations. Each iteration:
  - You write Clojure code.
  - It executes. Stdout + result + error get fed back to you.
  - DEFS PERSIST ACROSS ITERATIONS — `(def foo …)` in iter 1 is
    available in iter 2. Lean on this. Don't redefine functions or
    re-read the same file twice.
  - `clojure.string/*` is in scope — call as `(clojure.string/split …)`.
  - The substituted vector ABOVE — copy its literal value into your
    code. It's the ONLY way to get the paths.

WORKING TOOLS (single-map arg, namespaced functions)
=====================================================
  (pdf/page-count       {:path \"…\"})       -> int
  (pdf/page-text        {:path \"…\" :n 0})  -> string
  (pdf/document-text    {:path \"…\"})       -> full doc string
  (xlsx/write-workbook  {:out-path \"…\" :sheets-spec [...]}) -> path

FORBIDDEN — sandbox rejects PRE-EVAL
=====================================
  ❌ Java interop: System/*, .method, Class., Double/parseDouble, etc.
  ❌ Unsafe fns:   slurp, spit, eval, require, load, sh, exec
  ❌ predict / predict-all / final!  (RLM-only, not in legacy)
  ❌ (ns …) / (:require …)  — DON'T declare a namespace; just write
                              top-level forms. clojure.string is in scope.
  ❌ Defining a generic parser function with placeholder regex like
     `#\"<vendor pattern>\"` — that's how this benchmark fails. You
     have a tiny known invoice set; HAND-EXTRACT real values from
     the document text, don't try to write a universal parser.
  ❌ Nested `#(…)` reader literals — Clojure forbids `#( … #( …) …)`
     because the inner `%` is ambiguous. ALWAYS use `(fn [x] …)` for
     anonymous functions when one fn is nested inside another.

RECOMMENDED ITERATION PLAN
==========================
ITER 1: Read both PDFs, print their full text.
        (def acme-text (pdf/document-text {:path \"…/acme-…pdf\"}))
        (def gt-text   (pdf/document-text {:path \"…/globaltech-…pdf\"}))
        (println \"=== ACME ===\") (println acme-text)
        (println \"=== GT ===\")   (println gt-text)

ITER 2: NOW that the texts are visible in your prior-iteration stdout,
        TRANSCRIBE the values directly into a vector. Look at the text
        you printed — vendor name appears in the header, dollar amounts
        are next to labels like \"Subtotal\", \"Tax\", \"Total\", and
        line items are in a table. Just type them in:

        (def invoices
          [{:vendor-name    \"…actual name from text…\"
            :invoice-number \"…actual number…\"
            :date           \"…\"
            :due-date       \"…\"
            :subtotal       <number>
            :tax            <number>
            :total          <number>
            :line-items     [{:description \"…\" :quantity 1
                              :unit-price 100.0 :amount 100.0}
                             … one entry per row in the table …]}
           {…second invoice from gt-text…}])

ITER 3: Build sheets-spec, write the workbook, emit FINAL_ANSWER.

SUBMIT FORMAT — your FINAL println must be EXACTLY:
====================================================
  (println (str \"FINAL_ANSWER: \" (pr-str
    {:result   {:invoices invoices              ;; the vector you built
                :total-amount (reduce + 0.0 (keep :total invoices))
                :summary (str \"Processed \" (count invoices) \" invoices.\")}
     :workbook \"/tmp/invoice-extraction.xlsx\"})))

CRITICAL ANTI-PATTERN CHECKS
=============================
Before submitting, scan your `invoices` vector for these red flags
that mean you didn't do the work:
  ✗ vendor-name = \"<extract from text>\" or \"…\" or any placeholder
  ✗ total = 0.0 (real invoices have non-zero totals)
  ✗ line-items contains a single placeholder entry
  ✗ a binding form like `invoices` with no value (a comment-only entry)

The bench measures whether vendor names, totals, and line-item counts
match the source PDFs. Empty schema-shaped output is a hard failure.
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
