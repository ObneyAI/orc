(ns mt7e-dedup-oom-probe
  "MT-7e VERIFY-FIRST — pin the dedup-cascade OOM (lsh-candidate-pairs/shingles) driver
   before bounding. Captures a REAL comprehensive draft set (survey→model→extract, cap
   12) and profiles, WITHOUT running the OOM-ing blocker:
     - concept-draft COUNT (the fine/observation grain volume),
     - LABEL-LENGTH distribution (min/median/p99/MAX) — a single giant label would make
       `shingles` alone OOM (a set of (len-2) substrings),
     - LABEL-COLLISION bucket sizes (group by normalized label) — a degenerate bucket
       (thousands of near-identical labels) makes the within-bucket O(k^2) pair
       enumeration explode.
   Whichever is pathological tells us which bound is load-bearing.

   USAGE: clj -M:dev:test -m mt7e-dedup-oom-probe"
  (:require [eb12-graph-b-central-evolver :as h]
            [ai.obney.orc.ontology.core.central-evolver :as ce]
            [ai.obney.orc.ontology.core.dedup-cascade :as dedup]
            [clojure.string :as str]))

(def onet {:type :excel :path h/onet-dir})
(def goal "Build an ontology of occupations and the skills, knowledge, and job requirements they have.")

(defn- pctl [sorted p] (nth sorted (min (dec (count sorted)) (int (* p (count sorted)))) 0))

(defn run! []
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY required (env only)" {})))
  (h/register-openrouter! h/default-model)
  (let [ctx ((deref #'h/make-ctx) {:store :in-memory})]
    (try
      (println "=== MT-7e DEDUP-OOM PROBE — real extract (cap 12), profile the draft set ===")
      (let [{:keys [pipeline-sheet-id]} (ce/register-pipeline-sheets! ctx {:model h/default-model :resilient? true})
            sv (ce/delegate-survey! ctx {:source onet :goal goal :model h/default-model})
            mx (ce/delegate-model-extract! ctx {:source onet :goal goal :profile (:profile sv)
                                                :pipeline-sheet-id pipeline-sheet-id
                                                :model h/default-model :max-containers 12})
            _ (println "model-extract:" (:status mx) " retries:" (:vocabulary-retries mx))
            drafts (:concept-drafts mx)
            labeled (filter :label drafts)
            lens (sort (map (comp count str :label) labeled))
            ;; label-collision buckets (a coarse proxy for the LSH bucket blow-up):
            by-norm (frequencies (map (comp dedup/normalize-label :label) labeled))
            big-buckets (->> by-norm (sort-by val >) (take 12))]
        (println "\ndraft count:" (count drafts) " labeled:" (count labeled))
        (println "label length — min:" (first lens) " median:" (pctl lens 0.5)
                 " p99:" (pctl lens 0.99) " MAX:" (last lens))
        (println "\ntop label-collision buckets (normalized-label -> #drafts):")
        (doseq [[lbl n] big-buckets] (println (format "   %-7d %s" n (subs (str lbl) 0 (min 60 (count (str lbl)))))))
        (let [max-bucket (apply max 1 (vals by-norm))
              n (count labeled)]
          (println "\n=== VERDICT (which bound is load-bearing) ===")
          (println "  giant-label? MAX label len =" (last lens)
                   "=>" (if (> (last lens) 2000) "YES — a single huge label OOMs shingles; cap shingle input length"
                            "no (labels are small)"))
          (println "  degenerate-bucket? biggest label-collision bucket =" max-bucket
                   " (O(k^2) pairs ≈" (quot (* max-bucket max-bucket) 2) ")"
                   "=>" (if (> max-bucket 2000) "YES — cap pairs-per-bucket (honest truncation)"
                            "moderate"))
          (println "  sheer-volume? labeled concepts =" n
                   "=>" (if (> n 40000) "YES — the fine grain volume; bound total concepts/pairs the blocker processes"
                            "moderate"))))
      (println "\n=== DONE ===")
      (finally ((deref #'h/stop-ctx) ctx)))))

(defn -main [& _] (run!) (shutdown-agents) (System/exit 0))
