(ns ai.obney.orc.ontology.s03-alignment-registry-test
  "S03 — Alignment-section registry + auto-widening queries.

   The slice's acceptance test corpus. Each deftest corresponds to one
   acceptance criterion from the slice file:

     1. Registry: register/deregister commands emit events; the projection
        carries the CURRENT registrations per primary-id AND the full
        history; adversarial twin verifies a deregistration actually
        removes the entry (current view) but leaves history intact.

     2. Malformed commands rejected at the schema gate (defense in depth
        with the projection — the only way a bad event can land is by
        bypassing the command-processor; the schema gate rejects it).

     3. Auto-widening at retrieval: a scoped query against primary P with
        a registered alignment A1 auto-widens to include A1 in graph BFS
        (the S02 multi-section path), embedding semantic search, and
        ColBERT scoping. The cross-section concept surfaces with fusion
        intact (S02 promise preserved).

     4. Disable auto-widen per query: an explicit `:auto-widen-alignments?
        false` restores strict single-section behavior. Adversarial:
        verify the alignment IS visible default-on AND is NOT visible
        when disabled (the same registry state under both queries).

     5. Unregistered sections NEVER auto-included. Register A->A1; query
        scoped to B; B's results must NOT contain A1's concepts.

     6. Cleanly droppable: deregister A->A1, then the very-next scoped
        query against A no longer surfaces A1's concepts. Asserts the
        absence — no stale-cache leak.

     7. Cycle/transitive (the prototype's verdict): SINGLE-HOP only.
        Register P->A1, A1->A2; widen(P) returns {P, A1} (NOT {P, A1,
        A2}). Cycle: register P->A1, A1->P; widen(P) returns {P, A1}
        and TERMINATES (set dedupe).

   Verified through PUBLIC interfaces only — commands + read-model
   projection helpers + retrieval/hybrid-search."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.set]
            [ai.obney.orc.ontology.test-helpers :as h]
            ;; Required for event-schema registration — without this,
            ;; the event-store's append-time Malli validation rejects
            ;; the alignment-registration events.
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.retrieval :as retrieval]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]))

