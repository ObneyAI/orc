(ns ai.obney.orc.ontology.s06-edge-metadata-test
  "S06 — Edge metadata: schema'd + serialized.

   Relationship events gain named optional fields for the three edge-level
   metadata classes (confidence-class / evidence / temporal-validity), the
   `:properties` open bag stays AND is now ALSO serialized, and the event
   gains an `:ontology-id` field — required when present on new writes,
   absent on legacy events (which take the back-compat fallback path).

   Verified end-to-end through PUBLIC interfaces only:

   - Commands (`ontology-create-relationship`)
   - Read-model helpers (`get-relationship`, `get-relationships`,
     `get-edges-by-confidence-class`, `get-concept-by-uri`)
   - Serialization (`full-export`, `concepts->turtle` with edge-metadata
     reified statements).

   Adversarial spots called out per the disciplines block:
   - Metadata-rich export: EVERY field is enumerated by an explicit
     assertion against the TTL string (silent-drop on confidence-class /
     evidence / valid-from / valid-to / superseded-by / `:properties` is
     the thing under test).
   - Ambiguous-class queryable: a 3-edge fixture with extracted/inferred/
     ambiguous edges must return ONLY the ambiguous one when filtered by
     confidence-class — assertion enumerates the returned set is a
     singleton of the ambiguous URI.
   - Section-keyed projection back-compat: a legacy relationship event
     emitted WITHOUT `:ontology-id` must STILL project into both the
     URI-keyed and section-keyed projections. Asserted by writing a raw
     event bypassing the command (the only way to construct a no-id
     event today) and confirming edges land on both projections.

   Live verification runs against a real Grain event store via
   `with-test-context`."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.ontology.test-helpers :as h]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.serialization :as serialization]
            [ai.obney.grain.event-store-v3.interface :as es :refer [->event]]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

(def ontology-id #uuid "5060d000-0000-0000-0000-000000000001")
(def other-ontology-id #uuid "5060d000-0000-0000-0000-000000000002")

;; =============================================================================
;; Seeds
;; =============================================================================

(defn- create-concept! [ctx body]
  (h/run-and-apply! ctx
                    (fn [c]
                      (cmd/ontology-create-concept
                       (assoc c :command body)))))

(defn- create-relationship! [ctx body]
  (h/run-and-apply! ctx
                    (fn [c]
                      (cmd/ontology-create-relationship
                       (assoc c :command body)))))

(defn- seed-pair!
  "Seed two concepts (A, B) under the given ontology-id."
  [ctx oid a-uri b-uri]
  (create-concept! ctx {:ontology-id oid :uri a-uri :label "A"
                        :description "Concept A" :scope :custom})
  (create-concept! ctx {:ontology-id oid :uri b-uri :label "B"
                        :description "Concept B" :scope :custom}))

;; =============================================================================
;; AC1 — Schema accepts named metadata fields (event-store append survives)
;; =============================================================================

(deftest schema-accepts-named-metadata-fields-on-relationship-created
  (testing "A relationship written with confidence-class / evidence /
            valid-from / valid-to / superseded-by / properties / ontology-id
            survives Malli validation at command-emit AND at event-store
            append. (Without the schema additions the event-store rejects.)"
    (h/with-test-context [ctx]
      (seed-pair! ctx ontology-id "ex:Alpha" "ex:Beta")
      (let [result (create-relationship!
                    ctx
                    {:ontology-id ontology-id
                     :source-uri "ex:Alpha"
                     :target-uri "ex:Beta"
                     :predicate "skos:related"
                     :confidence-class :inferred
                     :evidence [{:source "doc:p3"
                                 :quote "Alpha relates to Beta in para 3."}]
                     :valid-from "2024-01-01T00:00:00Z"
                     :valid-to   "2026-12-31T23:59:59Z"
                     :properties {:extracted-by "rule-7"}})]
        (is (= 1 (count (:command-result/events result)))
            "exactly one event emitted")
        ;; The Grain v2 event-store-v3 `->event` flattens body fields onto
        ;; the event map directly — there is no `:event/body` sub-key.
        (let [ev (first (:command-result/events result))]
          (is (= :ontology/relationship-created (:event/type ev)))
          (is (= ontology-id (:ontology-id ev)))
          (is (= :inferred (:confidence-class ev)))
          (is (= [{:source "doc:p3" :quote "Alpha relates to Beta in para 3."}]
                 (:evidence ev)))
          (is (= "2024-01-01T00:00:00Z" (:valid-from ev)))
          (is (= "2026-12-31T23:59:59Z" (:valid-to ev)))
          (is (= {:extracted-by "rule-7"} (:properties ev))))))))

;; =============================================================================
;; AC2 — Projection exposes metadata on edges (URI-keyed AND section-keyed)
;; =============================================================================

(deftest metadata-projected-on-relationship-and-on-source-concept
  (testing "After a metadata-bearing relationship is created, the public
            read-model surface exposes the metadata via both
            (a) `get-relationship` by relationship-id, AND
            (b) the source/target concept records' typed-edges-meta map,
            queryable in both URI-keyed and section-keyed projections."
    (h/with-test-context [ctx]
      (seed-pair! ctx ontology-id "ex:Alpha" "ex:Beta")
      (let [rel-id (-> (create-relationship!
                        ctx
                        {:ontology-id ontology-id
                         :source-uri "ex:Alpha"
                         :target-uri "ex:Beta"
                         :predicate "owl:causes"
                         :confidence-class :extracted
                         :evidence [{:source "doc:p1" :quote "Alpha causes Beta."}]
                         :properties {:rule-id "R7"}})
                       :command-result/events
                       first :relationship-id)]
        (let [rel (rm/get-relationship ctx rel-id)]
          (is (some? rel) "get-relationship returns the projected edge")
          (is (= "ex:Alpha" (:source-uri rel)))
          (is (= "ex:Beta" (:target-uri rel)))
          (is (= "owl:causes" (:predicate rel)))
          (is (= ontology-id (:ontology-id rel)))
          (is (= :extracted (:confidence-class rel)))
          (is (= [{:source "doc:p1" :quote "Alpha causes Beta."}]
                 (:evidence rel)))
          (is (= {:rule-id "R7"} (:properties rel))))

        (testing "URI-keyed concept carries typed-edges-meta keyed by predicate"
          (let [c (rm/get-concept-by-uri ctx "ex:Alpha")]
            (is (some? c))
            (let [meta-set (get-in c [:typed-edges-meta "owl:causes" "ex:Beta"])]
              (is (some? meta-set)
                  "typed-edges-meta indexed by predicate then target URI")
              (is (= 1 (count meta-set))
                  "exactly one metadata entry for this edge")
              (let [m (first meta-set)]
                (is (= :extracted (:confidence-class m)))
                (is (= [{:source "doc:p1" :quote "Alpha causes Beta."}]
                       (:evidence m)))))))

        (testing "Section-keyed concept ALSO carries typed-edges-meta"
          (let [c (rm/get-concept-by-uri ctx "ex:Alpha"
                                         {:ontology-id ontology-id})]
            (is (some? c))
            (let [meta-set (get-in c [:typed-edges-meta "owl:causes" "ex:Beta"])]
              (is (some? meta-set))
              (is (= :extracted (-> meta-set first :confidence-class))))))))))

;; =============================================================================
;; AC3 — TTL emits ALL metadata fields (adversarial: enumerate every one)
;; =============================================================================

(deftest ttl-export-emits-every-edge-metadata-field-no-silent-drops
  (testing "A metadata-rich relationship — confidence-class + evidence +
            valid-from + valid-to + superseded-by + properties — when
            exported as TTL must include EVERY field on the reified
            statement. Each field is enumerated by an explicit assertion;
            this is the silent-drop failure mode under test."
    (h/with-test-context [ctx]
      (seed-pair! ctx ontology-id "ex:Gamma" "ex:Delta")
      (let [super-id #uuid "5060d000-0000-0000-0000-99999999AAAA"]
        (create-relationship!
         ctx
         {:ontology-id ontology-id
          :source-uri "ex:Gamma"
          :target-uri "ex:Delta"
          :predicate "ex:supersedes"
          :confidence-class :ambiguous
          :evidence [{:source "doc:p9"
                      :quote "Gamma replaces Delta in newer drafts."}]
          :valid-from "2024-01-01T00:00:00Z"
          :valid-to   "2026-12-31T23:59:59Z"
          :superseded-by super-id
          :properties {:rule-id "R-supersession"
                       :extractor "v3"}}))
      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false
                                                :include-axioms? false})]
        (testing "Plain triple emitted for BFS-style traversal"
          ;; The relationship's plain s/p/o appears (via the source
          ;; concept's typed-edges OR a dedicated triple — either way, the
          ;; predicate token is present unambiguously somewhere in TTL).
          (is (str/includes? ttl "ex:supersedes")
              "predicate token present in TTL"))
        (testing "Reified rdf:Statement block emitted (NOT RDF-star)"
          (is (str/includes? ttl "a rdf:Statement")
              "reified-on-demand rdf:Statement keyword present")
          (is (str/includes? ttl "rdf:subject")
              "rdf:subject predicate emitted on reified statement")
          (is (str/includes? ttl "rdf:predicate")
              "rdf:predicate predicate emitted on reified statement")
          (is (str/includes? ttl "rdf:object")
              "rdf:object predicate emitted on reified statement"))
        (testing "Confidence-class field emitted"
          (is (str/includes? ttl "orc:confidenceClass \"ambiguous\"")
              "confidence-class :ambiguous → orc:confidenceClass \"ambiguous\""))
        (testing "Evidence field emitted with source URI + quote"
          (is (str/includes? ttl "orc:evidence")
              "orc:evidence predicate emitted")
          (is (str/includes? ttl "Gamma replaces Delta in newer drafts.")
              "evidence quote present verbatim")
          (is (str/includes? ttl "doc:p9")
              "evidence source URI present"))
        (testing "Temporal validity fields emitted as xsd:dateTime"
          (is (str/includes? ttl "orc:validFrom")
              "orc:validFrom predicate emitted")
          (is (str/includes? ttl "\"2024-01-01T00:00:00Z\"^^xsd:dateTime")
              "valid-from value with xsd:dateTime literal type")
          (is (str/includes? ttl "orc:validTo")
              "orc:validTo predicate emitted")
          (is (str/includes? ttl "\"2026-12-31T23:59:59Z\"^^xsd:dateTime")
              "valid-to value with xsd:dateTime literal type"))
        (testing "Superseded-by field emitted as URI reference"
          (is (str/includes? ttl "orc:supersededBy")
              "orc:supersededBy predicate emitted")
          ;; Java's UUID.toString lowercases hex. Match case-insensitively
          ;; (the test asserts the UUID is PRESENT, not its case).
          (is (str/includes? (str/lower-case ttl)
                             "5060d000-0000-0000-0000-99999999aaaa")
              "superseded-by UUID present in TTL"))
        (testing "`:properties` open-bag content emitted (the gap S06 closes)"
          ;; Each key in :properties surfaces as orc:<key> "<value>"
          (is (str/includes? ttl "orc:rule-id \"R-supersession\"")
              "properties[:rule-id] surfaced as orc:rule-id with value")
          (is (str/includes? ttl "orc:extractor \"v3\"")
              "properties[:extractor] surfaced as orc:extractor with value"))))))

