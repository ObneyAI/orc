(ns ai.obney.orc.ontology.core.ttl-ingest
  "S09 — TTL ingestion adapter.

   Parses a TTL string and DECOMPOSES it into the standard event
   vocabulary via commands. Every fact lands via a `:ontology/...`
   command — NEVER via bare event-store appends — so the events-first
   invariant holds end-to-end:

     ingest(ttl) → commands → events → projections → export ≍ source

   Coverage of the predecessor representation bundle:
     S04 — language-tagged labels / datatyped attributes / annotations /
           ontology-level metadata header (`owl:Ontology` block)
     S05 — QUDT quantity blocks → `:attributes` with `{:value :unit}`;
           ordered sequences emit one `immediately-follows` relationship
           per consecutive pair.
     S06 — reified `rdf:Statement` blocks carry confidence-class /
           evidence / valid-from / valid-to / superseded-by / open
           `:properties` bag; ingestion decodes them back to the
           `:ontology/create-relationship` named metadata fields.
     S07 — `owl:disjointWith` / `owl:FunctionalProperty` /
           `owl:TransitiveProperty` / `owl:SymmetricProperty` /
           `owl:inverseOf` / `rdfs:subPropertyOf` /
           `owl:propertyChainAxiom` → assert-disjointness /
           assert-property-characteristic / assert-sub-property /
           assert-chain-axiom commands.
     S08 — `owl:sameAs` / `owl:equivalentClass` /
           `owl:equivalentProperty` → record-equivalence with the
           matching `:kind`. **Critical:** owl:equivalentClass MUST NOT
           collapse to owl:sameAs — the inheritance-merge hazard the
           S08 kind discriminator guards against.

   Public entry point: `ingest-ttl!` (returns a report).

   Adversarial handling — when canonicalization fails, the ingest
   returns an anomaly with `:anomaly/message` carrying rdflib's parse
   error (line/col where present). No partial silent ingest."
  (:require [clojure.string :as str]
            [ai.obney.orc.ontology.core.ttl-canonicalize :as ttlc]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.grain.event-store-v3.interface :as es]
            [cognitect.anomalies :as anom]))

;; =============================================================================
;; URI / literal helpers
;; =============================================================================

(def ^:private known-prefixes
  "URI-expansion → prefix mapping. Mirror of
   `serialization/standard-prefixes` + `domain-prefixes`. Used to map
   the expanded URIs rdflib emits back to the prefixed form the
   command/projection vocabulary uses (e.g.,
   `<http://obney.ai/workshop/ontology/failure#Hallucination>` →
   `\"failure:Hallucination\"`)."
  {"http://www.w3.org/1999/02/22-rdf-syntax-ns#" "rdf"
   "http://www.w3.org/2000/01/rdf-schema#" "rdfs"
   "http://www.w3.org/2002/07/owl#" "owl"
   "http://www.w3.org/2001/XMLSchema#" "xsd"
   "http://www.w3.org/2004/02/skos/core#" "skos"
   "http://purl.org/dc/terms/" "dcterms"
   "http://obney.ai/workshop/ontology/orc#" "orc"
   "http://qudt.org/schema/qudt/" "qudt"
   "http://obney.ai/workshop/ontology/failure#" "failure"
   "http://obney.ai/workshop/ontology/success#" "success"
   "http://obney.ai/workshop/ontology/problem#" "problem"
   "http://obney.ai/workshop/ontology/tree#" "tree"
   "http://obney.ai/workshop/ontology/node#" "node"
   "http://obney.ai/workshop/ontology/pattern#" "pattern"
   "http://example.org/taxonomy#" "ex"})

(defn- strip-uri-brackets
  "rdflib emits URIs as `<http://...>` via term.n3(). Strip the angle
   brackets to get the raw IRI."
  [^String s]
  (if (and (str/starts-with? s "<") (str/ends-with? s ">"))
    (subs s 1 (dec (count s)))
    s))

(defn iri->prefixed
  "Convert an N-Triples IRI (`<http://obney.ai/workshop/ontology/failure#X>`)
   to a prefixed form (`\"failure:X\"`). Unknown namespaces are returned
   verbatim as `<full-iri>` so downstream emitters can still round-trip
   them via the `urn:` / `<full-iri>` paths.

   Important: this is LOSSLESS for any prefix listed in
   `known-prefixes` (mirrors the prefix table the serializer uses)."
  [^String n3-iri]
  (let [iri (strip-uri-brackets n3-iri)]
    (or (some (fn [[expansion prefix]]
                (when (str/starts-with? iri expansion)
                  (str prefix ":" (subs iri (count expansion)))))
              known-prefixes)
        n3-iri)))

