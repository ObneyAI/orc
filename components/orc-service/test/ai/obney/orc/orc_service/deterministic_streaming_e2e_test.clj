(ns ai.obney.orc.orc-service.deterministic-streaming-e2e-test
  "Deterministic end-to-end coverage for the ephemeral execution stream."
  (:require [clojure.core.async :as async]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.streaming :as streaming]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(use-fixtures :each
  (fn [f]
    (streaming/reset-all!)
    (try (f) (finally (streaming/reset-all!)))))

(defn identity-value [{:keys [inputs]}]
  {:output (:input inputs)})

(defn fallback-value [_] {:fallback "selected"})
(defn left-value [_] {:left "left"})
(defn right-value [_] {:right "right"})
(defn echo-item [{:keys [inputs]}] {:item (:item inputs)})
(defn delegated-value [{:keys [inputs]}] {:delegated (:input inputs)})

(defn slow-value [{:keys [inputs]}]
  (Thread/sleep 1500)
  {:output (:input inputs)})

(def cancellation-race-state (atom nil))

(defn cancellation-race-item [{:keys [inputs]}]
  (let [item (:item inputs)
        {:keys [release-first release-blocked started completed]} @cancellation-race-state]
    (swap! started conj item)
    @(if (zero? item) release-first release-blocked)
    (swap! completed conj item)
    {:item item}))

(defn- fq [function-name]
  (str "ai.obney.orc.orc-service.deterministic-streaming-e2e-test/"
       function-name))

(defn- drain!
  [events-ch & {:keys [timeout-ms] :or {timeout-ms 10000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [acc []]
      (let [remaining (- deadline (System/currentTimeMillis))]
        (if (pos? remaining)
          (let [[value _] (async/alts!! [events-ch (async/timeout remaining)])]
            (if (nil? value) acc (recur (conj acc value))))
          acc)))))

(defn- take-until [events-ch pred & {:keys [timeout-ms] :or {timeout-ms 10000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [acc []]
      (let [remaining (- deadline (System/currentTimeMillis))]
        (if (pos? remaining)
          (let [[value _] (async/alts!! [events-ch (async/timeout remaining)])]
            (cond
              (nil? value) [nil acc]
              (pred value) [value (conj acc value)]
              :else (recur (conj acc value))))
          [nil acc])))))

(defn- simple-workflow [name function-name]
  (sheet/workflow name
    (sheet/blackboard {:input :string :output :string})
    (sheet/sequence "main"
      (sheet/code "copy" :fn (fq function-name) :reads [:input] :writes [:output]))))

(defn- normalized-durable [ctx tick-id]
  (mapv #(select-keys % [:event/type :node-id :node-type :status :read-keys
                          :write-keys :root-status :output-keys :key :value])
        (h/read-tick-events ctx tick-id)))

(deftest det-e2e-065-streaming-lifecycle
  (testing "all deterministic control-flow types emit one coherent lifecycle taxonomy"
    (h/with-async-test-context [ctx]
      (let [child-id (sheet/build-workflow!
                      ctx
                      (sheet/workflow "det-e2e-065-child"
                        (sheet/blackboard {:input :string :delegated :string})
                        (sheet/code "delegate-leaf" :fn (fq "delegated-value")
                          :reads [:input] :writes [:delegated])))
            workflow (sheet/workflow "det-e2e-065-lifecycle"
                       (sheet/blackboard {:input :string :output :string :flag :boolean
                                          :fallback :string :left :string :right :string
                                          :items [:vector :int] :item :int :mapped [:vector :int]
                                          :delegated :string})
                       (sheet/sequence "main"
                         (sheet/code "copy" :fn (fq "identity-value")
                           :reads [:input] :writes [:output])
                         (sheet/condition "guard" :check {:key :flag :op :equals :value true})
                         (sheet/fallback "fallback"
                           (sheet/code "fallback-success" :fn (fq "fallback-value")
                             :writes [:fallback]))
                         (sheet/parallel "parallel"
                           {:success-policy :all :failure-policy :any}
                           (sheet/code "left" :fn (fq "left-value") :writes [:left])
                           (sheet/code "right" :fn (fq "right-value") :writes [:right]))
                         (sheet/map-each "map" :from :items :as :item :into :mapped :parallel 2
                           (sheet/code "item" :fn (fq "echo-item") :reads [:item] :writes [:item]))
                         (sheet/delegate "delegate" :target-sheet-id child-id
                           :reads [:input] :writes [:delegated])))
            sheet-id (sheet/build-workflow! ctx workflow)
            {:keys [tick-id events-ch result]} (sheet/execute-stream
                                                ctx sheet-id
                                                {:input "x" :flag true :items [1 2 3]})
            execution (deref result 15000 ::timeout)
            envelopes (drain! events-ch :timeout-ms 15000)
            types (mapv :orc.stream/type envelopes)
            node-types (set (keep :node-type envelopes))]
        (is (= :success (:status execution)))
        (is (= :tick-started (first types)))
        (is (= :stream-closed (last types)))
        (is (= :tick-completed (last (butlast types))))
        (is (every? #(= tick-id (:root-tick-id %)) envelopes))
        (is (= (range 1 (inc (count envelopes))) (map :seq envelopes)))
        (is (every? :ts envelopes))
        (is (set/subset? #{:sequence :code :condition :fallback :parallel :map-each :delegate}
                         node-types))
        (is (some #(and (= :progress (:orc.stream/type %)) (= :sequence (:kind %))) envelopes))
        (is (some #(and (= :progress (:orc.stream/type %)) (= :map-each (:kind %))) envelopes))
        (is (some #(= :child-tick-linked (:orc.stream/type %)) envelopes))))))

(deftest det-e2e-066-streaming-preserves-engine-result
  (testing "subscribing changes neither durable events nor final outputs"
    (h/with-async-test-context [ctx]
      (let [sheet-id (sheet/build-workflow! ctx (simple-workflow "det-e2e-066-equivalence" "identity-value"))
            plain-tick (random-uuid)
            plain (sheet/execute ctx sheet-id {:input "same"} :tick-id plain-tick)
            streamed-tick (random-uuid)
            stream (sheet/execute-stream ctx sheet-id {:input "same"} :tick-id streamed-tick)
            streamed (deref (:result stream) 10000 ::timeout)
            envelopes (drain! (:events-ch stream))]
        (is (= (select-keys plain [:status :outputs :error :executed-version])
               (select-keys streamed [:status :outputs :error :executed-version])))
        (is (= (:outputs plain) (:outputs streamed)))
        (is (= (normalized-durable ctx plain-tick)
               (normalized-durable ctx streamed-tick)))
        (is (= :stream-closed (:orc.stream/type (last envelopes))))))))

(deftest det-e2e-067-slow-subscriber
  (testing "a stalled tiny-buffer subscriber cannot slow execution and observes documented sliding loss"
    (h/with-async-test-context [ctx]
      (let [workflow (sheet/workflow "det-e2e-067-slow-subscriber"
                       (sheet/blackboard {:input :string :output :string})
                       (apply sheet/sequence "main"
                              (for [n (range 12)]
                                (sheet/code (str "copy-" n) :fn (fq "identity-value")
                                  :reads [:input] :writes [:output]))))
            sheet-id (sheet/build-workflow! ctx workflow)
            stream (sheet/execute-stream ctx sheet-id {:input "x"} :buffer 1)
            started (System/currentTimeMillis)
            execution (deref (:result stream) 10000 ::timeout)
            elapsed (- (System/currentTimeMillis) started)
            durable-completions (filter #(= :sheet/node-execution-completed (:event/type %))
                                        (h/read-tick-events ctx (:tick-id stream)))
            leftovers (drain! (:events-ch stream) :timeout-ms 2000)]
        (is (= :success (:status execution)))
        (is (< elapsed 5000))
        (is (= 13 (count durable-completions)))
        (is (> (:seq (first leftovers)) 1)
            "the stalled subscriber observes a leading sequence gap from sliding loss")
        (is (apply < (map :seq leftovers))
            "envelopes retained after loss remain strictly ordered")
        (is (= :stream-closed (:orc.stream/type (last leftovers))))))))

(deftest det-e2e-068-subscriber-exception
  (testing "an exception in consumer processing cannot fail execution or durable processing"
    (h/with-async-test-context [ctx]
      (let [sheet-id (sheet/build-workflow! ctx (simple-workflow "det-e2e-068-consumer-error" "identity-value"))
            stream (sheet/execute-stream ctx sheet-id {:input "safe"})
            consumer (future
                       (when (async/<!! (:events-ch stream))
                         (throw (ex-info "subscriber callback failed" {}))))
            execution (deref (:result stream) 10000 ::timeout)
            consumer-error (try @consumer nil (catch Throwable t t))
            durable (h/read-tick-events ctx (:tick-id stream))]
        (is (some? consumer-error))
        (is (= :success (:status execution)))
        (is (= "safe" (get-in execution [:outputs :output])))
        (is (some #(= :sheet/tree-tick-completed (:event/type %)) durable))))))

(deftest det-e2e-069-late-subscription-and-reconnection
  (testing "late subscriptions receive no replay and durable queries provide recovery"
    (h/with-async-test-context [ctx]
      (let [sheet-id (sheet/build-workflow! ctx (simple-workflow "det-e2e-069-late" "identity-value"))
            tick-id (random-uuid)
            result (sheet/execute ctx sheet-id {:input "durable"} :tick-id tick-id)
            late (sheet/subscribe-execution ctx tick-id :ttl-ms 2000)
            [late-value _] (async/alts!! [(:events-ch late) (async/timeout 150)])]
        (is (= :success (:status result)))
        (is (nil? late-value) "completed durable history is not replayed")
        ((:close! late))
        (let [closed (drain! (:events-ch late) :timeout-ms 1000)
              trace (get-in (h/run-query ctx (h/make-get-trace-query tick-id))
                            [:query/result :trace])
              reconnect (sheet/subscribe-execution ctx tick-id :ttl-ms 2000)
              [replayed _] (async/alts!! [(:events-ch reconnect) (async/timeout 150)])]
          (is (= :stream-closed (:orc.stream/type (last closed))))
          (is (= :success (:status trace)))
          (is (= tick-id (:trace-id trace)))
          (is (nil? replayed))
          ((:close! reconnect))
          (drain! (:events-ch reconnect) :timeout-ms 1000))))))

(deftest det-e2e-070-stream-payload-cap
  (testing "stream previews are bounded while durable detail retains the exact value"
    (h/with-async-test-context [ctx]
      (let [large (apply str (repeat 40000 "x"))
            sheet-id (sheet/build-workflow! ctx (simple-workflow "det-e2e-070-cap" "identity-value"))
            stream (sheet/execute-stream ctx sheet-id {:input large})
            execution (deref (:result stream) 10000 ::timeout)
            envelopes (drain! (:events-ch stream))
            completion (first (filter #(and (= :node-completed (:orc.stream/type %))
                                            (= :code (:node-type %)))
                                      envelopes))
            preview (get-in completion [:writes :output])
            _ (is (h/settle-until!
                   #(h/trace-stored? ctx (:tick-id stream))
                   :timeout-ms 10000)
                  "durable trace assembly must finish after the ephemeral stream closes")
            trace (get-in (h/run-query ctx (h/make-get-trace-query (:tick-id stream)))
                          [:query/result :trace])
            leaf (first (filter #(= :leaf (:node-type %)) (:node-traces trace)))
            detail (:query/result
                    (h/run-query ctx {:query/name :sheet/node-trace-detail
                                      :trace-id (:tick-id stream)
                                      :trace-instance-id (:trace-instance-id leaf)}))]
        (is (= large (get-in execution [:outputs :output])))
        (is (true? (:orc.stream/truncated preview)))
        (is (= 16384 (count (:preview preview))))
        (is (= 40000 (:full-size preview)))
        (is (= large (get-in detail [:outputs :output])))))))

(deftest det-e2e-071-streaming-cancellation
  (testing "cancellation emits one terminal cancellation lifecycle and prevents later starts"
    (h/with-async-test-context [ctx]
      (let [workflow (sheet/workflow "det-e2e-071-cancel"
                       (sheet/blackboard {:input :string :output :string})
                       (sheet/sequence "main"
                         (sheet/code "slow" :fn (fq "slow-value")
                           :reads [:input] :writes [:output])
                         (sheet/code "must-not-start" :fn (fq "identity-value")
                           :reads [:input] :writes [:output])))
            sheet-id (sheet/build-workflow! ctx workflow)
            later-id (->> (sheet/get-nodes-for-sheet ctx sheet-id)
                          (some #(when (= "must-not-start" (:name %)) (:id %))))
            stream (sheet/execute-stream ctx sheet-id {:input "x"} :timeout-ms 10000)
            [started before] (take-until (:events-ch stream)
                                         #(and (= :node-started (:orc.stream/type %))
                                               (= :code (:node-type %))))]
        (is (some? started))
        (is (= [(:tick-id stream)] (:cancelled (sheet/cancel! ctx (:tick-id stream)))))
        (let [execution (deref (:result stream) 5000 ::timeout)
              after (drain! (:events-ch stream) :timeout-ms 5000)
              envelopes (into before after)
              types (mapv :orc.stream/type envelopes)]
          (is (not= ::timeout execution))
          (is (true? (:cancelled? execution)))
          (is (= 1 (count (filter #{:tick-cancelled} types))))
          (is (= :stream-closed (last types)))
          (is (= :cancelled (:reason (last envelopes))))
          (is (not-any? #(and (= :node-started (:orc.stream/type %))
                              (= later-id (:node-id %)))
                        envelopes)))))))

(deftest det-e2e-107-cancellation-race-across-delegated-tree
  (testing "one completed iteration survives while cancellation stops blocked and queued delegated work"
    (h/with-async-test-context [ctx]
      (let [release-first (promise)
            release-blocked (promise)
            started (atom [])
            completed (atom [])]
        (reset! cancellation-race-state
                {:release-first release-first
                 :release-blocked release-blocked
                 :started started
                 :completed completed})
        (let [child-id (sheet/build-workflow!
                        ctx
                        (sheet/workflow "det-e2e-107-child"
                          (sheet/blackboard {:items [:vector :int]
                                             :item :int
                                             :results [:vector :int]})
                          (sheet/map-each "bounded-work"
                            :from :items :as :item :into :results :parallel 2
                            (sheet/code "racing-item" :fn (fq "cancellation-race-item")
                              :reads [:item] :writes [:item]))))
              root-id (sheet/build-workflow!
                       ctx
                       (sheet/workflow "det-e2e-107-root"
                         (sheet/blackboard {:items [:vector :int]
                                            :results [:vector :int]})
                         (sheet/delegate "delegated-map" :target-sheet-id child-id
                           :reads [:items] :writes [:results])))
              racing-node-id (->> (sheet/get-nodes-for-sheet ctx child-id)
                                  (some #(when (= "racing-item" (:name %)) (:id %))))
              stream (sheet/execute-stream ctx root-id {:items [0 1 2 3 4]}
                                           :timeout-ms 15000)]
          (try
            (is (h/settle-until! #(= #{0 1} (set @started)) :timeout-ms 3000)
                "only the parallel frontier should start")
            (deliver release-first true)
            (is (h/settle-until! #(= [0] @completed) :timeout-ms 3000)
                "the deliberately released iteration must complete before cancellation")
            (let [cancelled (:cancelled (sheet/cancel! ctx (:tick-id stream)))
                  execution (deref (:result stream) 5000 ::timeout)
                  envelopes (drain! (:events-ch stream) :timeout-ms 5000)
                  types (mapv :orc.stream/type envelopes)]
              (is (= 2 (count cancelled)) "root and delegated child must both be cancelled")
              (is (not= ::timeout execution))
              (is (true? (:cancelled? execution)))
              (is (= [0] @completed))
              (is (= #{0 1} (set @started)))
              (is (= 1 (count (filter #{:tick-cancelled} types))))
              (is (= [:tick-cancelled :stream-closed] (vec (take-last 2 types))))
              (is (= :cancelled (:reason (last envelopes))))
              (is (= (range 1 (inc (count envelopes))) (map :seq envelopes)))
              (let [events (into [] (es/read (:event-store ctx)
                                             {:tenant-id (:tenant-id ctx)}))
                    live (into {} (map (fn [tick-id]
                                         [tick-id (select-keys (rm/get-tick ctx tick-id)
                                                               [:tick-id :sheet-id :status
                                                                :parent-tick-id :root-tick-id])]))
                               cancelled)
                    replayed-ticks (reduce rm/ticks* {} events)
                    replayed (into {} (map (fn [tick-id]
                                             [tick-id (select-keys (get replayed-ticks tick-id)
                                                                   [:tick-id :sheet-id :status
                                                                    :parent-tick-id :root-tick-id])]))
                                   cancelled)
                    leaf-completions (filter #(and (= :sheet/node-execution-completed
                                                       (:event/type %))
                                                   (= racing-node-id (:node-id %)))
                                             events)
                    leaf-writes (filter #(and (= :sheet/execution-value-written
                                                  (:event/type %))
                                              (= racing-node-id (:node-id %))
                                              (= :item (:key %))
                                              (not (:input-seed? %)))
                                        events)
                    cancellations (filter #(= :sheet/tick-cancelled (:event/type %)) events)]
                (is (= 1 (count leaf-completions))
                    (str "only the deliberately released iteration completes durably: "
                         (pr-str (mapv #(select-keys % [:event/id :status :inputs
                                                       :write-keys :error])
                                       leaf-completions))))
                (is (= 1 (count leaf-writes)))
                (is (= 0 (:value (first leaf-writes)))
                    "the completed iteration's result remains resolvable")
                (is (= (set cancelled) (set (map :tick-id cancellations)))
                    "root and delegate each have one durable cancellation")
                (is (every? #(= 1 (count (filter (fn [event]
                                                   (= % (:tick-id event)))
                                                 cancellations)))
                            cancelled))
                (is (= (pr-str live) (pr-str replayed))
                    "normalized terminal tick projections must be byte-equivalent after replay")))
            (finally
              (deliver release-first true)
              (deliver release-blocked true)
              (reset! cancellation-race-state nil))))))))

(deftest det-e2e-072-concurrent-stream-isolation
  (testing "each concurrent envelope belongs to exactly one tick and correlation context"
    (h/with-async-test-context [ctx]
      (let [sheet-id (sheet/build-workflow! ctx (simple-workflow "det-e2e-072-isolation" "identity-value"))
            correlation-a (random-uuid)
            correlation-b (random-uuid)
            stream-a (sheet/execute-stream ctx sheet-id {:input "a"} :correlation-id correlation-a)
            stream-b (sheet/execute-stream ctx sheet-id {:input "b"} :correlation-id correlation-b)
            result-a (deref (:result stream-a) 10000 ::timeout)
            result-b (deref (:result stream-b) 10000 ::timeout)
            events-a (drain! (:events-ch stream-a))
            events-b (drain! (:events-ch stream-b))
            trace-a (get-in (h/run-query ctx (h/make-get-trace-query (:tick-id stream-a)))
                            [:query/result :trace])
            trace-b (get-in (h/run-query ctx (h/make-get-trace-query (:tick-id stream-b)))
                            [:query/result :trace])]
        (is (= ["a" "b"] [(get-in result-a [:outputs :output])
                            (get-in result-b [:outputs :output])]))
        (is (every? #(= (:tick-id stream-a) (:tick-id %)) events-a))
        (is (every? #(= (:tick-id stream-b) (:tick-id %)) events-b))
        (is (empty? (set/intersection (set events-a) (set events-b))))
        (is (= correlation-a (:correlation-id trace-a)))
        (is (= correlation-b (:correlation-id trace-b)))))))
