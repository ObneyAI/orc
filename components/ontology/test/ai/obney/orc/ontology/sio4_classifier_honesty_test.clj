(ns ai.obney.orc.ontology.sio4-classifier-honesty-test
  "SIO-4 — the failure classifier stops asserting counts it does not have.

   @invariant FailureIsVisible (ontology.allium, contract ClaimConsolidation)
   — a durable failure record carries the target, the reason class, and the
   ATTEMPT COUNT. CC-28 satisfied the letter and violated the intent: an
   exec-result carrying no :error at all still got stamped
   {:reason :retries-exhausted :attempts 4} — a count asserted on zero
   evidence. This narrows the CLAIM (the count) without weakening the
   GUARANTEE (a count is still always carried, and the reason set stays
   CLOSED — no new class is minted here).

   THE TERMINAL, IDENTIFIED (forensic, not guessed). Measured over the
   durable Postgres store (tenant a89f9f58…, 43
   :ontology/description-consolidation-failed events, 12 with no :error):

     * every one of the 12 error-less events is the LAST event before a gap
       that ends in :ontology/living-description-enabled-set — the harness's
       STARTUP marker. They are consolidations that were in flight when the
       orc-sessions harness JVM was stopped;
     * 6 ontology-consolidator-* ticks in that window emitted
       :sheet/tree-tick-started and NEVER a :sheet/tree-tick-completed —
       the in-flight ticks whose async pipeline died with the process;
     * NO consolidator sheet has a non-:success :sheet/tree-tick-completed
       before 2026-08-12T19:29Z, so these terminals cannot have come from a
       delivered tick result at all.

   The mechanism: grain's stop-tenant-poller calls
   ExecutorService.shutdownNow, which INTERRUPTS the worker thread running
   the :ontology/consolidate-on-request processor. That thread is blocked in
   runtime/execute's `(deref p timeout-ms ::timeout)`; the interrupt makes
   CountDownLatch.await throw java.lang.InterruptedException, whose
   .getMessage is NIL. execute-reflection's `(catch Exception e {:status
   :failure :error (.getMessage e)})` therefore yields {:status :failure
   :error nil} — and the classifier's :else branch stamped 4 consumed
   provider attempts on it. ZERO retries were exhausted; the executor never
   even returned.

   The honest count for a terminal that carries no evidence of the retry
   loop having run is the CERTAIN FLOOR of 1 — exactly the doctrine CC-28
   already applies to :timeout and the legacy :unparseable shape."
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
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
;; Harness — same shape as the CC-28 suite this slice narrows.
;; ---------------------------------------------------------------------------

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/sio4-test-" (random-uuid))
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
  "The 12 REAL error-less events, read off the durable Postgres store and
   written to EDN by the SIO-4 forensic (see the file's own header for the
   query). Not transcribed by hand."
  (-> "ai/obney/orc/ontology/fixtures/sio4_error_less_terminals.edn"
      io/resource slurp edn/read-string))

;; ---------------------------------------------------------------------------
;; RED 1 — the pure classifier: an error-less, kind-less, non-timeout terminal
;; must not assert a consumed retry budget.
;; ---------------------------------------------------------------------------

(deftest an-error-less-terminal-does-not-assert-a-consumed-retry-budget
  (let [c consolidator/classify-reflection-failure
        full (inc @#'consolidator/reflection-max-retries)]
    (is (= {:reason :retries-exhausted :attempts 1}
           (c {:status :failure}))
        "no :error, no :failure-kind, not a deadline — NOTHING in this
         terminal says the executor's retry loop ran, so the honest count is
         the certain floor of 1, never the full budget")
    (is (= {:reason :retries-exhausted :attempts 1}
           (c {:status :failure :error nil :failure-kind nil}))
        "explicit nils are the same absence of evidence")
    (is (= {:reason :retries-exhausted :attempts 1}
           (c {:status :failure :error "   "}))
        "a blank message is no evidence either")
    (is (contains? #{:provider-rejected :timeout :retries-exhausted :unparseable}
                   (:reason (c {:status :failure})))
        "the reason set stays CLOSED — SIO-4 narrows a count, it mints no class")
    (is (m/validate ontology-schemas/consolidation-failure-reason
                    (:reason (c {:status :failure})))
        "and the produced class validates against the spec's closed enum")
    ;; The guarantee this must NOT weaken: a terminal that DOES carry
    ;; evidence of the exception path still reports the full consumed budget.
    (is (= {:reason :retries-exhausted :attempts full}
           (c {:status :failure :error "connection reset by peer"}))
        "an error string is evidence the executor's retry loop ran to
         exhaustion — that claim is unchanged")
    (is (= {:reason :retries-exhausted :attempts full}
           (c {:status :failure :failure-kind :transport-failure}))
        "a structured kind is evidence too, even with no message")
    (is (= {:reason :timeout :attempts 1}
           (c {:status :timeout}))
        "the deadline terminal keeps its own floor")))

;; ---------------------------------------------------------------------------
;; RED 2 — replay the 12 REAL store-derived terminals.
;; ---------------------------------------------------------------------------

(deftest the-twelve-real-error-less-terminals-reclassify-to-the-certain-floor
  (is (= 12 (count store-derived-terminals))
        "the forensic's measured population: 12 of 43 durable failure events
         carry no :error at all")
  (is (every? #(false? (:error-key-present? %)) store-derived-terminals)
      "…and the store confirms the :error key is ABSENT, not empty")
  (is (every? #(false? (:failure-kind-key-present? %)) store-derived-terminals)
      "…and no :failure-kind rode any of them")
  (is (= #{{:reason :retries-exhausted :attempts 4}}
         (set (map (fn [{:keys [recorded-reason recorded-attempts]}]
                     {:reason recorded-reason :attempts recorded-attempts})
                   store-derived-terminals)))
      "what CC-28 actually wrote: 4 consumed provider attempts, on every one")
  (doseq [{:keys [terminal event-id granularity]} store-derived-terminals]
    (is (= {:reason :retries-exhausted :attempts 1}
           (consolidator/classify-reflection-failure terminal))
        (str "real event " event-id " (" granularity ") must reclassify to the "
             "certain floor — the retry budget was never exercised"))))

;; ---------------------------------------------------------------------------
;; RED 3 — end-to-end through the STORE, on the REAL identified terminal:
;; an interrupted reflection (InterruptedException, .getMessage nil).
;; ---------------------------------------------------------------------------

(deftest an-interrupted-reflection-records-the-floor-in-the-durable-event
  (with-test-ctx [ctx]
    ;; The exact shape the forensic identified: shutdownNow interrupts the
    ;; consolidation worker while it blocks on runtime/execute's deref.
    (with-redefs [ai.obney.orc.orc-service.interface/execute
                  (fn [& _] (throw (InterruptedException.)))]
      (consolidator/consolidate! ctx :node-type :llm))
    (let [evs (failure-events ctx)
          ev (first evs)]
      (is (= 1 (count evs))
          "FailureIsVisible still holds — the death certificate is durable")
      (is (= :node-type (:granularity ev)))
      (is (= :llm (:target-identifier ev)))
      (is (= :retries-exhausted (:reason ev))
          "the reason set stays closed: an unclassifiable terminal maps to the
           honest superclass, exactly as CC-28 documents")
      (is (= 1 (:attempts ev))
          "the DURABLE event carries the certain floor, not the fiction of 4")
      (is (not (contains? ev :error))
          "and no message is invented either — the omit-not-nil idiom holds"))))

(deftest an-interrupted-reflection-records-the-floor-on-the-claim-path-too
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-redefs [ai.obney.orc.orc-service.interface/execute
                    (fn [& _] (throw (InterruptedException.)))]
        (consolidator/consolidate! ctx :tree-class target))
      (let [ev (first (failure-events ctx))]
        (is (= :tree-class (:granularity ev)))
        (is (= target (:target-identifier ev)))
        (is (= :retries-exhausted (:reason ev)))
        (is (= 1 (:attempts ev))
            "the claim path records the same honest floor as the body path")))))

;; ---------------------------------------------------------------------------
;; The seam itself: an interrupt really does arrive as {:status :failure
;; :error nil}. Guards the premise the whole slice rests on.
;; ---------------------------------------------------------------------------

(deftest an-interrupt-reaches-the-classifier-with-no-error-at-all
  (let [execute-reflection @#'consolidator/execute-reflection
        terminal (with-redefs [ai.obney.orc.orc-service.interface/execute
                               (fn [& _] (throw (InterruptedException.)))]
                   (execute-reflection {} (random-uuid) {}))]
    (is (= :failure (:status terminal)))
    (is (nil? (:error terminal))
        "InterruptedException carries NO message — this is the terminal the
         forensic identified behind all 12 error-less durable events")
    (is (nil? (:failure-kind terminal))
        "and no structured kind rides it either")
    (is (= {:reason :retries-exhausted :attempts 1}
           (consolidator/classify-reflection-failure terminal)))))
