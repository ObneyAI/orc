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
            [ai.obney.orc.orc-service.interface :as dsl]
            [ai.obney.orc.ontology.core.extract-subbehavior :as extract]
            [ai.obney.orc.ontology.core.discovery-tree :as dt]
            [ai.obney.orc.ontology.core.rlm-discovery :as rlm-discovery]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.reconcile-subbehavior :as recon]
            [ai.obney.orc.ontology.core.read-models :as orm]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m])
  (:import [java.io File]
           [java.sql DriverManager]))

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
;; MC-5 — the PUBLIC @v1 sheet is now a thin MULTI-container ORCHESTRATOR: ONE
;; :code node (list-containers + per-container child ticks + accumulation). The
;; three-node SAMPLE→AUTHOR→APPLY pipeline + #13 + REUSE invariants moved DOWN to
;; the per-container UNIT (extract-per-container@v1), which the orchestrator drives
;; once per container. register-extract-subbehavior! registers BOTH.
;; ---------------------------------------------------------------------------

(deftest public-sheet-is-a-single-code-orchestrator-test
  (testing "the public @v1 Extract sheet is ONE :code orchestrator node (it
            list-containers + drives the per-container unit per container) — NOT a
            map-each composite leaf (ORC-PRINCIPLES §14) and NOT a :repl-researcher"
    (h/with-async-test-context [ctx]
      (let [sid (extract/register-extract-subbehavior! ctx {})
            nodes (vals (rm/get-nodes-by-id ctx sid))
            leaves (filter #(= :leaf (:type %)) nodes)
            code-leaves (filter #(= :code (:executor %)) leaves)]
        (is (= 1 (count code-leaves))
            "exactly one :code leaf — the multi-container orchestrator")
        (is (empty? (filter #(= :ai (:executor %)) leaves))
            "no :llm leaf on the PUBLIC sheet — the AUTHOR lives in the per-container unit")
        (is (empty? (filter #(= :map-each (:type %)) nodes))
            "NO :map-each — a composite per-container leaf would hit the §14 race/bleed")
        (is (empty? (filter #(= :repl-researcher (:type %)) nodes))
            "no :repl-researcher node")
        (is (= "ai.obney.orc.ontology.core.extract-subbehavior/orchestrate-extract-containers"
               (:fn (first code-leaves)))
            "the orchestrator node's :fn is the multi-container orchestrator")
        (is (= [:source :model-spec :max-containers :max-windows :selected-containers]
               (vec (:reads (first code-leaves))))
            "the orchestrator reads the public :reads contract (+ the optional GC-9
             :max-containers/:max-windows reduced-cap bounds + the optional MT-2
             :selected-containers survey-driven relevance selection)")
        (is (= [:concept-drafts :relationship-drafts :extraction-report]
               (vec (:writes (first code-leaves))))
            "the orchestrator writes the public draft-set contract")))))

;; ---------------------------------------------------------------------------
;; Node design (per-container UNIT): a THREE-node :code → :llm → :code sequence.
;; ---------------------------------------------------------------------------

(deftest per-container-unit-is-code-llm-code-three-node-pipeline-test
  (testing "the per-container UNIT body is :code (sample) → :llm (author) → :code (apply)"
    (h/with-async-test-context [ctx]
      (let [sid (extract/register-extract-subbehavior! ctx {})
            unit-id (extract/extract-per-container-sheet-id-for)
            nodes (vals (rm/get-nodes-by-id ctx unit-id))
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
            "no :rlm config — the per-container unit is a deterministic+single-shot pipeline")))))

;; ---------------------------------------------------------------------------
;; REUSE not fork: the per-container :code nodes resolve to the proven
;; DT4-grounding + V20 fns (the per-container wrappers, no fork).
;; ---------------------------------------------------------------------------

(deftest per-container-code-nodes-reuse-mechanical-sample-rows-and-apply-step-test
  (testing "the per-container :code nodes wrap the REUSED mechanical-sample-rows +
            V20 apply-step keyed on the container (no fork — Discipline #8)"
    (h/with-async-test-context [ctx]
      (let [_ (extract/register-extract-subbehavior! ctx {})
            unit-id (extract/extract-per-container-sheet-id-for)
            leaves (filter #(= :code (:executor %)) (vals (rm/get-nodes-by-id ctx unit-id)))
            fns (set (map :fn leaves))]
        (is (contains? fns "ai.obney.orc.ontology.core.extract-subbehavior/sample-rows-for-container-code")
            "the SAMPLE node's :fn is the per-container mechanical-sample-rows wrapper")
        (is (contains? fns "ai.obney.orc.ontology.core.extract-subbehavior/apply-transform-for-container-code")
            "the APPLY node's :fn is the per-container apply-extraction-transform! wrapper"))))
  (testing "the proven fns the wrappers reuse are the actual DT4-grounding + V20 fns"
    ;; the wrappers REUSE (don't fork) these — proven by their var being the real one.
    (is (var? #'dt/mechanical-sample-rows)
        "Node 1 reuses discovery-tree/mechanical-sample-rows (DT4-grounding)")
    (is (var? #'rlm-discovery/apply-extraction-transform!)
        "Node 3 reuses rlm-discovery/apply-extraction-transform! (V20 apply-step)")))

;; ---------------------------------------------------------------------------
;; The per-container contract + the AUTHOR node's :reads/:writes (#13 reasoning first).
;; ---------------------------------------------------------------------------

(deftest per-container-author-node-reads-spec-and-samples-writes-reasoning-first-test
  (testing "#13 + contract: the per-container AUTHOR :llm node reads
            [:model-spec :sample-rows] and writes [:reasoning :transform-source
            :selector] (reasoning FIRST)"
    (h/with-async-test-context [ctx]
      (let [_ (extract/register-extract-subbehavior! ctx {})
            unit-id (extract/extract-per-container-sheet-id-for)
            author (first (filter #(= :ai (:executor %)) (vals (rm/get-nodes-by-id ctx unit-id))))
            writes (vec (:writes author))]
        (is (= [:model-spec :sample-rows :container] (vec (:reads author)))
            "the AUTHOR node reads the model-spec + the REAL sampled rows (Node 1's
             output) + the container (MT-3 — for the :long-form :shape tag)")
        (is (= :reasoning (first writes))
            "#13: the FIRST declared write must be :reasoning (force think-before-emit)")
        (is (= [:reasoning :transform-source :selector :aggregation-spec] writes)
            "the AUTHOR write contract is reasoning-first, then the transform-source +
             selector + the MT-3 :aggregation-spec (the :long-form rollup spec)")))))

(deftest per-container-sample-and-apply-node-contracts-test
  (testing "the per-container SAMPLE node reads [:source :container] writes
            [:sample-rows]; the APPLY node reads [:source :transform-source
            :selector :container :max-windows] writes the draft set (GC-9 added
            :max-windows so the orchestrator's forwarded per-container window cap binds)"
    (h/with-async-test-context [ctx]
      (let [_ (extract/register-extract-subbehavior! ctx {})
            unit-id (extract/extract-per-container-sheet-id-for)
            nodes (vals (rm/get-nodes-by-id ctx unit-id))
            sample (first (filter #(= "sample-rows" (:name %)) nodes))
            apply* (first (filter #(= "apply-transform" (:name %)) nodes))]
        (is (= [:source :container] (vec (:reads sample)))
            "the SAMPLE node reads the source + the ONE container it grounds")
        (is (= [:sample-rows] (vec (:writes sample))))
        (is (= [:source :transform-source :selector :container :max-windows
                :model-spec :aggregation-spec :sample-rows]
               (vec (:reads apply*)))
            "the APPLY node reads the source + transform + selector + the container it
             applies to + the GC-9 :max-windows reduced-cap + the GC-11a :model-spec
             (for the deterministic linking-key VALUE carry) + the MT-3 :aggregation-spec
             (routes the aggregating fold for a :long-form container) + the MT-6
             :sample-rows (the sample-driven key-repeats? gate)")
        (is (= [:concept-drafts :relationship-drafts :extraction-report]
               (vec (:writes apply*)))
            "the APPLY node writes the per-container draft-set contract")))))

(deftest per-container-unit-is-source-and-container-agnostic-test
  (testing "the per-container unit carries a stable @v1 name + reads the container
            it is pointed at (bakes in no container/source path)"
    (is (= "ontology-extract/extract-per-container@v1" (extract/extract-per-container-name))
        "the per-container unit name carries no source/container tag")
    (h/with-async-test-context [ctx]
      (let [_ (extract/register-extract-subbehavior! ctx {})
            unit-id (extract/extract-per-container-sheet-id-for)]
        (is (some? (rm/get-sheet-by-name ctx (extract/extract-per-container-name)))
            "the per-container unit is registered + discoverable by name")
        (is (uuid? unit-id) "the per-container unit resolves to a deterministic sheet-id")))))

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

;; =============================================================================
;; MC-0 fix #3 — the APPLY :code node passes a BOUNDED :max-windows
;; =============================================================================
;; REGRESSION: without :max-windows the apply-step streams the FULL source
;; unbounded, so a pathologically large table (millions of rows) times out the
;; node wholesale. The fix passes :max-windows 50 to apply-extraction-transform!
;; so the huge-table case samples within the node timeout instead of hanging.
;; This test redef's apply-extraction-transform! to CAPTURE its args (no stream)
;; and asserts the bound is present + finite. Pre-fix: :max-windows is absent
;; from the captured args (nil) → RED.

(deftest apply-node-passes-bounded-max-windows-test
  (testing "the APPLY :code node bounds apply-extraction-transform! with a finite
            :max-windows so an unbounded stream can't recur forever on a huge table"
    (let [captured (atom nil)]
      (with-redefs [rlm-discovery/apply-extraction-transform!
                    (fn [args]
                      (reset! captured args)
                      {:concept-drafts [] :relationship-drafts []
                       :selector nil :rows-streamed 0 :rows-ok 0 :rows-errored 0
                       :windows 0 :errors-sample []})]
        (extract/apply-transform-code
         {:inputs {:source {:type :sql :path "/tmp/whatever.db"}
                   :transform-source "(fn [row] {:concept-drafts [] :relationship-drafts []})"
                   :selector "some_table"}})
        (let [mw (:max-windows @captured)]
          (is (some? mw)
              "the APPLY node MUST pass :max-windows (absent → unbounded stream → timeout)")
          (is (and (integer? mw) (pos? mw))
              ":max-windows must be a positive finite bound")
          (is (= 50 mw)
              "the bound is the proven 50-window ceiling"))))))

;; =============================================================================
;; MC-5 — the per-container SAMPLE / APPLY fns key on the CURRENT container
;; =============================================================================
;; The per-container wrappers REUSE mechanical-sample-rows + apply-extraction-
;; transform! but FORCE the selector to the container the loop is traversing (its
;; :name), so a many-container source grounds + applies EACH container — not the
;; default-container heuristic's largest. These hermetic tests capture the selector
;; passed to the reused fns (no real source) and assert the container wins.

(deftest per-container-sample-keys-on-the-container-name-test
  (testing "sample-rows-for-container-code passes the CONTAINER's :name as the
            selector to the reused mechanical-sample-rows (not the default heuristic)"
    (let [captured (atom nil)]
      (with-redefs [dt/mechanical-sample-rows
                    (fn [_source selector & _]
                      (reset! captured selector)
                      [{"k" "v"}])]
        (extract/sample-rows-for-container-code
         {:inputs {:source {:type :sql :path "/tmp/x.db"}
                   :container {:name "container_two"}}})
        (is (= "container_two" @captured)
            "the SAMPLE wrapper grounds on the CURRENT container, not the largest")))))

(deftest per-container-apply-forces-the-container-selector-test
  (testing "apply-transform-for-container-code FORCES the container :name as the
            selector (the container wins over the author-emitted :selector) so the
            transform is applied to the container it was grounded on"
    (let [captured (atom nil)]
      (with-redefs [rlm-discovery/apply-extraction-transform!
                    (fn [args]
                      (reset! captured args)
                      {:concept-drafts [] :relationship-drafts []
                       :selector (:selector args) :rows-streamed 0 :rows-ok 0
                       :rows-errored 0 :windows 0 :errors-sample []})]
        (extract/apply-transform-for-container-code
         {:inputs {:source {:type :sql :path "/tmp/x.db"}
                   :transform-source "(fn [row] {:concept-drafts [] :relationship-drafts []})"
                   :selector "author_guessed_table"   ; the author's loose guess
                   :container {:name "the_real_container"}}})
        (is (= "the_real_container" (:selector @captured))
            "the CONTAINER selector wins over the author-emitted :selector")
        (is (= 50 (:max-windows @captured))
            "the per-container APPLY keeps the bounded 50-window ceiling (MC-0 guard)"))))
  (testing "with NO container (single-container source) it falls back to the
            author-emitted :selector — the original single-container behavior"
    (let [captured (atom nil)]
      (with-redefs [rlm-discovery/apply-extraction-transform!
                    (fn [args] (reset! captured args)
                      {:concept-drafts [] :relationship-drafts []
                       :selector (:selector args) :rows-streamed 0 :rows-ok 0
                       :rows-errored 0 :windows 0 :errors-sample []})]
        (extract/apply-transform-for-container-code
         {:inputs {:source {:type :csv :path "/tmp/x.csv"}
                   :transform-source "(fn [row] {})"
                   :selector "author_selector"}})
        (is (= "author_selector" (:selector @captured))
            "no container → the author selector is used (single-container path)")))))

;; =============================================================================
;; GC-11a — the APPLY node threads the model-spec's :linking-keys into the apply
;; step so the deterministic linking-key VALUE carry runs. The NAMES are runtime
;; discovery (off the model-spec); absent → no-op (behavior-preserving).
;; =============================================================================

(deftest gc11a-apply-node-threads-model-spec-linking-keys-test
  (testing "apply-transform-for-container-code passes the model-spec's :linking-keys
            to apply-extraction-transform! (so the deterministic VALUE carry runs);
            a model-spec without :linking-keys threads nil (the no-op path)"
    (let [captured (atom nil)]
      (with-redefs [rlm-discovery/apply-extraction-transform!
                    (fn [args]
                      (reset! captured args)
                      {:concept-drafts [] :relationship-drafts []
                       :selector (:selector args) :rows-streamed 0 :rows-ok 0
                       :rows-errored 0 :windows 0 :errors-sample []})]
        ;; model-spec carrying discovered linking-keys → threaded through
        (extract/apply-transform-for-container-code
         {:inputs {:source {:type :sql :path "/tmp/x.db"}
                   :transform-source "(fn [row] {:concept-drafts [] :relationship-drafts []})"
                   :container {:name "t"}
                   :model-spec {:entity-types [] :linking-keys ["CIPCODE"]}}})
        (is (= ["CIPCODE"] (:linking-keys @captured))
            "the model-spec's discovered :linking-keys are threaded into the apply step")
        ;; model-spec WITHOUT linking-keys → nil threaded (the no-op carry path)
        (extract/apply-transform-for-container-code
         {:inputs {:source {:type :sql :path "/tmp/x.db"}
                   :transform-source "(fn [row] {:concept-drafts [] :relationship-drafts []})"
                   :container {:name "t"}
                   :model-spec {:entity-types []}}})
        (is (nil? (:linking-keys @captured))
            "no :linking-keys on the model-spec → nil threaded (behavior-preserving no-op)")))))

;; =============================================================================
;; MC-5 — list-source-containers enumerates the source's containers (the loop set)
;; =============================================================================

(deftest list-source-containers-enumerates-via-the-contract-test
  (testing "list-source-containers returns the contract's containers; an
            unresolvable source returns [] (never throws)"
    ;; an unresolvable / text source has no container contract → [] (not a throw).
    (is (= [] (extract/list-source-containers {:type :text :path "x"}))
        "a source with no container contract enumerates to [] cleanly")))

(deftest orchestrator-bounds-traversal-and-reports-total-honestly-test
  (testing "the orchestrator bounds the container traversal (MC-0) and reports the
            TOTAL vs the PROCESSED count honestly (no silent truncation)"
    (let [many (mapv #(hash-map :name (str "c" %)) (range 10))
          tick->c (atom {})]
      (with-redefs [extract/list-source-containers (fn [_] many)
                    extract/extract-per-container-sheet-id-for (fn [] (random-uuid))
                    dsl/execute (fn [_ _ inputs & {:keys [tick-id]}]
                                  (swap! tick->c assoc tick-id (:name (get inputs "container")))
                                  {:status :success})
                    dsl/get-tick-blackboard
                    (fn [_ tick-id]
                      {:concept-drafts {:value [{:uri (str "u-" (get @tick->c tick-id))
                                                 :label "x"}]}
                       :relationship-drafts {:value []}
                       :extraction-report {:value {:rows-streamed 10 :rows-errored 0}}})]
        (let [out (extract/orchestrate-extract-containers
                   {:inputs {:source {:type :sql :path "/tmp/x.db"}
                             :model-spec {} :max-containers 3}
                    :tick-id (random-uuid) :event-store :stub})
              report (:extraction-report out)]
          (is (= 10 (:containers-total report)) "the report counts ALL containers in the source")
          (is (= 3 (:containers-processed report)) "only the capped count was processed")
          (is (= 3 (count (:concept-drafts out)))
              "drafts come only from the processed containers (honest, not the full 10)"))))))

;; =============================================================================
;; MT-2 — the orchestrator consumes an OPTIONAL `:selected-containers` (the survey-
;; driven relevance selection) instead of the blind `(take cap …)`. When present,
;; it drives child ticks for EXACTLY those containers (already pre-filtered + ranked
;; + bounded upstream); when ABSENT, the existing take-cap path is UNCHANGED (back-
;; compat — the csv single-container path + every existing extract test stays green).
;; =============================================================================

(deftest orchestrator-consumes-selected-containers-not-take-cap-test
  (testing "given :selected-containers, the orchestrator drives child ticks for
            EXACTLY those containers (not take-cap-first-N of the raw list)"
    (let [;; the raw source lists c0..c9 ALPHABETICALLY; take-cap would grab c0,c1,c2.
          raw (mapv #(hash-map :name (str "c" %)) (range 10))
          ;; the upstream selection chose two LATER containers (never in the take-3
          ;; prefix) — proving the orchestrator follows the SELECTION, not the raw take.
          selected [{:name "c7" :path "/wb" :sheet "c7" :shape :entity :roles {:key "k"}}
                    {:name "c4" :path "/wb" :sheet "c4" :shape :long-form :roles {:key "k"}}]
          driven (atom [])
          tick->c (atom {})]
      (with-redefs [extract/list-source-containers (fn [_] raw)
                    extract/extract-per-container-sheet-id-for (fn [] (random-uuid))
                    dsl/execute (fn [_ _ inputs & {:keys [tick-id]}]
                                  (let [c (get inputs "container")]
                                    (swap! driven conj (:name c))
                                    (swap! tick->c assoc tick-id (:name c))
                                    {:status :success}))
                    dsl/get-tick-blackboard
                    (fn [_ tick-id]
                      {:concept-drafts {:value [{:uri (str "u-" (get @tick->c tick-id))
                                                 :label "x"}]}
                       :relationship-drafts {:value []}
                       :extraction-report {:value {:rows-streamed 5 :rows-errored 0}}})]
        (let [out (extract/orchestrate-extract-containers
                   {:inputs {:source {:type :excel :path "/wb"}
                             :model-spec {} :max-containers 3
                             :selected-containers selected}
                    :tick-id (random-uuid) :event-store :stub})
              report (:extraction-report out)]
          ;; EXACTLY the selected containers were driven (child ticks run under
          ;; GC-13 BOUNDED CONCURRENCY, so start-order is non-deterministic — assert
          ;; the SET here; selection ORDER is asserted via the order-preserving
          ;; concept-drafts below).
          (is (= #{"c7" "c4"} (set @driven))
              "child ticks ran for exactly the selected containers (not the raw take-cap)")
          (is (= 2 (count @driven)) "no extra / duplicate containers were driven")
          ;; the accumulated drafts are order-preserving (mapv deref in input order),
          ;; so they follow the SELECTION order [c7 c4] — from the SELECTED containers,
          ;; never the take-cap prefix c0/c1/c2.
          (is (= ["u-c7" "u-c4"] (map :uri (:concept-drafts out)))
              "drafts come from the SELECTED containers, in selection order (not c0/c1/c2)")
          (is (= 2 (:containers-processed report))
              "the processed count reflects the 2 selected, not the cap of 3")
          (is (= 10 (:containers-total report))
              "the total still counts ALL containers in the source (honest report)"))))))

(deftest orchestrator-absent-selected-containers-uses-take-cap-unchanged-test
  (testing "when :selected-containers is ABSENT, the orchestrator falls back to the
            existing take-cap path UNCHANGED (back-compat)"
    (let [raw (mapv #(hash-map :name (str "c" %)) (range 10))
          driven (atom [])
          tick->c (atom {})]
      (with-redefs [extract/list-source-containers (fn [_] raw)
                    extract/extract-per-container-sheet-id-for (fn [] (random-uuid))
                    dsl/execute (fn [_ _ inputs & {:keys [tick-id]}]
                                  (let [c (get inputs "container")]
                                    (swap! driven conj (:name c))
                                    (swap! tick->c assoc tick-id (:name c))
                                    {:status :success}))
                    dsl/get-tick-blackboard
                    (fn [_ tick-id]
                      {:concept-drafts {:value [{:uri (str "u-" (get @tick->c tick-id))
                                                 :label "x"}]}
                       :relationship-drafts {:value []}
                       :extraction-report {:value {:rows-streamed 5 :rows-errored 0}}})]
        (let [out (extract/orchestrate-extract-containers
                   {:inputs {:source {:type :sql :path "/tmp/x.db"}
                             :model-spec {} :max-containers 3}   ; NO :selected-containers
                    :tick-id (random-uuid) :event-store :stub})]
          (is (= #{"c0" "c1" "c2"} (set @driven))
              "the take-cap-first-N path is UNCHANGED when no selection is supplied")
          (is (= ["u-c0" "u-c1" "u-c2"] (map :uri (:concept-drafts out)))
              "exactly the capped first-3 raw containers processed, in list order (back-compat)")
          (is (= 3 (count (:concept-drafts out)))
              "exactly the capped 3 raw containers were processed (back-compat)"))))))

;; =============================================================================
;; ER-1/ER-2 — the orchestrator NORMALIZES a string-form model-spec BEFORE it
;; forwards it to the per-container AUTHOR. The C1 parse of `:entity-types` is
;; intermittent (arrives as parsed data OR an un-parsed EDN STRING). The pipeline
;; threads the RAW model-spec to Extract (central_evolver's normalize runs
;; POST-extraction), so an un-coerced string would make the AUTHOR ground in
;; garbage + fall back to generic keys (→ 0 concepts on a heterogeneous sheet).
;; =============================================================================

(deftest orchestrator-normalizes-string-model-spec-before-forwarding-test
  (testing "a STRING-form :entity-types is coerced to a parsed vector-of-maps
            BEFORE being forwarded to the per-container child tick — so the AUTHOR
            grounds in real entity types, not the string's characters"
    (let [captured (atom :unset)]
      (with-redefs [extract/list-source-containers (fn [_] [{:name "sheet1"}])
                    extract/extract-per-container-sheet-id-for (fn [] (random-uuid))
                    dsl/execute (fn [_ _ inputs & _]
                                  (reset! captured (get inputs "model-spec"))
                                  {:status :success})
                    dsl/get-tick-blackboard
                    (fn [_ _] {:concept-drafts {:value []}
                               :relationship-drafts {:value []}
                               :extraction-report {:value {:rows-streamed 0 :rows-errored 0}}})]
        (extract/orchestrate-extract-containers
         {:inputs {:source {:type :excel :path "/tmp/x"}
                   ;; the C1 fragility: :entity-types as an UN-PARSED EDN STRING.
                   :model-spec {:entity-types "[{:type \"Occupation\" :uri-keying-fields [\"soc\"]}]"}
                   :max-containers 1}
          :tick-id (random-uuid) :event-store :stub})
        (let [ets (:entity-types @captured)]
          (is (vector? ets)
              "forwarded :entity-types is a parsed VECTOR (not the raw string the AUTHOR would choke on)")
          (is (= 1 (count ets)) "the one entity-type map survived the coercion")
          (is (= "Occupation" (:type (first ets)))
              "the entity-type map parsed correctly (real :type, not a nil-iterating char)"))))))

;; =============================================================================
;; MC-5 — the orchestrator ACCUMULATES per-container drafts + an HONEST report
;; =============================================================================
;; Hermetic: redef the per-container child tick (dsl/execute + get-tick-blackboard)
;; + list-source-containers so we drive a controlled 3-container scenario — two
;; containers yield drafts, one yields NOTHING (a clean per-container :failure with
;; a :diagnosis). Asserts: union accumulation across the producing containers, and
;; the report HONESTLY surfaces the 0-draft / failed container (no false-green, #4).

(deftest orchestrator-accumulates-and-reports-honestly-test
  (testing "the orchestrator unions drafts across containers + reports per-container
            coverage honestly (a 0-draft / cleanly-failed container surfaces)"
    (let [;; per-container child-tick blackboards keyed by container name.
          child-bbs
          {"c-one" {:concept-drafts {:value [{:uri "u1" :label "a"} {:uri "u2" :label "b"}]}
                    :relationship-drafts {:value [{:source-uri "u1" :target-uri "u2"
                                                   :predicate "p"}]}
                    :extraction-report {:value {:rows-streamed 100 :rows-errored 0}}}
           "c-two" {:concept-drafts {:value [{:uri "u3" :label "c"}]}
                    :relationship-drafts {:value []}
                    :extraction-report {:value {:rows-streamed 50 :rows-errored 0}}}
           ;; c-three yields NOTHING + fails cleanly with a diagnosis (the gate fired).
           "c-three" {:concept-drafts {:value []}
                      :relationship-drafts {:value []}
                      :extraction-report {:value {:rows-streamed 0 :rows-errored 0}}
                      :diagnosis {:value {:root-cause "mis-grounded field access"
                                          :recoverable? false}}}}
          tick->container (atom {})]
      (with-redefs [extract/list-source-containers
                    (fn [_source] [{:name "c-one"} {:name "c-two"} {:name "c-three"}])
                    extract/extract-per-container-sheet-id-for
                    (fn [] (random-uuid))
                    ;; the child tick: record which container this tick is for, by
                    ;; the "container" input, and return a status mirroring the bb.
                    dsl/execute
                    (fn [_ctx _sid inputs & {:keys [tick-id]}]
                      (let [cname (get inputs "container")
                            cname (:name cname)]
                        (swap! tick->container assoc tick-id cname)
                        {:status (if (= cname "c-three") :failure :success)}))
                    dsl/get-tick-blackboard
                    (fn [_ctx tick-id]
                      (get child-bbs (get @tick->container tick-id)))]
        (let [out (extract/orchestrate-extract-containers
                   {:inputs {:source {:type :sql :path "/tmp/x.db"}
                             :model-spec {:entity-types []}}
                    :tick-id (random-uuid)
                    :event-store :stub})
              report (:extraction-report out)]
          ;; UNION accumulation across the two producing containers (not just one).
          (is (= 3 (count (:concept-drafts out)))
              "concept-drafts is the UNION across containers (2 + 1 + 0 = 3)")
          (is (= 1 (count (:relationship-drafts out)))
              "relationship-drafts union across containers")
          (is (= #{"u1" "u2" "u3"} (set (map :uri (:concept-drafts out))))
              "drafts from MULTIPLE containers are present (not one-container-of-many)")
          ;; HONEST per-container report — no false-green.
          (is (= 3 (:containers-total report)) "all 3 containers in the source are counted")
          (is (= 3 (:containers-processed report)) "all 3 were processed (under the cap)")
          (is (= 3 (:containers-seen report)) "all 3 containers seen")
          (is (= 2 (:containers-streamed report)) "2 containers actually streamed rows")
          (is (= 2 (:containers-with-drafts report)) "2 containers produced drafts")
          (is (= 1 (:containers-failed report))
              "the 0-draft container that failed its gate SURFACES (no false-green)")
          (is (= 3 (:concept-count report)) "aggregate concept-count is honest")
          (let [c3 (first (filter #(= "c-three" (:container %)) (:per-container report)))]
            (is (= :failure (:status c3))
                "the failed container is recorded as :failure in the breakdown")
            (is (= 0 (:concept-count c3)) "its 0-draft count is surfaced")
            (is (some? (:diagnosis c3))
                "its troubleshoot :diagnosis crosses back into the report")))))))

;; =============================================================================
;; MC-6 — CROSS-CONTAINER relating via the relations contract (the within-source
;; multi-table EDGES). The per-container passes (MC-5) yield concepts from N
;; containers but relationship-count = 0 (each transform only emits intra-row
;; edges). MC-6 uses each source's `relations` ({:from :to :via}) to JOIN entities
;; ACROSS containers by the shared :via key VALUE — deterministic (:code set logic,
;; NO :llm), domain-agnostic (the key comes from relations, not hardcoded).
;;
;; KEY-VALUE RECOVERY (the wrinkle): each cross-container edge needs each entity's
;; :via key VALUE to match A vs B. We recover it from the concept-draft's
;; :attributes — the AUTHOR is prompted to "carry the row's measures as
;; :attributes", so the row's key columns live there. The match is case/type-
;; tolerant (the :via column name from relations is normalized + compared against
;; the draft's attribute keys), so a keyword vs string vs cased key still resolves.
;; We do NOT parse the URI (composite/prefixed URIs make single-key extraction
;; brittle + couple to a minting convention). When a draft carries no attribute
;; matching the :via key, that entity contributes no edge (honest); when a whole
;; relation materializes zero pairs it surfaces in the report as an
;; :unmaterialized-relations entry — NEVER a fabricated edge.
;; =============================================================================

;; ---- Pure cross-container matching logic (hermetic, fast — brick gate) ----

(deftest cross-container-drafts-join-different-typed-entities-by-shared-via-value-test
  (testing "two containers' drafts sharing a :via key VALUE (carried in
            :attributes) yield a cross-container relationship-draft per matched
            pair; different-typed entities (different URI shapes) link"
    (let [;; container A entities carry the join key in :attributes (the row field
          ;; the AUTHOR carried). DIFFERENT URI shape than B (so they do NOT
          ;; reconcile-merge — they need an EDGE).
          a-results
          [{:container "table_a"
            :concept-drafts
            [{:uri "program:p1" :label "P1" :attributes {:join_key "k100"}}
             {:uri "program:p2" :label "P2" :attributes {:join_key "k200"}}]}]
          b-results
          [{:container "table_b"
            :concept-drafts
            [{:uri "place:k100" :label "Place 100" :attributes {:join_key "k100"}}
             {:uri "place:k999" :label "Place 999" :attributes {:join_key "k999"}}]}]
          ;; relations: table_a links to table_b via the shared "join_key" column.
          relations-fn (fn [container-name]
                         (case container-name
                           "table_a" [{:from "table_a.join_key"
                                       :to "table_b.join_key" :via "join_key"}]
                           []))
          out (extract/cross-container-relationship-drafts
               (concat a-results b-results) relations-fn)
          edges (:relationship-drafts out)]
      (is (= 1 (count edges))
          "exactly ONE edge — only k100 is shared across the two containers")
      (let [e (first edges)]
        (is (= "program:p1" (:source-uri e)) "the A-side entity is the edge source")
        (is (= "place:k100" (:target-uri e)) "the B-side entity is the edge target")
        (is (string? (:predicate e)) "a derived predicate is present")
        (is (str/includes? (:predicate e) "join_key")
            "the predicate is derived from the relation's :via (domain-agnostic)"))
      (is (every? #(and (:source-uri %) (:target-uri %) (:predicate %)) edges)
          "every cross-container edge carries source-uri + target-uri + predicate"))))

(deftest cross-container-via-value-is-case-and-type-tolerant-test
  (testing "the :via value is recovered case/type-tolerantly from :attributes —
            a keyword key, a string key, a differently-cased key all resolve to the
            same join value (so an AUTHOR's key representation does not break it)"
    (let [a [{:container "a"
              :concept-drafts [{:uri "x:1" :label "x" :attributes {:MyKey "v1"}}]}]
          b [{:container "b"
              :concept-drafts [{:uri "y:1" :label "y" :attributes {"mykey" "v1"}}]}]
          rel-fn (fn [c] (if (= c "a")
                           [{:from "a.MyKey" :to "b.MyKey" :via "MyKey"}] []))
          edges (:relationship-drafts
                 (extract/cross-container-relationship-drafts (concat a b) rel-fn))]
      (is (= 1 (count edges))
          "keyword :MyKey on A and string \"mykey\" on B match the same join value"))))

(deftest cross-container-arbitrary-made-up-column-domain-agnostic-test
  (testing "domain-agnostic: an ARBITRARY made-up join column (no domain meaning)
            yields the cross-container edges via that column — no baked field name"
    (let [a [{:container "left"
              :concept-drafts [{:uri "l:a" :label "a" :attributes {:wibble "ZZ"}}
                               {:uri "l:b" :label "b" :attributes {:wibble "QQ"}}]}]
          b [{:container "right"
              :concept-drafts [{:uri "r:a" :label "a" :attributes {:wibble "ZZ"}}]}]
          rel-fn (fn [c] (if (= c "left")
                           [{:from "left.wibble" :to "right.wibble" :via "wibble"}] []))
          edges (:relationship-drafts
                 (extract/cross-container-relationship-drafts (concat a b) rel-fn))]
      (is (= 1 (count edges)) "only the shared :wibble value ZZ links across containers")
      (is (= "l:a" (:source-uri (first edges))))
      (is (= "r:a" (:target-uri (first edges)))))))

(deftest cross-container-honest-negative-when-via-value-not-carriable-test
  (testing "a relation whose :via key is carried by NEITHER side's :attributes
            materializes NO edge AND surfaces in :unmaterialized-relations (an
            honest negative, NOT a fabricated edge — #4/#5)"
    (let [;; the drafts carry SOME attributes but NOT the relation's :via column.
          a [{:container "a"
              :concept-drafts [{:uri "a:1" :label "a" :attributes {:other "x"}}]}]
          b [{:container "b"
              :concept-drafts [{:uri "b:1" :label "b" :attributes {:other "x"}}]}]
          rel-fn (fn [c] (if (= c "a")
                           [{:from "a.missing_key" :to "b.missing_key"
                             :via "missing_key"}] []))
          out (extract/cross-container-relationship-drafts (concat a b) rel-fn)]
      (is (empty? (:relationship-drafts out))
          "NO edge fabricated when the join value cannot be recovered from either side")
      (is (seq (:unmaterialized-relations out))
          "the unmaterialized relation is surfaced HONESTLY (no silent drop)")
      (let [u (first (:unmaterialized-relations out))]
        (is (= "missing_key" (:via u)) "the surfaced entry names the via that failed")
        (is (some? (:reason u)) "the entry carries a human reason")))))

(deftest cross-container-same-uri-not-edged-only-different-uris-edged-test
  (testing "two containers that mint the SAME URI for the same key already
            reconcile-MERGE (no edge needed) — MC-6 emits NO self-edge for them;
            only DIFFERENT-typed entities sharing the key are edged"
    (let [a [{:container "a"
              :concept-drafts [{:uri "shared:k1" :label "k1" :attributes {:k "k1"}}]}]
          b [{:container "b"
              :concept-drafts [{:uri "shared:k1" :label "k1" :attributes {:k "k1"}}]}]
          rel-fn (fn [c] (if (= c "a") [{:from "a.k" :to "b.k" :via "k"}] []))
          edges (:relationship-drafts
                 (extract/cross-container-relationship-drafts (concat a b) rel-fn))]
      (is (empty? (filter #(= (:source-uri %) (:target-uri %)) edges))
          "no SELF-edge for entities that minted the SAME URI (they reconcile-merge)"))))

(deftest cross-container-no-relations-no-edges-csv-single-container-unchanged-test
  (testing "a source with no relations (csv single container → relations-fn nil
            or empty) yields NO cross-container edges and is NOT an error"
    (let [results [{:container "only" :concept-drafts [{:uri "c:1" :label "1"
                                                        :attributes {:k "v"}}]}]]
      ;; nil relations-fn (csv exposes no relations op) → empty, clean.
      (is (empty? (:relationship-drafts
                   (extract/cross-container-relationship-drafts results nil)))
          "nil relations-fn → no cross-container edges (csv single-container path)")
      ;; a relations-fn returning [] for the lone container → empty, clean.
      (is (empty? (:relationship-drafts
                   (extract/cross-container-relationship-drafts
                    results (constantly []))))
          "empty relations → no cross-container edges, not an error"))))

;; =============================================================================
;; GC-2 — BOUND within-source / cross-container relating (fix the MC-6 O(A×B) edge
;; explosion). The MC-6 nested loop pairs EVERY row × EVERY row per shared :via
;; value, so a completions-style table (dozens of rows per institution id) blows
;; the edge count up combinatorially (the build-#1 9GB OOM). GC-1 made every
;; draft's :uri CANONICAL, so two ROWS for the SAME real entity now carry the SAME
;; :uri. GC-2 composes with that: dedupe each :via bucket to DISTINCT canonical
;; :uri BEFORE pairing (case (a) — row-multiplication — collapses to one entity),
;; then cap the genuine distinct-entity fan-out per :via value HONESTLY (case (b)
;; — a real 1:many fan-out — surfaces a :truncated-relations report entry, never a
;; silent drop). Re-orchestrate, not rewrite — the self-pair drop +
;; :unmaterialized honesty stay.
;; =============================================================================

(deftest gc2-entity-dedup-collapses-row-multiplication-test
  (testing "GC-2 case (a): side A has K ROWS that all share ONE canonical :uri for
            a :via value (the post-GC-1 row-multiplication shape — many completions
            rows for the same program), side B has 1 entity → exactly ONE edge, not
            K. The dedup collapses the rows to one entity BEFORE pairing"
    (let [k 40
          ;; A: 40 rows ALL minted to the SAME canonical :uri (GC-1 collapsed them),
          ;; all carrying the same :via value "u100" in :attributes. Pre-GC-2 this
          ;; bucket is 40 drafts → 40 × 1 = 40 edges; after dedup it is 1 distinct
          ;; entity → 1 edge.
          a [{:container "completions"
              :concept-drafts
              (vec (for [_ (range k)]
                     {:uri "programofstudy/01.0901" :label "Program 01.0901"
                      :attributes {:unitid "u100"}}))}]
          b [{:container "institutions"
              :concept-drafts
              [{:uri "institution/u100" :label "Inst 100"
                :attributes {:unitid "u100"}}]}]
          rel-fn (fn [c] (if (= c "completions")
                           [{:from "completions.unitid" :to "institutions.unitid"
                             :via "unitid"}] []))
          out (extract/cross-container-relationship-drafts (concat a b) rel-fn)
          edges (:relationship-drafts out)]
      (is (= 1 (count edges))
          "K rows sharing ONE canonical :uri → exactly ONE edge")
      ;; The edge COUNT alone can't prove the dedup (the post-pairing `seen` set
      ;; would also collapse K identical pairs to 1). The dedup-BEFORE-pairing is
      ;; observable in the bounded WORK: with the dedup, the loop considers 1×1 = 1
      ;; pair; reverting the dedup makes it iterate K×1 = K pairs (RED here).
      (is (= 1 (:pairs-considered out))
          "exactly ONE distinct-entity pair is considered — the dedup collapsed the
           K rows to one entity BEFORE pairing (reverting the dedup → K)")
      (let [e (first edges)]
        (is (= "programofstudy/01.0901" (:source-uri e)))
        (is (= "institution/u100" (:target-uri e)))))))

(deftest gc2-bounded-distinct-fan-out-surfaced-honestly-test
  (testing "GC-2 case (b): MANY DISTINCT entities share ONE :via value so the
            distinct-entity fan-out exceeds the cap → the edge count is BOUNDED at
            the cap AND the truncation is surfaced in :truncated-relations with the
            dropped count (never a silent top-N — #4/#5)"
    (let [na 30 nb 30                ;; 30 × 30 = 900 DISTINCT-entity pairs
          cap 100                    ;; an explicit small cap (hermetic, fast)
          ;; A: 30 DISTINCT entities (distinct canonical :uri), all carrying the
          ;; SAME :via value "k1" — a genuine 1:many fan-out, NOT row-multiplication.
          a [{:container "left"
              :concept-drafts
              (vec (for [i (range na)]
                     {:uri (str "atype/" i) :label (str "A" i)
                      :attributes {:k "k1"}}))}]
          b [{:container "right"
              :concept-drafts
              (vec (for [j (range nb)]
                     {:uri (str "btype/" j) :label (str "B" j)
                      :attributes {:k "k1"}}))}]
          rel-fn (fn [c] (if (= c "left")
                           [{:from "left.k" :to "right.k" :via "k"}] []))
          out (extract/cross-container-relationship-drafts (concat a b) rel-fn cap)
          edges (:relationship-drafts out)
          trunc (:truncated-relations out)]
      (is (= cap (count edges))
          "the edge count is BOUNDED at the cap (not the full 900 distinct pairs)")
      (is (seq trunc)
          "the truncation is SURFACED in :truncated-relations (not a silent drop)")
      (let [t (first trunc)]
        (is (= "k" (:via t)) "the truncated entry names the via that overflowed")
        (is (= "k1" (:value t)) "the truncated entry names the via VALUE that overflowed")
        (is (= (* na nb) (:distinct-pairs t))
            "the full distinct-pair count is reported honestly")
        (is (= cap (:cap t)) "the applied cap is reported")
        (is (= (- (* na nb) cap) (:dropped-pairs t))
            "the DROPPED count is visible (the honest negative — #4/#5)")
        (is (string? (:reason t)) "a human reason accompanies the truncation")))))

(deftest gc2-under-cap-fan-out-not-truncated-test
  (testing "a genuine distinct-entity fan-out UNDER the cap materializes EVERY pair
            and surfaces NO truncation (the cap admits real 1:many fan-out)"
    (let [na 5 nb 4 cap 100
          a [{:container "left"
              :concept-drafts (vec (for [i (range na)]
                                     {:uri (str "atype/" i) :attributes {:k "k1"}}))}]
          b [{:container "right"
              :concept-drafts (vec (for [j (range nb)]
                                     {:uri (str "btype/" j) :attributes {:k "k1"}}))}]
          rel-fn (fn [c] (if (= c "left")
                           [{:from "left.k" :to "right.k" :via "k"}] []))
          out (extract/cross-container-relationship-drafts (concat a b) rel-fn cap)]
      (is (= (* na nb) (count (:relationship-drafts out)))
          "every distinct pair under the cap is materialized")
      (is (empty? (:truncated-relations out))
          "no truncation surfaced when the fan-out is under the cap"))))

;; ---- The orchestrator wires the cross-container edges into its output ----

(deftest orchestrator-emits-cross-container-edges-from-relations-test
  (testing "the MC-5 orchestrator, after accumulating per-container drafts, uses the
            source's relations to emit CROSS-CONTAINER edges in :relationship-drafts
            (relationship-count > 0 vs the per-container 0) — hermetic, redef'd"
    (let [child-bbs
          {"a" {:concept-drafts {:value [{:uri "a:1" :label "a"
                                          :attributes {:link "shared"}}]}
                :relationship-drafts {:value []}
                :extraction-report {:value {:rows-streamed 10 :rows-errored 0}}}
           "b" {:concept-drafts {:value [{:uri "b:1" :label "b"
                                          :attributes {:link "shared"}}]}
                :relationship-drafts {:value []}
                :extraction-report {:value {:rows-streamed 10 :rows-errored 0}}}}
          tick->c (atom {})]
      (with-redefs [extract/list-source-containers
                    (fn [_] [{:name "a"} {:name "b"}])
                    extract/extract-per-container-sheet-id-for (fn [] (random-uuid))
                    ;; the source's relations: a links to b via "link".
                    extract/source-relations-fn
                    (fn [_source]
                      (fn [c] (if (= c "a")
                                [{:from "a.link" :to "b.link" :via "link"}] [])))
                    dsl/execute (fn [_ _ inputs & {:keys [tick-id]}]
                                  (swap! tick->c assoc tick-id
                                         (:name (get inputs "container")))
                                  {:status :success})
                    dsl/get-tick-blackboard
                    (fn [_ tick-id] (get child-bbs (get @tick->c tick-id)))]
        (let [out (extract/orchestrate-extract-containers
                   {:inputs {:source {:type :sql :path "/tmp/x.db"}
                             :model-spec {} :max-containers 5}
                    :tick-id (random-uuid) :event-store :stub})
              edges (:relationship-drafts out)
              report (:extraction-report out)]
          (is (pos? (count edges))
              "cross-container edges are emitted (relationship-count > 0, vs MC-5's 0)")
          (is (= "a:1" (:source-uri (first edges))))
          (is (= "b:1" (:target-uri (first edges))))
          (is (= (count edges) (:relationship-count report))
              "the report's relationship-count reflects the cross-container edges")
          (is (contains? report :cross-container-relations)
              "the report surfaces the cross-container relating summary")
          ;; GC-2 — the bounded-relating accounting surfaces at the public report.
          (is (contains? (:cross-container-relations report) :pairs-considered)
              "the report surfaces the bounded distinct-pair WORK (GC-2)")
          (is (contains? (:cross-container-relations report) :truncated)
              "the report surfaces the GC-2 truncation entries (honest, not silent)"))))))

(deftest gc2-orchestrator-surfaces-truncation-in-report-test
  (testing "GC-2: when a :via value's distinct fan-out exceeds the cap, the
            orchestrator's PUBLIC :extraction-report surfaces it in
            :cross-container-relations/:truncated with the dropped count — the
            honesty loop closes at the public surface, never a silent drop (#4/#5)"
    (let [;; container a: many DISTINCT entities all sharing ONE :via value, far over
          ;; the default cap; container b: 1 entity on that value. So the distinct
          ;; fan-out (na × 1) exceeds default-max-pairs-per-via.
          na (+ extract/default-max-pairs-per-via 25)
          child-bbs
          {"a" {:concept-drafts
                {:value (vec (for [i (range na)]
                               {:uri (str "atype/" i) :label (str "A" i)
                                :attributes {:link "shared"}}))}
                :relationship-drafts {:value []}
                :extraction-report {:value {:rows-streamed na :rows-errored 0}}}
           "b" {:concept-drafts
                {:value [{:uri "btype/1" :label "B1" :attributes {:link "shared"}}]}
                :relationship-drafts {:value []}
                :extraction-report {:value {:rows-streamed 1 :rows-errored 0}}}}
          tick->c (atom {})]
      (with-redefs [extract/list-source-containers
                    (fn [_] [{:name "a"} {:name "b"}])
                    extract/extract-per-container-sheet-id-for (fn [] (random-uuid))
                    extract/source-relations-fn
                    (fn [_source]
                      (fn [c] (if (= c "a")
                                [{:from "a.link" :to "b.link" :via "link"}] [])))
                    dsl/execute (fn [_ _ inputs & {:keys [tick-id]}]
                                  (swap! tick->c assoc tick-id
                                         (:name (get inputs "container")))
                                  {:status :success})
                    dsl/get-tick-blackboard
                    (fn [_ tick-id] (get child-bbs (get @tick->c tick-id)))]
        (let [out (extract/orchestrate-extract-containers
                   {:inputs {:source {:type :sql :path "/tmp/x.db"}
                             :model-spec {} :max-containers 5}
                    :tick-id (random-uuid) :event-store :stub})
              edges (:relationship-drafts out)
              ccr (get-in out [:extraction-report :cross-container-relations])]
          (is (= extract/default-max-pairs-per-via (count edges))
              "the edge count is BOUNDED at the cap (not the full na distinct pairs)")
          (is (pos? (:truncated-count ccr))
              "the report surfaces a non-zero truncated-count")
          (let [t (first (:truncated ccr))]
            (is (= na (:distinct-pairs t))
                "the full distinct-pair count is reported honestly")
            (is (= (- na extract/default-max-pairs-per-via) (:dropped-pairs t))
                "the DROPPED count is visible at the public surface (#4/#5)")))))))

;; ---------------------------------------------------------------------------
;; A real-Grain harness for the LAND + read-back tests (Discipline #7).
;; ---------------------------------------------------------------------------

(defn- make-grain-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/mc6-brick-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))]
    {:event-store store :cache cache :tenant-id (random-uuid)
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     :event-pubsub ps ::cache-dir dir}))

(defn- stop-grain-ctx [ctx]
  (rmp/l1-clear!)
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [s (:event-store ctx)] (es/stop s))
  (when-let [d (::cache-dir ctx)]
    (let [f (File. ^String d)]
      (when (.exists f) (doseq [c (.listFiles f)] (.delete c)) (.delete f)))))

(defmacro with-grain-ctx [[sym] & body]
  `(let [~sym (make-grain-ctx)]
     (try ~@body (finally (stop-grain-ctx ~sym)))))

;; ---- The cross-container edges LAND as graph edges (read the projection back) ----

(deftest cross-container-edges-land-and-read-back-from-projection-test
  (testing "the cross-container relationship-drafts flow through EB5 reconcile and
            LAND as graph edges read back from the projection (#7) — within-source
            multi-hop path: A → B → C joined by a shared key across 3 containers"
    (with-grain-ctx [ctx]
      (let [oid (random-uuid)
            ;; 3 containers, DIFFERENT-typed entities sharing a join key "K1".
            ;; A→B→C is the within-source multi-hop traversal.
            a [{:container "ca" :concept-drafts
                [{:uri "alpha:1" :label "Alpha-1" :attributes {:link "K1"}}]}]
            b [{:container "cb" :concept-drafts
                [{:uri "beta:1" :label "Beta-1" :attributes {:link "K1"}}]}]
            c [{:container "cc" :concept-drafts
                [{:uri "gamma:1" :label "Gamma-1" :attributes {:link "K1"}}]}]
            ;; relations: ca→cb, cb→cc, via "link" (the multi-hop chain).
            rel-fn (fn [container-name]
                     (case container-name
                       "ca" [{:from "ca.link" :to "cb.link" :via "link"}]
                       "cb" [{:from "cb.link" :to "cc.link" :via "link"}]
                       []))
            out (extract/cross-container-relationship-drafts (concat a b c) rel-fn)
            concept-drafts (vec (mapcat :concept-drafts (concat a b c)))
            rel-drafts (:relationship-drafts out)]
        (is (>= (count rel-drafts) 2)
            "≥2 cross-container edges authored (ca→cb and cb→cc) for the multi-hop chain")
        ;; LAND via the SAME EB5 reconcile the orchestrator output flows through.
        (recon/reconcile-drafts!
         ctx {:ontology-id oid
              :concept-drafts concept-drafts
              :relationship-drafts rel-drafts
              :source-uri-sets []
              :probe-signals #{:graph :lexical}})
        ;; DISCIPLINE 7 — read the EDGES back from the projection (not a return).
        (let [edges (filter #(= oid (:ontology-id %)) (orm/get-relationships ctx))
              by-pair (set (map (juxt :source-uri :target-uri) edges))]
          (is (pos? (count edges))
              "relationship-count > 0 read back from the projection (vs MC-5's 0)")
          (is (contains? by-pair ["alpha:1" "beta:1"])
              "the ca→cb cross-container edge landed")
          (is (contains? by-pair ["beta:1" "gamma:1"])
              "the cb→cc cross-container edge landed (the within-source multi-hop path)")
          ;; multi-hop read-back: alpha → beta → gamma is traversable in the graph.
          (let [hop1 (filter #(= "alpha:1" (:source-uri %)) edges)
                mid (set (map :target-uri hop1))
                hop2 (filter #(contains? mid (:source-uri %)) edges)]
            (is (some #(= "gamma:1" (:target-uri %)) hop2)
                "a 2-hop path alpha:1 → beta:1 → gamma:1 reads back (the traversal)")))))))

;; ---- LIVE IPEDS: within-source cross-container edges from real relations ----

(def ^:private ipeds-db
  (str (System/getProperty "user.home") "/Downloads/output.db"))

(defn- file-present? [^String p] (.exists (File. p)))

(defn- sqlite-uri-from-attr-concepts
  "Build per-container drafts from REAL IPEDS rows via the source's OWN tools — no
   LLM (the cross-container relating is deterministic). For each of two real
   relating tables, sample rows and mint one concept per row carrying the shared
   join column in :attributes (the AUTHOR's documented contract) under a
   container-distinct URI shape, so the two containers' same-key entities are
   DIFFERENT-typed (they need an edge, not a merge). Returns
   [per-container-results relations-fn] for the source — domain-agnostic: the join
   column is read from the source's relations, never hardcoded."
  [db-path]
  (let [tools ((requiring-resolve
                'ai.obney.orc.orc-service.core.source-tools-sql/sql-source-tools)
               {:db-path db-path})
        list-tables (get tools 'list-tables)
        relations   (get tools 'relations)
        sample      (get tools 'sample-rows)
        tables (list-tables)
        ;; find two distinct tables linked by relations + the via column.
        rel (->> tables
                 (mapcat (fn [t] (map #(assoc % :from-table t) (relations t))))
                 ;; pick a relation between two DISTINCT tables.
                 (filter (fn [{:keys [from to]}]
                           (not= (first (str/split from #"\."))
                                 (first (str/split to #"\.")))))
                 first)
        a-table (:from-table rel)
        b-table (first (str/split (:to rel) #"\."))
        via (:via rel)
        via-kw (keyword via)
        ;; sample a small bounded window from each real table.
        a-rows (sample a-table {:limit 20})
        b-rows (sample b-table {:limit 100})
        ;; mint container-distinct URIs so same-key entities are DIFFERENT-typed.
        mk (fn [prefix rows]
             (vec (for [r rows
                        :let [v (get r via-kw)]
                        :when (some? v)]
                    {:uri (str prefix ":" v)
                     :label (str prefix " " v)
                     :attributes {via-kw v}})))
        a-drafts (mk a-table a-rows)
        b-drafts (mk b-table b-rows)
        relations-fn (fn [container-name]
                       (if (= container-name a-table)
                         [{:from (str a-table "." via) :to (str b-table "." via)
                           :via via}]
                         []))]
    {:results [{:container a-table :concept-drafts a-drafts}
               {:container b-table :concept-drafts b-drafts}]
     :relations-fn relations-fn
     :a-table a-table :b-table b-table :via via
     :a-count (count a-drafts) :b-count (count b-drafts)}))

(deftest live-ipeds-within-source-cross-container-edges-land-test
  (testing "LIVE REAL IPEDS (skip-if-absent): the source's OWN relations join
            entities across two real tables by the shared key VALUE; the
            cross-container edges LAND + read back from the projection (#7) —
            relationship-count > 0 within ONE source (vs MC-5's 0)"
    (if-not (file-present? ipeds-db)
      (println "[MC-6] SKIP live IPEDS within-source edges — absent at" ipeds-db)
      (with-grain-ctx [ctx]
        (let [{:keys [results relations-fn a-table b-table via a-count b-count]}
              (sqlite-uri-from-attr-concepts ipeds-db)
              oid (random-uuid)
              out (extract/cross-container-relationship-drafts results relations-fn)
              rel-drafts (:relationship-drafts out)
              concept-drafts (vec (mapcat :concept-drafts results))]
          (println "[MC-6] live IPEDS relating" a-table "->" b-table "via" via
                   "| A-entities:" a-count "B-entities:" b-count
                   "| cross-container edges authored:" (count rel-drafts))
          (is (and (pos? a-count) (pos? b-count))
              "both real tables produced entities carrying the join key")
          (is (pos? (count rel-drafts))
              "cross-container edges are AUTHORED from real relations (the shared key
               actually joins entities across the two tables — not 0)")
          ;; LAND via EB5 reconcile + read the projection back (#7).
          (recon/reconcile-drafts!
           ctx {:ontology-id oid
                :concept-drafts concept-drafts
                :relationship-drafts rel-drafts
                :source-uri-sets []
                :probe-signals #{:graph :lexical}})
          (let [edges (filter #(= oid (:ontology-id %)) (orm/get-relationships ctx))]
            (println "[MC-6] live IPEDS within-source edges LANDED:" (count edges))
            (is (pos? (count edges))
                "within-source relationship-count > 0 read back from the projection")
            (is (every? #(and (:source-uri %) (:target-uri %)) edges)
                "every landed edge carries source + target (no dangling)")
            ;; the edge connects the two DIFFERENT tables' entities (the traversal).
            (is (some #(and (str/starts-with? (str (:source-uri %)) (str a-table ":"))
                            (str/starts-with? (str (:target-uri %)) (str b-table ":")))
                      edges)
                "a landed edge connects an A-table entity to a B-table entity
                 (the within-source cross-container traversal)")))))))

;; ---- GC-2 LIVE SCALE REGRESSION: real many-rows-per-key completions table ----

(defn- query-rows
  "Read a bounded result set from the real IPEDS sqlite as a vector of column-keyed
   maps (no LLM — the cross-container relating is deterministic)."
  [db-path sql]
  (with-open [conn (DriverManager/getConnection (str "jdbc:sqlite:" db-path))
              stmt (.createStatement conn)
              rs (.executeQuery stmt sql)]
    (let [md (.getMetaData rs)
          n (.getColumnCount md)
          cols (mapv #(.getColumnName md (inc %)) (range n))]
      (loop [acc []]
        (if (.next rs)
          (recur (conj acc (into {} (map (fn [c] [c (.getString rs ^String c)]) cols))))
          acc)))))

(deftest gc2-live-scale-regression-many-rows-per-key-bounded-test
  (testing "GC-2 LIVE (skip-if-absent): the REAL IPEDS completions table (C2022_A —
            dozens of rows per institution id, the actual build-#1 9GB OOM driver),
            joined against itself as two DIFFERENT-typed containers via UNITID,
            yields a BOUNDED edge count completing at a sane heap. Post-GC-1 each row
            mints a canonical programofstudy/<cip> (resp. award/<cip>), so the dedup
            collapses the rows-per-entity multiplication and the cap bounds the
            genuine distinct-entity fan-out. Reverting either blows the WORK up
            (12M+ rows×rows iterations on just 50 institutions)."
    (if-not (file-present? ipeds-db)
      (println "[GC-2] SKIP live scale regression — IPEDS absent at" ipeds-db)
      (let [;; bound the probe to a window of institutions (each carries dozens-to-
            ;; hundreds of completions rows — the row-multiplication is real even
            ;; within this window).
            units (mapv #(get % "UNITID")
                        (query-rows ipeds-db
                                    "SELECT DISTINCT UNITID FROM C2022_A
                                     ORDER BY UNITID LIMIT 50"))
            in-clause (str/join "," (map #(str "'" % "'") units))
            raw (query-rows ipeds-db
                            (str "SELECT UNITID, CIPCODE FROM C2022_A
                                  WHERE UNITID IN (" in-clause ")"))
            ;; POST-GC-1 canonical shape: many rows for the same (unitid,cip) share
            ;; ONE canonical :uri; distinct programs per unitid are distinct entities.
            a-drafts (mapv (fn [r] {:uri (str "programofstudy/" (get r "CIPCODE"))
                                    :attributes {:unitid (get r "UNITID")}}) raw)
            b-drafts (mapv (fn [r] {:uri (str "award/" (get r "CIPCODE"))
                                    :attributes {:unitid (get r "UNITID")}}) raw)
            results [{:container "completions_a" :concept-drafts a-drafts}
                     {:container "completions_b" :concept-drafts b-drafts}]
            rel-fn (fn [c] (if (= c "completions_a")
                             [{:from "completions_a.unitid"
                               :to "completions_b.unitid" :via "unitid"}] []))
            ;; the NAIVE rows×rows iteration count the reverted loop would perform —
            ;; the OOM driver, computed here for the regression assertion.
            a-by (group-by #(get-in % [:attributes :unitid]) a-drafts)
            b-by (group-by #(get-in % [:attributes :unitid]) b-drafts)
            naive (reduce + 0 (for [u units :when (and (a-by u) (b-by u))]
                                (* (count (a-by u)) (count (b-by u)))))
            out (extract/cross-container-relationship-drafts results rel-fn)
            edges (:relationship-drafts out)
            trunc (:truncated-relations out)]
        (println "[GC-2] live scale: 50 UNITIDs |" (count raw) "raw completions rows |"
                 "naive rows×rows iterations:" naive
                 "| GREEN edges:" (count edges)
                 "| pairs-considered:" (:pairs-considered out)
                 "| truncated entries:" (count trunc))
        (is (pos? (count raw))
            "the real completions table produced rows (the live source is present)")
        (is (> naive 1000000)
            "the NAIVE rows×rows pairing is a genuine combinatorial blow-up (>1M
             iterations on just 50 institutions — the real OOM driver)")
        ;; the BOUND: the deduped+capped work is dramatically smaller than naive AND
        ;; the per-via cap holds every edge group.
        (is (< (:pairs-considered out) (quot naive 10))
            "the deduped+capped WORK is >10× smaller than the naive rows×rows blow-up
             (the dedup collapsed the row-multiplication BEFORE pairing)")
        ;; the cap is per-via-VALUE: any value whose distinct fan-out exceeded the cap
        ;; appears in :truncated-relations with :distinct-pairs > cap, and the kept
        ;; pairs for it never exceed the cap. (The total edge count spans MANY via
        ;; values, so it is legitimately larger than the per-value cap.)
        (is (every? #(<= extract/default-max-pairs-per-via (:distinct-pairs %)) trunc)
            "every truncated via-value genuinely exceeded the per-value cap (the bound
             only fires on real combinatorial fan-out, not on small groups)")
        ;; HONEST truncation: where a real institution genuinely fans out past the
        ;; cap, the drop is SURFACED — never silent (#4/#5).
        (when (seq trunc)
          (is (every? #(and (pos? (:dropped-pairs %)) (string? (:reason %))) trunc)
              "every truncation surfaces its dropped count + reason (honest, not
               silent — #4/#5)"))
        (is (every? #(and (:source-uri %) (:target-uri %) (:predicate %)) edges)
            "every bounded edge carries source + target + predicate (no dangling)")))))

;; ---------------------------------------------------------------------------
;; GC-7 — the extract window cap is HONEST: the report flags :truncated? when the
;; stream hit the :max-windows ceiling (a huge table sampled, not exhausted), and
;; :max-windows is the named default (caller-overridable). Deterministic, no LLM.
;; ---------------------------------------------------------------------------

(deftest gc7-extract-truncation-signal-is-honest-test
  (testing "extract-truncated? is the ceiling-bit signal: windows AT/ABOVE the cap
            → truncated (table had more rows); below the cap → table exhausted"
    ;; the cap bit — windows hit the ceiling, more rows almost certainly remained
    (is (true? (extract/extract-truncated? 50 50)))
    (is (true? (extract/extract-truncated? 51 50)))
    ;; the table was exhausted before the ceiling — NOT truncated (honest)
    (is (false? (extract/extract-truncated? 7 50)))
    (is (false? (extract/extract-truncated? 0 50)))
    ;; defensive: non-numbers don't claim truncation
    (is (false? (extract/extract-truncated? nil 50))))
  (testing "the default window ceiling is the value the apply nodes used pre-GC-7
            (behavior-preserving), now NAMED + overridable"
    (is (= 50 extract/default-max-extract-windows))))

;; ===========================================================================
;; GC-13 — BOUNDED-PARALLEL per-container extraction (the measured bottleneck).
;;
;; model-extract = 55.6% of build wall-clock; its cost is the per-container
;; SAMPLE→AUTHOR→APPLY child ticks run SERIALLY in a `mapv`. GC-13 replaces that
;; serial loop with a BOUNDED-concurrency parallel map over the SAME child-tick
;; mechanism. These tests lock the helper's three load-bearing properties
;; (order preservation, the concurrency BOUND, and honest per-item failure
;; isolation) through its public seam, plus the orchestrator's honest surfacing
;; of a THROWING child tick (no false-green, no silent drop). The serial vs
;; parallel DRAFT-EQUIVALENCE is guarded by the existing
;; `orchestrator-accumulates-and-reports-honestly-test` (same stubbed seam).
;; ===========================================================================

;; --- GC-13 cycle 1: bounded-parallel helper preserves INPUT ORDER ---
(deftest gc13-bounded-parallel-map-preserves-order-test
  (testing "bounded-parallel-map returns results in INPUT order even when the
            per-item thunks complete out of order (a later item finishing first
            must NOT scramble attribution)"
    (let [n 12
          ;; reverse the completion order: item 0 sleeps the longest, the last
          ;; item returns immediately — so completion order is the reverse of
          ;; input order. The result vector must STILL be in input order.
          out (extract/bounded-parallel-map
               4
               (fn [i]
                 (Thread/sleep (long (* 5 (- n i))))
                 {:idx i :squared (* i i)})
               (vec (range n)))]
      (is (= (vec (range n)) (mapv :idx out))
          "results are in INPUT order regardless of completion order")
      (is (= (mapv #(* % %) (range n)) (mapv :squared out))
          "each slot carries ITS OWN item's result (no misattribution)"))))

;; --- GC-13 cycle 2: the concurrency BOUND is respected (peak ≤ K) ---
(deftest gc13-bounded-parallel-map-respects-the-bound-test
  (testing "with bound = K and thunks that each hold a slot briefly, the PEAK
            number in flight at once never exceeds K (bounded, not unbounded —
            a source with many containers must not fire dozens of :llm calls)"
    (let [k 3
          n 20
          in-flight (atom 0)
          peak (atom 0)
          out (extract/bounded-parallel-map
               k
               (fn [i]
                 (let [now (swap! in-flight inc)]
                   (swap! peak max now)
                   ;; hold the slot long enough that, were the bound not
                   ;; enforced, all N would pile in and peak would blow past K.
                   (Thread/sleep 30)
                   (swap! in-flight dec)
                   i))
               (vec (range n)))]
      (is (= (vec (range n)) out) "all items still processed + ordered")
      (is (<= @peak k)
          (str "peak concurrency must be <= the bound K=" k ", got " @peak))
      ;; sanity: with N >> K and a hold, the bound was actually exercised (we
      ;; really did run in parallel, not silently serialized).
      (is (>= @peak 2)
          (str "expected real parallelism (peak >= 2), got " @peak)))))

;; --- GC-13 cycle 3: honest per-item FAILURE ISOLATION ---
(deftest gc13-bounded-parallel-map-isolates-a-thrown-thunk-test
  (testing "when ONE item's thunk throws, that slot becomes an HONEST failure
            marker carrying the throwable; the OTHER items still complete and
            stay correctly ordered/attributed — the batch does NOT abort (#4/#5)"
    (let [out (extract/bounded-parallel-map
               4
               (fn [i]
                 (if (= i 2)
                   (throw (ex-info "boom in item 2" {:i i}))
                   {:idx i}))
               (vec (range 5)))]
      (is (= 5 (count out)) "no item is dropped — the throwing one keeps its slot")
      ;; the 4 healthy items completed + are correctly attributed/ordered (item 2,
      ;; the thrown slot, has no :idx — it is the error marker checked below).
      (is (= [0 1 nil 3 4] (mapv :idx out))
          "the non-throwing items completed + stayed in input order; the thrown
           slot carries no :idx (it is the error marker)")
      (is (= 0 (:idx (nth out 0))))
      (is (= 1 (:idx (nth out 1))))
      (is (= 3 (:idx (nth out 3))))
      (is (= 4 (:idx (nth out 4))))
      ;; the throwing slot is an HONEST marker, not a silent drop or a healthy map.
      (let [marker (nth out 2)]
        (is (contains? marker :ai.obney.orc.ontology.core.extract-subbehavior/error)
            "the thrown slot is tagged as an error (honest, surfaced)")
        (is (instance? Throwable
                       (:ai.obney.orc.ontology.core.extract-subbehavior/error marker))
            "the marker carries the actual throwable (diagnosable, not swallowed)")))))

;; --- GC-13 cycle 4: orchestrator surfaces a THROWING child tick honestly ---
;; The same stubbed child-tick seam as orchestrator-accumulates-and-reports-
;; honestly-test, but here ONE container's `dsl/execute` THROWS (a child tick
;; blowing up, not a clean gate-failure). Under the serial `mapv` that would
;; crash the whole batch; under bounded-parallel it must become that container's
;; HONEST :failure entry — the OTHER containers' drafts still accumulate, and the
;; throwing container is SURFACED in :extraction-report (never dropped, never
;; misattributed). This guards attribution/accumulation with NO LLM.
(deftest gc13-orchestrator-surfaces-a-throwing-child-tick-honestly-test
  (testing "a child tick that THROWS becomes the container's honest :failure
            entry; the surviving containers' drafts accumulate; nothing dropped"
    (let [child-bbs
          {"c-ok-1" {:concept-drafts {:value [{:uri "u1" :label "a"}
                                              {:uri "u2" :label "b"}]}
                     :relationship-drafts {:value [{:source-uri "u1" :target-uri "u2"
                                                    :predicate "p"}]}
                     :extraction-report {:value {:rows-streamed 100 :rows-errored 0}}}
           "c-ok-2" {:concept-drafts {:value [{:uri "u3" :label "c"}]}
                     :relationship-drafts {:value []}
                     :extraction-report {:value {:rows-streamed 50 :rows-errored 0}}}}
          tick->container (atom {})]
      (with-redefs [extract/list-source-containers
                    (fn [_source] [{:name "c-ok-1"} {:name "c-boom"} {:name "c-ok-2"}])
                    extract/extract-per-container-sheet-id-for
                    (fn [] (random-uuid))
                    dsl/execute
                    (fn [_ctx _sid inputs & {:keys [tick-id]}]
                      (let [cname (:name (get inputs "container"))]
                        (swap! tick->container assoc tick-id cname)
                        (if (= cname "c-boom")
                          ;; the child tick BLOWS UP (not a clean gate failure).
                          (throw (ex-info "child tick exploded" {:container cname}))
                          {:status :success})))
                    dsl/get-tick-blackboard
                    (fn [_ctx tick-id]
                      (get child-bbs (get @tick->container tick-id)))]
        (let [out (extract/orchestrate-extract-containers
                   {:inputs {:source {:type :sql :path "/tmp/x.db"}
                             :model-spec {:entity-types []}}
                    :tick-id (random-uuid)
                    :event-store :stub})
              report (:extraction-report out)]
          ;; the SURVIVING containers' drafts still accumulate (batch did not abort).
          (is (= 3 (count (:concept-drafts out)))
              "the two healthy containers' drafts (2 + 1) accumulate despite the throw")
          (is (= #{"u1" "u2" "u3"} (set (map :uri (:concept-drafts out))))
              "no surviving draft is lost or misattributed")
          ;; the throwing container is SURFACED honestly — counted + reported failed.
          (is (= 3 (:containers-total report)) "all 3 containers counted")
          (is (= 3 (:containers-processed report))
              "the throwing container is NOT silently dropped — still processed/counted")
          (is (= 1 (:containers-failed report))
              "the throwing container surfaces as a :failure (no false-green)")
          (let [boom (first (filter #(= "c-boom" (:container %)) (:per-container report)))]
            (is (some? boom) "the throwing container has a per-container entry (not dropped)")
            (is (= :failure (:status boom)) "it is honestly marked :failure")
            (is (= 0 (:concept-count boom)) "its 0-draft count is honest")
            (is (some? (:diagnosis boom))
                "its failure carries a diagnosis derived from the throwable")))))))
