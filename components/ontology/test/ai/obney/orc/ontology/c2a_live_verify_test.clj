(ns ai.obney.orc.ontology.c2a-live-verify-test
  "Tests for the C-2a live-verify orchestrator (development/src/c2a_live_verify.clj).

   The orchestrator runs three scenarios:
     A — 3-run regression (control → failure-burst → recovery)
     B — Seed-executability spot-check
     C — Retrieval-quality probe (deferred until C-2b)

   These tests verify orchestration with a fake LLM (`with-redefs` on
   dscloj/predict). The REAL-LLM live-verify gate runs the same code
   against gemini-3-flash-preview with `OPENROUTER_API_KEY` set."
  (:require [clojure.test :refer [deftest testing is]]
            [dscloj.core :as dscloj]
            [c2a-live-verify :as live-verify]))

;; =============================================================================
;; RED #1 — Driver script exists and runs with fake LLM
;; =============================================================================
;;
;; Tracer-bullet: calling `c2a-live-verify/run!` under a fake-LLM
;; with-redefs doesn't crash and returns a structured map containing
;; results for the three scenarios.

(defn- with-faked-llm
  "Run f with dscloj/predict stubbed to return a minimal well-formed
   description body. The fake mirrors what the consolidator's structured
   output expects (six writes keys)."
  [f]
  (with-redefs [dscloj/predict
                (fn [_provider _module _inputs _options]
                  {:outputs {:capabilities ["x"]
                             :strengths [{:trait "fake-strength"
                                          :good-when "ctx"
                                          :recommended-pattern "[:llm {:reads [:x] :writes [:y]}]"
                                          :confidence 0.8
                                          :evidence-count 5
                                          :first-observed-at "2026-05-26T00:00:00Z"
                                          :last-reinforced-at "2026-05-26T00:00:00Z"}]
                             :weaknesses []
                             :representative-uses ["x"]
                             :avoid-when ["x"]
                             :summary "Fake summary."}
                   :usage {:total-tokens 100}
                   :model "fake"})]
    (f)))

(deftest live-verify-runs-without-crashing
  (testing "c2a-live-verify/run! returns a structured result map with three scenario keys"
    (with-faked-llm
      (fn []
        (let [result (live-verify/run! {:scenario-b-sample-indices [0]
                                         :skip-edn-save? true})]
          (is (map? result)
              "run! returns a map")
          (is (contains? result :scenario-a)
              "Result map contains :scenario-a")
          (is (contains? result :scenario-b)
              "Result map contains :scenario-b")
          (is (contains? result :scenario-c)
              "Result map contains :scenario-c"))))))

;; =============================================================================
;; RED #2 — Scenario A 3-phase regression returns structured pass/fail summary
;; =============================================================================

;; =============================================================================
;; RED #3 — Scenario B picks N seeds + executes each via tree-executor
;; =============================================================================

(defn- with-faked-tree-llm-success
  "Like with-faked-llm but tolerates the tree-executor's :llm leaves
   calling dscloj/predict with any inputs and returning :status :success
   from the executor. The executor wraps dscloj and judges success based
   on whether outputs were produced — so we return any non-nil :outputs
   keyed by what the LLM was asked to write."
  [f]
  (with-redefs [dscloj/predict
                (fn [_provider module _inputs _options]
                  ;; Return non-nil values for whatever :name keys the
                  ;; module declared as outputs. dscloj uses them to fill
                  ;; the blackboard.
                  (let [output-fields (or (:outputs module) [])
                        outputs (into {} (map (fn [{:keys [name]}]
                                                [name (str "stubbed-" (clojure.core/name name))])
                                              output-fields))]
                    {:outputs outputs
                     :usage {:total-tokens 50}
                     :model "fake"}))]
    (f)))

(deftest scenario-b-picks-sampled-seeds-and-reports-each
  (testing "Scenario B picks the configured sample of seeds, attempts each, and reports per-seed status"
    (with-faked-tree-llm-success
      (fn []
        (let [result (live-verify/run! {:scenario-b-sample-indices [0 1]
                                         :skip-edn-save? true
                                         :register-llm-provider? false
                                         :scenario-a-event-counts {:control 1 :failure 1 :recovery 1}})]
          (let [b (:scenario-b result)]
            (is (boolean? (:passed? b))
                ":scenario-b has a :passed? boolean")
            (is (= 2 (count (:results b)))
                "Two seeds attempted (matching the configured sample-indices)")
            (is (every? :seed-id (:results b))
                "Each per-seed result records its seed-id")
            (is (every? #(contains? % :status) (:results b))
                "Each per-seed result records a :status")))))))

;; =============================================================================
;; RED #4 — Scenario B failure case correctly reports a broken seed
;; =============================================================================
;;
;; If a seed snippet's transform throws (e.g., malformed S-expr or
;; unknown node type), Scenario B's per-seed result must reflect
;; :status :error with the error message captured. Overall scenario
;; :passed? must be false.

