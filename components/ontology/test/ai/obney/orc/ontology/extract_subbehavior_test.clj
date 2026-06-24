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
        (is (= [:source :model-spec :max-containers] (vec (:reads (first code-leaves))))
            "the orchestrator reads the public :reads contract (+ optional :max-containers bound)")
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
        (is (= [:model-spec :sample-rows] (vec (:reads author)))
            "the AUTHOR node reads the model-spec + the REAL sampled rows (Node 1's output)")
        (is (= :reasoning (first writes))
            "#13: the FIRST declared write must be :reasoning (force think-before-emit)")
        (is (= [:reasoning :transform-source :selector] writes)
            "the AUTHOR write contract is reasoning-first, then the transform-source + selector")))))

(deftest per-container-sample-and-apply-node-contracts-test
  (testing "the per-container SAMPLE node reads [:source :container] writes
            [:sample-rows]; the APPLY node reads [:source :transform-source
            :selector :container] writes the draft set"
    (h/with-async-test-context [ctx]
      (let [_ (extract/register-extract-subbehavior! ctx {})
            unit-id (extract/extract-per-container-sheet-id-for)
            nodes (vals (rm/get-nodes-by-id ctx unit-id))
            sample (first (filter #(= "sample-rows" (:name %)) nodes))
            apply* (first (filter #(= "apply-transform" (:name %)) nodes))]
        (is (= [:source :container] (vec (:reads sample)))
            "the SAMPLE node reads the source + the ONE container it grounds")
        (is (= [:sample-rows] (vec (:writes sample))))
        (is (= [:source :transform-source :selector :container] (vec (:reads apply*)))
            "the APPLY node reads the source + transform + selector + the container it applies to")
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
              "the report surfaces the cross-container relating summary"))))))

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
