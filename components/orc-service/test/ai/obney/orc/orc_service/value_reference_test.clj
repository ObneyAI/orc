(ns ai.obney.orc.orc-service.value-reference-test
  "End-to-end invariants for canonical values and cross-tick references."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.value-log :as value-log]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.grain.event-store-v3.interface :as es]))

(def ^:private large-value (apply str (repeat 4096 "reference-payload-")))

(defn produce-large [_] {:blob large-value})
(defn copy-input [{:keys [inputs]}] {:output (:input inputs)})

(defn- event-type [events type]
  (filter #(= type (:event/type %)) events))

(defn- setup-nested-producer! [ctx]
  (let [sheet-id (-> (h/run-and-apply! ctx (h/make-create-sheet-command :name "nested-ref"))
                     :command-result/events first :sheet-id)]
    (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :blob :string))
    (let [outer (-> (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
                    :command-result/events first :node-id)
          inner (-> (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence :parent-id outer))
                    :command-result/events first :node-id)
          leaf (-> (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id inner))
                   :command-result/events first :node-id)]
      (h/run-and-apply! ctx (h/make-set-node-executor-command
                             sheet-id leaf :code
                             :fn "ai.obney.orc.orc-service.value-reference-test/produce-large"))
      (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf [] [:blob]))
      {:sheet-id sheet-id :leaf leaf :sequences #{outer inner}})))

(defn- setup-copy-sheet! [ctx name]
  (let [sheet-id (-> (h/run-and-apply! ctx (h/make-create-sheet-command :name name))
                     :command-result/events first :sheet-id)]
    (doseq [k [:input :output]]
      (h/run-and-apply! ctx (h/make-declare-key-command sheet-id k :string)))
    (let [root (-> (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
                   :command-result/events first :node-id)
          leaf (-> (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id root))
                   :command-result/events first :node-id)]
      (h/run-and-apply! ctx (h/make-set-node-executor-command
                             sheet-id leaf :code
                             :fn "ai.obney.orc.orc-service.value-reference-test/copy-input"))
      (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf [:input] [:output]))
      {:sheet-id sheet-id :leaf leaf})))

(defn- setup-delegate-sheet! [ctx child-id name]
  (let [sheet-id (-> (h/run-and-apply! ctx (h/make-create-sheet-command :name name))
                     :command-result/events first :sheet-id)]
    (doseq [k [:input :output]]
      (h/run-and-apply! ctx (h/make-declare-key-command sheet-id k :string)))
    (let [root (-> (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
                   :command-result/events first :node-id)
          delegate (-> (h/run-and-apply! ctx (h/make-create-node-command sheet-id :delegate :parent-id root))
                       :command-result/events first :node-id)]
      (h/run-and-apply! ctx (h/make-set-delegate-config-command
                             sheet-id delegate child-id
                             :reads [:input] :writes [:output]))
      {:sheet-id sheet-id :delegate delegate})))

(deftest nested-sequences-forward-one-canonical-write
  (testing "leaf bytes are stored once while every sequence retains provenance"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id leaf sequences]} (setup-nested-producer! ctx)
            result (runtime/execute ctx sheet-id {})
            events (h/read-all-events ctx)
            writes (filter #(and (= :sheet/execution-value-written (:event/type %))
                                 (= :blob (:key %))) events)
            completions (filter #(and (= :sheet/node-execution-completed (:event/type %))
                                      (sequences (:node-id %))) events)
            canonical (first writes)
            expected-source {:tick-id (:tick-id canonical)
                             :event-id (:event/id canonical)}]
        (is (= :success (:status result)))
        (is (= large-value (get-in result [:outputs :blob])))
        (is (= 1 (count writes)) "only the leaf owns the value bytes")
        (is (= leaf (:node-id canonical)))
        (is (= 2 (count completions)))
        (is (every? #(= expected-source (get-in % [:write-sources :blob])) completions))
        (is (every? #(not (contains? % :writes)) completions))))))

(deftest delegate-input-and-output-use-cross-tick-references
  (testing "a delegate neither snapshots its input nor copies its child output"
    (h/with-async-test-context [ctx]
      (let [{child-id :sheet-id} (setup-copy-sheet! ctx "ref-child")
            parent-id (-> (h/run-and-apply! ctx (h/make-create-sheet-command :name "ref-parent"))
                          :command-result/events first :sheet-id)]
        (doseq [k [:input :output]]
          (h/run-and-apply! ctx (h/make-declare-key-command parent-id k :string)))
        (let [root (-> (h/run-and-apply! ctx (h/make-create-node-command parent-id :sequence))
                       :command-result/events first :node-id)
              delegate (-> (h/run-and-apply! ctx (h/make-create-node-command parent-id :delegate :parent-id root))
                           :command-result/events first :node-id)]
          (h/run-and-apply! ctx (h/make-set-delegate-config-command
                                 parent-id delegate child-id
                                 :reads [:input] :writes [:output]))
          (let [result (runtime/execute ctx parent-id {:input large-value})
                events (h/read-all-events ctx)
                starts (event-type events :sheet/tree-tick-started)
                input-writes (filter #(and (= :sheet/execution-value-written (:event/type %))
                                           (= :input (:key %))) events)
                output-writes (filter #(and (= :sheet/execution-value-written (:event/type %))
                                            (= :output (:key %))) events)
                refs (filter #(and (= :sheet/execution-value-referenced (:event/type %))
                                   (= :output (:key %))) events)]
            (is (= :success (:status result)))
            (is (= large-value (get-in result [:outputs :output])))
            (is (not (contains? result :output-sources)) "public API hides internal provenance")
            (is (= 1 (count input-writes)) "child tick references the parent input seed")
            (is (= 1 (count output-writes)) "child leaf is the sole output owner")
            (is (= 1 (count refs)) "parent blackboard receives one lightweight reference")
            (is (every? #(not (clojure.string/includes? (pr-str %) large-value)) starts)
                "tick-started events contain structure and references, never input bytes")
            (is (= large-value
                   (value-log/resolve-source (:event-store ctx) (:tenant-id ctx)
                                             (:source (first refs)))))))))))

(deftest reference-resolution-is-safe-and-transitive
  (testing "logical pre-append IDs, chains, missing targets, and cycles"
    (h/with-async-test-context [ctx]
      (let [store (:event-store ctx) tenant (:tenant-id ctx)
            sheet (random-uuid) tick-a (random-uuid) tick-b (random-uuid)
            tick-c (random-uuid) tick-d (random-uuid)
            value-id (random-uuid) ref-id (random-uuid)
            cycle-c-id (random-uuid) cycle-d-id (random-uuid)
            source {:tick-id tick-a :event-id value-id}
            value-event (es/->event {:type :sheet/execution-value-written
                                     :tags #{[:sheet sheet] [:tick tick-a]}
                                     :body {:sheet-id sheet :tick-id tick-a :key :k
                                            :value large-value :value-id value-id}})
            ref-event (es/->event {:type :sheet/execution-value-referenced
                                   :tags #{[:sheet sheet] [:tick tick-b]}
                                   :body {:sheet-id sheet :tick-id tick-b :key :k
                                          :source source :value-id ref-id}})
            cycle-c (es/->event {:type :sheet/execution-value-referenced
                                 :tags #{[:sheet sheet] [:tick tick-c]}
                                 :body {:sheet-id sheet :tick-id tick-c :key :cycle
                                        :value-id cycle-c-id
                                        :source {:tick-id tick-d :event-id cycle-d-id}}})
            cycle-d (es/->event {:type :sheet/execution-value-referenced
                                 :tags #{[:sheet sheet] [:tick tick-d]}
                                 :body {:sheet-id sheet :tick-id tick-d :key :cycle
                                        :value-id cycle-d-id
                                        :source {:tick-id tick-c :event-id cycle-c-id}}})]
        ;; Reference first proves resolution does not depend on append or UUID order.
        (es/append store {:tenant-id tenant :events [ref-event]})
        (es/append store {:tenant-id tenant :events [value-event]})
        (is (= large-value (value-log/resolve-source store tenant source)))
        (is (= large-value
               (value-log/resolve-source store tenant
                                         {:tick-id tick-b :event-id ref-id})))
        (is (nil? (value-log/resolve-source store tenant
                                            {:tick-id tick-a :event-id (random-uuid)})))
        (es/append store {:tenant-id tenant :events [cycle-c cycle-d]})
        (is (nil? (value-log/resolve-source store tenant
                                            {:tick-id tick-c :event-id cycle-c-id})))))))

(deftest concurrent-delegates-remain-linear-and-isolated
  (testing "concurrency creates one input, output, and reference per execution"
    (h/with-async-test-context [ctx]
      (let [{child-id :sheet-id} (setup-copy-sheet! ctx "concurrent-ref-child")
            {parent-id :sheet-id} (setup-delegate-sheet! ctx child-id "concurrent-ref-parent")
            payloads (mapv #(str large-value %) (range 8))
            runs (mapv (fn [payload]
                         (future (runtime/execute ctx parent-id {:input payload}
                                                  :timeout-ms 20000)))
                       payloads)
            results (mapv deref runs)
            events (h/read-all-events ctx)]
        (is (= (repeat 8 :success) (map :status results)))
        (is (= payloads (mapv #(get-in % [:outputs :output]) results)))
        (is (= 8 (count (filter #(and (= :sheet/execution-value-written (:event/type %))
                                      (= :input (:key %))) events))))
        (is (= 8 (count (filter #(and (= :sheet/execution-value-written (:event/type %))
                                      (= :output (:key %))) events))))
        (is (= 8 (count (filter #(and (= :sheet/execution-value-referenced (:event/type %))
                                      (= :output (:key %))) events))))))))
