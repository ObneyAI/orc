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
   "dcterms" "http://purl.org/dc/terms/"
   ;; S04 — `orc:` carries ORC-native annotations that have no
   ;; standard RDF predicate (model-guidance is an LLM-facing usage
   ;; hint with no W3C equivalent).
   "orc" "http://obney.ai/workshop/ontology/orc#"
   ;; S05 — QUDT for quantity+unit values. Emitted as a blank-node
   ;; pattern: `[ a qudt:QuantityValue ; qudt:numericValue v ;
   ;; qudt:unit "u" ]`. We render the unit as a plain string literal
   ;; on `qudt:unit` rather than a QUDT unit-IRI; canonical QUDT uses
   ;; `unit:KiloGM` style IRIs, but that requires a unit registry
   ;; consumers don't have yet. The slice's requirement is just that
   ;; the unit appears adjacent to the value with a clear QUDT
   ;; vocabulary — string literals satisfy that without forcing a
   ;; registry decision.
   "qudt" "http://qudt.org/schema/qudt/"})

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

;; =============================================================================
;; S04 — Plain-literal helpers (no hardcoded language tag)
;; =============================================================================

(defn- plain-literal
  "Emit a plain Turtle string literal (no language tag, no datatype).
   Used wherever the source text has no documented language origin —
   the prior `@en` hardcoding is removed here per S04."
  [s]
  (str "\"" (escape-turtle-string s) "\""))

(defn- lang-literal
  "Emit a language-tagged Turtle literal: `\"value\"@lang`."
  [value lang]
  (str "\"" (escape-turtle-string value) "\"@" lang))

(defn- typed-literal
  "Emit a datatype-tagged Turtle literal: `\"value\"^^xsd:type`.
   The :datatype keyword's namespace is the Turtle prefix; the name is
   the local part. e.g., `:xsd/integer` → `^^xsd:integer`."
  [value datatype]
  (let [ns (namespace datatype)
        nm (name datatype)
        prefix (or ns "xsd")]
    (str "\"" value "\"^^" prefix ":" nm)))

(defn- qudt-quantity->turtle
  "S05 — emit the QUDT blank-node block for a `{:value :unit}` (or
   `{:value :unit :datatype}`) attribute value. Compact one-line form
   so multiple per-concept quantity attributes stay readable.

   The numeric value uses the supplied `:datatype` when present
   (typed literal `\"v\"^^xsd:<t>`), otherwise emits the raw value
   without quoting — Turtle accepts unquoted numerics directly.
   This matches how typical QUDT datasets render the predicate."
  [{:keys [value unit datatype]}]
  (let [val-lit (if datatype
                  (typed-literal value datatype)
                  (str value))]
    (str "[ a qudt:QuantityValue ; "
         "qudt:numericValue " val-lit " ; "
         "qudt:unit " (plain-literal unit) " ]")))

(defn- attribute-value->turtle
  "Serialize a single concept-attribute value.

   The branch order is load-bearing — a value carrying BOTH `:unit`
   AND `:datatype` must route through the S05 QUDT path (not the S04
   typed-literal path), so the quantity check runs FIRST:

   - `{:value :unit (:datatype?)}`  → S05 qudt:QuantityValue blank node
   - `{:value :datatype}` (no :unit)→ S04 typed literal
   - bare scalar                    → plain literal"
  [v]
  (cond
    (and (map? v) (:unit v) (contains? v :value))
    (qudt-quantity->turtle v)

    (and (map? v) (contains? v :datatype) (contains? v :value))
    (typed-literal (:value v) (:datatype v))

    :else
    (plain-literal (str v))))

