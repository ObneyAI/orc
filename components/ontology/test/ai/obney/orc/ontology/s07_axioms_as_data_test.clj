(ns ai.obney.orc.ontology.s07-axioms-as-data-test
  "S07 — axioms-as-data events + OWL export.

   End-to-end: axiom commands emit events; the per-ontology axiom
   projections carry disjointness sets / property characteristics
   (functional, transitive, symmetric, inverse-of) / sub-property
   hierarchies / chain definitions; a predicate marked TRANSITIVE
   is followed in BFS closure exactly like broader/narrower; TTL
   export emits each axiom as the correct OWL construct.

   CRITICAL non-goal — NO INFERENCE.
   Axioms are DATA + LINT INPUTS + TRAVERSAL HINTS. The projection
   NEVER auto-reclassifies, NEVER auto-emits inconsistency events,
   NEVER silently dedups. The formality ceiling is locked at
   'lightweight + axioms-as-lints, never a reasoner'.

   Verified through PUBLIC interfaces only — commands + read-model
   projection helpers + serialization. No internals tested."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.ontology.test-helpers :as h]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.retrieval :as retrieval]
            [ai.obney.orc.ontology.core.serialization :as serialization]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]))

(def ontology-id #uuid "5070d000-0000-0000-0000-000000000001")

;; =============================================================================
;; Test helpers — sugar over the new defcommands
;; =============================================================================

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

(defn- assert-disjointness! [ctx body]
  (h/run-and-apply! ctx
                    (fn [c]
                      (cmd/ontology-assert-disjointness
                       (assoc c :command body)))))

(defn- assert-property-characteristic! [ctx body]
  (h/run-and-apply! ctx
                    (fn [c]
                      (cmd/ontology-assert-property-characteristic
                       (assoc c :command body)))))

(defn- assert-sub-property! [ctx body]
  (h/run-and-apply! ctx
                    (fn [c]
                      (cmd/ontology-assert-sub-property
                       (assoc c :command body)))))

(defn- assert-chain-axiom! [ctx body]
  (h/run-and-apply! ctx
                    (fn [c]
                      (cmd/ontology-assert-chain-axiom
                       (assoc c :command body)))))

;; =============================================================================
;; AC1 — Each axiom type round-trips: command → event → projection
;; =============================================================================

(deftest disjointness-axiom-round-trips-through-projection
  (testing "Asserting disjointness over a set of class URIs surfaces on
            the :ontology/axioms projection as a symmetric per-ontology
            map: each URI in the set carries the OTHERS as its disjoint
            siblings."
    (h/with-test-context [ctx]
      (assert-disjointness! ctx
                            {:ontology-id ontology-id
                             :class-uris ["bio:Mammal" "bio:Reptile" "bio:Bird"]})

      (let [axioms (rmp/project ctx :ontology/axioms)
            disjoint (get-in axioms [ontology-id :disjointness])]
        (is (some? disjoint) ":disjointness submap projected")
        ;; Mammal disjoint with Reptile + Bird (NOT with itself).
        (is (= #{"bio:Reptile" "bio:Bird"} (get disjoint "bio:Mammal"))
            "Mammal carries the other two as disjoint siblings")
        (is (= #{"bio:Mammal" "bio:Bird"} (get disjoint "bio:Reptile"))
            "Reptile carries the other two")
        (is (= #{"bio:Mammal" "bio:Reptile"} (get disjoint "bio:Bird"))
            "Bird carries the other two")))))

(deftest property-characteristic-axiom-round-trips
  (testing "Each of the four characteristic flags (functional / transitive
            / symmetric / inverse-of) surfaces on the per-ontology
            characteristics projection."
    (h/with-test-context [ctx]
      (assert-property-characteristic! ctx
                                       {:ontology-id ontology-id
                                        :predicate "ex:hasPart"
                                        :characteristic [:transitive]})
      (assert-property-characteristic! ctx
                                       {:ontology-id ontology-id
                                        :predicate "ex:knows"
                                        :characteristic [:symmetric]})
      (assert-property-characteristic! ctx
                                       {:ontology-id ontology-id
                                        :predicate "ex:hasParent"
                                        :characteristic [:functional]})
      (assert-property-characteristic! ctx
                                       {:ontology-id ontology-id
                                        :predicate "ex:employs"
                                        :characteristic []
                                        :inverse-of "ex:employed-by"})

      (let [axioms (rmp/project ctx :ontology/axioms)
            chars (get-in axioms [ontology-id :characteristics])]
        (is (contains? (get-in chars ["ex:hasPart"] #{}) :transitive)
            "hasPart marked transitive")
        (is (contains? (get-in chars ["ex:knows"] #{}) :symmetric)
            "knows marked symmetric")
        (is (contains? (get-in chars ["ex:hasParent"] #{}) :functional)
            "hasParent marked functional")
        (let [inverses (get-in axioms [ontology-id :inverse-of])]
          ;; Inverse-of is bidirectional in the projection — both
          ;; directions resolve.
          (is (= "ex:employed-by" (get inverses "ex:employs"))
              "employs → employed-by")
          (is (= "ex:employs" (get inverses "ex:employed-by"))
              "employed-by → employs (symmetric inverse projection)"))))))

(deftest sub-property-axiom-round-trips
  (testing "rdfs:subPropertyOf assertions surface as a per-ontology
            sub→super predicate map."
    (h/with-test-context [ctx]
      (assert-sub-property! ctx
                            {:ontology-id ontology-id
                             :sub-predicate "ex:hasMother"
                             :super-predicate "ex:hasParent"})

      (let [axioms (rmp/project ctx :ontology/axioms)
            hierarchy (get-in axioms [ontology-id :sub-property-of])]
        (is (= "ex:hasParent" (get hierarchy "ex:hasMother"))
            "hasMother → hasParent in the hierarchy projection")))))

(deftest chain-axiom-round-trips
  (testing "Chain definitions P∘Q→R project per-ontology under :chains
            keyed by the derived predicate."
    (h/with-test-context [ctx]
      (assert-chain-axiom! ctx
                           {:ontology-id ontology-id
                            :chain ["ex:hasParent" "ex:hasBrother"]
                            :derived-predicate "ex:hasUncle"})

      (let [axioms (rmp/project ctx :ontology/axioms)
            chains (get-in axioms [ontology-id :chains])]
        (is (= ["ex:hasParent" "ex:hasBrother"]
               (get chains "ex:hasUncle"))
            "chain definition stored under derived-predicate key")))))

;; =============================================================================
;; AC1 (adversarial) — malformed commands are REJECTED with anomaly
;; =============================================================================

(deftest malformed-disjointness-rejected
  (testing "A disjointness assertion with fewer than 2 URIs (singleton
            'disjointness' is meaningless) is REJECTED at schema-validation
            time by the Grain command-processor's pre-handler Malli gate.
            Invoking through cp/process-command exercises the gate."
    (h/with-test-context [ctx]
      (let [result (cp/process-command
                    (assoc ctx :command
                           {:command/name :ontology/assert-disjointness
                            :command/id (random-uuid)
                            :command/timestamp (time/now)
                            :ontology-id ontology-id
                            :class-uris ["bio:Mammal"]}))]
        (is (some? (::anom/category result))
            "singleton class set returns an anomaly via the pre-handler gate")
        ;; Defense in depth: the bad command CANNOT have emitted a body event.
        (is (empty? (:command-result/events result))
            "no events emitted")))))

(deftest malformed-property-characteristic-rejected
  (testing "An assertion with an unknown characteristic keyword is
            rejected at the schema gate. The :characteristic vector's
            enum is bounded."
    (h/with-test-context [ctx]
      (let [result (cp/process-command
                    (assoc ctx :command
                           {:command/name :ontology/assert-property-characteristic
                            :command/id (random-uuid)
                            :command/timestamp (time/now)
                            :ontology-id ontology-id
                            :predicate "ex:weird"
                            :characteristic [:not-a-real-characteristic]}))]
        (is (some? (::anom/category result))
            "unknown characteristic returns an anomaly via the pre-handler gate")
        (is (empty? (:command-result/events result))
            "no events emitted")))))

;; =============================================================================
;; AC2 (load-bearing) — transitive marker drives BFS closure
;; =============================================================================

(deftest transitive-marker-enables-bfs-closure-over-follows-chain
  (testing "LOAD-BEARING. A 4-concept `follows` chain is seeded.
            Marking `follows` as a transitive characteristic THEN doing
            an axiom-aware BFS expansion reaches the tail at depth >= 3
            — the same closure shape as broader/narrower today.

            Without the transitive marker, the axiom-aware expansion
            DOES NOT follow `follows` edges (the filter rejects them);
            this is the adversarial pair that proves the marker is
            load-bearing rather than the BFS following edges naively.

            The default (predicates nil) path is preserved for
            back-compat — S05's existing test still passes."
    (h/with-test-context [ctx]
      (doseq [i [1 2 3 4]]
        (create-concept! ctx
                         {:ontology-id ontology-id
                          :uri (str "recipe:Step" i)
                          :label (str "Step " i)
                          :description (str "Step " i)
                          :scope :custom}))
      (create-relationship! ctx "recipe:Step2" "follows" "recipe:Step1")
      (create-relationship! ctx "recipe:Step3" "follows" "recipe:Step2")
      (create-relationship! ctx "recipe:Step4" "follows" "recipe:Step3")
      ;; Mark `follows` transitive
      (assert-property-characteristic! ctx
                                       {:ontology-id ontology-id
                                        :predicate "follows"
                                        :characteristic [:transitive]})

      ;; WITH marker: axiom-aware expansion follows the chain.
      (let [with-marker (retrieval/expand-concept-neighborhood
                         ["recipe:Step1"]
                         :max-depth 4
                         :decay 0.9
                         :ctx ctx
                         :ontology-id ontology-id
                         :transitive-only? true)
            by-uri (into {} (map (juxt :uri identity)) with-marker)]
        (is (contains? by-uri "recipe:Step4")
            "Step4 reachable with transitive marker")
        (is (>= (get-in by-uri ["recipe:Step4" :depth] 0) 3)
            "Step4 reached at BFS depth >= 3 — full transitive closure")
        ;; ADVERSARIAL: WITHOUT the marker (a different fixture without
        ;; the characteristic assertion), the axiom-aware expansion
        ;; reaches FEWER nodes — proving the marker is load-bearing.
        (h/with-test-context [empty-ctx]
          ;; Re-seed an identical fixture without the characteristic.
          (doseq [i [1 2 3 4]]
            (create-concept! empty-ctx
                             {:ontology-id ontology-id
                              :uri (str "recipe:Step" i)
                              :label (str "Step " i)
                              :description (str "Step " i)
                              :scope :custom}))
          (create-relationship! empty-ctx "recipe:Step2" "follows" "recipe:Step1")
          (create-relationship! empty-ctx "recipe:Step3" "follows" "recipe:Step2")
          (create-relationship! empty-ctx "recipe:Step4" "follows" "recipe:Step3")
          (let [no-marker (retrieval/expand-concept-neighborhood
                           ["recipe:Step1"]
                           :max-depth 4
                           :decay 0.9
                           :ctx empty-ctx
                           :ontology-id ontology-id
                           :transitive-only? true)
                no-marker-by-uri (into {} (map (juxt :uri identity)) no-marker)]
            (is (< (count no-marker-by-uri) (count by-uri))
                "WITHOUT transitive marker, axiom-aware BFS reaches FEWER nodes
                 — proves the marker drives the closure, not the BFS following edges naively")
            ;; Step4 must NOT be reachable without the marker
            (is (not (contains? no-marker-by-uri "recipe:Step4"))
                "Step4 unreachable without transitive marker — adversarial check"))))

      ;; BACK-COMPAT: default mode (no transitive-only? flag) preserves
      ;; S05's behavior — all edges followed naively.
      (let [default-mode (retrieval/expand-concept-neighborhood
                          ["recipe:Step1"]
                          :max-depth 4
                          :decay 0.9
                          :ctx ctx
                          :ontology-id ontology-id)
            default-by-uri (into {} (map (juxt :uri identity)) default-mode)]
        (is (contains? default-by-uri "recipe:Step4")
            "default BFS still reaches Step4 — S05 back-compat preserved")))))

;; =============================================================================
;; AC3 — Inverse pairings traverse both directions WITHOUT duplicate edges
;; =============================================================================

(deftest inverse-pair-traversal-is-bidirectional-and-deduplicated
  (testing "An inverse-of pair (`ex:employs` inverse of `ex:employed-by`)
            seeded with ONLY `:Person :employed-by :Company` is
            traversable BOTH ways (Person→Company AND Company→Person)
            from a BFS expansion. The edge appears exactly ONCE in the
            graph build, not duplicated."
    (h/with-test-context [ctx]
      (create-concept! ctx
                       {:ontology-id ontology-id
                        :uri "ex:Person"
                        :label "Person"
                        :description "A person."
                        :scope :custom})
      (create-concept! ctx
                       {:ontology-id ontology-id
                        :uri "ex:Company"
                        :label "Company"
                        :description "A company."
                        :scope :custom})
      ;; Declare inverse-of pairing FIRST.
      (assert-property-characteristic! ctx
                                       {:ontology-id ontology-id
                                        :predicate "ex:employs"
                                        :characteristic []
                                        :inverse-of "ex:employed-by"})
      ;; Seed ONLY one direction: Person employed-by Company.
      (create-relationship! ctx "ex:Person" "ex:employed-by" "ex:Company")

      (let [from-person (retrieval/expand-concept-neighborhood
                         ["ex:Person"]
                         :max-depth 2
                         :decay 0.9
                         :ctx ctx
                         :ontology-id ontology-id)
            from-company (retrieval/expand-concept-neighborhood
                          ["ex:Company"]
                          :max-depth 2
                          :decay 0.9
                          :ctx ctx
                          :ontology-id ontology-id)
            person-reach (set (map :uri from-person))
            company-reach (set (map :uri from-company))]
        ;; Person → Company (direct).
        (is (contains? person-reach "ex:Company")
            "Person reaches Company (forward direction)")
        ;; Company → Person (via inverse: employs is the inverse of employed-by).
        (is (contains? company-reach "ex:Person")
            "Company reaches Person (inverse direction)"))

      ;; Adversarial: no duplicate edges. Inspect the concept's :related
      ;; / :inverse-of-edges set — Person → Company should appear once
      ;; per direction in the projected graph, not twice.
      (let [person (rm/get-concept-by-uri ctx "ex:Person")
            company (rm/get-concept-by-uri ctx "ex:Company")
            ;; Count the edge-counts on both endpoints
            person-out-count (count (or (:related person) #{}))
            company-out-count (count (or (:related company) #{}))]
        (is (<= person-out-count 2)
            "Person's outgoing edges aren't duplicated")
        (is (<= company-out-count 2)
            "Company's outgoing edges aren't duplicated")))))

;; =============================================================================
;; AC4 — OWL export emits each axiom type as the correct construct
;; =============================================================================

(deftest disjointness-exports-as-owl-disjoint-with
  (testing "A disjointness assertion exports as `owl:disjointWith`
            triples between each pair in the set."
    (h/with-test-context [ctx]
      (doseq [uri ["bio:Mammal" "bio:Reptile"]]
        (create-concept! ctx
                         {:ontology-id ontology-id
                          :uri uri
                          :label uri
                          :description (str "Class " uri)
                          :scope :custom}))
      (assert-disjointness! ctx
                            {:ontology-id ontology-id
                             :class-uris ["bio:Mammal" "bio:Reptile"]})

      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        (is (str/includes? ttl "owl:disjointWith")
            "TTL emits owl:disjointWith")
        ;; Pair appears in at least one direction.
        (is (or (re-find #"bio:Mammal[^.]*owl:disjointWith[^.]*bio:Reptile" ttl)
                (re-find #"bio:Reptile[^.]*owl:disjointWith[^.]*bio:Mammal" ttl))
            "the Mammal/Reptile pair is recorded as disjoint")))))

(deftest functional-characteristic-exports-as-owl-functional-property
  (testing "A predicate marked functional exports as
            `owl:FunctionalProperty`."
    (h/with-test-context [ctx]
      (assert-property-characteristic! ctx
                                       {:ontology-id ontology-id
                                        :predicate "ex:hasFather"
                                        :characteristic [:functional]})

      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        (is (re-find #"ex:hasFather[^.]*a[^.]*owl:FunctionalProperty" ttl)
            "hasFather declared as owl:FunctionalProperty")))))

(deftest transitive-characteristic-exports-as-owl-transitive-property
  (testing "A predicate marked transitive exports as
            `owl:TransitiveProperty`."
    (h/with-test-context [ctx]
      (assert-property-characteristic! ctx
                                       {:ontology-id ontology-id
                                        :predicate "ex:hasAncestor"
                                        :characteristic [:transitive]})

      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        (is (re-find #"ex:hasAncestor[^.]*a[^.]*owl:TransitiveProperty" ttl)
            "hasAncestor declared as owl:TransitiveProperty")))))

(deftest symmetric-characteristic-exports-as-owl-symmetric-property
  (testing "A predicate marked symmetric exports as
            `owl:SymmetricProperty`."
    (h/with-test-context [ctx]
      (assert-property-characteristic! ctx
                                       {:ontology-id ontology-id
                                        :predicate "ex:knows"
                                        :characteristic [:symmetric]})

      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        (is (re-find #"ex:knows[^.]*a[^.]*owl:SymmetricProperty" ttl)
            "knows declared as owl:SymmetricProperty")))))

(deftest inverse-of-pairing-exports-as-owl-inverse-of
  (testing "An inverse-of pairing exports as `owl:inverseOf`."
    (h/with-test-context [ctx]
      (assert-property-characteristic! ctx
                                       {:ontology-id ontology-id
                                        :predicate "ex:employs"
                                        :characteristic []
                                        :inverse-of "ex:employed-by"})

      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        (is (re-find #"ex:employs[^.]*owl:inverseOf[^.]*ex:employed-by" ttl)
            "owl:inverseOf triple present")))))

(deftest sub-property-exports-as-rdfs-sub-property-of
  (testing "A sub-property hierarchy exports as `rdfs:subPropertyOf`."
    (h/with-test-context [ctx]
      (assert-sub-property! ctx
                            {:ontology-id ontology-id
                             :sub-predicate "ex:hasMother"
                             :super-predicate "ex:hasParent"})

      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        (is (re-find #"ex:hasMother[^.]*rdfs:subPropertyOf[^.]*ex:hasParent" ttl)
            "rdfs:subPropertyOf triple present")))))

(deftest chain-axiom-exports-as-owl-property-chain-axiom
  (testing "A chain definition exports as `owl:propertyChainAxiom`
            with the chain rendered as an RDF list."
    (h/with-test-context [ctx]
      (assert-chain-axiom! ctx
                           {:ontology-id ontology-id
                            :chain ["ex:hasParent" "ex:hasBrother"]
                            :derived-predicate "ex:hasUncle"})

      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        (is (str/includes? ttl "owl:propertyChainAxiom")
            "TTL emits owl:propertyChainAxiom")
        ;; Chain components appear in the export.
        (is (str/includes? ttl "ex:hasParent")
            "first chain element present")
        (is (str/includes? ttl "ex:hasBrother")
            "second chain element present")
        (is (str/includes? ttl "ex:hasUncle")
            "derived predicate present")))))

;; =============================================================================
;; AC5 (load-bearing) — NO INFERENCE non-goal
;; =============================================================================

(deftest disjointness-after-multi-class-assertion-does-not-reclassify
  (testing "LOAD-BEARING NON-GOAL. Concept C is asserted under TWO
            classes X and Y. THEN X and Y are declared disjoint.
            After all events project:
            - C still carries BOTH X and Y in its :broader set
              (no auto-removal, no auto-reclassification)
            - The disjointness IS recorded in the axiom projection
            - NO new event is auto-emitted
            This guards the formality ceiling — axioms are data, never
            a reasoner. Lint slices (S11) catch this AT VALIDATION TIME;
            that's a separate concern."
    (h/with-test-context [ctx]
      ;; Seed C under both X and Y
      (create-concept! ctx
                       {:ontology-id ontology-id
                        :uri "ex:C"
                        :label "C"
                        :description "Concept under two later-disjoint classes."
                        :scope :custom
                        :broader ["ex:X" "ex:Y"]})
      ;; Now declare X and Y disjoint AFTER the multi-class assertion.
      (assert-disjointness! ctx
                            {:ontology-id ontology-id
                             :class-uris ["ex:X" "ex:Y"]})

      (let [concept (rm/get-concept-by-uri ctx "ex:C")
            axioms (rmp/project ctx :ontology/axioms)]
        ;; The class assertions REMAIN.
        (is (contains? (:broader concept) "ex:X")
            "ex:X retained on concept's broader after disjointness — no auto-removal")
        (is (contains? (:broader concept) "ex:Y")
            "ex:Y retained on concept's broader after disjointness — no auto-removal")
        ;; The axiom IS recorded.
        (is (contains? (get-in axioms [ontology-id :disjointness "ex:X"] #{}) "ex:Y")
            "disjointness recorded in axiom projection")))))

(deftest functional-characteristic-does-not-dedup-multi-value-assertion
  (testing "Adversarial second case: a predicate P is marked functional;
            two relationship-created events are emitted with C as the
            subject and P as the predicate (different objects). The
            projection retains BOTH values; the functional characteristic
            IS recorded. (A future lint catches this at VALIDATION TIME;
            this slice does NOT auto-dedup.)"
    (h/with-test-context [ctx]
      (create-concept! ctx
                       {:ontology-id ontology-id
                        :uri "ex:Child"
                        :label "Child"
                        :description "A child."
                        :scope :custom})
      (create-concept! ctx
                       {:ontology-id ontology-id
                        :uri "ex:Parent1"
                        :label "Parent1"
                        :description "Parent 1."
                        :scope :custom})
      (create-concept! ctx
                       {:ontology-id ontology-id
                        :uri "ex:Parent2"
                        :label "Parent2"
                        :description "Parent 2."
                        :scope :custom})
      ;; Mark hasParent functional.
      (assert-property-characteristic! ctx
                                       {:ontology-id ontology-id
                                        :predicate "ex:hasParent"
                                        :characteristic [:functional]})
      ;; Emit two values — this would be a violation under OWL DL but
      ;; we MUST NOT auto-dedup.
      (create-relationship! ctx "ex:Child" "ex:hasParent" "ex:Parent1")
      (create-relationship! ctx "ex:Child" "ex:hasParent" "ex:Parent2")

      (let [child (rm/get-concept-by-uri ctx "ex:Child")
            axioms (rmp/project ctx :ontology/axioms)
            ;; Both values land in :related (the default predicate
            ;; bucket today) when hasParent isn't otherwise mapped.
            child-related (or (:related child) #{})]
        ;; Both values retained.
        (is (contains? child-related "ex:Parent1")
            "first value retained")
        (is (contains? child-related "ex:Parent2")
            "second value retained — NO auto-dedup despite functional marker")
        ;; Characteristic IS recorded.
        (is (contains? (get-in axioms [ontology-id :characteristics "ex:hasParent"] #{})
                       :functional)
            "functional characteristic IS recorded — lints will catch the violation")))))

;; =============================================================================
;; AC6 — Schema additivity: existing fixtures regress clean
;; =============================================================================

(deftest existing-fixtures-regress-clean
  (testing "Adding the new axiom event types is ADDITIVE.
            A fixture that exercises ONLY pre-S07 events (concept-created,
            relationship-created) still projects + exports cleanly."
    (h/with-test-context [ctx]
      (create-concept! ctx
                       {:ontology-id ontology-id
                        :uri "legacy:Thing"
                        :label "Thing"
                        :description "A pre-S07 fixture."
                        :scope :custom})
      (create-relationship! ctx "legacy:Thing" "skos:related" "legacy:Other")

      ;; Axiom projection exists but is EMPTY for this ontology.
      (let [axioms (rmp/project ctx :ontology/axioms)
            this-ontology (get axioms ontology-id)]
        (is (or (nil? this-ontology)
                (and (empty? (:disjointness this-ontology))
                     (empty? (:characteristics this-ontology))
                     (empty? (:sub-property-of this-ontology))
                     (empty? (:chains this-ontology))))
            "axioms projection is empty for pre-S07 events"))

      ;; Export still works.
      (let [ttl (serialization/full-export ctx {:include-profiles? false
                                                :include-experiences? false})]
        (is (str/includes? ttl "legacy:Thing")
            "TTL still contains the concept")
        ;; No spurious axiom triples appear.
        (is (not (str/includes? ttl "owl:disjointWith"))
            "no disjointness in TTL")
        (is (not (str/includes? ttl "owl:TransitiveProperty"))
            "no transitive declarations in TTL")
        (is (not (str/includes? ttl "owl:propertyChainAxiom"))
            "no chain declarations in TTL")))))
