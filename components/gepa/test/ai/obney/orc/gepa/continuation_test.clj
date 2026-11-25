(ns ai.obney.orc.gepa.continuation-test
  "Training continuation on a durable store: ledger conflict retry, inheritance
   switches, and score reuse only for the same evidence. Deterministic, with no
   model or network calls: LEVEL-k instructions answer example n correctly when
   k >= n."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.control-plane.interface :as control-plane]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.grain-test-utils.interface :as tu]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.gepa.interface :as gepa]
            [ai.obney.orc.gepa.interface.schemas]
            [ai.obney.orc.gepa.core.task-ledger :as ledger]
            [ai.obney.orc.gepa.core.todo-processors :as tp]
            [cognitect.anomalies :as anom]))

;; ---------------------------------------------------------------------------
;; Deterministic fixture
;; ---------------------------------------------------------------------------

(def route-schema [:enum "lookup" "clarify"])

(defn wf []
  (sheet/workflow "continuation-fixture"
    (sheet/blackboard {:request :string :route route-schema})
    (sheet/llm-decision "route" :instruction "LEVEL-0" :reads [:request] :writes [:route])))

(defn level [s] (some-> (re-find #"LEVEL-(\d+)" (str s)) second Long/parseLong))
(defn ex-n [inputs] (some->> (vals inputs) (map str) (some #(re-find #"\d+" %)) Long/parseLong))

(defn fake-predict [_p module inputs options]
  (let [k (level (:instructions module)) i (ex-n inputs)
        outputs {(-> module :outputs first :name) (if (>= k i) "lookup" "clarify")}]
    (if (:with-metadata? options)
      {:outputs outputs :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}
       :model "det" :raw-response (pr-str outputs)}
      outputs)))

(defn fake-proposer [_ctx parent-instrs component & _]
  (str "LEVEL-" (inc (level (get parent-instrs component)))))

(def step (atom 1))
(defn stepping-proposer [_c parent-instrs component & _]
  (str "LEVEL-" (+ @step (level (get parent-instrs component)))))

(defn metric [_input output]
  (if (= "lookup" (str (or (:route output) (get output "route")))) 1.0 0.0))

(def metric-id "continuation/route-metric")

(def data (mapv (fn [i] {"request" (str "ex-" i)}) (range 6)))

(defmacro with-cp
  "In-memory context with the control plane, deterministic model and proposer."
  [[ctx proposer] & body]
  `(tu/with-test-context [~ctx]
     (with-redefs [llm/predict fake-predict tp/generate-mutated-instruction ~proposer]
       (let [cp# (control-plane/start {:event-store (:event-store ~ctx) :cache (:cache ~ctx)
                                       :context ~ctx :heartbeat-interval-ms 100
                                       :staleness-threshold-ms 1000})]
         (try ~@body (finally (control-plane/stop cp#)))))))

(defn run!
  [ctx sid {:keys [budget valset config top] :or {budget 30 valset data}}]
  (:optimization-id
    (gepa/optimize! ctx (merge {:sheet-id sid :trainset data :valset valset
                                :metric-fn metric :metric-id metric-id
                                :config (merge {:max-metric-calls budget
                                                :reflection-minibatch-size 3} config)
                                :block? true :timeout-ms 120000}
                               top))))

(defn- read-events [ctx opt types]
  (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :types types
                                        :tags #{[:optimization opt]}})))

(defn cands [ctx opt] (read-events ctx opt #{:gepa/candidate-created}))
(defn evaluated [ctx opt] (read-events ctx opt #{:gepa/candidate-evaluated}))
(defn inherited [ctx opt]
  (filterv #(= "inherited-from-previous-run" (:mutation-reason %)) (cands ctx opt)))

(defn- evals-by-candidate [ctx opt]
  (into {} (map (juxt :candidate-id identity)) (evaluated ctx opt)))

;; ---------------------------------------------------------------------------
;; H. Conflicted ledger writes retry on every store
;; ---------------------------------------------------------------------------

(deftest conflict-retries-whatever-key-the-store-reports
  (doseq [k [:ai.obney.grain.event-store-sqlite-v3.core/cas
             :ai.obney.grain.event-store-postgres-v3.core/cas
             :cas]]
    (let [calls (atom 0)]
      (with-redefs [cp/process-command
                    (fn [_]
                      (if (< (swap! calls inc) 4)
                        {::anom/category ::anom/conflict ::anom/message "cas" k {:x 1}}
                        {:command/result {:status :opened}}))]
        (is (= {:status :opened}
               (ledger/open-boundary! {:gepa/ledger-retry {:max-backoff-ms 2}} {}))
            (str "a conflict reported under " k " is retried"))
        (is (= 4 @calls))))))

(deftest exhausted-conflict-retries-leave-a-recoverable-outcome
  (let [calls (atom 0)]
    (with-redefs [cp/process-command
                  (fn [_] (swap! calls inc)
                    {::anom/category ::anom/conflict ::anom/message "cas"
                     :ai.obney.grain.event-store-sqlite-v3.core/cas {:x 1}})]
      (let [r (ledger/claim! {:gepa/ledger-retry {:max-attempts 5 :max-backoff-ms 1}} {})]
        (is (= :recovery-required (:status r)) "resume! can continue it")
        (is (= :ledger-conflict (:reason r)))
        (is (= 6 @calls) "one attempt plus the bounded retries")))))

(deftest a-refusal-decided-by-the-ledger-is-not-retried
  (let [calls (atom 0)
        refusal {::anom/category ::anom/conflict ::anom/message "Workflow result is immutable"}]
    (with-redefs [cp/process-command (fn [_] (swap! calls inc) refusal)]
      (is (= refusal (ledger/record-workflow! {:gepa/ledger-retry {:max-backoff-ms 1}} {})))
      (is (= 1 @calls) "only a store conflict is retried"))))

(deftest default-retry-cap-is-well-above-32
  (let [calls (atom 0)]
    (with-redefs [cp/process-command
                  (fn [_]
                    (if (< (swap! calls inc) 100)
                      {::anom/category ::anom/conflict ::anom/message "cas"
                       :ai.obney.grain.event-store-sqlite-v3.core/cas {:x 1}}
                      {:command/result {:status :renewed}}))]
      (is (= {:status :renewed}
             (ledger/renew! {:gepa/ledger-retry {:max-backoff-ms 1}} {}))))))

(defn- sqlite-file []
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                       "gepa-continuation" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (str (io/file dir "events.db"))))

(def tenant (java.util.UUID/fromString "11111111-1111-1111-1111-111111111111"))

(defmacro with-sqlite
  "Fresh async context (processors running) on a durable SQLite file."
  [[ctx db] & body]
  `(with-redefs [llm/predict fake-predict tp/generate-mutated-instruction fake-proposer]
     (h/with-async-test-context
       [~ctx {:event-store-conn {:type :sqlite :database-file ~db :maximum-pool-size 2}
              :context {:tenant-id tenant}}]
       ~@body)))

(defn- optimize-durable! [ctx sid {:keys [budget inherit timeout-ms] :or {budget 50 inherit true timeout-ms 60000}}]
  (gepa/optimize! ctx {:sheet-id sid :trainset data :valset data :metric-fn metric
                       :metric-id metric-id
                       :config {:max-metric-calls budget :reflection-minibatch-size 3
                                :inherit-from-previous inherit}
                       :block? true :timeout-ms timeout-ms}))

(deftest durable-store-run-with-concurrent-tasks-completes
  (let [db (sqlite-file)]
    (with-sqlite [ctx db]
      (let [sid (sheet/build-workflow! ctx (wf))
            r (optimize-durable! ctx sid {:inherit false})]
        (is (= :completed (:status r)) "a SQLite-backed run does not stall on a ledger conflict")))))

;; ---------------------------------------------------------------------------
;; A. :inherit-from-previous false given to optimize! takes effect
;; ---------------------------------------------------------------------------

(deftest a-top-level-inherit-false-inherits-nothing
  (with-cp [ctx stepping-proposer]
    (let [sid (sheet/build-workflow! ctx (wf))
          prior (run! ctx sid {:config {:inherit-from-previous false}})
          top-false (run! ctx sid {:top {:inherit-from-previous false}})
          cfg-false (run! ctx sid {:config {:inherit-from-previous false}})
          default (run! ctx sid {})]
      (is (zero? (count (inherited ctx prior))))
      (is (zero? (count (inherited ctx cfg-false))) "flag inside :config")
      (is (zero? (count (inherited ctx top-false))) "flag at the optimize! level")
      (is (seq (inherited ctx default)) "inheritance stays on by default"))))

;; ---------------------------------------------------------------------------
;; B. :inherit-from names one run
;; ---------------------------------------------------------------------------

(deftest b-inherit-from-names-one-run
  (with-cp [ctx stepping-proposer]
    (let [sid (sheet/build-workflow! ctx (wf))
          a (do (reset! step 1) (run! ctx sid {:config {:inherit-from-previous false}}))
          b (do (reset! step 2) (run! ctx sid {:config {:inherit-from-previous false}}))
          c (do (reset! step 1) (run! ctx sid {:top {:inherit-from a}}))
          inh (inherited ctx c)]
      (is (seq inh) "C inherits something")
      (is (every? #(= a (:source-optimization-id %)) inh)
          "every inherited candidate comes from the named run")
      (is (not-any? #(= b (:source-optimization-id %)) inh)))))

;; ---------------------------------------------------------------------------
;; C. Scores are reused only for an identical example
;; ---------------------------------------------------------------------------

(defn- actual-scores [instr valset]
  (mapv (fn [ex] (if (>= (level (get instr "route")) (ex-n ex)) 1.0 0.0)) valset))

(deftest c-reordered-valset-reuses-scores-by-example-not-position
  (with-cp [ctx stepping-proposer]
    (let [sid (sheet/build-workflow! ctx (wf))
          v2 (vec (reverse data))
          _ (reset! step 1)
          _ (run! ctx sid {:config {:inherit-from-previous false}})
          r2 (run! ctx sid {:valset v2 :budget 12})
          inh (inherited ctx r2)
          evals (evals-by-candidate ctx r2)]
      (is (seq inh))
      (doseq [c inh :let [e (get evals (:candidate-id c))]]
        (is (= (actual-scores (:instructions c) v2) (:scores e))
            "the recorded scores are the candidate's scores on this run's valset")
        (is (zero? (:metric-calls e)) "identical examples cost no calls")))))

(deftest c-identical-valset-reuses-every-score-at-zero-calls
  (with-cp [ctx stepping-proposer]
    (let [sid (sheet/build-workflow! ctx (wf))
          _ (reset! step 1)
          _ (run! ctx sid {:config {:inherit-from-previous false}})
          r2 (run! ctx sid {:budget 12})
          inh (inherited ctx r2)
          evals (evals-by-candidate ctx r2)]
      (is (seq inh))
      (is (every? #(zero? (:metric-calls (get evals (:candidate-id %)))) inh)))))

(deftest c-changed-examples-are-evaluated-and-charged
  (with-cp [ctx stepping-proposer]
    (let [sid (sheet/build-workflow! ctx (wf))
          v3 (conj (subvec data 0 4) {"request" "ex-7"} {"request" "ex-8"})
          _ (reset! step 1)
          _ (run! ctx sid {:config {:inherit-from-previous false}})
          r2 (run! ctx sid {:valset v3 :budget 60})
          inh (inherited ctx r2)
          evals (evals-by-candidate ctx r2)]
      (is (seq inh))
      (doseq [c inh :let [e (get evals (:candidate-id c))]]
        (is (= (actual-scores (:instructions c) v3) (:scores e)))
        (is (pos? (:metric-calls e)) "examples not seen before are evaluated in this run")))))

;; ---------------------------------------------------------------------------
;; D. An edited workflow or metric never reuses scores from before
;; ---------------------------------------------------------------------------

(defn shift-fn [{:keys [inputs]}]
  {:shifted (str "ex-" (+ 3 (ex-n inputs)))})

(defn wf-edited []
  (sheet/workflow "continuation-fixture"
    (sheet/blackboard {:request :string :shifted :string :route route-schema})
    (sheet/sequence "root"
      (sheet/code "shift" :fn "ai.obney.orc.gepa.continuation-test/shift-fn"
                  :reads [:request] :writes [:shifted])
      (sheet/llm-decision "route" :instruction "LEVEL-0" :reads [:shifted] :writes [:route]))))

(deftest d-edited-workflow-reevaluates-inherited-candidates
  (with-cp [ctx stepping-proposer]
    (let [sid1 (sheet/build-workflow! ctx (wf))
          _ (reset! step 1)
          _ (run! ctx sid1 {:config {:inherit-from-previous false}})
          sid2 (sheet/build-workflow! ctx (wf-edited))
          r2 (run! ctx sid2 {:budget 60})
          inh (inherited ctx r2)
          evals (evals-by-candidate ctx r2)
          actual (fn [instr] (mapv (fn [ex] (if (>= (level (get instr "route")) (+ 3 (ex-n ex))) 1.0 0.0)) data))]
      (is (= sid1 sid2) "the sheet id stays name-derived")
      (is (seq inh))
      (doseq [c inh :let [e (get evals (:candidate-id c))]]
        (is (= (actual (:instructions c)) (:scores e)) "scores are those of the current tree")
        (is (pos? (:metric-calls e)) "the edit forces re-evaluation")))))

(deftest d-changed-metric-reevaluates-inherited-candidates
  (with-cp [ctx stepping-proposer]
    (let [sid (sheet/build-workflow! ctx (wf))
          _ (reset! step 1)
          _ (run! ctx sid {:config {:inherit-from-previous false}})
          r2 (run! ctx sid {:budget 60 :top {:metric-id "continuation/other-metric"}})
          inh (inherited ctx r2)
          evals (evals-by-candidate ctx r2)]
      (is (seq inh))
      (is (every? #(pos? (:metric-calls (get evals (:candidate-id %)))) inh)))))

;; ---------------------------------------------------------------------------
;; Persistence: two rounds, each in a fresh context on one SQLite store
;; ---------------------------------------------------------------------------

(deftest two-rounds-on-one-durable-store-continue-each-other
  (let [db (sqlite-file)
        round-1 (atom nil)]
    (with-sqlite [ctx db]
      (let [sid (sheet/build-workflow! ctx (wf))
            r (optimize-durable! ctx sid {:inherit false})]
        (is (= :completed (:status r)))
        (reset! round-1 {:sid sid :opt (:optimization-id r)})))
    (with-sqlite [ctx db]
      (let [sid (sheet/build-workflow! ctx (wf))
            r (optimize-durable! ctx sid {:inherit true})
            opt (:optimization-id r)
            inh (inherited ctx opt)
            evals (evals-by-candidate ctx opt)]
        (is (= (:sid @round-1) sid) "the rebuilt workflow keeps its identity")
        (is (= :completed (:status r)))
        (is (seq inh) "round 2 starts with round 1's candidates")
        (is (every? #(= (:opt @round-1) (:source-optimization-id %)) inh))
        (is (every? #(zero? (:metric-calls (get evals (:candidate-id %)))) inh)
            "inherited candidates cost no calls on the same evidence")))))

;; ---------------------------------------------------------------------------
;; A run whose next round is refused because the budget is spent still finishes
;; ---------------------------------------------------------------------------

(defn- exhausted-run!
  "A running optimization with one fully evaluated candidate and its whole
   metric budget recorded as spent."
  [ctx]
  (let [opt (get-in (tu/process-command! ctx
                      {:command/name :gepa/start-optimization :sheet-id (random-uuid)
                       :trainset [{"request" "ex-1"}] :valset [{"request" "ex-1"}]
                       :config {:max-metric-calls 6}})
                    [:command/result :optimization-id])
        cand (get-in (tu/process-command! ctx
                       {:command/name :gepa/create-candidate :optimization-id opt
                        :instructions {"route" "LEVEL-0"} :parent-ids [nil] :mutation-reason nil})
                     [:command/result :candidate-id])]
    (tu/process-command! ctx
      {:command/name :gepa/record-evaluation-result :optimization-id opt :candidate-id cand
       :scores [1.0] :trace-ids [] :metric-calls 6})
    {:optimization-id opt :candidate-id cand}))

(defn- status [ctx opt]
  (:status (ai.obney.orc.gepa.core.read-models/get-optimization-summary ctx opt)))

(deftest proposal-refused-for-budget-finishes-the-run
  (tu/with-test-context [ctx]
    (let [{:keys [optimization-id candidate-id]} (exhausted-run! ctx)]
      (is (= :running (status ctx optimization-id)))
      (tp/on-proposal-ready
        (assoc ctx :event {:optimization-id optimization-id :parent-id candidate-id
                           :proposed-instructions {"route" "LEVEL-1"} :component-updated "route"
                           :subsample-indices [0] :iteration 1}))
      (is (= :completed (status ctx optimization-id))
          "the refused subsample evaluation ends the run instead of leaving it running"))))
