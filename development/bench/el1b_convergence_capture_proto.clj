(ns el1b-convergence-capture-proto
  "THROWAWAY — EL-1b convergence-CAPTURE prototype (ADR 0015, emergence loop).
   NOT production code. Resolves the two EL-1b mechanism questions on real
   grain + real ColBERT:

   PART 1 — bundle the :novel candidate by MULTI-SIGNAL similarity.
     On :novel today, classify-task mints a fresh (random-uuid). A SECOND
     variant task (similar intent, worded differently, reranker doesn't clear
     the 0.7 match threshold) mints ANOTHER random-uuid -> scatter. The probe
     under test: before minting fresh, fuse MORE than the single reranker score
     — reranker fitness (intent) + raw ColBERT score (description/semantic) +
     shape (granularity == :tree-class) via RRF — and ask 'is this a VARIANT of
     an existing tree-class?' If a :tree-class candidate clears a BUNDLE
     threshold (looser than the match threshold) -> assign to THAT class
     (accrue on the bundle) instead of a fresh uuid.

   LIFECYCLE QUESTION (the one EL-1b must answer): at classify-time the task
   SIGNATURE is available but the EMITTED tree is not. Is classify-time
   task-signature similarity enough to converge two variants onto one class?
   This probe measures exactly that — if the :tree-class candidate from
   variant A is RETRIEVABLE + scores high enough on the fused signal when
   classifying variant B, classify-time bundling suffices (simplest scope).
   If not, the emitted-tree shape is needed -> flag a post-execution follow-up.

   PART 2 — evidence-gate retrieval. A one-off :tree-class (consolidation
   total below a SEPARATE retrieval gate) must NOT be a retrievable candidate;
   once it recurs past the gate it surfaces. The gate is PROVEN (one-off
   surfaces on recurrence), not a hidden cap.

   Run (venv read-only from orc-main; -J-D BEFORE -M:dev):
     OPENROUTER_API_KEY=... \\
     clojure -J-Dcolbert.venv.path=/Users/darylroberts/Desktop/Code/orc-main/.venv-colbert \\
             -J-Dcolbert.bridge.script=/Users/darylroberts/Desktop/Code/orc-rinject-redesign/scripts/colbert_bridge.py \\
             -M:dev -m el1b-convergence-capture-proto"
  (:require [runner]
            [ai.obney.orc.ontology.interface :as ont]
            [ai.obney.orc.ontology.core.task-classifier :as tc]
            [ai.obney.orc.ontology.core.todo-processors :as ont-tp]
            [ai.obney.orc.ontology.core.read-models :as rm]
            [ai.obney.orc.ontology.core.graph :as graph]
            [ai.obney.orc.colbert.interface :as colbert]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]))

