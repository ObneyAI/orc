(ns ai.obney.orc.ontology.core.graph
  "ORC integration adapter for the standalone ontology algorithms."
  (:require [ai.obney.ontology.algorithms :as ontology]))

(def default-edge-weights ontology/default-edge-weights)
(def default-traversal-config ontology/default-traversal-config)
(def concepts->graph ontology/concepts->graph)
(def parse-turtle-multiline ontology/parse-turtle-multiline)
(def parse-turtle-graph ontology/parse-turtle-graph)
(def bfs-spreading-activation ontology/bfs-spreading-activation)
(def link-expansion ontology/link-expansion)
(def compute-rrf-scores ontology/compute-rrf-scores)
(def merge-batches ontology/merge-batches)
(def compute-temporal-relevance ontology/compute-temporal-relevance)
(def apply-temporal-scoring ontology/apply-temporal-scoring)
(def quick-search ontology/quick-search)
(def expand-from-concept ontology/expand-from-concept)
