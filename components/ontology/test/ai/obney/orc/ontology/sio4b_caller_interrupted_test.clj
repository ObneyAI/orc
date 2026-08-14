(ns ai.obney.orc.ontology.sio4b-caller-interrupted-test
  "SIO-4b — caller interruption is a REASON CLASS, not an implied attempt count.

   @invariant FailureIsVisible (ontology.allium, contract ClaimConsolidation)
   — \"Produced no trustworthy knowledge\" (ClaimSetUnchanged), \"never
   answered\", and \"was interrupted before it could answer\" are THREE
   different facts and must be recorded as such.

   @invariant AttemptCountIsEvidenced — a terminal bearing neither an error
   message nor a structured failure kind evidences one attempt, never the
   full retry budget; and \"the reason class must carry the distinction
   rather than leaving it implied by an attempt count of 1.\"

   THE MEASURED INCIDENT (SIO-4's forensic, not guessed): 12 of 43 durable
   :ontology/description-consolidation-failed events asserted
   {:reason :retries-exhausted :attempts 4} on reflections where ZERO
   provider attempts happened. Stopping the harness JVM between batches →
   grain's stop-tenant-poller → ExecutorService.shutdownNow → the
   consolidation worker interrupted while blocked in runtime/execute's
   `deref` → java.lang.InterruptedException, whose .getMessage is NIL →
   execute-reflection's (catch Exception e) yields {:status :failure
   :error nil} → the classifier's evidence-free branch.

   SIO-4 fixed the COUNT (that terminal now stamps :attempts 1). It did NOT
   fix the CLASS: the durable store still said \"retries exhausted\" about
   events where nothing was retried — a false fact in the source of truth.
   This slice adds the fifth closed class :caller-interrupted so the record
   states what happened instead of leaving it implied by a count of 1.

   The boundary probe below is SIO-4's own, all seven rows. What proved that
   fix was that it fired exactly where evidence is absent and NOWHERE else;
   the same must hold for this one, so every neighbour is asserted unmoved."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas :as ontology-schemas]
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
            [malli.core :as m]))

;; ---------------------------------------------------------------------------
;; Harness — same shape as the SIO-4 suite this slice extends.
;; ---------------------------------------------------------------------------

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/sio4b-test-" (random-uuid))
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

(def ^:private store-derived-terminals
  "The 12 REAL error-less events, read off the durable Postgres store by the
   SIO-4 forensic (see the fixture's own header for the query and the
   derivation rule). Not transcribed by hand."
  (-> "ai/obney/orc/ontology/fixtures/sio4_error_less_terminals.edn"
      io/resource slurp edn/read-string))

;; ---------------------------------------------------------------------------
;; RED 1 — the closed set grows to five, and :caller-interrupted is in it.
;; ---------------------------------------------------------------------------

