(ns ai.obney.orc.orc-service.durable-iteration-stream-test
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.streaming :as streaming]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.event-store-sqlite-v3.interface]))

(use-fixtures :each
  (fn [f]
    (streaming/reset-all!)
    (try
      (f)
      (finally
        (streaming/reset-all!)))))

(defn- drain!
  [events-ch timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [acc []]
      (let [remaining (- deadline (System/currentTimeMillis))]
        (if-not (pos? remaining)
          acc
          (let [[value port]
                (async/alts!! [events-ch (async/timeout remaining)])]
            (if (or (nil? value) (not= port events-ch))
              acc
              (recur (conj acc value)))))))))

(defn- take-until
  [events-ch pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [acc []]
      (let [remaining (- deadline (System/currentTimeMillis))]
        (if-not (pos? remaining)
          [nil acc]
          (let [[value port]
                (async/alts!! [events-ch (async/timeout remaining)])]
            (cond
              (or (nil? value) (not= port events-ch)) [nil acc]
              (pred value) [value (conj acc value)]
              :else (recur (conj acc value)))))))))

(deftest det-e2e-278-live-iterations-equal-durable-records
  (testing "authoritative live iteration envelopes are the durable records"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [calls (atom 0)
            definition
            (sheet/workflow "rr16-durable-iteration-stream"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "record two bounded iterations"
                :writes [:summary]
                :max-iterations 2
                :rlm {:timeouts {:provider-ms 1000
                                 :iteration-ms 3000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            tick-id (random-uuid)
            {:keys [events-ch] :as subscription}
            (sheet/subscribe-execution ctx tick-id)]
        (is (nil? (:cognitect.anomalies/category subscription))
            (pr-str subscription))
        (with-redefs [llm/predict
                      (fn [& _]
                        (case (swap! calls inc)
                          1 {:outputs {:code "(store! :memo \"durable\")"}
                             :reasoning "first bounded iteration"
                             :usage {:prompt_tokens 2
                                     :completion_tokens 1
                                     :total_tokens 3}}
                          2 {:outputs {:code "(final! {:summary (get-var :memo)})"}
                             :reasoning "finish from durable state"
                             :usage {:prompt_tokens 2
                                     :completion_tokens 1
                                     :total_tokens 3}}
                          (throw (ex-info "RR16 fixture exceeded two turns" {}))))]
          (let [result (sheet/execute ctx sheet-id {}
                                      :tick-id tick-id
                                      :timeout-ms 15000)
                envelopes (drain! events-ch 5000)
                iteration-envelopes
                (filterv #(= :rlm-iteration-recorded
                             (:orc.stream/type %))
                         envelopes)
                watched
                (->> iteration-envelopes
                     (mapv #(select-keys % [:sheet-id :tick-id :node-id
                                            :iteration-index :attempt-ordinal
                                            :iteration-record])))
                durable
                (->> (h/read-tick-events ctx tick-id)
                     (filter #(= :rlm/researcher-iteration-recorded
                                 (:event/type %)))
                     (mapv #(select-keys % [:sheet-id :tick-id :node-id
                                            :iteration-index :attempt-ordinal
                                            :iteration-record])))]
            (is (= :success (:status result)) (pr-str result))
            (is (= 2 @calls))
            (is (= 2 (count durable)) (pr-str durable))
            (is (every? #(m/validate sheet/stream-envelope-schema %)
                        iteration-envelopes)
                (pr-str iteration-envelopes))
            (is (= durable watched)
                (pr-str {:durable durable
                         :watched watched
                         :stream-types (mapv :orc.stream/type envelopes)}))))))))

