(ns ai.obney.orc.orc-service.ce6c-dispatch-guard-test
  "CE-6c durable tests — final!+emit-tree! same-iteration dispatch guard
   (recursive mode only) + non-stream runtime execute :tool-context parity
   (ADR 0018).

   Root cause (live forensics): the model wrote
   (do (store! ...) (emit-tree! [...]) ... (final! {...})) in ONE iteration —
   it believed emit-tree! returns tree outputs synchronously. The RLM loop
   dispatch checks the final-output branch BEFORE the :generated-tree branch,
   so it accepted the final! and SILENTLY DISCARDED the emitted tree (zero
   Phase-2 executions, zero rlm-tree-execution-completed events).

   Fix under test (recursive mode ONLY): when one iteration produced BOTH a
   final-output AND a pending :generated-tree, neither is applied — both are
   cleared, an ITERATION ERROR is appended to the history so the model sees
   it next iteration, and the loop recurs. Terminal (non-recursive) mode
   keeps today's behavior (final! wins) — pinned here BEFORE the change.

   Harness: mock provider (the iteration-code injector), real Grain
   (in-memory event store, schema-validated commands -> events ->
   projections). Assertions read events/history BACK, never a bare return
   value alone."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [dscloj.core :as dscloj]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            ;; Loading interface.schemas registers the malli command schemas
            ;; (:sheet/create-sheet, :sheet/tick-tree, etc.) that the Phase-2
            ;; tree executor dispatches. Without this, Phase 2 silently aborts
            ;; before any tree events fire (see recursive_rlm_test's note).
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.core.executor :as executor]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as lmdb-store]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]))

;; =============================================================================
;; Test context (mirrors recursive_rlm_test / rlm-tree-executor-test pattern)
;; =============================================================================

(defn- create-test-context-with-provider []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/ce6c-dispatch-guard-test-" (random-uuid))
        cache (lmdb-store/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :dscloj-provider :openrouter
                  :event-pubsub ps
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
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
    (assoc base-ctx :processors processors)))

(defn- stop-test-context [ctx]
  (doseq [[_ processor] (:processors ctx)] (tp/stop processor))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [cache (:cache ctx)] (lmdb-store/stop cache))
  (when-let [event-store (:event-store ctx)] (es/stop event-store))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [child (.listFiles f)] (.delete child))
        (.delete f)))))

