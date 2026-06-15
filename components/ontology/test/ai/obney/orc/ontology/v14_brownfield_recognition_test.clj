(ns ai.obney.orc.ontology.v14-brownfield-recognition-test
  "V14 — TTL ingest brownfield concept-type recognition + no-false-green.

   Surfaced by the V02 Mode-A early read against the real 45 MB production
   BRYC TTL: `ingest-ttl!` classified a subject as a concept ONLY when its
   rdf:type set contained `skos:Concept`. The production graph has ZERO
   `skos:Concept` subjects — its 2,480 individuals are typed with DOMAIN
   classes (`edu:EducationalProgram`, `cip:CIPCode`, `onet:Occupation`, …).
   Result: 0 concepts → 0 relationships, yet the report returned
   `:ingested? true :anomaly nil` — a SILENT zero-ingest / false green.

   Two root-cause fixes verified here:

   1. Broaden recognition — a subject is a concept when EITHER it is typed
      `skos:Concept` (preserved) OR it is typed with a non-meta domain class
      (anything that is not one of the structural OWL-meta types:
      owl:Ontology / owl:Class / owl:DatatypeProperty / owl:ObjectProperty /
      owl:AnnotationProperty / rdf:Property / rdfs:Class). A caller-supplied
      `:concept-types` set in opts takes precedence when present.

   2. No false green — when N typed non-meta subjects exist but 0 are
      recognized as concepts, the report surfaces it (`:recognized 0
      :typed-subjects N` + an `:anomaly`) rather than `:ingested? true
      :anomaly nil`.

   All recognition is STRUCTURAL (type-set membership), never label
   string-matching. All writes go through commands; all assertions go
   through public interfaces (the `ingest-ttl!` report + the read-model
   projection helpers)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.test-helpers :as h]
            ;; event-schema registration
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.ttl-ingest :as ttli]))

