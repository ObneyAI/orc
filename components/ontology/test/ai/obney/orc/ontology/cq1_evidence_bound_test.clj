(ns ai.obney.orc.ontology.cq1-evidence-bound-test
  "CQ-1 — `render-evidence-text` must stay bounded at real graph scale.

   Root cause (trace-confirmed): the CQ-gate judge call crashed live
   builds with context-length errors (~2.36M tokens) because the
   evidence text enumerated a head line for EVERY concept (15,728) and
   a line for EVERY relationship (69,591 — the edges block had NO bound
   at all). The existing `enum-attr-concept-cap` bounded only which
   concepts got ATTRIBUTE lines, not the enumerations themselves.

   Contract guarded here:
   1. Bounded regardless of graph size (crash guard: 10k concepts /
      50k rels renders < 300k chars, with count digests + honest notes).
   2. Query-relevant neighborhood survives the cap (retrieved-URI-
      incident edges + 1-hop-neighbor concepts are prioritized).
   3. Small graphs render BYTE-IDENTICAL to the pre-cap implementation
      (golden expectation captured from the pre-change code).
   4. The completeness/closure block — the ONLY grounded basis for a
      :fail on absence — is untouched at every scale.

   All fixtures are deterministic and domain-agnostic (generated
   labels; no domain literals)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [ai.obney.orc.ontology.core.cq-runner :as cqr]))

;; =============================================================================
;; Fixtures (deterministic — same bytes every run, every JVM)
;; =============================================================================

(defn small-graph
  "40 concepts / 60 rels, some attributed, some retrieved, with closure
   signals — comfortably UNDER the enumeration caps. The golden file
   `cq1_golden_small_graph.txt` was captured by running the PRE-CQ-1
   implementation of `render-evidence-text` against exactly this value."
  []
  (let [concepts (mapv (fn [i]
                         (cond-> {:uri   (str "thing:grp" (mod i 4) "/c" i)
                                  :label (str "Thing " i)}
                           (< i 12)  (assoc :attributes
                                            {:size i
                                             :tags [(str "tag-" i) (str "tag-x" i)]})
                           (>= i 36) (assoc :properties {:legacy (str "L" i)})))
                       (range 40))
        rels (mapv (fn [i]
                     {:source-uri (str "thing:grp" (mod i 4) "/c" (mod i 40))
                      :predicate  (str "pred-" (mod i 3))
                      :target-uri (str "thing:grp" (mod (inc i) 4) "/c" (mod (* 7 i) 40))})
                   (range 60))
        retrieved [{:uri "thing:grp0/c0" :label "Thing 0"
                    :description "the zeroth thing"
                    :attributes {:size 0 :tags ["tag-0" "tag-x0"]}}
                   {:uri "thing:grp1/c1" :label "Thing 1"
                    :description "the first thing"}]]
    {:retrieved       retrieved
     :concepts        concepts
     :relationships   rels
     :axioms          {:disjointness #{#{"class:A" "class:B"}}}
     :closed-concepts [{:uri "thing:set/closed-1" :label "Closed Set" :closed? true}]}))

(defn large-graph
  "~10k concepts / ~50k rels — the crash-guard scale. The retrieved hub's
   incident edges and its 1-hop-neighbor concepts are appended at the END
   of the vectors, so any cap that takes purely by position would drop
   them: the neighborhood tests go RED without prioritization."
  []
  (let [n-c 10000
        n-r 50000
        concepts (mapv (fn [i]
                         {:uri   (str "kind:k" (mod i 7) "/n" i)
                          :label (str "Node " i)})
                       (range n-c))
        rels (mapv (fn [i]
                     {:source-uri (str "kind:k" (mod i 7) "/n" (mod i n-c))
                      :predicate  (str "rel-" (mod i 11))
                      :target-uri (str "kind:k" (mod (inc i) 7) "/n" (mod (* 13 i) n-c))})
                   (range n-r))
        hub {:uri "kind:hub/h0" :label "Hub" :description "retrieved hub"}
        neighbors (mapv (fn [i]
                          {:uri (str "kind:nb/nb" i) :label (str "Neighbor " i)})
                        (range 5))
        hub-edges (mapv (fn [i]
                          {:source-uri "kind:hub/h0"
                           :predicate  "hub-links"
                           :target-uri (str "kind:nb/nb" i)})
                        (range 5))]
    {:retrieved       [hub]
     :concepts        (into concepts (conj neighbors hub))
     :relationships   (into rels hub-edges)
     :axioms          {:disjointness #{#{"class:A" "class:B"}}}
     :closed-concepts [{:uri "kind:set/closed" :label "Closed" :closed? true}]}))

;; =============================================================================
;; Cycle 1 (the crash guard) — 10k concepts / 50k rels stays FAR below the
;; context limit, with count digests + honest cap notes.
;; =============================================================================

(deftest cq1-large-graph-evidence-is-bounded
  (testing "10k-concept / 50k-rel graph renders < 300k chars (the live crash
            was ~8.5 MB / 2.36M tokens), with a concept type digest, a
            relationship predicate digest, and honest cap notes"
    (let [txt (cqr/render-evidence-text (large-graph))]
      (is (< (count txt) 300000)
          (str "evidence text must stay bounded; got " (count txt) " chars"))
      (is (str/includes? txt "TYPE DIGEST")
          "the capped concepts block carries a type digest of the FULL set")
      (is (str/includes? txt "PREDICATE DIGEST")
          "the capped edges block carries a predicate digest of the FULL set")
      (is (str/includes? txt "concept enumeration capped")
          "the concept cap is SURFACED honestly, never silent")
      (is (str/includes? txt "relationship enumeration capped")
          "the edge cap is SURFACED honestly, never silent")
      (is (str/includes? txt "OMITTED")
          "the omission is named for the judge"))))

(deftest cq1-pathological-unique-prefixes-still-bounded
  (testing "even when EVERY concept URI carries a unique prefix (and every
            edge a unique predicate) the digests themselves stay bounded —
            'bounded regardless of graph size' must hold with no
            data-shape escape hatch"
    (let [concepts (mapv (fn [i] {:uri (str "u" i ":x/" i) :label (str "U " i)})
                         (range 20000))
          rels (mapv (fn [i] {:source-uri (str "u" i ":x/" i)
                              :predicate  (str "p-" i)
                              :target-uri (str "u" (mod (inc i) 20000) ":x/" (mod (inc i) 20000))})
                     (range 20000))
          txt (cqr/render-evidence-text {:retrieved [] :concepts concepts
                                         :relationships rels :axioms nil
                                         :closed-concepts []})]
      (is (< (count txt) 300000)
          (str "digest must be bounded too; got " (count txt) " chars")))))

