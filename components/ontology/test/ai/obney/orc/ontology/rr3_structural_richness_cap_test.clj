(ns ai.obney.orc.ontology.rr3-structural-richness-cap-test
  "RR-3 (ADR 0020, decision 4) — STRUCTURAL candidate-richness cap.

   Deterministic surface under test: WHICH candidates carry full richness
   (:avoid-when / :strengths / :weaknesses / :document-metadata) into the
   reranker's prompt, and which are cut to a terse (content + score +
   document-id) form.

   WHY STRUCTURAL AND NOT BY SCORE. The naive design — full richness for the
   top-K candidates by PRE-RERANK ColBERT score — was falsified by /prototype
   BEFORE it was built: replaying the established EL-5 `refactor` regression
   case through the real corpus's pure pre-rerank score puts the KNOWN-WRONG
   force-fit (child/rename-move-symbol) at #1 and the KNOWN-CORRECT answer
   (Code-building, a PARENT) at #9 of 19. A top-K-by-score cut would have fed
   the wrong answer full evidence and starved the right one. Raw ColBERT
   similarity IS the biased signal EL-2/EL-5 exist to correct.

   So the partition is structural, off metadata that ALREADY exists: a
   candidate is a CHILD iff its Living Description body declares
   `:parent-behavior` (the SKOS-broader axis mint-behavioral-subtree stamps).
   The ~12 curated abstract parents have none — nor does any non-behavioral
   candidate — so they are never capped, at any score.

   These tests assert on the candidates the reranker is GIVEN (captured via a
   stub), not on an LLM's ranking choice. The live EL-5 replay (real reranker,
   real corpus) is `development/bench/rr3_richness_shape_probe.clj` +
   `el2_inspect_rate.clj` — see the RR-3 issue's acceptance criteria."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.reranker :as reranker]
            [ai.obney.orc.ontology.core.task-classifier :as tc]
            [ai.obney.orc.colbert.interface :as colbert]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.schemas]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Scaffolding (mirrors el2_grounded_rank_test)
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/rr3-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))
        tenant-id (random-uuid)]
    {:event-store event-store
     :cache cache
     :tenant-id tenant-id
     :event-pubsub ps
     :dscloj-provider :openrouter
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     ::cache-dir cache-dir}))

