(ns ai.obney.orc.ontology.walk-down-classifier-test
  "Tests for C-2d-2: walk-down classifier + consolidator parent-inference.

   Covers:
   - :parent-tree-id optionally carried through :ontology/task-classified
     event + :ontology/assign-task-class command bodies
   - classify-task :walk-down? false preserves legacy behavior
   - classify-task :walk-down? true descends via concepts graph + reranker
     into the deepest child that scores >= auto-classify-threshold
   - Walk-down terminates when no child fits; current node returned
   - Walk-down fresh-mint binds :parent-tree-id to the deepest matched
     ancestor
   - Hard depth cap at 5 levels
   - C-2c-2 wedge forwards :parent-tree-id from classify-task result to
     the dispatched command body
   - Consolidator infers parent on first consolidation; preserves on
     subsequent consolidations (sticky)
   - Orphan logging when consolidator's classify-task is below threshold

   Live verify lives in C-2d-3."
  (:require [clojure.test :refer [deftest testing is]]
            [malli.core :as m]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.reranker :as reranker]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.ontology.core.consolidator]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.schemas]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Test context helpers (mirror classifier_test.clj)
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/c2d2-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)]
    {:event-store event-store
     :cache cache
     :tenant-id tenant-id
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
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)]
     (try ~@body (finally (stop-context ~sym)))))

;; =============================================================================
;; Helpers for walk-down tests
;; =============================================================================

(defn- fake-top-1
  "Build a search-descriptions result vector with a single top candidate
   matching the given target-id and fitness."
  [target-id fitness]
  [{:content "Parent summary"
    :score fitness
    :rank 1
    :document-id (str "tf::" target-id)
    :document-metadata {:granularity :tree-fingerprint
                        :target-id target-id
                        :confidence 1.0
                        :last-update "2026"}
    :reasoning "Top-level abstract pattern fit."
    :fitness-score fitness}])

;; =============================================================================
;; RED #1 — :parent-tree-id optional on task-classified event + assign-task-class
;; =============================================================================

