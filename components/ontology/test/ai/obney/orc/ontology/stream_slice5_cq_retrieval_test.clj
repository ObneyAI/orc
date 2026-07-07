(ns ai.obney.orc.ontology.stream-slice5-cq-retrieval-test
  "STREAM Slice 5 — CQ / semantic-retrieval streaming (bounded top-K cosine).

   Eliminates the HEAVIEST resident set: `semantic-search-concepts` and the
   `has-embeddings?` existence check used to materialize the ENTIRE
   concept-embedding VECTOR map for an in-memory cosine scan. The fix is a
   STREAMING bounded top-K cosine over `concept-stream/reduce-concept-embeddings`
   — bounded MEMORY *and* BYTE-INVARIANT (identical top-K ⇒ identical CQ evidence
   ⇒ identical verdicts).

   Byte-invariance is the GATE. These tracers pin:
     1. streamed `semantic-search-concepts` top-K == the full-scan
        (`search-concepts-by-embedding` over `get-all-concept-embeddings`) —
        same K, same order, same similarities, same tie-break (Part A).
     2. the bounded top-K fold never holds more than `limit` entries and
        retains NO embedding vector (Part A boundedness).
     3. `concept-stream/any-concept-embedding?` == `(seq (get-all-concept-embeddings ctx))`
        as a boolean, without materializing the vector map (Part B).
     4. the streamed CQ concept load (reduce-concepts + :project-fn) yields a
        byte-identical concept set to `(get-concepts ctx {})` filtered to the
        ontology-id, and produces an IDENTICAL layer-1 verdict (Part C)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.embedding :as embedding]
            [ai.obney.orc.ontology.core.static-ontology :as static]
            [ai.obney.orc.ontology.core.retrieval :as retrieval]
            [ai.obney.orc.ontology.core.concept-stream :as cs]
            [ai.obney.orc.ontology.core.cq-runner :as cqr]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.test-helpers :as h]))

;; =============================================================================
;; Fixture landing helpers
;; =============================================================================

(defn- create-concept! [ctx body]
  (h/run-and-apply! ctx (fn [c] (cmd/ontology-create-concept (assoc c :command body)))))

(defn- land-embedding!
  "Append a :ontology/concept-embedded event with a KNOWN vector, tagged EXACTLY
   as the real embed-concept command tags it: only [:concept id], ontology-id in
   the body (so `reduce-concept-embeddings` body-filters by ontology-id, and
   `get-all-concept-embeddings {:ontology-id id}` filters by the stored :ontology-id)."
  [ctx ont-id uri vector]
  (es/append (:event-store ctx)
             {:tenant-id (:tenant-id ctx)
              :events [(es/->event
                        {:type :ontology/concept-embedded
                         :tags #{[:concept (random-uuid)]}
                         :body {:uri uri
                                :ontology-id ont-id
                                :text-embedded (str "text for " uri)
                                :field-source "label+description"
                                :embedding vector
                                :model-id "test-model"
                                :embedded-at "2026-01-01T00:00:00Z"}})]}))

;; Six concept vectors (dim 4). Cosine vs the query [1 0 0 0] = first-component /
;; magnitude — chosen to give SIX DISTINCT similarities so top-K order is fully
;; determined (no ties → byte-identity is a total order, not tie-break-dependent).
(def ^:private query-vec [1.0 0.0 0.0 0.0])
(def ^:private concept-vecs
  {"concept:alpha"   [1.0 0.0 0.0 0.0]     ;; sim 1.0
   "concept:beta"    [0.8 0.6 0.0 0.0]     ;; sim 0.8
   "concept:gamma"   [0.6 0.8 0.0 0.0]     ;; sim 0.6
   "concept:delta"   [0.5 0.5 0.5 0.5]     ;; sim 0.5
   "concept:epsilon" [0.1 0.9 0.1 0.1]     ;; sim ~0.109
   "concept:zeta"    [0.0 1.0 0.0 0.0]})   ;; sim 0.0

(defn- seed-embeddings!
  "Land the 6 known embeddings under `ont-id`. Returns ont-id."
  [ctx ont-id]
  (doseq [[uri v] concept-vecs]
    (land-embedding! ctx ont-id uri v))
  ont-id)

;; The EXACT pre-conversion inner pipeline — the ORACLE the streamed version must
;; equal byte-for-byte (full-map materialize + `search-concepts-by-embedding`).
(defn- full-scan-oracle [ctx query-embedding filter-opts limit min-similarity]
  (let [ce (rm/get-all-concept-embeddings ctx (when (seq filter-opts) filter-opts))]
    (when (and query-embedding (seq ce))
      (->> (embedding/search-concepts-by-embedding
            query-embedding ce :limit limit :min-similarity min-similarity)
           (map (fn [{:keys [uri similarity]}]
                  (let [concept (static/get-concept-by-uri uri)]
                    {:uri uri :similarity similarity
                     :label (:label concept) :description (:description concept)
                     :scope (:scope concept)})))
           vec))))

;; =============================================================================
;; Tracer 1 — byte-invariance: streamed top-K == full-scan top-K (Part A)
;; =============================================================================

