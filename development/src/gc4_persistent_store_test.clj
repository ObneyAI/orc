(ns gc4-persistent-store-test
  "GC-4 — the persistent (SQLite-v3) event-store swap in the EB12 graph-B driver.

   What GC-4 changed: `eb12-graph-b-central-evolver/make-ctx` got a `:store` knob
   (`:sqlite` DEFAULT — a persistent SQLite-v3 file; `:in-memory` still available
   for tiny smokes), and `stop-ctx` now deletes the db-file + WAL/SHM sidecars.

   These tests guard the swap is BEHAVIOR-PRESERVING + has the right lifecycle,
   WITHOUT any LLM (so a failure can NEVER be excused as model variance — Disc 1):

   1. STORE PARITY (the decisive guard, Discipline 7) — land an IDENTICAL fixed
      draft set through the REAL Grain command/event/projection path into BOTH a
      `:sqlite` ctx and an `:in-memory` ctx, then READ BACK the projections
      (concepts + relationships counts + a sorted URI sample). The two stores MUST
      agree. Asserted via the projection read, NOT a return value. A BROKEN sqlite
      store (events not landing) makes the sqlite read-back EMPTY → the parity
      assert is RED. This is the guard that the swap didn't drop events.

   2. SQLITE LIFECYCLE — the db-file is threaded on the ctx as `::db-file`, EXISTS
      mid-run (after events land), and is GONE after `stop-ctx`. An in-memory ctx
      carries NO `::db-file`.

   Run:
     clj -M:dev:test -e \"(require 'gc4-persistent-store-test 'clojure.test) \\
       (let [r (clojure.test/run-tests 'gc4-persistent-store-test)] \\
         (println :SUMMARY r) (System/exit (+ (:fail r) (:error r))))\""
  (:require [clojure.test :refer [deftest testing is]]
            [eb12-graph-b-central-evolver :as b]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.read-models :as rm]))

;; The private driver fns are the unit under test — reference them via their vars
;; (GC-4 must NOT widen the driver's public surface just to test it).
(def make-ctx @#'b/make-ctx)
(def stop-ctx @#'b/stop-ctx)

;; ---------------------------------------------------------------------------
;; A FIXED, deterministic draft set (NO LLM). Mirrors the real cross-source
;; shape the builder produces: programs → fields, an institution, and an
;; earnings-bearing concept with numeric attributes. Identical for both stores.
;; ---------------------------------------------------------------------------

(def fixture-concepts
  [{:uri "programofstudy/01.0901" :label "Animal Sciences"
    :description "A program of study." :scope :custom :confidence-class :extracted}
   {:uri "programofstudy/42.0101" :label "Psychology, General"
    :description "A program of study." :scope :custom :confidence-class :extracted}
   {:uri "fieldofstudy/01" :label "Agriculture"
    :description "A field." :scope :custom :confidence-class :extracted}
   {:uri "fieldofstudy/42" :label "Psychology"
    :description "A field." :scope :custom :confidence-class :extracted}
   {:uri "institution/236753" :label "Louisiana State University"
    :description "An institution." :scope :custom :confidence-class :extracted}
   {:uri "occupation/19-3039" :label "Psychologists, All Other"
    :description "An occupation."
    :attributes {:median-wage 98230 :earnings-y5 105000} :scope :custom
    :confidence-class :extracted}])

(def fixture-relationships
  [{:source-uri "programofstudy/01.0901" :target-uri "fieldofstudy/01"
    :predicate "belongs-to" :confidence-class :extracted}
   {:source-uri "programofstudy/42.0101" :target-uri "fieldofstudy/42"
    :predicate "belongs-to" :confidence-class :extracted}
   {:source-uri "fieldofstudy/42" :target-uri "occupation/19-3039"
    :predicate "leads-to" :confidence-class :extracted}
   {:source-uri "institution/236753" :target-uri "programofstudy/42.0101"
    :predicate "offers" :confidence-class :extracted}])

(defn- land-fixture!
  "Land the fixed draft set through the REAL `compile-discovery-source!` (the
   same command/event path the builder uses) into the ctx's event store."
  [ctx oid]
  (ontology/compile-discovery-source!
   ctx oid
   {:status :emitted-drafts
    :emitted-concepts fixture-concepts
    :emitted-relationships fixture-relationships}))

(defn- read-projection
  "Discipline 7 — read the PROJECTION back off the event store (not a return
   value). Returns a comparable summary: counts + a sorted URI sample."
  [ctx oid]
  (let [concepts (rm/get-concepts ctx {:ontology-id oid})
        rels (filter #(= oid (:ontology-id %)) (rm/get-relationships ctx))]
    {:concept-count (count concepts)
     :relationship-count (count rels)
     :concept-uris (vec (sort (map :uri concepts)))
     :relationship-triples (vec (sort (map (juxt :source-uri :predicate :target-uri) rels)))
     ;; carry the numeric attribute through so a store that silently drops
     ;; attributes (a subtler break than dropping the whole event) is caught.
     :wage-bearing (->> concepts
                        (filter #(get-in % [:attributes :median-wage]))
                        (map (juxt :uri #(get-in % [:attributes :median-wage])))
                        (sort-by first)
                        vec)}))

;; ---------------------------------------------------------------------------
;; Cycle 1 — STORE PARITY (the decisive read-back guard)
;; ---------------------------------------------------------------------------

(deftest sqlite-vs-in-memory-projection-parity
  (testing "the SAME fixed draft set reads back IDENTICAL projections on the
            :sqlite store and the :in-memory store (the swap is behavior-preserving)"
    (let [sqlite-ctx (make-ctx {:store :sqlite})
          mem-ctx    (make-ctx {:store :in-memory})]
      (try
        (let [oid (random-uuid)]
          (land-fixture! sqlite-ctx oid)
          (land-fixture! mem-ctx oid)
          (let [sqlite-proj (read-projection sqlite-ctx oid)
                mem-proj    (read-projection mem-ctx oid)]
            (println "  [parity] :sqlite   →" (select-keys sqlite-proj
                                                           [:concept-count :relationship-count]))
            (println "  [parity] :in-memory→" (select-keys mem-proj
                                                           [:concept-count :relationship-count]))
            (println "  [parity] sqlite concept-uris:" (:concept-uris sqlite-proj))
            (println "  [parity] sqlite wage-bearing:" (:wage-bearing sqlite-proj))
            ;; the swap landed the events: a non-empty, fixture-sized projection
            (is (= (count fixture-concepts) (:concept-count sqlite-proj))
                "sqlite store must hold ALL fixture concepts (RED if events didn't land)")
            (is (= (count fixture-relationships) (:relationship-count sqlite-proj))
                "sqlite store must hold ALL fixture relationships")
            ;; PARITY — the two stores agree on every facet of the read-back
            (is (= mem-proj sqlite-proj)
                "sqlite + in-memory projections MUST be identical (behavior-preserving swap)")
            ;; numeric attribute survived the sqlite round-trip
            (is (= [["occupation/19-3039" 98230]] (:wage-bearing sqlite-proj))
                "numeric attribute must survive the sqlite event round-trip")))
        (finally
          (stop-ctx sqlite-ctx)
          (stop-ctx mem-ctx))))))

;; ---------------------------------------------------------------------------
;; Cycle 2 — SQLITE LIFECYCLE (db-file created mid-run, cleaned by stop-ctx)
;; ---------------------------------------------------------------------------

(deftest sqlite-db-file-lifecycle
  (testing ":sqlite ctx threads ::db-file; it exists mid-run; stop-ctx removes it"
    (let [ctx (make-ctx {:store :sqlite})
          db-file (::b/db-file ctx)]
      (is (string? db-file) ":sqlite ctx must thread ::db-file (the persistent path)")
      (try
        (let [oid (random-uuid)]
          (land-fixture! ctx oid)
          ;; reading back proves the file is a live store, then assert it's on disk
          (is (= (count fixture-concepts)
                 (:concept-count (read-projection ctx oid))))
          (is (.exists (java.io.File. ^String db-file))
              "db-file must EXIST on disk mid-run (events landed to it)")
          (println "  [lifecycle] db-file mid-run exists:" db-file))
        (finally (stop-ctx ctx)))
      (is (not (.exists (java.io.File. ^String db-file)))
          "stop-ctx must DELETE the db-file")
      ;; WAL/SHM sidecars are gone too
      (is (not (.exists (java.io.File. (str db-file "-wal")))) "WAL sidecar removed")
      (is (not (.exists (java.io.File. (str db-file "-shm")))) "SHM sidecar removed")
      (println "  [lifecycle] db-file + sidecars removed after stop-ctx"))))

(deftest in-memory-ctx-has-no-db-file
  (testing ":in-memory ctx carries NO ::db-file (no disk residue for tiny smokes)"
    (let [ctx (make-ctx {:store :in-memory})]
      (try
        (is (nil? (::b/db-file ctx))
            ":in-memory ctx must NOT thread a ::db-file")
        (finally (stop-ctx ctx))))))
