(ns stream-slice0-prototype
  "STREAM Slice 0 — THROWAWAY prototype-at-scale that GATES the streaming
   ontology-pipeline initiative (parent plan: precious-sleeping-kurzweil).

   ONE job: prove (or DISPROVE, with hard heap numbers) that reducing directly
   over the streaming `es/read` event store is bounded-memory and byte-identical
   to the current full-materialization projection, at ~50k concepts under a
   constrained -Xmx.

   Multi-process by design: each measurement runs in its OWN JVM (its own -Xmx)
   so an OOM in one path can't poison another, and L1 (in-process) cache starts
   cold. The event STORE is a PERSISTENT SQLite file at a FIXED path shared
   across processes; tenant-id + ontology-id are FIXED constants so every phase
   agrees on scope.

   Phases (first CLI arg):
     gen <n-concepts> <n-embed> [n-rels]  — generate into the fixed sqlite store
     project-concepts                     — peak heap of (vals (project :ontology/concepts))
     reduce-concepts                      — peak heap of hand-rolled reduce-concepts
     invariance                           — assert reduce-concepts state = project state
     get-all-embeddings                   — peak heap of get-all-concept-embeddings
     reduce-embeddings                    — peak heap of reduce-concept-embeddings

   Store + cache wiring MIRRORS eb12 make-ctx's :sqlite branch (es/start sqlite +
   LMDB kv cache), but with a FIXED sqlite path (make-ctx uses a random per-run
   dir, unusable across processes). The REDUCER + es/read + the windowed pager are
   the REAL registered ones (discipline #8 — no forked notion of concept state)."
  (:require [ai.obney.orc.ontology.interface.schemas]      ;; register event schemas (append validation)
            [ai.obney.orc.ontology.core.read-models :as rm] ;; register :ontology/concepts + reducer
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface] ;; resolve :sqlite es/start multimethod
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time])
  (:import [java.io File]))

;; =============================================================================
;; Fixed identity + paths (shared across processes)
;; =============================================================================

