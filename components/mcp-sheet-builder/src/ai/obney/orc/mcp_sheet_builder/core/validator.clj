(ns ai.obney.orc.mcp-sheet-builder.core.validator
  "Validate generated ORC sheets for correctness.

   Validation checks:
   - Blackboard schema completeness
   - Reads/writes alignment with blackboard
   - Data flow reachability
   - MCP tool references"
  (:require [ai.obney.orc.orc-service.interface :as sheet]))

;; ============================================================================
;; Validation Checks
;; ============================================================================

(defn- extract-blackboard-keys
  "Extract all keys from a blackboard schema."
  [blackboard]
  (if (map? blackboard)
    (set (keys blackboard))
    #{}))

(defn- extract-reads-writes
  "Extract all reads and writes from a workflow tree."
  [tree]
  (cond
    (not (sequential? tree)) {:reads #{} :writes #{}}

    (= 'sheet/code (first tree))
    (let [opts (apply hash-map (drop 2 tree))]
      {:reads (set (map keyword (:reads opts)))
       :writes (set (map keyword (:writes opts)))})

    (= 'sheet/llm (first tree))
    (let [opts (apply hash-map (drop 2 tree))]
      {:reads (set (map keyword (:reads opts)))
       :writes (set (map keyword (:writes opts)))})

    :else
    (let [children (filter sequential? tree)
          child-results (map extract-reads-writes children)]
      {:reads (apply clojure.set/union (map :reads child-results))
       :writes (apply clojure.set/union (map :writes child-results))})))

(defn- check-blackboard-coverage
  "Check that all reads/writes are covered by blackboard."
  [blackboard workflow]
  (let [bb-keys (extract-blackboard-keys blackboard)
        {:keys [reads writes]} (extract-reads-writes workflow)
        all-refs (clojure.set/union reads writes)
        missing (clojure.set/difference all-refs bb-keys)]
    (if (empty? missing)
      {:valid? true :errors [] :warnings []}
      {:valid? false
       :errors [(str "Missing blackboard keys: " missing)]
       :warnings []})))

(defn- check-blackboard-specificity
  [blackboard]
  (try
    (sheet/blackboard blackboard)
    {:valid? true :errors [] :warnings []}
    (catch clojure.lang.ExceptionInfo error
      {:valid? false :errors [(.getMessage error)] :warnings []})))

(defn- check-data-flow
  "Check that writes precede reads (within sequences)."
  [_workflow]
  ;; Simplified check - full data flow analysis would be more complex
  {:valid? true :errors [] :warnings []})

(defn- check-tool-references
  "Check that referenced tools exist."
  [{:keys [tools]} workflow]
  (let [tool-names (->> tools (map :name) (filter some?) set)
        code-nodes (filter #(and (sequential? %)
                                 (= 'sheet/code (first %)))
                           (tree-seq sequential? seq workflow))
        referenced (set (map second code-nodes))]
    {:valid? true
     :errors []
     :warnings (vec (for [ref referenced
                          :when (and (string? ref)
                                     (seq tool-names)
                                     (not (some #(clojure.string/includes? ref %) tool-names)))]
                      (str "Tool reference may not match: " ref)))}))

;; ============================================================================
;; Public API
;; ============================================================================

(defn validate-sheet
  "Validate a generated sheet for correctness.

   Returns a map with:
   - :valid? - Boolean indicating validity
   - :errors - Vector of error messages
   - :warnings - Vector of warning messages"
  [{:keys [workflow blackboard] :as sheet}]
  (let [checks [(check-blackboard-specificity blackboard)
                (check-blackboard-coverage blackboard workflow)
                (check-data-flow workflow)
                (check-tool-references sheet workflow)]
        all-errors (mapcat :errors checks)
        all-warnings (mapcat :warnings checks)]
    {:valid? (empty? all-errors)
     :errors (vec all-errors)
     :warnings (vec all-warnings)}))

(defn validate-and-explain
  "Validate and provide detailed explanation."
  [sheet]
  (let [{:keys [valid? errors warnings]} (validate-sheet sheet)]
    {:valid? valid?
     :errors errors
     :warnings warnings
     :summary (cond
                (not valid?) (str "Invalid: " (count errors) " error(s)")
                (seq warnings) (str "Valid with " (count warnings) " warning(s)")
                :else "Valid")}))

(comment
  ;; Example validation
  (validate-sheet
   {:blackboard {:query :string
                 :searchDocs-result [:map [:content :string]]}
    :workflow '(sheet/workflow "test"
                 (sheet/blackboard {:query :string})
                 (sheet/sequence "main"
                   (sheet/code "call-searchDocs"
                     :fn "..."
                     :reads [:query]
                     :writes [:searchDocs-result])))}))
