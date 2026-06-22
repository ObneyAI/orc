(ns ai.obney.orc.evaluation.core.scale
  "PA-3 — first-class, decoupled `Scale` abstraction for LLM-as-judge scoring.

   A `Scale` is the SCORING dimension of a judge, kept deliberately separate
   from the CRITERIA (what to evaluate) and the INSTRUCTION (how to behave).
   This decoupling is the tier-1 fix from the Verdict dive (see
   `doc/judge-framework-verdict-notes.md` and ADR 0011): bundling criteria +
   scale + output-format into one prompt string with a soft 0.0-1.0 anchor
   is the documented source of judge mode-collapse and miscalibration.

   The Scale here is a **discrete 1-5 with an explicit per-level band
   description**, mapped DETERMINISTICALLY to continuous `[0,1]` for storage
   and aggregation so nothing downstream changes shape (the
   `:judge/score-emitted` event still carries a `[0,1]` `:score`).

   Discrete scoring constrains the model's reasoning to a small, well-
   anchored set of choices — far less variance than free-form continuous
   self-scoring.

   Everything in this namespace is DETERMINISTIC and TDD'd
   (`scale_test.clj`). The LLM picks a band; this code validates and maps
   it, and gates against silent empty output (the structured-output rule's
   'no run-through' gate)."
  (:require [clojure.string :as str]))

;; =============================================================================
;; Construction / validation
;; =============================================================================

(defn discrete-scale
  "Build a first-class discrete `Scale` artifact.

   Args (map):
     :min   — lowest level (integer, e.g. 1)
     :max   — highest level (integer, e.g. 5)
     :bands — map of {level -> description-string}; one entry PER level in
              [min, max]. The description is what level N *means* — the
              anchor the judge reasons toward.

   Returns:
     {:kind :discrete :min <min> :max <max> :bands {...}}

   Throws ExceptionInfo when bounds are invalid or a level is missing a
   band description. The Scale intentionally carries NO :criteria,
   :instruction, or :prompt — those are supplied by the judge, decoupled
   from the scale."
  [{:keys [min max bands]}]
  (when-not (and (integer? min) (integer? max))
    (throw (ex-info "discrete-scale: :min and :max must be integers"
                    {:min min :max max})))
  (when-not (< min max)
    (throw (ex-info "discrete-scale: :min must be < :max"
                    {:min min :max max})))
  (let [levels (range min (inc max))
        missing (remove #(contains? bands %) levels)]
    (when (seq missing)
      (throw (ex-info "discrete-scale: every level needs a band description"
                      {:missing-levels (vec missing)
                       :levels (vec levels)
                       :provided (vec (keys bands))})))
    {:kind :discrete
     :min min
     :max max
     :bands (into (sorted-map) (select-keys bands levels))}))

(defn levels
  "The ordered seq of integer levels in this scale, low → high."
  [{:keys [min max]}]
  (range min (inc max)))

;; =============================================================================
;; Deterministic level → [0,1] mapping
;; =============================================================================

(defn coerce-level
  "Coerce a level to an integer. Accepts integers and numeric strings
   (some judge models return the level as a JSON string, or as a float like
   4.0). Returns nil for anything non-coercible."
  [level]
  (cond
    (integer? level) level
    (and (number? level) (== level (long level))) (long level)
    (and (string? level) (re-matches #"\s*-?\d+\s*" level)) (Long/parseLong (str/trim level))
    :else nil))

(defn level->unit-score
  "Map a discrete `level` to a continuous score in [0,1], deterministically:

       (level - min) / (max - min)

   For a 1-5 scale: 1→0.0, 2→0.25, 3→0.5, 4→0.75, 5→1.0.

   Coerces numeric-string levels (\"4\" → 4). Throws ExceptionInfo when the
   level is nil / non-numeric / out of [min, max] — this is part of the
   no-run-through guarantee: a garbage level is an error, never a silent 0."
  [{:keys [min max] :as _scale} level]
  (let [n (coerce-level level)]
    (when (nil? n)
      (throw (ex-info "level->unit-score: level is nil or non-numeric"
                      {:level level})))
    (when-not (<= min n max)
      (throw (ex-info "level->unit-score: level out of range"
                      {:level n :min min :max max})))
    (double (/ (- n min) (- max min)))))

;; =============================================================================
;; Band rendering for the prompt (NO json-in-prompt, NO output-schema)
;; =============================================================================

(defn render-bands
  "Render the scale's per-level bands as plain human-readable text for the
   judge's instruction. This is band ANCHORING text, not an output format:
   per the structured-output rule we never put a JSON example or
   'return only JSON' in the prompt — the output shape is carried by the
   typed blackboard / DSCloj output fields, not the prompt."
  [{:keys [bands] :as scale}]
  (->> (levels scale)
       (map (fn [lvl] (str "- " lvl ": " (get bands lvl))))
       (str/join "\n")))

;; =============================================================================
;; No-run-through gate
;; =============================================================================

(defn gate-banded-output
  "The 'no run-through' gate (structured-output rule, obney-ops craft).

   Takes the judge's structured output map (expected to carry at least a
   numeric `:level`, plus `:reasoning` / `:feedback`) and either:
     - throws ExceptionInfo when the output is nil/empty or missing a usable
       `:level` (a silent empty structured output is the documented
       failure mode of a prompt that grew past a permissive schema), or
     - returns the output enriched with a deterministic `:score` in [0,1]
       mapped from the level via `level->unit-score`.

   This guarantees a judge never silently scores 0 on an empty model
   response — it errors loudly so a structured-output regression is caught."
  [scale output]
  (when (or (nil? output) (and (map? output) (empty? output)))
    (throw (ex-info "gate-banded-output: empty judge output — no-run-through gate tripped (suspect a structured-output regression)"
                    {:output output})))
  (let [raw-level (:level output)
        level (coerce-level raw-level)]
    (when (nil? level)
      (throw (ex-info "gate-banded-output: judge output missing a usable :level/score — no-run-through gate tripped"
                      {:output output})))
    ;; Normalize :level to a clean integer so downstream consumers + the
    ;; [:enum 1..5] schema never see a string/float the model happened to emit.
    (assoc output
           :level level
           :score (level->unit-score scale level))))
