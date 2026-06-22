(ns ai.obney.orc.ontology.eb5-reconcile-test
  "EB5 — Reconcile subbehavior INTEGRATION test (on-demand lane, `:dev:test`).

   This is the REAL-Grain + REAL-embeddings + REAL-ColBERT proof of the EB5
   acceptance criteria — it is NOT on the fast `clj -M:poly test brick:ontology`
   gate (that gate carries the hermetic contract + deterministic-reconcile test in
   `components/ontology/test/.../reconcile_subbehavior_test.clj`). It lives here, on
   the `development/ontology-integration` on-demand lane, because it drives the
   REAL DJL MiniLM embedding model + (when up) the Python ColBERT bridge for the
   P3 check-before-mint probe over a real child tick — a heavyweight,
   bridge-dependent live run that does NOT belong in the fast brick gate (the
   gate-hygiene rule).

   It runs the EXACT same delegated path the live verify driver does
   (`development/src/eb5_reconcile_subbehavior_live_verify.clj`) and asserts the
   EB5 acceptance through the subbehavior's PUBLIC `:reads`/`:writes` contract,
   reading the report back off the PARENT tick blackboard via the projection
   (discipline 7) — NOT from the execute return value:

     - reconcile-NOT-duplicate against a PRE-POPULATED graph (a re-minted URI
       collapses to one node; only the genuinely-new entity grows the graph);
     - the check-before-mint P3 hybrid-search probe FIRED pre-mint against the
       real embedded/ColBERT-indexed graph;
     - entity-level cross-source link reported (reused reconcile-graph!);
     - 0 dangling (V18); ambiguities surfaced (never silently merged);
     - attribute-level links across entities (the EB5 deepening);
     - the report crosses `:delegate` as a parsed MAP (C1).

   The DJL MiniLM model is always available (local), so this test always runs the
   embedding signal; the ColBERT signal runs only when the Python bridge is up and
   is reported honestly either way. The captured evidence is
   `docs/build-timeline/live-verify/EB5-reconcile.md`."
  (:require [clojure.test :refer [deftest testing is]]
            [eb5-reconcile-subbehavior-live-verify :as eb5]))

(deftest reconcile-against-graph-state-entity-and-attribute-live
  (testing "EB5 (REAL Grain + REAL embeddings + REAL ColBERT): the delegated
            Reconcile subbehavior reconciles a 2nd source against the CURRENT
            graph state at entity + attribute granularity with check-before-mint"
    (let [r (eb5/run-all! {})
          report (:report r)
          entity (:entity-reconcile report)
          attrs (:attribute-reconcile report)
          probe (:probe r)]
      ;; --- the delegated run completed via the public contract --------------
      (is (= :success (:central-status r))
          "the central tree delegated to Reconcile and completed")
      (is (get-in r [:registry :registry-match?])
          "the Reconcile sheet registered with a deterministic name→id round-trip")

      ;; --- C1: the report crosses :delegate as a parsed MAP -----------------
      (is (:report-is-map? r)
          "the reconcile-report crosses :delegate as a parsed MAP (a :code-node output)")

      ;; --- reconcile-NOT-duplicate against the pre-populated graph ----------
      (is (= 1 (:nurse-node-count r))
          "the re-minted entity:nurse collapsed to ONE node (reconcile-NOT-duplicate)")
      (is (= 1 (- (:concepts-after-b r) (:concepts-before-b r)))
          "only the genuinely-new entity grew the graph (the re-mint did not duplicate)")

      ;; --- check-before-mint P3 probe FIRED pre-mint ------------------------
      (is (= (count eb5/source-b-concepts) (:probed probe))
          "the check-before-mint probe fired over every incoming draft BEFORE landing")
      (is (true? (:exact-uri? (first (filter #(= "entity:nurse" (:uri %)) (:entries probe)))))
          "the probe grounds entity:nurse as already-present in the real graph (check-before-mint)")
      (is (false? (:exact-uri? (first (filter #(= "entity:dentist" (:uri %)) (:entries probe)))))
          "the probe correctly treats the genuinely-new entity:dentist as not-present")

      ;; --- entity-level cross-source link (reused reconcile-graph!) ---------
      (is (contains? (set (get-in entity [:shared-uri-links :shared-uris])) "entity:nurse")
          "the cross-source shared-URI link (entity:nurse) is reported")

      ;; --- 0 dangling (V18), ambiguities surfaced honestly ------------------
      (is (= 0 (get-in entity [:referential-integrity :dangling-edge-count]))
          "0 dangling edges after reconcile (V18)")
      (is (true? (get-in entity [:referential-integrity :every-edge-endpoint-resolves?]))
          "every edge endpoint resolves after reconcile")
      (is (number? (:ambiguities-surfaced entity))
          "ambiguities are surfaced as a count (never silently merged — :requires-review band)")

      ;; --- attribute-level links (the EB5 deepening) ------------------------
      (is (pos? (count (:links attrs)))
          "the NEW entity's attributes link to existing entities' attributes (the EB5 deepening)")
      (is (some #(and (= "entity:dentist" (:new-uri %)) (= :sector (:new-attr-key %)))
                (:links attrs))
          "the new entity:dentist's :sector attribute links to an existing :sector attribute"))))