;; =============================================================================
;; AC4 — Back-compat: bare relationship (no metadata) exports plain triple only
;; =============================================================================

(deftest legacy-bare-relationship-emits-plain-triple-no-reified-noise
  (testing "A relationship with NO metadata fields (legacy back-compat) must
            export as the plain triple ONLY — no rdf:Statement block, no
            orc:confidenceClass, no orc:evidence. The reified-on-demand
            choice is 'reify ONLY when annotated'."
    (h/with-test-context [ctx]
      (seed-pair! ctx ontology-id "ex:Plain" "ex:Bare")
      (create-relationship!
       ctx
       {:ontology-id ontology-id
        :source-uri "ex:Plain"
        :target-uri "ex:Bare"
        :predicate "skos:related"})
      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false
                                                :include-axioms? false})]
        ;; The plain triple is present (via skos:related on the concept).
        (is (str/includes? ttl "skos:related")
            "predicate token present somewhere in TTL")
        ;; No reified block — no metadata to reify.
        (is (not (str/includes? ttl "rdf:Statement"))
            "NO rdf:Statement block when edge has no metadata (reify-on-demand)")
        (is (not (str/includes? ttl "orc:confidenceClass"))
            "NO confidenceClass triple when edge has no metadata")
        (is (not (str/includes? ttl "orc:validFrom"))
            "NO validFrom triple when edge has no metadata")))))

