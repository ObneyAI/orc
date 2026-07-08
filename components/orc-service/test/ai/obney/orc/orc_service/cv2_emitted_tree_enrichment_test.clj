(ns ai.obney.orc.orc-service.cv2-emitted-tree-enrichment-test
  "CV-2 (ADR 0017, decision 3): post-emit emitted-tree worked-DSL enrichment.

   A harvested specialist must ship the REAL proven pattern (ADR 0015's
   \"capture the emitted tree\"). CV-1 landed the retrievability FLOOR (a
   provisional :tree-class description whose :summary is the task signature).
   CV-2 is ADDITIVE: after the RLM emits its tree (Phase-2 completion), the
   assigned :tree-class description gains a :strengths entry whose
   :recommended-pattern is the emitted worked-DSL — the content harvest reads.

   Two coupled parts, one vertical slice:
     1. The durable :sheet/rlm-tree-execution-completed bookend now carries
        the emitted DSL (optional :generated-tree) + the SOURCE sheet-id
        (optional :source-sheet-id) — the host/classified sheet, distinct
        from the ephemeral Phase-2 sheet the bookend's :sheet-id names.
     2. A post-emit enrichment processor resolves the tree-class for the
        source sheet (the sheet->class join task-classified already carries),
        reads the CURRENT :tree-class description, and re-records it with the
        emitted DSL added as a :strengths[].:recommended-pattern — preserving
        CV-1's :summary + any existing strengths.

   Disciplines: real grain (commands -> schema-validated events ->
   projections; NO bare appends), assert by READING the description read-model
   BACK. NO second synthesis LLM."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [dscloj.core :as dscloj]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.core.commands]
            [ai.obney.orc.orc-service.core.read-models]
            ;; Register the ontology enrichment + description + read-model
            ;; namespaces so the post-emit enrichment processor is on the
            ;; global processor registry and its commands/read-models resolve.
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.ontology.core.harvest :as harvest]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

;; ---------------------------------------------------------------------------
;; Stub the consolidator's reflection LLM so any threshold-fired autonomous
;; consolidation completes fast + deterministically (never a real OpenRouter
;; call). CV-2's enrichment itself uses NO LLM; this is only harness hygiene.
;; ---------------------------------------------------------------------------
(defn- stub-predict-fixture [f]
  (with-redefs [dscloj/predict
                (fn [_provider _module _inputs _options]
                  {:outputs {:capabilities [] :strengths [] :weaknesses []
                             :representative-uses [] :avoid-when []
                             :summary "cv2 stub"}
                   :usage {:total-tokens 1}
                   :model "stub"})]
    (f)))

(use-fixtures :each stub-predict-fixture)

;; ---------------------------------------------------------------------------
;; Context helpers (mirror rlm_rolling_metrics_test / el4_harvest_test — all
;; registered processors started, so the enrichment processor fires for real).
;; ---------------------------------------------------------------------------
(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/cv2-test-" (random-uuid))
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
;; Fixtures — the emitted worked-DSL is a pure S-expr (matches seed shape).
;; ---------------------------------------------------------------------------
(def ^:private emitted-tree
  "A pure-data emitted S-expr tree (what emit-tree! produces as
   :generated-tree-raw and the bookend sanitizes before durable storage)."
  [:sequence
   [:llm {:reads [:doc] :writes [:summary]}]
   [:final {:keys [:summary]}]])

;; ---------------------------------------------------------------------------
;; Real-grain fixtures — every step is a schema-validated command.
;; ---------------------------------------------------------------------------
(defn- classify! [ctx source-sheet-id class-id]
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/assign-task-class
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :source-sheet-id source-sheet-id
            :source-tick-id (random-uuid)
            :source-node-id (random-uuid)
            :assigned-tree-id class-id
            :confidence 0.95
            :top-candidates []
            :reasoning "test"
            :was-fresh-mint? true})))

(defn- record-floor! [ctx class-id signature]
  ;; CV-1's provisional floor: signature as :summary, empty strengths.
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/record-tree-class-description
            :command/id (random-uuid)
            :command/timestamp (time/now)
            :target-id class-id
            :body {:summary signature
                   :capabilities []
                   :strengths []
                   :weaknesses []
                   :representative-uses []
                   :avoid-when []
                   :version 1
                   :consolidated-from-event-count 0}})))

