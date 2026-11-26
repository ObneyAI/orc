(ns ai.obney.orc.orc-service.core.recovery-scan
  "Decide, before anything is written, whether the periodic recovery scan has
   work to do.

   PeriodicRecoveryWritesOnlyWhenWorkIsAbandoned
   (specs/orc-service.allium, contract ExecutionRecovery).

   A Grain periodic handler receives only [tenant-id time]; it holds no event
   store or cache. A consumer that wants a quiet scan registers one capability
   at runtime start: a function from tenant id to the tenant's ORC context (the
   same context it passes to `resume-in-progress!`). Without it, or when the
   state cannot be read, the scan records its trigger exactly as before."
  (:require [ai.obney.orc.orc-service.core.runtime :as runtime]
            [com.brunobonacci.mulog :as u]))

(defonce ^:private context-fn (atom nil))

(defn register-context-fn!
  "Register `f`, a function from tenant id to that tenant's ORC context (or nil
   when unknown). Replaces any earlier registration. Returns a no-arg function
   that removes this registration (only if it is still the current one)."
  [f]
  (reset! context-fn f)
  (fn [] (compare-and-set! context-fn f nil)))

(defn needed?
  "True when the periodic scan should record its trigger for `tenant-id`."
  [tenant-id]
  (if-let [f @context-fn]
    (try
      (if-let [context (f tenant-id)]
        (boolean (runtime/abandoned-work? context))
        true)
      (catch Throwable t
        ;; Unreadable state: record the trigger rather than risk leaving
        ;; abandoned work unrecovered.
        (u/log ::recovery-scan-state-unreadable :tenant-id tenant-id
               :error (ex-message t))
        true))
    true))
