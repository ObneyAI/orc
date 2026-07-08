(ns ai.obney.orc.ontology.core.colbert-indexer
  "Build documents for ColBERT indexing from extracted concepts.

   This module provides the bridge between the evolutionary ontology builder
   and ColBERT late-interaction retrieval. It:
   1. Detects embeddable fields using field-analyzer heuristics
   2. Builds enriched documents by concatenating relevant fields
   3. Creates ColBERT indexes via the colbert component

   Key functions:
   - build-document: Build single document from concept + fields
   - build-documents-from-concepts: Build documents for all concepts
   - index-concepts!: Create ColBERT index from concepts
   - index-with-related-data!: Create enriched index with tasks/skills/knowledge"
  (:require [ai.obney.orc.ontology.core.field-analyzer :as fa]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as mu]))

(defn- require-colbert
  "Resolve a fn from the colbert component, or throw a clear error.
   colbert-indexer's index/search functions inherently need the colbert
   component (an optional Layer-5 upgrade). This namespace LOADS without
   colbert on the classpath — so the ontology builds colbert-free — but
   calling these functions without it is a clear, named error rather than
   an obscure NPE."
  [fn-name]
  (or (try (requiring-resolve (symbol "ai.obney.orc.colbert.interface" (name fn-name)))
           (catch Throwable _ nil))
      (throw (ex-info (str "ColBERT indexing requires the colbert component on the "
                           "classpath (optional Layer-5 upgrade — see COMPONENT-MAP.md).")
                      {:fn fn-name}))))

;; =============================================================================
;; Document Building
;; =============================================================================

(defn- format-field-value
  "Format a field value for document content."
  [value]
  (cond
    (nil? value) nil
    (string? value) (when-not (str/blank? value) value)
    (sequential? value) (when (seq value) (str/join ", " value))
    (map? value) (pr-str value)
    :else (str value)))

