(ns eb9-resilience-prototype
  "EB9 — WORTH prototype: settle the reusable resilience sub-tree
   (`with-resilience`) on ONE real induced subbehavior failure BEFORE the full TDD.

   We take a real subbehavior STEP (the EXTRACT apply-step: author a per-row
   transform → apply it → count concepts), INJECT a failure (force the PRIMARY
   author to MIS-GROUND → a transform that touches a key the rows do not have → 0
   concept drafts), and prove the three resilience behaviors with the REAL DSL
   runtime (real Grain event store, real async todo processors, real fallback /
   condition / sequence semantics — only the LLM is stubbed to a deterministic
   `:code` node so the WORTH prototype is hermetic and fast):

     (a) the SANITY GATE detects the bad intermediate state (0 concepts) and the
         primary path FAILS;
     (b) RECOVERABLE: the `:fallback`'s ROBUST path produces a sane, scoped draft
         set → the step SELF-CORRECTS (downstream sees a GOOD result, status
         :success);
     (c) UNRECOVERABLE: both paths fail the gate → the troubleshoot node lands a
         STRUCTURED :diagnosis AND the step returns a CLEAN :failure that does NOT
         poison downstream (the concept-drafts read back EMPTY, status :failure,
         the diagnosis present).

   This is the WORTH that settles the sub-tree shape; the durable hermetic tests
   (components/ontology/test/.../resilience_test.clj) and the on-demand LIVE verify
   with a REAL LLM troubleshoot (development/src/eb9_resilience_live_verify.clj)
   follow.

   USAGE (REPL, :dev:test):
     (require '[eb9-resilience-prototype :as p] :reload)
     (p/run-all!)"
  (:require [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.runtime :as runtime]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.todo-processors]
            [ai.obney.orc.ontology.core.resilience :as res]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [clojure.pprint :as pp]))

;; ---------------------------------------------------------------------------
;; A tiny in-memory "source" + two extract-like authoring/apply steps. The
;; PRIMARY mis-grounds (touches :nope, a key the rows lack) → 0 concepts. The
;; ROBUST path grounds in the REAL key (:id) → a sane scoped draft set.
;; ---------------------------------------------------------------------------

(def sample-rows
  [{:id "a-1" :label "Alpha"} {:id "b-2" :label "Beta"} {:id "c-3" :label "Gamma"}])

(defn primary-apply
  "INDUCED FAILURE: the primary author mis-grounds — it keys concepts off :nope,
   which the rows do NOT have → every concept-draft uri is nil → after the
   non-nil filter, ZERO concept drafts (the classic mis-ground false-empty)."
  [{:keys [inputs]}]
  (let [rows (:rows inputs)
        drafts (->> rows
                    (map (fn [r] (when-let [k (:nope r)] {:uri (str "x:" k) :label (:label r)})))
                    (remove nil?)
                    vec)]
    {:concept-drafts drafts
     :concept-count (count drafts)}))

(defn robust-apply
  "The ROBUST alternative: grounds in the REAL key (:id) → a sane scoped draft
   set (one concept per row, keyed by the real id)."
  [{:keys [inputs]}]
  (let [rows (:rows inputs)
        drafts (->> rows
                    (map (fn [r] {:uri (str "concept:" (:id r)) :label (:label r)}))
                    vec)]
    {:concept-drafts drafts
     :concept-count (count drafts)}))

(defn robust-apply-also-broken
  "An UNRECOVERABLE world: the robust path ALSO mis-grounds (keys off :missing) →
   0 concepts. Proves the fail-with-diagnosis branch."
  [{:keys [inputs]}]
  (let [rows (:rows inputs)
        drafts (->> rows
                    (map (fn [r] (when-let [k (:missing r)] {:uri (str "y:" k)})))
                    (remove nil?)
                    vec)]
    {:concept-drafts drafts
     :concept-count (count drafts)}))

