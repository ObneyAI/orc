(ns seed-descriptions
  "Thin shim over `ai.obney.orc.ontology.interface/baseline-seeds`. Kept
   for backward compatibility with development-only tests and tooling
   that reference the historical named seed vars (e.g.,
   `etl-pipeline-tree-seed`, `scheduling-tree-seed`, `research-behavior-id`).

   The SOURCE OF TRUTH for seed bodies is now the ontology component's
   shipped EDN resources at `components/ontology/resources/seeds/`. The
   public consumer API is `(ai.obney.orc.ontology.interface/seed-baseline-corpus! ctx)`.

   This namespace re-exports the seed bodies + identifiers loaded from
   the shipped EDN. To regenerate the EDN from these definitions, run
   `components/ontology/scripts/regen-seeds.clj` — see the script for
   details. The shim itself doesn't author seed bodies; it just provides
   the historical access pattern for in-tree dev tests."
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.commands]
            [ai.obney.orc.orc-service.core.rlm-dsl :as rlm-dsl]
            [seed-principles :as principles]))

;; =============================================================================
;; UUID derivation (same logic as the pre-shim file — drives stable IDs)
;; =============================================================================

(defn- ^java.util.UUID stable-uuid-from
  "Derive a stable UUID from a deterministic seed string. Mirrors the
   classifier's coerce-to-uuid idiom — preserves identity across JVM
   restarts and ColBERT JSON round-trips."
  [^String s]
  (java.util.UUID/nameUUIDFromBytes (.getBytes s "UTF-8")))

;; =============================================================================
;; Tree-fingerprint string constants
;; =============================================================================

(def chunked-extraction-fp     "seed:tree:ChunkedExtraction")
(def sequential-pipeline-fp    "seed:tree:SequentialPipeline")
(def parallel-independent-fp   "seed:tree:ParallelIndependent")
(def validation-loop-fp        "seed:tree:ValidationLoop")
(def fallback-recovery-fp      "seed:tree:FallbackRecovery")
(def map-reduce-fp             "seed:tree:MapReduce")
(def research-then-synth-fp    "seed:tree:ResearchThenSynthesize")

;; =============================================================================
;; Task-class UUIDs — re-exports from C-1's seed_principles.clj + locally-
;; derived ones for the R02 children
;; =============================================================================

(def document-analysis-task-class-id        principles/document-analysis-task-class-id)
(def risk-analysis-task-class-id            principles/risk-analysis-task-class-id)
(def contract-comparison-task-class-id      principles/contract-comparison-task-class-id)
(def legal-issue-detection-task-class-id    principles/legal-issue-detection-task-class-id)
(def chunked-extraction-task-class-id       principles/chunked-extraction-task-class-id)

(def etl-pipeline-task-class-id
  (stable-uuid-from "seed:tree-class:etl-pipeline"))
(def iterative-refinement-task-class-id
  (stable-uuid-from "seed:tree-class:iterative-refinement"))
(def scheduling-task-class-id
  (stable-uuid-from "seed:tree-class:scheduling"))
(def producer-validator-task-class-id
  (stable-uuid-from "seed:tree-class:producer-validator"))
(def draft-critique-task-class-id
  (stable-uuid-from "seed:tree-class:draft-critique"))
(def primary-backup-task-class-id
  (stable-uuid-from "seed:tree-class:primary-backup"))
(def model-cascade-task-class-id
  (stable-uuid-from "seed:tree-class:model-cascade"))
(def parallel-sum-task-class-id
  (stable-uuid-from "seed:tree-class:parallel-sum"))
(def parallel-classify-aggregate-task-class-id
  (stable-uuid-from "seed:tree-class:parallel-classify-aggregate"))
(def briefing-generation-task-class-id
  (stable-uuid-from "seed:tree-class:briefing-generation"))
(def comparative-summary-task-class-id
  (stable-uuid-from "seed:tree-class:comparative-summary"))