;; =============================================================================
;; Brownfield fixture — domain-class-typed individuals, ZERO skos:Concept.
;; =============================================================================
;;
;; Mirrors the production BRYC graph's shape in miniature: programs typed
;; edu:EducationalProgram, CIP codes typed cip:CIPCode, occupations typed
;; onet:Occupation, connected by edu:hasCIPCodeEntity (program→CIP) and
;; cip:leadsToOccupation (CIP→occupation). The namespaces are the same
;; example.org/* IRIs the production TTL uses — NOT in the ingest's
;; known-prefixes table, so they round-trip as <full-iri> type strings.
;; The recognizer must classify by "is-this-a-meta-type", not by prefix.

(def brownfield-ttl
  (str
   "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
   "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
   "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
   "@prefix edu: <http://example.org/education#> .\n"
   "@prefix cip: <http://example.org/cip#> .\n"
   "@prefix onet: <http://example.org/onet#> .\n"
   "\n"
   ;; A program individual typed with a DOMAIN class (not skos:Concept).
   "edu:Program_SW a edu:EducationalProgram ;\n"
   "  rdfs:label \"Social Work Bachelor's\" ;\n"
   "  edu:hasCIPCodeEntity cip:cip_44_0701 ;\n"
   "  .\n"
   "\n"
   "edu:Program_Psych a edu:EducationalProgram ;\n"
   "  rdfs:label \"Psychology Bachelor's\" ;\n"
   "  edu:hasCIPCodeEntity cip:cip_42_0101 ;\n"
   "  .\n"
   "\n"
   ;; CIP codes typed cip:CIPCode, leading to occupations.
   "cip:cip_44_0701 a cip:CIPCode ;\n"
   "  rdfs:label \"Social Work\" ;\n"
   "  cip:leadsToOccupation onet:soc_21_1029 ;\n"
   "  .\n"
   "\n"
   "cip:cip_42_0101 a cip:CIPCode ;\n"
   "  rdfs:label \"Psychology\" ;\n"
   "  cip:leadsToOccupation onet:soc_19_3033 ;\n"
   "  .\n"
   "\n"
   ;; Occupations typed onet:Occupation (leaf concepts — no out-edges).
   "onet:soc_21_1029 a onet:Occupation ; rdfs:label \"Social Workers\" .\n"
   "onet:soc_19_3033 a onet:Occupation ; rdfs:label \"Clinical Psychologists\" .\n"))

;; =============================================================================
;; AC1 — brownfield domain-class TTL ingests non-zero concepts + relationships
;; =============================================================================

(deftest brownfield-domain-classes-ingest-as-concepts
  (testing "A TTL whose individuals are typed with DOMAIN classes (no
            skos:Concept anywhere) ingests as concepts, and the edges on
            those subjects ingest as relationships. This is the headline
            V14 fix — the production BRYC shape."
    (h/with-test-context [ctx]
      (let [oid (random-uuid)
            r (ttli/ingest-ttl! ctx brownfield-ttl {:ontology-id oid})]
        (is (:ingested? r) "ingestion reports success")
        ;; 6 domain individuals: 2 programs + 2 CIPs + 2 occupations.
        (is (= 6 (:concept (:counts r)))
            (str "6 domain-class individuals recognized as concepts; got "
                 (:counts r)))
        ;; 4 object-property edges: 2 program→CIP + 2 CIP→occupation.
        (is (= 4 (:relationship (:counts r)))
            (str "4 object-property edges become relationships; got "
                 (:counts r)))
        ;; Projection read-back — the concepts/relationships actually
        ;; landed in the event-sourced graph, not just in the report.
        (let [concepts (filter #(= oid (:ontology-id %)) (rm/get-concepts ctx {}))
              rels (filter #(= oid (:ontology-id %)) (rm/get-relationships ctx))]
          (is (= 6 (count concepts))
              "6 concepts projected into the read model")
          (is (= 4 (count rels))
              "4 relationships projected into the read model"))))))

;; =============================================================================
;; AC2 — caller-supplied :concept-types takes precedence
;; =============================================================================

(deftest caller-supplied-concept-types-is-honored
  (testing "When the caller supplies an explicit :concept-types set, ONLY
            subjects typed with one of those classes are treated as
            concepts — even though the broadened default would recognize
            more. Here we restrict recognition to edu:EducationalProgram,
            so only the 2 programs land as concepts."
    (h/with-test-context [ctx]
      (let [oid (random-uuid)
            r (ttli/ingest-ttl!
               ctx brownfield-ttl
               {:ontology-id oid
                :concept-types #{"edu:EducationalProgram"}})]
        (is (:ingested? r) "ingestion succeeds")
        (is (= 2 (:concept (:counts r)))
            (str "only the 2 edu:EducationalProgram subjects are concepts; got "
                 (:counts r)))
        ;; Only edges whose SOURCE is a recognized concept emit. The
        ;; program→CIP edges emit (source is a program); the CIP→occupation
        ;; edges do NOT (their source CIPs are not recognized concepts).
        (is (= 2 (:relationship (:counts r)))
            (str "only the 2 program→CIP edges emit; got " (:counts r)))))))

;; =============================================================================
;; AC3 — no false green: 0-of-N recognition surfaces a warning/anomaly
;; =============================================================================

(deftest zero-recognized-of-n-typed-surfaces-anomaly
  (testing "When the graph has N typed non-meta subjects but the recognizer
            matches 0 of them (e.g. caller supplies a :concept-types set
            that matches nothing), the report MUST surface that — a
            :recognized 0 / :typed-subjects N pair and an :anomaly —
            NOT :ingested? true :anomaly nil. A consumer must detect
            'recognized nothing' without inspecting counts."
    (h/with-test-context [ctx]
      (let [oid (random-uuid)
            r (ttli/ingest-ttl!
               ctx brownfield-ttl
               {:ontology-id oid
                ;; A concept-type set that matches none of the subjects.
                :concept-types #{"edu:NonexistentClass"}})]
        (is (= 0 (:concept (:counts r))) "0 concepts recognized")
        (is (some? (:anomaly r))
            "report surfaces an :anomaly so a consumer can't get a false green")
        (is (= 0 (:recognized r)) "report exposes :recognized 0")
        (is (pos? (:typed-subjects r))
            "report exposes :typed-subjects N (the non-meta typed subjects)")))))

;; =============================================================================
;; AC4 — OWL-meta subjects are NOT misclassified as concepts
;; =============================================================================

(def meta-mixed-ttl
  "A TTL mixing OWL-meta declarations (an ontology header, a class
   declaration, an object-property and a datatype-property declaration)
   with ONE real domain-class individual. Only the domain individual is
   a concept; the meta subjects must NOT be recognized."
  (str
   "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
   "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
   "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
   "@prefix edu: <http://example.org/education#> .\n"
   "\n"
   "<http://example.org/ont/meta> a owl:Ontology .\n"
   "edu:EducationalProgram a owl:Class .\n"
   "edu:hasCIPCodeEntity a owl:ObjectProperty .\n"
   "edu:earningsY1Estimated a owl:DatatypeProperty .\n"
   "\n"
   "edu:Program_One a edu:EducationalProgram ;\n"
   "  rdfs:label \"Some Program\" ;\n"
   "  .\n"))

(deftest owl-meta-subjects-not-concepts
  (testing "owl:Ontology / owl:Class / owl:ObjectProperty /
            owl:DatatypeProperty declarations are STRUCTURAL meta, never
            concepts. Only the single edu:EducationalProgram individual is
            recognized — the 4 meta subjects are excluded."
    (h/with-test-context [ctx]
      (let [oid (random-uuid)
            r (ttli/ingest-ttl! ctx meta-mixed-ttl {:ontology-id oid})]
        (is (:ingested? r) "ingestion succeeds")
        (is (= 1 (:concept (:counts r)))
            (str "exactly 1 concept (the domain individual); meta excluded; got "
                 (:counts r)))
        (is (= 1 (:ontology-metadata (:counts r)))
            "the owl:Ontology header still lands as ontology-metadata")
        ;; No false green here — 1 of N recognized, so no anomaly.
        (is (nil? (:anomaly r))
            "1 recognized of N typed → no anomaly (not a zero-ingest)")
        (let [concepts (filter #(= oid (:ontology-id %)) (rm/get-concepts ctx {}))]
          (is (= 1 (count concepts)) "1 concept projected; meta not projected")
          ;; The edu: namespace is NOT in the ingest's known-prefixes
          ;; table, so the parser faithfully preserves the subject as its
          ;; raw <full-iri> URI. The KEY assertion is that it's the
          ;; domain individual (Program_One), never a meta subject.
          (is (= #{"<http://example.org/education#Program_One>"}
                 (set (map :uri concepts)))
              "the projected concept is the domain individual, not a meta subject"))))))
