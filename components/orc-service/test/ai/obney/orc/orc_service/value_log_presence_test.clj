(ns ai.obney.orc.orc-service.value-log-presence-test
  "Canonical reference fixtures; the public LLM workflow proof lives in
   structured-contract-e2e-test. No model or resolver is mocked here."
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.read-models :as service-read-models]))

(defn- event [type sheet tick body]
  (es/->event {:type type :tags #{[:sheet sheet] [:tick tick]}
              :body (merge {:sheet-id sheet :tick-id tick} body)}))

(deftest canonical-null-retains-presence-across-resolution-surfaces
  (h/with-async-test-context [ctx]
    (let [store (:event-store ctx) tenant (:tenant-id ctx)
          sheet (random-uuid) origin (random-uuid) tick (random-uuid)
          node (random-uuid) value-id (random-uuid) ref-id (random-uuid)
          cycle-a (random-uuid) cycle-b (random-uuid)
          source {:tick-id origin :event-id value-id}
          transitive {:tick-id tick :event-id ref-id}
          missing {:tick-id origin :event-id (random-uuid)}
          foreign {:tick-id (random-uuid) :event-id value-id}
          cyclic {:tick-id tick :event-id cycle-a}
          execution {:ai.obney.orc.orc-service.core.todo-processors/map-each-index 0
                     :ai.obney.orc.orc-service.core.todo-processors/map-each-parent (random-uuid)}
          metadata {:direct {:key :direct :source source}
                    :transitive {:key :transitive :source transitive}
                    :seed {:key :seed}
                    :missing {:key :missing :source missing}
                    :foreign {:key :foreign :source foreign}
                    :cyclic {:key :cyclic :source cyclic}}
          appended (es/append store
                     {:tenant-id tenant
                      :events [(event :sheet/execution-value-written sheet origin
                                      {:key :original :value nil :value-id value-id})
                               (event :sheet/tree-tick-started sheet tick
                                      {:seed-sources {:seed source :missing missing}
                                       :execution-snapshot {:blackboard-entries {} :nodes-by-id {}
                                                            :root-node-id nil}})
                               (event :sheet/execution-value-written sheet tick
                                      {:node-id node :key :direct :value "old" :value-id (random-uuid)})
                               (event :sheet/execution-value-written sheet tick
                                      {:node-id node :key :direct :value nil :value-id (random-uuid)})
                               (event :sheet/execution-value-written sheet tick
                                      {:node-id node :key :flag :value false :value-id (random-uuid)})
                               (event :sheet/execution-value-referenced sheet tick
                                      {:node-id node :key :transitive :source source :value-id ref-id})
                               (event :sheet/execution-value-referenced sheet tick
                                      {:node-id node :key :missing :source missing :value-id (random-uuid)})
                               (event :sheet/execution-value-referenced sheet tick
                                      {:node-id node :key :foreign :source foreign :value-id (random-uuid)})
                               (event :sheet/execution-value-referenced sheet tick
                                      {:node-id node :key :cyclic :source {:tick-id tick :event-id cycle-b}
                                       :value-id cycle-a})
                               (event :sheet/execution-value-referenced sheet tick
                                      {:node-id node :key :cyclic :source cyclic :value-id cycle-b})
                               (event :sheet/execution-value-written sheet tick
                                      {:node-id node :key :item :value nil :input-seed? true
                                       :exec-context execution :value-id (random-uuid)})]})]
      (is (nil? (:cognitect.anomalies/category appended)))
      (is (= {:direct nil :flag false :transitive nil}
             (service-read-models/resolve-writes ctx tenant tick {:node-id node :inputs {}})))
      (is (= {:seed nil} (service-read-models/tick-seeds ctx tenant tick)))
      (is (= {:direct nil :transitive nil :seed nil}
             (service-read-models/resolve-values ctx tenant tick metadata nil)))
      (is (= {:direct nil :transitive nil :seed nil}
             (service-read-models/resolve-reads ctx tenant tick
               {:read-keys [:direct :transitive :seed :missing :foreign :cyclic :never]
                :read-sources {:direct source :transitive transitive :missing missing
                               :foreign foreign :cyclic cyclic}})))
      (is (= {:item nil :seed nil}
             (service-read-models/resolve-reads ctx tenant tick
               {:inputs execution :read-keys [:item :seed :never]})))
      (is (= {:seed nil :direct nil :flag false :transitive nil :item nil}
             (service-read-models/final-values ctx tenant tick)))
      (let [hydrated (service-read-models/hydrate-blackboard ctx tenant tick metadata)]
        (doseq [k [:direct :transitive :seed]]
          (is (contains? (get hydrated k) :value))
          (is (nil? (get-in hydrated [k :value]))))
        (doseq [k [:missing :foreign :cyclic]]
          (is (not (contains? (get hydrated k) :value)))))
      (is (nil? (service-read-models/resolve-source ctx tenant source)))
      (doseq [bad [missing foreign cyclic]]
        (is (nil? (service-read-models/resolve-source ctx tenant bad))))
      (is (= {} (service-read-models/resolve-writes ctx (random-uuid) tick {:node-id node :inputs {}}))))))
