(ns cc16-shadow-rate
  "CC-16 (ADR 0026 + ADR 0027) — the SHADOW MEASUREMENT that gates the Stage-2
   flip, and the contrast distribution CC-20 derives the penalty's form and value
   from.

   Stage 1 computes BOTH positive-signal variants on every pass at zero extra
   cost (cos-good is a MAX over already-scored strings). This harness runs the
   PRODUCTION hot path — `domain-penalty/penalize-candidates` with the real
   JVM-native ColBERT rerank injected through the shipped resolver seam — over
   REAL production living-description bodies and REAL task signatures, and reads
   the shadow stamps back off the result. It reimplements nothing.

   It answers the three watch conditions ADR 0026 names, and nothing else:

     1. FIRING RATE. Against the 0/154 baseline. Reported for BOTH variants,
        with the full contrast and penalty distributions.
     2. ZERO FALSE POSITIVES. The web-search-on-its-own-domain canary (EL-5 case
        (3), the synthetic probe EL-5 was built to protect) must stay EXACTLY 0.
        Plus a broader own-domain bank: every real behavior scored against its
        OWN :representative-uses, which are concrete task examples that appear in
        NEITHER guard set — so the bank is not vacuous by construction.
     3. DEMOTION, NOT FIRING. P-B measured that firing != demoting: the refactor
        force-fit needs penalty > 0.0145 to fall below the runner-up. Ranking here
        uses the same proxy P-B and the shipped probe use — the candidate's own
        ColBERT retrieval score against the task — so 'demoted' means it actually
        moves down the list, not that a number went positive.

   HONEST SCOPE. The 0/154 baseline came from P-C's harness (7 key variants x 11
   uncontaminated queries x 2 roles); that harness's code was discarded and its
   queries are not recoverable, so this is NOT a re-run of the same 154 cells. It
   is a larger, independently-constructed cell set on the same corpus and the same
   encoder, and the baseline it replicates is the CLAIM under test — 'the shipped
   positive signal cannot fire on production content' — not the literal number.

   No LLM, no grain server, no index, no API key: real encoder + the real corpus
   dump only. Prints its N and a rerank-call ledger.

   Run:
     clojure -M:dev -m cc16-shadow-rate"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [time-literals.read-write]
            [ai.obney.orc.colbert.core.encoder :as encoder]
            [ai.obney.orc.colbert.core.model-store :as model-store]
            [ai.obney.orc.colbert.core.operations :as operations]
            [ai.obney.orc.ontology.core.task-classifier :as tc]
            [ai.obney.orc.ontology.core.domain-penalty :as dp]))

(def corpus-path
  "The P-E production dump (2,713 events). Override with -Dcc16.corpus."
  (or (System/getProperty "cc16.corpus")
      "/private/tmp/claude-501/-Users-darylroberts-Desktop-Code-orc-sessions/1dcb8677-d588-4d07-b419-779f8489bfdb/scratchpad/pe/corpus-a89f9f58-9761-42c9-bc67-94acba7bd4f2.edn"))

(def demotion-threshold
  "P-B, real corpus, real ColBERT: below this the penalty fires and the force-fit
   stays rank #1. Firing is not demoting."
  0.0145)

;; -----------------------------------------------------------------------------
;; Real task signatures — the production shape (task-classifier/build-task-signature)
;; -----------------------------------------------------------------------------

(defn- sig [instruction & {:keys [reads writes mcp-tools]}]
  (tc/build-task-signature
   {:instruction instruction
    :reads (or reads [:user-message :active-plan :workspace-root])
    :writes (or writes [:assistant-response])
    :mcp-tools (or mcp-tools ["shell/exec" "fs/read" "fs/list"])}))

(def refactor-task-label "refactor-extract-helper (the FORCE-FIT case)")

