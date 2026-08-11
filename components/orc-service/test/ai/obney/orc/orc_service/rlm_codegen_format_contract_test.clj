(ns ai.obney.orc.orc-service.rlm-codegen-format-contract-test
  "The RLM code-generation request must carry ONE output contract, matching
   its transport mode.

   Root-caused live (2026-08-11, wire-captured): the code-gen instructions
   hardcode an emphatic marker directive — 'Your response MUST start with
   `[[ ## code ## ]]`' — written for marker mode, and ship UNCHANGED into
   function-calling requests (where sio attaches the submit_response tool
   and appends 'Call the submit_response function with your answer').
   The request contradicts itself: models that weight the prompt text
   (qwen3.7-max, captured returning valid code as marker text, finish
   :stop, no tool call) lose their ENTIRE answer under sio's no-fallback
   contract — 'LLM did not generate code' when the code was generated.
   Models that weight the tool affordance (gemini) work. Neither model is
   wrong; the request is."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.orc.orc-service.core.executor]))

(def ^:private build-module
  @#'ai.obney.orc.orc-service.core.executor/build-rlm-code-generation-module)

(def ^:private node
  {:id (random-uuid)
   :name "implement-with-command-line"
   :instruction "Edit the file and verify."
   :writes [:assistant-response]})

(defn- instructions [opts]
  (:instructions (build-module node {} [] {} {} {} [] opts)))

(deftest marker-mode-keeps-the-marker-contract-byte-identically
  (testing "default (marker) mode: the marker directive stays, and the
             legacy arities' output is unchanged (backward compatibility —
             every existing caller and prompt hash)."
    (let [legacy (:instructions (build-module node {} [] {} {} {}))
          explicit (instructions {:function-calling? false})]
      (is (re-find #"\[\[ ## code ## \]\]" legacy))
      (is (= legacy explicit)
          "explicit marker mode is byte-identical to the legacy arity"))))

(deftest function-calling-mode-carries-no-marker-directives
  (testing "FC mode: NO marker directives anywhere in the instructions —
             one contract per request. The code goes in the tool call's
             `code` field, still fence-free."
    (let [fc (instructions {:function-calling? true})]
      (is (nil? (re-find #"\[\[ ##" fc))
          "no marker spelling may survive into a function-calling request")
      (is (re-find #"(?i)code.{0,40}field|field.{0,40}code" fc)
          "the FC contract names the code field")
      (is (re-find #"(?i)no markdown code fences|no ```" fc)
          "the fence prohibition survives — it is transport-independent"))))
