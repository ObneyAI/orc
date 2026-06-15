(ns ai.obney.orc.ontology.core.cq-runner
  "S15 — Competency-question runner with three-layer negation posture.

   The runner is the acceptance-test half of the ORSD contract: each
   competency question in an ontology's stored spec (S14) is evaluated
   against the built graph, producing per-CQ result events and a
   pass-rate-over-time health metric.

   ## Three-layer routing rule

   Each CQ is classified into exactly ONE layer at evaluation time. The
   routing rule is purely STRUCTURAL — it does not consume content
   semantics, only the SHAPE of the question. Quality (the verdict
   itself) is decided at the layer's mechanism — Layer 1 by the
   projection, Layer 2/3 by the judge over the retrieved evidence.

     Layer 1 (deterministic, NO LLM) — STRUCTURAL EXISTENCE shape:
       'Is there a X concept?' / 'Does X exist?'
       Resolved by scanning the concepts projection for a label OR URI
       fragment match. Result is :pass when a match is found in scope,
       :fail otherwise. Layer 1 NEVER produces :unknown — structural
       existence is a closed-world boolean against the projection.

     Layer 2 (judge over retrieved evidence) — SEMANTIC EXISTS:
       'Which Xs have Y?' / 'Are there any Xs with Y?' / 'Which Xs
       are Z?'
       Resolved by hybrid-search retrieval into the judge. The judge
       evaluates the closed-world evidence and returns :pass when
       evidence affirms, :fail when the closed-world evidence DENIES
       (e.g., enumerated category lacks the target), :unknown when the
       evidence is silent on the relevant fact-kind.

     Layer 3 (judge with explicit-unknown posture) — same mechanism as
       Layer 2; the difference is INTENT, not routing. Anything not
       Layer-1-shaped flows through the same retrieve-then-judge path.
       The 'Layer 3' designation in the EVENT records is set when the
       judge VERDICT is :unknown — that signals 'the graph lacks this
       kind of fact', the closed-world unknown that round-3's three-
       layer posture demanded as a first-class outcome.

   ## Why no hardcoded phrase matching as a quality gate

   Layer 1's regex is a STRUCTURAL ROUTING heuristic. The QUALITY of
   the verdict it produces comes from the projection lookup — a
   deterministic structural fact, not a string match against the
   question text. If the regex misses (the question is phrased
   differently), the CQ falls through to Layer 2's judge — no quality
   regression, just one LLM call instead of zero. The judge does the
   quality work for Layer 2 + Layer 3; the production prompt below is
   what enforces the three-way :pass / :fail / :unknown distinction.

   ## Public surface

   `evaluate-cqs!` — runs the CQ set from the stored ORSD spec for an
   ontology-id, emits one :ontology/record-cq-evaluation command per
   CQ, returns the per-CQ verdicts plus the derived graph-health
   summary. Layer-1 verdicts go through the projection; Layer-2/3
   verdicts call the supplied `:judge-fn` (default: a real LLM call
   via dscloj/predict against the runner's configured provider).

   The judge-fn protocol — `(fn [{:keys [question evidence]}]
   {:verdict :reasoning :evidence-uris :gaps})` — is INJECTABLE so
   tests can wire a controlled judge (verifying mechanics through the
   public interface) and production wires the real LLM. The production
   prompt that REAL judge applies is `judge-prompt-template` below."
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [clojure.string :as str]))

;; =============================================================================
;; Production judge prompt — three-way distinction (load-bearing)
;; =============================================================================

