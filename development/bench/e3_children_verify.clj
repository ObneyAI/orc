(ns e3-children-verify
  "THROWAWAY — E3 verification harness (Phase B, ADR 0014). NOT production code.

   Real grain + real ColBERT behavioral pool + real OpenRouter. Brings up the
   bench runner (which now seeds the 5 durable coding CHILDREN via
   seed-baseline-corpus! -> mint-behavioral-subtree and rebuilds the ColBERT
   index over the behavioral pool), then:
     1. classify-behaviors on the in-domain coding tasks that over-matched in
        E1 — does the relevant CHILD now surface (specialization)?
     2. renders the REAL R-Inject prepend for an in-domain task — do the
        child/parent strengths now render (Part-1 scope fix, end-to-end)?
     3. classify-behaviors on the E1 OOD tasks — OVERFIT GUARD: do they still
        NOT force-fit a coding child?
     4. reads the ontology read-model back to confirm the children's stable
        derived ids + skos:broader parent edges.

   Run (from the orc-rinject-redesign worktree, OPENROUTER_API_KEY in env):
     clojure -M:dev \\
       -J-Dcolbert.venv.path=/Users/darylroberts/Desktop/Code/orc-main/.venv-colbert \\
       -J-Dcolbert.bridge.script=/Users/darylroberts/Desktop/Code/orc-rinject-redesign/scripts/colbert_bridge.py \\
       -m e3-children-verify"
  (:require [runner]
            [ai.obney.orc.ontology.interface :as ont]
            [ai.obney.orc.ontology.core.task-classifier :as tc]
            [ai.obney.orc.orc-service.core.todo-processors :as otp]
            [ai.obney.orc.colbert.interface :as colbert]
            [clojure.string :as str]))

