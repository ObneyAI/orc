(ns ai.obney.orc.ontology.model-subbehavior-test
  "EB3 — the MODEL subbehavior as a delegatable ORC sheet.

   These durable tests lock the load-bearing DECISIONS the EB3 live verify
   validated, through the subbehavior's PUBLIC surface (its registry name, its
   `:reads`/`:writes` contract + blackboard schema, its persisted node config),
   never internal node text beyond the domain-agnostic / node-type / #13
   guarantees the prompt MUST carry. They are hermetic (no LLM): the real LLM
   reasoning quality (grain + scope + embed-fields + candidate-axioms on a real
   breakdown-heavy profile, arriving PARSED across `:delegate`) is proven by the
   live verify (`development/src/eb3_model_subbehavior_live_verify.clj`,
   `docs/build-timeline/live-verify/EB3-model.md`).

   What is locked here:
     - Registry: name → deterministic sheet-id, idempotent re-registration
       (the EB1/EB2 pattern). The Model sheet is SOURCE-AGNOSTIC (one sheet for
       every goal + profile), unlike per-source Survey.
     - Node type: the body is a SINGLE `:llm` node (NOT a `:repl-researcher`) —
       single-turn reasoning over goal + profile, F3 does not apply.
     - #13: `:reasoning` is the FIRST declared `:writes` key (think-before-emit).
     - C1 (the `:llm` node-type case): the map writes (`:model-spec`,
       `:candidate-axioms`) declare STRUCTURED `[:map …]` schemas with CONCRETE
       per-field collection types (NOT `:any`), so the AI executor flattens +
       parses them into nested maps across `:delegate` rather than returning a
       JSON string. The schemas validate a real model-spec / axioms map.
     - Contract keys: the DT3 frozen model-contract keys + the EB3 embed-fields
       addition; `:candidate-axioms` a sibling write.
     - Re-orchestration: the DT3 grain/scope reasoning is re-housed (no fork) and
       the frozen grain-strategy enum is re-used verbatim.
     - Domain-agnostic prompt (discipline 12)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.ontology.core.model-subbehavior :as model]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]
            [clojure.string :as str]
            [malli.core :as m]))

;; A real-shaped model-spec contract map (the shape the live verify produced;
;; grain-strategy comes back as a STRING — the DT3 value-shape tolerance).
(def sample-model-spec
  {:entity-types
   [{:type "Postsecondary Institution"
     :uri-keying-fields ["UNITID"]
     :grain-strategy ":canonical-row-filter"}
    {:type "Award Conferral"
     :uri-keying-fields ["UNITID" "CIPCODE" "AWLEVEL"]
     :grain-strategy ":breakdown-as-entity"
     :breakdown-key "AWLEVEL"}]
   :scope-filter {:field "STABBR" :values ["AL"]}
   :edges [{:source-type "Award Conferral"
            :target-type "Postsecondary Institution"
            :predicate "conferred_by"}]
   :embed-fields ["INSTNM" "CITY" "ADDR"]})

(def sample-candidate-axioms
  {:axioms [{:kind ":functional" :field "UNITID"
             :rationale "UNITID uniquely identifies an institution."}
            {:kind ":disjoint"
             :types ["Postsecondary Institution" "Academic Program"]
             :rationale "An institution is distinct from a field-of-study category."}]})

;; ---------------------------------------------------------------------------
;; Registry: name → deterministic sheet-id, idempotent, source-agnostic.
;; ---------------------------------------------------------------------------

(deftest registry-name-resolves-to-deterministic-idempotent-sheet-id-test
  (testing "the Model subbehavior registers by name → a deterministic, idempotent sheet-id"
    (h/with-async-test-context [ctx]
      (let [id-1 (model/register-model-subbehavior! ctx {})
            looked-up (model/model-sheet-id-for)
            id-2 (model/register-model-subbehavior! ctx {})]
        (is (= id-1 looked-up)
            "name→sheet-id lookup must match the registered sheet-id")
        (is (= id-1 id-2)
            "re-registering an unchanged subbehavior is idempotent (same id)")
        (is (some? (rm/get-sheet-by-name ctx (model/model-subbehavior-name)))
            "the registered subbehavior is discoverable by name in the projection")))))

(deftest model-subbehavior-is-source-agnostic-test
  (testing "ONE Model sheet serves every source (it reasons over goal + profile,
            bakes in no source path) — unlike per-source Survey"
    (is (= "ontology-model/model@v1" (model/model-subbehavior-name))
        "the Model registry name carries no source/medium/path tag")))

