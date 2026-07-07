(ns connect3e-spec-forensics
  "CONNECT-3e verification — read the ACTUAL discovery output the model authored
   for the `Related Occupations` container from the build store (rule out the
   inference: was it an association-spec with :element-entity-type Occupation, a
   per-row transform, what exactly?). Reads :sheet/execution-value-written events
   and surfaces every value that mentions the Related crosswalk / its columns.

   USAGE: clj -M:dev:test -m connect3e-spec-forensics <db-file> <tenant-uuid>"
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

(defn -main [& [db-file tenant-str]]
  (let [tenant (java.util.UUID/fromString tenant-str)
        store  (es/start {:conn {:type :sqlite :database-file db-file :maximum-pool-size 2} :logger nil})]
    (try
      (let [evs (into [] (es/read store {:tenant-id tenant
                                         :types #{:sheet/execution-value-written}
                                         :limit 1000000}))]
        (println "\n===== CONNECT-3e SPEC FORENSICS =====")
        (println "execution-value events:" (count evs))
        (when-let [e (first evs)] (println "event keys:" (pr-str (keys e))))
        ;; Surface every value that references the Related crosswalk or its cols.
        (let [hits (filter (fn [e]
                             (let [s (try (pr-str (:value e)) (catch Throwable _ ""))]
                               (re-find #"(?i)Related O\*NET-SOC|Related Occupation|Relatedness Tier|Related Title" s)))
                           evs)]
          (println "\nvalues mentioning the Related crosswalk:" (count hits))
          (doseq [e hits]
            (let [k (:key e) v (:value e)
                  s (try (pr-str v) (catch Throwable _ ""))]
              ;; Only print the DECISION-BEARING keys (spec / transform / selector /
              ;; model-spec-ish), skip huge concept-draft dumps unless small.
              (when (or (#{:aggregation-spec :transform-source :selector :entity-type-proposal
                           :model-spec :grain-strategy :extraction-report} k)
                        (< (count s) 1500))
                (println (format "\n---- node=%s key=%s (%d chars) ----"
                                 (pr-str (:node-name e)) (pr-str k) (count s)))
                (println (subs s 0 (min 2500 (count s)))))))))
      (finally (es/stop store)))))