(deftest det-e2e-278-live-iteration-tail-survives-sqlite-reopen
  (testing "fresh streaming state tails the same campaign after a real restart"
    (let [db-file (str "/tmp/rr16-stream-reopen-" (random-uuid) ".db")
          event-store-conn {:type :sqlite
                            :database-file db-file
                            :maximum-pool-size 2}
          owned? (atom true)
          second-turn-entered (promise)
          first-worker-finished (promise)
          first-context (atom nil)
          reopened-context (atom nil)
          execute-future (atom nil)
          calls (atom 0)
          tick-id (random-uuid)]
      (try
        (with-redefs
          [llm/predict
           (fn [& _]
             (case (swap! calls inc)
               1 {:outputs {:code "(store! :memo \"survived-reopen\")"}
                  :reasoning "commit before restart"
                  :usage {:prompt_tokens 2 :completion_tokens 1 :total_tokens 3}}
               2 (do
                   (deliver second-turn-entered true)
                   (Thread/sleep 10000)
                   {:outputs {:code "(final! {:summary \"stale\"})"}})
               3 {:outputs {:code "(final! {:summary (get-var :memo)})"}
                  :reasoning "finish after restart"
                  :usage {:prompt_tokens 2 :completion_tokens 1 :total_tokens 3}}
               (throw (ex-info "RR16 restart fixture exceeded three turns" {}))))]
          (let [ctx
                (h/create-async-test-context
                 {:context {:llm-provider :test
                            :lease-owned? (fn [] @owned?)
                            :researcher-lease-monitor-wait-fn #(Thread/sleep 10)
                            :researcher-worker-finished-fn
                            #(deliver first-worker-finished true)}
                  :event-store-conn event-store-conn})
                _ (reset! first-context ctx)
                definition
                (sheet/workflow "rr16-stream-sqlite-reopen"
                  (sheet/blackboard {:summary :string})
                  (sheet/repl-researcher "researcher"
                    :instruction "stream across restart"
                    :writes [:summary]
                    :max-iterations 3
                    :rlm {:timeouts {:provider-ms 20000
                                     :iteration-ms 25000
                                     :campaign-ms 60000}}))
                sheet-id (sheet/build-workflow! ctx definition)
                researcher-id
                (:id (first (filter #(= :repl-researcher (:type %))
                                    (sheet/get-nodes-for-sheet ctx sheet-id))))
                first-sub (sheet/subscribe-execution ctx tick-id)
                _ (reset! execute-future
                          (future (sheet/execute ctx sheet-id {}
                                                 :tick-id tick-id
                                                 :timeout-ms 60000)))
                [first-envelope _]
                (take-until (:events-ch first-sub)
                            #(and (= :rlm-iteration-recorded
                                     (:orc.stream/type %))
                                  (= 0 (:iteration-index %)))
                            10000)]
            (is (some? first-envelope)
                "the first runtime streams iteration zero from its durable append")
            (is (= true (deref second-turn-entered 10000 ::not-entered)))
            (reset! owned? false)
            (is (= true (deref first-worker-finished 10000 ::not-finished)))
            (future-cancel @execute-future)
            ((:close! first-sub))
            (h/stop-async-context ctx)
            (reset! first-context nil)
            (streaming/reset-all!)

            (let [reopened
                  (h/create-async-test-context
                   {:context {:llm-provider :test
                              :lease-owned? (constantly true)}
                    :event-store-conn event-store-conn})
                  _ (reset! reopened-context reopened)
                  reopened-sub (sheet/subscribe-execution reopened tick-id)
                  scan (sheet/resume-in-progress! reopened)
                  [second-envelope observed-after-reopen]
                  (take-until (:events-ch reopened-sub)
                              #(and (= :rlm-iteration-recorded
                                       (:orc.stream/type %))
                                    (= 1 (:iteration-index %)))
                              15000)]
              (is (= 1 (count (filter :resumed? scan))) (pr-str scan))
              (is (some? second-envelope)
                  (pr-str (mapv :orc.stream/type observed-after-reopen)))
              (is (h/settle-until!
                   #(some? (runtime/durable-terminal-result reopened tick-id))
                   :timeout-ms 15000))
              (let [durable
                    (->> (es/read (:event-store reopened)
                                  {:tenant-id (:tenant-id reopened)
                                   :types #{:rlm/researcher-iteration-recorded}
                                   :tags #{[:tick tick-id]}})
                         (into [])
                         (sort-by (juxt :iteration-index :attempt-ordinal))
                         (mapv #(select-keys % [:sheet-id :tick-id :node-id
                                                :iteration-index :attempt-ordinal
                                                :iteration-record])))
                    watched
                    (mapv #(select-keys % [:sheet-id :tick-id :node-id
                                           :iteration-index :attempt-ordinal
                                           :iteration-record])
                          [first-envelope second-envelope])
                    projected (rm/get-researcher-iteration-records
                               reopened sheet-id tick-id researcher-id)]
                (is (= [0 1] (mapv :iteration-index projected))
                    (pr-str projected))
                (is (= durable watched)
                    (pr-str {:durable durable :watched watched}))
                (is (= 3 @calls))))))
        (finally
          (when-let [f @execute-future]
            (future-cancel f))
          (when-let [ctx @reopened-context]
            (h/stop-async-context ctx))
          (when-let [ctx @first-context]
            (h/stop-async-context ctx))
          (io/delete-file db-file true))))))

