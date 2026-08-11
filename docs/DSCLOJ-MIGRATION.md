# Migrating from DSCloj to ORC's LLM component

ORC no longer depends on DSCloj. Structured input and output handling now lives
in SIO, while provider registration and prediction are exposed through ORC's
`llm` component. Consumers normally receive `orc/llm`, SIO, and `litellm-clj`
transitively from the packaged ORC project.

This is a migration, not a compatibility alias. Remove the old configuration
and namespaces rather than keeping both versions in an application.

## Migration checklist

- Remove any explicit `io.github.ObneyAI/DSCloj` dependency.
- Replace `dscloj.core` and other `dscloj.*` requires.
- Replace every `:dscloj-provider` context key with `:llm-provider`.
- Update both the context passed to ORC and the context supplied to each Grain
  tenant poller.
- Register named providers through
  `ai.obney.orc.llm.interface/register-provider!`.
- If the application consumes LLM streaming directly, switch from
  `:dscloj/event` to `:orc/event`.
- Replace numeric enum values in provider-facing schemas with strings or
  keywords, then convert them to numbers in deterministic application code if
  necessary.
- Run the repository searches and tests at the end of this guide.

## Dependencies and namespaces

Remove the DSCloj dependency from the consumer's `deps.edn`:

```clojure
;; Remove this dependency.
io.github.ObneyAI/DSCloj
{:git/url "https://github.com/ObneyAI/DSCloj.git"
 :git/sha "..."}
```

Consumers of ORC's packaged project should not need to add SIO or
`litellm-clj` themselves. Use the public ORC interface instead of depending on
either implementation library directly:

```clojure
;; Before
(:require [dscloj.core :as dscloj])

;; After
(:require [ai.obney.orc.llm.interface :as llm])
```

The replacement operations are:

| DSCloj | ORC LLM component |
|---|---|
| `dscloj/predict` | `llm/predict` |
| `dscloj/predict-stream-v2` | `llm/predict-stream-v2` |
| `dscloj/quick-setup!` | `llm/quick-setup!` |
| `dscloj/list-providers` | `llm/list-providers` |
| Direct provider registration | `llm/register-provider!` |

## Provider registration

Register a provider alias once during application startup:

```clojure
(require '[ai.obney.orc.llm.interface :as llm])

(llm/register-provider!
 :openrouter
 {:provider :openrouter
  :model "anthropic/claude-sonnet-5"
  :config {:api-base "https://openrouter.ai/api/v1"
           :api-key (System/getenv "OPENROUTER_API_KEY")}})
```

The alias passed to `register-provider!` is the value used by
`:llm-provider`. Existing provider credential environment variables, including
`OPENROUTER_API_KEY`, do not need to change.

`llm/quick-setup!` remains available when environment-based automatic setup is
preferred, but explicit registration makes the selected provider, model, and
configuration easiest to audit.

## Execution and poller contexts

Rename the provider key everywhere a service context is constructed. A common
miss is updating the context passed to `orc/execute` while leaving the old key
inside the tenant poller.

```clojure
;; Before
(def context
  {:event-store event-store
   :cache cache
   :dscloj-provider :openrouter})

(tp/start-tenant-poller
 {:event-store event-store
  :tenant-ids #{tenant-id}
  :context {:cache cache
            :dscloj-provider :openrouter}})

;; After
(def context
  {:event-store event-store
   :cache cache
   :llm-provider :openrouter})

(tp/start-tenant-poller
 {:event-store event-store
  :tenant-ids #{tenant-id}
  :context {:cache cache
            :llm-provider :openrouter}})
```

Do not leave `:dscloj-provider` alongside the replacement. ORC does not use the
legacy key. Depending on the execution path, a missed key may result in a
default provider being selected or in a later "no provider configured" failure;
it must not be treated as a compatibility fallback.

## Blocking predictions

The basic structured prediction contract remains familiar:

```clojure
(llm/predict :openrouter specification inputs options)
```

With `:with-metadata? true`, the result has this shape:

```clojure
{:outputs {...}
 :usage {:prompt-tokens 10
         :completion-tokens 4
         :total-tokens 14}
 :model "resolved/provider-model"
 :raw-response "..."}
```

