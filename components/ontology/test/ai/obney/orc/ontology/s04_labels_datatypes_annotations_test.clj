(ns ai.obney.orc.ontology.s04-labels-datatypes-annotations-test
  "S04 — Labels, datatypes, annotations: additive representation
   bundle that flows end-to-end through command → event → projection →
   TTL export.

   Each deftest corresponds to a slice acceptance criterion and verifies
   behavior through the PUBLIC interface (commands, defqueries, public
   read-model helpers, serialization/full-export) — never internals.

   The fixtures are real Grain event-store writes via
   ai.obney.orc.ontology.test-helpers/with-test-context — no synthesized
   projection state.

   Adversarial spots called out per the disciplines block:
   - Multi-language: the assertion checks BOTH de + en TTL lines appear
     and ZERO hardcoded @en remains on output for a multi-lang-only
     concept (passes-while-still-hardcoded-elsewhere mode).
   - Comment vs description: assertion that BOTH rdfs:comment AND
     skos:definition appear in TTL output for a concept carrying both
     (silent-merge failure mode).
   - Legacy back-compat: a concept seeded with ONLY a single :label and
     no :labels still projects + exports — with NO language tag (NOT
     silently @en, since the convention-only @en is what S04 removes)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.ontology.test-helpers :as h]
            ;; Required for event-schema registration — without this,
            ;; the event-store's append-time Malli validation rejects
            ;; the new optional fields. Same idiom as S02.
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.queries :as qry]
            [ai.obney.orc.ontology.core.serialization :as serialization]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

;; =============================================================================
;; Seeds — small reusable per-test fixtures
;; =============================================================================

