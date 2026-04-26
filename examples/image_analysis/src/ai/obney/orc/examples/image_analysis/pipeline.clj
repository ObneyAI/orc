(ns ai.obney.orc.examples.image-analysis.pipeline
  "Style A — Pipeline port of predict-rlm/examples/image_analysis.

   Each phase is an explicit node:
     1. (code load-images)        — paths -> base64 data URIs
     2. (map-each analyze-each
          (llm analyze-one))      — parallel per-image observation
     3. (llm synthesize)          — combine per-image findings into final answer

   Per-node tracing/judges/retries; instructions are GEPA-optimizable
   independently."
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.examples.image-analysis.schemas :as schemas]))

(defn load-images
  "code executor: read image-paths from blackboard, return image-uris."
  [{:keys [inputs]}]
  (let [paths (:image-paths inputs)
        ;; Lazy-require to keep the pipeline ns free of imports
        load-uri (fn [path]
                   (let [bytes (java.nio.file.Files/readAllBytes
                                 (.toPath (java.io.File. ^String path)))
                         ext (let [n (clojure.string/lower-case path)]
                               (cond
                                 (.endsWith n ".png")  "image/png"
                                 (.endsWith n ".jpg")  "image/jpeg"
                                 (.endsWith n ".jpeg") "image/jpeg"
                                 (.endsWith n ".webp") "image/webp"
                                 :else "image/png"))
                         b64 (.encodeToString (java.util.Base64/getEncoder) bytes)]
                     (str "data:" ext ";base64," b64)))]
    {:image-uris (mapv load-uri paths)}))

(def workflow
  (dsl/workflow "image-analysis-pipeline"
    (dsl/blackboard schemas/blackboard)
    (dsl/sequence "main"
      (dsl/code "load-images"
        :fn "ai.obney.orc.examples.image-analysis.pipeline/load-images"
        :reads [:image-paths]
        :writes [:image-uris])

      (dsl/map-each "analyze-each"
        :from :image-uris
        :as :image-uri
        :into :per-image-findings
        :parallel 4
        (dsl/llm "analyze-one"
          :model "google/gemini-2.5-flash"
          :instruction
          (str "You are inspecting a single image to help answer a query.\n\n"
               "Return a concise observation about this image specifically as it relates "
               "to the query. Stay grounded — only describe what is actually visible.")
          :reads [:image-uri :query]
          :writes [:per-image-finding]))

      (dsl/llm "synthesize"
        :model "google/gemini-2.5-flash"
        :instruction
        (str "Combine per-image observations into a single coherent answer to the query.\n\n"
             "If observations agree, present the agreed-upon facts. If they disagree, "
             "say so explicitly and present each view. Do not invent facts that aren't "
             "in the per-image findings.")
        :reads [:per-image-findings :query]
        :writes [:answer]))))
