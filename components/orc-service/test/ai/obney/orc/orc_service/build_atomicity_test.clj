(ns ai.obney.orc.orc-service.build-atomicity-test
  "Regression tests for concurrency-safety + anomaly-surfacing in
   `build-workflow!`.

   Background (see doc/build-timeline/issues/ORC-engine-build-atomicity.md):
   `build-workflow!` rebuilds a changed sheet by running `clear-sheet-content!`
   (per-key delete-key) then `build-sheet-content!` (declare-key) as separate,
   independently-validated commands. Two concurrent rebuilds of the SAME
   deterministic-id sheet (the hash-mismatch teardown window) therefore
   interleave the delete/declare commands and produce command anomalies:

     - \"Cannot delete key ':<k>': still in use by N node(s)\"
     - \"Key ':<k>' not declared\" / \"Key ':<k>' already declared\"
     - \"Node not found\" / \"Cannot delete node with children\"

   The old build path routed every command through the DEPRECATED
   `h/run-and-apply!`, whose result was never inspected — so those anomalies
   were silently swallowed, leaving a half-built sheet.

   These tests reproduce the race against a REAL event store (in-memory AND
   grain SQLite — the bug is store-agnostic) and assert the fix:
     1. Concurrent rebuilds of one sheet produce ZERO command anomalies.
     2. The final sheet is consistent (matches one of the built definitions).
     3. A forced build anomaly THROWS (no silent half-build)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.dsl :as dsl]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [clojure.java.io :as io]
            [cognitect.anomalies :as anom])
  (:import [java.io File]))

(defn- delete-dir-recursively [^String path]
  (when path
    (let [f (File. path)]
      (when (.exists f)
        (when (.isDirectory f)
          (doseq [child (.listFiles f)]
            (delete-dir-recursively (.getPath child))))
        (.delete f)))))

;; ---------------------------------------------------------------------------
;; Store fixtures (real stores — NO mocks)
;; ---------------------------------------------------------------------------

(defn- in-memory-ctx []
  (let [dir (str "/tmp/build-atom-mem-" (random-uuid))
        event-store (es/start {:conn {:type :in-memory}
                               :event-pubsub nil
                               :logger nil})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))]
    {:event-store event-store
     :cache cache
     :tenant-id (random-uuid)
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     ::cache-dir dir}))

(defn- sqlite-ctx []
  (let [db-file (str "/tmp/build-atom-sqlite-" (random-uuid) ".db")
        cache-dir (str "/tmp/build-atom-sqlite-cache-" (random-uuid))
        event-store (es/start {:conn {:type :sqlite
                                      :database-file db-file
                                      ;; pool-size > 1 so the store is genuinely
                                      ;; concurrent (PA-1 used pool 1 as a mask).
                                      :maximum-pool-size 4}})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "test"}))]
    {:event-store event-store
     :cache cache
     :tenant-id (random-uuid)
     :command-registry (cp/global-command-registry)
     :query-registry (qp/global-query-registry)
     ::cache-dir cache-dir
     ::db-file db-file}))

(defn- stop-ctx! [ctx]
  (when-let [c (:cache ctx)] (kv/stop c))
  (when-let [s (:event-store ctx)] (es/stop s))
  (delete-dir-recursively (::cache-dir ctx))
  (when-let [f (::db-file ctx)]
    (doseq [s ["" "-wal" "-shm"]]
      (io/delete-file (str f s) true))))

;; ---------------------------------------------------------------------------
;; Anomaly tap — captures any command-processor anomaly during the build
;; ---------------------------------------------------------------------------

