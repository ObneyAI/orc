(ns ai.obney.orc.orc-service.w2p1-claim-holdout-test
  "W2P-1 — a CLAIM-ONLY holdout for R-Inject, and `:turn-id` on the record.

   The existing `*injection-holdout*` is all-or-nothing: it suppresses the
   whole `## Suggested patterns from corpus` block. An A/B run against it
   compares ~49K characters of corpus content against none, so a positive
   result cannot be attributed to any single claim, and the ~30x prompt-size
   gap is itself a confound.

   `*claim-holdout*` is the narrow control that experiment needs. It leaves the
   block in place and suppresses only CLAIM-DERIVED content — the weakness
   entries a body renders through `format-seed-body`, and the
   `Known weaknesses: …` sentence the same claims put inside the assembled
   `:summary` the render quotes as `Pattern guidance`.

   THE ACCEPTANCE TEST IS BYTE-LEVEL. Rendering the same payload under
   treatment and under the claim-only holdout must differ by EXACTLY the claim
   spans and nothing else: the test reconstructs those spans independently
   (from the fixture bodies, using the render's own documented formats), deletes
   them from the treated block, and asserts the result is byte-identical to the
   control block. `roughly the same size` is not the property — an arm that
   moves anything else is not a clean arm and the experiment it feeds is
   worthless.

   Fixture text is taken VERBATIM from a real recorded render (W2's
   preflight-ON-instruction.txt): the real SP-3 verification claim, the real
   coding tree-class summary with its real `Known weaknesses:` sentence, the
   real recommendations. A synthetic fixture would not exercise the two
   different summary shapes production actually produces (one assembled by
   `assemble-summary`, one a raw instruction string with no claim sentence at
   all)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.obney.orc.orc-service.core.todo-processors :as tp]
            [ai.obney.orc.orc-service.core.read-models :as rm]
            [ai.obney.orc.orc-service.test-helpers :as th]
            [ai.obney.orc.ontology.interface :as ontology]))

;; =============================================================================
;; Fixtures — real text from a real recorded render
;; =============================================================================

(def ^:private coding-class-weaknesses
  "The real weakness claims on the coding tree-class, verbatim from the W2
   preflight render."
  [{:trait "Vulnerable to 'hollow success' or verification cap penalties if shell commands are blank, failed to execute due to formatting, or if results are reported as successful despite captured error evidence."
    :recommended-alternative "Always inspect both exit codes and stdout/stderr strings for evidence of intended logic execution before generating a success report."
    :confidence 0.77
    :evidence-count 450}
   {:trait "Susceptible to namespace-mismatch failures and syntax errors (e.g., unbalanced delimiters or duplicate docstrings) if the model rewrites the entire file without incremental validation."
    :avoid-when "When the task allows for surgical apply_patch edits instead of whole-file rewrites."
    :recommended-alternative "Prefer surgical apply_patch for large files to preserve surrounding functions and namespace integrity."
    :confidence 0.75
    :evidence-count 400}])

(def ^:private coding-class-summary
  "The real assembled `:summary` for that class — an `assemble-summary` output,
   so it carries the SAME two claims a SECOND time inside its
   `Known weaknesses: …` sentence, between `Strengths` and `Avoid when`."
  (str "Capabilities: Encodes a robust Workspace Edit-Apply-Verify lifecycle using a sequential DSL pattern of gated code-read, LLM-based logic transformation, gated code-write, and shell-execution verification.."
       " Strengths: Enforces functional correctness over simple write-confirmation by mandating shell commands that exercise the specific logic branches of the change.."
       " Known weaknesses: " (str/join "; " (map :trait coding-class-weaknesses)) "."
       " Avoid when: Risk of significant collateral damage if whole-file rewrites (fs/write) are performed without preserving pre-existing unrelated functions or documentation.."))

