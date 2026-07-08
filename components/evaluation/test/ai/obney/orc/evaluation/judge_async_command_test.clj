(ns ai.obney.orc.evaluation.judge-async-command-test
  "TDD for the async + command-only judge-emission refactor
   (handoff: ORC-judge-runtime-async-command).

   Covers the DETERMINISTIC surface:
     1. The two new command handlers — validation, idempotency on the
        identity tuple, and emitted event shape.
     2. The non-blocking-handler contract: the judge processor returns a
        `:result/effect` (NOT `:result/events`) and does NOT deref a slow
        judge on the handler thread.
     3. The bug-is-gone proof: a SLOW judge over a BURST of node-
        completions does not stall the processor; all scores eventually
        emit via the command path; checkpoints advance; a post-burst
        completion is still judged. Analogous to grain's
        poller_burst_stall_test.

   No mock data in the contract sense — these use the REAL in-memory grain
   store + the REAL command processor + the REAL judge runtime. The only
   thing stubbed is wall-clock latency (a deliberately slow judge) and the
   LLM call for the burst (heuristic-structural is deterministic and needs
   no LLM); the separate live suite exercises real OpenRouter."
  (:require [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.judge-runtime :as jr]
            [ai.obney.orc.evaluation.core.commands]
            [ai.obney.orc.evaluation.core.heuristic-structural :as heuristic-structural]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.core.commands]
            [ai.obney.orc.orc-service.core.read-models]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Test context (mirrors judge_runtime_test, with per-processor names so the
;; effect path's per-event checkpoint stream is keyed per processor)
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/judge-async-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :event-pubsub ps
                  :dscloj-provider :openrouter
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  ::cache-dir cache-dir}
        processors (reduce-kv
                     (fn [acc proc-name {:keys [handler-fn topics]}]
                       (assoc acc proc-name
                              ;; Only the evaluation judge processors take a
                              ;; :processor-name (they use the effect path,
                              ;; which checkpoints per-event — a distinct name
                              ;; keeps their replay-guard watermarks separate).
                              ;; Naming the pure-path processors would switch
                              ;; them into the checkpointed branch, whose
                              ;; monotonic replay-guard false-positives under
                              ;; the concurrent sub-ticks custom-judge
                              ;; orc/execute fans out.
                              (tp/start (cond-> {:event-pubsub ps :topics topics
                                                 :handler-fn handler-fn :context base-ctx}
                                          (= "evaluation" (namespace proc-name))
                                          (assoc :processor-name proc-name)))))
                     {} @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defn- stop-context [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [es (:event-store ctx)] (es/stop es))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)]
     (try ~@body (finally (stop-context ~sym)))))

