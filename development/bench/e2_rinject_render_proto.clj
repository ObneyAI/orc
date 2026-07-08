(ns e2-rinject-render-proto
  "THROWAWAY — E2 live-verify (Phase B, ADR 0014). NOT production code.

   Drives the REAL R-Inject path end-to-end:
     real grain (in-memory durable event store + command processor)
     + real ColBERT behavioral pool (seeded corpus, built index)
     + real OpenRouter reranker
   via the actual wedge `maybe-auto-classify-and-set-context` (runs
   classify-task + classify-behaviors, dispatches the command, assembles
   the :r05-classifier payload) then renders the prepend with the actual
   `apply-r05-classifier-context`.

   Confirms: an ABOVE-THRESHOLD coding task now receives a specialize/mint
   invitation in its rendered R-Inject prepend (it did NOT before E2), and
   an OOD/below-threshold task still mints. Prints both prepends VERBATIM.

   Run (from the orc-rinject-redesign worktree, OPENROUTER_API_KEY in env):
     clojure -M:dev \\
       -J-Dcolbert.venv.path=/Users/darylroberts/Desktop/Code/orc-main/.venv-colbert \\
       -J-Dcolbert.bridge.script=/Users/darylroberts/Desktop/Code/orc-rinject-redesign/scripts/colbert_bridge.py \\
       -m e2-rinject-render-proto"
  (:require [runner]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]
            [ai.obney.orc.colbert.interface :as colbert]
            [ai.obney.orc.ontology.interface :as ont]))

;; An above-threshold coding task (E1 showed add-function matches a broad
;; parent >= 0.6) and an OOD task that should fall through / mint.
(def coding-task
  "Add a new function `summarize-line-items` to the report namespace that aggregates each invoice's line items into a per-category subtotal and returns a sorted vector.")

(def ood-task
  "Suggest a vegetarian dinner menu for eight guests with a wine pairing for each course and a shopping list.")

(defn make-node [instruction]
  {:id (random-uuid)
   :type :repl-researcher
   :name "e2-probe"
   :instruction instruction
   :reads [:user-message :active-plan :workspace-root]
   :writes [:assistant-response]
   :mcp-tools ["shell/exec" "fs/read" "fs/list"]
   ;; The wedge only fires for an auto-classify rlm node with no :context.
   :rlm {:auto-classify? true}})

(defn render-for [ctx label instruction]
  (println (str "\n\n############################################################"))
  (println (str "## " label))
  (println (str "############################################################"))
  (let [sheet-id (random-uuid)
        wedge-ctx (assoc ctx :sheet-id sheet-id :tick-id (random-uuid))
        node (make-node instruction)
        ;; REAL wedge: classify-task + classify-behaviors + command dispatch.
        classified (tp/maybe-auto-classify-and-set-context node wedge-ctx)
        payload (get-in classified [:context :r05-classifier])
        rendered (tp/apply-r05-classifier-context classified wedge-ctx)
        prepend (let [inst (:instruction rendered)
                      parts (str/split inst #"\n---\n" 2)]
                  (first parts))]
    (println "\n--- classifier facts (real) ---")
    (println "structural assigned-tree-id:" (get-in payload [:structural :assigned-tree-id])
             "| confidence:" (get-in payload [:structural :confidence])
             "| was-fresh-mint?:" (get-in payload [:structural :was-fresh-mint?]))
    (doseq [b (get-in payload [:behavioral :behaviors])]
      (println "  behavioral:" (:behavior-id b)
               "| confidence:" (:confidence b)
               "| was-fresh-mint?:" (:was-fresh-mint? b)
               "| rerank-source:" (:rerank-source b)))
    (println "\n--- RENDERED R-INJECT PREPEND (verbatim) ---")
    (println prepend)
    (println "--- END PREPEND ---")
    {:label label
     :payload payload
     :prepend prepend
     :has-specialize? (boolean (re-find #"(?i)specialize" prepend))
     :has-mint-behavior? (boolean (re-find #"mint-behavior!" prepend))
     :has-adjacent? (boolean (re-find #"(?i)adjacent" prepend))
     :any-found? (boolean (some #(false? (:was-fresh-mint? %))
                                (get-in payload [:behavioral :behaviors])))}))

(defn -main [& _]
  (println "=== E2 THROWAWAY: render the REAL R-Inject prepend ===")
  (println "venv :" (System/getProperty "colbert.venv.path"))
  (println "bridge:" (System/getProperty "colbert.bridge.script"))
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (println "FATAL: OPENROUTER_API_KEY not set") (System/exit 1))
  (try
    (runner/start!)
    (let [ctx (deref (var-get (requiring-resolve 'runner/system-state)))]
      ;; rule out the harness first
      (println "\n--- HARNESS SANITY ---")
      (let [idxs (filter #(= "ontology-descriptions" (:index-name %))
                         (colbert/list-indexes ctx))]
        (println "ontology-descriptions indexes built:" (count idxs)))
      (println "reindex-state:" (pr-str (ont/get-reindex-state ctx)))
      (let [coding (render-for ctx "CODING TASK (above-threshold expected)" coding-task)
            ood (render-for ctx "OOD TASK (below-threshold / mint expected)" ood-task)]
        (println "\n\n=== ASSERTIONS (real render) ===")
        (println "[CODING] a behavioral match was FOUND (>=threshold):" (:any-found? coding))
        (println "[CODING] prepend contains 'specialize':" (:has-specialize? coding))
        (println "[CODING] prepend contains 'mint-behavior!':" (:has-mint-behavior? coding))
        (println "[CODING] prepend contains 'adjacent':" (:has-adjacent? coding))
        (println "[OOD]    prepend contains 'mint-behavior!':" (:has-mint-behavior? ood))
        (println "[OOD]    prepend contains 'specialize':" (:has-specialize? ood))
        (let [ok (and (:any-found? coding)
                      (:has-specialize? coding)
                      (:has-mint-behavior? coding)
                      (:has-mint-behavior? ood))]
          (println "\nE2 LIVE-VERIFY:" (if ok "PASS" "INVESTIGATE"))))
      (println "\n=== DONE ==="))
    (catch Throwable t
      (println "ERROR:" (.getMessage t))
      (.printStackTrace t))
    (finally
      (try (runner/stop!) (catch Throwable _ nil))
      (try (colbert/stop-bridge!) (catch Throwable _ nil))
      (shutdown-agents))))
