(ns ai.obney.orc.ontology.s12-dedup-cascade-test
  "S12 — Tiered, cheapest-first dedup cascade with a disjointness KEEP-guard
   as the FIRST gate, structured number/negation/entity KEEP rules, and a
   focused LLM merge/keep verdict ONLY for ambiguity-band pairs.

   The prototype (Phase A) authored a 20-pair adversarial fixture set with
   ground-truth verdicts; THIS file ports each fixture into a test assertion
   through public interfaces (commands + read-model projections + event-
   store reads). The LLM tier is exercised via a deterministic mock encoded
   in `core.dedup-cascade/mock-llm-verdict` for unit / contract coverage; a
   parallel live-verify script (development/src/s12_live_verify.clj) drives
   the real OpenRouter path for the cost discipline and KEEP-rule verdicts.

   Test contract:
     - All writes via commands. No bare event-store appends in production.
     - All assertions via public interfaces (cp/process-command, rmp/project,
       es/read). No internals tested.
     - The disjointness guard MUST fire BEFORE any LLM call — verified by
       instrumenting the cascade's `:llm-counter` atom and asserting
       `@llm-counter == 0` for disjoint pairs.
     - LLM budget exhaustion surfaces `:requires-review` — NEVER silently
       merges, NEVER silently skips."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.ontology.test-helpers :as h]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.dedup-cascade :as dedup]
            [ai.obney.orc.ontology.core.concept-stream :as cs]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]))

