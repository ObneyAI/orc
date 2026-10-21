(ns ai.obney.orc.evaluation.judge-intended-contracts-test
  "Four judge contracts the engine did not meet, each driven through a public
   boundary:

   - a judge can be attached to a delegate, so a reusable subbehaviour is
     independently assessable (AttachmentIsMonitoring);
   - every band of a scale carries a non-blank description (CompleteBands);
   - the shipped evaluation suite builds as an ordinary workflow;
   - aggregating a selection of judges counts only the selected judges and
     never invents a score for an unselected one (NothingInvented)."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.evaluation.interface :as evaluation]
            [ai.obney.orc.evaluation.core.scale :as scale]))

(defn echo [{:keys [inputs]}] {:answer (:request inputs)})

(defn- echo-workflow [name]
  (sheet/workflow name
    (sheet/blackboard {:request [:string {:description "Input to echo exactly"}]
                       :answer [:string {:description "The input, unchanged"}]})
    (sheet/code "echo" :fn "ai.obney.orc.evaluation.judge-intended-contracts-test/echo"
      :reads [:request] :writes [:answer])))

(defn- node-named [ctx sheet-id node-name]
  (some #(when (= node-name (:name %)) %) (sheet/get-nodes-for-sheet ctx sheet-id)))

(deftest a-judge-attaches-to-a-delegate
  (h/with-async-test-context [ctx]
    (let [child (sheet/build-workflow! ctx (echo-workflow "intended-child"))
          parent (sheet/build-workflow! ctx
                   (sheet/workflow "intended-parent"
                     (sheet/blackboard {:request [:string {:description "Input to echo exactly"}]
                                        :answer [:string {:description "The input, unchanged"}]})
                     (sheet/delegate "specialist" :target-sheet-id child
                       :reads [:request] :writes [:answer])))
          node-id (:id (node-named ctx parent "specialist"))
          declared (h/run-and-apply! ctx (h/make-declare-judge-command parent "quality" {:type :grounding}))
          attached (h/run-and-apply! ctx (h/make-set-node-judges-command parent node-id ["quality"]))]
      (is (nil? (:cognitect.anomalies/category declared)) (pr-str declared))
      (is (nil? (:cognitect.anomalies/category attached))
          (str "a reusable subbehaviour is independently assessable: " (pr-str attached))))))

(deftest every-band-needs-a-description
  (is (thrown? clojure.lang.ExceptionInfo
               (scale/discrete-scale {:min 1 :max 2 :bands {1 "" 2 nil}}))
      "a blank or missing band description is rejected"))

(deftest the-shipped-evaluation-suite-builds
  (h/with-async-test-context [ctx]
    (let [r (try {:sheet-id (sheet/build-workflow! ctx (evaluation/evaluation-suite))}
                 (catch clojure.lang.ExceptionInfo e {:failure (ex-message e) :data (ex-data e)}))]
      (is (uuid? (:sheet-id r)) (str "the shipped evaluation suite builds: " (pr-str r))))))

(deftest aggregation-counts-only-selected-judges
  (testing "a grounding-only selection of 1.0 aggregates to 1.0 over one dimension"
    (let [r (evaluation/aggregate-dimensions
             {:inputs {:grounding-result {:score 1.0 :feedback "Every claim is supported."}}})]
      (is (= 1 (count (get-in r [:aggregate-result :dimensions] (:dimensions r))))
          (pr-str r))
      (is (= 1.0 (get-in r [:aggregate-result :aggregate-score] (:aggregate-score r)))
          (pr-str r)))))
