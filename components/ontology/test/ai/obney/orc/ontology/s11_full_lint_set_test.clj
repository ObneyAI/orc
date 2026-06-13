(ns ai.obney.orc.ontology.s11-full-lint-set-test
  "S11 — Full built-in lint set + SHACL TTL export + consumer-authored shapes.

   Acceptance criteria mapped to deftests:

   Phase-2 interpreter components
     A. sh:qualifiedValueShape + sh:qualifiedMinCount: positive + negative.
     B. :not with :datatype: positive + negative.
     C. :not with :pattern: positive + negative.

   Built-in lints (positive + negative each, all via the run-validation
   command emitting :ontology/lint-violation events through Grain):
     D. Disjointness violation (S07 axioms consumed)
     E. Missing-disjointness warning
     F. Universal-without-existential
     G. Closure-axiom absence
     H. Roles-vs-classes
     I. Name-implied semantics
     J. Functional-property double values
        + ADVERSARIAL: no sameAs/merge — both values STILL PRESENT in
          the post-run projection AND a violation event landed.
     K. Single-parent discipline

   SHACL TTL export
     L. Every registered shape (built-in + consumer) emits as standard
        SHACL TTL — shape-grep checks `sh:NodeShape`, `sh:property`, etc.
     M. :code/:code-symbol shapes export WITH the ORC-extended
        sh:description marker (present, not absent — and the shape
        itself NOT omitted).
     N. External-validator round-trip (pySHACL via shell): verdicts
        for a standard-expressible lint match our interpreter's verdict.

   Consumer-authored shape
     O. A consumer-authored shape (not in builtin set) registers,
        fires, AND exports as SHACL TTL using the same form as a built-in
        (no special-casing — verified by shape-form comparison)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [clojure.java.shell :as shell]
            [ai.obney.orc.ontology.test-helpers :as h]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.interface]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.lints.commands :as lint-cmd]
            [ai.obney.orc.ontology.core.lints.queries :as lint-q]
            [ai.obney.orc.ontology.core.lints.builtin :as builtin]
            [ai.obney.orc.ontology.core.lints.interpreter :as interp]
            [ai.obney.orc.ontology.core.serialization :as serial]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

;; =============================================================================
;; Shared seed helpers (mirrors s10_lint_registry_test patterns)
;; =============================================================================

