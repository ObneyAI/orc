(ns ai.obney.orc.gepa.rounds-add-up-test
  "Splitting a metric budget into several runs must add up like one run:
   the minibatch schedule continues, at most one mutation round is undecided,
   the seed reuses recorded scores, and a round opens only when the budget can
   finish it. Deterministic, with no model or network calls."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.grain-test-utils.interface :as tu]
            [ai.obney.orc.gepa.continuation-test :as ct]
            [ai.obney.orc.gepa.core.sampler :as sampler]
            [ai.obney.orc.gepa.core.todo-processors :as tp]
            [ai.obney.orc.gepa.core.read-models :as rm]
            [ai.obney.orc.gepa.core.task-ledger :as ledger]
            [ai.obney.orc.gepa.interface :as gepa]
            [ai.obney.orc.orc-service.interface :as sheet]))

(def ^:private sqlite-file @#'ct/sqlite-file)

(defn- events [ctx opt types]
  (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :types types
                                        :tags #{[:optimization opt]}})))

(def ^:private timeline-types
  #{:gepa/candidate-created :gepa/candidate-evaluated
    :gepa/parent-minibatch-evaluation-requested :gepa/subsample-evaluated})

(defn- timeline [ctx opt] (events ctx opt timeline-types))

(defn- requests [ctx opt]
  (filterv #(= :gepa/parent-minibatch-evaluation-requested (:event/type %))
           (timeline ctx opt)))

(defn- batch [i] (vec (distinct (sampler/epoch-shuffled-batch-sampler 6 3 i))))

(defn- position
  "The schedule position a round used: recorded, else the iteration it ran at."
  [e] (or (:position e) (:iteration e)))

(defn- starting? [c]
  (or (nil? (:mutation-reason c)) (= "inherited-from-previous-run" (:mutation-reason c))))

(defn- one-round-at-a-time
  "Walk a run's timeline: returns violations of 'at most one undecided round,
   and none before every starting candidate has an evaluation'."
  [tl]
  (let [starters (into #{} (comp (filter #(= :gepa/candidate-created (:event/type %)))
                                 (filter starting?) (map :candidate-id)) tl)
        mutation (into #{} (comp (filter #(= :gepa/candidate-created (:event/type %)))
                                 (remove starting?) (map :candidate-id)) tl)]
    (loop [[e & more] tl open? false evaluated #{} bad []]
      (if-not e
        bad
        (case (:event/type e)
          :gepa/candidate-evaluated
          (recur more (if (contains? mutation (:candidate-id e)) false open?)
                 (conj evaluated (:candidate-id e)) bad)
          :gepa/subsample-evaluated
          (recur more (if (:accepted? e) open? false) evaluated bad)
          :gepa/parent-minibatch-evaluation-requested
          (recur more true evaluated
                 (cond-> bad
                   open? (conj [:second-round-while-undecided (:iteration e)])
                   (not (every? evaluated starters)) (conj [:round-before-starters-evaluated (:iteration e)])))
          (recur more open? evaluated bad))))))

(defn- run-twice-on-sqlite!
  "Run n rounds of `budget`, each in a fresh context on one SQLite file with the
   workflow rebuilt. Returns the per-round maps {:opt :status :level :calls
   :requests}."
  [db n budget]
  (loop [i 1 out []]
    (if (> i n)
      out
      (recur (inc i)
             (conj out
                   (ct/with-sqlite [ctx db]
                     (let [sid (sheet/build-workflow! ctx (ct/wf))
                           r (gepa/optimize! ctx {:sheet-id sid :trainset ct/data :valset ct/data
                                                  :metric-fn ct/metric :metric-id ct/metric-id
                                                  :config {:max-metric-calls budget
                                                           :reflection-minibatch-size 3
                                                           :inherit-from-previous (> i 1)}
                                                  :block? true :timeout-ms 240000})
                           opt (:optimization-id r)
                           p (:query/result (gepa/get-progress ctx opt))]
                       {:opt opt :status (:status r)
                        :level (ct/level (get-in r [:best-candidate :instructions "route"]))
                        :calls (get-in p [:metric-calls :used])
                        :requests (mapv #(select-keys % [:iteration :position :subsample-indices])
                                        (requests ctx opt))
                        :first-evals (mapv #(select-keys % [:metric-calls])
                                           (events ctx opt #{:gepa/candidate-evaluated}))})))))))

;; ---------------------------------------------------------------------------
;; E. The minibatch schedule continues across runs
;; ---------------------------------------------------------------------------

(deftest e-a-continuing-run-draws-the-minibatch-after-the-last-one-of-the-run-it-continues
  (let [[r1 r2] (run-twice-on-sqlite! (sqlite-file) 2 30)
        last-1 (position (last (:requests r1)))]
    (is (= :completed (:status r1)))
    (is (= :completed (:status r2)))
    (is (seq (:requests r1)))
    (is (seq (:requests r2)))
    (is (= (batch (inc last-1)) (:subsample-indices (first (:requests r2))))
        "round 2's first minibatch is the one right after round 1's last")))

(deftest e-a-run-without-inheritance-draws-today's-minibatches
  (let [[r1] (run-twice-on-sqlite! (sqlite-file) 1 30)
        reqs (:requests r1)]
    (is (>= (count reqs) 2))
    (is (= 1 (:iteration (first reqs))) "the seed's evaluation is iteration 1")
    (doseq [rq (take 2 reqs)]
      (is (= (batch (:iteration rq)) (:subsample-indices rq))
          "the position of a single run is its iteration"))))

(deftest e-round-three-continues-after-round-two-not-round-one
  (let [[r1 r2 r3] (run-twice-on-sqlite! (sqlite-file) 3 30)
        last-2 (position (last (:requests r2)))]
    (is (every? #(= :completed (:status %)) [r1 r2 r3]))
    (is (seq (:requests r3)))
    (is (= (batch (inc last-2)) (:subsample-indices (first (:requests r3)))))
    (is (> (position (first (:requests r3))) (position (last (:requests r1))))
        "round 3 is past everything round 1 drew")))

;; ---------------------------------------------------------------------------
;; F. One undecided round at a time
;; ---------------------------------------------------------------------------

(defn- two-prior-runs
  "Two runs with different mutation steps on one workflow, so a third run
   inherits more than one starting candidate."
  [ctx sid]
  (reset! ct/step 1)
  (ct/run! ctx sid {:config {:inherit-from-previous false}})
  (reset! ct/step 2)
  (ct/run! ctx sid {:config {:inherit-from-previous false}}))

(deftest f-inheriting-runs-keep-one-round-undecided-at-a-time
  (ct/with-cp [ctx ct/stepping-proposer]
    (let [sid (sheet/build-workflow! ctx (ct/wf))
          _ (two-prior-runs ctx sid)
          _ (reset! ct/step 1)
          opt (ct/run! ctx sid {:budget 60})
          tl (timeline ctx opt)]
      (is (>= (count (ct/inherited ctx opt)) 1) "the run starts with inherited candidates")
      (is (seq (requests ctx opt)))
      (is (= [] (one-round-at-a-time tl))))))

(deftest f-a-second-request-while-a-round-is-undecided-opens-no-round
  (tu/with-test-context [ctx]
    (let [opt (get-in (tu/process-command! ctx
                        {:command/name :gepa/start-optimization :sheet-id (random-uuid)
                         :trainset ct/data :valset ct/data
                         :config {:max-metric-calls 100 :reflection-minibatch-size 3}})
                      [:command/result :optimization-id])
          cand (get-in (tu/process-command! ctx
                         {:command/name :gepa/create-candidate :optimization-id opt
                          :instructions {"route" "LEVEL-0"} :parent-ids [nil] :mutation-reason nil})
                       [:command/result :candidate-id])
          _ (tu/process-command! ctx
              {:command/name :gepa/record-evaluation-result :optimization-id opt :candidate-id cand
               :scores [1.0 0.0 0.0 0.0 0.0 0.0] :trace-ids [] :metric-calls 6})
          ask (fn [iteration]
                (tu/process-command! ctx
                  {:command/name :gepa/request-parent-minibatch-evaluation
                   :optimization-id opt :parent-id cand :component "route"
                   :iteration iteration :subsample-indices [0 1 2]}))]
      (ask 1)
      (ask 2)
      (ask 3)
      (is (= 1 (count (requests ctx opt))) "exactly one round is recorded"))))

(deftest f-no-round-opens-before-every-starting-candidate-is-evaluated
  (tu/with-test-context [ctx]
    (let [opt (get-in (tu/process-command! ctx
                        {:command/name :gepa/start-optimization :sheet-id (random-uuid)
                         :trainset ct/data :valset ct/data
                         :config {:max-metric-calls 200 :reflection-minibatch-size 3}})
                      [:command/result :optimization-id])
          mk (fn [instr reason]
               (get-in (tu/process-command! ctx
                         {:command/name :gepa/create-candidate :optimization-id opt
                          :instructions {"route" instr} :parent-ids [nil] :mutation-reason reason})
                       [:command/result :candidate-id]))
          seed (mk "LEVEL-0" nil)
          inh-1 (mk "LEVEL-1" "inherited-from-previous-run")
          inh-2 (mk "LEVEL-2" "inherited-from-previous-run")
          scores {seed [1.0 0.0 0.0 0.0 0.0 0.0] inh-1 [1.0 1.0 0.0 0.0 0.0 0.0]
                  inh-2 [1.0 1.0 1.0 0.0 0.0 0.0]}
          evaluate! (fn [c]
                      (let [r (tu/process-command! ctx
                                {:command/name :gepa/record-evaluation-result :optimization-id opt
                                 :candidate-id c :scores (scores c) :trace-ids [] :metric-calls 0})]
                        (tp/on-candidate-evaluated
                          (assoc ctx :event {:optimization-id opt :candidate-id c :scores (scores c)
                                             :aggregate-score (/ (reduce + (scores c)) 6)}))
                        r))]
      (evaluate! seed)
      (is (zero? (count (requests ctx opt))) "inherited candidates are still unevaluated")
      (evaluate! inh-1)
      (is (zero? (count (requests ctx opt))) "one starting candidate is still unevaluated")
      (evaluate! inh-2)
      (is (= 1 (count (requests ctx opt))) "the first round opens once all three have evaluations")
      (tp/on-candidate-evaluated
        (assoc ctx :event {:optimization-id opt :candidate-id inh-2 :scores (scores inh-2)
                           :aggregate-score 0.5}))
      (is (= 1 (count (requests ctx opt))) "a repeated or racing request opens no second round")
      (is (= [] (one-round-at-a-time (timeline ctx opt)))))))

(deftest f-a-doubled-delivery-of-the-evaluated-event-still-completes-with-one-chain
  (ct/with-cp [ctx ct/stepping-proposer]
    (let [orig @#'ai.obney.orc.gepa.core.todo-processors/on-candidate-evaluated
          sid (sheet/build-workflow! ctx (ct/wf))
          _ (two-prior-runs ctx sid)
          _ (reset! ct/step 1)
          opt (with-redefs [ai.obney.orc.gepa.core.todo-processors/on-candidate-evaluated
                            (fn [c] (let [f (future (orig c))] (orig c) @f))]
                (ct/run! ctx sid {:budget 60}))]
      (is (= [] (one-round-at-a-time (timeline ctx opt))))
      (is (= :completed (:status (:query/result (gepa/get-progress ctx opt))))))))

;; ---------------------------------------------------------------------------
;; G. The seed reuses recorded scores
;; ---------------------------------------------------------------------------

(defn- seed-eval [ctx opt]
  (let [seed (first (filter #(nil? (:mutation-reason %)) (ct/cands ctx opt)))]
    (first (filter #(= (:candidate-id seed) (:candidate-id %)) (ct/evaluated ctx opt)))))

(deftest g-a-seed-on-identical-evidence-costs-nothing
  (ct/with-cp [ctx ct/stepping-proposer]
    (let [sid (sheet/build-workflow! ctx (ct/wf))
          _ (reset! ct/step 1)
          _ (ct/run! ctx sid {:config {:inherit-from-previous false}})
          r2 (ct/run! ctx sid {:budget 30})]
      (is (zero? (:metric-calls (seed-eval ctx r2)))
          "the seed's scores were recorded by the run it inherits from"))))

(deftest g-a-seed-on-changed-evidence-is-evaluated-and-charged
  (ct/with-cp [ctx ct/stepping-proposer]
    (let [sid (sheet/build-workflow! ctx (ct/wf))
          v2 (assoc ct/data 5 {"request" "ex-9"})
          _ (reset! ct/step 1)
          _ (ct/run! ctx sid {:config {:inherit-from-previous false}})
          r2 (ct/run! ctx sid {:valset v2 :budget 30})
          e (seed-eval ctx r2)]
      (is (pos? (:metric-calls e)) "one changed example forces a full, charged evaluation"))))

(deftest g-a-run-that-inherits-nothing-scores-its-seed
  (ct/with-cp [ctx ct/stepping-proposer]
    (let [sid (sheet/build-workflow! ctx (ct/wf))
          _ (reset! ct/step 1)
          _ (ct/run! ctx sid {:config {:inherit-from-previous false}})
          r2 (ct/run! ctx sid {:config {:inherit-from-previous false}})]
      (is (= 6 (:metric-calls (seed-eval ctx r2)))))))

;; ---------------------------------------------------------------------------
;; H. A round opens only when the budget can finish it
;; ---------------------------------------------------------------------------

(defn- used-before-each-request
  "[used-metric-calls request] for every round request, by recorded charges."
  [tl]
  (loop [[e & more] tl used 0 out []]
    (if-not e
      out
      (case (:event/type e)
        :gepa/parent-minibatch-evaluation-requested (recur more used (conj out [used e]))
        (:gepa/candidate-evaluated :gepa/subsample-evaluated)
        (recur more (+ used (or (:metric-calls e) 0)) out)
        (recur more used out)))))

(deftest h-no-round-opens-that-the-budget-cannot-finish
  (doseq [budget [17 20 25 29 30 31 37 41]]
    (ct/with-cp [ctx ct/fake-proposer]
      (let [sid (sheet/build-workflow! ctx (ct/wf))
            opt (ct/run! ctx sid {:budget budget :config {:inherit-from-previous false}})
            tl (timeline ctx opt)
            created (into #{} (comp (filter #(= :gepa/candidate-created (:event/type %)))
                                    (map :candidate-id)) tl)
            evaluated (into #{} (comp (filter #(= :gepa/candidate-evaluated (:event/type %)))
                                      (map :candidate-id)) tl)]
        (doseq [[used rq] (used-before-each-request tl)]
          (is (<= (+ used 12) budget)
              (str "budget " budget ": a round opened at iteration " (:iteration rq)
                   " with " used " used and 12 needed")))
        (is (= created evaluated)
            (str "budget " budget ": every created candidate has a full evaluation"))
        (is (= :completed (:status (:query/result (gepa/get-progress ctx opt)))))))))

;; ---------------------------------------------------------------------------
;; Acceptance: rounds add up
;; ---------------------------------------------------------------------------

(deftest rounds-add-up-to-one-run
  (let [b 30
        chain (run-twice-on-sqlite! (sqlite-file) 3 b)
        single (first (ct/with-sqlite [ctx (sqlite-file)]
                        (let [sid (sheet/build-workflow! ctx (ct/wf))
                              r (gepa/optimize! ctx {:sheet-id sid :trainset ct/data :valset ct/data
                                                     :metric-fn ct/metric :metric-id ct/metric-id
                                                     :config {:max-metric-calls (* 3 b)
                                                              :reflection-minibatch-size 3
                                                              :inherit-from-previous false}
                                                     :block? true :timeout-ms 240000})]
                          [{:status (:status r)
                            :level (ct/level (get-in r [:best-candidate :instructions "route"]))}])))
        levels (mapv :level chain)]
    (println "ROUNDS-ADD-UP chain levels" levels "calls" (mapv :calls chain)
             "single" single)
    (is (every? #(= :completed (:status %)) chain))
    (is (>= (:level single) 2) "the single run improves at least twice")
    (is (apply <= levels) "the chain's best never falls")
    (is (> (peek levels) (first levels)) "the chain's best rises above round 1's")
    (is (<= (Math/abs (- (:level single) (peek levels))) 1)
        "the chain ends within one level of the single run")))

;; ---------------------------------------------------------------------------
;; Parent selection never names a dominated candidate
;; ---------------------------------------------------------------------------

(deftest a-dominated-candidate-is-never-sampled-as-a-parent
  (tu/with-test-context [ctx]
    (let [opt (get-in (tu/process-command! ctx
                        {:command/name :gepa/start-optimization :sheet-id (random-uuid)
                         :trainset ct/data :valset ct/data
                         :config {:max-metric-calls 200 :reflection-minibatch-size 3}})
                      [:command/result :optimization-id])
          add! (fn [instr reason scores]
                 (let [c (get-in (tu/process-command! ctx
                                   {:command/name :gepa/create-candidate :optimization-id opt
                                    :instructions {"route" instr} :parent-ids [nil]
                                    :mutation-reason reason})
                                 [:command/result :candidate-id])]
                   (tu/process-command! ctx
                     {:command/name :gepa/record-evaluation-result :optimization-id opt
                      :candidate-id c :scores scores :trace-ids [] :metric-calls 0})
                   (tu/process-command! ctx
                     {:command/name :gepa/update-frontier :optimization-id opt
                      :candidate-id c :scores scores})
                   c))
          dominated (add! "LEVEL-1" nil [1.0 1.0 0.0 0.0 0.0 0.0])
          dominator (add! "LEVEL-2" "inherited-from-previous-run" [1.0 1.0 1.0 0.0 0.0 0.0])
          picks (frequencies (repeatedly 300 #(rm/sample-pareto-frontier ctx opt)))]
      (is (= #{dominator} (set (keys picks))) (str "picked " picks))
      (is (not (contains? picks dominated))))))

(deftest no-round-names-a-dominated-parent
  (ct/with-cp [ctx ct/fake-proposer]
    (let [sid (sheet/build-workflow! ctx (ct/wf))
          opt (ct/run! ctx sid {:budget 120 :config {:inherit-from-previous false}})
          cands (into {} (map (juxt :candidate-id identity)) (ct/cands ctx opt))
          lvl (fn [id] (ct/level (get-in cands [id :instructions "route"])))]
      (is (seq (requests ctx opt)))
      ;; In this fixture a higher level answers a superset of examples, so a
      ;; parent below the highest evaluated level at its request is dominated.
      (loop [[e & more] (timeline ctx opt) best -1]
        (when e
          (case (:event/type e)
            :gepa/candidate-evaluated (recur more (max best (lvl (:candidate-id e))))
            :gepa/parent-minibatch-evaluation-requested
            (do (is (= best (lvl (:parent-id e))) "the parent is not dominated")
                (recur more best))
            (recur more best)))))))

;; ---------------------------------------------------------------------------
;; A changed validation set re-evaluates only its new and changed examples
;; ---------------------------------------------------------------------------

(deftest a-changed-validation-set-charges-only-its-new-and-changed-examples
  (ct/with-cp [ctx ct/stepping-proposer]
    (let [sid (sheet/build-workflow! ctx (ct/wf))
          new-ex {"request" "ex-9"}
          edited {"request" "ex-8"}
          v2 (into [new-ex] (assoc ct/data 2 edited))
          _ (reset! ct/step 1)
          run-1 (ct/run! ctx sid {:config {:inherit-from-previous false}})
          run-2 (ct/run! ctx sid {:valset v2 :budget 60})
          level (fn [instr] (ct/level (get instr "route")))
          expected-scores (fn [instr valset]
                            (mapv (fn [ex] (if (>= (level instr) (ct/ex-n ex)) 1.0 0.0)) valset))
          created-2 (ct/cands ctx run-2)
          starting (filterv #(nil? (some some? (:parent-ids %))) created-2)
          ev-2 (into {} (map (juxt :candidate-id identity)) (ct/evaluated ctx run-2))
          instr-1 (into {} (map (juxt :candidate-id :instructions)) (ct/cands ctx run-1))
          recorded-1 (into {} (for [e (ct/evaluated ctx run-1)
                                    [i ex] (map-indexed vector ct/data)]
                                [[(instr-1 (:candidate-id e)) ex] (nth (:scores e) i)]))]
      (is (>= (count starting) 2) "the seed and at least one inherited candidate start the run")
      (doseq [c starting :let [e (get ev-2 (:candidate-id c))]]
        (is (= 2 (:metric-calls e)) "one call for the new and one for the edited example")
        (is (= 2 (count (:trace-ids e))) "traces exist for the evaluated examples only")
        (is (= (expected-scores (:instructions c) v2) (:scores e))
            "scores are in the new validation set's order, fresh for the new and edited examples")
        (doseq [[i ex] (map-indexed vector v2)
                :let [before (get recorded-1 [(:instructions c) ex])]
                :when (some? before)]
          (is (= before (nth (:scores e) i))
              "an unchanged example keeps the score recorded for the same content")))
      (let [charged (+ (reduce + (map :metric-calls (ct/evaluated ctx run-2)))
                       (reduce + (map #(or (:metric-calls %) 0)
                                      (events ctx run-2 #{:gepa/subsample-evaluated}))))]
        (is (= charged (:spent (ledger/get-accounting ctx run-2)))
            "the budget accounting equals the calls charged")))))
