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

(def ^:private domain-coverage-section
  "RS-1 (proven by RS-P1b run 2 — `development/bench/ood-stress-results/rs-p1b2-sibling-reuse-separated-probe/FINDINGS.md`):
   the domain-coverage section of the reranker instruction, and its
   replacement six-key output contract. Inserted into `reranker-instruction`
   VERBATIM, in place of the shipped three-key output-contract paragraph.
   Kept as its own constant (OUR text, not model prose) so a test can assert
   the instruction carries it byte-for-byte without matching on anything the
   model itself produces."
  "DOMAIN COVERAGE — A SEPARATE VERDICT, NOT A NUMBER.
fitness_score means how well the candidate's SHAPE and intent fit the task.
Separately, for each candidate, judge whether the candidate's DECLARED DOMAIN
— its representative uses and its avoid-when guards — covers the DOMAIN of the
task (what the task is about: the subject matter, the material, the kind of
output). Give a discrete verdict:
  covered    — a representative use names this task's SUBJECT MATTER, its
               MATERIAL (what is read) and its OUTPUT KIND. A shared kind of
               processing ('a pipeline', 'a sequence of passes', 'draft then
               revise') is NOT a domain and never makes a candidate covered.
  partial    — a representative use shares the subject matter but not the
               material or the output kind, or the reverse
  uncovered  — nothing the candidate declares names this task's subject
               matter, material or output kind; the fit, if any, is shape only
  unknown    — you cannot tell from what the candidate declares
The label names the FAMILY of task the domain belongs to: its subject matter
and its output kind — never the instance's own material, dates, names or
numbers. \"marathon-training-plan\" names a family; \"16-week marathon plan for
a first-time runner starting June 3\" names one instance of it. Two tasks that
share subject matter and output kind but differ in material (a different
runner's numbers, a different contract's clauses) are the SAME family and
must get the SAME label.

You are shown existing-domain-labels: the tenant's own list of family labels
already in use (bounded, most-recently-minted first) — and a candidate may
ALSO carry existing-domain-children: labels of domain children already minted
under that ONE candidate. Both serve ONE purpose — label reuse, never
coverage. Reuse a listed label VERBATIM as domain_label only when this task
shares BOTH the subject matter AND the output kind of that label's family. If
the subject matter differs, or the output kind differs, it is a different
family: coin a new label, even when the other one matches (a chess study plan
and a language-course curriculum are both structured plans, but their subject
matter differs, so they are different families). When you are unsure whether a
listed label covers this task, coin a new label: a new family can later be
merged by a judge that reads full descriptions, while a wrong reuse is final.
existing-domain-labels and
existing-domain-children MUST NOT influence domain_coverage: coverage is
judged solely against the candidate's OWN representative uses and content. A
child (or a tenant label) naming this task's domain does not make a parent
covered — a parent with a matching child is exactly the case where the task
belongs to the child, not to the parent.
Write domain_reasoning BEFORE choosing the verdict: name the representative use
or guard you matched, or state the gap. Also give domain_label: a 2-4 word
kebab-case label of the TASK's own domain (the same label for every candidate
of this task), e.g. \"marathon-training-plan\", \"recipe-scaling\",
\"security-findings-haiku\".

PRODUCE a JSON string of a vector, descending by fitness_score. Each
element is an object with EXACTLY these six keys:
  {\"document_id\":     \"<echo the candidate's document-id verbatim>\",
   \"reasoning\":       \"<concrete, actionable; references specific content>\",
   \"fitness_score\":   <number in [0.0, 1.0]>,
   \"domain_reasoning\": \"<the representative use / guard matched, or the gap>\",
   \"domain_coverage\": \"<covered|partial|uncovered|unknown>\",
   \"domain_label\":    \"<2-4 word kebab-case label of the task's domain>\"}

Example shape:
  [{\"document_id\":\"a\",\"reasoning\":\"...\",\"fitness_score\":0.91,
    \"domain_reasoning\":\"...\",\"domain_coverage\":\"covered\",\"domain_label\":\"contract-comparison\"},
   {\"document_id\":\"b\",\"reasoning\":\"...\",\"fitness_score\":0.42,
    \"domain_reasoning\":\"...\",\"domain_coverage\":\"uncovered\",\"domain_label\":\"contract-comparison\"}]")

(def ^:private reranker-instruction
  (str "You are ranking candidate descriptions by their fitness for a caller's intent.

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
- existing-domain-labels — a bounded JSON vector of strings: every domain
                  family label already in use across the WHOLE tenant (not
                  just one candidate's own children), most-recently-minted
                  first. See DOMAIN COVERAGE below for how to use it.

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

"
       domain-coverage-section
       "

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
per input candidate."))

;; =============================================================================
;; Workflow definition
;; =============================================================================

(def default-model
  "RR-2 (ADR 0020 decision 5, AMENDED — see grill GR-2 Q2): the reranker's
   default :model when the caller supplies none.

   The original default was `qwen/qwen3.5-flash-02-23`, described as
   validated-by-repro. CH-1 MEASURED that claim false on this path, N=10
   per arm against the real corpus via direct `rerank!`:

     shipped qwen ...... valid ranking  0/10 | silent marker fallback 10/10
     gemini-3-flash .... valid ranking 10/10 | silent marker fallback  0/10

   The model is not the root cause. `dscloj` emits `:tool_choice` while
   litellm-clj's OpenRouter transform reads `:tool-choice`, so the forced
   tool choice is dropped and function-calling silently degrades to the
   marker parsing this node was configured to avoid (ADR 0025 fixes that
   upstream). But qwen is a thinking model, and Alibaba returns HTTP 400
   for a *forced* tool choice in thinking mode — confirmed by raw curl —
   so it is unsuitable BOTH before and after the key fix. Gemini calls
   the offered tool voluntarily today and can be forced tomorrow.

   This is therefore the evidence-based default under the amended
   understanding, not an interim patch. Re-open only if the post-ADR-0025
   re-measurement surprises us."
  "google/gemini-3-flash-preview")

(defn resolve-model
  "RR-CFG: which model the reranker's 'rerank' node is pinned to for THIS
   call. Per-call opt > per-deployment context slot > the ratified default —
   the same precedence `resolve-timeout-ms` gives the reranker's other
   policy knob, and the same CONTEXT-carried seam the ontology's other model
   slot uses (`:ontology-consolidator-model`, consolidator.clj).

   Why this exists: `default-model` alone made the reranker's model
   unreachable from configuration. During the PR-6 free shakeout every model
   slot was set to a `:free` model and the store still recorded PAID
   `google/gemini-3-flash-preview` executions — a run configured entirely
   free silently billed a model outside every configured slot, because there
   was no slot to configure. The ratified default (grill GR-2 Q2 / CC-9c,
   ADR 0025) is UNCHANGED; it is now the FALLBACK rather than the only
   possibility.

   Set `:ontology-reranker-model` on the context wherever the deployment's
   other model slots are wired."
  [ctx model]
  (or model (:ontology-reranker-model ctx) default-model))

(def ^:private compact-principle-entry
  "A strengths/weaknesses entry at the RERANKER's provider boundary: the
   COMPACT principle shape EL-2's enrichment actually sends — an actionable
   :trait plus optional guard/advice — with the body-side weight signal
   (:confidence/:evidence-count) OPTIONAL rather than required. A floor,
   not a dialect: a full `ontology-schemas/principle-entry` also validates."
  [:map
   [:trait :string]
   [:good-when               {:optional true} :string]
   [:avoid-when              {:optional true} :string]
   [:recommended-pattern     {:optional true} :string]
   [:recommended-alternative {:optional true} :string]
   [:confidence              {:optional true} :double]
   [:evidence-count          {:optional true} :int]
   [:first-observed-at       {:optional true} :string]
   [:last-reinforced-at      {:optional true} :string]])

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
   ;; CC-15 integration finding (live, 2026-08-11): EL-2's enrichment
   ;; deliberately COMPACTS these entries to {:trait + guard + advice}
   ;; ("keep the enrichment compact", interface.clj compact-strengths/
   ;; compact-weaknesses) — it never sends :confidence/:evidence-count.
   ;; Declaring the FULL ontology-schemas/principle-entry here (the sio-era
   ;; schema-enforcement commit) made every enriched rerank fail blackboard
   ;; validation: pure-ColBERT fallback, EL-3 defer, 100% of live
   ;; classifications deferred (measured — CC-23's deferral events caught it
   ;; on their first production traffic). The boundary must describe the
   ;; payload the producers actually send: :trait is the floor, the guard/
   ;; advice pair and the weight signal are optional, so BOTH real producers
   ;; (EL-2 compact, and any future full-entry sender) validate.
   [:strengths {:optional true} [:vector compact-principle-entry]]
   [:weaknesses {:optional true} [:vector compact-principle-entry]]
   ;; RS-1: labels of domain children already minted under this candidate,
   ;; for the reranker's judge_domain_coverage label-reuse step (D7/D7b).
   ;; RS-2/RS-3 populate this; here it is accepted and rendered into the
   ;; candidates JSON — it MUST NOT influence :domain-coverage itself (the
   ;; instruction states that constraint; see `domain-coverage-section`).
   [:existing-domain-children {:optional true} [:vector :string]]])

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

   Inputs (blackboard): :query, :intent, :candidates, :existing-domain-labels
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
      {:query                   :string
       :intent                  :string
       :candidates              [:vector candidate-schema]
       ;; CV-A item 6: the tenant-wide, bounded label list — reuse over
       ;; coin (see the domain-coverage-section's task-family paragraph).
       ;; A vector of plain strings; empty when no families exist yet.
       :existing-domain-labels  [:vector :string]
       :reranked-json           :string})

    (orc/llm "rerank"
      :model model
      :instruction reranker-instruction
      :reads [:query :intent :candidates :existing-domain-labels]
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

(def ^:private domain-coverage-values
  "RS-1: the four values of `specs/ontology.allium`'s `enum DomainCoverage`.
   `parse-reranked-json` reads any `:domain_coverage` outside this set
   (missing, or a string the model sent that is not one of these four) as
   `:unknown` rather than coercing it."
  #{:covered :partial :uncovered :unknown})

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
                          ;; RS-1 (judge_domain_coverage's DomainVerdict):
                          ;; canonicalized the same way as the existing keys.
                          ;; A missing or malformed :domain_coverage DEFERS
                          ;; to :unknown — never coerced to whatever string
                          ;; the model sent (DomainCoverageIsJudgedNotInferred).
                          true (assoc :domain-coverage
                                      (let [v (some-> (:domain_coverage e) keyword)]
                                        (if (contains? domain-coverage-values v)
                                          v
                                          :unknown)))
                          true (assoc :domain-label (:domain_label e))
                          true (assoc :domain-reasoning (:domain_reasoning e))
                          true (dissoc :domain_coverage :domain_label :domain_reasoning)
                          ;; Keep only the canonical kebab-case keys
                          true (select-keys [:document-id :reasoning :fitness-score
                                              :domain-coverage :domain-label :domain-reasoning])))
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
                      'rerank' node (RR-2). RR-CFG: when absent, the
                      model resolves from the context slot
                      `:ontology-reranker-model`, and only then falls
                      back to the ratified `default-model`. See
                      `resolve-model`.
       :existing-domain-labels — CV-A item 6: OPTIONAL bounded vector of
                      the tenant's existing domain-family labels, for the
                      reranker's label-reuse instruction. Defaults to []
                      so every existing caller (walk-down's pick-best-child,
                      every pre-CV-A test) that supplies none still fills
                      the blackboard's required slot."
  [ctx {:keys [query intent candidates timeout-ms model existing-domain-labels]}]
  (let [budget-ms (resolve-timeout-ms ctx timeout-ms)
        resolved-model (resolve-model ctx model)
        sheet-id (orc/build-workflow! ctx (reranker-workflow resolved-model))
        inputs   {:query query
                  :intent intent
                  :candidates candidates
                  :existing-domain-labels (or existing-domain-labels [])}
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

;; =============================================================================
;; CV-C — the domain-family merge judge (`specs/ontology.allium`'s
;; `DomainFamilyMergeIsJudged`)
;;
;; A SECOND, separate single-:llm-node workflow — mirrors `reranker-workflow`/
;; `rerank!`'s shape exactly (own blackboard, own byte-pinned instruction, own
;; function-calling node) rather than overloading the ranking workflow, so the
;; merge question's own prompt/output-contract can evolve independently of
;; the ranking prompt's.
;; =============================================================================

(def ^:private family-schema
  "One candidate family shown to the merge judge — CV-C item 3's
   `nearest-families` retrieval, JOINED back here into the blackboard
   input. `:id` a STRING (JSON round-trip — `default-domain-merge-fn`
   re-derives the typed id from its OWN candidate list by string
   comparison, never trusts the judge's echoed id verbatim)."
  [:map
   [:id :string]
   [:label {:optional true} [:maybe :string]]
   [:description :string]])

(def ^:private family-merge-instruction
  "RS-P3's verdict (`development/bench/ood-stress-results/rs-p3-family-merge-probe/FINDINGS.md`):
   both arms converged 8/8 corpus groups; the ONE cross-group false merge
   (recipe scaling merged into a marathon-plan family) happened because the
   instruction stated 'same family = shared subject matter AND output kind'
   but never stated the CONVERSE — the judge treated a shared output kind
   alone as sufficient. This instruction states both converses explicitly
   (verdict's fix #1); covered-seed protection running BEFORE this judge
   (verdict's fix #2) is CV-A's existing ordering, unchanged here."
  (str "You decide whether a NEW task belongs to an EXISTING domain family or starts a new one.\n\n"
       "A domain family is the set of tasks that share BOTH its SUBJECT MATTER (what the task is "
       "about) AND its OUTPUT KIND (what kind of thing the task produces) — e.g. every request to "
       "scale a tested recipe to a different batch size is ONE family whatever the dish, venue or "
       "equipment; a request to compute nutrition labels is a DIFFERENT family even though it also "
       "involves recipes. The CONCRETE INSTANCE (the dish, the database engine, the runner's age) "
       "never makes a new family.\n\n"
       "BOTH axes must match for \"same\": a DIFFERENT subject matter is a NEW family even when the "
       "output kind matches (a marathon-training plan and a recipe-scaling plan share the output "
       "kind \"a structured, multi-component plan derived from specific input parameters\" but "
       "differ in subject matter — DIFFERENT families); a DIFFERENT output kind is a NEW family "
       "even when the subject matter matches (recipe scaling and nutrition-label computation share "
       "the subject matter \"recipes\" but differ in output kind — DIFFERENT families).\n\n"
       "Judge each axis at the level of the candidate family's OWN label and description, never at an "
       "umbrella category above it. \"Physical activity scheduling\" is an umbrella over marathon "
       "training and injury rehabilitation, which are DIFFERENT subject matters; \"technical "
       "troubleshooting\" is an umbrella over log triage and query optimisation, which are DIFFERENT "
       "subject matters. If you find yourself naming a broader category to make two tasks match, the "
       "axis does not match.\n\n"
       "INPUTS DESCRIBED\n"
       "- task        — the new task's instruction text\n"
       "- reasoning   — the reranker's domain reasoning for the new task (why the matched shape "
       "does not cover this task's domain)\n"
       "- candidates  — a JSON array of the NEAREST EXISTING domain families, each with id, label "
       "and description (its purpose, the shape it was born under, and the signature that minted "
       "it)\n\n"
       "Write merge_reasoning FIRST, before choosing the verdict: name the candidate you compared "
       "against and say explicitly what is SHARED and what DIFFERS on BOTH axes (subject matter and "
       "output kind). Then give the verdict:\n"
       "  same    — with the family id, when exactly one candidate is the SAME family (both axes "
       "match)\n"
       "  new     — when no candidate is the same family (at least one axis differs from every "
       "candidate)\n"
       "  unknown — when you cannot tell from what is shown\n\n"
       "Respond with a JSON object with EXACTLY these five keys:\n"
       "  {\"merge_reasoning\": \"<name the candidate compared; state what is shared and what "
       "differs in subject matter and output kind>\",\n"
       "   \"subject_matter_same\": \"yes\"|\"no\"  (for the candidate you compared, at its own level),\n"
       "   \"output_kind_same\": \"yes\"|\"no\"  (for the candidate you compared),\n"
       "   \"verdict\": \"same\"|\"new\"|\"unknown\",\n"
       "   \"family\": \"<the compared candidate's id when verdict is same, otherwise null>\"}\n"
       "No surrounding prose, no code fences."))

(defn- domain-family-merge-workflow-name
  "Same deterministic-sheet-identity reasoning as `reranker-workflow-name`:
   keep the DEFAULT model's identity pinned to a stable name; a
   caller-supplied override gets its own distinct sheet identity."
  [model]
  (if (= model default-model)
    "ontology-domain-family-merge"
    (str "ontology-domain-family-merge--" model)))

(defn domain-family-merge-workflow
  "CV-C item 1: the merge judge's single-:llm-node ORC workflow. Pure data
   (no I/O), mirroring `reranker-workflow`'s shape.

   Inputs (blackboard): :task, :reasoning, :candidates
   Output (one :writes slot): :merge-json — a JSON string (function-calling
     may hand back a MAP directly; `parse-merge-answer` accepts both — see
     its docstring for the RS-P3 run-1 parser defect this guards)."
  [model]
  (orc/workflow (domain-family-merge-workflow-name model)
    (orc/blackboard
      {:task       :string
       :reasoning  :string
       :candidates [:vector family-schema]
       :merge-json :string})

    (orc/llm "merge"
      :model model
      :instruction family-merge-instruction
      :reads [:task :reasoning :candidates]
      :writes [:merge-json]
      :options {:max-retries 3
                :retry-delay-ms [500 1500 3000]
                :use-function-calling? true})))

(defn- parse-merge-answer
  "CV-C: the merge workflow's structured answer arrives either as a MAP
   (function calling — the shipped path) or as a JSON STRING. Accept both:
   RS-P3 run 1 expected a string only and silently dropped 16 valid map
   verdicts, every one counted as :unknown
   (`development/bench/ood-stress-results/rs-p3-family-merge-probe/FINDINGS.md`,
   'Run 1 (parser defect)'). A malformed/unparseable answer returns nil —
   the caller reads that as verdict :unknown, never coerced."
  [raw]
  (cond
    (map? raw) (into {} (map (fn [[k v]] [(keyword (name k)) v])) raw)
    (string? raw)
    (let [s (.indexOf raw "{") e (.lastIndexOf raw "}")]
      (when (and (>= s 0) (> e s))
        (try (json/read-str (subs raw s (inc e)) :key-fn keyword)
             (catch Throwable _ nil))))
    :else nil))

(defn combine-merge-verdict
  "The judge's verdict, held to its own two axis answers. A \"same\" stands only
   when the judge ALSO answered yes on both subject matter and output kind; a
   \"same\" beside a \"no\" on either axis is the judge's own evidence of a
   different family and becomes :new (live finding: the judge wrote \"the subject
   matter differs\" and still answered same, by lifting both tasks to an umbrella
   category). A \"same\" with a missing or malformed axis answer is :unknown. The
   axis answers are the judge's structured output, not a reading of its prose.
   :new and :unknown pass through; anything out of set is :unknown."
  [verdict-kind subject-matter-same output-kind-same]
  (let [axis (fn [v] ({"yes" :yes "no" :no} (some-> v str clojure.string/trim clojure.string/lower-case)))
        sm (axis subject-matter-same)
        ok (axis output-kind-same)]
    (case verdict-kind
      :same (cond (and (= :yes sm) (= :yes ok)) :same
                  (or (= :no sm) (= :no ok)) :new
                  :else :unknown)
      :new :new
      :unknown)))

(defn merge-family!
  "CV-C item 2: invoke the domain-family-merge workflow with (signature,
   reasoning, candidates). Model resolution like `rerank!` (`resolve-model`
   — per-call opt > per-deployment ctx slot > the ratified default).

   Returns {:kind :same|:new|:unknown :family <id-or-nil> :reasoning
   <string-or-nil> :usage <map-or-nil>} — :kind is ALWAYS one of the three
   verdict values (an out-of-set or missing verdict, or a non-:success
   execution, reads as :unknown, never coerced/guessed).

   Args:
     ctx   — context with :event-store / :llm-provider
     opts  — {:signature :reasoning :candidates :model :timeout-ms}
       :signature   — the new task's signature text
       :reasoning   — the reranker's domain reasoning for the new task
       :candidates  — vector of {:id :label :description} (CV-C item 3's
                      `nearest-families`); :id is coerced to a string for
                      the workflow's :candidates blackboard input
       :model       — OPTIONAL model override (RR-2-style; see `resolve-model`)
       :timeout-ms  — OPTIONAL explicit execution budget (see
                      `resolve-timeout-ms`; shares the reranker's
                      `:rerank-timeout-ms` ctx knob and default budget —
                      this is the same shape of single-:llm-node call)."
  [ctx {:keys [signature reasoning candidates model timeout-ms]}]
  (let [budget-ms (resolve-timeout-ms ctx timeout-ms)
        resolved-model (resolve-model ctx model)
        sheet-id (orc/build-workflow! ctx (domain-family-merge-workflow resolved-model))
        inputs {:task (or signature "")
                :reasoning (or reasoning "")
                :candidates (mapv (fn [{:keys [id label description]}]
                                    {:id (str id)
                                     :label (or label "")
                                     :description (or description "")})
                                  candidates)}
        result (orc/execute ctx sheet-id inputs :timeout-ms budget-ms)]
    (if (not= :success (:status result))
      (do (mu/log ::merge-family-workflow-failed
                  :status (:status result)
                  :error (:error result)
                  :duration-ms (:duration-ms result))
          {:kind :unknown :family nil :reasoning nil :usage nil})
      (let [parsed (parse-merge-answer (get-in result [:outputs :merge-json]))
            verdict-kind (some-> (:verdict parsed) name keyword)
            kind (combine-merge-verdict verdict-kind
                                        (:subject_matter_same parsed)
                                        (:output_kind_same parsed))]
        (when (nil? verdict-kind)
          (mu/log ::merge-answer-unparseable
                  :raw-preview (let [raw (get-in result [:outputs :merge-json])]
                                (cond (string? raw) (subs raw 0 (min 200 (count raw)))
                                      (some? raw) (pr-str raw)))))
        (cond-> {:kind kind :family nil :reasoning nil :usage (:usage result)}
          (:family parsed) (assoc :family (:family parsed))
          (:merge_reasoning parsed) (assoc :reasoning (:merge_reasoning parsed)))))))
