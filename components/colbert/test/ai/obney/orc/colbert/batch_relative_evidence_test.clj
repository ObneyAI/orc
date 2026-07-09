(ns ai.obney.orc.colbert.batch-relative-evidence-test
  "Slice 3, cycle 4 (evidence): the normalization-variant comparison on the
   REAL encoder — 4 probe candidate/task sets x 3 normalization variants.

   The empirical problem (P-0 findings 'Scores, bound, and range' + Slice-1
   inspection): answerai MaxSim scores live in ~[27.5, 29.3] on guard-vs-task
   probes (MASK query expansion gives even unrelated pairs a high floor), so a
   FIXED-ceiling linear normalization compresses the contrastive
   domain-penalty margin (~0.012-0.021 at /40). This test derives, on the live
   JVM encoder, the cos-avoid/cos-good margin for each probe set under
     (a) /40 linear (the old default)
     (b) /32 linear (the re-derived theoretical ceiling)
     (c) batch-relative (normalize by the call's max raw score — the shipped
         domain-penalty default)
   and pins the witnessed properties:
     - batch-relative widens |margin| beyond BOTH linear variants on every set
     - NO clear case inverts (signs agree across all three variants)
     - separability: the force-fit margin exceeds every must-not-fire margin
   It also prints the full table (the slice's evidence artifact).

   Probe strings are the el5 separability probe's vocabulary (the semantics
   Slice 4's re-run judges), extended with a mixed and an irrelevant-guards
   set. Uses the local checkpoint via the support fixture — no network."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ai.obney.orc.colbert.colbert-test-support :as support]
            [ai.obney.orc.colbert.core.operations :as operations]))

(use-fixtures :once support/with-model-path)

(def refactor-task
  "INSTRUCTION:\nRefactor the order service to extract a pure pricing helper from the request handler, preserving existing behavior and keeping all current tests green.\n\nREADS: :user-message :active-plan :workspace-root\nWRITES: :assistant-response\nMCP-TOOLS: shell/exec fs/read fs/list\nBROWSER-TOOLS: (none)")

(def websearch-task
  "INSTRUCTION:\nSearch the web for the latest documentation on the payment API and summarize what you find.\n\nREADS: :user-message\nWRITES: :assistant-response\nMCP-TOOLS: web/search\nBROWSER-TOOLS: (none)")

(def rename-avoid
  ["the task changes behavior or adds functionality — that is code-building, not a behavior-preserving rename/move"
   "the change is a data reshape rather than an identity refactor of code symbols — that is the Transformation behavior"
   "the task is to extract a helper, pull out a function, or otherwise refactor/restructure code — that is code-building/refactor, NOT a pure identity rename or move of an existing symbol"
   "the symbol is referenced beyond the file that defines it"
   "the task is strictly a rename/move with behavior preserved"])
(def rename-good
  ["Rename-move-symbol is a behavior-preserving, EXHAUSTIVE cross-file identity refactor — rename a function everywhere, move a symbol to another namespace — changing NOTHING else."
   "the symbol is referenced from multiple files including tests"
   "a build or test command exists to confirm exhaustiveness and behavior preservation"])

(def websearch-avoid
  ["the web search requires special elevated permissions or authenticated access the agent does not hold"
   "the task is purely computational with no external lookup need"])
(def websearch-good
  ["Web-search gathers fresh external information by issuing search queries and reading results to ground downstream work."
   "the task needs current external information not already in context"])

(def codebuild-avoid
  ["the task is a one-line configuration tweak with no new code structure"
   "the task is strictly a behavior-preserving rename or move of an existing symbol"])
(def codebuild-good
  ["Code-building builds or restructures code from a spec — extract a pure helper from a handler, refactor structure, add new functions."
   "extract a pure helper from a request handler while keeping tests green"])

(def irrelevant-avoid
  ["the recipe requires ingredients that are out of season at the local market"
   "the storm forecast makes outdoor cooking unsafe this weekend"])
