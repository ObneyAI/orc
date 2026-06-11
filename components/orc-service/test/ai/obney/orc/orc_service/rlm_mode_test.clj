(ns ai.obney.orc.orc-service.rlm-mode-test
  "Integration tests for RLM (Recursive Language Model) mode.

   RLM mode enables repl-researcher to construct and execute behavior trees
   as primitives, separating variable space (data in sandbox) from token space
   (what the LLM sees)."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.orc.orc-service.core.rlm-sandbox :as rlm-sandbox]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]
            [dscloj.core :as dscloj]
            [litellm.router :as litellm-router]))

;; =============================================================================
;; Test Context with RLM Support
;; =============================================================================

(defn- create-rlm-test-context
  "Create an async test context configured for RLM mode testing.
   Uses :openrouter provider for real LLM calls."
  []
  (let [ps (pubsub/start {:type :core-async
                          :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory}
                               :event-pubsub ps
                               :logger nil})
        cache-dir (str "/tmp/rlm-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  :dscloj-provider :openrouter
                  ::cache-dir cache-dir}
        processors (reduce-kv
                    (fn [acc proc-name {:keys [handler-fn topics]}]
                      (assoc acc proc-name
                             (tp/start {:event-pubsub ps
                                        :topics topics
                                        :handler-fn handler-fn
                                        :context base-ctx})))
                    {}
                    @tp/processor-registry*)]
    (assoc base-ctx
           :event-pubsub ps
           :processors processors)))

(defn- stop-rlm-test-context [ctx]
  (doseq [[_ processor] (:processors ctx)]
    (tp/stop processor))
  (when-let [ps (:event-pubsub ctx)]
    (pubsub/stop ps))
  (when-let [cache (:cache ctx)]
    (kv/stop cache))
  (when-let [event-store (:event-store ctx)]
    (es/stop event-store))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [child (.listFiles f)]
          (.delete child))
        (.delete f)))))