(def ^:private base-dir "/tmp/slice0")
(def ^:private db-file (str base-dir "/events.db"))
(def ^:private TENANT-ID #uuid "00000000-0000-0000-0000-0000000000aa")
(def ^:private ONT-ID    #uuid "00000000-0000-0000-0000-0000000000b0")
(def ^:private ONT-TAG   #{[:ontology ONT-ID]})
(def ^:private EMBED-DIM 384)
(def ^:private WINDOW    5000) ;; events per es/read page (the pager's :limit)

;; =============================================================================
;; Ctx builders (mirror eb12 make-ctx :sqlite branch; FIXED db path)
;; =============================================================================

(defn- rm-rf [^File f]
  (when (.exists f)
    (when (.isDirectory f) (doseq [c (.listFiles f)] (rm-rf c)))
    (.delete f)))

(defn- open-store
  "Start the REAL SQLite-v3 store at the fixed path. No :event-pubsub, so
   es/append does NOT fan out to processors — keeps generation bounded and
   avoids needing the processor registry (readers don't need it either)."
  []
  (.mkdirs (File. base-dir))
  (es/start {:conn {:type :sqlite :database-file db-file :maximum-pool-size 4}
             :logger nil}))

(defn- fresh-cache
  "A FRESH LMDB kv cache in a throwaway dir — so rmp/project always sees an L2
   MISS and does a genuine full projection (no warm cache hiding the cost)."
  []
  (let [dir (str base-dir "/cache-" (random-uuid))]
    {:cache (kv/start (lmdb/->KV-Store-LMDB
                       {:storage-dir dir :db-name "slice0"
                        :map-size (* 4 1024 1024 1024)}))
     :dir dir}))

(defn- reader-ctx
  "Ctx for a measurement phase: real store + fresh cache + fixed tenant."
  []
  (let [store (open-store)
        {:keys [cache dir]} (fresh-cache)]
    {:event-store store :cache cache :tenant-id TENANT-ID ::cache-dir dir}))

(defn- close-ctx! [ctx]
  (rmp/l1-clear!)
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [s (:event-store ctx)] (es/stop s))
  (when-let [d (::cache-dir ctx)] (rm-rf (File. d))))

;; =============================================================================
;; Heap instrumentation
;; =============================================================================

(defn- used-mb ^double []
  (let [rt (Runtime/getRuntime)]
    (/ (double (- (.totalMemory rt) (.freeMemory rt))) 1048576.0)))

(defn- max-mb ^double [] (/ (double (.maxMemory (Runtime/getRuntime))) 1048576.0))

(defn- gc! [] (dotimes [_ 5] (System/gc) (Thread/sleep 60)))

(defn- measure
  "Run thunk while sampling peak used-heap in a background thread. Catches
   OutOfMemoryError so the phase reports OOM rather than dying silently."
  [label thunk]
  (gc!)
  (let [base (used-mb)
        peak (atom base)
        stop (atom false)
        sampler (Thread. (fn []
                           (while (not @stop)
                             (swap! peak max (used-mb))
                             (Thread/sleep 8))))
        start (System/currentTimeMillis)]
    (.start sampler)
    (let [outcome (try {:ok (thunk)}
                       (catch OutOfMemoryError e
                         {:oom (.getMessage e)})
                       (catch Throwable e
                         {:err (str (class e) ": " (.getMessage e))}))
          elapsed (- (System/currentTimeMillis) start)]
      (reset! stop true)
      (.join sampler 1000)
      (let [pk @peak]
        (gc!)
        (println
         (format "RESULT | %-24s | base=%6.0f peak=%6.0f delta=%6.0f max-heap=%6.0f | %6dms | %s"
                 label base pk (- pk base) (max-mb) elapsed
                 (cond (:ok outcome)  (str "OK " (pr-str (:ok outcome)))
                       (:oom outcome) (str "*** OOM *** " (:oom outcome))
                       :else          (str "ERR " (:err outcome)))))
        (flush)
        outcome))))

;; =============================================================================
;; The REGISTERED reducer + event types (discipline #8 — reuse, do NOT fork)
;; =============================================================================

(def ^:private concepts-entry (:ontology/concepts (rmp/global-read-model-registry)))
(def ^:private concept-rf    (:reducer-fn concepts-entry)) ;; (fn [state event] (concepts* ...))
(def ^:private concept-types (:events concepts-entry))     ;; = read-models/concept-events

;; =============================================================================
;; Windowed pager over es/read (copies the apply-aggregation-transform! cursor
;; loop: page by :limit, advance :after past the last event id, stop on a short
;; window). REUSED for both concepts and embeddings folds.
;; =============================================================================

(defn- reduce-scope
  "Fold `event-rf` over EVERY event matching (ONT-TAG ∩ types), paging es/read in
   WINDOW-sized pages. Returns {:state acc :events n :pages p}. es/read already
   streams one row at a time (JDBC plan); the windowing bounds the per-page SQL
   transaction — exactly the Slice-1 primitive shape."
  [ctx types event-rf init]
  (loop [after nil, acc init, total 0, pages 0]
    (let [q (cond-> {:tenant-id (:tenant-id ctx)
                     :tags ONT-TAG
                     :types types
                     :limit WINDOW}
              after (assoc :after after))
          ;; fold this page: apply event-rf to acc, track last event-id + count
          page (reduce (fn [m ev]
                         (-> m
                             (update :acc event-rf ev)
                             (assoc :last (:event/id ev))
                             (update :cnt inc)))
                       {:acc acc :last after :cnt 0}
                       (es/read (:event-store ctx) q))
          {acc' :acc last-id :last cnt :cnt} page]
      (if (< cnt WINDOW)
        {:state acc' :events (+ total cnt) :pages (inc pages)}
        (recur last-id acc' (+ total cnt) (inc pages))))))

(defn- reduce-concepts
  "Slice-1 primitive (prototype-local): fold the REGISTERED concepts reducer over
   the windowed es/read stream, then reduce `rf`/`init` over (vals state). State
   is built with the SAME reducer + SAME event scope + SAME id-order as
   rmp/project ⇒ must be byte-identical."
  [ctx rf init]
  (let [{:keys [state events pages]} (reduce-scope ctx concept-types concept-rf {})]
    {:state-map state
     :folded (reduce rf init (vals state))
     :events events :pages pages}))

(defn- reduce-concept-embeddings
  "Stream :ontology/concept-embedded events one at a time; keep only a per-URI
   FLAG (the uri string) and DISCARD the vector. Deliberately does NOT reuse the
   concept-embeddings* reducer — that reducer's whole job is to RETAIN the
   vector, which is the OOM we are eliminating. Heap = one vector transiently +
   a small uri set."
  [ctx]
  (let [uri-rf (fn [uri-set ev] (conj uri-set (:uri ev)))
        {:keys [state events pages]}
        (reduce-scope ctx #{:ontology/concept-embedded} uri-rf #{})]
    {:uri-count (count state) :events events :pages pages}))

;; =============================================================================
;; Generation (bounded — emit in batches, never hold all N in heap)
;; =============================================================================

(defn- concept-event [i]
  (es/->event
   {:type :ontology/concept-created
    :tags #{[:ontology ONT-ID] [:concept (random-uuid)]}
    :body {:ontology-id ONT-ID
           :concept-id (random-uuid)
           :uri (str "concept:" i)
           :label (str "Concept " i)
           :description (str "Synthetic concept number " i " for the slice-0 scale prototype.")
           :scope :custom
           :broader []
           :indicators []
           ;; modest :attributes (a handful of short key/values) — matches the
           ;; plan's "concept metadata ~50MB @ 50k" working set.
           :attributes {:idx i
                        :bucket (mod i 100)
                        :tag-a (str "a" (mod i 7))
                        :tag-b (str "b" (mod i 13))
                        :note "synthetic"}
           :created-at (str (time/now))}}))

(defn- embed-event [i vec384]
  (es/->event
   {:type :ontology/concept-embedded
    ;; tags MUST be [keyword uuid] tuples; scope by ontology only (the second
    ;; tag is irrelevant to the ONT-TAG-scoped reads).
    :tags #{[:ontology ONT-ID] [:concept (random-uuid)]}
    :body {:uri (str "concept:" i)
           :ontology-id ONT-ID
           :text-embedded (str "Concept " i)
           :field-source "label+description"
           :embedding vec384
           :model-id "synthetic-384"
           :embedded-at (str (time/now))}}))

(defn- rel-event [i]
  ;; skos:broader edge concept:i -> concept:(i-1) — exercises the reducer's
  ;; update-in mutation branch (narrower back-link on an already-landed concept).
  (es/->event
   {:type :ontology/relationship-created
    :tags #{[:ontology ONT-ID] [:concept (random-uuid)]}
    :body {:relationship-id (random-uuid)
           :ontology-id ONT-ID
           :source-uri (str "concept:" i)
           :target-uri (str "concept:" (dec i))
           :predicate "skos:broader"
           :created-at (str (time/now))}}))

(defn- rand-vec ^clojure.lang.PersistentVector []
  ;; real-length 384-dim double vector (schema requires :double)
  (vec (repeatedly EMBED-DIM #(double (rand)))))

(defn- append-batch! [store events]
  (let [r (es/append store {:tenant-id TENANT-ID :events (vec events)})]
    (when (and (map? r) (:cognitect.anomalies/category r))
      (throw (ex-info "append anomaly" {:anom r})))))

(defn- generate! [n-concepts n-embed n-rels]
  (println (format "GEN | concepts=%d embeddings=%d relationships=%d -> %s"
                   n-concepts n-embed n-rels db-file))
  ;; fresh store — delete any prior db + sidecars
  (doseq [s ["" "-wal" "-shm"]] (rm-rf (File. (str db-file s))))
  (let [store (open-store)
        batch 1000
        t0 (System/currentTimeMillis)]
    (try
      ;; concept-created
      (doseq [part (partition-all batch (range n-concepts))]
        (append-batch! store (map concept-event part)))
      (println (format "  concepts done (%dms, used=%.0fMB)"
                       (- (System/currentTimeMillis) t0) (used-mb)))
      ;; relationships (after concepts exist)
      (when (pos? n-rels)
        (doseq [part (partition-all batch (range 1 (inc n-rels)))]
          (append-batch! store (map rel-event part)))
        (println (format "  relationships done (%dms, used=%.0fMB)"
                         (- (System/currentTimeMillis) t0) (used-mb))))
      ;; concept-embedded — build vectors per-batch, discard after append (bounded)
      (doseq [part (partition-all batch (range n-embed))]
        (append-batch! store (map (fn [i] (embed-event i (rand-vec))) part)))
      (println (format "  embeddings done (%dms, used=%.0fMB)"
                       (- (System/currentTimeMillis) t0) (used-mb)))
      ;; sanity: count events back via streaming reduce (bounded)
      (let [total (reduce (fn [n _] (inc n)) 0
                          (es/read store {:tenant-id TENANT-ID :tags ONT-TAG}))]
        (println (format "  TOTAL events in scope = %d" total)))
      (finally (es/stop store)))))

;; =============================================================================
;; Phases
;; =============================================================================

(defn- phase-project-concepts []
  (let [ctx (reader-ctx)]
    (try
      (measure "vals-project-concepts"
               #(count (vals (rmp/project ctx :ontology/concepts {:tags ONT-TAG}))))
      (finally (close-ctx! ctx)))))

(defn- phase-reduce-concepts []
  (let [ctx (reader-ctx)]
    (try
      (measure "reduce-concepts"
               #(let [{:keys [folded events pages]} (reduce-concepts ctx (fn [n _] (inc n)) 0)]
                  {:concepts folded :events events :pages pages}))
      (finally (close-ctx! ctx)))))

(defn- phase-get-all-embeddings []
  (let [ctx (reader-ctx)]
    (try
      (measure "get-all-embeddings"
               #(count (vals (rm/get-all-concept-embeddings ctx {:ontology-id ONT-ID}))))
      (finally (close-ctx! ctx)))))

(defn- phase-reduce-embeddings []
  (let [ctx (reader-ctx)]
    (try
      (measure "reduce-concept-embeddings"
               #(let [{:keys [uri-count events pages]} (reduce-concept-embeddings ctx)]
                  {:embedded-uris uri-count :events events :pages pages}))
      (finally (close-ctx! ctx)))))

(defn- phase-invariance []
  (let [ctx (reader-ctx)]
    (try
      (gc!)
      (println (format "INVARIANCE | max-heap=%.0fMB baseline-used=%.0fMB" (max-mb) (used-mb)))
      (let [proj  (rmp/project ctx :ontology/concepts {:tags ONT-TAG})
            _     (println (format "  project built: %d concepts, used=%.0fMB" (count proj) (used-mb)))
            ;; fresh reducer fold over the same scope
            {:keys [state events pages]} (reduce-scope ctx concept-types concept-rf {})
            _     (println (format "  reduce built:  %d concepts (%d events, %d pages), used=%.0fMB"
                                   (count state) events pages (used-mb)))
            equal? (= proj state)
            hp (hash proj) hr (hash state)]
        (println (format "  count-equal? %s   structural-equal? %s   hash-equal? %s"
                         (= (count proj) (count state)) equal? (= hp hr)))
        (println (format "  hash(project)=%d  hash(reduce)=%d" hp hr))
        (when-not equal?
          ;; localize the first divergence
          (let [ks (into (sorted-set) (concat (keys proj) (keys state)))
                diff (first (filter #(not= (get proj %) (get state %)) ks))]
            (println "  FIRST DIVERGENT URI:" (pr-str diff))
            (println "    project:" (pr-str (get proj diff)))
            (println "    reduce :" (pr-str (get state diff))))))
      (finally (close-ctx! ctx)))))

(defn -main [& args]
  (let [phase (first args)]
    (println (format "=== slice0 phase=%s max-heap=%.0fMB ===" phase (max-mb)))
    (case phase
      "gen" (generate! (Long/parseLong (nth args 1))
                       (Long/parseLong (nth args 2))
                       (Long/parseLong (nth args 3 "0")))
      "project-concepts"   (phase-project-concepts)
      "reduce-concepts"    (phase-reduce-concepts)
      "get-all-embeddings" (phase-get-all-embeddings)
      "reduce-embeddings"  (phase-reduce-embeddings)
      "invariance"         (phase-invariance)
      (println "unknown phase:" phase))
    (println "=== done ===")
    (flush)
    (shutdown-agents)))
