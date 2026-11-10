(ns ai.obney.orc.orc-service.researcher-failure-metadata-test
  "BUDGET-7c: actual strict provider failure through SQLite, canonical completion and family."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.researcher-model-fallback-test :as transport]
            [ai.obney.orc.orc-service.ordinary-transport-failure-e2e-test :as http]))

(def last-proof (atom nil))

(deftest strict-researcher-provider-failure-retains-public-metadata
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "orc-researcher-failure-" (make-array java.nio.file.attribute.FileAttribute 0)))
        server (transport/server!)]
    (try
      (llm/register-provider! :researcher-failure-fixture
        {:provider :openrouter :model "fixture/prime"
         :config {:api-base (:url server) :api-key http/fixture-token :timeout 10000}})
      (h/with-async-test-context
        [ctx {:event-store-conn {:type :sqlite :database-file (str (io/file directory "store.sqlite")) :maximum-pool-size 2}
              :context {:tenant-id (random-uuid) :llm-provider :researcher-failure-fixture}}]
        (let [sheet (orc/build-workflow! ctx
                      (orc/workflow "researcher-public-failure"
                        (orc/blackboard {:summary [:string {:description "Researcher's final summary."}]})
                        (orc/repl-researcher "research" :model "fixture/prime"
                          :instruction "Research and finalize the summary." :writes [:summary]
                          :max-iterations 1
                          :options {:output-mode :json-schema :max-retries 0 :max-tokens 65536}
                          :rlm {:recursive? true :checkpointed? false})))
              tick (random-uuid)
              result (orc/execute ctx sheet {} :tick-id tick :timeout-ms 60000 :max-ticks 1)
              events (orc/value-log-read-tick-events ctx (:tenant-id ctx) tick)
              completed (filterv #(= :sheet/node-execution-completed (:event/type %)) events)
              node (first (filter #(= :repl-researcher (:node-type %)) completed))
              family (orc/get-execution-family ctx tick)
              writes (mapv #(orc/value-log-resolve-writes ctx (:tenant-id ctx) tick %) completed)
              proof {:directory (str directory) :result result :events events :node node :family family
                     :writes writes :requests @(:requests server)}]
          (reset! last-proof proof)
          (spit (io/file directory "proof.edn") (pr-str proof))
          (is (= ["fixture/prime"] (mapv :model (:requests proof))) "Exactly one actual HTTP request; no retry/fallback")
          (is (= :failure (:status result)))
          (is (not (contains? result :failure-kind)) "Preserve existing root result API")
          (is (= 1 (count completed)))
          (is (= :failure (:status node)))
          (is (= :transport-failure (:failure-kind node)))
          (is (= {:type :litellm/server-error :http-status 503} (get-in node [:provider-evidence :transport])))
          (is (some #(= :transport-failure (get-in % [:failure :kind])) (tree-seq coll? seq family)))
          (is (some #(= {:type :litellm/server-error :http-status 503}
                         (get-in % [:failure :provider-evidence :transport])) (tree-seq coll? seq family)))
          (is (every? empty? writes) "Failure publishes no declared output")
          (is (not (str/includes? (pr-str (select-keys proof [:result :events :family])) http/fixture-token)))
          (is (not (contains? node :model-fallback)) "Absent optional metadata remains absent")))
      (finally (http/stop-server! server)))))
