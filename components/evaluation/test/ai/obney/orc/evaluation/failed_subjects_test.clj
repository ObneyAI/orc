(ns ai.obney.orc.evaluation.failed-subjects-test
  "S14 (Q22): a failed execution is RECORDED, not graded. A completion whose
   status is :failure, :timeout or :blocked still has its assessments requested
   (coverage stays honest) but each ends UNGRADABLE with reason :subject-failed:
   no judge workflow runs and no model is called. A judge that declares
   :assess-failures? true is judged on failed completions like any other.
   Driven through the public flow: build -> execute (or a completion command)
   -> assessments / performance."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.evaluation.interface :as evaluation]
            [ai.obney.orc.ontology.interface]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.grain.event-store-v3.interface :as es]))

(def ^:private judge-calls
  "What the evaluation runtime handed the judge workflow, one entry per run."
  (atom []))

(defn boom [_] (throw (ex-info "producer failed" {})))
(defn ok [{:keys [inputs]}] {:answer (str "answer:" (:request inputs))})
(defn record-judge [{:keys [inputs]}]
  (swap! judge-calls conj inputs)
  {:score 0.75 :feedback "recorded"})

(def ^:private io [:map-of :keyword [:any {:description "any value"}]])

(defn- fq [n] (str "ai.obney.orc.evaluation.failed-subjects-test/" n))

(defn- judge-workflow []
  (sheet/workflow (str "s14-judge-" (random-uuid))
    (sheet/blackboard {:host-inputs io :host-outputs io
                       :host-instruction [:string {:description "Host instruction"}]
                       :original-task io
                       :score :double
                       :feedback [:string {:description "Feedback"}]})
    (sheet/code "record" :fn (fq "record-judge")
      :reads [:host-inputs :host-outputs :host-instruction :original-task]
      :writes [:score :feedback])))

(def ^:private bb {:request [:string {:description "Input"}]
                   :answer [:string {:description "Final"}]})