(deftest streamed-topk-equals-full-scan-ontology-id
  (testing "streamed semantic-search-concepts (:ontology-id) == the full-map scan:
            same ordered [{:uri :similarity ...}], same K, same similarities, same order"
    (h/with-test-context [ctx]
      (with-redefs [embedding/embed-text (fn [_ & _] query-vec)]
        (let [ont-id (seed-embeddings! ctx (random-uuid))]
          (doseq [[limit min-sim] [[3 0.5] [10 0.0] [2 0.6] [10 0.99]]]
            (let [oracle   (full-scan-oracle ctx query-vec {:ontology-id ont-id} limit min-sim)
                  streamed (retrieval/semantic-search-concepts
                            ctx "query text" :ontology-id ont-id
                            :limit limit :min-similarity min-sim)]
              (is (= oracle streamed)
                  (str "streamed top-K must be BYTE-IDENTICAL to the full scan "
                       "(limit " limit ", min-sim " min-sim "): oracle=" (pr-str oracle)
                       " streamed=" (pr-str streamed)))
              ;; explicit order + similarity guard (not just set equality)
              (is (= (mapv :uri oracle) (mapv :uri streamed)) "same URI ORDER")
              (is (= (mapv :similarity oracle) (mapv :similarity streamed))
                  "same similarity VALUES in the same positions"))))))))

(deftest streamed-topk-equals-full-scan-ontology-ids-union
  (testing "streamed (:ontology-ids union across 2 sections) == full-map scan for the union"
    (h/with-test-context [ctx]
      (with-redefs [embedding/embed-text (fn [_ & _] query-vec)]
        (let [ont-a (random-uuid)
              ont-b (random-uuid)]
          ;; alpha/beta/gamma under A, delta/epsilon/zeta under B
          (doseq [[uri v] (select-keys concept-vecs ["concept:alpha" "concept:beta" "concept:gamma"])]
            (land-embedding! ctx ont-a uri v))
          (doseq [[uri v] (select-keys concept-vecs ["concept:delta" "concept:epsilon" "concept:zeta"])]
            (land-embedding! ctx ont-b uri v))
          (let [ids      #{ont-a ont-b}
                oracle   (full-scan-oracle ctx query-vec {:ontology-ids ids} 4 0.0)
                streamed (retrieval/semantic-search-concepts
                          ctx "q" :ontology-ids ids :limit 4 :min-similarity 0.0)]
            (is (= oracle streamed) "union top-K byte-identical to full-scan union")
            (is (= ["concept:alpha" "concept:beta" "concept:gamma" "concept:delta"]
                   (mapv :uri streamed))
                "cross-section top-4 by similarity, correctly ordered")))))))

