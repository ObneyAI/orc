(ns ai.obney.orc.orc-service.r-inject-classifier-context-test
  "R-Inject — verify R05's classifier output reaches the model's Phase 1
   prompt via the new `apply-r05-classifier-context` helper.

   Covers:
   - Happy path: structural-confident match + behavioral top-3 → block
     contains both sections, reasoning verbatim, summary guidance verbatim,
     original instruction preserved after the divider
   - Structural fresh-mint branch (no high-confidence match)
   - Behavioral fresh-mint marker at top-1 (mint-recommended branch)
   - No `:r05-classifier` on node → identity (no-op preserves contract)
   - Rerank-fallback caution annotation
   - Behavioral over-3 candidates capped at 3

   The wedge integration + pipeline wiring are exercised by separate
   tests in the same component (matches R05b/R05c test-file pattern)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]
            [ai.obney.orc.ontology.interface :as ontology]))

;; =============================================================================
;; Test data builders — hand-built classifier payloads matching the live shapes
;; =============================================================================

(defn- mk-structural-candidate
  ([target-id reasoning content fitness]
   (mk-structural-candidate target-id reasoning content fitness :reranker))
  ([target-id reasoning content fitness rerank-source]
   {:content content
    :document-id (str ":tree-fingerprint:" target-id)
    :document-metadata {:granularity :tree-fingerprint :target-id target-id}
    :fitness-score fitness
    :reasoning reasoning
    :rerank-source rerank-source}))

(defn- mk-behavioral-entry
  ([behavior-id confidence reasoning]
   (mk-behavioral-entry behavior-id confidence reasoning :reranker false))
  ([behavior-id confidence reasoning rerank-source was-fresh-mint?]
   {:behavior-id behavior-id
    :confidence confidence
    :was-fresh-mint? was-fresh-mint?
    :reasoning reasoning
    :rerank-source rerank-source}))

(defn- mk-node
  "Node payload as the pipeline would see after the wedge runs. `payload`
   slots into :context.:r05-classifier."
  [instruction payload]
  {:id (random-uuid)
   :type :repl-researcher
   :name "test-node"
   :instruction instruction
   :context {:tree-id (or (get-in payload [:structural :assigned-tree-id])
                          (random-uuid))
             :r05-classifier payload}})

;; =============================================================================
;; RED #1 — Happy path: structural-confident + behavioral-top-3
;; =============================================================================