(defn- concept->turtle
  "Serialize a single concept to Turtle triples.
   Returns a string of Turtle statements.

   S04 representation bundle additions:
     :labels         → emits one rdfs:label per (value, lang) entry, language-tagged
     :comments       → emits one rdfs:comment per (value, lang) entry, language-tagged
     :comment        → emits rdfs:comment (single, plain — DISTINCT from skos:definition)
     :see-also       → emits rdfs:seeAlso (URIs)
     :is-defined-by  → emits rdfs:isDefinedBy (URI)
     :model-guidance → emits orc:modelGuidance (plain literal)
     :attributes     → emits orc:<key> with:
                        - QUDT blank node when value is `{:value :unit (:datatype?)}`  (S05)
                        - typed literal  when value is `{:value :datatype}` (no :unit) (S04)
                        - plain literal  for bare scalars                              (back-compat)

   The legacy single-value `:label` keeps emitting `skos:prefLabel` —
   but plainly (no `@en` tag) unless the concept also carries a
   `:labels` entry that lifts it to a tagged label. This is the slice's
   adversarial requirement: a single-label legacy concept exports with
   NO language tag at all."
  [{:keys [uri label description scope broader narrower related indicators
           labels comments comment see-also is-defined-by model-guidance
           attributes]}]
  (let [prefix (or (uri->prefix uri) "ex")
        local-name (uri->local-name uri)]
    (str
     ;; Type declaration
     prefix ":" local-name " a skos:Concept ;\n"
     ;; Legacy single-label — plain (NO language tag). Per S04: the
     ;; convention-only `@en` is removed; tagged labels come via :labels.
     "  skos:prefLabel " (plain-literal label) " ;\n"
     ;; S04 — language-tagged multi-labels emit as rdfs:label per entry.
     (when (seq labels)
       (str/join ""
                 (map #(str "  rdfs:label " (lang-literal (:value %) (:lang %)) " ;\n")
                      labels)))
     ;; Definition — skos:definition is DISTINCT from rdfs:comment per
     ;; S04. Plain literal (no hardcoded language tag).
     (when (seq description)
       (str "  skos:definition " (plain-literal description) " ;\n"))
     ;; S04 — :comment is rdfs:comment, DISTINCT from skos:definition.
     ;; Plain (single-string form).
     (when comment
       (str "  rdfs:comment " (plain-literal comment) " ;\n"))
     ;; S04 — multi-language comments emit as rdfs:comment per entry.
     (when (seq comments)
       (str/join ""
                 (map #(str "  rdfs:comment " (lang-literal (:value %) (:lang %)) " ;\n")
                      comments)))
     ;; Scope note — plain (no hardcoded language tag).
     (when scope
       (str "  skos:scopeNote " (plain-literal (name scope)) " ;\n"))
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
     ;; S04 — see-also (vector of URIs).
     (when (seq see-also)
       (str "  rdfs:seeAlso "
            (str/join " , " (map #(let [p (or (uri->prefix %) "ex")
                                        n (uri->local-name %)]
                                   (str p ":" n))
                                 see-also))
            " ;\n"))
     ;; S04 — is-defined-by (URI; preserved verbatim as an IRI inside <>).
     (when is-defined-by
       (str "  rdfs:isDefinedBy <" is-defined-by "> ;\n"))
     ;; S04 — model-guidance (LLM-facing usage hint; plain literal).
     (when model-guidance
       (str "  orc:modelGuidance " (plain-literal model-guidance) " ;\n"))
     ;; S04 — datatyped (or bare) attributes. Emit one triple per key.
     (when (seq attributes)
       (str/join ""
                 (map (fn [[k v]]
                        (str "  orc:" (name k) " " (attribute-value->turtle v) " ;\n"))
                      attributes)))
     ;; Indicators as hidden labels — plain literal per S04. (Indicators
     ;; are searchable text snippets — no inherent language assumption.)
     (when (seq indicators)
       (str/join ""
                 (map #(str "  skos:hiddenLabel " (plain-literal %) " ;\n")
                      indicators)))
     ;; Close the statement
     "  .\n")))

(defn- ontology-metadata->turtle
  "S04 — serialize the ontology-level metadata header block.
   Per-ontology-id; only the fields actually supplied appear (the
   defaulted-empty-string failure mode is impossible because the
   projection only stores what was recorded).

   Emits an `owl:Ontology` block keyed by the base-uri. Each metadata
   record from the read-model adds dcterms:title / owl:versionInfo /
   dcterms:license / dcterms:creator triples — but ONLY when present."
  [base-uri metadata-record]
  (let [{:keys [title version license creator]} metadata-record]
    (str "<" base-uri "> a owl:Ontology ;\n"
         (when title
           (str "  dcterms:title " (plain-literal title) " ;\n"))
         (when version
           (str "  owl:versionInfo " (plain-literal version) " ;\n"))
         (when license
           (str "  dcterms:license <" license "> ;\n"))
         (when creator
           (str "  dcterms:creator " (plain-literal creator) " ;\n"))
         "  .\n")))

