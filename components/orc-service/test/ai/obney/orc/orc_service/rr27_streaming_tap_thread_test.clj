(ns ai.obney.orc.orc-service.rr27-streaming-tap-thread-test
  "RR-27: the streaming tap forwards durable events off the engine's fixed
   core.async dispatch pool, on its own thread — exactly as start-router!
   already does and for the reason its docstring states (streaming.clj
   377-395), per the spec invariant
   ExecutionEventStream.StreamingNeverOccupiesTheEngineDispatchPool
   (specs/orc-service.allium)."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.orc.orc-service.core.streaming :as streaming]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(use-fixtures :each
  (fn [f]
    (streaming/reset-all!)
    (try
      (f)
      (finally
        (streaming/reset-all!)))))

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

(defn identity-value [{:keys [inputs]}]
  {:output (:input inputs)})

(defn- fq [function-name]
  (str "ai.obney.orc.orc-service.rr27-streaming-tap-thread-test/" function-name))

(defn- small-deterministic-workflow []
  (sheet/workflow "rr27-tap-thread-probe"
    (sheet/blackboard {:input :string :output :string})
    (sheet/sequence "main"
      (sheet/code "copy" :fn (fq "identity-value") :reads [:input] :writes [:output]))))

(deftest forwarding-never-runs-on-a-dispatch-thread
  (testing "the tap loop's forwarding work does not execute on the fixed core.async dispatch pool"
    (h/with-async-test-context [ctx {}]
      (let [root-tick-id (random-uuid)
            sheet-id (random-uuid)
            ps (or (:event-pubsub ctx)
                   (get-in ctx [:event-store :config :event-pubsub]))
            recorded-thread-name (atom nil)
            subs-covering-var
            (ns-resolve 'ai.obney.orc.orc-service.core.streaming
                        'subs-covering)
            real-subs-covering @subs-covering-var]
        (with-redefs-fn
          {subs-covering-var
           (fn [tick-id]
             (reset! recorded-thread-name (.getName (Thread/currentThread)))
             (real-subs-covering tick-id))}
          (fn []
            (let [{:keys [events-ch]} (sheet/subscribe-execution ctx root-tick-id)]
              (pubsub/pub
               ps
               {:message
                {:event/type :sheet/tree-tick-started
                 :grain/tenant-id (:tenant-id ctx)
                 :sheet-id sheet-id
                 :tick-id root-tick-id}})
              (let [[envelope _] (take-until events-ch
                                             #(= :tick-started (:orc.stream/type %))
                                             3000)]
                (is (some? envelope) "the tapped event was forwarded to the subscription")
                (is (some? @recorded-thread-name)
                    "subs-covering was invoked by the tap loop")
                (is (not (str/starts-with? (or @recorded-thread-name "")
                                            "async-dispatch-"))
                    (pr-str {:recorded-thread-name @recorded-thread-name}))))))))))

(deftest a-blocked-tap-path-holds-no-dispatch-thread-and-delays-no-workflow
  (testing "a blocked tap subscription path occupies no dispatch thread and delays no workflow execution"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [target-tick-id (random-uuid)
            sheet-id (random-uuid)
            ps (or (:event-pubsub ctx)
                   (get-in ctx [:event-store :config :event-pubsub]))
            tap-entered (promise)
            release-tap (promise)
            subs-covering-var
            (ns-resolve 'ai.obney.orc.orc-service.core.streaming
                        'subs-covering)
            real-subs-covering @subs-covering-var]
        (with-redefs-fn
          {subs-covering-var
           (fn [tick-id]
             (when (= tick-id target-tick-id)
               (deliver tap-entered true)
               ;; Bounded even though this is deliberately a blocking
               ;; probe: an unbounded deref on a shared thread is exactly
               ;; the hazard this slice exists to eliminate (see
               ;; .rr-durable-notes/WEDGE-ROOT-CAUSE.md §5).
               (deref release-tap 10000 ::timed-out))
             (real-subs-covering tick-id))}
          (fn []
            (let [{:keys [events-ch]} (sheet/subscribe-execution ctx target-tick-id)]
              (pubsub/pub
               ps
               {:message
                {:event/type :sheet/tree-tick-started
                 :grain/tenant-id (:tenant-id ctx)
                 :sheet-id sheet-id
                 :tick-id target-tick-id}})
              (is (= true (deref tap-entered 3000 ::not-entered))
                  "the tap loop is observably blocked inside subs-covering")

              ;; (a) No dispatch-pool thread is executing the tap loop while
              ;; it is held blocked.
              (let [dispatch-threads-with-tap-frame
                    (into []
                          (comp
                           (filter (fn [[t _]]
                                     (str/starts-with? (.getName ^Thread t) "async-dispatch-")))
                           (filter (fn [[_ trace]]
                                     (some (fn [^StackTraceElement frame]
                                             (str/includes? (.getClassName frame)
                                                             "streaming$ensure_tap_BANG_"))
                                           trace)))
                           (map (fn [[t _]] (.getName ^Thread t))))
                          (Thread/getAllStackTraces))]
                (is (empty? dispatch-threads-with-tap-frame)
                    (pr-str {:dispatch-threads-with-tap-frame dispatch-threads-with-tap-frame})))

              ;; (b) A workflow executes to completion while the tap is held.
              (let [sheet-id-2 (sheet/build-workflow! ctx (small-deterministic-workflow))
                    result (sheet/execute ctx sheet-id-2 {:input "x"} :timeout-ms 5000)]
                (is (= :success (:status result)) (pr-str result)))

              (deliver release-tap true)
              (let [[envelope _] (take-until events-ch
                                             #(= :tick-started (:orc.stream/type %))
                                             3000)]
                (is (some? envelope)
                    "the held envelope is forwarded once the tap unblocks")))))))))
