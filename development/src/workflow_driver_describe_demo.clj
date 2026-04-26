(ns workflow-driver-describe-demo
  "Validate Milestone 1: run the describe-Sheet agent against a built
   Sheet and print the model's structured report. Requires
   OPENROUTER_API_KEY in the environment.

   Run:
     clj -M:dev -m workflow-driver-describe-demo"
  (:require [clojure.pprint :as pp]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.workflow-driver.interface :as driver]))

(defn- sample-workflow []
  (orc/workflow "describe-demo-sheet"
    (orc/blackboard
      {:documents [:vector :string]
       :doc-summaries [:vector :string]
       :analysis :map})
    (orc/sequence "main"
      (orc/code "survey"
        :fn "fake.ns/survey"
        :reads [:documents]
        :writes [:doc-summaries])
      (orc/llm "synthesize"
        :model "google/gemini-2.5-flash"
        :instruction "Synthesize the per-document summaries into one analysis."
        :reads [:doc-summaries]
        :writes [:analysis]))))

(defn -main [& _args]
  (executor/setup-providers!)
  (let [ctx (h/create-test-context)]
    (try
      (let [sheet-id (orc/build-workflow! ctx (sample-workflow))]
        (println "================================================================")
        (println "Sheet built. ID:" sheet-id)
        (println "================================================================")
        (println "\n--- Snapshot text the LLM will receive ---\n")
        (println (driver/describe-sheet ctx sheet-id))
        (println "\n--- Calling LLM (google/gemini-2.5-flash) ---\n")
        (let [{:keys [outputs usage model]}
              (driver/describe-via-llm! ctx sheet-id)]
          (println "Model:" model)
          (println "Usage:" (pr-str usage))
          (println "\n--- Agent report ---\n")
          (println "PURPOSE:")
          (println (:purpose outputs))
          (println "\nTROUBLE SIGNS:")
          (println (:trouble-signs outputs))
          (println "\nFIRST OPTIMIZATION:")
          (println (:first-optimization outputs))))
      (finally
        (h/stop-context ctx)))))
