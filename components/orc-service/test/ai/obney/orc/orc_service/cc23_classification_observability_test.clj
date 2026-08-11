(ns ai.obney.orc.orc-service.cc23-classification-observability-test
  "CC-23 (contract TaskClassification, spec 18c76bc1): classification
   observability — the code side of the spec's two invariants.

   @invariant DeferralIsVisible — a classification that defers (the semantic
   reranker fell back, fitness unknown) leaves a DURABLE
   :ontology/task-classification-deferred event carrying the source
   occurrence triplet, the fallback source, and the bounded ranking
   snapshot. 'Uncertain' and 'nothing happened' are different facts; the
   deferral rate is a measured number (count-from-store), never an
   inference from missing events.

   @invariant DecidedRankingIsRecorded — the classified event records the
   PRE-GATE ranking the decision ran on (:ranked-candidates, bounded to the
   retrieval k, each entry reduced to identity/axis/scores/rerank-source —
   never description content) plus the explicit :assigned-via provenance.

   All assertions read the EVENT STORE back — never a return value. The
   rerank seam is stubbed deterministically (colbert/search + reranker
   rerank!): no LLM calls, no store stubbing."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.orc.ontology.core.reranker :as reranker]
            [ai.obney.orc.colbert.interface :as colbert]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.schemas]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as gtime]))

;; =============================================================================
;; Test context (mirrors el3_wedge_skip_uncertain_test — real in-memory
;; event store + LMDB cache; nothing store-level is stubbed)
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/cc23-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)]
    {:event-store event-store
     :cache cache
     :tenant-id tenant-id
     :event-pubsub ps
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     :sheet-id (random-uuid)
     :tick-id (random-uuid)
     ::cache-dir cache-dir}))

(defn- stop-context [ctx]
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [store (:event-store ctx)] (es/stop store))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [child (.listFiles f)] (.delete child))
        (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)]
     (try ~@body (finally (stop-context ~sym)))))

