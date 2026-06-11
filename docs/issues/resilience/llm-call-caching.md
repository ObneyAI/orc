# LLM Call Caching (deferred)

**Status:** local-only follow-up; not currently blocking.

## What to build

Add a deterministic-cache layer for LLM calls so that re-emitting the same
sub-LLM node (same `:instruction` + same `:reads` values + same model)
returns the prior response from cache instead of re-calling the provider.

## Why

The recursive RLM loop preserves successful writes in `sandbox-vars` across
iterations, but it has no per-node memoization. If the model emits a tree
that re-runs the same `:llm` node as a prior iteration, the sub-LLM call
fires again — burning tokens and wall clock on redundant work. The
prompt-level "recover from a failed tree" nudge mitigates this for SMART
models, but a deterministic cache layer would make it impossible to waste
the call regardless of model judgment.

The R-Inject runs are particularly exposed to this: as the rich prepend
drives more sophisticated tree designs (parallel branches, code-built
aggregators), the surface area for partial-failure-then-retry grows, and
the "re-emit the whole tree" anti-pattern becomes more expensive in
absolute tokens.

## What exists today (audited 2026-06-02)

- **`litellm-clj`** has cache config SCHEMAS (`::cache-type #{:memory :redis :s3}`,
  `::cache-config`, etc.) in `src/litellm/specs.clj` and `src/litellm/schemas.clj`
  — BUT the `completion` fn in `router.clj` passes straight through to provider
  with no cache lookup. The schemas are aspirational stubs that never got
  implementation. This is the natural place for the cache to live.
- **`dscloj`**: no caching anywhere in the source tree.
- **`orc-service`**: has an LMDB kv-store cache in context (`:cache`), used
  for snapshot/event caching only. No LLM-response keys.
- **`predict-rlm` family**: each sub-LLM call routes through dscloj →
  litellm-clj → provider with no cache layer.

So: building LLM caching is net-new infrastructure, not a reuse.

## Where to build

`litellm-clj`'s `completion` fn at `src/litellm/router.clj:67-88`. The
config schemas already define the cache surface; the implementation gap
is the function body. Approx 50-80 LOC for:

1. Cache-key derivation: stable hash over `(model, normalized-request)`
   where normalized-request strips non-determinism-relevant fields (e.g.,
   request-id, timestamps, retry counters) but keeps `messages`,
   `temperature`, `max-tokens`, etc.
2. Cache backend: start with `:memory` (atom-backed LRU keyed by hash).
   `:redis` and `:s3` are aspirational per the schema.
3. TTL via `::ttl-seconds`.
4. Bypass flag in request (`:bypass-cache? true`) for cases where the
   caller wants fresh output (e.g., explicit re-roll, temperature 0
   determinism check).
5. Hook the response shape to record `:cache-hit? true/false` so
   observability + token accounting can distinguish.

## Tradeoffs / design questions

- **Determinism vs creativity.** Caching is correct when `temperature` is
  low and the prompt is identical. For `temperature > 0` calls the same
  prompt is INTENTIONALLY non-deterministic — cache might be wrong by
  default. Solution: cache only when `temperature <= 0.1` OR when caller
  explicitly opts in via `:cacheable? true`.
- **Cache key normalization.** Two prompts that differ only in
  whitespace or quoting should hit the same cache entry. Decide:
  semantic equivalence (canonicalize) or syntactic (raw hash)? Lean
  syntactic — semantic equivalence is open-ended.
- **Invalidation.** Memory cache lifetime = process lifetime. Redis/S3
  caches need TTL. For RLM iteration loops, we want the cache to live
  within a single tick (so we save the retry cost) but NOT across ticks
  (where the context may have changed). Suggests a per-tick cache
  attached to the tick context, separate from any global cache.

## Acceptance criteria

- [ ] `completion` in `litellm-clj/router.clj` checks cache before calling
      provider when `:cacheable? true` (or `temperature <= 0.1`) is set
- [ ] Cache hit returns cached response with `:cache-hit? true` annotation
- [ ] Cache miss calls provider, stores response, returns with
      `:cache-hit? false`
- [ ] Per-tick cache lifetime via context-attached LRU for the RLM use case
- [ ] Bench task: prove a deliberately-failing tree's retry doesn't
      double-call the same `:llm` node when caching is enabled

## Cross-references

- Failure-recovery prompt nudge (the soft-cache mitigation already shipped):
  `executor.clj` "Recover from a failed tree" section
- Parse-error diagnostic hints: `executor.clj` `diagnose-parse-error` fn
- Conversation context: 2026-06-02 R-Inject rich-prepend session discussion
  of doubling/tripling cost when downstream errors in trees force retries.
