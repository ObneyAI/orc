(ns ai.obney.orc.orc-service.ce2-effector-leaf-context-test
  "CE-2 durable tests — engine leaf effector reachability (ADR 0018).

   Two engine gaps, proven through the REAL execute-tree / :sheet/tick-tree /
   execute-leaf-node / execute-code path with a mock provider + real Grain
   (in-memory event store, schema-validated commands -> events -> projections):

     G1  An OPAQUE :tool-context threaded from the RLM execution context
         (execute-tree's context arg) reaches the Phase-2 :code leaf fn's
         execution context — across the async command -> event -> processor
         boundary, at any tree depth, and transitively into nested ticks.
     G2  The :available-code-nodes VALUE reaches the RLM code-gen inputs map
         handed to the model (declared field + catalog prompt note non-empty).

   Discipline: assertions read the leaf's received context / stub-tool record
   BACK (never a node return value). :tool-context / the gated tool are STUB
   INSTRUMENTS; the engine threading is the system under test."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.test-helpers]
            [ai.obney.orc.orc-service.core.todo-processors :as tp-core]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.orc.orc-service.core.logic :as service-logic]))


(defn- create-test-context [extra-base]
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/ce2-effector-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx (merge {:event-store event-store
                         :cache cache
                         :tenant-id tenant-id
                         :llm-provider nil
                         :event-pubsub ps
                         :command-registry (cp/global-command-registry)
                         :query-registry (qp/global-query-registry)
                         ::cache-dir cache-dir}
                        extra-base)
        processors (reduce-kv
                    (fn [acc proc-name {:keys [handler-fn topics]}]
                      (assoc acc proc-name
                             (tp/start {:event-pubsub ps
                                        :topics topics
                                        :handler-fn handler-fn
                                        :context base-ctx})))
                    {}
                    @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defn- stop-test-context [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [cache (:cache ctx)] (kv/stop cache))
  (when-let [event-store (:event-store ctx)] (es/stop event-store))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defmacro ^:private with-test-context [[ctx-sym extra] & body]
  `(binding [service-logic/*default-llm-provider* nil]
     (let [~ctx-sym (create-test-context ~extra)]
       (try ~@body
            (finally (stop-test-context ~ctx-sym))))))

;; =============================================================================
;; Stub instruments
;; =============================================================================

(defn- recording-leaf-fn
  "Emitted-tree :code leaf. Records what its execution context carried into
   `leaf-record` (side channel) — the assertion reads THIS back, not the node
   return value. Returns a valid :writes map so the node itself succeeds."
  [leaf-record]
  (fn [ctx]
    (swap! leaf-record conj
           {:has-tool-context-key (contains? ctx :tool-context)
            :tool-context-val     (:tool-context ctx)
            :has-call-tool-fn-key (contains? ctx :call-tool-fn)
            :call-tool-fn-some?   (some? (:call-tool-fn ctx))})
    {:result-a "leaf-done"}))

(defn- make-stub-call-tool-fn
  "Stub :call-tool-fn modelling ADR-0018's 'gate lives inside call-tool-fn'.
   Records, per call, the tool + args + the :tool-context the caller handed it
   at the invocation point. Lives on the processor-registration context
   (mirrors orc-sessions' ::context already carrying :call-tool-fn)."
  [calls-atom]
  (fn [tool-name args tool-context]
    (swap! calls-atom conj {:tool tool-name
                            :args args
                            :gate-saw-tool-context tool-context})
    {:status :ok :stub true :tool tool-name}))

(defn- gated-tool-leaf-fn
  "Emitted-tree :code leaf that invokes the gated tool, handing it the opaque
   :tool-context read from its OWN execution context (the invocation point)."
  [leaf-record]
  (fn [ctx]
    (let [ctf (:call-tool-fn ctx)
          tc  (:tool-context ctx)
          call-result (when ctf (ctf "fs/write" {:path "probe.txt" :content "hi"} tc))]
      (swap! leaf-record conj {:invoked? (some? call-result)
                               :call-result call-result})
      {:result-a "leaf-done"})))


(deftest g1-s1-tool-context-on-execute-tree-arg-reaches-leaf
  (testing "an opaque :tool-context on the execute-tree context ARG reaches the emitted :code leaf fn's context"
    (let [leaf-record  (atom [])
          tool-context {:workspace "/ws" :marker :STUB-TOOL-CONTEXT}]
      (with-test-context [ctx {}]
        (let [exec-ctx (assoc ctx :tool-context tool-context)
              tree     (service-logic/rlm-dsl->orc-dsl
                         [:sequence
                          [:code {:fn (recording-leaf-fn leaf-record)
                                  :reads [] :writes [:result-a]
                                  :output-schemas {:result-a :string}}]
                          [:final {:keys [:result-a]}]])
              result   (tp-core/execute-tree tree exec-ctx
                         {:sandbox-vars {} :timeout-ms 20000})]
          (is (= :success (:status result))
              (str "tree should succeed; got " (:status result) " error " (:error result)))
          (is (= 1 (count @leaf-record)) "leaf fn should have run exactly once")
          (let [rec (first @leaf-record)]
            (is (:has-tool-context-key rec)
                "leaf context should CONTAIN :tool-context (RED before G1 threading)")
            (is (= tool-context (:tool-context-val rec))
                "leaf context's :tool-context should equal the RLM context's opaque tool-context")))))))


(deftest g1-s2-leaf-invokes-gated-tool-which-sees-tool-context
  (testing "the emitted :code leaf invokes a stub gated tool AND the stub sees the threaded :tool-context"
    (let [leaf-record  (atom [])
          tool-calls   (atom [])
          tool-context {:workspace "/ws" :marker :STUB-TOOL-CONTEXT}
          stub-ctf     (make-stub-call-tool-fn tool-calls)]
      (with-test-context [ctx {:call-tool-fn stub-ctf}]
        (let [exec-ctx (assoc ctx :tool-context tool-context)
              tree     (service-logic/rlm-dsl->orc-dsl
                         [:sequence
                          [:code {:fn (gated-tool-leaf-fn leaf-record)
                                  :reads [] :writes [:result-a]
                                  :output-schemas {:result-a :string}}]
                          [:final {:keys [:result-a]}]])
              result   (tp-core/execute-tree tree exec-ctx
                         {:sandbox-vars {} :timeout-ms 20000})]
          (is (= :success (:status result))
              (str "tree should succeed; got " (:status result) " error " (:error result)))
          (is (= 1 (count @tool-calls)) "the gated tool should have been invoked exactly once")
          (let [call (first @tool-calls)]
            (is (= "fs/write" (:tool call)) "the leaf invoked the fs/write gated tool")
            (is (= tool-context (:gate-saw-tool-context call))
                "the gate (inside call-tool-fn) saw the SAME threaded opaque :tool-context")))))))


(defn- nested-emitting-leaf-fn
  "Outer Phase-2 leaf that itself emits a nested tree — models a leaf/researcher
   spawning a child tick. It re-invokes execute-tree with its OWN execution
   context (which, per G1, now carries :tool-context), so the tool-context must
   re-thread down to the deeper leaf."
  [inner-leaf-record]
  (fn [ctx]
    (let [inner-tree (service-logic/rlm-dsl->orc-dsl
                       [:sequence
                        [:code {:fn (recording-leaf-fn inner-leaf-record)
                                :reads [] :writes [:inner-result]
                                :output-schemas {:inner-result :string}}]
                        [:final {:keys [:inner-result]}]])
          inner-ctx (:execution-context ctx)
          inner-result (tp-core/execute-tree inner-tree inner-ctx
                         {:sandbox-vars {} :timeout-ms 20000})]
      {:result-a {:inner-status (:status inner-result)}})))

(deftest g1-transitive-nested-tick-rethreads-tool-context
  (testing "a nested tick (outer leaf emits a tree) carries the opaque :tool-context to the DEEPER leaf"
    (let [inner-leaf-record (atom [])
          tool-context      {:workspace "/ws" :marker :STUB-TOOL-CONTEXT :depth :outer}]
      (with-test-context [ctx {}]
        (let [exec-ctx (assoc ctx :tool-context tool-context)
              outer-tree (service-logic/rlm-dsl->orc-dsl
                           [:sequence
                            [:code {:fn (nested-emitting-leaf-fn inner-leaf-record)
                                    :reads [] :writes [:result-a]
                                    :output-schemas
                                    {:result-a [:map [:inner-status :keyword]]}}]
                            [:final {:keys [:result-a]}]])
              result (tp-core/execute-tree outer-tree exec-ctx
                       {:sandbox-vars {} :timeout-ms 30000})]
          (is (= :success (:status result))
              (str "outer tree should succeed; got " (:status result) " error " (:error result)))
          (is (= 1 (count @inner-leaf-record)) "the deeper (inner) leaf fn should have run exactly once")
          (let [rec (first @inner-leaf-record)]
            (is (:has-tool-context-key rec)
                "the DEEPER leaf's context should CONTAIN the re-threaded :tool-context")
            (is (= tool-context (:tool-context-val rec))
                "the deeper leaf's :tool-context should equal the outer RLM context's opaque tool-context")))))))


(def ^:private catalog-value
  "STUB-CATALOG-XYZZY: fs/write {:path :content} -> {:status}")

(defn- capture-rlm-inputs
  "Drive the REAL execute-repl-researcher-rlm one iteration with llm/predict
   redef'd to CAPTURE the (module, inputs) handed to the model, then abort.
   This direct executor seam has no durable frontier, so it explicitly selects
   the non-checkpointed compatibility path. No real LLM, no ColBERT. Returns
   the captured inputs map (or nil)."
  [rlm-config]
  (let [captured (atom nil)
        node {:type :repl-researcher
              :name "ce2-researcher"
              :instruction "Do the coding task."
              :reads [:task-input]
              :writes [:answer]
              :max-iterations 1
              :rlm (assoc rlm-config :checkpointed? false)}
        blackboard {:task-input {:key :task-input :value "input-value" :version 0}}]
    (with-test-context [ctx {}]
      (with-redefs [llm/predict (fn [_provider module inputs _opts]
                                     (reset! captured {:module module :inputs inputs})
                                     (throw (ex-info "capture-abort" {})))]
        (try (tp-core/execute-repl-researcher-rlm node blackboard :probe-provider ctx)
             (catch Throwable _ nil))))
    @captured))

(deftest g2-available-code-nodes-value-reaches-rlm-inputs
  (testing "when :rlm configures :available-code-nodes, the VALUE is in the runtime inputs map"
    (let [{:keys [module inputs]} (capture-rlm-inputs {:recursive? true
                                                       :available-code-nodes catalog-value})]
      (is (some? inputs) "llm/predict should have been called (inputs captured)")
      (is (contains? inputs :available-code-nodes)
          "runtime inputs map should CONTAIN :available-code-nodes (RED before G2)")
      (is (= catalog-value (:available-code-nodes inputs))
          "the inputs :available-code-nodes should equal the configured catalog VALUE")
      (is (contains? (set (map :name (:inputs module))) :available-code-nodes)
          "module should declare the :available-code-nodes input field")))

  (testing "when :rlm does NOT configure :available-code-nodes, the inputs map omits it"
    (let [{:keys [inputs]} (capture-rlm-inputs {:recursive? true})]
      (is (some? inputs) "llm/predict should have been called (inputs captured)")
      (is (not (contains? inputs :available-code-nodes))
          "runtime inputs map should NOT contain :available-code-nodes when unconfigured"))))


(deftest ws5b-phase1-call-tool-fn-threads-tool-context
  (testing "with :tool-context present, the Phase-1 caller forwards it as the 3rd gate arg"
    (let [seen (atom nil)
          raw  (fn
                 ([tn args] (reset! seen {:arity 2 :tn tn :args args}) :two)
                 ([tn args tc] (reset! seen {:arity 3 :tn tn :args args :tc tc}) :three))
          tc   {:marker :STUB-TOOL-CONTEXT}
          ctf  (service-logic/phase1-call-tool-fn {:call-tool-fn raw :tool-context tc})]
      (is (= :three (ctf "apply_patch" {:patch "p"}))
          "the wrapped caller routed to the 3-arity gate arm")
      (is (= {:arity 3 :tn "apply_patch" :args {:patch "p"} :tc tc} @seen)
          "the gate saw the SAME tick tool-context the wrapper forwarded")))
  (testing "without :tool-context, the raw caller is returned UNCHANGED (backward-compatible)"
    (let [raw (fn ([tn args] :two))]
      (is (identical? raw (service-logic/phase1-call-tool-fn {:call-tool-fn raw}))
          "no tool-context -> no wrapping (non-coding hosts unaffected)")))
  (testing "without :call-tool-fn, returns nil (sandbox binds no tools)"
    (is (nil? (service-logic/phase1-call-tool-fn {:tool-context {:marker :X}})))))
