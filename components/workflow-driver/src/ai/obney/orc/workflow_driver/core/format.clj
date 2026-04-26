(ns ai.obney.orc.workflow-driver.core.format
  "Render observation data into LLM-readable text. The agent reasons in
   prose; observe.clj returns structured data; this namespace bridges
   the two.

   Style: terse, fact-dense, no decoration. Same shape as the strategy
   docstrings the existing :rlm mode uses."
  (:require [clojure.string :as str]))

(defn- pct
  "Format a 0..1 ratio as a percentage with one decimal place."
  [r]
  (when (number? r)
    (format "%.1f%%" (* 100.0 r))))

(defn- ms
  "Format a duration in ms as a short string."
  [n]
  (when (number? n)
    (cond
      (< n 1000) (str (long n) "ms")
      (< n 60000) (format "%.1fs" (/ n 1000.0))
      :else (format "%.1fmin" (/ n 60000.0)))))

(defn render-sheet-snapshot
  "Render a sheet-snapshot map as a compact text block."
  [{:keys [sheet root-node nodes-by-id blackboard-schema latest-version
           tree-metadata]}]
  (when sheet
    (str
      "Sheet: " (or (:name sheet) "<unnamed>") " (" (:id sheet) ")\n"
      (when latest-version
        (str "Latest published version: v" (:version-number latest-version)
             " at " (:published-at latest-version) "\n"))
      (when tree-metadata
        (str "Problem type: " (or (:problem-type tree-metadata) "—")
             ", avg score: " (or (:avg-score tree-metadata) "—") "\n"))
      "\nBlackboard schema:\n"
      (->> blackboard-schema
           (map (fn [{:keys [key schema]}]
                  (str "  " key " : " (pr-str schema))))
           (str/join "\n"))
      "\n\nRoot node: " (or (:name root-node) "<unnamed>")
      (when-let [t (:type root-node)]
        (str " (:" (clojure.core/name t) ")"))
      "\nTotal nodes: " (count nodes-by-id))))

(defn render-node-summary
  "Render per-node rolling metrics as a table. Skips nodes with no
   execution history."
  [node-summaries]
  (let [active (filter :execution-count node-summaries)]
    (if (empty? active)
      "No execution history yet."
      (str "Per-node performance (rolling window):\n"
           (->> active
                (sort-by (juxt (comp - (fnil identity 0) :execution-count)
                               (comp - (fnil identity 0) :avg-duration-ms)))
                (map (fn [{:keys [name executor node-type
                                  execution-count success-rate
                                  failure-rate avg-duration-ms
                                  recent-trend]}]
                       (str "  " name
                            " [" (or executor (clojure.core/name node-type)) "]"
                            " runs=" execution-count
                            " success=" (or (pct success-rate) "—")
                            " fail=" (or (pct failure-rate) "—")
                            " avg=" (or (ms avg-duration-ms) "—")
                            (when recent-trend
                              (str " trend=" (clojure.core/name recent-trend))))))
                (str/join "\n"))))))

(defn render-recent-ticks
  "Render a vector of tick summary maps as a compact list."
  [ticks]
  (if (empty? ticks)
    "No recent ticks."
    (str "Recent ticks (most recent first):\n"
         (->> ticks
              (map (fn [{:keys [id status iteration started-at root-status]}]
                     (str "  " id
                          " status=" (clojure.core/name (or status :unknown))
                          (when iteration (str " iter=" iteration))
                          (when root-status (str " root=" (clojure.core/name root-status)))
                          (when started-at (str " at=" started-at)))))
              (str/join "\n")))))

(defn render-tick-snapshot
  "Render one tick's snapshot as text."
  [{:keys [tick]}]
  (when tick
    (str "Tick " (:id tick) ":\n"
         "  status: " (clojure.core/name (:status tick)) "\n"
         "  iteration: " (:iteration tick) "\n"
         "  root-status: " (or (:root-status tick) "—") "\n"
         "  started-at: " (:started-at tick) "\n"
         "  completed-at: " (or (:completed-at tick) "—"))))

(defn render-pareto
  "Render the cost-vs-quality frontier datapoints."
  [points]
  (if (empty? points)
    "No completed ticks to compute frontier."
    (str "Completed ticks (sorted by duration):\n"
         (->> points
              (map (fn [{:keys [tick-id duration-ms root-status iteration]}]
                     (str "  " tick-id
                          " dur=" (or (ms duration-ms) "—")
                          " root=" (or (some-> root-status clojure.core/name) "—")
                          (when iteration (str " iter=" iteration)))))
              (str/join "\n")))))
