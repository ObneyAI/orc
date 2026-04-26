(ns ai.obney.orc.examples.image-analysis.agentic
  "Style B — RLM-faithful port of predict-rlm/examples/image_analysis.

   A single repl-researcher with :rlm true. The LLM sees only metadata for
   :image-paths (count, size hints) in the root prompt, but receives the
   full vector as a symbolic SCI variable. It writes Clojure that loads the
   images via `image/load-data-uri`, calls (predict-all …) over them, and
   returns via (final! …).

   This is the closest 1:1 analog to predict-rlm's
     await asyncio.gather(*[predict(image=img, query=q) for img in images])
   pattern."
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.doc-skills.interface :as ds]
            [ai.obney.orc.examples.image-analysis.schemas :as schemas]))

(def signature-strategy
  (str
"Analyze multiple images and answer the query about them.

============================================================
YOU ARE RUNNING IN A VALIDATED SCI SANDBOX
============================================================

Your code is parsed AND its symbols are checked BEFORE evaluation.
If you reach for forbidden symbols, the runtime REJECTS the iteration
with a precise error explaining what's wrong. Stay inside the rules
and the skeleton at the bottom — that gets you done in one iteration.

============================================================
AVAILABLE SCI BINDINGS
============================================================

Direct symbols bound for you:
  image-paths  — the vector of image file paths (full value, use directly)
  query        — the user's question about the images (string)
  inputs       — map of {:image-paths […] :query …} (alternative access)

Tools (call as namespaced functions, ALWAYS with a single map arg):
  (image/load-data-uri {:path \"…\"})  -> \"data:image/png;base64,…\"
  (image/file-info     {:path \"…\"})  -> {:path :name :size-bytes :mime}

RLM primitives:
  (predict     {:name … :inputs {…} :instructions \"…\"
                :schema <malli-schema>})
                — single sub-LM call.
  (predict-all {:name … :items [...] :as :item :inputs {…}
                :instructions \"…\" :schema <malli-schema>
                :max-concurrency 4})
                — bounded parallel fan-out, returns a vector of results
                  preserving item order.
  (final! {:writes-key value …})
                — capture the final answer; use this LAST.

============================================================
DO NOT (sandbox will REJECT these BEFORE eval — wasted iteration)
============================================================

  ❌ NO Java interop:        java.nio.file.Files/readAllBytes,
                              java.util.Base64/getEncoder,
                              .method, Class. (constructor)
  ❌ NO unsafe fns:          slurp, spit, eval, require, load,
                              load-string, load-file, sh, exec

If you need to read an image file's bytes, use `image/load-data-uri`.
The SCI sandbox blocks every direct Java interop path.

============================================================
⚠️ FINAL OUTPUT: YOU MUST CALL (final! {…})
============================================================

The ONLY way to commit your structured result is to call:

    (final! {:answer \"…the answer string…\"})

Returning a value as the last expression of your code does NOT commit
the answer. Only `(final! …)` writes into the workflow's blackboard.

============================================================
GOAL
============================================================

In ONE iteration, write code that:
  1. converts each path in `image-paths` to a data URI via
     `image/load-data-uri`
  2. calls `(predict-all …)` over the URIs to get one observation per image
     (use :as :image so each item is bound under `:image` in the per-call
     inputs)
  3. synthesizes the per-image observations into a single coherent answer
     to `query` (compose locally, or one `(predict {…})` call)
  4. ends with (final! {:answer answer-string})

============================================================
SKELETON — copy this exactly, adapting the synthesis if you wish.
============================================================

(let [uris (mapv (fn [p] (image/load-data-uri {:path p})) image-paths)

      observations
      (predict-all
        {:name        \"observe-image\"
         :items       uris
         :as          :image
         :inputs      {:query query}
         :instructions \"Return a concise grounded observation of THIS image
                         relevant to the query. Stay strictly grounded —
                         only describe what is actually visible.\"
         :schema      :string
         :max-concurrency 4})

      answer
      (if (= 1 (count observations))
        (first observations)
        (:answer
          (predict
            {:name         \"synthesize\"
             :inputs       {:query query
                            :observations (vec observations)}
             :instructions \"Combine per-image observations into a single
                             coherent answer to the query. If observations
                             agree, present the agreed-upon facts. If they
                             disagree, surface that explicitly. Do not
                             invent facts not present in the observations.\"
             :schema       [:map [:answer :string]]})))]

  (final! {:answer answer}))
"))

(def workflow
  (dsl/workflow "image-analysis-agentic"
    (dsl/blackboard schemas/blackboard)
    (dsl/repl-researcher "orchestrate"
      :model "google/gemini-2.5-flash"
      :instruction
      (str (ds/compose-instructions :image) "\n\n" signature-strategy)
      :reads [:image-paths :query]
      :writes [:answer]
      :mcp-tools ["image/load-data-uri" "image/file-info"]
      :max-iterations 10
      :rlm {:enabled? true
            :context-key :image-paths
            :max-predict-calls 50
            :max-predict-concurrency 4
            ;; Raised: a single screenshot encoded as a base64 data URI
            ;; can easily run 600-900k chars. The cap exists to prevent
            ;; root-LM-style giant text dumps; image payloads are the
            ;; legitimate exception for vision sub-calls.
            :max-predict-input-chars 5000000
            :history-preview-chars 4000})))
