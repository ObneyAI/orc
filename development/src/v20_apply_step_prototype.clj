(ns v20-apply-step-prototype
  "V20 PROTOTYPE — deterministic full-extraction apply-step.

   Question this prototype settles (before TDD): given a model-authored
   extraction TRANSFORM (a string of Clojure source defining
   `(fn [row] -> {:concept-drafts [...] :relationship-drafts [...]})`)
   that was validated on a SAMPLE, does streaming the FULL real source
   via V19's stream-all + applying the transform per row yield a
   COMPREHENSIVE, referentially-integral draft set — with per-row errors
   COUNTED (not aborting) — that flows through V18's compile?

   This driver hand-authors a transform (standing in for the model's
   sample-validated transform) and runs the seam over the REAL IPEDS
   SQLite DB. The real model-authored path is exercised in the live
   verify (v20_full_extraction_live_verify).

   No mocks of the source: real SQLite file, real stream-all, real
   SCI eval of the transform string."
  (:require [ai.obney.orc.ontology.core.rlm-discovery :as rlm-discovery]
            [ai.obney.orc.orc-service.core.source-tools :as source-tools]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [clojure.pprint :as pp]))

(def ipeds-db "/Users/darylroberts/Downloads/output.db")

(defn- make-ctx []
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/v20-proto-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB
                         {:storage-dir dir :db-name "proto"
                          :map-size (* 4 1024 1024 1024)}))]
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
  (when-let [s (:event-store ctx)] (es/stop s))
  (when-let [d (::cache-dir ctx)]
    (let [f (java.io.File. d)]
      (when (.exists f)
        (doseq [c (.listFiles f)] (.delete c))
        (.delete f)))))

;; A HAND-AUTHORED transform standing in for the model's sample-validated
;; transform. It mints one program node per IPEDS C2022_A row keyed on the
;; institution UNITID + CIPCODE, plus a shareable cip: node, and an edge.
;; Entity-as-node: the program is a NODE bearing its own attributes, not an
;; edge. Note: clojure.core only (matching the sandbox).
(def sample-transform-str
  "(fn [row]
     (let [unitid (get row :UNITID)
           award (get row :AWLEVELC)
           total (get row :CSTOTLT)]
       (if (and unitid award)
         {:concept-drafts
          [{:uri (str \"program:\" unitid \"-\" award)
            :label (str \"Completions award-level \" award \" at institution \" unitid)
            :scope :custom
            :attributes {:unitid unitid :awlevel award :completions total}
            :evidence [{:source \"C2022_C.UNITID\" :quote (str unitid)}]}
           {:uri (str \"institution:\" unitid)
            :label (str \"Institution \" unitid)
            :scope :custom
            :evidence [{:source \"C2022_C.UNITID\" :quote (str unitid)}]}]
          :relationship-drafts
          [{:source-uri (str \"program:\" unitid \"-\" award)
            :target-uri (str \"institution:\" unitid)
            :predicate \"offeredBy\"
            :confidence-class :extracted
            :evidence [{:source \"row\" :quote (str unitid)}]}]}
         {:concept-drafts [] :relationship-drafts []})))")

(defn run-prototype! []
  (let [ctx (make-ctx)
        oid (random-uuid)]
    (try
      (println "=== V20 APPLY-STEP PROTOTYPE (real IPEDS SQLite) ===")
      (let [table "C2022_C"
            descriptor {:name :ipeds :type :sql :path ipeds-db}
            ;; count + extract via the SAME source-tools the sandbox grants.
            tools (source-tools/source-tools-for descriptor)
            count-rows (get tools 'count-rows)
            cnt (count-rows table)
            _ (do (println "count-rows" table ":" cnt) (flush))
            t0 (System/currentTimeMillis)
            apply-result (rlm-discovery/apply-extraction-transform!
                          {:descriptor descriptor
                           :selector table
                           :transform-source sample-transform-str
                           :window 100})
            _ (do (println "apply-step wall-ms:" (- (System/currentTimeMillis) t0)) (flush))
            _ (println "rows-streamed:" (:rows-streamed apply-result)
                       " rows-errored:" (:rows-errored apply-result)
                       " concept-drafts:" (count (:concept-drafts apply-result))
                       " relationship-drafts:" (count (:relationship-drafts apply-result)))
            ;; compile through V18 referential integrity
            disc-out {:status :emitted-drafts
                      :emitted-concepts (:concept-drafts apply-result)
                      :emitted-relationships (:relationship-drafts apply-result)
                      :emitted-axioms []
                      :rlm-trace [{:apply-step apply-result}]}
            stub (rlm-discovery/compile-discovery-source! ctx oid disc-out)
            concepts (filter #(= oid (:ontology-id %)) (rm/get-concepts ctx {}))
            program-nodes (filter #(clojure.string/starts-with? (str (:uri %)) "program:") concepts)]
        (println "\n--- compile provenance ---")
        (pp/pprint (:discovery-provenance stub))
        (println "\n--- read-back ---")
        (println "concepts in graph:" (count concepts))
        (println "program: nodes (entity-as-node):" (count program-nodes))
        (println "endpoint-resolution:"
                 (get-in stub [:discovery-provenance :every-edge-endpoint-resolves?]))
        {:count cnt
         :apply-result (dissoc apply-result :concept-drafts :relationship-drafts)
         :sample-concept-drafts (take 3 (:concept-drafts apply-result))
         :provenance (:discovery-provenance stub)
         :concepts-in-graph (count concepts)
         :program-nodes (count program-nodes)})
      (finally (stop-ctx ctx)))))

(comment
  (require '[v20-apply-step-prototype :as p] :reload)
  (p/run-prototype!))
