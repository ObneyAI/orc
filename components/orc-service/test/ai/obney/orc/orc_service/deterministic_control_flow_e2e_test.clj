(ns ai.obney.orc.orc-service.deterministic-control-flow-e2e-test
  "Deterministic, network-free end-to-end coverage for ORC control flow.

   Checklist: docs/DETERMINISTIC-E2E-TEST-CHECKLIST.md."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def execution-log (atom []))
(def concurrency-state (atom {:active 0 :maximum 0}))

(defn append-a [{:keys [inputs]}]
  (swap! execution-log conj :a)
  {:markers (conj (or (:markers inputs) []) :a)})

(defn append-b [{:keys [inputs]}]
  (swap! execution-log conj :b)
  {:markers (conj (or (:markers inputs) []) :b)})

(defn append-c [{:keys [inputs]}]
  (swap! execution-log conj :c)
  {:markers (conj (or (:markers inputs) []) :c)})

(defn fail-b [_]
  (swap! execution-log conj :b)
  (throw (ex-info "deterministic failure at b" {:step :b})))

(defn choose-primary [_]
  (swap! execution-log conj :primary)
  {:route :primary})

(defn choose-secondary [_]
  (swap! execution-log conj :secondary)
  {:route :secondary})

(defn choose-tertiary [_]
  (swap! execution-log conj :tertiary)
  {:route :tertiary})

(defn fail-primary [_]
  (swap! execution-log conj :primary)
  (throw (ex-info "primary unavailable" {:route :primary})))

(defn fail-secondary [_]
  (swap! execution-log conj :secondary)
  (throw (ex-info "secondary unavailable" {:route :secondary})))

(defn fail-tertiary [_]
  (swap! execution-log conj :tertiary)
  (throw (ex-info "tertiary unavailable" {:route :tertiary})))

(defn guarded-action [_]
  (swap! execution-log conj :guarded)
  {:route :guarded})

(defn alternative-action [_]
  (swap! execution-log conj :alternative)
  {:route :alternative})

(defn parallel-a [_]
  (swap! execution-log conj [:start :a])
  (Thread/sleep 80)
  (swap! execution-log conj [:end :a])
  {:a 1})

(defn parallel-b [_]
  (swap! execution-log conj [:start :b])
  (Thread/sleep 80)
  (swap! execution-log conj [:end :b])
  {:b 2})

(defn parallel-c [_] {:c 3})
(defn parallel-d [_] {:d 4})

(defn fail-parallel [_]
  (throw (ex-info "parallel branch failed" {:branch :failure})))

(defn write-shared-first [_]
  (Thread/sleep 70)
  {:shared :first})

(defn write-shared-second [_]
  (Thread/sleep 10)
  {:shared :second})

(defn map-double [{:keys [inputs]}]
  {:item (* 2 (:item inputs))})

(defn tracked-map-double [{:keys [inputs]}]
  (let [state (swap! concurrency-state
                     (fn [{:keys [active maximum]}]
                       (let [next-active (inc active)]
                         {:active next-active
                          :maximum (max maximum next-active)})))]
    (try
      (Thread/sleep 40)
      {:item (* 2 (:item inputs))}
      (finally
        (swap! concurrency-state update :active dec)))))

(defn collect-summary [{:keys [inputs]}]
  {:summary {:doubled (:results inputs)
             :left (:a inputs)
             :right (:b inputs)}})

(defn delegate-copy [{:keys [inputs]}]
  {:output (str (:input inputs) "-child")})

(defn delegate-fail [_]
  (throw (ex-info "delegated workflow failed" {:scope :child})))

(defn delegate-slow [{:keys [inputs]}]
  (Thread/sleep 250)
  {:output (str (:input inputs) "-late")})

(defn delegate-left [{:keys [inputs]}]
  (Thread/sleep 60)
  {:left (str (:input inputs) "-left")})

(defn delegate-right [{:keys [inputs]}]
  (Thread/sleep 60)
  {:right (str (:input inputs) "-right")})

(defn- fq [function-name]
  (str "ai.obney.orc.orc-service.deterministic-control-flow-e2e-test/"
       function-name))

(defn- trace-for
  ([ctx result]
   (trace-for ctx result nil))
  ([ctx result expected-node-count]
   (let [trace-id (:trace-id result)]
     (is (uuid? trace-id) "execution must expose its durable trace identity")
     (let [settled? (h/settle-until!
                     #(let [trace (get-in (h/run-query
                                           ctx
                                           {:query/name :sheet/get-trace
                                            :trace-id trace-id})
                                          [:query/result :trace])]
                        (and (some? trace)
                             (or (nil? expected-node-count)
                                 (= expected-node-count
                                    (count (:node-traces trace)))))))
           trace (get-in (h/run-query ctx {:query/name :sheet/get-trace
                                            :trace-id trace-id})
                         [:query/result :trace])]
       (is settled?
           (if expected-node-count
             (str "execution trace did not settle at " expected-node-count
                  " nodes; observed " (count (:node-traces trace)))
             "execution trace did not settle"))
       trace))))

