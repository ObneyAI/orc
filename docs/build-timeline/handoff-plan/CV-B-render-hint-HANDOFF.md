# CV-B handoff — render + repair-hint bundle

Issue index: `docs/issues/domain-child-convergence/README.md`; decisions C5, C7; ADR 0007; plan `PLAN.md`
("Verified code seams", Render, Closure hint). Work in `/Users/darylroberts/Desktop/Code/orc-convergence-arc`.
Runner as in CV-A; one JVM at a time; no commits; never edit `specs/*.allium`. Another agent (CV-A) owns
`components/ontology/**` and the wedge's classification-dispatch block of `todo_processors.clj` (≈503–850); you own
the RENDER section of `components/orc-service/src/ai/obney/orc/orc_service/core/todo_processors.clj` (≈1196–1400 and
`apply-r05-classifier-context` ≈1880+), `executor.clj`, `rlm_sandbox.clj`, and the tests named below.

## Goal

The model always sees which family it is working on and what that family has learned; and the executor's rejection
of an evaluated closure in an emitted tree carries a hint that names the fix, before and after it happens.

## Contract with CV-A (do not wait for it)

CV-A makes every reach of a family a landing, so the wedge's `:domain` payload
(`{:assigned-via :parent-tree-id :child-tree-id :domain-label}`, built at ≈834–841 only for the three domain
provenances) will be present on every landing. You build against that payload as it exists today; your tests
construct payloads directly (as `rs4_waterfall_render_test` does).

## The exact change

1. **Family body under the child line.** In the newborn branch of `format-structural-section` (≈1363–1373) and
   `structural-display-candidates` (≈1336–1347): fetch the family's body ONCE (`fetch-tree-body`), keep the parent
   entry as primary until `consolidated-from-event-count ≥ 1` (D5), and beneath the child line render the family's
   own substance whenever it has any — purpose/capabilities, strengths, weaknesses, representative uses — through
   `format-seed-body` with the same caps as a seed (`traits-per-seed-cap`); the child line itself is unchanged.
   The injection record lists the parent AND the family (at its body version) via `structural-display-candidates`
   (add the family candidate with `:document-metadata {:granularity :tree-class :target-id child}` and the top
   match's score/reasoning) — cc13's single-source rule.
2. **SPECIALIZE bullet** unchanged; the shape-context line (consolidated branch) unchanged.
3. **Closure hint, preventive:** a bullet in the "Common pitfalls" block (`executor.clj` ≈2967–3016), adjacent to
   the inline-fn bullet (≈2998–3005): "in a checkpointed campaign, write every `:code` node's `:fn` as a literal
   quoted `(fn …)` inside the `emit-tree!` call — never bind the tree or a function to a name first; an
   already-evaluated closure cannot be recorded as durable source and the tree is rejected." Scope it to
   checkpointed runs if the block is built where `checkpointed?` is in scope; otherwise unconditional.
4. **Closure hint, reactive:** export a constant from `rlm_sandbox.clj` (e.g. `quoted-inline-function-source-message`)
   used in the throw (≈656–662) and keep the ex-data `{:requirement :quoted-inline-function-source}`; in
   `execute-rlm-code`'s catch (≈1046) carry `:error-data (ex-data e)` alongside `:error`; add an arm to
   `diagnose-parse-error` (≈1839–1871) keyed on the ex-data keyword (or, where only the message survives, on
   `(str/includes? msg quoted-inline-function-source-message)` — the constant, not a regex) returning the fix as a
   "Diagnostic hint". No regex over prose.

## Tests (write all, one RED run, then GREEN)

`rs4_waterfall_render_test.clj`: `newborn-family-with-strengths-renders-them-under-the-child-line` (payload
`:domain` + stubbed child body with one strength and one weakness at count 0 → the strength trait and weakness trait
appear AFTER the child line's index and BEFORE the behavioral section; the parent entry still first; the injection
candidates contain the child id at its body version); `newborn-family-without-substance-renders-only-the-child-line`
(byte-identical to today's newborn render); `consolidated-family-unchanged` (existing test stays green). Executor:
new `closure_rejection_hint_test.clj` (orc-service tests): constructing the ex-info the sandbox throws and passing it
through the catch → `:error-data` carries the keyword; `diagnose-parse-error` (via the public history renderer if it
is private — test through `build-iteration-history` output) contains the hint; the pitfalls block contains the bullet
(assert on the constant's text you injected, not a regex). Guards: `r-inject-classifier-context-test`,
`cc13-injection-record-test`, `w2p1-claim-holdout-test`, `rr22-offered-pattern-whole-test`, existing rs4 tests,
executor's existing hint tests (grep `diagnose-parse-error`/`Diagnostic hint` in orc-service tests), the sandbox's
`checkpointed-inline-closure-is-rejected` tests.

Live QA (you): none (no LLM); the orchestrator's end-to-end subset will show the hint firing on the marathon task,
which failed twice on this rejection in the last e2e run.

## Disciplines (verbatim — do not summarise, do not skip)

- **Never assume. Chase every bug to its ROOT CAUSE.** Reproduce → minimise → fix the actual cause; rule out the
  harness itself.
- **Bundle discipline (user direction):** write the whole bundle, run its new tests once RED before implementing the
  pure parts (capture the RESULT line), implement, GREEN, then the live QA named below. Do not run a JVM per tiny
  change; troubleshoot the bundle together.
- **Test behavior through public interfaces**, on structured data the runtime emits (provenance keywords, identities,
  labels, verdict maps, event bodies, graph edges, rendered values the test injected) — never regex or phrase matching
  over model-authored prose, in tests or in production.
- **Injected-capability seams** default to the real implementation and are faked in tests; a store failure in a
  lookup defers, never fails open.
- **Never weaken an existing test to make it pass**; if an existing assertion breaks, report it as a finding with the
  RESULT line and your reading (some Slice 0 characterisation tests are MEANT to flip — the brief names them).
- **Report faithfully** — including your own mis-steps and anything you couldn't verify. Never write the API key.

## Do NOT touch

`specs/*.allium`; `components/ontology/**`; the wedge's classification-dispatch block of `todo_processors.clj`
(≈503–850); `development/**`; the holdout controls and `record-injection!`'s row shape.

## Report back

Files changed; RED/GREEN RESULT lines verbatim; the rendered block from the strengths test verbatim; the pitfalls
bullet text and the hint text verbatim; anything you could NOT verify; the orphan check.
