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
