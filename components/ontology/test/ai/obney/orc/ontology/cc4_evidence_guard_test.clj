(ns ai.obney.orc.ontology.cc4-evidence-guard-test
  "CC-4 — a starved judge's score never becomes a claim; a stale
   consolidation's refusal becomes observable (ADRs 0023, 0021).

   PROPAGATED FROM SPEC: specs/ontology.allium. CONTRACT — never weaken a test
   to make it pass; report it as a finding instead.

   Obligations covered (allium plan ids):
     rule-failure.RecordClaimDeltas.1        (the evidence_is_grounded precondition)
     rule-success.ExcludeAbstainedEvaluations
     rule-success.RefuseStaleClaimDeltas     (the `ensures` half — the CAS half
                                              is already covered by CC-1)

   THE MOTIVATING INCIDENT, encoded as a fixture: three grounding judges scored
   [1.0 0.0 0.0] on ONE response with ONE empty input context — they differed
   only in their stance toward missing evidence — and those zeros were then
   consolidated into a living description as a genuine weakness. The pipeline
   had no concept of starvation at any point."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
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
        cache-dir (str "/tmp/cc4-test-" (random-uuid))
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

;; ---------------------------------------------------------------------------
;; Fixtures: an occurrence is a [sheet-id tick-id] pair; a judge score may be
;; GROUNDED (real feedback citing real evidence) or STARVED (the gap3 shape:
;; a score produced against an empty input context).
;; ---------------------------------------------------------------------------
(defn- judge-score!
  [ctx sheet-id tick-id judge-name score feedback]
  (cp/process-command
    (assoc ctx :command
           {:command/name :evaluation/record-judge-score
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :sheet-id sheet-id
            :node-id (random-uuid)
            :tick-id tick-id
            :judge-name judge-name
            :judge-config {}
            :score score
            :feedback feedback
            :dimensions []})))

(defn- grounded-occurrence!
  "A turn whose judge scored against real evidence."
  [ctx]
  (let [sheet (random-uuid) tick (random-uuid)]
    (judge-score! ctx sheet tick "grounding" 0.9
                  "The response cites PNFS-2025-EMP and the 20% bonus, both present in the source document.")
    [sheet tick]))

(defn- starved-occurrence!
  "The gap3 shape: the judge scored 0.0 BECAUSE its input context was empty —
   an evaluation artifact, not a detected defect."
  [ctx]
  (let [sheet (random-uuid) tick (random-uuid)]
    (judge-score! ctx sheet tick "grounding" 0.0
                  "The 'Input Context' provided in the prompt was empty (json { }). Therefore any specific claims are entirely ungrounded relative to the provided context.")
    [sheet tick]))

(defn- delta [op overrides]
  (merge {:operation op :kind :weakness :content "a claim"
          :episodes [] :from-legacy-corpus false}
         overrides))

(defn- record! [ctx target deltas]
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/record-claim-deltas
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :granularity :tree-class
            :target-identifier target
            :deltas deltas
            :claim-set-version (ontology/get-claim-set-version ctx :tree-class target)})))

(defn- claims [ctx target] (ontology/get-claims ctx :tree-class target))

;; ---------------------------------------------------------------------------
;; rule-failure.RecordClaimDeltas.1 — the evidence_is_grounded precondition
;; ---------------------------------------------------------------------------
(deftest a-claim-derived-from-starved-evidence-is-excluded
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          starved (starved-occurrence! ctx)]
      (record! ctx target
               [(delta :add {:content "vulnerability to zero-score grounding"
                             :episodes [starved]})])
      (is (empty? (claims ctx target))
          "the gap3 incident, prevented: a weakness derived ONLY from a starved
           evaluation must never become a durable claim"))))

(deftest the-deterministic-layer-catches-the-starved-shape-with-no-model-call
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          starved (starved-occurrence! ctx)
          calls (atom 0)]
      ;; Any LLM path must be untouched for this shape — the cheap check owns it.
      (with-redefs [llm/predict (fn [& _] (swap! calls inc) {:outputs {}})]
        (record! ctx target [(delta :add {:content "starved-derived claim"
                                          :episodes [starved]})]))
      (is (empty? (claims ctx target)) "excluded")
      (is (zero? @calls)
          "the deterministic check must settle this shape without a model call"))))

