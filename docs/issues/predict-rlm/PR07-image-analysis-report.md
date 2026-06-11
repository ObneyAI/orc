# PR07 — image_analysis hand-authored comparison report

## Parent

`docs/prd/predict-rlm-benchmark-ports.md`

## What to build

Hand-authored markdown report at `development/bench/predict-rlm-comparison/reports/01_image_analysis.md` following the PRD's report skeleton. This is HITL: the author reads the EDN + JSONL trace from PR06 and the predict-rlm sample output, cross-checks against the source image, and writes the analysis.

Sections (skeleton from PRD):

- **Task** — verbatim goal instruction quoted; output schema
- **Inputs** — file name, size, format of the image; identical to predict-rlm's
- **Methodology side-by-side table** — predict-rlm REPL-iterative + dspy.Image vs ORC adaptive tree + `:field-type :image`
- **Metrics table** — predict-rlm reported numbers (from their `output.md`) vs ours (from EDN), delta
- **Tree the model designed** — verbatim `:generated-tree-raw` from EDN
- **Sub-LLM call walkthrough** — for each leaf node in the tree, cite specific entries from `:node-trace` and the JSONL trace
- **Output quality assessment** — hand-authored against the screenshot; does our `:answer` actually look at the image content? Spot-check 3-5 facts the answer claims against the visible content of the image
- **Fidelity caveats** — explicit list of methodology asymmetries
- **Findings** — what the comparison teaches us about ORC's adaptive design vs predict-rlm's REPL pattern for this specific task
- **Reproducibility** — paths to EDN/JSONL, run command verbatim, git SHA at time of run

## Acceptance criteria

- [ ] Report exists at `development/bench/predict-rlm-comparison/reports/01_image_analysis.md`
- [ ] All skeleton sections populated; no placeholder text
- [ ] Metrics table includes predict-rlm reported numbers and our actual numbers from the EDN (tokens, cost estimate, duration)
- [ ] Emitted tree S-expr is quoted verbatim from the EDN's `:generated-tree-raw`
- [ ] Sub-LLM walkthrough cites specific JSONL trace entries (line numbers or event IDs) for each leaf
- [ ] Quality assessment cites at least 3 facts from the model's `:answer` and either confirms or refutes each against the source screenshot
- [ ] Fidelity caveats list non-empty (at minimum: model choice; any methodology asymmetries vs predict-rlm)
- [ ] Reproducibility section names the result files

## Blocked by

PR06
