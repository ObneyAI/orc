(ns ai.obney.orc.ontology.cc5-consolidator-deltas-test
  "CC-5 — the consolidator emits DELTAS; the anti-recency validator is retired
   (ADR 0021).

   PROPAGATED FROM SPEC: specs/ontology.allium (tended after the mid-arc weed
   sweep). CONTRACT — never weaken a test to make it pass; report it instead.

   Obligations covered (allium plan ids):
     rule-success.RecordClaimDeltas        rule-failure.RecordClaimDeltas.1/.2
     rule-success.ExcludeUngroundedDelta   rule-failure.ExcludeUngroundedDelta.1
     rule-success.RecordNothingWhenAllDeltasExcluded (+ .1/.2)
     config-default.initial_claim_support

   THIS IS THE SLICE THAT MAKES THE PIPELINE REAL. Until it lands the claim
   store has no production caller: the live consolidator still writes whole
   bodies through the legacy commands and the anti-recency validator is still
   running — the very valve ADR 0021 says was retired.

   The reflection LLM is STUBBED here so the contract is deterministic. Live
   verification with a real model is the orchestrator's QA, not this file's job."
  (:require [clojure.test :refer [deftest testing is]]
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
        cache-dir (str "/tmp/cc5-test-" (random-uuid))
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

(defn- episode [] [(random-uuid) (random-uuid)])

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


;; ---------------------------------------------------------------------------
;; PRODUCTION-FAITHFUL EVIDENCE WINDOW (orchestrator fix, after CC-5 reported
;; six of these tests unsatisfiable as written).
;;
;; These tests dispatched consolidations against a store holding NO observations
;; and NO judge evidence, then required claims to land — so CC-4's guard
;; correctly excluded everything, and one test additionally demanded supporting
;; episodes from an EMPTY window, which no implementation could satisfy. This is
;; the CC-4 fixture lesson one layer up: it is not enough to ground a delta's
;; episodes when the consolidator reads its own evidence window from the store.
;; ---------------------------------------------------------------------------
(defn- observe!
  "Record ONE real occurrence for the target plus a substantive judge score
   against it. Returns the [sheet-id tick-id] occurrence pair."
  [ctx tree-class-id]
  (let [sheet-id (random-uuid) tick-id (random-uuid)]
    (cp/process-command
      (assoc ctx :command
             {:command/name :ontology/assign-task-class
              :command/id (random-uuid) :command/timestamp (time/now)
              :source-sheet-id sheet-id :source-tick-id tick-id
              :source-node-id (random-uuid)
              :assigned-tree-id tree-class-id :confidence 0.95
              :top-candidates [] :reasoning "test observation"
              :was-fresh-mint? false}))
    (ground-episodes! ctx [[sheet-id tick-id]])
    [sheet-id tick-id]))

(defn- with-window!
  "Seed n grounded observations so the consolidator has real evidence to read."
  [ctx tree-class-id n]
  (mapv (fn [_] (observe! ctx tree-class-id)) (range n)))

(defn- record! [ctx target deltas]
  (ground-episodes! ctx (mapcat :episodes deltas))
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/record-claim-deltas
            :command/id (random-uuid) :command/timestamp (time/now)
            :granularity :tree-class :target-identifier target :deltas deltas
            :claim-set-version (ontology/get-claim-set-version ctx :tree-class target)})))

(defn- claims [ctx target] (ontology/get-claims ctx :tree-class target))

