# What agent research adds to authored trees

Read this with [BT assembly](bt-assembly.md) and [authored goal solving](authored-goal-solving.md). The mappings below are design inferences, not claims that ORC implements these research systems or inherits their benchmark results.

## Voyager: assess what execution accomplished

[Voyager](https://arxiv.org/html/2305.16291v2), §§2.2–2.3, 3.2–3.4, 4 and Appendix A.5/B.2, combines reusable executable skills, task selection, execution feedback and a separate success critic. The critic receives the task and resulting environment state, and can return failure with guidance. Its prompt excludes state irrelevant to that assessment. Refinement is bounded; an unsolved task can be deferred. Verified skills are retained for reuse. The authors report gains over their adapted Minecraft baselines, including ReAct, and acknowledge critic mistakes. Those results do not establish universal superiority over present-day tool loops.

**Authored ORC application:** run a known specialist, observe its actual artifact and effect receipts, then decide whether the parent goal is satisfied. If not, select an eligible resolver or repair behavior and carry the unresolved criterion back. Keep already accepted work. Described registered child workflows supply reusable capabilities in this scope; runtime code generation and an automatic curriculum are separate capabilities.

## MASS: validate specialists and their composition

[Multi-Agent Design](https://arxiv.org/pdf/2502.02533), §§2–4 and Appendix B.3/C.2, studies prompts and topology jointly. Its search improves individual building blocks, chooses useful compositions, then improves prompts in the assembled workflow. Blocks include aggregation, reflection, debate, summarization and tools. Some added blocks degrade results; the best arrangement depends on task and model. Its construction rules use a bounded ordered search space, not unrestricted recursive runtime routing. Its results support evaluating component quality and interactions, not assuming more agents always help.

**Authored ORC application:** first prove each specialist's goal and context contract. Parallelize independent obligations or deliberately diverse candidates; give them separate output keys. Join their actual results, then assess the combined parent obligation. A failed combined criterion should identify the responsible result/dependency and route only the relevant work for revision. Adding branches without a useful join or acceptance policy increases work without establishing better answers.

## Decisions after an entire subtree

A completion decision can follow a single specialist, a multi-step child or a parallel join. Its inputs are the original goal, current relevant constraints, actual returned artifacts, coverage, source versions and effect receipts. Its described finite answers can be:

- `satisfied`: the named parent postcondition is established.
- `revise`: an existing artifact fails a named criterion.
- `consult`: an eligible specialist can supply a missing prerequisite.
- `wait`: outside input or authorization is required.
- `handoff`: the available methods or budget cannot settle the goal.

Use `llm-decision` with an ordinary LLM or a supported native decision provider. A score-only model can select a known route or failed criterion; an ordinary LLM is useful when the next specialist needs a newly written question or critique. Scores and probabilities do not supply missing evidence. A host still validates identities, authority, freshness, budgets and effects. The controller owns continuation; an asynchronously attached quality judge does not itself re-enter the current tree.

This check creates a feedback path when connected to a repeated authored controller. Recursive composition alone does not create that path, continuously poll state or guarantee convergence. Stop only for established success or an explicit useful unresolved outcome, and keep runtime limits as backstops.
