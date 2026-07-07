(ns connect4-forensics
  "CONNECT-4 — forensic read of the LIVE bounded O*NET build's persisted SQLite
   store, feeding the pure `connectivity-verdict`.

   REUSES the `onet-overmint-forensics` read approach (cs/reduce-concepts,
   cs/reduce-relationships, cs/reduce-concept-embeddings, uri-scheme) but
   PARAMETERIZED on THIS run's db-file / tenant / oid (passed as CLI args, read
   from the SQLite `event_tags` — NEVER the log banner; rule out the harness).

   CASE-AWARE (the live run surfaced a case-variant vocabulary split): the
   canonical occupation nodes minted from Occupation Data land under one scheme
   spelling; a junction sheet's model-spec may author a DIFFERENT case spelling
   for the same entity-type. This scan reports both the STRICT-canonical view
   (edges whose source is a canonical `occupation/*` node, exact scheme) AND the
   case-insensitive connectivity view + the SOC-tail overlap that proves the two
   spellings are the SAME entities minted twice (the mt7c variant-scheme split).

   USAGE: clj -M:dev:test -m connect4-forensics <db-file> <tenant-uuid> <oid-uuid>
          [<canonical-occ-scheme, default occupation>]"
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface] ;; register :sqlite store (side-effect)
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.orc.ontology.core.concept-stream :as cs]
            [ai.obney.orc.ontology.core.read-models]   ;; register concepts read-model (side-effect)
            [ai.obney.orc.ontology.core.retrieval :as retrieval]
            [ai.obney.orc.ontology.core.graph :as graph]
            [ai.obney.orc.ontology.core.connectivity-acceptance :as cacc]
            [clojure.set]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

