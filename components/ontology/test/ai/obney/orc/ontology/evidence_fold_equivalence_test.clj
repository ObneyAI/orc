(ns ai.obney.orc.ontology.evidence-fold-equivalence-test
  "ME-3 /prototype gate — prove (or disprove) that moving evidence emission
   from PER-PAIR to ONCE-PER-CONCEPT preserves the `concept-evidence`
   PROJECTION byte-identically.

   The refactor is only sound if, for a concept participating in K
   cascade comparisons, the once-per-concept path (fold
   `aggregate-from-cascade` over the K comparisons IN ORDER → emit 1 event
   → project) yields a `concept-evidence` map entry that is `=` to what the
   per-pair path produces (emit K events → last-wins projection).

   BUT `per-pair` has TWO possible meanings, and they are NOT the same:

     * PATH A-running  — each per-pair event's `:existing` is the RUNNING
       projection (the emit → re-project → next-command-reads-it model).
       This is what a DIRECT caller of `run-dedup-cascade` sees (each call
       re-projects `:ontology/concept-evidence` live — see command
       ~1598), and it is the model the ME-3 fold analysis assumed.

     * PATH A-static  — every per-pair event's `:existing` is the SAME
       start-of-stage SNAPSHOT. This is what the ACTUAL `dedup-stage`
       produces: DTscale-1 project-once threads a frozen
       `:existing-evidence` map into EVERY survivor command
       (deterministic_skeleton ~435 + ~459), so no command re-projects and
       none sees another's intra-stage increment.

   The 927k evidence events in the real O*NET build come from `dedup-stage`,
   i.e. PATH A-static. So the byte-identity that actually matters for
   'behavior-preserving to TODAY' is: once-per-concept fold  vs  PATH
   A-static last-wins.

   These tests establish, empirically:
     1. once-per-concept fold  ==  PATH A-running  (the fold IS a clean
        order-preserving reduce — the analysis' claim holds under the
        running-projection model).
     2. once-per-concept fold  !=  PATH A-static  for K>=2 (the ACTUAL
        dedup-stage projection reflects only ONE comparison per concept —
        last-wins over near-identical snapshot+1 events).

   Clock is injected (`:computed-at` supplied) so byte-identity is stable."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.test-helpers :as h]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.deterministic-skeleton :as skeleton]
            [ai.obney.orc.ontology.core.evidence :as ev]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.event-store-v3.interface :as es]))

;; =============================================================================
;; Pure modelling — mirror the command's event builder + the projection
;; =============================================================================

(defn- agg->aggregated-event
  "Mirror `run-dedup-cascade`'s `evidence-event` builder (commands.clj
   ~1614-1632) EXACTLY: same keys, same conditional inclusion of
   :source-refs / :equivalence-history. This is the event body the
   projection reads."
  [uri agg verdict ontology-id]
  (cond-> {:event/type            :ontology/concept-evidence-aggregated
           :ontology-id           ontology-id
           :concept-uri           uri
           :tier                  (or (:tier verdict) :unknown-tier)
           :verdict               (:verdict verdict)
           :tier-contributions    (:tier-contributions agg)
           :sources-count         (:sources-count agg)
           :dedup-decisions-count (:dedup-decisions-count agg)
           :evidence-score        (:evidence-score agg)
           :computed-at           (:computed-at agg)}
    (seq (:source-refs agg))
    (assoc :source-refs (vec (:source-refs agg)))
    (seq (:equivalence-history agg))
    (assoc :equivalence-history (vec (:equivalence-history agg)))))

(defn- project
  "Project a seq of aggregated events through the REAL read-model reducer
   and return the entry for `uri`."
  [events uri]
  (-> (rm/concept-evidence {} events)
      (get uri)))