(deftest det-e2e-278-rejoined-child-restores-live-lineage
  (testing "replaying a completed generated child relinks it to a fresh root subscription"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [provider-calls (atom 0)
            source-tree
            '[:sequence
              [:code {:writes [:summary]
                      :output-schemas {:summary :string}
                      :fn (fn [_] {:summary "rejoined-child"})}]
              [:final {:keys [:summary]}]]
            code (str "(emit-tree! (quote " (pr-str source-tree) "))")
            definition
            (sheet/workflow "rr16-rejoined-child-lineage"
              (sheet/blackboard {:summary :string})
              (sheet/repl-researcher "researcher"
                :instruction "reuse one completed generated child"
                :writes [:summary]
                :max-iterations 1
                :model "deterministic-model"
                :rlm {:checkpointed? true
                      :recursive? false
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 5000
                                 :campaign-ms 15000}}))
            sheet-id (sheet/build-workflow! ctx definition)]
        (with-redefs [llm/predict
                      (fn [& _]
                        (swap! provider-calls inc)
                        {:outputs {:code code}
                         :usage {:prompt_tokens 2
                                 :completion_tokens 1
                                 :total_tokens 3}})]
          (let [first-result (sheet/execute ctx sheet-id {} :timeout-ms 15000)
                tick-id (:trace-id first-result)
                node (first (filter #(= "researcher" (:name %))
                                    (sheet/get-nodes-for-sheet ctx sheet-id)))
                node-id (:id node)
                _ (is (h/settle-until!
                       #(= #{:provider :generated-child}
                           (->> (rm/get-researcher-effect-claims
                                 ctx sheet-id tick-id node-id)
                                (filter (fn [claim]
                                          (= :completed (:status claim))))
                                (map :kind)
                                set))
                       :timeout-ms 5000)
                      "both durable completions must be projected before replay")
                claims (rm/get-researcher-effect-claims
                        ctx sheet-id tick-id node-id)
                completed-child
                (first (filter #(and (= :generated-child (:kind %))
                                     (= :completed (:status %)))
                               claims))
                replay-blackboard
                {:summary {:key :summary
                           :schema :string
                           :value nil
                           :version 0}}]
            (is (= :success (:status first-result)) (pr-str first-result))
            (is (some? completed-child) (pr-str claims))
            (streaming/reset-all!)
            (let [{:keys [events-ch] :as subscription}
                  (sheet/subscribe-execution ctx tick-id)
                  replay-result
                  (executor/execute-repl-researcher-rlm
                   node replay-blackboard :test
                   {:sheet-id sheet-id
                    :tick-id tick-id
                    :node-id node-id
                    ;; Replay the same advertised provider contract. The
                    ;; command registry determines whether mint-behavior! is
                    ;; present in both the module and its inputs.
                    :command-registry (:command-registry ctx)
                    :researcher-ownership-epoch 2
                    :researcher-effect-claims claims
                    :claim-researcher-effect! (fn [_] {:command-result/events []})
                    :complete-researcher-effect! (fn [_] {:command-result/events []})})
                  envelopes (drain! events-ch 1000)
                  child-tick-id (get-in completed-child [:result :trace-id])
                  link (first (filter #(= :child-tick-linked
                                          (:orc.stream/type %))
                                      envelopes))]
              (is (nil? (:cognitect.anomalies/category subscription))
                  (pr-str subscription))
              (is (= :success (:status replay-result)) (pr-str replay-result))
              (is (= 1 @provider-calls)
                  "the provider fixture must not obscure completed-child replay")
              (is (= {:tick-id tick-id
                      :parent-tick-id tick-id
                      :child-tick-id child-tick-id}
                     (select-keys link
                                  [:tick-id :parent-tick-id :child-tick-id]))
                  (pr-str {:expected-child child-tick-id
                           :envelopes envelopes})))))))))

(deftest det-e2e-278-terminal-aggregate-is-opt-out-compatibility-only
  (testing "default campaigns use immutable records while explicit opt-out retains its trace fallback"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [calls (atom 0)
            execute-researcher!
            (fn [workflow-name checkpointed?]
              (let [definition
                    (sheet/workflow workflow-name
                      (sheet/blackboard {:summary :string})
                      (sheet/repl-researcher "researcher"
                        :instruction "record two trace iterations"
                        :writes [:summary]
                        :max-iterations 2
                        :rlm {:checkpointed? checkpointed?
                              :recursive? false
                              :timeouts {:provider-ms 1000
                                         :iteration-ms 3000
                                         :campaign-ms 15000}}))
                    sheet-id (sheet/build-workflow! ctx definition)]
                (sheet/execute ctx sheet-id {} :timeout-ms 15000)))]
        (with-redefs [llm/predict
                      (fn [& _]
                        (if (odd? (swap! calls inc))
                          {:outputs {:code "(store! :memo \"trace-history\")"}
                           :usage {:prompt_tokens 2
                                   :completion_tokens 1
                                   :total_tokens 3}}
                          {:outputs {:code "(final! {:summary (get-var :memo)})"}
                           :usage {:prompt_tokens 2
                                   :completion_tokens 1
                                   :total_tokens 3}}))]
          (let [default-result (execute-researcher!
                                "rr16-default-record-only" true)
                opt-out-result (execute-researcher!
                                "rr16-opt-out-aggregate-fallback" false)
                evidence
                (fn [result]
                  (let [tick-id (:trace-id result)
                        events (h/read-tick-events ctx tick-id)]
                    {:records (filterv #(= :rlm/researcher-iteration-recorded
                                           (:event/type %))
                                       events)
                     :aggregates (filterv #(= :rlm/researcher-iterations
                                              (:event/type %))
                                          events)}))
                default-evidence (evidence default-result)
                opt-out-evidence (evidence opt-out-result)]
            (is (= [:success :success]
                   (mapv :status [default-result opt-out-result]))
                (pr-str [default-result opt-out-result]))
            (is (= 2 (count (:records default-evidence)))
                (pr-str default-evidence))
            (is (empty? (:aggregates default-evidence))
                (pr-str default-evidence))
            (is (empty? (:records opt-out-evidence))
                (pr-str opt-out-evidence))
            (is (= 1 (count (:aggregates opt-out-evidence)))
                (pr-str opt-out-evidence))
            (is (h/settle-until!
                 #(and (h/trace-stored? ctx (:trace-id default-result))
                       (h/trace-stored? ctx (:trace-id opt-out-result)))
                 :timeout-ms 10000))
            (is (= [[1 2] [1 2]]
                   (mapv (fn [result]
                           (mapv :iteration
                                 (:researcher-iterations
                                  (rm/get-trace ctx (:trace-id result)))))
                         [default-result opt-out-result])))))))))

