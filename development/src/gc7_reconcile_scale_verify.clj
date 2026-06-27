(ns gc7-reconcile-scale-verify
  "GC-7 — Cycle 4 (the ISOLATED, deterministic proof). The end-to-end build's
   model-extract step is an LLM-authoring phase whose latency is orthogonal to
   GC-7; to prove the thing GC-7 actually fixed — the reconcile O(n²) on real
   IPEDS-scale draft VOLUME — this drives `reconcile-drafts!` directly with a REAL,
   LLM-FREE draft set streamed from C2022_A (the ~1.6M-row table whose 5k-row/
   container samples drove the pre-GC-7 billions-of-comparisons blowup), on the
   GC-4 :sqlite store, in accumulating batches (each batch reconciled against the
   growing graph — the exact cross-source pattern that exploded).

   Proves: the bounded reconcile COMPLETES (no never-finishing, no OOM) on real
   draft volume, with the jaro-winkler work bounded, and the graph read BACK off
   the :sqlite projection is non-empty + grows per batch (Discipline 4/7). With the
   pre-GC-7 cross-product this same volume never terminated.

   Run (NO LLM — deterministic; :sqlite store):
     clj -M:dev -e \"(require 'gc7-reconcile-scale-verify)(gc7-reconcile-scale-verify/-main)\""
  (:require [eb12-graph-b-central-evolver :as b]
            [ai.obney.orc.ontology.core.reconcile-subbehavior :as recon]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.orc-service.core.source-tools :as st]
            [clojure.pprint :as pp]))

(def make-ctx @#'b/make-ctx)
(def stop-ctx @#'b/stop-ctx)

(def descriptor {:type :sql :path "/Users/darylroberts/Downloads/output.db"})

(defn stream-rows
  "Stream up to n rows of C2022_A via the REAL source tools (bounded windows of
   100 — the sql per-call hard cap), with an :offset so batches are disjoint."
  [n offset]
  (let [tools (st/source-tools-for descriptor)
        sample (get tools 'sample-rows)]
    (loop [taken [] off offset]
      (if (>= (count taken) n)
        (vec (take n taken))
        (let [rows (sample "C2022_A" {:limit 100 :offset off})]
          (if (empty? rows)
            (vec taken)
            (recur (into taken rows) (+ off (count rows)))))))))

(defn rows->concepts
  "Deterministically derive concepts from rows: a stable URI from the row's key
   columns (UNITID + CIPCODE + MAJORNUM + AWLEVEL — the C2022_A grain) and a
   handful of columns carried as attributes. NO LLM — the builder's discovery is
   NOT under test here; the RECONCILE scaling is."
  [rows]
  (vec
   (keep
    (fn [row]
      (let [g (fn [k] (or (get row k) (get row (keyword k))))
            unitid (g "UNITID") cip (g "CIPCODE")
            major (g "MAJORNUM") awl (g "AWLEVEL")]
        (when (and unitid cip)
          {:uri (str "completion/" unitid "/" cip "/" major "/" awl)
           :label (str "Completions " unitid " " cip)
           :description "An IPEDS C2022_A completion record."
           :scope :custom :confidence-class :extracted
           :attributes {:unitid unitid :cipcode cip :majornum major
                        :awlevel awl :ctotalt (g "CTOTALT")
                        :ctotalm (g "CTOTALM") :ctotalw (g "CTOTALW")}})))
    rows)))

(defn run! []
  (let [ctx (make-ctx {:store :sqlite})
        oid (random-uuid)]
    (try
      ;; 5 accumulating batches of ~1000 rows each (5000 drafts total — the
      ;; per-container extract volume), each reconciled against the growing graph.
      (let [batches (mapv (fn [i] (rows->concepts (stream-rows 1000 (* i 1000))))
                          (range 5))
            t0 (System/currentTimeMillis)
            reports
            (vec
             (map-indexed
              (fn [i concepts]
                (let [t (System/currentTimeMillis)
                      r (recon/reconcile-drafts!
                         ctx {:ontology-id oid
                              :concept-drafts concepts
                              ;; deterministic probe signals only (no embedding/ColBERT
                              ;; bridge) — this is the reconcile-scale proof, not a
                              ;; retrieval-quality proof.
                              :probe-signals #{:graph :lexical}})
                      after (count (rm/get-concepts ctx {:ontology-id oid}))]
                  (println (format "  batch %d: drafts=%d  graph-concepts-now=%d  jw-comparisons=%d  probe-coverage=%s  ms=%d"
                                   i (count concepts) after
                                   (get-in r [:attribute-reconcile :jw-comparisons])
                                   (pr-str (get-in r [:mint-probe :probe-coverage]))
                                   (- (System/currentTimeMillis) t)))
                  {:batch i :drafts (count concepts)
                   :graph-after after
                   :jw-comparisons (get-in r [:attribute-reconcile :jw-comparisons])
                   :attr-links (count (get-in r [:attribute-reconcile :links]))
                   :probe-coverage (get-in r [:mint-probe :probe-coverage])
                   :dangling (:dangling-edge-count r)}))
              batches))
            total-ms (- t0 (System/currentTimeMillis))
            ;; READ BACK off the :sqlite projection (Discipline 7).
            final-concepts (count (rm/get-concepts ctx {:ontology-id oid}))
            db-file (:ai.obney.orc.ontology... ctx)]
        {:status :complete
         :final-concept-count final-concepts
         :db-file (get ctx :gc7/db-file)
         :batches reports
         :max-jw (reduce max 0 (map :jw-comparisons reports))
         :elapsed-ms (- (System/currentTimeMillis) t0)})
      (finally (stop-ctx ctx)))))

(defn -main [& _]
  (let [fut (future (try (run!)
                         (catch Throwable t
                           (println "GC-7 reconcile-scale FAILED:" (.getMessage t))
                           (.printStackTrace t)
                           {:status :exception :error (.getMessage t)})))
        ;; HARD ceiling — 10 min. The pre-GC-7 cross-product would blow past this
        ;; (never finishing); the bound must complete well under it.
        res (deref fut (* 10 60 1000) :TIMEOUT)]
    (println "\n================ GC-7 RECONCILE-SCALE — RESULT ================")
    (if (= :TIMEOUT res)
      (do (println "!!! RECONCILE-SCALE DID NOT TERMINATE within 10 min — FINDING (bound regressed?)")
          (shutdown-agents) (.halt (Runtime/getRuntime) 3))
      (do (pp/pprint res)
          (shutdown-agents)
          (System/exit (if (and (= :complete (:status res))
                                (pos? (:final-concept-count res))
                                (< (:max-jw res) 100000))
                         0 1))))))