;; =============================================================================
;; Behavior subtree UUIDs
;; =============================================================================

(def research-behavior-id      (stable-uuid-from "seed:behavior:research"))
(def extraction-behavior-id    (stable-uuid-from "seed:behavior:extraction"))
(def analysis-behavior-id      (stable-uuid-from "seed:behavior:analysis"))
(def synthesis-behavior-id     (stable-uuid-from "seed:behavior:synthesis"))
(def ideation-behavior-id      (stable-uuid-from "seed:behavior:ideation"))
(def design-behavior-id        (stable-uuid-from "seed:behavior:design"))
(def critique-behavior-id      (stable-uuid-from "seed:behavior:critique"))
(def validation-behavior-id    (stable-uuid-from "seed:behavior:validation"))
(def code-building-behavior-id (stable-uuid-from "seed:behavior:code-building"))
(def transformation-behavior-id (stable-uuid-from "seed:behavior:transformation"))
(def classification-behavior-id (stable-uuid-from "seed:behavior:classification"))
(def investigation-behavior-id (stable-uuid-from "seed:behavior:investigation"))

;; =============================================================================
;; Helper predicates (kept here — they depend on rlm-dsl which is an
;; orc-service concern; living in the dev namespace avoids any
;; ontology→orc-service component dependency)
;; =============================================================================

(defn principle-shaped?
  "True if a strength/weakness entry has the required principle fields."
  [entry]
  (and (map? entry)
       (string? (:trait entry))
       (or (string? (:good-when entry)) (string? (:avoid-when entry)))
       (or (string? (:recommended-pattern entry))
           (string? (:recommended-alternative entry)))
       (number? (:confidence entry))
       (integer? (:evidence-count entry))))

(defn description-body-well-formed?
  "True if a description body has the required top-level fields."
  [body]
  (and (map? body)
       (vector? (:capabilities body))
       (vector? (:strengths body))
       (vector? (:weaknesses body))
       (every? principle-shaped? (concat (:strengths body) (:weaknesses body)))
       (string? (:summary body))
       (integer? (:version body))))

