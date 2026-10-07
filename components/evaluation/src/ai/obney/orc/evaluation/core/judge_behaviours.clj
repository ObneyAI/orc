(ns ai.obney.orc.evaluation.core.judge-behaviours
  "The shipped judges as ORC behaviours (JudgesAreBehaviours).

   A built-in judge is an ordinary ORC workflow executed against the assessed
   node's evidence, so its model call is budgeted, retried, accounted and
   model-selectable exactly like any other node's. Two forms, chosen by the
   judge's rubric:

   - feedback form (rubric :feedback :required): ONE `llm` node `grade` that
     reads the host evidence and the rubric and writes `reasoning` (first),
     the judge type's evidence lists, the chosen `band`, then `feedback`;
   - score-only form (rubric :feedback :none): ONE `llm-decision` node
     `grade`, banded from the rubric, writing only `band`.

   The RUBRIC IS DATA on the judge's blackboard (criterion, stance, described
   bands): editing it changes what the next grading call asks, with no change
   to the behaviour. The default rubric of each built-in type is built from the
   rubrics ns constants, so the legacy judge functions and these behaviours
   describe the same bands."
  (:require [clojure.string :as str]
            [ai.obney.orc.evaluation.core.judges :as judges]
            [ai.obney.orc.evaluation.core.rubrics :as rubrics]
            [ai.obney.orc.orc-service.interface :as sheet]))

;; =============================================================================
;; Rubrics (data)
;; =============================================================================

(def builtin-types
  "The judge types that ship as behaviours."
  #{:grounding :instruction-following :reasoning :completeness})

(defn default-rubric
  "The default rubric of a built-in judge type, as the data a judge declaration
   carries: {:criterion :stance :bands {level description} :feedback :required}.
   Built from the same criteria/stance/scale constants the legacy judge
   functions compose their prompts from."
  [judge-type]
  (let [{:keys [criteria stance scale]} (rubrics/get-tier1-rubric judge-type)]
    {:criterion criteria
     :stance stance
     :bands (into (sorted-map) (:bands scale))
     :feedback :required}))

(defn- non-blank-string? [v] (and (string? v) (not (str/blank? v))))

(defn effective-rubric
  "The rubric a built-in judge grades by: its declared :rubric, else the type's
   default rubric. A judge declared with only :criteria (no rubric) uses the
   default rubric with that criterion."
  [judge-config]
  (or (:rubric judge-config)
      (cond-> (default-rubric (:type judge-config))
        (non-blank-string? (:criteria judge-config))
        (assoc :criterion (:criteria judge-config)))))

(defn grading-form
  "`:feedback` when the rubric requires written feedback, `:score-only` when it
   declares none."
  [rubric]
  (if (= :none (:feedback rubric)) :score-only :feedback))

(defn rubric-value
  "The rubric as the blackboard value the behaviour reads: the grading contract
   (criterion, stance, described bands) without the feedback mode, which the
   behaviour's form already encodes."
  [rubric]
  (cond-> {:bands (into (sorted-map) (:bands rubric))}
    (:criterion rubric) (assoc :criterion (:criterion rubric))
    (:stance rubric) (assoc :stance (:stance rubric))))

;; =============================================================================
;; Blackboard (every key carries a model-facing description)
;; =============================================================================

(def ^:private host-value-registry
  {::value
   [:or :nil :string :int :double :boolean :keyword :uuid
    [:vector [:ref ::value]]
    [:map-of [:or :keyword :string] [:ref ::value]]]})

(defn- host-values
  "A recursive map of host values, described for the model."
  [description]
  [:schema {:registry host-value-registry}
   [:map-of {:description description} [:or :keyword :string] [:ref ::value]]])

(def ^:private rubric-schema
  [:map {:description (str "The grading contract. `criterion` is WHAT to evaluate, "
                           "`stance` is HOW to behave as the reviewer, and `bands` maps "
                           "each band number to what that band means. You must choose "
                           "exactly one of the band numbers.")}
   [:bands [:map-of :int [:maybe :string]]]
   [:criterion {:optional true} :string]
   [:stance {:optional true} :string]])

(def ^:private host-instruction-description
  "The instruction the assessed node was given (the task it was asked to do).")

(def ^:private host-inputs-description
  "The values the assessed node read (the material it had to work from).")

(def ^:private host-outputs-description
  (str "The values the assessed node wrote: its declared output fields, one value per "
       "field. The field set is fixed by the workflow's typed blackboard, not chosen by "
       "the producer. Judge the values, not the object shape."))

(def ^:private host-iterations-description
  "Bounded durable evidence for the research attempts that produced the outcome.")

(defn- with-description
  "Add a :description to a Malli schema form's properties."
  [schema description]
  (cond
    (keyword? schema) [schema {:description description}]
    (map? (second schema)) (update schema 1 assoc :description description)
    :else (into [(first schema) {:description description}] (rest schema))))

(def ^:private band-description
  (str "The band number you chose from the rubric's bands, chosen AFTER your "
       "reasoning. It must be one of the rubric's band numbers."))

(defn- band-schema
  "The chosen band: a positive integer. Never an enum (integer enums broke one
   Gemini route). The bound is a `:min` property on a plain `:int`, NOT an
   `[:and :int [:>= 1]]` form: the structured-output parser only reads a plain
   integer schema as a number, so the `:and` form leaves the model's `5` as the
   text \"5\" and the band fails schema validation (found live on
   z-ai/glm-5.3-flash). A banded decision requires a plain integer answer key
   too; the model is offered only the rubric's own bands."
  []
  [:int {:min 1 :description band-description}])

