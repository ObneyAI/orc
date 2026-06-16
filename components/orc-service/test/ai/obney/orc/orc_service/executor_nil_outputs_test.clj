(ns ai.obney.orc.orc-service.executor-nil-outputs-test
  "Nil-parse handling in execute-ai.

   An LLM leaf whose response parses to nil declared writes is a FAILURE,
   not a success — the model answered; we failed to extract. The failure
   carries the verbatim raw response so the parse problem is diagnosable
   and the Phase-1 model can recover. Rerunning is the job of the
   node-level :retry primitive (execute-with-retry), not an internal loop."
  (:require [ai.obney.orc.orc-service.core.executor :as executor]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dscloj.core :as dscloj]))

(def test-blackboard
  {:question {:key :question :schema :string :value "What is 2+2?" :version 1}
   :answer {:key :answer :schema :string :value nil :version 0}
   :extra {:key :extra :schema :string :value nil :version 0}})

(def base-node
  {:id #uuid "00000000-0000-0000-0000-000000000001"
   :type :leaf
   :executor :ai
   :model "fake/model"
   :instruction "Answer the question."
   :reads [:question]
   :writes [:answer]})

(deftest nil-outputs-fail-without-internal-retry
  (testing "nil-parsed outputs return :failure after a SINGLE dscloj call"
    (let [call-count (atom 0)
          raw "Free-form text the model wrote without any field markers."]
      (with-redefs [dscloj/predict
                    (fn [_provider _module _inputs _options]
                      (swap! call-count inc)
                      {:outputs {:answer nil}
                       :usage {:prompt-tokens 100 :completion-tokens 50 :total-tokens 150}
                       :model "fake/model"
                       :raw-response raw})]
        (let [result (executor/execute-leaf base-node test-blackboard :openrouter)]
          (is (= 1 @call-count) "no internal nil retry — rerunning is node-level :retry's job")
          (is (= :failure (:status result)))
          (is (str/includes? (:error result) ":answer")
              "error names the nil key(s)")
          (is (str/includes? (:error result) (subs raw 0 30))
              "error carries a raw-response preview")
          (is (= raw (:raw-response result))
              "full verbatim raw response on the result")
          (is (= 150 (get-in result [:usage :total-tokens]))
              "usage is preserved on the nil-failure path — these tokens were spent"))))))

(deftest partial-nil-outputs-name-only-nil-keys
  (testing "one nil write among populated ones fails and names only the nil key"
    (let [node (assoc base-node :writes [:answer :extra])]
      (with-redefs [dscloj/predict
                    (fn [_provider _module _inputs _options]
                      {:outputs {:answer "4" :extra nil}
                       :usage {:prompt-tokens 10 :completion-tokens 5 :total-tokens 15}
                       :model "fake/model"
                       :raw-response "[[ ## answer ## ]]\n4"})]
        (let [result (executor/execute-leaf node test-blackboard :openrouter)]
          (is (= :failure (:status result)))
          (is (str/includes? (:error result) ":extra"))
          (is (not (str/includes? (:error result) ":answer "))
              "populated keys are not blamed"))))))

(deftest exception-retry-path-unchanged
  (testing "transient exceptions still retry internally and can succeed"
    (let [call-count (atom 0)]
      (with-redefs [dscloj/predict
                    (fn [_provider _module _inputs _options]
                      (if (= 1 (swap! call-count inc))
                        (throw (ex-info "rate limited" {}))
                        {:outputs {:answer "4"}
                         :usage {:prompt-tokens 10 :completion-tokens 2 :total-tokens 12}
                         :model "fake/model"
                         :raw-response "[[ ## answer ## ]]\n4"}))]
        (let [result (executor/execute-leaf base-node test-blackboard :openrouter
                                            :options {:retry-delay-ms 1})]
          (is (= 2 @call-count))
          (is (= :success (:status result)))
          (is (= "4" (get-in result [:outputs :answer]))))))))

(deftest successful-parse-unchanged
  (testing "populated outputs return :success as before"
    (with-redefs [dscloj/predict
                  (fn [_provider _module _inputs _options]
                    {:outputs {:answer "4"}
                     :usage {:prompt-tokens 10 :completion-tokens 2 :total-tokens 12}
                     :model "fake/model"
                     :raw-response "[[ ## answer ## ]]\n4"})]
      (let [result (executor/execute-leaf base-node test-blackboard :openrouter)]
        (is (= :success (:status result)))
        (is (= "4" (get-in result [:outputs :answer])))
        (is (nil? (:error result)))))))

(deftest node-level-retry-reruns-nil-parse-failure
  (testing "a node with :retry config reruns the leaf until parse succeeds"
    (let [call-count (atom 0)
          node (assoc base-node :retry {:max-attempts 3 :backoff-ms [1 1]})]
      (with-redefs [dscloj/predict
                    (fn [_provider _module _inputs _options]
                      (if (< (swap! call-count inc) 3)
                        {:outputs {:answer nil}
                         :usage {:prompt-tokens 10 :completion-tokens 5 :total-tokens 15}
                         :model "fake/model"
                         :raw-response "markerless junk"}
                        {:outputs {:answer "4"}
                         :usage {:prompt-tokens 10 :completion-tokens 2 :total-tokens 12}
                         :model "fake/model"
                         :raw-response "[[ ## answer ## ]]\n4"}))]
        (let [result (executor/execute-leaf node test-blackboard :openrouter)]
          (is (= 3 @call-count) "node-level :retry drove the reruns")
          (is (= :success (:status result)))
          (is (= "4" (get-in result [:outputs :answer]))))))))