;; =============================================================================
;; S06 — Reified-on-demand edge metadata serialization
;; =============================================================================
;;
;; Prototype verdict (parser-support evidence in commit body):
;; reified-on-demand WINS over RDF-star. rdflib 7.5.0 (the parser reachable
;; on the dev classpath) REJECTS RDF-star with BadSyntax at `<<`; it parses
;; reified rdf:Statement blocks cleanly and SPARQL-queries the metadata.
;; Choosing reified preserves S09's G1 round-trip gate against ANY plain
;; Turtle parser, and forecloses the S11 external-validator risk.
;;
;; Shape:
;;   <s> <p> <o> .                          ;; plain triple — BFS traverses
;;   _:rel_<id> a rdf:Statement ;
;;     rdf:subject <s> ; rdf:predicate <p> ; rdf:object <o> ;
;;     orc:confidenceClass "extracted" ;
;;     orc:evidence [ a orc:Evidence ; orc:source <doc> ; orc:quote "..." ] ;
;;     orc:validFrom "..."^^xsd:dateTime ;
;;     orc:validTo   "..."^^xsd:dateTime ;
;;     orc:supersededBy <urn:rel:<other-uuid>> ;
;;     orc:<prop-key> "<prop-val>" ;       ;; from :properties open bag
;;     .
;;
;; The reified block is emitted ONLY when the edge carries metadata.
;; Bare-extracted edges with no metadata fields emit nothing here —
;; the plain triple is already on the source concept's :related set
;; (concepts->turtle handles that).

(defn- uri-token
  "Render a URI as a prefixed token (`prefix:LocalName`) for inline use
   in Turtle. Falls back to `ex:LocalName` when no prefix is recognized.
   Sanitizes the local name so syntactically-illegal Turtle characters
   never reach the serializer."
  [uri]
  (let [p (or (uri->prefix uri) "ex")
        n (sanitize-local-name (uri->local-name uri))]
    (str p ":" n)))

(defn- evidence-blank-node
  "Render one evidence entry as an inline blank node: `[ a orc:Evidence ;
   orc:source <iri> ; orc:quote \"...\" ]`."
  [{:keys [source quote]}]
  (str "[ a orc:Evidence"
       (when source
         (str " ; orc:source <" source ">"))
       (when quote
         (str " ; orc:quote \"" (escape-turtle-string quote) "\""))
       " ]"))

(defn- property-value->turtle
  "Render a value from the :properties open bag as a Turtle term. Keeps
   the rendering simple — keywords as quoted names, numbers raw, strings
   quoted, anything else stringified. Sufficient for the silent-drop
   failure mode under test; richer typing is the typed-attributes
   pathway (S05)."
  [v]
  (cond
    (keyword? v) (str "\"" (escape-turtle-string (name v)) "\"")
    (number? v)  (str v)
    (string? v)  (str "\"" (escape-turtle-string v) "\"")
    :else        (str "\"" (escape-turtle-string (str v)) "\"")))

(defn- reified-statement->turtle
  "S06 — serialize one relationship-with-metadata as a reified
   rdf:Statement. Returns nil when the edge has NO metadata to reify
   (the reify-on-demand discipline — bare edges stay bare)."
  [{:keys [relationship-id source-uri target-uri predicate
           confidence-class evidence valid-from valid-to superseded-by
           properties]}]
  (let [has-metadata? (or confidence-class (seq evidence)
                          valid-from valid-to superseded-by
                          (seq properties))]
    (when has-metadata?
      (let [stmt-id (str "_:rel_" (str/replace (str relationship-id) "-" "_"))]
        (str stmt-id " a rdf:Statement ;\n"
             "  rdf:subject " (uri-token source-uri) " ;\n"
             "  rdf:predicate " predicate " ;\n"
             "  rdf:object " (uri-token target-uri) " ;\n"
             (when confidence-class
               (str "  orc:confidenceClass \""
                    (name confidence-class) "\" ;\n"))
             (when (seq evidence)
               (str/join "" (map #(str "  orc:evidence " (evidence-blank-node %) " ;\n")
                                 evidence)))
             (when valid-from
               (str "  orc:validFrom \"" valid-from "\"^^xsd:dateTime ;\n"))
             (when valid-to
               (str "  orc:validTo \"" valid-to "\"^^xsd:dateTime ;\n"))
             (when superseded-by
               (str "  orc:supersededBy <urn:rel:" superseded-by "> ;\n"))
             (when (seq properties)
               (str/join "" (map (fn [[k v]]
                                   (str "  orc:" (name k) " "
                                        (property-value->turtle v) " ;\n"))
                                 properties)))
             "  .\n")))))

