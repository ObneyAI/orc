(ns ai.obney.orc.ontology.rr23-tag-scoped-evidence-test
  "RR-23 — contract tests: the loop's hot evidence reads are scoped by tag, the
   tree bookend is addressable by the campaign that produced it, scoped results
   equal their unscoped reference, and query cost no longer grows with unrelated
   store size (dossier G19). Contract tests are never weakened to pass.

   The reference implementations live HERE (test-side), so equivalence is a
   comparison against an independent computation, never against production."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.todo-processors]
            [ai.obney.orc.ontology.core.harvest :as harvest]
            [ai.obney.orc.ontology.core.consolidator :as consolidator]
            [ai.obney.orc.evaluation.interface.schemas]
            [ai.obney.orc.evaluation.core.commands]
            [ai.obney.orc.orc-service.interface.schemas]
            [ai.obney.orc.orc-service.core.commands]
            [ai.obney.orc.orc-service.core.rlm-fingerprint :as fp]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        cache-dir (str "/tmp/rr23-" (random-uuid))]
    {:event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
     :cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
     :tenant-id (random-uuid) :event-pubsub ps
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     ::cache-dir cache-dir}))

(defn- stop-context [ctx]
  (pubsub/stop (:event-pubsub ctx)) (kv/stop (:cache ctx)) (es/stop (:event-store ctx))
  (let [f (java.io.File. (::cache-dir ctx))]
    (when (.exists f) (doseq [c (.listFiles f)] (.delete c)) (.delete f))))

