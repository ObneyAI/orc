(ns ai.obney.orc.ontology.cc28-failure-visibility-test
  "CC-28 — consolidation failure visibility (contract ClaimConsolidation,
   specs: orc-cc-sio 926f2bef).

   @invariant FailureIsVisible — a reflection call that fails (provider
   rejection, timeout, exhausted retries, unparseable output, caller
   interruption) leaves a durable failure record carrying the target, the
   reason class, and the attempt count. Asserted on the STORE, never on a
   return value. Every terminal exercised HERE is an evidenced one; the
   evidence-free caller-interruption class is pinned by
   sio4b-caller-interrupted-test.

   @invariant FailuresConsumeBudget — the hourly consolidation budget
   counts failed attempts alongside successes; a target whose every
   attempt dies must not retry unthrottled, and the budget read cannot
   report a dying target as idle.

   Motivating incident: a target taking 23.4% of all consolidation
   requests had its prompt rejected outright (1,571,414 tokens vs
   1,048,576), 0/3 after four retries each, leaving NO event —
   indistinguishable from health.

   The reflection LLM is STUBBED to fail deterministically. ONE terminal
   failure event per attempt-set — :attempts carries the count, never
   per-attempt events."
  (:require [clojure.test :refer [deftest is]]
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

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/cc28-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        base-ctx {:event-store event-store :cache cache :tenant-id (random-uuid)
                  :event-pubsub ps
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  ::cache-dir cache-dir}
        processors (reduce-kv (fn [acc n {:keys [handler-fn topics]}]
                                (assoc acc n (tp/start {:event-pubsub ps :topics topics
                                                        :handler-fn handler-fn :context base-ctx})))
                              {} @tp/processor-registry*)]
    (assoc base-ctx :processors processors)))

(defn- stop-context [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [es (:event-store ctx)] (es/stop es))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f) (doseq [c (.listFiles f)] (.delete c)) (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)] (try ~@body (finally (stop-context ~sym)))))

(defn- failure-events [ctx]
  (into [] (es/read (:event-store ctx)
                    {:types #{:ontology/description-consolidation-failed}
                     :tenant-id (:tenant-id ctx)})))

(def ^:private provider-rejection-message
  ;; The incident's shape verbatim: the provider refuses the prompt outright.
  "prompt is too long: 1571414 tokens > 1048576 maximum")

