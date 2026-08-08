(ns ai.obney.orc.mcp-sheet-builder.core.comparison
  "Comparison framework for evaluating Phase 1 vs Phase 2 approaches.

   Phase 1: Simple LLM + code workflows (deterministic)
   Phase 2: repl-researcher with iterative LLM+SCI (adaptive)

   This module provides:
   - Side-by-side execution of two workflows
   - Metrics collection (latency, tokens, accuracy)
   - Batch comparison across multiple test cases
   - Summary statistics and winner determination"
  (:require [ai.obney.orc.orc-service.interface :as sheet]
            [clojure.string :as str]))

;; =============================================================================
;; Accuracy Scoring
;; =============================================================================

(defn- normalize-answer
  "Normalize an answer for comparison (lowercase, trim, etc.)"
  [answer]
  (when answer
    (-> (str answer)
        str/lower-case
        str/trim
        (str/replace #"\s+" " "))))

(defn- pattern-match?
  "Check if answer matches a pattern (regex or string)."
  [answer expected]
  (cond
    (instance? java.util.regex.Pattern expected)
    (boolean (re-find expected (str answer)))

    (string? expected)
    (str/includes? (normalize-answer answer) (normalize-answer expected))

    (fn? expected)
    (expected answer)

    :else
    (= answer expected)))

(defn calculate-accuracy
  "Calculate accuracy score for outputs vs expected values.

   Expected can be:
   - A string (exact match after normalization)
   - A regex pattern
   - A predicate function
   - A map of key -> expected (for multiple outputs)

   Returns a score between 0.0 and 1.0"
  [outputs expected]
  (cond
    (nil? expected)
    1.0  ;; No expectation means success

    (map? expected)
    ;; Compare each key
    (let [keys (keys expected)
          matches (for [k keys]
                    (let [output (get outputs k)
                          exp (get expected k)]
                      (if (pattern-match? output exp) 1.0 0.0)))]
      (if (empty? matches)
        1.0
        (/ (reduce + matches) (count matches))))

    :else
    ;; Single expected value - check against first output
    (let [first-output (first (vals outputs))]
      (if (pattern-match? first-output expected) 1.0 0.0))))

;; =============================================================================
;; Single Run Execution
;; =============================================================================

(defn execute-with-metrics
  "Execute a workflow and collect metrics.

   Returns:
   {:status :success/:failure
    :outputs {...}
    :duration-ms int
    :tokens {:prompt-tokens N :completion-tokens N :total-tokens N}
    :accuracy float (0.0-1.0)
    :error string?
    :trace-id uuid}"
  [ctx sheet-id inputs expected]
  (let [start-time (System/currentTimeMillis)
        result (sheet/execute ctx sheet-id inputs)
        duration-ms (- (System/currentTimeMillis) start-time)
        accuracy (when (= :success (:status result))
                   (calculate-accuracy (:outputs result) expected))]
    {:status (:status result)
     :outputs (:outputs result)
     :duration-ms duration-ms
     :tokens (or (:usage result) {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0})
     :accuracy (or accuracy 0.0)
     :error (:error result)
     :trace-id (:trace-id result)}))

;; =============================================================================
;; Comparison Execution
;; =============================================================================

(defn run-comparison
  "Run same task with both Phase 1 and Phase 2 approaches.

   Args:
     ctx - Execution context with event-store and llm-provider
     phase1-sheet-id - UUID of Phase 1 (simple llm+code) workflow
     phase2-sheet-id - UUID of Phase 2 (repl-researcher) workflow
     inputs - Map of blackboard key -> input value
     expected - Expected output(s) for accuracy calculation

   Returns:
   {:phase1 {:duration-ms ... :tokens ... :accuracy ...}
    :phase2 {:duration-ms ... :tokens ... :accuracy ... :iterations [...]}
    :winner {:by-accuracy :phase1/:phase2/:tie
             :by-tokens :phase1/:phase2/:tie
             :by-latency :phase1/:phase2/:tie}
    :inputs inputs
    :expected expected}"
  [ctx phase1-sheet-id phase2-sheet-id inputs expected]
  (let [;; Run Phase 1
        phase1-result (execute-with-metrics ctx phase1-sheet-id inputs expected)

        ;; Run Phase 2
        phase2-result (execute-with-metrics ctx phase2-sheet-id inputs expected)

        ;; Determine winners
        accuracy-winner (cond
                          (> (:accuracy phase1-result) (:accuracy phase2-result)) :phase1
                          (< (:accuracy phase1-result) (:accuracy phase2-result)) :phase2
                          :else :tie)

        tokens1 (get-in phase1-result [:tokens :total-tokens] 0)
        tokens2 (get-in phase2-result [:tokens :total-tokens] 0)
        tokens-winner (cond
                        (< tokens1 tokens2) :phase1
                        (> tokens1 tokens2) :phase2
                        :else :tie)

        latency-winner (cond
                         (< (:duration-ms phase1-result) (:duration-ms phase2-result)) :phase1
                         (> (:duration-ms phase1-result) (:duration-ms phase2-result)) :phase2
                         :else :tie)]

    {:phase1 phase1-result
     :phase2 phase2-result
     :winner {:by-accuracy accuracy-winner
              :by-tokens tokens-winner
              :by-latency latency-winner}
     :inputs inputs
     :expected expected}))

;; =============================================================================
;; Batch Comparison
;; =============================================================================

(defn batch-comparison
  "Run comparison across multiple test cases.

   Args:
     ctx - Execution context
     phase1-sheet-id - UUID of Phase 1 workflow
     phase2-sheet-id - UUID of Phase 2 workflow
     test-cases - Vector of {:inputs {...} :expected {...}}

   Returns:
   {:results [comparison-result ...]
    :summary {:phase1 {:avg-accuracy ... :avg-latency ... :avg-tokens ...}
              :phase2 {:avg-accuracy ... :avg-latency ... :avg-tokens ...}
              :overall-winner :phase1/:phase2/:tie}}"
  [ctx phase1-sheet-id phase2-sheet-id test-cases]
  (let [results (mapv (fn [{:keys [inputs expected]}]
                        (run-comparison ctx phase1-sheet-id phase2-sheet-id inputs expected))
                      test-cases)

        ;; Calculate averages
        phase1-results (map :phase1 results)
        phase2-results (map :phase2 results)

        avg (fn [coll key]
              (let [vals (map key coll)]
                (if (empty? vals)
                  0
                  (/ (reduce + vals) (count vals)))))

        phase1-summary {:avg-accuracy (avg phase1-results :accuracy)
                        :avg-latency (avg phase1-results :duration-ms)
                        :avg-tokens (avg (map #(get-in % [:tokens :total-tokens] 0) phase1-results) identity)
                        :success-rate (/ (count (filter #(= :success (:status %)) phase1-results))
                                         (max 1 (count phase1-results)))}

        phase2-summary {:avg-accuracy (avg phase2-results :accuracy)
                        :avg-latency (avg phase2-results :duration-ms)
                        :avg-tokens (avg (map #(get-in % [:tokens :total-tokens] 0) phase2-results) identity)
                        :success-rate (/ (count (filter #(= :success (:status %)) phase2-results))
                                         (max 1 (count phase2-results)))}

        ;; Determine overall winner based on accuracy
        overall-winner (cond
                         (> (:avg-accuracy phase1-summary) (:avg-accuracy phase2-summary)) :phase1
                         (< (:avg-accuracy phase1-summary) (:avg-accuracy phase2-summary)) :phase2
                         :else :tie)]

    {:results results
     :summary {:phase1 phase1-summary
               :phase2 phase2-summary
               :overall-winner overall-winner
               :test-count (count test-cases)}}))

;; =============================================================================
;; Reporting
;; =============================================================================

(defn format-comparison-report
  "Format a comparison result as a human-readable string."
  [comparison]
  (let [{:keys [phase1 phase2 winner]} comparison]
    (str "=== Comparison Report ===\n"
         "\nPhase 1 (Simple LLM+Code):\n"
         "  Status: " (:status phase1) "\n"
         "  Duration: " (:duration-ms phase1) "ms\n"
         "  Tokens: " (get-in phase1 [:tokens :total-tokens] 0) "\n"
         "  Accuracy: " (format "%.2f" (float (:accuracy phase1))) "\n"
         (when (:error phase1) (str "  Error: " (:error phase1) "\n"))
         "\nPhase 2 (REPL Researcher):\n"
         "  Status: " (:status phase2) "\n"
         "  Duration: " (:duration-ms phase2) "ms\n"
         "  Tokens: " (get-in phase2 [:tokens :total-tokens] 0) "\n"
         "  Accuracy: " (format "%.2f" (float (:accuracy phase2))) "\n"
         (when (:error phase2) (str "  Error: " (:error phase2) "\n"))
         "\nWinners:\n"
         "  By Accuracy: " (name (:by-accuracy winner)) "\n"
         "  By Tokens: " (name (:by-tokens winner)) "\n"
         "  By Latency: " (name (:by-latency winner)) "\n")))

(defn format-batch-summary
  "Format batch comparison summary as a human-readable string."
  [{:keys [summary]}]
  (let [{:keys [phase1 phase2 overall-winner test-count]} summary]
    (str "=== Batch Comparison Summary (" test-count " tests) ===\n"
         "\nPhase 1 Averages:\n"
         "  Accuracy: " (format "%.2f" (float (:avg-accuracy phase1))) "\n"
         "  Latency: " (format "%.0f" (float (:avg-latency phase1))) "ms\n"
         "  Tokens: " (format "%.0f" (float (:avg-tokens phase1))) "\n"
         "  Success Rate: " (format "%.1f%%" (* 100 (float (:success-rate phase1)))) "\n"
         "\nPhase 2 Averages:\n"
         "  Accuracy: " (format "%.2f" (float (:avg-accuracy phase2))) "\n"
         "  Latency: " (format "%.0f" (float (:avg-latency phase2))) "ms\n"
         "  Tokens: " (format "%.0f" (float (:avg-tokens phase2))) "\n"
         "  Success Rate: " (format "%.1f%%" (* 100 (float (:success-rate phase2)))) "\n"
         "\nOverall Winner: " (name overall-winner) "\n")))

(comment
  ;; Example usage

  ;; Run single comparison
  (def result
    (run-comparison ctx
      phase1-sheet-id
      phase2-sheet-id
      {:question "How do I trace LLM calls in Langfuse?"}
      {:answer #"trace|Langfuse"}))

  (println (format-comparison-report result))

  ;; Run batch comparison
  (def batch-result
    (batch-comparison ctx
      phase1-sheet-id
      phase2-sheet-id
      [{:inputs {:question "How do I trace LLM calls?"}
        :expected {:answer #"trace"}}
       {:inputs {:question "What is the Python SDK?"}
        :expected {:answer #"Python|SDK"}}
       {:inputs {:question "How do sessions work?"}
        :expected {:answer #"session"}}]))

  (println (format-batch-summary batch-result)))