(def ^:private literal-with-lang-re #"(?s)^\"(.*)\"@([A-Za-z][A-Za-z0-9-]*)$")
(def ^:private literal-with-type-re #"(?s)^\"(.*)\"\^\^<([^>]+)>$")
(def ^:private plain-literal-re #"(?s)^\"(.*)\"$")

(defn- unescape-literal
  "Reverse the N-Triples escape sequences rdflib emits in term.n3()."
  [^String s]
  (-> s
      (str/replace "\\\"" "\"")
      (str/replace "\\n" "\n")
      (str/replace "\\r" "\r")
      (str/replace "\\t" "\t")
      (str/replace "\\\\" "\\")))

(defn parse-object
  "Classify the object term of a canonical N-Triples line.
   Returns one of:
     {:kind :iri :value <prefixed-or-iri>}
     {:kind :bnode :id <_:..>}
     {:kind :literal :value <str> :lang <lang>}
     {:kind :literal :value <str> :datatype <prefixed-iri>}
     {:kind :literal :value <str>}              ; plain literal"
  [^String term]
  (cond
    (str/starts-with? term "<")
    {:kind :iri :value (iri->prefixed term)}

    (str/starts-with? term "_:")
    {:kind :bnode :id term}

    :else
    (cond
      (re-matches literal-with-lang-re term)
      (let [[_ v lang] (re-matches literal-with-lang-re term)]
        {:kind :literal :value (unescape-literal v) :lang lang})

      (re-matches literal-with-type-re term)
      (let [[_ v dt] (re-matches literal-with-type-re term)
            prefixed (iri->prefixed (str "<" dt ">"))]
        {:kind :literal :value (unescape-literal v) :datatype prefixed})

      (re-matches plain-literal-re term)
      (let [[_ v] (re-matches plain-literal-re term)]
        {:kind :literal :value (unescape-literal v)})

      :else
      {:kind :literal :value term})))

;; =============================================================================
;; Subject grouping
;; =============================================================================

(defn- group-by-subject [triples]
  (reduce (fn [acc [s p o]] (update acc s (fnil conj []) [p o]))
          {}
          triples))

(defn- po-map
  "Index a subject's [p o]s by predicate as `{prefixed-p [object-terms ...]}`.
   Predicates always parse to IRI; we lift them to prefixed form."
  [pos]
  (reduce (fn [acc [p o]]
            (update acc (iri->prefixed p) (fnil conj []) o))
          {}
          pos))

(defn- types-of
  "Return the set of prefixed-IRI types declared for the subject via
   `rdf:type`. Empty set if none."
  [pmap]
  (into #{} (map #(:value (parse-object %))) (get pmap "rdf:type" [])))

;; =============================================================================
;; xsd:datatype helper
;; =============================================================================

(defn- datatype-keyword
  "Convert a prefixed-IRI datatype (e.g. `\"xsd:integer\"`) to the
   keyword form `:xsd/integer` the serializer emits."
  [prefixed-dt]
  (let [[pfx local] (str/split prefixed-dt #":" 2)]
    (keyword pfx local)))

;; =============================================================================
;; Concept ingestion
;; =============================================================================

(defn- collect-labels
  "Collect the LANGUAGE-TAGGED `rdfs:label` literals (the S04 multi-lang
   bundle) as `{:value :lang}` entries. The `:labels` event field's
   schema requires BOTH `:value` and `:lang`, so a plain (untagged)
   `rdfs:label` does NOT belong here — including it produces a
   schema-invalid concept-created event that silently fails to persist
   (the V14 brownfield faithfulness defect). Plain `rdfs:label`s are
   instead routed to the single `:label` field by `ingest-concept!`."
  [pmap]
  (vec (for [term (get pmap "rdfs:label" [])
             :let [parsed (parse-object term)]
             :when (and (= :literal (:kind parsed)) (:lang parsed))]
         {:value (:value parsed) :lang (:lang parsed)})))

(defn- first-plain-rdfs-label
  "The first untagged `rdfs:label` literal on the subject (used as the
   back-compat single `:label` when there is no `skos:prefLabel` — the
   common brownfield shape). Returns nil when every `rdfs:label` is
   language-tagged (those flow into `:labels`)."
  [pmap]
  (some (fn [term]
          (let [parsed (parse-object term)]
            (when (and (= :literal (:kind parsed)) (not (:lang parsed)))
              (:value parsed))))
        (get pmap "rdfs:label" [])))

(defn- collect-comments
  "Language-TAGGED rdfs:comment entries (same `{:value :lang}` shape and
   same schema constraint as `collect-labels`). A plain untagged
   `rdfs:comment` is routed to the single `:comment` field by
   `ingest-concept!`, not here — including it would make the
   concept-created event schema-invalid and silently un-persistable."
  [pmap]
  (vec (for [term (get pmap "rdfs:comment" [])
             :let [parsed (parse-object term)]
             :when (and (= :literal (:kind parsed)) (:lang parsed))]
         {:value (:value parsed) :lang (:lang parsed)})))

(defn- collect-attributes
  "Walk every `orc:<key>` triple on the concept; produce an
   `{:<key> v}` attributes map. For bnode objects, resolve through the
   provided `bnodes` map (canonical-bnode-id → that bnode's pmap) and
   detect the QUDT quantity shape — collapse the
   `qudt:numericValue` + `qudt:unit` (+ optional datatype) into the
   S05 `{:value :unit (:datatype?)}` form.

   The classifier handles SCALAR (plain) values, the S04 typed-literal
   shape `{:value :datatype}`, and the S05 quantity shape."
  [pmap bnodes]
  (let [orc-keys (filter #(str/starts-with? % "orc:") (keys pmap))]
    (->> orc-keys
         (reduce
          (fn [acc full-key]
            (let [k (keyword (subs full-key 4))
                  ;; S04 spec: only ONE value per concept-attribute key in
                  ;; the round-trip case (the serializer emits each as a
                  ;; single triple; the projection asserts {:key v}, not
                  ;; a vec). Take the first term.
                  term (first (get pmap full-key))
                  parsed (parse-object term)
                  v (cond
                      ;; S05 — bnode pointing to qudt:QuantityValue
                      (= :bnode (:kind parsed))
                      (let [qpmap (get bnodes (:id parsed))
                            num-term (first (get qpmap "qudt:numericValue"))
                            unit-term (first (get qpmap "qudt:unit"))]
                        (when (and num-term unit-term)
                          (let [num-parsed (parse-object num-term)
                                unit-parsed (parse-object unit-term)
                                base {:value (cond
                                               ;; rdflib normalizes
                                               ;; `qudt:numericValue 75` (no
                                               ;; quotes) into a typed
                                               ;; xsd:integer literal. Parse
                                               ;; numeric literals back as
                                               ;; numbers when the datatype is
                                               ;; xsd:integer / xsd:double /
                                               ;; xsd:decimal so the
                                               ;; round-trip emits the same
                                               ;; lexical form.
                                               (= :literal (:kind num-parsed))
                                               (if (:datatype num-parsed)
                                                 (let [v (:value num-parsed)
                                                       dt (:datatype num-parsed)]
                                                   (cond
                                                     (= dt "xsd:integer") (Long/parseLong v)
                                                     (= dt "xsd:double") (Double/parseDouble v)
                                                     (= dt "xsd:decimal") (bigdec v)
                                                     :else v))
                                                 (:value num-parsed))
                                               :else (:value num-parsed))
                                       :unit (:value unit-parsed)}]
                            (cond-> base
                              (and (:datatype num-parsed)
                                   ;; preserve the numeric datatype only
                                   ;; when it isn't already represented
                                   ;; by the parsed numeric value's class.
                                   (not (contains?
                                         #{"xsd:integer" "xsd:double" "xsd:decimal"}
                                         (:datatype num-parsed))))
                              (assoc :datatype
                                     (datatype-keyword (:datatype num-parsed)))))))

                      ;; S04 typed literal — preserve `{:value :datatype}`
                      (and (= :literal (:kind parsed)) (:datatype parsed))
                      {:value (:value parsed)
                       :datatype (datatype-keyword (:datatype parsed))}

                      ;; Plain literal — bare value
                      (= :literal (:kind parsed))
                      (:value parsed)

                      :else nil)]
              (cond-> acc
                (some? v) (assoc k v))))
          {}))))

(defn- ingest-ontology-header!
  "S04 — ingest an `<base-uri> a owl:Ontology` subject as a
   `record-ontology-metadata` command. Returns the command body invoked.
   The caller supplies an `ontology-id` UUID that becomes the projection
   key."
  [ctx ontology-id pmap]
  (let [pick (fn [pred parser]
               (when-let [t (first (get pmap pred))]
                 (parser (parse-object t))))
        title   (pick "dcterms:title" :value)
        version (pick "owl:versionInfo" :value)
        license (some-> (first (get pmap "dcterms:license"))
                        parse-object
                        ((fn [o] (when (= :iri (:kind o))
                                   ;; license keeps its IRI form; convert
                                   ;; the prefixed form back to a full IRI
                                   ;; for the projection so the serializer
                                   ;; re-emits `<http://...>` faithfully.
                                   (let [v (:value o)]
                                     (if (str/includes? v ":")
                                       (let [[pfx local] (str/split v #":" 2)]
                                         (or (some (fn [[expansion pp]]
                                                     (when (= pp pfx)
                                                       (str expansion local)))
                                                   known-prefixes)
                                             v))
                                       v))))))
        creator (pick "dcterms:creator" :value)
        body (cond-> {:ontology-id ontology-id}
               title   (assoc :title title)
               version (assoc :version version)
               license (assoc :license license)
               creator (assoc :creator creator))]
    (cmd/ontology-record-ontology-metadata (assoc ctx :command body))))

;; =============================================================================
;; Reified statements & relationships
;; =============================================================================

(defn- decode-reified-statement
  "Decode one reified `rdf:Statement` bnode into a relationship-command
   body. Returns `{:source-uri ... :target-uri ... :predicate ...
                   :confidence-class? :evidence? :valid-from? :valid-to?
                   :superseded-by? :properties?}` — only the fields the
   bnode supplied."
  [stmt-pmap bnodes]
  (let [subj-term (first (get stmt-pmap "rdf:subject"))
        pred-term (first (get stmt-pmap "rdf:predicate"))
        obj-term  (first (get stmt-pmap "rdf:object"))
        source-uri (:value (parse-object subj-term))
        target-uri (:value (parse-object obj-term))
        predicate  (:value (parse-object pred-term))
        cc-term    (first (get stmt-pmap "orc:confidenceClass"))
        cc         (when cc-term (keyword (:value (parse-object cc-term))))
        valid-from (some-> (first (get stmt-pmap "orc:validFrom"))
                           parse-object :value)
        valid-to   (some-> (first (get stmt-pmap "orc:validTo"))
                           parse-object :value)
        superseded-by-term (first (get stmt-pmap "orc:supersededBy"))
        superseded-by (when superseded-by-term
                        (let [iri (strip-uri-brackets superseded-by-term)
                              ;; `urn:rel:<uuid>` form
                              uuid-str (last (str/split iri #":"))]
                          (try (java.util.UUID/fromString uuid-str)
                               (catch IllegalArgumentException _ nil))))
        evidence-terms (get stmt-pmap "orc:evidence" [])
        evidence (vec (for [et evidence-terms
                            :let [parsed (parse-object et)]
                            :when (= :bnode (:kind parsed))
                            :let [ebody (get bnodes (:id parsed))
                                  source-t (first (get ebody "orc:source"))
                                  quote-t  (first (get ebody "orc:quote"))]
                            :when (or source-t quote-t)]
                        (cond-> {}
                          source-t (assoc :source (strip-uri-brackets source-t))
                          quote-t  (assoc :quote  (:value (parse-object quote-t))))))
        ;; Open `:properties` bag — any `orc:<key>` that isn't one of
        ;; the named-metadata fields above. Use plain values; the open
        ;; bag does not currently round-trip per-key datatypes.
        named-keys #{"orc:confidenceClass" "orc:evidence" "orc:validFrom"
                     "orc:validTo" "orc:supersededBy"}
        orc-keys (filter #(and (str/starts-with? % "orc:")
                               (not (named-keys %)))
                         (keys stmt-pmap))
        properties (reduce
                    (fn [acc full-key]
                      (let [k (keyword (subs full-key 4))
                            v (:value (parse-object (first (get stmt-pmap full-key))))]
                        (assoc acc k v)))
                    {}
                    orc-keys)]
    (cond-> {:source-uri source-uri
             :target-uri target-uri
             :predicate predicate}
      cc             (assoc :confidence-class cc)
      (seq evidence) (assoc :evidence evidence)
      valid-from     (assoc :valid-from valid-from)
      valid-to       (assoc :valid-to valid-to)
      superseded-by  (assoc :superseded-by superseded-by)
      (seq properties) (assoc :properties properties))))

;; =============================================================================
;; Top-level subject classification
;; =============================================================================

(def ^:private owl-meta-types
  "Structural / OWL-meta `rdf:type` values that DECLARE the schema rather
   than instantiate a domain concept. A subject whose ONLY types are
   drawn from this set is never recognized as a concept by the broadened
   default recognizer (V14). `owl:Ontology` is handled by the dedicated
   ontology-header ingester; the property/class declarations feed the
   axiom/characteristic ingesters. These are PREFIXED forms — the owl /
   rdf / rdfs namespaces are always in `known-prefixes`, so an
   `a owl:Class` triple resolves to the literal `\"owl:Class\"` regardless
   of the source's domain prefixes.

   Property-CHARACTERISTIC declarations (`owl:FunctionalProperty` /
   `owl:TransitiveProperty` / `owl:SymmetricProperty`) are also structural
   meta — they declare a predicate's characteristic and feed
   `ingest-characteristics!`, NOT a domain concept. They are included here
   so a property-declaration subject is never misread as a concept (the
   S09 bundle's `ex:hasManager` / `ex:reports-to` / `ex:colleague-of`
   property declarations are the regression guard for this)."
  #{"owl:Ontology" "owl:Class" "owl:DatatypeProperty" "owl:ObjectProperty"
    "owl:AnnotationProperty" "rdf:Property" "rdfs:Class"
    "owl:FunctionalProperty" "owl:TransitiveProperty" "owl:SymmetricProperty"})

(defn- type-local-name
  "The local name of a (prefixed-or-raw) `rdf:type` string — the segment
   after the last `#`, `/`, or `:`. `\"edu:EducationalProgram\"` and the
   raw `\"<http://example.org/education#EducationalProgram>\"` both reduce
   to `\"EducationalProgram\"`. Used so a caller-supplied :concept-types
   set expressed in prefixed form still matches types the parser left in
   raw `<full-iri>` form (the production example.org namespaces are not in
   `known-prefixes`)."
  [^String t]
  (let [bare (strip-uri-brackets t)
        idx (max (.lastIndexOf bare "#")
                 (.lastIndexOf bare "/")
                 (.lastIndexOf bare ":"))]
    (if (neg? idx) bare (subs bare (inc idx)))))

(defn- caller-concept-type?
  "True when the subject's type-set `ts` matches the caller-supplied
   `concept-types` set. Matches either EXACTLY (the type string equals a
   set entry) OR by local name (so a prefixed entry like
   `\"edu:EducationalProgram\"` matches a raw
   `\"<http://example.org/education#EducationalProgram>\"` type). The
   local-name match is structural (IRI-segment equality), NOT label
   string-matching."
  [ts concept-types]
  (let [type-locals (into #{} (map type-local-name) concept-types)]
    (boolean
     (some (fn [t]
             (or (contains? concept-types t)
                 (contains? type-locals (type-local-name t))))
           ts))))

(defn- concept-subject?
  "Decide whether a subject (given its `rdf:type` set `ts`) is a concept
   (V14 brownfield recognition). STRUCTURAL — type-set membership only;
   never label string-matching.

   Resolution order:
   1. When the caller supplies `concept-types`, a subject is a concept iff
      one of its types matches that set (exact OR local-name; see
      `caller-concept-type?`). Explicit override — takes precedence over
      every default rule.
   2. Otherwise a subject is a concept iff it is typed `skos:Concept`
      (preserves the pre-V14 behavior) OR it carries at least one type
      that is NOT an OWL-meta type. A subject whose only types are
      structural meta (owl:Class / owl:Ontology / property declarations)
      is NOT a concept."
  [ts concept-types]
  (if (seq concept-types)
    (caller-concept-type? ts concept-types)
    (or (contains? ts "skos:Concept")
        (boolean (some (complement owl-meta-types) ts)))))

(def ^:private skos-relationship-predicates
  "SKOS hierarchy/related predicates that decompose into plain
   relationships when present directly on a concept subject (no
   accompanying rdf:Statement reified block)."
  {"skos:broader"  "skos:broader"
   "skos:narrower" "skos:narrower"
   "skos:related"  "skos:related"})

(def ^:private concept-only-predicates
  "Predicates that surface on a concept subject's pmap but are NOT
   relationships — they're attributes / annotations / labels that the
   `ingest-concept!` path already consumes. The relationship-emission
   loop skips these explicitly so they don't double-emit as
   relationships."
  #{"rdf:type" "skos:prefLabel" "rdfs:label" "rdfs:comment"
    "skos:definition" "skos:scopeNote" "rdfs:seeAlso" "rdfs:isDefinedBy"
    "orc:modelGuidance" "skos:hiddenLabel"})

(defn- attribute-predicate?
  "`orc:<key>` predicates land on the concept as attributes, not
   relationships."
  [pred]
  (and (string? pred) (str/starts-with? pred "orc:")))

(defn- axiom-predicate?
  "Axiom / equivalence predicates that the dedicated axiom/equivalence
   ingesters consume from the global triples sweep — NOT emitted from
   the concept-relationship loop."
  [pred]
  (contains? #{"owl:disjointWith" "owl:inverseOf" "rdfs:subPropertyOf"
               "owl:propertyChainAxiom" "owl:sameAs"
               "owl:equivalentClass" "owl:equivalentProperty"}
             pred))

(defn- ingest-concept-relationships!
  "For each non-attribute, non-annotation predicate on a concept's
   pmap, emit a `:ontology/create-relationship` command. Each bare
   triple `<s> <p> <o>` becomes a relationship with no metadata.
   Reified blocks (S06) are handled separately so the metadata lands.

   `reified-targets` is a set of `[source target predicate]` tuples
   the caller has already emitted via reified-statement decoding —
   we suppress the plain emit for those pairs so each fact only lands
   ONCE.

   S09 — non-SKOS predicates ALSO emit. The `skos:broader/narrower/
   related` predicates take the SKOS-branch projection path; any other
   predicate (`<.../immediately-follows>`, behavior:composes-into,
   custom domain predicates) takes the typed-edges path. Both paths
   must round-trip; the previous version skipped non-SKOS predicates
   and lost them on re-export.

   The subject must arrive as the PREFIXED form (`ex:Foo`) so it
   matches the URI key the concept-created event committed."
  [ctx ontology-id subject-iri-raw pmap reified-targets]
  (let [subject-iri (iri->prefixed subject-iri-raw)
        emit! (fn [target-iri predicate]
                (let [key [subject-iri target-iri predicate]]
                  (when-not (contains? reified-targets key)
                    (cmd/ontology-create-relationship
                     (assoc ctx :command
                            {:ontology-id ontology-id
                             :source-uri subject-iri
                             :target-uri target-iri
                             :predicate predicate})))))
        results (atom [])]
    (doseq [[pred terms] pmap
            :when (and (not (concept-only-predicates pred))
                       (not (attribute-predicate? pred))
                       (not (axiom-predicate? pred))
                       ;; characteristics (rdf:type owl:FunctionalProperty
                       ;; etc.) are handled by ingest-characteristics!; the
                       ;; concept-only-predicates set already excludes
                       ;; rdf:type above. But OWL property characteristics
                       ;; route via rdf:type with an OWL-class object,
                       ;; never as a relationship — so the exclusion above
                       ;; (rdf:type) handles them.
                       )]
      (doseq [t terms
              :let [parsed (parse-object t)]
              :when (= :iri (:kind parsed))]
        (let [r (emit! (:value parsed) pred)]
          (when r (swap! results conj r)))))
    @results))

(defn- ingest-concept!
  "Decode and emit a `:ontology/create-concept` command for one
   concept subject. Returns the command result.

   The `subject-iri-raw` is the N-Triples-form term (e.g.
   `<http://example.org/taxonomy#Wake>`); we lift to its prefixed form
   here so the projected `:uri` matches the prefixed URIs the reified-
   statement decoder uses on `:source-uri` / `:target-uri`. Without
   this, `relationship-created` events update a *different* URI key
   than `concept-created` did — producing phantom related-only concept
   shells in the projection."
  [ctx ontology-id subject-iri-raw pmap bnodes]
  (let [subject-iri (iri->prefixed subject-iri-raw)
        label-terms (get pmap "skos:prefLabel" [])
        ;; Pick the first plain literal as the back-compat single label;
        ;; the multi-lang labels go into :labels.
        pref-label  (some-> (first label-terms) parse-object :value)
        description (some-> (first (get pmap "skos:definition" []))
                            parse-object :value)
        scope       (some-> (first (get pmap "skos:scopeNote" []))
                            parse-object :value keyword)
        labels      (collect-labels pmap)
        comments    (collect-comments pmap)
        comment     (some-> (first (get pmap "rdfs:comment" []))
                            parse-object
                            ;; only treat as :comment if the parsed
                            ;; object has NO :lang (i.e. it's the plain
                            ;; rdfs:comment, not a tagged multi-lang
                            ;; entry — those go into :comments).
                            ((fn [p] (when (not (:lang p)) (:value p)))))
        see-also    (vec (for [t (get pmap "rdfs:seeAlso" [])
                               :let [o (parse-object t)]
                               :when (= :iri (:kind o))]
                           (:value o)))
        is-defined-by (some-> (first (get pmap "rdfs:isDefinedBy" []))
                              parse-object
                              ((fn [o] (when (= :iri (:kind o))
                                         ;; isDefinedBy is a full IRI in
                                         ;; the projection; expand the
                                         ;; prefixed form back.
                                         (let [v (:value o)]
                                           (if (str/includes? v ":")
                                             (let [[pfx local] (str/split v #":" 2)]
                                               (or (some (fn [[expansion pp]]
                                                           (when (= pp pfx)
                                                             (str expansion local)))
                                                         known-prefixes)
                                                   v))
                                             v))))))
        model-guidance (some-> (first (get pmap "orc:modelGuidance" []))
                               parse-object :value)
        indicators (vec (for [t (get pmap "skos:hiddenLabel" [])
                              :let [o (parse-object t)]
                              :when (= :literal (:kind o))]
                          (:value o)))
        attributes (let [non-mg (dissoc pmap "orc:modelGuidance")]
                     (collect-attributes non-mg bnodes))
        body (cond-> {:ontology-id ontology-id
                      :uri subject-iri
                      ;; Brownfield concepts often carry only a plain
                      ;; (untagged) rdfs:label and no skos:prefLabel — use
                      ;; it as the single :label so the concept is
                      ;; legible, falling back to the URI only when there
                      ;; is no usable label at all.
                      :label (or pref-label
                                 (first-plain-rdfs-label pmap)
                                 subject-iri)
                      :description (or description "")
                      :scope (or scope :custom)}
               (seq labels)        (assoc :labels labels)
               (seq comments)      (assoc :comments comments)
               comment             (assoc :comment comment)
               (seq see-also)      (assoc :see-also see-also)
               is-defined-by       (assoc :is-defined-by is-defined-by)
               model-guidance      (assoc :model-guidance model-guidance)
               (seq attributes)    (assoc :attributes attributes)
               (seq indicators)    (assoc :indicators indicators))]
    (cmd/ontology-create-concept (assoc ctx :command body))))

(defn- ingest-disjointness!
  "For every `<a> owl:disjointWith <b>` triple, accumulate connected
   components. Each component emits ONE `assert-disjointness` command.
   Symmetric — both `<a> disjointWith <b>` and `<b> disjointWith <a>`
   contribute to the same component."
  [ctx ontology-id triples]
  (let [pairs (for [[s p o] triples
                    :when (= "owl:disjointWith" (iri->prefixed p))]
                #{(iri->prefixed s) (:value (parse-object o))})
        ;; union-find via reduction — for each pair, merge into the set
        ;; that already contains either endpoint.
        components (reduce (fn [comps pair]
                             (let [overlap (filter #(seq (clojure.set/intersection % pair))
                                                   comps)
                                   merged (apply clojure.set/union pair overlap)
                                   rest-comps (remove (set overlap) comps)]
                               (conj rest-comps merged)))
                           []
                           pairs)]
    (vec (for [comp components
               :when (>= (count comp) 2)]
           (cmd/ontology-assert-disjointness
            (assoc ctx :command
                   {:ontology-id ontology-id
                    :class-uris (vec (sort comp))}))))))

(defn- ingest-characteristics!
  "Each predicate may carry `owl:FunctionalProperty` /
   `owl:TransitiveProperty` / `owl:SymmetricProperty` /
   `owl:inverseOf` triples. Group by subject (the predicate) and emit
   ONE `assert-property-characteristic` command per predicate."
  [ctx ontology-id triples]
  (let [by-pred (reduce
                 (fn [acc [s p o]]
                   (let [s* (iri->prefixed s)
                         o-parsed (parse-object o)]
                     (case (iri->prefixed p)
                       "rdf:type"
                       (let [ov (:value o-parsed)]
                         (case ov
                           "owl:FunctionalProperty" (update-in acc [s* :characteristic] (fnil conj #{}) :functional)
                           "owl:TransitiveProperty" (update-in acc [s* :characteristic] (fnil conj #{}) :transitive)
                           "owl:SymmetricProperty"  (update-in acc [s* :characteristic] (fnil conj #{}) :symmetric)
                           acc))
                       "owl:inverseOf"
                       (assoc-in acc [s* :inverse-of] (:value o-parsed))
                       acc)))
                 {}
                 triples)]
    (vec (for [[predicate {:keys [characteristic inverse-of]}] by-pred
               :when (or (seq characteristic) inverse-of)]
           (cmd/ontology-assert-property-characteristic
            (assoc ctx :command
                   (cond-> {:ontology-id ontology-id
                            :predicate predicate
                            :characteristic (vec characteristic)}
                     inverse-of (assoc :inverse-of inverse-of))))))))

(defn- ingest-sub-properties!
  [ctx ontology-id triples]
  (vec (for [[s p o] triples
             :when (= "rdfs:subPropertyOf" (iri->prefixed p))
             :let [sub (iri->prefixed s)
                   super (:value (parse-object o))]]
         (cmd/ontology-assert-sub-property
          (assoc ctx :command
                 {:ontology-id ontology-id
                  :sub-predicate sub
                  :super-predicate super})))))

(defn- collect-rdf-list
  "Walk an RDF Collection rooted at `head` (a bnode) via the standard
   `rdf:first` / `rdf:rest` / `rdf:nil` linkage. Returns a vector of
   the prefixed-IRI items."
  [head bnodes]
  (loop [node head, acc []]
    (cond
      (nil? node) acc
      (= node "<http://www.w3.org/1999/02/22-rdf-syntax-ns#nil>") acc
      (= node "rdf:nil") acc
      :else
      (let [pmap (get bnodes node)
            first-t (first (get pmap "rdf:first"))
            rest-t  (first (get pmap "rdf:rest"))
            item (when first-t (:value (parse-object first-t)))
            next-node (cond
                        (nil? rest-t) nil
                        (str/starts-with? rest-t "_:") rest-t
                        :else nil)]
        (if item
          (recur next-node (conj acc item))
          acc)))))

(defn- ingest-chains!
  "S07 — `<derived> owl:propertyChainAxiom ( P1 P2 P3 )` decomposes back
   to `assert-chain-axiom`. The chain is a bnode-rooted rdf:List."
  [ctx ontology-id triples bnodes]
  (vec (for [[s p o] triples
             :when (= "owl:propertyChainAxiom" (iri->prefixed p))
             :let [derived (iri->prefixed s)
                   chain (collect-rdf-list o bnodes)]
             :when (seq chain)]
         (cmd/ontology-assert-chain-axiom
          (assoc ctx :command
                 {:ontology-id ontology-id
                  :chain (vec chain)
                  :derived-predicate derived})))))

(def ^:private equivalence-predicate->kind
  {"owl:sameAs"             :same-as
   "owl:equivalentClass"    :equivalent-class
   "owl:equivalentProperty" :equivalent-property})

(defn- ingest-equivalences!
  "S08 — `<a> owl:sameAs/equivalentClass/equivalentProperty <b>`
   decompose to `record-equivalence` with the matching `:kind`. The
   kind is LOAD-BEARING: an `owl:equivalentClass` triple MUST land as
   `:equivalent-class`, never collapse to `:same-as`."
  [ctx ontology-id triples]
  (vec (for [[s p o] triples
             :let [kind (equivalence-predicate->kind (iri->prefixed p))]
             :when kind
             :let [source (iri->prefixed s)
                   target (:value (parse-object o))]]
         (cmd/ontology-record-equivalence
          (assoc ctx :command
                 {:ontology-id ontology-id
                  :source-uri source
                  :target-uri target
                  :kind kind})))))

;; =============================================================================
;; Public ingest entry point
;; =============================================================================

(defn- apply-events!
  "Append the events from a command result to the event store. Used
   so `ingest-ttl!` can land all events from a sequence of commands
   atomically per command and the subsequent projection-driven
   commands (which need to see prior writes) can read updated state."
  [ctx result]
  (when-let [evs (seq (:command-result/events result))]
    (es/append (:event-store ctx) {:events (vec evs)
                                   :tenant-id (:tenant-id ctx)}))
  result)

(defn ingest-ttl!
  "Parse `ttl-string` and decompose it into the standard event
   vocabulary via commands. Returns a REPORT map:

     {:ingested? true
      :ontology-id <uuid>
      :triples-parsed N
      :counts {:concept N :relationship N :ontology-metadata N
               :equivalence N :disjointness N :characteristic N
               :sub-property N :chain-axiom N}
      :commands [<result> ...]}

   On parse failure, returns the anomaly map from `canonicalize-ttl`
   verbatim — `::anom/category ::anom/incorrect` with
   `:anomaly/message` carrying rdflib's error text with line/col
   position (when rdflib supplies it). NO partial silent ingest — the
   caller can detect failure via the `::anom/category` key.

   `opts`:
     :ontology-id   — UUID to scope all events under. Generated if absent.
     :concept-types — optional set of prefixed `rdf:type` strings (e.g.
                      `#{\"edu:EducationalProgram\" \"cip:CIPCode\"}`). When
                      present, ONLY subjects typed with one of these are
                      treated as concepts (takes precedence over the
                      broadened default recognizer). When absent, a subject
                      is a concept when it is typed `skos:Concept` OR typed
                      with any non-OWL-meta domain class (V14 brownfield
                      recognition).

   No false green (V14): when the graph has N typed non-meta subjects but
   the recognizer matched 0 of them, the report carries `:recognized 0`,
   `:typed-subjects N`, and an `:anomaly` string — so a consumer checking
   `:ingested?` alone cannot mistake a total zero-ingest for success."
  [ctx ttl-string & [{:keys [ontology-id concept-types] :as opts}]]
  (let [canonical (ttlc/canonicalize-ttl ttl-string)]
    (if (map? canonical)
      canonical
      (let [triples (ttlc/parse-canonical-ntriples canonical)
            by-subj (group-by-subject triples)
            ;; Index bnode subjects' pmaps for cross-bnode references
            ;; (reified-statements pointing at evidence bnodes;
            ;; rdf:List nodes for chains; QUDT QuantityValue blocks).
            bnodes (into {} (for [[s pos] by-subj
                                  :when (str/starts-with? s "_:")]
                              [s (po-map pos)]))
            ;; Classify each subject by its types.
            subj->pmap (into {} (for [[s pos] by-subj
                                      :when (not (str/starts-with? s "_:"))]
                                  [s (po-map pos)]))
            ;; rdf:type lookups
            sub-types (into {} (for [[s pmap] subj->pmap]
                                 [s (types-of pmap)]))
            ;; Concepts (V14): skos:Concept OR any non-OWL-meta domain
            ;; class, with a caller-supplied :concept-types set overriding.
            concept-subjects (vec (for [[s ts] sub-types
                                        :when (concept-subject? ts concept-types)]
                                    s))
            ;; No-false-green accounting: how many subjects carry at least
            ;; one non-OWL-meta type (i.e. are candidates for being a
            ;; concept), independent of whether the recognizer matched
            ;; them. A graph full of these but with 0 recognized is the
            ;; silent-zero-ingest the report must NOT hide.
            typed-subjects (count (filter (fn [[_ ts]]
                                            (some (complement owl-meta-types) ts))
                                          sub-types))
            ;; Ontology header: types include owl:Ontology
            ontology-subjects (vec (for [[s ts] sub-types
                                         :when (contains? ts "owl:Ontology")]
                                     s))
            ;; Use the supplied ontology-id, or generate one (every
            ;; concept/relationship/etc. is scoped under the same id).
            oid (or ontology-id (random-uuid))
            ;; First: ontology metadata (header has no dependencies)
            metadata-results (vec (for [s ontology-subjects
                                        :let [r (apply-events!
                                                 ctx
                                                 (ingest-ontology-header!
                                                  ctx oid (get subj->pmap s)))]]
                                    r))
            ;; Next: concepts (must precede relationships so the
            ;; concept projection sees them first; this is purely an
            ;; ordering convention, not a hard dep — relationships
            ;; don't read concept state in the projection).
            concept-results (vec (for [s concept-subjects]
                                   (apply-events!
                                    ctx
                                    (ingest-concept! ctx oid s
                                                     (get subj->pmap s)
                                                     bnodes))))
            ;; Reified rdf:Statement bnodes → relationship commands
            ;; with the full metadata payload.
            reified-stmts (for [[bid pmap] bnodes
                                :when (contains? (types-of pmap)
                                                 "rdf:Statement")]
                            [bid pmap])
            reified-results (vec (for [[_ pmap] reified-stmts
                                       :let [body (decode-reified-statement
                                                   pmap bnodes)]]
                                   (apply-events!
                                    ctx
                                    (cmd/ontology-create-relationship
                                     (assoc ctx :command
                                            (assoc body :ontology-id oid))))))
            ;; Track which (source target predicate) tuples reified
            ;; statements already covered, so plain SKOS triples on
            ;; concept subjects don't double-emit.
            reified-targets (set (for [[_ pmap] reified-stmts
                                       :let [body (decode-reified-statement
                                                   pmap bnodes)]]
                                   [(:source-uri body)
                                    (:target-uri body)
                                    (:predicate body)]))
            ;; Bare SKOS + non-SKOS relationships directly on concept
            ;; subjects without a reified-statement counterpart. Each
            ;; result is `apply-events!`-routed here so the events
            ;; land in the store — `ingest-concept-relationships!`
            ;; returns command-results, NOT applied events.
            bare-rel-results (vec
                              (for [s concept-subjects
                                    r (ingest-concept-relationships!
                                       ctx oid s
                                       (get subj->pmap s)
                                       reified-targets)]
                                (apply-events! ctx r)))
            ;; Axioms: disjointness, characteristics, sub-properties, chains
            disj-results (ingest-disjointness! ctx oid triples)
            char-results (ingest-characteristics! ctx oid triples)
            subp-results (ingest-sub-properties! ctx oid triples)
            chain-results (ingest-chains! ctx oid triples bnodes)
            equiv-results (ingest-equivalences! ctx oid triples)
            ;; Apply axiom events
            apply-all (fn [rs] (doseq [r rs] (apply-events! ctx r)))
            _ (apply-all disj-results)
            _ (apply-all char-results)
            _ (apply-all subp-results)
            _ (apply-all chain-results)
            _ (apply-all equiv-results)
            recognized (count concept-results)
            ;; No false green (V14): N typed candidate subjects exist but
            ;; the recognizer matched zero. Surface it explicitly so a
            ;; consumer cannot read `:ingested? true` as success.
            zero-of-n? (and (pos? typed-subjects) (zero? recognized))
            anomaly (when zero-of-n?
                      (str "Recognized 0 concepts of " typed-subjects
                           " typed non-meta subjects — possible silent"
                           " zero-ingest. Check the source's rdf:type"
                           " classes against the recognizer (supply"
                           " :concept-types to be explicit)."))]
        {:ingested? true
         :ontology-id oid
         :triples-parsed (count triples)
         :recognized recognized
         :typed-subjects typed-subjects
         :anomaly anomaly
         :counts {:concept (count concept-results)
                  :ontology-metadata (count metadata-results)
                  :relationship (+ (count reified-results)
                                   (count bare-rel-results))
                  :equivalence (count equiv-results)
                  :disjointness (count disj-results)
                  :characteristic (count char-results)
                  :sub-property (count subp-results)
                  :chain-axiom (count chain-results)}
         :commands (vec (concat metadata-results
                                concept-results
                                reified-results
                                bare-rel-results
                                disj-results
                                char-results
                                subp-results
                                chain-results
                                equiv-results))}))))
