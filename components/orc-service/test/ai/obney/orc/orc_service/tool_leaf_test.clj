(ns ai.obney.orc.orc-service.tool-leaf-test
  "Generated from LeafExecutor ToolLeafCallsOnlyAuthoredTools
   (specs/orc-service.allium): the `:tool` executor is real.

   `sheet/tool` names one tool when the workflow is authored; its arguments are
   its declared read keys; its result is written to its single write key; the
   node's gate and declared contracts apply exactly as for a code leaf."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def calls (atom []))
(def gate-builds (atom 0))

(defn- base-caller [tool args & _]
  (swap! calls conj [:base tool args])
  {:hits [(str "base:" (:query args))]})

(defn gate [_blackboard _context]
  (swap! gate-builds inc)
  (fn [tool args & _]
    (swap! calls conj [:gated tool args])
    {:hits [(str "gated:" (:query args))]}))

(defn- reset-recorders! [] (reset! calls []) (reset! gate-builds 0))

(defn- tool-workflow [workflow-name & opts]
  (sheet/workflow workflow-name
    (sheet/blackboard {:query :string :hits [:vector :string]})
    (apply sheet/tool "lookup" :tool "search" :reads [:query] :writes [:hits] opts)))

(defn- run! [ctx workflow]
  (sheet/execute ctx (sheet/build-workflow! ctx workflow) {:query "q3"} :timeout-ms 10000))

(deftest tool-leaf-calls-its-authored-tool-with-its-reads
  (reset-recorders!)
  (h/with-async-test-context [ctx {:context {:call-tool-fn base-caller}}]
    (let [result (run! ctx (tool-workflow "tool-leaf-basic"))]
      (is (= :success (:status result)) (pr-str (select-keys result [:status :error])))
      (is (= [[:base "search" {:query "q3"}]] @calls))
      (is (= ["base:q3"] (get-in result [:outputs :hits]))))))

(deftest tool-leaf-uses-its-gate
  (reset-recorders!)
  (h/with-async-test-context [ctx {:context {:call-tool-fn base-caller}}]
    (let [result (run! ctx (tool-workflow "tool-leaf-gated"
                                          :tool-caller-fn "ai.obney.orc.orc-service.tool-leaf-test/gate"))]
      (is (= :success (:status result)))
      (is (= 1 @gate-builds))
      (is (= [[:gated "search" {:query "q3"}]] @calls) "never the ungated caller"))))

(deftest tool-leaf-honours-its-declared-contract
  (reset-recorders!)
  (h/with-async-test-context [ctx {:context {:call-tool-fn base-caller}}]
    (let [result (run! ctx (tool-workflow "tool-leaf-contract"
                                          :tool-contracts {"search" {:arguments [:map [:query :int]]}}))]
      (is (= :failure (:status result)))
      (is (empty? @calls) "invalid arguments never reach the tool"))))

(deftest tool-leaf-without-a-caller-fails-explicitly
  (reset-recorders!)
  (h/with-async-test-context [ctx]
    (let [result (run! ctx (tool-workflow "tool-leaf-no-caller"))]
      (is (= :failure (:status result)))
      (is (str/includes? (str (:error result) (pr-str (:failed-leaves result))) "search")
          "the failure names the tool"))))

(deftest tool-leaf-requires-an-authored-tool-name
  (h/with-async-test-context [ctx]
    (is (= ::rejected
           (try
             (let [r (sheet/build-workflow!
                      ctx (sheet/workflow "tool-leaf-unnamed"
                            (sheet/blackboard {:query :string :hits [:vector :string]})
                            (sheet/tool "lookup" :reads [:query] :writes [:hits])))]
               (if (uuid? r) ::built ::rejected))
             (catch Exception _ ::rejected))))))

(deftest tool-leaf-survives-round-trip
  (h/with-async-test-context [ctx]
    (let [workflow (tool-workflow "tool-leaf-round-trip"
                                  :tool-caller-fn "ai.obney.orc.orc-service.tool-leaf-test/gate"
                                  :tool-contracts {"search" {:arguments [:map [:query :string]]}})
          sheet-id (sheet/build-workflow! ctx workflow)
          form (dsl/export-to-dsl (dsl/export-sheet ctx sheet-id))
          regenerated (binding [*ns* (find-ns 'ai.obney.orc.orc-service.core.dsl)]
                        (eval (read-string form)))]
      (is (str/includes? form "(tool \"lookup\""))
      (is (= sheet-id (sheet/build-workflow! ctx regenerated))))))
