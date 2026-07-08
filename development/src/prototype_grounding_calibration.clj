(ns prototype-grounding-calibration
  "PA-3 HITL PROTOTYPE — throwaway calibration harness for the discrete 1-5
   grounding Scale. NOT part of the shipped component; lives under
   development/src and is excluded from the brick. Per discipline #2 it scores
   REAL traces with REAL live LLM calls (OpenRouter) — NO MOCKS.

   The traces are built from a REAL bench document
   (development/bench/documents/employment_agreement.txt). For each trace we
   craft a producer `response` that should land in a known band, then check
   whether the live judge agrees — calibrating the band wording.

   Run:
     export OPENROUTER_API_KEY=...
     clj -M:dev -e \"(require '[prototype-grounding-calibration :as p]) (p/run!)\"

   Or score a single ad-hoc trace from a REPL with (p/judge trace)."
  (:require [ai.obney.orc.evaluation.core.judges :as judges]
            [clojure.string :as str]
            [litellm.router :as litellm-router]))

;; ---------------------------------------------------------------------------
;; Provider registration (real OpenRouter; key from env)
;; ---------------------------------------------------------------------------

(defn register-openrouter! []
  (let [k (System/getenv "OPENROUTER_API_KEY")]
    (when (str/blank? k)
      (throw (ex-info "OPENROUTER_API_KEY not set — cannot run live calibration" {})))
    (litellm-router/register! :openrouter
                              {:provider :openrouter
                               :model "google/gemini-2.5-flash"
                               :config {:api-base "https://openrouter.ai/api/v1"
                                        :api-key k}})))

;; ---------------------------------------------------------------------------
;; Real source: a slice of the real employment agreement bench doc
;; ---------------------------------------------------------------------------

(def doc-path "development/bench/documents/employment_agreement.txt")

(defn source-context []
  {:document (slurp doc-path)})

;; ---------------------------------------------------------------------------
;; Candidate traces spanning the band range (responses crafted by hand from
;; the REAL doc facts, so we KNOW the expected band; the LLM judge is what we
;; are calibrating).
;; ---------------------------------------------------------------------------

(defn traces []
  (let [src (source-context)
        instr "Extract the employee's key employment details from the document."]
    [{:label "faithful (expect 5)"
      :expected 5
      :trace {:inputs src :instruction instr
              :response (str "Employee: Margaret Elisabeth Thornbury-Watson. "
                             "Position: Senior Financial Analyst, Wealth Management Division. "
                             "Start Date: April 1, 2025. Annual Salary: $142,500 CAD. "
                             "Reporting to James Harrington, VP Wealth Management.")}}

     {:label "mostly grounded, one minor extrapolation (expect 4)"
      :expected 4
      :trace {:inputs src :instruction instr
              :response (str "Margaret Thornbury-Watson is a Senior Financial Analyst earning "
                             "$142,500 CAD per year, starting April 1, 2025. She reports to "
                             "James Harrington. As a senior analyst she likely manages a small team.")}}

     {:label "mixed: gist right, unverifiable inferences as fact (expect 3)"
      :expected 3
      :trace {:inputs src :instruction instr
              :response (str "Margaret Thornbury-Watson was hired as a Senior Financial Analyst "
                             "in 2025. She has over 10 years of industry experience and was "
                             "recruited from a competing firm. Her salary is in the low six figures.")}}

     {:label "largely ungrounded: fabricated specifics (expect 2)"
      :expected 2
      :trace {:inputs src :instruction instr
              :response (str "Margaret Thornbury-Watson is the Chief Financial Officer, earning "
                             "$310,000 CAD plus equity, starting in January 2024. She reports "
                             "directly to the CEO and oversees a department of 40 people.")}}

     {:label "fabricated / contradicts source (expect 1)"
      :expected 1
      :trace {:inputs src :instruction instr
              :response (str "The document is a software licensing agreement between Acme Corp and "
                             "GlobalTech for a 3-year SaaS subscription totaling $2.4M, signed in "
                             "Seattle in 2022.")}}]))

;; ---------------------------------------------------------------------------
;; Run
;; ---------------------------------------------------------------------------

(defn judge [trace]
  (judges/grounding-judge {:inputs {:trace-data trace}}))

(defn run! []
  (register-openrouter!)
  (println "\n=== PA-3 grounding-judge live calibration (model:" judges/*judge-model* ") ===\n")
  (let [rows (doall
               (for [{:keys [label expected trace]} (traces)]
                 (let [{:keys [grounding-result]} (judge trace)
                       level (:level grounding-result)
                       score (:score grounding-result)]
                   (println "----------------------------------------------------------------")
                   (println "TRACE:" label)
                   (println "  expected band:" expected "| judge level:" level "| score:" score
                            (if (= expected level) "  <= MATCH" "  <= DIVERGES"))
                   (println "  reasoning:" (:reasoning grounding-result))
                   (println "  ungrounded-claims:" (:ungrounded-claims grounding-result))
                   (println "  feedback:" (:feedback grounding-result))
                   {:label label :expected expected :level level :score score})))]
    (println "\n=== SUMMARY ===")
    (doseq [{:keys [label expected level score]} rows]
      (println (format "  %-48s expected=%s level=%s score=%.2f %s"
                       label expected level (double score)
                       (if (= expected level) "MATCH" "DIVERGE"))))
    (let [matches (count (filter #(= (:expected %) (:level %)) rows))
          near (count (filter #(<= (Math/abs (long (- (int (:expected %)) (int (or (:level %) 0))))) 1) rows))]
      (println (format "\n  exact matches: %d/%d | within 1 band: %d/%d"
                       matches (count rows) near (count rows))))
    rows))
