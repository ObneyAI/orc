(ns rs7-traffic-sweep
  "RS-7 Part A — the resumable, two-pass classify-only sweep over the
   realistic-traffic corpus (`traffic-corpus-gen`), plus the 30-campaign
   end-to-end subset. rs6_specialisation_sweep.clj stays UNTOUCHED beyond its
   optional ctx-extra arg — this namespace reuses its `classify-one!` (live
   wedge, durable mint) and `ood/load-corpus`.

   Per-record persistence is ATOMIC (`.tmp` + ATOMIC_MOVE — no reader ever
   sees a partial record) and the pass is RESUMABLE (`:ok` records are
   skipped on re-entry; `:error` records are retried only when asked). A
   180s future timeout guards a single hung task without hanging the whole
   pass. `install-tee!` captures :usage / :rerank-calls / :timed-out? per
   task via the `::task-slug` key carried on the wedge ctx (the ctx-extra
   arg rs6_specialisation_sweep.clj's `classify-one!` now accepts).

   Everything here reads structured event/concept data — never a parse of
   model prose — per the standing 'no regex/phrase matching over
   model-authored prose' discipline; `flag-near-dups!`'s review flags live in
   `traffic-corpus-gen` and are explicitly excluded from `analyse`."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.reranker :as reranker]
            [ai.obney.orc.ontology.core.todo-processors :as ont-tp]
            [ai.obney.orc.ontology.test-support.c2d-ood-stress-test :as ood]
            [rs6-specialisation-sweep :as rs6]
            [rs6-usefulness-bench :as rs6-useful]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.pprint :as pp]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import [java.nio.file Files StandardCopyOption]))

;; =============================================================================
;; Small IO helpers
;; =============================================================================

(defn- atomic-spit!
  "Write `content` to `f` via a `.tmp` sibling + ATOMIC_MOVE, so a reader
   (resume, a concurrent `analyse`) never observes a partially-written
   record."
  [f content]
  (let [f (io/file f)
        tmp (io/file (str (.getPath f) ".tmp"))]
    (io/make-parents f)
    (spit tmp content)
    (Files/move (.toPath tmp) (.toPath f)
                (into-array java.nio.file.CopyOption [StandardCopyOption/ATOMIC_MOVE]))))

