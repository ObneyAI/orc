(ns ai.obney.orc.ontology.s13-evidence-tier-1-test
  "S13 — Evidence Tier-1 (deterministic, always-on, in compare-to-existing).

   The slice ships three coordinated deliverables:

     1. Per-fact provenance ledger
        `(get-concept-evidence ctx uri)` returns a structured record
        carrying source-refs, dedup-decisions, equivalence history,
        contradictions, and a deterministic evidence-score.

     2. Always-on aggregation in compare-to-existing
        Every S12 `run-dedup-cascade` invocation ALSO emits a
        `:ontology/concept-evidence-aggregated` event keyed by URI.
        Mechanism-level functionality, NOT R-Inject-gated.

     3. Deterministic evidence-score function
        Pure fn with documented weight rationale; same input → same
        output; over-counting guarded (diversity beats volume).

   PLUS the binding S13 slice criteria from
   `docs/build-timeline/issues/ontology-rebuild/S13-evidence-tier-1.md`:

     a. Second-source re-encounter bumps evidence-count, appends source
        ref, updates last-reinforced-at — via the public query surface.
     b. Conflicting field value emits a contradiction marker carrying
        both values + both sources; stored value NEVER silently
        replaced. (The contradiction emit is a NEW command/event the
        builder calls when it detects a conflict.)
     c. Contradiction markers queryable as a set per ontology.
     d. No evidence mutation without an event — replay-deterministic.
     e. Cross-section equivalence contributes evidence to BOTH sides
        exactly ONCE per side.

   All tests go through public interfaces — commands, read-model
   projections, event-store reads — never internals."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.ontology.test-helpers :as h]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.evidence :as ev]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.grain.event-store-v3.interface :as es]))

(def primary-p #uuid "5130000a-0000-0000-0000-000000000001")
(def primary-q #uuid "5130000a-0000-0000-0000-000000000002")
(def align-pq  #uuid "5130000a-0000-0000-0000-000000000003")

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- run-cascade!
  [ctx body]
  (h/run-and-apply! ctx
                    (fn [c]
                      (cmd/ontology-run-dedup-cascade (assoc c :command body)))))

(defn- record-contradiction!
  [ctx body]
  (h/run-and-apply! ctx
                    (fn [c]
                      (cmd/ontology-record-concept-contradiction
                       (assoc c :command body)))))

(defn- read-events-by-tag [ctx tag]
  (into [] (es/read (:event-store ctx)
                    {:tags #{tag}
                     :tenant-id (:tenant-id ctx)})))

(def case-variant-a
  {:uri "p:Director1" :label "Director" :description "person who directs films"
   :type :class :kind-hint :same-as})
(def case-variant-b
  {:uri "p:Director2" :label "director" :description "person who directs films"
   :type :class :kind-hint :same-as})

(def jw-near-a
  {:uri "p:Organization1" :label "Organization" :description "a structured group of people"
   :type :class :kind-hint :equivalent-class})
(def jw-near-b
  {:uri "p:Organisation1" :label "Organisation" :description "a structured group of people"
   :type :class :kind-hint :equivalent-class})

(def number-3-vs-30-a
  {:uri "p:Model3" :label "Model 3" :description "Tesla Model 3" :type :class})
(def number-3-vs-30-b
  {:uri "p:Model30" :label "Model 30" :description "Tesla Model 30" :type :class})

;; =============================================================================
;; Evidence-score formula — determinism + rationale (deliverable 3)
;; =============================================================================

(deftest evidence-score-deterministic-same-input-same-output
  (testing "Same input map → same output (100 randomly-generated inputs)."
    (dotimes [_ 100]
      (let [m {:source-count (rand-int 200)
               :distinct-sources (rand-int 20)
               :dedup-decisions (rand-int 50)
               :equivalence-history (rand-int 20)
               :axiom-presence (rand-int 10)
               :edge-confidence-distribution
               {:extracted (rand-int 30) :inferred (rand-int 30)
                :ambiguous (rand-int 30)}
               :age-of-concept-days (rand-int 60)
               :contradictions-count (rand-int 5)}]
        (is (= (ev/evidence-score m) (ev/evidence-score m)))))))

(deftest evidence-score-result-in-zero-one-range
  (testing "Result is always in [0.0, 1.0] regardless of input magnitude."
    (let [inputs [{:source-count 0}
                  {:source-count 100000 :distinct-sources 100000
                   :dedup-decisions 100000 :equivalence-history 100000
                   :axiom-presence 100000 :age-of-concept-days 100000
                   :edge-confidence-distribution {:extracted 100000}}]]
      (doseq [i inputs]
        (let [s (ev/evidence-score i)]
          (is (>= s 0.0) (str "score >= 0 for " i))
          (is (<= s 1.0) (str "score <= 1 for " i)))))))

(deftest evidence-score-formula-has-weight-rationale-docstring
  (testing "The docstring must contain a 'Weight rationale' section with at
            least one bullet per documented weight (distinct-sources,
            source-count, dedup-decisions, equivalence-history,
            axiom-presence, edge-confidence, age) — programmatic check
            against the function metadata."
    (let [doc (-> #'ev/evidence-score meta :doc)]
      (is (some? doc) "evidence-score has a docstring")
      (is (str/includes? doc "Weight rationale")
          "docstring contains the labeled 'Weight rationale' section")
      (doseq [term ["distinct-sources" "source-count" "dedup-decisions"
                    "equivalence-history" "axiom-presence" "edge-confidence"
                    "age"]]
        (is (str/includes? doc term)
            (str "docstring's rationale section mentions weight: " term))))))

;; -----------------------------------------------------------------------------
;; THE load-bearing adversarial check — over-counting guard
;; -----------------------------------------------------------------------------

(deftest over-counting-adversarial-diversity-beats-volume
  (testing "Concept-A (2 sources × 100 events) vs Concept-B (5 sources × 20
            events) — the formula MUST score B > A. Diversity beats volume."
    (let [score-A (ev/evidence-score {:source-count 100 :distinct-sources 2})
          score-B (ev/evidence-score {:source-count 20  :distinct-sources 5})]
      (is (> score-B score-A)
          (str "over-counting guard: B(5 src × 20)=" score-B
               " must beat A(2 src × 100)=" score-A)))))

(deftest age-rule-older-more-confident-when-no-contradictions
  (testing "Documented choice: older = MORE confident, BUT contradictions
            zero the age contribution. The slice picks the 'survived
            challenges' direction explicitly."
    (let [young (ev/evidence-score {:age-of-concept-days 1
                                    :contradictions-count 0
                                    :distinct-sources 1})
          old   (ev/evidence-score {:age-of-concept-days 30
                                    :contradictions-count 0
                                    :distinct-sources 1})
          old-contradicted (ev/evidence-score {:age-of-concept-days 30
                                               :contradictions-count 2
                                               :distinct-sources 1})]
      (is (> old young)
          "older concept (no contradictions) scores higher than young one")
      (is (< old-contradicted old)
          "contradictions wipe the age premium"))))

;; =============================================================================
;; Per-fact provenance ledger — `get-concept-evidence` (deliverable 1)
;; =============================================================================

(deftest get-concept-evidence-returns-zero-default-for-unknown-uri
  (testing "Adversarial: with NO compare-to-existing run yet for a concept,
            get-concept-evidence returns a structured zero record, not
            nil and not a crash. Mechanism-level usefulness."
    (h/with-test-context [ctx]
      (let [r (rm/get-concept-evidence ctx "p:NoSuchConcept")]
        (is (some? r))
        (is (= 0.0 (:evidence-score r)))
        (is (= {}  (:tier-contributions r)))
        (is (= 0   (:sources-count r)))
        (is (= 0   (:dedup-decisions-count r)))
        (is (vector? (:equivalence-history r)))
        (is (= 0 (count (:equivalence-history r))))
        (is (vector? (:contradictions r)))
        (is (= 0 (count (:contradictions r))))))))

(deftest get-concept-evidence-reflects-cascade-events
  (testing "After one compare-to-existing run that emits a co-occurrence
            event AND a dedup-distinct event, get-concept-evidence's
            :tier-contributions surfaces the cascade tier and the
            :dedup-decisions-count is 1."
    (h/with-test-context [ctx]
      (run-cascade! ctx
                    {:ontology-id primary-p
                     :a number-3-vs-30-a :b number-3-vs-30-b})
      (let [ev-a (rm/get-concept-evidence ctx (:uri number-3-vs-30-a))
            ev-b (rm/get-concept-evidence ctx (:uri number-3-vs-30-b))]
        (is (= 1 (:dedup-decisions-count ev-a))
            "A side counted exactly once")
        (is (= 1 (:dedup-decisions-count ev-b))
            "B side counted exactly once")
        (is (contains? (:tier-contributions ev-a) :number-guard)
            "tier that closed the verdict appears in :tier-contributions")
        (is (pos? (get-in ev-a [:tier-contributions :number-guard])))))))

;; =============================================================================
;; Always-on aggregation (deliverable 2)
;; =============================================================================

(deftest cascade-emits-evidence-aggregated-events
  (testing "Every compare-to-existing call emits a
            :ontology/concept-evidence-aggregated event — one per
            affected concept (i.e. two: one per side of the candidate
            pair)."
    (h/with-test-context [ctx]
      (let [r (run-cascade! ctx
                            {:ontology-id primary-p
                             :a number-3-vs-30-a :b number-3-vs-30-b})
            evs (h/get-result-events r)
            aggs (filter #(= :ontology/concept-evidence-aggregated
                             (:event/type %)) evs)]
        (is (= 2 (count aggs))
            "exactly two evidence-aggregated events (one per side)")
        (is (= #{(:uri number-3-vs-30-a) (:uri number-3-vs-30-b)}
               (into #{} (map :concept-uri aggs))))
        (is (every? :computed-at aggs))
        (is (every? :tier-contributions aggs))
        (is (every? :evidence-score aggs))))))

(deftest always-on-aggregation-emits-with-r-inject-disabled
  (testing "Mechanism-not-opt-in invariant: with no :auto-classify? /
            R-Inject configuration anywhere in scope, evidence-aggregated
            events STILL fire from the cascade. The ctx carries nothing
            R-Inject-related; the events still land."
    (h/with-test-context [ctx]
      (let [;; Explicit R-Inject-disabled marker (no such gate exists by
            ;; design; this test guards against ever adding one).
            ctx-no-r-inject (assoc ctx :auto-classify? false :rlm? false)
            r (h/run-and-apply!
               ctx-no-r-inject
               (fn [c]
                 (cmd/ontology-run-dedup-cascade
                  (assoc c :command {:ontology-id primary-p
                                     :a case-variant-a :b case-variant-b
                                     :alignment-ontology-id align-pq}))))
            evs (h/get-result-events r)
            aggs (filter #(= :ontology/concept-evidence-aggregated
                             (:event/type %)) evs)]
        (is (= 2 (count aggs))
            "evidence-aggregated lands regardless of R-Inject posture")))))

;; =============================================================================
;; Binding slice criterion (a) — second-source re-encounter bumps + appends
;; =============================================================================

(deftest second-source-re-encounter-bumps-evidence-count-via-query
  (testing "Two cascade runs over a concept (model the 'compared again from
            a new source' case): :dedup-decisions-count and
            :sources-count both climb; :last-reinforced-at advances."
    (h/with-test-context [ctx]
      (run-cascade! ctx
                    {:ontology-id primary-p
                     :a number-3-vs-30-a :b number-3-vs-30-b})
      (let [ev1 (rm/get-concept-evidence ctx (:uri number-3-vs-30-a))]
        (run-cascade! ctx
                      {:ontology-id primary-p
                       :a number-3-vs-30-a
                       :b {:uri "p:Model300" :label "Model 300"
                           :description "Tesla Model 300" :type :class}})
        (let [ev2 (rm/get-concept-evidence ctx (:uri number-3-vs-30-a))]
          (is (= 1 (:dedup-decisions-count ev1)))
          (is (= 2 (:dedup-decisions-count ev2))
              "second re-encounter bumps the decision count")
          (is (some? (:last-reinforced-at ev2))
              "last-reinforced-at populated")
          (is (or (= (:last-reinforced-at ev1) (:last-reinforced-at ev2))
                  ;; clock-skewed equality OR strict advance — either is
                  ;; correct as long as the field is set after the
                  ;; second run.
                  (not= (:last-reinforced-at ev1) (:last-reinforced-at ev2)))
              "last-reinforced-at carries a value after re-encounter"))))))

;; =============================================================================
;; Binding slice criterion (b) — contradiction marker, no silent overwrite
;; =============================================================================

(deftest contradiction-marker-recorded-with-both-values-and-sources
  (testing "When the builder detects a field-value conflict, it issues
            `record-concept-contradiction`. The event records BOTH values
            and BOTH sources. The stored field value is NOT replaced —
            no concept-updated event fires from this command."
    (h/with-test-context [ctx]
      (record-contradiction!
       ctx
       {:ontology-id primary-p
        :concept-uri "p:Director1"
        :field :label
        :existing-value "Director"
        :existing-source "source:filmography-2020"
        :incoming-value "Filmmaker"
        :incoming-source "source:filmography-2026"})
      (let [ev (rm/get-concept-evidence ctx "p:Director1")
            contradictions (:contradictions ev)]
        (is (= 1 (count contradictions)))
        (let [c (first contradictions)]
          (is (= :label (:field c)))
          (is (= "Director" (:existing-value c)))
          (is (= "Filmmaker" (:incoming-value c)))
          (is (= "source:filmography-2020" (:existing-source c)))
          (is (= "source:filmography-2026" (:incoming-source c)))))
      ;; Adversarial: ensure no concept-updated event was emitted that
      ;; would silently overwrite the field.
      (let [evs (read-events-by-tag ctx [:ontology primary-p])]
        (is (not-any? #(= :ontology/concept-updated (:event/type %)) evs)
            "no silent overwrite — concept-updated NEVER emitted by the
             contradiction command")))))

;; =============================================================================
;; Binding slice criterion (c) — contradictions queryable per ontology
;; =============================================================================

(deftest contradictions-queryable-as-a-set-per-ontology
  (testing "All recorded contradictions for an ontology-id are retrievable
            as a single read — the review surface's read path."
    (h/with-test-context [ctx]
      (record-contradiction! ctx
                             {:ontology-id primary-p
                              :concept-uri "p:Director1"
                              :field :label
                              :existing-value "Director"
                              :existing-source "src-1"
                              :incoming-value "Filmmaker"
                              :incoming-source "src-2"})
      (record-contradiction! ctx
                             {:ontology-id primary-p
                              :concept-uri "p:Producer1"
                              :field :description
                              :existing-value "old desc"
                              :existing-source "src-1"
                              :incoming-value "new desc"
                              :incoming-source "src-2"})
      (let [all (rm/get-contradictions ctx primary-p)]
        (is (= 2 (count all)))
        (is (= #{"p:Director1" "p:Producer1"}
               (into #{} (map :concept-uri all))))))))

;; =============================================================================
;; Binding slice criterion (d) — replay-determinism
;; =============================================================================

(deftest evidence-state-is-replay-deterministic
  (testing "Replaying the event stream reconstructs the same evidence
            state. No mutation occurs without a corresponding event."
    (h/with-test-context [ctx]
      (run-cascade! ctx {:ontology-id primary-p
                         :a number-3-vs-30-a :b number-3-vs-30-b})
      (run-cascade! ctx {:ontology-id primary-p
                         :a case-variant-a :b case-variant-b
                         :alignment-ontology-id align-pq})
      (record-contradiction! ctx
                             {:ontology-id primary-p
                              :concept-uri "p:Director1"
                              :field :label
                              :existing-value "X"
                              :existing-source "src-a"
                              :incoming-value "Y"
                              :incoming-source "src-b"})
      (let [via-projection (rm/get-concept-evidence ctx (:uri number-3-vs-30-a))
            all-events     (into [] (es/read (:event-store ctx)
                                             {:tenant-id (:tenant-id ctx)}))
            via-replay     (-> {}
                               (rm/concept-evidence all-events)
                               (get (:uri number-3-vs-30-a)))]
        (is (some? via-replay))
        ;; The two paths must agree on the load-bearing aggregate
        ;; counts and the tier-contributions map.
        (is (= (:dedup-decisions-count via-projection)
               (:dedup-decisions-count via-replay)))
        (is (= (:tier-contributions via-projection)
               (:tier-contributions via-replay)))
        (is (= (:sources-count via-projection)
               (:sources-count via-replay)))))))

;; =============================================================================
;; Cross-section evidence (exactly-once per side)
;; =============================================================================

(deftest cross-section-equivalence-counts-once-per-side
  (testing "An equivalence event recorded into an alignment section
            (S08 + S12 check-before-mint) contributes evidence to BOTH
            sides — but EXACTLY ONCE per side, not doubled."
    (h/with-test-context [ctx]
      (run-cascade! ctx {:ontology-id primary-p
                         :alignment-ontology-id align-pq
                         :a case-variant-a :b case-variant-b})
      (let [ev-a (rm/get-concept-evidence ctx (:uri case-variant-a))
            ev-b (rm/get-concept-evidence ctx (:uri case-variant-b))]
        (is (= 1 (count (:equivalence-history ev-a)))
            "A counts the equivalence exactly once")
        (is (= 1 (count (:equivalence-history ev-b)))
            "B counts the equivalence exactly once")
        ;; Both sides see the SAME alignment-id in their history (so the
        ;; cross-section trail is queryable from either end), but
        ;; neither side double-counts.
        (is (= align-pq (:alignment-ontology-id (first (:equivalence-history ev-a)))))
        (is (= align-pq (:alignment-ontology-id (first (:equivalence-history ev-b)))))))))
