# Operational checks and a guarded model-only warmer

## Inspect the consuming classpath without a provider call

Run this in the actual service's already-resolved Clojure environment. It resolves resources; it does not register a provider or invoke a model:

```clojure
(require '[clojure.java.io :as io]
         '[ai.obney.orc.llm.interface :as llm])
(into {}
      (for [path ["ai/obney/orc/llm/core.clj"
                  "ai/obney/orc/orc_service/core/executor.clj"
                  "ai/obney/orc/orc_service/core/dsl.clj"
                  "sio/core.clj"
                  "litellm/providers/openrouter.clj"]]
        [path (some-> (io/resource path) str)]))
(-> #'llm/predict meta (select-keys [:file :line]))
```

Compare resource URLs and resolved dependency manifests with the intended pins, not merely the working directory or branch name. Resolve before loading the service; if its classpath/source files were changed after namespaces loaded, use a fresh process. A URL shows the selected resource, not a hash of already compiled runtime code. Inspect that exact source for `message-content`/request layout, option consumption, strict compiler, fallback constructor retention and candidate option validation. At released ORC 4966c8e the enabled layout is one USER message/two content parts. A local candidate with two USER messages is not that release even if its base dependency says4966c8e.

The recorded pins are ORC 4966c8e/SIO e61e11a9/client f5fa99d8; qualification is in [the evidence record](qualified-evidence.md). These checks do not establish model availability, endpoint eligibility or cache hits. Do not hardcode a claimed currently available model from the historical examples. Inventory every registered generation role and fallback candidate, actual model/provider, mode, schema, options, completion allowance and enclosing deadline. A missing role or zero-node scan is a failed preflight. Build the actual workflows and compile their contracts before paid execution: [contract preflight](../../orc-model-sio/references/contract-preflight.md).

## Reuse the actual module; invoke only by explicit choice

This example uses public `llm/predict`, not a new warmer API. It does not execute a tree, delegate or domain capability. Supply the **same declared module** and effective request options as the intended generation call, including actual requested model and route settings. A dummy schema/instruction/tool catalogue does not warm a different target. Keep each distinct specialist/model family separate.

For an authored ordinary node, the source-pinned diagnostic seam is `executor/build-module node blackboard-metadata` and `executor/gather-inputs node synthetic-blackboard`; both operate on actual registered schemas, with synthetic values replacing only the runtime `:value`s. They are diagnostic source APIs, not promised stable public APIs. Preserve input/output descriptions and output mapping. If using another module builder, prove it yields the same actual module sent by the consumer. The synthetic suffix must be harmless valid data under that declared contract; do not put customer facts or domain authority in a warm request. Ordinary generated output is validated, never applied as a business result.

The following block defines functions only. Loading it makes **no provider calls**. `warm-once!` refuses unless the caller explicitly passes the paid-call guard. Even then, operate within the already authorized target, remaining spend/call allowance, provider registration, timeout and model policy. The boolean is an intentional-invocation guard, not a new user approval for each call; request authorization only when the existing scope does not cover the proposed spend. Direct prediction does not inherit an ORC root's family accounting/deadline; bound and account for each warm call separately. Any provider retries in effective options also count.

```clojure
(ns orc-cache.operational
  (:require [ai.obney.orc.llm.interface :as llm]
            [malli.core :as m]
            [sio.structured-output :as structured-output]))

(defn warm-plan [provider declared-module synthetic-inputs effective-options]
  (assert (keyword? provider) "Use the registered provider alias")
  (assert (= :json-schema (:output-mode effective-options)))
  (assert (true? (:prompt-cache? effective-options)))
  (assert (true? (:generic-tool-description? effective-options)))
  (assert (string? (:model effective-options)) "Preserve the target model")
  (assert (pos-int? (:max-tokens effective-options)))
  (assert (pos-int? (:timeout-ms effective-options)))
  ;; This minimal warmer requires one valid synthetic value for every read.
  ;; Missing optional reads need a separately reviewed target-specific plan.
  (assert (= (set (map :name (:inputs declared-module)))
             (set (keys synthetic-inputs))))
  (doseq [{:keys [name spec]} (:inputs declared-module)]
    (assert spec "Retain the declared input schema")
    (assert (m/validate spec (get synthetic-inputs name))
            "Synthetic input does not satisfy the declared contract"))
  (structured-output/compile-contract declared-module)
  {:provider provider :module declared-module :inputs synthetic-inputs
   :options (assoc effective-options :validate? true :with-metadata? true)})

(defn warm-once! [{:keys [provider module inputs options]}
                  {:keys [allow-paid-call?]}]
  (when-not (true? allow-paid-call?)
    (throw (ex-info "Warm call requires intentional invocation within the authorized budget"
                    {:type :warm-call-not-authorized})))
  (llm/predict provider module inputs options))

(defn cache-metrics [normalized-usage]
  (let [details (:prompt-tokens-details normalized-usage)
        observed (fn [m k](if (contains? m k) (get m k) :unknown))]
    {:cache-read-tokens (observed details :cached_tokens)
     :cache-write-tokens (observed details :cache_write_tokens)
     :actual-cost (observed normalized-usage :cost)}))

;; Safe default: construct/inspect the plan, then stop. No top-level warm call.
;; An authorized host may call (warm-once! plan {:allow-paid-call? true}).
;; Cache-metrics summarizes returned usage; it does not prove response validity.
```

