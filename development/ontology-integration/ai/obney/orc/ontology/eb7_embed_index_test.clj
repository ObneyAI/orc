(ns ai.obney.orc.ontology.eb7-embed-index-test
  "EB7 — Embed+Index subbehavior INTEGRATION test (on-demand lane, `:dev:test`).

   This is the REAL-Grain + REAL-DJL-embeddings + REAL-ColBERT-bridge proof of the
   EB7 acceptance criteria — it is NOT on the fast `clj -M:poly test brick:ontology`
   gate (that gate carries the hermetic contract + field-resolution + embed-read-back
   test in `components/ontology/test/.../embed_index_subbehavior_test.clj`). It lives
   here, on the `development/ontology-integration` on-demand lane, because it drives
   the REAL DJL MiniLM embedding model AND the Python ColBERT bridge over a real
   child tick — a heavyweight, bridge-dependent live run that does NOT belong in the
   fast brick gate (the gate-hygiene rule: a test driving real ColBERT is an
   INTEGRATION test).

   It runs the EXACT same delegated path the live verify driver does
   (`development/src/eb7_embed_index_subbehavior_live_verify.clj`) and asserts the
   EB7 acceptance through the subbehavior's PUBLIC `:reads`/`:writes` contract,
   reading the report back off the PARENT tick blackboard via the projection
   (discipline 7) — NOT from the execute return value:

     - the central tree DELEGATED to Embed+Index and completed;
     - GUARANTEED-by-default: embed + ColBERT-index FIRED with NO caller wiring;
     - embed events LANDED (projection read-back — one per concept);
     - the ColBERT index is RESOLVABLE (registered for the ontology);
     - hybrid-search returns a LABELED, semantically-correct hit on the embedding
       signal (always) AND the ColBERT signal (when the bridge is up — reported
       honestly either way);
     - the report crosses `:delegate` as a parsed MAP (C1).

   The DJL MiniLM model is always available (local), so the embedding-signal
   assertion always runs; the ColBERT-signal assertion runs only when the Python
   bridge is up and is reported honestly. The captured evidence is
   `docs/build-timeline/live-verify/EB7-embed-index.md`."
  (:require [clojure.test :refer [deftest testing is]]
            [eb7-embed-index-subbehavior-live-verify :as eb7]))

(deftest embed-index-fires-by-default-and-is-retrievable-live
  (testing "EB7 (REAL Grain + REAL DJL embeddings + REAL ColBERT): the delegated
            Embed+Index subbehavior embeds + ColBERT-indexes a built graph BY
            DEFAULT (no caller wiring) and the concepts are semantically retrievable"
    ;; BOUNDED run (#11): the live path drives the Python ColBERT bridge (a
    ;; lazily-spawned singleton subprocess). Wrap the driver in a future + deref
    ;; timeout so a bridge stall surfaces as a clear assertion failure rather than
    ;; hanging the suite. The standalone driver completes in ~10-30s; 280s is a
    ;; generous ceiling that still bounds a wedged bridge.
    (let [fut (future (eb7/run-all! {}))
          r (deref fut 280000 ::timeout)
          _ (is (not= ::timeout r)
                "the EB7 live run completed within the bounded timeout (the ColBERT bridge did not wedge)")
          r (if (= ::timeout r) {} r)
          report (:report r)]
      ;; --- the delegated run completed via the public contract --------------
      (is (= :success (:central-status r))
          "the central tree delegated to Embed+Index and completed")
      (is (get-in r [:registry :registry-match?])
          "the Embed+Index sheet registered with a deterministic name→id round-trip")

      ;; --- C1: the report crosses :delegate as a parsed MAP -----------------
      (is (:report-is-map? r)
          "the embed-index-report crosses :delegate as a parsed MAP (a :code-node output)")

      ;; --- GUARANTEED-by-default embed: events LANDED (read back, #7) --------
      (is (= (count eb7/source-concepts) (:embedded-count report))
          "every concept was embedded BY DEFAULT (no caller wiring)")
      (is (= (count eb7/source-concepts) (:embeddings-read-back-count r))
          "the :ontology/concept-embedded events LANDED (projection read-back, #7)")
      (is (= [:description :label] (:embed-fields-used report))
          "the embed-fields resolved from the Model's :embed-fields signal")
      (is (= :model-signal (:embed-fields-source report))
          "the fields came from the EB3 model signal (not the heuristic fallback)")

      ;; --- GUARANTEED-by-default ColBERT index: RESOLVABLE -------------------
      (is (some? (:colbert-index-id r))
          "a ColBERT index-id is registered for the ontology BY DEFAULT (RESOLVABLE)")
      (is (nil? (:index-skipped-reason report))
          "a real index was built (no skip reason)")

      ;; --- EMBEDDING signal: labeled, semantically-correct hit --------------
      (is (= "entity:nurse" (:uri (first (:emb-hits r))))
          "the embedding signal's TOP hit for 'caring for patients in a hospital' is the Nurse concept")
      (is (= "Registered Nurse" (:label (first (:emb-hits r))))
          "the top embedding hit carries its LABEL (labeled, semantically-correct)")

      ;; --- COLBERT signal: labeled hit when the bridge is up ----------------
      (if (:colbert-bridge-up? r)
        (do
          (is (contains? (set (map :uri (:cb-hits r))) "entity:engineer")
              "the ColBERT signal retrieves the Engineer concept for 'writing and maintaining software programs'")
          (is (some #(and (= "entity:engineer" (:uri %)) (= "Software Engineer" (:label %)))
                    (:cb-hits r))
              "the ColBERT hit carries its LABEL (labeled, semantically-correct)"))
        (println "[EB7 integration] ColBERT bridge DOWN — ColBERT-signal assertion skipped (reported honestly); embedding signal verified.")))))
