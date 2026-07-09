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
            [dscloj.core :as dscloj]
            [ai.obney.orc.orc-service.test-helpers]
            [ai.obney.orc.orc-service.core.rlm-tree-executor :as tree-executor]
            [ai.obney.orc.orc-service.core.rlm-dsl :as rlm-dsl]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.core.todo-processors :as tp-core]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]))

;; =============================================================================
;; Test context (mirrors rlm_tree_executor_test/create-test-context).
;; extra-base is merged into base-ctx BEFORE processors are registered, so the
;; test controls exactly what the async processor-registration context carries.
;; The KEY point of G1: stub keys go on the execute-tree ARG (not base-ctx) —
;; proving the arg's :tool-context is threaded end-to-end to the leaf.
;; =============================================================================

(defn- create-test-context [extra-base]
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/ce2-effector-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx (merge {:event-store event-store
                         :cache cache
                         :tenant-id tenant-id
                         :dscloj-provider nil
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
  `(binding [tp-core/*default-dscloj-provider* nil]
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

;; =============================================================================
;; G1 Cycle 1 — S1 leaf reachability
;; =============================================================================

(deftest g1-s1-tool-context-on-execute-tree-arg-reaches-leaf
  (testing "an opaque :tool-context on the execute-tree context ARG reaches the emitted :code leaf fn's context"
    (let [leaf-record  (atom [])
          tool-context {:workspace "/ws" :marker :STUB-TOOL-CONTEXT}]
      (with-test-context [ctx {}]
        ;; tool-context is placed ONLY on the execute-tree ARG (base-ctx is
        ;; clean). Before G1 threading the arg is dropped at the async boundary
        ;; and the leaf sees nil; after threading it rides the tick-execution
        ;; context to the leaf.
        (let [exec-ctx (assoc ctx :tool-context tool-context)
              tree     (rlm-dsl/rlm-dsl->orc-dsl
                         [:sequence
                          [:code {:fn (recording-leaf-fn leaf-record)
                                  :reads [] :writes [:result-a]}]
                          [:final {:keys [:result-a]}]])
              result   (tree-executor/execute-tree tree exec-ctx
                         {:sandbox-vars {} :timeout-ms 20000})]
          (is (= :success (:status result))
              (str "tree should succeed; got " (:status result) " error " (:error result)))
          (is (= 1 (count @leaf-record)) "leaf fn should have run exactly once")
          (let [rec (first @leaf-record)]
            (is (:has-tool-context-key rec)
                "leaf context should CONTAIN :tool-context (RED before G1 threading)")
            (is (= tool-context (:tool-context-val rec))
                "leaf context's :tool-context should equal the RLM context's opaque tool-context")))))))

;; =============================================================================
;; G1 Cycle 2 — S2 end-to-end: leaf invokes gated tool, gate sees tool-context
;; =============================================================================

(deftest g1-s2-leaf-invokes-gated-tool-which-sees-tool-context
  (testing "the emitted :code leaf invokes a stub gated tool AND the stub sees the threaded :tool-context"
    (let [leaf-record  (atom [])
          tool-calls   (atom [])
          tool-context {:workspace "/ws" :marker :STUB-TOOL-CONTEXT}
          stub-ctf     (make-stub-call-tool-fn tool-calls)]
      ;; :call-tool-fn lives on the processor-registration context (as it does
      ;; in orc-sessions' ::context — do NOT re-plumb it). :tool-context is on
      ;; the execute-tree ARG and must be threaded (G1) to the invocation point.
      (with-test-context [ctx {:call-tool-fn stub-ctf}]
        (let [exec-ctx (assoc ctx :tool-context tool-context)
              tree     (rlm-dsl/rlm-dsl->orc-dsl
                         [:sequence
                          [:code {:fn (gated-tool-leaf-fn leaf-record)
                                  :reads [] :writes [:result-a]}]
                          [:final {:keys [:result-a]}]])
              result   (tree-executor/execute-tree tree exec-ctx
                         {:sandbox-vars {} :timeout-ms 20000})]
          (is (= :success (:status result))
              (str "tree should succeed; got " (:status result) " error " (:error result)))
          ;; Read the stub-tool record BACK (not a node return value).
          (is (= 1 (count @tool-calls)) "the gated tool should have been invoked exactly once")
          (let [call (first @tool-calls)]
            (is (= "fs/write" (:tool call)) "the leaf invoked the fs/write gated tool")
            (is (= tool-context (:gate-saw-tool-context call))
                "the gate (inside call-tool-fn) saw the SAME threaded opaque :tool-context")))))))

;; =============================================================================
;; G1 Cycle 3 — transitive: a nested tick (leaf emits a tree) re-threads it
;; =============================================================================

(defn- nested-emitting-leaf-fn
  "Outer Phase-2 leaf that itself emits a nested tree — models a leaf/researcher
   spawning a child tick. It re-invokes execute-tree with its OWN execution
   context (which, per G1, now carries :tool-context), so the tool-context must
   re-thread down to the deeper leaf."
  [inner-leaf-record]
  (fn [ctx]
    (let [inner-tree (rlm-dsl/rlm-dsl->orc-dsl
                       [:sequence
                        [:code {:fn (recording-leaf-fn inner-leaf-record)
                                :reads [] :writes [:inner-result]}]
                        [:final {:keys [:inner-result]}]])
          ;; Pass the leaf's own execution-context down (the transitive seam).
          inner-ctx (:execution-context ctx)
          inner-result (tree-executor/execute-tree inner-tree inner-ctx
                         {:sandbox-vars {} :timeout-ms 20000})]
      {:result-a {:inner-status (:status inner-result)}})))

(deftest g1-transitive-nested-tick-rethreads-tool-context
  (testing "a nested tick (outer leaf emits a tree) carries the opaque :tool-context to the DEEPER leaf"
    (let [inner-leaf-record (atom [])
          tool-context      {:workspace "/ws" :marker :STUB-TOOL-CONTEXT :depth :outer}]
      (with-test-context [ctx {}]
        (let [exec-ctx (assoc ctx :tool-context tool-context)
              outer-tree (rlm-dsl/rlm-dsl->orc-dsl
                           [:sequence
                            [:code {:fn (nested-emitting-leaf-fn inner-leaf-record)
                                    :reads [] :writes [:result-a]}]
                            [:final {:keys [:result-a]}]])
              result (tree-executor/execute-tree outer-tree exec-ctx
                       {:sandbox-vars {} :timeout-ms 30000})]
          (is (= :success (:status result))
              (str "outer tree should succeed; got " (:status result) " error " (:error result)))
          (is (= 1 (count @inner-leaf-record)) "the deeper (inner) leaf fn should have run exactly once")
          (let [rec (first @inner-leaf-record)]
            (is (:has-tool-context-key rec)
                "the DEEPER leaf's context should CONTAIN the re-threaded :tool-context")
            (is (= tool-context (:tool-context-val rec))
                "the deeper leaf's :tool-context should equal the outer RLM context's opaque tool-context")))))))

;; =============================================================================
;; G2 Cycle 4 — :available-code-nodes VALUE reaches the RLM code-gen inputs
;; =============================================================================

(def ^:private catalog-value
  "STUB-CATALOG-XYZZY: fs/write {:path :content} -> {:status}")

(defn- capture-rlm-inputs
  "Drive the REAL execute-repl-researcher-rlm one iteration with dscloj/predict
   redef'd to CAPTURE the (module, inputs) handed to the model, then abort.
   No real LLM, no ColBERT. Returns the captured inputs map (or nil)."
  [rlm-config]
  (let [captured (atom nil)
        node {:type :repl-researcher
              :name "ce2-researcher"
              :instruction "Do the coding task."
              :reads [:task-input]
              :writes [:answer]
              :max-iterations 1
              :rlm rlm-config}
        blackboard {:task-input {:key :task-input :value "input-value" :version 0}}]
    (with-test-context [ctx {}]
      (with-redefs [dscloj/predict (fn [_provider module inputs _opts]
                                     (reset! captured {:module module :inputs inputs})
                                     (throw (ex-info "capture-abort" {})))]
        (try (executor/execute-repl-researcher-rlm node blackboard :probe-provider ctx)
             (catch Throwable _ nil))))
    @captured))

(deftest g2-available-code-nodes-value-reaches-rlm-inputs
  (testing "when :rlm configures :available-code-nodes, the VALUE is in the runtime inputs map"
    (let [{:keys [module inputs]} (capture-rlm-inputs {:recursive? true
                                                       :available-code-nodes catalog-value})]
      (is (some? inputs) "dscloj/predict should have been called (inputs captured)")
      (is (contains? inputs :available-code-nodes)
          "runtime inputs map should CONTAIN :available-code-nodes (RED before G2)")
      (is (= catalog-value (:available-code-nodes inputs))
          "the inputs :available-code-nodes should equal the configured catalog VALUE")
      ;; the module already declares the field + catalog note (pre-existing);
      ;; sanity-check the field is declared so value + declaration line up.
      (is (contains? (set (map :name (:inputs module))) :available-code-nodes)
          "module should declare the :available-code-nodes input field")))

  (testing "when :rlm does NOT configure :available-code-nodes, the inputs map omits it"
    (let [{:keys [inputs]} (capture-rlm-inputs {:recursive? true})]
      (is (some? inputs) "dscloj/predict should have been called (inputs captured)")
      (is (not (contains? inputs :available-code-nodes))
          "runtime inputs map should NOT contain :available-code-nodes when unconfigured"))))

;; =============================================================================
;; WS-5b — Phase-1 inline tool calls thread the tick :tool-context to the gate
;; =============================================================================
;;
;; The SCI sandbox invokes MCP tools via a two-arg (tool-name args) caller, so
;; the raw :call-tool-fn never sees the tick :tool-context — an inline (Phase-1)
;; mutate fail-closes on a missing tool-context, and a granted resume never
;; re-applies (the WS-5b FALSE SUCCESS). phase1-call-tool-fn wraps the raw
;; caller to FORWARD the tick :tool-context as the third arg — the same 3-arity
;; gate contract the Phase-2 :code leaf uses (make-stub-call-tool-fn above).

(deftest ws5b-phase1-call-tool-fn-threads-tool-context
  (testing "with :tool-context present, the Phase-1 caller forwards it as the 3rd gate arg"
    (let [seen (atom nil)
          raw  (fn
                 ([tn args] (reset! seen {:arity 2 :tn tn :args args}) :two)
                 ([tn args tc] (reset! seen {:arity 3 :tn tn :args args :tc tc}) :three))
          tc   {:marker :STUB-TOOL-CONTEXT}
          ctf  (executor/phase1-call-tool-fn {:call-tool-fn raw :tool-context tc})]
      ;; the sandbox still calls it two-arg; the wrapper adds the tool-context.
      (is (= :three (ctf "apply_patch" {:patch "p"}))
          "the wrapped caller routed to the 3-arity gate arm")
      (is (= {:arity 3 :tn "apply_patch" :args {:patch "p"} :tc tc} @seen)
          "the gate saw the SAME tick tool-context the wrapper forwarded")))
  (testing "without :tool-context, the raw caller is returned UNCHANGED (backward-compatible)"
    (let [raw (fn ([tn args] :two))]
      (is (identical? raw (executor/phase1-call-tool-fn {:call-tool-fn raw}))
          "no tool-context -> no wrapping (non-coding hosts unaffected)")))
  (testing "without :call-tool-fn, returns nil (sandbox binds no tools)"
    (is (nil? (executor/phase1-call-tool-fn {:tool-context {:marker :X}})))))
