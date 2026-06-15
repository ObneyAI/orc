(ns ai.obney.orc.ontology.s21-lexical-signal-test
  "S21 — Lexical (label-match) signal in core hybrid-search.

   Root-cause fix for the S19 live-verify finding: a recursive-RLM agent's
   natural first move — `(graph-search \"director\")` on a freshly-built
   graph — returned ALL THREE signals empty, because:
     - the graph signal only fires with caller-supplied :seed-uris,
     - the embedding signal needs pre-generated embeddings,
     - the ColBERT signal needs an index,
   none of which exist on a graph that has just been populated by commands
   but not yet embedded/indexed. The concept literally labelled \"Director\"
   was in the projection, findable by name — but hybrid-search had no
   lexical/name signal to find it, and no way to bootstrap the graph BFS
   from a text query.

   This slice adds a first-class LEXICAL signal: when :query-text is present,
   scan the scoped event-sourced concepts projection for label matches; the
   hits are (a) a first-class RRF signal AND (b) seeds that bootstrap the
   graph BFS when the caller supplied no :seed-uris.

   Live-verified through a REAL Grain event store via with-test-context — no
   synthesized projection fixtures, no embeddings required (that's the whole
   point: lexical must work BEFORE the embed/index stages run)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.test-helpers :as h]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.retrieval :as retrieval]))

