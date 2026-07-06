(ns mt9-retry-diagnostic
  "MT-9 diagnostic — is the vocabulary-recovery retry EFFECTIVE, or does re-running
   the delegate with identical inputs return a CACHED/identical degraded result
   (making the retry futile)? Sets up survey+pipeline ONCE, then calls
   delegate-model-extract! N times; the in-function [MT9-PROBE] lines show, PER
   ATTEMPT, the raw-model-spec hash — identical hashes across a single call's
   retries ⇒ caching; differing ⇒ genuine independent per-attempt loss.

   USAGE: clj -J-Xmx6g -M:dev:test -m mt9-retry-diagnostic"
  (:require [eb12-graph-b-central-evolver :as h]
            [ai.obney.orc.ontology.core.central-evolver :as ce]))

(def onet {:type :excel :path h/onet-dir})
(def goal "Build an ontology of occupations and the skills, knowledge, and job requirements they have.")

(defn -main [& _]
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY required (env only)" {})))
  (h/register-openrouter! h/default-model)
  (let [ctx ((deref #'h/make-ctx) {:store :in-memory})]
    (try
      (println "=== MT-9 RETRY DIAGNOSTIC — 5 model-extract calls (each up to 4 attempts) ===")
      (let [{:keys [pipeline-sheet-id]} (ce/register-pipeline-sheets! ctx {:model h/default-model :resilient? true})
            sv (ce/delegate-survey! ctx {:source onet :goal goal :model h/default-model})
            _ (println "survey:" (:status sv) "\n")
            outcomes
            (doall
             (for [i (range 10)]
               (do (println (str "\n----- CALL " (inc i) " -----"))
                   (let [mx (ce/delegate-model-extract!
                             ctx {:source onet :goal goal :profile (:profile sv)
                                  :pipeline-sheet-id pipeline-sheet-id
                                  :model h/default-model :max-containers 6 :max-windows 5})]
                     (println "  => status:" (:status mx)
                              " vocabulary-retries:" (:vocabulary-retries mx)
                              " et-count:" (count (:entity-types (:model-spec mx))))
                     {:status (:status mx) :retries (:vocabulary-retries mx)}))))]
        (println "\n=== SUMMARY ===")
        (println "successes:" (count (filter #(= :success (:status %)) outcomes)) "/5")
        (println "per-call retries:" (mapv :retries outcomes))
        (println "\nINTERPRET: within a failing call, compare the [MT9-PROBE] raw-hash across"
                 "\n  its attempts — ALL SAME ⇒ delegate is CACHED (retry re-reads identical"
                 "\n  degraded output; the bound is irrelevant, need cache-bust or dscloj fix)."
                 "\n  DIFFERENT ⇒ genuine independent per-attempt loss (a high loss rate)."))
      (finally ((deref #'h/stop-ctx) ctx)))))
