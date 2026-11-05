(ns ai.obney.orc.orc-service.execution-family-test
  "S9a ExecutionFamilyIsQueryable: every node execution beneath an execution,
   including the executions of delegated workflows, is retrievable through the
   public interface in order, each with its node, instruction, status, resolved
   inputs and outputs, failure and tool evidence."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.orc-service.core.value-log :as value-log]
            [ai.obney.orc.llm.interface :as llm]
            [litellm.router :as router]))

(defn one [{:keys [inputs]}] {:mid (str "one:" (:request inputs))})
(defn two [{:keys [inputs]}] {:answer (str "two:" (:mid inputs))})
(defn inner [{:keys [inputs]}] {:answer (str "inner:" (:mid inputs))})
(defn per-item [{:keys [inputs]}] {:result (str "item:" (:item inputs))})
(defn big [_] {:mid (apply str (repeat (* 200 1024) \x))})
(defn measure [{:keys [inputs]}] {:answer (str (count (:mid inputs)))})

(def ^:private bb {:request [:string {:description "Input"}]
                   :mid [:string {:description "Intermediate"}]
                   :answer [:string {:description "Final answer"}]
                   :items [:vector [:string {:description "An item"}]]
                   :item [:string {:description "Current item"}]
                   :result [:string {:description "Item result"}]
                   :results [:vector [:string {:description "Item results"}]]})

(defn- fq [sym] (str "ai.obney.orc.orc-service.execution-family-test/" sym))

(defn- build-child! [ctx]
  (sheet/build-workflow! ctx
    (sheet/workflow "ef-child"
      (sheet/blackboard bb)
      (sheet/code "inner" :fn (fq "inner") :reads [:mid] :writes [:answer]))))


(defn- oracle-mismatches
  "The slow path as oracle: for each entry, the public per-entry value-log
   functions applied to the same completion event."
  [ctx family]
  (let [tenant (:tenant-id ctx)]
    (into []
          (mapcat
           (fn [{:keys [tick-id event-id node-name] :as entry}]
             (let [events (value-log/read-tick-events ctx tenant tick-id)
                   completed (some #(when (and (= :sheet/node-execution-completed (:event/type %))
                                               (= event-id (:event/id %)))
                                      %)
                                   events)
                   expect {:inputs (value-log/resolve-reads ctx tenant tick-id completed)
                           :outputs (value-log/resolve-writes ctx tenant tick-id completed)
                           :rejected-outputs (not-empty (value-log/rejected-writes-for events completed))}
                   actual {:inputs (:inputs entry)
                           :outputs (:outputs entry)
                           :rejected-outputs (:rejected-outputs entry)}]
               (when (not= expect actual)
                 [{:node node-name :expected expect :actual actual}]))))
          family)))

(defn- family-of
  "get-execution-family, asserting every entry's resolved values equal the
   per-entry value-log oracle."
  [ctx trace-id & opts]
  (let [family (apply sheet/get-execution-family ctx trace-id opts)]
    (is (seq family) "the family is not empty")
    (is (empty? (oracle-mismatches ctx family))
        (pr-str (take 1 (oracle-mismatches ctx family))))
    family))

(defn- names [entries] (mapv :node-name entries))

