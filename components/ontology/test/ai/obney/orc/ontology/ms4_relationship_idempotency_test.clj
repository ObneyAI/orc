(ns ai.obney.orc.ontology.ms4-relationship-idempotency-test
  "MS-4 — relationship landing idempotency.

   Root cause (measured live on the INC-1 accretion store, 2026-07):
   31,300 relationship records for only 21,420 distinct
   [source-uri predicate target-uri] triples — a 1.46x inflation from a
   reconcile retry. Concepts are retry-safe (GC-1 canonical URIs land into a
   URI-keyed projection; MT-4b unions with existing); relationships were
   keyed by a per-landing :relationship-id UUID, so every retry APPENDED a
   fresh record for the same edge.

   The fix is event-sourced — at the PROJECTION, never a store rewrite:
   the :ontology/relationships read model keys by the relationship's
   IDENTITY [ontology-id source-uri predicate target-uri]; a re-landed
   duplicate MERGES (present fields last-wins; :evidence and :properties
   union) exactly like the concepts* URI-keyed precedent. Historical
   duplicates in an existing store VANISH at projection.

   Verified through public interfaces only: the ontology-create-relationship
   command, rm/get-relationships / rm/get-relationship, and
   compile-discovery-source! (the landing site). One raw es/append is used to
   construct HISTORICAL duplicate events (distinct relationship-ids, same
   triple) — the only way to reproduce the live store's shape, mirroring the
   s06 legacy-event precedent."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.test-helpers :as h]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es :refer [->event]]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

(def ontology-id #uuid "0054e000-0000-0000-0000-000000000001")

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

(defn- seed-pair! [ctx a-uri b-uri]
  (create-concept! ctx {:ontology-id ontology-id :uri a-uri :label "A"
                        :description "Concept A" :scope :custom})
  (create-concept! ctx {:ontology-id ontology-id :uri b-uri :label "B"
                        :description "Concept B" :scope :custom}))

;; =============================================================================
;; Cycle 1 — re-landing the SAME triple merges (never appends)
;; =============================================================================

(deftest re-landed-relationship-projects-once-with-unioned-metadata
  (testing "Landing the same [source predicate target] twice (the retry shape —
            each landing mints a fresh :relationship-id) projects exactly ONE
            relationship record; the second landing's evidence/properties UNION
            in (the concepts* precedent), never a duplicate record."
    (h/with-test-context [ctx]
      (seed-pair! ctx "ex:A" "ex:B")
      (create-relationship!
       ctx {:ontology-id ontology-id
            :source-uri "ex:A" :target-uri "ex:B" :predicate "owl:causes"
            :confidence-class :extracted
            :evidence [{:source "doc:1" :quote "first landing"}]
            :properties {:first "landing"}})
      ;; the retry — same triple, new relationship-id, new metadata
      (create-relationship!
       ctx {:ontology-id ontology-id
            :source-uri "ex:A" :target-uri "ex:B" :predicate "owl:causes"
            :confidence-class :extracted
            :evidence [{:source "doc:2" :quote "second landing"}]
            :properties {:second "landing"}})
      (let [rels (filterv #(and (= "ex:A" (:source-uri %))
                                (= "ex:B" (:target-uri %))
                                (= "owl:causes" (:predicate %)))
                          (rm/get-relationships ctx))]
        (is (= 1 (count rels))
            "the identity triple projects exactly ONCE (retry merges, never appends)")
        (let [rel (first rels)]
          (is (= #{{:source "doc:1" :quote "first landing"}
                   {:source "doc:2" :quote "second landing"}}
                 (set (:evidence rel)))
              "evidence from BOTH landings unions in (nothing dropped)")
          (is (= {:first "landing" :second "landing"} (:properties rel))
              "properties from both landings merge")
          (is (= ontology-id (:ontology-id rel)))
          (is (some? (:relationship-id rel))
              "the record still carries a relationship-id"))))))

