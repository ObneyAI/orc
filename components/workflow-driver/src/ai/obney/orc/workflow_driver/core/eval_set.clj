(ns ai.obney.orc.workflow-driver.core.eval-set
  "Eval-set primitive: a held-out collection of inputs the driver runs
   the target Sheet against to measure improvement turn over turn.

   An eval-set is just data — a vector of `{:name … :inputs {…}}` items.
   Run it via `run-eval-set!` which calls `orc/execute` per item,
   collects per-tick statuses, and (optionally) runs judges over each
   tick's trace to produce a richer score.

   Pass = `:status :success`. When `:judges` is supplied, the report
   also carries per-tick judge scores and an aggregate score the
   driver loop uses to gate `publish!`."
  (:require [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.workflow-driver.core.judge :as judge]))

;; =============================================================================
;; Eval-set schema
;; =============================================================================

;; An eval-set item shape:
;;   {:name      <string, optional> ; human label for traces
;;    :inputs    <map of blackboard-key -> value>
;;    :expected  <map, optional>    ; for future expected-output diffing
;;
;; An eval-set is `[item …]`. Persist later when the driver session
;; itself becomes a first-class entity.

(defn run-eval-set!
  "Execute a Sheet against every item in an eval-set, sequentially.

   Options:
     :timeout-ms    per-item tick timeout (default 60000)
     :use-version   pin to a specific published version number
                    (default — execute the current draft)
     :judges        optional vector of judge keys to run post-hoc
                    against each successful tick's trace
                    (e.g. [:grounding :completeness])

   Returns:
     {:total           <int>
      :pass-count      <int>
      :fail-count      <int>
      :pass-rate       <0..1>
      :avg-judge-score <0..1 or nil>   ; mean across judged ticks; nil if :judges empty
      :results [{:name … :inputs … :status … :duration-ms …
                 :outputs … :error … :tick-id … :trace-id …
                 :judge {:avg-score 0..1
                         :node-scores [{:node-name … :scores {…} :avg 0..1
                                         :feedback {…}} …]}} …]}"
  [ctx sheet-id eval-set & [{:keys [timeout-ms use-version judges]
                             :or {timeout-ms 60000
                                  judges []}}]]
  (let [results
        (mapv
          (fn [{:keys [name inputs] :as _item}]
            (let [result (orc/execute ctx sheet-id (or inputs {})
                           :timeout-ms timeout-ms
                           :use-version use-version)
                  base (cond-> {:name name
                                :inputs (or inputs {})
                                :status (:status result)
                                :duration-ms (:duration-ms result)}
                         (:outputs result) (assoc :outputs (:outputs result))
                         (:error result) (assoc :error (:error result))
                         (:tick-id result) (assoc :tick-id (:tick-id result))
                         (:trace-id result) (assoc :trace-id (:trace-id result))
                         (:executed-version result)
                         (assoc :executed-version (:executed-version result)))
                  ;; Run judges only if requested AND tick succeeded
                  judge-report (when (and (seq judges)
                                          (= :success (:status result))
                                          (:trace-id result))
                                 (judge/judge-tick! ctx (:trace-id result) judges))]
              (cond-> base
                judge-report (assoc :judge judge-report))))
          eval-set)
        total (count results)
        pass-count (count (filter #(= :success (:status %)) results))
        fail-count (- total pass-count)
        judged-scores (keep #(get-in % [:judge :avg-score]) results)
        avg-judge-score (when (seq judged-scores)
                          (/ (reduce + judged-scores) (double (count judged-scores))))]
    {:total total
     :pass-count pass-count
     :fail-count fail-count
     :pass-rate (if (zero? total) 0.0 (double (/ pass-count total)))
     :avg-judge-score avg-judge-score
     :results results}))
