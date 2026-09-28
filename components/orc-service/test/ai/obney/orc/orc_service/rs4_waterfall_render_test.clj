(ns ai.obney.orc.orc-service.rs4-waterfall-render-test
  "RS-4 — R-Inject renders the waterfall top down.

   When the runtime has assigned a task to a domain child with NO
   consolidated body (RS-2/RS-3's :mint-domain-child / :land-on-domain-child
   / :mint-sibling-domain-child), the structural section renders the
   PARENT's full entry exactly as a plain match — the shape the task
   matched — followed by one line naming the child and its label. The
   four-move menu's SPECIALIZE bullet points at that assignment instead of
   inviting a redundant structural mint. When the child DOES have a
   consolidated body, the child renders as the primary entry and the
   parent drops to one shape-context line.

   Today a newborn domain child renders WRONG: RS-2 sets :was-fresh-mint?
   true on a domain mint, and both structural-display-candidates and the
   first branch of format-structural-section short-circuit on that flag,
   so the model is told \"No high-confidence structural match\" and the
   parent's shape is thrown away.

   Mirrors r_inject_classifier_context_test's builders and with-redefs
   pattern; apply-r05-classifier-context is called directly (`{}` as ctx)
   so these tests never touch the event store."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]
            [ai.obney.orc.ontology.interface :as ontology]))

;; =============================================================================
;; Test data builders — mirrors r_inject_classifier_context_test's shapes
;; =============================================================================

(defn- mk-structural-candidate
  [target-id reasoning content fitness]
  {:content content
   :document-id (str ":tree-fingerprint:" target-id)
   :document-metadata {:granularity :tree-fingerprint :target-id target-id}
   :fitness-score fitness
   :reasoning reasoning
   :rerank-source :reranker})

(defn- mk-node
  "Node payload as the pipeline would see after the wedge runs. `payload`
   slots into :context.:r05-classifier."
  [instruction payload]
  {:id (random-uuid)
   :type :repl-researcher
   :name "test-node"
   :instruction instruction
   :context {:tree-id (or (get-in payload [:structural :assigned-tree-id])
                          (random-uuid))
             :r05-classifier payload}})

(defn- occurrence-count
  "Non-overlapping occurrences of `needle` in `haystack` — index-of based
   (no regex) so a needle containing regex-special characters still counts
   correctly."
  [haystack needle]
  (loop [from 0 n 0]
    (if-let [idx (str/index-of haystack needle from)]
      (recur (+ idx (count needle)) (inc n))
      n)))

;; =============================================================================
;; Cycle 1 — a newborn domain child (no consolidated body) renders the
;; parent's full entry, then the child-assignment line, NOT the fresh-mint
;; "no high-confidence match" branch.
;; =============================================================================

