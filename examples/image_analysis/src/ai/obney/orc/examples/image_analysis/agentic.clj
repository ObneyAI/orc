(ns ai.obney.orc.examples.image-analysis.agentic
  "Style B — RLM-faithful port of predict-rlm/examples/image_analysis.

   A single repl-researcher with :rlm true. The LLM sees only metadata for
   :image-paths (count, size hints) in the root prompt, but receives the
   full vector as a symbolic SCI variable. It writes Clojure that converts
   paths to data URIs, calls (predict-all …) over them, and returns via
   (final! …).

   This is the closest 1:1 analog to predict-rlm's
     await asyncio.gather(*[predict(image=img, query=q) for img in images])
   pattern."
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.examples.image-analysis.schemas :as schemas]))

(def signature-strategy
  "Verbatim from predict-rlm/examples/image_analysis/signature.py docstring,
  adapted for the Clojure RLM SCI surface."
  (str
"Analyze multiple images and answer the query about them.

1. Load each path in `image-paths` as a base64 data URI. Use stock Java:

   (defn load-uri [path]
     (let [bytes (java.nio.file.Files/readAllBytes (.toPath (java.io.File. path)))
           ext (let [n (clojure.string/lower-case path)]
                 (cond (.endsWith n \".png\")  \"image/png\"
                       (.endsWith n \".jpg\")  \"image/jpeg\"
                       (.endsWith n \".jpeg\") \"image/jpeg\"
                       (.endsWith n \".webp\") \"image/webp\"
                       :else \"image/png\"))
           b64 (.encodeToString (java.util.Base64/getEncoder) bytes)]
       (str \"data:\" ext \";base64,\" b64)))

2. Use (predict-all …) over the data URIs to extract a per-image observation
   in parallel. Provide the query as a constant input:

   (let [uris (mapv load-uri image-paths)
         findings (predict-all
                    {:name        \"observe-image\"
                     :items       uris
                     :as          :image
                     :inputs      {:query query}
                     :instructions \"Return a concise grounded observation of
                                     this image relevant to the query.\"
                     :schema      [:string]
                     :max-concurrency 4})]
     ...)

3. Synthesize the per-image findings into a single answer that addresses the
   query across all images. You can compose locally OR call (predict …) once
   to do the synthesis with an LLM.

4. End with (final! {:answer answer-string}).
"))

(def workflow
  (dsl/workflow "image-analysis-agentic"
    (dsl/blackboard schemas/blackboard)
    (dsl/repl-researcher "orchestrate"
      :model "google/gemini-2.5-flash"
      :instruction signature-strategy
      :reads [:image-paths :query]
      :writes [:answer]
      :max-iterations 10
      :rlm {:enabled? true
            :context-key :image-paths
            :max-predict-calls 50
            :max-predict-concurrency 4
            :max-predict-input-chars 200000
            :history-preview-chars 4000})))