(def ontology-id #uuid "5130fe00-0000-0000-0000-000000000001")
(def align-id    #uuid "5130fe00-0000-0000-0000-000000000002")
(def uri "p:FocalConcept")

;; A comparison spec: the verdict the cascade returned for the focal
;; concept, the source-ref the candidate came from, and the timestamp the
;; event carries. Mixed verdict types + kinds + dup source-refs.
(defn- verdict [tier v & {:keys [kind]}]
  (cond-> {:tier tier :verdict v}
    kind (assoc :kind kind)))

(def mixed-comparisons
  "K=6 comparisons of MIXED verdict types, varying source-refs (incl. a
   DUPLICATE 'src-A'), two of them :merge (append equivalence-history)."
  [{:verdict (verdict :number-guard :distinct)        :source-ref "src-A" :ts "2026-01-01T00:00:00Z"}
   {:verdict (verdict :string-merge :merge :kind :same-as) :source-ref "src-B" :ts "2026-01-02T00:00:00Z" :aid align-id}
   {:verdict (verdict :entropy-guard :skip)           :source-ref "src-A" :ts "2026-01-03T00:00:00Z"} ;; dup src-A
   {:verdict (verdict :llm :requires-review)          :source-ref "src-C" :ts "2026-01-04T00:00:00Z"}
   {:verdict (verdict :lsh-jaccard :distinct)         :source-ref "src-D" :ts "2026-01-05T00:00:00Z"}
   {:verdict (verdict :string-merge :merge :kind :equivalent-class) :source-ref "src-E" :ts "2026-01-06T00:00:00Z" :aid align-id}])

;; -----------------------------------------------------------------------------
;; The three paths
;; -----------------------------------------------------------------------------

(defn- agg-for [existing {:keys [verdict source-ref ts aid]}]
  (ev/aggregate-from-cascade
   {:existing existing :verdict verdict :source-ref source-ref
    :computed-at ts :alignment-id aid}))

(defn- path-B-fold-once
  "ONCE-PER-CONCEPT: reduce `aggregate-from-cascade` over the comparisons
   IN ORDER, emit ONE event, project."
  [comparisons]
  (let [final-agg (reduce (fn [existing c] (agg-for existing c)) {} comparisons)
        last-verdict (:verdict (last comparisons))
        events [(agg->aggregated-event uri final-agg last-verdict ontology-id)]]
    (project events uri)))

(defn- path-A-running
  "PER-PAIR, RUNNING projection: each comparison folds off the PRIOR
   running aggregate (emit → re-project → next reads it); emit K events;
   last-wins projection."
  [comparisons]
  (let [events (loop [existing {} , cs comparisons , acc []]
                 (if-let [c (first cs)]
                   (let [agg (agg-for existing c)]
                     (recur agg (rest cs)
                            (conj acc (agg->aggregated-event uri agg (:verdict c) ontology-id))))
                   acc))]
    (project events uri)))

(defn- path-A-static
  "PER-PAIR, STATIC snapshot (the ACTUAL dedup-stage / DTscale-1 project-once):
   EVERY comparison folds off the SAME `snapshot`; emit K events; last-wins
   projection."
  [comparisons snapshot]
  (let [events (mapv (fn [c]
                       (agg->aggregated-event uri (agg-for snapshot c) (:verdict c) ontology-id))
                     comparisons)]
    (project events uri)))

;; =============================================================================
;; (1) once-per-concept fold == PATH A-running  (the fold is a clean reduce)
;; =============================================================================

(deftest fold-once-equals-per-pair-running-projection
  (testing "For K in 1..6 prefixes, mixed verdict types/order, dup source-refs:
            the once-per-concept fold entry is BYTE-IDENTICAL to the per-pair
            RUNNING-projection entry (incl. :equivalence-history order,
            :tier-contributions, :source-refs, :evidence-score, :computed-at,
            :last-reinforced-at)."
    (doseq [k (range 1 (inc (count mixed-comparisons)))]
      (let [comps (vec (take k mixed-comparisons))
            b (path-B-fold-once comps)
            a (path-A-running comps)]
        (is (= a b)
            (str "K=" k ": fold-once must equal per-pair running projection"))
        ;; spell out the load-bearing fields so a failure localizes
        (is (= (:dedup-decisions-count a) (:dedup-decisions-count b) k))
        (is (= (:tier-contributions a) (:tier-contributions b)))
        (is (= (:source-refs a) (:source-refs b)))
        (is (= (:equivalence-history a) (:equivalence-history b)))
        (is (= (:evidence-score a) (:evidence-score b)))
        (is (= (:computed-at a) (:computed-at b)))
        (is (= (:last-reinforced-at a) (:last-reinforced-at b)))))))

(deftest fold-once-equivalence-history-order-preserved
  (testing "The ONLY order-dependent field (:equivalence-history is a conj
            vector) is preserved in processing order by the fold."
    (let [comps mixed-comparisons
          b (path-B-fold-once comps)]
      (is (= 2 (count (:equivalence-history b)))
          "two :merge comparisons → two history entries")
      (is (= [:same-as :equivalent-class]
             (mapv :kind (:equivalence-history b)))
          "history entries in processing order (same-as before equivalent-class)")
      (is (= ["2026-01-02T00:00:00Z" "2026-01-06T00:00:00Z"]
             (mapv :recorded-at (:equivalence-history b)))
          "each history entry carries ITS comparison's timestamp"))))