(def ^:private coding-class-body
  {:summary coding-class-summary
   :capabilities ["Encodes a robust Workspace Edit-Apply-Verify lifecycle"]
   :strengths [{:trait "Enforces functional correctness over simple write-confirmation by mandating shell commands that exercise the specific logic branches of the change."
                :good-when "the change has behavioural consequences a command can exercise"
                :recommended-pattern "[:sequence [:code {:writes [:read]}] [:llm {:writes [:edit]}] [:code {:writes [:verify]}]]"
                :confidence 0.8
                :evidence-count 120}]
   :weaknesses coding-class-weaknesses
   :representative-uses ["Multi-file Clojure logic modifications"]
   :avoid-when ["Risk of significant collateral damage if whole-file rewrites are performed"]
   :version 11
   :consolidated-from-event-count 450})

(def ^:private sp3-claim-trait
  "THE claim W2 set out to measure, verbatim."
  "Frequently omits the mandatory verification step (shell/exec) after applying a code fix, resulting in incomplete work when the task requires confirming the fix via command execution.")

(def ^:private instruction-class-weaknesses
  [{:trait sp3-claim-trait
    :recommended-alternative "Enforce a guard that blocks task completion until the specified verification command has been executed and its output captured."
    :confidence 0.40
    :evidence-count 3}
   {:trait "Fails to recover from file access errors or missing expected files; when a file read fails, it halts without probing the directory structure, diagnosing the path issue, or requesting clarification."
    :recommended-alternative "Add a fallback branch that triggers directory probing and path diagnosis when fs/read returns an error or empty result."
    :confidence 0.40
    :evidence-count 3}])

(def ^:private instruction-class-body
  "The SECOND real structural candidate. Its `:summary` is a raw INSTRUCTION
   string, NOT an assemble-summary output — so it carries no
   `Known weaknesses:` sentence and its claims reach the prompt exactly once,
   through `format-seed-body`. Production produces both shapes; the arm has to
   be clean on both."
  {:summary "INSTRUCTION:\nYou EDIT FILES. Implement the latest user message by actually modifying files in the workspace with the tools, then verifying the result."
   :capabilities ["Read-apply-verify workflow"]
   :strengths []
   :weaknesses instruction-class-weaknesses
   :representative-uses ["Represents the 'EDIT FILES' instruction set for implementation turns"]
   :avoid-when []
   :version 4
   :consolidated-from-event-count 3})

(def ^:private behavioral-weaknesses
  "THREE claims on the behavioral body — more than `traits-per-seed-cap`. The
   rendered section shows the top 2 by confidence; the assembled `:summary`
   carries all 3. Both are claim content and both must go."
  [{:trait "an abstract :llm {:writes [:edits]} data-tree produces edits as TEXT — no workspace effect ever happens and the turn returns hollow success"
    :avoid-when "the task requires files to actually change on disk"
    :recommended-alternative "route every effect through the gated catalog fns so the edit actually lands"
    :confidence 0.70
    :evidence-count 1}
   {:trait "bundling final! into the same iteration as emit-tree! reports success before any leaf ran"
    :avoid-when "always — there is nothing to report until :tree-results is inspected"
    :recommended-alternative "emit-tree! ALONE; read :tree-results next iteration; then final!"
    :confidence 0.65
    :evidence-count 1}
   {:trait "editing only the definition and the obvious callers leaves stale references in tests and secondary namespaces"
    :avoid-when "the symbol is referenced beyond the file that defines it"
    :recommended-alternative "first enumerate ALL references repo-wide, then edit every site, then compile"
    :confidence 0.60
    :evidence-count 1}])

(def ^:private behavioral-summary
  (str "Code-building turns a typed spec into executable code with imports and a file path."
       " Capabilities: write executable code from a spec."
       " Known weaknesses: " (str/join "; " (map :trait behavioral-weaknesses)) "."
       " Avoid when: the task is pure analysis with no code emit."))