;; ---------------------------------------------------------------------------
;; Two VARIANT tasks of ONE emergent specialization: "wire a JSON serialization
;; dependency into an export writer". Variant A is recorded as a tree-class;
;; variant B is the convergence target — it must NOT scatter a fresh uuid.
;; A DISTINCT task (rename-symbol) is the over-merge guard: it must NOT bundle.
;; ---------------------------------------------------------------------------
(def variant-a-class-id #uuid "deadbeef-1b00-4000-8000-0000000000a1")

(def variant-a-body
  {:summary (str "Task class for adding a third-party JSON serialization "
                 "dependency to a Clojure project's deps.edn and wiring it "
                 "into an export writer so a report map is serialized to a "
                 ".json file on disk. Dependency-wiring + serialization shape.")
   :scope :tree-class
   :capabilities ["Add a dependency to deps.edn"
                  "Wire a JSON serializer into an export writer"
                  "Serialize a report map to a .json file"]
   :strengths [{:trait "Handles deps.edn dependency additions cleanly"
                :confidence 0.9 :evidence-count 3}]
   :weaknesses []
   :representative-uses ["Add cheshire to deps.edn and serialize the report to JSON"]
   :avoid-when []
   :version 1
   :consolidated-from-event-count 3})

;; Variant A's instruction (the E4 "wire-dependency" verbatim — same task the gate uses).
(def variant-a-instruction
  "Add the cheshire JSON dependency to deps.edn and use it in the export writer to serialize the report map to a .json file.")

;; Variant B: SAME specialization (dependency-wiring + serialization), but a
;; DIFFERENT serialization target (EDN, not JSON) + different framing so the
;; reranker rates it a true VARIANT (related but below the 0.7 hard MATCH
;; threshold) rather than a clean match — that is the scatter case EL-1b
;; must converge without a fresh random-uuid.
(def variant-b-instruction
  "Pull in a serialization helper dependency and update the project's exporter component so it persists the accumulated results to disk in a portable serialized format for later reload.")

;; A DISTINCT task — the over-merge guard. Must classify :novel and NOT bundle to A.
(def distinct-instruction
  "Rename the function `calc` to `compute-tax` across the billing namespace and update every caller, including the test files.")

;; ---------------------------------------------------------------------------
;; A one-off junk class for Part 2 (the evidence gate). Distinct domain so it
;; can't accidentally bundle. Recorded once (total=1) -> must be filtered.
;; ---------------------------------------------------------------------------
(def oneoff-class-id #uuid "deadbeef-1b00-4000-8000-0000000000b1")
(def oneoff-body
  {:summary (str "Task class for generating a vegetarian dinner menu for eight "
                 "guests with a wine pairing per course and a shopping list.")
   :scope :tree-class
   :capabilities ["Plan a multi-course vegetarian menu" "Pair wine per course"]
   :strengths [{:trait "Balances courses" :confidence 0.8 :evidence-count 1}]
   :weaknesses []
   :representative-uses ["Plan a vegetarian dinner with wine pairings"]
   :avoid-when []
   :version 1
   :consolidated-from-event-count 1})
(def oneoff-instruction
  "Suggest a vegetarian dinner menu for eight guests with a wine pairing for each course and a shopping list.")

(def classifier-intent
  (str "I'm classifying a task to find the best-matching tree-class. "
       "Return the candidate whose recommended pattern best fits the "
       "task's structural shape and inputs/outputs."))

(defn build-sig [instruction]
  (tc/build-task-signature {:instruction instruction
                            :reads [:user-message :active-plan :workspace-root]
                            :writes [:assistant-response]
                            :mcp-tools ["shell/exec" "fs/read" "fs/list"]}))

(defn target-id->uuid [tid]
  (when tid (try (if (uuid? tid) tid (java.util.UUID/fromString (str tid))) (catch Throwable _ nil))))

(defn cand-class-id [c] (target-id->uuid (-> c :document-metadata :target-id)))

(defn record-class! [ctx id body]
  (cp/process-command
    (assoc ctx :command
           {:command/id (random-uuid)
            :command/timestamp (time/now)
            :command/name :ontology/record-tree-class-description
            :target-id id
            :body body})))

;; Tick a tree-class's consolidation counter by emitting :ontology/task-classified
;; events (the real accrual path — assign-task-class -> task-classified ->
;; consolidation-delta-counters bumps [:tree-class id] :total).
(defn tick-class! [ctx id n]
  (dotimes [_ n]
    (cp/process-command
      (assoc ctx :command
             {:command/name :ontology/assign-task-class
              :command/id (random-uuid)
              :command/timestamp (time/now)
              :source-sheet-id (random-uuid)
              :source-tick-id (random-uuid)
              :source-node-id (random-uuid)
              :assigned-tree-id id
              :confidence 0.5
              :top-candidates []
              :reasoning "proto tick"
              :was-fresh-mint? false}))))

;; ===========================================================================
;; The CANDIDATE bundle probe under prototype — MIRRORS what will land in
;; task_classifier.clj. Fuses reranker-fitness + raw ColBERT score + a
;; shape-match boost via RRF (graph/compute-rrf-scores), restricted to
;; :tree-class candidates, and returns the top fused candidate if it clears
;; the bundle threshold.
;; ===========================================================================
;; NOTE (root cause found in run 1): pure rank-based RRF (graph/compute-rrf-scores)
;; CANNOT gate a bundle — it uses ranks only, so the top :tree-class candidate
;; always lands at fused ~= 1/(60+1)+1/(60+1) ~= 0.0328 whether it's a true
;; variant or junk. RRF is a FUSION ranker, not an absolute-similarity gate. The
;; bundle DECISION needs absolute magnitude. So: use RRF to FUSE+RANK the
;; signals (pick the single best :tree-class candidate across signals), then gate
;; that winner on its ABSOLUTE reranker fitness (the calibrated 0..1 intent-fit
;; the LLM reranker produces, which already reads avoid-when via EL-2) AND a
;; corroborating ColBERT semantic floor.
(defn bundle-probe
  "Pick the best :tree-class candidate (RRF-fused rank across intent+semantic
   signals), then gate on absolute fitness >= `fit-gate` AND semantic >= `sem-gate`.
   Returns {:bundle-to id :fit :sem :fused-rank-score} when it bundles, else nil."
  [reranked raw fit-gate sem-gate]
  (let [tree-class? (fn [c] (= :tree-class (-> c :document-metadata :granularity)))
        ;; absolute signals keyed by class id
        fit-by-id (into {} (keep (fn [c] (when-let [id (cand-class-id c)]
                                           (when (tree-class? c) [id (or (:fitness-score c) 0.0)])))
                                 reranked))
        sem-by-id (into {} (keep (fn [c] (when-let [id (cand-class-id c)]
                                           (when (tree-class? c) [id (or (:score c) 0.0)])))
                                 raw))
        ;; RRF fuses the two ranked signal-lists to choose the single best
        ;; candidate (the FUSION primitive's correct role).
        intent-batch (mapv (fn [[id s]] {:uri id :score s}) fit-by-id)
        semantic-batch (mapv (fn [[id s]] {:uri id :score s}) sem-by-id)
        batches (filterv seq [intent-batch semantic-batch])
        fused (when (seq batches) (graph/compute-rrf-scores batches))
        [top-id rank-score] (first fused)
        fit (get fit-by-id top-id 0.0)
        sem (get sem-by-id top-id 0.0)]
    (when (and top-id (>= fit fit-gate) (>= sem sem-gate))
      {:bundle-to top-id :fit fit :sem sem :fused-rank-score rank-score})))

(defn -main [& _]
  (println "=== EL-1b CONVERGENCE-CAPTURE PROTOTYPE (ADR 0015) ===")
  (println "venv  :" (System/getProperty "colbert.venv.path"))
  (println "bridge:" (System/getProperty "colbert.bridge.script"))
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (println "FATAL: OPENROUTER_API_KEY not set") (System/exit 1))
  (try
    (runner/start!)
    (let [ctx (deref (var-get (requiring-resolve 'runner/system-state)))
          sig-a (build-sig variant-a-instruction)
          sig-b (build-sig variant-b-instruction)
          sig-distinct (build-sig distinct-instruction)
          sig-oneoff (build-sig oneoff-instruction)]

      ;; ================================================================
      ;; 0. RULE OUT THE HARNESS FIRST
      ;; ================================================================
      (println "\n############ 0. RULE-OUT-HARNESS ############")
      (let [idxs (filter #(= "ontology-descriptions" (:index-name %)) (colbert/list-indexes ctx))]
        (println "ontology-descriptions indexes built:" (count idxs)))
      (let [tf (ont/search-descriptions ctx
                 {:query sig-a :granularity :tree-fingerprint
                  :rerank-with-intent classifier-intent :k 5})]
        (println ":tree-fingerprint hits:" (count tf)
                 " all-tf? =" (and (seq tf) (every? #(= :tree-fingerprint (-> % :document-metadata :granularity)) tf))))

      ;; ================================================================
      ;; 1. RECORD variant A as a :tree-class + rebuild index
      ;; ================================================================
      (println "\n############ 1. RECORD variant-A class + rebuild ############")
      (let [r (record-class! ctx variant-a-class-id variant-a-body)]
        (println "record A events:" (count (:command-result/events r)) " error:" (pr-str (:command-result/error r))))
      ;; Tick A past the gate so it is a retrievable candidate (Part 2 interplay:
      ;; Part 1 convergence only makes sense for an ALREADY-recurring class).
      (tick-class! ctx variant-a-class-id 12)
      (Thread/sleep 400)
      (println "A consolidation total:" (rm/get-consolidation-total ctx :tree-class variant-a-class-id))
      (ont-tp/force-rebuild! ctx)
      (Thread/sleep 1200)

      ;; ================================================================
      ;; 2. PART 1 — variant B today SCATTERS; the probe converges it
      ;; ================================================================
      (println "\n############ 2. PART 1: variant-B convergence ############")
      (let [b-result (ont/classify-task ctx {:task-signature sig-b :threshold 0.7 :walk-down? false})]
        (println "variant-B classify-task TODAY: outcome=" (:outcome b-result)
                 " mint?=" (:was-fresh-mint? b-result)
                 " assigned=" (some-> (:assigned-tree-id b-result) str (subs 0 8))
                 " bundled-to-A? =" (= variant-a-class-id (:assigned-tree-id b-result))
                 "  <-- expect outcome=:novel, mint?=true, bundled-to-A?=false (SCATTER)"))

      ;; The probe inputs: the reranked two-axis candidates + the raw (no rerank).
      (let [reranked (ont/search-descriptions ctx
                       {:query sig-b :granularity #{:tree-fingerprint :tree-class}
                        :rerank-with-intent classifier-intent :k 5})
            raw (ont/search-descriptions ctx
                  {:query sig-b :granularity #{:tree-fingerprint :tree-class} :k 30})]
        (println "\nvariant-B reranked :tree-class candidates:")
        (doseq [c (filter #(= :tree-class (-> % :document-metadata :granularity)) reranked)]
          (println (format "   class=%s fit=%s%s"
                           (some-> (cand-class-id c) str (subs 0 8))
                           (some-> (:fitness-score c) double)
                           (if (= variant-a-class-id (cand-class-id c)) "  <== A" ""))))
        (println "variant-B raw :tree-class present (A reachable)? ="
                 (boolean (some #(= variant-a-class-id (cand-class-id %))
                                (filter #(= :tree-class (-> % :document-metadata :granularity)) raw))))
        (doseq [[fg sg] [[0.5 0.0] [0.6 0.0] [0.7 0.0]]]
          (let [p (bundle-probe reranked raw fg sg)]
            (println (format "   fit-gate %.2f sem-gate %.2f -> %s"
                             fg sg
                             (if p (format "BUNDLE-TO %s (fit=%.3f sem=%.3f)%s"
                                           (some-> (:bundle-to p) str (subs 0 8))
                                           (:fit p) (:sem p)
                                           (if (= variant-a-class-id (:bundle-to p)) "  <== A (CONVERGED)" ""))
                                 "no-bundle (mint fresh)"))))))

      ;; ================================================================
      ;; 3. OVER-MERGE GUARD — a DISTINCT :novel task must NOT bundle to A
      ;; ================================================================
      (println "\n############ 3. OVER-MERGE GUARD: distinct task ############")
      (let [reranked (ont/search-descriptions ctx
                       {:query sig-distinct :granularity #{:tree-fingerprint :tree-class}
                        :rerank-with-intent classifier-intent :k 5})
            raw (ont/search-descriptions ctx
                  {:query sig-distinct :granularity #{:tree-fingerprint :tree-class} :k 30})]
        (println "distinct reranked :tree-class candidates (RAW target-id shown):")
        (doseq [c (filter #(= :tree-class (-> % :document-metadata :granularity)) reranked)]
          (println (format "   raw-target=%s parsed=%s fit=%s"
                           (pr-str (-> c :document-metadata :target-id))
                           (some-> (cand-class-id c) str (subs 0 8))
                           (some-> (:fitness-score c) double))))
        (doseq [[fg sg] [[0.5 0.0] [0.6 0.0] [0.7 0.0]]]
          (let [p (bundle-probe reranked raw fg sg)]
            (println (format "   distinct @ fit-gate %.2f -> %s%s"
                             fg
                             (if p (format "BUNDLE-TO %s (fit=%.3f sem=%.3f)" (some-> (:bundle-to p) str (subs 0 8)) (:fit p) (:sem p)) "no-bundle (mint fresh)")
                             (if (and p (= variant-a-class-id (:bundle-to p))) "  !!! OVER-MERGE TO A" ""))))))

      ;; ================================================================
      ;; 4. PART 2 — evidence-gate: one-off filtered, recurrence surfaces
      ;; ================================================================
      (println "\n############ 4. PART 2: evidence gate ############")
      (record-class! ctx oneoff-class-id oneoff-body)
      (tick-class! ctx oneoff-class-id 1) ;; one-off: total=1
      (Thread/sleep 300)
      (ont-tp/force-rebuild! ctx)
      (Thread/sleep 1200)
      (let [gate 5
            total-before (rm/get-consolidation-total ctx :tree-class oneoff-class-id)
            raw (ont/search-descriptions ctx
                  {:query sig-oneoff :granularity #{:tree-fingerprint :tree-class} :k 30})
            present? (boolean (some #(= oneoff-class-id (cand-class-id %)) raw))
            in-filter-band? (and (pos? total-before) (< total-before gate))
            ;; End-to-end through the REAL classify-task with the gate: the
            ;; filtered one-off must NOT appear in its surfaced candidate set.
            pt (ont/classify-task ctx {:task-signature sig-oneoff :threshold 0.7
                                       :retrieval-gate gate :walk-down? false})
            in-classify-candidates? (boolean (some #(= oneoff-class-id (cand-class-id %))
                                                   (:top-candidates pt)))]
        (println "one-off total BEFORE recurrence:" total-before " (gate=" gate ")")
        (println "  raw retrieval contains one-off class? =" present?
                 "  in-filter-band (0<total<gate)? =" in-filter-band?
                 "  <-- expect present?=true, in-filter-band?=true")
        (println "  classify-task surfaced one-off in candidates? =" in-classify-candidates?
                 "  <-- expect FALSE (FILTERED end-to-end)"
                 " | classify outcome=" (:outcome pt) " assigned=" (some-> (:assigned-tree-id pt) str (subs 0 8))))
      ;; Recur it past the gate.
      (tick-class! ctx oneoff-class-id 6) ;; total now 7 >= gate 5
      (Thread/sleep 300)
      (let [gate 5
            total-after (rm/get-consolidation-total ctx :tree-class oneoff-class-id)
            pt (ont/classify-task ctx {:task-signature sig-oneoff :threshold 0.7
                                       :retrieval-gate gate :walk-down? false})
            in-classify-candidates? (boolean (some #(= oneoff-class-id (cand-class-id %))
                                                   (:top-candidates pt)))]
        (println "one-off total AFTER recurrence:" total-after "  above-gate? =" (>= total-after gate))
        (println "  classify-task surfaced one-off in candidates? =" in-classify-candidates?
                 "  <-- expect TRUE (SURFACES end-to-end once recurred)"
                 " | classify outcome=" (:outcome pt) " assigned=" (some-> (:assigned-tree-id pt) str (subs 0 8))))

      (println "\n=== DONE ==="))
    (catch Throwable t
      (println "ERROR:" (.getMessage t))
      (.printStackTrace t))
    (finally
      (try (runner/stop!) (catch Throwable _ nil))
      (try (colbert/stop-bridge!) (catch Throwable _ nil))
      (shutdown-agents))))
