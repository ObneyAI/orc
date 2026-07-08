(ns ai.obney.orc.evaluation.scale-test
  "PA-3 — TDD for the decoupled, discrete-band `Scale` abstraction and its
   deterministic 1-5 → [0,1] mapping.

   Per ADR 0011 / judge-framework-verdict-notes: the Scale is a first-class
   artifact, decoupled from the criteria/instruction. It is a discrete 1-5
   with an explicit per-level band description, mapped deterministically to
   continuous [0,1] for storage so nothing downstream changes shape.

   These are DETERMINISTIC/CONTRACT tests (discipline #5): the mapping, the
   construction/validation, the band rendering, and the no-run-through gate.
   The LLM's choice of band is NOT tested here."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.evaluation.core.scale :as scale]))

;; =============================================================================
;; RED #1 — discrete-scale constructor produces a first-class Scale artifact
;; =============================================================================

(deftest discrete-scale-is-first-class-and-decoupled
  (testing "discrete-scale builds a Scale map with levels + per-level bands, with NO criteria/instruction embedded"
    (let [s (scale/discrete-scale
              {:min 1 :max 5
               :bands {1 "Total failure."
                       2 "Mostly wrong."
                       3 "Mixed."
                       4 "Mostly right."
                       5 "Flawless."}})]
      (is (= :discrete (:kind s)))
      (is (= 1 (:min s)))
      (is (= 5 (:max s)))
      (is (= 5 (count (:bands s))) "One band description per level")
      (is (every? string? (vals (:bands s))) "Every band is a description string")
      ;; Decoupling: a Scale must NOT carry criteria/instruction/prompt.
      (is (nil? (:criteria s)) "Scale carries no criteria")
      (is (nil? (:instruction s)) "Scale carries no instruction")
      (is (nil? (:prompt s)) "Scale carries no prompt"))))

(deftest discrete-scale-rejects-missing-band
  (testing "Constructing a 1-5 scale with a band missing throws (every level needs a description)"
    (is (thrown? clojure.lang.ExceptionInfo
          (scale/discrete-scale
            {:min 1 :max 5
             :bands {1 "a" 2 "b" 3 "c" 4 "d"}}))
        "Missing the level-5 band must throw")))

(deftest discrete-scale-rejects-bad-bounds
  (testing "min must be < max"
    (is (thrown? clojure.lang.ExceptionInfo
          (scale/discrete-scale {:min 5 :max 1 :bands {}})))))

;; =============================================================================
;; RED #2 — deterministic level → [0,1] mapping
;; =============================================================================

(deftest level->unit-score-maps-1-5-to-0-1-deterministically
  (testing "1-5 maps linearly to [0,1]: (level - min) / (max - min)"
    (let [s (scale/discrete-scale
              {:min 1 :max 5
               :bands {1 "a" 2 "b" 3 "c" 4 "d" 5 "e"}})]
      (is (= 0.0  (scale/level->unit-score s 1)))
      (is (= 0.25 (scale/level->unit-score s 2)))
      (is (= 0.5  (scale/level->unit-score s 3)))
      (is (= 0.75 (scale/level->unit-score s 4)))
      (is (= 1.0  (scale/level->unit-score s 5))))))

(deftest level->unit-score-clamps-and-rejects-out-of-range
  (testing "A level outside [min,max] is an error — the gate must catch the LLM emitting garbage"
    (let [s (scale/discrete-scale
              {:min 1 :max 5
               :bands {1 "a" 2 "b" 3 "c" 4 "d" 5 "e"}})]
      (is (thrown? clojure.lang.ExceptionInfo (scale/level->unit-score s 0)))
      (is (thrown? clojure.lang.ExceptionInfo (scale/level->unit-score s 6)))
      (is (thrown? clojure.lang.ExceptionInfo (scale/level->unit-score s nil))))))

(deftest level->unit-score-coerces-numeric-strings
  (testing "Some judge models return the level as a JSON string; the mapping coerces \"4\" → 4 → 0.75"
    (let [s (scale/discrete-scale
              {:min 1 :max 5
               :bands {1 "a" 2 "b" 3 "c" 4 "d" 5 "e"}})]
      (is (= 0.75 (scale/level->unit-score s "4")))
      (is (= 1.0 (scale/level->unit-score s "5"))))))

;; =============================================================================
;; RED #3 — band rendering for the prompt (NO json-in-prompt)
;; =============================================================================

(deftest render-bands-produces-plain-text-not-json
  (testing "render-bands emits human-readable per-level band text for the judge instruction — not a JSON schema"
    (let [s (scale/discrete-scale
              {:min 1 :max 5
               :bands {1 "Total failure."
                       2 "Mostly wrong."
                       3 "Mixed."
                       4 "Mostly right."
                       5 "Flawless."}})
          rendered (scale/render-bands s)]
      (is (string? rendered))
      (is (re-find #"1" rendered))
      (is (re-find #"Total failure" rendered))
      (is (re-find #"5" rendered))
      (is (re-find #"Flawless" rendered))
      ;; The structured-output rule: no JSON example / "return only JSON" leaks
      ;; into the prompt text.
      (is (not (re-find #"(?i)return only json" rendered)))
      (is (not (re-find #"\{\"score\"" rendered))
          "No JSON object literal in the rendered band text"))))

;; =============================================================================
;; RED #4 — no-run-through gate
;; =============================================================================

(deftest gate-throws-on-empty-or-missing-level
  (testing "no-run-through gate: empty/nil judge output → throw (never a silent 0)"
    (let [s (scale/discrete-scale
              {:min 1 :max 5
               :bands {1 "a" 2 "b" 3 "c" 4 "d" 5 "e"}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)empty|no.?run.?through|missing"
            (scale/gate-banded-output s nil))
          "nil output throws")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)empty|no.?run.?through|missing"
            (scale/gate-banded-output s {}))
          "empty map throws")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)level|score"
            (scale/gate-banded-output s {:reasoning "I forgot the level"}))
          "output missing :level throws"))))

(deftest gate-passes-through-valid-banded-output
  (testing "A valid banded output (level + reasoning) passes the gate and yields the mapped unit score"
    (let [s (scale/discrete-scale
              {:min 1 :max 5
               :bands {1 "a" 2 "b" 3 "c" 4 "d" 5 "e"}})
          out {:level 4
               :reasoning "Adversarial review: most claims grounded, one weak inference."
               :feedback "Tighten claim X."}
          gated (scale/gate-banded-output s out)]
      (is (= 4 (:level gated)))
      (is (= 0.75 (:score gated)) "Mapped via level->unit-score")
      (is (string? (:reasoning gated)))
      (is (string? (:feedback gated))))))
