# Handoff — MT-12 SLICE 2: the coverage-aware LLM ranker sheet + delegate seam

**Parent plan:** `/Users/darylroberts/.claude/plans/precious-sleeping-kurzweil.md`. **SLICE 1 landed** (`931f4a9b` — the pure `select-containers` coverage heart consuming a coverage map + `:cqs`). This slice wires the REAL LLM ranker to emit that coverage map and threads it through the delegate seam. **PROTOTYPE-PROVEN** (SLICE 0, `development/src/mt12_coverage_select_prototype.clj`): the coverage-map schema + prompt parse reliably on real O\*NET and the LLM maps facet tables→facet CQs.
**Branch:** `feature/ontology-architecture` · commit-LOCAL only, NEVER push. `pgrep -f` hygiene; detached `nohup … &` for gate/live (Bash-tool background is being reaped this session).

## What to build — all in `components/ontology/src/ai/obney/orc/ontology/core/central_evolver.clj`

### A. The coverage-map schema (~near `selected-container-names-schema` ~810)
Add — apply the just-landed **MT-11 lesson** VERBATIM (concrete `[:vector [:map …]]`, concrete leaf types, `:description`s, a STRING `:enum` — NO bare keyword; that is exactly the shape the SLICE-0 prototype proved parses):
```clojure
(def container-coverage-key :container-coverage)
(def container-coverage-schema
  "MT-12 — the coverage-aware ranker output. Vector ORDER = relevance ranking (most-
   relevant FIRST); each entry names the container + the 0-based indices of the numbered
   competency-questions it helps answer. Concrete [:vector [:map …]] + concrete leaf types
   + string :enum (MT-11 C1 lesson) so DSCloj parses it (a bare :any/keyword → raw string).
   Domain-agnostic: names + indices are runtime-discovered."
  [:vector [:map {:closed false}
            [:name [:string {:description "the container's EXACT :name, copied verbatim from the candidates"}]]
            [:serves-cqs [:vector {:description "0-based indices of the numbered competency-questions this container helps ANSWER (may be empty)"} :int]]
            [:relevance {:optional true} [:enum {:description "overall relevance to the goal"} "high" "medium" "low"]]]])
```

### B. The ranker sheet + prompt (`select-rank-prompt` ~827, `select-rank-subbehavior-def` ~862)
- Blackboard: add `:competency-questions [:vector :string]`; change the write from `selected-container-names-key`/`…schema` to `container-coverage-key`/`container-coverage-schema`.
- `:reads [:goal :competency-questions :candidates]`; `:writes [:reasoning container-coverage-key]` (#13 reasoning first).
- Rewrite `select-rank-prompt` (a WORKING reference is the prototype's `coverage-prompt`): present the CQs as a NUMBERED list; ask, per container, its relevance + WHICH numbered-CQ indices it serves (0-based); still order most-relevant-first; KEEP the "use `:name` verbatim, never invent/rename/merge" guard; emit real structured data not a JSON/prose string. The prompt takes the CQ list as an arg (so the numbered list is rendered into the static instruction at sheet-build — note the CQs are also a runtime `:read`; render a generic "the numbered competency questions provided as input" framing, OR pass the list to the prompt fn if the sheet is built per-call — match how the existing sheet is built; the prototype rendered the list into the instruction and ALSO passed it as input, which worked).

### C. The delegate seam (`delegate-select-containers!` ~893-968)
- Destructure `:competency-questions` from the opts.
- The inner `rank-fn` (~927-957): add `"competency-questions" (or cqs [])` to the sheet `:inputs`, add `:competency-questions` to the `:bb-schema` + `:reads`, and read back `container-coverage-key` (the coverage MAP) instead of `selected-container-names-key`. Return the coverage map (vector of `{:name :serves-cqs …}`) — SLICE-1 `select-containers` already tolerates it. On non-success/degrade, return nil (honest degrade, unchanged).
- Call `select-containers` with `:cqs competency-questions` added to the opts (alongside `:goal :cap :rank-fn`).
- Keep the single-container short-circuit + the hard-failure catch (SLICE 4 hardens the catch; leave it).

### D. Backward-compat
When `:competency-questions` is nil/empty (this slice's callers until SLICE 3 wires STEP-3): the ranker still returns a coverage map with empty `:serves-cqs` (pure relevance ranking), and SLICE-1 `select-containers` with empty `:cqs` → no promotion → today's take-cap. Nothing regresses.

## TDD (tests first) + LIVE verify
- **Unit (extend `central_evolver` test ns or the select test):** the coverage-map schema flattens/parses via `executor` the way MT-11's schemas do — assert `malli-schema->description` on `container-coverage-schema` renders the `:enum` as "one of: high, medium, low" and `:serves-cqs` as "list of integer" (the concrete rendering that makes DSCloj parse). Assert the delegate seam threads `competency-questions` into the sheet inputs (with a stubbed `delegate-subbehavior!` capturing inputs) and passes `:cqs` to `select-containers`.
- **LIVE (the reviewer's, but you set it up):** extend `development/src/mt2_select_live_verify.clj` with a coverage arm — call `delegate-select-containers!` with a hand-authored CQ set (incl. a job-zone + interests CQ, like the prototype) at cap=6 on real O\*NET; assert the returned `:selection-report` carries `:cq-coverage`/`:promoted`/`:over-cap-dropped` and the facet tables are selected. (The reviewer runs it; leave it runnable.)

## Do NOT
Touch `select-containers` internals (SLICE-1, done) beyond passing `:cqs`. Touch the STEP-4 pipeline wiring (SLICE 3). Remove `selected-container-names-*` only if nothing else references it (grep first; if the old key is referenced elsewhere, leave it). NO domain names in code. NO bare-keyword enum (MT-11).

## Gate + hygiene
`clj -M:poly test brick:ontology` green (detached). ONE JVM at a time; 0 orphan this-repo JVMs after; consolidator flake — isolate ×3 if sole red.

## Deliverable — final message: unit tests red→green (gate line, exit 0); the schema + prompt + seam diff; confirm `malli-schema->description` renders the enum concretely (quote it); confirm the seam threads CQs (quote the stub-captured input); the live-verify arm is runnable (don't run it — reviewer does); no domain names / no bare-keyword enum; no commit/push; 0 orphan JVMs.

## Core Disciplines (verbatim)
1. NEVER assume; the shape is prototype-PROVEN. 2. Verify QUALITY — a schema that renders "any value" or a bare-keyword enum is the MT-11 bug; assert concrete rendering. 3. Instrument to root cause. 4. Live is mandatory (the reviewer's arm); unit is the floor. 5. No silent fallback — degrade returns nil honestly. 6. TDD, tests first. 7. NO hardcoded domain matching; concrete Malli + runtime CQs. 8. Re-orchestrate — extend the existing sheet/seam; don't fork. 9. Adversarial — hunt an unparseable coverage map or a dropped CQ thread. 10. Deterministic skeleton (SLICE-1) + LLM discovery (this) — verify the discovery parses. 11. Key = env var; JVM hygiene (detached, one at a time, `pgrep -f`, 0 orphans). 12. Domain-agnostic. 13. `:reasoning` FIRST on the ranker `:llm` node.