(defmacro with-ctx [[s] & body] `(let [~s (create-context)] (try ~@body (finally (stop-context ~s)))))

(defn- cmd! [ctx name body]
  (cp/process-command (assoc ctx :command (merge {:command/name name :command/id (random-uuid)
                                                   :command/timestamp (time/now)} body))))

(defn- all-events [ctx] (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)})))

(def ^:private shapes
  [[:sequence [:llm {:reads [:doc] :writes [:summary]}] [:final {:keys [:summary]}]]
   [:sequence [:llm {:reads [:doc] :writes [:notes]}] [:llm {:reads [:notes] :writes [:summary]}] [:final {:keys [:summary]}]]
   [:sequence [:code {:reads [:doc] :writes [:summary] :fn (fn [x] x)}] [:final {:keys [:summary]}]]])

(defn- campaign!
  "A classified campaign for `class` with `n-bookends` Phase-2 executions (last one
   `:success`), one judge score, and a verdict occurrence. Returns [sheet tick node]."
  [ctx class n-bookends verdict score]
  (let [sheet (random-uuid) tick (random-uuid) node (random-uuid)]
    (cmd! ctx :ontology/assign-task-class
          {:source-sheet-id sheet :source-tick-id tick :source-node-id node :assigned-tree-id class
           :confidence 0.95 :top-candidates [] :reasoning "rr23" :was-fresh-mint? false})
    (dotimes [i n-bookends]
      (let [tree (nth shapes (mod i 3))]
        (cmd! ctx :sheet/record-rlm-tree-execution-completion
              {:sheet-id (random-uuid) :tick-id (random-uuid) :trajectory [] :total-usage {:total-tokens 0}
               :status (if (= i (dec n-bookends)) :success :failure) :duration-ms 1
               :tree-fingerprint (fp/fingerprint tree) :generated-tree tree :generated-tree-source (pr-str tree)
               :source-sheet-id sheet :source-tick-id tick})))
    (cmd! ctx :evaluation/record-judge-score
          {:sheet-id sheet :tick-id tick :node-id node :judge-name "quality" :judge-config {}
           :score score :feedback "rr23" :dimensions []})
    (cmd! ctx :sheet/complete-node-execution
          {:sheet-id sheet :tick-id tick :node-id node :node-type :repl-researcher
           :completion-kind :terminal :status verdict :duration-ms 1})
    (let [cid (->> (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)
                                                :types #{:sheet/node-execution-completed}
                                                :tags #{[:tick tick]}})
                   (into []) last :event/id)]
      (cmd! ctx :ontology/record-tree-class-occurrence
            {:source-sheet-id sheet :source-tick-id tick :source-node-id node
             :source-completion-event-id cid :assigned-tree-id class :verdict verdict}))
    [sheet tick node]))

(defn- populate!
  "`n` campaigns for `class` plus `n-unrelated` campaigns for other classes."
  [ctx class n n-unrelated]
  (dotimes [i n] (campaign! ctx class (inc (mod i 3)) (if (zero? (mod i 4)) :failure :success) (/ (+ 5 (mod i 5)) 10.0)))
  (dotimes [i n-unrelated] (campaign! ctx (random-uuid) (inc (mod i 3)) :success 0.8)))

;; --- reference (unscoped) implementations, test-side ------------------------

(defn- ref-occurrence-scores [ctx class]
  (let [evs (all-events ctx)
        occs (filter #(and (= :ontology/tree-class-occurrence-recorded (:event/type %)) (= class (:assigned-tree-id %))) evs)
        scores (filter #(= :judge/score-emitted (:event/type %)) evs)]
    (->> occs
         (map (juxt :source-sheet-id :source-tick-id))
         distinct
         (keep (fn [[s t]] (let [ss (filter #(and (= s (:sheet-id %)) (= t (:tick-id %))) scores)]
                             (when (seq ss) (/ (reduce + 0.0 (map :score ss)) (count ss))))))
         vec)))

(defn- ref-winning-shape-coherence [ctx class]
  (let [evs (all-events ctx)
        pairs (->> evs (filter #(and (= :ontology/tree-class-occurrence-recorded (:event/type %))
                                     (= class (:assigned-tree-id %)) (= :success (:verdict %))))
                   (map (juxt :source-sheet-id :source-tick-id)) set)
        bookends (filter #(= :sheet/rlm-tree-execution-completed (:event/type %)) evs)
        winners (keep (fn [p] (->> bookends (filter #(and (= p [(:source-sheet-id %) (:source-tick-id %)]) (= :success (:status %))))
                                   last :tree-fingerprint)) pairs)]
    {:successful-campaigns (count pairs)
     :successful-shape-observations (count winners)
     :distinct-successful-shapes (count (distinct winners))}))

;; --- 1. the bookend tag ------------------------------------------------------

(deftest the-bookend-is-tagged-by-the-campaign-that-produced-it
  (with-ctx [ctx]
    (let [class (random-uuid) [_ tick _] (campaign! ctx class 2 :success 0.9)
          by-tag (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)
                                                       :types #{:sheet/rlm-tree-execution-completed}
                                                       :tags #{[:source-tick tick]}}))]
      (is (= 2 (count by-tag)) "both of the campaign's bookends are addressable by its source tick")
      (is (every? #(= tick (:source-tick-id %)) by-tag)))))

;; --- 2/3. equivalence -----------------------------------------------------

(deftest scoped-harvest-reads-equal-their-unscoped-reference
  (with-ctx [ctx]
    (let [class (random-uuid)]
      (populate! ctx class 9 6)
      (is (= (ref-occurrence-scores ctx class) (harvest/occurrence-scores ctx class)))
      (is (= (ref-winning-shape-coherence ctx class)
             (select-keys (harvest/winning-shape-coherence ctx class)
                          [:successful-campaigns :successful-shape-observations :distinct-successful-shapes])))
      (is (false? (harvest/already-harvested? ctx class))))))

(deftest scoped-reflection-gather-equals-its-unscoped-reference
  (with-ctx [ctx]
    (let [class (random-uuid)]
      (populate! ctx class 5 5)
      (let [observations (#'consolidator/gather-recent-tree-class-events ctx class)
            evs (all-events ctx)
            ref-classified (filter #(and (= :ontology/task-classified (:event/type %)) (= class (:assigned-tree-id %))) evs)]
        (is (= (count ref-classified) (count observations)) "one observation per classification of this class")
        (is (= (set (map :source-tick-id ref-classified)) (set (map :source-tick-id observations))))
        (is (every? #(some? (:execution %)) observations) "each observation is joined to its own campaign's completion")))))

(deftest the-composite-duplicate-check-is-scoped-to-its-execution
  (with-ctx [ctx]
    (let [class (random-uuid)]
      (populate! ctx class 3 3)
      (let [[sheet tick node] (campaign! ctx class 1 :success 0.9)
            reads (atom [])
            orig es/read]
        (with-redefs [es/read (fn [store q] (swap! reads conj q) (orig store q))]
          (cmd! ctx :evaluation/record-composite-score
                {:sheet-id sheet :tick-id tick :node-id node :composite-score 0.9
                 :contributing-judges [{:judge-name "quality" :score 0.9 :weight 1.0}]}))
        (let [composite-reads (filter #(contains? (:types %) :judge/composite-score-computed) @reads)]
          (is (seq composite-reads) "the duplicate check reads composite scores")
          (is (every? #(contains? (:tags %) [:tick tick]) composite-reads)
              "…scoped to this execution's tick, never a type-wide scan"))))))

;; --- 4. cost -----------------------------------------------------------------

(defn- events-materialised-by [ctx f]
  (let [n (atom 0) orig es/read]
    (with-redefs [es/read (fn [store q] (let [r (into [] (orig store q))] (swap! n + (count r)) r))]
      (f))
    @n))

(deftest hot-query-cost-does-not-grow-with-unrelated-store-size
  (testing "events materialised by the promotion path and the reflection gather are the same at store size S and 4S when the extra events belong to other classes"
    (let [measure (fn [n-unrelated]
                    (with-ctx [ctx]
                      (let [class (random-uuid)]
                        (populate! ctx class 6 n-unrelated)
                        {:promotion (events-materialised-by ctx #(harvest/maybe-harvest! ctx class))
                         :reflection (events-materialised-by ctx #(#'consolidator/gather-recent-tree-class-events ctx class))
                         :coherence (events-materialised-by ctx #(harvest/winning-shape-coherence ctx class))
                         :total (count (all-events ctx))})))
          small (measure 6)
          large (measure 24)]
      (println "RR-23 cost" {:small small :large large})
      (is (> (:total large) (* 2 (:total small))) "sanity: the larger store really is larger")
      (is (= (:promotion small) (:promotion large)) "promotion path reads do not grow with unrelated events")
      (is (= (:reflection small) (:reflection large)) "reflection gather reads do not grow with unrelated events")
      (is (= (:coherence small) (:coherence large)) "coherence measure reads do not grow with unrelated events"))))
