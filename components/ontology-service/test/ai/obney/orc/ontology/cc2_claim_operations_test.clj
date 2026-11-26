(ns ai.obney.orc.ontology.cc2-claim-operations-test
  "CC-2 — the full claim operation set: support, contradict, edit, retire,
   stale refusal (ADR 0021).

   PROPAGATED FROM SPEC: specs/ontology.allium. These tests are CONTRACT.
   Never weaken a test to make it pass — fix the spec and re-propagate.

   Obligations covered (allium plan ids):
     rule-success.ReinforceClaim            rule-failure.ReinforceClaim.1
     rule-success.ContradictClaim           rule-failure.ContradictClaim.1/.2
     rule-success.RetireExhaustedClaim      rule-failure.RetireExhaustedClaim.1/.2
     rule-success.RefuseStaleClaimDeltas    rule-failure.RefuseStaleClaimDeltas.1
     transition-rejected.Claim.status
     invariant.ClaimsAreRetiredOnlyWhenUnsupported
     invariant.ClaimsCarryResolvableProvenance
     enum-comparable.ClaimOperation         value-equality.ClaimDelta

   Written against CC-1's REAL produced API (see
   doc/build-timeline/handoffs/CC-1-PRODUCED-API.md in orc-sessions):
   claims carry a :claim-id string; the claim-set version IS the count of
   claim-delta events for the target; a stale version returns a
   :cognitect.anomalies/conflict."
  (:require [ai.obney.orc.ontology.core.read-models :as read-models]
            [ai.obney.orc.ontology.core.commands :as commands]
            [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
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
        cache-dir (str "/tmp/cc2-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)
        base-ctx {:event-store event-store
                  :cache cache
                  :tenant-id tenant-id
                  :event-pubsub ps
                  :command-registry (cp/global-command-registry)
                  :query-registry (qp/global-query-registry)
                  ::cache-dir cache-dir}
        processors (reduce-kv
                     (fn [acc proc-name {:keys [handler-fn topics]}]
                       (assoc acc proc-name
                              (tp/start {:event-pubsub ps :topics topics
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
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)]
     (try ~@body (finally (stop-context ~sym)))))

(defn- episode [] [(random-uuid) (random-uuid)])

(defn- delta [op overrides]
  (merge {:operation op
          :kind :weakness
          :content "a claim"
          :episodes [(episode)]
          :from-legacy-corpus false}
         overrides))


(defn- ground-episodes!
  "Seed substantive judge evidence for every occurrence the deltas cite."
  [ctx deltas]
  (doseq [[sheet-id tick-id] (distinct (mapcat :episodes deltas))]
    (cp/process-command
      (assoc ctx :command
             {:command/name :evaluation/record-judge-score
              :command/id (random-uuid)
              :command/timestamp (time/now)
              :sheet-id sheet-id
              :node-id (random-uuid)
              :tick-id tick-id
              :judge-name "coding-outcome"
              :judge-config {}
              :score 0.8
              :feedback (str "The turn applied the edit to src/util.clj and the "
                             "verification command exited 0, so the assessment is "
                             "grounded in the observed diff and command output.")
              :dimensions []}))))

(defn- record!
  "Dispatch deltas at the CURRENT claim-set version. Returns the raw result so
   refusal tests can inspect the anomaly; assert state via the projection."
  ([ctx target deltas] (record! ctx target deltas
                                (ontology/get-claim-set-version ctx :tree-class target)))
  ([ctx target deltas version]
   (ground-episodes! ctx deltas)
   (cp/process-command
     (assoc ctx :command
            {:command/name :ontology/record-claim-deltas
             :command/id (random-uuid)
             :command/timestamp (time/now)
             :granularity :tree-class
             :target-identifier target
             :deltas deltas
             :claim-set-version version}))))

(defn- claims [ctx target] (ontology/get-claims ctx :tree-class target))

(defn- seed-claim!
  "Create one claim and return its :claim-id."
  ([ctx target] (seed-claim! ctx target "seeded insight"))
  ([ctx target content]
   (record! ctx target [(delta :add {:content content})])
   (:claim-id (first (filter #(= content (:content %)) (claims ctx target))))))

(defn- support-of [ctx target claim-id]
  (:support (first (filter #(= claim-id (:claim-id %)) (claims ctx target)))))

(deftest support-increments-and-appends-the-episode
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          cid (seed-claim! ctx target)
          before (support-of ctx target cid)
          ep (episode)]
      (record! ctx target [(delta :support {:target-claim cid :episodes [ep]})])
      (let [c (first (filter #(= cid (:claim-id %)) (claims ctx target)))]
        (is (= (inc before) (:support c)) "support increments")
        (is (some #{ep} (:supporting-episodes c))
            "the reinforcing episode is retained as evidence")
        (is (= 1 (count (claims ctx target)))
            "reinforcement updates in place — it does not create a second claim")))))

(deftest edit-may-change-content-and-kind-while-support-survives
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          cid (seed-claim! ctx target "hallucinates write success")
          _ (record! ctx target [(delta :support {:target-claim cid})])
          before (support-of ctx target cid)]
      (record! ctx target [(delta :edit {:target-claim cid
                                         :kind :guard
                                         :content "avoid when the diff is empty"})])
      (let [c (first (filter #(= cid (:claim-id %)) (claims ctx target)))]
        (is (= "avoid when the diff is empty" (:content c)) "content is refreshed")
        (is (= :guard (:kind c))
            "kind is MUTABLE — an insight migrating between sections keeps its identity")
        (is (= (inc before) (:support c))
            "migrating between kinds does NOT cost the claim its accumulated support")
        (is (= 1 (count (claims ctx target))))))))

(deftest contradict-decrements-and-records-the-contradicting-episode
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          cid (seed-claim! ctx target)
          _ (record! ctx target [(delta :support {:target-claim cid})])
          _ (record! ctx target [(delta :support {:target-claim cid})])
          before (support-of ctx target cid)
          ep (episode)]
      (record! ctx target [(delta :contradict {:target-claim cid :episodes [ep]})])
      (let [c (first (filter #(= cid (:claim-id %)) (claims ctx target)))]
        (is (= (dec before) (:support c)) "support decrements")
        (is (some #{ep} (:contradicting-episodes c))
            "disagreement is RECORDED, not resolved by whoever wrote last")
        (is (not (some #{ep} (:supporting-episodes c)))
            "a contradicting episode is not also counted as support")))))

(deftest a-claim-is-retired-only-when-its-support-is-exhausted
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          cid (seed-claim! ctx target "contested insight")]
      (testing "a fresh claim is seeded above the retirement floor"
        (is (= 2 (support-of ctx target cid)) "seeded at 2, above the floor")
        (record! ctx target [(delta :contradict {:target-claim cid})])
        (is (seq (filter #(= cid (:claim-id %)) (claims ctx target)))
            "one contradiction weakens but does not erase")
        (record! ctx target [(delta :contradict {:target-claim cid})])
        (is (empty? (filter #(= cid (:claim-id %)) (claims ctx target)))
            "accumulated contradiction retires it"))
      (testing "a well-supported claim survives a single contradiction"
        (let [cid2 (seed-claim! ctx target "well-supported insight")]
          (record! ctx target [(delta :support {:target-claim cid2})])
          (record! ctx target [(delta :support {:target-claim cid2})])
          (record! ctx target [(delta :contradict {:target-claim cid2})])
          (is (some #(= cid2 (:claim-id %)) (claims ctx target))
              "accumulated support means one disagreement cannot delete knowledge"))))))

(deftest deletion-is-not-an-expressible-operation
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          cid (seed-claim! ctx target)]
      (doseq [bogus [:delete :remove :retire :destroy]]
        (record! ctx target [(delta bogus {:target-claim cid})]))
      (is (some #(= cid (:claim-id %)) (claims ctx target))
          "no operation outside the declared set can remove a claim"))))

(deftest no-visible-claim-ever-has-non-positive-support
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          survivor (seed-claim! ctx target "well-supported survivor")]
      (dotimes [_ 4] (record! ctx target [(delta :support {:target-claim survivor})]))
      (dotimes [i 3] (seed-claim! ctx target (str "fragile insight " i)))
      (doseq [round [1 2]]
        (doseq [c (claims ctx target)]
          (record! ctx target [(delta :contradict {:target-claim (:claim-id c)})]))
        (is (seq (claims ctx target))
            (str "round " round ": the survivor must still be visible, or this "
                 "invariant is being asserted over an empty set"))
        (is (every? #(pos? (:support %)) (claims ctx target))
            "the invariant holds after every operation sequence"))
      (is (some #(= survivor (:claim-id %)) (claims ctx target))
          "a well-supported claim outlives repeated contradiction"))))

(deftest a-consolidation-that-read-a-stale-claim-set-is-refused-and-can-retry
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          stale (ontology/get-claim-set-version ctx :tree-class target)]
      (record! ctx target [(delta :add {:content "the writer that won"})] stale)
      (let [after-first (claims ctx target)]
        (testing "the losing consolidation is refused and writes nothing"
          (record! ctx target [(delta :add {:content "the stale writer"})] stale)
          (is (= (map :content after-first) (map :content (claims ctx target)))
              "state is unchanged by the refused consolidation"))
        (testing "re-reading and retrying succeeds"
          (record! ctx target [(delta :add {:content "the retry"})])
          (is (= #{"the writer that won" "the retry"}
                 (set (map :content (claims ctx target))))))))))

(deftest every-claim-re-pools-from-its-own-provenance-with-nothing-orphaned
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          ep-a (episode)
          ep-b (episode)]
      (record! ctx target [(delta :add {:content "from occurrence A" :episodes [ep-a]})])
      (record! ctx target [(delta :add {:content "from occurrence B" :episodes [ep-b]})])
      (record! ctx target [(delta :add {:content "from both" :episodes [ep-a ep-b]})])
      (let [all (claims ctx target)
            pool-a (filter #(some #{ep-a} (:supporting-episodes %)) all)
            pool-b (filter #(some #{ep-b} (:supporting-episodes %)) all)
            orphans (remove #(or (seq (:supporting-episodes %))
                                 (:legacy-provenance %))
                            all)]
        (is (= 3 (count all)))
        (is (= 2 (count pool-a)))
        (is (= 2 (count pool-b)))
        (is (empty? orphans)
            "every claim resolves to episodes (or is explicitly legacy) — a
             re-partition of class identity cannot strand what we learned")))))

(deftest every-declared-operation-round-trips-and-deltas-compare-structurally
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          cid (seed-claim! ctx target "operable")]
      (record! ctx target [(delta :support {:target-claim cid})])
      (record! ctx target [(delta :edit {:target-claim cid :content "edited"})])
      (record! ctx target [(delta :contradict {:target-claim cid})])
      (is (some #(= cid (:claim-id %)) (claims ctx target))
          "add/support/edit/contradict all applied to one claim")
      (testing "value-equality.ClaimDelta"
        (let [ep (episode)
              d1 (delta :support {:target-claim "x" :episodes [ep]})
              d2 (delta :support {:target-claim "x" :episodes [ep]})]
          (is (= d1 d2) "two deltas with identical fields are equal")
          (is (not= d1 (assoc d1 :operation :contradict))
              "differing on operation makes them unequal"))))))