(defn relationships->turtle
  "S06 — serialize a seq of projected relationship records into reified
   rdf:Statement blocks. Each block is emitted ONLY when the edge has
   metadata; bare edges produce nothing here (their plain triple is
   already on the concept-side projection's :related set).

   Use from `full-export` to append the edge-metadata section after
   the concepts block."
  [relationships]
  (let [blocks (keep reified-statement->turtle relationships)]
    (when (seq blocks)
      (str "\n# Edge Metadata (reified-on-demand)\n"
           (str/join "\n" blocks)))))

(defn concepts->turtle
  "Serialize a collection of concepts to SKOS Turtle format.

   Arguments:
   - concepts: Map of URI -> concept-map (from concepts read model)
   - opts: Options map with:
     - :base-uri - Base URI for the ontology
     - :include-scheme? - Whether to include ConceptScheme (default true)
     - :ontology-metadata - S04: optional per-ontology metadata record
                           from the :ontology/ontology-metadata read-model.
                           When provided, the owl:Ontology header block is
                           emitted with only the fields the record carries."
  [concepts & [{:keys [base-uri include-scheme? ontology-metadata]
                :or {base-uri "http://obney.ai/workshop/ontology/"
                     include-scheme? true}}]]
  (let [concept-list (if (map? concepts) (vals concepts) concepts)
        prefixes-str (format-prefixes (all-prefixes))
        ;; S04 — Ontology header. Emitted when EITHER the legacy
        ;; ConceptScheme block is enabled OR explicit metadata was
        ;; recorded. The ConceptScheme half preserves today's behavior
        ;; (without the hardcoded @en); the owl:Ontology half is
        ;; data-driven from the metadata record.
        scheme-str (when include-scheme?
                     (str "\n# Concept Scheme\n"
                          "<" base-uri "scheme> a skos:ConceptScheme ;\n"
                          ;; Plain literals — the convention-only @en
                          ;; is removed per S04. Multi-language titles
                          ;; for the scheme can be added later via the
                          ;; same labels-vector machinery if needed.
                          "  skos:prefLabel " (plain-literal "ObneyAI Workshop Ontology") " ;\n"
                          "  dcterms:description " (plain-literal "Three-layer ontology: Failure, Success, Problem Domain") " ;\n"
                          "  .\n"))
        metadata-str (when ontology-metadata
                       (str "\n# Ontology Header\n"
                            (ontology-metadata->turtle base-uri ontology-metadata)))
        concepts-str (str "\n# Concepts\n"
                          (str/join "\n" (map concept->turtle concept-list)))]
    (str prefixes-str "\n"
         (or scheme-str "")
         (or metadata-str "")
         concepts-str)))

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

;; =============================================================================
;; S07 — Axiom Serialization (OWL)
;; =============================================================================
;;
;; Emit each of the four axiom families as the correct OWL construct.
;; The projection is per-ontology — so the emitted TTL is keyed off the
;; collapsed axiom-map (across all ontology-ids).
;;
;; CRITICAL non-goal reminder: this code emits axiom triples AS-IS. It
;; does NOT consult concept state, does NOT cross-check for OWL DL
;; consistency, does NOT silently rewrite anything. Lints (S11) catch
;; inconsistencies at validation time.

(defn- uri->turtle-ref
  "Render a URI as a Turtle prefixed-name. Falls back to `ex:` prefix
   when the URI has no recognizable prefix."
  [uri]
  (let [p (or (uri->prefix uri) "ex")
        n (uri->local-name uri)]
    (str p ":" n)))

(defn- disjointness->turtle
  "Emit one block per class with the OTHERS as `owl:disjointWith` targets.
   The projection is already symmetric, so each class carries the full
   set; the emit dedups same-direction triples by walking the URIs and
   emitting only when the source URI sorts BEFORE the target. (Symmetric
   semantics: A disjointWith B implies B disjointWith A — but a single
   directed triple suffices in TTL.)"
  [disjointness-map]
  (str/join "\n"
            (for [[src others] disjointness-map
                  target others
                  :when (neg? (compare src target))]
              (str (uri->turtle-ref src)
                   " owl:disjointWith "
                   (uri->turtle-ref target)
                   " ."))))

(defn- characteristics->turtle
  "Emit each predicate's characteristic flags as type declarations."
  [characteristics-map inverse-of-map]
  (let [char->owl {:functional "owl:FunctionalProperty"
                  :transitive "owl:TransitiveProperty"
                  :symmetric  "owl:SymmetricProperty"}
        ;; Each predicate may carry multiple flags AND/OR an inverse-of
        ;; pairing. We emit one block per predicate with all relevant
        ;; triples coalesced.
        all-preds (set (concat (keys characteristics-map) (keys inverse-of-map)))]
    (str/join "\n"
              (for [pred all-preds
                    :let [flags (get characteristics-map pred #{})
                          inv (get inverse-of-map pred)
                          type-decls (keep char->owl flags)
                          ;; Build one block per predicate.
                          ;; rdf:type triples collapse onto one line
                          ;; via the Turtle `,` shortcut when flags are
                          ;; > 1; we keep it simple and emit one type
                          ;; line per declaration.
                          lines (cond-> []
                                  (seq type-decls)
                                  (into (map #(str (uri->turtle-ref pred) " a " % " .")
                                             type-decls))
                                  inv
                                  (conj (str (uri->turtle-ref pred)
                                             " owl:inverseOf "
                                             (uri->turtle-ref inv) " .")))]
                    :when (seq lines)]
                (str/join "\n" lines)))))

(defn- sub-property-of->turtle
  "Emit `rdfs:subPropertyOf` triples — one per sub→super mapping."
  [sub-property-of-map]
  (str/join "\n"
            (for [[sub super] sub-property-of-map]
              (str (uri->turtle-ref sub)
                   " rdfs:subPropertyOf "
                   (uri->turtle-ref super)
                   " ."))))

(defn- chain->turtle
  "Emit one `owl:propertyChainAxiom` triple per derived predicate, with
   the chain rendered as a Turtle list `( P Q ... )`."
  [chains-map]
  (str/join "\n"
            (for [[derived chain] chains-map]
              (str (uri->turtle-ref derived)
                   " owl:propertyChainAxiom ( "
                   (str/join " " (map uri->turtle-ref chain))
                   " ) ."))))

(defn axioms->turtle
  "S07 — serialize the per-ontology axiom projection ({:disjointness ...
   :characteristics ... :inverse-of ... :sub-property-of ... :chains ...})
   into OWL Turtle. Each axiom family emits as the correct OWL construct.

   When the input is the full {ontology-id -> axiom-map}, all ontologies
   are collapsed into one TTL block (consumers wanting per-ontology
   separation should call this with a single ontology's axiom-map)."
  [axioms-input]
  (let [;; Accept either a per-ontology axiom map or the full
        ;; {ontology-id -> axiom-map} shape.
        ;; Detect by looking for the expected keys at the top level.
        ontology-axioms (if (some #{:disjointness :characteristics
                                    :inverse-of :sub-property-of :chains}
                                  (keys axioms-input))
                          [axioms-input]
                          (vals axioms-input))
        merged (reduce (fn [acc m]
                         (-> acc
                             (update :disjointness (fnil merge {}) (:disjointness m))
                             (update :characteristics (fnil merge {}) (:characteristics m))
                             (update :inverse-of (fnil merge {}) (:inverse-of m))
                             (update :sub-property-of (fnil merge {}) (:sub-property-of m))
                             (update :chains (fnil merge {}) (:chains m))))
                       {}
                       ontology-axioms)
        sections [(when (seq (:disjointness merged))
                    (str "# Disjointness\n"
                         (disjointness->turtle (:disjointness merged))))
                  (when (or (seq (:characteristics merged))
                            (seq (:inverse-of merged)))
                    (str "# Property Characteristics\n"
                         (characteristics->turtle (:characteristics merged)
                                                   (:inverse-of merged))))
                  (when (seq (:sub-property-of merged))
                    (str "# Sub-Property-Of\n"
                         (sub-property-of->turtle (:sub-property-of merged))))
                  (when (seq (:chains merged))
                    (str "# Chain Axioms\n"
                         (chain->turtle (:chains merged))))]
        present (remove nil? sections)]
    (when (seq present)
      (str/join "\n\n" present))))

