(ns ai.obney.orc.orc-service.end-to-end-integration-test
  "End-to-end integration test exercising all major ORC features in a
   single cohesive scenario: a customer support triage pipeline.

   Covers: DSL workflow building, all node types (sequence, parallel, fallback,
   condition, map-each, code, llm), blackboard, execution, versioning, judges,
   evaluation, ontology classification, GEPA optimization primitives,
   export/import, rolling metrics, and tracing.

   Uses REAL LLM calls via OpenRouter — gated behind ORC_INTEGRATION_TESTS=true.

   Collects structured report data in `*report*` atom for post-run analysis."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [clojure.pprint :as pprint]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.evaluation.interface :as eval]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.gepa.interface :as gepa]
            [ai.obney.orc.gepa.interface.schemas]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.llm.interface :as llm]))

;; =============================================================================
;; Integration Test Gate
;; =============================================================================

(def run-integration-tests?
  (= "true" (System/getenv "ORC_INTEGRATION_TESTS")))

;; Register OpenRouter provider from OPENROUTER_API_KEY env var
(when run-integration-tests?
  (llm/quick-setup!))

;; =============================================================================
;; Instrumentation
;; =============================================================================

(def ^:dynamic *report*
  "Atom collecting structured data from each test phase.
   Reset at the start of each test run."
  nil)

