(ns ai.obney.orc.ontology.gm1-graph-context-test
  "GM-1 — graph-context preview into the Model + the grain/reify prompt block.

   These durable, HERMETIC brick-gate tests lock the load-bearing CONTRACT + WIRING
   through the PUBLIC surface — never mutating the model-spec (the GC-16 failure this
   slice supersedes), never baking a domain column. The LIVE proof (pseo earnings as
   Observations w/ resolved dimensions; O*NET flat; clean-source parity at the 447
   baseline) lives on the prototype driver + the orchestrator's /inspect-orc, per the
   gate-hygiene rule (a test driving the real LLM / real source files is INTEGRATION).

   What is locked here:
     - CYCLE 1 (pure snapshot): `snapshot-from` lists existing entity TYPES (by URI
       scheme) with keying-sample + attribute-fields + a bounded content sample +
       predicate usage; empty graph → empty/degraded snapshot (no crash);
       deterministic (same collections → same snapshot).
     - CYCLE 2 (threading): the central evolver computes the snapshot per source and
       threads it into EVERY per-source model-extract-fn as PARSED data (C1); the
       Model sheet :reads :graph-context and declares it [:maybe schema].
     - CYCLE 3 (prompt assembly): the grain/reify block is present in the Model
       prompt (multi-subject → Observation; single-subject → flat; attach to existing);
       domain-agnostic (#12); #13 reasoning-first preserved; the empty-graph path still
       assembles a valid prompt."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.read-models :as orm]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.graph-context-snapshot :as gcs]
            [ai.obney.orc.ontology.core.model-subbehavior :as model]
            [ai.obney.orc.ontology.core.central-evolver :as ce]
            [malli.core :as m]))

;; ===========================================================================
;; CYCLE 1 — the PURE graph-context snapshot (deterministic from collections)
;; ===========================================================================