;; =============================================================================
;; RED #5 — Scenario C is gracefully deferred
;; =============================================================================

;; =============================================================================
;; RED #6 — EDN run-records are saved to development/bench/generalization-results/
;; =============================================================================

(deftest edn-records-saved-when-skip-edn-save-false
  (testing "When :skip-edn-save? false, the orchestrator writes per-scenario EDN files containing the structured pass/fail summary"
    (with-faked-tree-llm-success
      (fn []
        (let [tmp-dir (str "/tmp/c2a-live-verify-edn-test-" (random-uuid))]
          (try
            (.mkdirs (java.io.File. tmp-dir))
            (live-verify/run! {:scenario-b-sample-indices [0]
                               :skip-edn-save? false
                               :edn-dir tmp-dir
                               :register-llm-provider? false
                               :scenario-a-event-counts {:control 1 :failure 1 :recovery 1}})
            (let [files (->> (.listFiles (java.io.File. tmp-dir))
                              (map #(.getName %))
                              (filter #(re-matches #"c2a-live-verify-.*\.edn" %)))]
              (is (>= (count files) 2)
                  "At least two EDN files written (scenario-a + scenario-b)")
              ;; Each EDN file is readable / parseable
              (doseq [fname files]
                (let [content (slurp (str tmp-dir "/" fname))
                      parsed (try (clojure.core/read-string content)
                                  (catch Exception _ ::unparseable))]
                  (is (not= ::unparseable parsed)
                      (str "EDN file " fname " is parseable"))
                  (is (map? parsed)
                      (str "EDN file " fname " parses to a map")))))
            (finally
              ;; Cleanup
              (doseq [f (.listFiles (java.io.File. tmp-dir))]
                (.delete f))
              (.delete (java.io.File. tmp-dir)))))))))

(deftest scenario-c-is-deferred-pending-c-2b
  (testing "Scenario C is marked deferred with a reason mentioning C-2b"
    (with-faked-llm
      (fn []
        (let [result (live-verify/run! {:scenario-b-sample-indices []
                                         :skip-edn-save? true
                                         :register-llm-provider? false
                                         :scenario-a-event-counts {:control 1 :failure 1 :recovery 1}})
              c (:scenario-c result)]
          (is (= true (:deferred? c))
              "Scenario C reports :deferred? true")
          (is (string? (:reason c))
              "Scenario C carries a :reason string")
          (is (re-find #"C-2b" (:reason c))
              ":reason mentions C-2b (the slice that unblocks Scenario C)"))))))

(deftest scenario-b-handles-broken-seed-gracefully
  (testing "If a seed's snippet errors during transform, the per-seed status is :error and :passed? overall is false"
    (with-faked-tree-llm-success
      (fn []
        ;; Stub the transform to deliberately throw — simulates a broken seed.
        (with-redefs [ai.obney.orc.orc-service.core.rlm-dsl/rlm-dsl->orc-dsl
                      (fn [_tree] (throw (ex-info "Simulated broken seed" {:reason :test-injection})))]
          (let [result (live-verify/run! {:scenario-b-sample-indices [0]
                                           :skip-edn-save? true
                                           :register-llm-provider? false
                                           :scenario-a-event-counts {:control 1 :failure 1 :recovery 1}})
                b (:scenario-b result)
                seed-result (first (:results b))]
            (is (= false (:passed? b))
                "Overall :scenario-b :passed? is false when any seed errored")
            (is (= :error (:status seed-result))
                "Broken seed reports :status :error")
            (is (string? (:error seed-result))
                "Broken seed records the error message")
            (is (re-find #"Simulated broken seed" (:error seed-result))
                "Error message surfaces the underlying cause")))))))

(deftest scenario-a-reports-three-phases-with-bodies
  (testing "After running Scenario A, the result has three phases each with a captured description body"
    (with-faked-llm
      (fn []
        (let [result (live-verify/run! {:scenario-b-sample-indices []
                                         :skip-edn-save? true
                                         :register-llm-provider? false
                                         ;; smaller event counts for faster fake-LLM test
                                         :scenario-a-event-counts {:control 2 :failure 1 :recovery 2}})]
          (let [a (:scenario-a result)]
            (is (boolean? (:passed? a))
                ":scenario-a has a :passed? boolean")
            (is (= 3 (count (:phases a)))
                ":scenario-a has three phases (X, Y, Z)")
            (let [phase-labels (mapv :phase (:phases a))]
              (is (= [:x :y :z] phase-labels)
                  "Phases are in order: X (control), Y (failure-burst), Z (recovery)"))
            (let [bodies (mapv :body (:phases a))]
              (is (every? some? bodies)
                  "Each phase captures a description body"))))))))
