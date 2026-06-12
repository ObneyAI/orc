(ns ai.obney.orc.ontology.s02-uniform-scoping-test
  "S02 — Uniform ontology-id scoping across all three retrieval signals
   AND the accessor surface.

   This test file is the slice's acceptance test corpus. Each deftest
   corresponds to one acceptance criterion from the slice file:

   1. Adversarial BFS leak — two sections with overlapping URI
      neighborhoods; a single-section query never returns the OTHER
      section's concepts. The 'before-fix' shape of this test is the
      prototype-confirmed leak; the post-fix shape is the assertion of
      no leak through the public interface.

   2. Multi-section widening — :ontology-ids [a b] returns cross-section
      hits WITH fusion ranks intact (adversarial check on the
      silent-burial failure mode where a result is returned but the
      signal's rank field is nil).

   3. Accessor scoping — get-concept-by-uri / get-narrower-concepts /
      get-broader-concepts / concept-statistics / tree-profile finders
      accept and honor :ontology-id / :ontology-ids; unscoped calls
      behave as before within a single-section store (back-compat).

   4. URI-collision — two sections each minting the same URI resolve to
      their OWN section's concept under scoped lookup. (This requires
      a section-keyed projection layer because the URI-keyed projection
      silently overwrites.)

   Live-verification runs against a REAL Grain event store via
   ai.obney.orc.ontology.test-helpers/with-test-context — no synthesized
   fixtures of the projection state."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.set]
            [ai.obney.orc.ontology.test-helpers :as h]
            ;; Required for event-schema registration — without this,
            ;; the event-store's append-time Malli validation rejects
            ;; concept-created / relationship-created with
            ;; "schema not defined" and our writes silently no-op.
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.retrieval :as retrieval]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

;; =============================================================================
;; Two-section corpus seed helpers
;; =============================================================================
;; Section A is the "transcript correction" sketch from the prototype.
;; Section B is the "recommendations" sketch. Both contain ≥8 concepts
;; and a cross-section :related relationship (A's :lexicon → B's
;; :taxonomy), plus a deliberate URI collision (concept:rule exists in
;; both sections with different labels).