Use a sufficient, bounded completion allowance so the normal declared result can finish. Do not deliberately truncate an answer to save warmup cost. A successful model-only prediction proves neither an ORC tick nor durable canonical ownership: label this request-level warming and keep its own target/attempt/cost record. Use ordinary execution separately for the workflow proof. This simple block targets ordinary strict generation; it does not establish native decision, researcher or judge caching.

Offline validation of this definition block passed 16 checks through public `llm/predict` on the local two-USER candidate based on ORC 4966c8e with pinned SIO/client. Both HTTP responses were fully faked: module identity, typed synthetic suffix, default invocation refusal, strict decoding, stable prefix across changed suffix, and read/write/cost normalization were checked. This is example-mechanics proof, not a live cache hit or released-layout qualification. It does not establish actual target eligibility, cost savings, host scheduler behavior or domain execution ownership.

## Allowlisted measurements and execution ownership

For the pinned OpenRouter adapter, raw JSON `usage.prompt_tokens_details` becomes normalized `:usage :prompt-tokens-details`; its nested keys are preserved. Therefore raw `cached_tokens` and `cache_write_tokens` are read at `[:usage :prompt-tokens-details :cached_tokens]` and `[:usage :prompt-tokens-details :cache_write_tokens]` after normalization. Raw `usage.cost` is retained as `[:usage :cost]` by presence. The `cache-metrics` function takes just that normalized usage map. Preserve zero, absence and null distinctly; `:unknown` here means the field was absent. A present null is still unavailable evidence, never zero. Other providers may use different fields or omit counters; qualify their mapping rather than assuming this one.

Source: client f5fa99d8 `src/litellm/providers/openrouter.clj:82–98` (`transform-usage`); ORC 4966c8e `components/llm/src/ai/obney/orc/llm/core.clj:665–680` copies normalized usage/model/response identity into provider observations. Read and write counters can both be positive. A marker or lower latency is not a hit. Do not add observation usage to canonical family usage a second time.

For actual ordinary ticks, query execution-owned records through the public behavior-tree interface alias `bt`:

```clojure
(require '[ai.obney.orc.orc-service.interface :as bt])
(bt/provider-attempts ctx {:tick-id tick-id
                           :node-start-id node-start-event-id
                           :invocation-id invocation-id}) ; optional narrowing
```

Check `:query/result` or the returned anomaly. Match tenant, sheet/tick/node, canonical node-start ID, execution context, node/provider attempt and invocation UUID. Completed ledger rows contain `:outcome` and may contain `:provider-observations`; missing observations remain missing. Match raw response ID and resolved model through the owned observation, then compare actual response/schema/parsed outputs with canonical node/child/parent values and current source facts. Obtain delegated child ticks separately. The returned direct `predict` metadata is not a fabricated canonical tick record.

For exact physical-request ownership, use the accepted `start-provider-attempt!`/transport correlation seam described in [structural HTTP ownership](../../orc-observe-debug/references/evidence-path.md#structural-http-attempt-ownership); see [public attempt query and outcome layout](../../orc-model-sio/references/structured-contract-repairs.md#query-exact-execution-owned-attempts). An admitted attempt may fail local preflight before dispatch, so ledger starts are not automatically physical HTTP counts. Never join identical parallel outputs by text. Keep full raw requests/responses private when required for validation, and publish only allowlisted IDs, structural hashes, timing, token/cost fields and typed outcomes. Never publish authorization headers or whole exception data.
