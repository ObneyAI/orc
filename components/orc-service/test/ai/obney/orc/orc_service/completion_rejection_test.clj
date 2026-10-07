(ns ai.obney.orc.orc-service.completion-rejection-test
  "S7w — a rejected lifecycle-critical command must fail the node visibly
   instead of stranding the run until its deadline."
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(defn- workflow []
  (sheet/workflow "s7w-unknown-kind"
    (sheet/blackboard {:answer :string})
    (sheet/llm "answer"
      :instruction "Return an answer."
      :writes [:answer]
      :options {:use-function-calling? true :max-retries 0 :retry-delay-ms 1})))

(deftest an-out-of-enum-failure-kind-fails-the-node-instead-of-wedging-the-run
  (h/with-async-test-context [ctx]
    (with-redefs [llm/predict
                  (fn [& _]
                    (throw (ex-info "provider exploded"
                                    {:failure-kind :brand-new-kind-not-in-enum})))]
      (let [sheet-id (sheet/build-workflow! ctx (workflow))
            started (System/currentTimeMillis)
            result (sheet/execute (assoc ctx :llm-provider :deterministic-provider)
                                  sheet-id {} :timeout-ms 4000)
            elapsed (- (System/currentTimeMillis) started)]
        (is (= :failure (:status result)) (pr-str (select-keys result [:status :error])))
        (is (< elapsed 3500) "run reached a terminal status promptly, not at its deadline")
        (is (re-find #"(?i)provider exploded" (str (:error result)))
            "original error text is preserved")
        (is (re-find #"Completion rejected: :sheet/complete-node-execution" (str (:error result)))
            "error names the rejected completion")
        (is (re-find #":brand-new-kind-not-in-enum" (str (:error result)))
            "original failure kind is kept as detail")))))
