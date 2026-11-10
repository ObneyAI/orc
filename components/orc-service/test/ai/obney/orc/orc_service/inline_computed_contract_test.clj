(ns ai.obney.orc.orc-service.inline-computed-contract-test
  "Computed inputs keep concrete shape without invented meanings or :any."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [malli.core :as m]
            [ai.obney.orc.orc-service.core.blackboard-schema :as schemas]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.core.rlm-sandbox :as sandbox]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.inline-input-contract-test :as fixture]))

(def proof (atom []))

(deftest inferred-scalar-contracts-validate-the-actual-values
  (doseq [value [nil "" false (byte 1) (short 1) (int 1) (long 1) 1N (biginteger 1)
                (float 1.25) 1.25 1.25M :named/value (random-uuid)
                (java.util.Date. 0) (java.time.Instant/ofEpochMilli 0)]]
    (let [schema (schemas/infer-specific-schema value [:computed])]
      (is (m/validate schema value) (str (class value) " must satisfy " schema))))
  (doseq [value [1/3 Double/NaN Double/POSITIVE_INFINITY Float/NaN]]
    (let [error (try (schemas/infer-specific-schema value [:computed :amount]) nil
                     (catch clojure.lang.ExceptionInfo e e))]
      (is (= :unrepresentable-value (:reason (ex-data error))))
      (is (= [:computed :amount] (:path (ex-data error)))))))

(deftest inferred-inputs-keep-observed-shape-and-no-invented-meaning
  (reset! proof [])
  (doseq [variant [:inline :emitted]]
  (let [code (case variant
               :inline "(do (store! :computed {:records [{:id \"S1\" :count 0 :enabled false :note nil}]}) (store! :declared []) (llm \"computed\" :reads [:computed :declared] :writes [:inline-result] :instruction \"Return the controlled result.\") (final! {:research-result (get-var :inline-result)}))"
               :emitted "(if (get-var :inline-result) (final! {:research-result (get-var :inline-result)}) (do (store! :computed {:records [{:id \"S1\" :count 0 :enabled false :note nil}]}) (store! :declared []) (emit-tree! [:sequence [:llm {:instruction \"Return the controlled result.\" :reads [:computed :declared] :writes [:inline-result] :output-schemas {:inline-result :string}}] [:final {:keys [:inline-result]}]])))")
        {:keys [server pool requests errors url]} (#'fixture/fixture! code)
        options {:output-mode :json-schema :max-retries 0 :max-tokens 2222}
        db (str "/tmp/inline-computed-contract-" (random-uuid) ".sqlite")]
    (try
      (llm/register-provider! :computed-input-contract
        {:provider :openrouter :model "fixture/prime"
         :config {:api-base url :api-key "CONTROLLED-NOT-A-REAL-KEY" :timeout 10000}})
      (h/with-async-test-context [ctx {:context {:tenant-id (random-uuid) :llm-provider :computed-input-contract}
                                      :event-store-conn {:type :sqlite :database-file db :maximum-pool-size 4}}]
        (let [sheet (orc/build-workflow! ctx
                      (orc/workflow "computed-input-contract"
                        (orc/blackboard {:inline-result :string :research-result :string
                                         :declared [:vector {:description "Declared identifiers; an empty list is valid."} :string]})
                        (orc/repl-researcher "research" :model "fixture/prime" :writes [:research-result]
                          :instruction "Execute the controlled computed specialist." :max-iterations 2 :options options
                          :rlm {:recursive? true :checkpointed? true :sub-model "fixture/inline" :sub-options options})))
              result (orc/execute ctx sheet {} :timeout-ms 30000 :max-ticks 1 :llm-call-budget 3)
              bodies (mapv :body @requests)
              prompt (#'fixture/text-content (first (filter #(= "fixture/inline" (:model %)) bodies)))]
          (swap! proof conj {:kind :runtime :variant variant :result result :requests @requests
                             :events (orc/value-log-read-tick-events ctx (:tenant-id ctx) (:trace-id result))})
          (is (empty? @errors))
          (is (= :success (:status result)))
          (is (= "inline-ok" (get-in result [:outputs :research-result])))
          (is (= :success (:root-status (orc/get-tick ctx (:trace-id result)))))
          (is (= (if (= :inline variant) ["fixture/prime" "fixture/inline"]
                     ["fixture/prime" "fixture/inline" "fixture/prime"])
                 (mapv :model bodies)))
          (is (not (str/includes? prompt "(any)")))
          (is (not (str/includes? prompt "Input: ")))
          (doseq [field ["`computed` (json object)" "`records` (json array" "`id` (str)" "`count` (int)" "`enabled` (bool)" "`note`"]]
            (is (str/includes? prompt field) field))
          (is (str/includes? prompt "Declared identifiers; an empty list is valid."))
          (is (= [] (#'fixture/json-value (#'fixture/value-text prompt :strict :declared))))
          (is (= {:records [{:id "S1" :count 0 :enabled false :note nil}]}
                 (#'fixture/json-value (#'fixture/value-text prompt :strict :computed))))))
      (finally
        (.stop server 0) (.shutdownNow pool)
        (doseq [suffix ["" "-shm" "-wal"]] (.delete (java.io.File. (str db suffix)))))))))

(deftest ambiguous-inputs-and-missing-output-contracts-fail-before-provider
  (let [{:keys [server pool requests url]} (#'fixture/fixture! "unused")]
    (try
      (llm/register-provider! :computed-contract-diagnostic
        {:provider :openrouter :model "fixture/inline"
         :config {:api-base url :api-key "CONTROLLED-NOT-A-REAL-KEY" :timeout 10000}})
      (doseq [{:keys [label values board writes path detail]} [{:label :empty-records :values {:computed {:records []}}
                                                             :writes [:inline-result] :path [:computed :records] :detail :empty-collection}
                                                            {:label :empty-map :values {:computed {}}
                                                             :writes [:inline-result] :path [:computed] :detail :empty-map}
                                                            {:label :unconstrained-declaration :values {:computed "value"}
                                                             :board {:computed {:schema :any}}
                                                             :writes [:inline-result] :path [:computed] :detail :unconstrained-type}
                                                            {:label :unrepresentable-item :values {:computed {:records [(Object.)]}}
                                                             :writes [:inline-result] :path [:computed :records 0] :detail :unrepresentable-value}
                                                            {:label :missing-output :values {:computed "value"}
                                                             :writes [:missing-result] :path [:missing-result] :detail :missing-output-schema}]]
        (testing (name label)
          (let [n (count @requests)
                error (try
                        (sandbox/execute-llm-primitive "computed"
                          {:instruction "Return controlled result." :reads [:computed] :writes writes
                           :options {:use-function-calling? false :max-retries 0}}
                          {:provider :computed-contract-diagnostic :sandbox-vars values
                           :blackboard (merge {:inline-result {:schema :string}} board)})
                        nil
                        (catch clojure.lang.ExceptionInfo e e))
                data (ex-data error)]
            (swap! proof conj {:kind :diagnostic :case label :data data :calls-before n :calls-after (count @requests)})
            (is (some? error))
            (is (= :missing-inline-contract (:reason data)))
            (is (= path (:path data)))
            (is (= detail (:contract-reason data)))
            (is (= n (count @requests)) "Contract failure must precede any provider request"))))
      (finally (.stop server 0) (.shutdownNow pool)))))