(deftest domain-mint-newborn-child-renders-parent-then-child-line
  (testing "RS-4 cycle 1: a domain-mint whose child has NO consolidated body
            renders the parent's full entry (as a plain match would) then a
            child-assignment line — NOT the fresh-mint 'no high-confidence
            match' sentence RS-2's :was-fresh-mint? true used to trigger."
    (let [parent-id (random-uuid)
          child-id (random-uuid)
          domain-label "marathon-training-plan"
          parent-reasoning "Top-1 because the task shares the training-plan shape."
          ;; The candidate's :content is what retrieval returned for this seed
          ;; — in practice the same text as the corpus body's own :summary
          ;; (the render quotes CANDIDATE :content as "Pattern guidance", never
          ;; the fetched body's :summary directly — matches r_inject_
          ;; classifier_context_test's structural-content/mk-structural-
          ;; candidate convention).
          parent-summary "TrainingPlan sequences weekly mileage blocks toward a taper."
          child-summary "Marathon training plan child — no evidence consolidated yet."
          payload {:structural {:assigned-tree-id child-id
                                 :confidence 0.85
                                 :was-fresh-mint? true
                                 :reasoning parent-reasoning
                                 :top-candidates [(mk-structural-candidate
                                                    parent-id parent-reasoning
                                                    parent-summary 0.85)]
                                 :rerank-fallback? false
                                 :domain {:assigned-via :mint-domain-child
                                          :parent-tree-id parent-id
                                          :child-tree-id child-id
                                          :domain-label domain-label}}
                   :behavioral {:behaviors [] :rerank-fallback? false}}
          node (mk-node "Task: build a 16-week marathon plan" payload)
          stub-bodies {parent-id {:summary parent-summary
                                   :capabilities []
                                   :strengths [{:trait "Sequences taper correctly"
                                                :good-when "distance goal is a race"
                                                :confidence 0.9 :evidence-count 3}]
                                   :weaknesses []
                                   :representative-uses ["16-week marathon plan for a first-timer"]
                                   :avoid-when []
                                   :version 4
                                   :consolidated-from-event-count 6}
                       child-id {:summary child-summary
                                 :capabilities []
                                 :strengths []
                                 :weaknesses []
                                 :representative-uses []
                                 :avoid-when []
                                 :version 1
                                 :consolidated-from-event-count 0}}
          result (with-redefs [ontology/get-description
                                (fn [_ctx _granularity target-id]
                                  (get stub-bodies target-id))]
                   (tp/apply-r05-classifier-context node {}))
          instruction (:instruction result)]

      (testing "the fresh-mint sentence is ABSENT"
        (is (not (str/includes? instruction "No high-confidence structural match"))))

      (testing "the parent's full entry renders — summary + strength trait"
        (is (str/includes? instruction parent-summary)
            "parent's injected summary quoted verbatim")
        (is (str/includes? instruction "Sequences taper correctly")
            "parent's strength trait rendered"))

      (testing "the child line names the child id and its label"
        (is (str/includes? instruction (str child-id)))
        (is (str/includes? instruction domain-label)))

      (testing "the child line comes AFTER the parent entry"
        (let [parent-idx (str/index-of instruction parent-summary)
              child-idx (str/index-of instruction (str child-id))]
          (is (some? parent-idx))
          (is (some? child-idx))
          (is (< parent-idx child-idx)
              "parent entry renders before the child-assignment line"))))))

;; =============================================================================
;; Cycle 2 — the four-move menu's SPECIALIZE bullet is a function of the
;; payload: :domain present -> points at the runtime's assignment instead of
;; inviting a structural mint the runtime already made; :domain absent ->
;; byte-identical to the pre-RS-4 structural-mint invitation.
;; =============================================================================

