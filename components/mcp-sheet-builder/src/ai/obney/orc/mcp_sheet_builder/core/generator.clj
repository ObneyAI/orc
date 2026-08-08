(ns ai.obney.orc.mcp-sheet-builder.core.generator
  "Generate ORC DSL code from analyzed tools and selected patterns.

   Produces valid ORC workflow definitions including:
   - Blackboard schema
   - Node tree structure
   - MCP tool executor wrappers

   Phase 1 Enhancement: Now uses detected relationships to:
   - Topologically sort sequential pipelines
   - Group parallel tools correctly
   - Pair generators with validators via refinement relationships"
  (:require [ai.obney.orc.mcp-sheet-builder.core.schema-converter :as schema-converter]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.pprint :as pprint]))

;; ============================================================================
;; Blackboard Generation
;; ============================================================================

(defn generate-blackboard-schema
  "Generate blackboard schema from analyzed tools."
  [tools]
  (let [input-schemas (reduce
                       (fn [acc {:keys [name input-schema]}]
                         (if input-schema
                           (merge acc (schema-converter/tool-input-schema->malli input-schema))
                           acc))
                       {}
                       tools)
        output-schemas (reduce
                        (fn [acc {:keys [name output-schema]}]
                          (assoc acc (keyword (str name "-result"))
                                 (or (schema-converter/convert-schema output-schema)
                                     (throw (ex-info
                                             (str "MCP tool " name " does not declare an output schema; "
                                                  "provide the most specific schema possible for the value's intent")
                                             {:tool name})))))
                        {}
                        tools)]
    (merge input-schemas output-schemas)))

;; ============================================================================
;; Relationship-Aware Helpers (Phase 1)
;; ============================================================================

