(ns stream-slice7-nothing-dropped-live
  "STREAM Slice 7 — the MANDATORY live nothing-dropped run over a REAL O*NET source.

   Drives the deterministic AGGREGATING apply (`apply-aggregation-transform!`, the
   SAME streaming fold the composed pipeline uses) over the real O*NET `Task
   Statements.csv` — a `:long-form` collect source: occupation (`O*NET-SOC Code`) →
   its Task statements, with several occupations carrying FAR MORE than 25 distinct
   tasks. It proves the exact scenario the user flagged:

     BEFORE (opt-in `:max-list-size 25`): a >25-distinct key lands only 25 values.
     AFTER  (default, NO cap):            the SAME key lands ALL N (nothing dropped).

   No LLM / no OPENROUTER key — this is the deterministic fold over a real source."
  (:require [ai.obney.orc.ontology.core.rlm-discovery :as rlm]
            [ai.obney.orc.ontology.core.container-aggregate :as ca]
            [clojure.string :as str]))

(def onet-task-statements
  "/Users/darylroberts/Downloads/db_30_1_excel/Task Statements.csv")

(def probe-key
  "The occupation whose Task list is known to exceed the old 25-cap (40 distinct)."
  "25-2057.00")

(defn- list-for [drafts k attr]
  (some (fn [d] (when (= (str k) (str (:label d)))
                  (get-in d [:attributes attr])))
        drafts))

(defn -main [& _]
  (let [descriptor {:type :csv :path onet-task-statements}
        base-spec  {:key-col "O*NET-SOC Code" :element-col "Task"
                    :attr-name :tasks :entity-type "occupation"}
        run (fn [spec label]
              (println (str "\n--- " label " ---"))
              (let [r (rlm/apply-aggregation-transform!
                       {:descriptor descriptor :spec spec
                        :selector nil :window nil :max-windows nil})
                    drafts (:concept-drafts r)
                    lst (list-for drafts probe-key :tasks)]
                (println "  rows-streamed:" (:rows-streamed r)
                         " distinct-keys:" (:distinct-keys r)
                         " peak-acc-entries:" (:peak-acc-entries r))
                (println "  list-truncated?:" (:list-truncated? r))
                (println (str "  " probe-key " task-list size: " (count lst)))
                (println (str "    (distinct? " (= (count lst) (count (distinct lst))) ")"))
                {:size (count lst) :truncated? (:list-truncated? r)
                 :peak (:peak-acc-entries r) :drafts drafts :list lst}))]
    (println "=== STREAM Slice 7 — LIVE nothing-dropped over REAL O*NET Task Statements ===")
    (println "source:" onet-task-statements)
    ;; BEFORE — the old behavior, now an explicit opt-in cap
    (let [before (run (assoc base-spec :max-list-size 25) "BEFORE (opt-in :max-list-size 25 — the OLD cap)")
          after  (run base-spec "AFTER (default — NO cap, keep everything)")]
      (println "\n=== VERDICT ===")
      (println (format "  BEFORE (capped 25): %d values, list-truncated? %s"
                       (:size before) (:truncated? before)))
      (println (format "  AFTER  (uncapped):  %d values, list-truncated? %s"
                       (:size after) (:truncated? after)))
      (let [distinct-ok? (= (:size after) (count (distinct (:list after))))
            payoff? (and (= 25 (:size before))
                         (true? (:truncated? before))
                         (> (:size after) 25)
                         (not (:truncated? after))
                         distinct-ok?)]
        (println "\n  Nothing-dropped payoff proven?" payoff?)
        (println (str "    - default kept ALL " (:size after) " distinct tasks (>25): "
                      (> (:size after) 25)))
        (println (str "    - opt-in cap fired + surfaced (25 + truncated?): "
                      (and (= 25 (:size before)) (true? (:truncated? before)))))
        (println (str "    - DISTINCT dedup preserved at the new default: " distinct-ok?))
        (shutdown-agents)
        (System/exit (if payoff? 0 1))))))
