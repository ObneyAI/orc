(ns ai.obney.orc.gepa.budget-integration-test
  "Integration test: verify GEPA optimization loop stops at budget.
   Uses a mock workflow that returns instantly — no LLM calls."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.grain-test-utils.interface :as tu]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.gepa.interface :as gepa]
            [ai.obney.orc.gepa.interface.schemas]
            [ai.obney.orc.gepa.core.read-models :as rm]
            [ai.obney.orc.gepa.core.optimization :as optimization]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.control-plane.interface :as control-plane]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

;; A trivial code-only workflow (no LLM, instant execution)
(defn make-identity-workflow []
  (sheet/workflow "gepa-budget-test"
    (sheet/blackboard {:input :string :output :string})
    (sheet/code "pass-through"
      :fn "ai.obney.orc.gepa.budget-integration-test/identity-fn"
      :reads [:input]
      :writes [:output])))

(defn identity-fn [{:keys [inputs]}]
  {:output (get inputs :input "default")})

(deftest gepa-stops-at-budget
  (testing "optimization completes within budget and doesn't loop forever"
    (tu/with-test-context [ctx]
      (let [;; Start control plane so todo processors run
            cp (control-plane/start {:event-store (:event-store ctx)
                                     :cache (:cache ctx)
                                     :context ctx
                                     :heartbeat-interval-ms 100
                                     :staleness-threshold-ms 1000})
            ;; Build workflow
            sheet-id (sheet/build-workflow! ctx (make-identity-workflow))
            ;; Simple trainset
            trainset [{"input" "hello"} {"input" "world"}]
            ;; Track metric calls
            call-count (atom 0)
            metric-fn (fn [input output]
                        (let [n (swap! call-count inc)]
                          (println "  metric #" n "input-keys:" (keys input) "output-nil?:" (nil? output))
                          0.5)) ;; constant score, never perfect
            ;; Run GEPA with small budget
            result (gepa/optimize! ctx
                     {:sheet-id sheet-id
                      :trainset trainset
                      :valset trainset
                      :metric-fn metric-fn
                      :config {:max-metric-calls 2
                               :max-generations 2
                               :reflection-lm "us.anthropic.claude-sonnet-4-20250514-v1:0"}
                      :inherit-from-previous false
                      :block? true
                      :timeout-ms 10000})]
        (try
          (println "GEPA result:" (select-keys result [:status :best-score :duration-ms :error]))
          (println "Metric calls:" @call-count)
          ;; Trace GEPA events for this optimization
          (let [all (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)}))
                opt-id (:optimization-id result)
                gepa-evts (filter #(and (= "gepa" (namespace (:event/type %)))
                                        (= opt-id (:optimization-id %))) all)]
            (println "\nGEPA event trace:")
            (doseq [[t c] (sort (frequencies (map :event/type gepa-evts)))]
              (println "  " t c)))
          (println "Optimization summary:"
                   (select-keys (rm/get-optimization-summary ctx (:optimization-id result))
                                [:total-metric-calls :status]))

          ;; The test: metric calls should be bounded
          (is (= :completed (:status result))
              "An instant code-only optimization must complete; timeout is a lifecycle failure")
          (is (= 2 @call-count)
              (str "The two-example seed evaluation must consume the exact budget, got " @call-count))
          (let [summary (rm/get-optimization-summary ctx (:optimization-id result))]
            (println "Budget accounting: total-metric-calls=" (:total-metric-calls summary)
                     "max=" (get-in summary [:config :max-metric-calls]))
            (is (= 2 (:total-metric-calls summary 0))
                (str "Tracked metric calls must equal the configured budget, got " (:total-metric-calls summary))))
          (finally
            (control-plane/stop cp)))))))