(defn- events-of [ctx type]
  (into [] (es/read (:event-store ctx)
                    {:tenant-id (:tenant-id ctx)
                     :types #{type}})))

(defn- node []
  {:id (random-uuid)
   :name "cc23-test-node"
   :type :repl-researcher
   :instruction "summarize the quarterly report"
   :reads [] :writes []
   :rlm {:auto-classify? true}})

;; =============================================================================
;; Rerank-seam stubs. classify-task's retrieval path is
;;   search-descriptions → latest-ontology-descriptions-index
;;     → (colbert list-indexes) → (colbert search) → apply-rerank → rerank!
;; We stub list-indexes (index discovery), search (deterministic ColBERT
;; candidates), and rerank! (the LLM seam). Everything downstream —
;; classify-task's branches, the wedge, the command, the store — is REAL.
;; =============================================================================

(defn- fake-list-indexes [_ctx & _opts]
  [{:index-name "ontology-descriptions"
    :index-id   (random-uuid)
    :created-at "2026-05-28T00:00:00Z"}])

(def ^:private tree-class-id-a (random-uuid))
(def ^:private tree-fp-id-b (random-uuid))
(def ^:private tree-class-id-c (random-uuid))

(def ^:private colbert-candidates
  "Deterministic ColBERT candidates: two :tree-class + one
   :tree-fingerprint, WITH :content (the reduction under test must strip
   it). snake_case metadata mirrors the real JSON bridge."
  [{:content "tree-class A description content — MUST NOT reach any event"
    :score 0.91 :rank 1
    :document-id "doc-a"
    :document_metadata {:granularity "tree-class" :target-id (str tree-class-id-a)
                        :confidence 0.9 :last-update "2026"}}
   {:content "tree-fingerprint B description content — MUST NOT reach any event"
    :score 0.72 :rank 2
    :document-id "doc-b"
    :document_metadata {:granularity "tree-fingerprint" :target-id (str tree-fp-id-b)
                        :confidence 0.7 :last-update "2026"}}
   {:content "tree-class C description content — MUST NOT reach any event"
    :score 0.55 :rank 3
    :document-id "doc-c"
    :document_metadata {:granularity "tree-class" :target-id (str tree-class-id-c)
                        :confidence 0.5 :last-update "2026"}}])

(defmacro with-rerank-fallback
  "Stub the rerank seam so the reranker DID NOT RANK: rerank! returns
   `fallback-value` (nil → :colbert-fallback; the timeout marker →
   :timeout-fallback). ColBERT retrieval itself succeeds."
  [fallback-value & body]
  `(with-redefs [colbert/list-indexes fake-list-indexes
                 colbert/search (fn [_ctx# _opts#] colbert-candidates)
                 reranker/rerank! (fn [_ctx# _opts#] ~fallback-value)]
     ~@body))

;; =============================================================================
;; RED 1 — DeferralIsVisible: a rerank-fallback classification leaves a
;; DURABLE :ontology/task-classification-deferred event in the STORE.
;; Fails today: the call site silently drops the :uncertain result.
;; =============================================================================

(deftest deferral-leaves-durable-event-on-colbert-fallback
  (testing "reranker fell back (:colbert-fallback) → ONE durable :ontology/task-classification-deferred event with the source triplet, fallback source, bounded ranking, reasoning + stamp; NO task-classified event"
    (with-test-ctx [ctx]
      (let [n (node)]
        (with-rerank-fallback nil
          (tp/maybe-auto-classify-and-set-context n ctx))
        (let [deferred (events-of ctx :ontology/task-classification-deferred)
              classified (events-of ctx :ontology/task-classified)
              event (first deferred)]
          (is (= 1 (count deferred))
              "EXACTLY ONE durable deferral event lands in the store")
          (is (= 0 (count classified))
              "the deferral did NOT also assign/mint (no task-classified event)")
          ;; Source occurrence triplet
          (is (= (:sheet-id ctx) (:source-sheet-id event))
              "the deferral carries the source sheet-id")
          (is (= (:tick-id ctx) (:source-tick-id event))
              "the deferral carries the source tick-id")
          (is (= (:id n) (:source-node-id event))
              "the deferral carries the source node-id")
          ;; Fallback source (closed set)
          (is (= :colbert-fallback (:fallback-source event))
              "the fallback source is read from the candidates' :rerank-source")
          ;; Bounded ranking snapshot
          (is (vector? (:ranked-candidates event))
              ":ranked-candidates is a vector snapshot")
          (is (= 3 (count (:ranked-candidates event)))
              "the snapshot carries the full raw candidate set (3 stubbed)")
          (is (<= (count (:ranked-candidates event)) 5)
              "the snapshot is bounded to the retrieval k (5)")
          (is (every? #(= :colbert-fallback (:rerank-source %))
                      (:ranked-candidates event))
              "every snapshot entry records the fallback rerank-source")
          (is (= [(str tree-class-id-a) (str tree-fp-id-b) (str tree-class-id-c)]
                 (mapv :target-id (:ranked-candidates event)))
              "snapshot entries carry candidate identity in ranking order")
          ;; Reasoning + stamp
          (is (string? (:reasoning event)) ":reasoning rides on the event")
          (is (string? (:deferred-at event)) "the event carries a stamp")
          ;; Queryable by occurrence
          (is (= 1 (count (into [] (es/read (:event-store ctx)
                                            {:tenant-id (:tenant-id ctx)
                                             :types #{:ontology/task-classification-deferred}
                                             :tags #{[:tick (:tick-id ctx)]}}))))
              "the deferral is queryable by its [:tick tick-id] tag"))))))

;; =============================================================================
;; RED 2 — DecidedRankingIsRecorded: the classified event carries the
;; PRE-GATE ranking (:ranked-candidates) including a below-gate candidate
;; that the gated :top-candidates omits (the 150/156 defect reproduced),
;; plus :assigned-via matching the branch taken.
;; Fails today: neither field exists on the event.
;; =============================================================================

(defn- seed-below-gate-class!
  "Give `tree-class-id` a consolidation total of 1 — inside the gate's
   filter band (0 < 1 < retrieval-gate 3) — by recording one real
   :ontology/task-classified assignment for it. This makes it a candidate
   that MATCHING sees but SURFACING gates out."
  [ctx tree-class-id]
  (cp/process-command
    (assoc ctx :command
           {:command/name :ontology/assign-task-class
            :command/id (random-uuid)
            :command/timestamp (gtime/now)
            :source-sheet-id (random-uuid)
            :source-tick-id (random-uuid)
            :source-node-id (random-uuid)
            :assigned-tree-id tree-class-id
            :confidence 0.9
            :top-candidates []
            :reasoning "seed occurrence to place the class inside the gate band"
            :was-fresh-mint? true})))

(defmacro with-rerank-success
  "Stub the rerank seam with a SUCCESSFUL deterministic rerank: rerank!
   returns `fake-rerank` (document-id/reasoning/fitness-score triples,
   joined back onto the stubbed ColBERT candidates)."
  [fake-rerank & body]
  `(with-redefs [colbert/list-indexes fake-list-indexes
                 colbert/search (fn [_ctx# _opts#] colbert-candidates)
                 reranker/rerank! (fn [_ctx# _opts#] ~fake-rerank)]
     ~@body))

(deftest classified-event-records-pre-gate-ranking-and-assigned-via-match
  (testing "confident top-1 match → event carries :ranked-candidates = PRE-GATE ranking (incl. the below-gate candidate :top-candidates omits) + :assigned-via :match; :top-candidates keeps its exact gated shape"
    (with-test-ctx [ctx]
      (seed-below-gate-class! ctx tree-class-id-c)
      (with-rerank-success [{:document-id "doc-a" :reasoning "strong structural fit" :fitness-score 0.95}
                            {:document-id "doc-c" :reasoning "close but below-gate class" :fitness-score 0.8}
                            {:document-id "doc-b" :reasoning "weak" :fitness-score 0.4}]
        (tp/maybe-auto-classify-and-set-context (node) ctx))
      (let [events (->> (events-of ctx :ontology/task-classified)
                        ;; ignore the seed assignment
                        (remove #(= tree-class-id-c (:assigned-tree-id %))))
            event (first events)]
        (is (= 1 (count events)) "exactly one classification event for this tick")
        (is (= tree-class-id-a (:assigned-tree-id event))
            "sanity: top-1 (doc-a) was assigned")
        ;; :assigned-via — explicit provenance, closed set
        (is (= :match (:assigned-via event))
            ":assigned-via records the branch taken (:match for a confident top-1)")
        ;; :ranked-candidates — the PRE-GATE ranking the decision ran on
        (is (= [(str tree-class-id-a) (str tree-class-id-c) (str tree-fp-id-b)]
               (mapv :target-id (:ranked-candidates event)))
            ":ranked-candidates is the pre-gate ranking in decision order (reranker order)")
        (is (some #(= (str tree-class-id-c) (:target-id %)) (:ranked-candidates event))
            "the BELOW-GATE candidate is IN the recorded decision ranking")
        (is (= [0.95 0.8 0.4] (mapv :fitness-score (:ranked-candidates event)))
            "each entry carries the fitness the decision compared")
        (is (every? #(= :reranker (:rerank-source %)) (:ranked-candidates event))
            "each entry records its rerank source")
        (is (every? #(contains? % :score) (:ranked-candidates event))
            "each entry carries the raw retrieval score")
        (is (every? #(= :tree-class (:granularity %))
                    (filter #(not= (str tree-fp-id-b) (:target-id %))
                            (:ranked-candidates event)))
            "each entry carries its granularity/axis")
        ;; The 150/156 defect, reproduced: the gated surfaced view OMITS the
        ;; below-gate candidate — and its exact current shape is untouched.
        (is (not-any? #(= (str tree-class-id-c) (-> % :document-metadata :target-id))
                      (:top-candidates event))
            ":top-candidates (the gated view) omits the below-gate candidate")
        (is (= #{"doc-a" "doc-b"} (into #{} (map :document-id) (:top-candidates event)))
            ":top-candidates still carries exactly the gate-passing candidates")
        (is (every? #(contains? % :content) (:top-candidates event))
            ":top-candidates keeps its exact current (full-content) shape — ADDITIVE, never mutative")))))

(deftest classified-event-records-assigned-via-mint
  (testing "confident no-match fresh mint → :assigned-via :mint on the event, with the same pre-gate ranking recorded"
    (with-test-ctx [ctx]
      (with-rerank-success [{:document-id "doc-a" :reasoning "poor fit" :fitness-score 0.2}
                            {:document-id "doc-b" :reasoning "poor fit" :fitness-score 0.15}
                            {:document-id "doc-c" :reasoning "poor fit" :fitness-score 0.1}]
        (tp/maybe-auto-classify-and-set-context (node) ctx))
      (let [events (events-of ctx :ontology/task-classified)
            event (first events)]
        (is (= 1 (count events)) "exactly one classification event")
        (is (true? (:was-fresh-mint? event)) "sanity: the mint branch fired")
        (is (= :mint (:assigned-via event))
            ":assigned-via records the branch taken (:mint)")
        (is (= 3 (count (:ranked-candidates event)))
            "the pre-gate ranking is recorded on the mint path too")))))

;; =============================================================================
;; RED 3 — payload bound: every :ranked-candidates entry carries NO
;; :content / :summary (no description text of any kind), and the vector
;; length is <= the retrieval k (5) even when retrieval yields more.
;; =============================================================================

(def ^:private allowed-ranked-keys
  "The spec's reduction, exactly: identity, axis, scores, rerank source."
  #{:target-id :granularity :fitness-score :score :rerank-source})

(def ^:private six-colbert-candidates
  "Six candidates (> k) to prove the bound clips, every one carrying
   :content that must never reach an event."
  (vec (for [i (range 6)]
         {:content (str "description content " i " — MUST NOT reach any event")
          :score (- 0.9 (* 0.1 i)) :rank (inc i)
          :document-id (str "doc-" i)
          :document_metadata {:granularity "tree-class"
                              :target-id (str (random-uuid))
                              :confidence 0.5 :last-update "2026"}})))

(deftest ranked-candidates-payload-is-bounded-and-content-free
  (testing "classified event: every :ranked-candidates entry has ONLY the reduced keys (no :content, no :summary) and length <= 5"
    (with-test-ctx [ctx]
      (with-redefs [colbert/list-indexes fake-list-indexes
                    colbert/search (fn [_ctx _opts] six-colbert-candidates)
                    reranker/rerank! (fn [_ctx _opts]
                                       (vec (for [i (range 6)]
                                              {:document-id (str "doc-" i)
                                               :reasoning (str "fit " i)
                                               :fitness-score (- 0.95 (* 0.05 i))})))]
        (tp/maybe-auto-classify-and-set-context (node) ctx))
      (let [event (first (events-of ctx :ontology/task-classified))
            entries (:ranked-candidates event)]
        (is (some? event) "sanity: a classification event landed")
        (is (seq entries) "sanity: the snapshot is non-empty")
        (is (<= (count entries) 5)
            "the snapshot is bounded to the retrieval k (5) even with 6 retrieved")
        (doseq [entry entries]
          (is (not (contains? entry :content))
              "NO :content on any snapshot entry (the CC-21 lesson)")
          (is (not (contains? entry :summary))
              "NO :summary on any snapshot entry")
          (is (empty? (remove allowed-ranked-keys (keys entry)))
              (str "entry carries ONLY the reduced keys; extras: "
                   (pr-str (remove allowed-ranked-keys (keys entry)))))))))
  (testing "deferral event: the same bound holds on the fallback snapshot"
    (with-test-ctx [ctx]
      (with-redefs [colbert/list-indexes fake-list-indexes
                    colbert/search (fn [_ctx _opts] six-colbert-candidates)
                    reranker/rerank! (fn [_ctx _opts] nil)]
        (tp/maybe-auto-classify-and-set-context (node) ctx))
      (let [event (first (events-of ctx :ontology/task-classification-deferred))
            entries (:ranked-candidates event)]
        (is (some? event) "sanity: a deferral event landed")
        (is (seq entries) "sanity: the snapshot is non-empty")
        (is (<= (count entries) 5) "bounded to k on the deferral too")
        (doseq [entry entries]
          (is (empty? (remove allowed-ranked-keys (keys entry)))
              (str "deferral entry carries ONLY the reduced keys; extras: "
                   (pr-str (remove allowed-ranked-keys (keys entry))))))))))

;; =============================================================================
;; RED 4 — the measured-number criterion: N classifications with M
;; deferrals leave exactly M deferral events + (N - M) classified events
;; in the store. The deferral RATE is a count, not an inference.
;; =============================================================================

(deftest deferral-rate-is-a-count-from-the-store
  (testing "5 classifications, 2 deferred → the store holds EXACTLY 2 deferral events and 3 classified events, joinable by tick"
    (with-test-ctx [ctx]
      (let [uncertain-result {:assigned-tree-id nil
                              :confidence 0.0
                              :top-candidates []
                              :ranked-candidates [{:target-id (str (random-uuid))
                                                   :granularity :tree-class
                                                   :rerank-source :colbert-fallback
                                                   :score 0.4}]
                              :reasoning "reranker fell back; deferred"
                              :outcome :uncertain
                              :parent-tree-id nil
                              :rerank-fallback? true}
            matched-result (fn []
                             {:assigned-tree-id (random-uuid)
                              :confidence 0.9
                              :top-candidates []
                              :ranked-candidates [{:target-id (str (random-uuid))
                                                   :granularity :tree-class
                                                   :rerank-source :reranker
                                                   :fitness-score 0.9
                                                   :score 0.8}]
                              :assigned-via :match
                              :reasoning "fit"
                              :outcome :matched
                              :was-fresh-mint? false
                              :parent-tree-id nil
                              :rerank-fallback? false})
            behavioral {:behaviors [] :outcome :novel :rerank-fallback? false}
            outcomes [:defer :assign :defer :assign :assign]
            tick-ids (vec (repeatedly 5 random-uuid))]
        (doseq [[i outcome] (map-indexed vector outcomes)]
          (let [tick-ctx (assoc ctx :tick-id (tick-ids i) :sheet-id (random-uuid))]
            (with-redefs [ontology/classify-task
                          (fn [_ _] (if (= :defer outcome)
                                      uncertain-result
                                      (matched-result)))
                          ontology/classify-behaviors (fn [_ _] behavioral)]
              (tp/maybe-auto-classify-and-set-context (node) tick-ctx))))
        (let [deferred (events-of ctx :ontology/task-classification-deferred)
              classified (events-of ctx :ontology/task-classified)]
          (is (= 2 (count deferred))
              "M deferrals → EXACTLY M deferral events counted from the store")
          (is (= 3 (count classified))
              "N - M assignments → exactly N - M classified events")
          (is (= #{(tick-ids 0) (tick-ids 2)}
                 (into #{} (map :source-tick-id) deferred))
              "the deferral events carry exactly the deferred ticks' identities")
          (is (= #{(tick-ids 1) (tick-ids 3) (tick-ids 4)}
                 (into #{} (map :source-tick-id) classified))
              "the classified events carry exactly the assigned ticks' identities"))))))

(deftest behavioral-only-deferral-is-also-visible
  (testing "structural matched but BEHAVIORAL :uncertain → the wedge withholds assignment (EL-3), and that deferral is ALSO a durable event — its fallback source read from the behavioral candidates, its reasoning naming the behavioral axis"
    (with-test-ctx [ctx]
      (let [matched-structural {:assigned-tree-id (random-uuid)
                                :confidence 0.9
                                :top-candidates []
                                :ranked-candidates [{:target-id (str (random-uuid))
                                                     :granularity :tree-class
                                                     :rerank-source :reranker
                                                     :fitness-score 0.9
                                                     :score 0.8}]
                                :assigned-via :match
                                :reasoning "structural fit"
                                :outcome :matched
                                :was-fresh-mint? false
                                :parent-tree-id nil
                                :rerank-fallback? false}
            uncertain-behavioral {:behaviors [{:behavior-id (random-uuid)
                                               :confidence 0.0
                                               :was-fresh-mint? false
                                               :reasoning ""
                                               :rerank-source :timeout-fallback}]
                                  :outcome :uncertain
                                  :rerank-fallback? true}]
        (with-redefs [ontology/classify-task (fn [_ _] matched-structural)
                      ontology/classify-behaviors (fn [_ _] uncertain-behavioral)]
          (tp/maybe-auto-classify-and-set-context (node) ctx))
        (let [deferred (events-of ctx :ontology/task-classification-deferred)
              event (first deferred)]
          (is (= 0 (count (events-of ctx :ontology/task-classified)))
              "sanity: assignment was withheld (EL-3 behavior unchanged)")
          (is (= 1 (count deferred))
              "the behavioral-only deferral is NOT a silent tick — it leaves an event")
          (is (= :timeout-fallback (:fallback-source event))
              "the fallback source is read from the BEHAVIORAL candidates when the structural axis did not defer")
          (is (re-find #"(?i)behavioral" (:reasoning event))
              "the reasoning names the axis that deferred, not the structural match")
          (is (= (:ranked-candidates matched-structural) (:ranked-candidates event))
              "the structural pre-gate snapshot still rides along (what the decision saw)"))))))

(deftest deferral-records-timeout-fallback-distinctly
  (testing "reranker timed out (RR-1 marker) → the deferral's :fallback-source is :timeout-fallback"
    (with-test-ctx [ctx]
      (with-rerank-fallback (with-meta [] {:rerank-timeout? true})
        (tp/maybe-auto-classify-and-set-context (node) ctx))
      (let [deferred (events-of ctx :ontology/task-classification-deferred)]
        (is (= 1 (count deferred)) "ONE deferral event")
        (is (= :timeout-fallback (:fallback-source (first deferred)))
            "'infra was slow' is recorded distinctly from 'reranker could not rank'")))))
