(ns decision-eval.run
  "Runs the labelled decision cases on a native decision model and on a
   conversational model through real llm-decision workflows; writes
   results.edn into the eval directory (-Deval.dir). Makes paid calls."
  (:require [clojure.edn :as edn]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.complex-e2e-support :as live]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]))

(defn- eval-dir [] (System/getProperty "eval.dir" "development/bench/decision_eval"))

(def ^:private routes-atom (atom nil))
(def ^:private models {:jev "jev" :gemini live/openrouter-model})

(defn- route-wf [k]
  (sheet/workflow (str "decision-eval-route-" (name k))
    (sheet/blackboard {:request :string
                       :route (into [:enum {:descriptions @routes-atom}] (keys @routes-atom))})
    (sheet/llm-decision "route" :model (models k)
      :instruction "Route this customer support message to the team that should handle it."
      :reads [:request] :writes [:route])))

(defn- truth-wf [k]
  (sheet/workflow (str "decision-eval-truth-" (name k))
    (sheet/blackboard {:claim :string :holds :boolean})
    (sheet/llm-decision "holds" :model (models k)
      :instruction "Is the claim factually true?"
      :reads [:claim] :writes [:holds])))

(defn- record-of [ctx result]
  (first (filter #(and (= :sheet/node-execution-completed (:event/type %)) (:decision %))
                 (h/read-tick-events ctx (:trace-id result)))))

(defn- run-one [ctx sheet-id inputs out-key]
  (let [t0 (System/nanoTime)
        result (try (sheet/execute ctx sheet-id inputs :timeout-ms 120000)
                    (catch Exception e {:status :exception :error (.getMessage e)}))
        ms (quot (- (System/nanoTime) t0) 1000000)
        c (when (:trace-id result) (record-of ctx result))]
    {:status (:status result) :answer (get-in result [:outputs out-key]) :ms ms
     :confidence (get-in c [:decision :confidence])
     :probability (get-in c [:decision :probability])
     :probabilities (get-in c [:decision :probabilities])
     :usage (:usage c) :model (:model c) :error (:error result)}))

(defn -main [& _]
  (let [dir (eval-dir)
        cases (edn/read-string (slurp (str dir "/cases.edn")))
        results (atom [])]
    (reset! routes-atom (:routes cases))
    (live/register-openrouter!)
    (llm/register-provider! :jev {:provider :openrouter :protocol :decision :model "typesafe/jev-1.13"
                                  :config {:api-key (System/getenv "OPENROUTER_API_KEY")}})
(h/with-async-test-context [ctx {:context {:llm-provider :openrouter}}]
  (doseq [k [:jev :gemini]]
    (let [rid (sheet/build-workflow! ctx (route-wf k))
          tid (sheet/build-workflow! ctx (truth-wf k))]
      (doseq [{:keys [id label text hard?]} (:routing cases)]
        (let [r (run-one ctx rid {:request text} :route)]
          (swap! results conj (merge r {:task :routing :model k :id id :label label :hard? hard?}))
          (println k id label "->" (:answer r) (:confidence r) (:ms r) "ms" (when (:error r) (:error r)))))
      (doseq [{:keys [id label text]} (:propositions cases)]
        (let [r (run-one ctx tid {:claim text} :holds)]
          (swap! results conj (merge r {:task :truth :model k :id id :label label}))
          (println k id label "->" (:answer r) (:probability r) (:ms r) "ms"))))))

    (spit (str dir "/results.edn") (pr-str @results))
    (println :DONE (count @results))
    (shutdown-agents)
    (System/exit 0)))
