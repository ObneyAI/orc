# Research foundations for adaptive behavior-tree work

Use these concepts when choosing decomposition, tool orchestration, observation and completion checks. The summaries stand alone; the public primary sources provide deeper context. Paper results describe their evaluated methods and tasks. They do not establish ORC runtime support or guarantee the same quality/cost in a new application.

## Behavior trees: composition and runtime status

Behavior trees organize task switching through reusable actions and control structure. Sequence, fallback and parallel nodes have different status propagation and execution rules; choosing a composite is choosing behavior, not just a diagram layout. Modularity lets one behavior become a child of another. Reactivity depends on the runtime's retick and interruption semantics. Formal robustness/safety arguments require the assumptions of the actions and execution model. [Behavior Trees in Robotics and AI: An Introduction](https://arxiv.org/abs/1709.00084).

**Application to ORC:** write explicit specialist contracts, use sequence for dependencies and parallelism for independent work, and gate completion against the real goal. A fallback needs an alternative whose success satisfies that contract. Inspect ORC's actual asynchronous status/partial/recovery behavior before applying a textbook result. A generated subtree and an authored delegatable specialist remain different integration surfaces.

## Recursive language models: inputs as accessible state

RLM treats long input as a variable in an external execution environment. The model inspects and decomposes it programmatically, retains intermediate state, and invokes model work over relevant portions. This makes inspection and semantic subcalls part of the inference strategy rather than requiring every detail in one prompt. The paper evaluates substantial long-context gains and discusses trajectory/cost variation and limitations; those gains are specific to its experiments. [Recursive Language Models](https://arxiv.org/abs/2512.24601), especially method, trajectory analysis, limitations and negative results.

**Application to ORC:** use profiles and selective reads, preserve source identities, and build typed intermediate artifacts. Let a researcher adapt the method of one goal-owning stage. When it emits a tree, ORC executes that child through its own events and contracts. Repeatedly returning a full source dump to the root model discards much of the intended separation between stored data and model context. Summarization can also discard evidence needed for later exact comparison.

## ReAct: actions change what the model can know

ReAct interleaves reasoning and task actions. Action results supply new observations that let the model update its plan and handle exceptions. Its evaluations include external information access rather than relying solely on internal reasoning. [ReAct: Synergizing Reasoning and Acting in Language Models](https://arxiv.org/abs/2210.03629).

**Application to ORC:** use real tool results and execution evidence to choose subsequent work. Distinguish a planned action, its invocation, its durable outcome and the model's interpretation of that outcome. Natural-language reasoning about a tool call does not prove it executed. Preserve successful observations across focused repairs and verify the final artifact separately.

## LLMCompiler: parallelism follows dependencies

LLMCompiler separates function-call planning, task dispatch and execution to enable parallel calls. Its benchmark reports demonstrate that orchestration choices can affect latency, cost and accuracy. [An LLM Compiler for Parallel Function Calling](https://arxiv.org/abs/2312.04511).

**Application to ORC:** draw producer/consumer dependencies before generating a parallel subtree. Run independent retrieval/status checks together only after their shared reference exists. Put synthesis after the join. A branch that reads a sibling's future output creates a race even if its nodes each work correctly. Measure overlap and effective fan-out instead of inferring concurrency from source syntax.

## CodeAct: executable actions can compose capabilities

CodeAct represents agent actions as executable Python code, allowing multi-turn revision and composition beyond a single fixed tool-call format. Its interpreter and action environment are part of that method. [Executable Code Actions Elicit Better LLM Agents](https://arxiv.org/abs/2402.01030).

**Application to ORC:** generated code leaves can join validated values, perform exact computation and call granted tools. Supply described contracts and explicit capabilities. Preserve code source and inputs so it can be inspected and replayed. Python interpreter capability in a paper does not imply the same namespace access or API support inside ORC's SCI sandbox. Keep semantic interpretation, routing and prose synthesis in model work instead of phrase matching or canned result construction.

## Reflexion: feedback is evidence that needs context

Reflexion uses task feedback and retained reflective language to guide later trials without updating model weights. It studies different feedback sources and incorporation methods. [Reflexion: Language Agents with Verbal Reinforcement Learning](https://arxiv.org/abs/2303.11366).

**Application to ORC:** inspect whether feedback was based on the producer's actual instruction, source inputs and outputs before reusing it. Missing evaluator context can produce an apparently coherent but false diagnosis. Record located failures and retain only supported lessons. A new description, increased confidence or repeated pattern identity does not prove better work on a held-out task.

## Distilled experimental lessons

These are design rules drawn from actual tree, tool and output inspection. They are not benchmark speedup promises or private-project dependencies.

| Observation | General lesson |
|---|---|
| A generated tree and outer campaign reported success while required findings were nil or empty. | Validate the useful deliverable, including nested completeness and source coverage. Created keys and execution success are insufficient. |
| Per-item reviews were correct, but synthesis ran alongside their producer and returned no reviews. | Dependency order belongs in tree structure. Put downstream extraction and synthesis after the join. |
| Corrected collection workflows produced one assessment per input identity for different collection sizes. | Use runtime fan-out and exact identity coverage rather than hardcoding a demonstrated sample count. |
| Typed inner leaves ultimately returned prose at the outer boundary. | Declare and inspect the researcher's own output contract and the actual downstream parsed value. |
| Discovery and retrieval survived a failed status tool; follow-up work reused them. | Repair the failed seam and its dependents. Prove retained results and actual call counts before claiming no replay. |
| A recovery completed after weakening schemas or coercing malformed numbers. | Completion after changing the contract does not prove the original task was solved. Keep strict schemas and fail explicitly on unknown values. |
| A verifier found primitive names in generated source without proving those calls ran. | Capture invocation and returned evidence. Separate missing observation context from missing producer work. |
| Two verification passes returned identical OCR while a discount row was still missed. | Extra calls need specific failure hypotheses and source checks; identical outputs are not independent confirmation. |
| Chunk-local review claimed globally missing clauses. | Confirm absence against the complete source or an explicit global evidence search. |
| Pattern descriptions changed while subsequent requests still contained older guidance. | Verify the complete evidence→description→retrieval→actual request→behavior→quality chain. A version bump alone is insufficient. |

## Turn research into a reusable behavior

1. Record the goal, exact inputs/source identities, actual model and tool contracts, generated source/tree, execution outcomes and delivered artifact.
2. Name the completion checks and concrete failures the method handled.
3. Identify stable reusable structure and the parts that truly depend on runtime discovery.
4. Build a described specialist/tool for the stable method and retain an adaptive researcher only where it provides useful choice.
5. Test changed inputs, legitimate empty results, incomplete output, a deterministic failure and resource limits before claiming generality.

For implementation, continue with [design and completion](design-and-completion.md), [orc-design-tree](../../orc-design-tree/SKILL.md), [orc-tools](../../orc-tools/SKILL.md) and [orc-blackboard-contracts](../../orc-blackboard-contracts/SKILL.md). Public literature informs the design; actual ORC APIs, event projections and live acceptance evidence decide what the implementation supports.
