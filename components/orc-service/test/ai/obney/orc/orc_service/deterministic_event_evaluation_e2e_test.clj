(ns ai.obney.orc.orc-service.deterministic-event-evaluation-e2e-test
  "Deterministic end-to-end coverage for event replay and unified judges."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.evaluation.interface :as evaluation]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.commands]
            [ai.obney.orc.evaluation.core.judge-runtime :as judge-runtime]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(def ^:private excellent-tree
  [:sequence
   [:map-each {:from :items :as :item :into :results}
    [:llm {:reads [:item] :writes [:result]}]]
   [:final {:keys [:results]}]])

(def ^:private host-io-schema
  [:map
   [:generated-tree-raw {:optional true} [:= excellent-tree]]
   [:answer {:optional true} :string]])

(def ^:private host-trace-schema
  [:vector [:map [:node-id {:optional true} :uuid]]])

(def ^:private dimension-schema
  [:map
   [:name :string]
   [:weight {:optional true} :double]
   [:score :double]
   [:feedback :string]])

(defn produce-tree [_]
  {:generated-tree-raw excellent-tree})

(defn copy-value [{:keys [inputs]}]
  {:output (:input inputs)})

(defn score-low [_]
  {:score 0.2 :feedback "low deterministic score"
   :dimensions [{:name "low" :weight 1.0 :score 0.2 :feedback "fixture"}]})

(defn score-high [_]
  {:score 0.8 :feedback "high deterministic score"
   :dimensions [{:name "high" :weight 1.0 :score 0.8 :feedback "fixture"}]})

(defn score-from-host [{:keys [inputs]}]
  (let [host-outputs (:host-outputs inputs)]
    {:score (if (contains? host-outputs :generated-tree-raw) 0.9 0.1)
     :feedback (str "typed-host-keys=" (sort (keys host-outputs)))
     :dimensions [{:name "typed-host-io" :score 0.9 :feedback "received"}]}))

(defn score-throws [_]
  (throw (ex-info "deliberate deterministic judge failure" {})))

(defn- fq [function-name]
  (str "ai.obney.orc.orc-service.deterministic-event-evaluation-e2e-test/"
       function-name))

(defn- all-events [ctx]
  (h/read-all-events ctx))

