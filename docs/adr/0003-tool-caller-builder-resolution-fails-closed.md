# Tool-caller builder resolution fails closed

`repl-researcher` consumers may configure `:tool-caller-fn` with the fully
qualified name of a builder that constructs an execution-scoped tool caller.
The builder is an authorization boundary: it can apply identity, consent,
confinement, and routing rules before either Phase 1 or generated Phase 2 code
calls a tool. Silently replacing a configured but unavailable builder with the
base caller widens permissions precisely when the intended gate is broken.

## Considered options

- **Keep the fail-open fallback** — rejected because a typo, deleted var, or
  missing namespace would run tools without the restriction the consumer
  explicitly configured.
- **Make fallback behavior configurable** — rejected because it makes the
  security meaning of the same durable node configuration depend on another
  escape hatch and preserves a permission-widening mode.
- **Fail the node when the configured builder cannot produce a caller** —
  chosen. Resolution failure, builder failure, and a non-callable result all
  stop execution. ORC-authored resolution and non-callable-result errors name
  the configured builder; an exception thrown by the builder remains the
  builder's original exception.

## Consequences

- Nodes that omit `:tool-caller-fn` remain backward compatible and use the base
  caller supplied by the consumer context.
- Nodes that configure the hook cannot silently bypass it. Inline Phase-1 calls
  and generated Phase-2 code nodes share the same fail-closed boundary.
- The repository contains no committed production workflow with a literal
  builder declaration; current declarations are test fixtures. External
  consumers must ensure their configured fully qualified names resolve in the
  process that executes the node.
