(ns ai.obney.orc.ontology.pr2-exclusions-and-outcome-totality-test
  "PR-2 (ADR 0030) — exclusions durable + every consolidation request yields
   an outcome.

   Spec obligations, VERBATIM:
     BoundedReflectionEvidence — \"...Every event excluded by the budget is
     OBSERVABLE.\"
     EveryConsolidationRequestYieldsAnOutcome — \"A consolidation request
     terminates in exactly one durable outcome event — deltas recorded,
     ClaimSetUnchanged's exclusion shape, or a failure record. A request with
     NO outcome is a defect, not an ambiguity: the 2026-08-18 request that
     vanished under a mid-flight JVM death was indistinguishable from
     health.\"

   OBLIGATION PROVENANCE, stated rather than implied: contract-level
   invariants derive ZERO obligations from `allium plan` (known generator
   blindness, SIO-4b finding), so these tests are seeded from the spec text
   above and the 2026-08-18 vanished-request incident, NOT from /propagate.

   Cycles:
     1. exclusion record lands (ONE compact event, not one per excluded
        item) and reads back through the projection when the budget excludes
     2. zero-exclusion consolidations emit NOTHING
     3. totality — normal success / failure / unchanged / budget-skip each
        yield exactly one durable outcome attributed to the request
     4. simulated mid-flight death (killed before the outcome append) →
        orphan detectable by a store query
     5. the sweep / next consolidation pass converts the orphan into a
        durable failure record with an honest reason class
     6. (inspection ruling, spec tend e8c15571) a SUCCESSFUL reflection that
        proposed zero operations answers its request with a durable
        :no-operations-proposed skip — never swept as a false interruption

   The reflection LLM is STUBBED (the cc5 harness idiom) so the contract is
   deterministic; live verification is the orchestrator's QA."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.consolidator :as consolidator]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.commands]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Harness — the cc5 context idiom: real command registry, real read models,
;; real todo processors on a core-async pubsub, in-memory store, LMDB cache.
;; =============================================================================

(defn- create-context
  ([] (create-context {}))
  ([overrides]
   (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
         event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
         cache-dir (str "/tmp/pr2-test-" (random-uuid))
         cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
         base-ctx (merge {:event-store event-store :cache cache :tenant-id (random-uuid)
                          :event-pubsub ps
                          :command-registry (cp/global-command-registry)
                          :query-registry (qp/global-query-registry)
                          ::cache-dir cache-dir}
                         overrides)
         processors (reduce-kv (fn [acc n {:keys [handler-fn topics]}]
                                 (assoc acc n (tp/start {:event-pubsub ps :topics topics
                                                         :handler-fn handler-fn :context base-ctx})))
                               {} @tp/processor-registry*)]
     (assoc base-ctx :processors processors))))

(defn- stop-context [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [es (:event-store ctx)] (es/stop es))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f) (doseq [c (.listFiles f)] (.delete c)) (.delete f)))))

