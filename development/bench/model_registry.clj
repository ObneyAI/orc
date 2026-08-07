(ns model-registry
  "CH-1 — the bench harness's model-registration PRECONDITION.

   Why this exists
   ---------------
   A live harness that talks to a model needs its model registration
   verified BEFORE its output is treated as data. Twice in the CC arc a
   configuration defect read as a behavioural result: CC-5's implementer
   measured '100% silence' that was a missing `litellm.router/register!`,
   and the convergence probe ran for an unknown number of turns without
   anyone being able to say, from the harness alone, which models it was
   about to talk to.

   litellm's router makes that easy to get wrong in a specific way: a
   registration is looked up by NAME, and `litellm.router/completion`
   resolves the model as `(or (:model request-map) (:model resolved))`.
   So a single GENERIC `:openrouter` entry will happily serve a request
   for ANY model id — the model a harness actually uses is whatever some
   engine default happens to say, and the harness never has to name it.
   That is convenient and it is exactly how an undeclared model reaches
   production traffic without anyone deciding it should.

   The rule this namespace enforces
   --------------------------------
   Every model the harness WILL use must be EXPLICITLY registered under a
   registration whose own `:model` is that model id. A generic entry
   configured for a DIFFERENT model does NOT count — that is the whole
   point, and it is what `registrations-for` encodes.

   The check is pure over the registry: no network, no API key, no LLM
   call. That is what lets it fire before the first call rather than
   after a confusing result."
  (:require [clojure.string :as str]
            [litellm.router :as litellm-router]))

(defn registry-snapshot
  "The current litellm registrations as plain data:
   {registration-name -> the model id it is CONFIGURED for}.

   Taken as a snapshot so the pure predicates below can be tested
   without touching global state."
  []
  (into (sorted-map)
        (map (fn [n] [n (:model (litellm-router/get-config n))]))
        (litellm-router/list-configs)))

(defn registrations-for
  "The registration names in `snapshot` that are configured for EXACTLY
   `model`.

   A generic entry configured for another model is deliberately NOT a
   match: being reachable through a fallback is not the same as having
   been declared, and only the second is evidence that a human decided
   this harness talks to this model."
  [snapshot model]
  (->> snapshot
       (keep (fn [[reg-name reg-model]] (when (= reg-model model) reg-name)))
       vec))

(defn unregistered-models
  "The subset of `models` that `snapshot` has no explicit registration
   for, in the order given. Pure."
  [snapshot models]
  (vec (remove #(seq (registrations-for snapshot %)) models)))

(defn assert-models-registered!
  "PRECONDITION. Throws unless every model in `models` is explicitly
   registered with litellm under its own model id.

   Call this AFTER registering and BEFORE building any context or making
   any LLM call. The thrown message names the offending model(s) and
   prints every registration that does exist, so the failure is
   actionable from the first line of the stack trace."
  [harness-name models]
  (let [snapshot (registry-snapshot)
        missing (unregistered-models snapshot (distinct models))]
    (when (seq missing)
      (throw (ex-info
              (str "UNREGISTERED MODEL in harness '" harness-name "'.\n"
                   "  These models WILL be used but were never explicitly registered:\n"
                   (str/join "\n" (map #(str "    - " %) missing)) "\n"
                   "  Registrations that DO exist (name -> configured model):\n"
                   (if (seq snapshot)
                     (str/join "\n" (map (fn [[k v]] (str "    " k " -> " (pr-str v))) snapshot))
                     "    (none)")
                   "\n"
                   "  A generic entry configured for a DIFFERENT model does NOT count:\n"
                   "  litellm resolves (or request-:model registered-:model), so an\n"
                   "  undeclared model rides a generic registration silently and its\n"
                   "  output then reads as a behavioural result. Register each model\n"
                   "  the harness uses, by name, before the run.")
              {:harness harness-name
               :unregistered-models missing
               :requested-models (vec (distinct models))
               :registrations snapshot})))
    :ok))
