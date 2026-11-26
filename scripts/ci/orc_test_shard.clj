(ns orc-test-shard
  "Partition Polylith's discovered test namespaces on its original project classpath."
  (:require [polylith.clj.core.clojure-test-test-runner.interface :as runner]
            [polylith.clj.core.test-runner-contract.interface :as contract]))

(defn create [{:keys [workspace project] :as options}]
  (let [index (Integer/parseInt (System/getenv "ORC_TEST_SHARD_INDEX"))
        total (Integer/parseInt (System/getenv "ORC_TEST_SHARD_COUNT"))]
    (when-not (< -1 index total)
      (throw (ex-info "Invalid aggregate test shard" {:index index :count total})))
    (let [in-shard? (fn [{:keys [^String namespace]}]
                      (= index (Math/floorMod (.hashCode namespace) total)))
          select-tests (fn [entity]
                         (update-in entity [:namespaces :test] #(filterv in-shard? %)))
          select-bricks (fn [bricks] (mapv select-tests bricks))
          shard (runner/create (assoc options
                                      :workspace (-> workspace
                                                     (update :components select-bricks)
                                                     (update :bases select-bricks))
                                      :project (select-tests project)))]
      (when-not (contract/tests-present? shard {})
        (throw (ex-info "No test namespaces selected" {:index index :count total})))
      (println "Running aggregate test shard" (inc index) "of" total)
      shard)))