(defmacro with-test-ctx [[sym & [overrides]] & body]
  `(let [~sym (create-context ~(or overrides {}))]
     (try ~@body (finally (stop-context ~sym)))))

(defn- await-pred
  "POLL NEVER PARK — bounded inline sleep-and-read loop. Returns the first
   truthy (f) within timeout-ms, else nil."
  [f timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (if-let [v (f)]
        v
        (when (< (System/currentTimeMillis) deadline)
          (Thread/sleep 50)
          (recur))))))

;; =============================================================================
;; Production-faithful evidence fixtures (the cc5 observe! idiom): a real
;; :ontology/assign-task-class occurrence, optionally judge-grounded. All
;; identifiers random per call — disjoint across tests by construction.
;; =============================================================================

(defn- ground-episodes! [ctx eps]
  (doseq [[sheet-id tick-id] (distinct eps)]
    (cp/process-command
      (assoc ctx :command
             {:command/name :evaluation/record-judge-score
              :command/id (random-uuid) :command/timestamp (time/now)
              :sheet-id sheet-id :node-id (random-uuid) :tick-id tick-id
              :judge-name "coding-outcome" :judge-config {} :score 0.8
              :feedback (str "The turn applied the edit to src/util.clj and the "
                             "verification command exited 0, so the assessment is "
                             "grounded in the observed diff and command output.")
              :dimensions []}))))

(defn- observe!
  "Record ONE real occurrence for the tree-class target. Grounds a judge
   score against it unless :grounded? false."
  ([ctx tree-class-id] (observe! ctx tree-class-id {:grounded? true}))
  ([ctx tree-class-id {:keys [grounded?]}]
   (let [sheet-id (random-uuid) tick-id (random-uuid)]
     (cp/process-command
       (assoc ctx :command
              {:command/name :ontology/assign-task-class
               :command/id (random-uuid) :command/timestamp (time/now)
               :source-sheet-id sheet-id :source-tick-id tick-id
               :source-node-id (random-uuid)
               :assigned-tree-id tree-class-id :confidence 0.95
               :top-candidates [] :reasoning "pr2 test observation"
               :was-fresh-mint? false}))
     (when grounded? (ground-episodes! ctx [[sheet-id tick-id]]))
     [sheet-id tick-id])))

(defn- with-window! [ctx tree-class-id n & [opts]]
  (mapv (fn [_] (observe! ctx tree-class-id (or opts {:grounded? true}))) (range n)))

(defmacro with-reflection
  "Stub the reflection LLM to return a fixed operation list."
  [ops & body]
  `(with-redefs [llm/predict (fn [& _#]
                               {:outputs {:operations ~ops}
                                :usage {:total-tokens 1} :model "stub"})]
     ~@body))

(defn- set-evidence-budget! [ctx budget-tokens]
  (cp/process-command
    (assoc ctx :command {:command/name :ontology/set-evidence-token-budget
                         :command/id (random-uuid) :command/timestamp (time/now)
                         :budget-tokens budget-tokens})))

(defn- exclusion-events-in-store [ctx]
  (into [] (es/read (:event-store ctx)
                    {:types #{:ontology/reflection-evidence-excluded}
                     :tenant-id (:tenant-id ctx)})))

;; A budget below the bare prompt overhead for :tree-class (9,000 chars /
;; 3.47 chars-per-token = 2,594 predicted tokens) plus any single event:
;; the newest-first walk deterministically selects NOTHING and excludes the
;; whole window, regardless of per-observation byte drift in the fixtures.
(def ^:private budget-excluding-everything 2600)

;; =============================================================================
;; Cycle 1 — the exclusion record lands, compactly, and reads back
;; =============================================================================

(deftest budget-exclusion-lands-one-compact-durable-record-and-reads-back
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-window! ctx target 8)
      (set-evidence-budget! ctx budget-excluding-everything)
      (with-reflection [{:operation :add :kind :weakness
                         :content "claims success on an empty diff"}]
        (consolidator/consolidate! ctx :tree-class target))
      (testing "ONE compact exclusion event in the store — never one event per excluded item"
        (is (= 1 (count (exclusion-events-in-store ctx)))
            "8 excluded events must produce exactly one summary record"))
      (testing "the record reads back through the projection, not a return value"
        (let [records (ontology/get-reflection-evidence-exclusions ctx :tree-class target)]
          (is (= 1 (count records)))
          (let [{:keys [budget-tokens selected-count excluded-count
                        predicted-prompt-tokens oldest-excluded-at
                        newest-excluded-at]} (first records)]
            (is (= budget-excluding-everything budget-tokens))
            (is (= 0 selected-count)
                "a budget under the bare overhead selects nothing (the PR-1 degenerate case)")
            (is (= 8 excluded-count) "every squeezed-out event is counted")
            (is (pos? predicted-prompt-tokens))
            (is (string? oldest-excluded-at))
            (is (string? newest-excluded-at))
            (is (<= (compare oldest-excluded-at newest-excluded-at) 0)
                "the excluded range is the contiguous OLDEST prefix — its bounds identify every excluded event")))))))

;; =============================================================================
;; Cycle 2 — zero-exclusion consolidations emit NOTHING
;; =============================================================================

(deftest zero-exclusion-consolidation-emits-no-exclusion-record
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-window! ctx target 3)
      ;; No budget override: the DERIVED default (327,681 tokens) admits the
      ;; whole 3-event window with orders-of-magnitude headroom.
      (with-reflection [{:operation :add :kind :strength
                         :content "verifies with a real command"}]
        (consolidator/consolidate! ctx :tree-class target))
      (is (= [] (exclusion-events-in-store ctx))
          "no exclusion happened, so NO record may exist — an empty record would be misleading")
      (is (= [] (ontology/get-reflection-evidence-exclusions ctx :tree-class target))))))

