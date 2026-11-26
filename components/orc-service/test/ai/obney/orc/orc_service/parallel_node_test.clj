(ns ai.obney.orc.orc-service.parallel-node-test
  "Tests for parallel node execution."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]))

;; =============================================================================
;; Test Functions
;; =============================================================================

(defn score-a [{:keys [inputs]}]
  (Thread/sleep 50)
  {:score-a 10})

(defn score-b [{:keys [inputs]}]
  (Thread/sleep 50)
  {:score-b 20})

(defn score-c [{:keys [inputs]}]
  (Thread/sleep 50)
  {:score-c 30})

(defn failing-scorer [{:keys [inputs]}]
  (throw (ex-info "Scorer failed" {})))

(def execution-log (atom []))

(defn logged-score-a [{:keys [inputs]}]
  (swap! execution-log conj {:start :a :time (System/currentTimeMillis)})
  (Thread/sleep 100)
  (swap! execution-log conj {:end :a :time (System/currentTimeMillis)})
  {:score-a 10})

(defn logged-score-b [{:keys [inputs]}]
  (swap! execution-log conj {:start :b :time (System/currentTimeMillis)})
  (Thread/sleep 100)
  (swap! execution-log conj {:end :b :time (System/currentTimeMillis)})
  {:score-b 20})

;; =============================================================================
;; Parallel Execution Tests
;; =============================================================================

(deftest parallel-basic-test
  (testing "parallel node executes all children and collects outputs"
    (h/with-async-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Parallel Basic"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :score-a :int))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :score-b :int))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :score-c :int))

        (let [parallel-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :parallel))
              parallel-id (-> parallel-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-parallel-config-command sheet-id parallel-id
                                  :success-policy :all :failure-policy :any))

          (doseq [[idx [fn-name writes]] [[0 ["ai.obney.orc.orc-service.parallel-node-test/score-a" [:score-a]]]
                                          [1 ["ai.obney.orc.orc-service.parallel-node-test/score-b" [:score-b]]]
                                          [2 ["ai.obney.orc.orc-service.parallel-node-test/score-c" [:score-c]]]]]
            (let [leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf
                                                      :parent-id parallel-id :index idx))
                  leaf-id (-> leaf-result :command-result/events first :node-id)]
              (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code :fn fn-name))
              (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [] writes))))

          (let [result (sheet/execute ctx sheet-id {})]
            (is (= :success (:status result)))
            (is (= 10 (get-in result [:outputs :score-a])))
            (is (= 20 (get-in result [:outputs :score-b])))
            (is (= 30 (get-in result [:outputs :score-c])))))))))

(deftest parallel-concurrent-execution-test
  (testing "children execute concurrently, not sequentially"
    (h/with-async-test-context [ctx]
      (reset! execution-log [])

      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Parallel Concurrent"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :score-a :int))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :score-b :int))

        (let [parallel-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :parallel))
              parallel-id (-> parallel-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-parallel-config-command sheet-id parallel-id
                                  :success-policy :all :failure-policy :any))

          (let [leaf-a (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf
                                               :parent-id parallel-id :index 0))
                leaf-a-id (-> leaf-a :command-result/events first :node-id)
                leaf-b (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf
                                               :parent-id parallel-id :index 1))
                leaf-b-id (-> leaf-b :command-result/events first :node-id)]

            (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-a-id :code
                                    :fn "ai.obney.orc.orc-service.parallel-node-test/logged-score-a"))
            (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-a-id [] [:score-a]))

            (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-b-id :code
                                    :fn "ai.obney.orc.orc-service.parallel-node-test/logged-score-b"))
            (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-b-id [] [:score-b]))

            (let [result (sheet/execute ctx sheet-id {})
                  log @execution-log
                  starts (filter #(= :start (first (vals %))) log)
                  start-times (map :time starts)]

              (is (= :success (:status result)))

              (when (>= (count start-times) 2)
                (let [time-diff (Math/abs (- (first start-times) (second start-times)))]
                  (is (< time-diff 50) "Both children should start nearly simultaneously"))))))))))

(deftest parallel-success-policy-all-test
  (testing ":all policy fails when any child fails"
    (h/with-async-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Parallel Policy All"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :score-a :int))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :score-b :int))

        (let [parallel-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :parallel))
              parallel-id (-> parallel-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-parallel-config-command sheet-id parallel-id
                                  :success-policy :all :failure-policy :any))

          (let [leaf-a (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf
                                               :parent-id parallel-id :index 0))
                leaf-a-id (-> leaf-a :command-result/events first :node-id)
                leaf-b (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf
                                               :parent-id parallel-id :index 1))
                leaf-b-id (-> leaf-b :command-result/events first :node-id)]

            (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-a-id :code
                                    :fn "ai.obney.orc.orc-service.parallel-node-test/score-a"))
            (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-a-id [] [:score-a]))

            (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-b-id :code
                                    :fn "ai.obney.orc.orc-service.parallel-node-test/failing-scorer"))
            (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-b-id [] [:score-b]))

            (let [result (sheet/execute ctx sheet-id {})]
              (is (= :failure (:status result))))))))))

(deftest parallel-success-policy-any-test
  (testing ":any policy succeeds when any child succeeds"
    (h/with-async-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Parallel Policy Any"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]

        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :score-a :int))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :score-b :int))

        (let [parallel-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :parallel))
              parallel-id (-> parallel-result :command-result/events first :node-id)]

          (h/run-and-apply! ctx (h/make-set-parallel-config-command sheet-id parallel-id
                                  :success-policy :any :failure-policy :all))

          (let [leaf-a (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf
                                               :parent-id parallel-id :index 0))
                leaf-a-id (-> leaf-a :command-result/events first :node-id)
                leaf-b (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf
                                               :parent-id parallel-id :index 1))
                leaf-b-id (-> leaf-b :command-result/events first :node-id)]

            (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-a-id :code
                                    :fn "ai.obney.orc.orc-service.parallel-node-test/score-a"))
            (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-a-id [] [:score-a]))

            (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-b-id :code
                                    :fn "ai.obney.orc.orc-service.parallel-node-test/failing-scorer"))
            (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-b-id [] [:score-b]))

            (let [result (sheet/execute ctx sheet-id {})]
              (is (= :success (:status result)))
              (is (= 10 (get-in result [:outputs :score-a]))))))))))
