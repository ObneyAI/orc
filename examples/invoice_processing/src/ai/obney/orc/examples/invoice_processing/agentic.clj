(ns ai.obney.orc.examples.invoice-processing.agentic
  "Style B — the canonical RLM-mode demo.

   The LLM writes Clojure that:
     1. enumerates pages with `pdf/page-count` + `pdf/page-text`
     2. fans out per-page extraction with `(predict-all …)` — this is the
        Clojure analog of predict-rlm's
            await asyncio.gather(*[predict(p) for p in pages])
     3. merges page extracts into a result map
     4. writes the workbook with `xlsx/write-workbook`
     5. captures the result via `(final! …)`."
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.doc-skills.interface :as ds]
            [ai.obney.orc.examples.invoice-processing.schemas :as schemas]))

(def signature-strategy
  (str
"Extract structured invoice data and write an Excel workbook.

============================================================
YOU ARE RUNNING IN A VALIDATED SCI SANDBOX
============================================================

Your code is parsed AND its symbols are checked BEFORE evaluation.
If you reach for forbidden symbols, the runtime REJECTS the iteration
with a precise error explaining what's wrong. Read the rules below
and the skeleton at the bottom — staying inside them gets you done
in one iteration.

============================================================
AVAILABLE SCI BINDINGS
============================================================

Direct symbols bound for you:
  invoices  — the vector of PDF paths (full value, use directly)
  inputs    — map of {:invoices […]} (alternative access)

Tools (call as namespaced functions, ALWAYS with a single map arg):
  (pdf/page-count {:path \"…\"})                              -> int
  (pdf/page-text  {:path \"…\" :n 0})                         -> string
  (pdf/document-text {:path \"…\"})                           -> string
  (pdf/page-image {:path :n :out-path [:dpi]})                -> path
  (pdf/page-image-data-uri {:path :n [:dpi]})                 -> data-uri
  (xlsx/write-workbook {:out-path \"…\" :sheets-spec […]})    -> path
                                ⚠ keys MUST be :out-path and :sheets-spec
                                  (NOT :path, NOT :sheets — those will throw)
  (xlsx/list-sheets {:path \"…\"})                            -> [string]

RLM primitives:
  (predict     {:name … :inputs {…} :instructions \"…\"
                :schema <malli-schema>})
                — single sub-LM call. With a [:map …] schema, returns a
                  structured map matching that schema (top-level fields
                  flattened internally and reassembled — you get the
                  structured shape back).
  (predict-all {:name … :items [...] :as :item :inputs {…}
                :instructions \"…\" :schema <malli-schema>
                :max-concurrency 4})
                — bounded parallel fan-out, returns a vector of results
                  preserving item order. Each element is a structured
                  map per :schema.
  (final! {:writes-key value …})
                — capture the final answer; use this LAST.

Validation/inspection helpers (pure — never coerce):
  (check-shape <malli> v) -> {:ok? true} or {:ok? false :errors […]}
  (describe v)            -> {:type :map/:vector/… :preview \"…\"}
  (ns-explore \"pdf\")    -> [\"page-count\" \"page-text\" …]

============================================================
DO NOT (sandbox will REJECT these BEFORE eval — wasted iteration)
============================================================

  ❌ NO Java interop:        System/currentTimeMillis, Math/abs,
                              .method, Class. (constructor)
  ❌ NO unsafe fns:          slurp, spit, eval, require, load,
                              load-string, load-file, sh, exec
  ❌ NO randomness:          rand, rand-int (not in safe-list)

If you need a unique path, USE A FIXED PATH like
\"/tmp/invoice-extraction.xlsx\". The file will be overwritten on retry.

============================================================
⚠️ FINAL OUTPUT: YOU MUST CALL (final! {…}) — DO NOT print FINAL_ANSWER
============================================================

The ONLY way to commit your structured result is to call:

    (final! {:result <map matching the shape below>
             :workbook <path string>})

DO NOT do any of these — they will be IGNORED or treated as failure:

  ❌ (println (str \"FINAL_ANSWER: \" your-data))
  ❌ (str \"FINAL_ANSWER: \" your-data)
  ❌ Returning a value as the last expression of your code
  ❌ Wrapping in (println …) and assuming the runtime parses stdout

Only `(final! {…})` writes the values into the workflow's blackboard
under the correct keys. The text-pattern fallback is DEPRECATED for
structured outputs and CANNOT spread your map across :writes.

============================================================
GOAL
============================================================

In ONE iteration, write code that:
  1. enumerates pages from `invoices`
  2. predict-all over the pages with the per-page schema below
  3. merges header pages with continuation pages
  4. writes a workbook (Summary + per-invoice sheets)
  5. ends with (final! {:result … :workbook …})  ← MUST be (final! …)

Required shape for `:result`:
  {:invoices     [{:vendor-name … :invoice-number … :date … :due-date …
                   :subtotal … :tax … :total … :line-items [...]}]
   :total-amount 0.0
   :summary      \"…\"}

Required shape for each :line-item:
  {:description … :quantity … :unit-price … :amount …}

Convention: header pages have non-empty :invoice-number; continuation
pages set :vendor-name and :invoice-number to empty strings (\"\")
and 0.0 numerics, populating only :line-items.

============================================================
SKELETON — copy this exactly, only adapt the schema fields you need.
Keep tool calls in the {:out-path … :sheets-spec …} map shape shown.
============================================================

(let [pages (vec (for [p invoices
                       n (range (pdf/page-count {:path p}))]
                   {:path p :n n :text (pdf/page-text {:path p :n n})}))

      extracts (predict-all
                 {:name \"extract-page\"
                  :items pages
                  :as :page
                  :inputs {}
                  :instructions \"Extract invoice fields from THIS page text.
                                  Continuation pages: empty strings for
                                  vendor-name and invoice-number, 0.0 for
                                  numeric fields, only line-items populated.
                                  Stay grounded.\"
                  :schema [:map
                           [:vendor-name :string]
                           [:invoice-number :string]
                           [:date :string]
                           [:due-date :string]
                           [:subtotal :double]
                           [:tax :double]
                           [:total :double]
                           [:line-items [:vector [:map
                                                  [:description :string]
                                                  [:quantity :double]
                                                  [:unit-price :double]
                                                  [:amount :double]]]]]
                  :max-concurrency 4})

      header? (fn [e] (and (string? (:invoice-number e))
                           (not= \"\" (:invoice-number e))))

      ;; Walk in document order, attach continuation pages to prior header.
      invoices-out (loop [es extracts current nil acc []]
                     (if (empty? es)
                       (cond-> acc current (conj current))
                       (let [e (first es) more (rest es)]
                         (if (header? e)
                           (recur more
                                  (assoc (select-keys e [:vendor-name :invoice-number :date
                                                          :due-date :subtotal :tax :total])
                                         :line-items (vec (or (:line-items e) [])))
                                  (cond-> acc current (conj current)))
                           (recur more
                                  (if current
                                    (update current :line-items (fnil into [])
                                            (or (:line-items e) []))
                                    {:vendor-name \"\" :invoice-number \"\"
                                     :date \"\" :due-date \"\"
                                     :subtotal 0.0 :tax 0.0 :total 0.0
                                     :line-items (vec (or (:line-items e) []))})
                                  acc)))))

      total (reduce + 0.0 (keep :total invoices-out))

      out-path \"/tmp/invoice-extraction.xlsx\"

      sheets-spec (into [{:name \"Summary\"
                          :columns [{:header \"Vendor\" :width 24}
                                    {:header \"Invoice #\" :width 14}
                                    {:header \"Date\" :width 12}
                                    {:header \"Due Date\" :width 12}
                                    {:header \"Total\" :width 12}]
                          :rows (mapv (fn [m]
                                        [(:vendor-name m) (:invoice-number m)
                                         (:date m) (:due-date m) (or (:total m) 0.0)])
                                      invoices-out)}]
                        (mapv (fn [m]
                                {:name (str (:vendor-name m) \" \" (:invoice-number m))
                                 :columns [{:header \"Description\" :width 40}
                                           {:header \"Quantity\" :width 10}
                                           {:header \"Unit Price\" :width 12}
                                           {:header \"Amount\" :width 12}]
                                 :rows (mapv (fn [li]
                                               [(:description li) (:quantity li)
                                                (:unit-price li) (:amount li)])
                                             (:line-items m))})
                              invoices-out))

      written (xlsx/write-workbook {:out-path out-path :sheets-spec sheets-spec})]

  (final! {:result {:invoices invoices-out
                    :total-amount total
                    :summary (str \"Processed \" (count invoices-out) \" invoices.\")}
           :workbook written}))"))

(def workflow
  (dsl/workflow "invoice-processing-agentic"
    (dsl/blackboard schemas/blackboard)
    (dsl/repl-researcher "orchestrate"
      :model "openai/gpt-5.4-mini"
      :instruction
      (str (ds/compose-instructions :pdf :xlsx) "\n\n" signature-strategy)
      :reads [:invoices]
      :writes [:result :workbook]
      :mcp-tools (vec ds/all-tool-names)
      :max-iterations 20
      :rlm {:enabled? true
            :context-key :invoices
            :max-predict-calls 200
            :max-predict-concurrency 8
            :max-predict-input-chars 200000
            :history-preview-chars 6000})))
