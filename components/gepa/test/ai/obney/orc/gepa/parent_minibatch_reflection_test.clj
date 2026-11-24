(ns ai.obney.orc.gepa.parent-minibatch-reflection-test
  "Deterministic integration tests (no model, no network) for the
   ReflectiveInstructionProposer contract in specs/gepa.allium:
   a mutation round draws the training minibatch, evaluates the PARENT whose
   instruction will be rewritten on it, and builds the reflective examples from
   that evaluation alone. The validation set never reaches reflection, and the
   parent's minibatch is evaluated once and reused at the acceptance gate.

   Each scenario seeds a durable optimization through the public commands,
   runs the real todo processor for one round, and captures what reaches the
   proposer by redefining `generate-mutated-instruction`."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest testing is]]
            [ai.obney.grain.control-plane.interface :as control-plane]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.grain-test-utils.interface :as tu]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.gepa.interface :as gepa]
            [ai.obney.orc.gepa.core.commands]
            [ai.obney.orc.gepa.core.metric-runtime :as metric-runtime]
            [ai.obney.orc.gepa.core.read-models :as rm]
            [ai.obney.orc.gepa.core.todo-processors :as tp]
            [ai.obney.orc.gepa.interface.schemas]))

;; -----------------------------------------------------------------------------
;; Fixture workflow and metric
;; -----------------------------------------------------------------------------

(defn echo-fn [{:keys [inputs]}]
  {:output (str "OUT:" (get inputs :input))})

(defn failing-fn [_]
  (throw (ex-info "the workflow cannot produce an output" {})))

(defn- echo-workflow []
  (sheet/workflow "gepa-parent-minibatch-test"
    (sheet/blackboard {:input :string :output :string})
    (sheet/code "echo"
      :fn "ai.obney.orc.gepa.parent-minibatch-reflection-test/echo-fn"
      :reads [:input]
      :writes [:output])))

(defn- failing-workflow []
  (sheet/workflow "gepa-parent-minibatch-failing-test"
    (sheet/blackboard {:input :string :output :string})
    (sheet/code "boom"
      :fn "ai.obney.orc.gepa.parent-minibatch-reflection-test/failing-fn"
      :reads [:input]
      :writes [:output])))

(defn- examples [prefix n]
  (mapv (fn [i] {"input" (str prefix "-" i)}) (range n)))

(defn- example-id [input] (get input "input"))

(defn- counting-metric
  "A metric whose feedback names the example it graded. `score-of` maps an
   input id to a score. Every invocation is counted by input id."
  [counts score-of]
  (fn [input _output]
    (swap! counts update (example-id input) (fnil inc 0))
    {:score (score-of (example-id input))
     :feedback (str "FB<" (example-id input) ">")}))

;; Evaluating a task runs a real ORC tick, which the control plane processes.
;; The GEPA handlers themselves are silenced for the whole scenario so that the
;; seeded events do not start rounds of their own (no proposer, and so no model,
;; is ever reached); each scenario drives the real handler explicitly through
;; the captured originals below.
(def ^:private real-on-candidate-evaluated tp/on-candidate-evaluated)
(def ^:private real-on-subsample-evaluation-started tp/on-subsample-evaluation-started)

;; The handler of the recorded round request (`ParentMinibatchEvaluationRequested`)
;; is resolved by name: it is the durable boundary that resume! re-runs. While
;; scenarios run, the control plane's own deliveries of it are dropped; only the
;; scenario's thread (or, once `open-gate?` is set, any thread) reaches the real one.
(def ^:private requested-var
  (ns-resolve 'ai.obney.orc.gepa.core.todo-processors 'on-parent-minibatch-evaluation-requested))
(def ^:private real-on-requested (some-> requested-var deref))
(def ^:private open-gate? (atom false))

(defn call-with-gated-requested-handler [test-thread f]
  (reset! open-gate? false)
  (if requested-var
    (with-redefs-fn {requested-var
                     (fn [c] (when (or (identical? test-thread (Thread/currentThread)) @open-gate?)
                               (real-on-requested c)))}
      f)
    (f)))

