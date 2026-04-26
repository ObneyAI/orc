(ns ai.obney.orc.workflow-driver.core.judge
  "Post-hoc judge evaluation: after a tick completes, fetch its
   stored trace, extract the LLM-node trace records, and run the
   requested judges against each. Returns per-trace + aggregate
   scores the driver loop uses to gate `publish!`.

   Each judge call hits the LLM, so judging is opt-in (driver clients
   pass `:judges []` to skip). Default judge model is the same as the
   sheet's nodes."
  (:require [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.evaluation.interface :as eval]))

(defn- wait-for-trace
  "Poll the trace store until the trace assembly completes, or give up.
   Trace assembly runs concurrently with execution; small ticks land in
   < 100ms but heavy ones can take longer."
  [ctx trace-id {:keys [max-attempts delay-ms]
                 :or {max-attempts 50 delay-ms 50}}]
  (loop [attempt 0]
    (let [trace (orc/get-trace ctx trace-id)]
      (cond
        (some? trace) trace
        (>= attempt max-attempts) nil
        :else (do (Thread/sleep delay-ms)
                  (recur (inc attempt)))))))

(defn- llm-node-traces
  "Filter a trace's :node-traces to LLM-leaf traces. The trace records
   carry :inputs / :outputs / :node-id but NOT :executor / :instruction —
   those have to be looked up against the live node config from the
   read-model, which is what `enrich-with-live-node` does below.

   We keep all leaf node-traces here; the enrichment step drops any
   that aren't `:executor :ai` after lookup."
  [trace]
  (filter #(= :leaf (:node-type %)) (:node-traces trace)))

(defn- enrich-with-live-node
  "Look up the live node by :node-id and return a merged map carrying
   the trace's runtime data plus the live node's :executor and
   :instruction. Returns nil if the live node isn't an LLM node."
  [ctx sheet-id node-trace]
  (let [live (orc/get-node ctx sheet-id (:node-id node-trace))]
    (when (= :ai (:executor live))
      (assoc node-trace
             :executor :ai
             :instruction (:instruction live)
             :model (:model live)))))

(defn- format-trace-for-judges
  "Shape a single enriched LLM node trace into the {:inputs :outputs
   :instruction} map evaluation/evaluate-single expects."
  [node-trace]
  {:inputs (:inputs node-trace)
   :outputs (:outputs node-trace)
   :response (:outputs node-trace)
   :instruction (:instruction node-trace)})

(defn judge-tick!
  "Run the requested judges against every LLM-node trace in `trace-id`.

   Args:
     ctx        — orc context (event-store + tenant-id)
     trace-id   — uuid of the trace to evaluate (from orc/execute :trace-id)
     judge-keys — vector of judge-key keywords:
                  [:grounding :instruction-following :reasoning :completeness]

   Returns:
     {:trace-id … :ready? bool
      :node-scores  [{:node-name … :scores {<judge-key> 0..1 …} :avg 0..1
                      :feedback {<judge-key> …}} …]
      :avg-score    0..1 (mean of all per-node averages)
      :error        message  ;; only if :ready? false}"
  [ctx trace-id judge-keys]
  (let [trace (wait-for-trace ctx trace-id {})]
    (cond
      (nil? trace)
      {:trace-id trace-id :ready? false :error "trace not stored within timeout"}

      (empty? judge-keys)
      {:trace-id trace-id :ready? true :node-scores [] :avg-score nil}

      :else
      (let [sheet-id (:sheet-id trace)
            llm-traces (->> (llm-node-traces trace)
                            (keep #(enrich-with-live-node ctx sheet-id %)))
            node-scores
            (mapv
              (fn [nt]
                (let [td (format-trace-for-judges nt)
                      results
                      (reduce
                        (fn [acc jk]
                          (try
                            (let [r (eval/evaluate-single jk td)
                                  ;; evaluate-single returns the judge fn's
                                  ;; raw output map; the score lives under
                                  ;; either :score or :<judge-key>-result.
                                  score (or (:score r)
                                            (some-> (get r (keyword (str (name jk) "-result")))
                                                    :score)
                                            0.0)
                                  feedback (or (:feedback r)
                                               (some-> (get r (keyword (str (name jk) "-result")))
                                                       :feedback)
                                               "")]
                              (-> acc
                                  (assoc-in [:scores jk] score)
                                  (assoc-in [:feedback jk] feedback)))
                            (catch Exception e
                              (-> acc
                                  (assoc-in [:scores jk] 0.0)
                                  (assoc-in [:feedback jk]
                                            (str "judge error: " (.getMessage e)))))))
                        {:scores {} :feedback {}}
                        judge-keys)
                    avg (when (seq (:scores results))
                          (/ (reduce + (vals (:scores results)))
                             (double (count (:scores results)))))]
                  (assoc results
                         :node-name (:node-name nt)
                         :node-id (:node-id nt)
                         :avg avg)))
              llm-traces)
            avg-score (when (seq node-scores)
                        (/ (reduce + (keep :avg node-scores))
                           (double (count node-scores))))]
        {:trace-id trace-id
         :ready? true
         :node-scores node-scores
         :avg-score avg-score}))))
