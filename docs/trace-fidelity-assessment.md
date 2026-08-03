# Trace fidelity assessment — post "Storage amplification reduction" (18cabc6)

**Date:** 2026-08-02
**Reviewed:** `18cabc6` (+ `ea08c00` docs) against a live BRYC recommendation-generation run
**Verdict:** The storage win is real and the event log itself is lossless. Three defects sit in the
*rehydration* layer — the code that turns the shape-only trace back into values. All three are silent:
they return plausible data rather than failing, so nothing downstream reports an error.

> **Status: F1, F2a, F2b and F3 all confirmed fixed at scale by round 4** — the trace data is now a
> faithful, correctly-attributed record. **But a new finding, F4, makes that data unreachable:** the
> documented evaluation entry points (`get-llm-traces`, `get-node-stats`) return empty for every
> sheet, and always have. See [Verification round 4](#verification-round-4--data-correct-api-dead)
> at the end. The findings below are left as written; they are the record of what was wrong and how
> it was measured.

---

## How this was measured

A full recommendations run for a real student, executed on this branch, read back from the
in-memory event store over nREPL.

- Generation window: `2026-08-02T12:13:58.044-07:00` → `12:21:57.343-07:00` (~7m59s)
- 5,491 events, 27.09 MB Fressian-encoded
- 21 `:sheet/execution-traced` events, 268 node traces, 268 `:sheet/node-execution-completed`
- Single generation in the store; no sheet events fall outside the window, so attribution is clean

A prior run on the pinned SHA (`cefbb57`, different student — not a controlled A/B) produced
4,705 events / **137.13 MB**. Per-event averages tell the story better than the totals:

| Event type | avg before | avg after |
|---|---|---|
| `:sheet/execution-traced` | 3.05 MB | 3.3 KB |
| `:sheet/tree-tick-completed` | 806 KB | 437 B |
| `:sheet/node-execution-completed` | 84.7 KB | 579 B |
| `:sheet/node-execution-started` | 64.7 KB | 2.6 KB |
| `:sheet/execution-value-written` | 50.2 KB | 43.9 KB |

The reduction landed exactly where it was aimed. `:sheet/execution-value-written` is now 76% of a
run (20.47 MB of 27.09 MB) and is the remaining target if more is wanted.

---

## What is intact (verified, not assumed)

| Signal | Evidence |
|---|---|
| Node outputs / writes | 260/268 completions carry `:write-keys` + `:write-profile`; every value resolves from the write log |
| `value-log/writes-for` attribution | Tick `04c6ae16`: 87 completions → 87 distinct `[node-id exec-context]` keys; 27 map-each iterations → 27 distinct `:overview-narrative` values |
| LLM token usage | 179/268 completions carry `:usage` (e.g. `{:prompt-tokens 2624 :completion-tokens 612 :total-tokens 3236}`) |
| Durations, status, node names, parent-ids, node-type | Present on all 268 node traces |
| Tick outputs | `tree-tick-completed` stores `:output-keys` only; `deliver-execution-result` rehydrates the full blackboard from the tick-context read model — `runtime/execute`'s `:outputs` contract holds |
| Trace snapshots | `:input-snapshot` / `:output-snapshot` retained (2 KB / 5 KB total across all 21 traces) |

**`value-log.clj` is correct.** Every defect below is a caller that fails to use it, or uses it and
then discards its result.

---

## F1 — Leaf-node inputs are gone entirely

**Severity: high.** Every grounding judge on a leaf node now scores against `:inputs {}`.

222 of 268 node traces have no `:read-keys` and no `:input-profile`:

| node-type | n | with `:read-keys` |
|---|---|---|
| leaf | 222 | **0** |
| delegate | 18 | 18 |
| sequence / map-each / parallel | 28 | 0 (expected — control nodes don't read) |

### Cause

`commands.clj:1069` derives `read-values` from the completion command's `:inputs`:

```clojure
read-values (into {} (filter (fn [[k _]] (and (keyword? k) (nil? (namespace k)))) inputs))
```

and `commands.clj:1118` emits `:read-keys` / `:input-profile` only when `(seq read-values)`. This
commit stopped `node-execution-started` from inlining resolved blackboard reads (correctly — that
was the amplification), but `execute-leaf-node` passes only `exec-context` on the completion
command (`todo_processors.clj:1140`), so `read-values` is empty for every leaf.

Before this commit, the trace assembly took leaf `:inputs` from the started event
(`18cabc6^:todo_processors.clj` ~2938, the `completed-reads` / `started-reads` fallback). That path
is now dead for leaves.

Downstream, `trace_extraction/tick-node-io` does `:inputs (pick (:read-keys nt))` — with no
read-keys it returns `{}`. This is the exact failure its own docstring warns about:

> Judges need the real values for grounding, so an empty map here would silently degrade every
> grounding score rather than fail loudly.

Partial backstop: `:sheet/rlm-tree-node-completed` still carries `:input-profile` for 179 LLM leaf
calls. That is shape only, and only for LLM leaves.

### Fix

Costs no storage — `commands.clj` reduces read-values to shape before they reach the event. The
blackboard is already in hand at `todo_processors.clj:1140` (the same fn computes
`compute-input-profile` 13 lines later, just gated on `is-llm-call?`):

```clojure
;; todo_processors.clj:1140 — currently
(seq exec-context) (assoc :inputs exec-context)

;; proposed — values are stripped to :read-keys + :input-profile by commands.clj:1118
(seq read-values)  (assoc :inputs (merge exec-context read-values))
```

Add a `storage_budget_test` assertion that the emitted event still carries no simple-keyword
`:inputs`, so this cannot silently regress into re-inlining.

---

## F2 — Rehydrated inputs are wrong when a key is written twice in a tick

**Severity: high.** Wrong values, not missing ones — worse than F1 because it is invisible.

`trace_extraction.clj:130-141`: outputs go through the execution-attributed `writes-for`, but inputs
go through `pick`, which reads a **last-write-wins** map:

```clojure
values (reduce (fn [acc e] (assoc acc (:key e) (:value e))) {} write-events)
pick   (fn [ks] (into {} (for [k ks :when (contains? values k)] [k (get values k)])))
```

20 keys are written more than once within a single tick in this run.

### Reproduction

Tick `33ac7009-8bca-4861-b1e1-738cd4548031`, node `d-pers-college`:

| | |
|---|---|
| Ran | 12:15:16.118 → 12:15:18.387 |
| Its own retained `:input-profile` for `:programs-to-personalize` | `{:type :vector, :length 20}` |
| It wrote | 20 personalized programs |
| `tick-node-io` rehydrates its input as | **6 items** — the value `a-short-in` wrote at 12:15:25, 7s after it finished |

The trace's own profile contradicts the rehydrated value, so this is directly assertable in a test.
Same exposure on `:college-programs`, `:short-term-programs`, `:personalized-programs`,
`:filtered-institutions`, `:short-term-output`, `:apprenticeships-output`.

Note F1 masks F2 today for leaves. Fixing F1 alone makes F2 *more* visible, not less — reads still
resolve by bare key.

### Fix

Two options, in order of preference:

1. **Record the write provenance.** At completion time, capture the `:event/id` of the write each
   read resolved to, alongside `:read-keys`. Rehydration becomes an exact lookup with no ordering
   heuristics. Costs one UUID per read key.
2. **Bound by time.** Resolve `k` to the last write at or before the node's `:started-at` within the
   tick. Cheaper, but relies on clock ordering and is wrong under concurrent writers to the same key.

---

## F3 — `tick-node-io` collapses map-each iterations to the last execution

**Severity: high.** Affects the output side too, so the "outputs are lossless" claim holds at the
event layer but **not** at the trace-extraction layer.

`trace_extraction.clj:137`:

```clojure
writes-by-node (reduce (fn [acc c]
                         (assoc acc (:node-id c) (value-log/writes-for events c)))
                       {} completions)
```

`writes-for` is called with the right execution key — then the result is filed under bare
`:node-id`. Under map-each, N iterations of the same child node-id overwrite each other, last one
wins. The caller (`get-in io-by-trace [trace-id (:node-id node-trace)]`) then hands that single I/O
pair to every one of the N node traces.

### Reproduction

| Trace | node-traces | distinct node-ids | max repeat |
|---|---|---|---|
| `171616f1` | 35 | 4 | 32 |
| `04c6ae16` | 87 | 9 | 27 |
| `d7e6871c` | 66 | 9 | 50 |

Node `fd11c210-0dcf-5a9c-b642-0af08699737b` in tick `04c6ae16`: 27 node traces, 27 completions,
**27 distinct write-sets**, `tick-node-io` returns **1** entry — and it matches execution index
**26 of 27**. So 26 of 27 iterations are served another iteration's outputs.

Across the run, **166 of 268 node traces** (62%) receive I/O belonging to a different execution.

### Fix

Key the returned map — and the node traces themselves — by `value-log/execution-key` rather than
`:node-id`. `value-log/execution-key` already handles both event shapes (`:exec-context` on write
events, `:inputs` on lifecycle events); this is the identity the rest of the pipeline uses, and
`trace_extraction` is the one place that dropped back to bare node-id.

---

## Suggested order of work

1. **F3** first — pure rehydration-layer change, no emission change, and it is the prerequisite for
   trusting any per-iteration assertion you write for F1/F2.
2. **F1** next — one-line emission change plus a byte-budget guard.
3. **F2** last — needs a design call between provenance ids and time-bounding.

A regression test that would have caught all three: assemble a trace for a tick that (a) has a leaf
with declared reads, (b) writes one key twice with different values, and (c) runs a map-each with
≥2 iterations; then assert every node trace's rehydrated `:inputs`/`:outputs` matches that
execution's own `:input-profile`/`:write-profile`. The profiles are already stored and are cheap
ground truth — `storage_budget_test` guards the bytes, and this would guard the meaning.

---

## Unrelated observation

`:node-type` is `nil` on all 268 completion events, so the C-2a-2 per-node-type aggregator is
partitioning on nil. This commit did not touch that path (traces get node-type from the sheet
definition instead), so it is pre-existing — flagged here only because it surfaced during the same
sweep.

---

## Resolution

All three defects fixed, in the suggested order, plus the unrelated observation.

### F3 — iteration collapse

`assemble-execution-trace` now records `:exec-context` on every node trace
(`::node-trace` schema updated), making each execution addressable.
`trace_extraction/tick-node-io` and its caller key on `[node-id exec-context]` — the same identity
`value-log/execution-key` uses — instead of a bare node-id.

### F1 — leaf reads

`execute-leaf-node` now passes the node's resolved reads on the completion command
(`extract-read-inputs`, already in that namespace). `complete-node-execution` reduces them to
`:read-keys` + `:input-profile` before they reach the event, so this costs no storage: measured
amplification moved 1.49× → 1.51× and the duplication ratio stayed at 0.00.

### F2 — read provenance

Took option 1 (provenance), not time-bounding. The tick-execution-context read model now records
`:source-event-id` on each blackboard entry, `read-sources` captures `{read-key → write event id}`
at completion, and rehydration is an exact lookup by event id. Keys *seeded* into a tick have no
write event and fall back to the tick's `:inputs`; a final last-write-wins fallback covers events
that predate the field.

### Unrelated observation — `:node-type`

Plumbed through all five completion sites (`execute-leaf-node` ×2, delegate, condition,
composite-parent); previously only the repl-researcher path supplied it. Verified non-nil on real
events. **Open question for the owner:** leaves now report `:leaf`, consistent with the one
pre-existing call site. If the C-2a-2 metric wants `:ai`/`:code`/`:tool` granularity — as
`streaming/node-info` uses for leaves — that is a one-line change, but it is a semantics decision
rather than a bug fix, so it was left alone.

### Regression test

`components/evaluation/test/.../trace_fidelity_test.clj` — the test this assessment asked for. One
tick with a leaf that reads, a key written twice (the second write *after* its reader finished), and
a map-each with 4 iterations. The general assertion is the one suggested here: every node trace's
rehydrated `:inputs`/`:outputs` must profile identically to that execution's own stored
`:input-profile`/`:write-profile`. Three named tests sit alongside it so a regression reports *which*
defect returned.

The oracle was verified to actually detect the defects rather than merely pass: running the
pre-fix rehydration against a trace from the fixed engine returns 500 chars where 100 is correct
(F2) and 1 distinct iteration output where 4 is correct (F3).

`storage_budget_test` gained `completion-events-carry-read-shape-not-read-values`, asserting the
completion event carries no simple-keyword `:inputs` — so F1's fix cannot silently regress into
re-inlining read values.

---

## Verification round 2 — independent re-measurement

**Date:** 2026-08-02, after the fixes described in [Resolution](#resolution)
**Method:** a second full BRYC recommendation-generation run (different student), read back over
nREPL and checked with the profile oracle this document proposed, plus an index-order oracle the
profile check cannot express.

- Window `15:58:07.215-07:00` → `16:05:16.560-07:00` (~7m09s)
- 5,149 events, **25.93 MB** — 21 traces, 250 node traces, 250 completions
- Single generation in the store; attribution clean

### F1 — confirmed fixed

| node-type | n | with `:read-keys` |
|---|---|---|
| leaf | 204 | **204** (was 0 of 222) |
| delegate | 18 | 18 |

All 222 reading nodes carry `:read-keys` + `:input-profile`. The storage guard holds independently
of the unit test: **0** completions carry a simple-keyword `:inputs`, **0** carry inlined `:writes`.
Run total 25.93 MB against 27.09 MB pre-fix — the shape-not-values contract is intact.

### F3 — confirmed fixed

`:exec-context` is present on all 250 node traces and `tick-node-io` keys by execution.

**Outputs: 250 of 250 node traces rehydrate to values that profile exactly against their own stored
`:write-profile`. Zero mismatches.** Iteration collapse is gone.

### F2 — NOT fixed; split into two new failure modes

**Inputs: 153 of 250 node traces mismatch. 172 of 441 read keys (39%) fail to rehydrate at all,
and 17 node traces rehydrate to `{}`.**

#### F2a — the `seeded` fallback never fires (string vs keyword keys)

18 of 21 ticks seed `tree-tick-started :inputs` with **String** keys (`"student-analysis"`), not
keywords. `resolve-reads` tests `(contains? seeded k)` with `k` a keyword, so the branch never
matches; it falls through to `latest`, which is empty because the value was written in the *parent*
tick. The read key is then dropped by the `:when (some? v)` guard.

Missing reads: `:student-analysis` ×85, `:student-eligibility` ×62, 17 other keys ×25 = 172.

Two defects compound here, and both are worth fixing:

1. **The trigger.** `declare-key`'s invariant is that blackboard keys are always simple keywords —
   the nested-tick seeding path violates it. Normalize at the seeding site rather than papering
   over it in the resolver.
2. **The amplifier.** `resolve-reads` treats `(contains? sources k)` as terminal. When the
   event-id lookup misses, the key is dropped instead of falling through to `seeded` / `latest`.
   Making that fallthrough non-terminal would have salvaged most of these even with defect 1 present.

#### F2b — `:read-sources` races on map-each item keys

Map-each item writes carry `:node-id nil` and `:exec-context nil` — the parent emits them before
starting the child — so they are not execution-attributable, and the shared blackboard slot's
`:source-event-id` is clobbered by concurrent iterations.

Tick `45930984-6004-446a-9123-cbc2ea4ba5a5`, item key `:program`, 26 iterations against 26 item
writes. Correct mapping is iteration *i* → write *i*:

| iteration | 0 | 1 | 2 | … | 9 | 10 | 13 | 16 | 22 | 23 | 25 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| resolved write | 9 | 9 | 9 | … | 9 | 10 | 14 | 18 | 24 | 24 | 25 |

Iterations 0 through 9 all resolve to write #9.

Across the whole run: **60 of 154 map-each item reads (39%) resolve to the correct iteration.**
94 are served another iteration's item.

| tick | item key | iterations | correct |
|---|---|---|---|
| `45930984` | `:program` | 26 | 9 |
| `7016845d` | `:program` | 46 | 13 |
| `7016845d` | `:drafted` | 23 | 12 |
| `e8f9b977` | `:verify-candidate` | 50 | 25 |
| `e8f9b977` | `:explain-candidate` | 9 | 1 |

**Fix:** put `:exec-context` (map-each index + parent) on the item writes so they are
execution-attributable like every other write, and resolve the child's read source by execution key
rather than through a shared blackboard slot. This is the same identity fix F3 already applied on
the output side — the input side still routes through a slot that has no room for it.

### The regression test will not catch F2b

`trace_fidelity_test`'s general assertion — rehydrated values must profile identically to the stored
`:input-profile` — is a **lower bound** on this class of error. It only fires when the wrong
iteration's item happens to have a different shape. On this run it flagged **9** of the **94** known
misattributions; the other 85 resolved to a different item that profiled identically
(`{:type :map, :length 34}` either way).

The 4-iteration fixture in the test is also small enough that a race may not reproduce at all.

To close the gap, add an assertion that does not depend on shape:

- **Item identity.** Give each map-each item a distinguishing *value* (not just a distinguishing
  shape) and assert iteration *i* rehydrates item *i*. The index-order oracle used here —
  sort item writes by timestamp, assert `map-each-index` equals write position — is the cheap
  version and is what produced the table above.
- **Nested-tick seeding.** The current fixture appears to run a single tick. F2a only appears when a
  child tick reads a key it was *seeded* with rather than one written inside it, so the fixture needs
  a nested tick whose child reads a parent-written key.
- **Total resolution.** Assert every `:read-key` on every completion rehydrates to *something*.
  That single check catches all 172 F2a drops and needs no ground-truth values at all.

### Standing green

Everything previously verified as intact remained intact on this run: 161 of 250 completions carry
`:usage`; durations, status, node names, parent-ids present throughout; `:node-type` now non-nil on
242 of 250 completions (was 0). No re-inlining anywhere.


---

## Resolution round 2

Both new failure modes fixed, both root causes rather than resolver patches, and the three test
gaps closed. Verified in-repo; **not yet confirmed against a live run** — the measurements below are
from the regression fixture, not production scale.

### F2a — seeded reads

Fixed at both levels round 2 identified.

**Trigger.** `:sheet/tick-tree` now normalizes `:inputs` keys to keywords before they reach the
event, so the stored event agrees with `declare-key`'s invariant. The tick-execution-context read
model already keywordized on projection; only the raw event disagreed, and the resolver reads the
raw event. Verified: a delegated child tick's seeded keys are now `(:doc)`, previously strings.

**Amplifier.** `resolve-reads` was a `cond` — the first matching branch was terminal, so a
`:read-sources` hit that failed to resolve dropped the key instead of trying `seeded`. It is now an
ordered `or`: every step falls through on a miss. A new test asserts every declared read rehydrates
to *something*, which needs no ground truth and would have caught all 172 drops on its own.

### F2b — map-each item races

Fixed at the root, as round 2 recommended: the item write is now execution-attributable rather than
being resolved through a shared blackboard slot.

`make-bb-write-event` takes an attribution; both map-each dispatch sites stamp each item write with
the `:node-id` and `:exec-context` of the iteration that will read it. Rehydration resolves the
child's item from that, not from `:read-sources`.

One correction surfaced while implementing it, caught by this document's own profile oracle: a
map-each child commonly **reads and writes the same key**, so `[node-id exec-context]` alone is
ambiguous — is a write the value that execution was *given*, or the value it *produced*? Attribution
therefore carries `:input-seed?`, and `value-log` splits into `writes-by-execution` (outputs, seeds
excluded) and `input-seeds-by-execution` (inputs). Without the split, every iteration resolved its
input to its own output.

Measured on a 12-item, concurrency-8 fixture of same-shape items: **old resolver 2/12 correct, new
12/12.**

### Test gaps closed

All three additions round 2 asked for:

| Gap | Test |
|---|---|
| Shape-blind item identity | `f2b-map-each-items-resolve-to-their-own-iteration` — 12 same-shape items (`{:type :map :length 2}` either way), concurrency 8, asserts iteration *i* rehydrates item *i* by `:id`. The profile oracle is blind here by construction. |
| Nested-tick seeding | `f2a-nested-tick-seeded-reads-resolve` — parent sheet delegates to a child that reads a key it was seeded with rather than one written in its own tick. |
| Total resolution | `f2a-every-read-key-resolves-to-something` — every `:read-key` on every completion must rehydrate. No ground truth needed; catches drops of any cause. |

The fixture concurrency was also raised (2 → 8 with 12 items) so a race has room to appear.

### Not addressed

- **`:node-type` on 8 of 250 completions.** Round 2 measured 242/250. The 8 are the paths that
  emit a completion without a node in scope; not chased down.
- **Third live run.** F1/F3 were confirmed at scale by round 2; F2a/F2b have only fixture-level
  evidence so far. The same three checks round 2 used — read-key resolution rate, item index-order
  oracle, profile mismatch count — are what to re-run.

---

## Verification round 3 — third live run

**Date:** 2026-08-02, after the fixes described in [Resolution round 2](#resolution-round-2)
**Method:** a third full BRYC recommendation-generation run (third distinct student), same three
checks round 2 called for — read-key resolution rate, map-each item index oracle, profile mismatch
count.

- Window `16:40:06.500-07:00` → `16:46:16.838-07:00` (~6m10s)
- 4,952 events, **26.10 MB** — 21 traces, 238 node traces, 238 completions
- Single generation in the store; attribution clean

### Holding from earlier rounds

| | Evidence |
|---|---|
| **F1** | 192/192 leaves carry `:read-keys` + `:input-profile`. Storage contract intact: **0** completions with a simple-keyword `:inputs`, **0** with inlined `:writes`, run total 26.10 MB |
| **F3** | `:exec-context` on all 238 node traces; **238/238 outputs** profile exactly against their own `:write-profile` |

`:node-type` non-nil on 237/238 completions; 149 carry `:usage`.

### F2a — confirmed fixed at scale

Fixed at the source, which is the right layer: **all 21 ticks now seed `tree-tick-started :inputs`
with keyword keys** (round 2 measured 18 of 21 seeding with Strings).

**All 421 read keys rehydrate. 0 missing** — round 2 had 172 missing (39%). 178 keys still carry no
`:read-sources` entry and resolve through the `seeded` fallback, which now actually fires.

### F2b — NOT fixed; the write side was corrected, the read side was not

The emission half of the recommendation landed: map-each item writes now carry `:node-id` **and**
`:exec-context` with the iteration index — 284 of 429 writes. That is real progress, and it turns
this defect from something inferred via timestamp ordering into something directly measurable.

The resolution half did not land. A child's read source is still taken from the shared blackboard
entry's `:source-event-id`, which concurrent iterations clobber.

**83 of 142 map-each item reads (58%) resolve to a write stamped with a different iteration index.**

| item key | reads | correct |
|---|---|---|
| `:program` | 63 | 28 |
| `:verify-candidate` | 50 | 21 |
| `:drafted` | 19 | 9 |
| `:explain-candidate` | 10 | 1 |

This is not an indexing artifact. For every one of the 83:

- the write stamped with the **reader's own index exists in the same tick** (83/83), and
- the resolved value **genuinely differs** from that correct value (83/83).

The signature is the same shared-slot clobber seen in round 2, now legible with exact indices rather
than inferred positions — readers at index 0, 1, 2, 4, 5 and 7 all resolve to the write stamped
index 9.

**Remaining fix:** resolve a map-each child's read source by execution key
(`[node-id exec-context]`, the identity F3 already uses on the output side) instead of reading
`:source-event-id` off the shared blackboard entry. The write events now carry everything required;
nothing further needs to be emitted.

### The profile oracle is now demonstrably blind to F2b

Round 2 predicted the profile assertion is a lower bound on this class of error. Round 3 settles it:

| round | F2b misattributions present | caught by profile oracle |
|---|---|---|
| 2 | 94 | 9 |
| 3 | 83 | **0** |

This run reported **zero** input mismatches and **zero** output mismatches while 83 reads were
served another iteration's item. Wrong-iteration items are the same shape, so shape equality cannot
see them.

**Replace the shape assertion with an identity assertion.** Now that writes carry the index, the
check needs no fixture ground truth at all:

> For every completion carrying a `map-each-index`, each `:read-sources` entry must resolve to a
> write event whose `:exec-context` map-each-index equals the reader's.

That is exactly the check that produced the table above, it runs against any real trace, and it
would have failed loudly in both rounds 2 and 3.


---

## Resolution round 3

Round 3 was right on both counts: F2a landed, F2b's read side did not. One root cause, now fixed,
plus the test-design change round 3 asked for.

### F2b — why the read side missed

The resolver *did* have an execution-attributed lookup for item writes. It was keyed on
`[node-id exec-context]` — and that is the wrong identity.

An item write is stamped with the map-each's **direct child**. When that child is a composite, the
node that actually reads the item is a *descendant* with a different node-id, so the lookup missed
and fell through to `:read-sources` — the clobbered shared slot. Production uses composite children;
the fixture used a leaf child, so the keys matched there and the bug was invisible.

**The iteration identity is `exec-context` alone.** `(map-each parent, index)` is shared by every
node executing inside that iteration whatever its own node-id. `input-seeds-by-execution` became
`input-seeds-by-iteration`, keyed accordingly.

Measured on a composite-child fixture, 12 items at concurrency 8: the old node-id keying found an
item for **0 of 12** grandchild executions — it missed entirely, every time, and fell through. The
new keying resolves **12 of 12** correctly.

### `:read-sources` inside an iteration

Round 3's diagnosis — a child's read source comes from a slot concurrent iterations clobber —
applies to the recorded field itself, not just its use. `read-sources` now returns `{}` when
`exec-context` is non-empty. Inside a map-each iteration the blackboard holds one
`:source-event-id` per key and any id captured there names an arbitrary iteration's write.
Recording a plausible-but-wrong id next to a correct exec-context is worse than recording nothing,
because a consumer would trust it; the iteration identity is the reliable one and travels on both
the completion and the item write.

### Test design — shape assertion replaced, as round 3 specified

Round 3 settled that the profile oracle is blind to this class: 83 misattributions, 0 caught. Two
identity assertions replace it for map-each:

- **`f2b-composite-child-descendants-resolve-their-own-item`** — map-each whose child is a
  *sequence*, with two grandchildren reading the item (one of them after a sibling has run, so it
  cannot lean on an `:inputs` overlay either). Asserts iteration *i* resolves item *i* by `:id`.
  This is the shape that a leaf-child fixture cannot produce, and it is why two rounds passed here
  and failed in production.
- **`f2b-read-sources-never-name-another-iterations-write`** — round 3's proposed check verbatim:
  every `:read-sources` entry must resolve to a write whose `:exec-context` map-each-index equals
  the reader's. No fixture ground truth; runs against any real trace.

Both were verified to *discriminate*, not merely pass: replaying the pre-fix keying against the same
trace reproduces the production failure (0/12 resolved).

### `:node-type`

Round 3's remaining 1 of 238 was the map-each parent's own completion. Now supplied at every
completion site; the fixture reports 0 nil.

### What to check on run 4

`development/src/trace_fidelity_verify.clj` — `(v/report ctx)` and `(v/clean? ctx)`. Weight
`:map-each-item-collisions` over `:input-profile-mismatch`; round 3 proved the latter is blind here.

---

## Verification round 4 — data correct, API dead

**Date:** 2026-08-02, fourth live run (fourth distinct student)
**Window** `19:05:10.342-07:00` → `19:13:08.165-07:00` (~7m58s) — 5,109 events, **24.69 MB**,
21 traces, 249 node traces, 249 completions, single generation.

### The trace data is now correct

First fully clean round. Four independent oracles:

| check | result |
|---|---|
| Outputs profile against their own `:write-profile` | **249/249** |
| Inputs profile against their own `:input-profile` | **249/249** |
| Read keys resolving to a value | **439/439** |
| map-each item reads landing on their own iteration | **153/153** |

Storage contract intact: 24.69 MB, **0** completions with a simple-keyword `:inputs`, **0** with
inlined `:writes`. `:node-type` non-nil on 248/249; 160 completions carry `:usage`.

F2b was closed by making the read-source lookup unnecessary rather than by fixing it:
`value-log/input-seeds-by-iteration` indexes item values by iteration and `resolve-one` consults it
first. A side effect is a further storage win — `:read-sources` entries fell from 245 to 103,
because item reads no longer need them.

#### Correction to round 3's proposed oracle

The identity assertion round 3 proposed — "each `:read-sources` entry must resolve to a write whose
`:exec-context` index equals the reader's" — **produces false positives.** A `:sequence` inside a
`map-each` re-seeds the item between steps, so one `(key, iteration)` pair legitimately has several
writes and different readers correctly see different ones.

Tick `b137076a`, iteration 3 — two seeds, two readers, both correct:

| reader | started | its `:input-profile` | rehydrated |
|---|---|---|---|
| `overview-draft` | 19:07:17.069 | `{:type :map, :length 3}` | 3 ✅ |
| `outcomes-draft` | 19:10:13.184 | `{:type :map, :length 4}` | 4 ✅ |

The naive oracle flagged all 23 `overview-draft` reads. The correct ground truth is **the last item
write for `(key, iteration)` at or before the reader's `:started-at`**. With that bound: 153/153.
Use this form in `trace_fidelity_test`, and give the fixture a map-each whose body is a sequence
that rewrites the item — otherwise the test cannot distinguish the two.

---

## F4 — the documented evaluation entry points return nothing

**Severity: high. Pre-existing — not caused by the storage work.** But it means every fidelity fix
in this document is currently unreachable through the public API.

`get-llm-traces` is documented as "the main entry point for getting evaluation data." Measured
across this run:

| | |
|---|---|
| Sheets with raw traces | 20 (21 traces total) |
| `get-traces-raw` rows | 21 |
| **`get-llm-traces` rows** | **0, for every sheet** |
| **`get-node-stats` rows** | **0** |

### Cause — one predicate, two independent bugs

```clojure
;; trace_extraction.clj:71
(defn- is-llm-node? [node-trace]
  (contains? #{:llm :llm-condition :repl-researcher "llm" "llm-condition" "repl-researcher"}
             (:executor node-trace)))
```

`get-llm-traces` applies this with `llm-only? true`, so it gates every row.

1. **Wrong source.** Node traces have no `:executor` — **0 of 249** carry the key. The trace
   assembly copies the node's `:type` as `:node-type` and drops `:executor`. It never did otherwise:
   `git show 18cabc6^` has zero occurrences of `executor` in that code path. The predicate is
   therefore always false and the filter always empties the result.

2. **Wrong vocabulary.** Fixing the lookup alone will not help. `:llm`, `:llm-condition` and
   `:repl-researcher` are **node-type** values (see the `::node-type` enum,
   `interface/schemas.clj:17`). The `:executor` field takes `:ai` / `:code` / `:tool`. Measured over
   all 100 nodes in this run: `{:code 41, :ai 14, nil 45}` — **0** match the accepted set. The
   predicate tests the executor field against node-type values.

### The data it would surface is there and is good

`build-nodes-map` already reconstructs executors correctly from `:sheet/node-executor-set`, and
`get-llm-traces` already builds that map and passes it to `extract-node-trace-data`. Over this run:

- 14 nodes have `:executor :ai`
- **all 14** carry both `:instruction` and `:model`
- their I/O rehydrates correctly (they are inside the 249/249 above)

So the payoff is 14 fully-populated LLM node traces per run that currently surface as zero.

### Fix

Two edits, both small:

1. **Give the predicate the right source.** Either copy `:executor` onto node traces at assembly
   time (alongside `:node-type`, in `assemble-execution-trace`), or have `is-llm-node?` take the
   nodes-map entry — `get-llm-traces` already has it in scope.
2. **Give it the right vocabulary.** Match on `:ai` for executors, and keep the node-type values
   (`:llm-condition`, `:repl-researcher`) as a separate node-type check rather than folding both
   into one set. Today's set silently conflates the two axes.

### Blast radius

There are **no in-repo callers** of `get-llm-traces`, `get-node-stats` or
`format-trace-for-evaluation` outside `interface.clj`, which only re-exports them. `judge_runtime`
routes off `:type` from the read model on its own path, so judges are not affected. The exposure is
external consumers and anyone following the docs — these functions are advertised in
`EVENT-STORE-PATTERNS.md` and `GETTING-STARTED.md` as the way to get evaluation data.

**Worth confirming before closing:** whether any GEPA or out-of-repo evaluation path depends on
these. If so it has been scoring against empty input, and that predates this whole storage effort.

### Regression guard

Add an assertion that the public path is non-empty, not just the internals: run a fixture sheet with
at least one `:ai` node and assert `get-llm-traces` returns a row whose `:inputs`, `:outputs`,
`:instruction` and `:model` are all populated. Every check written so far exercises `tick-node-io`
directly and would pass with the public API returning `[]` — which is exactly what happened.
