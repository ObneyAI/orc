(ns ai.obney.orc.orc-service.rr22-pattern-adoption-live-test
  "RR-22 (`OfferedPatternsAreUsable`) Cycle 5 — the scripted-provider LIVE
   adopt (Seam-1): a proven pattern, offered WHOLE with its declared key
   bindings by R-Inject at Phase 1, is ADOPTED into a genuinely different
   task without re-deriving its logic — through the real `sheet/execute`
   boundary, never a raw event append, never a mocked executor.

   Reuses the scripted-provider / quoted-tree / `classify!` seam VERBATIM
   from `rr20_public_lifecycle_test`'s Seam-1 pattern
   (`(emit-tree! (quote <tree>))`, `with-redefs [llm/predict ...]`,
   `sheet/execute ctx sheet-id {} :tick-id tick-id`).

   WHAT 'DIFFERENT KEYS' MEANS HERE (read this before touching the
   assertions): `rlm-fingerprint/fingerprint` deliberately KEEPS a tree's
   :reads/:writes blackboard keys in its hash — only :fn bodies and
   :instruction text are normalized away (`rlm_fingerprint.clj`). So a
   pattern whose INTERNAL :reads/:writes were themselves renamed would NOT
   fingerprint-match the original, and 'the emitted rebound tree shares the
   offered pattern's shape fingerprint' would be unprovable by construction
   if 'rebind to different keys' meant renaming the pattern's own internal
   keys. The mechanically-rebindable seam the corpus's own ADOPT move
   describes ('use a reference as-is when it is an EXACT fit') is instead
   the OUTER glue: this task's researcher node declares its own :writes
   under a key ([:report]) that DIFFERS from the pattern's internal output
   key (:summary) — a genuinely different task/occurrence — and the model
   copies the pattern's tree BYTE-FOR-BYTE (identical :reads/:writes
   throughout, so identical fingerprint — proof no re-derivation happened),
   then bridges the pattern's internal output key to the task's declared
   key with one `(final! {:report (get-var :summary)})` call. No re-reading
   of the pattern's code was needed to find :summary — R-Inject's rendered
   'Writes: :draft, :summary · Outputs: :summary' line already named it."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [ai.obney.orc.orc-service.core.rlm-fingerprint :as rlm-fingerprint]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.llm.interface :as llm]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]))

;; -----------------------------------------------------------------------------
;; The offered pattern — a "proven" tree recorded directly as a :strength
;; claim (Seam-4, same claim-recording path RR-19/RR-20 use to crystallize a
;; winning shape; this test does not re-prove THAT mechanism, RR-19/RR-20's
;; own suites do). Two :code leaves so the pattern's OWN internal keys
;; (:draft written-then-read, :summary the final output) are unambiguous.
;; -----------------------------------------------------------------------------
(def ^:private offered-tree
  '[:sequence
    [:code {:reads []
            :writes [:draft]
            :output-schemas {:draft :string}
            :fn (fn [_] {:draft "first pass"})}]
    [:code {:reads [:draft]
            :writes [:summary]
            :output-schemas {:summary :string}
            :fn (fn [{:keys [inputs]}] {:summary (str "SUMMARY: " (:draft inputs))})}]
    [:final {:keys [:summary]}]])

(def ^:private offered-tree-source (pr-str offered-tree))