(deftest well-grounded-evidence-passes-through-untouched
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          good (grounded-occurrence! ctx)]
      (record! ctx target [(delta :add {:content "verifies with a real command"
                                        :kind :strength
                                        :episodes [good]})])
      (is (= 1 (count (claims ctx target)))
          "the guard must not suppress real signal — false positives here
           starve the loop just as surely as the old validator did"))))

(deftest a-mixed-batch-keeps-the-grounded-deltas-and-drops-only-the-starved-one
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          good (grounded-occurrence! ctx)
          starved (starved-occurrence! ctx)]
      (record! ctx target
               [(delta :add {:content "grounded insight" :episodes [good]})
                (delta :add {:content "starved insight" :episodes [starved]})])
      (let [contents (set (map :content (claims ctx target)))]
        (is (contains? contents "grounded insight"))
        (is (not (contains? contents "starved insight"))
            "exclusion is per-delta — one starved delta must not reject the
             whole batch (that would be the starvation failure mode again)")))))

(deftest exclusions-are-recorded-not-silently-dropped
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          starved (starved-occurrence! ctx)]
      (record! ctx target [(delta :add {:content "starved insight" :episodes [starved]})])
      (let [excluded (ontology/get-excluded-evidence ctx :tree-class target)]
        (is (seq excluded)
            "a dropped delta must leave a trace — silent exclusion is how we
             lose the ability to measure how much evidence was starved")
        (is (some? (:reason (first excluded)))
            "the exclusion carries a reason, so the drop can be diagnosed")
        (is (some? (:episodes (first excluded)))
            "and names the occurrence whose evidence could not be trusted")))))

;; ---------------------------------------------------------------------------
;; rule-success.ExcludeAbstainedEvaluations — no score means NOT judged
;; ---------------------------------------------------------------------------
(deftest an-evaluation-carrying-no-score-is-skipped-not-treated-as-zero
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          sheet (random-uuid) tick (random-uuid)]
      ;; An occurrence with NO judge score at all stands in for abstention
      ;; until the engine-side :no-evidence outcome lands (ADR 0023 is a
      ;; proposal to the maintainer; the ontology-side guard ships regardless).
      (record! ctx target [(delta :add {:content "claim from an unjudged turn"
                                        :episodes [[sheet tick]]})])
      (is (empty? (claims ctx target))
          "an unjudged occurrence is not evidence — it must not be read as a
           zero, which is exactly the conflation ADR 0023 exists to end"))))

;; ---------------------------------------------------------------------------
;; rule-success.RefuseStaleClaimDeltas — the `ensures` half
;; ---------------------------------------------------------------------------
(deftest a-stale-consolidation-emits-an-observable-refusal-fact
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          good (grounded-occurrence! ctx)
          stale (ontology/get-claim-set-version ctx :tree-class target)]
      (record! ctx target [(delta :add {:content "the writer that won" :episodes [good]})])
      ;; second consolidation still holding the stale version
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/record-claim-deltas
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :granularity :tree-class
                :target-identifier target
                :deltas [(delta :add {:content "the stale writer" :episodes [good]})]
                :claim-set-version stale}))
      (let [refusals (ontology/get-claim-delta-refusals ctx :tree-class target)]
        (is (seq refusals)
            "a refusal must be readable from the projection — a refusal
             observable only as a return value cannot be asserted on")
        (let [r (first refusals)]
          (is (= :stale-claim-set (:reason r)))
          (is (some? (:attempted-version r)) "carries the version the loser had read")
          (is (some? (:current-version r)) "and the version that had won")))
      (is (= ["the writer that won"] (map :content (claims ctx target)))
          "state is unchanged by the refused consolidation"))))