(deftest the-family-lists-every-execution-in-completion-order-across-delegates
  (h/with-async-test-context [ctx]
    (let [child (build-child! ctx)
          parent (sheet/build-workflow! ctx
                   (sheet/workflow "ef-parent"
                     (sheet/blackboard bb)
                     (sheet/sequence "root"
                       (sheet/code "one" :fn (fq "one") :reads [:request] :writes [:mid])
                       (sheet/delegate "del" :target-sheet-id child
                         :reads [:mid] :writes [:answer]))))
          result (sheet/execute ctx parent {:request "hi"} :timeout-ms 60000)
          family (family-of ctx (:trace-id result))
          by-name (into {} (map (juxt :node-name identity)) family)]
      (is (= :success (:status result)) (pr-str result))
      (is (= ["one" "inner" "del" "root"] (names family))
          "every execution, in durable completion order, children before their parent")
      (testing "resolved input and output VALUES"
        (is (= {:request "hi"} (:inputs (by-name "one"))))
        (is (= {:mid "one:hi"} (:outputs (by-name "one"))))
        (is (= {:mid "one:hi"} (:inputs (by-name "inner"))))
        (is (= {:answer "inner:one:hi"} (:outputs (by-name "inner")))))
      (testing "identity, kind and status"
        (is (= :success (:status (by-name "one"))))
        (is (= :sequence (:node-type (by-name "root"))))
        (is (= :delegate (:node-type (by-name "del"))))
        (is (= (:id (some #(when (= "one" (:name %)) %) (sheet/get-nodes-for-sheet ctx parent)))
               (:node-id (by-name "one"))))
        (is (every? #(some? (:completed-at %)) family)))
      (testing "the delegated execution carries its parent tick"
        (is (= (:trace-id result) (:parent-tick-id (by-name "inner"))))
        (is (= (:trace-id result) (:tick-id (by-name "one"))))
        (is (not= (:trace-id result) (:tick-id (by-name "inner"))))
        (is (nil? (:parent-tick-id (by-name "one"))))))))

(deftest a-failed-leaf-carries-its-failure-kind-and-provider-evidence
  (h/with-async-test-context [ctx]
    (with-redefs [router/completion
                  (fn [& _]
                    {:id "gen-finish-error"
                     :model "deterministic-model"
                     :usage {:prompt_tokens 7 :completion_tokens 0 :total_tokens 7}
                     :choices [{:finish-reason :error
                                :native-finish-reason "MALFORMED_FUNCTION_CALL"
                                :message {:content nil}}]})]
      (let [parent (sheet/build-workflow! ctx
                     (sheet/workflow "ef-failure"
                       (sheet/blackboard bb)
                       (sheet/sequence "root"
                         (sheet/llm "ask" :instruction "Answer the request."
                           :reads [:request] :writes [:answer]
                           :options {:use-function-calling? true :max-retries 0 :retry-delay-ms 1}))))
            result (sheet/execute (assoc ctx :llm-provider :deterministic-provider)
                                  parent {:request "hi"} :timeout-ms 60000)
            family (family-of ctx (:trace-id result))
            ask (some #(when (= "ask" (:node-name %)) %) family)]
        (is (= :failure (:status result)))
        (is (= :failure (:status ask)))
        (is (= :provider-finish-error (get-in ask [:failure :kind])))
        (is (= "MALFORMED_FUNCTION_CALL" (get-in ask [:failure :provider-evidence :native-finish-reason])))
        (is (string? (get-in ask [:failure :message])) (pr-str (:failure ask)))
        (is (= "Answer the request." (:instruction ask)))))))

(deftest map-each-iterations-are-distinct-entries
  (h/with-async-test-context [ctx]
    (let [parent (sheet/build-workflow! ctx
                   (sheet/workflow "ef-map-each"
                     (sheet/blackboard bb)
                     (sheet/map-each "each" :from :items :as :item :into :results
                       (sheet/code "per-item" :fn (fq "per-item") :reads [:item] :writes [:result]))))
          result (sheet/execute ctx parent {:items ["a" "b" "c"]} :timeout-ms 60000)
          family (family-of ctx (:trace-id result))
          iterations (filterv #(= "per-item" (:node-name %)) family)]
      (is (= :success (:status result)) (pr-str result))
      (is (= 3 (count iterations)))
      (is (= 3 (count (distinct (map :exec-context iterations)))) "distinct exec-contexts")
      (is (= #{"item:a" "item:b" "item:c"} (set (map #(get-in % [:outputs :result]) iterations))))
      (is (= #{{:item "a"} {:item "b"} {:item "c"}} (set (map :inputs iterations)))))))

(deftest node-id-scopes-the-family-to-the-executions-beneath-one-composite
  (h/with-async-test-context [ctx]
    (let [child (build-child! ctx)
          parent (sheet/build-workflow! ctx
                   (sheet/workflow "ef-scope"
                     (sheet/blackboard bb)
                     (sheet/sequence "root"
                       (sheet/code "one" :fn (fq "one") :reads [:request] :writes [:mid])
                       (sheet/sequence "sub"
                         (sheet/delegate "del" :target-sheet-id child
                           :reads [:mid] :writes [:answer])
                         (sheet/code "two" :fn (fq "two") :reads [:mid] :writes [:answer])))))
          result (sheet/execute ctx parent {:request "hi"} :timeout-ms 60000)
          node-id (fn [n] (:id (some #(when (= n (:name %)) %) (sheet/get-nodes-for-sheet ctx parent))))
          scoped (fn [n] (names (family-of ctx (:trace-id result) {:node-id (node-id n)})))]
      (is (= :success (:status result)) (pr-str result))
      (is (= ["inner" "del" "two"] (scoped "sub")) "descendants incl. the delegate's child tick, not sub itself")
      (is (= ["inner"] (scoped "del")) "a delegate's family is its child tick's executions")
      (is (= ["one" "inner" "del" "two" "sub"] (scoped "root"))))))

(deftest large-values-come-back-verbatim
  (h/with-async-test-context [ctx]
    (let [parent (sheet/build-workflow! ctx
                   (sheet/workflow "ef-big"
                     (sheet/blackboard bb)
                     (sheet/sequence "root"
                       (sheet/code "big" :fn (fq "big") :reads [] :writes [:mid])
                       (sheet/code "measure" :fn (fq "measure") :reads [:mid] :writes [:answer]))))
          result (sheet/execute ctx parent {} :timeout-ms 60000)
          family (family-of ctx (:trace-id result))
          by-name (into {} (map (juxt :node-name identity)) family)
          expected (apply str (repeat (* 200 1024) \x))]
      (is (= :success (:status result)) (pr-str result))
      (is (= expected (get-in by-name ["big" :outputs :mid])) "output verbatim, no truncation")
      (is (= expected (get-in by-name ["measure" :inputs :mid])) "input verbatim, no truncation")
      (is (= (str (count expected)) (get-in by-name ["measure" :outputs :answer]))))))

(defn- charge-tool [tool args & _]
  {:charged (str tool ":" (get args "amount" (:amount args)))})

(defn charging-child-leaf [{:keys [call-tool-fn]}]
  {:out (:charged (call-tool-fn "charge" {"amount" 5}))})

(deftest tool-receipts-and-generated-child-executions-are-in-the-family
  (h/with-async-test-context [ctx {:context {:llm-provider :test :call-tool-fn charge-tool}}]
    (let [parent (sheet/build-workflow! ctx
                   (sheet/workflow "ef-receipts"
                     (sheet/blackboard {:question :string :out :string})
                     (sheet/repl-researcher "researcher"
                       :instruction "Charge via a generated child."
                       :reads [:question] :writes [:out]
                       :mcp-tools ["charge"]
                       :tool-contracts {"charge" {:checkpoint-safe? true}}
                       :rlm {:checkpointed? true :recursive? false
                             :timeouts {:provider-ms 5000 :iteration-ms 20000 :campaign-ms 60000}}
                       :max-iterations 2)))]
      (with-redefs [llm/predict
                    (fn [& _]
                      {:outputs {:code (str "(emit-tree! [:sequence "
                                            "[:code {:fn \"" (fq "charging-child-leaf") "\" "
                                            ":reads [] :writes [:out]}] "
                                            "[:final {:keys [:out]}]])")}
                       :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})]
        (let [result (sheet/execute ctx parent {:question "q"} :timeout-ms 60000)
              family (family-of ctx (:trace-id result))
              receipts (into [] (mapcat :tool-receipts) family)]
          (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
          (is (some #(= (:trace-id result) (:parent-tick-id %)) family)
              "the generated child tree's executions are part of the family")
          (is (= 1 (count receipts)) (pr-str receipts))
          (is (= :completed (:status (first receipts))))
          (is (= {:charged "charge:5"} (:result (first receipts)))))))))

(deftest event-store-reads-are-bounded-by-ticks-not-entries
  (h/with-async-test-context [ctx]
    (let [parent (sheet/build-workflow! ctx
                   (sheet/workflow "ef-reads"
                     (sheet/blackboard bb)
                     (sheet/map-each "each" :from :items :as :item :into :results
                       (sheet/code "per-item" :fn (fq "per-item") :reads [:item] :writes [:result]))))
          items (mapv #(str "i" %) (range 200))
          result (sheet/execute ctx parent {:items items} :timeout-ms 120000)
          caller (Thread/currentThread)
          reads (atom 0)
          real-read es/read
          family (with-redefs [es/read (fn [& args]
                                         (when (identical? caller (Thread/currentThread))
                                           (swap! reads inc))
                                         (apply real-read args))]
                   (sheet/get-execution-family ctx (:trace-id result)))
          iterations (filterv #(= "per-item" (:node-name %)) family)
          ticks (count (distinct (map :tick-id family)))]
      (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
      (is (= 200 (count iterations)))
      (is (= (set items) (into #{} (map #(get-in % [:inputs :item])) iterations))
          "every iteration still resolves its own input")
      (is (= (set (map #(str "item:" %) items))
             (into #{} (map #(get-in % [:outputs :result])) iterations)))
      (is (pos? @reads) "the instrumentation observed the reads")
      (is (<= @reads (* 10 ticks))
          (str @reads " event-store reads for " ticks " tick(s) and " (count family) " entries")))))
