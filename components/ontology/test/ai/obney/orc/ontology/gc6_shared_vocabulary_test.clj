(ns ai.obney.orc.ontology.gc6-shared-vocabulary-test
  "GC-6 — shared DISCOVERED entity-type + key vocabulary (the keystone).

   These durable, HERMETIC brick-gate tests lock the load-bearing STRUCTURE +
   CONTRACT + the pipeline WIRING through the PUBLIC surface — never prompt-string
   internals beyond the domain-agnostic + #13 checks. The real-LLM synthesis +
   model-maps-onto-vocabulary + the integration read-back live on the live-verify
   driver (`development/src/gc6_shared_vocabulary_live_verify.clj`) per the
   gate-hygiene rule (a test driving the real LLM / real source files is an
   INTEGRATION test).

   What is locked here:
     - the synthesize-vocab subbehavior: registry name → deterministic, idempotent
       sheet-id (the EB1-EB8 pattern); a SINGLE :llm node, :reasoning FIRST (#13),
       reading [:goal :profile], writing [:reasoning :vocabulary]; source-agnostic;
       domain-agnostic prompt; the locked structured vocabulary-schema.
     - the Model subbehavior now :reads :vocabulary and its prompt carries the
       vocabulary constraint block (canonical type/key by description+aliases; novel
       entities still minted) — domain-agnostic, #13 preserved.
     - the central evolver WIRING (cycle 3): run-evolver-pipeline! calls synthesis
       AFTER the surveys (consuming the SAME profiles vector) and threads :vocabulary
       into EVERY per-source model-extract-fn; a synthesis failure yields the honest
       terminal :failed-at-synthesize-vocabulary."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.read-models :as orm]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.synthesize-vocab-subbehavior :as synth]
            [ai.obney.orc.ontology.core.model-subbehavior :as model]
            [ai.obney.orc.ontology.core.central-evolver :as ce]
            [malli.core :as m]))

