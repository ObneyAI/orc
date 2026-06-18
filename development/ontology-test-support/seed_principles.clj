(ns seed-principles
  "C-1 seed — hand-authored RLM tree-design principles, stored via the
   existing :ontology/record-tree-strength and :ontology/record-tree-weakness
   commands. Each principle is keyed by a stable task-class UUID so
   retrieval (via :context {:tree-id task-class-id :self-learning? true}
   on a repl-researcher node) finds it across sessions.

   Encoding maps the existing schema's domain-agnostic fields to RLM
   tree-design semantics:

     :domain-type        \"rlm-tree-design\"
     :context-conditions when the principle applies (task class, input
                         shape, observed symptom)
     :action-taken.type  named pattern URI suffix
     :action-taken.target the tree-DSL snippet (schema accepts :any)
     :action-taken.reason the rationale (why this pattern wins here)
     :expected-outcome   what success looks like
     :evidence-trace-ids tick-ids of supporting failure/success runs

   The format-rich-pattern renderer (extended in C-1 step 1) surfaces
   all four of (:context-conditions, :action-taken.target, :reason,
   :expected-outcome) in the prompt the model sees at iteration 1.

   Run from REPL:
     (require '[seed-principles :as s])
     (s/seed-all! ctx)   ;; emits all principles into the event store
     ;; OR
     (s/seed-one! ctx s/bounded-map-each-for-chunked-extraction)"
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.ontology.core.commands]))

;; =============================================================================
;; Task-class UUIDs
;; =============================================================================
;;
;; Each existing benchmark task class gets one stable UUID. C-2 will replace
;; manual UUID assignment with a classifier; until then these are the keys
;; the user puts in :context {:tree-id ...} on the repl-researcher node.

(def document-analysis-task-class-id
  "Document Analysis — extraction on a large RFP. Outputs:
   :summary, :key-dates, :entities."
  #uuid "00000000-c1c1-4001-b001-d0c0a0a0a0a1")

(def risk-analysis-task-class-id
  "Risk Analysis — identify and categorize risk factors. Outputs:
   :risk-matrix, :executive-summary."
  #uuid "00000000-c1c1-4001-b002-d0c0a0a0a0a2")

(def contract-comparison-task-class-id
  "Contract Comparison — diff key terms between two contracts. Outputs:
   :key-differences, :similarities, :recommendation."
  #uuid "00000000-c1c1-4001-b003-d0c0a0a0a0a3")

(def legal-issue-detection-task-class-id
  "Legal Issue Detection — flag legal concerns in a document. Outputs:
   :issues, :severity-summary."
  #uuid "00000000-c1c1-4001-b004-d0c0a0a0a0a4")

(def chunked-extraction-task-class-id
  "Chunked Extraction (general) — any task that extracts structured
   information from a large document via chunk-then-map-each. Cross-cuts
   document-analysis, risk-analysis, contract-comparison, and
   legal-issue-detection — it's a *structural* task class, not a
   *domain* task class.

   For C-1's live-verify we use this rather than a specific domain so
   the principle has clear structural impact regardless of which
   document the user is analyzing."
  #uuid "00000000-c1c1-4001-b005-d0c0a0a0a0a5")

;; =============================================================================
;; Hand-authored principles
;; =============================================================================

(def bounded-map-each-for-chunked-extraction
  "Strength principle — when extracting from a large chunked document,
   bound :max-concurrency on the :map-each. Otherwise sub-LLM rate
   limits exhaust on unbounded parallelism."
  {:command/name :ontology/record-tree-strength
   :tree-id chunked-extraction-task-class-id
   :pattern-uri "success:BoundedMapEachOnChunkedExtraction"
   :confidence 1.0
   :evidence-trace-ids [#uuid "00000000-c1c1-e1de-0001-000000000001"]
   :avg-score 0.95
   :domain-type "rlm-tree-design"
   :context-conditions {:task-class :chunked-extraction
                        :input-shape :large-document
                        :symptom :rate-limit-risk-on-unbounded-parallelism}
   :action-taken {:type "BoundedMapEach"
                  :target (str "[:map-each {:from :chunks :as :chunk "
                               ":into :results :max-concurrency 3} "
                               "[:llm {... :reads [:chunk] :writes [...]}]]")
                  :reason (str "Sub-LLM rate limits exhaust on unbounded "
                               "concurrency for documents with 6+ chunks; "
                               "bound to 3 for safe parallelism")}
   :expected-outcome (str "Successful per-chunk extraction without "
                          "rate-limit failures on documents with 6+ chunks")})

(def all-principles
  "All hand-authored principles. C-1 ships with the single bounded-map-each
   principle; future hand-authoring or C-3's automated extraction will add
   more entries here."
  [bounded-map-each-for-chunked-extraction])

;; =============================================================================
;; Seeding fns
;; =============================================================================

(defn seed-one!
  "Emit a single principle into the event store.

   The `principle` arg must be a complete record-tree-strength or
   record-tree-weakness command body (sans :command/id and
   :command/timestamp, which this fn supplies).

   Returns the command-result map."
  [ctx principle]
  (cp/process-command
    (assoc ctx :command (merge {:command/id (random-uuid)
                                :command/timestamp (time/now)}
                               principle))))

(defn seed-all!
  "Seed every hand-authored principle. Returns a vec of command-result maps."
  [ctx]
  (mapv (fn [p] (seed-one! ctx p)) all-principles))

(comment
  ;; Smoke test from REPL — see development/src/c1_pipeline_probe.clj for a
  ;; full end-to-end probe that exercises seed → retrieve → format.
  (require '[seed-principles :as s]))
