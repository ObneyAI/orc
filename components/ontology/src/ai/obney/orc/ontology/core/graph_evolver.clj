(ns ai.obney.orc.ontology.core.graph-evolver
  "Graph Evolver - Merges extracted concepts and tracks schema extensions.

   This implements the graph evolution layer from Python's graph_evolver.py
   with EXACT 1:1 parity:

   1. Apply canonical URI mappings to all concepts
   2. Detect schema extensions (new classes, properties)
   3. Merge triples into unified graph
   4. Track evolution history

   Key operations:
   - merge-sources: Apply canonical mapping, combine triples
   - detect-schema-extensions: Find new TBox elements
   - apply-canonical-mapping: Rewrite URIs in concepts/relationships"
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [ai.obney.grain.event-store-v3.interface :refer [->event]]))

;; =============================================================================
;; Canonical URI Application
;; =============================================================================

(defn apply-canonical-uri
  "Apply canonical mapping to a single URI.
   Returns the canonical URI or the original if not mapped."
  [canonical-map uri]
  (get canonical-map uri uri))

(defn apply-canonical-to-concept
  "Apply canonical URI mapping to a concept.
   Updates the concept's URI and any relationship targets."
  [canonical-map concept]
  (let [canonical-uri (apply-canonical-uri canonical-map (:uri concept))]
    (-> concept
        (assoc :uri canonical-uri
               :original-uri (:uri concept))
        (update :relationships
                (fn [rels]
                  (mapv (fn [rel]
                          (update rel :object #(apply-canonical-uri canonical-map %)))
                        (or rels [])))))))

(defn apply-canonical-to-relationship
  "Apply canonical URI mapping to a relationship triple."
  [canonical-map {:keys [subject predicate object] :as rel}]
  (assoc rel
         :subject (apply-canonical-uri canonical-map subject)
         :object (apply-canonical-uri canonical-map object)))

(defn apply-canonical-mappings
  "Apply canonical URI mappings to all concepts and relationships.

   Args:
     concepts - Vector of concept maps
     relationships - Vector of relationship maps
     canonical-map - {original-uri -> canonical-uri}

   Returns:
     {:concepts [...] :relationships [...]}"
  [concepts relationships canonical-map]
  {:concepts (mapv #(apply-canonical-to-concept canonical-map %) concepts)
   :relationships (mapv #(apply-canonical-to-relationship canonical-map %) relationships)})

;; =============================================================================
;; Deduplication
;; =============================================================================

(defn deduplicate-concepts
  "Deduplicate concepts by URI, merging properties from duplicates.
   When concepts share a URI (after canonical mapping), merge:
   - alt-labels: Union of all
   - definition: Prefer longest (most detailed)
   - confidence: Take highest
   - relationships: Union of all"
  [concepts]
  (let [by-uri (group-by :uri concepts)]
    (->> by-uri
         (map (fn [[_uri cs]]
                (if (= 1 (count cs))
                  (first cs)
                  ;; Merge multiple concepts
                  (reduce (fn [merged c]
                            (-> merged
                                (update :alt-labels (fnil into []) (:alt-labels c))
                                (update :definition
                                        (fn [d1]
                                          (let [d2 (:definition c)]
                                            (if (> (count (or d2 "")) (count (or d1 "")))
                                              d2 d1))))
                                (update :confidence
                                        (fn [c1]
                                          (max (or c1 0) (or (:confidence c) 0))))
                                (update :relationships
                                        (fn [r1]
                                          (vec (distinct (concat r1 (:relationships c))))))))
                          (first cs)
                          (rest cs)))))
         vec)))

(defn deduplicate-relationships
  "Deduplicate relationships by [subject predicate object] triple."
  [relationships]
  (->> relationships
       (group-by (juxt :subject :predicate :object))
       vals
       (map (fn [rels]
              ;; Keep the one with highest confidence
              (apply max-key #(or (:confidence %) 0) rels)))
       vec))

;; =============================================================================
;; Schema Extension Detection
;; =============================================================================

(defn extract-classes-from-concepts
  "Extract unique entity types as potential classes."
  [concepts]
  (->> concepts
       (map :entity-type)
       (remove nil?)
       distinct
       (map (fn [etype]
              {:uri (str "class:" (str/replace etype #"\s+" ""))
               :label etype}))
       vec))

(defn extract-properties-from-relationships
  "Extract unique predicates as potential properties."
  [relationships]
  (->> relationships
       (map :predicate)
       distinct
       (map (fn [pred]
              {:uri pred
               :label (last (str/split pred #"[:#/]"))}))
       vec))

(defn detect-schema-extensions
  "Detect new schema elements (classes, properties) from extracted data.

   Args:
     concepts - Extracted concepts
     relationships - Extracted relationships
     existing-schema - {:classes #{uri} :properties #{uri}}

   Returns:
     {:new-classes [{:uri :label}]
      :new-object-properties [{:uri :label}]
      :new-datatype-properties [{:uri :label}]}"
  [concepts relationships existing-schema]
  (let [{:keys [classes properties]
         :or {classes #{} properties #{}}} existing-schema

        extracted-classes (extract-classes-from-concepts concepts)
        extracted-properties (extract-properties-from-relationships relationships)

        new-classes (remove #(contains? classes (:uri %)) extracted-classes)
        new-properties (remove #(contains? properties (:uri %)) extracted-properties)]

    {:new-classes (vec new-classes)
     :new-object-properties (vec new-properties)
     :new-datatype-properties []}))

;; =============================================================================
;; Graph Merging
;; =============================================================================

(defn merge-into-graph
  "Merge new concepts and relationships into existing graph.

   Args:
     existing-graph - {:concepts {uri -> concept} :relationships [triple...]}
     new-concepts - Vector of concepts (after canonical mapping)
     new-relationships - Vector of relationships (after canonical mapping)

   Returns:
     {:concepts {uri -> concept}
      :relationships [triple...]
      :concepts-added count
      :concepts-merged count}"
  [{:keys [concepts relationships] :as existing-graph
    :or {concepts {} relationships []}}
   new-concepts
   new-relationships]
  (let [existing-uris (set (keys concepts))
        new-uris (set (map :uri new-concepts))

        added-uris (set/difference new-uris existing-uris)
        merged-uris (set/intersection new-uris existing-uris)

        ;; Build updated concepts map
        updated-concepts (reduce
                           (fn [m c]
                             (if (contains? m (:uri c))
                               ;; Merge with existing
                               (update m (:uri c)
                                       (fn [existing]
                                         (-> existing
                                             (update :alt-labels into (:alt-labels c))
                                             (update :relationships into (:relationships c)))))
                               ;; Add new
                               (assoc m (:uri c) c)))
                           concepts
                           new-concepts)

        ;; Merge relationships (deduplicate)
        all-relationships (deduplicate-relationships
                            (concat relationships new-relationships))]

    {:concepts updated-concepts
     :relationships all-relationships
     :concepts-added (count added-uris)
     :concepts-merged (count merged-uris)}))

;; =============================================================================
;; Main Evolution Operation
;; =============================================================================

(defn evolve-graph!
  "Evolve the graph with new extracted data.

   This is the main entry point for graph evolution:
   1. Apply canonical URI mappings
   2. Detect schema extensions
   3. Merge into existing graph
   4. Return events to emit

   Args:
     ontology-id - UUID of the ontology
     source-ids - Vector of source IDs being merged
     extracted-concepts - Vector of concepts from extraction
     extracted-relationships - Vector of relationships from extraction
     resolution-result - {:canonical-map {...} :alignment-triples [...]}
     existing-graph - Current graph state {:concepts {...} :relationships [...]}
     existing-schema - Current schema {:classes #{} :properties #{}}

   Returns:
     {:graph updated-graph
      :schema-extensions [...]
      :events [events-to-emit]}"
  [{:keys [ontology-id source-ids extracted-concepts extracted-relationships
           resolution-result existing-graph existing-schema]
    :or {existing-graph {:concepts {} :relationships []}
         existing-schema {:classes #{} :properties #{}}}}]

  (let [canonical-map (:canonical-map resolution-result)

        ;; Step 1: Apply canonical mappings
        {:keys [concepts relationships]}
        (apply-canonical-mappings extracted-concepts extracted-relationships canonical-map)

        ;; Deduplicate
        deduped-concepts (deduplicate-concepts concepts)
        deduped-relationships (deduplicate-relationships relationships)

        ;; Step 2: Detect schema extensions
        extensions (detect-schema-extensions deduped-concepts deduped-relationships existing-schema)

        ;; Step 3: Merge into graph
        triples-before (count (:relationships existing-graph))
        merge-result (merge-into-graph existing-graph deduped-concepts deduped-relationships)
        triples-after (count (:relationships merge-result))

        ;; Build events
        events [(->event {:type :evolutionary/graph-merged
                          :tags #{[:ontology ontology-id]}
                          :body {:ontology-id ontology-id
                                 :source-ids source-ids
                                 :triples-before triples-before
                                 :triples-after triples-after
                                 :concepts-added (:concepts-added merge-result)
                                 :concepts-merged (:concepts-merged merge-result)
                                 :merged-at (str (java.time.Instant/now))}})]]

    ;; Add schema extension event if there are new elements
    (let [has-extensions? (or (seq (:new-classes extensions))
                              (seq (:new-object-properties extensions))
                              (seq (:new-datatype-properties extensions)))

          extension-event (when has-extensions?
                            (->event {:type :evolutionary/schema-extended
                                      :tags #{[:ontology ontology-id]}
                                      :body {:ontology-id ontology-id
                                             :extensions
                                             (vec (concat
                                                    (map #(assoc % :element-type "class"
                                                                 :source-id (first source-ids))
                                                         (:new-classes extensions))
                                                    (map #(assoc % :element-type "object-property"
                                                                 :source-id (first source-ids))
                                                         (:new-object-properties extensions))))
                                             :extended-at (str (java.time.Instant/now))}}))]

      {:graph {:concepts (:concepts merge-result)
               :relationships (:relationships merge-result)}
       :schema-extensions extensions
       :stats {:concepts-added (:concepts-added merge-result)
               :concepts-merged (:concepts-merged merge-result)
               :triples-before triples-before
               :triples-after triples-after}
       :events (if extension-event
                 (conj events extension-event)
                 events)})))

;; =============================================================================
;; TTL Snapshot Generation
;; =============================================================================

(defn concept->ttl-triples
  "Convert a concept to TTL triple strings.
   Uses SKOS vocabulary for concept representation."
  [concept base-uri]
  (when (and (:uri concept) (:label concept))
    (let [concept-uri (or (:uri concept) "unknown")
          uri (if (and concept-uri (str/starts-with? concept-uri "http"))
                concept-uri
                (str base-uri concept-uri))
          label (or (:label concept) "")
          lines [(format "<%s> a skos:Concept ;" uri)
                 (format "    skos:prefLabel \"%s\"@en" (str/replace label "\"" "\\\""))]]

      (cond-> lines
        (:definition concept)
        (conj (format "    skos:definition \"%s\"@en"
                      (-> (or (:definition concept) "")
                          (str/replace "\"" "\\\"")
                          (str/replace "\n" " "))))

        (:entity-type concept)
        (conj (format "    :entityType \"%s\"" (:entity-type concept)))

        (seq (:alt-labels concept))
        (into (map #(format "    skos:altLabel \"%s\"@en" (str/replace (or % "") "\"" "\\\""))
                   (:alt-labels concept)))

        true
        (#(conj (vec (butlast %)) (str (last %) " .")))))))

(defn relationship->ttl-triple
  "Convert a relationship to TTL triple string."
  [{:keys [subject predicate object]} base-uri]
  (let [subj-uri (if (str/starts-with? subject "http") subject (str base-uri subject))
        obj-uri (if (str/starts-with? object "http") object (str base-uri object))]
    (format "<%s> <%s> <%s> ." subj-uri predicate obj-uri)))

(defn generate-ttl-snapshot
  "Generate a complete TTL snapshot of the graph.

   Args:
     graph - {:concepts {uri -> concept} :relationships [triple...]}
     config - {:base-uri :include-metadata?}

   Returns:
     {:ttl-string :triple-count :checksum}"
  [{:keys [concepts relationships]} {:keys [base-uri include-metadata?]
                                      :or {base-uri "http://ontology.local/"
                                           include-metadata? true}}]
  (let [prefixes ["@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> ."
                  "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> ."
                  "@prefix owl: <http://www.w3.org/2002/07/owl#> ."
                  "@prefix skos: <http://www.w3.org/2004/02/skos/core#> ."
                  "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> ."
                  (format "@prefix : <%s> ." base-uri)
                  ""]

        ;; Metadata section
        metadata (when include-metadata?
                   [(format "<%s> a owl:Ontology ;" base-uri)
                    "    rdfs:comment \"Generated by Evolutionary Ontology Builder\" ."
                    ""])

        ;; Concept section
        concept-triples (->> (vals concepts)
                             (map #(concept->ttl-triples % base-uri))
                             (remove nil?)
                             (mapcat identity)
                             (interpose "")
                             vec)

        ;; Relationship section
        relationship-triples (mapv #(relationship->ttl-triple % base-uri) relationships)

        ;; Combine all sections
        all-lines (concat prefixes
                          (or metadata [])
                          ["# Concepts" ""]
                          concept-triples
                          ["" "# Relationships" ""]
                          relationship-triples)

        ttl-string (str/join "\n" all-lines)
        triple-count (+ (count concepts) (count relationships))]

    {:ttl-string ttl-string
     :triple-count triple-count
     :checksum (format "%08x" (.hashCode ttl-string))}))

(defn emit-ttl-snapshot-event
  "Create TTL snapshot event."
  [ontology-id snapshot-result format]
  (->event {:type :evolutionary/ttl-snapshot-created
            :tags #{[:ontology ontology-id]}
            :body {:ontology-id ontology-id
                   :snapshot-id (java.util.UUID/randomUUID)
                   :format (name format)
                   :ttl-string (:ttl-string snapshot-result)
                   :triple-count (:triple-count snapshot-result)
                   :checksum (:checksum snapshot-result)
                   :created-at (str (java.time.Instant/now))}}))

;; =============================================================================
;; OWL T-box/A-box TTL Generation (Matching Python Implementation)
;; =============================================================================

(defn- escape-ttl-string
  "Escape special characters for TTL string literals."
  [s]
  (when s
    (-> (str s)
        (str/replace "\\" "\\\\")
        (str/replace "\"" "\\\"")
        (str/replace "\n" "\\n")
        (str/replace "\r" "\\r")
        (str/replace "\t" "\\t"))))

(defn tbox->ttl-lines
  "Generate TTL lines for T-box (classes and properties).
   Matches Python's OWL serialization format."
  [{:keys [classes object-properties datatype-properties]} base-uri]
  (let [;; Classes
        class-lines (mapcat (fn [c]
                              (let [uri (:uri c)
                                    full-uri (if (str/starts-with? uri "http") uri (str base-uri uri))]
                                (if (seq (:description c))
                                  [(format "<%s> a owl:Class ;" full-uri)
                                   (format "    rdfs:label \"%s\"@en ;" (escape-ttl-string (:label c)))
                                   (format "    rdfs:comment \"%s\"@en ." (escape-ttl-string (:description c)))
                                   ""]
                                  [(format "<%s> a owl:Class ;" full-uri)
                                   (format "    rdfs:label \"%s\"@en ." (escape-ttl-string (:label c)))
                                   ""])))
                            (or classes []))

        ;; Object Properties
        obj-prop-lines (mapcat (fn [p]
                                 (let [uri (:uri p)
                                       full-uri (if (str/starts-with? uri "http") uri (str base-uri uri))
                                       domain (:domain p)
                                       range (:range p)]
                                   [(format "<%s> a owl:ObjectProperty ;" full-uri)
                                    (when (:label p)
                                      (format "    rdfs:label \"%s\"@en ;" (escape-ttl-string (:label p))))
                                    (when domain
                                      (format "    rdfs:domain <%s> ;" (if (str/starts-with? domain "http") domain (str base-uri domain))))
                                    (when range
                                      (format "    rdfs:range <%s> ." (if (str/starts-with? range "http") range (str base-uri range))))
                                    ""]))
                               (or object-properties []))

        ;; Datatype Properties
        data-prop-lines (mapcat (fn [p]
                                  (let [uri (:uri p)
                                        full-uri (if (str/starts-with? uri "http") uri (str base-uri uri))
                                        domain (:domain p)
                                        datatype (or (:datatype p) "http://www.w3.org/2001/XMLSchema#string")]
                                    [(format "<%s> a owl:DatatypeProperty ;" full-uri)
                                     (when (:label p)
                                       (format "    rdfs:label \"%s\"@en ;" (escape-ttl-string (:label p))))
                                     (when domain
                                       (format "    rdfs:domain <%s> ;" (if (str/starts-with? domain "http") domain (str base-uri domain))))
                                     (format "    rdfs:range <%s> ." datatype)
                                     ""]))
                                (or datatype-properties []))]

    (filterv some? (concat ["# T-box (Schema)" ""]
                           class-lines
                           obj-prop-lines
                           data-prop-lines))))

(defn abox->ttl-lines
  "Generate TTL lines for A-box (individuals/instances).
   Matches Python's OWL serialization format."
  [individuals base-uri]
  (let [individual-lines
        (mapcat (fn [ind]
                  (let [uri (:uri ind)
                        full-uri (if (str/starts-with? uri "http") uri (str base-uri uri))
                        type-uri (:type ind)
                        full-type (if (str/starts-with? type-uri "http") type-uri (str base-uri type-uri))
                        props (take 10 (:properties ind))  ;; Limit to 10 properties
                        prop-lines (map-indexed
                                     (fn [idx [k v]]
                                       (if (= idx (dec (count props)))
                                         ;; Last property - end with period
                                         (format "    :%s \"%s\" ." (name k) (escape-ttl-string v))
                                         ;; Not last - end with semicolon
                                         (format "    :%s \"%s\" ;" (name k) (escape-ttl-string v))))
                                     props)]
                    (if (seq props)
                      (concat
                        [(format "<%s> a <%s>, owl:NamedIndividual ;" full-uri full-type)
                         (format "    rdfs:label \"%s\"@en ;" (escape-ttl-string (:label ind)))]
                        prop-lines
                        [""])
                      ;; No properties - just label
                      [(format "<%s> a <%s>, owl:NamedIndividual ;" full-uri full-type)
                       (format "    rdfs:label \"%s\"@en ." (escape-ttl-string (:label ind)))
                       ""])))
                (or individuals []))]

    (filterv some? (concat ["# A-box (Individuals)" ""]
                           individual-lines))))

(defn generate-owl-ttl-snapshot
  "Generate a complete OWL TTL snapshot with T-box and A-box.
   This matches Python's ontology serialization format.

   Args:
     tbox - {:classes [...] :object-properties [...] :datatype-properties [...]}
     abox - [{:uri :type :label :properties {...}}]
     config - {:base-uri :include-metadata?}

   Returns:
     {:ttl-string :triple-count :checksum}"
  [{:keys [tbox abox concepts relationships]} {:keys [base-uri include-metadata?]
                                                :or {base-uri "http://ontology.local/"
                                                     include-metadata? true}}]
  (let [prefixes ["@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> ."
                  "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> ."
                  "@prefix owl: <http://www.w3.org/2002/07/owl#> ."
                  "@prefix skos: <http://www.w3.org/2004/02/skos/core#> ."
                  "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> ."
                  (format "@prefix : <%s> ." base-uri)
                  ""]

        ;; Metadata section
        metadata (when include-metadata?
                   [(format "<%s> a owl:Ontology ;" base-uri)
                    "    rdfs:comment \"Generated by Evolutionary Ontology Builder (Clojure)\"@en ."
                    ""])

        ;; T-box section (if provided)
        tbox-lines (when (or (seq (:classes tbox))
                             (seq (:object-properties tbox))
                             (seq (:datatype-properties tbox)))
                     (tbox->ttl-lines tbox base-uri))

        ;; A-box section (if provided)
        abox-lines (when (seq abox)
                     (abox->ttl-lines abox base-uri))

        ;; SKOS concepts section (backward compatibility)
        concept-triples (when (seq concepts)
                          (concat ["# SKOS Concepts" ""]
                                  (->> (vals concepts)
                                       (map #(concept->ttl-triples % base-uri))
                                       (remove nil?)
                                       (mapcat identity)
                                       (interpose "")
                                       vec)))

        ;; Relationships section
        relationship-triples (when (seq relationships)
                               (concat ["" "# Relationships" ""]
                                       (mapv #(relationship->ttl-triple % base-uri) relationships)))

        ;; Combine all sections
        all-lines (concat prefixes
                          (or metadata [])
                          (or tbox-lines [])
                          (or abox-lines [])
                          (or concept-triples [])
                          (or relationship-triples []))

        ttl-string (str/join "\n" (filterv some? all-lines))

        ;; Count triples
        triple-count (+ (count (:classes tbox))
                        (count (:object-properties tbox))
                        (count (:datatype-properties tbox))
                        (count abox)
                        (count concepts)
                        (count relationships))]

    {:ttl-string ttl-string
     :triple-count triple-count
     :checksum (format "%08x" (.hashCode ttl-string))}))

(comment
  ;; Example usage

  ;; Sample data
  (def concepts
    [{:uri "onet:11-1011" :label "Chief Executives" :entity-type "Occupation"
      :definition "Plan and direct all aspects of an organization"}
     {:uri "programs:ceo" :label "CEO Program" :entity-type "Program"
      :definition "Training program for executives"}])

  (def relationships
    [{:subject "onet:11-1011" :predicate "skos:related" :object "skills:leadership"}
     {:subject "programs:ceo" :predicate "prepares" :object "onet:11-1011"}])

  (def canonical-map
    {"programs:ceo" "onet:11-1011"})

  ;; Evolve graph
  (evolve-graph!
    {:ontology-id (java.util.UUID/randomUUID)
     :source-ids ["onet" "programs"]
     :extracted-concepts concepts
     :extracted-relationships relationships
     :resolution-result {:canonical-map canonical-map}})

  ;; Generate TTL
  (generate-ttl-snapshot
    {:concepts {"onet:11-1011" (first concepts)}
     :relationships relationships}
    {:base-uri "http://bryc.ai/ontology#"})

  ,)
