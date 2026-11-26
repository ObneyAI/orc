(ns decision-eval.analyse
  "Summarises results.edn from decision-eval.run: accuracy, misses, latency,
   tokens, cost and the effect of a confidence floor."
  (:require [clojure.edn :as edn]))
(defn- pct [n d] (if (zero? d) "-" (format "%.0f%% (%d/%d)" (* 100.0 (/ n d)) n d)))
(defn- median [xs] (let [s (vec (sort xs))] (when (seq s) (s (quot (count s) 2)))))
(defn -main [& _]
 (let [rs (edn/read-string (slurp (str (System/getProperty "eval.dir" "development/bench/decision_eval")
                                     "/results.edn")))]
 (doseq [m [:jev :gemini]]
  (let [route (filter #(and (= m (:model %)) (= :routing (:task %))) rs)
        truth (filter #(and (= m (:model %)) (= :truth (:task %))) rs)
        ok? #(= (:label %) (:answer %))
        clear (remove :hard? route) hard (filter :hard? route)
        fails (remove #(= :success (:status %)) route)]
    (println "==" m)
    (println "routing all  " (pct (count (filter ok? route)) (count route)))
    (println "routing clear" (pct (count (filter ok? clear)) (count clear)))
    (println "routing hard " (pct (count (filter ok? hard)) (count hard)))
    (println "truth        " (pct (count (filter ok? truth)) (count truth)))
    (println "node failures" (count fails) (mapv (juxt :id :error) fails))
    (println "latency ms median route/truth" (median (map :ms route)) (median (map :ms truth)))
    (println "tokens route total" (reduce + (keep #(get-in % [:usage :total-tokens]) route))
             "cost route total" (reduce + 0 (keep #(get-in % [:usage :cost]) route)))
    (println "misses" (mapv (fn [r] [(:id r) (:label r) '-> (:answer r) (:confidence r)]) (remove ok? route)))
    (when (= m :jev)
      (doseq [floor [0.5 0.6 0.7 0.8 0.9]]
        (let [answered (filter #(and (:confidence %) (>= (:confidence %) floor)) route)]
          (println (format "  floor %.1f: answers %s, accuracy of answered %s" floor
                           (pct (count answered) (count route)) (pct (count (filter ok? answered)) (count answered)))))))))))
