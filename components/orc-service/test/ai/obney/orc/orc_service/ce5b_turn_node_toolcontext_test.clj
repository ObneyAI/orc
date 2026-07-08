(ns ai.obney.orc.orc-service.ce5b-turn-node-toolcontext-test
  "CE-5b durable tests — engine turn->node tool-context propagation (ADR 0018).

   The hop CE-2's test did NOT cover: a REAL implement turn delivering the
   turn's opaque :tool-context all the way from the execute-stream context arg,
   through the root :sheet/tick-tree command, into the repl-researcher node
   executor, into execute-tree's Phase-2 child tick, and finally onto the
   emitted :code leaf's execution context.

   Root cause (CE-5b): two engine drops on that specific hop —
     DROP A  execute-stream's root :sheet/tick-tree command cond-> never
             copied :tool-context off its context, so the root tick's
             tree-tick-started event (and the tick-execution-context read
             model) stored nil.
     DROP B  the repl-researcher node executor's enriched-context never read
             :tool-context from the tick read model, so execute-tree's context
             arg carried none.

   These tests drive the REAL path (sheet/execute-stream -> :sheet/tick-tree ->
   repl-researcher node executor -> execute-repl-researcher -> execute-tree ->
   :code leaf) with a mock provider + real Grain (in-memory event store,
   schema-validated commands -> events -> projections). The assertion reads the
   leaf's WRITE (the :tool-context marker it saw) BACK out of the event store
   (:sheet/execution-value-written), never a bare return value. :tool-context is
   an opaque STUB instrument; the engine threading is the system under test."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.core.async :as async]
            [dscloj.core :as dscloj]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.core.streaming :as streaming]
            ;; Loading interface.schemas registers the malli command schemas
            ;; (:sheet/create-sheet, :sheet/tick-tree, ...) that the Phase-2
            ;; tree executor dispatches. Without this, Phase 2 silently aborts
            ;; before any tree events fire (see recursive_rlm_test's note).
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.grain.event-store-v3.interface :as es]))

;; execute-stream self-subscribes to the streaming hub; reset between tests so
;; no subscription/lineage leaks across cases (mirrors streaming_test).
(use-fixtures :each
  (fn [f]
    (streaming/reset-all!)
    (try (f) (finally (streaming/reset-all!)))))

;; =============================================================================
;; Harness: a real root sheet whose root is a repl-researcher node.
;; =============================================================================

(defn- setup-repl-researcher-sheet!
  "Build a real sheet: (sequence (repl-researcher)). The researcher reads
   :question and writes :seen-marker. RLM mode so Phase 1 emits a tree and
   Phase 2 auto-executes it."
  [ctx]
  (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name "CE-5b Turn Sheet"))
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

;; The mock Phase-1 code-gen: emit a Phase-2 tree whose :code leaf records the
;; :marker of the :tool-context its OWN execution context carried, writing it to
;; :seen-marker. If :tool-context never reaches the leaf, (:marker nil) -> nil.
(def ^:private emit-tree-recording-marker
  "(emit-tree!
     [:sequence
       [:code {:fn (fn [ctx] {:seen-marker (:marker (:tool-context ctx))})
               :reads []
               :writes [:seen-marker]}]
       [:final {:keys [:seen-marker]}]])")

(defn- mock-predict-emitting-recorder
  "dscloj/predict stub: always returns the recording emit-tree! code (Phase 2's
   :code leaf needs no sub-LLM, so Phase 1 is the only predict call)."
  [_provider _module _inputs _opts]
  {:outputs {:code emit-tree-recording-marker}
   :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})

