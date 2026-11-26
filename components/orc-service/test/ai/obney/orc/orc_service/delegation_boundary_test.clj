(ns ai.obney.orc.orc-service.delegation-boundary-test
  "Generated from contract DelegationBoundary (specs/orc-service.allium):
   FamilyBudgetIsShared, DelegateToolContextCrosses,
   DelegateInputIdentityIsExact, FamilyUsageIsQueryable.

   Public DSL → build → execute with only the provider seam injected (honouring
   the predictor's bare/metadata return shapes)."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(defn- fake-predict [calls]
  (fn [_provider module _inputs options]
    (swap! calls inc)
    (let [outputs (into {} (map (fn [{:keys [name]}] [name "x"])) (:outputs module))]
      (if (:with-metadata? options)
        {:outputs outputs
         :usage {:prompt_tokens 4 :completion_tokens 2 :total_tokens 6}
         :model "deterministic"
         :raw-response (pr-str outputs)}
        outputs))))

(defn- two-call-child [ctx workflow-name]
  (sheet/build-workflow!
   ctx (sheet/workflow workflow-name
         (sheet/blackboard {:a :string :b :string})
         (sheet/sequence "both"
           (sheet/llm "first" :instruction "Say x." :writes [:a])
           (sheet/llm "second" :instruction "Say x." :writes [:b])))))

;; ---------------------------------------------------------------------------
;; FamilyBudgetIsShared
;; ---------------------------------------------------------------------------

(deftest ordinary-delegate-child-draws-on-the-family-budget
  (h/with-async-test-context [ctx]
    (let [calls (atom 0)]
      (with-redefs [llm/predict (fake-predict calls)]
        (let [child-id (two-call-child ctx "family-budget-child")
              parent-id (sheet/build-workflow!
                         ctx (sheet/workflow "family-budget-parent"
                               (sheet/blackboard {:a :string :b :string})
                               (sheet/delegate "child" :target-sheet-id child-id
                                 :writes [:a :b])))
              result (sheet/execute ctx parent-id {} :llm-call-budget 1 :timeout-ms 20000)]
          (is (= 1 @calls) "no provider invocation begins once the family budget is spent")
          (is (not= :success (:status result))))))))

;; ---------------------------------------------------------------------------
;; DelegateToolContextCrosses
;; ---------------------------------------------------------------------------

(def seen-tool-contexts (atom []))

(defn recording-gate [_blackboard context]
  (swap! seen-tool-contexts conj (:tool-context context))
  (fn [& _] {:ok true}))

(defn call-a-tool [{:keys [call-tool-fn]}]
  {:out (str (:ok (call-tool-fn "probe" {})))})

(deftest parent-tool-context-reaches-the-child
  (reset! seen-tool-contexts [])
  (h/with-async-test-context [ctx]
    (let [child-id (sheet/build-workflow!
                    ctx (sheet/workflow "tool-context-child"
                          (sheet/blackboard {:out :string})
                          (sheet/code "use-tool"
                            :fn "ai.obney.orc.orc-service.delegation-boundary-test/call-a-tool"
                            :tool-caller-fn "ai.obney.orc.orc-service.delegation-boundary-test/recording-gate"
                            :writes [:out])))
          parent-id (sheet/build-workflow!
                     ctx (sheet/workflow "tool-context-parent"
                           (sheet/blackboard {:out :string})
                           (sheet/delegate "child" :target-sheet-id child-id :writes [:out])))
          tool-context {:request-id "REQ-DELEGATE-1"}
          result (sheet/execute (assoc ctx :tool-context tool-context) parent-id {}
                                :timeout-ms 20000)]
      (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
      (is (= [tool-context] @seen-tool-contexts)
          "the child's gate is built with the parent's consumer tool context"))))

;; ---------------------------------------------------------------------------
;; DelegateInputIdentityIsExact
;; ---------------------------------------------------------------------------

(deftest colliding-parent-keys-are-rejected-at-build
  (h/with-async-test-context [ctx]
    (let [child-id (sheet/build-workflow!
                    ctx (sheet/workflow "identity-child"
                          (sheet/blackboard {:id :string :out :string})
                          (sheet/code "echo"
                            :fn "ai.obney.orc.orc-service.delegation-boundary-test/call-a-tool"
                            :reads [:id] :writes [:out])))
          outcome (try
                    (let [r (sheet/build-workflow!
                             ctx (sheet/workflow "identity-parent"
                                   (sheet/blackboard {:left/id :string :right/id :string
                                                      :out :string})
                                   (sheet/delegate "child" :target-sheet-id child-id
                                     :reads [:left/id :right/id] :writes [:out])))]
                      (if (uuid? r) ::built ::rejected))
                    (catch Exception _ ::rejected))]
      (is (= ::rejected outcome)
          ":left/id and :right/id would both supply the child's :id"))))

;; ---------------------------------------------------------------------------
;; FamilyUsageIsQueryable
;; ---------------------------------------------------------------------------

(deftest family-usage-includes-descendants
  (h/with-async-test-context [ctx]
    (let [calls (atom 0)]
      (with-redefs [llm/predict (fake-predict calls)]
        (let [child-id (sheet/build-workflow!
                        ctx (sheet/workflow "usage-child"
                              (sheet/blackboard {:b :string})
                              (sheet/llm "child-llm" :instruction "Say x." :writes [:b])))
              parent-id (sheet/build-workflow!
                         ctx (sheet/workflow "usage-parent"
                               (sheet/blackboard {:a :string :b :string})
                               (sheet/sequence "main"
                                 (sheet/llm "root-llm" :instruction "Say x." :writes [:a])
                                 (sheet/delegate "child" :target-sheet-id child-id :writes [:b]))))
              result (sheet/execute ctx parent-id {} :timeout-ms 20000)]
          (is (= :success (:status result)))
          (is (h/settle-until!
               #(= 12 (get-in (sheet/get-family-usage ctx (:trace-id result)) [:total-tokens]))
               :timeout-ms 5000)
              (pr-str (sheet/get-family-usage ctx (:trace-id result)))))))))

(deftest family-usage-counts-each-provider-call-once-for-researcher-families
  (testing "a researcher's own usage may already include its generated children; they are not added twice"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [calls (atom [])
            definition (sheet/workflow "usage-researcher-family"
                         (sheet/blackboard {:question :string :b :string})
                         (sheet/repl-researcher "researcher"
                           :instruction "Delegate to a generated child."
                           :reads [:question] :writes [:b]
                           :rlm {:recursive? false}
                           :max-iterations 2))
            sheet-id (sheet/build-workflow! ctx definition)]
        (with-redefs [llm/predict
                      (fn [_provider module _inputs options]
                        (swap! calls conj (mapv :name (:outputs module)))
                        (if (some #{:code} (map :name (:outputs module)))
                          {:outputs {:code (str "(emit-tree! [:sequence "
                                                "[:llm {:instruction \"Say x.\" :reads [] :writes [:b]}] "
                                                "[:final {:keys [:b]}]])")}
                           :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}
                          (let [outputs (into {} (map (fn [{:keys [name]}] [name "x"])) (:outputs module))]
                            (if (:with-metadata? options)
                              {:outputs outputs
                               :usage {:prompt_tokens 4 :completion_tokens 2 :total_tokens 6}
                               :model "deterministic" :raw-response (pr-str outputs)}
                              outputs))))]
          (let [result (sheet/execute ctx sheet-id {:question "q"} :timeout-ms 30000)
                expected (+ (* 2 (count (filter #(some #{:code} %) @calls)))
                            (* 6 (count (remove #(some #{:code} %) @calls))))]
            (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
            (is (h/settle-until!
                 #(= expected (:total-tokens (sheet/get-family-usage ctx (:trace-id result))))
                 :timeout-ms 5000)
                (str "expected " expected " from " (pr-str @calls) " got "
                     (pr-str (sheet/get-family-usage ctx (:trace-id result)))))))))))

(defn- iterating-researcher-family-usage
  "Run a researcher whose first Phase-1 call emits a child tree and whose second
   finalises, with `rlm` options; return the family usage plus the expected total
   derived from every provider call the fake made (Phase-1 code = 2 tokens,
   child leaf = 6 tokens)."
  [ctx rlm]
  (let [calls (atom [])
        phase1 (atom 0)
        definition (sheet/workflow "usage-researcher-iterating"
                     (sheet/blackboard {:question :string :b :string})
                     (sheet/repl-researcher "researcher"
                       :instruction "Delegate to a generated child, then finish."
                       :reads [:question] :writes [:b]
                       :rlm rlm
                       :max-iterations 4))
        sheet-id (sheet/build-workflow! ctx definition)]
    (with-redefs [llm/predict
                  (fn [_provider module _inputs options]
                    (swap! calls conj (mapv :name (:outputs module)))
                    (if (some #{:code} (map :name (:outputs module)))
                      {:outputs {:code (if (= 1 (swap! phase1 inc))
                                         (str "(emit-tree! [:sequence "
                                              "[:llm {:instruction \"Say x.\" :reads [] :writes [:b]}] "
                                              "[:final {:keys [:b]}]])")
                                         "(final! {:b \"x\"})")}
                       :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}}
                      (let [outputs (into {} (map (fn [{:keys [name]}] [name "x"])) (:outputs module))]
                        (if (:with-metadata? options)
                          {:outputs outputs
                           :usage {:prompt_tokens 4 :completion_tokens 2 :total_tokens 6}
                           :model "deterministic" :raw-response (pr-str outputs)}
                          outputs))))]
      (let [result (sheet/execute ctx sheet-id {:question "q"} :timeout-ms 30000)
            expected (+ (* 2 (count (filter #(some #{:code} %) @calls)))
                        (* 6 (count (remove #(some #{:code} %) @calls))))]
        {:result result
         :calls @calls
         :expected expected
         :settled? (h/settle-until!
                    #(= expected (:total-tokens (sheet/get-family-usage ctx (:trace-id result))))
                    :timeout-ms 5000)
         :family (sheet/get-family-usage ctx (:trace-id result))}))))

(deftest family-usage-counts-each-provider-call-once-for-recursive-researcher
  (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
    (let [{:keys [result calls expected settled? family]}
          (iterating-researcher-family-usage ctx {:recursive? true})]
      (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
      (is (some #(not (some #{:code} %)) calls) "a child tree leaf must have made a provider call")
      (is settled? (str "expected " expected " from " (pr-str calls) " got " (pr-str family))))))

(deftest family-usage-counts-each-provider-call-once-for-checkpointed-campaign
  (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
    (let [{:keys [result calls expected settled? family]}
          (iterating-researcher-family-usage ctx {:checkpointed? true})]
      (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
      (is (some #(not (some #{:code} %)) calls) "a child tree leaf must have made a provider call")
      (is settled? (str "expected " expected " from " (pr-str calls) " got " (pr-str family))))))