(defmacro with-loop-context [[ctx] & body]
  `(tu/with-test-context [~ctx]
     (with-redefs [tp/on-optimization-started (constantly nil)
                   tp/on-candidate-created (constantly nil)
                   tp/on-candidate-evaluation-started (constantly nil)
                   tp/on-candidate-evaluated (constantly nil)
                   tp/on-subsample-evaluation-started (constantly nil)
                   tp/on-proposal-ready (constantly nil)
                   tp/on-subsample-evaluated (constantly nil)
                   tp/on-mutation-proposed (constantly nil)
                   tp/on-frontier-updated (constantly nil)
                   tp/on-optimization-completed (constantly nil)
                   tp/on-optimization-failed (constantly nil)
                   tp/generate-mutated-instruction
                   (fn [& _#] (throw (ex-info "the proposer must be stubbed by the scenario" {})))]
       (let [cp# (control-plane/start {:event-store (:event-store ~ctx) :cache (:cache ~ctx)
                                       :context ~ctx})]
         (try (call-with-gated-requested-handler (Thread/currentThread) (fn [] ~@body))
              (finally (control-plane/stop cp#)))))))

;; -----------------------------------------------------------------------------
;; Seeding and observation
;; -----------------------------------------------------------------------------

(defn- seed!
  "Start an optimization and create two evaluated candidates. The seed `C` is
   the one whose evaluation was just recorded; `P` is the frontier parent.
   Both carry validation-set evidence with unmistakable markers."
  [ctx {:keys [trainset valset config workflow]}]
  (let [sheet-id (sheet/build-workflow! ctx (or workflow (echo-workflow)))
        ;; The durable metric identity delegates to whichever metric the test
        ;; installs for the round (an optimization is started with its metric).
        metric-ref (atom (fn [_ _] 0.0))
        opt-id (random-uuid)
        descriptor (metric-runtime/prepare! ctx opt-id {:metric-fn (fn [i o] (@metric-ref i o))})
        opt (get-in (tu/process-command! ctx
                      {:command/name :gepa/start-optimization
                       :optimization-id opt-id :metric-descriptor descriptor
                       :sheet-id sheet-id :trainset trainset :valset valset
                       :inherit-from-previous false
                       :config (merge {:max-metric-calls 1000
                                       :reflection-minibatch-size 3}
                                      config)})
                    [:command/result :optimization-id])
        candidate! (fn [instructions]
                     (get-in (tu/process-command! ctx
                               {:command/name :gepa/create-candidate
                                :optimization-id opt :instructions instructions
                                :parent-ids [nil] :mutation-reason "fixture"})
                             [:command/result :candidate-id]))
        evaluate! (fn [candidate-id marker]
                    (tu/process-command! ctx
                      {:command/name :gepa/record-evaluation-result
                       :optimization-id opt :candidate-id candidate-id
                       :scores (mapv (constantly 0.0) valset) :trace-ids []
                       :metric-calls 0
                       :outputs (into {} (map-indexed
                                           (fn [i v] [i {"output" (str marker "-OUT:" (example-id v))}])
                                           valset))
                       :feedbacks (into {} (map-indexed
                                             (fn [i v] [i (str marker "-FB<" (example-id v) ">")])
                                             valset))}))
        parent (candidate! {"default" "PARENT-INSTRUCTION"})
        cand (candidate! {"default" "CANDIDATE-INSTRUCTION"})]
    (evaluate! parent "PARENT-VALIDATION")
    (evaluate! cand "CANDIDATE-VALIDATION")
    {:optimization-id opt :sheet-id sheet-id :metric-ref metric-ref :parent-id parent :candidate-id cand
     :valset valset :trainset trainset}))

(defn- recording-proposer
  "Stand-in for `generate-mutated-instruction`. Records the reflective examples
   and the durable task evidence visible at the moment the proposer is called."
  [calls]
  (fn [context parent-instructions component examples _model
       & [optimization-id parent-id iteration]]
    (let [tasks (into [] (es/read (:event-store context)
                           {:tenant-id (:tenant-id context)
                            :types #{:gepa/task-call-completed}}))]
      (swap! calls conj
             {:examples examples :component component :parent-id parent-id
              :parent-instructions parent-instructions :iteration iteration
              :optimization-id optimization-id
              :parent-tasks (vec (filter #(= :subsample-parent (:role %)) tasks))
              :proposed-tasks (vec (filter #(= :subsample-proposed (:role %)) tasks))}))
    "FIXED-PROPOSED-INSTRUCTION"))

(defn- events-of [ctx opt types]
  (into [] (es/read (:event-store ctx)
             {:tenant-id (:tenant-id ctx) :types types
              :tags #{[:optimization opt]}})))

(defn- requested-rounds [ctx opt]
  (events-of ctx opt #{:gepa/parent-minibatch-evaluation-requested}))

(defn- drive-requested!
  "Deliver each recorded round request to its real handler (the processor of the
   `ParentMinibatchEvaluationRequested` event)."
  [ctx opt]
  (when real-on-requested
    (doseq [e (requested-rounds ctx opt)]
      (real-on-requested (assoc ctx :event e)))))

(defn- run-round!
  "Deliver the candidate-evaluated event of the seed candidate to the real
   processor, with the frontier sampler fixed to the parent."
  [ctx {:keys [optimization-id parent-id candidate-id valset metric-ref]} metric calls]
  (reset! metric-ref metric)
  (with-redefs [rm/sample-pareto-frontier (constantly parent-id)
                tp/generate-mutated-instruction (recording-proposer calls)]
    (let [ctx (assoc ctx :gepa/metric-fn metric)]
      (real-on-candidate-evaluated
        (assoc ctx
               :event {:optimization-id optimization-id :candidate-id candidate-id
                       :scores (mapv (constantly 0.0) valset) :aggregate-score 0.0}))
      (drive-requested! ctx optimization-id))))

(defn- proposal-ready [ctx opt]
  (first (events-of ctx opt #{:gepa/proposal-ready})))

(defn- coherent-with-one-training-example?
  "Does the reflective example describe one single training example?"
  [trainset {:strs [Inputs] :as example}]
  (let [id (example-id Inputs)
        output (get example "Generated Outputs")
        feedback (get example "Feedback")]
    (and (some #(= Inputs %) trainset)
         (= (str "OUT:" id) (:output output))
         (string/includes? feedback (str "FB<" id ">")))))

(defn- assert-reflection-from-the-minibatch!
  [ctx {:keys [optimization-id trainset] :as seeded} calls]
  (let [call (first @calls)
        ready (proposal-ready ctx optimization-id)
        minibatch (set (map #(nth trainset %) (:subsample-indices ready)))
        text (pr-str (:examples call))]
    (is (= 1 (count @calls)) "the proposer is called once for the round")
    (is (seq (:examples call)) "there are reflective examples")
    (is (every? #(coherent-with-one-training-example? trainset %) (:examples call))
        "every example's input, output and feedback belong to one training example")
    (is (every? #(not (empty? (get % "Inputs"))) (:examples call)) "no empty input")
    (is (every? #(contains? minibatch (get % "Inputs")) (:examples call))
        "the examples are the parent's minibatch")
    (is (not (string/includes? text "VAL")) "no validation value reaches reflection")
    (is (not (string/includes? text "VALIDATION")) "no recorded validation evidence reaches reflection")
    seeded))

;; -----------------------------------------------------------------------------
;; Reflection belongs to the training minibatch
;; -----------------------------------------------------------------------------

(deftest distinct-sets-reflect-on-one-training-example
  (testing "every reflective example's input, output and feedback belong to one training example"
    (with-loop-context [ctx]
      (let [counts (atom {})
            calls (atom [])
            seeded (seed! ctx {:trainset (examples "TRAIN" 5) :valset (examples "VAL" 4)})]
        (run-round! ctx seeded (counting-metric counts (constantly 0.0)) calls)
        (assert-reflection-from-the-minibatch! ctx seeded calls)))))

(deftest training-set-shorter-than-the-validation-set
  (testing "an index that exists only in the validation set never produces an empty input"
    (with-loop-context [ctx]
      (let [counts (atom {})
            calls (atom [])
            seeded (seed! ctx {:trainset (examples "TRAIN" 3) :valset (examples "VAL" 7)})]
        (run-round! ctx seeded (counting-metric counts (constantly 0.0)) calls)
        (assert-reflection-from-the-minibatch! ctx seeded calls)))))

(deftest reordered-equal-examples-stay-paired
  (testing "the same examples in a different order are still paired by identity"
    (with-loop-context [ctx]
      (let [counts (atom {})
            calls (atom [])
            shared (examples "SHARED" 5)
            seeded (seed! ctx {:trainset (vec (reverse shared)) :valset shared})]
        (run-round! ctx seeded (counting-metric counts (constantly 0.0)) calls)
        (is (every? #(coherent-with-one-training-example? (:trainset seeded) %)
                    (:examples (first @calls)))
            "an output is never shown with another example's input")
        (is (seq (:examples (first @calls))))))))

(deftest failing-examples-come-first
  (testing "examples below a perfect score precede the parent's perfect ones"
    (with-loop-context [ctx]
      (let [counts (atom {})
            calls (atom [])
            trainset (examples "TRAIN" 3)
            seeded (seed! ctx {:trainset trainset :valset (examples "VAL" 3)})
            score-of (fn [id] (if (= id "TRAIN-0") 1.0 0.25))]
        (run-round! ctx seeded (counting-metric counts score-of) calls)
        (let [ids (mapv #(example-id (get % "Inputs")) (:examples (first @calls)))]
          (is (= 3 (count ids)))
          (is (= "TRAIN-0" (last ids)) "the perfect example is last"))))))

(deftest reflection-comes-from-the-sampled-parent-not-the-evaluated-candidate
  (testing "the parent whose instruction is rewritten supplies the examples"
    (with-loop-context [ctx]
      (let [counts (atom {})
            calls (atom [])
            seeded (seed! ctx {:trainset (examples "TRAIN" 4) :valset (examples "VAL" 4)})
            {:keys [optimization-id parent-id candidate-id]} seeded]
        (is (not= parent-id candidate-id))
        (run-round! ctx seeded (counting-metric counts (constantly 0.0)) calls)
        (let [call (first @calls)
              boundaries (events-of ctx optimization-id #{:gepa/task-boundary-opened})
              parent-boundary (first (filter #(= :subsample (get-in % [:boundary :kind])) boundaries))
              text (pr-str (:examples call))]
          (is (= parent-id (:parent-id call)))
          (is (= parent-id (get-in parent-boundary [:boundary :parent-id]))
              "the parent, not the just-evaluated candidate, was evaluated on the minibatch")
          (is (every? #(= {"default" "PARENT-INSTRUCTION"} (:instructions %))
                      (:tasks parent-boundary))
              "the minibatch ran the parent's instructions")
          (is (not (string/includes? text "CANDIDATE-VALIDATION")))
          (is (not (string/includes? text "PARENT-VALIDATION"))))))))

;; A training set smaller than the minibatch size: the sampler pads the batch
;; with a repeated index; the round must use the distinct examples only.
(deftest training-set-smaller-than-the-minibatch-size
  (testing "a small training set reflects on each example once and reaches the proposer"
    (with-loop-context [ctx]
      (let [counts (atom {})
            calls (atom [])
            seeded (seed! ctx {:trainset (examples "TRAIN" 2) :valset (examples "VAL" 5)})
            {:keys [optimization-id trainset]} seeded]
        (run-round! ctx seeded (counting-metric counts (constantly 0.0)) calls)
        (let [ready (proposal-ready ctx optimization-id)
              indices (:subsample-indices ready)]
          (is (= 1 (count @calls)) "the proposer is reached")
          (is (= (count indices) (count (set indices))) "the proposal's indices are distinct")
          (is (= #{0 1} (set indices)))
          (is (= 2 (count (:examples (first @calls)))))
          (is (every? #(coherent-with-one-training-example? trainset %)
                      (:examples (first @calls)))))))))

;; -----------------------------------------------------------------------------
;; The parent's minibatch is evaluated once
;; -----------------------------------------------------------------------------

(deftest parent-minibatch-is-evaluated-before-the-proposer-and-only-once
  (testing "reflection and the acceptance gate share one parent evaluation"
    (with-loop-context [ctx]
      (let [counts (atom {})
            calls (atom [])
            metric (counting-metric counts (constantly 0.0))
            seeded (seed! ctx {:trainset (examples "TRAIN" 5) :valset (examples "VAL" 4)})
            {:keys [optimization-id]} seeded]
        (run-round! ctx seeded metric calls)
        (let [call (first @calls)
              ready (proposal-ready ctx optimization-id)
              size (count (:subsample-indices ready))]
          (is (= size (count (:parent-tasks call)))
              "the parent's minibatch was already evaluated when the proposer was called")
          (is (= (set (:subsample-indices ready))
                 (set (map :example-index (:parent-tasks call)))))
          (is (empty? (:proposed-tasks call)) "nothing was proposed yet")
          (is (= size (reduce + (vals @counts))) "one metric call per minibatch example so far")
          ;; The gate judges the proposal against that same parent evaluation.
          (real-on-subsample-evaluation-started (assoc ctx :event ready))
          (let [completed (events-of ctx optimization-id #{:gepa/task-call-completed})
                dispatched (events-of ctx optimization-id #{:gepa/task-attempt-dispatched})
                parents (filter #(= :subsample-parent (:role %)) completed)
                decided (first (events-of ctx optimization-id #{:gepa/subsample-evaluated}))]
            (is (= size (count parents)) "the parent's tasks were not run again")
            (is (= (count completed) (count (set (map :call-id completed)))))
            (is (= (* 2 size) (count completed)) "parent once and proposal once")
            (is (= (* 2 size) (count dispatched)) "no second dispatch for any task")
            (is (= (* 2 size) (reduce + (vals @counts))) "no second metric call")
            (is (= (* 2 size) (:metric-calls decided)))
            (is (false? (:accepted? decided)) "equal sums are not a strict improvement")))))))

(deftest redelivery-reuses-the-parent-evaluation
  (testing "a redelivered candidate-evaluated event does not run the parent again"
    (with-loop-context [ctx]
      (let [counts (atom {})
            calls (atom [])
            metric (counting-metric counts (constantly 0.0))
            seeded (seed! ctx {:trainset (examples "TRAIN" 4) :valset (examples "VAL" 4)})
            {:keys [optimization-id]} seeded]
        (run-round! ctx seeded metric calls)
        (let [after-first (reduce + (vals @counts))]
          (run-round! ctx seeded metric calls)
          (is (pos? after-first))
          (is (= after-first (reduce + (vals @counts)))
              "the second delivery reused every parent task")
          (is (= 1 (count (events-of ctx optimization-id #{:gepa/proposal-ready})))
              "one proposal for the round"))))))

;; -----------------------------------------------------------------------------
;; A parent perfect on its minibatch ends the round
;; -----------------------------------------------------------------------------

(deftest perfect-parent-skips-the-proposer-and-publishes-the-skip
  (testing "no proposer call; the skipped comparison is published so completion is not blocked"
    (with-loop-context [ctx]
      (let [counts (atom {})
            calls (atom [])
            seeded (seed! ctx {:trainset (examples "TRAIN" 4) :valset (examples "VAL" 4)})
            {:keys [optimization-id parent-id]} seeded]
        (run-round! ctx seeded (counting-metric counts (constantly 1.0)) calls)
        (is (empty? @calls) "the proposer is never called for a perfect parent")
        (is (empty? (events-of ctx optimization-id #{:gepa/proposal-ready})))
        (let [decided (events-of ctx optimization-id #{:gepa/subsample-evaluated})]
          (is (= 1 (count decided)) "the skipped comparison is published")
          (is (true? (:parent-perfect-skip? (first decided))))
          (is (= parent-id (:parent-id (first decided))))
          (is (false? (:accepted? (first decided)))))
        (let [done (tu/process-command! ctx
                     {:command/name :gepa/complete-optimization
                      :optimization-id optimization-id})]
          (is (not (:cognitect.anomalies/category done))
              "completion is not blocked by an unpublished comparison"))))))

(deftest perfect-parent-still-reflects-when-skipping-is-off
  (testing "skip-perfect-score false leaves the round to the proposer"
    (with-loop-context [ctx]
      (let [counts (atom {})
            calls (atom [])
            seeded (seed! ctx {:trainset (examples "TRAIN" 4) :valset (examples "VAL" 4)
                               :config {:skip-perfect-score false}})]
        (run-round! ctx seeded (counting-metric counts (constantly 1.0)) calls)
        (is (= 1 (count @calls)))))))

;; -----------------------------------------------------------------------------
;; A failed evaluation is never a score
;; -----------------------------------------------------------------------------

(deftest unmanaged-metric-exception-is-not-a-zero
  (testing "a metric that throws produces no grade"
    (with-loop-context [ctx]
      (let [sheet-id (sheet/build-workflow! ctx (echo-workflow))
            thrown (try
                     (tp/execute-unmanaged-workflow
                       ctx sheet-id {"default" "x"} {"input" "TRAIN-0"}
                       (fn [_ _] (throw (ex-info "metric exploded" {}))))
                     (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo thrown)
            "the unmanaged path returned a value instead of refusing to grade")
        (is (= :failed (:gepa/evaluation-status (ex-data thrown))))))))

(deftest unmanaged-failed-workflow-is-not-scored
  (testing "a workflow that failed is not graded on an empty output"
    (with-loop-context [ctx]
      (let [sheet-id (sheet/build-workflow! ctx (failing-workflow))
            graded (atom 0)
            thrown (try
                     (tp/execute-unmanaged-workflow
                       ctx sheet-id {"default" "x"} {"input" "TRAIN-0"}
                       (fn [_ _] (swap! graded inc) 1.0))
                     (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo thrown)
            "the unmanaged path returned a value for a failed workflow")
        (is (zero? @graded) "the metric never saw the empty output")
        (is (= :failed (:gepa/evaluation-status (ex-data thrown))))))))

(deftest managed-metric-exception-records-no-grade
  (testing "the managed round records no score and proposes nothing"
    (with-loop-context [ctx]
      (let [calls (atom [])
            seeded (seed! ctx {:trainset (examples "TRAIN" 4) :valset (examples "VAL" 4)})
            {:keys [optimization-id]} seeded]
        (run-round! ctx seeded (fn [_ _] (throw (ex-info "metric exploded" {}))) calls)
        (is (empty? @calls) "no proposer call without a graded parent")
        (is (empty? (events-of ctx optimization-id #{:gepa/task-call-completed}))
            "no task result was recorded for the failed grade")
        (is (empty? (events-of ctx optimization-id #{:gepa/proposal-ready})))))))

;; -----------------------------------------------------------------------------
;; The round is a durable, resumable boundary
;; -----------------------------------------------------------------------------

(def ^:private real-execute tp/execute-workflow-with-instructions)

(defn- dying-after
  "Run the first `n` parent tasks for real, then behave like a worker that died:
   the evaluation ends in a recovery-required status."
  [n]
  (let [done (atom 0)]
    (fn [& [_ _ _ _ _ call-context :as args]]
      (if (and (= :subsample-parent (:role call-context)) (>= @done n))
        (throw (ex-info "worker died" {:gepa/evaluation-status :recovery-required}))
        (do (swap! done inc) (apply real-execute args))))))

(defn- interrupt-round!
  "Start a round whose parent evaluation is interrupted after one completed task."
  [ctx seeded metric calls]
  (with-redefs [tp/execute-workflow-with-instructions (dying-after 1)]
    (run-round! ctx seeded metric calls)))

(deftest interrupted-parent-evaluation-resumes-the-same-round
  (testing "resume! continues the recorded round without running completed tasks again"
    (with-loop-context [ctx]
      (let [counts (atom {})
            calls (atom [])
            metric (counting-metric counts (constantly 0.0))
            seeded (seed! ctx {:trainset (examples "TRAIN" 5) :valset (examples "VAL" 4)})
            {:keys [optimization-id]} seeded]
        (interrupt-round! ctx seeded metric calls)
        (let [[request :as requests] (requested-rounds ctx optimization-id)]
          (is (= 1 (count requests)) "the round was recorded before any evaluation")
          (is (empty? @calls) "no proposer call yet")
          (is (empty? (events-of ctx optimization-id #{:gepa/proposal-ready})))
          (is (= 1 (count (events-of ctx optimization-id #{:gepa/task-call-completed})))
              "one parent task completed before the interruption")
          (let [resumed (with-redefs [tp/generate-mutated-instruction (recording-proposer calls)]
                          (gepa/resume! (assoc ctx :gepa/metric-fn metric) optimization-id))
                ready (proposal-ready ctx optimization-id)
                size (count (:subsample-indices request))
                completed (events-of ctx optimization-id #{:gepa/task-call-completed})
                dispatched (events-of ctx optimization-id #{:gepa/task-attempt-dispatched})]
            (is (= :resumed (:status resumed)))
            (is (= :parent-minibatch-evaluation (:boundary resumed)))
            (is (= 1 (count @calls)) "one proposer call")
            (is (= (:parent-id request) (:parent-id (first @calls))))
            (is (= (:component request) (:component (first @calls))))
            (is (= (:subsample-indices request) (:subsample-indices ready)) "same minibatch")
            (is (= (:parent-id request) (:parent-id ready)))
            (is (= size (count completed)) "each parent task completed exactly once")
            (is (= size (count dispatched)) "no completed task was dispatched again")
            (is (= size (reduce + (vals @counts))) "no completed task was charged again")))))))

(deftest resuming-twice-is-safe
  (testing "a second resume! makes no second proposal"
    (with-loop-context [ctx]
      (let [counts (atom {})
            calls (atom [])
            metric (counting-metric counts (constantly 0.0))
            seeded (seed! ctx {:trainset (examples "TRAIN" 5) :valset (examples "VAL" 4)})
            {:keys [optimization-id]} seeded
            resume! #(with-redefs [tp/generate-mutated-instruction (recording-proposer calls)]
                       (gepa/resume! (assoc ctx :gepa/metric-fn metric) optimization-id))]
        (interrupt-round! ctx seeded metric calls)
        (resume!)
        (let [after-first (reduce + (vals @counts))]
          (resume!)
          (is (= 1 (count @calls)) "one proposer call across both resumes")
          (is (= 1 (count (events-of ctx optimization-id #{:gepa/proposal-ready}))))
          (is (= after-first (reduce + (vals @counts))) "no extra metric call from the second resume"))))))

(deftest a-round-with-its-proposal-is-not-resumed-as-a-round
  (testing "the later boundary wins and the proposer is not called again"
    (with-loop-context [ctx]
      (let [counts (atom {})
            calls (atom [])
            metric (counting-metric counts (constantly 0.0))
            seeded (seed! ctx {:trainset (examples "TRAIN" 5) :valset (examples "VAL" 4)})
            {:keys [optimization-id]} seeded]
        (run-round! ctx seeded metric calls)
        (is (= 1 (count (events-of ctx optimization-id #{:gepa/proposal-ready}))))
        (let [resumed (with-redefs [tp/generate-mutated-instruction (recording-proposer calls)]
                        (gepa/resume! (assoc ctx :gepa/metric-fn metric) optimization-id))]
          (is (= :proposal-ready (:boundary resumed)))
          (is (= 1 (count @calls)) "no second proposer call"))))))

(deftest a-skipped-round-is-not-resumed
  (testing "a published skip leaves nothing to recover"
    (with-loop-context [ctx]
      (let [counts (atom {})
            calls (atom [])
            metric (counting-metric counts (constantly 1.0))
            seeded (seed! ctx {:trainset (examples "TRAIN" 4) :valset (examples "VAL" 4)})
            {:keys [optimization-id]} seeded]
        (run-round! ctx seeded metric calls)
        (let [resumed (with-redefs [tp/generate-mutated-instruction (recording-proposer calls)]
                        (gepa/resume! (assoc ctx :gepa/metric-fn metric) optimization-id))]
          (is (= :no-recoverable-boundary (:status resumed)))
          (is (empty? @calls) "no proposer call"))))))

(deftest recovery-request-reaches-the-interrupted-round
  (testing ":gepa/request-recovery drives resume! to the recorded round"
    (with-loop-context [ctx]
      (let [counts (atom {})
            calls (atom [])
            metric (counting-metric counts (constantly 0.0))
            seeded (seed! ctx {:trainset (examples "TRAIN" 5) :valset (examples "VAL" 4)})
            {:keys [optimization-id]} seeded]
        (interrupt-round! ctx seeded metric calls)
        (reset! open-gate? true)
        (with-redefs [tp/generate-mutated-instruction (recording-proposer calls)]
          (reset! (:metric-ref seeded) metric)
          (let [requested (tu/process-command! ctx {:command/name :gepa/request-recovery
                                                    :optimization-id optimization-id})]
            (is (= :requested (get-in requested [:command/result :status])))
            (loop [n 0]
              (when (and (< n 300)
                         (empty? (events-of ctx optimization-id #{:gepa/proposal-ready})))
                (Thread/sleep 100)
                (recur (inc n))))))
        (is (= 1 (count (events-of ctx optimization-id #{:gepa/proposal-ready})))
            "the recovery request carried the interrupted round to its proposal")))))

;; -----------------------------------------------------------------------------
;; One round per iteration
;; -----------------------------------------------------------------------------

(deftest two-requests-for-one-iteration-record-one-round
  (testing "a round is identified by its optimization and iteration, whatever parent it names"
    (with-loop-context [ctx]
      (let [seeded (seed! ctx {:trainset (examples "TRAIN" 5) :valset (examples "VAL" 4)})
            {:keys [optimization-id parent-id candidate-id]} seeded
            request! (fn [parent]
                       (tu/process-command! ctx
                         {:command/name :gepa/request-parent-minibatch-evaluation
                          :optimization-id optimization-id :parent-id parent
                          :component "default" :iteration 7 :subsample-indices [0 1 2]}))
            first-answer (request! parent-id)
            second-answer (request! candidate-id)
            rounds (requested-rounds ctx optimization-id)]
        (is (= :requested (get-in first-answer [:command/result :status])))
        (is (= :already-requested (get-in second-answer [:command/result :status])))
        (is (= 1 (count rounds)) "exactly one round recorded")
        (is (= parent-id (:parent-id (first rounds))) "the first request names the round's parent")))))

(deftest redelivered-candidate-evaluated-opens-no-second-round
  (testing "a redelivery whose random choice lands on another parent is only a redelivery"
    (with-loop-context [ctx]
      (let [calls (atom [])
            seeded (seed! ctx {:trainset (examples "TRAIN" 5) :valset (examples "VAL" 4)})
            {:keys [optimization-id parent-id candidate-id valset]} seeded
            event {:optimization-id optimization-id :candidate-id candidate-id
                   :scores (mapv (constantly 0.0) valset) :aggregate-score 0.0}]
        (with-redefs [rm/sample-pareto-frontier (constantly parent-id)]
          (real-on-candidate-evaluated (assoc ctx :event event)))
        (with-redefs [rm/sample-pareto-frontier (constantly candidate-id)]
          (real-on-candidate-evaluated (assoc ctx :event event)))
        (let [rounds (requested-rounds ctx optimization-id)]
          (is (= 1 (count rounds)) "one round for the iteration")
          (is (= parent-id (:parent-id (first rounds)))))))))
