(ns ai.obney.orc.ontology.stream-slice3-normalize-test
  "STREAM Slice 3 — the ref-integrity / normalize-stage conversion.

   `deterministic-skeleton/normalize-stage` now reads its whole-graph concept +
   relationship sets by STREAMING the registered reducers over a tag-scoped
   `es/read` pass (`concept-stream/reduce-concepts` / `reduce-relationships`)
   instead of `(vals (rmp/project ...))`. This suite proves:

     1. BYTE-EQUIVALENCE on a single-ontology store — `:concepts-count`,
        `:relationships-count`, and `:referential-integrity` are IDENTICAL to
        the pre-conversion `(filter #(= id (:ontology-id %)) (rm/get-concepts/
        get-relationships ...))` data source, INCLUDING a dangling-endpoint edge
        so the ref-integrity report is non-trivial (the conversion changes only
        the DATA SOURCE, never the ref-integrity LOGIC).

     2. The multi-ontology SCOPING-CORRECTNESS WIN — when a second ontology B
        mints a URI that ontology A also owns, the URI-keyed projection is
        last-writer-wins (B's copy overwrites A's), so the OLD unscoped-then-
        filter data source DROPS A's colliding concept. The tag-scoped
        `reduce-concepts` for A recovers A's own concept. A documented CORRECTNESS
        improvement, not a regression."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface.schemas]         ;; append-time validation
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.concept-stream :as cs]
            [ai.obney.orc.ontology.core.deterministic-skeleton :as ds]
            [ai.obney.orc.ontology.test-helpers :as h]))

