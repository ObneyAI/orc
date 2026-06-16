(ns v18-referential-integrity-live-verify
  "V18 live verification — referential integrity as an always-on
   structural invariant.

   Discipline #4: synthetic tests are the FLOOR; this drives the real
   compile path over the EXACT captured V17-style dangling drafts on a
   REAL Grain event store (real commands → schema-validated events →
   real projections). It reproduces the precise V17 failure condition
   (a graph built with NO supplied validation shapes whose relationship
   edges reference concept URIs that were never minted) and proves the
   V18 fix flips `every-edge-endpoint-resolves` from FALSE to TRUE, with
   the implied-mint + ambiguity counts surfaced.

   No OpenRouter key required: we replay CAPTURED V17 drafts (verbatim
   from docs/build-timeline/live-verify/V17-graph-b-full-scale.md), so
   the run is deterministic and reproducible. The substrate is real
   Grain — no mocks of the event store, no try/catch swallowing.

   USAGE (REPL with :dev:test alias):
     (require '[v18-referential-integrity-live-verify :as v])
     (v/run!)"
  (:require [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [clojure.pprint :as pp]))

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/v18-live-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "live"}))]
    {:event-store store
     :cache cache
     :tenant-id (random-uuid)
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     :event-pubsub ps
     ::cache-dir dir}))

(defn- stop-ctx [ctx]
  (rmp/l1-clear!)
  (when-let [ps (:event-pubsub ctx)] (pubsub/stop ps))
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [s (:event-store ctx)] (es/stop s)))

(defn- ev [src q] [{:source src :quote q}])

;; -----------------------------------------------------------------------------
;; CAPTURED V17 drafts (verbatim shapes from the V17 live-verify doc).
;;
;; The V17 artifact minted cip: concepts only from the alphabetical CIPCodes
;; window it sampled (cip:01.* agriculture) but emitted offersProgram edges to
;; the CIPs completions actually use (cip:11.0201, cip:10.0203, ...) — 119/249
;; edges dangled. O*NET emitted relatedTo edges between 8-digit SOC codes
;; (soc:11-1011.03) while many soc concepts are 6-digit — endpoints didn't
;; match (the structural near-variant case).
;;
;; Below: a faithful slice — concepts the builder DID mint, plus the exact
;; dangling-edge samples the V17 doc captured, PLUS the soc 6-digit/8-digit
;; near-variant pair (a structurally-similar encoding variant, the V18
;; ambiguity path — detected by general URI similarity, NOT a code-format rule).
;; -----------------------------------------------------------------------------

(def captured-v17-drafts
  {:status :emitted-drafts
   :emitted-concepts
   ;; The cip: concepts the builder minted (alphabetical agriculture window),
   ;; plus the 6-digit soc concept that the 8-digit relatedTo edges dangle near.
   [{:uri "cip:01.1105" :label "Plant Protection and Integrated Pest Management."
     :description "" :scope :custom :evidence (ev "CIPCode" "01.1105")}
    {:uri "cip:01.8107" :label "Veterinary Microbiology and Immunobiology."
     :description "" :scope :custom :evidence (ev "CIPCode" "01.8107")}
    {:uri "cip:01.06" :label "Applied Horticulture and Horticultural Business Services."
     :description "" :scope :custom :evidence (ev "CIPCode" "01.06")}
    {:uri "unitid:248527" :label "Some Institution"
     :description "" :scope :custom :evidence (ev "HD2022" "248527")}
    {:uri "unitid:159522" :label "Another Institution"
     :description "" :scope :custom :evidence (ev "HD2022" "159522")}
    {:uri "unitid:437103" :label "Third Institution"
     :description "" :scope :custom :evidence (ev "HD2022" "437103")}
    {:uri "unitid:158431" :label "Fourth Institution"
     :description "" :scope :custom :evidence (ev "HD2022" "158431")}
    {:uri "unitid:242413" :label "Fifth Institution"
     :description "" :scope :custom :evidence (ev "HD2022" "242413")}
    {:uri "unitid:160667" :label "Sixth Institution"
     :description "" :scope :custom :evidence (ev "HD2022" "160667")}
    ;; A 6-digit SOC concept (O*NET minted the 6-digit form for some); the
    ;; relatedTo edges below reference the 8-digit form — a near-variant.
    {:uri "soc:11-1011" :label "Chief Executives"
     :description "" :scope :custom :evidence (ev "Occupation Data" "11-1011")}
    {:uri "soc:19-2041" :label "Environmental Scientists"
     :description "" :scope :custom :evidence (ev "Occupation Data" "19-2041")}
    {:uri "soc:15-1299" :label "Computer Occupations, All Other"
     :description "" :scope :custom :evidence (ev "Occupation Data" "15-1299")}]

   ;; The EXACT dangling-edge samples captured in the V17 doc — endpoints that
   ;; were never minted as concepts. offersProgram edges to completions-CIPs the
   ;; builder didn't mint; relatedTo edges between 8-digit SOC codes.
   :emitted-relationships
   [{:source-uri "unitid:248527" :target-uri "cip:12" :predicate "offersProgram"
     :confidence-class :extracted :evidence (ev "C2022_A" "12")}
    {:source-uri "soc:11-1011.03" :target-uri "soc:19-2041.00" :predicate "relatedTo"
     :confidence-class :extracted :evidence (ev "Related Occupations" "11-1011.03")}
    {:source-uri "unitid:159522" :target-uri "cip:12.04" :predicate "offersProgram"
     :confidence-class :extracted :evidence (ev "C2022_A" "12.04")}
    {:source-uri "unitid:437103" :target-uri "cip:10.02" :predicate "offersProgram"
     :confidence-class :extracted :evidence (ev "C2022_A" "10.02")}
    {:source-uri "unitid:158431" :target-uri "cip:11.0201" :predicate "offersProgram"
     :confidence-class :extracted :evidence (ev "C2022_A" "11.0201")}
    {:source-uri "unitid:242413" :target-uri "cip:12" :predicate "offersProgram"
     :confidence-class :extracted :evidence (ev "C2022_A" "12")}
    {:source-uri "soc:11-1011.03" :target-uri "soc:15-1299.09" :predicate "relatedTo"
     :confidence-class :extracted :evidence (ev "Related Occupations" "11-1011.03")}
    {:source-uri "unitid:160667" :target-uri "cip:11.09" :predicate "offersProgram"
     :confidence-class :extracted :evidence (ev "C2022_A" "11.09")}]
   :emitted-axioms []
   :rlm-trace ["captured V17 dangling-edge slice"]
   :patterns-offered 5})