(defn- value-written-markers
  "Read the leaf's WRITE back out of grain: every :sheet/execution-value-written
   event's value for key :seen-marker, tenant-wide (across root + child ticks)."
  [ctx]
  (->> (es/read (:event-store ctx)
                {:tenant-id (:tenant-id ctx)
                 :types #{:sheet/execution-value-written}})
       (into [])
       (filter #(= :seen-marker (:key %)))
       (map :value)))

(defn- drain-close!
  "Consume + close a stream so no subscription lingers."
  [{:keys [events-ch close!]}]
  (when events-ch
    (let [deadline (+ (System/currentTimeMillis) 3000)]
      (loop []
        (when (< (System/currentTimeMillis) deadline)
          (let [[v _] (async/alts!! [events-ch (async/timeout 500)])]
            (when (some? v) (recur)))))))
  (when close! (close!)))

;; =============================================================================
;; Cycle 1 — Turn -> node -> leaf delivery (the core)
;; =============================================================================

(deftest cycle1-turn-tool-context-reaches-phase2-leaf
  (testing "a real turn's :tool-context (on the execute-stream context) reaches the emitted :code leaf, read back from the event store"
    (h/with-async-test-context [ctx]
      (with-redefs [dscloj/predict mock-predict-emitting-recorder]
        (let [marker       (str "CE5B-TURN-MARKER-" (random-uuid))
              tool-context {:marker marker :workspace "/ws"}
              {:keys [sheet-id]} (setup-repl-researcher-sheet! ctx)
              ;; DROP A lives in execute-stream: :tool-context is placed ONLY on
              ;; the execute-stream context arg (never as an explicit command
              ;; field), so FIX A must copy it onto the root tick command.
              stream       (sheet/execute-stream (assoc ctx :tool-context tool-context)
                                                 sheet-id {:question "go"}
                                                 :timeout-ms 30000)
              result       (deref (:result stream) 30000 ::timeout)]
          (drain-close! stream)
          (is (not= ::timeout result) "the turn should complete within the timeout")
          (is (= :success (:status result))
              (str "turn should succeed; got " (:status result) " error " (:error result)))
          ;; READ BACK from grain: the leaf wrote the marker it saw on its
          ;; :tool-context. RED before FIX A+B (leaf sees nil -> no marker).
          (let [markers (set (value-written-markers ctx))]
            (is (contains? markers marker)
                (str "the Phase-2 :code leaf should have written the turn's "
                     ":tool-context marker (read back from :sheet/execution-value-written); "
                     "saw markers: " (pr-str markers)))))))))

;; =============================================================================
;; Cycle 2 — PROVE the tick lines up (no assumption)
;;
;; The repl-researcher (FIX B) must read :tool-context from the SAME tick FIX A
;; stored it to. Prove it from the event store, not by assumption:
;;   (a) the ROOT :sheet/tree-tick-started event (tick-id == the execute-stream
;;       root tick) carries the turn's :tool-context — FIX A stored it there.
;;   (b) the repl-researcher's :sheet/node-execution-started event fires in that
;;       SAME root tick — so FIX B's (rm/get-tick-execution-context tick-id) reads
;;       the tick where (a) stored it.
;;   (c) a distinct CHILD :sheet/tree-tick-started (the Phase-2 execute-tree tick)
;;       also carries the same :tool-context — proving it threaded onward to the
;;       tick the leaf reads from.
;; =============================================================================

(defn- events-of-type [ctx event-type]
  (->> (es/read (:event-store ctx)
                {:tenant-id (:tenant-id ctx) :types #{event-type}})
       (into [])))

(deftest cycle2-tick-lines-up-repl-researcher-reads-root-tick
  (testing "the repl-researcher reads :tool-context from the SAME tick FIX A stored it to (proven via event store)"
    (h/with-async-test-context [ctx]
      (with-redefs [dscloj/predict mock-predict-emitting-recorder]
        (let [marker       (str "CE5B-TICK-MARKER-" (random-uuid))
              tool-context {:marker marker :workspace "/ws"}
              {:keys [sheet-id node-id]} (setup-repl-researcher-sheet! ctx)
              stream       (sheet/execute-stream (assoc ctx :tool-context tool-context)
                                                 sheet-id {:question "go"}
                                                 :timeout-ms 30000)
              root-tick-id (:tick-id stream)
              result       (deref (:result stream) 30000 ::timeout)]
          (drain-close! stream)
          (is (= :success (:status result))
              (str "turn should succeed; got " (:status result) " error " (:error result)))
          (let [tick-starts (events-of-type ctx :sheet/tree-tick-started)
                root-start  (first (filter #(= root-tick-id (:tick-id %)) tick-starts))
                node-starts (events-of-type ctx :sheet/node-execution-started)
                rr-start    (first (filter #(= node-id (:node-id %)) node-starts))
                child-starts (filter #(and (not= root-tick-id (:tick-id %))
                                           (= tool-context (:tool-context %)))
                                     tick-starts)]
            ;; (a) FIX A stored it on the ROOT tick
            (is (some? root-start) "a tree-tick-started event exists for the execute-stream root tick")
            (is (= tool-context (:tool-context root-start))
                "the ROOT tick-tree-started carries the turn's :tool-context (FIX A)")
            ;; (b) the repl-researcher ran in that SAME root tick
            (is (some? rr-start) "the repl-researcher node emitted a node-execution-started event")
            (is (= root-tick-id (:tick-id rr-start))
                (str "the repl-researcher's node-execution-started is in the ROOT tick "
                     "(so FIX B's rm/get-tick-execution-context reads the SAME tick FIX A stored to); "
                     "root=" root-tick-id " rr-tick=" (:tick-id rr-start)))
            ;; (c) it threaded onward to a distinct Phase-2 child tick
            (is (seq child-starts)
                "a distinct CHILD tick-tree-started also carries the same :tool-context (threaded onward to the leaf's tick)")))))))

;; =============================================================================
;; Cycle 3 — Backward-compat: absent :tool-context changes nothing
;; =============================================================================

(deftest cycle3-absent-tool-context-is-backward-compatible
  (testing "a turn WITHOUT :tool-context succeeds identically; root command + enriched-context stay unchanged (no :tool-context stored, leaf sees nil)"
    (h/with-async-test-context [ctx]
      (with-redefs [dscloj/predict mock-predict-emitting-recorder]
        (let [{:keys [sheet-id]} (setup-repl-researcher-sheet! ctx)
              ;; No :tool-context on the execute-stream context.
              stream       (sheet/execute-stream ctx sheet-id {:question "go"} :timeout-ms 30000)
              root-tick-id (:tick-id stream)
              result       (deref (:result stream) 30000 ::timeout)]
          (drain-close! stream)
          (is (= :success (:status result))
              (str "the non-coding turn still succeeds; got " (:status result) " error " (:error result)))
          (let [tick-starts (events-of-type ctx :sheet/tree-tick-started)
                root-start  (first (filter #(= root-tick-id (:tick-id %)) tick-starts))
                markers     (value-written-markers ctx)]
            (is (some? root-start) "root tick-tree-started event exists")
            (is (nil? (:tool-context root-start))
                "no :tool-context stored on the root command/event when absent (cond-> skips it)")
            (is (not-any? #(and (string? %) (re-find #"^CE5B-" %)) markers)
                (str "the leaf saw nil :tool-context -> wrote nil marker (no behavior change); "
                     "markers: " (pr-str (set markers))))))))))
