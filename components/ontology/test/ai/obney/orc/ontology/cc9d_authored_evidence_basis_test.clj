(ns ai.obney.orc.ontology.cc9d-authored-evidence-basis-test
  "CC-9d — AUTHORED knowledge is a first-class evidence basis (ADR 0022
   amendment 2, grill GR-2 Q4).

   PROPAGATED FROM SPEC: specs/ontology.allium. CONTRACT — never weaken a test
   to make it pass; report it as a finding instead.

   WHY THIS EXISTS. A claim enforces only at `support >= 5`
   (`validation-support-threshold`, DERIVED from the confidence floor). A
   designer-written corpus guard seeded the ordinary way starts at
   `initial-claim-support` = 2, i.e. `:candidate`, i.e. NON-ENFORCING — so the
   curated regression corpus, including every proven EL-5 case, would disarm
   itself on the day it was seeded.

   Both obvious alternatives were rejected in the grill. Seeding AT 5 buys only
   ~0.6 of full strength with measured demotion headroom already marginal.
   Seeding at 10 FABRICATES episode counts, which breaks
   `ClaimsCarryResolvableProvenance` at the corpus root and poisons every future
   calibration. Authorship is a TRUE, auditable statement about provenance; a
   fake support count is not. So authorship becomes a basis, not a number.

   WHAT AUTHORSHIP GRANTS, AND WHAT IT DOES NOT. It grants enforcement from
   creation and exemption from support-driven demotion. It does NOT grant
   immortality: contradiction still decrements, and the ordinary retirement path
   still removes the claim when its support is exhausted. And it is set at
   CREATION only — an edit PRESERVES it, which is what stops a reworded
   authored guard from being laundered into weaker earned-evidence accounting.

   Obligations covered (allium plan ids, clause mapping read from the plan's
   own source_span byte offsets rather than assumed):
     enum-comparable.EvidenceBasis
     rule-success.ValidateAuthoredClaimAtCreation
     rule-failure.ValidateAuthoredClaimAtCreation.1  (evidence_basis = authored)
     rule-success.DemoteUnderSupportedClaim
     rule-failure.DemoteUnderSupportedClaim.1        (status = validated)
     rule-failure.DemoteUnderSupportedClaim.2        (evidence_basis != authored)
     rule-failure.DemoteUnderSupportedClaim.3        (support < threshold)
     invariant.OnlyValidatedClaimsEnforce

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
;; Context — the CC-6 shape. No todo processors: these cycles exercise the
;; guard and the claim fold directly through commands.
;; ---------------------------------------------------------------------------

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start
                      {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/cc9d-authored-" (random-uuid))
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

;; DISJOINT ids per occurrence (the SJ-1 lesson): a shared sheet-id across turns
;; is what hid a join bug once, so every episode here is a fresh pair.
(defn- episode [] [(random-uuid) (random-uuid)])

(defn- ground-episodes!
  "Seed one substantive judge score per named occurrence, so a delta that DOES
   name episodes is production-faithful and CC-4's guard can resolve it."
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

(def ^:private authored-guard-text
  "never edit a generated file; regenerate it from its source instead")

(defn- authored-add
  "The production shape of a seeded corpus guard: designer-written content,
   NO occurrences (nothing judged it — nothing could have), and an HONEST
   declaration of what it rests on."
  ([] (authored-add authored-guard-text))
  ([content]
   {:operation :add :kind :guard :content content
    :episodes [] :from-legacy-corpus false
    :evidence-basis :authored}))

(defn- judged-add [content]
  {:operation :add :kind :guard :content content
   :episodes [(episode)] :from-legacy-corpus false})

;; ===========================================================================
;; CYCLE 1 — rule-success.ValidateAuthoredClaimAtCreation
;;           rule-failure.ValidateAuthoredClaimAtCreation.1
;;           enum-comparable.EvidenceBasis
;;
;; An authored-basis claim is BORN validated, WITHOUT having earned anything.
;; ===========================================================================

(deftest an-authored-claim-is-born-validated-with-no-earned-support
  (testing "a curated guard enforces at full strength from creation — the
            alternative silently disarms the proven corpus at seeding"
    (with-test-ctx [ctx]
      (let [target (random-uuid)]
        (record! ctx target [(authored-add)])
        (let [c (claim-by ctx target authored-guard-text)]
          (is (some? c)
              "the claim exists at all: the guard admitted an authored delta
               that names no occurrence")
          (is (= :authored (:evidence-basis c))
              "and the basis is DURABLE on the claim — the rules compare
               against it, so it cannot live only on the event")
          (is (= :validated (:status c))
              "BORN validated: authorship is the enforcement authority")
          ;; Non-vacuity: prove it is validated DESPITE having none of what
          ;; ordinarily earns validation, or this assertion proves nothing.
          (is (empty? (remove nil? (:supporting-episodes c)))
              "with ZERO post-guard episodes")
          (is (< (:support c) 5)
              "and support below the validation threshold (5) — nothing here
               was earned"))))))

(deftest a-non-authored-claim-is-still-born-a-candidate
  (testing "NEGATIVE CONTROL — rule-failure.ValidateAuthoredClaimAtCreation.1.
            The creation rule fires on the basis, not on being a claim."
    (with-test-ctx [ctx]
      (let [target (random-uuid)]
        (record! ctx target [(judged-add "an ordinary judged insight")])
        (let [c (claim-by ctx target "an ordinary judged insight")]
          (is (some? c) "the ordinary claim was recorded")
          (is (not= :authored (:evidence-basis c))
              "it declares no authorship")
          (is (= :candidate (:status c))
              "so it starts non-enforcing and must earn its way, exactly as
               before CC-9d"))))))

;; ===========================================================================
;; CYCLE 2 — invariant.OnlyValidatedClaimsEnforce (the authored arm)
;;
;; Born validated is worth nothing unless it reaches the PRODUCTION SELECTION
;; SURFACE. `get-enforcing-claims` is what EL-2 stamps onto a candidate for
;; EL-5's domain penalty to read, so this is the assertion that the curated
;; corpus is actually armed.
;; ===========================================================================

(deftest an-authored-claim-is-both-visible-and-enforcing
  (testing "the authored guard is offered to the ranker; an unearned ordinary
            claim, at the SAME support, is not"
    (with-test-ctx [ctx]
      (let [target (random-uuid)]
        (record! ctx target [(authored-add)])
        (record! ctx target [(judged-add "an unproven ordinary guard")])
        (let [all       (claims ctx target)
              enforcing (ontology/get-enforcing-claims ctx :tree-class target)]
          (is (= 2 (count all))
              "both claims are VISIBLE to the model — enforcement is a
               narrowing of visibility, not a replacement for it")
          ;; Same support on both arms, so the discriminator under test is
          ;; the BASIS and nothing else.
          (is (apply = (map :support all))
              "control: the two claims sit at identical support, so what
               separates them below cannot be accrual")
          (is (= [authored-guard-text] (mapv :content enforcing))
              "only the authored guard is handed to anything that ranks")
          (is (every? #(= :validated (:status %)) enforcing)
              "and the enforcing set is still exactly the validated set"))))))

;; ===========================================================================
;; CYCLE 3 — rule-failure.DemoteUnderSupportedClaim.2 (evidence_basis != authored)
;;
;; The exemption clause. An authored claim whose support sits below the
;; threshold KEEPS enforcing: its support number was never a threshold count,
;; and its authority comes from authorship, not accrual.
;; ===========================================================================

(deftest an-authored-claim-is-not-demoted-for-sitting-below-the-threshold
  (testing "a support CHANGE on an authored claim leaves it validated, on both
            the reinforcing and the eroding edge — support < 5 throughout"
    (with-test-ctx [ctx]
      (let [target (random-uuid)]
        (record! ctx target [(authored-add)])
        (let [cid (:claim-id (claim-by ctx target authored-guard-text))]
          (is (some? cid) "precondition: the authored claim was recorded")

          ;; EDGE A — a judged occurrence corroborates it. Support rises to 3,
          ;; still under the threshold. The status re-derivation runs.
          (record! ctx target [{:operation :support :target-claim cid
                                :kind :guard :content authored-guard-text
                                :episodes [(episode)] :from-legacy-corpus false}])
          (let [c (claim-by ctx target authored-guard-text)]
            (is (< (:support c) 5)
                "control: support really is still below the threshold, so the
                 demotion rule's support precondition really is met")
            (is (= :validated (:status c))
                "reinforcement did not knock it out of enforcement"))

          ;; EDGE B — the eroding one, which is the case the spec clause names.
          (record! ctx target [{:operation :contradict :target-claim cid
                                :kind :guard :content authored-guard-text
                                :episodes [(episode)] :from-legacy-corpus false}])
          (let [c (claim-by ctx target authored-guard-text)]
            (is (< (:support c) 5)
                "control: still below the threshold after the contradiction")
            (is (= :authored (:evidence-basis c))
                "and still authored — the basis survived two support changes")
            (is (= :validated (:status c))
                "DemoteUnderSupportedClaim does not fire: authorship, not
                 accrual, is what this claim's authority rests on")
            (is (contains? (set (map :content
                                     (ontology/get-enforcing-claims ctx :tree-class target)))
                           authored-guard-text)
                "and it is still on the production enforcement surface")))))))

;; ===========================================================================
;; CYCLE 4 — rule-success.DemoteUnderSupportedClaim
;;           rule-failure.DemoteUnderSupportedClaim.1 (status = validated)
;;           rule-failure.DemoteUnderSupportedClaim.3 (support < threshold)
;;
;; THE EXEMPTION MUST NOT LEAK. Cycle 3 proves the rule stops firing for
;; authored claims; on its own that is also what a rule DELETED entirely would
;; look like. This is the arm that tells the two apart.
;; ===========================================================================

(deftest a-non-authored-claim-below-the-threshold-is-still-demoted
  (testing "rule-success — an ordinary validated claim that loses support
            returns to candidate and leaves the enforcement surface"
    (with-test-ctx [ctx]
      (let [target (random-uuid)
            content "the reranker mislabels short instructions"]
        (record! ctx target [(judged-add content)])
        (let [cid (:claim-id (claim-by ctx target content))]
          ;; Earn it: 2 (seed) + 6 supports = 8, comfortably validated.
          (dotimes [_ 6]
            (record! ctx target [{:operation :support :target-claim cid
                                  :kind :guard :content content
                                  :episodes [(episode)] :from-legacy-corpus false}]))
          (is (= :validated (:status (claim-by ctx target content)))
              "precondition (rule-failure .1's satisfied form): it really did
               reach validated, so demotion has something to act on")

          ;; rule-failure.DemoteUnderSupportedClaim.3 — while support is still
          ;; AT OR ABOVE the threshold the rule must NOT fire.
          (dotimes [_ 3]
            (record! ctx target [{:operation :contradict :target-claim cid
                                  :kind :guard :content content
                                  :episodes [(episode)] :from-legacy-corpus false}]))
          (let [c (claim-by ctx target content)]
            (is (= 5 (:support c)) "control: support is exactly the threshold")
            (is (= :validated (:status c))
                "AT the threshold it still enforces — the rule's support
                 precondition is strict"))

          ;; rule-success — one more contradiction crosses the line.
          (record! ctx target [{:operation :contradict :target-claim cid
                                :kind :guard :content content
                                :episodes [(episode)] :from-legacy-corpus false}])
          (let [c (claim-by ctx target content)]
            (is (some? c) "still present — demotion is not deletion")
            (is (< (:support c) 5) "control: now below the threshold")
            (is (not= :authored (:evidence-basis c))
                "and this claim never declared authorship — the discriminator
                 under test")
            (is (= :candidate (:status c))
                "so it IS demoted: CC-9d exempted authored claims, it did not
                 disable the rule")
            (is (empty? (ontology/get-enforcing-claims ctx :tree-class target))
                "and it is off the production enforcement surface")))))))

(deftest the-exemption-keys-on-the-basis-and-nothing-adjacent
  (testing "the other three declared bases are NOT exempt. A declared basis
            buys admission past the guard; only AUTHORSHIP buys enforcement."
    (with-test-ctx [ctx]
      (let [target (random-uuid)
            mechanical {:legacy-corpus            "converted from a pre-claim body"
                        :classification-signature "implement: summarize a document"
                        :emitted-artifact         "the emitted worked DSL"}]
        (doseq [[basis content] mechanical]
          (record! ctx target [{:operation :add :kind :guard :content content
                                :episodes [] :from-legacy-corpus false
                                :evidence-basis basis}]))
        (record! ctx target [(authored-add)])
        (let [enforcing (set (map :content
                                  (ontology/get-enforcing-claims ctx :tree-class target)))]
          (is (= 4 (count (claims ctx target)))
              "control: all four declared deltas were admitted by the guard, so
               this test is not passing by nothing being recorded")
          (doseq [[basis content] mechanical]
            (is (= :candidate (:status (claim-by ctx target content)))
                (str basis " is admitted but NOT enforcing"))
            (is (not (contains? enforcing content))
                (str basis " is not on the enforcement surface")))
          (is (= #{authored-guard-text} enforcing)
              "authorship alone is the enforcing basis"))))))

;; ===========================================================================
;; CYCLE 5 — the spec's "Set at CREATION only; an edit operation preserves it."
;;
;; THE ANTI-LAUNDERING RULE, and the reason the basis is a field on the CLAIM
;; rather than a property of whichever delta spoke last. CC-2 made `:edit` the
;; operation that lets a claim keep its identity and its earned support while
;; its wording moves. If an edit also re-set the basis, the reflection LLM could
;; reword a curated guard — an entirely ordinary thing for it to do — and the
;; guard would silently drop out of enforcement into weaker earned-evidence
;; accounting, at a moment nobody is watching.
;;
;; It cuts BOTH ways: an edit cannot launder authorship OUT, and cannot launder
;; it IN either.
;; ===========================================================================

(deftest an-edit-preserves-an-authored-basis
  (testing "rewording a curated guard — while the delta declares the ORDINARY
            basis, which is what the consolidator stamps on every model-proposed
            operation — leaves the claim authored and enforcing"
    (with-test-ctx [ctx]
      (let [target (random-uuid)
            reworded "regenerate a generated file from its source; never edit it directly"]
        (record! ctx target [(authored-add)])
        (let [cid (:claim-id (claim-by ctx target authored-guard-text))]
          (is (= :authored (:evidence-basis (claim-by ctx target authored-guard-text)))
              "precondition: it is authored before the edit")

          ;; The production laundering shape: a well-formed edit that cites a
          ;; real judged occurrence and declares :judged-occurrences, exactly as
          ;; `prepare-operations` stamps it. It passes the guard on its own
          ;; merits; the question is only what it does to the BASIS.
          (record! ctx target [{:operation :edit :target-claim cid
                                :kind :guard :content reworded
                                :episodes [(episode)] :from-legacy-corpus false
                                :evidence-basis :judged-occurrences}])

          (let [c (claim-by ctx target reworded)]
            (is (some? c) "control: the edit really did land — the wording moved")
            (is (nil? (claim-by ctx target authored-guard-text))
                "control: and it replaced the old wording rather than adding a
                 second claim")
            (is (= cid (:claim-id c)) "the claim kept its identity")
            (is (= :authored (:evidence-basis c))
                "AND ITS BASIS. An edit rewords; it does not re-provenance.")
            (is (< (:support c) 5)
                "control: support is still below the threshold, so status here
                 can only be coming from the basis")
            (is (= :validated (:status c))
                "so the reworded guard is still enforcing — the laundering
                 attempt bought nothing")))))))

(deftest an-edit-cannot-confer-authorship-on-an-ordinary-claim
  (testing "the same rule in the other direction: authorship is established at
            creation, so an edit declaring :authored on an earned claim does not
            promote it"
    (with-test-ctx [ctx]
      (let [target (random-uuid)
            content "an ordinary unproven claim"
            reworded "an ordinary unproven claim, reworded"]
        (record! ctx target [(judged-add content)])
        (let [cid (:claim-id (claim-by ctx target content))]
          (is (= :candidate (:status (claim-by ctx target content)))
              "precondition: it starts non-enforcing")
          (record! ctx target [{:operation :edit :target-claim cid
                                :kind :guard :content reworded
                                :episodes [] :from-legacy-corpus false
                                :evidence-basis :authored}])
          (let [c (claim-by ctx target reworded)]
            (is (some? c) "control: the edit landed")
            (is (not= :authored (:evidence-basis c))
                "the claim did not acquire authorship by assertion")
            (is (= :candidate (:status c))
                "so it is still non-enforcing: a claim cannot be talked into
                 the curated corpus after the fact")
            (is (empty? (ontology/get-enforcing-claims ctx :tree-class target))
                "and nothing reached the enforcement surface")))))))

;; ===========================================================================
;; CYCLE 6 — "They erode through the ordinary contradiction path instead — a
;;            claim is removed when its support decays to zero."
;;
;; AUTHORSHIP GRANTS ENFORCEMENT, NOT IMMORTALITY. Cycle 3 removed the demotion
;; edge for authored claims, which is exactly the change that could accidentally
;; make a curated guard unkillable. The spec names the erosion path that remains,
;; and this is it.
;; ===========================================================================

(deftest an-authored-claim-still-erodes-under-contradiction-and-is-removed
  (testing "contradiction still decrements an authored claim's support, and the
            ordinary retirement path still removes it when that support is
            exhausted"
    (with-test-ctx [ctx]
      (let [target (random-uuid)]
        (record! ctx target [(authored-add)])
        (let [cid   (:claim-id (claim-by ctx target authored-guard-text))
              seed  (:support (claim-by ctx target authored-guard-text))
              contradict! (fn []
                            (record! ctx target
                                     [{:operation :contradict :target-claim cid
                                       :kind :guard :content authored-guard-text
                                       :episodes [(episode)] :from-legacy-corpus false}]))]
          (is (= 2 seed)
              "control: it seeded at initial-claim-support, so N below is not
               guessed — it takes exactly 2 contradictions to exhaust it")

          ;; ONE contradiction: support really moves, and the claim really is
          ;; still there. If contradiction were a no-op for authored claims this
          ;; would be where it showed.
          (contradict!)
          (let [c (claim-by ctx target authored-guard-text)]
            (is (some? c) "still present after one contradiction")
            (is (= 1 (:support c))
                "and its support REALLY DECREMENTED — the exemption is from
                 demotion, not from accounting")
            (is (= 1 (count (:contradicting-episodes c)))
                "with the disagreeing occurrence filed on its own ledger")
            (is (= :validated (:status c))
                "it does keep enforcing while it survives (cycle 3)"))

          ;; The second exhausts it.
          (contradict!)
          (is (nil? (claim-by ctx target authored-guard-text))
              "REMOVED. Accumulated contradiction retires an authored claim
               exactly as it retires any other — authorship is not immortality")
          (is (empty? (ontology/get-enforcing-claims ctx :tree-class target))
              "so it is off the enforcement surface too")

          (let [retired (ontology/get-retired-claims ctx :tree-class target)]
            (is (= 1 (count retired))
                "and the retirement is a FACT on the projection, not a silent
                 disappearance")
            (is (= :support-exhausted (:reason (first retired))))
            (is (= :authored (:evidence-basis (:claim (first retired))))
                "the retired claim carries its basis, so the record of what was
                 retired stays auditable")))))))

;; ===========================================================================
;; CYCLE 7 — admission runs through CC-6's EXISTING declared-basis path.
;;
;; A curated guard has no occurrences by construction, so CC-4's guard would
;; refuse it for `:no-episodes` unless something admits it. CC-6 already built
;; the category for exactly this — a delta whose evidence is DECLARED rather
;; than resolved — so `:authored` joins that set rather than getting a second
;; exemption route of its own. TWO WAYS PAST A GUARD IS HOW A GUARD STOPS
;; MEANING ANYTHING, so this cycle asserts the route as well as the outcome.
;; ===========================================================================

(deftest an-authored-delta-is-admitted-through-the-declared-basis-path
  (testing "the guard's verdict says :declared-provenance and names the basis —
            the same seam :legacy-corpus and :emitted-artifact already use"
    (with-test-ctx [ctx]
      (is (contains? guard/declared-bases-admitted :authored)
          ":authored is a member of the ONE declared-basis set, not a parallel
           bypass")
      (let [verdict (guard/evidence-verdict ctx (authored-add))]
        (is (true? (:grounded? verdict))
            "an authored delta naming no occurrence is admitted")
        (is (= :declared-provenance (:settled-by verdict))
            "and it is admitted BY THAT PATH — settled by declared provenance,
             so an exclusion record would say which declaration was honoured")
        (is (= :authored (:basis verdict))
            "with the basis reported on the verdict")
        (is (nil? (:reason verdict))
            "and no exclusion reason")))))

(deftest an-authored-declaration-does-not-excuse-episodes-that-exist
  (testing "NEGATIVE CONTROL — CC-6's one rule is unchanged. A delta that NAMES
            occurrences is resolved normally whatever it declares, so authorship
            can never excuse evidence that exists and failed."
    (with-test-ctx [ctx]
      (let [target (random-uuid)]
        ;; NOTE: deliberately NOT grounded — the occurrence is named but nothing
        ;; ever judged it. Bypasses `record!` so no judge score is seeded.
        (cp/process-command
          (assoc ctx :command
                 {:command/name :ontology/record-claim-deltas
                  :command/id (random-uuid)
                  :command/timestamp (time/now)
                  :granularity :tree-class
                  :target-identifier target
                  :deltas [{:operation :add :kind :guard
                            :content "an authored claim riding on an unjudged turn"
                            :episodes [(episode)]
                            :from-legacy-corpus false
                            :evidence-basis :authored}]
                  :claim-set-version 0}))
        (is (empty? (claims ctx target))
            "declaring authorship did not excuse an occurrence the guard could
             not resolve")
        (is (= [:no-judge-evidence]
               (mapv :reason (ontology/get-excluded-evidence ctx :tree-class target)))
            "and the exclusion names the real reason, not the declaration")))))

(deftest an-undeclared-episodeless-delta-is-still-refused
  (testing "NEGATIVE CONTROL — widening the admitted set did not make the guard
            stop caring. No episodes and no declaration is still a refusal."
    (with-test-ctx [ctx]
      (let [target (random-uuid)]
        (record! ctx target [{:operation :add :kind :guard
                              :content "an ungrounded assertion"
                              :episodes [] :from-legacy-corpus false}])
        (is (empty? (claims ctx target))
            "no claim: an undeclared, unjudged delta still buys nothing")
        (is (seq (ontology/get-excluded-evidence ctx :tree-class target))
            "and the refusal is still observable as an exclusion record")))))

(deftest the-reflection-llm-cannot-declare-itself-authored
  (testing "the stakes of CC-6's stamping discipline went UP with this slice.
            Before CC-9d a self-declared basis bought a model admission past the
            guard; `:authored` would buy it ENFORCEMENT. `prepare-operations`
            fixes the basis IN CODE, so the model cannot reach it."
    (let [prepared (#'consolidator/prepare-operations
                     [{:operation :add :kind :guard
                       :content "a guard the model would like to be curated"
                       :evidence-basis :authored}]
                     []   ;; no existing claims
                     [])  ;; an EMPTY evidence window: the dangerous case
          delta (first (:deltas prepared))]
      (is (some? delta) "the operation was accepted (it is well-formed)")
      (is (= :judged-occurrences (:evidence-basis delta))
          "but its basis is the one the CODE stamped, not the one the model
           asked for — authorship is not self-assignable")
      (is (not= :authored (guard/declared-basis delta))
          "so it cannot enter the curated corpus by assertion"))))

;; ===========================================================================
;; CYCLE 8 — invariant.OnlyValidatedClaimsEnforce, in its WIDENED form:
;;
;;   claim.status = validated implies
;;     (post_guard_episode_count(claim) > 0 or claim.evidence_basis = authored)
;;
;; Asserted over a claim set built to populate BOTH arms of the disjunction and
;; the negative case, because an invariant checked over an empty — or
;; single-shaped — collection is a silent pass. The counts are asserted, not
;; assumed.
;; ===========================================================================

(defn- post-guard-count [claim]
  (count (remove nil? (:supporting-episodes claim))))

(deftest only-validated-claims-enforce-under-the-widened-invariant
  (with-test-ctx [ctx]
    (let [target   (random-uuid)
          authored authored-guard-text
          earned   "the reranker mislabels short instructions"
          unproven "a fresh unproven hypothesis"
          declared "implement: summarize a document into three bullets"]
      ;; ARM 1 — validated by AUTHORSHIP, with zero post-guard episodes.
      (record! ctx target [(authored-add authored)])
      ;; ARM 2 — validated by ACCRUAL, with post-guard episodes.
      (record! ctx target [(judged-add earned)])
      (let [cid (:claim-id (claim-by ctx target earned))]
        (dotimes [_ 6]
          (record! ctx target [{:operation :support :target-claim cid
                                :kind :guard :content earned
                                :episodes [(episode)] :from-legacy-corpus false}])))
      ;; NEGATIVE 1 — an ordinary claim that has earned nothing.
      (record! ctx target [(judged-add unproven)])
      ;; NEGATIVE 2 — a mechanically declared claim: admitted, never authoritative.
      (record! ctx target [{:operation :add :kind :representative-use
                            :content declared :episodes []
                            :from-legacy-corpus false
                            :evidence-basis :classification-signature}])

      (let [all        (claims ctx target)
            validated  (filterv #(= :validated (:status %)) all)
            candidates (filterv #(= :candidate (:status %)) all)
            enforcing  (ontology/get-enforcing-claims ctx :tree-class target)]

        ;; --- NON-VACUITY, stated as counts so the harness prints its N -------
        (println "CC-9d invariant check — claims:" (count all)
                 "validated:" (count validated)
                 "candidates:" (count candidates)
                 "enforcing:" (count enforcing))
        (is (= 4 (count all))
            "all four shapes were recorded — nothing was silently refused")
        (is (= 2 (count validated))
            "BOTH arms of the disjunction are populated, so neither is checked
             vacuously")
        (is (= 2 (count candidates))
            "and both negative shapes are present")

        ;; --- THE INVARIANT ---------------------------------------------------
        (doseq [c validated]
          (is (or (pos? (post-guard-count c)) (= :authored (:evidence-basis c)))
              (str "validated claim " (pr-str (:content c))
                   " must have post-guard evidence OR an authored basis")))

        ;; --- THE WIDENING IS LOAD-BEARING ------------------------------------
        ;; If this set were empty the invariant would be indistinguishable from
        ;; its pre-CC-9d form, and this whole slice would be unwitnessed.
        (let [authored-arm (filterv #(= :authored (:evidence-basis %)) validated)]
          (is (= 1 (count authored-arm))
              "exactly one validated claim rests on the NEW arm")
          (is (zero? (post-guard-count (first authored-arm)))
              "and it has ZERO post-guard episodes — so the PRE-CC-9d form of
               this invariant would fail on it. The widening is what makes the
               curated corpus expressible at all."))
        (let [earned-arm (filterv #(pos? (post-guard-count %)) validated)]
          (is (= 1 (count earned-arm))
              "and exactly one still rests on the OLD arm — the widening did
               not replace it")
          (is (not= :authored (:evidence-basis (first earned-arm)))
              "that one earned its way with no declaration at all"))

        ;; --- CANDIDATES REMAIN NON-ENFORCING ---------------------------------
        (is (= (set (map :claim-id validated)) (set (map :claim-id enforcing)))
            "the enforcement surface is EXACTLY the validated set")
        (is (seq candidates)
            "guard: there are candidates to check, or the next assertion is
             vacuous")
        (let [enforcing-ids (set (map :claim-id enforcing))]
          (doseq [c candidates]
            (is (not (contains? enforcing-ids (:claim-id c)))
                (str "candidate " (pr-str (:content c))
                     " is visible but must not be able to suppress retrieval"))))
        (is (= #{authored earned} (set (map :content enforcing)))
            "so exactly the authored guard and the earned claim enforce")))))
