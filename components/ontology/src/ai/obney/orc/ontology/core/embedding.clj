(ns ai.obney.orc.ontology.core.embedding
  "Embedding generation and vector operations for semantic search.

   Provides:
   - DJL-based embedding model loading (HuggingFace sentence-transformers)
   - Text embedding generation
   - Cosine similarity computation
   - Schema analysis for detecting embeddable fields
   - Concept-to-text conversion for embedding
   - Batch embedding for evolutionary builder integration

   Embeddings enable semantic search in addition to graph-based BFS,
   allowing RRF fusion of multiple retrieval signals."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [ai.obney.orc.ontology.core.field-analyzer :as fa]
            [ai.obney.grain.time.interface :as time]
            [com.brunobonacci.mulog :as mu])
  (:import [ai.djl.repository.zoo Criteria]
           [ai.djl.huggingface.translator TextEmbeddingTranslatorFactory]))

;; =============================================================================
;; Default Configuration
;; =============================================================================

(def default-model-id
  "Default embedding model - all-MiniLM-L6-v2 produces 384-dim embeddings."
  "sentence-transformers/all-MiniLM-L6-v2")

(def default-dimensions
  "Dimension count for default model."
  384)

;; =============================================================================
;; DJL Embedding Model Management
;; =============================================================================

(defonce ^{:private true :doc "Map of model-id -> loaded DJL model. Thread-safe atom."}
  embedding-models
  (atom {}))

(defn- load-embedding-model-internal
  "Load a DJL embedding model by model-id.

   Args:
     model-id: HuggingFace model identifier (e.g., 'sentence-transformers/all-MiniLM-L6-v2')

   Returns:
     Loaded DJL ZooModel"
  [model-id]
  (println "[DJL] Loading embedding model:" model-id)
  (let [model-url (str "djl://ai.djl.huggingface.pytorch/" model-id)
        criteria (-> (Criteria/builder)
                     (.setTypes String (Class/forName "[F"))
                     (.optModelUrls model-url)
                     (.optEngine "PyTorch")
                     (.optTranslatorFactory (TextEmbeddingTranslatorFactory.))
                     (.build))
        model (.loadModel criteria)]
    (println "[DJL] Model loaded successfully:" model-id)
    model))

(defn get-embedding-model
  "Get an embedding model by ID, loading it if necessary.

   Thread-safe with double-checked locking pattern.

   Args:
     model-id: Optional model identifier (default: all-MiniLM-L6-v2)

   Returns:
     Loaded DJL ZooModel"
  ([]
   (get-embedding-model default-model-id))
  ([model-id]
   (if-let [model (get @embedding-models model-id)]
     model
     (locking embedding-models
       (if-let [model (get @embedding-models model-id)]
         model
         (let [model (load-embedding-model-internal model-id)]
           (swap! embedding-models assoc model-id model)
           model))))))

(defn close-embedding-model!
  "Close and remove a specific embedding model.

   Args:
     model-id: Model identifier to close"
  [model-id]
  (locking embedding-models
    (when-let [model (get @embedding-models model-id)]
      (println "[DJL] Closing embedding model:" model-id)
      (try
        (.close model)
        (catch Exception e
          (println "[DJL] Warning closing model:" (.getMessage e))))
      (swap! embedding-models dissoc model-id)
      (println "[DJL] Model closed:" model-id))))

(defn close-all-embedding-models!
  "Close all loaded embedding models. Useful for REPL cleanup."
  []
  (locking embedding-models
    (doseq [[model-id _] @embedding-models]
      (close-embedding-model! model-id))
    (reset! embedding-models {})
    (println "[DJL] All embedding models closed.")))

;; =============================================================================
;; Vector Operations
;; =============================================================================

(defn dot-product
  "Compute dot product of two vectors."
  [a b]
  (reduce + (map * a b)))

