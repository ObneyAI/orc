(ns ai.obney.orc.evaluation.node-performance-test
  "S11: performance per node VERSION is built into judging; threshold alerts are
   opt-in. Every test drives the real flow (completion -> request -> outcome ->
   queries/events) through public boundaries."
  (:require [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.judge-runtime :as jr]
            [ai.obney.orc.evaluation.core.commands]
            [ai.obney.orc.evaluation.core.heuristic-structural :as heuristic-structural]
            [ai.obney.orc.evaluation.core.alerts :as alerts]
            [ai.obney.orc.evaluation.core.node-version :as node-version]
            [ai.obney.orc.evaluation.core.performance :as performance]
            [ai.obney.orc.evaluation.interface :as evaluation]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.core.commands]
            [ai.obney.orc.orc-service.core.read-models]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.time.interface :as time]
            [litellm.router :as litellm-router]))

(litellm-router/register! :openrouter
                          {:provider :openrouter
                           :model "test-noop"
                           :config {:api-base "http://localhost:0"
                                    :api-key "test"}})

;; =============================================================================
;; Harness: real in-memory store + pubsub + every registered processor.
;; The evaluation processors are NAMED (checkpointed), as in production.
;; =============================================================================

(defn- create-context
  "A tenant with every registered processor running, except those named in
   `without` (a set of processor names) - for a test that drives one stage itself."
  ([] (create-context #{}))
  ([without]
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/node-performance-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id (random-uuid)
                  :event-pubsub ps
                  :llm-provider :openrouter
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  ::cache-dir cache-dir}
        ;; The same delivery production uses: checkpointed (evaluation/*)
        ;; processors are polled one event at a time, the rest ride pubsub.
        processors (h/start-test-processors base-ctx without)]
    (assoc base-ctx :processors processors))))

(defn- stop-context [ctx]
  (h/stop-test-processors! ctx)
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [e (:event-store ctx)] (es/stop e))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)]
     (try ~@body (finally (stop-context ~sym)))))

(defn- wait-until
  "Poll `f` until truthy or `timeout-ms` elapses; returns the last value."
  [timeout-ms f]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [v (f)]
        (if (or v (>= (System/currentTimeMillis) deadline))
          v
          (do (Thread/sleep 25) (recur)))))))

(defn- command! [ctx command]
  (cp/process-command
   (assoc ctx :command (merge {:command/id (random-uuid) :command/timestamp (time/now)}
                              command))))

(defn- events-of [ctx types]
  (into [] (es/read (:event-store ctx) {:types types :tenant-id (:tenant-id ctx)})))

(defn- requested [ctx] (events-of ctx #{:evaluation/assessment-requested}))
(defn- scored [ctx] (events-of ctx #{:evaluation/assessment-scored}))
(defn- legacy-scores [ctx] (events-of ctx #{:judge/score-emitted}))

(defn- set-living-description-enabled! [ctx enabled?]
  (command! ctx {:command/name :ontology/set-living-description-enabled :enabled? enabled?}))

(defn- create-sheet! [ctx]
  (-> (command! ctx {:command/name :sheet/create-sheet :name (str "s7-" (random-uuid))})
      :command-result/events first :sheet-id))

(defn- create-node! [ctx sheet-id node-type]
  (-> (command! ctx {:command/name :sheet/create-node :sheet-id sheet-id :type node-type})
      :command-result/events first :node-id))

(defn- declare-judge! [ctx sheet-id judge-name judge-config]
  (command! ctx {:command/name :sheet/declare-judge :sheet-id sheet-id
                 :judge-name judge-name :judge-config judge-config}))

(defn- attach-judges! [ctx sheet-id node-id judge-names]
  (command! ctx {:command/name :sheet/set-node-judges :sheet-id sheet-id
                 :node-id node-id :judges (vec judge-names)}))

(def ^:private a-tree [:sequence [:llm {}] [:final {}]])

(defn- complete-node!
  [ctx sheet-id tick-id node-id & {:as extra}]
  (command! ctx (merge {:command/name :sheet/complete-node-execution
                        :sheet-id sheet-id :tick-id tick-id :node-id node-id
                        :node-type :llm :status :success
                        :writes {:generated-tree-raw a-tree}
                        :duration-ms 1}
                       extra)))

(def ^:private judging-stage-off
  "The tests below settle assessments themselves, with chosen outcomes, through
   the public outcome command: only the stage that JUDGES is left out."
  #{:evaluation/on-assessment-requested})

(defn- sheet-with-judge!
  "A sheet with one leaf node carrying the heuristic judge `judge-name`
   (declared with `config`). Returns {:sheet-id :node-id}."
  ([ctx] (sheet-with-judge! ctx "structure" {:type :heuristic-structural}))
  ([ctx judge-name config]
   (let [sheet-id (create-sheet! ctx)
         _ (declare-judge! ctx sheet-id judge-name config)
         node-id (create-node! ctx sheet-id :leaf)]
     (attach-judges! ctx sheet-id node-id [judge-name])
     {:sheet-id sheet-id :node-id node-id})))

(defn- run-node!
  "One more execution of the node; returns the request it produced once durable."
  [ctx sheet-id node-id]
  (let [before (count (requested ctx))]
    (complete-node! ctx sheet-id (random-uuid) node-id)
    (is (wait-until 15000 #(= (inc before) (count (requested ctx))))
        "the completion requests an assessment")
    (last (requested ctx))))

(defn- settle!
  "Record `outcome` (a record-assessment-outcome body) for `request`, handing the
   command what the judging stage hands it: the definition of the judge in force."
  [ctx request outcome]
  (let [judge-config (get (orc/get-judges ctx (:sheet-id request)) (:judge-name request))
        r (command! ctx (merge {:command/name :evaluation/record-assessment-outcome
                                :assessment-id (:assessment-id request)
                                :judge-config judge-config}
                               outcome))]
    (is (nil? (:cognitect.anomalies/category r)) (pr-str r))
    r))

(defn- scored-with [score] {:status :scored :score score :dimensions [] :band 3})

(defmacro ^:private with-judging-off [[sym] & body]
  `(let [~sym (create-context judging-stage-off)]
     (try ~@body (finally (stop-context ~sym)))))

;; =============================================================================
;; Cycle 1 - a node VERSION is the node's effective definition when it ran
;; =============================================================================

(deftest a-request-names-the-node-version-it-assesses
  (testing "same definition -> same node version; an applied instruction change -> a different one"
    (with-judging-off [ctx]
      (let [{:keys [sheet-id node-id]} (sheet-with-judge! ctx)
            r1 (run-node! ctx sheet-id node-id)
            r2 (run-node! ctx sheet-id node-id)
            _ (command! ctx {:command/name :sheet/set-node-instruction
                             :sheet-id sheet-id :node-id node-id
                             :instruction "Answer more carefully."})
            r3 (run-node! ctx sheet-id node-id)]
        (is (some? (:node-version r1)) "the request carries the node version")
        (is (= (:node-version r1) (:node-version r2))
            "two runs of an unchanged node are the same version")
        (is (not= (:node-version r1) (:node-version r3))
            "an instruction change makes a different version")))))

(defn- change-instruction! [ctx sheet-id node-id instruction]
  (command! ctx {:command/name :sheet/set-node-instruction
                 :sheet-id sheet-id :node-id node-id :instruction instruction}))

(defn- version-entry [performance node-version]
  (first (filter #(= node-version (:node-version %)) (:versions performance))))

(defn- judge-stats [entry judge-name]
  (first (filter #(= judge-name (:judge-name %)) (:judges entry))))

(deftest performance-is-reported-per-node-version-with-a-rollup
  (testing "two versions of one node are listed separately, and rolled up across versions"
    (with-judging-off [ctx]
      (let [{:keys [sheet-id node-id]} (sheet-with-judge! ctx)
            r1 (run-node! ctx sheet-id node-id)
            r2 (run-node! ctx sheet-id node-id)
            _ (change-instruction! ctx sheet-id node-id "Answer more carefully.")
            r3 (run-node! ctx sheet-id node-id)
            _ (settle! ctx r1 (scored-with 0.8))
            _ (settle! ctx r2 (scored-with 0.6))
            _ (settle! ctx r3 (scored-with 0.4))
            perf (evaluation/get-node-performance ctx {:sheet-id sheet-id :node-id node-id})
            v1 (version-entry perf (:node-version r1))
            v2 (version-entry perf (:node-version r3))]
        (is (= 2 (count (:versions perf))) "two versions, never blended")
        (is (= 2 (:scored (judge-stats v1 "structure"))))
        (is (< (Math/abs (- 0.7 (:mean-score (judge-stats v1 "structure")))) 1e-9))
        (is (= 1 (:scored (judge-stats v2 "structure"))))
        (is (< (Math/abs (- 0.4 (:mean-score (judge-stats v2 "structure")))) 1e-9))
        (let [rollup (first (filter #(= "structure" (:judge-name %)) (:rollup perf)))]
          (is (= 3 (:scored rollup)) "the rollup spans every version")
          (is (< (Math/abs (- 0.6 (:mean-score rollup))) 1e-9)))
        (testing "narrowed to one version"
          (let [only (evaluation/get-node-performance
                      ctx {:sheet-id sheet-id :node-id node-id :node-version (:node-version r3)})]
            (is (= [(:node-version r3)] (mapv :node-version (:versions only))))))))))

(defn- only-version [perf judge-name]
  (is (= 1 (count (:versions perf))))
  (judge-stats (first (:versions perf)) judge-name))

(defn- close? [expected actual] (and (some? actual) (< (Math/abs (- expected actual)) 1e-9)))

;; =============================================================================
;; Cycle 2 - every kind of outcome counts; coverage includes the pending
;; =============================================================================

(deftest every-outcome-counts-and-coverage-includes-the-pending
  (with-judging-off [ctx]
    (let [{:keys [sheet-id node-id]} (sheet-with-judge! ctx)
          rs (vec (repeatedly 6 #(run-node! ctx sheet-id node-id)))]
      (settle! ctx (rs 0) {:status :scored :score 0.9 :dimensions [] :band 3})
      (settle! ctx (rs 1) {:status :scored :score 0.7 :dimensions [] :band 3})
      (settle! ctx (rs 2) {:status :scored :score 0.2 :dimensions [] :band 1})
      (settle! ctx (rs 3) {:status :failed :reason :judge-execution-failed :message "boom"})
      (settle! ctx (rs 4) {:status :ungradable :reason :no-evidence :message "nothing to grade"})
      ;; (rs 5) stays pending
      (let [s (only-version (evaluation/get-node-performance ctx {:sheet-id sheet-id :node-id node-id})
                            "structure")]
        (is (= [3 1 1 1 6] ((juxt :scored :failed :ungradable :pending :total) s)))
        (is (close? 0.5 (:coverage s)) "3 scored of 6 requested")
        (is (close? 0.6 (:mean-score s)) "the mean is over the scored only")
        (is (= {3 2, 1 1} (:band-distribution s)))))))

;; =============================================================================
;; Cycle 3 - the trailing window is the last N scored
;; =============================================================================

(deftest the-trailing-mean-is-over-the-last-n-scored
  (with-judging-off [ctx]
    (let [{:keys [sheet-id node-id]} (sheet-with-judge! ctx)
          scores [0.1 0.1 0.9 0.9]
          rs (vec (repeatedly 6 #(run-node! ctx sheet-id node-id)))]
      (doseq [[r s] (map vector rs scores)] (settle! ctx r (scored-with s)))
      (settle! ctx (rs 4) {:status :failed :reason :judge-execution-failed :message "boom"})
      (settle! ctx (rs 5) (scored-with 0.9))
      (let [q (fn [window] (:trailing-mean (only-version (evaluation/get-node-performance
                                                          ctx (cond-> {:sheet-id sheet-id :node-id node-id}
                                                                window (assoc :window window)))
                                                         "structure")))]
        (is (close? 0.9 (q 3)) "the failure is not a score: last 3 scored are .9 .9 .9")
        (is (close? 0.7 (q 4)) "last 4 scored are .1 .9 .9 .9")
        (is (close? 0.58 (q nil)) "the default window (20) covers all five scored")))))

;; =============================================================================
;; Cycle 7 - reporting over an explicit set of ids
;; =============================================================================

(deftest an-explicit-id-set-is-reported-with-its-own-coverage-and-bands
  (with-judging-off [ctx]
    (let [{:keys [sheet-id node-id]} (sheet-with-judge! ctx)
          [a b c d] (vec (repeatedly 4 #(run-node! ctx sheet-id node-id)))]
      (settle! ctx a {:status :scored :score 0.8 :dimensions [] :band 2})
      (settle! ctx b {:status :failed :reason :judge-execution-failed :message "boom"})
      (settle! ctx c {:status :scored :score 0.1 :dimensions [] :band 0})
      (let [by-ids (evaluation/get-assessment-report
                    ctx {:assessment-ids [(:assessment-id a) (:assessment-id b)]})
            by-subjects (evaluation/get-assessment-report
                         ctx {:subject-ids (map :subject-completion-id [a b])})
            r (first by-ids)]
        (is (= 1 (count by-ids)) "one judge, one revision")
        (is (= [2 1 1 0 0] ((juxt :total :scored :failed :ungradable :pending) r))
            "exactly the two named assessments, not c or d")
        (is (close? 0.5 (:coverage r)))
        (is (close? 0.8 (:mean-score r)))
        (is (= {2 1} (:band-distribution r)))
        (is (= by-ids by-subjects) "subjects name the same assessments")
        (let [all (first (evaluation/get-assessment-report
                          ctx {:assessment-ids (map :assessment-id [a b c d])}))]
          (is (= [4 2 1 0 1] ((juxt :total :scored :failed :ungradable :pending) all))))))))

;; =============================================================================
;; Cycle 8 - a fresh projection rebuild reproduces performance
;; =============================================================================

(defn- with-fresh-cache
  "`ctx` over the same event store with an EMPTY projection cache: whatever the
   queries return is rebuilt from the events alone."
  [ctx]
  (let [dir (str "/tmp/node-performance-rebuild-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))]
    (rmp/l1-clear!)
    (assoc ctx :cache cache ::rebuild-dir dir)))

(deftest a-fresh-rebuild-reproduces-performance
  (with-judging-off [ctx]
    (let [{:keys [sheet-id node-id]} (sheet-with-judge! ctx)
          rs (vec (repeatedly 4 #(run-node! ctx sheet-id node-id)))
          _ (change-instruction! ctx sheet-id node-id "Be terse.")
          r5 (run-node! ctx sheet-id node-id)
          _ (settle! ctx (rs 0) (scored-with 0.9))
          _ (settle! ctx (rs 1) (scored-with 0.3))
          _ (settle! ctx (rs 2) {:status :failed :reason :judge-execution-failed :message "boom"})
          _ (settle! ctx r5 (scored-with 0.5))
          query {:sheet-id sheet-id :node-id node-id}
          before (evaluation/get-node-performance ctx query)
          rebuilt-ctx (with-fresh-cache ctx)]
      (try
        (let [reads (atom [])
              real-read es/read]
          (with-redefs [es/read (fn [store opts] (swap! reads conj (:types opts)) (real-read store opts))]
            (is (= before (evaluation/get-node-performance rebuilt-ctx query))))
          (is (some #(contains? % :evaluation/assessment-requested) @reads)
              "control: the rebuilt answer really was folded from the lifecycle events"))
        (is (pos? (count (:versions before))))
        (finally
          (kv/stop (:cache rebuilt-ctx))
          (let [f (java.io.File. ^String (::rebuild-dir rebuilt-ctx))]
            (doseq [c (.listFiles f)] (.delete c))
            (.delete f)))))))

;; =============================================================================
;; Cycle 4a - a judge MAY declare an alert; a malformed one is rejected
;; =============================================================================

(def ^:private alert {:below 0.6 :window 5 :min-coverage 0.8})

(deftest a-judge-declares-its-alert-and-a-malformed-one-is-rejected
  (with-judging-off [ctx]
    (let [sheet-id (create-sheet! ctx)
          declare (fn [name alert]
                    (declare-judge! ctx sheet-id name {:type :heuristic-structural :alert alert}))
          rejected? (fn [r] (= :cognitect.anomalies/incorrect (:cognitect.anomalies/category r)))]
      (is (not (:cognitect.anomalies/category (declare "watched" alert)))
          "a well-formed alert is accepted")
      (is (= alert (:alert (get (orc/get-judges ctx sheet-id) "watched")))
          "and kept on the declared judge, so every revision's alert is its own")
      (doseq [[label bad] {"below above 1" (assoc alert :below 1.5)
                           "below zero" (assoc alert :below 0.0)
                           "window zero" (assoc alert :window 0)
                           "window beyond what a cell retains" (assoc alert :window 100000)
                           "coverage above 1" (assoc alert :min-coverage 1.5)
                           "missing window" (dissoc alert :window)
                           "not a map" 0.6}]
        (is (rejected? (declare (str "bad " label) bad)) label)))))

;; =============================================================================
;; Cycle 4b - an alert signals a crossing ONCE, re-arms on recovery
;; =============================================================================

(def ^:private signal-types
  #{:evaluation/performance-threshold-crossed
    :evaluation/performance-threshold-recovered
    :evaluation/performance-coverage-degraded
    :evaluation/performance-coverage-restored})

(defn- signals
  "The performance signals recorded for `judge-name`, oldest first, as [type body]."
  [ctx judge-name]
  (->> (events-of ctx signal-types)
       (filter #(= judge-name (:judge-name %)))
       (mapv (juxt :event/type identity))))

(def ^:private sentinel-alert {:below 1.0 :window 1 :min-coverage 1.0})

(defn- await-sentinel!
  "Every outcome settled before this call has been handled by the alert stage
   (it handles events in order): a sentinel judge with a window of one signals on
   its first outcome, and its signal is the proof."
  [ctx & {:keys [judging-live?]}]
  (let [name (str "sentinel-" (random-uuid))
        {:keys [sheet-id node-id]} (sheet-with-judge! ctx name
                                                      {:type :heuristic-structural
                                                       :alert sentinel-alert})]
    (if judging-live?
      ;; the real heuristic judge finds nothing to grade: ungradable, so a
      ;; window of one has coverage 0 and signals degraded coverage
      (complete-node! ctx sheet-id (random-uuid) node-id :writes {})
      (settle! ctx (run-node! ctx sheet-id node-id) (scored-with 0.5)))
    (is (wait-until 15000 #(seq (signals ctx name))) "the alert stage caught up")))

(deftest an-alert-signals-a-crossing-once-and-re-arms-on-recovery
  (with-judging-off [ctx]
    (let [{:keys [sheet-id node-id]} (sheet-with-judge! ctx "structure"
                                                        {:type :heuristic-structural :alert alert})
          settle-all! (fn [scores]
                        (mapv (fn [s]
                                (let [r (run-node! ctx sheet-id node-id)]
                                  (settle! ctx r (scored-with s))
                                  r))
                              scores))
          first-six (settle-all! [0.75 0.75 0.25 0.25 0.25 0.25])]
      (is (wait-until 15000 #(= 1 (count (signals ctx "structure")))) "one signal")
      (await-sentinel! ctx)
      (is (= [:evaluation/performance-threshold-crossed]
             (map first (signals ctx "structure")))
          "one crossing, not repeated by the further low scores")
      (let [[_ crossed] (first (signals ctx "structure"))]
        (is (= (:assessment-id (nth first-six 4)) (:assessment-id crossed))
            "signalled at the crossing point: the fifth outcome fills the window below 0.6")
        (is (= "structure" (:judge-name crossed)))
        (is (= (:node-version (first first-six)) (:node-version crossed)))
        (is (= 1 (:judge-revision-number crossed)))
        (is (close? 0.45 (:trailing-score crossed)))
        (is (close? 1.0 (:coverage-ratio crossed))))
      (let [highs (settle-all! [0.9 0.9 0.9])]
        (is (wait-until 15000 #(= 2 (count (signals ctx "structure")))))
        (is (= [:evaluation/performance-threshold-crossed :evaluation/performance-threshold-recovered]
               (map first (signals ctx "structure"))))
        (is (= (:assessment-id (last highs))
               (:assessment-id (second (second (signals ctx "structure")))))
            "recovered when the window mean reaches 0.6 again"))
      (settle-all! [0.1 0.1 0.1])
      (is (wait-until 15000 #(= 3 (count (signals ctx "structure"))))
          "a further drop crosses again")
      (await-sentinel! ctx)
      (is (= [:evaluation/performance-threshold-crossed :evaluation/performance-threshold-recovered
              :evaluation/performance-threshold-crossed]
             (map first (signals ctx "structure")))))))

;; =============================================================================
;; Cycle 5 - a window with too little coverage is degraded coverage, not poor
;;           performance
;; =============================================================================

(defn- settle-run! [ctx sheet-id node-id outcome]
  (let [r (run-node! ctx sheet-id node-id)]
    (settle! ctx r outcome)
    r))

(def ^:private failure {:status :failed :reason :judge-execution-failed :message "boom"})

(deftest too-little-coverage-signals-degraded-coverage-not-a-crossing
  (with-judging-off [ctx]
    (let [{:keys [sheet-id node-id]} (sheet-with-judge! ctx "structure"
                                                        {:type :heuristic-structural :alert alert})
          run! #(settle-run! ctx sheet-id node-id %)]
      (doseq [o [failure failure failure (scored-with 0.1) (scored-with 0.1)]] (run! o))
      (is (wait-until 15000 #(= 1 (count (signals ctx "structure")))))
      (let [[type degraded] (first (signals ctx "structure"))]
        (is (= :evaluation/performance-coverage-degraded type)
            "mean 0.1 is below 0.6, but only 2 of 5 were scored: coverage, not performance")
        (is (close? 0.4 (:coverage-ratio degraded)))
        (is (= 2 (:scored degraded))))
      (doseq [o [(scored-with 0.9) (scored-with 0.9) (scored-with 0.9) (scored-with 0.9)]] (run! o))
      (await-sentinel! ctx)
      (is (= [:evaluation/performance-coverage-degraded
              :evaluation/performance-threshold-crossed
              :evaluation/performance-threshold-recovered]
             (map first (signals ctx "structure")))
          (str "degraded once while coverage stayed low (not again on each failure); once coverage "
               "is enough the low mean is a real crossing; then it recovers")))))

(deftest degraded-coverage-that-heals-is-restored-and-re-arms
  (with-judging-off [ctx]
    (let [{:keys [sheet-id node-id]} (sheet-with-judge! ctx "structure"
                                                        {:type :heuristic-structural :alert alert})
          run! #(settle-run! ctx sheet-id node-id %)]
      (doseq [o [failure failure failure (scored-with 0.9) (scored-with 0.9)
                 (scored-with 0.9) (scored-with 0.9)]]
        (run! o))
      (await-sentinel! ctx)
      (is (= [:evaluation/performance-coverage-degraded
              :evaluation/performance-coverage-restored]
             (map first (signals ctx "structure"))))
      (doseq [o [failure failure failure]] (run! o))
      (await-sentinel! ctx)
      (is (= [:evaluation/performance-coverage-degraded
              :evaluation/performance-coverage-restored
              :evaluation/performance-coverage-degraded]
             (map first (signals ctx "structure")))
          "re-armed: a second episode of low coverage is signalled again"))))

;; =============================================================================
;; Cycle 6 - no alert declared: nothing is watched, nothing is read or emitted
;; =============================================================================

(defn- signal-read-of-cell?
  "True when one of the recorded read option maps is the alert stage reading the
   signals of the cell `cell-id`."
  [reads cell-id]
  (boolean (some #(and (= alerts/signal-types (:types %))
                       (= #{[:performance-cell cell-id]} (:tags %)))
                 reads)))

(deftest a-judge-without-an-alert-costs-the-alert-stage-nothing
  (with-judging-off [ctx]
    (let [reads (atom [])
          real-read es/read
          unwatched (sheet-with-judge! ctx "plain" {:type :heuristic-structural})
          watched (sheet-with-judge! ctx "structure" {:type :heuristic-structural :alert alert})]
      (with-redefs [es/read (fn [store opts]
                              (swap! reads conj (assoc (select-keys opts [:types :tags])
                                                       :thread (.getId (Thread/currentThread))))
                              (real-read store opts))]
        (testing "terrible scores from a judge with no alert"
          (let [rs (vec (repeatedly 8 #(settle-run! ctx (:sheet-id unwatched) (:node-id unwatched)
                                                    (scored-with 0.0))))
                plain-cell (performance/cell-id (performance/cell-key (first rs)))]
            (await-sentinel! ctx)
            (is (empty? (signals ctx "plain")) "no alert declared -> no signal, however poor")
            (is (not (signal-read-of-cell? @reads plain-cell))
                "and the alert stage never read the signals of its cell: it had nothing to watch")))
        (testing "the direct handler, given an outcome without an alert"
          (let [outcome (first (events-of ctx #{:evaluation/assessment-scored}))
                _ (reset! reads [])]
            (is (nil? (alerts/on-assessment-outcome (assoc ctx :event (dissoc outcome :alert)))))
            (is (empty? (filter #(= (.getId (Thread/currentThread)) (:thread %)) @reads))
                "no event-store read at all (other stages' threads are not this call)")))
        (testing "a watched judge on the same tenant still signals, and IS read for"
          (let [rs (vec (repeatedly 5 #(settle-run! ctx (:sheet-id watched) (:node-id watched)
                                                    (scored-with 0.0))))]
            (is (wait-until 15000 #(= 1 (count (signals ctx "structure")))))
            (is (signal-read-of-cell? @reads (performance/cell-id (performance/cell-key (first rs))))
                "control: the counter does see an alert stage read")))))))

;; =============================================================================
;; Live flow - the real executor, the real judging stage, no stand-ins for the
;; outcome: a custom judge scores each run from a queue; versions come from the
;; run's own snapshot (published version, instruction override).
;; =============================================================================

(def ^:private queued-scores (atom []))

(defn times-ten [{:keys [inputs]}] {:out (* 10 (:item inputs))})

(defn queued-score
  "Custom judge body: the next score of `queued-scores`."
  [_]
  (let [[s] (swap-vals! queued-scores #(vec (rest %)))]
    {:score (double (first s)) :feedback "queued"}))

(defn- queued-judge-workflow []
  (orc/workflow (str "s11-queued-judge-" (random-uuid))
    (orc/blackboard {:host-inputs [:map-of :keyword [:or :int :uuid]]
                     :host-outputs [:map-of :keyword [:or :int :uuid]]
                     :host-instruction [:string {:description "Host instruction"}]
                     :host-trace [:vector [:map [:node-id {:optional true} :uuid]]]
                     :score :double
                     :feedback [:string {:description "Feedback"}]})
    (orc/code "queued" :fn "ai.obney.orc.evaluation.node-performance-test/queued-score"
      :reads [:host-inputs :host-outputs :host-instruction :host-trace]
      :writes [:score :feedback])))

(defn- judged-workflow [judge-sheet-id]
  (orc/workflow (str "s11-judged-" (random-uuid))
    (orc/blackboard {:item :int :out :int})
    (orc/judges {:quality {:type :custom :sheet-id judge-sheet-id}})
    (orc/code "times-ten" :fn "ai.obney.orc.evaluation.node-performance-test/times-ten"
      :reads [:item] :writes [:out] :judges ["quality"])))

(deftest live-runs-build-performance-per-version
  (h/with-async-test-context [ctx]
    (reset! queued-scores [0.9 0.8 0.3])
    (let [judge-sheet (orc/build-workflow! ctx (queued-judge-workflow))
          sheet-id (orc/build-workflow! ctx (judged-workflow judge-sheet))
          node-id (:id (first (filter #(= "times-ten" (:name %)) (orc/get-nodes-for-sheet ctx sheet-id))))
          run! (fn [c]
                 (let [r (orc/execute c sheet-id {:item 1} :timeout-ms 60000)]
                   (is (= :success (:status r)) (pr-str r))))
          outcomes (fn [n] (wait-until 30000 #(= n (count (events-of ctx #{:evaluation/assessment-scored})))))]
      (run! ctx)
      (is (outcomes 1))
      (run! ctx)
      (is (outcomes 2))
      ;; the run's instruction override (what GEPA applies) is part of what ran
      (run! (assoc ctx :gepa/patched-instructions {"times-ten" "Be exact."}))
      (is (outcomes 3))
      (let [perf (evaluation/get-node-performance ctx {:sheet-id sheet-id :node-id node-id})
            by-count (group-by #(:scored (first (:judges %))) (:versions perf))]
        (is (= 2 (count (:versions perf)))
            "the plain runs and the overridden run are two versions")
        (is (= 1 (count (get by-count 2))) "the two plain runs share one version")
        (is (= 1 (count (get by-count 1))) "the overridden run stands alone")
        (is (close? 0.85 (:mean-score (first (:judges (first (get by-count 2)))))))
        (is (close? 0.3 (:mean-score (first (:judges (first (get by-count 1)))))))
        (is (= 3 (:scored (first (:rollup perf)))))))))

(deftest node-versions-follow-the-definition-and-the-published-sheet-version
  (let [node {:id (random-uuid) :sheet-id (random-uuid) :type :leaf :name "n" :executor :ai
              :instruction "Do it." :reads [:a] :writes [:b] :model nil
              :status :idle :children-ids [] :judges ["quality"]}
        version #(node-version/version-of (node-version/effective-definition % nil) nil)]
    (is (= (version node) (version (assoc node :status :running :last-error "x" :judges []
                                          :id (random-uuid))))
        "bookkeeping and the attached judges are not part of the definition")
    (is (= (version node) (version (assoc node :model nil :retry nil)))
        "an absent field is the same as a field never set")
    (is (not= (version node) (version (assoc node :writes [:b :c]))))
    (is (not= (version node) (version (assoc node :instruction "Do it better."))))
    (is (= (node-version/version-of (node-version/effective-definition node {"n" "Do it better."}) nil)
           (version (assoc node :instruction "Do it better.")))
        "an override applied for a run is the same as the instruction being set")
    (is (not= (node-version/version-of (node-version/effective-definition node nil) 1)
              (node-version/version-of (node-version/effective-definition node nil) 2))
        "the same definition under another published sheet version is another version")
    (is (not= (node-version/version-of (node-version/effective-definition node nil) nil)
              (node-version/version-of (node-version/effective-definition node nil) 1))
        "and a published run is not the draft's version")
    (is (= (version (assoc node :reads [:x] :context {:k #{3 2 1} :j {:z 1 :y 2}}))
           (version (assoc node :reads [:x] :context {:j {:y 2 :z 1} :k #{1 2 3}})))
        "map and set ordering never changes a version")))

(defn- alerted-workflow [judge-sheet-id]
  (orc/workflow (str "s11-alerted-" (random-uuid))
    (orc/blackboard {:item :int :out :int})
    (orc/judges {:quality {:type :custom :sheet-id judge-sheet-id :alert alert}})
    (orc/code "times-ten" :fn "ai.obney.orc.evaluation.node-performance-test/times-ten"
      :reads [:item] :writes [:out] :judges ["quality"])))

(deftest live-judging-of-a-watched-judge-signals-the-crossing-once
  (h/with-async-test-context [ctx]
    (reset! queued-scores [0.75 0.75 0.25 0.25 0.25 0.25])
    (let [judge-sheet (orc/build-workflow! ctx (queued-judge-workflow))
          sheet-id (orc/build-workflow! ctx (alerted-workflow judge-sheet))]
      (dotimes [i 6]
        (let [r (orc/execute ctx sheet-id {:item i} :timeout-ms 60000)]
          (is (= :success (:status r)) (pr-str r))
          ;; one at a time, so each outcome lands in order
          (is (wait-until 30000 #(= (inc i) (count (events-of ctx #{:evaluation/assessment-scored}))))
              (str "outcome " (inc i)))))
      (is (wait-until 15000 #(= 1 (count (signals ctx "quality")))))
      (await-sentinel! ctx :judging-live? true)
      (is (= [:evaluation/performance-threshold-crossed] (map first (signals ctx "quality"))))
      (is (= (:assessment-id (nth (vec (events-of ctx #{:evaluation/assessment-scored})) 4))
             (:assessment-id (second (first (signals ctx "quality")))))
          "at the fifth outcome, as the window filled below the threshold")
      (testing "the recorded events satisfy their registered schemas"
        (is (m/validate :evaluation/performance-threshold-crossed
                        (second (first (signals ctx "quality")))))
        (is (every? #(m/validate :evaluation/assessment-scored %)
                    (events-of ctx #{:evaluation/assessment-scored})))
        (is (every? #(m/validate :evaluation/assessment-requested %)
                    (requested ctx)))
        (is (every? #(some? (:alert %)) (take 6 (events-of ctx #{:evaluation/assessment-scored})))
            "the watched judge's outcomes carry its alert")))))

(deftest the-handler-less-query-schemas-are-gone
  (doseq [k [:evaluation/results-by-node :evaluation/get-scores
             :evaluation/get-low-scoring :evaluation/get-trends]]
    (is (nil? (try (m/schema k) (catch Exception _ nil)))
        (str k " has no handler, so it has no schema"))))
