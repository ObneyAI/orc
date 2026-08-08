(ns ai.obney.orc.ontology.core.reranker
  "C-2b-2: intent-aware LLM reranker over ColBERT recall.

   Single-:llm-node ORC workflow that takes (query, intent, candidates)
   and returns a reordered top-N with per-candidate :reasoning +
   :fitness-score. Mirrors the consolidator's reflection-workflow shape
   (single LLM node + U11 structured output + :max-retries 3).

   The reranker is delta-only: it returns just (document-id, reasoning,
   fitness-score) triples. The full candidate (content, ColBERT score,
   document-metadata) is JOINED back in `search-descriptions` via
   :document-id."
  (:require [ai.obney.orc.ontology.interface.schemas :as ontology-schemas]
            [ai.obney.orc.orc-service.interface :as orc]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [malli.core :as m]
            [com.brunobonacci.mulog :as mu]))

;; =============================================================================
;; Prompt instruction
;; =============================================================================

(def ^:private reranker-instruction
  "You are ranking candidate descriptions by their fitness for a caller's intent.

INPUTS DESCRIBED
- query       — the natural-language query the caller wrote
- intent      — the caller's goal/context: what they are trying to build or decide
- candidates  — a JSON vector of candidate descriptions, each with
                  content (the description's summary text),
                  score (raw ColBERT similarity),
                  document-id (stable id you must echo back),
                  document-metadata {granularity, target-id, confidence, last-update},
                  avoid-when (OPTIONAL — a vector of judge-grounded DOMAIN
                    guards: contexts where this candidate is the WRONG choice
                    even if its summary/shape looks similar),
                  strengths (OPTIONAL — vector of {trait, good-when,
                    recommended-pattern}: when this candidate is the RIGHT fit),
                  weaknesses (OPTIONAL — vector of {trait, avoid-when,
                    recommended-alternative}).

YOUR JOB
Rank the candidates by how well they FIT THE INTENT, not by raw lexical
overlap with the query. Cross-reference each candidate's CONTENT
against what the caller is actually trying to accomplish. Weight DOMAIN /
subject-matter fit (what the task IS), not just structural shape (what the
task LOOKS like).

AVOID-WHEN IS A HARD RULE — NOT A HINT.
For each candidate that carries an avoid-when list, read it FIRST, before its
content/strengths. If ANY avoid-when entry describes what the task is actually
doing, DOWN-rank that candidate sharply (assign a low fitness_score) EVEN IF
its structural shape or summary fits well — a strong shape match does NOT
override a matching domain guard. When an avoid-when entry fires, your
reasoning MUST quote it and say the task matches it. Prefer a more general
candidate that has no firing guard over an over-specific one whose avoid-when
matches the task.

PRODUCE a JSON string of a vector, descending by fitness_score. Each
element is an object with EXACTLY these three keys:
  {\"document_id\":   \"<echo the candidate's document-id verbatim>\",
   \"reasoning\":     \"<concrete, actionable; references specific content>\",
   \"fitness_score\": <number in [0.0, 1.0]>}

Example shape:
  [{\"document_id\":\"a\",\"reasoning\":\"...\",\"fitness_score\":0.91},
   {\"document_id\":\"b\",\"reasoning\":\"...\",\"fitness_score\":0.42}]

The output MUST be a raw JSON string starting with `[` and ending with
`]`. No surrounding prose, no code fences, no leading/trailing
explanation.

SCORE DEFINITION
1.0 = perfect fit for the caller's intent.
0.0 = irrelevant to the intent.

REASONING DISCIPLINE (HARD RULE)
Your reasoning MUST be principle-shaped — concrete and actionable. It
must reference something specific in the candidate's content (a node
type, a structural pattern, a confidence trait, a recommended pattern)
that ties to the caller's intent.

DO NOT produce status-shaped reasoning. Forbidden shapes:
- 'looks ok' / 'seems fine' / 'unclear if relevant'
- 'could investigate' / 'might work' / 'further evaluation needed'
- 'matches the query' (vague) / 'general fit' (vague)
- restating the query or the candidate summary without explaining the FIT

Every reasoning entry must answer: 'Why does THIS candidate's specific
content advance the caller's stated intent?' If you cannot answer that
concretely, assign a low fitness_score and say WHAT is missing.

Return ALL candidates — including low-fitness ones, and ones that arrive
with only content, score and document-id — the caller may want the full
ranking. Do not drop any. The output vector MUST contain exactly one entry
per input candidate.")

;; =============================================================================
;; Workflow definition
;; =============================================================================

(def default-model
  "RR-2 (ADR 0020 decision 5): the reranker's default :model when the
   caller supplies none. Evidence-tested (not an arbitrary 'fast model'
   pick) — validated via repro to produce correct, function-calling-valid
   structured rankings on the real behavioral-subtree corpus. A flagship
   model was proven unnecessary for ranking quality, and a different
   fast-alternative model was proven insufficient to reduce latency on
   its own (see ADR 0020 / doc/reranker-resilience-grill-input.md §2).
   This default is about correctness/determinism/decoupling, not speed."
  "qwen/qwen3.5-flash-02-23")

(def ^:private candidate-schema
  [:map
   [:content :string]
   ;; ColBERT implementations may surface any JVM Number subtype. This is an
   ;; input contract, not a demand that retrieval coerce every score to Double.
   [:score number?]
   [:document-id :string]
   ;; RR-3 deliberately sends capped child candidates with only
   ;; content/score/document-id. Metadata is therefore optional at this
   ;; provider boundary even though full parent candidates retain it.
   [:document-metadata {:optional true}
    [:map
     [:granularity :keyword]
     [:target-id [:or :string :uuid]]
     [:confidence {:optional true} number?]
     [:last-update {:optional true} :string]]]
   [:avoid-when {:optional true} [:vector :string]]
   [:strengths {:optional true} [:vector ontology-schemas/principle-entry]]
   [:weaknesses {:optional true} [:vector ontology-schemas/principle-entry]]])

(defn- reranker-workflow-name
  "The workflow's sheet-identity is deterministic from its NAME
   (orc-service `build-workflow!` derives a v5-UUID sheet-id from the
   name alone, then content-hashes the definition to decide whether to
   rebuild in place). Keep the DEFAULT model's identity pinned to the
   original stable name so existing deployments/dashboards that know it
   as \"ontology-description-reranker\" keep resolving the same sheet.
   A caller-supplied override gets its OWN distinct name/identity —
   never reusing the default sheet's name with different content, which
   would otherwise thrash that sheet's content hash (clear + rebuild in
   place) every time a different model is requested against the same
   name."
  [model]
  (if (= model default-model)
    "ontology-description-reranker"
    (str "ontology-description-reranker--" model)))

(defn reranker-workflow
  "Build the single-:llm-node ORC workflow for the description reranker,
   pinning the 'rerank' node's :model to `model` (RR-2). Pure data — no
   I/O — so tests can assert on the resolved node without a real LLM
   call.

   Inputs (blackboard): :query, :intent, :candidates
   Output (one :writes slot): :reranked-json
     — a JSON string of the reranked list (parsed back to Clojure in
       `rerank!`). We use a JSON-string output rather than a native
       vector-of-maps because some LLM providers (including
       gemini-3-flash-preview) hang or fail when asked to produce
       deeply-nested structured output via U11 :output-schemas. A
       string output trivially passes structured-output validation;
       we own the parse + validate step downstream."
  [model]
  (orc/workflow (reranker-workflow-name model)
    (orc/blackboard
      {:query         :string
       :intent        :string
       :candidates    [:vector candidate-schema]
       :reranked-json :string})

    (orc/llm "rerank"
      :model model
      :instruction reranker-instruction
      :reads [:query :intent :candidates]
      :writes [:reranked-json]
      ;; Per-node override: use function-calling for structured output.
      ;; The project default is marker-parsing (see commit 2c00391 —
      ;; per-node :use-function-calling? overrides are the supported
      ;; escape hatch for nodes where marker-parsing doesn't fit).
      ;;
      ;; Empirically with gemini-3-flash-preview: a single-:writes
      ;; string output asking for a free-form JSON payload triggers
      ;; the LLM to skip the [[ ## reranked-json ## ]] marker and emit
      ;; bare JSON. llm's marker-parser then returns nil, the
      ;; executor's outputs-have-nil retry path exhausts, and the
      ;; workflow succeeds with nil outputs. Function-calling tools
      ;; avoid that brittleness — the model is structurally compelled
      ;; to call the submit_response tool with our shape.
      :options {:max-retries 3
                :retry-delay-ms [500 1500 3000]
                :use-function-calling? true})))

;; =============================================================================
;; Execution budget (RR-1 / ADR 0020)
;; =============================================================================

(def default-rerank-timeout-ms
  "Fixed, GENEROUS execution budget for one reranker call, replacing the
   generic 300000ms `orc/execute` default the classify->rerank path used to
   inherit (the cliff a live run tripped by ~2s at 302147ms).

   Sizing (from the measured evidence, not a guess):
     - worst controlled-repro completion: 10525 tokens (ARM-E)
     - worst LIVE observed throughput:    ~25 tok/s (the degraded run)
     => worst realistic wall time ~= 10525 / 25 = 421s

   900000ms (15 min) clears that by ~2.1x. The margin is deliberate: this is
   a BACKSTOP against a hung/pathologically-degraded call, not a latency
   control — a bare match to the worst observed case would re-create the
   same cliff one bad provider-hour later. Affordable because RR-1 also
   moves classify off the dispatch thread: a slow rerank now costs turn
   latency only.

   Override per-call with `:timeout-ms` on `rerank!`'s opts, or per-
   deployment with `:rerank-timeout-ms` on the context."
  900000)

(def ^:private max-timeout-retries
  "How many times a TIMED-OUT rerank call is retried (same model, same
   call) before the caller falls through to its fallback path. One retry:
   a timeout is transient infra, not epistemic uncertainty (ADR 0015's
   spirit), and the retry is cheap now that classify is off the blocking
   path. A genuine throw / nil / empty result is NOT retried here — that is
   the reranker failing to rank, and `apply-rerank`'s ColBERT fallback owns
   it (unchanged)."
  1)

(defn- resolve-timeout-ms
  "Per-call opt > per-deployment ctx knob > the fixed default."
  [ctx timeout-ms]
  (or timeout-ms (:rerank-timeout-ms ctx) default-rerank-timeout-ms))

(def ^:private timed-out-result
  "What `rerank!` returns when the call AND its one retry both timed out.

   An EMPTY vector, so every existing caller — all of which test the result
   with `(seq …)` / `(first …)` — behaves exactly as it does for a nil or
   empty reranker result today (fall back / stop descent). The
   `:rerank-timeout? true` metadata is the ADDITIVE signal: a caller that
   cares can ask `timed-out?` and record 'infra was slow' distinctly from
   'the reranker could not rank'. Metadata is invisible to value equality,
   so nothing downstream changes shape."
  (with-meta [] {:rerank-timeout? true}))

(defn timed-out?
  "True when a `rerank!` return value is the exhausted-timeout marker (the
   call timed out and so did its retry). False for every other result,
   including nil, a genuine empty result, and success."
  [rerank-result]
  (boolean (:rerank-timeout? (meta rerank-result))))

(defn- parse-reranked-json
  "Parse + canonicalize + validate the reranker's JSON payload from a
   SUCCESSFUL execution result. Extracted from `rerank!` unchanged when
   RR-1 gave that function its budget/retry/timeout concerns — pure over
   the result map, no execution semantics."
  [result]
  (let [raw-json (get-in result [:outputs :reranked-json])
        ;; The LLM may wrap its JSON in a code fence or include a
        ;; brief preamble. Extract the first [...] block.
        json-payload (when (string? raw-json)
                       (let [start (.indexOf raw-json "[")
                             end (.lastIndexOf raw-json "]")]
                         (when (and (>= start 0) (> end start))
                           (subs raw-json start (inc end)))))
        parsed (try
                 (when json-payload
                   (json/read-str json-payload :key-fn keyword))
                 (catch Throwable t
                   (mu/log ::rerank-parse-failed
                           :error (.getMessage t)
                           :raw-preview (when raw-json
                                          (subs raw-json 0
                                                (min 200 (count raw-json)))))
                   nil))
        ;; The LLM produces snake_case keys per the instruction
        ;; ({"document_id":..., "fitness_score":...}). Canonicalize
        ;; to the kebab-case schema and validate.
        canon (when (sequential? parsed)
                (mapv (fn [e]
                        (cond-> e
                          (:document_id e)   (-> (assoc :document-id (:document_id e))
                                                 (dissoc :document_id))
                          (:fitness_score e) (-> (assoc :fitness-score (:fitness_score e))
                                                 (dissoc :fitness_score))
                          ;; Keep only the canonical kebab-case keys
                          true               (select-keys [:document-id :reasoning :fitness-score])))
                      parsed))
        valid (when (sequential? canon)
                (filterv #(m/validate ontology-schemas/reranked-result %) canon))]
    (when (and (sequential? canon)
               (not= (count canon) (count (or valid []))))
      (mu/log ::rerank-dropped-malformed-entries
              :raw-count (count canon)
              :valid-count (count valid)))
    valid))

;; =============================================================================
;; Public API
;; =============================================================================

(defn rerank!
  "Invoke the reranker workflow with (query, intent, candidates).

   Returns the :reranked-results vector — a vector of
   {:document-id :reasoning :fitness-score} entries in descending
   :fitness-score order. Returns nil if the workflow fails.

   RR-1: when the call TIMED OUT and the one retry ALSO timed out, the
   return value is an EMPTY vector carrying `{:rerank-timeout? true}`
   metadata (read it with `timed-out?`). Callers that only check
   `(seq …)` — every caller today — see it exactly as they see a nil/empty
   result and take their existing fallback path unchanged; the metadata
   only lets a caller distinguish 'infra was slow' from 'the reranker
   could not rank' when it wants to (see apply-rerank's
   :timeout-fallback stamp).

   Args:
     ctx        — context with :event-store / :llm-provider
     opts       — {:query :intent :candidates :model}
       :query       — original NL query string
       :intent      — caller's goal/context string
       :candidates  — vector of candidate maps (each with at least
                      :content :score :document-id :document-metadata)
       :timeout-ms  — optional explicit execution budget for the rerank
                      workflow. Defaults to default-rerank-timeout-ms
                      (see its docstring for the sizing evidence).
       :model       — OPTIONAL OpenRouter model id override for the
                      'rerank' node. Defaults to `default-model` (RR-2)
                      when absent, mirroring the caller-overridable
                      config-slot pattern so a future deployment can pick
                      a different model with no code change."
  [ctx {:keys [query intent candidates timeout-ms model]}]
  (let [budget-ms (resolve-timeout-ms ctx timeout-ms)
        resolved-model (or model default-model)
        sheet-id (orc/build-workflow! ctx (reranker-workflow resolved-model))
        inputs   {:query query
                  :intent intent
                  :candidates candidates}
        ;; RR-1: retry-on-TIMEOUT only. A timeout is transient infra (tail
        ;; latency against a fixed clock); the SAME call is re-run once,
        ;; unchanged, before the caller's fallback path is reached. Any other
        ;; non-success status is a genuine rerank failure and is NOT retried
        ;; here (the node itself already owns content-level retries via
        ;; :max-retries 3).
        result   (loop [attempt 0]
                   (let [r (orc/execute ctx sheet-id inputs :timeout-ms budget-ms)]
                     (if (and (= :timeout (:status r))
                              (< attempt max-timeout-retries))
                       (do (mu/log ::rerank-timed-out-retrying
                                   :attempt (inc attempt)
                                   :timeout-ms budget-ms
                                   :duration-ms (:duration-ms r))
                           (recur (inc attempt)))
                       r)))]
    (when-not (= :success (:status result))
      (mu/log ::rerank-workflow-failed
              :status (:status result)
              :error (:error result)
              :duration-ms (:duration-ms result)))
    (case (:status result)
      ;; RR-1: the retry ALSO timed out. Hand back the timeout-marked empty
      ;; result so the caller's fallback can record WHY it fell back.
      :timeout (do (mu/log ::rerank-timeout-exhausted
                           :timeout-ms budget-ms
                           :attempts (inc max-timeout-retries))
                   timed-out-result)
      :success (parse-reranked-json result)
      nil)))