(defn magnitude
  "Compute magnitude (L2 norm) of a vector."
  [v]
  (Math/sqrt (reduce + (map #(* % %) v))))

(defn cosine-similarity
  "Compute cosine similarity between two vectors.

   Returns:
     Similarity score in range [-1, 1], or 0.0 if either vector is zero."
  [a b]
  (let [dot (dot-product a b)
        mag-a (magnitude a)
        mag-b (magnitude b)]
    (if (or (zero? mag-a) (zero? mag-b))
      0.0
      (/ dot (* mag-a mag-b)))))

(defn normalize-vector
  "Normalize a vector to unit length."
  [v]
  (let [mag (magnitude v)]
    (if (zero? mag)
      v
      (mapv #(/ % mag) v))))

;; =============================================================================
;; Text Embedding
;; =============================================================================

(defn embed-text
  "Generate an embedding vector for text.

   Args:
     text: String to embed
     opts: Optional map with :model-id (default: all-MiniLM-L6-v2)

   Returns:
     Vector of doubles (384 dimensions for default model), or nil on error."
  ([text]
   (embed-text text {}))
  ([text {:keys [model-id] :or {model-id default-model-id}}]
   (when (and text (not (str/blank? text)))
     (try
       (let [model (get-embedding-model model-id)]
         (with-open [predictor (.newPredictor model)]
           ;; .predict returns a float[]; coerce to doubles so emitted
           ;; :ontology/concept-embedded events satisfy the [:vector :double] schema
           (mapv double (.predict predictor text))))
       (catch Exception e
         (println "[DJL] Error generating embedding:" (.getMessage e))
         nil)))))

(defn embed-texts-batch
  "Generate embeddings for multiple texts.

   Args:
     texts: Collection of strings to embed
     opts: Optional map with :model-id

   Returns:
     Vector of embedding vectors (same order as input)"
  ([texts]
   (embed-texts-batch texts {}))
  ([texts opts]
   (when (seq texts)
     (println "[DJL] Generating embeddings for" (count texts) "texts")
     (mapv #(embed-text % opts) texts))))

;; =============================================================================
;; Concept Text Preparation
;; =============================================================================

(defn concept->embedding-text
  "Convert a concept to text suitable for embedding.

   Combines specified fields into a single text string.

   Args:
     concept: Concept map with :label, :description, :indicators, etc.
     fields: Set of field keywords to include (default: #{:label :description})

   Returns:
     Combined text string for embedding"
  ([concept]
   (concept->embedding-text concept #{:label :description}))
  ([concept fields]
   (let [parts (remove str/blank?
                       [(when (contains? fields :label)
                          (:label concept))
                        (when (contains? fields :description)
                          (:description concept))
                        (when (and (contains? fields :indicators)
                                   (seq (:indicators concept)))
                          (str "Indicators: " (str/join ", " (:indicators concept))))
                        (when (and (contains? fields :triggers)
                                   (seq (:triggers concept)))
                          (str "Triggers: " (str/join ", " (:triggers concept))))])]
     (str/join ". " parts))))

(defn tree-profile->embedding-text
  "Convert a tree profile to text suitable for embedding.

   Summarizes strengths, weaknesses, and problem types.

   Args:
     profile: Tree profile map

   Returns:
     Summary text for embedding"
  [profile]
  (let [strengths (->> (:strengths profile)
                       (map :pattern)
                       (str/join ", "))
        weaknesses (->> (:weaknesses profile)
                        (map :failure)
                        (str/join ", "))
        problems (->> (:solves profile)
                      (map :problem-uri)
                      (str/join ", "))]
    (str/join ". "
              (remove str/blank?
                      [(when (seq strengths)
                         (str "Strengths: " strengths))
                       (when (seq weaknesses)
                         (str "Weaknesses: " weaknesses))
                       (when (seq problems)
                         (str "Solves: " problems))]))))

(defn evaluation-feedback->embedding-text
  "Convert evaluation feedback to text for embedding.

   Args:
     feedback: Feedback string
     dimension: Evaluation dimension name

   Returns:
     Formatted text for embedding"
  [feedback dimension]
  (str dimension " feedback: " feedback))

;; =============================================================================
;; Schema Analysis for Embeddable Fields
;; =============================================================================

(def ^:private semantic-field-names
  "Field names that likely contain semantic text worth embedding."
  #{:description :feedback :label :name :title :summary
    :content :text :message :explanation :reasoning
    :instructions :notes :comments})

(def ^:private vector-semantic-names
  "Vector field names that likely contain semantic content."
  #{:indicators :triggers :keywords :tags :reasons
    :examples :hints :suggestions})

(defn- string-schema?
  "Check if a schema represents a string type."
  [schema]
  (when schema
    (let [type-name (m/type schema)]
      (or (= type-name :string)
          (= type-name 'string)
          (= schema :string)))))

(defn- string-vector-schema?
  "Check if a schema represents a vector of strings."
  [schema]
  (when schema
    (let [type-name (m/type schema)]
      (and (= type-name :vector)
           (let [children (m/children schema)]
             (and (= 1 (count children))
                  (string-schema? (first children))))))))

(defn- has-description-property?
  "Check if schema has a :description property in metadata."
  [schema]
  (when schema
    (let [props (m/properties schema)]
      (contains? props :description))))

(defn- explain-embeddability
  "Explain why a field is embeddable."
  [field-name schema]
  (cond
    (contains? semantic-field-names field-name)
    "Semantic field name indicates natural language content"

    (contains? vector-semantic-names field-name)
    "Vector field with semantic content items"

    (has-description-property? schema)
    "Field has description metadata"

    (string-schema? schema)
    "String field that may contain text"

    (string-vector-schema? schema)
    "Vector of strings"

    :else
    "Unknown"))

(defn detect-embeddable-fields
  "Analyze a Malli schema to find fields suitable for embedding.

   Heuristics:
   - :string fields with semantic names (:description, :feedback, :label)
   - [:vector :string] fields (indicators, triggers)
   - Fields with {:description ...} metadata

   Args:
     schema: Malli schema to analyze

   Returns:
     {:embeddable-fields [:description :feedback ...]
      :reasoning {'description' 'reason...'}}

   Returns nil if schema is not a map schema."
  [schema]
  (when (and schema (= :map (m/type schema)))
    (let [entries (m/entries schema)
          embeddable (filter
                       (fn [[field-name field-schema]]
                         (or (contains? semantic-field-names field-name)
                             (contains? vector-semantic-names field-name)
                             (has-description-property? field-schema)
                             (and (string-schema? field-schema)
                                  ;; Exclude obvious non-semantic strings
                                  (not (contains? #{:id :uuid :uri :url :email :phone
                                                    :created-at :updated-at :timestamp}
                                                  field-name)))))
                       entries)]
      {:embeddable-fields (mapv first embeddable)
       :reasoning (into {}
                        (map (fn [[k v]] [k (explain-embeddability k v)])
                             embeddable))})))

(defn analyze-concept-schema
  "Analyze concept schemas to determine embedding strategy.

   Returns fields to embed for different concept types."
  []
  {:failure {:fields #{:label :description :indicators}
             :strategy "Combine label, description, and indicators for failure detection"}
   :success {:fields #{:label :description}
             :strategy "Combine label and description for pattern matching"}
   :problem {:fields #{:label :description}
             :strategy "Combine label and description for problem similarity"}
   :tree-profile {:fields #{:strengths :weaknesses :solves}
                  :strategy "Summarize profile into text then embed"}
   :evaluation {:fields #{:feedback :dimension}
                :strategy "Embed feedback with dimension context"}})

;; =============================================================================
;; Similarity Search Helpers
;; =============================================================================

(defn find-most-similar
  "Find the most similar items from a collection based on embedding similarity.

   Args:
     query-embedding: Query vector
     items: Collection of maps with :embedding key
     opts: {:limit 10, :min-similarity 0.0}

   Returns:
     Vector of items with :similarity score, sorted descending"
  [query-embedding items & {:keys [limit min-similarity]
                            :or {limit 10 min-similarity 0.0}}]
  (when (and query-embedding (seq items))
    (->> items
         (filter :embedding)
         (map (fn [item]
                (let [sim (cosine-similarity query-embedding (:embedding item))]
                  (assoc item :similarity sim))))
         (filter #(>= (:similarity %) min-similarity))
         (sort-by :similarity >)
         (take limit)
         vec)))

(defn search-concepts-by-embedding
  "Search concepts by semantic similarity.

   Args:
     query-embedding: Query vector
     concept-embeddings: Map of uri -> {:embedding [...]}
     opts: {:limit 10, :min-similarity 0.5}

   Returns:
     Vector of {:uri :similarity} sorted by similarity"
  [query-embedding concept-embeddings & {:keys [limit min-similarity]
                                          :or {limit 10 min-similarity 0.5}}]
  (when query-embedding
    (->> concept-embeddings
         (map (fn [[uri data]]
                (when (:embedding data)
                  {:uri uri
                   :similarity (cosine-similarity query-embedding (:embedding data))})))
         (remove nil?)
         (filter #(>= (:similarity %) min-similarity))
         (sort-by :similarity >)
         (take limit)
         vec)))

;; =============================================================================
;; Batch Embedding for Evolutionary Builder
;; =============================================================================

(defn concept->embedding-text-multi-field
  "Convert a concept to text using multiple fields.

   Unlike concept->embedding-text which only supports a fixed set of fields,
   this function handles any field detected by field analysis.

   Args:
     concept: Concept map
     fields: Vector or set of field keywords to include

   Returns:
     Combined text string for embedding"
  [concept fields]
  (let [fields-set (set fields)
        parts (->> fields-set
                   (map (fn [field-key]
                          (let [value (get concept field-key)]
                            (cond
                              (string? value) value
                              (sequential? value) (str/join ", " value)
                              :else nil))))
                   (remove str/blank?))]
    (str/join ". " parts)))

(defn embed-concepts-batch!
  "Embed all concepts for an ontology (used by evolutionary builder).

   Auto-detects fields if not provided, using same logic as ColBERT.
   Returns results for event emission by the caller.

   This function is designed to mirror the ColBERT indexing pattern in
   evolutionary_builder.clj, enabling automatic embedding during ontology builds.

   Args:
     concepts: Vector of concept maps to embed
     opts: {:embedding-fields nil     ;; Auto-detect if nil
            :auto-detect? true        ;; Use field analyzer
            :model-id nil             ;; Use default MiniLM
            :batch-size 32            ;; Process in batches
            :ctx nil}                 ;; Context for LLM field detection

   Returns:
     {:embedded-count N
      :fields-used [:label :description ...]
      :model-id \"sentence-transformers/all-MiniLM-L6-v2\"
      :embeddings [{:uri \"...\" :embedding [...] :text-embedded \"...\"}]
      :skipped-count N}"
  [concepts {:keys [embedding-fields auto-detect? model-id batch-size ctx]
             :or {auto-detect? true
                  model-id default-model-id
                  batch-size 32}}]
  (if (empty? concepts)
    (do
      (mu/log ::embed-concepts-batch-empty)
      {:embedded-count 0
       :fields-used []
       :model-id model-id
       :embeddings []
       :skipped-count 0})

    ;; Non-empty concepts - process them
    (let [;; Determine fields to use
        fields (cond
                 ;; Explicit fields provided
                 (seq embedding-fields)
                 (vec embedding-fields)

                 ;; Auto-detect from concepts (LLM if ctx available, heuristic otherwise)
                 auto-detect?
                 (let [detected (if ctx
                                  (fa/detect-embedding-fields ctx concepts)
                                  (fa/detect-embedding-fields concepts))]
                   (:embedding-fields detected))

                 ;; Fallback
                 :else
                 [:label :description])

        _ (mu/log ::embed-concepts-batch-start
                  :concept-count (count concepts)
                  :fields-to-use fields
                  :model-id model-id)

        ;; Build text for each concept
        texts-with-uris (->> concepts
                             (map (fn [concept]
                                    (let [text (concept->embedding-text-multi-field concept fields)]
                                      {:uri (or (:uri concept) (str (random-uuid)))
                                       :text text})))
                             (filter #(not (str/blank? (:text %)))))

        ;; Embed in batches
        start-time (System/currentTimeMillis)
        embeddings-result
        (loop [remaining texts-with-uris
               results []]
          (if (empty? remaining)
            results
            (let [batch (take batch-size remaining)
                  rest-items (drop batch-size remaining)
                  texts (mapv :text batch)
                  embedded (embed-texts-batch texts {:model-id model-id})
                  batch-results (mapv (fn [item emb]
                                        {:uri (:uri item)
                                         :embedding emb
                                         :text-embedded (:text item)})
                                      batch
                                      embedded)]
              (recur rest-items (into results batch-results)))))

        duration-ms (- (System/currentTimeMillis) start-time)
        successful (filter :embedding embeddings-result)
        skipped (- (count concepts) (count texts-with-uris))]

      (mu/log ::embed-concepts-batch-complete
              :embedded-count (count successful)
              :skipped-count skipped
              :duration-ms duration-ms)

      {:embedded-count (count successful)
       :fields-used fields
       :model-id model-id
       :embeddings successful
       :skipped-count skipped
       :duration-ms duration-ms})))
