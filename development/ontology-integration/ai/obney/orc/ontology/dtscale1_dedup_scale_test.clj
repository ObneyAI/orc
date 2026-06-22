(ns ai.obney.orc.ontology.dtscale1-dedup-scale-test
  "DTscale-1 — Dedup-stage scale: LSH/MinHash blocking + pure pre-filter +
   project-once.

   This is a ROOT-CAUSE performance fix. The contract under test is:

     1. MinHash/LSH blocking at pair generation prunes the candidate-pair
        set to genuinely-similar neighborhoods (SUB-QUADRATIC in concept
        count) — verified by counting pairs on a real-ish concept set with
        many disjoint label-neighborhoods.
     2. The cheap blocking tiers run as a PURE pre-filter over the blocked
        pairs — number / negation / entropy / type / LSH-jaccard / disjoint
        verdicts are decided with NO command, NO projection, NO events.
        Only pairs that SURVIVE the pre-filter (real merge-candidates +
        the ambiguity band) dispatch the full `run-dedup-cascade` command.
     3. The axioms projection happens ONCE per stage, not per pair (the
        command accepts a pre-projected `:disjointness` + `:existing-
        evidence`, projecting itself only when absent — defensive).

   ADVERSARIAL POSTURE (the no-false-green bar):
     - Blocking must prune ONLY true non-candidates. A blocking pass that
       silently drops a real merge pair (`Organization`/`Organisation`,
       `Café`/`Café`, `Director`/`director`) to 'go fast' is a FAIL — those
       pairs MUST survive into the cascade and reach their unchanged verdict.
     - The pure pre-filter must NOT silently decide a merge or an
       ambiguity-band pair (those have events + an LLM cost the stage must
       account for); it only short-circuits the deterministic KEEP/SKIP
       tiers whose verdict is invariant under the rest of the cascade.

   Pure-function level (no event store) + stage-level (real Grain) coverage."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands :as cmd]
            [ai.obney.orc.ontology.core.dedup-cascade :as dedup]
            [ai.obney.orc.ontology.core.deterministic-skeleton :as sk]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.test-helpers :as h]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.time.interface :as time]))

;; =============================================================================
;; Test context (mirrors s17 — real Grain, command/query registries wired)
;; =============================================================================

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/dtscale1-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))]
    {:event-store store
     :cache cache
     :tenant-id (random-uuid)
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     :event-pubsub ps
     ::cache-dir dir}))

