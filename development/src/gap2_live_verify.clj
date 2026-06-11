(ns gap2-live-verify
  "Gap-2 LIVE verify — score real model-generated trees via the
   extracted heuristic-structural evaluator.

   The heuristic is deterministic (no LLM), so 'live' here means
   running the heuristic on REAL Phase 1 emit-tree! outputs captured
   from prior bench runs. This proves:

   1. The extracted heuristic preserves the original scoring behavior
      from the retired RLM Rolling Judge
   2. The function works correctly on real model output shapes
      (not just synthetic test cases)
   3. The score+feedback+dimensions output shape is consistent with
      the unified protocol

   A fully end-to-end production verify (real bench task → judge auto-
   attached → :judge/score-emitted event) requires Gap-5's default
   attachment work to be production-shaped; we'll do that when Gap-5
   lands. For Gap-2's scope, scoring real model outputs is sufficient."
  (:require [ai.obney.orc.evaluation.core.heuristic-structural :as hs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn- load-bench-result
  "Load a saved bench result EDN. Returns the result map.

   Bench EDNs contain `#uuid`, `#time/offset-date-time`, and other
   tagged literals. We register permissive readers so the load
   succeeds — the actual tree we care about is a plain vector of
   keywords + maps, no special tags."
  [path]
  (edn/read-string
    {:readers {'uuid #(java.util.UUID/fromString %)
               'time/offset-date-time str
               'time/instant str
               'time/local-date-time str}
     :default (fn [_tag value] value)}
    (slurp path)))

(defn- score-tree-from-edn
  "Load one bench result, extract :generated-tree-raw, score it,
   return a report map."
  [path]
  (let [data (load-bench-result path)
        tree (or (:generated-tree-raw data)
                 (get-in data [:outputs :generated-tree-raw]))
        result (when tree (hs/evaluate-tree-structure tree))]
    {:path path
     :had-tree? (some? tree)
     :tree-snippet (when tree
                     (subs (pr-str tree) 0 (min 200 (count (pr-str tree)))))
     :score (:score result)
     :feedback (:feedback result)
     :dimensions (:dimensions result)}))

(defn verify!
  "Score every legal-issue-detection bench result from 2026-06-02
   via the extracted heuristic. Prints a per-file report; returns
   a vector of reports."
  []
  (println "\n#### Gap-2 LIVE Verify — heuristic-structural on real model output")
  (let [dir (io/file "development/bench/generalization-results")
        files (->> (.listFiles dir)
                   (filter #(.startsWith (.getName %) "legal-issue-detection_2026-06-02_"))
                   (sort-by #(.getName %)))
        reports (mapv (comp score-tree-from-edn #(.getPath %)) files)]
    (doseq [r reports]
      (println "\n---")
      (println "  file:" (.getName (io/file (:path r))))
      (println "  had-tree?:" (:had-tree? r))
      (when (:had-tree? r)
        (println "  tree:" (:tree-snippet r) "...")
        (println "  score:" (:score r))
        (println "  feedback:" (:feedback r))
        (println "  dimensions:")
        (doseq [d (:dimensions r)]
          (println "    -" (:name d) "weight" (:weight d) "score" (:score d) ":" (:feedback d)))))
    (println "\n#### Summary")
    (let [trees-found (count (filter :had-tree? reports))]
      (println "  files scanned:" (count reports))
      (println "  files with :generated-tree-raw:" trees-found)
      (when (pos? trees-found)
        (let [scores (keep :score reports)]
          (println "  score min:" (apply min scores))
          (println "  score max:" (apply max scores))
          (println "  score avg:" (/ (reduce + scores) (count scores))))))
    reports))

(comment
  (require '[gap2-live-verify :as v] :reload)
  (v/verify!))