(deftest distinct-triples-stay-distinct
  (testing "Identity-keying collapses ONLY identical triples — a different
            predicate or endpoint between the same concepts stays a distinct
            record (the projection must not over-merge)."
    (h/with-test-context [ctx]
      (seed-pair! ctx "ex:A" "ex:B")
      (create-relationship!
       ctx {:ontology-id ontology-id
            :source-uri "ex:A" :target-uri "ex:B" :predicate "owl:causes"})
      (create-relationship!
       ctx {:ontology-id ontology-id
            :source-uri "ex:A" :target-uri "ex:B" :predicate "skos:related"})
      (create-relationship!
       ctx {:ontology-id ontology-id
            :source-uri "ex:B" :target-uri "ex:A" :predicate "owl:causes"})
      (is (= 3 (count (rm/get-relationships ctx)))
          "three DIFFERENT identities → three records"))))

;; =============================================================================
;; Cycle 2 — historical duplicates (the live store's shape) cured at projection
;; =============================================================================

(deftest historical-duplicate-events-project-to-distinct-triples
  (testing "A store that ALREADY holds duplicate relationship-created events
            (distinct relationship-ids, same triple — the live accretion
            store's 31,300-vs-21,420 shape) projects to DISTINCT triples.
            The cure is at the projection — no store rewrite."
    (h/with-test-context [ctx]
      (seed-pair! ctx "ex:X" "ex:Y")
      ;; append the duplicates DIRECTLY (mirrors the s06 raw-event precedent) —
      ;; exactly what a pre-fix retry left in the live store.
      (let [dup-event (fn []
                        (->event
                         {:type :ontology/relationship-created
                          :tags #{[:ontology ontology-id]}
                          :body {:relationship-id (random-uuid)
                                 :ontology-id ontology-id
                                 :source-uri "ex:X"
                                 :target-uri "ex:Y"
                                 :predicate "crosswalk:maps-to"
                                 :created-at "2026-07-11T00:00:00Z"}}))]
        (es/append (:event-store ctx)
                   {:events [(dup-event) (dup-event) (dup-event)]
                    :tenant-id (:tenant-id ctx)})
        (rmp/l1-clear!))
      (let [rels (filterv #(= "crosswalk:maps-to" (:predicate %))
                          (rm/get-relationships ctx))]
        (is (= 1 (count rels))
            "3 historical duplicate events → ONE projected record")))))

(deftest legacy-no-ontology-id-duplicates-also-merge
  (testing "Legacy relationship-created events WITHOUT :ontology-id (the
            pre-S06 shape) with the same triple ALSO merge — the identity is
            [nil source predicate target] for both, so the projection stays
            duplicate-free across the back-compat path too."
    (h/with-test-context [ctx]
      (seed-pair! ctx "ex:L1" "ex:L2")
      (let [legacy (fn []
                     (->event
                      {:type :ontology/relationship-created
                       :tags #{[:relationship (random-uuid)]}
                       :body {:relationship-id (random-uuid)
                              :source-uri "ex:L1"
                              :target-uri "ex:L2"
                              :predicate "skos:related"
                              :created-at "2024-01-01T00:00:00Z"}}))]
        (es/append (:event-store ctx)
                   {:events [(legacy) (legacy)]
                    :tenant-id (:tenant-id ctx)})
        (rmp/l1-clear!))
      (is (= 1 (count (filterv #(= "skos:related" (:predicate %))
                               (rm/get-relationships ctx))))
          "legacy same-triple duplicates merge too"))))

;; =============================================================================
;; Cycle 3 — landing-site store hygiene: a re-landed identical triple is
;; SKIPPED at emit (per-batch pre-existing-triples set, the concepts'
;; existing-by-uri precedent — never an O(n) projection read per draft)
;; =============================================================================

(def ^:private discovery-output
  {:status :emitted-drafts
   :emitted-concepts
   [{:uri "ex:Program" :label "Program" :description "A program." :scope :custom}
    {:uri "ex:Occupation" :label "Occupation" :description "An occupation." :scope :custom}]
   :emitted-relationships
   [{:source-uri "ex:Program" :target-uri "ex:Occupation"
     :predicate "crosswalk:maps-to" :confidence-class :extracted
     :evidence [{:source "xwalk" :quote "maps to"}]}]
   :emitted-axioms []
   :rlm-trace []})

(deftest re-landed-batch-skips-pre-existing-triples-at-emit
  (testing "compile-discovery-source! (the landing site) SKIPS emitting a
            relationship whose identical identity triple already exists in the
            projection — the retry writes NO duplicate event into the store
            (hygiene on top of the projection cure). The skip is surfaced in
            the provenance (:relationships-skipped-existing), never silent."
    (h/with-test-context [ctx*]
      (let [ctx (assoc ctx* :command-registry (cp/global-command-registry))
            oid (random-uuid)
            first-stub (ontology/compile-discovery-source! ctx oid discovery-output)
            ;; the retry — the exact same drafts land again
            second-stub (ontology/compile-discovery-source! ctx oid discovery-output)]
        (is (= 1 (get-in first-stub [:discovery-provenance :relationships-emitted]))
            "first landing emits the edge")
        (is (= 0 (get-in first-stub [:discovery-provenance :relationships-skipped-existing] 0))
            "nothing to skip on a fresh ontology")
        (is (= 0 (get-in second-stub [:discovery-provenance :relationships-emitted]))
            "the retry emits NO duplicate relationship event")
        (is (= 1 (get-in second-stub [:discovery-provenance :relationships-skipped-existing]))
            "the skip is SURFACED in the provenance (honest, not silent)")
        (is (= 1 (count (filterv #(= oid (:ontology-id %))
                                 (rm/get-relationships ctx))))
            "the projection holds exactly one record for the triple")))))

(deftest new-triples-still-land-alongside-skipped-ones
  (testing "The skip is per-IDENTICAL-triple only: a batch mixing an existing
            triple with a NEW one emits the new edge (cross-source enrichment
            keeps landing)."
    (h/with-test-context [ctx*]
      (let [ctx (assoc ctx* :command-registry (cp/global-command-registry))
            oid (random-uuid)
            _ (ontology/compile-discovery-source! ctx oid discovery-output)
            enriched (update discovery-output :emitted-relationships conj
                             {:source-uri "ex:Occupation" :target-uri "ex:Program"
                              :predicate "feeds-into" :confidence-class :extracted})
            stub (ontology/compile-discovery-source! ctx oid enriched)]
        (is (= 1 (get-in stub [:discovery-provenance :relationships-emitted]))
            "the NEW triple lands")
        (is (= 1 (get-in stub [:discovery-provenance :relationships-skipped-existing]))
            "the pre-existing triple is skipped")
        (is (= 2 (count (filterv #(= oid (:ontology-id %))
                                 (rm/get-relationships ctx))))
            "projection: the original edge + the new edge")))))

;; =============================================================================
;; get-relationship (by relationship-id) contract survives the re-keying
;; =============================================================================

(deftest get-relationship-by-id-still-resolves
  (testing "get-relationship's public contract (lookup by relationship-id)
            survives the identity re-keying — the s06 consumers hand it the
            id from the created event."
    (h/with-test-context [ctx]
      (seed-pair! ctx "ex:A" "ex:B")
      (let [rel-id (-> (create-relationship!
                        ctx {:ontology-id ontology-id
                             :source-uri "ex:A" :target-uri "ex:B"
                             :predicate "owl:causes"})
                       :command-result/events first :relationship-id)
            rel (rm/get-relationship ctx rel-id)]
        (is (some? rel) "the edge resolves by its relationship-id")
        (is (= "ex:A" (:source-uri rel)))
        (is (= "owl:causes" (:predicate rel)))))))
