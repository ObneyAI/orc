(ns ai.obney.orc.orc-service.repl-researcher-async-test
  "Integration test for repl-researcher through the full async pipeline.
   Uses a real MCP connection (StaticMCPClient with configurable handler)
   to verify the complete execution chain."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.mcp-sheet-builder.interface :as mcp]
            [dscloj.core :as dscloj]))

;; =============================================================================
;; Async Context with MCP Support
;; =============================================================================

(defn- create-mcp-async-context
  "Create an async test context with :call-tool-fn and :dscloj-provider
   injected into the base context BEFORE todo processors start."
  [call-tool-fn]
  (let [ps (pubsub/start {:type :core-async
                           :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory}
                               :event-pubsub ps
                               :logger nil})
        cache-dir (str "/tmp/mcp-async-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  :call-tool-fn call-tool-fn
                  :dscloj-provider :test}
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

(defn- stop-mcp-async-context [ctx]
  (doseq [[_ processor] (:processors ctx)]
    (tp/stop processor))
  (when-let [ps (:event-pubsub ctx)]
    (pubsub/stop ps))
  (when-let [cache (:cache ctx)]
    (kv/stop cache))
  (when-let [event-store (:event-store ctx)]
    (es/stop event-store)))

(defmacro with-mcp-async-context
  [[ctx-sym call-tool-fn] & body]
  `(let [~ctx-sym (create-mcp-async-context ~call-tool-fn)]
     (try
       ~@body
       (finally
         (stop-mcp-async-context ~ctx-sym)))))

;; =============================================================================
;; Helper: Build repl-researcher sheet via commands
;; =============================================================================

(defn- setup-repl-researcher-sheet!
  [ctx & {:keys [instruction mcp-tools max-iterations reads writes]
          :or {instruction "Find the answer"
               mcp-tools ["lookup"]
               max-iterations 5
               reads [:question]
               writes [:answer]}}]
  (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "Researcher Test"))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)]
    (doseq [k (concat reads writes)]
      (h/run-and-apply! ctx (h/make-declare-key-command sheet-id k :string)))
    (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
          seq-id (-> seq-result :command-result/events first :node-id)
          node-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :repl-researcher :parent-id seq-id))
          node-id (-> node-result :command-result/events first :node-id)]
      (h/run-and-apply! ctx (h/make-set-repl-researcher-config-command
                              sheet-id node-id instruction reads writes mcp-tools
                              :max-iterations max-iterations))
      {:sheet-id sheet-id :node-id node-id})))

(defn- dispatch-async-execute!
  [ctx sheet-id inputs & {:keys [timeout-ms] :or {timeout-ms 15000}}]
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

(defn- wait-for-completion [p & {:keys [timeout-ms] :or {timeout-ms 15000}}]
  (let [result (deref p timeout-ms ::timeout)]
    (if (= result ::timeout) :timeout result)))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest repl-researcher-async-with-real-mcp-test
  (testing "full async pipeline with real MCP protocol"
    (let [tool-calls (atom [])
          mcp-conn (mcp/connect
                     {:type :static
                      :tools [{:name "lookup"
                               :description "Look up a value"
                               :inputSchema {:type "object"
                                             :properties {"key" {:type "string"}}
                                             :required ["key"]}}]
                      :call-tool-handler
                      (fn [tool-name args]
                        (swap! tool-calls conj {:tool tool-name :args args})
                        {"value" "42"})})
          call-tool-fn (partial mcp/call-tool mcp-conn)
          call-count (atom 0)]
      (with-mcp-async-context [ctx call-tool-fn]
        (with-redefs [dscloj/predict
                      (fn [_provider _module _inputs _opts]
                        (let [n (swap! call-count inc)]
                          (if (= n 1)
                            {:outputs {:code "(println (get (lookup {\"key\" \"answer\"}) \"value\"))"}
                             :usage {:prompt_tokens 50 :completion_tokens 20 :total_tokens 70}}
                            {:outputs {:code "(final! {:answer \"42\"})"}
                             :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})))]
          (let [{:keys [sheet-id]} (setup-repl-researcher-sheet! ctx)
                {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:question "What is the answer?"})
                result (wait-for-completion promise)]
            (is (not= :timeout result) "Execution should complete within timeout")
            (is (= :success (:status result)))
            (is (= "42" (:answer (:outputs result))))
            (is (= 1 (count @tool-calls)))
            (is (= "lookup" (:tool (first @tool-calls))))))))))

(deftest repl-researcher-async-no-tools-test
  (testing "repl-researcher works without MCP tools (pure computation)"
    (with-mcp-async-context [ctx nil]
      (with-redefs [dscloj/predict
                    (fn [_provider _module _inputs _opts]
                      {:outputs {:code "(final! {:answer \"42\"})"}
                       :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})]
        (let [{:keys [sheet-id]} (setup-repl-researcher-sheet!
                                   ctx :mcp-tools [] :instruction "Compute 21+21")
              {:keys [promise]} (dispatch-async-execute! ctx sheet-id {:question "What is 21+21?"})
              result (wait-for-completion promise)]
          (is (not= :timeout result))
          (is (= :success (:status result)))
          (is (= "42" (:answer (:outputs result)))))))))