(defn- blackboard-for [judge-type form iterations? feedback-fields]
  (cond-> {:host-instruction [:string {:description host-instruction-description}]
           :host-inputs (host-values host-inputs-description)
           :host-outputs (host-values host-outputs-description)
           :rubric rubric-schema
           :band (band-schema)}
    iterations? (assoc :host-iterations [:string {:description host-iterations-description}])
    (= :feedback form) (merge feedback-fields)))

;; =============================================================================
;; Evidence fields per judge type (reuse the existing described fields)
;; =============================================================================

(defn- type-fields [judge-type]
  (case judge-type
    :grounding (judges/grounding-output-fields)
    :instruction-following (judges/instruction-following-output-fields)
    :reasoning (judges/reasoning-output-fields)
    :completeness (judges/completeness-output-fields)))

(defn- as-band-language [text]
  (str/replace text "choosing a level" "choosing a band"))

(defn- non-blank-feedback
  "The feedback the rubric requires must contain a non-whitespace character. A
   blank answer then violates the node's declared output, so it is a malformed
   provider answer and takes the executor's ordinary retry path. The pattern is
   a STRING so the schema survives persistence in the workflow snapshot."
  [string-schema]
  [:re (second string-schema) "\\S"])

(defn- feedback-form-fields
  "Blackboard entries for the feedback form's writes other than :band, keyed by
   field name, in generation order: :reasoning first, then the type's evidence
   fields, (the band sits between them and the feedback), then :feedback."
  [judge-type]
  (let [fields (type-fields judge-type)
        field (fn [n] (first (filter #(= n (:name %)) fields)))
        evidence (remove #(#{:reasoning :level :feedback} (:name %)) fields)
        entry (fn [{:keys [spec description]}]
                (with-description spec (as-band-language description)))]
    {:ordered-writes (vec (concat [:reasoning]
                                  (map :name evidence)
                                  [:band :feedback]))
     :fields (into {:reasoning (entry (field :reasoning))
                    :feedback (non-blank-feedback (entry (field :feedback)))}
                   (map (fn [f] [(:name f) (entry f)]) evidence))}))

;; =============================================================================
;; Instructions (the type's guidance, minus the bands which come from the rubric)
;; =============================================================================

(defn- evidence-guidance [judge-type iterations?]
  (str
   (case judge-type
     :grounding
     (str "You are given `host-inputs` (the source: the values the producer read, the "
          "ground truth to check against), `host-outputs` (the values the producer "
          "wrote), and `host-instruction` (the task the producer was given, for context "
          "only: do not grade against it). Compare the host outputs against the host "
          "inputs ONLY.")
     (str "You are given `host-instruction` (the task the producer was given), "
          "`host-outputs` (the values the producer wrote), and `host-inputs` (the "
          "context and material the producer had). Evaluate the host outputs against "
          "the host instruction, and against the host inputs where relevant."))
   " Judge the values, not the object shape."
   (when iterations?
     (str " You are also given `host-iterations`, a bounded durable record of the "
          "research attempts. Use it to explain why the outcome occurred."))))

(def ^:private rubric-guidance
  (str "`rubric` is your grading contract: apply its criterion, behave as its stance "
       "says, and choose exactly one band number from its bands."))

(defn- feedback-instruction [judge-type iterations?]
  (str (evidence-guidance judge-type iterations?) "\n\n"
       rubric-guidance "\n\n"
       "Fill `reasoning` first (adversarial analysis), then the evidence lists, then "
       "choose `band`, then write `feedback`: specific, actionable feedback the "
       "producer can act on."))

(defn- score-only-instruction [judge-type iterations?]
  (str (evidence-guidance judge-type iterations?) "\n\n"
       "Grade the host outputs against the rubric: apply its criterion, behave as its "
       "stance says, and choose the band that fits."))

;; =============================================================================
;; The behaviours
;; =============================================================================

(defn workflow-name
  "The workflow's name: one per type, form, evidence shape and model, so a
   declared model gets its own concrete workflow (a content-addressed build is
   idempotent, so reuse is free)."
  [judge-type form iterations? model]
  (str "judge-" (name judge-type) "-" (name form)
       (when iterations? "-iterations")
       (when (non-blank-string? model) (str "@" model))))

(defn behaviour
  "The workflow definition of a built-in judge.

   `form` is :feedback or :score-only; `model` is the judge's declared model
   (nil: the node declares none and resolves like any node, from the runtime's
   configured provider); `iterations?` adds the host-iterations evidence read
   for repl-researcher hosts."
  [judge-type form {:keys [model iterations?]}]
  {:pre [(contains? builtin-types judge-type) (#{:feedback :score-only} form)]}
  (let [model (when (non-blank-string? model) model)
        reads (cond-> [:host-instruction :host-inputs :host-outputs :rubric]
                iterations? (conj :host-iterations))
        node-name "grade"
        wf-name (workflow-name judge-type form iterations? model)]
    (if (= :score-only form)
      (sheet/workflow wf-name
        (sheet/blackboard (blackboard-for judge-type form iterations? nil))
        (sheet/llm-decision node-name
          :model model
          :instruction (score-only-instruction judge-type iterations?)
          :reads reads
          :bands-from :rubric
          :writes [:band]))
      (let [{:keys [ordered-writes fields]} (feedback-form-fields judge-type)]
        (sheet/workflow wf-name
          (sheet/blackboard (blackboard-for judge-type form iterations? fields))
          (sheet/llm node-name
            :model model
            :instruction (feedback-instruction judge-type iterations?)
            :reads reads
            :writes ordered-writes
            ;; A missing or blank answer (feedback the rubric requires) is a
            ;; malformed provider answer: retried under the node's ordinary
            ;; retry budget, then failed with the rejected write named.
            :options {:unparseable-is-schema-error? true}))))))