(def ^:private original-specialize-bullet
  "The pre-RS-4 SPECIALIZE bullet, verbatim, as it exists in production
   today (todo_processors.clj's four-move-menu block) — OUR OWN template
   text, not model-authored prose, so asserting on it verbatim is a
   structural check on unchanged output, not phrase-matching over a model
   response."
  (str "  - SPECIALIZE — mint a CHILD of the nearest reference (`mint-behavior!` "
       "with `:parent <that behavior-id>`) when the top hit is a BROAD shape "
       "rather than an exact fit; the child keeps the proven shape, pins your "
       "domain, and accrues evidence under the parent. This is the RECOMMENDED "
       "move when a match cleared threshold only on shape.\n"))

(defn- plain-match-payload
  "A plain, non-domain, non-fresh-mint structural+behavioral payload — the
   byte-identity baseline cycle 2's 'without :domain' assertions render
   against."
  [target-id reasoning content fitness]
  {:structural {:assigned-tree-id target-id
                :confidence fitness
                :was-fresh-mint? false
                :reasoning reasoning
                :top-candidates [(mk-structural-candidate target-id reasoning content fitness)]
                :rerank-fallback? false}
   :behavioral {:behaviors [] :rerank-fallback? false}})

(deftest specialize-bullet-with-domain-points-at-the-assignment
  (testing "RS-4 cycle 2a: with :domain present the SPECIALIZE bullet names
            the domain label and assignment, NOT the structural-mint
            invitation."
    (let [parent-id (random-uuid)
          child-id (random-uuid)
          domain-label "ketogenic-meal-plan"
          reasoning "Top-1 because the task shares the meal-plan shape."
          content "MealPlan pattern: daily macro targets with a shopping list."
          payload (-> (plain-match-payload parent-id reasoning content 0.9)
                      (assoc-in [:structural :was-fresh-mint?] true)
                      (assoc-in [:structural :domain]
                                {:assigned-via :mint-domain-child
                                 :parent-tree-id parent-id
                                 :child-tree-id child-id
                                 :domain-label domain-label}))
          node (mk-node "Task: build a 7-day keto meal plan" payload)
          stub-bodies {parent-id {:summary content :capabilities [] :strengths []
                                   :weaknesses [] :representative-uses []
                                   :avoid-when [] :version 1
                                   :consolidated-from-event-count 3}
                       child-id {:summary "Keto meal plan child."
                                 :capabilities [] :strengths [] :weaknesses []
                                 :representative-uses [] :avoid-when []
                                 :version 1 :consolidated-from-event-count 0}}
          instruction (:instruction
                       (with-redefs [ontology/get-description
                                     (fn [_ _ id] (get stub-bodies id))]
                         (tp/apply-r05-classifier-context node {})))]
      (is (str/includes? instruction "SPECIALIZE")
          "sanity: a SPECIALIZE bullet is present")
      (is (str/includes? instruction domain-label)
          "the SPECIALIZE line names the domain label")
      (is (not (str/includes? instruction "mint-behavior!"))
          "the structural menu's mint-behavior! invitation is gone — nothing
           else in this payload's (empty) behavioral section renders it"))))

(deftest specialize-bullet-without-domain-is-byte-identical
  (testing "RS-4 cycle 2b: without :domain the whole four-move menu — the
            SPECIALIZE bullet included — is byte-identical to today, and the
            pure render is stable whether the payload never carried :domain
            or carried one that was dissoc'd back off."
    (let [target-id (random-uuid)
          reasoning "Top-1 because the task matches the report shape."
          content "ReportBuilder pattern: sectioned findings with citations."
          payload (plain-match-payload target-id reasoning content 0.9)
          payload-with-domain-then-stripped
          (-> payload
              (assoc-in [:structural :domain]
                        {:assigned-via :mint-domain-child
                         :parent-tree-id (random-uuid)
                         :child-tree-id (random-uuid)
                         :domain-label "some-domain"})
              (update :structural dissoc :domain))
          stub-bodies {target-id {:summary content :capabilities [] :strengths []
                                   :weaknesses [] :representative-uses []
                                   :avoid-when [] :version 1
                                   :consolidated-from-event-count 1}}
          render (fn [p]
                   (with-redefs [ontology/get-description
                                 (fn [_ _ id] (get stub-bodies id))]
                     (:instruction (tp/apply-r05-classifier-context
                                    (mk-node "Task: build a quarterly report" p) {}))))
          instruction (render payload)
          instruction-stripped (render payload-with-domain-then-stripped)]
      (is (str/includes? instruction original-specialize-bullet)
          "the SPECIALIZE bullet is byte-identical to the pre-RS-4 text")
      (is (= instruction instruction-stripped)
          "the render is stable: never-had-:domain == had-it-then-dissoc'd"))))

;; =============================================================================
;; Cycle 3 — a domain child WITH a consolidated body renders as the primary
;; entry; the parent drops to one shape-context line (no second full entry).
;; =============================================================================

(deftest domain-child-consolidated-renders-as-primary-entry
  (testing "RS-4 cycle 3: once the child's body is consolidated
            (:consolidated-from-event-count >= 1) it renders as the PRIMARY
            entry — its own summary + strength — and the parent drops to one
            shape-context line; no second full parent entry, and the
            cycle-1 child-assignment line is absent (superseded)."
    (let [parent-id (random-uuid)
          child-id (random-uuid)
          domain-label "ketogenic-meal-plan"
          top-reasoning "Top-1 because the task shares the meal-plan shape."
          ;; Two sentences: the shape-context line carries the summary WHOLE
          ;; (never parsed for a sentence break).
          parent-summary "MealPlan sequences daily macro targets with a shopping list. It ships weekly."
          child-summary "Ketogenic meal plan pins net-carb targets under 20g/day."
          payload {:structural {:assigned-tree-id child-id
                                 :confidence 0.88
                                 :was-fresh-mint? false
                                 :reasoning top-reasoning
                                 :top-candidates [(mk-structural-candidate
                                                    parent-id top-reasoning
                                                    "unused top-candidate content" 0.88)]
                                 :rerank-fallback? false
                                 :domain {:assigned-via :land-on-domain-child
                                          :parent-tree-id parent-id
                                          :child-tree-id child-id
                                          :domain-label domain-label}}
                   :behavioral {:behaviors [] :rerank-fallback? false}}
          node (mk-node "Task: build a 7-day keto meal plan" payload)
          stub-bodies {parent-id {:summary parent-summary
                                   :capabilities [] :strengths []
                                   :weaknesses [] :representative-uses []
                                   :avoid-when [] :version 5
                                   :consolidated-from-event-count 8}
                       child-id {:summary child-summary
                                 :capabilities []
                                 :strengths [{:trait "Pins net-carb targets under 20g/day"
                                              :good-when "the diner has an active keto goal"
                                              :confidence 0.8 :evidence-count 4}]
                                 :weaknesses [] :representative-uses []
                                 :avoid-when [] :version 2
                                 :consolidated-from-event-count 3}}
          instruction (:instruction
                       (with-redefs [ontology/get-description
                                     (fn [_ _ id] (get stub-bodies id))]
                         (tp/apply-r05-classifier-context node {})))]

      (testing "the child renders as the primary entry — summary + strength"
        (is (str/includes? instruction child-summary)
            "child's own summary quoted verbatim")
        (is (str/includes? instruction "Pins net-carb targets under 20g/day")
            "child's strength trait rendered"))

      (testing "the parent drops to exactly ONE shape-context line"
        (is (= 1 (occurrence-count instruction parent-summary))
            "parent's summary appears exactly once — no second full entry"))

      (testing "the cycle-1 child-assignment line is absent (superseded by the primary-entry render)"
        (is (not (str/includes? instruction "Assigned to domain child"))))

      (testing "the injection record names the CHILD it rendered, at the child's body version (CC-13)"
        (let [recorded (with-redefs [ontology/get-description
                                     (fn [_ _ id] (get stub-bodies id))]
                         (#'tp/injected-candidates {} payload))]
          (is (= [{:axis :structural :candidate-id (str child-id) :version 2 :score 0.88}]
                 recorded)))))))

;; =============================================================================
;; Cycle 4 — the wedge seam: maybe-auto-classify-and-set-context stamps the
;; :domain facts on :context.:r05-classifier.:structural.:domain (mirrors
;; r_inject_classifier_context_test's wedge-stashes-r05-classifier-payload).
;; =============================================================================

(deftest wedge-stashes-domain-facts-on-a-domain-outcome
  (testing "RS-4 cycle 4a: a :mint-domain-child classify-task result stamps
            the four-key :domain map onto the payload, key by key."
    (let [parent-id (random-uuid)
          child-id (random-uuid)
          ;; :was-fresh-mint? false here (even though a REAL assign-domain-
          ;; child mint always pairs :mint-domain-child with true): this test
          ;; stubs classify-task directly, bypassing assign-domain-child, and
          ;; only exercises the :domain payload-stamping seam — mirroring
          ;; wedge-stashes-r05-classifier-payload's hermetic ctx (no
          ;; :cache/:event-store), so it must not also trip the CV-1
          ;; convergence-capture side effect (domain-mint? AND
          ;; :was-fresh-mint?), which needs a real read-model cache. RS-3's
          ;; rs3_domain_child_durable_test exercises the real mint +
          ;; was-fresh-mint?/true combination through the live wedge.
          structural-result {:assigned-tree-id child-id
                              :confidence 0.9
                              :was-fresh-mint? false
                              :reasoning "Domain mint"
                              :top-candidates [{:document-metadata {:target-id parent-id}
                                                 :content "x"
                                                 :fitness-score 0.9
                                                 :reasoning "Domain mint"
                                                 :rerank-source :reranker}]
                              :rerank-fallback? false
                              :parent-tree-id parent-id
                              :assigned-via :mint-domain-child
                              :domain-label "some-canonical-label"}
          behavioral-result {:behaviors [] :rerank-fallback? false}
          node {:id (random-uuid)
                :type :repl-researcher
                :name "test"
                :instruction "x"
                :reads []
                :writes []
                :rlm {:auto-classify? true}}
          wedge-ctx {:sheet-id (random-uuid) :tick-id (random-uuid)}]
      (with-redefs [ontology/classify-task (constantly structural-result)
                    ontology/classify-behaviors (constantly behavioral-result)
                    ai.obney.grain.command-processor-v2.interface/process-command
                    (constantly {:command-result/events []})]
        (let [result-node (tp/maybe-auto-classify-and-set-context node wedge-ctx)
              domain (get-in result-node [:context :r05-classifier :structural :domain])]
          (is (= :mint-domain-child (:assigned-via domain)))
          (is (= parent-id (:parent-tree-id domain)))
          (is (= child-id (:child-tree-id domain)))
          (is (= "some-canonical-label" (:domain-label domain))))))))

(deftest wedge-omits-domain-on-a-plain-match
  (testing "RS-4 cycle 4b: a plain :match classify-task result carries no
            :domain key at all — not nil-valued, ABSENT."
    (let [target-id (random-uuid)
          structural-result {:assigned-tree-id target-id
                              :confidence 0.9
                              :was-fresh-mint? false
                              :reasoning "deterministic match"
                              :top-candidates []
                              :rerank-fallback? false
                              :parent-tree-id nil
                              :assigned-via :match}
          behavioral-result {:behaviors [] :rerank-fallback? false}
          node {:id (random-uuid)
                :type :repl-researcher
                :name "test"
                :instruction "x"
                :reads []
                :writes []
                :rlm {:auto-classify? true}}
          wedge-ctx {:sheet-id (random-uuid) :tick-id (random-uuid)}]
      (with-redefs [ontology/classify-task (constantly structural-result)
                    ontology/classify-behaviors (constantly behavioral-result)
                    ai.obney.grain.command-processor-v2.interface/process-command
                    (constantly {:command-result/events []})]
        (let [result-node (tp/maybe-auto-classify-and-set-context node wedge-ctx)
              structural (get-in result-node [:context :r05-classifier :structural])]
          (is (not (contains? structural :domain))
              ":domain key is absent, not nil-valued"))))))

;; =============================================================================
;; RS-7 Slice 0 characterisation — a plain :match whose ASSIGNED id happens
;; to already BE a domain child (a newborn reached again) still carries no
;; :domain key: maybe-assign-domain-child never ran here (classify-task is
;; stubbed wholesale, as production dispatches it), so the render has no
;; child line to show today even though the assigned target is itself a
;; domain child. Pinned so Slice 1 (a domain child is a leaf on the domain
;; axis) can flip this by name once the runtime produces a real :domain
;; payload for this case.
;; =============================================================================

(deftest wedge-omits-domain-on-a-plain-match-whose-target-is-a-domain-child
  (testing "RS-7 characterisation: a stubbed :match classify-task result
            whose :assigned-tree-id is a domain child's own id still carries
            no :domain key — not nil-valued, ABSENT."
    (let [domain-child-id (random-uuid)
          structural-result {:assigned-tree-id domain-child-id
                              :confidence 0.9
                              :was-fresh-mint? false
                              :reasoning "deterministic match, assigned id happens to be a domain child"
                              :top-candidates []
                              :rerank-fallback? false
                              :parent-tree-id nil
                              :assigned-via :match}
          behavioral-result {:behaviors [] :rerank-fallback? false}
          node {:id (random-uuid)
                :type :repl-researcher
                :name "test"
                :instruction "x"
                :reads []
                :writes []
                :rlm {:auto-classify? true}}
          wedge-ctx {:sheet-id (random-uuid) :tick-id (random-uuid)}]
      (with-redefs [ontology/classify-task (constantly structural-result)
                    ontology/classify-behaviors (constantly behavioral-result)
                    ai.obney.grain.command-processor-v2.interface/process-command
                    (constantly {:command-result/events []})]
        (let [result-node (tp/maybe-auto-classify-and-set-context node wedge-ctx)
              structural (get-in result-node [:context :r05-classifier :structural])]
          (is (not (contains? structural :domain))
              ":domain key is absent — no child line renders for this case today"))))))

;; =============================================================================
;; CV-B (C7) — the family's own body renders BENEATH the child line whenever
;; it has substance, while the parent stays the primary entry until
;; consolidated-from-event-count >= 1 (D5). Usefulness report 06: "the second
;; recipe occurrence read the child line but was never shown what the child
;; had learned" — the family's strengths were recorded (CV-2 enrichment) but
;; invisible before consolidation.
;; =============================================================================

(defn- mk-behavioral-entry
  "A minimal non-fresh-mint behavioral entry — enough to make format-
   behavioral-section render its '### Behavioral competencies' header, so
   the family-substance tests below have a real behavioral-section boundary
   to assert ordering against."
  [behavior-id reasoning confidence]
  {:behavior-id behavior-id
   :confidence confidence
   :was-fresh-mint? false
   :reasoning reasoning
   :rerank-source :reranker})

(deftest newborn-family-with-strengths-renders-them-under-the-child-line
  (testing "CV-B cycle 1: a newborn domain child (not yet consolidated) whose
            OWN body has accrued a strength and a weakness renders that
            substance beneath the child-assignment line — after the child
            line's index, before the behavioral section — while the parent's
            full entry still renders FIRST, exactly as RS-4 already proved."
    (let [parent-id (random-uuid)
          child-id (random-uuid)
          behavior-id (random-uuid)
          domain-label "recipe-conversion"
          parent-reasoning "Top-1 because the task shares the recipe shape."
          parent-summary "RecipeConversion sequences ingredient scaling toward a target yield."
          child-summary "Recipe-conversion child — one worked occurrence recorded, not yet consolidated."
          strength-trait "Scales fractional ingredient units correctly"
          weakness-trait "Drops unit conversion when the source uses imperial measures"
          payload {:structural {:assigned-tree-id child-id
                                 :confidence 0.85
                                 :was-fresh-mint? true
                                 :reasoning parent-reasoning
                                 :top-candidates [(mk-structural-candidate
                                                    parent-id parent-reasoning
                                                    parent-summary 0.85)]
                                 :rerank-fallback? false
                                 :domain {:assigned-via :mint-domain-child
                                          :parent-tree-id parent-id
                                          :child-tree-id child-id
                                          :domain-label domain-label}}
                   :behavioral {:behaviors [(mk-behavioral-entry
                                              behavior-id "fits the recipe task" 0.8)]
                                :rerank-fallback? false}}
          node (mk-node "Task: convert a recipe to metric" payload)
          stub-bodies {parent-id {:summary parent-summary
                                   :capabilities []
                                   :strengths [{:trait "Sequences scaling steps in order"
                                                :good-when "a target yield is given"
                                                :confidence 0.9 :evidence-count 4}]
                                   :weaknesses [] :representative-uses []
                                   :avoid-when [] :version 4
                                   :consolidated-from-event-count 6}
                       child-id {:summary child-summary
                                 :capabilities []
                                 :strengths [{:trait strength-trait
                                              :good-when "the recipe uses fractional units"
                                              :confidence 0.75 :evidence-count 1}]
                                 :weaknesses [{:trait weakness-trait
                                               :avoid-when "the source recipe is in imperial units"
                                               :recommended-alternative "convert units before scaling"
                                               :confidence 0.6 :evidence-count 1}]
                                 :representative-uses ["Halving a 4-serving pasta recipe"]
                                 :avoid-when [] :version 2
                                 :consolidated-from-event-count 0}
                       behavior-id nil}
          result (with-redefs [ontology/get-description
                                (fn [_ctx _granularity target-id]
                                  (get stub-bodies target-id))]
                   (tp/apply-r05-classifier-context node {}))
          instruction (:instruction result)
          parent-strength-idx (str/index-of instruction "Sequences scaling steps in order")
          child-line-idx (str/index-of instruction (str "Assigned to domain child " child-id))
          strength-idx (str/index-of instruction strength-trait)
          weakness-idx (str/index-of instruction weakness-trait)
          behavioral-section-idx (str/index-of instruction "### Behavioral competencies")]

      (testing "sanity: every marker was found"
        (is (some? parent-strength-idx))
        (is (some? child-line-idx))
        (is (some? strength-idx))
        (is (some? weakness-idx))
        (is (some? behavioral-section-idx)))

      (testing "the parent entry still renders FIRST"
        (is (< parent-strength-idx child-line-idx)
            "parent's strength (its full entry) precedes the child-assignment line"))

      (testing "the family's strength and weakness render AFTER the child line"
        (is (< child-line-idx strength-idx))
        (is (< child-line-idx weakness-idx)))

      (testing "the family's strength and weakness render BEFORE the behavioral section"
        (is (< strength-idx behavioral-section-idx))
        (is (< weakness-idx behavioral-section-idx)))

      (testing "the family's representative use also renders"
        (is (str/includes? instruction "Halving a 4-serving pasta recipe")))

      (testing "the injection candidates contain the child id at its own body version (CC-13)"
        (let [recorded (with-redefs [ontology/get-description
                                     (fn [_ _ id] (get stub-bodies id))]
                         (#'tp/injected-candidates {} payload))
              child-row (some #(when (= (str child-id) (:candidate-id %)) %) recorded)]
          (is (some? child-row) (pr-str recorded))
          (is (= 2 (:version child-row)))
          (is (= :structural (:axis child-row))))))))

(deftest newborn-family-without-substance-renders-only-the-child-line
  (testing "CV-B cycle 2: a newborn domain child whose body has NO substance
            beyond its birth line renders exactly like today's RS-4 newborn
            shape — no family-substance section headers appear anywhere."
    (let [parent-id (random-uuid)
          child-id (random-uuid)
          domain-label "no-substance-family"
          parent-reasoning "Top-1 because the task shares the shape."
          parent-summary "NoSubstanceFamily sequences steps toward an outcome."
          payload {:structural {:assigned-tree-id child-id
                                 :confidence 0.85
                                 :was-fresh-mint? true
                                 :reasoning parent-reasoning
                                 :top-candidates [(mk-structural-candidate
                                                    parent-id parent-reasoning
                                                    parent-summary 0.85)]
                                 :rerank-fallback? false
                                 :domain {:assigned-via :mint-domain-child
                                          :parent-tree-id parent-id
                                          :child-tree-id child-id
                                          :domain-label domain-label}}
                   :behavioral {:behaviors [] :rerank-fallback? false}}
          node (mk-node "Task: do the no-substance thing" payload)
          stub-bodies {parent-id {:summary parent-summary
                                   :capabilities [] :strengths []
                                   :weaknesses [] :representative-uses []
                                   :avoid-when [] :version 3
                                   :consolidated-from-event-count 5}
                       child-id {:summary "No-substance family child — birth line only."
                                 :capabilities [] :strengths [] :weaknesses []
                                 :representative-uses [] :avoid-when []
                                 :version 1 :consolidated-from-event-count 0}}
          instruction (:instruction
                       (with-redefs [ontology/get-description
                                     (fn [_ _ id] (get stub-bodies id))]
                         (tp/apply-r05-classifier-context node {})))]

      (testing "no family-substance section headers render — byte-identical
                to today's newborn shape"
        (is (not (str/includes? instruction "Strengths (proven traits")))
        (is (not (str/includes? instruction "Weaknesses (observed failure modes")))
        (is (not (str/includes? instruction "Representative uses (concrete tasks"))))

      (testing "the child line still renders, unchanged, exactly once"
        (is (= 1 (occurrence-count instruction (str "Assigned to domain child " child-id))))
        (is (str/includes? instruction domain-label)))

      (testing "the injection candidates carry ONLY the parent — no family row"
        (let [recorded (with-redefs [ontology/get-description
                                     (fn [_ _ id] (get stub-bodies id))]
                         (#'tp/injected-candidates {} payload))]
          (is (= [(str parent-id)] (map :candidate-id recorded))))))))

(deftest reached-family-renders-once-and-is-recorded-once
  (testing "orchestrator inspection: a later task that reaches the newborn
            family BY MATCH (C5 landing) carries the family itself among
            top-candidates. The child line stands for it: it is not a
            numbered entry, its substance renders once beneath the child
            line, and the injection record lists it exactly once (record =
            render)."
    (let [parent-id (random-uuid)
          child-id (random-uuid)
          other-id (random-uuid)
          strength-trait "Scales fractional ingredient units correctly"
          payload {:structural {:assigned-tree-id child-id
                                 :confidence 0.9
                                 :was-fresh-mint? false
                                 :reasoning "the family matches"
                                 :top-candidates [(mk-structural-candidate child-id "the family matches" "Recipe-conversion family." 0.9)
                                                  (mk-structural-candidate other-id "a weaker shape" "Other shape summary." 0.8)]
                                 :rerank-fallback? false
                                 :domain {:assigned-via :land-on-domain-child
                                          :parent-tree-id parent-id
                                          :child-tree-id child-id
                                          :domain-label "recipe-conversion"}}
                   :behavioral {:behaviors [] :rerank-fallback? false}}
          stub-bodies {child-id {:summary "Recipe-conversion family."
                                 :capabilities []
                                 :strengths [{:trait strength-trait
                                              :good-when "the recipe uses fractional units"
                                              :confidence 0.75 :evidence-count 1}]
                                 :weaknesses [] :representative-uses []
                                 :avoid-when [] :version 2
                                 :consolidated-from-event-count 0}
                       other-id {:summary "Other shape summary." :capabilities []
                                 :strengths [] :weaknesses [] :representative-uses []
                                 :avoid-when [] :version 1 :consolidated-from-event-count 3}}
          node (mk-node "Task: convert a recipe to metric" payload)
          [instruction recorded]
          (with-redefs [ontology/get-description
                        (fn [_ctx _granularity target-id] (get stub-bodies target-id))]
            [(:instruction (tp/apply-r05-classifier-context node {}))
             (#'tp/injected-candidates {} payload)])
          structural-ids (->> recorded (filter #(= :structural (:axis %))) (map :candidate-id))]
      (is (= 1 (count (filter #{(str child-id)} structural-ids)))
          (str "family recorded exactly once: " (pr-str recorded)))
      (is (= 1 (count (filter #{(str other-id)} structural-ids))))
      (is (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote strength-trait)) instruction)))
          "the family's substance renders once")
      (is (str/includes? instruction "top 1 from corpus retrieval")
          "only the other shape is a numbered entry"))))

(deftest consolidated-family-unchanged
  (testing "CV-B guard: a CONSOLIDATED domain child's render is untouched by
            the newborn-branch family-substance addition — the consolidated
            arm of structural-display-candidates still returns exactly ONE
            candidate (consolidated-domain-child-candidate), never a second
            family entry, and the 2-arity injected-candidates/structural-
            display-candidates forms (default suppress-claims? false) stay
            behaviorally identical to before this bundle."
    (let [parent-id (random-uuid)
          child-id (random-uuid)
          domain-label "consolidated-family"
          top-reasoning "Top-1 because the task shares the shape."
          parent-summary "ConsolidatedFamily sequences steps toward an outcome. It ships weekly."
          child-summary "Consolidated family child — has its own accrued body."
          payload {:structural {:assigned-tree-id child-id
                                 :confidence 0.88
                                 :was-fresh-mint? false
                                 :reasoning top-reasoning
                                 :top-candidates [(mk-structural-candidate
                                                    parent-id top-reasoning
                                                    "unused top-candidate content" 0.88)]
                                 :rerank-fallback? false
                                 :domain {:assigned-via :land-on-domain-child
                                          :parent-tree-id parent-id
                                          :child-tree-id child-id
                                          :domain-label domain-label}}
                   :behavioral {:behaviors [] :rerank-fallback? false}}
          node (mk-node "Task: do the consolidated thing" payload)
          stub-bodies {parent-id {:summary parent-summary
                                   :capabilities [] :strengths []
                                   :weaknesses [] :representative-uses []
                                   :avoid-when [] :version 5
                                   :consolidated-from-event-count 8}
                       child-id {:summary child-summary
                                 :capabilities []
                                 :strengths [{:trait "Handles the consolidated case correctly"
                                              :good-when "the family has accrued evidence"
                                              :confidence 0.85 :evidence-count 5}]
                                 :weaknesses [] :representative-uses []
                                 :avoid-when [] :version 2
                                 :consolidated-from-event-count 3}}
          instruction (:instruction
                       (with-redefs [ontology/get-description
                                     (fn [_ _ id] (get stub-bodies id))]
                         (tp/apply-r05-classifier-context node {})))]

      (testing "the child's strength renders exactly once — no duplicate family entry"
        (is (= 1 (occurrence-count instruction "Handles the consolidated case correctly"))))

      (testing "the parent drops to exactly ONE shape-context line"
        (is (= 1 (occurrence-count instruction parent-summary))))

      (testing "no newborn child-assignment line (superseded, as RS-4 already proved)"
        (is (not (str/includes? instruction "Assigned to domain child"))))

      (testing "the injection record still names exactly the child at its body version"
        (let [recorded (with-redefs [ontology/get-description
                                     (fn [_ _ id] (get stub-bodies id))]
                         (#'tp/injected-candidates {} payload))]
          (is (= [{:axis :structural :candidate-id (str child-id) :version 2 :score 0.88}]
                 recorded)))))))