(def ^:private behavioral-body
  {:summary behavioral-summary
   :capabilities ["write executable code from a spec"]
   :strengths [{:trait "separate the spec read from the code emit"
                :good-when "the task names an explicit signature or schema"
                :recommended-pattern "[:sequence [:llm {:writes [:plan]}] [:code {:writes [:impl]}]]"
                :confidence 0.9
                :evidence-count 3}]
   :weaknesses behavioral-weaknesses
   :representative-uses ["add a pure helper to a namespace"]
   :avoid-when ["the task is pure analysis with no code emit"]
   :version 3
   :consolidated-from-event-count 7})

;; =============================================================================
;; Independent reconstruction of the claim spans
;; =============================================================================
;;
;; Deliberately NOT calling the render's private helpers: an expectation
;; computed by the code under test proves nothing. These reproduce the two
;; documented formats from their specifications.

(def ^:private weakness-section-header
  "Weaknesses (observed failure modes — avoid these patterns, apply the recommended fix where applicable):\n")

(def ^:private traits-cap
  "`todo-processors/traits-per-seed-cap` — the per-list render cap."
  2)

(defn- weakness-entry-text
  [{:keys [trait avoid-when recommended-alternative confidence evidence-count]}]
  (str "  - **Failure mode:** " trait
       (format " (confidence %.2f, evidence-count %d)" (double confidence) (int evidence-count))
       "\n"
       (when avoid-when (str "    - Avoid when: " avoid-when "\n"))
       (when recommended-alternative (str "    - Recommended fix: " recommended-alternative "\n"))))

(defn- rendered-weakness-section
  "The span `format-seed-body` emits for a body's weaknesses: the header, the
   top-`traits-cap` entries by confidence, joined and terminated. The header is
   claim-derived too — it renders only because claims exist."
  [weaknesses]
  (let [top (take traits-cap (sort-by (fn [e] (- (double (or (:confidence e) 0.0)))) weaknesses))]
    (str weakness-section-header
         (str/join "\n" (map weakness-entry-text top))
         "\n")))

(defn- summary-claim-sentence
  "The span `assemble-summary` contributes to `:summary` for a body's
   weaknesses, WITH the single space that joined it to the previous section."
  [weaknesses]
  (str " Known weaknesses: " (str/join "; " (map :trait weaknesses)) "."))

;; =============================================================================
;; Payload builders
;; =============================================================================

(defn- mk-structural-candidate
  [target-id fitness content reasoning]
  {:content content
   :document-id (str ":tree-fingerprint:" target-id)
   :document-metadata {:granularity :tree-fingerprint :target-id target-id}
   :fitness-score fitness
   :reasoning reasoning
   :rerank-source :reranker})

(defn- mk-behavioral-entry
  [behavior-id confidence reasoning]
  {:behavior-id behavior-id
   :confidence confidence
   :was-fresh-mint? false
   :reasoning reasoning
   :rerank-source :reranker})

(def ^:private base-instruction "BASE-INSTRUCTION-SENTINEL")

(defn- fixture
  "Ids, node and body lookup for the two-structural + one-behavioral payload
   the byte-level test renders. Ids are DISJOINT so every span is unique."
  []
  (let [coding-id (random-uuid)
        instruction-id (random-uuid)
        behavior-id (random-uuid)
        payload {:structural {:assigned-tree-id coding-id
                              :confidence 1.0
                              :was-fresh-mint? false
                              :reasoning "structural reasoning"
                              :top-candidates
                              [(mk-structural-candidate coding-id 1.0 coding-class-summary
                                                        "Top match: the coding lifecycle class.")
                               (mk-structural-candidate instruction-id 0.80
                                                        (:summary instruction-class-body)
                                                        "Alternative: the EDIT FILES instruction set.")]
                              :rerank-fallback? false}
                 :behavioral {:behaviors [(mk-behavioral-entry behavior-id 0.88
                                                               "Code-building is the clearest behavioral fit.")]
                              :rerank-fallback? false}}]
    {:coding-id coding-id
     :instruction-id instruction-id
     :behavior-id behavior-id
     :bodies {coding-id coding-class-body
              instruction-id instruction-class-body
              behavior-id behavioral-body}
     :node {:id (random-uuid)
            :type :repl-researcher
            :name "implement-with-command-line"
            :model "anthropic/claude-sonnet-4.6"
            :instruction base-instruction
            :context {:tree-id coding-id :r05-classifier payload}}}))

