(ns ai.obney.orc.evaluation.core.judge-run
  "The one run path for a judge (JudgesAreBehaviours).

   A judge, built-in or custom, is an ORC workflow executed against the
   assessed node's evidence. `run-judge` executes it and returns an OUTCOME:

     {:status :scored|:failed|:ungradable
      :band int?            ;; the rubric band chosen (absent for a legacy
                            ;; numeric-score custom judge)
      :score double?        ;; (band - min) / (max - min) from the rubric's bands
      :feedback string?     ;; ABSENT for a score-only rubric, never \"\"
      :dimensions [...]?
      :reason keyword       ;; failed / ungradable: why
      :message string       ;; failed / ungradable: how to read / fix it
      :model-provenance [{:requested-model :resolved-model :model :usage ..}]
      :judge-tick-id uuid?}

   NothingInvented: a missing or out-of-rubric band, an incomplete dimension,
   blank feedback the rubric requires, an unresolved model, a provider failure
   or a missed deadline is a :failed or :ungradable outcome carrying its
   reason. None of them becomes a score."
  (:require [clojure.string :as str]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.orc-service.interface :as orc]
            [ai.obney.orc.evaluation.core.judge-behaviours :as behaviours]
            [ai.obney.orc.evaluation.core.judges :as judges]
            [com.brunobonacci.mulog :as u]))

(def default-timeout-ms
  "How long a judge's workflow may run unless the judge declares :timeout-ms."
  60000)

;; =============================================================================
;; Outcomes
;; =============================================================================

(defn- failed [reason message & {:as extra}]
  (merge {:status :failed :reason reason :message message} extra))

(defn- ungradable [reason message & {:as extra}]
  (merge {:status :ungradable :reason reason :message message} extra))

(def model-unresolved-message
  (str "The judge's model could not be resolved. Declare a :model on the judge "
       "(a model id or a registered provider name), or configure the runtime's "
       "LLM provider so the judge can run on it."))

;; =============================================================================
;; Evidence -> blackboard values
;; =============================================================================

(defn- host-value
  "Coerce an evidence value into the judge blackboard's value domain
   (nil, string, integer, double, boolean, keyword, uuid, vectors, maps)."
  [x]
  (cond
    (or (nil? x) (string? x) (boolean? x) (keyword? x) (uuid? x)) x
    (instance? Long x) x
    (integer? x) (str x)
    (ratio? x) (double x)
    (number? x) (double x)
    (map? x) (into {}
                   (map (fn [[k v]] [(if (or (keyword? k) (string? k)) k (str k))
                                     (host-value v)]))
                   x)
    (coll? x) (mapv host-value x)
    :else (str x)))

;; =============================================================================
;; Provenance and failure evidence from the judge's own tick
;; =============================================================================

