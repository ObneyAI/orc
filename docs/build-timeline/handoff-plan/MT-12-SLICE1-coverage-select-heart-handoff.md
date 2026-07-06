# Handoff — MT-12 SLICE 1: the pure coverage + bounded-promotion + honest-report heart of `select-containers`

**Parent plan:** `/Users/darylroberts/.claude/plans/precious-sleeping-kurzweil.md` (CQ-coverage-aware container selection). **Prototype (SLICE 0) PASSED** — mechanism proven live (coverage map parses; LLM maps facet tables→facet CQs; deterministic select covers all CQs). This slice is the PURE deterministic heart — NO LLM, fully unit-testable.
**Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. `pgrep -f` hygiene.

## What to build — rewrite `select-containers` in `components/ontology/src/ai/obney/orc/ontology/core/container_select.clj` (~148-194)
New signature: `(defn select-containers [candidates {:keys [goal cqs cap coverage-slack rank-fn]}] …)`.

Add a named ceiling const near `default-sample-limit` (~22):
```clojure
(def default-coverage-slack
  "Bounded headroom above `cap` the CQ-coverage guarantee may PROMOTE into. Absolute
   ceiling = min(count survivors, cap + slack) — promotion adds at most `slack` extra
   per-container child ticks downstream; extract concurrency + window caps untouched, so
   the memory/time envelope is unchanged (#2/#5)."
  8)
```

