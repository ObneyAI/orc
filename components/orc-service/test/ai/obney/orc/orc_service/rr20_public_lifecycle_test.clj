(ns ai.obney.orc.orc-service.rr20-public-lifecycle-test
  "RR-20's public lifecycle proof (the handoff brief's Cycle 6): a
   DEFAULT-CHECKPOINTED `:repl-researcher` campaign, driven end-to-end
   through the public `sheet/execute` boundary (Seam-1), whose SCRIPTED
   provider emits a quoted tree A that FAILS Phase-2 execution for real,
   then a repaired quoted tree B that SUCCEEDS Phase-2 execution and
   finalizes the campaign — never a raw event append, never a direct call to
   any private writer, never a mocked executor.

   The scripted-provider seam and the quoted-tree/`emit-tree!` pattern are
   REUSED verbatim from `checkpointed_researcher_test`'s
   `quoted-inline-code-tree-is-durable-source-and-executes` (RR-6's durable-
   source proof: `(emit-tree! (quote <tree>))`, `with-redefs [llm/predict
   (fn [& _] (case (swap! calls inc) ...))]`). The REAL, unmocked Phase-2
   failure mode — a `:code` leaf whose `:fn` throws — is reused verbatim from
   `rlm_tree_executor_test`'s `tree-with-failing-llm-node-propagates-error`
   (an LLM leaf there; a `:code` leaf here) and
   `recursive_rlm_test`'s `recursive-mode-failed-leaves-surface-on-tree-
   results` (T2-Hardening-A: a `:code` leaf's `:fn` throwing is the SAME
   runtime path a model-authored buggy validator hits in production, and the
   campaign is proven to continue to the next Phase-1 iteration rather than
   terminating). No fake was needed for either seam — nothing here mocks
   `execute-tree`, `execute-repl-researcher-rlm`, or any executor internal.

   The classification seam (pre-classify the campaign's real
   [sheet-id tick-id node-id] to a known tree-class, with a CV-1
   `:representative-use` floor claim) is reused verbatim from
   `cc6_cv2_claim_enrichment_test`'s `classify!`/`capture-floor!` — made
   possible because `sheet/execute` accepts a caller-supplied `:tick-id`
   (`runtime/execute`'s documented option), so the occurrence identity is
   known and classifiable BEFORE the campaign runs.

   Assertions read raw events and projections back (Seam-3) — never trust a
   return value alone. Every wait is a settle loop on the real store; no
   fixed sleep stands in for a proof."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.rlm-fingerprint :as rlm-fingerprint]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.ontology.core.harvest :as harvest]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]))

;; -----------------------------------------------------------------------------
;; Tree A — FAILS Phase-2 for real. Reused verbatim (a `:code` leaf whose
;; `:fn` throws) from recursive_rlm_test's T2-Hardening-A / rlm_tree_executor_
;; test's failing-node pattern; quoted per RR-6's durable-source discipline.
;; -----------------------------------------------------------------------------
(def ^:private tree-a
  '[:sequence
    [:code {:reads []
            :writes [:produced]
            :output-schemas {:produced :string}
            :fn (fn [_] (throw (ex-info "intentional RR-20 fault" {})))}]
    [:final {:keys [:produced]}]])

;; The repair — a GENUINELY DIFFERENT SHAPE from tree-a (two :code leaves,
;; not one), because `rlm-fingerprint/fingerprint` deliberately normalizes
;; away `:fn` BODY content (content, not structure) — two trees that differ
;; ONLY in what their :fn throws-vs-returns would collide on the SAME
;; fingerprint, which is wrong for this fixture (A and B must be
;; distinguishable shapes, exactly as the propagated RR-20 suite's own
;; tree-a/tree-b fixtures differ in node count/:reads/:writes, not merely
;; in :fn content).
(def ^:private tree-b
  '[:sequence
    [:code {:reads []
            :writes [:staged]
            :output-schemas {:staged :string}
            :fn (fn [_] {:staged "repaired-value"})}]
    [:code {:reads [:staged]
            :writes [:produced]
            :output-schemas {:produced :string}
            :fn (fn [{:keys [inputs]}] {:produced (:staged inputs)})}]
    [:final {:keys [:produced]}]])

(def ^:private tree-a-code
  (str "(emit-tree! (quote " (pr-str tree-a) "))"))
(def ^:private tree-b-code
  (str "(emit-tree! (quote " (pr-str tree-b) "))"))

(defn- classify! [ctx source-sheet-id source-tick-id source-node-id class-id]
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/assign-task-class
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :source-sheet-id source-sheet-id
           :source-tick-id source-tick-id
           :source-node-id source-node-id
           :assigned-tree-id class-id
           :confidence 0.95
           :top-candidates []
           :reasoning "rr20-public-lifecycle"
           :was-fresh-mint? true})))

(defn- capture-floor!
  "CV-1's floor, as CC-6 writes it — a :representative-use claim admitted on
   its declared :classification-signature basis (no judge has run at
   classify time). Reused verbatim from cc6_cv2_claim_enrichment_test."
  [ctx class-id signature]
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/record-claim-deltas
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :granularity :tree-class
           :target-identifier class-id
           :deltas [{:operation :add :kind :representative-use
                     :content signature :episodes []
                     :from-legacy-corpus false
                     :evidence-basis :classification-signature}]
           :evidence-event-count 0
           :claim-set-version (ontology/get-claim-set-version ctx :tree-class class-id)})))

(defn- bookend-for [events fingerprint]
  (first (filter #(and (= :sheet/rlm-tree-execution-completed (:event/type %))
                       (= fingerprint (:tree-fingerprint %)))
                 events)))

(deftest a-fail-then-repair-campaign-crystallizes-the-repaired-shape-as-the-proven-pattern
  (testing "RR-20 Cycle 6 — the full public lifecycle: through sheet/execute, a
            checkpointed researcher's Phase-2 tree FAILS then a repair SUCCEEDS
            and finalizes; the campaign's durable evidence — bookends,
            iteration records, the RR-19 occurrence, the claim set, and
            harvest — all agree the REPAIRED shape is the class's proven
            worked pattern and the failed shape is recorded as failed"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [class-id (random-uuid)
            calls (atom 0)
            definition
            (sheet/workflow "rr20-public-lifecycle"
              (sheet/blackboard {:produced :string})
              (sheet/repl-researcher "researcher"
                :instruction "emit a tree; if it failed, repair and re-emit; then finish"
                :reads []
                :writes [:produced]
                :max-iterations 4
                :rlm {:checkpointed? true
                      :recursive? true
                      :timeouts {:provider-ms 1000
                                 :iteration-ms 5000
                                 :campaign-ms 30000}}))
            sheet-id (sheet/build-workflow! ctx definition)
            researcher-id (:id (first (filter #(= "researcher" (:name %))
                                              (sheet/get-nodes-for-sheet ctx sheet-id))))
            tick-id (random-uuid)]
        ;; Pre-classify the campaign's REAL occurrence — known BEFORE
        ;; execution because :tick-id is caller-suppliable on sheet/execute.
        (classify! ctx sheet-id tick-id researcher-id class-id)
        (capture-floor! ctx class-id "implement: produce a value, repairing on failure")
        (Thread/sleep 150)
        (with-redefs [llm/predict
                      (fn [& _]
                        (case (swap! calls inc)
                          1 {:outputs {:code tree-a-code}
                             :reasoning "emit the first attempt"
                             :usage {:prompt_tokens 4 :completion_tokens 3 :total_tokens 7}}
                          2 {:outputs {:code tree-b-code}
                             :reasoning "the first attempt failed; repair and re-emit"
                             :usage {:prompt_tokens 5 :completion_tokens 4 :total_tokens 9}}
                          3 {:outputs {:code "(final! {:produced (get-var :produced)})"}
                             :reasoning "the repair succeeded"
                             :usage {:prompt_tokens 3 :completion_tokens 2 :total_tokens 5}}
                          (throw (ex-info "RR-20 public-lifecycle fixture exceeded three turns" {}))))]
          (let [result (sheet/execute ctx sheet-id {} :tick-id tick-id :timeout-ms 30000)]

            ;; ---------------------------------------------------------------
            ;; Public boundary: the campaign finalizes successfully.
            ;; ---------------------------------------------------------------
            (is (= :success (:status result)) (pr-str result))
            (is (= "repaired-value" (get-in result [:outputs :produced])) (pr-str result))
            (is (= tick-id (:trace-id result)))
            (is (= 3 @calls))

            ;; ---------------------------------------------------------------
            ;; 1. Two REAL :sheet/rlm-tree-execution-completed bookends.
            ;; ---------------------------------------------------------------
            (is (h/settle-until!
                 #(= 2 (count (filter (fn [e] (and (= :sheet/rlm-tree-execution-completed
                                                        (:event/type e))
                                                    (= sheet-id (:source-sheet-id e))
                                                    (= tick-id (:source-tick-id e))))
                                      (into [] (es/read (:event-store ctx)
                                                        {:tenant-id (:tenant-id ctx)})))))
                 :timeout-ms 10000)
                "both Phase-2 bookends landed, attributed to this campaign's occurrence")
            (let [all-events (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)}))
                  fp-a (rlm-fingerprint/fingerprint tree-a)
                  fp-b (rlm-fingerprint/fingerprint tree-b)
                  bookend-a (bookend-for all-events fp-a)
                  bookend-b (bookend-for all-events fp-b)]
              (is (some? bookend-a) "tree A's bookend exists")
              (is (some? bookend-b) "tree B's bookend exists")
              (is (= :failure (:status bookend-a)))
              (is (= :success (:status bookend-b)))
              (is (= (pr-str tree-a) (:generated-tree-source bookend-a)))
              (is (= (pr-str tree-b) (:generated-tree-source bookend-b)))
              (is (= sheet-id (:source-sheet-id bookend-a) (:source-sheet-id bookend-b)))
              (is (= tick-id (:source-tick-id bookend-a) (:source-tick-id bookend-b)))

              ;; -------------------------------------------------------------
              ;; 2. RecordedTreesCarryTheirShape: both iteration records
              ;;    carry a non-empty :tree-fingerprint when a tree was
              ;;    recorded.
              ;; -------------------------------------------------------------
              (let [records (rm/get-researcher-iteration-records ctx sheet-id tick-id researcher-id)
                    tree-records (filter :emitted-tree-recorded? records)]
                (is (>= (count tree-records) 2)
                    "both emit attempts left a recorded-tree iteration record")
                (doseq [r tree-records]
                  (is (and (string? (:tree-fingerprint r)) (not-empty (:tree-fingerprint r)))
                      (str "RecordedTreesCarryTheirShape: " (pr-str r))))
                (is (= #{fp-a fp-b} (into #{} (map :tree-fingerprint tree-records)))
                    "the iteration records carry BOTH shapes"))

              ;; -------------------------------------------------------------
              ;; 3. Exactly one RR-19 occurrence, :verdict :success.
              ;; -------------------------------------------------------------
              (is (h/settle-until!
                   #(= 1 (count (filter (fn [e] (and (= :ontology/tree-class-occurrence-recorded
                                                          (:event/type e))
                                                      (= sheet-id (:source-sheet-id e))
                                                      (= tick-id (:source-tick-id e))))
                                        (into [] (es/read (:event-store ctx)
                                                          {:tenant-id (:tenant-id ctx)})))))
                   :timeout-ms 10000)
                  "exactly one durable campaign verdict for this occurrence")
              (let [occurrences (filter #(and (= :ontology/tree-class-occurrence-recorded
                                                    (:event/type %))
                                               (= sheet-id (:source-sheet-id %))
                                               (= tick-id (:source-tick-id %)))
                                        (into [] (es/read (:event-store ctx)
                                                          {:tenant-id (:tenant-id ctx)})))]
                (is (= 1 (count occurrences)))
                (is (= :success (:verdict (first occurrences))))
                (is (= class-id (:assigned-tree-id (first occurrences)))))

              ;; -------------------------------------------------------------
              ;; 4. The claim set: B is :strength (exact source,
              ;;    :verdict-corroborations 1); A is :weakness (exact
              ;;    source); neither touched the other.
              ;; -------------------------------------------------------------
              (is (h/settle-until!
                   #(let [claims (ontology/get-claims ctx :tree-class class-id)
                          b-claim (first (filter (fn [c] (= (pr-str tree-b) (:recommendation c))) claims))]
                      (and (some? b-claim) (= 1 (:verdict-corroborations b-claim))))
                   :timeout-ms 10000)
                  "B's claim was reinforced by the durable campaign verdict")
              (let [claims (ontology/get-claims ctx :tree-class class-id)
                    a-claim (first (filter #(= (pr-str tree-a) (:recommendation %)) claims))
                    b-claim (first (filter #(= (pr-str tree-b) (:recommendation %)) claims))]
                (is (some? a-claim) "A's shape is recorded")
                (is (some? b-claim) "B's shape is recorded")
                (is (= :weakness (:kind a-claim)) "A is recorded as a failed shape")
                (is (= :strength (:kind b-claim)) "B is recorded as a worked pattern")
                (is (= (pr-str tree-a) (:recommendation a-claim)) "A's exact source")
                (is (= (pr-str tree-b) (:recommendation b-claim)) "B's exact source")
                (is (= 1 (:verdict-corroborations b-claim)))
                (is (zero? (or (:verdict-corroborations a-claim) 0))
                    "A was never touched by the success verdict")
                (is (not= (:claim-id a-claim) (:claim-id b-claim))
                    "distinct claims — neither displaced the other"))

              ;; -------------------------------------------------------------
              ;; 5 & 6. harvest-body offers B's exact source; the assembled
              ;; strength entry for B carries :verdict-corroborations 1.
              ;; -------------------------------------------------------------
              (let [desc (ontology/get-description ctx :tree-class class-id)
                    body (harvest/harvest-body desc 12)
                    b-entry (first (filter #(= (pr-str tree-b) (:recommended-pattern %))
                                           (:strengths desc)))]
                (is (= (pr-str tree-b) (:recommended-pattern body))
                    "harvest-body offers the repaired, proven shape — never the failed one")
                (is (some? b-entry) "the assembled body carries B's strength entry")
                (is (= 1 (:verdict-corroborations b-entry))
                    "the assembled strength entry surfaces the verdict corroboration")))))))))