(defn- replace-inline-fn-forms
  "Walk a parsed tree-DSL form; replace any '(fn ...)' list with a dummy
   fn value so downstream transform validation doesn't trip on the
   list-vs-fn distinction. Mirrors SCI-eval behavior in production."
  [form]
  (cond
    (and (seq? form) (= 'fn (first form)))
    (constantly nil)

    (map? form)
    (into {} (map (fn [[k v]] [k (replace-inline-fn-forms v)])) form)

    (sequential? form)
    (into (empty form) (map replace-inline-fn-forms) form)

    :else form))

(defn snippet-validates?
  "True if a string-valued tree-DSL snippet parses + transforms via
   `rlm-dsl/rlm-dsl->orc-dsl` without throwing. Inline `(fn ...)` forms
   inside `:code` nodes are pre-resolved to a placeholder fn value."
  [snippet-str]
  (try
    (let [form (read-string snippet-str)
          resolved (replace-inline-fn-forms form)]
      (rlm-dsl/rlm-dsl->orc-dsl resolved)
      true)
    (catch Exception _ false)))

(defn- now-str [] (str (time/now)))

;; =============================================================================
;; Seed vectors — loaded from the shipped ontology EDN resources.
;; baseline-seeds returns a fresh read on each call; we resolve it once
;; at namespace load so the named-seed vars below stay stable.
;; =============================================================================

(def ^:private baseline (ontology/baseline-seeds))

(def all-node-type-seeds
  "10 node-type seeds loaded from `components/ontology/resources/seeds/node-types.edn`."
  (:node-types baseline))

(def all-tree-fingerprint-seeds
  "23 structural tree-class seeds loaded from `components/ontology/resources/seeds/tree-classes.edn`.
   Includes both top-level patterns (ChunkedExtraction, MapReduce, etc.) and
   R02 children (etl-pipeline, scheduling, etc.). Each is dual-emitted to
   :tree-fingerprint AND :tree-class scopes by `seed-baseline-corpus!`."
  (:tree-classes baseline))

(def all-behavioral-subtree-seeds
  "12 behavioral subtree seeds (Research / Extraction / Analysis / Synthesis /
   Ideation / Design / Critique / Validation / Code-building / Transformation
   / Classification / Investigation) loaded from
   `components/ontology/resources/seeds/behavioral-subtrees.edn`."
  (:behavioral-subtrees baseline))

;; =============================================================================
;; Named seed vars — historical names resolved by target-id lookup against
;; the loaded vectors. New seeds need named bindings added here ONLY IF
;; dev tests reference them by name (the public API is to walk
;; baseline-seeds directly).
;; =============================================================================

(defn- find-seed [seeds target-id]
  (some #(when (= target-id (:target-id %)) %) seeds))

;; Node-type seeds — referenced by keyword target-id (e.g., :llm) in
;; existing tests. all-node-type-seeds already preserves their order.
(def llm-node-type-seed           (find-seed all-node-type-seeds :llm))
(def code-node-type-seed          (find-seed all-node-type-seeds :code))
(def map-each-node-type-seed      (find-seed all-node-type-seeds :map-each))
(def parallel-node-type-seed      (find-seed all-node-type-seeds :parallel))
(def sequence-node-type-seed      (find-seed all-node-type-seeds :sequence))
(def fallback-node-type-seed      (find-seed all-node-type-seeds :fallback))
(def condition-node-type-seed     (find-seed all-node-type-seeds :condition))
(def chunk-document-node-type-seed (find-seed all-node-type-seeds :chunk-document))
(def aggregate-node-type-seed     (find-seed all-node-type-seeds :aggregate))
(def final-node-type-seed         (find-seed all-node-type-seeds :final))

;; Tree-class seeds — top-level benchmark tasks + generic patterns
(def document-analysis-tree-seed
  (find-seed all-tree-fingerprint-seeds document-analysis-task-class-id))
(def risk-analysis-tree-seed
  (find-seed all-tree-fingerprint-seeds risk-analysis-task-class-id))
(def contract-comparison-tree-seed
  (find-seed all-tree-fingerprint-seeds contract-comparison-task-class-id))
(def legal-issue-detection-tree-seed
  (find-seed all-tree-fingerprint-seeds legal-issue-detection-task-class-id))
(def chunked-extraction-tree-seed
  (find-seed all-tree-fingerprint-seeds chunked-extraction-task-class-id))
(def chunked-extraction-pattern-seed
  (find-seed all-tree-fingerprint-seeds chunked-extraction-fp))
(def sequential-pipeline-pattern-seed
  (find-seed all-tree-fingerprint-seeds sequential-pipeline-fp))
(def parallel-independent-pattern-seed
  (find-seed all-tree-fingerprint-seeds parallel-independent-fp))
(def validation-loop-pattern-seed
  (find-seed all-tree-fingerprint-seeds validation-loop-fp))
(def fallback-recovery-pattern-seed
  (find-seed all-tree-fingerprint-seeds fallback-recovery-fp))
(def map-reduce-pattern-seed
  (find-seed all-tree-fingerprint-seeds map-reduce-fp))
(def research-then-synthesize-pattern-seed
  (find-seed all-tree-fingerprint-seeds research-then-synth-fp))

;; R02 children — structural tree-classes nested under the flat patterns
(def etl-pipeline-tree-seed
  (find-seed all-tree-fingerprint-seeds etl-pipeline-task-class-id))
(def iterative-refinement-tree-seed
  (find-seed all-tree-fingerprint-seeds iterative-refinement-task-class-id))
(def scheduling-tree-seed
  (find-seed all-tree-fingerprint-seeds scheduling-task-class-id))
(def producer-validator-tree-seed
  (find-seed all-tree-fingerprint-seeds producer-validator-task-class-id))
(def draft-critique-tree-seed
  (find-seed all-tree-fingerprint-seeds draft-critique-task-class-id))
(def primary-backup-tree-seed
  (find-seed all-tree-fingerprint-seeds primary-backup-task-class-id))
(def model-cascade-tree-seed
  (find-seed all-tree-fingerprint-seeds model-cascade-task-class-id))
(def parallel-sum-tree-seed
  (find-seed all-tree-fingerprint-seeds parallel-sum-task-class-id))
(def parallel-classify-aggregate-tree-seed
  (find-seed all-tree-fingerprint-seeds parallel-classify-aggregate-task-class-id))
(def briefing-generation-tree-seed
  (find-seed all-tree-fingerprint-seeds briefing-generation-task-class-id))
(def comparative-summary-tree-seed
  (find-seed all-tree-fingerprint-seeds comparative-summary-task-class-id))

;; Behavioral subtree seeds — top-level competencies
(def research-behavior-seed
  (find-seed all-behavioral-subtree-seeds research-behavior-id))
(def extraction-behavior-seed
  (find-seed all-behavioral-subtree-seeds extraction-behavior-id))
(def analysis-behavior-seed
  (find-seed all-behavioral-subtree-seeds analysis-behavior-id))
(def synthesis-behavior-seed
  (find-seed all-behavioral-subtree-seeds synthesis-behavior-id))
(def ideation-behavior-seed
  (find-seed all-behavioral-subtree-seeds ideation-behavior-id))
(def design-behavior-seed
  (find-seed all-behavioral-subtree-seeds design-behavior-id))
(def critique-behavior-seed
  (find-seed all-behavioral-subtree-seeds critique-behavior-id))
(def validation-behavior-seed
  (find-seed all-behavioral-subtree-seeds validation-behavior-id))
(def code-building-behavior-seed
  (find-seed all-behavioral-subtree-seeds code-building-behavior-id))
(def transformation-behavior-seed
  (find-seed all-behavioral-subtree-seeds transformation-behavior-id))
(def classification-behavior-seed
  (find-seed all-behavioral-subtree-seeds classification-behavior-id))
(def investigation-behavior-seed
  (find-seed all-behavioral-subtree-seeds investigation-behavior-id))

;; =============================================================================
;; Dispatch — thin wrappers over the ontology public API
;; =============================================================================

(defn seed-one!
  "Emit a single description seed via the appropriate command.

   Kept for backward compatibility with description-events-test which
   exercises per-granularity dispatch in isolation. New callers should
   prefer `(ontology/seed-baseline-corpus! ctx)` for the full corpus."
  [ctx granularity {:keys [target-id body]}]
  (let [cmd-name (case granularity
                   :node-type        :ontology/record-node-type-description
                   :node-instance    :ontology/record-node-instance-description
                   :tree-fingerprint :ontology/record-tree-description
                   :tree-class       :ontology/record-tree-class-description)]
    (cp/process-command
      (assoc ctx :command {:command/name cmd-name
                           :command/id (random-uuid)
                           :command/timestamp (time/now)
                           :target-id target-id
                           :body body}))))

(defn seed-all!
  "Backward-compatible alias for `(ontology/seed-baseline-corpus! ctx)`.

   Returns a vec of command-results — same shape as the historical
   `seed-all!` (10 node-type + 23 tree-fingerprint + 23 tree-class
   dual-emit + 12 behavioral-subtree = 68 dispatches)."
  [ctx]
  (ontology/seed-baseline-corpus! ctx))

(comment
  ;; Run from REPL after starting a context:
  (require '[seed-descriptions :as s] :reload)
  (count s/all-node-type-seeds)
  (count s/all-tree-fingerprint-seeds)
  (count s/all-behavioral-subtree-seeds)
  (:target-id s/scheduling-tree-seed))
