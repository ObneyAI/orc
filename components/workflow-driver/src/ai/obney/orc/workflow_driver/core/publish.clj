(ns ai.obney.orc.workflow-driver.core.publish
  "Publish / revert with pre-publish guards.

   `publish!` runs the eval-set on the current draft, refuses to
   promote if the pass-rate falls below the configured threshold,
   otherwise issues `:sheet/publish-version` to grain. The agent
   cannot ship a Sheet that fails its own smoke gate.

   `revert!` wraps `:sheet/revert-to-version` — the dirty draft is
   stashed automatically by the command handler so an over-eager
   revert can be undone."
  (:require [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.workflow-driver.core.eval-set :as eval-set]))

(defn- anomaly?
  [result]
  (contains? result :cognitect.anomalies/category))

(defn- extract-version-number
  [cmd-result]
  (some-> cmd-result :command-result/events first :version-number))

(defn- extract-snapshot-id
  [cmd-result]
  (some-> cmd-result :command-result/events first :snapshot-id))

(defn publish!
  "Promote the current draft of `sheet-id` to a new published version,
   gated by the eval-set's pass-rate AND (optionally) judge score.

   Pipeline:
     1. Run the eval-set against the current draft (with judges if requested)
     2. If pass-rate < `min-pass-rate`, return :refused
     3. If `min-judge-score` is set and avg-judge-score < threshold,
        return :refused
     4. Otherwise issue `:sheet/publish-version`, return the new
        version-number plus the eval report

   Options:
     :min-pass-rate    pass-rate threshold (default 1.0)
     :min-judge-score  judge score threshold 0..1 (optional — only
                       checked when `:judges` is provided)
     :judges           judge keys passed through to run-eval-set!
                       (e.g. [:grounding :completeness])
     :description      optional description tag on the new version
     :timeout-ms       per-eval-item tick timeout (default 60000)

   Returns:
     {:status :ok :version-number n :snapshot-id uuid :eval <report>}
     {:status :refused :reason :pass-rate-below-threshold | :judge-score-below-threshold
                       :pass-rate p :avg-judge-score s :eval <report>}
     {:status :error :error <msg> :eval <report-or-nil>}"
  [ctx sheet-id eval-set-data & [{:keys [min-pass-rate min-judge-score
                                         judges description timeout-ms]
                                  :or {min-pass-rate 1.0
                                       judges []
                                       timeout-ms 60000}}]]
  (let [report (eval-set/run-eval-set! ctx sheet-id eval-set-data
                 {:timeout-ms timeout-ms
                  :judges judges})]
    (cond
      (< (:pass-rate report) min-pass-rate)
      {:status :refused
       :reason :pass-rate-below-threshold
       :pass-rate (:pass-rate report)
       :min-pass-rate min-pass-rate
       :eval report}

      (and min-judge-score
           (some? (:avg-judge-score report))
           (< (:avg-judge-score report) min-judge-score))
      {:status :refused
       :reason :judge-score-below-threshold
       :avg-judge-score (:avg-judge-score report)
       :min-judge-score min-judge-score
       :eval report}

      :else
      (let [cmd-result (orc/publish-version! ctx sheet-id :description description)]
        (if (anomaly? cmd-result)
          {:status :error
           :error (:cognitect.anomalies/message cmd-result)
           :eval report}
          {:status :ok
           :version-number (extract-version-number cmd-result)
           :snapshot-id (extract-snapshot-id cmd-result)
           :eval report})))))

(defn revert!
  "Discard the current draft and restore Sheet state from
   `version-number`. The dirty draft is stashed by grain — call
   `orc/restore-stash!` to undo this operation if needed.

   Returns {:status :ok :reverted-to version-number}
        or {:status :error :error <msg>}."
  [ctx sheet-id version-number]
  (let [cmd-result (orc/revert-to-version! ctx sheet-id version-number)]
    (if (anomaly? cmd-result)
      {:status :error :error (:cognitect.anomalies/message cmd-result)}
      {:status :ok :reverted-to version-number})))

(defn commit-version!
  "Publish the current draft as a new version WITHOUT running the
   eval-set first. Use when the caller has already evaluated the
   draft and wants to skip a redundant (and variance-prone) re-eval.

   The driver loop uses this on its `:publish` decision since it has
   just eval'd the same draft. `publish!` is the right entry point
   for ad-hoc publish-with-gate flows.

   Returns:
     {:status :ok :version-number n :snapshot-id uuid}
     {:status :error :error <msg>}"
  [ctx sheet-id & [{:keys [description]}]]
  (let [cmd-result (orc/publish-version! ctx sheet-id :description description)]
    (if (anomaly? cmd-result)
      {:status :error :error (:cognitect.anomalies/message cmd-result)}
      {:status :ok
       :version-number (extract-version-number cmd-result)
       :snapshot-id (extract-snapshot-id cmd-result)})))
