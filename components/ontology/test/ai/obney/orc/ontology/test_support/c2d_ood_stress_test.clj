(ns ai.obney.orc.ontology.test-support.c2d-ood-stress-test
  "C-Loop-3 R04 — out-of-distribution stress orchestrator for the
   R-Inject + classify-task + classify-behaviors path.

   Loads a corpus of hand-curated OOD instructions (under
   `development/bench/ood-corpus/*.txt`), runs each through the
   classifier wedge to obtain an envelope shape
   `{:assigned-tree-id :confidence :was-fresh-mint?
     :parent-tree-id :rerank-fallback? :top-candidates ...}`,
   classifies the outcome (direct-match vs walk-down-fired vs
   fresh-mint vs rerank-fallback), aggregates rates + latency, and
   writes per-instruction EDNs + a combined EDN + a markdown summary
   to a results directory.

   This namespace contains only PURE HELPERS — loaders, classifiers,
   aggregators, formatters, persistence. The actual live LLM execution
   lives in `c2d-ood-stress-live` (a separate namespace that orchestrates
   real `bench/run!` calls) so this one can be unit-tested without
   touching OpenRouter.

   The R03 test
   (components/ontology/test/ai/obney/orc/ontology/r03_ood_stress_test.clj)
   exercises every function in this namespace against synthetic
   fixtures."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

;; =============================================================================
;; Corpus loader
;; =============================================================================

(defn- strip-leading-comments
  "Return the lines of `s` after the leading block of lines that start with
   `;` (any number of semicolons). Comment lines are convention for
   provenance / family annotations; the rest of the file is the
   instruction text."
  [s]
  (let [lines (str/split-lines s)
        ;; drop while line starts with ; possibly preceded by whitespace
        body (drop-while (fn [line] (re-matches #"^\s*;.*" line)) lines)]
    (str/join "\n" body)))

(defn load-corpus
  "Read every `*.txt` file in `dir` and return a vector of corpus entries
   `{:slug :instruction :source-path}` sorted by filename.

   - `:slug` is the filename without the `.txt` suffix
   - `:instruction` is the file body with any leading `;`-prefixed lines
     stripped (provenance / family annotations are metadata, not part
     of the prompt)
   - `:source-path` is the absolute path on disk for HITL drill-down

   Skips hidden files (`.*`) and any file whose suffix isn't `.txt`."
  [dir]
  (let [d (io/file dir)]
    (if (and (.exists d) (.isDirectory d))
      (->> (.listFiles d)
           (filter (fn [^java.io.File f]
                     (and (.isFile f)
                          (let [n (.getName f)]
                            (and (str/ends-with? n ".txt")
                                 (not (str/starts-with? n ".")))))))
           (sort-by (fn [^java.io.File f] (.getName f)))
           (mapv (fn [^java.io.File f]
                   (let [body (slurp f)
                         name (.getName f)
                         slug (subs name 0 (- (count name) 4))]   ; drop ".txt"
                     {:slug slug
                      :instruction (strip-leading-comments body)
                      :source-path (.getAbsolutePath f)}))))
      [])))

;; =============================================================================
;; Per-instruction outcome classifier
;; =============================================================================

(defn classify-outcome
  "Given a classify-task envelope (the shape produced by the C-2c-2 wedge
   in production), return a flat map of derived structural flags. Pure
   function — no live LLM, no IO. The structural flags partition the
   outcome space into mutually-exclusive-enough categories for
   aggregate-metrics counting:

     :direct-matched?      top-1 fitness-score >= 0.9 (high-confidence
                           classification — the corpus had a clear match)
     :walk-down-fired?     top-1 target-id != envelope :assigned-tree-id
                           (the classifier descended into the tree-class
                           hierarchy to a narrower-but-still-confident match)
     :was-fresh-mint?      propagated from envelope — the classifier
                           returned the not-matched path, freshly
                           generated UUID, model invited to mint
     :rerank-fallback?     propagated from envelope — the reranker
                           failed and the classifier fell back to pure
                           ColBERT similarity scoring; treat the result
                           with caution

   Notes on overlap: a fresh-mint can ALSO be a rerank-fallback (the
   reranker failed AND no candidate hit threshold). A direct-matched
   cannot be a fresh-mint. Walk-down-fired implies the parent-tree-id
   is populated."
  [envelope]
  (let [top-1 (first (:top-candidates envelope))
        top-fitness (or (:fitness-score top-1) 0.0)
        top-target-id (-> top-1 :document-metadata :target-id)
        assigned-id (:assigned-tree-id envelope)
        ;; Direct-matched requires a clean rerank match (NOT a fallback)
        ;; AND a non-fresh-mint envelope AND high top-1 fitness.
        fresh-mint? (boolean (:was-fresh-mint? envelope))
        rerank-fallback? (boolean (:rerank-fallback? envelope))
        direct? (and (not fresh-mint?)
                     (not rerank-fallback?)
                     (>= top-fitness 0.9))
        ;; Walk-down fired when the classifier returned a narrower
        ;; (descendant) classification — :assigned-tree-id differs from
        ;; the top-1's :target-id (the entry point of the walk).
        walk-down? (and (not fresh-mint?)
                        (some? assigned-id)
                        (some? top-target-id)
                        (not= assigned-id top-target-id))]
    {:direct-matched? direct?
     :walk-down-fired? walk-down?
     :was-fresh-mint? fresh-mint?
     :rerank-fallback? rerank-fallback?}))

;; =============================================================================
;; Aggregate metrics
;; =============================================================================

(defn- median [coll]
  (when (seq coll)
    (let [sorted (vec (sort coll))
          n (count sorted)
          mid (quot n 2)]
      (if (odd? n)
        (nth sorted mid)
        (/ (+ (nth sorted (dec mid)) (nth sorted mid)) 2)))))

(defn- percentile
  "Nearest-rank approach. Returns the value at the index that is the
   ceiling of (p/100) * N."
  [coll p]
  (when (seq coll)
    (let [sorted (vec (sort coll))
          n (count sorted)
          idx (max 0 (min (dec n)
                          (dec (int (Math/ceil (* (/ p 100.0) n))))))]
      (nth sorted idx))))

(defn aggregate-metrics
  "Roll `results` (a seq of per-instruction maps `{:outcome :elapsed-ms
   :parent-chain ...}`) into a structural metric map for the summary.
   Pure over its inputs — no IO, no LLM.

   On an empty input every count is 0, every rate is 0.0, and the
   latency summary is all zeros (no division by zero)."
  [results]
  (let [n (count results)
        n-direct (count (filter (comp :direct-matched? :outcome) results))
        n-walk (count (filter (comp :walk-down-fired? :outcome) results))
        n-fresh (count (filter (comp :was-fresh-mint? :outcome) results))
        n-rerank-fail (count (filter (comp :rerank-fallback? :outcome) results))
        latencies (keep :elapsed-ms results)
        chains (map (comp count :parent-chain) results)
        chain-dist (frequencies chains)
        denom (max 1 n)]
    {:total-count n
     :direct-match-count n-direct
     :walk-down-fired-count n-walk
     :fresh-mint-count n-fresh
     :rerank-failure-count n-rerank-fail
     :direct-match-rate (if (zero? n) 0.0 (double (/ n-direct denom)))
     :walk-down-fired-rate (if (zero? n) 0.0 (double (/ n-walk denom)))
     :fresh-mint-rate (if (zero? n) 0.0 (double (/ n-fresh denom)))
     :rerank-failure-rate (if (zero? n) 0.0 (double (/ n-rerank-fail denom)))
     :latency-mean-ms (if (seq latencies)
                        (double (/ (reduce + latencies) (count latencies)))
                        0)
     :latency-median-ms (or (median latencies) 0)
     :latency-p95-ms (or (percentile latencies 95) 0)
     :parent-chain-depth-distribution chain-dist}))

;; =============================================================================
;; Markdown summary
;; =============================================================================

(defn- fmt-pct [r]
  (format "%.1f%%" (* 100.0 (double r))))

(defn- fmt-ms [n]
  (cond
    (number? n) (format "%d ms" (int n))
    :else (str n)))

(defn- outcome-label [{:keys [direct-matched? walk-down-fired?
                              was-fresh-mint? rerank-fallback?]}]
  (cond
    rerank-fallback? "rerank-fallback"
    was-fresh-mint?  "fresh-mint"
    walk-down-fired? "walk-down"
    direct-matched?  "direct-match"
    :else            "moderate"))

(defn markdown-summary
  "Render the OOD-RESULTS.md content. Includes:
   - title + an honest-curation-disclosure block (we want HITL audit to
     know this corpus is hand-curated and may have selection bias)
   - aggregate rates (direct-match / walk-down / fresh-mint / rerank-fail)
   - a per-instruction table with audit columns marked `[?]` for HITL
     fill-in (whether the agent should have minted, whether the body
     was substantive, etc.)"
  [corpus results metrics]
  (let [by-slug (into {} (map (juxt :slug identity)) results)]
    (str
      "# OOD Stress Test Results\n\n"
      "> **Honest curation disclosure.** This corpus is hand-curated by\n"
      "> the project maintainer to exercise the R-Inject classifier on\n"
      "> instructions that the baseline corpus DOES NOT cleanly cover.\n"
      "> The selection has bias toward eliciting fresh-mint outcomes;\n"
      "> the aggregate rates here are informative but should not be read\n"
      "> as production traffic statistics. Each row is marked `[?]` in\n"
      "> the audit columns so a HITL reviewer can grep for unaudited\n"
      "> rows and fill them in after spot-checking the saved envelope.\n\n"

      "## Aggregate rates\n\n"
      "| metric | count | rate |\n"
      "|---|---:|---:|\n"
      "| direct-match | " (:direct-match-count metrics)
      " | " (fmt-pct (:direct-match-rate metrics)) " |\n"
      "| walk-down fired | " (:walk-down-fired-count metrics)
      " | " (fmt-pct (:walk-down-fired-rate metrics)) " |\n"
      "| fresh-mint | " (:fresh-mint-count metrics)
      " | " (fmt-pct (:fresh-mint-rate metrics)) " |\n"
      "| rerank-failure | " (:rerank-failure-count metrics)
      " | " (fmt-pct (:rerank-failure-rate metrics)) " |\n"
      "| **total** | " (:total-count metrics) " | — |\n\n"

      "## Latency\n\n"
      "- mean: " (fmt-ms (:latency-mean-ms metrics)) "\n"
      "- median: " (fmt-ms (:latency-median-ms metrics)) "\n"
      "- p95: " (fmt-ms (:latency-p95-ms metrics)) "\n\n"

      "## Per-instruction\n\n"
      "| slug | outcome | confidence | parent-chain-depth | assigned-tree-id | should-mint? [?] | substantive? [?] | review-notes [?] |\n"
      "|---|---|---:|---:|---|---|---|---|\n"
      (str/join "\n"
        (for [{:keys [slug]} corpus
              :let [r (get by-slug slug)
                    env (:envelope r)
                    outcome (:outcome r)]]
          (str "| " slug
               " | " (outcome-label outcome)
               " | " (when env (format "%.2f" (double (or (:confidence env) 0.0))))
               " | " (count (:parent-chain r))
               " | " (when env (subs (str (:assigned-tree-id env)) 0
                                     (min 8 (count (str (:assigned-tree-id env)))))) "..."
               " | [?]"
               " | [?]"
               " | [?] |")))
      "\n")))

;; =============================================================================
;; EDN persistence
;; =============================================================================

(defn- now-stamp []
  (str (java.time.LocalDateTime/now)
       (java.time.format.DateTimeFormatter/ofPattern "uuuu-MM-dd_HHmmss")))

(defn persist-run!
  "Write the run artifacts to `:dir`:
     - one `ood-result-<slug>.edn` per instruction with the verbatim
       envelope + outcome + elapsed-ms + parent-chain
     - one `ood-combined_<ts>.edn` with the full `{:corpus :results
       :metrics}` shape for downstream tooling
     - `OOD-RESULTS.md` regenerated each run (overwritten — the .edn
       artifacts are the audit trail; the markdown is a fresh
       projection)

   Returns `{:per-instruction [<path> ...] :combined <path>
   :markdown <path>}` for HITL inspection."
  [{:keys [dir corpus results metrics]}]
  (let [d (io/file dir)
        _ (.mkdirs d)
        ts (now-stamp)
        per-paths (vec
                    (for [r results
                          :let [path (str dir "/ood-result-" (:slug r) ".edn")]]
                      (do (spit path (with-out-str (pp/pprint r)))
                          path)))
        combined-path (str dir "/ood-combined.edn")
        md-path (str dir "/OOD-RESULTS.md")]
    (spit combined-path
          (with-out-str (pp/pprint {:corpus corpus
                                    :results results
                                    :metrics metrics
                                    :generated-at ts})))
    (spit md-path (markdown-summary corpus results metrics))
    {:per-instruction per-paths
     :combined combined-path
     :markdown md-path}))
