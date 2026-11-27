(ns ai.obney.orc.gepa.metric-runtime-test
  (:require [ai.obney.orc.gepa.core.logic :as logic]
            [clojure.edn :as edn]
            [ai.obney.orc.evaluation.interface :as evaluation]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]))

(deftest default-metric-descriptor-survives-serialization
  (let [ctx {:tenant-id (random-uuid)}
        descriptor (logic/prepare! ctx (random-uuid) {})
        restored (edn/read-string (pr-str descriptor))
        resolved (logic/resolve-metric ctx restored)]
    (is (= descriptor restored))
    (is (= :default (:kind descriptor)))
    (is (true? (:retry-safe? descriptor)))
    (is (= 3 (:max-attempts descriptor)))
    (is (= 300000 (:timeout-ms descriptor)))
    (is (= :ready (:status resolved)))
    (is (= 1.0 ((:metric-fn resolved) {"expected" "yes"} {"answer" "yes"})))
    (is (= 0.7 ((:metric-fn resolved) {"expected" "yes"} {"answer" "yes indeed"})))))

(deftest custom-metric-identity-does-not-fall-back-after-registration-loss
  (let [ctx {:tenant-id (random-uuid)}
        result {:score 0.82 :feedback "Keep this exact feedback.\nAnd this line."}
        f (fn [_ _] result)
        descriptor (logic/prepare! ctx (random-uuid)
                    {:metric-fn f :metric-id "domain/completeness" :metric-revision "7"})
        restored (edn/read-string (pr-str descriptor))]
    (is (= :custom (:kind restored)))
    (is (= "domain/completeness" (:metric-id restored)))
    (is (= "7" (:revision restored)))
    (is (false? (:retry-safe? restored)))
    (is (= result ((:metric-fn (logic/resolve-metric ctx restored)) {} {})))
    (is (= :recovery-required
           (:status (logic/resolve-metric {:tenant-id (random-uuid)} restored))))
    (logic/unregister! ctx restored)
    (is (= :metric-registration-missing
           (:reason (logic/resolve-metric ctx restored))))
    (logic/register! ctx restored f)
    (is (= result ((:metric-fn (logic/resolve-metric ctx restored)) {} {})))
    (is (= :recovery-required
           (:status (logic/resolve-metric ctx (assoc restored :revision "8")))))
    (logic/unregister! ctx restored)))

(deftest restored-judge-metric-keeps-its-task-provider-and-model
  (let [original {:provider :original-provider :model "original/model"}
        ctx {:tenant-id (random-uuid)}
        calls (atom [])
        descriptor (evaluation/call-with-judge-config original
                     #(logic/prepare! ctx (random-uuid)
                       {:judges {:weights {:instruction-following 1.0}
                                 :task "Verify the supplied incident evidence."}}))
        restored (edn/read-string (pr-str descriptor))]
    (is (= :judges (:kind restored)))
    (is (true? (:retry-safe? restored)))
    (is (= original (get-in restored [:configuration :judge-config])))
    (with-redefs [llm/predict
                  (fn [provider _module inputs options]
                    (swap! calls conj {:provider provider :inputs inputs :options options})
                    {:outputs {:level 4 :reasoning "Supported by incident evidence."
                               :requirements-met ["claim"] :requirements-missed []
                               :feedback "Cite the exact evidence."}
                     :model "original/model" :usage {:total-tokens 1}})]
      (evaluation/call-with-judge-config {:provider :changed-provider :model "changed/model"}
        #(let [metric (:metric-fn (logic/resolve-metric ctx restored))
               result (metric {"question" "What happened?"} {:answer "The incident."})]
           (is (= 0.75 (:score result)))
           (is (.contains (:feedback result) "Cite the exact evidence.")))))
    (is (= 1 (count @calls)))
    (is (= :original-provider (:provider (first @calls))))
    (is (= "original/model" (get-in (first @calls) [:options :model])))
    (is (= "Verify the supplied incident evidence."
           (get-in (first @calls) [:inputs :instruction])))))

(deftest one-invocation-preserves-valid-results-including-zero
  (doseq [result [0.0 {:score 0.8 :feedback "Preserve the exact evidence.\nSecond line."
                       :reasoning "A supplied claim matches the source."}]]
    (let [ctx {:tenant-id (random-uuid)}
          calls (atom [])
          input {:question "q"}
          output {:answer "a"}
          descriptor (logic/prepare! ctx (random-uuid)
                      {:metric-fn (fn [i o] (swap! calls conj [i o]) result)})
          outcome (logic/invoke-once! ctx descriptor input output)]
      (is (= :completed (:status outcome)))
      (is (= (if (number? result) {:score result :feedback nil} result)
             (:result outcome)))
      (is (= [[input output]] @calls))
      (logic/unregister! ctx descriptor))))