;; =============================================================================
;; (2) once-per-concept fold != PATH A-static  (the ACTUAL dedup-stage diverges)
;; =============================================================================

(deftest fold-once-DIVERGES-from-per-pair-static-snapshot
  (testing "THE FINDING: the real dedup-stage threads a FROZEN start-of-stage
            snapshot into every survivor command (DTscale-1 project-once), so
            every per-pair event reads the SAME `:existing` and last-wins keeps
            only the LAST comparison. For K>=2 this is NOT the fold — it
            reflects ONE comparison, not all K."
    (let [snapshot {} ;; a fresh build: concept-evidence is empty before the stage
          k2 (vec (take 2 mixed-comparisons))
          b2 (path-B-fold-once k2)
          s2 (path-A-static k2 snapshot)]
      (is (not= b2 s2)
          "K=2: fold-once entry differs from static-snapshot last-wins entry")
      (is (= 2 (:dedup-decisions-count b2)) "fold counts BOTH comparisons")
      (is (= 1 (:dedup-decisions-count s2)) "static keeps only the LAST comparison"))
    ;; full K=6 — quantify every diverging field
    (let [snapshot {}
          comps mixed-comparisons
          b (path-B-fold-once comps)
          s (path-A-static comps snapshot)]
      (is (not= b s) "K=6: entries differ")
      (is (= 6 (:dedup-decisions-count b)))
      (is (= 1 (:dedup-decisions-count s))
          "static-snapshot decisions-count is ALWAYS 1 on a fresh build")
      (is (not= (:source-refs b) (:source-refs s))
          "fold unions all source-refs; static keeps only the last comparison's")
      (is (not= (:tier-contributions b) (:tier-contributions s)))
      (is (not= (:equivalence-history b) (:equivalence-history s))
          "fold keeps BOTH merges; static keeps only the last (a merge, so 1)")
      (is (not= (:evidence-score b) (:evidence-score s))))))

;; =============================================================================
;; (3) EMPIRICAL — drive the REAL command + REAL projection to confirm the
;;     dedup-stage's static-snapshot behavior is not my modelling artifact.
;; =============================================================================