(def primary-p #uuid "5120000a-0000-0000-0000-000000000001")
(def primary-q #uuid "5120000a-0000-0000-0000-000000000002")
(def align-pq  #uuid "5120000a-0000-0000-0000-000000000003")

;; =============================================================================
;; Test helpers
;; =============================================================================

(defn- assert-disjointness! [ctx body]
  (h/run-and-apply! ctx
                    (fn [c]
                      (cmd/ontology-assert-disjointness (assoc c :command body)))))

(defn- register-alignment! [ctx primary alignment]
  (h/run-and-apply! ctx
                    (fn [c]
                      (cmd/ontology-register-alignment-section
                       (assoc c :command {:primary-ontology-id primary
                                          :alignment-ontology-id alignment})))))

(defn- create-concept! [ctx body]
  (h/run-and-apply! ctx
                    (fn [c]
                      (cmd/ontology-create-concept (assoc c :command body)))))

(defn- run-cascade-cmd!
  "Drive the cascade via the public command path. Returns the command result
   (carries `:command-result/data {:verdict ...}` + the events). `:llm-fn`
   may be passed via the ctx (the dedup-cascade pure fn picks it up)."
  [ctx body & {:keys [llm-fn]}]
  (let [ctx' (cond-> ctx llm-fn (assoc :llm-fn llm-fn))]
    (h/run-and-apply! ctx'
                      (fn [c]
                        (cmd/ontology-run-dedup-cascade (assoc c :command body))))))

(defn- read-events-by-tag
  "Read raw events under a single tag from the event store."
  [ctx tag]
  (into [] (es/read (:event-store ctx)
                    {:tags #{tag}
                     :tenant-id (:tenant-id ctx)})))

;; =============================================================================
;; Adversarial fixtures — verbatim from the prototype's GROUND TRUTH set
;; =============================================================================

(def case-variant-a
  {:uri "p:Director1" :label "Director" :description "person who directs films"
   :type :class :kind-hint :same-as})
(def case-variant-b
  {:uri "p:Director2" :label "director" :description "person who directs films"
   :type :class :kind-hint :same-as})

(def whitespace-variant-a
  {:uri "p:CEO1" :label "Chief Executive Officer" :description "head of a company"
   :type :class :kind-hint :same-as})
(def whitespace-variant-b
  {:uri "p:CEO2" :label "  Chief Executive   Officer  " :description "head of a company"
   :type :class :kind-hint :same-as})

(def unicode-nfc-a
  ;; precomposed é (U+00E9)
  {:uri "p:Cafe1" :label "Café" :description "coffeehouse" :type :class
   :kind-hint :same-as})
(def unicode-nfc-b
  ;; decomposed e + combining acute (U+0065 U+0301)
  {:uri "p:Cafe2" :label "Café" :description "coffeehouse" :type :class
   :kind-hint :same-as})

(def jw-near-a
  {:uri "p:Organization1" :label "Organization" :description "a structured group of people"
   :type :class :kind-hint :equivalent-class})
(def jw-near-b
  {:uri "p:Organisation1" :label "Organisation" :description "a structured group of people"
   :type :class :kind-hint :equivalent-class})

(def equiv-prop-a
  {:uri "p:hasAuthor" :label "hasAuthor" :description "links a work to its author"
   :type :property :kind-hint :equivalent-property})
(def equiv-prop-b
  {:uri "p:hasWriter" :label "hasWriter" :description "links a work to its author"
   :type :property :kind-hint :equivalent-property})

(def number-3-vs-30-a
  {:uri "p:Model3" :label "Model 3" :description "Tesla Model 3" :type :class})
(def number-3-vs-30-b
  {:uri "p:Model30" :label "Model 30" :description "Tesla Model 30" :type :class})

(def negation-approved-a
  {:uri "p:Approved" :label "approved" :description "passed review" :type :class})
(def negation-approved-b
  {:uri "p:NotApproved" :label "not approved" :description "did not pass review" :type :class})

(def negation-present-a
  {:uri "p:Present" :label "present" :description "found in the sample" :type :class})
(def negation-present-b
  {:uri "p:Absent" :label "absent" :description "not found in the sample" :type :class})

(def paris-city-a
  {:uri "p:ParisCity" :label "Paris" :description "the capital city of France"
   :type :individual})
(def paris-person-b
  {:uri "p:ParisPerson" :label "Paris" :description "the person who wrote the memoir"
   :type :individual})

(def disjoint-whale-a
  {:uri "bio:Whale" :label "Whale" :description "a Whale species"
   :type :class :broader ["bio:Mammal"]})
(def disjoint-crocodile-b
  {:uri "bio:Crocodile" :label "Crocodile" :description "a Crocodile species"
   :type :class :broader ["bio:Reptile"]})

(def short-label-a
  {:uri "p:A" :label "A" :description "" :type :class})
(def short-label-b
  {:uri "p:B" :label "An" :description "" :type :class})

(def type-mismatch-a
  {:uri "p:AuthorClass" :label "Author" :description "" :type :class})
(def type-mismatch-b
  {:uri "p:authorProperty" :label "Author" :description "" :type :property})

;; =============================================================================
;; AC1 — Adversarial MERGE: every case variant / whitespace variant /
;; Unicode-NFC variant / JW-near-identical / equivalent-property pair
;; produces the correct equivalence event WITH correct kind.
;; =============================================================================

(deftest merge-case-variant-emits-same-as-equivalence
  (testing "T6 :exact-normalization fires for Director vs director (case
            variant). The cascade emits a :ontology/equivalence-recorded
            event with :kind :same-as tagged to the alignment section."
    (h/with-test-context [ctx]
      (let [r (run-cascade-cmd! ctx
                                {:ontology-id primary-p
                                 :alignment-ontology-id align-pq
                                 :a case-variant-a :b case-variant-b})
            v (get-in r [:command-result/data :verdict])
            align-events (read-events-by-tag ctx [:ontology align-pq])
            equiv (first (filter #(= :ontology/equivalence-recorded (:event/type %))
                                 align-events))]
        (is (= :merge (:verdict v)))
        (is (= :exact-normalization (:tier v)))
        (is (= :same-as (:kind v)))
        (is (some? equiv) "equivalence event landed under alignment-section tag")
        (is (= :same-as (:kind equiv)) "kind preserved on the event")))))

(deftest merge-whitespace-variant-emits-same-as
  (testing "T6 fires for whitespace-variant CEO labels."
    (h/with-test-context [ctx]
      (let [r (run-cascade-cmd! ctx
                                {:ontology-id primary-p
                                 :alignment-ontology-id align-pq
                                 :a whitespace-variant-a :b whitespace-variant-b})
            v (get-in r [:command-result/data :verdict])]
        (is (= :merge (:verdict v)))
        (is (= :exact-normalization (:tier v)))
        (is (= :same-as (:kind v)))))))

(deftest merge-unicode-nfc-variant-emits-same-as
  (testing "Unicode decomposed vs precomposed 'Café' both normalize to the
            same NFC form — T6 fires."
    (h/with-test-context [ctx]
      (let [r (run-cascade-cmd! ctx
                                {:ontology-id primary-p
                                 :alignment-ontology-id align-pq
                                 :a unicode-nfc-a :b unicode-nfc-b})
            v (get-in r [:command-result/data :verdict])]
        (is (= :merge (:verdict v)))
        (is (= :exact-normalization (:tier v)))
        (is (= :same-as (:kind v)))))))

(deftest merge-jw-near-identical-emits-equivalent-class
  (testing "T8a :string-similarity-high fires for Organization vs
            Organisation (JW ≥ 0.95). Kind comes from the candidate's
            kind-hint."
    (h/with-test-context [ctx]
      (let [r (run-cascade-cmd! ctx
                                {:ontology-id primary-p
                                 :alignment-ontology-id align-pq
                                 :a jw-near-a :b jw-near-b})
            v (get-in r [:command-result/data :verdict])]
        (is (= :merge (:verdict v)))
        (is (= :string-similarity-high (:tier v)))
        (is (= :equivalent-class (:kind v)))))))

(deftest merge-property-pair-emits-equivalent-property
  (testing "hasAuthor vs hasWriter — camelCase word-token blocking gets the
            pair past T7 (LSH), JW lands in the ambiguity band, the LLM
            mock returns :merge with kind :equivalent-property."
    (h/with-test-context [ctx]
      (let [r (run-cascade-cmd! ctx
                                {:ontology-id primary-p
                                 :alignment-ontology-id align-pq
                                 :a equiv-prop-a :b equiv-prop-b})
            v (get-in r [:command-result/data :verdict])]
        (is (= :merge (:verdict v)))
        (is (= :llm-verdict (:tier v))
            "the property pair reaches T9 — camelCase blocking + ambiguity band")
        (is (= :equivalent-property (:kind v)))))))

;; =============================================================================
;; AC2 — Adversarial KEEP: number / negation / surface-form-distinct /
;; disjoint-class pairs all produce :distinct AND a
;; :ontology/dedup-distinct-recorded event.
;; =============================================================================

(deftest keep-number-variant-emits-dedup-distinct
  (testing "Number variants (Model 3 vs Model 30) → T2 :number-guard
            verdict :distinct :reason :number-difference. A
            :ontology/dedup-distinct-recorded event is emitted."
    (h/with-test-context [ctx]
      (let [r (run-cascade-cmd! ctx
                                {:ontology-id primary-p
                                 :a number-3-vs-30-a :b number-3-vs-30-b})
            v (get-in r [:command-result/data :verdict])
            primary-events (read-events-by-tag ctx [:ontology primary-p])
            distinct-evt (first (filter #(= :ontology/dedup-distinct-recorded
                                            (:event/type %))
                                        primary-events))]
        (is (= :distinct (:verdict v)))
        (is (= :number-guard (:tier v)))
        (is (= :number-difference (:reason v)))
        (is (some? distinct-evt) "dedup-distinct-recorded event landed")
        (is (= :number-difference (:reason distinct-evt)) "reason preserved")
        (is (= :number-guard (:tier distinct-evt)) "tier preserved")))))

(deftest keep-negation-approved-emits-dedup-distinct
  (testing "Negation pairs (approved vs not approved) → T3 :negation-guard
            verdict :distinct."
    (h/with-test-context [ctx]
      (let [r (run-cascade-cmd! ctx
                                {:ontology-id primary-p
                                 :a negation-approved-a :b negation-approved-b})
            v (get-in r [:command-result/data :verdict])]
        (is (= :distinct (:verdict v)))
        (is (= :negation-guard (:tier v)))
        (is (= :negation-difference (:reason v)))))))

(deftest keep-negation-polarity-antonym-emits-dedup-distinct
  (testing "Polarity antonyms (present vs absent) — no negation prefix on
            either side, but the canonical antonym set catches them at T3."
    (h/with-test-context [ctx]
      (let [r (run-cascade-cmd! ctx
                                {:ontology-id primary-p
                                 :a negation-present-a :b negation-present-b})
            v (get-in r [:command-result/data :verdict])]
        (is (= :distinct (:verdict v)))
        (is (= :negation-guard (:tier v)))
        (is (= :negation-difference (:reason v)))))))

(deftest keep-surface-form-distinct-entities-via-llm
  (testing "Paris the city vs Paris the person — labels exact-equal after
            normalization BUT descriptions disagree. T6 defers to the LLM
            tier; the LLM (mock) routes via the entity-disambiguation
            check and returns :distinct :reason :entity."
    (h/with-test-context [ctx]
      (let [r (run-cascade-cmd! ctx
                                {:ontology-id primary-p
                                 :a paris-city-a :b paris-person-b})
            v (get-in r [:command-result/data :verdict])]
        (is (= :distinct (:verdict v)))
        (is (= :llm-verdict (:tier v))
            "label-match-but-desc-disagree pairs reach the LLM tier")
        (is (= :entity (:reason v)))))))

;; =============================================================================
;; AC3 — Disjointness KEEP-guard fires BEFORE any LLM call (zero LLM calls
;; for disjoint pairs; instrumented at the cascade level).
;; =============================================================================

(deftest disjointness-guard-fires-before-llm
  (testing "ADVERSARIAL: when the pair's broader classes are asserted
            disjoint via S07, the cascade STOPS at T1 — zero LLM
            invocations. Instrumented at the pure-cascade level so the
            call count is observable; the defcommand path also goes
            through the S07 axioms projection without an LLM call.

            The pair under test has labels that would otherwise reach the
            T9 LLM tier (label-equal-but-desc-different, or JW
            ambiguity-band). The disjointness guard MUST intercept it
            first."
    (h/with-test-context [ctx]
      ;; Seed the disjointness axiom via the public command.
      (assert-disjointness! ctx {:ontology-id primary-p
                                 :class-uris ["bio:Mammal" "bio:Reptile"]})

      ;; Pure-cascade instrumentation — call counter visible to the test.
      (let [llm-counter (atom 0)
            disjointness (get-in (ai.obney.grain.read-model-processor-v2.interface/project
                                  ctx :ontology/axioms)
                                 [primary-p :disjointness])
            disjoint-fn (fn [_ _]
                          (dedup/disjoint-under-axioms?
                           disjointness
                           (:broader disjoint-whale-a [])
                           (:broader disjoint-crocodile-b [])))
            llm-fn (fn [_]
                     (swap! llm-counter inc)
                     ;; Even if reached, the verdict shape would be merge —
                     ;; this is what makes the assertion adversarial: if
                     ;; the guard ever failed, a merge would silently happen.
                     {:verdict :merge :kind :same-as :reason "should never run"})
            v (dedup/run-cascade {:a disjoint-whale-a :b disjoint-crocodile-b
                                  :disjoint-pair-fn disjoint-fn
                                  :llm-fn llm-fn
                                  :llm-counter llm-counter})]
        (is (= :distinct (:verdict v))
            "the disjoint pair is KEEP (never merged)")
        (is (= :disjointness-guard (:tier v))
            "the verdict came from T1 — not from the LLM tier")
        (is (= :disjointness-guard (:reason v)))
        (is (zero? @llm-counter)
            "ADVERSARIAL: the LLM was NEVER called for the disjoint pair"))

      ;; And via the public command path — emits dedup-distinct + co-occurrence,
      ;; reusing the projected axioms (no LLM call at the defcommand level either).
      (let [r (run-cascade-cmd! ctx
                                {:ontology-id primary-p
                                 :a disjoint-whale-a :b disjoint-crocodile-b})
            v (get-in r [:command-result/data :verdict])
            primary-events (read-events-by-tag ctx [:ontology primary-p])
            distinct-evt (first (filter #(and (= :ontology/dedup-distinct-recorded
                                                 (:event/type %))
                                              (= "bio:Whale" (:source-uri %)))
                                        primary-events))]
        (is (= :distinct (:verdict v)))
        (is (= :disjointness-guard (:tier v)))
        (is (some? distinct-evt))
        (is (= :disjointness-guard (:reason distinct-evt))
            "the persisted event carries the disjointness reason — provenance
             survives the command boundary")))))

;; =============================================================================
;; AC4 — Cost discipline: cheap tiers handle their cases with ZERO LLM calls
;; =============================================================================

(deftest cheap-tiers-do-not-call-llm
  (testing "ADVERSARIAL — cost discipline. Running the cascade over the
            cheap-tier fixtures (exact-norm + number-guard + negation-guard
            + LSH + entropy-gate + type-blocking) does NOT invoke the LLM.

            Instrumented via a counted llm-fn that increments an atom on
            every call — assertion is `(zero? @counter)`."
    (let [counter (atom 0)
          counted-fn (fn [m]
                       (swap! counter inc)
                       (dedup/mock-llm-verdict m))
          run #(dedup/run-cascade (assoc % :llm-fn counted-fn :llm-counter counter))
          cheap-pairs [[case-variant-a case-variant-b]
                       [whitespace-variant-a whitespace-variant-b]
                       [unicode-nfc-a unicode-nfc-b]
                       [number-3-vs-30-a number-3-vs-30-b]
                       [negation-approved-a negation-approved-b]
                       [negation-present-a negation-present-b]
                       [short-label-a short-label-b]
                       [type-mismatch-a type-mismatch-b]
                       ;; Foo vs Bar: zero-overlap → T7 LSH skip
                       [{:uri "p:Foo" :label "Foo" :description "" :type :class}
                        {:uri "p:Bar" :label "Bar" :description "" :type :class}]]]
      (doseq [[a b] cheap-pairs]
        (let [v (run {:a a :b b})]
          ;; Sanity: none of these reach the LLM tier.
          (is (not= :llm-verdict (:tier v))
              (str "cheap pair routed via " (:tier v) " — never to LLM"))))
      (is (zero? @counter)
          (str "ADVERSARIAL: cheap tiers issued " @counter
               " LLM calls — expected zero")))))

;; =============================================================================
;; AC5 — Cross-section check-before-mint
;; =============================================================================

(defn- check-before-mint!
  "Compose the cascade with the alignment-section widening from S03. The
   builder's check-before-mint hook is:
     1. Widen primary's ontology-id to (primary ∪ alignment-sections).
     2. For each candidate in the WIDENED scope, run the cascade.
     3. On :merge, the equivalence is recorded into the alignment section
        and the existing canonical URI is REUSED (NO new concept-created
        event for the duplicate in the primary section).
   This helper is a thin orchestrator the production builder will
   invoke; the test exercises the orchestration."
  [ctx primary-id alignment-id candidate-from-q widened-section-candidates]
  (let [verdict (some (fn [other]
                        (let [r (run-cascade-cmd!
                                 ctx {:ontology-id primary-id
                                      :alignment-ontology-id alignment-id
                                      :a candidate-from-q
                                      :b other})
                              v (get-in r [:command-result/data :verdict])]
                          (when (= :merge (:verdict v))
                            {:verdict v :canonical (:uri other)})))
                      widened-section-candidates)]
    (if verdict
      {:merged? true :reused-uri (:canonical verdict)}
      ;; No merge — caller mints fresh.
      {:merged? false})))

(deftest check-before-mint-reuses-canonical-uri
  (testing "AC5 — cross-section check-before-mint. A new candidate's labels
            collide with an existing canonical concept in another primary
            section (registered as an alignment). The cascade emits an
            equivalence event into the alignment section AND signals that
            the canonical URI should be reused — NO new concept-created
            event lands in the primary section under the duplicate URI.

            Assertions:
              (a) The canonical concept exists in primary-p unchanged.
              (b) ONE equivalence event lands under [:ontology align-pq].
              (c) No new :ontology/concept-created event lands in primary-q
                  under the duplicate URI (the test models this by checking
                  the check-before-mint helper returned :merged? true)."
    (h/with-test-context [ctx]
      ;; Register alignment.
      (register-alignment! ctx primary-q align-pq)
      ;; Seed canonical in primary-p.
      (create-concept! ctx {:ontology-id primary-p :uri "p:Director"
                            :label "Director"
                            :description "person who directs films"
                            :scope :custom})
      (let [;; Candidate the BUILDER wants to mint under primary-q (same label).
            candidate-q {:uri "q:Director" :label "director"
                         :description "person who directs films"
                         :type :class :kind-hint :same-as}
            ;; The widened candidate pool: the canonical in p.
            existing-p {:uri "p:Director" :label "Director"
                        :description "person who directs films"
                        :type :class :kind-hint :same-as}
            outcome (check-before-mint! ctx primary-q align-pq
                                        candidate-q [existing-p])
            ;; Inspect the resulting event streams.
            primary-p-events (read-events-by-tag ctx [:ontology primary-p])
            primary-q-events (read-events-by-tag ctx [:ontology primary-q])
            align-events    (read-events-by-tag ctx [:ontology align-pq])
            equiv-events    (filter #(= :ontology/equivalence-recorded (:event/type %))
                                    align-events)
            concept-q-events (filter #(and (= :ontology/concept-created (:event/type %))
                                           (= "q:Director" (:uri %)))
                                     primary-q-events)
            concept-p-events (filter #(and (= :ontology/concept-created (:event/type %))
                                           (= "p:Director" (:uri %)))
                                     primary-p-events)]
        (is (:merged? outcome) "check-before-mint signaled MERGE")
        (is (= "p:Director" (:reused-uri outcome)) "canonical URI reused")
        (is (= 1 (count equiv-events))
            "exactly ONE equivalence event in the alignment section")
        (is (= 1 (count concept-p-events))
            "canonical concept exists in primary-p — unchanged")
        (is (zero? (count concept-q-events))
            "NO new concept-created event in primary-q under the duplicate URI")))))

;; =============================================================================
;; AC6 — Co-occurrence events emitted per pair (not duplicated)
;; =============================================================================

(deftest co-occurrence-event-emitted-per-pair
  (testing "Every cascade invocation emits exactly ONE
            :ontology/concept-pair-co-occurrence event tagged to the
            primary section. The event carries the canonical pair, the
            tier that closed the verdict, and the verdict itself.

            Adversarial twin: running the cascade TWICE for the same pair
            emits TWO co-occurrence events (not silently aggregated at
            write time — that's a later context-aware-disambiguation
            slice's job)."
    (h/with-test-context [ctx]
      (run-cascade-cmd! ctx
                        {:ontology-id primary-p
                         :a number-3-vs-30-a :b number-3-vs-30-b})
      (run-cascade-cmd! ctx
                        {:ontology-id primary-p
                         :a number-3-vs-30-a :b number-3-vs-30-b})
      (let [primary-events (read-events-by-tag ctx [:ontology primary-p])
            co-events (filter #(= :ontology/concept-pair-co-occurrence
                                  (:event/type %))
                              primary-events)
            first-evt (first co-events)]
        (is (= 2 (count co-events))
            "two runs ⇒ two co-occurrence events (one per appearance)")
        (is (= "p:Model3" (:source-uri first-evt)))
        (is (= "p:Model30" (:target-uri first-evt)))
        (is (= :distinct (:verdict first-evt)))
        (is (= :number-guard (:context-source first-evt))
            "context-source carries the deciding tier")))))

;; =============================================================================
;; AC7 — LLM budget exhaustion surfaces :requires-review
;; =============================================================================

(deftest llm-budget-exhaustion-surfaces-requires-review
  (testing "ADVERSARIAL — when :llm-budget is 0 and a pair would have hit
            T9, the cascade returns :verdict :requires-review (NEVER
            silently merges, NEVER silently skips). Asserted via the
            public command surface."
    (h/with-test-context [ctx]
      (let [r (run-cascade-cmd! ctx
                                {:ontology-id primary-p
                                 :alignment-ontology-id align-pq
                                 :a equiv-prop-a :b equiv-prop-b
                                 :llm-budget 0})
            v (get-in r [:command-result/data :verdict])
            primary-events (read-events-by-tag ctx [:ontology primary-p])
            co-evt (first (filter #(= :ontology/concept-pair-co-occurrence
                                      (:event/type %))
                                  primary-events))
            align-events (read-events-by-tag ctx [:ontology align-pq])]
        (is (= :requires-review (:verdict v))
            "budget-exhausted pair surfaces as :requires-review")
        (is (= :llm-budget-exhausted (:tier v)))
        (is (= :requires-review (:verdict co-evt))
            "co-occurrence event carries the :requires-review verdict")
        (is (zero? (count (filter #(= :ontology/equivalence-recorded
                                      (:event/type %))
                                  align-events)))
            "no equivalence event — budget exhaustion did NOT silently
             merge")))))

;; =============================================================================
;; AC8 — Merge verdict requires alignment-ontology-id (no silent downgrade)
;; =============================================================================

(deftest merge-without-alignment-id-returns-anomaly
  (testing "ADVERSARIAL — a :merge verdict reached without an
            :alignment-ontology-id on the command returns ::anom/incorrect
            rather than silently downgrading to :skip or emitting a
            stray equivalence event without a section. This protects the
            S08 invariant that equivalences ONLY live in alignment
            sections."
    (h/with-test-context [ctx]
      (let [r (cp/process-command
               (assoc ctx :command
                      {:command/name :ontology/run-dedup-cascade
                       :command/id (random-uuid)
                       :command/timestamp (time/now)
                       :ontology-id primary-p
                       :a case-variant-a :b case-variant-b
                       ;; :alignment-ontology-id intentionally OMITTED
                       }))]
        (is (= ::anom/incorrect (::anom/category r))
            "no alignment-id + merge verdict → anomaly")
        (is (empty? (:command-result/events r))
            "no events emitted on the anomaly path")))))

;; =============================================================================
;; AC9 — Schema gate rejects malformed commands
;; =============================================================================

(deftest malformed-cascade-command-rejected
  (testing "Missing :a or :b is rejected at the pre-handler Malli gate."
    (h/with-test-context [ctx]
      (let [r (cp/process-command
               (assoc ctx :command
                      {:command/name :ontology/run-dedup-cascade
                       :command/id (random-uuid)
                       :command/timestamp (time/now)
                       :ontology-id primary-p
                       ;; :a / :b OMITTED
                       }))]
        (is (= ::anom/incorrect (::anom/category r)))))))

;; =============================================================================
;; AC10 — Production code carries no hardcoded phrase-matching as a quality
;; gate. The negation-guard polarity-antonym set is a TRANSPARENT,
;; ENUMERATED list AND the LLM prompt carries the rule semantically —
;; this is the disciplines-7 anti-pattern check.
;; =============================================================================

(deftest llm-prompt-carries-keep-rules
  (testing "The production prompt text carries the load-bearing rules
            (number / negation / entity → KEEP) so the LLM tier handles
            edge cases the deterministic guards can't cover. This is
            verified by string presence — the prompt is not a silent
            quality gate, it's a prompt template the model reads."
    (is (str/includes? dedup/llm-keep-rule-prompt "NUMBER")
        "prompt explicitly carries the number-difference KEEP rule")
    (is (str/includes? dedup/llm-keep-rule-prompt "NEGATION")
        "prompt carries the negation-difference KEEP rule")
    (is (str/includes? dedup/llm-keep-rule-prompt "DIFFERENT ENTITIES")
        "prompt carries the entity-disambiguation KEEP rule")))

;; =============================================================================
;; MT-7e — bounded LSH blocking (comprehensive-scale OOM guard)
;;
;; The comprehensive build reaches the S12 post-landing dedup cascade over a
;; VERY large draft set (52,910 fine-grain concepts). Labels are element names
;; repeated across occupations (`deductive reasoning` ×273, …), so an LSH bucket
;; fills with same-label-different-occupation concepts and the O(k^2) within-
;; bucket ordered-pair enumeration accumulates into one candidate-pair vector →
;; OOM. `lsh-candidate-pairs` now bounds this the GC-2 way: a per-bucket ordered-
;; pair cap + a total candidate-pairs ceiling, dropping the provably-wasted
;; excess DETERMINISTICALLY and surfacing the truncation HONESTLY (never a
;; silent top-N). The bound only removes excess within an already-recall-biased
;; same-signature bucket — a dropped pair is near-certainly a non-merge (same
;; label, different occupation) — so it loses NO genuine merge on a normal-scale
;; graph. Bounds are overridable via the opts map.
;; =============================================================================

(defn- identical-label-bucket
  "n concepts sharing the SAME label — the O*NET failure shape: one giant
   same-signature LSH bucket (`deductive reasoning` repeated across occupations,
   distinct :uri per occupation). Full ordered-pair count is n*(n-1)/2."
  [n]
  (mapv (fn [i]
          {:uri (format "ex:occ%04d/deductive-reasoning" i)
           :label "deductive reasoning"
           :description "element measurement"
           :type :class})
        (range n)))

(defn- pairs->uri-set [pairs]
  (set (map (fn [[a b]] #{(:uri a) (:uri b)}) pairs)))

;; Tracer 1 — per-bucket ordered-pair cap.
(deftest lsh-candidate-pairs-caps-giant-bucket
  (testing "A giant same-signature bucket (60 identical-label concepts →
            60*59/2 = 1770 ordered pairs) is capped to at most
            :max-pairs-per-bucket pairs; the truncation is surfaced honestly."
    (let [concepts (identical-label-bucket 60)
          pairs (dedup/lsh-candidate-pairs concepts {:max-pairs-per-bucket 100})
          trunc (dedup/candidate-pairs-truncation pairs)]
      ;; `[a b]` pair contract preserved — still a seq of [a b] concept pairs.
      (is (every? (fn [p] (and (vector? p) (= 2 (count p))
                               (:uri (first p)) (:uri (second p))))
                  pairs)
          "return is still a seq of [a b] concept pairs")
      (is (<= (count pairs) 100)
          (str "per-bucket cap must bound the giant bucket; got " (count pairs)))
      (is (pos? (:buckets-capped trunc))
          "at least one bucket hit the per-bucket cap")
      (is (pos? (:pairs-dropped trunc))
          "the dropped excess is counted honestly")))
  (testing "A small bucket (2 identical-label concepts → 1 ordered pair) is
            behavior-preserving: the single pair is kept, nothing truncated."
    (let [concepts (identical-label-bucket 2)
          pairs (dedup/lsh-candidate-pairs concepts {:max-pairs-per-bucket 100})
          trunc (dedup/candidate-pairs-truncation pairs)]
      (is (= 1 (count pairs)))
      (is (zero? (:buckets-capped trunc)))
      (is (false? (:total-cap-hit? trunc))))))

;; Tracer 2 — total candidate-pairs ceiling.
(deftest lsh-candidate-pairs-caps-total-ceiling
  (testing "Buckets whose admissible pairs exceed the total ceiling are bounded
            to the ceiling; the total-cap-hit flag is surfaced. (One 10-member
            identical bucket = 45 ordered pairs; a total ceiling of 5 bites even
            though the per-bucket default does not.)"
    (let [concepts (identical-label-bucket 10)
          pairs (dedup/lsh-candidate-pairs concepts {:max-candidate-pairs 5})
          trunc (dedup/candidate-pairs-truncation pairs)]
      (is (<= (count pairs) 5)
          (str "total ceiling must bound the out vector; got " (count pairs)))
      (is (true? (:total-cap-hit? trunc))
          "the total-pairs ceiling was reached — surfaced honestly")))
  (testing "Under the ceiling (default bounds) the same fixture is unchanged:
            all 45 pairs, total ceiling NOT hit."
    (let [concepts (identical-label-bucket 10)
          pairs (dedup/lsh-candidate-pairs concepts)
          trunc (dedup/candidate-pairs-truncation pairs)]
      (is (= 45 (count pairs)))
      (is (false? (:total-cap-hit? trunc))))))

;; Tracer 3 — recall preserved on a normal-scale graph.
(deftest lsh-candidate-pairs-cap-preserves-recall
  (testing "ADVERSARIAL no-false-green: on a normal-scale graph of genuine
            near-duplicate families the PRODUCTION cap yields the SAME candidate
            pairs as an effectively-uncapped run — the bound does not bite a
            normal-scale graph, so no genuine merge is lost."
    (let [concepts [;; genuine near-dup pairs (S12 ground-truth merge shapes)
                    {:uri "p:Director1" :label "Director" :type :class}
                    {:uri "p:Director2" :label "director" :type :class}
                    {:uri "p:Org1" :label "Organization" :type :class}
                    {:uri "p:Org2" :label "Organisation" :type :class}
                    {:uri "p:hasAuthor" :label "hasAuthor" :type :property}
                    {:uri "p:hasWriter" :label "hasWriter" :type :property}
                    {:uri "p:CEO1" :label "Chief Executive Officer" :type :class}
                    {:uri "p:CEO2" :label "  Chief Executive   Officer  " :type :class}
                    ;; token-disjoint noise that must stay pruned
                    {:uri "ex:Agriculture" :label "Agriculture General" :type :class}
                    {:uri "ex:Plumbing" :label "Plumbing Trades" :type :class}]
          capped   (dedup/lsh-candidate-pairs concepts)
          uncapped (dedup/lsh-candidate-pairs
                    concepts {:max-pairs-per-bucket 100000000
                              :max-candidate-pairs 1000000000})]
      (is (= (pairs->uri-set uncapped) (pairs->uri-set capped))
          "production cap yields the SAME pair set as uncapped on a normal graph")
      ;; a genuine merge candidate survives
      (is (contains? (pairs->uri-set capped) #{"p:Org1" "p:Org2"})
          "the Organization/Organisation genuine merge candidate is kept")
      (let [trunc (dedup/candidate-pairs-truncation capped)]
        (is (zero? (:buckets-capped trunc))
            "no bucket is capped on a normal-scale graph")
        (is (false? (:total-cap-hit? trunc))
            "the total ceiling is not hit on a normal-scale graph")))))

;; Tracer 4 — bounds are overridable via the opts map.
(deftest lsh-candidate-pairs-bounds-overridable
  (testing "The per-bucket knob is overridable: a 30-member identical bucket
            (435 ordered pairs) capped at 10 keeps ≤10; a generous cap keeps
            all 435 — proving the knob, not a hardcoded ceiling."
    (let [concepts (identical-label-bucket 30)
          tight (dedup/lsh-candidate-pairs concepts {:max-pairs-per-bucket 10})
          loose (dedup/lsh-candidate-pairs concepts {:max-pairs-per-bucket 1000})]
      (is (<= (count tight) 10)
          (str "tightened per-bucket cap bites; got " (count tight)))
      (is (= 435 (count loose))
          "a generous per-bucket cap keeps the whole 30-member bucket")
      (is (= 10 (:max-pairs-per-bucket (dedup/candidate-pairs-truncation tight)))
          "the truncation report echoes the effective per-bucket bound"))))

;; =============================================================================
;; STREAM Slice 4 — URIs-only LSH bucket (the O(n × bands) memory fix) +
;; streamed dedup-stage load.
;;
;; The pre-refactor `lsh-candidate-pairs` copied each concept's FULL map into
;; ~2×bands buckets → O(n × bands × concept-size) heap. Slice 4 makes the
;; buckets hold URI STRINGS and resolves them back through a compact
;; `{uri -> light-concept + sigs}` side map at pair-emit — bucket memory drops
;; to O(n × bands × uri-size). VERDICT-INVARIANCE is the invariant: the emitted
;; candidate-pair SET, ORDER, MT-7e caps, and each emitted concept's CASCADE-READ
;; fields must be byte-identical.
;;
;; The cascade reads EXACTLY these fields off a candidate concept (verified
;; against `run-cascade` — :uri/:label/:description/:type; `prefilter-verdict`
;; AND the `run-dedup-cascade` command's disjoint-pair-fn — :broader for T1
;; disjointness). Kind-hint is NOT read here in the blocked-pair flow (both
;; production callers project it out BEFORE blocking), so the light set is:
;;
;;     #{:uri :label :description :type :broader}
;;
;; Dropping :broader would flip a T1-disjointness verdict; dropping any of the
;; other four would flip a T2–T9 verdict. Heavy non-cascade fields (:attributes …)
;; are dropped — the observable memory win.
;; =============================================================================

(defn- slice4-heavy-concept
  "A candidate concept carrying BOTH the cascade-read LIGHT fields
   (:uri :label :description :type :broader) AND a HEAVY field (:attributes) the
   cascade NEVER reads. The URIs-only-bucket refactor resolves each emitted pair
   back through a LIGHT side map, so the emitted concept keeps every cascade-read
   field but DROPS the heavy one."
  [uri label]
  {:uri uri :label label :description (str label " description")
   :type :class :broader [(str "bio:" label "Parent")]
   :attributes (vec (repeat 40 {:k label :v (apply str (repeat 32 \x))}))})

;; Tracer — Part B: emitted pairs carry ONLY the light cascade-read fields
;; (:broader preserved; heavy :attributes dropped). RED before the refactor: the
;; pre-fix code copied the full concept into buckets and emitted :attributes.
(deftest slice4-lsh-pairs-carry-only-light-cascade-read-fields
  (testing "STREAM Slice 4 Part B — each emitted pair resolves to a LIGHT concept:
            it PRESERVES every cascade-read field (:uri :label :description :type
            :broader) so no verdict shifts, and DROPS heavy non-cascade fields
            (:attributes) — the URIs-only-bucket memory win made observable."
    (let [concepts [(slice4-heavy-concept "p:Organization1" "Organization")
                    (slice4-heavy-concept "p:Organisation1" "Organisation")]
          pairs (dedup/lsh-candidate-pairs concepts)]
      (is (seq pairs) "the near-dup family collides into at least one candidate pair")
      (doseq [[a b] pairs
              c [a b]]
        ;; ONLY light cascade-read keys survive — no heavy field, no ::wsig/::ssig.
        (is (every? #{:uri :label :description :type :broader} (keys c))
            (str "emitted concept carries ONLY light cascade-read fields; got "
                 (keys c)))
        ;; every cascade-read field is PRESENT + verbatim.
        (is (contains? c :uri))
        (is (contains? c :label))
        (is (contains? c :description))
        (is (contains? c :type))
        (is (contains? c :broader)
            "the T1-disjointness :broader field is PRESERVED — dropping it would
             flip a disjointness verdict in the stage's prefilter + command")
        (is (vector? (:broader c)))
        (is (not (contains? c :attributes))
            "heavy :attributes is DROPPED — buckets/side-map hold LIGHT concepts")))))

;; Tracer — Part B: pair SET + ORDER + MT-7e caps are byte-unchanged on a fixture
;; combining a giant same-signature bucket (exercises the per-bucket cap) with
;; genuine near-dup families (recall). The URIs-only bucket must not reorder /
;; drop / add / duplicate any pair.
(deftest slice4-lsh-uris-only-bucket-preserves-pair-set-order-and-caps
  (testing "STREAM Slice 4 Part B — pair URIs + ORDER + :blocking-truncation are
            byte-identical to the reference enumeration: the URIs-only bucket
            changes only WHAT the buckets hold, never the minhash/band-keys/caps/
            pair-order."
    (let [concepts (into (identical-label-bucket 60)
                         [{:uri "p:Director1" :label "Director" :type :class}
                          {:uri "p:Director2" :label "director" :type :class}
                          {:uri "p:Org1" :label "Organization" :type :class}
                          {:uri "p:Org2" :label "Organisation" :type :class}
                          {:uri "p:CEO1" :label "Chief Executive Officer" :type :class}
                          {:uri "p:CEO2" :label "  Chief Executive   Officer  " :type :class}])
          opts  {:max-pairs-per-bucket 100}
          pairs (dedup/lsh-candidate-pairs concepts opts)
          trunc (dedup/candidate-pairs-truncation pairs)
          uri-pairs (mapv (fn [[a b]] [(:uri a) (:uri b)]) pairs)]
      ;; genuine near-dup merges survive (recall preserved through the URI map).
      (is (contains? (set uri-pairs) ["p:Org1" "p:Org2"])
          "Organization/Organisation genuine merge candidate is kept")
      (is (contains? (set uri-pairs) ["p:CEO1" "p:CEO2"])
          "whitespace-variant CEO merge candidate is kept")
      (is (contains? (set uri-pairs) ["p:Director1" "p:Director2"])
          "case-variant Director merge candidate is kept")
      ;; the ordered-pair invariant (a-uri < b-uri) holds for EVERY emitted pair.
      (is (every? (fn [[au bu]] (neg? (compare au bu))) uri-pairs)
          "every emitted pair is ordered a-uri < b-uri")
      ;; the giant same-signature bucket still hits the per-bucket cap, honestly.
      (is (pos? (:buckets-capped trunc))
          "the 60-member same-signature bucket still hits the per-bucket cap")
      (is (= 100 (:max-pairs-per-bucket trunc))
          "the effective per-bucket bound is echoed unchanged")
      (is (false? (:total-cap-hit? trunc))
          "the total ceiling is not hit at this scale")
      ;; DETERMINISTIC + STABLE ORDER: re-running yields the identical ordered vector.
      (is (= uri-pairs
             (mapv (fn [[a b]] [(:uri a) (:uri b)])
                   (dedup/lsh-candidate-pairs concepts opts)))
          "the emitted pair ORDER is deterministic and stable across runs"))))

;; Tracer — Part A: the dedup-stage concept load streamed via cs/reduce-concepts
;; is byte-identical to the pre-conversion (rm/get-concepts)+filter+light-project
;; load, on a single-ontology store (order + fields + :broader coercion).
(deftest slice4-dedup-stage-streamed-load-is-byte-identical
  (testing "STREAM Slice 4 Part A — cs/reduce-concepts (project-fn keeping the
            cascade-read light fields + :ontology-id for the phantom filter) folds
            the SAME registered reducer over the tag-scoped stream, so the light
            concept vector it produces is byte-identical to the (rm/get-concepts)
            projection load — same order, same fields, same :broader coercion."
    (h/with-test-context [ctx]
      (let [oid primary-p
            ;; the EXACT light projection the dedup-stage applies (incl. the
            ;; :broader vec-coercion the cascade command schema requires).
            light (fn [c] (cond-> (select-keys c [:uri :label :description :type])
                            (seq (:broader c)) (assoc :broader (vec (:broader c)))))]
        ;; seed a single-ontology graph, including a concept WITH :broader.
        (create-concept! ctx {:ontology-id oid :uri "p:Whale" :label "Whale"
                              :description "a whale species" :scope :custom
                              :broader ["bio:Mammal"]})
        (create-concept! ctx {:ontology-id oid :uri "p:Organization" :label "Organization"
                              :description "a structured group" :scope :custom})
        (create-concept! ctx {:ontology-id oid :uri "p:Organisation" :label "Organisation"
                              :description "a structured group" :scope :custom})
        (let [projected (->> (rm/get-concepts ctx {})
                             (filter #(= oid (:ontology-id %)))
                             (mapv light))
              streamed  (cs/reduce-concepts
                         ctx oid
                         (fn [acc c] (if (= oid (:ontology-id c)) (conj acc (light c)) acc))
                         []
                         {:project-fn #(select-keys % [:uri :ontology-id :label
                                                       :description :type :broader])})]
          (is (= projected streamed)
              "streamed light load == projection light load (order + fields + broader)")
          (is (some #(seq (:broader %)) streamed)
              "the :broader concept survived the streamed light projection")
          (is (= 3 (count streamed))
              "all three single-ontology concepts are loaded"))))))