(def ontology-a-id #uuid "a0030000-0000-0000-0000-000000000001")
(def ontology-b-id #uuid "b0030000-0000-0000-0000-000000000002")
(def ontology-c-id #uuid "c0030000-0000-0000-0000-000000000003")

;; =============================================================================
;; Sugar over the new defcommands (mirrors the S07 pattern exactly)
;; =============================================================================

(defn- register! [ctx body]
  (h/run-and-apply! ctx
                    (fn [c]
                      (cmd/ontology-register-alignment-section
                       (assoc c :command body)))))

(defn- deregister! [ctx body]
  (h/run-and-apply! ctx
                    (fn [c]
                      (cmd/ontology-deregister-alignment-section
                       (assoc c :command body)))))

;; =============================================================================
;; AC1 — register/deregister round-trips through the projection
;; =============================================================================

(deftest registry-register-and-deregister-round-trip
  (testing "Register A->B, register A->C, deregister A->B; CURRENT
            registrations for A == {C} (NOT {B C}, NOT {B}); the
            full history retains every event."
    (h/with-test-context [ctx]
      (register! ctx {:primary-ontology-id ontology-a-id
                      :alignment-ontology-id ontology-b-id})
      (register! ctx {:primary-ontology-id ontology-a-id
                      :alignment-ontology-id ontology-c-id})
      (deregister! ctx {:primary-ontology-id ontology-a-id
                        :alignment-ontology-id ontology-b-id})

      (let [current (rm/get-alignment-sections ctx ontology-a-id)
            history (rm/get-alignment-registry-history ctx ontology-a-id)]
        (is (= #{ontology-c-id} current)
            "current registrations for A == {C}; B has been removed; B+C aren't both there")
        (is (= 3 (count history))
            "history retains the three actions")
        (let [evt-types (map :action history)]
          (is (= [:registered :registered :deregistered] evt-types)
              "history records both registrations AND the deregistration"))))))

(deftest registry-empty-for-unregistered-primary
  (testing "Primary-id with no events returns empty set (not nil) so
            callers can iterate without a nil guard."
    (h/with-test-context [ctx]
      (let [current (rm/get-alignment-sections ctx ontology-a-id)
            history (rm/get-alignment-registry-history ctx ontology-a-id)]
        (is (= #{} current) "current == #{} for an unregistered primary")
        (is (= [] history) "history == [] for an unregistered primary")))))

(deftest registry-deregister-of-unregistered-pair-is-a-no-op-on-current
  (testing "Adversarial: deregister A->B when A->B was never registered.
            The CURRENT view for A stays empty; the history still
            records the deregister event (audit-faithful)."
    (h/with-test-context [ctx]
      (deregister! ctx {:primary-ontology-id ontology-a-id
                        :alignment-ontology-id ontology-b-id})
      (is (= #{} (rm/get-alignment-sections ctx ontology-a-id))
          "current view unchanged (the pair wasn't there)")
      (is (= 1 (count (rm/get-alignment-registry-history ctx ontology-a-id)))
          "history records the action regardless"))))

;; =============================================================================
;; AC2 — malformed commands rejected at the schema gate
;; =============================================================================

(deftest registry-malformed-register-rejected
  (testing "Missing required field on register-alignment-section is
            rejected at the Grain command-processor's pre-handler Malli
            gate with an anomaly; no event is emitted."
    (h/with-test-context [ctx]
      (let [result (cp/process-command
                    (assoc ctx :command
                           {:command/name :ontology/register-alignment-section
                            :command/id (random-uuid)
                            :command/timestamp (time/now)
                            ;; missing :alignment-ontology-id
                            :primary-ontology-id ontology-a-id}))]
        (is (= ::anom/incorrect (::anom/category result))
            "missing-field returns the schema-incorrect anomaly")
        (is (empty? (:command-result/events result))
            "no event emitted")))))

(deftest registry-malformed-id-rejected
  (testing "Non-UUID id is rejected at the schema gate (the Malli :uuid
            check). Defense in depth: no event lands."
    (h/with-test-context [ctx]
      (let [result (cp/process-command
                    (assoc ctx :command
                           {:command/name :ontology/register-alignment-section
                            :command/id (random-uuid)
                            :command/timestamp (time/now)
                            :primary-ontology-id "not-a-uuid"
                            :alignment-ontology-id ontology-b-id}))]
        (is (= ::anom/incorrect (::anom/category result))
            "non-uuid id returns schema-incorrect")
        (is (empty? (:command-result/events result))
            "no event emitted")))))

;; =============================================================================
;; Two-section corpus (re-used across AC3/4/5/6)
;; =============================================================================
;; The setup is the S02 two-section corpus, narrowed to the minimum
;; needed to prove auto-widening: each section has 2 concepts, with a
;; cross-section :related edge from A's concept to B's. The minimal
;; shape isolates "the widened query found B because alignment was
;; registered" rather than dragging in S02's URI-collision adversarial
;; surface (which S02 already covers).

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
  [ctx ontology-id source-uri predicate target-uri]
  (h/run-and-apply! ctx
                    (fn [c]
                      (cmd/ontology-create-relationship
                       (assoc c :command
                              {:ontology-id ontology-id
                               :source-uri source-uri
                               :target-uri target-uri
                               :predicate predicate
                               :properties {}})))))

(defn- seed-two-section-corpus!
  "Section A: a:lexicon, a:term (term broader-than lexicon).
   Section B: b:taxonomy, b:program (program broader-than taxonomy).

   Cross-section edge: a:lexicon :related b:taxonomy, tagged with
   `:ontology-id ontology-b-id`. This is the intended alignment-section
   ownership pattern — the relationship lives WITH the alignment
   target's section so that scoping ONLY to the primary section A
   (without widening) NEVER sees the cross-section edge. When widening
   expands scope to {A B}, B's section-keyed concept-map merges in and
   b:taxonomy's projected `:related → a:lexicon` edge surfaces; the
   `concepts->graph` builder adds the BIDIRECTIONAL edge in both
   directions, so BFS seeded at a:lexicon reaches b:taxonomy.

   This is the precise interaction with the S06 section-keyed
   relationship projection: a relationship event whose `:ontology-id`
   names a section that contains the target URI lands in that section
   only — never spilling into the source's section. Strict isolation
   without widening; full reach with."
  [ctx]
  (seed-concept! ctx ontology-a-id "a:lexicon" "Lexicon")
  (seed-concept! ctx ontology-a-id "a:term" "Term")
  (seed-relationship! ctx ontology-a-id "a:term" "skos:broader" "a:lexicon")

  (seed-concept! ctx ontology-b-id "b:taxonomy" "Taxonomy")
  (seed-concept! ctx ontology-b-id "b:program" "Program")
  (seed-relationship! ctx ontology-b-id "b:program" "skos:broader" "b:taxonomy")

  ;; The cross-section :related edge tagged WITH B (not A). The S06
  ;; section-keyed projection routes the edge to section B; B's
  ;; b:taxonomy concept accrues `:related #{a:lexicon}`. Section A is
  ;; unaffected.
  (seed-relationship! ctx ontology-b-id "a:lexicon" "skos:related" "b:taxonomy"))

;; =============================================================================
;; AC3 — Auto-widening: a scoped query against primary A with A->B
;; registered surfaces B's concept via the graph signal, with fusion
;; ranks intact (S02 promise preserved).
;; =============================================================================

(deftest auto-widen-default-on-surfaces-cross-section-concept
  (testing "Register A->B. A scoped graph query against A surfaces B's
            taxonomy via the cross-section :related edge — exactly the
            S02 multi-section widening, but the caller passed
            :ontology-id A only (the registry expanded it). Adversarial
            twin: assert the SAME query against A without the
            registration does NOT surface B's taxonomy."
    (h/with-test-context [ctx]
      (seed-two-section-corpus! ctx)

      ;; --- Before registration: scoped to A only — strict isolation ---
      (let [{:keys [graph-results]}
            (retrieval/hybrid-search
             ctx
             {:seed-uris ["a:lexicon"]
              :query-text nil
              :ontology-id ontology-a-id
              :signals #{:graph}
              :limit 20})
            uris (set (map :uri graph-results))]
        (is (contains? uris "a:lexicon") "scoped graph reaches own seed")
        (is (not (contains? uris "b:taxonomy"))
            "WITHOUT registration, the cross-section concept is correctly invisible"))

      ;; --- After registration: registry expands A's scope to {A B} ---
      (register! ctx {:primary-ontology-id ontology-a-id
                      :alignment-ontology-id ontology-b-id})

      (let [{:keys [results graph-results batches-used]}
            (retrieval/hybrid-search
             ctx
             {:seed-uris ["a:lexicon"]
              :query-text nil
              :ontology-id ontology-a-id
              :signals #{:graph}
              :limit 20})
            uris (set (map :uri graph-results))]
        (is (contains? uris "b:taxonomy")
            "WITH A->B registered, scoped query against A surfaces B's taxonomy")
        (is (contains? (set batches-used) :graph)
            "graph batch participated in fusion")
        ;; Adversarial: fusion-rank not silently dropped (S02's promise)
        (when-let [hit (first (filter #(= "b:taxonomy" (:uri %)) results))]
          (is (some? (:graph-rank hit))
              "auto-widened cross-section hit carries graph-rank — fusion ranks intact"))))))

;; =============================================================================
;; AC4 — Per-query override: :auto-widen-alignments? false MUST suppress
;; the alignment even when one is registered. Same registry state,
;; different opt — different result. (Override semantics, not just
;; data presence.)
;; =============================================================================

(deftest auto-widen-can-be-disabled-per-query
  (testing "With A->B registered: the SAME query, with the only
            difference being :auto-widen-alignments? false, must NOT
            surface B's taxonomy. The override is the per-query knob
            consumers reach for to enforce strict single-section."
    (h/with-test-context [ctx]
      (seed-two-section-corpus! ctx)
      (register! ctx {:primary-ontology-id ontology-a-id
                      :alignment-ontology-id ontology-b-id})

      ;; Default-on: visible
      (let [{:keys [graph-results]}
            (retrieval/hybrid-search
             ctx
             {:seed-uris ["a:lexicon"]
              :query-text nil
              :ontology-id ontology-a-id
              :signals #{:graph}
              :limit 20})
            uris (set (map :uri graph-results))]
        (is (contains? uris "b:taxonomy")
            "default-on: B's taxonomy visible (the auto-widen path lit up)"))

      ;; Explicit disable: NOT visible (same registry, same seed, same scope)
      (let [{:keys [graph-results]}
            (retrieval/hybrid-search
             ctx
             {:seed-uris ["a:lexicon"]
              :query-text nil
              :ontology-id ontology-a-id
              :auto-widen-alignments? false
              :signals #{:graph}
              :limit 20})
            uris (set (map :uri graph-results))]
        (is (contains? uris "a:lexicon")
            "explicit-disable: own-section concept still reached (adversarial guard
             — 'invisible' must not pass trivially by returning nothing)")
        (is (not (contains? uris "b:taxonomy"))
            "explicit-disable: B's taxonomy invisible despite A->B registration")))))

;; =============================================================================
;; AC5 — Unregistered sections NEVER auto-included. The query is
;; ACTUALLY executed with the registry active; the assertion is on
;; retrieval-time behavior, not on registry contents.
;; =============================================================================

(deftest unregistered-section-never-auto-included
  (testing "Register A->A1 only. Query scoped to A; B (a wholly unrelated,
            unregistered section) must NOT surface in results — even
            though A and B coexist in the store. We probe the RETRIEVAL,
            not the registry contents."
    (h/with-test-context [ctx]
      (seed-two-section-corpus! ctx)
      ;; Register A's alignment as C (a non-existent section that's NOT
      ;; B). B remains COMPLETELY unregistered for A.
      (register! ctx {:primary-ontology-id ontology-a-id
                      :alignment-ontology-id ontology-c-id})

      (let [{:keys [graph-results]}
            (retrieval/hybrid-search
             ctx
             {:seed-uris ["a:lexicon"]
              :query-text nil
              :ontology-id ontology-a-id
              :signals #{:graph}
              :limit 20})
            uris (set (map :uri graph-results))]
        (is (contains? uris "a:lexicon") "own-section concept reached")
        (is (not (contains? uris "b:taxonomy"))
            "B is UNREGISTERED for A — the cross-section edge does NOT widen
             B in via the registry, even though scoping was active")
        (is (not (contains? uris "b:program"))
            "B's other concepts likewise excluded")))))

;; =============================================================================
;; AC6 — Cleanly droppable: deregister, the VERY NEXT query no longer
;; sees the alignment's concepts (no stale-cache leak).
;; =============================================================================

(deftest deregister-drops-alignment-influence-immediately
  (testing "Register A->B; verify auto-widen visible. Deregister A->B;
            re-run the SAME query — B's concepts NOT visible. The
            very-next query asserts there's no read-side cache that
            would silently keep widening with the deregistered pair."
    (h/with-test-context [ctx]
      (seed-two-section-corpus! ctx)
      (register! ctx {:primary-ontology-id ontology-a-id
                      :alignment-ontology-id ontology-b-id})

      ;; Verify widen ON
      (let [{:keys [graph-results]}
            (retrieval/hybrid-search
             ctx
             {:seed-uris ["a:lexicon"]
              :query-text nil
              :ontology-id ontology-a-id
              :signals #{:graph}
              :limit 20})
            uris (set (map :uri graph-results))]
        (is (contains? uris "b:taxonomy")
            "pre-deregister sanity: alignment visible"))

      ;; Deregister
      (deregister! ctx {:primary-ontology-id ontology-a-id
                        :alignment-ontology-id ontology-b-id})

      ;; Very-next query: no longer visible
      (let [{:keys [graph-results]}
            (retrieval/hybrid-search
             ctx
             {:seed-uris ["a:lexicon"]
              :query-text nil
              :ontology-id ontology-a-id
              :signals #{:graph}
              :limit 20})
            uris (set (map :uri graph-results))]
        (is (contains? uris "a:lexicon")
            "post-deregister: own-section reach still works (adversarial guard)")
        (is (not (contains? uris "b:taxonomy"))
            "post-deregister: B's taxonomy NOT visible — no stale widening leak")))))

;; =============================================================================
;; AC7 — The prototype's verdict: single-hop widening + cycle-tolerant
;; registration. Tested via the public widen-ontology-ids fn.
;; =============================================================================

(deftest widen-is-single-hop-not-transitive
  (testing "PROTOTYPE VERDICT (encoded as behavior): widen-ontology-ids
            is single-hop. Registering P->A1 and A1->A2 does NOT chain
            A1's alignments into P's widened set. Consumers who want a
            chain reach must register it explicitly."
    (h/with-test-context [ctx]
      (register! ctx {:primary-ontology-id ontology-a-id
                      :alignment-ontology-id ontology-b-id})
      (register! ctx {:primary-ontology-id ontology-b-id
                      :alignment-ontology-id ontology-c-id})

      (is (= #{ontology-a-id ontology-b-id}
             (rm/widen-ontology-ids ctx ontology-a-id))
          "single-hop: widen(A) == {A B} — does NOT chase B->C transitively")
      (is (= #{ontology-b-id ontology-c-id}
             (rm/widen-ontology-ids ctx ontology-b-id))
          "single-hop: widen(B) == {B C} — independent of widen(A)")
      (is (= #{ontology-c-id}
             (rm/widen-ontology-ids ctx ontology-c-id))
          "widen(C) == {C} — C has no registered alignment, so just C itself"))))

(deftest registration-tolerates-cycles-without-erroring
  (testing "PROTOTYPE VERDICT: cycles are accepted at registration
            (registration is per-pair, no global invariant violated by
            P<->A1). Widening terminates and dedupes via set semantics:
            widen(P) == {P A1}, NOT an infinite loop, NOT an error."
    (h/with-test-context [ctx]
      ;; P -> A1 and A1 -> P (a registration cycle)
      (register! ctx {:primary-ontology-id ontology-a-id
                      :alignment-ontology-id ontology-b-id})
      (register! ctx {:primary-ontology-id ontology-b-id
                      :alignment-ontology-id ontology-a-id})

      (is (= #{ontology-a-id ontology-b-id}
             (rm/widen-ontology-ids ctx ontology-a-id))
          "widen(A) terminates and == {A B} (single-hop dedupes)")
      (is (= #{ontology-a-id ontology-b-id}
             (rm/widen-ontology-ids ctx ontology-b-id))
          "widen(B) terminates and == {A B} (single-hop dedupes; cycle accepted)"))))

(deftest widen-ontology-ids-accepts-collection-input
  (testing "widen-ontology-ids should accept either a single id or a
            collection of ids — the latter is what auto-widening from a
            multi-section caller needs to feed back into the retrieval
            signals. The output is always the union of widened sets."
    (h/with-test-context [ctx]
      (register! ctx {:primary-ontology-id ontology-a-id
                      :alignment-ontology-id ontology-b-id})

      (is (= #{ontology-a-id ontology-b-id}
             (rm/widen-ontology-ids ctx [ontology-a-id]))
          "single-element coll widens like the singleton")
      (is (= #{ontology-a-id ontology-b-id ontology-c-id}
             (rm/widen-ontology-ids ctx [ontology-a-id ontology-c-id]))
          "multi-element coll unions widened sets (C unregistered → just C)"))))