(defn- seed-offered-pattern!
  "Record the pattern directly as a proven :strength claim for `class-id` —
   the same command RR-20's crystallization writes, and the same one
   `rr22-pattern-key-bindings-test`'s `assembled-strength-entry-carries-its-
   bindings-additively` proves derives :pattern-reads/:pattern-writes/
   :pattern-outputs from this exact source."
  [ctx class-id]
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/record-claim-deltas
           :command/id (random-uuid) :command/timestamp (time/now)
           :granularity :tree-class :target-identifier class-id
           :deltas [{:operation :add :kind :strength
                     :content "emits the proven draft-then-summarize shape"
                     :recommendation offered-tree-source
                     :episodes [] :from-legacy-corpus false
                     :evidence-basis :emitted-artifact-outcome}]
           :evidence-event-count 0
           :claim-set-version (ontology/get-claim-set-version ctx :tree-class class-id)})))

(defn- classify!
  "Pre-classify the campaign's REAL occurrence to `class-id` — reused
   verbatim from `rr20_public_lifecycle_test`, possible because
   `sheet/execute` accepts a caller-supplied :tick-id."
  [ctx source-sheet-id source-tick-id source-node-id class-id]
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/assign-task-class
           :command/id (random-uuid) :command/timestamp (time/now)
           :source-sheet-id source-sheet-id :source-tick-id source-tick-id
           :source-node-id source-node-id :assigned-tree-id class-id
           :confidence 0.95 :top-candidates []
           :reasoning "rr22-pattern-adoption-live" :was-fresh-mint? false})))

(defn- r05-classifier-payload
  "The wedge's :r05-classifier envelope, stamped STATICALLY on the node's
   :context at build time (`sheet/repl-researcher`'s :context option) so
   `apply-r05-classifier-context` renders it live at Phase 1 without
   depending on the (async, non-deterministic) auto-classify pipeline —
   the same static-:context shape `r_inject_classifier_context_test`'s
   `mk-node` uses, now exercised through the real executor instead of a
   direct unit call."
  [class-id]
  {:structural {:assigned-tree-id class-id :confidence 0.95 :was-fresh-mint? false
                :reasoning "matches the draft-then-summarize shape"
                :top-candidates [{:content "draft-then-summarize"
                                   :document-id (str ":tree-class:" class-id)
                                   :document-metadata {:target-id class-id}
                                   :fitness-score 0.95
                                   :reasoning "matches the draft-then-summarize shape"
                                   :rerank-source :reranker}]
                :rerank-fallback? false}
   :behavioral {:behaviors [] :rerank-fallback? false}})

(defn- success-bookend-tree-source [events sheet-id tick-id]
  (some (fn [e] (and (= :sheet/rlm-tree-execution-completed (:event/type e))
                     (= sheet-id (:source-sheet-id e))
                     (= tick-id (:source-tick-id e))
                     (= :success (:status e))
                     (:generated-tree-source e)))
        events))

(deftest a-model-can-adopt-a-pattern-against-different-keys
  (testing "RR-22 Cycle 5 — a default-checkpointed researcher, through
            sheet/execute, is offered a proven pattern whose corpus block
            names its declared :reads/:writes/:outputs; the scripted
            provider's FIRST prompt carries the pattern whole plus its
            bindings, the model ADOPTS it verbatim into a task whose OWN
            declared :writes key differs from the pattern's internal
            output key, bridging the two with one final! call — the
            campaign succeeds and the emitted tree's shape fingerprint
            equals the offered pattern's, proving the rebind never
            re-derived the pattern's logic"
    (h/with-async-test-context [ctx {:context {:llm-provider :test}}]
      (let [class-id (random-uuid)]
        (seed-offered-pattern! ctx class-id)
        (Thread/sleep 150)
        (let [definition
              (sheet/workflow "rr22-pattern-adoption-live"
                (sheet/blackboard {:report :string})
                (sheet/repl-researcher "researcher"
                  :instruction "produce a report; adopt a proven corpus pattern if one fits"
                  :reads []
                  :writes [:report]
                  :max-iterations 4
                  :rlm {:checkpointed? true
                        :recursive? true
                        :timeouts {:provider-ms 1000
                                   :iteration-ms 5000
                                   :campaign-ms 30000}}
                  :context {:tree-id class-id
                            :r05-classifier (r05-classifier-payload class-id)}))
              sheet-id (sheet/build-workflow! ctx definition)
              researcher-id (:id (first (filter #(= "researcher" (:name %))
                                                (sheet/get-nodes-for-sheet ctx sheet-id))))
              tick-id (random-uuid)
              received-tasks (atom [])
              calls (atom 0)
              ;; ADOPT: the model copies the offered pattern's EXACT source —
              ;; same :reads/:writes throughout, only the surrounding task
              ;; differs. This is the "as-is" move R-Inject's own prompt
              ;; names; content (the :fn bodies) is unchanged too, since
              ;; nothing about THIS task requires adapting the logic.
              adopted-tree-code (str "(emit-tree! (quote " offered-tree-source "))")]
          (classify! ctx sheet-id tick-id researcher-id class-id)
          (with-redefs [llm/predict
                        (fn [_provider _module inputs & _]
                          (swap! received-tasks conj (:task inputs))
                          (case (swap! calls inc)
                            1 {:outputs {:code adopted-tree-code}
                               :reasoning "adopt the proven draft-then-summarize pattern as-is"
                               :usage {:prompt_tokens 4 :completion_tokens 3 :total_tokens 7}}
                            ;; The bridge: :summary is the PATTERN's own key
                            ;; (named in the rendered "Writes:"/"Outputs:"
                            ;; line, never re-derived by reading the tree);
                            ;; :report is THIS task's declared :writes key.
                            2 {:outputs {:code "(final! {:report (get-var :summary)})"}
                               :reasoning "bridge the pattern's :summary to this task's declared :report"
                               :usage {:prompt_tokens 3 :completion_tokens 2 :total_tokens 5}}
                            (throw (ex-info "RR-22 pattern-adoption fixture exceeded two turns" {}))))]
            (let [result (sheet/execute ctx sheet-id {} :tick-id tick-id :timeout-ms 30000)]

              (is (= :success (:status result)) (pr-str result))
              (is (= "SUMMARY: first pass" (get-in result [:outputs :report])) (pr-str result))
              (is (= 2 @calls))

              (testing "the FIRST prompt carries the whole pattern and its declared bindings"
                (let [first-task (str (first @received-tasks))]
                  (is (str/includes? first-task offered-tree-source)
                      "the exact, complete offered pattern reached the model")
                  (is (not (str/includes? first-task "[truncated]"))
                      "no truncation marker of any kind")
                  (is (re-find #"(?i)writes:\s*:draft" first-task))
                  (is (re-find #"(?i)outputs:\s*:summary" first-task))
                  (is (re-find #"(?i)rebind|adapt" first-task)
                      "the bindings are offered for rebinding, not as a mandate")))

              (testing "the emitted rebound tree shares the offered pattern's shape fingerprint"
                (is (h/settle-until!
                     #(some? (success-bookend-tree-source
                              (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)}))
                              sheet-id tick-id))
                     :timeout-ms 10000)
                    "the durable success bookend landed")
                (let [emitted-source (success-bookend-tree-source
                                      (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)}))
                                      sheet-id tick-id)
                      emitted-tree (read-string emitted-source)]
                  (is (= offered-tree-source emitted-source)
                      "the durably recorded tree is the offered pattern's exact source — ADOPT, not reconstruction")
                  (is (= (rlm-fingerprint/fingerprint offered-tree)
                         (rlm-fingerprint/fingerprint emitted-tree))
                      "same shape — same :reads/:writes throughout — proves the rebind never re-derived the pattern's logic"))))))))))
