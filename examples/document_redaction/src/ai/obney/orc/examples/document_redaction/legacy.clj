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
"Redact PII from PDFs based on the criteria.

INPUTS (substituted at runtime):
  PDF paths: {documents}
  Criteria:
{criteria}

YOUR JOB (multi-iteration plan)
================================
ITERATION 1: read every page of EACH document. The {documents} above
substitutes to a literal Clojure vector at runtime, e.g.
[\"/path/to/PNFS.pdf\"]. Use that vector directly:

   (def doc-paths {documents})              ;; substituted to [\"…\"]
   (def per-doc
     (mapv (fn [path]
             (let [n (pdf/page-count {:path path})
                   page-texts (mapv (fn [i]
                                      {:n i :text (pdf/page-text {:path path :n i})})
                                    (range n))]
               {:path path :pages n :page-texts page-texts}))
           doc-paths))
   (println per-doc)

ITERATION 2+: HAND-EXTRACT a target list. Look at the printed page
text and copy each PII string LITERALLY into a {:page :text :category
:reason} entry. Examples of PII per the criteria above (names, phone
numbers, emails, SSNs, addresses, dates).

   (def targets
     [{:page 0 :text \"Margaret Elisabeth Thornbury-Watson\"
       :category \"Name\" :reason \"full name of individual\"}
      {:page 0 :text \"847-291-036\"
       :category \"Government ID\" :reason \"SIN matching criteria\"}
      ;; …one entry per PII string visible on the page text…
      ])

DO NOT write a regex-based parser function. For one document with a
known structure, hand-extraction is faster and more reliable. Each
:text MUST be a verbatim substring of the page text (otherwise
pdf/search-text won't locate it for redaction).

ITERATION N: search and redact, then FINAL_ANSWER.
   (def rect-specs
     (vec (mapcat
            (fn [{:keys [page text]}]
              (let [hits (try (pdf/search-text {:path \"…\" :n page :query text})
                              (catch Exception _ []))]
                (mapv (fn [{:keys [rect]}]
                        {:page page :rect rect :fill [0 0 0]})
                      hits)))
            targets)))
   (def out-path \"/tmp/redacted-PNFS.pdf\")
   (def written
     (if (seq rect-specs)
       (pdf/redact-rects {:path \"…\" :out-path out-path :rect-specs rect-specs})
       out-path))
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

You have NO sub-LLM call available. All identification of PII happens
in YOUR own reasoning across iterations.

TOOLS (single-map arg)
======================
  (pdf/page-count   {:path \"…\"})              -> int
  (pdf/page-text    {:path \"…\" :n 0})         -> string
  (pdf/document-text {:path \"…\"})             -> full string
  (pdf/search-text  {:path \"…\" :n 0 :query \"…\"})
                                                -> [{:rect [x0 y0 x1 y1] :match}]
  (pdf/redact-rects {:path \"…\" :out-path \"…\" :rect-specs [{:page :rect :fill}]})
                                                -> out-path

FORBIDDEN (sandbox rejects BEFORE eval)
=======================================
  ❌ Java interop:  System/currentTimeMillis, .method, Class.,
                    Double/parseDouble, java.io.File., etc.
  ❌ Unsafe fns:    slurp, spit, eval, require, load, sh, exec
  ❌ predict / predict-all / final!  (RLM-mode only)
  ❌ (ns …) and (:require …)  — DO NOT declare a namespace or
                                require anything. Just write top-
                                level forms directly. clojure.string
                                functions are already in scope; call
                                them as `clojure.string/split`,
                                `clojure.string/trim`, etc. directly.

CRITICAL — DO NOT SUBMIT PLACEHOLDERS
=====================================
The skeletons above show SHAPE; you must populate :text with actual
PII strings copied from the page text. Submitting `<extract>` or
`\"...\"` is a hard failure. The bench measures real redaction count
+ category coverage against a known PII inventory.
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
