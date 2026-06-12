(ns ai.obney.orc.ontology.s05-quantities-and-sequences-test
  "S05 — quantity+unit attribute values + ordered-sequence convention.

   End-to-end: command → event → projection (URI-keyed + section-keyed)
   → TTL export. Verifies behavior through PUBLIC interfaces only
   (commands, defqueries, public read-model helpers, serialization) —
   no internals.

   Two representation needs:

   1. Quantity+unit values: extends S04's `:attributes` field with an
      optional `:unit` key. A value can be a bare scalar, an S04
      `{:value :datatype}` map, OR an S05 `{:value :unit (:datatype?)}`
      map. The presence of `:unit` triggers QUDT-style TTL output —
      `qudt:QuantityValue` blank node with `qudt:numericValue` and
      `qudt:unit` predicates.

   2. Ordered sequences: a CONVENTION on the existing relationship
      machinery — emit relationship-created with predicate
      `\"immediately-follows\"` for the step→step pairing. Multi-hop
      \"X follows Y\" works through standard BFS expansion (the graph
      builder bidirectionally adds unknown predicates to its edge list
      via the default `:related` branch). S07 will add a
      transitive-closure marker for the `\"follows\"` predicate; S05
      establishes only the convention.

   Adversarial spots (per disciplines block):
   - 75kg vs 75lbs: two attributes carrying the SAME numeric value with
     DIFFERENT units must remain distinguishable through the public
     surface (both the projection AND the TTL emit both units).
   - Multi-hop sequence reach: a 4-concept `immediately-follows` chain
     must be reachable from step1 at BFS depth ≥3 — i.e., step4 surfaces
     in the expansion with a recorded `:depth >= 3`.
   - Bare-numeric back-compat: an `:attributes :age 42` event still
     projects + exports untyped after S05 (no phantom unit added)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.ontology.test-helpers :as h]
            ;; Required for event-schema registration — same idiom S02/S04.
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.retrieval :as retrieval]
            [ai.obney.orc.ontology.core.serialization :as serialization]))