(defn run! []
  (let [ctx (make-ctx)]
    (try
      (let [oid (random-uuid)
            n-rel (count (:emitted-relationships captured-v17-drafts))
            ;; --- BEFORE: prove these edges WOULD dangle (the V17 condition) ---
            concept-uris-pre (set (map :uri (:emitted-concepts captured-v17-drafts)))
            would-dangle (filter (fn [r]
                                   (or (not (contains? concept-uris-pre (:source-uri r)))
                                       (not (contains? concept-uris-pre (:target-uri r)))))
                                 (:emitted-relationships captured-v17-drafts))

            ;; --- COMPILE through the REAL command path on real Grain ---
            stub (ontology/compile-discovery-source! ctx oid captured-v17-drafts)
            prov (:discovery-provenance stub)

            ;; --- AFTER: read back the REAL projection + recompute integrity ---
            concepts (filter #(= oid (:ontology-id %)) (rm/get-concepts ctx {}))
            rels (filter #(= oid (:ontology-id %)) (rm/get-relationships ctx))
            uris (set (map :uri concepts))
            still-dangling (filter (fn [r]
                                     (or (not (contains? uris (:source-uri r)))
                                         (not (contains? uris (:target-uri r)))))
                                   rels)
            implied (filter #(get-in % [:attributes :implied?]) concepts)
            ambiguous (filter #(get-in % [:attributes :ambiguous?]) concepts)]
        (println "================ V18 LIVE VERIFY ================")
        (println "ontology-id:" oid)
        (println)
        (println "--- BEFORE compile (the V17 condition: NO shapes supplied) ---")
        (println "concepts minted by builder:" (count (:emitted-concepts captured-v17-drafts)))
        (println "relationship drafts        :" n-rel)
        (println "edges that WOULD dangle    :" (count would-dangle)
                 "of" n-rel)
        (println)
        (println "--- AFTER compile (real Grain projection read-back) ---")
        (println "concepts in graph          :" (count concepts)
                 (str "(" (count (:emitted-concepts captured-v17-drafts))
                      " explicit + " (count implied) " implied)"))
        (println "relationships in graph     :" (count rels))
        (println "edges STILL dangling       :" (count still-dangling))
        (println "every-edge-endpoint-resolves (recomputed from projection):"
                 (empty? still-dangling))
        (println)
        (println "--- provenance counts surfaced by compile-discovery-source! ---")
        (println ":implied-concepts-minted      " (:implied-concepts-minted prov))
        (println ":ambiguities-flagged          " (:ambiguities-flagged prov))
        (println ":unresolved-endpoints         " (:unresolved-endpoints prov))
        (println ":every-edge-endpoint-resolves?" (:every-edge-endpoint-resolves? prov))
        (println)
        (println "--- ambiguities (near-variant SOC encodings, structural detect) ---")
        (pp/pprint (:ambiguities prov))
        (println)
        (println "--- sample implied concepts (flagged low-confidence / enrichment) ---")
        (pp/pprint (mapv #(select-keys % [:uri :label :attributes])
                         (take 4 implied)))
        (println)
        (println "VERDICT every-edge-endpoint-resolves flipped FALSE->TRUE:"
                 (and (pos? (count would-dangle))
                      (empty? still-dangling)
                      (true? (:every-edge-endpoint-resolves? prov))))
        (println "================================================")
        {:before-dangling (count would-dangle)
         :after-dangling (count still-dangling)
         :provenance prov})
      (finally (stop-ctx ctx)))))
