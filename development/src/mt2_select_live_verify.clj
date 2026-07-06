(ns mt2-select-live-verify
  "MT-2 /inspect-orc — LIVE, adversarial verification of the survey-driven container
   SELECTION seam on the REAL O*NET source, with a REAL LLM relevance rank. No mocks:
   real Grain ctx (the eb12 harness), real OpenRouter rank sheet, real db_30_1_excel.

   Proves the MT-2 acceptance criteria a unit test cannot:
     1. On real O*NET at the dev cap, the SELECTED containers are the occupation-
        centric ones (Occupation Data / Skills / Knowledge / …), NOT the junction
        bridges (Abilities to Work Activities / Work Context) or tiny references.
     2. Two goals emphasizing DIFFERENT facets reorder the rank (the LLM relevance
        actually discriminates — not a rubber-stamp that returns list order).
     3. The selection is reported HONESTLY: total-vs-selected + drop reasons + the
        rank-degraded flag (so a silently-degraded rank is VISIBLE, not a false-green).

   USAGE (bounded CLI): clj -M:dev:test -m mt2-select-live-verify
   or REPL: (require '[mt2-select-live-verify :as m]) (m/run!)"
  (:require [eb12-graph-b-central-evolver :as h]
            [ai.obney.orc.ontology.core.central-evolver :as ce]
            [ai.obney.orc.ontology.core.container-select :as csel]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

(def onet {:type :excel :path h/onet-dir})

;; two goals that emphasize DIFFERENT facets, to prove the rank discriminates.
(def goal-skills
  (str "Build an ontology of OCCUPATIONS and the SKILLS and ABILITIES they require "
       "— what a worker must be able to DO. Focus on the competency/skill/ability "
       "profile of each occupation."))

(def goal-education
  (str "Build an ontology of the EDUCATION, TRAINING, and JOB-PREPARATION pathways "
       "into occupations — the experience, schooling, and job-zone requirements that "
       "qualify a person for each occupation."))

;; MT-12 — a hand-authored CQ set for the COVERAGE arm (production threads the
;; STEP-3-derived CQs; this reviewer arm hand-authors them for a deterministic probe).
;; Includes a JOB-ZONE CQ (idx 2) + an INTERESTS CQ (idx 3) — the two facets today's
;; flat rank STARVES at cap=6, so bounded coverage-promotion must LIFT them.
(def coverage-cqs
  ["What skills does a given occupation require?"
   "What knowledge areas does a given occupation require?"
   "What job zone (level of education/experience preparation) is a given occupation in?"
   "What vocational interests (RIASEC categories) are associated with a given occupation?"
   "What are the core work activities of a given occupation?"])

(defn- summarize-classify
  "Deterministic classify (no LLM) over ALL real O*NET containers — the structural
   pre-filter picture, so we can SEE what survives vs is dropped and why."
  []
  (let [candidates (csel/classify-source-containers onet {})]
    {:total (count candidates)
     :by-shape (frequencies (map :shape candidates))
     :kept (->> candidates (filter :keep?) (mapv (juxt :name :shape :row-count)))
     :dropped (->> candidates (remove :keep?) (mapv (juxt :name :shape :row-count)))}))

(defn- run-select [ctx goal label]
  (println "\n>>> delegate-select-containers! —" label)
  (let [t0 (System/currentTimeMillis)
        r (ce/delegate-select-containers!
           ctx {:source onet :goal goal :model h/default-model :max-containers 6})
        elapsed (- (System/currentTimeMillis) t0)
        selected (:selected-containers r)]
    (println "    (" elapsed "ms)  selected:" (count selected) " report:")
    (pp/pprint (:selection-report r))
    (println "    SELECTED (in rank order):")
    (doseq [c selected] (println "      -" (:name c) " shape=" (:shape c)))
    {:label label
     :selected-order (mapv :name selected)
     :selected-shapes (mapv :shape selected)
     :report (:selection-report r)}))

(defn- occupation-centric? [nm]
  ;; the occupation-centric tables the goal wants (for the adversarial verdict ONLY —
  ;; NOT used in production code; this is the reviewer naming what "good" looks like).
  (boolean (re-find #"(?i)occupation data|skills|knowledge|abilities|interests|work activit|work context|work styles|work values|education|job zone|task"
                    (str nm))))

(defn- junction-or-tiny? [nm]
  (boolean (re-find #"(?i) to |reference|categories|content model|scales|level scale|survey booklet|read me|version|taxonomy" (str nm))))

(defn run! []
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY env var required (env only)" {})))
  (h/register-openrouter! h/default-model)
  ;; make-ctx / stop-ctx are private (defn-) in the eb12 harness — call via their vars.
  (let [ctx ((deref #'h/make-ctx) {:store :in-memory})]
    (try
      (println "=== MT-2 SELECT LIVE VERIFY — real O*NET, real LLM rank ===")
      (println "O*NET dir:" h/onet-dir)
      (println "\n>>> deterministic structural classify (pre-filter picture, NO LLM):")
      (let [cl (summarize-classify)]
        (println "    total containers:" (:total cl) " by-shape:" (:by-shape cl))
        (println "    KEPT (survivors):")
        (doseq [k (:kept cl)] (println "      +" k))
        (println "    DROPPED (structural noise):")
        (doseq [d (:dropped cl)] (println "      -" d))
        (let [a (run-select ctx goal-skills "GOAL=skills/abilities")
              b (run-select ctx goal-education "GOAL=education/job-prep")]
          (println "\n=== ADVERSARIAL VERDICT ===")
          ;; AC1 — selected are occupation-centric, none are junction/tiny noise.
          (let [all-sel (distinct (concat (:selected-order a) (:selected-order b)))
                noise (filter junction-or-tiny? all-sel)
                occ (filter occupation-centric? all-sel)]
            (println "  AC1 selected-are-meaningful:")
            (println "     occupation-centric selected:" (vec occ))
            (println "     junction/tiny selected (SHOULD BE EMPTY):" (vec noise))
            (println "     => " (if (empty? noise) "PASS — no noise selected" "FAIL — noise leaked into selection")))
          ;; AC2 — the two goals reorder the selection (rank discriminates).
          (println "  AC2 rank-discriminates:")
          (println "     skills order   :" (:selected-order a))
          (println "     education order:" (:selected-order b))
          (println "     => " (if (not= (:selected-order a) (:selected-order b))
                                 "PASS — different goals produced different rank order"
                                 "SUSPECT — identical order for different goals (rubber-stamp? inspect reasoning)"))
          ;; AC3 — honest report: rank-degraded flag present + not silently degraded.
          (println "  AC3 honest-report:")
          (println "     skills report   :" (select-keys (:report a) [:containers-total :survivors :selected :rank-degraded :rank-degrade-reason]))
          (println "     education report:" (select-keys (:report b) [:containers-total :survivors :selected :rank-degraded :rank-degrade-reason]))
          (let [degraded? (or (:rank-degraded (:report a)) (:rank-degraded (:report b)))]
            (println "     => " (if degraded?
                                  "DEGRADED — the LLM rank fell back to list order; root-cause before trusting AC2"
                                  "PASS — real LLM rank ran (no degrade)")))
          (println "\n=== DONE ===")
          {:classify cl :skills a :education b}))
      (finally ((deref #'h/stop-ctx) ctx)))))

;; =============================================================================
;; MT-12 SLICE 2 — the COVERAGE arm. Real O*NET + real LLM coverage rank at cap=6
;; with a hand-authored CQ set (job-zone + interests). Proves the seam carries the
;; coverage signal end-to-end: the report's :cq-coverage / :promoted / :over-cap-
;; dropped are present, and the two facet tables that flat-rank starves are SELECTED
;; via bounded promotion. Facet NAMES are named ONLY in this reviewer measurement
;; (the adversarial verdict), NEVER in production code or the coverage prompt.
;; =============================================================================

(defn- coverage-goal []
  (str "Build an ontology of OCCUPATIONS and the skills, knowledge, job-zone "
       "requirements, and interests associated with each occupation."))

(defn run-coverage!
  "MT-12 coverage arm — call delegate-select-containers! with the hand-authored CQ
   set at cap=6 on real O*NET and adversarially inspect the coverage-aware selection.
   REPL: (require '[mt2-select-live-verify :as m]) (m/run-coverage!)"
  []
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY env var required (env only)" {})))
  (h/register-openrouter! h/default-model)
  (let [ctx ((deref #'h/make-ctx) {:store :in-memory})]
    (try
      (println "=== MT-12 COVERAGE ARM — real O*NET, real LLM coverage rank (cap=6) ===")
      (println "O*NET dir:" h/onet-dir)
      (println "\n>>> the numbered competency questions (0-based):")
      (doseq [[i q] (map-indexed vector coverage-cqs)] (println "   " i "." q))
      (let [t0 (System/currentTimeMillis)
            r (ce/delegate-select-containers!
               ctx {:source onet :goal (coverage-goal) :model h/default-model
                    :max-containers 6 :competency-questions coverage-cqs})
            elapsed (- (System/currentTimeMillis) t0)
            selected (:selected-containers r)
            report (:selection-report r)
            cov (:cq-coverage report)
            sel-names (mapv :name selected)
            ;; reviewer-only facet locate (NOT in the prompt/production).
            jobzone-nm (first (filter #(re-find #"(?i)job zone" (str %)) sel-names))
            interests-nm (first (filter #(re-find #"(?i)interest" (str %)) sel-names))]
        (println "\n    (" elapsed "ms)  selected:" (count selected))
        (println "    SELECTED (in rank order):")
        (doseq [c selected] (println "      -" (:name c) " shape=" (:shape c)))
        (println "    selection-report:")
        (pp/pprint report)
        (println "\n=== ADVERSARIAL VERDICT ===")
        ;; AC1 — the coverage report shape is present (the seam carried the signal).
        (let [has-coverage? (map? cov)
              has-promoted? (contains? report :promoted)
              has-overcap? (contains? report :over-cap-dropped)]
          (println "  AC1 report-carries-coverage-shape:")
          (println "     :cq-coverage      present? =>" has-coverage? " =>" (pr-str cov))
          (println "     :promoted         present? =>" has-promoted? " =>" (pr-str (:promoted report)))
          (println "     :over-cap-dropped present? =>" has-overcap? " =>" (pr-str (:over-cap-dropped report)))
          (println "     =>" (if (and has-coverage? has-promoted? has-overcap?)
                               "PASS — the coverage signal is carried end-to-end"
                               "FAIL — a coverage field is missing from the report")))
        ;; AC2 — the starved facet tables are SELECTED (bounded promotion lifted them).
        (println "  AC2 facet-tables-selected (job-zone + interests):")
        (println "     job-zone table selected  :" (pr-str jobzone-nm))
        (println "     interests table selected :" (pr-str interests-nm))
        (println "     =>" (if (and jobzone-nm interests-nm)
                             "PASS — both facet tables made the selection (promoted or ranked)"
                             "INSPECT — a facet table is absent; check :promoted / :cq-coverage :uncovered"))
        ;; AC3 — honest degrade flag (a silent LLM-degrade would fake AC2 via list order).
        (println "  AC3 honest-report:")
        (println "     rank-degraded:" (:rank-degraded report) " reason:" (:rank-degrade-reason report))
        (println "     =>" (if (:rank-degraded report)
                             "DEGRADED — the coverage rank fell back; root-cause before trusting AC2"
                             "PASS — the real LLM coverage rank ran (no degrade)"))
        (println "\n=== DONE ===")
        {:selected sel-names :report report})
      (finally ((deref #'h/stop-ctx) ctx)))))

(defn -main [& _]
  (let [r (run!)]
    (shutdown-agents)
    (System/exit 0)))

(defn -main-coverage [& _]
  (let [_ (run-coverage!)]
    (shutdown-agents)
    (System/exit 0)))