(def judge-prompt-template
  "The three-way :pass / :fail / :unknown distinction is enforced by
   the prompt body itself — the prompt explicitly distinguishes the
   closed-world NO (the graph DOES say) from the gap (the graph LACKS
   the fact). The 'do not default to comforting middle' clause is
   non-negotiable: round-3 Q7's explicit-unknown posture demanded
   :unknown be a real, named verdict — not a fallback. The prompt
   ships in production verbatim; tests reference this same string when
   asserting the three-way distinction language is present in prod
   code (not lazily in tests)."
  (str
   "You are evaluating a competency question against a KNOWLEDGE GRAPH that "
   "has been retrieved for you. The graph is an EVOLVING, CONTINUOUSLY-GROWING "
   "ontology — it is OPEN-WORLD and assumed INCOMPLETE by default. Absence of "
   "a fact almost always means 'not extracted yet', NOT 'false'. Decide one of "
   "THREE outcomes:\n"
   "\n"
   "  :pass    — the retrieved evidence CONTAINS information that answers the\n"
   "            question affirmatively (the answer is YES, and the evidence\n"
   "            shows it).\n"
   "\n"
   "  :fail    — the graph EXPLICITLY says NO. This requires a POSITIVE\n"
   "            negative-or-closure signal in the evidence, not mere absence:\n"
   "              * a direct negating edge/attribute (e.g. subject :status\n"
   "                \"retired\", X owl:disjointWith Y where the subject is an X), OR\n"
   "              * an EXPLICIT completeness/closure assertion over the relevant\n"
   "                set (a closure axiom, owl:oneOf, or a :closed? marker —\n"
   "                surfaced in the COMPLETENESS / CLOSURE block) AND the target\n"
   "                is absent from that closed set.\n"
   "            Without such a signal you may NOT conclude :fail.\n"
   "\n"
   "  :unknown — the graph is SILENT on what the question asks, and there is NO\n"
   "            explicit completeness/closure assertion making that silence\n"
   "            meaningful. This is a real, named verdict — the correct answer\n"
   "            for an incomplete graph. Specifically:\n"
   "              * the graph records the relation for SOME subjects but is\n"
   "                silent for THIS subject, with no closure assertion -> :unknown\n"
   "                (the presence of an enumeration for OTHERS is NOT a\n"
   "                completeness assertion — do not treat it as one), AND\n"
   "              * the graph has no facts of the kind the question asks for,\n"
   "                with no closure assertion  ->  :unknown.\n"
   "\n"
   "CRITICAL: do NOT default to :unknown when the evidence clearly answers (a\n"
   "positive :pass or an explicit-signal :fail). But when the graph is merely\n"
   "silent and nothing asserts completeness, :unknown is REQUIRED — inferring\n"
   ":fail from the absence of a fact in an open-world graph is a bug.\n"
   "\n"
   "Cite evidence-uris from the retrieved set that drove your verdict. If\n"
   "verdict is :unknown, name the SPECIFIC kind of fact that would be needed\n"
   "to answer (e.g., 'retirement-status edges', 'oscar-count attributes',\n"
   "'collaboration relationships'). This is the :gaps field — actionable\n"
   "for the next grow cycle.\n"
   "\n"
   "QUESTION: {question}\n"
   "\n"
   "RETRIEVED EVIDENCE:\n"
   "{evidence}\n"))

(defn render-judge-prompt
  "Render the production prompt with the question + evidence text. Kept
   public so a downstream caller wiring a real LLM provider can render
   the EXACT production prompt the judge would see."
  [question evidence-text]
  (-> judge-prompt-template
      (str/replace "{question}" question)
      (str/replace "{evidence}" evidence-text)))

;; =============================================================================
;; Layer routing (purely structural)
;; =============================================================================