Algorithm (pure, total — a WORKING reference is `development/src/mt12_coverage_select_prototype.clj` `coverage-select`):
1. **Pre-filter** — unchanged: drop `:keep? false` candidates → `:dropped [{:name :shape :reason <shape>}]`.
2. **Rank + cover** — call `rank-fn` (when present). TOLERATE three return shapes:
   - **coverage map** `[{:name … :serves-cqs [<int> …] :relevance …} …]` (the new SLICE-2 output) — vector ORDER is the relevance ranking;
   - **flat name vector** `["name" …]` (the OLD output / back-compat) → treat each as `{:name n :serves-cqs []}`;
   - **nil / throw** → honest degrade to survivor LIST ORDER, empty coverage.
   Then **RECONCILE** (extend today's discipline, `container_select.clj` ~173-186): keep only entries whose `:name` is a known survivor; preserve first-occurrence order; **append** survivors the ranker omitted at the END with `:serves-cqs []` (never a silent drop, #5); **clamp** each `:serves-cqs` to the valid index range `[0, (count cqs))` and dedupe. Result = `ordered` — a vector of `{:name :serves-cqs}` in relevance order (carry the full survivor map for the selected output).
3. **Base take** — `base = take cap ordered` (today's behavior preserved when cqs empty).
4. **Coverage check** — `covered = ⋃ :serves-cqs over base`; `uncovered = (range (count cqs)) − covered`.
5. **Bounded promotion** — `ceiling = min(count ordered, (+ cap (or coverage-slack default-coverage-slack)))`. Walk `uncovered` in CQ-index order; for each, promote the highest-ranked NOT-yet-selected `ordered` entry whose `:serves-cqs` contains it; append it (track `:promoted`). Stop when `uncovered` empty OR selected count = `ceiling`. **HARD BOUND — selected never exceeds `ceiling`** even if CQs remain uncovered. A CQ no survivor serves is honestly uncoverable (skip it, it lands in `:uncovered`).
6. **Return** `{:selected [<full container maps, in selected order> …] :dropped [...] :report {…}}` where `:report` gains (on top of today's `:containers-total :survivors :selected :dropped`):
   - `:containers-truncated?` — `(> (count ordered) (count selected))` (mirror `extract_subbehavior/extract-truncated?`).
   - `:over-cap-dropped` — the `ordered` entries NOT selected, each `{:name :shape :reason :over-cap :rank <0-based idx in ordered>}`.
   - `:promoted` — entries pulled above `cap` for coverage, each `{:name :for-cqs [<idx> …]}`.
   - `:cq-coverage` — `{:total-cqs (count cqs) :covered [<idx>…] :uncovered [<idx>…] :complete? <bool>}`.

## Backward-compat (MUST preserve — the no-CQ / old-caller path == today's behavior)
- `cqs` nil/empty → step 4 `uncovered` is empty → NO promotion → `selected = base = take cap ordered` → EXACTLY today's behavior. `:cq-coverage {:total-cqs 0 … :complete? true}`.
- `rank-fn` returning the OLD flat name vector → each `:serves-cqs []` → same as above (no coverage signal, take-cap).
- `rank-fn` nil / failed → survivor list order, take-cap (today's honest degrade).
- The existing `select-containers` callers/tests that pass `{:goal :cap :rank-fn}` (no `:cqs`) must stay green.

## TDD cycle (tests FIRST, red→green, PUBLIC `select-containers`, in `container_select_test.clj`)
1. **Coverage map + promotion lifts an uncovered facet:** survivors A..H; a coverage map where the only container serving CQ 3 is ranked 7th (beyond cap=6); assert it is PROMOTED into `:selected`, `:promoted` records it `:for-cqs [3]`, `:cq-coverage :complete? true`, and selected count ≤ ceiling.
2. **Bounded — promotion never exceeds `cap + slack`:** many uncovered CQs each served only by a distinct low-ranked container, `coverage-slack 2` → selected count = `cap + 2` exactly, remaining CQs land in `:uncovered`, `:complete? false` (surfaced, not swallowed).
3. **Honest truncation:** `> survivors than cap`, all CQs covered by the base → `:containers-truncated? true`, `:over-cap-dropped` lists the cut survivors with `:reason :over-cap` + `:rank`; nothing promoted.
4. **Back-compat:** (a) nil `cqs` → `selected = take cap` (today), `:cq-coverage :total-cqs 0`; (b) `rank-fn` returns a flat NAME vector → tolerated, take-cap; (c) `rank-fn` nil → list-order take-cap. Cite/keep the existing `select-containers` tests green.
5. **Reconcile:** a coverage map with an INVENTED name (not a survivor) → ignored; an OMITTED survivor → appended at end with `:serves-cqs []`; an out-of-range `:serves-cqs` index → clamped/dropped.

## Do NOT (this slice)
Touch the LLM ranker sheet / prompt / `delegate-select-containers!` / `central_evolver` threading (those are SLICE 2/3). Touch the classifier, extract, or the safety nets. NO domain names in code (no facet/table/column literals — everything is indices + names from the runtime `cqs`/candidates). NO silent drops (every cut is in `:over-cap-dropped` or `:dropped`; every uncovered CQ in `:uncovered`).

## Gate + hygiene
`clj -M:poly test brick:ontology` green — run DETACHED (`nohup … &` then poll the file; the Bash-tool background mechanism is being reaped this session). ONE test JVM at a time; kill orphan this-repo JVMs after; known consolidator flake — isolate ×3 if it's the only red.

## Deliverable — final message: the 5 tracers red→green (final gate line, exit 0); the `select-containers` diff + the const; quote the bounded-promotion assertion (selected ≤ ceiling) + the `:uncovered` honest-surface assertion; confirm back-compat tests green + no domain names in code; no commit/push; 0 orphan JVMs.

## Core Disciplines (verbatim)
1. NEVER assume; the mechanism is prototype-PROVEN. 2. Verify QUALITY not completion — a promotion that blows the ceiling, or an uncovered CQ swallowed, is the bug; test both. 3. Instrument to root cause. 4. Live is the floor elsewhere; here the PURE fn is fully unit-provable — exhaust it. 5. No silent drop — every cut/uncovered is surfaced. 6. TDD, tests first, public fn. 7. No hardcoded domain matching — indices + runtime names only. 8. Re-orchestrate — extend `select-containers`, reuse the reconcile discipline; don't fork. 9. Adversarial — hunt an unbounded promotion or a silent drop. 10. Deterministic skeleton — this IS the deterministic guarantee; verify it exhaustively. 11. JVM hygiene (detached, one at a time, 0 orphans, `pgrep -f`). 12. Domain-agnostic. 13. n/a (no `:llm` node here).
