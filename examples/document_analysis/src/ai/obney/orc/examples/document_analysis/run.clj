(ns ai.obney.orc.examples.document-analysis.run
  "REPL entry points for document_analysis."
  (:require [clojure.java.io :as io]
            [ai.obney.orc.examples.document-analysis.service :as service]))

(def default-criteria
  "# Report Criteria

Produce a markdown report with these sections:
- Overview (1-2 paragraphs of context)
- Key Dates (deadlines, effective dates, milestones — table format)
- Key Entities (organizations, contacts, roles)
- Recommendations (1-2 paragraphs)")

(def sample-input-dir
  (io/file (System/getProperty "user.dir")
           "examples" "document_analysis" "sample" "input"))

(defn list-pdf-paths [dir]
  (->> (file-seq (io/file dir))
       (filter #(.isFile %))
       (filter #(.endsWith (clojure.string/lower-case (.getName %)) ".pdf"))
       (mapv #(.getAbsolutePath %))))

(defn- get-ctx []
  (or (try @(requiring-resolve 'dev/ctx) (catch Exception _ nil))
      (when-let [f (try (requiring-resolve 'dev/ctx) (catch Exception _ nil))] (f))
      (throw (ex-info "No orc dev context available. Run (dev/start!) first." {}))))

(defn run-pipeline
  [& {:keys [paths criteria ctx]
      :or {paths (list-pdf-paths sample-input-dir) criteria default-criteria}}]
  (let [ctx (or ctx (get-ctx))]
    (service/execute-pipeline ctx
      {:documents paths :criteria criteria})))

(defn run-agentic
  [& {:keys [paths criteria ctx]
      :or {paths (list-pdf-paths sample-input-dir) criteria default-criteria}}]
  (let [ctx (or ctx (get-ctx))]
    (service/execute-agentic ctx
      {:documents paths :criteria criteria})))

(comment
  (run-pipeline)
  (run-agentic))