(deftest cq1-slash-scheme-uris-digest-per-scheme
  (testing "GC-1 canonical URIs are SLASH-scheme with no colon (e.g.
            occupation/11-1011.00) — the type digest must group them by the
            scheme segment before the first slash, NOT collapse the whole
            graph into one useless '(no prefix)' bucket"
    (let [concepts (into []
                         (mapcat (fn [[scheme n]]
                                   (mapv (fn [i]
                                           {:uri   (str scheme "/" i "-x.00")
                                            :label (str scheme " " i)})
                                         (range n))))
                         [["occupation" 200] ["skill" 150] ["task" 100]])
          txt (cqr/render-evidence-text {:retrieved [] :concepts concepts
                                         :relationships [] :axioms nil
                                         :closed-concepts []})]
      (is (str/includes? txt "    occupation: 200 concepts")
          "slash-scheme URIs group by their scheme segment")
      (is (str/includes? txt "    skill: 150 concepts"))
      (is (str/includes? txt "    task: 100 concepts"))
      (is (not (str/includes? txt "(no prefix)"))
          "the digest must NOT degenerate into a single no-prefix bucket"))))

;; =============================================================================
;; Cycle 2 (query-relevant neighborhood survives the cap) — edges incident to
;; the retrieved URIs and their 1-hop-neighbor concepts are prioritized into
;; the enumerated subsets even at 50k rels / 10k concepts.
;; =============================================================================

(deftest cq1-retrieved-neighborhood-survives-capping
  (testing "edges incident to retrieved URIs + the retrieved concepts'
            1-hop neighbors appear in the capped enumeration, even though
            the fixture appends them LAST (position-only capping drops them)"
    (let [txt (cqr/render-evidence-text (large-graph))]
      (doseq [i (range 5)]
        (is (str/includes? txt (str "  kind:hub/h0 hub-links kind:nb/nb" i))
            (str "retrieved-URI-incident edge " i " must survive the edge cap")))
      (doseq [i (range 5)]
        (is (str/includes? txt (str "  kind:nb/nb" i " [label: Neighbor " i "]"))
            (str "1-hop neighbor concept " i
                 " must survive the concept cap (head-line format)"))))))

;; =============================================================================
;; Cycle 3 (characterization FIRST, captured pre-change) — small-graph
;; rendering is byte-identical to the pre-CQ-1 implementation.
;; =============================================================================

(deftest cq1-small-graph-byte-identical-to-pre-cap-rendering
  (testing "under the caps, output is byte-identical to the pre-CQ-1
            implementation — full enumeration, no digests, no cap notes
            (golden captured from the CURRENT code before the change)"
    (let [golden (slurp (io/resource "ai/obney/orc/ontology/cq1_golden_small_graph.txt"))
          txt (cqr/render-evidence-text (small-graph))]
      (is (= golden txt)
          "small-graph evidence text drifted from the pre-cap golden capture")
      (is (not (str/includes? txt "DIGEST"))
          "no digest block appears under the caps")
      (is (not (str/includes? txt "enumeration capped"))
          "no cap note appears under the caps"))))

;; =============================================================================
;; Cycle 4 (closure grounding unchanged) — the completeness/closure block is
;; the ONLY grounded basis for a :fail on absence; it must render identically
;; whether the graph is under or over the enumeration caps.
;; =============================================================================

(deftest cq1-completeness-block-identical-at-both-scales
  (testing "the EXPLICIT COMPLETENESS / CLOSURE / DISJOINTNESS block renders
            byte-identically for the same axioms + closed-concepts at small
            and crash-guard scale — capping the enumerations cannot move the
            judge's :fail grounding"
    (let [axioms {:disjointness #{#{"class:A" "class:B"}}}
          closed [{:uri "shared:set/closed" :label "Closed Set" :closed? true}]
          header "EXPLICIT COMPLETENESS / CLOSURE / DISJOINTNESS SIGNALS"
          closure-block (fn [txt]
                          (let [idx (str/index-of txt header)]
                            (is (some? idx) "completeness header present")
                            (subs txt idx)))
          small-txt (cqr/render-evidence-text
                     (assoc (small-graph) :axioms axioms :closed-concepts closed))
          large-txt (cqr/render-evidence-text
                     (assoc (large-graph) :axioms axioms :closed-concepts closed))]
      (is (= (closure-block small-txt) (closure-block large-txt))
          "closure/disjointness signals must be identical at both scales"))))