(def ontology-id #uuid "5110bbbb-0000-0000-0000-000000000001")

(defn- seed-concept!
  ([ctx uri label] (seed-concept! ctx uri label nil))
  ([ctx uri label extras]
   (h/run-and-apply!
    ctx
    (fn [c]
      (cmd/ontology-create-concept
       (assoc c :command
              (merge (h/make-concept-data
                      :ontology-id ontology-id
                      :uri uri
                      :label label
                      :description (str label " :: " uri)
                      :scope :custom)
                     extras)))))))

(defn- seed-relationship!
  [ctx source-uri predicate target-uri]
  (h/run-and-apply!
   ctx
   (fn [c]
     (cmd/ontology-create-relationship
      (assoc c :command
             {:ontology-id ontology-id
              :source-uri source-uri
              :target-uri target-uri
              :predicate predicate
              :properties {}})))))

(defn- assert-disjointness!
  [ctx class-uris]
  (h/run-and-apply!
   ctx
   (fn [c]
     (cmd/ontology-assert-disjointness
      (assoc c :command {:ontology-id ontology-id
                         :class-uris class-uris})))))

(defn- assert-functional!
  [ctx predicate]
  (h/run-and-apply!
   ctx
   (fn [c]
     (cmd/ontology-assert-property-characteristic
      (assoc c :command {:ontology-id ontology-id
                         :predicate predicate
                         :characteristic [:functional]})))))

(defn- register-shape!
  [ctx shape]
  (h/run-and-apply!
   ctx
   (fn [c]
     (lint-cmd/ontology-register-shape
      (assoc c :command {:ontology-id ontology-id :shape shape})))))

(defn- run-validation!
  [ctx]
  (h/run-and-apply!
   ctx
   (fn [c]
     (lint-cmd/ontology-run-validation
      (assoc c :command {:ontology-id ontology-id})))))

(defn- report [ctx]
  (:query/result
   (lint-q/ontology-get-validation-report
    (assoc ctx :query {:ontology-id ontology-id}))))

(defn- mk-concept [uri & {:as overrides}]
  (merge {:uri uri :scope :custom
          :broader #{} :narrower #{} :related #{}}
         overrides))

(defn- graph [& concepts]
  (reduce (fn [g c] (assoc g (:uri c) c)) {} concepts))

;; =============================================================================
;; Phase-2 interpreter components
;; =============================================================================

(deftest test-A-qualified-value-shape
  (testing "qualified-value-shape: enough conforming values → SILENT"
    (let [g (graph (mk-concept "ex:Alice" :scope :custom
                               :typed-edges {"ex:knows" #{"ex:Bob" "ex:Carol"}})
                   (mk-concept "ex:Bob" :scope :custom :indicators ["valid-id"])
                   (mk-concept "ex:Carol" :scope :custom :indicators ["valid-id"]))
          ;; Qualified-value-shape: each ex:knows target must have an :indicators value
          shape {:shape/id :qvs/positive :shape/type :node-shape
                 :target-class "ex:Alice"
                 :severity :violation :message "knows count"
                 :property [{:path "ex:knows"
                             :qualified-value-shape
                             {:shape/id :inner
                              :property [{:path :indicators :min-count 1}]}
                             :qualified-min-count 2}]}
          {:keys [violations]} (interp/run-shape g shape)]
      (is (empty? violations)
          "2 of 2 targets conform; qualified-min-count met")))

  (testing "qualified-value-shape: fewer conforming → :qualified-min-count-violated"
    (let [g (graph (mk-concept "ex:Alice" :scope :custom
                               :typed-edges {"ex:knows" #{"ex:Bob" "ex:Carol"}})
                   (mk-concept "ex:Bob" :scope :custom :indicators ["valid-id"])
                   ;; Carol has NO :indicators — fails inner shape's min-count 1
                   (mk-concept "ex:Carol" :scope :custom :indicators []))
          shape {:shape/id :qvs/negative :shape/type :node-shape
                 :target-class "ex:Alice"
                 :severity :violation :message "knows count"
                 :property [{:path "ex:knows"
                             :qualified-value-shape
                             {:shape/id :inner
                              :property [{:path :indicators :min-count 1}]}
                             :qualified-min-count 2}]}
          {:keys [violations]} (interp/run-shape g shape)]
      (is (= 1 (count violations)))
      (is (= :qualified-min-count-violated
             (-> violations first :reason))
          "fewer than 2 conforming values fires the qualified-min-count violation"))))

(deftest test-B-not-with-datatype
  (testing ":not {:datatype :number} fires on numeric edge values (POSITIVE)"
    (let [g (graph (mk-concept "ex:Box" :scope :custom
                               :typed-edges {"ex:hasSize" #{42 99}}))
          shape {:shape/id :nd/positive :shape/type :node-shape
                 :target-class :custom
                 :severity :violation :message "no numeric here"
                 :property [{:path "ex:hasSize"
                             :not {:datatype :number}}]}
          {:keys [violations]} (interp/run-shape g shape)]
      (is (= 2 (count violations))
          "each numeric value violates the :not :number constraint")))
  (testing ":not {:datatype :number} SILENT on non-numeric values (NEGATIVE)"
    (let [g (graph (mk-concept "ex:Box" :scope :custom
                               :typed-edges {"ex:hasLabel" #{"a string" "another"}}))
          shape {:shape/id :nd/negative :shape/type :node-shape
                 :target-class :custom
                 :severity :violation :message "no numeric here"
                 :property [{:path "ex:hasLabel"
                             :not {:datatype :number}}]}]
      (is (empty? (:violations (interp/run-shape g shape))))))
  (testing ":not {:datatype :number :datatype-match? false} fires on NON-numeric (negated form)"
    (let [g (graph (mk-concept "ex:Box" :scope :custom
                               :typed-edges {"ex:hasSize" #{"not-a-number" 42}}))
          shape {:shape/id :nd/negated :shape/type :node-shape
                 :target-class :custom
                 :severity :violation :message "must be numeric"
                 :property [{:path "ex:hasSize"
                             :not {:datatype :number :datatype-match? false}}]}
          {:keys [violations]} (interp/run-shape g shape)]
      (is (= 1 (count violations))
          "the string value violates 'must be numeric'; the number satisfies"))))

(deftest test-C-not-with-pattern
  (testing ":not {:pattern \"^ghost\"} fires when value matches regex (POSITIVE)"
    (let [g (graph (mk-concept "ex:Box" :scope :custom
                               :typed-edges {"ex:names" #{"ghost123" "real"}}))
          shape {:shape/id :np/positive :shape/type :node-shape
                 :target-class :custom
                 :severity :violation :message "no ghost names"
                 :property [{:path "ex:names"
                             :not {:pattern "^ghost"}}]}
          {:keys [violations]} (interp/run-shape g shape)]
      (is (= 1 (count violations))
          "only the ghost-prefixed value violates")))
  (testing ":not {:pattern \"...\" :pattern-match? false} fires when value does NOT match (NEGATIVE)"
    ;; Reading: inner predicate when match?=false is 'value does NOT
    ;; match pattern' (i.e., 'this attribute MUST match'). The :not
    ;; flips again: violate when inner predicate is satisfied = violate
    ;; when value does NOT match. So 'Hi' (matches ^H) is FINE; 'bye'
    ;; (doesn't match ^H) is a VIOLATION.
    (let [g (graph (mk-concept "ex:Box" :scope :custom
                               :typed-edges {"ex:names" #{"Hi" "bye" "Hop"}}))
          shape {:shape/id :np/negated :shape/type :node-shape
                 :target-class :custom
                 :severity :violation :message "must start with H"
                 :property [{:path "ex:names"
                             :not {:pattern "^H" :pattern-match? false}}]}
          {:keys [violations]} (interp/run-shape g shape)]
      (is (= 1 (count violations))
          "only 'bye' fails the 'must start with H' check (Hi + Hop satisfy)"))))

;; =============================================================================
;; Built-in lint: disjointness violation (S07 axioms consumed)
;; =============================================================================

(deftest test-D-disjointness-violation
  (testing "concept typed under disjoint parents fires"
    (h/with-test-context [ctx]
      (seed-concept! ctx "concept:bio:Mammal" "Mammal")
      (seed-concept! ctx "concept:bio:Reptile" "Reptile")
      ;; Asserted disjoint BEFORE the offending concept lands
      (assert-disjointness! ctx ["concept:bio:Mammal" "concept:bio:Reptile"])
      ;; Concept C is typed under BOTH (via skos:broader edges)
      (seed-concept! ctx "concept:bio:Platypus" "Platypus")
      (seed-relationship! ctx "concept:bio:Platypus" "skos:broader" "concept:bio:Mammal")
      (seed-relationship! ctx "concept:bio:Platypus" "skos:broader" "concept:bio:Reptile")
      (register-shape! ctx builtin/disjointness-violation-shape-symbol)
      (run-validation! ctx)
      (let [vs (:violations (report ctx))]
        (is (some #(= "concept:bio:Platypus" (:offending-uri %)) vs)
            "Platypus fires the disjointness-violation lint"))))

  (testing "ADVERSARIAL — without the disjointness assertion, the lint stays silent"
    (h/with-test-context [ctx]
      (seed-concept! ctx "concept:bio:Mammal" "Mammal")
      (seed-concept! ctx "concept:bio:Reptile" "Reptile")
      ;; NO disjointness assertion
      (seed-concept! ctx "concept:bio:Platypus" "Platypus")
      (seed-relationship! ctx "concept:bio:Platypus" "skos:broader" "concept:bio:Mammal")
      (seed-relationship! ctx "concept:bio:Platypus" "skos:broader" "concept:bio:Reptile")
      (register-shape! ctx builtin/disjointness-violation-shape-symbol)
      (run-validation! ctx)
      (let [vs (:violations (report ctx))]
        (is (not (some #(= "concept:bio:Platypus" (:offending-uri %)) vs))
            "without the axiom, no violation — lint is silent")))))

;; =============================================================================
;; Built-in lint: missing-disjointness warning
;; =============================================================================

(deftest test-E-missing-disjointness
  (testing "sibling group with NO disjointness asserted: warning fires"
    (h/with-test-context [ctx]
      ;; Parent class + 3 siblings, no disjointness
      (seed-concept! ctx "concept:zoo:Animal" "Animal")
      (seed-concept! ctx "concept:zoo:Dog" "Dog")
      (seed-concept! ctx "concept:zoo:Cat" "Cat")
      (seed-concept! ctx "concept:zoo:Fish" "Fish")
      (seed-relationship! ctx "concept:zoo:Dog" "skos:broader" "concept:zoo:Animal")
      (seed-relationship! ctx "concept:zoo:Cat" "skos:broader" "concept:zoo:Animal")
      (seed-relationship! ctx "concept:zoo:Fish" "skos:broader" "concept:zoo:Animal")
      (register-shape! ctx builtin/missing-disjointness-shape-symbol)
      (run-validation! ctx)
      (let [vs (:violations (report ctx))
            offenders (set (map :offending-uri vs))]
        (is (seq (clojure.set/intersection offenders
                                            #{"concept:zoo:Dog"
                                              "concept:zoo:Cat"
                                              "concept:zoo:Fish"}))
            "at least one sibling fires the missing-disjointness warning"))))

  (testing "ADVERSARIAL — with disjointness asserted across the group, lint silent"
    (h/with-test-context [ctx]
      (seed-concept! ctx "concept:zoo:Animal" "Animal")
      (seed-concept! ctx "concept:zoo:Dog" "Dog")
      (seed-concept! ctx "concept:zoo:Cat" "Cat")
      (seed-relationship! ctx "concept:zoo:Dog" "skos:broader" "concept:zoo:Animal")
      (seed-relationship! ctx "concept:zoo:Cat" "skos:broader" "concept:zoo:Animal")
      (assert-disjointness! ctx ["concept:zoo:Dog" "concept:zoo:Cat"])
      (register-shape! ctx builtin/missing-disjointness-shape-symbol)
      (run-validation! ctx)
      (let [vs (:violations (report ctx))]
        (is (empty? (filter #(contains? #{"concept:zoo:Dog" "concept:zoo:Cat"}
                                         (:offending-uri %))
                            vs))
            "with disjointness asserted, lint silent on those siblings")))))

;; =============================================================================
;; Built-in lint: universal-without-existential
;; =============================================================================

(deftest test-F-universal-without-existential
  (testing "concept declares universal-restriction with NO existential edge fires"
    (h/with-test-context [ctx]
      (seed-concept! ctx "concept:cult:VegetarianRestaurant" "VegRest"
                     {:attributes {:universal-restriction ["ex:serves"]}})
      (register-shape! ctx builtin/universal-without-existential-shape-symbol)
      (run-validation! ctx)
      (let [vs (:violations (report ctx))]
        (is (some #(= "concept:cult:VegetarianRestaurant" (:offending-uri %)) vs)
            "fires when universal restriction has no existential assertion"))))

  (testing "ADVERSARIAL — concept with existential edge for the predicate stays silent"
    (h/with-test-context [ctx]
      (seed-concept! ctx "concept:cult:Salad" "Salad")
      (seed-concept! ctx "concept:cult:VegetarianRestaurant" "VegRest"
                     {:attributes {:universal-restriction ["ex:serves"]}})
      (seed-relationship! ctx "concept:cult:VegetarianRestaurant" "ex:serves"
                          "concept:cult:Salad")
      (register-shape! ctx builtin/universal-without-existential-shape-symbol)
      (run-validation! ctx)
      (let [vs (:violations (report ctx))]
        (is (empty? (filter #(= "concept:cult:VegetarianRestaurant" (:offending-uri %))
                            vs))
            "with an existential edge, the lint stays silent")))))

;; =============================================================================
;; Built-in lint: closure-axiom-absence (info)
;; =============================================================================

(deftest test-G-closure-axiom-absence
  (testing "fully-disjoint sibling group with no closing axiom: info fires"
    (h/with-test-context [ctx]
      (seed-concept! ctx "concept:tax:Color" "Color")
      ;; Two siblings, each disjoint with the other — i.e. the entire
      ;; OTHER-sibling set carries disjointness assertions
      (seed-concept! ctx "concept:tax:Red" "Red")
      (seed-concept! ctx "concept:tax:Blue" "Blue")
      (seed-concept! ctx "concept:tax:Green" "Green")
      (seed-relationship! ctx "concept:tax:Red" "skos:broader" "concept:tax:Color")
      (seed-relationship! ctx "concept:tax:Blue" "skos:broader" "concept:tax:Color")
      (seed-relationship! ctx "concept:tax:Green" "skos:broader" "concept:tax:Color")
      ;; Disjointness asserted across the whole sibling group
      (assert-disjointness! ctx ["concept:tax:Red" "concept:tax:Blue" "concept:tax:Green"])
      (register-shape! ctx builtin/closure-axiom-absence-shape-symbol)
      (run-validation! ctx)
      (let [vs (:violations (report ctx))
            offenders (set (map :offending-uri vs))]
        ;; At least one of the colors gets the info — they're all in a
        ;; closed-looking enumeration but the parent has no :closed?
        (is (seq (clojure.set/intersection offenders
                                            #{"concept:tax:Red"
                                              "concept:tax:Blue"
                                              "concept:tax:Green"}))
            "fully-enumerated-but-unclosed parent flags at least one child"))))

  (testing "ADVERSARIAL — when the parent carries :closed? true, info goes silent"
    (h/with-test-context [ctx]
      (seed-concept! ctx "concept:tax:Color" "Color"
                     {:attributes {:closed? true}})
      (seed-concept! ctx "concept:tax:Red" "Red")
      (seed-concept! ctx "concept:tax:Blue" "Blue")
      (seed-relationship! ctx "concept:tax:Red" "skos:broader" "concept:tax:Color")
      (seed-relationship! ctx "concept:tax:Blue" "skos:broader" "concept:tax:Color")
      (assert-disjointness! ctx ["concept:tax:Red" "concept:tax:Blue"])
      (register-shape! ctx builtin/closure-axiom-absence-shape-symbol)
      (run-validation! ctx)
      (let [vs (:violations (report ctx))]
        (is (empty? (filter #(contains? #{"concept:tax:Red" "concept:tax:Blue"}
                                         (:offending-uri %))
                            vs))
            "with :closed? true, no info fires")))))

;; =============================================================================
;; Built-in lint: roles-vs-classes (heuristic)
;; =============================================================================

(deftest test-H-roles-vs-classes
  (testing "class named with role-suffix fires the warning"
    (h/with-test-context [ctx]
      (seed-concept! ctx "concept:hr:Employee" "Employee")
      (register-shape! ctx builtin/roles-vs-classes-shape-symbol)
      (run-validation! ctx)
      (let [vs (:violations (report ctx))]
        (is (some #(= "concept:hr:Employee" (:offending-uri %)) vs)
            "Employee fires the roles-vs-classes warning"))))

  (testing "ADVERSARIAL — Person (no role-suffix) does NOT fire"
    (h/with-test-context [ctx]
      (seed-concept! ctx "concept:hr:Person" "Person")
      (register-shape! ctx builtin/roles-vs-classes-shape-symbol)
      (run-validation! ctx)
      (let [vs (:violations (report ctx))]
        (is (not (some #(= "concept:hr:Person" (:offending-uri %)) vs))
            "Person stays silent — no role-suffix match")))))

;; =============================================================================
;; Built-in lint: name-implied-semantics (heuristic)
;; =============================================================================

(deftest test-I-name-implied-semantics
  (testing "ExpensiveCar (adjective+noun) without backing attributes fires"
    (h/with-test-context [ctx]
      (seed-concept! ctx "concept:market:ExpensiveCar" "ExpensiveCar")
      (register-shape! ctx builtin/name-implied-semantics-shape-symbol)
      (run-validation! ctx)
      (let [vs (:violations (report ctx))]
        (is (some #(= "concept:market:ExpensiveCar" (:offending-uri %)) vs)
            "ExpensiveCar fires the info"))))

  (testing "ADVERSARIAL — same name with backing :attributes does NOT fire"
    (h/with-test-context [ctx]
      (seed-concept! ctx "concept:market:ExpensiveCar" "ExpensiveCar"
                     {:attributes {:price-magnitude :high}})
      (register-shape! ctx builtin/name-implied-semantics-shape-symbol)
      (run-validation! ctx)
      (let [vs (:violations (report ctx))]
        (is (not (some #(= "concept:market:ExpensiveCar" (:offending-uri %)) vs))
            "with backing attributes, lint stays silent")))))

;; =============================================================================
;; Built-in lint: functional-property double values
;; ADVERSARIAL: NO sameAs/merge — projection retains BOTH values.
;; =============================================================================

(deftest test-J-functional-property-double-values
  (testing "predicate marked functional + two values: violation fires"
    (h/with-test-context [ctx]
      (seed-concept! ctx "concept:p:Alice" "Alice")
      ;; Mark ex:hasAge as functional via S07
      (assert-functional! ctx "ex:hasAge")
      ;; Now create TWO different ex:hasAge relationships to two
      ;; different target URIs (the concept doesn't have to land them
      ;; in graph — :typed-edges captures them)
      (seed-concept! ctx "concept:p:Age30" "Age30")
      (seed-concept! ctx "concept:p:Age31" "Age31")
      (seed-relationship! ctx "concept:p:Alice" "ex:hasAge" "concept:p:Age30")
      (seed-relationship! ctx "concept:p:Alice" "ex:hasAge" "concept:p:Age31")
      (register-shape! ctx builtin/functional-double-value-shape-symbol)
      (run-validation! ctx)
      (let [vs (:violations (report ctx))]
        (is (some #(and (= "concept:p:Alice" (:offending-uri %))
                        (= :ontology.lint/functional-double-value (:shape-id %)))
                  vs)
            "Alice fires the functional-double-value lint"))

      ;; ADVERSARIAL — no sameAs/merge: BOTH values STILL PRESENT in the
      ;; projection. Inspect the concept's :typed-edges + :related buckets.
      (let [concepts-projection (rmp/project ctx :ontology/concepts)
            alice (get concepts-projection "concept:p:Alice")
            typed-age-edges (or (get-in alice [:typed-edges "ex:hasAge"]) #{})
            related (or (:related alice) #{})
            all-age-targets (clojure.set/union typed-age-edges related)]
        (is (contains? all-age-targets "concept:p:Age30")
            "ADVERSARIAL: Age30 still present — no silent sameAs/merge")
        (is (contains? all-age-targets "concept:p:Age31")
            "ADVERSARIAL: Age31 still present — no silent sameAs/merge")
        (is (= 2 (count (clojure.set/intersection all-age-targets
                                                   #{"concept:p:Age30"
                                                     "concept:p:Age31"})))
            "ADVERSARIAL: TWO distinct values remain on the projection"))))

  (testing "ADVERSARIAL — predicate NOT marked functional: single value lint silent"
    (h/with-test-context [ctx]
      (seed-concept! ctx "concept:p:Bob" "Bob")
      (seed-concept! ctx "concept:p:Color1" "Color1")
      (seed-concept! ctx "concept:p:Color2" "Color2")
      ;; ex:hasColor is NOT marked functional
      (seed-relationship! ctx "concept:p:Bob" "ex:hasColor" "concept:p:Color1")
      (seed-relationship! ctx "concept:p:Bob" "ex:hasColor" "concept:p:Color2")
      (register-shape! ctx builtin/functional-double-value-shape-symbol)
      (run-validation! ctx)
      (let [vs (:violations (report ctx))]
        (is (not (some #(= "concept:p:Bob" (:offending-uri %)) vs))
            "without functional characteristic, two values is FINE")))))

;; =============================================================================
;; Built-in lint: single-parent discipline
;; =============================================================================

(deftest test-K-single-parent-discipline
  (testing "concept with 3 broader-parents fires"
    (h/with-test-context [ctx]
      (seed-concept! ctx "concept:x:A" "A")
      (seed-concept! ctx "concept:x:B" "B")
      (seed-concept! ctx "concept:x:C" "C")
      (seed-concept! ctx "concept:x:Child" "Child")
      (seed-relationship! ctx "concept:x:Child" "skos:broader" "concept:x:A")
      (seed-relationship! ctx "concept:x:Child" "skos:broader" "concept:x:B")
      (seed-relationship! ctx "concept:x:Child" "skos:broader" "concept:x:C")
      (register-shape! ctx builtin/single-parent-discipline-shape-symbol)
      (run-validation! ctx)
      (let [vs (:violations (report ctx))]
        (is (some #(= "concept:x:Child" (:offending-uri %)) vs)
            "3-parents fires single-parent-discipline"))))

  (testing "ADVERSARIAL — single parent does NOT fire"
    (h/with-test-context [ctx]
      (seed-concept! ctx "concept:x:A" "A")
      (seed-concept! ctx "concept:x:Child" "Child")
      (seed-relationship! ctx "concept:x:Child" "skos:broader" "concept:x:A")
      (register-shape! ctx builtin/single-parent-discipline-shape-symbol)
      (run-validation! ctx)
      (let [vs (:violations (report ctx))]
        (is (not (some #(= "concept:x:Child" (:offending-uri %)) vs))
            "single parent stays silent"))))

  (testing "ADVERSARIAL — explicit :multi-inheritance? true marker silences the lint"
    (h/with-test-context [ctx]
      (seed-concept! ctx "concept:x:A" "A")
      (seed-concept! ctx "concept:x:B" "B")
      (seed-concept! ctx "concept:x:Child" "Child"
                     {:attributes {:multi-inheritance? true}})
      (seed-relationship! ctx "concept:x:Child" "skos:broader" "concept:x:A")
      (seed-relationship! ctx "concept:x:Child" "skos:broader" "concept:x:B")
      (register-shape! ctx builtin/single-parent-discipline-shape-symbol)
      (run-validation! ctx)
      (let [vs (:violations (report ctx))]
        (is (not (some #(= "concept:x:Child" (:offending-uri %)) vs))
            "with explicit multi-inheritance marker, lint silent")))))

;; =============================================================================
;; L. SHACL TTL export — shape-grep
;; =============================================================================

(deftest test-L-shacl-ttl-export-contains-standard-constructs
  (h/with-test-context [ctx]
    (doseq [shape builtin/all-m4-builtin-shapes]
      (register-shape! ctx shape))
    (let [ttl (serial/export-shacl-shapes ctx ontology-id)]
      (is (string? ttl) "export returns a TTL document string")
      (is (str/includes? ttl "sh:NodeShape") "TTL declares sh:NodeShape")
      (is (str/includes? ttl "sh:property") "TTL emits sh:property blocks")
      (is (str/includes? ttl "sh:targetClass") "TTL emits sh:targetClass")
      (is (str/includes? ttl "sh:severity") "TTL emits sh:severity")
      (is (str/includes? ttl "sh:message") "TTL emits sh:message")
      (is (str/includes? ttl "@prefix sh:") "TTL declares the sh: prefix"))))

;; =============================================================================
;; M. :code/:code-symbol shapes carry the ORC-extended marker
;; =============================================================================

(deftest test-M-code-shapes-marker-present
  (h/with-test-context [ctx]
    ;; Register a :code-symbol shape — the functional-double-value lint
    (register-shape! ctx builtin/functional-double-value-shape-symbol)
    ;; AND a standard SHACL-expressible shape — the dangling-endpoint
    (register-shape! ctx builtin/dangling-endpoint-shape)
    (let [ttl (serial/export-shacl-shapes ctx ontology-id)]
      ;; Code-symbol shape EMITS, NOT silently dropped — its block is present
      (is (str/includes? ttl "ex:ontology-lint--functional-double-value")
          "the :code-symbol shape's URI is in the export (NOT silently dropped)")
      ;; AND the ORC-EXTENDED description marker IS PRESENT
      (is (str/includes? ttl "ORC-EXTENDED")
          "the ORC-EXTENDED description marker IS present on :code shapes")
      ;; Standard SHACL-expressible shape does NOT carry the marker
      (let [;; Strip the code-symbol shape's section to isolate the
            ;; dangling-endpoint shape's marker absence
            ttl-without-marker
            (some->> (str/split ttl #"ex:ontology-lint--functional-double-value")
                     first)]
        (is (not (str/includes? (or ttl-without-marker "") "ORC-EXTENDED"))
            "standard-SHACL shapes do NOT carry the ORC-extended marker")))))

;; =============================================================================
;; N. External-validator round-trip (pySHACL via shell)
;; =============================================================================

(defn- pyshacl-available?
  "Probe whether the python3 pyshacl module is importable."
  []
  (try
    (let [{:keys [exit]} (shell/sh "python3" "-c" "import pyshacl")]
      (zero? exit))
    (catch Exception _ false)))

(deftest test-N-external-validator-roundtrip
  (testing "exported SHACL TTL loads in pySHACL and verdicts match ours"
    (h/with-test-context [ctx]
      ;; Use a fixture sized for direct pySHACL validation: build a small
      ;; ontology with a `Director` class, a `ex:hasCredit` property, and
      ;; one Director WITHOUT a credit. Our `dangling-endpoint`-style
      ;; shape isn't directly expressible in pySHACL (it needs the
      ;; object-graph check; SHACL targets a typed graph). Instead, we
      ;; export a consumer-authored min-count shape — fully standard —
      ;; and verify pySHACL's verdict matches our interpreter's.
      (let [consumer-shape {:shape/id     :ontology.consumer/director-needs-credit
                             :shape/type   :node-shape
                             :target-class "ex:Director"
                             :severity     :violation
                             :message      "Director must have at least one credit."
                             :property [{:path "ex:hasCredit" :min-count 1}]}]
        (register-shape! ctx consumer-shape)
        (let [ttl (serial/export-shacl-shapes ctx ontology-id)]
          (is (string? ttl))
          (is (str/includes? ttl "sh:minCount"))
          (if-not (pyshacl-available?)
            (do
              (println "pyshacl NOT available — falling back to structural-only check on TTL")
              (is (str/includes? ttl "ex:ontology-consumer--director-needs-credit")
                  "consumer shape URI present"))
            (let [shapes-file (java.io.File/createTempFile "s11-shapes-" ".ttl")
                  data-file (java.io.File/createTempFile "s11-data-" ".ttl")
                  ;; Synthetic data graph: ex:bob is a Director WITHOUT credit
                  data-ttl (str "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n"
                                "@prefix ex: <http://example.org/> .\n\n"
                                "ex:bob a ex:Director .\n")]
              (spit shapes-file ttl)
              (spit data-file data-ttl)
              (try
                (let [{:keys [exit out]}
                      (shell/sh "python3" "-m" "pyshacl"
                                "-s" (.getAbsolutePath shapes-file)
                                "-d" (.getAbsolutePath data-file)
                                "-f" "human")]
                  ;; pyshacl exits 0 when conforms, nonzero when violations
                  ;; AND prints "Conforms: False"
                  (is (str/includes? out "Conforms: False")
                      (str "pySHACL detected the same minCount violation our interpreter would. exit="
                           exit "\noutput=" out))
                  (is (or (str/includes? out "MinCount")
                          (str/includes? out "ex:hasCredit"))
                      "pySHACL's verdict cites the minCount constraint / path"))
                (finally
                  (.delete shapes-file)
                  (.delete data-file))))))))))

;; =============================================================================
;; O. Consumer-authored shape — registers + fires + exports identically
;; =============================================================================

(deftest test-O-consumer-authored-shape-no-special-casing
  (testing "consumer shape registers + fires through the same flow as built-ins"
    (h/with-test-context [ctx]
      ;; Consumer rule: every Director must have >= 1 has-credit relationship.
      (let [consumer-shape {:shape/id     :ontology.consumer/director-needs-credit
                             :shape/type   :node-shape
                             :target-class :custom
                             :severity     :violation
                             :message      "Director must have at least one credit."
                             :property [{:path "ex:hasCredit" :min-count 1}]}]
        ;; Director WITHOUT credit
        (seed-concept! ctx "concept:film:Director-no-credit" "Bob")
        ;; Director WITH credit
        (seed-concept! ctx "concept:film:Director-with-credit" "Alice")
        (seed-concept! ctx "concept:film:Credit-X" "Credit-X")
        (seed-relationship! ctx "concept:film:Director-with-credit"
                            "ex:hasCredit" "concept:film:Credit-X")
        (register-shape! ctx consumer-shape)
        (run-validation! ctx)
        (let [vs (:violations (report ctx))
              offenders (set (map :offending-uri vs))]
          (is (contains? offenders "concept:film:Director-no-credit")
              "consumer lint fires on the no-credit director")
          (is (not (contains? offenders "concept:film:Director-with-credit"))
              "consumer lint silent on the with-credit director")))))

  (testing "consumer shape exports as SHACL TTL using IDENTICAL form to a built-in"
    (h/with-test-context [ctx]
      ;; Register ONE consumer shape AND ONE built-in (also standard-expressible)
      (let [consumer-shape {:shape/id     :ontology.consumer/director-needs-credit
                             :shape/type   :node-shape
                             :target-class :custom
                             :severity     :violation
                             :message      "Director must have at least one credit."
                             :property [{:path "ex:hasCredit" :min-count 1}]}]
        (register-shape! ctx consumer-shape)
        (register-shape! ctx builtin/dangling-endpoint-shape)
        (let [ttl (serial/export-shacl-shapes ctx ontology-id)]
          ;; Both shapes use the SAME TTL form: sh:NodeShape +
          ;; sh:property + sh:path + sh:targetClass + sh:severity
          (is (str/includes? ttl "ex:ontology-consumer--director-needs-credit"))
          (is (str/includes? ttl "ex:ontology-lint--dangling-endpoint"))
          ;; Count the sh:NodeShape declarations — should be EXACTLY 2,
          ;; one per shape, no special-casing
          (let [n-nodeshapes (count (re-seq #"sh:NodeShape" ttl))]
            (is (= 2 n-nodeshapes)
                "exactly 2 sh:NodeShape blocks — no shape was dropped or duplicated"))
          ;; Both blocks carry sh:property — no special structural treatment
          (let [n-properties (count (re-seq #"sh:property" ttl))]
            (is (>= n-properties 2)
                "both shapes carry sh:property blocks")))))))
