(ns ai.obney.orc.orc-service.gepa-recovery-evidence
  "Opt-in full GEPA test evidence; observers preserve actual calls and results."
  (:require [clojure.java.io :as io]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.gepa.core.todo-processors :as todo]
            [ai.obney.orc.gepa.core.metric-runtime :as metric-runtime]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.living-description-evidence :as evidence]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(defn capture-test [f]
  (if-let [root (System/getenv "ORC_GEPA_EVIDENCE_DIR")]
    (let [directory (str (io/file root (str (random-uuid))))
          contexts (atom [])
          create-context h/create-async-test-context
          stop-store es/stop
          execute-ai executor/execute-ai
          propose todo/generate-mutated-instruction
          invoke-metric metric-runtime/invoke-once!]
      (evidence/capture-http (str directory "/http")
        #(with-redefs
           [h/create-async-test-context
            (fn [& args]
              (let [ctx (apply create-context args)]
                (swap! contexts conj ctx)
                ctx))
            executor/execute-ai
            (fn [node blackboard provider & args]
              (let [options (:options (apply hash-map args))]
                (binding [evidence/*execution*
                          (merge {:kind :workflow-node :node-id (:id node)
                                  :node-name (:name node)}
                                 (select-keys options [:tick-id :node-attempt :exec-context]))]
                  (apply execute-ai node blackboard provider args))))
            todo/generate-mutated-instruction
            (fn [& args]
              (binding [evidence/*execution*
                        {:kind :gepa-proposer :optimization-id (nth args 5 nil)
                         :parent-id (nth args 6 nil) :iteration (nth args 7 nil)
                         :component (nth args 2 nil)}]
                (apply propose args)))
            metric-runtime/invoke-once!
            (fn [ctx descriptor input output]
              (binding [evidence/*execution*
                        (merge {:kind :gepa-metric :metric-id (:metric-id descriptor)
                                :metric-revision (:revision descriptor)}
                               (:gepa/task-attempt ctx))]
                (invoke-metric ctx descriptor input output)))
            es/stop
            (fn [store]
              ;; Async fixture closes the store after fencing/draining work.
              ;; Capture here while the canonical events are still readable.
              (doseq [[index ctx] (map-indexed vector @contexts)
                      :when (identical? store (:event-store ctx))]
                (try
                  (evidence/write-evidence! directory (str "context-" index)
                    {:tenant-id (:tenant-id ctx) :events (h/read-all-events ctx)})
                  (catch Throwable error
                    (evidence/write-evidence! directory (str "context-" index "-error")
                      {:error-class (.getName (class error))}))))
              (stop-store store))]
           (f))))
    (f)))
