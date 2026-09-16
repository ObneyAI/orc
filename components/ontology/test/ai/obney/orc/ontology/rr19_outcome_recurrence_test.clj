(ns ai.obney.orc.ontology.rr19-outcome-recurrence-test
  "RR-19: classification attributes a campaign; only a durable verdict
   occurrence advances recurrence and its ordered learning window."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.todo-processors :as ontology-todos]
            [ai.obney.orc.ontology.core.harvest :as harvest]
            [ai.obney.orc.ontology.core.consolidator :as consolidator]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.commands]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.schemas]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.time.interface :as time])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

(defn- create-context
  ([] (create-context #{}))
  ([processor-names]
   (let [event-pubsub (pubsub/start {:type :core-async :topic-fn :event/type})
         base {:event-pubsub event-pubsub
               :event-store (es/start {:conn {:type :in-memory}
                                      :event-pubsub event-pubsub
                                      :logger nil})
               :tenant-id (random-uuid)
               :command-registry (cp/global-command-registry)}
         processors
         (reduce-kv
          (fn [acc processor-name {:keys [handler-fn topics]}]
            (if (contains? processor-names processor-name)
              (assoc acc processor-name
                     (tp/start {:event-pubsub event-pubsub
                                :topics topics
                                :handler-fn handler-fn
                                :context base}))
              acc))
          {} @tp/processor-registry*)]
     (assoc base :processors processors))))

(defn- stop-context [ctx]
  (doseq [[_ processor] (:processors ctx)] (tp/stop processor))
  (es/stop (:event-store ctx))
  (pubsub/stop (:event-pubsub ctx)))

(defmacro with-test-context [[binding] & body]
  `(let [~binding (create-context)]
     (try
       ~@body
       (finally (stop-context ~binding)))))

(defmacro with-terminal-processor-context [[binding] & body]
  `(let [~binding (create-context
                   #{:ontology/on-researcher-campaign-terminal})]
     (try
       ~@body
       (finally (stop-context ~binding)))))

(defn- occurrence-events [ctx tick-id]
  (into []
        (es/read (:event-store ctx)
                 {:tenant-id (:tenant-id ctx)
                  :types #{:ontology/tree-class-occurrence-recorded}
                  :tags #{[:tick tick-id]}})))

(defn- append-source-event! [ctx type body]
  ;; RR-23: also tag [:node node-id] when the body carries one, matching
  ;; production's real emit-site tag set for every event type this helper
  ;; fabricates (:sheet/node-execution-completed, :rlm/researcher-iteration-
  ;; recorded, ... all tag [:sheet ...] [:node ...] [:tick ...] when a node
  ;; is involved) — the consolidator's per-occurrence iteration-record read
  ;; is now scoped by [:tick ...] [:node ...] together, and a fixture tagged
  ;; only [:tick ...] is invisible to that scoped read, same as it would be
  ;; to any other real [:node ...]-scoped consumer.
  (es/append (:event-store ctx)
             {:tenant-id (:tenant-id ctx)
              :events [(es/->event {:type type
                                    :tags (cond-> #{[:tick (:tick-id body)]}
                                            (:node-id body) (conj [:node (:node-id body)]))
                                    :body body})]}))

(defn- assign-class! [ctx sheet-id tick-id node-id class-id]
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/assign-task-class
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :source-sheet-id sheet-id
           :source-tick-id tick-id
           :source-node-id node-id
           :assigned-tree-id class-id
           :confidence 0.9
           :top-candidates []
           :reasoning "RR-19 occurrence fixture"
           :was-fresh-mint? false})))

(defn- append-terminal-completion! [ctx sheet-id tick-id node-id verdict]
  (append-source-event! ctx :sheet/node-execution-completed
                        {:sheet-id sheet-id
                         :tick-id tick-id
                         :node-id node-id
                         :node-type :repl-researcher
                         :status verdict
                         :completion-kind :terminal})
  (:event/id
   (last
    (into []
          (es/read (:event-store ctx)
                   {:tenant-id (:tenant-id ctx)
                    :types #{:sheet/node-execution-completed}
                    :tags #{[:tick tick-id]}})))))

(defn- record-occurrence! [ctx sheet-id tick-id node-id class-id verdict]
  (assign-class! ctx sheet-id tick-id node-id class-id)
  (let [completion-id
        (append-terminal-completion! ctx sheet-id tick-id node-id verdict)]
    (cp/process-command
     (assoc ctx :command
            {:command/name :ontology/record-tree-class-occurrence
             :command/id (random-uuid)
             :command/timestamp (time/now)
             :source-sheet-id sheet-id
             :source-tick-id tick-id
             :source-node-id node-id
             :source-completion-event-id completion-id
             :assigned-tree-id class-id
             :verdict verdict}))))

(deftest occurrence-command-requires-durable-classification-and-terminal-verdict
  (testing "a caller cannot invent recurrence without the campaign facts it claims to summarize"
    (with-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            class-id (random-uuid)
            result
            (cp/process-command
             (assoc ctx :command
                    {:command/name :ontology/record-tree-class-occurrence
                     :command/id (random-uuid)
                     :command/timestamp (time/now)
                     :source-sheet-id sheet-id
                     :source-tick-id tick-id
                     :source-node-id node-id
                     :assigned-tree-id class-id
                     :verdict :success}))]
        (is (= :cognitect.anomalies/incorrect
               (:cognitect.anomalies/category result))
            (pr-str result))
        (is (empty? (occurrence-events ctx tick-id))
            "no classification and no terminal completion means no occurrence")))))

(deftest occurrence-command-validates-the-fact-it-summarizes
  (testing "class, node, verdict, and completion identity must match durable source facts"
    (with-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            class-id (random-uuid)
            completion-id (do
                            (assign-class! ctx sheet-id tick-id node-id class-id)
                            (append-terminal-completion!
                             ctx sheet-id tick-id node-id :success))
            base {:command/name :ontology/record-tree-class-occurrence
                  :command/timestamp (time/now)
                  :source-sheet-id sheet-id
                  :source-tick-id tick-id
                  :source-node-id node-id
                  :source-completion-event-id completion-id}
            dispatch (fn [body]
                       (cp/process-command
                        (assoc ctx :command
                               (assoc body :command/id (random-uuid)))))]
        (dispatch (assoc base :assigned-tree-id (random-uuid)
                              :verdict :success))
        (dispatch (assoc base :assigned-tree-id class-id
                              :verdict :failure))
        (is (empty? (occurrence-events ctx tick-id))
            "mismatched attribution or verdict cannot become recurrence")
        (let [result (dispatch (assoc base :assigned-tree-id class-id
                                          :verdict :success))]
          (is (nil? (:cognitect.anomalies/category result)) (pr-str result))
          (is (= [[class-id :success completion-id]]
                 (mapv (juxt :assigned-tree-id :verdict
                             :source-completion-event-id)
                       (occurrence-events ctx tick-id)))))))))

(defn- await-occurrence-count [ctx tick-id expected]
  (loop [remaining 100]
    (let [actual (count (occurrence-events ctx tick-id))]
      (if (or (= expected actual) (zero? remaining))
        actual
        (do (Thread/sleep 10)
            (recur (dec remaining)))))))

(defn- classified-event [sheet-id tick-id node-id class-id]
  {:event/type :ontology/task-classified
   :source-sheet-id sheet-id
   :source-tick-id tick-id
   :source-node-id node-id
   :assigned-tree-id class-id})

(defn- occurrence-event [sheet-id tick-id node-id class-id verdict]
  {:event/type :ontology/tree-class-occurrence-recorded
   :source-sheet-id sheet-id
   :source-tick-id tick-id
   :source-node-id node-id
   :assigned-tree-id class-id
   :verdict verdict})

(deftest classification-attributes-without-advancing-recurrence
  (testing "classification remains attribution while a success verdict advances recurrence once"
    (let [sheet-id (random-uuid)
          tick-id (random-uuid)
          node-id (random-uuid)
          class-id (random-uuid)
          classified (classified-event sheet-id tick-id node-id class-id)
          occurrence (occurrence-event sheet-id tick-id node-id class-id :success)
          counters-after-classification
          (rm/consolidation-delta-counters {} [classified])
          judge-state-after-classification
          (rm/tree-class-judge-averages* {} classified)
          counters-after-verdict
          (rm/consolidation-delta-counters counters-after-classification [occurrence])
          judge-state-after-verdict
          (rm/tree-class-judge-averages* judge-state-after-classification occurrence)]
      (is (= class-id
             (get-in judge-state-after-classification
                     [:occurrence->class [sheet-id tick-id]]))
          "classification still owns immutable score attribution")
      (is (nil? (get-in counters-after-classification [:tree-class class-id]))
          "classification intent does not advance recurrence")
      (is (empty? (get-in judge-state-after-classification
                          [:class->recent-occurrences class-id] []))
          "classification intent does not enter the ordered verdict window")
      (is (= {:delta 1 :total 1}
             (get-in counters-after-verdict [:tree-class class-id]))
          "one success verdict advances recurrence exactly once")
      (is (= [[sheet-id tick-id]]
             (get-in judge-state-after-verdict
                     [:class->recent-occurrences class-id]))
          "the verdict occurrence enters the ordered learning window"))))

(deftest harvest-gate-reports-verdict-occurrences-not-classifications
  (testing "the visible recurrence figure is derived from outcomes, not attributed intents"
    (let [class-id (random-uuid)
          campaigns (repeatedly 3
                                #(hash-map :sheet-id (random-uuid)
                                           :tick-id (random-uuid)
                                           :node-id (random-uuid)))
          classifications
          (mapv (fn [{:keys [sheet-id tick-id node-id]}]
                  (classified-event sheet-id tick-id node-id class-id))
                campaigns)
          verdicts
          (mapv (fn [{:keys [sheet-id tick-id node-id]}]
                  (occurrence-event sheet-id tick-id node-id class-id :success))
                (take 2 campaigns))
          counters (rm/consolidation-delta-counters
                    {}
                    (concat classifications verdicts))
          occurrences (get-in counters [:tree-class class-id :total])
          report (harvest/harvest-gate-report
                  {:occurrences occurrences
                   :judge-trailing-averages {}
                   :occurrence-scores []
                   :distinct-tree-shapes 0}
                  harvest/default-harvest-config)]
      (is (= 2 occurrences)
          "three classifications with two verdicts count as two recurrences")
      (is (= 2 (get-in report [:recurring :occurrences]))
          "the auditable gate report exposes the verdict-qualified count"))))

(deftest verdict-occurrence-command-and-event-have-closed-schemas
  (let [body {:source-sheet-id (random-uuid)
              :source-tick-id (random-uuid)
              :source-node-id (random-uuid)
              :source-completion-event-id (random-uuid)
              :assigned-tree-id (random-uuid)
              :verdict :failure}
        command-schema (get @schema-util/registry*
                            :ontology/record-tree-class-occurrence)
        event-schema (get @schema-util/registry*
                          :ontology/tree-class-occurrence-recorded)]
    (is (some? command-schema))
    (is (some? event-schema))
    (is (m/validate command-schema body))
    (is (m/validate event-schema (assoc body :recorded-at "2026-09-10T00:00:00Z")))
    (is (not (m/validate command-schema (assoc body :verdict :cancelled)))
        "infrastructure endings cannot be expressed as verdict occurrences")))

(deftest occurrence-command-is-first-writer-wins-per-campaign
  (with-test-context [ctx]
    (let [sheet-id (random-uuid)
          tick-id (random-uuid)
          other-tick-id (random-uuid)
          node-id (random-uuid)
          first-class-id (random-uuid)
          conflicting-class-id (random-uuid)
          first-result (record-occurrence! ctx sheet-id tick-id node-id
                                           first-class-id :failure)
          replay-result (record-occurrence! ctx sheet-id tick-id node-id
                                            conflicting-class-id :success)
          other-result (record-occurrence! ctx sheet-id other-tick-id node-id
                                           conflicting-class-id :timeout)
          events (occurrence-events ctx tick-id)]
      (is (nil? (:cognitect.anomalies/category first-result)))
      (is (nil? (:cognitect.anomalies/category replay-result)))
      (is (empty? (:command-result/events replay-result))
          "a visible replay is a successful no-op")
      (is (= 1 (count events)))
      (is (= [first-class-id :failure]
             ((juxt :assigned-tree-id :verdict) (first events)))
          "a conflicting replay cannot move class or verdict")
      (is (nil? (:cognitect.anomalies/category other-result)))
      (is (= [conflicting-class-id :timeout]
             ((juxt :assigned-tree-id :verdict)
              (first (occurrence-events ctx other-tick-id))))
          "another tick remains an independent campaign occurrence"))))

(deftest concurrent-occurrence-contenders-retain-one-first-fact
  (with-test-context [ctx]
    (let [sheet-id (random-uuid)
          tick-id (random-uuid)
          node-id (random-uuid)
          class-id (random-uuid)
          _ (assign-class! ctx sheet-id tick-id node-id class-id)
          ;; The realistic race: two at-least-once deliveries of the ONE
          ;; terminal completion (the campaign has exactly one verdict), not
          ;; two different verdicts for one campaign.
          completion-id (append-terminal-completion!
                         ctx sheet-id tick-id node-id :success)
          contenders [[completion-id :success]
                      [completion-id :success]]
          both-pre-reads (CountDownLatch. 2)
          original-read es/read
          command (fn [[completion-id verdict]]
                    {:command/name :ontology/record-tree-class-occurrence
                     :command/id (random-uuid)
                     :command/timestamp (time/now)
                     :source-sheet-id sheet-id
                     :source-tick-id tick-id
                     :source-node-id node-id
                     :source-completion-event-id completion-id
                     :assigned-tree-id class-id
                     :verdict verdict})
          results
          (with-redefs [es/read
                        (fn [event-store query]
                          (let [result (original-read event-store query)]
                            (if (and (contains? (:types query)
                                                :ontology/tree-class-occurrence-recorded)
                                     (= #{[:tick tick-id]} (:tags query)))
                              (let [snapshot (into [] result)]
                                (.countDown both-pre-reads)
                                (when-not (.await both-pre-reads 5 TimeUnit/SECONDS)
                                  (throw (ex-info "occurrence contenders did not overlap" {})))
                                snapshot)
                              result)))]
            (->> contenders
                 (mapv (fn [contender]
                         (future
                           (cp/process-command
                            (assoc ctx :command (command contender))))))
                 (mapv #(deref % 3000 ::timeout))))
          events (into []
                       (original-read (:event-store ctx)
                                      {:tenant-id (:tenant-id ctx)
                                       :types #{:ontology/tree-class-occurrence-recorded}
                                       :tags #{[:tick tick-id]}}))]
      (is (not-any? #{::timeout} results))
      (is (= 1 (count (remove :cognitect.anomalies/category results))))
      (is (= 1 (count (filter :cognitect.anomalies/category results)))
          "one append-time CAS contender loses")
      (is (= 1 (count events)))
      (is (= class-id (:assigned-tree-id (first events))))
      (is (contains? #{:success :failure} (:verdict (first events)))
          "the retained verdict names one complete terminal fact"))))

(deftest researcher-terminal-processor-records-only-behavior-verdicts
  (with-terminal-processor-context [ctx]
    (doseq [[status expected-count]
            [[:success 1] [:failure 1] [:timeout 1] [:blocked 0]]]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            class-id (random-uuid)]
        (cp/process-command
         (assoc ctx :command
                {:command/name :ontology/assign-task-class
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :source-sheet-id sheet-id
                 :source-tick-id tick-id
                 :source-node-id node-id
                 :assigned-tree-id class-id
                 :confidence 0.9
                 :top-candidates []
                 :reasoning "RR-19 producer proof"
                 :was-fresh-mint? false}))
        (let [completion {:sheet-id sheet-id
                          :tick-id tick-id
                          :node-id node-id
                          :node-type :repl-researcher
                          :status status
                          :completion-kind :terminal}]
          (append-source-event! ctx :sheet/node-execution-completed completion)
          (when (= :success status)
            (append-source-event! ctx :sheet/node-execution-completed completion)))
        (is (= expected-count
               (await-occurrence-count ctx tick-id expected-count))
            (str status " occurrence count"))
        (when (pos? expected-count)
          (is (= [class-id status]
                 ((juxt :assigned-tree-id :verdict)
                  (first (occurrence-events ctx tick-id))))))))

    (doseq [ending [:sheet/tick-cancelled :sheet/tree-tick-completed]]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            class-id (random-uuid)]
        (cp/process-command
         (assoc ctx :command
                {:command/name :ontology/assign-task-class
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :source-sheet-id sheet-id
                 :source-tick-id tick-id
                 :source-node-id node-id
                 :assigned-tree-id class-id
                 :confidence 0.9
                 :top-candidates []
                 :reasoning "RR-19 infrastructure ending proof"
                 :was-fresh-mint? false}))
        (append-source-event!
         ctx ending
         (cond-> {:sheet-id sheet-id :tick-id tick-id}
           (= ending :sheet/tick-cancelled) (assoc :reason "operator stop")
           (= ending :sheet/tree-tick-completed) (assoc :root-status :failure)))
        (Thread/sleep 50)
        (is (empty? (occurrence-events ctx tick-id))
            (str ending " does not invent a behavior verdict"))))))

(deftest infrastructure-terminal-visible-before-late-completion-records-no-occurrence
  (testing "a late completion delivery cannot convert a cancelled or abandoned campaign into recurrence"
    (with-terminal-processor-context [ctx]
      (doseq [ending [:sheet/tick-cancelled :sheet/tree-tick-completed]]
        (let [sheet-id (random-uuid)
              tick-id (random-uuid)
              node-id (random-uuid)
              class-id (random-uuid)]
          (cp/process-command
           (assoc ctx :command
                  {:command/name :ontology/assign-task-class
                   :command/id (random-uuid)
                   :command/timestamp (time/now)
                   :source-sheet-id sheet-id
                   :source-tick-id tick-id
                   :source-node-id node-id
                   :assigned-tree-id class-id
                   :confidence 0.9
                   :top-candidates []
                   :reasoning "RR-19 infrastructure ordering proof"
                   :was-fresh-mint? false}))
          (append-source-event! ctx ending
                                (cond-> {:sheet-id sheet-id :tick-id tick-id}
                                  (= ending :sheet/tick-cancelled)
                                  (assoc :reason "operator stop")
                                  (= ending :sheet/tree-tick-completed)
                                  (assoc :root-status :failure)))
          (append-source-event! ctx :sheet/node-execution-completed
                                {:sheet-id sheet-id
                                 :tick-id tick-id
                                 :node-id node-id
                                 :node-type :repl-researcher
                                 :status :success
                                 :completion-kind :terminal})
          (Thread/sleep 100)
          (is (empty? (occurrence-events ctx tick-id))
              (str ending " is a durable exclusion even if completion is delivered later")))))))

(deftest completion-before-parent-terminal-remains-a-verdict
  (testing "processor delay cannot erase a completion that preceded its parent terminal"
    (with-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            class-id (random-uuid)]
        (cp/process-command
         (assoc ctx :command
                {:command/name :ontology/assign-task-class
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :source-sheet-id sheet-id
                 :source-tick-id tick-id
                 :source-node-id node-id
                 :assigned-tree-id class-id
                 :confidence 0.9
                 :top-candidates []
                 :reasoning "RR-19 completion ordering proof"
                 :was-fresh-mint? false}))
        (append-source-event! ctx :sheet/node-execution-completed
                              {:sheet-id sheet-id
                               :tick-id tick-id
                               :node-id node-id
                               :node-type :repl-researcher
                               :status :success
                               :completion-kind :terminal})
        (let [completion (first (into []
                                      (es/read (:event-store ctx)
                                               {:tenant-id (:tenant-id ctx)
                                                :types #{:sheet/node-execution-completed}
                                                :tags #{[:tick tick-id]}})))]
          (append-source-event! ctx :sheet/tree-tick-completed
                                {:sheet-id sheet-id
                                 :tick-id tick-id
                                 :root-status :success})
          (ontology-todos/ontology-on-researcher-campaign-terminal
           (assoc ctx :event completion)))
        (is (= [[class-id :success]]
               (mapv (juxt :assigned-tree-id :verdict)
                     (occurrence-events ctx tick-id)))
            "event order, not processor scheduling order, decides admission")))))

(deftest first-terminal-completion-is-the-campaigns-only-verdict
  (testing "a campaign's status has no outbound transition once terminal, so a later stray completion is not a verdict"
    (with-terminal-processor-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            class-id (random-uuid)]
        (assign-class! ctx sheet-id tick-id node-id class-id)
        (append-terminal-completion! ctx sheet-id tick-id node-id :blocked)
        (let [late-success-id
              (append-terminal-completion! ctx sheet-id tick-id node-id :success)]
          (Thread/sleep 150)
          (is (empty? (occurrence-events ctx tick-id))
              "processor: a success delivered after the campaign blocked is not a verdict")
          (let [result (cp/process-command
                        (assoc ctx :command
                               {:command/name :ontology/record-tree-class-occurrence
                                :command/id (random-uuid)
                                :command/timestamp (time/now)
                                :source-sheet-id sheet-id
                                :source-tick-id tick-id
                                :source-node-id node-id
                                :source-completion-event-id late-success-id
                                :assigned-tree-id class-id
                                :verdict :success}))]
            (is (nil? (:cognitect.anomalies/category result)) (pr-str result))
            (is (empty? (occurrence-events ctx tick-id))
                "command: naming the later completion cannot manufacture a verdict")))))
    (with-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            class-id (random-uuid)]
        (assign-class! ctx sheet-id tick-id node-id class-id)
        (let [first-id (append-terminal-completion! ctx sheet-id tick-id node-id :success)
              stray-id (append-terminal-completion! ctx sheet-id tick-id node-id :failure)]
          (cp/process-command
           (assoc ctx :command
                  {:command/name :ontology/record-tree-class-occurrence
                   :command/id (random-uuid)
                   :command/timestamp (time/now)
                   :source-sheet-id sheet-id
                   :source-tick-id tick-id
                   :source-node-id node-id
                   :source-completion-event-id stray-id
                   :assigned-tree-id class-id
                   :verdict :failure}))
          (is (empty? (occurrence-events ctx tick-id))
              "the stray second completion is not the campaign's verdict even when it is named first")
          (cp/process-command
           (assoc ctx :command
                  {:command/name :ontology/record-tree-class-occurrence
                   :command/id (random-uuid)
                   :command/timestamp (time/now)
                   :source-sheet-id sheet-id
                   :source-tick-id tick-id
                   :source-node-id node-id
                   :source-completion-event-id first-id
                   :assigned-tree-id class-id
                   :verdict :success}))
          (is (= [[class-id :success first-id]]
                 (mapv (juxt :assigned-tree-id :verdict :source-completion-event-id)
                       (occurrence-events ctx tick-id)))
              "only the first terminal completion is the verdict"))))))

(deftest failed-verdict-enters-recurrence-and-low-quality-window
  (with-test-context [ctx]
    (let [sheet-id (random-uuid)
          tick-id (random-uuid)
          node-id (random-uuid)
          class-id (random-uuid)]
      (cp/process-command
       (assoc ctx :command
              {:command/name :ontology/assign-task-class
               :command/id (random-uuid)
               :command/timestamp (time/now)
               :source-sheet-id sheet-id
               :source-tick-id tick-id
               :source-node-id node-id
               :assigned-tree-id class-id
               :confidence 0.9
               :top-candidates []
               :reasoning "RR-19 quality proof"
               :was-fresh-mint? false}))
      (cp/process-command
       (assoc ctx :command
              {:command/name :evaluation/record-judge-score
               :command/id (random-uuid)
               :command/timestamp (time/now)
               :sheet-id sheet-id
               :tick-id tick-id
               :node-id node-id
               :judge-name "quality"
               :judge-config {}
               :score 0.0
               :feedback "campaign failed"
               :dimensions []}))
      (is (empty? (harvest/occurrence-scores ctx class-id))
          "classification plus a score is descriptive evidence, not recurrence")
      (record-occurrence! ctx sheet-id tick-id node-id class-id :failure)
      (is (= [0.0] (harvest/occurrence-scores ctx class-id))
          "a failed verdict counts and carries its low judge evidence")
      (let [events (concat
                    (into [] (es/read (:event-store ctx)
                                      {:tenant-id (:tenant-id ctx)
                                       :types #{:ontology/task-classified}}))
                    (occurrence-events ctx tick-id))]
        (is (= 1 (get-in (rm/consolidation-delta-counters {} events)
                         [:tree-class class-id :total]))
            "failure advances recurrence exactly once")))))

(deftest infrastructure-ended-iteration-evidence-remains-in-later-reflection
  (with-test-context [ctx]
    (let [class-id (random-uuid)
          facts
          (mapv (fn [ending]
                  {:ending ending
                   :sheet-id (random-uuid)
                   :tick-id (random-uuid)
                   :node-id (random-uuid)})
                [:cancelled :abandoned :success])]
      (doseq [[index {:keys [ending sheet-id tick-id node-id]}]
              (map-indexed vector facts)]
        (cp/process-command
         (assoc ctx :command
                {:command/name :ontology/assign-task-class
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :source-sheet-id sheet-id
                 :source-tick-id tick-id
                 :source-node-id node-id
                 :assigned-tree-id class-id
                 :confidence 0.9
                 :top-candidates []
                 :reasoning "RR-19 retained evidence proof"
                 :was-fresh-mint? false}))
        (append-source-event!
         ctx :rlm/researcher-iteration-recorded
         {:sheet-id sheet-id
          :tick-id tick-id
          :node-id node-id
          :iteration-index 0
          :attempt-ordinal 0
          :iteration-record {:iteration-index 0
                             :attempt-ordinal 0
                             :status :success
                             :code (str "(def evidence-" index " true)")}
          :recorded-at "2026-09-10T00:00:00Z"})
        (case ending
          :cancelled
          (append-source-event! ctx :sheet/tick-cancelled
                                {:sheet-id sheet-id :tick-id tick-id
                                 :reason "operator stop"})
          :abandoned
          (append-source-event! ctx :sheet/tree-tick-completed
                                {:sheet-id sheet-id :tick-id tick-id
                                 :root-status :failure})
          :success
          (record-occurrence! ctx sheet-id tick-id node-id class-id :success)))
      (let [observations (#'consolidator/gather-recent-tree-class-events
                          ctx class-id)]
        (is (= 3 (count observations)))
        (is (= #{"(def evidence-0 true)"
                 "(def evidence-1 true)"
                 "(def evidence-2 true)"}
               (into #{}
                     (mapcat (fn [observation]
                               (map :code (:researcher-iterations observation))))
                     observations))
            "cancelled and abandoned completed iteration evidence survives for later reflection")
        (is (= 1
               (count (filter #(= :ontology/tree-class-occurrence-recorded
                                  (:event/type %))
                              (into []
                                    (es/read (:event-store ctx)
                                             {:tenant-id (:tenant-id ctx)})))))
            "only the later legitimate verdict advances recurrence")))))
