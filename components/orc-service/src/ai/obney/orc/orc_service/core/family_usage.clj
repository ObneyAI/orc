(ns ai.obney.orc.orc-service.core.family-usage
  "Provider usage of an execution together with all of its descendants,
   derived from durable completion events (FamilyUsageIsQueryable)."
  (:require [ai.obney.grain.event-store-v3.interface :as es]))

(defn tick-family-ids
  "Return `tick-id` and every transitively nested child tick.

   Child starts carry a [:parent-tick parent-id] tag, so this walk is bounded
   by the execution tree. It never scans unrelated tenant history."
  [event-store tenant-id tick-id]
  (loop [seen #{tick-id}
         frontier [tick-id]]
    (if (empty? frontier)
      seen
      (let [children
            (into #{}
                  (mapcat (fn [parent-id]
                            (into []
                                  (comp (map :tick-id)
                                        (remove seen))
                                  (es/read event-store
                                           (cond-> {:types #{:sheet/tree-tick-started}
                                                    :tags #{[:parent-tick parent-id]}}
                                             tenant-id (assoc :tenant-id tenant-id))))))
                  frontier)]
        (recur (into seen children) (vec children))))))

(defn- usage-number [usage & ks]
  (some (fn [k] (let [v (get usage k)] (when (number? v) v))) ks))

(defn get-family-usage
  "Total provider usage for `trace-id` and all descendant executions:
   {:prompt-tokens :completion-tokens :total-tokens} plus :cost when any
   usage carries one. Each node completion contributes its :own-usage when present (a completion
   whose :usage folds in generated child ticks), otherwise its top-level :usage;
   the :by-node breakdown is a view of the same tokens, never added again."
  [{:keys [event-store tenant-id]} trace-id]
  (let [events (into []
                     (mapcat (fn [tick-id]
                               (into []
                                     (es/read event-store
                                              (cond-> {:types #{:sheet/node-execution-completed}
                                                       :tags #{[:tick tick-id]}}
                                                tenant-id (assoc :tenant-id tenant-id))))))
                     (tick-family-ids event-store tenant-id trace-id))
        usages (keep #(or (:own-usage %) (:usage %)) events)
        total (fn [& ks] (reduce + 0 (keep #(apply usage-number % ks) usages)))
        costs (keep #(usage-number % :cost) usages)]
    (cond-> {:prompt-tokens (total :prompt-tokens :prompt_tokens)
             :completion-tokens (total :completion-tokens :completion_tokens)
             :total-tokens (total :total-tokens :total_tokens)}
      (seq costs) (assoc :cost (reduce + 0 costs)))))
