(ns ai.obney.orc.orc-service.declared-string-keys-test
  "A map key the blackboard contract declares as a string stays a string; keys
   the contract leaves to convention are read as keywords (llm.allium)."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.core.value-log :as value-log]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [litellm.router :as router]))

(defn probs [_] {:out {"0" 0.41 "1" 0.59}})
(defn probs-map-of [_] {:out {"0" 0.41 "1" 0.59}})
(defn plain-string-keyed [_] {:out {"a" 1}})
(defn mixed [_] {:out {"a" 1 "b" 2}})
(defn nested-vec [_] {:out [{"0" 0.1 "1" 0.9} {"0" 0.5 "1" 0.5}]})
(defn nested-in-keyword-map [_] {:out {"scores" {"0" 1.0} "label" "x"}})

(defn keyword-keyed [_] {:out {:0 0.41 :1 0.59}})

(defn- fq [n] (str "ai.obney.orc.orc-service.declared-string-keys-test/" n))

(defn- run [fn-name schema]
  (h/with-async-test-context [ctx]
    (let [definition (sheet/workflow (str "string-keys-" fn-name)
                       (sheet/blackboard {:out schema})
                       (sheet/sequence "main"
                         (sheet/code "produce" :fn (fq fn-name) :writes [:out])))
          sheet-id (sheet/build-workflow! ctx definition)
          result (sheet/execute ctx sheet-id {})
          written (filter #(and (= :sheet/execution-value-written (:event/type %))
                                (= :out (:key %)))
                          (h/read-all-events ctx))
          durable (mapv #(value-log/resolve-source
                          ctx (:tenant-id ctx)
                          {:tick-id (:tick-id %) :event-id (:event/id %)})
                        written)]
      {:result result :durable durable})))

(deftest declared-string-keyed-map-keeps-string-keys
  (let [{:keys [result durable]} (run "probs" [:map ["0" :double] ["1" :double]])]
    (is (= :success (:status result)) (pr-str (:error result)))
    (is (= {"0" 0.41 "1" 0.59} (get-in result [:outputs :out])))
    (is (= [{"0" 0.41 "1" 0.59}] durable))))

(deftest map-of-string-keeps-string-keys
  (let [{:keys [result durable]} (run "probs-map-of" [:map-of :string :double])]
    (is (= :success (:status result)) (pr-str (:error result)))
    (is (= {"0" 0.41 "1" 0.59} (get-in result [:outputs :out])))
    (is (= [{"0" 0.41 "1" 0.59}] durable))))

(deftest keyword-declared-maps-still-keywordize
  (testing "[:map [:a :int]] given {\"a\" 1} reads as keyword"
    (let [{:keys [result]} (run "plain-string-keyed" [:map [:a :int]])]
      (is (= :success (:status result)) (pr-str (:error result)))
      (is (= {:a 1} (get-in result [:outputs :out])))))
  (testing "mixed keyword and declared-string keys"
    (let [{:keys [result]} (run "mixed" [:map [:a :int] ["b" :int]])]
      (is (= :success (:status result)) (pr-str (:error result)))
      (is (= {:a 1 "b" 2} (get-in result [:outputs :out]))))))

(deftest string-key-map-nested-in-vector
  (let [{:keys [result]} (run "nested-vec" [:vector [:map ["0" :double] ["1" :double]]])]
    (is (= :success (:status result)) (pr-str (:error result)))
    (is (= [{"0" 0.1 "1" 0.9} {"0" 0.5 "1" 0.5}] (get-in result [:outputs :out])))))

(deftest string-key-map-nested-in-keyword-map
  (let [{:keys [result]} (run "nested-in-keyword-map"
                              [:map [:scores [:map ["0" :double]]] [:label :string]])]
    (is (= :success (:status result)) (pr-str (:error result)))
    (is (= {:scores {"0" 1.0} :label "x"} (get-in result [:outputs :out])))))

(deftest code-leaf-keyword-arrived-declared-string-keys-are-recovered
  (let [{:keys [result durable]} (run "keyword-keyed" [:map ["0" :double] ["1" :double]])]
    (is (= :success (:status result)) (pr-str (:error result)))
    (is (= {"0" 0.41 "1" 0.59} (get-in result [:outputs :out])))
    (is (= [{"0" 0.41 "1" 0.59}] durable))))

(def ^:private llm-cases
  "The top-level [:map ...] output is flattened into one provider field per
   key, so the provider answers {\"0\":..,\"1\":..}; nested maps arrive whole."
  [{:label "flattened top-level"
    :schema [:map ["0" :double] ["1" :double]]
    :json "{\"0\":0.41,\"1\":0.59}"
    :marker "[[ ## 0 ## ]]\n0.41\n[[ ## 1 ## ]]\n0.59"
    :expected {"0" 0.41 "1" 0.59}}
   {:label "nested string-key map"
    :schema [:map [:inner [:map ["0" :double] ["1" :double]]]]
    :json "{\"inner\":{\"0\":0.41,\"1\":0.59}}"
    :marker "[[ ## inner ## ]]\n{\"0\":0.41,\"1\":0.59}"
    :expected {:inner {"0" 0.41 "1" 0.59}}}
   {:label "map-of string"
    :schema [:map-of :string :double]
    :json "{\"probs\":{\"0\":0.41,\"1\":0.59}}"
    :marker "[[ ## probs ## ]]\n{\"0\":0.41,\"1\":0.59}"
    :expected {"0" 0.41 "1" 0.59}}])

(deftest llm-leaf-keeps-declared-string-keys-through-a-public-tick
  (doseq [{:keys [label schema json marker expected]} llm-cases
          function-calling? [true false]]
    (testing (str label ", function-calling? " function-calling?)
      (h/with-async-test-context [ctx]
        (with-redefs
         [router/supports-function-calling? (constantly function-calling?)
          router/completion
          (fn [_provider _request]
            {:id "resp" :model "deterministic-model"
             :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}
             :choices
             [{:finish-reason (if function-calling? "tool_calls" "stop")
               :message
               (if function-calling?
                 {:tool-calls [{:function {:name "submit_response" :arguments json}}]}
                 {:content marker})}]})]
          (let [sheet-id (sheet/build-workflow!
                          ctx
                          (sheet/workflow (str "string-keys-llm-" (hash [label function-calling?]))
                            (sheet/blackboard {:probs schema})
                            (sheet/llm "decide"
                              :instruction "Return probabilities."
                              :writes [:probs]
                              :options {:use-function-calling? function-calling?
                                        :max-retries 0 :retry-delay-ms 1})))
                result (sheet/execute (assoc ctx :llm-provider :deterministic-provider)
                                      sheet-id {})]
            (is (= :success (:status result)) (pr-str (:error result)))
            (is (= expected (get-in result [:outputs :probs])))))))))