(defn- uri-scheme [uri] (-> (str uri) (str/split #"[:/]" 2) first (or "∅")))
(defn- uri-tail   [uri] (let [s (str uri) i (str/index-of s "/")] (if i (subs s (inc i)) s)))

(defn -main [& [db-file tenant-str oid-str canon-scheme]]
  (when-not (and db-file tenant-str oid-str)
    (throw (ex-info "usage: -m connect4-forensics <db-file> <tenant-uuid> <oid-uuid> [canon-scheme]" {})))
  (let [tenant (java.util.UUID/fromString tenant-str)
        oid    (java.util.UUID/fromString oid-str)
        canon  (or canon-scheme "occupation")            ; the CANONICAL occupation scheme (Occupation Data)
        occ?   (fn [uri] (= "occupation" (str/lower-case (uri-scheme uri)))) ; case-insensitive occupation
        store  (es/start {:conn {:type :sqlite :database-file db-file :maximum-pool-size 2} :logger nil})
        cache-dir (str "/tmp/connect4-forensics-" (random-uuid))
        cache  (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "c4"
                                                :map-size (* 512 1024 1024)}))
        ctx    {:event-store store :tenant-id tenant :cache cache}]
    (rmp/l1-clear!)
    (try
      (let [concepts (cs/reduce-concepts ctx oid conj [])
            rels     (cs/reduce-relationships ctx oid conj [])
            embedded (cs/reduce-concept-embeddings ctx oid (fn [acc uri _] (conj acc uri)) #{})
            scheme-of (into {} (map (fn [c] [(:uri c) (uri-scheme (:uri c))]) concepts))
            all-occ   (filter #(occ? (:uri %)) concepts)          ; case-insensitive occupation nodes
            all-occ-uris (set (map :uri all-occ))
            canon-occ-uris (set (map :uri (filter #(= canon (uri-scheme (:uri %))) concepts)))
            variant-occ-uris (set (remove canon-occ-uris all-occ-uris))]
        (println "\n========== CONNECT-4 LIVE FORENSICS ==========")
        (println "db-file:" db-file)
        (println "tenant :" tenant)
        (println "oid    :" oid " (VERIFIED from event_tags upstream)")
        (println "canonical occupation scheme (Occupation Data):" (pr-str canon))
        (println "concepts:" (count concepts) " relationships:" (count rels) " embedded-uris:" (count embedded))
        (println "\nconcept scheme breakdown (top 15):")
        (doseq [[s n] (->> concepts (map (comp uri-scheme :uri)) frequencies (sort-by val >) (take 15))]
          (println (format "   %6d  %s" n s)))

        ;; ---- occupation node-set case analysis (the variant-scheme split) ----
        (println "\n--- occupation node sets (case-insensitive) ---")
        (println "  all occupation nodes (any case):" (count all-occ-uris))
        (println "  canonical" (pr-str canon) "nodes:" (count canon-occ-uris))
        (println "  VARIANT-case occupation nodes  :" (count variant-occ-uris)
                 (pr-str (vec (take 3 (map uri-scheme variant-occ-uris)))))
        (let [canon-tails (set (map uri-tail canon-occ-uris))
              var-tails   (set (map uri-tail variant-occ-uris))
              overlap     (clojure.set/intersection canon-tails var-tails)]
          (println "  SOC-tail overlap (same entities minted twice under diff case):"
                   (count overlap) "of" (count var-tails) "variant tails")
          (println "  sample overlapping SOC tails:" (pr-str (vec (take 5 overlap)))))

        ;; ---- edge participation + source-scheme breakdown ----
        (let [src-scheme-freq (->> rels (map #(uri-scheme (:source-uri %))) frequencies (sort-by val >))
              canon-src-edges (filter #(canon-occ-uris (:source-uri %)) rels)      ; source is a CANONICAL occ node
              variant-src-edges (filter #(variant-occ-uris (:source-uri %)) rels)  ; source is a case-VARIANT occ node
              stub-src-edges  (filter #(= "entity" (str/lower-case (uri-scheme (:source-uri %)))) rels)
              any-occ-in-edge (->> rels
                                   (filter #(or (all-occ-uris (:source-uri %)) (all-occ-uris (:target-uri %))))
                                   (mapcat (fn [r] (filter all-occ-uris [(:source-uri r) (:target-uri r)])))
                                   distinct count)
              canon-in-edge   (->> rels
                                   (filter #(or (canon-occ-uris (:source-uri %)) (canon-occ-uris (:target-uri %))))
                                   (mapcat (fn [r] (filter canon-occ-uris [(:source-uri r) (:target-uri r)])))
                                   distinct count)]
          (println "\n--- edge source-uri SCHEME breakdown (CONNECT-3c live proof) ---")
          (doseq [[s n] src-scheme-freq]
            (println (format "   %6d  source-scheme = %s" n s)))
          (println "  entity/* STUB source edges       :" (count stub-src-edges)
                   (if (zero? (count stub-src-edges)) "  <-- ZERO entity/* stubs" "  <-- !! STUBS PRESENT"))
          (println "  CANONICAL" (pr-str canon) "source edges:" (count canon-src-edges))
          (println "  case-VARIANT occupation source edges :" (count variant-src-edges))
          (println "\n--- occupation edge-participation ---")
          (println "  occupation nodes participating in >=1 edge (ANY case):" any-occ-in-edge)
          (println "  CANONICAL" (pr-str canon) "nodes participating in >=1 edge:" canon-in-edge)

          ;; ---- work with whichever occupation node-set actually bears the edges ----
          (let [edge-src-uris (set (map :source-uri rels))
                bearing-canon? (some canon-occ-uris edge-src-uris)
                active-occ-uris (if bearing-canon? canon-occ-uris variant-occ-uris)
                active-edges (filter #(active-occ-uris (:source-uri %)) rels)
                elem-targets (map :target-uri active-edges)
                distinct-elems (distinct elem-targets)]
            (println "\n--- element-node dedup (on the edge-bearing occupation set) ---")
            (println "  edge-bearing occupation scheme:" (if bearing-canon? (pr-str canon) "VARIANT-case"))
            (println "  occupation-source edges:" (count active-edges)
                     "  distinct element targets:" (count distinct-elems))
            (println "  target element schemes:"
                     (pr-str (->> distinct-elems (map scheme-of) frequencies (into (sorted-map)))))

            ;; ---- sampled occupation's elements across sheets ----
            (let [by-src (group-by :source-uri active-edges)
                  [samp-occ samp-edges] (->> by-src
                                             (sort-by (fn [[_ es]] (- (count (distinct (map (comp scheme-of :target-uri) es))))))
                                             first)
                  samp-by-scheme (group-by (comp scheme-of :target-uri) samp-edges)]
              (println "\n--- sampled occupation (most element-scheme coverage) ---")
              (println "  occupation:" samp-occ)
              (println "  edges:" (count samp-edges) " across element schemes:" (pr-str (vec (keys samp-by-scheme))))
              (doseq [[esch es] samp-by-scheme]
                (println (format "     %-16s %4d edges  sample: %s" esch (count es)
                                 (pr-str (vec (take 3 (map :target-uri es))))))))

            ;; ---- BFS: occupation -> element -> DIFFERENT occupation ----
            ;; Prefer a SMALL-fanout bridge (shared by exactly 2-3 occupations) so
            ;; the retrieval scorer's `max-results` cap doesn't crowd the partner
            ;; occupation out of the top-K (mirrors the connect3a proof shape).
            (let [elem->occs (->> active-edges
                                  (group-by :target-uri)
                                  (map (fn [[t es]] [t (set (map :source-uri es))]))
                                  (filter (fn [[_ occs]] (>= (count occs) 2))))
                  small-bridge (->> elem->occs (sort-by (fn [[_ occs]] (count occs))) first)
                  big-bridge   (->> elem->occs (sort-by (fn [[_ occs]] (- (count occs)))) first)
                  [bridge-elem bridge-occs] small-bridge
                  seed-occ (first bridge-occs)
                  partner-occ (first (disj bridge-occs seed-occ))
                  graph (retrieval/build-concept-graph ctx)
                  ;; graph reverse-edge diagnostic: does the bridge element carry
                  ;; incoming (reverse) edges back to occupations?
                  elem-neighbors (get (:edges graph) bridge-elem [])
                  elem-occ-neighbors (->> elem-neighbors (map :to) (filter occ?) count)
                  ;; (1) the REAL retrieval surface (top-100 cap), and (2) the same
                  ;; core BFS with a high max-results so hub dilution can't hide the hop.
                  reached-retrieval (when seed-occ
                                      (->> (retrieval/expand-concept-neighborhood [seed-occ] :graph graph :max-depth 2)
                                           (map :uri) set))
                  reached-full (when seed-occ
                                 (->> (graph/bfs-spreading-activation graph [seed-occ]
                                                                      {:max-depth 2 :max-results 200000})
                                      (map :uri) set))
                  other-occ (when reached-full
                              (->> reached-full (filter #(and (occ? %) (not= % seed-occ))) (take 5) vec))
                  bfs-ok? (boolean (and reached-full bridge-elem
                                        (contains? reached-full bridge-elem) (seq other-occ)))]
              (println "\n--- BFS occupation -> element -> DIFFERENT occupation ---")
              (println "  shared-element bridges (element targeted by >=2 occupations):" (count elem->occs))
              (println "  BIG bridge (max fan-out):" (first big-bridge) "shared by" (count (second big-bridge)) "occupations")
              (println "  small bridge (min fan-out, used):" bridge-elem "shared by" (count bridge-occs) "occupations")
              (println "  graph total edge-nodes:" (count (:edges graph)) " graph nodes:" (:node-count graph))
              (println "  bridge element REVERSE edges back to occupations:" elem-occ-neighbors
                       (if (pos? elem-occ-neighbors) "<-- reverse edges PRESENT (traversable)" "<-- NO reverse edges"))
              (println "  BFS seed occupation:" seed-occ)
              (println "  expected partner occupation (shares the bridge):" partner-occ)
              (println "  [retrieval top-100] reached bridge element?:"
                       (boolean (and reached-retrieval (contains? reached-retrieval bridge-elem)))
                       " reached partner?:" (boolean (and reached-retrieval (contains? reached-retrieval partner-occ))))
              (println "  [full BFS] reached bridge element?:" (boolean (and reached-full (contains? reached-full bridge-elem))))
              (println "  [full BFS] reached partner occupation?:" (boolean (and reached-full (contains? reached-full partner-occ))))
              (println "  [full BFS] sample DIFFERENT occupations reached:" (pr-str other-occ))

              ;; ---- feed the verdict fn — STRICT canonical interpretation ----
              (let [strict-summary {:occupation-edge-participation canon-in-edge
                                    :canonical-source-edges (count canon-src-edges)
                                    :entity-stub-edges (count stub-src-edges)
                                    :bfs-canonical->related? (boolean (and bearing-canon? bfs-ok?))
                                    :element-node-counts {:distinct-elements (count distinct-elems)
                                                          :element-edges (count active-edges)}}
                    strict-verdict (cacc/connectivity-verdict strict-summary)]
                (println "\n========== VERDICT — STRICT canonical (edges must attach to" (pr-str canon) "nodes) ==========")
                (pp/pprint strict-summary)
                (println "PASS?:" (:pass? strict-verdict))
                (doseq [r (:reasons strict-verdict)]
                  (println (format "  %-38s %-6s %s" (name (:criterion r)) (str (:pass? r)) (:detail r))))
                (println "==============================")

                ;; ---- SECONDARY: case-INSENSITIVE verdict (treats Occupation ==
                ;; occupation) — shows the connectivity mechanism itself is real;
                ;; only a case-normalization gap separates the edges from the
                ;; canonical Occupation-Data URIs.
                (let [ci-summary {:occupation-edge-participation any-occ-in-edge
                                  :canonical-source-edges (count active-edges)
                                  :entity-stub-edges (count stub-src-edges)
                                  :bfs-canonical->related? bfs-ok?
                                  :element-node-counts {:distinct-elements (count distinct-elems)
                                                        :element-edges (count active-edges)}}
                      ci-verdict (cacc/connectivity-verdict ci-summary)]
                  (println "\n===== VERDICT — case-INSENSITIVE (Occupation == occupation): the connectivity mechanism =====")
                  (pp/pprint ci-summary)
                  (println "PASS?:" (:pass? ci-verdict))
                  (doseq [r (:reasons ci-verdict)]
                    (println (format "  %-38s %-6s %s" (name (:criterion r)) (str (:pass? r)) (:detail r))))
                  (println "==============================")))))))
      (finally (es/stop store) (kv/stop cache)
               (let [d (java.io.File. cache-dir)]
                 (when (.exists d) (doseq [f (.listFiles d)] (.delete f)) (.delete d)))))))