(defn- events-of-type [ctx event-type]
  (into [] (es/read (:event-store ctx)
                    {:tenant-id (:tenant-id ctx) :types #{event-type}})))

(defn- wait-events [ctx event-type tick-id expected]
  (let [matching #(filterv (fn [event] (= tick-id (:tick-id event)))
                           (events-of-type ctx event-type))]
    (is (h/settle-until! #(>= (count (matching)) expected)
                         :timeout-ms 15000)
        (str "did not observe " expected " " event-type " events"))
    (matching)))

(defn- enable-evaluation! [ctx enabled?]
  (let [result (cp/process-command
                (assoc ctx :command
                       {:command/name :ontology/set-living-description-enabled
                        :command/id (random-uuid)
                        :command/timestamp (time/now)
                        :enabled? enabled?}))]
    (is (not (h/is-anomaly? result)))
    (is (h/settle-until! #(= enabled? (ontology/get-living-description-enabled? ctx))))))

(defn- node-id-named [ctx sheet-id node-name]
  (some #(when (= node-name (:name %)) (:id %))
        (sheet/get-nodes-for-sheet ctx sheet-id)))

(defn- structural-host! [ctx name judge-defs judge-names]
  (sheet/build-workflow!
   ctx
   (sheet/workflow name
     (sheet/blackboard {:generated-tree-raw [:= excellent-tree]})
     (sheet/judges judge-defs)
     (sheet/code "host" :fn (fq "produce-tree")
       :writes [:generated-tree-raw]
       :judges judge-names))))

(defn- eval-workflow! [ctx name function-name]
  (sheet/build-workflow!
   ctx
   (sheet/workflow name
     (sheet/blackboard {:host-inputs host-io-schema
                        :host-outputs host-io-schema
                        :host-instruction :string
                        :host-trace host-trace-schema
                        :score :double
                        :feedback :string
                        :dimensions [:vector dimension-schema]})
     (sheet/code "evaluate" :fn (fq function-name)
       :reads [:host-inputs :host-outputs :host-instruction :host-trace]
       :writes [:score :feedback :dimensions]))))

(deftest det-e2e-073-command-event-projection
  (testing "real build and execution mutations are schema-valid events and reconstruct public state"
    (h/with-async-test-context [ctx]
      (let [definition (sheet/workflow "det-e2e-073-command-event-projection"
                         (sheet/blackboard {:input :string :output :string})
                         (sheet/sequence "main"
                           (sheet/code "copy" :fn (fq "copy-value")
                             :reads [:input] :writes [:output])))
            sheet-id (sheet/build-workflow! ctx definition)
            result (sheet/execute ctx sheet-id {:input "event-sourced"})]
        (is (= :success (:status result)))
        (is (h/settle-until! #(h/trace-stored? ctx (:trace-id result))))
        (let [events (all-events ctx)
              mutation-events (filter #(or (= sheet-id (:sheet-id %))
                                           (= (:trace-id result) (:trace-id %))) events)
              unregistered (filter #(nil? (get @schema-util/registry* (:event/type %)))
                                   mutation-events)
              invalid (filter (fn [event]
                                (when-let [schema (get @schema-util/registry* (:event/type event))]
                                  (not (m/validate schema event))))
                              mutation-events)]
          (is (seq mutation-events))
          (is (empty? unregistered)
              (str "mutation events without registered schemas: "
                   (mapv :event/type unregistered)))
          (is (empty? invalid)
              (str "schema-invalid mutation events: " (mapv :event/type invalid)))
          (is (= sheet-id (:id (sheet/get-sheet ctx sheet-id))))
          (is (= 2 (count (sheet/get-nodes-for-sheet ctx sheet-id))))
          (is (= #{:input :output}
                 (set (keys (sheet/get-blackboard-by-key ctx sheet-id)))))
          (is (= :success
                 (get-in (h/run-query ctx (h/make-get-trace-query (:trace-id result)))
                         [:query/result :trace :status]))))))))

(deftest det-e2e-074-projection-replay
  (testing "clean reductions over the event stream reproduce all relevant public query state"
    (h/with-async-test-context [ctx]
      (let [definition (sheet/workflow "det-e2e-074-replay"
                         (sheet/blackboard {:input :string :output :string})
                         (sheet/sequence "main"
                           (sheet/code "copy" :fn (fq "copy-value")
                             :reads [:input] :writes [:output])))
            sheet-id (sheet/build-workflow! ctx definition)
            _ (h/run-and-apply! ctx (h/make-set-key-value-command sheet-id :input "projected"))
            result (sheet/execute ctx sheet-id {})]
        (is (h/settle-until! #(h/trace-stored? ctx (:trace-id result))))
        (let [events (all-events ctx)
              replayed-sheet (get (reduce rm/sheets* {} events) sheet-id)
              replayed-nodes (->> (vals (reduce rm/nodes* {} events))
                                  (filterv #(= sheet-id (:sheet-id %))))
              replayed-bb (->> (vals (reduce rm/blackboard* {} events))
                               (filter #(= sheet-id (:sheet-id %)))
                               (map (juxt :key identity))
                               (into {}))
              replayed-trace (get (reduce rm/traces* {} events) (:trace-id result))
              live-nodes (sheet/get-nodes-for-sheet ctx sheet-id)]
          (is (= (sheet/get-sheet ctx sheet-id) replayed-sheet))
          (is (= (into {} (map (juxt :id identity)) live-nodes)
                 (into {} (map (juxt :id identity)) replayed-nodes)))
          (is (= (sheet/get-blackboard-by-key ctx sheet-id) replayed-bb))
          (is (= (get-in (h/run-query ctx (h/make-get-trace-query (:trace-id result)))
                         [:query/result :trace])
                 replayed-trace))
          (is (= "projected" (get-in result [:outputs :output]))))))))

(deftest det-e2e-075-judge-opt-in-disabled
  (testing "an attached deterministic judge emits no score while evaluation is disabled"
    (h/with-async-test-context [ctx]
      (is (false? (ontology/get-living-description-enabled? ctx)))
      (let [sheet-id (structural-host! ctx "det-e2e-075-disabled"
                                       {:structure {:type :heuristic-structural}}
                                       ["structure"])
            result (sheet/execute ctx sheet-id {})]
        (is (= :success (:status result)))
        (is (h/settle-until! #(h/trace-stored? ctx (:trace-id result))))
        (Thread/sleep 250)
        (is (empty? (filter #(= (:trace-id result) (:tick-id %))
                            (events-of-type ctx :judge/score-emitted))))))))

(deftest det-e2e-076-deterministic-structural-judge
  (testing "known tree shape emits the exact heuristic score, dimensions, and projected result"
    (h/with-async-test-context [ctx]
      (enable-evaluation! ctx true)
      (let [sheet-id (structural-host! ctx "det-e2e-076-structure"
                                       {:structure {:type :heuristic-structural}}
                                       ["structure"])
            node-id (node-id-named ctx sheet-id "host")
            result (sheet/execute ctx sheet-id {})
            scores (wait-events ctx :judge/score-emitted (:trace-id result) 1)
            score (first scores)]
        (is (= 1 (count scores)))
        (is (= "structure" (:judge-name score)))
        (is (= 0.5 (:score score)))
        (is (= ["Structure" "Decomposition"] (mapv :name (:dimensions score))))
        (is (= [1.0 1.0] (mapv :score (:dimensions score))))
        (is (= [(select-keys score [:sheet-id :tick-id :node-id :judge-name
                                    :judge-config :score :feedback :dimensions :emitted-at])]
               (evaluation/get-judge-scores ctx sheet-id node-id (:trace-id result))))))))

(deftest det-e2e-077-multiple-deterministic-judges
  (testing "one throwing custom judge cannot suppress a successful structural judge"
    (h/with-async-test-context [ctx]
      (enable-evaluation! ctx true)
      (let [throwing-sheet (eval-workflow! ctx "det-e2e-077-throwing-eval" "score-throws")
            sheet-id (structural-host! ctx "det-e2e-077-isolation"
                                       {:structure {:type :heuristic-structural}
                                        :throwing {:type :custom :sheet-id throwing-sheet}}
                                       ["structure" "throwing"])
            result (sheet/execute ctx sheet-id {})
            scores (wait-events ctx :judge/score-emitted (:trace-id result) 1)]
        (Thread/sleep 250)
        (is (= :success (:status result)))
        (is (= 1 (count scores)))
        (is (= ["structure"] (mapv :judge-name scores)))
        (is (= 0.5 (:score (first scores))))))))

(deftest det-e2e-078-judge-score-idempotency
  (testing "duplicate delivery of a completion yields one score per identity tuple"
    (h/with-async-test-context [ctx]
      (enable-evaluation! ctx true)
      (let [sheet-id (structural-host! ctx "det-e2e-078-idempotency"
                                       {:structure {:type :heuristic-structural}}
                                       ["structure"])
            result (sheet/execute ctx sheet-id {})
            _ (wait-events ctx :judge/score-emitted (:trace-id result) 1)
            completion (some #(when (and (= :sheet/node-execution-completed (:event/type %))
                                         (= :leaf (:node-type %))) %)
                             (h/read-tick-events ctx (:trace-id result)))]
        (is (some? completion))
        (es/append (:event-store ctx)
                   {:tenant-id (:tenant-id ctx) :events [completion]})
        (Thread/sleep 500)
        (let [scores (filterv #(= (:trace-id result) (:tick-id %))
                              (events-of-type ctx :judge/score-emitted))]
          (is (= 1 (count scores)))
          (is (= 1 (count (set (map (juxt :sheet-id :node-id :tick-id :judge-name)
                                      scores))))))))))

(deftest det-e2e-079-custom-code-judge-and-recursion-guard
  (testing "typed host IO reaches a real code judge and max-depth guard skips re-entry"
    (h/with-async-test-context [ctx]
      (enable-evaluation! ctx true)
      (let [eval-sheet (eval-workflow! ctx "det-e2e-079-eval" "score-from-host")
            host-sheet (structural-host! ctx "det-e2e-079-host"
                                         {:typed {:type :custom :sheet-id eval-sheet}}
                                         ["typed"])
            result (sheet/execute ctx host-sheet {})
            scores (wait-events ctx :judge/score-emitted (:trace-id result) 1)
            invoke-custom @#'ai.obney.orc.evaluation.core.judge-runtime/invoke-custom-judge
            before (count (all-events ctx))
            guarded (invoke-custom
                     (assoc ctx
                            :ai.obney.orc.evaluation.core.judge-runtime/judge-depth 1)
                     {:type :custom :sheet-id eval-sheet}
                     {:inputs {:fixture true}
                      :outputs {:generated-tree-raw excellent-tree}
                      :instruction "typed"})]
        (is (= 1 (count scores)))
        (is (= 0.9 (:score (first scores))))
        (is (re-find #"generated-tree-raw" (:feedback (first scores))))
        (is (nil? guarded))
        (Thread/sleep 150)
        (is (= before (count (all-events ctx)))
            "depth guard prevents the real evaluation sheet from sub-executing")))))

(defn- composite-run! [ctx name judges]
  (let [low (eval-workflow! ctx (str name "-low") "score-low")
        high (eval-workflow! ctx (str name "-high") "score-high")
        configs (into {}
                      (map (fn [[judge-name weight eval-id]]
                             [(keyword judge-name)
                              (cond-> {:type :custom :sheet-id eval-id}
                                weight (assoc :weight weight))])
                           [["low" (:low judges) low]
                            ["high" (:high judges) high]]))
        sheet-id (structural-host! ctx name configs ["low" "high"])
        result (sheet/execute ctx sheet-id {})]
    {:sheet-id sheet-id :result result
     :scores (wait-events ctx :judge/score-emitted (:trace-id result) 2)
     :composites (wait-events ctx :judge/composite-score-computed (:trace-id result) 1)}))

(deftest det-e2e-080-composite-score
  (testing "explicit and default weights aggregate deterministically and duplicate completion is idempotent"
    (h/with-async-test-context [ctx]
      (enable-evaluation! ctx true)
      (let [weighted (composite-run! ctx "det-e2e-080-weighted" {:low 1 :high 3})
            defaulted (composite-run! ctx "det-e2e-080-default" {})
            weighted-event (first (:composites weighted))
            default-event (first (:composites defaulted))
            completion (some #(when (and (= :sheet/node-execution-completed (:event/type %))
                                         (= :leaf (:node-type %))) %)
                             (h/read-tick-events ctx (get-in weighted [:result :trace-id])))]
        (is (= [0.2 0.8] (sort (map :score (:scores weighted)))))
        (is (= 0.65 (:composite-score weighted-event)))
        (is (= [0.25 0.75]
               (sort (map :weight (:contributing-judges weighted-event)))))
        (is (= 0.5 (:composite-score default-event)))
        (is (= [0.5 0.5]
               (sort (map :weight (:contributing-judges default-event)))))
        (es/append (:event-store ctx)
                   {:tenant-id (:tenant-id ctx) :events [completion]})
        (Thread/sleep 500)
        (is (= 1 (count (filter #(= (get-in weighted [:result :trace-id]) (:tick-id %))
                                (events-of-type ctx :judge/composite-score-computed)))))
        (let [node-id (node-id-named ctx (:sheet-id weighted) "host")]
          (is (= 2 (count (evaluation/get-judge-scores
                           ctx (:sheet-id weighted) node-id
                           (get-in weighted [:result :trace-id]))))))))))
