# RH1 — enforce the exact researcher declared-output contract

**Spec obligations covered:** `LeafExecutor.DeclaredOutputsOnly`,
`LeafExecutor.ResearcherFinalizationUsesDeclaredSchemas`,
`LeafExecutor.ResearcherOptionalWritesPreserveNestedSchemas`

## Verified mechanism

PR #34 validates a reduced view of successful researcher outputs, but first removes
every nil-valued declared write and then restores the untouched raw output map after
validation. Consequently, a mixed `final!` result can persist a required nil, and a
declared value normalized by Malli can be replaced by its unnormalized raw form.

The repair must distinguish three concepts precisely:

- required top-level writes are present, non-null, schema-valid, and normalized;
- top-level writes explicitly named by `:options :optional-writes` may be absent, and
  explicit nil for those keys becomes absence;
- optional fields nested inside a Malli map may be absent, but are nullable only when
  their nested schema explicitly permits nil.

The full Phase-2 snapshot may remain available for existing observability, but its raw
declared values must never overwrite the values that crossed the contract boundary.

## TDD cycles

1. RED: mixed valid + required-nil terminal output succeeds. GREEN: it fails with
   rejected-write evidence, no canonical writes, and failed projection read-back.
2. RED: a string-keyed nested value validates after decoding but is restored raw.
   GREEN: the normalized declared value is returned and projected.
3. RED/GREEN: top-level `:optional-writes` nil is absent and never durable while an
   unnamed required nil still fails.
4. RED/GREEN: terminal `final!` may omit a top-level optional write without retrying
   to exhaustion; the meaningful-work guard remains intact.
5. RED/GREEN: omitted nested optional field succeeds; explicit nil fails unless the
   nested schema is explicitly nullable, and an all-nil present map is not erased.
6. RED/GREEN: a simulated recursive success missing a required write fails with
   durable rejected evidence at the same boundary.
7. RED/GREEN: malformed `:optional-writes` entries outside a node's own write
   contract cannot suppress unrelated keys from the delivered completion shape.

## Do not touch

- Existing worktrees under `/Users/darylroberts/Desktop/Code/orc*`.
- Survey or ontology schemas; no Survey-specific failure has been reproduced.
- Committed benchmark traces; they contain no top-level researcher completions.
- Tool-contract or tool-gate behavior except documentation of the existing fail-closed
  compatibility change.

## Disciplines

- **Never assume. Chase every bug to its ROOT CAUSE.** No band-aids, no "probably," no jumping to conclusions. Reproduce → minimize → fix the actual cause. Don't blame the network or the model — the cause is in the code or the setup. And rule out the *harness itself* (a stuck flag, a stale fixture, a tool that stores results oddly can fake a symptom): distinguish "the work is wrong" from "my measurement is wrong."
- **TDD for real logic: red → green → refactor, one test at a time.** Vertical tracer-bullet slices, never horizontal (don't write all tests then all code). Test **behavior through public interfaces**, so tests survive refactors.
- **Injected-capability seam pattern.** Keep logic pure and testable; inject effects (clock, RNG, camera, network, share, storage, QR) as capabilities that **default to the real impl and are faked in tests**. This also dodges React-compiler purity lints on `Date.now()`/`Math.random()`.
- **Durable tests AND live QA.** A passing unit test is necessary, not sufficient — also drive the real thing (browser/MCP), and a **real device** for hardware paths (camera, share sheet). Then turn what you verified into a durable test so it's guarded on every run.
- **Dispatch sub-work to fresh agents, then INDEPENDENTLY and ADVERSARIALLY verify it.** Never trust a subagent's "done / all green" report — re-run the proof, re-read the code, try to break the claims, demand proof. **Run the `/inspect` skill** for this.
- **Report faithfully** — including your own mis-steps and anything you couldn't verify.