(defn- build-adjacency-map
  "Build an adjacency map from sequential relationships.
   Returns {from-tool -> [to-tools...]}"
  [relationships]
  (->> relationships
       (filter #(= :sequential (:type %)))
       (reduce (fn [acc {:keys [from to]}]
                 (update acc from (fnil conj []) to))
               {})))

(defn- build-in-degree-map
  "Build a map of tool-name -> number of incoming edges."
  [tools relationships]
  (let [tool-names (set (map :name tools))
        sequential-rels (filter #(= :sequential (:type %)) relationships)]
    (reduce
     (fn [acc {:keys [to]}]
       (if (tool-names to)
         (update acc to (fnil inc 0))
         acc))
     ;; Initialize all tools with 0 in-degree
     (zipmap tool-names (repeat 0))
     sequential-rels)))

(defn topological-sort-by-relationships
  "Sort tools by :sequential relationships - tools that feed others come first.
   Uses Kahn's algorithm for topological sort.
   Falls back to original order if no sequential relationships exist."
  [tools relationships]
  (let [tool-names (set (map :name tools))
        sequential-rels (filter #(and (= :sequential (:type %))
                                      (tool-names (:from %))
                                      (tool-names (:to %)))
                                relationships)]
    (if (empty? sequential-rels)
      ;; No sequential relationships - return original order
      tools
      (let [adj-map (build-adjacency-map sequential-rels)
            in-degree (build-in-degree-map tools relationships)
            ;; Start with nodes that have no incoming edges
            initial-queue (filter #(zero? (get in-degree % 0)) (map :name tools))
            ;; Kahn's algorithm
            sorted-names (loop [queue (vec initial-queue)
                                degrees in-degree
                                result []]
                           (if (empty? queue)
                             result
                             (let [node (first queue)
                                   neighbors (get adj-map node [])
                                   new-degrees (reduce
                                                (fn [d n] (update d n dec))
                                                degrees
                                                neighbors)
                                   new-queue (into (vec (rest queue))
                                                   (filter #(zero? (get new-degrees %))
                                                           neighbors))]
                               (recur new-queue new-degrees (conj result node)))))
            ;; Map sorted names back to tools
            tool-by-name (zipmap (map :name tools) tools)]
        ;; Return sorted tools, including any not in the sort (no relationships)
        (let [sorted (keep tool-by-name sorted-names)
              unsorted-names (set/difference (set (map :name tools))
                                             (set sorted-names))
              unsorted (filter #(unsorted-names (:name %)) tools)]
          (into (vec sorted) unsorted))))))

(defn group-parallel-tools
  "Group tools by :parallel relationships.
   Returns {:parallel [tools-that-can-run-together]
            :sequential [tools-that-run-after]}"
  [tools relationships]
  (let [tool-names (set (map :name tools))
        parallel-rels (filter #(and (= :parallel (:type %))
                                    (tool-names (:from %))
                                    (tool-names (:to %)))
                              relationships)]
    (if (empty? parallel-rels)
      ;; No parallel relationships - all tools are parallel (original behavior)
      {:parallel tools
       :sequential []}
      ;; Find tools connected by parallel relationships
      (let [parallel-names (reduce
                            (fn [acc {:keys [from to]}]
                              (-> acc (conj from) (conj to)))
                            #{}
                            parallel-rels)
            parallel-tools (filter #(parallel-names (:name %)) tools)
            other-tools (remove #(parallel-names (:name %)) tools)]
        {:parallel parallel-tools
         :sequential other-tools}))))

(defn find-refinement-pairs
  "Find generator-validator pairs from refinement relationships.
   Returns [{:generator tool :validator tool}...]"
  [tools relationships]
  (let [tool-by-name (zipmap (map :name tools) tools)
        refinement-rels (filter #(= :refinement (:type %)) relationships)]
    (->> refinement-rels
         (keep (fn [{:keys [from to confidence]}]
                 (when-let [gen-tool (tool-by-name from)]
                   (when-let [val-tool (tool-by-name to)]
                     {:generator gen-tool
                      :validator val-tool
                      :confidence confidence}))))
         (sort-by :confidence >))))

(defn get-sequential-targets
  "Get tools that are targets of sequential relationships from a given tool."
  [tool-name relationships]
  (->> relationships
       (filter #(and (= :sequential (:type %))
                     (= tool-name (:from %))))
       (map :to)
       set))

;; ============================================================================
;; Node Generation
;; ============================================================================

(defn generate-code-node
  "Generate a code node that calls an MCP tool."
  [tool-name input-keys output-key]
  `(~'sheet/code ~(str "call-" tool-name)
     :fn "ai.obney.orc.mcp-sheet-builder.core.executors/call-mcp-tool"
     :reads ~(vec (map keyword input-keys))
     :writes ~[(keyword output-key)]))

(defn generate-llm-node
  "Generate an LLM node for synthesis/generation."
  [node-name instruction input-keys output-keys]
  `(~'sheet/llm ~node-name
     :model "google/gemini-2.5-flash"
     :instruction ~instruction
     :reads ~(vec (map keyword input-keys))
     :writes ~(vec (map keyword output-keys))))

;; ============================================================================
;; Pattern-specific Generation
;; ============================================================================

(defn generate-sequential-pipeline
  "Generate a sequential pipeline workflow.
   Uses topological sort based on :sequential relationships."
  [tools relationships]
  (let [sorted-tools (topological-sort-by-relationships tools relationships)
        code-nodes (for [tool sorted-tools]
                     (let [input-keys (keys (get-in tool [:input-schema "properties"]))
                           output-key (str (:name tool) "-result")]
                       (generate-code-node (:name tool) input-keys output-key)))]
    `(~'sheet/sequence "pipeline"
       ~@code-nodes)))

(defn generate-parallel-fan-out
  "Generate a parallel fan-out workflow.
   Groups tools by :parallel relationships."
  [tools relationships]
  (let [{:keys [parallel sequential]} (group-parallel-tools tools relationships)
        parallel-tools (if (empty? parallel) tools parallel)
        parallel-nodes (for [tool parallel-tools]
                         (let [input-keys (keys (get-in tool [:input-schema "properties"]))
                               output-key (str (:name tool) "-result")]
                           (generate-code-node (:name tool) input-keys output-key)))
        result-keys (mapv #(keyword (str (:name %) "-result")) parallel-tools)]
    `(~'sheet/sequence "fan-out-gather"
       (~'sheet/parallel "fan-out"
         ~@parallel-nodes)
       (~'sheet/llm "synthesize"
         :model "google/gemini-2.5-flash"
         :instruction "Synthesize the results from all parallel operations."
         :reads ~result-keys
         :writes [:synthesis-result]))))

(defn generate-research-compilation
  "Generate a research compilation workflow.
   Orders searchers by sequential relationships."
  [tools relationships]
  (let [searchers (filter #(some (:capabilities %) #{:search :retrieval}) tools)
        sorted-searchers (topological-sort-by-relationships searchers relationships)
        search-nodes (for [tool sorted-searchers]
                       (let [input-keys (keys (get-in tool [:input-schema "properties"]))
                             output-key (str (:name tool) "-result")]
                         (generate-code-node (:name tool) input-keys output-key)))
        result-keys (mapv #(keyword (str (:name %) "-result")) sorted-searchers)]
    `(~'sheet/sequence "research"
       (~'sheet/parallel "gather"
         ~@search-nodes)
       (~'sheet/llm "compile"
         :model "google/gemini-2.5-flash"
         :instruction "Compile and synthesize the research findings into a coherent summary."
         :reads ~result-keys
         :writes [:compiled-research]))))

(defn generate-generator-critic
  "Generate a generator-critic workflow.
   Uses refinement relationships to pair generators with validators."
  [tools relationships]
  (let [;; Try to find pairs via refinement relationships first
        refinement-pairs (find-refinement-pairs tools relationships)
        ;; Fall back to capability matching if no relationships
        generators (filter #(contains? (:capabilities %) :generate) tools)
        validators (filter #(contains? (:capabilities %) :validate) tools)
        ;; Use relationship-based pair if available
        {:keys [generator validator]} (if (seq refinement-pairs)
                                         (first refinement-pairs)
                                         {:generator (first generators)
                                          :validator (first validators)})]
    `(~'sheet/sequence "generate-and-validate"
       ~(if generator
          (generate-code-node (:name generator)
                              (keys (get-in generator [:input-schema "properties"]))
                              (str (:name generator) "-result"))
          `(~'sheet/llm "generate"
             :model "google/gemini-2.5-flash"
             :instruction "Generate the requested content."
             :reads [:input]
             :writes [:generated-content]))
       ~(if validator
          (generate-code-node (:name validator)
                              [(str (or (:name generator) "generated") "-content")]
                              "validation-result")
          `(~'sheet/llm "critique"
             :model "google/gemini-2.5-flash"
             :instruction "Critique and validate the generated content."
             :reads [~(keyword (str (or (:name generator) "generated") "-result"))]
             :writes [:critique-result])))))

;; ============================================================================
;; Main Generation
;; ============================================================================

(defn generate-workflow-tree
  "Generate the workflow tree based on pattern."
  [tools relationships pattern-key]
  (case pattern-key
    :sequential-pipeline (generate-sequential-pipeline tools relationships)
    :parallel-fan-out (generate-parallel-fan-out tools relationships)
    :research-compilation (generate-research-compilation tools relationships)
    :generator-critic (generate-generator-critic tools relationships)
    ;; Default to sequential
    (generate-sequential-pipeline tools relationships)))

(defn generate-workflow-name
  "Generate a workflow name from tools."
  [tools]
  (let [names (mapv :name tools)]
    (str "mcp-workflow-" (str/join "-" (take 3 names)))))

(defn generate-workflow
  "Generate a complete ORC workflow."
  [tools relationships pattern-key]
  (let [workflow-name (generate-workflow-name tools)
        blackboard (generate-blackboard-schema tools)
        tree (generate-workflow-tree tools relationships pattern-key)]
    `(~'sheet/workflow ~workflow-name
       (~'sheet/blackboard ~blackboard)
       ~tree)))

;; ============================================================================
;; Public API
;; ============================================================================

(defn generate-sheet
  "Generate an ORC workflow DSL from analyzed tools and pattern selection.

   Returns a map with:
   - :workflow - The generated ORC workflow form
   - :blackboard - Generated blackboard schema
   - :code - String representation of the DSL"
  [{:keys [tools relationships patterns]} {:keys [pattern]}]
  (let [pattern-key (or pattern
                        (-> patterns first :pattern)
                        :sequential-pipeline)
        blackboard (generate-blackboard-schema tools)
        workflow (generate-workflow tools relationships pattern-key)
        code (with-out-str (pprint/pprint workflow))]
    {:workflow workflow
     :blackboard blackboard
     :code code
     :pattern pattern-key
     :tools (mapv :name tools)}))

(defn workflow->string
  "Convert a workflow form to a formatted string."
  [workflow]
  (with-out-str (pprint/pprint workflow)))

;; ============================================================================
;; Data Structure Generation (for direct build-workflow!)
;; ============================================================================

(defn generate-code-node-data
  "Generate a code node as a data structure (not quoted form)."
  [tool-name input-keys output-key]
  {:node-type :leaf
   :name (str "call-" tool-name)
   :executor :code
   :fn "ai.obney.orc.mcp-sheet-builder.core.executors/call-mcp-tool"
   :options {:tool-name tool-name}
   :reads (vec (map keyword input-keys))
   :writes [(keyword output-key)]})

(defn generate-llm-node-data
  "Generate an LLM node as a data structure.

   If intent is provided, enriches the instruction with goal-specific context:
   - User's primary goal
   - Secondary goals to focus on
   - Keywords to emphasize"
  ([node-name instruction input-keys output-keys]
   (generate-llm-node-data node-name instruction input-keys output-keys nil))
  ([node-name instruction input-keys output-keys intent]
   (let [;; Build intent-specific instruction if intent is provided
         enriched-instruction
         (if (and intent (map? intent))
           (let [primary (:primary-goal intent)
                 secondary (:secondary-goals intent)
                 keywords (:keywords intent)
                 domain (:domain intent)]
             (str instruction
                  (when primary
                    (str "\n\nUser's goal: " primary))
                  (when (and secondary (seq secondary))
                    (str "\nAlso focus on: " (str/join ", " secondary)))
                  (when (and keywords (seq keywords))
                    (str "\nKey terms to emphasize: " (str/join ", " keywords)))
                  (when domain
                    (str "\nDomain context: " domain))))
           instruction)]
     {:node-type :leaf
      :name node-name
      :executor :ai
      :model "google/gemini-2.5-flash"
      :instruction enriched-instruction
      :reads (vec (map keyword input-keys))
      :writes (vec (map keyword output-keys))})))

(defn generate-repl-researcher-node-data
  "Generate a repl-researcher node as a data structure."
  [node-name instruction input-keys output-keys mcp-tools & {:keys [model max-iterations]}]
  {:node-type :repl-researcher
   :name node-name
   :model (or model "google/gemini-2.5-flash")
   :instruction instruction
   :reads (vec (map keyword input-keys))
   :writes (vec (map keyword output-keys))
   :mcp-tools (vec mcp-tools)
   :max-iterations (or max-iterations 5)})

(defn generate-sequential-pipeline-data
  "Generate a sequential pipeline workflow as data structure.
   Uses topological sort based on :sequential relationships."
  [tools relationships]
  (let [;; Sort tools by data flow relationships
        sorted-tools (topological-sort-by-relationships tools relationships)
        code-nodes (for [tool sorted-tools]
                     (let [input-keys (keys (get-in tool [:input-schema "properties"]))
                           output-key (str (:name tool) "-result")]
                       (generate-code-node-data (:name tool) input-keys output-key)))]
    {:node-type :sequence
     :name "pipeline"
     :children (vec code-nodes)}))

(defn generate-parallel-fan-out-data
  "Generate a parallel fan-out workflow as data structure.
   Groups tools by :parallel relationships - truly parallel tools run together,
   synthesizers/generators run after.

   If intent is provided, the synthesis LLM node gets goal-specific instructions."
  ([tools relationships]
   (generate-parallel-fan-out-data tools relationships nil))
  ([tools relationships intent]
   (let [{:keys [parallel sequential]} (group-parallel-tools tools relationships)
         ;; Tools with :generate capability are synthesizers
         synthesizers (filter #(contains? (:capabilities %) :generate) sequential)
         ;; Generate parallel nodes for tools that can run together
         parallel-nodes (for [tool parallel]
                          (let [input-keys (keys (get-in tool [:input-schema "properties"]))
                                output-key (str (:name tool) "-result")]
                            (generate-code-node-data (:name tool) input-keys output-key)))
         ;; If no parallel relationships detected, use all tools as parallel
         final-parallel-nodes (if (empty? parallel-nodes)
                                (for [tool tools]
                                  (let [input-keys (keys (get-in tool [:input-schema "properties"]))
                                        output-key (str (:name tool) "-result")]
                                    (generate-code-node-data (:name tool) input-keys output-key)))
                                parallel-nodes)
         result-keys (mapv #(keyword (str (:name %) "-result"))
                           (if (empty? parallel) tools parallel))]
     {:node-type :sequence
      :name "fan-out-gather"
      :children [{:node-type :parallel
                  :name "fan-out"
                  :children (vec final-parallel-nodes)}
                 ;; Use a dedicated synthesizer tool if available, otherwise LLM
                 (if-let [synth (first synthesizers)]
                   (generate-code-node-data (:name synth)
                                            result-keys
                                            (str (:name synth) "-result"))
                   (generate-llm-node-data "synthesize"
                                           "Synthesize the results from all parallel operations."
                                           result-keys
                                           [:synthesis-result]
                                           intent))]})))

(defn generate-research-compilation-data
  "Generate a research compilation workflow as data structure.
   Orders searchers by sequential relationships (more foundational queries first),
   and pairs with generators via relationships.

   If intent is provided, the compilation LLM node gets goal-specific instructions."
  ([tools relationships]
   (generate-research-compilation-data tools relationships nil))
  ([tools relationships intent]
   (let [searchers (filter #(some (:capabilities %) #{:search :retrieval}) tools)
         generators (filter #(contains? (:capabilities %) :generate) tools)
         ;; Sort searchers by sequential relationships if present
         sorted-searchers (topological-sort-by-relationships searchers relationships)
         search-nodes (for [tool sorted-searchers]
                        (let [input-keys (keys (get-in tool [:input-schema "properties"]))
                              output-key (str (:name tool) "-result")]
                          (generate-code-node-data (:name tool) input-keys output-key)))
         result-keys (mapv #(keyword (str (:name %) "-result")) sorted-searchers)
         ;; Use generator tool if available and has relationship to searchers
         generator-with-rel (first
                             (filter (fn [gen]
                                       (some #(and (= :sequential (:type %))
                                                   (= (:name gen) (:to %)))
                                             relationships))
                                     generators))]
     {:node-type :sequence
      :name "research"
      :children [{:node-type :parallel
                  :name "gather"
                  :children (vec search-nodes)}
                 ;; Use related generator tool or fall back to LLM
                 (if generator-with-rel
                   (generate-code-node-data (:name generator-with-rel)
                                            result-keys
                                            (str (:name generator-with-rel) "-result"))
                   (generate-llm-node-data "compile"
                                           "Compile and synthesize the research findings into a coherent summary."
                                           result-keys
                                           [:compiled-research]
                                           intent))]})))

(defn generate-repl-researcher-workflow-data
  "Generate a workflow using repl-researcher for adaptive tool use.

   If intent is provided, enhances the instruction with goal-specific guidance."
  ([tools relationships]
   (generate-repl-researcher-workflow-data tools relationships nil))
  ([tools _relationships intent]
   (let [tool-names (mapv :name tools)
         ;; Gather all input keys from all tools for the blackboard
         all-input-keys (set (mapcat #(keys (get-in % [:input-schema "properties"])) tools))
         ;; Build base instruction
         base-instruction (str "You have access to these MCP tools: " (str/join ", " tool-names)
                               ". Use them to research and answer the question. "
                               "Call tools with (tool-name {:arg \"value\"}). "
                               "When done, output FINAL_ANSWER: <your answer>")
         ;; Enrich with intent if provided
         instruction (if (and intent (map? intent))
                       (let [primary (:primary-goal intent)
                             secondary (:secondary-goals intent)
                             keywords (:keywords intent)]
                         (str base-instruction
                              (when primary
                                (str "\n\nYour primary objective: " primary))
                              (when (and secondary (seq secondary))
                                (str "\nAlso address: " (str/join ", " secondary)))
                              (when (and keywords (seq keywords))
                                (str "\nKey topics to cover: " (str/join ", " keywords)))))
                       base-instruction)]
     {:node-type :repl-researcher
      :name "researcher"
      :model "google/gemini-2.5-flash"
      :instruction instruction
      :reads [:question]
      :writes [:answer]
      :mcp-tools (vec tool-names)
      :max-iterations 5})))

(defn generate-workflow-tree-data
  "Generate the workflow tree as data structure based on pattern.

   If intent is provided, passes it through to pattern generators
   for goal-specific LLM instructions."
  ([tools relationships pattern-key]
   (generate-workflow-tree-data tools relationships pattern-key nil))
  ([tools relationships pattern-key intent]
   (case pattern-key
     :sequential-pipeline (generate-sequential-pipeline-data tools relationships)
     :parallel-fan-out (generate-parallel-fan-out-data tools relationships intent)
     :research-compilation (generate-research-compilation-data tools relationships intent)
     :repl-researcher (generate-repl-researcher-workflow-data tools relationships intent)
     ;; Default to sequential
     (generate-sequential-pipeline-data tools relationships))))

(defn generate-workflow-data
  "Generate a complete ORC workflow as data structure (for direct use with build-workflow!).

   Returns a map that matches the internal workflow format expected by orc-service:
   {:workflow-name string
    :blackboard-schema {keyword schema}
    :root-node {...node data...}}

   If intent is provided, LLM nodes will have goal-specific instructions.

   This can be passed directly to sheet/build-workflow! without eval."
  ([tools relationships pattern-key]
   (generate-workflow-data tools relationships pattern-key nil))
  ([tools relationships pattern-key intent]
   (let [workflow-name (generate-workflow-name tools)
         blackboard (generate-blackboard-schema tools)
         tree (generate-workflow-tree-data tools relationships pattern-key intent)]
     {:workflow-name workflow-name
      :blackboard-schema blackboard
      :root-node tree})))

(defn generate-sheet-data
  "Generate an ORC workflow as data structure from analyzed tools and pattern selection.

   Returns a map with:
   - :workflow-data - The workflow data structure for build-workflow!
   - :blackboard - Generated blackboard schema
   - :pattern - Selected pattern key
   - :tools - Tool names

   Options:
   - :pattern - Override pattern selection
   - :intent - Intent map from analyze-intent, used for goal-specific LLM instructions"
  [{:keys [tools relationships patterns]} {:keys [pattern intent]}]
  (let [pattern-key (or pattern
                        (-> patterns first :pattern)
                        :sequential-pipeline)
        blackboard (generate-blackboard-schema tools)
        workflow-data (generate-workflow-data tools relationships pattern-key intent)]
    {:workflow-data workflow-data
     :blackboard blackboard
     :pattern pattern-key
     :intent intent
     :tools (mapv :name tools)}))

(comment
  ;; Example generation
  (def tools
    [{:name "searchDocs"
      :capabilities #{:search}
      :input-schema {"properties" {"query" {"type" "string"}}}}
     {:name "getPage"
      :capabilities #{:retrieval}
      :input-schema {"properties" {"path" {"type" "string"}}}}
     {:name "summarize"
      :capabilities #{:generate :transform}}])

  (generate-sheet
   {:tools tools
    :relationships []
    :patterns [{:pattern :research-compilation}]}
   {:pattern :research-compilation}))
