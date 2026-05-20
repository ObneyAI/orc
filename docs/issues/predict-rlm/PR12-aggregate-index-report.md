# PR12 — Aggregate index report

## Parent

`docs/prd/predict-rlm-benchmark-ports.md`

## What to build

Hand-authored markdown at `development/bench/predict-rlm-comparison/reports/00_index.md` that ties together the four ports into a single coherent narrative. Audience: anyone evaluating ORC RLM's generalization claim against predict-rlm.

Sections:

- **Headline summary** — one-paragraph framing of what was compared and the bottom-line finding (claim or qualified claim about generalization + efficiency)
- **Cross-benchmark summary table**:
  | Benchmark | Tokens (us / them) | Cost USD (us / them) | Wall clock | Quality verdict | Tree pattern model designed |
  | --- | --- | --- | --- | --- | --- |
  | contract_comparison_validated | … | … | … | … | … |
  | image_analysis | … | … | … | … | … |
  | document_redaction | … | … | … | … | … |
  | invoice_processing | … | … | … | … | … |
- **Cross-cutting findings** — what holds across all four ports (e.g., specific patterns of tree design the model converged on, where ORC's `:field-type :image` + adaptive tree wins or loses against predict-rlm's REPL-iterative pattern, common failure modes if any)
- **Methodology disclosures** — consolidated list of fidelity caveats from each report (text-mode redaction, deterministic Excel build, single-run-not-averaged, etc.)
- **Generalization claim** — defensible (or qualified) statement: does ORC RLM in `:rlm true` mode adaptively generalize across these task types? What evidence supports/qualifies the claim?
- **Links** — one per individual report (PR07/PR09/PR11 outputs + the existing contract_comparison_validated EDN)
- **Attribution** — predict-rlm is MIT-licensed at commit `<sha>` of https://github.com/Trampoline-AI/predict-rlm; sample inputs and reference outputs copied verbatim under `references/`

## Acceptance criteria

- [ ] Report exists at `development/bench/predict-rlm-comparison/reports/00_index.md`
- [ ] Cross-benchmark summary table populated for all 4 benchmarks
- [ ] Cross-cutting findings paragraph identifies at least 2 generalizable observations
- [ ] Methodology disclosures consolidated from all per-benchmark reports
- [ ] Generalization claim is unambiguous and defensible (or explicitly qualified)
- [ ] Links to individual reports work (relative paths)
- [ ] Attribution section includes the predict-rlm git SHA used as reference

## Blocked by

PR07, PR09, PR11
