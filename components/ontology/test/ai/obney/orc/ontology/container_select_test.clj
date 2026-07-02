(ns ai.obney.orc.ontology.container-select-test
  "MT-2 — survey-driven relevance rank + bounded container selection. Pure +
   injectable: the sampler and the LLM rank are INJECTED capabilities, faked here,
   so every tracer unit-tests with NO live LLM and NO real source file. The LIVE
   proof (real O*NET selects the occupation-centric tables, junctions dropped, goal
   discriminates the rank order) is the /inspect-orc, not a unit test."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.core.container-select :as sel]))

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
