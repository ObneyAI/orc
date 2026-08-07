(ns ai.obney.orc.orc-service.complex-e2e-support
  "Shared infrastructure for DET-E2E-101 through DET-E2E-120.

  Real-LLM candidates are opt-in and use one pinned OpenRouter model.  Keeping
  the gate here prevents an individual test namespace from silently inventing
  weaker gating or substituting fake model behavior."
  (:require [clojure.string :as str]
            [clojure.test :refer [is testing]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.orc.orc-service.interface :as sheet]
            [ai.obney.orc.orc-service.test-helpers :as h]
            [litellm.router :as litellm-router]))

(def openrouter-model
  "The model selected for every REAL-LLM candidate and every LLM role within
  those candidates."
  "google/gemini-3.6-flash")

(def openrouter-strong-model
  "The next cost/intelligence rung, reserved for a candidate whose observed
   live behavior demonstrates that the default model cannot satisfy its tool
   use contract."
  "google/gemini-3.1-pro-preview")

(def openrouter-gate-env "ORC_OPENROUTER_E2E_TESTS")
(def openrouter-key-env "OPENROUTER_API_KEY")

(defn real-llm-enabled?
  "True only when the explicit live-test gate is true and credentials exist.
  Never returns or logs the credential value."
  []
  (and (= "true" (some-> (System/getenv openrouter-gate-env)
                          str/trim
                          str/lower-case))
       (not (str/blank? (System/getenv openrouter-key-env)))))

(defn real-llm-skip-reason
  []
  (cond
    (not= "true" (some-> (System/getenv openrouter-gate-env)
                          str/trim
                          str/lower-case))
    (str openrouter-gate-env " is not true")

    (str/blank? (System/getenv openrouter-key-env))
    (str openrouter-key-env " is absent")

    :else nil))

(defmacro with-real-openrouter
  "Run body only under the explicit real-LLM gate. An ordinary suite run records
  an intentional skip without touching provider registration or the network."
  [& body]
  `(if (real-llm-enabled?)
     (do ~@body)
     (testing (str "REAL-LLM skipped: " (real-llm-skip-reason))
       (is true))))

(defn register-openrouter-model!
  "Register one explicitly pinned model under both provider addressing forms
   used by ORC. Call only inside with-real-openrouter."
  [model]
  {:pre [(real-llm-enabled?)]}
  (let [base-config {:provider :openrouter
                     :model model
                     :config {:api-base "https://openrouter.ai/api/v1"
                              :api-key (System/getenv openrouter-key-env)}}]
    (litellm-router/register! :openrouter base-config)
    (litellm-router/register! (keyword (str "openrouter/" model))
                              base-config)
    model))

(defn register-openrouter!
  "Register the default pinned model."
  []
  (register-openrouter-model! openrouter-model))

(defn register-openrouter-auth-failure!
  "Replace the OpenRouter configuration captured by async test processors with
   the pinned model and a deliberately invalid credential. Call
   register-openrouter! afterward to restore the valid configuration. This
   produces a real OpenRouter HTTP failure without mocking model behavior."
  []
  {:pre [(real-llm-enabled?)]}
  (litellm-router/register!
    :openrouter
    {:provider :openrouter
     :model openrouter-model
     :config {:api-base "https://openrouter.ai/api/v1"
              :api-key (str (System/getenv openrouter-key-env)
                            "-intentionally-invalid-for-e2e")}})
  :openrouter)

(defn assert-model!
  [expected-model provenance]
  (is (= expected-model (:model provenance))
      (str "Expected durable model provenance " expected-model
           ", got " (pr-str (:model provenance))))
  provenance)

(defn assert-pinned-model!
  "Fail when durable provenance resolves to anything other than the selected
  model. Returns the provenance for convenient threading in tests."
  [provenance]
  (assert-model! openrouter-model provenance))

(defn events
  "Read tenant-scoped immutable evidence. Candidate tests use this instead of
  instrumentation around the provider; only durable records count as proof."
  ([ctx]
   (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx)})))
  ([ctx event-type]
   (into [] (es/read (:event-store ctx)
                     {:tenant-id (:tenant-id ctx) :types #{event-type}}))))

(defn model-completions
  "Durable model-backed leaf completions, optionally for one tick."
  ([ctx] (model-completions ctx nil))
  ([ctx tick-id]
   (->> (events ctx :sheet/node-execution-completed)
        (filter :model)
        (filter #(or (nil? tick-id) (= tick-id (:tick-id %))))
        vec)))

(defn assert-live-provenance-for-model!
  "Require non-empty durable model and usage evidence for a completed live
  call. A successful result without this evidence is explicitly not a pass."
  [ctx tick-id expected-model]
  (let [calls (model-completions ctx tick-id)]
    (is (seq calls) "Expected at least one durable model-backed completion")
    (doseq [call calls]
      (assert-model! expected-model call)
      (is (map? (:usage call)) "Every completed model call must record usage")
      (is (some pos? (keep (:usage call) [:prompt-tokens :completion-tokens
                                          :total-tokens]))
          "At least one durable token count must be positive"))
    calls))

(defn assert-live-provenance!
  [ctx tick-id]
  (assert-live-provenance-for-model! ctx tick-id openrouter-model))

(defn execute-live-leaf!
  "Build and execute the smallest real OpenRouter workflow. This is a common
  primitive for complex journeys, not a substitute for their component-level
  assertions."
  [ctx workflow-name instruction input]
  {:pre [(real-llm-enabled?)]}
  (let [sheet-id (sheet/build-workflow!
                  ctx
                  (sheet/workflow workflow-name
                    (sheet/blackboard {:input :string :answer :string})
                    (sheet/llm "live-openrouter-leaf"
                      :model openrouter-model
                      :instruction instruction
                      :reads [:input]
                      :writes [:answer])))
        result (sheet/execute (assoc ctx :dscloj-provider :openrouter)
                              sheet-id {:input input})]
    (is (= :success (:status result)) (pr-str result))
    (assert-live-provenance! ctx (:trace-id result))
    {:sheet-id sheet-id :result result}))

(defn build-recursive-rlm!
  "Build a public-command-path recursive researcher pinned to OpenRouter.
  The instruction is intentionally supplied by each falsifiable journey; this
  helper provides infrastructure only and never supplies model responses."
  [ctx {:keys [name instruction reads writes max-iterations rlm model]
        :or {name "real-openrouter-recursive-rlm"
             reads [:question]
             writes [:answer]
             max-iterations 8
             rlm {}
             model openrouter-model}}]
  {:pre [(real-llm-enabled?) (string? instruction)]}
  (let [sheet-result (h/run-and-apply! ctx (h/make-create-sheet-command :name name))
        sheet-id (-> sheet-result :command-result/events first :sheet-id)]
    (doseq [k (distinct (concat reads writes))]
      (h/run-and-apply! ctx (h/make-declare-key-command sheet-id k :string)))
    (let [sequence-result (h/run-and-apply! ctx (h/make-create-node-command sheet-id :sequence))
          sequence-id (-> sequence-result :command-result/events first :node-id)
          node-result (h/run-and-apply! ctx
                        (h/make-create-node-command sheet-id :repl-researcher
                                                    :parent-id sequence-id))
          node-id (-> node-result :command-result/events first :node-id)]
      (h/run-and-apply!
       ctx
       (h/make-set-repl-researcher-config-command
        sheet-id node-id instruction reads writes []
        :model model
        :max-iterations max-iterations
        :rlm (merge {:recursive? true} rlm)))
      {:sheet-id sheet-id :node-id node-id})))