(def align-section #uuid "5130fe00-0000-0000-0000-0000000000aa")
(def focal {:uri "p:Director1" :label "Director" :description "person who directs films" :type :class :kind-hint :same-as})
(def dir-2 {:uri "p:Director2" :label "director" :description "person who directs films" :type :class :kind-hint :same-as})
(def dir-3 {:uri "p:Director3" :label "Directors" :description "person who directs films" :type :class :kind-hint :same-as})
(def unrelated {:uri "p:Organization1" :label "Organization" :description "a structured group of people" :type :class})

(defn- run-cascade-data! [ctx body]
  ;; Run the cascade command, apply its OWN events (equivalence, etc.), and
  ;; return :command-result/data (verdict + evidence contribution).
  (:command-result/data
   (h/run-and-apply! ctx (fn [c] (cmd/ontology-run-dedup-cascade (assoc c :command body))))))

(defn- evidence-events-for [ctx oid concept-uri]
  (->> (es/read (:event-store ctx) {:tags #{[:ontology oid]} :tenant-id (:tenant-id ctx)})
       (into [])
       (filter #(and (= :ontology/concept-evidence-aggregated (:event/type %))
                     (= concept-uri (:concept-uri %))))))

;; -----------------------------------------------------------------------------
;; ME-3 behavior (1): the cascade EMITS NO evidence event — it returns the
;; contribution on :command-result/data for the stage to fold.
;; -----------------------------------------------------------------------------

(deftest cascade-emits-no-evidence-event-returns-contribution
  (testing "ME-3: run-dedup-cascade no longer emits concept-evidence-aggregated
            per pair. Its result carries ZERO such events and instead returns an
            :evidence-contribution (both URIs + verdict) on :command-result/data."
    (h/with-test-context [ctx]
      (let [r (cmd/ontology-run-dedup-cascade
               (assoc ctx :command {:ontology-id ontology-id :a focal :b unrelated}))
            evs (:command-result/events r)
            aggs (filter #(= :ontology/concept-evidence-aggregated (:event/type %)) evs)
            contrib (get-in r [:command-result/data :evidence-contribution])]
        (is (= 0 (count aggs)) "cascade emits NO evidence event")
        (is (some? contrib) "the contribution rides on :command-result/data")
        (is (= (:uri focal) (:a-uri contrib)))
        (is (= (:uri unrelated) (:b-uri contrib)))
        (is (some? (:verdict contrib)))))))

;; -----------------------------------------------------------------------------
;; ME-3 behavior (2): the STAGE fold accumulates correctly AND emits exactly
;; ONE event per concept — driven through the REAL production shared helper
;; (`skeleton/emit-accumulated-evidence!`) over the REAL cascade contributions.
;; -----------------------------------------------------------------------------

(deftest stage-fold-accumulates-and-emits-one-event-per-concept
  (testing "Focal concept in K=3 mixed-verdict comparisons (2 merges + 1
            distinct). After the fold+emit: get-concept-evidence shows
            dedup-decisions-count = 3 (NOT the degenerate 1), tier-contributions
            summed across all 3, equivalence-history = the 2 merges IN ORDER —
            AND exactly ONE evidence event landed for the focal (write-
            amplification fixed: was ~2×pairs, now 1 per concept)."
    (h/with-test-context [ctx]
      (let [snapshot (rmp/project ctx :ontology/concept-evidence)
            d1 (run-cascade-data! ctx {:ontology-id ontology-id :alignment-ontology-id align-section
                                       :a focal :b dir-2})
            d2 (run-cascade-data! ctx {:ontology-id ontology-id :alignment-ontology-id align-section
                                       :a focal :b dir-3})
            d3 (run-cascade-data! ctx {:ontology-id ontology-id :a focal :b unrelated})
            datas [d1 d2 d3]
            verdicts (mapv :verdict datas)
            merges (filterv #(= :merge (:verdict %)) verdicts)
            contribs (mapv :evidence-contribution datas)
            ;; The REAL production orchestration (same fn dedup-stage uses).
            n (skeleton/emit-accumulated-evidence!
               ctx ontology-id snapshot contribs "2026-01-01T00:00:00Z")
            ev (rm/get-concept-evidence ctx (:uri focal))]
        (is (= 3 (:dedup-decisions-count ev))
            "focal participated in 3 comparisons → decisions-count accumulates to 3")
        (is (= 3 (reduce + (vals (:tier-contributions ev))))
            "tier-contributions summed across ALL 3 comparisons")
        (is (= (count merges) (count (:equivalence-history ev)))
            "equivalence-history has one entry per MERGE verdict")
        (is (= (mapv :kind merges) (mapv :kind (:equivalence-history ev)))
            "equivalence-history entries in processing ORDER, kinds matching the merges")
        ;; The write-amplification fix: exactly ONE landed event for the focal.
        (is (= 1 (count (evidence-events-for ctx ontology-id (:uri focal))))
            "exactly ONE concept-evidence-aggregated event landed for the focal (not 3)")
        ;; every participating concept got an event (focal + dir-2 + dir-3 + unrelated)
        (is (= 4 n) "one accumulated event per participating concept")))))
