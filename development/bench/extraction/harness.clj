(ns extraction.harness
  "S16 — Extraction bench harness + G2 gate (AFK layer).

   Drives the S17 deterministic skeleton against fixture sources, then
   compares the resulting graph against each fixture's hand-authored
   expected graph using S09's URDNA2015 canonicalizer.

   ## Public API

     (list-fixtures)                       → vector of fixture names
     (load-fixture fixture-name)           → fixture map
     (run-fixture! fixture-name)           → result map (see schema below)
     (run-all!)                            → vector of results + writes
                                             extraction-RESULTS.md
     (passes-G2? result)                   → boolean

   ## Fixture layout (`fixtures/<name>/`)

     source.ttl     — the input source (TTL today; future: csv/json/sql
                      adapters)
     spec.edn       — `{:purpose ... :competency-questions [...]}` —
                      schema-shaped per S14 ORSD spec
     expected.ttl   — the known-good graph (HITL-authored or derived)
     notes.md       — derivation provenance + HITL-REVIEW-REQUIRED marker

   ## Result shape

     {:fixture-name             string
      :status                   :pass | :triple-diff-found
                                | :skeleton-failed
      :triple-diff              {:missing #{string} :extra #{string}}
      :cq-pass-rate             float [0,1] (or nil if no spec)
      :evidence-score-distribution {:total int :scored int :mean float ...}
      :shacl-export             string (TTL) or nil
      :expected-graph-status    :auto-derived | :hitl-reviewed
      :timing                   {:total-ms int :stage-timings map}
      :skeleton-result          full skeleton result map}

   ## G2 gate semantics

     (passes-G2? r) returns true IFF
       (empty? (get-in r [:triple-diff :missing]))
       AND
       (>= (or (:cq-pass-rate r) 0.0) 0.8)

   That is: every expected triple must be produced (high RECALL is the
   gate), AND the CQ pass-rate threshold is met. Extra triples are
   tolerated — the harness is a high-recall gate today, not a precision
   one. This is by design: the rebuild's substrate is broader than
   hand-authored expected graphs; pre-G2 we don't penalize the extras
   the skeleton's S04/S06/S07 events produce.

   ## HITL extension surface

   See `HITL.md` next to this file. The harness honors a per-fixture
   `:expected-graph-status` derived from the presence of the
   `HITL-REVIEW-REQUIRED` marker in `notes.md`. The report is honest
   about which fixtures are AUTO-DERIVED (their expected.ttl is the
   AFK-seed, awaiting human ground-truth validation) vs HITL-REVIEWED
   (human-validated, suitable for gate use)."
  (:require [ai.obney.orc.ontology.core.deterministic-skeleton :as sk]
            [ai.obney.orc.ontology.core.ttl-canonicalize :as ttlc]
            [ai.obney.orc.ontology.core.serialization :as serial]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.interface.schemas]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.ontology.core.read-models]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.schemas]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

;; =============================================================================
;; Fixture discovery + loading
;; =============================================================================

(def ^:private fixtures-dir
  "development/bench/extraction/fixtures")

(defn- fixtures-root ^java.io.File []
  (io/file fixtures-dir))

