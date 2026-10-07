(ns ai.obney.orc.evaluation.assessment-lifecycle-test
  "S7: a judgment is a durable ASSESSMENT with its own identity and outcome
   (docs/adr/0008-judge-assessments-are-durable-grain-work.md).

   Every test drives the real flow through public boundaries: a completion
   command -> :sheet/node-execution-completed -> the evaluation processors ->
   :evaluation/assessment-requested -> judging -> :evaluation/record-assessment-
   outcome -> the terminal assessment event (and, for learning judges, the legacy
   :judge/score-emitted). Only the MODEL provider (`llm/predict`) and the
   deterministic heuristic evaluator are ever substituted."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.judge-runtime :as jr]
            [ai.obney.orc.evaluation.core.commands]
            [ai.obney.orc.evaluation.core.heuristic-structural :as heuristic-structural]
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
        cache-dir (str "/tmp/assessment-lifecycle-test-" (random-uuid))
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
  "Emit a real completion for `node-id` in `tick-id` (a heuristic-structural
   judge scores the tree it wrote; no model involved)."
  [ctx sheet-id tick-id node-id & {:as extra}]
  (command! ctx (merge {:command/name :sheet/complete-node-execution
                        :sheet-id sheet-id :tick-id tick-id :node-id node-id
                        :node-type :llm :status :success
                        :writes {:generated-tree-raw a-tree}
                        :duration-ms 1}
                       extra)))

(defn- sheet-with-attached-judge!
  "A sheet with one leaf node carrying `judge-name`. Returns {:sheet-id :node-id}."
  [ctx judge-name judge-config]
  (let [sheet-id (create-sheet! ctx)
        _ (declare-judge! ctx sheet-id judge-name judge-config)
        node-id (create-node! ctx sheet-id :leaf)]
    (attach-judges! ctx sheet-id node-id [judge-name])
    {:sheet-id sheet-id :node-id node-id}))

;; =============================================================================
;; Cycle 1 - attaching a judge is what enables it (AttachmentIsMonitoring);
;;           the defaults stay opt-in (OptInDefaults)
;; =============================================================================

