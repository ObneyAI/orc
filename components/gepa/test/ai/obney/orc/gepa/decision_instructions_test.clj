(ns ai.obney.orc.gepa.decision-instructions-test
  "GEPA optimises model decisions: the instruction of an `llm-decision` leaf
   (and of an `llm-condition`) is extracted as an optimisable component, and a
   candidate instruction patched in for evaluation actually reaches that node's
   provider call. Only the provider seam is injected."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.gepa.core.todo-processors :as gepa-tp]))

(def ^:private route-schema
  [:enum {:descriptions {"lookup" "Retrieve" "clarify" "Ask"}} "lookup" "clarify"])

(defn- decision-workflow [workflow-name]
  (sheet/workflow workflow-name
    (sheet/blackboard {:request :string :route route-schema})
    (sheet/llm-decision "route"
      :instruction "ORIGINAL decision instruction"
      :reads [:request] :writes [:route])))

(defn- condition-workflow [workflow-name]
  (sheet/workflow workflow-name
    (sheet/blackboard {:request :string})
    (sheet/llm-condition "holds"
      :instruction "ORIGINAL condition instruction"
      :reads [:request])))

(defn- capturing-predict [instructions answer]
  (fn [_provider module _inputs options]
    (swap! instructions conj (:instructions module))
    (let [outputs {(-> module :outputs first :name) answer}]
      (if (:with-metadata? options)
        {:outputs outputs :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}
         :model "deterministic" :raw-response (pr-str outputs)}
        outputs))))

(deftest decision-instructions-are-optimisable-components
  (h/with-async-test-context [ctx]
    (let [sheet-id (sheet/build-workflow! ctx (decision-workflow "gepa-decision-extract"))]
      (is (= {"route" "ORIGINAL decision instruction"}
             (gepa-tp/extract-workflow-instructions ctx sheet-id))))))

(deftest patched-decision-instruction-reaches-the-provider
  (h/with-async-test-context [ctx]
    (let [instructions (atom [])
          sheet-id (sheet/build-workflow! ctx (decision-workflow "gepa-decision-patch"))]
      (with-redefs [llm/predict (capturing-predict instructions "lookup")]
        (sheet/execute (assoc ctx :gepa/patched-instructions {"route" "PATCHED decision instruction"})
                       sheet-id {:request "q"} :timeout-ms 10000))
      (is (some #(str/includes? (str %) "PATCHED decision instruction") @instructions)
          (pr-str @instructions))
      (is (not-any? #(str/includes? (str %) "ORIGINAL decision instruction") @instructions)))))

(deftest patched-condition-instruction-reaches-the-provider
  (h/with-async-test-context [ctx]
    (let [instructions (atom [])
          sheet-id (sheet/build-workflow! ctx (condition-workflow "gepa-condition-patch"))]
      (with-redefs [llm/predict (capturing-predict instructions true)]
        (sheet/execute (assoc ctx :gepa/patched-instructions {"holds" "PATCHED condition instruction"})
                       sheet-id {:request "q"} :timeout-ms 10000))
      (is (some #(str/includes? (str %) "PATCHED condition instruction") @instructions)
          (pr-str @instructions)))))