(defn- format-field-with-label
  "Format a field with its name as a label."
  [field-name value]
  (when-let [formatted (format-field-value value)]
    (str (-> field-name name str/upper-case (str/replace #"[-_]" " ")) ": " formatted)))

(defn build-document
  "Build a single ColBERT document from a concept and its fields.

   Args:
     concept: Concept map with :uri and various fields
     colbert-fields: Vector of field keywords to include
     opts: {:include-field-labels? true, :separator \"\\n\\n\"}

   Returns:
     {:document-id \"uri\" :content \"combined text\"}"
  [concept colbert-fields & [{:keys [include-field-labels? separator]
                               :or {include-field-labels? true
                                    separator "\n\n"}}]]
  (let [uri (or (:uri concept) (str "concept:" (random-uuid)))
        content-parts (if include-field-labels?
                        (keep (fn [field]
                                (format-field-with-label field (get concept field)))
                              colbert-fields)
                        (keep (fn [field]
                                (format-field-value (get concept field)))
                              colbert-fields))]
    {:document-id uri
     :content (str/join separator content-parts)}))

(defn build-enriched-document
  "Build enriched document with related data (tasks, skills, knowledge).

   For O*NET occupations, related data includes:
   - :tasks - Core tasks for the occupation
   - :skills - Required skills
   - :knowledge - Knowledge areas
   - :abilities - Physical/cognitive abilities

   Args:
     concept: Base concept with :uri, :label, :description
     related-data: Map with :tasks, :skills, :knowledge, :abilities
     opts: {:max-tasks 8, :max-skills 6, :max-knowledge 6}

   Returns:
     {:document-id \"uri\" :content \"enriched text\"}"
  [concept related-data & [{:keys [max-tasks max-skills max-knowledge max-abilities]
                             :or {max-tasks 8
                                  max-skills 6
                                  max-knowledge 6
                                  max-abilities 6}}]]
  (let [uri (or (:uri concept) (str "concept:" (random-uuid)))
        label (or (:label concept) "")
        description (or (:description concept) "")

        ;; Extract related items
        tasks (take max-tasks (:tasks related-data))
        skills (take max-skills (:skills related-data))
        knowledge (take max-knowledge (:knowledge related-data))
        abilities (take max-abilities (:abilities related-data))

        ;; Build content sections
        content (str "OCCUPATION: " label
                     (when-not (str/blank? description)
                       (str "\n\nDESCRIPTION: " description))
                     (when (seq tasks)
                       (str "\n\nKEY TASKS:\n- " (str/join "\n- " tasks)))
                     (when (seq skills)
                       (str "\n\nSKILLS: " (str/join ", " skills)))
                     (when (seq knowledge)
                       (str "\n\nKNOWLEDGE: " (str/join ", " knowledge)))
                     (when (seq abilities)
                       (str "\n\nABILITIES: " (str/join ", " abilities))))]
    {:document-id uri
     :content content}))

(defn build-documents-from-concepts
  "Build ColBERT documents for all concepts.

   Args:
     concepts: Vector of concept maps
     opts: {:colbert-fields nil ;; auto-detect if nil
            :include-field-labels? true
            :separator \"\\n\\n\"}

   Returns:
     Vector of {:document-id :content} maps"
  [concepts & [{:keys [colbert-fields include-field-labels? separator]
                :or {include-field-labels? true
                     separator "\n\n"}}]]
  (let [;; Auto-detect fields if not provided
        detected-fields (when (nil? colbert-fields)
                          (:colbert-fields (fa/detect-colbert-fields concepts)))
        fields-to-use (or colbert-fields detected-fields [:label :description])]

    (mu/log ::building-documents
            :concept-count (count concepts)
            :fields-to-use fields-to-use)

    (mapv #(build-document % fields-to-use
                           {:include-field-labels? include-field-labels?
                            :separator separator})
          concepts)))

;; =============================================================================
;; ColBERT Index Creation
;; =============================================================================

(defn index-concepts!
  "Create a ColBERT index from concepts with auto-detected fields.

   Args:
     ctx: Context map with :event-store
     concepts: Vector of concept maps
     config: {:index-name \"my-index\"
              :colbert-fields [:description :tasks ...] ;; or nil for auto-detect
              :auto-detect-colbert-fields true}

   Returns:
     {:index-id uuid
      :document-count int
      :colbert-fields [...]}"
  [ctx concepts {:keys [index-name colbert-fields auto-detect-colbert-fields]
                 :or {auto-detect-colbert-fields true}}]
  (when (empty? concepts)
    (throw (ex-info "No concepts to index" {:concepts-count 0})))

  (let [;; Detect or use provided fields
        detected (when (and auto-detect-colbert-fields (nil? colbert-fields))
                   (fa/detect-colbert-fields concepts))
        fields-to-use (or colbert-fields
                          (:colbert-fields detected)
                          [:label :description])

        _ (mu/log ::indexing-concepts
                  :concept-count (count concepts)
                  :index-name index-name
                  :colbert-fields fields-to-use)

        ;; Build documents
        documents (build-documents-from-concepts concepts
                    {:colbert-fields fields-to-use})

        ;; Filter out empty documents
        valid-docs (filterv #(not (str/blank? (:content %))) documents)

        _ (when (< (count valid-docs) (count documents))
            (mu/log ::filtered-empty-documents
                    :original-count (count documents)
                    :valid-count (count valid-docs)))

        ;; Generate index name if not provided
        final-index-name (or index-name
                             (str "ontology-" (subs (str (random-uuid)) 0 8)))

        ;; Create ColBERT index
        index-id ((require-colbert (quote create-index!)) ctx
                   {:collection (mapv :content valid-docs)
                    :document-ids (mapv :document-id valid-docs)
                    :index-name final-index-name})]

    (mu/log ::index-created
            :index-id index-id
            :document-count (count valid-docs)
            :index-name final-index-name)

    {:index-id index-id
     :index-name final-index-name
     :document-count (count valid-docs)
     :colbert-fields fields-to-use
     :detected-confidence (when detected (:confidence-scores detected))}))

(defn index-with-related-data!
  "Create enriched ColBERT index with related data.

   For O*NET-style data, provide related-data-fn that looks up
   tasks/skills/knowledge by concept URI.

   Args:
     ctx: Context with :event-store
     concepts: Vector of base concepts
     related-data-fn: (fn [uri] {:tasks [...] :skills [...] :knowledge [...] :abilities [...]})
     config: {:index-name \"enriched-index\"
              :max-tasks 8
              :max-skills 6
              :max-knowledge 6}

   Returns:
     {:index-id uuid :document-count int :avg-doc-length float}"
  [ctx concepts related-data-fn {:keys [index-name max-tasks max-skills
                                         max-knowledge max-abilities]
                                  :or {max-tasks 8
                                       max-skills 6
                                       max-knowledge 6
                                       max-abilities 6}
                                  :as config}]
  (when (empty? concepts)
    (throw (ex-info "No concepts to index" {:concepts-count 0})))

  (mu/log ::building-enriched-index
          :concept-count (count concepts)
          :index-name index-name)

  (let [;; Build enriched documents
        documents (mapv (fn [concept]
                          (let [uri (:uri concept)
                                related (when related-data-fn
                                          (related-data-fn uri))]
                            (build-enriched-document concept related config)))
                        concepts)

        ;; Filter empty
        valid-docs (filterv #(not (str/blank? (:content %))) documents)

        ;; Calculate stats
        avg-length (when (seq valid-docs)
                     (/ (reduce + (map #(count (:content %)) valid-docs))
                        (count valid-docs)))

        ;; Generate index name
        final-index-name (or index-name
                             (str "ontology-enriched-" (subs (str (random-uuid)) 0 8)))

        ;; Create index
        index-id ((require-colbert (quote create-index!)) ctx
                   {:collection (mapv :content valid-docs)
                    :document-ids (mapv :document-id valid-docs)
                    :index-name final-index-name})]

    (mu/log ::enriched-index-created
            :index-id index-id
            :document-count (count valid-docs)
            :avg-doc-length avg-length)

    {:index-id index-id
     :index-name final-index-name
     :document-count (count valid-docs)
     :avg-doc-length avg-length
     :config config}))

;; =============================================================================
;; Search Helpers for RRF Integration
;; =============================================================================

(defn search-for-rrf
  "Search ColBERT index and format results for RRF fusion.

   Returns results compatible with ontology/retrieval.clj RRF merge.

   Args:
     ctx: Context with :event-store
     opts: {:query \"search query\"
            :index-id uuid
            :k 20
            :normalize? true}

   Returns:
     [{:uri \"concept-uri\" :score 0.92 :rank 1}]"
  [ctx {:keys [query index-id k normalize?]
        :or {k 20 normalize? true}}]
  (let [results ((require-colbert (quote search)) ctx {:query query :index-id index-id :k k})
        max-score (if (seq results)
                    (apply max (map :score results))
                    1.0)]
    (mapv (fn [r]
            {:uri (:document-id r)
             :label (:content r)  ;; First 100 chars for display
             :score (if normalize?
                      (if (pos? max-score)
                        (/ (:score r) max-score)
                        0.0)
                      (:score r))
             :rank (:rank r)
             :source :colbert})
          results)))

;; =============================================================================
;; Event Helpers
;; =============================================================================

(defn emit-colbert-indexed-event!
  "Record ColBERT index creation via command processor."
  [ctx {:keys [ontology-id index-id index-name document-count colbert-fields]}]
  (cp/process-command
   (assoc ctx :command {:command/id (random-uuid)
                        :command/timestamp (time/now)
                        :command/name :ontology/record-colbert-index
                        :ontology-id ontology-id
                        :index-id index-id
                        :index-name index-name
                        :document-count document-count
                        :colbert-fields (vec colbert-fields)})))