(deftest happy-path-structural-confident-plus-behavioral-top-3
  (let [structural-target (random-uuid)
        structural-content "ChunkedExtraction pattern: per-chunk :llm extraction then aggregate via :map-each / sequential reduce."
        structural-reasoning "Top-1 because the task explicitly chunks the large RFP and the candidate's pattern matches the per-chunk extraction shape."
        b1-id (random-uuid)
        b2-id (random-uuid)
        b3-id (random-uuid)
        b1-summary "Extraction turns raw text into typed structured items. The proven shape uses :llm with explicit :output-schemas as a shape contract..."
        b2-summary "Analysis reasons over already-extracted items to produce structured findings..."
        b3-summary "Synthesis integrates multiple structured inputs into a single cohesive artifact..."
        payload {:structural {:assigned-tree-id structural-target
                              :confidence 0.92
                              :was-fresh-mint? false
                              :reasoning structural-reasoning
                              :top-candidates [(mk-structural-candidate
                                                 structural-target
                                                 structural-reasoning
                                                 structural-content
                                                 0.92)]
                              :rerank-fallback? false}
                 :behavioral {:behaviors [(mk-behavioral-entry b1-id 0.95 "Extraction is the clearest fit because the task asks for structured fields.")
                                          (mk-behavioral-entry b2-id 0.85 "Analysis fits as the downstream step over the extracted items.")
                                          (mk-behavioral-entry b3-id 0.78 "Synthesis is appropriate for the executive-summary output.")]
                              :rerank-fallback? false}}
        original-instruction "Document available: :yyj_rfp (large RFP, ~280K chars). Task: Produce structured extractions including :summary, :key-dates, :entities."
        node (mk-node original-instruction payload)
        ;; Stub get-description to return seed body :summary fields.
        stub-bodies {b1-id {:summary b1-summary :capabilities ["x"] :strengths [] :weaknesses []
                            :representative-uses ["x"] :avoid-when ["x"]
                            :version 1 :consolidated-from-event-count 1}
                     b2-id {:summary b2-summary :capabilities ["x"] :strengths [] :weaknesses []
                            :representative-uses ["x"] :avoid-when ["x"]
                            :version 1 :consolidated-from-event-count 1}
                     b3-id {:summary b3-summary :capabilities ["x"] :strengths [] :weaknesses []
                            :representative-uses ["x"] :avoid-when ["x"]
                            :version 1 :consolidated-from-event-count 1}}
        result (with-redefs [ontology/get-description
                             (fn [_ctx _granularity target-id]
                               (get stub-bodies target-id))]
                 (tp/apply-r05-classifier-context node {}))
        instruction (:instruction result)]

    (testing "block opens with the corpus-suggestions header"
      (is (str/starts-with? instruction "## Suggested patterns from corpus")
          "Block header is the first thing the model sees"))

    (testing "block contains an honest framing line (examples, not mandates)"
      (is (re-find #"(?i)examples retrieved from the seed corpus" instruction)
          "Explicit 'examples' framing")
      (is (re-find #"(?i)not mandates" instruction)
          "Explicit non-mandate framing"))

    (testing "structural section appears with confidence + reasoning verbatim + content verbatim"
      (is (re-find #"### Structural pattern" instruction))
      (is (re-find #"0\.92" instruction)
          "Structural confidence rendered as 0.92")
      (is (str/includes? instruction structural-reasoning)
          "Structural reasoning verbatim")
      (is (str/includes? instruction structural-content)
          "Structural :content (seed summary) verbatim"))

    (testing "behavioral section appears with 3 candidates and their reasoning verbatim"
      ;; When a seed-name resolves from the body's :summary (via the
      ;; derive-seed-name helper — e.g., "Extraction turns raw text..."
      ;; → "Extraction"), the header reads "Behavioral: <name>"; only
      ;; the no-seed-name path falls back to "Behavioral suggestion".
      ;; This test's stub summaries all resolve to a seed name, so we
      ;; pin the seed-name path.
      (is (re-find #"Behavioral: Extraction" instruction)
          "Behavioral section labels the first candidate by its seed name")
      (is (re-find #"Behavioral: Analysis" instruction)
          "Behavioral section labels the second candidate by its seed name")
      (is (re-find #"Behavioral: Synthesis" instruction)
          "Behavioral section labels the third candidate by its seed name")
      (is (re-find #"0\.95" instruction))
      (is (re-find #"0\.85" instruction))
      (is (re-find #"0\.78" instruction))
      (is (str/includes? instruction "Extraction is the clearest fit"))
      (is (str/includes? instruction "Analysis fits as the downstream"))
      (is (str/includes? instruction "Synthesis is appropriate")))

    (testing "behavioral guidance includes each seed's :summary verbatim"
      (is (str/includes? instruction b1-summary))
      (is (str/includes? instruction b2-summary))
      (is (str/includes? instruction b3-summary)))

    (testing "original instruction appears AFTER the divider, intact"
      (let [parts (str/split instruction #"\n---\n")]
        (is (= 2 (count parts))
            "Exactly one divider separating prepend from original instruction")
        (is (str/includes? (second parts) original-instruction)
            "Original instruction preserved verbatim after divider")))))

;; =============================================================================
;; RED #2 — Structural fresh-mint branch
;; =============================================================================

(deftest structural-fresh-mint-branch
  (let [b1-id (random-uuid)
        payload {:structural {:assigned-tree-id (random-uuid)
                              :confidence 0.0
                              :was-fresh-mint? true
                              :reasoning "Top candidate did not pass confidence threshold; minting fresh task class."
                              :top-candidates []
                              :rerank-fallback? false}
                 :behavioral {:behaviors [(mk-behavioral-entry b1-id 0.9 "Analysis fits.")]
                              :rerank-fallback? false}}
        node (mk-node "Task: do something OOD" payload)
        stub-bodies {b1-id {:summary "Analysis reasons over already-extracted items..."
                            :capabilities ["x"] :strengths [] :weaknesses []
                            :representative-uses ["x"] :avoid-when ["x"]
                            :version 1 :consolidated-from-event-count 1}}
        result (with-redefs [ontology/get-description
                             (fn [_ _ id] (get stub-bodies id))]
                 (tp/apply-r05-classifier-context node {}))
        instruction (:instruction result)]

    (testing "structural section explicitly says no high-confidence match (principle-shaped)"
      (is (re-find #"### Structural pattern" instruction))
      (is (re-find #"(?i)no high-confidence structural match" instruction))
      (is (re-find #"(?i)design at the abstract pattern level" instruction)))

    (testing "no fitness-score number is shown when fresh-mint (avoids implying confidence)"
      (let [structural-section (-> instruction
                                   (str/split #"### Behavioral")
                                   first)]
        (is (not (re-find #"confidence:\s*0\.00" structural-section))
            "Don't print confidence: 0.00 on a fresh-mint structural")))

    (testing "behavioral section still fires (independent axis)"
      (is (re-find #"(?i)Behavioral suggestion" instruction)))))

;; =============================================================================
;; RED #3 — Behavioral fresh-mint marker at top-1
;; =============================================================================

(deftest behavioral-fresh-mint-marker-at-top-1
  (let [structural-target (random-uuid)
        b-mint-id (random-uuid)
        payload {:structural {:assigned-tree-id structural-target
                              :confidence 0.92
                              :was-fresh-mint? false
                              :reasoning "Top-1 high-confidence."
                              :top-candidates [(mk-structural-candidate
                                                 structural-target
                                                 "Top-1 high-confidence."
                                                 "ChunkedExtraction pattern..."
                                                 0.92)]
                              :rerank-fallback? false}
                 :behavioral {:behaviors [(mk-behavioral-entry
                                            b-mint-id 0.0
                                            "No candidate above threshold; minting fresh"
                                            :reranker true)]
                              :rerank-fallback? false}}
        node (mk-node "Task: novel" payload)
        result (tp/apply-r05-classifier-context node {})
        instruction (:instruction result)]

    (testing "behavioral section says no candidate above threshold + suggests mint-behavior! (principle-shaped)"
      (is (re-find #"(?i)no candidate above threshold" instruction)
          "Prepend explicitly signals the fresh-mint condition")
      (is (re-find #"mint-behavior!" instruction)
          "Explicitly references the minting affordance"))

    (testing "no get-description lookup attempted for fresh-mint behavioral marker (avoids needless reads on a not-yet-minted concept)"
      ;; The load-bearing invariant: we don't try to fetch a body for a
      ;; behavioral fresh-mint marker because there IS no body yet. We
      ;; DO fetch tree-class bodies for the structural candidate (always
      ;; — that's the corpus-injection mechanism). Track per-call args
      ;; to enforce the actual contract instead of total-call-count.
      (let [behavioral-fetch-attempts (atom 0)]
        (with-redefs [ontology/get-description
                      (fn [_ctx _granularity target-id]
                        (when (= target-id b-mint-id)
                          (swap! behavioral-fetch-attempts inc))
                        nil)]
          (tp/apply-r05-classifier-context node {}))
        (is (zero? @behavioral-fetch-attempts)
            "get-description not called with the fresh-mint behavioral marker's id — there's no body to fetch yet")))

    ;; C-Loop-2 P2 — verbatim prepend phrasing per spec acceptance criterion.
    ;; The fresh-mint branch must teach the model the body-map shape it
    ;; should pass + the persistence guarantee (retrievable on subsequent
    ;; classify-behaviors calls). Without these specifics, the model often
    ;; emits malformed mint calls or doesn't trust the affordance.
    (testing "C-Loop-2 P2: prepend includes the body-map signature so the model knows what to pass"
      (is (re-find #":capabilities" instruction)
          "Prepend tells the model the body needs :capabilities")
      (is (re-find #":strengths" instruction)
          "Prepend tells the model the body needs :strengths")
      (is (re-find #":weaknesses" instruction)
          "Prepend tells the model the body needs :weaknesses")
      (is (re-find #":representative-uses" instruction)
          "Prepend tells the model the body needs :representative-uses")
      (is (re-find #":summary" instruction)
          "Prepend tells the model the body needs :summary"))

    (testing "C-Loop-2 P2: prepend mentions the :parent option for the affordance"
      (is (re-find #":parent" instruction)
          "Prepend mentions :parent kwarg so the model knows top-level vs child mints"))

    (testing "C-Loop-2 P2: prepend explains the persistence promise — retrievable next time"
      (is (re-find #"(?i)retriev.{0,40}classify-behaviors|subsequent.{0,40}classify-behaviors|next.{0,40}classify-behaviors" instruction)
          "Prepend explicitly tells the model the minted behavior will surface on subsequent classify-behaviors calls — that's the load-bearing persistence guarantee"))))

;; =============================================================================
;; RED #4 — Rerank-fallback caution annotation
;; =============================================================================

(deftest rerank-fallback-caution
  (let [structural-target (random-uuid)
        b1-id (random-uuid)
        ;; Both confidences set ABOVE min-display-confidence (0.6) so the
        ;; candidates surface in the prepend — only then does the per-
        ;; candidate "reranker fell back" annotation render. Below the
        ;; floor the candidate is filtered out and the section falls
        ;; into the "no high-confidence match" branch which doesn't
        ;; carry the fallback annotation.
        payload {:structural {:assigned-tree-id structural-target
                              :confidence 0.7
                              :was-fresh-mint? false
                              :reasoning "Pure-ColBERT ordering; reranker fell back."
                              :top-candidates [(mk-structural-candidate
                                                 structural-target
                                                 "Pure-ColBERT ordering."
                                                 "ChunkedExtraction..."
                                                 0.7
                                                 :colbert-fallback)]
                              :rerank-fallback? true}
                 :behavioral {:behaviors [(mk-behavioral-entry b1-id 0.7
                                                               "Analysis fits."
                                                               :colbert-fallback false)]
                              :rerank-fallback? true}}
        node (mk-node "Task: x" payload)
        stub-bodies {b1-id {:summary "Analysis reasons over extracted items..."
                            :capabilities ["x"] :strengths [] :weaknesses []
                            :representative-uses ["x"] :avoid-when ["x"]
                            :version 1 :consolidated-from-event-count 1}}
        result (with-redefs [ontology/get-description
                             (fn [_ _ id] (get stub-bodies id))]
                 (tp/apply-r05-classifier-context node {}))
        instruction (:instruction result)
        structural-section (-> instruction
                               (str/split #"### Behavioral")
                               first)
        behavioral-section (-> instruction
                               (str/split #"### Behavioral")
                               second)]

    (testing "structural section carries a caution annotation"
      (is (re-find #"(?i)reranker fell back" structural-section)
          "Structural caution annotation present"))

    (testing "behavioral section carries a caution annotation"
      (is (re-find #"(?i)reranker fell back" behavioral-section)
          "Behavioral caution annotation present"))))

;; =============================================================================
;; RED #5 — No :r05-classifier → identity (legacy/manual-context contract)
;; =============================================================================

(deftest no-op-when-r05-classifier-absent
  (testing "node with no :context → unchanged"
    (let [node {:id (random-uuid) :type :repl-researcher
                :instruction "do the thing"}
          result (tp/apply-r05-classifier-context node {})]
      (is (= node result)
          "Identity when no :context at all")))

  (testing "node with :context but no :r05-classifier → unchanged"
    (let [node {:id (random-uuid) :type :repl-researcher
                :instruction "do the thing"
                :context {:problem-type "problem:Classification"}}
          result (tp/apply-r05-classifier-context node {})]
      (is (= node result)
          "Identity when :context lacks :r05-classifier (manual-context path preserved)"))))

;; =============================================================================
;; RED #6 — Behavioral cap at 5: when more than 5 above-floor candidates are
;;            returned, only the first 5 surface
;; =============================================================================
;;
;; The behavioral-cap was raised from 3 → 5 (aligns with classify-behaviors'
;; :top-n 5). Test sends 7 above-floor candidates; expects 5 surface, 2 cut.
;; Stub summaries are deliberately generic ("Generic summary.") so derive-
;; seed-name returns nil and the header falls back to "Behavioral suggestion"
;; — that's what the suggestion-count regex counts.

(deftest behavioral-cap-at-5
  (let [structural-target (random-uuid)
        b-ids (vec (repeatedly 7 random-uuid))
        payload {:structural {:assigned-tree-id structural-target
                              :confidence 0.92
                              :was-fresh-mint? false
                              :reasoning "ok"
                              :top-candidates [(mk-structural-candidate
                                                 structural-target "ok" "..." 0.92)]
                              :rerank-fallback? false}
                 :behavioral {:behaviors (mapv (fn [id]
                                                 (mk-behavioral-entry
                                                   id 0.9 (str "fits because " id)))
                                               b-ids)
                              :rerank-fallback? false}}
        node (mk-node "T" payload)
        stub-bodies (into {} (map (fn [id] [id {:summary "Generic summary."
                                                :capabilities ["x"] :strengths [] :weaknesses []
                                                :representative-uses ["x"] :avoid-when ["x"]
                                                :version 1 :consolidated-from-event-count 1}])) b-ids)
        result (with-redefs [ontology/get-description
                             (fn [_ _ id] (get stub-bodies id))]
                 (tp/apply-r05-classifier-context node {}))
        instruction (:instruction result)
        ;; Count "Behavioral suggestion" markers — generic summaries don't
        ;; resolve to a seed name so all entries get the generic header.
        suggestion-count (count (re-seq #"(?m)^\d+\. Behavioral suggestion" instruction))]

    (testing "exactly 5 behavioral suggestions surface (caps at 5 even when 7 returned)"
      (is (= 5 suggestion-count)
          (str "Expected 5 capped suggestions; got " suggestion-count)))

    (testing "section header reflects the cap"
      (is (re-find #"Behavioral competencies \(top 5 from corpus" instruction)))

    (testing "the candidates that did NOT make the cut are absent"
      (let [kept-ids (take 5 b-ids)
            cut-ids (drop 5 b-ids)]
        (doseq [id kept-ids]
          (is (str/includes? instruction (str id))
              (str "Top-5 candidate " id " surfaced")))
        (doseq [id cut-ids]
          (is (not (str/includes? instruction (str id)))
              (str "Cut candidate " id " NOT surfaced")))))))

;; =============================================================================
;; RED #7 — Wedge integration: stashes full payload on :context.:r05-classifier
;; =============================================================================

(deftest wedge-stashes-r05-classifier-payload
  (let [structural-tree-id (random-uuid)
        b1-id (random-uuid)
        b2-id (random-uuid)
        structural-result {:assigned-tree-id structural-tree-id
                           :confidence 0.92
                           :was-fresh-mint? false
                           :reasoning "Structural top-1"
                           :top-candidates [{:document-metadata {:target-id structural-tree-id}
                                             :content "ChunkedExtraction..."
                                             :fitness-score 0.92
                                             :reasoning "Structural top-1"
                                             :rerank-source :reranker}]
                           :rerank-fallback? false
                           :parent-tree-id nil}
        behavioral-result {:behaviors [{:behavior-id b1-id :confidence 0.92
                                        :was-fresh-mint? false :reasoning "B1"
                                        :rerank-source :reranker}
                                       {:behavior-id b2-id :confidence 0.85
                                        :was-fresh-mint? false :reasoning "B2"
                                        :rerank-source :reranker}]
                           :rerank-fallback? false}
        node {:id (random-uuid)
              :type :repl-researcher
              :name "test"
              :instruction "x"
              :reads []
              :writes []
              :rlm {:auto-classify? true}}
        wedge-ctx {:sheet-id (random-uuid) :tick-id (random-uuid)}]

    (with-redefs [ontology/classify-task (constantly structural-result)
                  ontology/classify-behaviors (constantly behavioral-result)
                  ;; cp/process-command is not under test here; stub to a no-op
                  ai.obney.grain.command-processor-v2.interface/process-command
                  (constantly {:command-result/events []})]
      (let [result-node (tp/maybe-auto-classify-and-set-context node wedge-ctx)
            payload (get-in result-node [:context :r05-classifier])]

        (testing ":r05-classifier appears under :context"
          (is (some? payload)
              "Wedge stashed the R05 payload on :context"))

        (testing "structural envelope is preserved verbatim under :structural"
          (let [s (:structural payload)]
            (is (= structural-tree-id (:assigned-tree-id s)))
            (is (= 0.92 (:confidence s)))
            (is (false? (:was-fresh-mint? s)))
            (is (= "Structural top-1" (:reasoning s)))
            (is (= 1 (count (:top-candidates s))))
            (is (= "ChunkedExtraction..." (:content (first (:top-candidates s)))))))

        (testing "behavioral envelope is preserved verbatim under :behavioral"
          (let [b (:behavioral payload)]
            (is (= 2 (count (:behaviors b))))
            (is (= b1-id (-> b :behaviors first :behavior-id)))
            (is (= 0.92 (-> b :behaviors first :confidence)))
            (is (false? (:rerank-fallback? b)))))

        (testing ":self-learning? is NOT on :context (no legacy-path payload)"
          (is (not (contains? (:context result-node) :self-learning?))
              ":self-learning? removed (R-Inject explicitly drops it)"))

        (testing ":tree-id stays accessible at top of :context for downstream consumers"
          (is (= structural-tree-id (get-in result-node [:context :tree-id]))
              ":tree-id preserved at the top level for any non-R-Inject reader"))))))

;; =============================================================================
;; C-Loop-1 — apply-r05-classifier-context reads tree bodies under :tree-class
;; =============================================================================
;;
;; The Living Description loop: consolidator writes per-tree-class bodies
;; (the substrate the prepend reads). Bootstrap seeds are also recorded
;; under :tree-class (via seed-all!), so first-time runs see seed content
;; via the same read path. The fetch-tree-body call site MUST request
;; :tree-class scope so the Living Description loop's updates surface
;; in the next R-Inject run's prepend.

(deftest prepend-fetches-tree-body-under-tree-class-scope
  (testing "C-Loop-1: apply-r05-classifier-context's fetch-tree-body calls get-description with :tree-class granularity"
    (let [structural-target (random-uuid)
          captured-calls (atom [])
          tree-class-body {:summary "tree-class-bound body"
                           :capabilities ["x"] :strengths [] :weaknesses []
                           :representative-uses ["x"] :avoid-when ["x"]
                           :version 2 :consolidated-from-event-count 5}
          payload {:structural {:assigned-tree-id structural-target
                                :confidence 0.92
                                :was-fresh-mint? false
                                :reasoning "Top-1 match"
                                :top-candidates [(mk-structural-candidate
                                                   structural-target
                                                   "Top-1 match"
                                                   "summary content"
                                                   0.92)]
                                :rerank-fallback? false}
                   :behavioral {:behaviors []
                                :rerank-fallback? false}}
          node (mk-node "Test instruction" payload)]
      (with-redefs [ontology/get-description
                    (fn [_ctx granularity target-id]
                      (swap! captured-calls conj [granularity target-id])
                      ;; Return body when asked for :tree-class so the
                      ;; happy-path rendering proceeds; nil for any other
                      ;; granularity so the test fails loudly if the
                      ;; helper falls back to :tree-fingerprint.
                      (when (= granularity :tree-class)
                        tree-class-body))]
        (tp/apply-r05-classifier-context node {}))
      (is (some (fn [[g _]] (= g :tree-class)) @captured-calls)
          "fetch-tree-body should call get-description with :tree-class granularity")
      (is (not-any? (fn [[g _]] (= g :tree-fingerprint)) @captured-calls)
          "fetch-tree-body should NOT call get-description with :tree-fingerprint scope — Option C migrates the read path to :tree-class"))))

;; =============================================================================
;; T2-Hardening-B — body fetch coerces string-form target-id to UUID
;;
;; The classifier's :top-candidates carry :document-metadata.:target-id as a
;; STRING (ColBERT bridges metadata through JSON, so a UUID seed comes back
;; as "5a08300e-10e3-305a-80c1-17eafea15ff7"). The descriptions read-model
;; keys bodies under the literal java.util.UUID. Without coercion at the
;; fetch site, every prepend silently degrades to summary-only because
;; format-seed-body receives nil. The fix is a UUID coercion in
;; fetch-tree-body. These tests pin the behavior across the matrix of
;; input shapes the classifier actually produces.
;; =============================================================================

(def ^:private rich-body-with-strengths
  "Body whose render exercises every section format-seed-body emits — most
   importantly the 'Strengths (proven traits ...)' header that proves the
   body fetch returned non-nil. Mirrors the shape of a real seed body."
  {:summary "Test pattern fits scheduling-style staged pipelines."
   :capabilities ["enumerate constraints → propose → conflict-check"]
   :strengths [{:trait "separate constraint enumeration from proposal"
                :good-when "the problem has hard constraints AND soft preferences"
                :recommended-pattern "[:sequence [:llm {:writes [:hard]}] [:llm {:writes [:proposal]}] [:code {:writes [:conflicts]}] [:final {:keys [:proposal :conflicts]}]]"
                :confidence 1.0
                :evidence-count 1}]
   :weaknesses [{:trait "single-pass llm scheduling silently violates constraints"
                 :avoid-when "more than 3 hard constraints in one llm call"
                 :recommended-alternative "always separate the constraint-check into its own :code stage"
                 :confidence 1.0
                 :evidence-count 1}]
   :representative-uses ["weekly on-call rotation"]
   :avoid-when ["only 1-2 hard constraints"]
   :version 1
   :consolidated-from-event-count 0})

(deftest fetch-tree-body-coerces-string-form-target-id-to-uuid
  (testing "T2-Hardening-B: candidate's :target-id may arrive as a stringified UUID (JSON roundtrip from ColBERT). The body fetch must coerce to UUID so the read-model lookup hits."
    (let [seed-uuid (random-uuid)
          ;; Classifier payload as the pipeline ACTUALLY receives it — the
          ;; candidate's :document-metadata.:target-id is the stringified
          ;; form ColBERT returns through its JSON bridge.
          string-target-id (str seed-uuid)
          payload {:structural {:assigned-tree-id seed-uuid
                                :confidence 1.0
                                :was-fresh-mint? false
                                :reasoning "Top-1 stringified-UUID match"
                                :top-candidates [(mk-structural-candidate
                                                   string-target-id
                                                   "Top-1 stringified-UUID match"
                                                   "Summary content"
                                                   1.0)]
                                :rerank-fallback? false}
                   :behavioral {:behaviors []
                                :rerank-fallback? false}}
          node (mk-node "Test instruction" payload)
          ;; The read-model stub mirrors production semantics: bodies are
          ;; keyed by UUID. A string-form target-id passed directly returns
          ;; nil; only the UUID-form returns the body. After the coercion
          ;; fix, the fetch site reaches the body even when classifier
          ;; surfaces the stringified form.
          result (with-redefs [ontology/get-description
                               (fn [_ctx granularity target-id]
                                 (when (and (= granularity :tree-class)
                                            (uuid? target-id)
                                            (= target-id seed-uuid))
                                   rich-body-with-strengths))]
                   (tp/apply-r05-classifier-context node {}))
          instruction (:instruction result)]
      (is (re-find #"(?m)^Strengths \(proven" instruction)
          "After coercion, the prepend renders the seed's :strengths section so the model sees the worked-example DSL"))))

(deftest fetch-tree-body-passes-uuid-input-through-unchanged
  (testing "T2-Hardening-B: when classifier surfaces a UUID-typed target-id directly (e.g., in a unit test or future code path), coercion is idempotent and the body fetch still hits."
    (let [seed-uuid (random-uuid)
          payload {:structural {:assigned-tree-id seed-uuid
                                :confidence 1.0
                                :was-fresh-mint? false
                                :reasoning "UUID-direct top-1"
                                :top-candidates [(mk-structural-candidate
                                                   seed-uuid
                                                   "UUID-direct top-1"
                                                   "Summary"
                                                   1.0)]
                                :rerank-fallback? false}
                   :behavioral {:behaviors []
                                :rerank-fallback? false}}
          node (mk-node "Test instruction" payload)
          result (with-redefs [ontology/get-description
                               (fn [_ctx granularity target-id]
                                 (when (and (= granularity :tree-class)
                                            (uuid? target-id)
                                            (= target-id seed-uuid))
                                   rich-body-with-strengths))]
                   (tp/apply-r05-classifier-context node {}))
          instruction (:instruction result)]
      (is (re-find #"(?m)^Strengths \(proven" instruction)
          "UUID input still reaches the body — coercion is idempotent"))))

(deftest fetch-tree-body-handles-no-match-without-crash
  (testing "T2-Hardening-B: when the classifier surfaces a target-id whose body is genuinely absent (fresh-mint or stale lookup), the prepend renders without the strengths header and without throwing."
    (let [absent-target (str (random-uuid))
          payload {:structural {:assigned-tree-id (random-uuid)
                                :confidence 0.7
                                :was-fresh-mint? false
                                :reasoning "Top-1 with no body in read-model"
                                :top-candidates [(mk-structural-candidate
                                                   absent-target
                                                   "Top-1 with no body in read-model"
                                                   "Summary content"
                                                   0.7)]
                                :rerank-fallback? false}
                   :behavioral {:behaviors []
                                :rerank-fallback? false}}
          node (mk-node "Test instruction" payload)
          result (with-redefs [ontology/get-description
                               (fn [_ctx _granularity _target-id]
                                 nil)]
                   (tp/apply-r05-classifier-context node {}))
          instruction (:instruction result)]
      (is (not (re-find #"(?m)^Strengths \(proven" instruction))
          "No body found → no strengths section rendered")
      (is (re-find #"Pattern guidance" instruction)
          "Summary-driven 'Pattern guidance' section still renders from the candidate's :content"))))
