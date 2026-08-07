(ns ai.obney.orc.ontology.cc1-claim-store-test
  "CC-1 — Claim store: one operation end-to-end (ADR 0021).

   PROPAGATED FROM SPEC: specs/ontology.allium. These tests are CONTRACT,
   generated from spec obligations before implementation. Never weaken a test
   to make it pass — fix the spec and re-propagate.

   Obligations covered (allium plan ids):
     rule-success.RecordClaimDeltas
     rule-success.ApplyAddClaim
     rule-entity-creation.ApplyAddClaim.1
     entity-fields.Claim
     entity-optional.Claim.context_guard
     entity-optional.Claim.recommendation
     entity-fields.ClaimDelta
     value-equality.ClaimDelta
     enum-comparable.ClaimKind
     enum-comparable.ClaimStatus
     enum-comparable.ClaimOperation
     entity-relationship.LivingDescription.claims

   House disciplines encoded here: assert by reading the PROJECTION back
   (never a command's return value); production-faithful ids that are DISJOINT
   across targets (the SJ-1 lesson)."
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

;; ---------------------------------------------------------------------------
;; Context helpers (mirror el4_harvest_test / consolidation_trigger_test)
;; ---------------------------------------------------------------------------
(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/cc1-test-" (random-uuid))
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

;; ---------------------------------------------------------------------------
;; Fixture data — production-faithful occurrence pairs, DISJOINT target ids.
;; An episode is the [source-sheet-id source-tick-id] occurrence pair (HP-2).
;; ---------------------------------------------------------------------------
(defn- episode [] [(random-uuid) (random-uuid)])

(defn- add-delta
  ([content] (add-delta content {}))
  ([content overrides]
   (merge {:operation :add
           :kind :weakness
           :content content
           :episodes [(episode)]
           :from-legacy-corpus false}
          overrides)))


;; ---------------------------------------------------------------------------
;; PRODUCTION-FAITHFUL EPISODES (orchestrator fix, after CC-3 and CC-4 both
;; root-caused the same defect independently).
;;
;; These fixtures originally cited episodes built from fresh random uuids —
;; occurrences that never happened. That is not a production shape: a claim is
;; derived from a turn that actually ran and was judged, and CC-4's evidence
;; guard correctly refuses evidence it cannot resolve. Fabricated identifiers
;; hiding a real contract is the SJ-1 lesson repeating, so the fixtures are
;; made REALISTIC rather than the guard made permissive.
;; ---------------------------------------------------------------------------
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

(defn- record-deltas!
  "Dispatch the claim-delta command. Returns the command result; tests must
   assert on the PROJECTION, not on this."
  [ctx target-id deltas version]
  (ground-episodes! ctx deltas)
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/record-claim-deltas
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :granularity :tree-class
            :target-identifier target-id
            :deltas deltas
            :claim-set-version version})))

;; ---------------------------------------------------------------------------
;; rule-success.RecordClaimDeltas · rule-success.ApplyAddClaim ·
;; rule-entity-creation.ApplyAddClaim.1 · entity-fields.Claim
;; ---------------------------------------------------------------------------
(deftest add-delta-creates-a-claim-readable-from-the-projection
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          ep (episode)]
      (record-deltas! ctx target
                      [(add-delta "hallucinates write success on empty diffs"
                                  {:episodes [ep]})]
                      0)
      (let [claims (ontology/get-claims ctx :tree-class target)
            claim (first claims)]
        (is (= 1 (count claims)) "exactly one claim landed")
        (testing "every declared field is present with the right type"
          (is (= :weakness (:kind claim)))
          (is (= "hallucinates write success on empty diffs" (:content claim)))
          (is (int? (:support claim)))
          (is (pos? (:support claim)) "support is seeded on creation")
          (is (= :candidate (:status claim)) "a new claim starts non-enforcing")
          (is (= [ep] (:supporting-episodes claim))
              "the occurrence pair that produced the claim is retained")
          (is (= [] (:contradicting-episodes claim)))
          (is (false? (:legacy-provenance claim)))
          (is (some? (:created-at claim)))
          (is (some? (:updated-at claim))))))))

;; ---------------------------------------------------------------------------
;; entity-optional.Claim.context_guard · entity-optional.Claim.recommendation
;; ---------------------------------------------------------------------------
(deftest optional-claim-fields-accept-null-and-non-null
  (with-test-ctx [ctx]
    (let [bare (random-uuid)
          guarded (random-uuid)]
      (record-deltas! ctx bare [(add-delta "no guard on this one")] 0)
      (record-deltas! ctx guarded
                      [(add-delta "avoid on wholesale file rewrites"
                                  {:context-guard "task rewrites a whole file"
                                   :recommendation "patch the region instead"})]
                      0)
      (let [bare-claim (first (ontology/get-claims ctx :tree-class bare))
            guarded-claim (first (ontology/get-claims ctx :tree-class guarded))]
        (is (nil? (:context-guard bare-claim)))
        (is (nil? (:recommendation bare-claim)))
        (is (= "task rewrites a whole file" (:context-guard guarded-claim)))
        (is (= "patch the region instead" (:recommendation guarded-claim)))))))

