(ns ai.obney.orc.demos.pi-agent-loop.runtime
  "Stateful queue/abort wrapper around the pure Pi-compatible loop."
  (:refer-clojure :exclude [run!])
  (:require [ai.obney.orc.demos.pi-agent-loop.core :as core]
            [ai.obney.orc.demos.pi-agent-loop.tools :as tools]))

(defrecord Harness [messages steering follow-ups aborted? stop-after-turn?
                    active-ticks cancellations context base-options running?])

(defn create
  [{:keys [messages context active-ticks] :as options}]
  (->Harness (atom (vec messages)) (atom []) (atom []) (atom false) (atom false)
             (or active-ticks (atom #{})) (atom []) context
             (dissoc options :messages :context :active-ticks) (atom false)))

(defn- drain! [queue]
  (let [value @queue]
    (reset! queue [])
    value))

(defn steer! [harness messages]
  (swap! (:steering harness) into messages)
  harness)

(defn follow-up! [harness messages]
  (swap! (:follow-ups harness) into messages)
  harness)

(defn stop-after-turn! [harness]
  (reset! (:stop-after-turn? harness) true)
  harness)

(defn abort! [harness]
  (reset! (:aborted? harness) true)
  (when (:context harness)
    (reset! (:cancellations harness)
            (tools/cancel-active! (:context harness) (:active-ticks harness))))
  harness)

(defn- run-options [harness extra]
  (merge (:base-options harness) extra
         {:messages @(:messages harness)
          :take-steering! #(drain! (:steering harness))
          :take-follow-ups! #(drain! (:follow-ups harness))
          :aborted? #(boolean @(:aborted? harness))
          :should-stop-after-turn?
          (fn [turn]
            (or @(:stop-after-turn? harness)
                (when-let [f (get-in harness [:base-options :should-stop-after-turn?])]
                  (f turn))))}))

(defn- execute! [harness options]
  (when-not (compare-and-set! (:running? harness) false true)
    (throw (ex-info "Harness is already running" {:kind :already-running})))
  (try
    (let [result (core/run options)]
      (swap! (:messages harness) into (:new-messages result))
      result)
    (finally (reset! (:running? harness) false))))

(defn prompt! [harness prompts]
  (execute! harness (run-options harness {:prompts prompts})))

(defn continue! [harness]
  (let [messages @(:messages harness)]
    (when (empty? messages)
      (throw (ex-info "Cannot continue: no messages in context" {:kind :invalid-context})))
    (when (= :assistant (:role (last messages)))
      (throw (ex-info "Cannot continue from message role: assistant"
                      {:kind :invalid-context}))))
  (execute! harness (run-options harness {:prompts []})))

(defn messages [harness] @(:messages harness))
(defn cancellations [harness] @(:cancellations harness))
(defn running? [harness] @(:running? harness))