(def tasks
  "A spread of real-shaped task signatures across the corpus's own domains, so
   the firing rate is over a realistic query mix rather than a cherry-picked one.
   The first is the EL-5 force-fit case; the second is the zero-FP canary."
  [[refactor-task-label
    (sig "Refactor the order service to extract a pure pricing helper from the request handler, preserving existing behavior and keeping all current tests green.")]
   ["web-search-own-domain (the ZERO-FP canary)"
    (sig "Search the web for the latest documentation on the payment API and summarize what you find."
         :reads [:user-message] :mcp-tools ["web/search"])]
   ["rename-symbol"
    (sig "Rename the function calc to compute-tax across the whole repository, including tests and docs, changing nothing else.")]
   ["add-function"
    (sig "Add a new function that computes the shipping surcharge for oversized parcels and wire it into the checkout path.")]
   ["debug-failing-test"
    (sig "The pricing test started failing after the last deploy. Work out why and fix it.")]
   ["perf-tune"
    (sig "The nightly report job takes 40 minutes. Make it faster without changing the numbers it produces.")]
   ["write-doc"
    (sig "Write the usage documentation for the new pricing API, with examples.")]
   ["wire-dependency"
    (sig "Introduce the new retry library and wire it into every outbound HTTP call site.")]
   ["extract-entities"
    (sig "Extract the parties, key dates and monetary amounts from this 90-page master services agreement."
         :reads [:user-message] :mcp-tools [])]
   ["classify-items"
    (sig "Classify each of these 4,000 support tickets into one of the seven predefined categories."
         :reads [:user-message] :mcp-tools [])]
   ["validate-artifact"
    (sig "Check this filing against the formal submission rules and report pass/fail per rule."
         :reads [:user-message] :mcp-tools [])]
   ["investigate"
    (sig "Orders are intermittently double-charged in production. Explain why.")]])

