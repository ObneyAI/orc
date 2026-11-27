(ns ai.obney.orc.mcp-sheet-builder.core.relationships
  "Detect relationships between MCP tools.

   Relationship types:
   - :sequential - Output of A feeds input of B (data flow)
   - :parallel - Same input type, independent operations
   - :alternative - Similar capabilities (substitutable)
   - :complementary - CRUD pairs, natural workflows
   - :refinement - Transform + validate chains")

;; ============================================================================
;; Relationship Detection
;; ============================================================================

(defn- shares-input-type?
  "Check if two tools accept similar input types."
  [tool-a tool-b]
  (let [schema-a (:input-schema tool-a)
        schema-b (:input-schema tool-b)]
    (when (and schema-a schema-b)
      (let [props-a (set (keys (get schema-a "properties")))
            props-b (set (keys (get schema-b "properties")))]
        (seq (clojure.set/intersection props-a props-b))))))

(defn- capability-overlap
  "Calculate capability overlap between tools."
  [tool-a tool-b]
  (let [caps-a (:capabilities tool-a)
        caps-b (:capabilities tool-b)
        overlap (clojure.set/intersection caps-a caps-b)]
    (when (seq overlap)
      {:overlap overlap
       :ratio (/ (count overlap)
                 (max (count caps-a) (count caps-b)))})))

(defn- detect-sequential
  "Detect potential sequential relationships (output → input chains)."
  [tools]
  (let [retrieval-tools (filter #(contains? (:capabilities %) :retrieval) tools)
        transform-tools (filter #(contains? (:capabilities %) :transform) tools)
        generate-tools (filter #(contains? (:capabilities %) :generate) tools)]
    (concat
     (for [r retrieval-tools
           t transform-tools]
       {:type :sequential
        :from (:name r)
        :to (:name t)
        :confidence 0.7
        :reason "Retrieval feeds transformation"})
     (for [t transform-tools
           g generate-tools]
       {:type :sequential
        :from (:name t)
        :to (:name g)
        :confidence 0.6
        :reason "Transformed data for generation"})
     (for [r retrieval-tools
           g generate-tools]
       {:type :sequential
        :from (:name r)
        :to (:name g)
        :confidence 0.8
        :reason "Retrieved data for generation"}))))

(defn- detect-parallel
  "Detect parallel relationships (same input, independent work)."
  [tools]
  (for [i (range (count tools))
        j (range (inc i) (count tools))
        :let [tool-a (nth tools i)
              tool-b (nth tools j)]
        :when (and (shares-input-type? tool-a tool-b)
                   (:idempotent? tool-a)
                   (:idempotent? tool-b))]
    {:type :parallel
     :from (:name tool-a)
     :to (:name tool-b)
     :confidence 0.6
     :reason "Same input type, both idempotent"}))

(defn- detect-alternative
  "Detect alternative relationships (substitutable tools)."
  [tools]
  (for [i (range (count tools))
        j (range (inc i) (count tools))
        :let [tool-a (nth tools i)
              tool-b (nth tools j)
              overlap (capability-overlap tool-a tool-b)]
        :when (and overlap (> (:ratio overlap) 0.5))]
    {:type :alternative
     :from (:name tool-a)
     :to (:name tool-b)
     :confidence (:ratio overlap)
     :reason (str "Shared capabilities: " (:overlap overlap))}))

(defn- detect-complementary
  "Detect complementary relationships (CRUD pairs, natural workflows)."
  [tools]
  (let [create-tools (filter #(contains? (:capabilities %) :create) tools)
        read-tools (filter #(or (contains? (:capabilities %) :retrieval)
                                (contains? (:capabilities %) :search)) tools)
        update-tools (filter #(contains? (:capabilities %) :update) tools)
        delete-tools (filter #(contains? (:capabilities %) :delete) tools)]
    (concat
     (for [c create-tools
           r read-tools]
       {:type :complementary
        :from (:name c)
        :to (:name r)
        :confidence 0.8
        :reason "Create-Read pair"})
     (for [r read-tools
           u update-tools]
       {:type :complementary
        :from (:name r)
        :to (:name u)
        :confidence 0.7
        :reason "Read-Update pair"})
     (for [r read-tools
           d delete-tools]
       {:type :complementary
        :from (:name r)
        :to (:name d)
        :confidence 0.6
        :reason "Read-Delete pair"}))))

(defn- detect-refinement
  "Detect refinement relationships (transform + validate chains)."
  [tools]
  (let [transform-tools (filter #(contains? (:capabilities %) :transform) tools)
        validate-tools (filter #(contains? (:capabilities %) :validate) tools)]
    (for [t transform-tools
          v validate-tools]
      {:type :refinement
       :from (:name t)
       :to (:name v)
       :confidence 0.75
       :reason "Transform-Validate chain"})))

;; ============================================================================
;; Public API
;; ============================================================================

(defn detect-relationships
  "Detect all relationships between analyzed tools.

   Returns a vector of relationship maps with:
   - :type - Relationship type
   - :from - Source tool name
   - :to - Target tool name
   - :confidence - Confidence score (0-1)
   - :reason - Human-readable explanation"
  [analyzed-tools]
  (let [rels (concat
              (detect-sequential analyzed-tools)
              (detect-parallel analyzed-tools)
              (detect-alternative analyzed-tools)
              (detect-complementary analyzed-tools)
              (detect-refinement analyzed-tools))]
    (->> rels
         (filter #(> (:confidence %) 0.5))
         (sort-by :confidence >)
         vec)))

(defn group-by-relationship-type
  "Group relationships by type."
  [relationships]
  (group-by :type relationships))

(defn tool-graph
  "Build a graph representation of tool relationships.

   Returns a map of tool-name → {:outgoing [...] :incoming [...]}"
  [relationships]
  (reduce
   (fn [acc {:keys [from to] :as rel}]
     (-> acc
         (update-in [from :outgoing] (fnil conj []) rel)
         (update-in [to :incoming] (fnil conj []) rel)))
   {}
   relationships))