(defn- render
  "Run the REAL render with the fixture bodies in place, returning the block
   (the prepend only — the base instruction is the same on both arms)."
  [{:keys [bodies node]} render-ctx]
  (let [result (with-redefs [ontology/get-description
                             (fn [_ctx _granularity target-id] (get bodies target-id))]
                 (tp/apply-r05-classifier-context node render-ctx))
        instruction (:instruction result)]
    (is (str/ends-with? instruction base-instruction)
        "the base instruction survives the prepend on every arm")
    (subs instruction 0 (- (count instruction) (count base-instruction)))))

(defn- remove-span-once
  "Delete `span` from `s`, asserting it is present EXACTLY once. An ambiguous
   span would let the reconstruction pass by deleting the wrong occurrence."
  [s span]
  (let [i (str/index-of s span)]
    (is (some? i) (str "expected claim span not found in the treated block: "
                       (pr-str (subs span 0 (min 70 (count span))))))
    (if i
      (do (is (nil? (str/index-of s span (inc i)))
              (str "claim span occurs more than once — the reconstruction is ambiguous: "
                   (pr-str (subs span 0 (min 70 (count span))))))
          (str (subs s 0 i) (subs s (+ i (count span)))))
      ;; Unchanged rather than nil, so a missing span fails the equality
      ;; assertion below instead of throwing partway through the reduce.
      s)))

;; =============================================================================
;; RED #1 — the claim-only holdout is OFF by default
;; =============================================================================