(defmacro with-throwing-reflection
  "Stub the reflection LLM to THROW on every attempt."
  [msg & body]
  `(with-redefs [llm/predict (fn [& _#] (throw (ex-info ~msg {})))]
     ~@body))

;; ---------------------------------------------------------------------------
;; RED 1 — FailureIsVisible, claim path (:tree-class)
;; ---------------------------------------------------------------------------
(deftest a-thrown-reflection-leaves-a-durable-failure-event-on-the-claim-path
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-throwing-reflection provider-rejection-message
        (consolidator/consolidate! ctx :tree-class target))
      (let [evs (failure-events ctx)
            ev (first evs)]
        (is (= 1 (count evs))
            "ONE terminal failure event per attempt-set — never per-attempt;
             'never answered' becomes a recorded fact instead of silence")
        (is (= :tree-class (:granularity ev)) "the target's granularity is carried")
        (is (= target (:target-identifier ev)) "the target's identity is carried")
        (is (= :provider-rejected (:reason ev))
            "an outright prompt rejection is classified as :provider-rejected —
             a different fact from 'produced no trustworthy knowledge'")
        (is (= 4 (:attempts ev))
            ":attempts carries the whole attempt-set — the reflection's
             :max-retries 3 budget means 4 provider attempts were consumed")))))

;; ---------------------------------------------------------------------------
;; RED 1b — FailureIsVisible, body path (:node-type)
;; ---------------------------------------------------------------------------
(deftest a-thrown-reflection-leaves-a-durable-failure-event-on-the-body-path
  (with-test-ctx [ctx]
    (with-throwing-reflection "connection reset by peer"
      (consolidator/consolidate! ctx :node-type :llm))
    (let [evs (failure-events ctx)
          ev (first evs)]
      (is (= 1 (count evs))
          "the legacy body path records its death exactly as the claim path does")
      (is (= :node-type (:granularity ev)))
      (is (= :llm (:target-identifier ev))
          "a node-type target's KEYWORD identity survives into the record")
      (is (= :retries-exhausted (:reason ev))
          "a transient-looking error that dies anyway is :retries-exhausted")
      (is (= 4 (:attempts ev))))))

;; ---------------------------------------------------------------------------
;; RED 2 — FailuresConsumeBudget: the hourly budget counts the failures
;; and throttles a dying target
;; ---------------------------------------------------------------------------
(deftest failures-consume-the-hourly-budget-and-throttle-a-dying-target
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          calls (atom 0)]
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/set-consolidation-budget
                :command/id (random-uuid) :command/timestamp (time/now)
                :target-type :tree-class :budget 2}))
      (with-redefs [llm/predict (fn [& _]
                                  (swap! calls inc)
                                  (throw (ex-info provider-rejection-message {})))]
        (consolidator/consolidate! ctx :tree-class target)
        (consolidator/consolidate! ctx :tree-class target)
        (is (= 2 (ontology/get-recent-consolidation-count ctx :tree-class))
            "the budget read counts the failed attempt-sets — a dying target
             cannot be reported as idle")
        (let [calls-after-two @calls]
          (consolidator/consolidate! ctx :tree-class target)
          (is (= calls-after-two @calls)
              "the third consolidation is THROTTLED: budget exhausted by the
               failures, so no further LLM attempt fires until the hour rolls")))
      (is (= 2 (count (failure-events ctx)))
          "exactly one failure event per EXECUTED attempt-set; the throttled
           request adds none — a skip is not a failure"))))

;; ---------------------------------------------------------------------------
;; Replay tolerance — a failure event replayed BEFORE any success must not
;; corrupt the budget fold (the CC-31 optionality lesson applied here)
;; ---------------------------------------------------------------------------
(deftest a-failure-recorded-before-any-success-still-folds-and-counts
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-throwing-reflection provider-rejection-message
        (consolidator/consolidate! ctx :tree-class target))
      (is (= 1 (ontology/get-recent-consolidation-count ctx :tree-class))
          "a store whose FIRST consolidation fact is a failure produces a
           well-formed budget count — no empty-state blowup, no misclassification")
      (is (nil? (ontology/get-description ctx :tree-class target))
          "and the failure wrote no description — 'never answered' stays a
           distinct fact from knowledge"))))

;; ---------------------------------------------------------------------------
;; CC-15 refit — upstream 5cce483d (preserve structured LLM failure evidence)
;; gives terminals a CLOSED :failure-kind. The classifier consumes it FIRST
;; (exact), keeping the regex heuristic only for legacy string-only shapes.
;; A structured failure is an exception-path terminal, so its attempt count
;; is the EXACT consumed budget — an upgrade from the old floor of 1 on the
;; parse family.
;; ---------------------------------------------------------------------------
(deftest structured-failure-kinds-classify-exactly
  (let [c consolidator/classify-reflection-failure
        full (inc @#'consolidator/reflection-max-retries)]
    (is (= {:reason :unparseable :attempts full}
           (c {:status :failure :error "irrelevant text" :failure-kind :tool-call-parsing-failed}))
        "parse failure: the provider ANSWERED; the kind, not the string, decides")
    (is (= {:reason :unparseable :attempts full}
           (c {:status :failure :error "x" :failure-kind :missing-forced-tool-call})))
    (is (= {:reason :unparseable :attempts full}
           (c {:status :failure :error "x" :failure-kind :schema-validation-failed})))
    (is (= {:reason :provider-rejected :attempts full}
           (c {:status :failure :error "x" :failure-kind :empty-provider-response})))
    (is (= {:reason :provider-rejected :attempts full}
           (c {:status :failure :failure-kind :transport-failure
               :error "prompt is too long: 1571414 tokens > 1048576 maximum"}))
        "transport failures keep the string check: a context-length rejection
         travels as an HTTP error")
    (is (= {:reason :retries-exhausted :attempts full}
           (c {:status :failure :failure-kind :transport-failure :error "connection reset"})))
    (is (= {:reason :retries-exhausted :attempts full}
           (c {:status :failure :error "x" :failure-kind :some-future-kind}))
        "an unknown kind maps to the honest superclass, never a minted class")
    (is (= {:reason :retries-exhausted :attempts full}
           (c {:status :failure :error "connection reset by peer"}))
        "legacy string-only shapes classify exactly as before the refit")
    (is (= {:reason :timeout :attempts 1}
           (c {:status :timeout :failure-kind :transport-failure}))
        "a deadline :status wins over any kind — the executor's own terminal")))

(deftest execute-reflection-carries-structured-ex-data
  ;; The seam under test is execute-reflection's CATCH: a structured
  ;; failure's ex-data ({:failure-kind :provider-evidence}) must survive
  ;; into the terminal shape the classifier reads. Tested at the seam
  ;; directly because the full executor path can legitimately convert a
  ;; retrying failure to :status :timeout when the execution deadline's
  ;; remaining budget undercuts the next backoff (executor.clj
  ;; deadline-before-backoff) — in which case :timeout IS the honest
  ;; classification and this seam never decides.
  (let [execute-reflection @#'consolidator/execute-reflection
        terminal (with-redefs [ai.obney.orc.orc-service.interface/execute
                               (fn [& _]
                                 (throw (ex-info "Tool call arguments could not be decoded"
                                                 {:failure-kind :tool-call-parsing-failed
                                                  :provider-evidence {:tool-call-present? true
                                                                      :finish-reason "stop"}})))]
                   (execute-reflection {} (random-uuid) {}))]
    (is (= :failure (:status terminal)))
    (is (= :tool-call-parsing-failed (:failure-kind terminal))
        "the structured kind survives the catch")
    (is (= {:tool-call-present? true :finish-reason "stop"}
           (:provider-evidence terminal))
        "the provider evidence survives the catch")
    (is (= {:reason :unparseable :attempts (inc @#'consolidator/reflection-max-retries)}
           (consolidator/classify-reflection-failure terminal))
        "and the classifier reads the kind, not the message string")))