Without `:with-metadata?`, `predict` returns the parsed output map directly.
Provider errors and requested validation failures propagate to the caller.

Structured failures are `ExceptionInfo` values whose `ex-data` may contain:

```clojure
{:failure-kind :missing-forced-tool-call
 :provider-evidence
 {:provider "openrouter"
  :model "resolved/provider-model"
  :response-id "..."
  :finish-reason "length"
  :tool-call-present? false
  :tool-call-name nil
  :usage {...}
  :output-truncated? true}}
```

The evidence is sanitized and provider-neutral. Do not expect tool arguments or
the complete provider response envelope. Successful metadata-bearing results
retain the shape shown above; diagnostic evidence is attached to failures.

### No hidden function-calling fallback

DSCloj could catch a function-calling exception and silently make a second,
marker-based provider request. ORC's LLM component does not do this. One call to
`predict` performs at most one provider invocation; ORC's workflow layer owns
retry and call-budget accounting.

This means a provider failure that DSCloj previously concealed may now be
visible. Do not add an application-level function-calling-to-marker fallback
without considering duplicate cost, latency, and side effects.

## Streaming predictions

Applications that directly consume `predict-stream-v2` must use ORC's event
discriminator:

```clojure
;; Before
(case (:dscloj/event event)
  :delta ...
  :fields ...
  :final ...
  :error ...)

;; After
(case (:orc/event event)
  :delta ...
  :fields ...
  :final ...
  :error ...)
```

The channel emits ordered events with these shapes:

```clojure
{:orc/event :delta  :text "..."}
{:orc/event :fields :fields {...}}
{:orc/event :final  :outputs {...} :usage {...} :model "..." :raw-response "..."}
{:orc/event :error  :error {:message "..." :class "..."}}
```

Exactly one `:final` or `:error` terminal event is emitted before the channel
closes. Applications that only call ORC workflows and do not consume this
channel directly require no streaming migration.

## Provider-facing enum schemas

Do not use numbers as choices in a provider-facing enum:

```clojure
;; Avoid
[:enum 1 2 3 4 5]

;; Prefer
[:enum "1" "2" "3" "4" "5"]

;; Or use domain names
[:enum :very-low :low :medium :high :very-high]
```

Provider protocols describe enum choices using JSON values and provider tool
schemas. Use strings or keywords for reliable structured output, then perform
numeric conversion or score derivation after validation.

Keyword enums are presented to providers using canonical JSON spelling. For
example, `:changed` is presented as `"changed"`, without the EDN leading colon,
and is decoded back to the declared keyword value.

## Verification

Search the consumer repository for migration leftovers:

```bash
rg -n ':dscloj-provider|dscloj\.|DSCloj' .
rg -n 'io\.github\.ObneyAI/DSCloj|ObneyAI/DSCloj' .
rg -n ':dscloj/event' .
```

All three searches should return no active source, test, configuration, or
dependency references. Archived documentation can be reviewed separately.

Then verify provider registration and runtime context in a fresh process:

```clojure
(llm/register-provider! :openrouter provider-configuration)

(:llm-provider context)
;; => :openrouter

(:dscloj-provider context)
;; => nil
```

Finally, run the consumer's complete test suite and one gated live-provider
smoke test. The live test should assert the configured provider and model as
well as the parsed output; a successful process start alone does not prove that
the LLM path is configured correctly.

## Troubleshooting

### ORC uses an unexpected provider

Check every execution and poller context for `:dscloj-provider`. The legacy key
is ignored and therefore cannot select the requested provider.

### A DSCloj namespace cannot be found

Remove the `dscloj.*` require and use `ai.obney.orc.llm.interface`. Do not add
DSCloj back merely to satisfy the old require.

### Function calling now surfaces an exception

The old hidden fallback has been removed. Treat the exception as a provider
failure and let the workflow's explicit retry policy handle it.

### Streaming handlers receive no recognized events

Read `:orc/event`, not `:dscloj/event`, and handle `:delta`, `:fields`, `:final`,
and `:error`.

### The provider is registered but calls still fail

Confirm that the registration alias exactly matches `:llm-provider`, the model
is valid for that provider, and the expected credential environment variable is
present in the process running the tenant poller.
