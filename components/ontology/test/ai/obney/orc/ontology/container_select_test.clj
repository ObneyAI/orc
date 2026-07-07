(ns ai.obney.orc.ontology.container-select-test
  "MT-2 — survey-driven relevance rank + bounded container selection. Pure +
   injectable: the sampler and the LLM rank are INJECTED capabilities, faked here,
   so every tracer unit-tests with NO live LLM and NO real source file. The LIVE
   proof (real O*NET selects the occupation-centric tables, junctions dropped, goal
   discriminates the rank order) is the /inspect-orc, not a unit test."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.core.container-select :as sel]
            [ai.obney.orc.ontology.core.vocabulary-binding :as vb]))

;; ---------------------------------------------------------------------------
;; A controlled 4-container source, addressed through the UNIFORM container
;; contract via a FAKE list-fn / sample-fn (no live source). Each container's
;; fake rows carry the REAL structural signal MT-1's classifier separates on:
;;   ent  — a unique NON-numeric key + attributes → :entity, kept
;;   long — a repeating key + element + numeric value → :long-form, kept
;;   brdg — two code-pair columns, NO measure, NO unique key → :bridge, dropped
;;   tiny — a small lookup dictionary (fewer rows than the over-sample limit) →
;;          :reference, dropped (the over-sample→row-count path catches it)
;; ---------------------------------------------------------------------------

(defn- rows
  "n sample rows from per-column generators (header col -> idx -> value)."
  [header n gens]
  (mapv (fn [i] (into {} (map (fn [h] [h ((gens h) i)]) header))) (range n)))