;; =============================================================================
;; Cycle 3 — totality: every request-driven consolidation yields exactly one
;; durable outcome, attributed to the request
;; =============================================================================

(defn- request-consolidation!
  "Dispatch a real on-demand consolidation request and return the REQUEST
   EVENT's id, read back from the store (never a return value). The running
   consolidate-on-request processor picks the event up asynchronously."
  [ctx target-type target-id]
  (cp/process-command
    (assoc ctx :command {:command/name :ontology/request-consolidation
                         :command/id (random-uuid) :command/timestamp (time/now)
                         :target-type target-type :target-id target-id
                         :on-demand? true}))
  (->> (es/read (:event-store ctx) {:types #{:ontology/consolidation-requested}
                                    :tenant-id (:tenant-id ctx)})
       (into [])
       (filter #(= target-id (:target-id %)))
       last
       :event/id))

(defn- await-outcome [ctx rid]
  (await-pred #(let [o (ontology/get-consolidation-outcome ctx rid)]
                 (when (not= :none (:status o)) o))
              20000))

(deftest a-successful-request-driven-consolidation-yields-exactly-one-outcome
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-window! ctx target 6)
      (with-reflection [{:operation :add :kind :weakness
                         :content "claims success on an empty diff"}]
        (let [rid (request-consolidation! ctx :tree-class target)
              outcome (await-outcome ctx rid)]
          (is (uuid? rid))
          (is (some? outcome) "the request terminated in a durable outcome")
          (is (= :deltas-recorded (:status outcome)))
          (is (= 1 (count (:events outcome)))
              "EXACTLY one durable outcome event — not zero, not two")
          (is (= [] (ontology/get-unanswered-consolidation-requests
                      ctx {:older-than-ms 0}))
              "an answered request is not an orphan"))))))

(deftest a-failed-reflection-yields-exactly-one-failure-outcome
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-window! ctx target 6)
      (with-redefs [llm/predict (fn [& _]
                                  (throw (ex-info "provider 500 simulated" {})))]
        (let [rid (request-consolidation! ctx :tree-class target)
              outcome (await-outcome ctx rid)]
          (is (some? outcome) "a dying reflection still terminates in a durable outcome")
          (is (= :failed (:status outcome)))
          (is (= 1 (count (:events outcome))))
          (is (= :retries-exhausted (:reason (first (:events outcome))))
              "the evidenced exception-terminal class, exactly as CC-28 classifies it")
          (is (= [] (ontology/get-unanswered-consolidation-requests
                      ctx {:older-than-ms 0}))))))))

(deftest an-all-deltas-excluded-consolidation-yields-the-claim-set-unchanged-shape
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      ;; Observations with NO judge grounding: every proposed delta fails
      ;; CC-4's evidence guard, so the outcome is ClaimSetUnchanged's
      ;; exclusion shape — PromotionEvidenceExcluded events plus the
      ;; deliberately-unadvanced version, never its own event type.
      (with-window! ctx target 5 {:grounded? false})
      (with-reflection [{:operation :add :kind :weakness
                         :content "asserts completion without verification"}]
        (let [rid (request-consolidation! ctx :tree-class target)
              outcome (await-outcome ctx rid)]
          (is (some? outcome))
          (is (= :claim-set-unchanged (:status outcome)))
          (is (every? #(= :ontology/promotion-evidence-excluded (:event-type %))
                      (:events outcome))
              "the unchanged shape is exclusions only — no recorded event, no failure record")
          (is (= 0 (ontology/get-claim-set-version ctx :tree-class target))
              "the claim-set version deliberately does not advance")
          (is (= [] (ontology/get-unanswered-consolidation-requests
                      ctx {:older-than-ms 0}))))))))

(deftest a-budget-skipped-request-yields-a-durable-skip-outcome
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-window! ctx target 3)
      (cp/process-command
        (assoc ctx :command {:command/name :ontology/set-consolidation-budget
                             :command/id (random-uuid) :command/timestamp (time/now)
                             :target-type :tree-class :budget 0}))
      (with-reflection [{:operation :add :kind :strength :content "never called"}]
        (let [rid (request-consolidation! ctx :tree-class target)
              outcome (await-outcome ctx rid)]
          (is (some? outcome)
              "a budget-skipped request must not be indistinguishable from a vanished one")
          (is (= :skipped (:status outcome)))
          (is (= 1 (count (:events outcome))))
          (is (= [] (ontology/get-unanswered-consolidation-requests
                      ctx {:older-than-ms 0}))
              "a skip is an ANSWER — the sweep must never convert it into a false interruption record"))))))