(defn- score-events [ctx]
  (into [] (es/read (:event-store ctx)
                    {:types #{:judge/score-emitted} :tenant-id (:tenant-id ctx)})))

(defn- composite-events [ctx]
  (into [] (es/read (:event-store ctx)
                    {:types #{:judge/composite-score-computed} :tenant-id (:tenant-id ctx)})))

(defn- checkpoint-events [ctx]
  (into [] (es/read (:event-store ctx)
                    {:types #{:grain/todo-processor-checkpoint} :tenant-id (:tenant-id ctx)})))

(defn- set-living-description-enabled! [ctx enabled?]
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/set-living-description-enabled
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :enabled? enabled?}))
  (Thread/sleep 100))

(defn- wait-until
  "Poll `f` until it returns truthy or `timeout-ms` elapses. Returns the
   last value f produced."
  [timeout-ms f]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [v (f)]
        (if (or v (>= (System/currentTimeMillis) deadline))
          v
          (do (Thread/sleep 25) (recur)))))))

(defn- record-judge-score!
  "Dispatch :evaluation/record-judge-score and return the command result."
  [ctx {:keys [sheet-id node-id tick-id judge-name judge-config score feedback dimensions]
        :or {judge-config {:type :grounding} score 0.8 feedback "ok" dimensions []}}]
  (cp/process-command
    (assoc ctx :command
           {:command/name :evaluation/record-judge-score
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :sheet-id sheet-id :node-id node-id :tick-id tick-id
            :judge-name judge-name :judge-config judge-config
            :score score :feedback feedback :dimensions dimensions})))

(defn- record-composite-score!
  [ctx {:keys [sheet-id node-id tick-id composite-score contributing-judges]
        :or {composite-score 0.7
             contributing-judges [{:judge-name "g" :score 0.8 :weight 0.5}
                                  {:judge-name "r" :score 0.6 :weight 0.5}]}}]
  (cp/process-command
    (assoc ctx :command
           {:command/name :evaluation/record-composite-score
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :sheet-id sheet-id :node-id node-id :tick-id tick-id
            :composite-score composite-score
            :contributing-judges contributing-judges})))

;; =============================================================================
;; 1. record-judge-score — emits the SAME :judge/score-emitted shape
;; =============================================================================

(deftest record-judge-score-emits-canonical-shape
  (testing "record-judge-score emits a :judge/score-emitted whose body validates against the registered schema and carries the identity tuple"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid) node-id (random-uuid) tick-id (random-uuid)
            r (record-judge-score! ctx {:sheet-id sheet-id :node-id node-id :tick-id tick-id
                                        :judge-name "grounding" :judge-config {:type :grounding}
                                        :score 0.85 :feedback "well grounded"
                                        :dimensions [{:name "Source" :weight 1.0 :score 0.9 :feedback "ok"}]})]
        (is (nil? (:cognitect.anomalies/category r))
            (str "command must succeed. Got: " (pr-str r)))
        (Thread/sleep 150)
        (let [evts (score-events ctx)
              schema (get @schema-util/registry* :judge/score-emitted)]
          (is (= 1 (count evts)) "exactly one score event emitted")
          (let [e (first evts)]
            (is (m/validate schema (select-keys e [:sheet-id :tick-id :node-id :judge-name
                                                   :judge-config :score :feedback :dimensions
                                                   :emitted-at]))
                (str "emitted body must validate against the unchanged schema. Got: "
                     (pr-str (m/explain schema e))))
            (is (and (= sheet-id (:sheet-id e)) (= node-id (:node-id e)) (= tick-id (:tick-id e))))
            (is (= "grounding" (:judge-name e)))
            (is (= 0.85 (:score e)))
            (is (string? (:emitted-at e)) "command stamps :emitted-at when omitted")))))))

;; =============================================================================
;; 2. record-judge-score — IDEMPOTENT on [sheet node tick judge-name]
;; =============================================================================

(deftest record-judge-score-idempotent-on-identity-tuple
  (testing "the SAME [sheet node tick judge-name] recorded twice → exactly one :judge/score-emitted (no double-emit)"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid) node-id (random-uuid) tick-id (random-uuid)
            args {:sheet-id sheet-id :node-id node-id :tick-id tick-id
                  :judge-name "grounding" :judge-config {:type :grounding} :score 0.8}]
        (record-judge-score! ctx args)
        (Thread/sleep 150)
        (record-judge-score! ctx (assoc args :score 0.2 :feedback "different payload"))
        (Thread/sleep 150)
        (let [evts (filter #(= tick-id (:tick-id %)) (score-events ctx))]
          (is (= 1 (count evts))
              (str "duplicate identity tuple must be a no-op. Got: " (count evts)))
          (is (= 0.8 (:score (first evts)))
              "the first write wins; the duplicate does not overwrite or append"))))))

