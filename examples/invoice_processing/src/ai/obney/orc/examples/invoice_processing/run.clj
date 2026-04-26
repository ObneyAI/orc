(ns ai.obney.orc.examples.invoice-processing.run
  (:require [clojure.java.io :as io]
            [ai.obney.orc.examples.invoice-processing.service :as service]))

(def sample-input-dir
  (io/file (System/getProperty "user.dir") "examples" "invoice_processing" "sample" "input"))

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
  [& {:keys [paths ctx] :or {paths (list-pdf-paths sample-input-dir)}}]
  (service/execute-pipeline (or ctx (get-ctx)) {:invoices paths}))

(defn run-agentic
  [& {:keys [paths ctx] :or {paths (list-pdf-paths sample-input-dir)}}]
  (service/execute-agentic (or ctx (get-ctx)) {:invoices paths}))

(comment
  (run-pipeline)
  (run-agentic))
