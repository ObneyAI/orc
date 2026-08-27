(ns ai.obney.orc.orc-service.core.trace-publication
  "Atomic publication of execution-trace creation and revision facts."
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]))

(defn- command-envelope
  [context command-name trace]
  (merge {:command/id ((or (:orc/new-command-id! context) random-uuid))
          :command/timestamp ((or (:orc/now! context) time/now))
          :command/name command-name}
         trace))

(defn publish!
  "Publish `trace` exactly once as a creation fact, or as a strictly newer
   revision after another publisher wins the creation claim.

   Command identity and time are injectable through `:orc/new-command-id!` and
   `:orc/now!`; ordinary runtime contexts default to UUIDv4 and Grain time."
  [context trace]
  (let [creation-result
        (cp/process-command
         (assoc context :command
                (command-envelope context :sheet/store-execution-trace trace)))]
    (if (= ::anom/conflict (::anom/category creation-result))
      (cp/process-command
       (assoc context :command
              (command-envelope context :sheet/refresh-execution-trace trace)))
      creation-result)))