(defn- stop-ctx [ctx]
  (rmp/l1-clear!)
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [s (:event-store ctx)] (es/stop s))
  (when-let [d (::cache-dir ctx)]
    (let [f (java.io.File. d)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

(defmacro with-ctx [[sym] & body]
  `(let [~sym (make-ctx)]
     (try ~@body (finally (stop-ctx ~sym)))))

;; =============================================================================
;; A real-ish concept set: many SMALL similar-label neighborhoods scattered
;; across a much larger graph. The all-pairs count is ~n^2/2; the genuinely
;; similar count is the sum over neighborhoods (linear in n). This is the
;; pattern that hot-looped the dedup stage on the crosswalk graph (thousands
;; of `cip:`/`soc:` concepts where only same-family labels are near-dups).
;; =============================================================================

;; Each neighborhood gets a UNIQUE multi-word stem (no shared words, no shared
;; 3-shingles across stems) so cross-neighborhood pairs are genuinely
;; dissimilar — the blocker MUST prune them. Within a neighborhood the labels
;; share the stem (a real near-dup family) so the blocker MUST keep them.
(def ^:private distinct-stems
  ["Photosynthesis" "Quaternion" "Locksmith" "Volcanology" "Bibliography"
   "Hydroponics" "Cryptography" "Embroidery" "Geomorphology" "Trombone"
   "Falconry" "Numismatics" "Spelunking" "Thermodynamics" "Calligraphy"
   "Oceanography" "Beekeeping" "Astrophysics" "Woodturning" "Glassblowing"
   "Cartography" "Herpetology" "Lithography" "Marquetry" "Paleontology"
   "Seismology" "Taxidermy" "Vexillology" "Watchmaking" "Xylography"
   "Apiculture" "Brachiation" "Cytogenetics" "Dendrochronology" "Epidemiology"
   "Fluorography" "Gnomonics" "Horology" "Ichnology" "Jurisprudence"])

(defn- neighborhood-graph
  "n-neighborhoods of `per` concepts each. Within a neighborhood the labels
   share a UNIQUE stem (`Photosynthesis alpha`, `Photosynthesis beta`, ...) so
   the pair is a genuine candidate; ACROSS neighborhoods the stems share no
   words and no 3-shingles, so an honest blocker prunes the cross pairs.
   Returns concept maps in the dedup-stage shape."
  [n per]
  (vec
   (for [t (range n)
         v (range per)]
     (let [stem (nth distinct-stems t)]
       ;; Variant suffix is a single stem-LOCAL letter appended directly to the
       ;; stem (`Photosynthesisa`, `Photosynthesisb`) — within a family the
       ;; labels share nearly all shingles (genuine near-dup); across families
       ;; the stems share no words and no shingles. No shared variant WORD
       ;; leaks similarity across families.
       {:uri (format "ex:T%02dV%d" t v)
        :label (str stem (nth ["a" "b" "c" "d"] v))
        :description (format "concept in family %s" stem)
        :type :class}))))

;; =============================================================================
;; AC — MinHash/LSH blocking prunes to similar neighborhoods (sub-quadratic)
;; =============================================================================

(deftest lsh-blocking-prunes-sub-quadratically
  (testing "On 40 mutually-dissimilar neighborhoods × 3 near-dup concepts
            (120 concepts), the all-pairs count is 120*119/2 = 7140. An honest
            MinHash/LSH blocker emits essentially only within-neighborhood
            pairs (plus a few recall-biased shingle-overlap admissions the T7
            gate later skips) — i.e. linear in concept count, an order of
            magnitude below the quadratic all-pairs count."
    (let [concepts (neighborhood-graph 40 3)
          n (count concepts)
          all-pairs-count (/ (* n (dec n)) 2)
          pairs (dedup/lsh-candidate-pairs concepts)
          pair-count (count pairs)]
      (is (= 120 n))
      (is (= 7140 all-pairs-count))
      ;; Sub-quadratic: an order of magnitude below the quadratic all-pairs
      ;; count, and linear-scale in n (a small multiple — never n^2/2).
      (is (< pair-count (/ all-pairs-count 8))
          (str "blocking must prune well below quadratic; got " pair-count
               " pairs (all-pairs would be " all-pairs-count ")"))
      (is (< pair-count (* 8 n))
          (str "blocked set must be linear-scale in n; got " pair-count
               " over " n " concepts"))
      ;; And it must catch the genuine within-neighborhood candidates: every
      ;; family's 3 concepts form 3 within-family pairs ⇒ at least 40*3 = 120.
      (is (>= pair-count 120)
          "every near-dup family's within-family pairs are kept"))))

(deftest lsh-blocking-keeps-genuine-merge-candidates
  (testing "ADVERSARIAL no-false-green: blocking must NOT drop a real
            near-duplicate pair to go fast. Each known-merge pair from the
            S12 ground-truth set (case / whitespace / unicode / JW-near /
            camelCase-property) MUST appear in the blocked candidate set."
    (let [pairs->set (fn [concepts]
                       (set (map (fn [[a b]] #{(:uri a) (:uri b)})
                                 (dedup/lsh-candidate-pairs concepts))))]
      (testing "Director / director (case variant)"
        (let [cs [{:uri "p:Director1" :label "Director" :type :class}
                  {:uri "p:Director2" :label "director" :type :class}]]
          (is (contains? (pairs->set cs) #{"p:Director1" "p:Director2"}))))
      (testing "Organization / Organisation (JW-near, 1-char diff)"
        (let [cs [{:uri "p:Org1" :label "Organization" :type :class}
                  {:uri "p:Org2" :label "Organisation" :type :class}]]
          (is (contains? (pairs->set cs) #{"p:Org1" "p:Org2"}))))
      (testing "hasAuthor / hasWriter (camelCase, shared `has` token)"
        (let [cs [{:uri "p:hasAuthor" :label "hasAuthor" :type :property}
                  {:uri "p:hasWriter" :label "hasWriter" :type :property}]]
          (is (contains? (pairs->set cs) #{"p:hasAuthor" "p:hasWriter"}))))
      (testing "Chief Executive Officer / whitespace variant"
        (let [cs [{:uri "p:CEO1" :label "Chief Executive Officer" :type :class}
                  {:uri "p:CEO2" :label "  Chief Executive   Officer  " :type :class}]]
          (is (contains? (pairs->set cs) #{"p:CEO1" "p:CEO2"})))))))

(deftest lsh-blocking-drops-token-disjoint-noise
  (testing "Concepts whose labels share NO meaningful token (and are not
            substring-similar) are NOT emitted as a pair — that is the
            pruning that kills the O(n^2) blowup. `Agriculture` vs
            `Plumbing` must not be a candidate."
    (let [cs [{:uri "ex:Agriculture" :label "Agriculture General" :type :class}
              {:uri "ex:Plumbing" :label "Plumbing Trades" :type :class}]
          pset (set (map (fn [[a b]] #{(:uri a) (:uri b)})
                         (dedup/lsh-candidate-pairs cs)))]
      (is (empty? pset)
          "token-disjoint, non-similar labels are pruned at blocking"))))

;; =============================================================================
;; AC — Pure pre-filter: cheap deterministic tiers decide with NO command
;; =============================================================================

(deftest pure-prefilter-decides-cheap-tiers
  (testing "The pure pre-filter returns a terminal verdict (no I/O) for the
            deterministic KEEP/SKIP tiers and `nil` (= survives → full
            cascade) for real merge / ambiguity-band candidates."
    (let [pf (fn [a b] (dedup/prefilter-verdict {:a a :b b}))]
      (testing "T2 number difference → :distinct (terminal)"
        (let [v (pf {:uri "p:M3" :label "Model 3" :type :class}
                    {:uri "p:M30" :label "Model 30" :type :class})]
          (is (= :distinct (:verdict v)))
          (is (= :number-guard (:tier v)))))
      (testing "T3 negation/polarity → :distinct (terminal)"
        (let [v (pf {:uri "p:Ap" :label "approved" :type :class}
                    {:uri "p:NAp" :label "not approved" :type :class})]
          (is (= :distinct (:verdict v)))
          (is (= :negation-guard (:tier v)))))
      (testing "T4 entropy gate → :skip (terminal)"
        (let [v (pf {:uri "p:A" :label "A" :type :class}
                    {:uri "p:B" :label "An" :type :class})]
          (is (= :skip (:verdict v)))
          (is (= :entropy-gate (:tier v)))))
      (testing "T5 type mismatch → :distinct (terminal)"
        (let [v (pf {:uri "p:AC" :label "Author" :type :class}
                    {:uri "p:AP" :label "Author" :type :property})]
          (is (= :distinct (:verdict v)))
          (is (= :type-blocking (:tier v)))))
      (testing "T7 jaccard-below-threshold → :skip (terminal)"
        (let [v (pf {:uri "p:Foo" :label "Foozle" :type :class}
                    {:uri "p:Bar" :label "Barnacle" :type :class})]
          (is (= :skip (:verdict v)))
          (is (= :lsh-blocking (:tier v)))))
      (testing "real merge candidate SURVIVES the pre-filter (nil)"
        (is (nil? (pf {:uri "p:Director1" :label "Director"
                       :description "directs films" :type :class}
                      {:uri "p:Director2" :label "director"
                       :description "directs films" :type :class}))
            "exact-normalization MERGE must NOT be decided by the cheap pre-filter"))
      (testing "ambiguity-band candidate SURVIVES the pre-filter (nil)"
        (is (nil? (pf {:uri "p:hasAuthor" :label "hasAuthor"
                       :description "links a work to its author" :type :property}
                      {:uri "p:hasWriter" :label "hasWriter"
                       :description "links a work to its author" :type :property}))
            "LLM-tier candidate must reach the full cascade")))))

(deftest pure-prefilter-honors-disjointness-map
  (testing "When the pre-filter is given a disjointness map (projected ONCE
            by the stage), a disjoint pair is decided :distinct at T1 with NO
            command — preserving the KEEP verdict the full cascade would
            reach, without a per-pair projection."
    (let [dmap {"bio:Mammal" #{"bio:Reptile"}
                "bio:Reptile" #{"bio:Mammal"}}
          v (dedup/prefilter-verdict
             {:a {:uri "bio:Whale" :label "Whale" :type :class :broader ["bio:Mammal"]}
              :b {:uri "bio:Croc" :label "Whale" :type :class :broader ["bio:Reptile"]}
              :disjointness-map dmap})]
      (is (= :distinct (:verdict v)))
      (is (= :disjointness-guard (:tier v))
          "disjoint pair short-circuits at T1 even with identical labels"))))

;; =============================================================================
;; AC — Stage-level: skipped/distinct pairs produce NO command/events; only
;; survivors hit the cascade; the axioms projection happens ONCE.
;; =============================================================================

(defn- count-events-of-type [ctx oid t]
  (count (filter #(= t (:event/type %))
                 (into [] (es/read (:event-store ctx)
                                   {:tags #{[:ontology oid]}
                                    :tenant-id (:tenant-id ctx)})))))

(deftest prefiltered-pairs-emit-no-events
  (testing "Build a graph of pairs that ALL resolve at the cheap pre-filter
            (number / type / token-disjoint). The dedup stage must emit ZERO
            cascade events (no co-occurrence, no dedup-distinct, no evidence)
            for them — they never reach the command. The stage still reports
            them as decided verdicts."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            ;; All near-dup by token (so they survive blocking) but each pair
            ;; is a deterministic KEEP/SKIP at the pre-filter:
            ;;  - Model N family: T2 number-guard → distinct
            concepts (vec (for [i (range 8)]
                            {:uri (format "ex:Model%d" i)
                             :label (format "Model %d" i)
                             :description "a model"
                             :type :class}))
            result (sk/build! ctx
                              {:ontology-id oid
                               :sources [{:type :inline-concepts :concepts concepts}]
                               :exit-criterion {:pass-rate-min 0.0 :unknown-rate-max 1.0}})]
        (is (= :complete (:status result))
            (str "expected complete, got " (:status result) " " (:error result)))
        (is (zero? (count-events-of-type ctx oid :ontology/concept-pair-co-occurrence))
            "ADVERSARIAL: pre-filtered KEEP pairs emit NO co-occurrence events")
        (is (zero? (count-events-of-type ctx oid :ontology/dedup-distinct-recorded))
            "ADVERSARIAL: pre-filtered KEEP pairs emit NO dedup-distinct events")
        (is (zero? (count-events-of-type ctx oid :ontology/concept-evidence-aggregated))
            "ADVERSARIAL: pre-filtered KEEP pairs emit NO evidence events")
        ;; The verdicts are still reported on the stage summary (number-guard
        ;; distinct), so we have NOT silently lost the decisions.
        (is (pos? (get-in result [:dedup-summary :distinct]))
            "the number-guard KEEP verdicts are reported on the dedup summary")))))

(deftest survivor-pair-reaches-cascade-and-merges
  (testing "A genuine merge candidate (case variant, descs agree) SURVIVES
            the pre-filter and reaches the full cascade command — which emits
            the equivalence + co-occurrence events. The MERGE verdict is
            unchanged from the pre-fix S12 behavior."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            concepts [{:uri "ex:Director1" :label "Director"
                       :description "person who directs films" :type :class}
                      {:uri "ex:Director2" :label "director"
                       :description "person who directs films" :type :class}]
            result (sk/build! ctx
                              {:ontology-id oid
                               :alignment-ontology-id oid
                               :sources [{:type :inline-concepts :concepts concepts}]
                               :exit-criterion {:pass-rate-min 0.0 :unknown-rate-max 1.0}})]
        (is (= :complete (:status result))
            (str "expected complete, got " (:status result) " " (:error result)))
        (is (= 1 (get-in result [:dedup-summary :merges]))
            "the genuine candidate reached the cascade and MERGED (verdict unchanged)")
        (is (pos? (count-events-of-type ctx oid :ontology/equivalence-recorded))
            "the survivor emitted its equivalence event via the command")
        (is (pos? (count-events-of-type ctx oid :ontology/concept-pair-co-occurrence))
            "the survivor emitted its co-occurrence event via the command")))))

(deftest cascade-command-projects-once-when-state-supplied
  (testing "The run-dedup-cascade command accepts pre-projected
            `:disjointness` + `:existing-evidence` and uses them instead of
            re-projecting. Supplying a disjointness map that marks the pair
            disjoint yields a T1 :distinct verdict WITHOUT the command having
            to project the axioms read-model itself — this is the
            project-once contract the stage relies on."
    (with-ctx [ctx]
      (let [oid (random-uuid)
            r (cp/process-command
               (assoc ctx :command
                      {:command/name :ontology/run-dedup-cascade
                       :command/id (random-uuid)
                       :command/timestamp (time/now)
                       :ontology-id oid
                       :a {:uri "bio:Whale" :label "Whale" :type :class
                           :broader ["bio:Mammal"]}
                       :b {:uri "bio:Croc" :label "Whale" :type :class
                           :broader ["bio:Reptile"]}
                       ;; pre-projected disjointness (stage hoists this once)
                       :disjointness {"bio:Mammal" #{"bio:Reptile"}
                                      "bio:Reptile" #{"bio:Mammal"}}
                       :existing-evidence {}}))
            v (get-in r [:command-result/data :verdict])]
        (is (= :distinct (:verdict v)))
        (is (= :disjointness-guard (:tier v))
            "the supplied disjointness map drove the T1 verdict — no re-projection")))))
