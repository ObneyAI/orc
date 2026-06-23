(ns ai.obney.orc.ontology.eb11-maintain-evolutionary-test
  "EB11 — Maintain (evolutionary). HERMETIC brick-gate tests locking the
   MAINTAIN-COMPOSITION logic the EB11 prototype + live verify validated, through
   the PUBLIC `run-central-evolver!` surface — never prompt-string internals. The
   maintain arm is the RE-ORCHESTRATION of EB10's greenfield STEP 2-6 pipeline
   against an EXISTING graph (the subbehaviors already read current graph state),
   tested DETERMINISTICALLY via the injected subbehavior seams (Discipline #4/#6).

   What is locked here (the EB11 acceptance):
     - MAINTAIN branch selects when a graph ALREADY EXISTS (DT9 reuse) and runs
       the REAL evolutionary-maintain composition (NOT the deferred stub): survey →
       derive CQs → per-source model→extract → reconcile → axiom → embed → build →
       CQ-objective loop — the SAME pipeline EB10's greenfield arm runs, against the
       existing graph;
     - the maintain composition reuses EB10's loop + the subbehavior seams (no fork)
       — proven by stubbing the SAME seams greenfield uses and asserting they run;
     - IDEMPOTENT: re-running an UNCHANGED source reconciles against the existing
       graph, does NOT duplicate (EB5's against-graph-state seam) — asserted via the
       projection read-back (discipline 7), NOT a return value;
     - the existing graph is the INPUT: the maintain run reads + grows the existing
       graph rather than building greenfield-only (a NEW class/entity lands ALONGSIDE
       the pre-existing concepts, the pre-existing ones survive).

   The LIVE evolutionary proof (a real 2nd source introduces a new class whose
   attribute connects to an existing entity's attribute; a previously-unanswerable
   CQ may now pass) is on the on-demand lane (`development/src` live verify) — the
   real-LLM/ColBERT path. Domain-agnostic fixtures (#12)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.reconcile-subbehavior :as reconcile]
            [ai.obney.orc.ontology.core.central-evolver :as ce]))

(defn- land!
  "Land concept drafts (with attributes) against the CURRENT graph via the real
   EB5 reconcile orchestration — the maintain landing path. Hermetic probe signals
   (no embedding model / ColBERT bridge)."
  [ctx oid concept-drafts relationship-drafts]
  (reconcile/reconcile-drafts!
   ctx {:ontology-id oid
        :concept-drafts concept-drafts
        :relationship-drafts relationship-drafts
        :probe-signals #{:graph :lexical}
        :llm-budget 0}))

(defn- count-concepts [ctx oid]
  (count (rm/get-concepts ctx {:ontology-id oid})))

(defn- concept-uris [ctx oid]
  (set (map :uri (rm/get-concepts ctx {:ontology-id oid}))))

;; =============================================================================
;; 1. MAINTAIN runs the REAL evolutionary-maintain composition (NOT the stub)
;; =============================================================================

(deftest maintain-runs-the-real-evolutionary-composition-test
  (testing "MAINTAIN (a graph already exists): run-central-evolver! runs the REAL
            survey→derive→model-extract→reconcile→axiom→embed→build→loop pipeline
            against the EXISTING graph — NOT the deferred stub. The maintain arm
            reuses the SAME subbehavior seams greenfield uses (no fork)."
    (h/with-async-test-context [ctx]
      (let [oid (random-uuid)
            ;; seed an EXISTING graph (so the front-of-tree condition selects maintain)
            _ (land! ctx oid [{:uri "ent:widget/w1" :label "Widget One"
                               :attributes {:region "north"}}] [])
            survey-calls (atom 0)
            derive-calls (atom 0)
            mx-calls (atom 0)
            reconcile-calls (atom 0)
            axiom-calls (atom 0)
            embed-calls (atom 0)
            build-calls (atom 0)
            survey-fn (fn [_ _] (swap! survey-calls inc)
                        {:status :success :profile {:entity-candidates ["gadget"]}})
            derive-cqs-fn (fn [_ _] (swap! derive-calls inc)
                            {:status :success :competency-questions ["Q1"]})
            ;; the NEW source introduces a NEW class (gadget) the graph lacked.
            model-extract-fn (fn [c _p]
                               (swap! mx-calls inc)
                               (land! c oid [{:uri "ent:gadget/g1" :label "Gadget One"
                                              :attributes {:region "north"}}] [])
                               {:status :success
                                :concept-drafts [{:uri "ent:gadget/g1"}]
                                :relationship-drafts [] :embed-fields []
                                :model-spec {} :candidate-axioms {:axioms []}})
            reconcile-fn (fn [_ _] (swap! reconcile-calls inc) {:status :success})
            axiom-fn (fn [_ _] (swap! axiom-calls inc) {:status :success})
            embed-fn (fn [_ _] (swap! embed-calls inc) {:status :success})
            build-fn (fn [_ _] (swap! build-calls inc) {:status :complete})
            gate-fn (fn [_ _]
                      {:graph-health {:pass-rate 1.0 :unknown-rate 0.0}
                       :evaluated [{:cq-text "Q1" :verdict :pass}]
                       :cq-verdict [{:cq-text "Q1" :verdict :pass}]})
            before (count-concepts ctx oid)
            result (ce/run-central-evolver!
                    ctx {:ontology-id oid :sources [{:type :csv :path "b"}] :goal "g"
                         :survey-fn survey-fn :derive-cqs-fn derive-cqs-fn
                         :model-extract-fn model-extract-fn :reconcile-fn reconcile-fn
                         :axiom-fn axiom-fn :embed-fn embed-fn
                         :build-fn build-fn :gate-fn gate-fn})
            after (count-concepts ctx oid)]
        ;; NOT the deferred stub
        (is (not= :maintain-deferred (:status result))
            "maintain runs the REAL composition, not the deferred stub")
        (is (= :complete (:status result)) "the maintain composition completed")
        ;; the maintain branch was selected (DT9 reuse)
        (is (= :maintain (get-in result [:branch-points :greenfield-vs-maintain :selected]))
            "the front-of-tree condition selected maintain (an existing graph)")
        (is (true? (get-in result [:branch-points :greenfield-vs-maintain :taken?]))
            "the maintain branch is taken")
        (is (= :maintain (:mode result)) "the result tags the maintain mode")
        ;; the SAME subbehavior pipeline ran (reuse, no fork)
        (is (pos? @survey-calls) "Survey ran on the maintain arm")
        (is (pos? @derive-calls) "Derive-CQs ran on the maintain arm")
        (is (pos? @mx-calls) "Model→Extract ran on the maintain arm")
        (is (pos? @reconcile-calls) "Reconcile ran on the maintain arm")
        (is (pos? @axiom-calls) "Axiom/TBox ran on the maintain arm")
        (is (pos? @embed-calls) "Embed ran on the maintain arm")
        ;; the existing graph is the INPUT: the new class lands ALONGSIDE it
        (is (contains? (concept-uris ctx oid) "ent:widget/w1")
            "the PRE-EXISTING entity survives the maintain run (read, not rebuilt)")
        (is (contains? (concept-uris ctx oid) "ent:gadget/g1")
            "the NEW class the new source introduced landed")
        (is (> after before)
            "the existing graph GREW (maintain made new discoveries, not greenfield-only)")
        (is (vector? (:competency-questions result))
            "the CQs were re-derived/re-gated on the maintain arm")))))

;; =============================================================================
;; 2. IDEMPOTENT — re-running an UNCHANGED source reconciles, does NOT duplicate
;; =============================================================================

(deftest maintain-is-idempotent-no-duplicate-on-unchanged-source-test
  (testing "re-running an UNCHANGED source against the existing graph reconciles
            (does NOT duplicate) — EB5's against-graph-state seam. Asserted via the
            projection read-back (discipline 7), NOT a return value."
    (h/with-async-test-context [ctx]
      (let [oid (random-uuid)
            ;; seed the graph so maintain is selected
            _ (land! ctx oid [{:uri "ent:widget/w1" :label "Widget One"
                               :attributes {:region "north"}}] [])
            ;; the new source ALWAYS extracts the SAME draft set (an unchanged source)
            stable-drafts [{:uri "ent:gadget/g1" :label "Gadget One"
                            :attributes {:region "north"}}]
            model-extract-fn (fn [c _p]
                               (land! c oid stable-drafts [])
                               {:status :success :concept-drafts stable-drafts
                                :relationship-drafts [] :embed-fields []
                                :model-spec {} :candidate-axioms {:axioms []}})
            seams {:survey-fn (fn [_ _] {:status :success :profile {}})
                   :derive-cqs-fn (fn [_ _] {:status :success :competency-questions ["Q1"]})
                   :model-extract-fn model-extract-fn
                   :reconcile-fn (fn [_ _] {:status :success})
                   :axiom-fn (fn [_ _] {:status :success})
                   :embed-fn (fn [_ _] {:status :success})
                   :build-fn (fn [_ _] {:status :complete})
                   :gate-fn (fn [_ _]
                              {:graph-health {:pass-rate 1.0 :unknown-rate 0.0}
                               :evaluated [{:cq-text "Q1" :verdict :pass}]
                               :cq-verdict [{:cq-text "Q1" :verdict :pass}]})}
            run! (fn [] (ce/run-central-evolver!
                         (merge ctx {})
                         (merge {:ontology-id oid :sources [{:type :csv :path "b"}] :goal "g"}
                                seams)))
            ;; FIRST maintain pass — lands the gadget alongside the widget.
            _ (run!)
            after-1 (count-concepts ctx oid)
            uris-1 (concept-uris ctx oid)
            ;; SECOND maintain pass over the UNCHANGED source.
            _ (run!)
            after-2 (count-concepts ctx oid)
            uris-2 (concept-uris ctx oid)]
        (is (= after-1 after-2)
            "an unchanged source re-run does NOT duplicate (concept count stable)")
        (is (= uris-1 uris-2)
            "the SAME concept URIs — no new node minted for an unchanged source")
        (is (= 1 (count (filter #(= "ent:gadget/g1" %) (map :uri (rm/get-concepts ctx {:ontology-id oid})))))
            "exactly ONE gadget node after two identical passes (reconcile-not-duplicate)")))))

;; =============================================================================
;; 3. MAINTAIN reads the EXISTING graph (not greenfield-only) — new attr links to
;;    an existing entity's attribute (EB5 attribute granularity), via the REAL
;;    reconcile (no stub on the reconcile seam).
;; =============================================================================

(deftest maintain-links-new-attribute-to-existing-entity-attribute-test
  (testing "the maintain run reads the EXISTING graph: a NEW entity the new source
            introduces carries an attribute that connects to an EXISTING entity's
            attribute (EB5 attribute-granularity, the genuinely-new EB5 logic) —
            proven with the REAL reconcile seam (default delegate stubbed only for
            the landing draft set)."
    (h/with-async-test-context [ctx]
      (let [oid (random-uuid)
            ;; EXISTING graph: a widget carrying :region "north"
            _ (land! ctx oid [{:uri "ent:widget/w1" :label "Widget One"
                               :attributes {:region "north"}}] [])
            ;; the new source extracts a NEW gadget carrying the SAME :region value
            new-drafts [{:uri "ent:gadget/g1" :label "Gadget One"
                         :attributes {:region "north" :model "x9"}}]
            ;; run the maintain composition, but use the REAL reconcile orchestration
            ;; for the new drafts (so the attribute-link pass actually runs against
            ;; the existing graph). Capture the report.
            captured (atom nil)
            model-extract-fn (fn [_ _]
                               {:status :success :concept-drafts new-drafts
                                :relationship-drafts [] :embed-fields []
                                :model-spec {} :candidate-axioms {:axioms []}})
            reconcile-fn (fn [c {:keys [concept-drafts]}]
                           (let [report (land! c oid concept-drafts [])]
                             (reset! captured report)
                             {:status :success :reconcile-report report}))
            result (ce/run-central-evolver!
                    ctx {:ontology-id oid :sources [{:type :csv :path "b"}] :goal "g"
                         :survey-fn (fn [_ _] {:status :success :profile {}})
                         :derive-cqs-fn (fn [_ _] {:status :success :competency-questions ["Q1"]})
                         :model-extract-fn model-extract-fn
                         :reconcile-fn reconcile-fn
                         :axiom-fn (fn [_ _] {:status :success})
                         :embed-fn (fn [_ _] {:status :success})
                         :build-fn (fn [_ _] {:status :complete})
                         :gate-fn (fn [_ _]
                                    {:graph-health {:pass-rate 1.0 :unknown-rate 0.0}
                                     :evaluated [{:cq-text "Q1" :verdict :pass}]
                                     :cq-verdict [{:cq-text "Q1" :verdict :pass}]})})
            links (get-in @captured [:attribute-reconcile :links])
            region-link (first (filter #(and (= "ent:gadget/g1" (:new-uri %))
                                             (= :region (:new-attr-key %))
                                             (= "ent:widget/w1" (:existing-uri %)))
                                       links))]
        (is (= :maintain (:mode result)) "the maintain composition ran")
        (is (some? region-link)
            "the NEW gadget's :region attribute LINKS to the EXISTING widget's :region
             attribute (EB5 attribute-granularity against the existing graph)")
        (is (= :same-value (:kind region-link))
            "the link is a same-value link (both carry :region \"north\")")
        (is (= "north" (:value region-link)))))))

;; =============================================================================
;; 4. MAINTAIN honest failure surfacing — a subbehavior failure surfaces honestly
;;    on the maintain arm too (#5; no false green).
;; =============================================================================

(deftest maintain-surfaces-subbehavior-failure-honestly-test
  (testing "a subbehavior failure on the maintain arm surfaces honestly as
            :failed-at-<step> (no false green, no silent partial) — the maintain arm
            shares greenfield's honest-failure surfacing"
    (h/with-async-test-context [ctx]
      (let [oid (random-uuid)
            _ (land! ctx oid [{:uri "ent:widget/w1" :label "Widget One"}] [])
            derive-calls (atom 0)
            result (ce/run-central-evolver!
                    ctx {:ontology-id oid :sources [{:type :csv :path "b"}] :goal "g"
                         :survey-fn (fn [_ _] {:status :failed :error "survey blew up"})
                         :derive-cqs-fn (fn [_ _] (swap! derive-calls inc) {:status :success})})]
        (is (= :failed-at-survey (:status result))
            "a survey failure on maintain surfaces honestly")
        (is (= :maintain (get-in result [:branch-points :greenfield-vs-maintain :selected]))
            "the failure still records the maintain branch")
        (is (zero? @derive-calls) "no downstream step runs after the failure")))))
