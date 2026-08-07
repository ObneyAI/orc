(ns ai.obney.orc.ontology.cc7-claim-status-test
  "CC-7 — claim status lifecycle: earning the right to enforce (ADRs 0021/0022).

   PROPAGATED FROM SPEC: specs/ontology.allium. CONTRACT — never weaken a test
   to make it pass; report it as a finding instead.

   Obligations covered (allium plan ids):
     rule-success.ValidateWellSupportedClaim
     rule-failure.ValidateWellSupportedClaim.1 / .2 / .3
     transition-edge.Claim.candidate.validated
     transition-edge.Claim.validated.candidate
     invariant.OnlyValidatedClaimsEnforce

   Written against the REAL APIs of CC-1/CC-2/CC-3/CC-4. Episodes are GROUNDED
   (a real judge score is seeded per occurrence) because CC-4's evidence guard
   correctly refuses evidence it cannot resolve — fabricating occurrences that
   never happened is not a production shape."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface :as ontology]
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
        cache-dir (str "/tmp/cc7-test-" (random-uuid))
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

(defn- ground-episodes! [ctx deltas]
  (doseq [[sheet-id tick-id] (distinct (mapcat :episodes deltas))]
    (cp/process-command
      (assoc ctx :command
             {:command/name :evaluation/record-judge-score
              :command/id (random-uuid)
              :command/timestamp (time/now)
              :sheet-id sheet-id :node-id (random-uuid) :tick-id tick-id
              :judge-name "coding-outcome" :judge-config {}
              :score 0.8
              :feedback (str "The turn applied the edit to src/util.clj and the "
                             "verification command exited 0, so the assessment is "
                             "grounded in the observed diff and command output.")
              :dimensions []}))))

(defn- delta [op overrides]
  (merge {:operation op :kind :weakness :content "a claim"
          :episodes [(episode)] :from-legacy-corpus false}
         overrides))

(defn- record! [ctx target deltas]
  (ground-episodes! ctx deltas)
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
(defn- claim-by [ctx target content]
  (first (filter #(= content (:content %)) (claims ctx target))))

(defn- seed! [ctx target content]
  (record! ctx target [(delta :add {:content content})])
  (:claim-id (claim-by ctx target content)))

(defn- reinforce! [ctx target cid n]
  (dotimes [_ n] (record! ctx target [(delta :support {:target-claim cid})])))

;; ---------------------------------------------------------------------------
;; rule-failure.ValidateWellSupportedClaim.2 — insufficient support
;; (corrected after CC-7 read the allium plan source spans: .1 is the
;;  `claim.status = candidate` precondition, .2 is the support precondition.)
;; ---------------------------------------------------------------------------
(deftest a-claim-starts-as-a-candidate-and-cannot-enforce
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (seed! ctx target "a fresh insight")
      (let [c (claim-by ctx target "a fresh insight")]
        (is (= :candidate (:status c))
            "a new claim is recorded and visible, but it has not earned the
             right to influence ranking")))))

;; ---------------------------------------------------------------------------
;; rule-success.ValidateWellSupportedClaim + transition-edge candidate->validated
;; ---------------------------------------------------------------------------
(deftest a-well-supported-claim-with-post-guard-evidence-becomes-validated
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          cid (seed! ctx target "repeatedly corroborated insight")]
      (reinforce! ctx target cid 6)
      (let [c (claim-by ctx target "repeatedly corroborated insight")]
        (is (= :validated (:status c))
            "accumulated support from grounded episodes earns enforcement")
        (is (seq (:supporting-episodes c))
            "and the episodes that earned it remain attached")))))

;; ---------------------------------------------------------------------------
;; rule-failure.ValidateWellSupportedClaim.3 — legacy evidence cannot validate
;; ---------------------------------------------------------------------------
(deftest legacy-evidence-counts-toward-support-but-cannot-by-itself-validate
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      ;; A backfilled claim: declared legacy provenance, no resolvable episodes.
      (record! ctx target [{:operation :add :kind :weakness
                            :content "converted from a pre-claim body"
                            :episodes []
                            :from-legacy-corpus true}])
      (let [cid (:claim-id (claim-by ctx target "converted from a pre-claim body"))]
        (dotimes [_ 6]
          (record! ctx target [{:operation :support :target-claim cid
                                :kind :weakness :content "converted from a pre-claim body"
                                :episodes [] :from-legacy-corpus true}]))
        ;; POSITIVE CONTROL — without this the test cannot distinguish "legacy
        ;; was correctly blocked" from "nothing ever validates at all".
        (let [grounded (seed! ctx target "earned under the guard")]
          (reinforce! ctx target grounded 6)
          (let [c (claim-by ctx target "converted from a pre-claim body")
                control (claim-by ctx target "earned under the guard")]
            (is (some? c) "the legacy claim exists and is visible")
            (is (< 1 (:support c)) "legacy evidence DOES count toward support")
            (is (= :validated (:status control))
                "control: comparable support from grounded episodes DOES validate")
            (is (= :candidate (:status c))
                "so the discriminator is provenance, not support: pre-guard
                 evidence has unknown starvation content by definition and
                 cannot by itself earn enforcement")))))))

;; ---------------------------------------------------------------------------
;; transition-edge.Claim.validated.candidate — demotion
;; ---------------------------------------------------------------------------
(deftest accumulated-contradiction-demotes-a-validated-claim
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          cid (seed! ctx target "contested but corroborated")]
      (reinforce! ctx target cid 6)
      (is (= :validated (:status (claim-by ctx target "contested but corroborated"))))
      (dotimes [_ 5] (record! ctx target [(delta :contradict {:target-claim cid})]))
      (let [c (claim-by ctx target "contested but corroborated")]
        (is (some? c) "still present — demotion is not deletion")
        (is (= :candidate (:status c))
            "losing support returns a claim to candidate: enforcement is
             continuously earned, not granted once")))))

;; ---------------------------------------------------------------------------
;; invariant.OnlyValidatedClaimsEnforce — candidates render but do not rank
;; ---------------------------------------------------------------------------
(deftest only-validated-claims-are-selected-for-enforcement
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          strong (seed! ctx target "well-corroborated guard")]
      (reinforce! ctx target strong 6)
      (seed! ctx target "unproven guard")
      (let [enforcing (ontology/get-enforcing-claims ctx :tree-class target)
            all (claims ctx target)]
        (is (= 2 (count all)) "both claims exist and are visible to the model")
        (is (= ["well-corroborated guard"] (map :content enforcing))
            "only the validated claim is offered to anything that ranks — an
             unproven assertion must not suppress a behaviour's retrieval")
        (is (every? #(= :validated (:status %)) enforcing))))))

(deftest every-validated-claim-has-post-guard-evidence
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          cid (seed! ctx target "earned the hard way")]
      (reinforce! ctx target cid 6)
      (record! ctx target [{:operation :add :kind :weakness
                            :content "legacy backfill" :episodes []
                            :from-legacy-corpus true}])
      (let [validated (filter #(= :validated (:status %)) (claims ctx target))]
        ;; The invariant must be asserted over a NON-EMPTY set, or it holds
        ;; trivially and proves nothing (the CC-2 lesson: an invariant checked
        ;; over an empty collection cannot fail).
        (is (seq validated)
            "at least one claim must have reached validated, or this invariant
             is being asserted over an empty set")
        (doseq [c validated]
          (is (seq (remove nil? (:supporting-episodes c)))
              "nothing reaches validated without evidence gathered under the guard"))
        (is (= #{"earned the hard way"} (set (map :content validated)))
            "and the legacy backfill is NOT among them")))))

;; ---------------------------------------------------------------------------
;; transition-rejected.Claim.status — no other status is reachable
;; ---------------------------------------------------------------------------
(deftest no-status-outside-the-declared-set-is-reachable
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          cid (seed! ctx target "ordinary claim")]
      (reinforce! ctx target cid 6)
      (record! ctx target [(delta :edit {:target-claim cid :content "edited"})])
      (is (every? #{:candidate :validated} (map :status (claims ctx target)))
          "status is only ever candidate or validated, through every operation"))))
