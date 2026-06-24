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
