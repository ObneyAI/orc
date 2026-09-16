(ns ai.obney.orc.evaluation.core.commands
  "Command handlers that WRITE the per-event judge score events.

   These are the ONLY writers of `:judge/score-emitted` and
   `:judge/composite-score-computed`. The judge runtime (judge_runtime.clj)
   resolves + runs judges in a background future and dispatches these
   commands via `cp/process-command` once each judge result is ready —
   the same async pattern as orc-service's `execute-leaf-node` (slow work
   in a future, completion emitted via a command, never blocking the
   pubsub/poller thread).

   Both commands are IDEMPOTENT on their identity tuple so an at-least-once
   redelivery (effect-path replay) or a future re-run can't double-emit:
     - record-judge-score: idempotent on [sheet-id node-id tick-id judge-name]
     - record-composite-score: idempotent on [sheet-id node-id tick-id]

   The emitted event shapes/fields are IDENTICAL to what judge_runtime's
   pure builders produced before the async refactor — the consolidator,
   the :evaluation/judge-scores read-model, and the quality-report all
   read these and must not change."
  (:require [ai.obney.grain.event-store-v3.interface :as es :refer [->event]]
            [ai.obney.grain.command-processor-v2.interface :refer [defcommand]]))

;; =============================================================================
;; Auth
;; =============================================================================

(defn within-process?
  "Authorization predicate for the judge score-recording commands.

   These commands are dispatched in-process by the judge runtime's
   background future (never from an external/HTTP boundary), so a
   default-allow is correct here — matching how `execute-leaf-node`'s
   completion commands flow within the process. Grain's command-request
   handler is the boundary that enforces auth for externally-submitted
   commands; these are never submitted that way."
  [_ctx]
  true)

;; =============================================================================
;; Idempotency helpers
;; =============================================================================

(defn- same-judge-score?
  [sheet-id node-id tick-id judge-name event]
  (and (= sheet-id (:sheet-id event))
       (= node-id (:node-id event))
       (= tick-id (:tick-id event))
       (= judge-name (:judge-name event))))

(defn- same-composite-score?
  [sheet-id node-id tick-id event]
  (and (= sheet-id (:sheet-id event))
       (= node-id (:node-id event))
       (= tick-id (:tick-id event))))

(defn- existing-judge-score?
  "True when a :judge/score-emitted event already exists for the identity
   tuple [sheet-id node-id tick-id judge-name]. Reading the event store
   directly (rather than a read-model) makes the common duplicate path
   immediately visible. The command-result CAS below is the authoritative
   race fence when two writers both observe no prior event."
  [{:keys [event-store tenant-id]} sheet-id node-id tick-id judge-name]
  (boolean
    (some (partial same-judge-score?
                   sheet-id node-id tick-id judge-name)
          (into [] (es/read event-store {:types #{:judge/score-emitted}
                                         :tags #{[:tick tick-id]}
                                         :tenant-id tenant-id})))))

(defn- existing-composite-score?
  "True when a :judge/composite-score-computed event already exists for
   the identity tuple [sheet-id node-id tick-id].

   RR-23: scoped by the `[:tick tick-id]` tag `record-composite-score`
   already writes on every emitted event — was the last untagged
   full-type scan on this hot path (`existing-judge-score?` above was
   already tick-scoped); this duplicate check runs once per composite
   score dispatched, over the fastest-growing event type in the system."
  [{:keys [event-store tenant-id]} sheet-id node-id tick-id]
  (boolean
    (some (fn [e]
            (and (= sheet-id (:sheet-id e))
                 (= node-id (:node-id e))
                 (= tick-id (:tick-id e))))
          (into [] (es/read event-store {:types #{:judge/composite-score-computed}
                                         :tags #{[:tick tick-id]}
                                         :tenant-id tenant-id})))))

;; =============================================================================
;; Commands
;; =============================================================================

(defcommand :evaluation record-judge-score
  {:authorized? within-process?}
  "Record one judge's score for a (sheet, node, tick) by emitting a
   `:judge/score-emitted` event. The command body carries the exact
   fields the event shape requires. Idempotent on
   [sheet-id node-id tick-id judge-name] — a duplicate is a no-op
   (returns no events) rather than a double-emit.

   Emitted event shape is identical to the pre-async judge runtime's
   `->score-emitted-event` so downstream consumers are unchanged."
  [{{:keys [sheet-id node-id tick-id judge-name judge-config
            score feedback dimensions model-provenance emitted-at]} :command
    :as ctx}]
  (if (existing-judge-score? ctx sheet-id node-id tick-id judge-name)
    ;; Idempotent no-op: a score for this tuple already exists.
    {:command-result/events []}
    {:command-result/cas
     {:types #{:judge/score-emitted}
      :tags #{[:tick tick-id]}
      :predicate-fn
      (fn [existing]
        (not-any? (partial same-judge-score?
                           sheet-id node-id tick-id judge-name)
                  (into [] existing)))}
     :command-result/events
     [(->event
        {:type :judge/score-emitted
         :tags #{[:sheet sheet-id]
                 [:node node-id]
                 [:tick tick-id]}
         :body {:sheet-id sheet-id
                :tick-id tick-id
                :node-id node-id
                :judge-name judge-name
                :judge-config judge-config
                :score score
                :feedback feedback
                :dimensions dimensions
                :model-provenance model-provenance
                :emitted-at (or emitted-at (str (java.time.Instant/now)))}})]}))

(defcommand :evaluation record-composite-score
  {:authorized? within-process?}
  "Record the weighted composite across all judges that fired on a
   (sheet, node, tick) by emitting a `:judge/composite-score-computed`
   event. Idempotent on [sheet-id node-id tick-id].

   Emitted event shape is identical to the pre-async judge runtime's
   `->composite-score-event`."
  [{{:keys [sheet-id node-id tick-id composite-score
            contributing-judges emitted-at]} :command
    :as ctx}]
  (if (existing-composite-score? ctx sheet-id node-id tick-id)
    ;; Idempotent no-op: a composite for this tuple already exists.
    {:command-result/events []}
    ;; The read above cannot fence a race: judge dispatch runs one future per
    ;; judge and the processor path is at-least-once, so two deliveries can both
    ;; read before either appends. The CAS re-checks at append time, which is the
    ;; only place OneCompositePerCompletion can actually be enforced — the same
    ;; fence `record-judge-score` uses above.
    {:command-result/cas
     {:types #{:judge/composite-score-computed}
      :tags #{[:tick tick-id]}
      :predicate-fn
      (fn [existing]
        (not-any? (partial same-composite-score? sheet-id node-id tick-id)
                  (into [] existing)))}
     :command-result/events
     [(->event
        {:type :judge/composite-score-computed
         :tags #{[:sheet sheet-id]
                 [:node node-id]
                 [:tick tick-id]}
         :body {:sheet-id sheet-id
                :tick-id tick-id
                :node-id node-id
                :composite-score composite-score
                :contributing-judges contributing-judges
                :emitted-at (or emitted-at (str (java.time.Instant/now)))}})]}))
