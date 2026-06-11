(ns c2d-ood-specialized-seeds-experiment
  "C-Loop-3 R04 follow-up — specialized-seed experiment.

   Hypothesis (proposed during R04 sweep review): the existing 12
   abstract behavioral seeds (Research / Extraction / Analysis / etc.)
   are too SHAPE-broad to discriminate between in-distribution and OOD
   tasks. The reranker matches on structural pattern (per-item
   processing, draft-then-validate, etc.) and ignores DOMAIN
   specialization (what's being analyzed, what tools/flows are needed).

   Test: introduce TWO specialized behavioral seeds that share shape
   with existing abstract behaviors but pin domain:

     1. `iterative-tuning-via-simulated-feedback`
        Shape: iterative refinement (similar to Validation-loop)
        Specialization: pairs LLM hypothesis with deterministic oracle
                        for measured feedback; matches game-balance,
                        hyperparameter search, A/B test optimization

     2. `code-investigation-by-hypothesis-ranking`
        Shape: investigation (similar to abstract Investigation seed)
        Specialization: ranked hypothesis generation with
                        falsifiability tests + effort estimates;
                        matches flaky-test diagnosis, memory-leak
                        debugging, incident root-cause analysis

   Predicted outcomes if hypothesis is right:
     - domain-001-game-balance attracts iterative-tuning at 0.9+
     - code-002, code-004 attract code-investigation at 0.9+
     - Tasks WITHOUT a specialized match (haiku, melody, recipe,
       marathon, symphony) stay at force-fit OR shift toward fresh-mint
     - Sanity-check tasks (extra-001 legal, extra-002 contract) stay
       at 1.00 (proves the new seeds aren't broken)

   Predicted outcomes if hypothesis is wrong (shape-matching is the
   dominant signal):
     - Specialized seeds match in-domain AND irrelevant tasks
       (e.g., iterative-tuning matching marathon training at 0.9)
     - Force-fit pattern unchanged

   Either result is informative. Saved to
   `development/bench/ood-stress-results/<ts>-with-specialized/`."
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.orc.ontology.interface :as ontology]
            [ai.obney.orc.ontology.core.todo-processors :as ont-tp]
            [c2d-ood-stress-live :as live]
            [c2d-ood-stress-test :as ood]
            [runner]))

;; =============================================================================
;; Specialized seed bodies
;; =============================================================================

(defn- stable-uuid [s]
  (java.util.UUID/nameUUIDFromBytes (.getBytes ^String s "UTF-8")))