;; =============================================================================
;; AC5 — Ambiguous-edge queryable (C5's future review queue's read path)
;; =============================================================================

(deftest ambiguous-edges-queryable-as-set-via-public-helper
  (testing "Given a fixture of 3 edges — one extracted, one inferred, one
            ambiguous — `get-edges-by-confidence-class ctx :ambiguous`
            returns ONLY the ambiguous edge. The HITL review queue's read
            path. Adversarial: the test verifies that the EXTRACTED and
            INFERRED edges are EXCLUDED (a stub returning all edges would
            pass a count==N assertion but fail the exclusion assertion)."
    (h/with-test-context [ctx]
      (seed-pair! ctx ontology-id "ex:E1" "ex:E2")
      (seed-pair! ctx ontology-id "ex:I1" "ex:I2")
      (seed-pair! ctx ontology-id "ex:A1" "ex:A2")
      (create-relationship! ctx {:ontology-id ontology-id
                                 :source-uri "ex:E1" :target-uri "ex:E2"
                                 :predicate "skos:related"
                                 :confidence-class :extracted})
      (create-relationship! ctx {:ontology-id ontology-id
                                 :source-uri "ex:I1" :target-uri "ex:I2"
                                 :predicate "skos:related"
                                 :confidence-class :inferred})
      (create-relationship! ctx {:ontology-id ontology-id
                                 :source-uri "ex:A1" :target-uri "ex:A2"
                                 :predicate "skos:related"
                                 :confidence-class :ambiguous})
      (let [ambiguous (rm/get-edges-by-confidence-class ctx :ambiguous)
            extracted (rm/get-edges-by-confidence-class ctx :extracted)]
        (is (= 1 (count ambiguous))
            "exactly one ambiguous edge")
        (is (= "ex:A1" (-> ambiguous first :source-uri))
            "the ambiguous edge is the A-pair")
        (is (= "ex:A2" (-> ambiguous first :target-uri)))
        (testing "extracted/inferred edges are EXCLUDED from the ambiguous set"
          (is (not-any? #(= :extracted (:confidence-class %)) ambiguous)
              "no extracted edges in ambiguous result")
          (is (not-any? #(= :inferred (:confidence-class %)) ambiguous)
              "no inferred edges in ambiguous result"))
        (testing "extracted filter is itself correct (no leak in the OTHER direction)"
          (is (= 1 (count extracted)) "exactly one extracted edge")
          (is (= "ex:E1" (-> extracted first :source-uri))))))))

;; =============================================================================
;; AC6 — Back-compat: legacy relationship event (no :ontology-id) still projects
;; =============================================================================

(deftest legacy-relationship-event-no-ontology-id-still-projects-via-fallback
  (testing "A relationship-created event written WITHOUT an `:ontology-id`
            field (the legacy shape from before S06) STILL projects:
            (a) the URI-keyed projection assigns edges as before,
            (b) the section-keyed projection FALLS BACK to the
                find-where-endpoints-live scan and assigns edges in the
                shared section.

            We construct the legacy event directly via es/append (the only
            way to produce a no-id event now that the command always adds
            one) and confirm both projections see it."
    (h/with-test-context [ctx]
      (seed-pair! ctx ontology-id "ex:L1" "ex:L2")
      ;; Bypass the command (which would inject :ontology-id) — append a
      ;; legacy-shaped event directly. The schema permits :ontology-id
      ;; absent because it's marked optional, exactly to preserve this
      ;; path.
      (let [rel-id (random-uuid)
            ;; Build the event exactly as the (pre-S06) command would have
            ;; emitted it — body fields flat on the event map (no
            ;; `:event/body` sub-key in Grain v2), with no `:ontology-id`
            ;; field on the body. We re-use the production `->event`
            ;; constructor so the legacy-shaped event gets a valid
            ;; UUID-v7 `:event/id` and a current `:event/timestamp`.
            legacy-event (->event
                          {:type :ontology/relationship-created
                           :tags #{[:relationship rel-id]}
                           :body {:relationship-id rel-id
                                  :source-uri "ex:L1"
                                  :target-uri "ex:L2"
                                  :predicate "skos:related"
                                  :created-at "2024-01-01T00:00:00Z"}})]
        (es/append (:event-store ctx)
                   {:events [legacy-event]
                    :tenant-id (:tenant-id ctx)})
        (rmp/l1-clear!))

      (testing "URI-keyed projection: the legacy edge lands on :related"
        (let [c (rm/get-concept-by-uri ctx "ex:L1")]
          (is (contains? (:related c) "ex:L2")
              "ex:L1's :related set contains ex:L2 from the legacy event")))

      (testing "Section-keyed projection (fallback path): legacy edge also lands"
        (let [c (rm/get-concept-by-uri ctx "ex:L1"
                                       {:ontology-id ontology-id})]
          (is (contains? (:related c) "ex:L2")
              "section-keyed projection has the edge too via shared-endpoints fallback"))))))

;; =============================================================================
;; AC7 — S02 simplification: new event with :ontology-id uses direct path
;; =============================================================================

(deftest relationship-with-ontology-id-uses-direct-path-not-section-scan
  (testing "When the relationship-created event carries `:ontology-id`, the
            section-keyed projection's handler uses the ontology-id
            DIRECTLY — no need to scan all sections to find shared
            endpoints. We test this BEHAVIORALLY: a relationship written
            with :ontology-id projects ONLY into that section, even when
            the SAME source URI exists in another section (where it
            would have been counted as 'shared' by the legacy scan).

            This is the bug S02 flagged: with two URI-collision sections,
            a relationship would have been duplicated onto BOTH sections
            under the legacy 'find sections that contain both endpoints'
            rule. With ontology-id present, it lands ONLY where intended."
    (h/with-test-context [ctx]
      ;; Two sections with COLLIDING URIs (the very scenario S02 was built for).
      (seed-pair! ctx ontology-id       "ex:Shared" "ex:Tgt")
      (seed-pair! ctx other-ontology-id "ex:Shared" "ex:Tgt")
      (create-relationship!
       ctx
       {:ontology-id ontology-id
        :source-uri "ex:Shared"
        :target-uri "ex:Tgt"
        :predicate "skos:related"})

      (testing "Edge lands in the explicitly-named section"
        (let [c (rm/get-concept-by-uri ctx "ex:Shared"
                                       {:ontology-id ontology-id})]
          (is (contains? (:related c) "ex:Tgt")
              "named section has the edge")))

      (testing "Edge does NOT land in the OTHER section (despite URI collision)"
        (let [c (rm/get-concept-by-uri ctx "ex:Shared"
                                       {:ontology-id other-ontology-id})]
          (is (not (contains? (:related c) "ex:Tgt"))
              "the other section, despite having the same URIs, does NOT receive
               the edge — the ontology-id on the event disambiguated"))))))