;; ---------------------------------------------------------------------------
;; Node type: a SINGLE :llm node (NOT a repl-researcher; F3 does not apply).
;; ---------------------------------------------------------------------------

(deftest model-body-is-single-llm-node-not-repl-researcher-test
  (testing "the Model body is ONE :llm (:ai executor) leaf — not a :repl-researcher"
    (h/with-async-test-context [ctx]
      (let [sid (model/register-model-subbehavior! ctx {})
            nodes (vals (rm/get-nodes-by-id ctx sid))
            leaves (filter #(= :leaf (:type %)) nodes)
            llm-leaves (filter #(= :ai (:executor %)) leaves)]
        (is (= 1 (count llm-leaves))
            "exactly one :ai (:llm) leaf")
        (is (empty? (filter #(= :repl-researcher (:type %)) nodes))
            "no :repl-researcher node — modeling is single-turn reasoning, F3 N/A")
        (is (nil? (some #(get % :rlm) nodes))
            "no :rlm config — not a recursive/terminal RLM session")))))

;; ---------------------------------------------------------------------------
;; #13 — :reasoning is the FIRST declared :writes key.
;; ---------------------------------------------------------------------------

(deftest reasoning-is-written-first-test
  (testing "#13: the :llm node writes :reasoning FIRST (chain-of-thought before the spec)"
    (h/with-async-test-context [ctx]
      (let [sid (model/register-model-subbehavior! ctx {})
            llm (first (filter #(= :ai (:executor %)) (vals (rm/get-nodes-by-id ctx sid))))
            writes (vec (:writes llm))]
        (is (= :reasoning (first writes))
            "the FIRST declared write must be :reasoning (force think-before-emit)")
        (is (= [:reasoning :model-spec :candidate-axioms] writes)
            "the full write contract is reasoning-first, then the two structured maps")))))

(deftest model-contract-is-goal-and-profile-in-spec-out-test
  (testing "the public contract: [:goal :profile :vocabulary] in, [:reasoning
            :model-spec :candidate-axioms] out (GC-6 widened :reads to also take the
            shared discovered :vocabulary)"
    (h/with-async-test-context [ctx]
      (let [sid (model/register-model-subbehavior! ctx {})
            llm (first (filter #(= :ai (:executor %)) (vals (rm/get-nodes-by-id ctx sid))))]
        (is (= [:goal :profile :vocabulary] (vec (:reads llm)))
            "the node reads the goal + the profile + the shared vocabulary (GC-6)")
        (is (= [:reasoning :model-spec :candidate-axioms] (vec (:writes llm)))
            "the node writes reasoning-first, then the model-spec + candidate-axioms")))))

;; ---------------------------------------------------------------------------
;; C1 (the :llm node-type case) — STRUCTURED schemas with CONCRETE field types.
;; ---------------------------------------------------------------------------

(deftest model-spec-write-schema-is-structured-not-bare-map-test
  (testing "C1: the :model-spec blackboard schema is a STRUCTURED [:map …], never a bare :map"
    (h/with-async-test-context [ctx]
      (let [sid (model/register-model-subbehavior! ctx {})
            bb (rm/get-blackboard-by-key ctx sid)
            spec-schema (get-in bb [:model-spec :schema])
            axioms-schema (get-in bb [:candidate-axioms :schema])]
        (is (vector? model/model-spec-contract-schema)
            "the model-spec schema must be a vector (structured) form")
        (is (= :map (first model/model-spec-contract-schema))
            "the model-spec schema must be a [:map …] schema")
        (is (> (count model/model-spec-contract-schema) 2)
            "a STRUCTURED [:map …] has field entries — a bare :map (the C1 failure) would not")
        (is (not= :map spec-schema)
            "the persisted :model-spec blackboard schema must NOT be the bare keyword :map (C1)")
        (is (not= :map axioms-schema)
            "the persisted :candidate-axioms blackboard schema must NOT be a bare :map (C1)")))))

(deftest model-spec-fields-are-concrete-collection-types-not-any-test
  (testing "C1 (the :llm per-field fix the prototype proved): each flattened
            model-spec field carries a CONCRETE collection spec, NOT :any — an
            :any-typed field comes back as raw EDN TEXT instead of parsed data"
    (let [fields (->> (rest model/model-spec-contract-schema)
                      (filter vector?))
          spec-for (fn [k] (let [[_ & r] (first (filter #(= k (first %)) fields))]
                             (if (map? (first r)) (second r) (first r))))]
      ;; entity-types / edges / embed-fields are vectors; scope-filter is :maybe map
      (is (= :vector (first (spec-for :entity-types)))
          "entity-types is a concrete [:vector …] so DSCloj parses it (not raw text)")
      (is (= :vector (first (spec-for :edges)))
          "edges is a concrete [:vector …]")
      (is (= :vector (first (spec-for model/embed-fields-key)))
          "embed-fields is a concrete [:vector …]")
      (is (#{:maybe :map} (first (spec-for :scope-filter)))
          "scope-filter is a concrete map (or :maybe map for the no-scope nil case)")
      ;; the axioms wrapper field is also a concrete vector
      (let [axiom-field (->> (rest model/candidate-axioms-schema)
                             (filter vector?)
                             (filter #(= :axioms (first %)))
                             first)
            axiom-spec (let [[_ & r] axiom-field] (if (map? (first r)) (second r) (first r)))]
        (is (= :vector (first axiom-spec))
            "candidate-axioms :axioms is a concrete [:vector …], not :any")))))

(deftest model-spec-schema-validates-a-real-contract-map-test
  (testing "the structured model-spec schema accepts a real, model-variable spec map"
    (is (m/validate model/model-spec-contract-schema sample-model-spec)
        "a real model-spec map (grain-strategy as a string, extra :breakdown-key) validates")
    (is (m/validate model/model-spec-contract-schema
                    (assoc sample-model-spec :scope-filter nil))
        "a nil scope-filter (the no-scope case) validates the :maybe map")
    (is (m/validate model/model-spec-contract-schema {})
        "an (empty) map still validates the SHAPE — keys are optional; the live verify gates on content")
    (is (not (m/validate model/model-spec-contract-schema "a json string"))
        "a STRING (the C1 failure mode) does NOT validate the structured map schema")
    (is (m/validate model/candidate-axioms-schema sample-candidate-axioms)
        "a real candidate-axioms map validates")
    (is (not (m/validate model/candidate-axioms-schema "a json string"))
        "a STRING does NOT validate the candidate-axioms structured schema")))

;; ---------------------------------------------------------------------------
;; Contract keys + re-orchestration of the DT3 frozen contract/enum.
;; ---------------------------------------------------------------------------

(deftest contract-keys-are-dt3-frozen-plus-embed-fields-test
  (testing "the Model model-spec contract = the DT3 frozen keys + the EB3 embed-fields
            + the GC-11a linking-keys carry-forward"
    (is (= (set dt/model-contract-keys)
           (set (remove #{model/embed-fields-key model/linking-keys-key}
                        model/model-spec-contract-keys)))
        "the DT3-frozen model-contract keys are re-used verbatim (no drift)")
    (is (contains? (set model/model-spec-contract-keys) model/embed-fields-key)
        "the embed-fields signal (P2 → EB7) is part of the model-spec contract")
    (is (contains? (set model/model-spec-contract-keys) model/linking-keys-key)
        "the GC-11a linking-keys carry-forward is part of the model-spec contract")
    (is (= :embed-fields model/embed-fields-key))
    (is (= :candidate-axioms model/candidate-axioms-key)
        "candidate-axioms (→ EB6) is a sibling write key")))

(deftest grain-strategy-enum-is-dt3-frozen-and-normalizes-test
  (testing "the model re-uses the DT3 frozen grain-strategy enum + normalization
            (so a string-shaped grain-strategy still reads onto the enum)"
    ;; the schema doesn't re-type the enum (value-shape tolerance), but the prompt
    ;; references it and the consumer normalizes via the DT3 carry-forward.
    (is (= :canonical-row-filter
           (dt/normalize-grain-strategy ":canonical-row-filter"))
        "a string grain-strategy normalizes onto the frozen enum keyword")
    (is (= :breakdown-as-entity
           (dt/normalize-grain-strategy :breakdown-as-entity))
        "a keyword grain-strategy normalizes onto the frozen enum keyword")
    (is (every? #(contains? dt/valid-grain-strategies
                            (dt/normalize-grain-strategy (:grain-strategy %)))
                (:entity-types sample-model-spec))
        "every entity in a real spec carries a grain-strategy on the frozen enum")))

;; ---------------------------------------------------------------------------
;; Prompt: re-housed DT3 grain/scope, :llm I/O framing, EB3 additions, agnostic.
;; ---------------------------------------------------------------------------

(deftest prompt-rehouses-dt3-grain-scope-and-adds-eb3-outputs-test
  (testing "the prompt re-houses the DT3 grain/scope decision and adds embed-fields + axioms"
    (let [p (model/model-prompt)
          lp (str/lower-case p)]
      ;; DT3 grain/scope keystone re-housed (the V17/V20 over-extraction fix)
      (is (str/includes? p "canonical-row-filter")
          "the prompt carries the DT3 :canonical-row-filter grain strategy")
      (is (str/includes? p "breakdown-as-entity")
          "the prompt carries the DT3 :breakdown-as-entity grain strategy")
      (is (str/includes? p "scope-filter")
          "the prompt carries the DT3 scope decision")
      ;; the EB3 additions
      (is (str/includes? lp "embed")
          "the prompt asks for the embed-worthy fields (P2 → EB7)")
      (is (str/includes? lp "axiom")
          "the prompt asks for candidate axioms (→ EB6)")
      ;; :llm I/O framing (not a repl-researcher tool session)
      (is (str/includes? lp "do not")
          "the prompt frames the node as a single reasoning step")
      (is (and (str/includes? p "model-spec") (str/includes? p "candidate-axioms"))
          "the prompt names the structured write keys"))))

;; ---------------------------------------------------------------------------
;; GC-11a — the model-spec carries the discovered linking-key COLUMN NAMES forward
;; so the Extract AUTHOR (which reads the model-spec, NOT the profile) sees them and
;; the apply-step carries their per-row VALUES into :attributes. The schema addition
;; is OPTIONAL/[:maybe …] so the no-linking-key path is behavior-preserving.
;; ---------------------------------------------------------------------------

(deftest gc11a-linking-keys-is-a-model-spec-contract-key-test
  (testing "the model-spec contract carries :linking-keys (the cross-source carry-forward)"
    (is (= :linking-keys model/linking-keys-key)
        "the linking-keys carry-forward key is :linking-keys")
    (is (contains? (set model/model-spec-contract-keys) model/linking-keys-key)
        "the model-spec contract includes :linking-keys so the Extract AUTHOR sees it")))

(deftest gc11a-linking-keys-schema-is-optional-and-behavior-preserving-test
  (testing "the model-spec schema accepts a spec WITH :linking-keys and one WITHOUT
            (nil/absent → unaffected; the no-linking-key path is preserved)"
    ;; WITH a real linking-keys vector (the cross-source case)
    (is (m/validate model/model-spec-contract-schema
                    (assoc sample-model-spec :linking-keys ["CIPCODE"]))
        "a spec carrying :linking-keys (a vector of column-name strings) validates")
    ;; ABSENT (the existing sample has no :linking-keys) — behavior-preserving
    (is (m/validate model/model-spec-contract-schema sample-model-spec)
        "a spec with NO :linking-keys still validates (the field is optional)")
    ;; nil (the explicit no-linking-key path) — [:maybe …] tolerates it
    (is (m/validate model/model-spec-contract-schema
                    (assoc sample-model-spec :linking-keys nil))
        "a nil :linking-keys (no linking key discovered) validates the :maybe vector")))

(deftest gc11a-prompt-instructs-copying-linking-keys-forward-test
  (testing "the Model prompt instructs the node to COPY the profile's linking-keys
            onto the model-spec (so the Extract AUTHOR/apply-step can carry the VALUES)"
    (let [p (model/model-prompt)
          lp (str/lower-case p)]
      (is (str/includes? lp "linking")
          "the prompt references the linking keys")
      (is (str/includes? p "linking-keys")
          "the prompt names the :linking-keys model-spec field to emit")
      ;; the load-bearing instruction: a linking key is OFTEN not a keying field.
      (is (or (str/includes? lp "uri-keying-field") (str/includes? lp "keying field"))
          "the prompt clarifies a linking key may differ from the entity's keying field"))))

(deftest prompt-is-domain-agnostic-test
  (testing "the prompt carries NO vertical/domain knowledge (discipline 12)"
    (let [p (str/lower-case (model/model-prompt))]
      ;; the runtime sentinel stands in for the goal; the example domain
      ;; (education / CIP-SOC / IPEDS) must NOT leak into the generic prompt.
      (doseq [leak ["cip" "soc" "ipeds" "opeid" "occupation" "institution"
                    "degree" "stabbr" "unitid" "awlevel"]]
        (is (not (str/includes? p leak))
            (str "the Model prompt must not bake in the vertical term: " leak))))))