;; ===========================================================================
;; The synthesize-vocab subbehavior — registry, node design, contract (#13)
;; ===========================================================================

(deftest synth-registry-name-resolves-to-deterministic-idempotent-sheet-id-test
  (testing "the synthesize-vocab subbehavior registers by name → a deterministic,
            idempotent, source-agnostic sheet-id (the EB1-EB8 registry pattern)"
    (h/with-async-test-context [ctx]
      (let [id-1 (synth/register-synthesize-vocab-subbehavior! ctx {})
            looked-up (synth/synthesize-vocab-sheet-id-for)
            id-2 (synth/register-synthesize-vocab-subbehavior! ctx {})]
        (is (= id-1 looked-up) "name→sheet-id lookup matches the registered id")
        (is (= id-1 id-2) "re-registering an unchanged subbehavior is idempotent")
        (is (= "ontology-synthesize-vocab/synthesize@v1"
               (synth/synthesize-vocab-subbehavior-name))
            "the registry name carries no source/medium/path tag (source-agnostic)")
        (is (some? (orm/get-sheet-by-name ctx (synth/synthesize-vocab-subbehavior-name)))
            "the registered subbehavior is discoverable by name in the projection")))))

(deftest synth-body-is-one-llm-node-reasoning-first-test
  (testing "the synthesize-vocab body is a SINGLE :llm node (not a repl-researcher),
            reading [:goal :profile], writing [:reasoning :vocabulary] with :reasoning
            FIRST (#13)"
    (h/with-async-test-context [ctx]
      (let [sid (synth/register-synthesize-vocab-subbehavior! ctx {})
            nodes (vals (orm/get-nodes-by-id ctx sid))
            leaves (filter #(= :leaf (:type %)) nodes)
            llm-leaves (filter #(= :ai (:executor %)) leaves)
            node (first llm-leaves)]
        (is (= 1 (count llm-leaves)) "exactly one :ai (:llm) leaf (the synthesis node)")
        (is (empty? (filter #(= :repl-researcher (:type %)) nodes))
            "no :repl-researcher — synthesis is single-turn :llm reasoning")
        (is (nil? (some #(get % :rlm) nodes)) "no :rlm config")
        (is (= :reasoning (first (:writes node)))
            "#13 — :reasoning is written FIRST in the synthesis node's :writes")
        (is (= [:reasoning :vocabulary] (vec (:writes node)))
            "the synthesis node writes :reasoning then :vocabulary")
        (is (= [:goal :profile] (vec (:reads node)))
            "the synthesis node reads the goal + the per-source profile(s)")))))

(deftest synth-vocabulary-schema-is-structured-not-bare-map-test
  (testing "the :vocabulary write declares the LOCKED structured schema — a
            [:map …] wrapper over a concrete [:vector [:map …]] (the :llm-node C1
            fix), so it crosses :delegate parsed (not a JSON string)"
    (is (= :map (first synth/vocabulary-schema))
        ":vocabulary is a structured [:map …] (not a bare :map / :any)")
    (is (= :vocabulary synth/vocabulary-key))
    (is (m/validate synth/vocabulary-schema
                    {:canonical-entity-types
                     [{:type "academic_program"
                       :uri-keying-fields ["program_code"]
                       :aliases ["field_of_study" "instructional_program"]
                       :description "a field of study keyed by program code"}]})
        "a real discovered vocabulary validates the structured schema")
    (is (m/validate synth/vocabulary-schema {})
        "an empty vocabulary map validates ({:closed false})")
    (is (not (m/validate synth/vocabulary-schema "a json string"))
        "a STRING does not validate the structured vocabulary schema")))

(deftest synth-prompt-is-domain-agnostic-and-keys-from-real-columns-test
  (testing "the synthesis prompt carries NO vertical knowledge (#12) and BAKES IN
            the locked decision — canonical keys drawn from REAL reported columns,
            :reasoning FIRST (#13)"
    (let [p (str/lower-case (synth/synthesize-prompt))]
      (doseq [term ["cip" "soc" "ipeds" "occupation" "education"
                    "opeid" "institution" "wage" "earnings"]]
        (is (not (str/includes? p term))
            (str "the synthesis prompt must not bake in the term: " term)))
      ;; the locked key-naming decision is explicit in the prompt.
      (is (str/includes? p "uri-keying-fields")
          "the prompt instructs on uri-keying-fields")
      (is (or (str/includes? p "actually report")
              (str/includes? p "profiles actually"))
          "the prompt requires keys drawn from columns the profiles ACTUALLY report")
      (is (str/includes? p "do not invent")
          "the prompt forbids inventing a new key name (the locked decision)")
      (is (str/includes? p "shared")
          "the prompt says pick the shared/linking key when sources differ")
      (is (< (.indexOf p "reasoning") (.indexOf p "vocabulary"))
          "#13 — the output framing names :reasoning before :vocabulary"))))

;; ===========================================================================
;; The Model subbehavior now reads :vocabulary + carries the constraint block
;; ===========================================================================

(deftest model-node-reads-vocabulary-test
  (testing "the Model :llm node now reads :vocabulary (in addition to :goal/:profile)
            and the sheet blackboard declares it [:maybe vocabulary-schema]"
    (h/with-async-test-context [ctx]
      (let [sid (model/register-model-subbehavior! ctx {})
            llm (first (filter #(= :ai (:executor %)) (vals (orm/get-nodes-by-id ctx sid))))]
        (is (= [:goal :profile :vocabulary :graph-context] (vec (:reads llm)))
            "the Model node reads :goal :profile :vocabulary (+ :graph-context, GM-1)")
        (is (= :reasoning (first (:writes llm)))
            "#13 — :reasoning still written FIRST (unchanged)")))))

(deftest model-prompt-carries-vocabulary-constraint-domain-agnostic-test
  (testing "the Model prompt carries the GC-6 vocabulary-constraint block — map by
            description+aliases onto the canonical type/key, STILL mint genuinely-
            novel entities (discovery preserved) — and stays domain-agnostic (#12)"
    (let [p (str/lower-case (model/model-prompt))]
      (is (str/includes? p "vocabulary")
          "the prompt references the shared vocabulary")
      (is (str/includes? p "canonical")
          "the prompt constrains to the canonical type/key")
      (is (or (str/includes? p "aliases") (str/includes? p "alias"))
          "the prompt maps by aliases")
      (is (str/includes? p "description")
          "the prompt maps by description (not exact label)")
      (is (str/includes? p "novel")
          "the prompt REQUIRES genuinely-novel entities still be minted (discovery)")
      ;; domain-agnostic — no vertical leakage from the constraint.
      (doseq [term ["cip" "soc" "ipeds" "occupation" "opeid"]]
        (is (not (str/includes? p term))
            (str "the Model prompt must not bake in the term: " term))))))

;; ===========================================================================
;; CYCLE 3 — the central evolver WIRING: synthesis after surveys; :vocabulary
;; threaded into every per-source model-extract-fn; honest synthesis-failure.
;; ===========================================================================

(deftest pipeline-synthesizes-after-surveys-and-threads-vocabulary-into-model-test
  (testing "run-evolver-pipeline! (via run-central-evolver!) calls synthesis AFTER
            the surveys, consuming the SAME profiles vector, and threads the
            discovered :vocabulary into EVERY per-source model-extract-fn call"
    (h/with-async-test-context [ctx]
      (let [oid (random-uuid)
            survey-order (atom [])
            synth-calls (atom [])
            captured-vocab (atom [])
            the-vocab {:canonical-entity-types
                       [{:type "thing" :uri-keying-fields ["k"]
                         :aliases ["thing" "widget"] :description "a thing keyed by k"}]}
            survey-fn (fn [_ {:keys [source]}]
                        (swap! survey-order conj (:path source))
                        {:status :success
                         :profile {:entity-candidates [(str "ent-" (:path source))]}})
            ;; the synthesis seam — capture the profiles it was handed.
            synthesize-vocab-fn (fn [_ {:keys [goal profile]}]
                                  (swap! synth-calls conj {:goal goal :profile profile})
                                  {:status :success :vocabulary the-vocab})
            ;; the model-extract seam — capture the :vocabulary each per-source call got.
            model-extract-fn (fn [c {:keys [vocabulary]}]
                               (swap! captured-vocab conj vocabulary)
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
                         :survey-fn survey-fn
                         :derive-cqs-fn (fn [_ _] {:status :success :competency-questions ["Q1"]})
                         :synthesize-vocab-fn synthesize-vocab-fn
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
        ;; synthesis ran exactly once, AFTER both surveys, with the FULL profiles vector.
        (is (= 1 (count @synth-calls)) "synthesis ran exactly once (aggregating all sources)")
        (is (= ["A" "B"] @survey-order) "both surveys ran first")
        (let [profiles (:profile (first @synth-calls))]
          (is (= 2 (count profiles))
              "synthesis consumed the SAME full profiles vector (one per source)")
          (is (= [{:entity-candidates ["ent-A"]} {:entity-candidates ["ent-B"]}]
                 (vec profiles))
              "the exact per-source profiles (the SAME vector the surveys produced)"))
        ;; EVERY per-source model-extract got the discovered vocabulary threaded in.
        (is (= 2 (count @captured-vocab)) "model-extract ran once per source")
        (is (every? #(= the-vocab %) @captured-vocab)
            "EVERY per-source model-extract-fn received the discovered :vocabulary")))))

(deftest synthesis-failure-surfaces-honestly-test
  (testing "a synthesize-vocab failure surfaces as :failed-at-synthesize-vocabulary
            (mirroring :failed-at-derive-cqs) — no downstream model-extract runs (#5)"
    (h/with-async-test-context [ctx]
      (let [model-calls (atom 0)
            result (ce/run-central-evolver!
                    ctx {:ontology-id (random-uuid)
                         :sources [{:type :csv :path "x"}] :goal "g"
                         :survey-fn (fn [_ _] {:status :success :profile {:entity-candidates ["t"]}})
                         :derive-cqs-fn (fn [_ _] {:status :success :competency-questions ["Q1"]})
                         :synthesize-vocab-fn (fn [_ _] {:status :failed :error "no vocab"})
                         :model-extract-fn (fn [_ _] (swap! model-calls inc)
                                             (throw (ex-info "must not run" {})))})]
        (is (= :failed-at-synthesize-vocabulary (:status result))
            "an honest terminal mirroring :failed-at-derive-cqs")
        (is (= "no vocab" (:error result)) "the synthesis error is surfaced")
        (is (zero? @model-calls)
            "no model-extract runs after a synthesis failure (no false green)")))))

;; ===========================================================================
;; GC-6 robustness — normalize-vocabulary coerces the intermittent :llm-node C1
;; parse shapes (clean map / EDN string / double-nested / string-valued field)
;; into a clean vocabulary so the Model NEVER receives a malformed one that would
;; silently drop the constraint and re-fragment.
;; ===========================================================================

(deftest normalize-vocabulary-coerces-all-c1-parse-shapes-test
  (let [want [{:type "thing" :uri-keying-fields ["k"] :aliases ["thing" "widget"]
               :description "a thing"}]
        clean {:canonical-entity-types want}]
    (testing "a clean map passes through unchanged"
      (is (= clean (synth/normalize-vocabulary clean))))
    (testing "a whole-value EDN STRING is parsed back to the clean map"
      (is (= clean (synth/normalize-vocabulary (pr-str clean)))))
    (testing "a DOUBLE-NESTED vocabulary is unwrapped one level"
      (is (= clean (synth/normalize-vocabulary
                    {:canonical-entity-types {:canonical-entity-types want}}))))
    (testing "a STRING-valued :canonical-entity-types is parsed"
      (is (= clean (synth/normalize-vocabulary
                    {:canonical-entity-types (pr-str want)}))))
    (testing "nil / garbage degrade to an HONEST empty (never a throw)"
      (is (= {:canonical-entity-types []} (synth/normalize-vocabulary nil)))
      (is (= {:canonical-entity-types []} (synth/normalize-vocabulary "not edn {{{")))
      (is (= {:canonical-entity-types []} (synth/normalize-vocabulary 42))))
    (testing "non-map entries are dropped (only well-formed entity-type maps kept)"
      (is (= {:canonical-entity-types want}
             (synth/normalize-vocabulary
              {:canonical-entity-types (conj want "garbage" nil)}))))
    (testing "the normalized output always validates against the locked schema"
      (is (m/validate synth/vocabulary-schema (synth/normalize-vocabulary (pr-str clean))))
      (is (m/validate synth/vocabulary-schema (synth/normalize-vocabulary nil))))))