(def iterative-tuning-seed
  {:target-id (stable-uuid "seed:behavior:experimental:iterative-tuning-via-simulated-feedback")
   :body
   {:scope :behavioral-subtree
    :capabilities
    ["iterate parameter adjustments driven by measured feedback from a simulator, A/B test, or cheap oracle"
     "detect convergence (metric within target band for 2+ rounds) vs divergence (metric oscillating)"
     "maintain per-round history of adjustments + resulting metric values for traceability"
     "decide when to stop iterating vs when to widen the search space"]
    :strengths
    [{:trait "pair LLM-driven heuristic adjustments with a deterministic oracle for the feedback signal — the oracle is the ground truth, the LLM is the search strategy"
      :good-when "the oracle is cheap to evaluate (simulation, batched A/B, deterministic check) AND the target metric has a clear convergence band"
      :recommended-pattern "[:sequence [:llm {:reads [:current-params :round-history] :writes [:hypothesis :proposed-adjustment]}] [:code {:reads [:proposed-adjustment :params] :writes [:simulated-metric] :fn (fn [{:keys [proposed-adjustment params]}] {:simulated-metric (oracle-fn (merge params proposed-adjustment))})}] [:llm {:reads [:simulated-metric :target-band] :writes [:converged? :next-action]}] [:final {:keys [:final-params :round-history]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-06-09T00:00:00Z"
      :last-reinforced-at "2026-06-09T00:00:00Z"}
     {:trait "track WHICH round produced WHICH adjustment so the model can revert if a later round regresses — round-history is a vector of {:round N :adjustment {...} :metric X}"
      :good-when "the search may need to backtrack — adjustments don't always monotonically improve"
      :recommended-pattern "[:code {:reads [:round-history :current-round] :writes [:next-round-history] :fn (fn [{:keys [round-history current-round]}] {:next-round-history (conj round-history current-round)})}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-06-09T00:00:00Z"
      :last-reinforced-at "2026-06-09T00:00:00Z"}]
    :weaknesses
    [{:trait "model can overshoot/undershoot when adjustments are too aggressive — the LLM produces a hypothesis without bound checking"
      :avoid-when "the parameter space is high-dimensional and the oracle is noisy"
      :recommended-alternative "wrap adjustment in :code that clamps to a per-parameter bound based on round-history slope before passing to the oracle"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-06-09T00:00:00Z"
      :last-reinforced-at "2026-06-09T00:00:00Z"}
     {:trait "iteration is impractical when the oracle is slow (>30s per measurement) — wall-clock dominates LLM cost"
      :avoid-when "measurement requires running a job to completion (training a model, executing a long test suite)"
      :recommended-alternative "switch to gradient-based methods if the measurement is differentiable, OR cache prior measurements aggressively"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-06-09T00:00:00Z"
      :last-reinforced-at "2026-06-09T00:00:00Z"}]
    :representative-uses
    ["game balance via simulated playtest (4-class card game converges to fair win-rate matrix)"
     "ML hyperparameter tuning against k-fold cross-validation oracle"
     "A/B test optimization across feature flag combinations"
     "compiler optimization parameter search against benchmark suite"]
    :avoid-when
    ["the work is a single-shot transformation (no iteration needed)"
     "the oracle is unavailable or prohibitively expensive"
     "the metric target is unbounded (no convergence band)"]
    :summary "Iterate parameter adjustments using LLM-driven heuristic search paired with a deterministic oracle for the feedback signal. The hypothesize → measure → adjust loop converges when the metric target is bounded and the oracle is cheap. SPECIALIZED for use cases where the search space is continuous, the oracle is well-defined, and convergence (not just one-shot synthesis) is the goal."
    :version 1
    :consolidated-from-event-count 0
    :parent-behavior nil
    :composes-into []}})

(def code-investigation-seed
  {:target-id (stable-uuid "seed:behavior:experimental:code-investigation-by-hypothesis-ranking")
   :body
   {:scope :behavioral-subtree
    :capabilities
    ["generate multiple hypotheses for an observed software defect or anomalous behavior from symptoms"
     "rank hypotheses by likelihood given the evidence available"
     "design a falsifiability test per hypothesis — a SPECIFIC experiment that would prove the hypothesis FALSE"
     "estimate effort-to-test per hypothesis so the ranking accounts for cost, not just likelihood"
     "produce a recommended :next-action that explains which hypothesis to test first AND WHY"]
    :strengths
    [{:trait "rank by likelihood AND effort-to-test, not just likelihood — a 70% likely cause that costs 2 days to investigate might rank below a 40% likely cause testable in 30 minutes"
      :good-when "multiple plausible causes exist, each with different verification cost"
      :recommended-pattern "[:sequence [:llm {:reads [:symptoms :recent-changes] :writes [:candidate-hypotheses]}] [:llm {:reads [:candidate-hypotheses :evidence] :writes [:ranked-hypotheses :next-action] :output-schemas {:ranked-hypotheses [:vector [:map [:hypothesis :string] [:likelihood-pct :int] [:falsifiability-test :string] [:effort :keyword]]]}}] [:final {:keys [:ranked-hypotheses :next-action]}]]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-06-09T00:00:00Z"
      :last-reinforced-at "2026-06-09T00:00:00Z"}
     {:trait "always produce AT LEAST one falsifiability test per hypothesis — a hypothesis without a way to disprove it is not actionable"
      :good-when "the team needs concrete next steps, not vibes"
      :recommended-pattern "[:llm {:instruction \"For each hypothesis, propose a SPECIFIC experiment that would prove the hypothesis FALSE (not just confirm it). Example: 'revert the polling library to v2.1 and observe flakiness rate — if rate is unchanged, hypothesis is FALSE'.\" :reads [:hypotheses] :writes [:hypotheses-with-tests]}]"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-06-09T00:00:00Z"
      :last-reinforced-at "2026-06-09T00:00:00Z"}]
    :weaknesses
    [{:trait "confirmation bias — if the first hypothesis tested is wrong, the model may invent new hypotheses rather than reconsider the original rank"
      :avoid-when "symptoms are sparse or contradictory (high uncertainty over the hypothesis space)"
      :recommended-alternative "explicitly ask for diagnostic data BEFORE generating hypotheses; suggest a 'gather signal first' step when symptom evidence is thin"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-06-09T00:00:00Z"
      :last-reinforced-at "2026-06-09T00:00:00Z"}
     {:trait "rank quality depends heavily on the model's familiarity with the domain — generic rankings on a niche stack tend to over-weight popular causes"
      :avoid-when "the system under investigation uses an unusual language/framework the model may not have seen extensively"
      :recommended-alternative "include the project's tech stack details in :reads so the model can ground rankings; cite the stack details in the hypothesis bodies"
      :confidence 1.0
      :evidence-count 1
      :first-observed-at "2026-06-09T00:00:00Z"
      :last-reinforced-at "2026-06-09T00:00:00Z"}]
    :representative-uses
    ["root-cause analysis on flaky CI tests"
     "diagnosing a production memory leak from heap traces + logs"
     "incident retrospective with multiple causal threads"
     "debugging a performance regression after a deployment"]
    :avoid-when
    ["the symptom is unambiguous and has a single obvious cause (no ranking needed — just fix it)"
     "the issue is reproducible deterministically and a debugger can step through (use the debugger, don't rank)"
     "the team is looking for an artifact (refactor proposal, design doc) — not an investigation report"]
    :summary "Generate ranked hypotheses for root-cause investigation. Each hypothesis carries an evidence chain, a falsifiability test, and an effort estimate. SPECIALIZED for software-debugging and incident-analysis contexts where multiple plausible causes exist and the team needs an ordered investigation plan, not just a list of guesses."
    :version 1
    :consolidated-from-event-count 0
    :parent-behavior nil
    :composes-into []}})