(deftest task-classified-event-accepts-optional-parent-tree-id
  (testing "Legacy event without :parent-tree-id still validates"
    (let [event {:source-sheet-id   (random-uuid)
                 :source-tick-id    (random-uuid)
                 :source-node-id    (random-uuid)
                 :assigned-tree-id  (random-uuid)
                 :confidence        0.87
                 :top-candidates    [{:document-id "x" :score 0.87}]
                 :reasoning         "Matches chunked-extraction."
                 :classified-at     "2026-05-27T12:00:00Z"
                 :was-fresh-mint?   false}
          schema (get @schema-util/registry* :ontology/task-classified)]
      (is (m/validate schema event)
          "Legacy event without :parent-tree-id remains valid")))

  (testing "Event WITH UUID :parent-tree-id validates"
    (let [event {:source-sheet-id   (random-uuid)
                 :source-tick-id    (random-uuid)
                 :source-node-id    (random-uuid)
                 :assigned-tree-id  (random-uuid)
                 :confidence        0.87
                 :top-candidates    []
                 :reasoning         "Walked down from parent."
                 :classified-at     "2026"
                 :was-fresh-mint?   false
                 :parent-tree-id    (random-uuid)}
          schema (get @schema-util/registry* :ontology/task-classified)]
      (is (m/validate schema event)
          (str "UUID :parent-tree-id should validate. Explain: "
               (pr-str (m/explain schema event))))))

  (testing "Event WITH string-fingerprint :parent-tree-id validates"
    (let [event {:source-sheet-id   (random-uuid)
                 :source-tick-id    (random-uuid)
                 :source-node-id    (random-uuid)
                 :assigned-tree-id  (random-uuid)
                 :confidence        0.4
                 :top-candidates    []
                 :reasoning         "Minted fresh under abstract parent."
                 :classified-at     "2026"
                 :was-fresh-mint?   true
                 :parent-tree-id    "seed:tree:ChunkedExtraction"}
          schema (get @schema-util/registry* :ontology/task-classified)]
      (is (m/validate schema event)
          "String fingerprint :parent-tree-id is also accepted")))

  (testing ":parent-tree-id is declared in the schema (not just an implicit open-map extra)"
    ;; This locks the schema down: future readers can see the field IS
    ;; part of the contract. Without the explicit [:parent-tree-id ...]
    ;; entry, the schema string wouldn't mention it at all.
    (let [schema (get @schema-util/registry* :ontology/task-classified)
          schema-form (m/form schema)]
      (is (some #{:parent-tree-id} (flatten (vec schema-form)))
          (str ":parent-tree-id should appear in the schema form. Got: "
               (pr-str schema-form))))))

(deftest assign-task-class-command-accepts-optional-parent-tree-id
  (testing "Legacy command without :parent-tree-id still validates"
    (let [cmd {:source-sheet-id   (random-uuid)
               :source-tick-id    (random-uuid)
               :source-node-id    (random-uuid)
               :assigned-tree-id  (random-uuid)
               :confidence        0.87
               :top-candidates    []
               :reasoning         "x"
               :was-fresh-mint?   false}
          schema (get @schema-util/registry* :ontology/assign-task-class)]
      (is (m/validate schema cmd)
          "Legacy command without :parent-tree-id remains valid")))

  (testing "Command WITH :parent-tree-id validates"
    (let [cmd {:source-sheet-id   (random-uuid)
               :source-tick-id    (random-uuid)
               :source-node-id    (random-uuid)
               :assigned-tree-id  (random-uuid)
               :confidence        0.4
               :top-candidates    []
               :reasoning         "Walked down from parent."
               :was-fresh-mint?   true
               :parent-tree-id    (random-uuid)}
          schema (get @schema-util/registry* :ontology/assign-task-class)]
      (is (m/validate schema cmd)
          (str "Command with :parent-tree-id should validate. Explain: "
               (pr-str (m/explain schema cmd))))))

  (testing ":parent-tree-id is declared in the assign-task-class schema"
    (let [schema (get @schema-util/registry* :ontology/assign-task-class)
          schema-form (m/form schema)]
      (is (some #{:parent-tree-id} (flatten (vec schema-form)))
          (str ":parent-tree-id should appear in the schema form. Got: "
               (pr-str schema-form))))))

;; =============================================================================
;; RED #2 — :walk-down? false preserves legacy behavior (no parent walk)
;;
;; The pure classify-task fn already exists. Adding :walk-down? false must
;; emit the same shape as before with :parent-tree-id ABSENT from the result
;; (nil-valued or missing — we accept either to keep the contract minimal).
;; =============================================================================

(deftest classify-task-walk-down-false-preserves-legacy-shape
  (testing "Top-1 above threshold with :walk-down? false → match, no parent-tree-id"
    (let [matched-uuid (random-uuid)
          fake-results [{:content "Pattern A summary"
                         :score 0.91
                         :rank 1
                         :document-id "tf::a"
                         :document-metadata {:granularity :tree-fingerprint
                                             :target-id matched-uuid
                                             :confidence 1.0
                                             :last-update "2026"}
                         :reasoning "Strong fit"
                         :fitness-score 0.91}]]
      (with-redefs [ontology/search-descriptions (fn [_ctx _opts] fake-results)]
        (let [result (ontology/classify-task {}
                       {:task-signature "extract per-section summaries"
                        :threshold 0.7
                        :walk-down? false})]
          (is (= matched-uuid (:assigned-tree-id result))
              "Top-1 still selected when walk-down disabled")
          (is (false? (:was-fresh-mint? result)))
          (is (nil? (:parent-tree-id result))
              ":parent-tree-id is nil when walk-down is disabled")))))

  (testing "Top-1 below threshold with :walk-down? false → fresh-mint, no parent-tree-id"
    (with-redefs [ontology/search-descriptions
                  (fn [_ctx _opts]
                    [{:content "weak" :score 0.4 :rank 1
                      :document-id "tf::w"
                      :document-metadata {:granularity :tree-fingerprint
                                          :target-id (random-uuid)}
                      :fitness-score 0.4}])]
      (let [result (ontology/classify-task {}
                     {:task-signature "novel"
                      :threshold 0.7
                      :walk-down? false})]
        (is (true? (:was-fresh-mint? result)))
        (is (nil? (:parent-tree-id result))
            "Fresh-mint with walk-down disabled has no parent")))))

;; =============================================================================
;; RED #3 — :walk-down? true descends into child when top-1 < specificity
;;
;; Setup:
;;   top-1 = parent P at fitness 0.85 (< specificity-threshold 0.9)
;;   P has one child C1 in the concept graph (skos:narrower)
;;   Re-rank of C1 against intent → fitness 0.92 (>= auto-classify-threshold 0.7)
;;   C1 has no further children
;; Expected: :assigned-tree-id = C1; :parent-tree-id = P; :was-fresh-mint? false
;; =============================================================================

(deftest classify-task-walks-down-to-child-when-top-1-below-specificity
  (testing "Walk-down picks the well-fitting child when top-1 is moderate"
    (let [parent-id (random-uuid)
          child-id  (random-uuid)
          ;; Concept graph: parent has narrower {child-uri}; child has no narrower
          fake-narrower (fn [_ctx uri]
                          (cond
                            (= uri (str "tree-class:" parent-id)) #{(str "tree-class:" child-id)}
                            :else #{}))
          ;; Description lookup: child has a summary; parent description
          ;; is not actually fetched (only its narrower set is)
          fake-get-desc (fn [_ctx granularity target-id]
                          (when (and (= granularity :tree-fingerprint)
                                     (= target-id child-id))
                            {:summary "Specific child variant of the parent pattern."
                             :version 1
                             :consolidated-from-event-count 1
                             :capabilities []
                             :strengths []
                             :weaknesses []
                             :representative-uses []
                             :avoid-when []}))
          ;; Reranker returns the child as a high-fitness match
          fake-rerank (fn [_ctx {:keys [candidates]}]
                        (mapv (fn [c]
                                {:document-id (:document-id c)
                                 :reasoning "Tighter match at the leaf level."
                                 :fitness-score 0.92})
                              candidates))]
      (with-redefs [ontology/search-descriptions       (fn [_ _] (fake-top-1 parent-id 0.85))
                    ontology/get-narrower-concepts     fake-narrower
                    ontology/get-description           fake-get-desc
                    reranker/rerank!                   fake-rerank]
        (let [result (ontology/classify-task {}
                       {:task-signature "extract per-section summaries"
                        :threshold 0.7
                        :walk-down? true
                        :specificity-threshold 0.9})]
          (is (= child-id (:assigned-tree-id result))
              ":assigned-tree-id is the CHILD, not the parent")
          (is (= parent-id (:parent-tree-id result))
              ":parent-tree-id is the PARENT we walked from")
          (is (false? (:was-fresh-mint? result))
              "Walked to an existing leaf — not a fresh mint")
          (is (= 0.92 (:confidence result))
              ":confidence reflects the leaf's fitness, not the parent's"))))))

;; =============================================================================
;; RED #4 — Walk considered children but none fit; no fresh-mint at depth 0
;;
;; Setup:
;;   top-1 = P at 0.85 (triggers walk because < specificity 0.9)
;;   P has one child C1
;;   Re-rank C1 → fitness 0.5 (< auto-classify-threshold 0.7)
;; Expected: classifier RETURNS P (no walk committed; nothing minted)
;;           :was-fresh-mint? false; :parent-tree-id nil
;; =============================================================================

(deftest classify-task-returns-current-when-no-child-fits-at-depth-zero
  (testing "Walk-down looks at children, finds none above threshold, returns current"
    (let [parent-id (random-uuid)
          child-id  (random-uuid)
          fake-narrower (fn [_ uri]
                          (cond
                            (= uri (str "tree-class:" parent-id))
                            #{(str "tree-class:" child-id)}
                            :else #{}))
          fake-get-desc (fn [_ _ target-id]
                          (when (= target-id child-id)
                            {:summary "Tangentially-related variant."
                             :version 1
                             :consolidated-from-event-count 1
                             :capabilities []
                             :strengths []
                             :weaknesses []
                             :representative-uses []
                             :avoid-when []}))
          fake-rerank (fn [_ {:keys [candidates]}]
                        (mapv (fn [c]
                                {:document-id (:document-id c)
                                 :reasoning "Loose, not a real fit."
                                 :fitness-score 0.5})
                              candidates))]
      (with-redefs [ontology/search-descriptions   (fn [_ _] (fake-top-1 parent-id 0.85))
                    ontology/get-narrower-concepts fake-narrower
                    ontology/get-description       fake-get-desc
                    reranker/rerank!               fake-rerank]
        (let [result (ontology/classify-task {}
                       {:task-signature "task that lives at parent level only"
                        :threshold 0.7
                        :walk-down? true
                        :specificity-threshold 0.9})]
          (is (= parent-id (:assigned-tree-id result))
              ":assigned-tree-id is the PARENT (no child fit; no commit)")
          (is (false? (:was-fresh-mint? result))
              "No fresh-mint at depth 0 — we never committed to a walk")
          (is (nil? (:parent-tree-id result))
              ":parent-tree-id nil — parent IS the assigned tree, has no grand-parent here")
          (is (= 0.85 (:confidence result))
              "Confidence is the parent's fitness, not the rejected child's"))))))

;; =============================================================================
;; RED #5 — Walk-down fresh-mint under deepest matched ancestor
;;
;; Setup:
;;   top-1 = P (0.85, < specificity 0.9 → walk)
;;   P has child C1
;;   Re-rank C1 → 0.92 (>= 0.7 → recurse to C1 at depth 1)
;;   C1 has child C2
;;   Re-rank C2 → 0.4 (< 0.7 → no fit at C1's level)
;;   We DID walk to C1 (depth 1) — mint fresh leaf under C1.
;; =============================================================================

(deftest classify-task-fresh-mints-when-walked-and-no-grandchild-fits
  (testing "Walked down to C1 (depth 1); no grandchild fits → mint fresh under C1"
    (let [parent-id (random-uuid)
          c1-id     (random-uuid)
          c2-id     (random-uuid)
          fake-narrower (fn [_ uri]
                          (cond
                            (= uri (str "tree-class:" parent-id))
                            #{(str "tree-class:" c1-id)}
                            (= uri (str "tree-class:" c1-id))
                            #{(str "tree-class:" c2-id)}
                            :else #{}))
          fake-get-desc (fn [_ _ target-id]
                          (cond
                            (= target-id c1-id)
                            {:summary "Mid-level variant under parent."
                             :version 1 :consolidated-from-event-count 1
                             :capabilities [] :strengths [] :weaknesses []
                             :representative-uses [] :avoid-when []}

                            (= target-id c2-id)
                            {:summary "Grandchild specialization."
                             :version 1 :consolidated-from-event-count 1
                             :capabilities [] :strengths [] :weaknesses []
                             :representative-uses [] :avoid-when []}))
          ;; Reranker returns different fitness based on what's being re-ranked.
          ;; We can tell which level by inspecting the candidate's :target-id
          ;; in :document-metadata.
          fake-rerank (fn [_ {:keys [candidates]}]
                        (mapv (fn [c]
                                (let [target-id (-> c :document-metadata :target-id)
                                      fit (cond
                                            (= target-id c1-id) 0.92  ;; C1 wins
                                            (= target-id c2-id) 0.40  ;; C2 loses
                                            :else 0.0)]
                                  {:document-id (:document-id c)
                                   :reasoning (str "depth-aware reasoning for " target-id)
                                   :fitness-score fit}))
                              candidates))]
      (with-redefs [ontology/search-descriptions   (fn [_ _] (fake-top-1 parent-id 0.85))
                    ontology/get-narrower-concepts fake-narrower
                    ontology/get-description       fake-get-desc
                    reranker/rerank!               fake-rerank]
        (let [result (ontology/classify-task {}
                       {:task-signature "novel-but-related task"
                        :threshold 0.7
                        :walk-down? true
                        :specificity-threshold 0.9})]
          (is (true? (:was-fresh-mint? result))
              "Fresh-mint because we walked to C1 and couldn't continue")
          (is (= c1-id (:parent-tree-id result))
              ":parent-tree-id is C1 — the deepest matched ancestor")
          (is (not= parent-id (:assigned-tree-id result))
              "Fresh-mint UUID is not P")
          (is (not= c1-id (:assigned-tree-id result))
              "Fresh-mint UUID is not C1")
          (is (not= c2-id (:assigned-tree-id result))
              "Fresh-mint UUID is not C2 (the rejected grandchild)")
          (is (uuid? (:assigned-tree-id result))
              ":assigned-tree-id is a UUID"))))))

;; =============================================================================
;; RED #6 — Hard depth cap at 5 stops the walk
;;
;; Build a synthetic hierarchy 7 levels deep with high-confidence at every
;; level (so the walk would keep going forever without a cap). Assert the
;; walk stops at depth 5.
;; =============================================================================

(deftest classify-task-respects-walk-down-depth-cap
  (testing "Walk stops at depth 5 even when more children would fit"
    (let [ids (vec (repeatedly 7 random-uuid))
          ;; ids[0] is the top-1 (P), ids[1] is child, ids[2] grandchild, etc.
          ;; Each id N has children #{(str "tree-class:" (id N+1))}.
          fake-narrower (fn [_ uri]
                          (let [parent-target (subs uri (count "tree-class:"))
                                idx (loop [i 0]
                                      (cond
                                        (>= i (count ids)) nil
                                        (= (str (nth ids i)) parent-target) i
                                        :else (recur (inc i))))]
                            (if (and idx (< (inc idx) (count ids)))
                              #{(str "tree-class:" (nth ids (inc idx)))}
                              #{})))
          fake-get-desc (fn [_ _ target-id]
                          (when (some #(= % target-id) ids)
                            {:summary (str "Level for " target-id)
                             :version 1 :consolidated-from-event-count 1
                             :capabilities [] :strengths [] :weaknesses []
                             :representative-uses [] :avoid-when []}))
          ;; Reranker always scores 0.95 — every level passes
          fake-rerank (fn [_ {:keys [candidates]}]
                        (mapv (fn [c]
                                {:document-id (:document-id c)
                                 :reasoning "Always fits"
                                 :fitness-score 0.95})
                              candidates))]
      (with-redefs [ontology/search-descriptions   (fn [_ _] (fake-top-1 (first ids) 0.85))
                    ontology/get-narrower-concepts fake-narrower
                    ontology/get-description       fake-get-desc
                    reranker/rerank!               fake-rerank]
        (let [result (ontology/classify-task {}
                       {:task-signature "deep walk"
                        :threshold 0.7
                        :walk-down? true
                        :specificity-threshold 0.9})]
          ;; max-walk-depth = 5. We start at depth 0 with P (ids[0]) and
          ;; descend to ids[1] (depth 1), ids[2] (depth 2), ids[3]
          ;; (depth 3), ids[4] (depth 4), ids[5] (depth 5 — cap fires
          ;; here, return current).
          (is (= (nth ids 5) (:assigned-tree-id result))
              "Walk stops at depth 5; returns the node we recursed to at that depth")
          (is (false? (:was-fresh-mint? result))
              "Depth cap returns current as-is; not a fresh-mint")
          (is (= (nth ids 4) (:parent-tree-id result))
              ":parent-tree-id is the node we walked FROM at depth 4"))))))

;; =============================================================================
;; RED #7 — :ontology/assign-task-class command with :parent-tree-id forwards
;; it through to the emitted :ontology/task-classified event body
;; =============================================================================

(deftest assign-task-class-defcommand-carries-parent-tree-id
  (testing "Command with :parent-tree-id → event body has :parent-tree-id"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            assigned (random-uuid)
            parent (random-uuid)
            cmd {:command/name :ontology/assign-task-class
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :source-sheet-id sheet-id
                 :source-tick-id tick-id
                 :source-node-id node-id
                 :assigned-tree-id assigned
                 :confidence 0.4
                 :top-candidates []
                 :reasoning "walked-down then fresh-minted under parent"
                 :was-fresh-mint? true
                 :parent-tree-id parent}]
        (cp/process-command (assoc ctx :command cmd))
        (let [events (into [] (es/read (:event-store ctx)
                                       {:tenant-id (:tenant-id ctx)
                                        :types #{:ontology/task-classified}}))
              event (first events)]
          (is (= 1 (count events)))
          (is (= parent (:parent-tree-id event))
              "Event body carries :parent-tree-id from the command")
          (let [schema (get @schema-util/registry* :ontology/task-classified)]
            (is (m/validate schema event)
                "Event with :parent-tree-id still validates"))))))

  (testing "Command without :parent-tree-id → event has no :parent-tree-id field"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            assigned (random-uuid)
            cmd {:command/name :ontology/assign-task-class
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :source-sheet-id sheet-id
                 :source-tick-id tick-id
                 :source-node-id node-id
                 :assigned-tree-id assigned
                 :confidence 0.9
                 :top-candidates []
                 :reasoning "matched"
                 :was-fresh-mint? false}]
        (cp/process-command (assoc ctx :command cmd))
        (let [events (into [] (es/read (:event-store ctx)
                                       {:tenant-id (:tenant-id ctx)
                                        :types #{:ontology/task-classified}}))
              event (first events)]
          (is (= 1 (count events)))
          (is (not (contains? event :parent-tree-id))
              "Legacy command (no parent) → event omits the field entirely"))))))

;; =============================================================================
;; RED #7b — Executor wedge forwards :parent-tree-id from classify-task's
;; result into the :ontology/assign-task-class command body
;; =============================================================================

(defn- run-wedge!
  "Invoke the C-2c-2 wedge with the given node + context. Returns the
   node (post-wedge)."
  [node context]
  (let [wedge (requiring-resolve
                'ai.obney.orc.orc-service.core.todo-processors/maybe-auto-classify-and-set-context)]
    (wedge node context)))

(deftest wedge-forwards-parent-tree-id-to-assign-task-class-command
  (testing "When classify-task returns :parent-tree-id, the dispatched command carries it"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            node-id (random-uuid)
            assigned (random-uuid)
            parent (random-uuid)
            classify-result {:assigned-tree-id assigned
                             :confidence 0.4
                             :top-candidates [{:document-id "x" :score 0.4}]
                             :reasoning "walked"
                             :was-fresh-mint? true
                             :parent-tree-id parent}]
        (with-redefs [ontology/classify-task (fn [_ _] classify-result)]
          (let [node {:id node-id
                      :name "test-node"
                      :instruction "Process the doc."
                      :reads [:document]
                      :writes [:summary]
                      :rlm {:auto-classify? true}}
                wedge-ctx (assoc ctx
                            :sheet-id sheet-id
                            :tick-id tick-id)
                _ (run-wedge! node wedge-ctx)
                events (into [] (es/read (:event-store ctx)
                                         {:tenant-id (:tenant-id ctx)
                                          :types #{:ontology/task-classified}}))
                event (first events)]
            (is (= 1 (count events)))
            (is (= parent (:parent-tree-id event))
                "Wedge forwarded :parent-tree-id through to the emitted event")
            (is (= assigned (:assigned-tree-id event)))
            (is (true? (:was-fresh-mint? event))))))))

  (testing "When classify-task returns nil :parent-tree-id, the event omits the field"
    (with-test-ctx [ctx]
      (let [node-id (random-uuid)
            classify-result {:assigned-tree-id (random-uuid)
                             :confidence 0.9
                             :top-candidates []
                             :reasoning "matched"
                             :was-fresh-mint? false
                             :parent-tree-id nil}]
        (with-redefs [ontology/classify-task (fn [_ _] classify-result)]
          (let [node {:id node-id
                      :name "node"
                      :instruction "x"
                      :reads [] :writes []
                      :rlm {:auto-classify? true}}
                wedge-ctx (assoc ctx
                            :sheet-id (random-uuid)
                            :tick-id (random-uuid))
                _ (run-wedge! node wedge-ctx)
                events (into [] (es/read (:event-store ctx)
                                         {:tenant-id (:tenant-id ctx)
                                          :types #{:ontology/task-classified}}))
                event (first events)]
            (is (= 1 (count events)))
            (is (not (contains? event :parent-tree-id))
                "Nil :parent-tree-id from classify-task → event omits the field")))))))

;; =============================================================================
;; Consolidator parent-inference: extracted helper `maybe-hydrate-parent-tree-id`
;;
;; The helper is the testable seam between the consolidator's body-assembly
;; and the description-recording command. Wraps the three-case decision:
;;   - non-tree-fingerprint targets → body unchanged
;;   - sticky case (prior description exists) → preserve its :parent-tree-id
;;   - first-time case → classify-task with :walk-down? false; assoc when
;;     match, omit + log orphan when fresh-mint
;;
;; Direct integration with the full consolidate! workflow is covered by
;; C-2d-3's live verify.
;; =============================================================================

;; =============================================================================
;; RED #8 — first-time tree-fingerprint: classify-task called → :parent-tree-id set
;; =============================================================================

(deftest hydrate-parent-tree-id-first-time-match
  (testing "First-time tree-fingerprint consolidation: classify-task → :parent-tree-id assoc'ed"
    (let [parent-id (random-uuid)
          target-id "fp:novel"
          body {:capabilities ["x"] :strengths [] :weaknesses []
                :representative-uses ["x"] :avoid-when ["x"]
                :summary "fresh consolidation" :version 1
                :consolidated-from-event-count 1}
          classify-calls (atom 0)]
      (with-redefs [ontology/classify-task
                    (fn [_ opts]
                      (swap! classify-calls inc)
                      (is (false? (:walk-down? opts))
                          "Helper passes :walk-down? false per Decision 5")
                      {:assigned-tree-id parent-id
                       :confidence 0.85
                       :top-candidates []
                       :reasoning "matches abstract chunked-extraction"
                       :was-fresh-mint? false
                       :parent-tree-id nil})]
        (let [hydrate (requiring-resolve
                        'ai.obney.orc.ontology.core.consolidator/maybe-hydrate-parent-tree-id)
              result (hydrate {} :tree-fingerprint target-id nil body)]
          (is (pos? @classify-calls)
              "classify-task was invoked")
          (is (= parent-id (:parent-tree-id result))
              ":parent-tree-id inferred from classify-task's match"))))))

;; =============================================================================
;; RED #9 — sticky: prior description's :parent-tree-id preserved, no classify
;; =============================================================================

(deftest hydrate-parent-tree-id-sticky-on-subsequent-consolidation
  (testing "Existing :parent-tree-id preserved; classify-task NOT called"
    (let [existing-parent (random-uuid)
          target-id "fp:already-known"
          current {:summary "prior" :version 1
                   :parent-tree-id existing-parent}
          body {:capabilities ["x"] :strengths [] :weaknesses []
                :representative-uses ["x"] :avoid-when ["x"]
                :summary "v2 summary" :version 2
                :consolidated-from-event-count 5}
          classify-calls (atom 0)]
      (with-redefs [ontology/classify-task
                    (fn [& _]
                      (swap! classify-calls inc)
                      (throw (ex-info "should not be called" {})))]
        (let [hydrate (requiring-resolve
                        'ai.obney.orc.ontology.core.consolidator/maybe-hydrate-parent-tree-id)
              result (hydrate {} :tree-fingerprint target-id current body)]
          (is (zero? @classify-calls)
              "classify-task NOT called (sticky)")
          (is (= existing-parent (:parent-tree-id result))
              "Existing parent preserved on the new body"))))))

;; =============================================================================
;; RED #10 — orphan logging when classify-task returns fresh-mint
;; =============================================================================

(deftest hydrate-parent-tree-id-orphan-omits-parent-when-classifier-fresh-mints
  (testing "Classify-task fresh-mints → no :parent-tree-id assoc'ed (orphan case)"
    ;; The orphan ::orphan-tree-class-created log is observability and is
    ;; verified at C-2d-3 live verify; mu/log is a macro and not
    ;; with-redefs-able. The structural assertion below is the real
    ;; semantic: under a fresh-mint classifier result, the body must
    ;; emerge without a :parent-tree-id key (so the C-2d-1 reactive
    ;; projector treats the new tree-class as top-level).
    (let [target-id "fp:truly-novel"
          body {:capabilities ["x"] :strengths [] :weaknesses []
                :representative-uses ["x"] :avoid-when ["x"]
                :summary "really novel" :version 1
                :consolidated-from-event-count 1}]
      (with-redefs [ontology/classify-task
                    (fn [_ _]
                      {:assigned-tree-id (random-uuid)
                       :confidence 0.3
                       :top-candidates []
                       :reasoning "below threshold"
                       :was-fresh-mint? true
                       :parent-tree-id nil})]
        (let [hydrate (requiring-resolve
                        'ai.obney.orc.ontology.core.consolidator/maybe-hydrate-parent-tree-id)
              result (hydrate {} :tree-fingerprint target-id nil body)]
          (is (not (contains? result :parent-tree-id))
              "No :parent-tree-id when classifier returned fresh-mint")
          (is (= body result)
              "Body returned otherwise-unchanged on orphan case"))))))

(deftest hydrate-parent-tree-id-orphan-omits-parent-when-classifier-throws
  (testing "Classify-task throws → no :parent-tree-id; helper catches and proceeds"
    (let [target-id "fp:truly-novel-2"
          body {:capabilities ["x"] :strengths [] :weaknesses []
                :representative-uses ["x"] :avoid-when ["x"]
                :summary "another novel" :version 1
                :consolidated-from-event-count 1}]
      (with-redefs [ontology/classify-task
                    (fn [_ _]
                      (throw (ex-info "downstream blew up" {})))]
        (let [hydrate (requiring-resolve
                        'ai.obney.orc.ontology.core.consolidator/maybe-hydrate-parent-tree-id)
              result (hydrate {} :tree-fingerprint target-id nil body)]
          (is (not (contains? result :parent-tree-id))
              "Inference failure is non-fatal — body returns without parent")
          (is (= body result)))))))

;; =============================================================================
;; RED #11 — non-tree-fingerprint targets pass through unchanged
;; =============================================================================

;; =============================================================================
;; RED #12 — coerce-to-uuid preserves UUID identity round-tripped through JSON
;;
;; Real-bug regression: ColBERT serializes target-id values through JSON,
;; so UUID seeds come back as STRINGS like
;; "00000000-c1c1-4001-b001-d0c0a0a0a0a1". The old coerce-to-uuid hashed
;; them via nameUUIDFromBytes, producing a DIFFERENT UUID — the walk-down
;; classifier would return the correct candidate then lose its identity.
;; This test pins the fix: stringified UUIDs parse back to the same UUID;
;; non-UUID strings still fall back to nameUUIDFromBytes (preserves the
;; existing string-fingerprint contract).
;; =============================================================================

(deftest classify-task-preserves-stringified-uuid-identity
  (testing "Stringified-UUID target-id round-trips back to the same UUID"
    (let [original-uuid (random-uuid)
          ;; Simulate ColBERT's JSON roundtrip: UUID → string
          target-id-as-string (str original-uuid)
          fake-results [{:content "x" :score 0.95 :rank 1
                         :document-id (str ":tree-fingerprint:" target-id-as-string)
                         :document-metadata {:granularity :tree-fingerprint
                                             :target-id target-id-as-string
                                             :confidence 1.0
                                             :last-update "2026"}
                         :reasoning "high-confidence match"
                         :fitness-score 0.95}]]
      (with-redefs [ontology/search-descriptions (fn [_ _] fake-results)]
        (let [result (ontology/classify-task {}
                       {:task-signature "x"
                        :threshold 0.7
                        :walk-down? false})]
          (is (= original-uuid (:assigned-tree-id result))
              ":assigned-tree-id IS the original UUID, not a hash-derived one")))))

  (testing "Non-UUID string fingerprint still derives a stable UUID via nameUUIDFromBytes"
    (let [fingerprint "seed:tree:NotAUuid"
          expected-uuid (java.util.UUID/nameUUIDFromBytes
                          (.getBytes fingerprint "UTF-8"))
          fake-results [{:content "x" :score 0.95 :rank 1
                         :document-id "x"
                         :document-metadata {:granularity :tree-fingerprint
                                             :target-id fingerprint
                                             :confidence 1.0}
                         :fitness-score 0.95}]]
      (with-redefs [ontology/search-descriptions (fn [_ _] fake-results)]
        (let [result (ontology/classify-task {}
                       {:task-signature "x" :threshold 0.7 :walk-down? false})]
          (is (= expected-uuid (:assigned-tree-id result))
              "Non-UUID-shaped strings still fall back to stable UUID derivation"))))))

(deftest hydrate-parent-tree-id-passthrough-for-other-target-types
  (testing "Non-tree-fingerprint target → body unchanged; classify-task not called"
    (let [body {:capabilities ["x"] :strengths [] :weaknesses []
                :representative-uses ["x"] :avoid-when ["x"]
                :summary "node-type body" :version 1
                :consolidated-from-event-count 1}
          classify-calls (atom 0)]
      (with-redefs [ontology/classify-task (fn [& _]
                                              (swap! classify-calls inc)
                                              (throw (ex-info "should not be called" {})))]
        (let [hydrate (requiring-resolve
                        'ai.obney.orc.ontology.core.consolidator/maybe-hydrate-parent-tree-id)
              result (hydrate {} :node-type :llm nil body)]
          (is (zero? @classify-calls))
          (is (= body result)
              "Body returned unchanged for non-tree-fingerprint targets"))))))
