(ns ai.obney.orc.examples.document-redaction.run
  (:require [clojure.java.io :as io]
            [ai.obney.orc.examples.document-redaction.service :as service]))

(def default-criteria
  "# Redaction Criteria

Redact all PII (Personally Identifiable Information):
- Person names (employees, contractors, references)
- Phone numbers
- Email addresses
- Street addresses
- SSNs and other government IDs
- Bank account / routing numbers")

(def sample-input-dir
  (io/file (System/getProperty "user.dir") "examples" "document_redaction" "sample" "input"))

(defn list-pdf-paths [dir]
  (->> (file-seq (io/file dir))
       (filter #(.isFile %))
       (filter #(.endsWith (clojure.string/lower-case (.getName %)) ".pdf"))
       (mapv #(.getAbsolutePath %))))

(defn- get-ctx []
  (or (try @(requiring-resolve 'dev/ctx) (catch Exception _ nil))
      (when-let [f (try (requiring-resolve 'dev/ctx) (catch Exception _ nil))] (f))
      (throw (ex-info "No orc dev context. Run (dev/start!) first." {}))))

(defn run-pipeline
  [& {:keys [paths criteria ctx]
      :or {paths (list-pdf-paths sample-input-dir) criteria default-criteria}}]
  (service/execute-pipeline (or ctx (get-ctx))
    {:documents paths :criteria criteria}))

(defn run-agentic
  [& {:keys [paths criteria ctx]
      :or {paths (list-pdf-paths sample-input-dir) criteria default-criteria}}]
  (service/execute-agentic (or ctx (get-ctx))
    {:documents paths :criteria criteria}))

(comment (run-pipeline) (run-agentic))