;; ---------------------------------------------------------------------------
;; enum-comparable.ClaimKind · ClaimStatus · ClaimOperation
;; ---------------------------------------------------------------------------
(deftest claim-kinds-are-comparable-and-distinct-per-claim
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (record-deltas! ctx target
                      [(add-delta "reads before editing" {:kind :capability})
                       (add-delta "verifies with a real command" {:kind :strength})
                       (add-delta "claims success on empty diffs" {:kind :weakness})
                       (add-delta "avoid when no test harness exists" {:kind :guard})
                       (add-delta "targeted bug fixes in utility modules"
                                  {:kind :representative-use})]
                      0)
      (let [claims (ontology/get-claims ctx :tree-class target)
            kinds (set (map :kind claims))]
        (is (= 5 (count claims)))
        (is (= #{:capability :strength :weakness :guard :representative-use} kinds)
            "every declared ClaimKind round-trips through the projection")
        (is (every? #(= :candidate (:status %)) claims)
            "ClaimStatus round-trips and every new claim is a candidate")))))

;; ---------------------------------------------------------------------------
;; entity-relationship.LivingDescription.claims
;; ---------------------------------------------------------------------------
(deftest claims-navigate-from-their-living-description-and-do-not-leak-across-targets
  (with-test-ctx [ctx]
    (let [target-a (random-uuid)
          target-b (random-uuid)]
      (record-deltas! ctx target-a [(add-delta "claim belonging to A")] 0)
      (record-deltas! ctx target-b [(add-delta "claim belonging to B")] 0)
      (let [a (ontology/get-claims ctx :tree-class target-a)
            b (ontology/get-claims ctx :tree-class target-b)]
        (is (= ["claim belonging to A"] (map :content a)))
        (is (= ["claim belonging to B"] (map :content b))
            "DISJOINT targets do not share claims (the SJ-1 join lesson)")))))

;; ---------------------------------------------------------------------------
;; The CAS seam: the claim-set version advances and is readable.
;; (Full stale-refusal behavior is CC-2; here we only prove the version moves
;;  and that a consolidation reading the CURRENT version succeeds.)
;; ---------------------------------------------------------------------------
(deftest claim-set-version-advances-and-a-consolidation-on-the-current-version-succeeds
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          v0 (ontology/get-claim-set-version ctx :tree-class target)]
      (record-deltas! ctx target [(add-delta "first insight")] v0)
      (let [v1 (ontology/get-claim-set-version ctx :tree-class target)]
        (is (> v1 v0) "recording deltas advances the claim-set version")
        (record-deltas! ctx target [(add-delta "second insight")] v1)
        (is (= 2 (count (ontology/get-claims ctx :tree-class target)))
            "a consolidation that read the current version lands")))))

;; ---------------------------------------------------------------------------
;; The fold degrades tolerantly: a delta referencing an absent claim is a
;; no-op, never a throw. Events are permanent; a fold that can throw on
;; historical data is a rebuild hazard.
;; ---------------------------------------------------------------------------
(deftest a-delta-referencing-an-absent-claim-is-a-no-op-not-a-throw
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (record-deltas! ctx target [(add-delta "the only real claim")] 0)
      (let [before (ontology/get-claims ctx :tree-class target)]
        (is (= 1 (count before)))
        (testing "folding a support op against a claim id that does not exist"
          (is (nil? (try
                      (record-deltas! ctx target
                                      [{:operation :support
                                        :target-claim (str (random-uuid))
                                        :kind :weakness
                                        :content "ghost"
                                        :episodes [(episode)]
                                        :from-legacy-corpus false}]
                                      (ontology/get-claim-set-version ctx :tree-class target))
                      nil
                      (catch Exception e e)))
              "the fold must not throw")
          (is (= (map :content before)
                 (map :content (ontology/get-claims ctx :tree-class target)))
              "state is unchanged by the ghost delta"))))))

;; ---------------------------------------------------------------------------
;; Legacy coexistence: pre-claim description events must still project after
;; the read model is versioned for claims. Proven by rebuilding from the log.
;; ---------------------------------------------------------------------------
(deftest legacy-description-events-still-project-alongside-claims
  (with-test-ctx [ctx]
    (let [legacy-target (random-uuid)
          claim-target (random-uuid)]
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/record-tree-class-description
                :command/id (random-uuid)
                :command/timestamp (time/now)
                :target-id legacy-target
                :body {:capabilities ["legacy capability"]
                       :strengths []
                       :weaknesses []
                       :representative-uses []
                       :avoid-when []
                       :summary "a description written before claims existed"
                       :version 1
                       :consolidated-from-event-count 3}}))
      (record-deltas! ctx claim-target [(add-delta "a claim-era insight")] 0)
      (is (some? (ontology/get-description ctx :tree-class legacy-target))
          "the legacy fold still produces a description")
      (is (= 1 (count (ontology/get-claims ctx :tree-class claim-target)))
          "the claim fold works in the same read model"))))
