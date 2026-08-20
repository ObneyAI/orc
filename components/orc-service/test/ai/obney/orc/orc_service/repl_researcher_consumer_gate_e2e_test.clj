(ns ai.obney.orc.orc-service.repl-researcher-consumer-gate-e2e-test
  "DET-E2E-155/156: public durable consumer gates and typed RLM finalization."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def gated-calls (atom []))
(def base-calls (atom []))
(def downstream-calls (atom 0))

(defn consumer-tool-caller-builder
  [blackboard context]
  (let [built-context (or (get-in blackboard [:tool-context :value])
                          (:tool-context context))]
    (fn
      ([tool args]
       (swap! gated-calls conj {:tool tool :args args
                                :tool-context built-context
                                :tick-id (:tick-id context)})
       {"sources" ["gated"]})
      ([tool args tool-context]
       (swap! gated-calls conj {:tool tool :args args
                                :tool-context (or tool-context built-context)
                                :tick-id (:tick-id context)})
       {"sources" ["gated"]}))))

(defn generated-tool-leaf
  [{:keys [call-tool-fn]}]
  (let [result (call-tool-fn "academic-search" {"query" "phase-2"})]
    {:research-draft {:sources (get result "sources")}}))

(defn record-downstream
  [_]
  (swap! downstream-calls inc)
  {:accepted true})

(def ^:private builder-fqn
  "ai.obney.orc.orc-service.repl-researcher-consumer-gate-e2e-test/consumer-tool-caller-builder")

(def ^:private generated-leaf-fqn
  "ai.obney.orc.orc-service.repl-researcher-consumer-gate-e2e-test/generated-tool-leaf")

(def ^:private downstream-fqn
  "ai.obney.orc.orc-service.repl-researcher-consumer-gate-e2e-test/record-downstream")

(def ^:private draft-schema
  [:map [:sources [:vector :string]]])

(defn- events
  [ctx]
  (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)})))

(defn- reset-recorders!
  []
  (reset! gated-calls [])
  (reset! base-calls [])
  (reset! downstream-calls 0))