;; --- abstract parents (12) ---
(def parent-name-by-id
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

(defn derive-child-id [name parent]
  (java.util.UUID/nameUUIDFromBytes (.getBytes (str "mint:" name ":" parent) "UTF-8")))

(def code-building #uuid "bf47c816-2833-320e-9fbd-6ae109275ab0")
(def transformation #uuid "86275302-0c3d-35ae-b74e-abd4f27984eb")
(def investigation #uuid "760be698-0bb8-3a5a-a2bd-1d45445a5861")

(def children
  [["code-edit-dependency-wiring" transformation]
   ["performance-optimization"    transformation]
   ["documentation-writing"       code-building]
   ["bug-diagnosis"               investigation]
   ["rename-move-symbol"          code-building]])

(def child-name-by-id
  (into {} (for [[n p] children] [(derive-child-id n p) (str "child/" n)])))

(defn display-name [id]
  (or (parent-name-by-id id)
      (child-name-by-id id)
      (str "OTHER/" (subs (str id) 0 8))))

(defn child-id? [id] (contains? child-name-by-id id))

;; in-domain coding tasks (same instructions as E1) that over-matched a broad parent
(def in-domain-tasks
  [["wire-dependency"   "Add the cheshire JSON dependency to deps.edn and use it in the export writer to serialize the report map to a .json file."]
   ["perf-tune"         "The report-rendering endpoint is slow on large inputs; profile it and optimize the hot path so a 10k-row report renders under 500ms without changing the output."]
   ["write-doc"         "Write a docstring and a short usage example for the public `parse-config` function describing each option key it accepts."]
   ["debug-failing-test" "Debug why the integration test `checkout-flow-test` fails intermittently with a nil cart-id and make it deterministic."]
   ["rename-symbol"     "Rename the function `calc` to `compute-tax` across the billing namespace and update every caller, including the test files."]])

;; OOD / non-coding tasks — the overfit guard (must NOT force-fit a child)
(def ood-tasks
  [["ood-earnings-pdf"  "Summarize this quarterly earnings PDF into a one-page executive brief highlighting revenue, margin trend, and forward guidance."]
   ["ood-marketing"     "Plan a three-month marketing campaign to launch a new consumer coffee brand, including channels, budget split, and a content calendar."]
   ["ood-recipe"        "Suggest a vegetarian dinner menu for eight guests with a wine pairing for each course and a shopping list."]])

(defn classify-one [ctx instruction]
  (let [sig (tc/build-task-signature {:instruction instruction
                                      :reads [:user-message :active-plan :workspace-root]
                                      :writes [:assistant-response]
                                      :mcp-tools ["shell/exec" "fs/read" "fs/list"]})]
    (ont/classify-behaviors ctx {:task-signature sig :threshold 0.6 :top-n 5})))

(defn fmt-row [b]
  (format "      %-28s conf=%.3f mint?=%s src=%s"
          (display-name (:behavior-id b))
          (double (:confidence b))
          (:was-fresh-mint? b)
          (:rerank-source b)))

(defn -main [& _]
  (println "=== E3 VERIFY: durable coding children — specialization + overfit guard ===")
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (println "FATAL: OPENROUTER_API_KEY not set") (System/exit 1))
  (try
    (runner/start!)
    (let [ctx (deref (var-get (requiring-resolve 'runner/system-state)))]

      ;; -------- HARNESS SANITY + read-model confirmation of children --------
      (println "\n--- HARNESS SANITY ---")
      (let [idxs (filter #(= "ontology-descriptions" (:index-name %)) (colbert/list-indexes ctx))]
        (println "ontology-descriptions indexes built:" (count idxs)))

      (println "\n--- READ-MODEL: children landed at stable derived ids + parent edge ---")
      (doseq [[name parent] children]
        (let [id (derive-child-id name parent)
              body (ont/get-description ctx :tree-fingerprint id)
              child-uri (str "behavioral-subtree:" id)
              parent-uri (str "behavioral-subtree:" parent)
              concept (ont/get-concept-by-uri ctx child-uri)]
          (println (format "  %-28s id=%s landed=%s scope=%s parent-stamped=%s skos:broader->parent=%s"
                           name id
                           (some? body)
                           (:scope body)
                           (= parent (:parent-behavior body))
                           (boolean (and concept (contains? (:broader concept) parent-uri)))))))

      ;; -------- stable id on re-mint (mint twice -> same id) --------
      (println "\n--- STABLE ID ON RE-MINT (same id twice) ---")
      (let [[n p] (first children)
            id1 (derive-child-id n p)]
        ;; the corpus was already seeded once by runner/start!; emit the same
        ;; child again and confirm it resolves to the SAME id (history grows).
        (require 'ai.obney.orc.ontology.core.seeds)
        (let [emit! (requiring-resolve 'ai.obney.orc.ontology.core.seeds/emit-behavioral-child!)
              read-seeds (requiring-resolve 'ai.obney.orc.ontology.core.seeds/read-seeds)]
          (when (and emit! read-seeds)
            (let [child-edn (first (read-seeds "seeds/behavioral-subtree-children.edn"))]
              (emit! ctx child-edn)
              (Thread/sleep 300)
              (let [hist (ont/get-description-history ctx :tree-fingerprint id1)]
                (println (format "  %s re-minted -> same derived id %s ; history entries now = %d (>=2 proves accrual, not scatter)"
                                 n id1 (count hist))))))))

      ;; -------- IN-DOMAIN: specialization --------
      (println "\n--- IN-DOMAIN coding tasks: does the relevant CHILD surface? ---")
      (doseq [[label instruction] in-domain-tasks]
        (let [r (classify-one ctx instruction)
              bs (:behaviors r)
              child-hits (filter #(child-id? (:behavior-id %)) bs)]
          (println (format "\n[%s] rerank-fallback?=%s  child-surfaced?=%s"
                           label (:rerank-fallback? r) (boolean (seq child-hits))))
          (doseq [b bs]
            (println (fmt-row b))
            (when (seq (:reasoning b)) (println "        reasoning:" (:reasoning b))))))

      ;; -------- RENDERED PREPEND for one in-domain task (Part-1 end-to-end) --------
      (println "\n--- RENDERED R-INJECT PREPEND (in-domain perf-tune) — do strengths render? ---")
      (let [instruction (second (second in-domain-tasks)) ;; perf-tune
            r (classify-one ctx instruction)
            ;; build the wedge payload shape apply-r05-classifier-context expects
            payload {:structural {:assigned-tree-id (random-uuid)
                                  :confidence 0.0 :was-fresh-mint? true
                                  :reasoning "n/a — behavioral focus" :top-candidates []
                                  :rerank-fallback? false}
                     :behavioral {:behaviors (:behaviors r)
                                  :rerank-fallback? (:rerank-fallback? r)}}
            node {:id (random-uuid) :type :repl-researcher :name "verify"
                  :instruction instruction
                  :context {:tree-id (random-uuid) :r05-classifier payload}}
            result (otp/apply-r05-classifier-context node ctx)]
        (println (:instruction result)))

      ;; -------- OOD: overfit guard --------
      (println "\n\n--- OVERFIT GUARD: OOD tasks must NOT force-fit a coding child ---")
      (doseq [[label instruction] ood-tasks]
        (let [r (classify-one ctx instruction)
              bs (:behaviors r)
              child-hits (filter #(child-id? (:behavior-id %)) bs)]
          (println (format "\n[%s] child-force-fit?=%s  %s"
                           label (boolean (seq child-hits))
                           (if (seq child-hits) "*** FAIL: OOD matched a coding child ***" "PASS (no coding child)")))
          (doseq [b bs]
            (println (fmt-row b))
            (when (seq (:reasoning b)) (println "        reasoning:" (:reasoning b))))))

      (println "\n=== DONE ==="))
    (catch Throwable t
      (println "ERROR:" (.getMessage t))
      (.printStackTrace t))
    (finally
      (try (runner/stop!) (catch Throwable _ nil))
      (try (colbert/stop-bridge!) (catch Throwable _ nil))
      (shutdown-agents))))
