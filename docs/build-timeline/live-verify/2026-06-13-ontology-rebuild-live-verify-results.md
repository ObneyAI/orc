# Ontology-Rebuild Live-Verify Results — 2026-06-13

Real-LLM verification of the four LLM-bearing slices of the ontology-rebuild
initiative, run with a working OpenRouter key (the earlier runs were blocked by
an HTTP 401 on the maintainer's key). Models: `google/gemini-2.5-flash` (S12),
`gemini-3-flash-preview` (S15/S18/S19). Real Grain event store, real embeddings
(all-MiniLM-L6-v2), no mocks in the LLM tier.

This is the **ceiling** verification per Discipline 4 — synthetic green was the
floor; these are real runs against a real model and a real event store. Every
unexpected behaviour below is chased to a proven root cause (Disciplines 1–3),
and no bug is bypassed with fallback logic (Discipline 5).

---

## Headline

| Slice | Verdict | One-line |
|-------|---------|----------|
| **S12** dedup cascade | ✅ **12/12 pass** | LLM tier reached the 3 genuinely-ambiguous pairs and ruled correctly; cheap tiers handled the other 9 with zero LLM calls |
| **S15** CQ runner | ✅ **14/15 + 1 calibration boundary** | three-layer posture held: Layer-1 zero-LLM, Layer-3 explicit-unknown 5/5 with named gaps; the one "miss" is a defensible closed-world call, not a defect |
| **S19** RLM tools | ⚠️ **agent did not converge — real bugs surfaced** | `graph-search` went dark (all 3 signals empty) and the agent cascaded into shape/arity errors on the other tools. Root-caused below. |
| **S18** RLM discovery | ⏳ **in progress** | engaged the recursive-RLM machinery correctly (classify-behaviors → emit-tree! → tree tick executing); result pending |

The S19 result is the most valuable outcome of the exercise: the synthetic
tests (9 tests / 116 assertions, all green) could not catch it, and only a real
model driving the real tool surface exposed it. This is exactly why Discipline 4
mandates the live ceiling.

---

## S12 — dedup cascade (✅ 12/12)

Model: `google/gemini-2.5-flash`. 12 adversarial pairs (the prototype's ground
truth) run through the real cascade; the genuinely-ambiguous band hit the real
LLM judge.

**Per-tier resolution:**

```
{:exact-normalization 3, :string-similarity-high 1, :llm-verdict 3,
 :number-guard 2, :negation-guard 2, :entropy-gate 1}
```

- **9 of 12 pairs resolved by cheap deterministic tiers with ZERO LLM calls** —
  the cost discipline holds end-to-end against a real model.
- **3 pairs reached the LLM tier**, all ruled correctly:
  - `hasAuthor` vs `hasWriter` → **merge**, kind `:equivalent-property`
    ("both ... clearly refer to the same property of linking a work to its
    author")
  - `Paris` (city) vs `Paris` (person) → **distinct**, reason `:entity`
  - `Apple` (fruit) vs `Apple` (company) → **distinct**, reason `:entity`
- Number guard (`Model 3`/`Model 30`, `ISO 9001`/`ISO 9002`) and negation guard
  (`approved`/`not approved`, `present`/`absent`) kept all KEEP cases distinct
  **without** reaching the LLM — the guards fire before the ambiguity band.
- **Event provenance:** 42 events persisted under the primary tag, 5 under the
  alignment tag, each carrying the tier + reason that closed the verdict.

**Verdict: production-faithful.** The cascade behaves identically against a real
model as the prototype ground truth predicted.

---

## S15 — CQ runner (✅ 14/15 + 1 calibration boundary)

Model: `gemini-3-flash-preview`. 15 competency questions across the three
negation layers, against a seeded directors/films graph.

**Graph-health metric (real run):**

```
{:pass-rate 0.4, :fail-rate 0.2666, :unknown-rate 0.3333,
 :judge-share 0.7333,
 :layer-counts {:layer-1-structural 4, :layer-2-semantic-exists 6,
                :layer-3-explicit-unknown 5},
 :total-cqs 15}
```

- **Layer-1 (structural) — 4/4, zero LLM calls.** "Is there a Director concept?"
  / "...Wombat..." / "...Oscar..." / "...Mira Sun..." all resolved
  deterministically (`judge=N`). The zero-LLM guarantee holds against a real
  model.
- **Layer-2 (semantic-exists) — correct on 5/6.** "Which directors won an
  Oscar?" → pass (grounded in 6 evidence URIs). "Which directors directed a
  horror film?" / "Which films are documentaries?" → fail, each with explicit
  closed-world reasoning over the enumerated genres.
- **Layer-3 (explicit-unknown) — 5/5, with named gaps.** Every knowledge-gap
  question returned `:unknown` (NOT a comforting fail/pass), and the judge
  **named what was missing**:
  - "Did director Leo Bird retire?" → unknown, gap: *"retirement-status edges
    for Leo Bird"*
  - "Has Jane Roe won more Oscars than John Doe?" → unknown, gaps:
    *"award-count attributes", "dated award-win events"*
  - "What is Leo Bird's birth year?" → unknown, gap: *"birth-date attributes"*
  - "Which directors have collaborated with each other?" → unknown, gaps:
    *"co-direction relationships", "collaboration edges between directors"*

### The one "miss" — a calibration boundary, not a defect

**"Has Mira Sun directed any feature film?"** — judge returned `:fail`, the test
expected `:unknown`.

Fixture: Mira Sun has `has-role director` but **no** `directed` edge; the other
directors **do** have `directed` edges.

The judge made an explicit **closed-world** inference:

> "the graph enumerates director→film relationships and Mira Sun is excluded
> from that enumeration"

i.e. *the graph speaks to the topic (it records who directed what) and Mira Sun
isn't in it → NO.* The test author assumed **open-world**: film-direction is
incompletely recorded, so absence → UNKNOWN.

**Both are defensible.** This is the exact semantic boundary the three-layer
posture exists to surface, and the judge reasoned about it explicitly rather
than waffling. It is **not** a runner bug — it is a domain-semantics judgment:
*is the director→film relation a complete enumeration in this graph?*

**HITL calibration question for the user:** should Layer-2 treat a
relation-type that exists for some subjects but is absent for one subject as a
closed-world NO, or as an open-world UNKNOWN? The current judge leans
closed-world. If the desired posture is "absence of a per-subject fact is
UNKNOWN unless the graph asserts completeness," the judge prompt needs a
completeness-signal clause. Recorded for review; not changed unilaterally.

**Verdict: the three-layer posture works against a real model.** The single
divergence is a calibration choice for HITL, not a code defect.

---

## S19 — RLM ontology tools (⚠️ agent did not converge — REAL BUGS)

Model: `gemini-3-flash-preview`. A recursive-RLM session granted section A only,
tasked with "find all directors who have NOT retired, using ≥4 tools." The graph
was **pre-seeded** with 6 concepts (directors, films, a `director` role) + a
`directed` edge + a `retired` edge.

**Outcome: 8/8 iterations exhausted, `final-output` nil. The agent never
retrieved anything and flailed.** Transcript:

| Iter | Call | Result |
|------|------|--------|
| 0 | `(graph-search "director")` | **all signals empty** `{:results [] :graph-results [] :embedding-results [] :colbert-results []}` |
| 1 | `(filter-by-label-pattern ".*[Dd]irector.*")` | **arity error** (1 arg; needs `[uris pattern]`) |
| 2 | `(meta filter-by-label-pattern)` | *self-corrected* — fetched the docstring |
| 3 | `(graph-search "film")` | empty again |
| 4 | `(classify-task "Find directors and ...")` | **"Invalid classify-task opts"** (bare string; needs a map) |
| 5–6 | `(neighborhood "concept:industry/film-production")` | returns only the seed (a URI that doesn't exist in the fixture) |
| 7 | `(classify-behaviors [uris] {...})` | **arity error** (2 args; takes 1 `[opts]`) |

### Root cause — PROVEN by instrumented reproduction

Ran a controlled diagnostic (`/tmp/s19_diag.clj`) seeding the identical fixture
and probing each signal. Five hypotheses, all resolved:

- **H1 ✅** — 5 concepts ARE in the projection under section A (correct labels,
  scope). **Rules out a seed/projection defect.**
- **H2/H3 ✅** — `graph-search "director"` returns **0 / 0 / 0** across graph /
  embedding / ColBERT.
- **H4 ✅** — `graph-search "director" {:seed-uris ["concept:role/director"]}`
  returns a **real** result. **The BFS + scoping path is correct.**
- **H5 ✅** — a lexical label scan over the *same projection the tools already
  read* finds `concept:role/director` for "director" instantly.

**Keystone root cause:** `graph-search` has **no lexical/label bootstrap**. The
graph signal only fires when `:seed-uris` is non-empty
(`retrieval.clj:1060`); the embedding signal requires pre-generated embeddings
(`retrieval.clj:722`, docstring line 706: *"Requires concept embeddings to be
generated first"*); ColBERT requires an index. The S19 fixture seeds concepts
but never runs the embed/index stages, so on a bare text query **all three
signals are dark** — even though the concept literally named "Director" is right
there, findable by name. The model's natural first move ("search the word
director") cannot succeed, and that empty result is the **first domino** that
forced the model to improvise with the other tools.

**Secondary root causes (shape contracts) — characterised:**

- `filter-by-label-pattern` is a **post-filter** requiring `[uris pattern]`; the
  model reached for it as a **search primitive** (it had no URIs because
  graph-search was dark). Downstream of the keystone.
- `classify-task` requires a **map** `{:task-signature :string :threshold ...}`
  (both required); the model passed a bare string → `m/explain` rejects.
- `classify-behaviors` tool has only a **1-arg `[opts]`** arity; the model
  invented a `[seed-uris opts]` shape → hard arity error.

The unifying secondary cause: **the call contracts don't match the shapes a
capable model naturally reaches for, and the failures are hard arity/validation
errors rather than self-correcting guidance.** (Note: at iter 2 the model *did*
self-correct by calling `(meta filter-by-label-pattern)` — the teaching-error
path works when it exists.)

### Why synthetic tests passed but the real agent failed

The S19 synthetic suite calls each tool **with correct arguments** and asserts
correct behaviour (9 tests / 116 assertions, all green). It never exercised "a
real model deciding which tool to call, with what shape, on a graph that has no
embeddings yet." That decision surface is precisely what broke. **This is the
canonical Discipline-4 gap: shape-valid is the floor; real-task is the ceiling.**

### Proposed root-cause fixes (NOT yet applied — awaiting steer)

1. **Keystone — lexical bootstrap for `graph-search`.** When no `:seed-uris` is
   supplied, derive seeds via a lexical/label scan over the scoped concepts
   projection (the H5-proven machinery) and use them to seed the graph signal.
   This is a real missing retrieval capability (exact-name match), **not** a
   fallback masking a bug. **Location decision:** contained in the S19 tool
   wrapper (`make-graph-search-fn`, no blast radius) vs. in core `hybrid-search`
   (benefits all callers, touches a contract 18 slices ride on). Recommend the
   contained tool-wrapper fix first.
2. **Teaching errors + tolerant shapes** for `classify-task` (accept a bare
   string as `:task-signature`; default `:threshold`), `classify-behaviors`
   (tolerate the `[seed-uris opts]` arity or raise a shape-teaching error), and
   `filter-by-label-pattern` (raise a teaching error naming the `[uris pattern]`
   contract when called with one arg).

**Verdict: S19's plumbing is correct (H4), but the agent-facing surface has a
real ergonomics defect that blocks autonomous use. Fix + re-run required before
S19 can be called live-verified.**

### Fixes applied (S21) + the convergence progression

All fixes follow TDD (test → impl) with a full regression sweep. The live
re-runs are the ceiling that proved each one.

**Fix 1 (keystone, in core `hybrid-search` per steer): lexical signal.**
Added a first-class LEXICAL signal to `retrieval/hybrid-search` (slice S21,
test `s21_lexical_signal_test.clj`, 6 tests / 12 assertions). When `:query-text`
is present, the scoped event-sourced concepts are scanned for label matches
(exact > prefix > whole-word > substring); the hits are both a fused RRF signal
AND bootstrap seeds for the graph BFS when the caller gave no `:seed-uris`.
Opt-outable via `:signals`. Full sweep — **243 tests / 1226 assertions across
all 19 ontology slices + retrieval-batch + scoping + S19/S20 — 0 failures.**
S01 caps, S02 scoping, S03 alignment all unaffected.

**Fix 2 (agent ergonomics, in `sandbox_tools`): contracts that meet the model.**
Live re-run #2 (keystone only) proved the keystone alone was insufficient — the
model never even tried `(graph-search "director")`; it reached for
`filter-by-label-pattern` as a SEARCH primitive and `graph-search {:predicate}`
as a triple query. So:
- `filter-by-label-pattern` now dispatches on first-arg type: a pattern
  (string/regex) → SEARCH all scoped labels; a URI collection → POST-FILTER
  (back-compat). This is the label-search primitive the model repeatedly
  reached for.
- `graph-search` raises a TEACHING error on a non-string query (naming the
  string contract + pointing at find-edges/absent-in-graph?) instead of a
  cryptic cast error.
- `classify-task` / `classify-behaviors` tolerate a bare string (→
  `:task-signature`) and default `:threshold`, and tolerate a leading non-map
  arg. Core classifier contract stays strict; tolerance lives only at the tool
  boundary.

**Fix 3 (capability gap, in `sandbox_tools`): `find-edges` triple-pattern tool.**
Live re-run #3 (ergonomics fixes) achieved CONVERGENCE — the agent called
`final!` in 2 iterations and used `graph-search`/`filter-by-label-pattern`
correctly — but returned the WRONG answer (`:active-directors []`). Root cause:
the directors are reachable ONLY via the `directed` edge (no `has-role` link),
and there was NO tool to answer "which subjects have predicate P." The model
kept improvising (`neighborhood "directed"`, `graph-search {:predicate ...}`).
Added `find-edges` — a scoped triple-pattern relationship query
(`{:subject :predicate :object}`) reusing the same relationships machinery
`absent-in-graph?` uses. S19+S20 suites: **23 tests / 207 assertions, 0
failures**; RLM adjacent suites (recursive-rlm, rlm-mode): 74 tests / 257
assertions, 0 failures.

**The convergence progression (each run = the ceiling test for the prior fix):**

| Run | Fixes in place | Outcome |
|-----|----------------|---------|
| #1 | none | 8 iters exhausted; `graph-search` dark; total flailing |
| #2 | keystone (lexical) | 8 iters exhausted; `graph-search "text"` works but model reaches for wrong tool shapes |
| #3 | + ergonomics | **CONVERGED** (`final!` in 2 iters), correct tool usage — but wrong answer (no predicate-query tool) |
| #4 | + `find-edges` | converged + USED `find-edges {:predicate "directed"}` correctly, but answer `[nil]` — read `:subject` off a result whose key was `:source-uri` (input/output vocabulary mismatch) |
| #5 | + symmetric `find-edges` output | converged (6 iters) but `[]` — model GUESSED shapes/keys: `(find-edges :predicate …)` kwargs, `:subject-uri` (invented key), `(exists? uri pred obj)` wrong arity |
| #6 | + kwargs-tolerant `find-edges` + **docstrings injected into prompt (S20 card simulation)** | ✅ **CORRECT** — `:active-directors ["concept:dir/jane-roe" "concept:dir/john-doe"]`, retired sam-smith excluded |

**Run #6 (the validated production-representative run).** Clean, grounded
trajectory: `(find-edges {:predicate "directed"})` → read `:subject` back
correctly → `(filter-by-label-pattern "director")` 1-arg search → confirmed
active via `(absent-in-graph? uri "retired")` (correct 2-arg) → `final!` with the
right answer. The agent used the right tool for each step, with the right shapes,
and reached a grounded, correct conclusion — the discipline-2 QUALITY bar, not
just completion.

**The systemic finding (run #5 — the most important).** Each run peeled back a
layer until the real issue surfaced: **the model was guessing tool shapes and
result keys because the live harness only gave it tool NAMES** (plus a hint to
call `(meta tool)`, which it didn't use). It tried `find-edges` three ways
(kwargs / map / positional), invented the result key `:subject-uri`, and guessed
`exists?`/`absent-in-graph?` arities. Two responses:

1. **Meet the natural shapes** — `find-edges` now accepts a map OR kwargs; output
   carries `:subject`/`:object` (query vocabulary) alongside native
   `:source-uri`/`:target-uri`.
2. **Give the model the docs up front** — the harness now injects every tool's
   PURPOSE/EXAMPLE/RETURNS docstring into the prompt. **This is exactly what the
   S20 orientation card does in production** (`ontology-tool-docs` is the shared
   source). The earlier runs were testing the tools WITHOUT the affordance layer
   production ships — an unfair test. Run #6 is the fair, production-representative
   one.

This is itself a load-bearing lesson for the whole initiative: **the S20
orientation card is not optional polish — it is the difference between an agent
that guesses and an agent that copies the exact call form.** Any consumer
granting these tools must inject the card (or the docstrings) into context.

**Verdict so far: tool USE went from unusable → converging-correctly. The
remaining variable was whether the agent is GIVEN the tool contracts (card) or
left to guess. Run #6 isolates that.**

---

## S18 — RLM discovery (⏳ in progress)

Model: `gemini-3-flash-preview`. Discovery session granted one section, 5
ontology-discovery seed patterns offered, driven against an employment-policy
source fixture.

**Engaged the recursive-RLM machinery correctly:** called
`classify-behaviors` for patterns, then `emit-tree!` to design a discovery
tree, which is now executing a full behavior-tree tick with live LLM nodes
(the slowest path). Result (emitted concept/relationship/axiom drafts +
skeleton ingest) pending tick completion.

Note: unlike S19, S18's graph starts **empty** — the agent extracts concepts
from the *source text* using the seed patterns, so an empty `graph-search`
early is expected, not a blocker. The keystone bug therefore bites S18 far less
than S19.

*(This section will be updated when the run completes.)*

---

## Cross-cutting observations

- **The cheap/deterministic tiers are rock-solid against a real model.** S12's
  guards + S15's Layer-1 structural path both held with zero LLM dependence.
- **The judge-based semantic layers are well-calibrated** with one genuine
  domain-semantics boundary (S15 Mira Sun) worth a HITL decision.
- **The agent-facing tool surface (S19) was the weak point** — and it is exactly
  the surface that only a real model could stress. The bugs were real, proven,
  fixed at root, and re-verified live: the agent now reaches the CORRECT answer
  (run #6). The underlying retrieval/scoping machinery was correct throughout.
- **The S20 orientation card is load-bearing, not polish.** The single biggest
  lever between "agent guesses and fails" and "agent copies the call form and
  succeeds" was injecting the tool docstrings into context — which is precisely
  the card's job. Consumers MUST inject it when granting these tools.

---

## Final state

- **Code fixes (all TDD, all green):**
  - S21 lexical signal in core `hybrid-search` (`retrieval.clj`) +
    `s21_lexical_signal_test.clj` (6 tests).
  - `sandbox_tools.clj`: `filter-by-label-pattern` type-dispatch (search vs
    post-filter); `graph-search` teaching guard; `classify-*` string/threshold
    tolerance; new `find-edges` triple-pattern tool (map-or-kwargs, symmetric
    output). New ergonomics tests in `s19_sandbox_tools_test.clj`.
  - `s19_live_verify.clj`: injects tool docstrings into the prompt (S20-card
    parity) + lists `find-edges`.
- **Regression:** full sweep **231 tests / 1206 assertions / 0 failures** across
  all 19 ontology slices + S19/S20 + retrieval-batch + scoping; RLM adjacent
  suites (recursive-rlm, rlm-mode) 74/257, 0 failures.
- **Live verdicts:** S12 ✅, S15 ✅ (+1 calibration → open-world default
  recommended), S19 ✅ (run #6 correct), S18 ⚠️ tree-execution timeout
  (partially root-caused; needs designed-tree capture + tick instrumentation —
  separate targeted investigation; not maskable).

## S18 root cause — what the agent designed + whether time/iterations were the problem

Captured the full Phase-1 trajectory + the designed tree + the resolved budget
with `debug? true`. Findings (answering the framed questions directly):

1. **What the model designed — a SENSIBLE tree, not pathological.** For the
   555-char employment policy it emitted a single-pass DirectExtraction tree:
   `[:sequence [:llm extract-ontology :reads [:policy] :output-schemas {...}]
   [:code trace] [:final]]`. The right shape for a small source.
2. **Recursive self-correction WORKS.** On the first attempt the model wrongly
   called `final!` in the same tick as `emit-tree!` → got "final! called with
   all empty values" → on the next iteration it **learned**, emitted just the
   tree and stopped. Recursive inspection of its own failed attempt is
   functioning at the Phase-1 level.
3. **Time / iterations were NOT the constraint.** Phase 1 converges in 1–2
   iterations; the Phase-2 budget was ample. In fact it was *too* ample because
   of a bug (below) — the agent got 15 min, not the intended 3.
4. **Real bug #1 — budget wiring (FIXED).** `rlm-discovery` set the Phase-2
   budget under the node's `:options {:timeout-ms ...}`, but
   `resolve-phase2-budget` only read top-level `(:timeout-ms node)` — so the
   intended 180000 (3-min) budget was silently dropped to the 900000 (15-min)
   hardcoded fallback. Fixed `resolve-phase2-budget` to honor both paths
   (`executor.clj`), + a `phase2_budget_test` case. Live-confirmed: the budget
   now resolves to `{:total-budget-ms 180000 :source :node}`.
5. **Real bug #2 — the harness never started the tick engine (ROOT-CAUSED + FIXED).**
   The Phase-2 child tick (`execute-tree` → `:sheet/tick-tree`) registers a
   completion promise and `(deref p timeout-ms)` waits for it. The promise is
   delivered by the `deliver-execution-result` **todo processor**
   (`todo_processors.clj`) — a `defprocessor` that must be SUBSCRIBED to the
   pubsub to fire. The discovery live-verify harness (`s18_live_verify/make-ctx`)
   started the event-store/cache/registries but **never called `tp/start`** to
   subscribe the todo processors. So the child tick ran, its events fired, but
   nothing delivered the promise → `(deref p)` waited the full budget → timeout.
   Phase 1 worked because it's direct LLM calls (no ticks); only Phase 2 needs
   the engine.

   The targeted dig followed the chain: tree was valid (`:final`→`final!` is a
   recognized no-op output marker); `:output-schemas` was the correct key; the
   `litellm` log staying at 2 (no Phase-2 LLM call ever logged) pointed at the
   tick never being DRIVEN, not the leaf. Confirmed against the bench
   `runner.clj` and `recursive_rlm_test.clj` — BOTH call `tp/start` over
   `@tp/processor-registry*`; the discovery harness was the only one that
   didn't. **This is a harness gap, NOT a production bug** (real consumers run
   their Grain engine) and **NOT a model tree-crafting problem** (the same tree
   executes fine under the bench, which starts the engine). Fix: `make-ctx` now
   starts the todo-processor pipeline + threads `:dscloj-provider`, mirroring
   the unit-test/bench setup.

**Net for S18 — the user's instinct was correct on both counts.** The
infrastructure (structured output, timing) was fine, and the graph explorer's
tree was well-formed. The real issues were all in the plumbing around it. With
the tick-engine + budget fixes, **Phase 2 executes for real and the model
extracts a genuine ontology** — re-run produced `:status :emitted-drafts` with
3 concepts, 3 relationships, 2 axioms from the 555-char policy, and the model's
own reasoning confirmed recursive inspection ("the previous tree successfully
executed and extracted the structured ontology components").

6. **Discovery→ingest adapter fixes (`compile-discovery-source!`), surfaced once
   Phase 2 actually produced drafts:**
   - **Concept scope (FIXED).** Discovery is general-purpose, so the model
     invents a domain `:scope` (e.g. `:policy`) not in the ontology-scope enum.
     The adapter now coerces unknown/absent scopes to `:custom` instead of
     failing the create-concept command. + unit test.
   - **Relationship confidence-class (FIXED).** The JSON round-trip leaves
     keyword-typed fields as STRINGS — the model's relationships arrived with
     `:confidence-class "extracted"` (string) vs the enum's `:extracted`
     (keyword). The adapter now coerces string→keyword for both `:scope` and
     `:confidence-class` (unknown → `:custom` / `:extracted`). + unit test.

**S18 — END-TO-END LIVE-VERIFIED.** Final run: discovery `:emitted-drafts`
(3 concepts / 3 relationships / 2 axioms) → `compile-discovery-source!`
`{:status :ingested}` → **S17 deterministic-skeleton `build!` → `:complete`**.
The full discovery→ingest→build chain runs against a real LLM. Recursive
self-correction was observed AGAIN (the model hit a syntax error, inspected its
failed tree, and corrected to a single clean `emit-tree!`).

The pattern across all fixes: each removed one plumbing obstacle and revealed
the next, walking discovery from "hangs forever" to "extracts AND ingests AND
builds a real ontology end-to-end." None of the obstacles were the model's
reasoning or the LLM/structured-output infrastructure — exactly the user's
hypothesis. Fixes: tick-engine wiring (harness), budget wiring (`executor.clj`),
scope + confidence-class coercion (`rlm_discovery.clj`), all TDD-covered.

## S15 calibration decision — APPLIED + live-verified (open-world)

Defaulted the CQ judge to **open-world UNKNOWN**, returning `:fail` for a missing
per-subject fact ONLY when the graph carries an explicit completeness/closure
signal (S11 closure axiom / `:closed?` / `owl:oneOf`) or a direct negating edge.
An evolutionary, continuously-growing ontology is perpetually incomplete;
closed-world-by-default would make it confidently wrong in the direction it
grows, and would defeat the very reason the explicit-`:unknown` verdict exists.

**Implemented (TDD) and LIVE-VERIFIED:**
- Rewrote `judge-prompt-template` (`cq_runner.clj`): open-world framing; `:fail`
  now requires an explicit negating/closure signal; the prompt explicitly
  rejects inferring completeness from "an enumeration for OTHERS" (the Mira-Sun
  fix).
- Reframed `render-evidence-text`: enumerations labeled "open-world — NOT
  asserted complete"; added an EXPLICIT COMPLETENESS / CLOSURE / DISJOINTNESS
  block (grounded in S07 axioms via the newly-threaded `get-axioms-fn` + S11
  `:closed?` markers). When empty it tells the judge absence ⇒ `:unknown`.
- New deterministic test + updated prompt-assertion test. S15 suite: 17 tests /
  145 assertions, 0 failures.
- **Live run:** "Has Mira Sun directed any feature film?" → `:unknown` (was
  `:fail`): *"the graph is open-world and lacks any closure signal regarding her
  filmography."* "Did Leo Bird/Mira Sun retire?", "documentaries?", "horror
  film?" all correctly flip to `:unknown` with the judge citing the
  closure/completeness block. Graph-health moved to the honest open-world shape
  (`fail-rate 0.067` — only the genuine structural miss). The two live-verify
  "misses" vs the old script expectations ARE the intended change (the script's
  expected verdicts encoded the old closed-world posture and are now stale).

This ties S11 (closure axioms) → S15 (CQ judge) into one coherent open-world
posture for the evolutionary substrate.
