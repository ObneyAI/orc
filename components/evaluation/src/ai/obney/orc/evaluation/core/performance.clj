(ns ai.obney.orc.evaluation.core.performance
  "Performance of a node VERSION under a judge revision, built from assessment
   outcomes (PerformanceMonitoring). It is always on and incremental: one read
   model folds each request and each terminal outcome into a small cell keyed
   `[sheet node node-version judge revision]`, so a query never rescans events and
   a fresh rebuild reproduces it.

   A cell counts what became of the assessments: scored, failed, ungradable, and
   pending (requested, no outcome yet). An ungradable outcome whose reason is
   :subject-failed (the assessed execution itself failed, so nothing was judged)
   is also counted apart as `:subject-failed`: it is an infrastructure signal,
   never a quality one, and it is in no mean, trailing mean or band distribution.
   COVERAGE is the share of them that were scored; a grade is never reported
   without it. Versions of a node are reported
   separately and never blended, with a rollup across versions beside them.

   The trailing mean is the mean of the last N SCORED outcomes (N = `default-window`
   unless asked otherwise). A cell keeps its most recent `max-retained` outcomes in
   order for trend queries and trailing windows; the counts, band distribution and
   mean are exact over the cell's whole life."
  (:require [clj-uuid :as uuid]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]
            [ai.obney.orc.evaluation.core.assessments :as assessments]
            [ai.obney.orc.orc-service.interface :as orc]))

(def default-window "Outcomes in a trailing window unless a query says otherwise." 20)

(def max-retained
  "Most recent outcomes a cell keeps in order; the largest window that can be asked of
   it, and so the largest an alert may declare."
  orc/max-alert-window)

;; =============================================================================
;; Read model
;; =============================================================================

(defn cell-key [{:keys [sheet-id node-id node-version judge-name judge-revision-number]}]
  [sheet-id node-id node-version judge-name judge-revision-number])

