(ns eb11-maintain-worth-prototype
  "EB11 WORTH prototype (deterministic — no live LLM).

   Proves the EVOLUTIONARY-MAINTAIN substrate before the central-evolver
   re-orchestration: build a graph from source A, then feed source B (which
   SHARES an entity with A + adds a NEW class whose attribute relates to an A
   entity's attribute) against the EXISTING graph, and prove:

     (a) B's shared entity RECONCILES-NOT-DUPLICATES (no second node for the
         same URI; the projection collapses on URI, and reconcile counts it as
         pre-existing, not new);
     (b) B introduces a NEW CLASS (a new entity TYPE the graph did not hold) —
         the TBox/graph GROWS (read back via get-concepts/get-axioms);
     (c) a B attribute CONNECTS to an EXISTING A attribute (EB5 attribute-link
         granularity — the genuinely-new EB5 logic).

   Plus IDEMPOTENCY: re-feeding source B UNCHANGED reconciles and does NOT
   duplicate (no new concepts land on the 2nd pass).

   Domain-agnostic fixtures (#12): no vertical vocabulary. The 'classes' are
   abstract :widget / :gadget / :module entity-types; the attributes are
   abstract :region / :tier.

   Run:  bb/clj -M:dev -e ... (see the comment block at the foot) — or from the
   on-demand live-verify runner. This is a HERMETIC deterministic proof (the
   reconcile substrate is deterministic: probe = P3 retrieval restricted to
   #{:graph :lexical}, links = structural). No OpenRouter key needed."
  (:require [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.reconcile-subbehavior :as reconcile]))

(defn- land!
  "Land a draft set against the CURRENT graph via the EB5 reconcile orchestration
   (the maintain path IS 'reconcile new drafts vs the existing graph'). Returns
   the reconcile report. Probe restricted to hermetic signals (#{:graph :lexical})
   so no embedding model / ColBERT bridge is needed for the deterministic proof."
  [ctx oid concept-drafts relationship-drafts]
  (reconcile/reconcile-drafts!
   ctx {:ontology-id oid
        :concept-drafts concept-drafts
        :relationship-drafts relationship-drafts
        :probe-signals #{:graph :lexical}
        :llm-budget 0}))

(defn run! []
  (h/with-async-test-context [ctx]
    (let [oid (random-uuid)
          ;; ---- SOURCE A: build the initial graph (greenfield-equivalent) ----
          ;; A widget entity carrying a :region attribute, and a module it uses.
          source-a-concepts
          [{:uri "ent:widget/w1" :label "Widget One"
            :attributes {:region "north" :tier "gold"}}
           {:uri "ent:module/m1" :label "Module One"
            :attributes {:region "north"}}]
          source-a-rels
          [{:source-uri "ent:widget/w1" :target-uri "ent:module/m1"
            :predicate "uses"}]
          _ (land! ctx oid source-a-concepts source-a-rels)

          ;; BEFORE snapshot of the graph (the existing graph maintain reads).
          before-concepts (rm/get-concepts ctx {:ontology-id oid})
          before-uris (set (map :uri before-concepts))

          ;; ---- SOURCE B: a NEW source fed AGAINST THE EXISTING GRAPH ----
          ;; - SHARES the existing widget entity (same URI ent:widget/w1) — must
          ;;   reconcile-not-duplicate.
          ;; - introduces a NEW CLASS: a :gadget entity-type the graph never held.
          ;; - the NEW gadget carries :region "north" — the SAME attr key+value an
          ;;   EXISTING A entity (widget/module) carries → an attribute-level link.
          source-b-concepts
          [{:uri "ent:widget/w1" :label "Widget One"
            :attributes {:region "north" :status "active"}}   ; SHARED entity (re-asserted)
           {:uri "ent:gadget/g1" :label "Gadget One"          ; NEW CLASS
            :attributes {:region "north" :model "x9"}}]
          source-b-rels
          [{:source-uri "ent:gadget/g1" :target-uri "ent:widget/w1"
            :predicate "augments"}]
          report-b (land! ctx oid source-b-concepts source-b-rels)

          after-concepts (rm/get-concepts ctx {:ontology-id oid})
          after-uris (set (map :uri after-concepts))

          ;; ---- IDEMPOTENCY: re-feed source B UNCHANGED ----
          report-b2 (land! ctx oid source-b-concepts source-b-rels)
          after2-concepts (rm/get-concepts ctx {:ontology-id oid})
          after2-uris (set (map :uri after2-concepts))

          ;; reconcile probe entries for source B (the check-before-mint signal)
          probe-entries (get-in report-b [:mint-probe :entries])
          shared-probe (first (filter #(= "ent:widget/w1" (:uri %)) probe-entries))
          gadget-probe (first (filter #(= "ent:gadget/g1" (:uri %)) probe-entries))
          attr-links (get-in report-b [:attribute-reconcile :links])
          gadget-region-links
          (filter #(and (= "ent:gadget/g1" (:new-uri %))
                        (= :region (:new-attr-key %)))
                  attr-links)]

      (println "\n=================== EB11 WORTH PROTOTYPE ===================")
      (println "ontology-id:" oid)
      (println "\n--- BEFORE (source A only) ---")
      (println "concept count:" (count before-concepts))
      (println "concept URIs: " (sort before-uris))

      (println "\n--- (a) SHARED ENTITY reconcile-not-duplicate ---")
      (println "shared widget/w1 probe :exact-uri?:" (:exact-uri? shared-probe)
               " :match?:" (:match? shared-probe))
      (println "widget/w1 node count in graph:"
               (count (filter #(= "ent:widget/w1" (:uri %)) after-concepts)))

      (println "\n--- (b) NEW CLASS introduced (TBox/graph grows) ---")
      (println "gadget/g1 was pre-existing? (should be false):"
               (contains? before-uris "ent:gadget/g1"))
      (println "gadget/g1 in graph AFTER? (should be true):"
               (contains? after-uris "ent:gadget/g1"))
      (println "gadget/g1 probe :exact-uri? (should be false — new):"
               (:exact-uri? gadget-probe))
      (println "graph grew from" (count before-concepts) "->" (count after-concepts) "concepts")

      (println "\n--- (c) NEW attribute connects to an EXISTING A attribute ---")
      (println "gadget :region attribute-links to existing entities:")
      (doseq [l gadget-region-links]
        (println "   " (:new-uri l) (:new-attr-key l) "->"
                 (:existing-uri l) (:existing-attr-key l)
                 " kind:" (:kind l) " value:" (:value l)))
      (println "same-value link count:" (get-in report-b [:attribute-reconcile :same-value-link-count]))

      (println "\n--- IDEMPOTENCY: re-feed source B unchanged ---")
      (println "concepts after 1st B pass:" (count after-concepts)
               " after 2nd identical B pass:" (count after2-concepts)
               " (should be EQUAL — no duplicate)")
      (println "2nd-pass probe exact-uri-hits (should be 2 — both URIs now pre-existing):"
               (get-in report-b2 [:mint-probe :exact-uri-hits]))

      (let [results
            {:shared-reconciles-not-duplicates
             (and (true? (:exact-uri? shared-probe))
                  (= 1 (count (filter #(= "ent:widget/w1" (:uri %)) after-concepts))))
             :new-class-introduced
             (and (not (contains? before-uris "ent:gadget/g1"))
                  (contains? after-uris "ent:gadget/g1")
                  (> (count after-concepts) (count before-concepts)))
             :new-attr-links-to-existing
             (boolean (seq gadget-region-links))
             :idempotent-no-duplicate
             (and (= (count after-concepts) (count after2-concepts))
                  (= after-uris after2-uris)
                  (= 2 (get-in report-b2 [:mint-probe :exact-uri-hits])))}]
        (println "\n=================== VERDICT ===================")
        (doseq [[k v] results]
          (println (if v "PASS" "FAIL") " " (name k)))
        (println "ALL PASS:" (every? true? (vals results)))
        results))))

(defn -main [& _]
  (let [fut (future (run!))
        res (deref fut 120000 ::timeout)]
    (if (= ::timeout res)
      (do (println "TIMEOUT") (System/exit 2))
      (do (println "\n[prototype complete]")
          (System/exit (if (every? true? (vals res)) 0 1))))))

;; Run:
;; clj -M:dev -m eb11-maintain-worth-prototype
