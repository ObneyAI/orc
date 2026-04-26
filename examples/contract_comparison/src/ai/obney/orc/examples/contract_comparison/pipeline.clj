(ns ai.obney.orc.examples.contract-comparison.pipeline
  "Style A — explicit pipeline.

     1. (code  survey)        — pull text from each contract
     2. (map-each summarize)  — per-contract structural summary
     3. (llm    compare)      — produce ComparisonResult"
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.examples.contract-comparison.schemas :as schemas]
            [ai.obney.orc.doc-skills.core.pdf :as pdf]))

(defn survey
  [{:keys [inputs]}]
  (let [contracts (:contracts inputs)]
    {:doc-summaries
     (mapv (fn [path]
             (let [raw (pdf/document-text path)
                   text (subs raw 0 (min (count raw) 20000))]
               {:path path
                :pages (pdf/page-count path)
                :text text}))
           contracts)}))

(def workflow
  (dsl/workflow "contract-comparison-pipeline"
    (dsl/blackboard schemas/blackboard)
    (dsl/sequence "main"
      (dsl/code "survey"
        :fn "ai.obney.orc.examples.contract-comparison.pipeline/survey"
        :reads [:contracts]
        :writes [:doc-summaries])

      (dsl/llm "compare"
        :model "google/gemini-2.5-flash"
        :instruction
        (str "Compare these two (or more) contracts and produce a structured ComparisonResult.\n\n"
             "Identify:\n"
             "1. report — a markdown comparison report\n"
             "2. section-diffs — per-section diffs with significance (major/minor/identical)\n"
             "3. key-differences — high-level differences with impact analysis\n"
             "4. summary — executive summary of the most important differences\n\n"
             "Stay grounded in the contract texts — do not fabricate sections or terms.")
        :reads [:doc-summaries]
        :writes [:result]
        :judges ["grounding"]))))
