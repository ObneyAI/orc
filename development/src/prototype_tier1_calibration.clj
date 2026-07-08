(ns prototype-tier1-calibration
  "PA-4 HITL PROTOTYPE — throwaway calibration harness for the discrete 1-5
   Scales of the three migrated tier-1 judges (instruction-following,
   reasoning, completeness). NOT part of the shipped component; lives under
   development/src and is excluded from the brick. Per discipline #2 it scores
   REAL traces with REAL live LLM calls (OpenRouter) — NO MOCKS.

   Traces are built from a REAL bench document
   (development/bench/documents/employment_agreement.txt). For each dimension we
   craft responses designed to land in a known band, then check whether the
   live judge agrees AND that the band ordering is MONOTONIC with degrading
   quality — calibrating the band wording.

   Run:
     export OPENROUTER_API_KEY=...
     clj -M:dev -e \"(require '[prototype-tier1-calibration :as p]) (p/run!)\""
  (:require [ai.obney.orc.evaluation.core.judges :as judges]
            [clojure.string :as str]
            [litellm.router :as litellm-router]))

(defn register-openrouter! []
  (let [k (System/getenv "OPENROUTER_API_KEY")]
    (when (str/blank? k)
      (throw (ex-info "OPENROUTER_API_KEY not set — cannot run live calibration" {})))
    (litellm-router/register! :openrouter
                              {:provider :openrouter
                               :model "google/gemini-2.5-flash"
                               :config {:api-base "https://openrouter.ai/api/v1"
                                        :api-key k}})))

(def doc-path "development/bench/documents/employment_agreement.txt")
(defn source-context [] {:document (slurp doc-path)})

;; ---------------------------------------------------------------------------
;; INSTRUCTION-FOLLOWING traces. Instruction has explicit, checkable directives:
;; format (bullet list), required components (name, position, salary, start),
;; a prohibition (no SIN/banking), and a length cap. Responses degrade in
;; compliance from full (5) to ignoring the instruction (1).
;; ---------------------------------------------------------------------------

(def if-instruction
  (str "Summarize the employee's role in EXACTLY three bullet points. "
       "Each bullet must be one short sentence. Include the position, the "
       "annual salary, and the start date. Do NOT include the SIN or any "
       "banking details."))

(defn instruction-following-traces []
  (let [src (source-context)]
    [{:label "full compliance (expect 5)" :expected 5
      :trace {:inputs src :instruction if-instruction
              :response (str "- Position: Senior Financial Analyst, Wealth Management Division.\n"
                             "- Annual salary: $142,500 CAD.\n"
                             "- Start date: April 1, 2025.")}}
     {:label "minor deviation, all directives met (expect 4)" :expected 4
      :trace {:inputs src :instruction if-instruction
              :response (str "- Margaret works as a Senior Financial Analyst in the Wealth "
                             "Management Division, a fairly senior role.\n"
                             "- Her annual salary is $142,500 CAD.\n"
                             "- She started on April 1, 2025.")}}
     {:label "missed a required component / wrong count (expect 3)" :expected 3
      :trace {:inputs src :instruction if-instruction
              :response (str "- Position: Senior Financial Analyst, Wealth Management Division.\n"
                             "- Start date: April 1, 2025.")}}
     {:label "violated prohibition + format (expect 2)" :expected 2
      :trace {:inputs src :instruction if-instruction
              :response (str "Margaret Thornbury-Watson is a Senior Financial Analyst earning "
                             "$142,500 CAD, starting April 1, 2025. Her SIN is 847-291-036 and "
                             "her RBC account number is 5127849. Pay is semi-monthly with a 20% "
                             "discretionary bonus and RRSP matching up to 5%.")}}
     {:label "ignored instruction entirely (expect 1)" :expected 1
      :trace {:inputs src :instruction if-instruction
              :response (str "Thank you for your question. Employment agreements are important "
                             "legal documents. If you have concerns about your contract you "
                             "should consult an employment lawyer in your jurisdiction.")}}]))

;; ---------------------------------------------------------------------------
;; REASONING traces. Instruction asks the producer to DERIVE a figure with a
;; visible chain. Responses degrade from a rigorous derivation (5) to an
;; incoherent / absent one (1).
;; ---------------------------------------------------------------------------

(def reasoning-instruction
  (str "Using only the document, work out the employee's maximum possible "
       "total annual cash compensation (base plus the maximum performance "
       "bonus). Show each step of your reasoning before stating the figure."))

