(ns ai.obney.orc.mcp-sheet-builder.core.analyzer
  "Analyze MCP tools to extract semantic meaning.

   Extracts:
   - Capability tags (:retrieval, :search, :create, :transform, etc.)
   - Semantic categories (data-access, transformation, side-effect)
   - IO signatures for relationship detection
   - Idempotency classification"
  (:require [ai.obney.orc.mcp-sheet-builder.core.schema-converter :as schema-converter]
            [clojure.string :as str]))

;; ============================================================================
;; Capability Detection
;; ============================================================================

(def capability-keywords
  "Keywords that indicate specific capabilities."
  {:retrieval #{"get" "fetch" "read" "retrieve" "load" "find" "lookup"}
   :search    #{"search" "query" "filter" "find" "discover" "explore"}
   :create    #{"create" "add" "insert" "new" "make" "generate" "produce"}
   :update    #{"update" "modify" "change" "edit" "set" "patch"}
   :delete    #{"delete" "remove" "drop" "clear" "purge"}
   :transform #{"transform" "convert" "format" "parse" "extract" "map" "reduce"}
   :validate  #{"validate" "check" "verify" "test" "assert"}
   :generate  #{"generate" "synthesize" "compose" "write" "summarize"}
   :aggregate #{"aggregate" "combine" "merge" "collect" "list" "all"}
   :route     #{"route" "dispatch" "forward" "redirect"}})

(defn- extract-words
  "Extract words from a tool name and description."
  [{:keys [name description]}]
  (let [text (str name " " (or description ""))]
    (->> (str/split (str/lower-case text) #"[^a-z]+")
         (filter #(> (count %) 2))
         set)))

(defn- detect-capabilities
  "Detect capabilities from tool name and description."
  [tool]
  (let [words (extract-words tool)]
    (->> capability-keywords
         (filter (fn [[_cap keywords]]
                   (some words keywords)))
         (map first)
         set)))

;; ============================================================================
;; Category Classification
;; ============================================================================

(defn- classify-category
  "Classify a tool into a semantic category based on its capabilities."
  [capabilities]
  (cond
    (some capabilities #{:create :update :delete})
    :data-mutation

    (some capabilities #{:retrieval :search :aggregate})
    :data-access

    (some capabilities #{:transform :validate})
    :transformation

    (some capabilities #{:generate})
    :side-effect

    :else
    :data-access))

;; ============================================================================
;; Idempotency Detection
;; ============================================================================

(defn- idempotent?
  "Determine if a tool is idempotent (safe to retry/call multiple times)."
  [capabilities]
  (boolean
   (and (not-any? capabilities #{:create :delete})
        (or (some capabilities #{:retrieval :search :validate :transform})
            (empty? capabilities)))))

;; ============================================================================
;; Analysis
;; ============================================================================

(defn analyze-tool
  "Analyze a single MCP tool.

   Returns a map with:
   - Original tool fields
   - :capabilities - Set of capability tags
   - :category - Semantic category
   - :malli-input - Malli schema for input
   - :malli-output - Malli schema for output
   - :idempotent? - Whether the tool is idempotent"
  [{:keys [name description inputSchema outputSchema] :as tool}]
  (let [capabilities (detect-capabilities tool)
        category (classify-category capabilities)
        malli-input (when inputSchema
                      (schema-converter/json-schema->malli inputSchema))]
    {:name name
     :description description
     :capabilities (if (empty? capabilities) #{:retrieval} capabilities)
     :category category
     :input-schema inputSchema
     :output-schema outputSchema
     :malli-input malli-input
     :malli-output nil
     :idempotent? (idempotent? capabilities)}))

(defn analyze-tools
  "Analyze a collection of MCP tools.

   Options:
   - :cache? - Whether to use cached analysis (default true)"
  ([tools]
   (analyze-tools tools {}))
  ([tools _opts]
   (mapv analyze-tool tools)))

;; ============================================================================
;; Exploration Budget
;; ============================================================================

(defn exploration-budget
  "Calculate the exploration budget based on tool count.

   Returns:
   - :patterns - Number of base patterns to explore
   - :creative - Extra budget for creative combinations
   - :total - Total exploration budget"
  [tool-count]
  (let [base-patterns (cond
                        (<= tool-count 3) 3
                        (<= tool-count 7) 6
                        :else 10)
        creative-budget (int (Math/ceil (* tool-count 0.3)))]
    {:patterns base-patterns
     :creative creative-budget
     :total (+ base-patterns creative-budget)}))