(defn- complete-emit! [ctx source-sheet-id tree]
  ;; The Phase-2 bookend: an ephemeral :sheet-id (distinct from the source),
  ;; carrying the emitted DSL + the source sheet-id for the sheet->class join.
  (cp/process-command
    (assoc ctx :command
           (cond-> {:command/name :sheet/record-rlm-tree-execution-completion
                    :command/id (random-uuid)
                    :command/timestamp (time/now)
                    :sheet-id (random-uuid)  ;; ephemeral Phase-2 sheet
                    :tick-id (random-uuid)
                    :trajectory []
                    :total-usage {:total-tokens 0}}
             source-sheet-id (assoc :source-sheet-id source-sheet-id)
             tree            (assoc :generated-tree tree)))))

(defn- emit-strength
  "The single :strengths entry CV-2 carries the emitted worked-DSL in."
  [desc]
  (->> (:strengths desc)
       (filter :recommended-pattern)
       first))

(defn- tree-class-description-updates
  "All :ontology/tree-description-updated events recorded for this
   :tree-class — one per record-tree-class-description call. Used to prove
   thrash-safety (an identical re-emit records nothing new)."
  [ctx class-id]
  (->> (es/read (:event-store ctx)
                {:types #{:ontology/tree-description-updated}
                 :tenant-id (:tenant-id ctx)})
       (into [])
       (filter #(and (= :tree-class (:target-type %))
                     (= class-id (:target-id %))))))

;; ===========================================================================
;; CYCLE 1 — the completion command/event carries :generated-tree
;; ===========================================================================

(deftest cycle1-completion-event-carries-generated-tree
  (testing ":sheet/record-rlm-tree-execution-completion with :generated-tree + :source-sheet-id emits an event carrying both"
    (with-test-ctx [ctx]
      (let [sheet-id (random-uuid)
            source-sheet-id (random-uuid)
            tick-id (random-uuid)
            cmd {:command/name :sheet/record-rlm-tree-execution-completion
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :sheet-id sheet-id
                 :tick-id tick-id
                 :trajectory []
                 :total-usage {:total-tokens 100}
                 :generated-tree emitted-tree
                 :source-sheet-id source-sheet-id}
            result (cp/process-command (assoc ctx :command cmd))
            event (first (:command-result/events result))]
        (is (= :sheet/rlm-tree-execution-completed (:event/type event))
            "Event type is the tree-completion bookend")
        (is (= emitted-tree (:generated-tree event))
            "Event carries the emitted DSL from the command")
        (is (= source-sheet-id (:source-sheet-id event))
            "Event carries the SOURCE (host/classified) sheet-id from the command")))))

(deftest cycle1-completion-event-backward-compatible-without-tree
  (testing "omitting :generated-tree / :source-sheet-id still emits the legacy event shape (backward compatible)"
    (with-test-ctx [ctx]
      (let [cmd {:command/name :sheet/record-rlm-tree-execution-completion
                 :command/id (random-uuid)
                 :command/timestamp (time/now)
                 :sheet-id (random-uuid)
                 :tick-id (random-uuid)
                 :trajectory []
                 :total-usage {:total-tokens 0}}
            result (cp/process-command (assoc ctx :command cmd))
            event (first (:command-result/events result))]
        (is (= :sheet/rlm-tree-execution-completed (:event/type event))
            "Legacy dispatch (no tree) still emits the bookend")
        (is (nil? (:generated-tree event))
            "No :generated-tree present when not supplied")
        (is (nil? (:source-sheet-id event))
            "No :source-sheet-id present when not supplied")))))

;; ===========================================================================
;; CYCLE 2 — post-emit enrichment lands the emitted DSL as :recommended-pattern
;; ===========================================================================

(deftest cycle2-enrichment-records-emitted-dsl-as-recommended-pattern
  (testing "after an emit, the assigned :tree-class description gains a :strengths entry whose :recommended-pattern = the emitted DSL; CV-1's :summary preserved"
    (with-test-ctx [ctx]
      (let [source-sheet-id (random-uuid)
            class-id (random-uuid)
            signature "implement: summarize a document"]
        ;; CV-1 floor: classify the task + record the provisional description.
        (classify! ctx source-sheet-id class-id)
        (record-floor! ctx class-id signature)
        (Thread/sleep 150)
        ;; The RLM emits its tree (Phase-2 completion bookend).
        (complete-emit! ctx source-sheet-id emitted-tree)
        (Thread/sleep 400)
        ;; Read the description read-model BACK — never trust a return value.
        (let [desc (ontology/get-description ctx :tree-class class-id)
              strength (emit-strength desc)]
          (is (some? desc) "the class description still exists")
          (is (= signature (:summary desc))
              "CV-1's :summary (the retrieval floor) is PRESERVED, not clobbered")
          (is (some? strength)
              "a :strengths entry carrying :recommended-pattern was added")
          (is (= (pr-str emitted-tree) (:recommended-pattern strength))
              "the strength's :recommended-pattern is the emitted worked-DSL"))))))

