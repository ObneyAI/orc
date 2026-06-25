(ns ai.obney.orc.ontology.eb10-central-evolver-subbehavior-test
  "EB10 — the CENTRAL evolver loop (the keystone). These durable, HERMETIC
   brick-gate tests lock the load-bearing STRUCTURE + the CQ-objective LOOP LOGIC
   the EB10 prototype + live verify validated, through the PUBLIC surface — never
   prompt-string internals. The LOOP LOGIC is tested DETERMINISTICALLY (Discipline
   #4/#6 backbone): the gate verdict + the ROUTE decision + the subbehavior close
   are STUBBED via injected seams so the adaptive route-and-close branching is
   exercised WITHOUT a live LLM/ColBERT (the live-LLM proof is the EB10 prototype /
   live verify on the on-demand lane).

   What is locked here (the EB10 acceptance):
     - the CENTRAL tree is FIXED-COMPOSED via `:delegate`: the per-source Model →
       Extract pipeline is a real ORC sheet whose two nodes `:delegate` to the
       Model + Extract subbehaviors (registry name → deterministic id, idempotent);
     - the ROUTE is ONE adaptive `:llm`/decision node with `:reasoning` FIRST (#13),
       a concrete decision-keyword schema (NOT a phrase table — #7/#12),
       source-agnostic;
     - the CQ-OBJECTIVE LOOP (re-housed DT8): fail → route → focal close → re-gate →
       PASS; an unanswerable / :terminate-routed CQ → honest terminate (no spin, no
       false-green, surfaced reason); budget-bounded (always terminates);
     - the greenfield-vs-maintain `:condition` selects correctly (DT9 reuse): a
       populated graph → the MAINTAIN arm (EB11 flipped this from the deferred stub
       to the real evolutionary-maintain composition; full EB11 behavior is in the
       eb11-maintain-evolutionary-test ns);
     - `gate-passed?` REUSES the skeleton exit-criterion (no fork).

   Domain-agnostic fixtures — no education/CIP/SOC specifics (Discipline #12)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.read-models :as orm]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.deterministic-skeleton :as skeleton]
            [ai.obney.orc.ontology.core.model-subbehavior :as model]
            [ai.obney.orc.ontology.core.extract-subbehavior :as extract]
            [ai.obney.orc.ontology.core.central-evolver :as ce]
            [malli.core :as m]))

;; =============================================================================
;; The fixed-composed Model → Extract pipeline sheet (the :delegate composition)
;; =============================================================================

(deftest pipeline-registry-name-resolves-to-deterministic-idempotent-sheet-id-test
  (testing "the Model→Extract pipeline registers by name → a deterministic,
            idempotent sheet-id (the EB1 registry pattern), source-agnostic"
    (h/with-async-test-context [ctx]
      (let [{id-1 :pipeline-sheet-id} (ce/register-pipeline-sheets! ctx {})
            looked-up (ce/model-extract-pipeline-sheet-id-for)
            {id-2 :pipeline-sheet-id} (ce/register-pipeline-sheets! ctx {})]
        (is (= id-1 looked-up)
            "name→sheet-id lookup matches the registered pipeline sheet-id")
        (is (= id-1 id-2)
            "re-registering an unchanged pipeline is idempotent (same id)")
        (is (= "ontology-central/model-extract-pipeline@v1" ce/model-extract-pipeline-name)
            "the pipeline name carries no source/medium/path tag (source-agnostic)")))))

(deftest pipeline-composes-model-then-extract-via-delegate-test
  (testing "the per-source pipeline is two :delegate nodes — Model then Extract —
            and Extract :delegate reads the Model :delegate's :model-spec write
            (the :writes→:reads composition the central tree wires)"
    (h/with-async-test-context [ctx]
      (let [{:keys [pipeline-sheet-id model-sheet-id extract-sheet-id]}
            (ce/register-pipeline-sheets! ctx {})
            nodes (vals (orm/get-nodes-by-id ctx pipeline-sheet-id))
            delegates (filter #(= :delegate (:type %)) nodes)
            by-name (into {} (map (juxt :name identity)) delegates)
            model-d (get by-name "delegate-model")
            extract-d (get by-name "delegate-extract")]
        (is (= 2 (count delegates)) "exactly two :delegate nodes (Model, Extract)")
        (is (some? model-d) "a Model delegate node")
        (is (some? extract-d) "an Extract delegate node")
        ;; the delegates point at the REAL subbehavior sheet-ids (composition, not fork)
        (is (= model-sheet-id (:target-sheet-id model-d))
            "the Model delegate targets the registered Model subbehavior sheet")
        (is (= extract-sheet-id (:target-sheet-id extract-d))
            "the Extract delegate targets the registered Extract subbehavior sheet")
        ;; the :writes→:reads wiring: Model writes :model-spec; Extract reads it.
        (is (contains? (set (:writes model-d)) :model-spec)
            "the Model delegate writes :model-spec onto the central child blackboard")
        (is (contains? (set (:reads extract-d)) :model-spec)
            "the Extract delegate reads the :model-spec Model wrote (the composition)")))))

(deftest pipeline-public-contract-test
  (testing "the pipeline's blackboard declares the Model + Extract structured
            schemas (so the contract crosses :delegate parsed) and no :llm node
            lives on the pipeline itself (the subbehaviors own the LLM work)"
    (h/with-async-test-context [ctx]
      (let [{:keys [pipeline-sheet-id]} (ce/register-pipeline-sheets! ctx {})
            nodes (vals (orm/get-nodes-by-id ctx pipeline-sheet-id))]
        ;; the pipeline itself is a pure :delegate composition — NO :llm/:code leaf
        ;; (the Model/Extract subbehaviors carry the LLM work behind :delegate).
        (is (empty? (filter #(and (= :leaf (:type %)) (= :ai (:executor %))) nodes))
            "no :llm leaf on the pipeline sheet (the subbehaviors own the LLM work)")
        (is (= 2 (count (filter #(= :delegate (:type %)) nodes)))
            "the pipeline body is purely the two :delegate composition nodes")))))

;; =============================================================================
;; The ROUTE node — ONE adaptive :llm/decision node, :reasoning FIRST (#13)
;; =============================================================================

(deftest route-node-registry-and-structure-test
  (testing "the ROUTE is ONE :llm node, :reasoning FIRST (#13), reading the failing
            CQ + graph-health, writing a concrete decision keyword; source-agnostic"
    (h/with-async-test-context [ctx]
      (let [sid-1 (ce/register-route-node! ctx {})
            looked-up (ce/route-node-sheet-id-for)
            sid-2 (ce/register-route-node! ctx {})
            nodes (vals (orm/get-nodes-by-id ctx sid-1))
            llm-leaves (filter #(and (= :leaf (:type %)) (= :ai (:executor %))) nodes)
            route (first llm-leaves)]
        (is (= sid-1 looked-up) "route name→id round-trips deterministically")
        (is (= sid-1 sid-2) "re-registering the route node is idempotent")
        (is (= 1 (count llm-leaves)) "exactly ONE adaptive :llm/decision node")
        (is (= [:reasoning :route] (vec (:writes route)))
            "#13 — :reasoning is written FIRST, before the :route decision")
        (is (= [:failing-cq :graph-health] (vec (:reads route)))
            "the route reads the failing CQ + graph-health (NOT a phrase table)")
        (is (= "ontology-central/route-decision@v1" ce/route-node-name)
            "the route name carries no source/domain tag (source-agnostic)")))))

(deftest route-decision-schema-is-the-closing-subbehavior-set-test
  (testing "the route decision space is the closeable subbehaviors + :terminate,
            as a concrete enum schema (so the :llm parses a real keyword, #7)"
    (is (= #{:extract :reconcile :axiom :model :terminate} ce/routable-subbehaviors)
        "the decision space = missing-entity→Extract, missing-link→Reconcile,
         missing-class/attr→Axiom/Model, absent→:terminate")
    (is (= :enum (first ce/route-decision-schema))
        "the :route write is a concrete :enum keyword (NOT :any → parsed, not raw)")
    (doseq [r ce/routable-subbehaviors]
      (is (m/validate ce/route-decision-schema r)
          (str r " is a valid route decision")))
    (is (not (m/validate ce/route-decision-schema :nonsense))
        "an out-of-space keyword is rejected by the schema")))

;; =============================================================================
;; gate-passed? — REUSE the skeleton exit-criterion (no fork)
;; =============================================================================

(deftest gate-passed-reuses-the-skeleton-exit-criterion-test
  (testing "gate-passed? applies build!'s exit-criterion (pass-rate ≥ 0.8 AND
            unknown-rate ≤ 0.3) — the SAME default the deterministic skeleton uses"
    ;; the default thresholds ARE the skeleton's (REUSE, not a forked notion).
    (is (= {:pass-rate-min 0.8 :unknown-rate-max 0.3} skeleton/default-exit-criterion))
    (is (ce/gate-passed? {:pass-rate 0.9 :unknown-rate 0.1} nil)
        "a healthy graph passes the default gate")
    (is (not (ce/gate-passed? {:pass-rate 0.5 :unknown-rate 0.1} nil))
        "a low pass-rate fails the gate")
    (is (not (ce/gate-passed? {:pass-rate 0.9 :unknown-rate 0.5} nil))
        "a high unknown-rate fails the gate (unknown-rate is first-class)")
    (is (ce/gate-passed? {:pass-rate 0.6 :unknown-rate 0.6}
                         {:pass-rate-min 0.5 :unknown-rate-max 0.7})
        "an overriding exit-criterion is honored")))

;; =============================================================================
;; The CQ-OBJECTIVE LOOP — re-housed DT8, but each close re-invokes the ROUTED
;; SUBBEHAVIOR (deterministically stubbed seams).
;; =============================================================================

(defn- land-one! [ctx oid uri label]
  (ontology/compile-discovery-source!
   ctx oid {:status :emitted-drafts
            :emitted-concepts [{:uri uri :label label}]
            :emitted-relationships []}))

;; ---- 1. fail → route :extract → focal close grows graph → re-gate PASSES ----

(deftest failing-cq-routes-to-extract-closes-focally-and-re-gate-passes-test
  (testing "a failing CQ ROUTES to :extract, the focal close grows the graph (NOT a
            full rebuild), and the re-gate then PASSES — the route-and-close"
    (h/with-async-test-context [ctx]
      (let [oid (random-uuid)
            gate-calls (atom 0)
            route-calls (atom 0)
            close-calls (atom 0)
            ;; gate FAILS first, PASSES after the close grows the graph.
            gate-fn (fn [_ctx _p]
                      (swap! gate-calls inc)
                      (if (= 1 @gate-calls)
                        {:graph-health {:pass-rate 0.0 :unknown-rate 0.0}
                         :evaluated [{:cq-text "Q1" :verdict :fail}]
                         :cq-verdict [{:cq-text "Q1" :verdict :fail}]}
                        {:graph-health {:pass-rate 1.0 :unknown-rate 0.0}
                         :evaluated [{:cq-text "Q1" :verdict :pass}]
                         :cq-verdict [{:cq-text "Q1" :verdict :pass}]}))
            ;; the ROUTE maps the gap to :extract (proof the CQ text was passed in).
            route-fn (fn [_ctx {:keys [failing-cq]}]
                       (swap! route-calls inc)
                       (is (= "Q1" failing-cq) "the failing CQ text reaches the route")
                       {:route :extract :reasoning "missing entity — re-extract"})
            ;; the closing model→extract seam GROWS the graph (real landed concept).
            model-extract-fn (fn [c _p]
                               (swap! close-calls inc)
                               (land-one! c oid "concept:x" "X")
                               {:status :success :concept-drafts [{:uri "concept:x"}]
                                :relationship-drafts [] :embed-fields []})
            reconcile-fn (fn [_ _] {:status :success})
            embed-fn (fn [_ _] {:status :success})
            result (ce/cq-objective-loop!
                    ctx {:ontology-id oid :source {:type :csv :path "x"} :goal "g" :profile {}
                         :pipeline-sheet-id (random-uuid) :route-sheet-id (random-uuid)
                         :gate-fn gate-fn :route-fn route-fn
                         :model-extract-fn model-extract-fn
                         :reconcile-fn reconcile-fn :embed-fn embed-fn})]
        (is (= :complete (:status result)) "the re-gate passes after the focal close")
        (is (= :cq-gate-passed (get-in result [:cq-loop :termination-reason])))
        (is (= :extract (get-in result [:cq-loop :history 0 :route]))
            "the failing CQ routed to the Extract subbehavior")
        (is (= 1 @route-calls) "exactly ONE route decision")
        (is (= 1 @close-calls) "exactly ONE focal close (not a full rebuild)")
        (is (empty? (get-in result [:cq-loop :unanswerable-cqs]))
            "no CQ surfaced unanswerable — the close worked")))))

;; ---- 2. an unanswerable CQ (route :terminate) → honest terminate (no spin) ----

(deftest unanswerable-cq-routes-to-terminate-and-stops-honestly-test
  (testing "a CQ the ROUTE judges genuinely-absent-in-source (:terminate) is
            surfaced UNANSWERABLE and the loop stops honestly — NOT :complete, NOT
            an infinite loop, and it does NOT attempt a close for it"
    (h/with-async-test-context [ctx]
      (let [oid (random-uuid)
            route-calls (atom 0)
            close-calls (atom 0)
            gate-fn (fn [_ _]
                      {:graph-health {:pass-rate 0.0 :unknown-rate 1.0}
                       :evaluated [{:cq-text "Impossible" :verdict :unknown}]
                       :cq-verdict [{:cq-text "Impossible" :verdict :unknown}]})
            route-fn (fn [_ _] (swap! route-calls inc)
                       {:route :terminate :reasoning "the source genuinely lacks this"})
            ;; the close seam must NEVER fire on a :terminate route.
            model-extract-fn (fn [_ _] (swap! close-calls inc) {:status :success})
            result (ce/cq-objective-loop!
                    ctx {:ontology-id oid :source {:type :csv :path "x"} :goal "g" :profile {}
                         :evolver-config {:max-iterations 5}
                         :pipeline-sheet-id (random-uuid) :route-sheet-id (random-uuid)
                         :gate-fn gate-fn :route-fn route-fn
                         :model-extract-fn model-extract-fn})]
        (is (= :failed-cq (:status result)) "NOT false-green: stays :failed-cq")
        (is (= :all-remaining-unanswerable (get-in result [:cq-loop :termination-reason])))
        (is (= ["Impossible"] (get-in result [:cq-loop :unanswerable-cqs]))
            "the unanswerable CQ is SURFACED honestly (the reason)")
        (is (zero? @close-calls) "a :terminate route attempts NO close")
        (is (= 1 @route-calls)
            "NO spin: routes the CQ ONCE, learns it is unanswerable, stops (would
             have hit 5 if it spun)")))))

(deftest close-that-grows-nothing-marks-cq-unanswerable-test
  (testing "a focal close that grows the graph by NOTHING (the source genuinely
            lacks the data) marks the CQ unanswerable and stops chasing it — the
            honest-negative even when the ROUTE chose a closeable subbehavior"
    (h/with-async-test-context [ctx]
      (let [oid (random-uuid)
            close-calls (atom 0)
            gate-fn (fn [_ _]
                      {:graph-health {:pass-rate 0.0 :unknown-rate 1.0}
                       :evaluated [{:cq-text "NoData" :verdict :fail}]
                       :cq-verdict [{:cq-text "NoData" :verdict :fail}]})
            route-fn (fn [_ _] {:route :extract :reasoning "try re-extract"})
            ;; the close runs but the graph does NOT grow (no real data to extract).
            model-extract-fn (fn [_ _] (swap! close-calls inc)
                               {:status :success :concept-drafts [] :relationship-drafts []
                                :embed-fields []})
            reconcile-fn (fn [_ _] {:status :success})
            embed-fn (fn [_ _] {:status :success})
            result (ce/cq-objective-loop!
                    ctx {:ontology-id oid :source {:type :csv :path "x"} :goal "g" :profile {}
                         :evolver-config {:max-iterations 5}
                         :pipeline-sheet-id (random-uuid) :route-sheet-id (random-uuid)
                         :gate-fn gate-fn :route-fn route-fn
                         :model-extract-fn model-extract-fn
                         :reconcile-fn reconcile-fn :embed-fn embed-fn})]
        (is (= :failed-cq (:status result)) "NOT false-green")
        (is (= :all-remaining-unanswerable (get-in result [:cq-loop :termination-reason])))
        (is (= ["NoData"] (get-in result [:cq-loop :unanswerable-cqs])))
        (is (= 1 @close-calls)
            "NO spin: attempts the close ONCE, learns the graph did not grow, stops")))))

;; ---- 3. budget exhaustion → terminate with the reason (always terminates) ----

(deftest loop-is-budget-bounded-and-terminates-with-the-reason-test
  (testing "even when every close grows the graph but the gate never passes, the
            loop terminates at :max-iterations with :budget-exhausted (no infinite)"
    (h/with-async-test-context [ctx]
      (let [oid (random-uuid)
            close-calls (atom 0)
            n (atom 0)
            ;; gate NEVER passes; each close grows the graph (so NOT unanswerable) →
            ;; the loop would spin forever WITHOUT the budget bound.
            gate-fn (fn [_ _]
                      {:graph-health {:pass-rate 0.1 :unknown-rate 0.0}
                       :evaluated [{:cq-text "Hard" :verdict :fail}]
                       :cq-verdict [{:cq-text "Hard" :verdict :fail}]})
            route-fn (fn [_ _] {:route :extract :reasoning "keep trying"})
            model-extract-fn (fn [c _p]
                               (swap! close-calls inc)
                               ;; each close lands a NEW concept (graph grows every time)
                               (land-one! c oid (str "concept:" (swap! n inc)) "C")
                               {:status :success :concept-drafts [{:uri "c"}]
                                :relationship-drafts [] :embed-fields []})
            reconcile-fn (fn [_ _] {:status :success})
            embed-fn (fn [_ _] {:status :success})
            result (ce/cq-objective-loop!
                    ctx {:ontology-id oid :source {:type :csv :path "x"} :goal "g" :profile {}
                         :evolver-config {:max-iterations 2}
                         :pipeline-sheet-id (random-uuid) :route-sheet-id (random-uuid)
                         :gate-fn gate-fn :route-fn route-fn
                         :model-extract-fn model-extract-fn
                         :reconcile-fn reconcile-fn :embed-fn embed-fn})]
        (is (= :failed-cq (:status result)) "NOT false-green")
        (is (= :budget-exhausted (get-in result [:cq-loop :termination-reason])))
        (is (= 2 (get-in result [:cq-loop :iterations])) "stopped at max-iterations")
        (is (= 2 @close-calls) "exactly max-iterations closes, then stop")))))

(deftest loop-no-op-when-gate-already-passes-test
  (testing "if the initial gate already passes, the loop is a no-op pass-through —
            no route, no close (the OBJECTIVE was met before the loop)"
    (h/with-async-test-context [ctx]
      (let [route-calls (atom 0)
            gate-fn (fn [_ _]
                      {:graph-health {:pass-rate 1.0 :unknown-rate 0.0}
                       :evaluated [{:cq-text "Q" :verdict :pass}]
                       :cq-verdict [{:cq-text "Q" :verdict :pass}]})
            route-fn (fn [_ _] (swap! route-calls inc) {:route :extract})
            result (ce/cq-objective-loop!
                    ctx {:ontology-id (random-uuid) :source {:type :csv :path "x"}
                         :goal "g" :profile {}
                         :pipeline-sheet-id (random-uuid) :route-sheet-id (random-uuid)
                         :gate-fn gate-fn :route-fn route-fn
                         :model-extract-fn (fn [_ _] (throw (ex-info "close MUST NOT run" {})))})]
        (is (= :complete (:status result)))
        (is (= :cq-gate-passed (get-in result [:cq-loop :termination-reason])))
        (is (zero? @route-calls) "no route when the gate already passed")
        (is (zero? (get-in result [:cq-loop :iterations])))))))

;; =============================================================================
;; greenfield-vs-maintain :condition (DT9 reuse) selects correctly
;; =============================================================================

(deftest greenfield-runs-the-full-evolver-test
  (testing "GREENFIELD (no existing graph): the central evolver runs the full
            survey→derive→loop path (here with all subbehavior seams stubbed)"
    (h/with-async-test-context [ctx]
      (let [oid (random-uuid)
            survey-fn (fn [_ _] {:status :success :profile {:entity-candidates ["thing"]}})
            derive-cqs-fn (fn [_ _] {:status :success :competency-questions ["Q1"]})
            model-extract-fn (fn [c _p]
                               (land-one! c oid "concept:thing" "Thing")
                               {:status :success :concept-drafts [{:uri "concept:thing"}]
                                :relationship-drafts [] :embed-fields [] :model-spec {}
                                :candidate-axioms {:axioms []}})
            reconcile-fn (fn [_ _] {:status :success})
            axiom-fn (fn [_ _] {:status :success})
            embed-fn (fn [_ _] {:status :success})
            build-fn (fn [_ _] {:status :complete})
            gate-fn (fn [_ _]
                      {:graph-health {:pass-rate 1.0 :unknown-rate 0.0}
                       :evaluated [{:cq-text "Q1" :verdict :pass}]
                       :cq-verdict [{:cq-text "Q1" :verdict :pass}]})
            result (ce/run-central-evolver!
                    ctx {:ontology-id oid :sources [{:type :csv :path "x"}] :goal "g"
                         :survey-fn survey-fn :derive-cqs-fn derive-cqs-fn
                         ;; GC-6 — the synthesize-vocab seam (new mandatory STEP 3.5).
                         :synthesize-vocab-fn (fn [_ _] {:status :success :vocabulary {}})
                         :model-extract-fn model-extract-fn :reconcile-fn reconcile-fn
                         :axiom-fn axiom-fn :embed-fn embed-fn
                         :build-fn build-fn :gate-fn gate-fn})]
        (is (false? (get-in result [:branch-points :greenfield-vs-maintain :taken?]))
            "greenfield: the maintain branch is NOT taken")
        (is (= :greenfield (get-in result [:branch-points :greenfield-vs-maintain :selected])))
        (is (= :complete (:status result)) "the full greenfield evolver completed")
        (is (= ["Q1"] (:competency-questions result))
            "the derived CQs are surfaced (HITL)")
        (is (= [{:entity-candidates ["thing"]}] (:survey-profiles result))
            "the per-source profiles are surfaced")))))

(deftest maintain-selects-the-maintain-arm-and-runs-the-real-composition-test
  (testing "MAINTAIN (a graph already exists for the ontology-id): the front-of-tree
            condition selects MAINTAIN (DT9 reuse) and — per EB11 — runs the REAL
            evolutionary-maintain composition against the existing graph (NOT the
            deferred stub). The full EB11 maintain behavior is covered in the
            eb11-maintain-evolutionary-test ns; here we lock the EB10 branch SELECTION
            + that the maintain arm runs the SAME subbehavior pipeline (no stub)."
    (h/with-async-test-context [ctx]
      (let [oid (random-uuid)
            _ (land-one! ctx oid "concept:pre-existing" "Pre-existing")
            survey-calls (atom 0)
            result (ce/run-central-evolver!
                    ctx {:ontology-id oid :sources [{:type :csv :path "x"}] :goal "g"
                         :survey-fn (fn [_ _] (swap! survey-calls inc)
                                      {:status :success :profile {}})
                         :derive-cqs-fn (fn [_ _] {:status :success :competency-questions ["Q1"]})
                         ;; GC-6 — the synthesize-vocab seam (new mandatory STEP 3.5).
                         :synthesize-vocab-fn (fn [_ _] {:status :success :vocabulary {}})
                         :model-extract-fn (fn [_ _] {:status :success :concept-drafts []
                                                      :relationship-drafts [] :embed-fields []
                                                      :model-spec {} :candidate-axioms {:axioms []}})
                         :reconcile-fn (fn [_ _] {:status :success})
                         :axiom-fn (fn [_ _] {:status :success})
                         :embed-fn (fn [_ _] {:status :success})
                         :build-fn (fn [_ _] {:status :complete})
                         :gate-fn (fn [_ _]
                                    {:graph-health {:pass-rate 1.0 :unknown-rate 0.0}
                                     :evaluated [{:cq-text "Q1" :verdict :pass}]
                                     :cq-verdict [{:cq-text "Q1" :verdict :pass}]})})]
        (is (not= :maintain-deferred (:status result))
            "EB11: an existing graph runs the REAL maintain composition, NOT the deferred stub")
        (is (= :maintain (get-in result [:branch-points :greenfield-vs-maintain :selected]))
            "the front-of-tree condition still SELECTS maintain (DT9 reuse)")
        (is (= :maintain (:mode result)) "the maintain mode is tagged")
        (is (pos? @survey-calls)
            "the maintain arm RUNS the subbehavior pipeline (EB11 — no longer a stub)")))))

(deftest maintain-mode-override-runs-the-maintain-composition-test
  (testing "the :mode override forces maintain even on an empty graph (HITL/test
            override of the front-of-tree condition — DT9 reuse). EB11: the maintain
            arm runs the real composition (the override drives it, no stub)."
    (h/with-async-test-context [ctx]
      (let [result (ce/run-central-evolver!
                    ctx {:ontology-id (random-uuid) :sources [{:type :csv :path "x"}]
                         :goal "g" :mode :maintain
                         :survey-fn (fn [_ _] {:status :success :profile {}})
                         :derive-cqs-fn (fn [_ _] {:status :success :competency-questions ["Q1"]})
                         ;; GC-6 — the synthesize-vocab seam (new mandatory STEP 3.5).
                         :synthesize-vocab-fn (fn [_ _] {:status :success :vocabulary {}})
                         :model-extract-fn (fn [_ _] {:status :success :concept-drafts []
                                                      :relationship-drafts [] :embed-fields []
                                                      :model-spec {} :candidate-axioms {:axioms []}})
                         :reconcile-fn (fn [_ _] {:status :success})
                         :axiom-fn (fn [_ _] {:status :success})
                         :embed-fn (fn [_ _] {:status :success})
                         :build-fn (fn [_ _] {:status :complete})
                         :gate-fn (fn [_ _]
                                    {:graph-health {:pass-rate 1.0 :unknown-rate 0.0}
                                     :evaluated [{:cq-text "Q1" :verdict :pass}]
                                     :cq-verdict [{:cq-text "Q1" :verdict :pass}]})})]
        (is (= :maintain (:mode result)) "the override forces the maintain arm")
        (is (= :maintain (get-in result [:branch-points :greenfield-vs-maintain :selected])))
        (is (not= :maintain-deferred (:status result))
            "EB11: the forced maintain arm runs the real composition, not the stub")))))

;; =============================================================================
;; Honest failure surfacing — a subbehavior failure is :failed-at-<step> (#5)
;; =============================================================================

(deftest survey-failure-surfaces-honestly-test
  (testing "a Survey subbehavior failure surfaces as :failed-at-survey carrying the
            error + the failed source — no fabricated downstream steps run (#5)"
    (h/with-async-test-context [ctx]
      (let [derive-calls (atom 0)
            result (ce/run-central-evolver!
                    ctx {:ontology-id (random-uuid) :sources [{:type :csv :path "x"}] :goal "g"
                         :survey-fn (fn [_ _] {:status :failed :error "survey blew up"})
                         :derive-cqs-fn (fn [_ _] (swap! derive-calls inc) {:status :success})})]
        (is (= :failed-at-survey (:status result)))
        (is (= "survey blew up" (:error result)))
        (is (zero? @derive-calls)
            "no downstream step runs after a survey failure (no false green)")))))

(deftest derive-cqs-failure-surfaces-honestly-test
  (testing "a Validate+CQ DERIVE failure surfaces as :failed-at-derive-cqs (#5)"
    (h/with-async-test-context [ctx]
      (let [result (ce/run-central-evolver!
                    ctx {:ontology-id (random-uuid) :sources [{:type :csv :path "x"}] :goal "g"
                         :survey-fn (fn [_ _] {:status :success :profile {}})
                         :derive-cqs-fn (fn [_ _] {:status :failed :error "no CQs"})
                         :model-extract-fn (fn [_ _] (throw (ex-info "must not run" {})))})]
        (is (= :failed-at-derive-cqs (:status result)))
        (is (= "no CQs" (:error result)))))))

;; =============================================================================
;; Input validation — the keystone fails LOUD on a missing scope/sources/goal (#5)
;; =============================================================================

(deftest run-central-evolver-requires-scope-sources-and-goal-test
  (testing "the keystone fails loudly without :ontology-id / :sources / :goal
            (no silent empty-graph default — #5)"
    (h/with-async-test-context [ctx]
      (is (thrown? Exception
                   (ce/run-central-evolver! ctx {:sources [{:type :csv :path "x"}] :goal "g"}))
          "missing :ontology-id throws")
      (is (thrown? Exception
                   (ce/run-central-evolver! ctx {:ontology-id (random-uuid) :goal "g"}))
          "missing :sources throws")
      (is (thrown? Exception
                   (ce/run-central-evolver! ctx {:ontology-id (random-uuid)
                                                 :sources [{:type :csv :path "x"}]}))
          "missing :goal throws"))))