(def ^:private layer-1-regexes
  "Structural shapes a Layer-1 deterministic check can answer without
   the judge. The match captures the TERM (whatever comes between the
   shape's anchors) — the projection-side checker then looks for a
   concept whose label or URI fragment includes the term."
  [#"(?i)^is there an? (.+?) concept\??$"
   #"(?i)^does an? (.+?) concept exist\??$"
   #"(?i)^does (.+?) exist (?:as a concept )?(?:in the (?:graph|ontology) )?\??$"])

(defn classify-cq-layer
  "Decide the routing layer for a CQ. Returns
   {:layer :layer-1-structural :term <captured>} OR
   {:layer :layer-2-semantic-exists}.

   The actual verdict :layer recorded on the event may be
   :layer-3-explicit-unknown when the judge says :unknown — that's
   a verdict-driven label, not a routing decision (see
   `effective-layer` below)."
  [cq-text]
  (or (some (fn [re]
              (when-let [m (re-find re cq-text)]
                {:layer :layer-1-structural
                 :term  (str/lower-case (second m))}))
            layer-1-regexes)
      {:layer :layer-2-semantic-exists}))

;; =============================================================================
;; Layer 1: structural existence
;; =============================================================================

(defn- concept-labels
  "Every label-shaped string on the concept — primary :label plus any
   S04 :labels [{:value :lang} ...]. Lowercased for case-insensitive
   substring matching."
  [c]
  (let [labels (cons (:label c)
                     (map :value (:labels c)))]
    (keep #(some-> % str/lower-case) labels)))

(defn layer-1-verdict
  "Deterministic verdict via the concepts projection. NO LLM. Returns
   `{:verdict :reasoning :evidence-uris :judged-by? :layer :gaps}`.

   :pass when at least one in-scope concept's label OR URI fragment
   contains the structural term; :fail otherwise. Layer 1 never returns
   :unknown — structural existence is a closed-world boolean against
   the projection."
  [{:keys [ontology-id term get-concepts-fn]}]
  (let [term-lc (some-> term str/lower-case)
        all (get-concepts-fn {})
        scoped (filter #(= ontology-id (:ontology-id %)) all)
        hit? (fn [c]
               (or (some #(str/includes? % (or term-lc "")) (concept-labels c))
                   (when-let [uri (:uri c)]
                     (str/includes? (str/lower-case uri) (or term-lc "")))))
        found (filterv hit? scoped)]
    {:verdict       (if (seq found) :pass :fail)
     :reasoning     (if (seq found)
                      (str "Structural existence verified: concept(s) "
                           (str/join ", " (mapv :uri found))
                           " match term '" term "'")
                      (str "No concept with label or URI fragment matching '"
                           term "' in scoped graph (closed-world NO)"))
     :evidence-uris (mapv :uri found)
     :judged-by?    false
     :layer         :layer-1-structural}))

;; =============================================================================
;; Layer 2/3: retrieve then judge
;; =============================================================================

(defn render-completeness-block
  "Render the EXPLICIT completeness/closure signals the judge may use to
   ground a :fail on absence. Built from S07 axioms (disjointness — a
   subject typed under a class disjoint with the asked class is an explicit
   NO) and any concept `:closed?` markers (S11 closure convention). When
   nothing explicit is present, the block states the graph is open-world so
   the judge defaults absence to :unknown — NOT :fail."
  [{:keys [axioms closed-concepts]}]
  (let [disjoint (:disjointness axioms)
        disjoint-lines (when (seq disjoint)
                         (mapv (fn [pair] (str "  disjoint: " (pr-str pair))) disjoint))
        closed-lines (when (seq closed-concepts)
                       (mapv (fn [c] (str "  closed-set: " (:uri c)
                                          " [" (or (:label c) "?") "]"))
                             closed-concepts))
        lines (vec (concat disjoint-lines closed-lines))]
    (if (seq lines)
      (str/join "\n" lines)
      (str "  (none — the graph is OPEN-WORLD / incomplete: absence of a fact\n"
           "   means :unknown, NOT :fail, unless a direct negating edge is present)"))))

(defn render-evidence-text
  "Serialize the retrieved hits + the graph's CURRENT concept and
   relationship contents (OPEN-WORLD — not asserted complete) + the
   explicit completeness/closure signals for the judge.

   The completeness block is what lets the judge distinguish a grounded
   :fail (a direct negating edge, or an explicit closure assertion with the
   target absent) from :unknown (the graph is merely silent). Mere presence
   of an enumeration for OTHER subjects is NOT completeness."
  [{:keys [retrieved concepts relationships axioms closed-concepts]}]
  (let [retrieved-block
        (if (seq retrieved)
          (str/join "\n"
                    (mapv (fn [r]
                            (str "  " (:uri r)
                                 " [label: " (or (:label r) "?") "]"
                                 " " (or (:description r) "")))
                          retrieved))
          "  (no retrieved hits)")
        concepts-block
        (if (seq concepts)
          (str/join "\n"
                    (mapv (fn [c]
                            (str "  " (:uri c)
                                 " [label: " (or (:label c) "?") "]"))
                          concepts))
          "  (no concepts in scope)")
        edges-block
        (if (seq relationships)
          (str/join "\n"
                    (mapv (fn [r]
                            (str "  " (:source-uri r)
                                 " " (:predicate r)
                                 " " (:target-uri r)))
                          relationships))
          "  (no relationships in scope)")]
    (str "TOP RETRIEVED HITS (best-match for the question):\n"
         retrieved-block
         "\n\nALL CONCEPTS CURRENTLY IN SCOPE (open-world — NOT asserted complete):\n"
         concepts-block
         "\n\nALL RELATIONSHIPS CURRENTLY IN SCOPE (open-world — NOT asserted complete):\n"
         edges-block
         "\n\nEXPLICIT COMPLETENESS / CLOSURE / DISJOINTNESS SIGNALS\n"
         "(the ONLY basis for concluding :fail on an absent fact):\n"
         (render-completeness-block {:axioms axioms :closed-concepts closed-concepts}))))

(defn layer-2-or-3-verdict
  "Retrieve evidence, build the closed-world text block, and dispatch
   to the supplied `:judge-fn`. The judge-fn protocol is
   `(fn [{:keys [question evidence]}] {:verdict :reasoning
   :evidence-uris :gaps})` — pluggable so tests can inject a controlled
   judge and production wires the real LLM.

   Returns the verdict map with `:judged-by? true` and `:layer` set per
   the verdict — `:layer-3-explicit-unknown` when the judge returns
   :unknown (verdict-driven label) else `:layer-2-semantic-exists`.

   When the judge returns no verdict at all (an error path), the
   runner RAISES — there is NO silent fallback to :unknown. round-3 Q7's
   explicit-unknown is a JUDGE OUTPUT, not a runner default."
  [{:keys [ontology-id cq-text judge-fn
           hybrid-search-fn get-concepts-fn get-relationships-fn get-axioms-fn ctx]}]
  (let [search-result (hybrid-search-fn
                       (cond-> {:event-store (:event-store ctx)}
                         (:tenant-id ctx) (assoc :tenant-id (:tenant-id ctx))
                         (:cache ctx)     (assoc :cache (:cache ctx)))
                       {:query-text   cq-text
                        :ontology-ids #{ontology-id}
                        :limit        25})
        retrieved (vec (or (:results search-result)
                           (:graph-results search-result)
                           []))
        all-concepts (filterv #(= ontology-id (:ontology-id %))
                              (get-concepts-fn {}))
        all-rels (filterv (fn [r]
                            (or (nil? (:ontology-id r))
                                (= ontology-id (:ontology-id r))))
                          (get-relationships-fn ctx))
        ;; S15 open-world: surface EXPLICIT closure signals so the judge can
        ;; ground a :fail on absence (S07 disjointness axioms + S11 :closed?
        ;; markers). get-axioms-fn is optional/back-compat — without it the
        ;; completeness block is empty and the prompt's open-world default
        ;; (absence ⇒ :unknown) still holds.
        axioms (when get-axioms-fn (get-axioms-fn ctx ontology-id))
        closed-concepts (filterv :closed? all-concepts)
        evidence-text (render-evidence-text
                       {:retrieved     retrieved
                        :concepts      all-concepts
                        :relationships all-rels
                        :axioms        axioms
                        :closed-concepts closed-concepts})
        result (judge-fn {:question cq-text :evidence evidence-text})
        verdict (:verdict result)
        evidence-uris (vec (or (:evidence-uris result) []))]
    (when-not (contains? #{:pass :fail :unknown} verdict)
      (throw (ex-info (str "CQ runner: judge-fn returned invalid verdict: "
                           (pr-str verdict))
                      {:cq-text cq-text :judge-output result})))
    ;; Grounding discipline (live-verify hardening): a :pass MUST be grounded
    ;; in evidence — but a real LLM judge often returns a CORRECT :pass with
    ;; the evidence in its prose reasoning while leaving :evidence-uris empty.
    ;; The old guard THREW on that, aborting the entire CQ batch. Instead:
    ;;   - :pass + empty evidence-uris + retrieval WAS non-empty → the judge
    ;;     demonstrably had evidence; ground the :pass in the retrieved URIs.
    ;;   - :pass + empty evidence-uris + retrieval ALSO empty → truly
    ;;     ungrounded affirmation (hallucination); downgrade to :unknown
    ;;     (open-world: we cannot confirm), with an actionable gap.
    ;; This preserves "no ungrounded :pass" without the brittleness of a
    ;; whole-run abort on a structured-field omission.
    (let [retrieved-uris (vec (keep :uri retrieved))
          ;; The judge sees BOTH the retrieved hits AND the full concept/
          ;; relationship enumeration (render-evidence-text). A :pass is
          ;; "grounded" if the judge had ANY of that to work with. Without
          ;; embeddings, retrieved is often empty for a sentence-shaped CQ,
          ;; yet the enumeration legitimately backs the :pass.
          graph-had-evidence? (or (seq retrieved) (seq all-concepts) (seq all-rels))
          [verdict* evidence-uris* gaps* ground-note]
          (cond
            (or (not= :pass verdict) (seq evidence-uris))
            [verdict evidence-uris (vec (or (:gaps result) [])) nil]

            ;; :pass, empty evidence-uris, but the retrieval signal had hits →
            ;; ground in the retrieved URIs.
            (seq retrieved-uris)
            [:pass retrieved-uris (vec (or (:gaps result) []))
             :grounded-in-retrieval]

            ;; :pass, empty evidence-uris, no retrieval hits, but the graph
            ;; enumeration the judge saw was non-empty → trust the reasoning,
            ;; keep :pass, flag that grounding came from the enumeration.
            graph-had-evidence?
            [:pass [] (vec (or (:gaps result) [])) :grounded-in-enumeration]

            ;; :pass over a genuinely EMPTY graph → nothing could ground it →
            ;; downgrade to :unknown (open-world: cannot confirm).
            :else
            [:unknown []
             (vec (or (seq (:gaps result))
                      ["affirmation over an empty graph — nothing to ground it (downgraded from :pass)"]))
             :downgraded-ungrounded-pass])]
      (cond-> {:verdict       verdict*
               :reasoning     (or (:reasoning result) "")
               :evidence-uris evidence-uris*
               :judged-by?    true
               :layer         (if (= :unknown verdict*)
                                :layer-3-explicit-unknown
                                :layer-2-semantic-exists)
               :gaps          gaps*}
        ground-note (assoc :grounding-note ground-note)))))

;; =============================================================================
;; Public runner
;; =============================================================================

(defn evaluate-cq
  "Evaluate ONE CQ. Pure routing + verdict computation — does not emit
   commands. Used internally by `evaluate-cqs!` and externally by
   callers that want a verdict without persisting an event (e.g.,
   dry-runs).

   `opts` keys:
     :ontology-id          — REQUIRED.
     :cq-text              — REQUIRED.
     :judge-fn             — REQUIRED (function; see layer-2-or-3-verdict
                             docstring for protocol).
     :hybrid-search-fn     — REQUIRED. (fn [ctx opts] result)
     :get-concepts-fn      — REQUIRED. (fn [opts] [concept ...])
     :get-relationships-fn — REQUIRED. (fn [ctx] [relationship ...])
     :ctx                  — REQUIRED. Read-model context.

   Returns `{:cq-text :verdict :reasoning :evidence-uris :judged-by?
   :layer :gaps}`."
  [{:keys [cq-text] :as opts}]
  (let [{:keys [layer term]} (classify-cq-layer cq-text)
        verdict (case layer
                  :layer-1-structural
                  (layer-1-verdict (assoc opts :term term))
                  :layer-2-semantic-exists
                  (layer-2-or-3-verdict opts))]
    (assoc verdict :cq-text cq-text)))

(defn- emit-cq-event!
  "Dispatch the :ontology/record-cq-evaluation command. Goes through
   cp/process-command — schema validation fires; no bare es/append."
  [ctx ontology-id cq-index verdict]
  (cp/process-command
   (assoc ctx :command
          (cond-> {:command/name :ontology/record-cq-evaluation
                   :command/id (random-uuid)
                   :command/timestamp (time/now)
                   :ontology-id   ontology-id
                   :cq-index      cq-index
                   :cq-text       (:cq-text verdict)
                   :verdict       (:verdict verdict)
                   :reasoning     (:reasoning verdict)
                   :evidence-uris (vec (:evidence-uris verdict))
                   :judged-by?    (:judged-by? verdict)
                   :layer         (:layer verdict)}
            (seq (:gaps verdict)) (assoc :gaps (vec (:gaps verdict)))))))

(defn evaluate-cqs!
  "S15: run the CQ-runner pass for an ontology-id.

   - Reads the stored ORSD spec via the supplied `:get-ontology-spec-fn`.
   - For each CQ at index i, routes to Layer 1 or Layer 2/3, decides the
     verdict, and dispatches ONE :ontology/record-cq-evaluation command
     per CQ. The :judged-by? flag distinguishes deterministic Layer 1
     verdicts from LLM-judge Layer 2/3 verdicts.
   - Returns `{:ontology-id :evaluated [verdict ...] :graph-health <map>}`.

   `opts` keys:
     :ontology-id          — REQUIRED.
     :judge-fn             — REQUIRED.
     :get-ontology-spec-fn — REQUIRED. (fn [ctx ontology-id] spec-body).
     :get-graph-health-fn  — REQUIRED. (fn [ctx ontology-id] health-map).
     :hybrid-search-fn     — REQUIRED.
     :get-concepts-fn      — REQUIRED.
     :get-relationships-fn — REQUIRED.
     :ctx                  — REQUIRED. Grain context with :event-store +
                             :command-registry. Commands flow through
                             cp/process-command.

   When the spec has no :competency-questions field (or it's empty),
   returns `{:ontology-id :evaluated [] :graph-health nil
   :reason :no-cqs-in-spec}` — does NOT mint events.

   When the ontology has no stored ORSD spec at all, returns
   `{:ontology-id :evaluated [] :graph-health nil :reason :no-spec}`.

   Adversarial: a judge that returns nothing is NOT swallowed —
   layer-2-or-3-verdict raises, and this fn propagates. There is no
   silent fallback path."
  [{:keys [ontology-id ctx
           get-ontology-spec-fn
           get-graph-health-fn]
    :as opts}]
  (let [spec (get-ontology-spec-fn ctx ontology-id)
        cqs (vec (:competency-questions spec))]
    (cond
      (nil? spec)
      {:ontology-id ontology-id :evaluated [] :graph-health nil :reason :no-spec}

      (empty? cqs)
      {:ontology-id ontology-id :evaluated [] :graph-health nil :reason :no-cqs-in-spec}

      :else
      (let [verdicts
            (vec
             (map-indexed
              (fn [i cq-text]
                (let [v (evaluate-cq (assoc opts :cq-text cq-text))]
                  (emit-cq-event! ctx ontology-id i v)
                  v))
              cqs))]
        ;; Let the projection catch up before reading graph-health.
        (Thread/sleep 50)
        {:ontology-id   ontology-id
         :evaluated     verdicts
         :graph-health  (get-graph-health-fn ctx ontology-id)}))))
