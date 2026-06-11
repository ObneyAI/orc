;; One-shot conversion: read the hand-authored seed vectors from the dev
;; namespace `seed-descriptions`, serialize each to EDN under
;; `components/ontology/resources/seeds/`. After running this script the
;; ontology component is self-sufficient (consumers don't need
;; `development/src/seed_descriptions.clj` to be on their classpath).
;;
;; Run via:
;;   clj -M:dev -e "(load-file \"components/ontology/scripts/regen-seeds.clj\")"
;;
;; The script verifies round-trip equality (loaded EDN == source Clojure data)
;; after writing each file; mismatches throw.
(ns ontology.scripts.regen-seeds
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [seed-descriptions :as s]))

(defn dump-seeds-edn!
  [out-path seeds header]
  (with-open [w (io/writer out-path)]
    (binding [*out* w
              *print-length* nil
              *print-level* nil]
      (println ";; " header)
      (println ";; Generated from development/src/seed_descriptions.clj — DO NOT EDIT BY HAND.")
      (println ";; To regenerate: clj -M:dev -e \"(load-file \\\"components/ontology/scripts/regen-seeds.clj\\\")\"")
      (println ";; Each entry: {:target-id <uuid-or-keyword-or-string> :body {<description-body>}}")
      (println)
      (pp/pprint (vec seeds)))))

(def out-dir "components/ontology/resources/seeds/")

(.mkdirs (io/file out-dir))

(let [specs [["node-types.edn"
              s/all-node-type-seeds
              "10 node-type seeds — :llm :code :map-each :parallel :sequence :fallback :condition :chunk-document :aggregate :final."]
             ["tree-classes.edn"
              s/all-tree-fingerprint-seeds
              "23 structural tree-class seeds — task-specific (legal-issue-detection, risk-analysis, etc.) plus generic patterns (ChunkedExtraction, MapReduce, ValidationLoop, etc.) plus R02 children."]
             ["behavioral-subtrees.edn"
              s/all-behavioral-subtree-seeds
              "12 behavioral-subtree seeds — Research / Extraction / Analysis / Synthesis / Ideation / Design / Critique / Validation / Code-building / Transformation / Classification / Investigation."]]]
  (doseq [[fname src header] specs]
    (let [path (str out-dir fname)]
      (dump-seeds-edn! path src header)
      (let [loaded (edn/read-string (slurp path))]
        (if (= (vec src) loaded)
          (println "  OK" fname "(" (count src) "entries )")
          (do
            (println "  FAIL" fname)
            (println "    source-count" (count src) "loaded-count" (count loaded))
            (throw (ex-info "Round-trip mismatch" {:file fname}))))))))

(println)
(println "All seed files written + round-trip verified at" out-dir)
