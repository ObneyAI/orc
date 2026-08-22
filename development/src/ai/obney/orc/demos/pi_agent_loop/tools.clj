(ns ai.obney.orc.demos.pi-agent-loop.tools
  "Pi-shaped tools whose effect boundary is a public ORC workflow execution."
  (:require [ai.obney.orc.orc-service.interface :as orc]))

(defn orc-workflow-tool
  "Create a loop tool backed by `orc/execute`.

   `input-fn` maps validated tool arguments to workflow inputs. `content-fn`
   maps a successful ORC result to transcript content. Execution provenance is
   always retained in :details."
  [{:keys [name description context sheet-id correlation-id input-fn content-fn
           timeout-ms execution-mode prepare-arguments validate-arguments active-ticks]
    :or {input-fn identity content-fn :outputs timeout-ms 30000
         execution-mode :parallel}}]
  {:pre [(string? name) context (uuid? sheet-id) (uuid? correlation-id)]
   :name name
   :description description
   :execution-mode execution-mode
   :execute-with-updates? true
   :prepare-arguments prepare-arguments
   :validate-arguments validate-arguments
   :execute
   (fn [arguments _update!]
     (let [tick-id (random-uuid)
           _ (when active-ticks (swap! active-ticks conj tick-id))
           result (try
                    (orc/execute context sheet-id (input-fn arguments)
                                 :tick-id tick-id
                                 :timeout-ms timeout-ms
                                 :correlation-id correlation-id)
                    (finally
                      (when active-ticks (swap! active-ticks disj tick-id))))
           details {:orc/trace-id (:trace-id result)
                    :orc/correlation-id correlation-id
                    :orc/status (:status result)
                    :orc/duration-ms (:duration-ms result)
                    :orc/outputs (:outputs result)}]
       (if (= :success (:status result))
         {:content (content-fn result) :details details}
         {:content (or (:error result) (str "ORC workflow " name " failed"))
          :details details :error true})))})

(defn cancel-active! [context active-ticks]
  (mapv #(orc/cancel! context %) (vec @active-ticks)))

(defn execution-identifiers [loop-result]
  (->> (:new-messages loop-result)
       (keep #(get-in % [:details :orc/trace-id]))
       vec))
