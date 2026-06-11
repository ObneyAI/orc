(ns ai.obney.orc.orc-service.streaming-test
  "Tests for the live execution streaming hub (core/streaming.clj).

   Covers the Stage 1 guarantees:
   - envelope sequence + monotonic :seq for a simple execution
   - map-each correlation on interleaved child events
   - isolation between concurrent subscribed ticks
   - engine safety: a stalled consumer never stalls or corrupts execution
   - no leaked subscriptions/lineage after any scenario
   - cancellation: cancel! unblocks callers and terminates the stream
   - delegate child-tick lineage cascades into the parent stream
   - ephemeral emit! path (RLM-style events) without an LLM"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.core.async :as async]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.core.streaming :as streaming]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [dscloj.core])) ;; for the Stage 2 stub (intern/with-redefs)

;; =============================================================================
;; Fixtures + helpers
;; =============================================================================

(use-fixtures :each
  (fn [f]
    (streaming/reset-all!)
    (try (f) (finally (streaming/reset-all!)))))

(defn- subscriptions* [] @@#'streaming/subscriptions)
(defn- lineage* [] @@#'streaming/lineage)

(defn identity-fn [{:keys [inputs]}]
  {:output (get inputs :input)})

(defn slow-fn [{:keys [inputs]}]
  (Thread/sleep 2000)
  {:output (get inputs :input)})

(defn double-value [{:keys [inputs]}]
  (let [item (get inputs :current-item)]
    {:current-item (update item :value * 2)}))

(defn- drain!
  "Blockingly collect envelopes until events-ch closes or timeout-ms elapses."
  [events-ch & {:keys [timeout-ms] :or {timeout-ms 10000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [acc []]
      (let [remaining (- deadline (System/currentTimeMillis))]
        (if (pos? remaining)
          (let [[v _] (async/alts!! [events-ch (async/timeout remaining)])]
            (if (nil? v)
              acc
              (recur (conj acc v))))
          acc)))))

(defn- take-until
  "Blockingly read envelopes until pred matches one (returning [matched acc])
   or timeout ([nil acc])."
  [events-ch pred & {:keys [timeout-ms] :or {timeout-ms 10000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [acc []]
      (let [remaining (- deadline (System/currentTimeMillis))]
        (if (pos? remaining)
          (let [[v _] (async/alts!! [events-ch (async/timeout remaining)])]
            (cond
              (nil? v) [nil acc]
              (pred v) [v (conj acc v)]
              :else (recur (conj acc v))))
          [nil acc])))))

(defn- setup-code-sheet!
  "Sheet with a sequence of n code leaves reading :input writing :output."
  [ctx & {:keys [n fn-sym] :or {n 1 fn-sym "ai.obney.orc.orc-service.streaming-test/identity-fn"}}]
  (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Streaming Test Sheet"))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)]
    (doseq [k [:input :output]]
      (h/run-and-apply! ctx (h/make-declare-key-command sheet-id k :string)))
    (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
          seq-id (-> seq-result :command-result/events first :node-id)
          leaf-ids (vec
                    (for [_ (range n)]
                      (let [leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
                            leaf-id (-> leaf-result :command-result/events first :node-id)]
                        (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code :fn fn-sym))
                        (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [:input] [:output]))
                        leaf-id)))]
      {:sheet-id sheet-id :seq-id seq-id :leaf-ids leaf-ids})))

(defn- dispatch-tick!
  "Subscribe-first dispatch: register completion + tick-tree with a fixed tick-id."
  [ctx sheet-id inputs tick-id & {:keys [timeout-ms] :or {timeout-ms 10000}}]
  (let [p (runtime/register-completion! tick-id)
        cmd-result (cp/process-command
                    (assoc ctx :command
                           {:command/id (random-uuid)
                            :command/timestamp (time/now)
                            :command/name :sheet/tick-tree
                            :sheet-id sheet-id
                            :tick-id tick-id
                            :inputs inputs
                            :options {:timeout-ms timeout-ms}}))]
    (is (not (:cognitect.anomalies/category cmd-result)) "tick-tree dispatch should succeed")
    p))

;; =============================================================================
;; Envelope sequence e2e
;; =============================================================================

(deftest envelope-sequence-test
  (testing "simple execution streams ordered lifecycle envelopes with monotonic :seq"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id leaf-ids]} (setup-code-sheet! ctx)
            tick-id (random-uuid)
            {:keys [events-ch]} (sheet/subscribe-execution ctx tick-id)
            p (dispatch-tick! ctx sheet-id {:input "hello"} tick-id)
            result (deref p 10000 ::timeout)
            envelopes (drain! events-ch)]
        (is (= :success (:status result)))
        ;; first and last
        (is (= :tick-started (:orc.stream/type (first envelopes))))
        (is (= :stream-closed (:orc.stream/type (last envelopes))))
        (is (= :completed (:reason (last envelopes))))
        (is (= :tick-completed (:orc.stream/type (last (butlast envelopes)))))
        ;; monotonic seq
        (is (= (map :seq envelopes) (range 1 (inc (count envelopes)))))
        ;; envelope basics
        (is (every? #(= tick-id (:root-tick-id %)) envelopes))
        (is (every? #(some? (:ts %)) envelopes))
        ;; leaf lifecycle present, with incremental result payload
        (let [leaf-id (first leaf-ids)
              started (filter #(and (= :node-started (:orc.stream/type %))
                                    (= leaf-id (:node-id %)))
                              envelopes)
              completed (filter #(and (= :node-completed (:orc.stream/type %))
                                      (= leaf-id (:node-id %)))
                                envelopes)]
          (is (= 1 (count started)))
          (is (= 1 (count completed)))
          (is (= :success (:status (first completed))))
          (is (= "hello" (get-in (first completed) [:writes :output])))
          ;; node-started precedes node-completed for the same node
          (is (< (:seq (first started)) (:seq (first completed)))))
        ;; tick-completed carries outputs
        (let [tc (first (filter #(= :tick-completed (:orc.stream/type %)) envelopes))]
          (is (= :success (:status tc))))
        ;; subscription cleaned up after close
        (is (empty? (subscriptions*)))))))

;; =============================================================================
;; Map-each correlation
;; =============================================================================

(deftest map-each-correlation-test
  (testing "map-each child events carry {:parent :index} correlation"
    (h/with-async-test-context [ctx]
      (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Streaming MapEach"))
            sheet-id (-> sheet-result :command-result/events first :sheet-id)]
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :items [:vector :map]))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :current-item :map))
        (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :results [:vector :map]))
        (let [map-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :map-each))
              map-id (-> map-result :command-result/events first :node-id)
              _ (h/run-and-apply! ctx (h/make-set-map-each-config-command sheet-id map-id
                                                                          :items :current-item :results))
              leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id map-id))
              leaf-id (-> leaf-result :command-result/events first :node-id)]
          (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :code
                                                                  :fn "ai.obney.orc.orc-service.streaming-test/double-value"))
          (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [:current-item] [:current-item]))
          (let [tick-id (random-uuid)
                {:keys [events-ch]} (sheet/subscribe-execution ctx tick-id)
                p (dispatch-tick! ctx sheet-id
                                  {:items [{:id 1 :value 5} {:id 2 :value 10} {:id 3 :value 15}]}
                                  tick-id)
                result (deref p 10000 ::timeout)
                envelopes (drain! events-ch)
                child-completions (filter #(and (= :node-completed (:orc.stream/type %))
                                                (= leaf-id (:node-id %)))
                                          envelopes)]
            (is (= :success (:status result)))
            (is (= 3 (count child-completions)))
            (is (every? #(= map-id (get-in % [:map-each :parent])) child-completions))
            (is (= #{0 1 2} (set (map #(get-in % [:map-each :index]) child-completions))))
            ;; progress events observed
            (is (some #(and (= :progress (:orc.stream/type %))
                            (= :map-each (:kind %)))
                      envelopes))))))))