(defn- with-anomaly-tap
  "Run `f`, capturing every command-processor anomaly produced anywhere on the
   build path. Returns {:result <f's value> :anomalies [<anomaly>...]}.

   Taps `cp/process-command` because the production build path routes every
   command through it. A swallowed anomaly therefore still shows up here even
   if the build doesn't throw — which is exactly the silent half-build the fix
   must eliminate."
  [f]
  (let [captured (atom [])
        orig cp/process-command]
    (with-redefs [cp/process-command
                  (fn [ctx]
                    (let [res (orig ctx)]
                      (when (and (map? res) (contains? res ::anom/category))
                        (swap! captured conj
                               {:command (get-in ctx [:command :command/name])
                                :key (get-in ctx [:command :key])
                                :category (::anom/category res)
                                :message (::anom/message res)}))
                      res))]
      (let [result (f)]
        {:result result :anomalies @captured}))))

;; ---------------------------------------------------------------------------
;; Workflow fixtures — A and B differ in content hash AND in blackboard keys,
;; so a rebuild genuinely tears down keys/nodes (the racey teardown window).
;; ---------------------------------------------------------------------------

(defn- wf [instruction extra-key]
  (dsl/workflow "atomicity-race-sheet"
    (dsl/blackboard (cond-> {:input :string :output :string}
                      extra-key (assoc extra-key :string)))
    (dsl/sequence "main"
      (dsl/llm "process"
        :model "test/model"
        :instruction instruction
        :reads [:input]
        :writes [:output]))))

;; ---------------------------------------------------------------------------
;; Core concurrent-rebuild scenario, parametrised over store
;; ---------------------------------------------------------------------------

(defn- run-concurrent-rebuild!
  "Build def A, then fire N concurrent rebuilds to changed defs simultaneously.
   Returns {:anomalies [...] :exceptions [...] :final-sheet <sheet>
            :final-bb-keys #{...} :sheet-id <uuid>}."
  [ctx n]
  ;; Seed with def A (single-threaded, must be clean).
  (dsl/build-workflow! ctx (wf "instruction A" nil))
  (let [sheet-id (dsl/sheet-id-for-name "atomicity-race-sheet")
        latch (promise)
        exceptions (atom [])
        {:keys [anomalies]}
        (with-anomaly-tap
          (fn []
            (let [futs (mapv
                        (fn [i]
                          (future
                            @latch
                            (try
                              ;; Alternate instruction + blackboard so each
                              ;; thread targets a changed content-hash, forcing
                              ;; the clear+rebuild teardown path concurrently.
                              (dsl/build-workflow!
                               ctx (wf (str "instruction B" (mod i 2))
                                       (when (odd? i) :extra)))
                              (catch Throwable t
                                (swap! exceptions conj (.getMessage t))))))
                        (range n))]
              (deliver latch true)
              (run! deref futs))))]
    ;; Read final state fresh (clear any read-model L1 cache first).
    (rmp/l1-clear!)
    {:anomalies anomalies
     :exceptions @exceptions
     :sheet-id sheet-id
     :final-sheet (rm/get-sheet ctx sheet-id)
     :final-bb-keys (set (map :key (rm/get-blackboard-for-sheet ctx sheet-id)))
     :final-nodes (rm/get-nodes-for-sheet ctx sheet-id)}))

(defn- assert-consistent-final-sheet!
  "The final sheet must match exactly one of the built definitions: it has
   either {:input :output} (def B even threads) or {:input :output :extra}
   (def B odd threads). A half-built sheet (missing :input/:output, dangling
   nodes referencing absent keys, etc.) fails this."
  [{:keys [final-sheet final-bb-keys final-nodes]}]
  (is (some? final-sheet) "sheet must still exist after concurrent rebuilds")
  (is (string? (:content-hash final-sheet))
      "sheet must carry a content hash (build completed, not torn down)")
  (is (contains? final-bb-keys :input)
      "final sheet must declare :input (no half-cleared blackboard)")
  (is (contains? final-bb-keys :output)
      "final sheet must declare :output (no half-cleared blackboard)")
  ;; Every key a node reads/writes must be declared on the blackboard — the
  ;; invariant the delete/declare anomalies violate under the race.
  (doseq [node final-nodes]
    (doseq [k (concat (:reads node) (:writes node))]
      (is (contains? final-bb-keys k)
          (str "node " (:name node) " references undeclared key " k
               " — half-built sheet")))))

(deftest concurrent-rebuild-no-anomalies-in-memory
  (testing "concurrent build-workflow! on one sheet — in-memory store"
    (let [ctx (in-memory-ctx)]
      (try
        (let [{:keys [anomalies exceptions] :as out}
              (run-concurrent-rebuild! ctx 8)]
          (is (empty? anomalies)
              (str "concurrent rebuild produced command anomalies (race): "
                   (pr-str (frequencies (map (juxt :command :message) anomalies)))))
          (is (empty? exceptions)
              (str "concurrent rebuild threw: " (pr-str exceptions)))
          (assert-consistent-final-sheet! out))
        (finally (stop-ctx! ctx))))))

(deftest concurrent-rebuild-no-anomalies-sqlite
  (testing "concurrent build-workflow! on one sheet — grain SQLite store"
    (let [ctx (sqlite-ctx)]
      (try
        (let [{:keys [anomalies exceptions] :as out}
              (run-concurrent-rebuild! ctx 8)]
          (is (empty? anomalies)
              (str "concurrent rebuild produced command anomalies (race): "
                   (pr-str (frequencies (map (juxt :command :message) anomalies)))))
          (is (empty? exceptions)
              (str "concurrent rebuild threw: " (pr-str exceptions)))
          (assert-consistent-final-sheet! out))
        (finally (stop-ctx! ctx))))))

(deftest build-anomaly-throws-no-silent-half-build
  (testing "a build command that returns an anomaly aborts the build loudly"
    (let [ctx (in-memory-ctx)]
      (try
        ;; Force a declare-key anomaly mid-build by making one declare-key
        ;; return a conflict anomaly. The fix must surface this as a thrown
        ;; exception rather than swallowing it and leaving a half-built sheet.
        (let [orig cp/process-command
              threw? (atom false)]
          (with-redefs [cp/process-command
                        (fn [c]
                          (if (= :sheet/declare-key (get-in c [:command :command/name]))
                            {::anom/category ::anom/conflict
                             ::anom/message "Forced declare-key anomaly"}
                            (orig c)))]
            (try
              (dsl/build-workflow! ctx (wf "instruction X" nil))
              (catch Throwable _ (reset! threw? true))))
          (is @threw?
              "build-workflow! must throw when a build command returns an anomaly"))
        (finally (stop-ctx! ctx))))))
