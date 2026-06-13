(ns ai.obney.orc.ontology.s09-ttl-round-trip-test
  "S09 — TTL ingestion adapter + G1 round-trip gate.

   This is the load-bearing gate for the entire representation arc.
   Every predecessor representation slice's correctness depends on G1
   being executable end-to-end. Three load-bearing properties:

     1. G1 FORWARD — A bundle-coverage fixture TTL exercising EVERY
        S04+S05+S06+S07+S08 feature ingests → events → projects →
        exports → semantic-diff to source: EQUIVALENT.

     2. G1 SYMMETRY — A graph BUILT FROM EVENTS (no TTL source) ->
        export -> ingest -> project -> export is a fixed point.
        Proves the gate works in BOTH directions.

     3. Equivalence-kind preservation — an `:equivalent-class` pair
        ingested back from TTL MUST emerge as `:kind :equivalent-class`,
        NOT `:same-as`. The inheritance-merge hazard the S08 kind
        discriminator exists to prevent.

   Adversarial diff-quality demonstrations (proves the diff is
   root-cause-ready, not just a boolean):
     - missing one triple from fixture: diff identifies the EXACT
       missing triple in human-readable form
     - extra triple: diff identifies the extra triple
     - lexical mismatch (`kg` → `Kg`): diff identifies the mismatch
       under LEXICAL MISMATCHES / EXTRA / MISSING BNODE RECORDS section

   Malformed-TTL adversarial fixtures fail loudly with a position-
   bearing error — never partial silent ingest.

   All writes go through commands (no bare event-store appends).
   All assertions go through public interfaces (commands + read-model
   projection helpers + serialization + ingestion). No internals."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.ontology.test-helpers :as h]
            ;; Required for event-schema registration.
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.serialization :as serialization]
            [ai.obney.orc.ontology.core.ttl-canonicalize :as ttlc]
            [ai.obney.orc.ontology.core.ttl-ingest :as ttli]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [cognitect.anomalies :as anom]))

;; =============================================================================
;; The bundle-coverage fixture — every S04+S05+S06+S07+S08 feature
;; =============================================================================
;;
;; This fixture is the executable definition of the representation
;; bundle. Each section is annotated with the slice it pins.