(deftest det-e2e-155-consumer-gate-survives-public-durable-boundary
  (testing "public DSL, event projection, replay, Phase 1 and Phase 2 retain the consumer gate"
    (reset-recorders!)
    (h/with-async-test-context
      [ctx {:context {:llm-provider :test
                      :call-tool-fn (fn [tool args & [tool-context]]
                                      (swap! base-calls conj {:tool tool :args args
                                                              :tool-context tool-context})
                                      {"error" "ungated"})}}]
      (let [definition
            (sheet/workflow "det-e2e-155-consumer-gate"
              (sheet/blackboard {:question :string
                                 :tool-context [:map [:request-id :string]]
                                 :research-draft draft-schema})
              (sheet/repl-researcher "adaptive-research"
                :instruction "Use academic-search and return a research draft."
                :reads [:question :tool-context]
                :writes [:research-draft]
                :mcp-tools ["academic-search"]
                :tool-caller-fn builder-fqn
                :rlm {:recursive? false}
                :max-iterations 3))
            sheet-id (sheet/build-workflow! ctx definition)
            node (some #(when (= "adaptive-research" (:name %)) %)
                       (sheet/get-nodes-for-sheet ctx sheet-id))
            config-event (some #(when (= :sheet/repl-researcher-config-set
                                         (:event/type %)) %)
                               (events ctx))
            replayed-node (get (reduce rm/nodes* {} (events ctx)) (:id node))]
        (is (= builder-fqn (:tool-caller-fn node)))
        (is (= builder-fqn (:tool-caller-fn config-event)))
        (is (= builder-fqn (:tool-caller-fn replayed-node)))
        (with-redefs [llm/predict
                      (fn [_ _ inputs _]
                        (let [history (str (:history inputs))]
                          (if (str/includes? history "phase-1")
                            {:outputs {:code (str "(emit-tree! "
                                                  "[:sequence "
                                                  "[:code {:fn \"" generated-leaf-fqn "\" "
                                                  ":reads [] :writes [:research-draft]}] "
                                                  "[:final {:keys [:research-draft]}]])")}
                             :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}
                            {:outputs {:code "(do (academic-search {\"query\" \"phase-1\"}) (println \"phase-1\"))"}
                             :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})))]
          (let [tool-context {:request-id "REQ-155"}
                result (sheet/execute (assoc ctx :tool-context tool-context)
                                      sheet-id {:question "research"
                                                :tool-context tool-context}
                                      :timeout-ms 30000)]
            (is (= :success (:status result)) (pr-str result))
            (is (= #{"phase-1" "phase-2"}
                   (set (map #(get-in % [:args "query"]) @gated-calls))))
            (is (every? #(= tool-context (:tool-context %)) @gated-calls))
            (is (empty? @base-calls) "the ungated base caller is never invoked")))))))

(deftest det-e2e-156-invalid-final-value-fails-before-durable-write
  (testing "schema-invalid final! output fails the researcher and prevents downstream execution"
    (reset-recorders!)
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [definition
            (sheet/workflow "det-e2e-156-typed-final"
              (sheet/blackboard {:question :string
                                 :research-draft draft-schema
                                 :accepted :boolean})
              (sheet/sequence "main"
                (sheet/repl-researcher "typed-researcher"
                  :instruction "Return the draft."
                  :reads [:question]
                  :writes [:research-draft]
                  :rlm {:recursive? false})
                (sheet/code "must-not-run"
                  :fn downstream-fqn
                  :reads [:research-draft]
                  :writes [:accepted])))
            sheet-id (sheet/build-workflow! ctx definition)]
        (with-redefs [llm/predict
                      (fn [_ _ _ _]
                        {:outputs {:code "(final! {:research-draft \"not-a-map\"})"}
                         :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
          (let [result (sheet/execute ctx sheet-id {:question "research"}
                                      :timeout-ms 30000)
                tick-id (:trace-id result)
                tick-events (filter #(= tick-id (:tick-id %)) (events ctx))
                researcher-failure (some #(when (and (= :sheet/node-execution-completed
                                                        (:event/type %))
                                                     (= :repl-researcher (:node-type %))
                                                     (= :failure (:status %))) %)
                                         tick-events)
                rejected (some #(when (and (= :sheet/execution-value-rejected
                                               (:event/type %))
                                            (= :research-draft (:key %))) %)
                               tick-events)]
            (is (= :failure (:status result)) (pr-str result))
            (is (str/includes? (:error researcher-failure) "Blackboard schema validation failed"))
            (is (str/includes? (:error researcher-failure) ":research-draft"))
            (is (= "not-a-map" (:value rejected)))
            (is (not-any? #(and (= :sheet/execution-value-written (:event/type %))
                                (= :research-draft (:key %)))
                          tick-events))
            (is (zero? @downstream-calls))
            (let [replayed-tick (get (reduce rm/ticks* {} (events ctx)) tick-id)]
              (is (= :completed (:status replayed-tick)))
              (is (= :failure (:root-status replayed-tick))))))))))

(deftest det-e2e-156-valid-final-value-crosses-schema-boundary
  (testing "a schema-valid final! value completes and becomes canonical"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [definition
            (sheet/workflow "det-e2e-156-valid-typed-final"
              (sheet/blackboard {:question :string
                                 :research-draft draft-schema})
              (sheet/repl-researcher "typed-researcher"
                :instruction "Return the draft."
                :reads [:question]
                :writes [:research-draft]
                :rlm {:recursive? false}))
            sheet-id (sheet/build-workflow! ctx definition)]
        (with-redefs [llm/predict
                      (fn [_ _ _ _]
                        {:outputs {:code "(final! {:research-draft {:sources [\"verified\"]}})"}
                         :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
          (let [result (sheet/execute ctx sheet-id {:question "research"}
                                      :timeout-ms 30000)]
            (is (= :success (:status result)) (pr-str result))
            (is (= {:sources ["verified"]}
                   (get-in result [:outputs :research-draft])))))))))