(def irrelevant-good
  ["Sourdough-baking proofs and bakes artisan loaves with a wild yeast starter."
   "the oven can hold two hundred twenty degrees for forty minutes"])

(def probe-sets
  [{:label "clearly-good" :fires? false
    :task websearch-task :avoid websearch-avoid :good websearch-good}
   {:label "clearly-avoid (force-fit)" :fires? true
    :task refactor-task :avoid rename-avoid :good rename-good}
   {:label "mixed (correct parent)" :fires? false
    :task refactor-task :avoid codebuild-avoid :good codebuild-good}
   {:label "irrelevant-guards" :fires? false
    :task refactor-task :avoid irrelevant-avoid :good irrelevant-good}])

(defn- probe-margins
  "ONE rerank call over avoid ++ good (the colbert-rerank-scores idiom), then
   the three normalization variants. Returns {:label :fires? :raw {...}
   :margins {:linear-40 :linear-32 :batch-relative}}."
  [{:keys [label fires? task avoid good]}]
  (let [docs (vec (distinct (concat avoid good)))
        res (operations/rerank {} {:query task :documents docs})
        by-content (into {} (map (juxt :content :score)) res)
        a-raw (apply max (keep by-content avoid))
        g-raw (apply max (keep by-content good))
        call-max (apply max (vals by-content))]
    {:label label
     :fires? fires?
     :raw {:avoid a-raw :good g-raw :call-max call-max}
     :margins {:linear-40 (- (/ a-raw 40.0) (/ g-raw 40.0))
               :linear-32 (- (min 1.0 (/ a-raw 32.0)) (min 1.0 (/ g-raw 32.0)))
               :batch-relative (- (/ a-raw call-max) (/ g-raw call-max))}}))

(deftest batch-relative-evidence-on-the-real-encoder
  (let [rows (mapv probe-margins probe-sets)]
    ;; The evidence artifact — printed so every run carries the table.
    (println "\n=== SLICE 3 NORMALIZATION EVIDENCE (live answerai-colbert-small-v1) ===")
    (println (format "%-28s %10s %10s | %11s %11s %15s"
                     "probe set" "raw-avoid" "raw-good"
                     "(a) /40 lin" "(b) /32 lin" "(c) batch-rel"))
    (doseq [{:keys [label raw margins]} rows]
      (println (format "%-28s %10.4f %10.4f | %+11.4f %+11.4f %+15.4f"
                       label (:avoid raw) (:good raw)
                       (:linear-40 margins) (:linear-32 margins)
                       (:batch-relative margins))))
    (println)
    (testing "batch-relative widens |margin| beyond both fixed-ceiling variants on every set"
      (doseq [{:keys [label margins]} rows]
        (is (> (Math/abs (:batch-relative margins)) (Math/abs (:linear-32 margins)))
            (str label ": |batch-relative| > |/32 linear|"))
        (is (> (Math/abs (:batch-relative margins)) (Math/abs (:linear-40 margins)))
            (str label ": |batch-relative| > |/40 linear|"))))
    (testing "no clear case inverts: margin signs agree across all three variants"
      (doseq [{:keys [label margins]} rows]
        (let [signs (map #(Math/signum (double %)) (vals margins))]
          (is (apply = signs) (str label ": same sign under all variants")))))
    (testing "separability under batch-relative: the force-fit margin tops every
              must-not-fire margin (witnessed band: clean <= +0.0025 < +0.0160)"
      (let [fire (first (filter :fires? rows))
            clean (remove :fires? rows)]
        (is (> (get-in fire [:margins :batch-relative]) 0.01)
            "the force-fit case has a positive, non-noise margin")
        (doseq [{:keys [label margins]} clean]
          (is (< (:batch-relative margins) 0.005)
              (str label ": must-not-fire margin stays below the force-fit band")))))))