;; A DETERMINISTIC stub of the troubleshoot node (the WORTH prototype is hermetic
;; — the real LLM troubleshoot is exercised in the live verify). It reasons over
;; the bad state and lands the SAME structured :diagnosis shape the :llm node
;; would, with :reasoning FIRST (#13). NO hardcoded phrase matching — it inspects
;; the actual concept-count value it is given.
(defn troubleshoot-stub
  [{:keys [inputs]}]
  (let [cc (:concept-count inputs)
        drafts (:concept-drafts inputs)]
    {:reasoning (str "Enumerated causes: (1) mis-grounded field access, (2) "
                     "over-aggressive scope filter, (3) empty source. Ruled out "
                     "(3): rows were present. concept-count=" cc ".")
     :diagnosis
     {:symptom (str "sanity gate rejected: concept-count=" cc " (expected > 0)")
      :root-cause "the per-row transform grounded field access in a key the rows do not carry → every draft uri was nil"
      :ruled-out [{:cause "empty source" :ruled-out-by "rows were present at apply time"}]
      :check-failed "concept-count > 0"
      :issues [{:rule "field-grounding" :reason "transform accessed a non-existent key"}]
      :recommended-fix "re-author the transform grounding field access in the REAL sampled-row keys"
      :recoverable? false
      :observed-drafts (count (or drafts []))}}))

;; ---------------------------------------------------------------------------
;; Build a resilient extract-apply step via the EB9 builder + a tiny downstream
;; node that COUNTS what it received (to prove poison / no-poison downstream).
;; ---------------------------------------------------------------------------

(defn- resilient-step [{:keys [robust-fn]}]
  (res/with-resilience
    {:step "extract-apply"
     :primary (dsl/code "extract-apply-primary"
                :fn "eb9-resilience-prototype/primary-apply"
                :reads [:rows]
                :writes [:concept-drafts :concept-count])
     :robust (dsl/code "extract-apply-robust"
               :fn (str "eb9-resilience-prototype/" robust-fn)
               :reads [:rows]
               :writes [:concept-drafts :concept-count])
     ;; DETERMINISTIC sanity gate — concept-count must be > 0 (no hardcoded
     ;; phrase matching; a structural threshold on the declared key).
     :gate {:check {:key :concept-count :op :gt :value 0}}
     ;; the troubleshoot reads the bad state + lands a structured :diagnosis.
     :troubleshoot {:reads [:rows :concept-drafts :concept-count]
                    :step-label "the per-row extraction transform"
                    :expectation "a non-empty, scoped set of concept drafts"}}))

(defn- build-with-stubbed-troubleshoot!
  "Rewrite the synthesized troubleshoot :ai leaf to a deterministic :code leaf
   (same name/reads, writes [:reasoning :diagnosis]) so the prototype runs with no
   LLM. Walks the tree replacing the node whose :name ends in -troubleshoot."
  [ctx wf]
  (let [rewrite (fn rewrite [node]
                  (cond
                    (and (map? node)
                         (= :leaf (:node-type node))
                         (re-find #"-troubleshoot$" (str (:name node))))
                    (dsl/code (:name node)
                      :fn "eb9-resilience-prototype/troubleshoot-stub"
                      :reads (:reads node)
                      :writes [:reasoning :diagnosis])

                    (and (map? node) (:children node))
                    (update node :children #(mapv rewrite %))

                    :else node))
        wf* (update wf :root-node rewrite)]
    (dsl/build-workflow! ctx wf*)))

(defn- build-sheet! [ctx {:keys [robust-fn name]}]
  (let [step (resilient-step {:robust-fn robust-fn})
        wf (dsl/workflow name
             (dsl/blackboard (merge
                              {:rows [:vector [:map {:closed false}]]
                               :concept-drafts [:vector [:map {:closed false}]]
                               :concept-count :int}
                              (res/resilience-blackboard-keys)))
             (dsl/sequence "root" step))]
    ;; SWAP the synthesized :llm troubleshoot for the deterministic stub: locate
    ;; the troubleshoot leaf and rewrite it to a :code node so the WORTH prototype
    ;; is hermetic. (The live verify keeps the real :llm node.)
    (build-with-stubbed-troubleshoot! ctx wf)))

;; ---------------------------------------------------------------------------
;; Real-Grain harness (no LLM provider needed — the prototype is :code-only).
;; ---------------------------------------------------------------------------

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/eb9-proto-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB
                         {:storage-dir dir :db-name "eb9-proto"
                          :map-size (* 256 1024 1024)}))
        base-ctx {:event-store store :cache cache :tenant-id (random-uuid)
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  :event-pubsub ps ::cache-dir dir}
        processors (reduce-kv
                    (fn [acc proc-name {:keys [handler-fn topics]}]
                      (assoc acc proc-name
                             (tp/start {:event-pubsub ps :topics topics
                                        :handler-fn handler-fn :context base-ctx})))
                    {} @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defn- stop-ctx [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (rmp/l1-clear!)
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [s (:event-store ctx)] (es/stop s))
  (when-let [d (::cache-dir ctx)]
    (let [f (java.io.File. d)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c)) (.delete f)))))

(defn- run-case! [ctx {:keys [robust-fn name]}]
  (let [sid (build-sheet! ctx {:robust-fn robust-fn :name name})
        tick-id (random-uuid)
        result (runtime/execute ctx sid {"rows" sample-rows}
                                :timeout-ms 60000 :tick-id tick-id)
        _ (Thread/sleep 200)
        bb (rm/get-tick-blackboard ctx tick-id)]
    {:status (:status result)
     :concept-drafts (get-in bb [:concept-drafts :value])
     :concept-count (get-in bb [:concept-count :value])
     :diagnosis (get-in bb [:diagnosis :value])
     :reasoning (get-in bb [:reasoning :value])}))

(defn run-all! []
  (let [ctx (make-ctx)]
    (try
      (println "=== EB9 RESILIENCE PROTOTYPE (induced Extract failure) ===\n")
      (let [recoverable (run-case! ctx {:robust-fn "robust-apply"
                                        :name "eb9-proto/recoverable@v1"})
            unrecoverable (run-case! ctx {:robust-fn "robust-apply-also-broken"
                                          :name "eb9-proto/unrecoverable@v1"})]
        (println "--- (a)+(b) RECOVERABLE: primary mis-grounds → gate rejects → robust recovers ---")
        (println "  status:" (:status recoverable)
                 " concept-count:" (:concept-count recoverable))
        (println "  concept-drafts:" (pr-str (:concept-drafts recoverable)))
        (println "  diagnosis present?:" (some? (:diagnosis recoverable)) "(should be nil — recovered, never diagnosed)")
        (println)
        (println "--- (c) UNRECOVERABLE: both paths mis-ground → troubleshoot + clean failure ---")
        (println "  status:" (:status unrecoverable) "(should be :failure)")
        (println "  concept-drafts (downstream NOT poisoned):" (pr-str (:concept-drafts unrecoverable)))
        (println "  diagnosis (structured, landed before clean failure):")
        (pp/pprint (:diagnosis unrecoverable))
        {:recoverable recoverable :unrecoverable unrecoverable})
      (finally (stop-ctx ctx)))))

(defn -main [& _]
  (let [fut (future (try (run-all!) :done
                         (catch Throwable t
                           (println "PROTO FAILED:" (.getMessage t))
                           (.printStackTrace t) :error)))
        r (deref fut 120000 :timeout)]
    (println "\nEB9 prototype result:" r)
    (shutdown-agents)
    (System/exit (if (= :done r) 0 1))))
