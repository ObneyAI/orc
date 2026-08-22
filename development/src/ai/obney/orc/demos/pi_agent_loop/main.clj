(ns ai.obney.orc.demos.pi-agent-loop.main
  (:gen-class)
  (:require [ai.obney.orc.demos.pi-agent-loop.repl :as repl]
            [ai.obney.orc.demos.pi-agent-loop.system :as system]
            [ai.obney.orc.demos.pi-agent-loop.scenarios :as scenarios]))

(defn- print-summary [{:keys [evidence-class scenario result provider-evidence durable]}]
  (println (pr-str {:evidence-class evidence-class
                    :scenario scenario
                    :status (:status result)
                    :turns (count (filter #(= :turn-start (:type %)) (:events result)))
                    :tool-calls (count (filter #(= :tool-execution-start (:type %)) (:events result)))
                    :provider-responses (count provider-evidence)
                    :orc-ticks (+ (count (:ticks durable))
                                  (count (:model-evidence-ticks durable)))}))
  (println "  transcript")
  (doseq [message (:new-messages result)]
    (println "   " (pr-str (cond-> {:role (:role message) :content (:content message)}
                              (:tool-name message) (assoc :tool (:tool-name message))
                              (:tool-call-id message) (assoc :tool-call-id (:tool-call-id message))))))
  (println "  ORC lineage"
           (pr-str {:tool-ticks (mapv :tick-id (:ticks durable))
                    :model-evidence-ticks (vec (:model-evidence-ticks durable))
                    :provider-response-ids (mapv :response-id provider-evidence)})))

(defn -main [& args]
  (if-let [nrepl-index (first (keep-indexed #(when (= "--nrepl" %2) %1) args))]
    (let [port (nth args (inc nrepl-index) nil)]
      (if port (repl/-main port) (repl/-main)))
    (let [live? (some #{"--live"} args)
          sys (system/start!)]
      (try
        (let [context (:context sys)
              outputs (cond-> [(scenarios/deterministic context)]
                        live? (conj (scenarios/live-tool-selection context)
                                    (scenarios/live-error-recovery context)
                                    (scenarios/live-steering context)
                                    (scenarios/live-structured-orc context)
                                    (scenarios/live-streaming-tool context)))]
          (doseq [output outputs]
            (print-summary output)))
        (finally
          (system/stop! sys)
          (shutdown-agents))))))