(def ^:private ent-rows
  (rows ["ent-key" "ent-title" "ent-desc"] 64
        {"ent-key"   #(str "E-" %)              ; unique per row → key
         "ent-title" #(str "title-" (mod % 10))
         "ent-desc"  #(str "desc-" (mod % 7))}))

(def ^:private long-rows
  (rows ["long-key" "long-elem" "long-scale" "long-value" "long-err"] 64
        {"long-key"   (fn [_] "K-1")            ; near-constant repeating key
         "long-elem"  #(str "elem-" (mod % 15))
         "long-scale" #(if (even? %) "IM" "LV")
         "long-value" #(str (+ 1.0 (* 0.13 %))) ; numeric ~unique → the value
         "long-err"   #(str (* 0.01 %))}))

(def ^:private bridge-rows
  (rows ["a-id" "a-name" "b-id" "b-name"] 64
        {"a-id"   #(str "A-" (mod % 3))
         "a-name" #(str "a-" (mod % 3))
         "b-id"   #(str "B-" (mod % 24))
         "b-name" #(str "b-" (mod % 24))}))

(def ^:private tiny-rows
  ;; a genuine tiny lookup dictionary: only 32 rows exist (fewer than the 64-row
  ;; over-sample), so the sampler returns them ALL → row-count 32 → :reference.
  (rows ["tiny-code" "tiny-label"] 32
        {"tiny-code"  #(str "S-" %)             ; unique but tiny
         "tiny-label" #(str "scale-" %)}))

(def ^:private fake-containers
  [{:name "ent"}  {:name "long"} {:name "brdg"} {:name "tiny"}])

(def ^:private fake-samples
  {"ent" ent-rows "long" long-rows "brdg" bridge-rows "tiny" tiny-rows})

(defn- fake-list-fn [_source] fake-containers)

(defn- fake-sample-fn [_source container {:keys [limit]}]
  ;; honor the over-sample limit exactly as the real contract would: return up to
  ;; `limit` rows for the container (a table with fewer rows returns all of them).
  (vec (take (or limit 100) (get fake-samples (:name container)))))

(deftest classify-source-containers-tags-each-shape-via-injected-sampler-test
  (testing "classify-source-containers over-samples each container via the injected
            sampler, builds the header from the row keys, and carries MT-1's
            :shape/:keep?/:roles verdict — meaningful shapes kept, noise dropped"
    (let [out (sel/classify-source-containers
               {:type :excel :path "/does/not/matter"}
               {:list-fn fake-list-fn :sample-fn fake-sample-fn :sample-limit 64})
          by-name (into {} (map (juxt :name identity) out))]
      (is (= 4 (count out)) "every container in the source is classified")
      ;; entity — a unique non-numeric key → :entity, KEPT, key role identified
      (let [e (by-name "ent")]
        (is (= :entity (:shape e)))
        (is (true? (:keep? e)) "a real entity table is kept")
        (is (= "ent-key" (get-in e [:roles :key])) "the unique key column is the role"))
      ;; long-form — repeating key + numeric value → :long-form, KEPT
      (let [l (by-name "long")]
        (is (= :long-form (:shape l)))
        (is (true? (:keep? l)))
        (is (= "long-key" (get-in l [:roles :key])) "the repeating key is the entity key")
        (is (some? (get-in l [:roles :value])) "a numeric value column is identified"))
      ;; bridge — no key, no measure → :bridge, DROPPED
      (let [b (by-name "brdg")]
        (is (= :bridge (:shape b)))
        (is (false? (:keep? b)) "a junction bridge is structural noise — dropped"))
      ;; tiny reference — the over-sample→row-count path caught the small dictionary
      (let [t (by-name "tiny")]
        (is (= 32 (:row-count t))
            "the over-sample returned FEWER than the limit → the exact tiny count")
        (is (= :reference (:shape t)))
        (is (false? (:keep? t)) "a tiny lookup dictionary is dropped"))
      ;; the ORIGINAL container map is carried forward (for the orchestrator's
      ;; medium-specific addressing + MT-3's shape tag).
      (is (= {:name "ent"} (:container (by-name "ent")))
          "the original container map is carried on :container"))))

;; ---------------------------------------------------------------------------
;; REGRESSION (live-caught) — the real uniform `:sample-rows` returns a WRAPPER map
;; `{:rows [{col val} …] :row-count N …}`, NOT a bare vector. `normalize-sample-result`
;; must unwrap `:rows`; a bare vector passes through; a `{:error …}`/nil → []. This is
;; the shape the /inspect-orc live run hit (ClassCastException calling `keys` on a
;; MapEntry) — guarded here so it can never silently regress.
;; ---------------------------------------------------------------------------

(deftest normalize-sample-result-unwraps-the-real-contract-wrapper-test
  (testing "the real {:rows … :row-count …} wrapper is unwrapped to its row-maps; a
            bare vector passes through; an error/nil result degrades to []"
    ;; the REAL excel/sql/csv shape — rows nested under :rows, alongside :row-count/:header.
    (is (= [{"a" 1} {"a" 2}]
           (sel/normalize-sample-result {:rows [{"a" 1} {"a" 2}] :row-count 2 :header ["a"]}))
        "the wrapper's :rows is unwrapped to the bare row-map vector")
    ;; a bare vector of row-maps (a compliant injected fake) passes through unchanged.
    (is (= [{"a" 1}] (sel/normalize-sample-result [{"a" 1}]))
        "a bare vector of row-maps passes through")
    ;; an {:error …} marker (no :rows) and nil both degrade to [] (unreadable → dropped).
    (is (= [] (sel/normalize-sample-result {:error "boom" :row-count 0})))
    (is (= [] (sel/normalize-sample-result nil)))
    ;; any stray non-map element is filtered out (defensive — never a keys-on-keyword crash).
    (is (= [{"a" 1}] (sel/normalize-sample-result {:rows [{"a" 1} :stray nil]})))))

;; ---------------------------------------------------------------------------
;; TRACER 2 — select-containers PRE-FILTER (drop noise with reasons) + BOUND (take
;; cap), with a fake rank-fn that keeps the survivors in list order (identity).
;; ---------------------------------------------------------------------------

(defn- candidate
  "A classify-source-containers-shaped candidate (as tracer 1 produces)."
  [name shape keep? & [roles]]
  {:name name :container {:name name :path "/wb" :sheet name}
   :shape shape :keep? keep? :roles roles :header [] :row-count 100})

(def ^:private mixed-candidates
  [(candidate "ent-a"  :entity     true  {:key "k"})
   (candidate "brdg"   :bridge     false)
   (candidate "long-b" :long-form  true  {:key "k" :value "v"})
   (candidate "tiny"   :reference  false)
   (candidate "ent-c"  :entity     true  {:key "k"})])

(defn- identity-rank-fn
  "A stand-in for the LLM rank that returns the survivor NAMES in list order (the
   honest degrade order) — so tracer 2 isolates the pre-filter + bound, not the rank."
  [_goal survivors]
  (mapv :name survivors))

(deftest select-containers-string-rank-output-degrades-to-list-order-not-char-iterated-test
  (testing "a rank-fn that returns a STRING (a degraded :delegate-crossing coverage map
            that reached the pure fn unparsed) is NOT char-iterated into bogus per-
            character entries — the sequential? guard treats it as no-rank → honest
            survivor list order + take-cap. (The production seam also coerces the read-
            back; this guards the pure fn against a direct string.)"
    (let [string-rank-fn (fn [_g _s] "[{:name \"ent-a\" :serves-cqs [0]}]")
          out (sel/select-containers mixed-candidates
                                     {:goal "g" :cqs ["cq0"] :cap 2 :rank-fn string-rank-fn})
          names (mapv :name (:selected out))]
      (is (= 2 (count names)) "bounded to cap")
      (is (every? #{"ent-a" "long-b" "ent-c"} names)
          "selected are REAL survivor names in list order — NOT single-character names
           from iterating the string")
      (is (= {:total-cqs 1 :covered [] :uncovered [0] :complete? false}
             (get-in out [:report :cq-coverage]))
          "no coverage from a degraded string — honestly uncovered, not faked"))))

;; ---------------------------------------------------------------------------
;; CONNECT-1 (the LOAD-BEARING tracer) — the coverage RANKING must survive the C1
;; `:delegate`-crossing key mangling. When the ranker's coverage map crosses
;; `:delegate` its keyword keys arrive as `(keyword ":name")` (prints `::name`);
;; `(:name entry)` then reads nil, so select-containers' name-reconciliation drops
;; the ENTIRE ranking and containers fall back to survivor/alphabetical list order
;; — the occupation-connecting sheets (Task Statements/Skills/Knowledge) never make
;; the budget cut → disconnected graph. Feeding the mangled coverage map through
;; the PRODUCTION normalization (vb/keywordize-entry-keys — the ONE shared fix)
;; must restore RELEVANCE order: a high-relevance LATE-alphabet name ranks ABOVE
;; an early-alphabet low one, NOT alphabetical.
;; ---------------------------------------------------------------------------

(defn- mangle-keys
  "Reproduce the C1 crossing shape on a coverage-map entry: every key's NAME
   gets a literal leading colon, so `:name` → `(keyword \":name\")`."
  [m]
  (into {} (map (fn [[k v]] [(keyword (str ":" (name k))) v])) m))

(def ^:private ranking-candidates
  ;; two kept survivors in ALPHABETICAL (list) order — the order select-containers
  ;; falls back to when the ranking is dropped.
  [(candidate "Alpha Sheet"     :entity true {:key "k"})
   (candidate "Task Statements" :entity true {:key "k"})])

(deftest select-containers-coverage-ranking-survives-delegate-crossing-key-mangling-test
  (testing "CONNECT-1 LOAD-BEARING: a coverage map whose keys crossed as
            `(keyword \":name\")`/`(keyword \":serves-cqs\")`/`(keyword \":relevance\")`,
            normalized through the PRODUCTION path (vb/keywordize-entry-keys), yields
            select-containers' RELEVANCE order — Task Statements (high relevance,
            LATE alphabet) ranks ABOVE Alpha Sheet (low, early alphabet) — NOT the
            alphabetical/survivor-list order the dropped-ranking bug produces"
    (let [;; relevance-ranked coverage (vector order = relevance): the connecting
          ;; sheet FIRST, the low-relevance early-alphabet sheet second.
          clean-coverage [{:name "Task Statements" :serves-cqs [0] :relevance "high"}
                          {:name "Alpha Sheet"     :serves-cqs []  :relevance "low"}]
          ;; the C1 crossing shape, then repaired by the SHARED normalizer (exactly
          ;; the production seam's coverage read-back).
          crossed-coverage (mapv (comp #'vb/keywordize-entry-keys mangle-keys) clean-coverage)
          rank-fn (fn [_g _survivors] crossed-coverage)
          out (sel/select-containers ranking-candidates
                                     {:goal "connect occupations to skills/tasks"
                                      :cqs ["cq0"] :cap 25 :rank-fn rank-fn})
          names (mapv :name (:selected out))]
      (is (= ["Task Statements" "Alpha Sheet"] names)
          "the coverage map's RELEVANCE order survives the (keyword \":name\") crossing —
           the connecting sheet ranks first, the ranking was NOT dropped")
      (is (not= (vec (sort names)) names)
          "and the order is NOT alphabetical (the dropped-ranking failure signature)"))))

(deftest select-containers-prefilters-noise-and-bounds-honestly-test
  (testing "select-containers drops :keep? false containers (with the shape as the
            reason), keeps the survivors with their shape tags, applies the cap, and
            reports total-vs-survivors-vs-selected honestly"
    (let [out (sel/select-containers mixed-candidates
                                     {:goal "g" :cap 2 :rank-fn identity-rank-fn})
          selected (:selected out)
          dropped (:dropped out)
          report (:report out)]
      ;; PRE-FILTER — the two noise containers are dropped with the shape as reason.
      (is (= #{"brdg" "tiny"} (set (map :name dropped))) "both noise shapes dropped")
      (is (= :bridge (:reason (first (filter #(= "brdg" (:name %)) dropped))))
          "the drop reason is the structural shape (bridge)")
      (is (= :reference (:reason (first (filter #(= "tiny" (:name %)) dropped))))
          "the tiny reference's drop reason is its shape")
      ;; BOUND — cap 2 over 3 survivors → exactly 2 selected, in list order.
      (is (= 2 (count selected)) "the cap bounds the selected count")
      (is (= ["ent-a" "long-b"] (mapv :name selected))
          "the first cap survivors (list order via identity rank) are selected")
      ;; the selected entries carry the SHAPE tag (MT-3) + the medium addressing.
      (is (= :entity (:shape (first selected))) "the shape tag is carried forward")
      (is (= "/wb" (:path (first selected)))
          "the original container addressing (:path/:sheet) is carried for the child tick")
      (is (= {:key "k" :value "v"} (:roles (second selected)))
          "the roles are carried forward for MT-3")
      ;; HONEST report — no silent truncation.
      (is (= 5 (:containers-total report)) "the report counts ALL candidates")
      (is (= 3 (:survivors report)) "3 survived the structural pre-filter")
      (is (= 2 (:selected report)) "2 were selected under the cap")
      (is (= 2 (count (:dropped report))) "the dropped-with-reasons list is surfaced"))))

;; ---------------------------------------------------------------------------
;; TRACER 3 (the adversarial heart) — rank RECONCILIATION. The fake ranker REORDERS,
;; DROPS a real survivor, and INVENTS a name that is no survivor. Reconciliation must
;; keep only KNOWN names (invented ignored — no LLM-invented identity), preserve the
;; ranker's order for the names it did return, and APPEND the omitted survivor at the
;; END (never a silent drop).
;; ---------------------------------------------------------------------------

(def ^:private survivors-only
  [(candidate "s1" :entity    true {:key "k"})
   (candidate "s2" :long-form true {:key "k" :value "v"})
   (candidate "s3" :entity    true {:key "k"})
   (candidate "s4" :entity    true {:key "k"})])

(defn- adversarial-rank-fn
  "Returns names REORDERED (s3 before s1), OMITS s4 entirely, and INVENTS `ghost`
   (a name no survivor has). Honest reconciliation must survive all three."
  [_goal _survivors]
  ["s3" "ghost" "s1" "s2"])

(deftest select-containers-reconciles-rank-no-invented-identity-no-silent-drop-test
  (testing "the ranker's order is honored for KNOWN names, an INVENTED name is
            ignored (no LLM-fabricated container), and a survivor the ranker OMITTED
            is appended at the END (never silently dropped)"
    (let [out (sel/select-containers survivors-only
                                     {:goal "g" :cap 10 :rank-fn adversarial-rank-fn})
          names (mapv :name (:selected out))]
      ;; ghost is NOT a survivor → it must never appear (no invented identity).
      (is (not (some #{"ghost"} names)) "an LLM-invented name is IGNORED, never selected")
      ;; every real survivor is present exactly once (no silent drop, no duplication).
      (is (= #{"s1" "s2" "s3" "s4"} (set names)) "every survivor is present exactly once")
      (is (= (count names) (count (distinct names))) "no survivor is duplicated")
      ;; the ranker's order holds for the names it returned; the omitted s4 lands LAST.
      (is (= ["s3" "s1" "s2" "s4"] names)
          "ranked order for known names, then the omitted survivor appended at the END")
      (is (= "s4" (last names)) "the survivor the ranker omitted is appended, not dropped")
      (is (= 4 (:survivors (:report out))) "the report still counts all 4 survivors honestly"))))

;; A NIL / failed rank degrades HONESTLY to list order (the surfaced-reason path the
;; central seam takes when the rank :delegate fails — no silent scramble).
(deftest select-containers-nil-rank-degrades-to-list-order-test
  (testing "a nil rank-fn (or one that returns nothing) keeps the survivors in their
            original list order — the honest degrade, not a silent reshuffle"
    (let [out (sel/select-containers survivors-only {:goal "g" :cap 10 :rank-fn nil})]
      (is (= ["s1" "s2" "s3" "s4"] (mapv :name (:selected out)))
          "list order is preserved when there is no ranker"))))

;; ===========================================================================
;; MT-12 — CQ-COVERAGE-AWARE selection: bounded promotion over a coverage map.
;; A larger survivor pool c0..c7 (runtime names only, NO domain literals) and a
;; coverage-map rank-fn (the SLICE-2 output shape): a vector of
;; {:name … :serves-cqs [<idx> …] :relevance …}, vector order = relevance rank.
;; ===========================================================================

(defn- cov-candidate
  "A survivor candidate (all :keep? true) named by its runtime id — no domain name."
  [name]
  {:name name :container {:name name :path "/wb" :sheet name}
   :shape :entity :keep? true :roles {:key "k"} :header [] :row-count 100})

(def ^:private eight-survivors
  (mapv cov-candidate ["c0" "c1" "c2" "c3" "c4" "c5" "c6" "c7"]))

;; Five generic competency questions (runtime strings; the code reasons on INDICES).
(def ^:private five-cqs
  ["cq-0" "cq-1" "cq-2" "cq-3" "cq-4"])

(defn- coverage-rank-fn
  "Returns a coverage MAP (the SLICE-2 shape): each survivor mapped to the CQ indices
   it serves, in a fixed relevance order = the given `cov-spec` order. `cov-spec` is
   [[name [cq-idx …]] …]."
  [cov-spec]
  (fn [_goal _survivors]
    (mapv (fn [[nm idxs]] {:name nm :serves-cqs (vec idxs) :relevance "high"}) cov-spec)))

(deftest select-containers-promotes-a-starved-facet-cq-into-selection-test
  (testing "at cap=6 the base take starves the only container serving CQ 3 (ranked
            7th), but bounded promotion over the coverage map LIFTS it into
            :selected; :promoted records it :for-cqs [3]; coverage is complete; and
            the selected count never exceeds the ceiling"
    (let [;; base c0..c5 covers CQs {0,1,2,4}; ONLY c6 (rank idx 6, beyond cap) serves CQ 3.
          cov-spec [["c0" [0]] ["c1" [1]] ["c2" [2]] ["c3" [0]]
                    ["c4" [1]] ["c5" [2 4]] ["c6" [3]] ["c7" [0]]]
          out (sel/select-containers eight-survivors
                                     {:goal "g" :cqs five-cqs :cap 6 :coverage-slack 8
                                      :rank-fn (coverage-rank-fn cov-spec)})
          names (mapv :name (:selected out))
          report (:report out)
          coverage (:cq-coverage report)]
      ;; the starved facet container is PROMOTED into the selection.
      (is (some #{"c6"} names) "the only container serving CQ 3 is promoted into :selected")
      ;; :promoted records the promotion honestly, with the facet CQ it was pulled for.
      (is (= [{:name "c6" :for-cqs [3]}] (:promoted report))
          "the promotion is surfaced with :for-cqs recording the uncovered CQ it served")
      ;; coverage is now COMPLETE — every CQ has a serving container in the selection.
      (is (true? (:complete? coverage)) "all five CQs are covered after promotion")
      (is (= [] (:uncovered coverage)) "no CQ is left uncovered")
      (is (= [0 1 2 3 4] (:covered coverage)) "every CQ index is covered")
      (is (= 5 (:total-cqs coverage)) "the CQ total is reported")
      ;; the HARD BOUND — selected never exceeds cap + slack.
      (is (<= (count names) (+ 6 8)) "selected count never exceeds cap + slack (the ceiling)")
      (is (= 7 (count names)) "base 6 + exactly one promotion for the single starved CQ"))))

(deftest select-containers-promotion-is-bounded-by-cap-plus-slack-test
  (testing "when MANY uncovered CQs are each served only by a distinct low-ranked
            container, promotion stops HARD at cap + slack; the still-uncovered CQs
            are SURFACED in :uncovered with :complete? false — never silently swallowed"
    (let [;; 8 survivors. base c0 (cap=1) covers CQ 0. CQs 1..4 are each served ONLY by
          ;; a distinct later container (c1→1, c2→2, c3→3, c4→4). slack=2 → ceiling=3 →
          ;; only TWO promotions possible; CQs 3 and 4 must remain uncovered.
          cov-spec [["c0" [0]] ["c1" [1]] ["c2" [2]] ["c3" [3]]
                    ["c4" [4]] ["c5" []] ["c6" []] ["c7" []]]
          out (sel/select-containers eight-survivors
                                     {:goal "g" :cqs five-cqs :cap 1 :coverage-slack 2
                                      :rank-fn (coverage-rank-fn cov-spec)})
          names (mapv :name (:selected out))
          report (:report out)
          coverage (:cq-coverage report)]
      ;; the HARD BOUND — selected is EXACTLY cap + slack, never more.
      (is (= (+ 1 2) (count names)) "selected count == cap + slack exactly (the ceiling)")
      (is (<= (count names) (+ 1 2)) "selected count never exceeds cap + slack")
      ;; two promotions were made (c1, c2), lifting CQs 1 and 2.
      (is (= 2 (count (:promoted report))) "exactly slack-many promotions, no more")
      ;; the CQs promotion could NOT reach are surfaced honestly — not swallowed.
      (is (false? (:complete? coverage)) "coverage is honestly INCOMPLETE, not faked")
      (is (= [3 4] (:uncovered coverage)) "the unreachable CQs are surfaced in :uncovered")
      (is (= [0 1 2] (:covered coverage)) "only the reachable CQs are marked covered"))))

(deftest select-containers-honest-truncation-surfaces-over-cap-drops-test
  (testing "with more survivors than cap and every CQ already covered by the base take,
            NOTHING is promoted, the surplus survivors are TRUNCATED, and each cut is
            surfaced in :over-cap-dropped with :reason :over-cap + its 0-based :rank"
    (let [;; base c0..c3 (cap=4) already covers all five CQs; c4..c7 are surplus.
          cov-spec [["c0" [0 1]] ["c1" [2]] ["c2" [3]] ["c3" [4]]
                    ["c4" [0]] ["c5" [1]] ["c6" [2]] ["c7" [3]]]
          out (sel/select-containers eight-survivors
                                     {:goal "g" :cqs five-cqs :cap 4 :coverage-slack 8
                                      :rank-fn (coverage-rank-fn cov-spec)})
          names (mapv :name (:selected out))
          report (:report out)
          over (:over-cap-dropped report)]
      ;; base already covers every CQ → NO promotion.
      (is (= [] (:promoted report)) "nothing is promoted — the base already covers all CQs")
      (is (true? (:complete? (:cq-coverage report))) "coverage complete from the base alone")
      ;; the cap truncates — only 4 selected, honestly flagged.
      (is (= 4 (count names)) "the cap bounds the selection to 4")
      (is (true? (:containers-truncated? report)) "truncation is honestly flagged")
      ;; every surplus survivor is surfaced with its reason + rank (no silent drop).
      (is (= #{"c4" "c5" "c6" "c7"} (set (map :name over))) "all four surplus survivors surfaced")
      (is (every? #(= :over-cap (:reason %)) over) "each cut's reason is :over-cap")
      (is (= #{4 5 6 7} (set (map :rank over))) "each cut carries its 0-based rank in the ordering")
      (is (= :entity (:shape (first over))) "the cut survivor's shape is carried for the record"))))

(deftest select-containers-reconciles-coverage-map-invented-omitted-out-of-range-test
  (testing "a coverage map is reconciled against known survivors: an INVENTED name is
            ignored, an OMITTED survivor is appended at the END with :serves-cqs [],
            and an OUT-OF-RANGE :serves-cqs index is dropped (never invents a CQ)"
    (let [survivors (mapv cov-candidate ["c0" "c1" "c2"])
          ;; c1 carries an out-of-range CQ index (9, valid range is [0,3)) + a valid 2.
          ;; "ghost" is no survivor. c2 is OMITTED entirely by the ranker.
          rank-fn (fn [_goal _survivors]
                    [{:name "c1" :serves-cqs [0 9 2] :relevance "high"}
                     {:name "ghost" :serves-cqs [1] :relevance "high"}
                     {:name "c0" :serves-cqs [1] :relevance "medium"}])
          out (sel/select-containers survivors
                                     {:goal "g" :cqs ["cq-0" "cq-1" "cq-2"] :cap 10
                                      :coverage-slack 8 :rank-fn rank-fn})
          names (mapv :name (:selected out))
          coverage (:cq-coverage (:report out))]
      ;; the invented name never appears; every real survivor is present exactly once.
      (is (not (some #{"ghost"} names)) "the invented name is ignored — no fabricated identity")
      (is (= #{"c0" "c1" "c2"} (set names)) "every survivor present exactly once")
      ;; ranker order for known names (c1, c0), then the omitted c2 appended at the END.
      (is (= ["c1" "c0" "c2"] names) "known order preserved; the omitted survivor appended last")
      ;; the out-of-range index 9 is DROPPED; only valid CQ indices remain covered.
      ;; covered = c1{0,2} ∪ c0{1} ∪ c2{} = {0,1,2}; index 9 never leaks in.
      (is (= [0 1 2] (:covered coverage)) "only valid CQ indices are covered — the out-of-range 9 dropped")
      (is (not (some #{9} (:covered coverage))) "the out-of-range index never leaks into coverage")
      (is (true? (:complete? coverage)) "the three valid CQs are fully covered"))))

(deftest select-containers-back-compat-no-cqs-is-exactly-take-cap-test
  (testing "the no-CQ / old-caller path is EXACTLY today's take-cap behavior: nil cqs
            → no promotion, selected = take cap, :cq-coverage :total-cqs 0 :complete?
            true; a flat NAME vector ranker is tolerated (each :serves-cqs []); a nil
            ranker degrades to list order — all with no coverage signal"
    ;; (a) nil cqs, coverage-map ranker present but IGNORED for promotion → pure take-cap.
    (let [cov-spec [["c0" [0]] ["c1" [1]] ["c2" [2]] ["c3" [3]]
                    ["c4" [4]] ["c5" [0]] ["c6" [1]] ["c7" [2]]]
          out (sel/select-containers eight-survivors
                                     {:goal "g" :cqs nil :cap 6
                                      :rank-fn (coverage-rank-fn cov-spec)})
          coverage (:cq-coverage (:report out))]
      (is (= 6 (count (:selected out))) "nil cqs → selected = take cap (today's behavior)")
      (is (= ["c0" "c1" "c2" "c3" "c4" "c5"] (mapv :name (:selected out)))
          "the first cap survivors in rank order — no promotion pulls a later one")
      (is (= [] (:promoted (:report out))) "no CQs → nothing promoted")
      (is (= 0 (:total-cqs coverage)) ":cq-coverage :total-cqs is 0 with no CQs")
      (is (true? (:complete? coverage)) "vacuously complete when there are no CQs"))
    ;; (b) a flat NAME-vector ranker (the OLD output) is tolerated → take-cap.
    (let [flat-rank (fn [_goal survivors] (mapv :name survivors))
          out (sel/select-containers eight-survivors
                                     {:goal "g" :cqs five-cqs :cap 3 :rank-fn flat-rank})]
      (is (= ["c0" "c1" "c2"] (mapv :name (:selected out)))
          "a flat name vector is tolerated (each :serves-cqs []) → take-cap")
      (is (= [] (:promoted (:report out))) "no coverage signal from a flat vector → no promotion"))
    ;; (c) a nil ranker degrades to list order, take-cap.
    (let [out (sel/select-containers eight-survivors
                                     {:goal "g" :cqs five-cqs :cap 3 :rank-fn nil})]
      (is (= ["c0" "c1" "c2"] (mapv :name (:selected out)))
          "a nil ranker keeps survivors in list order, take-cap"))))