(deftest a-zero-proposal-reflection-yields-a-durable-skip-not-an-orphan
  ;; Inspection ruling (spec tend e8c15571): a SUCCESSFUL reflection that
  ;; proposed zero operations is a third fact — neither a budget skip nor a
  ;; death — and must ANSWER its request durably. Before this cycle the
  ;; :else branch only logged, so the request stayed an orphan and the
  ;; sweep would eventually have recorded :caller-interrupted about a call
  ;; that SUCCEEDED — a false fact in the source of truth.
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-window! ctx target 5)
      (with-reflection []
        (let [rid (request-consolidation! ctx :tree-class target)
              outcome (await-outcome ctx rid)]
          (is (some? outcome)
              "a successful reflection that proposed nothing still terminates its request in a durable outcome")
          (is (= :skipped (:status outcome)))
          (is (= 1 (count (:events outcome))))
          (is (= :no-operations-proposed (:reason (first (:events outcome))))
              "the :reason field keeps budget skip / zero-proposal success / death distinguishable")
          (is (= [] (ontology/get-unanswered-consolidation-requests
                      ctx {:older-than-ms 0}))
              "a zero-proposal request is NOT an orphan")
          (testing "the sweep never converts it into a false interruption record"
            (consolidator/sweep-orphaned-consolidation-requests!
              ctx {:older-than-ms 0})
            (let [outcome' (ontology/get-consolidation-outcome ctx rid)]
              (is (= :skipped (:status outcome')))
              (is (= 1 (count (:events outcome')))
                  "still exactly one outcome — the sweep saw an ANSWERED request and left it alone")
              (is (empty? (->> (es/read (:event-store ctx)
                                        {:types #{:ontology/description-consolidation-failed}
                                         :tenant-id (:tenant-id ctx)})
                               (into [])
                               (filter #(= rid (:request-id %)))))
                  "no :caller-interrupted record exists about the successful call"))))))))

(deftest a-description-updated-outcome-is-attributed-at-the-command-seam
  ;; The body path's outcome attribution, exercised at the command seam: the
  ;; description command carries the request id, the event carries it, and
  ;; the ledger folds it as this request's outcome.
  (with-test-ctx [ctx]
    (let [rid (random-uuid)
          body {:capabilities ["summarizes tool output"]
                :strengths [] :weaknesses []
                :representative-uses [] :avoid-when []
                :summary "pr2 command-seam description"
                :version 1 :consolidated-from-event-count 3}]
      (cp/process-command
        (assoc ctx :command {:command/name :ontology/record-node-type-description
                             :command/id (random-uuid) :command/timestamp (time/now)
                             :target-id :llm :body body :request-id rid}))
      (let [outcome (ontology/get-consolidation-outcome ctx rid)]
        (is (= :description-updated (:status outcome)))
        (is (= 1 (count (:events outcome)))))
      (testing "the consolidator's body path threads the SAME request id into this command"
        (let [cmd (#'consolidator/record-description-command
                    :node-type :llm body nil rid)]
          (is (= rid (:request-id cmd))))))))

;; =============================================================================
;; Cycle 4 — the 08-18 shape: a mid-flight death (killed BEFORE the outcome
;; append) leaves a DETECTABLE orphan, not an ambiguity
;; =============================================================================

(def ^:private outcome-commands
  #{:ontology/record-claim-deltas
    :ontology/record-consolidation-failure
    :ontology/record-consolidation-skip
    :ontology/record-node-type-description
    :ontology/record-node-instance-description
    :ontology/record-tree-description
    :ontology/record-tree-class-description})

(defn- make-orphan!
  "Drive a REAL request through the running processor and the real claim
   path, killing the process at the outcome append — the reflection ran, the
   outcome never landed, exactly the 2026-08-18 mid-flight JVM death.
   Returns the orphaned request id after the death is witnessed."
  [ctx target]
  (let [killed (atom nil)
        orig cp/process-command]
    (with-redefs [llm/predict (fn [& _]
                                {:outputs {:operations
                                           [{:operation :add :kind :weakness
                                             :content "died before the append"}]}
                                 :usage {:total-tokens 1} :model "stub"})
                  cp/process-command
                  (fn [c]
                    (if (contains? outcome-commands
                                   (get-in c [:command :command/name]))
                      (do (reset! killed (get-in c [:command :command/name]))
                          (throw (InterruptedException.
                                   "simulated mid-flight JVM death")))
                      (orig c)))]
      (let [rid (request-consolidation! ctx :tree-class target)]
        (is (await-pred #(deref killed) 20000)
            "the outcome append was reached and killed — the reflection itself ran")
        rid))))

(deftest a-mid-flight-death-leaves-a-detectable-orphan
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-window! ctx target 6)
      (let [rid (make-orphan! ctx target)]
        (is (= :none (:status (ontology/get-consolidation-outcome ctx rid)))
            "no outcome landed — the 08-18 shape reproduced")
        (let [orphans (ontology/get-unanswered-consolidation-requests
                        ctx {:older-than-ms 0})]
          (is (= [rid] (mapv :request-id orphans))
              "the vanished request is FINDABLE by a store query — a defect, not an ambiguity")
          (is (= :tree-class (:target-type (first orphans))))
          (is (= target (:target-id (first orphans))))
          (is (string? (:requested-at (first orphans)))))))))

;; =============================================================================
;; Cycle 5 — the sweep (and the next consolidation pass) converts the orphan
;; into a durable failure record with an honest reason class
;; =============================================================================

(deftest the-sweep-converts-an-orphan-into-a-durable-caller-interrupted-record
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-window! ctx target 6)
      (let [rid (make-orphan! ctx target)]
        (consolidator/sweep-orphaned-consolidation-requests! ctx {:older-than-ms 0})
        (let [outcome (ontology/get-consolidation-outcome ctx rid)]
          (is (= :failed (:status outcome)))
          (is (= 1 (count (:events outcome))))
          (is (= :caller-interrupted (:reason (first (:events outcome))))
              "'was interrupted before it could answer' is the honest SIO-4b class for a JVM death — the closed set stays at five"))
        (is (= [] (ontology/get-unanswered-consolidation-requests
                    ctx {:older-than-ms 0}))
            "a swept orphan is answered — sweeping twice would double-count")
        (testing "the durable record carries the sweep's evidence"
          (let [ev (->> (es/read (:event-store ctx)
                                 {:types #{:ontology/description-consolidation-failed}
                                  :tenant-id (:tenant-id ctx)})
                        (into [])
                        (filter #(= rid (:request-id %)))
                        first)]
            (is (some? ev))
            (is (= 0 (:attempts ev))
                "AttemptCountIsEvidenced at its floor: a vanished request evidences ZERO consumed provider attempts")
            (is (str/includes? (str (:error ev)) (str rid))
                "the ride-along string names the orphaned request")))))))

(deftest the-next-consolidation-pass-converts-an-older-orphan
  ;; :orphan-grace-ms 0 in the processor context stands in for "the orphan is
  ;; older than the grace period" — the sweep the processor runs before
  ;; consolidating must convert the OLD orphan and must NOT touch the
  ;; request it is currently answering.
  (with-test-ctx [ctx {:orphan-grace-ms 0}]
    (let [target (random-uuid)]
      (with-window! ctx target 6)
      (let [rid1 (make-orphan! ctx target)]
        (with-reflection [{:operation :add :kind :strength
                           :content "verifies with a real command"}]
          (let [rid2 (request-consolidation! ctx :tree-class target)
                outcome2 (await-outcome ctx rid2)
                outcome1 (await-pred #(let [o (ontology/get-consolidation-outcome ctx rid1)]
                                        (when (not= :none (:status o)) o))
                                     20000)]
            (is (= :failed (:status outcome1))
                "the NEXT pass swept the old orphan")
            (is (= :caller-interrupted (:reason (first (:events outcome1)))))
            (is (= :deltas-recorded (:status outcome2))
                "the current request consolidated normally")
            (is (= 1 (count (:events outcome2)))
                "the sweep excluded the in-flight request — exactly one outcome, not a false interruption plus a success")
            (is (= [] (ontology/get-unanswered-consolidation-requests
                        ctx {:older-than-ms 0})))))))))
