(ns ai.obney.orc.ontology.el2-grounded-rank-test
  "EL-2 (ADR 0015, emergence loop — grounded domain rank).

   Deterministic surface under test: the PAYLOAD ASSEMBLY in apply-rerank.
   Does search-descriptions enrich each candidate map passed to the reranker
   with that candidate's body evidence (:avoid-when + compact strengths /
   weaknesses), fetched from the RIGHT read-model scope?

   We assert on the candidates the reranker is GIVEN (captured via a stub),
   NOT on the LLM's ranking choice (that is the live verify's job — the
   reranker is non-deterministic and shape-biased; see the EL-5 verdict).

   The load-bearing scope detail (the E3.5 wrong-scope trap): behavioral and
   minted tree-class bodies are recorded under :target-type :tree-fingerprint
   (mint-behavioral-subtree / record-tree-description), even though the
   candidate's :document-metadata :granularity reads :behavioral-subtree.
   A naive get-description keyed on the candidate's stated granularity returns
   nil and ships a no-op. The :behavioral-scope-mismatch test below proves the
   enrichment fetches from the right scope."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.reranker :as reranker]
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
;; Scaffolding (mirrors rerank_failure_surfacing_test)
;; =============================================================================

(defn- create-context []
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        cache-dir (str "/tmp/el2-test-" (random-uuid))
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
  (when-let [es (:event-store ctx)] (es/stop es))
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

(defmacro with-discoverable-index [& body]
  `(with-redefs [colbert/list-indexes fake-list-indexes]
     ~@body))

;; Seed a Living Description body under :target-type :tree-fingerprint — the
;; scope where mint-behavioral-subtree + record-tree-description actually land
;; bodies (regardless of the body's logical :scope). This is the read-model
;; substrate get-description reads back.
(defn- record-body! [ctx target-id body]
  (cp/process-command
    (assoc ctx :command {:command/name :ontology/record-tree-description
                         :command/id (random-uuid)
                         :command/timestamp (time/now)
                         :target-id target-id
                         :body body})))

(defn- principle-entry [m]
  (merge {:trait "t" :confidence 0.8 :evidence-count 3} m))

(def ^:private guarded-body
  "A body whose :avoid-when (top-level vector) + per-weakness :avoid-when guards
   are the domain signal the reranker must read."
  {:capabilities ["renames a symbol exhaustively across files"]
   :strengths [(principle-entry {:trait "enumerate every reference before editing"
                                 :good-when "symbol referenced in many files"
                                 :recommended-pattern "[:sequence ...]"})]
   :weaknesses [(principle-entry {:trait "stops at the first file"
                                  :avoid-when "the symbol is referenced beyond the file that defines it"
                                  :recommended-alternative "enumerate repo-wide first"})]
   :representative-uses ["rename calc to compute-tax"]
   :avoid-when ["the task changes behavior or adds functionality — that is code-building, not a rename"
                "the change is a data reshape — that is the Transformation behavior"]
   :summary "Rename-move-symbol: behavior-preserving cross-file identity refactor."
   :version 1
   :consolidated-from-event-count 5})

(defn- captured-rerank
  "Returns [capture-atom rerank-stub]. The stub records the :candidates it is
   given and returns a valid delta ranking echoing each candidate's id."
  []
  (let [capture (atom nil)]
    [capture
     (fn [_ctx {:keys [candidates]}]
       (reset! capture candidates)
       (mapv (fn [c] {:document-id (:document-id c)
                      :reasoning "stub"
                      :fitness-score 0.5})
             candidates))]))

;; =============================================================================
;; RED #1 — apply-rerank enriches each candidate with its body :avoid-when
;;          (body recorded at the candidate's own granularity)
;; =============================================================================

(deftest apply-rerank-enriches-candidate-with-avoid-when
  (testing "Each candidate passed to rerank! carries its body's :avoid-when + strengths/weaknesses"
    (with-test-ctx [ctx]
      (let [tid (random-uuid)]
        (record-body! ctx tid guarded-body)
        (Thread/sleep 50)
        (let [[capture stub] (captured-rerank)
              candidates [{:content (:summary guarded-body)
                           :score 0.9 :rank 1
                           :document-id (str "tf::" tid)
                           :document_metadata {:granularity "tree-fingerprint"
                                               :target-id (str tid)
                                               :confidence 0.7 :last-update "2026"}}]]
          (with-discoverable-index
           (with-redefs [colbert/search (fn [_ _] candidates)
                         reranker/rerank! stub]
             (ontology/search-descriptions ctx {:query "rename calc" :rerank-with-intent "y" :k 3})
             (let [given (first @capture)]
               (is (some? given) "Sanity: a candidate reached the reranker")
               (is (every? (set (:avoid-when given)) (:avoid-when guarded-body))
                   "candidate's :avoid-when contains every body top-level domain guard")
               (is (some #(= "the symbol is referenced beyond the file that defines it"
                             (:avoid-when %))
                         (:weaknesses given))
                   "per-weakness :avoid-when guards are present in the enriched payload")
               (is (contains? (set (:avoid-when given))
                              "the symbol is referenced beyond the file that defines it")
                   "per-weakness guards are also surfaced into the top-level :avoid-when (one place to read)")
               (is (seq (:strengths given))
                   "compact strengths are attached so the reranker sees good-when fit too")))))))))

;; =============================================================================
;; RED #2 — THE SCOPE GOTCHA (E3.5): body recorded under :tree-fingerprint,
;;          candidate metadata says :behavioral-subtree. Enrichment must STILL
;;          find the :avoid-when (right-scope fetch), not ship nil.
;; =============================================================================

(deftest apply-rerank-enriches-behavioral-candidate-from-tree-fingerprint-scope
  (testing "A :behavioral-subtree candidate whose body lives under :tree-fingerprint is still enriched"
    (with-test-ctx [ctx]
      (let [tid (random-uuid)]
        ;; mint-behavioral-subtree records the body under :tree-fingerprint.
        (record-body! ctx tid guarded-body)
        (Thread/sleep 50)
        ;; Confirm the trap is real: the body is NOT at :behavioral-subtree.
        (is (nil? (ontology/get-description ctx :behavioral-subtree tid))
            "Trap confirmed: a naive :behavioral-subtree fetch returns nil")
        (is (some? (ontology/get-description ctx :tree-fingerprint tid))
            "The body actually lives under :tree-fingerprint")
        (let [[capture stub] (captured-rerank)
              candidates [{:content (:summary guarded-body)
                           :score 0.95 :rank 1
                           :document-id (str ":behavioral-subtree:" tid)
                           ;; The metadata granularity is the WRONG scope for the body.
                           :document_metadata {:granularity "behavioral-subtree"
                                               :target-id (str tid)
                                               :confidence 0.7 :last-update "2026"}}]]
          (with-discoverable-index
           (with-redefs [colbert/search (fn [_ _] candidates)
                         reranker/rerank! stub]
             (ontology/search-descriptions ctx {:query "refactor" :rerank-with-intent "y" :k 3})
             (let [given (first @capture)]
               (is (seq (:avoid-when given))
                   "Enrichment fell back to :tree-fingerprint and found the :avoid-when (no no-op nil ship)")
               (is (every? (set (:avoid-when given)) (:avoid-when guarded-body))
                   "the body's domain guards are all present despite the metadata granularity mismatch")))))))))

;; =============================================================================
;; RED #3 — Output contract UNCHANGED: enrichment does not leak into the
;;          search-descriptions return shape's rerank delta keys, and the
;;          joined results still carry the canonical fields.
;; =============================================================================

(deftest apply-rerank-output-contract-unchanged
  (testing "search-descriptions results still carry :rerank-source/:reasoning/:fitness-score after enrichment"
    (with-test-ctx [ctx]
      (let [tid (random-uuid)]
        (record-body! ctx tid guarded-body)
        (Thread/sleep 50)
        (let [candidates [{:content (:summary guarded-body)
                           :score 0.9 :rank 1
                           :document-id (str "tf::" tid)
                           :document_metadata {:granularity "tree-fingerprint"
                                               :target-id (str tid)
                                               :confidence 0.7 :last-update "2026"}}]]
          (with-discoverable-index
           (with-redefs [colbert/search (fn [_ _] candidates)
                         reranker/rerank! (fn [_ {:keys [candidates]}]
                                            (mapv (fn [c] {:document-id (:document-id c)
                                                           :reasoning "r" :fitness-score 0.9})
                                                  candidates))]
             (let [results (ontology/search-descriptions ctx
                             {:query "rename calc" :rerank-with-intent "y" :k 3})
                   r (first results)]
               (is (= 1 (count results)))
               (is (= :reranker (:rerank-source r)))
               (is (number? (:fitness-score r)))
               (is (string? (:reasoning r)))
               (is (= (str "tf::" tid) (:document-id r))
                   "join-back on :document-id is intact")))))))))