(deftest streamed-scoping-excludes-other-ontology
  (testing "streaming by :ontology-id excludes another ontology's embeddings (scope honored)"
    (h/with-test-context [ctx]
      (with-redefs [embedding/embed-text (fn [_ & _] query-vec)]
        (let [ont-a (seed-embeddings! ctx (random-uuid))
              ont-b (random-uuid)]
          (land-embedding! ctx ont-b "concept:intruder" [1.0 0.0 0.0 0.0]) ;; sim 1.0 but WRONG scope
          (let [streamed (retrieval/semantic-search-concepts
                          ctx "q" :ontology-id ont-a :limit 10 :min-similarity 0.0)]
            (is (not (some #(= "concept:intruder" (:uri %)) streamed))
                "an embedding under ont-b must NOT appear in an ont-a scoped search")
            (is (= (full-scan-oracle ctx query-vec {:ontology-id ont-a} 10 0.0) streamed)
                "still byte-identical to the scoped full-scan")))))))

(deftest streamed-nil-vs-empty-contract
  (testing "nil when the scope has NO embeddings (mirrors (seq concept-embeddings)); vec otherwise"
    (h/with-test-context [ctx]
      (with-redefs [embedding/embed-text (fn [_ & _] query-vec)]
        (seed-embeddings! ctx (random-uuid))
        (let [fresh (random-uuid)]
          (is (nil? (retrieval/semantic-search-concepts ctx "q" :ontology-id fresh :min-similarity 0.0))
              "no embeddings for a fresh ontology-id → nil, exactly like the full scan")
          (is (= (full-scan-oracle ctx query-vec {:ontology-id fresh} 10 0.0)
                 (retrieval/semantic-search-concepts ctx "q" :ontology-id fresh :min-similarity 0.0))
              "nil-vs-[] contract matches the full scan"))))))

;; =============================================================================
;; Tracer 2 — the bounded top-K fold is bounded + vector-free (Part A)
;; =============================================================================

(deftest top-k-conj-is-bounded-and-vector-free
  (testing "the top-K fold step never holds more than `limit` entries and keeps
            ONLY {:uri :similarity} (no embedding vector rides along)"
    (let [limit 5
          entries (shuffle (mapv (fn [i] {:uri (str "c" i) :similarity (double i)})
                                 (range 50)))
          max-seen (atom 0)
          final (reduce (fn [acc e]
                          (let [acc' (#'retrieval/top-k-conj limit acc e)]
                            (swap! max-seen max (count acc'))
                            acc'))
                        (sorted-set-by @#'retrieval/top-k-similarity-comparator)
                        entries)]
      (is (<= @max-seen limit)
          (str "accumulator NEVER exceeds " limit " entries (bounded); peak was " @max-seen))
      (is (= limit (count final)) "final holds exactly limit entries")
      (is (= #{45.0 46.0 47.0 48.0 49.0} (set (map :similarity final)))
          "keeps the top-5 by similarity — the weakest were evicted")
      (is (every? #(= #{:uri :similarity} (set (keys %))) final)
          "every retained entry carries ONLY :uri/:similarity — NO :embedding vector"))))

(deftest streamed-result-carries-no-vector
  (testing "the enriched streamed results never surface a raw embedding vector"
    (h/with-test-context [ctx]
      (with-redefs [embedding/embed-text (fn [_ & _] query-vec)]
        (let [ont-id (seed-embeddings! ctx (random-uuid))
              out (retrieval/semantic-search-concepts ctx "q" :ontology-id ont-id
                                                      :limit 10 :min-similarity 0.0)]
          (is (seq out))
          (is (not-any? :embedding out) "no :embedding key on any result (vector discarded)"))))))

;; =============================================================================
;; Tracer 3 — existence check without materializing the vector map (Part B)
;; =============================================================================

(deftest any-concept-embedding-matches-seq-of-get-all
  (testing "any-concept-embedding? == (seq (get-all-concept-embeddings ctx)) as a boolean"
    (h/with-test-context [ctx]
      (is (false? (cs/any-concept-embedding? ctx))
          "empty store → false")
      (is (= (boolean (seq (rm/get-all-concept-embeddings ctx)))
             (cs/any-concept-embedding? ctx))
          "matches the materializing check on an empty store")
      (seed-embeddings! ctx (random-uuid))
      (is (true? (cs/any-concept-embedding? ctx))
          "after embeddings land → true")
      (is (= (boolean (seq (rm/get-all-concept-embeddings ctx)))
             (cs/any-concept-embedding? ctx))
          "matches the materializing check once embeddings exist"))))

;; =============================================================================
;; Tracer 4 — CQ concept load: streamed set byte-identical + identical verdict (Part C)
;; =============================================================================

(def ^:private cq-project-fn ontology/cq-concept-projection)

(defn- seed-cq-concepts! [ctx ont-id]
  (create-concept! ctx {:ontology-id ont-id :uri "concept:role/director" :label "Director"
                        :description "a director" :scope :custom})
  (create-concept! ctx {:ontology-id ont-id :uri "concept:award/oscar" :label "Academy Award"
                        :description "an award" :scope :custom
                        :attributes {:top-skills ["Directing" "Editing"] :job-zone 5}})
  (create-concept! ctx {:ontology-id ont-id :uri "concept:genre/drama" :label "Drama"
                        :description "a genre" :scope :custom})
  ont-id)

(deftest streamed-cq-concept-set-is-byte-identical
  (testing "reduce-concepts + :project-fn == (get-concepts ctx {}) filtered to the
            ontology-id, projected to the CQ read-fields (single-ontology)"
    (h/with-test-context [ctx]
      (let [ont-id (seed-cq-concepts! ctx (random-uuid))
            via-map    (->> (rm/get-concepts ctx {})
                            (filter #(= ont-id (:ontology-id %)))
                            (map cq-project-fn)
                            set)
            via-stream (set (cs/reduce-concepts ctx ont-id conj []
                                                {:project-fn cq-project-fn}))]
        (is (= 3 (count via-stream)) "all 3 concepts folded")
        (is (= via-map via-stream)
            "streamed projected concept set == map-version projected concept set")
        ;; the heavy :attributes fact survives the projection (CQ evidence needs it)
        (is (some #(seq (:attributes %)) via-stream)
            "the attribute-borne facts are preserved for the CQ evidence layer")))))

(deftest streamed-cq-concept-load-produces-identical-layer1-verdict
  (testing "layer-1-verdict is IDENTICAL whether get-concepts-fn is the map version
            or the streamed (reduce-concepts + project-fn) version"
    (h/with-test-context [ctx]
      (let [ont-id (seed-cq-concepts! ctx (random-uuid))
            map-fn    (fn [_opts] (rm/get-concepts ctx {}))
            stream-fn (fn [_opts] (cs/reduce-concepts ctx ont-id conj []
                                                      {:project-fn cq-project-fn}))]
        (doseq [term ["director" "oscar" "wombat"]]
          (let [v-map    (cqr/layer-1-verdict {:ontology-id ont-id :term term :get-concepts-fn map-fn})
                v-stream (cqr/layer-1-verdict {:ontology-id ont-id :term term :get-concepts-fn stream-fn})]
            (is (= (:verdict v-map) (:verdict v-stream))
                (str "verdict identical for term '" term "'"))
            (is (= (set (:evidence-uris v-map)) (set (:evidence-uris v-stream)))
                (str "evidence-uris identical for term '" term "'"))))))))