(def experimental-seeds
  [iterative-tuning-seed code-investigation-seed])

;; =============================================================================
;; Emission + ColBERT rebuild
;; =============================================================================

(defn- emit-experimental-seed!
  "Dispatch :ontology/record-tree-description for one experimental seed.
   The body's :scope :behavioral-subtree routes it to the behavioral-
   subtree projector path."
  [ctx {:keys [target-id body]}]
  (cp/process-command
    (assoc ctx :command {:command/name :ontology/record-tree-description
                         :command/id (random-uuid)
                         :command/timestamp (time/now)
                         :target-id target-id
                         :body body})))

(defn install-experimental-seeds!
  "Emit the 2 specialized behavioral seeds + force ColBERT re-index so
   the wedge classifier can find them."
  [ctx]
  (println "Installing 2 specialized experimental behavioral seeds...")
  (doseq [seed experimental-seeds]
    (emit-experimental-seed! ctx seed)
    (println "  emitted:" (-> seed :body :summary (subs 0 (min 80 (count (:summary (:body seed)))))) "..."))
  (Thread/sleep 500)
  (println "Triggering ColBERT re-index (force-rebuild via QP-3-like path)...")
  (ont-tp/force-rebuild! ctx)
  (Thread/sleep 1000)
  (println "Index state after rebuild:" (pr-str (ontology/get-reindex-state ctx))))

;; =============================================================================
;; Experiment runner
;; =============================================================================

(defn run-experiment!
  "Start runner, install the 2 specialized seeds, run the 21-task
   classify-only sweep, save under ood-stress-results/<ts>-with-
   specialized/. Caller compares against the baseline sweep."
  []
  (when-not (System/getenv "OPENROUTER_API_KEY")
    (throw (ex-info "OPENROUTER_API_KEY must be set for this experiment" {})))
  (runner/start!)
  (let [ctx (deref @(requiring-resolve 'runner/system-state))]
    (try
      (install-experimental-seeds! ctx)
      (let [outcome (live/run-classify-only! ctx
                      {:dir-suffix "-with-specialized-seeds"})]
        (println "\nExperiment dir:" (:dir outcome))
        (println "Specialized seed UUIDs (for grepping in saved EDNs):")
        (doseq [s experimental-seeds]
          (println "  " (:target-id s) (-> s :body :summary (subs 0 50)) "..."))
        outcome)
      (finally
        (runner/stop!)))))

(comment
  ;; From REPL:
  (require '[c2d-ood-specialized-seeds-experiment :as exp] :reload)
  (exp/run-experiment!))