(defn- read-edn-file
  "A persisted record's `:classified-event` is a raw Grain event, whose
   `:event/timestamp` prints as a `#time/offset-date-time` tagged literal
   (the `time-literals` lib's print-method, wired in by grain's event-store).
   Plain `clojure.edn/read-string` does NOT auto-discover that reader (by
   design — :readers must be explicit), so every record read needs
   `time-literals.read-write/tags` or resume/compare-runs throws on the
   FIRST previously-persisted record with a classify outcome."
  [f]
  (edn/read-string {:readers @(requiring-resolve 'time-literals.read-write/tags)} (slurp f)))

(defn- pass-dir [results-dir pass]
  (io/file results-dir (str "pass-" pass)))

(defn- record-file [results-dir pass slug]
  (io/file (pass-dir results-dir pass) (str slug ".edn")))

(defn load-pass-records
  "IO: every persisted TASK record for `pass` under `results-dir`, in slug
   order. Skips `.tmp` leftovers (an interrupted write — resume will re-run
   that slug) AND `events-snapshot-*.edn` (the periodic delta snapshots
   live in the SAME pass dir but are not task records — including them
   would corrupt resume's skip-map and `analyse`'s :total)."
  [results-dir pass]
  (let [d (pass-dir results-dir pass)]
    (if (.exists d)
      (->> (.listFiles d)
           (filter #(str/ends-with? (.getName %) ".edn"))
           (remove #(str/ends-with? (.getName %) ".tmp"))
           (remove #(str/starts-with? (.getName %) "events-snapshot-"))
           (map read-edn-file)
           (sort-by :slug)
           vec)
      [])))

;; =============================================================================
;; Corpus + manifest loading
;; =============================================================================

(defn load-manifest [manifest-path]
  (edn/read-string (slurp manifest-path)))

(defn load-traffic-corpus
  "Every task in `corpus-path`/tasks, in slug (= interleave) order — reuses
   `ood/load-corpus`, which already sorts by filename and strips the
   `;`-header. Optionally filtered by `corpus-filter` (a predicate over the
   manifest entry, e.g. `#(:fixture? %)` for a smoke subset)."
  [corpus-path manifest & [corpus-filter]]
  (let [entries-by-slug (into {} (map (juxt :slug identity)) (:entries manifest))
        tasks (ood/load-corpus (str corpus-path "/tasks"))]
    (vec (for [t tasks
               :let [entry (get entries-by-slug (:slug t))]
               :when (and entry (or (nil? corpus-filter) (corpus-filter entry)))]
           (assoc t :ground-truth entry)))))

;; =============================================================================
;; install-tee! — per-task :usage / :rerank-calls / :timed-out? capture
;; =============================================================================

(defn install-tee!
  "Alter-var-root on `orc/execute` and `reranker/rerank!` so every call made
   while a task's `::task-slug` is threaded on the ctx accumulates into
   `captured` (an atom of slug -> {:usage-events [...] :rerank-calls n
   :timed-out? bool}). Returns `{:captured atom :restore! (fn [])}` —
   callers MUST call `:restore!` when done, even on error (a `finally`),
   or a later run in the SAME process double-tees."
  []
  (let [captured (atom {})
        orig-execute @#'orc/execute
        orig-rerank! @#'reranker/rerank!
        touch! (fn [slug f]
                 (when slug
                   (swap! captured update slug
                          (fnil f {:usage-events [] :rerank-calls 0 :timed-out? false}))))]
    (alter-var-root #'orc/execute
      (constantly
        (fn [ctx sheet-id inputs & opts]
          (let [slug (::task-slug ctx)
                result (apply orig-execute ctx sheet-id inputs opts)]
            (when (:usage result)
              (touch! slug #(update % :usage-events conj (:usage result))))
            result))))
    (alter-var-root #'reranker/rerank!
      (constantly
        (fn [ctx opts]
          (let [slug (::task-slug ctx)
                result (orig-rerank! ctx opts)]
            (touch! slug (fn [m]
                           (-> m
                               (update :rerank-calls inc)
                               (update :timed-out? #(or % (reranker/timed-out? result))))))
            result))))
    {:captured captured
     :restore! (fn []
                 (alter-var-root #'orc/execute (constantly orig-execute))
                 (alter-var-root #'reranker/rerank! (constantly orig-rerank!)))}))

(defn- tee-summary [captured slug]
  (let [m (get @captured slug {:usage-events [] :rerank-calls 0 :timed-out? false})
        usage (reduce (fn [acc u]
                        (-> acc
                            (update :prompt-tokens (fnil + 0) (or (:prompt-tokens u) 0))
                            (update :completion-tokens (fnil + 0) (or (:completion-tokens u) 0))
                            (update :total-tokens (fnil + 0) (or (:total-tokens u) 0))))
                      nil (:usage-events m))]
    {:usage usage :rerank-calls (:rerank-calls m) :timed-out? (:timed-out? m)}))

;; =============================================================================
;; reached-via / newborn-in-index? — best-effort REPORTING derivation, never
;; a gate. `:match` is split seed-match vs index-match by the assigned
;; concept's own provenance; everything else follows :assigned-via directly.
;; =============================================================================

(defn- concept-provenance-kind [ctx assigned-tree-id]
  (when assigned-tree-id
    (get-in (ontology/get-concept-by-uri ctx (str "tree-class:" assigned-tree-id))
            [:provenance :kind])))

(defn reached-via
  [ctx {:keys [assigned-via assigned-tree-id]}]
  (cond
    (nil? assigned-via) :deferred
    (contains? #{:mint :mint-domain-child :mint-sibling-domain-child} assigned-via) :mint
    (= :bundle assigned-via) :bundle
    (= :walk-down assigned-via) :walk-down
    (= :land-on-domain-child assigned-via) :graph-land
    (= :match assigned-via)
    (if (= :agent-authored (concept-provenance-kind ctx assigned-tree-id))
      :index-match
      :seed-match)
    :else :deferred))

;; =============================================================================
;; run-pass!
;; =============================================================================

(def default-task-timeout-ms 180000)
(def default-snapshot-every 25)

(defn tree-classes
  "Every `:tree-class` concept as `{:uri :label :broader :provenance}`,
   sorted by :uri — the content of `tree-before.edn` / `tree-after.edn`."
  [ctx]
  (->> (ontology/get-concepts ctx {:scope :tree-class})
       (map #(select-keys % [:uri :label :broader :provenance]))
       (sort-by :uri)
       vec))

(defn- store-boundary
  "The most-recent event id currently in `ctx`'s store — the 'nothing
   happened yet' cursor a delta snapshot reads `:after`."
  [ctx]
  (let [evs (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)}))]
    (:event/id (last evs))))

(defn- events-since
  [ctx boundary]
  (into [] (es/read (:event-store ctx)
                     (cond-> {:tenant-id (:tenant-id ctx)}
                       boundary (assoc :after boundary)))))

(defn load-snapshot
  "Read an `events-snapshot-<n>.edn` file written by `run-pass!` back into a
   vector of events, with the `time-literals` readers so a non-empty
   snapshot (real :event/timestamp values) parses — see `read-edn-file`'s
   docstring. Callers should use THIS rather than a bare `edn/read-string`
   when loading a snapshot for `restore!`."
  [path]
  (read-edn-file path))

(defn restore!
  "Kill-and-restore: bring up a FRESH runner context (`runner/start!` — the
   normal seeded-baseline + built-index startup, which is DETERMINISTIC —
   the baseline seeds carry stable ids, `seed-baseline-corpus!`'s own
   docstring calls re-running it idempotent) and replay `snapshot` (a
   vector of DELTA events — i.e. only what happened after the baseline
   boundary, as captured by `run-pass!`'s periodic snapshot) into it:
   re-append every event (ids/timestamps re-assigned by the store, exactly
   like any other event construction), `drive-projectors!` (now public) to
   force the concept-graph + behavioral-subtree projections over the
   replayed events synchronously (no async pubsub race), then
   `force-rebuild!` the ColBERT index so retrieval reflects the restored
   state. Returns the restored ctx."
  [snapshot]
  (let [runner-start! (requiring-resolve 'runner/start!)
        runner-state (requiring-resolve 'runner/system-state)
        drive-projectors! (requiring-resolve 'runner/drive-projectors!)]
    (runner-start!)
    (let [ctx (deref @runner-state)
          appendable (mapv #(dissoc % :event/id :event/timestamp) snapshot)]
      (when (seq appendable)
        (es/append (:event-store ctx) {:tenant-id (:tenant-id ctx) :events appendable}))
      (drive-projectors! ctx)
      (ont-tp/force-rebuild! ctx)
      ctx)))

(defn- maybe-reindex! [ctx reindex-policy completed-count]
  (when (and (map? reindex-policy)
             (pos-int? (:every-k reindex-policy))
             (pos? completed-count)
             (zero? (mod completed-count (:every-k reindex-policy))))
    (ont-tp/force-rebuild! ctx)))

(defn- active-index-id [ctx]
  ((requiring-resolve 'runner/active-index-id) ctx))

(defn resume-decision
  "PURE: given a slug's PRIOR persisted record (or nil, when none exists)
   and `retry-errors?`, decide whether run-pass! should SKIP re-running
   that slug this pass. `:ok` is always resumed (skipped); `:error` is
   skipped unless `retry-errors?`; no prior record is never skipped."
  [prior retry-errors?]
  (boolean (and prior
                (or (= :ok (:status prior))
                    (and (= :error (:status prior)) (not retry-errors?))))))

(defn- run-one-task!
  "Run `entry` through `rs6/classify-one!` on a future, bounded by
   `timeout-ms`. Never throws — a task exception or timeout becomes an
   `{:status :error}` record so the pass keeps going."
  [ctx pass entry timeout-ms]
  (let [slug (:slug entry)
        fut (future
              (try {:ok (rs6/classify-one! ctx pass entry {::task-slug slug})}
                   (catch Throwable t
                     {:err (str (.getName (class t)) ": " (.getMessage t))})))
        outcome (deref fut timeout-ms ::timeout)]
    (cond
      (= outcome ::timeout)
      (do (future-cancel fut)
          {:slug slug :pass pass :status :error :error :harness-timeout})

      (:err outcome)
      {:slug slug :pass pass :status :error :error (:err outcome)}

      :else
      (assoc (:ok outcome) :status :ok))))

(defn run-pass!
  "Run every task in `corpus-path` (filtered by `manifest`/`corpus-filter`)
   through the live wedge, `pass` labels the record (1 or 2 — pass-to-pass
   identity stability is what `analyse` compares). `opts`:
     :corpus-path    - REQUIRED, the traffic corpus dir (`tasks/` + manifest)
     :manifest-path   - REQUIRED, `manifest.edn` path
     :results-dir     - REQUIRED
     :pass            - REQUIRED, 1 or 2
     :reindex-policy  - :none | {:every-k K} | :processor (default :none).
                        :processor assumes the caller already started it via
                        `runner/start-reindex-processor!` before calling.
     :retry-errors?   - re-run slugs whose persisted record has :status
                        :error (default false — resume just skips them)
     :corpus-filter   - predicate over the manifest entry (fixture subsets)
     :task-timeout-ms - default 180000
     :snapshot-every  - write an events-delta snapshot every N completed
                        tasks, PLUS always one before task 1 (default 25)

   Returns `{:results-dir :records [...]}`. Writes one `<slug>.edn` per task
   (atomic), `events-snapshot-<n>.edn` every `:snapshot-every` tasks (+ one
   at n=0, before any task runs — `restore!` on THAT one reproduces
   `tree-before.edn`), `tree-before.edn` / `tree-after.edn`, and `run.edn`."
  [ctx {:keys [corpus-path manifest-path results-dir pass reindex-policy retry-errors?
               corpus-filter task-timeout-ms snapshot-every]
        :or {reindex-policy :none retry-errors? false
             task-timeout-ms default-task-timeout-ms snapshot-every default-snapshot-every}}]
  (let [manifest (load-manifest manifest-path)
        corpus (load-traffic-corpus corpus-path manifest corpus-filter)
        task-order (mapv :slug corpus)
        existing (into {} (map (juxt :slug identity)) (load-pass-records results-dir pass))
        started-at (str (java.time.Instant/now))
        boundary (store-boundary ctx)
        tee (install-tee!)
        pdir (pass-dir results-dir pass)
        ;; :newborn-in-index? tracking: remember, per assigned concept, the
        ;; index-id active AT MINT TIME (an agent-authored concept is never
        ;; in the index that predates its own mint). A later classify that
        ;; reaches the SAME concept is "newborn-in-index?" true only once the
        ;; active index has moved past that mint-time index.
        minted-index-at (atom {})]
    (.mkdirs pdir)
    (spit (io/file results-dir "tree-before.edn") (with-out-str (pp/pprint (tree-classes ctx))))
    (atomic-spit! (io/file pdir "events-snapshot-0.edn")
                  (with-out-str (pp/pprint (events-since ctx boundary))))
    (try
      (let [records
            (vec
              (for [[i entry] (map-indexed vector corpus)
                    :let [slug (:slug entry)
                          prior (get existing slug)
                          skip? (resume-decision prior retry-errors?)]]
                (if skip?
                  prior
                  (let [index-before (active-index-id ctx)
                        record (-> (run-one-task! ctx pass entry task-timeout-ms)
                                   (merge (tee-summary (:captured tee) slug))
                                   (assoc :corpus-group (get-in entry [:ground-truth :group])
                                          :corpus-in-domain? (get-in entry [:ground-truth :in-domain?])
                                          :expected-seed-id (get-in entry [:ground-truth :expected-seed-id])
                                          :confounder-of (get-in entry [:ground-truth :confounder-of])
                                          :index-id-at-classify index-before))
                        via (when (= :ok (:status record)) (reached-via ctx record))
                        aid (:assigned-tree-id record)
                        newborn-in-index? (when (and via (contains? #{:mint :index-match :graph-land :walk-down} via))
                                            (if (contains? #{:mint :mint-domain-child :mint-sibling-domain-child}
                                                            (:assigned-via record))
                                              (do (swap! minted-index-at assoc aid index-before) false)
                                              (when (contains? @minted-index-at aid)
                                                (not= index-before (get @minted-index-at aid)))))
                        record (cond-> record
                                 via (assoc :reached-via via)
                                 (some? newborn-in-index?) (assoc :newborn-in-index? newborn-in-index?))]
                    (atomic-spit! (record-file results-dir pass slug) (with-out-str (pp/pprint record)))
                    (let [completed (inc i)]
                      (maybe-reindex! ctx reindex-policy completed)
                      (when (or (zero? (mod completed snapshot-every)) (= completed (count corpus)))
                        (atomic-spit! (io/file pdir (str "events-snapshot-" completed ".edn"))
                                      (with-out-str (pp/pprint (events-since ctx boundary))))))
                    record))))]
        (spit (io/file results-dir "tree-after.edn") (with-out-str (pp/pprint (tree-classes ctx))))
        (spit (io/file results-dir "run.edn")
              (with-out-str
                (pp/pprint
                  {:commit (try (str/trim (:out (shell/sh "git" "rev-parse" "HEAD"))) (catch Throwable _ nil))
                   :dirty? (try (not (str/blank? (:out (shell/sh "git" "status" "--porcelain")))) (catch Throwable _ nil))
                   :corpus-sha256 (:corpus-sha256 manifest)
                   :task-order task-order
                   :reindex-policy reindex-policy
                   :parallelism 1
                   :models {:reranker-model (reranker/resolve-model ctx nil)}
                   :thresholds {:rerank-timeout-ms reranker/default-rerank-timeout-ms}
                   :jvm-args (vec (.getInputArguments (java.lang.management.ManagementFactory/getRuntimeMXBean)))
                   :started-at started-at
                   :finished-at (str (java.time.Instant/now))})))
        {:results-dir results-dir :records records})
      (finally
        ((:restore! tee))))))

;; =============================================================================
;; analyse — pure over records + manifest (no IO, no LLM). run-pass! writes
;; the records to disk; this fn just needs THEM, not the dir.
;; =============================================================================

(defn- median [coll]
  (when (seq coll)
    (let [s (vec (sort coll)) n (count s) mid (quot n 2)]
      (if (odd? n) (nth s mid) (/ (+ (nth s (dec mid)) (nth s mid)) 2)))))

(defn- percentile [coll p]
  (when (seq coll)
    (let [s (vec (sort coll)) n (count s)
          idx (max 0 (min (dec n) (dec (int (Math/ceil (* (/ p 100.0) n))))))]
      (nth s idx))))

(defn- landing? [record]
  (contains? #{:match :walk-down :land-on-domain-child} (:assigned-via record)))

(defn- mint? [record]
  (contains? #{:mint :mint-domain-child :mint-sibling-domain-child} (:assigned-via record)))

(defn- per-group-analysis [group-slug records]
  (let [ids (remove nil? (map :assigned-tree-id records))
        distinct-ids (distinct ids)
        parents (distinct (remove nil? (map :parent-tree-id records)))
        labels (distinct (remove nil? (map :domain-label records)))
        decided (filterv #(some? (:assigned-via %)) records)
        second-plus (rest (sort-by :variant records))
        landing-second-plus (filter landing? second-plus)]
    {:group group-slug
     :child-count (count distinct-ids)
     :members (mapv :slug records)
     :parents parents
     :cross-parent-scatter (count parents)
     :label-variants labels
     :label-variant-count (count labels)
     :mint-count (count (filter mint? records))
     :landing-count (count (filter landing? records))
     :landing-rate-2nd-plus (if (seq second-plus)
                              (double (/ (count landing-second-plus) (count second-plus)))
                              0.0)
     :deferral-count (count (remove #(some? (:assigned-via %)) records))
     :converged-strict? (= 1 (count distinct-ids))
     :converged-relaxed? (or (empty? decided)
                             (>= (double (/ (apply max (vals (frequencies (keep :assigned-tree-id decided))))
                                            (count decided)))
                                 0.8))}))

(defn analyse
  "PURE over `records` (a seq of run-pass! record maps, ANY pass — pass-2
   records additionally get compared to pass-1 when both are present via
   `:pass-1-records`) and `manifest` (ground truth = each entry's `:group` /
   `:in-domain?` / `:expected-seed-id` / `:confounder-of` — NEVER a record's
   own `:domain-label`). `opts`:
     :pass-1-records - optional; when given alongside `records` (treated as
                       pass-2), adds `:identity-stability` and
                       `:majority-child-landing-rate`."
  [records manifest & [{:keys [pass-1-records]}]]
  (let [by-group (group-by :corpus-group records)
        in-domain-mints (->> records (filter :corpus-in-domain?) (filter mint?))
        confounder-mints (->> records (filter :confounder-of) (filter mint?))
        deferred (remove #(some? (:assigned-via %)) records)
        behavioral-entries (mapcat #(get-in % [:classified-event :behavioral-subtrees]) records)
        reached-via-freq (frequencies (keep :reached-via records))
        newborn-candidates (filter #(contains? #{:index-match :graph-land :walk-down} (:reached-via %)) records)
        usage-totals (keep (comp :total-tokens :usage) records)
        latencies (keep :elapsed-ms records)
        manifest-slugs (set (keep #(when (not= :rejected (:review-status %)) (:slug %))
                                  (:entries manifest)))
        recorded-slugs (set (map :slug records))]
    (cond-> {:total (count records)
             :missing-slugs (vec (sort (set/difference manifest-slugs recorded-slugs)))
             :groups (into (sorted-map)
                           (for [[g rs] by-group :when g] [g (per-group-analysis g rs)]))
             :in-domain-mint-count (count in-domain-mints)
             :in-domain-mint-slugs (mapv :slug in-domain-mints)
             :confounder-mint-count (count confounder-mints)
             :confounder-mint-slugs (mapv :slug confounder-mints)
             :deferral-count (count deferred)
             :deferrals-by-reason (frequencies (keep #(get-in % [:domain-deferral :reason]) deferred))
             :deferrals-by-axis (frequencies (keep (fn [r] (when (nil? (:assigned-via r)) :structural)) deferred))
             :behavioral-fresh-mint-rate (if (seq behavioral-entries)
                                          (double (/ (count (filter :was-fresh-mint? behavioral-entries))
                                                     (count behavioral-entries)))
                                          0.0)
             :reached-via-frequencies reached-via-freq
             :newborn-in-index-rate (if (seq newborn-candidates)
                                      (double (/ (count (filter :newborn-in-index? newborn-candidates))
                                                 (count newborn-candidates)))
                                      0.0)
             :tokens {:total (reduce + 0 usage-totals)
                      :mean (if (seq usage-totals) (double (/ (reduce + usage-totals) (count usage-totals))) 0.0)}
             :latency {:mean-ms (if (seq latencies) (double (/ (reduce + latencies) (count latencies))) 0.0)
                       :median-ms (or (median latencies) 0)
                       :p95-ms (or (percentile latencies 95) 0)}}
      pass-1-records
      (assoc :identity-stability
             (let [by-slug-1 (into {} (map (juxt :slug identity)) pass-1-records)
                   pairs (for [r2 records :let [r1 (get by-slug-1 (:slug r2))] :when r1]
                           (= (:assigned-tree-id r1) (:assigned-tree-id r2)))]
               (if (seq pairs) (double (/ (count (filter true? pairs)) (count pairs))) 0.0))
             :majority-child-landing-rate
             (let [group-majority (into {}
                                        (for [[g rs] (group-by :corpus-group pass-1-records) :when g
                                              :let [ids (frequencies (keep :assigned-tree-id rs))]
                                              :when (seq ids)]
                                          [g (key (apply max-key val ids))]))
                   evald (for [r2 records :let [maj (get group-majority (:corpus-group r2))] :when maj]
                           (= maj (:assigned-tree-id r2)))]
               (if (seq evald) (double (/ (count (filter true? evald)) (count evald))) 0.0))))))

;; =============================================================================
;; compare-runs — refuses mismatched corpora, reports changed slugs
;; =============================================================================

(defn- read-run-edn [dir] (read-edn-file (io/file dir "run.edn")))

(defn compare-runs
  "Refuses (returns `{:refused? true :reason ...}`, writes nothing) unless
   `dir-a` and `dir-b`'s `run.edn` share `:corpus-sha256` AND `:task-order`
   — comparing a baseline arm against a post-fix arm only means anything on
   the SAME corpus in the SAME order. Otherwise aligns pass-1 records by
   slug and reports every slug whose `:assigned-via` or `:domain-label`
   changed; writes `COMPARISON.md` into `dir-b`."
  [dir-a dir-b]
  (let [run-a (read-run-edn dir-a)
        run-b (read-run-edn dir-b)]
    (cond
      (not= (:corpus-sha256 run-a) (:corpus-sha256 run-b))
      {:refused? true :reason :corpus-sha-mismatch :dir-a dir-a :dir-b dir-b}

      (not= (:task-order run-a) (:task-order run-b))
      {:refused? true :reason :task-order-mismatch :dir-a dir-a :dir-b dir-b}

      :else
      (let [pass-a (load-pass-records dir-a 1)
            pass-b (load-pass-records dir-b 1)
            by-a (into {} (map (juxt :slug identity)) pass-a)
            by-b (into {} (map (juxt :slug identity)) pass-b)
            slugs (sort (distinct (concat (keys by-a) (keys by-b))))
            rows (vec (for [s slugs
                            :let [a (get by-a s) b (get by-b s)]]
                        {:slug s
                         :a-via (:assigned-via a) :b-via (:assigned-via b)
                         :a-label (:domain-label a) :b-label (:domain-label b)
                         :changed? (or (not= (:assigned-via a) (:assigned-via b))
                                      (not= (:domain-label a) (:domain-label b)))}))
            changed (filterv :changed? rows)
            report {:refused? false :dir-a dir-a :dir-b dir-b :rows rows
                     :changed-slugs (mapv :slug changed)}]
        (spit (io/file dir-b "COMPARISON.md")
              (str "# RS-7 compare-runs — " dir-a " vs " dir-b "\n\n"
                   "| slug | a via | b via | a label | b label | changed? |\n"
                   "|---|---|---|---|---|---|\n"
                   (apply str
                          (for [r rows]
                            (format "| %s | %s | %s | %s | %s | %s |\n"
                                    (:slug r) (:a-via r) (:b-via r) (:a-label r) (:b-label r) (:changed? r))))))
        report))))

;; =============================================================================
;; TRAFFIC.md — markdown renderer over an `analyse` result
;; =============================================================================

(defn traffic-md
  [{:keys [total groups in-domain-mint-count confounder-mint-count deferral-count
           behavioral-fresh-mint-rate reached-via-frequencies newborn-in-index-rate
           tokens latency identity-stability majority-child-landing-rate]}]
  (str "# RS-7 traffic sweep — TRAFFIC.md\n\n"
       "## Aggregate\n\n"
       "| metric | value |\n|---|---|\n"
       (format "| total tasks | %s |\n" total)
       (format "| in-domain mints (want 0) | %s |\n" in-domain-mint-count)
       (format "| confounder mints under a seed | %s |\n" confounder-mint-count)
       (format "| deferrals | %s |\n" deferral-count)
       (format "| behavioral fresh-mint rate | %.2f |\n" (double (or behavioral-fresh-mint-rate 0.0)))
       (format "| reached-via | %s |\n" reached-via-frequencies)
       (format "| newborn-in-index rate | %.2f |\n" (double (or newborn-in-index-rate 0.0)))
       (format "| mean tokens | %.0f |\n" (double (get tokens :mean 0.0)))
       (format "| mean latency ms | %.0f |\n" (double (get latency :mean-ms 0.0)))
       (when identity-stability (format "| pass1->pass2 identity stability | %.2f |\n" (double identity-stability)))
       (when majority-child-landing-rate
         (format "| pass2 majority-child landing rate | %.2f |\n" (double majority-child-landing-rate)))
       "\n## Per group\n\n"
       "| group | child count | mints | landings | label variants | converged (strict) | converged (relaxed) |\n"
       "|---|---:|---:|---:|---|---|---|\n"
       (apply str
              (for [[g a] groups]
                (format "| %s | %s | %s | %s | %s | %s | %s |\n"
                        g (:child-count a) (:mint-count a) (:landing-count a)
                        (:label-variant-count a) (:converged-strict? a) (:converged-relaxed? a))))))

;; =============================================================================
;; run-e2e! — 15-group end-to-end subset (v01 + last accepted variant)
;; =============================================================================

(defn- tree-node-count [tree]
  (if (sequential? tree)
    (reduce + 1 (map tree-node-count (rest tree)))
    0))

(defn- e2e-slugs-for-group [corpus group]
  (let [members (->> corpus (filter #(= group (get-in % [:ground-truth :group]))) (sort-by :slug))
        v01 (first (filter #(= 1 (get-in % [:ground-truth :variant])) members))
        last-accepted (->> members
                          (filter #(not= :rejected (get-in % [:ground-truth :review-status])))
                          last)]
    (distinct (remove nil? [v01 last-accepted]))))

(defn run-e2e!
  "The e2e subset: for each of `groups` (manifest group names), v01 and the
   last accepted variant, ALL on the one `ctx` store (every group's v01 first,
   then every group's later variant), through
   `runner/run!` with auto-classify on — reuses
   `run-full-bench-observation!`'s sheet-event read + `child-state`. Records
   status/usage/classified/minted/occurrence/claim events, the render
   candidates + prepend, child-after-run, and the generated tree's node
   count. `opts`: :manifest-path :corpus-path :results-dir."
  [ctx {:keys [manifest-path corpus-path groups results-dir]}]
  (let [manifest (load-manifest manifest-path)
        corpus (load-traffic-corpus corpus-path manifest)
        run! (requiring-resolve 'runner/run!)
        _ (.mkdirs (io/file results-dir))
        results
        (vec
          ;; Order per PLAN.md Part A: every group's FIRST variant runs before
          ;; any group's later variant, on ONE store, so a later variant lands
          ;; on the family its group's first campaign minted and sees what that
          ;; campaign accrued (the "first mints, later lands" proof).
          (for [entry (let [per-group (mapv #(e2e-slugs-for-group corpus %) groups)]
                        (concat (keep first per-group) (mapcat rest per-group)))
                :let [slug (:slug entry)]]
            (do
              (println "\n=== e2e:" slug)
              (let [record (run! {:name slug :slug slug :pattern "traffic-e2e" :documents []
                                  :instruction (:instruction entry) :writes [:result]
                                  :rlm {:auto-classify? true}})
                    sheet-id (some-> record :r-inject-trace :injection-records first :sheet-id)
                    events (when sheet-id
                             (->> (es/read (:event-store ctx)
                                          {:tenant-id (:tenant-id ctx)
                                           :types #{:ontology/task-classified
                                                    :ontology/task-classification-deferred
                                                    :ontology/domain-child-minted
                                                    :ontology/tree-class-occurrence-recorded
                                                    :ontology/claim-deltas-recorded}})
                                  (into [])
                                  (filterv #(or (= sheet-id (:source-sheet-id %))
                                               (contains? (:event/tags %) [:sheet sheet-id])))))
                    by-type (group-by :event/type events)
                    classified (first (:ontology/task-classified by-type))
                    child-id (:assigned-tree-id classified)
                    obs {:group (get-in entry [:ground-truth :group]) :slug slug :variant (get-in entry [:ground-truth :variant])
                         :status (:status record) :usage (:usage record)
                         :classified (select-keys classified
                                                   [:outcome :assigned-via :assigned-tree-id :parent-tree-id
                                                    :domain-label :domain-verdict :was-fresh-mint?])
                         :deferred (mapv #(select-keys % [:fallback-source :reasoning])
                                         (:ontology/task-classification-deferred by-type))
                         :minted (mapv #(select-keys % [:parent-tree-id :child-tree-id :domain-label])
                                       (:ontology/domain-child-minted by-type))
                         :occurrences (mapv #(select-keys % [:assigned-tree-id :verdict])
                                            (:ontology/tree-class-occurrence-recorded by-type))
                         :claims (mapv #(select-keys % [:granularity :target-identifier :evidence-event-count])
                                       (:ontology/claim-deltas-recorded by-type))
                         :render {:arm (-> record :r-inject-trace :arm)
                                  :candidates (-> record :r-inject-trace :candidates)
                                  :prepend-chars (-> record :r-inject-trace :prepend-chars)
                                  :prepend (-> record :r-inject-trace :prepend)}
                         :generated-tree-node-count (tree-node-count (:generated-tree-raw record))
                         :child-after-run (rs6-useful/child-state ctx child-id)}]
                (spit (io/file results-dir (str "e2e-" slug ".edn")) (with-out-str (pp/pprint obs)))
                obs))))]
    (spit (io/file results-dir "e2e-results.edn") (with-out-str (pp/pprint results)))
    {:results-dir results-dir :results results}))

(comment
  ;; Launcher (from the worktree root, OPENROUTER_API_KEY in the environment):
  ;; clojure -J-Djava.awt.headless=true -J-Xmx1600m -M:dev:test -e "(require 'runner) (runner/start!) (Thread/sleep 45000) (require 'rs7-traffic-sweep) (let [ctx (deref @(requiring-resolve 'runner/system-state))] (rs7-traffic-sweep/run-pass! ctx {:corpus-path traffic-corpus-gen/corpus-dir :manifest-path (str traffic-corpus-gen/corpus-dir \"/manifest.edn\") :results-dir \"development/bench/ood-stress-results/rs7-pass-1\" :pass 1 :reindex-policy {:every-k 25}})) (runner/stop!) (shutdown-agents) (System/exit 0)"
  )
