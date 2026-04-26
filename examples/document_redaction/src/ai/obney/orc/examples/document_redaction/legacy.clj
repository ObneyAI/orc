(ns ai.obney.orc.examples.document-redaction.legacy
  "Legacy mode — repl-researcher WITHOUT :rlm. Pre-RLM iterative coding
   agent: blackboard values template-substituted into the instruction
   (no metadata-only previews), :mcp-tools available in SCI sandbox,
   NO predict / predict-all / final! host functions, submission via
   FINAL_ANSWER text marker.

   Architectural baseline against Style A, Style B (RLM), and Hybrid."
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.doc-skills.interface :as ds]
            [ai.obney.orc.examples.document-redaction.schemas :as schemas]))

(def signature-strategy
  (str
"Redact PII from PDFs based on the criteria, then emit a structured result.

INPUTS (substituted at runtime — these become Clojure literals):
  PDF paths: {documents}
  Criteria:
{criteria}

HOW THIS RUNTIME WORKS — read carefully
========================================
You're in a SCI sandbox. You'll get up to 15 iterations. Each iteration:
  - You write Clojure code.
  - It executes. Stdout + result + error get fed back to you.
  - DEFS PERSIST ACROSS ITERATIONS — `(def foo …)` in iter 1 is
    available in iter 2. Lean on this. Don't redefine the same data.
  - `clojure.string/*` is in scope — call as `(clojure.string/split …)`.
  - The substituted vector ABOVE — copy its literal value into your
    code. It's the ONLY way to get the paths.

WORKING TOOLS (single-map arg)
==============================
  (pdf/page-count   {:path \"…\"})              -> int
  (pdf/page-text    {:path \"…\" :n 0})         -> string
  (pdf/document-text {:path \"…\"})             -> full string
  (pdf/search-text  {:path \"…\" :n 0 :query \"…\"})
                                                -> [{:rect [x0 y0 x1 y1] :match}]
  (pdf/redact-rects {:path \"…\" :out-path \"…\" :rect-specs [{:page :rect :fill}]})
                                                -> out-path string

FORBIDDEN — sandbox rejects PRE-EVAL
=====================================
  ❌ Java interop:  System/*, .method, Class., Double/parseDouble, etc.
  ❌ Unsafe fns:    slurp, spit, eval, require, load, sh, exec
  ❌ predict / predict-all / final!  (RLM-only)
  ❌ (ns …) / (:require …)  — DON'T declare a namespace; just write
                              top-level forms. clojure.string is in scope.
  ❌ Nested `#(…)` reader literals — Clojure forbids `#( … #( …) …)`
     because the inner `%` is ambiguous. ALWAYS use `(fn [x] …)` for
     anonymous functions when one fn is nested inside another.

RECOMMENDED ITERATION PLAN
==========================
ITER 1: Read every page of every doc. Print them.
        (def doc-paths …)   ;; copy the substituted vector above
        (def per-doc
          (mapv (fn [path]
                  (let [n (pdf/page-count {:path path})]
                    {:path path :pages n
                     :page-texts (mapv (fn [i]
                                         {:n i :text (pdf/page-text {:path path :n i})})
                                       (range n))}))
                doc-paths))
        (println per-doc)

ITER 2 (and onward): NOW that you have the page texts in your prior
        iteration's stdout, IDENTIFY each PII string by reading the
        text directly. Build a target list — one entry per PII string,
        :text MUST be a verbatim substring of the page text (otherwise
        pdf/search-text won't find it for redaction).

        Cover ALL PAGES in the document — each page has its own PII to
        find. Don't truncate after the first page or two.

        (def targets
          [{:page 0 :text \"…actual PII string from page 0 text…\"
            :category \"Name\" :reason \"matches Names criterion\"}
           {:page 0 :text \"…actual SIN string…\"
            :category \"Government ID\" :reason \"matches Government IDs\"}
           ;; ⚠ continue for ALL PII on ALL pages — partial coverage
           ;;   is a failure. predict-rlm finds 70-100 PII items per
           ;;   document of comparable size; if your list has fewer
           ;;   than 30 entries you've missed pages.
           ])

ITER 3: Search rects, build rect-specs, redact, emit FINAL_ANSWER.
        (def rect-specs
          (vec (mapcat
                 (fn [{:keys [page text]}]
                   (let [hits (try (pdf/search-text {:path (first doc-paths) :n page :query text})
                                   (catch Exception _ []))]
                     (mapv (fn [{:keys [rect]}]
                             {:page page :rect rect :fill [0 0 0]})
                           hits)))
                 targets)))
        (def out-path \"/tmp/redacted-out.pdf\")
        (def written (pdf/redact-rects {:path (first doc-paths)
                                        :out-path out-path
                                        :rect-specs rect-specs}))
        (def page-summaries
          (->> targets
               (group-by :page)
               (mapv (fn [[page ts]]
                       {:page page
                        :redaction-count (count ts)
                        :categories (vec (distinct (mapv :category ts)))}))))
        (println (str \"FINAL_ANSWER: \"
                      (pr-str
                        {:redacted-documents [written]
                         :result {:total-redactions (count targets)
                                  :page-summaries page-summaries
                                  :targets targets}})))

SUBMIT FORMAT
=============
The runtime detects FINAL_ANSWER in your stdout, parses the EDN, and
spreads it across the node's :writes (:redacted-documents and :result).
The println MUST be the LAST thing you do in your final iteration.

CRITICAL ANTI-PATTERN CHECKS
=============================
Before submitting, scan your output for:
  ✗ targets has placeholder strings like \"<extract>\" or \"…\"
  ✗ total-redactions < 30 (you missed pages — read all of them)
  ✗ page-summaries covers < N pages where N is doc page-count
  ✗ a binding form `targets` with no value (a comment-only entry)
  ✗ redaction text that DOESN'T appear in the page text verbatim
    (pdf/search-text will return [] and the rect won't be redacted)
"))

(def workflow
  (dsl/workflow "document-redaction-legacy"
    (dsl/blackboard schemas/blackboard)
    (dsl/repl-researcher "orchestrate"
      :model "openai/gpt-5"
      :instruction
      (str (ds/compose-instructions :pdf :redaction) "\n\n" signature-strategy)
      :reads [:documents :criteria]
      :writes [:redacted-documents :result]
      :mcp-tools (vec ds/all-tool-names)
      :max-iterations 15)))