(defmacro with-test-ctx [[ctx-sym] & body]
  `(let [~ctx-sym (create-test-context-with-provider)]
     (try ~@body
          (finally (stop-test-context ~ctx-sym)))))

(defn- phase1-module?
  "Phase 1 (outer RLM) module has :outputs [{:name :code ...}]; Phase 2
   sub-LLM modules have :outputs named after their :writes."
  [module]
  (boolean (some #(= :code (:name %)) (:outputs module))))

(defn- events-of-type [ctx event-type]
  (->> (es/read (:event-store ctx)
                {:tenant-id (:tenant-id ctx) :types #{event-type}})
       (into [])))

;; The live killer's shape: store! + emit-tree! + final! in ONE code block.
(def ^:private both-in-one-iteration-code
  "(do (store! :note \"stored-before-tree\")
       (emit-tree!
         [:sequence
           [:llm {:instruction \"summarize\"
                  :reads [:document]
                  :writes [:summary]}]
           [:final {:keys [:summary]}]])
       (final! {:summary \"premature final\"}))")

;; =============================================================================
;; Cycle 1 — recursive mode: the guard fires. One iteration produces BOTH a
;; final-output and a pending :generated-tree -> the loop must NOT terminate
;; with that final, the tree must NOT execute, the next iteration's history
;; must carry the guard's ITERATION ERROR text, and a subsequent final!-alone
;; iteration terminates normally.
;;
;; RED before the guard: the dispatch's final-output branch is checked first,
;; so the premature final! is accepted after ONE iteration and the tree is
;; silently discarded (the live killer).
;; =============================================================================

(def ^:private guard-error-text
  "You called (final! ...) in the same iteration as (emit-tree! ...). The tree executes AFTER your code returns, so its outputs were not available to your final!. Neither was applied: the tree was not executed and the final! was discarded. Either call (emit-tree! ...) alone this iteration and inspect :tree-results next iteration, or call (final! ...) alone.")

(deftest cycle1-recursive-mode-guard-rejects-same-iteration-final-and-tree
  (testing "recursive mode: final! + emit-tree! in ONE iteration -> neither applied; guard error surfaces to the next iteration; final! alone then terminates"
    (with-test-ctx [ctx]
      (let [outer-call-count (atom 0)
            phase2-call-count (atom 0)
            history-snapshots (atom [])]
        (with-redefs [dscloj/predict
                      (fn [_provider module inputs _opts]
                        (if (phase1-module? module)
                          (let [n (swap! outer-call-count inc)]
                            (swap! history-snapshots conj (or (:history inputs) ""))
                            (if (= 1 n)
                              ;; Iter 1: the live killer — both in one block.
                              {:outputs {:code both-in-one-iteration-code}
                               :usage {:prompt_tokens 50 :completion_tokens 25 :total_tokens 75}}
                              ;; Iter 2: final! ALONE — must terminate the loop.
                              {:outputs {:code "(final! {:summary \"clean final after guard\"})"}
                               :usage {:prompt_tokens 60 :completion_tokens 30 :total_tokens 90}}))
                          (do (swap! phase2-call-count inc)
                              {:outputs {:summary "tree-produced summary"}
                               :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})))]
          (let [node {:type :repl-researcher
                      :instruction "Guard test"
                      :reads [:document]
                      :writes [:summary]
                      :rlm {:recursive? true}
                      :max-iterations 4}
                blackboard {:document {:key :document :schema :string
                                       :value "doc text" :version 1}}
                result (executor/execute-repl-researcher-rlm
                         node blackboard :openrouter ctx)]
            ;; The loop did NOT terminate with iteration 1's premature final!.
            (is (= 2 @outer-call-count)
                "TWO Phase-1 iterations: the guard recurs instead of accepting the premature final!")
            (is (= :success (:status result))
                (str "loop terminates on iteration 2's clean final!; got "
                     (:status result) " error " (:error result)))
            (is (= "clean final after guard" (get-in result [:outputs :summary]))
                "the returned final is iteration 2's — the premature final! was discarded")
            ;; The tree was NOT executed — no Phase-2 sub-LLM calls, and read
            ;; BACK from grain: no child tick, no Phase-2 bookend.
            (is (zero? @phase2-call-count)
                "the guarded tree was NOT executed (no Phase-2 sub-LLM calls)")
            (is (empty? (events-of-type ctx :sheet/tree-tick-started))
                "no child tree tick in the event store — the guarded tree never ran")
            (is (empty? (events-of-type ctx :sheet/rlm-tree-execution-completed))
                "no rlm-tree-execution-completed bookend in the event store")
            ;; The NEXT iteration's history/prompt carries the guard error.
            (is (str/includes? (nth @history-snapshots 1) guard-error-text)
                "iteration 2's history input carries the guard's ITERATION ERROR text verbatim")
            ;; The result's iteration history records the guard error on the
            ;; offending iteration (read back from the loop's own record).
            (is (some #(and (string? (:error %))
                            (str/includes? (:error %) guard-error-text))
                      (:iterations result))
                "the offending iteration's history entry carries the guard error")))))))

;; =============================================================================
;; Prompt line (CHANGE 1, forward guidance) — the recursive-mode prompt
;; section warns that emit-tree! does not return tree outputs in the same
;; iteration. Asserted through the REAL builder (no string copies), like the
;; ce6b advertisement tests. Terminal-mode prompts must NOT carry it (it
;; lives in the recursive-mode section CE-6b's region already gates).
;; =============================================================================

(def ^:private prompt-guard-line
  "emit-tree! does NOT return tree outputs in the same iteration — never call final! in the same code block as emit-tree!.")

(deftest recursive-prompt-warns-about-same-iteration-final
  (let [build-fn #'executor/build-rlm-code-generation-module
        base-node {:type :repl-researcher
                   :writes [:answer]
                   :instruction "Do the task."}]
    (testing "recursive-mode prompt carries the same-iteration warning line"
      (let [instructions (:instructions (build-fn (assoc base-node :rlm {:recursive? true})
                                                  "" [] {} {} {}))]
        (is (str/includes? instructions prompt-guard-line)
            "the recursive-mode section warns that emit-tree! outputs are not available to a same-block final!")))
    (testing "terminal-mode prompt does NOT carry it (the section is recursive-only)"
      (let [instructions (:instructions (build-fn (assoc base-node :rlm {:recursive? false})
                                                  "" [] {} {} {}))]
        (is (not (str/includes? instructions prompt-guard-line))
            "non-recursive prompts are unchanged")))))

;; =============================================================================
;; Cycle 3 (PIN, captured BEFORE the change) — terminal (non-recursive) mode
;; with BOTH final! and emit-tree! in one iteration keeps today's behavior:
;; the dispatch's final-output branch wins, the tree is not executed, and the
;; loop terminates on the first iteration. The CE-6c guard is scoped to
;; recursive mode ONLY and must not touch this.
;; =============================================================================

(deftest cycle3-terminal-mode-both-present-final-wins-unchanged
  (testing "non-recursive mode: final! + emit-tree! in one iteration -> final! wins, tree not executed (today's dispatch, pinned)"
    (with-test-ctx [ctx]
      (let [outer-call-count (atom 0)
            phase2-call-count (atom 0)]
        (with-redefs [dscloj/predict
                      (fn [_provider module _inputs _opts]
                        (if (phase1-module? module)
                          (do (swap! outer-call-count inc)
                              {:outputs {:code both-in-one-iteration-code}
                               :usage {:prompt_tokens 50 :completion_tokens 25 :total_tokens 75}})
                          (do (swap! phase2-call-count inc)
                              {:outputs {:summary "tree-produced summary"}
                               :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})))]
          (let [node {:type :repl-researcher
                      :instruction "Terminal-mode pin"
                      :reads [:document]
                      :writes [:summary]
                      :rlm {:recursive? false}
                      :max-iterations 3}
                blackboard {:document {:key :document :schema :string
                                       :value "doc text" :version 1}}
                result (executor/execute-repl-researcher-rlm
                         node blackboard :openrouter ctx)]
            (is (= :success (:status result))
                (str "terminal mode still terminates on the final!; got "
                     (:status result) " error " (:error result)))
            (is (= "premature final" (get-in result [:outputs :summary]))
                "terminal mode: the final!'s outputs are returned as today")
            (is (= 1 @outer-call-count)
                "terminal mode: exactly ONE Phase-1 iteration — final! accepted immediately")
            (is (zero? @phase2-call-count)
                "terminal mode: the emitted tree is NOT executed (no Phase-2 sub-LLM calls) — today's dispatch order, unchanged")
            ;; Read back from grain: no child tree tick was started, no
            ;; Phase-2 bookend recorded.
            (is (empty? (events-of-type ctx :sheet/tree-tick-started))
                "terminal mode: no child tree tick in the event store")
            (is (empty? (events-of-type ctx :sheet/rlm-tree-execution-completed))
                "terminal mode: no rlm-tree-execution-completed bookend in the event store")
            (is (= [:sequence
                    [:llm {:instruction "summarize"
                           :reads [:document]
                           :writes [:summary]}]
                    [:final {:keys [:summary]}]]
                   (:generated-tree-raw result))
                "terminal mode: the designed-but-unexecuted tree still surfaces on the result (today's final! branch behavior)")))))))

;; =============================================================================
;; Cycle 4 (CHANGE 2) — runtime.clj execute PARITY: the NON-stream entry's
;; root :sheet/tick-tree cond-> must carry :tool-context off the context,
;; exactly as CE-5b FIX A did for execute-stream. Mirrors ce5b's root-tick
;; assertion but through sheet/execute (runtime/execute).
;;
;; RED before the parity line: the root tree-tick-started event stores nil
;; :tool-context, so any non-stream consumer with mutate tools hits the ADR
;; 0018 fail-closed gate.
;; =============================================================================

(defn- setup-repl-researcher-sheet!
  "Real sheet: (sequence (repl-researcher)). Reads :question, writes
   :seen-marker. Terminal RLM mode so Phase 1 emits a tree and Phase 2
   auto-executes it (mirrors ce5b's harness)."
  [ctx]
  (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "CE-6c Parity Sheet"))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)]
    (doseq [k [:question :seen-marker]]
      (h/run-and-apply! ctx (h/make-declare-key-command sheet-id k :string)))
    (let [seq-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
          seq-id (-> seq-result :command-result/events first :node-id)
          node-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :repl-researcher :parent-id seq-id))
          node-id (-> node-result :command-result/events first :node-id)]
      (h/run-and-apply! ctx (h/make-set-repl-researcher-config-command
                              sheet-id node-id
                              "Record the tool-context you were handed."
                              [:question] [:seen-marker] []
                              :max-iterations 3
                              :rlm {:recursive? false}))
      {:sheet-id sheet-id :node-id node-id})))

;; Phase-2 :code leaf records the :marker of the :tool-context its OWN
;; execution context carried (nil if the threading dropped it).
(def ^:private emit-tree-recording-marker
  "(emit-tree!
     [:sequence
       [:code {:fn (fn [ctx] {:seen-marker (:marker (:tool-context ctx))})
               :reads []
               :writes [:seen-marker]}]
       [:final {:keys [:seen-marker]}]])")

(defn- mock-predict-emitting-recorder
  [_provider _module _inputs _opts]
  {:outputs {:code emit-tree-recording-marker}
   :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})

(defn- events-of-type* [ctx event-type]
  (->> (es/read (:event-store ctx)
                {:tenant-id (:tenant-id ctx) :types #{event-type}})
       (into [])))

(deftest cycle4-execute-carries-tool-context-onto-root-tick
  (testing "through runtime/execute (non-stream entry), a context :tool-context lands on the root :sheet/tick-tree / tree-tick-started event and reaches the Phase-2 leaf"
    (h/with-async-test-context [ctx]
      (with-redefs [dscloj/predict mock-predict-emitting-recorder]
        (let [marker       (str "CE6C-EXECUTE-MARKER-" (random-uuid))
              tool-context {:marker marker :workspace "/ws"}
              {:keys [sheet-id]} (setup-repl-researcher-sheet! ctx)
              root-tick-id (random-uuid)
              result (sheet/execute (assoc ctx :tool-context tool-context)
                                    sheet-id {:question "go"}
                                    :timeout-ms 30000
                                    :tick-id root-tick-id)]
          (is (= :success (:status result))
              (str "the non-stream turn should succeed; got " (:status result)
                   " error " (:error result)))
          ;; Root-tick assertion (mirrors ce5b cycle 2 (a), through execute):
          ;; FIX-A-parity stored :tool-context on the ROOT tick's event.
          (let [tick-starts (events-of-type* ctx :sheet/tree-tick-started)
                root-start  (first (filter #(= root-tick-id (:tick-id %)) tick-starts))]
            (is (some? root-start)
                "a tree-tick-started event exists for the supplied root tick-id")
            (is (= tool-context (:tool-context root-start))
                "the ROOT tree-tick-started carries the context's :tool-context (parity with execute-stream)"))
          ;; And it threads onward: the Phase-2 leaf wrote the marker it saw,
          ;; read BACK from :sheet/execution-value-written.
          (let [markers (->> (events-of-type* ctx :sheet/execution-value-written)
                             (filter #(= :seen-marker (:key %)))
                             (map :value)
                             set)]
            (is (contains? markers marker)
                (str "the Phase-2 :code leaf wrote the :tool-context marker it saw; "
                     "saw markers: " (pr-str markers)))))))))

(deftest cycle4b-execute-without-tool-context-is-unchanged
  (testing "absent :tool-context -> the root command/event is unchanged (cond-> skips the clause)"
    (h/with-async-test-context [ctx]
      (with-redefs [dscloj/predict mock-predict-emitting-recorder]
        (let [{:keys [sheet-id]} (setup-repl-researcher-sheet! ctx)
              root-tick-id (random-uuid)
              result (sheet/execute ctx sheet-id {:question "go"}
                                    :timeout-ms 30000
                                    :tick-id root-tick-id)]
          (is (= :success (:status result))
              (str "the turn still succeeds without :tool-context; got "
                   (:status result) " error " (:error result)))
          (let [tick-starts (events-of-type* ctx :sheet/tree-tick-started)
                root-start  (first (filter #(= root-tick-id (:tick-id %)) tick-starts))]
            (is (some? root-start) "root tree-tick-started event exists")
            (is (nil? (:tool-context root-start))
                "no :tool-context stored when absent — command shape unchanged")))))))