(defmacro with-reflection
  "Stub the reflection LLM to return a fixed operation list."
  [ops & body]
  `(with-redefs [llm/predict (fn [& _#]
                                  {:outputs {:operations ~ops}
                                   :usage {:total-tokens 1} :model "stub"})]
     ~@body))

;; ---------------------------------------------------------------------------
;; config-default.initial_claim_support — the contradiction cascade (P-A)
;; ---------------------------------------------------------------------------
(deftest a-fresh-claim-survives-one-contradiction-and-dies-on-the-second
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          ep (episode)]
      (record! ctx target [{:operation :add :kind :weakness
                            :content "a fresh insight" :episodes [ep]
                            :from-legacy-corpus false}])
      (let [c (first (claims ctx target))]
        (is (= 2 (:support c))
            "a claim is SEEDED above the retirement floor — at a seed of 1 a
             single contradiction erases it, which contradicts ADR 0021's own
             property that no single judgement can erase knowledge"))
      (let [cid (:claim-id (first (claims ctx target)))]
        (record! ctx target [{:operation :contradict :target-claim cid
                              :kind :weakness :content "a fresh insight"
                              :episodes [(episode)] :from-legacy-corpus false}])
        (is (seq (claims ctx target))
            "ONE contradiction weakens but does not erase")
        (record! ctx target [{:operation :contradict :target-claim cid
                              :kind :weakness :content "a fresh insight"
                              :episodes [(episode)] :from-legacy-corpus false}])
        (is (empty? (claims ctx target))
            "accumulated contradiction retires it — that is what 'accumulated'
             means")))))

;; ---------------------------------------------------------------------------
;; rule-success.RecordClaimDeltas — a real consolidation lands claims
;; ---------------------------------------------------------------------------
(deftest a-consolidation-emits-operations-that-land-as-claims
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-window! ctx target 6)
      (with-reflection [{:operation :add :kind :weakness
                         :content "claims success on an empty diff"}
                        {:operation :add :kind :strength
                         :content "verifies with a real command"}]
        (consolidator/consolidate! ctx :tree-class target))
      (let [cs (claims ctx target)]
        (is (= 2 (count cs)) "the consolidation's operations became claims")
        (is (= #{"claims success on an empty diff" "verifies with a real command"}
               (set (map :content cs))))
        (is (every? #(seq (:supporting-episodes %)) cs)
            "CC-5 fills episodes in code from the evidence window — it never
             asks the model for occurrence ids, which is what keeps
             id-fabrication at zero")))))

;; ---------------------------------------------------------------------------
;; The core property: a rephrasing STRENGTHENS rather than duplicating
;; ---------------------------------------------------------------------------
(deftest rephrasing-an-existing-insight-reinforces-it-instead-of-duplicating
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-window! ctx target 6)
      (with-reflection [{:operation :add :kind :weakness
                         :content "hallucinates write success on empty diffs"}]
        (consolidator/consolidate! ctx :tree-class target))
      (let [cid (:claim-id (first (claims ctx target)))
            support-before (:support (first (claims ctx target)))]
        (with-reflection [{:operation :support :target-claim cid
                           :content "hallucinates write success on empty diffs"}]
          (consolidator/consolidate! ctx :tree-class target))
        (let [cs (claims ctx target)]
          (is (= 1 (count cs))
              "the insight the old validator would have rejected for wording now
               strengthens the claim it matches")
          (is (> (:support (first cs)) support-before)))))))

;; ---------------------------------------------------------------------------
;; P-A finding: unknown keys must be rejected, not silently dropped
;; ---------------------------------------------------------------------------
(deftest an-operation-carrying-an-unknown-key-is-rejected-not-silently-dropped
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-window! ctx target 6)
      ;; :guard is not a claim-delta key. The schema is an OPEN malli map, so
      ;; it validates and the data vanishes without a word.
      ;; A VALID operation rides alongside the drifted one, so this test cannot
      ;; pass by "nothing happened" — it previously did exactly that.
      (with-reflection [{:operation :add :kind :strength
                         :content "a well-formed claim"}
                        {:operation :add :kind :weakness
                         :content "a claim with drifted fields"
                         :guard "this text disappears silently today"}]
        (consolidator/consolidate! ctx :tree-class target))
      (let [cs (claims ctx target)
            drifted (first (filter #(= "a claim with drifted fields" (:content %)) cs))]
        (is (some #(= "a well-formed claim" (:content %)) cs)
            "the valid operation in the same batch still lands")
        (is (or (nil? drifted) (some? (:context-guard drifted)))
            "and the drifted operation is either refused outright or its key is
             mapped to the real one — what must NOT happen is the value being
             silently discarded, which is what the OPEN malli schema does today")))))

;; ---------------------------------------------------------------------------
;; P-A finding: repeated targets in one batch must not double-count support
;; ---------------------------------------------------------------------------
(deftest operations-are-collapsed-per-target-so-one-occurrence-counts-once
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-window! ctx target 6)
      (with-reflection [{:operation :add :kind :weakness :content "an insight"}]
        (consolidator/consolidate! ctx :tree-class target))
      (let [cid (:claim-id (first (claims ctx target)))
            before (:support (first (claims ctx target)))]
        (with-reflection [{:operation :support :target-claim cid :content "an insight"}
                          {:operation :edit :target-claim cid :content "an insight, reworded"}]
          (consolidator/consolidate! ctx :tree-class target))
        (is (= (inc before) (:support (first (claims ctx target))))
            "one consolidation is ONE occurrence: repeating a target in its
             operation list must not inflate support, which would feed straight
             into the validation threshold and the enforcement weighting")))))

;; ---------------------------------------------------------------------------
;; rule-success.ExcludeUngroundedDelta — per-delta, never per-batch
;; ---------------------------------------------------------------------------
(deftest an-ungrounded-operation-is-excluded-without-rejecting-the-batch
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          good (first (with-window! ctx target 6))]
      (with-reflection [{:operation :add :kind :strength
                         :content "grounded insight" :episodes [good]}
                        {:operation :add :kind :weakness
                         :content "ungrounded insight" :episodes [(episode)]}]
        (consolidator/consolidate! ctx :tree-class target))
      (let [contents (set (map :content (claims ctx target)))
            excluded (ontology/get-excluded-evidence ctx :tree-class target)]
        (is (contains? contents "grounded insight"))
        (is (not (contains? contents "ungrounded insight")))
        (is (seq excluded) "and the exclusion is recorded, not silent")))))

;; ---------------------------------------------------------------------------
;; THE RETIREMENT: no whole-body write, no validator
;; ---------------------------------------------------------------------------
(deftest the-consolidator-no-longer-writes-whole-description-bodies
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-window! ctx target 6)
      (with-reflection [{:operation :add :kind :weakness :content "an insight"}]
        (consolidator/consolidate! ctx :tree-class target))
      (let [evs (into [] (es/read (:event-store ctx)
                                  {:types #{:ontology/tree-class-description-updated}
                                   :tenant-id (:tenant-id ctx)}))]
        ;; NON-VACUITY GUARD: this test passed before implementation for the
        ;; WRONG reason — the stub's shape failed the old validator, so the
        ;; consolidation aborted before emitting anything and "no body event"
        ;; was trivially true. Requiring the claim to have landed means it can
        ;; only pass if the delta path actually ran.
        (is (= 1 (count (claims ctx target)))
            "the consolidation must have actually produced a claim")
        (is (empty? evs)
            "AND written no whole body — the legacy path is RETIRED for the
             claim path; two writers on one slot means a legacy consolidation
             silently overwrites an assembled body while leaving its claims
             intact")))))

(deftest no-consolidation-is-ever-refused-for-wording
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-window! ctx target 6)
      ;; Establish a well-supported claim, then have the model reword it —
      ;; the exact shape the anti-recency validator rejected 145 times.
      (with-reflection [{:operation :add :kind :weakness
                         :content "falsely claims verification success when commands fail"}]
        (consolidator/consolidate! ctx :tree-class target))
      (let [cid (:claim-id (first (claims ctx target)))]
        (with-reflection [{:operation :edit :target-claim cid :kind :weakness
                           :content "hallucinates successful verification when commands fail or are skipped"}]
          (consolidator/consolidate! ctx :tree-class target))
        (let [rejections (into [] (es/read (:event-store ctx)
                                           {:types #{:ontology/anti-recency-rejection}
                                            :tenant-id (:tenant-id ctx)}))]
          (is (empty? rejections)
              "the valve is gone: 145 of 145 real consolidations were rejected
               for rewording, and ~90% of those rejections were wrong")
          (is (= "hallucinates successful verification when commands fail or are skipped"
                 (:content (first (claims ctx target))))
              "the rewording LANDED"))))))

;; ---------------------------------------------------------------------------
;; CC-31 — model provenance on the claim-delta path
;;
;; The retained description path threads :model-provenance (the completion's
;; trace-id / model / usage) onto its events; the claim path did not (TRACKED
;; GAP CC-31, specs/ontology.allium). Claims accrue across many
;; consolidations — diagnosing model drift needs to know which model proposed
;; each insight. Asserted on the EVENT read back from the store, never on a
;; return value.
;; ---------------------------------------------------------------------------
(deftest recorded-claim-deltas-carry-the-proposing-completions-model-provenance
  (with-test-ctx [ctx]
    ;; Pinned exactly as a provenance-sensitive production caller pins it —
    ;; the same :ontology-consolidator-model override the body path honors.
    ;; The node's :model is what the engine stamps onto the completion event,
    ;; and that stamp is what the recorded deltas' provenance is read from.
    (let [ctx (assoc ctx :ontology-consolidator-model "stub-model")
          target (random-uuid)]
      (with-window! ctx target 6)
      (with-reflection [{:operation :add :kind :weakness
                         :content "claims success on an empty diff"}]
        (consolidator/consolidate! ctx :tree-class target))
      (let [evs (into [] (es/read (:event-store ctx)
                                  {:types #{:ontology/claim-deltas-recorded}
                                   :tenant-id (:tenant-id ctx)}))
            prov (:model-provenance (first evs))]
        (is (= 1 (count evs))
            "the consolidation recorded exactly one delta batch")
        (is (some? prov)
            "the RECORDED event carries the provenance of the completion that
             proposed the deltas — same shape the description events carry")
        (is (= "stub-model" (:model prov))
            "the model is the one the reflection call was pinned to")
        (is (uuid? (:trace-id prov))
            "the trace-id pins the exact execution that proposed them")
        (is (map? (:usage prov)))))))

(deftest deltas-recorded-without-a-model-still-record-and-fold
  ;; The field is OPTIONAL, and that is load-bearing twice over: every
  ;; pre-CC-31 event in the store lacks it and must replay, and direct
  ;; writers (the CV-2 emitted-DSL enrichment, CC-9d authored claims, the
  ;; legacy-body backfill) record deltas no LLM proposed, so there is no
  ;; model to attribute. This event is byte-shaped like a pre-CC-31 one.
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          ep (episode)]
      (record! ctx target [{:operation :add :kind :weakness
                            :content "an authored insight" :episodes [ep]
                            :from-legacy-corpus false}])
      (let [evs (into [] (es/read (:event-store ctx)
                                  {:types #{:ontology/claim-deltas-recorded}
                                   :tenant-id (:tenant-id ctx)}))]
        (is (= 1 (count evs)) "the provenance-less write was accepted")
        (is (not (contains? (first evs) :model-provenance))
            "a writer with no model produces an event with NO provenance
             field — identical in shape to every event recorded before the
             field existed")
        (is (= 1 (count (claims ctx target)))
            "and the projection folds it exactly as it folds old events")))))
