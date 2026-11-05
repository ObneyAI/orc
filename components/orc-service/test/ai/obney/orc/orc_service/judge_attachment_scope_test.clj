(ns ai.obney.orc.orc-service.judge-attachment-scope-test
  "S9a: a judge may be attached to ANY node (leaves, researchers, composites,
   delegates, conditions and the root), through the command and through the DSL,
   and the attachment survives export, DSL generation and import."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.read-models :as rm]))

(defn echo [{:keys [inputs]}] {:answer (:request inputs)})

(def ^:private bb {:request [:string {:description "Input"}]
                   :answer [:string {:description "Answer"}]
                   :items [:vector [:string {:description "Item"}]]
                   :item [:string {:description "Current item"}]
                   :results [:vector [:string {:description "Result"}]]})

(defn- leaf [n] (dsl/code n :fn "ai.obney.orc.orc-service.judge-attachment-scope-test/echo"
                          :reads [:request] :writes [:answer]))

(defn- node-named [ctx sheet-id n]
  (some #(when (= n (:name %)) %) (rm/get-nodes-for-sheet ctx sheet-id)))

(defn- anomaly [r] (:cognitect.anomalies/category r))

(deftest any-node-accepts-a-declared-judge-and-unknown-targets-are-rejected
  (h/with-test-context [ctx]
    (let [child (dsl/build-workflow! ctx (dsl/workflow "s9a-cmd-child" (dsl/blackboard bb) (leaf "echo")))
          sheet (dsl/build-workflow! ctx
                  (dsl/workflow "s9a-cmd-parent"
                    (dsl/blackboard bb)
                    (dsl/judges {:quality {:type :grounding}})
                    (dsl/sequence "root-seq"
                      (dsl/parallel "par" (leaf "p1"))
                      (dsl/map-each "each" :from :items :as :item :into :results (leaf "e1"))
                      (dsl/fallback "fb" (leaf "f1"))
                      (dsl/condition "cond" :check {:key :request :op :equals :value "x"})
                      (dsl/delegate "del" :target-sheet-id child :reads [:request] :writes [:answer]))))]
      (doseq [n ["root-seq" "par" "each" "fb" "cond" "del" "p1"]]
        (let [id (:id (node-named ctx sheet n))
              r (h/run-and-apply! ctx (h/make-set-node-judges-command sheet id ["quality"]))]
          (is (nil? (anomaly r)) (str n ": " (pr-str r)))
          (is (= ["quality"] (:judges (node-named ctx sheet n))) n)))
      (testing "an unknown node is not-found"
        (is (= :cognitect.anomalies/not-found
               (anomaly (h/run-command ctx (h/make-set-node-judges-command sheet (random-uuid) ["quality"]))))))
      (testing "an undeclared judge is not-found"
        (is (= :cognitect.anomalies/not-found
               (anomaly (h/run-command ctx (h/make-set-node-judges-command
                                            sheet (:id (node-named ctx sheet "root-seq")) ["nope"])))))))))

(defn- attached-workflow [wf-name child]
  (dsl/workflow wf-name
    (dsl/blackboard bb)
    (dsl/judges {:quality {:type :grounding}})
    (dsl/sequence "root-seq" :judges ["quality"]
      (dsl/parallel "par" {:judges ["quality"]} (leaf "p1"))
      (dsl/map-each "each" :from :items :as :item :into :results :judges ["quality"] (leaf "e1"))
      (dsl/fallback "fb" :judges ["quality"] (dsl/code "f1" :fn "ai.obney.orc.orc-service.judge-attachment-scope-test/echo"
                                                       :reads [:request] :writes [:answer] :judges ["quality"]))
      (dsl/condition "cond" :check {:key :request :op :equals :value "x"} :judges ["quality"])
      (dsl/delegate "del" :target-sheet-id child :reads [:request] :writes [:answer] :judges ["quality"]))))

(def ^:private attached-names ["root-seq" "par" "each" "fb" "f1" "cond" "del"])

(defn- judges-by-name [ctx sheet]
  (into {} (map (fn [n] [n (:judges (node-named ctx sheet n))])) attached-names))

(def ^:private all-attached (zipmap attached-names (repeat ["quality"])))

(deftest dsl-attaches-judges-to-composites-and-delegates-and-round-trips
  (h/with-test-context [ctx]
    (let [child (dsl/build-workflow! ctx (dsl/workflow "s9a-dsl-child" (dsl/blackboard bb) (leaf "echo")))
          sheet (dsl/build-workflow! ctx (attached-workflow "s9a-dsl-parent" child))
          exported (dsl/export-sheet ctx sheet)]
      (is (= all-attached (judges-by-name ctx sheet)) "the read model shows every attachment")
      (testing "re-building an unchanged definition is a no-op; changing only attachments rebuilds"
        (is (= sheet (dsl/build-workflow! ctx (attached-workflow "s9a-dsl-parent" child))))
        (is (= all-attached (judges-by-name ctx sheet))))
      (doseq [pretty? [true false]]
        (let [code (dsl/export-to-dsl exported :pretty? pretty?)
              regenerated (binding [*ns* (find-ns 'ai.obney.orc.orc-service.core.dsl)]
                            (eval (read-string code)))
              rebuilt (dsl/build-workflow! ctx (assoc regenerated :workflow-name (str "s9a-dsl-regen-" pretty?)))]
          (is (= all-attached (judges-by-name ctx rebuilt))
              (str "DSL text round trip (pretty? " pretty? ")"))))
      (testing "EDN import keeps the attachments"
        (let [imported (dsl/import-sheet ctx (assoc-in exported [:sheet :name] "s9a-dsl-imported"))]
          (is (= all-attached (judges-by-name ctx imported))))))))