(def ontology-id #uuid "5050d000-0000-0000-0000-000000000001")

(defn- create-concept! [ctx body]
  (h/run-and-apply! ctx
                    (fn [c]
                      (cmd/ontology-create-concept
                       (assoc c :command body)))))

(defn- create-relationship! [ctx source-uri predicate target-uri]
  (h/run-and-apply! ctx
                    (fn [c]
                      (cmd/ontology-create-relationship
                       (assoc c :command
                              {:source-uri source-uri
                               :target-uri target-uri
                               :predicate predicate
                               :properties {}})))))

;; =============================================================================
;; AC1 — Quantity+unit round-trips through projection (URI-keyed + section-keyed)
;; =============================================================================

(deftest quantity-with-unit-round-trips-through-uri-keyed-projection
  (testing "An :attributes entry with the {:value :unit} shape surfaces
            verbatim on the URI-keyed projection — both value AND unit
            preserved."
    (h/with-test-context [ctx]
      (create-concept! ctx
                       {:ontology-id ontology-id
                        :uri "person:Patient1"
                        :label "Patient 1"
                        :description "A patient with a weight reading."
                        :scope :custom
                        :attributes {:weight {:value 75 :unit "kg"}}})

      (let [concept (rm/get-concept-by-uri ctx "person:Patient1")]
        (is (some? concept) "concept projects")
        (is (= {:value 75 :unit "kg"}
               (get-in concept [:attributes :weight]))
            "quantity+unit preserved verbatim on URI-keyed projection")
        (is (= 75 (get-in concept [:attributes :weight :value]))
            ":value accessible")
        (is (= "kg" (get-in concept [:attributes :weight :unit]))
            ":unit accessible")))))

(deftest quantity-with-unit-round-trips-through-section-keyed-projection
  (testing "Same quantity+unit attribute also surfaces on the S02
            section-keyed projection — the dual-projection invariant
            S04 established holds for S05's value shape."
    (h/with-test-context [ctx]
      (create-concept! ctx
                       {:ontology-id ontology-id
                        :uri "person:Patient2"
                        :label "Patient 2"
                        :description "A patient with a height reading."
                        :scope :custom
                        :attributes {:height {:value 1.75 :unit "m" :datatype :xsd/decimal}}})

      (let [section-concept (rm/get-concept-by-uri ctx "person:Patient2"
                                                   {:ontology-id ontology-id})]
        (is (= {:value 1.75 :unit "m" :datatype :xsd/decimal}
               (get-in section-concept [:attributes :height]))
            "quantity+unit+datatype preserved on section-keyed projection")))))

;; =============================================================================
;; AC2 — Adversarial 75kg vs 75lbs distinguishability
;; =============================================================================

(deftest same-numeric-different-unit-remain-distinguishable
  (testing "Two attributes carrying the SAME numeric value (75) with
            DIFFERENT units (kg vs lbs) must remain distinct through
            the public retrieval surface AND in the TTL. Silent
            collapse — where the unit is dropped and both look like
            bare 75 — is the failure mode under test."
    (h/with-test-context [ctx]
      (create-concept! ctx
                       {:ontology-id ontology-id
                        :uri "person:Adversarial"
                        :label "Adversarial Patient"
                        :description "Two weight readings, same number, different units."
                        :scope :custom
                        :attributes {:weight-metric    {:value 75 :unit "kg"}
                                     :weight-imperial  {:value 75 :unit "lbs"}}})

      (let [concept (rm/get-concept-by-uri ctx "person:Adversarial")
            metric (get-in concept [:attributes :weight-metric])
            imperial (get-in concept [:attributes :weight-imperial])]
        ;; Distinct map values — not equal.
        (is (not= metric imperial)
            "75kg and 75lbs are distinct values on the projection")
        ;; The unit string itself must be present — not silently dropped.
        (is (= "kg" (:unit metric)))
        (is (= "lbs" (:unit imperial)))
        ;; Same numeric value — proves we're testing the same-number case.
        (is (= 75 (:value metric)))
        (is (= 75 (:value imperial))))

      ;; TTL: both units appear, adjacent to their respective values.
      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        (is (str/includes? ttl "\"kg\"")
            "the kg unit string appears in TTL (not silently dropped)")
        (is (str/includes? ttl "\"lbs\"")
            "the lbs unit string appears in TTL (not silently dropped)")
        ;; Adversarial: each unit appears with the QUDT predicate, not
        ;; smuggled into a comment or stray literal.
        (is (re-find #"qudt:unit\s+\"kg\"" ttl)
            "qudt:unit predicate carries the kg string")
        (is (re-find #"qudt:unit\s+\"lbs\"" ttl)
            "qudt:unit predicate carries the lbs string")))))

;; =============================================================================
;; AC3 — TTL export renders the QUDT pattern
;; =============================================================================

(deftest quantity-attribute-exports-as-qudt-quantity-value
  (testing "An :attributes value with :unit emits the QUDT pattern in
            TTL: a qudt:QuantityValue blank node with qudt:numericValue
            and qudt:unit predicates."
    (h/with-test-context [ctx]
      (create-concept! ctx
                       {:ontology-id ontology-id
                        :uri "physics:Ball"
                        :label "Ball"
                        :description "A ball with mass."
                        :scope :custom
                        :attributes {:mass {:value 2.5 :unit "kg" :datatype :xsd/decimal}}})

      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        (is (str/includes? ttl "qudt:QuantityValue")
            "TTL contains qudt:QuantityValue class")
        (is (str/includes? ttl "qudt:numericValue")
            "TTL contains qudt:numericValue predicate")
        (is (str/includes? ttl "qudt:unit")
            "TTL contains qudt:unit predicate")
        ;; The QUDT prefix must be declared in the header.
        (is (str/includes? ttl "@prefix qudt:")
            "qudt: prefix declared in the TTL header")
        ;; The value's datatype is preserved on the numeric-value literal.
        (is (re-find #"qudt:numericValue\s+\"2\.5\"\^\^xsd:decimal" ttl)
            "datatype rides through to qudt:numericValue when supplied")
        ;; The unit appears as a string literal next to qudt:unit.
        (is (re-find #"qudt:unit\s+\"kg\"" ttl)
            "unit emitted as plain literal on qudt:unit predicate")))))

;; =============================================================================
;; AC4 — Bare numerics still export untyped (back-compat)
;; =============================================================================

(deftest bare-numeric-attribute-still-projects-and-exports-untyped
  (testing "An :attributes entry that's a bare scalar (no :value/:unit
            map shape) still projects + exports as a plain literal —
            no phantom QUDT block, no phantom xsd type."
    (h/with-test-context [ctx]
      (create-concept! ctx
                       {:ontology-id ontology-id
                        :uri "person:Legacy"
                        :label "Legacy"
                        :description "A concept with a bare-numeric age."
                        :scope :custom
                        :attributes {:age 42}})

      (let [concept (rm/get-concept-by-uri ctx "person:Legacy")]
        (is (= 42 (get-in concept [:attributes :age]))
            "bare numeric preserved verbatim — no map-wrapping"))

      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        (is (str/includes? ttl "\"42\"")
            "bare 42 emitted as plain literal in TTL")
        ;; Adversarial: bare value does NOT acquire a phantom QUDT block
        ;; or xsd type.
        (is (not (re-find #"orc:age\s+\[\s*a\s+qudt:QuantityValue" ttl))
            "bare numeric MUST NOT acquire a phantom qudt:QuantityValue wrapper")
        (is (not (re-find #"\"42\"\^\^xsd" ttl))
            "bare numeric MUST NOT acquire a phantom xsd type")))))

;; =============================================================================
;; AC5 — S04 datatyped attributes still survive (no regression)
;; =============================================================================

(deftest s04-datatyped-attribute-still-exports-as-typed-literal
  (testing "S04's typed-literal export path survives — an :attributes
            entry with :value+:datatype (no :unit) still emits
            ^^xsd:<type>, NOT a QUDT wrapper."
    (h/with-test-context [ctx]
      (create-concept! ctx
                       {:ontology-id ontology-id
                        :uri "person:Year"
                        :label "Year"
                        :description "S04 datatype back-compat."
                        :scope :custom
                        :attributes {:established {:value 2026 :datatype :xsd/integer}}})

      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        (is (re-find #"orc:established\s+\"2026\"\^\^xsd:integer" ttl)
            "S04 datatype path still emits ^^xsd:integer")
        ;; Adversarial: the S04 datatype value does NOT acquire a QUDT
        ;; wrapper just because the QUDT branch was added.
        (is (not (re-find #"orc:established\s+\[\s*a\s+qudt:QuantityValue" ttl))
            "S04 datatyped attribute MUST NOT route through the QUDT branch")))))

;; =============================================================================
;; AC6 — Ordered sequence convention: immediately-follows chain is
;;       traversable in order through BFS expansion
;; =============================================================================

(deftest immediately-follows-chain-is-traversable-via-bfs-expansion
  (testing "A 4-step ordered sequence — step1 → step2 → step3 → step4
            written via :ontology/create-relationship with predicate
            \"immediately-follows\" — is traversable from step1 through
            the public expand-concept-neighborhood surface. All four
            steps appear in the BFS result; step4 is reachable from
            step1 at BFS depth >= 3 (multi-hop reachability — proof
            that the chain is NOT just pairwise-linked but transitively
            reachable end-to-end)."
    (h/with-test-context [ctx]
      ;; Seed the four steps.
      (doseq [i [1 2 3 4]]
        (create-concept! ctx
                         {:ontology-id ontology-id
                          :uri (str "recipe:Step" i)
                          :label (str "Step " i)
                          :description (str "The " (case i 1 "first" 2 "second" 3 "third" 4 "fourth")
                                            " step of the procedure.")
                          :scope :custom}))
      ;; Chain them with immediately-follows: step2 immediately-follows step1, etc.
      (create-relationship! ctx "recipe:Step2" "immediately-follows" "recipe:Step1")
      (create-relationship! ctx "recipe:Step3" "immediately-follows" "recipe:Step2")
      (create-relationship! ctx "recipe:Step4" "immediately-follows" "recipe:Step3")

      ;; Public BFS surface: expand from step1, depth 4 to give room for
      ;; the 3-hop reach to step4.
      (let [results (retrieval/expand-concept-neighborhood
                     ["recipe:Step1"]
                     :max-depth 4
                     :decay 0.9
                     :ctx ctx
                     :ontology-id ontology-id)
            by-uri (into {} (map (juxt :uri identity)) results)]
        ;; All four steps appear in the expansion.
        (is (contains? by-uri "recipe:Step1") "Step1 (seed) present")
        (is (contains? by-uri "recipe:Step2") "Step2 reachable")
        (is (contains? by-uri "recipe:Step3") "Step3 reachable")
        (is (contains? by-uri "recipe:Step4") "Step4 reachable")
        ;; Adversarial: Step4 must be reached via multi-hop traversal,
        ;; not by accident. Assert depth >= 3 on Step4's BFS record.
        ;; This is the load-bearing distinguisher between "pairs work"
        ;; and "the chain is actually transitively traversable."
        (is (>= (get-in by-uri ["recipe:Step4" :depth]) 3)
            "Step4 reached at BFS depth >= 3 — the chain is fully traversable")
        ;; Path on Step4 records the route — must include intermediate steps.
        (let [step4-path (get-in by-uri ["recipe:Step4" :path])]
          (is (= "recipe:Step1" (first step4-path))
              "Step4's path starts at the seed")
          (is (= "recipe:Step4" (last step4-path))
              "Step4's path ends at Step4")
          (is (>= (count step4-path) 4)
              "path includes at least 4 nodes — the full chain"))))))

;; =============================================================================
;; AC7 — Bundle all together (composability regression)
;; =============================================================================

(deftest quantity-and-sequence-and-s04-bundle-coexist-on-one-graph
  (testing "A graph with BOTH a quantity-bearing concept AND a 3-step
            sequence AND an S04-datatyped concept exports cleanly —
            proves S05's additions compose with the prior representation
            bundle without interference."
    (h/with-test-context [ctx]
      ;; Quantity-bearing concept.
      (create-concept! ctx
                       {:ontology-id ontology-id
                        :uri "physics:Object"
                        :label "Object"
                        :description "A physical object."
                        :scope :custom
                        :attributes {:mass {:value 10 :unit "kg"}}})
      ;; S04 datatyped concept.
      (create-concept! ctx
                       {:ontology-id ontology-id
                        :uri "event:Year"
                        :label "Year"
                        :description "An establishment year."
                        :scope :custom
                        :attributes {:established {:value 2026 :datatype :xsd/integer}}})
      ;; Sequence: 3 steps.
      (doseq [i [1 2 3]]
        (create-concept! ctx
                         {:ontology-id ontology-id
                          :uri (str "proc:Step" i)
                          :label (str "Step " i)
                          :description (str "Procedure step " i)
                          :scope :custom}))
      (create-relationship! ctx "proc:Step2" "immediately-follows" "proc:Step1")
      (create-relationship! ctx "proc:Step3" "immediately-follows" "proc:Step2")

      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        ;; All three representation strands appear in one TTL.
        (is (str/includes? ttl "qudt:QuantityValue")
            "quantity TTL present")
        (is (re-find #"\"2026\"\^\^xsd:integer" ttl)
            "S04 datatyped TTL present")
        (is (str/includes? ttl "proc:Step1")
            "sequence steps present in TTL"))

      ;; Sequence BFS still works alongside everything else.
      (let [results (retrieval/expand-concept-neighborhood
                     ["proc:Step1"]
                     :max-depth 3
                     :decay 0.9
                     :ctx ctx
                     :ontology-id ontology-id)
            uris (set (map :uri results))]
        (is (contains? uris "proc:Step3")
            "Step3 reachable from Step1 in the composite graph")))))