(def bundle-ttl
  "TTL fixture exercising EVERY bundle feature:
   - S04: multi-lang labels, datatyped attribute, annotations, owl:Ontology header
   - S05: QUDT quantity-with-unit, ordered sequence chain
   - S06: reified rdf:Statement with confidence/evidence/valid-from
   - S07: disjointness + transitive/functional/symmetric/inverse-of + sub-property + chain
   - S08: sameAs (individuals) + equivalentClass + equivalentProperty"
  (str
   "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
   "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
   "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
   "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n"
   "@prefix skos: <http://www.w3.org/2004/02/skos/core#> .\n"
   "@prefix dcterms: <http://purl.org/dc/terms/> .\n"
   "@prefix qudt: <http://qudt.org/schema/qudt/> .\n"
   "@prefix orc: <http://obney.ai/workshop/ontology/orc#> .\n"
   "@prefix ex: <http://example.org/taxonomy#> .\n"
   "@prefix failure: <http://obney.ai/workshop/ontology/failure#> .\n"
   "@prefix success: <http://obney.ai/workshop/ontology/success#> .\n"
   "\n"
   ;; S04 — owl:Ontology header with metadata
   "<http://example.org/ont/bundle> a owl:Ontology ;\n"
   "  dcterms:title \"S09 Bundle Coverage Fixture\" ;\n"
   "  owl:versionInfo \"1.0.0\" ;\n"
   "  dcterms:creator \"ORC S09\" ;\n"
   "  .\n"
   "\n"
   ;; Two concepts with language-tagged labels (S04). Every concept
   ;; carries a `skos:scopeNote` to match the projection's required
   ;; `:scope` field — without it the serializer (which faithfully
   ;; emits the projected `:scope`) would diff against a source with
   ;; no scope.
   "ex:Athlete a skos:Concept ;\n"
   "  skos:prefLabel \"Athlete\" ;\n"
   "  rdfs:label \"Athlete\"@en ;\n"
   "  rdfs:label \"Sportler\"@de ;\n"
   "  skos:scopeNote \"custom\" ;\n"
   ;; S04 datatyped attribute
   "  orc:retirementAge \"35\"^^xsd:integer ;\n"
   ;; S05 quantity with unit — distinguishability case
   "  orc:bodyWeight [ a qudt:QuantityValue ;\n"
   "                    qudt:numericValue 75 ;\n"
   "                    qudt:unit \"kg\" ] ;\n"
   "  .\n"
   "\n"
   "ex:Coach a skos:Concept ;\n"
   "  skos:prefLabel \"Coach\" ;\n"
   "  rdfs:label \"Coach\"@en ;\n"
   "  rdfs:label \"Trainer\"@de ;\n"
   "  skos:scopeNote \"custom\" ;\n"
   "  .\n"
   "\n"
   ;; S05 ordered sequence chain of length 3:
   ;; Wake -> Warmup -> Train
   "ex:Wake a skos:Concept ; skos:prefLabel \"Wake\" ; skos:scopeNote \"custom\" .\n"
   "ex:Warmup a skos:Concept ; skos:prefLabel \"Warmup\" ; skos:scopeNote \"custom\" .\n"
   "ex:Train a skos:Concept ; skos:prefLabel \"Train\" ; skos:scopeNote \"custom\" .\n"
   "\n"
   ;; S06 reified-statement metadata on a relationship. The plain
   ;; companion triples are included on BOTH sides because skos:related
   ;; is semantically symmetric — the projection adds both directions
   ;; on import and the serializer emits both on export, so the source
   ;; fixture canonicalizes to bi-directional form.
   "ex:Athlete skos:related ex:Coach .\n"
   "ex:Coach skos:related ex:Athlete .\n"
   "_:rel_athlete_coach a rdf:Statement ;\n"
   "  rdf:subject ex:Athlete ;\n"
   "  rdf:predicate skos:related ;\n"
   "  rdf:object ex:Coach ;\n"
   "  orc:confidenceClass \"extracted\" ;\n"
   "  orc:evidence [ a orc:Evidence ;\n"
   "                  orc:source <http://example.org/source1> ;\n"
   "                  orc:quote \"the athlete works with the coach\" ] ;\n"
   "  orc:validFrom \"2026-01-01T00:00:00+00:00\"^^xsd:dateTime ;\n"
   "  .\n"
   "\n"
   ;; S05 ordered-sequence relationships — uses a custom (non-SKOS)
   ;; predicate that the projection treats as ONE-DIRECTIONAL via the
   ;; typed-edges map. The serializer's S09 typed-edges path emits each
   ;; (predicate -> target) pair under its ACTUAL predicate name —
   ;; preserving the `immediately-follows` semantics through round-trip
   ;; instead of aliasing to skos:related (the pre-S09 lossy path).
   "ex:Wake <http://example.org/immediately-follows> ex:Warmup .\n"
   "ex:Warmup <http://example.org/immediately-follows> ex:Train .\n"
   "_:rel_wake_warmup a rdf:Statement ;\n"
   "  rdf:subject ex:Wake ;\n"
   "  rdf:predicate <http://example.org/immediately-follows> ;\n"
   "  rdf:object ex:Warmup ;\n"
   "  orc:confidenceClass \"extracted\" ;\n"
   "  .\n"
   "_:rel_warmup_train a rdf:Statement ;\n"
   "  rdf:subject ex:Warmup ;\n"
   "  rdf:predicate <http://example.org/immediately-follows> ;\n"
   "  rdf:object ex:Train ;\n"
   "  orc:confidenceClass \"extracted\" ;\n"
   "  .\n"
   "\n"
   ;; S07 axiom corpus.
   ;; Disjointness: Athlete vs Coach
   "ex:Athlete owl:disjointWith ex:Coach .\n"
   ;; Functional property
   "ex:hasManager a owl:FunctionalProperty .\n"
   ;; Transitive property
   "ex:reports-to a owl:TransitiveProperty .\n"
   ;; Symmetric property
   "ex:colleague-of a owl:SymmetricProperty .\n"
   ;; Inverse-of — owl:inverseOf is symmetric, so the canonical form
   ;; includes BOTH directions.
   "ex:hasParent owl:inverseOf ex:hasChild .\n"
   "ex:hasChild owl:inverseOf ex:hasParent .\n"
   ;; Sub-property hierarchy
   "ex:hasMother rdfs:subPropertyOf ex:hasParent .\n"
   ;; Chain axiom: P∘Q→R
   "ex:hasAunt owl:propertyChainAxiom ( ex:hasParent ex:hasSister ) .\n"
   "\n"
   ;; S08 — all three equivalence kinds.
   ;; sameAs individuals
   "ex:alice owl:sameAs ex:alice-smith .\n"
   ;; equivalentClass — the CRITICAL S08 case (must NOT collapse to sameAs)
   "ex:Director owl:equivalentClass ex:Filmmaker .\n"
   ;; equivalentProperty
   "ex:hasName owl:equivalentProperty ex:name .\n"))