(deftest the-closed-reason-set-admits-caller-interruption
  (is (m/validate ontology-schemas/consolidation-failure-reason :caller-interrupted)
      "the spec's FailureIsVisible now names caller interruption as a fact the
       durable record must be able to state — the closed enum must admit it")
  (is (= #{:provider-rejected :timeout :retries-exhausted :unparseable
           :caller-interrupted}
         (set (rest ontology-schemas/consolidation-failure-reason)))
      "the set stays CLOSED at exactly five classes — this slice mints one
       named class, it does not open the enum")
  (is (not (m/validate ontology-schemas/consolidation-failure-reason :interrupted))
      "and no near-miss spelling sneaks in — a closed set that accepts
       synonyms is not closed"))

;; ---------------------------------------------------------------------------
;; RED 2 — SIO-4's boundary probe, all seven rows. The new class must fire
;; exactly where evidence is absent and NOWHERE else.
;; ---------------------------------------------------------------------------

(def ^:private boundary-probe
  "[label terminal expected] — SIO-4's own probe, the regression net that
   proved the last fix moved no neighbour. Row 1 and row 2 are what SIO-4b
   changes; rows 3-7 must be bit-identical to before."
  (let [full (inc @#'consolidator/reflection-max-retries)]
    [["interrupt shape (:error nil, no kind)"
      {:status :failure :error nil :failure-kind nil}
      {:reason :caller-interrupted :attempts 1}]
     ["blank-string error"
      {:status :failure :error "   "}
      {:reason :caller-interrupted :attempts 1}]
     ["real transport error message"
      {:status :failure :error "connection reset by peer"}
      {:reason :retries-exhausted :attempts full}]
     ["context/token rejection"
      {:status :failure :error "prompt is too long: 1571414 tokens > 1048576 maximum"}
      {:reason :provider-rejected :attempts full}]
     ["legacy unparseable string"
      {:status :failure :error "LLM output unparseable for keys [:summary]"}
      {:reason :unparseable :attempts 1}]
     ["structured kind, no message"
      {:status :failure :failure-kind :schema-validation-failed}
      {:reason :unparseable :attempts full}]
     [":timeout status"
      {:status :timeout :error nil :failure-kind nil}
      {:reason :timeout :attempts 1}]]))

(deftest the-boundary-probe-holds-on-every-neighbour
  (doseq [[label terminal expected] boundary-probe]
    (testing label
      (is (= expected (consolidator/classify-reflection-failure terminal))
          (str "boundary row: " label))
      (is (m/validate ontology-schemas/consolidation-failure-reason
                      (:reason (consolidator/classify-reflection-failure terminal)))
          (str "every produced class validates against the closed enum: " label)))))

(deftest an-absent-error-key-is-the-same-absence-as-an-explicit-nil
  (is (= {:reason :caller-interrupted :attempts 1}
         (consolidator/classify-reflection-failure {:status :failure}))
      "the durable 12 carry no :error KEY at all — key-absent and
       explicitly-nil are the same absence of evidence")
  (is (= {:reason :caller-interrupted :attempts 1}
         (consolidator/classify-reflection-failure {:status :failure :error ""}))
      "an empty string is no evidence either")
  (is (= {:reason :retries-exhausted :attempts (inc @#'consolidator/reflection-max-retries)}
         (consolidator/classify-reflection-failure
          {:status :failure :error "some error we have never seen before"}))
      ":retries-exhausted REMAINS the fallback for an unrecognised but
       EVIDENCED terminal — this slice takes only the evidence-less one away")
  (is (= {:reason :retries-exhausted :attempts (inc @#'consolidator/reflection-max-retries)}
         (consolidator/classify-reflection-failure
          {:status :failure :failure-kind :some-future-kind}))
      "…including an unknown structured kind, which IS evidence the exception
       path ran"))

;; ---------------------------------------------------------------------------
;; RED 3 — the 12 REAL store-derived terminals reclassify to the named class.
;; ---------------------------------------------------------------------------

(deftest the-twelve-real-error-less-terminals-reclassify-to-caller-interrupted
  (is (= 12 (count store-derived-terminals)))
  (is (= #{{:reason :retries-exhausted :attempts 4}}
         (set (map (fn [{:keys [recorded-reason recorded-attempts]}]
                     {:reason recorded-reason :attempts recorded-attempts})
                   store-derived-terminals)))
      "what CC-28 actually wrote to the durable store: 'retries exhausted, 4
       consumed' on every one — the false fact this arc corrects")
  (doseq [{:keys [terminal event-id granularity]} store-derived-terminals]
    (is (= {:reason :caller-interrupted :attempts 1}
           (consolidator/classify-reflection-failure terminal))
        (str "real event " event-id " (" granularity ") is a caller interruption
              stated as such, not a retry budget that was never exercised"))))

;; ---------------------------------------------------------------------------
;; RED 4 — end-to-end through the STORE. The command and event schemas must
;; both accept the fifth class, or the death certificate never lands.
;; ---------------------------------------------------------------------------

(deftest an-interrupted-reflection-records-caller-interrupted-on-the-body-path
  (with-test-ctx [ctx]
    (with-redefs [ai.obney.orc.orc-service.interface/execute
                  (fn [& _] (throw (InterruptedException.)))]
      (consolidator/consolidate! ctx :node-type :llm))
    (let [evs (failure-events ctx)
          ev (first evs)]
      (is (= 1 (count evs))
          "FailureIsVisible still holds — the death certificate is durable")
      (is (= :node-type (:granularity ev)))
      (is (= :llm (:target-identifier ev)))
      (is (= :caller-interrupted (:reason ev))
          "the durable record now STATES the interruption instead of asserting
           a retry exhaustion that never happened")
      (is (= 1 (:attempts ev))
          "and the count SIO-4 corrected stays corrected")
      (is (not (contains? ev :error))
          "no message is invented either — the omit-not-nil idiom holds"))))

(deftest an-interrupted-reflection-records-caller-interrupted-on-the-claim-path
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-redefs [ai.obney.orc.orc-service.interface/execute
                    (fn [& _] (throw (InterruptedException.)))]
        (consolidator/consolidate! ctx :tree-class target))
      (let [ev (first (failure-events ctx))]
        (is (= :tree-class (:granularity ev)))
        (is (= target (:target-identifier ev)))
        (is (= :caller-interrupted (:reason ev))
            "both reflection paths record the same named class")
        (is (= 1 (:attempts ev)))))))

;; ---------------------------------------------------------------------------
;; RED 5 — the read-model path. FailuresConsumeBudget folds the failure event
;; by TYPE, not by reason; a grown reason set must not change that.
;; ---------------------------------------------------------------------------

(deftest a-caller-interrupted-failure-still-consumes-the-hourly-budget
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-redefs [ai.obney.orc.orc-service.interface/execute
                    (fn [& _] (throw (InterruptedException.)))]
        (consolidator/consolidate! ctx :tree-class target))
      (is (= :caller-interrupted (:reason (first (failure-events ctx)))))
      (is (= 1 (ontology/get-recent-consolidation-count ctx :tree-class))
          "FailuresConsumeBudget is folded on event TYPE — the fifth reason
           class must not make a dying target read as idle"))))

;; ---------------------------------------------------------------------------
;; The seam the whole slice rests on: an interrupt really does arrive with
;; no error and no kind.
;; ---------------------------------------------------------------------------

(deftest an-interrupt-reaches-the-classifier-with-no-evidence-at-all
  (let [execute-reflection @#'consolidator/execute-reflection
        terminal (with-redefs [ai.obney.orc.orc-service.interface/execute
                               (fn [& _] (throw (InterruptedException.)))]
                   (execute-reflection {} (random-uuid) {}))]
    (is (= :failure (:status terminal)))
    (is (nil? (:error terminal))
        "InterruptedException carries NO message — the terminal behind all 12")
    (is (nil? (:failure-kind terminal)))
    (is (= {:reason :caller-interrupted :attempts 1}
           (consolidator/classify-reflection-failure terminal)))))
