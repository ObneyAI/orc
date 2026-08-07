(ns ai.obney.orc.ontology.core.serialization
  "TTL/SKOS/OWL serialization for ontology concepts and tree profiles.

   Produces valid Turtle RDF format compatible with graph databases.
   Based on extraction from ontology_exploration.clj pipeline.

   Main functions:
   - concepts->turtle: Serialize concept graph to SKOS
   - tree-profile->turtle: Serialize tree profile as OWL individuals
   - full-export: Complete ontology export with all layers"
  (:require [clojure.string :as str]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

;; =============================================================================
;; URI Helpers
;; =============================================================================

(defn- sanitize-local-name
  "Convert a label or URI fragment to a valid local name.
   Removes non-alphanumeric characters and ensures valid identifier."
  [s]
  (when s
    (-> s
        (str/replace #"[^a-zA-Z0-9_-]" "")
        (str/replace #"^(\d)" "_$1"))))  ; Can't start with digit

(defn- uri->local-name
  "Extract local name from a URI like 'failure:Hallucination' -> 'Hallucination'"
  [uri]
  (when uri
    (if (str/includes? uri ":")
      (second (str/split uri #":" 2))
      uri)))

(defn- uri->prefix
  "Extract prefix from a URI like 'failure:Hallucination' -> 'failure'"
  [uri]
  (when uri
    (if (str/includes? uri ":")
      (first (str/split uri #":" 2))
      nil)))

(defn- escape-turtle-string
  "Escape special characters in Turtle string literals."
  [s]
  (when s
    (-> s
        (str/replace "\\" "\\\\")
        (str/replace "\"" "\\\"")
        (str/replace "\n" "\\n")
        (str/replace "\r" "\\r")
        (str/replace "\t" "\\t"))))

;; =============================================================================
;; Prefix Declarations
;; =============================================================================

(def standard-prefixes
  "Standard prefixes for ontology export."
  {"rdf" "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
   "rdfs" "http://www.w3.org/2000/01/rdf-schema#"
   "owl" "http://www.w3.org/2002/07/owl#"
   "xsd" "http://www.w3.org/2001/XMLSchema#"
   "skos" "http://www.w3.org/2004/02/skos/core#"
   "dcterms" "http://purl.org/dc/terms/"})

(def domain-prefixes
  "Prefixes for ontology domain namespaces."
  {"failure" "http://obney.ai/workshop/ontology/failure#"
   "success" "http://obney.ai/workshop/ontology/success#"
   "problem" "http://obney.ai/workshop/ontology/problem#"
   "tree" "http://obney.ai/workshop/ontology/tree#"
   "node" "http://obney.ai/workshop/ontology/node#"
   "pattern" "http://obney.ai/workshop/ontology/pattern#"
   "ex" "http://example.org/taxonomy#"})

(defn- format-prefixes
  "Format prefix declarations for Turtle output."
  [prefixes]
  (->> prefixes
       (map (fn [[prefix uri]]
              (str "@prefix " prefix ": <" uri "> .")))
       (str/join "\n")))

(defn- all-prefixes []
  (merge standard-prefixes domain-prefixes))

;; =============================================================================
;; Concept Serialization (SKOS)
;; =============================================================================

(defn- concept->turtle
  "Serialize a single concept to Turtle triples.
   Returns a string of Turtle statements."
  [{:keys [uri label description scope broader narrower related indicators]}]
  (let [prefix (or (uri->prefix uri) "ex")
        local-name (uri->local-name uri)]
    (str
     ;; Type declaration
     prefix ":" local-name " a skos:Concept ;\n"
     ;; Label
     "  skos:prefLabel \"" (escape-turtle-string label) "\"@en ;\n"
     ;; Definition
     (when (seq description)
       (str "  skos:definition \"" (escape-turtle-string description) "\"@en ;\n"))
     ;; Scope note
     (when scope
       (str "  skos:scopeNote \"" (name scope) "\"@en ;\n"))
     ;; Broader relationships
     (when (seq broader)
       (str "  skos:broader "
            (str/join " , " (map #(let [p (or (uri->prefix %) "ex")
                                        n (uri->local-name %)]
                                   (str p ":" n))
                                 broader))
            " ;\n"))
     ;; Narrower relationships
     (when (seq narrower)
       (str "  skos:narrower "
            (str/join " , " (map #(let [p (or (uri->prefix %) "ex")
                                        n (uri->local-name %)]
                                   (str p ":" n))
                                 narrower))
            " ;\n"))
     ;; Related relationships
     (when (seq related)
       (str "  skos:related "
            (str/join " , " (map #(let [p (or (uri->prefix %) "ex")
                                        n (uri->local-name %)]
                                   (str p ":" n))
                                 related))
            " ;\n"))
     ;; Indicators as hidden labels (searchable synonyms)
     (when (seq indicators)
       (str/join ""
                 (map #(str "  skos:hiddenLabel \"" (escape-turtle-string %) "\"@en ;\n")
                      indicators)))
     ;; Close the statement
     "  .\n")))

(defn concepts->turtle
  "Serialize a collection of concepts to SKOS Turtle format.

   Arguments:
   - concepts: Map of URI -> concept-map (from concepts read model)
   - opts: Options map with:
     - :base-uri - Base URI for the ontology
     - :include-scheme? - Whether to include ConceptScheme (default true)"
  [concepts & [{:keys [base-uri include-scheme?]
                :or {base-uri "http://obney.ai/workshop/ontology/"
                     include-scheme? true}}]]
  (let [concept-list (if (map? concepts) (vals concepts) concepts)
        prefixes-str (format-prefixes (all-prefixes))
        scheme-str (when include-scheme?
                     (str "\n# Concept Scheme\n"
                          "<" base-uri "scheme> a skos:ConceptScheme ;\n"
                          "  skos:prefLabel \"ObneyAI Workshop Ontology\"@en ;\n"
                          "  dcterms:description \"Three-layer ontology: Failure, Success, Problem Domain\"@en ;\n"
                          "  .\n"))
        concepts-str (str "\n# Concepts\n"
                          (str/join "\n" (map concept->turtle concept-list)))]
    (str prefixes-str "\n" (or scheme-str "") concepts-str)))

;; =============================================================================
;; Tree Profile Serialization (OWL)
;; =============================================================================

(defn- strength->turtle
  "Serialize a tree strength as OWL individual."
  [tree-uri {:keys [pattern confidence evidence-count avg-score]}]
  (let [pattern-local (sanitize-local-name (uri->local-name pattern))]
    (str "tree:" tree-uri "_strength_" pattern-local " a tree:Strength ;\n"
         "  tree:forTree tree:" tree-uri " ;\n"
         "  tree:pattern " (or (uri->prefix pattern) "pattern") ":" (uri->local-name pattern) " ;\n"
         "  tree:confidence \"" confidence "\"^^xsd:double ;\n"
         (when evidence-count
           (str "  tree:evidenceCount \"" evidence-count "\"^^xsd:integer ;\n"))
         (when avg-score
           (str "  tree:avgScore \"" avg-score "\"^^xsd:double ;\n"))
         "  .\n")))

(defn- weakness->turtle
  "Serialize a tree weakness as OWL individual."
  [tree-uri {:keys [failure subtype frequency severity triggers]} idx]
  (let [failure-local (sanitize-local-name (uri->local-name failure))]
    (str "tree:" tree-uri "_weakness_" failure-local "_" idx " a tree:Weakness ;\n"
         "  tree:forTree tree:" tree-uri " ;\n"
         "  tree:failureType failure:" (uri->local-name failure) " ;\n"
         (when subtype
           (str "  tree:failureSubtype failure:" (uri->local-name subtype) " ;\n"))
         "  tree:frequency \"" frequency "\"^^xsd:double ;\n"
         "  tree:severity \"" (name severity) "\" ;\n"
         (when (seq triggers)
           (str "  tree:triggers \""
                (escape-turtle-string (str/join ", " triggers))
                "\" ;\n"))
         "  .\n")))

(defn- problem-mapping->turtle
  "Serialize a problem mapping as OWL individual."
  [tree-uri {:keys [problem-uri success-rate execution-count]}]
  (let [problem-local (sanitize-local-name (uri->local-name problem-uri))]
    (str "tree:" tree-uri "_solves_" problem-local " a tree:ProblemMapping ;\n"
         "  tree:forTree tree:" tree-uri " ;\n"
         "  tree:problemType problem:" (uri->local-name problem-uri) " ;\n"
         "  tree:successRate \"" success-rate "\"^^xsd:double ;\n"
         "  tree:executionCount \"" execution-count "\"^^xsd:integer ;\n"
         "  .\n")))

(defn tree-profile->turtle
  "Serialize a tree profile to OWL Turtle format.

   Arguments:
   - profile: Tree profile map from tree-profiles read model
   - opts: Options map (reserved for future use)"
  [{:keys [tree-id strengths weaknesses solves domain-knowledge]} & [_opts]]
  (let [tree-uri (str tree-id)
        tree-str (str "# Tree Profile: " tree-id "\n"
                      "tree:" tree-uri " a tree:TreeProfile ;\n"
                      "  rdfs:label \"Tree " tree-uri "\" ;\n"
                      "  .\n\n")
        strengths-str (when (seq strengths)
                        (str "# Strengths\n"
                             (str/join "\n"
                                       (map #(strength->turtle tree-uri %) strengths))
                             "\n"))
        weaknesses-str (when (seq weaknesses)
                         (str "# Weaknesses\n"
                              (str/join "\n"
                                        (map-indexed (fn [idx w]
                                                       (weakness->turtle tree-uri w idx))
                                                     weaknesses))
                              "\n"))
        mappings-str (when (seq solves)
                       (str "# Problem Mappings\n"
                            (str/join "\n"
                                      (map #(problem-mapping->turtle tree-uri %) solves))
                            "\n"))]
    (str tree-str
         (or strengths-str "")
         (or weaknesses-str "")
         (or mappings-str ""))))

(defn tree-profiles->turtle
  "Serialize multiple tree profiles to OWL Turtle format."
  [profiles & [opts]]
  (let [prefixes-str (format-prefixes (all-prefixes))
        ontology-decl (str "\n# Tree Profile Ontology\n"
                           "tree:TreeProfile a owl:Class ;\n"
                           "  rdfs:label \"Tree Profile\" ;\n"
                           "  rdfs:comment \"Profile capturing a tree's strengths, weaknesses, and capabilities\" .\n\n"
                           "tree:Strength a owl:Class ;\n"
                           "  rdfs:label \"Strength\" .\n\n"
                           "tree:Weakness a owl:Class ;\n"
                           "  rdfs:label \"Weakness\" .\n\n"
                           "tree:ProblemMapping a owl:Class ;\n"
                           "  rdfs:label \"Problem Mapping\" .\n\n")
        profiles-str (str/join "\n"
                               (map #(tree-profile->turtle %) (vals profiles)))]
    (str prefixes-str "\n" ontology-decl profiles-str)))

;; =============================================================================
;; Node Experiences Serialization
;; =============================================================================

(defn- node-pattern->turtle
  "Serialize a learned pattern as OWL individual."
  [node-type pattern-type effective? {:keys [pattern metrics evidence-count]} idx]
  (let [id (str (name node-type) "_" (name pattern-type) "_"
                (if effective? "eff" "ineff") "_" idx)]
    (str "pattern:" id " a pattern:LearnedPattern ;\n"
         "  pattern:nodeType \"" (name node-type) "\" ;\n"
         "  pattern:patternType \"" (name pattern-type) "\" ;\n"
         "  pattern:effective " (if effective? "true" "false") " ;\n"
         "  pattern:description \"" (escape-turtle-string pattern) "\" ;\n"
         (when evidence-count
           (str "  pattern:evidenceCount \"" evidence-count "\"^^xsd:integer ;\n"))
         (when-let [sr (:success-rate metrics)]
           (str "  pattern:successRate \"" sr "\"^^xsd:double ;\n"))
         (when-let [avg (:avg-score metrics)]
           (str "  pattern:avgScore \"" avg "\"^^xsd:double ;\n"))
         "  .\n")))

(defn node-experiences->turtle
  "Serialize node learning experiences to OWL Turtle format.

   Arguments:
   - experiences: Map from node-experiences read model"
  [experiences & [_opts]]
  (let [prefixes-str (format-prefixes (all-prefixes))
        ontology-decl (str "\n# Pattern Learning Ontology\n"
                           "pattern:LearnedPattern a owl:Class ;\n"
                           "  rdfs:label \"Learned Pattern\" ;\n"
                           "  rdfs:comment \"A pattern learned from node execution traces\" .\n\n")
        patterns-str
        (str/join "\n"
                  (for [[node-type pattern-types] experiences
                        [pattern-type {:keys [effective ineffective]}] pattern-types
                        :let [eff-patterns (map-indexed
                                            (fn [idx p]
                                              (node-pattern->turtle node-type pattern-type true p idx))
                                            (or effective []))
                              ineff-patterns (map-indexed
                                              (fn [idx p]
                                                (node-pattern->turtle node-type pattern-type false p idx))
                                              (or ineffective []))]]
                    (str/join "\n" (concat eff-patterns ineff-patterns))))]
    (str prefixes-str "\n" ontology-decl patterns-str)))

;; =============================================================================
;; Full Export
;; =============================================================================

(defn full-export
  "Export the complete ontology including concepts, tree profiles, and node experiences.

   Arguments:
   - ctx: Context map with :event-store, :tenant-id, :cache
   - opts: Options map with:
     - :scope - Filter concepts by scope (:failure, :success, :problem)
     - :include-profiles? - Include tree profiles (default true)
     - :include-experiences? - Include node experiences (default true)
     - :base-uri - Base URI for the ontology"
  [ctx & [{:keys [scope include-profiles? include-experiences? base-uri]
           :or {include-profiles? true
                include-experiences? true
                base-uri "http://obney.ai/workshop/ontology/"}}]]
  (let [concept-graph (rmp/project ctx :ontology/concepts)
        filtered-concepts (if scope
                            (into {} (filter (fn [[_ c]] (= scope (:scope c))) concept-graph))
                            concept-graph)
        concepts-ttl (concepts->turtle filtered-concepts {:base-uri base-uri})

        profiles-ttl (when include-profiles?
                       (let [profiles (rmp/project ctx :ontology/tree-profiles)]
                         (when (seq profiles)
                           (str "\n\n# === TREE PROFILES ===\n\n"
                                (tree-profiles->turtle profiles)))))

        experiences-ttl (when include-experiences?
                          (let [experiences (rmp/project ctx :ontology/node-experiences)]
                            (when (seq experiences)
                              (str "\n\n# === NODE EXPERIENCES ===\n\n"
                                   (node-experiences->turtle experiences)))))]
    (str concepts-ttl
         (or profiles-ttl "")
         (or experiences-ttl ""))))

;; =============================================================================
;; Utility Functions
;; =============================================================================

(defn validate-turtle
  "Basic validation of generated Turtle syntax.
   Returns {:valid? true/false :errors [...]}."
  [turtle-str]
  (let [errors (atom [])]
    ;; Check for balanced quotes
    (let [quote-count (count (filter #(= \" %) turtle-str))]
      (when (odd? quote-count)
        (swap! errors conj "Unbalanced quotes")))
    ;; Check for prefix usage (strip URIs first to avoid matching inside <...>)
    (let [without-uris (-> turtle-str
                           (str/replace #"<[^>]+>" "")
                           (str/replace #"\"(?:\\\\.|[^\"\\\\])*\"" ""))
          ;; Extract just the prefix names (capture group) from used prefixes
          used-prefixes (->> (re-seq #"(\w+):" without-uris)
                             (map second)  ; Get capture group (prefix name)
                             (remove #{"http" "https" "urn" "mailto" "file"})  ; Remove URI schemes
                             set)
          ;; Extract declared prefix names from @prefix declarations
          declared-prefixes (->> (re-seq #"@prefix\s+(\w+):" turtle-str)
                                 (map second)  ; Get capture group (prefix name)
                                 set)]
      (when-not (every? declared-prefixes used-prefixes)
        (swap! errors conj "Potentially undeclared prefix")))
    {:valid? (empty? @errors)
     :errors @errors}))
