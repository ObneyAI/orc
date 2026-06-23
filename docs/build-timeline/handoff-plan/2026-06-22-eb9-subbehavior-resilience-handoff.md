# EB9 Handoff — Subbehavior-internal resilience (reusable fallback / gate / troubleshoot)

Fresh-context brief for EB9 (`docs/build-timeline/issues/evolutionary-builder/EB9-subbehavior-resilience.md`).
Crafted post-merge from the REAL `:fallback`/`:condition`/`:llm-condition` semantics +
the EB2–EB8 sheets it hardens. Work DIRECTLY on `feature/ontology-architecture` (NOT a
worktree). DO NOT COMMIT/PUSH — leave staged; the orchestrator `/inspect-orc`s then
commits locally. Implement via `/tdd`.

## The goal
A **reusable RESILIENCE pattern** composed into the subbehaviour sheets so a
subbehavior **self-corrects** or **fails CLEANLY WITH A DIAGNOSIS** before poisoning
downstream or returning. Three reused primitives (via `orc-service.interface`):
- **`:fallback`** — runs children IN ORDER, succeeds on the FIRST success (primary/
  cheap path → robust path). (dsl.clj `fallback` ~L305.)
- **`:condition`** (`{:check {:key … :op … :value …} :on-fail :failure}`, dsl ~L183) /
  **`:llm-condition`** (`{:model … :instruction "<yes/no>" :reads […]}`, dsl ~L195) —
  GATES on intermediate state (is the node's output sane / non-empty / scoped?).
- a **troubleshoot `:llm` node** — compose Investigation (root-cause) + Validation
  (check): on a bad intermediate state, reason about WHY and emit a structured
  DIAGNOSIS. `:reasoning` FIRST (#13).

## Read first
1. `components/orc-service/src/ai/obney/orc/orc_service/core/dsl.clj` — `fallback` (~L305), `condition` (~L183), `llm-condition` (~L195). These are re-exported by `orc-service.interface` (use the interface, boundary-correct).
2. The EB2–EB8 subbehavior sheets (`survey_subbehavior.clj`, `model_subbehavior.clj`, `extract_subbehavior.clj`, `reconcile_subbehavior.clj`, `axiom_tbox_subbehavior.clj`, `embed_index_subbehavior.clj`, `validate_cq_subbehavior.clj`) — what EB9 composes resilience INTO. Note their failure modes (e.g. Extract's `:llm` author can mis-ground → 0 concepts; Embed needs a bridge; the V17/V20 over-extraction).
3. `components/ontology/resources/seeds/behavioral-subtrees.edn` — the Investigation + Validation corpus behaviors (the troubleshoot node's root-cause + check pattern — reuse the pattern, don't fork).
4. The FallbackRecovery caveat (memory `emit_tree_extensions_pending`): `:fallback` works in HAND-COMPOSED top-level sheets TODAY — use that. RLM-*emitted* (Phase-2) `:fallback` stays a tracked follow-up; do NOT depend on it.

## What EB9 must build
- A **reusable resilience sub-tree builder** (e.g. `with-resilience` / `resilient-step`): given a primary node (or node-fn) + a robust alternative + a sanity gate, produce a `:fallback`[primary→robust] wrapped/guarded by a `:condition`/`:llm-condition`, with a troubleshoot `:llm` node that emits a structured DIAGNOSIS on unrecoverable failure (clean `:failure` carrying the diagnosis, NOT a poisoned/empty success — the EB6 honest-gap + #4 no-false-green ethos).
- **Compose it into the subbehaviors (EB2–EB8).** Apply the wrapper where each subbehavior has a failure-prone step (the `:llm`-bearing ones first: Model/Extract/Axiom/Validate). If composing into all 7 cleanly is mechanical, do it; otherwise harden the representative ones + make the builder cleanly composable into the rest, and REPORT the breadth + why.

## Do NOT
- Fork the dsl `:fallback`/`:condition`/`:llm-condition` or the Investigation/Validation behaviors (reuse). Depend on RLM-emitted `:fallback` (deferred). Let a troubleshoot path RETURN A FAKE SUCCESS (it must fail-with-diagnosis or genuinely recover — no masking, #4/#5). Hardcode failure-phrase matching (#7/#12). Touch unrelated files. Commit/push. Create a worktree.

## Prototype (WORTH — do first)
Settle the reusable resilience sub-tree on ONE real induced subbehavior failure: take
a real subbehavior (e.g. Extract), INJECT a failure (e.g. force the primary author to
mis-ground → 0 concepts), and show (a) the gate detects the bad intermediate state,
(b) the `:fallback` recovers via the robust path OR (c) when unrecoverable, the
troubleshoot node emits a DIAGNOSIS and the subbehavior returns a CLEAN failure that
does NOT poison downstream. Capture it.

## Verify
- `/tdd` red→green: the resilience builder exists + is composed into ≥ the representative subbehaviors; tested via the subbehavior's `:reads`/`:writes` contract.
- **INDUCED-FAILURE verify (the core):** inject a RECOVERABLE failure on a real subbehavior → it self-corrects via `:fallback` (downstream sees a good result); inject an UNRECOVERABLE failure → it returns a clean `:failure` WITH a diagnosis (troubleshoot ran; downstream is NOT poisoned — read the projection back, discipline 7). Both captured (`docs/build-timeline/live-verify/EB9-resilience.md`).
- **Gate hygiene:** the deterministic resilience structure (fallback wiring + `:condition` gate + the builder) is hermetic → fast brick gate. If the troubleshoot/`:llm-condition` live path drives real LLM, that test goes to `development/ontology-integration/...` (on-demand). Decide per measured runtime; report which.
- Green under BOTH `clj -M:poly test brick:ontology` AND `:dev:test` of the EB9 ns. Re-run the affected subbehaviours' suites (EB2–EB8) — composing resilience in MUST NOT regress them.
- JVM hygiene: bounded runs; 0 orphan THIS-repo JVMs after (exclude sibling worktrees — kill only your own by PID).

## Report back (raw data)
The resilience builder design; which subbehaviors it was composed into + the breadth decision + why; the prototype + induced-failure captures (recoverable→fallback recovers; unrecoverable→clean failure WITH diagnosis, no downstream poison); which lane the tests went in; dual-runner totals + confirmation EB2–EB8 suites still green; "0 orphan THIS-repo JVMs"; every file changed by path; honest negatives. DO NOT COMMIT/PUSH. Binding Core Disciplines block 1–13 (in the EB9 issue) in force verbatim.
