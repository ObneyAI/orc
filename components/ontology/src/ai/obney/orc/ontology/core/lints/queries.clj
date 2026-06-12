(ns ai.obney.orc.ontology.core.lints.queries
  "S10 — Lint registry queries.

   - `get-validation-report` — current (latest-run) validation snapshot
     for an ontology-id
   - `get-violation-history` — every violation event for an ontology-id,
     optionally filtered by shape-id and/or a time window
   - `get-registered-shapes` — the active shape-registry slice for an
     ontology-id"
  (:require [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.query-processor.interface :refer [defquery]]))

(defn- within-window? [evt since until]
  (let [at (:detected-at evt)]
    (and (or (nil? since) (>= (compare at since) 0))
         (or (nil? until) (<= (compare at until) 0)))))

(defquery :ontology get-validation-report
  "Return the latest validation snapshot for an ontology-id —
   `{:run-id :detected-at :violations [...] :skips [...]}`.
   Returns `{:run-id nil :violations [] :skips []}` when no run has
   executed yet (preserves the snapshot shape — the empty answer is
   data, not an anomaly)."
  [{{:keys [ontology-id]} :query :as ctx}]
  (let [reports (rmp/project ctx :ontology/validation-report)
        result (or (get reports ontology-id)
                   {:run-id nil :detected-at nil :violations [] :skips []})]
    {:query/result result}))

(defquery :ontology get-violation-history
  "Return ALL lint-violation events recorded for an ontology-id,
   chronological. Optional `:shape-id` narrows by lint identity.
   Optional `:since` / `:until` (RFC-3339 strings) bound the time
   window inclusively."
  [{{:keys [ontology-id shape-id since until]} :query :as ctx}]
  (let [hist (rmp/project ctx :ontology/violation-history)
        all (or (get hist ontology-id) [])
        filtered (cond->> all
                   shape-id (filter #(= shape-id (:shape-id %)))
                   (or since until) (filter #(within-window? % since until)))]
    {:query/result (vec filtered)}))

(defquery :ontology get-registered-shapes
  "Return the active shape-registry slice for an ontology-id —
   `{shape-id {:shape-body :registered-at :code-symbol?}}`."
  [{{:keys [ontology-id]} :query :as ctx}]
  (let [reg (rmp/project ctx :ontology/shape-registry)]
    {:query/result (or (get reg ontology-id) {})}))