(defn- tick-completions [ctx tick-id]
  (when (and tick-id (:event-store ctx))
    (into [] (es/read (:event-store ctx)
                      {:types #{:sheet/node-execution-completed}
                       :tenant-id (:tenant-id ctx)
                       :tags #{[:tick tick-id]}}))))

(defn- model-provenance
  "One entry per model call of the judge's tick, from its completion events
   (requested model, resolved model, usage) - never invented."
  [completions provider]
  (->> completions
       (filter #(or (:model %) (:requested-model %) (:resolved-model %)))
       (mapv (fn [e]
               (cond-> {:model (or (:resolved-model e) (:requested-model e) (:model e))}
                 (:requested-model e) (assoc :requested-model (:requested-model e))
                 (:resolved-model e) (assoc :resolved-model (:resolved-model e))
                 (:usage e) (assoc :usage (:usage e))
                 (keyword? provider) (assoc :provider provider))))))

(defn- rejected-keys
  "The writes the executor rejected on the judge's failed completions."
  [completions]
  (into #{} (comp (filter #(= :failure (:status %))) (mapcat :rejected-write-keys)) completions))

(defn- failure-kind [completions]
  (some #(when (and (= :failure (:status %)) (:failure-kind %)) (:failure-kind %))
        completions))

;; =============================================================================
;; Interpreting a finished judge workflow
;; =============================================================================

(defn- band-score
  "Deterministic mapping of a valid band to [0,1]: (level - min) / (max - min)."
  [bands band]
  (let [levels (sort (keys bands))
        lo (first levels)
        hi (last levels)]
    (double (/ (- band lo) (- hi lo)))))

(defn- non-blank? [v] (and (string? v) (not (str/blank? v))))

(defn- validate-dimensions
  "Each supporting dimension needs a non-blank name and a numeric score in
   [0,1] - and feedback when the rubric requires it. A missing WEIGHT defaults
   to 1.0 (declared equal weighting); a missing score is never defaulted
   (J09). Returns {:dimensions [...]} or {:error message}."
  [raw rubric]
  (cond
    (nil? raw) {:dimensions []}
    (not (sequential? raw)) {:error (str "the judge wrote :dimensions that is not a vector: " (pr-str raw))}
    :else
    (let [feedback-required? (= :required (:feedback rubric))
          checked
          (map-indexed
           (fn [i d]
             (let [problem
                   (cond
                     (not (map? d)) "is not a map"
                     (not (non-blank? (:name d))) "has no non-blank :name"
                     (not (and (number? (:score d)) (<= 0.0 (double (:score d)) 1.0)))
                     (str "has no numeric :score in [0,1] (got " (pr-str (:score d)) ")")
                     (and (some? (:weight d)) (not (and (number? (:weight d)) (>= (double (:weight d)) 0.0))))
                     (str "has an invalid :weight " (pr-str (:weight d)))
                     (and feedback-required? (not (non-blank? (:feedback d))))
                     "has no :feedback, which the rubric requires")]
               (if problem
                 {:error (str "dimension " i " " problem)}
                 {:dimension (cond-> {:name (:name d)
                                      :weight (double (or (:weight d) 1.0))
                                      :score (double (:score d))}
                               (some? (:feedback d)) (assoc :feedback (:feedback d))
                               (and (nil? (:feedback d)) (nil? rubric)) (assoc :feedback ""))})))
           raw)]
      (if-let [e (some :error checked)]
        {:error e}
        {:dimensions (mapv :dimension checked)}))))

(defn- summarize-evidence
  "Render one dimension's feedback from a pair of evidence lists (cited vs
   omitted), in the judge's own vocabulary. Empty lists still yield a
   dimension - the feedback says nothing was cited/omitted rather than
   silently dropping the dimension (RR-30)."
  [cited-label cited-items omitted-label omitted-items]
  (str cited-label ": "
       (if (seq cited-items) (str/join "; " cited-items) "nothing cited")
       ". " omitted-label ": "
       (if (seq omitted-items) (str/join "; " omitted-items) "none")
       "."))

(defn project-dimensions
  "RR-30: project a built-in judge's own evidence lists (on its outputs) into
   its one named DimensionScore, carrying the judge's own score and a weight
   of 1.0, named with the judge's rubric name from
   `judges/default-judge-dimension-names` (the ontology classifier's dictionary
   is case-sensitive). Pure: no new model call."
  [judge-type inner score]
  (let [dimension (fn [cited-label cited omitted-label omitted]
                    [{:name (judges/default-judge-dimension-names judge-type)
                      :weight 1.0
                      :score score
                      :feedback (summarize-evidence cited-label cited omitted-label omitted)}])]
    (case judge-type
      :grounding (dimension "Grounded claims" (:grounded-claims inner)
                            "Ungrounded claims" (:ungrounded-claims inner))
      :reasoning (dimension "Strengths" (:reasoning-strengths inner)
                            "Weaknesses" (:reasoning-weaknesses inner))
      :completeness (dimension "Aspects covered" (:aspects-covered inner)
                               "Aspects missing" (:aspects-missing inner))
      :instruction-following (dimension "Requirements met" (:requirements-met inner)
                                        "Requirements missed" (:requirements-missed inner))
      [])))

(defn- rubric-outcome
  "The outcome of a judge that grades by a rubric: the workflow wrote a `:band`."
  [judge-type rubric outputs]
  (let [bands (:bands rubric)
        band (:band outputs)
        valid? (and (integer? band) (contains? bands band))]
    (cond
      (not valid?)
      (failed :invalid-result
              (if (nil? band)
                "The judge wrote no band, so there is nothing to score."
                (str "The judge wrote band " (pr-str band) ", which is not one of the rubric's bands "
                     (pr-str (vec (sort (keys bands))))
                     ".")))

      (and (= :required (:feedback rubric)) (not (non-blank? (:feedback outputs))))
      (failed :missing-feedback
              "The rubric requires written feedback but the judge wrote none; no score is recorded.")

      :else
      (let [score (band-score bands band)
            dims (if (contains? behaviours/builtin-types judge-type)
                   {:dimensions (project-dimensions judge-type outputs score)}
                   (validate-dimensions (:dimensions outputs) rubric))]
        (if (:error dims)
          (failed :invalid-dimensions (:error dims))
          (cond-> {:status :scored
                   :band band
                   :score score
                   :dimensions (:dimensions dims)}
            (= :required (:feedback rubric)) (assoc :feedback (:feedback outputs))))))))

(defn- legacy-score-outcome
  "A legacy custom judge (no rubric) that writes a numeric :score in [0,1]."
  [outputs]
  (let [score (:score outputs)]
    (if-not (and (number? score) (<= 0.0 (double score) 1.0))
      (failed :invalid-result
              (if (nil? score)
                "The custom judge wrote no :score, so there is nothing to score."
                (str "The custom judge wrote :score " (pr-str score) ", which is not a number in [0,1].")))
      (let [dims (validate-dimensions (:dimensions outputs) nil)]
        (if (:error dims)
          (failed :invalid-dimensions (:error dims))
          {:status :scored
           :score (double score)
           :feedback (or (:feedback outputs) "")
           :dimensions (:dimensions dims)})))))

(defn- execution-failure-outcome [result completions timeout-ms]
  (let [kind (failure-kind completions)]
    (cond
      (= :timeout (:status result))
      (failed :deadline (str "The judge did not finish within " timeout-ms " ms."))

      (= :undecided kind)
      (ungradable :tied-bands
                  (str "The decision model returned an exact tie for the most probable band, "
                       "so no band is selected; the expected position is never rounded into a band."))

      ;; A feedback answer that stayed blank or absent through the node's
      ;; retries: the rubric required it, so nothing is scored.
      (and (= :schema-validation-failed kind) (contains? (rejected-keys completions) :feedback))
      (failed :missing-feedback
              "The rubric requires written feedback but the judge wrote none after its retries; no score is recorded.")

      (= :provider-not-configured kind)
      (failed :judge-model-unresolved model-unresolved-message)

      :else
      (failed (or kind :judge-execution-failed)
              (str "The judge's workflow did not succeed"
                   (when-let [e (:error result)] (str ": " e)))))))

(defn- finish
  "Turn the finished workflow `result` of a judge into its outcome."
  [ctx result {:keys [judge-type rubric timeout-ms provider]}]
  (let [tick-id (:trace-id result)
        completions (tick-completions ctx tick-id)
        provenance (model-provenance completions provider)
        outcome (if (= :success (:status result))
                  (if rubric
                    (rubric-outcome judge-type rubric (:outputs result))
                    (legacy-score-outcome (:outputs result)))
                  (execution-failure-outcome result completions timeout-ms))]
    (cond-> (assoc outcome :model-provenance provenance)
      tick-id (assoc :judge-tick-id tick-id))))

;; =============================================================================
;; Running
;; =============================================================================

(defn- execute-workflow
  "Run a judge's workflow marked as assessment work (`origin`): the mark is
   recorded durably on the run and inherited by everything it delegates to, so
   nothing inside a judge's tree is ever auto-assessed."
  [ctx sheet-id inputs timeout-ms origin]
  (try
    (orc/execute ctx sheet-id inputs :timeout-ms timeout-ms :assessment-origin origin)
    (catch Throwable t
      (u/log ::judge-execution-threw
             :sheet-id sheet-id
             :error (ex-message t)
             :exception-class (.getName (class t)))
      {:status :failure :error (ex-message t)})))

(def ^:private build-lock (Object.))

(defn- build-behaviour!
  "Build (idempotently, by content) the concrete behaviour of a built-in judge.
   Serialised so concurrent judges of one type never race the same build."
  [ctx judge-type form opts]
  (locking build-lock
    (orc/build-workflow! ctx (behaviours/behaviour judge-type form opts))))

(defn- run-builtin
  [ctx judge-config evidence origin]
  (let [judge-type (:type judge-config)
        rubric (behaviours/effective-rubric judge-config)
        form (behaviours/grading-form rubric)
        iterations (not-empty (:researcher-iterations evidence))
        ;; Grounding grades against the source only; the other judges may be
        ;; shown a repl-researcher host's durable iteration record.
        iterations? (and (boolean iterations) (not= :grounding judge-type))
        timeout-ms (or (:timeout-ms judge-config) default-timeout-ms)
        provider (:llm-provider ctx)
        inputs (cond-> {:host-instruction (judges/compose-task evidence (:criteria judge-config))
                        :host-inputs (host-value (or (:inputs evidence) {}))
                        :host-outputs (host-value (or (:outputs evidence) {}))
                        :rubric (behaviours/rubric-value rubric)}
                 iterations? (assoc :host-iterations (judges/coerce-source-string iterations)))
        sheet-id (build-behaviour! ctx judge-type form {:model (:model judge-config)
                                                        :iterations? iterations?})
        result (execute-workflow ctx sheet-id inputs timeout-ms origin)]
    (finish ctx result {:judge-type judge-type :rubric rubric
                        :timeout-ms timeout-ms :provider provider})))

(defn- run-custom
  [ctx judge-config evidence origin]
  (let [;; The judges read model lifts a custom judge's :sheet-id to
        ;; :eval-sheet-id so it is not overwritten by the host sheet id;
        ;; legacy callers that pass the raw config carry :sheet-id directly.
        eval-sheet-id (or (:eval-sheet-id judge-config) (:sheet-id judge-config))
        timeout-ms (or (:timeout-ms judge-config) default-timeout-ms)]
    (cond
      (nil? eval-sheet-id)
      (failed :missing-sheet-id "A custom judge needs the id of the workflow that grades.")

      :else
      (let [declared (orc/get-blackboard-by-key ctx eval-sheet-id)
            host-trace-schema (:schema (get declared :host-trace))
            trace-entry (cond-> {} (:node-id evidence) (assoc :node-id (:node-id evidence)))
            ;; Custom evaluator workflows own their blackboard contract:
            ;; preserve the vector-of-events shape while also supporting a
            ;; consumer that declares a single trace-summary map.
            host-trace (if (= :vector (first host-trace-schema)) [trace-entry] trace-entry)
            rubric (:rubric judge-config)
            inputs (cond-> {:host-inputs (or (:inputs evidence) {})
                            :host-outputs (or (:outputs evidence) {})
                            :host-instruction (or (:instruction evidence) "")
                            :host-trace host-trace}
                     rubric (assoc :rubric (behaviours/rubric-value rubric)))
            result (execute-workflow ctx eval-sheet-id inputs timeout-ms origin)]
        (finish ctx result {:judge-type :custom :rubric rubric
                            :timeout-ms timeout-ms :provider (:llm-provider ctx)})))))

(defn run-judge
  "Run one judge against the assessed node's `evidence`
   (`{:inputs :outputs :instruction :write-keys :researcher-iterations
      :node-id}`) and return its OUTCOME (see the namespace doc).

   `judge-config` is the judge's read-model entry: its :type, and optionally
   :rubric, :model, :provider, :criteria, :timeout-ms (a custom judge's
   workflow is :eval-sheet-id). Never throws: every way a judge can fail is a
   :failed or :ungradable outcome with its reason.

   The evidence may carry the :assessment-id the judging serves: the judge's run
   is marked with it as its durable assessment origin. A judge run with no
   durable assessment (the legacy tree-shape path) is marked with a fresh id -
   it is assessment work all the same."
  [ctx judge-config evidence]
  (try
    (let [judge-type (:type judge-config)
          origin {:assessment-id (or (:assessment-id evidence) (random-uuid))}]
      ;; A judge's model resolves like any node's: its declared :model, else the
      ;; runtime's configured provider. The runtime's provider belongs to the
      ;; processors that execute every leaf, so a per-judge :provider cannot
      ;; take effect; it is never silently honoured halfway.
      (when (:provider judge-config)
        (u/log ::judge-provider-ignored
               :judge-type judge-type
               :provider (:provider judge-config)))
      (cond
        (= :custom judge-type) (run-custom ctx judge-config evidence origin)
        (contains? behaviours/builtin-types judge-type) (run-builtin ctx judge-config evidence origin)
        :else (failed :unsupported-judge-type
                      (str "No judge behaviour exists for type " (pr-str judge-type) "."))))
    (catch Throwable t
      (u/log ::run-judge-threw
             :judge-type (:type judge-config)
             :error (ex-message t)
             :exception-class (.getName (class t)))
      (failed :judge-execution-failed (or (ex-message t) (.getName (class t)))))))