(def ontology-id #uuid "5040d000-0000-0000-0000-000000000001")

(defn- create-concept!
  "Dispatch :ontology/create-concept with the given body. Returns the
   command result."
  [ctx body]
  (h/run-and-apply! ctx
                    (fn [c]
                      (cmd/ontology-create-concept
                       (assoc c :command body)))))

;; =============================================================================
;; AC1 — Multi-language labels round-trip
;; =============================================================================

(deftest multi-language-labels-round-trip-through-projection-and-ttl
  (testing "A concept carrying :labels [{:value :lang} ...] surfaces both
            language entries on the projected record AND emits both
            rdfs:label lines in TTL with their language tags."
    (h/with-test-context [ctx]
      (create-concept! ctx
                       {:ontology-id ontology-id
                        :uri "role:Director"
                        :label "Director" ;; back-compat single label still required by command schema
                        :description "Top role responsible for orchestration"
                        :scope :custom
                        :labels [{:value "Director" :lang "en"}
                                 {:value "Regisseur" :lang "de"}]})
      ;; Projection: the multi-language labels are on the concept map.
      (let [concept (rm/get-concept-by-uri ctx "role:Director")]
        (is (some? concept) "concept projects from event-store")
        (is (= [{:value "Director" :lang "en"}
                {:value "Regisseur" :lang "de"}]
               (:labels concept))
            ":labels preserved on the projected record"))

      ;; Section-keyed projection (S02 parallel projection): same data
      ;; under [ontology-id uri] key.
      (let [section-concept (rm/get-concept-by-uri ctx "role:Director"
                                                   {:ontology-id ontology-id})]
        (is (= [{:value "Director" :lang "en"}
                {:value "Regisseur" :lang "de"}]
               (:labels section-concept))
            ":labels also surface on the section-keyed projection (S02 additive)"))

      ;; TTL: both labels appear with their language tags.
      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        (is (str/includes? ttl "\"Director\"@en")
            "TTL emits English label with @en tag")
        (is (str/includes? ttl "\"Regisseur\"@de")
            "TTL emits German label with @de tag")
        ;; Adversarial: rdfs:label predicate (not just any string match),
        ;; both lines present, distinct.
        (is (re-find #"rdfs:label\s+\"Director\"@en" ttl)
            "rdfs:label predicate carries the English value with its tag")
        (is (re-find #"rdfs:label\s+\"Regisseur\"@de" ttl)
            "rdfs:label predicate carries the German value with its tag")))))

(deftest serializer-source-has-no-residual-hardcoded-language-tag
  (testing "Adversarial grep — the serializer source MUST NOT contain a
            literal '@en' string after S04. Any future occurrence must
            be documented as language-tag-driven output, not hardcoded.

            This guards against the 'passes-while-still-hardcoded-
            elsewhere' failure mode: an individual export test could
            pass while a downstream serializer function still hardcodes
            the tag."
    (let [src (slurp "components/ontology/src/ai/obney/orc/ontology/core/serialization.clj")
          ;; Match the literal '@en' tag (closing-quote of a Turtle literal
          ;; followed by '@en'). The check uses the wire form (`\"@en`)
          ;; because that's the only place hardcoded language tags can hide.
          matches (re-seq #"\"@en" src)]
      (is (empty? matches)
          (str "Residual hardcoded @en literal in serializer source: "
               (count matches) " occurrence(s)")))))

;; =============================================================================
;; AC2 — Legacy single-label back-compat
;; =============================================================================

(deftest legacy-single-label-still-projects-and-exports
  (testing "A concept-created event with ONLY the legacy :label field
            (no :labels) still projects AND exports — but the TTL
            output has NO language tag (NOT a silently-defaulted @en
            since S04 removed the convention-only hardcoding)."
    (h/with-test-context [ctx]
      (create-concept! ctx
                       {:ontology-id ontology-id
                        :uri "role:LegacyDirector"
                        :label "Legacy Director"
                        :description "A legacy concept with no language-tagged labels."
                        :scope :custom})
      (let [concept (rm/get-concept-by-uri ctx "role:LegacyDirector")]
        (is (= "Legacy Director" (:label concept))
            "back-compat: legacy single :label preserved on projection")
        (is (nil? (:labels concept))
            "legacy event has no :labels field"))

      ;; TTL: the legacy concept exports its label with NO language tag.
      ;; Adversarial: this is the 'silent @en' failure mode under test.
      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        (is (str/includes? ttl "\"Legacy Director\"")
            "legacy label appears in TTL")
        (is (not (str/includes? ttl "\"Legacy Director\"@en"))
            "legacy label MUST NOT be silently tagged @en")
        (is (not (str/includes? ttl "\"Legacy Director\"@"))
            "legacy label MUST NOT carry ANY language tag — convention-only @en was removed")))))

;; =============================================================================
;; AC3 — Datatyped attributes
;; =============================================================================

(deftest datatyped-attributes-export-with-xsd-types
  (testing "Concept attributes with the structured {:value :datatype}
            shape emit ^^xsd:<type> on TTL; bare values export untyped."
    (h/with-test-context [ctx]
      (create-concept! ctx
                       {:ontology-id ontology-id
                        :uri "role:Year"
                        :label "Year"
                        :description "A year concept exercising datatyped attributes."
                        :scope :custom
                        :attributes {:established {:value 2026 :datatype :xsd/integer}
                                     :revenue   {:value 99.5 :datatype :xsd/decimal}
                                     :code-name "Aurora"}})

      ;; Projection preserves the structured shape.
      (let [concept (rm/get-concept-by-uri ctx "role:Year")]
        (is (= {:value 2026 :datatype :xsd/integer}
               (get-in concept [:attributes :established]))
            ":attributes :established carries the typed shape")
        (is (= {:value 99.5 :datatype :xsd/decimal}
               (get-in concept [:attributes :revenue]))
            ":attributes :revenue carries the typed shape")
        (is (= "Aurora"
               (get-in concept [:attributes :code-name]))
            ":attributes :code-name remains a bare string (back-compat)"))

      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        (is (str/includes? ttl "\"2026\"^^xsd:integer")
            "TTL emits ^^xsd:integer for the :xsd/integer-typed attribute")
        (is (str/includes? ttl "\"99.5\"^^xsd:decimal")
            "TTL emits ^^xsd:decimal for the :xsd/decimal-typed attribute")
        (is (str/includes? ttl "\"Aurora\"")
            "TTL emits the bare-string attribute untyped (no ^^xsd:...)")
        ;; Adversarial: the untyped value does NOT silently acquire ^^xsd:string.
        (is (not (str/includes? ttl "\"Aurora\"^^xsd"))
            "bare-string attribute MUST NOT acquire a phantom xsd type")))))

;; =============================================================================
;; AC4 — :comment vs :description distinct predicates
;; =============================================================================

(deftest comment-and-description-export-as-distinct-predicates
  (testing "A concept with BOTH :comment and :description exports BOTH
            in TTL — :comment as rdfs:comment, :description as
            skos:definition. Neither overwrites the other. Adversarial
            target: the silent-merge failure mode where the body
            collapses one into the other."
    (h/with-test-context [ctx]
      (create-concept! ctx
                       {:ontology-id ontology-id
                        :uri "role:DualAnnotated"
                        :label "Dual Annotated"
                        :description "Formal definition: the role of orchestrator."
                        :scope :custom
                        :comment "Editorial note: prefer to assign this to senior staff."})

      (let [concept (rm/get-concept-by-uri ctx "role:DualAnnotated")]
        (is (= "Formal definition: the role of orchestrator."
               (:description concept))
            ":description preserved on projection")
        (is (= "Editorial note: prefer to assign this to senior staff."
               (:comment concept))
            ":comment preserved on projection — separate from :description"))

      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        ;; BOTH predicates appear — distinct.
        (is (re-find #"skos:definition\s+\"Formal definition" ttl)
            "skos:definition carries the description")
        (is (re-find #"rdfs:comment\s+\"Editorial note" ttl)
            "rdfs:comment carries the comment")
        ;; Adversarial: each predicate carries its OWN text — the
        ;; comment text is NEVER attached to skos:definition (silent
        ;; merge mode).
        (is (not (re-find #"skos:definition\s+\"Editorial note" ttl))
            "skos:definition MUST NOT carry the editorial comment")
        (is (not (re-find #"rdfs:comment\s+\"Formal definition" ttl))
            "rdfs:comment MUST NOT carry the formal definition")))))

;; =============================================================================
;; AC5 — :model-guidance surfaces in retrieval payloads
;; =============================================================================

(deftest model-guidance-surfaces-in-public-retrieval-payloads
  (testing ":model-guidance set on a concept appears in the projected
            record and through the public get-concept query result —
            so downstream LLM consumers see the usage hint."
    (h/with-test-context [ctx]
      (create-concept! ctx
                       {:ontology-id ontology-id
                        :uri "role:Curator"
                        :label "Curator"
                        :description "A curator role."
                        :scope :custom
                        :model-guidance "Prefer this role when selecting human-in-the-loop reviewers; do not assign to autonomous agents."})

      ;; Public surface: get-concept-by-uri returns :model-guidance.
      (let [concept (rm/get-concept-by-uri ctx "role:Curator")]
        (is (= "Prefer this role when selecting human-in-the-loop reviewers; do not assign to autonomous agents."
               (:model-guidance concept))
            ":model-guidance surfaces on the projected record"))

      ;; Public surface: defquery get-concept returns the same.
      (let [result (qry/ontology-get-concept
                     (assoc ctx :query {:uri "role:Curator"}))]
        (is (= "Prefer this role when selecting human-in-the-loop reviewers; do not assign to autonomous agents."
               (:model-guidance (:query/result result)))
            ":model-guidance flows through the public defquery surface"))

      ;; Adversarial: a concept WITHOUT :model-guidance does not get a
      ;; phantom default value.
      (create-concept! ctx
                       {:ontology-id ontology-id
                        :uri "role:NoGuide"
                        :label "Unguided"
                        :description "A concept with no model guidance."
                        :scope :custom})
      (let [concept (rm/get-concept-by-uri ctx "role:NoGuide")]
        (is (nil? (:model-guidance concept))
            "concept without :model-guidance carries no phantom default")))))

;; =============================================================================
;; AC6 — Ontology-level metadata on TTL header
;; =============================================================================

(deftest ontology-level-metadata-exports-on-header
  (testing "When :ontology/record-ontology-metadata fires, the exported
            TTL header carries owl:Ontology + dcterms:title/version/
            license/creator triples — only the FIELDS SUPPLIED appear
            (no defaulted-empty-string artefacts)."
    (h/with-test-context [ctx]
      ;; Record the ontology metadata via the new command.
      (h/run-and-apply! ctx
                        (fn [c]
                          (cmd/ontology-record-ontology-metadata
                           (assoc c :command
                                  {:ontology-id ontology-id
                                   :title "S04 Test Ontology"
                                   :version "1.0.0"
                                   :license "https://creativecommons.org/licenses/by/4.0/"
                                   :creator "Daryl Roberts"}))))
      ;; Seed at least one concept so the export has body.
      (create-concept! ctx
                       {:ontology-id ontology-id
                        :uri "role:Seed"
                        :label "Seed"
                        :description "Seed concept for the metadata test."
                        :scope :custom})

      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        (is (str/includes? ttl "owl:Ontology")
            "TTL header declares owl:Ontology")
        (is (str/includes? ttl "\"S04 Test Ontology\"")
            "title appears on the header")
        (is (str/includes? ttl "\"1.0.0\"")
            "version appears on the header")
        ;; License is emitted as an IRI (per dcterms:license convention),
        ;; not as a plain string literal — `<URL>` not `"URL"`.
        (is (str/includes? ttl "<https://creativecommons.org/licenses/by/4.0/>")
            "license appears on the header as an IRI")
        (is (str/includes? ttl "\"Daryl Roberts\"")
            "creator appears on the header")))))

(deftest ontology-level-metadata-emits-only-supplied-fields
  (testing "Adversarial: when only :title is recorded, the exported
            header carries title but NOT defaulted-empty-string
            version/license/creator triples."
    (h/with-test-context [ctx]
      (h/run-and-apply! ctx
                        (fn [c]
                          (cmd/ontology-record-ontology-metadata
                           (assoc c :command
                                  {:ontology-id ontology-id
                                   :title "Title-Only Ontology"}))))
      (create-concept! ctx
                       {:ontology-id ontology-id
                        :uri "role:Seed2"
                        :label "Seed2"
                        :description "Seed concept."
                        :scope :custom})

      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        (is (str/includes? ttl "\"Title-Only Ontology\"")
            "title appears")
        ;; Adversarial: no empty-string triples.
        (is (not (re-find #"dcterms:creator\s+\"\"" ttl))
            "no defaulted-empty creator triple")
        (is (not (re-find #"dcterms:license\s+\"\"" ttl))
            "no defaulted-empty license triple")
        (is (not (re-find #"owl:versionInfo\s+\"\"" ttl))
            "no defaulted-empty version triple")))))

;; =============================================================================
;; AC7 — Optionality / back-compat — every existing event fixture validates
;; =============================================================================
;; This is a structural regression — driven by the brick test sweep run
;; from the harness, NOT by an in-line test (since the optionality is on
;; the Malli schemas themselves, ANY existing test that creates concepts
;; without the new fields is the regression evidence).

(deftest extra-bundle-fields-and-classic-fields-coexist-on-one-concept
  (testing "A single concept carrying ALL the new optional fields PLUS
            the classic ones round-trips faithfully — proves the bundle
            composes."
    (h/with-test-context [ctx]
      (create-concept! ctx
                       {:ontology-id ontology-id
                        :uri "role:Composite"
                        :label "Composite"
                        :description "A definition."
                        :scope :custom
                        :labels [{:value "Composite" :lang "en"}
                                 {:value "Komposit" :lang "de"}]
                        :comment "An editorial remark."
                        :see-also ["role:Director" "role:Curator"]
                        :is-defined-by "https://obney.ai/specs/roles"
                        :model-guidance "Use sparingly — composite roles are rare."
                        :attributes {:established {:value 2026 :datatype :xsd/integer}}})

      (let [concept (rm/get-concept-by-uri ctx "role:Composite")]
        (is (= "Composite" (:label concept)))
        (is (= "A definition." (:description concept)))
        (is (= "An editorial remark." (:comment concept)))
        (is (= ["role:Director" "role:Curator"] (:see-also concept)))
        (is (= "https://obney.ai/specs/roles" (:is-defined-by concept)))
        (is (= "Use sparingly — composite roles are rare." (:model-guidance concept)))
        (is (= 2 (count (:labels concept))))
        (is (= {:value 2026 :datatype :xsd/integer}
               (get-in concept [:attributes :established]))))

      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        (is (str/includes? ttl "\"Composite\"@en"))
        (is (str/includes? ttl "\"Komposit\"@de"))
        (is (str/includes? ttl "rdfs:comment"))
        (is (str/includes? ttl "rdfs:seeAlso"))
        (is (str/includes? ttl "rdfs:isDefinedBy"))
        (is (str/includes? ttl "\"2026\"^^xsd:integer"))))))