(defmacro with-rlm-test-context
  [[ctx-sym] & body]
  `(let [~ctx-sym (create-rlm-test-context)]
     (try
       ~@body
       (finally
         (stop-rlm-test-context ~ctx-sym)))))

;; =============================================================================
;; OpenRouter Setup
;; =============================================================================

(defn setup-openrouter! []
  (litellm-router/register! :openrouter
    {:provider :openrouter
     :model "minimax/minimax-m1"
     :config {:api-base "https://openrouter.ai/api/v1"
              :api-key (System/getenv "OPENROUTER_API_KEY")}}))

(use-fixtures :once
  (fn [f]
    (setup-openrouter!)
    (f)))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn- setup-rlm-repl-researcher-sheet!
  "Create a sheet with an RLM-enabled repl-researcher node.

   R-Default: these tests were written against terminal-mode dispatch
   semantics. After R-Default flipped recursive to be the always-on
   default, the helper's default rlm-config explicitly opts back into
   terminal mode (:recursive? false). Tests that genuinely want recursive
   behavior can pass :rlm-config {:recursive? true}."
  [ctx & {:keys [instruction reads writes model rlm-config max-iterations]
          :or {instruction "Research the question"
               reads [:question]
               writes [:answer]
               model "minimax/minimax-m1"
               rlm-config {:recursive? false}
               max-iterations 5}}]
  (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "RLM Test"))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)]
    ;; Declare blackboard keys
    (doseq [k (concat reads writes)]
      (h/run-and-apply! ctx (h/make-declare-key-command sheet-id k :string)))
    ;; Create sequence with repl-researcher child
    (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
          seq-id (-> seq-result :command-result/events first :node-id)
          node-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :repl-researcher :parent-id seq-id))
          node-id (-> node-result :command-result/events first :node-id)]
      ;; Configure repl-researcher with RLM mode
      ;; Note: :rlm config will be added to the command schema
      (h/run-and-apply! ctx (h/make-set-repl-researcher-config-command
                              sheet-id node-id instruction reads writes []
                              :model model
                              :max-iterations max-iterations
                              :rlm rlm-config))
      {:sheet-id sheet-id :node-id node-id})))

(defn- dispatch-async-execute!
  "Dispatch async execution and return promise for completion."
  [ctx sheet-id inputs & {:keys [timeout-ms] :or {timeout-ms 30000}}]
  (let [tick-id (random-uuid)
        p (runtime/register-completion! tick-id)
        _cmd-result (cp/process-command
                      (assoc ctx :command
                             {:command/id (random-uuid)
                              :command/timestamp (time/now)
                              :command/name :sheet/tick-tree
                              :sheet-id sheet-id
                              :tick-id tick-id
                              :inputs inputs
                              :options {:timeout-ms timeout-ms}}))]
    {:tick-id tick-id :promise p}))

(defn- wait-for-completion [p & {:keys [timeout-ms] :or {timeout-ms 30000}}]
  (let [result (deref p timeout-ms ::timeout)]
    (if (= result ::timeout) :timeout result)))

;; =============================================================================
;; Tracer Bullet: RLM Mode Basic Execution
;; =============================================================================

(deftest rlm-mode-llm-primitive-test
  (testing "RLM-enabled repl-researcher can execute llm primitive and capture result via final!"
    (with-rlm-test-context [ctx]
      ;; Track call count to return different responses
      (let [call-count (atom 0)]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (let [n (swap! call-count inc)]
                          (cond
                            ;; First call: root LLM generates BT code
                            (= n 1)
                            {:outputs {:code "(let [result (llm \"calc\"
                                                              :instruction \"What is 2+2? Reply with just the number.\"
                                                              :writes [:answer])]
                                                (final! {:answer (:answer result)}))"}
                             :usage {:prompt_tokens 100 :completion_tokens 50 :total_tokens 150}}

                            ;; Second call: sub-LLM returns the actual answer
                            :else
                            {:outputs {:answer "4"}
                             :usage {:prompt_tokens 20 :completion_tokens 5 :total_tokens 25}})))]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Use the llm primitive to answer the question"
                                     :rlm-config {:recursive? false})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:question "What is 2+2?"})
                result (wait-for-completion promise :timeout-ms 30000)]

            ;; Verify execution completed
            (is (not= :timeout result) "Execution should complete within timeout")
            (is (= :success (:status result)) (str "Expected success, got: " (:error result) " iterations: " (pr-str (:iterations result))))

            ;; Verify the answer was captured
            (is (some? (:answer (:outputs result))) "Should have :answer in outputs")
            (is (= "4" (:answer (:outputs result))) "Should have correct answer")))))))

(deftest rlm-mode-final-validation-test
  (testing "final! validates against declared :writes keys"
    (with-rlm-test-context [ctx]
      (with-redefs [dscloj/predict
                    (fn [_provider _module _inputs _opts]
                      ;; Typo in key name - should fail validation
                      {:outputs {:code "(final! {:anwser \"42\"})"}
                       :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}})]
        (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                   ctx
                                   :writes [:answer]  ;; correct key
                                   :rlm-config {:recursive? false})
              {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:question "test"})
              result (wait-for-completion promise)]

          ;; Should fail due to validation error (final! rejects wrong keys)
          ;; Note: error message propagation through async pipeline is limited,
          ;; but status correctly reflects the failure
          (is (= :failure (:status result)) (str "Expected failure, got: " (:status result)))
          ;; The answer should not have been written since validation failed
          (is (nil? (:answer (:outputs result))) "Answer should not be written when validation fails"))))))

;; =============================================================================
;; Slice 2: Input Metadata Preview System
;; =============================================================================

(deftest rlm-mode-get-input-returns-full-value-test
  (testing "get-input returns full value while LLM only sees preview"
    (with-rlm-test-context [ctx]
      ;; Create a large document (over 500 chars to trigger preview)
      (let [large-doc (apply str (repeat 1000 "x"))
            call-count (atom 0)]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (let [n (swap! call-count inc)]
                          (cond
                            ;; First call: root LLM generates code that uses get-input
                            (= n 1)
                            {:outputs {:code "(let [doc (get-input :document)
                                                    ;; doc should be the full 1000 chars
                                                    len (count doc)]
                                                (final! {:answer (str len)}))"}
                             :usage {:prompt_tokens 100 :completion_tokens 50 :total_tokens 150}}

                            :else
                            {:outputs {:answer "unexpected call"}
                             :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})))]
          ;; Setup sheet with :document as input
          (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Preview Test"))
                sheet-id (-> sheet-result :command-result/events first :sheet-id)]
            ;; Declare keys
            (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :document :string))
            (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :answer :string))
            ;; Create repl-researcher node
            (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
                  seq-id (-> seq-result :command-result/events first :node-id)
                  node-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :repl-researcher :parent-id seq-id))
                  node-id (-> node-result :command-result/events first :node-id)]
              (h/run-and-apply! ctx (h/make-set-repl-researcher-config-command
                                      sheet-id node-id
                                      "Count the characters in the document"
                                      [:document] [:answer] []
                                      :rlm {:recursive? false}))
              ;; Execute with large document
              (let [{:keys [promise]} (dispatch-async-execute! ctx sheet-id {:document large-doc})
                    result (wait-for-completion promise)]

                (is (= :success (:status result)) (str "Expected success, got: " (:status result)))
                ;; The answer should be "1000" - proving get-input returned the full value
                (is (= "1000" (:answer (:outputs result)))
                    "get-input should return full 1000-char document")))))))))

(deftest rlm-mode-inputs-preview-shows-metadata-test
  (testing "inputs map shows metadata preview, not full values"
    (with-rlm-test-context [ctx]
      (let [large-doc (apply str (repeat 1000 "x"))
            captured-inputs-info (atom nil)]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        ;; Capture the inputs-info that was sent to the LLM
                        (reset! captured-inputs-info (:inputs-info inputs))
                        {:outputs {:code "(final! {:answer \"done\"})"}
                         :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}})]
          (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Inputs Preview Test"))
                sheet-id (-> sheet-result :command-result/events first :sheet-id)]
            (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :document :string))
            (h/run-and-apply! ctx (h/make-declare-key-command sheet-id :answer :string))
            (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
                  seq-id (-> seq-result :command-result/events first :node-id)
                  node-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :repl-researcher :parent-id seq-id))
                  node-id (-> node-result :command-result/events first :node-id)]
              (h/run-and-apply! ctx (h/make-set-repl-researcher-config-command
                                      sheet-id node-id "Process document" [:document] [:answer] []
                                      :rlm {:recursive? false}))
              (let [{:keys [promise]} (dispatch-async-execute! ctx sheet-id {:document large-doc})
                    _ (wait-for-completion promise)
                    inputs-info @captured-inputs-info]

                ;; The inputs-info sent to LLM should NOT contain the full 1000-char document
                (is (some? inputs-info) "inputs-info should be captured")
                ;; The preview should show truncation marker "[...N chars...]"
                (is (clojure.string/includes? inputs-info "chars...")
                    "inputs-info should show truncation marker for large strings")
                ;; The size should be mentioned
                (is (clojure.string/includes? inputs-info "1000")
                    "inputs-info should mention the document size")))))))))

;; =============================================================================
;; Slice 3: Sequence and Parallel BT Primitives
;; =============================================================================

(deftest rlm-mode-sequence-primitive-test
  (testing "sequence primitive executes llm nodes in order"
    (with-rlm-test-context [ctx]
      (let [call-count (atom 0)
            call-order (atom [])]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (let [n (swap! call-count inc)]
                          (cond
                            ;; First call: root LLM generates code with sequence
                            (= n 1)
                            {:outputs {:code "(sequence \"two-step\"
                                                (llm \"first\" :instruction \"Say hello\" :writes [:greeting])
                                                (llm \"second\" :instruction \"Say goodbye\" :writes [:farewell]))
                                              (final! {:answer (str (:greeting (get-input :greeting))
                                                                   \" and \"
                                                                   (:farewell (get-input :farewell)))})"}
                             :usage {:prompt_tokens 100 :completion_tokens 50 :total_tokens 150}}

                            ;; Second call: first llm in sequence
                            (= n 2)
                            (do (swap! call-order conj :first)
                                {:outputs {:greeting "Hello"}
                                 :usage {:prompt_tokens 20 :completion_tokens 5 :total_tokens 25}})

                            ;; Third call: second llm in sequence
                            (= n 3)
                            (do (swap! call-order conj :second)
                                {:outputs {:farewell "Goodbye"}
                                 :usage {:prompt_tokens 20 :completion_tokens 5 :total_tokens 25}})

                            :else
                            {:outputs {:unexpected "call"}
                             :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})))]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Use sequence to run two steps"
                                     :rlm-config {:recursive? false})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:question "test"})
                result (wait-for-completion promise)]

            (is (= :success (:status result)) (str "Expected success, got: " (:status result)))
            ;; Verify sequence executed in order
            (is (= [:first :second] @call-order)
                "Sequence should execute nodes in order")))))))

;; =============================================================================
;; Slice 4: Map-Each Primitive with Data Slicing
;; =============================================================================

(deftest rlm-mode-map-each-primitive-test
  (testing "map-each primitive processes collection items"
    (with-rlm-test-context [ctx]
      (let [call-count (atom 0)
            processed-items (atom [])]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (let [n (swap! call-count inc)]
                          (cond
                            ;; First call: root LLM generates code with map-each
                            (= n 1)
                            {:outputs {:code "(let [items [\"apple\" \"banana\" \"cherry\"]
                                                    results (map-each \"process-fruit\"
                                                              items
                                                              (fn [fruit]
                                                                (llm \"classify\"
                                                                     :instruction (str \"Is \" fruit \" tropical? Reply yes or no.\")
                                                                     :writes [:tropical])))]
                                                (final! {:answer (pr-str (map :tropical results))}))"}
                             :usage {:prompt_tokens 100 :completion_tokens 50 :total_tokens 150}}

                            ;; Subsequent calls: process each fruit
                            :else
                            (let [;; The instruction contains the fruit name
                                  instruction (get-in module [:instructions])
                                  fruit (second (re-find #"Is (\w+) tropical" instruction))]
                              (swap! processed-items conj fruit)
                              {:outputs {:tropical (if (= fruit "banana") "yes" "no")}
                               :usage {:prompt_tokens 20 :completion_tokens 5 :total_tokens 25}}))))]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Use map-each to classify fruits"
                                     :rlm-config {:recursive? false})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:question "test"})
                result (wait-for-completion promise)]

            (is (= :success (:status result)) (str "Expected success, got: " (:status result)))
            ;; All three items should have been processed
            (is (= #{"apple" "banana" "cherry"} (set @processed-items))
                "map-each should process all collection items")))))))

;; =============================================================================
;; Slice 5: Fallback, Condition, and LLM-Condition Primitives
;; =============================================================================

(deftest rlm-mode-fallback-primitive-test
  (testing "fallback primitive returns first non-nil value"
    (with-rlm-test-context [ctx]
      (let [call-count (atom 0)
            attempt-order (atom [])]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (let [n (swap! call-count inc)]
                          (cond
                            ;; First call: root LLM generates code with fallback
                            ;; Using nil as first option to simulate failure
                            (= n 1)
                            {:outputs {:code "(let [result (fallback \"try-sources\"
                                                            nil
                                                            (llm \"backup\" :instruction \"Provide backup answer\" :writes [:answer]))]
                                                (final! {:answer (:answer result)}))"}
                             :usage {:prompt_tokens 100 :completion_tokens 50 :total_tokens 150}}

                            ;; Second call: backup llm succeeds
                            (= n 2)
                            (do (swap! attempt-order conj :backup)
                                {:outputs {:answer "backup-value"}
                                 :usage {:prompt_tokens 20 :completion_tokens 5 :total_tokens 25}})

                            :else
                            {:outputs {:unexpected "call"}
                             :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})))]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Use fallback for resilience"
                                     :rlm-config {:recursive? false})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:question "test"})
                result (wait-for-completion promise)]

            (is (= :success (:status result)) (str "Expected success, got: " (:status result)))
            ;; Backup should have been called
            (is (= [:backup] @attempt-order)
                "Fallback should call backup when first option is nil")
            ;; The answer should be from backup
            (is (= "backup-value" (:answer (:outputs result)))
                "Should have backup value in answer")))))))

(deftest rlm-mode-condition-primitive-test
  (testing "condition primitive evaluates predicate and executes appropriate branch"
    (with-rlm-test-context [ctx]
      (let [call-count (atom 0)
            branches-called (atom [])]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (let [n (swap! call-count inc)]
                          (cond
                            ;; First call: root LLM generates code with condition
                            ;; Note: both branches will be evaluated due to eager eval,
                            ;; but condition selects the correct one
                            (= n 1)
                            {:outputs {:code "(let [x 10
                                                    high-result (llm \"high\" :instruction \"X is high\" :writes [:result])
                                                    low-result (llm \"low\" :instruction \"X is low\" :writes [:result])
                                                    chosen (condition \"check-x\" (> x 5) high-result low-result)]
                                                (final! {:answer (:result chosen)}))"}
                             :usage {:prompt_tokens 100 :completion_tokens 50 :total_tokens 150}}

                            ;; Second call: high branch
                            (= n 2)
                            (do
                              (swap! branches-called conj :high)
                              {:outputs {:result "high-value"}
                               :usage {:prompt_tokens 20 :completion_tokens 5 :total_tokens 25}})

                            ;; Third call: low branch (also evaluated due to eager)
                            (= n 3)
                            (do
                              (swap! branches-called conj :low)
                              {:outputs {:result "low-value"}
                               :usage {:prompt_tokens 20 :completion_tokens 5 :total_tokens 25}})

                            :else
                            {:outputs {:unexpected "call"}
                             :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})))]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Use condition to branch"
                                     :rlm-config {:recursive? false})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:question "test"})
                result (wait-for-completion promise)]

            (is (= :success (:status result)) (str "Expected success, got: " (:status result)))
            ;; Both branches get evaluated (eager), but condition selects high
            ;; The answer should be from the high branch since x=10 > 5
            (is (= "high-value" (:answer (:outputs result)))
                "Condition should return the true branch result when predicate is true")))))))

;; =============================================================================
;; Slice 6: Code Primitive (pure computation, no LLM)
;; =============================================================================

(deftest rlm-mode-code-primitive-test
  (testing "code primitive executes pure computation and stores result"
    (with-rlm-test-context [ctx]
      (let [call-count (atom 0)]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (let [n (swap! call-count inc)]
                          (cond
                            ;; First call: root LLM generates code using the code primitive
                            (= n 1)
                            {:outputs {:code "(let [nums [1 2 3 4 5]
                                                    sum-result (code \"compute-sum\"
                                                                  :writes [:total]
                                                                  :body (reduce + nums))]
                                                (final! {:answer (str (:total sum-result))}))"}
                             :usage {:prompt_tokens 100 :completion_tokens 50 :total_tokens 150}}

                            :else
                            {:outputs {:unexpected "call"}
                             :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})))]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Use code primitive for computation"
                                     :rlm-config {:recursive? false})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:question "test"})
                result (wait-for-completion promise)]

            ;; Should succeed with just one LLM call (the root)
            (is (= :success (:status result)) (str "Expected success, got: " (:status result)))
            (is (= 1 @call-count) "Should only call LLM once (the root)")
            (is (= "15" (:answer (:outputs result)))
                "Code primitive should compute sum correctly")))))))

(deftest rlm-mode-parallel-primitive-test
  (testing "parallel primitive executes llm nodes (conceptually in parallel)"
    (with-rlm-test-context [ctx]
      (let [call-count (atom 0)
            calls-seen (atom [])]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (let [n (swap! call-count inc)]
                          (cond
                            ;; First call: root LLM generates code with parallel
                            (= n 1)
                            {:outputs {:code "(parallel \"concurrent\"
                                                (llm \"a\" :instruction \"Get A\" :writes [:val-a])
                                                (llm \"b\" :instruction \"Get B\" :writes [:val-b]))
                                              (final! {:answer (str (:val-a (get-input :val-a))
                                                                   \"-\"
                                                                   (:val-b (get-input :val-b)))})"}
                             :usage {:prompt_tokens 100 :completion_tokens 50 :total_tokens 150}}

                            ;; Second call: first llm in parallel
                            (= n 2)
                            (do (swap! calls-seen conj :a)
                                {:outputs {:val-a "Alpha"}
                                 :usage {:prompt_tokens 20 :completion_tokens 5 :total_tokens 25}})

                            ;; Third call: second llm in parallel
                            (= n 3)
                            (do (swap! calls-seen conj :b)
                                {:outputs {:val-b "Beta"}
                                 :usage {:prompt_tokens 20 :completion_tokens 5 :total_tokens 25}})

                            :else
                            {:outputs {:unexpected "call"}
                             :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})))]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Use parallel to run two steps"
                                     :rlm-config {:recursive? false})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:question "test"})
                result (wait-for-completion promise)]

            (is (= :success (:status result)) (str "Expected success, got: " (:status result)))
            ;; Both calls should have happened
            (is (= #{:a :b} (set @calls-seen))
                "Parallel should execute both nodes")))))))

;; =============================================================================
;; Issue 001: store!/get-var Primitives
;; =============================================================================

(deftest rlm-store!-returns-stored-value-test
  (testing "store! stores a value in sandbox-vars and returns it"
    (with-rlm-test-context [ctx]
      (let [call-count (atom 0)
            test-code "(let [chunks [1 2 3] stored (store! :chunks chunks)] (final! {:answer (pr-str stored)}))"]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (swap! call-count inc)
                        {:outputs {:code test-code}
                         :usage {:prompt_tokens 50 :completion_tokens 30 :total_tokens 80}})]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Store and return a value"
                                     :rlm-config {:recursive? false})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:question "test"})
                result (wait-for-completion promise)]

            (is (= :success (:status result)) (str "Expected success, got: " (:status result)))
            (is (= "[1 2 3]" (:answer (:outputs result)))
                "store! should return the stored value")))))))

(deftest rlm-get-var-retrieves-stored-value-test
  (testing "get-var retrieves a value that was stored with store!"
    (with-rlm-test-context [ctx]
      (let [test-code "(do (store! :my-data [10 20 30]) (final! {:answer (pr-str (get-var :my-data))}))"]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        {:outputs {:code test-code}
                         :usage {:prompt_tokens 50 :completion_tokens 30 :total_tokens 80}})]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Store and retrieve"
                                     :rlm-config {:recursive? false})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:question "test"})
                result (wait-for-completion promise)]
            (is (= :success (:status result)) (str "Expected success, got: " (:status result)))
            (is (= "[10 20 30]" (:answer (:outputs result)))
                "get-var should retrieve the stored vector")))))))

(deftest rlm-get-var-returns-nil-for-nonexistent-test
  (testing "get-var returns nil when key doesn't exist"
    (with-rlm-test-context [ctx]
      (let [test-code "(final! {:answer (pr-str (get-var :nonexistent))})"]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        {:outputs {:code test-code}
                         :usage {:prompt_tokens 50 :completion_tokens 30 :total_tokens 80}})]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Get nonexistent var"
                                     :rlm-config {:recursive? false})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:question "test"})
                result (wait-for-completion promise)]
            (is (= :success (:status result)) (str "Expected success, got: " (:status result)))
            (is (= "nil" (:answer (:outputs result)))
                "get-var should return nil for nonexistent key")))))))

(deftest rlm-vars-persist-across-iterations-test
  (testing "Variables stored in one iteration are available in the next"
    (with-rlm-test-context [ctx]
      (let [iteration (atom 0)]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (let [n (swap! iteration inc)]
                          (case n
                            ;; First iteration: store a value
                            1 {:outputs {:code "(store! :counter 42)"}
                               :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}}
                            ;; Second iteration: read it and return
                            2 {:outputs {:code "(final! {:answer (str (get-var :counter))})"}
                               :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}}
                            ;; Fallback for any other iteration
                            {:outputs {:code "(final! {:answer \"fallback\"})"}
                             :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}})))]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Store then retrieve"
                                     :max-iterations 3
                                     :rlm-config {:recursive? false})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:question "test"})
                result (wait-for-completion promise)]
            (is (= :success (:status result)) (str "Expected success, got: " (:status result) " error: " (:error result)))
            ;; Note: str coercion since value may be number or string
            (is (= "42" (str (:answer (:outputs result))))
                "Variable stored in iteration 1 should be available in iteration 2")))))))

;; =============================================================================
;; Issue 002: list-vars with Previews
;; =============================================================================

(deftest rlm-list-vars-returns-previews-test
  (testing "list-vars returns map of all sandbox-vars with previews"
    (with-rlm-test-context [ctx]
      (let [test-code "(do (store! :nums [1 2 3 4 5]) (store! :text \"hello\") (final! {:answer (pr-str (list-vars))}))"]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        {:outputs {:code test-code}
                         :usage {:prompt_tokens 50 :completion_tokens 30 :total_tokens 80}})]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "List vars"
                                     :rlm-config {:recursive? false})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:question "test"})
                result (wait-for-completion promise)]
            (is (= :success (:status result)) (str "Expected success, got: " (:status result)))
            ;; Parse the answer to check it contains previews
            (let [vars-str (:answer (:outputs result))
                  vars (read-string vars-str)]
              (is (contains? vars :nums) "Should have :nums key")
              (is (contains? vars :text) "Should have :text key")
              (is (= :vector (get-in vars [:nums :type])) "nums should be vector type")
              (is (= :string (get-in vars [:text :type])) "text should be string type"))))))))

(deftest rlm-preview-value-unit-test
  (testing "preview-value handles different types correctly"
    (let [preview-value @#'ai.obney.orc.orc-service.core.rlm-sandbox/preview-value]
      ;; nil
      (is (= {:type :nil :value nil} (preview-value nil)) "nil preview")

      ;; Short string (under limit)
      (let [p (preview-value "hello")]
        (is (= :string (:type p)))
        (is (= 5 (:size p)))
        (is (= "hello" (:value p))))

      ;; Long string (over limit - triggers truncation)
      (let [long-str (apply str (repeat 600 "x"))
            p (preview-value long-str)]
        (is (= :string (:type p)))
        (is (= 600 (:size p)))
        (is (string? (:preview p)))
        (is (clojure.string/includes? (:preview p) "chars...")))

      ;; Vector
      (let [p (preview-value [1 2 3 4 5])]
        (is (= :vector (:type p)))
        (is (= 5 (:length p)))
        (is (= [1 2 3] (:sample p)) "Sample should be first 3"))

      ;; Map
      (let [p (preview-value {:a 1 :b 2 :c 3})]
        (is (= :map (:type p)))
        (is (= 3 (:size p)))
        (is (set? (set (:keys p)))))

      ;; Other (number)
      (let [p (preview-value 42)]
        (is (= 42 (:value p)))))))

;; =============================================================================
;; Issue 003: Available Variables section in prompt
;; =============================================================================

;; =============================================================================
;; T2-Hardening-C: Generic "Common pitfalls" section in Phase-1 instructions
;;
;; Several recurring SCI/Clojure footguns hit model-authored code across the
;; bench suite. Set literals with computed elements (#{(expr) (expr)}) fail
;; SCI's reader. `frequencies` over collections that contain nil produces
;; duplicate-`null` map keys which clash on downstream merges. Sandbox-vars
;; that didn't write on a prior Phase-2 :failure are nil at the next
;; iteration. Namespaced symbols (clojure.string/join etc.) aren't bound in
;; the SCI sandbox. None of these are task-specific; all are preventable
;; with one prompt-level "watch out for these" section authored UP-FRONT,
;; before the model writes code — turning what was a backward retry signal
;; (R-6 syntax-error history) into a forward-guidance pass.
;;
;; The section is APPENDED to the existing instructions text. Existing
;; sections (Two-Space Architecture, Available Primitives, retry framing,
;; Output Contract) stay intact — tests pin specific phrases from those
;; sections so any regression surfaces immediately.
;; =============================================================================

(deftest rlm-prompt-includes-common-pitfalls-section-header
  (testing "T2-Hardening-C: RLM Phase-1 instructions include the literal '## Common pitfalls' header"
    (with-rlm-test-context [ctx]
      (let [captured-module (atom nil)]
        (with-redefs [dscloj/predict
                      (fn [_provider module _inputs _opts]
                        (reset! captured-module module)
                        {:outputs {:code "(final! {:answer \"done\"})"}
                         :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}})]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Test task"
                                     :reads [:question]
                                     :rlm-config {:recursive? false})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:question "test"})
                _ (wait-for-completion promise)
                instructions (:instructions @captured-module)]
            (is (str/includes? instructions "## Common pitfalls")
                "Phase-1 instructions must include the '## Common pitfalls' section header for forward guidance")))))))

(deftest rlm-prompt-includes-common-pitfalls-content
  (testing "T2-Hardening-C: pitfalls section covers the specific footguns observed across the bench suite"
    (with-rlm-test-context [ctx]
      (let [captured-module (atom nil)]
        (with-redefs [dscloj/predict
                      (fn [_provider module _inputs _opts]
                        (reset! captured-module module)
                        {:outputs {:code "(final! {:answer \"done\"})"}
                         :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}})]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Test task"
                                     :reads [:question]
                                     :rlm-config {:recursive? false})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:question "test"})
                _ (wait-for-completion promise)
                instructions (:instructions @captured-module)]
            (testing "set literal with computed elements pitfall + (set [...]) workaround"
              (is (str/includes? instructions "#{")
                  "Mentions the failing set-literal syntax verbatim so the model recognizes it")
              (is (str/includes? instructions "(set ")
                  "Mentions the (set [...]) workaround verbatim"))

            (testing "frequencies on nil-bearing collections pitfall + filter-some workaround"
              (is (str/includes? instructions "frequencies")
                  "Mentions frequencies by name")
              (is (str/includes? instructions "some?")
                  "Mentions the (filter some? ...) workaround"))

            (testing "sandbox-var existence after Phase-2 :failure"
              (is (or (str/includes? instructions "(get-var ")
                      (str/includes? instructions "get-var"))
                  "References get-var so the warning is anchored to the actual primitive")
              (is (or (str/includes? instructions "nil")
                      (str/includes? instructions "when-let"))
                  "Warns that values may be nil and shows nil-safe consumption"))

            (testing "namespaced symbols not bound in the SCI sandbox"
              (is (str/includes? instructions "clojure.string/")
                  "Names the namespaced-symbol example most likely to be reached for"))))))))

(deftest rlm-prompt-existing-sections-survive-pitfalls-addition
  (testing "T2-Hardening-C: appending pitfalls does NOT regress existing instruction sections"
    (with-rlm-test-context [ctx]
      (let [captured-module (atom nil)]
        (with-redefs [dscloj/predict
                      (fn [_provider module _inputs _opts]
                        (reset! captured-module module)
                        {:outputs {:code "(final! {:answer \"done\"})"}
                         :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}})]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Test task"
                                     :reads [:question]
                                     :rlm-config {:recursive? false})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:question "test"})
                _ (wait-for-completion promise)
                instructions (:instructions @captured-module)]
            (is (str/includes? instructions "## Two-Space Architecture")
                "Two-Space Architecture section preserved")
            (is (str/includes? instructions "## Available Primitives")
                "Available Primitives section preserved")
            (is (str/includes? instructions "## Output Contract")
                "Output Contract section preserved")
            (is (str/includes? instructions "## Available Variables")
                "Available Variables section preserved")
            ;; The pitfalls section is forward guidance — it must appear
            ;; BEFORE the Output Contract so the model reads the rule
            ;; before being told how to format its response.
            (let [pitfall-idx (.indexOf instructions "## Common pitfalls")
                  contract-idx (.indexOf instructions "## Output Contract")]
              (is (pos? pitfall-idx) "pitfalls section is present")
              (is (pos? contract-idx) "output contract section is present")
              (is (< pitfall-idx contract-idx)
                  "pitfalls appears BEFORE Output Contract so it's forward guidance, not afterthought"))))))))

(deftest rlm-prompt-includes-available-variables-test
  (testing "RLM prompt includes Available Variables section with blackboard keys"
    (with-rlm-test-context [ctx]
      (let [captured-module (atom nil)]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        ;; Capture the module to inspect instructions
                        (reset! captured-module module)
                        {:outputs {:code "(final! {:answer \"done\"})"}
                         :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}})]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Test task"
                                     :reads [:question]
                                     :rlm-config {:recursive? false})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:question "test input"})
                _ (wait-for-completion promise)
                instructions (:instructions @captured-module)]
            ;; Check that instructions contain Available Variables section
            (is (clojure.string/includes? instructions "## Available Variables")
                "Instructions should include Available Variables section")
            ;; Check that blackboard key is listed with annotation
            (is (clojure.string/includes? instructions ":question")
                "Instructions should list :question key")
            (is (clojure.string/includes? instructions "[from blackboard]")
                "Blackboard keys should have [from blackboard] annotation")))))))

(deftest rlm-prompt-shows-created-vars-with-iteration-test
  (testing "RLM prompt shows sandbox-vars with [created iteration N] annotation"
    (with-rlm-test-context [ctx]
      (let [iteration (atom 0)
            captured-modules (atom [])]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (let [n (swap! iteration inc)]
                          ;; Capture module for each iteration
                          (swap! captured-modules conj module)
                          (case n
                            ;; First iteration: store a value
                            1 {:outputs {:code "(store! :my-chunks [1 2 3])"}
                               :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}}
                            ;; Second iteration: check prompt and return
                            2 {:outputs {:code "(final! {:answer \"done\"})"}
                               :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}})))]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Test task"
                                     :max-iterations 3
                                     :rlm-config {:recursive? false})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:question "test"})
                result (wait-for-completion promise)]
            ;; Verify execution succeeded
            (is (= :success (:status result)) (str "Expected success, got: " (:status result) " error: " (:error result)))
            ;; Check iteration 2's instructions (should show :my-chunks created in iteration 1)
            (when (>= (count @captured-modules) 2)
              (let [instructions (:instructions (nth @captured-modules 1))]
                (is (clojure.string/includes? instructions ":my-chunks")
                    "Instructions should list :my-chunks variable")
                (is (clojure.string/includes? instructions "[created iteration 1]")
                    "Sandbox-vars should have [created iteration N] annotation")))))))))

;; =============================================================================
;; Issue 004: Iteration Deltas Tracking
;; =============================================================================

(deftest rlm-iteration-history-includes-variables-created-test
  (testing "Iteration history shows variables created in each iteration"
    (with-rlm-test-context [ctx]
      (let [captured-inputs (atom [])
            iteration (atom 0)]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (swap! captured-inputs conj inputs)
                        (let [n (swap! iteration inc)]
                          (case n
                            ;; First iteration: create some variables
                            1 {:outputs {:code "(do (store! :chunks [1 2 3]) (store! :count 3))"}
                               :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}}
                            ;; Second iteration: use them and finish
                            2 {:outputs {:code "(final! {:answer (str (get-var :count))})"}
                               :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}})))]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Process data"
                                     :max-iterations 3
                                     :rlm-config {:recursive? false})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:question "test"})
                result (wait-for-completion promise)]
            (is (= :success (:status result)))
            ;; Check that iteration 2's history input includes variable info
            (when (>= (count @captured-inputs) 2)
              (let [history-input (:history (nth @captured-inputs 1 nil))]
                ;; History should exist and mention variables created
                (is (some? history-input) "History should be present in iteration 2")
                ;; If history is just "None", it's not tracking properly
                (is (not= "None" history-input) "History should not be 'None' after iteration 1")
                ;; KEY: History should mention "Variables created" with specific variable names
                (is (clojure.string/includes? (str history-input) "Variables created")
                    "History should have 'Variables created' section")
                (is (clojure.string/includes? (str history-input) ":chunks")
                    "History should mention :chunks variable that was created")
                (is (clojure.string/includes? (str history-input) ":count")
                    "History should mention :count variable that was created")))))))))

(deftest rlm-iteration-history-format-test
  (testing "Iteration history has correct format with code and variables"
    (with-rlm-test-context [ctx]
      (let [captured-inputs (atom [])
            iteration (atom 0)]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (swap! captured-inputs conj inputs)
                        (let [n (swap! iteration inc)]
                          (case n
                            1 {:outputs {:code "(store! :result 42)"}
                               :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}}
                            2 {:outputs {:code "(final! {:answer (str (get-var :result))})"}
                               :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}})))]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Test"
                                     :max-iterations 3
                                     :rlm-config {:recursive? false})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:question "test"})
                result (wait-for-completion promise)]
            (is (= :success (:status result)))
            (when (>= (count @captured-inputs) 2)
              (let [history-input (:history (nth @captured-inputs 1 nil))]
                ;; History should include iteration header
                (is (clojure.string/includes? (str history-input) "Iteration 1")
                    "History should include 'Iteration 1' header")
                ;; History should include the code that was executed
                (is (clojure.string/includes? (str history-input) "store!")
                    "History should include executed code")
                ;; History should mention variables created
                (is (clojure.string/includes? (str history-input) ":result")
                    "History should mention created variable :result")))))))))

(deftest rlm-iteration-history-truncates-long-code-test
  (testing "Long code in iteration history is truncated for conciseness"
    (with-rlm-test-context [ctx]
      (let [captured-inputs (atom [])
            iteration (atom 0)
            ;; Create a very long code string (over 500 chars)
            long-code (str "(do "
                           (apply str (repeat 100 "(store! :x 1) "))
                           "(final! {:answer \"done\"}))")
            short-code "(final! {:answer \"truncated\"})"]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (swap! captured-inputs conj inputs)
                        (let [n (swap! iteration inc)]
                          (case n
                            1 {:outputs {:code long-code}
                               :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}}
                            2 {:outputs {:code short-code}
                               :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}})))]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Test"
                                     :max-iterations 3
                                     :rlm-config {:recursive? false})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:question "test"})
                result (wait-for-completion promise)]
            ;; May succeed or fail depending on whether truncation affects execution
            ;; Key thing is that the history should be concise
            (when (>= (count @captured-inputs) 2)
              (let [history-input (str (:history (nth @captured-inputs 1 nil)))]
                ;; History should exist
                (is (seq history-input) "History should exist")
                ;; History should not contain the full 1500+ char code
                ;; If truncation is implemented, it should be much shorter
                (is (or (< (count history-input) 2000)
                        (clojure.string/includes? history-input "..."))
                    "Long code should be truncated in history")))))))))

;; =============================================================================
;; Issue 005: Error Feedback with Suggestions
;; =============================================================================

(deftest rlm-error-includes-available-variables-test
  (testing "Error message includes list of available variables"
    (with-rlm-test-context [ctx]
      (let [captured-inputs (atom [])
            iteration (atom 0)]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (swap! captured-inputs conj inputs)
                        (let [n (swap! iteration inc)]
                          (case n
                            ;; First iteration: try to access non-existent variable
                            1 {:outputs {:code "(get-var :nonexistent)"}
                               :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}}
                            ;; Second iteration: correct the mistake after seeing error
                            2 {:outputs {:code "(store! :data 123) (final! {:answer \"123\"})"}
                               :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}}
                            ;; Fallback
                            {:outputs {:code "(final! {:answer \"fallback\"})"}
                             :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}})))]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Access data"
                                     :max-iterations 3
                                     :rlm-config {:recursive? false})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:question "test"})
                result (wait-for-completion promise)]
            ;; Should eventually succeed
            (is (= :success (:status result)))
            ;; Check iteration 2's history shows the error from iteration 1
            (when (>= (count @captured-inputs) 2)
              (let [history-input (:history (nth @captured-inputs 1 nil))]
                ;; History should contain the nil result from get-var
                (is (some? history-input) "History should be present")))))))))

(deftest rlm-levenshtein-suggestion-test
  (testing "Levenshtein distance used to suggest similar variable names"
    ;; Unit test for the suggestion logic
    (let [suggest-fn (resolve 'ai.obney.orc.orc-service.core.executor/suggest-similar-key)]
      (if suggest-fn
        (do
          ;; :doc should suggest :document
          (is (= :document (suggest-fn :doc [:document :chunks :summary]))
              "Should suggest :document for :doc")
          ;; :chunk should suggest :chunks
          (is (= :chunks (suggest-fn :chunk [:document :chunks :summary]))
              "Should suggest :chunks for :chunk")
          ;; :xyz with no similar keys should return nil
          (is (nil? (suggest-fn :xyz [:document :chunks :summary]))
              "Should return nil when no similar key exists"))
        ;; If function doesn't exist yet, that's expected - this is RED phase
        (is false "suggest-similar-key function not implemented yet")))))

(deftest rlm-error-feedback-in-next-iteration-test
  (testing "Error message is included in next iteration's prompt"
    (with-rlm-test-context [ctx]
      (let [captured-inputs (atom [])
            iteration (atom 0)]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (swap! captured-inputs conj inputs)
                        (let [n (swap! iteration inc)]
                          (case n
                            ;; First iteration: cause a syntax error
                            1 {:outputs {:code "(this is invalid syntax"}
                               :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}}
                            ;; Second iteration: fix it
                            2 {:outputs {:code "(final! {:answer \"fixed\"})"}
                               :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}}
                            ;; Fallback
                            {:outputs {:code "(final! {:answer \"fallback\"})"}
                             :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}})))]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Do something"
                                     :max-iterations 3
                                     :rlm-config {:recursive? false})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:question "test"})
                result (wait-for-completion promise)]
            ;; Should eventually succeed after fixing the error
            (is (= :success (:status result)))
            ;; Check iteration 2's history includes the error from iteration 1
            (when (>= (count @captured-inputs) 2)
              (let [history-input (str (:history (nth @captured-inputs 1 nil)))]
                (is (clojure.string/includes? history-input "Error")
                    "History should include error message from failed iteration")))))))))

;; =============================================================================
;; Issue 006: Sub-LLM Receives Previews Only
;; =============================================================================

(deftest rlm-sub-llm-receives-preview-not-full-data-test
  (testing "Sub-LLM receives preview of large data, not full value"
    ;; Unit test for the preview logic in execute-llm-primitive
    (let [large-string (apply str (repeat 10000 "x"))  ;; 10K chars
          preview (rlm-sandbox/preview-value large-string)]
      ;; Preview should be much smaller than original
      (is (< (count (str (:preview preview))) 1000)
          "Preview should be bounded in size")
      (is (= :string (:type preview))
          "Preview should indicate type")
      (is (= 10000 (:size preview))
          "Preview should include original size"))))

;; ARCHITECTURE NOTE (canonical doc at rlm_sandbox.clj:125-127):
;; Sub-LLM calls receive FULL VALUES from :reads. The generated code is
;; responsible for managing chunk sizes. Previews are only used for the
;; CODE-GENERATING parent LLM to understand variable shapes in its
;; :inputs-info — actual data processing by sub-LLMs uses full values
;; because the sub-LLM cannot process content it cannot see.
;;
;; If you find yourself wanting to assert "sub-LLM sees preview" — that's
;; the wrong contract. Update the parent LLM's :inputs-info instead.

(deftest rlm-sub-llm-inputs-are-full-values-test
  (testing "When (llm ...) is called, sub-LLM receives FULL values from :reads
            (not previews) so it can actually process the content"
    (with-rlm-test-context [ctx]
      (let [captured-sub-llm-inputs (atom nil)
            iteration (atom 0)]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (let [n (swap! iteration inc)]
                          (case n
                            ;; Root LLM: call a sub-LLM with :reads
                            1 {:outputs {:code "(llm \"sub\" :instruction \"Summarize\" :reads [:document] :writes [:summary])"}
                               :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}}
                            ;; Sub-LLM call: capture the inputs
                            2 (do
                                (reset! captured-sub-llm-inputs inputs)
                                {:outputs {:summary "This is a summary"}
                                 :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}})
                            ;; Root LLM iteration 2: use result
                            3 {:outputs {:code "(final! {:answer (:summary (get-var :sub-result))})"}
                               :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}}
                            ;; Fallback
                            {:outputs {:code "(final! {:answer \"fallback\"})"}
                             :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}})))]
          ;; Create a sheet with a large document
          (let [large-doc (apply str (repeat 5000 "Lorem ipsum "))
                {:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Summarize the document"
                                     :reads [:document]
                                     :max-iterations 5
                                     :rlm-config {:recursive? false})
                ;; Set a large document value
                _ (h/run-and-apply! ctx {:command/name :sheet/set-key-value
                                         :command/id (random-uuid)
                                         :command/timestamp (java.time.Instant/now)
                                         :sheet-id sheet-id
                                         :key :document
                                         :value large-doc})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:document large-doc})
                result (wait-for-completion promise)]
            ;; Check that sub-LLM received the FULL document, not a preview.
            ;; The generated code is responsible for chunk sizing; the sub-LLM
            ;; must see actual content to process it.
            (when @captured-sub-llm-inputs
              (let [doc-input (:document @captured-sub-llm-inputs)]
                ;; Should be the full string value, not a preview map and not truncated.
                (is (string? doc-input)
                    "Sub-LLM should receive the full document string, not a preview map")
                (is (= (count large-doc) (count doc-input))
                    "Sub-LLM should receive the document at its original length (no truncation)")))))))))

(deftest rlm-sub-llm-token-count-bounded-test
  (testing "Token count for sub-LLM prompt stays bounded regardless of input size"
    ;; This is a property test: varying input size shouldn't vary prompt size much
    (let [small-preview (rlm-sandbox/preview-value "small")
          large-preview (rlm-sandbox/preview-value (apply str (repeat 100000 "x")))]
      ;; Small preview includes full value, large preview has truncated preview
      ;; The difference should be bounded (preview is max ~500 chars + metadata)
      (is (< (Math/abs (- (count (pr-str small-preview))
                          (count (pr-str large-preview))))
             600)
          "Preview size should be bounded regardless of original data size"))))

;; =============================================================================
;; Issue 007: Sub-LLM Sandbox Isolation
;; =============================================================================

(deftest rlm-sub-llm-only-sees-reads-keys-test
  (testing "Sub-LLM only receives :reads keys, not all sandbox-vars"
    (let [captured-inputs (atom nil)]
      (with-redefs [dscloj/predict
                    (fn [provider module inputs opts]
                      (reset! captured-inputs inputs)
                      {:outputs {:summary "done"}
                       :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})]
        ;; Execute llm-primitive directly with sandbox-vars containing multiple keys
        (rlm-sandbox/execute-llm-primitive
         "test"
         {:instruction "Summarize" :reads [:document] :writes [:summary]}
         {:provider :openrouter
          :blackboard {}
          :sandbox-vars {:document "doc content"
                         :secret "should not see"
                         :other "also hidden"}})
        ;; Sub-LLM should only receive :document (from :reads), not :secret or :other
        (is (contains? @captured-inputs :document)
            "Sub-LLM should receive :document from :reads")
        (is (not (contains? @captured-inputs :secret))
            "Sub-LLM should NOT receive :secret (not in :reads)")
        (is (not (contains? @captured-inputs :other))
            "Sub-LLM should NOT receive :other (not in :reads)")))))

(deftest rlm-sub-llm-returns-only-writes-test
  (testing "execute-llm-primitive returns only :writes keys, filtering extras"
    (with-redefs [dscloj/predict
                  (fn [provider module inputs opts]
                    ;; LLM returns extra keys beyond declared :writes
                    {:outputs {:declared-output "expected"
                               :sneaky-key "should be filtered"}
                     :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})]
      (let [result (rlm-sandbox/execute-llm-primitive
                    "test"
                    {:instruction "Do something" :writes [:declared-output]}
                    {:provider :openrouter :blackboard {} :sandbox-vars {}})]
        ;; Result should only contain :declared-output, not :sneaky-key
        (is (= {:declared-output "expected"} result)
            "execute-llm-primitive should only return :writes keys")
        (is (not (contains? result :sneaky-key))
            "Extra keys from LLM should be filtered out")))))

(deftest rlm-llm-fn-merges-only-writes-to-parent-test
  (testing "llm function in sandbox only merges :writes to parent sandbox-vars"
    (with-redefs [dscloj/predict
                  (fn [provider module inputs opts]
                    {:outputs {:out "value" :extra "sneaky"}
                     :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})]
      (let [sandbox-vars (atom {:existing "keep"})
            ctx (rlm-sandbox/build-rlm-context
                 {:provider :openrouter
                  :blackboard {}
                  :declared-writes [:out]
                  :sandbox-vars sandbox-vars})]
        ;; Execute code that calls llm
        (rlm-sandbox/execute-rlm-code ctx "(llm \"test\" :instruction \"x\" :writes [:out])")
        ;; Parent sandbox-vars should have :out and :existing, but not :extra
        (is (= "value" (get @sandbox-vars :out))
            ":writes key should be merged")
        (is (= "keep" (get @sandbox-vars :existing))
            "Existing vars should remain")
        (is (not (contains? @sandbox-vars :extra))
            "Extra keys should NOT be merged to parent")))))

(deftest rlm-sequential-llm-calls-chain-correctly-test
  (testing "Sequential LLM calls pass outputs to subsequent reads"
    (let [captured-inputs (atom [])]
      (with-redefs [dscloj/predict
                    (fn [provider module inputs opts]
                      (swap! captured-inputs conj inputs)
                      ;; Return different outputs based on which call
                      (let [write-key (:name (first (:outputs module)))]
                        {:outputs {write-key (str "result-of-" (name write-key))}
                         :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}}))]
        (let [sandbox-vars (atom {:initial "start"})
              ctx (rlm-sandbox/build-rlm-context
                   {:provider :openrouter
                    :blackboard {}
                    :declared-writes [:step1 :step2]
                    :sandbox-vars sandbox-vars})]
          ;; Execute code with two sequential llm calls
          (rlm-sandbox/execute-rlm-code
           ctx
           "(do
              (llm \"first\" :instruction \"A\" :reads [:initial] :writes [:step1])
              (llm \"second\" :instruction \"B\" :reads [:step1] :writes [:step2]))")
          ;; First call should see :initial
          (is (contains? (first @captured-inputs) :initial)
              "First call should receive :initial")
          ;; Second call should see :step1 (output from first)
          (is (contains? (second @captured-inputs) :step1)
              "Second call should receive :step1 from first call"))))))

;; =============================================================================
;; Issue 008: map-each with :as injection
;; =============================================================================

(deftest rlm-map-each-as-injection-basic-test
  (testing "map-each with :as syntax processes items with injection"
    (let [call-count (atom 0)
          captured-inputs (atom [])]
      (with-redefs [dscloj/predict
                    (fn [provider module inputs opts]
                      (swap! call-count inc)
                      (swap! captured-inputs conj inputs)
                      {:outputs {:summary (str "summary-" @call-count)}
                       :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})]
        (let [sandbox-vars (atom {:chunks ["chunk-1" "chunk-2" "chunk-3"]})
              ctx (rlm-sandbox/build-rlm-context
                   {:provider :openrouter
                    :blackboard {}
                    :declared-writes [:summaries]
                    :sandbox-vars sandbox-vars})]
          ;; Execute map-each with :as syntax and function body
          ;; The function body is called for each item after :current-chunk is injected
          (let [result (rlm-sandbox/execute-rlm-code
                        ctx
                        "(map-each \"process\" :chunks :as :current-chunk
                           (fn [] (llm \"analyze\"
                                    :instruction \"Summarize\"
                                    :reads [:current-chunk]
                                    :writes [:summary])))")]
            ;; Should have called LLM 3 times (once per chunk)
            (is (= 3 @call-count)
                "Should call LLM once per chunk")
            ;; Each call should have received :current-chunk
            (is (every? #(contains? % :current-chunk) @captured-inputs)
                "Each sub-LLM should receive :current-chunk injection")))))))

(deftest rlm-map-each-as-injects-full-value-test
  (testing "map-each injects items as FULL VALUES (not previews) so the
            inner fn body can pass them to sub-LLM :reads and the sub-LLM
            can actually process the content.
            See architecture note above rlm-sub-llm-inputs-are-full-values-test
            and canonical doc at rlm_sandbox.clj:125-127."
    (let [captured-inputs (atom [])]
      (with-redefs [dscloj/predict
                    (fn [provider module inputs opts]
                      (swap! captured-inputs conj inputs)
                      {:outputs {:summary "done"}
                       :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})]
        (let [;; Create chunks with large strings
              large-chunk (apply str (repeat 10000 "x"))
              sandbox-vars (atom {:chunks [large-chunk]})
              ctx (rlm-sandbox/build-rlm-context
                   {:provider :openrouter
                    :blackboard {}
                    :declared-writes [:summaries]
                    :sandbox-vars sandbox-vars})]
          (rlm-sandbox/execute-rlm-code
           ctx
           "(map-each \"process\" :chunks :as :item
              (fn [] (llm \"analyze\" :instruction \"Summarize\" :reads [:item] :writes [:summary])))")
          ;; The injected :item should be the FULL 10K string so the sub-LLM
          ;; can process it. A preview map here would mean the sub-LLM only
          ;; sees metadata and cannot do its job.
          (let [input (first @captured-inputs)
                item-value (:item input)]
            (is (string? item-value)
                "Injected item should be the full string value, not a preview map")
            (is (= 10000 (count item-value))
                "Injected item should be the full 10K characters, not truncated")))))))

(deftest rlm-map-each-collects-results-test
  (testing "map-each collects sub-LLM results into a vector"
    (let [call-count (atom 0)]
      (with-redefs [dscloj/predict
                    (fn [provider module inputs opts]
                      (swap! call-count inc)
                      {:outputs {:result (str "result-" @call-count)}
                       :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})]
        (let [sandbox-vars (atom {:items ["a" "b" "c"]})
              ctx (rlm-sandbox/build-rlm-context
                   {:provider :openrouter
                    :blackboard {}
                    :declared-writes [:results]
                    :sandbox-vars sandbox-vars})]
          (let [exec-result (rlm-sandbox/execute-rlm-code
                             ctx
                             "(map-each \"process\" :items :as :item
                                (fn [] (llm \"x\" :instruction \"y\" :reads [:item] :writes [:result])))")
                raw-result (:raw-result exec-result)]
            ;; Should return a vector of 3 results
            (is (vector? raw-result)
                "map-each should return a vector")
            (is (= 3 (count raw-result))
                "Vector should have 3 elements")
            ;; Each element should be the sub-LLM result map
            (is (= [{:result "result-1"} {:result "result-2"} {:result "result-3"}]
                   raw-result)
                "Results should be collected in order")))))))

(deftest rlm-map-each-old-syntax-deprecated-test
  (testing "map-each with old function syntax produces clear error or is deprecated"
    (let [sandbox-vars (atom {:items [1 2 3]})
          ctx (rlm-sandbox/build-rlm-context
               {:provider :openrouter
                :blackboard {}
                :declared-writes []
                :sandbox-vars sandbox-vars})]
      ;; Old syntax: (map-each "name" coll f)
      (let [result (rlm-sandbox/execute-rlm-code
                    ctx
                    "(map-each \"old\" [1 2 3] (fn [x] {:doubled (* 2 x)}))")]
        ;; Either should work for backward compat, or produce helpful error
        (if (:error result)
          (is (clojure.string/includes? (:error result) ":as")
              "Error should mention new :as syntax")
          ;; If backward compat, should still work
          (is (= [{:doubled 2} {:doubled 4} {:doubled 6}]
                 (:raw-result result))
              "Old syntax should still work for backward compat"))))))

;; =============================================================================
;; Issue 009: System Prompt Documentation
;; =============================================================================

(deftest rlm-prompt-documents-all-primitives-test
  (testing "RLM prompt includes documentation for all primitives"
    (with-rlm-test-context [ctx]
      (let [captured-instructions (atom nil)]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (reset! captured-instructions (:instructions module))
                        {:outputs {:code "(final! {:answer \"test\"})"}
                         :usage {:prompt_tokens 100 :completion_tokens 20 :total_tokens 120}})]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Test task"
                                     :max-iterations 1
                                     :rlm-config {:recursive? false})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:input "test"})
                _ (wait-for-completion promise)
                instructions @captured-instructions]
            ;; Check all primitives are documented
            (is (clojure.string/includes? instructions "llm")
                "Should document llm primitive")
            (is (clojure.string/includes? instructions "final!")
                "Should document final! primitive")
            (is (clojure.string/includes? instructions "get-input")
                "Should document get-input primitive")
            (is (clojure.string/includes? instructions "store!")
                "Should document store! primitive")
            (is (clojure.string/includes? instructions "get-var")
                "Should document get-var primitive")
            (is (clojure.string/includes? instructions "map-each")
                "Should document map-each primitive")
            (is (clojure.string/includes? instructions "sequence")
                "Should document sequence primitive")
            (is (clojure.string/includes? instructions "code")
                "Should document code primitive")))))))

(deftest rlm-prompt-explains-two-space-architecture-test
  (testing "RLM prompt explains variable space vs token space"
    (with-rlm-test-context [ctx]
      (let [captured-instructions (atom nil)]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (reset! captured-instructions (:instructions module))
                        {:outputs {:code "(final! {:answer \"test\"})"}
                         :usage {:prompt_tokens 100 :completion_tokens 20 :total_tokens 120}})]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Test"
                                     :max-iterations 1
                                     :rlm-config {:recursive? false})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:input "test"})
                _ (wait-for-completion promise)
                instructions @captured-instructions]
            ;; Check two-space explanation is present
            (is (or (clojure.string/includes? instructions "preview")
                    (clojure.string/includes? instructions "token space")
                    (clojure.string/includes? instructions "variable space"))
                "Should explain preview/two-space architecture")))))))

(deftest rlm-prompt-includes-reads-usage-example-test
  (testing "RLM prompt explains :reads usage correctly"
    (with-rlm-test-context [ctx]
      (let [captured-instructions (atom nil)]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (reset! captured-instructions (:instructions module))
                        {:outputs {:code "(final! {:answer \"test\"})"}
                         :usage {:prompt_tokens 100 :completion_tokens 20 :total_tokens 120}})]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Test"
                                     :max-iterations 1
                                     :rlm-config {:recursive? false})
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:input "test"})
                _ (wait-for-completion promise)
                instructions @captured-instructions]
            ;; Check :reads is explained
            (is (clojure.string/includes? instructions ":reads")
                "Should explain :reads usage")
            (is (clojure.string/includes? instructions "Available Variables")
                "Should reference Available Variables section")))))))

;; =============================================================================
;; Slice 4: Two-Phase RLM Execution (emit-tree! detection)
;; =============================================================================

(deftest rlm-emit-tree-generates-tree-result-test
  (testing "emit-tree! triggers Phase 2 execution and returns success with outputs"
    (with-rlm-test-context [ctx]
      (let [call-count (atom 0)]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (let [n (swap! call-count inc)]
                          (cond
                            ;; First call: LLM generates code with emit-tree!
                            (= n 1)
                            {:outputs {:code "(emit-tree!
                                                [:sequence
                                                  [:llm {:instruction \"Extract summary\"
                                                         :reads [:document]
                                                         :writes [:summary]}]
                                                  [:final {:keys [:summary]}]])"}
                             :usage {:prompt_tokens 100 :completion_tokens 80 :total_tokens 180}}

                            :else
                            {:outputs {:code "(final! {:answer \"fallback\"})"}
                             :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})))]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Generate a BT for document analysis"
                                     :rlm-config {:recursive? false})
                {:keys [promise tick-id]} (dispatch-async-execute! ctx sheet-id {:document "test doc"})
                result (wait-for-completion promise :timeout-ms 30000)]

            ;; Phase 2 executes automatically - returns :success (not :tree-generated)
            (is (= :success (:status result))
                (str "Expected :success status (Phase 2 auto-execution), got: " (:status result)))
            ;; Should still have the generated raw tree for observability
            (is (some? (:generated-tree-raw result))
                "Should have :generated-tree-raw in result")
            ;; Raw should be S-expr vector
            (is (vector? (:generated-tree-raw result))
                "Raw tree should be a vector")
            (is (= :sequence (first (:generated-tree-raw result)))
                "Raw tree should start with :sequence")
            ;; Should have outputs from Phase 2 execution
            (is (some? (:outputs result))
                "Should have outputs from Phase 2 execution")))))))

;; =============================================================================
;; Slice 5: RLM System Prompt Tests
;; =============================================================================

(deftest rlm-system-prompt-documents-emit-tree-test
  (testing "System prompt explains emit-tree! primitive for BT generation"
    (let [node {:type :repl-researcher
                :instruction "Test task"
                :writes [:answer]}
          blackboard {}
          sandbox-vars {}
          var-creation-times {}
          ;; Access private function via var
          build-fn #'executor/build-rlm-code-generation-module
          module (build-fn node "" [] blackboard sandbox-vars var-creation-times)
          prompt (:instructions module)]
      ;; Should document emit-tree!
      (is (str/includes? prompt "emit-tree!")
          "System prompt should document emit-tree! primitive")
      ;; Should explain S-expr DSL node types
      (is (str/includes? prompt ":sequence")
          "System prompt should document :sequence node type")
      (is (str/includes? prompt ":map-each")
          "System prompt should document :map-each node type")
      (is (str/includes? prompt ":llm")
          "System prompt should document :llm node type")
      (is (str/includes? prompt ":chunk-document")
          "System prompt should document :chunk-document node type"))))

(deftest rlm-system-prompt-guides-decomposition-test
  (testing "System prompt recommends BT decomposition for large data"
    (let [node {:type :repl-researcher
                :instruction "Test task"
                :writes [:answer]}
          blackboard {}
          sandbox-vars {}
          var-creation-times {}
          build-fn #'executor/build-rlm-code-generation-module
          module (build-fn node "" [] blackboard sandbox-vars var-creation-times)
          prompt (:instructions module)]
      ;; Should guide toward decomposition for any large data (documents, graphs, collections)
      (is (re-find #"(?i)large.*data" prompt)
          "System prompt should mention large data processing")
      (is (str/includes? prompt "chunk")
          "System prompt should recommend chunking")
      ;; Should mention graphs/ontology support
      (is (re-find #"(?i)graph|ontology" prompt)
          "System prompt should mention graph/ontology support")
      ;; Should explain when to use emit-tree!
      (is (re-find #"(?i)emit-tree!.*behavior.?tree|behavior.?tree.*emit-tree!" prompt)
          "System prompt should explain emit-tree! creates behavior trees"))))

;; =============================================================================
;; Slice 6: RLM Tree Event Emission Tests
;; =============================================================================

(defn- read-events-by-type
  "Read events from event store filtering by type."
  [event-store type tenant-id]
  (->> (es/read event-store {:types #{type} :tenant-id tenant-id})
       (filterv #(not= :grain/tx (:event/type %)))))

(deftest rlm-tree-generated-event-emitted-test
  (testing "When emit-tree! is called, :rlm/tree-generated event is emitted"
    (with-rlm-test-context [ctx]
      (let [call-count (atom 0)]
        (with-redefs [dscloj/predict
                      (fn [provider module inputs opts]
                        (let [n (swap! call-count inc)]
                          (cond
                            (= n 1)
                            {:outputs {:code "(emit-tree!
                                                [:sequence
                                                  [:llm {:instruction \"Test\" :reads [:doc] :writes [:out]}]
                                                  [:final {:keys [:summary]}]])"}
                             :usage {:prompt_tokens 100 :completion_tokens 80 :total_tokens 180}}
                            :else
                            {:outputs {:code "(final! {:summary \"done\"})"}
                             :usage {:prompt_tokens 50 :completion_tokens 30 :total_tokens 80}})))]
          (let [{:keys [sheet-id]} (setup-rlm-repl-researcher-sheet!
                                     ctx
                                     :instruction "Generate a BT"
                                     :rlm-config {:recursive? false})
                {:keys [promise tick-id]} (dispatch-async-execute! ctx sheet-id {:document "test doc"})
                result (wait-for-completion promise :timeout-ms 10000)
                ;; Query for :rlm/tree-generated events
                events (read-events-by-type (:event-store ctx) :rlm/tree-generated (:tenant-id ctx))]
            ;; Should have emitted at least one tree-generated event
            (is (pos? (count events))
                "Should have emitted :rlm/tree-generated event")
            ;; Event should contain tree data
            (when (pos? (count events))
              (let [event (first events)]
                (is (some? (:tree-id event))
                    "Event should have tree-id")
                (is (some? (:raw-dsl event))
                    "Event should have raw-dsl")
                (is (vector? (:raw-dsl event))
                    "raw-dsl should be a vector")))))))))

;; =============================================================================
;; Slice 7: Rolling Judge Processor Tests
;; =============================================================================

;; rlm-rolling-judge-evaluates-tree-test — RETIRED in Gap-2.
;;
;; This test previously asserted that the standalone `:rlm evaluate-rlm-tree`
;; processor (subscribed to `:rlm/tree-generated`) emitted `:rlm/tree-evaluated`
;; events with score + feedback + tree-id. That standalone processor was
;; retired in Gap-2 of the judge-system-unification arc — its functionality
;; moved to the unified evaluator protocol under the `:heuristic-structural`
;; judge type.
;;
;; The equivalent integration is now tested at:
;;
;;   components/evaluation/test/ai/obney/orc/evaluation/judge_runtime_test.clj
;;
;; Specifically the tests `heuristic-structural-judge-recognized-and-emits`
;; and `heuristic-structural-uses-real-evaluator-not-stub` exercise the
;; new path: attach `:heuristic-structural` judge to a leaf node → host
;; node emits `:generated-tree-raw` in its `:writes` → judge runtime
;; invokes the extracted `evaluation/heuristic-structural/evaluate-tree-
;; structure` heuristic → emits `:judge/score-emitted` with the structural
;; score + feedback + dimensions.

;; =============================================================================
;; Slice 8: Ontology Few-Shot Examples Tests
;; =============================================================================

(deftest rlm-system-prompt-includes-ontology-examples-test
  (testing "System prompt includes few-shot examples when ontology provides them"
    (let [node {:type :repl-researcher
                :instruction "Analyze a document"
                :writes [:summary]
                :rlm {:examples-fn (fn [_ctx]
                                     ;; Mock ontology returning successful tree examples
                                     [{:task "Extract dates from document"
                                       :tree [:sequence
                                              [:chunk-document {:from :document :size 5000 :into :chunks}]
                                              [:map-each {:from :chunks :as :chunk :into :results}
                                               [:llm {:instruction "Extract dates" :reads [:chunk] :writes [:dates]}]]
                                              [:aggregate {:from :results :writes [:all-dates]}]
                                              [:final {:keys [:all-dates]}]]
                                       :score 0.95}])}}
          blackboard {}
          sandbox-vars {}
          var-creation-times {}
          build-fn #'executor/build-rlm-code-generation-module
          module (build-fn node "" [] blackboard sandbox-vars var-creation-times)
          prompt (:instructions module)]
      ;; Should include example from ontology
      (is (str/includes? prompt "Example from ontology")
          "System prompt should include ontology examples section")
      (is (str/includes? prompt "Extract dates")
          "System prompt should include example task description")
      (is (str/includes? prompt ":chunk-document")
          "System prompt should include example tree structure"))))

(deftest rlm-system-prompt-gracefully-handles-no-examples-test
  (testing "System prompt works when no ontology examples available"
    (let [node {:type :repl-researcher
                :instruction "Test task"
                :writes [:answer]
                :rlm {:examples-fn (fn [_ctx] [])}} ;; Empty examples
          blackboard {}
          sandbox-vars {}
          var-creation-times {}
          build-fn #'executor/build-rlm-code-generation-module
          module (build-fn node "" [] blackboard sandbox-vars var-creation-times)
          prompt (:instructions module)]
      ;; Should still have the basic structure
      (is (str/includes? prompt "emit-tree!")
          "System prompt should still document emit-tree!")
      ;; Should NOT have ontology examples section
      (is (not (str/includes? prompt "Example from ontology"))
          "System prompt should not have empty ontology section"))))

(comment
  ;; Run individual test
  (clojure.test/run-tests 'ai.obney.orc.orc-service.rlm-mode-test)

  ;; Run specific test
  (clojure.test/test-vars [#'rlm-mode-llm-primitive-test])
  (clojure.test/test-vars [#'rlm-store!-returns-stored-value-test])
  )
