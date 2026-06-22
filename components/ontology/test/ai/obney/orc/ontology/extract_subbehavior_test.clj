(ns ai.obney.orc.ontology.extract-subbehavior-test
  "EB4 — the EXTRACT subbehavior as a delegatable ORC sheet.

   These durable tests lock the load-bearing STRUCTURE + CONTRACT the EB4 live
   verify validated, through the subbehavior's PUBLIC surface (its registry name,
   its `:reads`/`:writes` contract + blackboard schema, its persisted node
   configs), never internal node text beyond the domain-agnostic / node-type / #13
   guarantees the prompt MUST carry. They are hermetic (no LLM, no real source):
   the real LLM authoring quality (the AUTONOMOUS field-grounded transform yielding
   a SANE SCOPED concept count over a real source, NO hand-correction) is the P1
   verify-not-assume criterion proven by the live verify
   (`development/src/eb4_extract_subbehavior_live_verify.clj`,
   `docs/build-timeline/live-verify/EB4-extract.md`) and the on-demand integration
   test (`development/ontology-integration/.../eb4-extract-test`).

   What is locked here:
     - Registry: name → deterministic sheet-id, idempotent re-registration
       (the EB1/EB2/EB3 pattern). The Extract sheet is SOURCE-AGNOSTIC (one sheet
       for every model-spec + source), like EB3 Model — it bakes in no path.
     - Node design: a THREE-node sequence `:code` → `:llm` → `:code` (sample →
       author → apply); the AUTHOR node is `:ai` (:llm), the sample + apply are
       `:code`.
     - #13: `:reasoning` is the FIRST declared `:writes` key on the AUTHOR node.
     - The public contract: [:model-spec :source] in, [:concept-drafts
       :relationship-drafts :extraction-report] out; the AUTHOR node reads
       [:model-spec :sample-rows] and writes [:reasoning :transform-source
       :selector].
     - REUSE not fork: Node 1's `:fn` resolves to `mechanical-sample-rows` (via
       the wrapper); Node 3's `:fn` resolves to `apply-extraction-transform!` (via
       the wrapper) — the SAME proven DT4-grounding + V20 fns.
     - The AUTHOR prompt re-houses the DT4 transform-authoring body (grain/scope/
       grounding) + the DT4-grounding key-shape grounding instruction, is `:llm`-
       framed (no tool session), and is domain-agnostic (discipline 12)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.ontology.core.extract-subbehavior :as extract]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]
            [ai.obney.orc.ontology.core.rlm-discovery :as rlm-discovery]
            [clojure.string :as str]
            [malli.core :as m]))

;; A real-shaped EB3 model-spec (the CSV CIP/SOC crosswalk shape the live verify
;; used; grain-strategy as a keyword — the DT3 value-shape tolerance covers both).
(def sample-model-spec
  {:entity-types
   [{:type "Program of Study"
     :uri-keying-fields ["CIP_Code"]
     :grain-strategy :canonical-row-filter}
    {:type "Occupation"
     :uri-keying-fields ["SOC_Code"]
     :grain-strategy :canonical-row-filter}]
   :scope-filter {:field "CIP_Code" :values ["01"]}
   :edges [{:source-type "Program of Study"
            :target-type "Occupation"
            :predicate "prepares_for"}]
   :embed-fields ["CIP_Title" "SOC_Title"]})

;; A real-shaped draft set (what apply-extraction-transform! returns) — validates
;; the structured output schemas without a live run.
(def sample-concept-drafts
  [{:uri "program:010000" :label "Agriculture, General." :attributes {:code "01.0000"}}
   {:uri "occupation:191011" :label "Animal Scientists" :attributes {:code "19-1011"}}])

(def sample-relationship-drafts
  [{:source-uri "program:010000" :target-uri "occupation:191011" :predicate "prepares_for"}])

(def sample-extraction-report
  {:selector nil :rows-streamed 6097 :rows-ok 6097 :rows-errored 0
   :windows 61 :concept-count 138 :relationship-count 286 :errors-sample []})

;; ---------------------------------------------------------------------------
;; Registry: name → deterministic sheet-id, idempotent, source-agnostic.
;; ---------------------------------------------------------------------------

(deftest registry-name-resolves-to-deterministic-idempotent-sheet-id-test
  (testing "the Extract subbehavior registers by name → a deterministic, idempotent sheet-id"
    (h/with-async-test-context [ctx]
      (let [id-1 (extract/register-extract-subbehavior! ctx {})
            looked-up (extract/extract-sheet-id-for)
            id-2 (extract/register-extract-subbehavior! ctx {})]
        (is (= id-1 looked-up)
            "name→sheet-id lookup must match the registered sheet-id")
        (is (= id-1 id-2)
            "re-registering an unchanged subbehavior is idempotent (same id)")
        (is (some? (rm/get-sheet-by-name ctx (extract/extract-subbehavior-name)))
            "the registered subbehavior is discoverable by name in the projection")))))

