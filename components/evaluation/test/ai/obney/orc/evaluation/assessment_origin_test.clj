(ns ai.obney.orc.evaluation.assessment-origin-test
  "S8: work done for an assessment is never auto-assessed
   (specs: orc-service AssessmentWorkIsMarked, evaluation CustomJudgeConfinement).

   A judge is a workflow. Its run carries a DURABLE assessment origin that every
   child run inherits, so a completion inside a judge's tree requests no
   attached-judge assessment - read from the store, never from in-memory
   context, so it holds across async hand-offs and processor restarts."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.core.commands]
            [ai.obney.orc.orc-service.core.read-models]
            [ai.obney.orc.evaluation.interface]
            [ai.obney.orc.ontology.interface]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.grain.time.interface]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.commands]
            [ai.obney.orc.evaluation.core.judge-runtime :as jr]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.todo-processor-v2.interface :as tp]))

(def ^:private b-calls (atom []))
(def ^:private a-calls (atom []))

(defn host-work [{:keys [inputs]}] {:answer (str "answer:" (:request inputs))})
(defn leaf-work [{:keys [inputs]}] {:answer (str "leaf:" (:request inputs))})

(defn record-b [{:keys [inputs]}]
  (swap! b-calls conj inputs)
  {:score 0.5 :feedback "b"})

(defn record-a [{:keys [inputs]}]
  (swap! a-calls conj inputs)
  {:answer "a-leaf" :score 0.75 :feedback "a"})

(def ^:private io [:map-of :keyword [:string {:description "any value"}]])

(def ^:private judge-bb
  {:host-inputs io
   :host-outputs io
   :host-instruction [:string {:description "Host instruction"}]
   :host-trace [:vector [:map [:node-id {:optional true} :uuid]]]
   :score :double
   :feedback [:string {:description "Feedback"}]})

(defn- judge-b-workflow []
  (orc/workflow (str "s8-judge-b-" (random-uuid))
    (orc/blackboard judge-bb)
    (orc/code "record-b" :fn "ai.obney.orc.evaluation.assessment-origin-test/record-b"
      :reads [:host-inputs :host-outputs :host-instruction :host-trace]
      :writes [:score :feedback])))

(defn- events-of [ctx types]
  (into [] (es/read (:event-store ctx) {:types types :tenant-id (:tenant-id ctx)})))

