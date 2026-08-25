(ns ai.obney.orc.orc-service.checkpointed-researcher-latency-test
  "Deterministic engine-only latency measurements for the checkpoint boundary."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.core.executor :as executor]))

(defn- percentile [samples percentile]
  (let [ordered (vec (sort samples))
        index (min (dec (count ordered))
                   (dec (int (Math/ceil (* percentile (count ordered))))))]
    (nth ordered (max 0 index))))

(defn- measure-ms [f]
  (let [started (System/nanoTime)]
    (f)
    (/ (- (System/nanoTime) started) 1000000.0)))

(defn- summary [samples]
  {:samples (count samples)
   :p50-ms (percentile samples 0.50)
   :p95-ms (percentile samples 0.95)
   :p99-ms (percentile samples 0.99)})

(deftest checkpoint-boundary-latency-distribution
  (testing "paired 30-sample runs report p50/p95/p99 without provider latency"
    (let [plain-node {:type :repl-researcher
                      :instruction "finish"
                      :writes [:summary]
                      :max-iterations 1
                      :rlm {:recursive? true}}
          checkpoint-node (assoc plain-node :rlm {:recursive? true
                                                   :checkpointed? true})
          run-plain #(executor/execute-repl-researcher-rlm plain-node {} :test {})
          run-checkpoint #(executor/execute-repl-researcher-rlm checkpoint-node {} :test {})]
      (with-redefs [llm/predict
                    (fn [_ _ _ _]
                      {:outputs {:code "(store! :memo \"ok\")"}
                       :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
        (dotimes [_ 5] (run-plain) (run-checkpoint))
        (let [plain (summary (repeatedly 30 #(measure-ms run-plain)))
              checkpointed (summary (repeatedly 30 #(measure-ms run-checkpoint)))
              report {:plain plain :checkpointed checkpointed}]
          (is (= 30 (:samples plain)))
          (is (= 30 (:samples checkpointed)))
          (is (every? #(and (number? %) (not (neg? %)))
                      (concat (vals (dissoc plain :samples))
                              (vals (dissoc checkpointed :samples))))
              (pr-str report)))))))
