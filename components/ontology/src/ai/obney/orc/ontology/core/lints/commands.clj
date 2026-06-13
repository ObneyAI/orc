(ns ai.obney.orc.ontology.core.lints.commands
  "S10 — Lint registry commands.

   Two commands:

   - `register-shape` — Malli-validates the EDN shape body (rejecting
     malformed shapes at registration time), emits a
     `:ontology/shape-registered` event. The in-process `:code` fn (if
     supplied) is stripped from the event body — it's not
     EDN-serializable. A persisted shape MUST use `:code-symbol` to
     reach its predicate; an in-process registration may use either,
     and the interpreter's `resolve-code-fn` prefers `:code` when both
     are present.

   - `run-validation` — loads the registered shapes for the
     `ontology-id`, loads the concepts projection for the same
     ontology-id, walks shapes × concepts through the interpreter, and
     emits one event per violation OR skip — all tagged with a shared
     `run-id` so the validation-report read-model can collect them as
     one logical run.

   Both commands flow through grain's command-processor — no bare
   `es/append` calls; reading + writing are evented (S10 disciplines
   §7)."
  (:require [ai.obney.orc.ontology.core.lints.interpreter :as interpreter]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.event-store-v3.interface :refer [->event]]
            [ai.obney.grain.command-processor-v2.interface :refer [defcommand]]
            [ai.obney.grain.time.interface :as time]
            [malli.core :as m]
            [malli.error :as me]
            [cognitect.anomalies :as anom]))

(defn- now-str []
  (str (time/now)))

(defn- generate-uuid []
  (random-uuid))

;; The shape's :code fn isn't EDN-serializable; strip it before storing
;; in the event body. The :code-symbol path is the canonical persisted
;; way to wire a code-predicate.
(defn- strip-non-serializable
  [shape]
  (dissoc shape :code))

;; =============================================================================
;; register-shape
;; =============================================================================

(defcommand :ontology register-shape
  "Register a SHACL-shaped EDN lint shape against an ontology-id.

   The shape format is documented on `:ontology/register-shape` in the
   schemas namespace (Malli-validated). Phase-1 supported components:
   target-class, severity, message, deactivated, property[path/min-
   count/max-count/not{:object-exists?}], code (or code-symbol).

   The Malli :fn guard enforces that a shape has AT LEAST one of
   :property / :code / :code-symbol — a shape with no predicate would
   silently pass everything, which is the adversarial failure mode.

   IMPORTANT: this handler ALSO runs the schema check explicitly. The
   process-command pipeline would catch malformed commands BEFORE
   dispatching here, but the test suite and direct-call paths invoke the
   handler fn directly — so a defensive in-handler validate keeps the
   rejection invariant true regardless of caller surface (S10
   disciplines §4: false-green prevention)."
  [{{:keys [ontology-id shape] :as cmd} :command}]
  (if-not (m/validate :ontology/register-shape cmd)
    {::anom/category ::anom/incorrect
     ::anom/message  "Invalid lint shape — register-shape command failed Malli validation"
     :error/explain  (me/humanize (m/explain :ontology/register-shape cmd))}
    (let [stripped (strip-non-serializable shape)
          code-sym (:code-symbol shape)]
      {:command-result/events
       [(->event
         {:type :ontology/shape-registered
          :tags #{[:ontology ontology-id]}
          :body (cond-> {:ontology-id   ontology-id
                         :shape-id      (:shape/id shape)
                         :shape-body    stripped
                         :registered-at (now-str)}
                  code-sym (assoc :code-symbol code-sym))})]})))

;; =============================================================================
;; run-validation
;; =============================================================================

(defn- hydrate-shape
  "Reconstitute a shape-map ready to feed the interpreter from the
   shape-registry projection entry. The :code-symbol survives EDN
   round-trip; the interpreter's resolve-code-fn handles
   requiring-resolve on demand."
  [{:keys [shape-body code-symbol]}]
  (cond-> shape-body
    code-symbol (assoc :code-symbol code-symbol)))

(defn- concepts-for-ontology
  "Return the seq of concept maps in the URI-keyed projection that
   belong to `ontology-id`. We load the URI-keyed projection (vs the
   section-keyed one) because relationships have already been merged
   onto the concept maps as :broader/:narrower/:related sets — exactly
   what the interpreter consumes. We filter by :ontology-id on the way
   in so a multi-tenant store doesn't leak."
  [ctx ontology-id]
  (let [all (rmp/project ctx :ontology/concepts)]
    (->> all
         vals
         (filter #(= ontology-id (:ontology-id %)))
         (reduce (fn [acc c] (assoc acc (:uri c) c)) {}))))

(defn- relationships-for-ontology
  "Return the seq of relationship records belonging to `ontology-id`.
   S11 axiom-consuming lints (functional double-value) read this to
   count edges per (source-uri, predicate)."
  [ctx ontology-id]
  (let [all (rmp/project ctx :ontology/relationships)]
    (->> all
         vals
         (filter #(= ontology-id (:ontology-id %))))))

(defn- axioms-for-ontology
  "Return the axiom submap for `ontology-id` (the union of disjointness,
   characteristics, inverse-of, sub-property-of, chains) or nil when
   no axioms have been asserted."
  [ctx ontology-id]
  (get (rmp/project ctx :ontology/axioms) ontology-id))

(defcommand :ontology run-validation
  "Run all registered shapes for the ontology-id against the projected
   concept graph; emit one event per violation OR skip.

   Returns an anomaly if no shapes are registered — silently reporting
   'no violations' on an empty registry would be a false-negative
   failure mode (S10 disciplines §2 adversarial review)."
  [{{:keys [ontology-id]} :command :as ctx}]
  (let [registry (or (get (rmp/project ctx :ontology/shape-registry) ontology-id) {})
        shapes (mapv hydrate-shape (vals registry))]
    (if (empty? shapes)
      {::anom/category ::anom/not-found
       ::anom/message (str "No shapes registered for ontology-id " ontology-id)}
      (let [graph (concepts-for-ontology ctx ontology-id)
            axioms (axioms-for-ontology ctx ontology-id)
            relationships (relationships-for-ontology ctx ontology-id)
            interp-ctx {:graph graph
                        :axioms axioms
                        :relationships relationships}
            {:keys [violations skips]} (interpreter/run-registry interp-ctx shapes)
            run-id (generate-uuid)
            now (now-str)
            violation-events
            (mapv (fn [v]
                    (->event
                     {:type :ontology/lint-violation
                      :tags #{[:ontology ontology-id]}
                      :body {:violation-id  (generate-uuid)
                             :ontology-id   ontology-id
                             :shape-id      (:shape-id v)
                             :severity      (:severity v)
                             :message       (:message v)
                             :offending-uri (:offending-uri v)
                             :reason        (:reason v)
                             :detail        (:detail v)
                             :run-id        run-id
                             :detected-at   now}}))
                  violations)
            skip-events
            (mapv (fn [s]
                    (->event
                     {:type :ontology/lint-shape-skipped
                      :tags #{[:ontology ontology-id]}
                      :body {:skip-id     (generate-uuid)
                             :ontology-id ontology-id
                             :shape-id    (:shape-id s)
                             :reason      (:reason s)
                             :run-id      run-id
                             :detected-at now}}))
                  skips)]
        {:command-result/events (vec (concat violation-events skip-events))
         :command-result/value  {:run-id     run-id
                                 :violations (count violations)
                                 :skips      (count skips)}}))))