(defn cell-id
  "A stable UUID naming a cell (used to tag its alert signals)."
  [cell-key]
  (uuid/v5 #uuid "a41f9d52-6b0e-5b0a-9c8e-0d2b7f7a4c11" (pr-str cell-key)))

(defn- empty-cell [k first-seen]
  (let [[sheet-id node-id node-version judge-name revision] k]
    {:sheet-id sheet-id :node-id node-id :node-version node-version
     :judge-name judge-name :judge-revision-number revision
     :pending 0 :scored 0 :failed 0 :ungradable 0 :subject-failed 0
     :score-sum 0.0 :bands {} :series []
     :first-seen first-seen :last-seen first-seen}))

(defmulti performance*
  "Fold one assessment lifecycle event into `{:index {assessment-id {:cell :settled?}}
   :cells {cell-key cell}}`."
  (fn [_state event] (:event/type event)))

(defmethod performance* :default [state _] state)

(defmethod performance* :evaluation/assessment-requested
  [state event]
  (let [id (:assessment-id event)
        k (cell-key event)]
    (if (contains? (:index state) id)
      state
      (-> state
          (assoc-in [:index id] {:cell k :settled? false})
          (update-in [:cells k] #(or % (cond-> (empty-cell k (:requested-at event))
                                    (:version-number event) (assoc :version-number (:version-number event)))))
          (update-in [:cells k :pending] inc)))))

(defn- settle-cell [cell status event]
  (let [score (:score event)]
    (-> cell
        (update :pending dec)
        (update status inc)
        ;; An execution that failed is recorded ungradable (:subject-failed); it
        ;; stays among the ungradable and is counted apart, never as quality.
        (cond-> (= :subject-failed (:reason event)) (update :subject-failed (fnil inc 0)))
        (assoc :last-seen (str (:event/timestamp event)))
        (cond->
         (= :scored status) (update :score-sum + score)
         (and (= :scored status) (some? (:band event))) (update-in [:bands (:band event)] (fnil inc 0)))
        (update :series (fn [series]
                          (let [series (conj series (cond-> {:assessment-id (:assessment-id event)
                                                             :status status
                                                             :at (str (:event/timestamp event))}
                                                      (= :scored status) (assoc :score score)
                                                      (= :subject-failed (:reason event))
                                                      (assoc :reason :subject-failed)
                                                      (some? (:band event)) (assoc :band (:band event))))]
                            (if (> (count series) max-retained)
                              (subvec series (- (count series) max-retained))
                              series)))))))

(defn- settle [state event]
  (let [id (:assessment-id event)
        {:keys [cell settled?]} (get-in state [:index id])]
    ;; An outcome for an assessment never requested here, or already settled, is
    ;; not counted again.
    (if (or (nil? cell) settled?)
      state
      (-> state
          (assoc-in [:index id :settled?] true)
          (update-in [:cells cell] settle-cell (assessments/terminal-status (:event/type event)) event)))))

(doseq [t assessments/terminal-event-types]
  (defmethod performance* t [state event] (settle state event)))

(defreadmodel :evaluation performance
  {:events assessments/lifecycle-event-types :version 3}
  [state event] (performance* state event))

;; =============================================================================
;; Pure statistics
;; =============================================================================

(defn- mean [xs] (when (seq xs) (/ (reduce + 0.0 xs) (count xs))))

(defn trailing-scores
  "The scores of the last `window` scored outcomes of `series`, oldest first."
  [series window]
  (->> series (filter #(= :scored (:status %))) (keep :score) (take-last window)))

(defn stats
  "The reported statistics of a cell-shaped map (a cell or a merge of cells)."
  [{:keys [scored failed ungradable pending score-sum bands series] :as cell} window]
  (let [total (+ scored failed ungradable pending)
        subject-failed (or (:subject-failed cell) 0)]
    (-> (select-keys cell [:sheet-id :node-id :node-version :judge-name :judge-revision-number
                           :first-seen :last-seen])
        (assoc :scored scored :failed failed :ungradable ungradable :pending pending
               :subject-failed subject-failed
               :total total
               :coverage (if (pos? total) (/ (double scored) total) 0.0)
               :mean-score (when (pos? scored) (/ score-sum scored))
               :trailing-mean (mean (trailing-scores series window))
               :band-distribution bands))))

(defn- merge-cells
  "One cell standing for several (the same judge and revision across versions)."
  [cells]
  (let [base (-> (first cells) (dissoc :node-version))]
    (reduce (fn [acc c]
              (-> acc
                  (update :pending + (:pending c))
                  (update :scored + (:scored c))
                  (update :failed + (:failed c))
                  (update :ungradable + (:ungradable c))
                  (update :subject-failed (fnil + 0) (or (:subject-failed c) 0))
                  (update :score-sum + (:score-sum c))
                  (update :bands #(merge-with + % (:bands c)))
                  (update :series into (:series c))
                  (update :first-seen #(first (sort [% (:first-seen c)])))
                  (update :last-seen #(last (sort [% (:last-seen c)])))))
            (update base :series vec)
            (rest cells))))

(defn- chronological [cell] (update cell :series #(vec (sort-by :at %))))

;; =============================================================================
;; Queries
;; =============================================================================

(defn- cells [ctx]
  (vals (:cells (rmp/project ctx :evaluation/performance))))

(defn- matching [{:keys [sheet-id node-id node-version judge-name]} cells]
  (filter (fn [c] (and (or (nil? sheet-id) (= sheet-id (:sheet-id c)))
                       (or (nil? node-id) (= node-id (:node-id c)))
                       (or (nil? node-version) (= node-version (:node-version c)))
                       (or (nil? judge-name) (= judge-name (:judge-name c)))))
          cells))

(defn cell
  "The cell of `cell-key` (see `cell-key`), or nil."
  [ctx cell-key]
  (get-in (rmp/project ctx :evaluation/performance) [:cells cell-key]))

(defn get-node-performance
  "The performance of a node, version by version. `{:sheet-id :node-id}` are
   required; `:node-version` and `:judge-name` narrow it. Returns
   `{:sheet-id :node-id :versions [{:node-version :first-seen :last-seen :judges [stats]}]
     :rollup [stats]}`: the versions oldest first, each with one stats entry per judge
   and judge revision, and the rollup merging each judge revision across the listed
   versions. Stats are described in `stats`; `:window` sets the trailing window."
  [ctx {:keys [sheet-id node-id window] :as query}]
  (let [window (or window default-window)
        cs (matching query (cells ctx))
        judge-order (juxt :judge-name :judge-revision-number)
        versions (->> cs
                      (group-by :node-version)
                      (map (fn [[v group]]
                             {:node-version v
                              :version-number (:version-number (first group))
                              :first-seen (first (sort (map :first-seen group)))
                              :last-seen (last (sort (map :last-seen group)))
                              :judges (->> (sort-by judge-order group)
                                           (mapv #(stats % window)))}))
                      (sort-by (juxt :first-seen (comp str :node-version)))
                      vec)
        rollup (->> cs
                    (map chronological)
                    (group-by judge-order)
                    (sort-by key)
                    (mapv (fn [[_ group]] (stats (merge-cells group) window))))]
    {:sheet-id sheet-id :node-id node-id :versions versions :rollup rollup}))

(defn get-low-performing
  "The node versions performing below `:below` (required): each judge revision of
   each node version whose trailing mean is under it, worst first. `:sheet-id`
   narrows the search; `:min-coverage` (default 0) leaves out cells whose coverage
   is lower, since a thin grade is not evidence of poor performance."
  [ctx {:keys [below min-coverage window] :as query}]
  (let [window (or window default-window)
        min-coverage (or min-coverage 0.0)]
    (->> (matching (select-keys query [:sheet-id]) (cells ctx))
         (map #(stats % window))
         (filter (fn [s] (and (some? (:trailing-mean s))
                              (< (:trailing-mean s) below)
                              (>= (:coverage s) min-coverage))))
         (sort-by (juxt :trailing-mean :judge-name))
         vec)))

(defn get-performance-trend
  "The ordered outcomes of a node's judge, oldest first: the most recent `:window`
   (default `default-window`) of `{:assessment-id :status :score :band :at
   :node-version :judge-revision-number}`. `:sheet-id :node-id :judge-name` are
   required; `:node-version` narrows to one version."
  [ctx {:keys [window] :as query}]
  (let [window (or window default-window)]
    (->> (matching query (cells ctx))
         (mapcat (fn [c] (map #(assoc % :node-version (:node-version c)
                                      :judge-revision-number (:judge-revision-number c))
                              (:series c))))
         (sort-by (juxt :at (comp str :assessment-id)))
         (take-last window)
         vec)))

(defn get-assessment-report
  "Coverage and grades over an explicit set of assessments, named by
   `{:assessment-ids [...]}` or `{:subject-ids [...]}` (the completions assessed).
   Separated by judge and judge revision, each with its counts, coverage, band
   counts and the mean of the scored. An id naming no assessment is simply absent
   from the report."
  [ctx {:keys [assessment-ids subject-ids]}]
  (let [wanted-a (set assessment-ids)
        wanted-s (set subject-ids)
        entries (filter (fn [e] (or (contains? wanted-a (:assessment-id e))
                                    (contains? wanted-s (:subject-completion-id e))))
                        (vals (rmp/project ctx :evaluation/assessments)))]
    (->> entries
         (group-by (juxt :judge-name :judge-revision-number))
         (sort-by key)
         (mapv (fn [[[judge-name revision] group]]
                 (let [by (group-by :status group)
                       scored (get by :scored)
                       total (count group)]
                   {:judge-name judge-name
                    :judge-revision-number revision
                    :total total
                    :scored (count scored)
                    :failed (count (get by :failed))
                    :ungradable (count (get by :ungradable))
                    :pending (count (get by :pending))
                    :coverage (/ (double (count scored)) total)
                    :mean-score (mean (keep :score scored))
                    :band-distribution (frequencies (keep :band scored))}))))))
