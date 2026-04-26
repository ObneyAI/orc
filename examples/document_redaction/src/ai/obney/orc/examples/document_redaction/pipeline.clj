(ns ai.obney.orc.examples.document-redaction.pipeline
  "Style A — explicit redaction pipeline.

     1. (code  survey)             — gather per-doc page text
     2. (map-each find-targets)    — per-doc LLM identifies redaction targets
     3. (code  apply-redactions)   — for each doc + its targets, search-text
                                     then redact-rects to write a new PDF
     4. (code  build-result)       — assemble RedactionResult"
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.examples.document-redaction.schemas :as schemas]
            [ai.obney.orc.doc-skills.core.pdf :as pdf]))

(defn survey
  [{:keys [inputs]}]
  (let [docs (:documents inputs)]
    {:doc-survey
     (mapv (fn [path]
             (let [n (pdf/page-count path)]
               {:path path
                :pages n
                :page-texts (mapv (fn [i] {:n i :text (pdf/page-text path i)})
                                  (range n))}))
           docs)}))

(defn apply-redactions
  "For each doc + its identified targets, run pdf/search-text per page and
   pdf/redact-rects. Writes new PDFs and returns redaction telemetry.

   :all-targets shape (from map-each :into):
     [{:path … :pages … :page-texts […] :doc-targets [{:page :text :category :reason}]}]
   i.e. each item is the iteration scope merged with the inner llm's :writes.
   We extract :doc-targets per item and remap by path."
  [{:keys [inputs]}]
  (let [iter-results (or (:all-targets inputs) [])
        targets-by-doc (into {}
                             (map (fn [{:keys [path doc-targets]}]
                                    [path (vec (or doc-targets []))]))
                             iter-results)
        docs (:doc-survey inputs)
        out-root (System/getProperty "java.io.tmpdir")
        per-doc (mapv
                  (fn [{:keys [path]}]
                    (let [targets (get targets-by-doc path [])
                          rect-specs (vec (mapcat
                                            (fn [{:keys [page text]}]
                                              (let [hits (try (pdf/search-text path page text)
                                                              (catch Exception _ []))]
                                                (mapv (fn [{:keys [rect]}]
                                                        {:page page :rect rect :fill [0 0 0]})
                                                      hits)))
                                            targets))
                          out-path (str out-root "/redacted-"
                                        (.getName (java.io.File. ^String path)))
                          written (if (seq rect-specs)
                                    (pdf/redact-rects path out-path rect-specs)
                                    out-path)
                          page-summaries (->> targets
                                              (group-by :page)
                                              (map (fn [[page ts]]
                                                     {:page page
                                                      :redaction-count (count ts)
                                                      :categories (vec (distinct (mapv :category ts)))}))
                                              vec)]
                      {:path path
                       :out-path written
                       :targets targets
                       :page-summaries page-summaries}))
                  docs)
        all-targets (vec (mapcat :targets per-doc))
        total (count all-targets)]
    {:doc-redactions per-doc
     :redacted-documents (mapv :out-path per-doc)
     :result {:total-redactions total
              :page-summaries (vec (mapcat :page-summaries per-doc))
              :targets all-targets}}))

(def workflow
  (dsl/workflow "document-redaction-pipeline"
    (dsl/blackboard schemas/blackboard)
    (dsl/sequence "main"
      (dsl/code "survey"
        :fn "ai.obney.orc.examples.document-redaction.pipeline/survey"
        :reads [:documents]
        :writes [:doc-survey])

      (dsl/map-each "find-targets"
        :from :doc-survey
        :as :doc
        :into :all-targets
        :parallel 4
        (dsl/llm "find-targets-for-doc"
          :model "google/gemini-2.5-flash"
          :instruction
          (str "Identify all text passages on the given document that match the redaction "
               "criteria. For EACH match return a redaction target {:page :text :category :reason}. "
               "Only return passages that LITERALLY appear in the page text — strings that don't "
               "match exactly cannot be located for redaction.")
          :reads [:doc :criteria]
          :writes [:doc-targets]
          :judges ["grounding"]))

      (dsl/code "apply-redactions"
        :fn "ai.obney.orc.examples.document-redaction.pipeline/apply-redactions"
        :reads [:doc-survey :all-targets]
        :writes [:doc-redactions :redacted-documents :result]))))
