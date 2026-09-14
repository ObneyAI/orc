(ns ai.obney.orc.orc-service.core.researcher-mode
  "Authoritative execution-mode decisions for repl-researcher nodes.")

(defn checkpointed?
  "True when a repl-researcher uses durable checkpoint execution.

   An explicit :checkpointed? value is authoritative in every RLM mode.
   Otherwise recursive RLM researchers checkpoint by default while terminal
   and non-RLM researchers preserve their prior execution mode."
  [node]
  (when (and (= :repl-researcher (:type node))
             (:rlm node))
    (let [rlm-config (if (map? (:rlm node)) (:rlm node) {})]
      (if (contains? rlm-config :checkpointed?)
        (true? (:checkpointed? rlm-config))
        (not= false (:recursive? rlm-config))))))

