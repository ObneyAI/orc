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
;; ⚠ CC-16 (ADR 0026) — READ THIS BEFORE CITING THIS PROBE AS EVIDENCE ABOUT
;; PRODUCTION. The first string below is a 176-char STUB: the first sentence of
;; the real rename-move-symbol summary. The REAL production summary is 796 chars
;; and ENDS by restating the behavior's own avoid-conditions in prose ("Avoid
;; when the task adds/changes behavior (code-building) or is a data reshape").
;; P-B measured, same query and same guards, one variable:
;;     176-char stub  -> batch-relative contrast +0.016049  (fires)
;;     796-char real  -> batch-relative contrast +0.002612  (inert)
;; So this probe's force-fit margin is a property of the STUB, not of production,
;; and it cannot detect a positive signal that cancels its own guard. It is left
;; unchanged because it pins the NORMALIZATION-VARIANT comparison it was built
;; for (which is stub-independent); the production-shaped contract lives in
;; ontology's cc16-positive-signal-test (deterministic, real measured scores)
;; and development/bench/cc16_shadow_rate.clj (real encoder, real corpus).
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

(def reference-limit
  "The maximum_query_tokens this Slice-3 evidence was captured at — the
   checkpoint's own query_maxlen. CC-17 turned that into configuration and
   moved the shipped default, so the historical comparison NAMES the
   configuration it belongs to (the golden-fixture treatment). The
   shipped-limit behaviour is asserted separately below."
  32)

(defn- probe-margins
  "ONE rerank call over avoid ++ good (the colbert-rerank-scores idiom), then
   the three normalization variants. Returns {:label :fires? :raw {...}
   :margins {:linear-loose :linear-ceiling :batch-relative}}.

   CC-17: the two fixed-ceiling divisors are DERIVED from the limit under test,
   not frozen at 40.0/32.0. The ceiling IS maximum_query_tokens; the 'loose'
   variant keeps the historical 40/32 = 1.25x over-estimate so the comparison
   means the same thing at any limit."
  [limit {:keys [label fires? task avoid good]}]
  (let [docs (vec (distinct (concat avoid good)))
        res (operations/rerank {} {:query task :documents docs
                                   :maximum-query-tokens limit})
        by-content (into {} (map (juxt :content :score)) res)
        a-raw (apply max (keep by-content avoid))
        g-raw (apply max (keep by-content good))
        call-max (apply max (vals by-content))
        ceiling (operations/maxsim-ceiling limit)
        loose (* 1.25 ceiling)]
    {:label label
     :fires? fires?
     :raw {:avoid a-raw :good g-raw :call-max call-max}
     :margins {:linear-loose (- (/ a-raw loose) (/ g-raw loose))
               :linear-ceiling (- (min 1.0 (/ a-raw ceiling)) (min 1.0 (/ g-raw ceiling)))
               :batch-relative (- (/ a-raw call-max) (/ g-raw call-max))}}))

(deftest batch-relative-evidence-on-the-real-encoder
  (let [rows (mapv (partial probe-margins reference-limit) probe-sets)]
    ;; The evidence artifact — printed so every run carries the table.
    (println "\n=== SLICE 3 NORMALIZATION EVIDENCE (live answerai-colbert-small-v1) ===")
    (println (format "%-28s %10s %10s | %11s %11s %15s"
                     "probe set" "raw-avoid" "raw-good"
                     "(a) loose lin" "(b) ceil lin" "(c) batch-rel"))
    (doseq [{:keys [label raw margins]} rows]
      (println (format "%-28s %10.4f %10.4f | %+11.4f %+11.4f %+15.4f"
                       label (:avoid raw) (:good raw)
                       (:linear-loose margins) (:linear-ceiling margins)
                       (:batch-relative margins))))
    (println)
    (testing "batch-relative widens |margin| beyond both fixed-ceiling variants on every set"
      (doseq [{:keys [label margins]} rows]
        (is (> (Math/abs (:batch-relative margins)) (Math/abs (:linear-ceiling margins)))
            (str label ": |batch-relative| > |ceiling linear|"))
        (is (> (Math/abs (:batch-relative margins)) (Math/abs (:linear-loose margins)))
            (str label ": |batch-relative| > |loose linear|"))))
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

;; =============================================================================
;; CC-17 — what the SHIPPED maximum_query_tokens does to this same evidence.
;;
;; MEASURED (do not assume). Raising the limit from the reference 32 to 464
;; adds [MASK] query-expansion rows; these probe TASKS are short (~70-90
;; word-piece tokens), so at 464 the pedestal is ~80% of the rows and every
;; raw score converges toward the same value. The batch-relative margins
;; therefore COMPRESS hard:
;;
;;   probe set                    batch-rel @32   batch-rel @464
;;   clearly-good                    -0.0190         -0.002978
;;   clearly-avoid (force-fit)       +0.0160         +0.000211
;;   mixed (correct parent)          -0.0283         -0.001806
;;   irrelevant-guards               +0.0025         -0.001544
;;
;; The SEPARABILITY ORDER survives — the force-fit is still the ONLY positive
;; margin — but the MAGNITUDE falls ~75x, which puts the force-fit far below
;; the shipped :margin 0.010 knob. That knob is NOT retuned here: it is a
;; gate-approved calibration (ADR 0016 / Slice-4 gate), and re-fitting it to
;; four short synthetic probes would be fitting to a regime the measured
;; production corpus does not contain (its shortest real query is 150 tokens;
;; on 20 REAL live-enriched candidates the contrast did not collapse, it
;; shifted ~0.005 more negative — doc/build-timeline/evidence/cc17).
;;
;; What this test PINS is the property that must not silently rot: at the
;; shipped limit the force-fit is still the only positive margin.
;; =============================================================================

(deftest separability-order-survives-the-shipped-limit
  (let [shipped (long (operations/maxsim-ceiling))
        rows (mapv (partial probe-margins shipped) probe-sets)
        fire (first (filter :fires? rows))
        clean (remove :fires? rows)]
    (println (format "\n=== CC-17: same probes at the SHIPPED limit %d ===" shipped))
    (doseq [{:keys [label margins]} rows]
      (println (format "%-28s batch-relative %+15.6f" label (:batch-relative margins))))
    (println "  [N] probe sets =" (count rows) "| fire =" 1 "| clean =" (count clean))
    (is (pos? (get-in fire [:margins :batch-relative]))
        "the force-fit margin stays POSITIVE at the shipped limit")
    (doseq [{:keys [label margins]} clean]
      (is (< (:batch-relative margins) (get-in fire [:margins :batch-relative]))
          (str label ": separability ORDER survives — clean below the force-fit")))))
