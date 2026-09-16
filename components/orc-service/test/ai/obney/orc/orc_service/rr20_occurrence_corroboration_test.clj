(ns ai.obney.orc.orc-service.rr20-occurrence-corroboration-test
  "RR-20 exact behavioral change 4, delta fix (Finding 1 from inspection):
   `on-campaign-success-corroborate-worked-pattern` reinforces a shape's
   worked-pattern claim from RR-19's durable campaign verdict.

   FINDING 1 (reproduced, root-caused, fixed): `success-bookend-fingerprints`
   in `todo_processors.clj` called `filter`/`map` directly on the value
   `event-store/read` returns. Grain v3's in-memory store returns a
   REDUCIBLE, not a seq, so this threw `IllegalArgumentException: Don't know
   how to create ISeq from: ...in_memory$read_single$reify...` on the
   todo-processor's own thread — swallowed silently, so the corroboration
   writer never reinforced anything in a real processor-full context despite
   every unit-level check on its PURE helpers passing. Fixed by materialising
   the read with `(into [] ...)` before `filter`/`map`, matching every other
   reader in this codebase.

   This test drives the FULL path through the REAL, REGISTERED processors and
   the REAL commands: a Phase-2 bookend, a real `:sheet/complete-node-
   execution` terminal completion, and a real `:ontology/record-tree-class-
   occurrence` — never a raw event append, never a direct call to the private
   writer fn. Assertions read the claim set back with a settle loop; no fixed
   sleeps stand in for a proof."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.core.commands]
            [ai.obney.orc.orc-service.core.read-models]
            [ai.obney.orc.orc-service.core.rlm-fingerprint :as rlm-fingerprint]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

(defn- stub-predict-fixture [f]
  (with-redefs [llm/predict (fn [_provider _module _inputs _options]
                              {:outputs {:operations []}
                               :usage {:total-tokens 1}
                               :model "stub"})]
    (f)))

(use-fixtures :each stub-predict-fixture)

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/rr20-corrob-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        base-ctx {:event-store event-store :cache cache :tenant-id (random-uuid)
                  :event-pubsub ps
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  ::cache-dir cache-dir}
        ;; ALL registered processors, including the one this test proves:
        ;; :ontology/on-campaign-success-corroborate-worked-pattern.
        processors (reduce-kv
                    (fn [acc n {:keys [handler-fn topics]}]
                      (assoc acc n (tp/start {:event-pubsub ps :topics topics
                                              :handler-fn handler-fn :context base-ctx})))
                    {} @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defn- stop-context [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [store (:event-store ctx)] (es/stop store))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f) (doseq [c (.listFiles f)] (.delete c)) (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)] (try ~@body (finally (stop-context ~sym)))))

(defn- settle-until! [pred & {:keys [timeout-ms] :or {timeout-ms 4000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 25) (recur))))))

(def ^:private tree-a
  [:sequence [:llm {:reads [:doc] :writes [:summary]}] [:final {:keys [:summary]}]])
(def ^:private source-a "tree-a-source-text")
(def ^:private tree-b
  [:sequence [:llm {:reads [:doc] :writes [:notes]}]
   [:llm {:reads [:notes] :writes [:summary]}] [:final {:keys [:summary]}]])
(def ^:private source-b "tree-b-source-text")

(defn- classify! [ctx source-sheet-id source-tick-id source-node-id class-id]
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/assign-task-class
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :source-sheet-id source-sheet-id
           :source-tick-id source-tick-id
           :source-node-id source-node-id
           :assigned-tree-id class-id
           :confidence 0.95
           :top-candidates []
           :reasoning "rr20-occurrence-corroboration"
           :was-fresh-mint? true})))

(defn- capture-floor! [ctx class-id signature]
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/record-claim-deltas
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :granularity :tree-class
           :target-identifier class-id
           :deltas [{:operation :add :kind :representative-use
                     :content signature :episodes []
                     :from-legacy-corpus false
                     :evidence-basis :classification-signature}]
           :evidence-event-count 0
           :claim-set-version (ontology/get-claim-set-version ctx :tree-class class-id)})))

(defn- emit! [ctx source-sheet-id source-tick-id tree source status]
  (cp/process-command
   (assoc ctx :command
          {:command/name :sheet/record-rlm-tree-execution-completion
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id (random-uuid)
           :tick-id (random-uuid)
           :trajectory []
           :total-usage {:total-tokens 0}
           :status status
           :duration-ms 1
           :tree-fingerprint (rlm-fingerprint/fingerprint tree)
           :generated-tree tree
           :generated-tree-source source
           :source-sheet-id source-sheet-id
           :source-tick-id source-tick-id})))