(defn- requested [ctx] (events-of ctx #{:evaluation/assessment-requested}))
(defn- scored [ctx] (events-of ctx #{:evaluation/assessment-scored}))

(defn- host-workflow [judge-a-sheet]
  (orc/workflow (str "s8-host-" (random-uuid))
    (orc/blackboard {:request [:string {:description "Input"}]
                     :answer [:string {:description "Output"}]})
    (orc/judges {:a {:type :custom :sheet-id judge-a-sheet}})
    (orc/code "host-leaf" :fn "ai.obney.orc.evaluation.assessment-origin-test/host-work"
      :reads [:request] :writes [:answer] :judges ["a"])))

;; Judge A's own workflow: one leaf with judge B attached.
(defn- judge-a-with-leaf-judged-by-b [judge-b-sheet]
  (orc/workflow (str "s8-judge-a-" (random-uuid))
    (orc/blackboard (assoc judge-bb :answer [:string {:description "leaf out"}]))
    (orc/judges {:b {:type :custom :sheet-id judge-b-sheet}})
    (orc/code "a-leaf" :fn "ai.obney.orc.evaluation.assessment-origin-test/record-a"
      :reads [:host-inputs :host-outputs :host-instruction :host-trace]
      :writes [:answer :score :feedback] :judges ["b"])))

(defn- wait-for [pred]
  (h/settle-until! pred :timeout-ms 45000))

(defn- completions-of-sheet [ctx sheet-id]
  (filterv #(= sheet-id (:sheet-id %)) (events-of ctx #{:sheet/node-execution-completed})))

(deftest a-judge-runs-inside-an-assessment-are-never-assessed
  (testing "host leaf judged by A; A's own leaf carries judge B: B is never requested"
    (h/with-async-test-context [ctx]
      (reset! a-calls []) (reset! b-calls [])
      (let [b-sheet (orc/build-workflow! ctx (judge-b-workflow))
            a-sheet (orc/build-workflow! ctx (judge-a-with-leaf-judged-by-b b-sheet))
            host (orc/build-workflow! ctx (host-workflow a-sheet))
            result (orc/execute ctx host {:request "hello"} :timeout-ms 60000)]
        (is (= :success (:status result)) (pr-str result))
        (is (wait-for #(= 1 (count (scored ctx))))
            (str "A assesses the host; requested " (count (requested ctx))))
        (is (= ["a"] (mapv :judge-name (requested ctx)))
            "only the host's judge was requested - B (on A's own leaf) never")
        (is (= 1 (count @a-calls)) "A ran once")
        (let [a-leaf-completion (first (completions-of-sheet ctx a-sheet))]
          (is (some? a-leaf-completion) "A's leaf completion is durable")
          (is (nil? (jr/on-node-execution-completed (assoc ctx :event a-leaf-completion)))
              "the completion handler requests nothing for a completion inside an assessment"))
        (is (empty? @b-calls) "judge B never ran")))))

;; -----------------------------------------------------------------------------
;; Cycle 2 - the mark survives a process restart
;; -----------------------------------------------------------------------------

(defn- evaluation-processor? [proc-name] (= "evaluation" (namespace proc-name)))

(defn- stop-evaluation-processors! [ctx]
  (h/stop-test-processors! {:processors (into {} (filter (comp evaluation-processor? key))
                                              (:processors ctx))})
  (update ctx :processors #(into {} (remove (comp evaluation-processor? key)) %)))

(defn- start-evaluation-processors!
  "Start the evaluation processors afresh over the same store: nothing they know
   survives in memory; whatever they decide must be read from the store."
  [ctx]
  ;; Through the shared harness, so they are delivered as in production
  ;; (checkpointed processors are polled, one event at a time).
  (let [base (dissoc ctx :processors)
        others (into #{} (remove evaluation-processor?) (keys @tp/processor-registry*))]
    (update ctx :processors merge (h/start-test-processors base others))))

(deftest the-assessment-mark-survives-a-processor-restart
  (testing "the host completes while the evaluation processors are down; started again over the same store, they assess the host and still never assess inside A"
    (h/with-async-test-context [ctx]
      (reset! a-calls []) (reset! b-calls [])
      (let [b-sheet (orc/build-workflow! ctx (judge-b-workflow))
            a-sheet (orc/build-workflow! ctx (judge-a-with-leaf-judged-by-b b-sheet))
            host (orc/build-workflow! ctx (host-workflow a-sheet))
            down (stop-evaluation-processors! ctx)
            result (orc/execute down host {:request "hello"} :timeout-ms 60000)]
        (is (= :success (:status result)) (pr-str result))
        (is (empty? (requested down)) "pre-condition: nothing was requested while down")
        (let [up (start-evaluation-processors! down)]
          (try
            (is (wait-for #(= 1 (count (scored up))))
                (str "catch-up assesses the host; requested " (count (requested up))))
            (is (= ["a"] (mapv :judge-name (requested up)))
                "after the restart B is still never requested")
            (let [a-leaf-completion (first (completions-of-sheet up a-sheet))]
              (is (some? a-leaf-completion))
              (is (nil? (jr/on-node-execution-completed
                         (-> (dissoc up :processors) (assoc :event a-leaf-completion))))
                  "a fresh context (no in-memory state) still requests nothing inside A"))
            (is (empty? @b-calls) "judge B never ran")
            (finally
              (h/stop-test-processors! {:processors (into {} (filter (comp evaluation-processor? key)) (:processors up))}))))))))

;; -----------------------------------------------------------------------------
;; Cycle 3 - the mark is inherited by what a judge delegates to
;; -----------------------------------------------------------------------------

(def ^:private c-calls (atom []))

(defn record-c [{:keys [inputs]}]
  (swap! c-calls conj inputs)
  {:score 0.5 :feedback "c"})

(defn specialist-work [{:keys [inputs]}]
  {:verdict (str "verdict:" (count (str (:host-instruction inputs))))})

(defn check-one [{:keys [inputs]}] {:check-one (str "one:" (:verdict inputs))})
(defn check-two [{:keys [inputs]}] {:check-two (str "two:" (:verdict inputs))})

(defn finalize-a [{:keys [inputs]}]
  (swap! a-calls conj inputs)
  {:score 0.75 :feedback (str (:check-one inputs) "|" (:check-two inputs) "|" (:verdict inputs))})

(def ^:private text [:string {:description "text"}])

(defn- judge-c-workflow []
  (orc/workflow (str "s8-judge-c-" (random-uuid))
    (orc/blackboard judge-bb)
    (orc/code "record-c" :fn "ai.obney.orc.evaluation.assessment-origin-test/record-c"
      :reads [:host-inputs :host-outputs :host-instruction :host-trace]
      :writes [:score :feedback])))

(defn- specialist-workflow
  "A specialist whose leaf carries judge C."
  [judge-c-sheet]
  (orc/workflow (str "s8-specialist-" (random-uuid))
    (orc/blackboard {:host-instruction text :verdict text})
    (orc/judges {:c {:type :custom :sheet-id judge-c-sheet}})
    (orc/code "specialist-leaf" :fn "ai.obney.orc.evaluation.assessment-origin-test/specialist-work"
      :reads [:host-instruction] :writes [:verdict] :judges ["c"])))

(defn- judge-a-delegating
  "Judge A: delegates to the specialist, optionally runs two parallel checks,
   then writes its score."
  [specialist-sheet parallel?]
  (orc/workflow (str "s8-judge-a-delegating-" (random-uuid))
    (orc/blackboard (assoc judge-bb
                           :verdict text :check-one text :check-two text))
    (if parallel?
      (orc/sequence "a-root"
        (orc/delegate "specialist" :target-sheet-id specialist-sheet
          :reads [:host-instruction] :writes [:verdict])
        (orc/parallel "checks"
          (orc/code "check-one" :fn "ai.obney.orc.evaluation.assessment-origin-test/check-one"
            :reads [:verdict] :writes [:check-one])
          (orc/code "check-two" :fn "ai.obney.orc.evaluation.assessment-origin-test/check-two"
            :reads [:verdict] :writes [:check-two]))
        (orc/code "finalize" :fn "ai.obney.orc.evaluation.assessment-origin-test/finalize-a"
          :reads [:verdict :check-one :check-two] :writes [:score :feedback]))
      (orc/sequence "a-root"
        (orc/delegate "specialist" :target-sheet-id specialist-sheet
          :reads [:host-instruction] :writes [:verdict])
        (orc/code "finalize" :fn "ai.obney.orc.evaluation.assessment-origin-test/finalize-a"
          :reads [:verdict] :writes [:score :feedback])))))

(defn- completion-ticks [ctx sheet-id]
  (into #{} (map :tick-id) (completions-of-sheet ctx sheet-id)))

(deftest a-judges-delegate-inherits-the-assessment-mark
  (testing "judge A delegates to a specialist whose leaf carries judge C: C is never requested, and the specialist's run carries the origin"
    (h/with-async-test-context [ctx]
      (reset! a-calls []) (reset! c-calls [])
      (let [c-sheet (orc/build-workflow! ctx (judge-c-workflow))
            specialist (orc/build-workflow! ctx (specialist-workflow c-sheet))
            a-sheet (orc/build-workflow! ctx (judge-a-delegating specialist false))
            host (orc/build-workflow! ctx (host-workflow a-sheet))
            result (orc/execute ctx host {:request "hello"} :timeout-ms 60000)]
        (is (= :success (:status result)) (pr-str result))
        (is (wait-for #(= 1 (count (scored ctx))))
            (str "A assesses the host; requested " (count (requested ctx))))
        (is (= ["a"] (mapv :judge-name (requested ctx)))
            "C (on the specialist's leaf) is never requested")
        (let [spec-ticks (completion-ticks ctx specialist)
              a-ticks (completion-ticks ctx a-sheet)
              assessment-id (:assessment-id (first (requested ctx)))]
          (is (= 1 (count spec-ticks)) "the specialist ran once")
          (is (every? #(= {:assessment-id assessment-id} (orc/assessment-origin ctx %))
                      (concat spec-ticks a-ticks))
              "A's run and its delegate's run carry the assessment's origin")
          (is (nil? (orc/assessment-origin ctx (:tick-id (first (completions-of-sheet ctx host)))))
              "the host's own run carries none"))
        (is (empty? @c-calls) "judge C never ran")))))

;; -----------------------------------------------------------------------------
;; Cycle 4 - composition inside a judge still works
;; -----------------------------------------------------------------------------

(deftest a-judge-still-composes-delegates-and-parallel-checks
  (testing "judge A delegates to a specialist and runs two parallel checks: its assessment is scored from them"
    (h/with-async-test-context [ctx]
      (reset! a-calls []) (reset! c-calls [])
      (let [c-sheet (orc/build-workflow! ctx (judge-c-workflow))
            specialist (orc/build-workflow! ctx (specialist-workflow c-sheet))
            a-sheet (orc/build-workflow! ctx (judge-a-delegating specialist true))
            host (orc/build-workflow! ctx (host-workflow a-sheet))
            result (orc/execute ctx host {:request "hello"} :timeout-ms 60000)]
        (is (= :success (:status result)) (pr-str result))
        (is (wait-for #(= 1 (count (scored ctx))))
            (str "A's assessment is scored; requested " (count (requested ctx))))
        (let [a-score (first (scored ctx))]
          (is (= 0.75 (:score a-score)))
          (is (re-find #"one:verdict:\d+\|two:verdict:\d+\|verdict:\d+" (str (:feedback a-score)))
              (str "the score was derived from the delegate and both parallel checks: "
                   (pr-str (:feedback a-score)))))
        (is (= ["a"] (mapv :judge-name (requested ctx))))
        (is (empty? @c-calls) "nothing inside A is judged")))))

;; -----------------------------------------------------------------------------
;; Cycle 5 - outside an assessment, delegates are assessed as ever
;; -----------------------------------------------------------------------------

(defn- judge-a-plain []
  (orc/workflow (str "s8-judge-a-plain-" (random-uuid))
    (orc/blackboard judge-bb)
    (orc/code "record-a-plain" :fn "ai.obney.orc.evaluation.assessment-origin-test/record-b"
      :reads [:host-inputs :host-outputs :host-instruction :host-trace]
      :writes [:score :feedback])))

(deftest an-ordinary-delegate-s-leaf-is-still-assessed
  (testing "a normal host delegates to a child whose leaf has a judge attached: that leaf IS assessed, and no origin marks the run"
    (h/with-async-test-context [ctx]
      (reset! b-calls []) (reset! c-calls [])
      (let [c-sheet (orc/build-workflow! ctx (judge-c-workflow))
            specialist (orc/build-workflow! ctx (specialist-workflow c-sheet))
            host (orc/build-workflow! ctx
                   (orc/workflow (str "s8-ordinary-host-" (random-uuid))
                     (orc/blackboard {:host-instruction text :verdict text})
                     (orc/delegate "specialist" :target-sheet-id specialist
                       :reads [:host-instruction] :writes [:verdict])))
            result (orc/execute ctx host {:host-instruction "hi"} :timeout-ms 60000)]
        (is (= :success (:status result)) (pr-str result))
        (is (wait-for #(= 1 (count (scored ctx))))
            (str "the delegate's leaf is assessed; requested " (count (requested ctx))))
        (is (= ["c"] (mapv :judge-name (requested ctx))))
        (is (= 1 (count @c-calls)) "judge C ran")
        (is (every? nil? (map #(orc/assessment-origin ctx %)
                              (concat (completion-ticks ctx specialist)
                                      (completion-ticks ctx host))))
            "no origin outside an assessment")))))

;; -----------------------------------------------------------------------------
;; The tree-generation path obeys the same rule
;; -----------------------------------------------------------------------------

(defn tree-writer [_] {:answer "x"})

(deftest a-tree-generated-under-an-assessment-is-not-auto-assessed
  (testing "on-rlm-tree-generated (the structural judge's trigger) requests nothing for a tick that carries an assessment origin, and still does for an ordinary tick"
    (h/with-async-test-context [ctx]
      (h/run-and-apply! ctx {:command/name :ontology/set-living-description-enabled
                             :command/id (random-uuid)
                             :command/timestamp (ai.obney.grain.time.interface/now)
                             :enabled? true})
      (is (h/settle-until! #(ai.obney.orc.ontology.interface/get-living-description-enabled? ctx))
          "the Living Description flag is on")
      (let [on-tree @#'jr/on-rlm-tree-generated
            wf (fn [] (orc/workflow (str "s8-tree-host-" (random-uuid))
                        (orc/blackboard {:answer [:string {:description "out"}]})
                        (orc/judges {:structure {:type :heuristic-structural}})
                        (orc/code "writer" :fn "ai.obney.orc.evaluation.assessment-origin-test/tree-writer"
                          :writes [:answer] :judges ["structure"])))
            run (fn [& opts]
                  (let [sheet (orc/build-workflow! ctx (wf))
                        result (apply orc/execute ctx sheet {} :timeout-ms 60000 opts)
                        node (:id (first (filter #(= "writer" (:name %))
                                                 (orc/get-nodes-for-sheet ctx sheet))))]
                    {:result result
                     :event {:execution-id (:trace-id result) :raw-dsl [:sequence [:llm {}] [:final {}]]
                             :sheet-id sheet :node-id node}}))
            ordinary (run)
            marked (run :assessment-origin {:assessment-id (random-uuid)})]
        (is (= :success (get-in ordinary [:result :status])))
        (is (= :success (get-in marked [:result :status])))
        (is (some? (on-tree (assoc ctx :event (:event ordinary))))
            "an ordinary tick's generated tree is assessed")
        (is (nil? (on-tree (assoc ctx :event (:event marked))))
            "a tick inside an assessment requests nothing")))))