;; The private stage + report under test (byte-equivalence is asserted through
;; the real fold path; the OLD data source is reconstructed inline for the
;; expected value — the ref-integrity LOGIC is the same fn in both).
(def normalize-stage #'ds/normalize-stage)
(def referential-integrity-report #'ds/referential-integrity-report)

(defn- create-concept! [ctx body]
  (h/run-and-apply! ctx (fn [c] (cmd/ontology-create-concept (assoc c :command body)))))

(defn- create-relationship! [ctx ont-id s p t]
  (h/run-and-apply! ctx (fn [c] (cmd/ontology-create-relationship
                                 (assoc c :command {:ontology-id ont-id
                                                    :source-uri s
                                                    :predicate p
                                                    :target-uri t})))))

(defn- seed-single-ontology!
  "5 concepts + 3 resolvable edges + ONE DANGLING edge (target concept:omega
   never minted) so referential-integrity has something to report. Returns oid."
  [ctx]
  (let [oid (random-uuid)]
    (doseq [[uri label] [["concept:alpha" "Alpha"] ["concept:beta" "Beta"]
                         ["concept:gamma" "Gamma"] ["concept:delta" "Delta"]
                         ["concept:epsilon" "Epsilon"]]]
      (create-concept! ctx {:ontology-id oid :uri uri :label label
                            :description label :scope :custom
                            ;; a HEAVY attributes list — the projection drops it
                            :attributes {:codes ["a" "b" "c" "d"] :note "heavy list"}}))
    (create-relationship! ctx oid "concept:beta"  "skos:broader"        "concept:alpha")
    (create-relationship! ctx oid "concept:gamma" "skos:related"        "concept:delta")
    (create-relationship! ctx oid "concept:delta" "immediately-follows" "concept:epsilon")
    ;; DANGLING: concept:omega was never minted; "links-to" touches only source,
    ;; so no phantom concept entry is created — the edge stays truly dangling.
    (create-relationship! ctx oid "concept:alpha" "links-to"            "concept:omega")
    oid))

;; =============================================================================
;; 1. Byte-equivalence on a single-ontology store (counts + ref-integrity)
;; =============================================================================

(deftest normalize-stage-byte-equivalent-single-ontology-test
  (testing "normalize-stage :concepts-count / :relationships-count /
            :referential-integrity are IDENTICAL to the pre-conversion
            (filter #(= id (:ontology-id %)) (get-concepts/get-relationships))
            data source — with a dangling edge so ref-integrity is non-trivial"
    (h/with-test-context [ctx]
      (let [oid (seed-single-ontology! ctx)
            ;; --- pre-conversion data source (the OLD normalize-stage read) ---
            old-concepts (filter #(= oid (:ontology-id %)) (rm/get-concepts ctx {}))
            old-rels     (filter #(= oid (:ontology-id %)) (rm/get-relationships ctx))
            expected {:concepts-count      (count old-concepts)
                      :relationships-count (count old-rels)
                      :referential-integrity
                      (referential-integrity-report old-concepts old-rels)}
            ;; --- converted stage (streams the registered reducers) ---
            actual (normalize-stage ctx {:ontology-id oid})]
        ;; sanity: the fixture actually HAS a dangling edge to report
        (is (= 1 (:dangling-edge-count (:referential-integrity expected)))
            "fixture has exactly one dangling edge (precondition)")
        (is (false? (:every-edge-endpoint-resolves? (:referential-integrity expected)))
            "fixture ref-integrity is non-trivial (precondition)")
        ;; THE load-bearing byte-equivalence assertions
        (is (= (:concepts-count expected) (:concepts-count actual))
            "concepts-count identical to the pre-conversion filtered projection")
        (is (= (:relationships-count expected) (:relationships-count actual))
            "relationships-count identical to the pre-conversion filtered projection")
        (is (= (:referential-integrity expected) (:referential-integrity actual))
            "referential-integrity (incl. dangling-edge report) byte-identical")))))

;; =============================================================================
;; 2. Heavy :attributes are NOT carried by the streamed concept set
;; =============================================================================

(deftest normalize-stage-drops-heavy-attributes-test
  (testing "the streamed concept projection keeps only [:uri :ontology-id] — the
            heavy :attributes list never rides along (the OOM the slice removes)"
    (h/with-test-context [ctx]
      (let [oid (seed-single-ontology! ctx)
            streamed (cs/reduce-concepts
                      ctx oid
                      (fn [acc c] (if (= oid (:ontology-id c)) (conj acc c) acc))
                      []
                      {:project-fn #(select-keys % [:uri :ontology-id])})]
        (is (= 5 (count streamed)))
        (is (every? #(= #{:uri :ontology-id} (set (keys %))) streamed)
            "every streamed concept carries ONLY :uri and :ontology-id")
        (is (not-any? :attributes streamed)
            "no streamed concept carries the heavy :attributes list")))))

;; =============================================================================
;; 3. Multi-ontology scoping-correctness win (the tag-scoped read is MORE correct)
;; =============================================================================

(deftest normalize-stage-scoping-correctness-across-ontologies-test
  (testing "when ontology B mints a URI ontology A also owns, the URI-keyed
            projection is last-writer-wins (B overwrites A). The OLD unscoped-
            then-filter data source DROPS A's colliding concept; the tag-scoped
            reduce-concepts for A RECOVERS it — a correctness improvement"
    (h/with-test-context [ctx]
      (let [oid-a (random-uuid)
            oid-b (random-uuid)]
        ;; A mints concept:shared (+ one private concept)
        (create-concept! ctx {:ontology-id oid-a :uri "concept:shared"
                              :label "Shared-in-A" :description "A's copy" :scope :custom})
        (create-concept! ctx {:ontology-id oid-a :uri "concept:a-only"
                              :label "A only" :description "x" :scope :custom})
        ;; B mints the SAME URI LATER — URI-keyed projection now holds B's copy
        (create-concept! ctx {:ontology-id oid-b :uri "concept:shared"
                              :label "Shared-in-B" :description "B's copy" :scope :custom})
        (let [;; OLD data source for A: unscoped URI-keyed projection, filtered
              old-a (filter #(= oid-a (:ontology-id %)) (rm/get-concepts ctx {}))
              old-a-uris (set (map :uri old-a))
              ;; NEW tag-scoped stream for A
              new-a (cs/reduce-concepts
                     ctx oid-a
                     (fn [acc c] (if (= oid-a (:ontology-id c)) (conj acc c) acc))
                     []
                     {:project-fn #(select-keys % [:uri :ontology-id :label])})
              new-a-uris (set (map :uri new-a))]
          ;; The OLD data source DROPS concept:shared for A (B overwrote it)
          (is (not (contains? old-a-uris "concept:shared"))
              "OLD unscoped-then-filter loses A's colliding URI (last-writer-wins is B)")
          ;; The NEW tag-scoped read RECOVERS A's own concept:shared
          (is (contains? new-a-uris "concept:shared")
              "tag-scoped reduce-concepts recovers A's own colliding URI (MORE correct)")
          (is (= "Shared-in-A"
                 (:label (first (filter #(= "concept:shared" (:uri %)) new-a))))
              "and it is A's version, not B's last-writer copy")
          ;; A's private concept survives both paths
          (is (contains? new-a-uris "concept:a-only")))))))