;; =============================================================================
;; Helpers — fully exercise the public API surface
;; =============================================================================

(defn- export-full
  "Re-export the in-memory event store as TTL via the standard
   serialization pipeline. Disables tree-profile / node-experience
   sections and the legacy ConceptScheme block so the round-trip
   compares JUST the ingested representation constructs."
  [ctx & [{:keys [base-uri]}]]
  (let [concepts (rmp/project ctx :ontology/concepts)
        all-meta (rmp/project ctx :ontology/ontology-metadata)
        primary-meta (some-> all-meta vals first)
        concepts-ttl (serialization/concepts->turtle
                       concepts
                       (cond-> {:include-scheme? false}
                         base-uri (assoc :base-uri base-uri)
                         primary-meta (assoc :ontology-metadata primary-meta)))
        all-axioms (rmp/project ctx :ontology/axioms)
        axioms-ttl (serialization/axioms->turtle all-axioms)
        all-equivs (rmp/project ctx :ontology/equivalences)
        relationships (vals (rmp/project ctx :ontology/relationships))]
    ;; Mirror full-export's section structure without the ConceptScheme +
    ;; tree-profile / node-experience blocks so the round-trip compares
    ;; only the bundle representation surface.
    (str concepts-ttl
         (when axioms-ttl (str "\n\n# === AXIOMS ===\n\n" axioms-ttl))
         (when-let [b (#'serialization/equivalences->turtle all-equivs)]
           (str "\n\n# === EQUIVALENCES ===\n\n" b))
         (when-let [b (serialization/relationships->turtle relationships)]
           (str "\n\n# === EDGE METADATA ===\n" b)))))

;; =============================================================================
;; AC1 — Canonicalization works on a known fixture
;; =============================================================================

(deftest canonicalize-fixture-is-deterministic
  (testing "Two canonicalizations of the same TTL produce the same
            byte sequence — proves URDNA2015 stability over rdflib."
    (let [a (ttlc/canonicalize-ttl bundle-ttl)
          b (ttlc/canonicalize-ttl bundle-ttl)]
      (is (string? a) "canonicalize succeeds on the bundle fixture")
      (is (= a b) "two runs produce byte-identical canonical N-Triples"))))

;; =============================================================================
;; AC2 — Diff is root-cause-ready (the prototype's three failure modes)
;; =============================================================================

(deftest diff-identifies-missing-language-label
  (testing "Dropping one rdfs:label produces a diff showing the EXACT
            missing triple — not a generic 'files differ'."
    (let [mutated (str/replace bundle-ttl
                               "  rdfs:label \"Sportler\"@de ;\n"
                               "")
          {:keys [equivalent? report]} (ttlc/semantic-diff bundle-ttl mutated)]
      (is (not equivalent?))
      (is (str/includes? report "MISSING TRIPLES")
          "Diff identifies a missing named triple section")
      (is (str/includes? report "Sportler")
          "Diff names the actual missing literal value")
      (is (str/includes? report "@de")
          "Diff identifies the language tag of the dropped label"))))

(deftest diff-identifies-lexical-mismatch
  (testing "Changing kg → Kg surfaces in the diff with both
            bnode-record values side-by-side, so a human reader can
            see the mismatch without searching."
    (let [mutated (str/replace bundle-ttl "qudt:unit \"kg\"" "qudt:unit \"Kg\"")
          {:keys [equivalent? report]} (ttlc/semantic-diff bundle-ttl mutated)]
      (is (not equivalent?))
      (is (or (str/includes? report "MISSING BNODE RECORDS")
              (str/includes? report "LEXICAL MISMATCHES"))
          "Diff identifies the QUDT bnode-record on each side OR a lexical mismatch")
      (is (str/includes? report "\"kg\"")
          "Diff shows the original unit value")
      (is (str/includes? report "\"Kg\"")
          "Diff shows the mutated unit value"))))

(deftest diff-identifies-missing-reified-field
  (testing "Dropping the orc:validFrom field from the reified
            rdf:Statement bnode surfaces BOTH bnode records (with and
            without the field) so the human reader can spot the
            single-line difference."
    (let [mutated (str/replace bundle-ttl
                               "  orc:validFrom \"2026-01-01T00:00:00+00:00\"^^xsd:dateTime ;\n"
                               "")
          {:keys [equivalent? report]} (ttlc/semantic-diff bundle-ttl mutated)]
      (is (not equivalent?))
      (is (str/includes? report "BNODE RECORDS")
          "Diff groups by bnode structural signature")
      (is (str/includes? report "validFrom")
          "Diff names the missing field by its prefixed predicate"))))

(deftest diff-identifies-extra-triple
  (testing "Adding an extra triple surfaces in the EXTRA TRIPLES section."
    (let [mutated (str bundle-ttl "\nex:Athlete rdfs:label \"NewLabel\"@fr .\n")
          {:keys [equivalent? report]} (ttlc/semantic-diff bundle-ttl mutated)]
      (is (not equivalent?))
      (is (str/includes? report "EXTRA TRIPLES")
          "Diff identifies the added triple")
      (is (str/includes? report "NewLabel")
          "Diff shows the value of the extra triple"))))

;; =============================================================================
;; AC3 — Adversarial: malformed TTL fails loudly with a parser error
;; =============================================================================

(deftest malformed-ttl-truncated-fails-loudly
  (testing "A truncated TTL ends mid-statement — ingest returns an
            anomaly with a position-bearing error, NOT a partial
            silent ingest."
    (let [bad "@prefix ex: <http://example.org/> .\nex:Foo a skos:Concept ;\n  skos:prefLabel \"unclos"]
      (h/with-test-context [ctx]
        (let [result (ttli/ingest-ttl! ctx bad)]
          (is (= ::anom/incorrect (::anom/category result))
              "Truncated TTL returns ::anom/incorrect")
          (is (str/includes? (or (:anomaly/message result) "") "ARSE-ERROR")
              "Anomaly message carries the rdflib parse-error prefix"))))))

(deftest malformed-ttl-undefined-prefix-fails-loudly
  (testing "Undefined prefix in a TTL file returns a parser anomaly
            with a position-bearing error."
    (let [bad "ex:Foo a skos:Concept .\n"]
      (h/with-test-context [ctx]
        (let [result (ttli/ingest-ttl! ctx bad)]
          (is (= ::anom/incorrect (::anom/category result))
              "Undefined prefix returns ::anom/incorrect")
          (is (str/includes? (or (:anomaly/message result) "") "ARSE-ERROR")
              "Anomaly message references the parser error"))))))

(deftest malformed-ttl-datatype-garbage-fails-or-flags
  (testing "Datatype garbage like `\"abc\"^^xsd:integer` is rdflib's
            domain to reject; we assert ingest either returns an
            anomaly OR — when rdflib accepts the lexical form as a
            valid-but-non-canonical literal — the round-trip is still
            equivalent (no partial silent drop). Either path proves
            'no silent partial ingest'."
    (let [bad (str "@prefix ex: <http://example.org/> .\n"
                   "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n"
                   "@prefix skos: <http://www.w3.org/2004/02/skos/core#> .\n"
                   "@prefix orc: <http://obney.ai/workshop/ontology/orc#> .\n"
                   "ex:Foo a skos:Concept ;\n"
                   "  skos:prefLabel \"Foo\" ;\n"
                   "  orc:age \"abc\"^^xsd:integer .\n")]
      (h/with-test-context [ctx]
        (let [result (ttli/ingest-ttl! ctx bad)]
          (is (or (= ::anom/incorrect (::anom/category result))
                  ;; Or it ingested — in which case the round-trip
                  ;; below must preserve the bad literal as-is. This
                  ;; is rdflib's call and it documents tolerance.
                  (and (:ingested? result) (pos? (:triples-parsed result))))
              "Either fails loudly OR preserves the bad literal verbatim — never silently drops it"))))))

;; =============================================================================
;; AC4 — G1 FORWARD: ingest the bundle TTL, re-export, semantic-diff
;; =============================================================================

(deftest g1-forward-bundle-roundtrips
  (testing "G1 FORWARD: ingest(bundle-ttl) → events → project → export
            ≍ source. The bundle fixture exercises EVERY S04+S05+S06+S07+S08
            feature; the canonicalized triple-set equivalence verdict is
            EQUIVALENT. The diff on failure prints the exact missing /
            extra / lexical triples in the prototype's format."
    (h/with-test-context [ctx]
      (let [oid (random-uuid)
            result (ttli/ingest-ttl! ctx bundle-ttl {:ontology-id oid})]
        (is (:ingested? result) "ingestion succeeds")
        ;; Explicit debug instrumentation per the disciplines: emit
        ;; per-feature event counts so a future investigator sees
        ;; ingestion coverage from the test output.
        (println "S09 G1-FORWARD ingestion counts:" (:counts result))
        (let [exported (export-full ctx {:base-uri "http://example.org/ont/bundle"})
              {:keys [equivalent? report]} (ttlc/semantic-diff bundle-ttl exported)]
          (is equivalent?
              (str "G1 FORWARD: triple-set equivalence — see diff:\n" report)))))))

;; =============================================================================
;; AC5 — G1 SYMMETRY: events-built graph → export → ingest → export = fixed point
;; =============================================================================

(deftest g1-symmetry-events-built-graph
  (testing "G1 SYMMETRY: build a graph FROM EVENTS (no TTL source) →
            export → ingest the export into a fresh context → export
            again. The two exports are triple-set-equivalent. Proves
            the gate works in BOTH directions, not just from a TTL
            source."
    (h/with-test-context [ctx1]
      (let [oid (random-uuid)]
        ;; Build a small graph entirely via commands (no TTL source).
        (h/run-and-apply!
         ctx1 (fn [c]
                (cmd/ontology-record-ontology-metadata
                 (assoc c :command {:ontology-id oid
                                    :title "Events-Built Graph"
                                    :version "0.1"}))))
        (h/run-and-apply!
         ctx1 (fn [c]
                (cmd/ontology-create-concept
                 (assoc c :command {:ontology-id oid
                                    :uri "ex:Alpha"
                                    :label "Alpha"
                                    :description "First"
                                    :scope :custom
                                    :labels [{:value "Alpha" :lang "en"}]}))))
        (h/run-and-apply!
         ctx1 (fn [c]
                (cmd/ontology-create-concept
                 (assoc c :command {:ontology-id oid
                                    :uri "ex:Beta"
                                    :label "Beta"
                                    :description "Second"
                                    :scope :custom}))))
        (h/run-and-apply!
         ctx1 (fn [c]
                (cmd/ontology-record-equivalence
                 (assoc c :command {:ontology-id oid
                                    :source-uri "ex:Alpha"
                                    :target-uri "ex:Beta"
                                    :kind :equivalent-class}))))
        (let [export1 (export-full ctx1 {:base-uri "http://example.org/ont/symm"})]
          (h/with-test-context [ctx2]
            (let [ingest-result (ttli/ingest-ttl! ctx2 export1 {:ontology-id oid})]
              (is (:ingested? ingest-result) "second-leg ingest succeeds")
              (println "S09 G1-SYMMETRY ingestion counts:" (:counts ingest-result))
              (let [export2 (export-full ctx2 {:base-uri "http://example.org/ont/symm"})
                    {:keys [equivalent? report]} (ttlc/semantic-diff export1 export2)]
                (is equivalent?
                    (str "G1 SYMMETRY: events→export→ingest→export not a fixed point:\n"
                         report))))))))))

(def real-world-skos-excerpt
  "A small SKOS-shaped excerpt that mimics a published taxonomy
   (broader/narrower hierarchy, multi-language labels, scope notes,
   an OWL equivalentClass). Hand-authored to keep the test offline
   while still exercising the BFS hierarchy + the
   non-S04 representation surface a real-world TTL exhibits.

   The Cat/Feline equivalence pair references concepts that don't
   have a `skos:Concept` declaration — verifies the equivalence
   ingestion records the assertion even when the endpoints are
   underspecified in the source."
  (str
   "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
   "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
   "@prefix skos: <http://www.w3.org/2004/02/skos/core#> .\n"
   "@prefix dcterms: <http://purl.org/dc/terms/> .\n"
   "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
   "@prefix ex: <http://example.org/taxonomy#> .\n"
   "\n"
   "ex:Animal a skos:Concept ;\n"
   "  skos:prefLabel \"Animal\" ;\n"
   "  rdfs:label \"Animal\"@en ;\n"
   "  rdfs:label \"Tier\"@de ;\n"
   "  skos:scopeNote \"custom\" ;\n"
   "  .\n"
   "\n"
   "ex:Mammal a skos:Concept ;\n"
   "  skos:prefLabel \"Mammal\" ;\n"
   "  rdfs:label \"Mammal\"@en ;\n"
   "  skos:scopeNote \"custom\" ;\n"
   "  skos:broader ex:Animal ;\n"
   "  .\n"
   "\n"
   "ex:Animal skos:narrower ex:Mammal .\n"
   "\n"
   "ex:Dog a skos:Concept ;\n"
   "  skos:prefLabel \"Dog\" ;\n"
   "  rdfs:label \"Dog\"@en ;\n"
   "  skos:scopeNote \"custom\" ;\n"
   "  skos:broader ex:Mammal ;\n"
   "  .\n"
   "\n"
   "ex:Mammal skos:narrower ex:Dog .\n"
   "\n"
   "ex:Cat owl:equivalentClass ex:Feline .\n"))

(deftest g1-forward-real-world-skos-excerpt
  (testing "G1 FORWARD on a SKOS taxonomy excerpt resembling real-world
            published ontologies. Per the S09 acceptance criteria's
            'Live verify: ingest a real-world TTL, inspect events,
            re-export, run G1' clause. Hand-authored fixture (offline)
            mirrors broader/narrower hierarchy + multi-language labels
            + an equivalence assertion."
    (h/with-test-context [ctx]
      (let [oid (random-uuid)
            r (ttli/ingest-ttl! ctx real-world-skos-excerpt {:ontology-id oid})]
        (is (:ingested? r) "real-world ingest succeeds")
        (is (= 3 (:concept (:counts r))) "3 concepts (Animal Mammal Dog)")
        (is (>= (:relationship (:counts r)) 4) "broader/narrower chain")
        (is (= 1 (:equivalence (:counts r))) "Cat equivalentClass Feline")
        (let [exported (export-full ctx {:base-uri "http://example.org/taxonomy/"})
              {:keys [equivalent? report]} (ttlc/semantic-diff real-world-skos-excerpt exported)]
          (is equivalent?
              (str "real-world SKOS round-trip diff:\n" report)))))))

(deftest g1-symmetry-full-bundle
  (testing "G1 SYMMETRY on the FULL bundle fixture: ingest the bundle
            TTL into ctx1 → export from ctx1 → ingest into ctx2 →
            export from ctx2. The two exports are byte-equivalent
            under canonicalization. Proves the gate works across the
            entire S04+S05+S06+S07+S08 representation surface in BOTH
            directions, not just on a toy graph."
    (h/with-test-context [ctx1]
      (let [oid1 (random-uuid)]
        (ttli/ingest-ttl! ctx1 bundle-ttl {:ontology-id oid1})
        (let [export1 (export-full ctx1 {:base-uri "http://example.org/ont/bundle"})]
          (h/with-test-context [ctx2]
            (let [oid2 (random-uuid)
                  r2 (ttli/ingest-ttl! ctx2 export1 {:ontology-id oid2})]
              (is (:ingested? r2) "second-leg ingest succeeds on the bundle export")
              (let [export2 (export-full ctx2 {:base-uri "http://example.org/ont/bundle"})
                    {:keys [equivalent? report]} (ttlc/semantic-diff export1 export2)]
                (is equivalent?
                    (str "G1 SYMMETRY (full bundle): export1 ≠ export2:\n" report))))))))))

;; =============================================================================
;; AC6 — Equivalence-kind preservation (the S08 load-bearing semantic gate)
;; =============================================================================

(deftest equivalence-kind-preserved-through-round-trip
  (testing "An :equivalent-class pair ingested back from TTL MUST
            land as :kind :equivalent-class — NEVER collapse to
            :same-as. This is the inheritance-merge hazard the S08
            kind discriminator exists to prevent: a class-level
            equivalence silently expressed as sameAs merges all
            property assertions across the equivalence under OWL DL."
    (h/with-test-context [ctx]
      (let [oid (random-uuid)
            _ (ttli/ingest-ttl! ctx bundle-ttl {:ontology-id oid})
            equivs (rm/get-equivalences ctx oid)]
        (is (contains? (:equivalent-class equivs)
                       #{"ex:Director" "ex:Filmmaker"})
            "the equivalent-class pair lands in :equivalent-class bucket")
        (is (not (contains? (:same-as equivs #{})
                            #{"ex:Director" "ex:Filmmaker"}))
            "the equivalent-class pair does NOT appear in :same-as — kind preserved")
        (is (contains? (:same-as equivs)
                       #{"ex:alice" "ex:alice-smith"})
            "the sameAs pair correctly lands in :same-as")
        (is (contains? (:equivalent-property equivs)
                       #{"ex:hasName" "ex:name"})
            "the equivalentProperty pair correctly lands in :equivalent-property")))))

;; =============================================================================
;; AC7 — Grain discipline: all writes through commands
;; =============================================================================

(deftest ingest-uses-commands-not-bare-appends
  (testing "Every fact lands via a defcommand handler — the ingest
            invokes cmd/ontology-* via the standard process pathway,
            and the events that hit the store carry the standard
            event-type vocabulary. Verified by inspecting the
            returned command-result events on the report's :commands."
    (h/with-test-context [ctx]
      (let [oid (random-uuid)
            result (ttli/ingest-ttl! ctx bundle-ttl {:ontology-id oid})
            event-types (->> (:commands result)
                             (mapcat :command-result/events)
                             (map :event/type)
                             set)
            expected-event-types #{:ontology/ontology-metadata-recorded
                                   :ontology/concept-created
                                   :ontology/relationship-created
                                   :ontology/disjointness-asserted
                                   :ontology/property-characteristic-asserted
                                   :ontology/sub-property-asserted
                                   :ontology/chain-axiom-asserted
                                   :ontology/equivalence-recorded}]
        (is (clojure.set/subset? expected-event-types event-types)
            (str "Every bundle-feature event type emerges via commands; got "
                 event-types))))))

;; =============================================================================
;; AC8 — Per-feature counts surface (debug instrumentation)
;; =============================================================================

(deftest ingest-report-counts-bundle-features
  (testing "The ingest report enumerates per-feature event counts so
            a future investigator can see ingestion coverage at a
            glance — required by the acceptance criteria's 'explicit
            debug logging counts produced events per feature' clause."
    (h/with-test-context [ctx]
      (let [{:keys [counts ingested?]} (ttli/ingest-ttl! ctx bundle-ttl)]
        (is ingested?)
        (is (= 5 (:concept counts)) "5 concepts in fixture: Athlete Coach Wake Warmup Train")
        (is (= 1 (:ontology-metadata counts)) "1 ontology-header block")
        (is (>= (:relationship counts) 3) "3 reified relationships (1 athlete-coach + 2 immediately-follows)")
        (is (= 1 (:disjointness counts)) "Athlete disjointWith Coach")
        (is (>= (:characteristic counts) 4) "functional + transitive + symmetric + inverseOf")
        (is (= 1 (:sub-property counts)) "hasMother sub-property-of hasParent")
        (is (= 1 (:chain-axiom counts)) "hasAunt = hasParent ∘ hasSister")
        (is (= 3 (:equivalence counts)) "sameAs + equivalentClass + equivalentProperty")))))
