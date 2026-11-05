(ns ai.obney.orc.orc-service.test-helpers-delivery-test
  "The async test context must deliver CHECKPOINTED processors the way
   production does: each (tenant, processor)'s events one at a time, in order.

   Production runs todo-processors through Grain's poller, which dispatches a
   processor's events sequentially. Grain's checkpoint is a gap-free high
   watermark, so a later event that checkpoints first makes the earlier
   event's effect look 'already processed' and it is skipped. Delivering every
   event on its own thread (pubsub) reproduces exactly that loss, which is a
   combination production never uses."
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.todo-processor-v2.interface :as tp]))

(defschemas probe-events
  {:s7p/probe-fired [:map [:n :int]]})

(def ^:private probe-name :evaluation/s7p-probe)

(defn- probe-event [n]
  (es/->event {:type :s7p/probe-fired
               :tags #{[:s7p (random-uuid)]}
               :body {:n n}}))

(deftest checkpointed-effects-are-not-lost-when-an-earlier-event-is-slow
  (let [ran (atom [])
        handler (fn [{:keys [event]}]
                  (let [n (:n event)]
                    ;; The first event is slow to decide; the second is instant.
                    (when (= 1 n) (Thread/sleep 300))
                    {:result/effect (fn [] (swap! ran conj n))
                     :result/checkpoint :after}))]
    (try
      (swap! tp/processor-registry* assoc probe-name
             {:handler-fn handler :topics #{:s7p/probe-fired}})
      (h/with-async-test-context [ctx]
        (es/append (:event-store ctx) {:tenant-id (:tenant-id ctx)
                                       :events [(probe-event 1)]})
        (es/append (:event-store ctx) {:tenant-id (:tenant-id ctx)
                                       :events [(probe-event 2)]})
        (is (h/settle-until! #(= 2 (count @ran)) :timeout-ms 5000)
            (str "both effects must run; ran: " @ran))
        (is (= [1 2] @ran) "effects run in event order"))
      (finally
        (swap! tp/processor-registry* dissoc probe-name)))))
