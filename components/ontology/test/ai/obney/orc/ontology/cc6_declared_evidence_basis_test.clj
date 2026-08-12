(ns ai.obney.orc.ontology.cc6-declared-evidence-basis-test
  "CC-6 — the DECLARED EVIDENCE BASIS, and what it is allowed to buy.

   WHY THIS EXISTS. CC-4's evidence guard resolves a delta's `:episodes`
   against real judge evidence and refuses what it cannot resolve. That is
   correct for a consolidation, which reasons over turns a judge has already
   scored. It is fatal for a DETERMINISTIC writer that runs BEFORE any judge:
   CV-1's provisional capture fires at classify time, so a naive migration onto
   claim operations would produce a designed path that can never write. The
   orchestrator recorded that forward conflict when CC-4 landed.

   THE GENERALISATION. `:from-legacy-corpus` was the FIRST member of an idea —
   a delta whose evidence is DECLARED rather than resolved. CC-6 names the idea
   and gives it a field: `:evidence-basis`. A writer says what its content
   rests on, and the guard admits the bases that rest on something other than a
   judge event. `:legacy-corpus` keeps meaning exactly what it meant (a prior
   whole-body corpus), so CC-7's `:legacy-provenance` discriminator does not
   quietly acquire two meanings.

   WHAT A DECLARATION DOES NOT BUY. Admission, and nothing else. A declared
   delta names NO occurrence, so the claim it produces has no post-guard
   episode, so CC-7 cannot validate it, so CC-9's gate cannot let it enforce —
   for any amount of accumulated support. Mechanical knowledge is VISIBLE and
   never AUTHORITATIVE. It earns the right to enforce the only way anything
   does: by being reinforced from occurrences a judge actually scored.

   House rules: real grain (commands -> schema-validated events -> projections;
   NO bare appends); every assertion reads the PROJECTION back, never a
   command's return value."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.consolidator :as consolidator]
            [ai.obney.orc.ontology.core.evidence-guard :as guard]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.commands]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

;; ---------------------------------------------------------------------------
;; Context — no processors started. These cycles exercise the guard and the
;; claim fold directly through commands; the CV-1/CV-2 writer cycles live in
;; their own namespaces where the real processors DO run.
;; ---------------------------------------------------------------------------

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start
                      {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/cc6-basis-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))]
    {:event-store event-store :cache cache :tenant-id (random-uuid)
     :event-pubsub ps
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     ::cache-dir cache-dir}))

(defn- stop-context [ctx]
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [es (:event-store ctx)] (es/stop es))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f) (doseq [c (.listFiles f)] (.delete c)) (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)] (try ~@body (finally (stop-context ~sym)))))

(defn- episode [] [(random-uuid) (random-uuid)])

