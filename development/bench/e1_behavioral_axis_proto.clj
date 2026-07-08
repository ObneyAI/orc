(ns e1-behavioral-axis-proto
  "THROWAWAY — E1 observation harness (Phase B, ADR 0014). NOT production code.

   Read-only observation: does the over-general trap reproduce on the
   BEHAVIORAL axis for coding tasks? Reuses bench.runner/start! to get a
   ctx with a REAL built ontology-descriptions ColBERT index (real
   in-memory grain + real ColBERT bridge + real OpenRouter), then calls
   ai.obney.orc.ontology.interface/classify-behaviors (threshold 0.6,
   top-n 5) on ~12 coding-implementation task signatures + 3 OOD tasks.

   Run (from the orc-rinject-redesign worktree, OPENROUTER_API_KEY in env):
     clojure -M:dev \\
       -J-Dcolbert.venv.path=/Users/darylroberts/Desktop/Code/orc-main/.venv-colbert \\
       -J-Dcolbert.bridge.script=/Users/darylroberts/Desktop/Code/orc-rinject-redesign/scripts/colbert_bridge.py \\
       -m e1-behavioral-axis-proto

   NO engine source change. This file is scratch/prototype."
  (:require [runner]
            [ai.obney.orc.ontology.interface :as ont]
            [ai.obney.orc.ontology.core.task-classifier :as tc]
            [ai.obney.orc.colbert.interface :as colbert]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; behavior-id -> seed name, derived from the 12 behavioral-subtree seeds
;; (first word of each :summary; ids verified against
;; components/ontology/resources/seeds/behavioral-subtrees.edn @ cefbb577)
;; -----------------------------------------------------------------------------
(def seed-name-by-id
  {#uuid "8ad38e72-05e4-3201-bac7-3ed9c54a2791" "Research"
   #uuid "abe10f3b-ab49-3916-ae30-0ca7abfcad96" "Extraction"
   #uuid "b3597aa7-3957-3734-90c6-531d27c08f67" "Analysis"
   #uuid "2bce84a1-d186-3892-8131-c19b59e4543e" "Synthesis"
   #uuid "1b1480f1-66b3-399a-8715-e5b8f023a71b" "Ideation"
   #uuid "abe48812-ad0f-3a27-a142-806558801fc0" "Design"
   #uuid "0f11dba5-c331-3cdf-81fa-8ee114fc224f" "Critique"
   #uuid "1361eafc-6a90-391a-97a9-b558118f1f57" "Validation"
   #uuid "bf47c816-2833-320e-9fbd-6ae109275ab0" "Code-building"
   #uuid "86275302-0c3d-35ae-b74e-abd4f27984eb" "Transformation"
   #uuid "01f78800-c0e7-34b3-bb09-a7a6a95a022d" "Classification"
   #uuid "760be698-0bb8-3a5a-a2bd-1d45445a5861" "Investigation"})

(defn seed-name [id] (get seed-name-by-id id (str "MINT/" (subs (str id) 0 8))))

;; Coding-implementation tasks (task in :instruction so it reaches the
;; classifier via build-task-signature — PB-1 showed static node sig is task-blind).
(def coding-tasks
  [["add-function"      "Add a new function `summarize-line-items` to the report namespace that aggregates each invoice's line items into a per-category subtotal and returns a sorted vector."]
   ["fix-bug"           "Fix the off-by-one bug in the pagination loop in list_view.clj so the final page renders its last row instead of dropping it."]
   ["refactor"          "Refactor the order service to extract a pure pricing helper from the request handler, preserving existing behavior and keeping all current tests green."]
   ["add-tests"         "Write unit tests for the pricing helper covering percentage discounts, flat discounts, tax, and the zero-quantity edge case."]
   ["wire-dependency"   "Add the cheshire JSON dependency to deps.edn and use it in the export writer to serialize the report map to a .json file."]
   ["rename-symbol"     "Rename the function `calc` to `compute-tax` across the billing namespace and update every caller, including the test files."]
   ["write-doc"         "Write a docstring and a short usage example for the public `parse-config` function describing each option key it accepts."]
   ["debug-failing-test" "Debug why the integration test `checkout-flow-test` fails intermittently with a nil cart-id and make it deterministic."]
   ["perf-tune"         "The report-rendering endpoint is slow on large inputs; profile it and optimize the hot path so a 10k-row report renders under 500ms without changing the output."]
   ["security-review"   "Review the auth middleware for injection and broken-access-control vulnerabilities and report each finding with severity and a suggested fix."]
   ["add-endpoint"      "Add a new POST /invoices REST endpoint that validates the request body against the invoice schema, persists it, and returns the created id."]
   ["migrate-schema"    "Write a database migration that adds a nullable `archived_at` timestamp column to the invoices table and backfills it to null for existing rows."]])

;; OOD / non-coding tasks — first-class overfit evidence.
(def ood-tasks
  [["ood-earnings-pdf"  "Summarize this quarterly earnings PDF into a one-page executive brief highlighting revenue, margin trend, and forward guidance."]
   ["ood-marketing"     "Plan a three-month marketing campaign to launch a new consumer coffee brand, including channels, budget split, and a content calendar."]
   ["ood-recipe"        "Suggest a vegetarian dinner menu for eight guests with a wine pairing for each course and a shopping list."]])

(def all-tasks (concat coding-tasks ood-tasks))

(defn classify-one
  "Run classify-behaviors on one task's signature. Returns the raw result."
  [ctx instruction]
  (let [sig (tc/build-task-signature {:instruction instruction
                                      :reads [:user-message :active-plan :workspace-root]
                                      :writes [:assistant-response]
                                      :mcp-tools ["shell/exec" "fs/read" "fs/list"]})]
    (ont/classify-behaviors ctx {:task-signature sig :threshold 0.6 :top-n 5})))

(defn fmt-row [behavior]
  (format "      %-16s conf=%.3f mint?=%s src=%s"
          (seed-name (:behavior-id behavior))
          (double (:confidence behavior))
          (:was-fresh-mint? behavior)
          (:rerank-source behavior)))

(defn -main [& _]
  (println "=== E1 THROWAWAY: behavioral-axis classification of coding tasks ===")
  (println "venv :" (System/getProperty "colbert.venv.path"))
  (println "bridge:" (System/getProperty "colbert.bridge.script"))
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (println "FATAL: OPENROUTER_API_KEY not set") (System/exit 1))
  (let [results (atom [])]
    (try
      ;; --- bring up real ctx + build the real ontology-descriptions index ---
      (runner/start!)
      (let [ctx (deref (var-get (requiring-resolve 'runner/system-state)))]
        ;; ---------------- RULE OUT THE HARNESS FIRST ----------------
        (println "\n--- HARNESS SANITY ---")
        (let [idxs (filter #(= "ontology-descriptions" (:index-name %))
                           (colbert/list-indexes ctx))]
          (println "ontology-descriptions indexes built:" (count idxs))
          (doseq [i idxs]
            (println "  index:" (:index-name i)
                     "doc-count:" (or (:document-count i) (:doc-count i) (:count i) "?"))))
        (println "reindex-state:" (pr-str (ont/get-reindex-state ctx)))
        (println "\n--- SANITY PROBE: an obvious Code-building task should return non-empty :behaviors ---")
        (let [probe (classify-one ctx "Implement a function from a typed spec: write the executable Clojure code for it, with imports and a file path.")]
          (println "sanity probe behaviors:")
          (doseq [b (:behaviors probe)] (println (fmt-row b)))
          (when (and (= 1 (count (:behaviors probe)))
                     (:was-fresh-mint? (first (:behaviors probe))))
            (println "  WARNING: sanity probe minted — the behavioral pool may be empty / wrong axis. Investigate before trusting numbers.")))

        ;; ---------------- THE SWEEP ----------------
        (println "\n--- SWEEP: classify-behaviors threshold=0.6 top-n=5 ---")
        (doseq [[label instruction] all-tasks]
          (let [r (classify-one ctx instruction)
                bs (:behaviors r)]
            (swap! results conj {:label label :instruction instruction :result r})
            (println (format "\n[%s] rerank-fallback?=%s" label (:rerank-fallback? r)))
            (doseq [b bs]
              (println (fmt-row b))
              (when (seq (:reasoning b))
                (println "        reasoning:" (:reasoning b))))))

        ;; ---------------- MACHINE-READABLE DUMP (verbatim, for the report) ----------------
        (println "\n\n=== VERBATIM RESULT DUMP (for the evidence table) ===")
        (doseq [{:keys [label instruction result]} @results]
          (println "\n----------------------------------------")
          (println "TASK:" label)
          (println "INSTRUCTION:" instruction)
          (println "rerank-fallback?:" (:rerank-fallback? result))
          (doseq [b (:behaviors result)]
            (println "  -" (seed-name (:behavior-id b))
                     "| confidence" (:confidence b)
                     "| was-fresh-mint?" (:was-fresh-mint? b)
                     "| rerank-source" (:rerank-source b)
                     "| behavior-id" (:behavior-id b))
            (println "    REASONING (verbatim):" (pr-str (:reasoning b)))))
        (println "\n=== DONE ==="))
      (catch Throwable t
        (println "ERROR:" (.getMessage t))
        (.printStackTrace t))
      (finally
        (try (runner/stop!) (catch Throwable _ nil))
        (try (colbert/stop-bridge!) (catch Throwable _ nil))
        (shutdown-agents)))))
