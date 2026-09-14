(ns ai.obney.orc.ontology.rr21-winning-shape-coherence-test
  "RR-21 — propagated from `specs/ontology.allium`
   `rule-success.ReportSuccessfulShapeCoherence` (and the already-covered
   `entity-optional.CampaignIteration.emitted_shape`), governed by the
   PromoteWellScoredClass coherence commentary and dossier G11:

     Coherence: a tight cluster, not a grab-bag. A winning shape is the
     terminal shape that carried a successful campaign to success. The
     measured ratio is distinct successful terminal shapes divided by
     successful campaigns. Failed and timed-out campaigns remain recurrence,
     quality and weakness evidence, but enter neither side of this ratio and
     therefore cannot make a failure-heavy class look more coherent. Shapes
     abandoned during repair are process evidence, not winning shapes. ...
     Until its real distribution has been observed it is computed and
     REPORTED without blocking promotion.

     rule ReportSuccessfulShapeCoherence {
         when: TreeClassOccurrenceRecorded(tree_class, occurrences)
         ... ensures: ShapeCoherenceReported(tree_class, verdict_occurrences,
             successful_campaigns, successful_shape_observations,
             distinct_successful_shapes, ratio, status, recorded_at) }

   Generated tests are contract: never weakened to pass. Seam-4 (ontology
   consumers over a synthesized event stream) through real commands and the
   registered processors; assertions read events and public functions back."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.ontology.core.harvest :as harvest]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.core.commands]
            [ai.obney.orc.orc-service.core.rlm-fingerprint :as fp]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

(defn- create-context
  "HARNESS FIX (found during RR-21 implementation, not an assertion change):
   `:sheet/complete-node-execution` (dispatched by this namespace's own
   `verdict!` helper for every :success/:failure/:timeout campaign ending)
   unconditionally calls `is-tick-or-ancestor-cancelled?` ->
   `read-model-processor-v2/project`, which REQUIRES a `:cache` (LMDB kv-store)
   in context — without one it throws `No implementation of method: :get! ...
   for class: nil` inside the command handler (caught by the processor and
   returned as an anomaly, so it fails SILENTLY: `record-tree-class-occurrence`
   then gets no completion-event-id and no-ops, and every campaign this
   namespace's `campaign!`/`verdict!` fixtures build for a :success/:failure/
   :timeout ending was silently never recorded). Sibling RR-20 fixtures in
   this codebase (`rr20-occurrence-corroboration-test`'s `create-context`)
   already provision exactly this LMDB cache for the identical command; this
   mirrors that established pattern. No `deftest`, `is`, `testing` or helper
   ARGUMENT/CALL was changed — only the context this file's own commands run
   against now carries the infrastructure they require."
  [processor-names]
  (let [event-pubsub (pubsub/start {:type :core-async :topic-fn :event/type})
        cache-dir (str "/tmp/rr21-coherence-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        base {:event-pubsub event-pubsub
              :event-store (es/start {:conn {:type :in-memory}
                                     :event-pubsub event-pubsub
                                     :logger nil})
              :cache cache
              :tenant-id (random-uuid)
              :command-registry (cp/global-command-registry)
              ::cache-dir cache-dir}
        processors (reduce-kv
                    (fn [acc n {:keys [handler-fn topics]}]
                      (if (contains? processor-names n)
                        (assoc acc n (tp/start {:event-pubsub event-pubsub :topics topics
                                                :handler-fn handler-fn :context base}))
                        acc))
                    {} @tp/processor-registry*)]
    (assoc base :processors processors)))

(defn- stop-context [ctx]
  (doseq [[_ p] (:processors ctx)] (tp/stop p))
  (es/stop (:event-store ctx))
  (pubsub/stop (:event-pubsub ctx))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defmacro with-ctx [[b procs] & body]
  `(let [~b (create-context ~procs)] (try ~@body (finally (stop-context ~b)))))

(defn- settle-until! [pred & {:keys [timeout-ms] :or {timeout-ms 4000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [] (cond (pred) true
                   (> (System/currentTimeMillis) deadline) false
                   :else (do (Thread/sleep 25) (recur))))))

(defn- cmd! [ctx name body]
  (cp/process-command
   (assoc ctx :command (merge {:command/name name :command/id (random-uuid)
                               :command/timestamp (time/now)} body))))

(defn- events [ctx type]
  (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :types #{type}})))

(def ^:private shape-a [:sequence [:llm {:reads [:doc] :writes [:summary]}] [:final {:keys [:summary]}]])
(def ^:private shape-b [:sequence [:llm {:reads [:doc] :writes [:notes]}] [:llm {:reads [:notes] :writes [:summary]}] [:final {:keys [:summary]}]])
(def ^:private shape-x [:sequence [:code {:reads [:doc] :writes [:summary]}] [:final {:keys [:summary]}]])

(defn- classify! [ctx sheet tick node class]
  (cmd! ctx :ontology/assign-task-class
        {:source-sheet-id sheet :source-tick-id tick :source-node-id node
         :assigned-tree-id class :confidence 0.95 :top-candidates []
         :reasoning "rr21" :was-fresh-mint? false}))

(defn- bookend! [ctx sheet tick tree status]
  (cmd! ctx :sheet/record-rlm-tree-execution-completion
        {:sheet-id (random-uuid) :tick-id (random-uuid) :trajectory []
         :total-usage {:total-tokens 0} :status status :duration-ms 1
         :tree-fingerprint (fp/fingerprint tree) :generated-tree tree
         :generated-tree-source (pr-str tree)
         :source-sheet-id sheet :source-tick-id tick}))

(defn- verdict! [ctx sheet tick node class status]
  (cmd! ctx :sheet/complete-node-execution
        {:sheet-id sheet :tick-id tick :node-id node :node-type :repl-researcher
         :completion-kind :terminal :status status :duration-ms 1})
  (let [cid (->> (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)
                                              :types #{:sheet/node-execution-completed}
                                              :tags #{[:tick tick]}})
                 (into []) last :event/id)]
    (cmd! ctx :ontology/record-tree-class-occurrence
          {:source-sheet-id sheet :source-tick-id tick :source-node-id node
           :source-completion-event-id cid :assigned-tree-id class :verdict status})))

(defn- campaign!
  "One classified campaign: `bookends` is a vector of [tree status] Phase-2
   executions in order; `ending` is :success/:failure/:timeout (a verdict),
   :cancelled or :abandoned (no verdict)."
  [ctx class bookends ending]
  (let [sheet (random-uuid) tick (random-uuid) node (random-uuid)]
    (classify! ctx sheet tick node class)
    (doseq [[tree status] bookends] (bookend! ctx sheet tick tree status))
    (case ending
      (:success :failure :timeout) (verdict! ctx sheet tick node class ending)
      :cancelled (es/append (:event-store ctx)
                            {:tenant-id (:tenant-id ctx)
                             :events [(es/->event {:type :sheet/tick-cancelled :tags #{[:tick tick]}
                                                   :body {:sheet-id sheet :tick-id tick :reason "operator stop"}})]})
      :abandoned (es/append (:event-store ctx)
                            {:tenant-id (:tenant-id ctx)
                             :events [(es/->event {:type :sheet/tree-tick-completed :tags #{[:tick tick]}
                                                   :body {:sheet-id sheet :tick-id tick :root-status :failure}})]}))
    [sheet tick node]))

(defn- measure
  "RED-first seam: `harvest/winning-shape-coherence` does not exist until
   RR-21 lands. Resolving at runtime keeps every other assertion in this
   namespace runnable and reporting on its own."
  [ctx class]
  (if-let [f (resolve 'ai.obney.orc.ontology.core.harvest/winning-shape-coherence)]
    (f ctx class)
    {:missing-fn 'ai.obney.orc.ontology.core.harvest/winning-shape-coherence}))

;; -----------------------------------------------------------------------------
;; The measure
;; -----------------------------------------------------------------------------

(deftest coherence-counts-one-winning-shape-per-successful-campaign
  (testing "three successful campaigns that each abandoned shape X and won with shape A → 1 distinct winning shape / 3"
    (with-ctx [ctx #{}]
      (let [class (random-uuid)]
        (dotimes [_ 3] (campaign! ctx class [[shape-x :failure] [shape-a :success]] :success))
        (let [m (measure ctx class)]
          (is (= 3 (:successful-campaigns m)))
          (is (= 3 (:successful-shape-observations m)) "one winning shape per successful campaign")
          (is (= 1 (:distinct-successful-shapes m)) "the repaired shape is the only winner; abandoned X is not counted")
          (is (= 1/3 (:ratio m)))
          (is (= :qualified (:status m)) "1/3 <= maximum-shape-ratio 0.5"))))))

(deftest failed-timed-out-cancelled-and-abandoned-campaigns-enter-neither-side
  (testing "only verdict-success campaigns feed the ratio"
    (with-ctx [ctx #{}]
      (let [class (random-uuid)]
        (campaign! ctx class [[shape-a :success]] :success)
        (campaign! ctx class [[shape-b :success]] :failure)
        (campaign! ctx class [[shape-b :success]] :timeout)
        (campaign! ctx class [[shape-b :success]] :cancelled)
        (campaign! ctx class [[shape-b :success]] :abandoned)
        (let [m (measure ctx class)]
          (is (= 1 (:successful-campaigns m)))
          (is (= 1 (:successful-shape-observations m)))
          (is (= 1 (:distinct-successful-shapes m)) "shape B rode only non-success campaigns and is not a winning shape")
          (is (= 1 (:ratio m))))))))

(deftest the-terminal-successful-shape-is-the-winning-shape
  (testing "a campaign that succeeded with A, then also ran B successfully before its verdict, has B as its winning shape (terminal)"
    (with-ctx [ctx #{}]
      (let [class (random-uuid)]
        (campaign! ctx class [[shape-a :success] [shape-b :success]] :success)
        (campaign! ctx class [[shape-b :success]] :success)
        (let [m (measure ctx class)]
          (is (= 2 (:successful-campaigns m)))
          (is (= 1 (:distinct-successful-shapes m)) "both campaigns' terminal shape is B")
          (is (= 1/2 (:ratio m))))))))

(deftest a-grab-bag-is-rejected-and-a-shapeless-class-is-not-measurable
  (testing "rejected vs not-measurable are distinct facts"
    (with-ctx [ctx #{}]
      (let [grab-bag (random-uuid) shapeless (random-uuid)]
        (campaign! ctx grab-bag [[shape-a :success]] :success)
        (campaign! ctx grab-bag [[shape-b :success]] :success)
        (campaign! ctx grab-bag [[shape-x :success]] :success)
        (let [m (measure ctx grab-bag)]
          (is (= 3 (:successful-campaigns m)))
          (is (= 3 (:distinct-successful-shapes m)))
          (is (= 1 (:ratio m)))
          (is (= :rejected (:status m)) "3/3 > 0.5"))
        (campaign! ctx shapeless [] :success)
        (let [m (measure ctx shapeless)]
          (is (= 1 (:successful-campaigns m)))
          (is (= 0 (:successful-shape-observations m)))
          (is (nil? (:ratio m)))
          (is (= :not-measurable (:status m)) "a successful campaign with no recorded shape is not evidence either way"))))))

;; -----------------------------------------------------------------------------
;; Report-only rollout
;; -----------------------------------------------------------------------------

(deftest coherence-is-reported-but-never-gates-promotion
  (testing "the harvest gate ignores the coherence clause while the report still carries its verdict"
    (let [good {:occurrences 12
                :judge-trailing-averages {"quality" 0.9}
                :occurrence-scores (vec (repeat 12 0.9))
                :winning-shape-coherence {:successful-campaigns 12
                                          :successful-shape-observations 12
                                          :distinct-successful-shapes 11
                                          :ratio 11/12
                                          :status :rejected}}
          report (harvest/harvest-gate-report good harvest/default-harvest-config)]
      (is (true? (harvest/harvest-candidate? good harvest/default-harvest-config))
          "a grab-bag no longer blocks promotion while the measure is report-only")
      (is (true? (:candidate? report)))
      (is (= :rejected (get-in report [:coherence :verdict])))
      (is (= 11/12 (get-in report [:coherence :ratio])))
      (let [unmeasurable (assoc good :winning-shape-coherence {:successful-campaigns 0
                                                              :successful-shape-observations 0
                                                              :distinct-successful-shapes 0
                                                              :ratio nil
                                                              :status :not-measurable})
            r2 (harvest/harvest-gate-report unmeasurable harvest/default-harvest-config)]
        (is (= :not-measurable (get-in r2 [:coherence :verdict]))
            "'not yet measurable' is reported as such, never as coherent or as rejected")
        (is (true? (:candidate? r2)))))))

(deftest the-observed-distribution-is-recorded-durably-on-every-verdict-occurrence
  (testing "rule ReportSuccessfulShapeCoherence: each TreeClassOccurrenceRecorded yields one ShapeCoherenceReported"
    (with-ctx [ctx #{:ontology/on-tree-class-check-harvest}]
      (let [class (random-uuid)]
        (campaign! ctx class [[shape-x :failure] [shape-a :success]] :success)
        (campaign! ctx class [[shape-a :success]] :failure)
        (campaign! ctx class [[shape-b :success]] :success)
        (is (settle-until! (fn [] (= 3 (count (filter #(= class (:tree-class %))
                                                     (events ctx :ontology/shape-coherence-reported))))))
            "one durable report per verdict occurrence (success, failure and success all trigger the rule)")
        (let [reports (->> (events ctx :ontology/shape-coherence-reported)
                           (filter #(= class (:tree-class %)))
                           (sort-by :verdict-occurrences))
              last-report (last reports)]
          (is (= [1 2 3] (mapv :verdict-occurrences reports)))
          (is (= 2 (:successful-campaigns last-report)))
          (is (= 2 (:successful-shape-observations last-report)))
          (is (= 2 (:distinct-successful-shapes last-report)))
          (is (= 1 (:ratio last-report)))
          (is (= :rejected (:status last-report)))
          (is (= 0.5 (:maximum-shape-ratio last-report)))
          (is (string? (:recorded-at last-report)))
          (is (= [1 1 1 :rejected]
                 ((juxt :successful-campaigns :successful-shape-observations
                        :distinct-successful-shapes :status) (first reports)))
              "the first report saw one successful campaign whose winning shape was A: 1/1 > 0.5")
          (is (= [1 1 1 :rejected]
                 ((juxt :successful-campaigns :successful-shape-observations
                        :distinct-successful-shapes :status) (second reports)))
              "the failure verdict changed nothing on either side of the ratio"))))))