(deftest metric-failures-never-become-zero-grades
  (doseq [f [(fn [_ _] (throw (ex-info "Metric unavailable" {})))
             (fn [_ _] {:score nil :feedback "not a grade"})
             (fn [_ _] {:score Double/NaN :feedback "not a grade"})]]
    (let [ctx {:tenant-id (random-uuid)}
          descriptor (logic/prepare! ctx (random-uuid) {:metric-fn f})
          outcome (logic/invoke-once! ctx descriptor {} {})]
      (is (= :failed (:status outcome)))
      (is (not (contains? outcome :result)))
      (logic/unregister! ctx descriptor)))
  (with-redefs [llm/predict (fn [& _] (throw (ex-info "Judge transport failed" {})))]
    (let [ctx {:tenant-id (random-uuid)}
          descriptor (logic/prepare! ctx (random-uuid) {:judges {:grounding 1.0}})
          outcome (logic/invoke-once! ctx descriptor {} {:answer "answer"})]
      (is (= :failed (:status outcome)))
      (is (not (contains? outcome :result))))))

(deftest unknown-metric-outcomes-obey-explicit-retry-and-time-budgets
  (let [observed (atom [])
        ctx {:tenant-id (random-uuid)
             :gepa/metric-runner (fn [thunk timeout-ms]
                                   (swap! observed conj timeout-ms)
                                   (thunk)
                                   (throw (java.util.concurrent.TimeoutException. "Response lost")))}
        calls (atom 0)
        descriptor (logic/prepare! ctx (random-uuid)
                    {:metric-fn (fn [_ _] (swap! calls inc) 0.7)
                     :metric-retry-safe? true
                     :config {:metric-timeout-ms 456789 :metric-max-attempts 2}})
        outcome (logic/invoke-once! ctx descriptor {} {})]
    (is (= :unknown (:status outcome)))
    (is (= :metric-timeout (:reason outcome)))
    (is (not (contains? outcome :result)))
    (is (= [456789] @observed))
    (is (= 1 @calls))
    (is (true? (logic/retry-allowed? descriptor 1 outcome)))
    (is (false? (logic/retry-allowed? descriptor 2 outcome)))
    (is (false? (logic/retry-allowed? (assoc descriptor :retry-safe? false) 1 outcome)))
    (is (false? (logic/retry-allowed? descriptor 1 {:status :completed})))
    (logic/unregister! ctx descriptor)))

(deftest factory-metrics-reconstruct-without-process-registrations
  (doseq [[kind factory expected actual]
          [[:exact-match logic/make-exact-match-metric "Yes" "Yes"]
           [:contains logic/make-contains-metric "YES" "yes indeed"]]]
    (let [descriptor (logic/prepare! {:tenant-id (random-uuid)} (random-uuid)
                      {:metric-fn (factory "answer")})
          restored (edn/read-string (pr-str descriptor))
          outcome (logic/invoke-once! {:tenant-id (random-uuid)} restored
                   {"answer" expected} {"answer" actual})]
      (is (= kind (:kind restored)))
      (is (= {:output-key "answer"} (:configuration restored)))
      (is (true? (:retry-safe? restored)))
      (is (= {:status :completed :result {:score 1.0 :feedback nil}} outcome))))
  (let [descriptor (logic/prepare! {:tenant-id (random-uuid)} (random-uuid)
                    {:metric-fn (logic/make-judge-metric
                                  {:weights {:grounding 1.0} :task "Check evidence."})})]
    (is (= :judges (:kind descriptor)))
    (is (= "Check evidence." (get-in descriptor [:configuration :task])))
    (is (map? (get-in descriptor [:configuration :judge-config])))))

(deftest native-runner-bounds-an-unfinished-metric
  (let [ctx {:tenant-id (random-uuid)}
        release (promise)
        descriptor (logic/prepare! ctx (random-uuid)
                    {:metric-fn (fn [_ _] @release 0.9)
                     :config {:metric-timeout-ms 25}})
        invocation (future (logic/invoke-once! ctx descriptor {} {}))]
    (try
      (let [outcome (deref invocation 2000 ::caller-stuck)]
        (is (= :unknown (:status outcome)))
        (is (= :metric-timeout (:reason outcome)))
        (is (not (contains? outcome :result))))
      (finally
        (deliver release true)
        (future-cancel invocation)
        (logic/unregister! ctx descriptor)))))