(defn- node-id [ctx sheet-id n]
  (:id (some #(when (= n (:name %)) %) (sheet/get-nodes-for-sheet ctx sheet-id))))

(defn- events-of [ctx types]
  (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :types types})))

(defn- settled-assessments
  "The assessments of `node-id`, once `n` of them are no longer pending."
  [ctx node-id n]
  (let [ours #(filterv (fn [a] (and (= node-id (:node-id a)) (not= :pending (:status a))))
                       (evaluation/get-assessments ctx {}))]
    (is (h/settle-until! #(>= (count (ours)) n) :timeout-ms 60000)
        (str "expected " n " settled assessments"))
    (ours)))

(defn- failing-leaf-sheet! [ctx judge-config]
  (let [judge (sheet/build-workflow! ctx (judge-workflow))
        sheet-id (sheet/build-workflow! ctx
                   (sheet/workflow (str "s14-failing-" (random-uuid))
                     (sheet/blackboard bb)
                     (sheet/judges {:quality (assoc judge-config :type :custom :sheet-id judge)})
                     (sheet/code "producer" :fn (fq "boom") :reads [:request] :writes [:answer]
                       :judges ["quality"])))]
    sheet-id))

(deftest a-failed-leaf-is-recorded-ungradable-not-judged
  (h/with-async-test-context [ctx]
    (reset! judge-calls [])
    (let [sheet-id (failing-leaf-sheet! ctx {})
          result (sheet/execute ctx sheet-id {:request "hi"} :timeout-ms 60000)
          producer (node-id ctx sheet-id "producer")
          assessed (settled-assessments ctx producer 1)]
      (is (= :failure (:status result)) (pr-str result))
      (is (= 1 (count assessed)) "the failed execution still has its assessment requested")
      (is (= :ungradable (:status (first assessed))) (pr-str (first assessed)))
      (is (= :subject-failed (:reason (first assessed))))
      (is (string? (:message (first assessed))))
      (is (not (contains? (first assessed) :score)) "nothing is invented")
      (is (empty? @judge-calls) "no judge workflow ran")
      (is (empty? (events-of ctx #{:judge/score-emitted})) "no legacy learning record"))))

(defn- complete-node!
  "A real completion command for `node-id` with `status` in a fresh run."
  [ctx sheet-id node-id status & {:as extra}]
  (let [r (h/run-and-apply! ctx (merge {:command/name :sheet/complete-node-execution
                                        :command/id (random-uuid)
                                        :command/timestamp (java.time.OffsetDateTime/now)
                                        :sheet-id sheet-id :tick-id (random-uuid)
                                        :node-id node-id :node-type :code
                                        :status status :duration-ms 1}
                                       extra))]
    (is (not (h/is-anomaly? r)) (pr-str r))
    r))

(deftest timeout-and-blocked-completions-are-recorded-ungradable-not-judged
  (doseq [[status extra kind-text] [[:timeout {:error "node exceeded its deadline"} "node exceeded its deadline"]
                                    [:blocked {:block-payload {:waiting-on "approval"}} "blocked"]]]
    (testing (str "status " status)
      (h/with-async-test-context [ctx]
        (reset! judge-calls [])
        (let [sheet-id (failing-leaf-sheet! ctx {})
              producer (node-id ctx sheet-id "producer")]
          (apply complete-node! ctx sheet-id producer status (mapcat identity extra))
          (let [assessed (settled-assessments ctx producer 1)]
            (is (= 1 (count assessed)))
            (is (= :ungradable (:status (first assessed))) (pr-str (first assessed)))
            (is (= :subject-failed (:reason (first assessed))))
            (is (re-find (re-pattern (name status)) (:message (first assessed)))
                "the message names the node's status")
            (when (:error extra)
              (is (re-find (re-pattern (:error extra)) (:message (first assessed)))
                  "the message carries the node's error"))
            (is (empty? @judge-calls))
            (is (empty? (events-of ctx #{:judge/score-emitted})))))))))

(defn echo-or-boom [{:keys [inputs]}]
  (if (= "BOOM" (:item inputs))
    (throw (ex-info "item failed" {}))
    {:item (:item inputs)}))

(deftest a-partial-map-each-is-judged-and-only-its-failed-iteration-is-ungradable
  (h/with-async-test-context [ctx]
    (reset! judge-calls [])
    (let [judge (sheet/build-workflow! ctx (judge-workflow))
          sheet-id (sheet/build-workflow! ctx
                     (sheet/workflow (str "s14-partial-" (random-uuid))
                       (sheet/blackboard {:items [:vector :string] :item :string
                                          :results [:vector :string]})
                       (sheet/judges {:quality {:type :custom :sheet-id judge}})
                       (sheet/map-each "each" :from :items :as :item :into :results
                                       :parallel 1 :judges ["quality"]
                         (sheet/code "work" :fn (fq "echo-or-boom")
                           :reads [:item] :writes [:item] :judges ["quality"]))))
          result (sheet/execute ctx sheet-id {:items ["a" "BOOM" "c"]} :timeout-ms 60000)
          each (node-id ctx sheet-id "each")
          work (node-id ctx sheet-id "work")
          parent (settled-assessments ctx each 1)
          leaves (settled-assessments ctx work 3)]
      (is (= :partial (:status result)) (pr-str (select-keys result [:status])))
      (is (= [:scored] (mapv :status parent))
          "the :partial map-each is judged normally")
      (is (= {:scored 2 :ungradable 1} (frequencies (map :status leaves)))
          "only the failed iteration is ungradable")
      (is (= [:subject-failed] (distinct (keep :reason leaves))))
      (is (= 3 (count @judge-calls)) "the judge ran for the parent and the two good iterations"))))

(deftest a-judge-that-assesses-failures-judges-a-failed-completion
  (h/with-async-test-context [ctx]
    (reset! judge-calls [])
    (let [sheet-id (failing-leaf-sheet! ctx {:assess-failures? true})
          result (sheet/execute ctx sheet-id {:request "hi"} :timeout-ms 60000)
          producer (node-id ctx sheet-id "producer")
          assessed (settled-assessments ctx producer 1)]
      (is (= :failure (:status result)) (pr-str result))
      (is (= :scored (:status (first assessed))) (pr-str (first assessed)))
      (is (= 1 (count @judge-calls)) "the judge ran on the failed completion"))))

(defn maybe-boom [{:keys [inputs]}]
  (if (= "BOOM" (:request inputs))
    (throw (ex-info "producer failed" {}))
    {:answer (str "answer:" (:request inputs))}))

(defn- flaky-sheet! [ctx alert]
  (let [judge (sheet/build-workflow! ctx (judge-workflow))]
    (sheet/build-workflow! ctx
      (sheet/workflow (str "s14-flaky-" (random-uuid))
        (sheet/blackboard bb)
        (sheet/judges {:quality (cond-> {:type :custom :sheet-id judge}
                                  alert (assoc :alert alert))})
        (sheet/code "producer" :fn (fq "maybe-boom") :reads [:request] :writes [:answer]
          :judges ["quality"])))))

(defn- quality-stats [ctx sheet-id producer]
  (let [perf (evaluation/get-node-performance ctx {:sheet-id sheet-id :node-id producer})]
    (first (:rollup perf))))

(deftest subject-failures-are-counted-apart-and-never-enter-the-quality-statistics
  (h/with-async-test-context [ctx]
    (reset! judge-calls [])
    (let [sheet-id (flaky-sheet! ctx nil)
          producer (node-id ctx sheet-id "producer")]
      (doseq [request ["a" "BOOM" "b" "BOOM" "BOOM"]]
        (sheet/execute ctx sheet-id {:request request} :timeout-ms 60000))
      (settled-assessments ctx producer 5)
      (let [stats (quality-stats ctx sheet-id producer)]
        (is (= 2 (:scored stats)))
        (is (= 3 (:ungradable stats)) "they are still ungradable outcomes")
        (is (= 3 (:subject-failed stats)) "and counted apart")
        (is (= 5 (:total stats)) "coverage stays honest: every execution is in the total")
        (is (= 0.4 (:coverage stats)))
        (is (= 0.75 (:mean-score stats)) "the quality mean is over scored outcomes only")
        (is (= 0.75 (:trailing-mean stats)))
        (is (= 2 (count @judge-calls)) "the judge only ran for the two good executions")))))

(deftest a-window-of-subject-failures-signals-degraded-coverage-not-a-crossing
  (h/with-async-test-context [ctx]
    (let [sheet-id (flaky-sheet! ctx {:below 0.6 :window 3 :min-coverage 0.5})
          producer (node-id ctx sheet-id "producer")
          signals #(into [] (map :event/type)
                         (es/read (:event-store ctx)
                                  {:tenant-id (:tenant-id ctx)
                                   :types #{:evaluation/performance-threshold-crossed
                                            :evaluation/performance-threshold-recovered
                                            :evaluation/performance-coverage-degraded
                                            :evaluation/performance-coverage-restored}}))]
      (doseq [request ["a" "BOOM" "BOOM" "BOOM"]]
        (sheet/execute ctx sheet-id {:request request} :timeout-ms 60000)
        ;; one at a time, so the series order is the run order
        (settled-assessments ctx producer 1))
      (is (h/settle-until! #(= 4 (:total (quality-stats ctx sheet-id producer))) :timeout-ms 60000))
      (is (h/settle-until! #(some #{:evaluation/performance-coverage-degraded} (signals))
                           :timeout-ms 60000)
          (str "signals: " (pr-str (signals))))
      (is (not-any? #{:evaluation/performance-threshold-crossed} (signals))
          "failures are not a quality drop"))))

(def ^:private parent-saw (atom []))

(defn record-parent [{:keys [inputs]}]
  (swap! parent-saw conj (:child-assessments inputs))
  {:score 0.5 :feedback "parent"})

(defn- parent-judge-workflow []
  (sheet/workflow (str "s14-parent-judge-" (random-uuid))
    (sheet/blackboard {:host-inputs io :host-outputs io
                       :host-instruction [:string {:description "Host instruction"}]
                       :original-task io
                       :child-assessments [:vector [:map-of :keyword [:any {:description "one child assessment"}]]]
                       :score :double
                       :feedback [:string {:description "Feedback"}]})
    (sheet/code "record" :fn (fq "record-parent")
      :reads [:host-inputs :host-outputs :host-instruction :original-task :child-assessments]
      :writes [:score :feedback])))

(deftest a-parent-judge-sees-a-failed-child-as-ungradable-subject-failed
  (h/with-async-test-context [ctx]
    (reset! judge-calls [])
    (reset! parent-saw [])
    (let [child-judge (sheet/build-workflow! ctx (judge-workflow))
          parent-judge (sheet/build-workflow! ctx (parent-judge-workflow))
          sheet-id (sheet/build-workflow! ctx
                     (sheet/workflow (str "s14-family-" (random-uuid))
                       (sheet/blackboard {:items [:vector :string] :item :string
                                          :results [:vector :string]})
                       (sheet/judges {:child {:type :custom :sheet-id child-judge}
                                      :parent {:type :custom :sheet-id parent-judge}})
                       (sheet/map-each "each" :from :items :as :item :into :results
                                       :parallel 1 :judges ["parent"]
                         (sheet/code "work" :fn (fq "echo-or-boom")
                           :reads [:item] :writes [:item] :judges ["child"]))))
          _ (sheet/execute ctx sheet-id {:items ["a" "BOOM" "c"]} :timeout-ms 60000)
          each (node-id ctx sheet-id "each")
          parent (settled-assessments ctx each 1)
          kids (first @parent-saw)]
      (is (= [:scored] (mapv :status parent)) (pr-str parent))
      (is (= 3 (count kids)) "the parent waited for all three children (the failed one settles at once)")
      (is (= {:scored 2 :ungradable 1} (frequencies (map :status kids))))
      (is (= [:subject-failed] (distinct (keep :reason kids)))
          "the failed child is shown to the parent as ungradable :subject-failed, not dropped")
      (is (every? string? (keep :message (filter #(= :ungradable (:status %)) kids)))))))

(deftest the-composite-of-a-failed-subject-is-coverage-only
  (h/with-async-test-context [ctx]
    (reset! judge-calls [])
    (let [j1 (sheet/build-workflow! ctx (judge-workflow))
          j2 (sheet/build-workflow! ctx (judge-workflow))
          sheet-id (sheet/build-workflow! ctx
                     (sheet/workflow (str "s14-composite-" (random-uuid))
                       (sheet/blackboard bb)
                       (sheet/judges {:one {:type :custom :sheet-id j1}
                                      :two {:type :custom :sheet-id j2}})
                       (sheet/code "producer" :fn (fq "boom") :reads [:request] :writes [:answer]
                         :judges ["one" "two"])))
          _ (sheet/execute ctx sheet-id {:request "hi"} :timeout-ms 60000)
          producer (node-id ctx sheet-id "producer")
          composites #(events-of ctx #{:judge/composite-score-computed})]
      (is (= [:ungradable :ungradable] (mapv :status (settled-assessments ctx producer 2))))
      (is (h/settle-until! #(seq (composites)) :timeout-ms 60000) "a composite is still recorded")
      (let [c (first (composites))]
        (is (= {:expected 2 :scored 0 :failed 0 :ungradable 2} (:coverage c)))
        (is (true? (:partial c)))
        (is (not (contains? c :composite-score)) "no score is invented"))
      (is (empty? @judge-calls)))))
