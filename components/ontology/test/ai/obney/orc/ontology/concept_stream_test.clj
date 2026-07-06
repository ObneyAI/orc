(ns ai.obney.orc.ontology.concept-stream-test
  "STREAM Slice 1 — TDD for the `concept-stream` streaming-read foundation.

   Proves the two primitives that let a whole-graph op run in bounded heap by
   reducing DIRECTLY over the streaming `es/read` event log (never
   `(vals (rmp/project ...))`, which materializes the whole map + the vector set):

     - `reduce-concepts`           — folds the REGISTERED :ontology/concepts
                                     reducer over a tag-scoped, windowed es/read
                                     stream; state is byte-identical to
                                     `(rmp/project :ontology/concepts {:tags ...})`.
                                     Supports optional field-projection so a
                                     whole-graph pass keeps ONLY light fields and
                                     DISCARDS heavy :attributes lists.
     - `reduce-concept-embeddings` — streams :ontology/concept-embedded events one
                                     at a time (each carries ONE vector); the rf
                                     keeps only light state and the vector is
                                     DISCARDED after rf returns (the OOM the whole
                                     initiative eliminates).

   Fixture: a REAL in-memory event store (`h/with-test-context`), concepts landed
   via the same `create-concept` command the pipeline uses — including one with a
   MULTI-value :attributes list and one relationship (to exercise the reducer's
   narrower-back-link update branch) — plus a couple of directly-appended
   :ontology/concept-embedded events (tagged exactly as the real embed-concept
   command tags them: [:concept id] only, ontology-id in the body)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.orc.ontology.interface.schemas]        ;; append-time event validation
            [ai.obney.orc.ontology.core.read-models]         ;; register :ontology/concepts
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.concept-stream :as cs]
            [ai.obney.orc.ontology.test-helpers :as h]))

;; =============================================================================
;; Fixture landing helpers
;; =============================================================================

(defn- create-concept! [ctx body]
  (h/run-and-apply! ctx (fn [c] (cmd/ontology-create-concept (assoc c :command body)))))

(defn- create-relationship! [ctx ont-id s p t]
  (h/run-and-apply! ctx (fn [c] (cmd/ontology-create-relationship
                                 (assoc c :command {:ontology-id ont-id
                                                    :source-uri s
                                                    :predicate p
                                                    :target-uri t})))))

