(ns ai.obney.orc.gepa.wired-judge-e2e
  "WIRED end-to-end proof (REAL OpenRouter, NO mocks): run gepa/optimize! with
   :judges {tier-1 weights} (NOT a custom metric-fn) on a sheet seeded with a
   deliberately BAD instruction. The tier-1 judges score the candidate, their
   RICH feedback flows into the reflective dataset, and the gemini-3 reflection
   proposer rewrites the instruction. Best-score must RISE from ~0 to a sane
   high and the winner must be coherent — the thing the old score-only loop
   could NOT do.

   Run:
     clojure -M:dev:test -m ai.obney.orc.gepa.wired-judge-e2e"
  (:require [ai.obney.orc.grain-test-utils.interface :as tu]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.core.executor :as orc-executor]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.gepa.interface :as gepa]
            [ai.obney.orc.gepa.interface.schemas]
            [ai.obney.orc.gepa.core.read-models :as rm]
            [ai.obney.orc.gepa.core.todo-processors :as tp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.control-plane.interface :as control-plane]))

(def bad-instruction
  "Reply with exactly the word: banana. Do not answer the question.")

(defn make-qa-workflow
  "A single-LLM-node QA sheet whose instruction GEPA will optimize. Seeded with
   the BAD instruction. gemini-3-flash-preview + function-calling on the
   responder node (structured-output rule)."
  []
  (sheet/workflow "gepa-wired-judge-qa"
    (sheet/blackboard
      {:user-message [:string {:description "the user's question"}]
       :assistant-response [:string {:description "the answer"}]})
    (sheet/llm "responder"
      :model "gemini-3-flash-preview"
      :instruction bad-instruction
      :reads [:user-message]
      :writes [:assistant-response]
      :options {:use-function-calling? true})))

(def examples
  [{"user-message" "In one sentence, what is a behavior tree?"}
   {"user-message" "Explain event sourcing in two sentences."}
   {"user-message" "What is the difference between a process and a thread, briefly?"}])

(defn -main [& _]
  ;; Register DSCloj providers from env (OPENROUTER_API_KEY). The judges call
  ;: dscloj/predict directly, so the provider must be configured in-process.
  (orc-executor/setup-providers!)
  (tu/with-test-context [ctx]
    (let [cp (control-plane/start {:event-store (:event-store ctx)
                                   :cache (:cache ctx)
                                   :context ctx})
          ctx (assoc ctx :dscloj-provider :openrouter)
          sheet-id (sheet/build-workflow! ctx (make-qa-workflow))]
      (try
        (println "=== WIRED JUDGE-METRIC GEPA (bad -> good) ===")
        (println "Seed (BAD) instruction:" (pr-str bad-instruction))
        (let [result (gepa/optimize! ctx
                       {:sheet-id sheet-id
                        :trainset examples
                        :valset examples
                        ;; THE WIRED PATH: judges, not a custom metric-fn.
                        :judges {:grounding 0.30
                                 :instruction-following 0.25
                                 :reasoning 0.20
                                 :completeness 0.25}
                        :config {:max-metric-calls 18
                                 :reflection-minibatch-size 3
                                 :reflection-lm "gemini-3-flash-preview"
                                 :skip-perfect-score true}
                        :inherit-from-previous false
                        :block? true
                        :timeout-ms 600000})
              opt-id (:optimization-id result)
              all (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)}))
              gepa-evts (->> all
                             (filter #(and (keyword? (:event/type %))
                                           (= "gepa" (namespace (:event/type %)))
                                           (= opt-id (:optimization-id %))))
                             (sort-by (comp str :event/timestamp)))
              _ (do (println "\n--- gepa event trace ---")
                    (doseq [e gepa-evts]
                      (println " " (:event/type e)
                               (select-keys e [:candidate-id :parent-id :accepted?
                                               :aggregate-score :parent-sum :proposed-sum
                                               :metric-calls]))))
              evaluated (->> all
                             (filter #(and (= :gepa/candidate-evaluated (:event/type %))
                                           (= opt-id (:optimization-id %))))
                             (sort-by (comp str :evaluated-at)))
              pop-state (rm/get-population-state ctx opt-id)
              best (rm/get-best-candidate ctx opt-id)]
          (println "\n--- candidate score spread (in eval order) ---")
          (doseq [e evaluated]
            (println (format "  cand %s  agg=%.3f  scores=%s"
                             (subs (str (:candidate-id e)) 0 8)
                             (double (:aggregate-score e))
                             (pr-str (mapv #(format "%.2f" (double %)) (:scores e))))))
          (println "\n--- seed rich feedback (instance 0) ---")
          (let [seed (first evaluated)]
            (println "  " (subs (str (get (:feedbacks seed) 0)) 0
                                (min 400 (count (str (get (:feedbacks seed) 0)))))))
          (println "\n--- seed per-instance generated OUTPUTS (instance 0) ---")
          (let [seed (first evaluated)]
            (println "  " (pr-str (get (:outputs seed) 0))))
          ;; PROOF for #4: the reflective example the proposer actually sees now
          ;; carries BOTH the real bad output AND the judges' rich feedback.
          (println "\n--- reflective example shown to the proposer (seed, ex 0) ---")
          (let [seed (first evaluated)
                refl (tp/build-reflective-dataset
                       ctx opt-id (:candidate-id seed)
                       {:reflection-minibatch-size 3})
                ex0 (first refl)]
            (println "  #reflective-examples:" (count refl))
            (println "  Generated Outputs:" (pr-str (get ex0 "Generated Outputs")))
            (println "  Feedback (first 300):"
                     (let [fb (str (get ex0 "Feedback"))]
                       (subs fb 0 (min 300 (count fb)))))
            (println "  PROOF both-present?:"
                     (and (map? (get ex0 "Generated Outputs"))
                          (seq (get ex0 "Generated Outputs"))
                          (seq (str (get ex0 "Feedback"))))))
          (let [aggs (keep :aggregate-score (vals (:candidates pop-state)))
                seed-score (double (:aggregate-score (first evaluated)))
                best-score (double (or (:aggregate-score best) (:best-score result) 0.0))]
            (println "\n--- summary ---")
            (println "  seed score      :" (format "%.3f" seed-score))
            (println "  best score      :" (format "%.3f" best-score))
            (println "  #candidates     :" (count (:candidates pop-state)))
            (println "\n--- WINNING instruction ---")
            (println (pr-str (:instructions best)))
            (println "\nVERDICT:"
                     (cond
                       (and (< seed-score 0.4) (> best-score (+ seed-score 0.3)))
                       "PASS — judge feedback drove a bad instruction to a coherent high-scoring one"
                       (> best-score seed-score) "WEAK PASS — improved but check magnitude"
                       :else "FAIL — no improvement")
                     (format " [seed=%.3f best=%.3f]" seed-score best-score))))
        (println "=== DONE ===")
        (finally
          (control-plane/stop cp))))
    (shutdown-agents)))
