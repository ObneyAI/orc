(ns ai.obney.orc.ontology.consolidator-claim-path-test
  "CC-5 — the consolidator's CLAIM path, exercised over a PRODUCTION-FAITHFUL
   evidence window.

   WHY THIS FILE EXISTS, AND WHY IT IS NOT A DUPLICATE OF THE CONTRACT.

   `cc5_consolidator_deltas_test` dispatches its consolidations against an event
   store that contains NO observations and NO judge evidence. Six of its eight
   tests therefore require a claim to land from a delta whose occurrences cannot
   be resolved — which CC-4's evidence guard correctly refuses, by design and
   without any involvement from this slice. That is a fixture defect, reported
   rather than edited (see the CC-5 data return); it is the CC-4 lesson
   repeating one layer up, where fabricated-or-absent occurrences hide the fact
   that a claim is supposed to be DERIVED FROM SOMETHING THAT HAPPENED.

   Every test below therefore builds the window the production consolidator
   actually reads: real `:ontology/task-classified` occurrences for the target,
   each carrying a substantive `:judge/score-emitted` judgement. With that in
   place the same behaviours are asserted through the same public surface —
   `consolidate!` in, `get-claims` / `get-description` / the event log out.

   The reflection LLM is stubbed so the contract is deterministic; a live model
   is the orchestrator's QA."
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
        cache-dir (str "/tmp/cc5-claim-path-test-" (random-uuid))
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
;; A production-faithful observation: a classified occurrence that a judge
;; actually judged. This is the shape the consolidator's evidence window is
;; built from, and the shape CC-4's guard can resolve.
;; ---------------------------------------------------------------------------

(defn- observe!
  "Record ONE real occurrence for `tree-class-id` and one substantive judge
   score against it. Returns the `[sheet-id tick-id]` occurrence pair."
  [ctx tree-class-id]
  (let [sheet-id (random-uuid)
        tick-id (random-uuid)]
    (cp/process-command
      (assoc ctx :command
             {:command/name :ontology/assign-task-class
              :command/id (random-uuid) :command/timestamp (time/now)
              :source-sheet-id sheet-id
              :source-tick-id tick-id
              :source-node-id (random-uuid)
              :assigned-tree-id tree-class-id
              :confidence 0.95
              :top-candidates []
              :reasoning "test observation"
              :was-fresh-mint? false}))
    (cp/process-command
      (assoc ctx :command
             {:command/name :evaluation/record-judge-score
              :command/id (random-uuid) :command/timestamp (time/now)
              :sheet-id sheet-id :node-id (random-uuid) :tick-id tick-id
              :judge-name "coding-outcome" :judge-config {} :score 0.8
              :feedback (str "The turn applied the edit to src/util.clj and the "
                             "verification command exited 0, so the assessment is "
                             "grounded in the observed diff and command output.")
              :dimensions []}))
    [sheet-id tick-id]))

(defn- with-window!
  "Seed `n` grounded observations for the target. Returns the occurrence pairs."
  [ctx tree-class-id n]
  (mapv (fn [_] (observe! ctx tree-class-id)) (range n)))

(defn- claims [ctx target] (ontology/get-claims ctx :tree-class target))

(defn- contents [ctx target] (set (map :content (claims ctx target))))

(defmacro with-reflection
  "Stub the reflection LLM to return a fixed operation list."
  [ops & body]
  `(with-redefs [llm/predict (fn [& _#]
                                  {:outputs {:operations ~ops}
                                   :usage {:total-tokens 1} :model "stub"})]
     ~@body))

(defn- body-events [ctx]
  (into [] (es/read (:event-store ctx)
                    {:types #{:ontology/tree-description-updated
                              :ontology/node-type-description-updated
                              :ontology/node-instance-description-updated}
                     :tenant-id (:tenant-id ctx)})))

;; ---------------------------------------------------------------------------
;; rule-success.RecordClaimDeltas — operations land, and CC-5 fills the
;; episodes IN CODE. The model is never asked for occurrence ids.
;; ---------------------------------------------------------------------------

(deftest a-consolidation-over-a-real-window-lands-claims-with-code-filled-episodes
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          window (with-window! ctx target 3)]
      (with-reflection [{:operation :add :kind :weakness
                         :content "claims success on an empty diff"}
                        {:operation :add :kind :strength
                         :content "verifies with a real command"}]
        (consolidator/consolidate! ctx :tree-class target))
      (let [cs (claims ctx target)]
        (is (= 2 (count cs))
            "the consolidation's operations became claims")
        (is (= #{"claims success on an empty diff" "verifies with a real command"}
               (set (map :content cs))))
        (is (every? #(seq (:supporting-episodes %)) cs)
            "episodes are filled from the evidence window, not asked of the model")
        (is (every? (fn [c] (every? (set window) (:supporting-episodes c))) cs)
            "and every filled episode is a REAL occurrence from the window — a
             fabricated pair would be excluded by the guard, so this is the
             property that keeps id-fabrication at zero")
        ;; rule-failure.ExcludeUngroundedDelta.1 — when nothing is ungrounded,
        ;; no exclusion is recorded. The negative control for the guard: an
        ;; exclusion log that fires on grounded work would be indistinguishable
        ;; from one that never fires at all.
        (is (empty? (ontology/get-excluded-evidence ctx :tree-class target))
            "and NOTHING was excluded, because nothing was ungrounded")
        ;; rule-failure.RecordNothingWhenAllDeltasExcluded.2 — the claim set DID
        ;; change, so the "record nothing" rule must not have fired.
        (is (= 1 (ontology/get-claim-set-version ctx :tree-class target))
            "the claim-set version advanced exactly once for this consolidation")))))

;; ---------------------------------------------------------------------------
;; rule-success.RecordNothingWhenAllDeltasExcluded (+ rule-failure
;; .RecordClaimDeltas.2) — when NOTHING survives the guard, the claim set and
;; its VERSION both stand still. A consolidation that learned nothing
;; trustworthy must not look like one that did.
;; ---------------------------------------------------------------------------

(deftest when-every-operation-is-ungrounded-the-claim-set-and-its-version-stand-still
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      ;; Observations exist, but NOBODY JUDGED THEM. Not judged is not judged
      ;; badly — ADR 0023 — so none of them can ground a durable claim.
      (dotimes [_ 2]
        (cp/process-command
          (assoc ctx :command
                 {:command/name :ontology/assign-task-class
                  :command/id (random-uuid) :command/timestamp (time/now)
                  :source-sheet-id (random-uuid)
                  :source-tick-id (random-uuid)
                  :source-node-id (random-uuid)
                  :assigned-tree-id target
                  :confidence 0.95 :top-candidates [] :reasoning "unjudged"
                  :was-fresh-mint? false})))
      (with-reflection [{:operation :add :kind :strength
                         :content "an insight nobody judged"}]
        (consolidator/consolidate! ctx :tree-class target))
      (is (empty? (claims ctx target))
          "nothing was claimed")
      (is (zero? (ontology/get-claim-set-version ctx :tree-class target))
          "and the VERSION did not advance — recording an empty batch would make
           a consolidation that learned nothing look like one that did, and
           would cost a concurrent consolidation its CAS")
      (is (= #{:no-judge-evidence}
             (set (map :reason (ontology/get-excluded-evidence ctx :tree-class target))))
          "the refusal is recorded and names its ADR-0023 reason"))))

;; ---------------------------------------------------------------------------
;; THE WIRE FORMAT. This test exists because a LIVE model run found a defect
;; every stubbed test in this repo was structurally blind to.
;; ---------------------------------------------------------------------------

(deftest operations-arriving-in-the-models-real-wire-format-are-accepted
  ;; Driven against the real judges'-slot model, the reflection returns
  ;;     {:operation "support", :target-claim "<id>"}
  ;; — the enum as a JSON STRING, and no :content at all, because a support
  ;; restates nothing. Both were rejected by the first implementation of
  ;; `prepare-operations`: the schema declares keyword enums and `claim-delta`
  ;; requires content. The whole contract suite was green throughout, because
  ;; every stub in it hands the consolidator keywords and a content string.
  ;;
  ;; The measured consequence was total: 0 of 6 supportable claims touched, 0
  ;; operations dispatched, 0 exclusions recorded — a consolidation that looked
  ;; like a model saying nothing and was actually a parser throwing everything
  ;; away. This test pins the real shape so that cannot come back quietly.
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-window! ctx target 2)
      (with-reflection [{:operation "add" :kind "weakness"
                         :content "reports verification success without running a command"}]
        (consolidator/consolidate! ctx :tree-class target))
      (is (= 1 (count (claims ctx target)))
          "an operation whose enums arrived as strings still lands")
      (let [c (first (claims ctx target))]
        (is (= :weakness (:kind c)) "and its enums were coerced, not stored as strings")
        (with-window! ctx target 2)
        (with-reflection [{:operation "support" :target-claim (:claim-id c)}]
          (consolidator/consolidate! ctx :tree-class target))
        (let [after (first (claims ctx target))]
          (is (= 1 (count (claims ctx target)))
              "a bare support did not fork the claim")
          (is (= 3 (:support after))
              "the reinforcement LANDED — this is the assertion that was silently
               false against the real model")
          (is (= (:content c) (:content after))
              "and a support with no :content keeps the claim's own wording
               rather than blanking it"))))))

;; ---------------------------------------------------------------------------
;; config-default.initial_claim_support, through the consolidator this time.
;; ---------------------------------------------------------------------------

(deftest a-claim-added-by-a-consolidation-is-seeded-above-the-retirement-floor
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-window! ctx target 2)
      (with-reflection [{:operation :add :kind :weakness :content "a fresh insight"}]
        (consolidator/consolidate! ctx :tree-class target))
      (is (= 2 (:support (first (claims ctx target))))
          "at a seed of 1 a single contradiction erases a brand-new claim, which
           contradicts ADR 0021's own property that no single judgement can
           erase accumulated knowledge"))))

;; ---------------------------------------------------------------------------
;; The core property the retired validator destroyed: a rephrasing
;; STRENGTHENS the claim it matches rather than duplicating it.
;; ---------------------------------------------------------------------------

(deftest a-rephrasing-reinforces-the-claim-it-matches
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-window! ctx target 2)
      (with-reflection [{:operation :add :kind :weakness
                         :content "hallucinates write success on empty diffs"}]
        (consolidator/consolidate! ctx :tree-class target))
      (let [cid (:claim-id (first (claims ctx target)))
            before (:support (first (claims ctx target)))]
        (with-window! ctx target 2)
        (with-reflection [{:operation :support :target-claim cid
                           :content "hallucinates write success on empty diffs"}]
          (consolidator/consolidate! ctx :tree-class target))
        (let [cs (claims ctx target)]
          (is (= 1 (count cs))
              "the insight the old validator rejected for wording now strengthens
               the claim it matches instead of duplicating it")
          (is (= (inc before) (:support (first cs))))
          (is (= :weakness (:kind (first cs)))
              "and the kind is filled from the claim — a :support operation is
               not asked to restate what it is only reinforcing"))))))

;; ---------------------------------------------------------------------------
;; P-A finding: unknown keys must be rejected, not silently dropped.
;; ---------------------------------------------------------------------------

(deftest an-operation-carrying-a-drifted-key-is-rejected-and-its-siblings-survive
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-window! ctx target 2)
      ;; :guard is not a claim-delta key. The delta schema is an OPEN malli map,
      ;; so it validates and the value vanishes without a word.
      (with-reflection [{:operation :add :kind :strength
                         :content "a well-formed claim"}
                        {:operation :add :kind :weakness
                         :content "a claim with drifted fields"
                         :guard "this text would disappear silently"}]
        (consolidator/consolidate! ctx :tree-class target))
      (is (contains? (contents ctx target) "a well-formed claim")
          "the valid operation in the same batch still lands — rejection is
           per-operation, never per-batch")
      (is (not (contains? (contents ctx target) "a claim with drifted fields"))
          "and the drifted operation is refused outright rather than recorded
           with its data silently discarded"))))

;; ---------------------------------------------------------------------------
;; P-A finding: one consolidation is ONE occurrence.
;; ---------------------------------------------------------------------------

(deftest repeating-a-target-in-one-operation-list-does-not-inflate-support
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-window! ctx target 2)
      (with-reflection [{:operation :add :kind :weakness :content "an insight"}]
        (consolidator/consolidate! ctx :tree-class target))
      (let [cid (:claim-id (first (claims ctx target)))
            before (:support (first (claims ctx target)))]
        (with-window! ctx target 2)
        (with-reflection [{:operation :support :target-claim cid :content "an insight"}
                          {:operation :edit :target-claim cid :content "an insight, reworded"}]
          (consolidator/consolidate! ctx :tree-class target))
        (let [c (first (claims ctx target))]
          (is (= (inc before) (:support c))
              "support moves by ONE: repeating a target would feed straight into
               the validation threshold and the enforcement weighting")
          (is (= "an insight, reworded" (:content c))
              "and the :edit wins the collapse, because an edit is a superset of
               a support — so collapsing the pair loses nothing"))))))

;; ---------------------------------------------------------------------------
;; rule-success.ExcludeUngroundedDelta — per delta, never per batch.
;; ---------------------------------------------------------------------------

(deftest an-ungrounded-operation-is-excluded-while-its-batch-survives
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          [grounded] (with-window! ctx target 1)]
      (with-reflection [{:operation :add :kind :strength
                         :content "grounded insight" :episodes [grounded]}
                        {:operation :add :kind :weakness
                         :content "ungrounded insight" :episodes [[(random-uuid) (random-uuid)]]}]
        (consolidator/consolidate! ctx :tree-class target))
      (is (contains? (contents ctx target) "grounded insight"))
      (is (not (contains? (contents ctx target) "ungrounded insight")))
      (is (seq (ontology/get-excluded-evidence ctx :tree-class target))
          "and the exclusion is RECORDED — a silent drop destroys the ability to
           measure how much of our evidence was starved"))))

;; ---------------------------------------------------------------------------
;; THE RETIREMENT: no whole-body write, no validator.
;; ---------------------------------------------------------------------------

(deftest a-claim-path-consolidation-emits-no-whole-body-description-event
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-window! ctx target 2)
      (with-reflection [{:operation :add :kind :weakness :content "an insight"}]
        (consolidator/consolidate! ctx :tree-class target))
      (is (= 1 (count (claims ctx target)))
          "the consolidation actually produced a claim")
      (is (empty? (body-events ctx))
          "AND wrote no whole body: two writers on one :current slot means a
           legacy consolidation silently overwrites an assembled body while
           leaving its claims intact")
      (is (some? (ontology/get-description ctx :tree-class target))
          "the body is still there — it is ASSEMBLED from the claims"))))

(deftest no-consolidation-is-refused-for-wording
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-window! ctx target 2)
      (with-reflection [{:operation :add :kind :weakness
                         :content "falsely claims verification success when commands fail"}]
        (consolidator/consolidate! ctx :tree-class target))
      (let [cid (:claim-id (first (claims ctx target)))]
        (with-window! ctx target 2)
        (with-reflection [{:operation :edit :target-claim cid :kind :weakness
                           :content "hallucinates successful verification when commands fail or are skipped"}]
          (consolidator/consolidate! ctx :tree-class target))
        (is (empty? (into [] (es/read (:event-store ctx)
                                      {:types #{:ontology/anti-recency-rejection}
                                       :tenant-id (:tenant-id ctx)})))
            "the valve is gone: it rejected 145 of 145 real consolidations for
             rewording, and about nine in ten of those rejections were wrong")
        (is (= "hallucinates successful verification when commands fail or are skipped"
               (:content (first (claims ctx target))))
            "the rewording LANDED")
        (is (= 1 (count (claims ctx target)))
            "on the SAME claim — the rephrasing did not fork the knowledge")))))

;; ---------------------------------------------------------------------------
;; The two-writers-on-one-slot decision: a target that has only a LEGACY body.
;; ---------------------------------------------------------------------------

(deftest a-legacy-only-target-has-its-body-converted-to-claims-not-overwritten
  (with-test-ctx [ctx]
    (let [target (random-uuid)
          legacy {:capabilities ["runs a sub-LLM call"]
                  :strengths [{:trait "verifies with a real command"
                               :good-when "the change is executable"
                               :recommended-pattern "run the test suite"
                               :confidence 0.9 :evidence-count 7
                               :first-observed-at "2026-05-20T00:00:00Z"
                               :last-reinforced-at "2026-05-26T00:00:00Z"}]
                  :weaknesses [{:trait "claims success on an empty diff"
                                :avoid-when "no file was written"
                                :recommended-alternative "re-read the diff"
                                :confidence 0.95 :evidence-count 9
                                :first-observed-at "2026-05-20T00:00:00Z"
                                :last-reinforced-at "2026-05-26T00:00:00Z"}]
                  :representative-uses ["per-chunk extraction"]
                  :avoid-when ["deterministic work"]
                  :summary "A legacy whole-body description."
                  :version 4
                  :consolidated-from-event-count 40}]
      ;; A target described the legacy way, with no claims.
      (cp/process-command
        (assoc ctx :command
               {:command/name :ontology/record-tree-class-description
                :command/id (random-uuid) :command/timestamp (time/now)
                :target-id target :body legacy}))
      (is (empty? (claims ctx target)) "precondition: no claims yet")
      (with-window! ctx target 2)
      (with-reflection [{:operation :add :kind :weakness
                         :content "a newly observed insight"}]
        (consolidator/consolidate! ctx :tree-class target))
      (let [cs (claims ctx target)
            texts (set (map :content cs))]
        (is (contains? texts "claims success on an empty diff")
            "the legacy body's WEAKNESS survived as a claim — the first claim
             event recomputes :current from the claim set, so a target whose
             body was not converted would lose a lifetime of consolidations in
             one fold")
        (is (contains? texts "verifies with a real command")
            "and its strength")
        (is (contains? texts "runs a sub-LLM call") "and its capabilities")
        (is (contains? texts "per-chunk extraction") "and its representative uses")
        (is (contains? texts "deterministic work") "and its body-level guards")
        (is (contains? texts "a newly observed insight")
            "and the new consolidation's own insight landed on top")
        (is (every? :legacy-provenance
                    (filter #(= "claims success on an empty diff" (:content %)) cs))
            "converted claims declare their provenance, so CC-7 will not let
             them validate on evidence nobody can re-examine")
        (is (= 4 (count (filter #(= :candidate (:status %)) (take 4 cs))))
            "and they arrive as candidates")))))

;; ---------------------------------------------------------------------------
;; The budget gate must keep bounding the path that consolidates most often.
;; ---------------------------------------------------------------------------

(deftest a-claim-path-consolidation-counts-against-the-hourly-budget
  (with-test-ctx [ctx]
    (let [target (random-uuid)]
      (with-window! ctx target 2)
      (is (zero? (ontology/get-recent-consolidation-count ctx :tree-class))
          "precondition: nothing consolidated yet")
      (with-reflection [{:operation :add :kind :weakness :content "an insight"}]
        (consolidator/consolidate! ctx :tree-class target))
      (is (= 1 (ontology/get-recent-consolidation-count ctx :tree-class))
          "a claim-path consolidation costs the same LLM call as a body-path one
           and must count against the same budget — it emits no
           *-description-updated event, so without this it would be free"))))
