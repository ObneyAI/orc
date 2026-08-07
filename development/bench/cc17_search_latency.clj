(ns cc17-search-latency
  (:require [clojure.edn :as edn] [time-literals.read-write]
            [ai.obney.orc.colbert.core.operations :as ops]
            [ai.obney.orc.colbert.core.read-models :as rm]
            [ai.obney.orc.colbert.core.model-store :as model-store]
            [ai.obney.orc.ontology.core.consolidator]))
(def corpus
  "The P-E production dump; override with -Dcc17.corpus."
  (or (System/getProperty "cc17.corpus")
      "/private/tmp/claude-501/-Users-darylroberts-Desktop-Code-orc-sessions/1dcb8677-d588-4d07-b419-779f8489bfdb/scratchpad/pe/corpus-a89f9f58-9761-42c9-bc67-94acba7bd4f2.edn"))
(defn -main [& _]
  (time-literals.read-write/print-time-literals-clj!)
  (System/setProperty "colbert.index.root"
    (str (.toFile (java.nio.file.Files/createTempDirectory "cc17-lat" (make-array java.nio.file.attribute.FileAttribute 0)))))
  (let [evs (edn/read-string {:readers time-literals.read-write/tags} (slurp corpus))
        sig @#'ai.obney.orc.ontology.core.consolidator/build-parent-inference-signature
        bodies (->> evs (filter #(= :ontology/tree-description-updated (:event/type %))) (keep :body))
        docs (vec (distinct (keep :summary bodies)))
        ids (mapv #(str "d" %) (range (count docs)))
        query (sig (first bodies))]
    (println "INDEX documents (distinct real summaries):" (count docs))
    (doseq [limit [32 128 256 464]]
      (let [res (ops/create-index! {} {:collection docs :document-ids ids
                                       :index-name (str "lat-" limit)
                                       :split-documents? false
                                       :maximum-query-tokens limit})]
        (with-redefs [rm/get-index (fn [_ id] (when (= id (:index-id res))
                                                {:index-id id :index-path (:index-path res)
                                                 :config (:config res) :status :active}))]
          (ops/search {} {:query query :index-id (:index-id res) :k 10})
          (let [t0 (System/nanoTime)
                _ (dotimes [_ 5] (ops/search {} {:query query :index-id (:index-id res) :k 10}))
                ms (/ (- (System/nanoTime) t0) 5e6)]
            (println (format "  limit %3d: %.0f ms per search over %d passages"
                             limit ms (:num-passages res)))))))
    (System/exit 0)))