(deftest det-e2e-278-durable-order-precedes-root-stream-closure
  (testing "a delayed child iteration cannot be overtaken by a later root completion"
    (h/with-async-test-context [ctx {}]
      (let [root-tick-id (random-uuid)
            child-tick-id (random-uuid)
            sheet-id (random-uuid)
            node-id (random-uuid)
            ps (or (:event-pubsub ctx)
                   (get-in ctx [:event-store :config :event-pubsub]))
            child-tap-entered (promise)
            release-child-tap (promise)
            ;; The tap loop now forwards on its own dedicated thread (RR-27),
            ;; not a shared core.async dispatch thread, but an unbounded
            ;; deref here is still a latent hazard for whichever thread runs
            ;; it — bound it and record the outcome so a wrong
            ;; synchronization fails this test in seconds instead of
            ;; wedging a full gate (.rr-durable-notes/WEDGE-ROOT-CAUSE.md §5).
            release-child-tap-result (atom nil)
            subs-covering-var
            (ns-resolve 'ai.obney.orc.orc-service.core.streaming
                        'subs-covering)
            real-subs-covering @subs-covering-var]
        (with-redefs-fn
          {subs-covering-var
           (fn [tick-id]
             (cond
               (= child-tick-id tick-id)
               (do
                 (deliver child-tap-entered true)
                 (reset! release-child-tap-result
                         (deref release-child-tap 5000 ::release-timed-out)))
               :else nil)
             (real-subs-covering tick-id))}
          (fn []
            (let [{:keys [events-ch]} (sheet/subscribe-execution ctx root-tick-id)]
              (streaming/link-child! root-tick-id child-tick-id)
              (pubsub/pub
               ps
               {:message
                {:event/type :rlm/researcher-iteration-recorded
                 :grain/tenant-id (:tenant-id ctx)
                 :sheet-id sheet-id
                 :tick-id child-tick-id
                 :node-id node-id
                 :iteration-index 0
                 :attempt-ordinal 0
                 :iteration-record {:iteration-index 0
                                    :attempt-ordinal 0
                                    :status :success}}})
              (is (= true (deref child-tap-entered 2000 ::not-entered)))
              (pubsub/pub
               ps
               {:message
                {:event/type :sheet/tree-tick-completed
                 :grain/tenant-id (:tenant-id ctx)
                 :sheet-id sheet-id
                 :tick-id root-tick-id
                 :root-status :success}})
              ;; If event types have separate tap loops, the root terminal can
              ;; reach and close the consumer stream while the earlier child
              ;; iteration remains blocked. A single ordered tap cannot expose
              ;; that terminal until the child event is released.
              (let [[_ before-release]
                    (take-until events-ch
                                #(= :tick-completed (:orc.stream/type %))
                                1000)
                    root-overtook-child?
                    (some #(= :tick-completed (:orc.stream/type %))
                          before-release)]
                (deliver release-child-tap true)
                (let [envelopes (into before-release
                                      (drain! events-ch 2000))
                      types (mapv :orc.stream/type envelopes)
                      iteration-position (.indexOf types :rlm-iteration-recorded)
                      terminal-position (.indexOf types :tick-completed)]
                  (is (and (not (neg? iteration-position))
                           (not (neg? terminal-position))
                           (< iteration-position terminal-position))
                      (pr-str {:root-overtook-child? root-overtook-child?
                               :types types}))
                  (is (not= ::release-timed-out @release-child-tap-result)
                      "the tap's own wait on release-child-tap must not itself time out"))))))))))
