(ns ai.obney.orc.orc-service.core.budget-facts
  "Dedicated family index for provider admission; ordinary tick tags stay local."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.orc-service.core.researcher-effects :as effects]))

(def accounting-version 1)

(defn- first-start [context tick-id]
  (when tick-id
    (let [result (es/read (:event-store context)
                          {:tenant-id (:tenant-id context) :tags #{[:tick tick-id]}
                           :types #{:sheet/tree-tick-started}})]
      (when (:cognitect.anomalies/category result)
        (throw (ex-info "Cannot read canonical model campaign start"
                        {:failure-kind :model-plan-incompatible :reason :canonical-start-unavailable})))
      (first (filter #(= tick-id (:tick-id %)) (into [] result))))))

(defn- campaign-options [start]
  (let [options (:options start)
        fields [:researcher-campaign-sheet-id :researcher-campaign-tick-id :researcher-campaign-node-id]
        ids (mapv #(get options %) fields)
        advertised? (some #(contains? options %) (conj fields :researcher-ownership-epoch :researcher-iteration-index))]
    (when (and advertised?
               (not (and (every? uuid? ids)
                         (or (nil? (:researcher-campaign-exec-context options))
                             (map? (:researcher-campaign-exec-context options)))
                         (or (not (contains? options :researcher-ownership-epoch))
                             (pos-int? (:researcher-ownership-epoch options)))
                         (or (not (contains? options :researcher-iteration-index))
                             (nat-int? (:researcher-iteration-index options))))))
      (throw (ex-info "Canonical model campaign metadata is incomplete"
                      {:failure-kind :model-plan-incompatible :reason :emitted-campaign-lineage-unavailable})))
    (when (every? uuid? ids)
      {:sheet-id (nth ids 0) :tick-id (nth ids 1) :node-id (nth ids 2)
       :exec-context (or (:researcher-campaign-exec-context options) {})
       :ownership-epoch (:researcher-ownership-epoch options)
       :iteration-index (:researcher-iteration-index options)
       :parent-tick-id (:parent-tick-id start)})))

(defn researcher-campaign
  "Original emitted campaign metadata from the canonical first start.
   This is an index description, not permission to dispatch a model request."
  [context tick-id]
  (campaign-options (first-start context tick-id)))

(defn- campaign-tags [start body]
  (if-let [campaign (campaign-options start)]
    #{(effects/campaign-tag (:sheet-id campaign) (:tick-id campaign) (:node-id campaign))}
    ;; Inline specialists belong to their actual parent researcher. Its starts,
    ;; resumes and terminal facts must share the same aggregate as model facts.
    (let [nodes (get-in start [:execution-snapshot :nodes-by-id])
          node-id (:node-id body)
          researchers (if node-id
                        (when (= :repl-researcher (:type (get nodes node-id))) [node-id])
                        (keep (fn [[id node]] (when (= :repl-researcher (:type node)) id)) nodes))]
      (into #{} (map #(effects/campaign-tag (:sheet-id start) (:tick-id start) %) researchers)))))

(defn root
  "Resolve a bounded owner through complete canonical first-start lineage.
   Unknown/cyclic ancestry and read anomalies never imply an unlimited family."
  [context tick-id]
  (loop [tick-id tick-id seen #{} owner nil]
    (cond
      (nil? tick-id) owner
      (contains? seen tick-id)
      (throw (ex-info "Cyclic LLM budget ancestry" {:failure-kind :budget-history-unavailable}))
      :else
      (let [result (es/read (:event-store context)
                            {:tenant-id (:tenant-id context) :tags #{[:tick tick-id]}
                             :types #{:sheet/tree-tick-started}})
            _ (when (:cognitect.anomalies/category result)
                (throw (ex-info "Cannot read LLM budget ancestry" {:failure-kind :budget-history-unavailable :anomaly result})))
            start (first (into [] result))
            _ (when (and (nil? start) (seq seen))
                (throw (ex-info "Incomplete LLM budget ancestry" {:failure-kind :budget-history-unavailable :tick-id tick-id})))
            budget (get-in start [:options :llm-call-budget])
            owner (if (and (integer? budget) (pos? budget))
                    {:sheet-id (:sheet-id start) :tick-id (:tick-id start)
                     :budget budget :accounting-version (get-in start [:options :llm-budget-accounting-version])}
                    owner)]
        (recur (:parent-tick-id start) (conj seen tick-id) owner)))))

(defn start-options [context sheet-id tick-id parent-tick-id options]
  (let [parent-root (when parent-tick-id (root context parent-tick-id))
        budget (:llm-call-budget options)]
    (cond
      parent-root
      (assoc options :llm-call-budget (:budget parent-root)
                     :llm-budget-root-sheet-id (:sheet-id parent-root)
                     :llm-budget-root-tick-id (:tick-id parent-root))
      (and (integer? budget) (pos? budget))
      (assoc options :llm-budget-root-sheet-id sheet-id :llm-budget-root-tick-id tick-id
                     :llm-budget-accounting-version accounting-version)
      :else options)))

(defn tags [context tick-id existing]
  (if-let [owner (root context tick-id)]
    (conj existing [:llm-budget-root (:tick-id owner)])
    existing))

(defn event
  "Construct a canonical admission fact with its dedicated family tag.
   New first starts use their already-derived options; later facts use durable lineage."
  [context {:keys [type body] :as definition}]
  (let [first? (= :sheet/tree-tick-started type)
        start (if first? body (first-start context (:tick-id body)))
        root-id (when first? (get-in body [:options :llm-budget-root-tick-id]))]
    (es/->event (update definition :tags
                        (fn [existing]
                          (into (if root-id
                                  (conj existing [:llm-budget-root root-id])
                                  (tags context (:tick-id body) existing))
                                (campaign-tags start body)))))))