(deftest extract-subbehavior-is-source-agnostic-test
  (testing "ONE Extract sheet serves every source (it reasons over model-spec +
            samples the source at runtime, bakes in no source path) — like EB3 Model"
    (is (= "ontology-extract/extract@v1" (extract/extract-subbehavior-name))
        "the Extract registry name carries no source/medium/path tag")))

;; ---------------------------------------------------------------------------
;; Node design: a THREE-node :code → :llm → :code sequence.
;; ---------------------------------------------------------------------------

(deftest body-is-code-llm-code-three-node-pipeline-test
  (testing "the Extract body is :code (sample) → :llm (author) → :code (apply)"
    (h/with-async-test-context [ctx]
      (let [sid (extract/register-extract-subbehavior! ctx {})
            nodes (vals (rm/get-nodes-by-id ctx sid))
            leaves (filter #(= :leaf (:type %)) nodes)
            code-leaves (filter #(= :code (:executor %)) leaves)
            llm-leaves (filter #(= :ai (:executor %)) leaves)]
        (is (= 2 (count code-leaves))
            "exactly two :code leaves (sample + apply)")
        (is (= 1 (count llm-leaves))
            "exactly one :ai (:llm) leaf (author)")
        (is (empty? (filter #(= :repl-researcher (:type %)) nodes))
            "no :repl-researcher node — sampling is a :code node, not a tool session")
        (is (nil? (some #(get % :rlm) nodes))
            "no :rlm config — Extract is a deterministic+single-shot pipeline")))))

;; ---------------------------------------------------------------------------
;; REUSE not fork: the :code nodes resolve to the proven DT4-grounding + V20 fns.
;; ---------------------------------------------------------------------------

(deftest code-nodes-reuse-mechanical-sample-rows-and-apply-step-test
  (testing "the :code nodes wrap the REUSED mechanical-sample-rows + V20 apply-step
            (no fork — Discipline #8); the wrappers delegate to those exact fns"
    (h/with-async-test-context [ctx]
      (let [sid (extract/register-extract-subbehavior! ctx {})
            leaves (filter #(= :code (:executor %)) (vals (rm/get-nodes-by-id ctx sid)))
            fns (set (map :fn leaves))]
        (is (contains? fns "ai.obney.orc.ontology.core.extract-subbehavior/sample-rows-code")
            "the SAMPLE node's :fn is the mechanical-sample-rows wrapper")
        (is (contains? fns "ai.obney.orc.ontology.core.extract-subbehavior/apply-transform-code")
            "the APPLY node's :fn is the apply-extraction-transform! wrapper"))))
  (testing "the proven fns the wrappers reuse are the actual DT4-grounding + V20 fns"
    ;; the wrappers REUSE (don't fork) these — proven by their var being the real one.
    (is (var? #'dt/mechanical-sample-rows)
        "Node 1 reuses discovery-tree/mechanical-sample-rows (DT4-grounding)")
    (is (var? #'rlm-discovery/apply-extraction-transform!)
        "Node 3 reuses rlm-discovery/apply-extraction-transform! (V20 apply-step)")))

;; ---------------------------------------------------------------------------
;; The public contract + the AUTHOR node's :reads/:writes (#13 reasoning first).
;; ---------------------------------------------------------------------------

(deftest author-node-reads-spec-and-samples-writes-reasoning-first-test
  (testing "#13 + contract: the AUTHOR :llm node reads [:model-spec :sample-rows]
            and writes [:reasoning :transform-source :selector] (reasoning FIRST)"
    (h/with-async-test-context [ctx]
      (let [sid (extract/register-extract-subbehavior! ctx {})
            author (first (filter #(= :ai (:executor %)) (vals (rm/get-nodes-by-id ctx sid))))
            writes (vec (:writes author))]
        (is (= [:model-spec :sample-rows] (vec (:reads author)))
            "the AUTHOR node reads the model-spec + the REAL sampled rows (Node 1's output)")
        (is (= :reasoning (first writes))
            "#13: the FIRST declared write must be :reasoning (force think-before-emit)")
        (is (= [:reasoning :transform-source :selector] writes)
            "the AUTHOR write contract is reasoning-first, then the transform-source + selector")))))

(deftest sample-and-apply-node-contracts-test
  (testing "the SAMPLE node reads [:source] writes [:sample-rows]; the APPLY node
            reads [:source :transform-source :selector] writes the draft set"
    (h/with-async-test-context [ctx]
      (let [nodes (vals (rm/get-nodes-by-id ctx (extract/register-extract-subbehavior! ctx {})))
            sample (first (filter #(= "sample-rows" (:name %)) nodes))
            apply* (first (filter #(= "apply-transform" (:name %)) nodes))]
        (is (= [:source] (vec (:reads sample))))
        (is (= [:sample-rows] (vec (:writes sample))))
        (is (= [:source :transform-source :selector] (vec (:reads apply*))))
        (is (= [:concept-drafts :relationship-drafts :extraction-report]
               (vec (:writes apply*)))
            "the APPLY node writes the public draft-set contract")))))

(deftest contract-keys-are-dt4-transform-and-v20-draft-set-test
  (testing "the transform contract is re-used verbatim from DT4; the public output
            is the V20 draft set + the coverage report"
    (is (= (vec dt/transform-contract-keys) extract/transform-contract-keys)
        "the DT4-frozen transform-contract keys are re-used verbatim (no drift)")
    (is (= [:concept-drafts :relationship-drafts :extraction-report]
           extract/draft-contract-keys)
        "the public output contract is the draft set + the coverage report")))

;; ---------------------------------------------------------------------------
;; Structured output schemas (documented shape; robust if AI-path-routed).
;; ---------------------------------------------------------------------------

(deftest output-write-schemas-are-structured-not-bare-map-test
  (testing "the draft-set writes declare STRUCTURED schemas (vector-of-maps / map),
            NEVER a bare :map, and validate a real draft set"
    (is (= :vector (first extract/concept-drafts-schema))
        "concept-drafts is a concrete [:vector …]")
    (is (= :vector (first extract/relationship-drafts-schema))
        "relationship-drafts is a concrete [:vector …]")
    (is (= :map (first extract/extraction-report-schema))
        "extraction-report is a structured [:map …]")
    (is (> (count extract/extraction-report-schema) 2)
        "a STRUCTURED [:map …] has field entries — a bare :map would not")
    ;; validates a real draft set produced by apply-extraction-transform!
    (is (m/validate extract/concept-drafts-schema sample-concept-drafts))
    (is (m/validate extract/relationship-drafts-schema sample-relationship-drafts))
    (is (m/validate extract/extraction-report-schema sample-extraction-report))
    (is (not (m/validate extract/concept-drafts-schema "a json string"))
        "a STRING does not validate the structured vector schema")))

;; ---------------------------------------------------------------------------
;; The AUTHOR prompt: re-houses DT4 grain/scope/grounding, :llm-framed, agnostic.
;; ---------------------------------------------------------------------------

(deftest author-prompt-rehouses-dt4-grain-scope-grounding-test
  (testing "the AUTHOR prompt re-houses the DT4 transform-authoring body
            (grain/scope/URI-keying/grounding) and is :llm-framed (no tool session)"
    (let [p (extract/transform-author-prompt)
          lp (str/lower-case p)]
      ;; DT4 grain/scope re-housed (the V17/V20 over-extraction fix at extract time)
      (is (str/includes? p "canonical-row-filter")
          "the prompt carries the DT4 :canonical-row-filter grain strategy")
      (is (str/includes? p "breakdown-as-entity")
          "the prompt carries the DT4 :breakdown-as-entity grain strategy")
      (is (str/includes? lp "scope")
          "the prompt carries the DT4 scope enforcement")
      (is (str/includes? lp "uri")
          "the prompt carries the DT4 URI-keying")
      ;; the transform contract
      (is (str/includes? p "concept-drafts")
          "the prompt names the per-row transform contract (concept-drafts)")
      (is (str/includes? p "transform-source")
          "the prompt names the transform-source write")
      ;; :llm I/O framing — no tool session, no emit-tree, no self-sampling
      (is (str/includes? lp "do not")
          "the prompt frames the node as a single authoring step (no tool session)")
      (is (str/includes? lp "sample-rows")
          "the prompt tells the node the rows were already sampled for it"))))

(deftest author-prompt-renders-key-shape-grounding-when-provided-test
  (testing "the DT4-grounding key-shape block renders the EXACT real keys when a
            key-shape is provided (the honest-negative field-grounding fix)"
    (let [key-shape {:keys ["CIP_Code" "SOC_Code"] :key-type :string
                     :format :csv
                     :sample-row {"CIP_Code" "01.0000" "SOC_Code" "19-1011"}}
          p (extract/transform-author-prompt key-shape)]
      (is (str/includes? p "CIP_Code")
          "the rendered key-shape block names the EXACT real key verbatim")
      (is (str/includes? (str/lower-case p) "real row key shape")
          "the prompt carries the DT4-grounding hard grounding block")))
  (testing "with no key-shape the block is empty (back-compat) but the runtime
            grounding instruction still tells the node to use the sample-rows input"
    (let [p (extract/transform-author-prompt)]
      (is (str/includes? (str/lower-case p) "sample-rows")
          "the :llm framing still grounds the node in the runtime sample-rows input"))))

(deftest author-prompt-is-domain-agnostic-test
  (testing "the AUTHOR prompt carries NO vertical/domain knowledge (discipline 12)"
    (let [p (str/lower-case (extract/transform-author-prompt))]
      (doseq [leak ["cip" "soc" "ipeds" "opeid" "occupation" "institution"
                    "degree" "stabbr" "unitid" "awlevel" "crosswalk"]]
        (is (not (str/includes? p leak))
            (str "the Extract AUTHOR prompt must not bake in the vertical term: " leak))))))
