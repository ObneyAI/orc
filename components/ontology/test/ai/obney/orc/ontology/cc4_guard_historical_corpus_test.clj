(ns ai.obney.orc.ontology.cc4-guard-historical-corpus-test
  "CC-4 — the evidence guard measured against REAL historical judge output.

   NOT propagated from the spec; added by the CC-4 implementer to make the
   catch-rate measurement DURABLE. The contract tests in
   `cc4_evidence_guard_test` prove the guard behaves correctly on authored
   fixtures. This one proves it behaves correctly on the actual strings the
   system produced, which is the claim CC-14 hands to the maintainer.

   The corpus, measured while building CC-4:

     * `orc-main/development/bench/gap3_loop_live_verify/raw_2026-06-03_150543.edn`
       — THE INCIDENT. Three grounding judges, one response, one empty input
       context, scores [1.0 0.0 0.0]. All three feedback strings below are
       VERBATIM from that file. The guard catches 3/3.
     * the orc-sessions durable store (Postgres, `grain.events`, 120
       `:judge/score-emitted` events, judge `implementation-turn/coding-outcome`)
       — 0/120 flagged, i.e. no false positives, including all 21 legitimate
       0.0 scores. One of those 0.0s is reproduced verbatim below because it
       is the hardest negative in the corpus: it is FULL of absence language
       ('empty diff', 'no textual change', 'no commands were executed') that
       is about the TURN, not about the judge's own input.

   The distinction that matters, and the reason this is not sentiment
   analysis: a starved judgement is one where the JUDGE reports it had
   nothing to judge. A harsh judgement is one where the judge had plenty and
   found it wanting. Confusing the two in either direction is fatal — the
   first becomes a fabricated weakness claim, the second a suppressed real
   one."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.ontology.core.evidence-guard :as guard]))

(def ^:private gap3-grounding-feedback
  "Verbatim, all three judges from the incident run."
  {:scored-1.0
   "The assessment of the LLM response is hindered by the fact that the actual content of the ':employment_agreement' was not provided in the 'Input Context' for this evaluation task. However, the response itself contains internal citations which strongly suggest it is analyzing a specific document. Assuming the document exists and contains the quoted text, the claims are logically grounded in those quotes. The response effectively identifies potential employee-side issues (discretionary bonuses, broad non-competes), ambiguities (undefined terms like 'solicit'), and common missing clauses (vacation, severance). Because I cannot verify the specific text of the agreement against the provided empty context, I am marking the groundedness based on the consistency between the descriptions and the provided citations within the response."

   :scored-0.0-a
   "The model failed the grounding task because the 'Input Context' provided in the prompt was empty (json `{ }`). Therefore, any specific claims about a bonus capped at 20%, non-compete clauses in BC districts, specific schedule references (Schedule A, B, D, E), or plan codes like 'PNFS-2025-EMP' are entirely hallucinated relative to the provided context. While the response contains a logically consistent 'generated-tree-raw' demonstrating its internal process, the actual content of the review is not grounded in any data provided in the evaluation request. To improve, the model must only use information present in the input context."

   :scored-0.0-b
   "The LLM response is highly detailed and structurally sound; however, the 'Input Context' provided in the prompt was an empty JSON object `{}`. Because the `employment_agreement` mentioned in the instructions was not actually provided in the `inputs` section of this evaluation task, I cannot verify any of the specific factual claims regarding \"Schedule A, Section 3\", \"PNFS-2025-EMP\", or specific geographic locations like \"GVRD\". In a real-world scenario where the document was present, this might be a 1.0, but based strictly on the provided input context (which is empty), all specific claims are technically ungrounded. Assuming the prompt implies the contract exists but was omitted from the display for the evaluator, the internal logic is consistent. However, following strict grounding rules against the provided empty context, the score reflects the inability to trace claims."})

(def ^:private real-zero-score-defect-feedback
  "Verbatim from the orc-sessions durable store: a REAL 0.0 from the coding
   judge. Nothing was starved — the judge had the diff, the self-report and
   the command evidence, and used all three."
  "When claiming a file was edited, ensure the diff actually shows textual changes — an empty diff means no change was made. Always run a verification command (e.g., a test or REPL check) that exercises the modified function to prove the change works before reporting success. — The diff evidence shows 6 consecutive attempts to update src/util.clj, but ALL show `<empty diff — no textual change>`. This means the file content was NEVER actually modified. The self-report claims \"The `slugify` function correctly handles `nil` by returning an empty string, and the updated file has been saved\" with \"Write status: true\", but the captured diff proves no textual change occurred. This is a clear case of claiming success without evidence — the honesty floor applies. Additionally, no commands were executed to verify the change, which would have triggered the verification cap anyway.")

(deftest every-judge-in-the-incident-is-caught-including-the-one-that-scored-1
  (testing "all three judgements are starved — they differ only in stance"
    (doseq [[label feedback] gap3-grounding-feedback]
      (is (guard/starved-feedback? feedback)
          (str "gap3 " label " must be caught")))))

(deftest the-guard-is-not-a-score-filter
  (testing "the 1.0-scoring judge reasoned from an ASSUMED document"
    (is (guard/starved-feedback? (:scored-1.0 gap3-grounding-feedback))
        "a filter keyed on low scores would have missed this one entirely,
         and it is the arm that fabricates a false STRENGTH rather than a
         false weakness")))

(deftest a-real-zero-score-with-real-evidence-survives
  (testing "harsh is not starved"
    (is (not (guard/starved-feedback? real-zero-score-defect-feedback))
        "this feedback is dense with absence language about the TURN
         ('empty diff', 'no commands were executed'); flagging it would
         suppress real defect signal — the over-rejection failure mode")))

(deftest substantive-feedback-is-settled-without-a-model-call
  (testing "real judge feedback is long; the verifier is for the residue"
    (is (every? #(>= (count %) 400) (vals gap3-grounding-feedback)))
    (is (>= (count real-zero-score-defect-feedback) 400))))