(defn- node-statuses [trace]
  (mapv :status (:node-traces trace)))

(defn- trace-family [ctx trace-id expected-count]
  (is (h/settle-until!
       #(= expected-count
           (count (get-in (h/run-query ctx {:query/name :sheet/get-trace-family
                                             :trace-id trace-id})
                          [:query/result :traces]))))
      "trace family did not settle")
  (get-in (h/run-query ctx {:query/name :sheet/get-trace-family
                             :trace-id trace-id})
          [:query/result]))

(deftest det-e2e-001-sequence-success
  (testing "sequence executes left-to-right and persists the complete trace"
    (h/with-async-test-context [ctx]
      (reset! execution-log [])
      (let [workflow (sheet/workflow "det-e2e-001-sequence-success"
                       (sheet/blackboard {:markers [:vector :keyword]})
                       (sheet/sequence "main"
                         (sheet/code "a" :fn (fq "append-a")
                           :reads [:markers] :writes [:markers])
                         (sheet/code "b" :fn (fq "append-b")
                           :reads [:markers] :writes [:markers])
                         (sheet/code "c" :fn (fq "append-c")
                           :reads [:markers] :writes [:markers])))
            sheet-id (sheet/build-workflow! ctx workflow)
            result (sheet/execute ctx sheet-id {:markers []})
            trace (trace-for ctx result 4)]
        (is (= :success (:status result)))
        (is (= [:a :b :c] @execution-log))
        (is (= [:a :b :c] (get-in result [:outputs :markers])))
        (is (= :success (:status trace)))
        (is (= 4 (count (:node-traces trace))) "root plus three leaves")
        (is (every? #{:success} (node-statuses trace)))))))

(deftest det-e2e-002-sequence-fail-fast
  (testing "a failed sequence child prevents every later child from running"
    (h/with-async-test-context [ctx]
      (reset! execution-log [])
      (let [workflow (sheet/workflow "det-e2e-002-sequence-fail-fast"
                       (sheet/blackboard {:markers [:vector :keyword]})
                       (sheet/sequence "main"
                         (sheet/code "a" :fn (fq "append-a")
                           :reads [:markers] :writes [:markers])
                         (sheet/code "b-fails" :fn (fq "fail-b")
                           :reads [] :writes [])
                         (sheet/code "c-must-not-run" :fn (fq "append-c")
                           :reads [:markers] :writes [:markers])))
            sheet-id (sheet/build-workflow! ctx workflow)
            result (sheet/execute ctx sheet-id {:markers []})
            trace (trace-for ctx result 3)]
        (is (= :failure (:status result)))
        (is (= [:a :b] @execution-log))
        (is (not-any? #(= :c %) @execution-log))
        (is (= :failure (:status trace)))
        (is (= 3 (count (:node-traces trace))) "root, successful a, failed b")
        (is (= 2 (count (filter #{:failure} (node-statuses trace))))
            "the failed leaf and its sequence parent are both failed")))))

(deftest det-e2e-003-fallback-first-child-success
  (testing "fallback stops after its first successful child"
    (h/with-async-test-context [ctx]
      (reset! execution-log [])
      (let [workflow (sheet/workflow "det-e2e-003-fallback-first-success"
                       (sheet/blackboard {:route :keyword})
                       (sheet/fallback "routes"
                         (sheet/code "primary" :fn (fq "choose-primary")
                           :reads [] :writes [:route])
                         (sheet/code "secondary" :fn (fq "choose-secondary")
                           :reads [] :writes [:route])))
            result (sheet/execute ctx (sheet/build-workflow! ctx workflow) {})
            trace (trace-for ctx result 2)]
        (is (= :success (:status result)))
        (is (= :primary (get-in result [:outputs :route])))
        (is (= [:primary] @execution-log))
        (is (= 2 (count (:node-traces trace))) "root plus selected child")))))

(deftest det-e2e-004-fallback-recovery
  (testing "fallback records failed attempts and returns the first recovery"
    (h/with-async-test-context [ctx]
      (reset! execution-log [])
      (let [workflow (sheet/workflow "det-e2e-004-fallback-recovery"
                       (sheet/blackboard {:route :keyword})
                       (sheet/fallback "routes"
                         (sheet/code "primary" :fn (fq "fail-primary")
                           :reads [] :writes [])
                         (sheet/code "secondary" :fn (fq "fail-secondary")
                           :reads [] :writes [])
                         (sheet/code "tertiary" :fn (fq "choose-tertiary")
                           :reads [] :writes [:route])))
            result (sheet/execute ctx (sheet/build-workflow! ctx workflow) {})
            trace (trace-for ctx result 4)]
        (is (= :success (:status result)))
        (is (= :tertiary (get-in result [:outputs :route])))
        (is (= [:primary :secondary :tertiary] @execution-log))
        (is (= 4 (count (:node-traces trace))))
        (is (= 2 (count (filter #{:failure} (node-statuses trace)))))))))

(deftest det-e2e-005-fallback-exhaustion
  (testing "fallback fails only after every child has failed"
    (h/with-async-test-context [ctx]
      (reset! execution-log [])
      (let [workflow (sheet/workflow "det-e2e-005-fallback-exhaustion"
                       (sheet/blackboard {:route :keyword})
                       (sheet/fallback "routes"
                         (sheet/code "primary" :fn (fq "fail-primary")
                           :reads [] :writes [])
                         (sheet/code "secondary" :fn (fq "fail-secondary")
                           :reads [] :writes [])
                         (sheet/code "tertiary" :fn (fq "fail-tertiary")
                           :reads [] :writes [])))
            result (sheet/execute ctx (sheet/build-workflow! ctx workflow) {})
            trace (trace-for ctx result 4)]
        (is (= :failure (:status result)))
        (is (= [:primary :secondary :tertiary] @execution-log))
        (is (nil? (get-in result [:outputs :route])))
        (is (= 4 (count (:node-traces trace))))
        (is (every? #{:failure} (node-statuses trace)))))))

(deftest det-e2e-006-condition-true-branch
  (testing "a true condition permits its guarded action"
    (h/with-async-test-context [ctx]
      (reset! execution-log [])
      (let [workflow (sheet/workflow "det-e2e-006-condition-true"
                       (sheet/blackboard {:enabled :boolean :route :keyword})
                       (sheet/sequence "main"
                         (sheet/condition "enabled?"
                           :check {:key :enabled :op :equals :value true})
                         (sheet/code "guarded" :fn (fq "guarded-action")
                           :reads [] :writes [:route])))
            result (sheet/execute ctx (sheet/build-workflow! ctx workflow)
                                  {:enabled true})
            trace (trace-for ctx result 3)]
        (is (= :success (:status result)))
        (is (= :guarded (get-in result [:outputs :route])))
        (is (= [:guarded] @execution-log))
        (is (= 3 (count (:node-traces trace))))))))

(deftest det-e2e-007-condition-false-with-fallback
  (testing "a false condition selects the fallback alternative"
    (h/with-async-test-context [ctx]
      (reset! execution-log [])
      (let [workflow (sheet/workflow "det-e2e-007-condition-fallback"
                       (sheet/blackboard {:enabled :boolean :route :keyword})
                       (sheet/fallback "route"
                         (sheet/sequence "guarded-route"
                           (sheet/condition "enabled?"
                             :check {:key :enabled :op :equals :value true})
                           (sheet/code "guarded" :fn (fq "guarded-action")
                             :reads [] :writes [:route]))
                         (sheet/code "alternative" :fn (fq "alternative-action")
                           :reads [] :writes [:route])))
            result (sheet/execute ctx (sheet/build-workflow! ctx workflow)
                                  {:enabled false})
            trace (trace-for ctx result 4)]
        (is (= :success (:status result)))
        (is (= :alternative (get-in result [:outputs :route])))
        (is (= [:alternative] @execution-log))
        (is (= :success (:status trace)))
        (is (some #{:failure} (node-statuses trace)))))))

(deftest det-e2e-008-condition-operator-matrix
  (testing "every documented deterministic condition operator works through execution"
    (h/with-async-test-context [ctx]
      (doseq [[index op input expected]
              [[0 :equals 5 5]
               [1 :not-equals 5 6]
               [2 :gt 6 5]
               [3 :lt 4 5]
               [4 :gte 5 5]
               [5 :lte 5 5]
               [6 :contains "behavior tree" "tree"]
               [7 :exists "present" nil]
               [8 :truthy true nil]]]
        (let [check (cond-> {:key :candidate :op op}
                      (not (#{:exists :truthy} op)) (assoc :value expected))
              workflow (sheet/workflow (str "det-e2e-008-condition-op-" index)
                         (sheet/blackboard {:candidate [:or :int :string :boolean]
                                            :route :keyword})
                         (sheet/sequence "main"
                           (sheet/condition (name op) :check check)
                           (sheet/code "selected" :fn (fq "guarded-action")
                             :reads [] :writes [:route])))
              result (sheet/execute ctx (sheet/build-workflow! ctx workflow)
                                    {:candidate input})
              trace (trace-for ctx result 3)]
          (is (= :success (:status result)) (str op " must pass"))
          (is (= :guarded (get-in result [:outputs :route])))
          (is (= :success (:status trace))))))))

(deftest det-e2e-009-parallel-all-success
  (testing "parallel :all returns every branch and overlaps their execution"
    (h/with-async-test-context [ctx]
      (reset! execution-log [])
      (let [workflow (sheet/workflow "det-e2e-009-parallel-all"
                       (sheet/blackboard {:a :int :b :int})
                       (sheet/parallel "branches"
                         {:success-policy :all :failure-policy :any}
                         (sheet/code "a" :fn (fq "parallel-a") :reads [] :writes [:a])
                         (sheet/code "b" :fn (fq "parallel-b") :reads [] :writes [:b])))
            result (sheet/execute ctx (sheet/build-workflow! ctx workflow) {})
            trace (trace-for ctx result 3)
            entries @execution-log]
        (is (= :success (:status result)))
        (is (= {:a 1 :b 2} (select-keys (:outputs result) [:a :b])))
        (is (= 3 (count (:node-traces trace))))
        (is (< (.indexOf entries [:start :b]) (.indexOf entries [:end :a]))
            "b starts before a ends")
        (is (< (.indexOf entries [:start :a]) (.indexOf entries [:end :b]))
            "a starts before b ends")))))

(deftest det-e2e-010-parallel-any-success
  (testing "parallel :any succeeds with one successful branch and traces failures"
    (h/with-async-test-context [ctx]
      (let [workflow (sheet/workflow "det-e2e-010-parallel-any"
                       (sheet/blackboard {:a :int})
                       (sheet/parallel "branches"
                         {:success-policy :any :failure-policy :all}
                         (sheet/code "failure" :fn (fq "fail-parallel") :reads [] :writes [])
                         (sheet/code "success" :fn (fq "parallel-a") :reads [] :writes [:a])))
            result (sheet/execute ctx (sheet/build-workflow! ctx workflow) {})
            trace (trace-for ctx result 3)]
        (is (= :success (:status result)))
        (is (= 1 (get-in result [:outputs :a])))
        (is (= 3 (count (:node-traces trace))))
        (is (= #{:success :failure} (set (node-statuses trace))))))))

(deftest det-e2e-011-parallel-majority-boundaries
  (testing "majority requires strictly more than half of all children"
    (h/with-async-test-context [ctx]
      (let [odd (sheet/workflow "det-e2e-011-majority-odd"
                  (sheet/blackboard {:a :int :b :int})
                  (sheet/parallel "branches"
                    {:success-policy :majority :failure-policy :all}
                    (sheet/code "a" :fn (fq "parallel-a") :reads [] :writes [:a])
                    (sheet/code "b" :fn (fq "parallel-b") :reads [] :writes [:b])
                    (sheet/code "failure" :fn (fq "fail-parallel") :reads [] :writes [])))
            even (sheet/workflow "det-e2e-011-majority-even"
                   (sheet/blackboard {:a :int :b :int})
                   (sheet/parallel "branches"
                     {:success-policy :majority :failure-policy :all}
                     (sheet/code "a" :fn (fq "parallel-a") :reads [] :writes [:a])
                     (sheet/code "b" :fn (fq "parallel-b") :reads [] :writes [:b])
                     (sheet/code "failure-1" :fn (fq "fail-parallel") :reads [] :writes [])
                     (sheet/code "failure-2" :fn (fq "fail-parallel") :reads [] :writes [])))
            odd-result (sheet/execute ctx (sheet/build-workflow! ctx odd) {})
            even-result (sheet/execute ctx (sheet/build-workflow! ctx even) {})]
        (is (= :success (:status odd-result)) "two of three is a majority")
        (is (= :failure (:status even-result)) "two of four is not a majority")
        (is (= :success (:status (trace-for ctx odd-result 4))))
        (is (= :failure (:status (trace-for ctx even-result 5))))))))

(deftest det-e2e-012-parallel-shared-write-resolution
  (testing "parallel shared writes resolve by canonical merge order, not wall-clock completion"
    (h/with-async-test-context [ctx]
      (let [workflow (sheet/workflow "det-e2e-012-parallel-shared-write"
                       (sheet/blackboard {:shared :keyword})
                       (sheet/parallel "branches"
                         {:success-policy :all :failure-policy :any}
                         (sheet/code "first" :fn (fq "write-shared-first")
                           :reads [] :writes [:shared])
                         (sheet/code "second" :fn (fq "write-shared-second")
                           :reads [] :writes [:shared])))
            sheet-id (sheet/build-workflow! ctx workflow)
            results (repeatedly 5 #(sheet/execute ctx sheet-id {}))
            values (mapv #(get-in % [:outputs :shared]) results)]
        (is (every? #(= :success (:status %)) results))
        (is (= 1 (count (set values))) "resolution must be repeatable")
        (is (= :first (first values)) "first sibling wins equal-version merge")
        (doseq [result results]
          (let [trace (trace-for ctx result 3)]
            (is (= 3 (count (:node-traces trace))))
            (is (every? #{:success} (node-statuses trace)))))))))

(deftest det-e2e-013-map-each-sequential
  (testing "sequential map-each preserves order and distinct iteration identities"
    (h/with-async-test-context [ctx]
      (let [workflow (sheet/workflow "det-e2e-013-map-each-sequential"
                       (sheet/blackboard {:items [:vector :int]
                                          :item :int
                                          :results [:vector :int]})
                       (sheet/map-each "double"
                         :from :items :as :item :into :results :parallel 1
                         (sheet/code "double-one" :fn (fq "map-double")
                           :reads [:item] :writes [:item])))
            result (sheet/execute ctx (sheet/build-workflow! ctx workflow)
                                  {:items [1 2 3 4]})
            trace (trace-for ctx result 5)
            leaf-traces (filter #(= :leaf (:node-type %)) (:node-traces trace))]
        (is (= :success (:status result)))
        (is (= [2 4 6 8] (get-in result [:outputs :results])))
        (is (= 4 (count leaf-traces)))
        (is (= 4 (count (set (map :trace-instance-id leaf-traces)))))))))

(deftest det-e2e-014-map-each-bounded-parallel
  (testing "map-each respects its concurrency bound without reordering output"
    (h/with-async-test-context [ctx]
      (reset! concurrency-state {:active 0 :maximum 0})
      (let [workflow (sheet/workflow "det-e2e-014-map-each-bounded"
                       (sheet/blackboard {:items [:vector :int]
                                          :item :int
                                          :results [:vector :int]})
                       (sheet/map-each "double"
                         :from :items :as :item :into :results :parallel 2
                         (sheet/code "double-one" :fn (fq "tracked-map-double")
                           :reads [:item] :writes [:item])))
            result (sheet/execute ctx (sheet/build-workflow! ctx workflow)
                                  {:items [1 2 3 4 5 6]})
            trace (trace-for ctx result 7)]
        (is (= :success (:status result)))
        (is (= [2 4 6 8 10 12] (get-in result [:outputs :results])))
        (is (= 2 (:maximum @concurrency-state)))
        (is (= 6 (count (filter #(= :leaf (:node-type %))
                                (:node-traces trace)))))))))

(deftest det-e2e-015-nested-composites
  (testing "sequence, parallel, map-each, and fallback compose into one complete trace"
    (h/with-async-test-context [ctx]
      (let [workflow (sheet/workflow "det-e2e-015-nested-composites"
                       (sheet/blackboard {:items [:vector :int]
                                          :item :int
                                          :results [:vector :int]
                                          :a :int :b :int
                                          :summary [:map
                                                    [:doubled [:vector :int]]
                                                    [:left :int]
                                                    [:right :int]]})
                       (sheet/sequence "main"
                         (sheet/map-each "double"
                           :from :items :as :item :into :results :parallel 2
                           (sheet/code "double-one" :fn (fq "map-double")
                             :reads [:item] :writes [:item]))
                         (sheet/parallel "scores"
                           {:success-policy :all :failure-policy :any}
                           (sheet/code "a" :fn (fq "parallel-a") :reads [] :writes [:a])
                           (sheet/fallback "b-with-recovery"
                             (sheet/code "fails" :fn (fq "fail-parallel") :reads [] :writes [])
                             (sheet/code "b" :fn (fq "parallel-b") :reads [] :writes [:b])))
                         (sheet/code "summary" :fn (fq "collect-summary")
                           :reads [:results :a :b] :writes [:summary])))
            result (sheet/execute ctx (sheet/build-workflow! ctx workflow)
                                  {:items [1 2 3]})
            trace (trace-for ctx result 11)
            instance-ids (map :trace-instance-id (:node-traces trace))]
        (is (= :success (:status result)))
        (is (= {:doubled [2 4 6] :left 1 :right 2}
               (get-in result [:outputs :summary])))
        (is (= 11 (count (:node-traces trace))))
        (is (= (count instance-ids) (count (set instance-ids))))
        (is (every? #(or (nil? (:parent-trace-instance-id %))
                         (uuid? (:parent-trace-instance-id %)))
                    (:node-traces trace)))))))

(deftest det-e2e-016-delegate-success
  (testing "delegate transfers only declared values and persists parent-child lineage"
    (h/with-async-test-context [ctx]
      (let [child (sheet/workflow "det-e2e-016-child"
                    (sheet/blackboard {:input :string :output :string :secret :string})
                    (sheet/code "copy" :fn (fq "delegate-copy")
                      :reads [:input] :writes [:output]))
            child-id (sheet/build-workflow! ctx child)
            parent (sheet/workflow "det-e2e-016-parent"
                     (sheet/blackboard {:input :string :output :string :secret :string})
                     (sheet/delegate "child"
                       :target-sheet-id child-id
                       :reads [:input]
                       :writes [:output]))
            result (sheet/execute ctx (sheet/build-workflow! ctx parent)
                                  {:input "work" :secret "parent-only"})
            trace (trace-for ctx result)
            family (trace-family ctx (:trace-id result) 2)
            child-trace (second (:traces family))]
        (is (= :success (:status result)))
        (is (= "work-child" (get-in result [:outputs :output])))
        (is (= "parent-only" (get-in result [:outputs :secret])))
        (is (= 1 (count (:child-trace-ids trace))))
        (is (= (:trace-id result) (:root-trace-id child-trace)))
        (is (= (:trace-id result) (:parent-trace-id child-trace)))))))

(deftest det-e2e-017-delegate-child-failure
  (testing "child failure fails the delegate without corrupting parent state"
    (h/with-async-test-context [ctx]
      (let [child (sheet/workflow "det-e2e-017-child"
                    (sheet/blackboard {:input :string :output :string})
                    (sheet/code "fail" :fn (fq "delegate-fail")
                      :reads [:input] :writes [:output]))
            child-id (sheet/build-workflow! ctx child)
            parent (sheet/workflow "det-e2e-017-parent"
                     (sheet/blackboard {:input :string :output :string :guard :string})
                     (sheet/delegate "child"
                       :target-sheet-id child-id :reads [:input] :writes [:output]))
            result (sheet/execute ctx (sheet/build-workflow! ctx parent)
                                  {:input "work" :guard "preserved"})
            trace (trace-for ctx result)
            family (trace-family ctx (:trace-id result) 2)
            child-trace (second (:traces family))]
        (is (= :failure (:status result)))
        (is (= "preserved" (get-in result [:outputs :guard])))
        (is (nil? (get-in result [:outputs :output])))
        (is (= :failure (:status trace)))
        (is (= :failure (:status child-trace)))
        (is (= (:trace-id result) (:parent-trace-id child-trace)))))))

(deftest det-e2e-018-delegate-timeout
  (testing "delegate timeout is bounded and cannot publish a late child write"
    (h/with-async-test-context [ctx]
      (let [child (sheet/workflow "det-e2e-018-child"
                    (sheet/blackboard {:input :string :output :string})
                    (sheet/code "slow" :fn (fq "delegate-slow")
                      :reads [:input] :writes [:output]))
            child-id (sheet/build-workflow! ctx child)
            parent (sheet/workflow "det-e2e-018-parent"
                     (sheet/blackboard {:input :string :output :string})
                     (sheet/delegate "child"
                       :target-sheet-id child-id :reads [:input] :writes [:output]
                       :timeout-ms 30))
            started (System/nanoTime)
            result (sheet/execute ctx (sheet/build-workflow! ctx parent)
                                  {:input "work"} :timeout-ms 2000)
            elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)
            trace (trace-for ctx result)]
        (is (= :timeout (:status result)))
        (is (< elapsed-ms 1000.0))
        (is (nil? (get-in result [:outputs :output])))
        (is (= :timeout (:status trace)))
        (Thread/sleep 350)
        (is (nil? (get-in (h/run-query ctx {:query/name :sheet/node-trace-detail
                                              :trace-id (:trace-id result)
                                              :trace-instance-id (:trace-instance-id
                                                                  (first (:node-traces trace)))})
                           [:query/result :outputs :output])))))))

(deftest det-e2e-019-nested-delegates
  (testing "three delegate levels form one structural trace family"
    (h/with-async-test-context [ctx]
      (let [grandchild (sheet/workflow "det-e2e-019-grandchild"
                         (sheet/blackboard {:input :string :output :string})
                         (sheet/code "copy" :fn (fq "delegate-copy")
                           :reads [:input] :writes [:output]))
            grandchild-id (sheet/build-workflow! ctx grandchild)
            child (sheet/workflow "det-e2e-019-child"
                    (sheet/blackboard {:input :string :output :string})
                    (sheet/delegate "grandchild"
                      :target-sheet-id grandchild-id :reads [:input] :writes [:output]))
            child-id (sheet/build-workflow! ctx child)
            parent (sheet/workflow "det-e2e-019-parent"
                     (sheet/blackboard {:input :string :output :string})
                     (sheet/delegate "child"
                       :target-sheet-id child-id :reads [:input] :writes [:output]))
            result (sheet/execute ctx (sheet/build-workflow! ctx parent) {:input "deep"})
            family (trace-family ctx (:trace-id result) 3)
            traces (:traces family)]
        (is (= :success (:status result)))
        (is (= "deep-child" (get-in result [:outputs :output])))
        (is (= 3 (count traces)))
        (is (every? #(= (:trace-id result) (:root-trace-id %)) traces))
        (is (= 2 (count (filter :parent-trace-id traces))))))))

(deftest det-e2e-020-parallel-delegates
  (testing "parallel delegates remain isolated while sharing one operation correlation"
    (h/with-async-test-context [ctx]
      (let [left (sheet/workflow "det-e2e-020-left"
                   (sheet/blackboard {:input :string :left :string})
                   (sheet/code "left" :fn (fq "delegate-left")
                     :reads [:input] :writes [:left]))
            right (sheet/workflow "det-e2e-020-right"
                    (sheet/blackboard {:input :string :right :string})
                    (sheet/code "right" :fn (fq "delegate-right")
                      :reads [:input] :writes [:right]))
            left-id (sheet/build-workflow! ctx left)
            right-id (sheet/build-workflow! ctx right)
            parent (sheet/workflow "det-e2e-020-parent"
                     (sheet/blackboard {:input :string :left :string :right :string})
                     (sheet/parallel "delegates"
                       {:success-policy :all :failure-policy :any}
                       (sheet/delegate "left" :target-sheet-id left-id
                         :reads [:input] :writes [:left])
                       (sheet/delegate "right" :target-sheet-id right-id
                         :reads [:input] :writes [:right])))
            correlation-id (random-uuid)
            result (sheet/execute (assoc ctx :orc/correlation-id correlation-id)
                                  (sheet/build-workflow! ctx parent) {:input "work"})
            family (trace-family ctx (:trace-id result) 3)
            correlated (do
                         (is (h/settle-until!
                              #(= 3 (count (get-in
                                            (h/run-query ctx
                                              {:query/name :sheet/get-correlated-traces
                                               :correlation-id correlation-id})
                                            [:query/result :traces])))))
                         (get-in (h/run-query ctx
                                   {:query/name :sheet/get-correlated-traces
                                    :correlation-id correlation-id})
                                 [:query/result :traces]))]
        (is (= :success (:status result)))
        (is (= "work-left" (get-in result [:outputs :left])))
        (is (= "work-right" (get-in result [:outputs :right])))
        (is (= 3 (count (:traces family))))
        (is (= 3 (count correlated)))
        (is (= 3 (count (set (map :trace-id correlated)))))))))