(deftest claim-holdout-is-off-by-default
  (testing "the shipped default suppresses no claim"
    (is (false? (:enabled? tp/*claim-holdout*)))
    (is (zero? (:fraction tp/*claim-holdout*))))

  (testing "with no configuration at all, every claim still reaches the prompt"
    (let [f (fixture)
          block (render f {})]
      (is (str/includes? block sp3-claim-trait))
      (is (str/includes? block (rendered-weakness-section coding-class-weaknesses)))
      (is (str/includes? block (summary-claim-sentence coding-class-weaknesses))))))

;; =============================================================================
;; RED #2 — THE ACCEPTANCE TEST: the arms differ by EXACTLY the claim spans
;; =============================================================================

(deftest claim-only-holdout-differs-from-treatment-by-exactly-the-claim-spans
  (let [f (fixture)
        on (render f {})
        off (render f {:claim-holdout {:enabled? true :fraction 1.0}})
        ;; Every claim-derived span, reconstructed from the FIXTURE bodies —
        ;; not read out of the render.
        spans [;; the rendered weakness sections, one per body that has claims
               (rendered-weakness-section coding-class-weaknesses)
               (rendered-weakness-section instruction-class-weaknesses)
               (rendered-weakness-section behavioral-weaknesses)
               ;; the SAME claims a second time, inside the quoted `:summary`
               (summary-claim-sentence coding-class-weaknesses)
               (summary-claim-sentence behavioral-weaknesses)]
        reconstructed (reduce remove-span-once on spans)]

    (testing "the control still renders the block — this is not the all-or-nothing holdout"
      (is (str/starts-with? off "## Suggested patterns from corpus")))

    (testing "deleting exactly the claim spans from the treated block YIELDS the control block"
      (is (= reconstructed off)
          (str "claim-only-OFF must differ from ON by the claim spans and NOTHING else. "
               "ON " (count on) " chars, OFF " (count off) " chars, "
               "ON-minus-claims " (count reconstructed) " chars.")))

    (testing "no weakness claim text survives on the control arm — on either rendering"
      (doseq [w (concat coding-class-weaknesses instruction-class-weaknesses behavioral-weaknesses)]
        (is (str/includes? on (:trait w))
            (str "treated arm must carry the claim: " (subs (:trait w) 0 40)))
        (is (not (str/includes? off (:trait w)))
            (str "control arm must NOT carry the claim: " (subs (:trait w) 0 40)))
        (when-let [fix (:recommended-alternative w)]
          (is (not (str/includes? off fix))
              "control arm must not carry the claim's recommendation either")))
      (is (not (str/includes? off "Known weaknesses:"))
          "the assembled-summary rendering of the claims is gone too")
      (is (not (str/includes? off "**Failure mode:**"))
          "and so is the seed-body rendering"))

    (testing "everything that is NOT claim-derived is untouched"
      (doseq [kept ["## Suggested patterns from corpus"
                    "### Structural patterns (top 2 from corpus retrieval)"
                    "#### Top match"
                    "#### Alternative #1"
                    "Top match: the coding lifecycle class."
                    "Alternative: the EDIT FILES instruction set."
                    "Strengths (proven traits"
                    "Enforces functional correctness over simple write-confirmation"
                    "separate the spec read from the code emit"
                    "Representative uses (concrete tasks this pattern has shipped on):"
                    "Capabilities: Encodes a robust Workspace Edit-Apply-Verify lifecycle"
                    "Avoid when: Risk of significant collateral damage"
                    "### Behavioral competencies (top 1 from corpus retrieval)"
                    "mint-behavior!"]]
        (is (str/includes? off kept)
            (str "control arm dropped non-claim content: " kept))))))

;; =============================================================================
;; RED #2b — the candidate HEADER is not arm-dependent
;; =============================================================================
;;
;; `derive-seed-name` scans the summary for a stative verb and names the
;; candidate from the text before it. A verb can sit INSIDE a claim: when a
;; body's only claims are weaknesses, assemble-summary puts the
;; `Known weaknesses:` sentence FIRST, and the derived name is taken out of it.
;; Deriving the name from the STRIPPED summary would therefore let the holdout
;; move a candidate's header — content that is not the claim — and the arm
;; would not be clean. So the name is derived from the ORIGINAL summary on both
;; arms, and this pins it on the shape that discriminates.
;;
;; Known residual, deliberately not "fixed": on this shape the header echoes a
;; FRAGMENT of the claim, identically on both arms. It cannot confound the
;; contrast (the bytes are the same either way), but the control prompt is
;; claim-EQUAL there rather than claim-FREE. Removing it would mean changing
;; what the treated arm renders, which is out of bounds for a control.

(def ^:private weakness-first-body
  "A body whose ONLY claims are weaknesses — so its assembled `:summary` opens
   with the `Known weaknesses:` sentence."
  (let [trait "the pipeline is fragile under retry"]
    {:summary (str "Known weaknesses: " trait ". Avoid when: retries are disabled.")
     :capabilities []
     :strengths []
     :weaknesses [{:trait trait
                   :recommended-alternative "make the retry idempotent"
                   :confidence 0.5
                   :evidence-count 1}]
     :representative-uses []
     :avoid-when ["retries are disabled"]
     :version 1
     :consolidated-from-event-count 1}))

(deftest candidate-header-is-identical-on-both-arms
  (let [target-id (random-uuid)
        payload {:structural {:assigned-tree-id target-id
                              :confidence 1.0
                              :was-fresh-mint? false
                              :reasoning "r"
                              :top-candidates [(mk-structural-candidate
                                                 target-id 1.0 (:summary weakness-first-body) "r")]
                              :rerank-fallback? false}
                 :behavioral {:behaviors [] :rerank-fallback? false}}
        f {:bodies {target-id weakness-first-body}
           :node {:id (random-uuid) :type :repl-researcher :name "n"
                  :instruction base-instruction
                  :context {:tree-id target-id :r05-classifier payload}}}
        header-of (fn [block] (first (filter #(str/starts-with? % "#### ") (str/split-lines block))))
        on (render f {})
        off (render f {:claim-holdout {:enabled? true :fraction 1.0}})]

    (testing "the fixture really is the discriminating shape"
      (is (str/starts-with? (:summary weakness-first-body) "Known weaknesses: ")
          "the claim sentence leads the summary, so a name derived from the stripped text would differ"))

    (testing "the holdout does not move the candidate's header"
      (is (= (header-of on) (header-of off))
          "a header that changed with the arm is non-claim content moving — the arm would not be clean"))

    (testing "and the claim itself is gone from the control, in both renderings"
      (is (str/includes? on "**Failure mode:** the pipeline is fragile under retry"))
      (is (not (str/includes? off "**Failure mode:**")))
      (is (not (str/includes? off "Known weaknesses: the pipeline is fragile under retry.")))
      (is (not (str/includes? off "the pipeline is fragile under retry"))
          "the claim's trait text does not survive"))

    (testing "the residual, named honestly: the header echo is present on BOTH arms"
      ;; derive-seed-name took the name out of the leading claim sentence, so
      ;; the header carries the fragment "Known weaknesses: the pipeline" —
      ;; identically on both arms, hence harmless to the contrast. Recorded as
      ;; a characterization, not an endorsement.
      (is (str/includes? on "Known weaknesses: the pipeline"))
      (is (str/includes? off "Known weaknesses: the pipeline")))))

;; =============================================================================
;; RED #3 — the arm is recorded, so the analysis can tell the rows apart
;; =============================================================================

(deftest claim-only-holdout-is-stamped-on-the-injection-record
  (th/with-test-context [ctx]
    (let [sheet-id (random-uuid)
          treated-tick (random-uuid)
          control-tick (random-uuid)
          f-on (fixture)
          f-off (fixture)]
      (render f-on (assoc ctx :sheet-id sheet-id :tick-id treated-tick))
      (render f-off (assoc ctx :sheet-id sheet-id :tick-id control-tick
                           :claim-holdout {:enabled? true :fraction 1.0}))
      (let [treated (rm/get-injection-record ctx sheet-id treated-tick (:id (:node f-on)))
            control (rm/get-injection-record ctx sheet-id control-tick (:id (:node f-off)))]

        (testing "both rows landed"
          (is (some? treated))
          (is (some? control)))

        (testing "the realized arm is explicit — a claim-holdout row is not a treated row"
          (is (= :treatment (:arm treated)))
          (is (= :claim-holdout (:arm control))))

        (testing "the control still rendered a block, so it still carries a dose and a hash"
          (is (pos? (:rendered-chars control)))
          (is (string? (:prompt-content-hash control)))
          (is (< (:rendered-chars control) (:rendered-chars treated))
              "the control's dose is smaller by exactly the claims"))

        (testing "and it names the claim control it was compared against"
          (is (= "r-inject/no-claim-injection" (:baseline-policy-id control))))))))

;; =============================================================================
;; RED #4 — the existing all-or-nothing holdout is unchanged
;; =============================================================================

(deftest injection-holdout-semantics-are-untouched
  (testing "the all-or-nothing holdout still suppresses the WHOLE block"
    (let [f (fixture)
          result (with-redefs [ontology/get-description
                               (fn [_ _ id] (get (:bodies f) id))]
                   (tp/apply-r05-classifier-context
                     (:node f) {:injection-holdout {:enabled? true :fraction 1.0}}))]
      (is (= base-instruction (:instruction result))
          "nothing prepended — the pre-existing control condition is byte-identical to the base")))

  (testing "when both are on, the all-or-nothing holdout wins (it is the stronger suppression)"
    (th/with-test-context [ctx]
      (let [sheet-id (random-uuid)
            tick-id (random-uuid)
            f (fixture)]
        (with-redefs [ontology/get-description (fn [_ _ id] (get (:bodies f) id))]
          (tp/apply-r05-classifier-context
            (:node f) (assoc ctx :sheet-id sheet-id :tick-id tick-id
                             :injection-holdout {:enabled? true :fraction 1.0}
                             :claim-holdout {:enabled? true :fraction 1.0})))
        (is (= :holdout (:arm (rm/get-injection-record ctx sheet-id tick-id (:id (:node f))))))))))

;; =============================================================================
;; RED #5 — the claim holdout is a real randomized holdout, seam and all
;; =============================================================================

(deftest claim-holdout-uses-the-assignment-seam-with-its-own-occurrence-salt
  (let [seen (atom [])
        f (fixture)
        node-id (:id (:node f))]
    (binding [tp/*claim-holdout* {:enabled? true :fraction 0.25}
              tp/*holdout-assignment* (fn [occurrence fraction]
                                        (swap! seen conj [occurrence fraction])
                                        true)]
      (render f {:sheet-id :s :tick-id :t}))
    (testing "the fake assignment is honored, so no test depends on real randomness"
      (is (= 1 (count @seen))))
    (testing "and the occurrence is SALTED, so the two holdouts do not co-assign the same turns"
      (is (= [[:s :t node-id :claim] 0.25] (first @seen))))))

(deftest claim-holdout-is-configurable-process-wide-and-per-run
  (let [f (fixture)]
    (testing "per-run, via the context"
      (is (not (str/includes? (render f {:claim-holdout {:enabled? true :fraction 1.0}})
                              sp3-claim-trait))))
    (testing "process-wide, via the var"
      ;; NOTE: `binding` works HERE because the render runs on this thread.
      ;; In the live app it does not — see the var's docstring.
      (binding [tp/*claim-holdout* {:enabled? true :fraction 1.0}]
        (is (not (str/includes? (render f {}) sp3-claim-trait)))))))

;; =============================================================================
;; RED #6 — :turn-id on the injection record
;; =============================================================================
;;
;; Without it, a turn is joined to its injection by wall-clock window. That
;; join is not wrong-proof, and W2 had to do exactly it.

(deftest injection-record-carries-the-turn-id-when-the-render-context-has-one
  (th/with-test-context [ctx]
    (let [sheet-id (random-uuid)
          tick-id (random-uuid)
          turn-id (random-uuid)
          f (fixture)]
      (with-redefs [ontology/get-description (fn [_ _ id] (get (:bodies f) id))]
        (tp/apply-r05-classifier-context
          (:node f) (assoc ctx :sheet-id sheet-id :tick-id tick-id :turn-id turn-id)))
      (let [record (rm/get-injection-record ctx sheet-id tick-id (:id (:node f)))]
        (testing "the record joins to the TURN directly, not by wall clock"
          (is (= turn-id (:turn-id record))))))))

(deftest turn-id-reaches-the-record-from-the-real-tick-tool-context
  ;; The unit tests above prove the RECORD carries whatever turn-id the render
  ;; context has. This proves the EMIT SITE puts one there: a real tick command
  ;; carrying the host's opaque :tool-context, a real execution-context read
  ;; model, the real repl-researcher handler, and the record read back from the
  ;; projection.
  (th/with-test-context [ctx]
    (let [sheet-id (-> (th/run-and-apply! ctx (th/make-create-sheet-command :name "W2P1"))
                       :command-result/events first :sheet-id)
          _ (doseq [k [:question :answer]]
              (th/run-and-apply! ctx (th/make-declare-key-command sheet-id k :string)))
          seq-id (-> (th/run-and-apply! ctx (th/make-create-node-command sheet-id :sequence))
                     :command-result/events first :node-id)
          node-id (-> (th/run-and-apply! ctx (th/make-create-node-command
                                               sheet-id :repl-researcher :parent-id seq-id))
                      :command-result/events first :node-id)
          _ (th/run-and-apply! ctx (th/make-set-repl-researcher-config-command
                                     sheet-id node-id "Design a tree" [:question] [:answer] []
                                     :max-iterations 1
                                     :rlm {:auto-classify? true}))
          tick-id (random-uuid)
          turn-id (random-uuid)
          classified-tree-id (random-uuid)
          _ (th/run-command ctx (assoc (th/make-tick-tree-command sheet-id :tick-id tick-id)
                                       :inputs {:question "what shape is this task?"}
                                       :tool-context {:session-id (random-uuid)
                                                      :turn-id turn-id
                                                      :request-id (random-uuid)}))]

      (testing "the host's turn-id really survived to the tick execution context"
        (is (= turn-id (:turn-id (:tool-context (rm/get-tick-execution-context ctx tick-id))))
            "the opaque :tool-context crosses the command -> event -> read-model boundary"))

      ;; No provider: the researcher fails fast with "No ORC LLM provider
      ;; configured" and makes NO model call. The render and its record happen
      ;; before that, which is exactly the part under test.
      ;;
      ;; The handler returns as soon as it has spawned its future, so the
      ;; with-redefs MUST stay open until the record lands — otherwise the
      ;; stubs are torn down before the wedge runs and the REAL classifier
      ;; executes inside the future. (It did, the first time I wrote this. The
      ;; test still passed, which is exactly why the harness needs checking
      ;; separately from the work.)
      (binding [tp/*default-llm-provider* nil]
        (with-redefs [ontology/classify-task
                      (fn [_ _] {:assigned-tree-id classified-tree-id :confidence 0.85
                                 :top-candidates [{:content "corpus pattern"
                                                   :document-metadata
                                                   {:granularity :tree-fingerprint
                                                    :target-id classified-tree-id}
                                                   :fitness-score 0.85
                                                   :reasoning "r"
                                                   :rerank-source :reranker}]
                                 :reasoning "r"
                                 :was-fresh-mint? false :rerank-fallback? false
                                 :parent-tree-id nil :outcome :matched})
                      ontology/classify-behaviors
                      (fn [_ _] {:behaviors [] :rerank-fallback? false :outcome :matched})
                      ontology/get-description
                      (fn [_ _ id] (when (= id classified-tree-id)
                                     {:summary "Corpus pattern reasons over a spec."
                                      :capabilities ["c"] :strengths []
                                      :weaknesses [] :representative-uses []
                                      :avoid-when [] :version 5}))]
          (tp/execute-repl-researcher-node
            (assoc ctx :event {:sheet-id sheet-id :tick-id tick-id
                               :node-id node-id :inputs {}}))

          (let [deadline (+ (System/currentTimeMillis) 20000)
                record (loop []
                         (or (rm/get-injection-record ctx sheet-id tick-id node-id)
                             (when (< (System/currentTimeMillis) deadline)
                               (Thread/sleep 100)
                               (recur))))]
            (testing "the render left a record, and it names the turn"
              (is (some? record) "an injection record landed for this occurrence")
              (is (= turn-id (:turn-id record))
                  "joined to the turn directly — no wall-clock window needed"))

            (testing "the stubs were still in force when the future ran"
              ;; Guards the harness, not the work: a torn-down stub would leave
              ;; the REAL classifier's fresh-minted class here instead.
              (is (= classified-tree-id (:task-class record)))
              (is (= [(str classified-tree-id)]
                     (mapv :candidate-id (:candidates record)))))))))))

(deftest injection-record-omits-turn-id-when-the-host-supplies-none
  (th/with-test-context [ctx]
    (let [sheet-id (random-uuid)
          tick-id (random-uuid)
          f (fixture)]
      (with-redefs [ontology/get-description (fn [_ _ id] (get (:bodies f) id))]
        (tp/apply-r05-classifier-context
          (:node f) (assoc ctx :sheet-id sheet-id :tick-id tick-id)))
      (let [record (rm/get-injection-record ctx sheet-id tick-id (:id (:node f)))]
        (testing "a non-conversational render still records — the field is optional, not required"
          (is (some? record))
          (is (not (contains? record :turn-id))
              "absent rather than nil: a nil turn-id would join to every other nil"))))))
