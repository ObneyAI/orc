(ns ai.obney.orc.evaluation.core.judges)

(def ^:dynamic *use-mock-llm*
  "Set to true to use mock LLM responses for testing.
   Default: false (use real LLM calls)."
  false)

(def ^:dynamic *judge-provider*
  "Provider keyword for LLM calls (e.g., :openrouter, :anthropic).
   Default: :openrouter"
  :openrouter)

(def ^:dynamic *judge-model*
  "Model to use for judge LLM calls.
   Default: google/gemini-2.5-flash (fast and cost-effective for evaluation)"
  "google/gemini-2.5-flash")

(defmacro with-mock-llm
  "Execute body with mock LLM responses (for testing)."
  [& body]
  `(binding [*use-mock-llm* true]
     ~@body))

(defmacro with-judge-config
  "Execute body with custom judge configuration.

   Example:
     (with-judge-config {:provider :anthropic :model \"claude-3-haiku-20240307\"}
       (evaluate-single :grounding trace-data))"
  [{:keys [provider model mock?]} & body]
  `(binding [*judge-provider* (or ~provider *judge-provider*)
             *judge-model* (or ~model *judge-model*)
             *use-mock-llm* (or ~mock? *use-mock-llm*)]
     ~@body))