(def section-a #uuid "a2100000-0000-0000-0000-00000000021a")
(def section-b #uuid "b2100000-0000-0000-0000-00000000021b")

(defn- seed-concept! [ctx ontology-id uri label]
  (h/run-and-apply! ctx
    (fn [c]
      (cmd/ontology-create-concept
       (assoc c :command
              (h/make-concept-data
               :ontology-id ontology-id
               :uri uri
               :label label
               :description (str label " in " ontology-id)
               :scope :custom))))))

(defn- seed-rel! [ctx src pred tgt]
  (h/run-and-apply! ctx
    (fn [c]
      (cmd/ontology-create-relationship
       (assoc c :command {:source-uri src :predicate pred :target-uri tgt
                          :properties {}})))))

(defn- seed! [ctx]
  ;; Section A — directors/films, NO embeddings generated, NO ColBERT index.
  (doseq [[uri label] [["concept:dir/jane-roe" "Jane Roe"]
                       ["concept:dir/john-doe" "John Doe"]
                       ["concept:role/director" "Director"]
                       ["concept:film/red-dawn" "Red Dawn"]]]
    (seed-concept! ctx section-a uri label))
  (seed-rel! ctx "concept:dir/jane-roe" "directed" "concept:film/red-dawn")
  ;; Section B — a director-labelled concept that must NEVER surface from an
  ;; A-scoped query (isolation guard).
  (seed-concept! ctx section-b "concept:b/film-director" "Film Director Magazine"))

;; =============================================================================
;; AC1 — the keystone: text query with no seed-uris, no embeddings, finds
;;       the label-matching concept via the lexical signal.
;; =============================================================================

(deftest text-query-finds-concept-by-label-without-seeds-or-embeddings
  (h/with-test-context [ctx]
    (seed! ctx)
    (let [r (retrieval/hybrid-search ctx {:query-text "director"
                                          :ontology-ids [section-a]})
          uris (set (map :uri (:results r)))]
      (testing "the concept labelled 'Director' is found by name alone"
        (is (contains? uris "concept:role/director")
            (str "expected concept:role/director in results, got " (pr-str uris))))
      (testing "lexical appears in the batches used"
        (is (contains? (set (:batches-used r)) :lexical)))
      (testing "the lexical-results key is populated"
        (is (seq (:lexical-results r)))))))

;; =============================================================================
;; AC2 — lexical hits bootstrap the graph BFS when no seed-uris given:
;;       a query matching jane-roe surfaces her connected film via the
;;       'directed' edge, even though the caller passed no seeds.
;; =============================================================================

(deftest lexical-hit-bootstraps-graph-bfs
  (h/with-test-context [ctx]
    (seed! ctx)
    (let [r (retrieval/hybrid-search ctx {:query-text "Jane Roe"
                                          :ontology-ids [section-a]
                                          :max-depth 2})
          uris (set (map :uri (:results r)))]
      (testing "jane-roe matched lexically"
        (is (contains? uris "concept:dir/jane-roe")))
      (testing "her connected film surfaced via graph BFS bootstrapped from the lexical seed"
        (is (contains? uris "concept:film/red-dawn")
            (str "expected red-dawn via BFS from lexical seed, got " (pr-str uris))))
      (testing "graph signal fired (graph-rank present on the BFS-reached node)"
        (is (some (fn [x] (and (= "concept:film/red-dawn" (:uri x))
                               (:graph-rank x)))
                  (:results r)))))))

;; =============================================================================
;; AC3 — isolation: a label match that exists ONLY in section B never
;;       surfaces from an A-scoped query. The grant is authoritative.
;; =============================================================================

(deftest lexical-signal-honors-scope-no-cross-section-leak
  (h/with-test-context [ctx]
    (seed! ctx)
    (let [r (retrieval/hybrid-search ctx {:query-text "director"
                                          :ontology-ids [section-a]})
          uris (set (map :uri (:results r)))]
      (testing "section B's 'Film Director Magazine' never leaks into an A-scoped query"
        (is (not (contains? uris "concept:b/film-director"))
            (str "section B leaked: " (pr-str uris)))))))

;; =============================================================================
;; AC4 — quality: exact label match ranks above a mere substring match.
;; =============================================================================

(deftest exact-label-match-outranks-substring
  (h/with-test-context [ctx]
    (seed-concept! ctx section-a "concept:x/director" "Director")
    (seed-concept! ctx section-a "concept:x/assistant" "Assistant Director")
    (let [r (retrieval/hybrid-search ctx {:query-text "Director"
                                          :ontology-ids [section-a]})
          ordered (map :uri (:lexical-results r))]
      (testing "both match lexically"
        (is (= #{"concept:x/director" "concept:x/assistant"} (set ordered))))
      (testing "the exact match 'Director' ranks ahead of the substring match"
        (is (< (.indexOf (vec ordered) "concept:x/director")
               (.indexOf (vec ordered) "concept:x/assistant")))))))

;; =============================================================================
;; AC5 — opt-out: a caller can disable the lexical signal via :signals.
;;       Back-compat for callers that want the strict pre-S21 three-signal
;;       behaviour.
;; =============================================================================

(deftest lexical-signal-is-opt-outable
  (h/with-test-context [ctx]
    (seed! ctx)
    (let [r (retrieval/hybrid-search ctx {:query-text "director"
                                          :ontology-ids [section-a]
                                          :signals #{:graph :embedding :colbert}})
          uris (set (map :uri (:results r)))]
      (testing "with :lexical excluded from :signals, the name match is NOT found"
        (is (not (contains? uris "concept:role/director"))))
      (testing "lexical not in batches-used when disabled"
        (is (not (contains? (set (:batches-used r)) :lexical)))))))

;; =============================================================================
;; AC6 — back-compat: an explicit seed-uris call (the pre-S21 graph path)
;;       still works and is not disturbed by the lexical addition.
;; =============================================================================

(deftest explicit-seed-uris-still-work
  (h/with-test-context [ctx]
    (seed! ctx)
    (let [r (retrieval/hybrid-search ctx {:query-text "Jane Roe"
                                          :seed-uris ["concept:dir/jane-roe"]
                                          :ontology-ids [section-a]
                                          :max-depth 2})
          uris (set (map :uri (:results r)))]
      (testing "explicit-seed BFS still reaches the connected film"
        (is (contains? uris "concept:film/red-dawn"))))))