;; ===========================================================================
;; CYCLE 3 — EL-4 harvest picks up the emitted DSL as :recommended-pattern
;; ===========================================================================

(deftest cycle3-harvest-body-returns-emitted-dsl
  (testing "harvest-body's best-recommended-pattern returns the emitted DSL for the enriched class (a harvested specialist ships the real worked pattern)"
    (with-test-ctx [ctx]
      (let [source-sheet-id (random-uuid)
            class-id (random-uuid)]
        (classify! ctx source-sheet-id class-id)
        (record-floor! ctx class-id "implement: summarize a document")
        (Thread/sleep 150)
        (complete-emit! ctx source-sheet-id emitted-tree)
        (Thread/sleep 400)
        ;; Harvest assembles the durable behavior body by REUSING the
        ;; enriched description (no second synthesis). It must carry the
        ;; emitted worked-DSL as :recommended-pattern.
        (let [desc (ontology/get-description ctx :tree-class class-id)
              body (harvest/harvest-body desc 12)]
          (is (some? body) "harvest-body assembled from the enriched description")
          (is (= (pr-str emitted-tree) (:recommended-pattern body))
              "harvest ships the emitted worked-DSL, not just the summary"))))))

;; ===========================================================================
;; CYCLE 4 — timeout / no-emit path is safe (additive, never a regression)
;; ===========================================================================

(deftest cycle4-timeout-no-emit-leaves-cv1-floor-intact
  (testing "a turn that times out before emit (completion carries NO :generated-tree) records no enrichment; the class stays retrievable via CV-1's floor"
    (with-test-ctx [ctx]
      (let [source-sheet-id (random-uuid)
            class-id (random-uuid)
            signature "implement: summarize a document"]
        (classify! ctx source-sheet-id class-id)
        (record-floor! ctx class-id signature)
        (Thread/sleep 150)
        ;; Timeout bookend: source-sheet-id present, but NO emitted tree.
        (complete-emit! ctx source-sheet-id nil)
        (Thread/sleep 300)
        (let [desc (ontology/get-description ctx :tree-class class-id)]
          (is (some? desc) "the class remains retrievable (CV-1 floor)")
          (is (= signature (:summary desc)) "CV-1's signature summary is intact")
          (is (nil? (emit-strength desc))
              "no :recommended-pattern strength added on the no-emit path"))))))

(deftest cycle4-emit-for-unclassified-sheet-is-a-safe-noop
  (testing "an emit whose source sheet was never classified enriches nothing and does not crash"
    (with-test-ctx [ctx]
      (let [unclassified-sheet (random-uuid)]
        ;; No classify!, no floor — just an emit bookend for a stray sheet.
        (complete-emit! ctx unclassified-sheet emitted-tree)
        (Thread/sleep 300)
        (is (nil? (ontology/get-tree-class-for-sheet ctx unclassified-sheet))
            "no class resolves for an unclassified sheet — enrichment is a no-op")))))

;; ===========================================================================
;; CYCLE 5 — thrash-safe: an identical re-emit records nothing new
;; ===========================================================================

(deftest cycle5-identical-re-emit-does-not-rerecord
  (testing "re-emitting the SAME DSL enriches once (idempotent) — no redundant tree-description-updated (no reindex thrash), and exactly one emit strength"
    (with-test-ctx [ctx]
      (let [source-sheet-id (random-uuid)
            class-id (random-uuid)]
        (classify! ctx source-sheet-id class-id)
        (record-floor! ctx class-id "implement: summarize a document")
        (Thread/sleep 150)
        ;; floor recorded => 1 tree-class description update so far.
        (complete-emit! ctx source-sheet-id emitted-tree)
        (Thread/sleep 400)
        (complete-emit! ctx source-sheet-id emitted-tree)  ;; identical re-emit
        (Thread/sleep 400)
        (let [desc (ontology/get-description ctx :tree-class class-id)
              updates (tree-class-description-updates ctx class-id)]
          (is (= 2 (count updates))
              "exactly 2 records: CV-1 floor + ONE enrichment (the identical re-emit no-ops)")
          (is (= 1 (count (filter :recommended-pattern (:strengths desc))))
              "exactly one emitted-pattern strength — the re-emit did not duplicate it")
          (is (= (pr-str emitted-tree) (:recommended-pattern (emit-strength desc)))
              "the emitted DSL is still the recorded pattern"))))))