(defmacro timed
  "Execute body, returning [result elapsed-ms]."
  [& body]
  `(let [start# (System/nanoTime)
         result# (do ~@body)
         elapsed# (/ (- (System/nanoTime) start#) 1e6)]
     [result# elapsed#]))

(defn record-phase!
  "Append a phase record to the report atom."
  [phase-key data]
  (when *report*
    (swap! *report* assoc phase-key
           (assoc data :recorded-at (java.time.Instant/now)))))

(defn print-report
  "Pretty-print the collected report to stdout."
  [report]
  (println "\n")
  (println "╔══════════════════════════════════════════════════════════════╗")
  (println "║              E2E Integration Test Report                    ║")
  (println "╚══════════════════════════════════════════════════════════════╝")
  (println)

  ;; Phase 1: Build
  (when-let [p (:build report)]
    (println "── Phase 1: Build Workflow ──────────────────────────────────")
    (println (format "  Sheet ID:     %s" (:sheet-id p)))
    (println (format "  Sheet name:   %s" (:sheet-name p)))
    (println (format "  Node count:   %d" (:node-count p)))
    (println (format "  Root type:    %s" (:root-type p)))
    (println (format "  BB keys:      %d" (:blackboard-key-count p)))
    (println (format "  Judges:       %s" (str/join ", " (:judge-names p))))
    (println (format "  Build time:   %.0f ms" (:elapsed-ms p)))
    (println))

  ;; Phase 2: Execute urgent
  (when-let [p (:execute-urgent report)]
    (println "── Phase 2: Execute (urgent ticket) ──────────────────────────")
    (println (format "  Status:       %s" (:status p)))
    (println (format "  Wall time:    %.0f ms" (:elapsed-ms p)))
    (println (format "  Engine time:  %s ms" (:duration-ms p)))
    (println "  Outputs:")
    (println (format "    urgency:    %s" (:urgency p)))
    (println (format "    is-urgent:  %s" (:is-urgent p)))
    (println (format "    category:   %s" (:category p)))
    (println (format "    sentiment:  %s" (:sentiment p)))
    (println (format "    routed-to:  %s" (:routed-to p)))
    (println (format "  Sub-issues:   %d processed" (:sub-issue-count p)))
    (when-let [subs (:sub-issue-results p)]
      (doseq [[i s] (map-indexed vector subs)]
        (println (format "    [%d] %s" i (pr-str s)))))
    (println (format "  Full outputs: %s" (pr-str (:raw-outputs p))))
    (println))

  ;; Phase 3: Tracing
  (when-let [p (:tracing report)]
    (println "── Phase 3: Tracing ────────────────────────────────────────")
    (println (format "  Duration:       %s ms" (:duration-ms p)))
    (println (format "  Trace ID:       %s" (:trace-id p)))
    (println (format "  Node count:     %s" (:node-count p)))
    (println (format "  Node statuses:  %s" (:node-statuses p)))
    (when (:node-names p)
      (println (format "  Execution path: %s" (str/join " -> " (:node-names p)))))
    (println))

  ;; Phase 4: Execute normal
  (when-let [p (:execute-normal report)]
    (println "── Phase 4: Execute (normal ticket) + Metrics ───────────────")
    (println (format "  Status:       %s" (:status p)))
    (println (format "  Wall time:    %.0f ms" (:elapsed-ms p)))
    (println (format "  Engine time:  %s ms" (:duration-ms p)))
    (println "  Outputs:")
    (println (format "    urgency:    %s" (:urgency p)))
    (println (format "    category:   %s" (:category p)))
    (println (format "    sentiment:  %s" (:sentiment p)))
    (println (format "    routed-to:  %s" (:routed-to p)))
    (println "  Rolling metrics:")
    (println (format "    total-executions: %s" (:total-executions p)))
    (println (format "    node-metrics:     %d nodes" (or (:node-metrics-count p) 0)))
    (println))

  ;; Phase 5: Versioning
  (when-let [p (:versioning report)]
    (println "── Phase 5: Versioning ─────────────────────────────────────")
    (println (format "  Published versions:  %d" (:version-count p)))
    (println (format "  Version exec status: %s" (:version-exec-status p)))
    (println (format "  Version exec time:   %.0f ms" (:version-exec-elapsed-ms p)))
    (println (format "  Version exec outputs: %s" (pr-str (:version-exec-outputs p))))
    (println))

  ;; Phase 6: Evaluation
  (when-let [p (:evaluation report)]
    (println "── Phase 6: Evaluation Judges ────────────────────────────────")
    (println (format "  Eval time:    %.0f ms" (:elapsed-ms p)))
    (println (format "  Overall score: %.3f" (double (:score p))))
    (println (format "  Feedback:     %s" (:feedback p)))
    (println "  Dimensions:")
    (doseq [d (:dimensions p)]
      (println (format "    %-25s score=%.3f  %s"
                       (str (:name d))
                       (double (or (:score d) 0))
                       (or (:feedback d) ""))))
    (println))

  ;; Phase 7: Ontology
  (when-let [p (:ontology report)]
    (println "── Phase 7: Ontology Classification ──────────────────────────")
    (println (format "  Overall score:  %.3f" (double (:overall-score p))))
    (println (format "  Failure count:  %d" (:failure-count p)))
    (when (pos? (:failure-count p))
      (println "  Failures (real eval):")
      (doseq [f (:failures p)]
        (println (format "    %s  confidence=%.2f  evidence=%s"
                         (:uri f) (double (or (:confidence f) 0)) (:evidence f)))))
    (when (:synthetic-failure-count p)
      (println (format "  Synthetic eval (score=%.2f):" (double (:synthetic-eval-score p))))
      (println (format "    Failures detected: %d" (:synthetic-failure-count p)))
      (println (format "    Failure URIs:      %s" (str/join ", " (:synthetic-failure-uris p))))
      (doseq [f (:synthetic-failures p)]
        (println (format "    %s  dim=%-25s conf=%.2f"
                         (:uri f) (:dimension f) (double (or (:confidence f) 0))))))
    (println))

  ;; Phase 8: GEPA
  (when-let [p (:gepa report)]
    (println "── Phase 8: GEPA Optimization ────────────────────────────────")
    (println (format "  Optimization ID:    %s" (:optimization-id p)))
    (println (format "  Status:             %s" (or (:final-status p) (:status p))))
    (println (format "  Candidates:         %s" (:candidate-count p)))
    (println (format "  Metric calls:       %s" (:total-metric-calls p)))
    (println (format "  Best score:         %s" (:best-score p)))
    (println (format "  Valset/Trainset:    %s / %s" (:valset-size p) (:trainset-size p)))
    (println (format "  Duration:           %.0f ms" (double (or (:elapsed-ms p) 0))))
    (println)
    (when-let [candidates (:candidates p)]
      (println "  Candidates (ranked by score):")
      (doseq [[i c] (map-indexed vector candidates)]
        (println (format "    [%d] score=%-8s gen=%-2s reason=%-12s status=%s"
                         i
                         (if (:score c) (format "%.4f" (double (:score c))) "nil")
                         (:generation c)
                         (or (:mutation-reason c) "-")
                         (:status c)))
        (doseq [[node-name instruction] (:instructions c)]
          (let [instr (str instruction)]
            (println (format "        %s: \"%s\"" node-name
                             (if (> (count instr) 90)
                               (str (subs instr 0 87) "...")
                               instr))))))
      (println))
    (when-let [best (:best-candidate p)]
      (println "  Best candidate:")
      (println (format "    ID:           %s" (:id best)))
      (println (format "    Score:        %s" (:score best)))
      (println (format "    Generation:   %s" (:generation best)))
      (doseq [[node-name instruction] (:instructions best)]
        (println (format "    %s:" node-name))
        (println (format "      \"%s\"" instruction)))
      (println)))

  ;; Phase 9: Export
  (when-let [p (:export report)]
    (println "── Phase 9: Export/Import ─────────────────────────────────────")
    (println (format "  Export keys:        %s" (str/join ", " (map name (:export-keys p)))))
    (println (format "  DSL length:         %d chars" (:dsl-length p)))
    (println (format "  Idempotent rebuild: %s" (:idempotent? p)))
    (println))

  ;; Timing summary
  (let [phases [[:build "Build"]
                [:execute-urgent "Execute (urgent)"]
                [:execute-normal "Execute (normal)"]
                [:versioning "Versioning"]
                [:evaluation "Evaluation"]
                [:gepa "GEPA"]
                [:export "Export"]]
        total (reduce + 0 (keep #(:elapsed-ms (get report (first %))) phases))]
    (println "── Timing Summary ──────────────────────────────────────────")
    (doseq [[k label] phases]
      (when-let [ms (:elapsed-ms (get report k))]
        (println (format "  %-22s %8.0f ms" label (double ms)))))
    (println (format "  %-22s %8.0f ms" "TOTAL" (double total)))
    (println)))

;; =============================================================================
;; Code Executor Functions (resolved by fully-qualified name at runtime)
;; =============================================================================

(defn classify-urgency
  "Code executor: reads :ticket-message, writes :urgency and :is-urgent."
  [{:keys [inputs]}]
  (let [message (str (:ticket-message inputs))]
    {:urgency (if (re-find #"(?i)urgent|critical|emergency|down" message)
               "high" "normal")
     :is-urgent (boolean (re-find #"(?i)urgent|critical|emergency|down" message))}))

(defn split-issues
  "Code executor: reads :ticket-message, writes :sub-issues."
  [{:keys [inputs]}]
  (let [message (str (:ticket-message inputs ""))
        parts (->> (str/split message #"[.;!?]+")
                   (map str/trim)
                   (remove str/blank?)
                   (mapv (fn [s] {:text s})))]
    {:sub-issues (if (seq parts) parts [{:text message}])}))

(defn default-category
  "Code executor: fallback that writes :category as 'general'."
  [{:keys [_inputs]}]
  {:category "general"})

(defn build-routing-decision
  "Code executor: assembles final routing from urgency/sentiment/category."
  [{:keys [inputs]}]
  {:routing-decision
   {:urgency (:urgency inputs)
    :sentiment (or (:sentiment inputs) "neutral")
    :category (or (:category inputs) "general")
    :routed-to (if (= "high" (:urgency inputs)) "tier-2" "tier-1")}})

;; =============================================================================
;; Workflow Definition
;; =============================================================================

(def triage-workflow
  (sheet/workflow "support-triage-e2e"
    (sheet/blackboard
      {:ticket-message    :string
       :urgency           [:enum "high" "normal"]
       :is-urgent         :boolean
       :sentiment         :string
       :category          :string
       :sub-issues        [:vector [:map [:text :string]]]
       :sub-issue-results [:vector [:map
                                    [:text :string]
                                    [:sub-issue-summary :string]]]
       :current-issue     [:map [:text :string]]
       :sub-issue-summary :string
       :routing-decision  [:map
                           [:urgency [:enum "high" "normal"]]
                           [:sentiment :string]
                           [:category :string]
                           [:routed-to [:enum "tier-1" "tier-2"]]]})

    (sheet/judges
      {:grounding-judge    {:type :grounding
                            :criteria "All routing decisions must be supported by ticket content"
                            :weight 0.4}
       :completeness-judge {:type :completeness
                            :criteria "Must produce urgency, sentiment, category, and routing decision"
                            :weight 0.3}
       :instruction-judge  {:type :instruction-following
                            :criteria "Must follow triage protocol accurately"
                            :weight 0.3}})

    (sheet/sequence "triage-pipeline"
      ;; Step 1: Code — classify urgency via regex
      (sheet/code "classify-urgency"
        :fn "ai.obney.orc.orc-service.end-to-end-integration-test/classify-urgency"
        :reads [:ticket-message]
        :writes [:urgency :is-urgent])

      ;; Step 2: Parallel — LLM analysis + fallback category
      (sheet/parallel "analyze" {:success-policy :all}
        ;; Fallback: try LLM category classification, fall back to code default
        (sheet/fallback "classify-category"
          (sheet/llm "llm-classify"
            :model "google/gemini-2.5-flash"
            :instruction "You are classifying a customer support ticket. Read the ticket message and output exactly one category. Valid categories: billing, technical, account, general. Output ONLY the category word, nothing else."
            :reads [:ticket-message]
            :writes [:category]
            :judges ["grounding-judge"])
          (sheet/code "default-category"
            :fn "ai.obney.orc.orc-service.end-to-end-integration-test/default-category"
            :writes [:category]))
        ;; LLM sentiment analysis
        (sheet/llm "sentiment-analysis"
          :model "google/gemini-2.5-flash"
          :instruction "Analyze the sentiment of this customer support ticket. Output exactly one word: positive, negative, or neutral."
          :reads [:ticket-message]
          :writes [:sentiment]
          :judges ["instruction-judge"]))

      ;; Step 3: Code — split ticket into sub-issues
      (sheet/code "split-issues"
        :fn "ai.obney.orc.orc-service.end-to-end-integration-test/split-issues"
        :reads [:ticket-message]
        :writes [:sub-issues])

      ;; Step 4: Condition — verify urgency was classified
      (sheet/condition "check-classified"
        :check {:key :urgency :op :exists})

      ;; Step 5: Map-each — process each sub-issue with LLM
      (sheet/map-each "process-sub-issues"
        :from :sub-issues
        :as :current-issue
        :into :sub-issue-results
        :parallel 2
        (sheet/llm "analyze-sub-issue"
          :model "google/gemini-2.5-flash"
          :instruction "Summarize this customer sub-issue in exactly one sentence. Be concise."
          :reads [:current-issue]
          :writes [:sub-issue-summary]))

      ;; Step 6: Code — build final routing decision
      (sheet/code "build-routing"
        :fn "ai.obney.orc.orc-service.end-to-end-integration-test/build-routing-decision"
        :reads [:urgency :sentiment :category]
        :writes [:routing-decision]
        :judges ["completeness-judge"]))))

;; =============================================================================
;; E2E Integration Test
;; =============================================================================

(deftest ^:integration end-to-end-support-triage-test
  (if-not run-integration-tests?
    (do (println "Skipping E2E integration test. Set ORC_INTEGRATION_TESTS=true to run.")
        (is true "skipped"))

    (binding [*report* (atom {})]
      (h/with-async-test-context [ctx]

        ;; =====================================================================
        ;; Phase 1: Build Workflow via DSL
        ;; =====================================================================
        (testing "Phase 1: Build workflow via DSL"
          (let [[sheet-id build-ms] (timed (sheet/build-workflow! ctx triage-workflow))]
            (is (uuid? sheet-id) "build-workflow! returns a UUID")

            (let [s (sheet/get-sheet ctx sheet-id)
                  nodes (sheet/get-nodes-for-sheet ctx sheet-id)
                  root (sheet/get-root-node ctx sheet-id)
                  bb (sheet/get-blackboard-for-sheet ctx sheet-id)]

              (is (= "support-triage-e2e" (:name s)))
              (is (some? s))
              (is (>= (count nodes) 10) "Should have at least 10 nodes")
              (is (= :sequence (:type root)) "Root should be a sequence")
              (is (>= (count bb) 9) "Should have at least 9 blackboard keys")

              (record-phase! :build
                {:sheet-id sheet-id
                 :sheet-name (:name s)
                 :node-count (count nodes)
                 :node-types (frequencies (map :type nodes))
                 :root-type (:type root)
                 :blackboard-key-count (count bb)
                 :blackboard-keys (mapv :key bb)
                 :judge-names ["grounding-judge" "completeness-judge" "instruction-judge"]
                 :elapsed-ms build-ms}))

            ;; =====================================================================
            ;; Phase 2: Execute Workflow (urgent ticket)
            ;; =====================================================================
            (testing "Phase 2: Execute with urgent ticket"
              (let [input {:ticket-message "URGENT: billing error on my account. Also my password reset isn't working."}
                    [result exec-ms] (timed (sheet/execute ctx sheet-id input :timeout-ms 120000))
                    outputs (:outputs result)]

                (is (= :success (:status result))
                    (str "Execution should succeed, got: " (:status result)
                         (when (:error result) (str " error: " (:error result)))))
                (is (= "high" (:urgency outputs)) "Code executor: urgency should be high")
                (is (true? (:is-urgent outputs)) "Code executor: is-urgent should be true")
                (is (string? (:category outputs)) "Category should be a string")
                (is (not (str/blank? (:category outputs))) "Category should not be blank")
                (is (string? (:sentiment outputs)) "Sentiment should be a string")
                (is (not (str/blank? (:sentiment outputs))) "Sentiment should not be blank")
                (is (vector? (:sub-issue-results outputs)) "Sub-issue results should be a vector")
                (is (pos? (count (:sub-issue-results outputs))) "Should have processed sub-issues")
                (is (map? (:routing-decision outputs)) "Routing decision should be a map")
                (is (= "tier-2" (get-in outputs [:routing-decision :routed-to]))
                    "Urgent ticket should route to tier-2")

                (record-phase! :execute-urgent
                  {:status (:status result)
                   :elapsed-ms exec-ms
                   :duration-ms (:duration-ms result)
                   :urgency (:urgency outputs)
                   :is-urgent (:is-urgent outputs)
                   :category (:category outputs)
                   :sentiment (:sentiment outputs)
                   :routed-to (get-in outputs [:routing-decision :routed-to])
                   :sub-issue-count (count (:sub-issue-results outputs))
                   :sub-issue-results (:sub-issue-results outputs)
                   :raw-outputs outputs
                   :input input
                   :error (:error result)})

                ;; =============================================================
                ;; Phase 3: Verify Tracing
                ;; =============================================================
                (testing "Phase 3: Verify execution tracing"
                  (is (some? (:duration-ms result)) "Should have duration")

                  ;; Poll for traces (assembled asynchronously)
                  (let [trace (loop [attempts 0]
                                (let [result (h/run-query ctx (h/make-get-traces-query sheet-id :limit 1))
                                      traces (get-in result [:query/result :traces])]
                                  (cond
                                    (seq traces)
                                    (first traces)

                                    (< attempts 20)
                                    (do (Thread/sleep 500) (recur (inc attempts)))

                                    :else nil)))]

                    (is (some? trace) "Should have at least one trace")
                    (when trace
                      (is (= sheet-id (:sheet-id trace)) "Trace should reference correct sheet")
                      (is (= :success (:status trace)) "Trace should show success")

                      ;; Verify node traces
                      (let [node-traces (:node-traces trace)
                            node-names (set (map :node-name node-traces))]
                        (is (vector? node-traces) "Should have node-traces vector")
                        (is (>= (count node-traces) 5) "Should have traces for multiple nodes")

                        ;; Key nodes should appear
                        (doseq [expected-name ["classify-urgency" "sentiment-analysis" "build-routing"]]
                          (is (contains? node-names expected-name)
                              (str "Node traces should include " expected-name)))

                        ;; Check execution ordering: classify-urgency before build-routing
                        (let [idx-of (fn [name] (->> node-traces
                                                      (keep-indexed #(when (= name (:node-name %2)) %1))
                                                      first))]
                          (when (and (idx-of "classify-urgency") (idx-of "build-routing"))
                            (is (< (idx-of "classify-urgency") (idx-of "build-routing"))
                                "classify-urgency should execute before build-routing")))

                        (record-phase! :tracing
                          {:duration-ms (:duration-ms result)
                           :trace-id (:trace-id trace)
                           :node-count (count node-traces)
                           :node-names (mapv :node-name node-traces)
                           :node-statuses (frequencies (map :status node-traces))})))))

                ;; =============================================================
                ;; Phase 4: Second Execution (normal priority) + Rolling Metrics
                ;; =============================================================
                (testing "Phase 4: Normal ticket + rolling metrics"
                  (let [input2 {:ticket-message "How do I reset my password?"}
                        [result2 exec2-ms] (timed (sheet/execute ctx sheet-id input2 :timeout-ms 120000))
                        outputs2 (:outputs result2)
                        metrics (sheet/get-tree-rolling-metrics ctx sheet-id)]

                    (is (= :success (:status result2))
                        (str "Normal ticket should succeed, got: " (:status result2)
                             (when (:error result2) (str " error: " (:error result2)))))
                    (is (= "normal" (:urgency outputs2)) "Non-urgent ticket should be normal")
                    (is (= "tier-1" (get-in outputs2 [:routing-decision :routed-to]))
                        "Normal ticket should route to tier-1")
                    (is (some? metrics) "Should have tree metrics")
                    (when metrics
                      (is (>= (or (:total-executions metrics) 0) 2)
                          "Should have at least 2 executions recorded"))

                    (record-phase! :execute-normal
                      {:status (:status result2)
                       :elapsed-ms exec2-ms
                       :duration-ms (:duration-ms result2)
                       :urgency (:urgency outputs2)
                       :category (:category outputs2)
                       :sentiment (:sentiment outputs2)
                       :routed-to (get-in outputs2 [:routing-decision :routed-to])
                       :total-executions (:total-executions metrics)
                       :node-metrics-count (count (:nodes metrics))
                       :rolling-metrics metrics
                       :input input2
                       :raw-outputs outputs2
                       :error (:error result2)})))

                ;; =============================================================
                ;; Phase 5: Versioning
                ;; =============================================================
                (testing "Phase 5: Publish version and execute against it"
                  (h/run-and-apply! ctx (h/make-publish-version-command sheet-id
                                          :description "v1 - initial triage pipeline"))
                  (let [versions (sheet/get-versions-for-sheet ctx sheet-id)]
                    (is (>= (count versions) 1) "Should have at least 1 published version")

                    (let [input-v {:ticket-message "My subscription was charged twice"}
                          [result-v1 v1-ms] (timed (sheet/execute ctx sheet-id input-v
                                                     :timeout-ms 120000 :use-version 1))]
                      (is (= :success (:status result-v1))
                          (str "Published version execution should succeed, got: " (:status result-v1)
                               (when (:error result-v1) (str " error: " (:error result-v1)))))
                      (is (some? (:outputs result-v1)) "Published version should produce outputs")

                      (record-phase! :versioning
                        {:version-count (count versions)
                         :version-exec-status (:status result-v1)
                         :version-exec-elapsed-ms v1-ms
                         :version-exec-duration-ms (:duration-ms result-v1)
                         :version-exec-outputs (:outputs result-v1)
                         :version-exec-error (:error result-v1)}))))

                ;; =============================================================
                ;; Phase 6: Evaluation Judges (real LLM)
                ;; =============================================================
                (testing "Phase 6: Evaluate execution with LLM judges"
                  (let [trace-data {:inputs {:ticket-message "URGENT: billing error on my account. Also my password reset isn't working."}
                                    :outputs outputs
                                    :instruction "Triage customer support tickets by classifying urgency, category, sentiment, and routing to appropriate team."}
                        [eval-result eval-ms] (timed (eval/evaluate-trace trace-data))]

                    (is (some? eval-result) "Should return evaluation result")
                    (when eval-result
                      (is (number? (:score eval-result)) "Score should be a number")
                      (is (<= 0.0 (:score eval-result) 1.0) "Score should be 0-1")
                      (is (string? (:feedback eval-result)) "Should have feedback")
                      (is (not (str/blank? (:feedback eval-result))) "Feedback should not be blank")
                      (is (vector? (:dimensions eval-result)) "Should have dimensions")
                      (is (pos? (count (:dimensions eval-result))) "Should have at least one dimension")

                      (record-phase! :evaluation
                        {:elapsed-ms eval-ms
                         :score (:score eval-result)
                         :feedback (:feedback eval-result)
                         :dimensions (:dimensions eval-result)
                         :trace-data trace-data})

                      ;; =========================================================
                      ;; Phase 7: Ontology Classification
                      ;; =========================================================
                      (testing "Phase 7: Classify evaluation into failure taxonomy"
                        (let [classification (ontology/classify-evaluation
                                               {:score (:score eval-result)
                                                :dimensions (:dimensions eval-result)})]

                          (is (map? classification) "Should return classification map")
                          (is (vector? (:failures classification)) "Should have failures vector")
                          (is (number? (:overall-score classification)) "Should have overall-score")
                          (doseq [failure (:failures classification)]
                            (is (string? (:uri failure)) "Failure should have URI")
                            (is (str/starts-with? (:uri failure) "failure:")
                                (str "Failure URI should start with 'failure:', got: " (:uri failure))))

                          ;; Also test with synthetic BAD evaluation to exercise failure taxonomy
                          (let [bad-eval {:score 0.25
                                          :dimensions [{:name "Grounding" :score 0.2
                                                        :feedback "The response contains hallucinated facts that were made up"}
                                                       {:name "Instruction Following" :score 0.3
                                                        :feedback "The output format was wrong and violated constraints"}
                                                       {:name "Reasoning" :score 0.8
                                                        :feedback "Reasoning was adequate"}
                                                       {:name "Completeness" :score 0.15
                                                        :feedback "Missing key entities and truncated output"}]}
                                bad-classification (ontology/classify-evaluation bad-eval)]

                            ;; Should detect failures for low-scoring dimensions
                            (is (pos? (count (:failures bad-classification)))
                                "Synthetic bad eval should produce failures")
                            (let [failure-uris (set (map :uri (:failures bad-classification)))
                                  failure-dims (set (map :dimension (:failures bad-classification)))]
                              ;; Grounding, Instruction Following, Completeness should fail (< 0.7)
                              ;; Reasoning should NOT fail (0.8 > 0.7)
                              (is (contains? failure-dims "Grounding") "Should flag Grounding failure")
                              (is (contains? failure-dims "Instruction Following") "Should flag Instruction Following failure")
                              (is (contains? failure-dims "Completeness") "Should flag Completeness failure")
                              (is (not (contains? failure-dims "Reasoning")) "Reasoning should not fail (0.8 > 0.7)")

                              ;; Check subtype detection via feedback indicators
                              (is (some #(str/includes? % "Hallucination") failure-uris)
                                  "Should detect Hallucination subtype from 'hallucinated' feedback")

                              (record-phase! :ontology
                                {:overall-score (:overall-score classification)
                                 :failure-count (count (:failures classification))
                                 :failures (:failures classification)
                                 :primary-failure-uri (:primary-failure-uri classification)
                                 :synthetic-eval-score 0.25
                                 :synthetic-failure-count (count (:failures bad-classification))
                                 :synthetic-failures (:failures bad-classification)
                                 :synthetic-failure-uris failure-uris}))))))))

                ;; =============================================================
                ;; Phase 8: GEPA Optimization (real LLM loop)
                ;; =============================================================
                (testing "Phase 8: GEPA optimization (real)"
                  (let [;; Metric: does the workflow's :category output match expected?
                        ;; Uses same field names as GEPA's default-metric-fn for compatibility
                        ;; with the async todo processors (which can't receive the metric-fn)
                        category-metric (fn [input output]
                                          (let [expected (get input "expected")
                                                actual (or (get output "category")
                                                           (get output :category))]
                                            (cond
                                              (nil? expected) 0.0
                                              (nil? actual) 0.0
                                              (= (str/lower-case (str/trim (str expected)))
                                                 (str/lower-case (str/trim (str actual)))) 1.0
                                              (.contains (str/lower-case (str actual))
                                                         (str/lower-case (str expected))) 0.7
                                              :else 0.0)))
                        trainset [{"ticket-message" "I was charged twice for my subscription"
                                   "expected" "billing"}
                                  {"ticket-message" "My password reset email never arrived"
                                   "expected" "account"}
                                  {"ticket-message" "The app keeps crashing on iOS 18"
                                   "expected" "technical"}]
                        valset [{"ticket-message" "You charged the wrong card"
                                 "expected" "billing"}
                                {"ticket-message" "Can't log into my account"
                                 "expected" "account"}
                                {"ticket-message" "Page loads are extremely slow"
                                 "expected" "technical"}]
                        [opt-result gepa-ms]
                        (timed
                          (gepa/optimize! ctx
                            {:sheet-id sheet-id
                             :trainset trainset
                             :valset valset
                             :metric-fn category-metric
                             :config {:max-metric-calls 12
                                      :reflection-minibatch-size 2
                                      :reflection-lm "google/gemini-2.5-flash"}
                             :block? true
                             :timeout-ms 600000}))]

                    (is (some? opt-result) "optimize! should return a result")
                    (is (contains? #{:completed :timeout :failed} (:status opt-result))
                        (str "Status should be terminal, got: " (:status opt-result)))

                    (let [opt-id (:optimization-id opt-result)
                          pop-state (when opt-id (gepa/get-population-state ctx opt-id))
                          all-candidates (when pop-state
                                           (vals (:candidates pop-state)))
                          best (when opt-id (gepa/get-best-candidate ctx opt-id))
                          final-summary (when opt-id (gepa/get-optimization-summary ctx opt-id))]

                      (when (= :completed (:status opt-result))
                        (is (some? best) "Completed optimization should have a best candidate")
                        (when best
                          (is (number? (:aggregate-score best)) "Best should have a score")
                          (is (> (:aggregate-score best) 0) "Best score should be positive")))

                      (record-phase! :gepa
                        {:optimization-id opt-id
                         :status (:status opt-result)
                         :best-score (:best-score opt-result)
                         :duration-ms (:duration-ms opt-result)
                         :elapsed-ms gepa-ms
                         :candidate-count (count all-candidates)
                         :total-metric-calls (:total-metric-calls pop-state)
                         :candidates (when all-candidates
                                       (mapv (fn [c]
                                               {:id (:candidate-id c)
                                                :instructions (:instructions c)
                                                :score (:aggregate-score c)
                                                :generation (:generation c)
                                                :mutation-reason (:mutation-reason c)
                                                :status (:status c)})
                                             (sort-by #(or (:aggregate-score %) -1) > all-candidates)))
                         :best-candidate (when best
                                           {:id (:candidate-id best)
                                            :instructions (:instructions best)
                                            :score (:aggregate-score best)
                                            :generation (:generation best)})
                         :best-candidate-id (:best-candidate-id final-summary)
                         :final-status (:status final-summary)
                         :valset-size (count valset)
                         :trainset-size (count trainset)}))))

                ;; =============================================================
                ;; Phase 9: Export/Import Round-Trip
                ;; =============================================================
                (testing "Phase 9: Export and import round-trip"
                  (let [[_ export-ms]
                        (timed
                          (let [exported (sheet/export-sheet ctx sheet-id)]
                            (is (map? exported) "Export should return a map")
                            (is (some? (:sheet exported)) "Should have :sheet key")

                            (let [dsl-code (sheet/export-to-dsl exported)]
                              (is (string? dsl-code) "DSL export should be a string")
                              (is (str/includes? dsl-code "workflow") "DSL should contain 'workflow'")
                              (is (str/includes? dsl-code "blackboard") "DSL should contain 'blackboard'")
                              (is (str/includes? dsl-code "sequence") "DSL should contain 'sequence'")

                              (let [rebuilt-id (sheet/build-workflow! ctx triage-workflow)]
                                (is (= sheet-id rebuilt-id)
                                    "Rebuilding same workflow should return same sheet-id")

                                (record-phase! :export
                                  {:export-keys (keys exported)
                                   :dsl-length (count dsl-code)
                                   :dsl-preview (subs dsl-code 0 (min 200 (count dsl-code)))
                                   :idempotent? (= sheet-id rebuilt-id)
                                   :elapsed-ms 0})))))]
                    (record-phase! :export
                      (assoc (get @*report* :export) :elapsed-ms export-ms)))))))))

      ;; Print the report after all phases
      (print-report @*report*))))