(defn- stop-context [ctx]
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [e (:event-store ctx)] (es/stop e))
  (when-let [dir (::cache-dir ctx)]
    (let [f (java.io.File. dir)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defmacro with-test-ctx [[sym] & body]
  `(let [~sym (create-context)]
     (try ~@body (finally (stop-context ~sym)))))

(defn- fake-list-indexes [_ctx & _opts]
  [{:index-name "ontology-descriptions"
    :index-id   (random-uuid)
    :created-at "2026-05-28T00:00:00Z"}])

(defn- record-body!
  "Land a Living Description body under :target-type :tree-fingerprint — the
   scope both record-tree-description AND mint-behavioral-subtree actually
   write to (the E3.5 scope gotcha), and the scope fetch-evidence-body reads."
  [ctx target-id body]
  (cp/process-command
    (assoc ctx :command {:command/name :ontology/record-tree-description
                         :command/id (random-uuid)
                         :command/timestamp (time/now)
                         :target-id target-id
                         :body body})))

(defn- principle-entry [m]
  (merge {:trait "t" :confidence 0.8 :evidence-count 3} m))

(defn- body-with
  "A schema-valid description body. `parent` non-nil makes it a CHILD (the
   :parent-behavior SKOS-broader stamp); nil makes it a top-level PARENT."
  [label parent]
  (cond-> {:capabilities [(str label " capability")]
           :strengths [(principle-entry {:trait (str label " strength")
                                         :good-when "some context"
                                         :recommended-pattern "[:sequence ...]"})]
           :weaknesses [(principle-entry {:trait (str label " weakness")
                                          :avoid-when (str "avoid " label " when narrow")
                                          :recommended-alternative "something else"})]
           :representative-uses [(str "use " label)]
           :avoid-when [(str "top-level guard for " label)]
           :summary (str label " summary line.")
           :version 1
           :consolidated-from-event-count 3
           :scope :behavioral-subtree}
    parent (assoc :parent-behavior parent)))

(defn- candidate
  "The normalized ColBERT result shape apply-rerank sees."
  [target-id score]
  {:content (str target-id " summary line.")
   :score score
   :rank 1
   :document-id (str "behavioral-subtree:" target-id)
   :document_metadata {:granularity "behavioral-subtree"
                       :target-id (str target-id)
                       :confidence 0.7
                       :last-update "2026"}})

(defn- captured-rerank
  "[capture-atom stub] — the stub records the candidates it is handed and
   echoes a valid delta ranking so the JOIN downstream still succeeds."
  []
  (let [capture (atom nil)]
    [capture
     (fn [_ctx {:keys [candidates]}]
       (reset! capture candidates)
       (mapv (fn [c] {:document-id (:document-id c)
                      :reasoning "stub"
                      :fitness-score 0.5})
             candidates))]))

(defn- full-richness?
  "A candidate carries FULL richness when its body evidence rode along."
  [c]
  (boolean (and (seq (:avoid-when c))
                (seq (:strengths c))
                (seq (:weaknesses c)))))

(defn- run-capture!
  "Seed `bodies` ({target-id -> body}), hand `candidates` to search-descriptions
   through a stubbed ColBERT + stubbed reranker, and return the candidate
   payload the reranker was GIVEN."
  [ctx bodies candidates]
  (doseq [[tid body] bodies] (record-body! ctx tid body))
  (Thread/sleep 100)
  (let [[capture stub] (captured-rerank)]
    (with-redefs [colbert/list-indexes fake-list-indexes
                  colbert/search (fn [_ _] candidates)
                  reranker/rerank! stub]
      (ontology/search-descriptions ctx {:query "refactor the order service"
                                         :granularity :behavioral-subtree
                                         :rerank-with-intent tc/behavioral-classifier-intent
                                         :k 6})
      @capture)))

;; =============================================================================
;; RED #1 — PARENTS ALWAYS keep full richness, whatever their pre-rerank score.
;;          Acceptance criterion 1. The parents here are deliberately given the
;;          WORST scores in the set, so a score-based cut would starve them.
;; =============================================================================

(deftest parents-keep-full-richness-at-any-pre-rerank-score
  (testing "Every parent (no :parent-behavior) is fully enriched even at the bottom of the ranking"
    (with-test-ctx [ctx]
      (let [parent-ids (vec (repeatedly 12 random-uuid))
            child-ids (vec (repeatedly 8 random-uuid))
            bodies (merge
                     (into {} (map-indexed (fn [i t] [t (body-with (str "parent-" i) nil)]) parent-ids))
                     (into {} (map-indexed (fn [i t] [t (body-with (str "child-" i) (first parent-ids))]) child-ids)))
            ;; Children score 0.90 .. 0.83 ; parents score 0.30 .. 0.19.
            ;; Under the FALSIFIED score-based design every parent is capped.
            candidates (vec (concat
                              (map-indexed (fn [i t] (candidate t (- 0.90 (* 0.01 i)))) child-ids)
                              (map-indexed (fn [i t] (candidate t (- 0.30 (* 0.01 i)))) parent-ids)))
            given (run-capture! ctx bodies candidates)
            by-doc (into {} (map (juxt :document-id identity)) given)]
        (is (= (count candidates) (count given))
            "Sanity: the cap never drops a candidate — every one is still ranked")
        ;; Non-vacuity guard: the cap must actually be ACTIVE in this very
        ;; scenario, otherwise "parents are full" would pass trivially on a
        ;; build where nothing is ever capped.
        (is (= (- (count child-ids) tc/default-classify-behaviors-top-n)
               (count (remove full-richness? given)))
            "the cap IS active here — every child below the top-K is terse")
        (doseq [t parent-ids]
          (is (full-richness? (get by-doc (str "behavioral-subtree:" t)))
              (str "parent " t " must carry full richness despite its bottom-of-the-pack score")))))))

;; =============================================================================
;; RED #2 — Among CHILDREN, exactly top-K by pre-rerank score keep full
;;          richness; every other child is cut to content + score + id.
;;          Acceptance criterion 2. K is classify-behaviors' own :top-n.
;; =============================================================================

(deftest only-top-k-children-keep-full-richness
  (testing "Exactly K highest-scoring children keep full richness; the rest are terse"
    (with-test-ctx [ctx]
      (let [k tc/default-classify-behaviors-top-n
            parent-id (random-uuid)
            child-ids (vec (repeatedly 8 random-uuid))
            bodies (merge {parent-id (body-with "parent" nil)}
                          (into {} (map-indexed (fn [i t] [t (body-with (str "child-" i) parent-id)]) child-ids)))
            ;; child-0 highest .. child-7 lowest
            candidates (vec (concat [(candidate parent-id 0.10)]
                                    (map-indexed (fn [i t] (candidate t (- 0.90 (* 0.05 i)))) child-ids)))
            given (run-capture! ctx bodies candidates)
            by-doc (into {} (map (juxt :document-id identity)) given)
            child-doc (fn [t] (get by-doc (str "behavioral-subtree:" t)))
            rich (filterv #(full-richness? (child-doc %)) child-ids)]
        (is (= k (count rich))
            (str "exactly " k " children keep full richness (classify-behaviors' :top-n)"))
        (is (= (set (take k child-ids)) (set rich))
            "and they are the K HIGHEST-scoring children, not an arbitrary K")
        (doseq [t (drop k child-ids)]
          (let [c (child-doc t)]
            (is (= #{:document-id :content :score} (set (keys c)))
                (str "capped child " t " carries ONLY content-summary + score + id"))
            (is (number? (:score c)) "the capped child still carries its score")
            (is (seq (:content c)) "the capped child still carries its content summary")))
        (is (= (count candidates) (count given))
            "the cap shrinks per-candidate DETAIL, never the candidate count")))))

;; =============================================================================
;; RED #3 — THE EL-5 REPLAY (the case the naive design failed).
;;          Real seed corpus (behavioral-subtrees.edn +
;;          behavioral-subtree-children.edn, emitted through the real
;;          seed_baseline path), real derived child identities, and the
;;          PRE-RERANK ordering the /prototype actually measured on the
;;          `refactor` task: force-fit child/rename-move-symbol #1,
;;          Code-building (the correct PARENT answer) far down the list.
;;          Acceptance: Code-building carries full richness anyway.
;; =============================================================================

(def ^:private code-building-id
  "The Code-building abstract PARENT — the known-correct EL-5 answer."
  #uuid "bf47c816-2833-320e-9fbd-6ae109275ab0")

(def ^:private rename-move-symbol-id
  "child/rename-move-symbol — the known force-fit the pre-rerank score puts #1."
  #uuid "9880798a-8487-3a24-93e4-b59c5ae5d789")

(defn- derived-child-id
  "mint-behavioral-subtree's identity rule, recomputed here so the test keys
   off the REAL corpus's derived child identities rather than hard-coded ids."
  [name parent-behavior]
  (java.util.UUID/nameUUIDFromBytes
    (.getBytes (str "mint:" name ":" parent-behavior) "UTF-8")))

(deftest el5-refactor-replay-code-building-keeps-full-richness
  (testing "On the measured EL-5 pre-rerank ordering, the correct PARENT answer is never capped"
    (with-test-ctx [ctx]
      (let [{:keys [behavioral-subtrees behavioral-subtree-children]} (ontology/baseline-seeds)
            parent-ids (mapv :target-id behavioral-subtrees)
            child-entries (mapv (fn [{:keys [name parent-behavior body]}]
                                  {:target-id (derived-child-id name parent-behavior)
                                   :name name
                                   :body (cond-> (assoc body :scope :behavioral-subtree)
                                           parent-behavior (assoc :parent-behavior parent-behavior))})
                                behavioral-subtree-children)]
        ;; Guard the fixture against corpus drift: these are the two ids the
        ;; /prototype measured on, derived from the shipped seeds.
        (is (contains? (set parent-ids) code-building-id)
            "Code-building is still one of the shipped abstract PARENTS")
        (is (= rename-move-symbol-id
               (:target-id (first (filter #(= "rename-move-symbol" (:name %)) child-entries))))
            "rename-move-symbol still derives to the id the /prototype measured")
        (let [bodies (merge
                       (into {} (map (juxt :target-id :body)) behavioral-subtrees)
                       (into {} (map (juxt :target-id :body)) child-entries))
              ;; The measured pre-rerank shape: the force-fit CHILD on top,
              ;; every other child above the correct PARENT, Code-building
              ;; buried well below the top-K cut the naive design would have
              ;; applied.
              ordered (vec (concat [rename-move-symbol-id]
                                   (remove #{rename-move-symbol-id} (map :target-id child-entries))
                                   (remove #{code-building-id} parent-ids)
                                   [code-building-id]))
              candidates (vec (map-indexed (fn [i t] (candidate t (- 0.95 (* 0.01 i)))) ordered))
              given (run-capture! ctx bodies candidates)
              by-doc (into {} (map (juxt :document-id identity)) given)
              cb (get by-doc (str "behavioral-subtree:" code-building-id))
              ff (get by-doc (str "behavioral-subtree:" rename-move-symbol-id))
              cb-rank (inc (count (take-while #(not= code-building-id %) ordered)))]
          (is (> cb-rank tc/default-classify-behaviors-top-n)
              "Fixture integrity: Code-building really is BELOW the top-K cut a score-based design would use")
          (is (full-richness? cb)
              (str "Code-building (correct answer, PARENT) keeps full richness at pre-rerank rank "
                   cb-rank " — the exact case the score-based design failed"))
          (is (some? ff) "the force-fit child is still in the candidate set (nothing is dropped)")
          (is (= (count ordered) (count given))
              "every seeded behavior is still handed to the reranker")
          ;; Non-vacuity guard: on THIS corpus the cap really does bite —
          ;; the children below the top-K are terse. Without it, "Code-building
          ;; keeps full richness" would pass on a build that caps nothing.
          (is (= (- (count child-entries) tc/default-classify-behaviors-top-n)
                 (count (remove full-richness? given)))
              "the cap IS active on the real seed corpus — the sub-top-K children are terse"))))))

;; =============================================================================
;; RED #4 — The cap is INPUT-ONLY. A capped candidate must still come back out
;;          of search-descriptions with its full original fields, because the
;;          JOIN + EL-5 domain penalty key onto the FULLY enriched set. If the
;;          shaped payload leaked into the join, the deterministic penalty
;;          would silently lose the :avoid-when of every capped candidate.
;; =============================================================================

(deftest cap-is-input-only-results-keep-full-fields
  (testing "Capped candidates still return with :document-metadata and the join intact"
    (with-test-ctx [ctx]
      (let [parent-id (random-uuid)
            child-ids (vec (repeatedly 6 random-uuid))
            bodies (merge {parent-id (body-with "parent" nil)}
                          (into {} (map-indexed (fn [i t] [t (body-with (str "child-" i) parent-id)]) child-ids)))
            candidates (vec (concat [(candidate parent-id 0.10)]
                                    (map-indexed (fn [i t] (candidate t (- 0.90 (* 0.05 i)))) child-ids)))]
        (doseq [[tid body] bodies] (record-body! ctx tid body))
        (Thread/sleep 100)
        (with-redefs [colbert/list-indexes fake-list-indexes
                      colbert/search (fn [_ _] candidates)
                      reranker/rerank! (fn [_ {:keys [candidates]}]
                                         (mapv (fn [c] {:document-id (:document-id c)
                                                        :reasoning "r"
                                                        :fitness-score 0.9})
                                               candidates))]
          (let [results (ontology/search-descriptions ctx
                          {:query "refactor"
                           :granularity :behavioral-subtree
                           :rerank-with-intent tc/behavioral-classifier-intent
                           :k 7})
                lowest (first (filter #(= (str "behavioral-subtree:" (last child-ids))
                                          (:document-id %))
                                      results))]
            (is (= 7 (count results)) "all candidates survive the join")
            (is (some? lowest) "the LOWEST-scoring (capped) child still joins back")
            (is (some? (:document-metadata lowest))
                ":document-metadata is restored on the way out — the cap never leaked into the join")
            (is (seq (:avoid-when lowest))
                "a capped candidate's :avoid-when is still available downstream (EL-5's penalty needs it)")
            (is (= :reranker (:rerank-source lowest)))
            (is (nil? (:ai.obney.orc.ontology.interface/parent-behavior lowest))
                "RR-3's internal structural marker never leaks to callers")))))))

;; =============================================================================
;; RED #5 — SCOPE GUARD. The cap must only touch the behavioral CHILD axis.
;;          A non-behavioral candidate (tree-class / tree-fingerprint /
;;          node-type) has no :parent-behavior and must never be capped, no
;;          matter how low it scores — those retrieval paths (EL-1a, classify-
;;          task) must be bit-for-bit unaffected by this slice.
;; =============================================================================

(deftest non-behavioral-candidates-are-never-capped
  (testing "tree-class / tree-fingerprint candidates keep full richness at any score"
    (with-test-ctx [ctx]
      (let [structural-ids (vec (repeatedly 6 random-uuid))
            bodies (into {} (map-indexed
                              (fn [i t] [t (dissoc (body-with (str "tree-class-" i) nil) :scope)])
                              structural-ids))
            candidates (vec (map-indexed
                              (fn [i t]
                                (-> (candidate t (- 0.90 (* 0.10 i)))
                                    (assoc-in [:document_metadata :granularity] "tree-class")))
                              structural-ids))]
        (doseq [[tid body] bodies] (record-body! ctx tid body))
        (Thread/sleep 100)
        (let [[capture stub] (captured-rerank)
              given (with-redefs [colbert/list-indexes fake-list-indexes
                                  colbert/search (fn [_ _] candidates)
                                  reranker/rerank! stub]
                      (ontology/search-descriptions ctx {:query "classify this task"
                                                         :granularity :tree-class
                                                         :rerank-with-intent "classify"
                                                         :k 6})
                      @capture)]
          (is (= (count candidates) (count given)))
          (doseq [c given]
            (is (full-richness? c)
                "a structural (non-behavioral) candidate is never capped, whatever its score")))))))
