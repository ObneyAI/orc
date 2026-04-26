(ns ai.obney.orc.examples.document-redaction.agentic
  "Style B — RLM-faithful port of predict-rlm/examples/document_redaction.

   Single repl-researcher: surveys page text, fans out predict-all to
   identify PII targets per doc, then uses pdf/search-text + pdf/redact-rects
   to write the redacted PDFs and emit the structured result.

   Mirrors the structure of invoice_processing/agentic.clj — sandbox warning,
   explicit bindings list, copy-paste skeleton — because that's the shape
   that has empirically held up against gpt-5's tendency to call (final! …)
   with empty data on iteration 1."
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.doc-skills.interface :as ds]
            [ai.obney.orc.examples.document-redaction.schemas :as schemas]))

(def signature-strategy
  (str
"Redact sensitive content from PDFs based on the criteria.

============================================================
YOU ARE RUNNING IN A VALIDATED SCI SANDBOX
============================================================

Your code is parsed AND its symbols are checked BEFORE evaluation.
If you reach for forbidden symbols, the runtime REJECTS the iteration
with a precise error explaining what's wrong. Stay inside the rules and
the skeleton at the bottom — that gets you done in one iteration.

============================================================
AVAILABLE SCI BINDINGS
============================================================

Direct symbols bound for you:
  documents — vector of PDF paths (full value, use directly)
  criteria  — string describing what PII to redact
  inputs    — map of {:documents […] :criteria …} (alternative access)

PDF tools (call as namespaced functions, ALWAYS with a single map arg):
  (pdf/page-count        {:path \"…\"})              -> int
  (pdf/page-text         {:path \"…\" :n 0})         -> string
  (pdf/search-text       {:path \"…\" :n 0 :query \"…\"})
                                                     -> [{:rect [x0 y0 x1 y1] :match \"…\"}]
  (pdf/redact-rects      {:path \"…\" :out-path \"…\" :rect-specs [{:page :rect :fill}]})
                                                     -> out-path string

RLM primitives:
  (predict     {:name … :inputs {…} :instructions \"…\" :schema <malli>})
  (predict-all {:name … :items [...] :as :item :inputs {…}
                :instructions \"…\" :schema <malli> :max-concurrency 4})
                — bounded parallel fan-out, returns vector of results
                  preserving item order.
  (final! {:writes-key value …}) — capture the final answer; call LAST.

============================================================
DO NOT (sandbox will REJECT these BEFORE eval — wasted iteration)
============================================================

  ❌ NO Java interop:        java.io.File., .getName, System/currentTimeMillis
  ❌ NO unsafe fns:          slurp, spit, eval, require, load, sh, exec
  ❌ NO randomness:          rand, rand-int

If you need a unique output path, use a FIXED PATH per input — e.g.
  (str \"/tmp/redacted-\" (last (clojure.string/split path #\"/\")))

============================================================
⚠️ FINAL OUTPUT: YOU MUST CALL (final! {…}) — DO NOT print FINAL_ANSWER
============================================================

DO NOT call (final! {…}) with empty data and stop. The job is to actually
identify PII, search for its rectangles, redact, and report counts.
The convergence detector only re-runs you on errors, not on
\"I gave up.\"

The ONLY way to commit your structured result is to call:

    (final! {:redacted-documents [\"…path…\" \"…path…\"]
             :result {:total-redactions N
                      :page-summaries  [{:page :redaction-count :categories}]
                      :targets         [{:page :text :category :reason}]}})

============================================================
GOAL — in ONE iteration, write code that does:
============================================================

  1. Survey: get page count + per-page text for each document
  2. predict-all over the documents to identify {:page :text :category :reason}
     entries — text MUST literally appear in the page text or
     pdf/search-text won't find it
  3. For each (path, targets) pair: pdf/search-text per target → build a
     rect-specs vector → pdf/redact-rects to write the redacted PDF
  4. Aggregate page summaries (per page: count + distinct categories)
  5. (final! {:redacted-documents [...] :result {...}})

============================================================
SKELETON — copy this exactly. Adapt nothing except the schema names if
your :writes shape differs.
============================================================

(let [;; 1. Survey page text per doc
      doc-survey
      (mapv (fn [path]
              (let [n (pdf/page-count {:path path})]
                {:path path
                 :pages n
                 :page-texts (mapv (fn [i]
                                     {:n i :text (pdf/page-text {:path path :n i})})
                                   (range n))}))
            documents)

      ;; 2. Identify redaction targets — one predict per document, parallel
      targets-per-doc
      (predict-all
        {:name        \"find-targets\"
         :items       doc-survey
         :as          :doc
         :inputs      {:criteria criteria}
         :instructions \"Identify ALL passages on this document matching the
                         criteria. Return [{:page :text :category :reason}]
                         where text LITERALLY appears in the page-texts
                         (otherwise pdf/search-text can't find it for
                         redaction). Stay grounded; do not invent.\"
         :schema      [:vector [:map [:page :int]
                                     [:text :string]
                                     [:category :string]
                                     [:reason :string]]]
         :max-concurrency 4})

      ;; 3. Apply redactions — search rects per target, then redact-rects per doc
      per-doc
      (mapv (fn [{:keys [path]} targets]
              (let [rect-specs
                    (vec (mapcat
                           (fn [{:keys [page text]}]
                             (let [hits (try (pdf/search-text
                                                {:path path :n page :query text})
                                              (catch Exception _ []))]
                               (mapv (fn [{:keys [rect]}]
                                       {:page page :rect rect :fill [0 0 0]})
                                     hits)))
                           targets))
                    out-path (str \"/tmp/redacted-\"
                                  (last (clojure.string/split path #\"/\")))
                    written (if (seq rect-specs)
                              (pdf/redact-rects {:path path :out-path out-path
                                                 :rect-specs rect-specs})
                              out-path)
                    page-summaries
                    (->> targets
                         (group-by :page)
                         (mapv (fn [[page ts]]
                                 {:page page
                                  :redaction-count (count ts)
                                  :categories (vec (distinct (mapv :category ts)))})))]
                {:path path
                 :out-path written
                 :targets targets
                 :page-summaries page-summaries}))
            doc-survey
            targets-per-doc)

      all-targets   (vec (mapcat :targets per-doc))
      page-summaries (vec (mapcat :page-summaries per-doc))]

  (final! {:redacted-documents (mapv :out-path per-doc)
           :result {:total-redactions (count all-targets)
                    :page-summaries page-summaries
                    :targets all-targets}}))
"))

(def workflow
  (dsl/workflow "document-redaction-agentic"
    (dsl/blackboard schemas/blackboard)
    (dsl/repl-researcher "orchestrate"
      :model "google/gemini-2.5-flash"
      :instruction
      (str (ds/compose-instructions :pdf :redaction) "\n\n" signature-strategy)
      :reads [:documents :criteria]
      :writes [:redacted-documents :result]
      :mcp-tools (vec ds/all-tool-names)
      :max-iterations 20
      :rlm {:enabled? true
            :context-key :documents
            :max-predict-calls 100
            :max-predict-concurrency 4
            :max-predict-input-chars 200000
            :history-preview-chars 6000})))
