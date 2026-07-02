(ns mt6-listform-prototype
  "MT-6 /prototype (THROWAWAY) — de-risk the MT-1 classifier refinement that
   distinguishes a LIST-VALUED ATTRIBUTE table (a repeating entity key + a descriptive
   element column, NO numeric value — e.g. O*NET `Alternate Titles`: occupation → its
   other job titles) from a true `:entity` table (all columns near-unique, ~1 row per
   entity — e.g. `Occupation Data`).

   The current classifier tags `Alternate Titles` `:entity` (it sees the near-unique
   `Alternate Title` column as the key) → per-row extraction → 24,999 nodes → OOM +
   an acceptance violation. The fix: detect the REPEATING key column (low distinct-
   ratio = many rows per value) and classify such tables `:list-form` (aggregate the
   element into a flat list on the entity), NOT `:entity`.

   This prototype runs a CANDIDATE refined classifier against the CURRENT one on all
   40 real O*NET tables and reports every re-classification — confirming it re-tags the
   attribute tables while PRESERVING the existing shapes (Occupation Data :entity,
   Skills/Knowledge :long-form, the bridges/references unchanged). Nothing ships.

   USAGE: clj -M:dev:test -m mt6-listform-prototype"
  (:require [ai.obney.orc.orc-service.core.source-tools :as st]
            [ai.obney.orc.ontology.core.container-select :as csel]
            [ai.obney.orc.ontology.core.container-shape :as cs]
            [clojure.string :as str]))

(def onet {:type :excel :path "/Users/darylroberts/Downloads/db_30_1_excel"})

;; ---- the candidate refined classifier (a copy of the real one + the :list-form rule) ----
(def ^:private unique-key-threshold 0.9)
(def ^:private numeric-threshold 0.9)
(def ^:private tiny-row-count 50)
(def ^:private wide-stats-min-measures 5)
(def ^:private repeating-key-threshold
  "A NON-numeric column whose sampled distinct-ratio is AT/BELOW this REPEATS heavily
   (many rows share a value) → it is an entity key with MANY rows per entity (long/list
   form), NOT a per-row entity table. 0.5 sits well below a near-unique key (~1.0) and
   well above the mid-card element columns." 0.5)

(defn- numeric-like? [v]
  (or (number? v) (and (string? v) (re-matches #"\s*-?\d+(?:\.\d+)?\s*" v) true)))

(defn- column-stats [header sample]
  (let [n (max 1 (count sample))]
    (mapv (fn [h]
            (let [vals (remove nil? (map #(get % h) sample)) c (count vals)]
              {:col h
               :distinct-ratio (/ (count (distinct vals)) (double n))
               :numeric-frac (if (pos? c) (/ (count (filter numeric-like? vals)) (double c)) 0.0)}))
          header)))

(defn classify-refined [{:keys [header sample row-count]}]
  (let [stats (column-stats header sample)
        key-cols (filter #(and (>= (:distinct-ratio %) unique-key-threshold)
                               (< (:numeric-frac %) numeric-threshold)) stats)
        measure-cols (filter #(>= (:numeric-frac %) numeric-threshold) stats)
        non-numeric (remove #(>= (:numeric-frac %) numeric-threshold) stats)
        ;; NEW — a strongly-REPEATING non-numeric column = an entity key with many rows.
        repeating-cols (filter #(<= (:distinct-ratio %) repeating-key-threshold) non-numeric)
        has-key? (seq key-cols)
        has-measure? (seq measure-cols)
        has-repeating-key? (seq repeating-cols)
        tiny? (and row-count (<= row-count tiny-row-count))]
    (cond
      ;; NEW — a repeating key + a distinct element column, NO numeric value → the element
      ;; is a LIST-VALUED ATTRIBUTE of the repeating entity (collect into a flat list).
      (and has-repeating-key? (not has-measure?)
           (some #(> (:distinct-ratio %) repeating-key-threshold) non-numeric))
      (let [key-col (:col (first (sort-by :distinct-ratio repeating-cols)))
            element (:col (last (sort-by :distinct-ratio non-numeric)))]
        {:shape :list-form :keep? true :roles {:key key-col :element element}})
      ;; repeating key + numeric value → long-form (existing keyless-measure path also
      ;; catches this; kept explicit for clarity)
      (and has-repeating-key? has-measure?)
      {:shape :long-form :keep? true}
      ;; entity-shaped: a unique non-numeric key, ~1 row/key (all cols near-unique)
      has-key?
      (cond
        tiny? {:shape :reference :keep? false}
        (>= (count measure-cols) wide-stats-min-measures) {:shape :wide-stats :keep? true}
        :else {:shape :entity :keep? true})
      ;; no unique key but a numeric value → long-form (keyless measure)
      has-measure? {:shape :long-form :keep? true}
      :else {:shape :bridge :keep? false})))

(defn run! []
  (let [cc (st/container-contract onet)
        tools (st/source-tools-for onet)]
    (println "=== MT-6 /prototype — CURRENT vs REFINED classifier on 40 real O*NET tables ===\n")
    (println (format "  %-32s %-12s %-12s %-6s %s" "table" "current" "refined" "rows" "roles(refined)"))
    (let [rows
          (for [c ((:list-containers cc))]
            (let [samp (csel/normalize-sample-result ((:sample-rows cc) c {:limit 64}))
                  header (vec (distinct (mapcat keys samp)))
                  rc (try (:row-count ((tools 'count-rows) (:path c) (:sheet c))) (catch Throwable _ -1))
                  cur (:shape (cs/classify-container-shape {:header header :sample samp :row-count (count samp)}))
                  ref (classify-refined {:header header :sample samp :row-count (count samp)})]
              {:name (:name c) :cur cur :ref (:shape ref) :roles (:roles ref) :rc rc}))]
      (doseq [r (reverse (sort-by :rc rows))]
        (println (format "  %-32s %-12s %-12s %-6s %s"
                         (:name r) (str (:cur r)) (str (:ref r)) (str (:rc r))
                         (if (= (:cur r) (:ref r)) "" (str "  <== RE-CLASSIFIED " (pr-str (:roles r)))))))
      (let [changed (filter #(not= (:cur %) (:ref %)) rows)]
        (println "\n=== RE-CLASSIFICATIONS:" (count changed) "===")
        (doseq [r changed] (println "   " (:name r) ":" (:cur r) "->" (:ref r) " roles" (:roles r)))
        (println "\n=== SANITY (must hold) ===")
        (let [by-name (into {} (map (juxt :name :ref) rows))]
          (doseq [[nm want] [["Occupation Data" :entity] ["Skills" :long-form]
                             ["Knowledge" :long-form] ["Alternate Titles" :list-form]
                             ["Task Statements" :list-form]
                             ["Abilities to Work Activities" :bridge]]]
            (println (format "   %-32s refined=%-12s want=%-12s %s"
                             nm (str (get by-name nm)) (str want)
                             (if (= (get by-name nm) want) "OK" "*** MISMATCH ***")))))))
    (println "\nDONE") (System/exit 0)))

(defn -main [& _] (run!))