(defn- land-embedding!
  "Append a :ontology/concept-embedded event EXACTLY as the real embed-concept
   command tags it: only a [:concept id] tag, ontology-id in the body. Proves
   reduce-concept-embeddings does NOT depend on an [:ontology id] tag (production
   embeddings do not carry one)."
  [ctx ont-id uri]
  (es/append (:event-store ctx)
             {:tenant-id (:tenant-id ctx)
              :events [(es/->event
                        {:type :ontology/concept-embedded
                         :tags #{[:concept (random-uuid)]}
                         :body {:uri uri
                                :ontology-id ont-id
                                :text-embedded (str "text for " uri)
                                :field-source "label+description"
                                :embedding (vec (repeatedly 8 #(double (rand))))
                                :model-id "test-model"
                                :embedded-at "2026-01-01T00:00:00Z"}})]}))

(defn- seed!
  "Land 5 concepts (one with a MULTI-value :attributes list), one skos:broader
   relationship (concept:beta broader concept:alpha — exercises the reducer's
   narrower-back-link update branch), and 2 embeddings. Returns the ontology-id."
  [ctx]
  (let [ont-id (random-uuid)]
    (create-concept! ctx {:ontology-id ont-id :uri "concept:alpha" :label "Alpha"
                          :description "first" :scope :custom
                          :attributes {:codes ["x" "y" "z" "w"] :count 4 :note "multi-value list"}})
    (create-concept! ctx {:ontology-id ont-id :uri "concept:beta"  :label "Beta"
                          :description "second" :scope :custom})
    (create-concept! ctx {:ontology-id ont-id :uri "concept:gamma" :label "Gamma"
                          :description "third" :scope :custom})
    (create-concept! ctx {:ontology-id ont-id :uri "concept:delta" :label "Delta"
                          :description "fourth" :scope :custom})
    (create-concept! ctx {:ontology-id ont-id :uri "concept:epsilon" :label "Epsilon"
                          :description "fifth" :scope :custom})
    ;; concept:beta --skos:broader--> concept:alpha  ⇒  alpha gains :narrower #{beta}
    (create-relationship! ctx ont-id "concept:beta" "skos:broader" "concept:alpha")
    (land-embedding! ctx ont-id "concept:alpha")
    (land-embedding! ctx ont-id "concept:gamma")
    ont-id))

(defn- project-concepts [ctx ont-id]
  (rmp/project ctx :ontology/concepts {:tags #{[:ontology ont-id]}}))

(defn- project-relationships [ctx ont-id]
  (rmp/project ctx :ontology/relationships {:tags #{[:ontology ont-id]}}))

(defn- seed-relationships!
  "Land 5 concepts + several relationships (mixed predicates), one of which
   DANGLES (target endpoint has no concept). Returns the ontology-id. Used by
   the reduce-relationships tracers (analogous to `seed!` for concepts)."
  [ctx]
  (let [ont-id (random-uuid)]
    (doseq [[uri label] [["concept:alpha" "Alpha"] ["concept:beta" "Beta"]
                         ["concept:gamma" "Gamma"] ["concept:delta" "Delta"]
                         ["concept:epsilon" "Epsilon"]]]
      (create-concept! ctx {:ontology-id ont-id :uri uri :label label
                            :description label :scope :custom}))
    (create-relationship! ctx ont-id "concept:beta"  "skos:broader" "concept:alpha")
    (create-relationship! ctx ont-id "concept:gamma" "skos:related" "concept:delta")
    (create-relationship! ctx ont-id "concept:delta" "immediately-follows" "concept:epsilon")
    ;; a DANGLING edge — target concept:omega was never minted
    (create-relationship! ctx ont-id "concept:alpha" "links-to" "concept:omega")
    ont-id))

;; =============================================================================
;; Tracer 1 — State-invariance (the load-bearing correctness test)
;; =============================================================================

(deftest reduce-concepts-state-invariance-test
  (testing "reduce-concepts (no projection, conj rf) collects the SAME concept set
            as (rmp/project :ontology/concepts {:tags #{[:ontology id]}})"
    (h/with-test-context [ctx]
      (let [ont-id  (seed! ctx)
            via-project (vals (project-concepts ctx ont-id))
            via-stream  (cs/reduce-concepts ctx ont-id conj [])]
        (is (= 5 (count via-stream)) "all 5 concepts folded")
        (is (= (count via-project) (count via-stream)) "equal count")
        ;; THE load-bearing assertion: the folded concept VALUES == project's VALUES
        (is (= (set via-project) (set via-stream))
            "reduce-concepts state == rmp/project state (same registered reducer, scope, id-order)")))))

;; =============================================================================
;; Tracer 2 — Field-projection discards heavy fields BEFORE they accumulate
;; =============================================================================

(deftest reduce-concepts-field-projection-discards-heavy-fields-test
  (testing "with :project-fn keeping only [:uri :label], the folded values carry
            ONLY :uri/:label — the heavy multi-value :attributes list never rides along"
    (h/with-test-context [ctx]
      (let [ont-id (seed! ctx)
            ;; sanity: the FULL fold DOES carry the heavy :attributes on concept:alpha
            full   (cs/reduce-concepts ctx ont-id conj [])
            alpha-full (first (filter #(= "concept:alpha" (:uri %)) full))
            projected (cs/reduce-concepts ctx ont-id conj []
                                          {:project-fn #(select-keys % [:uri :label])})]
        (is (seq (:attributes alpha-full)) "precondition: full concept carries the heavy list")
        (is (every? #(= #{:uri :label} (set (keys %))) projected)
            "every projected value has ONLY :uri and :label")
        (is (not-any? :attributes projected)
            "NO projected value carries :attributes (heavy list discarded pre-accumulation)")
        (is (= 5 (count projected)))))))

;; =============================================================================
;; Tracer 3 — Windowing (pager correctness across multiple pages)
;; =============================================================================

(deftest reduce-concepts-windowing-across-pages-test
  (testing "with a small WINDOW (2) over >WINDOW concepts, the paged result still
            equals the full single-page set"
    (h/with-test-context [ctx]
      (let [ont-id (seed! ctx)
            full     (set (cs/reduce-concepts ctx ont-id conj []))
            windowed (set (cs/reduce-concepts ctx ont-id conj [] {:window 2}))]
        (is (= 5 (count windowed)) "all concepts survive multi-page paging")
        (is (= full windowed) "paged fold == whole fold (cursor advances correctly)")))))

;; =============================================================================
;; Tracer 4 — reduce-concept-embeddings streams URIs, retains NO vector
;; =============================================================================

(deftest reduce-concept-embeddings-streams-uris-retains-no-vector-test
  (testing "reduce-concept-embeddings invokes rf once per embedded concept with the
            vector, and the accumulator holds ONLY URIs (no vector retained)"
    (h/with-test-context [ctx]
      (let [ont-id (seed! ctx)
            seen   (atom [])
            acc    (cs/reduce-concept-embeddings
                    ctx ont-id
                    (fn [uris uri vector]
                      (swap! seen conj [uri (count vector)])
                      (conj uris uri))     ;; keep ONLY the uri — discard the vector
                    #{})]
        (is (= #{"concept:alpha" "concept:gamma"} acc)
            "streams to the exact set of embedded URIs")
        (is (= 2 (count @seen)) "rf invoked once per embedded concept")
        (is (every? #(pos? (second %)) @seen)
            "the vector WAS passed to rf (non-empty) each call")
        ;; accumulator shape carries no vector — it is a set of URI strings only
        (is (every? string? acc) "accumulator holds only URIs, no vector rides along")))))

;; =============================================================================
;; Tracer 6 — reduce-relationships state-invariance (the graph-build foundation)
;; =============================================================================

(deftest reduce-relationships-state-invariance-test
  (testing "reduce-relationships (no projection, conj rf) collects the SAME
            relationship set as (rmp/project :ontology/relationships {:tags ...})
            — same REGISTERED reducer, same [:ontology id]-tag scope, same order"
    (h/with-test-context [ctx]
      (let [ont-id      (seed-relationships! ctx)
            via-project (vals (project-relationships ctx ont-id))
            via-stream  (cs/reduce-relationships ctx ont-id conj [])]
        (is (= 4 (count via-stream)) "all 4 relationships folded")
        (is (= (count via-project) (count via-stream)) "equal count")
        (is (= (set via-project) (set via-stream))
            "reduce-relationships state == rmp/project state (registered reducer reused, not forked)")))))

;; =============================================================================
;; Tracer 7 — reduce-relationships field-projection == the fixture's edge fields
;; =============================================================================

(deftest reduce-relationships-field-projection-test
  (testing "with :project-fn keeping only endpoint fields, the folded values
            carry ONLY [:source-uri :target-uri :predicate] and equal what the
            project's values project to — heavy metadata is discarded pre-accumulation"
    (h/with-test-context [ctx]
      (let [ont-id    (seed-relationships! ctx)
            fields    [:source-uri :target-uri :predicate]
            projected (cs/reduce-relationships ctx ont-id conj []
                                               {:project-fn #(select-keys % fields)})
            expected  (map #(select-keys % fields) (vals (project-relationships ctx ont-id)))]
        (is (= 4 (count projected)))
        (is (every? #(= (set fields) (set (keys %))) projected)
            "every projected edge carries ONLY the endpoint fields")
        (is (= (set expected) (set projected))
            "projected edges == the registered projection's values projected to the same fields")))))

;; =============================================================================
;; Tracer 8 — reduce-relationships windowing across pages
;; =============================================================================

(deftest reduce-relationships-windowing-across-pages-test
  (testing "with a small WINDOW (2) over >WINDOW events, the paged relationship
            fold equals the full single-page fold (cursor advances correctly)"
    (h/with-test-context [ctx]
      (let [ont-id   (seed-relationships! ctx)
            full     (set (cs/reduce-relationships ctx ont-id conj []))
            windowed (set (cs/reduce-relationships ctx ont-id conj [] {:window 2}))]
        (is (= 4 (count windowed)) "all relationships survive multi-page paging")
        (is (= full windowed) "paged fold == whole fold")))))

;; =============================================================================
;; Tracer 5 — Reducer-reuse (the relationship narrower-back-link update branch)
;; =============================================================================

(deftest reduce-concepts-reuses-registered-reducer-test
  (testing "reduce-concepts reuses the REGISTERED reducer: the skos:broader edge's
            narrower back-link (an update-in on an already-landed concept) appears
            identically to rmp/project — a forked/naive reducer would miss it"
    (h/with-test-context [ctx]
      (let [ont-id  (seed! ctx)
            stream  (cs/reduce-concepts ctx ont-id conj [])
            alpha-stream (first (filter #(= "concept:alpha" (:uri %)) stream))
            alpha-proj   (get (project-concepts ctx ont-id) "concept:alpha")]
        (is (= #{"concept:beta"} (:narrower alpha-stream))
            "the reducer's update branch produced the narrower back-link")
        (is (= alpha-proj alpha-stream)
            "the folded concept is byte-identical to project (registered reducer reused, not forked)")))))