(defn- consolidator-signature
  "The LONGEST real production query: the consolidator's own parent-inference
   signature. Included because every signature above is ~75 word-piece tokens,
   and CC-17 measured that query LENGTH (not the guard content) drives how hard
   the [MASK] pedestal compresses the contrast — so a firing rate measured only
   on short queries would not be evidence about production.

   READ-ONLY use of a private var; consolidator.clj is not modified."
  [body]
  (@(requiring-resolve 'ai.obney.orc.ontology.core.consolidator/build-parent-inference-signature)
   body))

;; -----------------------------------------------------------------------------
;; Corpus
;; -----------------------------------------------------------------------------

(defn body->candidate
  "The enriched-candidate shape the penalty reads, from a real body. :content is
   the indexed description — exactly what EL-2's enrichment puts there."
  [body]
  {:document-id (subs (:summary body) 0 (min 42 (count (:summary body))))
   :content (:summary body)
   :avoid-when (vec (:avoid-when body))
   :strengths (vec (:strengths body))
   :weaknesses (vec (:weaknesses body))
   :representative-uses (vec (:representative-uses body))})

(defn- load-candidates []
  (let [evs (edn/read-string {:readers time-literals.read-write/tags} (slurp corpus-path))
        bodies (->> evs
                    (filter #(= :ontology/tree-description-updated (:event/type %)))
                    (keep :body)
                    (filter #(seq (:avoid-when %)))
                    (filter #(seq (:strengths %))))]
    (let [distinct-bodies (vec (vals (into {} (map (juxt :summary identity)) bodies)))]
      {:n-events (count evs)
       :n-bodies (count bodies)
       :raw-bodies distinct-bodies
       :candidates (mapv body->candidate distinct-bodies)})))

;; -----------------------------------------------------------------------------
;; The production seam, with the real encoder injected
;; -----------------------------------------------------------------------------

(def ^:dynamic *limit*
  "maximum_query_tokens for every rerank call in this run. nil = the shipped
   default. CC-17 relocated this from 32 to 464 and MEASURED that the move
   compresses batch-relative contrasts on short queries ~75x, so any contrast
   number is meaningless without the limit it was taken at — P-B's evidence
   (and therefore the golden fixture) was captured at 32."
  nil)

(defn- rerank* [query documents]
  (operations/rerank {} (cond-> {:query query :documents documents}
                          *limit* (assoc :maximum-query-tokens *limit*))))

(defn- real-resolver [calls]
  (constantly
   {:rerank (fn [_ctx {:keys [query documents]}]
              (swap! calls inc)
              (rerank* query documents))
    :normalize (fn [score & _] score)}))

(defn- run-pass
  "ONE production penalty pass (penalize-candidates, 4-arity, shipped defaults)
   for one task. Returns the stamped candidates."
  [candidates task calls config]
  (binding [dp/*colbert-resolver* (real-resolver calls)]
    (dp/penalize-candidates nil candidates task config)))

;; -----------------------------------------------------------------------------
;; Reporting
;; -----------------------------------------------------------------------------

(defn- fmt-dist [d]
  (if d
    (format "n=%d min %+.6f | p25 %+.6f | p50 %+.6f | p75 %+.6f | p95 %+.6f | max %+.6f | mean %+.6f"
            (:n d) (:min d) (:p25 d) (:p50 d) (:p75 d) (:p95 d) (:max d) (:mean d))
    "(NOT COMPUTED — absent, which is not the same as never fired)"))

(defn- variant-rows
  "Per-cell rows for one variant across all stamped passes."
  [passes a-key g-key p-key]
  (for [{:keys [label stamped]} passes
        c stamped
        :when (and (some? (get c a-key)) (some? (get c g-key)))]
    {:label label
     :document-id (:document-id c)
     :cos-avoid (get c a-key)
     :cos-good (get c g-key)
     :contrast (- (double (get c a-key)) (double (get c g-key)))
     :penalty (double (get c p-key))}))

(def ^:private variants
  [[:content+good-when :cos-avoid-with-content :cos-good-with-content :domain-penalty-with-content
    "SHIPPED   (:content + :good-when)"]
   [:good-when :cos-avoid-sans-content :cos-good-sans-content :domain-penalty-sans-content
    "ADR 0026  (:good-when alone)     "]])

(defn- distribution [xs]
  (#'dp/distribution xs))

;; -----------------------------------------------------------------------------

(defn run-report []
  (let [calls (atom 0)
        config dp/default-penalty-config
        {:keys [n-events n-bodies candidates raw-bodies]} (load-candidates)
        enc (encoder/get-encoder (model-store/resolve-model-dir))
        tasks (conj tasks
                    ["consolidator-signature (LONGEST real query)"
                     (consolidator-signature (first raw-bodies))])
        passes (mapv (fn [[label task]]
                       {:label label :task task
                        :stamped (run-pass candidates task calls config)})
                     tasks)]

    (println "\n================ CC-16 SHADOW RATE — ADR 0026 watch conditions ================")
    (println "\n=== N (stated, not assumed) ===")
    (println "  corpus events                :" n-events)
    (println "  bodies with avoid+strengths  :" n-bodies)
    (println "  DISTINCT candidates          :" (count candidates))
    (println "  real task signatures         :" (count tasks))
    (println "  cells (candidates x tasks)   :" (* (count candidates) (count tasks)))
    (println "  encoder                      :" (str (model-store/resolve-model-dir)))
    (println "  maximum_query_tokens         :" (or *limit* "(shipped default)"))
    (println "  config                       :" (pr-str (select-keys config [:scorer :margin :penalty-scale :penalty-cap :positive-signal :colbert-norm])))

    ;; ---- Liveness control: rule out a dead harness before reading any rate ----
    (println "\n=== LIVENESS CONTROL (rule out the harness, not just the code) ===")
    (let [probe (rerank* (second (first tasks))
                         ["extract a pure helper from a request handler"
                          "preheat the oven and bake the sourdough loaf"])
          scores (mapv :score probe)]
      (println (format "  refactor query vs [on-topic, sourdough]: %.4f vs %.4f  (differ? %s)"
                       (double (first scores)) (double (second scores))
                       (not= (first scores) (second scores))))
      (println "  task-1 word-piece tokens:" (count (encoder/encode-ids enc (second (first tasks)))))
      (when (= (first scores) (second scores))
        (println "  !! DEAD ENCODER — every number below is meaningless")))

    ;; ---- WATCH CONDITION 1 ----
    (println "\n=== WATCH CONDITION 1 — FIRING RATE (baseline: 0 of 154 cells) ===")
    (doseq [[_variant a g p label] variants]
      (let [rows (variant-rows passes a g p)
            fired (filter #(pos? (:penalty %)) rows)
            demoting (filter #(> (:penalty %) demotion-threshold) rows)
            pinned (filter #(= 1.0 (double (:cos-good %))) rows)]
        (println (format "  %s  fired %4d/%4d (%.3f%%)  |  penalty > %.4f in %d"
                         label (count fired) (count rows)
                         (* 100.0 (/ (count fired) (double (max 1 (count rows)))))
                         demotion-threshold (count demoting)))
        ;; The DIRECT replication of P-C's headline defect: cos-good == 1.000000
        ;; in 153 of 154 cells. This is the mechanism check ADR 0027 decision 1
        ;; demands — can the mechanism EXPRESS the property at all, before any
        ;; number is fitted to it?
        (println (format "      cos-good pinned at EXACTLY 1.0 : %4d/%4d (%.1f%%)   <- P-C baseline was 153/154 = 99.4%%"
                         (count pinned) (count rows)
                         (* 100.0 (/ (count pinned) (double (max 1 (count rows)))))))
        (println "      contrast " (fmt-dist (distribution (map :contrast rows))))
        (println "      penalty  " (fmt-dist (distribution (map :penalty rows))))))

    ;; A gate that cannot fire at ANY value of its own knob is a mechanism
    ;; problem, not a calibration one. Reporting the sweep is distribution
    ;; reporting (ADR 0027 decision 2), NOT calibration — CC-20 owns the value.
    (println "\n  MARGIN SWEEP (distribution reporting, not calibration — CC-20 owns the value):")
    (println (format "    %-34s %10s %10s %10s %10s %10s" "variant" "m=0.010" "m=0.005" "m=0.003" "m=0.001" "m=0.000"))
    (doseq [[_variant a g p label] variants]
      (let [rows (variant-rows passes a g p)
            xs (map :contrast rows)
            at (fn [m] (count (filter #(> % m) xs)))]
        (println (format "    %-34s %10s %10s %10s %10s %10s" label
                         (str (at 0.010) "/" (count xs)) (str (at 0.005) "/" (count xs))
                         (str (at 0.003) "/" (count xs)) (str (at 0.001) "/" (count xs))
                         (str (at 0.000) "/" (count xs))))))

    ;; ---- WATCH CONDITION 2 ----
    (println "\n=== WATCH CONDITION 2 — ZERO FALSE POSITIVES ===")
    (println "  (a) THE CANARY: the web-search-on-its-own-domain task (EL-5 case (3)).")
    (let [ws-pass (first (filter #(str/starts-with? (:label %) "web-search") passes))
          ;; The synthetic EL-5 probe candidate, verbatim from el5_zero_fp_check.
          ws-cand {:document-id "websearch(probe)"
                   :avoid-when ["the web search requires special elevated permissions or authenticated access the agent does not hold"
                                "the task is purely computational with no external lookup need"]
                   :content "Web-search gathers fresh external information by issuing search queries and reading results to ground downstream work."
                   :strengths [{:good-when "the task needs current external information not already in context"}]}
          probe-stamped (first (run-pass [ws-cand] (:task ws-pass) calls config))]
      (println (format "      probe candidate: p(with)=%.6f  p(sans)=%.6f   BOTH ZERO? %s"
                       (double (:domain-penalty-with-content probe-stamped))
                       (double (:domain-penalty-sans-content probe-stamped))
                       (and (zero? (double (:domain-penalty-with-content probe-stamped)))
                            (zero? (double (:domain-penalty-sans-content probe-stamped))))))
      (println "      (contrast with=" (format "%+.6f" (- (double (:cos-avoid-with-content probe-stamped))
                                                          (double (:cos-good-with-content probe-stamped))))
               " sans=" (format "%+.6f" (- (double (:cos-avoid-sans-content probe-stamped))
                                           (double (:cos-good-sans-content probe-stamped)))) ")"))

    (println "  (b) OWN-DOMAIN BANK: every real behavior against its OWN :representative-uses")
    (println "      (concrete task examples that appear in NEITHER guard set — non-vacuous).")
    (let [bank (for [c candidates
                     u (:representative-uses c)
                     :when (and (string? u) (not (str/blank? u)))]
                 [c u])
          rows (mapv (fn [[c u]]
                       (let [st (first (run-pass [c] (sig u) calls config))]
                         {:document-id (:document-id c)
                          :use u
                          :with (double (:domain-penalty-with-content st))
                          :sans (double (:domain-penalty-sans-content st))}))
                     bank)
          fp-with (filter #(pos? (:with %)) rows)
          fp-sans (filter #(pos? (:sans %)) rows)]
      (println (format "      N = %d (behavior, own-use) pairs" (count rows)))
      (println (format "      SHIPPED  false positives: %d/%d" (count fp-with) (count rows)))
      (println (format "      ADR 0026 false positives: %d/%d" (count fp-sans) (count rows)))
      (doseq [r (take 12 (sort-by (comp - :sans) fp-sans))]
        (println (format "        FP p=%.6f  %-44s  <- %s"
                         (:sans r) (:document-id r) (subs (:use r) 0 (min 60 (count (:use r))))))))

    ;; ---- WATCH CONDITION 3 ----
    (println "\n=== WATCH CONDITION 3 — DEMOTION, NOT FIRING ===")
    (println "  Proxy fitness = the candidate's own ColBERT retrieval score for the task")
    (println "  (the same proxy P-B and the shipped probe use). Rank BEFORE vs AFTER penalty.")
    (let [{:keys [task]} (first (filter #(= refactor-task-label (:label %)) passes))
          summaries (mapv :content candidates)
          _ (swap! calls inc)
          retrieval (rerank* task summaries)
          by-summary (into {} (map (juxt :content :score)) retrieval)
          with-proxy (mapv #(assoc % :fitness-score (get by-summary (:content %))) candidates)
          rank-of (fn [stamped-or-plain id key]
                    (->> stamped-or-plain
                         (sort-by (fn [c] (or (get c key) -1.0)) >)
                         (map :document-id)
                         (map-indexed vector)
                         (filter #(= id (second %)))
                         ffirst))
          force-fit-id (->> with-proxy
                            (filter #(str/starts-with? (:content %) "Rename-move-symbol"))
                            first :document-id)
          stamped (run-pass with-proxy task calls config)
          apply-variant (fn [p-key]
                          (mapv (fn [c] (assoc c :fitness-variant
                                               (dp/apply-penalty (get by-summary (:content c))
                                                                 (double (get c p-key)))))
                                stamped))]
      ;; SEPARABILITY ORDER on the force-fit task: is the force-fit the TOP
      ;; contrast, i.e. can the mechanism express the property even where the
      ;; shipped margin is too high to act on it? (ADR 0027 decision 1.)
      (println "  separability ORDER on the force-fit task (top 6 contrasts per variant):")
      (doseq [[_v a g _p vlabel] variants]
        (let [ranked (->> stamped
                          (map (fn [c] [(- (double (get c a)) (double (get c g))) (:document-id c)]))
                          (sort-by first >))
              pos (->> ranked (map second) (map-indexed vector)
                       (filter #(= force-fit-id (second %))) ffirst)]
          (println (format "    %s  force-fit is contrast-rank #%d of %d" vlabel (inc pos) (count ranked)))
          (doseq [[cst id] (take 6 ranked)]
            (println (format "        %+.6f  %s%s" cst id (if (= id force-fit-id) "   <== FORCE-FIT" ""))))))
      (println "  force-fit candidate:" force-fit-id)
      (println (format "  BASELINE rank (retrieval only)            : #%d of %d"
                       (inc (rank-of with-proxy force-fit-id :fitness-score)) (count candidates)))
      (doseq [[_v _a _g p-key label] variants]
        (let [ranked (apply-variant p-key)
              r (inc (rank-of ranked force-fit-id :fitness-variant))
              pen (->> stamped (filter #(= force-fit-id (:document-id %))) first (#(double (get % p-key))))]
          (println (format "  %s -> penalty %.6f, rank #%d of %d  | demotes? %s | p > %.4f? %s"
                           label pen r (count candidates)
                           (> r (inc (rank-of with-proxy force-fit-id :fitness-score)))
                           demotion-threshold (> pen demotion-threshold))))))

    ;; ---- CC-20 input ----
    (println "\n=== CC-20 INPUT — the contrast distribution, per variant, per task ===")
    (println (format "  %-46s %10s %12s %12s %12s"
                     "task" "variant" "p50" "p95" "max"))
    (doseq [{:keys [label stamped]} passes
            [_v a g _p vlabel] variants]
      (let [xs (keep (fn [c] (when (and (some? (get c a)) (some? (get c g)))
                               (- (double (get c a)) (double (get c g)))))
                     stamped)
            d (distribution xs)]
        (println (format "  %-46s %10s %+12.6f %+12.6f %+12.6f"
                         (subs label 0 (min 46 (count label)))
                         (if (str/starts-with? vlabel "SHIPPED") "shipped" "adr0026")
                         (:p50 d) (:p95 d) (:max d)))))

    (println "\n=== LEDGER ===")
    (println "  real ColBERT rerank calls made:" @calls)

    ;; CC-20 consumes the DISTRIBUTION, not this printout: dump every cell so the
    ;; penalty's form and value can be derived without re-prototyping.
    (let [out (or (System/getProperty "cc16.out")
                  (str "/tmp/cc16-cells-" (or *limit* "shipped") ".edn"))]
      (spit out (pr-str
                 {:maximum-query-tokens (or *limit* :shipped-default)
                  :config (select-keys config [:scorer :margin :penalty-scale
                                               :penalty-cap :positive-signal :colbert-norm])
                  :n-candidates (count candidates)
                  :n-tasks (count tasks)
                  :rerank-calls @calls
                  :cells (into {}
                               (map (fn [[_v a g p label]]
                                      [(if (str/starts-with? label "SHIPPED")
                                         :content+good-when :good-when)
                                       (vec (variant-rows passes a g p))]))
                               variants)}))
      (println "  per-cell EDN for CC-20:" out))
    (println "===============================================================================\n")))

(defn -main
  "Optional arg: maximum_query_tokens for every rerank call (default = shipped).
   Run it at 32 as well as the shipped limit — P-B's evidence (and therefore the
   golden fixture) was captured at 32, and CC-17 MEASURED that the limit, not the
   signal, dominates the magnitude."
  [& args]
  (time-literals.read-write/print-time-literals-clj!)
  (binding [*limit* (some-> (first args) parse-long)]
    (run-report))
  (System/exit 0))