;; Synthetic entity types X/Y (NO CIP/SOC/pseo — #12). Two schemes, a couple of
;; predicates, some numeric attributes on one type.
(def ^:private synthetic-concepts
  [{:uri "widget/1" :label "Widget One" :attributes {"score" 10 "rank" 2}}
   {:uri "widget/2" :label "Widget Two" :attributes {"score" 20}}
   {:uri "widget/3" :label "Widget Three" :attributes {}}
   {:uri "gadget/a" :label "Gadget A" :attributes {"gcode" "GA"}}
   {:uri "gadget/b" :label "Gadget B" :attributes {"gcode" "GB"}}])

(def ^:private synthetic-relationships
  [{:source-uri "widget/1" :target-uri "gadget/a" :predicate "made-of"}
   {:source-uri "widget/2" :target-uri "gadget/a" :predicate "made-of"}
   {:source-uri "widget/1" :target-uri "gadget/b" :predicate "relates-to"}])

(deftest cycle1-snapshot-lists-entity-types-with-keying-and-predicates-test
  (testing "the snapshot lists existing entity TYPES (by URI scheme) with a
            keying-sample + attribute-fields, plus predicate usage + a content sample"
    (let [snap (gcs/snapshot-from synthetic-concepts synthetic-relationships)
          types (:entity-types snap)
          by-type (into {} (map (juxt :type identity) types))]
      (is (= #{"widget" "gadget"} (set (map :type types)))
          "the two URI schemes surface as the two existing entity TYPES")
      (is (= 3 (:count (by-type "widget"))) "widget count reflects the 3 widget concepts")
      (is (= 2 (:count (by-type "gadget"))) "gadget count reflects the 2 gadget concepts")
      (is (every? #(str/starts-with? % "widget/") (:uri-keying-sample (by-type "widget")))
          "the widget keying-sample is real existing widget URIs (the keying shape)")
      (is (contains? (set (:attribute-fields (by-type "widget"))) "score")
          "the numeric attribute field on widget concepts surfaces (so the Model sees existing measures)")
      (is (= #{"made-of" "relates-to"} (set (map :predicate (:predicates snap))))
          "the predicates already in the graph are listed")
      (is (= 2 (:count (first (:predicates snap))))
          "predicate usage counts are surfaced, sorted by count (made-of=2 first)")
      (is (seq (:content-sample snap)) "a non-empty content sample of existing concepts")
      (is (= 5 (:concept-count snap)) "concept-count reflects the whole graph")
      (is (= 3 (:relationship-count snap)) "relationship-count reflects the whole graph")
      (is (m/validate gcs/graph-context-schema snap)
          "the snapshot validates the locked C1 structured schema"))))

(deftest cycle1-empty-graph-degrades-gracefully-test
  (testing "an EMPTY graph (the first source) yields an empty/degraded snapshot — no
            crash — so the empty-graph first-source path still runs"
    (let [snap (gcs/snapshot-from [] [])]
      (is (= [] (:entity-types snap)) "no entity types")
      (is (= [] (:predicates snap)) "no predicates")
      (is (= [] (:content-sample snap)) "no content sample")
      (is (= 0 (:concept-count snap)))
      (is (= 0 (:relationship-count snap)))
      (is (m/validate gcs/graph-context-schema snap)
          "the empty/degraded snapshot still validates the schema"))))

(deftest cycle1-snapshot-is-deterministic-test
  (testing "the same collections yield the SAME snapshot (deterministic — a pure
            function of the projection state)"
    (is (= (gcs/snapshot-from synthetic-concepts synthetic-relationships)
           (gcs/snapshot-from synthetic-concepts synthetic-relationships))
        "byte-identical across invocations")
    ;; order-independence of the INPUT collection (projection vals order is not
    ;; contractual) — a shuffled input yields the same snapshot.
    (is (= (gcs/snapshot-from synthetic-concepts synthetic-relationships)
           (gcs/snapshot-from (reverse synthetic-concepts) (reverse synthetic-relationships)))
        "input order does not change the snapshot")))

(deftest cycle1-schema-is-structured-not-bare-map-test
  (testing "the :graph-context schema is a structured [:map …] (the :llm-node C1 fix),
            so it crosses :delegate parsed; a STRING does not validate"
    (is (= :map (first gcs/graph-context-schema))
        ":graph-context is a structured [:map …] (not a bare :map / :any)")
    (is (= :graph-context gcs/graph-context-key))
    (is (not (m/validate gcs/graph-context-schema "a json string"))
        "a STRING does not validate the structured graph-context schema")))

;; ===========================================================================
;; CYCLE 2 — threading: the Model sheet reads :graph-context; the central evolver
;; computes the snapshot per source + threads it into EVERY model-extract-fn as
;; PARSED data (not a string).
;; ===========================================================================

(deftest cycle2-model-node-reads-graph-context-test
  (testing "the Model :llm node now reads :graph-context (in addition to
            :goal/:profile/:vocabulary) and the sheet blackboard declares it
            [:maybe graph-context-schema]"
    (h/with-async-test-context [ctx]
      (let [sid (model/register-model-subbehavior! ctx {})
            llm (first (filter #(= :ai (:executor %)) (vals (orm/get-nodes-by-id ctx sid))))]
        (is (= [:goal :profile :vocabulary :graph-context] (vec (:reads llm)))
            "the Model node reads :goal :profile :vocabulary :graph-context")
        (is (= :reasoning (first (:writes llm)))
            "#13 — :reasoning still written FIRST (unchanged)")))))

(deftest cycle2-pipeline-threads-graph-context-into-every-model-extract-test
  (testing "run-central-evolver! computes the graph-context PER SOURCE and threads it
            into EVERY per-source model-extract-fn call as PARSED data (C1 — a map,
            not a string)"
    (h/with-async-test-context [ctx]
      (let [oid (random-uuid)
            captured-gc (atom [])
            the-snapshot {:entity-types [{:type "thing" :count 2
                                          :uri-keying-sample ["thing/1"]
                                          :attribute-fields ["m"]}]
                          :predicates [{:predicate "p" :count 1}]
                          :content-sample [{:uri "thing/1" :label "T" :type "thing"}]
                          :concept-count 2 :relationship-count 1}
            ;; stub the graph-context step so the assertion is deterministic (the REAL
            ;; snapshot fn is proven pure by CYCLE 1; here we lock the WIRING).
            graph-context-fn (fn [_ _] the-snapshot)
            model-extract-fn (fn [c {:keys [graph-context]}]
                               (swap! captured-gc conj graph-context)
                               (ontology/compile-discovery-source!
                                c oid {:status :emitted-drafts
                                       :emitted-concepts [{:uri "concept:x" :label "X"}]
                                       :emitted-relationships []})
                               {:status :success :concept-drafts [{:uri "concept:x"}]
                                :relationship-drafts [] :embed-fields [] :model-spec {}
                                :candidate-axioms {:axioms []}})
            result (ce/run-central-evolver!
                    ctx {:ontology-id oid
                         :sources [{:type :csv :path "A"} {:type :csv :path "B"}]
                         :goal "g"
                         :survey-fn (fn [_ {:keys [source]}]
                                      {:status :success
                                       :profile {:entity-candidates [(str "e-" (:path source))]}})
                         :derive-cqs-fn (fn [_ _] {:status :success :competency-questions ["Q1"]})
                         :synthesize-vocab-fn (fn [_ _] {:status :success :vocabulary {:canonical-entity-types []}})
                         :graph-context-fn graph-context-fn
                         :model-extract-fn model-extract-fn
                         :reconcile-fn (fn [_ _] {:status :success})
                         :axiom-fn (fn [_ _] {:status :success})
                         :embed-fn (fn [_ _] {:status :success})
                         :build-fn (fn [_ _] {:status :complete})
                         :gate-fn (fn [_ _]
                                    {:graph-health {:pass-rate 1.0 :unknown-rate 0.0}
                                     :evaluated [{:cq-text "Q1" :verdict :pass}]
                                     :cq-verdict [{:cq-text "Q1" :verdict :pass}]})})]
        (is (= :complete (:status result)) "the full pipeline completed")
        (is (= 2 (count @captured-gc)) "model-extract ran once per source")
        (is (every? map? @captured-gc)
            "every per-source model-extract received the graph-context as PARSED data (a map, not a string)")
        (is (every? #(= the-snapshot %) @captured-gc)
            "EVERY per-source model-extract-fn received the computed :graph-context snapshot")))))

(deftest cycle2-empty-graph-context-still-runs-test
  (testing "when the graph-context step yields the empty/degraded snapshot (the first
            source, empty graph), the pipeline STILL runs (the [:maybe …] path)"
    (h/with-async-test-context [ctx]
      (let [oid (random-uuid)
            got (atom nil)
            result (ce/run-central-evolver!
                    ctx {:ontology-id oid
                         :sources [{:type :csv :path "solo"}] :goal "g"
                         :survey-fn (fn [_ _] {:status :success :profile {:entity-candidates ["t"]}})
                         :derive-cqs-fn (fn [_ _] {:status :success :competency-questions ["Q1"]})
                         :synthesize-vocab-fn (fn [_ _] {:status :success :vocabulary {:canonical-entity-types []}})
                         ;; the REAL snapshot fn over an empty graph → the degraded snapshot.
                         :graph-context-fn gcs/graph-context-snapshot
                         :model-extract-fn (fn [c {:keys [graph-context]}]
                                             (reset! got graph-context)
                                             (ontology/compile-discovery-source!
                                              c oid {:status :emitted-drafts
                                                     :emitted-concepts [{:uri "concept:x" :label "X"}]
                                                     :emitted-relationships []})
                                             {:status :success :concept-drafts [{:uri "concept:x"}]
                                              :relationship-drafts [] :embed-fields [] :model-spec {}
                                              :candidate-axioms {:axioms []}})
                         :reconcile-fn (fn [_ _] {:status :success})
                         :axiom-fn (fn [_ _] {:status :success})
                         :embed-fn (fn [_ _] {:status :success})
                         :build-fn (fn [_ _] {:status :complete})
                         :gate-fn (fn [_ _] {:graph-health {:pass-rate 1.0 :unknown-rate 0.0}
                                             :evaluated [{:cq-text "Q1" :verdict :pass}]
                                             :cq-verdict [{:cq-text "Q1" :verdict :pass}]})})]
        (is (= :complete (:status result)) "the empty-graph first-source path completed")
        (is (map? @got) "the first source still received a (degraded) graph-context map")
        (is (= 0 (:concept-count @got)) "the first source's graph-context is the empty snapshot")))))

;; ===========================================================================
;; CYCLE 3 — the grain/reify prompt block (assembly + domain-agnostic + #13)
;; ===========================================================================

(deftest cycle3-model-prompt-carries-grain-reify-block-test
  (testing "the Model prompt carries the GM-1 grain/reify block — measures of a grain
            reify as Observations IFF multi-subject-qualified; single-subject → flat;
            attach to existing entities from graph-context (no duplicates)"
    (let [p (str/lower-case (model/model-prompt))]
      (is (str/includes? p "graph-context")
          "the prompt references the graph-context preview input")
      (is (str/includes? p "observation")
          "the prompt teaches reifying as an Observation")
      (is (str/includes? p "measure")
          "the prompt frames numeric columns as measures")
      (is (str/includes? p "grain")
          "the prompt frames the grain")
      (is (or (str/includes? p "more than one") (str/includes? p "multiple"))
          "the discriminator: more than one subject dimension")
      (is (str/includes? p "dimension")
          "the prompt speaks in terms of subject dimensions")
      (is (or (str/includes? p "single subject") (str/includes? p "single-subject")
              (str/includes? p "one subject") (str/includes? p "one entity"))
          "single-subject measures stay flat attributes")
      (is (or (str/includes? p "attach") (str/includes? p "do not") (str/includes? p "duplicate"))
          "attach to existing entities; do not mint duplicates"))))

(deftest cycle3-round2-reify-precision-guards-test
  (testing "ROUND-2 tuning: the reify EXCEPTION is narrow + high-bar — (a) a measure-
            less PAIRING (mapping/crosswalk) is an EDGE, never a node; (b) reification
            is the RARE exception with a hard flat DEFAULT + a when-in-doubt-don't rule;
            (c) a single-subject multi-numeric entity + a wide-vs-long LABEL column stay
            FLAT. These are the guards against the 447/338 → 701/992 over-reification."
    (let [p (str/lower-case (model/model-prompt))]
      ;; (a) a two-entity pairing without measures is an EDGE, not a node — the fix for
      ;; the crosswalk-reified-into-nodes regression.
      (is (str/includes? p "edge")
          "the prompt says a relationship between two entities is an EDGE")
      (is (or (str/includes? p "mapping") (str/includes? p "crosswalk")
              (str/includes? p "pairing") (str/includes? p "correspondence"))
          "the prompt names the pairing/mapping/crosswalk case as edges")
      (is (str/includes? p "never mint a node")
          "the prompt forbids minting a node for the pairing itself")
      ;; (b) rare-exception posture + when-in-doubt-don't.
      (is (or (str/includes? p "rare") (str/includes? p "default"))
          "reification is framed as the rare exception, flat is the default")
      (is (or (str/includes? p "when in doubt") (str/includes? p "do not reify"))
          "the when-in-doubt-don't-reify rule is present")
      ;; (c) reification REQUIRES numeric measure values (no measures → nothing to reify).
      (is (or (str/includes? p "numeric measure value") (str/includes? p "no numeric measure")
              (str/includes? p "actual numeric measure"))
          "reification requires actual numeric measure values")
      ;; the wide-vs-long LABEL column is a layout artifact, not a second subject.
      (is (or (str/includes? p "layout artifact") (str/includes? p "label"))
          "a measure/element/attribute LABEL column is a layout artifact, not a 2nd subject"))))

(deftest cycle3-model-prompt-grain-reify-is-domain-agnostic-test
  (testing "the grain/reify block bakes in NO vertical column/entity (#12)"
    (let [p (str/lower-case (model/model-prompt))]
      (doseq [term ["pseo" "cip" "soc" "ipeds" "onet" "o*net" "opeid"
                    "occupation" "institution" "wage" "earnings" "cohort"]]
        (is (not (str/includes? p term))
            (str "the Model prompt must not bake in the term: " term))))))

(deftest cycle3-prompt-assembles-and-reasoning-first-preserved-test
  (testing "the Model prompt assembles into a valid non-empty instruction with the
            grain/reify block AND #13 reasoning-first ordering preserved (the block is
            present whether or not a graph-context is populated — it is prompt text)"
    (let [p (model/model-prompt)
          lp (str/lower-case p)]
      (is (string? p) "the prompt assembles to a string")
      (is (> (count p) 500) "a substantive assembled prompt")
      ;; #13 — reasoning is named before the structured model-spec in the output framing.
      (is (< (.indexOf lp "reasoning") (.indexOf lp "model-spec"))
          "#13 — the output framing names :reasoning before :model-spec")
      ;; the robust variant also carries the block (it wraps model-prompt).
      (is (str/includes? (str/lower-case (model/robust-model-prompt)) "observation")
          "the robust re-attempt prompt also carries the grain/reify block"))))