(defn reasoning-traces []
  (let [src (source-context)]
    [{:label "rigorous, gap-free derivation (expect 5)" :expected 5
      :trace {:inputs src :instruction reasoning-instruction
              :response (str "Step 1: The base salary is $142,500 CAD per annum (Compensation "
                             "section). Step 2: The performance bonus is 'up to 20% of base "
                             "salary', so the maximum bonus is 0.20 x $142,500 = $28,500. "
                             "Step 3: Maximum total cash = base + max bonus = $142,500 + "
                             "$28,500 = $171,000. Therefore the maximum possible total annual "
                             "cash compensation is $171,000 CAD.")}}
     {:label "sound, one minor unstated-but-obvious assumption (expect 4)" :expected 4
      :trace {:inputs src :instruction reasoning-instruction
              :response (str "The base is $142,500 and the bonus can be up to 20%. Twenty "
                             "percent of the base is $28,500, so adding that to the base gives "
                             "$171,000 as the maximum total cash compensation.")}}
     {:label "gap / unstated leap presented as fact (expect 3)" :expected 3
      :trace {:inputs src :instruction reasoning-instruction
              :response (str "The base salary is $142,500. With the performance bonus and the "
                             "RRSP matching the employee effectively earns well over $175,000, "
                             "so the maximum total annual cash compensation is about $180,000.")}}
     {:label "major non-sequitur (expect 2)" :expected 2
      :trace {:inputs src :instruction reasoning-instruction
              :response (str "The salary is paid semi-monthly on the 1st and 15th. Because there "
                             "are 24 pay periods and the bonus is discretionary, the maximum "
                             "total annual cash compensation must be $142,500.")}}
     {:label "incoherent / no derivation (expect 1)" :expected 1
      :trace {:inputs src :instruction reasoning-instruction
              :response (str "Compensation is complex and depends on many factors. The maximum "
                             "could be quite high. It is hard to say exactly. The figure is "
                             "what the employer decides.")}}]))

;; ---------------------------------------------------------------------------
;; COMPLETENESS traces. Instruction asks for FOUR distinct fields. Responses
;; degrade from full coverage (5) to an empty stub (1).
;; ---------------------------------------------------------------------------

(def completeness-instruction
  (str "From the document, report four things: (1) the employee's full legal "
       "name, (2) the position title, (3) the annual base salary, and (4) who "
       "the employee reports to. Give each with enough detail to be useful."))

(defn completeness-traces []
  (let [src (source-context)]
    [{:label "all four covered with detail (expect 5)" :expected 5
      :trace {:inputs src :instruction completeness-instruction
              :response (str "1. Full legal name: Margaret Elisabeth Thornbury-Watson. "
                             "2. Position: Senior Financial Analyst, Wealth Management Division. "
                             "3. Annual base salary: $142,500.00 CAD per annum. "
                             "4. Reports to: James Harrington, VP Wealth Management.")}}
     {:label "all four, one slightly thin (expect 4)" :expected 4
      :trace {:inputs src :instruction completeness-instruction
              :response (str "Margaret Elisabeth Thornbury-Watson is a Senior Financial Analyst "
                             "earning $142,500 CAD a year, and she reports to James Harrington.")}}
     {:label "one required aspect missing (expect 3)" :expected 3
      :trace {:inputs src :instruction completeness-instruction
              :response (str "The employee is Margaret Elisabeth Thornbury-Watson, a Senior "
                             "Financial Analyst in the Wealth Management Division earning "
                             "$142,500 CAD per year.")}}
     {:label "most aspects missing (expect 2)" :expected 2
      :trace {:inputs src :instruction completeness-instruction
              :response "The employee is Margaret Thornbury-Watson."}}
     {:label "empty stub (expect 1)" :expected 1
      :trace {:inputs src :instruction completeness-instruction
              :response "The requested details are in the employment agreement."}}]))

;; ---------------------------------------------------------------------------
;; Run one dimension
;; ---------------------------------------------------------------------------

(defn- judge-for [dim trace]
  (case dim
    :instruction-following (-> (judges/instruction-following-judge {:inputs {:trace-data trace}}) :instruction-result)
    :reasoning             (-> (judges/reasoning-judge {:inputs {:trace-data trace}}) :reasoning-result)
    :completeness          (-> (judges/completeness-judge {:inputs {:trace-data trace}}) :completeness-result)))

(defn run-dimension! [dim traces]
  (println (str "\n=== PA-4 " (name dim) " live calibration (model: " judges/*judge-model* ") ===\n"))
  (let [rows (doall
               (for [{:keys [label expected trace]} traces]
                 (let [res (judge-for dim trace)
                       level (:level res)
                       score (:score res)]
                   (println "----------------------------------------------------------------")
                   (println "TRACE:" label)
                   (println "  expected band:" expected "| judge level:" level "| score:" score
                            (if (= expected level) "  <= MATCH" "  <= DIVERGES"))
                   (println "  reasoning:" (:reasoning res))
                   (println "  feedback:" (:feedback res))
                   {:label label :expected expected :level level :score score})))]
    (println "\n  --- SUMMARY:" (name dim) "---")
    (doseq [{:keys [label expected level score]} rows]
      (println (format "  %-48s expected=%s level=%s score=%.2f %s"
                       label expected level (double (or score 0.0))
                       (if (= expected level) "MATCH" "DIVERGE"))))
    (let [levels (map :level rows)
          monotonic? (apply >= (map #(or % -1) levels))  ;; expected order is high->low
          matches (count (filter #(= (:expected %) (:level %)) rows))
          near (count (filter #(<= (Math/abs (long (- (int (:expected %)) (int (or (:level %) 0))))) 1) rows))]
      (println (format "  exact: %d/%d | within-1: %d/%d | monotonic(high->low): %s | levels=%s"
                       matches (count rows) near (count rows)
                       monotonic? (pr-str (vec levels))))
      {:dim dim :rows rows :monotonic? monotonic? :matches matches :near near})))

(defn run! []
  (register-openrouter!)
  [(run-dimension! :instruction-following (instruction-following-traces))
   (run-dimension! :reasoning (reasoning-traces))
   (run-dimension! :completeness (completeness-traces))])