(deftest record-judge-score-distinct-judge-names-both-emit
  (testing "same [sheet node tick] but DIFFERENT judge-name → two events (judge-name is part of the identity)"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid) node-id (random-uuid) tick-id (random-uuid)
            base {:sheet-id sheet-id :node-id node-id :tick-id tick-id}]
        (record-judge-score! ctx (assoc base :judge-name "grounding" :judge-config {:type :grounding}))
        (record-judge-score! ctx (assoc base :judge-name "reasoning" :judge-config {:type :reasoning}))
        (Thread/sleep 200)
        (let [evts (filter #(= tick-id (:tick-id %)) (score-events ctx))]
          (is (= 2 (count evts)) "two distinct judge-names → two events")
          (is (= #{"grounding" "reasoning"} (set (map :judge-name evts)))))))))

;; =============================================================================
;; 3. record-composite-score — shape + idempotency on [sheet node tick]
;; =============================================================================

(deftest record-composite-score-emits-and-is-idempotent
  (testing "record-composite-score emits a valid :judge/composite-score-computed and is idempotent on [sheet node tick]"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid) node-id (random-uuid) tick-id (random-uuid)
            args {:sheet-id sheet-id :node-id node-id :tick-id tick-id :composite-score 0.71}]
        (record-composite-score! ctx args)
        (Thread/sleep 150)
        (record-composite-score! ctx (assoc args :composite-score 0.99))
        (Thread/sleep 150)
        (let [evts (filter #(= tick-id (:tick-id %)) (composite-events ctx))
              schema (get @schema-util/registry* :judge/composite-score-computed)]
          (is (= 1 (count evts)) "idempotent on [sheet node tick] — one composite event")
          (is (= 0.71 (:composite-score (first evts))) "first write wins")
          (is (m/validate schema (select-keys (first evts)
                                              [:sheet-id :tick-id :node-id :composite-score
                                               :contributing-judges :emitted-at]))
              "composite body validates against the unchanged schema"))))))

;; =============================================================================
;; 4. Command validation — malformed bodies are rejected by the command schema
;; =============================================================================

(deftest record-judge-score-rejects-out-of-range-score
  (testing "a score outside [0,1] is rejected by the command schema (no event emitted)"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid) node-id (random-uuid) tick-id (random-uuid)
            r (cp/process-command
                (assoc ctx :command
                       {:command/name :evaluation/record-judge-score
                        :command/id (random-uuid) :command/timestamp (time/now)
                        :sheet-id sheet-id :node-id node-id :tick-id tick-id
                        :judge-name "grounding" :judge-config {:type :grounding}
                        :score 1.5 :feedback "bad" :dimensions []}))]
        (is (= :cognitect.anomalies/incorrect (:cognitect.anomalies/category r))
            (str "score > 1.0 must fail validation. Got: " (pr-str r)))
        (Thread/sleep 100)
        (is (= 0 (count (filter #(= tick-id (:tick-id %)) (score-events ctx))))
            "no event emitted for an invalid command")))))

(deftest record-judge-score-rejects-missing-judge-name
  (testing "a missing :judge-name is rejected by the command schema"
    (with-test-ctx [ctx]
      (let [r (cp/process-command
                (assoc ctx :command
                       {:command/name :evaluation/record-judge-score
                        :command/id (random-uuid) :command/timestamp (time/now)
                        :sheet-id (random-uuid) :node-id (random-uuid) :tick-id (random-uuid)
                        :judge-config {:type :grounding}
                        :score 0.5 :feedback "x" :dimensions []}))]
        (is (= :cognitect.anomalies/incorrect (:cognitect.anomalies/category r))
            (str "missing :judge-name must fail validation. Got: " (pr-str r)))))))

;; =============================================================================
;; 5. Non-blocking-handler contract: the handler returns a :result/effect
;;    immediately and does NOT deref the (slow) judge on the handler thread.
;; =============================================================================

(deftest handler-returns-effect-not-events-and-never-blocks
  (testing "even with a deliberately SLOW judge, on-node-execution-completed returns a :result/effect map fast (no deref on the handler thread, no :result/events)"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      ;; A judge that would take 3s if the handler deref'd it inline.
      (with-redefs [heuristic-structural/evaluate-tree-structure
                    (fn [_tree] (Thread/sleep 3000) {:score 0.5 :feedback "slow" :dimensions []})]
        (let [sheet-id (random-uuid) node-id (random-uuid) tick-id (random-uuid)
              ;; Build the handler context by hand: a node with the
              ;; heuristic-structural judge effectively resolved. We
              ;; redefine the resolver to return one heuristic judge so
              ;; the handler reaches the effect branch deterministically.
              event {:event/id (random-uuid)
                     :event/type :sheet/node-execution-completed
                     :sheet-id sheet-id :node-id node-id :tick-id tick-id
                     :status :success
                     :writes {:generated-tree-raw [:sequence [:llm {}] [:final {}]]}}
              handler-ctx (assoc ctx :event event)]
          (with-redefs [jr/get-effective-judges-for-node
                        (fn [& _] [{:judge-name "structure" :judge-config {:type :heuristic-structural}}])]
            (let [t0 (System/currentTimeMillis)
                  result (#'ai.obney.orc.evaluation.core.judge-runtime/on-node-execution-completed
                          handler-ctx)
                  elapsed (- (System/currentTimeMillis) t0)]
              (is (< elapsed 1000)
                  (str "handler must return WELL before the 3s judge would finish "
                       "(proves no inline deref). Elapsed: " elapsed "ms"))
              (is (fn? (:result/effect result))
                  "handler returns a :result/effect function")
              (is (= :after (:result/checkpoint result))
                  "effect is checkpointed :after (per-event try/catch + skip-checkpoint path)")
              (is (nil? (:result/events result))
                  "handler must NOT return :result/events — emission goes through commands now")
              ;; Running the effect spawns a background future and ALSO
              ;; returns immediately (it must not block on the slow judge).
              (let [t1 (System/currentTimeMillis)
                    _ ((:result/effect result))
                    effect-elapsed (- (System/currentTimeMillis) t1)]
                (is (< effect-elapsed 1000)
                    (str "the effect itself must return immediately (spawns a future). "
                         "Elapsed: " effect-elapsed "ms")))
              ;; Eventually the slow judge's score lands via the command path.
              (is (wait-until 8000
                              #(seq (filter (fn [e] (= tick-id (:tick-id e))) (score-events ctx))))
                  "the slow judge's score eventually appears via :evaluation/record-judge-score"))))))))

;; =============================================================================
;; 6. BURST stall proof (analogous to grain's poller_burst_stall_test):
;;    a slow judge over MANY node-completions does not stall the processor;
;;    every score eventually emits via commands; checkpoints advance; and a
;;    completion fired AFTER the burst is still judged.
;; =============================================================================

(deftest slow-judge-burst-does-not-stall-all-scores-emit-and-post-burst-judged
  (testing "a slow judge over a burst of N node-completions: no stall, all N scores emit via the command path, checkpoints advance, and a post-burst completion is still judged"
    (with-test-ctx [ctx]
      (set-living-description-enabled! ctx true)
      (let [n 12
            ;; Slow but bounded judge (300ms). The OLD design deref'd this
            ;; inline on the coalesced poller thread → a burst serialized
            ;; into a single batch that, on any throw, lost the whole batch
            ;; and re-looped forever. The async design runs each off-thread.
            slow-judge (fn [_tree] (Thread/sleep 300) {:score 0.5 :feedback "slow-burst" :dimensions []})]
        (with-redefs [heuristic-structural/evaluate-tree-structure slow-judge]
          ;; One shared sheet + node with a heuristic-structural judge;
          ;; the burst is N distinct ticks (distinct completions).
          (let [sr (cp/process-command (assoc ctx :command
                                              {:command/name :sheet/create-sheet
                                               :command/id (random-uuid) :command/timestamp (time/now)
                                               :name (str "burst-" (random-uuid))}))
                sheet-id (-> sr :command-result/events first :sheet-id)
                _ (cp/process-command (assoc ctx :command
                                             {:command/name :sheet/declare-judge
                                              :command/id (random-uuid) :command/timestamp (time/now)
                                              :sheet-id sheet-id :judge-name "structure"
                                              :judge-config {:type :heuristic-structural}}))
                nr (cp/process-command (assoc ctx :command
                                              {:command/name :sheet/create-node
                                               :command/id (random-uuid) :command/timestamp (time/now)
                                               :sheet-id sheet-id :type :leaf}))
                node-id (-> nr :command-result/events first :node-id)
                _ (cp/process-command (assoc ctx :command
                                             {:command/name :sheet/set-node-judges
                                              :command/id (random-uuid) :command/timestamp (time/now)
                                              :sheet-id sheet-id :node-id node-id :judges ["structure"]}))
                _ (Thread/sleep 150)
                burst-ticks (vec (repeatedly n random-uuid))
                t0 (System/currentTimeMillis)]
            ;; Fire the burst back-to-back (no waiting between completions).
            (doseq [tick burst-ticks]
              (cp/process-command (assoc ctx :command
                                         {:command/name :sheet/complete-node-execution
                                          :command/id (random-uuid) :command/timestamp (time/now)
                                          :sheet-id sheet-id :tick-id tick :node-id node-id
                                          :node-type :llm :status :success
                                          :writes {:generated-tree-raw [:sequence [:llm {}] [:final {}]]}
                                          :duration-ms 1})))
            (let [enqueue-elapsed (- (System/currentTimeMillis) t0)]
              (is (< enqueue-elapsed 2000)
                  (str "firing the burst must NOT block on the slow judges "
                       "(handlers return effects immediately). Elapsed: " enqueue-elapsed "ms")))
            ;; All N scores must eventually land via the command path.
            (let [landed (wait-until 20000
                                     #(let [ticks (set (map :tick-id (score-events ctx)))]
                                        (when (every? ticks burst-ticks) ticks)))]
              (is landed
                  (str "all " n " burst scores must eventually emit via commands — no stall. "
                       "Got ticks: " (count (set (map :tick-id (score-events ctx)))) "/" n)))
            (is (= n (count (filter #(contains? (set burst-ticks) (:tick-id %)) (score-events ctx))))
                "exactly one score event per burst tick (idempotent, no duplicates)")
            ;; Checkpoints advanced (the processor kept making progress; it
            ;; did not wedge on a single jammed batch).
            (is (pos? (count (checkpoint-events ctx)))
                "processor checkpoints advanced during/after the burst (no wedge)")
            ;; A completion fired AFTER the burst is still judged — the
            ;; processor is alive, not stuck replaying a lost batch.
            (let [post-tick (random-uuid)]
              (cp/process-command (assoc ctx :command
                                         {:command/name :sheet/complete-node-execution
                                          :command/id (random-uuid) :command/timestamp (time/now)
                                          :sheet-id sheet-id :tick-id post-tick :node-id node-id
                                          :node-type :llm :status :success
                                          :writes {:generated-tree-raw [:sequence [:llm {}] [:final {}]]}
                                          :duration-ms 1}))
              (is (wait-until 10000
                              #(seq (filter (fn [e] (= post-tick (:tick-id e))) (score-events ctx))))
                  "a post-burst completion is still judged (processor not stalled)"))))))))
