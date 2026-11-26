# ORC skills: contents and starting points

ORC’s bundled authoring skills help humans and coding agents design, build and operate workflows. This repository is the maintained source of the public collection. Read only the skills relevant to your task, then follow their focused references as needed; this is not a mandatory sequence of stages.

## Point your agent here

Give your agent this instruction from an ORC checkout (or provide the checkout path from your application):

> Read ORC’s `orc-skills/CONTENTS.md`, select the guidance relevant to this task, and check the consuming ORC revision before using its APIs.

No tool-specific installation is required to read these files. Each folder contains a standard `SKILL.md` entry point. Your tool’s automatic discovery behavior is separate; this collection does not change its configuration. When changing this repository, also follow its root agent instructions. When building a consumer application, follow that application’s instructions and use ORC’s source and principles for framework behavior.

## Choose a skill

| Skill | What it covers and when to use it |
| --- | --- |
| [orc-design-tree](orc-design-tree/SKILL.md) | Design the responsibilities and control flow of a new tree, including success conditions, routing and bounded recovery. |
| [orc-blackboard-contracts](orc-blackboard-contracts/SKILL.md) | Define described, typed inputs and outputs; preserve context, identity and ownership across node and delegate boundaries. |
| [orc-compose-specialists](orc-compose-specialists/SKILL.md) | Assemble reusable delegated trees, revisit specialists and retain completed parallel work. |
| [orc-runtime-bootstrap](orc-runtime-bootstrap/SKILL.md) | Set up a standalone runtime: dependencies, event stores, projections, providers, concurrency and recovery. |
| [orc-grain-application](orc-grain-application/SKILL.md) | Build the application around a tree with guarded commands, events, processors, projections and queries. |
| [orc-model-sio](orc-model-sio/SKILL.md) | Configure models and fallbacks, enforce structured contracts and inspect the actual provider request and response. |
| [orc-decision-models](orc-decision-models/SKILL.md) | Choose finite semantic routes, applicability checks, rankings and native decision providers. |
| [orc-tools](orc-tools/SKILL.md) | Describe, select and invoke tools with typed arguments and explicit effect boundaries. |
| [orc-repl-researcher](orc-repl-researcher/SKILL.md) | Add adaptive recursive research when the strategy or tree shape must be discovered at runtime. |
| [orc-researcher-pattern-reuse](orc-researcher-pattern-reuse/SKILL.md) | Give researchers relevant prior patterns through described catalogs, retrieval and R-inject. |
| [orc-observe-debug](orc-observe-debug/SKILL.md) | Trace durable execution, exact node inputs/outputs and provider attempts to diagnose failures. |
| [orc-judges](orc-judges/SKILL.md) | Design quality rubrics and custom judge trees; calibrate assessments and report their evidence. |
| [orc-prompt-caching](orc-prompt-caching/SKILL.md) | Configure and measure reusable provider prompt prefixes across nodes, delegates, models and concurrent users. |

## Common starting points

- **Build a new project:** start with tree design, blackboard contracts and runtime bootstrap. Add Grain application guidance when domain state or effects must persist.
- **Extend a complex tree:** use specialist composition and tools; consult decisions for finite routing and researchers only where adaptive exploration is needed.
- **Diagnose a failure:** start with observation/debugging, then model/SIO for provider or contract problems and contracts for missing or misowned context.
- **Improve quality or performance:** use judges for quality assessment, prompt caching for prefix reuse, and runtime bootstrap for many-user serving. Cache hits and valid schemas do not establish content correctness.

## Source compatibility and deeper reading

[Source status](SOURCE-STATUS.md) distinguishes supported mainline APIs, historical experiments and unpublished candidates. Inspect the actual loaded ORC/SIO/client revisions before adopting an example; a dependency declaration alone does not prove which code is executing.

[Core ORC knowledge](RESEARCH.md) explains the design rationale and connects the focused references. The [ORC principles](../docs/ORC-PRINCIPLES.md) and [getting-started guide](../docs/GETTING-STARTED.md) remain useful companion documentation.

The public collection includes reusable lessons and qualified historical observations. Private experiment packets, raw model captures, personal worktrees and evaluation campaigns are not bundled or required. Their absence is not a new independent verification of historical results. Future public guidance changes should be reviewed alongside the relevant ORC behavior and compatibility notes.
