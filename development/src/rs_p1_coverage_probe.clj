(ns rs-p1-coverage-probe
  "PROTOTYPE — throwaway. RS-P1: does the reranker separate 'right shape' from
   'wrong domain' when asked for a DISCRETE coverage verdict beside fitness, and
   are its domain labels stable across passes? Runs the 21-task OOD corpus twice
   with an EXTENDED reranker instruction (the shipped one plus a coverage
   section), tees the reranker's parsed output (extra keys kept) per call, and
   reports per task: top-1 fitness, coverage, label, and pass-to-pass label
   agreement. Nothing in components/ is modified on disk; vars are altered in
   this JVM only. Delete when RS-1 lands."
  (:require [ai.obney.orc.ontology.core.reranker :as reranker]
            [ai.obney.orc.ontology.core.task-classifier :as classifier]
            [ai.obney.orc.ontology.test-support.c2d-ood-stress-test :as ood]
            [ai.obney.orc.ontology.interface.schemas :as ontology-schemas]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [malli.core :as m]
            [com.brunobonacci.mulog :as mu]
            [runner]))

(def corpus-dir "development/bench/ood-corpus")
(def out-dir "development/bench/ood-stress-results/rs-p1-coverage-probe")

;; --- 1. the extended instruction: the shipped one with the output contract widened
(def base-instruction @#'reranker/reranker-instruction)

(def old-contract
  "PRODUCE a JSON string of a vector, descending by fitness_score. Each
element is an object with EXACTLY these three keys:
  {\"document_id\":   \"<echo the candidate's document-id verbatim>\",
   \"reasoning\":     \"<concrete, actionable; references specific content>\",
   \"fitness_score\": <number in [0.0, 1.0]>}

Example shape:
  [{\"document_id\":\"a\",\"reasoning\":\"...\",\"fitness_score\":0.91},
   {\"document_id\":\"b\",\"reasoning\":\"...\",\"fitness_score\":0.42}]")

(def new-contract
  "DOMAIN COVERAGE — A SEPARATE VERDICT, NOT A NUMBER.
fitness_score means how well the candidate's SHAPE and intent fit the task.
Separately, for each candidate, judge whether the candidate's DECLARED DOMAIN
— its representative uses and its avoid-when guards — covers the DOMAIN of the
task (what the task is about: the subject matter, the material, the kind of
output). Give a discrete verdict:
  covered    — a representative use or the content names this task's domain
  partial    — the domain is adjacent: some representative use overlaps, but
               the task's material or output kind is not one the candidate names
  uncovered  — nothing the candidate declares names this task's domain; the
               fit, if any, is shape only
  unknown    — you cannot tell from what the candidate declares
Write domain_reasoning BEFORE choosing the verdict: name the representative use
or guard you matched, or state the gap. Also give domain_label: a 2-4 word
kebab-case label of the TASK's own domain (the same label for every candidate
of this task), e.g. \"marathon-training-plan\", \"recipe-scaling\",
\"security-findings-haiku\".

PRODUCE a JSON string of a vector, descending by fitness_score. Each
element is an object with EXACTLY these six keys:
  {\"document_id\":     \"<echo the candidate's document-id verbatim>\",
   \"reasoning\":       \"<concrete, actionable; references specific content>\",
   \"fitness_score\":   <number in [0.0, 1.0]>,
   \"domain_reasoning\": \"<the representative use / guard matched, or the gap>\",
   \"domain_coverage\": \"<covered|partial|uncovered|unknown>\",
   \"domain_label\":    \"<2-4 word kebab-case label of the task's domain>\"}

Example shape:
  [{\"document_id\":\"a\",\"reasoning\":\"...\",\"fitness_score\":0.91,
    \"domain_reasoning\":\"...\",\"domain_coverage\":\"covered\",\"domain_label\":\"contract-comparison\"},
   {\"document_id\":\"b\",\"reasoning\":\"...\",\"fitness_score\":0.42,
    \"domain_reasoning\":\"...\",\"domain_coverage\":\"uncovered\",\"domain_label\":\"contract-comparison\"}]")

(def extended-instruction
  (let [s (str/replace base-instruction old-contract new-contract)]
    (assert (not= s base-instruction) "old contract text not found in shipped instruction")
    s))

;; --- 2. a parse that keeps the extra keys (the shipped one select-keys them away)
(defn probe-parse [result]
  (let [raw-json (get-in result [:outputs :reranked-json])
        payload (when (string? raw-json)
                  (let [start (.indexOf raw-json "[") end (.lastIndexOf raw-json "]")]
                    (when (and (>= start 0) (> end start)) (subs raw-json start (inc end)))))
        parsed (try (when payload (json/read-str payload :key-fn keyword)) (catch Throwable _ nil))
        canon (when (sequential? parsed)
                (mapv (fn [e]
                        (-> e
                            (assoc :document-id (:document_id e) :fitness-score (:fitness_score e)
                                   :domain-coverage (:domain_coverage e) :domain-label (:domain_label e)
                                   :domain-reasoning (:domain_reasoning e))
                            (dissoc :document_id :fitness_score :domain_coverage :domain_label :domain_reasoning)))
                      parsed))
        valid (when (sequential? canon) (filterv #(m/validate ontology-schemas/reranked-result %) canon))]
    valid))

(def captured (atom []))  ;; every rerank! output in this JVM, in call order

(defn install-probe! []
  (alter-var-root #'reranker/reranker-instruction (constantly extended-instruction))
  (alter-var-root #'reranker/parse-reranked-json (constantly probe-parse))
  (let [orig @#'reranker/rerank!]
    (alter-var-root #'reranker/rerank!
      (constantly (fn [ctx opts]
                    (let [out (orig ctx opts)]
                      (swap! captured conj {:query (:query opts) :out out})
                      out))))))

;; --- 3. one pass over the corpus
(defn run-pass! [ctx pass]
  (let [corpus (ood/load-corpus corpus-dir)]
    (vec (for [{:keys [slug instruction]} corpus]
           (let [before (count @captured)
                 env (classifier/classify-task ctx {:task-signature instruction :threshold 0.6})
                 calls (subvec @captured before)
                 ;; the first rerank! call of this classify is the structural rank (walk-down may add more)
                 first-out (:out (first calls))
                 top (first (sort-by #(- (or (:fitness-score %) 0.0)) first-out))
                 n-valid (count (filter #(contains? #{"covered" "partial" "uncovered" "unknown"} (:domain-coverage %)) first-out))]
             (println (format "  pass %d %-46s outcome=%s fit=%.2f cov=%s label=%s valid=%d/%d"
                              pass slug (:outcome env) (double (or (:fitness-score top) 0.0))
                              (:domain-coverage top) (:domain-label top) n-valid (count first-out)))
             {:pass pass :slug slug :outcome (:outcome env) :assigned-via (:assigned-via env)
              :top-fitness (:fitness-score top) :top-document (:document-id top)
              :coverage (:domain-coverage top) :label (:domain-label top)
              :domain-reasoning (:domain-reasoning top)
              :valid-count n-valid :candidate-count (count first-out)
              :all (mapv #(select-keys % [:document-id :fitness-score :domain-coverage :domain-label]) first-out)})))))

(defn run! []
  (install-probe!)
  (.mkdirs (java.io.File. out-dir))
  (let [ctx (deref @(requiring-resolve 'runner/system-state))
        p1 (run-pass! ctx 1)
        p2 (run-pass! ctx 2)
        by-slug (fn [rs] (into {} (map (juxt :slug identity) rs)))
        m1 (by-slug p1) m2 (by-slug p2)
        rows (for [slug (keys m1)] [slug (get m1 slug) (get m2 slug)])
        agree (count (filter (fn [[_ a b]] (= (:label a) (:label b))) rows))
        cov-agree (count (filter (fn [[_ a b]] (= (:coverage a) (:coverage b))) rows))
        valid-total (reduce + (map :valid-count (concat p1 p2)))
        cand-total (reduce + (map :candidate-count (concat p1 p2)))]
    (spit (str out-dir "/probe-results.edn") (pr-str {:pass-1 p1 :pass-2 p2}))
    (println "\n=== RS-P1 summary ===")
    (println (format "valid verdicts: %d / %d candidates" valid-total cand-total))
    (println (format "label agreement between passes: %d / %d" agree (count rows)))
    (println (format "coverage agreement between passes: %d / %d" cov-agree (count rows)))
    (println "per-task (pass1 cov/label | pass2 cov/label):")
    (doseq [[slug a b] (sort-by first rows)]
      (println (format "  %-46s %-9s %-32s | %-9s %s" slug (:coverage a) (:label a) (:coverage b) (:label b))))
    (println "saved" (str out-dir "/probe-results.edn"))))