(defn full-export
  "Export the complete ontology including concepts, tree profiles, and node experiences.

   Arguments:
   - ctx: Context map with :event-store, :tenant-id, :cache
   - opts: Options map with:
     - :scope - Filter concepts by scope (:failure, :success, :problem)
     - :include-profiles? - Include tree profiles (default true)
     - :include-experiences? - Include node experiences (default true)
     - :include-axioms? - Include axioms (default true)
     - :base-uri - Base URI for the ontology

   S04 — when an :ontology/ontology-metadata-recorded event has been
   emitted for any ontology-id, its title/version/license/creator are
   surfaced on the exported header (owl:Ontology block). When no such
   event exists, the legacy ConceptScheme block alone forms the header
   (its hardcoded @en is removed per S04).

   S07 — when any axiom event has been emitted, the axiom projection
   is rendered into OWL constructs (`owl:disjointWith`,
   `owl:FunctionalProperty`/`owl:TransitiveProperty`/`owl:SymmetricProperty`,
   `owl:inverseOf`, `rdfs:subPropertyOf`, `owl:propertyChainAxiom`) and
   appended after the concepts block. When no axiom events exist, the
   axioms section is OMITTED entirely (no defaulted-empty triples)."
  [ctx & [{:keys [scope include-profiles? include-experiences? include-axioms? base-uri]
           :or {include-profiles? true
                include-experiences? true
                include-axioms? true
                base-uri "http://obney.ai/workshop/ontology/"}}]]
  (let [concept-graph (rmp/project ctx :ontology/concepts)
        filtered-concepts (if scope
                            (into {} (filter (fn [[_ c]] (= scope (:scope c))) concept-graph))
                            concept-graph)
        ;; S04 — pull the (first) recorded ontology-metadata to drive
        ;; the owl:Ontology header. Most consumers record one metadata
        ;; record per export; if multiple have been recorded against
        ;; different ontology-ids, the FIRST is surfaced — operators
        ;; with multi-ontology exports can call concepts->turtle
        ;; directly with the metadata they want.
        all-metadata (rmp/project ctx :ontology/ontology-metadata)
        primary-metadata (some-> all-metadata vals first)
        concepts-ttl (concepts->turtle filtered-concepts
                                       (cond-> {:base-uri base-uri}
                                         primary-metadata
                                         (assoc :ontology-metadata primary-metadata)))

        profiles-ttl (when include-profiles?
                       (let [profiles (rmp/project ctx :ontology/tree-profiles)]
                         (when (seq profiles)
                           (str "\n\n# === TREE PROFILES ===\n\n"
                                (tree-profiles->turtle profiles)))))

        experiences-ttl (when include-experiences?
                          (let [experiences (rmp/project ctx :ontology/node-experiences)]
                            (when (seq experiences)
                              (str "\n\n# === NODE EXPERIENCES ===\n\n"
                                   (node-experiences->turtle experiences)))))

        ;; S07 — axioms section
        axioms-ttl (when include-axioms?
                     (let [all-axioms (rmp/project ctx :ontology/axioms)]
                       (when-let [block (axioms->turtle all-axioms)]
                         (str "\n\n# === AXIOMS ===\n\n" block))))

        ;; S06 — edge-metadata section (reified-on-demand). Walks the
        ;; relationships projection so the section iterates EDGES, not
        ;; concepts; bare edges (no metadata) emit nothing.
        all-relationships (vals (rmp/project ctx :ontology/relationships))
        edges-meta-ttl (when-let [block (relationships->turtle all-relationships)]
                         (str "\n\n# === EDGE METADATA ===\n" block))]
    (str concepts-ttl
         (or profiles-ttl "")
         (or experiences-ttl "")
         (or axioms-ttl "")
         (or edges-meta-ttl ""))))

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
    (let [without-uris (str/replace turtle-str #"<[^>]+>" "")
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
