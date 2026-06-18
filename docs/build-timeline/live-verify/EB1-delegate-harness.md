# EB1 — Subbehavior-sheet harness + `:delegate` composition — LIVE VERIFY

**Branch:** `feature/ontology-architecture`. **Model:** `google/gemini-3-flash-preview` (real OpenRouter). **No mocks** — real Grain event store, real LLM, real async todo processors, real child tick.

Proves the composition backbone the evolutionary-builder re-architecture stands on: a SUBBEHAVIOR is a first-class composed ORC sheet, REGISTERED under a stable name → deterministic sheet-id, and a CENTRAL tree `:delegate`s to it (child tick, isolated blackboard, mapped `:reads`/`:writes`). The contract round-trips, and we prove the writes LANDED by reading the PARENT tick's blackboard back from the projection — not by trusting the `execute` return value.

## The subbehavior-sheet REGISTRY pattern

A delegatable subbehavior is named/versioned/looked-up by its WORKFLOW NAME. `build-workflow!` already derives a DETERMINISTIC v5-UUID sheet-id from that name (`dsl/sheet-id-for-name`) and is idempotent. So the registry is a NAMING CONVENTION over the existing name→sheet-id identity:

- **NAME** = `"<family>/<behavior>@v<N>"` — version is part of identity, so a new version is a new, separately-evolvable sheet; callers pinned to `@v1` are never rebuilt out from under them.
- **REGISTER** = `(build-workflow! ctx (subbehavior-def ...))` → returns the deterministic sheet-id.
- **LOOK UP** = `(sheet-id-for "<name>")` — a central tree resolves a subbehavior name to its sheet-id and points its `:delegate` node's `:target-sheet-id` at it, without rebuilding the subbehavior.

Registered names this run:

```clojure
{:subbehavior-name "eb1/echo-contract@v1",
 :central-name "eb1/central-delegator@v1",
 :sub-sheet-id #uuid "cd35f5c9-7dea-5182-a9b8-b5f6f2b263ff",
 :looked-up-sub-id #uuid "cd35f5c9-7dea-5182-a9b8-b5f6f2b263ff",
 :registry-id-match? true,
 :sub-found-by-name? true,
 :central-sheet-id #uuid "1781fa76-1e2a-553d-b95a-c7aee9a4b189"}
```

Registry round-trip: `build-workflow!`-returned id == `(sheet-id-for name)` == **true**; subbehavior found by name in the projection == **true**.

## Subbehavior is independently runnable (isolation)

The subbehavior was executed DIRECTLY on its own sheet-id via its `:reads`/`:writes` contract — no central tree involved.

- status: **:success** (2173ms)
- return outputs:

```clojure
{:input-contract
 {:goal "round-trip a contract through a delegated subbehavior",
  :source-kind "arbitrary",
  :payload {:a 1, :b [2 3], :c "three"}},
 :reasoning
 "The input contract contains 3 top-level keys: \"goal\", \"source-kind\", and \"payload\".",
 :echoed-contract
 {:goal "round-trip a contract through a delegated subbehavior",
  :source-kind "arbitrary",
  :payload {:a 1, :b [2 3], :c "three"},
  :echoed-by "eb1-echo-subbehavior",
  :field-count 3}}
```

## Delegate round-trip (central tree → child tick)

- central tree status: **:success** (1177ms)
- parent tick-id: `4f1ee5ba-92a8-4989-abbb-b145372d05cb`

### EVENTS LANDED — parent tick blackboard read back from the projection (discipline 7)

We do NOT trust the `execute` return value. We read `(rm/get-tick-blackboard ctx central-tick-id)` and assert the DELEGATED writes are present on the PARENT blackboard:

- `:reasoning` present on parent bb (discipline-13 think-before-emit): **true**
- `:echoed-contract` keys on parent bb: `#{:payload :goal :field-count :source-kind :echoed-by}`
- input `:payload` preserved verbatim through the round-trip: **true**
- input `:goal` preserved through the round-trip: **true**
- provenance keys (`:echoed-by`, `:field-count`) added correctly: **true**

Reasoning (written FIRST by the `:llm` body, verbatim from the projection):

```
I see 3 top-level keys in the input contract: `goal`, `source-kind`, and `payload`.
```

Echoed contract (verbatim from the parent-tick projection):

```clojure
{:goal "round-trip a contract through a delegated subbehavior",
 :source-kind "arbitrary",
 :payload {:a 1, :b [2 3], :c "three"},
 :echoed-by "eb1-echo-subbehavior",
 :field-count 3}
```

## VERIFY-NOT-ASSUME — measured child-tick delegation overhead (for EB12)

Overhead = delegate (central-tree) wall time − isolated-subbehavior wall time. Both paths run the SAME body (reasoning `:llm` + echo `:code`); the difference is the delegate child-tick dispatch + the parent←child `:reads`/`:writes` projection mapping. Single-shot is LLM-latency-dominated, so we take MEDIANS over multiple trials to cancel the noise:

Single-shot (the proof run above):
- isolated subbehavior: **2173 ms**
- central (delegate) tree: **1177 ms**
- single-shot delta: **-996 ms**

Multi-trial (5 trials each):
- isolation wall times (ms): `[1434 1179 1124 1282 1180]`
- delegate wall times (ms): `[1230 1250 1310 1165 1054]`
- isolation MEDIAN: **1180 ms**
- delegate MEDIAN: **1230 ms**
- **median delegation overhead: 50 ms**

The median overhead is the real per-`:delegate` child-tick cost EB12 must judge at scale; it is small relative to the LLM-call latency that dominates each subbehavior, and the per-trial spread shows it is within run-to-run LLM-latency noise.