(defn- complete-node-execution!
  "The REAL command a turn's researcher node completion goes through
   (`:sheet/complete-node-execution`). Returns the resulting
   `:sheet/node-execution-completed` event's `:event/id`, which
   `:ontology/record-tree-class-occurrence` requires as
   `:source-completion-event-id`."
  [ctx sheet-id tick-id node-id status]
  (cp/process-command
   (assoc ctx :command
          {:command/name :sheet/complete-node-execution
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :sheet-id sheet-id
           :tick-id tick-id
           :node-id node-id
           :status status
           :node-type :repl-researcher
           :completion-kind :terminal}))
  (:event/id
   (last
    (into [] (es/read (:event-store ctx)
                      {:tenant-id (:tenant-id ctx)
                       :types #{:sheet/node-execution-completed}
                       :tags #{[:tick tick-id]}})))))

(defn- record-occurrence!
  "The REAL RR-19 command — `:ontology/record-tree-class-occurrence` —
   fired directly (mirrors `rr19_outcome_recurrence_test`'s own precedent),
   naming the exact completion event `complete-node-execution!` produced."
  [ctx sheet-id tick-id node-id class-id completion-event-id verdict]
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/record-tree-class-occurrence
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :source-sheet-id sheet-id
           :source-tick-id tick-id
           :source-node-id node-id
           :source-completion-event-id completion-event-id
           :assigned-tree-id class-id
           :verdict verdict})))

(defn- claim-by [ctx class-id kind content]
  (first (filter #(and (= kind (:kind %)) (= content (:content %)))
                 (ontology/get-claims ctx :tree-class class-id))))

(deftest a-success-verdict-corroborates-the-successful-shape-through-the-registered-processor
  (testing "a Phase-2 success bookend, then the real terminal completion +
            real RR-19 occurrence command: A's :strength claim gains support
            AND a :verdict-corroborations count — through the REGISTERED
            on-campaign-success-corroborate-worked-pattern processor, not a
            direct call to its private writer fn"
    (with-test-ctx [ctx]
      (let [class-id (random-uuid)
            sheet (random-uuid) tick (random-uuid) node (random-uuid)]
        (classify! ctx sheet tick node class-id)
        (capture-floor! ctx class-id "implement: summarize a document")
        (Thread/sleep 150)
        (emit! ctx sheet tick tree-a source-a :success)
        (is (settle-until!
             #(= 2 (:support (or (first (filter (fn [c] (and (= :strength (:kind c))
                                                             (= source-a (:recommendation c))))
                                                (ontology/get-claims ctx :tree-class class-id)))
                                 {})))
             :timeout-ms 2000)
            "the bookend alone landed the shape at the initial support (2)")
        (let [completion-event-id (complete-node-execution! ctx sheet tick node :success)]
          (record-occurrence! ctx sheet tick node class-id completion-event-id :success)
          (is (settle-until!
               #(let [c (first (filter (fn [c] (= source-a (:recommendation c)))
                                       (ontology/get-claims ctx :tree-class class-id)))]
                  (and (some? c) (= 3 (:support c)) (= 1 (:verdict-corroborations c)))))
              "the durable campaign verdict reinforced the SAME shape claim
               (support 2 -> 3) and stamped :verdict-corroborations 1 — the
               bug left support at 2 and :verdict-corroborations absent")
          (let [c (first (filter #(= source-a (:recommendation %))
                                 (ontology/get-claims ctx :tree-class class-id)))]
            (is (= :emitted-artifact-outcome (:evidence-basis c))
                "evidence-basis stays the CREATING delta's basis
                 (:emitted-artifact-outcome) — the :campaign-verdict-basis
                 reinforcement does NOT overwrite it. `reinforce-claim` never
                 touches `:evidence-basis` (only `:add`/`edit-claim` do),
                 which is CC-9d's anti-laundering rule holding for this new
                 basis exactly as it does for every other one.")))))))

(deftest a-success-verdict-reinforces-only-the-successful-shape-never-the-failed-one
  (testing "failure bookend A + success bookend B in ONE turn, then that
            turn's terminal completion is :success: the verdict reinforces
            B's :strength claim only — A's :weakness claim (the failed
            shape) is left completely untouched"
    (with-test-ctx [ctx]
      (let [class-id (random-uuid)
            sheet (random-uuid) tick (random-uuid) node (random-uuid)]
        (classify! ctx sheet tick node class-id)
        (capture-floor! ctx class-id "implement: summarize a document")
        (Thread/sleep 150)
        (emit! ctx sheet tick tree-a source-a :failure)
        (emit! ctx sheet tick tree-b source-b :success)
        (is (settle-until!
             #(and (some? (claim-by ctx class-id :weakness
                                    (str "emitted the " (rlm-fingerprint/fingerprint tree-a)
                                         " tree for tasks of this class and it failed")))
                   (some? (first (filter (fn [c] (= source-b (:recommendation c)))
                                        (ontology/get-claims ctx :tree-class class-id))))))
            "both shapes landed: A as a failed shape, B as a worked pattern")
        (let [weakness-before (claim-by ctx class-id :weakness
                                        (str "emitted the " (rlm-fingerprint/fingerprint tree-a)
                                             " tree for tasks of this class and it failed"))
              strength-before (first (filter #(= source-b (:recommendation %))
                                             (ontology/get-claims ctx :tree-class class-id)))
              completion-event-id (complete-node-execution! ctx sheet tick node :success)]
          (record-occurrence! ctx sheet tick node class-id completion-event-id :success)
          (is (settle-until!
               #(let [b (first (filter (fn [c] (= source-b (:recommendation c)))
                                       (ontology/get-claims ctx :tree-class class-id)))]
                  (and (some? b)
                       (< (:support strength-before) (:support b))
                       (= 1 (:verdict-corroborations b)))))
              "B's claim was reinforced by the campaign verdict")
          (let [weakness-after (claim-by ctx class-id :weakness (:content weakness-before))]
            (is (= (:support weakness-before) (:support weakness-after))
                "A's failed-shape claim support is UNCHANGED by the success verdict")
            (is (zero? (or (:verdict-corroborations weakness-after) 0))
                "A's failed-shape claim carries no verdict corroboration")))))))