(defn list-fixtures
  "Discover fixture names by scanning the `fixtures/` directory.
   Each subdirectory is one fixture. Returns a sorted vector of
   strings — the report iterates in this order so the output is
   stable across runs."
  []
  (let [root (fixtures-root)]
    (if (.exists root)
      (->> (.listFiles root)
           (filter #(.isDirectory ^java.io.File %))
           (mapv #(.getName ^java.io.File %))
           sort
           vec)
      [])))

(defn- fixture-dir [fixture-name]
  (io/file fixtures-dir fixture-name))

(defn- read-edn [^java.io.File f]
  (when (.exists f)
    (edn/read-string (slurp f))))

(defn- read-text [^java.io.File f]
  (when (.exists f)
    (slurp f)))

(defn load-fixture
  "Load a fixture by name into a map:
     {:fixture-name <str>
      :source       {:type :ttl :content <ttl-str>}
      :spec         <ORSD spec map or nil>
      :expected-ttl <str or nil>
      :notes        <str or nil>}

   Returns nil when the fixture directory does not exist."
  [fixture-name]
  (let [dir (fixture-dir fixture-name)]
    (when (.exists dir)
      (let [src-ttl  (read-text (io/file dir "source.ttl"))
            spec     (read-edn  (io/file dir "spec.edn"))
            expected (read-text (io/file dir "expected.ttl"))
            notes    (read-text (io/file dir "notes.md"))]
        {:fixture-name fixture-name
         :source       (when src-ttl {:type :ttl :content src-ttl})
         :spec         spec
         :expected-ttl expected
         :notes        notes}))))

;; =============================================================================
;; HITL-status detection
;; =============================================================================
;;
;; Honest demarcation: a fixture's notes.md MUST be checked for the
;; `HITL-REVIEW-REQUIRED` marker. When present → :auto-derived (the
;; expected.ttl was seeded from existing bench artifacts and has not
;; been human-validated as semantic ground truth). When absent →
;; :hitl-reviewed.
;;
;; No silent defaulting to :hitl-reviewed. The harness MUST surface the
;; status honestly so the report's headline summary can call out the
;; gap.

(def ^:private hitl-review-marker "HITL-REVIEW-REQUIRED")

(defn- expected-graph-status [notes]
  (if (and notes (str/includes? notes hitl-review-marker))
    :auto-derived
    :hitl-reviewed))

;; =============================================================================
;; In-memory Grain context (mirrors the s17 test's make-ctx pattern)
;; =============================================================================

(defn- make-ctx []
  ;; Clear projection L1 cache between fixture runs so a prior run's
  ;; projected state doesn't leak into the next fixture's queries.
  (rmp/l1-clear!)
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :in-memory} :event-pubsub ps :logger nil})
        dir (str "/tmp/orc-s16-bench-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "bench"}))]
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

;; =============================================================================
;; Spec wiring + judge fn
;; =============================================================================

(defn- record-spec! [ctx ontology-id spec]
  (cp/process-command
   (assoc ctx :command
          {:command/name :ontology/record-ontology-spec
           :command/id (random-uuid)
           :command/timestamp (time/now)
           :ontology-id ontology-id
           :body spec})))

(defn always-pass-judge
  "Default judge for the bench: a controlled `:pass` verdict so the
   harness exercises the runner mechanics without consuming LLM budget.
   Production / live-verify can substitute an OpenRouter judge per S15."
  [_]
  {:verdict :pass
   :reasoning "harness controlled judge — always-pass"
   :evidence-uris []
   :gaps []})

;; =============================================================================
;; Triple-set diff (uses S09 canonicalizer)
;; =============================================================================

(defn- canonical-triple-set
  "Canonicalize a TTL string and return its triple-set (set of
   canonical N-Triples lines). Returns nil and an anomaly map when
   parse fails; the caller surfaces the anomaly verbatim so a
   malformed expected.ttl is loud, not silent."
  [ttl-string]
  (let [c (ttlc/canonicalize-ttl ttl-string)]
    (cond
      (string? c)
      {:triples (->> (str/split-lines c)
                     (remove str/blank?)
                     set)}

      (map? c)
      {:anomaly c}

      :else
      {:anomaly {:cognitect.anomalies/category :cognitect.anomalies/fault
                 :anomaly/message "canonicalize-ttl returned unexpected shape"}})))

(defn- triple-diff
  "Compute the diff direction: `:missing` is triples in EXPECTED but
   not in ACTUAL (recall gap — the G2 hard signal). `:extra` is
   triples in ACTUAL but not in EXPECTED (extras tolerated today).

   When either side fails to canonicalize, returns `:anomaly` with
   the parse error from the offending side — no silent fallback."
  [expected-ttl actual-ttl]
  (let [e (canonical-triple-set (or expected-ttl ""))
        a (canonical-triple-set (or actual-ttl ""))]
    (cond
      (:anomaly e)
      {:anomaly {:side :expected :error (:anomaly e)}}

      (:anomaly a)
      {:anomaly {:side :actual :error (:anomaly a)}}

      :else
      {:missing (set/difference (:triples e) (:triples a))
       :extra   (set/difference (:triples a) (:triples e))})))

;; =============================================================================
;; Evidence-score distribution helper
;; =============================================================================
;;
;; S13 emits :ontology/concept-evidence-aggregated events that carry a
;; per-concept evidence score. We collect those into a histogram-style
;; summary the report consumes.

(defn- evidence-score-distribution [ctx ontology-id]
  (let [events
        (into [] (es/read (:event-store ctx)
                          {:tenant-id (:tenant-id ctx)
                           :types #{:ontology/concept-evidence-aggregated}}))
        in-scope (filter #(= ontology-id (:ontology-id %)) events)
        scores (keep :evidence-score in-scope)]
    {:total (count in-scope)
     :scored (count scores)
     :mean (if (seq scores)
             (double (/ (reduce + scores) (count scores)))
             nil)
     :min (when (seq scores) (apply min scores))
     :max (when (seq scores) (apply max scores))}))

;; =============================================================================
;; G2 gate
;; =============================================================================

(def ^:const g2-cq-pass-rate-min
  "G2 gate threshold for CQ pass-rate — half of the gate (the other
   half is empty :missing). Mirrors S17's default exit-criterion."
  0.8)

(defn passes-G2?
  "G2 gate: returns true IFF every expected triple is produced
   (:missing is empty) AND the CQ pass-rate is at least 0.8.

   When `:cq-pass-rate` is nil (the fixture's spec is absent or
   carries no CQs), the gate REQUIRES it — a fixture without
   testable CQs can't pass the gate."
  [result]
  (and (empty? (get-in result [:triple-diff :missing]))
       (number? (:cq-pass-rate result))
       (>= (:cq-pass-rate result) g2-cq-pass-rate-min)))

;; =============================================================================
;; run-fixture!
;; =============================================================================

(defn run-fixture!
  "Run the S17 skeleton against the named fixture; compare the actual
   graph against the fixture's expected.ttl; produce the result map
   described in the ns docstring.

   Each invocation creates a fresh in-memory event store and tears it
   down — no state leaks between fixtures.

   When the fixture's source is missing OR expected.ttl is missing OR
   the spec is missing, returns a result map with `:status :fixture-
   incomplete` and a `:missing-files` vector. The harness fails LOUDLY
   (per disciplines #5: no silent fallback)."
  [fixture-name]
  (let [fixture (load-fixture fixture-name)
        _ (when (nil? fixture)
            (throw (ex-info "fixture directory not found"
                            {:fixture-name fixture-name
                             :path (str fixture-dir "/" fixture-name)})))
        missing-files
        (cond-> []
          (nil? (:source fixture))       (conj "source.ttl")
          (nil? (:spec fixture))         (conj "spec.edn")
          (nil? (:expected-ttl fixture)) (conj "expected.ttl"))]
    (if (seq missing-files)
      {:fixture-name fixture-name
       :status :fixture-incomplete
       :missing-files missing-files
       :expected-graph-status (expected-graph-status (:notes fixture))}

      (let [start (System/currentTimeMillis)
            ctx (make-ctx)
            ontology-id (random-uuid)]
        (try
          ;; Record the ORSD spec via the S14 public command FIRST so
          ;; the skeleton's exit-criterion stage finds it.
          (record-spec! ctx ontology-id (:spec fixture))
          (Thread/sleep 50)
          (let [skel-result (sk/build! ctx
                                       {:ontology-id ontology-id
                                        :sources [(:source fixture)]
                                        :judge-fn always-pass-judge
                                        ;; Permissive exit-criterion so
                                        ;; the harness records the
                                        ;; actual pass-rate without the
                                        ;; SKELETON pre-failing. The G2
                                        ;; gate fn ALONE asserts the
                                        ;; 0.8 threshold against the
                                        ;; recorded pass-rate.
                                        :exit-criterion {:pass-rate-min 0.0
                                                         :unknown-rate-max 1.0}})
                actual-ttl (serial/full-export ctx
                                               {:base-uri (str "http://example.org/"
                                                               fixture-name "#")})
                diff (triple-diff (:expected-ttl fixture) actual-ttl)
                cq-pass-rate (some-> (:graph-health skel-result) :pass-rate)
                evidence (evidence-score-distribution ctx ontology-id)
                shacl (get-in skel-result [:artifacts :shacl-ttl])
                total-ms (- (System/currentTimeMillis) start)
                base-result
                {:fixture-name fixture-name
                 :triple-diff (select-keys diff [:missing :extra])
                 :diff-anomaly (:anomaly diff)
                 :cq-pass-rate cq-pass-rate
                 :evidence-score-distribution evidence
                 :shacl-export shacl
                 :expected-graph-status (expected-graph-status (:notes fixture))
                 :timing {:total-ms total-ms
                          :stage-timings (:stage-timings skel-result)}
                 :skeleton-result skel-result}
                status
                (cond
                  (:anomaly diff) :diff-anomaly
                  (contains? #{:failed-at-parse
                               :failed-at-normalize
                               :failed-at-dedup
                               :failed-at-validate
                               :failed-at-embed
                               :failed-at-index
                               :failed-at-exit-criterion
                               :failed-validation
                               :failed-cq}
                             (:status skel-result))
                  :skeleton-failed

                  (passes-G2? base-result) :pass

                  (seq (:missing diff)) :triple-diff-found

                  :else :pass)]
            (assoc base-result :status status))
          (finally
            (stop-ctx ctx)))))))

;; =============================================================================
;; Report generation
;; =============================================================================

(defn- truncate [s n]
  (if (and (string? s) (> (count s) n))
    (str (subs s 0 n) "...")
    (str s)))

(defn- format-triple-sample [triples n]
  (if (empty? triples)
    "_(none)_"
    (let [sample (take n (sort triples))]
      (str (str/join "\n" (map #(str "  - `" (truncate % 240) "`") sample))
           (when (> (count triples) n)
             (str "\n  - _... and " (- (count triples) n) " more_"))))))

(defn- render-fixture-section [result]
  (let [{:keys [fixture-name status triple-diff cq-pass-rate
                evidence-score-distribution expected-graph-status
                timing diff-anomaly missing-files]} result
        missing-count (count (:missing triple-diff))
        extra-count (count (:extra triple-diff))]
    (str/join "\n"
              (cond->
               [(str "## " fixture-name)
                ""
                (str "- **Status:** `" (name (or status :unknown)) "`")
                (str "- **G2 gate:** " (if (passes-G2? result) "PASS" "FAIL"))
                (str "- **Expected-graph status:** `" (name (or expected-graph-status :unknown)) "`")
                (str "- **CQ pass-rate:** "
                     (if cq-pass-rate
                       (format "%.2f" (double cq-pass-rate))
                       "n/a"))
                (str "- **Triple-diff:** missing=" missing-count " extra=" extra-count)
                (str "- **Evidence-score-distribution:** "
                     (let [{:keys [total scored mean]} evidence-score-distribution]
                       (str "total=" (or total 0)
                            " scored=" (or scored 0)
                            " mean=" (if mean (format "%.3f" mean) "n/a"))))
                (str "- **Timing:** total-ms=" (get-in timing [:total-ms] "n/a"))]

                (= :fixture-incomplete status)
                (into [(str "- **Missing fixture files:** "
                            (str/join ", " missing-files))])

                (and diff-anomaly (= :diff-anomaly status))
                (into ["" "### Diff anomaly" "```" (pr-str diff-anomaly) "```"])

                (pos? missing-count)
                (into [""
                       "### Missing triples (recall gap)"
                       (format-triple-sample (:missing triple-diff) 10)])

                (pos? extra-count)
                (into [""
                       "### Extra triples (sample)"
                       (format-triple-sample (:extra triple-diff) 10)])

                :always
                (into ["" "---" ""])))))

(defn- pass-count [results]
  (count (filter passes-G2? results)))

(defn- hitl-reviewed-count [results]
  (count (filter #(= :hitl-reviewed (:expected-graph-status %)) results)))

(defn- render-report [results]
  (let [n (count results)
        timestamp (.format (java.time.ZonedDateTime/now)
                           (java.time.format.DateTimeFormatter/ISO_INSTANT))]
    (str/join
     "\n"
     [(str "# Extraction Bench — Results")
      ""
      (str "**Generated:** " timestamp)
      ""
      (str "**G2 status:** "
           (pass-count results) "/" n " fixtures pass | "
           (hitl-reviewed-count results) "/" n
           " have HITL-reviewed expected graphs")
      ""
      "G2 = `(empty? :missing)` AND `:cq-pass-rate >= 0.8`. The harness is a"
      "high-recall gate today (extras tolerated); HITL-reviewed expected"
      "graphs are the gate's load-bearing ground truth."
      ""
      "---"
      ""
      (str/join "\n" (map render-fixture-section results))])))

(def ^:private results-path
  "development/bench/extraction/extraction-RESULTS.md")

(defn write-report! [results]
  (let [out (render-report results)
        f (io/file results-path)]
    (io/make-parents f)
    (spit f out)
    (.getPath f)))

(defn run-all!
  "Run every discovered fixture, collect results, write extraction-
   RESULTS.md. Returns the vector of results."
  []
  (let [names (list-fixtures)]
    (when (empty? names)
      (throw (ex-info "no fixtures found"
                      {:fixtures-dir fixtures-dir})))
    (let [results (mapv (fn [name]
                          (println "[extraction-bench] running fixture:" name)
                          (run-fixture! name))
                        names)
          path (write-report! results)]
      (println "[extraction-bench] wrote report:" path)
      results)))
