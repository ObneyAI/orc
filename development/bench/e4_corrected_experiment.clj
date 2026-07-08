(ns e4-corrected-experiment
  "THROWAWAY — E4 corrected experiment (Phase B, ADR 0014). NOT production code.

   The proof the self-learning redesign rests on. Runs BOTH classify-task
   (structural, :tree-fingerprint pool) AND classify-behaviors (behavioral,
   :behavioral-subtree pool) per task — the experiment sweep-2 never ran
   (it queried the wrong axis -> 0 hits). Measures, against the E1 baseline:
     1. Deepening as the MARGIN (child rank #1 above parent + delta).
     2. Non-child coding tasks ride the PARENT (anti-overfit).
     3. Before/after vs E1.
     4. Mint groundedness — normal + UNDER FALLBACK (fault ONLY at
        reranker/rerank!; ColBERT search+index stay REAL).
     5. OOD force-fit rate (target 0).

   Real grain + real ColBERT (both pools) + real OpenRouter. Classification
   only (NO full 8-iter RLM runs). The ONLY injected fault is reranker/rerank!.

   Run (venv read-only from orc-main; -J-D BEFORE -M:dev):
     OPENROUTER_API_KEY=... \\
     clojure -J-Dcolbert.venv.path=/Users/darylroberts/Desktop/Code/orc-main/.venv-colbert \\
             -J-Dcolbert.bridge.script=/Users/darylroberts/Desktop/Code/orc-rinject-redesign/scripts/colbert_bridge.py \\
             -M:dev -m e4-corrected-experiment"
  (:require [runner]
            [ai.obney.orc.ontology.interface :as ont]
            [ai.obney.orc.ontology.core.task-classifier :as tc]
            [ai.obney.orc.ontology.core.reranker :as reranker]
            [ai.obney.orc.orc-service.core.todo-processors :as otp]
            [ai.obney.orc.colbert.interface :as colbert]
            [clojure.string :as str]))

;; ============================================================================
;; Ids — parents + derived children (must match e3_children_verify.clj exactly)
;; ============================================================================
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

(def code-building  #uuid "bf47c816-2833-320e-9fbd-6ae109275ab0")
(def transformation #uuid "86275302-0c3d-35ae-b74e-abd4f27984eb")
(def investigation  #uuid "760be698-0bb8-3a5a-a2bd-1d45445a5861")

(def children
  [["code-edit-dependency-wiring" transformation]
   ["performance-optimization"    transformation]
   ["documentation-writing"       code-building]
   ["bug-diagnosis"               investigation]
   ["rename-move-symbol"          code-building]])

(def child-id->name (into {} (for [[n p] children] [(derive-child-id n p) (str "child/" n)])))
(def child-id->parent (into {} (for [[n p] children] [(derive-child-id n p) p])))
(defn child-id? [id] (contains? child-id->name id))
(defn display-name [id]
  (or (parent-name-by-id id) (child-id->name id)
      ;; Defensive: an OOD structural classify-task can return an empty/nil
      ;; :assigned-tree-id (no structural match) — render it without crashing the
      ;; OOD force-fit verdict on (subs "" 0 8). Harness display only.
      (let [s (str id)]
        (if (str/blank? s) "OTHER/<none>" (str "OTHER/" (subs s 0 (min 8 (count s))))))))

;; The expected child per child-targeted task (for margin computation).
(def task->expected-child
  {"wire-dependency"    (derive-child-id "code-edit-dependency-wiring" transformation)
   "perf-tune"          (derive-child-id "performance-optimization" transformation)
   "write-doc"          (derive-child-id "documentation-writing" code-building)
   "debug-failing-test" (derive-child-id "bug-diagnosis" investigation)
   "rename-symbol"      (derive-child-id "rename-move-symbol" code-building)})

;; ============================================================================
;; Task sets (instructions identical to E1 / E3 so before/after compares clean)
;; ============================================================================
(def child-targeted-tasks
  [["wire-dependency"   "Add the cheshire JSON dependency to deps.edn and use it in the export writer to serialize the report map to a .json file."]
   ["perf-tune"         "The report-rendering endpoint is slow on large inputs; profile it and optimize the hot path so a 10k-row report renders under 500ms without changing the output."]
   ["write-doc"         "Write a docstring and a short usage example for the public `parse-config` function describing each option key it accepts."]
   ["debug-failing-test" "Debug why the integration test `checkout-flow-test` fails intermittently with a nil cart-id and make it deterministic."]
   ["rename-symbol"     "Rename the function `calc` to `compute-tax` across the billing namespace and update every caller, including the test files."]])

;; non-child coding tasks — should ride the PARENT (Code-building), NOT a child
(def non-child-coding-tasks
  [["add-function"      "Add a new function `summarize-line-items` to the report namespace that aggregates each invoice's line items into a per-category subtotal and returns a sorted vector."]
   ["refactor"          "Refactor the order service to extract a pure pricing helper from the request handler, preserving existing behavior and keeping all current tests green."]
   ["add-endpoint"      "Add a new POST /invoices REST endpoint that validates the request body against the invoice schema, persists it, and returns the created id."]])

(def ood-tasks
  [["ood-earnings-pdf"  "Summarize this quarterly earnings PDF into a one-page executive brief highlighting revenue, margin trend, and forward guidance."]
   ["ood-marketing"     "Plan a three-month marketing campaign to launch a new consumer coffee brand, including channels, budget split, and a content calendar."]
   ["ood-recipe"        "Suggest a vegetarian dinner menu for eight guests with a wine pairing for each course and a shopping list."]])

(defn build-sig [instruction]
  (tc/build-task-signature {:instruction instruction
                            :reads [:user-message :active-plan :workspace-root]
                            :writes [:assistant-response]
                            :mcp-tools ["shell/exec" "fs/read" "fs/list"]}))

(defn classify-behaviors-one [ctx instruction]
  (ont/classify-behaviors ctx {:task-signature (build-sig instruction) :threshold 0.6 :top-n 5}))

(defn classify-task-one [ctx instruction]
  (ont/classify-task ctx {:task-signature (build-sig instruction) :threshold 0.7 :walk-down? true}))

;; ============================================================================
;; Margin helpers
;; ============================================================================
(defn behavior-by-id [behaviors id] (first (filter #(= id (:behavior-id %)) behaviors)))

(defn rank-of [behaviors id]
  (let [idx (first (keep-indexed (fn [i b] (when (= id (:behavior-id b)) i)) behaviors))]
    (when idx (inc idx))))

(defn fmt-b [b]
  (format "      %-30s conf=%.3f mint?=%-5s src=%s"
          (display-name (:behavior-id b))
          (double (or (:confidence b) 0.0))
          (str (:was-fresh-mint? b))
          (:rerank-source b)))

;; ============================================================================
;; MAIN
;; ============================================================================
(defn -main [& _]
  (println "=== E4 CORRECTED EXPERIMENT (Phase B, ADR 0014) ===")
  (println "venv  :" (System/getProperty "colbert.venv.path"))
  (println "bridge:" (System/getProperty "colbert.bridge.script"))
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (println "FATAL: OPENROUTER_API_KEY not set") (System/exit 1))
  (let [collected (atom {})]
    (try
      (runner/start!)
      (let [ctx (deref (var-get (requiring-resolve 'runner/system-state)))]

        ;; ==================================================================
        ;; 0. RULE OUT THE HARNESS FIRST (non-negotiable)
        ;; ==================================================================
        (println "\n############ 0. RULE-OUT-HARNESS ############")
        (let [idxs (filter #(= "ontology-descriptions" (:index-name %)) (colbert/list-indexes ctx))]
          (println "ontology-descriptions indexes built:" (count idxs))
          (doseq [i idxs]
            (println "  index doc-count:" (or (:document-count i) (:doc-count i) (:count i) "?"))))
        (println "reindex-state:" (pr-str (ont/get-reindex-state ctx)))

        ;; Prove EACH pool returns non-zero hits with sane scores AND the
        ;; right granularity stamped on every returned result.
        (println "\n--- POOL PROBE: :tree-fingerprint (the structural axis) ---")
        (let [tf (ont/search-descriptions ctx
                   {:query (build-sig "Implement a function from a typed spec: write the executable Clojure code.")
                    :granularity :tree-fingerprint
                    :rerank-with-intent "Find the structural pattern that best fits this task." :k 5})]
          (println "  hits:" (count tf)
                   " all granularity=:tree-fingerprint? ="
                   (and (seq tf) (every? #(= :tree-fingerprint (-> % :document-metadata :granularity)) tf))
                   " scores:" (pr-str (mapv #(some-> (:fitness-score %) double) tf)))
          (doseq [r (take 3 tf)]
            (let [tid (-> r :document-metadata :target-id)
                  uid (when tid (try (if (uuid? tid) tid (java.util.UUID/fromString (str tid))) (catch Throwable _ nil)))]
              (println "    -" (if uid (display-name uid) (str "raw-target-id=" tid))
                       "fit=" (some-> (:fitness-score r) double)))))

        (println "\n--- POOL PROBE: :behavioral-subtree (the behavioral axis) ---")
        (let [bs (ont/search-descriptions ctx
                   {:query (build-sig "Implement a function from a typed spec: write the executable Clojure code.")
                    :granularity :behavioral-subtree
                    :rerank-with-intent "Identify the behavioral approach for this task." :k 5})]
          (println "  hits:" (count bs)
                   " all granularity=:behavioral-subtree? ="
                   (and (seq bs) (every? #(= :behavioral-subtree (-> % :document-metadata :granularity)) bs))
                   " scores:" (pr-str (mapv #(some-> (:fitness-score %) double) bs))))

        (println "\n--- SANITY PROBE: classify-behaviors on an obvious Code-building task ---")
        (let [probe (classify-behaviors-one ctx "Implement a function from a typed spec: write the executable Clojure code for it, with imports and a file path.")]
          (doseq [b (:behaviors probe)] (println (fmt-b b)))
          (let [degenerate? (and (= 1 (count (:behaviors probe))) (:was-fresh-mint? (first (:behaviors probe))))]
            (println "  sanity-degenerate (1 fresh-mint only)? =" degenerate?)
            (when degenerate? (println "  !!! WARNING: behavioral pool may be empty/wrong axis — DO NOT TRUST NUMBERS"))))

        (println "\n--- SANITY PROBE: classify-task (structural) on the same task ---")
        (let [pt (classify-task-one ctx "Implement a function from a typed spec: write the executable Clojure code for it, with imports and a file path.")]
          (println "  assigned=" (display-name (:assigned-tree-id pt))
                   " conf=" (double (or (:confidence pt) 0.0))
                   " mint?=" (:was-fresh-mint? pt)
                   " fallback?=" (:rerank-fallback? pt)
                   " #top-candidates=" (count (:top-candidates pt))))

        ;; ==================================================================
        ;; Helper that runs BOTH classifiers + records everything per task
        ;; ==================================================================
        (let [run-task!
              (fn [label instruction]
                (let [bres (classify-behaviors-one ctx instruction)
                      tres (classify-task-one ctx instruction)
                      bs   (:behaviors bres)
                      rec  {:label label :instruction instruction
                            :behavioral bres :structural tres
                            :behaviors bs
                            :top-behavior (first bs)
                            :child-hits (filter #(child-id? (:behavior-id %)) bs)
                            :behav-fallback? (:rerank-fallback? bres)
                            :struct-assigned (:assigned-tree-id tres)
                            :struct-conf (:confidence tres)
                            :struct-mint? (:was-fresh-mint? tres)
                            :struct-fallback? (:rerank-fallback? tres)}]
                  (swap! collected assoc label rec)
                  (println (format "\n[%s] behav-fallback?=%s  struct: %s conf=%.3f mint?=%s"
                                   label (:rerank-fallback? bres)
                                   (display-name (:assigned-tree-id tres))
                                   (double (or (:confidence tres) 0.0))
                                   (:was-fresh-mint? tres)))
                  (println "    BEHAVIORAL top-N:")
                  (doseq [b bs]
                    (println (fmt-b b))
                    (when (seq (:reasoning b)) (println "        reasoning:" (subs (:reasoning b) 0 (min 220 (count (:reasoning b)))))))
                  rec))]

          ;; ================================================================
          ;; 1+3. CHILD-TARGETED TASKS — deepening margins (child vs parent)
          ;; ================================================================
          (println "\n\n############ 1. CHILD-TARGETED TASKS (deepening margin) ############")
          (doseq [[label instruction] child-targeted-tasks] (run-task! label instruction))

          ;; ================================================================
          ;; 2. NON-CHILD CODING TASKS — should ride the PARENT, not a child
          ;; ================================================================
          (println "\n\n############ 2. NON-CHILD CODING TASKS (anti-overfit: ride parent) ############")
          (doseq [[label instruction] non-child-coding-tasks] (run-task! label instruction))

          ;; ================================================================
          ;; 5. OOD TASKS — must NOT force-fit a coding child
          ;; ================================================================
          (println "\n\n############ 5. OOD TASKS (force-fit guard) ############")
          (doseq [[label instruction] ood-tasks] (run-task! label instruction))

          ;; ================================================================
          ;; 4b. FALLBACK-MINT measurement (induced fault ONLY at rerank!)
          ;; ================================================================
          (println "\n\n############ 4b. UNDER FALLBACK: induced reranker failure (ColBERT search+index REAL) ############")
          (println "Fault injected ONLY at reranker/rerank! (throw). ColBERT search + index stay real.")
          (let [fallback-recs (atom {})]
            (with-redefs [reranker/rerank! (fn [& _] (throw (ex-info "induced reranker failure (E4 fallback measurement)" {})))]
              (doseq [[label instruction] (concat child-targeted-tasks ood-tasks)]
                (let [bres (classify-behaviors-one ctx instruction)
                      bs (:behaviors bres)
                      top (first bs)]
                  (swap! fallback-recs assoc label
                         {:fallback? (:rerank-fallback? bres)
                          :top-mint? (:was-fresh-mint? top)
                          :top-name (display-name (:behavior-id top))
                          :top-src (:rerank-source top)
                          :n (count bs)
                          :any-child? (boolean (seq (filter #(child-id? (:behavior-id %)) bs)))})
                  (println (format "  [%s] fallback?=%s top=%s src=%s mint?=%s n=%d child-forcefit?=%s"
                                   label (:rerank-fallback? bres)
                                   (display-name (:behavior-id top)) (:rerank-source top)
                                   (:was-fresh-mint? top) (count bs)
                                   (boolean (seq (filter #(child-id? (:behavior-id %)) bs))))))))
            ;; Render the REAL R-Inject prepend under fallback for one in-domain
            ;; task and read the caution lines back (assert from rendered output).
            (println "\n--- RENDERED R-INJECT PREPEND under fallback (perf-tune) — caution lines? ---")
            (with-redefs [reranker/rerank! (fn [& _] (throw (ex-info "induced reranker failure (E4 fallback measurement)" {})))]
              (let [instruction (second (second child-targeted-tasks))
                    bres (classify-behaviors-one ctx instruction)
                    tres (classify-task-one ctx instruction)
                    node {:id (random-uuid) :name "e4-fallback-perf-tune"
                          :instruction "ORIGINAL INSTRUCTION BODY (sentinel)."
                          :context {:tree-id (:assigned-tree-id tres)
                                    :r05-classifier
                                    {:structural {:assigned-tree-id (:assigned-tree-id tres)
                                                  :confidence (:confidence tres)
                                                  :was-fresh-mint? (:was-fresh-mint? tres)
                                                  :reasoning (:reasoning tres)
                                                  :top-candidates (vec (:top-candidates tres))
                                                  :rerank-fallback? (boolean (:rerank-fallback? tres))}
                                     :behavioral {:behaviors (vec (:behaviors bres))
                                                  :rerank-fallback? (boolean (:rerank-fallback? bres))}}}}
                    apply-r05 (requiring-resolve 'ai.obney.orc.orc-service.core.todo-processors/apply-r05-classifier-context)
                    rendered (apply-r05 node (assoc ctx :sheet-id (random-uuid)))
                    prepend (:instruction rendered)
                    caution-lines (->> (str/split-lines prepend)
                                       (filter #(re-find #"(?i)fell back|caution|degraded|reranker" %)))
                    mint-invite-lines (->> (str/split-lines prepend)
                                           (filter #(re-find #"(?i)mint|specialize|adopt|adapt|novel" %)))]
                (println "  rendered prepend chars:" (count prepend))
                (println "  >>> CAUTION LINES (verbatim) <<<")
                (doseq [l caution-lines] (println "  |" l))
                (println "  >>> MINT-INVITATION LINES (verbatim) — does fallback still invite minting? <<<")
                (doseq [l mint-invite-lines] (println "  |" l))
                (println "  caution-present? =" (boolean (seq caution-lines))
                         " mint-invite-present-under-fallback? =" (boolean (seq mint-invite-lines)))))
            (reset! collected (assoc @collected ::fallback @fallback-recs)))

          ;; ================================================================
          ;; SUMMARY TABLES (the deliverable)
          ;; ================================================================
          (let [recs @collected]
            (println "\n\n================ SUMMARY: DEEPENING MARGINS (child vs parent) ================")
            (println (format "%-20s %-30s %-8s %-6s | %-16s %-8s %-6s | %-8s %s"
                             "task" "child(behavioral)" "c-conf" "c-rank" "parent" "p-conf" "p-rank" "delta" "beats-parent?"))
            (doseq [[label _] child-targeted-tasks]
              (let [rec (get recs label)
                    bs (:behaviors rec)
                    child-id (task->expected-child label)
                    parent-id (child-id->parent child-id)
                    cb (behavior-by-id bs child-id)
                    pb (behavior-by-id bs parent-id)
                    c-conf (some-> cb :confidence double)
                    p-conf (some-> pb :confidence double)
                    c-rank (rank-of bs child-id)
                    p-rank (rank-of bs parent-id)
                    delta (when (and c-conf p-conf) (- c-conf p-conf))
                    beats (cond
                            (and c-rank p-rank) (< c-rank p-rank)
                            (and c-rank (nil? p-rank)) true
                            :else false)]
                (println (format "%-20s %-30s %-8s %-6s | %-16s %-8s %-6s | %-8s %s"
                                 label
                                 (if cb (display-name child-id) "(child absent)")
                                 (if c-conf (format "%.3f" c-conf) "-")
                                 (or c-rank "-")
                                 (if pb (display-name parent-id) "(parent absent)")
                                 (if p-conf (format "%.3f" p-conf) "-")
                                 (or p-rank "-")
                                 (if delta (format "%+.3f" delta) "-")
                                 (if beats "YES" "NO")))))

            (println "\n================ SUMMARY: NON-CHILD CODING (must ride parent, no child) ================")
            (doseq [[label _] non-child-coding-tasks]
              (let [rec (get recs label)
                    top (:top-behavior rec)
                    child? (boolean (seq (:child-hits rec)))]
                (println (format "  %-16s top-behavior=%-22s top-is-child?=%s  struct-assigned=%s"
                                 label (display-name (:behavior-id top)) child?
                                 (display-name (:struct-assigned rec))))))

            (println "\n================ SUMMARY: OOD FORCE-FIT GUARD ================")
            (let [ood-recs (map #(get recs (first %)) ood-tasks)
                  force-fits (filter #(seq (:child-hits %)) ood-recs)]
              (doseq [[label _] ood-tasks]
                (let [rec (get recs label)]
                  (println (format "  %-18s top-behavior=%-22s child-force-fit?=%s"
                                   label (display-name (:behavior-id (:top-behavior rec)))
                                   (boolean (seq (:child-hits rec)))))))
              (println (format "  OOD FORCE-FIT RATE = %d/%d" (count force-fits) (count ood-tasks))))

            (println "\n================ SUMMARY: MINT RATE (normal) ================")
            (let [all-labels (map first (concat child-targeted-tasks non-child-coding-tasks ood-tasks))
                  behav-mints (filter (fn [l] (let [r (get recs l)] (some :was-fresh-mint? (:behaviors r)))) all-labels)
                  struct-mints (filter (fn [l] (:struct-mint? (get recs l))) all-labels)]
              (println "  tasks total:" (count all-labels))
              (println "  behavioral fresh-mint tasks:" (count behav-mints) "->" (pr-str behav-mints))
              (println "  structural fresh-mint tasks:" (count struct-mints) "->" (pr-str struct-mints)))

            (println "\n================ SUMMARY: MINT RATE UNDER FALLBACK ================")
            (let [fb (::fallback recs)]
              (doseq [[label r] (sort-by first fb)]
                (println (format "  %-18s fallback?=%s top-mint?=%s top=%s force-fit-child?=%s"
                                 label (:fallback? r) (:top-mint? r) (:top-name r) (:any-child? r))))
              (let [fb-mints (filter (fn [[_ r]] (:top-mint? r)) fb)]
                (println (format "  FALLBACK fresh-mint rate = %d/%d  (spurious-novel-task risk)"
                                 (count fb-mints) (count fb))))))

          (println "\n=== DONE ===")))
      (catch Throwable t
        (println "ERROR:" (.getMessage t))
        (.printStackTrace t))
      (finally
        (try (runner/stop!) (catch Throwable _ nil))
        (try (colbert/stop-bridge!) (catch Throwable _ nil))
        (shutdown-agents)))))