;; =============================================================================
;; Concurrent tick isolation
;; =============================================================================

(deftest concurrent-ticks-isolation-test
  (testing "two concurrent subscribed ticks receive only their own events"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (setup-code-sheet! ctx)
            tick-a (random-uuid)
            tick-b (random-uuid)
            sub-a (sheet/subscribe-execution ctx tick-a)
            sub-b (sheet/subscribe-execution ctx tick-b)
            pa (dispatch-tick! ctx sheet-id {:input "aaa"} tick-a)
            pb (dispatch-tick! ctx sheet-id {:input "bbb"} tick-b)
            ra (deref pa 10000 ::timeout)
            rb (deref pb 10000 ::timeout)
            ea (drain! (:events-ch sub-a))
            eb (drain! (:events-ch sub-b))]
        (is (= :success (:status ra)))
        (is (= :success (:status rb)))
        (is (every? #(= tick-a (:tick-id %)) ea))
        (is (every? #(= tick-b (:tick-id %)) eb))
        (is (= "aaa" (some #(get-in % [:writes :output]) ea)))
        (is (= "bbb" (some #(get-in % [:writes :output]) eb)))
        (is (empty? (subscriptions*)))))))

;; =============================================================================
;; Engine safety under a stalled consumer
;; =============================================================================

(deftest stalled-consumer-engine-safety-test
  (testing "a consumer that never reads its channel cannot stall the engine or lose durable events"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id leaf-ids]} (setup-code-sheet! ctx :n 10)
            tick-id (random-uuid)
            ;; tiny buffer + zero consumption = guaranteed overflow
            subscription (sheet/subscribe-execution ctx tick-id :buffer 1)
            p (dispatch-tick! ctx sheet-id {:input "x"} tick-id)
            result (deref p 15000 ::timeout)]
        (is (= :success (:status result)) "engine completes despite stalled consumer")
        ;; every durable node completion reached the event store
        (let [completed (->> (es/read (:event-store ctx)
                                      {:tenant-id (:tenant-id ctx)
                                       :types #{:sheet/node-execution-completed}
                                       :tags #{[:tick tick-id]}})
                             (into [])
                             (filter #(contains? (set leaf-ids) (:node-id %))))]
          (is (= 10 (count completed))))
        ;; the stalled channel still yields the newest event (sliding semantics)
        (let [leftovers (drain! (:events-ch subscription) :timeout-ms 2000)]
          (is (<= (count leftovers) 2) "sliding buffer kept only the newest")
          (is (= :stream-closed (:orc.stream/type (last leftovers)))
              "terminal event survives the slide"))
        (is (empty? (subscriptions*)))))))

;; =============================================================================
;; Cancellation
;; =============================================================================

(deftest cancellation-test
  (testing "cancel! unblocks the caller and terminates the stream"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id]} (setup-code-sheet!
                                ctx :fn-sym "ai.obney.orc.orc-service.streaming-test/slow-fn")
            {:keys [tick-id events-ch result]} (sheet/execute-stream ctx sheet-id {:input "x"}
                                                                     :timeout-ms 20000)
            ;; wait until the leaf is actually running
            [started pre] (take-until events-ch #(= :node-started (:orc.stream/type %)))]
        (is (some? started) (str "expected a :node-started envelope, got " (mapv :orc.stream/type pre)))
        (let [cancel-result (sheet/cancel! ctx tick-id)]
          (is (= [tick-id] (:cancelled cancel-result))))
        ;; blocking caller unblocks promptly (well before the 20s timeout)
        (let [r (deref result 5000 ::timeout)]
          (is (not= ::timeout r))
          (is (true? (:cancelled? r)))
          (is (= :failure (:status r))))
        ;; stream ends with :tick-cancelled then :stream-closed {:reason :cancelled}
        (let [rest-envelopes (drain! events-ch :timeout-ms 5000)
              types (mapv :orc.stream/type rest-envelopes)]
          (is (some #{:tick-cancelled} types))
          (is (= :stream-closed (last types)))
          (is (= :cancelled (:reason (last rest-envelopes)))))
        (is (empty? (subscriptions*)))
        (is (empty? (lineage*))))))

  (testing "cancel! on an unknown tick returns an anomaly"
    (h/with-async-test-context [ctx]
      (let [r (sheet/cancel! ctx (random-uuid))]
        (is (= :cognitect.anomalies/not-found (:cognitect.anomalies/category r)))))))

;; =============================================================================
;; Delegate lineage cascade
;; =============================================================================

(deftest delegate-lineage-test
  (testing "delegate child tick events cascade into the parent subscription"
    (h/with-async-test-context [ctx]
      (let [;; child sheet: one identity leaf
            {child-sheet-id :sheet-id child-leaf-ids :leaf-ids} (setup-code-sheet! ctx)
            ;; parent sheet: delegate node targeting the child sheet
            parent-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Streaming Delegate Parent"))
            parent-sheet-id (-> parent-result :command-result/events first :sheet-id)]
        (doseq [k [:input :output]]
          (h/run-and-apply! ctx (h/make-declare-key-command parent-sheet-id k :string)))
        (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command parent-sheet-id :sequence))
              seq-id (-> seq-result :command-result/events first :node-id)
              del-result (h/run-and-apply! ctx (h/make-create-node-command parent-sheet-id :delegate :parent-id seq-id))
              del-id (-> del-result :command-result/events first :node-id)]
          (h/run-and-apply! ctx (h/make-set-delegate-config-command parent-sheet-id del-id child-sheet-id
                                                                    :reads [:input] :writes [:output]))
          (let [tick-id (random-uuid)
                {:keys [events-ch]} (sheet/subscribe-execution ctx tick-id)
                p (dispatch-tick! ctx parent-sheet-id {:input "via-delegate"} tick-id :timeout-ms 15000)
                result (deref p 15000 ::timeout)
                envelopes (drain! events-ch)]
            (is (= :success (:status result)))
            (is (= "via-delegate" (get-in result [:outputs :output])))
            ;; lineage envelope observed
            (let [linked (first (filter #(= :child-tick-linked (:orc.stream/type %)) envelopes))]
              (is (some? linked))
              (is (= tick-id (:parent-tick-id linked)))
              ;; the child tick's own leaf events arrived on the parent stream
              (let [child-tick (:child-tick-id linked)
                    child-completions (filter #(and (= :node-completed (:orc.stream/type %))
                                                    (= child-tick (:tick-id %))
                                                    (contains? (set child-leaf-ids) (:node-id %)))
                                              envelopes)]
                (is (seq child-completions))
                (is (= "via-delegate"
                       (get-in (first child-completions) [:writes :output])))))
            (is (empty? (subscriptions*)))))))))

;; =============================================================================
;; Ephemeral emit! path (RLM-style events without an LLM)
;; =============================================================================

(deftest ephemeral-emit-test
  (testing "emit! events reach covering subscriptions with hub-assigned seq/ts"
    (h/with-async-test-context [ctx]
      (let [tick-id (random-uuid)
            {:keys [events-ch close!]} (sheet/subscribe-execution ctx tick-id)]
        (streaming/emit! tick-id {:orc.stream/type :rlm-iteration-started
                                  :iteration 1
                                  :max-iterations 10})
        (let [[env _] (take-until events-ch #(= :rlm-iteration-started (:orc.stream/type %))
                                  :timeout-ms 2000)]
          (is (some? env))
          (is (= 1 (:iteration env)))
          (is (pos? (:seq env)))
          (is (some? (:ts env)))
          (is (= tick-id (:root-tick-id env))))
        (close!)
        (let [rest-envelopes (drain! events-ch :timeout-ms 2000)]
          (is (= :stream-closed (:orc.stream/type (last rest-envelopes))))
          (is (= :closed-by-consumer (:reason (last rest-envelopes)))))
        (is (empty? (subscriptions*))))))

  (testing "emit! to a child tick reaches a subscription on the root"
    (h/with-async-test-context [ctx]
      (let [root-tick (random-uuid)
            child-tick (random-uuid)
            {:keys [events-ch close!]} (sheet/subscribe-execution ctx root-tick)]
        (streaming/link-child! root-tick child-tick)
        (streaming/emit! child-tick {:orc.stream/type :rlm-code-generated
                                     :iteration 1
                                     :code "(+ 1 1)"})
        (let [[env collected] (take-until events-ch #(= :rlm-code-generated (:orc.stream/type %))
                                          :timeout-ms 2000)]
          (is (some? env))
          (is (= child-tick (:tick-id env)))
          (is (= root-tick (:root-tick-id env)))
          ;; the lineage link itself was announced
          (is (some #(= :child-tick-linked (:orc.stream/type %)) collected)))
        (close!)
        (drain! events-ch :timeout-ms 2000)
        (is (empty? (subscriptions*)))))))

;; =============================================================================
;; Stage 2: LLM token streaming (stubbed predict-stream-v2)
;; =============================================================================

(defn- setup-ai-sheet!
  "Sheet with one :ai leaf reading :input writing :answer."
  [ctx]
  (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Streaming AI Sheet"))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)]
    (doseq [k [:input :answer]]
      (h/run-and-apply! ctx (h/make-declare-key-command sheet-id k :string)))
    (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
          seq-id (-> seq-result :command-result/events first :node-id)
          leaf-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :leaf :parent-id seq-id))
          leaf-id (-> leaf-result :command-result/events first :node-id)]
      (h/run-and-apply! ctx (h/make-set-node-executor-command sheet-id leaf-id :ai))
      (h/run-and-apply! ctx (h/make-set-node-instruction-command sheet-id leaf-id "Answer the question."))
      (h/run-and-apply! ctx (h/make-set-node-io-command sheet-id leaf-id [:input] [:answer]))
      {:sheet-id sheet-id :leaf-id leaf-id})))

(def ^:private stub-usage {:prompt-tokens 10 :completion-tokens 20 :total-tokens 30})

(defn- stub-stream-ch
  "Fake predict-stream-v2 channel: deltas + progressive fields + final."
  [& _args]
  (let [ch (async/chan 16)]
    (async/onto-chan! ch
                      [{:dscloj/event :delta :text "stream"}
                       {:dscloj/event :fields :fields {:answer "stream"}}
                       {:dscloj/event :delta :text "ed answer"}
                       {:dscloj/event :fields :fields {:answer "streamed answer"}}
                       {:dscloj/event :final
                        :outputs {:answer "streamed answer"}
                        :usage stub-usage
                        :model "stub-model"}])
    ch))

(defmacro with-stub-predict-stream-v2 [& body]
  `(do (intern (the-ns 'dscloj.core) '~'predict-stream-v2 stub-stream-ch)
       (try ~@body
            (finally (ns-unmap (the-ns 'dscloj.core) '~'predict-stream-v2)))))

(deftest llm-token-streaming-test
  (testing "an :ai leaf streams field snapshots and raw deltas when a subscriber opted in"
    (h/with-async-test-context [ctx]
      (with-stub-predict-stream-v2
        (let [{:keys [sheet-id leaf-id]} (setup-ai-sheet! ctx)
              tick-id (random-uuid)
              {:keys [events-ch]} (sheet/subscribe-execution ctx tick-id
                                                             :llm-deltas? true
                                                             :raw-deltas? true)
              p (dispatch-tick! ctx sheet-id {:input "q"} tick-id)
              result (deref p 10000 ::timeout)
              envelopes (drain! events-ch)
              raw (filter #(= :llm-raw-delta (:orc.stream/type %)) envelopes)
              fields (filter #(= :llm-fields (:orc.stream/type %)) envelopes)
              completed (first (filter #(and (= :node-completed (:orc.stream/type %))
                                             (= leaf-id (:node-id %)))
                                       envelopes))]
          (is (= :success (:status result)))
          ;; raw deltas in order
          (is (= ["stream" "ed answer"] (mapv :text raw)))
          ;; progressive field snapshots, growing, ending with :final? true
          (is (= ["stream" "streamed answer" "streamed answer"]
                 (mapv #(get-in % [:fields :answer]) fields)))
          (is (true? (:final? (last fields))))
          ;; all delta events are node-scoped and arrive after :node-started
          (let [started-seq (:seq (first (filter #(and (= :node-started (:orc.stream/type %))
                                                       (= leaf-id (:node-id %)))
                                                 envelopes)))]
            (is (every? #(= leaf-id (:node-id %)) (concat raw fields)))
            (is (every? #(> (:seq %) started-seq) (concat raw fields)))
            (is (every? #(< (:seq %) (:seq completed)) (concat raw fields))))
          ;; the durable completion is unaffected: full writes + usage
          (is (= "streamed answer" (get-in completed [:writes :answer])))
          (is (= stub-usage (:usage completed)))
          (is (= "streamed answer" (get-in result [:outputs :answer])))))))

  (testing "without delta opt-in the stub is never consulted (blocking path preserved)"
    (h/with-async-test-context [ctx]
      (with-stub-predict-stream-v2
        (with-redefs [dscloj.core/predict (fn [& _]
                                            {:outputs {:answer "blocking answer"}
                                             :usage stub-usage
                                             :model "stub-model"})]
          (let [{:keys [sheet-id]} (setup-ai-sheet! ctx)
                tick-id (random-uuid)
                {:keys [events-ch]} (sheet/subscribe-execution ctx tick-id) ;; no :llm-deltas?
                p (dispatch-tick! ctx sheet-id {:input "q"} tick-id)
                result (deref p 10000 ::timeout)
                envelopes (drain! events-ch)]
            (is (= :success (:status result)))
            (is (= "blocking answer" (get-in result [:outputs :answer])))
            (is (empty? (filter #(#{:llm-fields :llm-raw-delta} (:orc.stream/type %))
                                envelopes)))))))))

(deftest streamed-vs-blocking-equivalence-test
  (testing "streamed and blocking executions persist identical node-completion data"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id leaf-id]} (setup-ai-sheet! ctx)
            completed-event (fn [tick-id]
                              (->> (es/read (:event-store ctx)
                                            {:tenant-id (:tenant-id ctx)
                                             :types #{:sheet/node-execution-completed}
                                             :tags #{[:tick tick-id]}})
                                   (into [])
                                   (filter #(= leaf-id (:node-id %)))
                                   first))
            ;; streamed run
            streamed-tick (random-uuid)
            _ (with-stub-predict-stream-v2
                (let [{:keys [events-ch]} (sheet/subscribe-execution ctx streamed-tick
                                                                     :llm-deltas? true)
                      p (dispatch-tick! ctx sheet-id {:input "q"} streamed-tick)]
                  (is (= :success (:status (deref p 10000 ::timeout))))
                  (drain! events-ch)))
            ;; blocking run, same payload via redefined predict
            blocking-tick (random-uuid)
            _ (with-redefs [dscloj.core/predict (fn [& _]
                                                  {:outputs {:answer "streamed answer"}
                                                   :usage stub-usage
                                                   :model "stub-model"})]
                (let [p (dispatch-tick! ctx sheet-id {:input "q"} blocking-tick)]
                  (is (= :success (:status (deref p 10000 ::timeout))))))
            streamed-ev (completed-event streamed-tick)
            blocking-ev (completed-event blocking-tick)
            relevant #(select-keys % [:writes :usage :status :node-id])]
        (is (some? streamed-ev))
        (is (some? blocking-ev))
        (is (= (relevant streamed-ev) (relevant blocking-ev)))))))

;; =============================================================================
;; Review regressions
;; =============================================================================

(deftest link-child-after-finalize-no-zombie-test
  (testing "link-child! racing a finalized subscription never resurrects a registry entry"
    (h/with-async-test-context [ctx]
      (let [root (random-uuid)
            child (random-uuid)
            {:keys [events-ch close!]} (sheet/subscribe-execution ctx root)]
        (close!)
        (drain! events-ch :timeout-ms 2000)
        (is (empty? (subscriptions*)))
        ;; the race: a delegate/RLM spawn links a child after the root closed
        (streaming/link-child! root child)
        (is (empty? (subscriptions*)) "no zombie entry recreated")
        ;; registry scans keep working for unrelated and related ticks
        (is (false? (streaming/wanted? child)))
        (is (nil? (streaming/delta-config child)))
        ;; and a fresh subscription on a new tick still functions
        (let [tick (random-uuid)
              {events-ch2 :events-ch close2! :close!} (sheet/subscribe-execution ctx tick)]
          (streaming/emit! tick {:orc.stream/type :rlm-iteration-started :iteration 1})
          (let [[env _] (take-until events-ch2 #(= :rlm-iteration-started (:orc.stream/type %))
                                    :timeout-ms 2000)]
            (is (some? env)))
          (close2!)
          (drain! events-ch2 :timeout-ms 2000))))))

(deftest delta-opt-in-per-subscription-test
  (testing "token-delta envelopes are delivered only to subscriptions that opted in"
    (h/with-async-test-context [ctx]
      (with-stub-predict-stream-v2
        (let [{:keys [sheet-id]} (setup-ai-sheet! ctx)
              tick-id (random-uuid)
              opted (sheet/subscribe-execution ctx tick-id :llm-deltas? true :raw-deltas? true)
              plain (sheet/subscribe-execution ctx tick-id)
              p (dispatch-tick! ctx sheet-id {:input "q"} tick-id)
              result (deref p 10000 ::timeout)
              opted-envs (drain! (:events-ch opted))
              plain-envs (drain! (:events-ch plain))
              delta? #(#{:llm-fields :llm-raw-delta} (:orc.stream/type %))]
          (is (= :success (:status result)))
          (is (seq (filter delta? opted-envs)) "opted subscription receives deltas")
          (is (empty? (filter delta? plain-envs)) "plain subscription receives none")
          ;; both still see the full lifecycle
          (doseq [envs [opted-envs plain-envs]]
            (is (some #(= :node-completed (:orc.stream/type %)) envs))
            (is (= :stream-closed (:orc.stream/type (last envs)))))
          ;; seq stays gapless per subscription even with filtering
          (is (= (map :seq plain-envs) (range 1 (inc (count plain-envs)))))
          (is (empty? (subscriptions*))))))))

;; =============================================================================
;; Truncation
;; =============================================================================

(deftest value-truncation-test
  (testing "oversized write values are capped with a truncation marker"
    (h/with-async-test-context [ctx]
      (let [{:keys [sheet-id leaf-ids]} (setup-code-sheet! ctx)
            big (apply str (repeat 40000 "x"))
            tick-id (random-uuid)
            {:keys [events-ch]} (sheet/subscribe-execution ctx tick-id)
            p (dispatch-tick! ctx sheet-id {:input big} tick-id)
            result (deref p 10000 ::timeout)
            envelopes (drain! events-ch)
            completed (first (filter #(and (= :node-completed (:orc.stream/type %))
                                           (= (first leaf-ids) (:node-id %)))
                                     envelopes))
            v (get-in completed [:writes :output])]
        (is (= :success (:status result)))
        (is (map? v))
        (is (true? (:orc.stream/truncated v)))
        (is (= 40000 (:full-size v)))
        (is (= 16384 (count (:preview v))))))))
