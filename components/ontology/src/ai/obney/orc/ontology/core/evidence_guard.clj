(ns ai.obney.orc.ontology.core.evidence-guard
  "CC-4 (ADRs 0023, 0021) — the evidence guard, and the record of what it
   rejected.

   THE INCIDENT THIS EXISTS TO PREVENT. Three grounding judges once scored
   [1.0 0.0 0.0] on ONE response against ONE empty input context. They said
   so in their own feedback and differed only in their stance toward the
   missing evidence — one reasoned 'assuming the document exists…' and
   scored 1.0, two scored strictly against the empty context. A later
   fixture check proved the judged output was substantially grounded in the
   real source document, so those zeros were EVALUATION ARTIFACTS, not
   detected defects. They were consolidated into a living description as a
   genuine weakness anyway. Under ADR 0021 a weakness claim is no longer a
   note in a file — it is a lever that suppresses a behavior's retrieval, so
   the same failure now costs more.

   TWO LAYERS, IN THIS ORDER.

   1. A DETERMINISTIC check that owns the common case and NEVER calls a
      model. It resolves a delta's `:episodes` — HP-2 `[sheet-id tick-id]`
      occurrence pairs — against the judge evidence in the event store and
      answers two questions: does this occurrence have judge evidence at
      all, and does that evidence show the judge actually HAD input to
      judge? The starved shape is settled here, for free.

   2. An EXPLANATION VERIFIER for what the cheap check cannot settle — an
      occurrence whose judge left feedback too thin to read either way. It
      is a small judge of its own, asking whether an explanation references
      real evidence from the input, and it runs on a DIFFERENT model family
      from the judge under inspection. It is injected as a capability
      (`:ontology/evidence-verifier` on the context) so tests fake it and
      production gets the real one; consolidation is low-frequency, so this
      never sits on a hot path.

   WHAT THE GUARD IS NOT ALLOWED TO DO. It must not over-reject. A guard
   that suppresses real signal starves the learning loop exactly as the old
   whole-body validator did — the failure ADR 0021 exists to end. So the
   deterministic layer only excludes on POSITIVE evidence of starvation or
   on the absence of judge evidence entirely; anything ambiguous is handed
   to the verifier rather than dropped.

   AND EVERY EXCLUSION IS RECORDED. A silently dropped delta destroys the
   ability to measure how much of our historical evidence was starved — the
   number the migration (CC-12) and the maintainer proposal (CC-14) both
   depend on. Exclusions and stale-consolidation refusals are events, folded
   into their own read model, readable through `ontology/get-excluded-
   evidence` and `ontology/get-claim-delta-refusals`."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp
             :refer [defreadmodel]]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as mu]))

;; =============================================================================
;; What the deterministic layer keys on
;; =============================================================================

(def judge-score-event-type
  "The engine's judge-evidence event. The guard reads it by TYPE and TAG
   only — no code dependency on components/evaluation, which is deliberate:
   the ontology-side guard ships regardless of whether the engine-side
   `:no-evidence` outcome (ADR 0023, proposed to the maintainer as CC-14)
   ever lands."
  :judge/score-emitted)

