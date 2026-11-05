(ns ai.obney.orc.evaluation.judge-attachment-scope-e2e-test
  "S9a: a judge attached to a SEQUENCE and a judge attached to a DELEGATE each
   assess that composite's completion, through the live processor path
   (build-workflow! -> execute -> :sheet/node-execution-completed ->
   evaluation processor -> :judge/score-emitted). The custom judge workflow
   records what the evaluation runtime handed it, so the composite's judge
   inputs are visible."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.evaluation.interface]
            [ai.obney.orc.ontology.interface]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]))

(def ^:private judge-calls (atom []))

(defn step [{:keys [inputs]}] {:mid (str "mid:" (:request inputs))})
(defn finish [{:keys [inputs]}] {:answer (str "answer:" (:mid inputs))})

(defn record-judge
  "Custom judge body: records everything the runtime handed the judge."
  [{:keys [inputs]}]
  (swap! judge-calls conj inputs)
  {:score 0.75 :feedback "recorded"})

(def ^:private io [:map-of :keyword [:string {:description "any value"}]])

(defn- judge-workflow []
  (sheet/workflow (str "s9a-e2e-judge-" (random-uuid))
    (sheet/blackboard {:host-inputs io
                       :host-outputs io
                       :host-instruction [:string {:description "Host instruction"}]
                       :host-trace [:vector [:map [:node-id {:optional true} :uuid]]]
                       :score :double
                       :feedback [:string {:description "Feedback"}]})
    (sheet/code "record" :fn "ai.obney.orc.evaluation.judge-attachment-scope-e2e-test/record-judge"
      :reads [:host-inputs :host-outputs :host-instruction :host-trace]
      :writes [:score :feedback])))

(defn- wait-for-scores [ctx pred n]
  (let [deadline (+ (System/currentTimeMillis) 30000)]
    (loop []
      (let [evts (filterv pred (into [] (es/read (:event-store ctx)
                                                 {:types #{:judge/score-emitted}
                                                  :tenant-id (:tenant-id ctx)})))]
        (if (or (>= (count evts) n) (>= (System/currentTimeMillis) deadline))
          evts
          (do (Thread/sleep 100) (recur)))))))

(deftest judges-on-a-sequence-and-a-delegate-assess-their-completions
  (h/with-async-test-context [ctx]
    (reset! judge-calls [])
    (h/run-and-apply! ctx {:command/name :ontology/set-living-description-enabled
                           :command/id (random-uuid)
                           :command/timestamp (time/now)
                           :enabled? true})
    (Thread/sleep 100)
    (let [bb {:request [:string {:description "Input"}]
              :mid [:string {:description "Intermediate"}]
              :answer [:string {:description "Final"}]}
          judge-sheet (sheet/build-workflow! ctx (judge-workflow))
          child (sheet/build-workflow! ctx
                  (sheet/workflow "s9a-e2e-child"
                    (sheet/blackboard bb)
                    (sheet/code "finish" :fn "ai.obney.orc.evaluation.judge-attachment-scope-e2e-test/finish"
                      :reads [:mid] :writes [:answer])))
          parent (sheet/build-workflow! ctx
                   (sheet/workflow "s9a-e2e-parent"
                     (sheet/blackboard bb)
                     (sheet/judges {:seq-judge {:type :custom :sheet-id judge-sheet}
                                    :del-judge {:type :custom :sheet-id judge-sheet}})
                     (sheet/sequence "pipeline" :judges ["seq-judge"]
                       (sheet/code "step" :fn "ai.obney.orc.evaluation.judge-attachment-scope-e2e-test/step"
                         :reads [:request] :writes [:mid])
                       (sheet/delegate "specialist" :target-sheet-id child
                         :reads [:mid] :writes [:answer] :judges ["del-judge"]))))
          nodes (sheet/get-nodes-for-sheet ctx parent)
          node-id (fn [n] (:id (some #(when (= n (:name %)) %) nodes)))
          result (sheet/execute ctx parent {:request "hello"} :timeout-ms 60000)
          seq-scores (wait-for-scores ctx #(and (= (node-id "pipeline") (:node-id %))
                                                (= "seq-judge" (:judge-name %))) 1)
          del-scores (wait-for-scores ctx #(and (= (node-id "specialist") (:node-id %))
                                                (= "del-judge" (:judge-name %))) 1)]
      (is (= :success (:status result)) (pr-str result))
      (is (= 1 (count seq-scores)) "the sequence's judge scored its completion")
      (is (= 1 (count del-scores)) "the delegate's judge scored its completion")
      (is (= 0.75 (:score (first seq-scores))))
      (is (= 0.75 (:score (first del-scores))))
      ;; Recorded for the next slice: what the runtime hands a composite's judge.
      (println "S9A-HOST-CALLS" (pr-str @judge-calls) "pipeline" (node-id "pipeline") "specialist" (node-id "specialist")))))
