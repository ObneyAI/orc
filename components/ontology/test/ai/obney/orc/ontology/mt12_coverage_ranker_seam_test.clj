(ns ai.obney.orc.ontology.mt12-coverage-ranker-seam-test
  "MT-12 SLICE 2 — the coverage-aware LLM ranker sheet + delegate seam.

   These durable, hermetic brick-gate tests lock the load-bearing DECISIONS of the
   coverage ranker through the PUBLIC surface (the schema shape, the seam's threading
   of the runtime CQ list), never live-LLM prompt quality (proven by the SLICE-0
   prototype `development/src/mt12_coverage_select_prototype.clj` + the reviewer's
   live-verify coverage arm):

     - C1 (the MT-11 lesson, VERBATIM): `container-coverage-schema` is a CONCRETE
       `[:vector [:map …]]` with CONCRETE leaf types (`:serves-cqs` → `[:vector :int]`)
       and a STRING `:enum` (`\"high\"/\"medium\"/\"low\"`, NOT a bare keyword), so the
       `:llm` executor renders + parses it into real Clojure data across `:delegate`
       rather than a raw string. Asserted via `executor/malli-schema->description`
       rendering the enum as \"one of: high, medium, low\" and `:serves-cqs` as
       \"list of integer\" — the exact concrete rendering that makes DSCloj parse.
     - The delegate seam THREADS the runtime `:competency-questions` into the ranker
       sheet inputs (+ `:reads` + `:bb-schema`), reads back the coverage MAP
       (`:container-coverage`, NOT the old flat name vector), and passes `:cqs` to
       `select-containers` (SLICE-1). Captured via a stubbed `delegate-subbehavior!`.
     - Backward-compat: nil/empty CQs still thread (empty vector) — the pure
       SLICE-1 `select-containers` then no-ops promotion (today's take-cap).

   Domain-agnostic (#12): fixtures name no vertical entity/column/table."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.ontology.core.central-evolver :as ce]
            [ai.obney.orc.ontology.core.container-select :as csel]
            [clojure.string :as str]
            [malli.core :as m]))

;; =============================================================================
;; A. The coverage-map schema — the MT-11 lesson, asserted through the executor
;;    renderer (the mechanism that makes the model emit parseable structured data).
;; =============================================================================

(deftest coverage-schema-enum-renders-exact-relevance-values-test
  (testing "MT-11 lesson: executor/malli-schema->description renders the coverage
            schema's :relevance enum as the EXACT allowed strings (\"one of: high,
            medium, low\") — NOT \"any value\" — the concrete rendering that tells the
            model what to emit and is what fixes the DSCloj parse (a bare keyword /
            :any would render \"any value\" and the field would arrive as raw text)."
    (let [rendered (executor/malli-schema->description ce/container-coverage-schema)]
      (is (str/includes? rendered "one of: high, medium, low")
          "the :relevance enum renders the three allowed strings, not \"any value\"")
      (is (str/includes? rendered "list of integer")
          "the :serves-cqs leaf renders as a concrete list of integer (0-based CQ idxs)")
      (is (str/includes? rendered "list of object")
          "the top level renders as a list of objects (the coverage map is a vector of maps)")
      (is (not (str/includes? rendered "any value"))
          "no leaf renders as \"any value\" (the :any / bare-keyword failure mode)"))))

(deftest coverage-schema-validates-a-real-coverage-map-test
  (testing "the concrete coverage schema accepts a real, model-variable coverage map:
            per-entry :name + :serves-cqs (int vector) + optional :relevance string"
    (is (m/validate ce/container-coverage-schema
                    [{:name "container-alpha" :serves-cqs [0 4] :relevance "high"}
                     {:name "container-beta" :serves-cqs []}])
        "a real coverage map (one entry with :relevance, one without) validates")
    (is (m/validate ce/container-coverage-schema [])
        "an empty coverage map validates the SHAPE (a degrade-to-empty case)")
    (is (not (m/validate ce/container-coverage-schema
                         [{:name "x" :serves-cqs ["not-an-int"]}]))
        ":serves-cqs is CONCRETELY typed — a non-int index is rejected (not :any)")
    (is (not (m/validate ce/container-coverage-schema
                         [{:name "x" :serves-cqs [] :relevance "sometimes"}]))
        ":relevance is a STRING enum — an out-of-set value is rejected")))

;; =============================================================================
;; B. The delegate seam — threads the runtime CQ list into the ranker sheet inputs,
;;    reads back the coverage MAP, and passes :cqs to SLICE-1 select-containers.
;; =============================================================================

(def ^:private fake-candidates
  "Two structurally-kept survivors (so the seam does NOT short-circuit the N<=1
   single-container path). No domain names (#12)."
  [{:name "container-alpha" :keep? true :shape :entity
    :header ["col-a" "col-b"] :row-count 120}
   {:name "container-beta" :keep? true :shape :entity
    :header ["col-c" "col-d"] :row-count 90}])

(deftest seam-threads-cqs-into-sheet-inputs-and-select-containers-test
  (testing "delegate-select-containers! THREADS the runtime :competency-questions into
            the ranker sheet inputs (+ :reads + :bb-schema), reads back the coverage
            MAP (:container-coverage) from the delegate, returns it from rank-fn, and
            passes :cqs to select-containers. Captured via a stubbed
            delegate-subbehavior! + a select-containers spy that invokes rank-fn."
    (let [cqs ["What does a serve?" "What does b serve?"]
          coverage-map [{:name "container-alpha" :serves-cqs [0] :relevance "high"}
                        {:name "container-beta" :serves-cqs [1] :relevance "medium"}]
          captured-delegate (atom nil)
          captured-register (atom nil)
          captured-select-opts (atom nil)
          captured-rank-return (atom ::unset)
          delegate-stub (fn [_ctx opts]
                          (reset! captured-delegate opts)
                          {:status :success
                           :outputs {ce/container-coverage-key coverage-map}
                           :tick-id (random-uuid)})
          register-stub (fn [_ctx opts]
                          (reset! captured-register opts)
                          (random-uuid))
          ;; select-containers spy: capture opts AND invoke rank-fn (the seam's inner
          ;; delegated rank), so the delegate stub actually runs and we can capture
          ;; both the threaded inputs AND rank-fn's return (the coverage map).
          select-stub (fn [_candidates opts]
                        (reset! captured-select-opts opts)
                        (when-let [rf (:rank-fn opts)]
                          (reset! captured-rank-return (rf (:goal opts) fake-candidates)))
                        {:selected [] :dropped [] :report {}})]
      (with-redefs [ce/register-select-rank-subbehavior! register-stub
                    ce/delegate-subbehavior! delegate-stub
                    csel/classify-source-containers (fn [_src _opts] fake-candidates)
                    csel/select-containers select-stub]
        (ce/delegate-select-containers!
         :fake-ctx {:source {:type :csv} :goal "G" :model "m"
                    :competency-questions cqs
                    :list-fn (constantly [{:name "container-alpha"} {:name "container-beta"}])}))
      ;; --- threaded into the sheet INPUTS (the runtime read) ---
      (is (= cqs (get-in @captured-delegate [:inputs "competency-questions"]))
          "the CQ list is threaded verbatim into the ranker sheet inputs")
      (is (= "G" (get-in @captured-delegate [:inputs "goal"]))
          "the goal is still threaded into the inputs")
      ;; --- declared in the delegate :reads + :bb-schema so it crosses :delegate ---
      (is (some #{:competency-questions} (:reads @captured-delegate))
          ":competency-questions is a declared :read on the central delegate")
      (is (contains? (:bb-schema @captured-delegate) :competency-questions)
          ":competency-questions is declared in the delegate :bb-schema")
      ;; --- the seam now reads back the COVERAGE MAP, not the flat name key ---
      (is (= [ce/container-coverage-key] (:writes @captured-delegate))
          "the delegate reads back the coverage MAP key (not selected-container-names)")
      (is (= coverage-map @captured-rank-return)
          "rank-fn returns the coverage MAP (vector of {:name :serves-cqs …}) verbatim")
      ;; --- the register threading (so the numbered CQ list renders into the sheet) ---
      (is (= cqs (:competency-questions @captured-register))
          "the CQ list is threaded into register-select-rank-subbehavior! (for the prompt)")
      ;; --- and :cqs is passed to SLICE-1 select-containers (the coverage heart) ---
      (is (= cqs (:cqs @captured-select-opts))
          "the CQ list is passed as :cqs to select-containers (SLICE-1 coverage)"))))

(deftest seam-cleans-c1-delegate-crossing-degrades-in-coverage-readback-test
  (testing "the coverage MAP crosses :delegate and INTERMITTENTLY degrades (both modes
            measured live): (1) an unparsed STRING; (2) a vector with NAMESPACED entry
            keys (::name instead of :name). The seam coerces + normalizes the read-back
            so rank-fn ALWAYS returns plain-keyed {:name :serves-cqs} maps — never a
            char-iterated string or nil-reading namespaced keys."
    (let [run-seam (fn [degraded-outputs]
                     (let [captured (atom ::unset)
                           delegate-stub (fn [_ _opts]
                                           {:status :success :outputs degraded-outputs
                                            :tick-id (random-uuid)})
                           select-stub (fn [_c opts]
                                         (when-let [rf (:rank-fn opts)]
                                           (reset! captured (rf (:goal opts) fake-candidates)))
                                         {:selected [] :dropped [] :report {}})]
                       (with-redefs [ce/register-select-rank-subbehavior! (fn [_ _] (random-uuid))
                                     ce/delegate-subbehavior! delegate-stub
                                     csel/classify-source-containers (fn [_ _] fake-candidates)
                                     csel/select-containers select-stub]
                         (ce/delegate-select-containers!
                          :fake-ctx {:source {:type :csv} :goal "G" :model "m"
                                     :competency-questions ["cq0" "cq1"]
                                     :list-fn (constantly [{:name "container-alpha"} {:name "container-beta"}])}))
                       @captured))]
      ;; (2) NAMESPACED entry keys → normalized to PLAIN :name/:serves-cqs
      (let [ret (run-seam {ce/container-coverage-key
                           [{::name "container-alpha" ::serves-cqs [0] ::relevance "high"}
                            {::name "container-beta" ::serves-cqs [1] ::relevance "medium"}]})]
        (is (= ["container-alpha" "container-beta"] (mapv :name ret))
            "namespaced ::name keys are normalized to plain :name (readable)")
        (is (= [[0] [1]] (mapv :serves-cqs ret))
            "namespaced ::serves-cqs keys are normalized to plain :serves-cqs"))
      ;; (1) STRING (JSON) → coerced to a real vector of plain-keyed maps
      (let [ret (run-seam {ce/container-coverage-key
                           "[{\"name\": \"container-alpha\", \"serves-cqs\": [0]}, {\"name\": \"container-beta\", \"serves-cqs\": [1]}]"})]
        (is (= ["container-alpha" "container-beta"] (mapv :name ret))
            "a degraded JSON STRING is parsed to real maps (not char-iterated)")
        (is (= [[0] [1]] (mapv :serves-cqs ret))
            "the parsed maps carry :serves-cqs"))
      ;; a genuinely-unparseable string → [] (honest degrade → list order downstream)
      (is (= [] (run-seam {ce/container-coverage-key "{not parseable"}))
          "an unparseable coverage value → [] (honest degrade, never garbage)"))))

(deftest seam-backward-compat-empty-cqs-thread-as-empty-vector-test
  (testing "backward-compat: nil :competency-questions threads an EMPTY vector into
            the sheet inputs and :cqs to select-containers (SLICE-1 then no-ops
            promotion → today's take-cap). Nothing regresses."
    (let [captured-delegate (atom nil)
          captured-select-opts (atom nil)
          delegate-stub (fn [_ctx opts]
                          (reset! captured-delegate opts)
                          {:status :success
                           :outputs {ce/container-coverage-key
                                     [{:name "container-alpha" :serves-cqs []}]}
                           :tick-id (random-uuid)})
          select-stub (fn [_candidates opts]
                        (reset! captured-select-opts opts)
                        (when-let [rf (:rank-fn opts)] (rf (:goal opts) fake-candidates))
                        {:selected [] :dropped [] :report {}})]
      (with-redefs [ce/register-select-rank-subbehavior! (fn [_ _] (random-uuid))
                    ce/delegate-subbehavior! delegate-stub
                    csel/classify-source-containers (fn [_src _opts] fake-candidates)
                    csel/select-containers select-stub]
        (ce/delegate-select-containers!
         :fake-ctx {:source {:type :csv} :goal "G" :model "m"
                    :list-fn (constantly [{:name "container-alpha"} {:name "container-beta"}])}))
      (is (= [] (get-in @captured-delegate [:inputs "competency-questions"]))
          "absent CQs → an EMPTY vector is threaded (never nil that breaks the schema)")
      (is (= [] (:cqs @captured-select-opts))
          "absent CQs → :cqs is an empty vector to select-containers (no-op promotion)"))))
