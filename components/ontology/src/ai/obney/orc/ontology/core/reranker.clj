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
                  document-metadata {granularity, target-id, confidence, last-update}.

YOUR JOB
Rank the candidates by how well they FIT THE INTENT, not by raw lexical
overlap with the query. Cross-reference each candidate's CONTENT
against what the caller is actually trying to accomplish.

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

Return ALL candidates, even low-fitness ones — the caller may want the
full ranking. Do not drop any.")

;; =============================================================================
;; Workflow definition
;; =============================================================================

(def reranker-workflow
  "Single-:llm-node ORC workflow for the description reranker.

   Inputs (blackboard): :query, :intent, :candidates
   Output (one :writes slot): :reranked-json
     — a JSON string of the reranked list (parsed back to Clojure in
       `rerank!`). We use a JSON-string output rather than a native
       vector-of-maps because some LLM providers (including
       gemini-3-flash-preview) hang or fail when asked to produce
       deeply-nested structured output via U11 :output-schemas. A
       string output trivially passes structured-output validation;
       we own the parse + validate step downstream."
  (orc/workflow "ontology-description-reranker"
    (orc/blackboard
      {:query         :string
       :intent        :string
       :candidates    [:vector :map]
       :reranked-json :string})

    (orc/llm "rerank"
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
      ;; bare JSON. dscloj's marker-parser then returns nil, the
      ;; executor's outputs-have-nil retry path exhausts, and the
      ;; workflow succeeds with nil outputs. Function-calling tools
      ;; avoid that brittleness — the model is structurally compelled
      ;; to call the submit_response tool with our shape.
      :options {:max-retries 3
                :retry-delay-ms [500 1500 3000]
                :use-function-calling? true})))

;; =============================================================================
;; Public API
;; =============================================================================

(defn rerank!
  "Invoke the reranker workflow with (query, intent, candidates).

   Returns the :reranked-results vector — a vector of
   {:document-id :reasoning :fitness-score} entries in descending
   :fitness-score order. Returns nil if the workflow fails.

   Args:
     ctx        — context with :event-store / :dscloj-provider
     opts       — {:query :intent :candidates}
       :query       — original NL query string
       :intent      — caller's goal/context string
       :candidates  — vector of candidate maps (each with at least
                      :content :score :document-id :document-metadata)"
  [ctx {:keys [query intent candidates]}]
  (let [sheet-id (orc/build-workflow! ctx reranker-workflow)
        result   (orc/execute ctx sheet-id
                              {:query query
                               :intent intent
                               :candidates candidates})]
    (when-not (= :success (:status result))
      (mu/log ::rerank-workflow-failed
              :status (:status result)
              :error (:error result)
              :duration-ms (:duration-ms result)))
    (when (= :success (:status result))
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
        valid))))