(defn- ground-episodes!
  "Seed one substantive judge score per named occurrence, so a delta that DOES
   name episodes is production-faithful (the CC-4 fixture decision)."
  [ctx deltas]
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

;; ===========================================================================
;; CYCLE 1 — a mechanical writer's declared basis gets it PAST the guard,
;; without pretending to be the legacy corpus.
;; ===========================================================================

(deftest a-classification-signature-delta-is-admitted-with-no-judge-evidence
  (testing "a delta declaring :classification-signature and naming no occurrence
            is recorded — the CV-1-can-never-write conflict is resolved"
    (with-test-ctx [ctx]
      (let [target (random-uuid)
            signature "implement: summarize a document into three bullets"]
        (record! ctx target
                 [{:operation :add :kind :representative-use
                   :content signature
                   :episodes []
                   :from-legacy-corpus false
                   :evidence-basis :classification-signature}])
        (let [c (claim-by ctx target signature)]
          (is (some? c)
              "the guard admitted the delta on its DECLARED basis")
          (is (false? (:legacy-provenance c))
              "and it is NOT recorded as legacy-corpus provenance — CC-7's
               discriminator keeps its single meaning"))
        ;; AT CC-6 the claim MAP deliberately gained no thirteenth key: for a
        ;; MECHANICAL basis, correctness does not need one — a declared delta
        ;; carries no episode, which is what actually stops CC-7 validating it.
        ;; The basis was durable all the same, riding on the permanent recording
        ;; event, so a rebuild reproduces it and CC-14's maintainer view can
        ;; read it.
        ;;
        ;; SUPERSEDED BY CC-9d, which added `:authored` — a basis that DOES
        ;; change the claim's status, so two spec rules and one invariant now
        ;; compare against `Claim.evidence_basis` directly and it had to become
        ;; a field on the claim. The event-log assertion below is unaffected and
        ;; still the stronger of the two: the declaration is permanent there
        ;; whatever the projection chooses to carry.
        (let [recorded (->> (es/read (:event-store ctx)
                                     {:tenant-id (:tenant-id ctx)
                                      :types #{:ontology/claim-deltas-recorded}})
                            (into []) first)]
          (is (= [:classification-signature]
                 (mapv :evidence-basis (:deltas recorded)))
              "the declaration is on the event log, verbatim and permanent"))))))

(deftest an-undeclared-episodeless-delta-is-still-refused
  (testing "NEGATIVE CONTROL — the guard did not simply stop caring. A delta
            with no episodes and no declared basis is still excluded, and the
            exclusion is still recorded."
    (with-test-ctx [ctx]
      (let [target (random-uuid)]
        (record! ctx target
                 [{:operation :add :kind :weakness
                   :content "an ungrounded assertion"
                   :episodes []
                   :from-legacy-corpus false}])
        (is (empty? (claims ctx target))
            "no claim: an undeclared, unjudged delta buys nothing")
        (is (seq (ontology/get-excluded-evidence ctx :tree-class target))
            "and the refusal is observable as an exclusion record")))))

;; ===========================================================================
;; CYCLE 2 — what the declaration does NOT buy. CC-7's rule still holds.
;; ===========================================================================

(deftest a-declared-basis-claim-can-never-enforce-however-supported
  (testing "a mechanically-declared claim accumulates support and stays a
            CANDIDATE forever — visible, never authoritative — because it has
            no post-guard episode. With a grounded POSITIVE CONTROL at the same
            support, so this cannot pass by nothing ever validating."
    (with-test-ctx [ctx]
      (let [target (random-uuid)
            declared "implement: summarize a document into three bullets"
            grounded "the reranker mislabels short instructions"]
        ;; The mechanical claim: declared basis, no occurrences, reinforced
        ;; well past CC-7's validation threshold.
        (record! ctx target [{:operation :add :kind :representative-use
                              :content declared :episodes []
                              :from-legacy-corpus false
                              :evidence-basis :classification-signature}])
        (let [cid (:claim-id (claim-by ctx target declared))]
          (dotimes [_ 8]
            (record! ctx target [{:operation :support :target-claim cid
                                  :kind :representative-use :content declared
                                  :episodes [] :from-legacy-corpus false
                                  :evidence-basis :classification-signature}])))
        ;; POSITIVE CONTROL — a judged claim at comparable support DOES enforce.
        (record! ctx target [{:operation :add :kind :weakness :content grounded
                              :episodes [(episode)] :from-legacy-corpus false}])
        (let [cid (:claim-id (claim-by ctx target grounded))]
          (dotimes [_ 6]
            (record! ctx target [{:operation :support :target-claim cid
                                  :kind :weakness :content grounded
                                  :episodes [(episode)] :from-legacy-corpus false}])))
        (let [d (claim-by ctx target declared)
              g (claim-by ctx target grounded)
              enforcing (set (map :content
                                  (ontology/get-enforcing-claims ctx :tree-class target)))]
          (is (< 5 (:support d))
              "the declared claim really did accumulate support past the threshold")
          (is (= :candidate (:status d))
              "and it is STILL a candidate — a declaration buys admission, not authority")
          (is (= :validated (:status g))
              "CONTROL: judged evidence at comparable support DOES validate")
          (is (not (contains? enforcing declared))
              "the declared claim is not in the enforcing set")
          (is (contains? enforcing grounded)
              "CONTROL: the judged claim is"))))))

;; ===========================================================================
;; CYCLE 9 — the two ways a declaration could become a loophole, closed.
;; ===========================================================================

(deftest a-declared-basis-does-not-excuse-episodes-that-exist
  (testing "declaring a basis while NAMING occurrences buys nothing: the
            occurrences are resolved as usual, and unjudged ones are excluded.
            One rule, not one rule per writer."
    (with-test-ctx [ctx]
      (let [target (random-uuid)]
        ;; NOTE: no ground-episodes! for this one — the occurrence is named but
        ;; nothing ever judged it.
        (cp/process-command
          (assoc ctx :command
                 {:command/name :ontology/record-claim-deltas
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :granularity :tree-class
                  :target-identifier target
                  :deltas [{:operation :add :kind :strength
                            :content "an artifact claim riding on an unjudged turn"
                            :episodes [(episode)]
                            :from-legacy-corpus false
                            :evidence-basis :emitted-artifact}]
                  :claim-set-version 0}))
        (is (empty? (claims ctx target))
            "the declaration did not excuse an occurrence the guard could not
             resolve")
        (is (= [:no-judge-evidence]
               (mapv :reason (ontology/get-excluded-evidence ctx :tree-class target)))
            "and the exclusion names the real reason, not the declaration")))))

(deftest the-reflection-llm-cannot-declare-its-own-basis
  (testing "`:evidence-basis` is a key the delta schema now permits, which means
            a reflection operation is now ALLOWED to carry one. The consolidator
            must stamp it in code — the same discipline it already applies to
            `:episodes` — or a model could declare its way past the guard."
    (let [prepared (#'consolidator/prepare-operations
                     [{:operation :add :kind :strength
                       :content "a model-authored insight"
                       :evidence-basis :legacy-corpus}]
                     []   ;; no existing claims
                     [])  ;; an EMPTY evidence window: the dangerous case
          delta (first (:deltas prepared))]
      (is (some? delta) "the operation was accepted (it is well-formed)")
      (is (= :judged-occurrences (:evidence-basis delta))
          "but its declared basis is the one the CODE fixed, not the one the
           model asked for")
      (is (not (contains? guard/declared-bases-admitted (guard/declared-basis delta)))
          "so with no episodes it is still refused, exactly as before CC-6"))))
