(ns ai.obney.orc.examples.image-analysis.run
  "REPL entry points for the image_analysis port.

   Usage from a connected REPL with orc-dev started:

     (require '[ai.obney.orc.examples.image-analysis.run :as run])
     (run/run-pipeline)   ;; Style A
     (run/run-agentic)    ;; Style B"
  (:require [clojure.java.io :as io]
            [ai.obney.orc.examples.image-analysis.service :as service]))

(def default-query
  "What is shown in these images? Provide a concise factual description.")

(def sample-input-dir
  "Resolved relative to the example component's root."
  (io/file (System/getProperty "user.dir")
           "examples" "image_analysis" "sample" "input"))

(def sample-output-root
  (io/file (System/getProperty "user.dir")
           "examples" "image_analysis" "sample" "output"))

(defn list-image-paths
  "Discover image files in `dir`. Accepts PNG/JPG/JPEG/WEBP."
  [dir]
  (let [exts #{".png" ".jpg" ".jpeg" ".webp"}]
    (->> (file-seq (io/file dir))
         (filter #(.isFile %))
         (filter #(let [n (clojure.string/lower-case (.getName %))]
                    (some (fn [e] (.endsWith n e)) exts)))
         (mapv #(.getAbsolutePath %)))))

(defn- get-ctx
  "Pull the live orc context from dev/ctx if loaded, else error."
  []
  (or (try @(requiring-resolve 'dev/ctx) (catch Exception _ nil))
      (when-let [f (try (requiring-resolve 'dev/ctx) (catch Exception _ nil))]
        (f))
      (throw (ex-info "No orc dev context available. Run (dev/start!) first."
                      {:type :no-context}))))

(defn run-pipeline
  "Run the Style-A pipeline against all images under sample/input/.
   Returns the orc result map (status/outputs/duration-ms/...)."
  [& {:keys [paths query ctx]
      :or   {paths (list-image-paths sample-input-dir)
             query default-query}}]
  (let [ctx (or ctx (get-ctx))]
    (service/execute-pipeline ctx
      {:image-paths paths
       :query       query})))

(defn run-agentic
  "Run the Style-B repl-researcher (RLM mode) against all images under
   sample/input/. Returns the orc result map; check (:rlm result) for
   subcall-usage / predict-call-count / final-source telemetry."
  [& {:keys [paths query ctx]
      :or   {paths (list-image-paths sample-input-dir)
             query default-query}}]
  (let [ctx (or ctx (get-ctx))]
    (service/execute-agentic ctx
      {:image-paths paths
       :query       query})))

(comment
  ;; Start orc once (in this REPL) so dev/ctx is available:
  ;;   (require 'dev) (dev/start!)

  (run-pipeline)
  ;; => {:status :success :outputs {:answer "…"} :duration-ms 5234}

  (run-agentic)
  ;; => {:status :success :outputs {:answer "…"} :duration-ms 8120
  ;;     :rlm {:enabled? true :predict-call-count 3
  ;;           :root-usage {…} :subcall-usage {…} :final-source :final!}}

  ;; Ad hoc with a custom query:
  (run-pipeline :query "What text is visible? List it verbatim.")
  )
