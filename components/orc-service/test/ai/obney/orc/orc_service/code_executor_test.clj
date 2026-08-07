(ns ai.obney.orc.orc-service.code-executor-test
  "Tests for code executor functionality."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.core.executor :as executor]))

;; =============================================================================
;; Test Functions
;; =============================================================================

(defn simple-fn [{:keys [inputs]}]
  {:output (str "Hello, " (get inputs :name))})

(defn multi-input-fn [{:keys [inputs]}]
  (let [a (get inputs :a)
        b (get inputs :b)]
    {:sum (+ a b)
     :product (* a b)}))

(defn context-fn [{:keys [event-store inputs]}]
  {:has-store (some? event-store)
   :input-count (count inputs)})

(defn throwing-fn [{:keys [inputs]}]
  (throw (ex-info "Intentional error" {:reason "testing"})))

(defn returns-non-map [{:keys [inputs]}]
  "not a map")

;; =============================================================================
;; resolve-fn Tests
;; =============================================================================

(deftest resolve-fn-test
  (testing "resolves fully-qualified symbol to function"
    (let [result (executor/resolve-fn "ai.obney.orc.orc-service.code-executor-test/simple-fn")]
      (is (contains? result :fn))
      (is (fn? (:fn result)))))

  (testing "resolves a function supplied by a consumer namespace"
    (h/install-consumer-code-fixture!)
    (let [result (executor/resolve-fn "sormo.orc.consumer-code-fixture/deterministic-decision")]
      (is (contains? result :fn))
      (is (fn? (:fn result)))))

  (testing "returns error for non-existent namespace"
    (let [result (executor/resolve-fn "non.existent.namespace/some-fn")]
      (is (contains? result :error))
      (is (re-find #"Failed to resolve" (:error result)))))

  (testing "returns error for non-existent function"
    (h/install-consumer-code-fixture!)
    (let [result (executor/resolve-fn "sormo.orc.consumer-code-fixture/non-existent")]
      (is (contains? result :error))
      (is (re-find #"Function not found" (:error result))))))

;; =============================================================================
;; execute-code Tests
;; =============================================================================

(deftest execute-code-simple-test
  (testing "executes function with inputs and returns outputs"
    (let [node {:fn "ai.obney.orc.orc-service.code-executor-test/simple-fn"
                :reads [:name]
                :writes [:output]}
          blackboard {:name {:key :name :value "World" :version 1}}
          result (executor/execute-code node blackboard {})]
      (is (= :success (:status result)))
      (is (= "Hello, World" (get-in result [:outputs :output])))
      (is (number? (:duration-ms result))))))

(deftest execute-code-multi-input-test
  (testing "handles multiple inputs and outputs"
    (let [node {:fn "ai.obney.orc.orc-service.code-executor-test/multi-input-fn"
                :reads [:a :b]
                :writes [:sum :product]}
          blackboard {:a {:key :a :value 5 :version 1}
                      :b {:key :b :value 3 :version 1}}
          result (executor/execute-code node blackboard {})]
      (is (= :success (:status result)))
      (is (= 8 (get-in result [:outputs :sum])))
      (is (= 15 (get-in result [:outputs :product]))))))

(deftest execute-code-context-test
  (testing "passes event-store in context"
    (let [node {:fn "ai.obney.orc.orc-service.code-executor-test/context-fn"
                :reads [:x]
                :writes [:has-store :input-count]}
          blackboard {:x {:key :x :value 42 :version 1}}
          result (executor/execute-code node blackboard {:event-store :mock-store})]
      (is (= :success (:status result)))
      (is (= true (get-in result [:outputs :has-store])))
      (is (= 1 (get-in result [:outputs :input-count]))))))

(deftest execute-code-error-test
  (testing "handles function exceptions gracefully"
    (let [node {:fn "ai.obney.orc.orc-service.code-executor-test/throwing-fn"
                :reads []
                :writes []}
          result (executor/execute-code node {} {})]
      (is (= :failure (:status result)))
      (is (re-find #"Intentional error" (:error result))))))

(deftest execute-code-invalid-return-test
  (testing "fails when function returns non-map"
    (let [node {:fn "ai.obney.orc.orc-service.code-executor-test/returns-non-map"
                :reads []
                :writes []}
          result (executor/execute-code node {} {})]
      (is (= :failure (:status result)))
      (is (re-find #"must return a map" (:error result))))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest code-executor-integration-test
  (testing "code executor works end-to-end via execute API"
    (h/with-async-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Code Executor Integration"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :name :string))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :output :string))

        (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
              seq-id (-> seq-result :command-result/events first :node-id)
              leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
              leaf-id (-> leaf-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                  :fn "ai.obney.orc.orc-service.code-executor-test/simple-fn"))
          (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [:name] [:output]))

          (let [result (sheet/execute ctx sheet-id {:name "Clojure"})]
            (is (= :success (:status result)))
            (is (= "Hello, Clojure" (get-in result [:outputs :output])))))))))