(deftest an-attached-judge-is-requested-with-living-descriptions-off
  (testing "LD flag OFF + an explicitly attached judge -> the completion requests an assessment"
    (with-test-ctx [ctx]
      (is (false? (ontology/get-living-description-enabled? ctx))
          "pre-condition: the Living Description flag is off")
      (let [{:keys [sheet-id node-id]} (sheet-with-attached-judge!
                                        ctx "structure" {:type :heuristic-structural})
            tick-id (random-uuid)]
        (complete-node! ctx sheet-id tick-id node-id)
        (is (wait-until 15000 #(= 1 (count (requested ctx))))
            (str "one assessment is requested; got " (count (requested ctx))))
        (let [r (first (requested ctx))
              completion (first (events-of ctx #{:sheet/node-execution-completed}))]
          (is (= "structure" (:judge-name r)))
          (is (= 1 (:judge-revision-number r)))
          (is (= :heuristic-structural (:judge-type r)))
          (is (= tick-id (:tick-id r)))
          (is (= node-id (:node-id r)))
          (is (= (:event/id completion) (:subject-completion-id r))
              "the subject is the completion's durable event id"))))))

(deftest an-attached-judge-is-scored-with-living-descriptions-off
  (testing "the requested assessment is judged and ends scored"
    (with-test-ctx [ctx]
      (let [{:keys [sheet-id node-id]} (sheet-with-attached-judge!
                                        ctx "structure" {:type :heuristic-structural})]
        (complete-node! ctx sheet-id (random-uuid) node-id)
        (is (wait-until 15000 #(= 1 (count (scored ctx))))
            (str "the assessment is scored; got " (count (scored ctx))))
        (is (= (:assessment-id (first (requested ctx)))
               (:assessment-id (first (scored ctx))))
            "the terminal event belongs to the requested assessment")))))

(defn- stored-completion [ctx tick-id node-id]
  (first (filter #(and (= tick-id (:tick-id %)) (= node-id (:node-id %)))
                 (events-of ctx #{:sheet/node-execution-completed}))))

(deftest the-default-judges-stay-opt-in
  (testing "LD flag OFF + a repl-researcher with NO attachment -> the completion requests nothing"
    (with-test-ctx [ctx]
      (let [sheet-id (create-sheet! ctx)
            node-id (create-node! ctx sheet-id :repl-researcher)
            tick-id (random-uuid)]
        (complete-node! ctx sheet-id tick-id node-id :node-type :repl-researcher)
        (let [completion (stored-completion ctx tick-id node-id)]
          (is (some? completion) "the completion is durable")
          (is (nil? (jr/on-node-execution-completed (assoc ctx :event completion)))
              "the defaults are gated by the Living Description flag: no result at all")
          (is (empty? (requested ctx))))))))

;; =============================================================================
;; Cycle 2 - distinct executions of one node are distinct subjects (J05)
;; =============================================================================

(def ^:private judge-calls
  "What the deterministic custom judge was handed, one entry per invocation."
  (atom []))

(defn times-ten [{:keys [inputs]}] {:item (* 10 (:item inputs))})

(defn record-judge
  "Custom judge body: records the evidence it was handed and scores 0.75."
  [{:keys [inputs]}]
  (swap! judge-calls conj inputs)
  {:score 0.75 :feedback "recorded"})

(def ^:private any-map
  ;; the judge is handed the node's values plus its execution-context markers
  ;; (map-each index :int, map-each parent :uuid)
  [:map-of :keyword [:or :int :uuid]])

(defn- recording-judge-workflow []
  (orc/workflow (str "s7-recording-judge-" (random-uuid))
    (orc/blackboard {:host-inputs any-map
                     :host-outputs any-map
                     :host-instruction [:string {:description "Host instruction"}]
                     :host-trace [:vector [:map [:node-id {:optional true} :uuid]]]
                     :score :double
                     :feedback [:string {:description "Feedback"}]})
    (orc/code "record" :fn "ai.obney.orc.evaluation.assessment-lifecycle-test/record-judge"
      :reads [:host-inputs :host-outputs :host-instruction :host-trace]
      :writes [:score :feedback])))

(defn- map-each-with-judge-workflow [judge-sheet-id]
  (orc/workflow (str "s7-map-each-" (random-uuid))
    (orc/blackboard {:items [:vector :int] :item :int :results [:vector :int]})
    (orc/judges {:quality {:type :custom :sheet-id judge-sheet-id}})
    (orc/map-each "each" :from :items :as :item :into :results :parallel 1
      (orc/code "times-ten" :fn "ai.obney.orc.evaluation.assessment-lifecycle-test/times-ten"
        :reads [:item] :writes [:item] :judges ["quality"]))))

(deftest three-map-each-executions-are-three-assessments
  (testing "a judge on a map-each leaf assesses each iteration: 3 requested, 3 scored, 3 learning records"
    (h/with-async-test-context [ctx]
      (reset! judge-calls [])
      (let [judge-sheet (orc/build-workflow! ctx (recording-judge-workflow))
            sheet-id (orc/build-workflow! ctx (map-each-with-judge-workflow judge-sheet))
            result (orc/execute ctx sheet-id {:items [1 2 3]} :timeout-ms 60000)]
        (is (= :success (:status result)) (pr-str result))
        (is (wait-until 30000 #(= 3 (count (scored ctx))))
            (str "three assessments scored; requested " (count (requested ctx))
                 " scored " (count (scored ctx))))
        (let [reqs (requested ctx)]
          (is (= 3 (count reqs)))
          (is (= 3 (count (set (map :subject-completion-id reqs))))
              "each iteration is its own subject completion")
          (is (= 3 (count (set (map :assessment-id reqs))))
              "and so its own assessment")
          (is (every? :exec-context reqs) "each request names its iteration"))
        (is (= #{[1 10] [2 20] [3 30]}
               (set (map (fn [i] [(get-in i [:host-inputs :item]) (get-in i [:host-outputs :item])])
                         @judge-calls)))
            (str "each judgment saw ITS iteration's evidence: " (pr-str @judge-calls)))
        (is (wait-until 15000 #(= 3 (count (legacy-scores ctx))))
            "three legacy learning records, one per assessment")
        (is (= (set (map :assessment-id (scored ctx)))
               (set (keep :assessment-id (legacy-scores ctx)))))))))

;; =============================================================================
;; Cycle 3 - replay requests nothing new and judges nothing twice (J06)
;; =============================================================================

(defn- sheet-with-recording-judge!
  "A leaf node carrying a deterministic custom judge that records each
   invocation in `judge-calls`. Returns {:sheet-id :node-id}."
  [ctx]
  (let [judge-sheet (orc/build-workflow! ctx (recording-judge-workflow))]
    (sheet-with-attached-judge! ctx "quality" {:type :custom :sheet-id judge-sheet})))

(defn- complete-and-await-score!
  [ctx sheet-id node-id]
  (let [tick-id (random-uuid)]
    (complete-node! ctx sheet-id tick-id node-id :writes {:item 42})
    (is (wait-until 30000 #(= 1 (count (scored ctx)))) "the first delivery is judged")
    (stored-completion ctx tick-id node-id)))

(deftest redelivering-a-completion-requests-nothing-and-judges-nothing
  (testing "the same completion handed to the handler again: no new request, the judge ran once"
    (with-test-ctx [ctx]
      (reset! judge-calls [])
      (let [{:keys [sheet-id node-id]} (sheet-with-recording-judge! ctx)
            completion (complete-and-await-score! ctx sheet-id node-id)]
        (is (= 1 (count (requested ctx))))
        (is (= 1 (count @judge-calls)))
        (is (nil? (jr/on-node-execution-completed (assoc ctx :event completion)))
            "everything it would request is already requested")
        (is (= 1 (count (requested ctx))) "still one request")
        (is (= 1 (count (scored ctx))) "still one outcome")
        (is (= 1 (count @judge-calls)) "the judge was invoked exactly once")))))

(defn- restart-processors!
  "Stop every processor of `ctx` and start them again over the SAME event store
   (named, as in production): the new processors catch up from their checkpoints."
  [ctx]
  (h/stop-test-processors! ctx)
  (assoc ctx :processors (h/start-test-processors (dissoc ctx :processors))))

(deftest a-processor-restart-over-the-same-store-judges-nothing-twice
  (testing "processors stopped and started again over the same event store: catch-up redelivers the completion, nothing is repeated"
    ;; A deterministic structural judge keeps the store free of other
    ;; completions: a later completion handled with nothing to request would
    ;; checkpoint the processor past this one and hide the redelivery.
    (let [ctx (atom (create-context))
          evaluations (atom 0)
          real heuristic-structural/evaluate-tree-structure]
      (try
        (with-redefs [heuristic-structural/evaluate-tree-structure
                      (fn [tree] (swap! evaluations inc) (real tree))]
          (let [{:keys [sheet-id node-id]} (sheet-with-attached-judge!
                                            @ctx "structure" {:type :heuristic-structural})]
            (complete-node! @ctx sheet-id (random-uuid) node-id)
            (is (wait-until 30000 #(= 1 (count (scored @ctx)))) "the first delivery is judged")
            (swap! ctx restart-processors!)
            ;; A completion AFTER the restart proves the restarted processors are
            ;; live and past their catch-up (they handle events in order).
            (let [probe (sheet-with-attached-judge! @ctx "probe" {:type :heuristic-structural})]
              (complete-node! @ctx (:sheet-id probe) (random-uuid) (:node-id probe))
              (is (wait-until 30000 #(= 2 (count (scored @ctx)))) "the post-restart completion is judged")
              (is (= 2 (count (requested @ctx))) "the catch-up requested nothing for the old completion")
              (is (= 2 @evaluations) "and nothing was judged twice"))))
        (finally (stop-context @ctx))))))

(defn- create-processorless-context []
  (let [ctx (create-context)]
    (h/stop-test-processors! ctx)
    (assoc ctx :processors {})))

(deftest concurrent-deliveries-of-one-completion-request-once
  (testing "two deliveries that both read before either appends: the handler's CAS lets exactly one request through"
    (let [ctx (create-processorless-context)]
      (try
        (let [{:keys [sheet-id node-id]} (sheet-with-attached-judge! ctx "structure" {:type :heuristic-structural})
              tick-id (random-uuid)
              _ (complete-node! ctx sheet-id tick-id node-id)
              completion (stored-completion ctx tick-id node-id)
              ready (java.util.concurrent.CountDownLatch. 2)
              release (promise)
              results (doall (repeatedly 2 #(let [r (jr/on-node-execution-completed (assoc ctx :event completion))]
                                              (is (some? r) "both read before either appended")
                                              r)))]
          (let [appends (mapv (fn [r] (future (.countDown ready) (deref release 5000 nil)
                                        (es/append (:event-store ctx)
                                                   {:tenant-id (:tenant-id ctx)
                                                    :events (:result/events r)
                                                    :cas (:result/cas r)})))
                              results)]
            (is (.await ready 5 java.util.concurrent.TimeUnit/SECONDS))
            (deliver release true)
            (run! deref appends))
          (is (= 1 (count (requested ctx))) "one request, however the deliveries interleave"))
        (finally (stop-context ctx))))))

;; =============================================================================
;; Cycle 4 - failed and ungradable are outcomes: recorded with their reason,
;;           never a score, visible in the assessments read model
;; =============================================================================

(defn forgets-to-score [_] {:feedback "I forgot to score."})

(defn- forgetful-judge-workflow []
  (orc/workflow (str "s7-forgetful-judge-" (random-uuid))
    (orc/blackboard {:host-inputs any-map
                     :host-outputs any-map
                     :host-instruction [:string {:description "Host instruction"}]
                     :host-trace [:vector [:map [:node-id {:optional true} :uuid]]]
                     :score :double
                     :feedback [:string {:description "Feedback"}]})
    (orc/code "forget" :fn "ai.obney.orc.evaluation.assessment-lifecycle-test/forgets-to-score"
      :reads [:host-inputs :host-outputs :host-instruction :host-trace]
      :writes [:feedback])))

(defn- assessments-of [ctx node-id]
  (evaluation/get-assessments ctx {:node-id node-id}))

(deftest a-judge-that-writes-no-score-leaves-a-failed-assessment
  (testing "the judge's workflow succeeded but wrote no score: the assessment FAILED with its reason, and no learning record exists"
    (with-test-ctx [ctx]
      (let [judge-sheet (orc/build-workflow! ctx (forgetful-judge-workflow))
            {:keys [sheet-id node-id]} (sheet-with-attached-judge!
                                        ctx "quality" {:type :custom :sheet-id judge-sheet})]
        (complete-node! ctx sheet-id (random-uuid) node-id :writes {:item 1})
        (is (wait-until 30000 #(= [:failed] (mapv :status (assessments-of ctx node-id))))
            (pr-str (assessments-of ctx node-id)))
        (let [a (first (assessments-of ctx node-id))]
          (is (= :invalid-result (:reason a)))
          (is (string? (:message a)))
          (is (= "quality" (:judge-name a)))
          (is (= 1 (:judge-revision-number a)))
          (is (not (contains? a :score)) "nothing is invented"))
        (is (empty? (legacy-scores ctx)) "a failure never reaches the learning loops")))))

(deftest a-node-with-no-tree-is-ungradable-by-the-structural-judge
  (testing "the structural judge has nothing to grade: UNGRADABLE with its reason, not a score"
    (with-test-ctx [ctx]
      (let [{:keys [sheet-id node-id]} (sheet-with-attached-judge!
                                        ctx "structure" {:type :heuristic-structural})]
        (complete-node! ctx sheet-id (random-uuid) node-id :writes {})
        (is (wait-until 30000 #(= [:ungradable] (mapv :status (assessments-of ctx node-id))))
            (pr-str (assessments-of ctx node-id)))
        (let [a (first (assessments-of ctx node-id))]
          (is (= :no-tree-emitted (:reason a)))
          (is (not (contains? a :score))))
        (is (empty? (legacy-scores ctx)))))))

;; =============================================================================
;; Cycle 5 - a score-only (monitoring) judge: a scored assessment with a band, no
;;           feedback invented, and nothing for the learning loops (J10)
;; =============================================================================

(defn writes-band-two [_] {:band 2})

(def ^:private three-bands
  {1 "Wrong: contradicts the evidence"
   2 "Partly: some support"
   3 "Right: fully supported"})

(def ^:private score-only-rubric
  {:criterion "Is the answer supported by the evidence?" :bands three-bands :feedback :none})

(def ^:private rubric-key-schema
  [:map [:bands [:map-of :int :string]]
   [:criterion {:optional true} :string]
   [:feedback {:optional true} :keyword]])

(defn- banding-judge-workflow []
  (orc/workflow (str "s7-banding-judge-" (random-uuid))
    (orc/blackboard {:host-inputs any-map
                     :host-outputs any-map
                     :host-instruction [:string {:description "Host instruction"}]
                     :host-trace [:vector [:map [:node-id {:optional true} :uuid]]]
                     :rubric rubric-key-schema
                     :band [:int {:description "The rubric band chosen"}]})
    (orc/code "band" :fn "ai.obney.orc.evaluation.assessment-lifecycle-test/writes-band-two"
      :reads [:host-inputs :host-outputs :host-instruction :host-trace :rubric]
      :writes [:band])))

(deftest a-score-only-judge-scores-without-feedback-and-feeds-no-learning-loop
  (testing "rubric feedback :none -> monitoring only: a scored assessment with its band, no feedback key, no legacy record"
    (with-test-ctx [ctx]
      (let [judge-sheet (orc/build-workflow! ctx (banding-judge-workflow))
            {:keys [sheet-id node-id]} (sheet-with-attached-judge!
                                        ctx "monitor" {:type :custom :sheet-id judge-sheet
                                                       :rubric score-only-rubric})]
        (complete-node! ctx sheet-id (random-uuid) node-id :writes {:item 1})
        (is (wait-until 30000 #(= [:scored] (mapv :status (assessments-of ctx node-id))))
            (pr-str (assessments-of ctx node-id)))
        (let [a (first (assessments-of ctx node-id))
              terminal (first (scored ctx))]
          (is (= 2 (:band a)))
          (is (= 0.5 (:score a)) "(2-1)/(3-1)")
          (is (= #{:monitoring} (:purposes a)) "a score-only rubric can only monitor")
          (is (not (contains? a :feedback)) "no feedback is invented")
          (is (not (contains? terminal :feedback)) "not on the durable event either"))
        (is (empty? (legacy-scores ctx))
            "a monitoring-only judge never emits the record the learning loops read")))))

;; =============================================================================
;; Cycle 6 - a second outcome for one assessment records nothing, even when two
;;           are delivered at the same instant
;; =============================================================================

(defn- request-assessment!
  "Deliver a fresh completion of `node-id` to the (processor-less) handler and
   append what it returns. Returns the requested assessment's id."
  [ctx sheet-id node-id & {:as completion-extra}]
  (let [tick-id (random-uuid)
        _ (complete-node! ctx sheet-id tick-id node-id completion-extra)
        result (jr/on-node-execution-completed
                (assoc ctx :event (stored-completion ctx tick-id node-id)))]
    (es/append (:event-store ctx) {:tenant-id (:tenant-id ctx)
                                   :events (:result/events result)
                                   :cas (:result/cas result)})
    (some-> result :result/events first :assessment-id)))

(defn- outcome-command [assessment-id]
  {:command/name :evaluation/record-assessment-outcome
   :assessment-id assessment-id :status :scored :score 0.5
   :feedback "Enough." :dimensions []})

(defn- events-for [ctx types assessment-id]
  (filterv #(= assessment-id (:assessment-id %)) (events-of ctx types)))

(deftest concurrent-duplicate-outcomes-record-one-terminal-event
  (testing "two outcome commands for one assessment, released together: one terminal event and one learning record"
    (let [ctx (create-processorless-context)]
      (try
        (let [{:keys [sheet-id node-id]} (sheet-with-attached-judge!
                                          ctx "structure" {:type :heuristic-structural})
              trials 20
              results
              (doall
               (for [_ (range trials)]
                 (let [id (request-assessment! ctx sheet-id node-id)
                       ready (java.util.concurrent.CountDownLatch. 2)
                       release (promise)
                       contenders (doall (repeatedly
                                          2 (fn [] (future (.countDown ready)
                                                           (deref release 5000 nil)
                                                           (command! ctx (outcome-command id))))))]
                   (is (.await ready 5 java.util.concurrent.TimeUnit/SECONDS))
                   (deliver release true)
                   (run! #(deref % 10000 ::timeout) contenders)
                   {:id id
                    :terminals (events-for ctx #{:evaluation/assessment-scored} id)
                    :legacy (events-for ctx #{:judge/score-emitted} id)})))
              bad (filterv #(not= [1 1] [(count (:terminals %)) (count (:legacy %))]) results)]
          (is (every? :id results) "every trial requested an assessment")
          (is (empty? bad)
              (str (count bad) " of " trials " assessments did not end with exactly one terminal "
                   "event and one learning record: "
                   (pr-str (mapv (fn [r] [(count (:terminals r)) (count (:legacy r))]) bad)))))
        (finally (stop-context ctx))))))

(deftest an-outcome-for-an-unrequested-assessment-is-rejected
  (testing "nothing is recorded for an assessment that was never requested"
    (let [ctx (create-processorless-context)]
      (try
        (let [r (command! ctx (outcome-command (random-uuid)))]
          (is (= :cognitect.anomalies/not-found (:cognitect.anomalies/category r)) (pr-str r))
          (is (empty? (scored ctx))))
        (finally (stop-context ctx))))))

;; =============================================================================
;; Cycle 7 - tenants are isolated
;; =============================================================================

(deftest two-tenants-have-isolated-assessments
  (testing "the same store, two tenants: each tenant's assessments are its own"
    (with-test-ctx [ctx-a]
      (let [ctx-b (assoc ctx-a :tenant-id (random-uuid))
            a (sheet-with-attached-judge! ctx-a "structure" {:type :heuristic-structural})
            b (sheet-with-attached-judge! ctx-b "structure" {:type :heuristic-structural})]
        (complete-node! ctx-a (:sheet-id a) (random-uuid) (:node-id a))
        (complete-node! ctx-b (:sheet-id b) (random-uuid) (:node-id b))
        (complete-node! ctx-b (:sheet-id b) (random-uuid) (:node-id b))
        (is (wait-until 30000 #(and (= 1 (count (scored ctx-a))) (= 2 (count (scored ctx-b)))))
            (str "scored a=" (count (scored ctx-a)) " b=" (count (scored ctx-b))))
        (is (= #{(:node-id a)} (set (map :node-id (evaluation/get-assessments ctx-a {})))))
        (is (= 1 (count (evaluation/get-assessments ctx-a {}))))
        (is (= #{(:node-id b)} (set (map :node-id (evaluation/get-assessments ctx-b {})))))
        (is (= 2 (count (evaluation/get-assessments ctx-b {}))))
        (is (every? #{:scored} (map :status (concat (evaluation/get-assessments ctx-a {})
                                                    (evaluation/get-assessments ctx-b {})))))))))

;; =============================================================================
;; Cycle 8 - the read model is a pure projection of the events
;; =============================================================================

(deftest a-fresh-projection-rebuild-reproduces-the-assessments
  (testing "a new cache over the same event store projects the same assessments, pending and settled alike"
    (with-test-ctx [ctx]
      (let [judge-sheet (orc/build-workflow! ctx (forgetful-judge-workflow))
            scoring (sheet-with-attached-judge! ctx "structure" {:type :heuristic-structural})
            failing (sheet-with-attached-judge! ctx "quality" {:type :custom :sheet-id judge-sheet})]
        (complete-node! ctx (:sheet-id scoring) (random-uuid) (:node-id scoring))
        (complete-node! ctx (:sheet-id failing) (random-uuid) (:node-id failing) :writes {:item 1})
        (is (wait-until 30000 #(= #{:scored :failed} (set (map :status (evaluation/get-assessments ctx {})))))
            (pr-str (evaluation/get-assessments ctx {})))
        (let [before (evaluation/get-assessments ctx {})
              fresh-dir (str "/tmp/assessment-lifecycle-fresh-" (random-uuid))
              fresh-cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir fresh-dir :db-name "fresh"}))]
          (try
            (rmp/l1-clear!)
            (let [after (evaluation/get-assessments (assoc ctx :cache fresh-cache) {})]
              (is (= 2 (count after)))
              (is (= before after) "the rebuilt projection equals the live one"))
            (finally
              (kv/stop fresh-cache)
              (doseq [f (.listFiles (java.io.File. fresh-dir))] (.delete f))
              (.delete (java.io.File. fresh-dir)))))))))

;; =============================================================================
;; Cycle 9 - a judge's revision is part of the assessment's identity, and a
;;           request is judged by the definition of ITS revision
;; =============================================================================

(defn- revise-judge! [ctx sheet-id judge-name judge-config]
  (command! ctx {:command/name :sheet/revise-judge :sheet-id sheet-id
                 :judge-name judge-name :judge-config judge-config}))

(deftest a-revised-judge-assesses-under-its-new-revision
  (testing "revise a judge between two runs: the second assessment carries revision 2 and a different id"
    (with-test-ctx [ctx]
      (let [{:keys [sheet-id node-id]} (sheet-with-attached-judge!
                                        ctx "structure" {:type :heuristic-structural})
            tick-1 (random-uuid)
            tick-2 (random-uuid)]
        (complete-node! ctx sheet-id tick-1 node-id)
        (is (wait-until 30000 #(= 1 (count (scored ctx)))))
        (let [revised (revise-judge! ctx sheet-id "structure"
                                     {:type :heuristic-structural :criteria "revision two"})]
          (is (nil? (:cognitect.anomalies/category revised)) (pr-str revised)))
        (complete-node! ctx sheet-id tick-2 node-id)
        (is (wait-until 30000 #(= 2 (count (scored ctx))))
            (pr-str (map (juxt :tick-id :judge-revision-number) (requested ctx))))
        (let [by-tick (into {} (map (juxt :tick-id identity)) (evaluation/get-assessments ctx {}))
              first-a (get by-tick tick-1)
              second-a (get by-tick tick-2)]
          (is (= 1 (:judge-revision-number first-a)))
          (is (= 2 (:judge-revision-number second-a)))
          (is (not= (:assessment-id first-a) (:assessment-id second-a)))
          (is (every? #{:scored} (map :status [first-a second-a])))))
      )))

(deftest a-completion-delivered-again-after-a-revision-requests-nothing
  (testing "a handler-CAS outcome is appended without a checkpoint, so Grain delivers the completion again; a revision in between must not add a second request for it"
    (with-test-ctx [ctx]
      (let [{:keys [sheet-id node-id]} (sheet-with-attached-judge!
                                        ctx "structure" {:type :heuristic-structural})
            tick (random-uuid)]
        (complete-node! ctx sheet-id tick node-id)
        (is (wait-until 30000 #(= 1 (count (scored ctx)))))
        (let [completion (first (filter #(= tick (:tick-id %))
                                        (events-of ctx #{:sheet/node-execution-completed})))]
          (is (nil? (:cognitect.anomalies/category
                     (revise-judge! ctx sheet-id "structure"
                                    {:type :heuristic-structural :criteria "revision two"}))))
          (is (nil? (jr/on-node-execution-completed (assoc ctx :event completion)))
              "the completion's request set was decided when it was first processed")
          (is (= [1] (mapv :judge-revision-number
                           (filter #(= tick (:tick-id %)) (requested ctx))))
              "one request, under the revision in force when it was first processed"))))))

(deftest a-request-is-judged-by-the-definition-of-its-revision
  (testing "a pending revision-1 request, judged after the judge was revised, runs revision 1's workflow"
    ;; Every processor runs (the judge's own workflow executes on them) except the
    ;; two evaluation stages, which the test drives itself.
    (let [ctx (create-context #{:evaluation/on-node-execution-completed
                                :evaluation/on-assessment-requested})]
      (try
        (reset! judge-calls [])
        (let [revision-1 (orc/build-workflow! ctx (recording-judge-workflow))
              revision-2 (orc/build-workflow! ctx (forgetful-judge-workflow))
              {:keys [sheet-id node-id]} (sheet-with-attached-judge!
                                          ctx "quality" {:type :custom :sheet-id revision-1})
              id (request-assessment! ctx sheet-id node-id :writes {:item 1})
              request (first (requested ctx))]
          (command! ctx {:command/name :sheet/revise-judge :sheet-id sheet-id :judge-name "quality"
                         :judge-config {:type :custom :sheet-id revision-2}})
          (is (= 2 (:revision-number (orc/get-judge ctx sheet-id "quality")))
              "pre-condition: the judge is now at revision 2")
          (let [result (jr/on-assessment-requested (assoc ctx :event request))]
            (is (fn? (:result/effect result)))
            (deref ((:result/effect result)) 30000 ::timeout))
          (let [a (first (evaluation/get-assessments ctx {:node-id node-id}))]
            (is (= id (:assessment-id a)))
            (is (= :scored (:status a))
                (str "judged by revision 1 (which scores); revision 2 would fail: " (pr-str a)))
            (is (= 1 (count @judge-calls)))))
        (finally (stop-context ctx))))))

;; =============================================================================
;; Cycle 10 - a burst: every completion is a durable request; none disappears
;; =============================================================================

(deftest a-burst-of-completions-requests-every-assessment-and-loses-none
  (testing "12 completions in a burst, a slow judge: 12 requests; every assessment is scored or visibly pending"
    (with-test-ctx [ctx]
      (let [real heuristic-structural/evaluate-tree-structure
            n 12]
        (with-redefs [heuristic-structural/evaluate-tree-structure
                      (fn [tree] (Thread/sleep 300) (real tree))]
          (let [{:keys [sheet-id node-id]} (sheet-with-attached-judge!
                                            ctx "structure" {:type :heuristic-structural})
                ticks (vec (repeatedly n random-uuid))]
            (doseq [tick ticks] (complete-node! ctx sheet-id tick node-id))
            (is (wait-until 30000 #(= n (count (requested ctx))))
                (str "every completion requested its assessment; got " (count (requested ctx))))
            (wait-until 30000 #(= n (count (scored ctx))))
            (let [assessments (evaluation/get-assessments ctx {:node-id node-id})
                  by-status (frequencies (map :status assessments))]
              (println "S7-BURST terminal within the wait:" (get by-status :scored 0) "of" n
                       "- pending:" (get by-status :pending 0) "- all:" by-status)
              (is (= n (count assessments)) "no assessment is missing")
              (is (= (set ticks) (set (map :tick-id assessments))))
              (is (every? #{:scored :pending} (map :status assessments))
                  "each is scored, or visibly pending - never silently gone"))))))))

;; =============================================================================
;; Cycle 11 - the judge-scores history keeps what the legacy record carries (J08)
;; =============================================================================

(deftest judge-scores-carry-the-model-provenance
  (testing "a learning judge's score, read back through get-judge-scores, shows the model call that produced it; the legacy event carries its assessment, band and revision"
    (let [ctx (create-processorless-context)]
      (try
        (let [{:keys [sheet-id node-id]} (sheet-with-attached-judge!
                                          ctx "structure" {:type :heuristic-structural})
              id (request-assessment! ctx sheet-id node-id)
              tick-id (:tick-id (first (requested ctx)))
              provenance {:provider :openrouter :model "judge-model-7" :usage {:total-tokens 11}}
              recorded (command! ctx (assoc (outcome-command id)
                                            :band 3
                                            :model-provenance [(assoc provenance :requested-model "judge-model")]))]
          (is (nil? (:cognitect.anomalies/category recorded)) (pr-str recorded))
          (let [entry (first (evaluation/get-judge-scores ctx sheet-id node-id tick-id))]
            (is (= provenance (:model-provenance entry))
                "the legacy record keeps the provider, model and usage the judge's call recorded"))
          (let [legacy (first (legacy-scores ctx))]
            (is (= id (:assessment-id legacy)))
            (is (= 3 (:band legacy)))
            (is (= 1 (:revision-number legacy)))))
        (finally (stop-context ctx))))))

;; =============================================================================
;; Cycles 3d / 5b - guards of the two remaining rules of the lifecycle
;; =============================================================================

(deftest a-settled-request-delivered-again-is-not-judged-again
  (testing "the judging processor, handed a request that already has its outcome, starts no judging"
    (with-test-ctx [ctx]
      (reset! judge-calls [])
      (let [{:keys [sheet-id node-id]} (sheet-with-recording-judge! ctx)]
        (complete-and-await-score! ctx sheet-id node-id)
        (let [request (first (requested ctx))]
          (is (nil? (jr/on-assessment-requested (assoc ctx :event request)))
              "an assessment that is already scored has nothing to judge")
          (is (= 1 (count @judge-calls)) "the judge ran once"))))))

(deftest a-monitoring-only-judge-with-feedback-feeds-no-learning-loop
  (testing "purposes #{:monitoring}: the scored assessment keeps its feedback, but no legacy record is written"
    (with-test-ctx [ctx]
      (reset! judge-calls [])
      (let [judge-sheet (orc/build-workflow! ctx (recording-judge-workflow))
            {:keys [sheet-id node-id]} (sheet-with-attached-judge!
                                        ctx "monitor" {:type :custom :sheet-id judge-sheet
                                                       :purposes #{:monitoring}})]
        (complete-node! ctx sheet-id (random-uuid) node-id :writes {:item 1})
        (is (wait-until 30000 #(= [:scored] (mapv :status (assessments-of ctx node-id))))
            (pr-str (assessments-of ctx node-id)))
        (let [a (first (assessments-of ctx node-id))]
          (is (= "recorded" (:feedback a)) "the assessment carries the feedback the judge wrote")
          (is (= #{:monitoring} (:purposes a))))
        (is (empty? (legacy-scores ctx))
            "only a LEARNING judge's scored outcome becomes the learning loops' record")))))
