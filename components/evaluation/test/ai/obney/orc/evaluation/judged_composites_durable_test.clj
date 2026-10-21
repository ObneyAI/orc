(ns ai.obney.orc.evaluation.judged-composites-durable-test
  "S9c: a composite that carries judges is a durable boundary. A tree of plain
   sequence/fallback/condition/leaf nodes runs on the ephemeral fast path, which
   writes no composite completion - so a judge on such a composite (or on the
   root) would never fire. A judged composite makes the run durable; a tree
   with no judged composite keeps the fast path. Published runs are judged
   through the source (draft) node and show their judges the definition that
   actually ran. Driven through the public flow: build -> execute -> assessments."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.evaluation.interface :as evaluation]
            [ai.obney.orc.ontology.interface]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.grain.event-store-v3.interface :as es]))

(def ^:private judge-calls
  "What the evaluation runtime handed each judge, by the judge's name."
  (atom {}))

(defn step [{:keys [inputs]}] {:mid (str "mid:" (:request inputs))})
(defn finish [{:keys [inputs]}] {:answer (str "answer:" (:mid inputs))})
(defn boom [_] (throw (ex-info "branch failed" {})))

(defn record-root [{:keys [inputs]}]
  (swap! judge-calls update :root (fnil conj []) inputs)
  {:score 0.75 :feedback "recorded"})
(defn record-inner [{:keys [inputs]}]
  (swap! judge-calls update :inner (fnil conj []) inputs)
  {:score 0.75 :feedback "recorded"})
(defn record-fallback [{:keys [inputs]}]
  (swap! judge-calls update :fallback (fnil conj []) inputs)
  {:score 0.75 :feedback "recorded"})
(defn record-leaf [{:keys [inputs]}]
  (swap! judge-calls update :leaf (fnil conj []) inputs)
  {:score 0.75 :feedback "recorded"})

(defn record-published [{:keys [inputs]}]
  (swap! judge-calls update :published (fnil conj []) inputs)
  {:score 0.75 :feedback "recorded"})

(def ^:private io [:map-of :keyword [:any {:description "any value"}]])

(defn- judge-workflow [record-fn]
  (sheet/workflow (str "s9c-judge-" (random-uuid))
    (sheet/blackboard {:host-inputs io
                       :host-outputs io
                       :host-instruction [:string {:description "Host instruction"}]
                       :original-task io
                       :score :double
                       :feedback [:string {:description "Feedback"}]})
    (sheet/code "record" :fn (str "ai.obney.orc.evaluation.judged-composites-durable-test/" record-fn)
      :reads [:host-inputs :host-outputs :host-instruction :original-task]
      :writes [:score :feedback])))

(def ^:private bb {:request [:string {:description "Input"}]
                   :mid [:string {:description "Intermediate"}]
                   :answer [:string {:description "Final"}]})

(defn- fq [n] (str "ai.obney.orc.evaluation.judged-composites-durable-test/" n))

