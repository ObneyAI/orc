(ns ai.obney.orc.orc-service.code-leaf-tool-gate-test
  "Generated from LeafExecutor invariants CodeLeafToolGateIsPreserved and
   ToolGateSeesInvocationIdentity (specs/orc-service.allium).

   A hand-authored `sheet/code` leaf names a consumer tool gate. The gate
   builder records the context it is constructed with and returns a caller
   that records each tool call. The execution also supplies an ordinary,
   ungated caller that must never be used when a gate is configured."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def builds (atom []))
(def gated-calls (atom []))
(def ungated-calls (atom []))

(defn gate-builder
  "Consumer tool gate: (builder blackboard context) -> call-tool-fn."
  [_blackboard context]
  (swap! builds conj (select-keys context [:node :node-id :tick-id
                                           :execution-deadline-ms]))
  (fn [tool-name args & _]
    (swap! gated-calls conj [tool-name args])
    {:hits [(str "gated:" (:q args))]}))

(defn not-a-caller-builder [_blackboard _context] "not a function")

(defn search-leaf
  "Consumer code that uses whatever tool caller its context provides."
  [{:keys [inputs call-tool-fn]}]
  {:hits (:hits (call-tool-fn "search" {:q (:q inputs)}))})

(defn- fq [function-name]
  (str "ai.obney.orc.orc-service.code-leaf-tool-gate-test/" function-name))

(defn- reset-recorders! []
  (reset! builds []) (reset! gated-calls []) (reset! ungated-calls []))

(defn- ungated-caller [tool-name args & _]
  (swap! ungated-calls conj [tool-name args])
  {:hits ["ungated"]})

(defn- gated-workflow [workflow-name builder]
  (sheet/workflow workflow-name
    (sheet/blackboard {:q :string :hits [:vector :string]})
    (sheet/code "search" :fn (fq "search-leaf")
      :tool-caller-fn builder
      :reads [:q] :writes [:hits])))

(defn- run! [ctx workflow]
  (let [sheet-id (sheet/build-workflow! ctx workflow)]
    {:sheet-id sheet-id
     :result (sheet/execute (assoc ctx :call-tool-fn ungated-caller)
                            sheet-id {:q "revenue"} :timeout-ms 10000)}))

;; ---------------------------------------------------------------------------
;; CodeLeafToolGateIsPreserved
;; ---------------------------------------------------------------------------

(deftest authored-code-leaf-keeps-its-tool-gate
  (testing "the gate named on sheet/code reaches the stored definition"
    (is (= (fq "gate-builder")
           (:tool-caller-fn (sheet/code "search" :fn (fq "search-leaf")
                              :tool-caller-fn (fq "gate-builder")
                              :reads [:q] :writes [:hits])))
        "the public constructor keeps the option")
    (h/with-async-test-context [ctx]
      (reset-recorders!)
      (let [{:keys [sheet-id]} (run! ctx (gated-workflow "gate-stored" (fq "gate-builder")))
            stored (some #(when (= "search" (:name %)) %)
                         (sheet/get-nodes-for-sheet ctx sheet-id))]
        (is (= (fq "gate-builder") (:tool-caller-fn stored)))))))

(deftest authored-code-leaf-tool-calls-use-the-gate
  (testing "every tool call from the leaf goes through the configured gate"
    (h/with-async-test-context [ctx]
      (reset-recorders!)
      (let [{:keys [result]} (run! ctx (gated-workflow "gate-used" (fq "gate-builder")))]
        (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
        (is (= ["gated:revenue"] (get-in result [:outputs :hits])))
        (is (= [["search" {:q "revenue"}]] @gated-calls))
        (is (empty? @ungated-calls) "the ungated caller is never used")))))

(deftest unresolvable-gate-fails-without-fallback
  (testing "a gate that cannot be resolved fails the node; no ungated call happens"
    (h/with-async-test-context [ctx]
      (reset-recorders!)
      (let [{:keys [result]} (run! ctx (gated-workflow "gate-missing"
                                                       "no.such.namespace/builder"))]
        (is (= :failure (:status result)))
        (is (empty? @ungated-calls))))))

(deftest gate-that-builds-no-caller-fails-without-fallback
  (testing "a gate builder that returns no function fails the node; no ungated call happens"
    (h/with-async-test-context [ctx]
      (reset-recorders!)
      (let [{:keys [result]} (run! ctx (gated-workflow "gate-not-fn"
                                                       (fq "not-a-caller-builder")))]
        (is (= :failure (:status result)))
        (is (empty? @ungated-calls))))))

(deftest code-leaf-without-gate-keeps-the-ordinary-caller
  (testing "with no gate configured the ordinary caller remains available"
    (h/with-async-test-context [ctx]
      (reset-recorders!)
      (let [workflow (sheet/workflow "gate-absent"
                       (sheet/blackboard {:q :string :hits [:vector :string]})
                       (sheet/code "search" :fn (fq "search-leaf")
                         :reads [:q] :writes [:hits]))
            {:keys [result]} (run! ctx workflow)]
        (is (= :success (:status result)))
        (is (= ["ungated"] (get-in result [:outputs :hits])))))))

(deftest authored-gate-survives-round-trip
  (h/with-async-test-context [ctx]
    (let [sheet-id (sheet/build-workflow! ctx (gated-workflow "gate-round-trip"
                                                             (fq "gate-builder")))
          form (dsl/export-to-dsl (dsl/export-sheet ctx sheet-id))
          regenerated (binding [*ns* (find-ns 'ai.obney.orc.orc-service.core.dsl)]
                        (eval (read-string form)))]
      (is (str/includes? form ":tool-caller-fn")
          "the regenerated DSL names the gate")
      (is (str/includes? form (fq "gate-builder")))
      (is (= sheet-id (sheet/build-workflow! ctx regenerated))
          "the regenerated definition is the same workflow"))))

;; ---------------------------------------------------------------------------
;; ToolGateSeesInvocationIdentity
;; ---------------------------------------------------------------------------

(deftest gate-is-built-with-the-invocation-identity
  (h/with-async-test-context [ctx]
    (reset-recorders!)
    (let [{:keys [sheet-id result]} (run! ctx (gated-workflow "gate-identity"
                                                              (fq "gate-builder")))
          node-id (some #(when (= "search" (:name %)) (:id %))
                        (sheet/get-nodes-for-sheet ctx sheet-id))
          built (first @builds)]
      (is (= :success (:status result)))
      (is (= 1 (count @builds)))
      (is (= node-id (:node-id built)))
      (is (= node-id (get-in built [:node :id])))
      (is (= (:trace-id result) (:tick-id built)) "the execution identity")
      (is (number? (:execution-deadline-ms built)) "the effective deadline")
      (let [now (System/currentTimeMillis)
            deadline (:execution-deadline-ms built)]
        (is (< (- now 10000) deadline (+ now 10000))
            "it is this execution's absolute deadline (run! uses :timeout-ms 10000)")))))