(def ^:private starvation-patterns
  "Feedback shapes in which a judge SAYS its input was absent.

   This is not sentiment analysis and not a quality heuristic — every
   pattern here matches a judge REPORTING the absence of the material it
   was asked to judge, which is a fact about the evaluation harness rather
   than about the response. The last pattern is the 1.0-scoring judge from
   the incident: reasoning from an assumed document is the same starvation
   as scoring against an empty one, and it is the arm a score-based filter
   would have missed entirely."
  [#"(?i)\binput\s+context\b[^.]{0,60}?\b(?:was|is)\s+empty\b"
   #"(?i)\bempty\s+input\s+context\b"
   #"(?i)\b(?:no|missing|absent)\s+input\s+context\b"
   #"(?i)\bcontext\b[^.]{0,60}?\b(?:was|is)\s+empty\b"
   #"(?i)\bempty\s*\(\s*json\s*\{\s*\}\s*\)"
   #"(?i)\bcontext\b[^.]{0,40}?\bwas\s+not\s+provided\b"
   #"(?i)\bno\s+(?:source\s+)?(?:document|documents|evidence|context|input|excerpt|excerpts|material)\s+(?:was|were)?\s*(?:provided|supplied|available|given|present)\b"
   #"(?i)\b(?:document|source|context|input)\s+(?:was|is)\s+(?:not\s+provided|unavailable|missing)\b"
   #"(?i)\bnothing\s+(?:was\s+)?(?:provided|supplied)\s+to\s+(?:judge|evaluate|assess|score)\b"
   #"(?i)\bassum(?:e|ed|ing)\s+(?:that\s+)?the\s+(?:document|source|context|input)\s+(?:exists|existed|is\s+correct)\b"])

(def ^:private min-substantive-feedback-chars
  "Below this, a judge's feedback is too thin for the cheap check to read
   either way, so the occurrence goes to the verifier instead of being
   guessed at in either direction. Deliberately low: the deterministic
   layer must own the COMMON case, and real judge feedback in this system
   is a paragraph, not a phrase."
  40)

(defn starved-feedback?
  "True when a judge's own feedback reports that it had no input to judge."
  [feedback]
  (boolean (and (string? feedback)
                (some #(re-find % feedback) starvation-patterns))))

(defn judge-evidence-for-occurrence
  "Every judge-score event recorded for the `[sheet-id tick-id]`
   occurrence. Queried by the `:tick` tag — occurrence identity, not event
   identity: a static task-shape's sheet-id is shared across every turn, so
   the tick is what makes the reference resolvable (the SJ-1 join lesson) —
   then confirmed against the sheet so a tick reused under a different
   sheet cannot be mistaken for this occurrence's evidence."
  [{:keys [event-store tenant-id]} [sheet-id tick-id]]
  (when (and event-store tenant-id tick-id)
    (->> (es/read event-store {:types #{judge-score-event-type}
                               :tags #{[:tick tick-id]}
                               :tenant-id tenant-id})
         (into [])
         (filterv #(= sheet-id (:sheet-id %))))))

(defn classify-occurrence
  "The DETERMINISTIC verdict for one occurrence. Never calls a model.

     :no-judge-evidence — nothing judged this occurrence. It is NOT a zero.
                          Conflating 'not judged' with 'judged badly' is
                          precisely what ADR 0023 exists to end.
     :judge-abstained   — judge evidence exists but carries no score (the
                          engine-side `:no-evidence` outcome, CC-14, or any
                          future abstention). Also not a zero.
     :starved-evidence  — a judge reported it had no input to judge. The
                          score, whatever it is, is an artifact of the
                          harness.
     :grounded          — a scored judgement with substantive feedback that
                          says nothing about missing input.
     :unsettled         — scored, but the feedback is too thin for the cheap
                          check to read. The verifier takes over HERE and
                          only here.

   Starvation is judged across ALL of the occurrence's judges, not the
   worst-scoring one: emptiness is a property of the shared input context,
   so one judge naming it condemns the occurrence's evidence even when a
   sibling judge scored 1.0 off the same void — the exact shape of the
   incident."
  [ctx occurrence]
  (let [evidence (judge-evidence-for-occurrence ctx occurrence)]
    (cond
      (empty? evidence)
      {:verdict :excluded :reason :no-judge-evidence :evidence-count 0}

      (some #(starved-feedback? (:feedback %)) evidence)
      {:verdict :excluded :reason :starved-evidence
       :evidence-count (count evidence)
       :judge-names (mapv :judge-name evidence)}

      (every? #(nil? (:score %)) evidence)
      {:verdict :excluded :reason :judge-abstained
       :evidence-count (count evidence)}

      (some (fn [e]
              (and (some? (:score e))
                   (string? (:feedback e))
                   (>= (count (str/trim (:feedback e)))
                       min-substantive-feedback-chars)))
            evidence)
      {:verdict :grounded :evidence-count (count evidence)}

      :else
      {:verdict :unsettled :reason :feedback-too-thin
       :evidence-count (count evidence)
       :evidence evidence})))

;; =============================================================================
;; Layer 2 — the explanation verifier (injected capability)
;; =============================================================================

(def verifier-model
  "A DIFFERENT model family from the grounding judges under inspection —
   asking a judge from the same family whether a sibling's explanation is
   grounded reproduces the sibling's blind spot. Pinned as a default the
   caller can override, mirroring RR-2's config-slot pattern."
  "qwen/qwen3.5-flash-02-23")

(def ^:private verifier-instruction
  "You are checking whether an evaluation's written explanation REFERENCES REAL
EVIDENCE from the material it was given, or whether it was produced without
anything to look at.

You are NOT judging whether the evaluation's verdict was correct, kind, or
well-calibrated. Exactly one question: does this explanation cite something
concrete that could only have come from real input — a quoted phrase, an
identifier, a number, a named section — or is it generic, hypothetical, or
explicitly reasoning about material it says it did not receive?

Answer with `true` when the explanation is anchored in real material, and
`false` when it is not. When you cannot tell, answer `false` — an evaluation
whose grounding cannot be established must not become a durable claim.")

(defn- verifier-workflow
  [model]
  (let [orc (requiring-resolve 'ai.obney.orc.orc-service.interface/workflow)
        blackboard (requiring-resolve 'ai.obney.orc.orc-service.interface/blackboard)
        llm (requiring-resolve 'ai.obney.orc.orc-service.interface/llm)]
    (orc "ontology-evidence-explanation-verifier"
         (blackboard {:explanation :string
                      :grounded-verdict :string})
         (llm "verify-explanation"
              :model model
              :instruction verifier-instruction
              :reads [:explanation]
              :writes [:grounded-verdict]
              :options {:max-retries 3
                        :retry-delay-ms [500 1500 3000]
                        :use-function-calling? true}))))

(def ^:private surrounding-quoting
  "Whitespace and the quoting a model wraps a one-word answer in. Deliberately
   TINY: every character added here is a new way for prose to be mistaken for a
   verdict. Markdown emphasis and trailing punctuation are NOT in it — a field
   that needs them stripped is no longer a one-word answer."
  #"(?:^[\s\"'`]+)|(?:[\s\"'`]+$)")

(defn grounding-established?
  "Did the verifier's `:grounded-verdict` field ESTABLISH grounding?

   True only when the field, after trimming whitespace and any surrounding
   quoting, is exactly the word `true` (any case). Prose, a template echo, a
   multi-word answer, an empty field, `nil` — all of it means grounding was NOT
   established, which is the same answer as `false`.

   WHY SO STRICT (CC-4b measured this; evidence in orc-sessions
   `doc/build-timeline/evidence/cc4b/`). The predecessor asked only whether the
   field contained the token `true` anywhere. On 8 of 30 real verifier calls the
   forced tool call was dropped upstream and dscloj silently reissued in marker
   mode, so the field came back carrying leaked `</think>` reasoning and/or an
   echoed `{grounded-verdict}` template. In three of those the model had stated
   `false` — once six times over — and an incidental `true`, quoted from the
   instruction or from the very explanation under inspection, was enough to
   return GROUNDED. A verdict is a one-word answer; anything longer is the
   model thinking out loud, and thinking out loud is not a verdict.

   This is the contract the guard's fail-CLOSED promise rests on, so it is
   asymmetric ON PURPOSE: a field that fails to parse costs a deferred claim,
   which is re-derivable; a field misread as `true` costs a fabricated one,
   which is not.

   ONE OF THE EIGHT IS NOT OURS, recorded here so it is not re-diagnosed. In
   arm-2 call 04 the field was the literal `{grounded-verdict}` placeholder
   while the model's final block was a correctly formed
   `[[ ## grounded-verdict ## ]] true`. That verdict was lost UPSTREAM:
   `dscloj.core/parse-output`'s `extract-field` (fork 41ef3e20,
   `src/dscloj/core.clj`) selects with `re-find`, which returns the FIRST
   marker occurrence. Replaying that captured 9,643-char response through the
   same regex yields, in order, `{grounded-verdict}`, `{true/false}`, `true`,
   `true` — the two real answers are the last two, and dscloj returns the
   first. Our side never sees the raw text; it reads an already-parsed field,
   so there is no occurrence for us to choose. Strict extraction cannot
   recover a verdict dscloj discarded; it only guarantees the loss lands on
   the safe side. Reported as an upstream finding alongside ADR 0025's
   `tool_choice` key fix, which removes the marker fallback that produces
   these fields in the first place."
  [raw-verdict]
  (= "true" (-> raw-verdict
                str
                (str/replace surrounding-quoting "")
                (str/lower-case))))

(defn default-explanation-verifier
  "The REAL layer-2 verifier: a one-node LLM workflow asking whether an
   explanation references real evidence from the input.

   orc-service is resolved lazily (the ontology's established pattern for
   optional heavy paths) so requiring this namespace never drags an LLM
   stack onto the load path, and so the deterministic layer is provably
   reachable without one.

   Fails CLOSED, and `grounding-established?` is what makes that true rather
   than aspirational: an unreachable verifier, a failed workflow AND a verdict
   field that is anything other than a one-word `true` all return false. An
   evidence-starved claim that slips through is the failure this slice exists
   to prevent; a claim deferred because grounding could not be established is
   recorded as an exclusion and can be re-derived."
  [ctx {:keys [explanation model]}]
  (try
    (let [build! (requiring-resolve 'ai.obney.orc.orc-service.interface/build-workflow!)
          execute (requiring-resolve 'ai.obney.orc.orc-service.interface/execute)
          sheet-id (build! ctx (verifier-workflow (or model verifier-model)))
          result (execute ctx sheet-id {:explanation (str explanation)})]
      (if (= :success (:status result))
        (grounding-established? (get-in result [:outputs :grounded-verdict]))
        (do (mu/log ::verifier-workflow-failed :status (:status result))
            false)))
    (catch Exception e
      (mu/log ::verifier-unavailable :error (.getMessage e))
      false)))

(defn- verifier-fn
  "The injected-capability seam: the context may carry
   `:ontology/evidence-verifier`; otherwise the real implementation is
   used."
  [ctx]
  (or (:ontology/evidence-verifier ctx) default-explanation-verifier))

;; =============================================================================
;; The delta-level verdict
;; =============================================================================

;; -----------------------------------------------------------------------------
;; CC-6 — DECLARED EVIDENCE BASIS
;; -----------------------------------------------------------------------------
;; The guard's own docstring already says what it is for: it rejects evidence it
;; CANNOT TRUST, not evidence that is ABSENT BY DESIGN. `:from-legacy-corpus`
;; was that second category arriving with a name too narrow for it. CC-6 widens
;; the name without widening the permission.
;;
;; The rule is ONE rule, not one per writer: a delta is admitted on its
;; declaration only when it names NO occurrence. A mechanical writer that DOES
;; name occurrences is resolved normally — declaring a basis never buys a pass
;; on evidence that could have been checked. That is also what keeps CC-7
;; honest, since a claim admitted this way carries no supporting episode and
;; therefore cannot reach `:validated` however much support it accumulates.

(defn declared-basis
  "What a delta says its content rests on, normalised.

   Absent means DERIVED, never `nil`: a pre-CC-6 delta carrying only
   `:from-legacy-corpus` keeps exactly the meaning it had, and everything else
   defaults to `:judged-occurrences` — the strict arm. A new field cannot
   loosen an old delta."
  [{:keys [evidence-basis from-legacy-corpus]}]
  (or evidence-basis
      (if from-legacy-corpus :legacy-corpus :judged-occurrences)))

(def declared-bases-admitted
  "The bases that stand WITHOUT resolvable judge evidence, because they assert
   something other than a judgement:

     :legacy-corpus             a prior corpus, whose judgements predate the
                                guard and cannot be re-resolved.
     :classification-signature  the deterministic input the classifier keyed
                                on — a fact about what was asked, recorded
                                before anything could have judged the answer.
     :emitted-artifact          a verbatim artifact the engine itself produced,
                                recorded from the completion event that
                                produced it.
     :authored                  designer-written corpus knowledge (CC-9d). A
                                curated guard has no occurrences by
                                construction: nothing judged it, because
                                nothing could have. Authorship is a true,
                                auditable statement about where the content
                                came from, which is precisely the category
                                this set exists for.

   `:judged-occurrences` is deliberately ABSENT: it is the arm that means
   'resolve my episodes', and a delta declaring it while naming none is exactly
   the ungrounded assertion this guard exists to refuse.

   CC-9d ADDED `:authored` HERE RATHER THAN BUILDING A SECOND EXEMPTION ROUTE,
   and that is the whole point: the seeding path needs exactly what this set
   already grants — admission for a delta that names no occurrence — so a
   parallel bypass would be a second way past the guard, which is how a guard
   stops meaning anything. What authorship additionally buys (enforcement) is
   granted downstream in the claim fold, where the spec puts it, NOT here. The
   ONE rule below is unchanged: a delta that DOES name occurrences is resolved
   normally whatever it declares."
  #{:legacy-corpus :classification-signature :emitted-artifact :authored})

(defn- occurrence-explanations
  [occurrence-verdicts]
  (->> occurrence-verdicts
       (mapcat (comp #(map :feedback %) :evidence))
       (remove str/blank?)
       vec))

(defn evidence-verdict
  "The guard's verdict for ONE delta.

   Returns `{:grounded? bool :reason <keyword or nil>
             :settled-by :deterministic|:verifier|:declared-provenance
             :episodes <the delta's episodes>}`.

   A delta stands when AT LEAST ONE of its occurrences is grounded — a
   consolidation that cites three turns, one of which was starved, still
   learned something real from the other two, and rejecting it would be the
   protection-induced starvation ADR 0021 removes. It is excluded only when
   NONE of its occurrences can be established, and the reported reason is
   the first occurrence's, which is what a diagnosis needs.

   A delta that names no occurrence and DECLARES a basis in
   `declared-bases-admitted` is grounded by DECLARED PROVENANCE: it asserts a
   prior corpus, a classifier input or an emitted artifact rather than a judge
   event, so there is no judge evidence to resolve and no starved score to
   catch. A delta that DOES name occurrences is checked normally whatever it
   declares — CC-6's rule adds a category of writer, never an exemption from
   evidence that exists. `:basis` is reported on the verdict so an exclusion
   record says WHICH declaration was (or was not) honoured."
  [ctx {:keys [episodes] :as delta}]
  (let [episodes (vec episodes)
        basis (declared-basis delta)]
    (cond
      (and (empty? episodes) (contains? declared-bases-admitted basis))
      {:grounded? true :settled-by :declared-provenance :basis basis
       :episodes episodes}

      (empty? episodes)
      {:grounded? false :reason :no-episodes :settled-by :deterministic
       :episodes episodes}

      :else
      (let [verdicts (mapv #(classify-occurrence ctx %) episodes)]
        (cond
          (some #(= :grounded (:verdict %)) verdicts)
          {:grounded? true :settled-by :deterministic :episodes episodes}

          ;; Layer 2 runs ONLY on what layer 1 could not settle. When every
          ;; occurrence was settled deterministically — the starved shape
          ;; included — no model is called at all.
          (some #(= :unsettled (:verdict %)) verdicts)
          (let [unsettled (filterv #(= :unsettled (:verdict %)) verdicts)
                explanations (occurrence-explanations unsettled)
                verified? (boolean
                            (some (fn [explanation]
                                    ((verifier-fn ctx) ctx {:explanation explanation}))
                                  explanations))]
            (if verified?
              {:grounded? true :settled-by :verifier :episodes episodes}
              {:grounded? false :reason :unverified-explanation
               :settled-by :verifier :episodes episodes}))

          :else
          {:grounded? false
           :reason (:reason (first (filterv #(= :excluded (:verdict %)) verdicts)))
           :settled-by :deterministic
           :episodes episodes})))))

;; =============================================================================
;; The record of what was rejected
;; =============================================================================
;;
;; These facts live in their own read model rather than in
;; :ontology/descriptions. A description's projection holds what a target
;; BELIEVES; an exclusion and a refusal are records of writes that never
;; happened. Keeping them separate also means neither fact's arrival
;; invalidates the description cache generation that CC-3's assembled body
;; is served from.

(def guard-events
  #{:ontology/promotion-evidence-excluded
    :ontology/claim-deltas-refused})

(defmulti claim-guard*
  "Apply one guard event to the claim-guard read-model state.
   State: {granularity {target-id {:excluded-evidence [...] :refusals [...]}}}"
  (fn [_state event] (:event/type event)))

(defmethod claim-guard* :default [state _event] state)

(defmethod claim-guard* :ontology/promotion-evidence-excluded
  [state event]
  (update-in state [(:granularity event) (:target-identifier event) :excluded-evidence]
             (fnil conj [])
             {:reason (:reason event)
              :episodes (:episodes event)
              :operation (:operation event)
              :kind (:kind event)
              :content (:content event)
              :settled-by (:settled-by event)
              :excluded-at (:excluded-at event)}))

(defmethod claim-guard* :ontology/claim-deltas-refused
  [state event]
  (update-in state [(:granularity event) (:target-identifier event) :refusals]
             (fnil conj [])
             {:reason (:reason event)
              :attempted-version (:attempted-version event)
              :current-version (:current-version event)
              :delta-count (:delta-count event)
              :refused-at (:refused-at event)}))

(defreadmodel :ontology claim-guard
  {:events guard-events :version 1}
  [state event] (claim-guard* state event))

(defn get-excluded-evidence
  "Every delta the evidence guard refused to record for this target,
   oldest first. `[]` when none."
  [ctx granularity target-id]
  (or (get-in (rmp/project ctx :ontology/claim-guard)
              [granularity target-id :excluded-evidence])
      []))

(defn get-claim-delta-refusals
  "Every consolidation refused for this target, oldest first. `[]` when
   none."
  [ctx granularity target-id]
  (or (get-in (rmp/project ctx :ontology/claim-guard)
              [granularity target-id :refusals])
      []))
