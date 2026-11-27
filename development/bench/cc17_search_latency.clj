(ns cc17-search-latency
  (:require [ai.obney.orc.colbert.core.logic :as logic]
            [ai.obney.orc.colbert.core.queries :as queries]
            [clojure.edn :as edn] [time-literals.read-write]
            [ai.obney.orc.colbert.core.read-models :as rm]
            [ai.obney.orc.ontology.core.logic :as ontology-logic]
            [ai.obney.orc.ontology.core.queries :as ontology-queries]
            [ai.obney.orc.ontology.core.todo-processors :as ontology-processors]))
(def corpus
  "The P-E production dump; override with -Dcc17.corpus."
  (or (System/getProperty "cc17.corpus")
      "/private/tmp/claude-501/-Users-darylroberts-Desktop-Code-orc-sessions/1dcb8677-d588-4d07-b419-779f8489bfdb/scratchpad/pe/corpus-a89f9f58-9761-42c9-bc67-94acba7bd4f2.edn"))
(defn -main [& _]
  (time-literals.read-write/print-time-literals-clj!)
  (System/setProperty "colbert.index.root"
    (str (.toFile (java.nio.file.Files/createTempDirectory "cc17-lat" (make-array java.nio.file.attribute.FileAttribute 0)))))
  (let [evs (edn/read-string {:readers time-literals.read-write/tags} (slurp corpus))
        sig @#'ontology-logic/build-parent-inference-signature
        bodies (->> evs (filter #(= :ontology/tree-description-updated (:event/type %))) (keep :body))
        docs (vec (distinct (keep :summary bodies)))
        ids (mapv #(str "d" %) (range (count docs)))
        query (sig (first bodies))]
    (println "INDEX documents (distinct real summaries):" (count docs))
    (doseq [limit [32 128 256 464]]
      (let [res (logic/create-index! {} {:collection docs :document-ids ids
                                       :index-name (str "lat-" limit)
                                       :split-documents? false
                                       :maximum-query-tokens limit})]
        (with-redefs [rm/get-index (fn [_ id] (when (= id (:index-id res))
                                                {:index-id id :index-path (:index-path res)
                                                 :config (:config res) :status :active}))]
          (queries/search {} {:query query :index-id (:index-id res) :k 10})
          (let [t0 (System/nanoTime)
                _ (dotimes [_ 5] (queries/search {} {:query query :index-id (:index-id res) :k 10}))
                ms (/ (- (System/nanoTime) t0) 5e6)]
            (println (format "  limit %3d: %.0f ms per search over %d passages"
                             limit ms (:num-passages res)))))))
    (System/exit 0)))
