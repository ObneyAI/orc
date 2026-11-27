## What to build

Restructure `docs/GEPA-GUIDE.md` to lead with GEPA's independence from ontology/Living Descriptions, and to make the judge feedback → reflective mutation chain explicit and visible upfront. The current guide mixes Python GEPA library references with the native Clojure implementation.

## Read first

1. `docs/GEPA-GUIDE.md` — full file
2. `components/gepa-service/deps.edn` — zero ontology dep (the evidence to cite)
3. `components/gepa-service/src/ai/obney/orc/gepa/core/logic.clj` — the feedback→mutation chain
4. `docs/GETTING-STARTED.md` Phase 4 — what was covered there
5. `docs/prd/orc-documentation-overhaul.md`

## Prototype required?

Yes — run `gepa/optimize!` with a small trainset and `make-judge-metric`. Capture `{:status :completed :best-score N :best-candidate {...}}`. Confirm the instruction key is in `:reads`, not inline.

## TDD cycle

- **Red:** GEPA guide's architecture diagram still references the Python GEPA library path. "Zero ontology dependency" not prominently stated. Feedback→mutation chain not visible upfront.
- **Green:** Restructure opening: (1) what GEPA is → (2) zero ontology dep — confirmed from `gepa/deps.edn` → (3) the judge feedback → mutation chain → (4) quick start.
- **Refactor:** Verify `gepa/deps.edn` zero `ontology` dep. Verify `make-judge-metric` consumes `ScoreWithFeedback` shape. Confirm captured output matches doc.

## Acceptance criteria

- [ ] Opening: GEPA is for static `orc/llm` nodes. Independent of ontology and Living Descriptions. Confirmed from `gepa/deps.edn` — cited explicitly.
- [ ] "Orthogonal to `:auto-classify?`" section: GEPA improves static instructions; R-Inject shapes dynamic tree design. They are independent actuators.
- [ ] Judge feedback → mutation chain shown: `ScoreWithFeedback {:score :feedback :dimensions}` → reflective dataset → proposer LLM → new instruction candidate. Cross-reference JUDGE-ARCHITECTURE.md.
- [ ] Instructions-as-data pattern shown and required: instruction must be a blackboard key in `:reads`, not an inline string. Why: GEPA needs to propose variants at that key.
- [ ] `gepa/optimize!` REPL output captured and embedded
- [ ] Python GEPA library references cleaned up — the native Clojure implementation is the path
- [ ] Cross-reference to GETTING-STARTED Phase 4 at top

## Do NOT touch

`docs/JUDGE-ARCHITECTURE.md`. Any component source.

## Live QA the orchestrator runs

Read `components/gepa-service/deps.edn` — confirm zero `ontology` reference. Run a minimal `optimize!` cycle with `make-judge-metric`. Confirm output shape matches doc. Confirm `components/gepa-service/core/metrics.clj` imports only `evaluation.core.judges` (not ontology).

## Blocked by

Wave 1 complete.

## Handoff note

The GEPA guide mentions "libpython-clj2 bridge to real Python GEPA library" in its architecture section — this is the old Python-bridge path. The shipped implementation is native Clojure with exact 1:1 Python GEPA parity. Update the architecture section to describe the native Clojure implementation.