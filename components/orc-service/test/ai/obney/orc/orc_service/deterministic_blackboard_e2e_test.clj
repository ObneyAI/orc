(ns ai.obney.orc.orc-service.deterministic-blackboard-e2e-test
  "Deterministic end-to-end coverage for blackboard schemas and value boundaries."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.core.value-log :as value-log]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.grain.event-store-v3.interface :as es]))

(defonce ^:private calls (atom []))

(defn record-int-input [{:keys [inputs]}]
  (swap! calls conj [:input (:input inputs)])
  {:output (:input inputs)})

(defn invalid-number-output [_]
  (swap! calls conj [:producer])
  {:number "not-an-integer"})

(defn record-downstream [{:keys [inputs]}]
  (swap! calls conj [:downstream (:number inputs)])
  {:seen (:number inputs)})

(defn return-extra-key [{:keys [inputs]}]
  {:output (:input inputs)
   :rogue "must-not-enter-blackboard"})

(defn observe-value [{:keys [inputs]}]
  {:observed (:value inputs)})

(defn- fq [function-name]
  (str "ai.obney.orc.orc-service.deterministic-blackboard-e2e-test/"
       function-name))

(defn- events-for-tick [ctx tick-id]
  (into [] (es/read (:event-store ctx)
                   {:tenant-id (:tenant-id ctx) :tags #{[:tick tick-id]}})))

(deftest det-e2e-048-input-schema-rejection
  (testing "invalid initial input is rejected before any node begins"
    (h/with-async-test-context [ctx]
      (reset! calls [])
      (let [definition (sheet/workflow "det-e2e-048-input-schema"
                         (sheet/blackboard {:input :int :output :int})
                         (sheet/sequence "main"
                           (sheet/code "record" :fn (fq "record-int-input")
                             :reads [:input] :writes [:output])))
            sheet-id (sheet/build-workflow! ctx definition)
            result (sheet/execute ctx sheet-id {:input "wrong-type"})
            events (events-for-tick ctx (:trace-id result))]
        (is (= :failure (:status result)))
        (is (empty? @calls) "schema rejection must precede executor invocation")
        (is (not-any? #(= :sheet/node-execution-started (:event/type %)) events))
        (is (str/includes? (or (:error result) "") "input"))
        (is (str/includes? (str/lower-case (or (:error result) "")) "schema"))))))

(deftest det-e2e-049-output-schema-rejection
  (testing "an invalid code result cannot update the blackboard or reach downstream"
    (h/with-async-test-context [ctx]
      (reset! calls [])
      (let [definition (sheet/workflow "det-e2e-049-output-schema"
                         (sheet/blackboard {:number :int :seen :int})
                         (sheet/sequence "main"
                           (sheet/code "invalid-producer" :fn (fq "invalid-number-output")
                             :writes [:number])
                           (sheet/code "downstream" :fn (fq "record-downstream")
                             :reads [:number] :writes [:seen])))
            sheet-id (sheet/build-workflow! ctx definition)
            result (sheet/execute ctx sheet-id {})
            tick-events (events-for-tick ctx (:trace-id result))
            number-writes (filter #(and (= :sheet/execution-value-written (:event/type %))
                                        (= :number (:key %)))
                                  tick-events)]
        (is (= :failure (:status result)))
        (is (= [[:producer]] @calls))
        (is (empty? number-writes))
        (is (nil? (get-in result [:outputs :number])))
        (is (str/includes? (or (:error result) "") "number"))))))

(deftest det-e2e-050-versioned-blackboard-writes
  (testing "successive projected values are monotonic and each execution resolves its actual read source"
    (h/with-async-test-context [ctx]
      (let [definition (sheet/workflow "det-e2e-050-versioned-writes"
                         (sheet/blackboard {:value :string :observed :string})
                         (sheet/sequence "main"
                           (sheet/code "observe" :fn (fq "observe-value")
                             :reads [:value] :writes [:observed])))
            sheet-id (sheet/build-workflow! ctx definition)
            _ (h/run-and-apply! ctx (h/make-set-key-value-command sheet-id :value "v1"))
            projected-v1 (get (sheet/get-blackboard-by-key ctx sheet-id) :value)
            result-v1 (sheet/execute ctx sheet-id {})
            _ (h/run-and-apply! ctx (h/make-set-key-value-command sheet-id :value "v2"))
            projected-v2 (get (sheet/get-blackboard-by-key ctx sheet-id) :value)
            result-v2 (sheet/execute ctx sheet-id {})
            read-source (fn [result]
                          (->> (events-for-tick ctx (:trace-id result))
                               (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                             (= :leaf (:node-type %))))
                               first :read-sources :value))]
        (is (= [1 2] [(:version projected-v1) (:version projected-v2)]))
        (is (= ["v1" "v2"] [(:value projected-v1) (:value projected-v2)]))
        (is (= ["v1" "v2"] [(get-in result-v1 [:outputs :observed])
                              (get-in result-v2 [:outputs :observed])]))
        (is (= "v1" (value-log/resolve-source ctx (:tenant-id ctx) (read-source result-v1))))
        (is (= "v2" (value-log/resolve-source ctx (:tenant-id ctx) (read-source result-v2))))
        (is (not= (read-source result-v1) (read-source result-v2)))))))

(deftest det-e2e-051-undeclared-read
  (testing "workflow validation names the exact undeclared read before execution"
    (h/with-async-test-context [ctx]
      (let [error (try
                    (sheet/build-workflow!
                     ctx
                     (sheet/workflow "det-e2e-051-undeclared-read"
                       (sheet/blackboard {:output :string})
                       (sheet/sequence "main"
                         (sheet/code "bad-read" :fn (fq "return-extra-key")
                           :reads [:missing-input] :writes [:output]))))
                    nil
                    (catch Throwable t t))]
        (is (some? error))
        (is (str/includes? (ex-message error) "missing-input"))
        (is (str/includes? (str/lower-case (ex-message error)) "unknown blackboard"))
        (is (str/includes? (str/lower-case (ex-message error)) "reads"))))))

(deftest det-e2e-052-undeclared-write-filtering
  (testing "executor keys outside declared writes never enter canonical state"
    (h/with-async-test-context [ctx]
      (let [definition (sheet/workflow "det-e2e-052-undeclared-write"
                         (sheet/blackboard {:input :string :output :string})
                         (sheet/sequence "main"
                           (sheet/code "extra-return" :fn (fq "return-extra-key")
                             :reads [:input] :writes [:output])))
            sheet-id (sheet/build-workflow! ctx definition)
            result (sheet/execute ctx sheet-id {:input "safe"})
            tick-events (events-for-tick ctx (:trace-id result))
            writes (filter #(= :sheet/execution-value-written (:event/type %)) tick-events)]
        (is (= :success (:status result)))
        (is (= "safe" (get-in result [:outputs :output])))
        (is (not (contains? (:outputs result) :rogue)))
        (is (not (contains? (sheet/get-blackboard-by-key ctx sheet-id) :rogue)))
        (is (not-any? #(= :rogue (:key %)) writes))))))