(def ontology-a-id #uuid "a0000000-0000-0000-0000-00000000000a")
(def ontology-b-id #uuid "b0000000-0000-0000-0000-00000000000b")

(defn- seed-concept!
  [ctx ontology-id uri label]
  (h/run-and-apply! ctx
                    (fn [c]
                      (cmd/ontology-create-concept
                       (assoc c :command
                              (h/make-concept-data
                               :ontology-id ontology-id
                               :uri uri
                               :label label
                               :description (str label " in " ontology-id)
                               :scope :custom))))))

(defn- seed-relationship!
  [ctx source-uri predicate target-uri]
  (h/run-and-apply! ctx
                    (fn [c]
                      (cmd/ontology-create-relationship
                       (assoc c :command
                              {:source-uri source-uri
                               :target-uri target-uri
                               :predicate predicate
                               :properties {}})))))

(defn- seed-two-sections!
  "Emit real commands → events that build the two-section corpus.
   After this returns, the event-store has 16 concepts (8 per section)
   plus a cross-section :related edge plus a URI-collision pair."
  [ctx]
  ;; Section A — write FIRST so that on the URI-collision case, section
  ;; B's concept:rule is the one that wins in the URI-keyed projection.
  ;; This makes the silent-burial failure mode the natural one to test
  ;; against (probe-confirmed: B's later write overwrites A's earlier).
  (doseq [[uri label] [["concept:lexicon" "Lexicon"]
                       ["concept:term" "Term"]
                       ["concept:rule" "Rule (A: correction rule)"]
                       ["concept:transcript" "Transcript"]
                       ["concept:speaker" "Speaker"]
                       ["concept:correction" "Correction"]
                       ["concept:domain" "Domain (A)"]
                       ["concept:engagement" "Engagement"]]]
    (seed-concept! ctx ontology-a-id uri label))
  ;; Section A internal hierarchy
  (seed-relationship! ctx "concept:term" "skos:broader" "concept:lexicon")
  (seed-relationship! ctx "concept:rule" "skos:broader" "concept:lexicon")
  (seed-relationship! ctx "concept:correction" "skos:related" "concept:term")

  ;; Section B — note B writes concept:rule SECOND so it wins the
  ;; URI-keyed projection. This is the silent-collision shape the
  ;; URI-collision acceptance test must defeat.
  (doseq [[uri label] [["concept:taxonomy" "Taxonomy"]
                       ["concept:program" "Program"]
                       ["concept:rule" "Rule (B: scholarship rule)"]
                       ["concept:school" "School"]
                       ["concept:career" "Career"]
                       ["concept:wage" "Wage"]
                       ["concept:advisor" "Advisor"]
                       ["concept:outcome" "Outcome"]]]
    (seed-concept! ctx ontology-b-id uri label))
  ;; Section B internal hierarchy
  (seed-relationship! ctx "concept:program" "skos:broader" "concept:taxonomy")
  (seed-relationship! ctx "concept:rule" "skos:broader" "concept:taxonomy")

  ;; THE cross-section :related edge — A's :lexicon related to B's
  ;; :taxonomy. Today, this is what makes scoped BFS leak across
  ;; sections.
  (seed-relationship! ctx "concept:lexicon" "skos:related" "concept:taxonomy"))

;; =============================================================================
;; Acceptance test 1 — adversarial BFS leak (scoped never returns
;; the OTHER section's concepts)
;; =============================================================================

(deftest scoped-bfs-does-not-leak-across-sections
  (testing "Single-section BFS query never returns concepts from another section.
            Adversarial twin: also assert the scoped run DOES return the in-section
            concepts (otherwise 'no other section' trivially passes by returning nothing)."
    (h/with-test-context [ctx]
      (seed-two-sections! ctx)

      ;; --- Scoped (the fix path) — BFS scoped to section A only ---
      (let [scoped (retrieval/expand-concept-neighborhood
                    ["concept:lexicon"]
                    :ctx ctx
                    :ontology-id ontology-a-id
                    :max-depth 3
                    :decay 0.5)
            scoped-uris (set (map :uri scoped))
            section-b-uris #{"concept:taxonomy" "concept:program" "concept:school"
                             "concept:career" "concept:wage" "concept:advisor"
                             "concept:outcome"}]
        (is (contains? scoped-uris "concept:lexicon")
            "scoped BFS still reaches its own seed (adversarial: 'no leak' must
             not pass by returning nothing)")
        (is (contains? scoped-uris "concept:term")
            "scoped BFS still traverses section A's internal hierarchy")
        (is (empty? (clojure.set/intersection scoped-uris section-b-uris))
            (str "scoped BFS leaked into section B: "
                 (clojure.set/intersection scoped-uris section-b-uris))))

      ;; --- Unscoped path — preserves today's static-graph behavior ---
      ;; The unscoped path falls back to the static seed corpus
      ;; (failure/success/problem taxonomy). Back-compat: a static-URI
      ;; seed (failure:Root) still expands through the static graph
      ;; just as it did before S02.
      (let [unscoped-static (retrieval/expand-concept-neighborhood
                             ["failure:Root"]
                             :max-depth 2 :decay 0.6)
            uris (set (map :uri unscoped-static))]
        (is (contains? uris "failure:Root")
            "back-compat: unscoped path still expands the static seed corpus")
        (is (some #(re-find #"^failure:" %) uris)
            "back-compat: static failure taxonomy URIs traversed normally")
        ;; Adversarial: the unscoped static path returns NONE of the
        ;; event-store-only concepts (section A/B URIs are inventions of
        ;; this test, not part of static corpus).
        (is (empty? (filter #{"concept:lexicon" "concept:taxonomy"} uris))
            "back-compat: static path doesn't see event-store-only URIs")))))

;; =============================================================================
;; Acceptance test 2 — multi-section widening + fusion ranks intact
;; =============================================================================

(deftest multi-section-widening-returns-cross-section-results-with-fusion-ranks-intact
  (testing "Querying with :ontology-ids [a b] returns cross-section hits AND
            embedding/ColBERT ranks (where applicable) — adversarial check on the
            silent-burial mode where a result is returned but ranks are nil."
    (h/with-test-context [ctx]
      (seed-two-sections! ctx)

      ;; Multi-section BFS via expand-concept-neighborhood
      (let [widened (retrieval/expand-concept-neighborhood
                     ["concept:lexicon"]
                     :ctx ctx
                     :ontology-ids [ontology-a-id ontology-b-id]
                     :max-depth 3
                     :decay 0.5)
            uris (set (map :uri widened))]
        (is (contains? uris "concept:lexicon") "starts at A's seed")
        (is (contains? uris "concept:taxonomy")
            "widened BFS reaches B's :taxonomy via cross-section :related edge")
        (is (contains? uris "concept:program")
            "widened BFS traverses INTO section B's hierarchy"))

      ;; hybrid-search with multi-section scoping — verify the graph
      ;; signal's per-uri rank field is non-nil for graph-derived hits
      ;; (the silent-burial failure mode would set graph-rank to nil
      ;; even when the hit came from the graph signal).
      (let [{:keys [results graph-results batches-used]}
            (retrieval/hybrid-search
             ctx
             {:seed-uris ["concept:lexicon"]
              :query-text nil  ; graph-only — no embedding signal needed
              :ontology-ids [ontology-a-id ontology-b-id]
              :signals #{:graph}
              :limit 20})]
        (is (some #(= "concept:taxonomy" (:uri %)) graph-results)
            "graph signal under multi-section scope DID see B's taxonomy")
        (is (contains? (set batches-used) :graph)
            "graph batch participated in fusion")
        ;; The adversarial bit — verify the fused result for an
        ;; in-section URI is decorated with graph-rank (the fusion is
        ;; preserving the signal lineage, not silently dropping ranks).
        (when-let [hit (first (filter #(= "concept:taxonomy" (:uri %)) results))]
          (is (some? (:graph-rank hit))
              "cross-section hit carries graph-rank — fusion ranks intact"))))))

;; =============================================================================
;; Acceptance test 3 — accessor fns honor scoping; back-compat preserved
;; =============================================================================

(deftest accessors-honor-scoping-params
  (testing "get-concept-by-uri / get-narrower-concepts / get-broader-concepts /
            concept-statistics accept :ontology-id / :ontology-ids and behave
            as before when no scope is passed (back-compat single-section case)."
    (h/with-test-context [ctx]
      (seed-two-sections! ctx)

      ;; --- get-concept-by-uri with scoping ---
      (testing "get-concept-by-uri scoped lookup hits the right section"
        (let [b-rule (rm/get-concept-by-uri ctx "concept:rule" {:ontology-id ontology-b-id})
              a-rule (rm/get-concept-by-uri ctx "concept:rule" {:ontology-id ontology-a-id})]
          (is (= "Rule (B: scholarship rule)" (:label b-rule))
              "scoping to B returns B's concept:rule")
          (is (= "Rule (A: correction rule)" (:label a-rule))
              "scoping to A returns A's concept:rule (URI collision defeated)")))

      ;; --- Back-compat: no scoping returns SOMETHING (today's overwrite winner) ---
      (testing "unscoped get-concept-by-uri returns the projection's URI-keyed winner"
        (let [unscoped (rm/get-concept-by-uri ctx "concept:rule")]
          (is (some? unscoped) "back-compat: unscoped still resolves")
          (is (#{"Rule (A: correction rule)"
                 "Rule (B: scholarship rule)"} (:label unscoped))
              "winner is one of the two — depends on projection order;
               back-compat is preserved as 'last-writer wins' shape")))

      ;; --- get-narrower-concepts with scoping ---
      (testing "scoped narrower returns only same-section URIs"
        (let [a-narrower (rm/get-narrower-concepts ctx "concept:lexicon"
                                                   {:ontology-id ontology-a-id})]
          (is (contains? a-narrower "concept:term") "A's narrower includes :term")
          (is (contains? a-narrower "concept:rule")
              "A's narrower includes A's :rule (URI-collision-defeated)")))

      ;; --- get-broader-concepts with scoping ---
      (testing "scoped broader: A's :rule is broader-than :lexicon"
        (let [a-broader (rm/get-broader-concepts ctx "concept:rule"
                                                 {:ontology-id ontology-a-id})]
          (is (contains? a-broader "concept:lexicon")
              "A's :rule's broader includes :lexicon"))
        (let [b-broader (rm/get-broader-concepts ctx "concept:rule"
                                                 {:ontology-id ontology-b-id})]
          (is (contains? b-broader "concept:taxonomy")
              "B's :rule's broader includes :taxonomy")))

      ;; --- concept-statistics with scoping ---
      (testing "concept-statistics scoped to a single section gives per-section counts"
        (let [a-stats (rm/concept-statistics ctx {:ontology-id ontology-a-id})
              b-stats (rm/concept-statistics ctx {:ontology-id ontology-b-id})
              all-stats (rm/concept-statistics ctx)]
          (is (= 8 (:total-concepts a-stats))
              "section A has its 8 concepts")
          (is (= 8 (:total-concepts b-stats))
              "section B has its 8 concepts")
          ;; Unscoped reflects the URI-keyed projection — 15 (one
          ;; collision means 16 events but only 15 distinct URI keys)
          (is (= 15 (:total-concepts all-stats))
              "back-compat: unscoped count reflects today's URI-keyed projection")))

      ;; --- get-concepts with scoping (already worked silently —
      ;; absorbing Gap-3 by VERIFYING the param is honored) ---
      (testing "get-concepts respects :ontology-id (already worked; documenting it)"
        (let [a-concepts (rm/get-concepts ctx {:ontology-id ontology-a-id})
              b-concepts (rm/get-concepts ctx {:ontology-id ontology-b-id})]
          (is (= 8 (count a-concepts)) "scoped to A")
          (is (= 8 (count b-concepts)) "scoped to B")
          (is (= #{"Rule (A: correction rule)"}
                 (->> a-concepts (filter #(= "concept:rule" (:uri %))) (map :label) set))
              "A's :rule is findable under scoped get-concepts despite URI collision"))))))

;; =============================================================================
;; Acceptance test 4 — URI-collision: scoped lookup returns OWN section
;; =============================================================================

(deftest uri-collision-scoped-lookup-returns-own-sections-concept
  (testing "Two sections each minting concept:rule with different labels —
            scoped lookup returns each section's OWN concept (the silent
            collision in the URI-keyed projection is defeated by the
            section-keyed parallel projection)."
    (h/with-test-context [ctx]
      (seed-two-sections! ctx)
      (let [a-rule (rm/get-concept-by-uri ctx "concept:rule" {:ontology-id ontology-a-id})
            b-rule (rm/get-concept-by-uri ctx "concept:rule" {:ontology-id ontology-b-id})]
        (is (not= (:label a-rule) (:label b-rule))
            "the two :rules are distinct under scoped lookup")
        (is (= ontology-a-id (:ontology-id a-rule)))
        (is (= ontology-b-id (:ontology-id b-rule)))
        (is (re-find #"^Rule \(A" (:label a-rule))
            "A's :rule has A's label")
        (is (re-find #"^Rule \(B" (:label b-rule))
            "B's :rule has B's label")))))

;; =============================================================================
;; Acceptance test 5 — adversarial leak proof: same test as #1, articulated
;; as a verbatim before/after of the leak
;; =============================================================================

(deftest adversarial-leak-twin-bidirectional
  (testing "Mirror of test 1 — adversarial twin from B's perspective. BFS seeded
            in B never sees section A's concepts under scoped lookup."
    (h/with-test-context [ctx]
      (seed-two-sections! ctx)
      (let [scoped-b (retrieval/expand-concept-neighborhood
                      ["concept:taxonomy"]
                      :ctx ctx
                      :ontology-id ontology-b-id
                      :max-depth 3 :decay 0.5)
            b-uris (set (map :uri scoped-b))
            section-a-uris #{"concept:lexicon" "concept:term" "concept:transcript"
                             "concept:speaker" "concept:correction"
                             "concept:domain" "concept:engagement"}]
        (is (contains? b-uris "concept:taxonomy") "B's seed reached")
        (is (contains? b-uris "concept:program") "B's hierarchy traversed")
        (is (empty? (clojure.set/intersection b-uris section-a-uris))
            (str "scoped BFS leaked into section A from B: "
                 (clojure.set/intersection b-uris section-a-uris)))))))