(defn- node-id [ctx sheet-id n]
  (:id (some #(when (= n (:name %)) %) (sheet/get-nodes-for-sheet ctx sheet-id))))

(defn- assessments-of
  "The settled assessments of the run `tick-id`, once `n` of them are no longer pending."
  [ctx tick-id n]
  (let [ours #(filterv (fn [a] (and (= tick-id (:tick-id a)) (not= :pending (:status a))))
                       (evaluation/get-assessments ctx {}))]
    (is (h/settle-until! #(>= (count (ours)) n) :timeout-ms 60000)
        (str "expected " n " settled assessments for the run"))
    (ours)))

(defn- events-of [ctx tick-id types]
  (filterv #(contains? types (:event/type %))
           (into [] (es/read (:event-store ctx)
                             {:tenant-id (:tenant-id ctx)
                              :tags #{[:tick tick-id]}}))))

(deftest a-judge-on-the-root-sequence-of-plain-leaves-assesses-it
  (h/with-async-test-context [ctx]
    (reset! judge-calls {})
    (let [judge (sheet/build-workflow! ctx (judge-workflow "record-root"))
          sheet-id (sheet/build-workflow! ctx
                     (sheet/workflow "s9c-root"
                       (sheet/blackboard bb)
                       (sheet/judges {:root-judge {:type :custom :sheet-id judge}})
                       (sheet/sequence "pipeline" :judges ["root-judge"]
                         (sheet/code "step" :fn (fq "step") :reads [:request] :writes [:mid])
                         (sheet/code "finish" :fn (fq "finish") :reads [:mid] :writes [:answer]))))
          result (sheet/execute ctx sheet-id {:request "hello"} :timeout-ms 60000)
          assessed (assessments-of ctx (:trace-id result) 1)]
      (is (= :success (:status result)) (pr-str result))
      (is (= 1 (count assessed)) "the root sequence's judge assessed its completion")
      (is (= (node-id ctx sheet-id "pipeline") (:node-id (first assessed))))
      (is (= :scored (:status (first assessed))) (pr-str (first assessed)))
      (is (= 0.75 (:score (first assessed))))
      (is (= {:answer "answer:mid:hello"} (:host-outputs (first (:root @judge-calls))))
          "the judge was shown the sequence's outputs as evidence"))))

(deftest a-judge-on-a-nested-sequence-assesses-it
  (h/with-async-test-context [ctx]
    (reset! judge-calls {})
    (let [judge (sheet/build-workflow! ctx (judge-workflow "record-inner"))
          sheet-id (sheet/build-workflow! ctx
                     (sheet/workflow "s9c-nested"
                       (sheet/blackboard bb)
                       (sheet/judges {:inner-judge {:type :custom :sheet-id judge}})
                       (sheet/sequence "outer"
                         (sheet/code "step" :fn (fq "step") :reads [:request] :writes [:mid])
                         (sheet/sequence "inner" :judges ["inner-judge"]
                           (sheet/code "finish" :fn (fq "finish") :reads [:mid] :writes [:answer])))))
          result (sheet/execute ctx sheet-id {:request "hello"} :timeout-ms 60000)
          assessed (assessments-of ctx (:trace-id result) 1)]
      (is (= :success (:status result)) (pr-str result))
      (is (= [(node-id ctx sheet-id "inner")] (mapv :node-id assessed))
          "only the nested sequence is assessed")
      (is (= :scored (:status (first assessed))))
      (is (= {:answer "answer:mid:hello"} (:host-outputs (first (:inner @judge-calls))))))))

(deftest a-judge-on-a-fallback-assesses-it
  (h/with-async-test-context [ctx]
    (reset! judge-calls {})
    (let [judge (sheet/build-workflow! ctx (judge-workflow "record-fallback"))
          sheet-id (sheet/build-workflow! ctx
                     (sheet/workflow "s9c-fallback"
                       (sheet/blackboard bb)
                       (sheet/judges {:fallback-judge {:type :custom :sheet-id judge}})
                       (sheet/fallback "recover" :judges ["fallback-judge"]
                         (sheet/code "primary" :fn (fq "boom") :reads [:request] :writes [:mid])
                         (sheet/code "backup" :fn (fq "step") :reads [:request] :writes [:mid]))))
          result (sheet/execute ctx sheet-id {:request "hello"} :timeout-ms 60000)
          assessed (assessments-of ctx (:trace-id result) 1)]
      (is (= :success (:status result)) (pr-str result))
      (is (= [(node-id ctx sheet-id "recover")] (mapv :node-id assessed)))
      (is (= :scored (:status (first assessed))))
      (is (= {:mid "mid:hello"} (:host-outputs (first (:fallback @judge-calls))))))))

(deftest a-tree-with-no-judged-composite-keeps-the-ephemeral-fast-path
  (h/with-async-test-context [ctx]
    (reset! judge-calls {})
    (let [judge (sheet/build-workflow! ctx (judge-workflow "record-leaf"))
          sheet-id (sheet/build-workflow! ctx
                     (sheet/workflow "s9c-fast"
                       (sheet/blackboard bb)
                       (sheet/judges {:leaf-judge {:type :custom :sheet-id judge}})
                       (sheet/sequence "pipeline"
                         (sheet/code "step" :fn (fq "step") :reads [:request] :writes [:mid])
                         (sheet/code "finish" :fn (fq "finish") :reads [:mid] :writes [:answer]
                           :judges ["leaf-judge"]))))
          result (sheet/execute ctx sheet-id {:request "hello"} :timeout-ms 60000)
          assessed (assessments-of ctx (:trace-id result) 1)
          completed-nodes (set (map :node-id (events-of ctx (:trace-id result)
                                                        #{:sheet/node-execution-completed})))]
      (is (= :success (:status result)) (pr-str result))
      (is (= [(node-id ctx sheet-id "finish")] (mapv :node-id assessed))
          "a judge on a LEAF still works on the fast path")
      (is (seq (events-of ctx (:trace-id result) #{:sheet/ephemeral-evaluations-recorded}))
          "the run recorded its ephemeral summary")
      (is (not (contains? completed-nodes (node-id ctx sheet-id "pipeline")))
          "no composite completion was written: the fast path is preserved"))))

(defn- publish! [ctx sheet-id]
  (let [r (h/run-and-apply! ctx (h/make-publish-version-command sheet-id :description "v1"))]
    (is (not (h/is-anomaly? r)) (pr-str r))))

(deftest a-judge-on-the-root-sequence-of-a-published-run-assesses-it
  (h/with-async-test-context [ctx]
    (reset! judge-calls {})
    (let [judge (sheet/build-workflow! ctx (judge-workflow "record-root"))
          sheet-id (sheet/build-workflow! ctx
                     (sheet/workflow "s9c-published-root"
                       (sheet/blackboard bb)
                       (sheet/judges {:root-judge {:type :custom :sheet-id judge}})
                       (sheet/sequence "pipeline"
                         (sheet/code "step" :fn (fq "step") :reads [:request] :writes [:mid])
                         (sheet/code "finish" :fn (fq "finish") :reads [:mid] :writes [:answer]))))
          source-id (node-id ctx sheet-id "pipeline")
          _ (publish! ctx sheet-id)
          ;; attached on the DRAFT node after publishing: it monitors the version
          attached (h/run-and-apply! ctx (h/make-set-node-judges-command sheet-id source-id ["root-judge"]))
          result (sheet/execute ctx sheet-id {:request "hello"} :use-version 1 :timeout-ms 60000)
          assessed (assessments-of ctx (:trace-id result) 1)]
      (is (not (h/is-anomaly? attached)) (pr-str attached))
      (is (= 1 (:executed-version result)))
      (is (= :success (:status result)) (pr-str result))
      (is (= [source-id] (mapv :node-id assessed)) "assessed as the SOURCE (draft) node")
      (is (= :scored (:status (first assessed))))
      (is (= {:answer "answer:mid:hello"} (:host-outputs (first (:root @judge-calls))))))))

(deftest a-judge-on-a-published-leaf-is-given-the-leafs-real-instruction
  (h/with-async-test-context [ctx]
    (reset! judge-calls {})
    (let [judge (sheet/build-workflow! ctx (judge-workflow "record-published"))
          sheet-id (sheet/build-workflow! ctx
                     (sheet/workflow "s9c-published-leaf"
                       (sheet/blackboard bb)
                       (sheet/judges {:leaf-judge {:type :custom :sheet-id judge}})
                       (sheet/sequence "pipeline"
                         (sheet/code "step" :fn (fq "step") :reads [:request] :writes [:mid]
                           :judges ["leaf-judge"]))))
          source-id (node-id ctx sheet-id "step")
          _ (h/run-and-apply! ctx {:command/name :sheet/set-node-instruction
                                   :command/id (random-uuid)
                                   :command/timestamp (ai.obney.grain.time.interface/now)
                                   :sheet-id sheet-id :node-id source-id
                                   :instruction "Prefix the request with mid."})
          _ (publish! ctx sheet-id)
          result (sheet/execute ctx sheet-id {:request "hello"} :use-version 1 :timeout-ms 60000)
          assessed (assessments-of ctx (:trace-id result) 1)]
      (is (= :success (:status result)) (pr-str result))
      (is (= :scored (:status (first assessed))))
      (is (= "Prefix the request with mid."
             (:host-instruction (first (:published @judge-calls))))
          "the judge sees the instruction of the definition that ran"))))
